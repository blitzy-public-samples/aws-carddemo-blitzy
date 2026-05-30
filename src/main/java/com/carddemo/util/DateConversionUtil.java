package com.carddemo.util;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.Optional;

/**
 * Stateless utility providing all date and timestamp format conversions used
 * across the CardDemo application.
 *
 * <p>This class replaces the COBOL {@code CSUTLDTC.cbl} Language Environment
 * wrapper [app/cbl/CSUTLDTC.cbl] — which delegated date validation to the
 * {@code CEEDAYS} callable service and returned a severity code in an 80-byte
 * result area — with pure Java {@link java.time} APIs. It has zero internal
 * dependencies and relies solely on the {@code java.time} and {@code java.util}
 * standard libraries.
 *
 * <h2>Supported formats</h2>
 * <ul>
 *   <li><b>CCYYMMDD</b> — {@code yyyyMMdd}, the 8-digit compact form derived
 *       from {@code WS-CURDATE} in {@code app/cpy/CSDAT01Y.cpy}.</li>
 *   <li><b>MM/DD/YYYY</b> — {@code MM/dd/yyyy}, the US display form derived from
 *       {@code WS-CURDATE-MM-DD-YY} in {@code app/cpy/CSDAT01Y.cpy} (extended
 *       from the original 2-digit year to a 4-digit year per PR-11 semantics).</li>
 *   <li><b>ISO</b> — {@code yyyy-MM-dd}, the default validation mask used by
 *       COBOL {@code CSUTLDTC} callers (e.g., {@code app/cbl/COTRN02C.cbl}:
 *       {@code WS-DATE-FORMAT VALUE 'YYYY-MM-DD'}) and the layout of the ASCII
 *       fixture files.</li>
 *   <li><b>DB2 timestamp</b> — {@code yyyy-MM-dd-HH.mm.ss.SSS'0000'}, the DB2
 *       external timestamp form derived from {@code DB2-FORMAT-TS} in
 *       {@code app/cbl/CBACT04C.cbl} and emitted to {@code TRAN-ORIG-TS} and
 *       {@code TRAN-PROC-TS}.</li>
 * </ul>
 *
 * <h2>COBOL parity</h2>
 * <p>The original {@code CSUTLDTC}/{@code CEEDAYS} contract validated a date
 * against a format mask and reported a severity code (severity {@code '0000'},
 * named {@code FC-INVALID-DATE} but paradoxically meaning "Date is valid") in
 * lieu of raising an exception [app/cbl/CSUTLDTC.cbl L128-L149]. That
 * no-exception semantic is preserved here by {@link #parseLocalDateSafe} and
 * {@link #isValidDate}, which wrap {@link DateTimeParseException} in
 * {@link Optional}/{@code boolean} rather than propagating it.
 *
 * <h2>DB2 timestamp precision (PR-11)</h2>
 * <p>The COBOL {@code DB2-FORMAT-TS} field is 26 characters with a 2-digit
 * centisecond component ({@code DB2-MIL PIC 9(02)}) followed by the literal
 * {@code '0000'} [app/cbl/CBACT04C.cbl L150-L165, L613-L626]. Per PR-11, the
 * Java migration uses the pattern {@code yyyy-MM-dd-HH.mm.ss.SSS'0000'}, where
 * {@code SSS} is a 3-digit millisecond component followed by the same literal
 * {@code 0000}. This is an intentional precision upgrade (27 characters total);
 * downstream parity tests assert against the Java pattern.
 *
 * <h2>Null handling</h2>
 * <p>All conversion methods accept {@code null} (and, where a string is
 * expected, blank) input and return {@code null} gracefully, mirroring the
 * spaces-initialized COBOL working-storage fields these conversions replace.
 *
 * <h2>Invalid input handling</h2>
 * <p>The strict conversion methods ({@link #toDb2Timestamp},
 * {@link #fromDb2Timestamp}, {@link #ccyymmddToIso}, {@link #isoToCcyymmdd},
 * {@link #mmDdYyyyToIso}, {@link #isoToMmDdYyyy}) propagate
 * {@link DateTimeParseException} for malformed (non-null, non-blank) input. The
 * lenient methods ({@link #parseLocalDateSafe}, {@link #isValidDate}) never
 * throw, returning {@link Optional#empty()}/{@code false} instead.
 *
 * <h2>Thread safety</h2>
 * <p>{@link DateTimeFormatter} is immutable and thread-safe by the JSR-310
 * contract, and this class is stateless with only {@code public static} methods;
 * the formatter constants are safely shared across all threads.
 *
 * @see java.time.format.DateTimeFormatter
 */
public final class DateConversionUtil {

    private DateConversionUtil() {
        throw new UnsupportedOperationException("Utility class - do not instantiate");
    }

    /**
     * DB2 external timestamp format: {@code yyyy-MM-dd-HH.mm.ss.SSS'0000'}.
     *
     * <p>Preserves the COBOL CBACT04C {@code DB2-FORMAT-TS} layout
     * [app/cbl/CBACT04C.cbl L150-L165, L613-L626]:
     * <pre>
     *   01 DB2-FORMAT-TS PIC X(26).
     *      06 DB2-YYYY      PIC X(004).   -- "yyyy"
     *      06 DB2-STREEP-1  PIC X.        -- "-"
     *      06 DB2-MM        PIC X(002).   -- "MM"
     *      06 DB2-STREEP-2  PIC X.        -- "-"
     *      06 DB2-DD        PIC X(002).   -- "dd"
     *      06 DB2-STREEP-3  PIC X.        -- "-"
     *      06 DB2-HH        PIC X(002).   -- "HH"
     *      06 DB2-DOT-1     PIC X.        -- "."
     *      06 DB2-MIN       PIC X(002).   -- "mm"
     *      06 DB2-DOT-2     PIC X.        -- "."
     *      06 DB2-SS        PIC X(002).   -- "ss"
     *      06 DB2-DOT-3     PIC X.        -- "."
     *      06 DB2-MIL       PIC 9(002).   -- "SS" centiseconds (Java SSS=ms approximates)
     *      06 DB2-REST      PIC X(04).    -- literal "0000"
     * </pre>
     *
     * <p>Used by {@code com.carddemo.batch.InterestCalculationTasklet},
     * {@code com.carddemo.batch.TransactionPostingProcessor}, and
     * {@code com.carddemo.mapper.TransactionMapper} when emitting
     * {@code TRAN-ORIG-TS} and {@code TRAN-PROC-TS} fields.
     *
     * <p>The trailing {@code '0000'} is a literal (escaped with single quotes in
     * the pattern syntax), producing four literal {@code '0'} characters that
     * mirror the COBOL {@code DB2-REST} field value.
     *
     * <p>Required by PR-11.
     */
    public static final DateTimeFormatter DB2_TIMESTAMP_FORMATTER =
        DateTimeFormatter.ofPattern("yyyy-MM-dd-HH.mm.ss.SSS'0000'");

    /**
     * Compact CCYYMMDD format: {@code yyyyMMdd} (8 digits, no separators).
     *
     * <p>Matches the COBOL {@code WS-CURDATE} redefinition
     * {@code WS-CURDATE-N PIC 9(08)} in {@code app/cpy/CSDAT01Y.cpy}. Used by
     * compact COBOL date fields and reporting outputs.
     */
    public static final DateTimeFormatter CCYYMMDD_FORMATTER =
        DateTimeFormatter.ofPattern("yyyyMMdd");

    /**
     * US display date format: {@code MM/dd/yyyy} (with forward-slash separators).
     *
     * <p>Used by online presentation layers and statement headers. Matches the
     * COBOL {@code WS-CURDATE-MM-DD-YY} pattern in {@code app/cpy/CSDAT01Y.cpy}
     * (extended to a 4-digit year per PR-11 semantics).
     */
    public static final DateTimeFormatter MM_DD_YYYY_FORMATTER =
        DateTimeFormatter.ofPattern("MM/dd/yyyy");

    /**
     * ISO standard date format: {@code yyyy-MM-dd}.
     *
     * <p>Default format mask used by COBOL CSUTLDTC callers (e.g.,
     * {@code app/cbl/COTRN02C.cbl}: {@code WS-DATE-FORMAT VALUE 'YYYY-MM-DD'}).
     * Also the format used in ASCII fixture files (e.g.,
     * {@code app/data/ASCII/acctdata.txt} columns ACCT-OPEN-DATE,
     * ACCT-EXPIRAION-DATE).
     */
    public static final DateTimeFormatter ISO_DATE_FORMATTER =
        DateTimeFormatter.ofPattern("yyyy-MM-dd");

    /**
     * Formats a {@link LocalDateTime} as a 27-character DB2 external timestamp
     * matching the COBOL CBACT04C {@code DB2-FORMAT-TS} pattern
     * {@code yyyy-MM-dd-HH.mm.ss.SSS0000}.
     *
     * <p>Example:
     * {@code toDb2Timestamp(LocalDateTime.of(2022, 7, 18, 19, 27, 53, 123_000_000))}
     * returns {@code "2022-07-18-19.27.53.1230000"}.
     *
     * <p>Returns {@code null} for {@code null} input (graceful).
     *
     * @param dt the timestamp to format (may be {@code null})
     * @return the DB2-formatted string, or {@code null} if input is {@code null}
     */
    public static String toDb2Timestamp(LocalDateTime dt) {
        return dt == null ? null : dt.format(DB2_TIMESTAMP_FORMATTER);
    }

    /**
     * Parses a DB2-format timestamp string into a {@link LocalDateTime}.
     *
     * <p>Returns {@code null} for null/blank input. Throws
     * {@link DateTimeParseException} for malformed input (callers that cannot
     * guarantee well-formed input should validate first or wrap in try/catch).
     *
     * @param s the DB2 timestamp string
     *          (e.g., {@code "2022-07-18-19.27.53.1230000"})
     * @return the parsed {@code LocalDateTime}, or {@code null} if input is
     *         null/blank
     * @throws DateTimeParseException if the input does not match the DB2 pattern
     */
    public static LocalDateTime fromDb2Timestamp(String s) {
        if (s == null || s.isBlank()) {
            return null;
        }
        return LocalDateTime.parse(s, DB2_TIMESTAMP_FORMATTER);
    }

    /**
     * Converts a CCYYMMDD-format date string (e.g., {@code "20221121"}) to
     * ISO format (e.g., {@code "2022-11-21"}).
     *
     * @param ccyymmdd the 8-character compact date
     * @return the ISO-formatted date string, or {@code null} if input is
     *         null/blank
     * @throws DateTimeParseException if the input is not a valid CCYYMMDD date
     */
    public static String ccyymmddToIso(String ccyymmdd) {
        if (ccyymmdd == null || ccyymmdd.isBlank()) {
            return null;
        }
        return LocalDate.parse(ccyymmdd, CCYYMMDD_FORMATTER).format(ISO_DATE_FORMATTER);
    }

    /**
     * Converts an ISO-format date string (e.g., {@code "2022-11-21"}) to
     * CCYYMMDD format (e.g., {@code "20221121"}).
     *
     * @param iso the ISO-formatted date
     * @return the 8-character compact date, or {@code null} if input is
     *         null/blank
     * @throws DateTimeParseException if the input is not a valid ISO date
     */
    public static String isoToCcyymmdd(String iso) {
        if (iso == null || iso.isBlank()) {
            return null;
        }
        return LocalDate.parse(iso, ISO_DATE_FORMATTER).format(CCYYMMDD_FORMATTER);
    }

    /**
     * Converts an MM/DD/YYYY-format date string (e.g., {@code "11/21/2022"}) to
     * ISO format (e.g., {@code "2022-11-21"}).
     *
     * @param mmddyyyy the US-format date
     * @return the ISO-formatted date string, or {@code null} if input is
     *         null/blank
     * @throws DateTimeParseException if the input is not a valid US date
     */
    public static String mmDdYyyyToIso(String mmddyyyy) {
        if (mmddyyyy == null || mmddyyyy.isBlank()) {
            return null;
        }
        return LocalDate.parse(mmddyyyy, MM_DD_YYYY_FORMATTER).format(ISO_DATE_FORMATTER);
    }

    /**
     * Converts an ISO-format date string (e.g., {@code "2022-11-21"}) to
     * MM/DD/YYYY format (e.g., {@code "11/21/2022"}).
     *
     * @param iso the ISO-formatted date
     * @return the US-formatted date string, or {@code null} if input is
     *         null/blank
     * @throws DateTimeParseException if the input is not a valid ISO date
     */
    public static String isoToMmDdYyyy(String iso) {
        if (iso == null || iso.isBlank()) {
            return null;
        }
        return LocalDate.parse(iso, ISO_DATE_FORMATTER).format(MM_DD_YYYY_FORMATTER);
    }

    /**
     * Safely parses a date string using the specified format pattern, returning
     * {@link Optional#empty()} on failure instead of throwing.
     *
     * <p>Replaces the COBOL CSUTLDTC/CEEDAYS pattern that returned severity codes
     * ({@code '0000'} = valid) without raising exceptions
     * [app/cbl/CSUTLDTC.cbl L88-L149].
     *
     * <p>Examples:
     * <pre>
     *   parseLocalDateSafe("2022-11-21", "yyyy-MM-dd")  // Optional[2022-11-21]
     *   parseLocalDateSafe("BADDATE", "yyyy-MM-dd")     // Optional.empty()
     * </pre>
     *
     * @param value   the date string to parse (may be {@code null} or blank)
     * @param pattern the {@link DateTimeFormatter} pattern
     * @return an {@code Optional} containing the parsed date, or
     *         {@link Optional#empty()} if the value is null/blank, the pattern is
     *         {@code null}, or parsing fails
     */
    public static Optional<LocalDate> parseLocalDateSafe(String value, String pattern) {
        if (value == null || value.isBlank() || pattern == null) {
            return Optional.empty();
        }
        try {
            return Optional.of(LocalDate.parse(value, DateTimeFormatter.ofPattern(pattern)));
        } catch (DateTimeParseException ex) {
            return Optional.empty();
        }
    }

    /**
     * Checks whether the given date string is parseable as a {@link LocalDate}
     * using the specified format pattern.
     *
     * <p>Replaces the COBOL CSUTLDTC/CEEDAYS severity-code check: returns
     * {@code true} when severity would have been {@code '0000'}
     * ({@code FC-INVALID-DATE} in {@code app/cbl/CSUTLDTC.cbl}, which
     * paradoxically means "Date is valid").
     *
     * @param value   the date string to validate
     * @param pattern the format pattern (e.g., {@code "yyyy-MM-dd"})
     * @return {@code true} if the value parses cleanly; {@code false} otherwise
     */
    public static boolean isValidDate(String value, String pattern) {
        return parseLocalDateSafe(value, pattern).isPresent();
    }

    /**
     * Returns the current timestamp formatted as a DB2 external timestamp.
     *
     * <p>Convenience method equivalent to
     * {@code toDb2Timestamp(LocalDateTime.now())}.
     *
     * <p>COBOL equivalent (CBACT04C {@code Z-GET-DB2-FORMAT-TIMESTAMP}
     * paragraph):
     * <pre>
     *   MOVE FUNCTION CURRENT-DATE TO COBOL-TS
     *   MOVE COB-YYYY TO DB2-YYYY ... etc.
     * </pre>
     *
     * @return the current timestamp in DB2 format
     */
    public static String nowAsDb2Timestamp() {
        return LocalDateTime.now().format(DB2_TIMESTAMP_FORMATTER);
    }

    /**
     * Returns today's date formatted as an ISO date string ({@code yyyy-MM-dd}).
     *
     * @return today's date in ISO format
     */
    public static String todayAsIso() {
        return LocalDate.now().format(ISO_DATE_FORMATTER);
    }
}
