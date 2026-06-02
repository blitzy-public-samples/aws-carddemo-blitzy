package com.carddemo.util;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * Cross-cutting utility providing reusable constants and helper methods for
 * monetary {@link BigDecimal} arithmetic across the entire CardDemo application.
 *
 * <p>This is the most foundational utility class in the system — it is consumed
 * by services, batch processors, mappers, validators, and entities for ANY
 * monetary calculation. It has zero internal dependencies and relies solely on
 * the {@code java.math} standard library.
 *
 * <h2>Why no {@code float}/{@code double}</h2>
 * <p>Monetary precision requires exact decimal arithmetic. Binary floating-point
 * types ({@code float}, {@code double}) cannot represent most decimal fractions
 * exactly (e.g., {@code 0.1} has no exact binary representation) and would
 * corrupt cents over repeated operations. Per <b>PR-16</b>, no {@code float},
 * {@code double}, {@code Float}, or {@code Double} is permitted for any monetary
 * field or calculation; {@link BigDecimal} is used uniformly.
 *
 * <h2>Standard scale and rounding</h2>
 * <ul>
 *   <li><b>Scale:</b> {@code 2} (two decimal places) — matches the COBOL
 *       {@code PIC S9(n)V99} packed-decimal money fields (e.g.,
 *       {@code ACCT-CURR-BAL}, {@code TRAN-CAT-BAL}, {@code DIS-INT-RATE}).</li>
 *   <li><b>Rounding:</b> {@link RoundingMode#HALF_UP} — matches the COBOL
 *       {@code ROUNDED} clause (half-values round away from zero).</li>
 * </ul>
 *
 * <h2>COBOL parity</h2>
 * <p>The canonical CBACT04C interest formula
 * {@code COMPUTE WS-MONTHLY-INT = (TRAN-CAT-BAL * DIS-INT-RATE) / 1200}
 * [app/cbl/CBACT04C.cbl L462-L470] is preserved exactly; its constant divisor
 * {@code 1200} is captured here as {@link #INTEREST_DIVISOR} and applied via
 * {@code scaledDivide(scaledMultiply(tranCatBal, disIntRate), INTEREST_DIVISOR)}.
 *
 * <h2>Comparison rule</h2>
 * <p>ALL comparisons use {@link BigDecimal#compareTo(BigDecimal)} and NEVER
 * {@link BigDecimal#equals(Object)}. This is the most common BigDecimal pitfall:
 * {@code equals} considers scale, so {@code new BigDecimal("100.00")} is NOT
 * {@code equals} to {@code new BigDecimal("100.0")}, whereas {@code compareTo}
 * correctly reports them as numerically equal.
 *
 * <h2>Null safety</h2>
 * <p>Every helper treats a {@code null} operand as {@link #ZERO} via
 * {@link #nullSafe(BigDecimal)}, mirroring the COBOL zero-initialized working
 * storage fields these calculations replace and eliminating
 * {@code NullPointerException} risk in tight calculation loops.
 *
 * <h2>Thread safety</h2>
 * <p>{@link BigDecimal} is immutable and this class is stateless; all constants
 * are safely shared across threads.
 */
public final class BigDecimalUtil {

    private BigDecimalUtil() {
        // Utility class: not instantiable. Intentionally empty (throws nothing) per the
        // final-checkpoint rule that utility constructors must not throw.
    }

    /**
     * Standard scale for monetary BigDecimal values: {@code 2} (two decimal places).
     *
     * <p>Matches COBOL {@code PIC S9(n)V99} fields used for all money columns
     * across the CardDemo data model (e.g., {@code ACCT-CURR-BAL},
     * {@code ACCT-CREDIT-LIMIT}, {@code TRAN-AMT}, {@code DIS-INT-RATE}).
     *
     * <p>Per PR-16, all monetary BigDecimal values stored in entities and
     * computed in business logic MUST use this scale.
     */
    public static final int SCALE_TWO = 2;

    /**
     * Standard rounding mode for monetary arithmetic: {@link RoundingMode#HALF_UP}.
     *
     * <p>Matches the COBOL {@code ROUNDED} clause semantics: half-values round
     * away from zero (e.g., {@code 0.005} &rarr; {@code 0.01},
     * {@code -0.005} &rarr; {@code -0.01}).
     *
     * <p>Per PR-16, all BigDecimal arithmetic that may produce additional decimal
     * places (multiplication, division) MUST apply this rounding mode.
     */
    public static final RoundingMode HALF_UP = RoundingMode.HALF_UP;

    /**
     * Constant divisor used in the CBACT04C monthly interest formula:
     * {@code monthlyInterest = (tranCatBal * disIntRate) / 1200}.
     *
     * <p>The {@code 1200} divisor reflects the COBOL formula in paragraph
     * {@code 1300-COMPUTE-INTEREST} [app/cbl/CBACT04C.cbl L462-L470]:
     * <pre>
     *   COMPUTE WS-MONTHLY-INT = ( TRAN-CAT-BAL * DIS-INT-RATE) / 1200
     * </pre>
     *
     * <p>The divisor of 1200 converts an annual interest rate percentage (e.g.,
     * {@code 12.00} = 12%) into a monthly multiplier:
     * annual rate / 100 (percent &rarr; fraction) / 12 (annual &rarr; monthly)
     * = rate / 1200.
     *
     * <p>Pre-allocated as a {@link BigDecimal} constant to avoid repeated
     * allocation in tight interest-calculation loops.
     */
    public static final BigDecimal INTEREST_DIVISOR = BigDecimal.valueOf(1200);

    /**
     * Constant zero with scale 2: {@code 0.00}.
     *
     * <p>Used as the null-safe replacement value for monetary fields. Always has
     * scale 2 (so {@code compareTo(ZERO) == 0} for any value semantically equal
     * to zero regardless of declared scale).
     */
    public static final BigDecimal ZERO = BigDecimal.ZERO.setScale(SCALE_TWO, HALF_UP);

    /**
     * Returns the given value or {@link #ZERO} if the value is {@code null}.
     *
     * <p>Cornerstone of null-safe monetary arithmetic. ALL helper methods in this
     * class call {@code nullSafe} on every BigDecimal argument before operating,
     * ensuring that null operands behave as the COBOL zero-initialized fields they
     * replace.
     *
     * @param value the value to null-check (may be {@code null})
     * @return {@code value} if non-null, otherwise {@link #ZERO}
     */
    public static BigDecimal nullSafe(BigDecimal value) {
        return value == null ? ZERO : value;
    }

    /**
     * Adds two BigDecimal values with null-safe handling, returning a result
     * scaled to {@link #SCALE_TWO} with {@link #HALF_UP} rounding.
     *
     * <p>Either operand may be {@code null} (treated as ZERO).
     *
     * <p>Example: {@code scaledAdd(new BigDecimal("100.00"), new BigDecimal("50.555"))}
     * returns {@code 150.56}.
     *
     * @param a the first operand (may be {@code null})
     * @param b the second operand (may be {@code null})
     * @return {@code a + b} scaled to 2 with HALF_UP
     */
    public static BigDecimal scaledAdd(BigDecimal a, BigDecimal b) {
        return nullSafe(a).add(nullSafe(b)).setScale(SCALE_TWO, HALF_UP);
    }

    /**
     * Subtracts {@code b} from {@code a} with null-safe handling, returning a
     * result scaled to {@link #SCALE_TWO} with {@link #HALF_UP} rounding.
     *
     * <p>Either operand may be {@code null} (treated as ZERO).
     *
     * @param a the minuend (may be {@code null})
     * @param b the subtrahend (may be {@code null})
     * @return {@code a - b} scaled to 2 with HALF_UP
     */
    public static BigDecimal scaledSubtract(BigDecimal a, BigDecimal b) {
        return nullSafe(a).subtract(nullSafe(b)).setScale(SCALE_TWO, HALF_UP);
    }

    /**
     * Multiplies two BigDecimal values with null-safe handling, returning a
     * result scaled to {@link #SCALE_TWO} with {@link #HALF_UP} rounding.
     *
     * <p>Either operand may be {@code null} (treated as ZERO, making the
     * product ZERO).
     *
     * @param a the first factor (may be {@code null})
     * @param b the second factor (may be {@code null})
     * @return {@code a * b} scaled to 2 with HALF_UP
     */
    public static BigDecimal scaledMultiply(BigDecimal a, BigDecimal b) {
        return nullSafe(a).multiply(nullSafe(b)).setScale(SCALE_TWO, HALF_UP);
    }

    /**
     * Divides {@code dividend} by {@code divisor} returning a result scaled to
     * {@link #SCALE_TWO} with {@link #HALF_UP} rounding. The divisor MUST NOT be
     * {@code null} or zero.
     *
     * <p>Dividend may be {@code null} (treated as ZERO, making the quotient ZERO).
     *
     * <p>Used by the CBACT04C interest formula:
     * {@code scaledDivide(tranCatBal.multiply(disIntRate), INTEREST_DIVISOR)}.
     *
     * @param dividend the dividend (may be {@code null})
     * @param divisor the divisor (must be non-null and non-zero)
     * @return {@code dividend / divisor} scaled to 2 with HALF_UP
     * @throws ArithmeticException if {@code divisor} is null or zero
     */
    public static BigDecimal scaledDivide(BigDecimal dividend, BigDecimal divisor) {
        if (divisor == null || divisor.signum() == 0) {
            throw new ArithmeticException("Divisor must not be null or zero");
        }
        return nullSafe(dividend).divide(divisor, SCALE_TWO, HALF_UP);
    }

    /**
     * Returns the sum of two BigDecimal values treating null operands as ZERO.
     *
     * <p>Equivalent to {@link #scaledAdd} but preserves the original scale of
     * the result (does NOT force scale 2). Use when callers need to control
     * scale themselves (e.g., intermediate calculations before a final setScale).
     *
     * @param a the first operand (may be {@code null})
     * @param b the second operand (may be {@code null})
     * @return {@code a + b} (scale not forced)
     */
    public static BigDecimal nullSafeAdd(BigDecimal a, BigDecimal b) {
        return nullSafe(a).add(nullSafe(b));
    }

    /**
     * Returns {@code true} if the value is strictly greater than zero.
     *
     * <p>Uses {@link BigDecimal#compareTo(BigDecimal)} (NEVER {@code equals})
     * per PR-16. A {@code null} value is treated as ZERO, so {@code isPositive(null)}
     * returns {@code false}.
     *
     * @param value the value to test (may be {@code null})
     * @return {@code true} if {@code value > 0}
     */
    public static boolean isPositive(BigDecimal value) {
        return nullSafe(value).compareTo(ZERO) > 0;
    }

    /**
     * Returns {@code true} if the value is strictly less than zero.
     *
     * <p>Uses {@link BigDecimal#compareTo(BigDecimal)} (NEVER {@code equals})
     * per PR-16. A {@code null} value is treated as ZERO, so {@code isNegative(null)}
     * returns {@code false}.
     *
     * @param value the value to test (may be {@code null})
     * @return {@code true} if {@code value < 0}
     */
    public static boolean isNegative(BigDecimal value) {
        return nullSafe(value).compareTo(ZERO) < 0;
    }

    /**
     * Returns {@code true} if the value is exactly zero.
     *
     * <p>Uses {@link BigDecimal#compareTo(BigDecimal)} (NEVER {@code equals})
     * per PR-16. A {@code null} value is treated as ZERO, so {@code isZero(null)}
     * returns {@code true}.
     *
     * <p>Critical: {@code BigDecimal.valueOf(0).equals(BigDecimal.ZERO)} returns
     * {@code true} but {@code new BigDecimal("0.00").equals(BigDecimal.ZERO)}
     * returns {@code false} (different scale). Always use this helper instead.
     *
     * @param value the value to test (may be {@code null})
     * @return {@code true} if {@code value == 0}
     */
    public static boolean isZero(BigDecimal value) {
        return nullSafe(value).compareTo(ZERO) == 0;
    }

    /**
     * Returns {@code true} if {@code a < b} (strict).
     *
     * <p>Uses {@link BigDecimal#compareTo(BigDecimal)} per PR-16. Either operand
     * may be {@code null} (treated as ZERO).
     *
     * <p>Used by CBTRN02C credit-limit check [app/cbl/CBTRN02C.cbl L393-L422]:
     * <pre>
     *   IF ACCT-CREDIT-LIMIT &lt; (ACCT-CURR-CYC-CREDIT - ACCT-CURR-CYC-DEBIT + DALYTRAN-AMT)
     *      MOVE 102 TO WS-RESP-CD  -- "OVERLIMIT TRANSACTION"
     * </pre>
     *
     * @param a the first operand
     * @param b the second operand
     * @return {@code true} if {@code a < b}
     */
    public static boolean isLessThan(BigDecimal a, BigDecimal b) {
        return nullSafe(a).compareTo(nullSafe(b)) < 0;
    }

    /**
     * Returns {@code true} if {@code a > b} (strict).
     *
     * <p>Uses {@link BigDecimal#compareTo(BigDecimal)} per PR-16. Either operand
     * may be {@code null} (treated as ZERO).
     *
     * @param a the first operand
     * @param b the second operand
     * @return {@code true} if {@code a > b}
     */
    public static boolean isGreaterThan(BigDecimal a, BigDecimal b) {
        return nullSafe(a).compareTo(nullSafe(b)) > 0;
    }

    /**
     * Returns {@code true} if {@code a <= b}.
     *
     * <p>Uses {@link BigDecimal#compareTo(BigDecimal)} per PR-16. Either operand
     * may be {@code null} (treated as ZERO).
     *
     * @param a the first operand
     * @param b the second operand
     * @return {@code true} if {@code a <= b}
     */
    public static boolean isLessThanOrEqual(BigDecimal a, BigDecimal b) {
        return nullSafe(a).compareTo(nullSafe(b)) <= 0;
    }

    /**
     * Returns {@code true} if {@code a >= b}.
     *
     * <p>Uses {@link BigDecimal#compareTo(BigDecimal)} per PR-16. Either operand
     * may be {@code null} (treated as ZERO).
     *
     * @param a the first operand
     * @param b the second operand
     * @return {@code true} if {@code a >= b}
     */
    public static boolean isGreaterThanOrEqual(BigDecimal a, BigDecimal b) {
        return nullSafe(a).compareTo(nullSafe(b)) >= 0;
    }

    /**
     * Returns {@code true} if {@code a} is numerically equal to {@code b}
     * (ignoring scale differences such as {@code 100.0} vs {@code 100.00}).
     *
     * <p>Uses {@link BigDecimal#compareTo(BigDecimal)} per PR-16 — DO NOT use
     * {@link BigDecimal#equals(Object)}, which considers scale (so
     * {@code new BigDecimal("100").equals(new BigDecimal("100.00"))} returns
     * {@code false}).
     *
     * <p>Either operand may be {@code null} (treated as ZERO).
     *
     * @param a the first operand
     * @param b the second operand
     * @return {@code true} if the values are numerically equal
     */
    public static boolean isEqual(BigDecimal a, BigDecimal b) {
        return nullSafe(a).compareTo(nullSafe(b)) == 0;
    }

    /**
     * Forces the given value to scale 2 with {@link #HALF_UP} rounding.
     *
     * <p>Convenience for mappers that receive BigDecimal from JSON deserialization
     * (which may produce scale 0 or scale &gt; 2) and need to normalize to the
     * standard money scale.
     *
     * <p>{@code null} input returns {@link #ZERO}.
     *
     * @param value the value to scale (may be {@code null})
     * @return the value scaled to 2 with HALF_UP, or {@code ZERO} if input is null
     */
    public static BigDecimal ensureScaleTwo(BigDecimal value) {
        return nullSafe(value).setScale(SCALE_TWO, HALF_UP);
    }
}
