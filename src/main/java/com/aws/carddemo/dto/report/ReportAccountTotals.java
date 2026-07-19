package com.aws.carddemo.dto.report;

import java.math.BigDecimal;

/**
 * Account-total line of the AWS CardDemo daily transaction report.
 *
 * <p><strong>Origin: legacy/cpy/CVTRA07Y.cpy ({@code REPORT-ACCOUNT-TOTALS}).</strong>
 * This is a 1:1 translation of the {@code REPORT-ACCOUNT-TOTALS} {@code 01}-level group in copybook
 * {@code CVTRA07Y}, the per-account subtotal line printed by the daily transaction report writer
 * ({@code batch/TransactionReportJobConfig}, migrated from COBOL program {@code CBTRN03C}). The
 * original COBOL layout is a fixed-width, <strong>112-byte</strong> group:
 *
 * <pre>
 *   FILLER               PIC X(13) VALUE 'Account Total' (bytes   1-13)   'Account Total' (label)
 *   FILLER               PIC X(84) VALUE ALL '.'         (bytes  14-97)   leader dots
 *   REPT-ACCOUNT-TOTAL   PIC +ZZZ,ZZZ,ZZZ.ZZ             (bytes  98-112)  variable (edited)
 *   ---------------------------------------------------------------------------
 *   total                                                          112 bytes
 * </pre>
 *
 * <h2>Modeling notes</h2>
 * <ul>
 *   <li>The only runtime-variable field is {@link #reptAccountTotal}; it is a {@link BigDecimal}
 *       (never floating point, per AAP 0.6.1) edited with the forced-sign {@code +ZZZ,ZZZ,ZZZ.ZZ}
 *       mask by {@link ReportAmountFormatter#formatForcedSign(BigDecimal)}.</li>
 *   <li>The fixed {@code FILLER} literals (the {@code 'Account Total'} label, which exactly fills
 *       its 13-byte field, and the run of leader dots) are COBOL {@code VALUE} constants and are
 *       modeled as immutable {@code String} constants, reproduced verbatim by
 *       {@link #toReportLine()}.</li>
 *   <li>This class does not override {@code toString()}; it inherits the non-sensitive
 *       {@code Object} representation so amounts cannot leak into logs (CWE-532). Report content is
 *       produced only through {@link #toReportLine()}.</li>
 *   <li>The 112-byte group is right-padded with spaces to the 133-byte report record width by the
 *       report writer, consistent with {@link TransactionReportHeaders}.</li>
 * </ul>
 */
public class ReportAccountTotals {

    /** Exact fixed-width length, in bytes, of the {@code REPORT-ACCOUNT-TOTALS} group. */
    public static final int LINE_LENGTH = 112;

    /** {@code FILLER PIC X(13) VALUE 'Account Total'}: fixed label (exactly fills the 13-byte field). */
    public static final String LABEL = "Account Total";

    /** Fixed width, in bytes, of the {@link #LABEL} field ({@code PIC X(13)}). */
    public static final int LABEL_WIDTH = 13;

    /** {@code FILLER PIC X(84) VALUE ALL '.'}: fixed 84-character leader of dots. */
    public static final String LEADER_DOTS = ".".repeat(84);

    /** {@code REPT-ACCOUNT-TOTAL PIC +ZZZ,ZZZ,ZZZ.ZZ}: account subtotal amount (edited on output). */
    private BigDecimal reptAccountTotal;

    /**
     * Creates an empty account-total line. {@link #reptAccountTotal} defaults to {@code null}, which
     * renders as the all-zero forced-sign edited amount.
     */
    public ReportAccountTotals() {
    }

    /**
     * Creates an account-total line for the supplied subtotal.
     *
     * @param reptAccountTotal the account subtotal amount ({@code REPT-ACCOUNT-TOTAL}); may be
     *                         {@code null}
     */
    public ReportAccountTotals(BigDecimal reptAccountTotal) {
        this.reptAccountTotal = reptAccountTotal;
    }

    /**
     * Returns the account subtotal amount ({@code REPT-ACCOUNT-TOTAL}).
     *
     * @return the account subtotal, or {@code null} if unset
     */
    public BigDecimal getReptAccountTotal() {
        return reptAccountTotal;
    }

    /**
     * Sets the account subtotal amount ({@code REPT-ACCOUNT-TOTAL}).
     *
     * @param reptAccountTotal the account subtotal; may be {@code null}
     */
    public void setReptAccountTotal(BigDecimal reptAccountTotal) {
        this.reptAccountTotal = reptAccountTotal;
    }

    /**
     * Returns the account subtotal edited with the COBOL {@code +ZZZ,ZZZ,ZZZ.ZZ} mask, exactly as it
     * prints on the account-total line ({@code REPT-ACCOUNT-TOTAL}).
     *
     * @return the 15-character edited amount (see {@link ReportAmountFormatter#formatForcedSign})
     */
    public String getFormattedTotal() {
        return ReportAmountFormatter.formatForcedSign(reptAccountTotal);
    }

    /**
     * Renders this account-total line as the exact {@value #LINE_LENGTH}-byte
     * {@code REPORT-ACCOUNT-TOTALS} record: the {@code 'Account Total'} label left-justified in its
     * 13-byte field, the 84-character dot leader, and the forced-sign edited subtotal. The report
     * writer pads the result to the 133-byte report record width.
     *
     * @return the fixed-width account-total line, exactly {@value #LINE_LENGTH} characters long
     */
    public String toReportLine() {
        return LABEL + " ".repeat(LABEL_WIDTH - LABEL.length())
                + LEADER_DOTS
                + ReportAmountFormatter.formatForcedSign(reptAccountTotal);
    }
}
