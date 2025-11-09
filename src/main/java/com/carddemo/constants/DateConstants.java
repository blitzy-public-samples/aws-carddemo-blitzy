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
package com.carddemo.constants;

import java.time.LocalDate;

/**
 * Date and time formatting constants for the CardDemo application.
 * 
 * <p>This class provides standard date/time format patterns used throughout the application,
 * ensuring consistent date formatting across all layers (service, controller, batch processing).
 * 
 * <p><b>COBOL Origin:</b> This class is derived from the COBOL copybook {@code CSDAT01Y.cpy}
 * which defined the {@code WS-DATE-TIME} structure containing various date and time field
 * formats used in the mainframe CardDemo application.
 * 
 * <p><b>Lillian Date Conversion:</b> The LILLIAN_EPOCH_DATE constant supports conversion
 * between COBOL's Lillian date format (used by CEEDAYS/CEEDATE functions) and Java's
 * LocalDate. The Lillian date system counts days since October 15, 1582 (the start of
 * the Gregorian calendar), and this epoch is critical for maintaining exact functional
 * equivalence with mainframe date arithmetic.
 * 
 * <p><b>Original COBOL Date Structures:</b>
 * <ul>
 *   <li>WS-CURDATE (YYYYMMDD) → DATE_FORMAT_YYYYMMDD pattern</li>
 *   <li>WS-CURDATE-MM-DD-YY (MM/DD/YY) → DATE_FORMAT_MMDDYY pattern</li>
 *   <li>WS-CURTIME-HH-MM-SS (HH:MM:SS) → TIME_FORMAT_HHMMSS pattern</li>
 *   <li>WS-TIMESTAMP (YYYY-MM-DD HH:MM:SS.MMMMMM) → TIMESTAMP_FORMAT pattern</li>
 * </ul>
 * 
 * <p><b>Usage Example:</b>
 * <pre>{@code
 * // Format a date using the standard YYYY-MM-DD pattern
 * DateTimeFormatter formatter = DateTimeFormatter.ofPattern(DateConstants.DATE_FORMAT_YYYYMMDD);
 * String formattedDate = LocalDate.now().format(formatter);
 * 
 * // Convert Lillian date (from COBOL CEEDAYS) to LocalDate
 * int lillianDays = 159845;  // Example Lillian date from COBOL
 * LocalDate date = DateConstants.LILLIAN_EPOCH_DATE.plusDays(lillianDays);
 * }</pre>
 * 
 * @see java.time.format.DateTimeFormatter
 * @see java.time.LocalDate
 * @see java.time.LocalDateTime
 */
public final class DateConstants {

    /**
     * Private constructor to prevent instantiation of this utility class.
     * This class is designed to hold only static constants and should never be instantiated.
     */
    private DateConstants() {
        throw new UnsupportedOperationException("DateConstants is a utility class and cannot be instantiated");
    }

    /**
     * The Lillian date epoch: October 15, 1582.
     * 
     * <p>This date represents day 0 in the Lillian date system used by COBOL's CEEDAYS
     * and CEEDATE callable services. The Lillian date system is named after Luigi Lilio,
     * who proposed the Gregorian calendar reform that took effect on this date.
     * 
     * <p><b>COBOL Function Equivalence:</b>
     * <ul>
     *   <li>COBOL CEEDAYS converts a Gregorian date to Lillian days (integer)</li>
     *   <li>COBOL CEEDATE converts Lillian days back to a Gregorian date</li>
     *   <li>Java equivalent: days between LILLIAN_EPOCH_DATE and target date</li>
     * </ul>
     * 
     * <p><b>Conversion Examples:</b>
     * <pre>{@code
     * // Convert LocalDate to Lillian days (equivalent to COBOL CEEDAYS)
     * long lillianDays = ChronoUnit.DAYS.between(DateConstants.LILLIAN_EPOCH_DATE, targetDate);
     * 
     * // Convert Lillian days to LocalDate (equivalent to COBOL CEEDATE)
     * LocalDate date = DateConstants.LILLIAN_EPOCH_DATE.plusDays(lillianDays);
     * }</pre>
     * 
     * <p><b>Critical for Functional Equivalence:</b> Maintaining this exact epoch date
     * ensures that all date arithmetic operations produce identical results to the
     * mainframe COBOL implementation, which is essential for data integrity during
     * the migration period and for any data exchanges with legacy systems.
     */
    public static final LocalDate LILLIAN_EPOCH_DATE = LocalDate.of(1582, 10, 15);

    /**
     * Standard date format pattern: yyyy-MM-dd
     * 
     * <p>Corresponds to COBOL structure {@code WS-CURDATE} (YYYYMMDD format).
     * This ISO 8601 compliant format is used for:
     * <ul>
     *   <li>Database date column storage and retrieval</li>
     *   <li>REST API date parameter serialization</li>
     *   <li>Date parsing from external system files</li>
     *   <li>Logging and audit trail timestamps</li>
     * </ul>
     * 
     * <p><b>Example:</b> "2024-03-15" represents March 15, 2024
     * 
     * <p><b>Usage:</b>
     * <pre>{@code
     * DateTimeFormatter formatter = DateTimeFormatter.ofPattern(DATE_FORMAT_YYYYMMDD);
     * LocalDate date = LocalDate.parse("2024-03-15", formatter);
     * String formatted = date.format(formatter);
     * }</pre>
     */
    public static final String DATE_FORMAT_YYYYMMDD = "yyyy-MM-dd";

    /**
     * Legacy date format pattern: MM/dd/yy
     * 
     * <p>Corresponds to COBOL structure {@code WS-CURDATE-MM-DD-YY} with '/' delimiters.
     * This format maintains compatibility with the original 3270 terminal screen displays
     * and is used for:
     * <ul>
     *   <li>User interface date display (React components)</li>
     *   <li>Legacy report generation maintaining original format</li>
     *   <li>External file formats requiring MM/DD/YY layout</li>
     * </ul>
     * 
     * <p><b>Example:</b> "03/15/24" represents March 15, 2024
     * 
     * <p><b>Warning:</b> This two-digit year format may cause ambiguity for dates
     * beyond the year 2099. For new implementations, prefer DATE_FORMAT_YYYYMMDD.
     * This pattern is retained solely for maintaining functional equivalence with
     * the original BMS screen displays.
     * 
     * <p><b>Usage:</b>
     * <pre>{@code
     * DateTimeFormatter formatter = DateTimeFormatter.ofPattern(DATE_FORMAT_MMDDYY);
     * LocalDate date = LocalDate.of(2024, 3, 15);
     * String formatted = date.format(formatter);  // Returns "03/15/24"
     * }</pre>
     */
    public static final String DATE_FORMAT_MMDDYY = "MM/dd/yy";

    /**
     * Standard timestamp format pattern: yyyy-MM-dd HH:mm:ss.SSSSSS
     * 
     * <p>Corresponds to COBOL structure {@code WS-TIMESTAMP} which includes both
     * date and time with microsecond precision. This format is used for:
     * <ul>
     *   <li>Transaction timestamp recording (6-digit microsecond precision)</li>
     *   <li>Audit log entries requiring precise timing</li>
     *   <li>Batch job execution start/end timestamps</li>
     *   <li>Database timestamp column storage</li>
     * </ul>
     * 
     * <p><b>Example:</b> "2024-03-15 14:23:45.123456" represents March 15, 2024
     * at 2:23:45 PM and 123,456 microseconds
     * 
     * <p><b>Precision Note:</b> The COBOL original supported microsecond precision
     * (6 digits after decimal point). Java's {@code LocalDateTime} with {@code Instant}
     * supports nanosecond precision, but this format maintains the 6-digit microsecond
     * display for exact compatibility with mainframe transaction logs.
     * 
     * <p><b>Usage:</b>
     * <pre>{@code
     * DateTimeFormatter formatter = DateTimeFormatter.ofPattern(TIMESTAMP_FORMAT);
     * LocalDateTime timestamp = LocalDateTime.now();
     * String formatted = timestamp.format(formatter);
     * }</pre>
     */
    public static final String TIMESTAMP_FORMAT = "yyyy-MM-dd HH:mm:ss.SSSSSS";

    /**
     * Standard time format pattern: HH:mm:ss
     * 
     * <p>Corresponds to COBOL structure {@code WS-CURTIME-HH-MM-SS} with ':'
     * delimiters. This 24-hour time format is used for:
     * <ul>
     *   <li>Time-of-day display in user interfaces</li>
     *   <li>Batch job scheduling and execution time recording</li>
     *   <li>Transaction processing time display</li>
     *   <li>System event logging</li>
     * </ul>
     * 
     * <p><b>Example:</b> "14:23:45" represents 2:23:45 PM (24-hour format)
     * 
     * <p><b>24-Hour Format:</b> This pattern uses HH (00-23) rather than hh (01-12)
     * to maintain consistency with the COBOL mainframe implementation which uses
     * 24-hour time representation throughout.
     * 
     * <p><b>Usage:</b>
     * <pre>{@code
     * DateTimeFormatter formatter = DateTimeFormatter.ofPattern(TIME_FORMAT_HHMMSS);
     * LocalTime time = LocalTime.now();
     * String formatted = time.format(formatter);
     * }</pre>
     */
    public static final String TIME_FORMAT_HHMMSS = "HH:mm:ss";
}
