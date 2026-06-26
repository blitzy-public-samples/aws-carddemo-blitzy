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
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

/**
 * Pure unit tests for {@link LookupCodes} — the immutable validation lookup tables transcribed from
 * the legacy COBOL copybook {@code CSLKPCDY.cpy} ({@code 88}-level {@code VALUE} lists).
 *
 * <p>These tables gate phone-area-code, US-state-code, and state/ZIP-prefix field validation in the
 * online programs (e.g. COACTUPC). The headline parity invariant is the EXACT cardinality of each
 * set: {@code 490 / 410 / 80 / 56 / 240}. A wrong size means a code was dropped or duplicated
 * during the COBOL→Java translation, which would silently change which inputs are accepted versus
 * rejected — a behavioral regression. These counts were verified two ways: by counting the {@code
 * 'xxx'} literals in {@code CSLKPCDY.cpy} and by counting the entries in {@link LookupCodes}
 * itself.
 *
 * <p>The suite is deliberately Spring-free and database-free: it exercises only the static tables
 * and the null-safe, exact-match helper methods. It covers, for every table:
 *
 * <ul>
 *   <li>exact set cardinality (the non-negotiable invariant);
 *   <li>representative and boundary membership (the first/last copybook entries);
 *   <li>non-membership, including the exact-match discipline (no trimming, padding, or case
 *       folding) that mirrors the fixed-width COBOL {@code 88}-level comparison;
 *   <li>null-safety of the {@code isValid*} helpers; and
 *   <li>immutability of every exposed set.
 * </ul>
 */
class LookupCodesTest {

  /**
   * Asserts the exact cardinality of all five lookup tables. These sizes are the authoritative
   * parity invariant taken directly from {@code CSLKPCDY.cpy}; they must never be relaxed.
   */
  @Test
  void all_lookup_sets_have_exact_cobol_cardinalities() {
    assertThat(LookupCodes.VALID_PHONE_AREA_CODES).hasSize(490);
    assertThat(LookupCodes.VALID_GENERAL_PURPOSE_AREA_CODES).hasSize(410);
    assertThat(LookupCodes.VALID_EASY_RECOGNIZABLE_AREA_CODES).hasSize(80);
    assertThat(LookupCodes.VALID_US_STATE_CODES).hasSize(56);
    assertThat(LookupCodes.VALID_US_STATE_ZIP2_COMBOS).hasSize(240);
  }

  /**
   * Verifies membership for the three area-code families. Beyond the first/last entries of the full
   * phone table, this also confirms the two sub-tables are distinct: an easy-recognizable-only code
   * ({@code "200"}) is absent from the general-purpose table, and a general-purpose code ({@code
   * "201"}) is absent from the easy-recognizable table. Non-membership cases enforce the
   * exact-width, exact-match contract (no short, long, or non-numeric value matches).
   */
  @Test
  void phone_area_codes_contain_known_members_and_exclude_unknowns() {
    // Full phone table: first ("201") and last ("999") copybook entries.
    assertThat(LookupCodes.VALID_PHONE_AREA_CODES).contains("201", "999");
    // Exact-match discipline: wrong length or non-numeric values are never members.
    assertThat(LookupCodes.VALID_PHONE_AREA_CODES).doesNotContain("000", "abc", "20", "2011");

    // General-purpose (geographic) table: first ("201") and last ("989") copybook entries; it must
    // NOT contain the easy-recognizable code "200".
    assertThat(LookupCodes.VALID_GENERAL_PURPOSE_AREA_CODES).contains("201", "989");
    assertThat(LookupCodes.VALID_GENERAL_PURPOSE_AREA_CODES).doesNotContain("200");

    // Easy-recognizable table: first ("200") and last ("999") copybook entries; it must NOT contain
    // the general-purpose code "201".
    assertThat(LookupCodes.VALID_EASY_RECOGNIZABLE_AREA_CODES).contains("200", "999");
    assertThat(LookupCodes.VALID_EASY_RECOGNIZABLE_AREA_CODES).doesNotContain("201");
  }

  /**
   * Verifies boundary membership for the state and state/ZIP tables and enforces case-sensitivity.
   * The state table holds the first ("AL") and last ("VI") copybook entries; the state/ZIP table
   * holds the first ("AA34") and last ("WY83") entries. Lowercase {@code "al"} must be absent,
   * proving the comparison is case-sensitive exactly like the COBOL fixed-width field.
   */
  @Test
  void state_and_state_zip_sets_contain_boundary_members() {
    assertThat(LookupCodes.VALID_US_STATE_CODES).contains("AL", "VI");
    assertThat(LookupCodes.VALID_US_STATE_CODES).doesNotContain("ZZ", "al");

    assertThat(LookupCodes.VALID_US_STATE_ZIP2_COMBOS).contains("AA34", "WY83");
    assertThat(LookupCodes.VALID_US_STATE_ZIP2_COMBOS).doesNotContain("ZZ99");
  }

  /**
   * Exercises every {@code isValid*} helper for true, false, and {@code null} arguments, and proves
   * the helpers are exact-match: a leading space ({@code " 201"}) or a case difference ({@code
   * "al"}) is rejected because the COBOL {@code 88}-level check compares the fixed-width field for
   * exact equality without normalization.
   */
  @Test
  void isValid_helpers_are_exact_match_and_null_safe() {
    // Phone area code.
    assertThat(LookupCodes.isValidPhoneAreaCode("201")).isTrue();
    assertThat(LookupCodes.isValidPhoneAreaCode("000")).isFalse();
    assertThat(LookupCodes.isValidPhoneAreaCode(null)).isFalse();
    assertThat(LookupCodes.isValidPhoneAreaCode(" 201")).isFalse();

    // General-purpose and easy-recognizable area codes (membership + null-safety).
    assertThat(LookupCodes.isValidGeneralPurposeAreaCode("201")).isTrue();
    assertThat(LookupCodes.isValidGeneralPurposeAreaCode("200")).isFalse();
    assertThat(LookupCodes.isValidGeneralPurposeAreaCode(null)).isFalse();
    assertThat(LookupCodes.isValidEasyRecognizableAreaCode("200")).isTrue();
    assertThat(LookupCodes.isValidEasyRecognizableAreaCode("201")).isFalse();
    assertThat(LookupCodes.isValidEasyRecognizableAreaCode(null)).isFalse();

    // US state code (case-sensitive, null-safe).
    assertThat(LookupCodes.isValidUsStateCode("AL")).isTrue();
    assertThat(LookupCodes.isValidUsStateCode("ZZ")).isFalse();
    assertThat(LookupCodes.isValidUsStateCode("al")).isFalse();
    assertThat(LookupCodes.isValidUsStateCode(null)).isFalse();

    // State/ZIP-prefix combo (null-safe).
    assertThat(LookupCodes.isValidStateZip2Combo("AA34")).isTrue();
    assertThat(LookupCodes.isValidStateZip2Combo("ZZ99")).isFalse();
    assertThat(LookupCodes.isValidStateZip2Combo(null)).isFalse();
  }

  /**
   * Confirms every exposed lookup set is unmodifiable, so a caller cannot corrupt the shared
   * validation tables at runtime. Mutating any of them throws {@link
   * UnsupportedOperationException}.
   */
  @Test
  void lookup_sets_are_immutable() {
    assertThatThrownBy(() -> LookupCodes.VALID_PHONE_AREA_CODES.add("xxx"))
        .isInstanceOf(UnsupportedOperationException.class);
    assertThatThrownBy(() -> LookupCodes.VALID_GENERAL_PURPOSE_AREA_CODES.add("xxx"))
        .isInstanceOf(UnsupportedOperationException.class);
    assertThatThrownBy(() -> LookupCodes.VALID_EASY_RECOGNIZABLE_AREA_CODES.add("xxx"))
        .isInstanceOf(UnsupportedOperationException.class);
    assertThatThrownBy(() -> LookupCodes.VALID_US_STATE_CODES.add("xxx"))
        .isInstanceOf(UnsupportedOperationException.class);
    assertThatThrownBy(() -> LookupCodes.VALID_US_STATE_ZIP2_COMBOS.add("xxx"))
        .isInstanceOf(UnsupportedOperationException.class);
  }
}
