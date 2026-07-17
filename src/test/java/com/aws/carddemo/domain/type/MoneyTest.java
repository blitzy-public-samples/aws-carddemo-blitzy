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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link Money}, verifying the fixed-scale (scale 2, HALF_UP)
 * arithmetic, the to-the-cent monthly-interest parity with the legacy COBOL
 * {@code CBACT04C} computation, value-based equality, immutability, and
 * null-safety.
 */
class MoneyTest {

    // ---------------------------------------------------------------------
    // Monthly-interest golden values (to the cent, HALF_UP)
    //   monthlyInterest = (amount * annualRate) / 1200, single rounding step.
    // ---------------------------------------------------------------------

    @Test
    @DisplayName("monthlyInterest reproduces the COBOL formula to the cent (golden values)")
    void monthlyInterest_goldenValues() {
        assertEquals(Money.of("12.50"), Money.of("1000.00").monthlyInterest(new BigDecimal("15.00")));
        assertEquals(Money.of("52.08"), Money.of("2500.00").monthlyInterest(new BigDecimal("25.00")));
        assertEquals(Money.of("1.67"), Money.of("100.00").monthlyInterest(new BigDecimal("19.99")));
        assertEquals(Money.of("3.61"), Money.of("333.33").monthlyInterest(new BigDecimal("12.99")));
        assertEquals(Money.of("0.06"), Money.of("50.00").monthlyInterest(new BigDecimal("1.50")));

        // Lock the scale/string form as well, not just numeric equality.
        assertEquals("12.50", Money.of("1000.00").monthlyInterest(new BigDecimal("15.00")).toString());
        assertEquals("52.08", Money.of("2500.00").monthlyInterest(new BigDecimal("25.00")).toString());
    }

    @Test
    @DisplayName("monthlyInterest uses a single rounding step (exact product), not a pre-rounded product")
    void monthlyInterest_singleExpressionParity() {
        // Exact product 571.66 * 9.8765 = 5645.999990; /1200 = 4.70499... -> 4.70 (HALF_UP).
        // A chained multiply()-then-divideBy() would pre-round the product to 5646.00,
        // giving 4.705 -> 4.71. The single-expression form must yield 4.70.
        Money result = Money.of("571.66").monthlyInterest(new BigDecimal("9.8765"));
        assertEquals(Money.of("4.70"), result);
        assertEquals("4.70", result.toString());
    }

    @Test
    @DisplayName("monthlyInterest with a zero rate yields 0.00 (no special-casing inside Money)")
    void monthlyInterest_zeroRate() {
        assertEquals(Money.of("0.00"), Money.of("100.00").monthlyInterest(new BigDecimal("0.00")));
        assertTrue(Money.of("100.00").monthlyInterest(BigDecimal.ZERO).isZero());
    }

    // ---------------------------------------------------------------------
    // HALF_UP boundary rounding on construction
    // ---------------------------------------------------------------------

    @Test
    @DisplayName("Construction rounds HALF_UP at the cent boundary")
    void factory_halfUpBoundaries() {
        assertEquals("2.68", Money.of("2.675").toString());
        assertEquals("-0.01", Money.of("-0.005").toString());
        assertEquals("5.00", Money.of("5.004").toString());
        assertEquals("5.01", Money.of("5.005").toString());
    }

    // ---------------------------------------------------------------------
    // Scale normalization across factories
    // ---------------------------------------------------------------------

    @Test
    @DisplayName("of(String), of(long) and whole-unit values all normalize to scale 2 and are equal")
    void factory_scaleNormalization() {
        Money fromBareString = Money.of("5");
        Money fromLong = Money.of(5L);
        Money fromScaledString = Money.of("5.00");
        Money fromBigDecimal = Money.of(new BigDecimal("5"));

        assertEquals(fromBareString, fromLong);
        assertEquals(fromLong, fromScaledString);
        assertEquals(fromScaledString, fromBigDecimal);

        assertEquals("5.00", fromBareString.toString());
        assertEquals("5.00", fromLong.toString());
        assertEquals("5.00", fromScaledString.toString());
        assertEquals("5.00", fromBigDecimal.toString());

        assertEquals("0.00", Money.ZERO.toString());
    }

    // ---------------------------------------------------------------------
    // Exact addition / subtraction (scale-2 operands)
    // ---------------------------------------------------------------------

    @Test
    @DisplayName("add and subtract are exact on scale-2 operands")
    void addSubtract_exact() {
        assertEquals(Money.of("1234568.00"), Money.of("1234567.89").add(Money.of("0.11")));
        assertEquals("1234568.00", Money.of("1234567.89").add(Money.of("0.11")).toString());

        assertEquals(Money.of("1234567.78"), Money.of("1234567.89").subtract(Money.of("0.11")));
        assertEquals(Money.of("0.00"), Money.of("10.00").subtract(Money.of("10.00")));
        assertEquals(Money.of("-5.00"), Money.of("5.00").subtract(Money.of("10.00")));
    }

    // ---------------------------------------------------------------------
    // multiply / divideBy (rounded HALF_UP to scale 2)
    // ---------------------------------------------------------------------

    @Test
    @DisplayName("multiply and divideBy round the result HALF_UP to scale 2")
    void multiplyDivide_rounding() {
        assertEquals(Money.of("30.00"), Money.of("10.00").multiply(new BigDecimal("3")));
        // 10.00 * 0.333 = 3.33 (exact product 3.330 -> scale 2 = 3.33)
        assertEquals(Money.of("3.33"), Money.of("10.00").multiply(new BigDecimal("0.333")));

        assertEquals(Money.of("3.33"), Money.of("10.00").divideBy(new BigDecimal("3")));
        assertEquals(Money.of("2.50"), Money.of("10.00").divideBy(new BigDecimal("4")));
    }

    // ---------------------------------------------------------------------
    // negate / abs
    // ---------------------------------------------------------------------

    @Test
    @DisplayName("negate and abs preserve scale-2 semantics")
    void negateAbs() {
        assertEquals(Money.of("-12.34"), Money.of("12.34").negate());
        assertEquals(Money.of("12.34"), Money.of("-12.34").abs());
        assertEquals(Money.of("12.34"), Money.of("12.34").abs());
        assertEquals("-12.34", Money.of("12.34").negate().toString());
    }

    // ---------------------------------------------------------------------
    // Comparisons / sign predicates (via BigDecimal.compareTo)
    // ---------------------------------------------------------------------

    @Test
    @DisplayName("sign predicates report zero / negative / positive correctly")
    void signPredicates() {
        assertTrue(Money.of("0.00").isZero());
        assertFalse(Money.of("0.01").isZero());

        assertTrue(Money.of("-0.01").isNegative());
        assertFalse(Money.of("0.00").isNegative());
        assertFalse(Money.of("0.01").isNegative());

        assertTrue(Money.of("0.01").isPositive());
        assertFalse(Money.of("0.00").isPositive());
        assertFalse(Money.of("-0.01").isPositive());
    }

    @Test
    @DisplayName("relational predicates compare numerically via compareTo")
    void relationalPredicates() {
        Money small = Money.of("10.00");
        Money large = Money.of("20.00");
        Money alsoSmall = Money.of("10.000");

        assertTrue(large.isGreaterThan(small));
        assertFalse(small.isGreaterThan(large));
        assertFalse(small.isGreaterThan(alsoSmall));

        assertTrue(small.isGreaterThanOrEqual(alsoSmall));
        assertTrue(large.isGreaterThanOrEqual(small));
        assertFalse(small.isGreaterThanOrEqual(large));

        assertTrue(small.isLessThan(large));
        assertFalse(large.isLessThan(small));

        assertTrue(small.isLessThanOrEqual(alsoSmall));
        assertTrue(small.isLessThanOrEqual(large));
        assertFalse(large.isLessThanOrEqual(small));
    }

    // ---------------------------------------------------------------------
    // Value-based equals / hashCode (scale-insensitive via compareTo)
    // ---------------------------------------------------------------------

    @Test
    @DisplayName("equals is value-based and scale-insensitive; equal values share hashCode")
    void equalsHashCode_valueBased() {
        Money a = Money.of("2.0");
        Money b = Money.of("2.00");

        assertTrue(a.equals(b));
        assertEquals(a, b);
        assertEquals(a.hashCode(), b.hashCode());

        // Reflexive, and unequal for different values / types / null.
        assertEquals(a, a);
        assertFalse(a.equals(Money.of("2.01")));
        assertFalse(a.equals(null));
        assertFalse(a.equals("2.00"));
    }

    // ---------------------------------------------------------------------
    // Immutability
    // ---------------------------------------------------------------------

    @Test
    @DisplayName("operations return new instances and never mutate the receiver")
    void immutability() {
        Money base = Money.of("10.00");

        Money sum = base.add(Money.of("5.00"));
        Money diff = base.subtract(Money.of("5.00"));
        Money product = base.multiply(new BigDecimal("2"));
        Money quotient = base.divideBy(new BigDecimal("2"));
        Money negated = base.negate();

        assertNotSame(base, sum);
        assertNotSame(base, diff);
        assertNotSame(base, product);
        assertNotSame(base, quotient);
        assertNotSame(base, negated);

        // The original is unchanged.
        assertEquals("10.00", base.toString());
    }

    // ---------------------------------------------------------------------
    // Null-safety (Objects.requireNonNull messages)
    // ---------------------------------------------------------------------

    @Test
    @DisplayName("factories reject null with a clear NullPointerException message")
    void factories_nullSafety() {
        BigDecimal nullDecimal = null;
        String nullString = null;

        NullPointerException fromDecimal =
                assertThrows(NullPointerException.class, () -> Money.of(nullDecimal));
        assertEquals("amount must not be null", fromDecimal.getMessage());

        NullPointerException fromString =
                assertThrows(NullPointerException.class, () -> Money.of(nullString));
        assertEquals("amount must not be null", fromString.getMessage());
    }

    @Test
    @DisplayName("operations reject null operands with a clear NullPointerException message")
    void operations_nullSafety() {
        Money one = Money.of("1.00");

        NullPointerException addNpe =
                assertThrows(NullPointerException.class, () -> one.add(null));
        assertEquals("operand must not be null", addNpe.getMessage());

        NullPointerException subNpe =
                assertThrows(NullPointerException.class, () -> one.subtract(null));
        assertEquals("operand must not be null", subNpe.getMessage());

        NullPointerException mulNpe =
                assertThrows(NullPointerException.class, () -> one.multiply(null));
        assertEquals("factor must not be null", mulNpe.getMessage());

        NullPointerException divNpe =
                assertThrows(NullPointerException.class, () -> one.divideBy(null));
        assertEquals("divisor must not be null", divNpe.getMessage());

        NullPointerException interestNpe =
                assertThrows(NullPointerException.class, () -> one.monthlyInterest(null));
        assertEquals("annualRate must not be null", interestNpe.getMessage());

        NullPointerException gtNpe =
                assertThrows(NullPointerException.class, () -> one.isGreaterThan(null));
        assertEquals("operand must not be null", gtNpe.getMessage());
    }

    // ---------------------------------------------------------------------
    // Accessor
    // ---------------------------------------------------------------------

    @Test
    @DisplayName("toBigDecimal exposes the scale-2 amount")
    void toBigDecimal_exposesScale2Amount() {
        BigDecimal value = Money.of("42.5").toBigDecimal();
        assertEquals(2, value.scale());
        assertEquals(0, value.compareTo(new BigDecimal("42.50")));
    }
}
