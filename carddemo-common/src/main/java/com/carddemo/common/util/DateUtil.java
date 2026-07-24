package com.carddemo.common.util;

import java.time.Clock;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.format.ResolverStyle;
import java.util.Optional;

/**
 * Stateless date-validation utility re-platformed from COBOL ``CSUTLDTC``
 * (CEEDAYS / Lillian) and the ``CSUTLDWY`` / ``CSUTLDPY`` field edits.
 *
 * <p>The class exposes the classification-string and mask literals as frozen
 * contracts, a {@link DateValidationResult} carrying the shape of the legacy
 * 80-byte result buffer (severity, message number, 15-character result, and
 * Lillian day count), and boolean convenience checks. It depends only on
 * {@code java.time} and holds no mutable state.
 */
public final class DateUtil {

    /** Result classification: severity-zero (valid) outcome; ``WS-RESULT PIC X(15)`` right-padded. */
    public static final String RESULT_VALID            = "Date is valid  ";
    /** Result classification: insufficient data supplied; ``WS-RESULT PIC X(15)`` right-padded. */
    public static final String RESULT_INSUFFICIENT     = "Insufficient   ";
    /** Result classification: bad date value. */
    public static final String RESULT_BAD_DATE_VALUE   = "Datevalue error";
    /** Result classification: invalid era. */
    public static final String RESULT_INVALID_ERA      = "Invalid Era    ";
    /** Result classification: unsupported range. */
    public static final String RESULT_UNSUPP_RANGE     = "Unsupp. Range  ";
    /** Result classification: invalid month. */
    public static final String RESULT_INVALID_MONTH    = "Invalid month  ";
    /** Result classification: bad picture (mask) string. */
    public static final String RESULT_BAD_PIC_STRING   = "Bad Pic String ";
    /** Result classification: non-numeric data. */
    public static final String RESULT_NON_NUMERIC      = "Nonnumeric data";
    /** Result classification: year-in-era is zero. */
    public static final String RESULT_YEAR_IN_ERA_ZERO = "YearInEra is 0 ";
    /** Result classification: generic invalid date. */
    public static final String RESULT_INVALID          = "Date is invalid";

    /** Mask literal for the packed ``CCYYMMDD`` wire form (CSUTLDWY default). */
    public static final String MASK_CCYYMMDD = "YYYYMMDD";
    /** Mask literal for the hyphenated ISO wire form (CICS caller form). */
    public static final String MASK_ISO      = "YYYY-MM-DD";

    private static final DateTimeFormatter FMT_CCYYMMDD =
            DateTimeFormatter.ofPattern("uuuuMMdd").withResolverStyle(ResolverStyle.STRICT);
    private static final DateTimeFormatter FMT_ISO =
            DateTimeFormatter.ofPattern("uuuu-MM-dd").withResolverStyle(ResolverStyle.STRICT);

    private static final long LILLIAN_EPOCH_DAY = LocalDate.of(1582, 10, 14).toEpochDay();

    /** CEEDAYS severity for a valid date (feedback token all-zeros / ``FC-INVALID-DATE``). */
    private static final int SEVERITY_VALID = 0;
    /**
     * CEEDAYS severity for every rejected condition. The Language Environment
     * feedback tokens (facility ``CEE``) carry severity ``3`` in their leading
     * halfword (for example ``X'0003 09CB ...'``), so a rejected date reports
     * severity 3 rather than a synthetic value.
     */
    private static final int SEVERITY_ERROR = 3;
    private static final int RESULT_WIDTH   = 15;

    /*
     * CEEDAYS message numbers decoded from the ``FEEDBACK-CODE`` tokens in
     * ``CSUTLDTC`` (the second halfword of each token). Message number ``0`` is
     * reported for a valid date.
     */
    private static final int MSG_NONE             = 0;
    private static final int MSG_INSUFFICIENT     = 2507; // FC-INSUFFICIENT-DATA  X'09CB'
    private static final int MSG_BAD_DATE_VALUE   = 2508; // FC-BAD-DATE-VALUE     X'09CC'
    private static final int MSG_INVALID_ERA      = 2509; // FC-INVALID-ERA        X'09CD'
    private static final int MSG_UNSUPP_RANGE     = 2513; // FC-UNSUPP-RANGE       X'09D1'
    private static final int MSG_INVALID_MONTH    = 2517; // FC-INVALID-MONTH      X'09D5'
    private static final int MSG_BAD_PIC_STRING   = 2518; // FC-BAD-PIC-STRING     X'09D6'
    private static final int MSG_NON_NUMERIC      = 2520; // FC-NON-NUMERIC-DATA   X'09D8'
    private static final int MSG_YEAR_IN_ERA_ZERO = 2521; // FC-YEAR-IN-ERA-ZERO   X'09D9'

    /**
     * Immutable result carrying the shape of the ``CSUTLDTC`` 80-byte buffer.
     *
     * :param severity: CEEDAYS severity; ``0`` denotes a valid date and ``3`` denotes
     *     any rejected condition (matching the ``CEE`` feedback tokens).
     * :param msgNo: CEEDAYS message number; ``0`` for a valid date, otherwise the
     *     specific ``CEE`` message number for the rejection (for example ``2513`` for
     *     an unsupported range or ``2521`` for a year-in-era of zero).
     * :param result: 15-character classification string (``WS-RESULT PIC X(15)``).
     * :param lillian: Lillian day count for a valid date, ``0`` otherwise.
     */
    public record DateValidationResult(int severity, int msgNo, String result, int lillian) {

        /**
         * Reports whether the validated date is valid.
         *
         * :returns: ``true`` when severity is zero.
         */
        public boolean isValid() {
            return severity == SEVERITY_VALID;
        }
    }

    private DateUtil() {
    }

    /**
     * Validates a date string against a mask, mirroring ``CSUTLDTC`` / CEEDAYS.
     *
     * <p>The input is not trimmed: the CEEDAYS picture is fixed-width, so embedded
     * or surrounding spaces are treated as data errors exactly as the mainframe
     * service would. A successfully parsed date is additionally range-checked
     * against the CEEDAYS supported window (Lillian day 1 = ``1582-10-15``) and
     * for a year-in-era of zero.
     *
     * :param date: the date text to validate.
     * :param format: the mask literal; {@link #MASK_CCYYMMDD} or {@link #MASK_ISO}.
     * :returns: a {@link DateValidationResult}; severity ``0`` with
     *     {@link #RESULT_VALID} when the date parses under a strict Gregorian
     *     calendar and lies within the supported range, otherwise severity ``3``
     *     with the matching classification ({@link #RESULT_BAD_PIC_STRING} for an
     *     unknown mask, {@link #RESULT_INSUFFICIENT} for null/blank or too-short
     *     input, {@link #RESULT_YEAR_IN_ERA_ZERO} for year zero,
     *     {@link #RESULT_UNSUPP_RANGE} for a date before ``1582-10-15``, or one of
     *     {@link #RESULT_NON_NUMERIC}, {@link #RESULT_INVALID_MONTH},
     *     {@link #RESULT_BAD_DATE_VALUE} for a malformed date value).
     */
    public static DateValidationResult validateDate(String date, String format) {
        DateTimeFormatter formatter = formatterFor(format);
        if (formatter == null) {
            return error(RESULT_BAD_PIC_STRING, MSG_BAD_PIC_STRING);
        }
        if (date == null || date.isBlank()) {
            return error(RESULT_INSUFFICIENT, MSG_INSUFFICIENT);
        }
        LocalDate parsed;
        try {
            parsed = LocalDate.parse(date, formatter);
        } catch (DateTimeParseException ex) {
            return classifyParseFailure(date, format);
        }
        DateValidationResult rangeError = classifyRange(parsed);
        if (rangeError != null) {
            return rangeError;
        }
        return new DateValidationResult(SEVERITY_VALID, MSG_NONE, RESULT_VALID, toLillian(parsed));
    }

    /**
     * Builds a rejected-date result carrying the CEEDAYS error severity, the
     * message number, and the 15-character classification string.
     *
     * :param result15: the 15-character ``WS-RESULT`` classification string.
     * :param msgNo: the CEEDAYS message number for the rejection.
     * :returns: a {@link DateValidationResult} with severity {@link #SEVERITY_ERROR}
     *     and a Lillian count of ``0``.
     */
    private static DateValidationResult error(String result15, int msgNo) {
        return new DateValidationResult(SEVERITY_ERROR, msgNo, result15, 0);
    }

    /**
     * Applies the CEEDAYS supported-range and year-in-era checks to a parsed date.
     *
     * :param parsed: a date that already parsed under a strict Gregorian calendar.
     * :returns: a rejected {@link DateValidationResult} when the year is zero or the
     *     date precedes ``1582-10-15``; ``null`` when the date is in range.
     */
    private static DateValidationResult classifyRange(LocalDate parsed) {
        if (parsed.getYear() == 0) {
            return error(RESULT_YEAR_IN_ERA_ZERO, MSG_YEAR_IN_ERA_ZERO);
        }
        if (parsed.toEpochDay() <= LILLIAN_EPOCH_DAY) {
            return error(RESULT_UNSUPP_RANGE, MSG_UNSUPP_RANGE);
        }
        return null;
    }

    /**
     * Validates a date string using the default {@link #MASK_CCYYMMDD} mask.
     *
     * :param date: the date text to validate.
     * :returns: a {@link DateValidationResult} as produced by
     *     {@link #validateDate(String, String)}.
     */
    public static DateValidationResult validateDate(String date) {
        return validateDate(date, MASK_CCYYMMDD);
    }

    /**
     * Reports whether a date string is valid under the supplied mask.
     *
     * :param date: the date text to validate.
     * :param format: the mask literal.
     * :returns: ``true`` when {@link #validateDate(String, String)} yields severity zero.
     */
    public static boolean isValid(String date, String format) {
        return validateDate(date, format).isValid();
    }

    /**
     * Reports whether a date string is valid under {@link #MASK_CCYYMMDD}.
     *
     * :param date: the date text to validate.
     * :returns: ``true`` when {@link #validateDate(String)} yields severity zero.
     */
    public static boolean isValid(String date) {
        return validateDate(date).isValid();
    }

    /**
     * Validates an 8-character ``CCYYMMDD`` value, mirroring the
     * ``CSUTLDPY`` field edits: strict Gregorian validity plus a century
     * restricted to ``19`` or ``20``.
     *
     * :param ccyymmdd: the 8-character date text.
     * :returns: ``true`` only when the value has exactly eight digits, a
     *     century of ``19`` or ``20``, and parses as a real calendar date.
     */
    public static boolean isValidCcyymmdd(String ccyymmdd) {
        if (ccyymmdd == null || ccyymmdd.length() != 8) {
            return false;
        }
        for (int i = 0; i < 8; i++) {
            if (!Character.isDigit(ccyymmdd.charAt(i))) {
                return false;
            }
        }
        String century = ccyymmdd.substring(0, 2);
        boolean centuryOk = century.equals("19") || century.equals("20");
        return centuryOk && validateDate(ccyymmdd, MASK_CCYYMMDD).isValid();
    }

    /**
     * Validates a date of birth, mirroring ``CSUTLDPY EDIT-DATE-OF-BIRTH``:
     * a real, in-range date that is strictly before the current date, evaluated
     * against the system clock.
     *
     * :param date: the date-of-birth text.
     * :param format: the mask literal.
     * :returns: ``true`` when the date parses, is within the CEEDAYS supported
     *     range, and precedes today; today and any future date yield ``false``.
     */
    public static boolean isValidDateOfBirth(String date, String format) {
        return isValidDateOfBirth(date, format, Clock.systemDefaultZone());
    }

    /**
     * Validates a date of birth against an injected {@link Clock}, making the
     * "before today" comparison deterministic and testable.
     *
     * :param date: the date-of-birth text.
     * :param format: the mask literal.
     * :param clock: the clock supplying "today"; must not be null.
     * :returns: ``true`` when the date parses, is within the CEEDAYS supported
     *     range, and precedes the clock's current date.
     */
    public static boolean isValidDateOfBirth(String date, String format, Clock clock) {
        return parse(date, format)
                .filter(parsed -> parsed.isBefore(LocalDate.now(clock)))
                .isPresent();
    }

    /**
     * Parses a date string under a strict Gregorian calendar without throwing.
     *
     * <p>The input is not trimmed (the CEEDAYS picture is fixed-width), and a
     * successfully parsed date must also fall within the CEEDAYS supported range
     * (Lillian day 1 = ``1582-10-15``) and have a non-zero year; otherwise an
     * empty {@link Optional} is returned, keeping this method consistent with
     * {@link #validateDate(String, String)}.
     *
     * :param date: the date text to parse.
     * :param format: the mask literal.
     * :returns: the parsed, in-range {@link LocalDate}, or an empty
     *     {@link Optional} for an unknown mask, null/blank input, an unparseable
     *     value, a year of zero, or a date before ``1582-10-15``.
     */
    public static Optional<LocalDate> parse(String date, String format) {
        DateTimeFormatter formatter = formatterFor(format);
        if (formatter == null || date == null || date.isBlank()) {
            return Optional.empty();
        }
        try {
            LocalDate parsed = LocalDate.parse(date, formatter);
            if (classifyRange(parsed) != null) {
                return Optional.empty();
            }
            return Optional.of(parsed);
        } catch (DateTimeParseException ex) {
            return Optional.empty();
        }
    }

    /**
     * Computes the CEEDAYS ``OUTPUT-LILLIAN`` day count for a date.
     *
     * :param date: the date to convert; assumed non-null.
     * :returns: the number of days since ``1582-10-14`` (so ``1582-10-15`` is ``1``).
     */
    public static int toLillian(LocalDate date) {
        return (int) (date.toEpochDay() - LILLIAN_EPOCH_DAY);
    }

    /**
     * Right-pads or truncates a classification string to the COBOL
     * ``WS-RESULT PIC X(15)`` field width.
     *
     * :param result: the classification string; may be null.
     * :returns: a string of exactly 15 characters (spaces when null,
     *     truncated when longer, right-padded with spaces when shorter).
     */
    public static String padResult15(String result) {
        if (result == null) {
            return " ".repeat(RESULT_WIDTH);
        }
        if (result.length() > RESULT_WIDTH) {
            return result.substring(0, RESULT_WIDTH);
        }
        return result + " ".repeat(RESULT_WIDTH - result.length());
    }

    private static DateTimeFormatter formatterFor(String mask) {
        if (MASK_CCYYMMDD.equals(mask)) {
            return FMT_CCYYMMDD;
        }
        if (MASK_ISO.equals(mask)) {
            return FMT_ISO;
        }
        return null;
    }

    /**
     * Classifies a strict-parse failure into the matching CEEDAYS feedback,
     * without trimming so that padded input is rejected as the mainframe would.
     *
     * :param date: the raw (untrimmed) date text that failed to parse.
     * :param mask: the mask literal used for the attempt.
     * :returns: a rejected {@link DateValidationResult} classified as insufficient,
     *     bad date value, non-numeric data, or invalid month.
     */
    private static DateValidationResult classifyParseFailure(String date, String mask) {
        boolean iso = MASK_ISO.equals(mask);
        int expectedLen = iso ? 10 : 8;

        // Fewer characters than the fixed-width picture requires -> insufficient data.
        if (date.length() < expectedLen) {
            return error(RESULT_INSUFFICIENT, MSG_INSUFFICIENT);
        }
        // More characters than the picture (including surrounding padding) -> bad value.
        if (date.length() > expectedLen) {
            return error(RESULT_BAD_DATE_VALUE, MSG_BAD_DATE_VALUE);
        }
        // The ISO picture requires literal hyphens at positions 4 and 7.
        if (iso && (date.charAt(4) != '-' || date.charAt(7) != '-')) {
            return error(RESULT_BAD_DATE_VALUE, MSG_BAD_DATE_VALUE);
        }
        // A non-digit in any digit position -> non-numeric data.
        int[] digitPositions = iso
                ? new int[] {0, 1, 2, 3, 5, 6, 8, 9}
                : new int[] {0, 1, 2, 3, 4, 5, 6, 7};
        for (int position : digitPositions) {
            if (!Character.isDigit(date.charAt(position))) {
                return error(RESULT_NON_NUMERIC, MSG_NON_NUMERIC);
            }
        }
        // A month outside 1..12 -> invalid month.
        int monthStart = iso ? 5 : 4;
        int month = Integer.parseInt(date.substring(monthStart, monthStart + 2));
        if (month < 1 || month > 12) {
            return error(RESULT_INVALID_MONTH, MSG_INVALID_MONTH);
        }
        // Numeric, correct length and valid month, but not a real calendar date
        // (for example 2023-02-30) -> bad date value.
        return error(RESULT_BAD_DATE_VALUE, MSG_BAD_DATE_VALUE);
    }
}
