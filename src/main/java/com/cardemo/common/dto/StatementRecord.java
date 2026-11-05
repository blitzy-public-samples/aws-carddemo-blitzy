/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * Licensed under the Apache License, Version 2.0 (the "License").
 * You may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *     http://www.apache.org/licenses/LICENSE-2.0
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.cardemo.common.dto;

import java.math.BigDecimal;
import java.util.Objects;

/**
 * Statement report record DTO — translated from COSTM01.CPY (TRNX-RECORD)
 * and CVTRA07Y.cpy (report structures).
 *
 * <p>This DTO combines two complementary COBOL copybook layouts into a single
 * cohesive Java class representing the complete statement report data structure
 * used by the statement generation batch job (CBSTM03A/CBSTM03B).</p>
 *
 * <h3>COSTM01.CPY — TRNX-RECORD (Transaction Altered Layout for Reporting)</h3>
 * <p>Provides the composite key (TRNX-CARD-NUM + TRNX-ID) that identifies
 * a transaction record within the statement context. The key is a 32-byte
 * structure split into a 16-byte card number and 16-byte transaction ID.</p>
 *
 * <h3>CVTRA07Y.cpy — Report Structures</h3>
 * <p>Provides the report header (REPORT-NAME-HEADER), transaction detail
 * (TRANSACTION-DETAIL-REPORT), and aggregate totals (page, account, grand)
 * used for daily transaction report generation.</p>
 *
 * <p><strong>Data integrity rules:</strong></p>
 * <ul>
 *   <li>All monetary/amount fields use {@link BigDecimal} — never float/double</li>
 *   <li>COBOL COMP-3 packed decimal fields map to BigDecimal with scale 2</li>
 *   <li>COBOL display-format PIC masks (e.g., PIC -ZZZ,ZZZ,ZZZ.ZZ) are stored
 *       as BigDecimal values; formatting is deferred to presentation layer</li>
 *   <li>FILLER fields from COBOL layouts are NOT mapped</li>
 *   <li>Equality and hashing use the composite key (trnxCardNum + trnxId)</li>
 * </ul>
 */
public class StatementRecord {

    // -----------------------------------------------------------------------
    // Report Header Fields (from CVTRA07Y.cpy REPORT-NAME-HEADER)
    // -----------------------------------------------------------------------

    /** Report short name — REPT-SHORT-NAME PIC X(38), default "DALYREPT". */
    private String reptShortName;

    /** Report long name — REPT-LONG-NAME PIC X(41), default "Daily Transaction Report". */
    private String reptLongName;

    /** Date header label — REPT-DATE-HEADER PIC X(12), default "Date Range: ". */
    private String reptDateHeader;

    /** Report start date — REPT-START-DATE PIC X(10). */
    private String reptStartDate;

    /** Report end date — REPT-END-DATE PIC X(10). */
    private String reptEndDate;

    // -----------------------------------------------------------------------
    // Transaction Detail Report Fields (from CVTRA07Y.cpy TRANSACTION-DETAIL-REPORT)
    // -----------------------------------------------------------------------

    /** Transaction ID — TRAN-REPORT-TRANS-ID PIC X(16). */
    private String tranReportTransId;

    /** Account ID — TRAN-REPORT-ACCOUNT-ID PIC X(11). */
    private String tranReportAccountId;

    /** Transaction type code — TRAN-REPORT-TYPE-CD PIC X(02). */
    private String tranReportTypeCd;

    /** Transaction type description — TRAN-REPORT-TYPE-DESC PIC X(15). */
    private String tranReportTypeDesc;

    /** Transaction category code — TRAN-REPORT-CAT-CD PIC 9(04). Numeric category identifier. */
    private int tranReportCatCd;

    /** Transaction category description — TRAN-REPORT-CAT-DESC PIC X(29). */
    private String tranReportCatDesc;

    /** Transaction source — TRAN-REPORT-SOURCE PIC X(10). */
    private String tranReportSource;

    /**
     * Transaction amount — TRAN-REPORT-AMT PIC -ZZZ,ZZZ,ZZZ.ZZ.
     * Stored as BigDecimal with scale 2 for exact monetary arithmetic.
     * The COBOL display PIC mask is a presentation concern, not stored.
     */
    private BigDecimal tranReportAmt;

    // -----------------------------------------------------------------------
    // Report Totals Fields (from CVTRA07Y.cpy)
    // -----------------------------------------------------------------------

    /**
     * Page total — REPT-PAGE-TOTAL PIC +ZZZ,ZZZ,ZZZ.ZZ.
     * BigDecimal with scale 2 — aggregated total for the current page.
     */
    private BigDecimal reptPageTotal;

    /**
     * Account total — REPT-ACCOUNT-TOTAL PIC +ZZZ,ZZZ,ZZZ.ZZ.
     * BigDecimal with scale 2 — aggregated total for the current account.
     */
    private BigDecimal reptAccountTotal;

    /**
     * Grand total — REPT-GRAND-TOTAL PIC +ZZZ,ZZZ,ZZZ.ZZ.
     * BigDecimal with scale 2 — aggregated total across all accounts.
     */
    private BigDecimal reptGrandTotal;

    // -----------------------------------------------------------------------
    // Transaction Key Fields (from COSTM01.CPY TRNX-RECORD / TRNX-KEY)
    // -----------------------------------------------------------------------

    /** Card number key field — TRNX-CARD-NUM PIC X(16). First part of composite key. */
    private String trnxCardNum;

    /** Transaction ID key field — TRNX-ID PIC X(16). Second part of composite key. */
    private String trnxId;

    // -----------------------------------------------------------------------
    // Constructors
    // -----------------------------------------------------------------------

    /**
     * Default no-arg constructor.
     * Initializes report header fields with COBOL VALUE clause defaults
     * matching the REPORT-NAME-HEADER definition in CVTRA07Y.cpy.
     */
    public StatementRecord() {
        this.reptShortName = "DALYREPT";
        this.reptLongName = "Daily Transaction Report";
        this.reptDateHeader = "Date Range: ";
        this.reptStartDate = "";
        this.reptEndDate = "";
        this.tranReportTransId = "";
        this.tranReportAccountId = "";
        this.tranReportTypeCd = "";
        this.tranReportTypeDesc = "";
        this.tranReportCatCd = 0;
        this.tranReportCatDesc = "";
        this.tranReportSource = "";
        this.tranReportAmt = BigDecimal.ZERO;
        this.reptPageTotal = BigDecimal.ZERO;
        this.reptAccountTotal = BigDecimal.ZERO;
        this.reptGrandTotal = BigDecimal.ZERO;
        this.trnxCardNum = "";
        this.trnxId = "";
    }

    /**
     * All-args constructor for complete initialization of a StatementRecord.
     *
     * @param reptShortName        report short name (default "DALYREPT")
     * @param reptLongName         report long name (default "Daily Transaction Report")
     * @param reptDateHeader       date header label (default "Date Range: ")
     * @param reptStartDate        report start date
     * @param reptEndDate          report end date
     * @param tranReportTransId    transaction ID for report line
     * @param tranReportAccountId  account ID for report line
     * @param tranReportTypeCd     transaction type code
     * @param tranReportTypeDesc   transaction type description
     * @param tranReportCatCd      transaction category code (numeric)
     * @param tranReportCatDesc    transaction category description
     * @param tranReportSource     transaction source identifier
     * @param tranReportAmt        transaction amount (BigDecimal, scale 2)
     * @param reptPageTotal        page total (BigDecimal, scale 2)
     * @param reptAccountTotal     account total (BigDecimal, scale 2)
     * @param reptGrandTotal       grand total (BigDecimal, scale 2)
     * @param trnxCardNum          card number — first part of composite key
     * @param trnxId               transaction ID — second part of composite key
     */
    @SuppressWarnings("parameternumber")
    public StatementRecord(String reptShortName, String reptLongName,
                           String reptDateHeader, String reptStartDate,
                           String reptEndDate, String tranReportTransId,
                           String tranReportAccountId, String tranReportTypeCd,
                           String tranReportTypeDesc, int tranReportCatCd,
                           String tranReportCatDesc, String tranReportSource,
                           BigDecimal tranReportAmt, BigDecimal reptPageTotal,
                           BigDecimal reptAccountTotal, BigDecimal reptGrandTotal,
                           String trnxCardNum, String trnxId) {
        this.reptShortName = reptShortName;
        this.reptLongName = reptLongName;
        this.reptDateHeader = reptDateHeader;
        this.reptStartDate = reptStartDate;
        this.reptEndDate = reptEndDate;
        this.tranReportTransId = tranReportTransId;
        this.tranReportAccountId = tranReportAccountId;
        this.tranReportTypeCd = tranReportTypeCd;
        this.tranReportTypeDesc = tranReportTypeDesc;
        this.tranReportCatCd = tranReportCatCd;
        this.tranReportCatDesc = tranReportCatDesc;
        this.tranReportSource = tranReportSource;
        this.tranReportAmt = tranReportAmt;
        this.reptPageTotal = reptPageTotal;
        this.reptAccountTotal = reptAccountTotal;
        this.reptGrandTotal = reptGrandTotal;
        this.trnxCardNum = trnxCardNum;
        this.trnxId = trnxId;
    }

    // -----------------------------------------------------------------------
    // Getters and Setters — Report Header Fields
    // -----------------------------------------------------------------------

    /**
     * Returns the report short name.
     * Maps to REPT-SHORT-NAME PIC X(38) in CVTRA07Y.cpy.
     *
     * @return report short name, typically "DALYREPT"
     */
    public String getReptShortName() {
        return reptShortName;
    }

    /**
     * Sets the report short name.
     *
     * @param reptShortName report short name
     */
    public void setReptShortName(String reptShortName) {
        this.reptShortName = reptShortName;
    }

    /**
     * Returns the report long name.
     * Maps to REPT-LONG-NAME PIC X(41) in CVTRA07Y.cpy.
     *
     * @return report long name, typically "Daily Transaction Report"
     */
    public String getReptLongName() {
        return reptLongName;
    }

    /**
     * Sets the report long name.
     *
     * @param reptLongName report long name
     */
    public void setReptLongName(String reptLongName) {
        this.reptLongName = reptLongName;
    }

    /**
     * Returns the date header label.
     * Maps to REPT-DATE-HEADER PIC X(12) in CVTRA07Y.cpy.
     *
     * @return date header label, typically "Date Range: "
     */
    public String getReptDateHeader() {
        return reptDateHeader;
    }

    /**
     * Sets the date header label.
     *
     * @param reptDateHeader date header label
     */
    public void setReptDateHeader(String reptDateHeader) {
        this.reptDateHeader = reptDateHeader;
    }

    /**
     * Returns the report start date.
     * Maps to REPT-START-DATE PIC X(10) in CVTRA07Y.cpy.
     *
     * @return report start date string
     */
    public String getReptStartDate() {
        return reptStartDate;
    }

    /**
     * Sets the report start date.
     *
     * @param reptStartDate report start date
     */
    public void setReptStartDate(String reptStartDate) {
        this.reptStartDate = reptStartDate;
    }

    /**
     * Returns the report end date.
     * Maps to REPT-END-DATE PIC X(10) in CVTRA07Y.cpy.
     *
     * @return report end date string
     */
    public String getReptEndDate() {
        return reptEndDate;
    }

    /**
     * Sets the report end date.
     *
     * @param reptEndDate report end date
     */
    public void setReptEndDate(String reptEndDate) {
        this.reptEndDate = reptEndDate;
    }

    // -----------------------------------------------------------------------
    // Getters and Setters — Transaction Detail Report Fields
    // -----------------------------------------------------------------------

    /**
     * Returns the transaction ID from the report detail line.
     * Maps to TRAN-REPORT-TRANS-ID PIC X(16) in CVTRA07Y.cpy.
     *
     * @return transaction ID
     */
    public String getTranReportTransId() {
        return tranReportTransId;
    }

    /**
     * Sets the transaction ID for the report detail line.
     *
     * @param tranReportTransId transaction ID
     */
    public void setTranReportTransId(String tranReportTransId) {
        this.tranReportTransId = tranReportTransId;
    }

    /**
     * Returns the account ID from the report detail line.
     * Maps to TRAN-REPORT-ACCOUNT-ID PIC X(11) in CVTRA07Y.cpy.
     *
     * @return account ID
     */
    public String getTranReportAccountId() {
        return tranReportAccountId;
    }

    /**
     * Sets the account ID for the report detail line.
     *
     * @param tranReportAccountId account ID
     */
    public void setTranReportAccountId(String tranReportAccountId) {
        this.tranReportAccountId = tranReportAccountId;
    }

    /**
     * Returns the transaction type code.
     * Maps to TRAN-REPORT-TYPE-CD PIC X(02) in CVTRA07Y.cpy.
     *
     * @return two-character transaction type code
     */
    public String getTranReportTypeCd() {
        return tranReportTypeCd;
    }

    /**
     * Sets the transaction type code.
     *
     * @param tranReportTypeCd two-character transaction type code
     */
    public void setTranReportTypeCd(String tranReportTypeCd) {
        this.tranReportTypeCd = tranReportTypeCd;
    }

    /**
     * Returns the transaction type description.
     * Maps to TRAN-REPORT-TYPE-DESC PIC X(15) in CVTRA07Y.cpy.
     *
     * @return transaction type description
     */
    public String getTranReportTypeDesc() {
        return tranReportTypeDesc;
    }

    /**
     * Sets the transaction type description.
     *
     * @param tranReportTypeDesc transaction type description
     */
    public void setTranReportTypeDesc(String tranReportTypeDesc) {
        this.tranReportTypeDesc = tranReportTypeDesc;
    }

    /**
     * Returns the transaction category code.
     * Maps to TRAN-REPORT-CAT-CD PIC 9(04) in CVTRA07Y.cpy.
     * Numeric field (not monetary), stored as int.
     *
     * @return four-digit transaction category code
     */
    public int getTranReportCatCd() {
        return tranReportCatCd;
    }

    /**
     * Sets the transaction category code.
     *
     * @param tranReportCatCd four-digit transaction category code
     */
    public void setTranReportCatCd(int tranReportCatCd) {
        this.tranReportCatCd = tranReportCatCd;
    }

    /**
     * Returns the transaction category description.
     * Maps to TRAN-REPORT-CAT-DESC PIC X(29) in CVTRA07Y.cpy.
     *
     * @return transaction category description
     */
    public String getTranReportCatDesc() {
        return tranReportCatDesc;
    }

    /**
     * Sets the transaction category description.
     *
     * @param tranReportCatDesc transaction category description
     */
    public void setTranReportCatDesc(String tranReportCatDesc) {
        this.tranReportCatDesc = tranReportCatDesc;
    }

    /**
     * Returns the transaction source identifier.
     * Maps to TRAN-REPORT-SOURCE PIC X(10) in CVTRA07Y.cpy.
     *
     * @return transaction source
     */
    public String getTranReportSource() {
        return tranReportSource;
    }

    /**
     * Sets the transaction source identifier.
     *
     * @param tranReportSource transaction source
     */
    public void setTranReportSource(String tranReportSource) {
        this.tranReportSource = tranReportSource;
    }

    /**
     * Returns the transaction amount.
     * Maps to TRAN-REPORT-AMT PIC -ZZZ,ZZZ,ZZZ.ZZ in CVTRA07Y.cpy.
     * Stored as BigDecimal with scale 2 for exact monetary arithmetic.
     *
     * @return transaction amount as BigDecimal
     */
    public BigDecimal getTranReportAmt() {
        return tranReportAmt;
    }

    /**
     * Sets the transaction amount.
     *
     * @param tranReportAmt transaction amount as BigDecimal (scale 2)
     */
    public void setTranReportAmt(BigDecimal tranReportAmt) {
        this.tranReportAmt = tranReportAmt;
    }

    // -----------------------------------------------------------------------
    // Getters and Setters — Report Totals Fields
    // -----------------------------------------------------------------------

    /**
     * Returns the page total.
     * Maps to REPT-PAGE-TOTAL PIC +ZZZ,ZZZ,ZZZ.ZZ in CVTRA07Y.cpy.
     *
     * @return page total as BigDecimal
     */
    public BigDecimal getReptPageTotal() {
        return reptPageTotal;
    }

    /**
     * Sets the page total.
     *
     * @param reptPageTotal page total as BigDecimal (scale 2)
     */
    public void setReptPageTotal(BigDecimal reptPageTotal) {
        this.reptPageTotal = reptPageTotal;
    }

    /**
     * Returns the account total.
     * Maps to REPT-ACCOUNT-TOTAL PIC +ZZZ,ZZZ,ZZZ.ZZ in CVTRA07Y.cpy.
     *
     * @return account total as BigDecimal
     */
    public BigDecimal getReptAccountTotal() {
        return reptAccountTotal;
    }

    /**
     * Sets the account total.
     *
     * @param reptAccountTotal account total as BigDecimal (scale 2)
     */
    public void setReptAccountTotal(BigDecimal reptAccountTotal) {
        this.reptAccountTotal = reptAccountTotal;
    }

    /**
     * Returns the grand total.
     * Maps to REPT-GRAND-TOTAL PIC +ZZZ,ZZZ,ZZZ.ZZ in CVTRA07Y.cpy.
     *
     * @return grand total as BigDecimal
     */
    public BigDecimal getReptGrandTotal() {
        return reptGrandTotal;
    }

    /**
     * Sets the grand total.
     *
     * @param reptGrandTotal grand total as BigDecimal (scale 2)
     */
    public void setReptGrandTotal(BigDecimal reptGrandTotal) {
        this.reptGrandTotal = reptGrandTotal;
    }

    // -----------------------------------------------------------------------
    // Getters and Setters — Transaction Key Fields (COSTM01.CPY TRNX-KEY)
    // -----------------------------------------------------------------------

    /**
     * Returns the card number key field.
     * Maps to TRNX-CARD-NUM PIC X(16) in COSTM01.CPY.
     * First part of the composite key (TRNX-KEY).
     *
     * @return 16-character card number
     */
    public String getTrnxCardNum() {
        return trnxCardNum;
    }

    /**
     * Sets the card number key field.
     *
     * @param trnxCardNum 16-character card number
     */
    public void setTrnxCardNum(String trnxCardNum) {
        this.trnxCardNum = trnxCardNum;
    }

    /**
     * Returns the transaction ID key field.
     * Maps to TRNX-ID PIC X(16) in COSTM01.CPY.
     * Second part of the composite key (TRNX-KEY).
     *
     * @return 16-character transaction ID
     */
    public String getTrnxId() {
        return trnxId;
    }

    /**
     * Sets the transaction ID key field.
     *
     * @param trnxId 16-character transaction ID
     */
    public void setTrnxId(String trnxId) {
        this.trnxId = trnxId;
    }

    // -----------------------------------------------------------------------
    // toString, equals, hashCode
    // -----------------------------------------------------------------------

    /**
     * Returns a string representation of this statement record focusing
     * on key identifying fields for logging and debugging.
     *
     * @return string representation with composite key and report header info
     */
    @Override
    public String toString() {
        return "StatementRecord{"
                + "trnxCardNum='" + trnxCardNum + '\''
                + ", trnxId='" + trnxId + '\''
                + ", reptShortName='" + reptShortName + '\''
                + ", tranReportTransId='" + tranReportTransId + '\''
                + ", tranReportAccountId='" + tranReportAccountId + '\''
                + ", tranReportAmt=" + tranReportAmt
                + ", reptPageTotal=" + reptPageTotal
                + ", reptAccountTotal=" + reptAccountTotal
                + ", reptGrandTotal=" + reptGrandTotal
                + '}';
    }

    /**
     * Compares this StatementRecord with another for equality.
     * Uses the composite key (trnxCardNum + trnxId) from the TRNX-KEY
     * structure defined in COSTM01.CPY for identity comparison, matching
     * the VSAM KSDS keyed access semantics.
     *
     * @param obj the object to compare with
     * @return true if both records share the same composite key
     */
    @Override
    public boolean equals(Object obj) {
        if (this == obj) {
            return true;
        }
        if (!(obj instanceof StatementRecord other)) {
            return false;
        }
        return Objects.equals(trnxCardNum, other.trnxCardNum)
                && Objects.equals(trnxId, other.trnxId);
    }

    /**
     * Computes a hash code based on the composite key (trnxCardNum + trnxId)
     * from the TRNX-KEY structure defined in COSTM01.CPY.
     *
     * @return hash code derived from the composite key fields
     */
    @Override
    public int hashCode() {
        return Objects.hash(trnxCardNum, trnxId);
    }
}
