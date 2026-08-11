package com.carddemo.ledger.config;

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
 * <p>No COBOL program and no copybook in this repository defines this type. The
 * posting job {@code app/jcl/POSTTRAN.jcl} names its datasets in Job Control Language and reads no
 * configuration file, so no configuration record has an ancestor here.
 *
 * <p>Every value the ledger posting service takes from configuration arrives through this record.
 * A key with no component here is not configuration, and a component with no key fails start-up.
 *
 * <p>{@link Validated} runs the constraints below while the context builds. A blank topic name, a
 * retry count under one or a non-positive relay delay therefore stops start-up with the offending
 * property named.
 *
 * <p>{@code LedgerApplication} carries {@code @ConfigurationPropertiesScan}, which registers this
 * record as a bean. An injected instance is immutable.
 *
 * <p>Decisions: {@code card-platform/docs/decision-log.md}.
 *
 * @param kafka          the topic names this service consumes and publishes
 * @param consumer       the delivery-attempt settings a listener applies
 * @param outbox         the relay and published-row retention settings
 * @param processedEvent the processed-marker retention setting
 * @param retention      the cleanup schedule
 */
@ConfigurationProperties(prefix = "carddemo")
@Validated
public record LedgerProperties(

        @NotNull @Valid Kafka kafka,

        @NotNull @Valid Consumer consumer,

        @NotNull @Valid Outbox outbox,

        @NotNull @Valid ProcessedEvent processedEvent,

        @NotNull @Valid Retention retention) {

    /**
     * The broker-facing names this service uses.
     *
     * @param topics the topics this service reads or writes and the dead-letter suffix
     */
    public record Kafka(@NotNull @Valid Topics topics) {

        /**
         * One name per event this service reads or writes, plus the dead-letter suffix.
         *
         * @param transactionAuthorized the consumed topic, replacing the sequential daily feed
         *                              {@code app/jcl/POSTTRAN.jcl} allocates
         * @param transactionDeclined   the consumed topic a reject arrives on, carrying the reason
         *                              and the text of {@code app/cbl/CBTRN02C.cbl:L446-L465} and
         *                              the nine descriptive values the 350-byte reject area holds.
         *                              This service reads it and never writes it: the authorization
         *                              service is the sole writer of the decision under AAP 0.1.1
         * @param accountStateChanged   the consumed topic that keeps
         *                              {@code account_balance_projection} current, so an account
         *                              opened after deployment gets its first row and the cycle
         *                              close at {@code app/cbl/CBACT04C.cbl:L353-L354} reaches the
         *                              two accumulators here
         * @param transactionPosted     the topic a posted balance travels on, taken after the add at
         *                              {@code app/cbl/CBTRN02C.cbl:L547}
         * @param deadLetter            fallback topic when a refused record has no source topic
         * @param deadLetterSuffix      suffix appended to each source topic for dead-letter routing
         */
        public record Topics(

                @NotBlank String transactionAuthorized,

                @NotBlank String transactionDeclined,

                @NotBlank String accountStateChanged,

                @NotBlank String transactionPosted,

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
     * Ceiling on {@link Outbox.Relay#maxDurationMs()}, five minutes in milliseconds.
     *
     * <p>The bound exists so a misconfiguration cannot turn the pass deadline off. A pass that spends
     * five minutes waiting for broker acknowledgements is a stalled relay, and it holds both the
     * scheduled thread and the database connection its transaction owns for that whole time.
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
         * @param fixedDelayMs milliseconds between the end of one sweep and the start of the next
         * @param batchSize    due rows one sweep claims
         * @param instanceId   value written into {@code outbox_event.claimed_by}
         * @param claimTimeout  how long a claim may stand before another sweep recovers the row
         * @param maxDurationMs wall-clock ceiling on one whole sweep, measured from a monotonic
         *                      clock. It must stay above {@code max.block.ms} plus
         *                      {@code delivery.timeout.ms}, so one send resolves inside the pass
         *                      that issued it rather than landing after the relay gave up and
         *                      retried the same event
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
     * <p>{@link #MINIMUM_RETENTION_MARGIN} is enforced here rather than documented,
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
     * How often the retention sweep runs, and how long a reject row is kept.
     *
     * <p>The reject horizon was declared before anything applied it. {@code COMMENT ON TABLE
     * rejected_transaction} in {@code src/main/resources/db/migration/V1__schema.sql} names ninety
     * days from {@code rejected_at}, and {@code domain/RetentionSweep} deleted published outbox
     * rows and duplicate markers only, so the number described an intention rather than the table.
     * A security review found the gap. This component is what the sweep reads, so the declaration
     * and the behaviour come from one place.
     *
     * <p>A bounded reject set is faithful rather than additive. The source writes each reject to a
     * generation of a Generation Data Group, and {@code app/jcl/DALYREJS.jcl:L24-L28} defines that
     * base with {@code LIMIT(5)} and {@code SCRATCH}, so a sixth write deletes the oldest
     * generation. Days rather than generations, because a row is not a nightly file.
     *
     * @param sweepIntervalMs                   milliseconds between retention sweeps
     * @param rejectedTransactionRetentionDays  days a {@code rejected_transaction} row is kept,
     *                                          measured from {@code rejected_at}
     */
    public record Retention(@Positive long sweepIntervalMs,
            @Positive int rejectedTransactionRetentionDays) {
    }
}
