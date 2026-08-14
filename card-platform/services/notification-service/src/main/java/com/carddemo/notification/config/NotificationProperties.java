package com.carddemo.notification.config;

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
 * <p>The statement program {@code app/cbl/CBSTM03A.CBL} names its datasets in
 * {@code app/jcl/CREASTMT.JCL} and reads no configuration file, so no configuration record has an
 * ancestor here.
 *
 * <p>Every value the notification service takes from configuration arrives through this record. A
 * key with no component here is not configuration, and a component with no key fails start-up. That
 * rule is why {@link History} exists: {@code carddemo.history} was declared in
 * {@code application.yml} and bound by nothing, so its retention windows named a horizon no delete
 * could read.
 *
 * <p>{@link Validated} runs the constraints below while the context builds. A blank consumer group,
 * a blank topic name or a retry count under one therefore stops start-up with the offending property
 * named. Two listeners sharing one group would split the messages between them, so each group is a
 * separate component and each carries its own name.
 *
 * <p>{@code NotificationApplication} carries {@code @ConfigurationPropertiesScan}, which registers
 * this record as a bean. An injected instance is immutable. This module runs no outbox relay, so it
 * carries no relay setting.
 *
 * <p>Decisions: {@code card-platform/docs/decision-log.md}.
 *
 * @param kafka         the consumer groups and the topic names this service reads
 * @param consumer      the delivery-attempt settings a listener applies
 * @param history       the read-model horizons the retention sweep applies
 */
@ConfigurationProperties(prefix = "carddemo")
@Validated
public record NotificationProperties(

        @NotNull @Valid Kafka kafka,

        @NotNull @Valid Consumer consumer,

        @NotNull @Valid History history) {

    /**
     * The broker-facing names this service uses.
     *
     * @param groups one consumer group per listener
     * @param topics the three consumed topics, the fallback dead-letter topic and its suffix
     */
    public record Kafka(

            @NotNull @Valid Groups groups,

            @NotNull @Valid Topics topics) {

        /**
         * One group per listener, and no group shared. This service registers listeners on the
         * authorized, posted, assessment, and customer-context topics.
         *
         * <p>The group on the authorized topic is this service's own. The ledger and the fraud
         * detector read that same topic under groups of their own, and a group is what Kafka tracks
         * offsets against, so all three receive every record and none of them consumes on behalf of
         * another.
         *
         * @param transactionAuthorized  the group of the listener on the authorized topic
         * @param transactionPosted the group of the listener on the posted topic
         * @param fraudAssessed     the group of the listener on the assessment topic
         * @param customerContextChanged the group of the listener on the customer-context topic
         */
        public record Groups(

                @NotBlank String transactionAuthorized,

                @NotBlank String transactionPosted,

                @NotBlank String fraudAssessed,

                @NotBlank String customerContextChanged) {
        }

        /**
         * The four consumed topics, the fallback dead-letter topic and the per-topic suffix.
         *
         * @param transactionAuthorized  the authorization decision, whose rules are the batch
         *                               validation paragraphs at
         *                               {@code app/cbl/CBTRN02C.cbl:L380-L420}. Reading it directly
         *                               makes this the third independent consumer of that event,
         *                               which AAP 0.1.1 and 0.8.3 require
         * @param transactionPosted      the posted balance, taken from
         *                               {@code app/cbl/CBTRN02C.cbl:L547}
         * @param fraudAssessed          the topic both assessment outcomes travel on
         * @param customerContextChanged the topic the ten cardholder fields of
         *                               {@code app/cpy/CVCUS01Y.cpy:L6-L22} travel on
         * @param deadLetter             the fallback topic a record reaches when its source topic
         *                               is not known, shared by every service
         * @param deadLetterSuffix       appended to the source topic name to address that topic's
         *                               own dead-letter topic, so a poison record is traceable to
         *                               the topic it arrived on
         */
        public record Topics(

                @NotBlank String transactionAuthorized,

                @NotBlank String transactionPosted,

                @NotBlank String fraudAssessed,

                @NotBlank String customerContextChanged,

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
     * The read-model horizons the retention sweep applies.
     *
     * <p>Both tables grow by one row per consumed event, so a sweep is what bounds them. A statement
     * row backs the history endpoint and therefore outlives an alert by a wide margin.
     *
     * <p>No page size is configured. {@code GET /notifications/&#123;cardNumber&#125;} returns every
     * row of one card, because {@code app/cbl/CBSTM03A.CBL:L429} totals every row of one card between
     * two key breaks.
     *
     * @param statementRetentionDays how long a read-model row is kept after its processing timestamp
     * @param logRetentionDays       how long an alert attempt row is kept after it was attempted
     * @param sweepIntervalMs        milliseconds between the end of one retention sweep and the next
     */
    public record History(

            @Positive int statementRetentionDays,

            @Positive int logRetentionDays,

            @Positive long sweepIntervalMs) {
    }
}
