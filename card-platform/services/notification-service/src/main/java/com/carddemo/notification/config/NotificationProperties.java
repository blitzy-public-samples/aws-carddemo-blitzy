package com.carddemo.notification.config;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * The {@code carddemo} block of {@code application.yml}, bound and checked at start-up.
 *
 * <p>ADDITIVE IN FULL. The statement program {@code app/cbl/CBSTM03A.CBL} names its datasets in
 * {@code app/jcl/CREASTMT.JCL} and reads no configuration file, so no configuration record has an
 * ancestor here.
 *
 * <p>Every value the notification service takes from configuration arrives through this record. A
 * key with no component here is not configuration, and a component with no key fails start-up.
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
 * <p>Decisions: {@code card-platform/docs/decision-log.md} (planned).
 *
 * @param kafka    the consumer groups and the topic names this service reads
 * @param consumer the delivery-attempt settings a listener applies
 */
@ConfigurationProperties(prefix = "carddemo")
@Validated
public record NotificationProperties(

        @NotNull @Valid Kafka kafka,

        @NotNull @Valid Consumer consumer) {

    /**
     * The broker-facing names this service uses.
     *
     * @param groups one consumer group per listener
     * @param topics the three consumed topics and the dead-letter suffix
     */
    public record Kafka(

            @NotNull @Valid Groups groups,

            @NotNull @Valid Topics topics) {

        /**
         * One group per listener. This service registers two listeners, and neither shares a group
         * with the other.
         *
         * @param transactionPosted the group of the listener on the posted topic
         * @param fraudAssessed     the group of the listener on the assessment topic
         */
        public record Groups(

                @NotBlank String transactionPosted,

                @NotBlank String fraudAssessed) {
        }

        /**
         * The two consumed topics and the one shared dead-letter topic.
         *
         * @param transactionPosted the posted balance, taken from
         *                          {@code app/cbl/CBTRN02C.cbl:L547}
         * @param fraudAssessed     the topic both assessment outcomes travel on
         * @param deadLetter        the one topic every record no listener could consume reaches,
         *                          shared by every service rather than named per consumed topic
         */
        public record Topics(

                @NotBlank String transactionPosted,

                @NotBlank String fraudAssessed,

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
}
