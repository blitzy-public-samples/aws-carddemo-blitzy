/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
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
package com.cardemo.common.validation;

import com.cardemo.common.exception.ValidationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.format.ResolverStyle;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Field validation utility replicating the validation logic from COBOL copybooks
 * CSUTLDPY.cpy (validation paragraphs) and CSUTLDWY.cpy (working storage).
 *
 * <p>The original COBOL validation performs field-level checks on BMS map input
 * before VSAM I/O operations. This class faithfully reproduces those checks
 * in Java, covering:</p>
 *
 * <h2>Paragraph-to-Method Mapping (CSUTLDPY.cpy)</h2>
 * <table>
 *   <tr><th>COBOL Paragraph</th><th>Java Method</th><th>Function</th></tr>
 *   <tr><td>EDIT-DATE-CCYYMMDD</td><td>{@link #validateDateCcyymmdd(String)}</td>
 *       <td>Main date validation entry point</td></tr>
 *   <tr><td>EDIT-YEAR-CCYY</td><td>{@link #validateYear(int)}</td>
 *       <td>Century 19/20 check + numeric validation</td></tr>
 *   <tr><td>EDIT-MONTH</td><td>{@link #validateMonth(int)}</td>
 *       <td>Month range 1–12</td></tr>
 *   <tr><td>EDIT-DAY</td><td>{@link #validateDay(int)}</td>
 *       <td>Day range 1–31</td></tr>
 *   <tr><td>EDIT-DAY-MONTH-YEAR</td><td>{@link #validateDayMonthYear(int, int, int)}</td>
 *       <td>Day/month combination + leap year</td></tr>
 *   <tr><td>EDIT-DATE-OF-BIRTH</td><td>{@link #validateDateOfBirth(String)}</td>
 *       <td>Must not be a future date</td></tr>
 * </table>
 *
 * <h2>Additional BMS Map Field Validation</h2>
 * <ul>
 *   <li>{@link #validateNumeric(String, String)} — {@code IF NOT NUMERIC} COBOL checks</li>
 *   <li>{@link #validateRequired(String, String)} — blank/spaces field checks</li>
 *   <li>{@link #validateLength(String, String, int)} — PIC X(n) length enforcement</li>
 *   <li>{@link #validateMaxLength(String, String, int)} — maximum length enforcement</li>
 *   <li>{@link #validatePositiveAmount(BigDecimal, String)} — monetary amount checks</li>
 * </ul>
 *
 * @see com.cardemo.common.exception.ValidationException
 * @see com.cardemo.common.util.DateConversionUtil
 */
@Component
public class FieldValidator {

    private static final Logger log = LoggerFactory.getLogger(FieldValidator.class);

    /**
     * Strict date formatter for CCYYMMDD format.
     * Uses STRICT resolver style to reject invalid dates like Feb 30.
     */
    private static final DateTimeFormatter CCYYMMDD_FORMATTER =
            DateTimeFormatter.ofPattern("uuuuMMdd")
                    .withResolverStyle(ResolverStyle.STRICT);

    /**
     * Months with 31 days, matching the COBOL EDIT-DAY-MONTH-YEAR paragraph
     * which checks: IF WS-EDIT-MONTH-N = 01 OR 03 OR 05 OR 07 OR 08 OR 10 OR 12.
     */
    private static final Set<Integer> MONTHS_WITH_31_DAYS = Set.of(1, 3, 5, 7, 8, 10, 12);

    /**
     * Months with 30 days, matching the COBOL check:
     * IF WS-EDIT-MONTH-N = 04 OR 06 OR 09 OR 11.
     */
    private static final Set<Integer> MONTHS_WITH_30_DAYS = Set.of(4, 6, 9, 11);

    // ========================================================================
    // Date Validation — CSUTLDPY.cpy Paragraph Translations
    // ========================================================================

    /**
     * Validates a date string in CCYYMMDD format.
     *
     * <p>Translates COBOL paragraph {@code EDIT-DATE-CCYYMMDD} from CSUTLDPY.cpy,
     * which sequentially calls EDIT-YEAR-CCYY, EDIT-MONTH, EDIT-DAY,
     * and EDIT-DAY-MONTH-YEAR.</p>
     *
     * <p>Validation steps (matching COBOL order):</p>
     * <ol>
     *   <li>String must be exactly 8 characters and all numeric</li>
     *   <li>Year (CCYY) must be in century 19 or 20 (1900–2099)</li>
     *   <li>Month must be 01–12</li>
     *   <li>Day must be 01–31</li>
     *   <li>Day must be valid for the given month/year (leap year aware)</li>
     * </ol>
     *
     * @param dateStr the date string in CCYYMMDD format (e.g., "20241015")
     * @return {@code true} if the date is valid
     * @throws ValidationException if any validation check fails
     */
    public boolean validateDateCcyymmdd(String dateStr) {
        if (dateStr == null || dateStr.length() != 8) {
            throw new ValidationException("date",
                    "Date must be exactly 8 characters in CCYYMMDD format");
        }

        if (!dateStr.chars().allMatch(Character::isDigit)) {
            throw new ValidationException("date",
                    "Date must contain only numeric characters (CCYYMMDD)");
        }

        int cc = Integer.parseInt(dateStr.substring(0, 2));
        int yy = Integer.parseInt(dateStr.substring(2, 4));
        int ccyy = Integer.parseInt(dateStr.substring(0, 4));
        int mm = Integer.parseInt(dateStr.substring(4, 6));
        int dd = Integer.parseInt(dateStr.substring(6, 8));

        // EDIT-YEAR-CCYY: Century must be 19 or 20
        validateYear(ccyy);

        // EDIT-MONTH: Month must be 01–12
        validateMonth(mm);

        // EDIT-DAY: Day must be 01–31
        validateDay(dd);

        // EDIT-DAY-MONTH-YEAR: Day/month/year combination must be valid
        validateDayMonthYear(ccyy, mm, dd);

        log.debug("Date validation passed for CCYYMMDD: {}", dateStr);
        return true;
    }

    /**
     * Validates the year component of a CCYYMMDD date.
     *
     * <p>Translates COBOL paragraph {@code EDIT-YEAR-CCYY} from CSUTLDPY.cpy:</p>
     * <pre>
     *     IF WS-EDIT-CENTURY-N NOT = 19 AND NOT = 20
     *         SET WS-EDIT-YEAR-NOT-VALID TO TRUE
     *     END-IF
     * </pre>
     *
     * @param ccyy the four-digit year (e.g., 2024)
     * @throws ValidationException if the century is not 19 or 20
     */
    public void validateYear(int ccyy) {
        int century = ccyy / 100;
        if (century != 19 && century != 20) {
            throw new ValidationException("year",
                    "Year century must be 19 or 20, got: " + century);
        }
    }

    /**
     * Validates the month component of a date.
     *
     * <p>Translates COBOL paragraph {@code EDIT-MONTH} from CSUTLDPY.cpy:</p>
     * <pre>
     *     IF WS-EDIT-MONTH-N &lt; 1 OR &gt; 12
     *         SET WS-EDIT-MONTH-NOT-VALID TO TRUE
     *     END-IF
     * </pre>
     *
     * @param month the month value (1–12)
     * @throws ValidationException if the month is outside range 1–12
     */
    public void validateMonth(int month) {
        if (month < 1 || month > 12) {
            throw new ValidationException("month",
                    "Month must be between 1 and 12, got: " + month);
        }
    }

    /**
     * Validates the day component of a date.
     *
     * <p>Translates COBOL paragraph {@code EDIT-DAY} from CSUTLDPY.cpy:</p>
     * <pre>
     *     IF WS-EDIT-DAY-N &lt; 1 OR &gt; 31
     *         SET WS-EDIT-DAY-NOT-VALID TO TRUE
     *     END-IF
     * </pre>
     *
     * @param day the day value (1–31)
     * @throws ValidationException if the day is outside range 1–31
     */
    public void validateDay(int day) {
        if (day < 1 || day > 31) {
            throw new ValidationException("day",
                    "Day must be between 1 and 31, got: " + day);
        }
    }

    /**
     * Validates the day-month-year combination including leap year rules.
     *
     * <p>Translates COBOL paragraph {@code EDIT-DAY-MONTH-YEAR} from CSUTLDPY.cpy,
     * which performs the following checks in order:</p>
     * <ol>
     *   <li>31-day months (Jan, Mar, May, Jul, Aug, Oct, Dec): day ≤ 31</li>
     *   <li>30-day months (Apr, Jun, Sep, Nov): day ≤ 30</li>
     *   <li>February:
     *     <ul>
     *       <li>Day ≤ 29 for leap years</li>
     *       <li>Day ≤ 28 for non-leap years</li>
     *       <li>Leap year: divisible by 4, except centuries not divisible by 400</li>
     *     </ul>
     *   </li>
     * </ol>
     *
     * <p>The COBOL leap year check:</p>
     * <pre>
     *     DIVIDE WS-EDIT-YEAR-N BY 4 GIVING WS-QUOTIENT REMAINDER WS-REMAINDER
     *     IF WS-REMAINDER = 0
     *         IF WS-EDIT-DAY-N &gt; 29
     *             SET WS-EDIT-DAY-NOT-VALID TO TRUE
     *         END-IF
     *     ELSE
     *         IF WS-EDIT-DAY-N &gt; 28
     *             SET WS-EDIT-DAY-NOT-VALID TO TRUE
     *         END-IF
     *     END-IF
     * </pre>
     *
     * @param year  the four-digit year
     * @param month the month (1–12)
     * @param day   the day (1–31)
     * @throws ValidationException if the day is not valid for the given month/year
     */
    public void validateDayMonthYear(int year, int month, int day) {
        if (MONTHS_WITH_31_DAYS.contains(month)) {
            // 31-day months: day already validated ≤ 31 by validateDay()
            return;
        }

        if (MONTHS_WITH_30_DAYS.contains(month)) {
            if (day > 30) {
                throw new ValidationException("day",
                        "Day must be 1-30 for month " + month + ", got: " + day);
            }
            return;
        }

        // February (month == 2)
        if (month == 2) {
            boolean leapYear = isLeapYear(year);
            int maxDay = leapYear ? 29 : 28;
            if (day > maxDay) {
                throw new ValidationException("day",
                        "Day must be 1-" + maxDay + " for February " + year
                                + (leapYear ? " (leap year)" : " (non-leap year)")
                                + ", got: " + day);
            }
        }
    }

    /**
     * Determines whether a year is a leap year, matching the COBOL
     * divisibility check from EDIT-DAY-MONTH-YEAR paragraph.
     *
     * <p>Leap year rules (Gregorian calendar):</p>
     * <ul>
     *   <li>Divisible by 4 → leap year</li>
     *   <li>Except: divisible by 100 → not a leap year</li>
     *   <li>Except: divisible by 400 → leap year</li>
     * </ul>
     *
     * @param year the four-digit year
     * @return {@code true} if the year is a leap year
     */
    public boolean isLeapYear(int year) {
        if (year % 400 == 0) {
            return true;
        }
        if (year % 100 == 0) {
            return false;
        }
        return year % 4 == 0;
    }

    /**
     * Validates that a date in CCYYMMDD format is not in the future.
     *
     * <p>Translates COBOL paragraph {@code EDIT-DATE-OF-BIRTH} from CSUTLDPY.cpy,
     * which checks that the date-of-birth does not exceed the current date.
     * The COBOL logic obtains the current date via {@code EXEC CICS ASKTIME}
     * and compares it with the entered date.</p>
     *
     * @param dateStr the date string in CCYYMMDD format
     * @return {@code true} if the date is today or in the past
     * @throws ValidationException if the date is in the future or invalid
     */
    public boolean validateDateOfBirth(String dateStr) {
        // First, validate the date format and components
        validateDateCcyymmdd(dateStr);

        // Parse to LocalDate for comparison
        LocalDate date = LocalDate.parse(dateStr, CCYYMMDD_FORMATTER);
        LocalDate today = LocalDate.now();

        if (date.isAfter(today)) {
            throw new ValidationException("dateOfBirth",
                    "Date of birth cannot be in the future: " + dateStr);
        }

        log.debug("Date-of-birth validation passed: {}", dateStr);
        return true;
    }

    /**
     * Validates a date string in MM/DD/YYYY display format.
     *
     * <p>This corresponds to the display-format date validation used in
     * BMS map fields, which present dates in {@code MM/DD/YYYY} format
     * to the user.</p>
     *
     * @param dateStr the date string in MM/DD/YYYY format (e.g., "10/15/2024")
     * @return {@code true} if the date is valid
     * @throws ValidationException if the date is invalid
     */
    public boolean validateDateMmddyyyy(String dateStr) {
        if (dateStr == null || dateStr.length() != 10) {
            throw new ValidationException("date",
                    "Date must be in MM/DD/YYYY format (10 characters)");
        }

        if (dateStr.charAt(2) != '/' || dateStr.charAt(5) != '/') {
            throw new ValidationException("date",
                    "Date must use '/' separators in MM/DD/YYYY format");
        }

        String mmStr = dateStr.substring(0, 2);
        String ddStr = dateStr.substring(3, 5);
        String yyyyStr = dateStr.substring(6, 10);

        if (!mmStr.chars().allMatch(Character::isDigit)
                || !ddStr.chars().allMatch(Character::isDigit)
                || !yyyyStr.chars().allMatch(Character::isDigit)) {
            throw new ValidationException("date",
                    "Date components must be numeric in MM/DD/YYYY format");
        }

        // Convert to CCYYMMDD and delegate to the main validator
        String ccyymmdd = yyyyStr + mmStr + ddStr;
        return validateDateCcyymmdd(ccyymmdd);
    }

    // ========================================================================
    // General BMS Map Field Validation
    // ========================================================================

    /**
     * Validates that a field value is not null, empty, or blank (spaces only).
     *
     * <p>Translates the COBOL pattern used across all online programs:</p>
     * <pre>
     *     IF &lt;field&gt; = SPACES OR LOW-VALUES
     *         MOVE 'Field is required' TO WS-MESSAGE
     *         ...
     *     END-IF
     * </pre>
     *
     * @param value     the field value to check
     * @param fieldName the name of the field (for error messages)
     * @throws ValidationException if the value is null, empty, or blank
     */
    public void validateRequired(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            throw new ValidationException(fieldName,
                    fieldName + " is required and must not be blank");
        }
    }

    /**
     * Validates that a field value contains only numeric characters.
     *
     * <p>Translates the COBOL {@code IF NOT NUMERIC} check used for
     * account IDs, card numbers, transaction amounts, and other
     * numeric BMS map fields.</p>
     *
     * @param value     the field value to check
     * @param fieldName the name of the field (for error messages)
     * @throws ValidationException if the value contains non-numeric characters
     */
    public void validateNumeric(String value, String fieldName) {
        if (value == null || value.isEmpty()) {
            throw new ValidationException(fieldName,
                    fieldName + " must not be empty for numeric validation");
        }
        if (!value.chars().allMatch(Character::isDigit)) {
            throw new ValidationException(fieldName,
                    fieldName + " must contain only numeric characters");
        }
    }

    /**
     * Validates that a field value has the exact specified length.
     *
     * <p>Translates the COBOL PIC X(n) fixed-length field checks. In COBOL,
     * fields are always padded to their PIC length, but in Java, input may
     * arrive trimmed. This check ensures the content matches the expected
     * COBOL field size.</p>
     *
     * @param value     the field value to check
     * @param fieldName the name of the field (for error messages)
     * @param length    the exact required length
     * @throws ValidationException if the value does not have the exact specified length
     */
    public void validateLength(String value, String fieldName, int length) {
        if (value == null || value.length() != length) {
            int actualLength = (value == null) ? 0 : value.length();
            throw new ValidationException(fieldName,
                    fieldName + " must be exactly " + length + " characters, got: " + actualLength);
        }
    }

    /**
     * Validates that a field value does not exceed the maximum length.
     *
     * <p>A relaxed form of {@link #validateLength} that allows shorter values
     * (common for trimmed input from REST APIs, as opposed to fixed-width
     * COBOL PIC X(n) fields which are always padded).</p>
     *
     * @param value     the field value to check
     * @param fieldName the name of the field (for error messages)
     * @param maxLength the maximum allowed length
     * @throws ValidationException if the value exceeds the maximum length
     */
    public void validateMaxLength(String value, String fieldName, int maxLength) {
        if (value != null && value.length() > maxLength) {
            throw new ValidationException(fieldName,
                    fieldName + " must not exceed " + maxLength + " characters, got: " + value.length());
        }
    }

    /**
     * Validates that a BigDecimal amount is positive (greater than zero).
     *
     * <p>Used for monetary field validation matching COBOL checks like:</p>
     * <pre>
     *     IF WS-TRAN-AMT NOT &gt; ZERO
     *         MOVE 'Amount must be positive' TO WS-MESSAGE
     *     END-IF
     * </pre>
     *
     * @param amount    the monetary amount to validate
     * @param fieldName the name of the field (for error messages)
     * @throws ValidationException if the amount is null, zero, or negative
     */
    public void validatePositiveAmount(BigDecimal amount, String fieldName) {
        if (amount == null || amount.compareTo(BigDecimal.ZERO) <= 0) {
            throw new ValidationException(fieldName,
                    fieldName + " must be a positive amount");
        }
    }

    /**
     * Validates that a BigDecimal amount is non-negative (zero or greater).
     *
     * <p>Used for balance fields and credit limit checks where zero is acceptable.</p>
     *
     * @param amount    the monetary amount to validate
     * @param fieldName the name of the field (for error messages)
     * @throws ValidationException if the amount is null or negative
     */
    public void validateNonNegativeAmount(BigDecimal amount, String fieldName) {
        if (amount == null || amount.compareTo(BigDecimal.ZERO) < 0) {
            throw new ValidationException(fieldName,
                    fieldName + " must be zero or a positive amount");
        }
    }

    /**
     * Validates that a value is one of the allowed values.
     *
     * <p>Translates the COBOL EVALUATE / 88-level condition checks used
     * for field values like transaction types, account status codes,
     * and user types.</p>
     *
     * @param value        the value to validate
     * @param fieldName    the name of the field (for error messages)
     * @param allowedValues the set of allowed values
     * @throws ValidationException if the value is not in the allowed set
     */
    public void validateAllowedValues(String value, String fieldName, Set<String> allowedValues) {
        if (value == null || !allowedValues.contains(value)) {
            throw new ValidationException(fieldName,
                    fieldName + " must be one of " + allowedValues + ", got: " + value);
        }
    }

    /**
     * Validates a card number: must be 16 digits.
     *
     * <p>Matches COBOL PIC 9(16) for CARD-NUM in CVACT02Y.cpy.</p>
     *
     * @param cardNumber the card number to validate
     * @throws ValidationException if the card number is not exactly 16 digits
     */
    public void validateCardNumber(String cardNumber) {
        validateRequired(cardNumber, "cardNumber");
        validateLength(cardNumber, "cardNumber", 16);
        validateNumeric(cardNumber, "cardNumber");
    }

    /**
     * Validates an account ID: must be 11 digits.
     *
     * <p>Matches COBOL PIC 9(11) for ACCT-ID in CVACT01Y.cpy.</p>
     *
     * @param accountId the account ID to validate
     * @throws ValidationException if the account ID is not exactly 11 digits
     */
    public void validateAccountId(String accountId) {
        validateRequired(accountId, "accountId");
        validateLength(accountId, "accountId", 11);
        validateNumeric(accountId, "accountId");
    }

    /**
     * Validates a customer ID: must be 9 digits.
     *
     * <p>Matches COBOL PIC 9(09) for CUST-ID in CVCUS01Y.cpy.</p>
     *
     * @param customerId the customer ID to validate
     * @throws ValidationException if the customer ID is not exactly 9 digits
     */
    public void validateCustomerId(String customerId) {
        validateRequired(customerId, "customerId");
        validateLength(customerId, "customerId", 9);
        validateNumeric(customerId, "customerId");
    }

    /**
     * Performs bulk validation on multiple fields and collects all errors.
     *
     * <p>This method runs a list of validation checks and collects all failures
     * rather than failing on the first error. This matches the COBOL BMS pattern
     * where all map fields are validated before sending the error map back to
     * the terminal, allowing the user to fix all errors at once.</p>
     *
     * @param validations a list of {@link Runnable} validation checks
     * @throws ValidationException with all collected field errors if any validation fails
     */
    public void validateAll(List<Runnable> validations) {
        List<String> errors = new ArrayList<>();
        for (Runnable validation : validations) {
            try {
                validation.run();
            } catch (ValidationException e) {
                errors.add(e.getMessage());
            }
        }
        if (!errors.isEmpty()) {
            throw new ValidationException(
                    "Validation failed with " + errors.size() + " error(s): " + String.join("; ", errors));
        }
    }
}
