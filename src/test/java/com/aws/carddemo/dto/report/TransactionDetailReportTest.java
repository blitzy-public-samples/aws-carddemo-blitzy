package com.aws.carddemo.dto.report;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;

import org.junit.jupiter.api.Test;

/**
 * Pure unit tests for {@link TransactionDetailReport}, the per-transaction detail-line DTO of the
 * AWS CardDemo daily transaction report.
 *
 * <p>Origin oracle: legacy/cpy/CVTRA07Y.cpy (TRANSACTION-DETAIL-REPORT).
 *
 * <p>{@code TransactionDetailReport} is a 1:1 translation of the {@code TRANSACTION-DETAIL-REPORT}
 * {@code 01}-level group in copybook {@code CVTRA07Y} (a fixed-width, 114-byte group representing a
 * single printed transaction row). These tests verify that the eight runtime-variable data fields
 * round-trip faithfully through their JavaBean accessors, and that the derived
 * {@link TransactionDetailReport#getFormattedAmount()} accessor delegates to the shared
 * {@code -ZZZ,ZZZ,ZZZ.ZZ} edit mask in {@link ReportAmountFormatter#formatSigned(BigDecimal)}.
 *
 * <p>Two migration-fidelity points are pinned explicitly:
 * <ul>
 *   <li>{@code TRAN-REPORT-CAT-CD PIC 9(04)} maps to a numeric primitive {@code int} (not a
 *       {@code String}); the {@code cat_cd_is_numeric_int_type} case documents that mapping.</li>
 *   <li>{@code TRAN-REPORT-AMT} is kept as a {@link BigDecimal} (never floating point, per AAP
 *       0.6.1); it is formatted only at the boundary through {@code getFormattedAmount()}, and its
 *       stored scale is preserved.</li>
 * </ul>
 *
 * <p>The COBOL {@code FILLER} bytes (the two literal {@code '-'} separators and the space fillers)
 * are cosmetic column dividers applied by the report writer, not data; the DTO deliberately does
 * not expose them, so this pure unit test asserts field round-trips and delegated formatting only.
 *
 * <p>This is a pure unit test: it exercises the DTO in complete isolation with no application
 * context, no database, and no container runtime, and runs under Surefire in milliseconds.
 */
class TransactionDetailReportTest {

    /**
     * All eight runtime-variable data fields round-trip through their JavaBean accessor pairs on a
     * no-arg-constructed instance. Text columns are stored and returned verbatim; the category code
     * is a numeric {@code int}; the amount is a {@link BigDecimal} compared by value.
     */
    @Test
    void all_data_fields_round_trip_via_getters_and_setters() {
        TransactionDetailReport line = new TransactionDetailReport();
        line.setTranReportTransId("0000000000000123");
        line.setTranReportAccountId("00000000001");
        line.setTranReportTypeCd("01");
        line.setTranReportTypeDesc("Purchase");
        line.setTranReportCatCd(5);
        line.setTranReportCatDesc("Retail");
        line.setTranReportSource("POS");
        line.setTranReportAmt(new BigDecimal("1234.56"));

        assertThat(line.getTranReportTransId()).isEqualTo("0000000000000123");
        assertThat(line.getTranReportAccountId()).isEqualTo("00000000001");
        assertThat(line.getTranReportTypeCd()).isEqualTo("01");
        assertThat(line.getTranReportTypeDesc()).isEqualTo("Purchase");
        assertThat(line.getTranReportCatCd()).isEqualTo(5);
        assertThat(line.getTranReportCatDesc()).isEqualTo("Retail");
        assertThat(line.getTranReportSource()).isEqualTo("POS");
        assertThat(line.getTranReportAmt()).isEqualByComparingTo("1234.56");
    }

    /**
     * {@code TRAN-REPORT-CAT-CD PIC 9(04)} maps to a numeric primitive {@code int}. Setting a
     * four-digit value and reading it back proves the numeric mapping; a common migration slip is
     * to model this category code as a {@code String}, which this case guards against.
     */
    @Test
    void cat_cd_is_numeric_int_type() {
        TransactionDetailReport line = new TransactionDetailReport();
        line.setTranReportCatCd(9999);
        assertThat(line.getTranReportCatCd()).isEqualTo(9999);
    }

    /**
     * {@code getFormattedAmount()} delegates to the {@code -ZZZ,ZZZ,ZZZ.ZZ} signed detail mask: a
     * positive amount is comma-grouped, right-justified, and carries a leading space in the fixed
     * sign position, producing exactly 15 characters.
     */
    @Test
    void formatted_amount_delegates_to_signed_mask_for_positive() {
        TransactionDetailReport line = new TransactionDetailReport();
        line.setTranReportAmt(new BigDecimal("1234.56"));
        assertThat(line.getFormattedAmount()).isEqualTo(" ".repeat(7) + "1,234.56").hasSize(15);
    }

    /**
     * A negative amount is edited with a leading {@code '-'} in the fixed sign position, still
     * producing exactly 15 characters, confirming delegation to the signed mask.
     */
    @Test
    void formatted_amount_is_negative_signed_and_15_chars() {
        TransactionDetailReport line = new TransactionDetailReport();
        line.setTranReportAmt(new BigDecimal("-50.00"));
        assertThat(line.getFormattedAmount())
                .isEqualTo("-" + " ".repeat(9) + "50.00")
                .startsWith("-")
                .hasSize(15);
    }

    /**
     * A {@code null} amount is null-guarded by the formatter and edits to 15 spaces (the COBOL
     * all-zero blanking rule) without throwing.
     */
    @Test
    void formatted_amount_null_amount_is_15_spaces() {
        TransactionDetailReport line = new TransactionDetailReport();
        assertThat(line.getFormattedAmount()).isEqualTo(" ".repeat(15));
    }

    /**
     * A zero amount blanks the entire numeric portion (including the decimal point) to 15 spaces,
     * reproducing the IBM Enterprise COBOL all-{@code Z} picture rule for the signed mask.
     */
    @Test
    void formatted_amount_zero_is_blanked() {
        TransactionDetailReport line = new TransactionDetailReport();
        line.setTranReportAmt(new BigDecimal("0.00"));
        assertThat(line.getFormattedAmount()).isEqualTo(" ".repeat(15));
    }

    /**
     * The amount is stored as provided, so a scale-2 value keeps its scale of two, documenting the
     * decimal-fidelity requirement (AAP 0.6.1) that monetary values are exact {@link BigDecimal}s
     * rather than floating-point approximations.
     */
    @Test
    void amount_retains_scale_two() {
        TransactionDetailReport line = new TransactionDetailReport();
        line.setTranReportAmt(new BigDecimal("10.00"));
        assertThat(line.getTranReportAmt().scale()).isEqualTo(2);
    }
}
