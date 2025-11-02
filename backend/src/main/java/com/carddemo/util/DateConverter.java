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
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.format.ResolverStyle;

/**
 * Date format conversion utility providing transformations between various date string formats
 * used throughout the CardDemo application.
 * 
 * <p>This class transforms COBOL date format structures from CSDAT01Y.cpy (WS-DATE-TIME with
 * multiple REDEFINES) into Java date format conversion methods. It implements conversions between:</p>
 * <ul>
 *   <li>CCYYMMDD format (PIC 9(08)) - 8-digit numeric date string (e.g., "20231225")</li>
 *   <li>MM/DD/YY format (WS-CURDATE-MM-DD-YY) - slash-separated 2-digit year (e.g., "12/25/23")</li>
 *   <li>ISO timestamp format (WS-TIMESTAMP) - YYYY-MM-DD HH:MM:SS.microseconds (e.g., "2023-12-25 14:30:45.123456")</li>
 * </ul>
 * 
 * <p>All conversions preserve exact date values without timezone shifts or implicit conversions
 * per Section 0.9 requirements. Leading zeros are maintained for COBOL PIC clause compatibility.</p>
 * 
 * <p>This utility class implements REDEFINES-equivalent functionality where a single date can be
 * accessed as structured components (WS-CURDATE with year/month/day fields) or as a continuous
 * numeric string (WS-CURDATE-N PIC 9(08)).</p>
 * 
 * @see java.time.LocalDate
 * @see java.time.LocalDateTime
 */
public class DateConverter {

    /**
     * Date formatter for CCYYMMDD/YYYYMMDD format (8-digit numeric: YYYYMMDD).
     * Matches COBOL WS-CURDATE-N PIC 9(08) structure from CSDAT01Y.cpy lines 23.
     * Uses STRICT resolver style to reject invalid dates per COBOL validation rules.
     */
    private static final DateTimeFormatter CCYYMMDD_FORMATTER = DateTimeFormatter
            .ofPattern("yyyyMMdd")
            .withResolverStyle(ResolverStyle.STRICT);

    /**
     * Date formatter for MM/DD/YY format with 2-digit year.
     * Matches COBOL WS-CURDATE-MM-DD-YY structure from CSDAT01Y.cpy lines 30-35.
     * Uses STRICT resolver style to enforce valid date component ranges.
     */
    private static final DateTimeFormatter MMDDYY_FORMATTER = DateTimeFormatter
            .ofPattern("MM/dd/yy")
            .withResolverStyle(ResolverStyle.STRICT);

    /**
     * Timestamp formatter for ISO format with microsecond precision (6 digits).
     * Matches COBOL WS-TIMESTAMP structure from CSDAT01Y.cpy lines 42-55.
     * Format: YYYY-MM-DD HH:MM:SS.microseconds where microseconds is PIC 9(06).
     * Uses STRICT resolver style to validate all date and time components.
     */
    private static final DateTimeFormatter ISO_TIMESTAMP_FORMATTER = DateTimeFormatter
            .ofPattern("yyyy-MM-dd HH:mm:ss.SSSSSS")
            .withResolverStyle(ResolverStyle.STRICT);

    /**
     * Private constructor to prevent instantiation of utility class.
     * All methods are static and should be accessed via class name.
     */
    private DateConverter() {
        throw new UnsupportedOperationException("DateConverter is a utility class and cannot be instantiated");
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
        validateDateString(dateString, 8, "CCYYMMDD");
        
        try {
            return LocalDate.parse(dateString, CCYYMMDD_FORMATTER);
        } catch (DateTimeParseException e) {
            throw new IllegalArgumentException(
                    String.format("Invalid CCYYMMDD date format: '%s'. Expected format: YYYYMMDD (e.g., '20231225'). %s",
                            dateString, e.getMessage()),
                    e);
        }
    }

    /**
     * Formats a LocalDate to CCYYMMDD format (8-digit numeric: YYYYMMDD).
     * 
     * <p>This method converts Java LocalDate to COBOL WS-CURDATE-N PIC 9(08) format.
     * Leading zeros are preserved for all components (year, month, day).</p>
     * 
     * <p>Example: LocalDate.of(2023, 12, 25) → "20231225"</p>
     * 
     * @param date the LocalDate to format
     * @return String in CCYYMMDD format with leading zeros preserved
     * @throws IllegalArgumentException if date is null
     */
    public static String formatCCYYMMDD(LocalDate date) {
        if (date == null) {
            throw new IllegalArgumentException("Date cannot be null");
        }
        return date.format(CCYYMMDD_FORMATTER);
    }

    /**
     * Parses a date string in MM/DD/YY format to LocalDate.
     * 
     * <p>This method converts COBOL WS-CURDATE-MM-DD-YY format (lines 30-35 of CSDAT01Y.cpy)
     * to Java LocalDate. The input must be exactly in MM/DD/YY format with slash separators.</p>
     * 
     * <p>Two-digit year interpretation follows Java DateTimeFormatter default behavior:
     * years 00-99 are interpreted as 2000-2099.</p>
     * 
     * <p>Example: "12/25/23" → LocalDate.of(2023, 12, 25)</p>
     * 
     * @param dateString the date string in MM/DD/YY format (e.g., "12/25/23")
     * @return LocalDate object representing the parsed date
     * @throws IllegalArgumentException if dateString is null, empty, or has invalid format
     */
    public static LocalDate parseMMDDYY(String dateString) {
        if (dateString == null || dateString.trim().isEmpty()) {
            throw new IllegalArgumentException("Date string cannot be null or empty for MM/DD/YY format");
        }
        
        if (!dateString.matches("\\d{2}/\\d{2}/\\d{2}")) {
            throw new IllegalArgumentException(
                    String.format("Invalid MM/DD/YY date format: '%s'. Expected format: MM/DD/YY (e.g., '12/25/23')",
                            dateString));
        }
        
        try {
            return LocalDate.parse(dateString, MMDDYY_FORMATTER);
        } catch (DateTimeParseException e) {
            throw new IllegalArgumentException(
                    String.format("Invalid MM/DD/YY date format: '%s'. %s", dateString, e.getMessage()),
                    e);
        }
    }

    /**
     * Formats a LocalDate to MM/DD/YY format.
     * 
     * <p>This method converts Java LocalDate to COBOL WS-CURDATE-MM-DD-YY format
     * (lines 30-35 of CSDAT01Y.cpy). The output includes slash separators and uses
     * 2-digit year representation (last 2 digits of the year).</p>
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
        if (date == null) {
            throw new IllegalArgumentException("Date cannot be null");
        }
        return date.format(MMDDYY_FORMATTER);
    }

    /**
     * Parses an ISO timestamp string with microsecond precision to LocalDateTime.
     * 
     * <p>This method converts COBOL WS-TIMESTAMP structure (lines 42-55 of CSDAT01Y.cpy)
     * to Java LocalDateTime. The input format is: YYYY-MM-DD HH:MM:SS.microseconds
     * where microseconds is a 6-digit field (PIC 9(06)).</p>
     * 
     * <p>Microsecond precision is preserved by converting to nanoseconds in LocalDateTime.
     * One microsecond equals 1000 nanoseconds.</p>
     * 
     * <p>Example: "2023-12-25 14:30:45.123456" → LocalDateTime.of(2023, 12, 25, 14, 30, 45, 123456000)</p>
     * 
     * @param timestampString the timestamp string in ISO format with microseconds
     * @return LocalDateTime object representing the parsed timestamp
     * @throws IllegalArgumentException if timestampString is null, empty, or has invalid format
     */
    public static LocalDateTime parseISOTimestamp(String timestampString) {
        if (timestampString == null || timestampString.trim().isEmpty()) {
            throw new IllegalArgumentException("Timestamp string cannot be null or empty for ISO format");
        }
        
        // Validate format: YYYY-MM-DD HH:MM:SS.microseconds (26 characters total)
        if (!timestampString.matches("\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2}\\.\\d{6}")) {
            throw new IllegalArgumentException(
                    String.format("Invalid ISO timestamp format: '%s'. Expected format: YYYY-MM-DD HH:MM:SS.microseconds (e.g., '2023-12-25 14:30:45.123456')",
                            timestampString));
        }
        
        try {
            return LocalDateTime.parse(timestampString, ISO_TIMESTAMP_FORMATTER);
        } catch (DateTimeParseException e) {
            throw new IllegalArgumentException(
                    String.format("Invalid ISO timestamp format: '%s'. %s", timestampString, e.getMessage()),
                    e);
        }
    }

    /**
     * Formats a LocalDateTime to ISO timestamp format with microsecond precision.
     * 
     * <p>This method converts Java LocalDateTime to COBOL WS-TIMESTAMP format
     * (lines 42-55 of CSDAT01Y.cpy). The output format is: YYYY-MM-DD HH:MM:SS.microseconds
     * where microseconds is a 6-digit field with leading zeros preserved.</p>
     * 
     * <p>Nanosecond precision in LocalDateTime is converted to microseconds by dividing by 1000
     * and truncating to 6 digits, matching COBOL WS-TIMESTAMP-TM-MS6 PIC 9(06) precision.</p>
     * 
     * <p>Example: LocalDateTime.of(2023, 12, 25, 14, 30, 45, 123456000) → "2023-12-25 14:30:45.123456"</p>
     * 
     * @param dateTime the LocalDateTime to format
     * @return String in ISO timestamp format with 6-digit microsecond precision
     * @throws IllegalArgumentException if dateTime is null
     */
    public static String formatISOTimestamp(LocalDateTime dateTime) {
        if (dateTime == null) {
            throw new IllegalArgumentException("DateTime cannot be null");
        }
        return dateTime.format(ISO_TIMESTAMP_FORMATTER);
    }

    /**
     * Parses a date string in YYYYMMDD format (8-digit numeric) to LocalDate.
     * 
     * <p>This method is functionally equivalent to {@link #parseCCYYMMDD(String)} and is provided
     * for API clarity when working with YYYYMMDD nomenclature.</p>
     * 
     * <p>Converts COBOL WS-CURDATE-N PIC 9(08) format to Java LocalDate.
     * The input must be exactly 8 digits with leading zeros preserved.</p>
     * 
     * <p>Example: "20231225" → LocalDate.of(2023, 12, 25)</p>
     * 
     * @param dateString the date string in YYYYMMDD format (exactly 8 digits)
     * @return LocalDate object representing the parsed date
     * @throws IllegalArgumentException if dateString is null, empty, not 8 digits, or invalid date
     */
    public static LocalDate parseYYYYMMDD(String dateString) {
        // YYYYMMDD is the same format as CCYYMMDD (both are 8-digit numeric dates)
        // Delegate to parseCCYYMMDD to avoid code duplication
        return parseCCYYMMDD(dateString);
    }

    /**
     * Formats a LocalDate to YYYYMMDD format (8-digit numeric).
     * 
     * <p>This method is functionally equivalent to {@link #formatCCYYMMDD(LocalDate)} and is provided
     * for API clarity when working with YYYYMMDD nomenclature.</p>
     * 
     * <p>Converts Java LocalDate to COBOL WS-CURDATE-N PIC 9(08) format.
     * Leading zeros are preserved for all components (year, month, day).</p>
     * 
     * <p>Example: LocalDate.of(2023, 12, 25) → "20231225"</p>
     * 
     * @param date the LocalDate to format
     * @return String in YYYYMMDD format with leading zeros preserved
     * @throws IllegalArgumentException if date is null
     */
    public static String formatYYYYMMDD(LocalDate date) {
        // YYYYMMDD is the same format as CCYYMMDD (both are 8-digit numeric dates)
        // Delegate to formatCCYYMMDD to avoid code duplication
        return formatCCYYMMDD(date);
    }

    /**
     * Validates a date string for proper format and length.
     * 
     * <p>This private helper method enforces COBOL PIC clause length constraints
     * and ensures numeric-only content where required.</p>
     * 
     * @param dateString the date string to validate
     * @param expectedLength the expected length per COBOL PIC clause
     * @param formatName the format name for error messages
     * @throws IllegalArgumentException if validation fails
     */
    private static void validateDateString(String dateString, int expectedLength, String formatName) {
        if (dateString == null || dateString.trim().isEmpty()) {
            throw new IllegalArgumentException(
                    String.format("Date string cannot be null or empty for %s format", formatName));
        }
        
        if (dateString.length() != expectedLength) {
            throw new IllegalArgumentException(
                    String.format("Invalid %s date length: '%s'. Expected exactly %d digits, but got %d",
                            formatName, dateString, expectedLength, dateString.length()));
        }
        
        if (!dateString.matches("\\d+")) {
            throw new IllegalArgumentException(
                    String.format("Invalid %s date format: '%s'. Must contain only numeric digits (0-9)",
                            formatName, dateString));
        }
    }
}
