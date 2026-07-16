/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License").
 * You may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.aws.carddemo.account;

import com.fasterxml.jackson.core.StreamReadConstraints;
import com.fasterxml.jackson.databind.cfg.CoercionAction;
import com.fasterxml.jackson.databind.cfg.CoercionInputShape;
import com.fasterxml.jackson.databind.type.LogicalType;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.jackson.Jackson2ObjectMapperBuilderCustomizer;
import org.springframework.context.annotation.Bean;

/**
 * Entry point for the CardDemo Account Management microservice (Feature F-003).
 *
 * <p>This service migrates the legacy COBOL/CICS/VSAM account view
 * ({@code CAVW} / {@code COACTVWC}) and account update ({@code CAUP} /
 * {@code COACTUPC}) transactions to a stateless Java&nbsp;17 / Spring&nbsp;Boot
 * REST service backed by PostgreSQL. It exposes:</p>
 * <ul>
 *   <li>{@code GET /api/v1/accounts/{accountId}} &mdash; account inquiry</li>
 *   <li>{@code PUT /api/v1/accounts/{accountId}} &mdash; account update</li>
 * </ul>
 *
 * <p>Annotated with {@link SpringBootApplication}, this class is the
 * <strong>component-scan root</strong> for the {@code com.aws.carddemo.account}
 * package and all of its sub-packages ({@code controller}, {@code service},
 * {@code repository}, {@code domain}, {@code dto}, {@code mapper},
 * {@code exception} and {@code config}). Because it resides directly in the
 * base package, Spring Boot's default component scan, JPA repository scan and
 * entity scan automatically discover every layer &mdash; no explicit
 * {@code scanBasePackages}, {@code @EnableJpaRepositories} or {@code @EntityScan}
 * is required (adding any of those would risk narrowing the scan).</p>
 *
 * <p>Beyond bootstrapping, this class declares a single infrastructure bean &mdash; a
 * {@link Jackson2ObjectMapperBuilderCustomizer} (see {@link #jsonHardeningCustomizer()}) that hardens
 * JSON <em>input</em> parsing: strict numeric coercion (so a fractional or quoted {@code version}
 * token and a quoted monetary token are rejected rather than silently reshaped) plus conservative
 * {@link StreamReadConstraints stream-read limits} (bounded document length, string length, nesting
 * depth, and number length) that refuse an oversized or deeply nested payload during parsing
 * (SEC-INPUT-1). All other concerns &mdash; datasource, JPA, Flyway, Actuator, logging, and the
 * {@code write-bigdecimal-as-plain} JSON <em>output</em> setting &mdash; are configured declaratively
 * in {@code application.yml}, and OpenAPI metadata is defined in {@code config.OpenApiConfig}; no
 * other beans, runners or business logic are declared here.</p>
 */
@SpringBootApplication
public class AccountServiceApplication {

    /**
     * Boots the Spring application context and starts the embedded web server.
     *
     * @param args command-line arguments forwarded to Spring Boot
     */
    public static void main(String[] args) {
        SpringApplication.run(AccountServiceApplication.class, args);
    }

    // ------------------------------------------------------------------
    // JSON input-hardening limits (SEC-INPUT-1)
    // ------------------------------------------------------------------

    /** Maximum total JSON document length the parser will accept (64&nbsp;KiB): the primary DoS guard. */
    private static final long MAX_JSON_DOCUMENT_LENGTH = 64L * 1024L; // 65536

    /** Maximum length of any single JSON string token (far above any legitimate account field). */
    private static final int MAX_JSON_STRING_LENGTH = 20_000;

    /** Maximum JSON nesting depth (the account contract is flat; deep nesting is an attack signal). */
    private static final int MAX_JSON_NESTING_DEPTH = 32;

    /**
     * Maximum JSON numeric token length. Kept deliberately high so that an out-of-range monetary
     * value (for example {@code 10000000000.00}) still <em>parses</em> and is then rejected by the
     * {@code AccountValidator} with a business {@code 400} + {@code fieldErrors}, preserving the
     * legacy edit-order semantics &mdash; rather than being refused at parse time as a malformed body.
     */
    private static final int MAX_JSON_NUMBER_LENGTH = 1_000;

    /**
     * Builds the Jackson input-hardening customizer applied to Boot's auto-configured
     * {@code ObjectMapper}. Exposed as a {@code public static} factory so the web-slice tests can
     * apply the <em>identical</em> policy without duplicating it (a single source of truth).
     *
     * <p>Two concerns are combined, both mapping a rejected payload to a sanitized {@code 400}
     * (via {@code GlobalExceptionHandler}'s {@code HttpMessageNotReadableException} handler):</p>
     * <ul>
     *   <li><strong>Strict numeric coercion</strong> &mdash; the {@code Long version} token rejects a
     *       fractional ({@code 0.5}) or quoted-string ({@code "0"}) input, and the {@code BigDecimal}
     *       money tokens reject a quoted-string ({@code "194.00"}) input, so a mistyped token cannot
     *       be silently reshaped and corrupt the optimistic-lock comparison or a monetary value.</li>
     *   <li><strong>Stream read constraints (SEC-INPUT-1)</strong> &mdash; bounded document length,
     *       string length, nesting depth, and number length on the shared {@code JsonFactory}, so an
     *       oversized or deeply nested payload is refused <em>during</em> parsing (a
     *       {@code StreamConstraintsException}) instead of being fully buffered and parsed. This is the
     *       primary guard against JSON resource-exhaustion.</li>
     * </ul>
     *
     * <p>The customizer runs as a {@code postConfigurer}, so it augments &mdash; rather than replaces
     * &mdash; Boot's auto-configured {@code ObjectMapper}, preserving the {@code write-bigdecimal-as-plain}
     * output setting from {@code application.yml} and the auto-registered JSR-310 date handling.</p>
     *
     * @return a customizer that hardens JSON input parsing
     */
    public static Jackson2ObjectMapperBuilderCustomizer jsonHardeningCustomizer() {
        return builder -> builder.postConfigurer(mapper -> {
            // Strict numeric coercion (consolidated here from the former standalone Jackson
            // configuration): reject fractional/quoted `version` tokens and quoted money tokens
            // rather than silently coercing them.
            mapper.coercionConfigFor(LogicalType.Integer)
                    .setCoercion(CoercionInputShape.Float, CoercionAction.Fail)
                    .setCoercion(CoercionInputShape.String, CoercionAction.Fail);
            mapper.coercionConfigFor(LogicalType.Float)
                    .setCoercion(CoercionInputShape.String, CoercionAction.Fail);
            // Conservative stream-read constraints (SEC-INPUT-1) on the shared parser factory.
            mapper.getFactory().setStreamReadConstraints(StreamReadConstraints.builder()
                    .maxDocumentLength(MAX_JSON_DOCUMENT_LENGTH)
                    .maxStringLength(MAX_JSON_STRING_LENGTH)
                    .maxNestingDepth(MAX_JSON_NESTING_DEPTH)
                    .maxNumberLength(MAX_JSON_NUMBER_LENGTH)
                    .build());
        });
    }

    /**
     * Registers {@link #jsonHardeningCustomizer()} as a Spring bean so Boot's
     * {@code JacksonAutoConfiguration} applies the input-hardening policy to the application
     * {@code ObjectMapper}.
     *
     * @return the JSON input-hardening customizer bean
     */
    @Bean
    Jackson2ObjectMapperBuilderCustomizer accountJsonHardeningCustomizer() {
        return jsonHardeningCustomizer();
    }
}
