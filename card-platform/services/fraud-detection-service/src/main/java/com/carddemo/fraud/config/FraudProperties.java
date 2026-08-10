package com.carddemo.fraud.config;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.temporal.ChronoUnit;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.convert.DurationUnit;
import org.springframework.validation.annotation.Validated;

/**
 * The {@code carddemo} block of {@code application.yml}, bound and checked at start-up.
 *
 * <p>The CardDemo source holds no fraud module: no program under
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
 * <p>Decisions: {@code card-platform/docs/decision-log.md}.
 *
 * @param kafka          the topic names this service consumes and publishes
 * @param consumer       the delivery-attempt settings a listener applies
 * @param outbox         the relay and published-row retention settings
 * @param processedEvent the processed-marker retention setting
 * @param retention      the cleanup schedule
 * @param fraud          the risk thresholds the scoring rules read
 */
@ConfigurationProperties(prefix = "carddemo")
@Validated
public record FraudProperties(

        @NotNull @Valid Kafka kafka,

        @NotNull @Valid Consumer consumer,

        @NotNull @Valid Outbox outbox,

        @NotNull @Valid ProcessedEvent processedEvent,

        @NotNull @Valid Retention retention,

        @NotNull @Valid Fraud fraud) {

    /**
     * Refuses a velocity retention horizon that does not outlast the window it retains.
     *
     * <p>The two settings live in different sub-records, so no per-field annotation can relate
     * them. Binding is the only place both are visible, and start-up is the only moment at which
     * refusing costs nothing: a horizon shorter than the window span would delete the window a
     * live authorization is counting into, and the symptom would be a burst that quietly stopped
     * triggering the velocity rule rather than an error anyone could see.
     *
     * <p>The comparison is strict. Equality is not enough, because a window that started exactly
     * one span ago is still the current window for an authorization arriving in the same instant.
     *
     * @throws IllegalArgumentException when the horizon does not exceed the window span
     */
    public FraudProperties {
        if (retention != null && fraud != null && fraud.risk() != null) {
            Duration horizon = Duration.ofDays(retention.velocityRetentionDays());
            Duration window = Duration.ofMinutes(fraud.risk().velocityWindowMinutes());
            if (horizon.compareTo(window) <= 0) {
                throw new IllegalArgumentException("carddemo.retention.velocity-retention-days of "
                        + horizon + " must exceed carddemo.fraud.risk.velocity-window-minutes of "
                        + window + ", or the sweep removes the window a live authorization is "
                        + "counting into");
            }
        }
    }

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
         * @param deadLetter            fallback topic when a failed record names no source topic
         * @param deadLetterSuffix      suffix appended to the source topic for its dead-letter topic
         */
        public record Topics(

                @NotBlank String transactionAuthorized,

                @NotBlank String fraudAssessed,

                @NotBlank String deadLetter,

                @NotBlank String deadLetterSuffix) {
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
     * @param relay                   the sweep the relay performs
     * @param publishedRetentionHours hours a published row remains for diagnosis
     */
    public record Outbox(
            @NotNull @Valid Relay relay,
            @Positive long publishedRetentionHours) {

        /**
         * How often the relay sweeps due rows, how many it takes per sweep, which instance it says it
         * is, and how long one of its claims may stand.
         *
         * <p>{@code instanceId} is written into {@code outbox_event.claimed_by}, so a claim can be
         * traced to a process. It has to differ per replica, which is why the shipped file derives it
         * from the host name rather than carrying a literal.
         *
         * <p>{@code claimTimeout} is how long a claim stands before another sweep treats the claiming
         * instance as dead and returns the row to {@code PENDING}. It also caps the retry backoff, so
         * a row the broker keeps refusing is always claimable again within one timeout.
         *
         * @param fixedDelayMs milliseconds between the end of one sweep and the start of the next
         * @param batchSize    unpublished rows one sweep reads
         * @param instanceId   identity written into {@code outbox_event.claimed_by}, distinct per
         *                     replica
         * @param claimTimeout how long a claim stands before another sweep reclaims the row, and the
         *                     cap on the retry backoff
         * @param maxDurationMs maximum wall time one sweep may spend waiting on sends
         */
        public record Relay(

                @Positive long fixedDelayMs,

                @Positive int batchSize,

                @NotBlank String instanceId,

                @NotNull @DurationUnit(ChronoUnit.SECONDS) Duration claimTimeout,

                @Positive @Max(300000) long maxDurationMs) {

            /**
             * Refuses a claim timeout that is not positive.
             *
             * <p>{@code @NotNull} catches an absent value and nothing else, so zero and a negative
             * duration both bound cleanly. Either one makes every claim stranded the moment it is
             * taken, and the next sweep then recovers the batch this sweep is still publishing —
             * which is the double publish the claim exists to prevent. The other four relays refuse
             * it; this one bound it, so a deployment could switch off the guarantee by setting one
             * variable to zero and nothing would say so.
             *
             * @throws IllegalArgumentException when {@code claimTimeout} is zero or negative
             */
            public Relay {
                if (claimTimeout != null
                        && (claimTimeout.isZero() || claimTimeout.isNegative())) {
                    throw new IllegalArgumentException(
                            "outbox.relay.claimTimeout must be positive, found " + claimTimeout);
                }
            }
        }
    }

    /**
     * The duplicate-marker horizon, and the broker retention it has to outlast.
     *
     * <p>A marker matters only while a redelivery of its event is still possible, and past that
     * point it is dead weight on a table every message passes through. That makes the horizon a
     * relationship rather than a number: the marker has to outlast every window through which the
     * record itself can come back. Broker log retention is the shortest of those windows and the
     * only one this platform configures, so it is the one the relationship is stated against.
     *
     * <p>The two shipped values were equal, which made the relationship an equality rather than a
     * margin. Segment cleanup is not instant, a restored backup can carry a record older than the
     * broker would still hold, and an operator resetting a consumer group replays whatever the log
     * still has. Any one of those leaves a record readable after its marker has been swept, and the
     * consumer then applies it a second time: for {@code account-posted} that means one transaction
     * amount reaching a balance and a cycle accumulator twice.
     *
     * <p>{@link #MINIMUM_RETENTION_MARGIN} is therefore enforced here rather than documented,
     * and at start-up rather than later, because the two values arrive from configuration and a
     * mismatch is invisible until the day a replay happens. The shipped pair is 720 hours of
     * markers against 168 hours of broker log, which is a margin above four.
     *
     * @param markerRetentionHours hours a processed-event marker remains
     * @param brokerRetentionHours hours the broker is configured to retain a topic log, which
     *                             {@code KAFKA_LOG_RETENTION_HOURS} sets for the broker and for
     *                             every service that has to outlast it
     */
    public record ProcessedEvent(@Positive long markerRetentionHours,
            @Positive long brokerRetentionHours) {

        /**
         * The smallest multiple of broker retention a marker horizon may be.
         *
         * <p>Two rather than one, because equality is what the review found: it leaves no room for
         * segment cleanup lag, a restored backup, or a manually replayed window. Two rather than a
         * larger figure, because the floor has to be one a deployment can meet by configuration
         * alone, and the shipped pair clears it four times over.
         */
        public static final long MINIMUM_RETENTION_MARGIN = 2L;

        /**
         * Refuses a marker horizon that does not outlast broker retention by the required margin.
         *
         * @throws IllegalArgumentException when the marker horizon is under the margin
         */
        public ProcessedEvent {
            if (markerRetentionHours > 0 && brokerRetentionHours > 0
                    && markerRetentionHours < brokerRetentionHours * MINIMUM_RETENTION_MARGIN) {
                throw new IllegalArgumentException(
                        "processed-event.markerRetentionHours must be at least "
                                + MINIMUM_RETENTION_MARGIN + " times"
                                + " processed-event.brokerRetentionHours, so a replayed record"
                                + " cannot outlive the marker that suppresses it. Found "
                                + markerRetentionHours + " against " + brokerRetentionHours);
            }
        }
    }

    /**
     * How often the retention sweep runs, and how long the two business tables of this service keep
     * a row.
     *
     * <p>Both horizons were declared before anything applied them. {@code COMMENT ON TABLE
     * fraud_assessment} and {@code COMMENT ON TABLE velocity_window} in
     * {@code src/main/resources/db/migration/V1__schema.sql} name ninety days and seven days
     * respectively, and {@code domain/RetentionSweep} deleted only outbox rows and duplicate
     * markers, so both numbers described an intention rather than the table. A security review
     * found the gap. The two components below are what the sweep now reads, so the declaration and
     * the behaviour come from one place.
     *
     * <p>Days rather than hours, because both declarations are written in days and a reader
     * comparing the catalogue comment against the setting should not have to divide.
     *
     * @param sweepIntervalMs          milliseconds between retention sweeps
     * @param assessmentRetentionDays  days a {@code fraud_assessment} row is kept, measured from
     *                                 {@code assessed_at}
     * @param velocityRetentionDays    days a {@code velocity_window} row is kept, measured from
     *                                 {@code window_start}. It has to exceed
     *                                 {@code carddemo.fraud.risk.velocity-window-minutes}, or the
     *                                 purge removes the bucket a live authorization is counting
     *                                 into; {@code domain/RetentionSweep} refuses to start
     *                                 otherwise
     */
    public record Retention(@Positive long sweepIntervalMs,
            @Positive int assessmentRetentionDays,
            @Positive int velocityRetentionDays) {
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
         * <p>{@code flagThreshold} starts at one rather than zero. Every rule contributes a positive
         * score and an untriggered rule contributes none, so a threshold of one or more is reached
         * only where a rule triggered. That is what lets {@code fraud_assessment} keep its
         * constraint that a flagged row names the rule which caused the flag, and it is why a
         * threshold of zero — which would flag a transaction no rule objected to — stops start-up.
         *
         * @param flagThreshold           the score at or above which an assessment is flagged, from
         *                                one through one hundred
         * @param velocityWindowMinutes   the width of the window the velocity rule counts over
         * @param velocityCountThreshold  authorizations in one window that trigger the velocity rule
         * @param amountAnomalyThreshold  the amount at or above which the anomaly rule triggers, at
         *                                the two-decimal scale of
         *                                {@code TRAN-AMT PIC S9(09)V99} at
         *                                {@code app/cpy/CVTRA05Y.cpy:L10}
         */
        public record Risk(

                @Min(1) @Max(100) int flagThreshold,

                @Positive int velocityWindowMinutes,

                @Positive int velocityCountThreshold,

                @NotNull @DecimalMin("0.00") BigDecimal amountAnomalyThreshold) {
        }
    }
}
