package com.carddemo.card.outbox;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.carddemo.card.CardApplication;
import com.carddemo.card.config.CardProperties;
import com.carddemo.card.config.ObservabilityConfig.CardLatencyTimers;
import com.carddemo.card.entity.OutboxEventEntity;
import com.carddemo.card.messaging.CardUpdated;
import com.carddemo.card.messaging.DeadLetterMetadata;
import com.carddemo.card.messaging.EventPublisherPort;
import com.carddemo.card.repository.OutboxEventRepository;
import com.carddemo.card.repository.ProcessedEventRepository;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Timer;
import java.lang.annotation.Annotation;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.MapPropertySource;
import org.springframework.data.domain.Limit;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Asserts the card outbox relay publishes a row before it marks that row, publishes each row once,
 * and honours the sweep limits its configuration names.
 *
 * <p>No CardDemo program relays an event. The source carries one asynchronous handoff, in paragraph
 * {@code WIRTE-JOBSUB-TDQ} at {@code app/cbl/CORPT00C.cbl:L515}. That paragraph writes one record to
 * a Customer Information Control System (CICS) transient data queue at
 * {@code app/cbl/CORPT00C.cbl:L517-L518}, and a separate job reads the record later.
 *
 * <p>The transactional outbox, the relay's separate transaction and the processed-event marker are
 * declared additive deviations. Every {@code DEFINE FILE} block of {@code app/csd/CARDDEMO.CSD}
 * disables recovery and journalling, eight blocks in all. The card file block spans
 * {@code app/csd/CARDDEMO.CSD:L25-L36}, carrying {@code JOURNAL(NO)} on L31 and
 * {@code RECOVERY(NONE)} on L33. The source answered a duplicate key with the abend routine reached
 * from {@code app/cbl/CBTRN02C.cbl:L562-L579}, and it detected no duplicate at all.
 *
 * <p>{@code @EnableScheduling} on {@link CardApplication} is what makes the sweep fire. A test that
 * waited for a tick without that annotation would never return, so every wait below carries the
 * bounded ceiling {@link #POLL_CEILING} names.
 *
 * <p>Every test drives mocked collaborators, so no database and no message broker has to run. The
 * sibling classes of this package cover the claim lock and the dead-letter envelope; the tests here
 * cover the publication contract.
 *
 * <p>The message path is drawn in {@code card-platform/docs/event-flow.md}.
 *
 * <p>Decisions: {@code card-platform/docs/decision-log.md}.
 */
@DisplayName("Card outbox relay")
class OutboxRelayTest {

    /**
     * An eleven-digit account identifier, the width {@code XREF-ACCT-ID PIC 9(11)} at
     * {@code app/cpy/CVACT03Y.cpy:L7} fixes. The two leading zeros belong to the value.
     */
    private static final String ACCOUNT_ID = "00000000077";

    /** A second account identifier of the same width, used where two rows must differ. */
    private static final String OTHER_ACCOUNT_ID = "00000000078";

    /** One stored payload, already serialized as JavaScript Object Notation (JSON) text. */
    private static final String PAYLOAD = "{\"eventType\":\"CardUpdated\"}";

    /** The longest any wait in this class may run before it fails and names the reason. */
    private static final Duration POLL_CEILING = Duration.ofSeconds(10L);

    /** How often a bounded wait re-reads the state it waits on. */
    private static final Duration POLL_INTERVAL = Duration.ofMillis(20L);

    /** The sweep delay the scheduled test resolves {@link #SWEEP_DELAY_KEY} to. */
    private static final long QUICK_SWEEP_DELAY_MS = 40L;

    /** The property the relay schedule reads, and the key a hard-coded delay would ignore. */
    private static final String SWEEP_DELAY_KEY = "carddemo.outbox.relay.fixed-delay-ms";

    /** The shipped {@code carddemo} block, bound from {@code src/main/resources/application.yml}. */
    private static CardProperties shipped;

    /** The shipped {@code spring.kafka.producer.acks}, read from the same file. */
    private static String shippedAcks;

    /** The shipped {@code spring.kafka.producer.properties.enable.idempotence}. */
    private static String shippedIdempotence;

    /** The shipped {@code spring.kafka.producer.value-serializer}. */
    private static String shippedValueSerializer;

    /** The shipped sweep delay as text, exactly as the schedule placeholder resolves it. */
    private static String shippedSweepDelay;

    /** Holds the rows one sweep can see, and answers the two queries the relay issues. */
    private RelayStore store;

    /** Records what reached the publish port, and the mark state at the moment of each send. */
    private RecordingPublisher publisher;

    /** Reports whether the sweep boundary is open, and how many times it opened. */
    private BoundaryProbe boundary;

    /** Counts events the broker accepted, from {@code carddemo.card.events.published}. */
    private Counter eventsPublished;

    /** Counts attempts that failed, from {@code carddemo.card.failures}. */
    private Counter infrastructureFailures;

    /** Counts rows the relay gave up on, from {@code carddemo.card.outbox.abandoned}. */
    private Counter outboxAbandoned;

    /** Counts refused terminal diagnostics, from {@code carddemo.card.dead.letters.failed}. */
    private Counter deadLettersFailed;

    /** Times one publish attempt, from {@code carddemo.card.publish.latency}. */
    private Timer publishLatency;

    /** The relay under test, built over the fixture above. */
    private OutboxRelay relay;

    /**
     * Binds the shipped configuration once and keeps the values every test reads.
     *
     * <p>{@code src/main/resources/application.yml} sits on the test classpath, so the values below
     * are the ones a running container would use. A key renamed on one side alone fails here.
     */
    @BeforeAll
    static void loadShippedConfiguration() {
        AtomicReference<CardProperties> properties = new AtomicReference<>();
        AtomicReference<Map<String, String>> kafka = new AtomicReference<>();

        new ApplicationContextRunner()
                .withInitializer(new ConfigDataApplicationContextInitializer())
                .withUserConfiguration(PropertiesEnabled.class)
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    properties.set(context.getBean(CardProperties.class));
                    kafka.set(Map.of(
                            "acks", producerSetting(context.getEnvironment()
                                    .getProperty("spring.kafka.producer.acks")),
                            "idempotence", producerSetting(context.getEnvironment().getProperty(
                                    "spring.kafka.producer.properties.enable.idempotence")),
                            "value-serializer", producerSetting(context.getEnvironment()
                                    .getProperty("spring.kafka.producer.value-serializer")),
                            "sweep-delay", producerSetting(context.getEnvironment()
                                    .getProperty(SWEEP_DELAY_KEY))));
                });

        shipped = properties.get();
        assertThat(shipped)
                .withFailMessage("the shipped application.yml must bind into CardProperties")
                .isNotNull();

        Map<String, String> settings = kafka.get();
        shippedAcks = settings.get("acks");
        shippedIdempotence = settings.get("idempotence");
        shippedValueSerializer = settings.get("value-serializer");
        shippedSweepDelay = settings.get("sweep-delay");
    }

    /**
     * Reports one resolved property, naming the absence when a key resolves to nothing.
     *
     * @param value the property the environment resolved, or null when the key is absent
     * @return the value, or a marker naming the absent key
     */
    private static String producerSetting(String value) {
        return value == null ? "<absent>" : value;
    }

    @BeforeEach
    void setUp() {
        store = new RelayStore();
        boundary = new BoundaryProbe();
        publisher = new RecordingPublisher(store, boundary);
        eventsPublished = mock(Counter.class);
        infrastructureFailures = mock(Counter.class);
        outboxAbandoned = mock(Counter.class);
        deadLettersFailed = mock(Counter.class);
        publishLatency = mock(Timer.class);
        relay = relayOver(store.asRepository(), publisher, shipped);
    }

    /**
     * @param rows       the store the sweep reads and marks
     * @param sends      the publish port the sweep reaches
     * @param properties the bound {@code carddemo} block the relay reads its limits from
     * @return one relay wired to the five meters of this fixture
     */
    private OutboxRelay relayOver(OutboxEventRepository rows, EventPublisherPort sends,
            CardProperties properties) {

        CardLatencyTimers timers = mock(CardLatencyTimers.class);
        when(timers.eventPublish()).thenReturn(publishLatency);
        return new OutboxRelay(rows, sends, eventsPublished, infrastructureFailures, outboxAbandoned,
                deadLettersFailed, timers, properties.kafka().topics().cardUpdated(),
                boundary.asTemplate(), properties);
    }

    /**
     * @param accountId the eleven-digit account identifier, which becomes the message key
     * @param createdAt when the writer stored the row, and when the row first falls due
     * @return one row in {@link OutboxEventEntity.RelayState#PENDING}
     */
    private static OutboxEventEntity row(String accountId, Instant createdAt) {
        return new OutboxEventEntity(UUID.randomUUID(), CardUpdated.EVENT_TYPE, accountId, PAYLOAD,
                createdAt);
    }

    /**
     * @return one row in {@link OutboxEventEntity.RelayState#PENDING}
     */
    private static OutboxEventEntity row() {
        return row(ACCOUNT_ID, Instant.now());
    }

    /**
     * Asserts the sweep runs in a transaction of its own, and that the meters stay outside it.
     */
    @Nested
    @DisplayName("Separate transaction")
    class SeparateTransaction {

        /**
         * One sweep opens a claim boundary and one boundary per outcome, and sends inside neither.
         *
         * <p>The writer stores its row in the transaction that changed the card. The send happens
         * here, after that transaction closed, which is the split
         * {@code app/cbl/CORPT00C.cbl:L517-L518} already had between a queue write and the job that
         * read it.
         *
         * <p>One boundary around the whole sweep is what this asserted before, and a performance
         * review rejected it: a batch of rows waiting on a broker inside one transaction holds a
         * database connection and every row lock it took for the sum of those waits, and every later
         * event of every account waits behind it. Three boundaries here are the stranded-claim read,
         * the claim, and the mark of the one row this sweep holds, and the send sits between them
         * rather than inside any of them. The first two take row locks and so need a transaction at
         * all; the third writes the publication.
         */
        @Test
        @DisplayName("one sweep opens a boundary per step, and sends inside none of them")
        void oneSweepOpensABoundaryPerStepAndSendsInsideNoneOfThem() {
            OutboxEventEntity waiting = row();
            store.add(waiting);
            assertThat(waiting.isPublished())
                    .withFailMessage("a stored row waits unpublished for the relay")
                    .isFalse();

            relay.publishPendingEvents();

            assertThat(boundary.openings()).isEqualTo(3);
            assertThat(publisher.sends()).hasSize(1);
            assertThat(publisher.sends().getFirst().insideBoundary())
                    .withFailMessage("no send may wait on a broker inside a transaction")
                    .isFalse();
            assertThat(waiting.isPublished()).isTrue();
        }

        /**
         * Every meter is recorded after the boundary closes.
         *
         * <p>A counter takes no part in a database transaction, so an increment made inside one
         * would survive a rollback and report a row as published that carries no mark.
         */
        @Test
        @DisplayName("the meters are recorded after the boundary closes")
        void theMetersAreRecordedAfterTheBoundaryCloses() {
            store.add(row());
            AtomicBoolean openAtIncrement = new AtomicBoolean(true);
            AtomicBoolean openAtTiming = new AtomicBoolean(true);
            doAnswer(call -> {
                openAtIncrement.set(boundary.isOpen());
                return null;
            }).when(eventsPublished).increment();
            doAnswer(call -> {
                openAtTiming.set(boundary.isOpen());
                return null;
            }).when(publishLatency).record(anyLong(), any());

            relay.publishPendingEvents();

            assertThat(openAtIncrement).isFalse();
            assertThat(openAtTiming).isFalse();
            verify(eventsPublished).increment();
        }

        /**
         * A store the relay cannot read counts one failure and marks nothing.
         *
         * <p>The source answered an unwritable record with the abend routine reached from
         * {@code app/cbl/CBTRN02C.cbl:L577}. The sweep records the failure and the schedule carries
         * on.
         */
        @Test
        @DisplayName("an unreadable store counts one failure and marks nothing")
        void anUnreadableStoreCountsOneFailureAndMarksNothing() {
            store.add(row());
            store.failEveryClaimWith(new IllegalStateException("store unavailable"));

            relay.publishPendingEvents();

            assertThat(publisher.sends()).isEmpty();
            assertThat(store.marks()).isEmpty();
            verify(infrastructureFailures).increment();
            verify(eventsPublished, never()).increment();
        }

        /**
         * The relay holds no reference to the writer that stored the row.
         *
         * <p>The two components share the table and nothing else, so no writer transaction can
         * reach the mark this relay writes.
         */
        @Test
        @DisplayName("the relay holds no reference to the writer")
        void theRelayHoldsNoReferenceToTheWriter() {
            List<Class<?>> fieldTypes = new ArrayList<>();
            for (Field field : OutboxRelay.class.getDeclaredFields()) {
                fieldTypes.add(field.getType());
            }

            assertThat(fieldTypes).doesNotContain(OutboxWriter.class);
        }
    }

    /**
     * Asserts the relay sends a row before it marks that row, and marks nothing it failed to send.
     */
    @Nested
    @DisplayName("Publish then mark")
    class PublishThenMark {

        /**
         * The row is unmarked during its send and marked once the sweep returns.
         *
         * <p>A process that stops between the send and the mark sends the row again on a later
         * sweep, so delivery is at least once. A mark written first would lose the event outright.
         */
        @Test
        @DisplayName("the row carries no mark during its own send")
        void theRowCarriesNoMarkDuringItsOwnSend() {
            OutboxEventEntity waiting = row();
            store.add(waiting);

            relay.publishPendingEvents();

            assertThat(publisher.sends()).hasSize(1);
            assertThat(publisher.sends().getFirst().publishedAtSend())
                    .withFailMessage("the relay must send before it marks")
                    .doesNotContain(waiting.getEventId());
            assertThat(waiting.isPublished()).isTrue();
            assertThat(store.marks()).containsExactly(waiting.getEventId());
        }

        /**
         * No row of a batch carries a mark while any send of its window is still outstanding.
         *
         * <p>Two rows of two accounts make the ordering measurable per row. Both sends are issued
         * before either is waited for, which is what lets the two accounts travel at once, so neither
         * send can see any mark. The marks still follow in claim order once the acknowledgements
         * arrive.
         *
         * <p>Before the window existed, this asserted that the second send saw the first row already
         * marked. That was the sequential shape: one send, one mark, then the next send, which made
         * one slow account the pace of every account behind it.
         */
        @Test
        @DisplayName("no row of a batch carries a mark during any send of its window")
        void noRowOfABatchCarriesAMarkDuringAnySendOfItsWindow() {
            Instant start = Instant.now().minusSeconds(60L);
            OutboxEventEntity first = row(ACCOUNT_ID, start);
            OutboxEventEntity second = row(OTHER_ACCOUNT_ID, start.plusSeconds(1L));
            store.add(first, second);

            relay.publishPendingEvents();

            List<Send> sends = publisher.sends();
            assertThat(sends).hasSize(2);
            assertThat(sends.get(0).publishedAtSend()).isEmpty();
            assertThat(sends.get(1).publishedAtSend()).isEmpty();
            assertThat(store.marks()).containsExactly(first.getEventId(), second.getEventId());
        }

        /**
         * A refused send leaves the row unmarked.
         *
         * <p>The same ordering seen from the failure side: the mark follows the send, so a send that
         * never succeeded writes no mark.
         */
        @Test
        @DisplayName("a refused send leaves the row unmarked")
        void aRefusedSendLeavesTheRowUnmarked() {
            OutboxEventEntity waiting = row();
            store.add(waiting);
            publisher.failSendsTo(shipped.kafka().topics().cardUpdated(),
                    new IllegalStateException("broker unavailable"), 1);

            relay.publishPendingEvents();

            assertThat(waiting.isPublished()).isFalse();
            assertThat(waiting.getPublishedAt()).isNull();
            assertThat(store.marks()).isEmpty();
        }
    }

    /**
     * Asserts one row reaches the broker once, and that a batch travels in due order.
     */
    @Nested
    @DisplayName("Publish once")
    class PublishOnce {

        /**
         * Two sweeps over one row produce one send and one mark.
         *
         * <p>The claim query filters on {@link OutboxEventEntity.RelayState#PENDING}, so the second
         * sweep selects nothing. The source detected no duplicate at all: a replayed feed drove the
         * write at {@code app/cbl/CBTRN02C.cbl:L564} to a duplicate key and on to the abend routine
         * reached from {@code app/cbl/CBTRN02C.cbl:L577}.
         */
        @Test
        @DisplayName("two sweeps over one row send once and mark once")
        void twoSweepsOverOneRowSendOnceAndMarkOnce() {
            OutboxEventEntity waiting = row();
            store.add(waiting);

            relay.publishPendingEvents();
            relay.publishPendingEvents();

            assertThat(publisher.sends()).hasSize(1);
            assertThat(store.marks()).containsExactly(waiting.getEventId());
            assertThat(waiting.getRelayState())
                    .isEqualTo(OutboxEventEntity.RelayState.PUBLISHED);
            verify(eventsPublished, times(1)).increment();
        }

        /**
         * The second sweep claims no row at all.
         *
         * <p>The claim filters on {@link OutboxEventEntity.RelayState#PENDING}, so a published row
         * leaves the working set. A claim that returned the row again would count a failure, since
         * {@code entity/OutboxEventEntity.claim} refuses a terminal row.
         */
        @Test
        @DisplayName("the second sweep claims no row")
        void theSecondSweepClaimsNoRow() {
            store.add(row());

            relay.publishPendingEvents();
            relay.publishPendingEvents();

            assertThat(store.claimedCounts()).containsExactly(1, 0);
            assertThat(publisher.sends()).hasSize(1);
            // Five boundaries across the two sweeps: each sweep reads stranded claims and claims due
            // rows, and only the first sweep finds a row to mark.
            assertThat(boundary.openings()).isEqualTo(5);
        }

        /**
         * An idle sweep records no failure.
         *
         * <p>A card list and a card read store no row, so a sweep with nothing to do is the ordinary
         * case and has to stay quiet on the meters.
         */
        @Test
        @DisplayName("an idle sweep records no failure")
        void anIdleSweepRecordsNoFailure() {
            store.add(row());

            relay.publishPendingEvents();
            relay.publishPendingEvents();

            verify(infrastructureFailures, never()).increment();
            verify(outboxAbandoned, never()).increment();
            verify(deadLettersFailed, never()).increment();
            verify(eventsPublished, times(1)).increment();
        }

        /**
         * A batch travels oldest due time first.
         *
         * <p>The writer stamps each row as it stores it, and every message carries the account
         * identifier as its key, so one account's events stay on one partition in commit order.
         */
        @Test
        @DisplayName("a batch travels oldest due time first")
        void aBatchTravelsOldestDueTimeFirst() {
            Instant start = Instant.now().minusSeconds(300L);
            OutboxEventEntity third = row(ACCOUNT_ID, start.plusSeconds(20L));
            OutboxEventEntity first = row(ACCOUNT_ID, start);
            OutboxEventEntity second = row(ACCOUNT_ID, start.plusSeconds(10L));
            store.add(third, first, second);

            relay.publishPendingEvents();

            assertThat(store.marks()).containsExactly(first.getEventId(), second.getEventId(),
                    third.getEventId());
        }
    }

    /**
     * Asserts the relay reads its batch size and its sweep delay from configuration.
     */
    @Nested
    @DisplayName("Configured limits")
    class ConfiguredLimits {

        /**
         * The relay asks the store for exactly the configured batch size.
         *
         * <p>The value read here is {@code carddemo.outbox.relay.batch-size} of
         * {@code src/main/resources/application.yml}, bound through {@link CardProperties}.
         */
        @Test
        @DisplayName("the claim asks for the configured batch size")
        void theClaimAsksForTheConfiguredBatchSize() {
            int configured = shipped.outbox().relay().batchSize();
            store.add(row());

            relay.publishPendingEvents();

            assertThat(store.claimLimits()).containsExactly(Limit.of(configured));
        }

        /**
         * One sweep publishes the configured count and leaves the rest waiting.
         *
         * <p>The store holds one row more than the limit allows. The surplus row stays in
         * {@link OutboxEventEntity.RelayState#PENDING} and falls to the next tick.
         */
        @Test
        @DisplayName("a sweep publishes the configured count and leaves the surplus waiting")
        void aSweepPublishesTheConfiguredCountAndLeavesTheSurplusWaiting() {
            int configured = smallBatch().outbox().relay().batchSize();
            OutboxRelay limited = relayOver(store.asRepository(), publisher, smallBatch());
            Instant start = Instant.now().minusSeconds(600L);
            List<OutboxEventEntity> written = new ArrayList<>();
            for (int index = 0; index <= configured; index++) {
                OutboxEventEntity waiting = row(ACCOUNT_ID, start.plusSeconds(index));
                written.add(waiting);
                store.add(waiting);
            }

            limited.publishPendingEvents();

            assertThat(written).hasSize(configured + 1);
            assertThat(publisher.sends()).hasSize(configured);
            assertThat(store.marks()).hasSize(configured);
            assertThat(written.getLast().isPublished())
                    .withFailMessage("the surplus row waits for the next tick")
                    .isFalse();
            assertThat(written.getLast().getRelayState())
                    .isEqualTo(OutboxEventEntity.RelayState.PENDING);
        }

        /**
         * The surplus row of one sweep is published by the next sweep.
         *
         * <p>Nothing is lost when a batch fills. The row the limit held back is claimed on the
         * following tick.
         */
        @Test
        @DisplayName("the next sweep publishes the surplus row")
        void theNextSweepPublishesTheSurplusRow() {
            int configured = smallBatch().outbox().relay().batchSize();
            OutboxRelay limited = relayOver(store.asRepository(), publisher, smallBatch());
            Instant start = Instant.now().minusSeconds(600L);
            List<OutboxEventEntity> written = new ArrayList<>();
            for (int index = 0; index <= configured; index++) {
                OutboxEventEntity waiting = row(ACCOUNT_ID, start.plusSeconds(index));
                written.add(waiting);
                store.add(waiting);
            }

            limited.publishPendingEvents();
            limited.publishPendingEvents();

            assertThat(publisher.sends()).hasSize(configured + 1);
            assertThat(written.getLast().isPublished()).isTrue();
            assertThat(store.marks()).hasSize(configured + 1);
        }

        /**
         * The sweep schedule names the delay property and no literal.
         *
         * <p>The annotation carries {@code fixedDelayString}, which resolves
         * {@link #SWEEP_DELAY_KEY}. A populated {@code fixedDelay} would ignore that key outright.
         *
         * @throws NoSuchMethodException if the scheduled method is renamed
         */
        @Test
        @DisplayName("the schedule reads the delay property and holds no literal delay")
        void theScheduleReadsTheDelayProperty() throws NoSuchMethodException {
            Method sweep = OutboxRelay.class.getMethod("publishPendingEvents");
            Scheduled schedule = sweep.getAnnotation(Scheduled.class);

            assertThat(schedule).isNotNull();
            assertThat(schedule.fixedDelayString()).isEqualTo("${" + SWEEP_DELAY_KEY + "}");
            assertThat(schedule.fixedDelay())
                    .withFailMessage("a literal delay would ignore %s", SWEEP_DELAY_KEY)
                    .isEqualTo(-1L);
        }

        /**
         * The shipped configuration resolves the sweep delay to 500 milliseconds.
         *
         * <p>Both the bound record and the raw property are read, so the placeholder the schedule
         * resolves and the value the relay reads are proven to agree.
         */
        @Test
        @DisplayName("the shipped sweep delay is 500 milliseconds")
        void theShippedSweepDelayIsFiveHundredMilliseconds() {
            assertThat(shipped.outbox().relay().fixedDelayMs()).isEqualTo(500L);
            assertThat(shippedSweepDelay).isEqualTo("500");
        }

        /**
         * The shipped configuration resolves the batch size to 100 rows.
         */
        @Test
        @DisplayName("the shipped batch size is 100 rows")
        void theShippedBatchSizeIsOneHundredRows() {
            assertThat(shipped.outbox().relay().batchSize()).isEqualTo(100);
        }

        /**
         * @return the shipped block with a batch size of three
         */
        private CardProperties smallBatch() {
            CardProperties.Outbox.Relay tuned = new CardProperties.Outbox.Relay(
                    shipped.outbox().relay().fixedDelayMs(), 3,
                    shipped.outbox().relay().instanceId(), shipped.outbox().relay().claimTimeout(),
                    shipped.outbox().relay().maxDurationMs(),
                    shipped.outbox().relay().publishTimeout());
            return new CardProperties(shipped.api(), shipped.kafka(),
                    new CardProperties.Outbox(tuned, shipped.outbox().publishedRetentionHours()),
                    shipped.retention(), shipped.write());
        }
    }

    /**
     * Asserts the schedule fires the sweep once scheduling is enabled.
     */
    @Nested
    @DisplayName("Scheduled poll")
    class ScheduledPoll {

        /**
         * {@link CardApplication} carries the annotation that starts the sweep.
         *
         * <p>Without it the relay is inert, the row stays unpublished, and a test that waited for a
         * tick would never return.
         */
        @Test
        @DisplayName("CardApplication enables scheduling")
        void cardApplicationEnablesScheduling() {
            assertThat(CardApplication.class.getAnnotation(EnableScheduling.class))
                    .withFailMessage("@EnableScheduling on CardApplication is what starts the sweep")
                    .isNotNull();
        }

        /**
         * A row left alone is published by the schedule inside a bounded wait.
         *
         * <p>The context below enables scheduling and resolves {@link #SWEEP_DELAY_KEY} to
         * {@value #QUICK_SWEEP_DELAY_MS} milliseconds. The wait fails after ten seconds, which turns
         * a missing {@code @EnableScheduling} into a readable failure and never a stalled build.
         */
        @Test
        @DisplayName("the schedule publishes a waiting row inside a bounded wait")
        void theSchedulePublishesAWaitingRowInsideABoundedWait() {
            OutboxEventEntity waiting = row();
            store.add(waiting);

            try (AnnotationConfigApplicationContext context =
                    new AnnotationConfigApplicationContext()) {

                context.getEnvironment().getPropertySources().addFirst(new MapPropertySource(
                        "relayScheduleUnderTest",
                        Map.of(SWEEP_DELAY_KEY, String.valueOf(QUICK_SWEEP_DELAY_MS))));
                context.registerBean(OutboxRelay.class, () -> relay);
                context.register(SchedulingEnabled.class);
                context.refresh();

                // The wait is on the mark rather than on the entity flag, and the two are not the
                // same instant. OutboxRelay.publishAndMark calls row.markPublished before it calls
                // save, and the row under test is the very object the relay mutates, so
                // isPublished() flips one statement before the save that records the mark. Waiting
                // on the flag let this test read an empty mark list between those two statements.
                await().atMost(POLL_CEILING)
                        .pollInterval(POLL_INTERVAL)
                        .until(() -> store.marks().contains(waiting.getEventId()));
            }

            assertThat(waiting.isPublished()).isTrue();
            assertThat(store.marks()).contains(waiting.getEventId());
            assertThat(publisher.sends()).isNotEmpty();
        }
    }

    /**
     * Asserts the relay tolerates a broker it cannot reach.
     */
    @Nested
    @DisplayName("Broker absence")
    class BrokerAbsence {

        /**
         * A refused send leaves the row claimable on the next sweep.
         *
         * <p>{@code messaging/KafkaEventPublisher} waits for the broker and then reports a failure
         * as an unchecked exception, which is what arrives here.
         */
        @Test
        @DisplayName("a refused send leaves the row claimable")
        void aRefusedSendLeavesTheRowClaimable() {
            OutboxEventEntity waiting = row();
            store.add(waiting);
            publisher.failSendsTo(shipped.kafka().topics().cardUpdated(),
                    new IllegalStateException("broker unavailable"), 1);

            relay.publishPendingEvents();

            assertThat(waiting.isPublished()).isFalse();
            assertThat(waiting.getRelayState())
                    .isEqualTo(OutboxEventEntity.RelayState.PENDING);
            assertThat(waiting.getAttemptCount()).isEqualTo(1);
            verify(infrastructureFailures).increment();
            verify(eventsPublished, never()).increment();
        }

        /**
         * The row reaches the broker once the broker answers again.
         *
         * <p>The first sweep is refused and the second succeeds, so an unreachable broker delays an
         * event and loses none.
         */
        @Test
        @DisplayName("the row is published once the broker answers")
        void theRowIsPublishedOnceTheBrokerAnswers() {
            OutboxEventEntity waiting = row(ACCOUNT_ID, Instant.now().minusSeconds(120L));
            store.add(waiting);
            publisher.failSendsTo(shipped.kafka().topics().cardUpdated(),
                    new IllegalStateException("broker unavailable"), 1);

            relay.publishPendingEvents();
            assertThat(waiting.isPublished()).isFalse();
            waiting.recordFailure("due now", Instant.now(), Instant.now().minusSeconds(1L));
            relay.publishPendingEvents();

            assertThat(waiting.isPublished()).isTrue();
            assertThat(store.marks()).containsExactly(waiting.getEventId());
        }

        /**
         * A refused send backs off its own account and leaves the other accounts of the sweep alone.
         *
         * <p>Commit order per account is kept by the claim rather than by stopping the sweep.
         * {@link OutboxEventRepository#claimDueRows} answers with the due head row of each aggregate,
         * so a later event of one account is not claimable while that account's earlier row is
         * unpublished, and a refused row therefore holds only its own account back.
         *
         * <p>This asserted the opposite before: the sweep returned at the first refused row, leaving
         * every row behind it unattempted, so one unreachable partition stopped publication for every
         * account. Both rows here name different accounts and both are refused, so each records
         * exactly one attempt of its own.
         */
        @Test
        @DisplayName("a refused send backs off its own account and no other")
        void aRefusedSendBacksOffItsOwnAccountAndNoOther() {
            Instant start = Instant.now().minusSeconds(120L);
            OutboxEventEntity first = row(ACCOUNT_ID, start);
            OutboxEventEntity second = row(OTHER_ACCOUNT_ID, start.plusSeconds(1L));
            store.add(first, second);
            publisher.failSendsTo(shipped.kafka().topics().cardUpdated(),
                    new IllegalStateException("broker unavailable"), 2);

            relay.publishPendingEvents();

            assertThat(publisher.sends()).hasSize(2);
            assertThat(first.getAttemptCount()).isEqualTo(1);
            assertThat(first.getRelayState())
                    .isEqualTo(OutboxEventEntity.RelayState.PENDING);
            assertThat(second.getAttemptCount()).isEqualTo(1);
            assertThat(second.getRelayState())
                    .isEqualTo(OutboxEventEntity.RelayState.PENDING);
        }
    }

    /**
     * Asserts the relay names the configured topic and keys each message on the account identifier.
     */
    @Nested
    @DisplayName("Destination and key")
    class DestinationAndKey {

        /**
         * The send names the configured card update topic.
         *
         * <p>The name comes from {@code carddemo.kafka.topics.card-updated}, which
         * {@code card-platform/.env.example} leaves to this service to define.
         */
        @Test
        @DisplayName("the send names the configured card update topic")
        void theSendNamesTheConfiguredCardUpdateTopic() {
            store.add(row());

            relay.publishPendingEvents();

            assertThat(publisher.sends()).hasSize(1);
            assertThat(publisher.sends().getFirst().topic())
                    .isEqualTo(shipped.kafka().topics().cardUpdated());
        }

        /**
         * The message key is the row's account identifier, eleven digits wide.
         *
         * <p>{@code XREF-ACCT-ID PIC 9(11)} at {@code app/cpy/CVACT03Y.cpy:L7} fixes the width. One
         * key per account keeps that account's events on one partition.
         */
        @Test
        @DisplayName("the message key is the eleven-digit account identifier")
        void theMessageKeyIsTheElevenDigitAccountIdentifier() {
            OutboxEventEntity waiting = row();
            store.add(waiting);

            relay.publishPendingEvents();

            Send send = publisher.sends().getFirst();
            assertThat(send.key()).isEqualTo(waiting.getAggregateId());
            assertThat(send.key()).matches(EventPublisherPort.AGGREGATE_ID_PATTERN);
            assertThat(send.key()).hasSize(OutboxEventEntity.AGGREGATE_ID_LENGTH);
        }

        /**
         * The payload travels exactly as the writer stored it.
         *
         * <p>The stored text is already serialized JavaScript Object Notation, so the relay passes it
         * through and serializes nothing.
         */
        @Test
        @DisplayName("the payload travels unchanged")
        void thePayloadTravelsUnchanged() {
            OutboxEventEntity waiting = row();
            store.add(waiting);

            relay.publishPendingEvents();

            assertThat(publisher.sends().getFirst().payload()).isEqualTo(waiting.getPayload());
        }
    }

    /**
     * Asserts the terminal diagnostic reaches the configured dead-letter topic, and that its four
     * components hold the widths {@code 01 ABEND-DATA} at {@code app/cpy/CSMSG02Y.cpy:L21} fixes.
     */
    @Nested
    @DisplayName("Dead-letter diagnostics")
    class DeadLetterDiagnostics {

        /** Attempts a row takes before one more failure abandons it. */
        private static final int ATTEMPTS_BEFORE_THE_LAST =
                OutboxEventEntity.MAX_DELIVERY_ATTEMPTS - 1;

        /**
         * The shipped configuration resolves the dead-letter topic name.
         *
         * <p>{@code carddemo.dead-letter} is the one topic every service shares, fixed by
         * {@code card-platform/.env.example}.
         */
        @Test
        @DisplayName("the shipped dead-letter topic is carddemo.dead-letter")
        void theShippedDeadLetterTopicIsTheSharedOne() {
            assertThat(shipped.kafka().topics().deadLetter()).isEqualTo("carddemo.dead-letter");
        }

        /**
         * A row whose attempts are spent is named on the configured dead-letter topic.
         *
         * <p>The diagnostic carries the account identifier as its key, so it stays on the partition
         * the events it follows used.
         */
        @Test
        @DisplayName("a spent row is named on the dead-letter topic")
        void aSpentRowIsNamedOnTheDeadLetterTopic() {
            OutboxEventEntity spent = spentRow();
            store.add(spent);
            publisher.failSendsTo(shipped.kafka().topics().cardUpdated(),
                    new IllegalStateException("broker unavailable"), 1);

            relay.publishPendingEvents();

            String deadLetterTopic = shipped.kafka().topics().deadLetter();
            assertThat(spent.getRelayState())
                    .isEqualTo(OutboxEventEntity.RelayState.ABANDONED);
            assertThat(publisher.sendsTo(deadLetterTopic)).hasSize(1);
            assertThat(publisher.sendsTo(deadLetterTopic).getFirst().key())
                    .isEqualTo(spent.getAggregateId());
            verify(outboxAbandoned).increment();
        }

        /**
         * The diagnostic quotes no card number and no card verification value.
         *
         * <p>The relay builds its diagnostic from a failure's type name and its own constants, so no
         * value of the payload can travel on it.
         */
        @Test
        @DisplayName("the diagnostic quotes no cardholder value")
        void theDiagnosticQuotesNoCardholderValue() {
            OutboxEventEntity spent = spentRow();
            store.add(spent);
            publisher.failSendsTo(shipped.kafka().topics().cardUpdated(),
                    new IllegalStateException("broker unavailable"), 1);

            relay.publishPendingEvents();

            String diagnostic =
                    publisher.sendsTo(shipped.kafka().topics().deadLetter()).getFirst().payload();
            assertThat(diagnostic).doesNotContain(PAYLOAD);
            assertThat(diagnostic).doesNotContain("maskedCardNumber");
        }

        /**
         * The four component widths match {@code app/cpy/CSMSG02Y.cpy:L22-L29}.
         *
         * <p>{@code ABEND-CODE PIC X(4)} sits at L22, {@code ABEND-CULPRIT PIC X(8)} at L24,
         * {@code ABEND-REASON PIC X(50)} at L26 and {@code ABEND-MSG PIC X(72)} at L28. The four
         * widths total 134 characters.
         */
        @Test
        @DisplayName("the four component widths total 134 characters")
        void theFourComponentWidthsTotalOneHundredAndThirtyFour() {
            assertThat(DeadLetterMetadata.ABEND_CODE_MAX_LENGTH).isEqualTo(4);
            assertThat(DeadLetterMetadata.CULPRIT_MAX_LENGTH).isEqualTo(8);
            assertThat(DeadLetterMetadata.REASON_MAX_LENGTH).isEqualTo(50);
            assertThat(DeadLetterMetadata.MESSAGE_MAX_LENGTH).isEqualTo(72);
            assertThat(DeadLetterMetadata.ABEND_CODE_MAX_LENGTH
                    + DeadLetterMetadata.CULPRIT_MAX_LENGTH
                    + DeadLetterMetadata.REASON_MAX_LENGTH
                    + DeadLetterMetadata.MESSAGE_MAX_LENGTH).isEqualTo(134);
        }

        /**
         * A null component becomes the empty string.
         *
         * <p>All four fields of {@code app/cpy/CSMSG02Y.cpy:L22-L29} carry {@code VALUE SPACES}, on
         * L23, L25, L27 and L29, which is the state the empty string mirrors.
         */
        @Test
        @DisplayName("a null component becomes the empty string")
        void aNullComponentBecomesTheEmptyString() {
            DeadLetterMetadata absent = DeadLetterMetadata.of(null, null, null, null);

            assertThat(absent.abendCode()).isEmpty();
            assertThat(absent.culprit()).isEmpty();
            assertThat(absent.reason()).isEmpty();
            assertThat(absent.message()).isEmpty();
        }

        /**
         * An over-long component is shortened to its own width and raises nothing.
         *
         * <p>A diagnostic describes a failure that already happened. A second failure raised while
         * building one would suppress the record of the first.
         */
        @Test
        @DisplayName("an over-long component is shortened to its own width")
        void anOverLongComponentIsShortenedToItsOwnWidth() {
            DeadLetterMetadata shortened = DeadLetterMetadata.of("X".repeat(40), "Y".repeat(40),
                    "Z".repeat(200), "W".repeat(400));

            assertThat(shortened.abendCode())
                    .hasSize(DeadLetterMetadata.ABEND_CODE_MAX_LENGTH);
            assertThat(shortened.culprit()).hasSize(DeadLetterMetadata.CULPRIT_MAX_LENGTH);
            assertThat(shortened.reason()).hasSize(DeadLetterMetadata.REASON_MAX_LENGTH);
            assertThat(shortened.message()).hasSize(DeadLetterMetadata.MESSAGE_MAX_LENGTH);
        }

        /**
         * The default detail reads exactly as {@code app/cbl/COCRDUPC.cbl:L1534} moves it.
         *
         * <p>That line runs under the {@code IF ABEND-MSG EQUAL LOW-VALUES} guard at
         * {@code app/cbl/COCRDUPC.cbl:L1533}, inside the {@code ABEND-ROUTINE.} paragraph opening at
         * {@code app/cbl/COCRDUPC.cbl:L1531}. The trailing full stop belongs to the literal.
         */
        @Test
        @DisplayName("the default detail carries the source text verbatim")
        void theDefaultDetailCarriesTheSourceTextVerbatim() {
            assertThat(DeadLetterMetadata.DEFAULT_MESSAGE).isEqualTo("UNEXPECTED ABEND OCCURRED.");
            assertThat(DeadLetterMetadata.DEFAULT_MESSAGE)
                    .hasSizeLessThanOrEqualTo(DeadLetterMetadata.MESSAGE_MAX_LENGTH);
        }

        /**
         * The diagnostic carrier holds no annotation and never reaches a table.
         *
         * <p>An unannotated record is not a mapped entity, so no dead-letter route can write a row.
         */
        @Test
        @DisplayName("the diagnostic carrier holds no annotation")
        void theDiagnosticCarrierHoldsNoAnnotation() {
            Annotation[] declared = DeadLetterMetadata.class.getAnnotations();

            assertThat(DeadLetterMetadata.class.isRecord()).isTrue();
            assertThat(declared).isEmpty();
        }

        /**
         * @return a row holding one attempt fewer than its ceiling allows
         */
        private OutboxEventEntity spentRow() {
            OutboxEventEntity almostSpent = row(ACCOUNT_ID, Instant.now().minusSeconds(600L));
            Instant past = Instant.now().minusSeconds(300L);
            for (int attempt = 0; attempt < ATTEMPTS_BEFORE_THE_LAST; attempt++) {
                almostSpent.recordFailure("broker unavailable", past, past);
            }
            return almostSpent;
        }
    }

    /**
     * Asserts the producer settings live in configuration, and that the relay writes no marker.
     */
    @Nested
    @DisplayName("Configuration and the marker table")
    class ConfigurationAndTheMarkerTable {

        /**
         * The acknowledgement and idempotence settings come from the shipped file.
         *
         * <p>Both keys sit under {@code spring.kafka.producer} in
         * {@code src/main/resources/application.yml}, and {@code config/KafkaProducerConfig} sets
         * neither in Java.
         */
        @Test
        @DisplayName("acknowledgement and idempotence come from configuration")
        void acknowledgementAndIdempotenceComeFromConfiguration() {
            assertThat(shippedAcks).isEqualTo("all");
            assertThat(shippedIdempotence).isEqualTo("true");
        }

        /**
         * The value serializer writes the stored text as it stands.
         *
         * <p>The payload the relay republishes is already-serialized JavaScript Object Notation, so a
         * text serializer carries it whole.
         */
        @Test
        @DisplayName("the value serializer writes text")
        void theValueSerializerWritesText() {
            assertThat(shippedValueSerializer)
                    .isEqualTo("org.apache.kafka.common.serialization.StringSerializer");
        }

        /**
         * A sweep writes no duplicate-delivery marker.
         *
         * <p>The relay publishes, but it consumes no broker event, so it takes no
         * {@link ProcessedEventRepository} at all. The marker table waits for a consumer this
         * service does not have.
         */
        @Test
        @DisplayName("a sweep writes no duplicate-delivery marker")
        void aSweepWritesNoDuplicateDeliveryMarker() {
            store.add(row());
            List<Class<?>> collaborators = new ArrayList<>();
            for (Field field : OutboxRelay.class.getDeclaredFields()) {
                collaborators.add(field.getType());
            }

            relay.publishPendingEvents();

            assertThat(collaborators).doesNotContain(ProcessedEventRepository.class);
            for (Class<?> constructorArgument : OutboxRelay.class.getConstructors()[0]
                    .getParameterTypes()) {
                assertThat(constructorArgument).isNotEqualTo(ProcessedEventRepository.class);
            }
        }
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(CardProperties.class)
    static class PropertiesEnabled {
    }

    /** Turns on the scheduling the relay poll depends on, and nothing else. */
    @Configuration(proxyBeanMethods = false)
    @EnableScheduling
    static class SchedulingEnabled {
    }

    /**
     * Holds the rows one sweep can see and answers the two queries the relay issues.
     *
     * <p>The claim answer reproduces the query at
     * {@code repository/OutboxEventRepository.claimDueRows}: rows in
     * {@link OutboxEventEntity.RelayState#PENDING} whose next attempt has fallen due, ordered by
     * that due time, limited to the count the caller asked for. A sweep therefore skips a published
     * row on its own terms, and the publish-once tests assert relay behaviour.
     */
    private static final class RelayStore {

        /** Every row this store holds, in insertion order. */
        private final List<OutboxEventEntity> rows = new ArrayList<>();

        /** One entry per row the relay saved carrying a mark. */
        private final List<UUID> marks = new ArrayList<>();

        /** One entry per claim the relay issued, holding the limit it asked for. */
        private final List<Limit> claimLimits = new ArrayList<>();

        /** One entry per claim the relay issued, holding how many rows that claim returned. */
        private final List<Integer> claimedCounts = new ArrayList<>();

        /** Raised by every claim once set, standing in for a store the relay cannot read. */
        private RuntimeException claimFailure;

        /**
         * Adds rows this store will offer.
         *
         * @param added the rows to hold
         */
        void add(OutboxEventEntity... added) {
            rows.addAll(List.of(added));
        }

        /**
         * @param failure the failure each claim raises
         */
        void failEveryClaimWith(RuntimeException failure) {
            this.claimFailure = failure;
        }

        /**
         * The identifiers of the rows this store currently reports as published.
         *
         * @return the published identifiers, in insertion order
         */
        List<UUID> publishedIds() {
            return rows.stream()
                    .filter(OutboxEventEntity::isPublished)
                    .map(OutboxEventEntity::getEventId)
                    .toList();
        }

        /**
         * One entry per mark the relay wrote.
         *
         * @return the marked identifiers, in the order the relay saved them
         */
        List<UUID> marks() {
            return List.copyOf(marks);
        }

        /**
         * One entry per claim the relay issued.
         *
         * @return the limits the relay asked for, in claim order
         */
        List<Limit> claimLimits() {
            return List.copyOf(claimLimits);
        }

        /**
         * How many rows each claim returned.
         *
         * @return one row count per claim, in claim order
         */
        List<Integer> claimedCounts() {
            return List.copyOf(claimedCounts);
        }

        /**
         * @return a repository backed by this store
         */
        OutboxEventRepository asRepository() {
            OutboxEventRepository repository = mock(OutboxEventRepository.class);

            when(repository.claimDueRows(any(Instant.class), any(Limit.class))).thenAnswer(call -> {
                if (claimFailure != null) {
                    throw claimFailure;
                }
                Instant now = call.getArgument(0);
                Limit limit = call.getArgument(1);
                claimLimits.add(limit);
                List<OutboxEventEntity> claimed = due(now, limit);
                claimedCounts.add(claimed.size());
                return claimed;
            });

            when(repository.findByRelayStateAndClaimedAtBeforeOrderByClaimedAtAsc(
                    any(OutboxEventEntity.RelayState.class), any(Instant.class), any(Limit.class)))
                    .thenAnswer(call -> stranded(call.getArgument(0), call.getArgument(1),
                            call.getArgument(2)));

            when(repository.save(any(OutboxEventEntity.class))).thenAnswer(call -> {
                OutboxEventEntity saved = call.getArgument(0);
                if (saved.isPublished()) {
                    marks.add(saved.getEventId());
                }
                return saved;
            });

            // The relay records each outcome in a transaction of its own and re-reads the row inside
            // it, because the claim has committed by then and saving the copy the claim loaded would
            // write pre-claim state back over it. This store answers that read with the row it holds,
            // so each case keeps asserting against the instance it added.
            when(repository.findById(any(UUID.class))).thenAnswer(call -> {
                UUID identifier = call.getArgument(0);
                return rows.stream()
                        .filter(row -> row.getEventId().equals(identifier))
                        .findFirst();
            });

            return repository;
        }

        /**
         * The rows a claim at {@code now} returns.
         *
         * @param now   the moment the sweep started
         * @param limit the count the relay asked for
         * @return the due rows, oldest due time first
         */
        private List<OutboxEventEntity> due(Instant now, Limit limit) {
            return rows.stream()
                    .filter(row -> row.getRelayState() == OutboxEventEntity.RelayState.PENDING)
                    .filter(row -> !row.getNextAttemptAt().isAfter(now))
                    .sorted(Comparator.comparing(OutboxEventEntity::getNextAttemptAt)
                            .thenComparing(row -> row.getEventId().toString()))
                    .limit(limit.isLimited() ? limit.max() : rows.size())
                    .toList();
        }

        /**
         * The rows a claim older than {@code claimedBefore} left behind.
         *
         * @param state         the state a stranded row sits in
         * @param claimedBefore the moment a standing claim becomes stale
         * @param limit         the count the relay asked for
         * @return the stranded rows, oldest claim first
         */
        private List<OutboxEventEntity> stranded(OutboxEventEntity.RelayState state,
                Instant claimedBefore, Limit limit) {

            return rows.stream()
                    .filter(row -> row.getRelayState() == state)
                    .filter(row -> row.getClaimedAt() != null
                            && row.getClaimedAt().isBefore(claimedBefore))
                    .sorted(Comparator.comparing(OutboxEventEntity::getClaimedAt))
                    .limit(limit.isLimited() ? limit.max() : rows.size())
                    .toList();
        }
    }

    /**
     * Records every send, and reads the mark state of the store at the moment of each one.
     *
     * <p>Reading the store during {@code publish} is what turns publish-then-mark ordering into an
     * assertion. A relay that marked first would show its own row already published in the snapshot
     * taken here.
     */
    private static final class RecordingPublisher implements EventPublisherPort {

        /** The store whose mark state each send snapshots. */
        private final RelayStore store;

        /** The probe reporting whether the sweep boundary stands open during a send. */
        private final BoundaryProbe boundary;

        /** One entry per send, in send order. */
        private final List<Send> sends = new ArrayList<>();

        /** The topic whose sends fail while {@link #failuresRemaining} is positive. */
        private String failingTopic;

        /** The failure a refused send raises. */
        private RuntimeException failure;

        /** How many further sends to the failing topic still fail. */
        private int failuresRemaining;

        /**
         * Takes the store to snapshot and the boundary to report.
         *
         * @param store    the store each send reads
         * @param boundary the probe each send reads
         */
        RecordingPublisher(RelayStore store, BoundaryProbe boundary) {
            this.store = store;
            this.boundary = boundary;
        }

        /**
         * @param topic       the topic whose sends fail
         * @param refusal     the failure each of those sends raises
         * @param occurrences how many sends fail
         */
        void failSendsTo(String topic, RuntimeException refusal, int occurrences) {
            this.failingTopic = topic;
            this.failure = refusal;
            this.failuresRemaining = occurrences;
        }

        /**
         * One entry per send.
         *
         * @return the sends, in send order
         */
        List<Send> sends() {
            return List.copyOf(sends);
        }

        /**
         * The sends that reached one topic.
         *
         * @param topic the topic to select
         * @return the sends to that topic, in send order
         */
        List<Send> sendsTo(String topic) {
            return sends.stream().filter(send -> send.topic().equals(topic)).toList();
        }

        @Override
        public CompletionStage<Void> publish(String topic, String aggregateId, String payload) {
            sends.add(new Send(topic, aggregateId, payload, store.publishedIds(),
                    boundary.isOpen()));
            if (failuresRemaining > 0 && topic.equals(failingTopic)) {
                failuresRemaining = failuresRemaining - 1;
                // A broker refusal fails the stage rather than the call, which is what the
                // Kafka-backed publisher does now. outbox/OutboxRelay unwraps the stage and
                // rethrows this exception, so the relay sees exactly what it saw before.
                return CompletableFuture.failedFuture(failure);
            }
            return CompletableFuture.completedFuture(null);
        }
    }

    /**
     * One send the relay made, with the state observed as it was made.
     *
     * @param topic           the topic the relay named
     * @param key             the message key, which the relay takes from {@code aggregate_id}
     * @param payload         the text the relay passed through unchanged
     * @param publishedAtSend the rows the store reported as published during this send
     * @param insideBoundary  whether the sweep boundary stood open during this send
     */
    private record Send(String topic, String key, String payload, List<UUID> publishedAtSend,
            boolean insideBoundary) {
    }

    /**
     * Runs the sweep callback and reports whether its boundary stands open.
     *
     * <p>The relay opens this boundary itself, so the meters it records afterwards sit outside the
     * transaction that wrote the marks.
     */
    private static final class BoundaryProbe {

        /** True while the sweep callback runs. */
        private final AtomicBoolean open = new AtomicBoolean();

        /** How many times the relay opened the boundary. */
        private final AtomicInteger openings = new AtomicInteger();

        /**
         * @return a template that runs the callback and tracks the boundary
         */
        TransactionTemplate asTemplate() {
            TransactionTemplate template = mock(TransactionTemplate.class);
            when(template.execute(any())).thenAnswer(call -> {
                TransactionCallback<?> work = call.getArgument(0);
                openings.incrementAndGet();
                open.set(true);
                try {
                    return work.doInTransaction(mock(TransactionStatus.class));
                } finally {
                    open.set(false);
                }
            });
            return template;
        }

        /**
         * Whether the sweep boundary stands open.
         *
         * @return true while the callback runs
         */
        boolean isOpen() {
            return open.get();
        }

        /**
         * How many boundaries the relay opened.
         *
         * @return the opening count
         */
        int openings() {
            return openings.get();
        }
    }
}
