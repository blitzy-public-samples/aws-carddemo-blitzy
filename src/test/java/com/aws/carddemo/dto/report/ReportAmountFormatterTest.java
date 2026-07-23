package com.aws.carddemo.dto.report;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link ReportAmountFormatter}. Origin oracle: legacy/cpy/CVTRA07Y.cpy
 * (the {@code TRAN-REPORT-AMT} detail-line amount mask {@code -ZZZ,ZZZ,ZZZ.ZZ} and the
 * {@code REPT-PAGE-TOTAL} / {@code REPT-ACCOUNT-TOTAL} / {@code REPT-GRAND-TOTAL} total-line
 * amount mask {@code +ZZZ,ZZZ,ZZZ.ZZ}).
 *
 * <p>These are pure, framework-free unit tests: no application context, no database and no
 * container-backed integration harness. They pin the two COBOL numeric edit masks to their exact
 * {@value ReportAmountFormatter#WIDTH}-character output so the daily transaction report stays
 * byte-faithful in the amount column (report positions 98-112).</p>
 *
 * <p>Every expected value is written as a {@code " ".repeat(n) + digits} composition so the
 * leading-space counts stay auditable against the verified oracle table, and every amount is
 * created with the {@link BigDecimal} string constructor (never a binary floating-point
 * primitive), matching the decimal-fidelity rule (AAP 0.6.1).</p>
 *
 * <p>The counter-intuitive legacy behavior locked down here (and deliberately NOT "corrected",
 * per AAP 0.2.2): an all-zero value blanks the entire numeric portion including the decimal point,
 * the single leading sign symbol stays fixed at position one, and {@code formatForcedSign} still
 * prints {@code '+'} for a zero value even though the digits are blanked.</p>
 */
@DisplayName("ReportAmountFormatter COBOL edit-mask unit tests")
class ReportAmountFormatterTest {

    /**
     * Group A: the {@code -ZZZ,ZZZ,ZZZ.ZZ} detail-line mask
     * ({@link ReportAmountFormatter#formatSigned(BigDecimal)}). The leading sign position holds a
     * space for zero/positive values and {@code '-'} for negative values.
     */
    @Nested
    @DisplayName("formatSigned - detail mask -ZZZ,ZZZ,ZZZ.ZZ")
    class FormatSigned {

        @Test
        void positive_value_groups_with_commas_and_space_sign() {
            String result = ReportAmountFormatter.formatSigned(new BigDecimal("1234.56"));
            assertThat(result).isEqualTo(" ".repeat(7) + "1,234.56").hasSize(15);
        }

        @Test
        void negative_value_uses_leading_minus_sign() {
            String result = ReportAmountFormatter.formatSigned(new BigDecimal("-1234.56"));
            assertThat(result).isEqualTo("-" + " ".repeat(6) + "1,234.56").hasSize(15);
        }

        @Test
        void fractional_only_value_blanks_integer_region_and_keeps_two_decimals() {
            String result = ReportAmountFormatter.formatSigned(new BigDecimal("0.05"));
            assertThat(result).isEqualTo(" ".repeat(12) + ".05").hasSize(15);
        }

        @Test
        void zero_blanks_entire_numeric_portion_including_decimal_point() {
            String result = ReportAmountFormatter.formatSigned(new BigDecimal("0.00"));
            assertThat(result).isEqualTo(" ".repeat(15)).hasSize(15);
        }

        @Test
        void maximum_positive_value_prints_all_groups_with_space_sign() {
            String result = ReportAmountFormatter.formatSigned(new BigDecimal("999999999.99"));
            assertThat(result).isEqualTo(" " + "999,999,999.99").hasSize(15);
        }

        @Test
        void maximum_negative_value_prints_all_groups_with_minus_sign() {
            String result = ReportAmountFormatter.formatSigned(new BigDecimal("-999999999.99"));
            assertThat(result).isEqualTo("-" + "999,999,999.99").hasSize(15);
        }

        @Test
        void small_positive_value_has_no_comma_grouping() {
            String result = ReportAmountFormatter.formatSigned(new BigDecimal("50.00"));
            assertThat(result).isEqualTo(" ".repeat(10) + "50.00").hasSize(15);
        }

        @Test
        void null_is_treated_as_zero_and_does_not_throw() {
            // The call itself must not throw; a null amount edits to an all-blank field.
            String result = ReportAmountFormatter.formatSigned(null);
            assertThat(result).isEqualTo(" ".repeat(15)).hasSize(15);
        }
    }

    /**
     * Group B: the {@code +ZZZ,ZZZ,ZZZ.ZZ} total-line mask
     * ({@link ReportAmountFormatter#formatForcedSign(BigDecimal)}). The leading sign position holds
     * a forced {@code '+'} for zero/positive values and {@code '-'} for negative values.
     */
    @Nested
    @DisplayName("formatForcedSign - total mask +ZZZ,ZZZ,ZZZ.ZZ")
    class FormatForcedSign {

        @Test
        void positive_value_uses_forced_plus_sign() {
            String result = ReportAmountFormatter.formatForcedSign(new BigDecimal("1234.56"));
            assertThat(result).isEqualTo("+" + " ".repeat(6) + "1,234.56").hasSize(15).startsWith("+");
        }

        @Test
        void negative_value_uses_leading_minus_sign() {
            String result = ReportAmountFormatter.formatForcedSign(new BigDecimal("-1234.56"));
            assertThat(result).isEqualTo("-" + " ".repeat(6) + "1,234.56").hasSize(15).startsWith("-");
        }

        @Test
        void zero_blanks_digits_but_forced_plus_sign_still_prints() {
            String result = ReportAmountFormatter.formatForcedSign(new BigDecimal("0.00"));
            assertThat(result).isEqualTo("+" + " ".repeat(14)).hasSize(15).startsWith("+");
        }

        @Test
        void positive_value_groups_with_commas() {
            String result = ReportAmountFormatter.formatForcedSign(new BigDecimal("12345.00"));
            assertThat(result).isEqualTo("+" + " ".repeat(5) + "12,345.00").hasSize(15).startsWith("+");
        }

        @Test
        void null_is_treated_as_zero_and_prints_forced_plus_without_throwing() {
            // The call itself must not throw; the forced sign still prints '+' over blank digits.
            String result = ReportAmountFormatter.formatForcedSign(null);
            assertThat(result).isEqualTo("+" + " ".repeat(14)).hasSize(15).startsWith("+");
        }
    }

    /**
     * Group C: the fixed-field-width contract. Both masks always emit exactly
     * {@link ReportAmountFormatter#WIDTH} characters regardless of magnitude or sign, which is what
     * keeps the report line length constant across detail and total lines.
     */
    @Nested
    @DisplayName("fixed 15-character field width")
    class WidthInvariant {

        @Test
        void width_constant_is_fifteen() {
            assertThat(ReportAmountFormatter.WIDTH).isEqualTo(15);
        }

        @Test
        void both_masks_always_produce_exactly_fifteen_characters() {
            BigDecimal[] samples = {
                new BigDecimal("1234.56"),       // positive with comma grouping
                new BigDecimal("-1234.56"),      // negative with comma grouping
                new BigDecimal("0.00"),          // zero (all-blank numeric portion)
                new BigDecimal("0.05"),          // fractional only
                new BigDecimal("999999999.99"),  // maximum in-range magnitude
                new BigDecimal("-999999999.99"), // maximum negative magnitude
                new BigDecimal("50.00")          // small, no comma grouping
            };
            for (BigDecimal sample : samples) {
                assertThat(ReportAmountFormatter.formatSigned(sample)).hasSize(15);
                assertThat(ReportAmountFormatter.formatForcedSign(sample)).hasSize(15);
            }
        }
    }
}
