package com.carddemo.authorization.config;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
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
 * @param decision       the locking and reservation settings of one decision
 */
@ConfigurationProperties(prefix = "carddemo")
@Validated
public record AuthorizationProperties(

        @NotNull @Valid Kafka kafka,

        @NotNull @Valid Outbox outbox,

        @NotNull @Valid ProcessedEvent processedEvent,

        @NotNull @Valid Retention retention,

        @NotNull @Valid Replica replica,

        @NotNull @Valid Decision decision) {

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
     * Ceiling on {@link Outbox.Relay#maxDurationMs()}, five minutes in milliseconds.
     *
     * <p>The bound exists so a misconfiguration cannot turn the pass deadline off. A pass that spends
     * five minutes waiting for broker acknowledgements is a stalled relay, and the scheduled thread it
     * holds is the one thread every later event of every account waits behind.
     */
    static final long MAX_PASS_DURATION_MS = 300_000L;

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
         * @param fixedDelayMs   milliseconds between the end of one sweep and the start of the next
         * @param batchSize      due rows one sweep claims
         * @param instanceId     value written into {@code outbox_event.claimed_by}
         * @param claimTimeout   how long a claim may stand before another sweep recovers the row
         * @param maxDurationMs  wall time one pass may spend waiting for broker acknowledgements,
         *                       measured on the monotonic clock. A pass that reaches it stops, and
         *                       the rows it did not reach are claimed by the next pass. The ceiling
         *                       is five minutes, because a pass longer than that is a stalled relay
         *                       rather than a busy one
         */
        public record Relay(

                @Positive long fixedDelayMs,

                @Positive int batchSize,

                @NotBlank String instanceId,

                @NotNull @DurationUnit(ChronoUnit.SECONDS) Duration claimTimeout,

                @Positive @Max(MAX_PASS_DURATION_MS) long maxDurationMs) {

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

    /**
     * The locking and reservation settings of one decision.
     *
     * <p>ADDITIVE. {@code app/cbl/CBTRN02C.cbl} is one batch program reading one sequential feed, so
     * it needs no lock and no reservation: paragraph {@code 2700-UPDATE-ACCOUNT} at
     * {@code app/cbl/CBTRN02C.cbl:L545-L560} rewrites the account record in the same loop that
     * validates the next one, and {@code app/cbl/CBTRN02C.cbl:L403-L405} therefore reads figures that
     * already carry every earlier approval. This service answers concurrent calls and does not own the
     * account record, so it needs both.
     *
     * @param lockWaitMs     how long the locked read of one decision waits for a row another decision
     *                       holds, applied as a transaction-local {@code lock_timeout} by
     *                       {@code AccountCreditSnapshotRepository#applyLockWaitBound}
     * @param reservationTtl how long an approval's reserved exposure counts before it is treated as
     *                       never having been posted. Long enough that an ordinary posting round trip
     *                       reports back first, short enough that an approval whose event was
     *                       dead-lettered does not hold its exposure for ever and shrink available
     *                       credit until every call declines
     */
    public record Decision(
            @Positive long lockWaitMs,
            @NotNull @DurationUnit(ChronoUnit.SECONDS) Duration reservationTtl) {

        /**
         * Holds the reservation lifetime above zero.
         *
         * <p>A zero or negative lifetime would release every reservation the moment it was written,
         * which is exactly the behaviour these columns exist to replace.
         *
         * @throws IllegalArgumentException when the lifetime is not positive
         */
        public Decision {
            if (reservationTtl != null
                    && (reservationTtl.isZero() || reservationTtl.isNegative())) {
                throw new IllegalArgumentException(
                        "carddemo.decision.reservation-ttl must be positive, found "
                                + reservationTtl);
            }
        }
    }
}
