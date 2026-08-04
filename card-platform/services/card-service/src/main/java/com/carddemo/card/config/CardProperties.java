package com.carddemo.card.config;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * The {@code carddemo} block of {@code application.yml}, bound and checked at start-up.
 *
 * <p>ADDITIVE IN FULL. The card programs {@code app/cbl/COCRDLIC.cbl},
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
 * <p>Decisions: {@code card-platform/docs/decision-log.md} (planned).
 *
 * @param kafka  the topic name this service publishes to
 * @param outbox the relay sweep settings
 */
@ConfigurationProperties(prefix = "carddemo")
@Validated
public record CardProperties(

        @NotNull @Valid Kafka kafka,

        @NotNull @Valid Outbox outbox) {

    /**
     * The broker-facing names this service uses.
     *
     * @param topics the one topic this service publishes to
     */
    public record Kafka(@NotNull @Valid Topics topics) {

        /**
         * The one published topic. A card list and a card read publish nothing.
         *
         * @param cardUpdated the topic a card update travels on, carrying the card after the update
         *                    with its number masked
         */
        public record Topics(@NotBlank String cardUpdated) {
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
