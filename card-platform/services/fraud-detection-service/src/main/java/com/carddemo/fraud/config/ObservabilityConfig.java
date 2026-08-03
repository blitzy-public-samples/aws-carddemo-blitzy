package com.carddemo.fraud.config;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.time.Duration;
import java.util.Objects;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Supplies the one bean the fraud detection service records its measurements through.
 *
 * <p>ADDITIVE IN FULL: net new; no COBOL ancestor. Searching {@code app/cbl/} for {@code fraud},
 * {@code velocit}, {@code risk} and {@code scoring} matches zero of its 28 programs.
 *
 * <p>{@link FraudMeters} holds seven meters under four names, covering the three families a
 * demonstration shows: events consumed, processing latency, and failure count. Spring Boot supplies
 * the {@link MeterRegistry}, and all seven register at start-up, so a scrape taken before the first
 * message lists them at zero.
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
 *
 * <p>Rationale for the names and the bean shape: {@code card-platform/docs/decision-log.md}.
 */
@Configuration
public class ObservabilityConfig {

    /**
     * Registers the seven fraud meters and publishes them as one injectable bean.
     *
     * @param registry the meter registry Spring Boot auto-configuration supplies
     * @return the facade every measured path in this service records through
     */
    @Bean
    public FraudMeters fraudMeters(MeterRegistry registry) {
        return new FraudMeters(registry);
    }

    /** The recording surface of this service. One method names one measured path. */
    public static final class FraudMeters {

        private final Counter eventsConsumed;
        private final Counter assessmentsFlagged;
        private final Counter assessmentsCleared;
        private final Timer processingLatency;
        private final Counter deserializeFailures;
        private final Counter processFailures;
        private final Counter publishFailures;

        /** Registers all seven meters against {@code registry}. */
        FraudMeters(MeterRegistry registry) {
            Objects.requireNonNull(registry, "registry");
            this.eventsConsumed = Counter.builder("carddemo.fraud.events.consumed")
                    .description("Events read from topic transaction.authorized")
                    .register(registry);
            this.assessmentsFlagged = Counter.builder("carddemo.fraud.assessments.produced")
                    .tag("outcome", "flagged")
                    .description("Assessments published to topic fraud.assessed")
                    .register(registry);
            this.assessmentsCleared = Counter.builder("carddemo.fraud.assessments.produced")
                    .tag("outcome", "cleared")
                    .description("Assessments published to topic fraud.assessed")
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

        /** Counts one message the deserializer or the schema validator rejected. */
        public void recordDeserializeFailure() {
            deserializeFailures.increment();
        }

        /** Counts one event whose scoring or whose database write did not complete. */
        public void recordProcessFailure() {
            processFailures.increment();
        }

        /** Counts one outbox row the broker did not accept. */
        public void recordPublishFailure() {
            publishFailures.increment();
        }
    }
}
