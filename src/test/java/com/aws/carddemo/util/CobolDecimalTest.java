/*
 * Copyright Amazon.com, Inc. or its affiliates.
 * All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License").
 * You may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND,
 * either express or implied. See the License for the specific
 * language governing permissions and limitations under the License.
 */
package com.aws.carddemo.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.math.BigDecimal;
import java.math.RoundingMode;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Parity-oracle unit tests for {@link CobolDecimal}: the single most important
 * decimal-fidelity guard in the migrated CardDemo suite.
 *
 * <p>These tests prove that COBOL fixed-scale packed-decimal ({@code COMP-3} /
 * {@code S9(n)V99}) arithmetic &mdash; which <strong>truncates toward zero</strong> (never
 * rounds) unless the COBOL statement carries an explicit {@code ROUNDED} phrase &mdash; is
 * reproduced exactly by {@link java.math.BigDecimal} with an explicit
 * {@link java.math.RoundingMode}. If a future refactor ever flips the money rounding mode
 * from {@link RoundingMode#DOWN} to {@link RoundingMode#HALF_UP}, the headline assertion
 * {@link #interestComputationTruncatesToTwoDecimals_16_65_not_16_66()} MUST fail.</p>
 *
 * <p>This is a pure, fast, deterministic JUnit&nbsp;5 unit test: it bootstraps no Spring
 * context, no database, and no Testcontainers, and it never uses {@code float}/{@code double}
 * anywhere &mdash; every {@link BigDecimal} is constructed from a {@code String}, mirroring
 * the production prohibition on floating point for monetary values. The class name ends in
 * {@code Test} so Surefire runs it in the {@code test} phase (it is not a Failsafe {@code *IT}).</p>
 *
 * <p>Parity oracle: legacy/cbl/CBACT04C.cbl 1300-COMPUTE-INTEREST
 * (WS-MONTHLY-INT PIC S9(09)V99, no ROUNDED). The canonical COBOL statement is
 * {@code COMPUTE WS-MONTHLY-INT = (TRAN-CAT-BAL * DIS-INT-RATE) / 1200}, where
 * {@code TRAN-CAT-BAL} is {@code PIC S9(09)V99}, {@code DIS-INT-RATE} is {@code PIC S9(04)V99},
 * and {@code WS-MONTHLY-INT} is {@code PIC S9(09)V99} with no {@code ROUNDED} phrase &mdash; so
 * the quotient is truncated to two decimals. Verified fact: the token {@code ROUNDED} appears
 * zero times across every program in {@code legacy/cbl/**}, so truncation is the only money
 * rounding semantic the legacy system exercises.</p>
 *
 * @see CobolDecimal
 */
@DisplayName("CobolDecimal — COBOL packed-decimal truncation vs rounding parity")
class CobolDecimalTest {

    // ---------------------------------------------------------------------------------------
    // Phase 2 — Canonical truncation-vs-rounding test (THE headline assertion)
    // ---------------------------------------------------------------------------------------

    /**
     * Headline parity assertion for {@code CBACT04C} {@code 1300-COMPUTE-INTEREST}:
     * {@code COMPUTE WS-MONTHLY-INT = (TRAN-CAT-BAL * DIS-INT-RATE) / 1200} with no
     * {@code ROUNDED} phrase truncates the quotient to two decimals.
     *
     * <p>{@code TRAN-CAT-BAL = 1000.00}, {@code DIS-INT-RATE = 19.99}. The product
     * {@code 1000.00 * 19.99 = 19990.0000} is exact; {@code 19990.0000 / 1200 = 16.658333...};
     * truncated to two decimals that is <strong>16.65</strong>, NOT the rounded 16.66. This is
     * the semantic that must never regress.</p>
     */
    @Test
    void interestComputationTruncatesToTwoDecimals_16_65_not_16_66() {
        // TRAN-CAT-BAL PIC S9(09)V99 and DIS-INT-RATE PIC S9(04)V99 - constructed from
        // Strings so no floating-point representation error can ever creep in.
        BigDecimal catBal = new BigDecimal("1000.00");
        BigDecimal intRate = new BigDecimal("19.99");

        // Reproduce the COBOL COMPUTE exactly: full-scale product, then divide by 1200
        // truncated (RoundingMode.DOWN) to the S9(09)V99 receiving-field scale of 2.
        BigDecimal monthlyInt = CobolDecimal.divide(catBal.multiply(intRate), new BigDecimal("1200"), 2);

        // Legacy-correct value is 16.65 (truncated), not 16.66 (rounded).
        assertEquals(0, monthlyInt.compareTo(new BigDecimal("16.65")));
        assertNotEquals(0, monthlyInt.compareTo(new BigDecimal("16.66")));

        // The result carries the fixed COBOL scale (V99 -> scale 2).
        assertEquals(2, monthlyInt.scale());
    }

    /**
     * Proves the truncation-vs-rounding distinction is real and meaningful on this exact input:
     * routing the same computation through {@link RoundingMode#HALF_UP} yields the
     * <em>different</em> answer 16.66. Because DOWN (legacy-correct) and HALF_UP genuinely
     * diverge here, the choice of {@link RoundingMode#DOWN} for money is a deliberate,
     * behavior-defining decision rather than an accident.
     */
    @Test
    void divideHalfUpWouldRoundTo_16_66() {
        BigDecimal catBal = new BigDecimal("1000.00");
        BigDecimal intRate = new BigDecimal("19.99");

        // Same numerator/denominator, but HALF_UP instead of the legacy DOWN.
        BigDecimal rounded = CobolDecimal.divide(
                catBal.multiply(intRate), new BigDecimal("1200"), 2, RoundingMode.HALF_UP);

        assertEquals(0, rounded.compareTo(new BigDecimal("16.66")));
    }

    // ---------------------------------------------------------------------------------------
    // Phase 3 — Direct method semantics
    // ---------------------------------------------------------------------------------------

    /**
     * {@link CobolDecimal#truncate(BigDecimal, int)} discards excess low-order digits
     * (RoundingMode.DOWN) rather than rounding: {@code 16.658333 -> 16.65}.
     */
    @Test
    void truncateUsesRoundingDown() {
        assertEquals(0, CobolDecimal.truncate(new BigDecimal("16.658333"), 2)
                .compareTo(new BigDecimal("16.65")));
        assertEquals(2, CobolDecimal.truncate(new BigDecimal("16.658333"), 2).scale());
    }

    /**
     * {@link CobolDecimal#round(BigDecimal, int)} rounds half away from zero
     * (RoundingMode.HALF_UP) - the semantic reserved for a COBOL {@code COMPUTE ... ROUNDED}.
     * No current CardDemo statement uses {@code ROUNDED}; this test locks the distinction so
     * the two helpers can never silently collapse into one behavior.
     */
    @Test
    void roundUsesHalfUp() {
        // Dropped digit 8 (>= 5) -> rounds the cents up: 16.658333 -> 16.66.
        assertEquals(0, CobolDecimal.round(new BigDecimal("16.658333"), 2)
                .compareTo(new BigDecimal("16.66")));
        // Dropped digit 4 (< 5) -> rounds down (value unchanged at two places): 16.664 -> 16.66.
        assertEquals(0, CobolDecimal.round(new BigDecimal("16.664"), 2)
                .compareTo(new BigDecimal("16.66")));
        // Dropped digit exactly 5 -> HALF_UP rounds up: 16.665 -> 16.67.
        assertEquals(0, CobolDecimal.round(new BigDecimal("16.665"), 2)
                .compareTo(new BigDecimal("16.67")));
    }

    /**
     * {@link CobolDecimal#money(BigDecimal)} is truncation to the standard monetary scale
     * ({@link CobolDecimal#MONEY_SCALE} = 2) using RoundingMode.DOWN, mirroring a store into a
     * COBOL {@code S9(n)V99} field without {@code ROUNDED}: {@code 123.459 -> 123.45}.
     */
    @Test
    void moneyIsTruncateToScaleTwo() {
        assertEquals(0, CobolDecimal.money(new BigDecimal("123.459"))
                .compareTo(new BigDecimal("123.45")));
        assertEquals(2, CobolDecimal.money(new BigDecimal("123.459")).scale());
    }

    /**
     * {@link CobolDecimal#multiply(BigDecimal, BigDecimal, int)} computes the product at full
     * precision and only then truncates to the requested scale (RoundingMode.DOWN):
     * {@code 2.005 * 2.005 = 4.020025}, truncated to 2 places is {@code 4.02}.
     */
    @Test
    void multiplyTruncatesProductToScale() {
        BigDecimal product = CobolDecimal.multiply(new BigDecimal("2.005"), new BigDecimal("2.005"), 2);
        assertEquals(0, product.compareTo(new BigDecimal("4.02")));
        assertEquals(2, product.scale());
    }

    // ---------------------------------------------------------------------------------------
    // Phase 4 — Negative-value truncation (toward zero, matching COBOL)
    // ---------------------------------------------------------------------------------------

    /**
     * COBOL drops digits beyond the receiving scale, i.e. it truncates toward zero;
     * {@link RoundingMode#DOWN} has exactly that semantic. A negative value therefore moves
     * toward zero, not away from it: {@code -16.658 -> -16.65} (NOT -16.66) and
     * {@code -0.019 -> -0.01} (NOT -0.02).
     */
    @Test
    void negativeValuesTruncateTowardZero() {
        assertEquals(0, CobolDecimal.truncate(new BigDecimal("-16.658"), 2)
                .compareTo(new BigDecimal("-16.65")));
        assertEquals(0, CobolDecimal.money(new BigDecimal("-0.019"))
                .compareTo(new BigDecimal("-0.01")));
    }

    // ---------------------------------------------------------------------------------------
    // Phase 5 — Constant + scale guards
    // ---------------------------------------------------------------------------------------

    /**
     * The standard monetary scale constant matches the COBOL {@code V99} convention (two
     * decimal places).
     */
    @Test
    void moneyScaleConstantIsTwo() {
        assertEquals(2, CobolDecimal.MONEY_SCALE);
    }

    /**
     * Every helper returns a {@link BigDecimal} whose {@code scale()} equals the requested
     * scale (or {@link CobolDecimal#MONEY_SCALE} for {@link CobolDecimal#money(BigDecimal)}).
     * This is the "results are always BigDecimal with the expected fixed scale" guard: COBOL
     * fixed-scale fields never carry stray precision, so neither may their Java equivalents.
     */
    @Test
    void resultsAlwaysHaveExpectedScale() {
        BigDecimal sample = new BigDecimal("1.23456");

        // truncate at several scales.
        assertEquals(0, CobolDecimal.truncate(sample, 0).scale());
        assertEquals(2, CobolDecimal.truncate(sample, 2).scale());
        assertEquals(4, CobolDecimal.truncate(sample, 4).scale());

        // round, divide, and money all pin the scale too.
        assertEquals(2, CobolDecimal.round(sample, 2).scale());
        assertEquals(4, CobolDecimal.divide(new BigDecimal("10"), new BigDecimal("3"), 4).scale());
        assertEquals(2, CobolDecimal.money(new BigDecimal("1.239")).scale());
    }

    // ---------------------------------------------------------------------------------------
    // Additional guards over the verified CobolDecimal API (coverage + contract locks)
    // ---------------------------------------------------------------------------------------

    /**
     * The three-argument {@link CobolDecimal#divide(BigDecimal, BigDecimal, int)} must be a
     * pure delegation to the four-argument overload with {@link RoundingMode#DOWN}: for any
     * input the two forms produce the identical quotient. This documents that the default
     * money-division rounding is truncation.
     */
    @Test
    void divideDefaultOverloadMatchesExplicitDown() {
        BigDecimal numerator = new BigDecimal("19990.0000");
        BigDecimal denominator = new BigDecimal("1200");

        BigDecimal viaDefault = CobolDecimal.divide(numerator, denominator, 2);
        BigDecimal viaExplicitDown = CobolDecimal.divide(numerator, denominator, 2, RoundingMode.DOWN);

        assertEquals(0, viaDefault.compareTo(viaExplicitDown));
        assertEquals(0, viaDefault.compareTo(new BigDecimal("16.65")));
    }

    /**
     * {@link CobolDecimal#nullToZero(BigDecimal)} maps a {@code null} reference to
     * {@link BigDecimal#ZERO} (COBOL numeric fields are never null and default to zero) and
     * returns any non-null value unchanged (same instance, no copy).
     */
    @Test
    void nullToZeroReturnsZeroForNullAndValueOtherwise() {
        assertEquals(0, CobolDecimal.nullToZero(null).compareTo(BigDecimal.ZERO));

        BigDecimal value = new BigDecimal("5.55");
        assertSame(value, CobolDecimal.nullToZero(value));
    }

    /**
     * The scaling helpers reject {@code null} inputs with {@link NullPointerException},
     * exercising each {@code Objects.requireNonNull} guard (value, dividend, divisor). COBOL
     * has no concept of a null numeric operand, so a null reference is a programming error and
     * must fail fast rather than silently produce a wrong amount.
     */
    @Test
    void nullArgumentsThrowNullPointerException() {
        assertThrows(NullPointerException.class, () -> CobolDecimal.truncate(null, 2));
        assertThrows(NullPointerException.class, () -> CobolDecimal.round(null, 2));
        assertThrows(NullPointerException.class,
                () -> CobolDecimal.divide(null, new BigDecimal("1200"), 2));
        assertThrows(NullPointerException.class,
                () -> CobolDecimal.divide(new BigDecimal("1"), null, 2));
    }

    /**
     * Division by zero surfaces as an {@link ArithmeticException} (COBOL {@code ON SIZE ERROR}
     * / a divide-by-zero abend), never a silent or infinite result.
     */
    @Test
    void divideByZeroThrowsArithmeticException() {
        assertThrows(ArithmeticException.class,
                () -> CobolDecimal.divide(new BigDecimal("100.00"), new BigDecimal("0"), 2));
    }
}
