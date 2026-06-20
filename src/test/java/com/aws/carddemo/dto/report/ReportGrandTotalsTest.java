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
 * Pure JUnit&nbsp;5 + AssertJ unit tests for {@link ReportGrandTotals}, the Java migration of the
 * COBOL {@code 01 REPORT-GRAND-TOTALS} group item from copybook {@code CVTRA07Y.cpy} (legacy source
 * {@code legacy/app/cpy/CVTRA07Y.cpy} lines 62-66). This is the report-wide grand-total line of the
 * <em>Daily Transaction Report</em> produced by batch program {@code CBTRN03C}.
 *
 * <p>The fixed COBOL layout these tests pin is:
 *
 * <pre>
 * 01  REPORT-GRAND-TOTALS.
 *     05  FILLER           PIC X(11) VALUE 'Grand Total'.   &lt;- 11-char label, exact fit
 *     05  FILLER           PIC X(86) VALUE ALL '.'.         &lt;- 86-dot leader
 *     05  REPT-GRAND-TOTAL PIC +ZZZ,ZZZ,ZZZ.ZZ.            &lt;- raw S9(9)V99, signed, scale 2
 * </pre>
 *
 * <p>These invariants are part of the golden-file print-line contract (Agent Action Plan
 * &sect;0.6.1): the {@code "Grand Total"} label occupies exactly 11 columns and the dot leader is
 * exactly <strong>86</strong> dots, so {@code 11 + 86 + 1 = 98} places the edited amount column at
 * print position 98 — the identical alignment used by the page-total line ({@code 11 + 86}). The
 * account-total line, by contrast, uses an 84-dot leader; locking the 86-dot value here guards
 * against silently swapping the two leader widths and shifting the rendered amount column.
 *
 * <p>The grand-total amount is held as a {@link BigDecimal} with scale 2 — never {@code float} or
 * {@code double}, which are prohibited for decimal data (&sect;0.6.1). The {@code +ZZZ,ZZZ,ZZZ.ZZ}
 * edit mask is display-only and is applied by the report writer at render time, so this DTO stores
 * the raw signed value and round-trips it through the setter/getter unchanged.
 *
 * <p>This is intentionally a framework-light test: the POJO is constructed directly with {@code
 * new} and every assertion uses AssertJ only. There is no Spring context, database, Testcontainers,
 * or Mockito.
 */
class ReportGrandTotalsTest {

  // --- 3a. LABEL literal (FILLER PIC X(11) VALUE 'Grand Total') ---

  /**
   * The label constant must equal the COBOL {@code VALUE 'Grand Total'} literal byte-for-byte and
   * occupy exactly the 11-character field width (an exact fit with no padding).
   */
  @Test
  void label_matches_copybook_literal() {
    assertThat(ReportGrandTotals.LABEL).isEqualTo("Grand Total");
    assertThat(ReportGrandTotals.LABEL).hasSize(11);
  }

  // --- 3b. DOT_LEADER is exactly 86 dots (FILLER PIC X(86) VALUE ALL '.') ---

  /** The dot leader must be exactly 86 characters wide (COBOL {@code PIC X(86)}). */
  @Test
  void dot_leader_has_length_86() {
    assertThat(ReportGrandTotals.DOT_LEADER).hasSize(86);
  }

  /** The dot leader must consist of exactly 86 dot characters ({@code VALUE ALL '.'}). */
  @Test
  void dot_leader_is_all_dots() {
    assertThat(ReportGrandTotals.DOT_LEADER).isEqualTo(".".repeat(86));
  }

  // --- 3c. reptGrandTotal is BigDecimal scale 2, round-trips (incl. signed) ---

  /**
   * The grand-total amount round-trips through the setter/getter as a scale-2 {@link BigDecimal}.
   * The widest 9-digit value {@code 99999999.99} exercises the full {@code S9(9)V99} capacity, and
   * the declared {@code BigDecimal} return type is compile-time proof that the value is not stored
   * as a lossy {@code float}/{@code double}.
   */
  @Test
  void grand_total_is_bigdecimal_scale_2_and_round_trips() {
    ReportGrandTotals totals = new ReportGrandTotals();
    BigDecimal value = new BigDecimal("99999999.99");

    totals.setReptGrandTotal(value);

    BigDecimal stored = totals.getReptGrandTotal();
    assertThat(stored).isEqualByComparingTo("99999999.99");
    assertThat(stored.scale()).isEqualTo(2);
  }

  /**
   * The amount is a <em>signed</em> {@code S9(9)V99} field, so negative totals must round-trip
   * faithfully and report as negative.
   */
  @Test
  void grand_total_supports_negative_values() {
    ReportGrandTotals totals = new ReportGrandTotals();

    totals.setReptGrandTotal(new BigDecimal("-12345.67"));

    assertThat(totals.getReptGrandTotal()).isEqualByComparingTo("-12345.67");
    assertThat(totals.getReptGrandTotal()).isNegative();
  }

  // --- 3d. Width constants (present in production source) ---

  /**
   * The exposed field-width constants must equal the COBOL picture-clause widths that drive the
   * golden-file column alignment: label {@code PIC X(11)} and dot leader {@code PIC X(86)}.
   */
  @Test
  void width_constants_match() {
    assertThat(ReportGrandTotals.W_LABEL).isEqualTo(11);
    assertThat(ReportGrandTotals.W_DOT_LEADER).isEqualTo(86);
  }
}
