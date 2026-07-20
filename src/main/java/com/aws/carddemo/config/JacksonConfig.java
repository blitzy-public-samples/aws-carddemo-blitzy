/*
 * Copyright Amazon.com, Inc. or its affiliates.
 * All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License").
 * You may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND,
 * either express or implied. See the License for the specific
 * language governing permissions and limitations under the License.
 */
package com.aws.carddemo.config;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.cfg.CoercionAction;
import com.fasterxml.jackson.databind.cfg.CoercionInputShape;
import com.fasterxml.jackson.databind.type.LogicalType;
import org.springframework.boot.autoconfigure.jackson.Jackson2ObjectMapperBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * JSON (de)serialization hardening for the CardDemo REST boundary.
 *
 * <p>This configuration <strong>augments</strong> — it never replaces — the {@code ObjectMapper}
 * that Spring Boot auto-configures for the application. It contributes a single
 * {@link Jackson2ObjectMapperBuilderCustomizer} bean, so Boot's sensible defaults and, crucially,
 * the well-known modules it auto-registers on the classpath (most importantly the
 * {@code jackson-datatype-jsr310} {@code JavaTimeModule}, pulled in transitively by
 * {@code spring-boot-starter-web} / {@code -json}) all remain in effect. The customizer runs on
 * top of Boot's builder, which is why the {@code spring.jackson.*} settings declared in
 * {@code application.yml} are preserved rather than discarded.
 *
 * <h2>Why this exists: monetary fidelity (the migration's #1 correctness risk)</h2>
 * <p>Every monetary value in the legacy AWS CardDemo mainframe application is a COBOL
 * {@code PIC S9(n)V99 COMP-3} packed decimal. The migration contract (AAP &sect;0.2.2, &sect;0.6.4,
 * &sect;0.8.1, &sect;0.9.1) requires these to be represented as {@link java.math.BigDecimal} at a
 * fixed scale of {@code 2} with <em>no</em> floating-point coercion anywhere; any drift compounds
 * across financial postings (AAP &sect;0.7, hotspot H3). JSON (de)serialization is the one place
 * where Jackson could silently coerce a decimal to {@code double} / {@code float} and destroy that
 * fidelity, so this class closes that gap on both the inbound and outbound edges:
 * <ul>
 *   <li><b>Input (deserialization):</b> {@link DeserializationFeature#USE_BIG_DECIMAL_FOR_FLOATS}
 *       forces incoming JSON floating-point numbers that land on loosely-typed targets
 *       (for example {@code Object}, {@code Number}, or {@code Map} values) to be parsed as
 *       {@code BigDecimal} rather than {@code double}, so no precision is lost before the value
 *       ever reaches the domain layer.</li>
 *   <li><b>Output (serialization):</b> {@link JsonGenerator.Feature#WRITE_BIGDECIMAL_AS_PLAIN}
 *       emits {@code BigDecimal} values in plain notation (via {@code toPlainString()}), so a
 *       value is never rendered in scientific form (for example {@code 1E+3}); the exact,
 *       scale-preserving representation (for example {@code 1000.00}) is what crosses the wire.</li>
 * </ul>
 *
 * <h2>Coordination with {@code application.yml} (single source of truth, no conflict)</h2>
 * <p>{@code application.yml} already declares:
 * <pre>{@code
 * spring:
 *   jackson:
 *     default-property-inclusion: non_null
 *     serialization:
 *       write-dates-as-timestamps: false
 * }</pre>
 * The two properties below intentionally <em>mirror</em> that YAML so the intent is self-documenting
 * at the code boundary and holds regardless of customizer ordering; they are idempotent reinforcements,
 * not competing overrides:
 * <ul>
 *   <li>{@link SerializationFeature#WRITE_DATES_AS_TIMESTAMPS} is disabled so {@code java.time} types
 *       (rendered by the auto-registered {@code JavaTimeModule}) serialize as ISO-8601 strings rather
 *       than numeric epoch timestamps.</li>
 *   <li>{@link JsonInclude.Include#NON_NULL} omits {@code null} properties from responses.</li>
 * </ul>
 * The {@code BigDecimal} features above are the unique value-add of this class: they cannot be
 * expressed cleanly through {@code spring.jackson.*} YAML keys, which is precisely why they live here.
 *
 * <h2>Design constraints honored</h2>
 * <ul>
 *   <li>No standalone {@code @Bean ObjectMapper} is defined — replacing the mapper would drop Boot's
 *       auto-registered {@code JavaTimeModule} and the {@code application.yml} settings, reintroducing
 *       timestamp-style dates and potential {@code double} coercion.</li>
 *   <li>{@code builder.modules(...)} is never called — that would <em>replace</em> the module set and
 *       likewise discard the auto-registered jsr310 module; module registration is left to Boot.</li>
 *   <li>The bean carries no state and needs no collaborators, so there are no fields and no injection
 *       (constructor injection would be used if any collaborator were ever required).</li>
 * </ul>
 */
@Configuration
public class JacksonConfig {

    /**
     * Contributes the CardDemo-specific Jackson tuning to Spring Boot's auto-configured
     * {@code ObjectMapper} without replacing it.
     *
     * <p>Boot collects every {@link Jackson2ObjectMapperBuilderCustomizer} bean and applies it to the
     * shared {@code Jackson2ObjectMapperBuilder}; returning a lambda here therefore layers the
     * following four adjustments on top of Boot's defaults and the {@code application.yml} settings:
     * <ol>
     *   <li>enable {@link DeserializationFeature#USE_BIG_DECIMAL_FOR_FLOATS} — parse JSON
     *       floating-point numbers as {@code BigDecimal} (the primary monetary-input safeguard);</li>
     *   <li>enable {@link JsonGenerator.Feature#WRITE_BIGDECIMAL_AS_PLAIN} — serialize
     *       {@code BigDecimal} in plain, non-scientific notation, preserving scale;</li>
     *   <li>disable {@link SerializationFeature#WRITE_DATES_AS_TIMESTAMPS} — emit ISO-8601
     *       date/time strings (mirrors {@code application.yml});</li>
     *   <li>set the serialization inclusion to {@link JsonInclude.Include#NON_NULL} — omit
     *       {@code null} properties (mirrors {@code application.yml}).</li>
     * </ol>
     *
     * @return a customizer that hardens {@code BigDecimal} fidelity and reinforces the ISO-8601 /
     *         non-null response contract on the Boot-configured {@code ObjectMapper}
     */
    @Bean
    Jackson2ObjectMapperBuilderCustomizer cardDemoJacksonCustomizer() {
        return builder -> builder
                .featuresToEnable(
                        DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS,
                        JsonGenerator.Feature.WRITE_BIGDECIMAL_AS_PLAIN)
                .featuresToDisable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
                .serializationInclusion(JsonInclude.Include.NON_NULL)
                // Post-configure the fully-built ObjectMapper to tighten input-shape coercion; this
                // is applied via postConfigurer because per-type coercion is an ObjectMapper-level
                // concern that the builder's feature toggles do not express.
                .postConfigurer(JacksonConfig::disableScalarToStringCoercion);
    }

    /**
     * Rejects JSON scalar shapes that would otherwise be silently coerced onto a textual
     * ({@link String}) target, closing the loosely-typed input gap identified in QA finding
     * <strong>F-P4-K</strong>.
     *
     * <p>By default Jackson coerces a JSON number or boolean into a {@code String}-typed field
     * (for example {@code {"password": 12345678}} or {@code {"userId": true}} would deserialize
     * into the string {@code "12345678"} / {@code "true"}), which allowed wrong-typed &mdash; and,
     * for a numeric password, still usable &mdash; values to slip past the DTO field contract. The
     * legacy 3270 screens are exclusively text fields (BMS {@code PIC X(n)}), so a JSON scalar is
     * never a valid representation of a screen field; the correct outcome is a deterministic
     * rejection, not a coercion.</p>
     *
     * <p>Setting {@link CoercionAction#Fail} for the {@link CoercionInputShape#Integer},
     * {@link CoercionInputShape#Float}, and {@link CoercionInputShape#Boolean} input shapes on the
     * {@link LogicalType#Textual} target makes such input raise a Jackson
     * {@code MismatchedInputException}; Spring surfaces that as an
     * {@code HttpMessageNotReadableException}, which the {@code GlobalExceptionHandler} maps to a
     * consistent HTTP {@code 400}. The constraint is scoped to textual targets only, so it does not
     * touch {@link java.math.BigDecimal} (numeric) monetary fields &mdash; preserving the
     * migration's {@code #1} correctness guarantee &mdash; nor enum targets such as the
     * attention-key ({@code PfKeyAction}) field.</p>
     *
     * @param mapper the fully-built {@code ObjectMapper} to harden (never {@code null})
     */
    private static void disableScalarToStringCoercion(ObjectMapper mapper) {
        var textual = mapper.coercionConfigFor(LogicalType.Textual);
        textual.setCoercion(CoercionInputShape.Integer, CoercionAction.Fail);
        textual.setCoercion(CoercionInputShape.Float, CoercionAction.Fail);
        textual.setCoercion(CoercionInputShape.Boolean, CoercionAction.Fail);
    }
}
