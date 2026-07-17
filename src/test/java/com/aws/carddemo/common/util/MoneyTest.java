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
package com.aws.carddemo.common.util;

import com.aws.carddemo.domain.type.Money;

import java.math.BigDecimal;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Unit tests for the {@link Money} value object.
 *
 * <p>These are fast, isolated, pure-logic tests: no Spring context, no database, and
 * no Testcontainers. They exercise {@link Money} directly to lock down the fixed-scale
 * ({@code 2}), {@link java.math.RoundingMode#HALF_UP} packed-decimal semantics that
 * reproduce the legacy COBOL {@code COMP-3} / {@code PIC S9(n)V99} money fields.
 *
 * <p>The parity-critical assertions are the monthly-interest golden values, which
 * reproduce the single COBOL {@code COMPUTE} in {@code legacy/cbl/CBACT04C.cbl}
 * paragraph {@code 1300-COMPUTE-INTEREST} (lines 462&ndash;465):
 * {@code COMPUTE WS-MONTHLY-INT = (TRAN-CAT-BAL * DIS-INT-RATE) / 1200}, where
 * {@code TRAN-CAT-BAL} ({@code app/cpy/CVTRA01Y.cpy}, {@code PIC S9(09)V99}) and
 * {@code DIS-INT-RATE} ({@code app/cpy/CVTRA02Y.cpy}, {@code PIC S9(04)V99}) are both
 * scale-2 fields and the annual rate is a whole-number percentage. The COBOL result is
 * matched to the cent; HALF_UP is authoritative for the golden fixtures (any legacy
 * truncation divergence is recorded in {@code docs/decision-log.md}).
 *
 * <p>Every monetary literal in this test is a {@link String} or {@link BigDecimal};
 * {@code double}/{@code float} are never used, mirroring the production constraint.
 */
class MoneyTest {

    // ---- monthlyInterest: COBOL 1300-COMPUTE-INTEREST parity (golden values) ----

    @Test
    void monthlyInterest_matchesCobolInterestFormulaToTheCent() {
        // (TRAN-CAT-BAL * DIS-INT-RATE) / 1200 evaluated as a single HALF_UP rounding
        // step. Rates 15.00 and 25.00 are real seed disclosure-group rates.
        assertThat(Money.of("1000.00").monthlyInterest(new BigDecimal("15.00")))
                .isEqualTo(Money.of("12.50")); // 15000.00 / 1200 = 12.50 (exact)
        assertThat(Money.of("2500.00").monthlyInterest(new BigDecimal("25.00")))
                .isEqualTo(Money.of("52.08")); // 62500.00 / 1200 = 52.0833... -> 52.08
        assertThat(Money.of("100.00").monthlyInterest(new BigDecimal("19.99")))
                .isEqualTo(Money.of("1.67")); // 1999.00 / 1200 = 1.6658... -> 1.67
        assertThat(Money.of("333.33").monthlyInterest(new BigDecimal("12.99")))
                .isEqualTo(Money.of("3.61")); // 4329.9567 / 1200 = 3.6083... -> 3.61
        assertThat(Money.of("50.00").monthlyInterest(new BigDecimal("1.50")))
                .isEqualTo(Money.of("0.06")); // 75.00 / 1200 = 0.0625 -> 0.06
    }

    @Test
    void monthlyInterest_resultIsScaleTwoAndComparesEqualToGolden() {
        Money result = Money.of("1000.00").monthlyInterest(new BigDecimal("15.00"));
        assertThat(result.toBigDecimal().scale()).isEqualTo(2);
        assertThat(result.toBigDecimal()).isEqualByComparingTo("12.50");
        assertThat(result.toBigDecimal()).isEqualByComparingTo(new BigDecimal("12.50"));
        assertThat(result).isEqualTo(Money.of("12.50"));
    }

    @Test
    void monthlyInterest_nullRateThrowsNullPointerException() {
        assertThrows(NullPointerException.class,
                () -> Money.of("1000.00").monthlyInterest(null));
    }

    // ---- of(String): HALF_UP normalization at the scale-2 rounding boundaries ----

    @Test
    void of_roundsHalfUpAtScaleTwoBoundaries() {
        // String literals carry the exact decimal value (unlike a binary double), so
        // these boundary cases isolate the HALF_UP rule itself.
        assertThat(Money.of("2.675").toBigDecimal()).isEqualByComparingTo("2.68");
        assertThat(Money.of("-0.005").toBigDecimal()).isEqualByComparingTo("-0.01"); // magnitude away from zero
        assertThat(Money.of("5.004").toBigDecimal()).isEqualByComparingTo("5.00");
        assertThat(Money.of("5.005").toBigDecimal()).isEqualByComparingTo("5.01");
    }

    @Test
    void of_normalizedResultsAreAlwaysScaleTwo() {
        assertThat(Money.of("2.675").toBigDecimal().scale()).isEqualTo(2);
        assertThat(Money.of("-0.005").toBigDecimal().scale()).isEqualTo(2);
        assertThat(Money.of("5.004").toBigDecimal().scale()).isEqualTo(2);
        assertThat(Money.of("5.005").toBigDecimal().scale()).isEqualTo(2);
    }

    // ---- scale normalization + exact add / subtract ----

    @Test
    void of_normalizesWholeUnitsAndStringsToTheSameScaleTwoValue() {
        assertThat(Money.of("5")).isEqualTo(Money.of("5.00"));
        assertThat(Money.of(5L)).isEqualTo(Money.of("5.00"));
        assertThat(Money.of("5")).isEqualTo(Money.of(5L));

        assertThat(Money.of("5").toString()).isEqualTo("5.00");
        assertThat(Money.of(5L).toString()).isEqualTo("5.00");
        assertThat(Money.of("5.00").toString()).isEqualTo("5.00");
    }

    @Test
    void add_isExactAtScaleTwo() {
        assertThat(Money.of("1234567.89").add(Money.of("0.11")))
                .isEqualTo(Money.of("1234568.00"));
        assertThat(Money.of("1234567.89").add(Money.of("0.11")).toString())
                .isEqualTo("1234568.00");
    }

    @Test
    void subtract_isExactAtScaleTwo() {
        assertThat(Money.of("1234568.00").subtract(Money.of("0.11")))
                .isEqualTo(Money.of("1234567.89"));
    }

    @Test
    void zeroConstant_isZeroAndFormatsWithScaleTwo() {
        assertThat(Money.ZERO.isZero()).isTrue();
        assertThat(Money.ZERO.toString()).isEqualTo("0.00");
    }

    // ---- value equality + hashCode contract (compareTo-based, scale-insensitive) ----

    @Test
    void equals_isValueBasedAndScaleInsensitive() {
        assertThat(Money.of("2.0")).isEqualTo(Money.of("2.00"));
        assertThat(Money.of("2.00")).isNotEqualTo(Money.of("2.01"));
    }

    @Test
    void hashCode_isConsistentForEqualValuesAcrossScales() {
        assertThat(Money.of("2.0").hashCode()).isEqualTo(Money.of("2.00").hashCode());
    }

    // ---- sign predicates ----

    @Test
    void signPredicates_reflectTheAmountSign() {
        assertThat(Money.of("-1.00").isNegative()).isTrue();
        assertThat(Money.of("1.00").isNegative()).isFalse();

        assertThat(Money.of("1.00").isPositive()).isTrue();
        assertThat(Money.of("-1.00").isPositive()).isFalse();

        assertThat(Money.ZERO.isZero()).isTrue();
        assertThat(Money.of("0.01").isZero()).isFalse();
    }

    // ---- ordering predicates ----

    @Test
    void orderingPredicates_matchNumericOrdering() {
        Money one = Money.of("1.00");
        Money two = Money.of("2.00");
        Money twoAgain = Money.of("2.00");
        Money three = Money.of("3.00");

        assertThat(two.isGreaterThanOrEqual(twoAgain)).isTrue();
        assertThat(three.isGreaterThanOrEqual(two)).isTrue();
        assertThat(one.isGreaterThanOrEqual(two)).isFalse();

        assertThat(three.isGreaterThan(two)).isTrue();
        assertThat(two.isGreaterThan(three)).isFalse();

        assertThat(one.isLessThan(two)).isTrue();
        assertThat(two.isLessThan(one)).isFalse();

        assertThat(two.isLessThanOrEqual(twoAgain)).isTrue();
        assertThat(one.isLessThanOrEqual(two)).isTrue();
        assertThat(three.isLessThanOrEqual(two)).isFalse();
    }

    // ---- immutability: operations never mutate their operands ----

    @Test
    void operationsReturnNewInstancesAndDoNotMutateOperands() {
        Money a = Money.of("10.00");
        Money b = Money.of("1.00");

        assertThat(a.add(b)).isNotSameAs(a);
        assertThat(a.subtract(b)).isNotSameAs(a);

        assertThat(a).isEqualTo(Money.of("10.00"));
        assertThat(b).isEqualTo(Money.of("1.00"));
    }

    // ---- multiply / divideBy sanity ----

    @Test
    void multiplyAndDivideBy_produceScaleTwoResults() {
        assertThat(Money.of("10.00").multiply(new BigDecimal("3")))
                .isEqualTo(Money.of("30.00"));
        assertThat(Money.of("10.00").divideBy(new BigDecimal("4")))
                .isEqualTo(Money.of("2.50"));
    }

    // ---- null-safety: every factory and arithmetic entry point rejects null ----

    @Test
    void nullArgumentsThrowNullPointerException() {
        // The (BigDecimal)/(String) casts disambiguate the of(...) factory overloads.
        assertThrows(NullPointerException.class, () -> Money.of((BigDecimal) null));
        assertThrows(NullPointerException.class, () -> Money.of((String) null));
        assertThrows(NullPointerException.class, () -> Money.of("1.00").add(null));
        assertThrows(NullPointerException.class, () -> Money.of("1.00").subtract(null));
        assertThrows(NullPointerException.class, () -> Money.of("1.00").multiply(null));
        assertThrows(NullPointerException.class, () -> Money.of("1.00").divideBy(null));
    }
}
