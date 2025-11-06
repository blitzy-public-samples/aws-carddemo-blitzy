package com.carddemo.util;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * Numeric utility class providing thread-safe stateless helper methods for COBOL COMP-3 
 * packed decimal to Java BigDecimal conversion with explicit scale and RoundingMode.HALF_UP 
 * to maintain exact decimal precision and rounding behavior from mainframe monetary calculations.
 * 
 * <p>COBOL COMP-3 (packed decimal) format stores decimal numbers in a compressed binary-coded 
 * decimal representation where the decimal point is implied by the PICTURE clause. This utility 
 * ensures that all conversions to Java BigDecimal maintain the exact precision and rounding 
 * behavior of the original COBOL programs.</p>
 * 
 * <h2>COBOL to Java Mapping</h2>
 * <ul>
 *   <li>COBOL: PIC S9(10)V99 COMP-3 → Java: BigDecimal with precision=12, scale=2</li>
 *   <li>COBOL: COMPUTE result ROUNDED → Java: setScale with RoundingMode.HALF_UP</li>
 *   <li>COBOL: Implicit rounding → Java: Explicit RoundingMode.HALF_UP</li>
 * </ul>
 * 
 * <h2>Usage Examples</h2>
 * <pre>{@code
 * // Convert COBOL PIC S9(10)V99 COMP-3 field to BigDecimal
 * BigDecimal balance = NumericUtils.createDecimal(1234567890L, 2); // 12345678.90
 * 
 * // Apply COBOL-style rounding to calculation result
 * BigDecimal interest = principal.multiply(rate).divide(divisor, 10, RoundingMode.HALF_UP);
 * interest = NumericUtils.setScaleWithRounding(interest, 2); // Round to 2 decimal places
 * 
 * // Validate that value fits in COBOL PIC S9(10)V99
 * NumericUtils.validatePrecision(balance, 10, 2);
 * 
 * // Format monetary amount for display
 * String formatted = NumericUtils.formatMonetaryAmount(balance); // "12345678.90"
 * }</pre>
 * 
 * <p>All methods in this class are thread-safe and stateless, designed for use in concurrent 
 * Spring Boot service layer operations where multiple threads may perform financial calculations 
 * simultaneously.</p>
 * 
 * @see java.math.BigDecimal
 * @see java.math.RoundingMode
 * @since 1.0
 */
public final class NumericUtils {

    /**
     * Private constructor to prevent instantiation of utility class.
     * All methods are static and the class should never be instantiated.
     */
    private NumericUtils() {
        throw new UnsupportedOperationException("Utility class cannot be instantiated");
    }

    /**
     * Creates a BigDecimal from a long value with specified scale, matching COBOL PIC S9(n)V9(m) 
     * COMP-3 format where n+m is the total number of digits and m is the scale (decimal places).
     * 
     * <p>This method is the primary conversion point for transforming COBOL packed decimal values 
     * to Java BigDecimal. The long value represents the unscaled value (all digits including those 
     * after the implied decimal point), and the scale parameter indicates how many of those digits 
     * are fractional.</p>
     * 
     * <h3>COBOL Equivalent</h3>
     * <pre>
     * COBOL: 05 ACCT-CURR-BAL PIC S9(10)V99 COMP-3 VALUE 1234567890.
     * Java:  BigDecimal balance = NumericUtils.createDecimal(1234567890L, 2);
     * Result: 12345678.90
     * </pre>
     * 
     * <h3>Common Use Cases</h3>
     * <ul>
     *   <li>Account balances: PIC S9(10)V99 → createDecimal(value, 2)</li>
     *   <li>Credit limits: PIC S9(10)V99 → createDecimal(value, 2)</li>
     *   <li>Transaction amounts: PIC S9(10)V99 → createDecimal(value, 2)</li>
     *   <li>Interest rates: PIC S9(3)V9(5) → createDecimal(value, 5)</li>
     * </ul>
     * 
     * @param unscaledValue the unscaled long value containing all digits (e.g., 1234567890 for 12345678.90)
     * @param scale the number of decimal places (must be non-negative and ≤ 18)
     * @return BigDecimal with the specified scale and RoundingMode.HALF_UP
     * @throws IllegalArgumentException if scale is negative or greater than 18
     * @see #setScaleWithRounding(BigDecimal, int)
     */
    public static BigDecimal createDecimal(long unscaledValue, int scale) {
        validateScale(scale);
        
        // Use BigDecimal.valueOf to create from long, then apply scale with HALF_UP rounding
        // This matches COBOL behavior where the decimal point is implied
        BigDecimal value = BigDecimal.valueOf(unscaledValue);
        
        // Move decimal point to the left by 'scale' positions
        // Example: 1234567890 with scale 2 becomes 12345678.90
        return value.movePointLeft(scale).setScale(scale, RoundingMode.HALF_UP);
    }

    /**
     * Applies scale to a BigDecimal with HALF_UP rounding mode, matching COBOL COMPUTE statement 
     * rounding behavior where midpoint values (.5) round away from zero.
     * 
     * <p>This method ensures consistent rounding across all financial calculations, replicating 
     * the implicit rounding that COBOL applies during arithmetic operations. COBOL's COMPUTE 
     * statement automatically rounds intermediate results, and this method provides the explicit 
     * equivalent in Java.</p>
     * 
     * <h3>COBOL Equivalent</h3>
     * <pre>
     * COBOL: COMPUTE INTEREST-AMT ROUNDED = PRINCIPAL * RATE / 365
     * Java:  BigDecimal interest = principal.multiply(rate).divide(divisor, 10, RoundingMode.HALF_UP);
     *        interest = NumericUtils.setScaleWithRounding(interest, 2);
     * </pre>
     * 
     * <h3>Rounding Examples</h3>
     * <ul>
     *   <li>12.345 with scale 2 → 12.35 (rounds up)</li>
     *   <li>12.344 with scale 2 → 12.34 (rounds down)</li>
     *   <li>12.005 with scale 2 → 12.01 (rounds up, matching COBOL)</li>
     *   <li>-12.345 with scale 2 → -12.35 (rounds away from zero)</li>
     * </ul>
     * 
     * @param value the BigDecimal value to scale (must not be null)
     * @param scale the target number of decimal places (must be non-negative and ≤ 18)
     * @return BigDecimal with the specified scale using RoundingMode.HALF_UP
     * @throws IllegalArgumentException if value is null or scale is invalid
     * @see java.math.RoundingMode#HALF_UP
     */
    public static BigDecimal setScaleWithRounding(BigDecimal value, int scale) {
        validateNotNull(value, "value");
        validateScale(scale);
        
        // Apply scale with HALF_UP rounding to match COBOL COMPUTE behavior
        // If value already has the target scale, this is a no-op
        return value.setScale(scale, RoundingMode.HALF_UP);
    }

    /**
     * Validates that a BigDecimal value fits within COBOL PIC clause constraints, ensuring 
     * the number of integer and fractional digits does not exceed the specified limits.
     * 
     * <p>COBOL PICTURE clauses define strict constraints on numeric field sizes. This validation 
     * ensures that values converted to BigDecimal or calculated in Java will fit back into the 
     * equivalent COBOL field definition without overflow or truncation.</p>
     * 
     * <h3>COBOL Equivalent</h3>
     * <pre>
     * COBOL: 05 ACCT-CREDIT-LIMIT PIC S9(10)V99 COMP-3.
     * Java:  NumericUtils.validatePrecision(creditLimit, 10, 2);
     * 
     * Valid:   12345678.90  (10 integer digits, 2 fractional)
     * Invalid: 12345678901.90  (11 integer digits, exceeds constraint)
     * Invalid: 123456.789  (3 fractional digits, exceeds constraint)
     * </pre>
     * 
     * <h3>Validation Rules</h3>
     * <ul>
     *   <li>Integer digits: Count of digits before decimal point must be ≤ integerDigits</li>
     *   <li>Fractional digits: Count of digits after decimal point must be ≤ fractionalDigits</li>
     *   <li>Zero value: Always valid regardless of constraints</li>
     *   <li>Negative values: Sign is not counted in digit limits</li>
     * </ul>
     * 
     * @param value the BigDecimal value to validate (must not be null)
     * @param integerDigits maximum number of integer digits allowed (must be positive and ≤ 38)
     * @param fractionalDigits maximum number of fractional digits allowed (must be non-negative and ≤ 18)
     * @throws IllegalArgumentException if value is null, parameters are invalid, or value exceeds constraints
     * @see #validateIntegerDigits(int)
     * @see #validateFractionalDigits(int)
     */
    public static void validatePrecision(BigDecimal value, int integerDigits, int fractionalDigits) {
        validateNotNull(value, "value");
        validateIntegerDigits(integerDigits);
        validateFractionalDigits(fractionalDigits);
        
        // Skip validation for zero value
        if (value.compareTo(BigDecimal.ZERO) == 0) {
            return;
        }
        
        // Get the scale (number of fractional digits)
        int actualScale = value.scale();
        
        // Get the precision (total number of digits)
        int actualPrecision = value.precision();
        
        // Calculate actual integer digits: precision - scale
        // For negative scale (e.g., 1.23E+5), we need to handle specially
        int actualIntegerDigits;
        if (actualScale < 0) {
            // Negative scale means trailing zeros (e.g., 123000 has scale -3)
            actualIntegerDigits = actualPrecision + Math.abs(actualScale);
        } else {
            actualIntegerDigits = actualPrecision - actualScale;
        }
        
        // Validate integer digits constraint
        if (actualIntegerDigits > integerDigits) {
            throw new IllegalArgumentException(
                String.format("Value %s has %d integer digits, exceeds maximum of %d (COBOL PIC S9(%d)V9(%d))",
                    value, actualIntegerDigits, integerDigits, integerDigits, fractionalDigits));
        }
        
        // Validate fractional digits constraint
        // Use Math.max to handle negative scale (no fractional digits)
        int actualFractionalDigits = Math.max(0, actualScale);
        if (actualFractionalDigits > fractionalDigits) {
            throw new IllegalArgumentException(
                String.format("Value %s has %d fractional digits, exceeds maximum of %d (COBOL PIC S9(%d)V9(%d))",
                    value, actualFractionalDigits, fractionalDigits, integerDigits, fractionalDigits));
        }
    }

    /**
     * Formats a monetary BigDecimal amount with exactly 2 decimal places, suitable for display 
     * or logging of financial values.
     * 
     * <p>This method ensures consistent formatting of all monetary values throughout the application, 
     * matching the COBOL display format for currency fields. The output always includes exactly 
     * 2 decimal places, even for whole dollar amounts (e.g., "100.00" not "100").</p>
     * 
     * <h3>COBOL Equivalent</h3>
     * <pre>
     * COBOL: DISPLAY "Balance: " ACCT-CURR-BAL
     *        (where ACCT-CURR-BAL PIC S9(10)V99 displays as 12345678.90)
     * Java:  String formatted = NumericUtils.formatMonetaryAmount(balance);
     *        logger.info("Balance: {}", formatted);
     * </pre>
     * 
     * <h3>Formatting Examples</h3>
     * <ul>
     *   <li>12345678.9 → "12345678.90"</li>
     *   <li>100 → "100.00"</li>
     *   <li>0.5 → "0.50"</li>
     *   <li>-1234.56 → "-1234.56"</li>
     * </ul>
     * 
     * @param amount the monetary amount to format (must not be null)
     * @return string representation with exactly 2 decimal places
     * @throws IllegalArgumentException if amount is null
     * @see #setScaleWithRounding(BigDecimal, int)
     */
    public static String formatMonetaryAmount(BigDecimal amount) {
        validateNotNull(amount, "amount");
        
        // Ensure exactly 2 decimal places with HALF_UP rounding
        BigDecimal scaledAmount = setScaleWithRounding(amount, 2);
        
        // Convert to string - BigDecimal.toString() always shows the scale
        return scaledAmount.toString();
    }

    /**
     * Validates that a BigDecimal value is not null, throwing IllegalArgumentException with 
     * a descriptive message if null.
     * 
     * <p>This is a helper method used internally by other validation methods to ensure consistent 
     * null checking and error messaging across the utility class.</p>
     * 
     * @param value the BigDecimal value to check
     * @param parameterName the name of the parameter being validated (for error message)
     * @throws IllegalArgumentException if value is null
     */
    public static void validateNotNull(BigDecimal value, String parameterName) {
        if (value == null) {
            throw new IllegalArgumentException(
                String.format("Parameter '%s' must not be null", parameterName));
        }
    }

    /**
     * Validates that a scale value is non-negative and does not exceed PostgreSQL NUMERIC maximum 
     * scale of 16383, with practical limit of 18 for COBOL compatibility.
     * 
     * <p>COBOL COMP-3 fields typically have scale (decimal places) ranging from 0 to 9, but 
     * this validation allows up to 18 to accommodate edge cases while staying within PostgreSQL 
     * practical limits and Java BigDecimal reasonable performance characteristics.</p>
     * 
     * <h3>Typical COBOL Scales</h3>
     * <ul>
     *   <li>Monetary amounts: scale=2 (dollars and cents)</li>
     *   <li>Interest rates: scale=5 or scale=6 (percentage with precision)</li>
     *   <li>Quantity fields: scale=0 (whole numbers)</li>
     *   <li>Unit prices: scale=4 (high precision pricing)</li>
     * </ul>
     * 
     * @param scale the scale value to validate
     * @throws IllegalArgumentException if scale is negative or greater than 18
     */
    public static void validateScale(int scale) {
        if (scale < 0) {
            throw new IllegalArgumentException(
                String.format("Scale must be non-negative, got: %d", scale));
        }
        if (scale > 18) {
            throw new IllegalArgumentException(
                String.format("Scale must not exceed 18 (COBOL compatibility limit), got: %d", scale));
        }
    }

    /**
     * Validates that integer digits constraint is positive and does not exceed PostgreSQL NUMERIC 
     * maximum precision of 131072, with practical limit of 38 for COBOL compatibility.
     * 
     * <p>COBOL COMP-3 fields typically have 1 to 18 integer digits. This validation allows up to 
     * 38 digits to match PostgreSQL's practical precision limit and accommodate any COBOL field 
     * definition while ensuring calculations remain performant.</p>
     * 
     * <h3>Typical COBOL Integer Digits</h3>
     * <ul>
     *   <li>Account numbers: 9-11 digits (PIC 9(11))</li>
     *   <li>Balances: 10 digits (PIC S9(10)V99)</li>
     *   <li>Transaction amounts: 8-10 digits (PIC S9(10)V99)</li>
     *   <li>Customer IDs: 9 digits (PIC 9(9))</li>
     * </ul>
     * 
     * @param integerDigits the integer digits constraint to validate
     * @throws IllegalArgumentException if integerDigits is not positive or exceeds 38
     */
    public static void validateIntegerDigits(int integerDigits) {
        if (integerDigits <= 0) {
            throw new IllegalArgumentException(
                String.format("Integer digits must be positive, got: %d", integerDigits));
        }
        if (integerDigits > 38) {
            throw new IllegalArgumentException(
                String.format("Integer digits must not exceed 38 (PostgreSQL NUMERIC precision limit), got: %d", 
                    integerDigits));
        }
    }

    /**
     * Validates that fractional digits constraint is non-negative and does not exceed practical 
     * limit of 18 for COBOL compatibility and PostgreSQL NUMERIC performance.
     * 
     * <p>COBOL COMP-3 fields typically have 0 to 9 fractional digits. This validation allows up to 
     * 18 decimal places to accommodate high-precision financial calculations while maintaining 
     * compatibility with COBOL field definitions and PostgreSQL NUMERIC type constraints.</p>
     * 
     * <h3>Typical COBOL Fractional Digits</h3>
     * <ul>
     *   <li>Monetary amounts: 2 digits (cents)</li>
     *   <li>Interest rates: 5-6 digits (percentage precision)</li>
     *   <li>Exchange rates: 4-6 digits (currency conversion precision)</li>
     *   <li>Unit costs: 4 digits (high precision pricing)</li>
     * </ul>
     * 
     * @param fractionalDigits the fractional digits constraint to validate
     * @throws IllegalArgumentException if fractionalDigits is negative or exceeds 18
     */
    public static void validateFractionalDigits(int fractionalDigits) {
        if (fractionalDigits < 0) {
            throw new IllegalArgumentException(
                String.format("Fractional digits must be non-negative, got: %d", fractionalDigits));
        }
        if (fractionalDigits > 18) {
            throw new IllegalArgumentException(
                String.format("Fractional digits must not exceed 18 (COBOL compatibility limit), got: %d", 
                    fractionalDigits));
        }
    }
}
