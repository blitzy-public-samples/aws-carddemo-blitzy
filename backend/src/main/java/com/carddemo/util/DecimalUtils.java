package com.carddemo.util;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.NumberFormat;
import java.util.Locale;

/**
 * BigDecimal utility class providing COMP-3 packed decimal equivalence operations 
 * with exact scale and RoundingMode.HALF_UP preservation for financial calculations.
 * 
 * <p>This utility is CRITICAL for maintaining COBOL numeric precision in Java,
 * ensuring that all monetary and interest computations produce identical results
 * to the legacy mainframe COBOL COMP-3 arithmetic operations.</p>
 * 
 * <p><strong>COBOL COMP-3 Precision Mapping:</strong></p>
 * <ul>
 *   <li>PIC S9(13)V99 COMP-3 → BigDecimal with scale=2 (money amounts)</li>
 *   <li>PIC S9(3)V9(5) COMP-3 → BigDecimal with scale=5 (interest rates)</li>
 *   <li>PIC S9(n)V99 COMP-3 → BigDecimal with scale=2 (percentages)</li>
 * </ul>
 * 
 * <p><strong>Rounding Mode:</strong> All arithmetic operations use RoundingMode.HALF_UP
 * to match COBOL ROUNDED clause behavior (round to nearest neighbor, ties round up).</p>
 * 
 * <p>All methods are static and the class cannot be instantiated.</p>
 * 
 * @version 1.0
 * @since 1.0
 */
public final class DecimalUtils {
    
    /**
     * Scale for monetary amounts (2 decimal places).
     * Maps to COBOL PIC S9(13)V99 COMP-3 fields used for balances,
     * transaction amounts, and other currency values.
     */
    public static final int MONEY_SCALE = 2;
    
    /**
     * Scale for interest rates (5 decimal places).
     * Maps to COBOL PIC S9(3)V9(5) COMP-3 fields used for
     * annual percentage rates, daily interest rates, and other
     * high-precision rate calculations.
     */
    public static final int INTEREST_RATE_SCALE = 5;
    
    /**
     * Scale for percentage values (2 decimal places).
     * Maps to COBOL percentage fields requiring standard
     * 2-decimal precision for display and calculation.
     */
    public static final int PERCENTAGE_SCALE = 2;
    
    /**
     * BigDecimal constant for zero with money scale.
     */
    private static final BigDecimal ZERO_MONEY = BigDecimal.ZERO.setScale(MONEY_SCALE, RoundingMode.HALF_UP);
    
    /**
     * BigDecimal constant for one hundred for percentage conversions.
     */
    private static final BigDecimal ONE_HUNDRED = new BigDecimal("100");
    
    /**
     * Maximum precision for monetary amounts (15 digits total: 13 integer + 2 decimal).
     * Matches COBOL PIC S9(13)V99 COMP-3 field definition.
     */
    private static final int MONEY_PRECISION = 15;
    
    /**
     * Maximum precision for interest rates (8 digits total: 3 integer + 5 decimal).
     * Matches COBOL PIC S9(3)V9(5) COMP-3 field definition.
     */
    private static final int INTEREST_RATE_PRECISION = 8;
    
    /**
     * Private constructor to prevent instantiation of utility class.
     */
    private DecimalUtils() {
        throw new AssertionError("DecimalUtils is a utility class and should not be instantiated");
    }
    
    /**
     * Creates a BigDecimal from a string value with specified scale and HALF_UP rounding.
     * This is the base factory method for all decimal value creation.
     * 
     * @param value the string representation of the decimal value
     * @param scale the number of decimal places to maintain
     * @return BigDecimal with the specified scale and HALF_UP rounding mode
     * @throws IllegalArgumentException if value is null or invalid number format
     */
    public static BigDecimal createDecimal(String value, int scale) {
        if (value == null || value.trim().isEmpty()) {
            throw new IllegalArgumentException("Decimal value cannot be null or empty");
        }
        
        try {
            BigDecimal decimal = new BigDecimal(value.trim());
            return decimal.setScale(scale, RoundingMode.HALF_UP);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Invalid decimal format: " + value, e);
        }
    }
    
    /**
     * Creates a BigDecimal money amount from string with 2 decimal places.
     * Maps to COBOL PIC S9(13)V99 COMP-3 monetary fields.
     * 
     * <p>Example: createMoneyAmount("1234.56") → 1234.56</p>
     * <p>Example: createMoneyAmount("1234.567") → 1234.57 (rounded)</p>
     * 
     * @param value the string representation of the money amount
     * @return BigDecimal with scale=2 and HALF_UP rounding
     * @throws IllegalArgumentException if value is null or invalid
     */
    public static BigDecimal createMoneyAmount(String value) {
        return createDecimal(value, MONEY_SCALE);
    }
    
    /**
     * Creates a BigDecimal money amount from double with 2 decimal places.
     * Maps to COBOL PIC S9(13)V99 COMP-3 monetary fields.
     * 
     * <p><strong>Warning:</strong> Use string-based factory methods when possible
     * to avoid floating-point precision issues inherent in double representation.</p>
     * 
     * <p>Example: createMoneyAmount(1234.56) → 1234.56</p>
     * 
     * @param value the double value to convert to money amount
     * @return BigDecimal with scale=2 and HALF_UP rounding
     */
    public static BigDecimal createMoneyAmount(double value) {
        return BigDecimal.valueOf(value).setScale(MONEY_SCALE, RoundingMode.HALF_UP);
    }
    
    /**
     * Creates a BigDecimal interest rate from string with 5 decimal places.
     * Maps to COBOL PIC S9(3)V9(5) COMP-3 interest rate fields.
     * 
     * <p>Example: createInterestRate("0.04567") → 0.04567</p>
     * <p>Example: createInterestRate("4.5") → 4.50000</p>
     * 
     * @param value the string representation of the interest rate
     * @return BigDecimal with scale=5 and HALF_UP rounding
     * @throws IllegalArgumentException if value is null or invalid
     */
    public static BigDecimal createInterestRate(String value) {
        return createDecimal(value, INTEREST_RATE_SCALE);
    }
    
    /**
     * Creates a BigDecimal percentage from string with 2 decimal places.
     * Maps to COBOL percentage fields with 2-decimal precision.
     * 
     * <p>Example: createPercentage("12.50") → 12.50</p>
     * <p>Example: createPercentage("12.567") → 12.57 (rounded)</p>
     * 
     * @param value the string representation of the percentage
     * @return BigDecimal with scale=2 and HALF_UP rounding
     * @throws IllegalArgumentException if value is null or invalid
     */
    public static BigDecimal createPercentage(String value) {
        return createDecimal(value, PERCENTAGE_SCALE);
    }
    
    /**
     * Parses a string to BigDecimal with automatic scale detection.
     * Preserves the scale from the input string if valid.
     * 
     * <p>This method is useful for parsing COBOL-formatted numbers that
     * may have varying decimal precision based on their field definitions.</p>
     * 
     * @param value the string to parse
     * @return BigDecimal representation of the string
     * @throws IllegalArgumentException if value is null or invalid number format
     */
    public static BigDecimal fromString(String value) {
        if (value == null || value.trim().isEmpty()) {
            throw new IllegalArgumentException("Value cannot be null or empty");
        }
        
        try {
            return new BigDecimal(value.trim());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Invalid number format: " + value, e);
        }
    }
    
    /**
     * Safely adds two BigDecimal values maintaining the maximum scale of the operands.
     * Uses RoundingMode.HALF_UP to match COBOL ROUNDED clause behavior.
     * 
     * <p>Example: safeAdd(1.23, 4.56) → 5.79</p>
     * <p>Handles null inputs by treating them as zero.</p>
     * 
     * @param augend the first value (can be null)
     * @param addend the second value (can be null)
     * @return the sum with appropriate scale and HALF_UP rounding
     */
    public static BigDecimal safeAdd(BigDecimal augend, BigDecimal addend) {
        if (augend == null && addend == null) {
            return ZERO_MONEY;
        }
        if (augend == null) {
            return addend;
        }
        if (addend == null) {
            return augend;
        }
        
        int resultScale = Math.max(augend.scale(), addend.scale());
        BigDecimal result = augend.add(addend);
        return result.setScale(resultScale, RoundingMode.HALF_UP);
    }
    
    /**
     * Safely subtracts two BigDecimal values maintaining the maximum scale of the operands.
     * Uses RoundingMode.HALF_UP to match COBOL ROUNDED clause behavior.
     * 
     * <p>Example: safeSubtract(10.00, 3.50) → 6.50</p>
     * <p>Handles null inputs by treating them as zero.</p>
     * 
     * @param minuend the value to subtract from (can be null)
     * @param subtrahend the value to subtract (can be null)
     * @return the difference with appropriate scale and HALF_UP rounding
     */
    public static BigDecimal safeSubtract(BigDecimal minuend, BigDecimal subtrahend) {
        if (minuend == null && subtrahend == null) {
            return ZERO_MONEY;
        }
        if (minuend == null) {
            return subtrahend.negate();
        }
        if (subtrahend == null) {
            return minuend;
        }
        
        int resultScale = Math.max(minuend.scale(), subtrahend.scale());
        BigDecimal result = minuend.subtract(subtrahend);
        return result.setScale(resultScale, RoundingMode.HALF_UP);
    }
    
    /**
     * Safely multiplies two BigDecimal values maintaining appropriate precision.
     * Uses RoundingMode.HALF_UP to match COBOL ROUNDED clause behavior.
     * 
     * <p>The result scale is the sum of both operand scales, then adjusted
     * with HALF_UP rounding to prevent excessive decimal places.</p>
     * 
     * <p>Example: safeMultiply(100.00, 0.045) → 4.50</p>
     * <p>Handles null inputs by treating them as zero.</p>
     * 
     * @param multiplicand the first value (can be null)
     * @param multiplier the second value (can be null)
     * @return the product with appropriate scale and HALF_UP rounding
     */
    public static BigDecimal safeMultiply(BigDecimal multiplicand, BigDecimal multiplier) {
        if (multiplicand == null || multiplier == null) {
            return ZERO_MONEY;
        }
        
        BigDecimal result = multiplicand.multiply(multiplier);
        int resultScale = multiplicand.scale() + multiplier.scale();
        return result.setScale(resultScale, RoundingMode.HALF_UP);
    }
    
    /**
     * Safely divides two BigDecimal values with specified scale for the result.
     * Uses RoundingMode.HALF_UP to match COBOL ROUNDED clause behavior.
     * 
     * <p>This method prevents ArithmeticException from division operations
     * that would produce non-terminating decimal expansions.</p>
     * 
     * <p>Example: safeDivide(100.00, 3.00) → 33.33 (with scale=2)</p>
     * 
     * @param dividend the value to be divided (cannot be null)
     * @param divisor the value to divide by (cannot be null or zero)
     * @param scale the scale of the quotient
     * @return the quotient with specified scale and HALF_UP rounding
     * @throws IllegalArgumentException if dividend or divisor is null
     * @throws ArithmeticException if divisor is zero
     */
    public static BigDecimal safeDivide(BigDecimal dividend, BigDecimal divisor, int scale) {
        if (dividend == null) {
            throw new IllegalArgumentException("Dividend cannot be null");
        }
        if (divisor == null) {
            throw new IllegalArgumentException("Divisor cannot be null");
        }
        if (divisor.compareTo(BigDecimal.ZERO) == 0) {
            throw new ArithmeticException("Division by zero");
        }
        
        return dividend.divide(divisor, scale, RoundingMode.HALF_UP);
    }
    
    /**
     * Safely divides two BigDecimal values using the dividend's scale for the result.
     * Uses RoundingMode.HALF_UP to match COBOL ROUNDED clause behavior.
     * 
     * <p>Overloaded convenience method that uses dividend scale as result scale.</p>
     * 
     * @param dividend the value to be divided (cannot be null)
     * @param divisor the value to divide by (cannot be null or zero)
     * @return the quotient with dividend's scale and HALF_UP rounding
     * @throws IllegalArgumentException if dividend or divisor is null
     * @throws ArithmeticException if divisor is zero
     */
    public static BigDecimal safeDivide(BigDecimal dividend, BigDecimal divisor) {
        if (dividend == null) {
            throw new IllegalArgumentException("Dividend cannot be null");
        }
        return safeDivide(dividend, divisor, dividend.scale());
    }
    
    /**
     * Checks if a BigDecimal value is zero.
     * Handles null by returning true (treating null as zero).
     * 
     * @param value the value to check (can be null)
     * @return true if value is null or equals zero, false otherwise
     */
    public static boolean isZero(BigDecimal value) {
        if (value == null) {
            return true;
        }
        return value.compareTo(BigDecimal.ZERO) == 0;
    }
    
    /**
     * Checks if a BigDecimal value is positive (greater than zero).
     * Handles null by returning false.
     * 
     * @param value the value to check (can be null)
     * @return true if value is greater than zero, false otherwise
     */
    public static boolean isPositive(BigDecimal value) {
        if (value == null) {
            return false;
        }
        return value.compareTo(BigDecimal.ZERO) > 0;
    }
    
    /**
     * Checks if a BigDecimal value is negative (less than zero).
     * Handles null by returning false.
     * 
     * @param value the value to check (can be null)
     * @return true if value is less than zero, false otherwise
     */
    public static boolean isNegative(BigDecimal value) {
        if (value == null) {
            return false;
        }
        return value.compareTo(BigDecimal.ZERO) < 0;
    }
    
    /**
     * Compares two BigDecimal values for equality with scale-aware comparison.
     * Uses compareTo() to ensure 2.00 equals 2.0.
     * 
     * <p>Handles null values: two nulls are equal, null is not equal to non-null.</p>
     * 
     * @param first the first value to compare (can be null)
     * @param second the second value to compare (can be null)
     * @return true if both values are equal (or both null), false otherwise
     */
    public static boolean equals(BigDecimal first, BigDecimal second) {
        if (first == null && second == null) {
            return true;
        }
        if (first == null || second == null) {
            return false;
        }
        return first.compareTo(second) == 0;
    }
    
    /**
     * Applies scale and RoundingMode.HALF_UP to a BigDecimal value.
     * This is a convenience method for consistent scale adjustment across the application.
     * 
     * @param value the value to adjust (can be null)
     * @param scale the desired scale
     * @return the value with the specified scale and HALF_UP rounding, or null if input is null
     */
    public static BigDecimal setScaleWithRounding(BigDecimal value, int scale) {
        if (value == null) {
            return null;
        }
        return value.setScale(scale, RoundingMode.HALF_UP);
    }
    
    /**
     * Converts BigDecimal to plain string representation without scientific notation.
     * Preserves exact decimal representation matching COBOL display format.
     * 
     * <p>Example: toPlainString(1234.56) → "1234.56"</p>
     * <p>Example: toPlainString(0.00001) → "0.00001" (not "1E-5")</p>
     * 
     * @param value the value to convert (can be null)
     * @return plain string representation, or "0.00" if null
     */
    public static String toPlainString(BigDecimal value) {
        if (value == null) {
            return ZERO_MONEY.toPlainString();
        }
        return value.toPlainString();
    }
    
    /**
     * Formats BigDecimal as currency with locale-specific formatting.
     * Includes currency symbol, thousands separators, and proper decimal places.
     * 
     * <p>Uses US locale by default to match mainframe US-based deployment.</p>
     * 
     * <p>Example: toFormattedCurrency(1234.56) → "$1,234.56"</p>
     * <p>Example: toFormattedCurrency(1000000.00) → "$1,000,000.00"</p>
     * 
     * @param value the value to format (can be null)
     * @return formatted currency string with symbol and separators, or "$0.00" if null
     */
    public static String toFormattedCurrency(BigDecimal value) {
        if (value == null) {
            value = ZERO_MONEY;
        }
        
        NumberFormat currencyFormatter = NumberFormat.getCurrencyInstance(Locale.US);
        currencyFormatter.setRoundingMode(RoundingMode.HALF_UP);
        return currencyFormatter.format(value);
    }
    
    /**
     * Formats BigDecimal as currency with specified locale.
     * Allows internationalization of currency display.
     * 
     * @param value the value to format (can be null)
     * @param locale the locale for formatting (if null, uses US locale)
     * @return formatted currency string with locale-specific symbol and separators
     */
    public static String toFormattedCurrency(BigDecimal value, Locale locale) {
        if (value == null) {
            value = ZERO_MONEY;
        }
        if (locale == null) {
            locale = Locale.US;
        }
        
        NumberFormat currencyFormatter = NumberFormat.getCurrencyInstance(locale);
        currencyFormatter.setRoundingMode(RoundingMode.HALF_UP);
        return currencyFormatter.format(value);
    }
    
    /**
     * Validates that a BigDecimal conforms to expected precision and scale constraints.
     * This ensures values match COBOL COMP-3 field definitions.
     * 
     * <p>Precision is the total number of digits (integer + decimal).
     * Scale is the number of decimal places.</p>
     * 
     * <p>Example: validatePrecisionAndScale(1234.56, 6, 2) → true (4 integer + 2 decimal = 6 total)</p>
     * <p>Example: validatePrecisionAndScale(12345.67, 6, 2) → false (exceeds precision)</p>
     * 
     * @param value the value to validate (cannot be null)
     * @param expectedPrecision the maximum total number of digits
     * @param expectedScale the expected number of decimal places
     * @return true if value conforms to constraints, false otherwise
     * @throws IllegalArgumentException if value is null
     */
    public static boolean validatePrecisionAndScale(BigDecimal value, int expectedPrecision, int expectedScale) {
        if (value == null) {
            throw new IllegalArgumentException("Value cannot be null for validation");
        }
        
        // Check scale
        if (value.scale() != expectedScale) {
            return false;
        }
        
        // Check precision (total digits)
        int actualPrecision = value.precision();
        if (actualPrecision > expectedPrecision) {
            return false;
        }
        
        return true;
    }
    
    /**
     * Validates that a money amount conforms to COBOL PIC S9(13)V99 COMP-3 constraints.
     * Ensures maximum 13 integer digits and exactly 2 decimal places.
     * 
     * @param value the money amount to validate (cannot be null)
     * @return true if value is valid money amount, false otherwise
     * @throws IllegalArgumentException if value is null
     */
    public static boolean validateMoneyAmount(BigDecimal value) {
        if (value == null) {
            throw new IllegalArgumentException("Money amount cannot be null for validation");
        }
        
        // Must have exactly 2 decimal places
        BigDecimal scaledValue = value.setScale(MONEY_SCALE, RoundingMode.HALF_UP);
        if (value.compareTo(scaledValue) != 0) {
            return false;
        }
        
        // Check total precision doesn't exceed COBOL limit
        if (value.precision() > MONEY_PRECISION) {
            return false;
        }
        
        return true;
    }
    
    /**
     * Validates that an interest rate conforms to COBOL PIC S9(3)V9(5) COMP-3 constraints.
     * Ensures maximum 3 integer digits and exactly 5 decimal places.
     * 
     * @param value the interest rate to validate (cannot be null)
     * @return true if value is valid interest rate, false otherwise
     * @throws IllegalArgumentException if value is null
     */
    public static boolean validateInterestRate(BigDecimal value) {
        if (value == null) {
            throw new IllegalArgumentException("Interest rate cannot be null for validation");
        }
        
        // Must have exactly 5 decimal places
        BigDecimal scaledValue = value.setScale(INTEREST_RATE_SCALE, RoundingMode.HALF_UP);
        if (value.compareTo(scaledValue) != 0) {
            return false;
        }
        
        // Check total precision doesn't exceed COBOL limit
        if (value.precision() > INTEREST_RATE_PRECISION) {
            return false;
        }
        
        return true;
    }
    
    /**
     * Converts a percentage value to decimal rate for calculations.
     * Example: 4.5% → 0.045
     * 
     * @param percentage the percentage value (e.g., 4.5 for 4.5%)
     * @return the decimal equivalent with appropriate scale
     * @throws IllegalArgumentException if percentage is null
     */
    public static BigDecimal percentageToRate(BigDecimal percentage) {
        if (percentage == null) {
            throw new IllegalArgumentException("Percentage cannot be null");
        }
        return safeDivide(percentage, ONE_HUNDRED, INTEREST_RATE_SCALE);
    }
    
    /**
     * Converts a decimal rate to percentage value for display.
     * Example: 0.045 → 4.5%
     * 
     * @param rate the decimal rate (e.g., 0.045)
     * @return the percentage equivalent with 2 decimal places
     * @throws IllegalArgumentException if rate is null
     */
    public static BigDecimal rateToPercentage(BigDecimal rate) {
        if (rate == null) {
            throw new IllegalArgumentException("Rate cannot be null");
        }
        return safeMultiply(rate, ONE_HUNDRED).setScale(PERCENTAGE_SCALE, RoundingMode.HALF_UP);
    }
}

