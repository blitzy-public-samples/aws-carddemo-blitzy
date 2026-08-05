package com.carddemo.account.config;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.micrometer.metrics.autoconfigure.MeterRegistryCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Supplies the one bean the account service records its measurements through.
 *
 * <p>{@link AccountMeters} holds nine series in four groups: events consumed, processing latency,
 * throughput, and failure count. Every name opens with {@code carddemo.account.}, which is the
 * namespace the fraud detection and notification services use.
 *
 * <p>The events-consumed counter stays at zero. The account service publishes on a state change and
 * consumes no event, and the counter is registered so every service exposes the same four metric
 * families. {@link AccountMeters} offers no method that increments it.
 *
 * <p>Every meter surfaces under {@code /actuator}, where the service exposes
 * {@code health,metrics,prometheus} and answers its container probe at
 * {@code http://localhost:8080/actuator/health}. Every meter carries the bounded
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
     * Registers the account meter set and publishes it as one injectable bean.
     *
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
     * <p>No COBOL program declares a meter. The two counters below track the two totals
     * the batch posting program printed, and the five remaining meters have no source ancestor.
     *
     * <p>Adding a measurement means adding one meter field and one record method here.
     *
     * <p>The path that calls each method: {@code domain/AccountUpdateService.java} calls
     * {@link AccountMeters#recordUpdateLatency(Duration)},
     * {@link AccountMeters#recordUpdateApplied()} and
     * {@link AccountMeters#recordValidationFailure()}, with
     * {@link AccountMeters#recordUpdateFailure()} on rollback;
     * {@code domain/BillingCycleService.java} calls
     * {@link AccountMeters#recordCycleClosed()} or
     * {@link AccountMeters#recordCycleCloseFailure()}; and {@code outbox/OutboxRelay.java} calls
     * {@link AccountMeters#recordOutboxPublished(long)} and
     * {@link AccountMeters#recordPublishFailure()}. The events-consumed counter has no caller and
     * takes none. Every meter registers at start-up, so each one is scrapable before its caller
     * records against it.</p>
     */
    public static final class AccountMeters {

        /**
         * Events this service consumed. The count stays at zero: the account service publishes on a
         * state change and reads no topic. The meter is registered so the events-consumed family
         * appears on every service, and this class declares no method that increments it.
         */
        private final Counter eventsConsumed;

        /**
         * Wall time of one account update, from request entry to commit. No COBOL ancestor: the
         * batch posting program counted records at {@code app/cbl/CBTRN02C.cbl:L185} and timed
         * nothing.
         */
        private final Timer updateLatency;

        /**
         * Account updates that committed. with no COBOL ancestor.
         */
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
         * Outbox publish attempts that failed. The source answer to a failed write is
         * {@code 9999-ABEND-PROGRAM} at {@code app/cbl/CBTRN02C.cbl:L707-L711}, four lines that
         * display one message, move 999 into an abend code, and call {@code CEE3ABD}.
         */
        private final Counter publishFailed;

        /** Transaction rollbacks or commit failures, keyed by the bounded operation name. */
        private final Map<String, Counter> transactionFailures;

        /**
         * Registers every meter against {@code registry}. Each meter appears under
         * {@code /actuator/metrics} from startup, before any path records against it.
         *
         * @param registry the meter registry every meter registers against
         */
        AccountMeters(MeterRegistry registry) {
            Objects.requireNonNull(registry, "registry");
            this.eventsConsumed = Counter.builder("carddemo.account.events.consumed")
                    .description("Events this service consumed, which stays at zero")
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
            Map<String, Counter> failures = new LinkedHashMap<>();
            for (String operation : java.util.List.of(UPDATE_OPERATION, CYCLE_CLOSE_OPERATION)) {
                failures.put(operation, Counter.builder("carddemo.account.transaction.failures")
                        .tag(OPERATION_TAG, operation)
                        .description("Account transactions that rolled back or failed to commit")
                        .register(registry));
            }
            this.transactionFailures = Map.copyOf(failures);
        }

        /**
         * Reads the events-consumed count, which stays at zero for this service. No method here
         * increments it.
         *
         * @return the number of events this service consumed, always zero
         */
        public double eventsConsumedTotal() {
            return eventsConsumed.count();
        }

        /**
         * Records how long one account update took.
         *
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
    }
}
