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

package com.aws.carddemo.util;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Byte-faithful parity tests for {@link NumberFormatter}, the canonical guard for COBOL
 * edited-numeric {@code PIC} fidelity (Agent Action Plan &sect;0.6.1).
 *
 * <p>The migrated statements and reports are compared character-for-character against expected
 * mainframe output by golden-file parity tests in the batch/service packages. A single wrong space,
 * a misplaced comma, an incorrect sign position, or a rounding-vs-truncation error there is a
 * parity failure. This suite pins every edited-numeric mask at the source so those higher-level
 * tests have a trustworthy formatting primitive.
 *
 * <p>The four masks under test (each derived from a legacy COBOL picture clause):
 *
 * <ul>
 *   <li>{@link NumberFormatter#formatReportAmount(BigDecimal)} — {@code PIC -ZZZ,ZZZ,ZZZ.ZZ}, width
 *       15 ({@code TRAN-REPORT-AMT}, {@code app/cpy/CVTRA07Y.cpy} L30).
 *   <li>{@link NumberFormatter#formatTotalAmount(BigDecimal)} — {@code PIC +ZZZ,ZZZ,ZZZ.ZZ}, width
 *       15 ({@code REPT-PAGE/ACCOUNT/GRAND-TOTAL}, {@code app/cpy/CVTRA07Y.cpy} L54/L60/L66).
 *   <li>{@link NumberFormatter#formatStatementAmount(BigDecimal)} — {@code PIC Z(9).99-}, width 13
 *       ({@code ST-TRANAMT}/{@code ST-TOTAL-TRAMT}, {@code app/cbl/CBSTM03A.CBL} L137/L142).
 *   <li>{@link NumberFormatter#formatFixedSignedAmount(BigDecimal)} — {@code PIC +99999999.99},
 *       width 12 ({@code WS-TRAN-AMT}, {@code app/cbl/CORPT00C.cbl} L77).
 * </ul>
 *
 * <p><b>Decimal-only contract.</b> Every input below is built with the {@link
 * BigDecimal#BigDecimal(String) String constructor} — never {@code new BigDecimal(double)} — so no
 * binary floating-point error is ever introduced into the test vectors. The truncation cases prove
 * the production code performs exact decimal arithmetic ({@code setScale(2, RoundingMode.DOWN)})
 * rather than floating-point rounding: COBOL {@code MOVE}/{@code COMPUTE} into these edited fields
 * carries no {@code ROUNDED} phrase and therefore truncates toward zero.
 *
 * <p>The expected strings are exact Java {@code String} literals (every leading/trailing space and
 * embedded comma is significant); they are supplied through {@link MethodSource} providers rather
 * than {@code @CsvSource} so neither the commas nor the surrounding whitespace are mangled. Each
 * vector asserts both exact equality and the fixed field width.
 *
 * <p>The suite is intentionally JDK-only: no Spring context, no database, no Testcontainers, and no
 * mocks.
 */
@DisplayName("NumberFormatter — COBOL edited-numeric PIC parity")
class NumberFormatterTest {

  // ---------------------------------------------------------------------------
  // Mask A — formatReportAmount: PIC -ZZZ,ZZZ,ZZZ.ZZ, width 15.
  // Leading floating minus (space when non-negative), comma grouping, zero-suppressed integer;
  // the entire field blanks (15 spaces) when the value is exactly zero. CVTRA07Y.cpy L30.
  // ---------------------------------------------------------------------------

  static Stream<Arguments> reportAmountVectors() {
    return Stream.of(
        Arguments.of(new BigDecimal("0.00"), "               "),
        Arguments.of(new BigDecimal("0.05"), "            .05"),
        Arguments.of(new BigDecimal("1.00"), "           1.00"),
        Arguments.of(new BigDecimal("-1.00"), "-          1.00"),
        Arguments.of(new BigDecimal("123.45"), "         123.45"),
        Arguments.of(new BigDecimal("-123.45"), "-        123.45"),
        Arguments.of(new BigDecimal("1234567.89"), "   1,234,567.89"),
        Arguments.of(new BigDecimal("-1234567.89"), "-  1,234,567.89"),
        Arguments.of(new BigDecimal("999999999.99"), " 999,999,999.99"),
        Arguments.of(new BigDecimal("-999999999.99"), "-999,999,999.99"),
        Arguments.of(new BigDecimal("-0.99"), "-           .99"));
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("reportAmountVectors")
  void formatReportAmount_matches_report_mask_vectors(BigDecimal input, String expected) {
    String result = NumberFormatter.formatReportAmount(input);
    assertThat(result).isEqualTo(expected).hasSize(15);
  }

  // ---------------------------------------------------------------------------
  // Mask B — formatTotalAmount: PIC +ZZZ,ZZZ,ZZZ.ZZ, width 15.
  // Identical to Mask A except the leading sign is ALWAYS shown ('+' for non-negative, '-' for
  // negative); a zero total renders '+' followed by 14 spaces. CVTRA07Y.cpy L54/L60/L66.
  // ---------------------------------------------------------------------------

  static Stream<Arguments> totalAmountVectors() {
    return Stream.of(
        Arguments.of(new BigDecimal("0.00"), "+              "),
        Arguments.of(new BigDecimal("1.00"), "+          1.00"),
        Arguments.of(new BigDecimal("-1.00"), "-          1.00"),
        Arguments.of(new BigDecimal("1234567.89"), "+  1,234,567.89"),
        Arguments.of(new BigDecimal("-1234567.89"), "-  1,234,567.89"),
        Arguments.of(new BigDecimal("999999999.99"), "+999,999,999.99"));
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("totalAmountVectors")
  void formatTotalAmount_matches_total_mask_vectors(BigDecimal input, String expected) {
    String result = NumberFormatter.formatTotalAmount(input);
    assertThat(result).isEqualTo(expected).hasSize(15);
  }

  // ---------------------------------------------------------------------------
  // Mask C — formatStatementAmount: PIC Z(9).99-, width 13.
  // No commas; zero-suppressed 9-digit integer; decimals ALWAYS shown (forced .NN); trailing sign
  // ('-' for negative, space otherwise). CBSTM03A.CBL L137/L142.
  // ---------------------------------------------------------------------------

  static Stream<Arguments> statementAmountVectors() {
    return Stream.of(
        Arguments.of(new BigDecimal("0.00"), "         .00 "),
        Arguments.of(new BigDecimal("0.05"), "         .05 "),
        Arguments.of(new BigDecimal("5.00"), "        5.00 "),
        Arguments.of(new BigDecimal("-5.00"), "        5.00-"),
        Arguments.of(new BigDecimal("123.45"), "      123.45 "),
        Arguments.of(new BigDecimal("-123.45"), "      123.45-"),
        Arguments.of(new BigDecimal("1234567.89"), "  1234567.89 "),
        Arguments.of(new BigDecimal("-1234567.89"), "  1234567.89-"),
        Arguments.of(new BigDecimal("123456789.99"), "123456789.99 "),
        Arguments.of(new BigDecimal("-123456789.99"), "123456789.99-"),
        Arguments.of(new BigDecimal("-0.01"), "         .01-"));
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("statementAmountVectors")
  void formatStatementAmount_matches_statement_mask_vectors(BigDecimal input, String expected) {
    String result = NumberFormatter.formatStatementAmount(input);
    assertThat(result).isEqualTo(expected).hasSize(13);
  }

  // ---------------------------------------------------------------------------
  // Mask D — formatFixedSignedAmount: PIC +99999999.99, width 12.
  // Leading sign always shown; eight FORCED integer digits (zero-filled, NOT suppressed; no
  // commas); forced decimals. CORPT00C.cbl L77.
  // ---------------------------------------------------------------------------

  static Stream<Arguments> fixedSignedAmountVectors() {
    return Stream.of(
        Arguments.of(new BigDecimal("0.00"), "+00000000.00"),
        Arguments.of(new BigDecimal("0.05"), "+00000000.05"),
        Arguments.of(new BigDecimal("5.00"), "+00000005.00"),
        Arguments.of(new BigDecimal("-5.00"), "-00000005.00"),
        Arguments.of(new BigDecimal("123.45"), "+00000123.45"),
        Arguments.of(new BigDecimal("-123.45"), "-00000123.45"),
        Arguments.of(new BigDecimal("-0.01"), "-00000000.01"),
        Arguments.of(new BigDecimal("99999999.99"), "+99999999.99"),
        Arguments.of(new BigDecimal("-99999999.99"), "-99999999.99"));
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("fixedSignedAmountVectors")
  void formatFixedSignedAmount_matches_fixed_signed_mask_vectors(
      BigDecimal input, String expected) {
    String result = NumberFormatter.formatFixedSignedAmount(input);
    assertThat(result).isEqualTo(expected).hasSize(12);
  }

  // ---------------------------------------------------------------------------
  // Truncation parity (the §0.6.1 headline): RoundingMode.DOWN, never HALF_UP.
  // ---------------------------------------------------------------------------

  @Test
  @DisplayName("truncates extra fraction digits toward zero — never rounds")
  void truncates_extra_fraction_digits_toward_zero() {
    // Truncation (RoundingMode.DOWN), not rounding — COBOL COMPUTE without ROUNDED, §0.6.1.
    // The production formatter applies setScale(2, RoundingMode.DOWN) before editing, so every
    // fractional digit beyond the second is DROPPED rather than rounded up.
    assertThat(NumberFormatter.formatReportAmount(new BigDecimal("1.005")))
        .isEqualTo("           1.00")
        .hasSize(15);
    assertThat(NumberFormatter.formatReportAmount(new BigDecimal("1.999")))
        .isEqualTo("           1.99")
        .hasSize(15);
    // RoundingMode.DOWN truncates toward zero, so -1.999 -> -1.99 (the magnitude shrinks).
    assertThat(NumberFormatter.formatReportAmount(new BigDecimal("-1.999")))
        .isEqualTo("-          1.99")
        .hasSize(15);
    assertThat(NumberFormatter.formatStatementAmount(new BigDecimal("5.999")))
        .isEqualTo("        5.99 ")
        .hasSize(13);
    assertThat(NumberFormatter.formatFixedSignedAmount(new BigDecimal("5.999")))
        .isEqualTo("+00000005.99")
        .hasSize(12);
  }

  // ---------------------------------------------------------------------------
  // Null handling — production treats null as BigDecimal.ZERO (COBOL VALUE ZERO).
  // ---------------------------------------------------------------------------

  @Test
  @DisplayName("null amount renders the zero vector for each mask")
  void null_amount_is_treated_as_zero_per_mask() {
    // NumberFormatter#edit maps a null amount to BigDecimal.ZERO, so each mask yields exactly the
    // vector it produces for 0.00 — callers never receive a NullPointerException.
    assertThat(NumberFormatter.formatReportAmount(null)).isEqualTo("               ").hasSize(15);
    assertThat(NumberFormatter.formatTotalAmount(null)).isEqualTo("+              ").hasSize(15);
    assertThat(NumberFormatter.formatStatementAmount(null)).isEqualTo("         .00 ").hasSize(13);
    assertThat(NumberFormatter.formatFixedSignedAmount(null)).isEqualTo("+00000000.00").hasSize(12);
  }

  // ---------------------------------------------------------------------------
  // Optional formatCategoryCode(int) — PIC 9(04). Resolved reflectively so the suite neither
  // hard-depends on the method nor fails when a build omits it (per the file contract).
  // ---------------------------------------------------------------------------

  @Test
  @DisplayName("formatCategoryCode (optional) zero-pads PIC 9(04) when present")
  void formatCategoryCode_optional_zero_pads_when_present() throws ReflectiveOperationException {
    // TRAN-REPORT-CAT-CD PIC 9(04), CVTRA07Y.cpy L24; the same layout backs TRNX-CAT-CD in
    // COSTM01.CPY L26. The four-digit display item is right-justified and left zero-padded.
    Method method;
    try {
      method = NumberFormatter.class.getMethod("formatCategoryCode", int.class);
    } catch (NoSuchMethodException absent) {
      return; // Method not present in this build — nothing to assert.
    }
    assertThat((String) method.invoke(null, 5)).isEqualTo("0005");
    assertThat((String) method.invoke(null, 1234)).isEqualTo("1234");
  }
}
