package com.carddemo.authorization.config;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

import java.time.Duration;
import java.time.temporal.ChronoUnit;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.convert.DurationUnit;
import org.springframework.validation.annotation.Validated;

/**
 * The {@code carddemo} block of {@code application.yml}, bound and checked at start-up.
 *
 * <p>ADDITIVE IN FULL. No COBOL program and no copybook in this repository defines this type. The
 * source reads its operational values from Job Control Language parameters and from hard-coded
 * literals, so no configuration record has an ancestor here.
 *
 * <p>Every value the authorization service takes from configuration arrives through this record.
 * A key with no component here is not configuration, and a component with no key fails start-up.
 *
 * <p>{@link Validated} runs the constraints below while the context builds. A blank topic name, a
 * non-positive relay delay or a non-positive replica window therefore stops start-up with the
 * offending property named, rather than surfacing later as a message published to the empty-string
 * topic.
 *
 * <p>{@code AuthorizationApplication} carries {@code @ConfigurationPropertiesScan}, which registers
 * this record as a bean. An injected instance is immutable, so no component can change a value the
 * constraints already accepted.
 *
 * <p>Decisions: {@code card-platform/docs/decision-log.md}.
 *
 * @param kafka          the topic and consumer-group names this service publishes to and reads from
 * @param outbox         the relay and published-row retention settings
 * @param processedEvent the processed-marker retention setting
 * @param retention      the cleanup schedule
 * @param replica        the freshness policy applied to the two replica tables
 */
@ConfigurationProperties(prefix = "carddemo")
@Validated
public record AuthorizationProperties(

        @NotNull @Valid Kafka kafka,

        @NotNull @Valid Outbox outbox,

        @NotNull @Valid ProcessedEvent processedEvent,

        @NotNull @Valid Retention retention,

        @NotNull @Valid Replica replica) {

    /**
     * The broker-facing names this service uses.
     *
     * @param topics the two topics this service publishes to, the two it consumes and the
     *               dead-letter topic
     * @param groups one consumer group per replica listener
     */
    public record Kafka(@NotNull @Valid Topics topics, @NotNull @Valid Groups groups) {

        /**
         * One name per event this service publishes or consumes, and the dead-letter topic.
         *
         * <p>The two consumed topics are what keep {@code card_xref} and
         * {@code account_credit_snapshot} current. Both tables are replicas of data another service
         * owns, and the decline rules read them on every call, so a name here that reaches no topic
         * leaves those rules answering from whatever the replica last knew.
         *
         * @param transactionAuthorized the topic an approved authorization travels on, keyed by the
         *                              account identifier
         * @param transactionDeclined   the topic a declined authorization travels on, carrying one
         *                              of the four reject reasons of
         *                              {@code app/cbl/CBTRN02C.cbl:L385-L420}
         * @param accountStateChanged   the topic carrying the account state this service replicates
         *                              into {@code account_credit_snapshot}
         * @param cardUpdated           the topic carrying the card state this service reads to keep
         *                              {@code card_xref} observably current
         * @param deadLetter            the topic a record that cannot be applied is routed to
         */
        public record Topics(

                @NotBlank String transactionAuthorized,

                @NotBlank String transactionDeclined,

                @NotBlank String accountStateChanged,

                @NotBlank String cardUpdated,

                @NotBlank String deadLetter) {
        }

        /**
         * One consumer group per replica listener.
         *
         * <p>Two groups rather than one, because the two listeners read different topics and must be
         * able to lag, rebalance and be reset independently. One group across both would tie the
         * offsets of unrelated streams together.
         *
         * @param accountStateChanged group of the listener that applies account state
         * @param cardUpdated         group of the listener that applies card state
         */
        public record Groups(

                @NotBlank String accountStateChanged,

                @NotBlank String cardUpdated) {
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
         * How often the relay sweeps due rows, how many it claims, its instance name, and the claim
         * recovery window.
         *
         * @param fixedDelayMs milliseconds between the end of one sweep and the start of the next
         * @param batchSize    due rows one sweep claims
         * @param instanceId   value written into {@code outbox_event.claimed_by}
         * @param claimTimeout how long a claim may stand before another sweep recovers the row
         */
        public record Relay(

                @Positive long fixedDelayMs,

                @Positive int batchSize,

                @NotBlank String instanceId,

                @NotNull @DurationUnit(ChronoUnit.SECONDS) Duration claimTimeout) {

            /**
             * Refuses a claim timeout that cannot protect an active claim.
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

    /**
     * The retention policy of the sweep.
     *
     * @param sweepIntervalMs      milliseconds between retention sweeps
     * @param decisionRetentionDays days a decision row and an unresolved-card attempt remain. The
     *                             row is the attribution record of a financial decision, so it
     *                             outlives the outbox row the decision published
     */
    public record Retention(@Positive long sweepIntervalMs, @Positive long decisionRetentionDays) {
    }

    /**
     * The freshness policy applied to the two replica tables.
     *
     * <p>ADDITIVE. The source has no equivalent because it has no replica:
     * {@code app/cbl/CBTRN02C.cbl:L382} and {@code app/cbl/CBTRN02C.cbl:L395} read the
     * cross-reference and account datasets themselves, so nothing they read can be out of date.
     *
     * @param maxStaleness how old a replica observation may be and still be authorized against
     */
    public record Replica(

            @NotNull
            @DurationUnit(ChronoUnit.SECONDS)
            Duration maxStaleness) {

        /**
         * Holds the window above zero.
         *
         * <p>A zero or negative window would refuse every call, because no observation can be newer
         * than the moment it is compared against.
         *
         * @throws IllegalArgumentException when the window is not positive
         */
        public Replica {
            if (maxStaleness != null
                    && (maxStaleness.isZero() || maxStaleness.isNegative())) {
                throw new IllegalArgumentException(
                        "carddemo.replica.max-staleness must be positive, found " + maxStaleness);
            }
        }
    }
}
