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

/**
 * Report title / date-range header for the Daily Transaction Report, migrated from the COBOL {@code
 * 01 REPORT-NAME-HEADER} group item of copybook {@code CVTRA07Y.cpy} (lines 4-13).
 *
 * <p>In the legacy z/OS application this structure is the printed header produced by batch program
 * {@code CBTRN03C}. It is composed of three fixed title constants, a fixed {@code FILLER} separator
 * literal, and two variable date fields that are populated at run time from the report's date-range
 * parameter:
 *
 * <pre>
 * 01  REPORT-NAME-HEADER.
 *     05  REPT-SHORT-NAME   PIC X(38) VALUE 'DALYREPT'.
 *     05  REPT-LONG-NAME    PIC X(41) VALUE 'Daily Transaction Report'.
 *     05  REPT-DATE-HEADER  PIC X(12) VALUE 'Date Range: '.
 *     05  REPT-START-DATE   PIC X(10) VALUE SPACES.
 *     05  FILLER            PIC X(04) VALUE ' to '.
 *     05  REPT-END-DATE     PIC X(10) VALUE SPACES.
 * </pre>
 *
 * <p>Design notes (Agent Action Plan §0.4.1, §0.6.1):
 *
 * <ul>
 *   <li><b>One Java type per copybook 01-level</b> (§0.4.1): this POJO models exactly the {@code
 *       REPORT-NAME-HEADER} group; the other 01-levels of {@code CVTRA07Y.cpy} map to their own
 *       types.
 *   <li><b>Raw (unpadded) constant literals plus width constants</b>: each fixed field is exposed
 *       as its exact COBOL {@code VALUE} text (for example {@code "DALYREPT"} is 8 characters, not
 *       pre-padded to its {@code PIC X(38)} width). The {@code W_*} width constants carry the
 *       fixed-field widths so the report formatter in {@code com.aws.carddemo.util} can space-pad
 *       each field to its exact column width and reproduce the mainframe report line byte-for-byte
 *       (golden-file parity, §0.6.1). There is no numeric/decimal data in this header, so no {@code
 *       BigDecimal} is involved.
 *   <li><b>Variable date fields default to {@code SPACES}</b>: {@link #getReptStartDate()} and
 *       {@link #getReptEndDate()} default to ten blanks, mirroring the COBOL {@code VALUE SPACES}
 *       initialization, and are overwritten with 10-character formatted dates at run time.
 * </ul>
 *
 * <p>This is a framework-light DTO: it intentionally has no JPA, web, validation, or domain
 * dependencies, preserving the layering boundary of the {@code com.aws.carddemo.dto} package.
 */
public class ReportNameHeader {

  // -----------------------------------------------------------------------------------------------
  // Fixed-width field lengths (the COBOL PIC clause widths). These widths are part of the external
  // report-line contract: the formatter pads each field to its exact column width for golden-file
  // parity, so the values must equal the copybook PIC sizes exactly.
  // -----------------------------------------------------------------------------------------------

  /** Width of {@code REPT-SHORT-NAME} ({@code PIC X(38)}). */
  public static final int W_SHORT_NAME = 38;

  /** Width of {@code REPT-LONG-NAME} ({@code PIC X(41)}). */
  public static final int W_LONG_NAME = 41;

  /** Width of {@code REPT-DATE-HEADER} ({@code PIC X(12)}). */
  public static final int W_DATE_HEADER = 12;

  /** Width of {@code REPT-START-DATE} and {@code REPT-END-DATE} ({@code PIC X(10)} each). */
  public static final int W_DATE = 10;

  // -----------------------------------------------------------------------------------------------
  // Fixed title constants — the three COBOL title fields and the FILLER separator that always carry
  // their declared VALUE. Stored as the raw (unpadded) COBOL VALUE text; the W_* widths above give
  // the column widths the report formatter uses to space-pad each field.
  // -----------------------------------------------------------------------------------------------

  /**
   * {@code REPT-SHORT-NAME PIC X(38) VALUE 'DALYREPT'} — the report's short (internal) name. The
   * raw literal is 8 characters; the field width is {@value #W_SHORT_NAME}.
   */
  public static final String REPT_SHORT_NAME = "DALYREPT";

  /**
   * {@code REPT-LONG-NAME PIC X(41) VALUE 'Daily Transaction Report'} — the report's printed title.
   * The raw literal is 24 characters; the field width is {@value #W_LONG_NAME}.
   */
  public static final String REPT_LONG_NAME = "Daily Transaction Report";

  /**
   * {@code REPT-DATE-HEADER PIC X(12) VALUE 'Date Range: '} — the date-range label. The literal is
   * exactly 12 characters including its single trailing space, matching the {@value #W_DATE_HEADER}
   * field width byte-for-byte.
   */
  public static final String REPT_DATE_HEADER = "Date Range: ";

  /**
   * {@code FILLER PIC X(04) VALUE ' to '} — the constant separator placed between the start and end
   * dates. Exactly 4 characters (a leading and a trailing space), matching the FILLER width.
   */
  public static final String DATE_SEPARATOR = " to ";

  // -----------------------------------------------------------------------------------------------
  // Variable date fields — the only members that change at run time. Populated from the report's
  // date-range parameter with 10-character formatted dates; default to SPACES (VALUE SPACES).
  // -----------------------------------------------------------------------------------------------

  /**
   * {@code REPT-START-DATE PIC X(10) VALUE SPACES} — the start of the report's date range. Defaults
   * to {@value #W_DATE} blanks to mirror the COBOL {@code VALUE SPACES} initialization; set to a
   * formatted 10-character date string at run time.
   */
  private String reptStartDate = " ".repeat(W_DATE);

  /**
   * {@code REPT-END-DATE PIC X(10) VALUE SPACES} — the end of the report's date range. Defaults to
   * {@value #W_DATE} blanks to mirror the COBOL {@code VALUE SPACES} initialization; set to a
   * formatted 10-character date string at run time.
   */
  private String reptEndDate = " ".repeat(W_DATE);

  /**
   * Creates a header with both date fields defaulted to {@value #W_DATE} blanks (COBOL {@code VALUE
   * SPACES}).
   */
  public ReportNameHeader() {
    // No-arg constructor; date fields retain their SPACES defaults until populated.
  }

  /**
   * @return {@code REPT-START-DATE} (up to {@value #W_DATE} characters); defaults to {@value
   *     #W_DATE} blanks before it is populated.
   */
  public String getReptStartDate() {
    return reptStartDate;
  }

  /**
   * Sets {@code REPT-START-DATE} ({@code PIC X(10)}) — the start of the report's date range.
   *
   * @param reptStartDate the formatted start date (up to {@value #W_DATE} characters)
   */
  public void setReptStartDate(String reptStartDate) {
    this.reptStartDate = reptStartDate;
  }

  /**
   * @return {@code REPT-END-DATE} (up to {@value #W_DATE} characters); defaults to {@value #W_DATE}
   *     blanks before it is populated.
   */
  public String getReptEndDate() {
    return reptEndDate;
  }

  /**
   * Sets {@code REPT-END-DATE} ({@code PIC X(10)}) — the end of the report's date range.
   *
   * @param reptEndDate the formatted end date (up to {@value #W_DATE} characters)
   */
  public void setReptEndDate(String reptEndDate) {
    this.reptEndDate = reptEndDate;
  }

  @Override
  public String toString() {
    return "ReportNameHeader{reptStartDate='"
        + reptStartDate
        + "', reptEndDate='"
        + reptEndDate
        + "'}";
  }
}
