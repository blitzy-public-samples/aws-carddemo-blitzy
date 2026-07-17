/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License").
 * You may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.aws.carddemo.common.util;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.format.ResolverStyle;
import java.util.Objects;

/**
 * Foundational, dependency-free date/time utility built exclusively on {@code java.time}.
 *
 * <p>This class is the Java re-platform of three COBOL date copybooks (relocated under
 * {@code legacy/**} during migration):</p>
 * <ul>
 *   <li>{@code legacy/cpy/CSDAT01Y.cpy} &mdash; the display format masks
 *       ({@code MM/DD/YY}, {@code HH:MM:SS}, and the microsecond timestamp);</li>
 *   <li>{@code legacy/cpy/CSUTLDWY.cpy} &mdash; the working-storage validation ranges and
 *       88-level condition names (valid century, month, day, and leap-year day counts);</li>
 *   <li>{@code legacy/cpy/CSUTLDPY.cpy} &mdash; the {@code EDIT-DATE-CCYYMMDD} validation
 *       procedure, including the leap-year rule and the date-of-birth reasonableness check.</li>
 * </ul>
 *
 * <p>It additionally folds in the behavior of the Language Environment date intrinsics
 * ({@code FUNCTION INTEGER-OF-DATE}, {@code FUNCTION CURRENT-DATE}) and the
 * {@code CEEDAYS}/Lillian-day calendar validation wrapped by {@code CSUTLDTC}: the final
 * {@code EDIT-DATE-LE} step becomes a <em>strict</em> proleptic-Gregorian calendar parse.</p>
 *
 * <p>The methods here are intentionally small, message-free primitives so that
 * {@code service.DateValidationService} (the migration of {@code CSUTLDTC}) can reproduce the
 * exact COBOL validation order and per-field messages by composing them. This class depends on
 * nothing in the project &mdash; only {@code java.time} and {@code java.lang}/{@code java.util}.</p>
 *
 * <p>All members are {@code static}; the class is stateless and cannot be instantiated.</p>
 */
public final class DateUtils {

    /**
     * Display mask for {@code WS-CURDATE-MM-DD-YY} (COBOL {@code MM/DD/YY}) from
     * {@code legacy/cpy/CSDAT01Y.cpy}. Uses the proleptic-year symbol {@code uu} (last two
     * digits of the year) rather than {@code yy} to keep behavior unambiguous under a strict
     * resolver.
     */
    private static final DateTimeFormatter DATE_MM_DD_YY = DateTimeFormatter.ofPattern("MM/dd/uu");

    /**
     * Display mask for {@code WS-CURTIME-HH-MM-SS} (COBOL {@code HH:MM:SS}) from
     * {@code legacy/cpy/CSDAT01Y.cpy}. 24-hour clock.
     */
    private static final DateTimeFormatter TIME_HH_MM_SS = DateTimeFormatter.ofPattern("HH:mm:ss");

    /**
     * Display mask for {@code WS-TIMESTAMP} (COBOL {@code YYYY-MM-DD HH:MM:SS.} + a 6-digit
     * {@code WS-TIMESTAMP-TM-MS6}) from {@code legacy/cpy/CSDAT01Y.cpy}. The {@code SSSSSS}
     * fraction always renders exactly six digits (microsecond precision).
     */
    private static final DateTimeFormatter TIMESTAMP =
            DateTimeFormatter.ofPattern("uuuu-MM-dd HH:mm:ss.SSSSSS");

    /**
     * Strict {@code CCYYMMDD} calendar formatter reproducing the {@code EDIT-DATE-LE}
     * ({@code CALL 'CSUTLDTC'} &rarr; LE {@code CEEDAYS}) step from
     * {@code legacy/cpy/CSUTLDPY.cpy}. {@link ResolverStyle#STRICT} rejects impossible dates
     * such as {@code 20240230} or {@code 20230229}; the {@code uuuu} proleptic-year symbol
     * avoids requiring an era.
     */
    private static final DateTimeFormatter CCYYMMDD_STRICT =
            DateTimeFormatter.ofPattern("uuuuMMdd").withResolverStyle(ResolverStyle.STRICT);

    /**
     * Non-instantiable stateless utility.
     *
     * @throws AssertionError always &mdash; this class exposes only {@code static} members.
     */
    private DateUtils() {
        throw new AssertionError("utility class");
    }

    // ------------------------------------------------------------------------
    // Phase 2 - Formatting helpers (from CSDAT01Y.cpy)
    // ------------------------------------------------------------------------

    /**
     * Formats a date using the {@code MM/DD/YY} mask of {@code WS-CURDATE-MM-DD-YY}
     * ({@code legacy/cpy/CSDAT01Y.cpy}).
     *
     * @param date the date to format; must not be {@code null}
     * @return the date rendered as {@code MM/dd/uu}, e.g. {@code 07/19/22}
     * @throws NullPointerException if {@code date} is {@code null}
     */
    public static String formatDateMmDdYy(LocalDate date) {
        Objects.requireNonNull(date, "date must not be null");
        return date.format(DATE_MM_DD_YY);
    }

    /**
     * Formats a time using the {@code HH:MM:SS} mask of {@code WS-CURTIME-HH-MM-SS}
     * ({@code legacy/cpy/CSDAT01Y.cpy}).
     *
     * @param time the time to format; must not be {@code null}
     * @return the time rendered as {@code HH:mm:ss}, e.g. {@code 23:15:58}
     * @throws NullPointerException if {@code time} is {@code null}
     */
    public static String formatTimeHhMmSs(LocalTime time) {
        Objects.requireNonNull(time, "time must not be null");
        return time.format(TIME_HH_MM_SS);
    }

    /**
     * Formats a timestamp using the {@code WS-TIMESTAMP} mask
     * ({@code legacy/cpy/CSDAT01Y.cpy}) with six fractional-second digits.
     *
     * @param timestamp the timestamp to format; must not be {@code null}
     * @return the timestamp rendered as {@code uuuu-MM-dd HH:mm:ss.SSSSSS},
     *         e.g. {@code 2022-07-19 23:15:58.000000}
     * @throws NullPointerException if {@code timestamp} is {@code null}
     */
    public static String formatTimestamp(LocalDateTime timestamp) {
        Objects.requireNonNull(timestamp, "timestamp must not be null");
        return timestamp.format(TIMESTAMP);
    }

    /**
     * Returns the current system date formatted as {@code MM/dd/uu}.
     * Convenience for the {@code FUNCTION CURRENT-DATE} population of
     * {@code WS-CURDATE-MM-DD-YY}.
     *
     * @return today rendered as {@code MM/dd/uu}
     */
    public static String currentDateMmDdYy() {
        return formatDateMmDdYy(LocalDate.now());
    }

    /**
     * Returns the current system time formatted as {@code HH:mm:ss}.
     *
     * @return now rendered as {@code HH:mm:ss}
     */
    public static String currentTimeHhMmSs() {
        return formatTimeHhMmSs(LocalTime.now());
    }

    /**
     * Returns the current system timestamp formatted as {@code uuuu-MM-dd HH:mm:ss.SSSSSS}.
     *
     * @return now rendered as the microsecond timestamp mask
     */
    public static String currentTimestamp() {
        return formatTimestamp(LocalDateTime.now());
    }

    // ------------------------------------------------------------------------
    // Phase 3 - CCYYMMDD component validation primitives
    // (from CSUTLDWY.cpy 88-levels + CSUTLDPY.cpy EDIT-* paragraphs)
    // ------------------------------------------------------------------------

    /**
     * Reproduces the {@code THIS-CENTURY}/{@code LAST-CENTURY} 88-levels of
     * {@code legacy/cpy/CSUTLDWY.cpy}: only centuries {@code 19} and {@code 20} are accepted
     * (see {@code EDIT-YEAR-CCYY} in {@code legacy/cpy/CSUTLDPY.cpy}).
     *
     * @param cc the two-digit century component
     * @return {@code true} if {@code cc} is 19 or 20
     */
    public static boolean isValidCentury(int cc) {
        return cc == 19 || cc == 20;
    }

    /**
     * Reproduces the {@code WS-VALID-MONTH} 88-level ({@code 1 THROUGH 12}) of
     * {@code legacy/cpy/CSUTLDWY.cpy}.
     *
     * @param mm the month component
     * @return {@code true} if {@code mm} is in the range 1..12
     */
    public static boolean isValidMonth(int mm) {
        return mm >= 1 && mm <= 12;
    }

    /**
     * Reproduces the {@code WS-31-DAY-MONTH} 88-level ({@code 1,3,5,7,8,10,12}) of
     * {@code legacy/cpy/CSUTLDWY.cpy}.
     *
     * @param mm the month component
     * @return {@code true} if {@code mm} is a 31-day month
     */
    public static boolean isThirtyOneDayMonth(int mm) {
        return mm == 1 || mm == 3 || mm == 5 || mm == 7
                || mm == 8 || mm == 10 || mm == 12;
    }

    /**
     * Reproduces the {@code WS-FEBRUARY} 88-level ({@code VALUE 2}) of
     * {@code legacy/cpy/CSUTLDWY.cpy}.
     *
     * @param mm the month component
     * @return {@code true} if {@code mm} is February (2)
     */
    public static boolean isFebruary(int mm) {
        return mm == 2;
    }

    /**
     * Reproduces the {@code WS-VALID-DAY} 88-level ({@code 1 THROUGH 31}) of
     * {@code legacy/cpy/CSUTLDWY.cpy}.
     *
     * @param dd the day component
     * @return {@code true} if {@code dd} is in the range 1..31
     */
    public static boolean isValidDay(int dd) {
        return dd >= 1 && dd <= 31;
    }

    /**
     * Reproduces the COBOL leap-year rule from {@code EDIT-DAY-MONTH-YEAR} in
     * {@code legacy/cpy/CSUTLDPY.cpy} lines ~243-256: the divisor is {@code 400} when the
     * two-digit {@code YY} part is {@code 00} (a century year) else {@code 4}, and the year is
     * a leap year when {@code CCYY MOD divisor = 0}. This is exactly the proleptic-Gregorian
     * rule that {@code java.time} uses (equivalent to {@link java.time.Year#isLeap(long)}); the
     * explicit formula is retained here as the primary implementation for traceability.
     *
     * @param ccyy the full four-digit year
     * @return {@code true} if {@code ccyy} is a leap year
     */
    public static boolean isLeapYear(int ccyy) {
        return (ccyy % 100 == 0) ? (ccyy % 400 == 0) : (ccyy % 4 == 0);
    }

    /**
     * Reproduces the combination checks of {@code EDIT-DAY-MONTH-YEAR}
     * ({@code legacy/cpy/CSUTLDPY.cpy} lines 209-272), in the exact COBOL order and with the
     * same short-circuit behavior:
     * <ol>
     *   <li>a non-31-day month cannot have day 31
     *       (<em>Cannot have 31 days in this month</em>);</li>
     *   <li>February cannot have day 30
     *       (<em>Cannot have 30 days in this month</em>);</li>
     *   <li>February 29 is only valid in a leap year
     *       (<em>Not a leap year. Cannot have 29 days in this month</em>).</li>
     * </ol>
     *
     * <p>This class stays message-free: the specific COBOL message per branch is emitted by
     * {@code DateValidationService}, which may instead compose the individual primitives
     * ({@link #isThirtyOneDayMonth(int)}, {@link #isFebruary(int)}, {@link #isLeapYear(int)}) to
     * distinguish the failing branch. This method assumes {@code mm} and {@code dd} have already
     * passed the individual range checks ({@code EDIT-MONTH}, {@code EDIT-DAY}).</p>
     *
     * @param ccyy the full four-digit year (used only for the February-29 leap-year test)
     * @param mm   the month component
     * @param dd   the day component
     * @return {@code true} if the day is admissible for the given month and year
     */
    public static boolean isValidDayForMonth(int ccyy, int mm, int dd) {
        // Check 1: NOT WS-31-DAY-MONTH AND WS-DAY-31 (CSUTLDPY.cpy L213-226).
        if (!isThirtyOneDayMonth(mm) && dd == 31) {
            return false;
        }
        // Check 2: WS-FEBRUARY AND WS-DAY-30 (CSUTLDPY.cpy L228-241).
        if (isFebruary(mm) && dd == 30) {
            return false;
        }
        // Check 3: WS-FEBRUARY AND WS-DAY-29 in a non-leap year (CSUTLDPY.cpy L243-272).
        if (isFebruary(mm) && dd == 29 && !isLeapYear(ccyy)) {
            return false;
        }
        return true;
    }

    // ------------------------------------------------------------------------
    // Phase 4 - Strict calendar parse + date-of-birth reasonableness
    // (from EDIT-DATE-LE and EDIT-DATE-OF-BIRTH in CSUTLDPY.cpy)
    // ------------------------------------------------------------------------

    /**
     * Tests whether the supplied string is a valid {@code CCYYMMDD} calendar date, reproducing
     * the final {@code EDIT-DATE-LE} step ({@code CALL 'CSUTLDTC'} &rarr; LE {@code CEEDAYS}) of
     * {@code legacy/cpy/CSUTLDPY.cpy}. The input must be exactly eight numeric digits (mirroring
     * the COBOL blank/non-numeric guards); it is then subjected to a strict proleptic-Gregorian
     * parse, so impossible dates such as {@code 20240230} or {@code 20230229} are rejected.
     *
     * <p>Note: this does <strong>not</strong> apply the 19/20 century restriction &mdash;
     * {@code java.time} accepts any year. Callers that require the COBOL century gate should use
     * {@link #isValidCcyyMmDdWithCenturyRule(String)} or compose {@link #isValidCentury(int)}.</p>
     *
     * @param ccyymmdd the candidate date; may be {@code null}
     * @return {@code true} if {@code ccyymmdd} is eight digits and a real calendar date
     */
    public static boolean isValidCcyyMmDd(String ccyymmdd) {
        if (ccyymmdd == null || !isEightDigits(ccyymmdd)) {
            return false;
        }
        try {
            LocalDate.parse(ccyymmdd, CCYYMMDD_STRICT);
            return true;
        } catch (DateTimeParseException ex) {
            return false;
        }
    }

    /**
     * Parses a {@code CCYYMMDD} string into a {@link LocalDate} using the strict calendar rules
     * of {@code EDIT-DATE-LE} ({@code legacy/cpy/CSUTLDPY.cpy}). The input must be exactly eight
     * numeric digits and denote a real calendar date.
     *
     * @param ccyymmdd the date string in {@code CCYYMMDD} form; must not be {@code null}
     * @return the parsed {@link LocalDate}
     * @throws NullPointerException   if {@code ccyymmdd} is {@code null}
     * @throws DateTimeParseException if {@code ccyymmdd} is not eight numeric digits or is not a
     *                                valid calendar date; the caller (the date-validation
     *                                service) maps this to the COBOL invalid-date outcome
     */
    public static LocalDate parseCcyyMmDd(String ccyymmdd) {
        Objects.requireNonNull(ccyymmdd, "ccyymmdd must not be null");
        if (!isEightDigits(ccyymmdd)) {
            // Guard before the strict parse so adjacent-value parsing cannot partially succeed
            // on malformed input; mirrors the COBOL numeric/length edits.
            throw new DateTimeParseException("Date must be 8 numeric digits (CCYYMMDD)", ccyymmdd, 0);
        }
        return LocalDate.parse(ccyymmdd, CCYYMMDD_STRICT);
    }

    /**
     * Tests whether the supplied string is a valid {@code CCYYMMDD} calendar date <em>and</em>
     * carries a century of 19 or 20, combining the strict {@code EDIT-DATE-LE} parse with the
     * {@code EDIT-YEAR-CCYY} century restriction (the COBOL rejects other centuries before the LE
     * step). Both gates live in {@code legacy/cpy/CSUTLDPY.cpy}.
     *
     * @param ccyymmdd the candidate date; may be {@code null}
     * @return {@code true} if {@code ccyymmdd} is a real calendar date with century 19 or 20
     */
    public static boolean isValidCcyyMmDdWithCenturyRule(String ccyymmdd) {
        if (!isValidCcyyMmDd(ccyymmdd)) {
            return false;
        }
        int cc = (ccyymmdd.charAt(0) - '0') * 10 + (ccyymmdd.charAt(1) - '0');
        return isValidCentury(cc);
    }

    /**
     * Reproduces the date-of-birth reasonableness check of {@code EDIT-DATE-OF-BIRTH}
     * ({@code legacy/cpy/CSUTLDPY.cpy} lines 341-369). The COBOL accepts the date only when
     * {@code WS-CURRENT-DATE-BINARY > WS-EDIT-DATE-BINARY} (strictly greater), i.e. the date of
     * birth must be <strong>strictly before</strong> today; a date of birth equal to today is
     * <strong>invalid</strong>. {@code FUNCTION INTEGER-OF-DATE} corresponds to
     * {@link LocalDate#toEpochDay()}, and {@code today > dob} is equivalent to
     * {@link LocalDate#isBefore(java.time.chrono.ChronoLocalDate)}.
     *
     * @param dob the date of birth; must not be {@code null}
     * @return {@code true} if {@code dob} is strictly before the current system date
     * @throws NullPointerException if {@code dob} is {@code null}
     */
    public static boolean isDateOfBirthValid(LocalDate dob) {
        Objects.requireNonNull(dob, "dob must not be null");
        return dob.isBefore(LocalDate.now());
    }

    /**
     * String overload of {@link #isDateOfBirthValid(LocalDate)}: strict-parses the
     * {@code CCYYMMDD} value and applies the same strictly-before-today rule. Returns
     * {@code false} for {@code null}, malformed, or non-calendar input (mirroring the COBOL
     * edits that would have rejected the value before the reasonableness check).
     *
     * @param ccyymmdd the date of birth in {@code CCYYMMDD} form; may be {@code null}
     * @return {@code true} if the value is a real calendar date strictly before today
     */
    public static boolean isDateOfBirthValid(String ccyymmdd) {
        if (ccyymmdd == null || !isEightDigits(ccyymmdd)) {
            return false;
        }
        try {
            return isDateOfBirthValid(LocalDate.parse(ccyymmdd, CCYYMMDD_STRICT));
        } catch (DateTimeParseException ex) {
            return false;
        }
    }

    /**
     * Returns the count of days from the epoch (1970-01-01) for the given date, the
     * {@code java.time} analogue of the COBOL {@code FUNCTION INTEGER-OF-DATE} (a Lillian-style
     * day count) used by {@code EDIT-DATE-OF-BIRTH}. Only differences of two such counts are
     * meaningful, exactly as in the COBOL comparison, so the epoch origin is immaterial.
     *
     * @param date the date to convert; must not be {@code null}
     * @return the number of days from the epoch (may be negative for dates before 1970-01-01)
     * @throws NullPointerException if {@code date} is {@code null}
     */
    public static long toEpochDay(LocalDate date) {
        Objects.requireNonNull(date, "date must not be null");
        return date.toEpochDay();
    }

    // ------------------------------------------------------------------------
    // Internal helpers
    // ------------------------------------------------------------------------

    /**
     * Tests whether the string is exactly eight ASCII digits. Reproduces the COBOL numeric and
     * length edits that precede calendar validation (the value redefines {@code PIC 9(8)}).
     *
     * @param value the string to test; must not be {@code null}
     * @return {@code true} if {@code value} has length 8 and every character is {@code '0'..'9'}
     */
    private static boolean isEightDigits(String value) {
        if (value.length() != 8) {
            return false;
        }
        for (int i = 0; i < 8; i++) {
            char c = value.charAt(i);
            if (c < '0' || c > '9') {
                return false;
            }
        }
        return true;
    }
}
