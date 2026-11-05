package com.cardemo.common.util;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;

/**
 * Date conversion utility replicating IBM Language Environment CEEDAYS API functionality.
 *
 * <p>Translated from:
 * <ul>
 *   <li>CSUTLDTC.cbl — Main CEEDAYS wrapper program (PROGRAM-ID: CSUTLDTC)</li>
 *   <li>CSDAT01Y.cpy — Date/time working storage (WS-DATE-TIME structure)</li>
 *   <li>CSUTLDWY.cpy — Date edit working storage with 88-level validation conditions</li>
 * </ul>
 *
 * <p>Provides conversions between three date formats used throughout CardDemo:
 * <ol>
 *   <li><b>CCYYMMDD</b> (internal) — 8-character numeric date, e.g., {@code "20231215"}</li>
 *   <li><b>MM/DD/YYYY</b> (display) — 10-character formatted date, e.g., {@code "12/15/2023"}</li>
 *   <li><b>ISO-8601 timestamp</b> — 26-character extended format with microsecond precision,
 *       e.g., {@code "2023-12-15-10.30.45.123456"} (TRAN-ORIG-TS per AAP section 0.7.4)</li>
 * </ol>
 *
 * <p>All methods are static pure functions with no mutable state. All date operations
 * are null-safe and handle empty string input gracefully.
 *
 * @see <a href="app/cbl/CSUTLDTC.cbl">CSUTLDTC.cbl — CEEDAYS API wrapper</a>
 * @see <a href="app/cpy/CSDAT01Y.cpy">CSDAT01Y.cpy — Date/time working storage</a>
 * @see <a href="app/cpy/CSUTLDWY.cpy">CSUTLDWY.cpy — Date edit validation rules</a>
 */
public final class DateConversionUtil {

    // ========================================================================
    // DateTimeFormatter Constants
    // ========================================================================

    /**
     * CCYYMMDD (yyyyMMdd) format — internal date representation.
     * Corresponds to COBOL WS-CURDATE-DATA structure:
     * WS-CURDATE-YEAR PIC 9(04) + WS-CURDATE-MONTH PIC 9(02) + WS-CURDATE-DAY PIC 9(02)
     * from CSDAT01Y.cpy lines 19-22.
     */
    private static final DateTimeFormatter CCYYMMDD_FORMAT =
            DateTimeFormatter.ofPattern("yyyyMMdd");

    /**
     * MM/DD/YYYY display format — human-readable date representation.
     * Corresponds to COBOL WS-CURDATE-MM-DD-YY from CSDAT01Y.cpy lines 30-35,
     * upgraded from 2-digit year (YY) to 4-digit year (YYYY) per AAP requirements.
     */
    private static final DateTimeFormatter DISPLAY_FORMAT =
            DateTimeFormatter.ofPattern("MM/dd/yyyy");

    /**
     * AAP-specified ISO timestamp format: YYYY-MM-DD-HH.MM.SS.mmmmmm (26 characters).
     * Uses dash separator between date and time parts, dots between time components.
     * Matches TRAN-ORIG-TS format per AAP section 0.7.4.
     *
     * <p>Note: This differs from the COBOL WS-TIMESTAMP format which uses space + colons.
     * Both formats are 26 characters with 6-digit microsecond precision.
     */
    private static final DateTimeFormatter ISO_TIMESTAMP_FORMAT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd-HH.mm.ss.SSSSSS");

    /**
     * COBOL WS-TIMESTAMP format: YYYY-MM-DD HH:MM:SS.mmmmmm (26 characters).
     * Uses space separator between date and time, colons between time components.
     * Directly corresponds to CSDAT01Y.cpy lines 42-55:
     * <pre>
     *   WS-TIMESTAMP-DT-YYYY '-' WS-TIMESTAMP-DT-MM '-' WS-TIMESTAMP-DT-DD
     *   ' ' WS-TIMESTAMP-TM-HH ':' WS-TIMESTAMP-TM-MM ':' WS-TIMESTAMP-TM-SS
     *   '.' WS-TIMESTAMP-TM-MS6
     * </pre>
     */
    private static final DateTimeFormatter COBOL_TIMESTAMP_FORMAT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSSSSS");

    // ========================================================================
    // CEEDAYS Feedback Code Constants (from CSUTLDTC.cbl lines 62-70)
    // ========================================================================
    // Decoded from hex FEEDBACK-TOKEN-VALUE:
    //   Bytes 0-1: SEVERITY (PIC S9(4) BINARY)
    //   Bytes 2-3: MSG-NO   (PIC S9(4) BINARY)
    //   Byte 4:    CASE-SEV-CTL
    //   Bytes 5-7: FACILITY-ID ('CEE' in EBCDIC)
    // ========================================================================

    /** FC-INVALID-DATE: X'0000000000000000' — severity 0, all fields valid. */
    private static final int SEVERITY_OK = 0;

    /** All CEEDAYS error conditions use severity 3 (X'0003...'). */
    private static final int SEVERITY_ERROR = 3;

    /** FC-INVALID-DATE msg-no=0: date parsed successfully. */
    private static final int MSG_VALID = 0;

    /** FC-INSUFFICIENT-DATA msg-no=2507 (X'09CB'): input too short for format. */
    private static final int MSG_INSUFFICIENT_DATA = 2507;

    /** FC-BAD-DATE-VALUE msg-no=2508 (X'09CC'): day invalid for given month/year. */
    private static final int MSG_BAD_DATE_VALUE = 2508;

    /** FC-INVALID-ERA msg-no=2509 (X'09CD'): century not 19 or 20. */
    private static final int MSG_INVALID_ERA = 2509;

    /** FC-UNSUPP-RANGE msg-no=2513 (X'09D1'): date outside supported calendar range. */
    private static final int MSG_UNSUPP_RANGE = 2513;

    /** FC-INVALID-MONTH msg-no=2517 (X'09D5'): month not 1-12. */
    private static final int MSG_INVALID_MONTH = 2517;

    /** FC-BAD-PIC-STRING msg-no=2518 (X'09D6'): unrecognized format mask. */
    private static final int MSG_BAD_PIC_STRING = 2518;

    /** FC-NON-NUMERIC-DATA msg-no=2520 (X'09D8'): non-digit in numeric field. */
    private static final int MSG_NON_NUMERIC_DATA = 2520;

    /** FC-YEAR-IN-ERA-ZERO msg-no=2521 (X'09D9'): year value is 0000. */
    private static final int MSG_YEAR_IN_ERA_ZERO = 2521;

    /** WHEN OTHER catch-all: unclassified parse failure (CSUTLDTC.cbl line 147). */
    private static final int MSG_INVALID_OTHER = 9999;

    // ========================================================================
    // Constructor
    // ========================================================================

    /**
     * Private constructor — utility class with only static methods, no instantiation.
     */
    private DateConversionUtil() {
        // Utility class — prevent instantiation
    }

    // ========================================================================
    // DateValidationResult Record
    // ========================================================================

    /**
     * Validation result mirroring the 80-byte WS-MESSAGE / WS-DATE-VALIDATION-RESULT
     * structure from CSUTLDTC.cbl (lines 42-57) and CSUTLDWY.cpy (lines 60-85).
     *
     * <p>Field mapping to COBOL structure:
     * <ul>
     *   <li>{@code severity} → WS-SEVERITY-N PIC 9(4): 0 = valid, 3 = error</li>
     *   <li>{@code messageCode} → WS-MSG-NO-N PIC 9(4): CEEDAYS feedback message number</li>
     *   <li>{@code resultText} → WS-RESULT PIC X(15): human-readable validation result</li>
     *   <li>{@code testedDate} → WS-DATE PIC X(10): the date string that was tested</li>
     *   <li>{@code formatUsed} → WS-DATE-FMT PIC X(10): the format mask used for testing</li>
     *   <li>{@code valid} → convenience flag: {@code true} when severity is 0</li>
     * </ul>
     *
     * @param severity    CEEDAYS severity level (0 = valid, 3 = error)
     * @param messageCode CEEDAYS message number identifying the specific error condition
     * @param resultText  human-readable result text (up to 15 characters per WS-RESULT)
     * @param testedDate  the original date string that was validated
     * @param formatUsed  the COBOL-style format mask used during validation
     * @param valid       convenience flag: {@code true} if and only if severity is 0
     */
    public record DateValidationResult(
            int severity,
            int messageCode,
            String resultText,
            String testedDate,
            String formatUsed,
            boolean valid
    ) {
    }

    // ========================================================================
    // Core Conversion Methods
    // ========================================================================

    /**
     * Converts a date from CCYYMMDD internal format to MM/DD/YYYY display format.
     *
     * <p>Translated from CSDAT01Y.cpy WS-CURDATE-MM-DD-YY format construction,
     * where COBOL programs move WS-CURDATE-MONTH, WS-CURDATE-DAY, and WS-CURDATE-YEAR
     * fields into the MM/DD/YY display structure (lines 30-35).
     *
     * @param ccyymmdd date string in CCYYMMDD format, e.g., {@code "20231215"}
     * @return date in MM/DD/YYYY format, e.g., {@code "12/15/2023"},
     *         or {@code null} if input is null, empty, or unparseable
     */
    public static String convertCcyymmddToDisplay(String ccyymmdd) {
        if (ccyymmdd == null || ccyymmdd.isBlank()) {
            return null;
        }
        try {
            LocalDate date = LocalDate.parse(ccyymmdd, CCYYMMDD_FORMAT);
            // Roundtrip verification: guards against SMART resolver silently adjusting
            // invalid dates (e.g., Feb 30 → Feb 28). CEEDAYS would reject such dates,
            // so we must also reject them here.
            if (!date.format(CCYYMMDD_FORMAT).equals(ccyymmdd)) {
                return null;
            }
            return date.format(DISPLAY_FORMAT);
        } catch (DateTimeParseException _) {
            return null;
        }
    }

    /**
     * Converts a date from MM/DD/YYYY display format to CCYYMMDD internal format.
     *
     * <p>Inverse of {@link #convertCcyymmddToDisplay(String)}. Translates the
     * display-formatted date back to the internal CCYYMMDD representation used
     * by COBOL WS-CURDATE-DATA (CSDAT01Y.cpy lines 18-23).
     *
     * @param displayDate date string in MM/DD/YYYY format, e.g., {@code "12/15/2023"}
     * @return date in CCYYMMDD format, e.g., {@code "20231215"},
     *         or {@code null} if input is null, empty, or unparseable
     */
    public static String convertDisplayToCcyymmdd(String displayDate) {
        if (displayDate == null || displayDate.isBlank()) {
            return null;
        }
        try {
            LocalDate date = LocalDate.parse(displayDate, DISPLAY_FORMAT);
            // Roundtrip verification: guards against SMART resolver silently adjusting
            // invalid dates (e.g., 02/30/2023 → 02/28/2023). CEEDAYS would reject.
            if (!date.format(DISPLAY_FORMAT).equals(displayDate)) {
                return null;
            }
            return date.format(CCYYMMDD_FORMAT);
        } catch (DateTimeParseException _) {
            return null;
        }
    }

    /**
     * Converts a CCYYMMDD date string to the 26-character ISO-8601 extended timestamp.
     *
     * <p>The time component is set to midnight (00.00.00.000000). Output format:
     * {@code YYYY-MM-DD-HH.MM.SS.mmmmmm} matching TRAN-ORIG-TS per AAP section 0.7.4.
     *
     * <p>Related to CSDAT01Y.cpy WS-TIMESTAMP structure (lines 42-55) with
     * 6-digit microsecond precision (WS-TIMESTAMP-TM-MS6 PIC 9(06)).
     *
     * @param ccyymmdd date string in CCYYMMDD format, e.g., {@code "20231215"}
     * @return 26-character ISO timestamp, e.g., {@code "2023-12-15-00.00.00.000000"},
     *         or {@code null} if input is null, empty, or unparseable
     */
    public static String convertToIso8601(String ccyymmdd) {
        if (ccyymmdd == null || ccyymmdd.isBlank()) {
            return null;
        }
        try {
            LocalDate date = LocalDate.parse(ccyymmdd, CCYYMMDD_FORMAT);
            // Roundtrip verification: guards against SMART resolver silently adjusting
            // invalid dates. CEEDAYS would reject, so we must also reject.
            if (!date.format(CCYYMMDD_FORMAT).equals(ccyymmdd)) {
                return null;
            }
            // Construct midnight timestamp using LocalDateTime.of()
            LocalDateTime dateTime = LocalDateTime.of(
                    date.getYear(), date.getMonthValue(), date.getDayOfMonth(),
                    0, 0, 0, 0);
            return dateTime.format(ISO_TIMESTAMP_FORMAT);
        } catch (DateTimeParseException _) {
            return null;
        }
    }

    /**
     * Extracts the CCYYMMDD date portion from a 26-character ISO-8601 extended timestamp.
     *
     * <p>Accepts both the AAP format ({@code YYYY-MM-DD-HH.MM.SS.mmmmmm} with dashes/dots)
     * and the COBOL WS-TIMESTAMP format ({@code YYYY-MM-DD HH:MM:SS.mmmmmm} with
     * space/colons from CSDAT01Y.cpy lines 42-55).
     *
     * @param iso ISO-8601 timestamp string, e.g., {@code "2023-12-15-10.30.45.123456"}
     * @return date in CCYYMMDD format, e.g., {@code "20231215"},
     *         or {@code null} if input is null, empty, or unparseable
     */
    public static String convertFromIso8601(String iso) {
        if (iso == null || iso.isBlank()) {
            return null;
        }
        // Try AAP format first: YYYY-MM-DD-HH.MM.SS.mmmmmm (dash + dots)
        try {
            LocalDateTime dateTime = LocalDateTime.parse(iso, ISO_TIMESTAMP_FORMAT);
            // Roundtrip verification: guards against SMART resolver adjustments
            if (!dateTime.format(ISO_TIMESTAMP_FORMAT).equals(iso)) {
                return null;
            }
            return dateTime.toLocalDate().format(CCYYMMDD_FORMAT);
        } catch (DateTimeParseException _) {
            // Fall through to try COBOL format
        }
        // Try COBOL WS-TIMESTAMP format: YYYY-MM-DD HH:MM:SS.mmmmmm (space + colons)
        try {
            LocalDateTime dateTime = LocalDateTime.parse(iso, COBOL_TIMESTAMP_FORMAT);
            // Roundtrip verification: guards against SMART resolver adjustments
            if (!dateTime.format(COBOL_TIMESTAMP_FORMAT).equals(iso)) {
                return null;
            }
            return dateTime.toLocalDate().format(CCYYMMDD_FORMAT);
        } catch (DateTimeParseException _) {
            return null;
        }
    }

    /**
     * Returns the current date/time as a 26-character ISO-8601 extended timestamp.
     *
     * <p>Format: {@code YYYY-MM-DD-HH.MM.SS.mmmmmm} matching TRAN-ORIG-TS
     * per AAP section 0.7.4. Uses microsecond precision (6 digits) matching
     * COBOL WS-TIMESTAMP-TM-MS6 PIC 9(06) from CSDAT01Y.cpy line 55.
     *
     * <p>Microsecond truncation via {@link ChronoUnit#MICROS} ensures exactly
     * 6-digit fractional seconds in the output string.
     *
     * @return current timestamp in 26-character format,
     *         e.g., {@code "2026-03-17-14.30.45.123456"}
     */
    public static String getCurrentTimestamp() {
        LocalDateTime now = LocalDateTime.now().truncatedTo(ChronoUnit.MICROS);
        return now.format(ISO_TIMESTAMP_FORMAT);
    }

    /**
     * Returns the current date in CCYYMMDD internal format.
     *
     * <p>Corresponds to COBOL's {@code ACCEPT WS-CURDATE-DATA FROM DATE YYYYMMDD}
     * which populates WS-CURDATE-YEAR(4) + WS-CURDATE-MONTH(2) + WS-CURDATE-DAY(2)
     * from CSDAT01Y.cpy lines 19-22.
     *
     * @return current date as CCYYMMDD string, e.g., {@code "20260317"}
     */
    public static String getCurrentDateCcyymmdd() {
        return LocalDate.now().format(CCYYMMDD_FORMAT);
    }

    /**
     * Returns the current date in MM/DD/YYYY display format.
     *
     * <p>Corresponds to the formatted display date WS-CURDATE-MM-DD-YY
     * from CSDAT01Y.cpy lines 30-35, upgraded to 4-digit year (YYYY).
     *
     * @return current date as MM/DD/YYYY string, e.g., {@code "03/17/2026"}
     */
    public static String getCurrentDateDisplay() {
        return LocalDate.now().format(DISPLAY_FORMAT);
    }

    // ========================================================================
    // Date Validation Methods
    // ========================================================================

    /**
     * Validates a date string against a COBOL-style format mask, replicating CEEDAYS behavior.
     *
     * <p>This is the core translation of CSUTLDTC.cbl paragraph A000-MAIN (lines 103-151).
     * Maps Java date parsing outcomes to CEEDAYS FEEDBACK-CODE error categories defined
     * in CSUTLDTC.cbl lines 62-70:
     *
     * <table>
     *   <caption>CEEDAYS Feedback Code Mapping</caption>
     *   <tr><th>COBOL Condition</th><th>Sev</th><th>Code</th><th>Result Text</th></tr>
     *   <tr><td>FC-INVALID-DATE</td><td>0</td><td>0</td><td>"Date is valid"</td></tr>
     *   <tr><td>FC-INSUFFICIENT-DATA</td><td>3</td><td>2507</td><td>"Insufficient"</td></tr>
     *   <tr><td>FC-BAD-DATE-VALUE</td><td>3</td><td>2508</td><td>"Datevalue error"</td></tr>
     *   <tr><td>FC-INVALID-ERA</td><td>3</td><td>2509</td><td>"Invalid Era"</td></tr>
     *   <tr><td>FC-UNSUPP-RANGE</td><td>3</td><td>2513</td><td>"Unsupp. Range"</td></tr>
     *   <tr><td>FC-INVALID-MONTH</td><td>3</td><td>2517</td><td>"Invalid month"</td></tr>
     *   <tr><td>FC-BAD-PIC-STRING</td><td>3</td><td>2518</td><td>"Bad Pic String"</td></tr>
     *   <tr><td>FC-NON-NUMERIC-DATA</td><td>3</td><td>2520</td><td>"Nonnumeric data"</td></tr>
     *   <tr><td>FC-YEAR-IN-ERA-ZERO</td><td>3</td><td>2521</td><td>"YearInEra is 0"</td></tr>
     *   <tr><td>WHEN OTHER</td><td>3</td><td>9999</td><td>"Date is invalid"</td></tr>
     * </table>
     *
     * @param dateString the date string to validate
     * @param format     COBOL-style format mask (e.g., "YYYYMMDD", "MM/DD/YYYY");
     *                   defaults to "YYYYMMDD" if null or empty, matching
     *                   CSUTLDWY.cpy line 58: WS-DATE-FORMAT VALUE 'YYYYMMDD'
     * @return a {@link DateValidationResult} populated with severity, message code,
     *         result text, tested date, and format — matching WS-MESSAGE layout
     */
    public static DateValidationResult validateDate(String dateString, String format) {
        // Default format per CSUTLDWY.cpy line 58-59: WS-DATE-FORMAT PIC X(08) VALUE 'YYYYMMDD'
        String effectiveFormat = (format == null || format.isBlank())
                ? "YYYYMMDD" : format.trim();
        String testedDate = (dateString == null) ? "" : dateString;

        // ---- FC-INSUFFICIENT-DATA (CSUTLDTC.cbl line 63) ----
        // Null or empty input has insufficient data for any format
        if (dateString == null || dateString.isBlank()) {
            return new DateValidationResult(
                    SEVERITY_ERROR, MSG_INSUFFICIENT_DATA,
                    "Insufficient", testedDate, effectiveFormat, false);
        }

        // Convert COBOL format mask to Java DateTimeFormatter pattern
        String javaPattern = convertCobolFormatToJava(effectiveFormat);
        if (javaPattern == null) {
            // ---- FC-BAD-PIC-STRING (CSUTLDTC.cbl line 68) ----
            return new DateValidationResult(
                    SEVERITY_ERROR, MSG_BAD_PIC_STRING,
                    "Bad Pic String", testedDate, effectiveFormat, false);
        }

        // Check if input is too short for the expected format
        if (dateString.length() < effectiveFormat.length()) {
            // ---- FC-INSUFFICIENT-DATA (CSUTLDTC.cbl line 63) ----
            return new DateValidationResult(
                    SEVERITY_ERROR, MSG_INSUFFICIENT_DATA,
                    "Insufficient", testedDate, effectiveFormat, false);
        }

        // Pre-parse: check for non-numeric characters in digit positions
        if (containsNonNumericInDatePositions(dateString, effectiveFormat)) {
            // ---- FC-NON-NUMERIC-DATA (CSUTLDTC.cbl line 69) ----
            return new DateValidationResult(
                    SEVERITY_ERROR, MSG_NON_NUMERIC_DATA,
                    "Nonnumeric data", testedDate, effectiveFormat, false);
        }

        // Detailed CCYYMMDD component validation from CSUTLDWY.cpy 88-level conditions
        if ("YYYYMMDD".equals(effectiveFormat)) {
            DateValidationResult componentResult =
                    validateCcyymmddComponents(dateString, effectiveFormat);
            if (componentResult != null) {
                return componentResult;
            }
        }

        // Final parse attempt using Java DateTimeFormatter
        try {
            DateTimeFormatter formatter = DateTimeFormatter.ofPattern(javaPattern);
            LocalDate.parse(dateString, formatter);
            // ---- FC-INVALID-DATE = all zeros → "Date is valid" ----
            // (CSUTLDTC.cbl line 62, 129-130)
            return new DateValidationResult(
                    SEVERITY_OK, MSG_VALID,
                    "Date is valid", testedDate, effectiveFormat, true);
        } catch (DateTimeParseException e) {
            // Use exception details for enhanced error classification
            // getMessage() provides parse failure description
            // getErrorIndex() identifies position of the parsing failure
            return classifyParseError(
                    e.getMessage(), e.getErrorIndex(),
                    testedDate, effectiveFormat);
        }
    }

    /**
     * Validates a CCYYMMDD date string using the exact rules from CSUTLDWY.cpy.
     *
     * <p>Replicates the 88-level condition validation logic:
     * <ul>
     *   <li>THIS-CENTURY VALUE 20, LAST-CENTURY VALUE 19 (lines 9-10)</li>
     *   <li>WS-VALID-MONTH VALUES 1 THROUGH 12 (lines 19-20)</li>
     *   <li>WS-31-DAY-MONTH VALUES 1, 3, 5, 7, 8, 10, 12 (lines 21-23)</li>
     *   <li>WS-FEBRUARY VALUE 2 (line 24)</li>
     *   <li>WS-VALID-DAY VALUES 1 THROUGH 31 (lines 28-29)</li>
     *   <li>WS-VALID-FEB-DAY VALUES 1 THROUGH 28 (lines 33-34)</li>
     *   <li>WS-DAY-29 VALUE 29 — February in leap years only (line 32)</li>
     * </ul>
     *
     * @param ccyymmdd date string in CCYYMMDD format, e.g., {@code "20231215"}
     * @return {@code true} if the date is valid per CSUTLDWY.cpy rules,
     *         {@code false} otherwise
     */
    public static boolean isValidCcyymmdd(String ccyymmdd) {
        // Must be non-null and exactly 8 characters
        if (ccyymmdd == null || ccyymmdd.length() != 8) {
            return false;
        }

        // Must be all numeric
        for (int i = 0; i < 8; i++) {
            if (!Character.isDigit(ccyymmdd.charAt(i))) {
                return false;
            }
        }

        // Parse components matching CSUTLDWY.cpy WS-EDIT-DATE-CCYYMMDD (lines 4-36)
        int century = Integer.parseInt(ccyymmdd.substring(0, 2));  // WS-EDIT-DATE-CC
        int year    = Integer.parseInt(ccyymmdd.substring(0, 4));  // WS-EDIT-DATE-CCYY-N
        int month   = Integer.parseInt(ccyymmdd.substring(4, 6));  // WS-EDIT-DATE-MM-N
        int day     = Integer.parseInt(ccyymmdd.substring(6, 8));  // WS-EDIT-DATE-DD-N

        // Century check: 88 THIS-CENTURY VALUE 20, 88 LAST-CENTURY VALUE 19
        // (CSUTLDWY.cpy lines 9-10)
        if (century != 19 && century != 20) {
            return false;
        }

        // Month check: 88 WS-VALID-MONTH VALUES 1 THROUGH 12
        // (CSUTLDWY.cpy lines 19-20)
        if (month < 1 || month > 12) {
            return false;
        }

        // Day must be at least 1: 88 WS-VALID-DAY VALUES 1 THROUGH 31
        // (CSUTLDWY.cpy lines 28-29)
        if (day < 1) {
            return false;
        }

        // 88 WS-31-DAY-MONTH VALUES 1, 3, 5, 7, 8, 10, 12 (lines 21-23)
        if (is31DayMonth(month)) {
            return day <= 31; // WS-DAY-31 VALUE 31
        }

        // 88 WS-FEBRUARY VALUE 2 (line 24)
        if (month == 2) {
            // 88 WS-VALID-FEB-DAY VALUES 1 THROUGH 28 (lines 33-34)
            if (day <= 28) {
                return true;
            }
            // 88 WS-DAY-29 VALUE 29 — allowed only in leap years (line 32)
            if (day == 29) {
                return isLeapYear(year);
            }
            return false; // day > 29 for February
        }

        // Remaining months (4, 6, 9, 11): 88 WS-DAY-30 VALUE 30 (line 31)
        return day <= 30;
    }

    // ========================================================================
    // Private Helper Methods
    // ========================================================================

    /**
     * Determines if a month has 31 days.
     * Mirrors CSUTLDWY.cpy 88-level: WS-31-DAY-MONTH VALUES 1, 3, 5, 7, 8, 10, 12.
     *
     * @param month month number (1-12)
     * @return {@code true} if the month has 31 days
     */
    private static boolean is31DayMonth(int month) {
        return month == 1 || month == 3 || month == 5 || month == 7
                || month == 8 || month == 10 || month == 12;
    }

    /**
     * Determines if a year is a leap year using the Gregorian calendar rule.
     * Divisible by 4, except century years not divisible by 400.
     *
     * <p>Used for February day-29 validation (CSUTLDWY.cpy WS-DAY-29 VALUE 29).
     *
     * @param year full 4-digit year (e.g., 2024)
     * @return {@code true} if the year is a leap year
     */
    private static boolean isLeapYear(int year) {
        if (year % 400 == 0) {
            return true;
        }
        if (year % 100 == 0) {
            return false;
        }
        return year % 4 == 0;
    }

    /**
     * Converts a COBOL-style date format mask to a Java DateTimeFormatter pattern.
     *
     * <p>Handles common COBOL date format tokens:
     * <ul>
     *   <li>YYYY → yyyy (4-digit year)</li>
     *   <li>YY → yy (2-digit year, only if YYYY not present)</li>
     *   <li>MM → MM (month, same in both systems)</li>
     *   <li>DD → dd (day of month)</li>
     *   <li>Literal separators (/, -, .) pass through unchanged</li>
     * </ul>
     *
     * @param cobolFormat COBOL format string (e.g., "YYYYMMDD", "MM/DD/YYYY")
     * @return Java DateTimeFormatter pattern, or {@code null} if unrecognized
     */
    private static String convertCobolFormatToJava(String cobolFormat) {
        if (cobolFormat == null || cobolFormat.isBlank()) {
            return null;
        }
        // Replace COBOL date tokens with Java DateTimeFormatter equivalents
        String result = cobolFormat;
        result = result.replace("YYYY", "yyyy");
        result = result.replace("DD", "dd");
        // If no 4-digit year was present, try 2-digit year
        if (!result.contains("yyyy")) {
            result = result.replace("YY", "yy");
        }
        // MM remains MM in Java DateTimeFormatter (month-of-year)

        // Validate the converted pattern contains year, month, and day
        boolean hasYear = result.contains("yyyy") || result.contains("yy");
        boolean hasMonth = result.contains("MM");
        boolean hasDay = result.contains("dd");
        if (!hasYear || !hasMonth || !hasDay) {
            return null;
        }
        return result;
    }

    /**
     * Checks if the date string contains non-numeric characters in positions
     * where the format mask expects numeric date components (Y, M, D).
     *
     * <p>Mirrors FC-NON-NUMERIC-DATA detection from CSUTLDTC.cbl line 69.
     *
     * @param dateString the date string to check
     * @param format     the COBOL format mask
     * @return {@code true} if non-numeric data is found in date component positions
     */
    private static boolean containsNonNumericInDatePositions(
            String dateString, String format) {
        int checkLength = Math.min(dateString.length(), format.length());
        for (int i = 0; i < checkLength; i++) {
            char formatChar = format.charAt(i);
            // Y, M, D positions in the format expect numeric digits in the date
            if (formatChar == 'Y' || formatChar == 'M' || formatChar == 'D') {
                if (!Character.isDigit(dateString.charAt(i))) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * Validates individual CCYYMMDD components and returns a specific error result
     * if any component fails. Returns {@code null} if all components are valid.
     *
     * <p>Component checks mirror CSUTLDWY.cpy 88-level conditions and
     * CSUTLDTC.cbl EVALUATE TRUE feedback code mappings.
     *
     * @param dateString      the 8+ character date string (pre-verified as numeric)
     * @param effectiveFormat the format string for populating the result
     * @return {@link DateValidationResult} for the specific error, or {@code null} if valid
     */
    private static DateValidationResult validateCcyymmddComponents(
            String dateString, String effectiveFormat) {
        if (dateString.length() < 8) {
            return null; // Length already checked by caller
        }

        int year    = Integer.parseInt(dateString.substring(0, 4));
        int century = Integer.parseInt(dateString.substring(0, 2));
        int month   = Integer.parseInt(dateString.substring(4, 6));
        int day     = Integer.parseInt(dateString.substring(6, 8));

        // FC-YEAR-IN-ERA-ZERO (CSUTLDTC.cbl line 70): year is 0000
        if (year == 0) {
            return new DateValidationResult(
                    SEVERITY_ERROR, MSG_YEAR_IN_ERA_ZERO,
                    "YearInEra is 0", dateString, effectiveFormat, false);
        }

        // FC-INVALID-ERA (CSUTLDTC.cbl line 65): century not 19 or 20
        // Mirrors CSUTLDWY.cpy: 88 THIS-CENTURY VALUE 20, 88 LAST-CENTURY VALUE 19
        if (century != 19 && century != 20) {
            return new DateValidationResult(
                    SEVERITY_ERROR, MSG_INVALID_ERA,
                    "Invalid Era", dateString, effectiveFormat, false);
        }

        // FC-INVALID-MONTH (CSUTLDTC.cbl line 67): month not in 1-12
        // Mirrors CSUTLDWY.cpy: 88 WS-VALID-MONTH VALUES 1 THROUGH 12
        if (month < 1 || month > 12) {
            return new DateValidationResult(
                    SEVERITY_ERROR, MSG_INVALID_MONTH,
                    "Invalid month", dateString, effectiveFormat, false);
        }

        // FC-BAD-DATE-VALUE (CSUTLDTC.cbl line 64): day out of valid range
        // Uses CSUTLDWY.cpy month/day 88-level conditions
        int maxDay = maxDayForMonth(month, year);
        if (day < 1 || day > maxDay) {
            return new DateValidationResult(
                    SEVERITY_ERROR, MSG_BAD_DATE_VALUE,
                    "Datevalue error", dateString, effectiveFormat, false);
        }

        // All CCYYMMDD components valid — return null to signal no error
        return null;
    }

    /**
     * Returns the maximum valid day for a given month and year.
     * Mirrors CSUTLDWY.cpy 88-level month/day conditions.
     *
     * @param month month number (1-12)
     * @param year  full 4-digit year
     * @return maximum day number (28, 29, 30, or 31)
     */
    private static int maxDayForMonth(int month, int year) {
        // WS-31-DAY-MONTH VALUES 1, 3, 5, 7, 8, 10, 12 (CSUTLDWY.cpy lines 21-23)
        if (is31DayMonth(month)) {
            return 31;
        }
        // WS-FEBRUARY VALUE 2 (CSUTLDWY.cpy line 24)
        if (month == 2) {
            // WS-VALID-FEB-DAY 1-28, WS-DAY-29 for leap years (lines 32-34)
            return isLeapYear(year) ? 29 : 28;
        }
        // Remaining months (4, 6, 9, 11): WS-DAY-30 VALUE 30 (line 31)
        return 30;
    }

    /**
     * Classifies a {@link DateTimeParseException} into the closest CEEDAYS feedback
     * code category by analyzing the exception message and error index.
     *
     * <p>Maps Java parse failures to CSUTLDTC.cbl EVALUATE TRUE conditions.
     * Uses {@link DateTimeParseException#getMessage()} for error context and
     * {@link DateTimeParseException#getErrorIndex()} for failure position.
     *
     * @param errorMessage the exception message from DateTimeParseException
     * @param errorIndex   the position in the parsed string where failure occurred
     * @param testedDate   the original date string
     * @param format       the format mask used
     * @return {@link DateValidationResult} matching the closest CEEDAYS feedback code
     */
    private static DateValidationResult classifyParseError(
            String errorMessage, int errorIndex,
            String testedDate, String format) {
        // Analyze error message for specific date component failures
        if (errorMessage != null) {
            String lowerMsg = errorMessage.toLowerCase();
            // Month-related parse error → FC-INVALID-MONTH (CSUTLDTC.cbl line 67)
            if (lowerMsg.contains("month")) {
                return new DateValidationResult(
                        SEVERITY_ERROR, MSG_INVALID_MONTH,
                        "Invalid month", testedDate, format, false);
            }
            // Day-related parse error → FC-BAD-DATE-VALUE (CSUTLDTC.cbl line 64)
            if (lowerMsg.contains("day")) {
                return new DateValidationResult(
                        SEVERITY_ERROR, MSG_BAD_DATE_VALUE,
                        "Datevalue error", testedDate, format, false);
            }
            // Range-related parse error → FC-UNSUPP-RANGE (CSUTLDTC.cbl line 66)
            if (lowerMsg.contains("valid range") || lowerMsg.contains("out of range")) {
                return new DateValidationResult(
                        SEVERITY_ERROR, MSG_UNSUPP_RANGE,
                        "Unsupp. Range", testedDate, format, false);
            }
        }

        // Use error index for positional classification
        if (errorIndex >= 0 && testedDate.length() < format.length()) {
            // Parsing failed before consuming all expected input → insufficient data
            return new DateValidationResult(
                    SEVERITY_ERROR, MSG_INSUFFICIENT_DATA,
                    "Insufficient", testedDate, format, false);
        }

        // WHEN OTHER: generic catch-all (CSUTLDTC.cbl line 147-148)
        return new DateValidationResult(
                SEVERITY_ERROR, MSG_INVALID_OTHER,
                "Date is invalid", testedDate, format, false);
    }
}
