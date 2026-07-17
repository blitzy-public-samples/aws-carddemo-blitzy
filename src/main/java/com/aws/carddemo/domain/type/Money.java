/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License").
 * You may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.aws.carddemo.domain.type;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Objects;

/**
 * Immutable monetary value object that reproduces the fixed-scale, packed-decimal
 * arithmetic of the legacy COBOL {@code COMP-3} / {@code PIC S9(n)V99} money fields.
 *
 * <p>Every instance carries a {@link BigDecimal} normalized to a fixed scale of
 * {@code 2} using {@link RoundingMode#HALF_UP}. This is the single canonical place
 * where credit-card monetary arithmetic is performed, so that rounding behavior is
 * consistent and no drift compounds across postings and interest accrual. Because
 * all arithmetic is centralized here and carried out with {@code BigDecimal}, the
 * class never uses {@code double} or {@code float}.
 *
 * <p>The type is {@code final} and every field is {@code final}; each operation
 * returns a brand-new {@code Money}, so instances are safe to share across threads.
 * Instances are constructed only through the static {@code of(...)} factories or the
 * {@link #ZERO} constant.
 *
 * <p>Equality is value-based and scale-insensitive: it compares amounts with
 * {@link BigDecimal#compareTo(BigDecimal)} (so {@code 2.0} equals {@code 2.00})
 * rather than {@link BigDecimal#equals(Object)}, which is scale-sensitive.
 *
 * <p>This value object is consumed by the service and batch layers (interest
 * calculation, posting balance updates). It is intentionally not referenced by the
 * JPA entities, which map monetary columns as raw {@code BigDecimal}; that
 * decoupling is deliberate and must be preserved.
 */
public final class Money {

    /** Fixed number of decimal places for every monetary amount (cents). */
    private static final int SCALE = 2;

    /** Rounding applied to every normalization, division, and scale adjustment. */
    private static final RoundingMode ROUNDING = RoundingMode.HALF_UP;

    /**
     * Divisor used by {@link #monthlyInterest(BigDecimal)}: twelve months times one
     * hundred, matching the COBOL {@code / 1200} in the interest computation, where
     * the annual rate is expressed as a whole-number percentage.
     */
    private static final BigDecimal MONTHS_PER_YEAR_TIMES_HUNDRED = BigDecimal.valueOf(1200L);

    /** The canonical zero amount ({@code 0.00}). */
    public static final Money ZERO = new Money(BigDecimal.ZERO.setScale(SCALE, ROUNDING));

    private final BigDecimal amount;

    private Money(BigDecimal normalizedAmount) {
        this.amount = normalizedAmount;
    }

    /**
     * Creates a {@code Money} from a {@link BigDecimal}, normalizing it to scale 2
     * with {@link RoundingMode#HALF_UP}.
     *
     * @param value the amount; must not be {@code null}
     * @return a {@code Money} holding {@code value} at scale 2
     * @throws NullPointerException if {@code value} is {@code null}
     */
    public static Money of(BigDecimal value) {
        Objects.requireNonNull(value, "amount must not be null");
        return new Money(value.setScale(SCALE, ROUNDING));
    }

    /**
     * Creates a {@code Money} from a whole-unit {@code long} value (for example,
     * {@code 5} becomes {@code 5.00}).
     *
     * @param value the whole-unit amount
     * @return a {@code Money} holding {@code value} at scale 2
     */
    public static Money of(long value) {
        return new Money(BigDecimal.valueOf(value).setScale(SCALE, ROUNDING));
    }

    /**
     * Creates a {@code Money} by parsing a decimal string, normalizing the result to
     * scale 2 with {@link RoundingMode#HALF_UP}.
     *
     * @param value the decimal string to parse; must not be {@code null}
     * @return a {@code Money} holding the parsed amount at scale 2
     * @throws NullPointerException if {@code value} is {@code null}
     * @throws NumberFormatException if {@code value} is not a valid decimal
     */
    public static Money of(String value) {
        Objects.requireNonNull(value, "amount must not be null");
        return new Money(new BigDecimal(value).setScale(SCALE, ROUNDING));
    }

    /**
     * Returns a new {@code Money} equal to this amount plus {@code other}. Because
     * both operands are at scale 2, the sum is exact and is re-normalized to scale 2.
     *
     * @param other the amount to add; must not be {@code null}
     * @return the sum
     * @throws NullPointerException if {@code other} is {@code null}
     */
    public Money add(Money other) {
        Objects.requireNonNull(other, "operand must not be null");
        return new Money(this.amount.add(other.amount).setScale(SCALE, ROUNDING));
    }

    /**
     * Returns a new {@code Money} equal to this amount minus {@code other}. Because
     * both operands are at scale 2, the difference is exact and is re-normalized to
     * scale 2.
     *
     * @param other the amount to subtract; must not be {@code null}
     * @return the difference
     * @throws NullPointerException if {@code other} is {@code null}
     */
    public Money subtract(Money other) {
        Objects.requireNonNull(other, "operand must not be null");
        return new Money(this.amount.subtract(other.amount).setScale(SCALE, ROUNDING));
    }

    /**
     * Returns a new {@code Money} equal to this amount multiplied by {@code factor},
     * with the product rounded to scale 2 using {@link RoundingMode#HALF_UP}.
     *
     * <p>Note: this is a general-purpose multiply that rounds the product. It must
     * not be used to build the monthly-interest formula; use
     * {@link #monthlyInterest(BigDecimal)}, which performs a single rounding step.
     *
     * @param factor the multiplier; must not be {@code null}
     * @return the rounded product
     * @throws NullPointerException if {@code factor} is {@code null}
     */
    public Money multiply(BigDecimal factor) {
        Objects.requireNonNull(factor, "factor must not be null");
        return new Money(this.amount.multiply(factor).setScale(SCALE, ROUNDING));
    }

    /**
     * Returns a new {@code Money} equal to this amount divided by {@code divisor},
     * with the quotient rounded to scale 2 using {@link RoundingMode#HALF_UP}.
     *
     * @param divisor the divisor; must not be {@code null}
     * @return the rounded quotient
     * @throws NullPointerException if {@code divisor} is {@code null}
     * @throws ArithmeticException if {@code divisor} is zero
     */
    public Money divideBy(BigDecimal divisor) {
        Objects.requireNonNull(divisor, "divisor must not be null");
        return new Money(this.amount.divide(divisor, SCALE, ROUNDING));
    }

    /**
     * Computes the monthly interest on this balance for the given annual percentage
     * rate, reproducing the legacy COBOL computation
     * {@code COMPUTE WS-MONTHLY-INT = (TRAN-CAT-BAL * DIS-INT-RATE) / 1200}
     * from {@code CBACT04C} exactly.
     *
     * <p>The formula is evaluated as a single expression:
     * {@code amount.multiply(annualRate).divide(1200, 2, HALF_UP)}. The
     * {@link BigDecimal#multiply(BigDecimal)} product is exact (full precision) and
     * the single {@link BigDecimal#divide(BigDecimal, int, RoundingMode)} is the only
     * rounding step (scale 2, {@link RoundingMode#HALF_UP}), mirroring the single
     * COBOL {@code COMPUTE} so results match to the cent. It therefore does not chain
     * {@link #multiply(BigDecimal)} (which would pre-round the product).
     *
     * <p>The caller is responsible for any zero-rate guard; a zero rate here simply
     * yields {@code 0.00}.
     *
     * @param annualRate the annual interest rate as a whole-number percentage
     *                    (for example {@code 15.00} for 15%); must not be {@code null}
     * @return the monthly interest amount at scale 2
     * @throws NullPointerException if {@code annualRate} is {@code null}
     */
    public Money monthlyInterest(BigDecimal annualRate) {
        Objects.requireNonNull(annualRate, "annualRate must not be null");
        BigDecimal monthly = this.amount
                .multiply(annualRate)
                .divide(MONTHS_PER_YEAR_TIMES_HUNDRED, SCALE, ROUNDING);
        return new Money(monthly);
    }

    /**
     * Returns a new {@code Money} with the arithmetic negation of this amount.
     *
     * @return the negated amount
     */
    public Money negate() {
        return new Money(this.amount.negate());
    }

    /**
     * Returns a new {@code Money} with the absolute value of this amount.
     *
     * @return the absolute amount
     */
    public Money abs() {
        return new Money(this.amount.abs());
    }

    /**
     * Indicates whether this amount is exactly zero.
     *
     * @return {@code true} if this amount equals {@code 0.00}
     */
    public boolean isZero() {
        return this.amount.compareTo(BigDecimal.ZERO) == 0;
    }

    /**
     * Indicates whether this amount is less than zero.
     *
     * @return {@code true} if this amount is negative
     */
    public boolean isNegative() {
        return this.amount.compareTo(BigDecimal.ZERO) < 0;
    }

    /**
     * Indicates whether this amount is greater than zero.
     *
     * @return {@code true} if this amount is positive
     */
    public boolean isPositive() {
        return this.amount.compareTo(BigDecimal.ZERO) > 0;
    }

    /**
     * Indicates whether this amount is strictly greater than {@code other}.
     *
     * @param other the amount to compare against; must not be {@code null}
     * @return {@code true} if this amount is greater than {@code other}
     * @throws NullPointerException if {@code other} is {@code null}
     */
    public boolean isGreaterThan(Money other) {
        Objects.requireNonNull(other, "operand must not be null");
        return this.amount.compareTo(other.amount) > 0;
    }

    /**
     * Indicates whether this amount is greater than or equal to {@code other}.
     *
     * @param other the amount to compare against; must not be {@code null}
     * @return {@code true} if this amount is greater than or equal to {@code other}
     * @throws NullPointerException if {@code other} is {@code null}
     */
    public boolean isGreaterThanOrEqual(Money other) {
        Objects.requireNonNull(other, "operand must not be null");
        return this.amount.compareTo(other.amount) >= 0;
    }

    /**
     * Indicates whether this amount is strictly less than {@code other}.
     *
     * @param other the amount to compare against; must not be {@code null}
     * @return {@code true} if this amount is less than {@code other}
     * @throws NullPointerException if {@code other} is {@code null}
     */
    public boolean isLessThan(Money other) {
        Objects.requireNonNull(other, "operand must not be null");
        return this.amount.compareTo(other.amount) < 0;
    }

    /**
     * Indicates whether this amount is less than or equal to {@code other}.
     *
     * @param other the amount to compare against; must not be {@code null}
     * @return {@code true} if this amount is less than or equal to {@code other}
     * @throws NullPointerException if {@code other} is {@code null}
     */
    public boolean isLessThanOrEqual(Money other) {
        Objects.requireNonNull(other, "operand must not be null");
        return this.amount.compareTo(other.amount) <= 0;
    }

    /**
     * Returns the underlying amount as a {@link BigDecimal} at scale 2.
     *
     * @return the scale-2 amount
     */
    public BigDecimal toBigDecimal() {
        return this.amount;
    }

    /**
     * Value-based equality. Two {@code Money} instances are equal when their amounts
     * are numerically equal, compared with {@link BigDecimal#compareTo(BigDecimal)}
     * so that scale differences do not affect the result.
     *
     * @param o the object to compare with
     * @return {@code true} if {@code o} is a {@code Money} with a numerically equal amount
     */
    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof Money)) {
            return false;
        }
        Money money = (Money) o;
        return this.amount.compareTo(money.amount) == 0;
    }

    /**
     * Returns a hash code consistent with {@link #equals(Object)}. Trailing zeros are
     * stripped first so that numerically equal amounts (for example {@code 2.0} and
     * {@code 2.00}) produce the same hash code.
     *
     * @return the value-based hash code
     */
    @Override
    public int hashCode() {
        return Objects.hash(this.amount.stripTrailingZeros());
    }

    /**
     * Returns the plain decimal string for this amount (no currency symbol and no
     * exponent), for example {@code "5.00"}.
     *
     * @return the plain-string representation of the amount
     */
    @Override
    public String toString() {
        return this.amount.toPlainString();
    }
}
