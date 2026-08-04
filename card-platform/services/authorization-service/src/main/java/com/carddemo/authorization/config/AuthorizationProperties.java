package com.carddemo.authorization.config;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;

import org.springframework.boot.context.properties.ConfigurationProperties;
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
 * non-positive relay delay or a supporting-service address with no scheme therefore stops start-up
 * with the offending property named, rather than surfacing later as a message published to the
 * empty-string topic.
 *
 * <p>{@code AuthorizationApplication} carries {@code @ConfigurationPropertiesScan}, which registers
 * this record as a bean. An injected instance is immutable, so no component can change a value the
 * constraints already accepted.
 *
 * <p>Decisions: {@code card-platform/docs/decision-log.md} (planned).
 *
 * @param kafka    the topic names this service publishes to
 * @param outbox   the relay sweep settings
 * @param services the addresses of the two supporting services this service reads
 */
@ConfigurationProperties(prefix = "carddemo")
@Validated
public record AuthorizationProperties(

        @NotNull @Valid Kafka kafka,

        @NotNull @Valid Outbox outbox,

        @NotNull @Valid Services services) {

    /**
     * The broker-facing names this service uses.
     *
     * @param topics the two topics this service publishes to
     */
    public record Kafka(@NotNull @Valid Topics topics) {

        /**
         * One name per published event. This service consumes nothing, so no consumed topic and no
         * dead-letter topic appears here.
         *
         * @param transactionAuthorized the topic an approved authorization travels on, keyed by the
         *                              account identifier
         * @param transactionDeclined   the topic a declined authorization travels on, carrying one
         *                              of the four reject reasons of
         *                              {@code app/cbl/CBTRN02C.cbl:L385-L420}
         */
        public record Topics(

                @NotBlank String transactionAuthorized,

                @NotBlank String transactionDeclined) {
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

    /**
     * The two supporting services the authorization path reads over Representational State Transfer
     * (REST). Both calls are synchronous, and neither service publishes to reach this one.
     *
     * @param account the account service, read for the credit snapshot the credit-limit rule uses
     * @param card    the card service
     */
    public record Services(

            @NotNull @Valid Endpoint account,

            @NotNull @Valid Endpoint card) {

        /**
         * One supporting service address.
         *
         * @param baseUrl the scheme, host and port, with no trailing path
         */
        public record Endpoint(

                @NotBlank
                @Pattern(regexp = "^https?://[^\\s/]+(?::\\d+)?$",
                        message = "must be an http or https address carrying a host and an "
                                + "optional port, with no trailing path")
                String baseUrl) {
        }
    }
}
