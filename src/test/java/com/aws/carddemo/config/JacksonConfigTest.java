/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License").
 * You may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.aws.carddemo.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.exc.MismatchedInputException;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.jackson.Jackson2ObjectMapperBuilderCustomizer;
import org.springframework.boot.autoconfigure.jackson.JacksonAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder;

/**
 * Tests for {@link JacksonConfig}.
 *
 * <p>The class is verified two complementary ways:
 * <ul>
 *   <li><b>Direct</b> — the customizer is applied to a bare {@link Jackson2ObjectMapperBuilder},
 *       isolating the feature adjustments this bean is responsible for, including the
 *       scalar-to-String coercion clamp (F-P4-K).</li>
 *   <li><b>Boot integration</b> — an {@link ApplicationContextRunner} loads the real
 *       {@link JacksonAutoConfiguration} together with {@link JacksonConfig}, proving the customizer
 *       <em>augments</em> rather than <em>replaces</em> Boot's {@code ObjectMapper} (exactly one
 *       {@code ObjectMapper} bean is present) and that the resulting mapper behaves identically.</li>
 * </ul>
 * All assertions are database- and web-free, so the test runs as a fast, deterministic unit test.
 */
class JacksonConfigTest {

    /**
     * Boot's real Jackson auto-configuration plus the bean under test; no datasource, servlet, or
     * other application beans are involved.
     */
    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(JacksonAutoConfiguration.class))
            .withUserConfiguration(JacksonConfig.class);

    /**
     * Builds an {@code ObjectMapper} by applying only this bean's customizer to a fresh builder,
     * mirroring how Spring Boot layers the customizer onto its own builder.
     */
    private static ObjectMapper mapperFromCustomizer() {
        Jackson2ObjectMapperBuilder builder = new Jackson2ObjectMapperBuilder();
        new JacksonConfig().cardDemoJacksonCustomizer().customize(builder);
        return builder.build();
    }

    // -------------------------------------------------------------------------
    // Direct customizer behavior
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("customizer enables the BigDecimal fidelity features and keeps ISO-8601 dates")
    void customizer_togglesExpectedFeatures() {
        ObjectMapper mapper = mapperFromCustomizer();

        assertThat(mapper.isEnabled(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)).isTrue();
        assertThat(mapper.isEnabled(JsonGenerator.Feature.WRITE_BIGDECIMAL_AS_PLAIN)).isTrue();
        assertThat(mapper.isEnabled(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)).isFalse();
    }

    @Test
    @DisplayName("BigDecimal serializes plain and scale-preserving, never scientific")
    void bigDecimal_serializesPlainAndScalePreserving() throws Exception {
        ObjectMapper mapper = mapperFromCustomizer();

        // Scale-2 monetary value: rendered exactly, not collapsed to 1000.0.
        assertThat(mapper.writeValueAsString(new BigDecimal("1000.00"))).isEqualTo("1000.00");

        // A value whose toString() is scientific ("1E+2"): WRITE_BIGDECIMAL_AS_PLAIN forces "100".
        assertThat(mapper.writeValueAsString(new BigDecimal("1E+2"))).isEqualTo("100");

        // As an object property too.
        Sample sample = new Sample();
        sample.amount = new BigDecimal("1000.00");
        assertThat(mapper.writeValueAsString(sample)).contains("\"amount\":1000.00");
    }

    @Test
    @DisplayName("JSON floating-point numbers deserialize as BigDecimal, never double")
    void floatingPoint_deserializesAsBigDecimal() throws Exception {
        ObjectMapper mapper = mapperFromCustomizer();

        // Loosely-typed target: the feature decides BigDecimal vs Double.
        Object loose = mapper.readValue("123.45", Object.class);
        assertThat(loose).isInstanceOf(BigDecimal.class);
        assertThat((BigDecimal) loose).isEqualByComparingTo(new BigDecimal("123.45"));

        Untyped untyped = mapper.readValue("{\"amount\":123.45}", Untyped.class);
        assertThat(untyped.amount).isInstanceOf(BigDecimal.class);
        assertThat((BigDecimal) untyped.amount).isEqualByComparingTo(new BigDecimal("123.45"));

        // A field explicitly typed as BigDecimal preserves the exact value as well.
        Sample sample = mapper.readValue("{\"amount\":123.45}", Sample.class);
        assertThat(sample.amount).isInstanceOf(BigDecimal.class);
        assertThat(sample.amount).isEqualByComparingTo(new BigDecimal("123.45"));
    }

    // -------------------------------------------------------------------------
    // F-P4-K: scalar-to-String coercion is disabled on textual targets
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("F-P4-K: a JSON integer targeting a String field is rejected, not coerced to text")
    void integerOntoStringField_isRejected() {
        ObjectMapper mapper = mapperFromCustomizer();
        // Without the coercion clamp Jackson would bind "note" to the string "123"; the clamp makes
        // this a MismatchedInputException, which Spring surfaces as HttpMessageNotReadable -> 400.
        assertThatThrownBy(() -> mapper.readValue("{\"note\":123}", Sample.class))
                .isInstanceOf(MismatchedInputException.class);
    }

    @Test
    @DisplayName("F-P4-K: a JSON floating-point number targeting a String field is rejected, not coerced to text")
    void floatOntoStringField_isRejected() {
        ObjectMapper mapper = mapperFromCustomizer();
        assertThatThrownBy(() -> mapper.readValue("{\"note\":1.5}", Sample.class))
                .isInstanceOf(MismatchedInputException.class);
    }

    @Test
    @DisplayName("F-P4-K: a JSON boolean targeting a String field is rejected, not coerced to text")
    void booleanOntoStringField_isRejected() {
        ObjectMapper mapper = mapperFromCustomizer();
        // A boolean coerced to "true"/"false" is exactly how a wrong-typed password slipped through
        // before the clamp; it must now be rejected.
        assertThatThrownBy(() -> mapper.readValue("{\"note\":true}", Sample.class))
                .isInstanceOf(MismatchedInputException.class);
    }

    @Test
    @DisplayName("F-P4-K: a genuine JSON string still binds to a String field and a JSON number still binds to a BigDecimal field")
    void textualClampLeavesLegitimateBindingIntact() throws Exception {
        ObjectMapper mapper = mapperFromCustomizer();

        // A real string still binds: the clamp only rejects non-text input shapes on text targets.
        Sample stringTarget = mapper.readValue("{\"note\":\"hello\"}", Sample.class);
        assertThat(stringTarget.note).isEqualTo("hello");

        // A JSON number still binds to a BigDecimal (numeric, LogicalType.Float) target: the textual
        // clamp does not touch numeric targets, so monetary fidelity is fully preserved.
        Sample numericTarget = mapper.readValue("{\"amount\":123.45}", Sample.class);
        assertThat(numericTarget.amount).isEqualByComparingTo(new BigDecimal("123.45"));
    }

    @Test
    @DisplayName("java.time values serialize as ISO-8601 strings and null properties are omitted")
    void dates_areIso8601_andNullsOmitted() throws Exception {
        ObjectMapper mapper = mapperFromCustomizer();

        Sample sample = new Sample();
        sample.amount = new BigDecimal("10.00");
        sample.date = LocalDate.of(2024, 1, 15);
        sample.timestamp = LocalDateTime.of(2024, 1, 15, 9, 30, 0);
        // sample.note stays null -> must be omitted under NON_NULL inclusion.

        String json = mapper.writeValueAsString(sample);

        assertThat(json).contains("\"date\":\"2024-01-15\"");
        assertThat(json).contains("\"timestamp\":\"2024-01-15T09:30:00\"");
        assertThat(json).doesNotContain("\"note\"");
    }

    // -------------------------------------------------------------------------
    // Spring Boot integration: augment, do not replace
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("Boot's ObjectMapper is augmented (single mapper bean, customizer registered)")
    void bootObjectMapper_isAugmentedNotReplaced() {
        contextRunner.run(context -> {
            // Augment-not-replace: no standalone ObjectMapper bean was contributed.
            assertThat(context).hasSingleBean(ObjectMapper.class);
            assertThat(context.getBeanNamesForType(Jackson2ObjectMapperBuilderCustomizer.class))
                    .contains("cardDemoJacksonCustomizer");

            ObjectMapper mapper = context.getBean(ObjectMapper.class);
            assertThat(mapper.isEnabled(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)).isTrue();
            assertThat(mapper.isEnabled(JsonGenerator.Feature.WRITE_BIGDECIMAL_AS_PLAIN)).isTrue();
            assertThat(mapper.isEnabled(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)).isFalse();

            assertThat(mapper.writeValueAsString(new BigDecimal("1E+2"))).isEqualTo("100");
            assertThat(mapper.readValue("123.45", Object.class)).isInstanceOf(BigDecimal.class);

            // F-P4-K: the scalar-to-String coercion clamp applied via postConfigurer survives Boot's
            // own build, so the real application ObjectMapper rejects a number/boolean on a text field.
            assertThatThrownBy(() -> mapper.readValue("{\"note\":123}", Sample.class))
                    .isInstanceOf(MismatchedInputException.class);
            assertThatThrownBy(() -> mapper.readValue("{\"note\":true}", Sample.class))
                    .isInstanceOf(MismatchedInputException.class);
        });
    }

    // -------------------------------------------------------------------------
    // Serialization fixtures
    // -------------------------------------------------------------------------

    /** Simple bean with a monetary field, {@code java.time} fields, and a nullable field. */
    static class Sample {
        public BigDecimal amount;
        public LocalDate date;
        public LocalDateTime timestamp;
        public String note;
    }

    /** Bean whose numeric property is loosely typed, so the parser chooses the numeric type. */
    static class Untyped {
        public Object amount;
    }
}
