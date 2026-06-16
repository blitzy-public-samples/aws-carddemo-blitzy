package com.carddemo.service;

import com.carddemo.exception.ValidationException;

import org.springframework.stereotype.Service;

import java.time.DateTimeException;
import java.time.LocalDate;
import java.time.Year;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.time.format.ResolverStyle;

/**
 * Pure {@code java.time} date-validation service that replaces the legacy
 * mainframe utility {@code CSUTLDTC}.
 *
 * <h2>COBOL lineage</h2>
 * <p>In the original CardDemo system, {@code CSUTLDTC.cbl} was a tiny shim that
 * built two variable-length parameter strings (the date value and a format
 * mask such as {@code YYYY-MM-DD} or {@code YYYYMMDD}) and delegated to the
 * IBM Language&nbsp;Environment callable service {@code CEEDAYS}:</p>
 * <pre>
 *     CALL "CEEDAYS" USING WS-DATE-TO-TEST,
 *                          WS-DATE-FORMAT,
 *                          OUTPUT-LILLIAN,
 *                          FEEDBACK-CODE
 * </pre>
 * <p>{@code CEEDAYS} converts the supplied date to a Lillian day number and
 * returns a feedback token. A <strong>severity of zero</strong> meant
 * <em>"Date is valid"</em> and {@code RETURN-CODE = 0}; any non-zero severity
 * meant the date was rejected, with the feedback code mapping to a specific
 * diagnostic ("Invalid month", "Datevalue error", "Nonnumeric data", and so
 * on). Callers such as {@code COTRN02C} (transaction origination/processing
 * dates) and {@code CORPT00C} (custom report date ranges) supplied a format
 * mask and treated any non-zero severity as a validation failure.</p>
 *
 * <p>The accompanying reusable copybooks {@code CSUTLDPY.cpy} /
 * {@code CSUTLDWY.cpy} performed the same conceptual edits inline before the
 * final {@code CEEDAYS} cross-check: month must fall in {@code 01-12}
 * ({@code WS-VALID-MONTH}), the day must be legal for the month
 * ({@code WS-31-DAY-MONTH}, {@code WS-FEBRUARY}), and February&nbsp;29 is only
 * accepted in a leap year (divide-by-400 when the two-digit year is {@code 00},
 * otherwise divide-by-4).</p>
 *
 * <h2>Java translation strategy (AAP &sect;0.3.2, &sect;0.7.1)</h2>
 * <p>This service does <em>not</em> reproduce the numeric {@code CEEDAYS}
 * feedback codes. Instead it reproduces the observable <strong>outcome</strong>
 * &mdash; valid versus invalid &mdash; while preserving the supported input
 * <strong>formats</strong>, <strong>leap-year</strong> correctness, and
 * <strong>month-range</strong> checking. It achieves this with
 * {@link DateTimeFormatter} instances configured with
 * {@link ResolverStyle#STRICT}, which reject impossible dates outright
 * (for example {@code 2023-02-29} or month {@code 13}) rather than silently
 * rolling them over.</p>
 *
 * <p>The legacy century restriction (only {@code 19xx}/{@code 20xx} accepted),
 * which lived in the separate inline {@code EDIT-YEAR-CCYY} routine of
 * {@code CSUTLDPY.cpy} rather than in {@code CSUTLDTC}/{@code CEEDAYS}, is
 * intentionally <em>not</em> reproduced here: {@code CEEDAYS} itself accepts a
 * far wider range, and the AAP scopes this migration to formats, leap-year, and
 * month-range only.</p>
 *
 * <h2>Wiring</h2>
 * <p>This bean is injected wherever the legacy code performed
 * {@code EXEC CICS LINK PROGRAM('CSUTLDTC')} &mdash; namely the transaction and
 * report services. It has no collaborators, so the default constructor
 * suffices; the two {@link DateTimeFormatter}s are {@code private static final}
 * and {@link DateTimeFormatter} is immutable, making the service fully
 * thread-safe.</p>
 *
 * <h2>Error contract</h2>
 * <p>Every validation failure is surfaced as a
 * {@link com.carddemo.exception.ValidationException} (translated to
 * <strong>HTTP&nbsp;400</strong> by the global exception handler). A raw
 * {@link java.time.format.DateTimeParseException} is never propagated to
 * callers.</p>
 *
 * @see com.carddemo.exception.ValidationException
 */
@Service
public class DateValidationService {

    /**
     * Enumerates the date formats supported by the legacy {@code CSUTLDTC}
     * callers, replacing the {@code WS-DATE-FORMAT} mask string that the COBOL
     * code passed to {@code CEEDAYS}.
     *
     * <p>Each constant pairs the {@code java.time} pattern actually used for
     * parsing with the human-readable legacy mask that appeared on the 3270
     * screens, so callers and diagnostics can refer to the format the same way
     * the original programs did.</p>
     *
     * <p>The parsing patterns deliberately use {@code uuuu} (proleptic year)
     * rather than {@code yyyy} (year-of-era) so that
     * {@link ResolverStyle#STRICT} resolution works without requiring an
     * explicit era field.</p>
     */
    public enum DateFormat {

        /** Full calendar date, legacy mask {@code YYYY-MM-DD}, parsed to {@link LocalDate}. */
        ISO_DATE("uuuu-MM-dd", "YYYY-MM-DD"),

        /** Month and year only, legacy mask {@code MM/YYYY}, parsed to {@link YearMonth}. */
        MONTH_YEAR("MM/uuuu", "MM/YYYY");

        private final String pattern;
        private final String legacyMask;

        DateFormat(String pattern, String legacyMask) {
            this.pattern = pattern;
            this.legacyMask = legacyMask;
        }

        /**
         * Returns the {@code java.time} pattern used to build the
         * {@link DateTimeFormatter} for this format.
         *
         * @return the strict-resolution parse pattern (never {@code null})
         */
        public String getPattern() {
            return pattern;
        }

        /**
         * Returns the human-readable legacy mask (as the original COBOL callers
         * supplied it via {@code WS-DATE-FORMAT}), suitable for inclusion in
         * user-facing validation messages.
         *
         * @return the legacy date mask (never {@code null})
         */
        public String getLegacyMask() {
            return legacyMask;
        }
    }

    /**
     * Strict formatter for the {@code YYYY-MM-DD} format. STRICT resolution
     * rejects month values outside {@code 01-12}, days that are illegal for the
     * month, and February&nbsp;29 in non-leap years. Immutable and therefore
     * thread-safe.
     */
    private static final DateTimeFormatter ISO_DATE_FORMATTER =
            DateTimeFormatter.ofPattern(DateFormat.ISO_DATE.getPattern())
                    .withResolverStyle(ResolverStyle.STRICT);

    /**
     * Strict formatter for the {@code MM/YYYY} format, parsed to a
     * {@link YearMonth}. STRICT resolution rejects month values outside
     * {@code 01-12}. Immutable and therefore thread-safe.
     */
    private static final DateTimeFormatter MONTH_YEAR_FORMATTER =
            DateTimeFormatter.ofPattern(DateFormat.MONTH_YEAR.getPattern())
                    .withResolverStyle(ResolverStyle.STRICT);

    /** Lowest valid calendar month (January), mirroring {@code WS-VALID-MONTH} lower bound. */
    private static final int MIN_MONTH = 1;

    /** Highest valid calendar month (December), mirroring {@code WS-VALID-MONTH} upper bound. */
    private static final int MAX_MONTH = 12;

    /**
     * Validates a {@code YYYY-MM-DD} date string and returns the parsed
     * {@link LocalDate}.
     *
     * <p>This is the Java equivalent of a {@code CSUTLDTC} call with the
     * {@code YYYY-MM-DD} mask: a severity-zero ("Date is valid") result returns
     * the parsed date, while any other outcome raises a validation failure.
     * Parsing uses {@link ResolverStyle#STRICT}, so month must be {@code 01-12},
     * the day must be legal for the month, and February&nbsp;29 is accepted only
     * in leap years.</p>
     *
     * @param value      the candidate date text in {@code YYYY-MM-DD} form
     * @param fieldLabel a human-readable label for the field being validated
     *                   (for example {@code "Transaction origination date"});
     *                   used to build a clear, PII-free error message
     * @return the parsed {@link LocalDate} when {@code value} is a valid date
     * @throws ValidationException if {@code value} is {@code null}, blank, or
     *                             not a valid {@code YYYY-MM-DD} date
     */
    public LocalDate validateAndParseDate(String value, String fieldLabel) {
        String label = safeLabel(fieldLabel);
        requireSupplied(value, label);
        try {
            return LocalDate.parse(value.trim(), ISO_DATE_FORMATTER);
        } catch (DateTimeException ex) {
            // DateTimeException is the supertype of DateTimeParseException, so a
            // single catch covers both malformed text and impossible (strictly
            // resolved) dates. Never let a raw java.time exception escape to
            // callers; translate to the domain ValidationException (mapped to
            // HTTP 400). The original cause is chained for server-side
            // diagnostics only.
            throw new ValidationException(
                    label + " is not a valid date; expected format "
                            + DateFormat.ISO_DATE.getLegacyMask(),
                    ex);
        }
    }

    /**
     * Validates a {@code MM/YYYY} month-and-year string and returns the parsed
     * {@link YearMonth}.
     *
     * <p>This mirrors how {@code CORPT00C} validated the custom-report period
     * inputs. Parsing uses {@link ResolverStyle#STRICT}, so the month component
     * must fall within {@code 01-12}.</p>
     *
     * @param value      the candidate text in {@code MM/YYYY} form
     * @param fieldLabel a human-readable label for the field being validated;
     *                   used to build a clear, PII-free error message
     * @return the parsed {@link YearMonth} when {@code value} is valid
     * @throws ValidationException if {@code value} is {@code null}, blank, or
     *                             not a valid {@code MM/YYYY} value
     */
    public YearMonth validateAndParseMonthYear(String value, String fieldLabel) {
        String label = safeLabel(fieldLabel);
        requireSupplied(value, label);
        try {
            return YearMonth.parse(value.trim(), MONTH_YEAR_FORMATTER);
        } catch (DateTimeException ex) {
            throw new ValidationException(
                    label + " is not a valid month/year; expected format "
                            + DateFormat.MONTH_YEAR.getLegacyMask(),
                    ex);
        }
    }

    /**
     * Non-throwing convenience predicate that reports whether {@code value} is a
     * valid {@code YYYY-MM-DD} date.
     *
     * <p>This is the boolean counterpart of {@link #validateAndParseDate} and is
     * useful where a caller wants to branch on validity rather than handle an
     * exception. A {@code null} or blank input is considered invalid.</p>
     *
     * @param value the candidate date text in {@code YYYY-MM-DD} form
     * @return {@code true} if {@code value} is a valid {@code YYYY-MM-DD} date,
     *         {@code false} otherwise
     */
    public boolean isValidDate(String value) {
        if (value == null || value.trim().isEmpty()) {
            return false;
        }
        try {
            LocalDate.parse(value.trim(), ISO_DATE_FORMATTER);
            return true;
        } catch (DateTimeException ex) {
            return false;
        }
    }

    /**
     * Validates that {@code month} falls within the legal calendar range
     * {@code 1-12}.
     *
     * <p>This reproduces the {@code WS-VALID-MONTH} (VALUES 1 THROUGH 12) edit of
     * {@code CSUTLDWY.cpy} and the "Invalid month" feedback of {@code CSUTLDTC}.
     * It is exposed for explicit month checks (for example validating a
     * standalone month selector) independent of full date parsing.</p>
     *
     * @param month the month number to validate
     * @throws ValidationException if {@code month} is less than {@code 1} or
     *                             greater than {@code 12}
     */
    public void validateMonthRange(int month) {
        if (month < MIN_MONTH || month > MAX_MONTH) {
            throw new ValidationException(
                    "Invalid month: " + month + "; month must be a number between "
                            + MIN_MONTH + " and " + MAX_MONTH);
        }
    }

    /**
     * Reports whether {@code year} is a leap year, delegating to
     * {@link java.time.Year#isLeap(long)}.
     *
     * <p>STRICT date parsing in {@link #validateAndParseDate} already enforces
     * leap-year correctness for February&nbsp;29; this method exposes the same
     * proleptic-Gregorian rule (mirroring the divide-by-4 / divide-by-400 logic
     * of {@code CSUTLDPY.cpy}) for explicit checks and tests.</p>
     *
     * @param year the proleptic-Gregorian year to test
     * @return {@code true} if {@code year} is a leap year, {@code false} otherwise
     */
    public boolean isLeapYear(int year) {
        return Year.isLeap(year);
    }

    /**
     * Guards against a missing (null/blank) input, mirroring the legacy
     * "&hellip; must be supplied" edits in {@code CSUTLDPY.cpy}. Prevents a
     * {@link NullPointerException} from {@code LocalDate.parse(null, ...)} and
     * yields a clean {@link ValidationException} instead.
     *
     * @param value the raw input under validation
     * @param label the already-normalized field label for the message
     * @throws ValidationException if {@code value} is {@code null} or blank
     */
    private static void requireSupplied(String value, String label) {
        if (value == null || value.trim().isEmpty()) {
            throw new ValidationException(label + " must be supplied");
        }
    }

    /**
     * Normalizes a possibly-null/blank caller-supplied field label to a stable,
     * generic fallback so error messages are always meaningful.
     *
     * @param fieldLabel the caller-supplied label, possibly {@code null} or blank
     * @return the trimmed label, or {@code "Date"} when none was provided
     */
    private static String safeLabel(String fieldLabel) {
        if (fieldLabel == null || fieldLabel.trim().isEmpty()) {
            return "Date";
        }
        return fieldLabel.trim();
    }
}
