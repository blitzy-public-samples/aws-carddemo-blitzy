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

/**
 * Fixed-width string helpers that reproduce COBOL {@code MOVE}/{@code PIC} field semantics in Java.
 *
 * <p>The legacy z/OS programs read and write fixed-width records (reports, statements, reject
 * files, and screen/DTO fields) whose layouts are dictated by COBOL picture clauses. To keep the
 * migrated Java output byte-faithful to the mainframe, every place that assembles such a field must
 * apply the exact same justification, padding, and truncation rules. This class centralizes those
 * rules so callers never re-implement them.
 *
 * <p>Two COBOL receiving-field behaviors are mirrored:
 *
 * <ul>
 *   <li><b>Alphanumeric</b> ({@code PIC X(n)}) — fixed length, <em>left-justified</em>,
 *       <em>space-padded on the right</em>, and <em>truncated on the right</em> (the left-most
 *       {@code n} characters are kept) when the sending value is longer. See {@link
 *       #padRight(String, int)} / {@link #fixedWidth(String, int)}.
 *   <li><b>Numeric display</b> ({@code PIC 9(n)}) — fixed length, <em>right-justified</em>,
 *       <em>zero-padded on the left</em>, with <em>high-order (left-most) truncation</em> when the
 *       sending value is longer. See {@link #padLeftZeros(String, int)} / {@link
 *       #padLeftZeros(long, int)}.
 * </ul>
 *
 * <p>Design contract:
 *
 * <ul>
 *   <li>This class is JDK-only and uses no floating-point arithmetic.
 *   <li>Every method is null-safe: a {@code null} input string is treated as COBOL {@code SPACES}
 *       (an empty string), never raising {@link NullPointerException}.
 *   <li>Any requested {@code width <= 0} yields an empty string.
 *   <li>All methods are pure (no shared state) and therefore thread-safe.
 * </ul>
 *
 * <p>Reference layouts that drove these rules include the daily transaction report structure in
 * {@code app/cpy/CVTRA07Y.cpy} and the report transaction record in {@code app/cpy/COSTM01.CPY}.
 * Edited numeric pictures (for example {@code PIC -ZZZ,ZZZ,ZZZ.ZZ}) are intentionally out of scope
 * here and are handled by the dedicated number formatter.
 */
public final class CobolStringUtils {

  /** Utility class; never instantiated. */
  private CobolStringUtils() {
    // utility class
    throw new AssertionError("CobolStringUtils is a non-instantiable utility class");
  }

  /**
   * Reproduces a COBOL {@code MOVE} of an alphanumeric value into a {@code PIC X(width)} field.
   *
   * <p>COBOL alphanumeric receiving fields are fixed length, left-justified, and space-filled on
   * the right. When the sending value is longer than the field it is truncated on the right (the
   * left-most {@code width} characters are retained). This mirrors {@code MOVE WS-SRC TO} a {@code
   * PIC X(width)} item exactly.
   *
   * @param value the source text; a {@code null} value is treated as COBOL {@code SPACES}
   * @param width the fixed receiving-field length; values {@code <= 0} yield an empty string
   * @return a string of exactly {@code width} characters (for {@code width > 0}), right-padded with
   *     spaces or right-truncated as required
   */
  public static String padRight(String value, int width) {
    if (width <= 0) {
      return "";
    }
    String source = (value == null) ? "" : value;
    if (source.length() >= width) {
      return source.substring(0, width);
    }
    return source + " ".repeat(width - source.length());
  }

  /**
   * Reproduces a COBOL numeric-display {@code MOVE} into a {@code PIC 9(width)} field from a string
   * of digits.
   *
   * <p>COBOL unsigned numeric-display receiving fields are right-justified and zero-filled on the
   * left. When the sending value has more characters than the field, the high-order (left-most)
   * characters are dropped and the right-most {@code width} characters are retained. The input is
   * used verbatim and is intentionally not validated as digits, matching a byte-level COBOL {@code
   * MOVE}.
   *
   * @param value the source digits; a {@code null} or empty value yields {@code width} zeros
   * @param width the fixed receiving-field length; values {@code <= 0} yield an empty string
   * @return a string of exactly {@code width} characters (for {@code width > 0}), left-padded with
   *     {@code '0'} or high-order-truncated as required
   */
  public static String padLeftZeros(String value, int width) {
    if (width <= 0) {
      return "";
    }
    String source = (value == null) ? "" : value;
    int length = source.length();
    if (length >= width) {
      return source.substring(length - width);
    }
    return "0".repeat(width - length) + source;
  }

  /**
   * Convenience overload that renders a {@code long} as zero-padded numeric-display digits.
   *
   * <p>Mirrors moving a binary/numeric work field into a {@code PIC 9(width)} display item. The
   * <em>absolute value</em> digits are emitted: any sign is discarded so the result is always
   * unsigned, because sign handling is the caller's (or the number formatter's) concern. Overflow
   * follows the same high-order truncation as {@link #padLeftZeros(String, int)}. {@link
   * Long#MIN_VALUE} is handled safely because the magnitude digits are taken from {@link
   * Long#toString(long)} rather than {@code Math.abs}, which would overflow for that value.
   *
   * @param value the numeric value whose absolute-value digits are emitted
   * @param width the fixed receiving-field length; values {@code <= 0} yield an empty string
   * @return a string of exactly {@code width} unsigned digits (for {@code width > 0})
   */
  public static String padLeftZeros(long value, int width) {
    if (width <= 0) {
      return "";
    }
    String digits = Long.toString(value);
    if (!digits.isEmpty() && digits.charAt(0) == '-') {
      digits = digits.substring(1);
    }
    return padLeftZeros(digits, width);
  }

  /**
   * Returns the left-most {@code width} characters of {@code value} without padding.
   *
   * <p>Mirrors a COBOL reference-modification such as {@code WS-FIELD(1:width)} or a {@code MOVE}
   * into a shorter alphanumeric field where only right-truncation (never padding) is desired. A
   * value shorter than {@code width} is returned unchanged.
   *
   * @param value the source text; {@code null} yields an empty string
   * @param width the maximum number of leading characters to retain; values {@code <= 0} yield an
   *     empty string
   * @return at most the first {@code width} characters of {@code value}
   */
  public static String truncate(String value, int width) {
    if (value == null || width <= 0) {
      return "";
    }
    return value.length() <= width ? value : value.substring(0, width);
  }

  /**
   * Builds a fixed-width alphanumeric field. This is an explicitly named alias of {@link
   * #padRight(String, int)}, provided for readability when assembling fixed-layout records such as
   * reports, statements, and reject files.
   *
   * <p>Like {@code padRight} it reproduces a COBOL {@code PIC X(width)} {@code MOVE}: left-justify,
   * right-pad with spaces, and right-truncate when the source is longer than {@code width}.
   *
   * @param value the source text; a {@code null} value is treated as COBOL {@code SPACES}
   * @param width the fixed field length; values {@code <= 0} yield an empty string
   * @return a string of exactly {@code width} characters (for {@code width > 0})
   */
  public static String fixedWidth(String value, int width) {
    return padRight(value, width);
  }

  /**
   * Removes trailing spaces only, mirroring COBOL {@code FUNCTION TRIM(field TRAILING)}.
   *
   * <p>Only the ASCII space character ({@code ' '}, COBOL {@code SPACES}) is removed; leading
   * spaces and every other character are preserved. This is typically used to convert a
   * space-padded fixed-width field back to its logical (display) value.
   *
   * @param value the source text; {@code null} yields an empty string
   * @return {@code value} with trailing spaces removed
   */
  public static String rtrim(String value) {
    if (value == null) {
      return "";
    }
    int end = value.length();
    while (end > 0 && value.charAt(end - 1) == ' ') {
      end--;
    }
    return value.substring(0, end);
  }

  /**
   * Removes both leading and trailing spaces, mirroring COBOL {@code FUNCTION TRIM(field)}.
   *
   * <p>Only the ASCII space character ({@code ' '}, COBOL {@code SPACES}) is stripped from each
   * end; interior spaces and every other character are preserved.
   *
   * @param value the source text; {@code null} yields an empty string
   * @return {@code value} with leading and trailing spaces removed
   */
  public static String trim(String value) {
    if (value == null) {
      return "";
    }
    int start = 0;
    int end = value.length();
    while (start < end && value.charAt(start) == ' ') {
      start++;
    }
    while (end > start && value.charAt(end - 1) == ' ') {
      end--;
    }
    return value.substring(start, end);
  }

  /**
   * Returns a string of exactly {@code width} spaces, mirroring a COBOL {@code MOVE SPACES} into a
   * {@code PIC X(width)} field or a {@code FILLER ... VALUE SPACES} clause.
   *
   * @param width the number of spaces to produce; values {@code <= 0} yield an empty string
   * @return a string consisting of {@code width} space characters
   */
  public static String spaces(int width) {
    return width <= 0 ? "" : " ".repeat(width);
  }
}
