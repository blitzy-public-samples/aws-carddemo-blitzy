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
 * Pure JUnit&nbsp;5 + AssertJ unit tests for {@link ReportPageTotals}, the Java migration of the
 * COBOL 01-level group {@code REPORT-PAGE-TOTALS} (the per-page subtotal line of the Daily
 * Transaction Report) defined in copybook {@code legacy/app/cpy/CVTRA07Y.cpy} lines&nbsp;50-54
 * (source-branch {@code app/cpy/CVTRA07Y.cpy}).
 *
 * <p>The COBOL ground truth pinned by these tests is:
 *
 * <pre>
 * 01  REPORT-PAGE-TOTALS.
 *     05  FILLER           PIC X(11) VALUE 'Page Total'.
 *     05  FILLER           PIC X(86) VALUE ALL '.'.
 *     05  REPT-PAGE-TOTAL  PIC +ZZZ,ZZZ,ZZZ.ZZ.
 * </pre>
 *
 * <p>Two byte-faithful invariants drive report-alignment parity (Agent Action Plan &sect;0.6.1):
 *
 * <ul>
 *   <li>the {@code LABEL} literal content is exactly {@code "Page Total"} (10 characters) — the
 *       extra byte of the {@code X(11)} field is the trailing pad captured by the {@code W_LABEL}
 *       width constant, never by the literal itself; and
 *   <li>the {@code DOT_LEADER} run is exactly 86 {@code '.'} characters, so the label width plus
 *       the leader ({@code 11 + 86 = 97}) places the amount column at print position&nbsp;98 —
 *       identical to the Account Total and Grand Total lines of the same report.
 * </ul>
 *
 * <p>The page-total amount is held as a {@link java.math.BigDecimal} of scale&nbsp;2 (never {@code
 * float}/{@code double}), preserving the signed {@code PIC +ZZZ,ZZZ,ZZZ.ZZ} decimal fidelity; the
 * edit mask is a display-only concern applied by the report formatter, not by this DTO.
 *
 * <p>The test is intentionally framework-light: no Spring context, database, Testcontainers, or
 * Mockito — just {@code new ReportPageTotals()} / constant access and AssertJ fluent assertions,
 * matching the production class's framework-free design.
 */
class ReportPageTotalsTest {

  // -----------------------------------------------------------------------------------------------
  // 3a. LABEL literal — byte-faithful content (CVTRA07Y.cpy L51-52).
  // -----------------------------------------------------------------------------------------------

  /**
   * The {@code LABEL} constant mirrors the COBOL literal {@code 'Page Total'} verbatim. The literal
   * is 10 characters; the {@code X(11)} field width (one trailing pad space) is a separate concern
   * exposed via {@code W_LABEL}, so the literal must be asserted by value — never by length 11.
   */
  @Test
  void label_matches_copybook_literal() {
    assertThat(ReportPageTotals.LABEL).isEqualTo("Page Total");
  }

  // -----------------------------------------------------------------------------------------------
  // 3b. DOT_LEADER — exactly 86 dots (CVTRA07Y.cpy L53; AAP §0.6.1 byte-faithful fixed width).
  // -----------------------------------------------------------------------------------------------

  /**
   * The dot-leader must be exactly 86 characters long, matching {@code FILLER PIC X(86) VALUE ALL
   * '.'}. This count is the cross-check that the amount column lands at print position&nbsp;98.
   */
  @Test
  void dot_leader_has_length_86() {
    assertThat(ReportPageTotals.DOT_LEADER).hasSize(86);
  }

  /** Every character of the dot-leader is a literal {@code '.'} — exactly 86, no exceptions. */
  @Test
  void dot_leader_is_all_dots() {
    assertThat(ReportPageTotals.DOT_LEADER).isEqualTo(".".repeat(86));
  }

  // -----------------------------------------------------------------------------------------------
  // 3c. reptPageTotal — BigDecimal scale 2, round-trips, and is signed (AAP §0.6.1).
  // -----------------------------------------------------------------------------------------------

  /**
   * A scale-2 value set through {@code setReptPageTotal} round-trips unchanged through {@code
   * getReptPageTotal}, which is statically typed as {@link java.math.BigDecimal} — the compile-time
   * proof that the amount is never stored as {@code float}/{@code double}.
   */
  @Test
  void page_total_is_bigdecimal_scale_2_and_round_trips() {
    ReportPageTotals totals = new ReportPageTotals();
    BigDecimal value = new BigDecimal("1234567.89");
    totals.setReptPageTotal(value);
    BigDecimal stored = totals.getReptPageTotal();
    assertThat(stored).isEqualByComparingTo("1234567.89");
    assertThat(stored.scale()).isEqualTo(2);
  }

  /**
   * The COBOL {@code PIC +ZZZ,ZZZ,ZZZ.ZZ} mask carries a floating sign, so the underlying total is
   * signed; a negative page total must round-trip and report as negative.
   */
  @Test
  void page_total_supports_negative_values() {
    ReportPageTotals totals = new ReportPageTotals();
    totals.setReptPageTotal(new BigDecimal("-50.00"));
    assertThat(totals.getReptPageTotal()).isEqualByComparingTo("-50.00");
    assertThat(totals.getReptPageTotal()).isNegative();
  }

  // -----------------------------------------------------------------------------------------------
  // 3d. Width constants — present in the production source, so pinned to the COBOL picture widths.
  // -----------------------------------------------------------------------------------------------

  /**
   * The exposed width constants must equal the COBOL field widths: {@code W_LABEL} = {@code X(11)},
   * {@code W_DOT_LEADER} = {@code X(86)}, and {@code W_AMOUNT} = 15 (the edited {@code
   * +ZZZ,ZZZ,ZZZ.ZZ} mask: sign + 9 digits + 2 group separators + decimal point + 2 fraction
   * digits). Together they guarantee the amount column begins at print position&nbsp;98.
   */
  @Test
  void width_constants_match_cobol_picture_widths() {
    assertThat(ReportPageTotals.W_LABEL).isEqualTo(11);
    assertThat(ReportPageTotals.W_DOT_LEADER).isEqualTo(86);
    assertThat(ReportPageTotals.W_AMOUNT).isEqualTo(15);
  }
}
