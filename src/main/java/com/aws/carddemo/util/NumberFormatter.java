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

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * Reproduces the COBOL edited-numeric {@code PIC} clauses used by the CardDemo daily transaction
 * report and customer statement, emitting <em>byte-faithful</em> fixed-width {@link String}s.
 *
 * <p>The legacy z/OS programs render monetary amounts into print lines whose exact column layout is
 * dictated by COBOL picture clauses. To keep the migrated Java output identical to the mainframe
 * (the acceptance bar is 100% parity, verified by golden-file tests), every amount that lands on a
 * report or statement line must be edited with the same zero-suppression, comma-insertion, decimal,
 * and sign rules that the COBOL compiler applied. This class centralizes those rules so callers
 * never re-implement them.
 *
 * <p><b>Decimal fidelity (AAP §0.6.1).</b> All arithmetic uses {@link java.math.BigDecimal}; the
 * primitive IEEE-754 real types are deliberately avoided so no binary-fraction error is ever
 * introduced. Before editing, each value is reduced to scale {@value #DECIMAL_SCALE} with {@link
 * RoundingMode#DOWN} (truncation toward zero) because the COBOL {@code MOVE}/{@code COMPUTE} into
 * these fields carries no {@code ROUNDED} phrase and therefore truncates rather than rounds.
 *
 * <p><b>Null handling.</b> A {@code null} amount is treated as {@link BigDecimal#ZERO}, mirroring a
 * COBOL numeric work field declared with {@code VALUE ZERO}; callers therefore never receive a
 * {@link NullPointerException}.
 *
 * <p>The four supported masks (all derived from the legacy sources named below):
 *
 * <ul>
 *   <li>{@link #formatReportAmount(BigDecimal)} — {@code PIC -ZZZ,ZZZ,ZZZ.ZZ}, width 15.
 *   <li>{@link #formatTotalAmount(BigDecimal)} — {@code PIC +ZZZ,ZZZ,ZZZ.ZZ}, width 15.
 *   <li>{@link #formatStatementAmount(BigDecimal)} — {@code PIC Z(9).99-}, width 13.
 *   <li>{@link #formatFixedSignedAmount(BigDecimal)} — {@code PIC +99999999.99}, width 12.
 * </ul>
 *
 * <p>The trap when reproducing these masks is the zero value: the all-{@code Z} report masks blank
 * the <em>entire</em> numeric region when the value is zero (the {@code +} mask still prints its
 * leading sign), whereas the statement mask keeps {@code .00} and the fixed mask keeps {@code
 * +00000000.00}. The three behaviors are implemented distinctly below.
 *
 * <p>This class is JDK-only, stateless, and therefore thread-safe.
 */
public final class NumberFormatter {

  /** COBOL scale of every monetary/amount field edited here ({@code V99}). */
  private static final int DECIMAL_SCALE = 2;

  /** Integer digit positions in the report/statement masks ({@code ZZZ,ZZZ,ZZZ} / {@code Z(9)}). */
  private static final int REPORT_INTEGER_DIGITS = 9;

  /** Integer digit positions in the fixed signed mask ({@code 99999999}). */
  private static final int FIXED_INTEGER_DIGITS = 8;

  /** Width of the report category-code field ({@code PIC 9(04)}). */
  private static final int CATEGORY_CODE_DIGITS = 4;

  /** Utility class; never instantiated. */
  private NumberFormatter() {
    // utility class
    throw new AssertionError("NumberFormatter is a non-instantiable utility class");
  }

  /**
   * Where the editing sign character is placed and which glyph represents a non-negative value.
   *
   * <p>Each constant models one of the COBOL sign-insertion symbols used by the supported masks.
   */
  private enum SignStyle {
    /** Leading {@code PIC -}: {@code '-'} when negative, {@code ' '} (space) otherwise. */
    LEADING_MINUS_SPACE,
    /** Leading {@code PIC +}: {@code '-'} when negative, {@code '+'} otherwise (always shown). */
    LEADING_PLUS,
    /** Trailing {@code PIC ...-}: {@code '-'} when negative, {@code ' '} (space) otherwise. */
    TRAILING_MINUS_SPACE
  }

  /**
   * Formats a transaction amount for the daily transaction detail report.
   *
   * <p>COBOL picture: {@code PIC -ZZZ,ZZZ,ZZZ.ZZ} — field {@code TRAN-REPORT-AMT} in {@code
   * app/cpy/CVTRA07Y.cpy} (line 30). The result is exactly <b>15</b> characters: a leading
   * fixed-insertion sign (column 1: {@code '-'} if negative, otherwise a space), nine
   * zero-suppressed integer positions with comma group separators, a decimal point, and two decimal
   * digits. Leading zeros — and any comma that falls inside the suppressed zone — become spaces.
   * When the whole value is zero the entire numeric region (digits, commas, point and decimals) is
   * blanked, leaving 15 spaces.
   *
   * <p>Examples (the quotes delimit the exact 15-character field):
   *
   * <pre>
   *      0.00          -> "               "
   *      0.05          -> "            .05"
   *     -123.45        -> "-        123.45"
   *      1234567.89    -> "   1,234,567.89"
   *     -999999999.99  -> "-999,999,999.99"
   *     -0.99          -> "-           .99"
   * </pre>
   *
   * @param amount the amount; {@code null} is treated as zero. Truncated to scale {@value
   *     #DECIMAL_SCALE} ({@link RoundingMode#DOWN}) before editing
   * @return a 15-character edited string matching the COBOL {@code PIC -ZZZ,ZZZ,ZZZ.ZZ} field
   */
  public static String formatReportAmount(BigDecimal amount) {
    return edit(
        amount,
        REPORT_INTEGER_DIGITS,
        /* useCommas= */ true,
        /* suppressInteger= */ true,
        /* blankWholeOnZero= */ true,
        SignStyle.LEADING_MINUS_SPACE);
  }

  /**
   * Formats a running/grand total for the daily transaction report.
   *
   * <p>COBOL picture: {@code PIC +ZZZ,ZZZ,ZZZ.ZZ} — fields {@code REPT-PAGE-TOTAL}, {@code
   * REPT-ACCOUNT-TOTAL} and {@code REPT-GRAND-TOTAL} in {@code app/cpy/CVTRA07Y.cpy} (lines 54, 60
   * and 66). Identical to {@link #formatReportAmount(BigDecimal)} except the leading sign is always
   * shown: {@code '+'} for a zero-or-positive value and {@code '-'} for a negative value. The
   * result is exactly <b>15</b> characters; for a zero value the numeric region is blanked but the
   * {@code '+'} still occupies column 1 (a zero total renders as {@code '+'} followed by 14
   * spaces).
   *
   * <p>Examples (the quotes delimit the exact 15-character field):
   *
   * <pre>
   *      0.00          -> "+              "
   *      1.00          -> "+          1.00"
   *     -1.00          -> "-          1.00"
   *      1234567.89    -> "+  1,234,567.89"
   *     -1234567.89    -> "-  1,234,567.89"
   *      999999999.99  -> "+999,999,999.99"
   * </pre>
   *
   * @param amount the total; {@code null} is treated as zero. Truncated to scale {@value
   *     #DECIMAL_SCALE} ({@link RoundingMode#DOWN}) before editing
   * @return a 15-character edited string matching the COBOL {@code PIC +ZZZ,ZZZ,ZZZ.ZZ} field
   */
  public static String formatTotalAmount(BigDecimal amount) {
    return edit(
        amount,
        REPORT_INTEGER_DIGITS,
        /* useCommas= */ true,
        /* suppressInteger= */ true,
        /* blankWholeOnZero= */ true,
        SignStyle.LEADING_PLUS);
  }

  /**
   * Formats a transaction amount for the printed/HTML customer statement.
   *
   * <p>COBOL picture: {@code PIC Z(9).99-} — fields {@code ST-TRANAMT} and {@code ST-TOTAL-TRAMT}
   * in {@code app/cbl/CBSTM03A.CBL} (lines 137 and 142). The result is exactly <b>13</b>
   * characters: nine zero-suppressed integer positions (leading zeros become spaces, <em>no</em>
   * comma separators), a decimal point, two <em>forced</em> decimal digits, and a <em>trailing</em>
   * sign in the final column ({@code '-'} if negative, otherwise a space). Because the fraction is
   * forced (picture {@code 9}, not {@code Z}), {@code .00} prints even for a zero value — only the
   * integer positions blank.
   *
   * <p>Examples (the quotes delimit the exact 13-character field):
   *
   * <pre>
   *      0.00         -> "         .00 "
   *     -5.00         -> "        5.00-"
   *      1234567.89   -> "  1234567.89 "
   *     -123456789.99 -> "123456789.99-"
   *     -0.01         -> "         .01-"
   * </pre>
   *
   * @param amount the amount; {@code null} is treated as zero. Truncated to scale {@value
   *     #DECIMAL_SCALE} ({@link RoundingMode#DOWN}) before editing
   * @return a 13-character edited string matching the COBOL {@code PIC Z(9).99-} field
   */
  public static String formatStatementAmount(BigDecimal amount) {
    return edit(
        amount,
        REPORT_INTEGER_DIGITS,
        /* useCommas= */ false,
        /* suppressInteger= */ true,
        /* blankWholeOnZero= */ false,
        SignStyle.TRAILING_MINUS_SPACE);
  }

  /**
   * Formats a transaction amount into the fixed signed work field used when generating report JCL.
   *
   * <p>COBOL picture: {@code PIC +99999999.99} — field {@code WS-TRAN-AMT} in {@code
   * app/cbl/CORPT00C.cbl} (line 77). The result is exactly <b>12</b> characters: a leading sign
   * always shown ({@code '+'} for zero-or-positive, {@code '-'} for negative), eight
   * <em>forced</em> integer digits (leading zeros <em>are</em> printed; no suppression and no
   * commas), a decimal point, and two forced decimal digits.
   *
   * <p><b>Overflow.</b> When the integer part exceeds eight digits, COBOL drops the high-order
   * digits; this method preserves that behavior by retaining the right-most eight integer digits
   * (for example {@code 123456789.99 -> "+23456789.99"}). This is an edge case outside the report's
   * normal value range.
   *
   * <p>Examples (the quotes delimit the exact 12-character field):
   *
   * <pre>
   *      0.00         -> "+00000000.00"
   *     -5.00         -> "-00000005.00"
   *     -0.01         -> "-00000000.01"
   *      99999999.99  -> "+99999999.99"
   *     -99999999.99  -> "-99999999.99"
   * </pre>
   *
   * @param amount the amount; {@code null} is treated as zero. Truncated to scale {@value
   *     #DECIMAL_SCALE} ({@link RoundingMode#DOWN}) before editing
   * @return a 12-character edited string matching the COBOL {@code PIC +99999999.99} field
   */
  public static String formatFixedSignedAmount(BigDecimal amount) {
    return edit(
        amount,
        FIXED_INTEGER_DIGITS,
        /* useCommas= */ false,
        /* suppressInteger= */ false,
        /* blankWholeOnZero= */ false,
        SignStyle.LEADING_PLUS);
  }

  /**
   * Formats a transaction category code as fixed-width zero-padded display digits.
   *
   * <p>COBOL picture: {@code PIC 9(04)} — field {@code TRAN-REPORT-CAT-CD} in {@code
   * app/cpy/CVTRA07Y.cpy} (line 24); the same layout backs {@code TRNX-CAT-CD} in {@code
   * app/cpy/COSTM01.CPY} (line 26). The field is an unsigned four-digit display item, so the
   * absolute-value digits are emitted left-padded with {@code '0'} (for example {@code 5 ->
   * "0005"}, {@code 1234 -> "1234"}). Delegates to {@link CobolStringUtils#padLeftZeros(long, int)}
   * which applies the same right-justified, zero-filled, high-order-truncating semantics as a COBOL
   * numeric-display {@code MOVE}.
   *
   * @param code the category code; the sign is discarded to match the unsigned {@code PIC 9(04)}
   * @return a 4-character zero-padded numeric-display string
   */
  public static String formatCategoryCode(int code) {
    return CobolStringUtils.padLeftZeros(code, CATEGORY_CODE_DIGITS);
  }

  /**
   * Core editing routine shared by every public mask.
   *
   * <p>The amount is truncated to scale {@value #DECIMAL_SCALE} ({@link RoundingMode#DOWN}), split
   * into sign / integer-digit / two-digit-fraction parts using {@link BigDecimal} operations only,
   * and then assembled according to the supplied mask options.
   *
   * @param amount the value to edit; {@code null} is treated as {@link BigDecimal#ZERO}
   * @param integerWidth number of integer digit positions in the mask
   * @param useCommas whether thousands-separator commas are inserted between integer groups
   * @param suppressInteger {@code true} for {@code Z} (zero-suppressed) integer positions, {@code
   *     false} for {@code 9} (forced, zero-padded) integer positions
   * @param blankWholeOnZero {@code true} for all-{@code Z} masks whose decimal point and fraction
   *     are also blanked when the whole value is zero; {@code false} when the fraction is forced
   *     and always printed
   * @param signStyle placement and glyph of the editing sign
   * @return the fully edited fixed-width field
   */
  private static String edit(
      BigDecimal amount,
      int integerWidth,
      boolean useCommas,
      boolean suppressInteger,
      boolean blankWholeOnZero,
      SignStyle signStyle) {

    // A null amount mirrors a COBOL numeric field initialized with VALUE ZERO.
    BigDecimal value = (amount == null) ? BigDecimal.ZERO : amount;

    // COBOL MOVE/COMPUTE into an edited field truncates (there is no ROUNDED phrase), so reduce to
    // the field scale with RoundingMode.DOWN before any digit extraction.
    BigDecimal scaled = value.setScale(DECIMAL_SCALE, RoundingMode.DOWN);
    boolean negative = scaled.signum() < 0;
    boolean wholeIsZero = scaled.signum() == 0;

    // Split the magnitude with BigDecimal operations only. After setScale(2) the plain string is
    // always "<integer>.<2-digit-fraction>", so splitting on '.' yields both parts exactly.
    String plain = scaled.abs().toPlainString();
    int dotIndex = plain.indexOf('.');
    String integerDigits = plain.substring(0, dotIndex);
    String fractionDigits = plain.substring(dotIndex + 1);

    // Normalize the integer digits to the mask width: left zero-pad, or drop high-order digits on
    // overflow, exactly as a COBOL numeric-display receiving field would.
    String paddedInteger = CobolStringUtils.padLeftZeros(integerDigits, integerWidth);
    String integerRegion = buildIntegerRegion(paddedInteger, useCommas, suppressInteger);

    // The decimal region is the decimal point plus the two fraction digits, unless this is an
    // all-Z mask and the whole value is zero, in which case the point and fraction blank too.
    String decimalRegion;
    if (blankWholeOnZero && wholeIsZero) {
      decimalRegion = " ".repeat(1 + DECIMAL_SCALE);
    } else {
      decimalRegion = "." + fractionDigits;
    }

    String numericRegion = integerRegion + decimalRegion;

    return switch (signStyle) {
      case LEADING_MINUS_SPACE -> (negative ? "-" : " ") + numericRegion;
      case LEADING_PLUS -> (negative ? "-" : "+") + numericRegion;
      case TRAILING_MINUS_SPACE -> numericRegion + (negative ? "-" : " ");
    };
  }

  /**
   * Renders the integer portion of a mask, applying COBOL zero-suppression and comma insertion.
   *
   * <p>Scanning left to right, leading zeros are replaced by spaces while {@code suppressInteger}
   * is set; suppression stops at the first significant (non-zero) digit, after which every digit —
   * including embedded zeros — prints. A comma is emitted only when a significant digit already
   * appears to its left; a comma inside the suppressed zone becomes a space. When {@code
   * suppressInteger} is {@code false} the digits (and any commas) are always shown, reproducing a
   * forced {@code 9} integer picture.
   *
   * @param paddedDigits the integer digits, already exactly the mask's integer width
   * @param useCommas whether thousands-separator commas are inserted between integer groups
   * @param suppressInteger {@code true} to zero-suppress leading zeros ({@code Z}); {@code false}
   *     for forced digits ({@code 9})
   * @return the rendered integer region (length = width, plus one character per inserted comma)
   */
  private static String buildIntegerRegion(
      String paddedDigits, boolean useCommas, boolean suppressInteger) {
    int width = paddedDigits.length();

    // Index of the first significant digit; equals width when the integer is entirely zero.
    int firstSignificant = width;
    for (int i = 0; i < width; i++) {
      if (paddedDigits.charAt(i) != '0') {
        firstSignificant = i;
        break;
      }
    }

    StringBuilder region = new StringBuilder(width + width / 3);
    for (int i = 0; i < width; i++) {
      // A comma sits to the LEFT of digit i when the digits remaining (width - i) form a complete
      // group of three. It prints only once suppression has ended (a significant digit is to its
      // left); otherwise it is blanked along with the leading zeros.
      if (useCommas && i > 0 && (width - i) % 3 == 0) {
        boolean commaVisible = !suppressInteger || firstSignificant < i;
        region.append(commaVisible ? ',' : ' ');
      }
      boolean suppressed = suppressInteger && i < firstSignificant;
      region.append(suppressed ? ' ' : paddedDigits.charAt(i));
    }
    return region.toString();
  }
}
