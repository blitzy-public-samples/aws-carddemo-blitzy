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

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Objects;

/**
 * Origin: legacy/cbl/CBACT04C.cbl ({@code 1300-COMPUTE-INTEREST}) &mdash; decimal-fidelity oracle.
 *
 * <p>Centralized helper that reproduces COBOL fixed-scale decimal arithmetic on
 * {@link java.math.BigDecimal}, so that monetary math across the migrated CardDemo
 * application (services, Spring Batch jobs, and JPA entities) is provably identical to the
 * original COBOL {@code COMPUTE} semantics. Implements the Decimal Fidelity concern
 * (Technical Specification &sect;0.6.1) and the migration rule that monetary fields must
 * never use floating point.</p>
 *
 * <p><strong>COBOL rounding semantics.</strong> A COBOL {@code COMPUTE} statement
 * <em>without</em> the {@code ROUNDED} phrase discards (truncates toward zero) the excess
 * low-order digits when storing its result into a fixed-scale receiving field; a statement
 * <em>with</em> {@code ROUNDED} rounds half away from zero. The canonical oracle is
 * {@code CBACT04C}'s {@code 1300-COMPUTE-INTEREST}:
 * {@code COMPUTE WS-MONTHLY-INT = (TRAN-CAT-BAL * DIS-INT-RATE) / 1200}, where
 * {@code WS-MONTHLY-INT} is {@code PIC S9(09)V99} (scale 2) and no {@code ROUNDED} phrase
 * is present, so the quotient is truncated to two decimals.</p>
 *
 * <p><strong>Verified fact.</strong> The keyword {@code ROUNDED} appears zero times in any
 * legacy CardDemo COBOL program, so every existing monetary {@code COMPUTE} truncates.
 * {@link java.math.RoundingMode#DOWN} is therefore the normal path; {@link #round(BigDecimal, int)}
 * (which uses {@link java.math.RoundingMode#HALF_UP}) is provided for completeness per
 * &sect;0.6.1 should a future {@code ROUNDED} statement ever be migrated.</p>
 *
 * <p><strong>General rule.</strong> Perform intermediate arithmetic at full precision, then
 * set the final scale with {@link java.math.RoundingMode#DOWN} where the COBOL statement
 * omits {@code ROUNDED}, and {@link java.math.RoundingMode#HALF_UP} where {@code ROUNDED} is
 * present. Callers must never route monetary values through {@code float} or {@code double}.</p>
 *
 * <p>This is a stateless, side-effect-free utility and is never instantiated. Design
 * rationale is recorded in {@code docs/decision-log.md}.</p>
 */
public final class CobolDecimal {

    /**
     * Standard monetary scale (COBOL {@code V99}): two decimal places.
     */
    public static final int MONEY_SCALE = 2;

    /**
     * The rounding mode COBOL applies to a {@code COMPUTE} that omits the {@code ROUNDED}
     * phrase: truncation toward zero ({@link RoundingMode#DOWN}). Exposed so callers can
     * reference the default semantic explicitly instead of hard-coding the enum constant.
     */
    public static final RoundingMode COBOL_DEFAULT_ROUNDING = RoundingMode.DOWN;

    /**
     * Non-instantiable utility class.
     *
     * @throws AssertionError always; this class exposes only static helpers.
     */
    private CobolDecimal() {
        throw new AssertionError("No instances");
    }

    /**
     * Truncates {@code value} to {@code scale} decimal places using
     * {@link RoundingMode#DOWN}. Reproduces a COBOL {@code COMPUTE} or {@code MOVE} into a
     * fixed-scale numeric field <em>without</em> the {@code ROUNDED} phrase &mdash; the
     * normal CardDemo case.
     *
     * @param value the value to truncate; must not be {@code null}
     * @param scale the number of decimal places to retain (per {@link BigDecimal} scale
     *              semantics)
     * @return {@code value} rescaled to {@code scale} with excess low-order digits discarded
     * @throws NullPointerException if {@code value} is {@code null}
     */
    public static BigDecimal truncate(BigDecimal value, int scale) {
        Objects.requireNonNull(value, "value");
        return value.setScale(scale, RoundingMode.DOWN);
    }

    /**
     * Rounds {@code value} to {@code scale} decimal places using
     * {@link RoundingMode#HALF_UP}. Reproduces a COBOL {@code COMPUTE ... ROUNDED}
     * statement. No current CardDemo COBOL statement uses {@code ROUNDED}; this helper
     * exists for completeness and future parity (see class Javadoc).
     *
     * @param value the value to round; must not be {@code null}
     * @param scale the number of decimal places to retain
     * @return {@code value} rescaled to {@code scale}, rounded half away from zero
     * @throws NullPointerException if {@code value} is {@code null}
     */
    public static BigDecimal round(BigDecimal value, int scale) {
        Objects.requireNonNull(value, "value");
        return value.setScale(scale, RoundingMode.HALF_UP);
    }

    /**
     * Convenience truncation to the standard {@link #MONEY_SCALE monetary scale} (two
     * decimal places) using {@link RoundingMode#DOWN}. Equivalent to
     * {@code truncate(value, MONEY_SCALE)} and mirrors storing a computed amount into a
     * COBOL {@code S9(n)V99} field without {@code ROUNDED}.
     *
     * @param value the monetary value; must not be {@code null}
     * @return {@code value} truncated to two decimal places
     * @throws NullPointerException if {@code value} is {@code null}
     */
    public static BigDecimal money(BigDecimal value) {
        return truncate(value, MONEY_SCALE);
    }

    /**
     * Divides {@code dividend} by {@code divisor} to {@code scale} decimal places using
     * {@link RoundingMode#DOWN}. Reproduces COBOL division in a {@code COMPUTE} without the
     * {@code ROUNDED} phrase &mdash; the interest case
     * {@code (TRAN-CAT-BAL * DIS-INT-RATE) / 1200} from {@code CBACT04C}'s
     * {@code 1300-COMPUTE-INTEREST}.
     *
     * @param dividend the numerator; must not be {@code null}
     * @param divisor  the denominator; must not be {@code null} and must be non-zero
     * @param scale    the number of decimal places to retain in the quotient
     * @return the quotient truncated to {@code scale}
     * @throws NullPointerException if {@code dividend} or {@code divisor} is {@code null}
     * @throws ArithmeticException  if {@code divisor} is zero
     */
    public static BigDecimal divide(BigDecimal dividend, BigDecimal divisor, int scale) {
        return divide(dividend, divisor, scale, RoundingMode.DOWN);
    }

    /**
     * Divides {@code dividend} by {@code divisor} to {@code scale} decimal places using the
     * caller-supplied {@code mode}. Use {@link RoundingMode#DOWN} to reproduce a COBOL
     * {@code COMPUTE} without {@code ROUNDED}, and {@link RoundingMode#HALF_UP} to reproduce
     * {@code COMPUTE ... ROUNDED}.
     *
     * @param dividend the numerator; must not be {@code null}
     * @param divisor  the denominator; must not be {@code null} and must be non-zero
     * @param scale    the number of decimal places to retain in the quotient
     * @param mode     the rounding mode to apply; must not be {@code null}
     * @return the quotient rescaled to {@code scale} using {@code mode}
     * @throws NullPointerException if {@code dividend}, {@code divisor}, or {@code mode} is
     *                              {@code null}
     * @throws ArithmeticException  if {@code divisor} is zero
     */
    public static BigDecimal divide(BigDecimal dividend, BigDecimal divisor, int scale, RoundingMode mode) {
        Objects.requireNonNull(dividend, "dividend");
        Objects.requireNonNull(divisor, "divisor");
        Objects.requireNonNull(mode, "mode");
        return dividend.divide(divisor, scale, mode);
    }

    /**
     * Multiplies {@code a} by {@code b} at full precision and then truncates the product to
     * {@code scale} decimal places using {@link RoundingMode#DOWN}. Mirrors the COBOL rule
     * of computing an intermediate result at full precision and only then storing it into a
     * fixed-scale field without {@code ROUNDED}.
     *
     * @param a     the first factor; must not be {@code null}
     * @param b     the second factor; must not be {@code null}
     * @param scale the number of decimal places to retain in the product
     * @return {@code a * b} truncated to {@code scale}
     * @throws NullPointerException if {@code a} or {@code b} is {@code null}
     */
    public static BigDecimal multiply(BigDecimal a, BigDecimal b, int scale) {
        Objects.requireNonNull(a, "a");
        Objects.requireNonNull(b, "b");
        return truncate(a.multiply(b), scale);
    }

    /**
     * Returns {@code value} when it is non-{@code null}, otherwise {@link BigDecimal#ZERO}.
     * Mirrors COBOL numeric fields, which are never {@code null} and default to zero, so a
     * {@code null} Java reference (for example an absent optional column) is treated as the
     * COBOL zero value.
     *
     * @param value the value that may be {@code null}
     * @return {@code value}, or {@link BigDecimal#ZERO} when {@code value} is {@code null}
     */
    public static BigDecimal nullToZero(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }
}
