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

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;

/**
 * Core date and time utility class providing common date field operations, current date/time retrieval,
 * and date arithmetic functions.
 * 
 * <p>This class transforms COBOL CSDAT01Y WS-DATE-TIME common date fields copybook into Java utility
 * methods. It provides equivalent functionality for COBOL date operations including:</p>
 * <ul>
 *   <li>Current date/time retrieval (equivalent to COBOL ACCEPT FROM DATE/TIME statements)</li>
 *   <li>Date formatting in COBOL-compatible formats (CCYYMMDD, MM/DD/YY, ISO timestamp)</li>
 *   <li>Date component extraction (year, month, day, hour, minute, second)</li>
 *   <li>Date arithmetic (add/subtract days, months, years)</li>
 *   <li>Date comparison operations (before, after, equal)</li>
 *   <li>Lillian date format conversion (COBOL CEEDAYS compatibility)</li>
 * </ul>
 * 
 * <p>All date operations preserve COBOL numeric formatting including leading zero padding for all
 * date components per Section 0.9 requirements. Date arithmetic handles month-end scenarios correctly
 * using Java 8+ date-time API capabilities.</p>
 * 
 * <p>This utility class uses the application's default timezone without implicit conversions,
 * maintaining exact equivalence with COBOL ACCEPT FROM DATE/TIME behavior.</p>
 * 
 * @see DateConverter for format conversion methods
 * @see java.time.LocalDate
 * @see java.time.LocalTime
 * @see java.time.LocalDateTime
 */
public class DateUtils {

    /**
     * Lillian date epoch: October 15, 1582 (Gregorian calendar adoption).
     * This is day 1 in the Lillian date system used by COBOL CEEDAYS function.
     * COBOL date intrinsic functions use Lillian dates as integer day counts.
     */
    private static final LocalDate LILLIAN_EPOCH = LocalDate.of(1582, 10, 15);

    /**
     * Private constructor to prevent instantiation of utility class.
     * All methods are static and should be accessed via class name.
     */
    private DateUtils() {
        throw new UnsupportedOperationException("DateUtils is a utility class and cannot be instantiated");
    }

    /**
     * Gets the current date in the application's default timezone.
     * 
     * <p>This method is equivalent to COBOL statement:
     * <code>ACCEPT WS-CURDATE FROM DATE</code></p>
     * 
     * <p>Returns the current system date without time components, using the JVM's default timezone.
     * The returned LocalDate can be formatted using {@link #formatCCYYMMDD(LocalDate)} or
     * {@link #formatMMDDYY(LocalDate)} to match COBOL WS-CURDATE-N or WS-CURDATE-MM-DD-YY structures.</p>
     * 
     * @return LocalDate representing the current date
     */
    public static LocalDate getCurrentDate() {
        return LocalDate.now();
    }

    /**
     * Gets the current time in the application's default timezone.
     * 
     * <p>This method is equivalent to COBOL statement:
     * <code>ACCEPT WS-CURTIME FROM TIME</code></p>
     * 
     * <p>Returns the current system time without date components, using the JVM's default timezone.
     * The returned LocalTime provides hour, minute, second, and nanosecond precision, matching
     * COBOL WS-CURTIME structure (hours, minutes, seconds, centiseconds) with enhanced precision.</p>
     * 
     * @return LocalTime representing the current time
     */
    public static LocalTime getCurrentTime() {
        return LocalTime.now();
    }

    /**
     * Gets the current date and time combined in the application's default timezone.
     * 
     * <p>This method is equivalent to combining COBOL statements:
     * <code>ACCEPT WS-CURDATE FROM DATE</code> and <code>ACCEPT WS-CURTIME FROM TIME</code></p>
     * 
     * <p>Returns the current system timestamp with date and time components, using the JVM's default
     * timezone. The returned LocalDateTime can be formatted using {@link #formatTimestampISO(LocalDateTime)}
     * to match COBOL WS-TIMESTAMP structure (YYYY-MM-DD HH:MM:SS.microseconds).</p>
     * 
     * @return LocalDateTime representing the current date and time
     */
    public static LocalDateTime getCurrentTimestamp() {
        return LocalDateTime.now();
    }

    /**
     * Formats a LocalDate to CCYYMMDD format (8-digit numeric: YYYYMMDD).
     * 
     * <p>This method converts Java LocalDate to COBOL WS-CURDATE-N PIC 9(08) format from
     * CSDAT01Y.cpy line 23. Leading zeros are preserved for all components matching COBOL
     * numeric field formatting.</p>
     * 
     * <p>Example: LocalDate.of(2023, 12, 25) → "20231225"</p>
     * 
     * @param date the LocalDate to format
     * @return String in CCYYMMDD format with leading zeros preserved
     * @throws IllegalArgumentException if date is null
     */
    public static String formatCCYYMMDD(LocalDate date) {
        return DateConverter.formatCCYYMMDD(date);
    }

    /**
     * Formats a LocalDate to MM/DD/YY format.
     * 
     * <p>This method converts Java LocalDate to COBOL WS-CURDATE-MM-DD-YY format from
     * CSDAT01Y.cpy lines 30-35. The output includes slash separators and uses 2-digit year
     * representation (last 2 digits of the year).</p>
     * 
     * <p>Leading zeros are preserved for month and day components per COBOL PIC 9(02) specification.</p>
     * 
     * <p>Example: LocalDate.of(2023, 12, 25) → "12/25/23"</p>
     * 
     * @param date the LocalDate to format
     * @return String in MM/DD/YY format with leading zeros preserved
     * @throws IllegalArgumentException if date is null
     */
    public static String formatMMDDYY(LocalDate date) {
        return DateConverter.formatMMDDYY(date);
    }

    /**
     * Formats a LocalDateTime to ISO timestamp format with microsecond precision.
     * 
     * <p>This method converts Java LocalDateTime to COBOL WS-TIMESTAMP format from
     * CSDAT01Y.cpy lines 42-55. The output format is: YYYY-MM-DD HH:MM:SS.microseconds
     * where microseconds is a 6-digit field with leading zeros preserved.</p>
     * 
     * <p>Example: LocalDateTime.of(2023, 12, 25, 14, 30, 45, 123456000) → "2023-12-25 14:30:45.123456"</p>
     * 
     * @param dateTime the LocalDateTime to format
     * @return String in ISO timestamp format with 6-digit microsecond precision
     * @throws IllegalArgumentException if dateTime is null
     */
    public static String formatTimestampISO(LocalDateTime dateTime) {
        return DateConverter.formatISOTimestamp(dateTime);
    }

    /**
     * Parses a date string attempting multiple COBOL-compatible formats.
     * 
     * <p>This method attempts to parse the input string using the following formats in order:</p>
     * <ol>
     *   <li>CCYYMMDD (8-digit numeric: YYYYMMDD) - WS-CURDATE-N format</li>
     *   <li>MM/DD/YY (with slashes) - WS-CURDATE-MM-DD-YY format</li>
     * </ol>
     * 
     * <p>The first successful parse result is returned. If all formats fail, an IllegalArgumentException
     * is thrown with details about the expected formats.</p>
     * 
     * @param dateString the date string to parse
     * @return LocalDate object representing the parsed date
     * @throws IllegalArgumentException if dateString is null, empty, or doesn't match any supported format
     */
    public static LocalDate parseDate(String dateString) {
        if (dateString == null || dateString.trim().isEmpty()) {
            throw new IllegalArgumentException("Date string cannot be null or empty");
        }

        // Try CCYYMMDD format first (8-digit numeric)
        if (dateString.matches("\\d{8}")) {
            try {
                return DateConverter.parseCCYYMMDD(dateString);
            } catch (IllegalArgumentException e) {
                // Fall through to try next format
            }
        }

        // Try MM/DD/YY format (with slashes)
        if (dateString.matches("\\d{2}/\\d{2}/\\d{2}")) {
            try {
                return DateConverter.parseMMDDYY(dateString);
            } catch (IllegalArgumentException e) {
                // Fall through to error
            }
        }

        // No format matched
        throw new IllegalArgumentException(
                String.format("Unable to parse date string: '%s'. Supported formats: CCYYMMDD (e.g., '20231225'), MM/DD/YY (e.g., '12/25/23')",
                        dateString));
    }

    /**
     * Parses a date string in CCYYMMDD format (8-digit numeric: YYYYMMDD) to LocalDate.
     * 
     * <p>This method converts COBOL WS-CURDATE-N PIC 9(08) format to Java LocalDate.
     * The input must be exactly 8 digits with leading zeros preserved.</p>
     * 
     * <p>Example: "20231225" → LocalDate.of(2023, 12, 25)</p>
     * 
     * @param dateString the date string in CCYYMMDD format (exactly 8 digits)
     * @return LocalDate object representing the parsed date
     * @throws IllegalArgumentException if dateString is null, empty, not 8 digits, or invalid date
     */
    public static LocalDate parseCCYYMMDD(String dateString) {
        return DateConverter.parseCCYYMMDD(dateString);
    }

    /**
     * Parses a date string in MM/DD/YY format to LocalDate.
     * 
     * <p>This method converts COBOL WS-CURDATE-MM-DD-YY format (lines 30-35 of CSDAT01Y.cpy)
     * to Java LocalDate. The input must be exactly in MM/DD/YY format with slash separators.</p>
     * 
     * <p>Two-digit year interpretation: years 00-99 are interpreted as 2000-2099.</p>
     * 
     * <p>Example: "12/25/23" → LocalDate.of(2023, 12, 25)</p>
     * 
     * @param dateString the date string in MM/DD/YY format (e.g., "12/25/23")
     * @return LocalDate object representing the parsed date
     * @throws IllegalArgumentException if dateString is null, empty, or has invalid format
     */
    public static LocalDate parseMMDDYY(String dateString) {
        return DateConverter.parseMMDDYY(dateString);
    }

    /**
     * Extracts the year component from a LocalDate.
     * 
     * <p>This method is equivalent to accessing COBOL field:
     * <code>WS-CURDATE-YEAR PIC 9(04)</code></p>
     * 
     * <p>Returns the 4-digit year value as an integer (e.g., 2023).</p>
     * 
     * @param date the LocalDate to extract year from
     * @return int representing the year (4 digits)
     * @throws IllegalArgumentException if date is null
     */
    public static int getYear(LocalDate date) {
        if (date == null) {
            throw new IllegalArgumentException("Date cannot be null");
        }
        return date.getYear();
    }

    /**
     * Extracts the month component from a LocalDate.
     * 
     * <p>This method is equivalent to accessing COBOL field:
     * <code>WS-CURDATE-MONTH PIC 9(02)</code></p>
     * 
     * <p>Returns the month value as an integer (1-12) where 1 is January and 12 is December.</p>
     * 
     * @param date the LocalDate to extract month from
     * @return int representing the month (1-12)
     * @throws IllegalArgumentException if date is null
     */
    public static int getMonth(LocalDate date) {
        if (date == null) {
            throw new IllegalArgumentException("Date cannot be null");
        }
        return date.getMonthValue();
    }

    /**
     * Extracts the day component from a LocalDate.
     * 
     * <p>This method is equivalent to accessing COBOL field:
     * <code>WS-CURDATE-DAY PIC 9(02)</code></p>
     * 
     * <p>Returns the day of month value as an integer (1-31).</p>
     * 
     * @param date the LocalDate to extract day from
     * @return int representing the day of month (1-31)
     * @throws IllegalArgumentException if date is null
     */
    public static int getDay(LocalDate date) {
        if (date == null) {
            throw new IllegalArgumentException("Date cannot be null");
        }
        return date.getDayOfMonth();
    }

    /**
     * Extracts the hour component from a LocalTime.
     * 
     * <p>This method is equivalent to accessing COBOL field:
     * <code>WS-CURTIME-HOURS PIC 9(02)</code></p>
     * 
     * <p>Returns the hour value as an integer (0-23) in 24-hour format.</p>
     * 
     * @param time the LocalTime to extract hour from
     * @return int representing the hour (0-23)
     * @throws IllegalArgumentException if time is null
     */
    public static int getHour(LocalTime time) {
        if (time == null) {
            throw new IllegalArgumentException("Time cannot be null");
        }
        return time.getHour();
    }

    /**
     * Extracts the minute component from a LocalTime.
     * 
     * <p>This method is equivalent to accessing COBOL field:
     * <code>WS-CURTIME-MINUTE PIC 9(02)</code></p>
     * 
     * <p>Returns the minute value as an integer (0-59).</p>
     * 
     * @param time the LocalTime to extract minute from
     * @return int representing the minute (0-59)
     * @throws IllegalArgumentException if time is null
     */
    public static int getMinute(LocalTime time) {
        if (time == null) {
            throw new IllegalArgumentException("Time cannot be null");
        }
        return time.getMinute();
    }

    /**
     * Extracts the second component from a LocalTime.
     * 
     * <p>This method is equivalent to accessing COBOL field:
     * <code>WS-CURTIME-SECOND PIC 9(02)</code></p>
     * 
     * <p>Returns the second value as an integer (0-59).</p>
     * 
     * @param time the LocalTime to extract second from
     * @return int representing the second (0-59)
     * @throws IllegalArgumentException if time is null
     */
    public static int getSecond(LocalTime time) {
        if (time == null) {
            throw new IllegalArgumentException("Time cannot be null");
        }
        return time.getSecond();
    }

    /**
     * Adds the specified number of days to a date.
     * 
     * <p>This method provides date arithmetic equivalent to COBOL date manipulation operations.
     * It correctly handles month-end scenarios, leap years, and all calendar edge cases using
     * Java 8+ date-time API.</p>
     * 
     * <p>Examples:</p>
     * <ul>
     *   <li>addDays(LocalDate.of(2023, 1, 31), 1) → LocalDate.of(2023, 2, 1)</li>
     *   <li>addDays(LocalDate.of(2024, 2, 28), 1) → LocalDate.of(2024, 2, 29) (leap year)</li>
     * </ul>
     * 
     * @param date the base date
     * @param days the number of days to add (can be negative for subtraction)
     * @return LocalDate representing the result of the addition
     * @throws IllegalArgumentException if date is null
     */
    public static LocalDate addDays(LocalDate date, int days) {
        if (date == null) {
            throw new IllegalArgumentException("Date cannot be null");
        }
        return date.plusDays(days);
    }

    /**
     * Subtracts the specified number of days from a date.
     * 
     * <p>This method provides date arithmetic equivalent to COBOL date manipulation operations.
     * It correctly handles month-end scenarios, leap years, and all calendar edge cases using
     * Java 8+ date-time API.</p>
     * 
     * <p>Example: subtractDays(LocalDate.of(2023, 3, 1), 1) → LocalDate.of(2023, 2, 28)</p>
     * 
     * @param date the base date
     * @param days the number of days to subtract (must be positive)
     * @return LocalDate representing the result of the subtraction
     * @throws IllegalArgumentException if date is null
     */
    public static LocalDate subtractDays(LocalDate date, int days) {
        if (date == null) {
            throw new IllegalArgumentException("Date cannot be null");
        }
        return date.minusDays(days);
    }

    /**
     * Adds the specified number of months to a date.
     * 
     * <p>This method provides date arithmetic equivalent to COBOL date manipulation operations.
     * It correctly handles month-end scenarios following Java date-time API rules:</p>
     * <ul>
     *   <li>If the resulting month has fewer days than the original day-of-month, the day is adjusted to the last valid day</li>
     *   <li>Example: addMonths(LocalDate.of(2023, 1, 31), 1) → LocalDate.of(2023, 2, 28)</li>
     * </ul>
     * 
     * @param date the base date
     * @param months the number of months to add (can be negative for subtraction)
     * @return LocalDate representing the result of the addition
     * @throws IllegalArgumentException if date is null
     */
    public static LocalDate addMonths(LocalDate date, int months) {
        if (date == null) {
            throw new IllegalArgumentException("Date cannot be null");
        }
        return date.plusMonths(months);
    }

    /**
     * Subtracts the specified number of months from a date.
     * 
     * <p>This method provides date arithmetic equivalent to COBOL date manipulation operations.
     * It correctly handles month-end scenarios following Java date-time API rules.</p>
     * 
     * <p>Example: subtractMonths(LocalDate.of(2023, 3, 31), 1) → LocalDate.of(2023, 2, 28)</p>
     * 
     * @param date the base date
     * @param months the number of months to subtract (must be positive)
     * @return LocalDate representing the result of the subtraction
     * @throws IllegalArgumentException if date is null
     */
    public static LocalDate subtractMonths(LocalDate date, int months) {
        if (date == null) {
            throw new IllegalArgumentException("Date cannot be null");
        }
        return date.minusMonths(months);
    }

    /**
     * Adds the specified number of years to a date.
     * 
     * <p>This method provides date arithmetic equivalent to COBOL date manipulation operations.
     * It correctly handles leap year scenarios following Java date-time API rules:</p>
     * <ul>
     *   <li>If the original date is Feb 29 and the target year is not a leap year, the result is Feb 28</li>
     *   <li>Example: addYears(LocalDate.of(2024, 2, 29), 1) → LocalDate.of(2025, 2, 28)</li>
     * </ul>
     * 
     * @param date the base date
     * @param years the number of years to add (can be negative for subtraction)
     * @return LocalDate representing the result of the addition
     * @throws IllegalArgumentException if date is null
     */
    public static LocalDate addYears(LocalDate date, int years) {
        if (date == null) {
            throw new IllegalArgumentException("Date cannot be null");
        }
        return date.plusYears(years);
    }

    /**
     * Subtracts the specified number of years from a date.
     * 
     * <p>This method provides date arithmetic equivalent to COBOL date manipulation operations.
     * It correctly handles leap year scenarios following Java date-time API rules.</p>
     * 
     * <p>Example: subtractYears(LocalDate.of(2024, 2, 29), 1) → LocalDate.of(2023, 2, 28)</p>
     * 
     * @param date the base date
     * @param years the number of years to subtract (must be positive)
     * @return LocalDate representing the result of the subtraction
     * @throws IllegalArgumentException if date is null
     */
    public static LocalDate subtractYears(LocalDate date, int years) {
        if (date == null) {
            throw new IllegalArgumentException("Date cannot be null");
        }
        return date.minusYears(years);
    }

    /**
     * Checks if the first date is before the second date.
     * 
     * <p>This method provides date comparison equivalent to COBOL date comparison operations.
     * Returns true if date1 is strictly before date2 in chronological order.</p>
     * 
     * <p>Example: isBefore(LocalDate.of(2023, 1, 1), LocalDate.of(2023, 12, 31)) → true</p>
     * 
     * @param date1 the first date
     * @param date2 the second date
     * @return true if date1 is before date2, false otherwise
     * @throws IllegalArgumentException if either date is null
     */
    public static boolean isBefore(LocalDate date1, LocalDate date2) {
        if (date1 == null || date2 == null) {
            throw new IllegalArgumentException("Dates cannot be null");
        }
        return date1.isBefore(date2);
    }

    /**
     * Checks if the first date is after the second date.
     * 
     * <p>This method provides date comparison equivalent to COBOL date comparison operations.
     * Returns true if date1 is strictly after date2 in chronological order.</p>
     * 
     * <p>Example: isAfter(LocalDate.of(2023, 12, 31), LocalDate.of(2023, 1, 1)) → true</p>
     * 
     * @param date1 the first date
     * @param date2 the second date
     * @return true if date1 is after date2, false otherwise
     * @throws IllegalArgumentException if either date is null
     */
    public static boolean isAfter(LocalDate date1, LocalDate date2) {
        if (date1 == null || date2 == null) {
            throw new IllegalArgumentException("Dates cannot be null");
        }
        return date1.isAfter(date2);
    }

    /**
     * Checks if two dates are equal.
     * 
     * <p>This method provides date comparison equivalent to COBOL date comparison operations.
     * Returns true if both dates represent the same day (year, month, and day match).</p>
     * 
     * <p>Example: isEqual(LocalDate.of(2023, 12, 25), LocalDate.of(2023, 12, 25)) → true</p>
     * 
     * @param date1 the first date
     * @param date2 the second date
     * @return true if date1 equals date2, false otherwise
     * @throws IllegalArgumentException if either date is null
     */
    public static boolean isEqual(LocalDate date1, LocalDate date2) {
        if (date1 == null || date2 == null) {
            throw new IllegalArgumentException("Dates cannot be null");
        }
        return date1.isEqual(date2);
    }

    /**
     * Converts a Lillian date (integer day count) to LocalDate.
     * 
     * <p>This method provides compatibility with COBOL CEEDAYS intrinsic function which uses
     * Lillian dates. The Lillian date system counts days from October 15, 1582 (the adoption
     * of the Gregorian calendar). Day 1 in the Lillian system is October 15, 1582.</p>
     * 
     * <p>COBOL example equivalence:</p>
     * <pre>
     * COBOL: COMPUTE LILLIAN-DATE = FUNCTION INTEGER-OF-DATE(YYYYMMDD)
     * Java: int lillianDate = convertLocalDateToLillian(LocalDate.of(yyyy, mm, dd))
     * 
     * COBOL: COMPUTE YYYYMMDD = FUNCTION DATE-OF-INTEGER(LILLIAN-DATE)
     * Java: LocalDate date = convertLillianToLocalDate(lillianDate)
     * </pre>
     * 
     * <p>This conversion is essential for maintaining compatibility with mainframe date arithmetic
     * that uses integer day counts for calculations.</p>
     * 
     * @param lillianDate the Lillian date integer (days since October 15, 1582)
     * @return LocalDate representing the converted date
     * @throws IllegalArgumentException if lillianDate is less than 1 (before epoch)
     */
    public static LocalDate convertLillianToLocalDate(int lillianDate) {
        if (lillianDate < 1) {
            throw new IllegalArgumentException(
                    String.format("Lillian date must be >= 1 (October 15, 1582). Provided: %d", lillianDate));
        }
        // Lillian date 1 = October 15, 1582, so we add (lillianDate - 1) days to the epoch
        return LILLIAN_EPOCH.plusDays(lillianDate - 1);
    }

    /**
     * Converts a LocalDate to Lillian date (integer day count).
     * 
     * <p>This method provides compatibility with COBOL CEEDAYS intrinsic function which uses
     * Lillian dates. The Lillian date system counts days from October 15, 1582 (the adoption
     * of the Gregorian calendar). Day 1 in the Lillian system is October 15, 1582.</p>
     * 
     * <p>COBOL example equivalence:</p>
     * <pre>
     * COBOL: COMPUTE LILLIAN-DATE = FUNCTION INTEGER-OF-DATE(YYYYMMDD)
     * Java: int lillianDate = convertLocalDateToLillian(LocalDate.of(yyyy, mm, dd))
     * </pre>
     * 
     * <p>This conversion is essential for maintaining compatibility with mainframe date arithmetic
     * that uses integer day counts for calculations.</p>
     * 
     * @param date the LocalDate to convert
     * @return int representing the Lillian date (days since October 15, 1582)
     * @throws IllegalArgumentException if date is null or before Lillian epoch (October 15, 1582)
     */
    public static int convertLocalDateToLillian(LocalDate date) {
        if (date == null) {
            throw new IllegalArgumentException("Date cannot be null");
        }
        if (date.isBefore(LILLIAN_EPOCH)) {
            throw new IllegalArgumentException(
                    String.format("Date %s is before Lillian epoch (October 15, 1582)", date));
        }
        // Calculate days between epoch and the provided date, then add 1 (epoch is day 1)
        return (int) (LILLIAN_EPOCH.until(date, java.time.temporal.ChronoUnit.DAYS) + 1);
    }
}
