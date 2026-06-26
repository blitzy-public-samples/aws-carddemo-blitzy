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

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Pure unit tests for {@link CobolStringUtils}, the lowest-level helpers that reproduce COBOL
 * fixed-width {@code MOVE} semantics in Java.
 *
 * <p>These helpers underpin byte-faithful fixed-width record I/O and report rendering (Agent Action
 * Plan &sect;0.6.1). The two COBOL receiving-field behaviors exercised here are:
 *
 * <ul>
 *   <li><b>{@code PIC X(n)}</b> — left-justify, space-pad on the right, truncate on the right
 *       (verified through {@link CobolStringUtils#padRight(String, int)} and its alias {@link
 *       CobolStringUtils#fixedWidth(String, int)}); reference layouts such as {@code
 *       TRAN-REPORT-ACCOUNT-ID PIC X(11)} in {@code app/cpy/CVTRA07Y.cpy}.
 *   <li><b>{@code PIC 9(n)}</b> — right-justify, zero-pad on the left, high-order (left-most)
 *       truncation (verified through {@link CobolStringUtils#padLeftZeros(String, int)} and {@link
 *       CobolStringUtils#padLeftZeros(long, int)}); reference layouts such as {@code
 *       TRAN-REPORT-CAT-CD PIC 9(04)}.
 * </ul>
 *
 * <p>The suite is intentionally JDK-only: no Spring context, no database, no Testcontainers, and no
 * mocks. Every vector asserts exact string equality and, where the receiving-field width matters,
 * the produced length. Edge cases ({@code null}, empty, {@code width <= 0}, and overflow) are
 * covered exhaustively because every downstream formatter and date utility relies on these
 * contracts. The assertions are aligned to the actual production contract in {@code
 * src/main/java/com/aws/carddemo/util/CobolStringUtils.java}.
 */
@DisplayName("CobolStringUtils — COBOL PIC X(n)/PIC 9(n) MOVE semantics")
class CobolStringUtilsTest {

  // ---------------------------------------------------------------------------
  // padRight — PIC X(n): left-justify, space-pad right, right-truncate
  // ---------------------------------------------------------------------------

  @Test
  void padRight_pads_and_truncates_to_fixed_width() {
    // Shorter than width: space-pad on the right to the fixed length.
    assertThat(CobolStringUtils.padRight("AB", 5)).isEqualTo("AB   ").hasSize(5);
    // Longer than width: keep the left-most `width` chars (truncate on the right).
    assertThat(CobolStringUtils.padRight("ABCDEF", 3)).isEqualTo("ABC").hasSize(3);
    // Exactly the width: unchanged.
    assertThat(CobolStringUtils.padRight("ABC", 3)).isEqualTo("ABC").hasSize(3);
    // Empty source: all spaces (COBOL SPACES).
    assertThat(CobolStringUtils.padRight("", 3)).isEqualTo("   ").hasSize(3);
  }

  @Test
  void padRight_is_null_safe_and_handles_nonpositive_width() {
    // null is treated as COBOL SPACES, never throwing NullPointerException.
    assertThat(CobolStringUtils.padRight(null, 3)).isEqualTo("   ").hasSize(3);
    // width <= 0 yields an empty string.
    assertThat(CobolStringUtils.padRight("AB", 0)).isEmpty();
    assertThat(CobolStringUtils.padRight("AB", -1)).isEmpty();
  }

  @Test
  void fixedWidth_delegates_to_padRight() {
    // The alias must behave identically to padRight for the same inputs.
    assertThat(CobolStringUtils.fixedWidth("AB", 5)).isEqualTo("AB   ").hasSize(5);
    assertThat(CobolStringUtils.fixedWidth("AB", 5)).isEqualTo(CobolStringUtils.padRight("AB", 5));
  }

  // ---------------------------------------------------------------------------
  // padLeftZeros(String, int) — PIC 9(n): right-justify, zero-pad left, high-order truncation
  // ---------------------------------------------------------------------------

  @Test
  void padLeftZeros_string_overload_zero_pads_and_keeps_rightmost() {
    // Shorter than width: zero-pad on the left.
    assertThat(CobolStringUtils.padLeftZeros("42", 5)).isEqualTo("00042").hasSize(5);
    // Longer than width: keep the right-most `width` chars (high-order truncation).
    assertThat(CobolStringUtils.padLeftZeros("123456", 4)).isEqualTo("3456").hasSize(4);
    assertThat(CobolStringUtils.padLeftZeros("7", 3)).isEqualTo("007").hasSize(3);
    // Exactly the width: unchanged.
    assertThat(CobolStringUtils.padLeftZeros("123", 3)).isEqualTo("123").hasSize(3);
  }

  @Test
  void padLeftZeros_string_overload_is_null_and_empty_safe() {
    // A typed local disambiguates the (String, int) overload from (long, int) without a cast.
    String nullValue = null;
    // Empty or null source yields `width` zeros.
    assertThat(CobolStringUtils.padLeftZeros("", 4)).isEqualTo("0000").hasSize(4);
    assertThat(CobolStringUtils.padLeftZeros(nullValue, 4)).isEqualTo("0000").hasSize(4);
    // width <= 0 yields an empty string.
    assertThat(CobolStringUtils.padLeftZeros("42", 0)).isEmpty();
  }

  // ---------------------------------------------------------------------------
  // padLeftZeros(long, int) — unsigned magnitude digits, zero-padded
  // ---------------------------------------------------------------------------

  @Test
  void padLeftZeros_long_overload_is_unsigned() {
    assertThat(CobolStringUtils.padLeftZeros(7L, 3)).isEqualTo("007").hasSize(3);
    assertThat(CobolStringUtils.padLeftZeros(0L, 3)).isEqualTo("000").hasSize(3);
    // Overflow: keep the right-most `width` digits.
    assertThat(CobolStringUtils.padLeftZeros(12345L, 3)).isEqualTo("345").hasSize(3);
    // Negative input renders its unsigned magnitude, mirroring COBOL unsigned PIC 9 storage.
    // Aligned to production: the leading '-' is stripped from Long.toString (not Math.abs), so
    // the magnitude digits are emitted; for -7 that is "7" -> zero-padded to "007".
    assertThat(CobolStringUtils.padLeftZeros(-7L, 3)).isEqualTo("007").hasSize(3);
    // Long.MIN_VALUE is handled safely (Math.abs would overflow): magnitude is
    // "9223372036854775808"; the right-most 3 digits are "808".
    assertThat(CobolStringUtils.padLeftZeros(Long.MIN_VALUE, 3)).isEqualTo("808").hasSize(3);
    // width <= 0 yields an empty string.
    assertThat(CobolStringUtils.padLeftZeros(7L, 0)).isEmpty();
  }

  // ---------------------------------------------------------------------------
  // truncate — left-most `width` chars, no padding
  // ---------------------------------------------------------------------------

  @Test
  void truncate_returns_leftmost_chars() {
    // Longer than width: keep the left-most `width` chars.
    assertThat(CobolStringUtils.truncate("HELLO", 3)).isEqualTo("HEL").hasSize(3);
    // Shorter than width: returned unchanged (no padding).
    assertThat(CobolStringUtils.truncate("HI", 9)).isEqualTo("HI");
    // width <= 0 yields an empty string.
    assertThat(CobolStringUtils.truncate("HELLO", 0)).isEmpty();
    // null yields an empty string.
    assertThat(CobolStringUtils.truncate(null, 3)).isEmpty();
  }

  // ---------------------------------------------------------------------------
  // rtrim — remove trailing spaces only (leading preserved)
  // ---------------------------------------------------------------------------

  @Test
  void rtrim_removes_only_trailing_spaces() {
    assertThat(CobolStringUtils.rtrim("AB   ")).isEqualTo("AB");
    // Leading spaces are preserved.
    assertThat(CobolStringUtils.rtrim("  AB")).isEqualTo("  AB");
    // All spaces collapse to empty.
    assertThat(CobolStringUtils.rtrim("   ")).isEmpty();
    // null yields an empty string.
    assertThat(CobolStringUtils.rtrim(null)).isEmpty();
  }

  // ---------------------------------------------------------------------------
  // trim — strip leading and trailing spaces
  // ---------------------------------------------------------------------------

  @Test
  void trim_strips_both_ends() {
    assertThat(CobolStringUtils.trim("  AB  ")).isEqualTo("AB");
    assertThat(CobolStringUtils.trim("   ")).isEmpty();
    // null yields an empty string.
    assertThat(CobolStringUtils.trim(null)).isEmpty();
  }

  // ---------------------------------------------------------------------------
  // spaces — a run of exactly `width` spaces
  // ---------------------------------------------------------------------------

  @Test
  void spaces_returns_blank_run() {
    assertThat(CobolStringUtils.spaces(4)).isEqualTo("    ").hasSize(4);
    // width <= 0 yields an empty string.
    assertThat(CobolStringUtils.spaces(0)).isEmpty();
    assertThat(CobolStringUtils.spaces(-1)).isEmpty();
  }
}
