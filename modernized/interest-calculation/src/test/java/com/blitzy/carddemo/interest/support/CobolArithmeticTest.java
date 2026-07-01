/*
 * Copyright Amazon.com, Inc. or its affiliates.
 * All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License").
 * You may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND,
 * either express or implied. See the License for the specific
 * language governing permissions and limitations under the License.
 */
package com.blitzy.carddemo.interest.support;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.math.BigDecimal;
import java.math.RoundingMode;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

/**
 * Characterization / unit tests for {@link CobolArithmetic#monthlyInterest(BigDecimal, BigDecimal)},
 * locking <strong>business rule BR-09</strong> — the truncating monthly-interest formula.
 *
 * <h2>Source characterized ({@code app/cbl/CBACT04C.cbl})</h2>
 * Ported paragraph {@code 1300-COMPUTE-INTEREST} at
 * {@code app/cbl/CBACT04C.cbl:L462-470}:
 * <pre>
 * 1300-COMPUTE-INTEREST.
 *     COMPUTE WS-MONTHLY-INT
 *      = ( TRAN-CAT-BAL * DIS-INT-RATE) / 1200      *&gt; L464-465  — NO ROUNDED clause
 *     ADD WS-MONTHLY-INT  TO WS-TOTAL-INT           *&gt; L467  (accumulation — the service's job)
 *     PERFORM 1300-B-WRITE-TX.                       *&gt; L468  (write — the io/service's job)
 *     EXIT.
 * </pre>
 *
 * <p>The receiving field is {@code WS-MONTHLY-INT PIC S9(09)V99}
 * ({@code app/cbl/CBACT04C.cbl:L168}) &rarr; <strong>scale 2</strong>. Because the
 * {@code COMPUTE} carries <strong>no {@code ROUNDED} clause</strong>, COBOL
 * <em>truncates</em> the exact quotient to the receiving field's scale — it drops,
 * it does not round, the third and later fractional digits. The Java port therefore
 * MUST use {@link RoundingMode#DOWN} at scale 2 — <strong>NEVER</strong>
 * {@link RoundingMode#HALF_UP} and <strong>NEVER</strong> {@link RoundingMode#HALF_EVEN}.
 * The divisor {@code 1200} = 12 months &times; 100, because {@code DIS-INT-RATE} is an
 * annual percentage (for example {@code 12.50} meaning 12.5%).</p>
 *
 * <p>These tests PROVE truncation rather than rounding: for each half-way / boundary
 * vector the independently computed {@code HALF_EVEN}/{@code HALF_UP} value is asserted
 * to <em>differ</em> from the production {@code DOWN} result. They are self-contained
 * (inline literals only — no file I/O, no fixtures, no network) so {@code mvn test}
 * runs with zero external resources (AAP §0.7: correctness via tests + static reasoning,
 * never a live system).</p>
 *
 * <p>Scope note: {@link CobolArithmetic} performs neither the accumulation
 * ({@code ADD ... TO WS-TOTAL-INT}, L467 — service layer) nor the transaction write
 * ({@code PERFORM 1300-B-WRITE-TX}, L468 — io/service layer); those are not exercised
 * here. This class is in the same package as {@link CobolArithmetic}, so the utility is
 * called directly with no import.</p>
 */
@DisplayName("CobolArithmetic.monthlyInterest — BR-09 truncating interest (CBACT04C L462-470)")
public class CobolArithmeticTest {

    /**
     * The COBOL divisor {@code 1200} from {@code CBACT04C:L465} ({@code ... / 1200}).
     * Used ONLY to compute the would-be rounded ({@code HALF_EVEN}/{@code HALF_UP})
     * values independently of the production code, so the divergence proofs cannot be
     * accidentally satisfied by reusing the production divide.
     */
    private static final BigDecimal DIVISOR_1200 = new BigDecimal("1200");

    /**
     * Independent reference computation of {@code (balance * rate) / 1200} rounded with
     * {@link RoundingMode#HALF_EVEN} at scale 2 — the value the port would produce if it
     * (incorrectly) rounded. Deliberately does NOT call {@link CobolArithmetic}.
     */
    private static BigDecimal halfEven(final BigDecimal balance, final BigDecimal rate) {
        return balance.multiply(rate).divide(DIVISOR_1200, 2, RoundingMode.HALF_EVEN);
    }

    /**
     * Independent reference computation of {@code (balance * rate) / 1200} rounded with
     * {@link RoundingMode#HALF_UP} at scale 2 — the other would-be rounded value.
     * Deliberately does NOT call {@link CobolArithmetic}.
     */
    private static BigDecimal halfUp(final BigDecimal balance, final BigDecimal rate) {
        return balance.multiply(rate).divide(DIVISOR_1200, 2, RoundingMode.HALF_UP);
    }

    // ---------------------------------------------------------------------
    // Phase 3 — truncation happy-path vectors (verified against BigDecimal).
    // Each asserts the exact scale-2 value AND that the scale is exactly 2,
    // matching WS-MONTHLY-INT PIC S9(09)V99 (app/cbl/CBACT04C.cbl:L168).
    // ---------------------------------------------------------------------

    @Test
    @DisplayName("100.00 × 12.55 / 1200 = 1.04583… truncates DOWN to 1.04 (not 1.05)")
    void primaryTruncationBoundary() {
        // BR-09 — CBACT04C 1300-COMPUTE-INTEREST L464-465 (no ROUNDED => truncate DOWN).
        // 100.00 × 12.55 = 1255.0000; ÷ 1200 = 1.045833…; DOWN @ scale 2 = 1.04.
        // (HALF_UP/HALF_EVEN would yield 1.05 — which would be WRONG for this port.)
        BigDecimal result = CobolArithmetic.monthlyInterest(new BigDecimal("100.00"), new BigDecimal("12.55"));
        assertEquals(new BigDecimal("1.04"), result,
                "no-ROUNDED COMPUTE must truncate 1.045833… to 1.04 (CBACT04C:L464-465)");
        // WS-MONTHLY-INT PIC S9(09)V99 => scale 2 (CBACT04C:L168).
        assertEquals(2, result.scale(), "result scale must be exactly 2 (CBACT04C:L168)");
    }

    @Test
    @DisplayName("0.00 balance × 15.00 rate = 0.00 (feeds BR-07: rate≠0 still emits 0.00)")
    void zeroBalanceNonZeroRateYieldsZero() {
        // BR-09 — CBACT04C 1300-COMPUTE-INTEREST L464-465.
        // A zero balance with a non-zero rate still produces 0.00 at scale 2; this is the
        // amount BR-07 (write guard tests the rate, not the balance) later writes as a 0.00 txn.
        BigDecimal result = CobolArithmetic.monthlyInterest(new BigDecimal("0.00"), new BigDecimal("15.00"));
        assertEquals(new BigDecimal("0.00"), result,
                "0 balance yields 0.00 at scale 2 (CBACT04C:L464-465)");
        // WS-MONTHLY-INT PIC S9(09)V99 => scale 2 (CBACT04C:L168).
        assertEquals(2, result.scale(), "result scale must be exactly 2 (CBACT04C:L168)");
    }

    @Test
    @DisplayName("1000.00 × 15.00 / 1200 = 12.50 exactly (clean quotient, scale normalized to 2)")
    void exactCleanQuotient() {
        // BR-09 — CBACT04C 1300-COMPUTE-INTEREST L464-465.
        // 1000.00 × 15.00 = 15000.0000; ÷ 1200 = 12.5 exactly => 12.50 at scale 2.
        BigDecimal result = CobolArithmetic.monthlyInterest(new BigDecimal("1000.00"), new BigDecimal("15.00"));
        assertEquals(new BigDecimal("12.50"), result,
                "exact quotient 12.5 is represented at scale 2 as 12.50 (CBACT04C:L464-465)");
        // WS-MONTHLY-INT PIC S9(09)V99 => scale 2 (CBACT04C:L168).
        assertEquals(2, result.scale(), "result scale must be exactly 2 (CBACT04C:L168)");
    }

    @Test
    @DisplayName("194.00 × 25.00 / 1200 = 4.041666… truncates DOWN to 4.04")
    void fixtureDerivedRate() {
        // BR-09 — CBACT04C 1300-COMPUTE-INTEREST L464-465 (no ROUNDED => truncate DOWN).
        // 194.00 × 25.00 = 4850.0000; ÷ 1200 = 4.041666…; DOWN @ scale 2 = 4.04.
        // This vector is NON-divergent (HALF_EVEN/HALF_UP also give 4.04) — happy-path only.
        BigDecimal result = CobolArithmetic.monthlyInterest(new BigDecimal("194.00"), new BigDecimal("25.00"));
        assertEquals(new BigDecimal("4.04"), result,
                "4.041666… truncates to 4.04 (CBACT04C:L464-465)");
        // WS-MONTHLY-INT PIC S9(09)V99 => scale 2 (CBACT04C:L168).
        assertEquals(2, result.scale(), "result scale must be exactly 2 (CBACT04C:L168)");
    }

    @Test
    @DisplayName("100.00 × 1.00 / 1200 = 0.08333… (non-terminating) truncates to 0.08 without throwing")
    void nonTerminatingQuotientDoesNotThrow() {
        // BR-09 — CBACT04C 1300-COMPUTE-INTEREST L464-465.
        // 100.00 × 1.00 = 100.0000; ÷ 1200 = 0.083333… (non-terminating). The production
        // 3-arg divide(divisor, 2, DOWN) truncates to 0.08 and must NOT raise ArithmeticException
        // (an exact 2-arg divide would). This locks the "scaled divide, not exact divide" choice.
        BigDecimal result = assertDoesNotThrow(
                () -> CobolArithmetic.monthlyInterest(new BigDecimal("100.00"), new BigDecimal("1.00")),
                "non-terminating quotient must not throw (CBACT04C:L464-465)");
        assertEquals(new BigDecimal("0.08"), result,
                "0.083333… truncates DOWN to 0.08 (CBACT04C:L464-465)");
        // WS-MONTHLY-INT PIC S9(09)V99 => scale 2 (CBACT04C:L168).
        assertEquals(2, result.scale(), "result scale must be exactly 2 (CBACT04C:L168)");
    }

    // ---------------------------------------------------------------------
    // Phase 4 — divergence proofs: production DOWN must DIFFER from HALF_EVEN
    // and HALF_UP. This is the heart of BR-09: it proves truncation, not
    // rounding. Only genuinely half-way / divergent vectors are used here.
    // ---------------------------------------------------------------------

    @Test
    @DisplayName("Divergence: (100.00, 12.55) DOWN=1.04 ≠ HALF=1.05")
    void divergesFromRounding_100_1255() {
        // BR-09 — CBACT04C 1300-COMPUTE-INTEREST L464-465 (no ROUNDED => truncate DOWN, NOT HALF_*).
        BigDecimal balance = new BigDecimal("100.00");
        BigDecimal rate = new BigDecimal("12.55");
        BigDecimal down = CobolArithmetic.monthlyInterest(balance, rate);   // production (DOWN)
        BigDecimal halfEven = halfEven(balance, rate);                      // would-be rounded
        BigDecimal halfUp = halfUp(balance, rate);
        assertEquals(new BigDecimal("1.04"), down, "1.045833… truncates to 1.04 (CBACT04C:L464-465)");
        assertEquals(new BigDecimal("1.05"), halfEven, "HALF_EVEN would round to 1.05");
        assertEquals(new BigDecimal("1.05"), halfUp, "HALF_UP would round to 1.05");
        assertNotEquals(halfEven, down, "DOWN must diverge from HALF_EVEN — locks BR-09 (CBACT04C:L464-465)");
        assertNotEquals(halfUp, down, "DOWN must diverge from HALF_UP — locks BR-09 (CBACT04C:L464-465)");
    }

    @Test
    @DisplayName("Divergence: (100.00, 6.66) DOWN=0.55 ≠ HALF=0.56 (exact 0.555 half)")
    void divergesFromRounding_100_666() {
        // BR-09 — CBACT04C 1300-COMPUTE-INTEREST L464-465 (no ROUNDED => truncate DOWN, NOT HALF_*).
        // 100.00 × 6.66 = 666.0000; ÷ 1200 = 0.555 exactly (a true half) => DOWN 0.55, HALF 0.56.
        BigDecimal balance = new BigDecimal("100.00");
        BigDecimal rate = new BigDecimal("6.66");
        BigDecimal down = CobolArithmetic.monthlyInterest(balance, rate);
        BigDecimal halfEven = halfEven(balance, rate);
        BigDecimal halfUp = halfUp(balance, rate);
        assertEquals(new BigDecimal("0.55"), down, "0.555 truncates to 0.55 (CBACT04C:L464-465)");
        assertEquals(new BigDecimal("0.56"), halfEven, "HALF_EVEN rounds 0.555 up to 0.56");
        assertEquals(new BigDecimal("0.56"), halfUp, "HALF_UP rounds 0.555 up to 0.56");
        assertNotEquals(halfEven, down, "DOWN must diverge from HALF_EVEN — locks BR-09 (CBACT04C:L464-465)");
        assertNotEquals(halfUp, down, "DOWN must diverge from HALF_UP — locks BR-09 (CBACT04C:L464-465)");
    }

    @Test
    @DisplayName("Divergence: (250.00, 25.00) DOWN=5.20 ≠ HALF=5.21")
    void divergesFromRounding_250_2500() {
        // BR-09 — CBACT04C 1300-COMPUTE-INTEREST L464-465 (no ROUNDED => truncate DOWN, NOT HALF_*).
        // 250.00 × 25.00 = 6250.0000; ÷ 1200 = 5.208333…; DOWN 5.20, HALF 5.21.
        BigDecimal balance = new BigDecimal("250.00");
        BigDecimal rate = new BigDecimal("25.00");
        BigDecimal down = CobolArithmetic.monthlyInterest(balance, rate);
        BigDecimal halfEven = halfEven(balance, rate);
        BigDecimal halfUp = halfUp(balance, rate);
        assertEquals(new BigDecimal("5.20"), down, "5.208333… truncates to 5.20 (CBACT04C:L464-465)");
        assertEquals(new BigDecimal("5.21"), halfEven, "HALF_EVEN would round to 5.21");
        assertEquals(new BigDecimal("5.21"), halfUp, "HALF_UP would round to 5.21");
        assertNotEquals(halfEven, down, "DOWN must diverge from HALF_EVEN — locks BR-09 (CBACT04C:L464-465)");
        assertNotEquals(halfUp, down, "DOWN must diverge from HALF_UP — locks BR-09 (CBACT04C:L464-465)");
    }

    @Test
    @DisplayName("Divergence: (-100.00, 12.55) DOWN=-1.04 (toward zero) ≠ HALF=-1.05")
    void divergesFromRounding_negativeBalanceTruncatesTowardZero() {
        // BR-09 — CBACT04C 1300-COMPUTE-INTEREST L464-465 (no ROUNDED => truncate DOWN).
        // RoundingMode.DOWN rounds toward zero, so -1.045833… truncates to -1.04 (magnitude drops),
        // whereas HALF_EVEN/HALF_UP would round the magnitude up to -1.05. This characterizes the
        // impl's documented toward-zero truncation; no extra COBOL behavior is inferred (AAP §0.7).
        BigDecimal balance = new BigDecimal("-100.00");
        BigDecimal rate = new BigDecimal("12.55");
        BigDecimal down = CobolArithmetic.monthlyInterest(balance, rate);
        BigDecimal halfEven = halfEven(balance, rate);
        BigDecimal halfUp = halfUp(balance, rate);
        assertEquals(new BigDecimal("-1.04"), down, "-1.045833… truncates toward zero to -1.04 (CBACT04C:L464-465)");
        assertEquals(new BigDecimal("-1.05"), halfEven, "HALF_EVEN would round to -1.05");
        assertEquals(new BigDecimal("-1.05"), halfUp, "HALF_UP would round to -1.05");
        assertNotEquals(halfEven, down, "DOWN must diverge from HALF_EVEN — locks BR-09 (CBACT04C:L464-465)");
        assertNotEquals(halfUp, down, "DOWN must diverge from HALF_UP — locks BR-09 (CBACT04C:L464-465)");
    }

    // ---------------------------------------------------------------------
    // Phase 5 — scale invariant: the result scale is ALWAYS exactly 2 across
    // every input, matching the receiving field WS-MONTHLY-INT PIC S9(09)V99
    // (app/cbl/CBACT04C.cbl:L168). Also re-asserts the exact truncated value.
    // ---------------------------------------------------------------------

    @ParameterizedTest(name = "[{index}] monthlyInterest({0}, {1}) = {2} at scale 2")
    @CsvSource({
            // balance,   rate,   expected DOWN (truncated) value — all verified vectors
            "100.00,   12.55,  1.04",
            "0.00,     15.00,  0.00",
            "1000.00,  15.00,  12.50",
            "194.00,   25.00,  4.04",
            "100.00,   1.00,   0.08",
            "100.00,   6.66,   0.55",
            "250.00,   25.00,  5.20",
            "-100.00,  12.55,  -1.04"
    })
    @DisplayName("Result is always exactly scale 2 and equals the truncated value")
    void resultScaleIsAlwaysTwoAndValueMatches(String balance, String rate, String expected) {
        // BR-09 — CBACT04C 1300-COMPUTE-INTEREST L464-465; receiving field WS-MONTHLY-INT
        // PIC S9(09)V99 => scale 2 (CBACT04C:L168). Every result must be exactly scale 2.
        BigDecimal result = CobolArithmetic.monthlyInterest(new BigDecimal(balance), new BigDecimal(rate));
        assertEquals(2, result.scale(),
                "result scale must be exactly 2 for WS-MONTHLY-INT S9(09)V99 (CBACT04C:L168)");
        assertEquals(new BigDecimal(expected), result,
                "truncated (DOWN) value must match (CBACT04C:L464-465)");
    }
}
