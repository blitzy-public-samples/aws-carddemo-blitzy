package com.carddemo.ledger.config;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.time.Duration;
import java.util.Objects;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.micrometer.metrics.autoconfigure.MeterRegistryCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Supplies the one bean the ledger posting service records its measurements through.
 *
 * <p>{@link LedgerMeters} holds nine meters under five names, covering the three families a
 * demonstration shows: events consumed, processing latency, and failure count. Spring Boot supplies
 * the {@link MeterRegistry}, and every meter registers at start-up, so a scrape taken before the
 * first message lists each series at zero.
 *
 * <p>Two counter names follow the source's own counters. {@code WS-TRANSACTION-COUNT} and
 * {@code WS-REJECT-COUNT} at {@code app/cbl/CBTRN02C.cbl:L185-L186} are printed to the job log at
 * {@code app/cbl/CBTRN02C.cbl:L227-L230}, and the reject count decides the return code:
 * {@code app/cbl/CBTRN02C.cbl:L229-L230} moves 4 into {@code RETURN-CODE} when it is positive. A
 * demonstration can therefore read the same two numbers the nightly job would have reported.
 *
 * <p>A reject is expected traffic and never a fault. It is counted under
 * {@code carddemo.ledger.transactions.processed} with {@code outcome=rejected} and never on
 * {@code carddemo.ledger.failures}, because the source answered a rejected record with return code
 * 4 and no abend.
 *
 * <p>These meters replace three source mechanisms, shape only and no logic: the job-log
 * {@code DISPLAY} statements above, the file-status formatter at
 * {@code app/cbl/CBTRN02C.cbl:L714-L727}, and the four-statement abend at
 * {@code app/cbl/CBTRN02C.cbl:L707-L711} that ends in {@code CALL 'CEE3ABD'}. The abend performed no
 * cleanup and drew no distinction between a rejected record and a broken file, which is the
 * distinction the {@code stage} tag now carries.
 *
 * <p>ADDITIVE: the duplicate counter. The source has no duplicate detection of any kind, so a
 * replayed feed reaches the transaction write at {@code app/cbl/CBTRN02C.cbl:L562-L579}, hits a
 * duplicate key and abends. This service records the marker instead, and the counter says how often
 * that guard did its work.
 *
 * <p>No meter name and no tag value holds a transaction identifier, an account identifier, a card
 * number or an event identifier. Every tag value is one literal from a closed set, so the number of
 * series stays fixed however much traffic arrives.
 *
 * <p>This module compiles at release 25 while the Spring Boot parent defaults to 17, and class-file
 * major version 69 is the proof.
 */
@Configuration
public class ObservabilityConfig {

    /** Tag name every meter of this service carries, so one scrape separates the six services. */
    public static final String TAG_SERVICE = "service";

    /**
     * Registers the nine ledger meters and publishes them as one injectable bean.
     *
     * @param registry the meter registry Spring Boot auto-configuration supplies
     * @return the facade every measured path in this service records through
     * @throws NullPointerException when {@code registry} is {@code null}
     */
    @Bean
    public LedgerMeters ledgerMeters(MeterRegistry registry) {
        return new LedgerMeters(registry);
    }

    /**
     * The recording surface of this service. One method names one measured path.
     *
     * <p>The path that calls each method: {@code messaging/TransactionAuthorizedConsumer.java}
     * calls {@link #recordEventConsumed()}, {@link #recordProcessingLatency(Duration)},
     * {@link #recordDuplicateSkipped()}, {@link #recordTransactionPosted()},
     * {@link #recordTransactionRejected()}, {@link #recordDeserializeFailure()} and
     * {@link #recordProcessFailure()}; {@code outbox/OutboxRelay.java} calls
     * {@link #recordPublishFailure()}.
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

        /** Outbox rows the broker did not accept. */
        private final Counter publishFailures;

        /** Records the broker refused with no retry left, so the row was abandoned. */
        private final Counter abandonedFailures;

        /**
         * Registers all nine meters against {@code registry}.
         *
         * @param registry the meter registry every series is published through
         * @throws NullPointerException when {@code registry} is {@code null}
         */
        LedgerMeters(MeterRegistry registry) {
            Objects.requireNonNull(registry, "registry");
            this.eventsConsumed = Counter.builder("carddemo.ledger.events.consumed")
                    .description("Events read from topics transaction.authorized and"
                            + " transaction.declined")
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
            this.duplicatesSkipped = Counter.builder("carddemo.ledger.transactions.processed")
                    .tag("outcome", "duplicate")
                    .description("Deliveries a processed-event marker already covered")
                    .register(registry);
            this.processingLatency = Timer.builder("carddemo.ledger.processing.latency")
                    .description("Wall time of one event, from listener entry to commit")
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

        /** Counts one outbox row abandoned after its attempts ran out. */
        /**
         * Counts one failure under the named stage.
         *
         * <p>The four stage names are the constants on this class. A caller that has the stage as a
         * value uses this method; a caller that knows the stage at the call site uses the named
         * recorder instead. Both reach the same series.
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

        public void recordAbandonedRow() {
            abandonedFailures.increment();
        }
    }

    /**
     * Adds the one common tag every meter of this service carries, including the meters Spring Boot
     * registers for the Java Virtual Machine and for the web surface. Spring Boot applies this
     * customizer while it post-processes the registry, so every meter above inherits the tag. The
     * fallback value matches {@code spring.application.name} in {@code application.yml}, so a
     * context that omits the property still tags its meters.
     *
     * @param applicationName the bound {@code spring.application.name}
     * @return the customizer that installs the {@link #TAG_SERVICE} tag
     */
    @Bean
    public MeterRegistryCustomizer<MeterRegistry> ledgerCommonTags(
            @Value("${spring.application.name:ledger-posting-service}") String applicationName) {
        if (applicationName == null || applicationName.isBlank()) {
            throw new IllegalStateException("spring.application.name must hold a value");
        }
        return registry -> registry.config().commonTags(TAG_SERVICE, applicationName);
    }
}
