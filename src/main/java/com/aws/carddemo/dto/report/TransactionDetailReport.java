package com.aws.carddemo.dto.report;

import java.math.BigDecimal;

/**
 * Detail line of the AWS CardDemo daily transaction report.
 *
 * <p><strong>Origin: legacy/cpy/CVTRA07Y.cpy ({@code TRANSACTION-DETAIL-REPORT}).</strong>
 * This is a 1:1 translation of the {@code TRANSACTION-DETAIL-REPORT} {@code 01}-level group in
 * copybook {@code CVTRA07Y}, the per-transaction detail line printed by the daily transaction
 * report writer ({@code batch/TransactionReportJobConfig}, migrated from COBOL program
 * {@code CBTRN03C}). The original COBOL layout is a fixed-width, <strong>114-byte</strong> group:
 *
 * <pre>
 *   TRAN-REPORT-TRANS-ID    PIC X(16)              (bytes   1-16)   variable
 *   FILLER                  PIC X(01) VALUE SPACES (byte     17)    ' '
 *   TRAN-REPORT-ACCOUNT-ID  PIC X(11)              (bytes  18-28)   variable
 *   FILLER                  PIC X(01) VALUE SPACES (byte     29)    ' '
 *   TRAN-REPORT-TYPE-CD     PIC X(02)              (bytes  30-31)   variable
 *   FILLER                  PIC X(01) VALUE '-'    (byte     32)    '-'
 *   TRAN-REPORT-TYPE-DESC   PIC X(15)              (bytes  33-47)   variable
 *   FILLER                  PIC X(01) VALUE SPACES (byte     48)    ' '
 *   TRAN-REPORT-CAT-CD      PIC 9(04)              (bytes  49-52)   variable (zero-filled)
 *   FILLER                  PIC X(01) VALUE '-'    (byte     53)    '-'
 *   TRAN-REPORT-CAT-DESC    PIC X(29)              (bytes  54-82)   variable
 *   FILLER                  PIC X(01) VALUE SPACES (byte     83)    ' '
 *   TRAN-REPORT-SOURCE      PIC X(10)              (bytes  84-93)   variable
 *   FILLER                  PIC X(04) VALUE SPACES (bytes  94-97)   '    '
 *   TRAN-REPORT-AMT         PIC -ZZZ,ZZZ,ZZZ.ZZ    (bytes  98-112)  variable (edited)
 *   FILLER                  PIC X(02) VALUE SPACES (bytes 113-114)  '  '
 *   ---------------------------------------------------------------------------
 *   total                                                          114 bytes
 * </pre>
 *
 * <h2>Modeling notes</h2>
 * <ul>
 *   <li>Every non-{@code FILLER} item is a runtime-variable field and is modeled as a typed
 *       property. Text columns are {@link String}s; the category code is the numeric COBOL
 *       {@code 9(04)} and is modeled as an {@code int}; the amount is a {@link BigDecimal}
 *       (never floating point, per AAP 0.6.1) and is edited with the {@code -ZZZ,ZZZ,ZZZ.ZZ}
 *       mask by {@link ReportAmountFormatter#formatSigned(BigDecimal)}.</li>
 *   <li>The fixed {@code FILLER} bytes (two literal {@code '-'} separators between type/desc and
 *       cat/desc, and the space fillers) are part of the printed byte columns and are reproduced
 *       exactly by {@link #toReportLine()}.</li>
 *   <li>This class deliberately does <em>not</em> override {@code toString()}; it inherits the
 *       non-sensitive {@code Object} representation so transaction identifiers and amounts cannot
 *       leak into logs (CWE-532). Report content is produced only through the explicit
 *       {@link #toReportLine()} rendering contract.</li>
 *   <li>The 114-byte group is right-padded with spaces to the 133-byte report record width
 *       ({@code FD-REPTFILE-REC PIC X(133)}) by the report writer, consistent with
 *       {@link TransactionReportHeaders}.</li>
 * </ul>
 */
public class TransactionDetailReport {

    /** Exact fixed-width length, in bytes, of the {@code TRANSACTION-DETAIL-REPORT} group. */
    public static final int LINE_LENGTH = 114;

    /** {@code FILLER PIC X(01) VALUE '-'} separator printed after the transaction type code. */
    public static final char TYPE_SEPARATOR = '-';

    /** {@code FILLER PIC X(01) VALUE '-'} separator printed after the transaction category code. */
    public static final char CATEGORY_SEPARATOR = '-';

    /** {@code TRAN-REPORT-TRANS-ID PIC X(16)}: transaction id printed on the detail line. */
    private String tranReportTransId;

    /** {@code TRAN-REPORT-ACCOUNT-ID PIC X(11)}: account id printed on the detail line. */
    private String tranReportAccountId;

    /** {@code TRAN-REPORT-TYPE-CD PIC X(02)}: transaction type code. */
    private String tranReportTypeCd;

    /** {@code TRAN-REPORT-TYPE-DESC PIC X(15)}: transaction type description. */
    private String tranReportTypeDesc;

    /** {@code TRAN-REPORT-CAT-CD PIC 9(04)}: transaction category code (zero-filled to 4 digits). */
    private int tranReportCatCd;

    /** {@code TRAN-REPORT-CAT-DESC PIC X(29)}: transaction category description. */
    private String tranReportCatDesc;

    /** {@code TRAN-REPORT-SOURCE PIC X(10)}: transaction source. */
    private String tranReportSource;

    /** {@code TRAN-REPORT-AMT PIC -ZZZ,ZZZ,ZZZ.ZZ}: transaction amount (edited on output). */
    private BigDecimal tranReportAmt;

    /**
     * Creates an empty detail line. Text fields default to {@code null} (rendered as spaces),
     * {@code tranReportCatCd} to {@code 0}, and {@code tranReportAmt} to {@code null}
     * (rendered as the all-zero edited amount).
     */
    public TransactionDetailReport() {
    }

    /**
     * Creates a fully populated detail line.
     *
     * @param tranReportTransId   the transaction id ({@code TRAN-REPORT-TRANS-ID})
     * @param tranReportAccountId the account id ({@code TRAN-REPORT-ACCOUNT-ID})
     * @param tranReportTypeCd    the transaction type code ({@code TRAN-REPORT-TYPE-CD})
     * @param tranReportTypeDesc  the transaction type description ({@code TRAN-REPORT-TYPE-DESC})
     * @param tranReportCatCd     the transaction category code ({@code TRAN-REPORT-CAT-CD})
     * @param tranReportCatDesc   the transaction category description ({@code TRAN-REPORT-CAT-DESC})
     * @param tranReportSource    the transaction source ({@code TRAN-REPORT-SOURCE})
     * @param tranReportAmt       the transaction amount ({@code TRAN-REPORT-AMT}); may be {@code null}
     */
    public TransactionDetailReport(String tranReportTransId, String tranReportAccountId,
            String tranReportTypeCd, String tranReportTypeDesc, int tranReportCatCd,
            String tranReportCatDesc, String tranReportSource, BigDecimal tranReportAmt) {
        this.tranReportTransId = tranReportTransId;
        this.tranReportAccountId = tranReportAccountId;
        this.tranReportTypeCd = tranReportTypeCd;
        this.tranReportTypeDesc = tranReportTypeDesc;
        this.tranReportCatCd = tranReportCatCd;
        this.tranReportCatDesc = tranReportCatDesc;
        this.tranReportSource = tranReportSource;
        this.tranReportAmt = tranReportAmt;
    }

    /**
     * Returns the transaction id ({@code TRAN-REPORT-TRANS-ID}).
     *
     * @return the transaction id, or {@code null} if unset
     */
    public String getTranReportTransId() {
        return tranReportTransId;
    }

    /**
     * Sets the transaction id ({@code TRAN-REPORT-TRANS-ID}).
     *
     * @param tranReportTransId the transaction id; may be {@code null}
     */
    public void setTranReportTransId(String tranReportTransId) {
        this.tranReportTransId = tranReportTransId;
    }

    /**
     * Returns the account id ({@code TRAN-REPORT-ACCOUNT-ID}).
     *
     * @return the account id, or {@code null} if unset
     */
    public String getTranReportAccountId() {
        return tranReportAccountId;
    }

    /**
     * Sets the account id ({@code TRAN-REPORT-ACCOUNT-ID}).
     *
     * @param tranReportAccountId the account id; may be {@code null}
     */
    public void setTranReportAccountId(String tranReportAccountId) {
        this.tranReportAccountId = tranReportAccountId;
    }

    /**
     * Returns the transaction type code ({@code TRAN-REPORT-TYPE-CD}).
     *
     * @return the transaction type code, or {@code null} if unset
     */
    public String getTranReportTypeCd() {
        return tranReportTypeCd;
    }

    /**
     * Sets the transaction type code ({@code TRAN-REPORT-TYPE-CD}).
     *
     * @param tranReportTypeCd the transaction type code; may be {@code null}
     */
    public void setTranReportTypeCd(String tranReportTypeCd) {
        this.tranReportTypeCd = tranReportTypeCd;
    }

    /**
     * Returns the transaction type description ({@code TRAN-REPORT-TYPE-DESC}).
     *
     * @return the transaction type description, or {@code null} if unset
     */
    public String getTranReportTypeDesc() {
        return tranReportTypeDesc;
    }

    /**
     * Sets the transaction type description ({@code TRAN-REPORT-TYPE-DESC}).
     *
     * @param tranReportTypeDesc the transaction type description; may be {@code null}
     */
    public void setTranReportTypeDesc(String tranReportTypeDesc) {
        this.tranReportTypeDesc = tranReportTypeDesc;
    }

    /**
     * Returns the transaction category code ({@code TRAN-REPORT-CAT-CD}).
     *
     * @return the transaction category code
     */
    public int getTranReportCatCd() {
        return tranReportCatCd;
    }

    /**
     * Sets the transaction category code ({@code TRAN-REPORT-CAT-CD}).
     *
     * @param tranReportCatCd the transaction category code
     */
    public void setTranReportCatCd(int tranReportCatCd) {
        this.tranReportCatCd = tranReportCatCd;
    }

    /**
     * Returns the transaction category description ({@code TRAN-REPORT-CAT-DESC}).
     *
     * @return the transaction category description, or {@code null} if unset
     */
    public String getTranReportCatDesc() {
        return tranReportCatDesc;
    }

    /**
     * Sets the transaction category description ({@code TRAN-REPORT-CAT-DESC}).
     *
     * @param tranReportCatDesc the transaction category description; may be {@code null}
     */
    public void setTranReportCatDesc(String tranReportCatDesc) {
        this.tranReportCatDesc = tranReportCatDesc;
    }

    /**
     * Returns the transaction source ({@code TRAN-REPORT-SOURCE}).
     *
     * @return the transaction source, or {@code null} if unset
     */
    public String getTranReportSource() {
        return tranReportSource;
    }

    /**
     * Sets the transaction source ({@code TRAN-REPORT-SOURCE}).
     *
     * @param tranReportSource the transaction source; may be {@code null}
     */
    public void setTranReportSource(String tranReportSource) {
        this.tranReportSource = tranReportSource;
    }

    /**
     * Returns the transaction amount ({@code TRAN-REPORT-AMT}).
     *
     * @return the transaction amount, or {@code null} if unset
     */
    public BigDecimal getTranReportAmt() {
        return tranReportAmt;
    }

    /**
     * Sets the transaction amount ({@code TRAN-REPORT-AMT}).
     *
     * @param tranReportAmt the transaction amount; may be {@code null}
     */
    public void setTranReportAmt(BigDecimal tranReportAmt) {
        this.tranReportAmt = tranReportAmt;
    }

    /**
     * Returns the transaction amount edited with the COBOL {@code -ZZZ,ZZZ,ZZZ.ZZ} mask, exactly
     * as it prints on the detail line ({@code TRAN-REPORT-AMT}).
     *
     * @return the 15-character edited amount (see {@link ReportAmountFormatter#formatSigned})
     */
    public String getFormattedAmount() {
        return ReportAmountFormatter.formatSigned(tranReportAmt);
    }

    /**
     * Renders this detail line as the exact {@value #LINE_LENGTH}-byte {@code TRANSACTION-DETAIL-REPORT}
     * record, reproducing the COBOL group byte-for-byte: each text column is left-justified and
     * space-padded (or right-truncated) to its {@code PIC X} width, the category code is zero-filled
     * to four digits, the two literal {@code '-'} separators and the space fillers are emitted at
     * their fixed positions, and the amount is edited with the signed mask. The report writer pads
     * the result to the 133-byte report record width.
     *
     * @return the fixed-width detail line, exactly {@value #LINE_LENGTH} characters long
     */
    public String toReportLine() {
        StringBuilder sb = new StringBuilder(LINE_LENGTH);
        sb.append(fixed(tranReportTransId, 16));
        sb.append(' ');
        sb.append(fixed(tranReportAccountId, 11));
        sb.append(' ');
        sb.append(fixed(tranReportTypeCd, 2));
        sb.append(TYPE_SEPARATOR);
        sb.append(fixed(tranReportTypeDesc, 15));
        sb.append(' ');
        sb.append(String.format("%04d", tranReportCatCd));
        sb.append(CATEGORY_SEPARATOR);
        sb.append(fixed(tranReportCatDesc, 29));
        sb.append(' ');
        sb.append(fixed(tranReportSource, 10));
        sb.append("    ");
        sb.append(ReportAmountFormatter.formatSigned(tranReportAmt));
        sb.append("  ");
        return sb.toString();
    }

    /**
     * Left-justifies {@code value} in a fixed {@code width} field of spaces, reproducing COBOL's
     * {@code MOVE} into a {@code PIC X(width)} item: a {@code null} or shorter value is right-padded
     * with spaces, and a longer value is truncated on the right to {@code width} characters.
     *
     * @param value the field value ({@code null} is treated as spaces)
     * @param width the fixed COBOL field width
     * @return the value rendered in exactly {@code width} characters
     */
    private static String fixed(String value, int width) {
        String v = (value == null) ? "" : value;
        if (v.length() >= width) {
            return v.substring(0, width);
        }
        return v + " ".repeat(width - v.length());
    }
}
