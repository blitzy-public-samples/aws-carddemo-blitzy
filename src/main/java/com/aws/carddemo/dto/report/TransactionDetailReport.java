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
 * Detail-line data-transfer object (DTO) for the CardDemo <strong>Daily Transaction
 * Report</strong>.
 *
 * <p>This POJO migrates the COBOL 01-level record {@code TRANSACTION-DETAIL-REPORT} defined in
 * {@code legacy/app/cpy/CVTRA07Y.cpy} (lines 15-31) into the modernized Spring Boot application,
 * following the AAP rule of one Java type per copybook 01-level (AAP &sect;0.4.1). It carries
 * exactly ONE detail row of the report. In the legacy system the record is populated and written by
 * batch program {@code CBTRN03C}, paragraph {@code 1120-WRITE-DETAIL}, which performs an {@code
 * INITIALIZE} and then a field-by-field {@code MOVE} from the joined transaction, cross-reference,
 * transaction-type and transaction-category records.
 *
 * <p>It is deliberately a presentation / transfer object only: it is <em>not</em> a JPA entity and
 * holds no persistence, domain, or business behaviour. The entity-to-DTO assembly (reading the
 * domain {@code Transaction} and its related reference rows) is performed by the report service
 * layer, preserving the package layering boundary of {@code com.aws.carddemo.dto}.
 *
 * <p><strong>Decimal fidelity (AAP &sect;0.6.1).</strong> {@link #tranReportAmt} stores the RAW
 * signed amount as a {@link java.math.BigDecimal} at scale 2 &mdash; never {@code float} or {@code
 * double}. The COBOL edited picture {@code -ZZZ,ZZZ,ZZZ.ZZ} is a DISPLAY-ONLY mask applied at print
 * time by {@code com.aws.carddemo.util.NumberFormatter.formatReportAmount(BigDecimal)}; this DTO
 * never stores a pre-formatted string, so COBOL truncation/rounding parity is preserved by the
 * single, central formatter rather than scattered across callers.
 *
 * <p><strong>Line contract (golden-file parity, AAP &sect;0.6.7).</strong> The legacy record
 * interleaves the eight data fields with {@code FILLER} items: single spaces, a four-space gap
 * before the amount, a two-space trailing gap, and two literal {@code '-'} separators (one between
 * the type code and its description, one between the category code and its description). These
 * separators are part of the exact, byte-faithful report line and MUST be reproduced by the
 * renderer. The width constants and {@link #SEP_DASH} published below let the report service
 * assemble that fixed-width line:
 *
 * <pre>
 * col  width  field                                    picture
 *   1     16  TRAN-REPORT-TRANS-ID                     X(16)
 *  17      1  FILLER                                   X(01) VALUE SPACES
 *  18     11  TRAN-REPORT-ACCOUNT-ID                   X(11)
 *  29      1  FILLER                                   X(01) VALUE SPACES
 *  30      2  TRAN-REPORT-TYPE-CD                      X(02)
 *  32      1  FILLER                                   X(01) VALUE '-'
 *  33     15  TRAN-REPORT-TYPE-DESC                    X(15)
 *  48      1  FILLER                                   X(01) VALUE SPACES
 *  49      4  TRAN-REPORT-CAT-CD                       9(04)
 *  53      1  FILLER                                   X(01) VALUE '-'
 *  54     29  TRAN-REPORT-CAT-DESC                     X(29)
 *  83      1  FILLER                                   X(01) VALUE SPACES
 *  84     10  TRAN-REPORT-SOURCE                       X(10)
 *  94      4  FILLER                                   X(04) VALUE SPACES
 *  98     15  TRAN-REPORT-AMT                          -ZZZ,ZZZ,ZZZ.ZZ
 * 113      2  FILLER                                   X(02) VALUE SPACES
 * </pre>
 *
 * <p>Column widths align with the titles emitted by the adjacent {@code TRANSACTION-HEADER-1}
 * record (a separate report DTO). This class models only the single {@code
 * TRANSACTION-DETAIL-REPORT} 01-level.
 *
 * <p><strong>Authority:</strong> AAP &sect;0.4.1 (dto/report from CVTRA07Y.cpy) and AAP &sect;0.6.1
 * (decimal fidelity).
 */
public class TransactionDetailReport {

  // ---------------------------------------------------------------------------------------------
  // Fixed-width line contract (COBOL PIC widths + the literal FILLER separator). These widths are
  // part of the external report contract: the renderer space-pads each field to the exact column
  // so the generated line is byte-faithful to the legacy DALYREPT output (golden-file parity).
  // ---------------------------------------------------------------------------------------------

  /** Width of {@code TRAN-REPORT-TRANS-ID} ({@code PIC X(16)}). */
  public static final int W_TRANS_ID = 16;

  /** Width of {@code TRAN-REPORT-ACCOUNT-ID} ({@code PIC X(11)}). */
  public static final int W_ACCOUNT_ID = 11;

  /** Width of {@code TRAN-REPORT-TYPE-CD} ({@code PIC X(02)}). */
  public static final int W_TYPE_CD = 2;

  /** Width of {@code TRAN-REPORT-TYPE-DESC} ({@code PIC X(15)}). */
  public static final int W_TYPE_DESC = 15;

  /** Width of {@code TRAN-REPORT-CAT-CD} ({@code PIC 9(04)}). */
  public static final int W_CAT_CD = 4;

  /** Width of {@code TRAN-REPORT-CAT-DESC} ({@code PIC X(29)}). */
  public static final int W_CAT_DESC = 29;

  /** Width of {@code TRAN-REPORT-SOURCE} ({@code PIC X(10)}). */
  public static final int W_SOURCE = 10;

  /**
   * Rendered width of {@code TRAN-REPORT-AMT}. The edited picture {@code -ZZZ,ZZZ,ZZZ.ZZ} occupies
   * 15 character positions: a floating leading minus, a zero-suppressed and thousands-grouped
   * nine-digit integer part, the decimal point, and two fraction digits. The raw value lives in
   * {@link #tranReportAmt}; the edit is applied only at print time by the report formatter.
   */
  public static final int W_AMT_EDITED = 15;

  /**
   * Literal hyphen emitted by the two {@code FILLER PIC X(01) VALUE '-'} items: one joining {@code
   * TRAN-REPORT-TYPE-CD} to {@code TRAN-REPORT-TYPE-DESC} (for example {@code "DB-Debit"}) and one
   * joining {@code TRAN-REPORT-CAT-CD} to {@code TRAN-REPORT-CAT-DESC} (for example {@code
   * "0001-Purchase"}). It is part of the exact line contract; the renderer MUST preserve it.
   */
  public static final String SEP_DASH = "-";

  // ---------------------------------------------------------------------------------------------
  // Data fields (the COBOL 05-level elementary items). The interleaved FILLER items are NOT stored;
  // they are the documented separators declared above.
  // ---------------------------------------------------------------------------------------------

  /**
   * Transaction identifier. COBOL {@code TRAN-REPORT-TRANS-ID PIC X(16)} (max {@value #W_TRANS_ID}
   * characters); populated by {@code MOVE TRAN-ID TO TRAN-REPORT-TRANS-ID} in CBTRN03C {@code
   * 1120-WRITE-DETAIL}.
   */
  private String tranReportTransId;

  /**
   * Eleven-digit account identifier carried as text (the AAP-mandated string mapping for this
   * report field). COBOL {@code TRAN-REPORT-ACCOUNT-ID PIC X(11)} (max {@value #W_ACCOUNT_ID}
   * characters); populated by {@code MOVE XREF-ACCT-ID TO TRAN-REPORT-ACCOUNT-ID}.
   */
  private String tranReportAccountId;

  /**
   * Transaction type code. COBOL {@code TRAN-REPORT-TYPE-CD PIC X(02)} (max {@value #W_TYPE_CD}
   * characters); populated by {@code MOVE TRAN-TYPE-CD OF TRAN-RECORD TO TRAN-REPORT-TYPE-CD}. In
   * the rendered line it is immediately followed by {@link #SEP_DASH} and then {@link
   * #tranReportTypeDesc}.
   */
  private String tranReportTypeCd;

  /**
   * Transaction type description. COBOL {@code TRAN-REPORT-TYPE-DESC PIC X(15)} (max {@value
   * #W_TYPE_DESC} characters); populated by {@code MOVE TRAN-TYPE-DESC TO TRAN-REPORT-TYPE-DESC}.
   */
  private String tranReportTypeDesc;

  /**
   * Transaction category code. COBOL {@code TRAN-REPORT-CAT-CD PIC 9(04)} is an unsigned four-digit
   * numeric field populated by the numeric-to-numeric {@code MOVE TRAN-CAT-CD OF TRAN-RECORD TO
   * TRAN-REPORT-CAT-CD}; it is therefore mapped to {@link Integer} (values 0 to 9999) rather than
   * {@code String}, which is the PIC-faithful mapping recommended by AAP &sect;0.4.1. The renderer
   * zero-pads it to {@value #W_CAT_CD} digits and joins it to {@link #tranReportCatDesc} with
   * {@link #SEP_DASH} (for example {@code "0001-Purchase"}).
   */
  private Integer tranReportCatCd;

  /**
   * Transaction category description. COBOL {@code TRAN-REPORT-CAT-DESC PIC X(29)} (max {@value
   * #W_CAT_DESC} characters); populated by {@code MOVE TRAN-CAT-TYPE-DESC TO TRAN-REPORT-CAT-DESC}.
   */
  private String tranReportCatDesc;

  /**
   * Transaction source. COBOL {@code TRAN-REPORT-SOURCE PIC X(10)} (max {@value #W_SOURCE}
   * characters); populated by {@code MOVE TRAN-SOURCE TO TRAN-REPORT-SOURCE}.
   */
  private String tranReportSource;

  /**
   * Raw, signed transaction amount for this detail row, stored as {@link java.math.BigDecimal} at
   * scale 2 &mdash; never {@code float} or {@code double} (AAP &sect;0.6.1 decimal fidelity). The
   * COBOL source field {@code TRAN-AMT PIC S9(09)V99} is moved into the edited report field {@code
   * TRAN-REPORT-AMT PIC -ZZZ,ZZZ,ZZZ.ZZ} by {@code MOVE TRAN-AMT TO TRAN-REPORT-AMT}.
   *
   * <p>This DTO stores ONLY the raw value. The {@code -ZZZ,ZZZ,ZZZ.ZZ} edit (floating leading
   * minus, zero-suppressed, {@value #W_AMT_EDITED}-character width) is DISPLAY-ONLY and is applied
   * at print time by {@code com.aws.carddemo.util.NumberFormatter.formatReportAmount(BigDecimal)};
   * it is never formatted or pre-stringified here.
   */
  private BigDecimal tranReportAmt;

  /**
   * Creates an empty detail row with all fields unset ({@code null}). This mirrors the COBOL {@code
   * INITIALIZE TRANSACTION-DETAIL-REPORT} performed at the top of {@code 1120-WRITE-DETAIL} before
   * each row is populated through the setters and written.
   */
  public TransactionDetailReport() {
    // No-args constructor; the report service populates the fields via the setters below.
  }

  // ---------------------------------------------------------------------------------------------
  // Accessors.
  // ---------------------------------------------------------------------------------------------

  /**
   * @return {@code TRAN-REPORT-TRANS-ID} (max {@value #W_TRANS_ID} characters), or {@code null}.
   */
  public String getTranReportTransId() {
    return tranReportTransId;
  }

  /**
   * Sets {@code TRAN-REPORT-TRANS-ID} ({@code PIC X(16)}).
   *
   * @param tranReportTransId the transaction identifier (up to {@value #W_TRANS_ID} characters)
   */
  public void setTranReportTransId(String tranReportTransId) {
    this.tranReportTransId = tranReportTransId;
  }

  /**
   * @return {@code TRAN-REPORT-ACCOUNT-ID} (max {@value #W_ACCOUNT_ID} characters), or {@code
   *     null}.
   */
  public String getTranReportAccountId() {
    return tranReportAccountId;
  }

  /**
   * Sets {@code TRAN-REPORT-ACCOUNT-ID} ({@code PIC X(11)}).
   *
   * @param tranReportAccountId the account identifier text (up to {@value #W_ACCOUNT_ID}
   *     characters)
   */
  public void setTranReportAccountId(String tranReportAccountId) {
    this.tranReportAccountId = tranReportAccountId;
  }

  /**
   * @return {@code TRAN-REPORT-TYPE-CD} (max {@value #W_TYPE_CD} characters), or {@code null}.
   */
  public String getTranReportTypeCd() {
    return tranReportTypeCd;
  }

  /**
   * Sets {@code TRAN-REPORT-TYPE-CD} ({@code PIC X(02)}).
   *
   * @param tranReportTypeCd the transaction type code (up to {@value #W_TYPE_CD} characters)
   */
  public void setTranReportTypeCd(String tranReportTypeCd) {
    this.tranReportTypeCd = tranReportTypeCd;
  }

  /**
   * @return {@code TRAN-REPORT-TYPE-DESC} (max {@value #W_TYPE_DESC} characters), or {@code null}.
   */
  public String getTranReportTypeDesc() {
    return tranReportTypeDesc;
  }

  /**
   * Sets {@code TRAN-REPORT-TYPE-DESC} ({@code PIC X(15)}).
   *
   * @param tranReportTypeDesc the transaction type description (up to {@value #W_TYPE_DESC}
   *     characters)
   */
  public void setTranReportTypeDesc(String tranReportTypeDesc) {
    this.tranReportTypeDesc = tranReportTypeDesc;
  }

  /**
   * @return {@code TRAN-REPORT-CAT-CD} ({@code PIC 9(04)}, values 0 to 9999), or {@code null}.
   */
  public Integer getTranReportCatCd() {
    return tranReportCatCd;
  }

  /**
   * Sets {@code TRAN-REPORT-CAT-CD} ({@code PIC 9(04)}).
   *
   * @param tranReportCatCd the transaction category code (0 to 9999)
   */
  public void setTranReportCatCd(Integer tranReportCatCd) {
    this.tranReportCatCd = tranReportCatCd;
  }

  /**
   * @return {@code TRAN-REPORT-CAT-DESC} (max {@value #W_CAT_DESC} characters), or {@code null}.
   */
  public String getTranReportCatDesc() {
    return tranReportCatDesc;
  }

  /**
   * Sets {@code TRAN-REPORT-CAT-DESC} ({@code PIC X(29)}).
   *
   * @param tranReportCatDesc the transaction category description (up to {@value #W_CAT_DESC}
   *     characters)
   */
  public void setTranReportCatDesc(String tranReportCatDesc) {
    this.tranReportCatDesc = tranReportCatDesc;
  }

  /**
   * @return {@code TRAN-REPORT-SOURCE} (max {@value #W_SOURCE} characters), or {@code null}.
   */
  public String getTranReportSource() {
    return tranReportSource;
  }

  /**
   * Sets {@code TRAN-REPORT-SOURCE} ({@code PIC X(10)}).
   *
   * @param tranReportSource the transaction source (up to {@value #W_SOURCE} characters)
   */
  public void setTranReportSource(String tranReportSource) {
    this.tranReportSource = tranReportSource;
  }

  /**
   * Returns the raw, unedited transaction amount.
   *
   * @return the raw {@code TRAN-AMT} value as a scale-2 {@link java.math.BigDecimal}, or {@code
   *     null}
   */
  public BigDecimal getTranReportAmt() {
    return tranReportAmt;
  }

  /**
   * Sets the raw transaction amount (COBOL {@code TRAN-AMT PIC S9(09)V99}). Store the unedited
   * {@link java.math.BigDecimal} at scale 2; the {@code -ZZZ,ZZZ,ZZZ.ZZ} edit is applied only at
   * print time by the report formatter, never here.
   *
   * @param tranReportAmt the raw signed amount at scale 2
   */
  public void setTranReportAmt(BigDecimal tranReportAmt) {
    this.tranReportAmt = tranReportAmt;
  }
}
