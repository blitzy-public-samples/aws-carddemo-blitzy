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

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.DisplayNameGenerator;
import org.junit.jupiter.api.Test;
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder;

/**
 * Pure unit tests for {@link JacksonConfig}, the configuration that guarantees COBOL-faithful
 * {@link java.math.BigDecimal} decimal fidelity for every JSON payload the migrated CardDemo
 * application produces or consumes.
 *
 * <p>Behavioral source spec: the legacy account money fields in {@code legacy/app/cpy/CVACT01Y.cpy}
 * — {@code ACCT-CURR-BAL}, {@code ACCT-CREDIT-LIMIT}, {@code ACCT-CASH-CREDIT-LIMIT}, {@code
 * ACCT-CURR-CYC-CREDIT}, and {@code ACCT-CURR-CYC-DEBIT} — are all declared {@code PIC S9(10)V99}:
 * a signed fixed-point value with an implied two-digit scale. To preserve cent-exact fidelity
 * across the JSON boundary (Agent Action Plan §0.6.1), such values must never be emitted in
 * scientific notation, and every inbound JSON floating-point token must be parsed into a {@code
 * BigDecimal} rather than a lossy {@code double}.
 *
 * <p>This test deliberately avoids {@code @SpringBootTest} and Testcontainers. It builds an {@link
 * ObjectMapper} directly from the production {@link
 * org.springframework.boot.autoconfigure.jackson.Jackson2ObjectMapperBuilderCustomizer} contributed
 * by {@link JacksonConfig#bigDecimalFidelityCustomizer()} — exactly the mechanism Spring Boot uses
 * at runtime to apply customizers to its primary mapper — so the assertions verify the real
 * production behaviour without a Spring context.
 */
@DisplayNameGeneration(DisplayNameGenerator.ReplaceUnderscores.class)
class JacksonConfigTest {

  private ObjectMapper objectMapper;

  /**
   * Builds the {@link ObjectMapper} under test once per test method by applying the production
   * customizer to a fresh {@link Jackson2ObjectMapperBuilder}. This mirrors precisely how Spring
   * Boot applies a {@code Jackson2ObjectMapperBuilderCustomizer} to its auto-configured mapper.
   */
  @BeforeEach
  void setUp() {
    Jackson2ObjectMapperBuilder builder = new Jackson2ObjectMapperBuilder();
    new JacksonConfig().bigDecimalFidelityCustomizer().customize(builder);
    this.objectMapper = builder.build();
  }

  /**
   * A representative money value such as {@code 1234567.89} must serialize in plain
   * (non-scientific) notation. This case documents the decimal-fidelity intent required verbatim by
   * the folder requirement: it is plain regardless of the feature flag, so it pins the expected
   * contract.
   */
  @Test
  void serializes_a_typical_money_value_in_plain_notation() throws Exception {
    String json = objectMapper.writeValueAsString(new BigDecimal("1234567.89"));
    assertThat(json).isEqualTo("1234567.89");
    assertThat(json).doesNotContainIgnoringCase("e");
  }

  /**
   * Feature-discriminating proof that {@code WRITE_BIGDECIMAL_AS_PLAIN} is enabled: {@code new
   * BigDecimal("1E+2")} has unscaled value {@code 1} and scale {@code -2}, so its {@code
   * toString()} is the scientific {@code "1E+2"} while its {@code toPlainString()} is {@code
   * "100"}. Jackson emits the plain form only when the serialization feature is on; asserting
   * {@code "100"} (with no {@code e}/{@code E}) therefore definitively proves the feature is
   * active.
   */
  @Test
  void serializes_a_scientific_scaled_value_as_a_plain_string() throws Exception {
    String json = objectMapper.writeValueAsString(new BigDecimal("1E+2"));
    assertThat(json).isEqualTo("100");
    assertThat(json).doesNotContainIgnoringCase("e");
  }

  /**
   * Feature-discriminating proof that {@code USE_BIG_DECIMAL_FOR_FLOATS} is enabled: an untyped
   * ({@code Object.class}) read of a JSON floating-point token yields a {@code BigDecimal} when the
   * deserialization feature is on, but a precision-losing {@code Double} when it is off. The value
   * is compared with {@code isEqualByComparingTo} so the assertion is robust to scale.
   */
  @Test
  void deserializes_floating_point_tokens_as_bigdecimal_never_double() throws Exception {
    Object value = objectMapper.readValue("0.05", Object.class);
    assertThat(value)
        .as("USE_BIG_DECIMAL_FOR_FLOATS must keep cent fidelity; default would yield Double")
        .isInstanceOf(BigDecimal.class);
    assertThat((BigDecimal) value).isEqualByComparingTo(new BigDecimal("0.05"));
  }
}
