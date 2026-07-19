package com.aws.carddemo.dto.report;

import java.math.BigDecimal;

/**
 * Page-total line of the AWS CardDemo daily transaction report.
 *
 * <p><strong>Origin: legacy/cpy/CVTRA07Y.cpy ({@code REPORT-PAGE-TOTALS}).</strong>
 * This is a 1:1 translation of the {@code REPORT-PAGE-TOTALS} {@code 01}-level group in copybook
 * {@code CVTRA07Y}, the per-page subtotal line printed by the daily transaction report writer
 * ({@code batch/TransactionReportJobConfig}, migrated from COBOL program {@code CBTRN03C}). The
 * original COBOL layout is a fixed-width, <strong>112-byte</strong> group:
 *
 * <pre>
 *   FILLER            PIC X(11) VALUE 'Page Total'  (bytes   1-11)   'Page Total ' (label)
 *   FILLER            PIC X(86) VALUE ALL '.'       (bytes  12-97)   leader dots
 *   REPT-PAGE-TOTAL   PIC +ZZZ,ZZZ,ZZZ.ZZ           (bytes  98-112)  variable (edited)
 *   ---------------------------------------------------------------------------
 *   total                                                          112 bytes
 * </pre>
 *
 * <h2>Modeling notes</h2>
 * <ul>
 *   <li>The only runtime-variable field is {@link #reptPageTotal}; it is a {@link BigDecimal}
 *       (never floating point, per AAP 0.6.1) edited with the forced-sign {@code +ZZZ,ZZZ,ZZZ.ZZ}
 *       mask by {@link ReportAmountFormatter#formatForcedSign(BigDecimal)}.</li>
 *   <li>The fixed {@code FILLER} literals (the {@code 'Page Total'} label and the run of leader
 *       dots) are COBOL {@code VALUE} constants and are modeled as immutable {@code String}
 *       constants, reproduced verbatim by {@link #toReportLine()}.</li>
 *   <li>This class does not override {@code toString()}; it inherits the non-sensitive
 *       {@code Object} representation so amounts cannot leak into logs (CWE-532). Report content is
 *       produced only through {@link #toReportLine()}.</li>
 *   <li>The 112-byte group is right-padded with spaces to the 133-byte report record width by the
 *       report writer, consistent with {@link TransactionReportHeaders}.</li>
 * </ul>
 */
public class ReportPageTotals {

    /** Exact fixed-width length, in bytes, of the {@code REPORT-PAGE-TOTALS} group. */
    public static final int LINE_LENGTH = 112;

    /** {@code FILLER PIC X(11) VALUE 'Page Total'}: fixed label (10 chars, printed in an 11-byte field). */
    public static final String LABEL = "Page Total";

    /** Fixed width, in bytes, of the {@link #LABEL} field ({@code PIC X(11)}). */
    public static final int LABEL_WIDTH = 11;

    /** {@code FILLER PIC X(86) VALUE ALL '.'}: fixed 86-character leader of dots. */
    public static final String LEADER_DOTS = ".".repeat(86);

    /** {@code REPT-PAGE-TOTAL PIC +ZZZ,ZZZ,ZZZ.ZZ}: page subtotal amount (edited on output). */
    private BigDecimal reptPageTotal;

    /**
     * Creates an empty page-total line. {@link #reptPageTotal} defaults to {@code null}, which
     * renders as the all-zero forced-sign edited amount.
     */
    public ReportPageTotals() {
    }

    /**
     * Creates a page-total line for the supplied subtotal.
     *
     * @param reptPageTotal the page subtotal amount ({@code REPT-PAGE-TOTAL}); may be {@code null}
     */
    public ReportPageTotals(BigDecimal reptPageTotal) {
        this.reptPageTotal = reptPageTotal;
    }

    /**
     * Returns the page subtotal amount ({@code REPT-PAGE-TOTAL}).
     *
     * @return the page subtotal, or {@code null} if unset
     */
    public BigDecimal getReptPageTotal() {
        return reptPageTotal;
    }

    /**
     * Sets the page subtotal amount ({@code REPT-PAGE-TOTAL}).
     *
     * @param reptPageTotal the page subtotal; may be {@code null}
     */
    public void setReptPageTotal(BigDecimal reptPageTotal) {
        this.reptPageTotal = reptPageTotal;
    }

    /**
     * Returns the page subtotal edited with the COBOL {@code +ZZZ,ZZZ,ZZZ.ZZ} mask, exactly as it
     * prints on the page-total line ({@code REPT-PAGE-TOTAL}).
     *
     * @return the 15-character edited amount (see {@link ReportAmountFormatter#formatForcedSign})
     */
    public String getFormattedTotal() {
        return ReportAmountFormatter.formatForcedSign(reptPageTotal);
    }

    /**
     * Renders this page-total line as the exact {@value #LINE_LENGTH}-byte {@code REPORT-PAGE-TOTALS}
     * record: the {@code 'Page Total'} label left-justified in its 11-byte field, the 86-character
     * dot leader, and the forced-sign edited subtotal. The report writer pads the result to the
     * 133-byte report record width.
     *
     * @return the fixed-width page-total line, exactly {@value #LINE_LENGTH} characters long
     */
    public String toReportLine() {
        return LABEL + " ".repeat(LABEL_WIDTH - LABEL.length())
                + LEADER_DOTS
                + ReportAmountFormatter.formatForcedSign(reptPageTotal);
    }
}
