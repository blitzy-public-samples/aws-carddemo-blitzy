package com.aws.carddemo.dto.report;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;

import org.junit.jupiter.api.Test;

/**
 * Pure unit tests for {@link ReportAccountTotals}, the per-account subtotal line
 * DTO of the daily transaction report.
 *
 * <p>Origin oracle: legacy/cpy/CVTRA07Y.cpy (REPORT-ACCOUNT-TOTALS).
 *
 * <p>{@code ReportAccountTotals} is a 1:1 translation of the
 * {@code REPORT-ACCOUNT-TOTALS} {@code 01}-level group in copybook {@code CVTRA07Y}
 * (a fixed-width, 112-byte record):
 *
 * <pre>
 *   FILLER              PIC X(13) VALUE 'Account Total'   (bytes   1-13)  label
 *   FILLER              PIC X(84) VALUE ALL '.'           (bytes  14-97)  leader dots
 *   REPT-ACCOUNT-TOTAL  PIC +ZZZ,ZZZ,ZZZ.ZZ              (bytes  98-112) edited amount
 * </pre>
 *
 * <p>These tests pin the fixed COBOL {@code VALUE} literals - modeled as
 * {@code public static final} constants - to their exact values and byte widths,
 * verify that the runtime-variable {@code REPT-ACCOUNT-TOTAL} amount round-trips
 * through its {@code BigDecimal} JavaBean accessors, and lock down the forced-sign
 * {@code +ZZZ,ZZZ,ZZZ.ZZ} edit mask (delegated to
 * {@link ReportAmountFormatter#formatForcedSign(BigDecimal)}) for positive,
 * negative, zero and {@code null} amounts. The distinguishing fidelity check for
 * this class is the 13-character {@code 'Account Total'} label paired with the
 * 84-character dot leader, which together keep the amount in report columns
 * 98-112 exactly as on the page- and grand-total lines.
 *
 * <p>Every amount is created with the {@link BigDecimal} string constructor (never
 * a binary floating-point primitive), matching the decimal-fidelity rule
 * (AAP 0.6.1). The forced {@code '+'} printed for zero/positive values is
 * intentional legacy behavior and is deliberately NOT "corrected" (AAP 0.2.2).
 *
 * <p>This is a pure unit test: it exercises the DTO in complete isolation with no
 * application context, no database, and no container runtime, and runs under
 * Surefire in milliseconds.
 */
class ReportAccountTotalsTest {

    /**
     * {@code FILLER PIC X(13) VALUE 'Account Total'}: the fixed label literal is
     * preserved exactly and is 13 characters wide, so it fills the whole X(13)
     * field with no padding (its length is 13, not 12). This exact length is the
     * distinguishing fidelity check that separates the account-total line from the
     * 11-character page/grand-total labels.
     */
    @Test
    void label_is_account_total_and_13_chars() {
        assertThat(ReportAccountTotals.LABEL).isEqualTo("Account Total").hasSize(13);
    }

    /**
     * The declared width of the label field ({@code PIC X(13)}) is 13, matching the
     * length of the {@code 'Account Total'} literal so no trailing padding is
     * emitted when the line is rendered.
     */
    @Test
    void label_width_constant_is_13() {
        assertThat(ReportAccountTotals.LABEL_WIDTH).isEqualTo(13);
    }

    /**
     * {@code FILLER PIC X(84) VALUE ALL '.'}: the leader is exactly 84 dot
     * characters (84 here, not 86, because the 13-character label consumes two more
     * bytes than the 11-character page/grand-total labels), keeping the amount in
     * the same report columns. Asserting equality with {@code ".".repeat(84)}
     * simultaneously pins every character to {@code '.'} and the width to 84.
     */
    @Test
    void leader_is_84_dots() {
        assertThat(ReportAccountTotals.LEADER_DOTS).isEqualTo(".".repeat(84)).hasSize(84);
    }

    /**
     * The fixed-width byte length of the whole {@code REPORT-ACCOUNT-TOTALS} group
     * is 112 (13 label + 84 leader + 15 edited amount).
     */
    @Test
    void line_length_constant_is_112() {
        assertThat(ReportAccountTotals.LINE_LENGTH).isEqualTo(112);
    }

    /**
     * {@code REPT-ACCOUNT-TOTAL PIC +ZZZ,ZZZ,ZZZ.ZZ} round-trips through the
     * {@code BigDecimal} JavaBean accessor pair on a no-arg-constructed instance.
     * The stored value is compared by numeric value (scale-insensitive) via
     * {@code isEqualByComparingTo}.
     */
    @Test
    void account_total_round_trips_via_getter_setter() {
        ReportAccountTotals totals = new ReportAccountTotals();
        totals.setReptAccountTotal(new BigDecimal("1234.56"));
        assertThat(totals.getReptAccountTotal()).isEqualByComparingTo("1234.56");
    }

    /**
     * The convenience constructor populates {@code REPT-ACCOUNT-TOTAL}; the value is
     * returned unchanged by its getter.
     */
    @Test
    void convenience_constructor_sets_account_total() {
        ReportAccountTotals totals = new ReportAccountTotals(new BigDecimal("2500.00"));
        assertThat(totals.getReptAccountTotal()).isEqualByComparingTo("2500.00");
    }

    /**
     * A freshly no-arg-constructed line leaves {@code REPT-ACCOUNT-TOTAL}
     * {@code null} before the report writer populates the subtotal; a {@code null}
     * amount renders as the all-zero forced-sign edited amount.
     */
    @Test
    void new_instance_has_null_account_total_by_default() {
        ReportAccountTotals totals = new ReportAccountTotals();
        assertThat(totals.getReptAccountTotal()).isNull();
    }

    /**
     * A positive subtotal edits with a forced leading {@code '+'} and comma
     * grouping, right-justified into the 15-character forced-sign mask.
     */
    @Test
    void formatted_account_total_forces_plus_for_positive() {
        ReportAccountTotals totals = new ReportAccountTotals();
        totals.setReptAccountTotal(new BigDecimal("1234.56"));
        assertThat(totals.getFormattedTotal())
                .isEqualTo("+" + " ".repeat(6) + "1,234.56")
                .hasSize(15)
                .startsWith("+");
    }

    /**
     * A negative subtotal edits with a leading {@code '-'} in the fixed sign
     * position; the magnitude is otherwise formatted identically to the positive
     * case.
     */
    @Test
    void formatted_account_total_negative() {
        ReportAccountTotals totals = new ReportAccountTotals();
        totals.setReptAccountTotal(new BigDecimal("-1234.56"));
        assertThat(totals.getFormattedTotal())
                .isEqualTo("-" + " ".repeat(6) + "1,234.56")
                .hasSize(15)
                .startsWith("-");
    }

    /**
     * A zero subtotal blanks the entire numeric portion (including the decimal
     * point) per the IBM all-{@code Z} editing rule, yet the forced-sign mask still
     * prints {@code '+'} in the fixed sign position.
     */
    @Test
    void formatted_account_total_zero_keeps_plus_sign() {
        ReportAccountTotals totals = new ReportAccountTotals();
        totals.setReptAccountTotal(new BigDecimal("0.00"));
        assertThat(totals.getFormattedTotal())
                .isEqualTo("+" + " ".repeat(14))
                .hasSize(15);
    }

    /**
     * A {@code null} subtotal is treated as zero by the edit mask: the accessor
     * must not throw and still prints the forced {@code '+'} over blank digits.
     */
    @Test
    void formatted_account_total_null_keeps_plus_sign() {
        ReportAccountTotals totals = new ReportAccountTotals();
        assertThat(totals.getFormattedTotal())
                .isEqualTo("+" + " ".repeat(14))
                .hasSize(15);
    }

    /**
     * The whole line renders as the exact 112-byte {@code REPORT-ACCOUNT-TOTALS}
     * record: the {@code 'Account Total'} label (which fills its 13-byte field with
     * no padding), the 84-character dot leader, and the forced-sign edited subtotal
     * occupying report columns 98-112.
     */
    @Test
    void to_report_line_renders_full_112_byte_line() {
        ReportAccountTotals totals = new ReportAccountTotals(new BigDecimal("1234.56"));
        assertThat(totals.toReportLine())
                .isEqualTo("Account Total" + ".".repeat(84) + "+" + " ".repeat(6) + "1,234.56")
                .hasSize(112);
    }
}
