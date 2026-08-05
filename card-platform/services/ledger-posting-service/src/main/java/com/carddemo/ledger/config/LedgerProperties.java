package com.carddemo.ledger.config;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import java.time.Duration;
import java.time.temporal.ChronoUnit;

import java.time.Duration;

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
         * @param transactionPosted     the topic a posted balance travels on, taken after the add at
         *                              {@code app/cbl/CBTRN02C.cbl:L547}
         * @param transactionDeclined   the topic a reject travels on, carrying the reason and the
         *                              text of {@code app/cbl/CBTRN02C.cbl:L446-L465}
         * @param deadLetter            fallback topic when a refused record has no source topic
         * @param deadLetterSuffix      suffix appended to each source topic for dead-letter routing
         */
        public record Topics(

                @NotBlank String transactionAuthorized,

                @NotBlank String transactionPosted,

                @NotBlank String transactionDeclined,

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

    /** @param sweepIntervalMs milliseconds between retention sweeps */
    public record Retention(@Positive long sweepIntervalMs) {
    }
}
