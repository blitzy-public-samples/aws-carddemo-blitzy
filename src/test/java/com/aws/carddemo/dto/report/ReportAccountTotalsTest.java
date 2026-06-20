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
package com.aws.carddemo.dto.report;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

/**
 * Pure unit tests for {@link ReportAccountTotals}, the Java migration of the COBOL {@code 01
 * REPORT-ACCOUNT-TOTALS} group item of copybook {@code legacy/app/cpy/CVTRA07Y.cpy} (lines 56-60) —
 * the per-account subtotal line of the Daily Transaction Report.
 *
 * <p>These tests pin the byte-faithful, fixed-width parity invariants that the golden-file report
 * contract depends on (AAP &sect;0.6.1):
 *
 * <ul>
 *   <li>the {@link ReportAccountTotals#LABEL} literal is exactly {@code "Account Total"} — 13
 *       characters, an exact fit for the {@code FILLER PIC X(13)} field (no padding);
 *   <li>the {@link ReportAccountTotals#DOT_LEADER} is exactly <b>84</b> {@code '.'} characters
 *       ({@code FILLER PIC X(84) VALUE ALL '.'}). The 84-dot leader is the critical discriminator
 *       from the page-total and grand-total lines (which use an 86-dot leader); the two-character
 *       longer label compensates so the amount column still lands at print position 98 across all
 *       three total lines; and
 *   <li>{@link ReportAccountTotals#getReptAccountTotal()} round-trips a scale-2 {@link BigDecimal}
 *       — never {@code float}/{@code double} — including signed (negative) values, preserving the
 *       raw {@code REPT-ACCOUNT-TOTAL PIC +ZZZ,ZZZ,ZZZ.ZZ} amount for the display-time formatter.
 * </ul>
 *
 * <p>The suite is intentionally framework-free: no Spring context, database, Testcontainers, or
 * Mockito — just {@code new ReportAccountTotals()} / static-constant access and AssertJ fluent
 * assertions, matching the production DTO's dependency-light design.
 */
class ReportAccountTotalsTest {

  // -----------------------------------------------------------------------------------------------
  // 3a. LABEL literal — FILLER PIC X(13) VALUE 'Account Total' (CVTRA07Y.cpy L57-58).
  // -----------------------------------------------------------------------------------------------

  @Test
  void label_matches_copybook_literal() {
    // 'Account Total' is exactly 13 characters, an exact fit for the X(13) field (no padding).
    assertThat(ReportAccountTotals.LABEL).isEqualTo("Account Total").hasSize(13);
  }

  // -----------------------------------------------------------------------------------------------
  // 3b. DOT_LEADER — FILLER PIC X(84) VALUE ALL '.' (CVTRA07Y.cpy L59). The 84-dot length is the
  // byte-faithful discriminator versus the 86-dot page/grand-total leaders (AAP §0.6.1).
  // -----------------------------------------------------------------------------------------------

  @Test
  void dot_leader_has_length_84() {
    assertThat(ReportAccountTotals.DOT_LEADER).hasSize(84);
  }

  @Test
  void dot_leader_is_all_dots() {
    assertThat(ReportAccountTotals.DOT_LEADER).isEqualTo(".".repeat(84));
  }

  // -----------------------------------------------------------------------------------------------
  // 3c. REPT-ACCOUNT-TOTAL — raw signed amount held as BigDecimal scale 2 (AAP §0.6.1); the
  // +ZZZ,ZZZ,ZZZ.ZZ picture is a display-only mask applied at print time, never stored here.
  // -----------------------------------------------------------------------------------------------

  @Test
  void account_total_is_bigdecimal_scale_2_and_round_trips() {
    ReportAccountTotals totals = new ReportAccountTotals();
    BigDecimal value = new BigDecimal("9876543.21");
    totals.setReptAccountTotal(value);

    // Compile-time proof that the property type is BigDecimal, not float/double.
    BigDecimal stored = totals.getReptAccountTotal();
    assertThat(stored).isEqualByComparingTo("9876543.21");
    assertThat(stored.scale()).isEqualTo(2);
  }

  @Test
  void account_total_supports_negative_values() {
    ReportAccountTotals totals = new ReportAccountTotals();
    totals.setReptAccountTotal(new BigDecimal("-1.50"));

    assertThat(totals.getReptAccountTotal()).isEqualByComparingTo("-1.50");
    assertThat(totals.getReptAccountTotal()).isNegative();
  }

  // -----------------------------------------------------------------------------------------------
  // 3d. Fixed-width constants — W_LABEL (13) and W_DOT_LEADER (84) are part of the golden-file line
  // contract and are both exposed by the production class, so pin them here.
  // -----------------------------------------------------------------------------------------------

  @Test
  void width_constants_match_copybook_widths() {
    assertThat(ReportAccountTotals.W_LABEL).isEqualTo(13);
    assertThat(ReportAccountTotals.W_DOT_LEADER).isEqualTo(84);
  }
}
