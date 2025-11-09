/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.carddemo.util;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;

/**
 * Date and time utility class providing thread-safe stateless helper methods for date/time conversion,
 * formatting, and arithmetic operations critical for preserving COBOL date handling semantics.
 * 
 * <p>This class transforms COBOL date handling logic from:</p>
 * <ul>
 *   <li>CSUTLDTC.cbl (88 lines) - CEEDAYS/CEEDATE LE service calls with VSTRING descriptors</li>
 *   <li>CSUTLDWY.cpy - Date work area data structures with validation flags</li>
 *   <li>CSUTLDPY.cpy (375 lines) - Comprehensive date validation procedures</li>
 * </ul>
 * 
 * <p><strong>Lillian Date Format:</strong></p>
 * <p>Lillian date is defined as integer days since October 15, 1582 (the start of the Gregorian calendar).
 * This matches the COBOL CEEDAYS and CEEDATE Language Environment services used in the mainframe application.</p>
 * 
 * <p><strong>Validation Logic:</strong></p>
 * <p>All validation methods preserve exact COBOL semantics including:</p>
 * <ul>
 *   <li>Century validation: Only 19xx and 20xx allowed (Y2K constraints from CSUTLDPY line 70-71)</li>
 *   <li>Month validation: 1-12 range (CSUTLDWY line 19-20)</li>
 *   <li>Day validation: 1-31 with month-specific constraints (CSUTLDWY line 28-34)</li>
 *   <li>Leap year calculation: Divisible by 400 for century years, divisible by 4 otherwise (CSUTLDPY line 245-272)</li>
 *   <li>Date of birth validation: Must be in the past (CSUTLDPY line 341-372)</li>
 * </ul>
 * 
 * <p><strong>Thread Safety:</strong></p>
 * <p>All methods are static and thread-safe, using immutable java.time.LocalDate objects.</p>
 * 
 * @version 1.0
 * @since 1.0
 */
public final class DateTimeUtils {

    /**
     * Lillian epoch start date: October 15, 1582 (start of Gregorian calendar).
     * This constant is used for all Lillian date conversions matching CEEDAYS behavior.
     */
    private static final LocalDate LILLIAN_EPOCH = LocalDate.of(1582, 10, 15);

    /**
     * Date formatter for CCYYMMDD format (8-character date: Century+Year+Month+Day).
     * Pattern: yyyyMMdd
     * Example: 20240315 represents March 15, 2024
     */
    private static final DateTimeFormatter CCYYMMDD_FORMATTER = DateTimeFormatter.ofPattern("yyyyMMdd");

    /**
     * Date formatter for YYYYMMDD format (8-character date: Year+Month+Day).
     * Pattern: yyyyMMdd
     * Example: 20240315 represents March 15, 2024
     */
    private static final DateTimeFormatter YYYYMMDD_FORMATTER = DateTimeFormatter.ofPattern("yyyyMMdd");

    /**
     * Date formatter for MM/DD/YYYY format with slashes.
     * Pattern: MM/dd/yyyy
     * Example: 03/15/2024 represents March 15, 2024
     */
    private static final DateTimeFormatter MMDDYYYY_FORMATTER = DateTimeFormatter.ofPattern("MM/dd/yyyy");

    /**
     * Private constructor to prevent instantiation of utility class.
     * All methods are static and this class should never be instantiated.
     */
    private DateTimeUtils() {
        throw new UnsupportedOperationException("Utility class cannot be instantiated");
    }

    /**
     * Converts a Lillian date integer to LocalDate.
     * 
     * <p>Lillian date is defined as the number of days since October 15, 1582 (the start of the
     * Gregorian calendar). This method replicates the CEEDATE Language Environment service behavior
     * from CSUTLDTC.cbl lines 116-120.</p>
     * 
     * <p><strong>COBOL Equivalent:</strong></p>
     * <pre>
     * CALL "CEEDATE" USING LILLIAN-DATE, DATE-FORMAT, OUTPUT-DATE, FEEDBACK-CODE
     * </pre>
     * 
     * <p><strong>Examples:</strong></p>
     * <ul>
     *   <li>Lillian 0 → October 15, 1582</li>
     *   <li>Lillian 1 → October 16, 1582</li>
     *   <li>Lillian 161428 → March 15, 2024</li>
     * </ul>
     * 
     * @param lillianDays the number of days since October 15, 1582
     * @return LocalDate representing the date
     * @throws IllegalArgumentException if lillianDays is negative
     */
    public static LocalDate fromLillianDate(int lillianDays) {
        if (lillianDays < 0) {
            throw new IllegalArgumentException("Lillian date cannot be negative: " + lillianDays);
        }
        return LILLIAN_EPOCH.plusDays(lillianDays);
    }

    /**
     * Converts a LocalDate to Lillian date integer.
     * 
     * <p>Returns the number of days since October 15, 1582. This method replicates the CEEDAYS
     * Language Environment service behavior from CSUTLDTC.cbl lines 116-120.</p>
     * 
     * <p><strong>COBOL Equivalent:</strong></p>
     * <pre>
     * CALL "CEEDAYS" USING DATE-STRING, DATE-FORMAT, OUTPUT-LILLIAN, FEEDBACK-CODE
     * </pre>
     * 
     * <p><strong>Examples:</strong></p>
     * <ul>
     *   <li>October 15, 1582 → Lillian 0</li>
     *   <li>October 16, 1582 → Lillian 1</li>
     *   <li>March 15, 2024 → Lillian 161428</li>
     * </ul>
     * 
     * @param date the date to convert
     * @return the Lillian date as integer days since epoch
     * @throws IllegalArgumentException if date is null or before October 15, 1582
     */
    public static int toLillianDate(LocalDate date) {
        if (date == null) {
            throw new IllegalArgumentException("Date cannot be null");
        }
        if (date.isBefore(LILLIAN_EPOCH)) {
            throw new IllegalArgumentException(
                "Date cannot be before Lillian epoch (October 15, 1582): " + date);
        }
        return (int) ChronoUnit.DAYS.between(LILLIAN_EPOCH, date);
    }

    /**
     * Parses a date string in CCYYMMDD format to LocalDate.
     * 
     * <p>CCYYMMDD format is an 8-character string: Century(2) + Year(2) + Month(2) + Day(2).
     * This matches the format used in BMS screen fields and VSAM record layouts.</p>
     * 
     * <p><strong>COBOL Equivalent:</strong></p>
     * <pre>
     * MOVE WS-EDIT-DATE-CCYYMMDD TO date field (CSUTLDWY.cpy line 4-36)
     * PERFORM EDIT-DATE-CCYYMMDD (CSUTLDPY.cpy line 18)
     * </pre>
     * 
     * <p><strong>Validation performed:</strong></p>
     * <ul>
     *   <li>String must be exactly 8 characters</li>
     *   <li>All characters must be numeric digits</li>
     *   <li>Century must be 19 or 20 (Y2K constraint)</li>
     *   <li>Month must be 1-12</li>
     *   <li>Day must be valid for the given month and year (including leap year check)</li>
     * </ul>
     * 
     * @param dateStr the date string in CCYYMMDD format (e.g., "20240315" for March 15, 2024)
     * @return LocalDate parsed from the string
     * @throws DateTimeParseException if the string cannot be parsed or is invalid
     * @throws IllegalArgumentException if dateStr is null or not 8 characters
     */
    public static LocalDate parseCCYYMMDD(String dateStr) {
        if (dateStr == null) {
            throw new IllegalArgumentException("Date string cannot be null");
        }
        if (dateStr.trim().isEmpty()) {
            throw new IllegalArgumentException("Date string cannot be blank");
        }
        if (dateStr.length() != 8) {
            throw new IllegalArgumentException(
                "Date string must be 8 characters in CCYYMMDD format, got: " + dateStr.length());
        }
        
        // Validate all characters are numeric (CSUTLDPY.cpy line 48)
        if (!dateStr.matches("\\d{8}")) {
            throw new DateTimeParseException(
                "Date string must contain only numeric digits", dateStr, 0);
        }

        try {
            LocalDate date = LocalDate.parse(dateStr, CCYYMMDD_FORMATTER);
            
            // Extract year, month, day for validation
            int year = date.getYear();
            int month = date.getMonthValue();
            int day = date.getDayOfMonth();
            
            // Validate century (19 or 20 only) - CSUTLDPY.cpy lines 70-84
            int century = year / 100;
            if (century != 19 && century != 20) {
                throw new DateTimeParseException(
                    "Century must be 19 or 20, got: " + century, dateStr, 0);
            }
            
            // Additional validation through validateDate method
            validateDate(year, month, day);
            
            return date;
        } catch (DateTimeParseException e) {
            throw new DateTimeParseException(
                "Invalid date format or value in CCYYMMDD: " + e.getMessage(), dateStr, 0);
        }
    }

    /**
     * Formats a LocalDate to CCYYMMDD string format.
     * 
     * <p>Produces an 8-character string: Century(2) + Year(2) + Month(2) + Day(2).
     * This matches the format used in BMS screen fields and VSAM record layouts.</p>
     * 
     * <p><strong>Example:</strong></p>
     * <pre>
     * March 15, 2024 → "20240315"
     * December 31, 1999 → "19991231"
     * </pre>
     * 
     * @param date the date to format
     * @return formatted date string in CCYYMMDD format
     * @throws IllegalArgumentException if date is null
     */
    public static String formatCCYYMMDD(LocalDate date) {
        if (date == null) {
            throw new IllegalArgumentException("Date cannot be null");
        }
        return date.format(CCYYMMDD_FORMATTER);
    }

    /**
     * Parses a date string in YYYYMMDD format to LocalDate.
     * 
     * <p>YYYYMMDD format is an 8-character string: Year(4) + Month(2) + Day(2).
     * This is functionally equivalent to CCYYMMDD but uses the full pattern name.</p>
     * 
     * @param dateStr the date string in YYYYMMDD format (e.g., "20240315" for March 15, 2024)
     * @return LocalDate parsed from the string
     * @throws DateTimeParseException if the string cannot be parsed
     * @throws IllegalArgumentException if dateStr is null or not 8 characters
     */
    public static LocalDate parseYYYYMMDD(String dateStr) {
        if (dateStr == null) {
            throw new IllegalArgumentException("Date string cannot be null");
        }
        if (dateStr.trim().isEmpty()) {
            throw new IllegalArgumentException("Date string cannot be blank");
        }
        if (dateStr.length() != 8) {
            throw new IllegalArgumentException(
                "Date string must be 8 characters in YYYYMMDD format, got: " + dateStr.length());
        }
        
        try {
            return LocalDate.parse(dateStr, YYYYMMDD_FORMATTER);
        } catch (DateTimeParseException e) {
            throw new DateTimeParseException(
                "Invalid date format in YYYYMMDD: " + e.getMessage(), dateStr, 0);
        }
    }

    /**
     * Formats a LocalDate to YYYYMMDD string format.
     * 
     * <p>Produces an 8-character string: Year(4) + Month(2) + Day(2).</p>
     * 
     * @param date the date to format
     * @return formatted date string in YYYYMMDD format
     * @throws IllegalArgumentException if date is null
     */
    public static String formatYYYYMMDD(LocalDate date) {
        if (date == null) {
            throw new IllegalArgumentException("Date cannot be null");
        }
        return date.format(YYYYMMDD_FORMATTER);
    }

    /**
     * Formats a LocalDate to MM/DD/YYYY string format with slashes.
     * 
     * <p>Produces a formatted string with slashes: Month(2)/Day(2)/Year(4).
     * This format is commonly used for display purposes in UI components.</p>
     * 
     * <p><strong>Example:</strong></p>
     * <pre>
     * March 15, 2024 → "03/15/2024"
     * December 31, 1999 → "12/31/1999"
     * </pre>
     * 
     * @param date the date to format
     * @return formatted date string in MM/DD/YYYY format
     * @throws IllegalArgumentException if date is null
     */
    public static String formatMMDDYYYY(LocalDate date) {
        if (date == null) {
            throw new IllegalArgumentException("Date cannot be null");
        }
        return date.format(MMDDYYYY_FORMATTER);
    }

    /**
     * Validates a date specified by year, month, and day components.
     * 
     * <p>This method replicates comprehensive COBOL validation logic from CSUTLDPY.cpy including:</p>
     * <ul>
     *   <li>EDIT-YEAR-CCYY (lines 25-88): Year validation with century check</li>
     *   <li>EDIT-MONTH (lines 91-146): Month range validation (1-12)</li>
     *   <li>EDIT-DAY (lines 150-206): Day range validation (1-31)</li>
     *   <li>EDIT-DAY-MONTH-YEAR (lines 209-282): Complex validation including month-specific day limits and leap year</li>
     * </ul>
     * 
     * <p><strong>Validation Rules:</strong></p>
     * <ul>
     *   <li>Century must be 19 or 20 (years 1900-2099 only)</li>
     *   <li>Month must be 1-12</li>
     *   <li>Day must be 1-31</li>
     *   <li>April, June, September, November: maximum 30 days</li>
     *   <li>February: maximum 28 days (29 in leap years)</li>
     *   <li>Leap year calculation: divisible by 400 for century years (e.g., 2000), divisible by 4 for others (e.g., 2024)</li>
     * </ul>
     * 
     * @param year the year (1900-2099)
     * @param month the month (1-12)
     * @param day the day of month (1-31)
     * @throws IllegalArgumentException if any component is invalid
     */
    public static void validateDate(int year, int month, int day) {
        // Validate century (CSUTLDPY.cpy lines 70-84)
        int century = year / 100;
        if (century != 19 && century != 20) {
            throw new IllegalArgumentException(
                "Century must be 19 or 20 (years 1900-2099 only), got: " + century);
        }

        // Validate month range (CSUTLDPY.cpy lines 111-124)
        if (month < 1 || month > 12) {
            throw new IllegalArgumentException(
                "Month must be between 1 and 12, got: " + month);
        }

        // Validate day range (CSUTLDPY.cpy lines 187-199)
        if (day < 1 || day > 31) {
            throw new IllegalArgumentException(
                "Day must be between 1 and 31, got: " + day);
        }

        // Check for months with 30 days maximum (CSUTLDPY.cpy lines 213-226)
        // April (4), June (6), September (9), November (11)
        if ((month == 4 || month == 6 || month == 9 || month == 11) && day > 30) {
            throw new IllegalArgumentException(
                "Month " + month + " cannot have more than 30 days, got day: " + day);
        }

        // Check for February (CSUTLDPY.cpy lines 228-272)
        if (month == 2) {
            if (day > 29) {
                throw new IllegalArgumentException(
                    "February cannot have more than 29 days, got day: " + day);
            }
            
            // Check for February 29 in non-leap years (CSUTLDPY.cpy lines 243-272)
            if (day == 29 && !isLeapYear(year)) {
                throw new IllegalArgumentException(
                    year + " is not a leap year, February cannot have 29 days");
            }
        }
    }

    /**
     * Determines if a given year is a leap year.
     * 
     * <p>This method replicates the exact COBOL leap year calculation logic from CSUTLDPY.cpy lines 245-272:</p>
     * <pre>
     * IF WS-EDIT-DATE-YY-N = 0
     *    MOVE 400 TO WS-DIV-BY     (century years must be divisible by 400)
     * ELSE
     *    MOVE 4 TO WS-DIV-BY       (non-century years must be divisible by 4)
     * END-IF
     * </pre>
     * 
     * <p><strong>Leap Year Rules:</strong></p>
     * <ul>
     *   <li>Century years (ending in 00): divisible by 400 → leap year (e.g., 2000 is leap, 1900 is not)</li>
     *   <li>Non-century years: divisible by 4 → leap year (e.g., 2024 is leap, 2023 is not)</li>
     * </ul>
     * 
     * @param year the year to check
     * @return true if the year is a leap year, false otherwise
     */
    public static boolean isLeapYear(int year) {
        // Check if century year (ends in 00)
        if (year % 100 == 0) {
            // Century years must be divisible by 400 (CSUTLDPY.cpy line 246)
            return year % 400 == 0;
        } else {
            // Non-century years must be divisible by 4 (CSUTLDPY.cpy line 248)
            return year % 4 == 0;
        }
    }

    /**
     * Validates that a date of birth is in the past.
     * 
     * <p>This method replicates the COBOL date of birth validation from CSUTLDPY.cpy lines 341-372.
     * The original COBOL comment states: "At the time of writing this program, time travel was not
     * possible. Date of birth in the future is not acceptable."</p>
     * 
     * <p><strong>COBOL Equivalent:</strong></p>
     * <pre>
     * COMPUTE WS-EDIT-DATE-BINARY = FUNCTION INTEGER-OF-DATE(WS-EDIT-DATE-CCYYMMDD-N)
     * COMPUTE WS-CURRENT-DATE-BINARY = FUNCTION INTEGER-OF-DATE(WS-CURRENT-DATE-YYYYMMDD-N)
     * IF WS-CURRENT-DATE-BINARY > WS-EDIT-DATE-BINARY
     *    CONTINUE (valid)
     * ELSE
     *    SET INPUT-ERROR TO TRUE (date is in the future)
     * </pre>
     * 
     * @param birthDate the date of birth to validate
     * @throws IllegalArgumentException if birthDate is null or in the future
     */
    public static void validateDateOfBirth(LocalDate birthDate) {
        if (birthDate == null) {
            throw new IllegalArgumentException("Birth date cannot be null");
        }

        LocalDate today = LocalDate.now();

        // Date of birth must be in the past (CSUTLDPY.cpy lines 350-367)
        if (birthDate.isAfter(today)) {
            throw new IllegalArgumentException(
                "Date of birth cannot be in the future: " + birthDate);
        }
        
        // Also reject today's date as birth date (must be strictly in the past)
        if (birthDate.equals(today)) {
            throw new IllegalArgumentException(
                "Date of birth cannot be today's date: " + birthDate);
        }
    }

    /**
     * Adds a specified number of days to a date.
     * 
     * <p>This method supports date arithmetic operations used in batch processing programs
     * for calculating date ranges, expiration dates, and aging analysis.</p>
     * 
     * <p><strong>Thread Safety:</strong> This method is thread-safe as LocalDate is immutable.</p>
     * 
     * @param date the starting date
     * @param days the number of days to add (can be negative to subtract)
     * @return a new LocalDate with the days added
     * @throws IllegalArgumentException if date is null
     */
    public static LocalDate addDays(LocalDate date, int days) {
        if (date == null) {
            throw new IllegalArgumentException("Date cannot be null");
        }
        return date.plusDays(days);
    }

    /**
     * Subtracts a specified number of days from a date.
     * 
     * <p>This method supports date arithmetic operations used in batch processing programs
     * for calculating date ranges, expiration dates, and aging analysis.</p>
     * 
     * <p><strong>Thread Safety:</strong> This method is thread-safe as LocalDate is immutable.</p>
     * 
     * @param date the starting date
     * @param days the number of days to subtract (can be negative to add)
     * @return a new LocalDate with the days subtracted
     * @throws IllegalArgumentException if date is null
     */
    public static LocalDate subtractDays(LocalDate date, int days) {
        if (date == null) {
            throw new IllegalArgumentException("Date cannot be null");
        }
        return date.minusDays(days);
    }
}
