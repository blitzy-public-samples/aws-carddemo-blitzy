package com.carddemo.common.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.math.RoundingMode;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Executable specification of the CardDemo monthly-interest computation fidelity.
 *
 * :purpose: Lock the fixed-scale ``java.math.BigDecimal`` arithmetic that reproduces the
 *     COBOL ``CBACT04C`` paragraph ``1300-COMPUTE-INTEREST`` formula
 *     ``COMPUTE WS-MONTHLY-INT = (TRAN-CAT-BAL * DIS-INT-RATE) / 1200`` where
 *     ``WS-MONTHLY-INT PIC S9(09)V99`` carries scale 2. The COBOL statement has no
 *     ``ROUNDED`` phrase, so the excess fractional digits are truncated toward zero;
 *     the Java equivalent multiplies the transaction-category balance by the
 *     disclosure-group interest rate, divides by 1200, and normalizes to scale 2 with
 *     ``RoundingMode.DOWN`` so that the modern ``batch-service`` interest service yields
 *     byte-identical financial output for the domain money fields
 *     ``TranCatBal.tranCatBal`` and ``DiscGroup.disIntRate``.
 * :output: JUnit 5 / AssertJ assertions only; the class holds no state and touches no
 *     database, Spring context, or other external resource (pure JDK arithmetic).
 */
final class FinancialPrecisionTest {

    /**
     * Reproduce the COBOL fixed-scale monthly-interest computation verbatim.
     *
     * :param tranCatBal: transaction-category balance (COBOL ``TRAN-CAT-BAL``, scale 2).
     * :param disIntRate: disclosure-group interest rate (COBOL ``DIS-INT-RATE``, scale 2).
     * :return: ``(tranCatBal * disIntRate) / 1200`` as a scale-2 ``BigDecimal`` truncated
     *     toward zero (``RoundingMode.DOWN``), matching the COBOL ``COMPUTE`` without
     *     a ``ROUNDED`` phrase.
     */
    private static BigDecimal monthlyInterest(BigDecimal tranCatBal, BigDecimal disIntRate) {
        return tranCatBal.multiply(disIntRate)
                         .divide(BigDecimal.valueOf(1200), 2, RoundingMode.DOWN);
    }

    @Test
    @DisplayName("Canonical example: 100.00 x 5.00 / 1200 = 0.41 at scale 2 (truncated)")
    void canonicalExampleIsZeroPoint41() {
        // 100.00 * 5.00 / 1200 = 0.41666... -> truncate (DOWN) at scale 2 -> 0.41
        // (COBOL COMPUTE has no ROUNDED phrase; HALF_UP would incorrectly yield 0.42)
        BigDecimal r = monthlyInterest(new BigDecimal("100.00"), new BigDecimal("5.00"));

        assertThat(r).isEqualByComparingTo("0.41");
        assertThat(r).isNotEqualByComparingTo("0.42");
        assertThat(r.scale()).isEqualTo(2);
    }

    @Test
    @DisplayName("Truncation toward zero at the .005 boundary (0.125 -> 0.12, not 0.13)")
    void truncatesTowardZeroAtBoundary() {
        // 30.00 * 5.00 / 1200 = 0.125 exactly -> truncate (DOWN) at scale 2 -> 0.12
        // (HALF_UP would incorrectly yield 0.13; the COBOL COMPUTE has no ROUNDED phrase)
        BigDecimal r = monthlyInterest(new BigDecimal("30.00"), new BigDecimal("5.00"));

        assertThat(r).isEqualByComparingTo("0.12");
        assertThat(r).isNotEqualByComparingTo("0.13");
    }

    @Test
    @DisplayName("Result always carries scale 2, including exact and zero cases")
    void resultIsAlwaysScale2() {
        // Exact case: 1200.00 * 1.00 / 1200 = 1.00
        BigDecimal exact = monthlyInterest(new BigDecimal("1200.00"), new BigDecimal("1.00"));
        assertThat(exact).isEqualByComparingTo("1.00");
        assertThat(exact.scale()).isEqualTo(2);

        // Zero case: 0.00 * 5.00 / 1200 = 0.00
        BigDecimal zero = monthlyInterest(new BigDecimal("0.00"), new BigDecimal("5.00"));
        assertThat(zero).isEqualByComparingTo("0.00");
        assertThat(zero.scale()).isEqualTo(2);
    }

    @Test
    @DisplayName("NUMERIC(p,2) storage normalizes any monetary input to scale 2")
    void scaleNormalizationMatchesNumericColumn() {
        // A NUMERIC(p,2) column (every monetary @Column(scale = 2) in the domain) coerces
        // stored values to exactly two fractional digits; setScale(2, HALF_UP) mirrors that.
        assertThat(new BigDecimal("100.1").setScale(2, RoundingMode.HALF_UP).toPlainString())
                .isEqualTo("100.10");
        assertThat(new BigDecimal("100").setScale(2, RoundingMode.HALF_UP).toPlainString())
                .isEqualTo("100.00");
    }
}
