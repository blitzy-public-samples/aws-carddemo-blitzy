package com.carddemo.ledger.config;

import io.micrometer.core.instrument.Counter;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.time.Duration;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.micrometer.metrics.autoconfigure.MeterRegistryCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Publishes the one bean the ledger posting service records its measurements through.
 *
 * <p>{@link LedgerMeters} registers nineteen meters under seven names, covering three families:
 * events consumed, processing latency, and failure count. Spring Boot supplies the {@link MeterRegistry}.
 * Each meter registers at start-up, so every series reads zero before the first message.
 *
 * <p>Two counters carry {@code WS-TRANSACTION-COUNT} and {@code WS-REJECT-COUNT} forward from the
 * job log at {@code app/cbl/CBTRN02C.cbl:L227-L230}. A reject raises
 * {@code carddemo.ledger.transactions.processed} with {@code outcome=rejected}; an infrastructure
 * fault raises {@code carddemo.ledger.failures}. The two are separate meters.
 *
 * <p>ADDITIVE: processing latency, and the duplicate counter. The source times nothing and detects
 * no duplicate. No tag holds an identifier, so the series count stays fixed under any traffic.
 *
 * <p>Design decisions: {@code card-platform/docs/decision-log.md}.
 */
@Configuration
public class ObservabilityConfig {

    /** Tag name every meter of this service carries, so one scrape separates the six services. */
    public static final String TAG_SERVICE = "service";

    /**
     * Registers the ledger meters and publishes them as one injectable bean.
     *
     * @param registry the meter registry Spring Boot auto-configuration supplies
     * @return the surface every measured path in this service records through
     * @throws NullPointerException when {@code registry} is {@code null}
     */
    @Bean
    public LedgerMeters ledgerMeters(MeterRegistry registry) {
        return new LedgerMeters(registry);
    }

    /**
     * The recording surface of this service. One method names one measured path.
     *
     * <p>Each meter registers in the constructor, so every series exists from start-up.
     */
    public static final class LedgerMeters {

        /** Stage tag value of a failure raised while publishing an outbox row. */
        public static final String PUBLISH_STAGE = "publish";

        /** Stage tag value of a failure raised while applying an event. */
        public static final String POST_STAGE = "process";

        /** Stage tag value of a payload that could not be deserialized or validated. */
        public static final String DESERIALIZE_STAGE = "deserialize";

        /** Stage tag value of an outbox row abandoned after its attempts ran out. */
        public static final String ABANDON_STAGE = "abandon";

        /** Outcome tag value of a consumed record whose diagnostic the broker acknowledged. */
        public static final String DEAD_LETTER_PUBLISHED = "published";

        /** Outcome tag value of a consumed record whose diagnostic the broker refused. */
        public static final String DEAD_LETTER_FAILED = "failed";

        /**
         * Kind tag value of a record the schema validator refused.
         *
         * <p>The four values below name what a delivery this service gave up on failed at. They are
         * a second dimension of {@code carddemo.ledger.dead.letters}, beside the outcome of the
         * diagnostic itself, because a count of abandoned records that cannot say what they failed
         * at names no fault to act on. Every value is a constant, so the series count of that meter
         * stays fixed under any traffic.
         */
        public static final String FAILURE_SCHEMA_VALIDATION = "schema_validation";

        /** Kind tag value of a record the value deserializer could not read at all. */
        public static final String FAILURE_DESERIALIZATION = "deserialization";

        /** Kind tag value of a record whose listener raised, so a business rule or a write failed. */
        public static final String FAILURE_PROCESSING = "processing";

        /** Kind tag value of a record whose failure named none of the three kinds above. */
        public static final String FAILURE_UNKNOWN = "unknown";

        /** Tag name of the kind dimension of {@code carddemo.ledger.dead.letters}. */
        public static final String TAG_FAILURE_KIND = "failure.kind";

        /** Tag name of the outcome dimension of {@code carddemo.ledger.dead.letters}. */
        public static final String TAG_OUTCOME = "outcome";

        /** Every value the kind dimension carries, registered at start-up and never extended. */
        public static final List<String> FAILURE_KINDS = List.of(FAILURE_SCHEMA_VALIDATION,
                FAILURE_DESERIALIZATION, FAILURE_PROCESSING, FAILURE_UNKNOWN);

        /** Category-balance stores that lost the high-order digits of their sum. */
        private final Counter categoryBalanceWrapped;

        /** Events read from the two topics this service subscribes to. */
        private final Counter eventsConsumed;

        /** Postings applied, the successor of {@code WS-TRANSACTION-COUNT}. */
        private final Counter transactionsPosted;

        /** Rejects recorded, the successor of {@code WS-REJECT-COUNT}. */
        private final Counter transactionsRejected;

        /** Deliveries a marker already covered, so nothing was applied twice. */
        private final Counter duplicatesSkipped;

        /** Wall time of one event, from listener entry to commit. */
        private final Timer processingLatency;

        /** Messages the deserializer or the schema validator refused. */
        private final Counter deserializeFailures;

        /** Events whose posting or whose database write did not complete. */
        private final Counter processFailures;

        /** Outbox rows the broker accepted, counted after the sweep that sent them committed. */
        private final Counter eventsPublished;

        /** Outbox rows the broker did not accept. */
        private final Counter publishFailures;

        /** Records the broker refused with no retry left, so the row was abandoned. */
        private final Counter abandonedFailures;

        /**
         * Consumed records given up on, one counter per outcome and failure kind.
         *
         * <p>Keyed by outcome then kind. Every combination registers at start-up, so a scrape
         * carries all eight series before the first dead letter and an alerting rule written
         * against one of them can fire. Prometheus admits one tag-key set per meter name, so both
         * tags sit on every series of this name.
         */
        private final Map<String, Map<String, Counter>> deadLetters;

        /**
         * Registers every meter against {@code registry}.
         *
         * @param registry the meter registry every series is published through
         * @throws NullPointerException when {@code registry} is {@code null}
         */
        LedgerMeters(MeterRegistry registry) {
            Objects.requireNonNull(registry, "registry");
            this.eventsConsumed = Counter.builder("carddemo.ledger.events.consumed")
                    .description("Events read from the topics this service subscribes to")
                    .register(registry);
            this.transactionsPosted = Counter.builder("carddemo.ledger.transactions.processed")
                    .tag("outcome", "posted")
                    .description("Transactions applied, after WS-TRANSACTION-COUNT at"
                            + " app/cbl/CBTRN02C.cbl:L185")
                    .register(registry);
            this.transactionsRejected = Counter.builder("carddemo.ledger.transactions.processed")
                    .tag("outcome", "rejected")
                    .description("Transactions rejected, after WS-REJECT-COUNT at"
                            + " app/cbl/CBTRN02C.cbl:L186")
                    .register(registry);
            this.categoryBalanceWrapped =
                    Counter.builder("carddemo.ledger.category.balance.wrapped")
                    .description("Category-balance stores whose sum passed nine integer digits and"
                            + " kept only the low-order nine, reproducing an ADD with no ON SIZE"
                            + " ERROR phrase")
                    .register(registry);
            this.duplicatesSkipped = Counter.builder("carddemo.ledger.transactions.processed")
                    .tag("outcome", "duplicate")
                    .description("Deliveries a processed-event marker already covered")
                    .register(registry);
            this.processingLatency = Timer.builder("carddemo.ledger.processing.latency")
                    .description("Wall time of one event, from listener entry to commit")
                    .register(registry);
            this.eventsPublished = Counter.builder("carddemo.ledger.events.published")
                    .description("Outbox rows the broker acknowledged, counted after the sweep that"
                            + " sent them committed")
                    .register(registry);
            this.deserializeFailures = Counter.builder("carddemo.ledger.failures")
                    .tag("stage", "deserialize")
                    .description("Processing faults, tagged by the stage that failed")
                    .register(registry);
            this.processFailures = Counter.builder("carddemo.ledger.failures")
                    .tag("stage", "process")
                    .description("Processing faults, tagged by the stage that failed")
                    .register(registry);
            this.publishFailures = Counter.builder("carddemo.ledger.failures")
                    .tag("stage", "publish")
                    .description("Processing faults, tagged by the stage that failed")
                    .register(registry);
            this.abandonedFailures = Counter.builder("carddemo.ledger.failures")
                    .tag("stage", "abandon")
                    .description("Processing faults, tagged by the stage that failed")
                    .register(registry);
            Map<String, Map<String, Counter>> byOutcome = new LinkedHashMap<>();
            for (String outcome : List.of(DEAD_LETTER_PUBLISHED, DEAD_LETTER_FAILED)) {
                Map<String, Counter> byKind = new LinkedHashMap<>();
                for (String kind : FAILURE_KINDS) {
                    byKind.put(kind, Counter.builder("carddemo.ledger.dead.letters")
                            .tag(TAG_OUTCOME, outcome)
                            .tag(TAG_FAILURE_KIND, kind)
                            .description("Consumed records given up on, by what became of the"
                                    + " diagnostic naming them and by what they failed at")
                            .register(registry));
                }
                byOutcome.put(outcome, Map.copyOf(byKind));
            }
            this.deadLetters = Map.copyOf(byOutcome);
        }

        /**
         * Counts the outbox rows one committed sweep published.
         *
         * <p>This is the produce-side counterpart of {@link #recordPublishFailure()}, and it counts a
         * publication the broker acknowledged rather than a row written to the outbox. Those two are
         * different events and the difference matters on a demonstration: a relay that cannot reach
         * the broker leaves the write count rising and this count flat, which is the reading that
         * tells a stalled relay from an idle one.
         *
         * <p>Counted after the sweep's transaction commits, so a row whose {@code published} mark
         * rolled back is not counted as published.
         *
         * @param rows publications the broker acknowledged in one committed sweep
         */
        public void recordEventsPublished(long rows) {
            if (rows > 0L) {
                eventsPublished.increment(rows);
            }
        }

        /** Counts one event read from either subscribed topic. */
        public void recordEventConsumed() {
            eventsConsumed.increment();
        }

        /** Counts one posting applied to an account balance. */
        public void recordTransactionPosted() {
            transactionsPosted.increment();
        }

        /** Counts one reject recorded. A reject is expected traffic and not a fault. */
        public void recordTransactionRejected() {
            transactionsRejected.increment();
        }

        /** Counts one delivery a marker already covered, so no table changed. */
        public void recordDuplicateSkipped() {
            duplicatesSkipped.increment();
        }

        /**
         * Counts one category-balance store that wrapped past the field.
         *
         * <p>The wrap is reproduced rather than prevented, because the source stores the same sum
         * with no {@code ON SIZE ERROR} phrase. Counting it is what stops the reproduction being
         * invisible: the balance is then wrong by a known amount, and nothing else says so.
         */
        public void recordCategoryBalanceWrapped() {
            categoryBalanceWrapped.increment();
        }

        /**
         * Records how long one event took, from listener entry to commit.
         *
         * @param elapsed the wall time the listener spent on one event
         * @throws NullPointerException when {@code elapsed} is {@code null}
         */
        public void recordProcessingLatency(Duration elapsed) {
            processingLatency.record(Objects.requireNonNull(elapsed, "elapsed"));
        }

        /** Counts one message the deserializer or the schema validator refused. */
        public void recordDeserializeFailure() {
            deserializeFailures.increment();
        }

        /** Counts one event whose posting or whose database write did not complete. */
        public void recordProcessFailure() {
            processFailures.increment();
        }

        /** Counts one outbox row the broker did not accept. */
        public void recordPublishFailure() {
            publishFailures.increment();
        }

        /**
         * Counts one failure under the named stage, reaching the same series as the named recorder
         * for that stage.
         *
         * @param stage one of {@link #DESERIALIZE_STAGE}, {@link #POST_STAGE},
         *              {@link #PUBLISH_STAGE} or {@link #ABANDON_STAGE}
         * @throws IllegalArgumentException when the stage is not one of the four
         */
        public void recordFailure(String stage) {
            switch (stage) {
                case DESERIALIZE_STAGE -> deserializeFailures.increment();
                case POST_STAGE -> processFailures.increment();
                case PUBLISH_STAGE -> publishFailures.increment();
                case ABANDON_STAGE -> abandonedFailures.increment();
                default -> throw new IllegalArgumentException(
                        "stage must name one of the four failure stages, found " + stage);
            }
        }

        /**
         * Counts one outbox row this service will not attempt again.
         *
         * <p>The unit is one row, not one attempt. A row reaches this method once it crosses
         * {@code OutboxEventEntity.MAX_DELIVERY_ATTEMPTS}.
         */
        public void recordAbandonedRow() {
            abandonedFailures.increment();
        }

        /**
         * Counts one consumed record this service will not deliver again, whose diagnostic the
         * broker acknowledged.
         *
         * <p>The unit is one record, not one attempt. The container calls a recoverer once the
         * backoff is spent, which is when a delivery counts as given up on. This series covers a
         * consumed record, and {@link #ABANDON_STAGE} covers an outbox row.
         */
        public void recordDeadLetterPublished(String failureKind) {
            deadLetters.get(DEAD_LETTER_PUBLISHED).get(resolveKind(failureKind)).increment();
        }

        /**
         * Counts one consumed record this service will not deliver again, whose diagnostic the
         * broker refused.
         *
         * <p>A reading above zero means the record is spent and no topic names it.
         */
        public void recordDeadLetterFailure(String failureKind) {
            deadLetters.get(DEAD_LETTER_FAILED).get(resolveKind(failureKind)).increment();
        }

        /**
         * Returns a kind this surface registered, and {@link #FAILURE_UNKNOWN} for anything else.
         *
         * <p>A caller naming a value no series carries would otherwise create a series at run time
         * or lose the count. Neither is acceptable of a failure counter, so an unnamed kind is
         * counted as unknown, which is itself a registered value.
         *
         * @param failureKind the kind a caller named, possibly {@code null}
         * @return one of {@link #FAILURE_KINDS}
         */
        private static String resolveKind(String failureKind) {
            return failureKind != null && FAILURE_KINDS.contains(failureKind)
                    ? failureKind
                    : FAILURE_UNKNOWN;
        }
    }

    /**
     * Adds the one common tag every meter of this service carries, including the meters Spring Boot
     * registers for the Java Virtual Machine and for the web surface. Spring Boot applies this
     * customizer while it post-processes the registry, so every meter above inherits the tag.
     *
     * @param applicationName the bound {@code spring.application.name}, which
     *                        {@code application.yml} sets
     * @return the customizer that installs the {@link #TAG_SERVICE} tag
     * @throws IllegalStateException when {@code spring.application.name} holds no value
     */
    @Bean
    public MeterRegistryCustomizer<MeterRegistry> ledgerCommonTags(
            @Value("${spring.application.name:}") String applicationName) {
        if (applicationName == null || applicationName.isBlank()) {
            throw new IllegalStateException(
                    "spring.application.name must hold a value to tag every meter with "
                            + TAG_SERVICE);
        }
        return registry -> registry.config().commonTags(TAG_SERVICE, applicationName);
    }

}
