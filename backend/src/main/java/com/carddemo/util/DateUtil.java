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

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.format.ResolverStyle;
import java.time.temporal.ChronoUnit;

/**
 * Utility class for date and time operations transformed from COBOL date utilities.
 * 
 * <p>This class provides static methods for date manipulation, validation, and formatting
 * that preserve the exact logic and semantics from the mainframe COBOL programs:
 * <ul>
 *   <li>CSUTLDTC.cbl - Date validation program using CEEDAYS API</li>
 *   <li>CSDAT01Y.cpy - Date/time structure copybook with WS-DATE-TIME layout</li>
 * </ul>
 * 
 * <p>All methods are thread-safe and stateless, using the java.time API introduced in Java 8
 * to replace COBOL date handling with Lillian dates (CEEDAYS API).
 * 
 * <p><b>COBOL Source Mappings:</b>
 * <ul>
 *   <li>WS-CURDATE (YYYYMMDD PIC 9(08)) → parseCobolDateNumeric(String)</li>
 *   <li>WS-TIMESTAMP (YYYY-MM-DD HH:MM:SS.SSSSSS) → formatToCobolDateTime(LocalDateTime)</li>
 *   <li>CEEDAYS API validation → validateDate(String, String)</li>
 *   <li>FEEDBACK-CODE checks → DateTimeParseException handling</li>
 * </ul>
 * 
 * @see java.time.LocalDate
 * @see java.time.LocalDateTime
 * @see java.time.format.DateTimeFormatter
 */
public class DateUtil {

    /**
     * Standard COBOL date format: YYYY-MM-DD (PIC X(10) from COBOL programs).
     * This is the primary date format used in CardDemo COBOL programs for
     * account dates, card expiration dates, and transaction dates.
     * Uses 'uuuu' (proleptic year) for proper STRICT parsing support.
     */
    private static final DateTimeFormatter COBOL_DATE_FORMATTER = 
        DateTimeFormatter.ofPattern("uuuu-MM-dd");

    /**
     * Numeric COBOL date format: YYYYMMDD (PIC 9(08) from WS-CURDATE-N).
     * Used in COBOL copybook CSDAT01Y.cpy for compact numeric date representation.
     * Uses 'uuuu' (proleptic year) for proper STRICT parsing support.
     */
    private static final DateTimeFormatter COBOL_DATE_NUMERIC_FORMATTER = 
        DateTimeFormatter.ofPattern("uuuuMMdd");

    /**
     * COBOL timestamp format: YYYY-MM-DD HH:MM:SS.SSSSSS (WS-TIMESTAMP structure).
     * Includes microsecond precision (6 digits) as defined in CSDAT01Y.cpy lines 42-55.
     * Uses 'uuuu' (proleptic year) for proper STRICT parsing support.
     */
    private static final DateTimeFormatter COBOL_DATETIME_FORMATTER = 
        DateTimeFormatter.ofPattern("uuuu-MM-dd HH:mm:ss.SSSSSS");

    /**
     * Display format used in COBOL for formatted dates: MM/DD/YY (WS-CURDATE-MM-DD-YY).
     * Two-digit year format from CSDAT01Y.cpy lines 30-35.
     */
    private static final DateTimeFormatter COBOL_DISPLAY_FORMATTER = 
        DateTimeFormatter.ofPattern("MM/dd/yy");

    /**
     * Private constructor to prevent instantiation of utility class.
     * All methods are static and the class is stateless.
     */
    private DateUtil() {
        throw new UnsupportedOperationException("DateUtil is a utility class and cannot be instantiated");
    }

    /**
     * Validates a date string against a specified format, replicating COBOL CEEDAYS API behavior.
     * 
     * <p>This method replaces the COBOL program CSUTLDTC.cbl which calls the CEEDAYS API
     * to validate dates using Lillian date conversion. The validation feedback codes from
     * COBOL (lines 62-70) are translated to Java exception handling:
     * <ul>
     *   <li>FC-INVALID-DATE (X'0000000000000000') → returns "Date is valid"</li>
     *   <li>FC-BAD-DATE-VALUE (X'000309CC59C3C5C5') → returns "Datevalue error"</li>
     *   <li>FC-INVALID-MONTH (X'000309D559C3C5C5') → returns "Invalid month"</li>
     *   <li>FC-NON-NUMERIC-DATA (X'000309D859C3C5C5') → returns "Nonnumeric data"</li>
     *   <li>FC-YEAR-IN-ERA-ZERO (X'000309D959C3C5C5') → returns "YearInEra is 0"</li>
     * </ul>
     * 
     * @param dateStr the date string to validate (e.g., "2024-01-15")
     * @param format the expected date format pattern (e.g., "yyyy-MM-dd")
     * @return validation result message: "Date is valid" or specific error message
     */
    public static String validateDate(String dateStr, String format) {
        if (dateStr == null || dateStr.trim().isEmpty()) {
            return "Insufficient data";
        }
        
        if (format == null || format.trim().isEmpty()) {
            return "Bad Pic String";
        }
        
        try {
            // Convert yyyy to uuuu for STRICT mode compatibility (year-of-era to proleptic year)
            String strictFormat = format.replace("yyyy", "uuuu").replace("YYYY", "uuuu");
            
            // Use STRICT resolver style to detect invalid dates like Feb 30
            DateTimeFormatter formatter = DateTimeFormatter.ofPattern(strictFormat)
                .withResolverStyle(ResolverStyle.STRICT);
            LocalDate.parse(dateStr, formatter);
            return "Date is valid";
        } catch (DateTimeParseException e) {
            String message = e.getMessage();
            String parsedString = e.getParsedString();
            
            // Check for non-numeric data in numeric date formats
            if (format.matches(".*[yMd]+.*") && !format.contains("-") && !format.contains("/")) {
                if (parsedString != null && !parsedString.matches("\\d+")) {
                    return "Nonnumeric data";
                }
            }
            
            // Check for invalid month values
            if (message != null && (message.contains("MonthOfYear") || message.contains("month"))) {
                return "Invalid month";
            }
            
            // Check for year zero
            if (parsedString != null && parsedString.contains("0000")) {
                return "YearInEra is 0";
            }
            
            // Check for invalid date values (like Feb 30)
            if (message != null && (message.contains("Invalid date") || message.contains("Invalid value"))) {
                return "Datevalue error";
            }
            
            // Default error for other parsing failures
            return "Datevalue error";
        } catch (IllegalArgumentException e) {
            return "Bad Pic String";
        }
    }

    /**
     * Parses a COBOL standard date string in YYYY-MM-DD format (PIC X(10)).
     * 
     * <p>This format is used throughout CardDemo COBOL programs for dates like:
     * <ul>
     *   <li>ACCT-OPEN-DATE in CVACT01Y.cpy</li>
     *   <li>CARD-EXPIRATION-DATE in CVACT02Y.cpy</li>
     *   <li>TRAN-ORIG-TS-DATE in CVTRA05Y.cpy</li>
     * </ul>
     * 
     * @param dateStr the date string in YYYY-MM-DD format (e.g., "2024-01-15")
     * @return LocalDate object representing the parsed date
     * @throws DateTimeParseException if the date string cannot be parsed
     */
    public static LocalDate parseCobolDate(String dateStr) {
        if (dateStr == null || dateStr.trim().isEmpty()) {
            throw new DateTimeParseException("Date string is null or empty", "", 0);
        }
        return LocalDate.parse(dateStr.trim(), COBOL_DATE_FORMATTER);
    }

    /**
     * Parses a numeric COBOL date string in YYYYMMDD format (PIC 9(08) from WS-CURDATE-N).
     * 
     * <p>This format corresponds to WS-CURDATE-N in CSDAT01Y.cpy line 23, which is a
     * numeric representation of the date without separators. Used in COBOL batch programs
     * for compact date storage and efficient date comparisons.
     * 
     * @param dateStr the date string in YYYYMMDD format (e.g., "20240115")
     * @return LocalDate object representing the parsed date
     * @throws DateTimeParseException if the date string cannot be parsed
     */
    public static LocalDate parseCobolDateNumeric(String dateStr) {
        if (dateStr == null || dateStr.trim().isEmpty()) {
            throw new DateTimeParseException("Date string is null or empty", "", 0);
        }
        
        String trimmed = dateStr.trim();
        if (!trimmed.matches("\\d{8}")) {
            throw new DateTimeParseException("Date string must be 8 numeric digits", trimmed, 0);
        }
        
        return LocalDate.parse(trimmed, COBOL_DATE_NUMERIC_FORMATTER);
    }

    /**
     * Formats a LocalDate to COBOL standard date string format YYYY-MM-DD (PIC X(10)).
     * 
     * <p>This is the inverse of parseCobolDate() and produces output compatible with
     * COBOL date fields throughout CardDemo programs.
     * 
     * @param date the LocalDate to format
     * @return date string in YYYY-MM-DD format (e.g., "2024-01-15")
     * @throws NullPointerException if date is null
     */
    public static String formatToCobolDate(LocalDate date) {
        if (date == null) {
            throw new NullPointerException("Date cannot be null");
        }
        return date.format(COBOL_DATE_FORMATTER);
    }

    /**
     * Formats a LocalDateTime to COBOL timestamp format YYYY-MM-DD HH:MM:SS.SSSSSS.
     * 
     * <p>This format corresponds to WS-TIMESTAMP structure in CSDAT01Y.cpy (lines 42-55):
     * <pre>
     * 05 WS-TIMESTAMP.
     *   10  WS-TIMESTAMP-DT-YYYY      PIC 9(04).
     *   10  FILLER                    PIC X(01) VALUE '-'.
     *   10  WS-TIMESTAMP-DT-MM        PIC 9(02).
     *   10  FILLER                    PIC X(01) VALUE '-'.
     *   10  WS-TIMESTAMP-DT-DD        PIC 9(02).
     *   10  FILLER                    PIC X(01) VALUE ' '.
     *   10  WS-TIMESTAMP-TM-HH        PIC 9(02).
     *   10  FILLER                    PIC X(01) VALUE ':'.
     *   10  WS-TIMESTAMP-TM-MM        PIC 9(02).
     *   10  FILLER                    PIC X(01) VALUE ':'.
     *   10  WS-TIMESTAMP-TM-SS        PIC 9(02).
     *   10  FILLER                    PIC X(01) VALUE '.'.
     *   10  WS-TIMESTAMP-TM-MS6       PIC 9(06).
     * </pre>
     * 
     * @param dateTime the LocalDateTime to format
     * @return timestamp string in YYYY-MM-DD HH:MM:SS.SSSSSS format
     * @throws NullPointerException if dateTime is null
     */
    public static String formatToCobolDateTime(LocalDateTime dateTime) {
        if (dateTime == null) {
            throw new NullPointerException("DateTime cannot be null");
        }
        return dateTime.format(COBOL_DATETIME_FORMATTER);
    }

    /**
     * Adds the specified number of days to a date, preserving COBOL date arithmetic semantics.
     * 
     * <p>This operation is equivalent to COBOL date arithmetic using Lillian dates where
     * adding to a date simply increments the day count. Handles month and year rollovers
     * automatically, including leap year calculations.
     * 
     * @param date the base date
     * @param days the number of days to add (can be negative for subtraction)
     * @return new LocalDate with days added
     * @throws NullPointerException if date is null
     */
    public static LocalDate addDays(LocalDate date, int days) {
        if (date == null) {
            throw new NullPointerException("Date cannot be null");
        }
        return date.plusDays(days);
    }

    /**
     * Adds the specified number of months to a date, preserving COBOL date arithmetic semantics.
     * 
     * <p>Handles end-of-month scenarios intelligently: if the day-of-month is greater than
     * the last day of the target month, it adjusts to the last valid day. For example,
     * adding 1 month to January 31 results in February 28 (or 29 in leap years).
     * 
     * @param date the base date
     * @param months the number of months to add (can be negative for subtraction)
     * @return new LocalDate with months added
     * @throws NullPointerException if date is null
     */
    public static LocalDate addMonths(LocalDate date, int months) {
        if (date == null) {
            throw new NullPointerException("Date cannot be null");
        }
        return date.plusMonths(months);
    }

    /**
     * Adds the specified number of years to a date, preserving COBOL date arithmetic semantics.
     * 
     * <p>Handles leap year scenarios: adding years to February 29 results in February 28
     * in non-leap years, maintaining consistency with COBOL date behavior.
     * 
     * @param date the base date
     * @param years the number of years to add (can be negative for subtraction)
     * @return new LocalDate with years added
     * @throws NullPointerException if date is null
     */
    public static LocalDate addYears(LocalDate date, int years) {
        if (date == null) {
            throw new NullPointerException("Date cannot be null");
        }
        return date.plusYears(years);
    }

    /**
     * Subtracts the specified number of days from a date.
     * 
     * <p>This is a convenience method equivalent to addDays(date, -days).
     * 
     * @param date the base date
     * @param days the number of days to subtract
     * @return new LocalDate with days subtracted
     * @throws NullPointerException if date is null
     */
    public static LocalDate subtractDays(LocalDate date, int days) {
        return addDays(date, -days);
    }

    /**
     * Subtracts the specified number of months from a date.
     * 
     * <p>This is a convenience method equivalent to addMonths(date, -months).
     * 
     * @param date the base date
     * @param months the number of months to subtract
     * @return new LocalDate with months subtracted
     * @throws NullPointerException if date is null
     */
    public static LocalDate subtractMonths(LocalDate date, int months) {
        return addMonths(date, -months);
    }

    /**
     * Subtracts the specified number of years from a date.
     * 
     * <p>This is a convenience method equivalent to addYears(date, -years).
     * 
     * @param date the base date
     * @param years the number of years to subtract
     * @return new LocalDate with years subtracted
     * @throws NullPointerException if date is null
     */
    public static LocalDate subtractYears(LocalDate date, int years) {
        return addYears(date, -years);
    }

    /**
     * Determines if a given date is a business day (Monday through Friday, excluding weekends).
     * 
     * <p>This method supports COBOL batch processing logic where certain operations only
     * execute on business days. Uses DayOfWeek enum to check for SATURDAY and SUNDAY,
     * which corresponds to COBOL day-of-week checks (typically day 6 and 7, or 0 and 1
     * depending on the COBOL intrinsic function used).
     * 
     * <p><b>Note:</b> This method does NOT account for holidays. Holiday calendars should
     * be implemented separately if needed, similar to how COBOL programs would reference
     * a holiday table file.
     * 
     * @param date the date to check
     * @return true if the date is Monday-Friday, false if Saturday or Sunday
     * @throws NullPointerException if date is null
     */
    public static boolean isBusinessDay(LocalDate date) {
        if (date == null) {
            throw new NullPointerException("Date cannot be null");
        }
        
        DayOfWeek dayOfWeek = date.getDayOfWeek();
        return dayOfWeek != DayOfWeek.SATURDAY && dayOfWeek != DayOfWeek.SUNDAY;
    }

    /**
     * Returns the current system date.
     * 
     * <p>Equivalent to COBOL ACCEPT statements that populate WS-CURDATE in CSDAT01Y.cpy:
     * <pre>
     * ACCEPT WS-CURDATE FROM DATE YYYYMMDD
     * </pre>
     * 
     * @return current LocalDate from system clock
     */
    public static LocalDate getCurrentDate() {
        return LocalDate.now();
    }

    /**
     * Returns the current system date and time.
     * 
     * <p>Equivalent to COBOL ACCEPT statements that populate WS-DATE-TIME in CSDAT01Y.cpy:
     * <pre>
     * ACCEPT WS-DATE-TIME FROM DATE YYYYMMDD
     * ACCEPT WS-CURTIME FROM TIME
     * </pre>
     * 
     * @return current LocalDateTime from system clock
     */
    public static LocalDateTime getCurrentDateTime() {
        return LocalDateTime.now();
    }

    /**
     * Checks if the first date is before the second date.
     * 
     * <p>Equivalent to COBOL date comparison: IF DATE1 &lt; DATE2
     * or using Lillian dates: IF LILLIAN-DATE-1 &lt; LILLIAN-DATE-2
     * 
     * @param date1 the first date
     * @param date2 the second date
     * @return true if date1 is chronologically before date2
     * @throws NullPointerException if either date is null
     */
    public static boolean isBefore(LocalDate date1, LocalDate date2) {
        if (date1 == null || date2 == null) {
            throw new NullPointerException("Dates cannot be null");
        }
        return date1.isBefore(date2);
    }

    /**
     * Checks if the first date is after the second date.
     * 
     * <p>Equivalent to COBOL date comparison: IF DATE1 &gt; DATE2
     * or using Lillian dates: IF LILLIAN-DATE-1 &gt; LILLIAN-DATE-2
     * 
     * @param date1 the first date
     * @param date2 the second date
     * @return true if date1 is chronologically after date2
     * @throws NullPointerException if either date is null
     */
    public static boolean isAfter(LocalDate date1, LocalDate date2) {
        if (date1 == null || date2 == null) {
            throw new NullPointerException("Dates cannot be null");
        }
        return date1.isAfter(date2);
    }

    /**
     * Checks if two dates are equal.
     * 
     * <p>Equivalent to COBOL date comparison: IF DATE1 = DATE2
     * or using Lillian dates: IF LILLIAN-DATE-1 = LILLIAN-DATE-2
     * 
     * @param date1 the first date
     * @param date2 the second date
     * @return true if dates are equal
     * @throws NullPointerException if either date is null
     */
    public static boolean isEqual(LocalDate date1, LocalDate date2) {
        if (date1 == null || date2 == null) {
            throw new NullPointerException("Dates cannot be null");
        }
        return date1.isEqual(date2);
    }

    /**
     * Calculates the number of days between two dates.
     * 
     * <p>This method replicates COBOL date arithmetic using Lillian dates where the
     * difference between two dates is computed by subtracting their Lillian date values.
     * Uses ChronoUnit.DAYS.between() which provides the same semantics as COBOL's
     * date difference calculations.
     * 
     * <p>The result is positive if date2 is after date1, negative if date2 is before date1,
     * and zero if the dates are equal.
     * 
     * <p><b>COBOL Equivalent:</b>
     * <pre>
     * COMPUTE DAYS-DIFF = LILLIAN-DATE-2 - LILLIAN-DATE-1
     * </pre>
     * 
     * @param date1 the start date
     * @param date2 the end date
     * @return the number of days between the dates (can be negative)
     * @throws NullPointerException if either date is null
     */
    public static long daysBetween(LocalDate date1, LocalDate date2) {
        if (date1 == null || date2 == null) {
            throw new NullPointerException("Dates cannot be null");
        }
        return ChronoUnit.DAYS.between(date1, date2);
    }
}
