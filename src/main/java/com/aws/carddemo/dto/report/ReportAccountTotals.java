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
 * Per-account total line of the Daily Transaction Report, migrated from the COBOL {@code 01
 * REPORT-ACCOUNT-TOTALS} group item of copybook {@code CVTRA07Y.cpy} (lines 56-60).
 *
 * <p>In the legacy z/OS application this 01-level is the fixed-width print line that batch program
 * {@code CBTRN03C} emits after the detail lines of each account, summing that account's transaction
 * amounts. Its layout is a literal label, a dot leader, and the edited account total:
 *
 * <pre>
 * 01  REPORT-ACCOUNT-TOTALS.
 *     05  FILLER              PIC X(13) VALUE 'Account Total'.
 *     05  FILLER              PIC X(84) VALUE ALL '.'.
 *     05  REPT-ACCOUNT-TOTAL  PIC +ZZZ,ZZZ,ZZZ.ZZ.
 * </pre>
 *
 * <p>In the modernized Spring Boot application this is a plain, framework-light POJO consumed by
 * the batch transaction-report writer. It intentionally has no persistence, web, or domain
 * dependencies, preserving the layering boundary of the {@code com.aws.carddemo.dto.report} package
 * (AAP §0.4.1: one Java type per copybook 01-level).
 *
 * <p>Behavioral parity notes:
 *
 * <ul>
 *   <li><b>Decimal fidelity</b> (AAP §0.6.1): {@link #reptAccountTotal} holds the RAW signed
 *       account total as a {@link BigDecimal} with scale 2 — never {@code float}/{@code double}.
 *       The edited picture {@code +ZZZ,ZZZ,ZZZ.ZZ} (floating leading sign, zero-suppressed, width
 *       {@value #W_AMOUNT}) is a DISPLAY-ONLY mask; the edit is applied at print time by {@code
 *       com.aws.carddemo.util.NumberFormatter}, never within this DTO, which therefore never stores
 *       a preformatted {@code String}.
 *   <li><b>Fixed-width contract</b>: the label occupies {@value #W_LABEL} columns and the dot
 *       leader {@value #W_DOT_LEADER} columns, so the amount column begins at position 98 —
 *       identical to the page-total and grand-total lines (each 11 + 86 = 97), keeping every total
 *       line column-aligned in the report. The label here is two characters longer than {@code Page
 *       Total}/{@code Grand Total}, hence the correspondingly shorter 84-dot leader. These widths
 *       are part of the golden-file line contract and must not change.
 * </ul>
 */
public class ReportAccountTotals {

  // ---------------------------------------------------------------------------------------------
  // Fixed-width field lengths (the COBOL PIC clause widths). These widths are part of the external
  // golden-file line contract: the report's fixed-width columns depend on these exact sizes.
  // ---------------------------------------------------------------------------------------------

  /** Width of the label FILLER ({@code PIC X(13) VALUE 'Account Total'}). */
  public static final int W_LABEL = 13;

  /** Width of the dot-leader FILLER ({@code PIC X(84) VALUE ALL '.'}). */
  public static final int W_DOT_LEADER = 84;

  /** Width of the edited amount {@code REPT-ACCOUNT-TOTAL} ({@code PIC +ZZZ,ZZZ,ZZZ.ZZ}). */
  public static final int W_AMOUNT = 15;

  // ---------------------------------------------------------------------------------------------
  // Constant text segments (the COBOL FILLER VALUEs). These are identical on every account-total
  // line; only the trailing amount varies.
  // ---------------------------------------------------------------------------------------------

  /**
   * Literal label of the line — the {@code FILLER PIC X(13) VALUE 'Account Total'}. Exactly {@value
   * #W_LABEL} characters, so it fills the {@code X(13)} field with no padding.
   */
  public static final String LABEL = "Account Total";

  /**
   * Dot leader between the label and the amount — the {@code FILLER PIC X(84) VALUE ALL '.'}. Built
   * with {@link String#repeat(int)} from {@link #W_DOT_LEADER} to guarantee exactly {@value
   * #W_DOT_LEADER} {@code '.'} characters.
   */
  public static final String DOT_LEADER = ".".repeat(W_DOT_LEADER);

  // ---------------------------------------------------------------------------------------------
  // Variable field — the per-account total amount.
  // ---------------------------------------------------------------------------------------------

  /**
   * {@code REPT-ACCOUNT-TOTAL PIC +ZZZ,ZZZ,ZZZ.ZZ} — the RAW signed account total. Held as a {@link
   * BigDecimal} with scale 2 per AAP §0.6.1 (never {@code float}/{@code double}). The {@code
   * +ZZZ,ZZZ,ZZZ.ZZ} edit is a presentation concern applied at print time by {@code
   * com.aws.carddemo.util.NumberFormatter}; this DTO stores only the raw numeric value.
   */
  private BigDecimal reptAccountTotal;

  /** Creates an empty account-total line; {@link #reptAccountTotal} is {@code null} until set. */
  public ReportAccountTotals() {
    // No-args constructor for framework instantiation and builder/mapper population.
  }

  /**
   * Returns the raw signed account total as a scale-2 {@link BigDecimal} ({@code
   * REPT-ACCOUNT-TOTAL}).
   *
   * @return the raw account total, or {@code null} if it has not been set
   */
  public BigDecimal getReptAccountTotal() {
    return reptAccountTotal;
  }

  /**
   * Sets the raw signed account total. Callers supply a scale-2 {@link BigDecimal}; the value is
   * stored as-is (no rounding, scaling, or reformatting) so the raw total is preserved for the
   * display-time formatter that applies the {@code +ZZZ,ZZZ,ZZZ.ZZ} edit.
   *
   * @param reptAccountTotal the raw account total ({@code REPT-ACCOUNT-TOTAL}); may be {@code null}
   */
  public void setReptAccountTotal(BigDecimal reptAccountTotal) {
    this.reptAccountTotal = reptAccountTotal;
  }
}
