package com.carddemo.fraud.config;

import java.math.BigDecimal;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * The {@code carddemo} block of {@code application.yml}, bound and checked at start-up.
 *
 * <p>ADDITIVE IN FULL. The CardDemo source holds no fraud module: no program under
 * {@code app/cbl/} scores risk, counts authorizations in a window or names a rule, so neither this
 * record nor the thresholds it carries has an ancestor.
 *
 * <p>Every value the fraud detection service takes from configuration arrives through this record.
 * A key with no component here is not configuration, and a component with no key fails start-up.
 *
 * <p>{@link Validated} runs the constraints below while the context builds. A risk threshold outside
 * zero to one hundred, a non-positive window or a negative amount therefore stops start-up with the
 * offending property named, rather than flagging or clearing every authorization at run time.
 *
 * <p>{@code FraudApplication} carries {@code @ConfigurationPropertiesScan}, which registers this
 * record as a bean. An injected instance is immutable.
 *
 * <p>Decisions: {@code card-platform/docs/decision-log.md} (planned).
 *
 * @param kafka    the topic names this service consumes and publishes
 * @param consumer the delivery-attempt settings a listener applies
 * @param outbox   the relay sweep settings
 * @param fraud    the risk thresholds the scoring rules read
 */
@ConfigurationProperties(prefix = "carddemo")
@Validated
public record FraudProperties(

        @NotNull @Valid Kafka kafka,

        @NotNull @Valid Consumer consumer,

        @NotNull @Valid Outbox outbox,

        @NotNull @Valid Fraud fraud) {

    /**
     * The broker-facing names this service uses.
     *
     * @param topics the three topics this service reads or writes
     */
    public record Kafka(@NotNull @Valid Topics topics) {

        /**
         * One name per event this service reads or writes.
         *
         * @param transactionAuthorized the consumed topic, read outside the authorization response
         *                              path
         * @param fraudAssessed         the topic both assessment outcomes travel on, separated by the
         *                              envelope event type
         * @param deadLetter            the topic a record reaches once its delivery attempts run out
         */
        public record Topics(

                @NotBlank String transactionAuthorized,

                @NotBlank String fraudAssessed,

                @NotBlank String deadLetter) {
        }
    }

    /**
     * The consume-side settings.
     *
     * @param retry the delivery attempts and the wait between two of them
     */
    public record Consumer(@NotNull @Valid Retry retry) {

        /**
         * How many times a listener takes one record before the record routes to the dead-letter
         * topic, and how long it waits between two attempts.
         *
         * @param maxAttempts delivery attempts, counting the first
         * @param backoffMs   milliseconds between two attempts
         */
        public record Retry(

                @Min(1) int maxAttempts,

                @PositiveOrZero long backoffMs) {
        }
    }

    /**
     * The transactional outbox settings.
     *
     * @param relay the sweep the relay performs
     */
    public record Outbox(@NotNull @Valid Relay relay) {

        /**
         * How often the relay sweeps unpublished rows, and how many it takes per sweep.
         *
         * @param fixedDelayMs milliseconds between the end of one sweep and the start of the next
         * @param batchSize    unpublished rows one sweep reads
         */
        public record Relay(

                @Positive long fixedDelayMs,

                @Positive int batchSize) {
        }
    }

    /**
     * The scoring settings this service owns.
     *
     * @param risk the thresholds the three rules read
     */
    public record Fraud(@NotNull @Valid Risk risk) {

        /**
         * The four thresholds the risk rules read. Every value is additive: the source performs no
         * risk assessment of any kind.
         *
         * @param flagThreshold           the score at or above which an assessment is flagged, on a
         *                                scale of zero to one hundred
         * @param velocityWindowMinutes   the width of the window the velocity rule counts over
         * @param velocityCountThreshold  authorizations in one window that trigger the velocity rule
         * @param amountAnomalyThreshold  the amount at or above which the anomaly rule triggers, at
         *                                the two-decimal scale of
         *                                {@code TRAN-AMT PIC S9(09)V99} at
         *                                {@code app/cpy/CVTRA05Y.cpy:L10}
         */
        public record Risk(

                @Min(0) @Max(100) int flagThreshold,

                @Positive int velocityWindowMinutes,

                @Positive int velocityCountThreshold,

                @NotNull @DecimalMin("0.00") BigDecimal amountAnomalyThreshold) {
        }
    }
}
