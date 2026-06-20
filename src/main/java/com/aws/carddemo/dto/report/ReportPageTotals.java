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

import java.math.BigDecimal;

/**
 * Per-page subtotal line of the Daily Transaction Report (legacy batch program {@code CBTRN03C}).
 *
 * <p>This framework-light value object migrates the COBOL 01-level group {@code REPORT-PAGE-TOTALS}
 * defined in copybook {@code legacy/app/cpy/CVTRA07Y.cpy} (lines 50-54). The original record
 * renders the "Page Total" footer printed at the bottom of every report page:
 *
 * <pre>
 * 01  REPORT-PAGE-TOTALS.
 *     05  FILLER           PIC X(11) VALUE 'Page Total'.
 *     05  FILLER           PIC X(86) VALUE ALL '.'.
 *     05  REPT-PAGE-TOTAL  PIC +ZZZ,ZZZ,ZZZ.ZZ.
 * </pre>
 *
 * <p>The line is composed of three fixed-width regions: a left {@code Page Total} label occupying
 * an {@code X(11)} field, an {@code X(86)} run of {@code '.'} leader characters, and the page-total
 * amount edited with the mask {@code +ZZZ,ZZZ,ZZZ.ZZ} (a leading floating plus sign,
 * zero-suppressed thousands groups, width 15). The constant label width plus the dot-leader count
 * is held at {@code 11 + 86 = 97} so the amount column always begins at column 98 — identical
 * alignment to the Account Total ({@code 13 + 84}) and Grand Total ({@code 11 + 86}) lines of the
 * same report.
 *
 * <p><strong>Decimal fidelity (AAP §0.6.1):</strong> {@link #reptPageTotal} holds the <em>raw</em>,
 * signed monetary total as a {@link java.math.BigDecimal} of scale 2 — never {@code float} or
 * {@code double}. The {@code +ZZZ,ZZZ,ZZZ.ZZ} edit mask is purely a DISPLAY concern and is applied
 * by {@code com.aws.carddemo.util.NumberFormatter.formatTotalAmount(BigDecimal)} at render time;
 * this DTO deliberately stores no preformatted string and performs no formatting itself.
 *
 * <p><strong>Origin:</strong> {@code legacy/app/cpy/CVTRA07Y.cpy} (source-branch {@code
 * app/cpy/CVTRA07Y.cpy}, lines 50-54).
 *
 * <p><strong>Authority:</strong> AAP §0.4.1 ("dto/report/*.java + util formatters" sourced from
 * {@code app/cpy/CVTRA07Y.cpy}) and §0.6.1 (Decimal and Arithmetic Fidelity).
 */
public class ReportPageTotals {

  /**
   * Left-aligned label rendered in the leading {@code X(11)} field. Mirrors the COBOL literal
   * {@code FILLER PIC X(11) VALUE 'Page Total'} (CVTRA07Y.cpy L51-52). Stored raw (no padding); the
   * fixed {@link #W_LABEL} width is applied by the report formatter.
   */
  public static final String LABEL = "Page Total";

  /**
   * Dot-leader run that visually connects the label to the right-aligned amount. Mirrors the COBOL
   * {@code FILLER PIC X(86) VALUE ALL '.'} (CVTRA07Y.cpy L53) — exactly 86 {@code '.'} characters.
   * {@code ".".repeat(86)} guarantees the precise count required by the golden-file line contract.
   */
  public static final String DOT_LEADER = ".".repeat(86);

  /** Fixed display width of the {@link #LABEL} field — COBOL {@code PIC X(11)}. */
  public static final int W_LABEL = 11;

  /** Fixed display width of the {@link #DOT_LEADER} run — COBOL {@code PIC X(86)}. */
  public static final int W_DOT_LEADER = 86;

  /**
   * Edited display width of the page-total amount — COBOL {@code PIC +ZZZ,ZZZ,ZZZ.ZZ} (sign + 9
   * digits + 2 group separators + decimal point + 2 fraction digits = 15 characters).
   */
  public static final int W_AMOUNT = 15;

  /**
   * Raw, signed page-total amount. Migrates COBOL {@code REPT-PAGE-TOTAL PIC +ZZZ,ZZZ,ZZZ.ZZ}
   * (CVTRA07Y.cpy L54). Held as a {@link java.math.BigDecimal} of scale 2 to preserve exact decimal
   * fidelity (AAP §0.6.1); the floating-plus edit mask is applied only at render time by the report
   * number formatter, never stored here.
   */
  private BigDecimal reptPageTotal;

  /** Creates an empty page-total line; {@link #reptPageTotal} is unset ({@code null}) until set. */
  public ReportPageTotals() {
    // No-arg constructor for framework instantiation and incremental field population.
  }

  /**
   * Returns the raw, signed page-total amount (scale 2), or {@code null} if it has not been set.
   *
   * @return the page total as a {@link java.math.BigDecimal}, or {@code null}
   */
  public BigDecimal getReptPageTotal() {
    return reptPageTotal;
  }

  /**
   * Sets the raw, signed page-total amount. Callers should supply a scale-2 {@link
   * java.math.BigDecimal}; the value is stored unmodified — no rounding or display editing is
   * performed here, as formatting is the report number formatter's responsibility.
   *
   * @param reptPageTotal the raw page total to store; may be {@code null}
   */
  public void setReptPageTotal(BigDecimal reptPageTotal) {
    this.reptPageTotal = reptPageTotal;
  }
}
