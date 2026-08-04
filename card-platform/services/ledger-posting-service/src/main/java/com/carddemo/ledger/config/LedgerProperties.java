package com.carddemo.ledger.config;

import jakarta.validation.Valid;
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
 * <p>ADDITIVE IN FULL. No COBOL program and no copybook in this repository defines this type. The
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
 * <p>Decisions: {@code card-platform/docs/decision-log.md} (planned).
 *
 * @param kafka    the topic names this service consumes and publishes
 * @param consumer the delivery-attempt settings a listener applies
 * @param outbox   the relay sweep settings
 */
@ConfigurationProperties(prefix = "carddemo")
@Validated
public record LedgerProperties(

        @NotNull @Valid Kafka kafka,

        @NotNull @Valid Consumer consumer,

        @NotNull @Valid Outbox outbox) {

    /**
     * The broker-facing names this service uses.
     *
     * @param topics the four topics this service reads or writes
     */
    public record Kafka(@NotNull @Valid Topics topics) {

        /**
         * One name per event this service reads or writes.
         *
         * @param transactionAuthorized the consumed topic, replacing the sequential daily feed
         *                              {@code app/jcl/POSTTRAN.jcl} allocates
         * @param transactionPosted     the topic a posted balance travels on, taken after the add at
         *                              {@code app/cbl/CBTRN02C.cbl:L547}
         * @param transactionDeclined   the topic a reject travels on, carrying the reason and the
         *                              text of {@code app/cbl/CBTRN02C.cbl:L446-L465}
         * @param deadLetter            the topic a record reaches once its delivery attempts run out
         */
        public record Topics(

                @NotBlank String transactionAuthorized,

                @NotBlank String transactionPosted,

                @NotBlank String transactionDeclined,

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
}
