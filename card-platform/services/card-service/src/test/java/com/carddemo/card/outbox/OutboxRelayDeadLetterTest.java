package com.carddemo.card.outbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.carddemo.card.config.CardProperties;
import com.carddemo.card.config.ObservabilityConfig.CardLatencyTimers;
import com.carddemo.card.entity.OutboxEventEntity;
import com.carddemo.card.messaging.CardUpdated;
import com.carddemo.card.messaging.EventPublisherPort;
import com.carddemo.card.repository.OutboxEventRepository;
import com.carddemo.events.serde.EventContracts;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Timer;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * Verifies the terminal path of the card outbox relay: what happens to a row whose attempts are
 * spent, and what reaches the dead-letter topic when that happens.
 *
 * <p>No COBOL ancestor. The source answered a record it could not write with the abend routine at
 * {@code app/cbl/COCRDUPC.cbl:L1531-L1537}, which ends the address space and leaves the operator a
 * job log. Naming the row on a topic and carrying on is the target form.
 *
 * <p>Three properties are pinned here, and each one was absent before. A row this relay gives up on
 * is named by exactly one governed {@link com.carddemo.events.DeadLetterEnvelope}. That envelope
 * validates against {@code schemas/dead-letter-v1.json} and carries no field of the payload it
 * describes. A broker that refuses the diagnostic does not leave the row terminal, because the
 * refusal leaves the sweep and the boundary rolls the abandonment back with it.
 */
@DisplayName("Card outbox relay terminal path")
class OutboxRelayDeadLetterTest {

    private static final String ACCOUNT_ID = "00000000077";

    private static final String TOPIC = "card.updated";

    private static final String DEAD_LETTER_TOPIC = "carddemo.dead-letter";

    /**
     * A full Primary Account Number and a card verification value, planted in the stored payload so
     * a diagnostic that copied any part of the payload would be caught by name.
     */
    private static final String SENTINEL_PAN = "4111111111111111";

    private static final String SENTINEL_CVV = "937";

    /**
     * Members of a diagnostic that a run generates, which no scan for a payload value reads.
     */
    private static final List<String> GENERATED_MEMBERS =
            List.of("eventId", "failedEventId", "occurredAt");

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private OutboxEventRepository outboxEvents;
    private EventPublisherPort publisher;
    private Counter eventsPublished;
    private Counter failures;
    private Counter abandoned;
    private Counter deadLettersFailed;
    private TransactionTemplate transactionTemplate;
    private AtomicBoolean insideTransaction;
    private AtomicBoolean boundaryFailed;
    private List<Publication> publications;
    private OutboxRelay relay;

    /** Rows the stubbed store holds, so a re-read inside a later transaction finds them. */
    private final Map<UUID, OutboxEventEntity> stored = new HashMap<>();

    /** Set when one row's transaction was marked for rollback rather than left by a failure. */
    private AtomicBoolean markedForRollback;

    @BeforeEach
    void setUp() {
        outboxEvents = mock(OutboxEventRepository.class);
        publisher = mock(EventPublisherPort.class);
        eventsPublished = mock(Counter.class);
        failures = mock(Counter.class);
        abandoned = mock(Counter.class);
        deadLettersFailed = mock(Counter.class);
        transactionTemplate = mock(TransactionTemplate.class);
        insideTransaction = new AtomicBoolean();
        boundaryFailed = new AtomicBoolean();
        markedForRollback = new AtomicBoolean();
        publications = new ArrayList<>();

        CardLatencyTimers timers = mock(CardLatencyTimers.class);
        when(timers.eventPublish()).thenReturn(mock(Timer.class));
        stored.clear();
        when(outboxEvents.save(any(OutboxEventEntity.class)))
                .thenAnswer(call -> {
                    OutboxEventEntity saved = call.getArgument(0);
                    stored.put(saved.getEventId(), saved);
                    return saved;
                });
        // The relay records each outcome in a transaction of its own and re-reads the row inside it,
        // because the claim has committed by then and saving the copy the claim loaded would write
        // pre-claim state back over it. A store keyed by identifier answers that read with the row
        // the claim saved, so these cases keep asserting against the instance they created.
        when(outboxEvents.findById(any(UUID.class)))
                .thenAnswer(call -> Optional.ofNullable(stored.get(call.getArgument(0))));
        when(outboxEvents.findByRelayStateAndClaimedAtBeforeOrderByClaimedAtAsc(
                any(), any(), any())).thenReturn(List.of());
        when(outboxEvents.claimDueRows(any(), any())).thenReturn(List.of());
        doAnswer(call -> {
            publications.add(new Publication(call.getArgument(0), call.getArgument(1),
                    call.getArgument(2)));
            // The port answers the stage the acknowledgement completes, and outbox/OutboxRelay
            // waits on it. A stub answering null would fail the relay before it ever reached the
            // wait, which is not what any assertion here is about.
            return CompletableFuture.completedFuture(null);
        }).when(publisher).publish(anyString(), anyString(), anyString());

        when(transactionTemplate.execute(any())).thenAnswer(call -> {
            TransactionCallback<?> callback = call.getArgument(0);
            TransactionStatus status = mock(TransactionStatus.class);
            // A refused diagnostic no longer leaves its transaction; it marks that transaction for
            // rollback so only that row goes back and the sweep carries on. Recording the mark is
            // how the rollback stays observable.
            doAnswer(marked -> {
                markedForRollback.set(true);
                return null;
            }).when(status).setRollbackOnly();
            insideTransaction.set(true);
            try {
                return callback.doInTransaction(status);
            } catch (RuntimeException failure) {
                boundaryFailed.set(true);
                throw failure;
            } finally {
                insideTransaction.set(false);
            }
        });

        relay = new OutboxRelay(outboxEvents, publisher, eventsPublished, failures, abandoned,
                deadLettersFailed, timers, TOPIC, transactionTemplate, properties(DEAD_LETTER_TOPIC));
    }

    @Test
    @DisplayName("a row whose attempts are spent is named by one governed dead letter")
    void aRowWhoseAttemptsAreSpentIsNamedByOneGovernedDeadLetter() {
        OutboxEventEntity row = rowAtLastAttempt(CardUpdated.EVENT_TYPE);
        when(outboxEvents.claimDueRows(any(), any())).thenReturn(List.of(row));
        doThrow(new IllegalStateException("broker unavailable")).when(publisher)
                .publish(eq(TOPIC), anyString(), anyString());
        doAnswer(call -> {
            assertFalse(insideTransaction.get(), "terminal meters are recorded after commit");
            return null;
        }).when(abandoned).increment();

        relay.publishPendingEvents();

        assertThat(row.getRelayState()).isEqualTo(OutboxEventEntity.RelayState.ABANDONED);
        assertThat(row.getAttemptCount()).isEqualTo(OutboxEventEntity.MAX_DELIVERY_ATTEMPTS);
        assertThat(publications).singleElement().satisfies(published -> {
            assertThat(published.topic()).isEqualTo(DEAD_LETTER_TOPIC);
            assertThat(published.key()).isEqualTo(ACCOUNT_ID);
            assertThat(EventContracts.violationsOf(EventContracts.DEAD_LETTER, published.payload()))
                    .as("the diagnostic passes the one gate every event of this platform passes")
                    .isEmpty();
        });

        JsonNode envelope = MAPPER.readTree(publications.getFirst().payload());
        assertThat(envelope.path("eventType").asString()).isEqualTo(EventContracts.DEAD_LETTER);
        assertThat(envelope.path("aggregateId").asString()).isEqualTo(ACCOUNT_ID);
        assertThat(envelope.path("terminal").asBoolean()).isTrue();
        assertThat(envelope.path("sourceTopic").asString()).isEqualTo(TOPIC);
        assertThat(envelope.path("failedEventId").asString())
                .isEqualTo(row.getEventId().toString());
        assertThat(envelope.path("failedEventType").asString()).isEqualTo(CardUpdated.EVENT_TYPE);
        assertThat(envelope.path("attemptCount").asInt())
                .isEqualTo(OutboxEventEntity.MAX_DELIVERY_ATTEMPTS);
        assertThat(envelope.path("culprit").asString()).isNotBlank();

        verify(abandoned).increment();
        verify(deadLettersFailed, never()).increment();
        verify(eventsPublished, never()).increment();
    }

    @Test
    @DisplayName("the diagnostic carries no card number and no card verification value")
    void theDiagnosticCarriesNoCardNumberAndNoCardVerificationValue() {
        OutboxEventEntity row = rowAtLastAttempt(CardUpdated.EVENT_TYPE);
        when(outboxEvents.claimDueRows(any(), any())).thenReturn(List.of(row));
        doThrow(new IllegalStateException("broker unavailable")).when(publisher)
                .publish(eq(TOPIC), anyString(), anyString());

        relay.publishPendingEvents();

        assertThat(row.getPayload()).contains(SENTINEL_PAN, SENTINEL_CVV);
        assertThat(publications).singleElement().satisfies(published ->
                assertThat(scannableMembersOf(published.payload()))
                        .as("no field of the payload travels on the diagnostic")
                        .doesNotContain(SENTINEL_PAN)
                        .doesNotContain(SENTINEL_CVV));
    }

    /**
     * Renders one diagnostic without the three members a run generates, so the scan above reads only
     * the members that could carry a payload value.
     *
     * <p>{@code eventId}, {@code failedEventId} and {@code occurredAt} are minted by the relay and by
     * the row, and {@link #aRowWhoseAttemptsAreSpentIsNamedByOneGovernedDeadLetter} measures the
     * second against the row it names. A random identifier carries a three-character sentinel
     * roughly once in a hundred renderings, and a scan that read one would report a leak no code
     * performed.
     *
     * @param diagnostic the published diagnostic
     * @return the same document without those three members
     */
    private static String scannableMembersOf(String diagnostic) {
        ObjectNode scanned = (ObjectNode) MAPPER.readTree(diagnostic);
        scanned.remove(GENERATED_MEMBERS);
        assertThat(scanned.propertyNames()).isNotEmpty();
        return scanned.toString();
    }

    /**
     * Asserts a refused diagnostic rolls its own row back and leaves the sweep running.
     *
     * <p>This asserted that the refusal left the sweep entirely, which rolled the abandonment back by
     * taking the one transaction the whole sweep ran in with it. A performance review rejected that
     * transaction: it held a database connection and every claimed row lock for the sum of the sweep's
     * broker waits. The rollback it bought is kept by marking this row's own short transaction
     * instead, so the abandonment still goes back and every other account of the sweep is still
     * attempted.
     */
    @Test
    @DisplayName("a refused dead letter rolls its own row back and leaves the sweep running")
    void aRefusedDeadLetterRollsItsOwnRowBackAndLeavesTheSweepRunning() {
        OutboxEventEntity row = rowAtLastAttempt(CardUpdated.EVENT_TYPE);
        when(outboxEvents.claimDueRows(any(), any())).thenReturn(List.of(row));
        doThrow(new IllegalStateException("broker unavailable")).when(publisher)
                .publish(anyString(), anyString(), anyString());
        doAnswer(call -> {
            assertFalse(insideTransaction.get(), "a refused diagnostic is counted after the rollback");
            return null;
        }).when(deadLettersFailed).increment();

        relay.publishPendingEvents();

        assertThat(markedForRollback)
                .as("the refusal marks this row's transaction for rollback, which is what undoes the "
                        + "abandonment")
                .isTrue();
        assertThat(boundaryFailed)
                .as("no failure leaves a transaction, so no other account's row is lost with it")
                .isFalse();
        verify(deadLettersFailed).increment();
        verify(abandoned, never()).increment();
        verify(failures, never()).increment();
        verify(eventsPublished, never()).increment();
    }

    @Test
    @DisplayName("a row still in flight is not dead lettered")
    void aRowStillInFlightIsNotDeadLettered() {
        OutboxEventEntity row = row(CardUpdated.EVENT_TYPE);
        when(outboxEvents.claimDueRows(any(), any())).thenReturn(List.of(row));
        doThrow(new IllegalStateException("broker unavailable")).when(publisher)
                .publish(eq(TOPIC), anyString(), anyString());

        relay.publishPendingEvents();

        assertThat(row.getRelayState()).isEqualTo(OutboxEventEntity.RelayState.PENDING);
        assertThat(publications).isEmpty();
        verify(abandoned, never()).increment();
        verify(failures).increment();
    }

    @Test
    @DisplayName("a spent row whose stored type names no topic reports an unresolved destination")
    void aSpentRowWhoseStoredTypeNamesNoTopicReportsAnUnresolvedDestination() {
        OutboxEventEntity row = rowAtLastAttempt("CardRetired");
        when(outboxEvents.claimDueRows(any(), any())).thenReturn(List.of(row));

        relay.publishPendingEvents();

        assertThat(row.getRelayState()).isEqualTo(OutboxEventEntity.RelayState.ABANDONED);
        assertThat(publications).singleElement().satisfies(published -> {
            assertThat(published.topic()).isEqualTo(DEAD_LETTER_TOPIC);
            assertThat(EventContracts.violationsOf(EventContracts.DEAD_LETTER, published.payload()))
                    .isEmpty();
        });

        JsonNode envelope = MAPPER.readTree(publications.getFirst().payload());
        assertThat(envelope.path("sourceTopic").asString()).isEqualTo("no-configured-topic");
        assertThat(envelope.path("failedEventType").asString()).isEqualTo("CardRetired");
        verify(abandoned).increment();
        verify(publisher, never()).publish(eq(TOPIC), anyString(), anyString());
    }

    @Test
    @DisplayName("a stored type outside the governed shape is reported as absent, not as a failure")
    void aStoredTypeOutsideTheGovernedShapeIsReportedAsAbsent() {
        OutboxEventEntity row = rowAtLastAttempt("card-retired");
        when(outboxEvents.claimDueRows(any(), any())).thenReturn(List.of(row));

        relay.publishPendingEvents();

        assertThat(publications).singleElement().satisfies(published ->
                assertThat(EventContracts.violationsOf(EventContracts.DEAD_LETTER,
                        published.payload()))
                        .as("a stored value no contract admits must not cost the diagnostic")
                        .isEmpty());
        assertThat(MAPPER.readTree(publications.getFirst().payload()).path("failedEventType")
                .isNull()).isTrue();
        verify(abandoned).increment();
    }

    @Test
    @DisplayName("a stranded row that exhausts its attempts is dead lettered too")
    void aStrandedRowThatExhaustsItsAttemptsIsDeadLettered() {
        OutboxEventEntity row = rowAtLastAttempt(CardUpdated.EVENT_TYPE);
        row.claim("stopped-instance", Instant.now().minus(Duration.ofMinutes(3L)));
        when(outboxEvents.findByRelayStateAndClaimedAtBeforeOrderByClaimedAtAsc(
                any(), any(), any())).thenReturn(List.of(row));

        relay.publishPendingEvents();

        assertThat(row.getRelayState()).isEqualTo(OutboxEventEntity.RelayState.ABANDONED);
        assertThat(publications).singleElement().satisfies(published -> {
            assertThat(published.topic()).isEqualTo(DEAD_LETTER_TOPIC);
            assertThat(EventContracts.violationsOf(EventContracts.DEAD_LETTER, published.payload()))
                    .isEmpty();
        });
        verify(abandoned).increment();
        verify(failures).increment();
    }

    @Test
    @DisplayName("the destination is the configured name and never a literal")
    void theDestinationIsTheConfiguredNameAndNeverALiteral() {
        OutboxRelay renamed = new OutboxRelay(outboxEvents, publisher, eventsPublished, failures,
                abandoned, deadLettersFailed, timers(), TOPIC, transactionTemplate,
                properties("carddemo.dead-letter.v2"));
        OutboxEventEntity row = rowAtLastAttempt(CardUpdated.EVENT_TYPE);
        when(outboxEvents.claimDueRows(any(), any())).thenReturn(List.of(row));
        doThrow(new IllegalStateException("broker unavailable")).when(publisher)
                .publish(eq(TOPIC), anyString(), anyString());

        renamed.publishPendingEvents();

        assertThat(publications).singleElement().satisfies(published ->
                assertThat(published.topic()).isEqualTo("carddemo.dead-letter.v2"));
    }

    /** One publish the relay made, as the port received it. */
    private record Publication(String topic, String key, String payload) {
    }

    private CardLatencyTimers timers() {
        CardLatencyTimers timers = mock(CardLatencyTimers.class);
        when(timers.eventPublish()).thenReturn(mock(Timer.class));
        return timers;
    }

    /** A row of {@code eventType} that has taken no attempt yet. */
    private OutboxEventEntity row(String eventType) {
        OutboxEventEntity built = new OutboxEventEntity(
                UUID.randomUUID(),
                eventType,
                ACCOUNT_ID,
                "{\"cardNumber\":\"" + SENTINEL_PAN + "\",\"cvv\":\"" + SENTINEL_CVV + "\"}",
                Instant.now());
        // Registered here rather than only on save, because a row recovered from a stranded claim
        // reaches the relay through the stranded finder and is re-read before anything saves it.
        stored.put(built.getEventId(), built);
        return built;
    }

    /**
     * A row of {@code eventType} one failed attempt short of the ceiling, so the relay's own failure
     * is the attempt that abandons it.
     */
    private OutboxEventEntity rowAtLastAttempt(String eventType) {
        OutboxEventEntity row = row(eventType);
        Instant earlier = Instant.now().minus(Duration.ofMinutes(10L));
        for (int attempt = 1; attempt < OutboxEventEntity.MAX_DELIVERY_ATTEMPTS; attempt++) {
            row.recordFailure("earlier attempt", earlier, earlier);
        }
        return row;
    }

    private static CardProperties properties(String deadLetterTopic) {
        return new CardProperties(
                new CardProperties.Api(65536L),
                new CardProperties.Kafka(new CardProperties.Kafka.Topics(TOPIC, deadLetterTopic)),
                new CardProperties.Outbox(new CardProperties.Outbox.Relay(
                        500L, 100, "card-relay", Duration.ofMinutes(2L), 5_000L,
                        Duration.ofSeconds(10L)), 168L),
                new CardProperties.ProcessedEvent(720L, 168L),
                new CardProperties.Retention(3_600_000L),
                new CardProperties.Write(3_000L));
    }
}
