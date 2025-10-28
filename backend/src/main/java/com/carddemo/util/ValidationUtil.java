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

import com.carddemo.exception.ValidationException;

import java.math.BigDecimal;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Utility class centralizing field validation rules from all 15 CICS online programs.
 * 
 * <p>This class provides static methods for input validation that replicate COBOL validation
 * logic from mainframe programs including numeric checks (PIC 9 field validation), alphabetic
 * pattern validation, length constraints, range validations, mandatory field checks from BMS
 * map attributes (NUM, ALPHA, PROT, ASKIP), and COBOL 88-level condition validations.</p>
 * 
 * <h2>COBOL Source Mappings</h2>
 * <p>Validation logic converted from the following COBOL programs:</p>
 * <ul>
 *   <li><b>COSGN00C</b> - User signon validation (lines 118-127): Mandatory field checks
 *       for User ID and Password using SPACES OR LOW-VALUES tests</li>
 *   <li><b>COACTUPC</b> - Account update validation (lines 52-200): Generic input edits
 *       including numeric (FLG-SIGNED-NUMBER-ISVALID), alpha (FLG-ALPHA-ISVALID),
 *       alphanumeric (FLG-ALPHNANUM-ISVALID), mandatory (FLG-MANDATORY-ISVALID),
 *       Yes/No (FLG-YES-NO-ISVALID), US phone number (WS-EDIT-US-PHONE-NUM lines 82-115),
 *       and SSN validation (WS-EDIT-US-SSN lines 117-146) with invalid part1 values
 *       (0, 666, 900-999)</li>
 *   <li><b>COTRN02C</b> - Transaction entry validation (lines 196-200): Numeric validation
 *       for Account ID using IS NUMERIC test, Y/N confirmation validation (lines 169-188)</li>
 *   <li><b>COUSR01C</b> - User add validation: User ID and password field validations</li>
 *   <li><b>COCRDUPC</b> - Card update validation: Card number format and expiration date
 *       validations</li>
 * </ul>
 * 
 * <h2>BMS Map Attribute Mappings</h2>
 * <ul>
 *   <li><b>NUM</b> attribute → isNumeric() validation</li>
 *   <li><b>ALPHA</b> attribute → isAlphabetic() validation</li>
 *   <li><b>ASKIP/PROT</b> attributes → Enforced in frontend as read-only fields</li>
 *   <li><b>BRT</b> attribute → Field highlighting for validation errors</li>
 * </ul>
 * 
 * <h2>COBOL 88-Level Condition Validations</h2>
 * <p>The validate88LevelCondition() method replicates COBOL 88-level value checking:</p>
 * <pre>
 * COBOL:
 *   05 WS-EDIT-ACCT-STATUS PIC X(1).
 *      88 FLG-ACCT-STATUS-ISVALID VALUES 'Y', 'N'.
 * 
 * Java:
 *   validate88LevelCondition(accountStatus, Arrays.asList("Y", "N"))
 * </pre>
 * 
 * <h2>Thread Safety</h2>
 * <p>All methods are static and stateless, making this class thread-safe for concurrent
 * use across multiple threads in the Spring Boot application. Compiled regex patterns
 * are stored as constants for optimal performance.</p>
 * 
 * <h2>Usage Examples</h2>
 * <pre>
 * // Mandatory field validation (throws ValidationException if empty)
 * ValidationUtil.validateMandatoryField(userId, "userId");
 * 
 * // Numeric validation
 * if (!ValidationUtil.isNumeric(accountId)) {
 *     throw new ValidationException("VAL005", "Account ID must be numeric", "accountId");
 * }
 * 
 * // Length validation
 * ValidationUtil.validateLength(cardNumber, 16, 16);
 * 
 * // Range validation with BigDecimal
 * ValidationUtil.validateRange(creditLimit, BigDecimal.ZERO, new BigDecimal("999999.99"));
 * 
 * // SSN validation with COBOL business rules
 * ValidationUtil.validateSsn("123456789");
 * 
 * // 88-level condition check
 * ValidationUtil.validate88LevelCondition(statusFlag, Arrays.asList("Y", "N"));
 * </pre>
 * 
 * @author CardDemo Modernization Team
 * @version 1.0
 * @since 2025-10-25
 * @see DateUtil
 * @see MessageUtil
 * @see ValidationException
 */
public class ValidationUtil {

    // ========================================================================
    // Regular Expression Patterns for Format Validation
    // ========================================================================

    /**
     * Pattern for US phone number validation: (XXX)XXX-XXXX or XXX-XXX-XXXX.
     * Matches COBOL WS-EDIT-US-PHONE-NUM structure from COACTUPC.cbl lines 82-100.
     */
    private static final Pattern US_PHONE_PATTERN = Pattern.compile(
        "^\\(?\\d{3}\\)?[\\s-]?\\d{3}[\\s-]?\\d{4}$"
    );

    /**
     * Pattern for email address validation.
     * RFC 5322 simplified pattern for standard email format validation.
     */
    private static final Pattern EMAIL_PATTERN = Pattern.compile(
        "^[a-zA-Z0-9._%+-]+@[a-zA-Z0-9.-]+\\.[a-zA-Z]{2,}$"
    );

    /**
     * Pattern for SSN validation: 9 consecutive digits.
     * Matches COBOL WS-EDIT-US-SSN-N PIC 9(09) from COACTUPC.cbl line 131.
     */
    private static final Pattern SSN_PATTERN = Pattern.compile("^\\d{9}$");

    /**
     * Pattern for card number validation: 16 consecutive digits.
     * Matches COBOL PIC X(16) card number field format.
     */
    private static final Pattern CARD_NUMBER_PATTERN = Pattern.compile("^\\d{16}$");

    /**
     * Pattern for account ID validation: 11 consecutive digits.
     * Matches COBOL PIC 9(11) account ID field format.
     */
    private static final Pattern ACCOUNT_ID_PATTERN = Pattern.compile("^\\d{11}$");

    /**
     * Pattern for alphabetic characters only (A-Z, a-z, spaces).
     * Matches COBOL IF ALPHABETIC test including spaces.
     */
    private static final Pattern ALPHA_PATTERN = Pattern.compile("^[a-zA-Z\\s]*$");

    /**
     * Pattern for alphanumeric characters (A-Z, a-z, 0-9, spaces).
     * Matches COBOL alphanumeric field validation.
     */
    private static final Pattern ALPHANUM_PATTERN = Pattern.compile("^[a-zA-Z0-9\\s]*$");

    /**
     * Pattern for numeric characters only (0-9, optional negative sign and decimal).
     * Matches COBOL IF NUMERIC test for signed decimal numbers.
     */
    private static final Pattern NUMERIC_PATTERN = Pattern.compile("^-?\\d+(\\.\\d+)?$");

    // ========================================================================
    // Constructor
    // ========================================================================

    /**
     * Private constructor to prevent instantiation of utility class.
     * All methods are static and the class is stateless.
     */
    private ValidationUtil() {
        throw new UnsupportedOperationException("ValidationUtil is a utility class and cannot be instantiated");
    }

    // ========================================================================
    // Empty/Blank Field Validation
    // ========================================================================

    /**
     * Checks if a string value is empty (null, empty string, or contains only LOW-VALUES).
     * 
     * <p>Replicates COBOL check: {@code IF field-name = SPACES OR LOW-VALUES}</p>
     * <p>COBOL Source: COSGN00C.cbl lines 118, 123 - User ID and Password validation</p>
     * 
     * @param value The string value to check
     * @return true if value is null, empty, or contains only null characters (LOW-VALUES)
     */
    public static boolean isEmpty(String value) {
        if (value == null || value.isEmpty()) {
            return true;
        }
        // Check for LOW-VALUES (null characters)
        for (char c : value.toCharArray()) {
            if (c != '\0') {
                return false;
            }
        }
        return true;
    }

    /**
     * Checks if a string value is blank (null, empty, or contains only whitespace).
     * 
     * <p>Replicates COBOL check: {@code IF field-name = SPACES}</p>
     * <p>More lenient than isEmpty() - considers whitespace-only strings as blank.</p>
     * <p>COBOL Source: Multiple programs checking for SPACES condition</p>
     * 
     * @param value The string value to check
     * @return true if value is null, empty, or contains only whitespace characters
     */
    public static boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }

    // ========================================================================
    // Character Type Validation
    // ========================================================================

    /**
     * Validates that a string contains only numeric characters (digits, optional sign and decimal).
     * 
     * <p>Replicates COBOL check: {@code IF field-name IS NUMERIC}</p>
     * <p>COBOL Source: COTRN02C.cbl lines 197-199 - Account ID numeric validation,
     * COACTUPC.cbl lines 56-59 - FLG-SIGNED-NUMBER-ISVALID</p>
     * 
     * @param value The string value to validate
     * @return true if value contains only numeric characters (0-9), optional negative sign, and decimal point
     */
    public static boolean isNumeric(String value) {
        if (isBlank(value)) {
            return false;
        }
        return NUMERIC_PATTERN.matcher(value.trim()).matches();
    }

    /**
     * Validates that a string contains only alphabetic characters (A-Z, a-z, spaces).
     * 
     * <p>Replicates COBOL check: {@code IF field-name IS ALPHABETIC}</p>
     * <p>COBOL Source: COACTUPC.cbl lines 64-67 - FLG-ALPHA-ISVALID</p>
     * 
     * @param value The string value to validate
     * @return true if value contains only alphabetic characters and spaces
     */
    public static boolean isAlphabetic(String value) {
        if (isBlank(value)) {
            return false;
        }
        return ALPHA_PATTERN.matcher(value).matches();
    }

    /**
     * Validates that a string contains only alphanumeric characters (A-Z, a-z, 0-9, spaces).
     * 
     * <p>Replicates COBOL alphanumeric field validation.</p>
     * <p>COBOL Source: COACTUPC.cbl lines 68-71 - FLG-ALPHNANUM-ISVALID</p>
     * 
     * @param value The string value to validate
     * @return true if value contains only alphanumeric characters and spaces
     */
    public static boolean isAlphanumeric(String value) {
        if (isBlank(value)) {
            return false;
        }
        return ALPHANUM_PATTERN.matcher(value).matches();
    }

    // ========================================================================
    // Length Validation
    // ========================================================================

    /**
     * Validates that a string's length falls within specified min/max constraints.
     * 
     * <p>Replicates COBOL PIC field length constraints (e.g., PIC X(10) for 10-character field).</p>
     * <p>COBOL Source: Multiple programs with PIC length specifications</p>
     * 
     * @param value     The string value to validate
     * @param minLength Minimum allowed length (inclusive)
     * @param maxLength Maximum allowed length (inclusive)
     * @return true if value length is within the specified range
     * @throws ValidationException if value is null or length is outside the specified range
     */
    public static boolean validateLength(String value, int minLength, int maxLength) {
        if (value == null) {
            throw new ValidationException(
                "VAL003",
                MessageUtil.getMessage("validation.length.null"),
                "value"
            );
        }
        
        int length = value.length();
        if (length < minLength || length > maxLength) {
            throw new ValidationException(
                "VAL003",
                MessageUtil.getMessage("validation.length.invalid", minLength, maxLength, length),
                "value"
            );
        }
        return true;
    }

    // ========================================================================
    // Range Validation
    // ========================================================================

    /**
     * Validates that an integer value falls within specified min/max range.
     * 
     * <p>Replicates COBOL numeric range validation for integer fields.</p>
     * <p>COBOL Source: Multiple programs with range checks on numeric fields</p>
     * 
     * @param value The integer value to validate
     * @param min   Minimum allowed value (inclusive)
     * @param max   Maximum allowed value (inclusive)
     * @return true if value is within the specified range
     * @throws ValidationException if value is outside the specified range
     */
    public static boolean validateRange(int value, int min, int max) {
        if (value < min || value > max) {
            throw new ValidationException(
                "VAL005",
                MessageUtil.getMessage("validation.range.invalid", min, max, value),
                "value"
            );
        }
        return true;
    }

    /**
     * Validates that a BigDecimal value falls within specified min/max range.
     * 
     * <p>Replicates COBOL COMP-3 packed decimal range validation for financial fields.</p>
     * <p>Uses BigDecimal.compareTo() to ensure proper precision handling for currency amounts.</p>
     * <p>COBOL Source: COACTUPC.cbl lines 196-199 - Credit limit validation,
     * COTRN02C.cbl - Transaction amount range validation</p>
     * 
     * @param value The BigDecimal value to validate
     * @param min   Minimum allowed value (inclusive)
     * @param max   Maximum allowed value (inclusive)
     * @return true if value is within the specified range
     * @throws ValidationException if value is null or outside the specified range
     */
    public static boolean validateRange(BigDecimal value, BigDecimal min, BigDecimal max) {
        if (value == null) {
            throw new ValidationException(
                "VAL005",
                MessageUtil.getMessage("validation.range.null"),
                "value"
            );
        }
        
        if (value.compareTo(min) < 0 || value.compareTo(max) > 0) {
            throw new ValidationException(
                "VAL005",
                MessageUtil.getMessage("validation.range.invalid", min, max, value),
                "value"
            );
        }
        return true;
    }

    // ========================================================================
    // Format-Specific Validation
    // ========================================================================

    /**
     * Validates US Social Security Number format and business rules.
     * 
     * <p>Validates 9-digit SSN format with COBOL business rules:</p>
     * <ul>
     *   <li>Must be exactly 9 digits</li>
     *   <li>First 3 digits (area number) cannot be 000, 666, or 900-999</li>
     *   <li>Middle 2 digits (group number) cannot be 00</li>
     *   <li>Last 4 digits (serial number) cannot be 0000</li>
     * </ul>
     * 
     * <p>COBOL Source: COACTUPC.cbl lines 117-146 - WS-EDIT-US-SSN validation with
     * INVALID-SSN-PART1 88-level condition (lines 121-123) defining invalid values
     * (0, 666, 900 THRU 999)</p>
     * 
     * @param ssn The SSN string to validate (9 digits, no formatting)
     * @return true if SSN is valid per COBOL business rules
     * @throws ValidationException if SSN format or business rules are violated
     */
    public static boolean validateSsn(String ssn) {
        if (isBlank(ssn)) {
            throw new ValidationException(
                "VAL002",
                MessageUtil.getMessage("validation.ssn.required"),
                "ssn"
            );
        }
        
        String cleanSsn = ssn.trim().replaceAll("[^0-9]", "");
        
        if (!SSN_PATTERN.matcher(cleanSsn).matches()) {
            throw new ValidationException(
                "VAL003",
                MessageUtil.getMessage("validation.ssn.format"),
                "ssn"
            );
        }
        
        // Validate COBOL business rules
        int area = Integer.parseInt(cleanSsn.substring(0, 3));
        int group = Integer.parseInt(cleanSsn.substring(3, 5));
        int serial = Integer.parseInt(cleanSsn.substring(5, 9));
        
        // COBOL: INVALID-SSN-PART1 VALUES 0, 666, 900 THRU 999
        if (area == 0 || area == 666 || (area >= 900 && area <= 999)) {
            throw new ValidationException(
                "VAL003",
                MessageUtil.getMessage("validation.ssn.area.invalid"),
                "ssn"
            );
        }
        
        if (group == 0) {
            throw new ValidationException(
                "VAL003",
                MessageUtil.getMessage("validation.ssn.group.invalid"),
                "ssn"
            );
        }
        
        if (serial == 0) {
            throw new ValidationException(
                "VAL003",
                MessageUtil.getMessage("validation.ssn.serial.invalid"),
                "ssn"
            );
        }
        
        return true;
    }

    /**
     * Validates US phone number format.
     * 
     * <p>Accepts formats:</p>
     * <ul>
     *   <li>(XXX)XXX-XXXX</li>
     *   <li>XXX-XXX-XXXX</li>
     *   <li>XXX XXX XXXX</li>
     *   <li>XXXXXXXXXX (10 digits)</li>
     * </ul>
     * 
     * <p>COBOL Source: COACTUPC.cbl lines 82-115 - WS-EDIT-US-PHONE-NUM structure
     * with validation flags for each phone number segment (area code, prefix, line number)</p>
     * 
     * @param phone The phone number string to validate
     * @return true if phone number format is valid
     * @throws ValidationException if phone number format is invalid
     */
    public static boolean validatePhoneNumber(String phone) {
        if (isBlank(phone)) {
            throw new ValidationException(
                "VAL002",
                MessageUtil.getMessage("validation.phone.required"),
                "phone"
            );
        }
        
        String cleanPhone = phone.trim();
        
        if (!US_PHONE_PATTERN.matcher(cleanPhone).matches()) {
            throw new ValidationException(
                "VAL003",
                MessageUtil.getMessage("validation.phone.format"),
                "phone"
            );
        }
        
        return true;
    }

    /**
     * Validates email address format.
     * 
     * <p>Uses RFC 5322 simplified pattern for standard email validation.</p>
     * <p>Format: local-part@domain with proper domain extension</p>
     * 
     * @param email The email address string to validate
     * @return true if email format is valid
     * @throws ValidationException if email format is invalid
     */
    public static boolean validateEmail(String email) {
        if (isBlank(email)) {
            throw new ValidationException(
                "VAL002",
                MessageUtil.getMessage("validation.email.required"),
                "email"
            );
        }
        
        String cleanEmail = email.trim();
        
        if (!EMAIL_PATTERN.matcher(cleanEmail).matches()) {
            throw new ValidationException(
                "VAL003",
                MessageUtil.getMessage("validation.email.format"),
                "email"
            );
        }
        
        return true;
    }

    /**
     * Validates credit card number format (16 digits).
     * 
     * <p>Validates that card number is exactly 16 digits as per COBOL PIC X(16) specification.</p>
     * <p>COBOL Source: COCRDUPC.cbl - Card number field validation</p>
     * 
     * @param cardNumber The card number string to validate
     * @return true if card number is exactly 16 digits
     * @throws ValidationException if card number format is invalid
     */
    public static boolean validateCardNumber(String cardNumber) {
        if (isBlank(cardNumber)) {
            throw new ValidationException(
                "VAL006",
                MessageUtil.getMessage("validation.card.number.required"),
                "cardNumber"
            );
        }
        
        String cleanCard = cardNumber.trim().replaceAll("[^0-9]", "");
        
        if (!CARD_NUMBER_PATTERN.matcher(cleanCard).matches()) {
            throw new ValidationException(
                "VAL006",
                MessageUtil.getMessage("validation.card.number.format"),
                "cardNumber"
            );
        }
        
        return true;
    }

    /**
     * Validates date string format by delegating to DateUtil.
     * 
     * <p>Supports COBOL date formats:</p>
     * <ul>
     *   <li>YYYY-MM-DD (PIC X(10))</li>
     *   <li>YYYYMMDD (PIC 9(08))</li>
     * </ul>
     * 
     * <p>COBOL Source: CSUTLDTC.cbl - Date validation using CEEDAYS API</p>
     * 
     * @param dateStr The date string to validate
     * @return true if date is valid
     * @throws ValidationException if date format is invalid or date is not valid
     */
    public static boolean validateDate(String dateStr) {
        if (isBlank(dateStr)) {
            throw new ValidationException(
                "VAL002",
                MessageUtil.getMessage("validation.date.required"),
                "date"
            );
        }
        
        try {
            // Delegate to DateUtil for comprehensive date validation
            DateUtil.parseCobolDate(dateStr);
            return true;
        } catch (Exception e) {
            throw new ValidationException(
                "VAL003",
                MessageUtil.getMessage("validation.date.format"),
                "date"
            );
        }
    }

    // ========================================================================
    // COBOL 88-Level Condition Validation
    // ========================================================================

    /**
     * Validates that a field value matches one of the allowed values from a list.
     * 
     * <p>Replicates COBOL 88-level condition checking where multiple valid values are defined:</p>
     * <pre>
     * COBOL Example:
     *   05 WS-EDIT-ACCT-STATUS PIC X(1).
     *      88 FLG-ACCT-STATUS-ISVALID VALUES 'Y', 'N'.
     * 
     * Java Equivalent:
     *   validate88LevelCondition(accountStatus, Arrays.asList("Y", "N"))
     * </pre>
     * 
     * <p>COBOL Source: COACTUPC.cbl lines 193-195 - FLG-ACCT-STATUS-ISVALID VALUES 'Y', 'N'
     * and lines 76-80 - FLG-YES-NO-ISVALID VALUES 'Y', 'N'</p>
     * 
     * @param value       The field value to validate
     * @param validValues List of valid values that the field can have
     * @return true if value is in the validValues list
     * @throws ValidationException if value is not in the validValues list
     */
    public static boolean validate88LevelCondition(String value, List<String> validValues) {
        if (value == null) {
            throw new ValidationException(
                "VAL002",
                MessageUtil.getMessage("validation.88level.null"),
                "value"
            );
        }
        
        if (validValues == null || validValues.isEmpty()) {
            throw new ValidationException(
                "VAL003",
                MessageUtil.getMessage("validation.88level.novalidvalues"),
                "value"
            );
        }
        
        if (!validValues.contains(value.trim())) {
            throw new ValidationException(
                "VAL004",
                MessageUtil.getMessage("validation.88level.invalid", String.join(", ", validValues)),
                "value"
            );
        }
        
        return true;
    }

    // ========================================================================
    // Mandatory Field Validation
    // ========================================================================

    /**
     * Validates that a mandatory field is not empty, throwing ValidationException if it is.
     * 
     * <p>Replicates COBOL mandatory field check with immediate error handling:</p>
     * <pre>
     * COBOL:
     *   WHEN field-name = SPACES OR LOW-VALUES
     *       MOVE 'Y' TO WS-ERR-FLG
     *       MOVE 'Please enter Field Name...' TO WS-MESSAGE
     * </pre>
     * 
     * <p>COBOL Source: COSGN00C.cbl lines 118-127 - Mandatory User ID and Password validation,
     * COACTUPC.cbl lines 72-75 - FLG-MANDATORY-ISVALID flags</p>
     * 
     * @param value     The field value to validate
     * @param fieldName The name of the field for error messaging
     * @throws ValidationException with error code VAL002 if field is empty or null
     */
    public static void validateMandatoryField(String value, String fieldName) {
        if (isBlank(value)) {
            throw new ValidationException(
                "VAL002",
                MessageUtil.getMessage("validation.mandatory.required", fieldName),
                fieldName
            );
        }
    }

    // ========================================================================
    // Domain-Specific Validation
    // ========================================================================

    /**
     * Validates that an account ID is a valid 11-digit number.
     * 
     * <p>Replicates COBOL PIC 9(11) account ID field validation.</p>
     * <p>COBOL Source: COTRN02C.cbl lines 196-200 - Account ID numeric validation,
     * CVACT01Y.cpy - ACCT-ID PIC 9(11)</p>
     * 
     * @param accountId The account ID to validate
     * @return true if account ID is valid (11 digits)
     */
    public static boolean isValidAccountId(Long accountId) {
        if (accountId == null) {
            return false;
        }
        
        String accountIdStr = String.valueOf(accountId);
        return ACCOUNT_ID_PATTERN.matcher(accountIdStr).matches();
    }

    /**
     * Validates that a card number string is a valid 16-digit number.
     * 
     * <p>Replicates COBOL PIC X(16) card number field validation.</p>
     * <p>COBOL Source: COCRDUPC.cbl - Card number validation,
     * CVACT02Y.cpy - CARD-NUM PIC X(16)</p>
     * 
     * @param cardNum The card number to validate
     * @return true if card number is valid (16 digits)
     */
    public static boolean isValidCardNumber(String cardNum) {
        if (isBlank(cardNum)) {
            return false;
        }
        
        String cleanCard = cardNum.trim().replaceAll("[^0-9]", "");
        return CARD_NUMBER_PATTERN.matcher(cleanCard).matches();
    }
}
