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

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.SerializationFeature;
import org.springframework.boot.autoconfigure.jackson.Jackson2ObjectMapperBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Jackson configuration that guarantees COBOL-faithful decimal fidelity for every JSON payload the
 * migrated CardDemo application produces or consumes.
 *
 * <p>In the legacy z/OS system, all monetary and rate fields are fixed-point packed/zoned decimals.
 * For example, the account balance and limit fields defined in {@code legacy/app/cpy/CVACT01Y.cpy}
 * — {@code ACCT-CURR-BAL}, {@code ACCT-CREDIT-LIMIT}, {@code ACCT-CASH-CREDIT-LIMIT}, {@code
 * ACCT-CURR-CYC-CREDIT}, and {@code ACCT-CURR-CYC-DEBIT} — are declared {@code PIC S9(10)V99}: a
 * signed value with an implied two-digit fractional scale. The migration maps every such field to
 * {@link java.math.BigDecimal} with an explicit scale; floating-point types ({@code float} / {@code
 * double}) are strictly prohibited for decimal data (Agent Action Plan §0.6.1, §0.7.1).
 *
 * <p>Jackson's out-of-the-box behaviour is hostile to that requirement in two ways:
 *
 * <ul>
 *   <li>When <em>serializing</em>, a {@link java.math.BigDecimal} may be emitted in scientific
 *       notation (for example {@code 1.0E+2} instead of {@code 100.00}), discarding the visible
 *       scale that mirrors the COBOL picture clause.
 *   <li>When <em>deserializing</em>, a JSON floating-point literal is parsed into a {@code double}
 *       by default, silently losing precision before it can ever reach a {@code BigDecimal} field.
 * </ul>
 *
 * <p>Both hazards are eliminated here for the single, application-wide {@code ObjectMapper}. This
 * configuration deliberately contributes a {@link Jackson2ObjectMapperBuilderCustomizer} rather
 * than declaring a replacement {@code ObjectMapper} bean, so it <strong>augments</strong> Spring
 * Boot's auto-configured mapper — preserving all of Boot's other sensible defaults (such as {@code
 * WRITE_DATES_AS_TIMESTAMPS=false}) — instead of overriding them.
 *
 * @see SerializationFeature#WRITE_BIGDECIMAL_AS_PLAIN
 * @see DeserializationFeature#USE_BIG_DECIMAL_FOR_FLOATS
 */
@Configuration
public class JacksonConfig {

  /**
   * Contributes a {@link Jackson2ObjectMapperBuilderCustomizer} that locks in COBOL-faithful {@link
   * java.math.BigDecimal} handling on Spring Boot's primary {@code ObjectMapper}.
   *
   * <p>Two Jackson features are enabled:
   *
   * <ul>
   *   <li>{@link SerializationFeature#WRITE_BIGDECIMAL_AS_PLAIN} — renders {@code BigDecimal}
   *       values in plain (non-scientific) notation, preserving the exact scale carried over from
   *       the originating COBOL {@code PIC S9(n)V99} field (for example {@code 100.00} rather than
   *       {@code 100.0} or {@code 1.0E+2}).
   *   <li>{@link DeserializationFeature#USE_BIG_DECIMAL_FOR_FLOATS} — parses every JSON
   *       floating-point number into a {@code BigDecimal} instead of a {@code double}, so no
   *       precision is lost on the inbound path.
   * </ul>
   *
   * <p>Because Boot applies every {@code Jackson2ObjectMapperBuilderCustomizer} bean to the primary
   * mapper, this single bean enforces the rule platform-wide — across REST payloads, actuator
   * output, error bodies, and any DTO logging. No setting that would coerce a {@code BigDecimal} to
   * a binary floating-point type is ever enabled (Agent Action Plan §0.6.1).
   *
   * <p>{@link SerializationFeature#WRITE_BIGDECIMAL_AS_PLAIN} is marked deprecated in the Jackson
   * 2.21.x release present on the project's classpath (ahead of the feature reorganization planned
   * for Jackson 3.0), yet it remains the canonical and fully functional 2.x mechanism for plain
   * {@code BigDecimal} output. It is therefore retained deliberately, and this method carries
   * {@code @SuppressWarnings("deprecation")} so the mandated decimal-fidelity behaviour coexists
   * with the project's {@code -Werror} zero-warning build gate (Agent Action Plan §0.7.3).
   *
   * @return a customizer that enables plain {@code BigDecimal} serialization and {@code
   *     BigDecimal}-backed parsing of JSON floating-point numbers
   */
  @Bean
  @SuppressWarnings("deprecation")
  public Jackson2ObjectMapperBuilderCustomizer bigDecimalFidelityCustomizer() {
    return builder -> {
      // Serialize BigDecimal in plain (non-scientific) notation, preserving scale. This feature is
      // deprecated in Jackson 2.21+ but is still the canonical 2.x switch for plain BigDecimal
      // output; it is retained intentionally (see method Javadoc and @SuppressWarnings above).
      builder.featuresToEnable(SerializationFeature.WRITE_BIGDECIMAL_AS_PLAIN);
      // Parse JSON floating-point numbers as BigDecimal (never double) to preserve precision.
      builder.featuresToEnable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS);
    };
  }
}
