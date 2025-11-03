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

package com.carddemo.util;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.regex.Pattern;

/**
 * ValidationUtils - Comprehensive field validation utility class providing reusable
 * validation methods supporting COBOL field validation patterns.
 * 
 * <p>This class provides validation methods for:
 * <ul>
 *   <li>Numeric fields (PIC 9) - digit-only validation</li>
 *   <li>Alphabetic fields (PIC A) - letter-only validation</li>
 *   <li>Alphanumeric fields (PIC X) - letters and digits validation</li>
 *   <li>Required field checks - LOW-VALUES and SPACES equivalence</li>
 *   <li>Length validation - PIC clause length constraints</li>
 *   <li>Range validation - numeric and date boundaries</li>
 *   <li>Format validation - email, phone, ZIP, account numbers, card numbers</li>
 * </ul>
 * 
 * <p>All validation methods return {@link ValidationResult} objects containing
 * a boolean success flag and error message string, enabling integration with
 * Bean Validation framework and providing COBOL-style validation feedback for
 * form field validation in React components and REST API request validation.
 * 
 * <p>COBOL Validation Pattern Equivalence:
 * <ul>
 *   <li>LOW-VALUES check → isEmpty() method</li>
 *   <li>SPACES check → validateNotBlank() method</li>
 *   <li>PIC 9 validation → isNumeric() method</li>
 *   <li>PIC A validation → isAlphabetic() method</li>
 *   <li>PIC X validation → isAlphanumeric() method</li>
 *   <li>PIC clause length → validateLength() methods</li>
 * </ul>
 * 
 * <p><strong>Note:</strong> This is a static utility class and cannot be instantiated.
 * All methods are static and should be called directly on the class.
 * 
 * @version CardDemo_v1.0 COBOL-to-Java Migration
 * @see StringUtils
 * @see DateValidator
 */
public final class ValidationUtils {

    // Regex patterns for format validation (compiled once for performance)
    
    /**
     * RFC 5322 compliant email validation pattern.
     * Validates: local-part@domain format with standard characters.
     */
    private static final Pattern EMAIL_PATTERN = Pattern.compile(
        "^[a-zA-Z0-9._%+-]+@[a-zA-Z0-9.-]+\\.[a-zA-Z]{2,}$"
    );
    
    /**
     * E.164 phone number format validation pattern.
     * Validates: digits only, optionally starting with +, 10-15 digits total.
     */
    private static final Pattern PHONE_PATTERN = Pattern.compile(
        "^\\+?[0-9]{10,15}$"
    );
    
    /**
     * US ZIP code validation pattern (5 digits or 5+4 format).
     * Validates: 12345 or 12345-6789 format.
     */
    private static final Pattern ZIP_CODE_PATTERN = Pattern.compile(
        "^[0-9]{5}(-[0-9]{4})?$"
    );
    
    /**
     * Account number validation pattern (11 digits).
     * Corresponds to COBOL ACCT-ID PIC 9(11).
     */
    private static final Pattern ACCOUNT_NUMBER_PATTERN = Pattern.compile(
        "^[0-9]{11}$"
    );
    
    /**
     * CVV validation pattern (3 or 4 digits).
     * Card verification value for security checks.
     */
    private static final Pattern CVV_PATTERN = Pattern.compile(
        "^[0-9]{3,4}$"
    );
    
    /**
     * Credit card expiry pattern (MM/YY or MM/YYYY format).
     * Validates format only, not whether the date is valid or future.
     */
    private static final Pattern CARD_EXPIRY_PATTERN = Pattern.compile(
        "^(0[1-9]|1[0-2])/([0-9]{2}|[0-9]{4})$"
    );
    
    /**
     * Minimum valid credit card number length (13 digits for some Visa cards).
     */
    private static final int MIN_CARD_NUMBER_LENGTH = 13;
    
    /**
     * Maximum valid credit card number length (19 digits).
     */
    private static final int MAX_CARD_NUMBER_LENGTH = 19;
    
    /**
     * Private constructor to prevent instantiation of utility class.
     */
    private ValidationUtils() {
        throw new UnsupportedOperationException("ValidationUtils is a utility class and cannot be instantiated");
    }
    
    // ========================================================================
    // Basic Field Validation Methods - COBOL LOW-VALUES and SPACES Checks
    // ========================================================================
    
    /**
     * Validates that a string is not blank (not null, not empty, and not all spaces).
     * Combines COBOL LOW-VALUES check and SPACES check.
     * 
     * <p>COBOL Equivalence:
     * <pre>
     * IF FIELD-NAME = LOW-VALUES OR FIELD-NAME = SPACES
     *    MOVE 'Field cannot be blank' TO ERROR-MESSAGE
     * </pre>
     * 
     * @param value the string to validate
     * @return ValidationResult with success=true if not blank, success=false with error message if blank
     */
    public static ValidationResult validateNotBlank(String value) {
        if (StringUtils.isEmpty(value)) {
            return ValidationResult.failure("Field cannot be empty");
        }
        
        if (StringUtils.isBlank(value)) {
            return ValidationResult.failure("Field cannot contain only spaces");
        }
        
        return ValidationResult.success();
    }
    
    /**
     * Checks if a string contains only numeric characters (0-9).
     * Replicates COBOL PIC 9 field validation.
     * 
     * <p>COBOL Equivalence:
     * <pre>
     * 01 NUMERIC-FIELD PIC 9(n).
     * IF NUMERIC-FIELD IS NOT NUMERIC
     *    (validation fails)
     * </pre>
     * 
     * @param value the string to check
     * @return true if the string contains only digits, false otherwise
     */
    public static boolean isNumeric(String value) {
        return StringUtils.isNumeric(value);
    }
    
    /**
     * Checks if a string contains only alphabetic characters (A-Z, a-z).
     * Replicates COBOL PIC A field validation.
     * 
     * <p>COBOL Equivalence:
     * <pre>
     * 01 ALPHA-FIELD PIC A(n).
     * IF ALPHA-FIELD IS NOT ALPHABETIC
     *    (validation fails)
     * </pre>
     * 
     * @param value the string to check
     * @return true if the string contains only letters, false otherwise
     */
    public static boolean isAlphabetic(String value) {
        return StringUtils.isAlphabetic(value);
    }
    
    /**
     * Checks if a string contains only alphanumeric characters (A-Z, a-z, 0-9).
     * Replicates COBOL PIC X alphanumeric validation.
     * 
     * <p>COBOL Equivalence:
     * <pre>
     * 01 ALPHANUM-FIELD PIC X(n).
     * IF ALPHANUM-FIELD IS NOT ALPHANUMERIC
     *    (validation fails)
     * </pre>
     * 
     * @param value the string to check
     * @return true if the string contains only letters and digits, false otherwise
     */
    public static boolean isAlphanumeric(String value) {
        return StringUtils.isAlphanumeric(value);
    }
    
    /**
     * Checks if a string is null or has zero length.
     * Replicates COBOL LOW-VALUES check.
     * 
     * @param value the string to check
     * @return true if the string is null or empty, false otherwise
     */
    public static boolean isEmpty(String value) {
        return StringUtils.isEmpty(value);
    }
    
    /**
     * Checks if a string is not null and has at least one character.
     * Opposite of isEmpty().
     * 
     * @param value the string to check
     * @return true if the string is not null and not empty, false otherwise
     */
    public static boolean isNotEmpty(String value) {
        return StringUtils.isNotEmpty(value);
    }
    
    // ========================================================================
    // Length Validation Methods - COBOL PIC Clause Length Constraints
    // ========================================================================
    
    /**
     * Validates that a string has exactly the specified length.
     * Enforces COBOL PIC clause exact length constraints.
     * 
     * <p>COBOL Equivalence:
     * <pre>
     * 01 FIXED-FIELD PIC X(10).
     * IF LENGTH OF FIXED-FIELD NOT = 10
     *    (validation fails)
     * </pre>
     * 
     * @param value the string to validate
     * @param expectedLength the required exact length
     * @return ValidationResult with success=true if length matches, success=false with error message if not
     */
    public static ValidationResult validateLength(String value, int expectedLength) {
        if (value == null) {
            return ValidationResult.failure("Field cannot be null");
        }
        
        if (value.length() != expectedLength) {
            return ValidationResult.failure(
                String.format("Field must be exactly %d characters (current length: %d)", 
                    expectedLength, value.length())
            );
        }
        
        return ValidationResult.success();
    }
    
    /**
     * Validates that a string does not exceed the specified maximum length.
     * Enforces COBOL PIC clause maximum length constraints.
     * 
     * <p>COBOL Equivalence:
     * <pre>
     * 01 VAR-FIELD PIC X(50).
     * IF LENGTH OF VAR-FIELD > 50
     *    (validation fails)
     * </pre>
     * 
     * @param value the string to validate
     * @param maxLength the maximum allowed length
     * @return ValidationResult with success=true if within limit, success=false with error message if exceeded
     */
    public static ValidationResult validateMaxLength(String value, int maxLength) {
        if (value == null) {
            return ValidationResult.success(); // Null values are acceptable for max length checks
        }
        
        if (value.length() > maxLength) {
            return ValidationResult.failure(
                String.format("Field cannot exceed %d characters (current length: %d)", 
                    maxLength, value.length())
            );
        }
        
        return ValidationResult.success();
    }
    
    /**
     * Validates that a string meets the specified minimum length.
     * 
     * @param value the string to validate
     * @param minLength the minimum required length
     * @return ValidationResult with success=true if meets minimum, success=false with error message if too short
     */
    public static ValidationResult validateMinLength(String value, int minLength) {
        if (value == null) {
            return ValidationResult.failure("Field cannot be null");
        }
        
        if (value.length() < minLength) {
            return ValidationResult.failure(
                String.format("Field must be at least %d characters (current length: %d)", 
                    minLength, value.length())
            );
        }
        
        return ValidationResult.success();
    }
    
    // ========================================================================
    // Range Validation Methods - Numeric and Date Boundaries
    // ========================================================================
    
    /**
     * Validates that a BigDecimal value falls within the specified range (inclusive).
     * Ensures COBOL COMP-3 packed decimal precision equivalence per Section 0.9 requirements.
     * 
     * <p>COBOL Equivalence:
     * <pre>
     * 01 AMOUNT-FIELD PIC S9(7)V99 COMP-3.
     * IF AMOUNT-FIELD < MIN-AMOUNT OR AMOUNT-FIELD > MAX-AMOUNT
     *    (validation fails)
     * </pre>
     * 
     * @param value the BigDecimal value to validate
     * @param minValue the minimum allowed value (inclusive)
     * @param maxValue the maximum allowed value (inclusive)
     * @return ValidationResult with success=true if within range, success=false with error message if out of range
     */
    public static ValidationResult validateRange(BigDecimal value, BigDecimal minValue, BigDecimal maxValue) {
        if (value == null) {
            return ValidationResult.failure("Value cannot be null");
        }
        
        if (minValue != null && value.compareTo(minValue) < 0) {
            return ValidationResult.failure(
                String.format("Value must be at least %s (current value: %s)", 
                    minValue.toPlainString(), value.toPlainString())
            );
        }
        
        if (maxValue != null && value.compareTo(maxValue) > 0) {
            return ValidationResult.failure(
                String.format("Value cannot exceed %s (current value: %s)", 
                    maxValue.toPlainString(), value.toPlainString())
            );
        }
        
        return ValidationResult.success();
    }
    
    /**
     * Validates that a numeric value falls within the specified range (inclusive).
     * Convenience method for Integer, Long, Double validation.
     * 
     * @param value the numeric value to validate
     * @param minValue the minimum allowed value (inclusive)
     * @param maxValue the maximum allowed value (inclusive)
     * @return ValidationResult with success=true if within range, success=false with error message if out of range
     */
    public static ValidationResult validateRange(Number value, Number minValue, Number maxValue) {
        if (value == null) {
            return ValidationResult.failure("Value cannot be null");
        }
        
        BigDecimal valueBD = BigDecimal.valueOf(value.doubleValue());
        BigDecimal minBD = minValue != null ? BigDecimal.valueOf(minValue.doubleValue()) : null;
        BigDecimal maxBD = maxValue != null ? BigDecimal.valueOf(maxValue.doubleValue()) : null;
        
        return validateRange(valueBD, minBD, maxBD);
    }
    
    /**
     * Validates that a date falls within the specified range (inclusive).
     * Uses DateValidator for comprehensive date validation per COBOL CSUTLDPY logic.
     * 
     * @param date the date to validate
     * @param minDate the minimum allowed date (inclusive)
     * @param maxDate the maximum allowed date (inclusive)
     * @return ValidationResult with success=true if within range, success=false with error message if out of range
     */
    public static ValidationResult validateDateRange(LocalDate date, LocalDate minDate, LocalDate maxDate) {
        if (date == null) {
            return ValidationResult.failure("Date cannot be null");
        }
        
        try {
            boolean isValid = DateValidator.isValidDateRange(date, minDate, maxDate);
            if (!isValid) {
                return ValidationResult.failure(
                    String.format("Date must be between %s and %s", minDate, maxDate)
                );
            }
            return ValidationResult.success();
        } catch (IllegalArgumentException e) {
            return ValidationResult.failure(e.getMessage());
        }
    }
    
    // ========================================================================
    // Format Validation Methods - Email, Phone, ZIP, Account Numbers
    // ========================================================================
    
    /**
     * Validates an email address using RFC 5322 compliant pattern.
     * 
     * @param email the email address to validate
     * @return ValidationResult with success=true if valid email format, success=false with error message if invalid
     */
    public static ValidationResult validateEmail(String email) {
        if (StringUtils.isEmpty(email)) {
            return ValidationResult.failure("Email address cannot be empty");
        }
        
        if (!EMAIL_PATTERN.matcher(email).matches()) {
            return ValidationResult.failure("Invalid email address format");
        }
        
        return ValidationResult.success();
    }
    
    /**
     * Validates a phone number using E.164 format (10-15 digits, optionally starting with +).
     * 
     * @param phone the phone number to validate
     * @return ValidationResult with success=true if valid phone format, success=false with error message if invalid
     */
    public static ValidationResult validatePhone(String phone) {
        if (StringUtils.isEmpty(phone)) {
            return ValidationResult.failure("Phone number cannot be empty");
        }
        
        // Remove common formatting characters for validation
        String cleanedPhone = phone.replaceAll("[\\s\\-\\(\\)]", "");
        
        if (!PHONE_PATTERN.matcher(cleanedPhone).matches()) {
            return ValidationResult.failure("Invalid phone number format (must be 10-15 digits)");
        }
        
        return ValidationResult.success();
    }
    
    /**
     * Validates a US ZIP code (5 digits or 5+4 format).
     * 
     * @param zipCode the ZIP code to validate
     * @return ValidationResult with success=true if valid ZIP format, success=false with error message if invalid
     */
    public static ValidationResult validateZipCode(String zipCode) {
        if (StringUtils.isEmpty(zipCode)) {
            return ValidationResult.failure("ZIP code cannot be empty");
        }
        
        if (!ZIP_CODE_PATTERN.matcher(zipCode).matches()) {
            return ValidationResult.failure("Invalid ZIP code format (must be 12345 or 12345-6789)");
        }
        
        return ValidationResult.success();
    }
    
    /**
     * Validates an account number (11 digits).
     * Corresponds to COBOL ACCT-ID PIC 9(11) field validation.
     * 
     * @param accountNumber the account number to validate
     * @return ValidationResult with success=true if valid account number, success=false with error message if invalid
     */
    public static ValidationResult validateAccountNumber(String accountNumber) {
        if (StringUtils.isEmpty(accountNumber)) {
            return ValidationResult.failure("Account number cannot be empty");
        }
        
        if (!ACCOUNT_NUMBER_PATTERN.matcher(accountNumber).matches()) {
            return ValidationResult.failure("Invalid account number format (must be 11 digits)");
        }
        
        return ValidationResult.success();
    }
    
    /**
     * Validates a credit card number with Luhn algorithm check.
     * 
     * <p>Performs the following validations:
     * <ul>
     *   <li>Length check (13-19 digits)</li>
     *   <li>Numeric validation</li>
     *   <li>Luhn algorithm checksum validation</li>
     * </ul>
     * 
     * @param cardNumber the credit card number to validate
     * @return ValidationResult with success=true if valid card number, success=false with error message if invalid
     */
    public static ValidationResult validateCardNumber(String cardNumber) {
        if (StringUtils.isEmpty(cardNumber)) {
            return ValidationResult.failure("Card number cannot be empty");
        }
        
        // Remove spaces and dashes for validation
        String cleanedCard = cardNumber.replaceAll("[\\s\\-]", "");
        
        // Check if numeric
        if (!StringUtils.isNumeric(cleanedCard)) {
            return ValidationResult.failure("Card number must contain only digits");
        }
        
        // Check length
        if (cleanedCard.length() < MIN_CARD_NUMBER_LENGTH || cleanedCard.length() > MAX_CARD_NUMBER_LENGTH) {
            return ValidationResult.failure(
                String.format("Card number must be between %d and %d digits", 
                    MIN_CARD_NUMBER_LENGTH, MAX_CARD_NUMBER_LENGTH)
            );
        }
        
        // Perform Luhn algorithm check
        if (!luhnCheck(cleanedCard)) {
            return ValidationResult.failure("Invalid card number (failed checksum validation)");
        }
        
        return ValidationResult.success();
    }
    
    /**
     * Validates a CVV (Card Verification Value) code (3 or 4 digits).
     * 
     * @param cvv the CVV code to validate
     * @return ValidationResult with success=true if valid CVV, success=false with error message if invalid
     */
    public static ValidationResult validateCVV(String cvv) {
        if (StringUtils.isEmpty(cvv)) {
            return ValidationResult.failure("CVV cannot be empty");
        }
        
        if (!CVV_PATTERN.matcher(cvv).matches()) {
            return ValidationResult.failure("Invalid CVV format (must be 3 or 4 digits)");
        }
        
        return ValidationResult.success();
    }
    
    // ========================================================================
    // Credit Card Expiry Validation
    // ========================================================================
    
    /**
     * Validates a credit card expiry date given month and year.
     * Checks if the expiry month (01-12) and year represent a future date.
     * 
     * <p>COBOL card validation pattern equivalence for card expiry checking.
     * 
     * @param expiryMonth the expiry month (1-12)
     * @param expiryYear the expiry year (2-digit or 4-digit format)
     * @return true if the card is not expired (expiry date is in the future), false otherwise
     */
    public static boolean isValidCreditCardExpiry(int expiryMonth, int expiryYear) {
        // Validate month range
        if (!DateValidator.isValidMonth(expiryMonth)) {
            return false;
        }
        
        // Convert 2-digit year to 4-digit if necessary
        int fullYear = expiryYear;
        if (expiryYear < 100) {
            // Assume 2-digit year: 00-99 maps to 2000-2099
            fullYear = 2000 + expiryYear;
        }
        
        // Validate year is in valid range
        if (!DateValidator.isValidYear(fullYear)) {
            return false;
        }
        
        // Create YearMonth for comparison
        YearMonth expiryYearMonth = YearMonth.of(fullYear, expiryMonth);
        YearMonth currentYearMonth = YearMonth.now();
        
        // Card is valid if expiry date is after current month
        return expiryYearMonth.isAfter(currentYearMonth);
    }
    
    /**
     * Validates a credit card expiry date given in MM/YY or MM/YYYY format.
     * 
     * @param expiryDate the expiry date string in MM/YY or MM/YYYY format
     * @return true if the format is valid and the card is not expired, false otherwise
     */
    public static boolean isValidCreditCardExpiry(String expiryDate) {
        if (StringUtils.isEmpty(expiryDate)) {
            return false;
        }
        
        // Validate format
        if (!CARD_EXPIRY_PATTERN.matcher(expiryDate).matches()) {
            return false;
        }
        
        // Parse month and year
        String[] parts = expiryDate.split("/");
        if (parts.length != 2) {
            return false;
        }
        
        try {
            int month = Integer.parseInt(parts[0]);
            int year = Integer.parseInt(parts[1]);
            
            return isValidCreditCardExpiry(month, year);
        } catch (NumberFormatException e) {
            return false;
        }
    }
    
    // ========================================================================
    // Pattern Validation - Custom Regex Validation
    // ========================================================================
    
    /**
     * Validates a string against a custom regex pattern.
     * Enables flexible validation for application-specific formats.
     * 
     * @param value the string to validate
     * @param regex the regular expression pattern to match
     * @return ValidationResult with success=true if matches pattern, success=false with error message if not
     */
    public static ValidationResult validatePattern(String value, String regex) {
        if (StringUtils.isEmpty(value)) {
            return ValidationResult.failure("Value cannot be empty");
        }
        
        if (StringUtils.isEmpty(regex)) {
            return ValidationResult.failure("Pattern cannot be empty");
        }
        
        try {
            if (!Pattern.matches(regex, value)) {
                return ValidationResult.failure("Value does not match required pattern");
            }
            return ValidationResult.success();
        } catch (Exception e) {
            return ValidationResult.failure("Invalid regex pattern: " + e.getMessage());
        }
    }
    
    // ========================================================================
    // Luhn Algorithm Implementation - Credit Card Checksum Validation
    // ========================================================================
    
    /**
     * Performs Luhn algorithm checksum validation on a numeric string.
     * Used for credit card number validation.
     * 
     * <p>Luhn Algorithm Steps:
     * <ol>
     *   <li>Starting from the rightmost digit (check digit), double every second digit</li>
     *   <li>If doubling results in a number > 9, subtract 9 from it</li>
     *   <li>Sum all the digits</li>
     *   <li>If sum modulo 10 equals 0, the number is valid</li>
     * </ol>
     * 
     * @param cardNumber the card number string (digits only) to validate
     * @return true if the checksum is valid, false otherwise
     */
    public static boolean luhnCheck(String cardNumber) {
        if (StringUtils.isEmpty(cardNumber) || !StringUtils.isNumeric(cardNumber)) {
            return false;
        }
        
        int sum = 0;
        boolean alternate = false;
        
        // Process digits from right to left
        for (int i = cardNumber.length() - 1; i >= 0; i--) {
            int digit = Character.getNumericValue(cardNumber.charAt(i));
            
            if (alternate) {
                digit *= 2;
                if (digit > 9) {
                    digit -= 9;
                }
            }
            
            sum += digit;
            alternate = !alternate;
        }
        
        // Valid if sum is divisible by 10
        return (sum % 10 == 0);
    }
    
    // ========================================================================
    // ValidationResult Inner Class - Validation Result Wrapper
    // ========================================================================
    
    /**
     * ValidationResult - Immutable class representing the result of a validation operation.
     * 
     * <p>Contains a boolean success flag and optional error message string,
     * enabling integration with Bean Validation framework and providing
     * COBOL-style validation feedback.
     * 
     * <p>Usage Pattern:
     * <pre>
     * ValidationResult result = ValidationUtils.validateEmail(email);
     * if (!result.isValid()) {
     *     // Handle validation error
     *     String errorMessage = result.getErrorMessage();
     * }
     * </pre>
     */
    public static class ValidationResult {
        private final boolean valid;
        private final String errorMessage;
        
        /**
         * Private constructor. Use factory methods success() or failure() instead.
         * 
         * @param valid true if validation passed, false otherwise
         * @param errorMessage the error message (null if validation passed)
         */
        private ValidationResult(boolean valid, String errorMessage) {
            this.valid = valid;
            this.errorMessage = errorMessage;
        }
        
        /**
         * Checks if the validation passed.
         * 
         * @return true if validation was successful, false otherwise
         */
        public boolean isValid() {
            return valid;
        }
        
        /**
         * Gets the error message if validation failed.
         * 
         * @return the error message, or null if validation passed
         */
        public String getErrorMessage() {
            return errorMessage;
        }
        
        /**
         * Factory method to create a successful validation result.
         * 
         * @return a ValidationResult with valid=true and no error message
         */
        public static ValidationResult success() {
            return new ValidationResult(true, null);
        }
        
        /**
         * Factory method to create a failed validation result with error message.
         * 
         * @param errorMessage the error message describing the validation failure
         * @return a ValidationResult with valid=false and the provided error message
         */
        public static ValidationResult failure(String errorMessage) {
            return new ValidationResult(false, errorMessage);
        }
        
        /**
         * Factory method to create a validation result with explicit valid flag and message.
         * 
         * @param valid true if validation passed, false otherwise
         * @param errorMessage the error message (ignored if valid is true)
         * @return a ValidationResult with the specified valid flag and error message
         */
        public static ValidationResult of(boolean valid, String errorMessage) {
            if (valid) {
                return success();
            }
            return failure(errorMessage);
        }
        
        /**
         * Returns a string representation of the validation result.
         * 
         * @return string representation including valid flag and error message
         */
        @Override
        public String toString() {
            if (valid) {
                return "ValidationResult{valid=true}";
            }
            return "ValidationResult{valid=false, errorMessage='" + errorMessage + "'}";
        }
    }
}

