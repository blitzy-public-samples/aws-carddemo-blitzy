package com.carddemo.account.config;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.time.Duration;
import java.util.Objects;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Supplies the one bean the account service records its measurements through.
 *
 * <p>{@link AccountMeters} holds six meters in three groups: processing latency, throughput, and
 * failure count. The account service publishes on a state change and consumes no event, so it
 * registers no consumed-event meter.
 *
 * <p>Every meter surfaces under {@code /actuator}, where the service exposes
 * {@code health,info,metrics,prometheus} and answers its container probe at
 * {@code http://localhost:8080/actuator/health}. No meter carries a tag. No meter name holds a
 * Social Security Number or a government-issued identifier, the two customer fields declared at
 * {@code app/cpy/CVCUS01Y.cpy:L17-L18}.
 *
 * <p>Each log line leaves as JavaScript Object Notation through
 * {@code net.logstash.logback:logstash-logback-encoder}.
 */
@Configuration
public class ObservabilityConfig {

    /**
     * Registers the six account meters and publishes them as one injectable bean.
     *
     * @param registry the meter registry Spring Boot supplies
     * @return the facade every measured path in this service records through
     */
    @Bean
    public AccountMeters accountMeters(MeterRegistry registry) {
        return new AccountMeters(registry);
    }

    /**
     * The recording surface of the account service. One method names one measured path, so a
     * caller injects {@code AccountMeters} and calls the method that matches the work it just did.
     *
     * <p>ADDITIVE. No COBOL program declares a meter. The two counters below track the two totals
     * the batch posting program printed, and the four remaining meters have no source ancestor.
     *
     * <p>Adding a measurement means adding one meter field and one record method here.
     */
    public static final class AccountMeters {

        /**
         * Wall time of one account update, from request entry to commit. ADDITIVE: the batch
         * posting program counted records at {@code app/cbl/CBTRN02C.cbl:L185} and timed nothing.
         */
        private final Timer updateLatency;

        /**
         * Account updates that committed. ADDITIVE, with no COBOL ancestor.
         */
        private final Counter updateApplied;

        /**
         * Submitted account and customer fields that failed validation. ADDITIVE, with no
         * COBOL ancestor.
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

        /**
         * Registers all six meters against {@code registry}. Each meter appears under
         * {@code /actuator/metrics} from startup, before any path records against it.
         *
         * @param registry the meter registry every meter registers against
         */
        AccountMeters(MeterRegistry registry) {
            Objects.requireNonNull(registry, "registry");
            this.updateLatency = Timer.builder("account.update.latency")
                    .description("Wall time of one account update, from request entry to commit")
                    .register(registry);
            this.updateApplied = Counter.builder("account.update.applied")
                    .description("Account updates that committed")
                    .register(registry);
            this.validationFailed = Counter.builder("account.validation.failed")
                    .description("Submitted account or customer fields rejected by validation")
                    .register(registry);
            this.cycleClosed = Counter.builder("account.cycle.closed")
                    .description("Billing cycle closes that committed")
                    .register(registry);
            this.outboxPublished = Counter.builder("account.outbox.published")
                    .description("Outbox rows published to the broker")
                    .register(registry);
            this.publishFailed = Counter.builder("account.publish.failed")
                    .description("Outbox publish attempts that failed")
                    .register(registry);
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
