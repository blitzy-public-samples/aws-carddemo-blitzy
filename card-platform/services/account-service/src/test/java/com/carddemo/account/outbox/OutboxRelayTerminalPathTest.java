package com.carddemo.account.outbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.carddemo.account.config.AccountProperties;
import com.carddemo.account.config.KafkaProducerConfig;
import com.carddemo.account.config.ObservabilityConfig;
import com.carddemo.account.config.ObservabilityConfig.AccountMeters;
import com.carddemo.account.entity.OutboxEventEntity;
import com.carddemo.account.entity.OutboxEventEntity.DeadLetterState;
import com.carddemo.account.entity.OutboxEventEntity.RelayState;
import com.carddemo.account.messaging.EventPublisherPort;
import com.carddemo.account.repository.OutboxEventRepository;
import com.carddemo.events.serde.EventContracts;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Limit;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Covers what the relay does with a row it cannot publish: how the failure is recorded, when the row
 * is given up on, and what happens to the terminal diagnostic that names it.
 *
 * <p>The source answer to a write it cannot complete is {@code 9999-ABEND-PROGRAM} at
 * {@code app/cbl/CBTRN02C.cbl:L707-L711}: display one message, move 999 into an abend code, call
 * {@code CEE3ABD}. The address space ends and the operator is left the job log. Naming the row on a
 * topic replaces that, and it only replaces it if the naming actually happens, which is what these
 * tests measure.
 *
 * <p>Two defects are held closed here.
 *
 * <p><b>A poison row froze its own attempt count.</b> The destination of a row was resolved before
 * the per-row failure boundary opened. A stored event type this relay has no topic for therefore
 * threw before any bookkeeping ran, the surrounding transaction rolled the claim back with it, and
 * the row met every later sweep with an unchanged {@code attempt_count}. It could never reach
 * {@link RelayState#ABANDONED}, so it was retried for ever and the rows behind it waited.
 *
 * <p><b>A refused dead letter was a lost dead letter.</b> The diagnostic was dispatched and not
 * awaited, so an unreachable broker looked exactly like a healthy one. The row stayed terminal, the
 * claim query never returned it again, and the only record of a lost account state change was one
 * warning in one container's log.
 */
@DisplayName("OutboxRelay, the terminal path of a row it cannot publish")
class OutboxRelayTerminalPathTest {

    /** Destination of an account state event, as the shipped file names it. */
    private static final String STATE_TOPIC = "account.state-changed";

    /** Destination of a customer context event, as the shipped file names it. */
    private static final String CONTEXT_TOPIC = "customer.context-changed";

    /** Destination every terminal diagnostic reports itself on. */
    private static final String DEAD_LETTER_TOPIC = "carddemo.dead-letter";

    /** Account identifier every row here carries, and every message key. */
    private static final String ACCOUNT_ID = "00000000042";

    /** The moment every sweep in this class reads. */
    private static final Instant NOW = Instant.parse("2099-01-31T23:59:59Z");

    /** An event type no topic is configured for, which is how a poison row arises. */
    private static final String UNCONFIGURED_TYPE = "AccountRetired";

    /** The placeholder a diagnostic reports when the stored type names no topic. */
    private static final String UNRESOLVED_DESTINATION = "no-configured-topic";

    /** Prefix every meter of this service carries. */
    private static final String PREFIX = "carddemo.account.";

    @Nested
    @DisplayName("A stored event type with no configured topic")
    class UnconfiguredEventType {

        @Test
        @DisplayName("records the attempt instead of throwing before the bookkeeping")
        void recordsTheAttemptInsteadOfThrowingFirst() {
            OutboxEventEntity row = freshRow(UNCONFIGURED_TYPE);
            RecordingRepository rows = new RecordingRepository(row);
            OutboxRelay relay = relay(rows, refusing());

            relay.publishPendingEvents();

            assertThat(row.getAttemptCount())
                    .as("the row that names no topic still records the attempt it just failed")
                    .isEqualTo(1);
            assertThat(row.getRelayState()).isEqualTo(RelayState.PENDING);
            assertThat(row.getLastError()).isEqualTo("IllegalArgumentException");
            assertThat(rows.saved).contains(row);
        }

        @Test
        @DisplayName("reaches ABANDONED on schedule and names itself on the dead-letter topic")
        void reachesAbandonedOnScheduleAndNamesItself() {
            OutboxEventEntity row = freshRow(UNCONFIGURED_TYPE);
            RecordingRepository rows = new RecordingRepository(row);
            CapturingPublisher publisher = refusing();
            OutboxRelay relay = relay(rows, publisher);

            for (int sweep = 0; sweep < OutboxEventEntity.MAX_DELIVERY_ATTEMPTS; sweep++) {
                makeDue(row);
                relay.publishPendingEvents();
            }

            assertThat(row.getAttemptCount())
                    .isEqualTo(OutboxEventEntity.MAX_DELIVERY_ATTEMPTS);
            assertThat(row.getRelayState())
                    .as("a poison row is given up on rather than retried for ever")
                    .isEqualTo(RelayState.ABANDONED);
            assertThat(row.getDeadLetterState()).isEqualTo(DeadLetterState.PUBLISHED);
            assertThat(publisher.deadLetters).hasSize(1);
            assertThat(publisher.deadLetters.getFirst().payload())
                    .as("the diagnostic reports the placeholder rather than failing on the lookup")
                    .contains(UNRESOLVED_DESTINATION)
                    .contains(row.getEventId().toString())
                    .contains(UNCONFIGURED_TYPE);
            assertThat(EventContracts.violationsOf(EventContracts.DEAD_LETTER,
                    publisher.deadLetters.getFirst().payload()))
                    .as("the placeholder has to satisfy the sourceTopic pattern of "
                            + "schemas/dead-letter-v1.json, or the one diagnostic this row will "
                            + "ever have is refused by the gate it must pass")
                    .isEmpty();
        }

        @Test
        @DisplayName("carries no payload value into the diagnostic")
        void carriesNoPayloadValueIntoTheDiagnostic() {
            OutboxEventEntity row = new OutboxEventEntity(UUID.randomUUID(), UNCONFIGURED_TYPE,
                    "{\"creditLimit\":\"31415.92\",\"cardNumber\":\"4859452612877065\"}",
                    ACCOUNT_ID, NOW.minusSeconds(60));
            RecordingRepository rows = new RecordingRepository(row);
            CapturingPublisher publisher = refusing();
            OutboxRelay relay = relay(rows, publisher);

            for (int sweep = 0; sweep < OutboxEventEntity.MAX_DELIVERY_ATTEMPTS; sweep++) {
                makeDue(row);
                relay.publishPendingEvents();
            }

            assertThat(publisher.deadLetters).hasSize(1);
            assertThat(publisher.deadLetters.getFirst().payload())
                    .doesNotContain("31415.92")
                    .doesNotContain("4859452612877065");
        }
    }

    @Nested
    @DisplayName("A dead letter the broker refuses")
    class RefusedDeadLetter {

        @Test
        @DisplayName("leaves the obligation standing rather than clearing it on the attempt")
        void leavesTheObligationStanding() {
            OutboxEventEntity row = rowAtItsLastAttempt();
            RecordingRepository rows = new RecordingRepository(row);
            CapturingPublisher publisher = refusingEverything();
            OutboxRelay relay = relay(rows, publisher);

            relay.publishPendingEvents();

            assertThat(row.getRelayState()).isEqualTo(RelayState.ABANDONED);
            assertThat(row.owesDeadLetter())
                    .as("a refused diagnostic is still owed, and that is the whole difference "
                            + "between an abandoned event and a lost one")
                    .isTrue();
            assertThat(row.getDeadLetterPublishedAt()).isNull();
        }

        @Test
        @DisplayName("is offered again on the next sweep, and discharged once the broker answers")
        void isOfferedAgainAndDischargedOnceTheBrokerAnswers() {
            OutboxEventEntity row = rowAtItsLastAttempt();
            RecordingRepository rows = new RecordingRepository(row);
            CapturingPublisher publisher = refusingEverything();
            OutboxRelay relay = relay(rows, publisher);

            relay.publishPendingEvents();
            assertThat(row.owesDeadLetter()).isTrue();
            int attemptsAfterAbandonment = row.getAttemptCount();

            publisher.acceptDeadLetters = true;
            relay.publishPendingEvents();

            assertThat(row.getDeadLetterState()).isEqualTo(DeadLetterState.PUBLISHED);
            assertThat(row.getDeadLetterPublishedAt()).isEqualTo(NOW);
            assertThat(publisher.deadLetters)
                    .as("one refused offer and one accepted offer")
                    .hasSize(2);
            assertThat(row.getAttemptCount())
                    .as("offering a diagnostic is not another attempt on the business event")
                    .isEqualTo(attemptsAfterAbandonment);
        }

        @Test
        @DisplayName("is never offered a third time once acknowledged")
        void isNeverOfferedAgainOnceAcknowledged() {
            OutboxEventEntity row = rowAtItsLastAttempt();
            RecordingRepository rows = new RecordingRepository(row);
            CapturingPublisher publisher = refusingEverything();
            publisher.acceptDeadLetters = true;
            OutboxRelay relay = relay(rows, publisher);

            relay.publishPendingEvents();
            relay.publishPendingEvents();
            relay.publishPendingEvents();

            assertThat(publisher.deadLetters).hasSize(1);
            assertThat(row.getDeadLetterState()).isEqualTo(DeadLetterState.PUBLISHED);
        }
    }

    @Nested
    @DisplayName("The terminal counters")
    class TerminalCounters {

        @Test
        @DisplayName("count a spent row, its refused diagnostic and its acknowledged one apart")
        void countEachTerminalOutcomeApart() {
            OutboxEventEntity row = rowAtItsLastAttempt();
            RecordingRepository rows = new RecordingRepository(row);
            CapturingPublisher publisher = refusingEverything();
            MeterRegistry registry = new SimpleMeterRegistry();
            OutboxRelay relay = relay(rows, publisher,
                    new ObservabilityConfig().accountMeters(registry));

            relay.publishPendingEvents();

            assertThat(count(registry, "outbox.abandoned"))
                    .as("one row given up on")
                    .isEqualTo(1.0D);
            assertThat(count(registry, "dead.letters.failed"))
                    .as("one diagnostic the broker refused")
                    .isEqualTo(1.0D);
            assertThat(count(registry, "dead.letters.published")).isZero();
            assertThat(count(registry, "publish.failed"))
                    .as("one failed attempt against the row, counted once and by the relay alone")
                    .isEqualTo(1.0D);

            publisher.acceptDeadLetters = true;
            relay.publishPendingEvents();

            assertThat(count(registry, "dead.letters.published")).isEqualTo(1.0D);
            assertThat(count(registry, "outbox.abandoned"))
                    .as("the row is given up on once, however often its diagnostic is offered")
                    .isEqualTo(1.0D);
            assertThat(count(registry, "publish.failed"))
                    .as("offering a diagnostic is not a failed attempt on the business event")
                    .isEqualTo(1.0D);
        }

        @Test
        @DisplayName("count one refused publish once, not twice")
        void countOneRefusedPublishOnce() {
            OutboxEventEntity row = freshRow("AccountStateChanged");
            RecordingRepository rows = new RecordingRepository(row);
            MeterRegistry registry = new SimpleMeterRegistry();
            OutboxRelay relay = relay(rows, refusing(),
                    new ObservabilityConfig().accountMeters(registry));

            relay.publishPendingEvents();

            assertThat(count(registry, "publish.failed"))
                    .as("the publisher and the sweep result both counted this once, so a dashboard "
                            + "reported double the real failure rate")
                    .isEqualTo(1.0D);
        }
    }

    /** Reads one counter of this service by its short name. */
    private static double count(MeterRegistry registry, String shortName) {
        return registry.get(PREFIX + shortName).counter().count();
    }

    /** Builds one unpublished row of the supplied event type, due now. */
    private static OutboxEventEntity freshRow(String eventType) {
        return new OutboxEventEntity(UUID.randomUUID(), eventType, "{\"eventType\":\"" + eventType
                + "\"}", ACCOUNT_ID, NOW.minusSeconds(60));
    }

    /**
     * Builds one row whose next failure abandons it.
     *
     * <p>The row reaches that point the way a real row reaches it, by recording one failure fewer
     * than the ceiling.
     *
     * @return a row at {@link OutboxEventEntity#MAX_DELIVERY_ATTEMPTS} minus one attempts
     */
    private static OutboxEventEntity rowAtItsLastAttempt() {
        OutboxEventEntity row = freshRow("AccountStateChanged");
        for (int attempt = 1; attempt < OutboxEventEntity.MAX_DELIVERY_ATTEMPTS; attempt++) {
            row.recordFailure("seeded failure", NOW.minusSeconds(30), NOW.minusSeconds(30));
        }
        return row;
    }

    /** Returns one row to the claim query however many sweeps run, as long as it is claimable. */
    private static void makeDue(OutboxEventEntity row) {
        if (row.getRelayState() == RelayState.PENDING) {
            row.recordFailure(row.getLastError(), NOW.minusSeconds(30), NOW.minusSeconds(30));
        }
    }

    /** A publisher that refuses the two business topics and accepts the dead-letter topic. */
    private static CapturingPublisher refusing() {
        CapturingPublisher publisher = new CapturingPublisher();
        publisher.acceptDeadLetters = true;
        return publisher;
    }

    /** A publisher that refuses every topic, the dead-letter topic included. */
    private static CapturingPublisher refusingEverything() {
        return new CapturingPublisher();
    }

    /** Builds a relay over a throwaway registry. */
    private static OutboxRelay relay(OutboxEventRepository rows, EventPublisherPort publisher) {
        return relay(rows, publisher,
                new ObservabilityConfig().accountMeters(new SimpleMeterRegistry()));
    }

    /** Builds a relay on a fixed clock, one row per claim, and the supplied meters. */
    private static OutboxRelay relay(OutboxEventRepository rows, EventPublisherPort publisher,
            AccountMeters meters) {
        AccountProperties properties = properties();
        return new OutboxRelay(rows, publisher, immediateTransactions(), properties,
                new KafkaProducerConfig(properties).accountEventObjectMapper(), meters,
                Clock.fixed(NOW, ZoneOffset.UTC), "terminal-path-test");
    }

    /** Returns the bound settings block, with the three topic names the shipped file carries. */
    private static AccountProperties properties() {
        return new AccountProperties(
                new AccountProperties.Api(65536L),
                new AccountProperties.Kafka(new AccountProperties.Kafka.Topics(STATE_TOPIC,
                        CONTEXT_TOPIC, "transaction.posted", DEAD_LETTER_TOPIC),
                        new AccountProperties.Kafka.Groups("account-posted")),
                new AccountProperties.Consumer(new AccountProperties.Consumer.Retry(3, 1_000L)),
                new AccountProperties.Outbox(
                        new AccountProperties.Outbox.Relay(500L, 4, "terminal-path-test",
                                Duration.ofSeconds(30L), 5_000L, Duration.ofSeconds(10L)), 168L),
                new AccountProperties.Retention(3_600_000L),
                new AccountProperties.Write(3_000L));
    }

    /** Runs a transaction callback directly, so no transaction manager takes part. */
    private static TransactionTemplate immediateTransactions() {
        return new TransactionTemplate() {

            private static final long serialVersionUID = 1L;

            @Override
            public <T> T execute(TransactionCallback<T> action) {
                return action.doInTransaction(new SimpleTransactionStatus());
            }
        };
    }

    /** One publication this class captured. */
    private record Publication(String topic, String key, String payload) {}

    /**
     * Refuses or accepts a publish per topic, and records every dead letter it was offered.
     *
     * <p>The two business topics are always refused, because every test here is about a row that
     * cannot be published. {@link #acceptDeadLetters} decides whether the broker takes the
     * diagnostic, which is the condition the two defects turn on.
     */
    private static final class CapturingPublisher implements EventPublisherPort {

        /** Whether the broker accepts a diagnostic on this attempt. */
        private boolean acceptDeadLetters;

        /** Every diagnostic this publisher was offered, refused ones included. */
        private final List<Publication> deadLetters = new ArrayList<>();

        @Override
        public CompletionStage<Void> publish(String topic, String key, String payload) {
            if (DEAD_LETTER_TOPIC.equals(topic)) {
                deadLetters.add(new Publication(topic, key, payload));
                if (!acceptDeadLetters) {
                    return CompletableFuture.failedFuture(
                            new IllegalStateException("the broker refused a dead letter"));
                }
                return CompletableFuture.completedFuture(null);
            }
            return CompletableFuture.failedFuture(
                    new IllegalStateException("the broker refused a publish to " + topic));
        }
    }

    /**
     * A repository over one in-memory row, answering the three queries one sweep runs.
     *
     * <p>A mock would need a stub per query per sweep and would not model the row moving between
     * the claim query and the owed-diagnostic query, which is exactly what these tests measure.
     */
    private static final class RecordingRepository implements OutboxEventRepository {

        /** The one row this repository holds. */
        private final OutboxEventEntity row;

        /** Every row this repository was asked to save, in call order. */
        private final List<OutboxEventEntity> saved = new ArrayList<>();

        private RecordingRepository(OutboxEventEntity row) {
            this.row = row;
        }

        @Override
        public List<OutboxEventEntity> claimDueRows(Instant now, Limit limit) {
            return row.getRelayState() == RelayState.PENDING ? List.of(row) : List.of();
        }

        @Override
        public List<OutboxEventEntity> findByRelayStateAndClaimedAtBeforeOrderByClaimedAtAsc(
                RelayState relayState, Instant claimedBefore, Limit limit) {
            return List.of();
        }

        @Override
        public List<OutboxEventEntity> findByDeadLetterStateOrderByLastAttemptAtAsc(
                DeadLetterState deadLetterState, Limit limit) {
            return row.getDeadLetterState() == deadLetterState ? List.of(row) : List.of();
        }

        @Override
        public int deletePublishedBefore(Instant horizon, int limit) {
            return 0;
        }

        @Override
        public boolean existsByRelayState(RelayState relayState) {
            return row.getRelayState() == relayState;
        }

        @Override
        public long countDueBefore(Instant now) {
            return dueAt(now).isPresent() ? 1L : 0L;
        }

        @Override
        public Optional<Instant> findEarliestDueBefore(Instant now) {
            return dueAt(now);
        }

        /**
         * Answers when the one row held here became due, matching the query the interface declares.
         *
         * @param now the instant to read as of
         * @return the instant the row became due, or empty when it is not pending and due
         */
        private Optional<Instant> dueAt(Instant now) {
            boolean due = row.getRelayState() == RelayState.PENDING
                    && !row.getNextAttemptAt().isAfter(now);
            return due ? Optional.of(row.getNextAttemptAt()) : Optional.empty();
        }

        @Override
        public List<OutboxEventEntity> findByPublishedFalseOrderByCreatedAtAscEventIdAsc(
                Limit limit) {
            return row.isPublished() ? List.of() : List.of(row);
        }

        @Override
        public <S extends OutboxEventEntity> S save(S entity) {
            saved.add(entity);
            return entity;
        }

        @Override
        public <S extends OutboxEventEntity> List<S> saveAll(Iterable<S> entities) {
            List<S> stored = new ArrayList<>();
            for (S entity : entities) {
                stored.add(save(entity));
            }
            return stored;
        }

        @Override
        public java.util.Optional<OutboxEventEntity> findById(UUID identifier) {
            return row.getEventId().equals(identifier)
                    ? java.util.Optional.of(row)
                    : java.util.Optional.empty();
        }

        @Override
        public boolean existsById(UUID identifier) {
            return row.getEventId().equals(identifier);
        }

        @Override
        public List<OutboxEventEntity> findAll() {
            return List.of(row);
        }

        @Override
        public List<OutboxEventEntity> findAllById(Iterable<UUID> identifiers) {
            List<OutboxEventEntity> found = new ArrayList<>();
            for (UUID identifier : identifiers) {
                findById(identifier).ifPresent(found::add);
            }
            return found;
        }

        @Override
        public long count() {
            return 1L;
        }

        @Override
        public void deleteById(UUID identifier) {
            throw new UnsupportedOperationException("this repository deletes nothing");
        }

        @Override
        public void delete(OutboxEventEntity entity) {
            throw new UnsupportedOperationException("this repository deletes nothing");
        }

        @Override
        public void deleteAllById(Iterable<? extends UUID> identifiers) {
            throw new UnsupportedOperationException("this repository deletes nothing");
        }

        @Override
        public void deleteAll(Iterable<? extends OutboxEventEntity> entities) {
            throw new UnsupportedOperationException("this repository deletes nothing");
        }

        @Override
        public void deleteAll() {
            throw new UnsupportedOperationException("this repository deletes nothing");
        }
    }
}
