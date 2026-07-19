package com.aws.carddemo.dto.report;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;

import org.junit.jupiter.api.Test;

/**
 * Pure unit tests for {@link ReportGrandTotals}, the report-wide grand-total line
 * DTO for the daily transaction report.
 *
 * <p>Origin oracle: legacy/cpy/CVTRA07Y.cpy (REPORT-GRAND-TOTALS).
 *
 * <p>{@code ReportGrandTotals} is a 1:1 translation of the {@code REPORT-GRAND-TOTALS}
 * {@code 01}-level group in copybook {@code CVTRA07Y} (a fixed-width, 112-byte record:
 * an {@code X(11)} {@code 'Grand Total'} label that exactly fills its field, an
 * {@code X(86)} run of leader dots, and the forced-sign {@code +ZZZ,ZZZ,ZZZ.ZZ} edited
 * amount {@code REPT-GRAND-TOTAL}). These tests pin the two fixed COBOL {@code VALUE}
 * literals - modeled as {@code public static final String} constants - to their exact
 * values and byte widths, verify that the single runtime-variable amount round-trips
 * through its JavaBean accessors, and lock down the forced-sign edit mask surfaced by
 * {@link ReportGrandTotals#getFormattedTotal()} (which delegates to
 * {@link ReportAmountFormatter#formatForcedSign(BigDecimal)}).
 *
 * <p>Every amount is created with the {@link BigDecimal} string constructor (never a
 * binary floating-point primitive), matching the decimal-fidelity rule (AAP 0.6.1), and
 * every expected edited amount is written as a {@code " ".repeat(n)} composition so the
 * leading-space counts stay auditable against the verified oracle.
 *
 * <p>The counter-intuitive legacy behavior locked down here (and deliberately NOT
 * "corrected", per AAP 0.2.2): the forced-sign mask prints a leading {@code '+'} for
 * zero and positive totals, and an all-zero total blanks the entire numeric portion so
 * only the {@code '+'} sign survives. A {@code null} amount is treated as zero and must
 * not throw.
 *
 * <p>This is a pure unit test: it exercises the DTO in complete isolation with no
 * application context, no database, and no container runtime, and runs under Surefire in
 * milliseconds.
 */
class ReportGrandTotalsTest {

    /**
     * {@code FILLER PIC X(11) VALUE 'Grand Total'}: the fixed label literal is preserved
     * exactly and is 11 characters wide, so it already fills the whole {@code X(11)}
     * field (its length equals {@link ReportGrandTotals#LABEL_WIDTH}).
     */
    @Test
    void label_is_grand_total_and_11_chars() {
        assertThat(ReportGrandTotals.LABEL).isEqualTo("Grand Total").hasSize(11);
        assertThat(ReportGrandTotals.LABEL_WIDTH).isEqualTo(11);
        assertThat(ReportGrandTotals.LABEL).hasSize(ReportGrandTotals.LABEL_WIDTH);
    }

    /**
     * {@code FILLER PIC X(86) VALUE ALL '.'}: the fixed leader is exactly 86 dot
     * characters, matching the {@code X(86)} field width between the label and the edited
     * amount.
     */
    @Test
    void leader_dots_is_86_dots() {
        assertThat(ReportGrandTotals.LEADER_DOTS).isEqualTo(".".repeat(86)).hasSize(86);
    }

    /**
     * The whole {@code REPORT-GRAND-TOTALS} group is a fixed-width 112-byte record
     * ({@code 11 + 86 + 15}); {@link ReportGrandTotals#LINE_LENGTH} pins that width.
     */
    @Test
    void line_length_constant_is_112() {
        assertThat(ReportGrandTotals.LINE_LENGTH).isEqualTo(112);
    }

    /**
     * {@code REPT-GRAND-TOTAL} round-trips through the JavaBean accessor pair on a
     * no-arg-constructed instance. The stored {@link BigDecimal} is returned by value and
     * compared by numeric value (scale-insensitive) against the oracle.
     */
    @Test
    void grand_total_round_trips_via_getter_setter() {
        ReportGrandTotals totals = new ReportGrandTotals();
        totals.setReptGrandTotal(new BigDecimal("1234.56"));
        assertThat(totals.getReptGrandTotal()).isEqualByComparingTo("1234.56");
    }

    /**
     * A freshly no-arg-constructed line leaves {@code REPT-GRAND-TOTAL} {@code null}
     * until the batch report writer populates the report-wide total.
     */
    @Test
    void new_instance_has_null_grand_total_by_default() {
        ReportGrandTotals totals = new ReportGrandTotals();
        assertThat(totals.getReptGrandTotal()).isNull();
    }

    /**
     * A positive total is edited with the forced-sign {@code +ZZZ,ZZZ,ZZZ.ZZ} mask: a
     * leading {@code '+'}, comma grouping, and exactly 15 characters.
     */
    @Test
    void formatted_grand_total_forces_plus_for_positive() {
        ReportGrandTotals totals = new ReportGrandTotals();
        totals.setReptGrandTotal(new BigDecimal("1234.56"));
        assertThat(totals.getFormattedTotal())
                .isEqualTo("+" + " ".repeat(6) + "1,234.56")
                .hasSize(15)
                .startsWith("+");
    }

    /**
     * A negative total uses a leading {@code '-'} in the fixed sign position while the
     * magnitude is edited identically to the positive case.
     */
    @Test
    void formatted_grand_total_negative() {
        ReportGrandTotals totals = new ReportGrandTotals();
        totals.setReptGrandTotal(new BigDecimal("-1234.56"));
        assertThat(totals.getFormattedTotal())
                .isEqualTo("-" + " ".repeat(6) + "1,234.56")
                .hasSize(15)
                .startsWith("-");
    }

    /**
     * An all-zero total blanks the entire numeric portion (digits and decimal point) yet
     * the forced {@code '+'} sign still prints - the deliberately preserved legacy edit
     * behavior (AAP 0.2.2).
     */
    @Test
    void formatted_grand_total_zero_keeps_plus_sign() {
        ReportGrandTotals totals = new ReportGrandTotals();
        totals.setReptGrandTotal(new BigDecimal("0.00"));
        assertThat(totals.getFormattedTotal())
                .isEqualTo("+" + " ".repeat(14))
                .hasSize(15)
                .startsWith("+");
    }

    /**
     * A larger positive total exercises comma grouping in the integer region while still
     * fitting the fixed 15-character field.
     */
    @Test
    void formatted_grand_total_large_value_groups_commas() {
        ReportGrandTotals totals = new ReportGrandTotals();
        totals.setReptGrandTotal(new BigDecimal("12345.00"));
        assertThat(totals.getFormattedTotal())
                .isEqualTo("+" + " ".repeat(5) + "12,345.00")
                .hasSize(15)
                .startsWith("+");
    }

    /**
     * A {@code null} total is treated as zero: the accessor must not throw and still
     * prints the forced {@code '+'} over an otherwise blank field.
     */
    @Test
    void formatted_grand_total_null_keeps_plus_sign() {
        ReportGrandTotals totals = new ReportGrandTotals();
        assertThat(totals.getFormattedTotal())
                .isEqualTo("+" + " ".repeat(14))
                .hasSize(15)
                .startsWith("+");
    }

    /**
     * The convenience constructor seeds {@code REPT-GRAND-TOTAL}; the value round-trips
     * through its getter and drives the forced-sign edited output identically to the
     * setter path.
     */
    @Test
    void convenience_constructor_sets_grand_total() {
        ReportGrandTotals totals = new ReportGrandTotals(new BigDecimal("1234.56"));
        assertThat(totals.getReptGrandTotal()).isEqualByComparingTo("1234.56");
        assertThat(totals.getFormattedTotal()).isEqualTo("+" + " ".repeat(6) + "1,234.56");
    }

    /**
     * {@link ReportGrandTotals#toReportLine()} reproduces the exact 112-byte
     * {@code REPORT-GRAND-TOTALS} record: the {@code 'Grand Total'} label (already filling
     * its 11-byte field), the 86-character dot leader, and the forced-sign edited total,
     * for a combined {@link ReportGrandTotals#LINE_LENGTH} width.
     */
    @Test
    void to_report_line_reproduces_112_byte_layout() {
        ReportGrandTotals totals = new ReportGrandTotals(new BigDecimal("1234.56"));
        assertThat(totals.toReportLine())
                .isEqualTo("Grand Total" + ReportGrandTotals.LEADER_DOTS + "+" + " ".repeat(6) + "1,234.56")
                .hasSize(ReportGrandTotals.LINE_LENGTH)
                .startsWith("Grand Total");
    }
}
