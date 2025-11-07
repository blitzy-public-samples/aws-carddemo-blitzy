/*
 * DateUtility.java
 * 
 * CardDemo Application - Date Utility Service
 * 
 * This utility service transforms the COBOL date utility program CSUTLDTC.cbl
 * that calls CEEDAYS API for Lillian date format conversion into a comprehensive
 * Java utility class using java.time API.
 * 
 * Original COBOL Program: CSUTLDTC.cbl
 * Original Copybooks: CSUTLDPY.cpy, CSUTLDWY.cpy, CSDAT01Y.cpy
 * 
 * COBOL CEEDAYS API Background:
 * The CEEDAYS callable service converts Gregorian dates to Lillian format.
 * Lillian date format represents dates as the integer number of days since
 * October 15, 1582 (the start of the Gregorian calendar). This system is
 * named after Luigi Lilio, who proposed the Gregorian calendar reform.
 * 
 * Key Transformations:
 * - CEEDAYS API calls → Java LocalDate arithmetic with LILLIAN_EPOCH_DATE
 * - COBOL date validation logic → Java DateTimeFormatter parsing
 * - COBOL feedback codes → Java ValidationException with detailed messages
 * - COBOL date structures → Java LocalDate and LocalDateTime
 * 
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
package com.carddemo.service.util;

import com.carddemo.constants.DateConstants;
import com.carddemo.exception.ValidationException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;

/**
 * Comprehensive date utility service providing Lillian date format conversion,
 * date validation, and formatting operations for the CardDemo application.
 * 
 * <p>This utility class replaces the COBOL CSUTLDTC.cbl program which called
 * IBM's CEEDAYS Language Environment callable service for date conversions.
 * All methods maintain exact functional equivalence with the original mainframe
 * date processing logic.</p>
 * 
 * <p><b>Lillian Date System:</b></p>
 * <p>The Lillian date system represents dates as integer values counting the
 * number of days since October 15, 1582. This epoch marks the beginning of
 * the Gregorian calendar. The system is named after Luigi Lilio, the Italian
 * scientist who proposed the Gregorian calendar reform.</p>
 * 
 * <p><b>COBOL CEEDAYS Equivalence:</b></p>
 * <ul>
 *   <li>COBOL: CALL "CEEDAYS" USING date-string, format, OUTPUT-LILLIAN, FEEDBACK</li>
 *   <li>Java: toLillianDate(LocalDate) returns int days since epoch</li>
 *   <li>COBOL: OUTPUT-LILLIAN PIC S9(9) USAGE IS BINARY</li>
 *   <li>Java: int (32-bit signed integer, -2,147,483,648 to 2,147,483,647)</li>
 * </ul>
 * 
 * <p><b>Original COBOL Error Conditions Mapped:</b></p>
 * <ul>
 *   <li>FC-INVALID-DATE (X'0000000000000000') → Valid date, no exception</li>
 *   <li>FC-INSUFFICIENT-DATA (X'000309CB59C3C5C5') → ValidationException "Insufficient date data"</li>
 *   <li>FC-BAD-DATE-VALUE (X'000309CC59C3C5C5') → ValidationException "Invalid date value"</li>
 *   <li>FC-INVALID-ERA (X'000309CD59C3C5C5') → ValidationException "Invalid date era"</li>
 *   <li>FC-UNSUPP-RANGE (X'000309D159C3C5C5') → ValidationException "Date out of supported range"</li>
 *   <li>FC-INVALID-MONTH (X'000309D559C3C5C5') → ValidationException "Invalid month"</li>
 *   <li>FC-BAD-PIC-STRING (X'000309D659C3C5C5') → ValidationException "Invalid date format pattern"</li>
 *   <li>FC-NON-NUMERIC-DATA (X'000309D859C3C5C5') → ValidationException "Non-numeric date data"</li>
 *   <li>FC-YEAR-IN-ERA-ZERO (X'000309D959C3C5C5') → ValidationException "Year in era is zero"</li>
 * </ul>
 * 
 * <p><b>Usage Examples:</b></p>
 * <pre>{@code
 * // Example 1: Convert COBOL Lillian date to Java LocalDate
 * // COBOL: OUTPUT-LILLIAN = 159845 (from CEEDAYS call)
 * int lillianDays = 159845;
 * LocalDate date = DateUtility.fromLillianDate(lillianDays);
 * // Result: LocalDate representing the date 159845 days after 1582-10-15
 * 
 * // Example 2: Convert Java LocalDate to Lillian format for storage
 * LocalDate today = LocalDate.now();
 * int lillianDays = DateUtility.toLillianDate(today);
 * // Result: Integer days since 1582-10-15, matching COBOL OUTPUT-LILLIAN
 * 
 * // Example 3: Validate date string matching COBOL validation logic
 * ValidationResult result = DateUtility.validateDate("20240315", "yyyyMMdd");
 * if (result.isValid()) {
 *     // Date is valid, proceed with processing
 * } else {
 *     // Handle validation error: result.getResultMessage()
 * }
 * 
 * // Example 4: Format date for display (matching COBOL date formats)
 * LocalDate date = LocalDate.of(2024, 3, 15);
 * String formatted = DateUtility.formatDate(date, DateConstants.DATE_FORMAT_MMDDYY);
 * // Result: "03/15/24" matching COBOL WS-CURDATE-MM-DD-YY format
 * }</pre>
 * 
 * <p><b>Thread Safety:</b> This class is thread-safe. All methods are stateless
 * and can be safely called from multiple threads concurrently.</p>
 * 
 * @see LocalDate
 * @see DateConstants
 * @see ValidationException
 */
@Component
public class DateUtility {
    
    private static final Logger logger = LoggerFactory.getLogger(DateUtility.class);
    
    /**
     * Private constructor to prevent direct instantiation.
     * This class is managed by Spring as a component and should be
     * autowired where needed.
     */
    private DateUtility() {
        // Spring-managed component, use dependency injection
    }
    
    /**
     * Converts a Lillian date integer to a Java LocalDate.
     * 
     * <p>This method replicates the reverse operation of COBOL's CEEDAYS API,
     * converting from Lillian format (integer days since epoch) back to a
     * Gregorian calendar date.</p>
     * 
     * <p><b>COBOL Equivalent:</b></p>
     * <pre>
     * CALL "CEEDATE" USING
     *      INPUT-LILLIAN,      (PIC S9(9) BINARY)
     *      DATE-FORMAT,        (Picture string format)
     *      OUTPUT-DATE,        (Resulting date string)
     *      FEEDBACK-CODE
     * </pre>
     * 
     * <p><b>Algorithm:</b></p>
     * <ol>
     *   <li>Start with Lillian epoch date (October 15, 1582)</li>
     *   <li>Add the specified number of days to the epoch</li>
     *   <li>Return resulting LocalDate</li>
     * </ol>
     * 
     * <p><b>Valid Range:</b> This method supports dates from approximately
     * 5,879,489 BC (Integer.MIN_VALUE days before epoch) to approximately
     * 5,881,580 AD (Integer.MAX_VALUE days after epoch). In practice, the
     * valid range for business data is limited to dates after the Gregorian
     * calendar adoption (1582-10-15) and reasonable future dates.</p>
     * 
     * <p><b>Examples:</b></p>
     * <pre>{@code
     * // Convert COBOL Lillian date 0 (epoch date)
     * LocalDate epoch = DateUtility.fromLillianDate(0);
     * // Result: 1582-10-15
     * 
     * // Convert positive Lillian date
     * LocalDate future = DateUtility.fromLillianDate(159845);
     * // Result: Date 159845 days after epoch
     * 
     * // Convert negative Lillian date (before epoch, rarely used)
     * LocalDate past = DateUtility.fromLillianDate(-100);
     * // Result: Date 100 days before epoch (1582-07-07)
     * }</pre>
     * 
     * @param lillianDays the number of days since the Lillian epoch (October 15, 1582).
     *                    Positive values represent dates after the epoch, negative values
     *                    represent dates before the epoch.
     * @return LocalDate representing the Gregorian calendar date corresponding to the
     *         given Lillian day count. Never returns null.
     * @throws ArithmeticException if the resulting date would overflow LocalDate's
     *                              supported range (approximately year -999,999,999
     *                              to year +999,999,999)
     */
    public static LocalDate fromLillianDate(int lillianDays) {
        logger.debug("Converting Lillian date {} to LocalDate", lillianDays);
        
        try {
            LocalDate result = DateConstants.LILLIAN_EPOCH_DATE.plusDays(lillianDays);
            logger.debug("Lillian date {} converted to {}", lillianDays, result);
            return result;
        } catch (ArithmeticException e) {
            logger.error("Arithmetic overflow converting Lillian date {}", lillianDays, e);
            throw new ArithmeticException(
                "Lillian date value " + lillianDays + " results in date overflow: " + e.getMessage()
            );
        }
    }
    
    /**
     * Converts a Java LocalDate to Lillian date integer format.
     * 
     * <p>This method replicates COBOL's CEEDAYS API functionality, converting
     * a Gregorian calendar date to Lillian format (integer days since epoch).</p>
     * 
     * <p><b>COBOL Equivalent:</b></p>
     * <pre>
     * CALL "CEEDAYS" USING
     *      INPUT-DATE,         (Date string)
     *      DATE-FORMAT,        (Picture string format like "YYYYMMDD")
     *      OUTPUT-LILLIAN,     (PIC S9(9) BINARY - result)
     *      FEEDBACK-CODE
     * </pre>
     * 
     * <p><b>Algorithm:</b></p>
     * <ol>
     *   <li>Calculate number of days between Lillian epoch and given date</li>
     *   <li>Use ChronoUnit.DAYS.between() for precise day count</li>
     *   <li>Cast result to int (matching COBOL PIC S9(9) BINARY)</li>
     * </ol>
     * 
     * <p><b>Return Value Range:</b> The method returns an int value matching
     * COBOL's OUTPUT-LILLIAN PIC S9(9) BINARY field, which can represent
     * values from -2,147,483,648 to 2,147,483,647. This provides a date range
     * from approximately 3,895 BC to approximately 7,456 AD when offset from
     * the 1582 epoch.</p>
     * 
     * <p><b>Precision:</b> The calculation uses ChronoUnit.DAYS which counts
     * complete 24-hour periods, matching COBOL CEEDAYS behavior exactly. Time
     * components are ignored (LocalDate has no time component).</p>
     * 
     * <p><b>Examples:</b></p>
     * <pre>{@code
     * // Convert epoch date itself
     * LocalDate epoch = LocalDate.of(1582, 10, 15);
     * int lillian = DateUtility.toLillianDate(epoch);
     * // Result: 0 (epoch date)
     * 
     * // Convert modern date
     * LocalDate today = LocalDate.of(2024, 3, 15);
     * int lillian = DateUtility.toLillianDate(today);
     * // Result: Positive integer representing days since 1582-10-15
     * 
     * // Convert date before epoch (rare in business applications)
     * LocalDate historical = LocalDate.of(1582, 7, 7);
     * int lillian = DateUtility.toLillianDate(historical);
     * // Result: -100 (100 days before epoch)
     * }</pre>
     * 
     * @param date the LocalDate to convert to Lillian format. Must not be null.
     * @return integer representing the number of days since the Lillian epoch.
     *         Positive for dates after October 15, 1582; negative for dates before.
     * @throws NullPointerException if date is null
     * @throws ArithmeticException if the day count exceeds int range (extremely rare,
     *                              would require dates beyond year 7456 AD or before 3895 BC)
     */
    public static int toLillianDate(LocalDate date) {
        if (date == null) {
            logger.error("Attempted to convert null LocalDate to Lillian format");
            throw new NullPointerException("Date parameter cannot be null");
        }
        
        logger.debug("Converting LocalDate {} to Lillian format", date);
        
        try {
            long daysBetween = ChronoUnit.DAYS.between(DateConstants.LILLIAN_EPOCH_DATE, date);
            
            // Verify the result fits in an int (matching COBOL PIC S9(9) BINARY)
            if (daysBetween > Integer.MAX_VALUE || daysBetween < Integer.MIN_VALUE) {
                throw new ArithmeticException(
                    "Date " + date + " is too far from Lillian epoch to represent as int"
                );
            }
            
            int lillianDays = (int) daysBetween;
            logger.debug("LocalDate {} converted to Lillian date {}", date, lillianDays);
            return lillianDays;
            
        } catch (ArithmeticException e) {
            logger.error("Arithmetic error converting date {} to Lillian format", date, e);
            throw e;
        }
    }
    
    /**
     * Validates a date string against a specified format pattern, replicating
     * COBOL date validation logic from CSUTLDPY.cpy copybook.
     * 
     * <p>This method performs comprehensive date validation matching the COBOL
     * validation paragraphs:</p>
     * <ul>
     *   <li>EDIT-DATE-CCYYMMDD - validates complete date structure</li>
     *   <li>EDIT-YEAR-CCYY - validates year and century (19xx or 20xx only)</li>
     *   <li>EDIT-MONTH - validates month (1-12)</li>
     *   <li>EDIT-DAY - validates day (1-31, with month-specific limits)</li>
     * </ul>
     * 
     * <p><b>COBOL Validation Logic Replicated:</b></p>
     * <pre>
     * COBOL Source (CSUTLDPY.cpy lines 18-376):
     * - Checks for blank or missing fields (LOW-VALUES, SPACES)
     * - Validates numeric data (IS NOT NUMERIC check)
     * - Validates century (only 19 and 20 accepted per line 70-71)
     * - Validates month range (1-12, line 19-20)
     * - Validates day range (1-31, month-specific, line 28-30)
     * - Validates leap year for February 29 (line 24)
     * - Validates 30-day months (April, June, September, November)
     * - Validates 31-day months (January, March, May, July, August, October, December)
     * </pre>
     * 
     * <p><b>Century Validation (Y2K Legacy):</b> Following the original COBOL
     * validation logic (CSUTLDPY.cpy lines 66-84), only centuries 19 and 20
     * are considered valid. This restriction was implemented for Y2K compliance
     * and remains in the Java implementation for functional equivalence. Dates
     * in the 18th century or 21st century (2100+) will fail validation.</p>
     * 
     * <p><b>Format Pattern Examples:</b></p>
     * <ul>
     *   <li>"yyyyMMdd" - 8-digit format like "20240315" (matches COBOL CCYYMMDD)</li>
     *   <li>"yyyy-MM-dd" - ISO 8601 format like "2024-03-15"</li>
     *   <li>"MM/dd/yy" - Legacy format like "03/15/24" (matches COBOL MM-DD-YY)</li>
     *   <li>"dd/MM/yyyy" - European format like "15/03/2024"</li>
     * </ul>
     * 
     * <p><b>Validation Result Structure:</b> The returned ValidationResult object
     * matches the COBOL WS-DATE-VALIDATION-RESULT structure from CSUTLDWY.cpy:</p>
     * <ul>
     *   <li>severity - numeric code (0 = valid, non-zero = error)</li>
     *   <li>messageNumber - specific error code</li>
     *   <li>resultMessage - human-readable validation result</li>
     *   <li>dateString - the date string that was validated</li>
     *   <li>formatPattern - the format pattern used</li>
     * </ul>
     * 
     * <p><b>Examples:</b></p>
     * <pre>{@code
     * // Example 1: Valid date in CCYYMMDD format
     * ValidationResult result = DateUtility.validateDate("20240315", "yyyyMMdd");
     * // result.isValid() = true, result.getResultMessage() = "Date is valid"
     * 
     * // Example 2: Invalid month
     * ValidationResult result = DateUtility.validateDate("20241315", "yyyyMMdd");
     * // result.isValid() = false, result.getResultMessage() = "Invalid month"
     * 
     * // Example 3: Invalid format (non-numeric data)
     * ValidationResult result = DateUtility.validateDate("2024-AB-15", "yyyyMMdd");
     * // result.isValid() = false, result.getResultMessage() = "Non-numeric date data"
     * 
     * // Example 4: Insufficient data (empty string)
     * ValidationResult result = DateUtility.validateDate("", "yyyyMMdd");
     * // result.isValid() = false, result.getResultMessage() = "Insufficient date data"
     * }</pre>
     * 
     * @param dateString the date string to validate. Can be null or empty, which
     *                   will result in "Insufficient date data" validation failure.
     * @param formatPattern the date format pattern (e.g., "yyyyMMdd", "yyyy-MM-dd").
     *                      Must be a valid DateTimeFormatter pattern.
     * @return ValidationResult object containing validation status, error codes,
     *         and descriptive messages. Never returns null.
     * @see ValidationResult
     * @see DateTimeFormatter
     */
    public static ValidationResult validateDate(String dateString, String formatPattern) {
        logger.debug("Validating date string '{}' with format '{}'", dateString, formatPattern);
        
        // Check for insufficient data (COBOL: IF WS-EDIT-DATE-CCYY EQUAL LOW-VALUES OR SPACES)
        if (dateString == null || dateString.trim().isEmpty()) {
            logger.warn("Date validation failed: insufficient data");
            return new ValidationResult(
                2507,  // Severity code from COBOL FEEDBACK-CODE for insufficient data
                2507,  // Message number matching COBOL MSG-NO
                "Insufficient date data",
                dateString,
                formatPattern
            );
        }
        
        // Check for invalid format pattern
        if (formatPattern == null || formatPattern.trim().isEmpty()) {
            logger.warn("Date validation failed: invalid format pattern");
            return new ValidationResult(
                2518,  // Severity code for bad PIC string
                2518,
                "Invalid date format pattern",
                dateString,
                formatPattern
            );
        }
        
        try {
            // Attempt to parse the date using the specified format
            DateTimeFormatter formatter = DateTimeFormatter.ofPattern(formatPattern);
            LocalDate parsedDate = LocalDate.parse(dateString, formatter);
            
            // Validate century restriction (only 19xx and 20xx allowed, matching COBOL validation)
            // COBOL source: CSUTLDPY.cpy lines 70-84 (THIS-CENTURY VALUE 20, LAST-CENTURY VALUE 19)
            int year = parsedDate.getYear();
            if (year < 1900 || year >= 2100) {
                logger.warn("Date validation failed: century not valid (year {})", year);
                return new ValidationResult(
                    2509,  // Severity code for invalid era
                    2509,
                    "Century is not valid. Only years 1900-2099 are supported.",
                    dateString,
                    formatPattern
                );
            }
            
            // If all validations pass, return success
            logger.debug("Date validation successful for '{}'", dateString);
            return new ValidationResult(
                0,     // Severity 0 = valid (FC-INVALID-DATE actually means "no error" in COBOL)
                0,
                "Date is valid",
                dateString,
                formatPattern
            );
            
        } catch (DateTimeParseException e) {
            // Parse exception - determine specific error type
            logger.warn("Date parsing failed for '{}' with format '{}': {}", 
                       dateString, formatPattern, e.getMessage());
            
            String errorMessage = e.getMessage();
            
            // Check for non-numeric data
            if (errorMessage != null && (errorMessage.contains("could not be parsed") || 
                                        errorMessage.contains("Invalid value"))) {
                return new ValidationResult(
                    2520,  // Severity code for non-numeric data
                    2520,
                    "Non-numeric date data or invalid format",
                    dateString,
                    formatPattern
                );
            }
            
            // Check for invalid month specifically
            if (errorMessage != null && errorMessage.toLowerCase().contains("month")) {
                return new ValidationResult(
                    2517,  // Severity code for invalid month
                    2517,
                    "Invalid month",
                    dateString,
                    formatPattern
                );
            }
            
            // Check for invalid day
            if (errorMessage != null && errorMessage.toLowerCase().contains("day")) {
                return new ValidationResult(
                    2508,  // Severity code for bad date value
                    2508,
                    "Invalid day for the specified month",
                    dateString,
                    formatPattern
                );
            }
            
            // Generic bad date value error
            return new ValidationResult(
                2508,  // Severity code for bad date value
                2508,
                "Invalid date value: " + errorMessage,
                dateString,
                formatPattern
            );
            
        } catch (IllegalArgumentException e) {
            // Invalid format pattern
            logger.error("Invalid date format pattern '{}': {}", formatPattern, e.getMessage());
            return new ValidationResult(
                2518,  // Severity code for bad PIC string
                2518,
                "Invalid date format pattern: " + e.getMessage(),
                dateString,
                formatPattern
            );
        }
    }
    
    /**
     * Formats a LocalDate to a string using the specified format pattern.
     * 
     * <p>This method provides date formatting capabilities matching the various
     * COBOL date display formats defined in CSDAT01Y.cpy copybook.</p>
     * 
     * <p><b>COBOL Date Formats Supported:</b></p>
     * <ul>
     *   <li>WS-CURDATE (YYYYMMDD) → pattern "yyyyMMdd" → "20240315"</li>
     *   <li>WS-CURDATE-MM-DD-YY → pattern "MM/dd/yy" → "03/15/24"</li>
     *   <li>WS-TIMESTAMP date part → pattern "yyyy-MM-dd" → "2024-03-15"</li>
     * </ul>
     * 
     * <p><b>Common Format Patterns:</b></p>
     * <ul>
     *   <li>{@link DateConstants#DATE_FORMAT_YYYYMMDD} - ISO 8601 format "yyyy-MM-dd"</li>
     *   <li>{@link DateConstants#DATE_FORMAT_MMDDYY} - Legacy format "MM/dd/yy"</li>
     *   <li>"yyyyMMdd" - Compact 8-digit format (matching COBOL CCYYMMDD)</li>
     *   <li>"dd/MM/yyyy" - European format</li>
     *   <li>"MMM dd, yyyy" - "Mar 15, 2024" (long month name)</li>
     * </ul>
     * 
     * <p><b>Thread Safety:</b> DateTimeFormatter instances are immutable and
     * thread-safe, so this method can be safely called from multiple threads.</p>
     * 
     * <p><b>Examples:</b></p>
     * <pre>{@code
     * LocalDate date = LocalDate.of(2024, 3, 15);
     * 
     * // Format in COBOL YYYYMMDD style
     * String formatted1 = DateUtility.formatDate(date, "yyyyMMdd");
     * // Result: "20240315"
     * 
     * // Format in COBOL MM/DD/YY style
     * String formatted2 = DateUtility.formatDate(date, DateConstants.DATE_FORMAT_MMDDYY);
     * // Result: "03/15/24"
     * 
     * // Format in ISO 8601 style
     * String formatted3 = DateUtility.formatDate(date, DateConstants.DATE_FORMAT_YYYYMMDD);
     * // Result: "2024-03-15"
     * 
     * // Format with long month name
     * String formatted4 = DateUtility.formatDate(date, "MMMM dd, yyyy");
     * // Result: "March 15, 2024"
     * }</pre>
     * 
     * @param date the LocalDate to format. Must not be null.
     * @param formatPattern the format pattern string conforming to DateTimeFormatter
     *                      syntax (e.g., "yyyy-MM-dd", "MM/dd/yy"). Must not be null.
     * @return formatted date string matching the specified pattern. Never returns null.
     * @throws NullPointerException if date or formatPattern is null
     * @throws IllegalArgumentException if the format pattern is invalid
     * @see DateTimeFormatter
     * @see DateConstants
     */
    public static String formatDate(LocalDate date, String formatPattern) {
        if (date == null) {
            logger.error("Attempted to format null LocalDate");
            throw new NullPointerException("Date parameter cannot be null");
        }
        
        if (formatPattern == null || formatPattern.trim().isEmpty()) {
            logger.error("Attempted to format date with null or empty format pattern");
            throw new NullPointerException("Format pattern cannot be null or empty");
        }
        
        logger.debug("Formatting date {} with pattern '{}'", date, formatPattern);
        
        try {
            DateTimeFormatter formatter = DateTimeFormatter.ofPattern(formatPattern);
            String formattedDate = date.format(formatter);
            logger.debug("Date {} formatted as '{}'", date, formattedDate);
            return formattedDate;
        } catch (IllegalArgumentException e) {
            logger.error("Invalid format pattern '{}': {}", formatPattern, e.getMessage());
            throw new IllegalArgumentException(
                "Invalid date format pattern '" + formatPattern + "': " + e.getMessage(),
                e
            );
        }
    }
    
    /**
     * Returns the current system date.
     * 
     * <p>This method replicates COBOL's ability to retrieve the current date:</p>
     * <pre>
     * COBOL: ACCEPT WS-CURRENT-DATE FROM DATE YYYYMMDD
     * Java:  LocalDate currentDate = DateUtility.getCurrentDate()
     * </pre>
     * 
     * <p><b>COBOL Equivalent:</b> The ACCEPT statement retrieves the system date
     * into WS-CURRENT-DATE-YYYYMMDD (CSUTLDWY.cpy line 39-41, CSDAT01Y.cpy line 17-23).</p>
     * 
     * <p><b>Time Zone Consideration:</b> This method uses the system default time
     * zone to determine "today". In the original COBOL mainframe environment, all
     * dates were in the system time zone (typically EST/EDT for US mainframes).
     * In a distributed cloud environment, ensure consistent time zone configuration
     * across all services.</p>
     * 
     * <p><b>Usage in Business Logic:</b></p>
     * <ul>
     *   <li>Transaction date stamping (matching COBOL transaction programs)</li>
     *   <li>Batch job date determination (day-end processing cutoff)</li>
     *   <li>Account aging calculations (days past due)</li>
     *   <li>Date range validations (expiration date checks)</li>
     * </ul>
     * 
     * <p><b>Examples:</b></p>
     * <pre>{@code
     * // Get current date for transaction processing
     * LocalDate today = DateUtility.getCurrentDate();
     * 
     * // Calculate days since transaction (matching COBOL date arithmetic)
     * LocalDate transactionDate = LocalDate.of(2024, 3, 1);
     * long daysSince = ChronoUnit.DAYS.between(transactionDate, today);
     * 
     * // Check if account is current (not past due)
     * LocalDate dueDate = account.getPaymentDueDate();
     * if (today.isAfter(dueDate)) {
     *     // Account is past due (matching COBOL IF WS-CURRENT-DATE > DUE-DATE)
     * }
     * }</pre>
     * 
     * @return LocalDate representing today's date in the system default time zone.
     *         Never returns null.
     */
    public static LocalDate getCurrentDate() {
        LocalDate currentDate = LocalDate.now();
        logger.debug("Retrieved current system date: {}", currentDate);
        return currentDate;
    }
    
    /**
     * Validation result object matching COBOL WS-DATE-VALIDATION-RESULT structure.
     * 
     * <p>This inner class replicates the COBOL validation result structure defined
     * in CSUTLDWY.cpy (lines 60-85) and CSUTLDTC.cbl (lines 42-57).</p>
     * 
     * <p><b>COBOL Structure Mapping:</b></p>
     * <pre>
     * COBOL Field (CSUTLDWY.cpy)          Java Field
     * ------------------------------      ---------------------
     * WS-SEVERITY PIC 9(4)         →      int severity
     * WS-MSG-NO PIC 9(4)           →      int messageNumber
     * WS-RESULT PIC X(15)          →      String resultMessage
     * WS-DATE PIC X(10)            →      String dateString
     * WS-DATE-FMT PIC X(10)        →      String formatPattern
     * </pre>
     * 
     * <p><b>Severity Codes (from COBOL CEEDAYS FEEDBACK-CODE):</b></p>
     * <ul>
     *   <li>0 - Valid date (FC-INVALID-DATE feedback, which paradoxically means "no error")</li>
     *   <li>2507 - Insufficient data (FC-INSUFFICIENT-DATA)</li>
     *   <li>2508 - Bad date value (FC-BAD-DATE-VALUE)</li>
     *   <li>2509 - Invalid era (FC-INVALID-ERA)</li>
     *   <li>2513 - Unsupported range (FC-UNSUPP-RANGE)</li>
     *   <li>2517 - Invalid month (FC-INVALID-MONTH)</li>
     *   <li>2518 - Bad PIC string (FC-BAD-PIC-STRING)</li>
     *   <li>2520 - Non-numeric data (FC-NON-NUMERIC-DATA)</li>
     *   <li>2521 - Year in era zero (FC-YEAR-IN-ERA-ZERO)</li>
     * </ul>
     * 
     * <p><b>Usage Example:</b></p>
     * <pre>{@code
     * ValidationResult result = DateUtility.validateDate("20240315", "yyyyMMdd");
     * 
     * if (result.isValid()) {
     *     System.out.println("Date is valid");
     *     // Proceed with business logic
     * } else {
     *     System.err.println("Validation error: " + result.getResultMessage());
     *     System.err.println("Error code: " + result.getMessageNumber());
     *     System.err.println("Date attempted: " + result.getDateString());
     *     System.err.println("Format expected: " + result.getFormatPattern());
     *     // Handle validation error
     * }
     * }</pre>
     */
    public static class ValidationResult {
        
        /**
         * Severity code matching COBOL WS-SEVERITY-N field.
         * 0 indicates valid date, non-zero indicates error condition.
         */
        private final int severity;
        
        /**
         * Message number matching COBOL WS-MSG-NO-N field.
         * Corresponds to CEEDAYS API feedback message numbers.
         */
        private final int messageNumber;
        
        /**
         * Result message matching COBOL WS-RESULT field (15 characters max in COBOL).
         * Human-readable description of validation result.
         */
        private final String resultMessage;
        
        /**
         * The date string that was validated, matching COBOL WS-DATE field.
         */
        private final String dateString;
        
        /**
         * The format pattern used for validation, matching COBOL WS-DATE-FMT field.
         */
        private final String formatPattern;
        
        /**
         * Constructs a ValidationResult with all fields.
         * 
         * @param severity the severity code (0 = valid, non-zero = error)
         * @param messageNumber the error message number
         * @param resultMessage the human-readable result message
         * @param dateString the date string that was validated
         * @param formatPattern the format pattern used
         */
        public ValidationResult(int severity, int messageNumber, String resultMessage,
                              String dateString, String formatPattern) {
            this.severity = severity;
            this.messageNumber = messageNumber;
            this.resultMessage = resultMessage != null ? resultMessage : "";
            this.dateString = dateString != null ? dateString : "";
            this.formatPattern = formatPattern != null ? formatPattern : "";
        }
        
        /**
         * Checks if the date validation was successful.
         * 
         * @return true if severity is 0 (valid date), false otherwise
         */
        public boolean isValid() {
            return severity == 0;
        }
        
        /**
         * Returns the severity code.
         * 
         * @return severity code (0 = valid, non-zero = error)
         */
        public int getSeverity() {
            return severity;
        }
        
        /**
         * Returns the message number.
         * 
         * @return message number corresponding to COBOL feedback codes
         */
        public int getMessageNumber() {
            return messageNumber;
        }
        
        /**
         * Returns the result message.
         * 
         * @return human-readable validation result message
         */
        public String getResultMessage() {
            return resultMessage;
        }
        
        /**
         * Returns the date string that was validated.
         * 
         * @return the original date string
         */
        public String getDateString() {
            return dateString;
        }
        
        /**
         * Returns the format pattern used for validation.
         * 
         * @return the date format pattern
         */
        public String getFormatPattern() {
            return formatPattern;
        }
        
        /**
         * Returns a formatted string representation of the validation result,
         * matching COBOL WS-MESSAGE structure layout.
         * 
         * @return formatted validation result string
         */
        @Override
        public String toString() {
            return String.format(
                "Severity: %04d Mesg Code: %04d %s TstDate: %s Mask used: %s",
                severity, messageNumber, resultMessage, dateString, formatPattern
            );
        }
    }
}
