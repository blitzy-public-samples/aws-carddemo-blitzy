package com.carddemo.fraud.config;

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
 * Supplies the one bean the fraud detection service records its measurements through.
 *
 * <p>No COBOL ancestor. Searching {@code app/cbl/} for {@code fraud},
 * {@code velocit}, {@code risk} and {@code scoring} matches zero of its 28 programs.
 *
 * <p>{@link FraudMeters} holds nine meters under five names, covering the three families a
 * demonstration shows: events consumed, processing latency, and failure count. Spring Boot supplies
 * the {@link MeterRegistry}, and all nine register at start-up, so a scrape taken before the first
 * message lists them at zero.
 *
 * <p>Two of those names measure failure, and they count different things on purpose.
 * {@code carddemo.fraud.failures} counts one delivery or publish ATTEMPT, tagged by the stage that
 * failed, which is what makes a retry storm visible. {@code carddemo.fraud.dead.letters} counts one
 * RECORD whose attempts are spent, tagged by what became of its diagnostic. Without the second name a
 * retried record and a permanently lost one are the same reading, which is the distinction an
 * operator needs first. Nothing is counted twice: the two names have different denominators, and the
 * Javadoc of each recording method names its own.
 *
 * <p>The two counter names follow {@code WS-TRANSACTION-COUNT} and {@code WS-REJECT-COUNT} at
 * {@code app/cbl/CBTRN02C.cbl:L185-L186}, printed to the job log at
 * {@code app/cbl/CBTRN02C.cbl:L227-L230} (shape only, no logic). These meters replace that job-log
 * output, the file-status formatter at {@code app/cbl/CBTRN02C.cbl:L714-L727}, and the four-line
 * abend at {@code app/cbl/CBTRN02C.cbl:L707-L711} (both shape only, no logic).
 *
 * <p>A flagged assessment is a normal outcome, counted under {@code outcome=flagged} and never on
 * {@code carddemo.fraud.failures}. The batch program moved 4 into {@code RETURN-CODE} for a
 * rejected record at {@code app/cbl/CBTRN02C.cbl:L229-L230} and raised no abend (shape only, no
 * logic).
 *
 * <p>No meter name and no tag value holds a transaction identifier, an account identifier, a card
 * number, a card verification value or an event identifier. Every tag value is one literal from a
 * closed set, so the meter count stays fixed.
 *
 * <p>Two facts a reader needs. Every class in {@code com.carddemo.cobol} is final, holds static
 * members only, and keeps a private constructor, so no bean method returns one. This module
 * compiles at release 25 while the Spring Boot parent defaults to 17, and class-file major
 * version 69 is the proof.
 */
@Configuration
public class ObservabilityConfig {

    /** Tag key that names this service on every application and framework meter. */
    public static final String SERVICE_TAG = "service";

    /**
     * Registers the nine fraud meters and publishes them as one injectable bean.
     *
     * @param registry the meter registry Spring Boot auto-configuration supplies
     * @return the facade every measured path in this service records through
     */
    @Bean
    public FraudMeters fraudMeters(MeterRegistry registry) {
        return new FraudMeters(registry);
    }

    /** Adds the service name to application, Java Virtual Machine, and web meters alike. */
    @Bean
    public MeterRegistryCustomizer<MeterRegistry> fraudCommonTags(
            @Value("${spring.application.name:fraud-detection-service}") String applicationName) {
        if (applicationName == null || applicationName.isBlank()) {
            throw new IllegalStateException("spring.application.name must hold a value");
        }
        return registry -> registry.config().commonTags(SERVICE_TAG, applicationName);
    }

    /**
     * The recording surface of this service. One method names one measured path.
     *
     * <p>The path that calls each method, in the order the methods appear below:
     * {@code messaging/TransactionAuthorizedConsumer.java} calls
     * {@link FraudMeters#recordEventConsumed()},
     * {@link FraudMeters#recordProcessingLatency(Duration)},
     * {@link FraudMeters#recordProcessFailure()}, and — once the outbox row for one assessment has
     * been written — {@link FraudMeters#recordAssessmentFlagged()} or
     * {@link FraudMeters#recordAssessmentCleared()}; {@code config/KafkaConsumerConfig.java} calls
     * {@link FraudMeters#recordDeserializeFailure()} and, once a record has been routed to the
     * dead-letter topic for the last time, {@link FraudMeters#recordDeadLetterPublished()} or
     * {@link FraudMeters#recordDeadLetterFailure()}; and {@code outbox/OutboxRelay.java} calls
     * {@link FraudMeters#recordPublishFailure()} and the same two terminal methods for a row it can
     * never publish. Every meter registers at start-up, so each series is scrapable before its caller
     * records against it.</p>
     *
     * <p>{@link FraudMeters#recordDeserializeFailure()} is called from the consumer configuration
     * rather than from a listener because a payload that did not read never reaches a listener: the
     * container raises that failure first, and the recoverer is the only place it is observable.
     * {@link FraudMeters#recordProcessFailure()} counts once per delivery attempt and the recoverer
     * adds nothing to it, so one business failure is never reported as several. The recoverer records
     * the terminal outcome under {@code carddemo.fraud.dead.letters} instead, once for the record
     * rather than once per attempt.</p>
     */
    public static final class FraudMeters {

        private final Counter eventsConsumed;
        private final Counter assessmentsFlagged;
        private final Counter assessmentsCleared;
        private final Timer processingLatency;
        private final Counter deserializeFailures;
        private final Counter processFailures;
        private final Counter publishFailures;
        private final Counter deadLettersPublished;
        private final Counter deadLettersFailed;

        /** Tag value of a diagnostic the broker acknowledged on the dead-letter topic. */
        public static final String DEAD_LETTER_PUBLISHED = "published";

        /** Tag value of a diagnostic the broker refused. */
        public static final String DEAD_LETTER_FAILED = "failed";

        /** Registers all nine meters against {@code registry}. */
        FraudMeters(MeterRegistry registry) {
            Objects.requireNonNull(registry, "registry");
            this.eventsConsumed = Counter.builder("carddemo.fraud.events.consumed")
                    .description("Events read from topic transaction.authorized")
                    .register(registry);
            this.assessmentsFlagged = Counter.builder("carddemo.fraud.assessments.produced")
                    .tag("outcome", "flagged")
                    .description("Assessment events written to the outbox, by outcome")
                    .register(registry);
            this.assessmentsCleared = Counter.builder("carddemo.fraud.assessments.produced")
                    .tag("outcome", "cleared")
                    .description("Assessment events written to the outbox, by outcome")
                    .register(registry);
            this.processingLatency = Timer.builder("carddemo.fraud.processing.latency")
                    .description("Wall time of one event, from listener entry to commit")
                    .register(registry);
            this.deserializeFailures = Counter.builder("carddemo.fraud.failures")
                    .tag("stage", "deserialize")
                    .description("Processing faults, tagged by the stage that failed")
                    .register(registry);
            this.processFailures = Counter.builder("carddemo.fraud.failures")
                    .tag("stage", "process")
                    .description("Processing faults, tagged by the stage that failed")
                    .register(registry);
            this.publishFailures = Counter.builder("carddemo.fraud.failures")
                    .tag("stage", "publish")
                    .description("Processing faults, tagged by the stage that failed")
                    .register(registry);
            this.deadLettersPublished = Counter.builder("carddemo.fraud.dead.letters")
                    .tag("outcome", DEAD_LETTER_PUBLISHED)
                    .description("Records whose attempts are spent, by what became of the"
                            + " diagnostic naming them")
                    .register(registry);
            this.deadLettersFailed = Counter.builder("carddemo.fraud.dead.letters")
                    .tag("outcome", DEAD_LETTER_FAILED)
                    .description("Records whose attempts are spent, by what became of the"
                            + " diagnostic naming them")
                    .register(registry);
        }

        /** Counts one event read from topic {@code transaction.authorized}. */
        public void recordEventConsumed() {
            eventsConsumed.increment();
        }

        /** Counts one assessment that flagged a transaction. A flag is not a fault. */
        public void recordAssessmentFlagged() {
            assessmentsFlagged.increment();
        }

        /** Counts one assessment that cleared a transaction. */
        public void recordAssessmentCleared() {
            assessmentsCleared.increment();
        }

        /**
         * Records how long one event took, from listener entry to commit.
         *
         * @param elapsed the wall time the listener spent on one event
         */
        public void recordProcessingLatency(Duration elapsed) {
            processingLatency.record(elapsed);
        }

        /**
         * Counts one message the deserializer or the schema validator rejected.
         *
         * <p>Called from {@code config/KafkaConsumerConfig}, which is where such a failure is
         * observable. The container raises it before invoking a listener, so a listener cannot see
         * one.
         */
        public void recordDeserializeFailure() {
            deserializeFailures.increment();
        }

        /**
         * Counts one event whose scoring or whose database write did not complete.
         *
         * <p>One increment per delivery attempt, which is what makes a retry storm visible. The
         * terminal recovery adds nothing here: it records
         * {@link #recordDeadLetterPublished()} or {@link #recordDeadLetterFailure()} instead, once
         * for the record rather than once per attempt.
         */
        public void recordProcessFailure() {
            processFailures.increment();
        }

        /**
         * Counts one outbox row the broker did not accept.
         *
         * <p>One increment per publish attempt, whether the row will be attempted again or has just
         * been dead-lettered. The terminal series says which of the two it was.
         */
        public void recordPublishFailure() {
            publishFailures.increment();
        }

        /**
         * Counts one record this service gave up on, whose diagnostic the broker acknowledged.
         *
         * <p>One increment per RECORD and never per attempt, so this reading is the number of
         * assessments or deliveries permanently lost to their consumers. It is recorded by
         * {@code config/KafkaConsumerConfig.java} for a delivery the container recovered from for the
         * last time, and by {@code outbox/OutboxRelay.java} for a row no tick can ever publish. Both
         * record it after the diagnostic has reached the broker, so a reading here means an operator
         * can find the record on the dead-letter topic.
         */
        public void recordDeadLetterPublished() {
            deadLettersPublished.increment();
        }

        /**
         * Counts one record this service gave up on whose diagnostic the broker refused.
         *
         * <p>This is the reading that matters most and is expected to stay at zero: the record is
         * spent and nothing names it on any topic. It is separate from
         * {@link #recordPublishFailure()} because that series concerns rows still in flight.
         */
        public void recordDeadLetterFailure() {
            deadLettersFailed.increment();
        }
    }
}
