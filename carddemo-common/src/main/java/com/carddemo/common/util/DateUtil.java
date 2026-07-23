package com.carddemo.common.util;

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

    /** Result classification: severity-zero (valid) outcome. */
    public static final String RESULT_VALID            = "Date is valid";
    /** Result classification: insufficient data supplied. */
    public static final String RESULT_INSUFFICIENT     = "Insufficient";
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

    private static final int SEVERITY_VALID   = 0;
    private static final int SEVERITY_INVALID = 12;
    private static final int RESULT_WIDTH      = 15;

    /**
     * Immutable result carrying the shape of the ``CSUTLDTC`` 80-byte buffer.
     *
     * :param severity: numeric severity; ``0`` denotes a valid date.
     * :param msgNo: CEEDAYS message number; always ``0`` under 4-digit-year masks.
     * :param result: 15-character classification string.
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
     * Validates a date string against a mask, mirroring ``CSUTLDTC``.
     *
     * :param date: the date text to validate.
     * :param format: the mask literal; {@link #MASK_CCYYMMDD} or {@link #MASK_ISO}.
     * :returns: a {@link DateValidationResult}; severity ``0`` with
     *     {@link #RESULT_VALID} when the date parses under a strict Gregorian
     *     calendar, otherwise a non-zero severity with the matching
     *     classification ({@link #RESULT_BAD_PIC_STRING} for an unknown mask,
     *     {@link #RESULT_INSUFFICIENT} for null/blank input, or one of
     *     {@link #RESULT_NON_NUMERIC}, {@link #RESULT_INVALID_MONTH},
     *     {@link #RESULT_INVALID} for a malformed date).
     */
    public static DateValidationResult validateDate(String date, String format) {
        DateTimeFormatter formatter = formatterFor(format);
        if (formatter == null) {
            return new DateValidationResult(SEVERITY_INVALID, 0, RESULT_BAD_PIC_STRING, 0);
        }
        if (date == null || date.isBlank()) {
            return new DateValidationResult(SEVERITY_INVALID, 0, RESULT_INSUFFICIENT, 0);
        }
        String trimmed = date.trim();
        try {
            LocalDate parsed = LocalDate.parse(trimmed, formatter);
            return new DateValidationResult(SEVERITY_VALID, 0, RESULT_VALID, toLillian(parsed));
        } catch (DateTimeParseException ex) {
            return new DateValidationResult(
                    SEVERITY_INVALID, 0, classifyParseFailure(trimmed, format), 0);
        }
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
     * a real date that is strictly before the current date.
     *
     * :param date: the date-of-birth text.
     * :param format: the mask literal.
     * :returns: ``true`` when the date parses and precedes today; today and
     *     any future date yield ``false``.
     */
    public static boolean isValidDateOfBirth(String date, String format) {
        return parse(date, format)
                .filter(parsed -> parsed.isBefore(LocalDate.now()))
                .isPresent();
    }

    /**
     * Parses a date string under a strict Gregorian calendar without throwing.
     *
     * :param date: the date text to parse.
     * :param format: the mask literal.
     * :returns: the parsed {@link LocalDate}, or an empty {@link Optional} for
     *     an unknown mask, null/blank input, or an unparseable value.
     */
    public static Optional<LocalDate> parse(String date, String format) {
        DateTimeFormatter formatter = formatterFor(format);
        if (formatter == null || date == null || date.isBlank()) {
            return Optional.empty();
        }
        try {
            return Optional.of(LocalDate.parse(date.trim(), formatter));
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

    private static String classifyParseFailure(String date, String mask) {
        boolean iso = MASK_ISO.equals(mask);
        int[] digitPositions = iso
                ? new int[] {0, 1, 2, 3, 5, 6, 8, 9}
                : new int[] {0, 1, 2, 3, 4, 5, 6, 7};
        int monthStart = iso ? 5 : 4;

        for (int position : digitPositions) {
            if (position < date.length() && !Character.isDigit(date.charAt(position))) {
                return RESULT_NON_NUMERIC;
            }
        }
        if (iso) {
            if (date.length() != 10 || date.charAt(4) != '-' || date.charAt(7) != '-') {
                return RESULT_INVALID;
            }
        } else if (date.length() != 8) {
            return RESULT_INVALID;
        }
        int month = Integer.parseInt(date.substring(monthStart, monthStart + 2));
        if (month < 1 || month > 12) {
            return RESULT_INVALID_MONTH;
        }
        return RESULT_INVALID;
    }
}
