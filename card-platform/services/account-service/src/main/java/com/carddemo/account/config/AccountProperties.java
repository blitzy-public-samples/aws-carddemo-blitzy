package com.carddemo.account.config;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import java.time.Duration;
import java.time.temporal.ChronoUnit;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.convert.DurationUnit;
import org.springframework.validation.annotation.Validated;

/**
 * The {@code carddemo} block of {@code application.yml}, bound and checked at start-up.
 *
 * <p>The account programs {@code app/cbl/COACTVWC.cbl} and
 * {@code app/cbl/COACTUPC.cbl} read no configuration file, and the interest job
 * {@code app/jcl/INTCALC.jcl} passes its one parameter as a Job Control Language literal, so no
 * configuration record has an ancestor here.
 *
 * <p>Every value the account service takes from configuration arrives through this record. A key
 * with no component here is not configuration, and a component with no key fails start-up.
 *
 * <p>{@link Validated} runs the constraints below while the context builds. A blank topic name or a
 * non-positive relay delay therefore stops start-up with the offending property named.
 *
 * <p>{@code AccountApplication} carries {@code @ConfigurationPropertiesScan}, which registers this
 * record as a bean. An injected instance is immutable.
 *
 * <p>Decisions: {@code card-platform/docs/decision-log.md}.
 *
 * @param api      the request-body ceiling of the web surface
 * @param kafka    the topic and group names this service uses
 * @param consumer the delivery-attempt policy of the one listener this service runs
 * @param outbox   the relay sweep settings
 * @param processedEvent how long a duplicate-delivery marker is kept
 * @param retention the retention windows the sweep applies, and its interval
 * @param write    the bound on how long one locked read waits
 */
@ConfigurationProperties(prefix = "carddemo")
@Validated
public record AccountProperties(

        @NotNull @Valid Api api,

        @NotNull @Valid Kafka kafka,

        @NotNull @Valid Consumer consumer,

        @NotNull @Valid Outbox outbox,

        @NotNull @Valid ProcessedEvent processedEvent,

        @NotNull @Valid Retention retention,

        @NotNull @Valid Write write) {

    /**
     * Bounds how long one locked read waits for a row another writer holds.
     *
     * <p>PostgreSQL waits forever by default, so a contended update held the request open for as long
     * as the other writer held the row, and the documented lock-failure answer was unreachable through
     * contention: the wait either ended in a lock or never ended at all.
     *
     * <p>The bound is applied as a transaction-local {@code lock_timeout}, so it governs the locked
     * reads of one update and nothing else. A datasource-wide setting would also bound a schema
     * migration and the relay sweep, and a migration that gives up on a lock leaves a half-applied
     * schema.
     *
     * @param lockWaitMs longest one locked read waits, in milliseconds, before the datastore refuses
     *                   the lock and the documented lock-failure answer is returned
     */
    public record Write(@Positive long lockWaitMs) {
    }

    /**
     * Bounds the request parser before domain validation begins.
     *
     * @param maxRequestBodyBytes largest declared request body the service accepts
     */
    public record Api(@Positive long maxRequestBodyBytes) {
    }

    /**
     * The broker-facing names this service uses.
     *
     * @param topics the topics this service publishes to and the one it reads
     * @param groups the consumer group the one listener of this service runs under
     */
    public record Kafka(@NotNull @Valid Topics topics, @NotNull @Valid Groups groups) {

        /**
         * The topic names. An account read publishes nothing and reads nothing.
         *
         * @param accountStateChanged     the topic an account update, a billing-cycle close and a
         *                                posted transaction travel on. The cycle close reproduces
         *                                the two accumulator statements at
         *                                {@code app/cbl/CBACT04C.cbl:L353-L354}
         * @param customerContextChanged  the topic the ten cardholder fields of
         *                                {@code app/cpy/CVCUS01Y.cpy:L6-L22} travel on when a
         *                                customer update changes one of them
         * @param transactionPosted       the topic this service reads, carrying one posted amount per
         *                                record. {@code messaging/TransactionPostedConsumer} adds
         *                                that amount to the account record, reproducing
         *                                {@code app/cbl/CBTRN02C.cbl:L545-L560}
         * @param deadLetter              the one topic every record no listener could consume
         *                                reaches, shared by every service
         */
        public record Topics(

                @NotBlank String accountStateChanged,

                @NotBlank String customerContextChanged,

                @NotBlank String transactionPosted,

                @NotBlank String deadLetter) {
        }

        /**
         * The consumer groups this service reads under, one per listener.
         *
         * @param transactionPosted the group the posted-transaction listener joins. It is this
         *                          service's own, so the notification service reading the same topic
         *                          under its own group receives every record too
         */
        public record Groups(@NotBlank String transactionPosted) {
        }
    }

    /**
     * The delivery-attempt policy of the one listener this service runs.
     *
     * @param retry how many times a record is taken, and how long the wait between two attempts is
     */
    public record Consumer(@NotNull @Valid Retry retry) {

        /**
         * How many times a listener takes one record before the record routes to the dead-letter
         * topic, and how long it waits between two attempts.
         *
         * <p>No COBOL ancestor. {@code app/cbl/CBTRN02C.cbl:L707-L711} answered a fault by calling
         * the abend service on the first failure, so the source retried nothing.
         *
         * @param maxAttempts deliveries of one record, counting the first
         * @param backoffMs   milliseconds between two deliveries
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
         * How often the relay sweeps due rows, how many it takes per sweep, which instance it says
         * it is, and how long one of its claims may stand.
         *
         * <p>{@code instanceId} is written into {@code outbox_event.claimed_by}, so a claim can be
         * traced to a process. It has to differ per replica, which is why the shipped file derives it
         * from the host name rather than carrying a literal.
         *
         * <p>{@code claimTimeout} is how long a claim stands before another sweep treats the claiming
         * instance as dead and returns the row to {@code PENDING}. It also caps the retry backoff, so
         * a row the broker keeps refusing is always claimable again within one timeout.
         *
         * <p>{@code publishTimeout} is how long one send waits for the broker. A send that does not
         * complete inside it leaves the row unpublished, so the next sweep claims it again.
         *
         * @param fixedDelayMs   milliseconds between the end of one sweep and the start of the next
         * @param batchSize      unpublished rows one sweep reads
         * @param instanceId     what this instance writes into {@code outbox_event.claimed_by}
         * @param claimTimeout   how long one claim stands before another sweep recovers the row
         * @param maxDurationMs  maximum wall time one sweep may spend waiting on sends
         * @param publishTimeout longest one send waits for the broker
         */
        public record Relay(
                @Positive long fixedDelayMs,

                @Positive int batchSize,

                @NotBlank String instanceId,

                @NotNull @DurationUnit(ChronoUnit.SECONDS) Duration claimTimeout,

                @Positive @Max(300000) long maxDurationMs,

                @NotNull @DurationUnit(ChronoUnit.SECONDS) Duration publishTimeout) {

            public Relay {
                if (claimTimeout != null
                        && (claimTimeout.isZero() || claimTimeout.isNegative())) {
                    throw new IllegalArgumentException(
                            "carddemo.outbox.relay.claim-timeout must be positive");
                }
                if (publishTimeout != null
                        && (publishTimeout.isZero() || publishTimeout.isNegative())) {
                    throw new IllegalArgumentException(
                            "carddemo.outbox.relay.publish-timeout must be positive");
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

    /** @param sweepIntervalMs milliseconds between retention sweeps */
    public record Retention(@Positive long sweepIntervalMs) {
    }
}
