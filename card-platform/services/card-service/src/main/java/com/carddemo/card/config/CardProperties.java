package com.carddemo.card.config;

import java.time.Duration;
import java.time.temporal.ChronoUnit;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.convert.DurationUnit;
import org.springframework.validation.annotation.Validated;

/**
 * The {@code carddemo} block of {@code application.yml}, bound and checked at start-up.
 *
 * <p>The card programs {@code app/cbl/COCRDLIC.cbl},
 * {@code app/cbl/COCRDSLC.cbl} and {@code app/cbl/COCRDUPC.cbl} read no configuration file, so no
 * configuration record has an ancestor here.
 *
 * <p>Every value the card service takes from configuration arrives through this record. A key with
 * no component here is not configuration, and a component with no key fails start-up.
 *
 * <p>{@link Validated} runs the constraints below while the context builds. A blank topic name or a
 * non-positive relay delay therefore stops start-up with the offending property named.
 *
 * <p>{@code CardApplication} carries {@code @ConfigurationPropertiesScan}, which registers this
 * record as a bean. An injected instance is immutable. This module registers no listener, so it
 * carries no consumer group and no retry setting.
 *
 * <p>Decisions: {@code card-platform/docs/decision-log.md}.
 *
 * @param api    the request-body ceiling of the web surface
 * @param kafka  the topic names this service publishes to
 * @param outbox the relay sweep settings
 * @param processedEvent how long a duplicate-delivery marker is kept
 * @param retention the retention windows the sweep applies, and its interval
 * @param write  the bound on how long one locked read waits
 */
@ConfigurationProperties(prefix = "carddemo")
@Validated
public record CardProperties(

        @NotNull @Valid Api api,

        @NotNull @Valid Kafka kafka,

        @NotNull @Valid Outbox outbox,

        @NotNull @Valid ProcessedEvent processedEvent,

        @NotNull @Valid Retention retention,

        @NotNull @Valid Write write) {

    /**
     * Ceiling on {@link Outbox.Relay#maxDurationMs()}, five minutes in milliseconds.
     *
     * <p>The bound exists so a misconfiguration cannot turn the pass deadline off. A sweep that
     * spends five minutes waiting for broker acknowledgements is a stalled relay, and the scheduled
     * thread it holds is the one thread every later card event waits behind.
     */
    static final long MAX_PASS_DURATION_MS = 300_000L;

    /**
     * Bounds how long one locked read waits for a row another writer holds.
     *
     * <p>The bound is applied as a transaction-local {@code lock_timeout}, so it governs the locked
     * reads of one update and nothing else. Rationale, including why it is not a datasource-wide
     * setting, sits in {@code card-platform/docs/decision-log.md}.
     *
     * @param lockWaitMs longest one locked read waits, in milliseconds, before the datastore refuses
     *                   the lock and the documented lock-failure answer is returned
     */
    public record Write(@Positive long lockWaitMs) {
    }

    /**
     * Bounds the request parser before card validation begins.
     *
     * @param maxRequestBodyBytes largest declared request body the service accepts
     */
    public record Api(@Positive long maxRequestBodyBytes) {
    }

    /**
     * The broker-facing names this service uses.
     *
     * @param topics the two topics this service publishes to
     */
    public record Kafka(@NotNull @Valid Topics topics) {

        /**
         * The two published topics. A card list and a card read publish nothing.
         *
         * <p>{@code deadLetter} is the shared destination of a terminal diagnostic and never of a
         * card update. {@code outbox/OutboxRelay} publishes one governed
         * {@code com.carddemo.events.DeadLetterEnvelope} onto it for a row it has abandoned, so an
         * event this service gave up on is still accounted for. A blank value stops start-up, which
         * is what keeps that path from being configured away.
         *
         * @param cardUpdated the topic a card update travels on, carrying the card after the update
         *                    with its number masked
         * @param deadLetter  the topic a terminal diagnostic travels on, shared by every service
         */
        public record Topics(@NotBlank String cardUpdated, @NotBlank String deadLetter) {
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
         * <p>{@code maxDurationMs} bounds one whole sweep on the monotonic clock. Without it a
         * sweep of {@code batchSize} rows could spend the publish timeout on each of them in turn,
         * so the time one sweep may take was the product of two settings naming no bound between
         * them.
         *
         * @param fixedDelayMs   milliseconds between the end of one sweep and the start of the next
         * @param batchSize      due rows one sweep claims
         * @param instanceId     what this instance writes into {@code claimed_by}
         * @param claimTimeout   how long a claim may stand before another sweep recovers the row
         * @param maxDurationMs  wall time one sweep may spend waiting for broker acknowledgements,
         *                       measured from {@link System#nanoTime()} and capped at
         *                       {@link CardProperties#MAX_PASS_DURATION_MS}
         * @param publishTimeout longest one send waits for the broker before
         *                       {@code messaging/KafkaEventPublisher} reports the attempt failed.
         *                       The row then stays unpublished and a later sweep claims it again
         */
        public record Relay(

                @Positive long fixedDelayMs,

                @Positive int batchSize,

                @NotBlank String instanceId,

                @NotNull @DurationUnit(ChronoUnit.SECONDS) Duration claimTimeout,

                @Positive @Max(MAX_PASS_DURATION_MS) long maxDurationMs,

                @NotNull @DurationUnit(ChronoUnit.SECONDS) Duration publishTimeout) {

            /**
             * Refuses a claim timeout that is not positive, and a publish timeout that gives a send
             * no time at all.
             *
             * <p>A claim timeout of zero or less would make every claim stranded the moment it was
             * taken, so one sweep would recover the batch the previous sweep is still publishing —
             * which is the double publish the claim exists to prevent.
             *
             * <p>{@code publishTimeout} reached the publisher through a second binding of the same
             * property, a constructor {@code @Value}, which meets no constraint declared here. Only
             * {@code null} was refused, so zero or a negative duration started the service and then
             * failed every send the instant it was issued. The value is a component of this record
             * now, so the binding that validates it is the binding the publisher reads.
             *
             * @throws IllegalArgumentException when either duration is zero or negative
             */
            public Relay {
                if (claimTimeout != null
                        && (claimTimeout.isZero() || claimTimeout.isNegative())) {
                    throw new IllegalArgumentException(
                            "outbox.relay.claimTimeout must be positive, found " + claimTimeout);
                }
                if (publishTimeout != null
                        && (publishTimeout.isZero() || publishTimeout.isNegative())) {
                    throw new IllegalArgumentException(
                            "outbox.relay.publishTimeout must be positive, found " + publishTimeout);
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
