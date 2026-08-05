package com.carddemo.card.config;

import java.time.Duration;
import java.time.temporal.ChronoUnit;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import java.time.Duration;

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
 */
@ConfigurationProperties(prefix = "carddemo")
@Validated
public record CardProperties(

        @NotNull @Valid Api api,

        @NotNull @Valid Kafka kafka,

        @NotNull @Valid Outbox outbox,

        @NotNull @Valid ProcessedEvent processedEvent,

        @NotNull @Valid Retention retention) {

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
         * @param fixedDelayMs milliseconds between the end of one sweep and the start of the next
         * @param batchSize    due rows one sweep claims
         * @param instanceId   what this instance writes into {@code claimed_by}
         * @param claimTimeout how long a claim may stand before another sweep recovers the row
         */
        public record Relay(

                @Positive long fixedDelayMs,

                @Positive int batchSize,

                @NotBlank String instanceId,

                @NotNull @DurationUnit(ChronoUnit.SECONDS) Duration claimTimeout) {

            /**
             * Refuses a claim timeout that is not positive.
             *
             * <p>Zero or less would make every claim stranded the moment it was taken, so one sweep
             * would recover the batch the previous sweep is still publishing — which is the double
             * publish the claim exists to prevent.
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

    /** @param markerRetentionHours hours a processed-event marker remains */
    public record ProcessedEvent(@Positive long markerRetentionHours) {
    }

    /** @param sweepIntervalMs milliseconds between retention sweeps */
    public record Retention(@Positive long sweepIntervalMs) {
    }
}
