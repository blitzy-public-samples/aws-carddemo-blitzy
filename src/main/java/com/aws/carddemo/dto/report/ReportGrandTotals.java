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
 * Report grand-total line, migrated from the COBOL {@code 01 REPORT-GRAND-TOTALS} group item of
 * copybook {@code CVTRA07Y.cpy} (lines 62-66).
 *
 * <p>In the legacy z/OS application this structure renders the final, report-wide grand-total line
 * of the <em>Daily Transaction Report</em> produced by batch program {@code CBTRN03C}. The fixed
 * layout is a literal {@code "Grand Total"} label in an {@code X(11)} field, followed by exactly 86
 * dot ({@code '.'}) leader characters ({@code VALUE ALL '.'}), followed by the grand-total amount
 * edited with the numeric mask {@code PIC +ZZZ,ZZZ,ZZZ.ZZ} (leading floating sign, zero-suppressed,
 * display width 15). Label ({@value #W_LABEL}) + leader ({@value #W_DOT_LEADER}) = 97, so the
 * amount column begins at print position 98 — the identical alignment used by the page-total
 * ({@code 11 + 86}) and account-total ({@code 13 + 84}) lines of the same report.
 *
 * <p>This is a plain, framework-light POJO consumed by the batch report-writer layer. It is
 * <strong>not</strong> a JPA entity and intentionally has no persistence, web, or domain
 * dependencies, preserving the layering boundary of the {@code com.aws.carddemo.dto.report} package
 * (AAP §0.4.1 — one Java type per copybook {@code 01}-level).
 *
 * <p>Behavioral parity notes:
 *
 * <ul>
 *   <li><b>Decimal fidelity</b> (AAP §0.6.1): {@link #getReptGrandTotal() reptGrandTotal} holds the
 *       <em>raw</em>, signed total as a {@link java.math.BigDecimal} with scale 2 — never {@code
 *       float} or {@code double}, which are prohibited for decimal data. The {@code PIC
 *       +ZZZ,ZZZ,ZZZ.ZZ} edit is display-only and is applied by {@code
 *       com.aws.carddemo.util.NumberFormatter.formatTotalAmount(BigDecimal)} when the report line
 *       is rendered, so output stays byte-faithful and decimal-exact. This DTO never formats the
 *       value and never stores a preformatted {@code String}.
 *   <li><b>Fixed-width contract</b>: the label width ({@value #W_LABEL}), leader width ({@value
 *       #W_DOT_LEADER}), and edited-amount width ({@value #W_AMOUNT}) are part of the golden-file
 *       line contract and are exposed as constants so the report writer can reproduce the exact
 *       column alignment of the legacy 133-column print line.
 * </ul>
 */
public class ReportGrandTotals {

  // -----------------------------------------------------------------------------------------------
  // Fixed-width / literal contract constants (the COBOL FILLER values and PIC clause widths). These
  // widths are part of the external golden-file contract: the report print line depends on the
  // exact field sizes and the literal label / dot-leader content.
  // -----------------------------------------------------------------------------------------------

  /**
   * Literal label text of the grand-total line — the {@code FILLER PIC X(11) VALUE 'Grand Total'}.
   * Stored raw (the COBOL value occupies the full {@value #W_LABEL}-character field; any padding to
   * width is applied by the report writer, not here).
   */
  public static final String LABEL = "Grand Total";

  /** Width of the label {@code FILLER} field ({@code PIC X(11)}). */
  public static final int W_LABEL = 11;

  /**
   * Dot leader — the {@code FILLER PIC X(86) VALUE ALL '.'}. Built with {@code ".".repeat(86)} so
   * it contains exactly {@value #W_DOT_LEADER} {@code '.'} characters, matching the copybook layout
   * byte-for-byte.
   */
  public static final String DOT_LEADER = ".".repeat(86);

  /** Width of the dot-leader {@code FILLER} field ({@code PIC X(86)}). */
  public static final int W_DOT_LEADER = 86;

  /**
   * Display width of the edited grand-total amount ({@code PIC +ZZZ,ZZZ,ZZZ.ZZ}). The mask expands
   * to {@code +} (1) + {@code ZZZ} (3) + {@code ,} (1) + {@code ZZZ} (3) + {@code ,} (1) + {@code
   * ZZZ} (3) + {@code .} (1) + {@code ZZ} (2) = {@value #W_AMOUNT} characters.
   */
  public static final int W_AMOUNT = 15;

  // -----------------------------------------------------------------------------------------------
  // Variable field — the only data-bearing item of the COBOL group.
  // -----------------------------------------------------------------------------------------------

  /**
   * Raw, signed grand-total amount — the {@code REPT-GRAND-TOTAL PIC +ZZZ,ZZZ,ZZZ.ZZ} item. Held as
   * a {@link BigDecimal} with scale 2 (the two implied decimal places of the edit mask). The value
   * is stored unformatted; the {@code +ZZZ,ZZZ,ZZZ.ZZ} edit is applied only at render time so that
   * arithmetic remains decimal-exact (AAP §0.6.1).
   */
  private BigDecimal reptGrandTotal;

  // -----------------------------------------------------------------------------------------------
  // Constructor.
  // -----------------------------------------------------------------------------------------------

  /** Creates an empty grand-total line; the amount is populated by the report writer via setter. */
  public ReportGrandTotals() {}

  // -----------------------------------------------------------------------------------------------
  // Accessors.
  // -----------------------------------------------------------------------------------------------

  /**
   * Returns the raw, signed grand-total amount ({@code REPT-GRAND-TOTAL}).
   *
   * @return the grand total as a scale-2 {@link BigDecimal}, or {@code null} if not yet set
   */
  public BigDecimal getReptGrandTotal() {
    return reptGrandTotal;
  }

  /**
   * Sets the raw, signed grand-total amount ({@code REPT-GRAND-TOTAL}). The caller supplies the
   * unformatted value; rendering with the {@code PIC +ZZZ,ZZZ,ZZZ.ZZ} edit is performed elsewhere.
   *
   * @param reptGrandTotal the grand total as a {@link BigDecimal} (scale 2); may be {@code null}
   */
  public void setReptGrandTotal(BigDecimal reptGrandTotal) {
    this.reptGrandTotal = reptGrandTotal;
  }
}
