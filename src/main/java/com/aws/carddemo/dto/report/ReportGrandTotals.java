package com.aws.carddemo.dto.report;

import java.math.BigDecimal;

/**
 * Grand-total line of the AWS CardDemo daily transaction report.
 *
 * <p><strong>Origin: legacy/cpy/CVTRA07Y.cpy ({@code REPORT-GRAND-TOTALS}).</strong>
 * This is a 1:1 translation of the {@code REPORT-GRAND-TOTALS} {@code 01}-level group in copybook
 * {@code CVTRA07Y}, the final report-wide total line printed by the daily transaction report writer
 * ({@code batch/TransactionReportJobConfig}, migrated from COBOL program {@code CBTRN03C}). The
 * original COBOL layout is a fixed-width, <strong>112-byte</strong> group:
 *
 * <pre>
 *   FILLER             PIC X(11) VALUE 'Grand Total' (bytes   1-11)   'Grand Total' (label)
 *   FILLER             PIC X(86) VALUE ALL '.'       (bytes  12-97)   leader dots
 *   REPT-GRAND-TOTAL   PIC +ZZZ,ZZZ,ZZZ.ZZ           (bytes  98-112)  variable (edited)
 *   ---------------------------------------------------------------------------
 *   total                                                          112 bytes
 * </pre>
 *
 * <h2>Modeling notes</h2>
 * <ul>
 *   <li>The only runtime-variable field is {@link #reptGrandTotal}; it is a {@link BigDecimal}
 *       (never floating point, per AAP 0.6.1) edited with the forced-sign {@code +ZZZ,ZZZ,ZZZ.ZZ}
 *       mask by {@link ReportAmountFormatter#formatForcedSign(BigDecimal)}.</li>
 *   <li>The fixed {@code FILLER} literals (the {@code 'Grand Total'} label, which exactly fills its
 *       11-byte field, and the run of leader dots) are COBOL {@code VALUE} constants and are modeled
 *       as immutable {@code String} constants, reproduced verbatim by {@link #toReportLine()}.</li>
 *   <li>This class does not override {@code toString()}; it inherits the non-sensitive
 *       {@code Object} representation so amounts cannot leak into logs (CWE-532). Report content is
 *       produced only through {@link #toReportLine()}.</li>
 *   <li>The 112-byte group is right-padded with spaces to the 133-byte report record width by the
 *       report writer, consistent with {@link TransactionReportHeaders}.</li>
 * </ul>
 */
public class ReportGrandTotals {

    /** Exact fixed-width length, in bytes, of the {@code REPORT-GRAND-TOTALS} group. */
    public static final int LINE_LENGTH = 112;

    /** {@code FILLER PIC X(11) VALUE 'Grand Total'}: fixed label (exactly fills the 11-byte field). */
    public static final String LABEL = "Grand Total";

    /** Fixed width, in bytes, of the {@link #LABEL} field ({@code PIC X(11)}). */
    public static final int LABEL_WIDTH = 11;

    /** {@code FILLER PIC X(86) VALUE ALL '.'}: fixed 86-character leader of dots. */
    public static final String LEADER_DOTS = ".".repeat(86);

    /** {@code REPT-GRAND-TOTAL PIC +ZZZ,ZZZ,ZZZ.ZZ}: report-wide grand-total amount (edited on output). */
    private BigDecimal reptGrandTotal;

    /**
     * Creates an empty grand-total line. {@link #reptGrandTotal} defaults to {@code null}, which
     * renders as the all-zero forced-sign edited amount.
     */
    public ReportGrandTotals() {
    }

    /**
     * Creates a grand-total line for the supplied total.
     *
     * @param reptGrandTotal the report-wide grand-total amount ({@code REPT-GRAND-TOTAL}); may be
     *                       {@code null}
     */
    public ReportGrandTotals(BigDecimal reptGrandTotal) {
        this.reptGrandTotal = reptGrandTotal;
    }

    /**
     * Returns the report-wide grand-total amount ({@code REPT-GRAND-TOTAL}).
     *
     * @return the grand total, or {@code null} if unset
     */
    public BigDecimal getReptGrandTotal() {
        return reptGrandTotal;
    }

    /**
     * Sets the report-wide grand-total amount ({@code REPT-GRAND-TOTAL}).
     *
     * @param reptGrandTotal the grand total; may be {@code null}
     */
    public void setReptGrandTotal(BigDecimal reptGrandTotal) {
        this.reptGrandTotal = reptGrandTotal;
    }

    /**
     * Returns the grand total edited with the COBOL {@code +ZZZ,ZZZ,ZZZ.ZZ} mask, exactly as it
     * prints on the grand-total line ({@code REPT-GRAND-TOTAL}).
     *
     * @return the 15-character edited amount (see {@link ReportAmountFormatter#formatForcedSign})
     */
    public String getFormattedTotal() {
        return ReportAmountFormatter.formatForcedSign(reptGrandTotal);
    }

    /**
     * Renders this grand-total line as the exact {@value #LINE_LENGTH}-byte {@code REPORT-GRAND-TOTALS}
     * record: the {@code 'Grand Total'} label left-justified in its 11-byte field, the 86-character
     * dot leader, and the forced-sign edited total. The report writer pads the result to the
     * 133-byte report record width.
     *
     * @return the fixed-width grand-total line, exactly {@value #LINE_LENGTH} characters long
     */
    public String toReportLine() {
        return LABEL + " ".repeat(LABEL_WIDTH - LABEL.length())
                + LEADER_DOTS
                + ReportAmountFormatter.formatForcedSign(reptGrandTotal);
    }
}
