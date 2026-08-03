package com.carddemo.account.config;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Registers the meters the account service records against, and nothing else. Seven meters sit in
 * four groups: events consumed, processing latency, failure count, and throughput.
 *
 * <p>The batch posting program printed its two totals at
 * {@code app/cbl/CBTRN02C.cbl:L227-L228} and formatted a file status by hand at
 * {@code app/cbl/CBTRN02C.cbl:L714-L727}. Those two {@code DISPLAY} paths are the whole
 * observability surface of the source.
 *
 * <p>Every meter surfaces under {@code /actuator}, where the service exposes
 * {@code health,info,metrics,prometheus} and answers its container probe at
 * {@code http://localhost:8080/actuator/health}. No meter carries a tag. No meter name holds a
 * Social Security Number or a government-issued identifier, the two customer fields declared at
 * {@code app/cpy/CVCUS01Y.cpy:L17-L18}.
 *
 * <p>Each log line leaves as JavaScript Object Notation through
 * {@code net.logstash.logback:logstash-logback-encoder} 9.0. Adding a meter means adding one
 * {@code @Bean} method here. The message path these meters watch:
 * {@code card-platform/docs/event-flow.md}. Rationale for every choice in this class:
 * {@code card-platform/docs/decision-log.md}.
 */
@Configuration
public class ObservabilityConfig {

    /**
     * Counts the events this service consumed. ADDITIVE, with no COBOL ancestor.
     *
     * <p>The account service publishes on a state change and consumes nothing, so this count
     * stays at zero.
     *
     * @param registry the meter registry Spring Boot supplies
     * @return the counter of consumed events
     */
    @Bean
    public Counter eventsConsumedCounter(MeterRegistry registry) {
        return Counter.builder("account.events.consumed")
                .description("Events this service consumed from the event bus")
                .register(registry);
    }

    /**
     * Times one account update, from request entry to commit. ADDITIVE: the batch posting program
     * counted records at {@code app/cbl/CBTRN02C.cbl:L185} and timed nothing.
     *
     * @param registry the meter registry Spring Boot supplies
     * @return the timer of the account update path
     */
    @Bean
    public Timer accountUpdateTimer(MeterRegistry registry) {
        return Timer.builder("account.update.latency")
                .description("Wall time of one account update, from request entry to commit")
                .register(registry);
    }

    /**
     * Counts the outbox publish attempts that failed. The source answer to a failed write is
     * {@code 9999-ABEND-PROGRAM} at {@code app/cbl/CBTRN02C.cbl:L707-L711}, four lines that
     * display one message, move 999 into an abend code, and call {@code CEE3ABD}.
     *
     * @param registry the meter registry Spring Boot supplies
     * @return the counter of failed publish attempts
     */
    @Bean
    public Counter publishFailureCounter(MeterRegistry registry) {
        return Counter.builder("account.publish.failed")
                .description("Outbox publish attempts that failed")
                .register(registry);
    }

    /**
     * Counts the submitted account and customer fields that failed validation.
     *
     * <p>Tracks {@code WS-REJECT-COUNT}, declared at {@code app/cbl/CBTRN02C.cbl:L186} and
     * printed at {@code app/cbl/CBTRN02C.cbl:L228}. A count above zero moves 4 into
     * {@code RETURN-CODE} at {@code app/cbl/CBTRN02C.cbl:L229-L230}, which ends the job normally.
     *
     * @param registry the meter registry Spring Boot supplies
     * @return the counter of validation rejections
     */
    @Bean
    public Counter validationFailureCounter(MeterRegistry registry) {
        return Counter.builder("account.validation.failed")
                .description("Submitted account or customer fields rejected by validation")
                .register(registry);
    }

    /**
     * Counts the account updates that committed. Tracks {@code WS-TRANSACTION-COUNT}, declared at
     * {@code app/cbl/CBTRN02C.cbl:L185} and printed at {@code app/cbl/CBTRN02C.cbl:L227}.
     *
     * @param registry the meter registry Spring Boot supplies
     * @return the counter of committed account updates
     */
    @Bean
    public Counter appliedUpdateCounter(MeterRegistry registry) {
        return Counter.builder("account.update.applied")
                .description("Account updates that committed")
                .register(registry);
    }

    /**
     * Counts the billing cycle closes that committed. One close zeroes both cycle accumulators,
     * which the interest program does at {@code app/cbl/CBACT04C.cbl:L353-L354}.
     *
     * @param registry the meter registry Spring Boot supplies
     * @return the counter of committed cycle closes
     */
    @Bean
    public Counter cycleCloseCounter(MeterRegistry registry) {
        return Counter.builder("account.cycle.closed")
                .description("Billing cycle closes that committed")
                .register(registry);
    }

    /**
     * Counts the outbox rows published to the broker. The source handoff is the single transient
     * data queue write at {@code app/cbl/CORPT00C.cbl:L517-L518}.
     *
     * @param registry the meter registry Spring Boot supplies
     * @return the counter of published outbox rows
     */
    @Bean
    public Counter outboxPublishedCounter(MeterRegistry registry) {
        return Counter.builder("account.outbox.published")
                .description("Outbox rows published to the broker")
                .register(registry);
    }
}
