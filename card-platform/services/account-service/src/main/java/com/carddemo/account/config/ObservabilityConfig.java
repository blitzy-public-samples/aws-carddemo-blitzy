package com.carddemo.account.config;

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
 * Supplies the one bean the account service records its measurements through.
 *
 * <p>{@link AccountMeters} holds sixteen series in four groups: events consumed, processing latency,
 * throughput, and failure count. Every name opens with {@code carddemo.account.}, which is the
 * namespace the fraud detection and notification services use.
 *
 * <p>The outbox relay reports four of them, and they answer four different questions. A failed
 * attempt against a row is {@code publish.failed} and says a retry is coming. A row given up on is
 * {@code outbox.abandoned} and says one account state change will never be published. A diagnostic
 * the broker acknowledged is {@code dead.letters.published} and says that lost event is recorded
 * somewhere. A diagnostic the broker refused is {@code dead.letters.failed} and says it is not
 * recorded anywhere yet. Summing them into one failure count would hide the third and fourth
 * behind the first, which is the distinction an operator most needs.
 *
 * <p>The events-consumed counter carries the posted transactions this service applied.
 * {@code messaging/TransactionPostedConsumer} increments it once per delivery, times each one on
 * {@code posting.latency}, and separates three outcomes: a posting applied, a duplicate delivery
 * skipped, and a delivery that failed. The failure joins {@code transaction.failures} under the
 * {@code posting} operation, so the three transactional paths of this service report their failures
 * on one series with one bounded tag rather than on three names.
 *
 * <p>Every meter surfaces under {@code /actuator} on the management port, which
 * {@code application.yml} sets to 9080 and never the service port. The service exposes
 * {@code health,metrics,prometheus} there and answers its container probe at
 * {@code http://localhost:9080/actuator/health}. Every meter carries the bounded
 * {@code service=account-service} tag. No meter name holds a
 * Social Security Number or a government-issued identifier, the two customer fields declared at
 * {@code app/cpy/CVCUS01Y.cpy:L17-L18}.
 *
 * <p>Each log line leaves as JavaScript Object Notation. The
 * {@code logging.structured.format.console} property of {@code application.yml} carries the whole of
 * that setup, and this module ships no Logback configuration file.
 *
 * <p>Decisions behind the meter set: {@code card-platform/docs/decision-log.md}.
 */
@Configuration
public class ObservabilityConfig {

    /** Tag key that names this service on every meter it reports. */
    public static final String SERVICE_TAG = "service";

    /** Tag key that distinguishes the two transactional operations. */
    public static final String OPERATION_TAG = "operation";

    /** Failure tag value for the account and customer update transaction. */
    public static final String UPDATE_OPERATION = "update";

    /** Failure tag value for the billing-cycle close transaction. */
    public static final String CYCLE_CLOSE_OPERATION = "cycle-close";

    /**
     * Failure tag value for the posted-transaction transaction.
     *
     * <p>{@code messaging/TransactionPostedConsumer} opens one transaction per delivery, carrying the
     * processed-event marker, the account row and the outbox row, so a failure there is a rollback of
     * the same kind the other two operations report.
     */
    public static final String POSTING_OPERATION = "posting";

    /**
     * @param registry the meter registry Spring Boot supplies
     * @return the facade every measured path in this service records through
     */
    @Bean
    public AccountMeters accountMeters(MeterRegistry registry) {
        return new AccountMeters(registry);
    }

    /** Adds the service name to application, Java Virtual Machine, and web meters alike. */
    @Bean
    public MeterRegistryCustomizer<MeterRegistry> accountCommonTags(
            @Value("${spring.application.name:account-service}") String applicationName) {
        if (applicationName == null || applicationName.isBlank()) {
            throw new IllegalStateException("spring.application.name must hold a value");
        }
        return registry -> registry.config().commonTags(SERVICE_TAG, applicationName);
    }

    /**
     * The recording surface of the account service. One method names one measured path, so a
     * caller injects {@code AccountMeters} and calls the method that matches the work it just did.
     *
     * <p>No COBOL program declares a meter, so every meter here is additive. Thirteen named meters
     * are declared below, and {@code carddemo.account.transaction.failures} carries three series
     * under its bounded {@code operation} tag, one each for {@code update}, {@code cycle-close} and
     * {@code posting}. Sixteen series in total.
     *
     * <p>Adding a measurement means adding one meter field and one record method here.
     *
     * <p>Four callers record: {@code domain/AccountUpdateService} the four update meters,
     * {@code domain/BillingCycleService} the two cycle-close meters, {@code outbox/OutboxRelay} the
     * five relay meters, and {@code messaging/TransactionPostedConsumer} the five consumption
     * meters including {@link AccountMeters#recordEventConsumed()}.
     * {@code config/KafkaConsumerConfig} records the two dead-letter meters for a record it routes.
     * The relay records once its transaction has committed, so no publisher, repository or template
     * increments a meter behind it. Every meter registers at start-up, so each one is scrapable
     * before its caller first records against it.</p>
     */
    public static final class AccountMeters {

        /**
         * Events this service consumed, one increment per {@code TransactionPosted} delivery.
         *
         * <p>{@code messaging/TransactionPostedConsumer} increments it as a delivery is read, before
         * the marker decides whether it is a duplicate, so the count is deliveries taken from the
         * topic rather than postings applied. {@link #postingApplied} and
         * {@link #postingDuplicatesSkipped} separate those two outcomes.
         */
        private final Counter eventsConsumed;

        /**
         * Wall time of one posted-transaction delivery, from the read to the commit or the failure.
         *
         * <p>No COBOL ancestor: the batch posting program counted records at
         * {@code app/cbl/CBTRN02C.cbl:L185} and timed nothing.
         */
        private final Timer postingLatency;

        /**
         * Posted amounts applied to the account record, one per committed delivery.
         *
         * <p>Each increment is one reproduction of {@code app/cbl/CBTRN02C.cbl:L545-L560} on the
         * record this service owns.
         */
        private final Counter postingApplied;

        /**
         * Duplicate deliveries the processed-event marker suppressed.
         *
         * <p>Kafka delivers at least once, so a rise here is ordinary rather than a fault. A rise
         * without {@link #postingApplied} rising signals that a partition is being redelivered.
         */
        private final Counter postingDuplicatesSkipped;

        /**
         * Wall time of one account update, from request entry to commit. No COBOL ancestor: the
         * batch posting program counted records at {@code app/cbl/CBTRN02C.cbl:L185} and timed
         * nothing.
         */
        private final Timer updateLatency;

        /** Account updates that committed. No COBOL ancestor. */
        private final Counter updateApplied;

        /**
         * Submitted account and customer fields that failed validation. No COBOL ancestor.
         */
        private final Counter validationFailed;

        /**
         * Billing cycle closes that committed. One close zeroes both cycle accumulators, which the
         * interest program does at {@code app/cbl/CBACT04C.cbl:L353-L354}.
         */
        private final Counter cycleClosed;

        /**
         * Outbox rows published to the broker. The source handoff is the single transient data
         * queue write at {@code app/cbl/CORPT00C.cbl:L517-L518}.
         */
        private final Counter outboxPublished;

        /**
         * Outbox publish attempts that failed, counted once per attempt against one row.
         * {@code outbox/OutboxRelay} is the only writer: it sees a refused publish, an event type it
         * has no topic for, an expired sweep deadline and a claim recovered from a dead instance,
         * and the publisher sees only the first of those. The source answer to a failed write is
         * {@code 9999-ABEND-PROGRAM} at {@code app/cbl/CBTRN02C.cbl:L707-L711}, four lines that
         * display one message, move 999 into an abend code, and call {@code CEE3ABD}.
         */
        private final Counter publishFailed;

        /**
         * Outbox rows this service gave up on, after
         * {@link com.carddemo.account.entity.OutboxEventEntity#MAX_DELIVERY_ATTEMPTS} attempts. One
         * increment is one account state change that will not be published, which is why it is
         * counted apart from the attempts that preceded it.
         */
        private final Counter outboxAbandoned;

        /**
         * Terminal diagnostics the broker acknowledged. Each one names an abandoned row on the
         * dead-letter topic, so an operator can reach the event without its payload leaving this
         * service.
         */
        private final Counter deadLettersPublished;

        /**
         * Terminal diagnostic attempts the broker refused. The row keeps its durable obligation and
         * a later sweep offers the diagnostic again, so this counter rising while
         * {@link #deadLettersPublished} stays flat is the one signal that an abandoned event is
         * recorded nowhere yet.
         */
        private final Counter deadLettersFailed;

        /** Transaction rollbacks or commit failures, keyed by the bounded operation name. */
        private final Map<String, Counter> transactionFailures;

        /**
         * @param registry the meter registry every meter registers against
         */
        AccountMeters(MeterRegistry registry) {
            Objects.requireNonNull(registry, "registry");
            this.eventsConsumed = Counter.builder("carddemo.account.events.consumed")
                    .description("Posted-transaction deliveries this service read")
                    .register(registry);
            this.postingLatency = Timer.builder("carddemo.account.posting.latency")
                    .description("Wall time of one posted-transaction delivery")
                    .register(registry);
            this.postingApplied = Counter.builder("carddemo.account.posting.applied")
                    .description("Posted amounts applied to the account record")
                    .register(registry);
            this.postingDuplicatesSkipped = Counter
                    .builder("carddemo.account.posting.duplicates.skipped")
                    .description("Duplicate posted-transaction deliveries the marker suppressed")
                    .register(registry);
            this.updateLatency = Timer.builder("carddemo.account.update.latency")
                    .description("Wall time of one account update, from request entry to commit")
                    .register(registry);
            this.updateApplied = Counter.builder("carddemo.account.update.applied")
                    .description("Account updates that committed")
                    .register(registry);
            this.validationFailed = Counter.builder("carddemo.account.validation.failed")
                    .description("Submitted account or customer fields rejected by validation")
                    .register(registry);
            this.cycleClosed = Counter.builder("carddemo.account.cycle.closed")
                    .description("Billing cycle closes that committed")
                    .register(registry);
            this.outboxPublished = Counter.builder("carddemo.account.outbox.published")
                    .description("Outbox rows published to the broker")
                    .register(registry);
            this.publishFailed = Counter.builder("carddemo.account.publish.failed")
                    .description("Outbox publish attempts that failed")
                    .register(registry);
            this.outboxAbandoned = Counter.builder("carddemo.account.outbox.abandoned")
                    .description("Outbox rows given up on after exhausting their attempts")
                    .register(registry);
            this.deadLettersPublished = Counter
                    .builder("carddemo.account.dead.letters.published")
                    .description("Terminal diagnostics the broker acknowledged")
                    .register(registry);
            this.deadLettersFailed = Counter.builder("carddemo.account.dead.letters.failed")
                    .description("Terminal diagnostic attempts the broker refused")
                    .register(registry);
            Map<String, Counter> failures = new LinkedHashMap<>();
            for (String operation : java.util.List.of(UPDATE_OPERATION, CYCLE_CLOSE_OPERATION,
                    POSTING_OPERATION)) {
                failures.put(operation, Counter.builder("carddemo.account.transaction.failures")
                        .tag(OPERATION_TAG, operation)
                        .description("Account transactions that rolled back or failed to commit")
                        .register(registry));
            }
            this.transactionFailures = Map.copyOf(failures);
        }

        /**
         * @return the number of posted-transaction deliveries this service has read
         */
        public double eventsConsumedTotal() {
            return eventsConsumed.count();
        }

        /**
         * Records one posted-transaction delivery read from the topic.
         *
         * <p>Called as the delivery is read, ahead of the marker check, so the count reports what
         * arrived rather than what was applied.
         */
        public void recordEventConsumed() {
            eventsConsumed.increment();
        }

        /**
         * @param elapsed the wall time of the delivery, whether it committed or failed
         */
        public void recordPostingLatency(Duration elapsed) {
            postingLatency.record(elapsed);
        }

        public void recordPostingApplied() {
            postingApplied.increment();
        }

        public void recordPostingDuplicateSkipped() {
            postingDuplicatesSkipped.increment();
        }

        /**
         * Records one posted-transaction delivery whose transaction rolled back.
         *
         * <p>Counted once per failed attempt. A record the container gives up on is counted again as
         * a terminal outcome by {@link #recordDeadLetterPublished()} or
         * {@link #recordDeadLetterFailure()}, so an attempt and a spent record are never read as one
         * number.
         */
        public void recordPostingFailure() {
            transactionFailures.get(POSTING_OPERATION).increment();
        }

        /**
         * @param elapsed the wall time the update took, from request entry to commit
         */
        public void recordUpdateLatency(Duration elapsed) {
            updateLatency.record(elapsed);
        }

        /**
         * Counts one account update that committed.
         */
        public void recordUpdateApplied() {
            updateApplied.increment();
        }

        /**
         * Counts one submitted field that failed validation.
         */
        public void recordValidationFailure() {
            validationFailed.increment();
        }

        /**
         * Counts one billing cycle close that committed.
         */
        public void recordCycleClosed() {
            cycleClosed.increment();
        }

        /** Counts one account-update transaction that rolled back or failed to commit. */
        public void recordUpdateFailure() {
            transactionFailures.get(UPDATE_OPERATION).increment();
        }

        /** Counts one cycle-close transaction that rolled back or failed to commit. */
        public void recordCycleCloseFailure() {
            transactionFailures.get(CYCLE_CLOSE_OPERATION).increment();
        }

        /**
         * Counts the outbox rows one relay sweep published.
         *
         * @param rows the number of rows the broker accepted in this sweep
         */
        public void recordOutboxPublished(long rows) {
            outboxPublished.increment(rows);
        }

        /**
         * Counts one outbox publish attempt that failed.
         */
        public void recordPublishFailure() {
            publishFailed.increment();
        }

        /**
         * Counts one outbox row this service gave up on.
         */
        public void recordOutboxAbandoned() {
            outboxAbandoned.increment();
        }

        /**
         * Counts one terminal diagnostic the broker acknowledged.
         */
        public void recordDeadLetterPublished() {
            deadLettersPublished.increment();
        }

        /**
         * Counts one terminal diagnostic attempt the broker refused.
         */
        public void recordDeadLetterFailure() {
            deadLettersFailed.increment();
        }
    }

}
