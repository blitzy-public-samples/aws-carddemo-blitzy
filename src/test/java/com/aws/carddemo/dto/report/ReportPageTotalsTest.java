package com.aws.carddemo.dto.report;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;

import org.junit.jupiter.api.Test;

/**
 * Pure unit tests for {@link ReportPageTotals}, the per-page subtotal line DTO of the daily
 * transaction report.
 *
 * <p>Origin oracle: legacy/cpy/CVTRA07Y.cpy (REPORT-PAGE-TOTALS).
 *
 * <p>{@code ReportPageTotals} is a 1:1 translation of the {@code REPORT-PAGE-TOTALS}
 * {@code 01}-level group in copybook {@code CVTRA07Y} - a fixed-width, 112-byte record composed of
 * an {@code X(11)} {@code 'Page Total'} label, an {@code X(86)} run of leader dots, and the
 * runtime-variable {@code REPT-PAGE-TOTAL PIC +ZZZ,ZZZ,ZZZ.ZZ} amount. These tests pin the two
 * fixed COBOL {@code VALUE} literals (modeled as {@code public static final String} constants) and
 * their byte widths, verify the amount round-trips faithfully through its {@link java.math.BigDecimal}
 * accessor pair, and lock the derived {@link ReportPageTotals#getFormattedTotal()} accessor to the
 * forced-sign {@code +ZZZ,ZZZ,ZZZ.ZZ} edit mask produced by
 * {@link ReportAmountFormatter#formatForcedSign(BigDecimal)}. The forced {@code '+'} on
 * zero/positive totals is intentional legacy behavior and is asserted explicitly, not treated as a
 * defect (AAP 0.2.2 - no behavioral changes).
 *
 * <p>This is a pure unit test: it exercises the DTO in complete isolation with no application
 * context, no database, and no container runtime, and runs under Surefire ({@code *Test}) in
 * milliseconds. Every amount is created with the {@link BigDecimal} string constructor (never a
 * binary floating-point primitive), matching the decimal-fidelity rule (AAP 0.6.1), and every
 * expected edited amount is written as a {@code " ".repeat(n)} composition so the leading-space
 * counts stay auditable against the verified oracle.
 */
class ReportPageTotalsTest {

    /**
     * {@code FILLER PIC X(11) VALUE 'Page Total'}: the fixed page-total label literal is preserved
     * exactly. Its length is 10 characters; the report writer prints it left-justified in the
     * 11-byte {@code X(11)} field.
     */
    @Test
    void label_is_page_total() {
        assertThat(ReportPageTotals.LABEL).isEqualTo("Page Total").hasSize(10);
    }

    /**
     * {@code FILLER PIC X(86) VALUE ALL '.'}: the fixed dot leader is exactly 86 characters wide,
     * bridging the label to the right-aligned amount column so the page-total line stays
     * byte-faithful to the COBOL layout.
     */
    @Test
    void leader_is_86_dots() {
        assertThat(ReportPageTotals.LEADER_DOTS).isEqualTo(".".repeat(86)).hasSize(86);
    }

    /**
     * Every character of the leader is a period; the {@code VALUE ALL '.'} clause admits no other
     * character.
     */
    @Test
    void leader_contains_only_dot_characters() {
        assertThat(ReportPageTotals.LEADER_DOTS.chars().allMatch(c -> c == '.')).isTrue();
    }

    /**
     * The native {@code REPORT-PAGE-TOTALS} group is exactly 112 bytes
     * ({@code X(11) + X(86) + the 15-character edited amount}); this width is published as
     * {@link ReportPageTotals#LINE_LENGTH} for the report writer.
     */
    @Test
    void line_length_constant_is_112() {
        assertThat(ReportPageTotals.LINE_LENGTH).isEqualTo(112);
    }

    /**
     * The label occupies an {@code X(11)} field; {@link ReportPageTotals#LABEL_WIDTH} pins that
     * fixed field width so the one trailing pad space after {@code 'Page Total'} is reproduced.
     */
    @Test
    void label_width_constant_is_11() {
        assertThat(ReportPageTotals.LABEL_WIDTH).isEqualTo(11);
    }

    /**
     * {@code REPT-PAGE-TOTAL} round-trips through its JavaBean accessor pair on a
     * no-arg-constructed instance. The stored {@link BigDecimal} is compared by value so scale
     * differences never mask an equality regression.
     */
    @Test
    void page_total_round_trips_via_getter_setter() {
        ReportPageTotals totals = new ReportPageTotals();
        totals.setReptPageTotal(new BigDecimal("1234.56"));
        assertThat(totals.getReptPageTotal()).isEqualByComparingTo("1234.56");
    }

    /**
     * A freshly no-arg-constructed line leaves {@code REPT-PAGE-TOTAL} {@code null} before the batch
     * writer populates the subtotal; a null amount later renders as the all-zero forced-sign edited
     * value.
     */
    @Test
    void new_instance_has_null_page_total_by_default() {
        ReportPageTotals totals = new ReportPageTotals();
        assertThat(totals.getReptPageTotal()).isNull();
    }

    /**
     * The convenience constructor populates {@code REPT-PAGE-TOTAL}; the value is stored unchanged
     * and returned by its getter (compared by value).
     */
    @Test
    void convenience_constructor_sets_page_total() {
        ReportPageTotals totals = new ReportPageTotals(new BigDecimal("1234.56"));
        assertThat(totals.getReptPageTotal()).isEqualByComparingTo("1234.56");
    }

    /**
     * A positive subtotal edits with the forced {@code '+'} sign in the fixed leading position,
     * comma grouping, and two decimals - exactly 15 characters wide.
     */
    @Test
    void formatted_page_total_forces_plus_for_positive() {
        ReportPageTotals totals = new ReportPageTotals();
        totals.setReptPageTotal(new BigDecimal("1234.56"));
        assertThat(totals.getFormattedTotal())
                .isEqualTo("+" + " ".repeat(6) + "1,234.56")
                .hasSize(15)
                .startsWith("+");
    }

    /**
     * A negative subtotal edits with a leading {@code '-'} in the fixed sign position; the digit
     * region is otherwise identical to the positive case - exactly 15 characters wide.
     */
    @Test
    void formatted_page_total_negative() {
        ReportPageTotals totals = new ReportPageTotals();
        totals.setReptPageTotal(new BigDecimal("-1234.56"));
        assertThat(totals.getFormattedTotal())
                .isEqualTo("-" + " ".repeat(6) + "1,234.56")
                .hasSize(15)
                .startsWith("-");
    }

    /**
     * An exactly-zero subtotal blanks the entire numeric portion (digits and decimal point) yet the
     * forced {@code '+'} control symbol still prints in the leading position - {@code '+'} followed
     * by 14 spaces, exactly 15 characters wide.
     */
    @Test
    void formatted_page_total_zero_keeps_plus_sign() {
        ReportPageTotals totals = new ReportPageTotals();
        totals.setReptPageTotal(new BigDecimal("0.00"));
        assertThat(totals.getFormattedTotal())
                .isEqualTo("+" + " ".repeat(14))
                .hasSize(15)
                .startsWith("+");
    }

    /**
     * A {@code null} subtotal is treated as zero: the accessor must not throw and edits to the same
     * forced-{@code '+'}-over-blank-digits value as an explicit zero.
     */
    @Test
    void formatted_page_total_null_keeps_plus_sign() {
        ReportPageTotals totals = new ReportPageTotals();
        assertThat(totals.getFormattedTotal())
                .isEqualTo("+" + " ".repeat(14))
                .hasSize(15)
                .startsWith("+");
    }

    /**
     * {@link ReportPageTotals#toReportLine()} composes the full fixed-width record: the
     * {@code 'Page Total'} label left-justified in its 11-byte field (one trailing pad space), the
     * 86-character dot leader, and the forced-sign edited amount - exactly
     * {@link ReportPageTotals#LINE_LENGTH} (112) characters.
     */
    @Test
    void to_report_line_composes_label_dots_and_forced_sign_amount() {
        ReportPageTotals totals = new ReportPageTotals(new BigDecimal("1234.56"));
        assertThat(totals.toReportLine())
                .hasSize(112)
                .startsWith("Page Total ")
                .contains(ReportPageTotals.LEADER_DOTS)
                .endsWith("+" + " ".repeat(6) + "1,234.56");
    }
}
