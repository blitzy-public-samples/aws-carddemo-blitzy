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

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
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
 *       isolating the four feature adjustments this bean is responsible for.</li>
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
