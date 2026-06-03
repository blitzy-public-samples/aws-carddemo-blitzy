package com.carddemo.service;

import com.carddemo.util.DateConversionUtil;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.format.ResolverStyle;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * Date conversion and validation service replacing {@code app/cbl/CSUTLDTC.cbl}
 * (the IBM Language Environment {@code CEEDAYS} API wrapper).
 *
 * <p>This is a thin, stateless service-tier facade over the static
 * {@link com.carddemo.util.DateConversionUtil} helper. It exists so that
 * collaborators which participate in Spring dependency injection — notably
 * {@code AccountService} (validating {@code openDate}, {@code expirationDate},
 * {@code reissueDate}) and {@code CustomerService} (validating
 * {@code dateOfBirth}), plus future batch validators — can obtain
 * COBOL-compatible date validation through an injected, mockable bean rather
 * than calling a static utility directly. All heavy lifting (formatter
 * construction, DB2 timestamp emission, format inter-conversion) is delegated to
 * {@link DateConversionUtil}.
 *
 * <h2>COBOL parity ({@code CSUTLDTC} / {@code CEEDAYS})</h2>
 * <p>The original subroutine accepted {@code LS-DATE PIC X(10)} and
 * {@code LS-DATE-FORMAT PIC X(10)}, invoked {@code CEEDAYS}, and reported a
 * severity code plus a 15-character result message in {@code LS-RESULT}
 * [app/cbl/CSUTLDTC.cbl L83-L149]. A severity of {@code '0000'} — confusingly
 * named {@code FC-INVALID-DATE} in the copybook yet meaning <em>"Date is
 * valid"</em> — signalled success; any non-zero severity signalled an invalid
 * date. The {@code CEEDAYS} feedback-token branches map verbatim to the result
 * messages preserved as constants on this class
 * [app/cbl/CSUTLDTC.cbl L128-L149]:
 * <ul>
 *   <li>{@code FC-INVALID-DATE}      &rarr; {@value #MSG_VALID}</li>
 *   <li>{@code FC-INSUFFICIENT-DATA} &rarr; {@value #MSG_INSUFFICIENT}</li>
 *   <li>{@code FC-BAD-DATE-VALUE}    &rarr; {@value #MSG_DATEVALUE_ERROR}</li>
 *   <li>{@code FC-INVALID-ERA}       &rarr; {@value #MSG_INVALID_ERA}</li>
 *   <li>{@code FC-UNSUPP-RANGE}      &rarr; {@value #MSG_UNSUPP_RANGE}</li>
 *   <li>{@code FC-INVALID-MONTH}     &rarr; {@value #MSG_INVALID_MONTH}</li>
 *   <li>{@code FC-BAD-PIC-STRING}    &rarr; {@value #MSG_BAD_PIC_STRING}</li>
 *   <li>{@code FC-NON-NUMERIC-DATA}  &rarr; {@value #MSG_NON_NUMERIC_DATA}</li>
 *   <li>{@code FC-YEAR-IN-ERA-ZERO}  &rarr; {@value #MSG_YEAR_IN_ERA_ZERO}</li>
 *   <li>{@code OTHER}                &rarr; {@value #MSG_INVALID}</li>
 * </ul>
 *
 * <p>{@code CEEDAYS} performs <em>strict</em> calendar validation: a day that is
 * out of range for its month (for example, {@code 2025-02-30}) is reported as
 * {@value #MSG_DATEVALUE_ERROR}, never silently normalised. To preserve this
 * behaviour exactly, {@link #validateDate(String, String)} builds its
 * {@link DateTimeFormatter} with {@link ResolverStyle#STRICT} and a proleptic
 * year symbol ({@code uuuu}); the default {@code SMART} resolver would instead
 * clamp {@code 2025-02-30} to the last valid day of February and report a false
 * positive.
 *
 * <h2>DB2 timestamp format (PR-11)</h2>
 * <p>{@link #getCurrentDb2Timestamp()} and {@link #convertToDb2Timestamp(LocalDateTime)}
 * delegate to {@link DateConversionUtil}, which emits the 26-character DB2
 * external timestamp preserved from {@code CBACT04C} ({@code DB2-FORMAT-TS},
 * pattern {@code yyyy-MM-dd-HH.mm.ss.SS'0000'}) per PR-11.
 *
 * <h2>Threading and side effects</h2>
 * <p>This service is stateless, side-effect free, and therefore deliberately not
 * {@code @Transactional} — date validation and formatting are pure computations.
 * All collaborators are obtained via constructor injection (PR-29); the
 * {@code java.time} types used throughout are immutable and thread-safe.
 *
 * @see com.carddemo.util.DateConversionUtil
 * @since 1.0
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class DateConversionService {

    // ---------------------------------------------------------------------
    // CEEDAYS feedback messages — preserved verbatim from CSUTLDTC.cbl
    // [app/cbl/CSUTLDTC.cbl L128-L149]. Exposed as constants so callers and
    // parity tests can reference the exact COBOL strings.
    // ---------------------------------------------------------------------

    /** {@code FC-INVALID-DATE} (severity {@code '0000'}) — paradoxically means the date is valid. */
    public static final String MSG_VALID = "Date is valid";
    /** {@code FC-INSUFFICIENT-DATA} — the supplied date string was empty/too short. */
    public static final String MSG_INSUFFICIENT = "Insufficient";
    /** {@code FC-BAD-DATE-VALUE} — a calendar field (e.g. day-of-month) is out of range. */
    public static final String MSG_DATEVALUE_ERROR = "Datevalue error";
    /** {@code FC-INVALID-ERA} — era component invalid (CEEDAYS-specific; not reachable via ISO parsing). */
    public static final String MSG_INVALID_ERA = "Invalid Era";
    /** {@code FC-UNSUPP-RANGE} — date outside the supported range (CEEDAYS-specific). */
    public static final String MSG_UNSUPP_RANGE = "Unsupp. Range";
    /** {@code FC-INVALID-MONTH} — month component out of the range 1-12. */
    public static final String MSG_INVALID_MONTH = "Invalid month";
    /** {@code FC-BAD-PIC-STRING} — the format mask (picture string) was missing or unparseable. */
    public static final String MSG_BAD_PIC_STRING = "Bad Pic String";
    /** {@code FC-NON-NUMERIC-DATA} — the date string contained non-numeric characters. */
    public static final String MSG_NON_NUMERIC_DATA = "Nonnumeric data";
    /** {@code FC-YEAR-IN-ERA-ZERO} — year-within-era resolved to zero. */
    public static final String MSG_YEAR_IN_ERA_ZERO = "YearInEra is 0";
    /** {@code OTHER} (catch-all) — date is invalid for an unclassified reason. */
    public static final String MSG_INVALID = "Date is invalid";

    // ---------------------------------------------------------------------
    // Severity codes — mirror the 4-character WS-SEVERITY field. "0000" is the
    // sole success value; the non-zero values categorise the failure class.
    // ---------------------------------------------------------------------

    /** Severity reported for a valid date (COBOL {@code RETURN-CODE = 0}). */
    public static final String SEVERITY_VALID = "0000";
    /** Severity reported when the input date string is null/blank. */
    public static final String SEVERITY_INSUFFICIENT = "0010";
    /** Severity reported when the format mask is null/blank or unparseable. */
    public static final String SEVERITY_BAD_PIC_STRING = "0030";
    /** Severity reported when a well-formed mask cannot parse the supplied date. */
    public static final String SEVERITY_INVALID = "0040";

    /**
     * Immutable result of a date validation, mirroring the COBOL
     * {@code WS-MESSAGE} structure from {@code CSUTLDTC} so that downstream
     * parity tests can assert on the same fields the subroutine returned in
     * {@code LS-RESULT} [app/cbl/CSUTLDTC.cbl L42-L57].
     *
     * @param severityCode the 4-character severity ({@code "0000"} when valid),
     *                      mirroring {@code WS-SEVERITY}
     * @param resultMessage the CEEDAYS feedback message (e.g. {@code "Date is
     *                      valid"}, {@code "Invalid month"}), mirroring
     *                      {@code WS-RESULT}
     * @param valid         {@code true} when {@code severityCode} is
     *                      {@code "0000"}; a Java-friendly convenience flag
     * @param inputDate     the date string that was validated, mirroring
     *                      {@code WS-DATE}
     * @param inputFormat   the format mask that was applied, mirroring
     *                      {@code WS-DATE-FMT}
     */
    public record DateValidationResult(
            String severityCode,
            String resultMessage,
            boolean valid,
            String inputDate,
            String inputFormat) {

        /**
         * Factory for a successful validation result (severity {@code "0000"},
         * message {@code "Date is valid"}).
         *
         * @param date   the validated date string
         * @param format the format mask that was applied
         * @return a valid {@code DateValidationResult}
         */
        public static DateValidationResult valid(String date, String format) {
            return new DateValidationResult(SEVERITY_VALID, MSG_VALID, true, date, format);
        }

        /**
         * Factory for a failed validation result.
         *
         * @param severity the non-{@code "0000"} severity code
         * @param message  the CEEDAYS feedback message describing the failure
         * @param date     the date string that failed validation
         * @param format   the format mask that was applied
         * @return an invalid {@code DateValidationResult}
         */
        public static DateValidationResult invalid(String severity, String message, String date, String format) {
            return new DateValidationResult(severity, message, false, date, format);
        }
    }

    /**
     * Validates a date string against a COBOL-style format mask, returning a
     * {@link DateValidationResult} that mirrors the {@code CSUTLDTC}/{@code CEEDAYS}
     * severity-and-message contract instead of throwing.
     *
     * <p>Validation rules, preserving COBOL semantics:
     * <ol>
     *   <li>A {@code null}/blank {@code dateStr} yields severity
     *       {@value #SEVERITY_INSUFFICIENT} / {@value #MSG_INSUFFICIENT}
     *       ({@code FC-INSUFFICIENT-DATA}).</li>
     *   <li>A {@code null}/blank {@code formatMask} yields severity
     *       {@value #SEVERITY_BAD_PIC_STRING} / {@value #MSG_BAD_PIC_STRING}
     *       ({@code FC-BAD-PIC-STRING}).</li>
     *   <li>The mask is translated to a Java pattern and applied with
     *       {@link ResolverStyle#STRICT}; a parse failure is classified into the
     *       matching CEEDAYS message via {@link #inferMessageFromException}.</li>
     *   <li>An unparseable mask (rejected by {@link DateTimeFormatter#ofPattern})
     *       yields {@value #MSG_BAD_PIC_STRING}.</li>
     *   <li>Otherwise the date is valid (severity {@value #SEVERITY_VALID}).</li>
     * </ol>
     *
     * <p>This method is a pure computation: it never mutates state and never
     * propagates an exception to the caller.
     *
     * @param dateStr    the date string to validate (e.g. {@code "2025-01-15"});
     *                   may be {@code null} or blank
     * @param formatMask the COBOL format mask (e.g. {@code "YYYY-MM-DD"},
     *                   {@code "CCYYMMDD"}, {@code "MM/DD/YYYY"}); may be
     *                   {@code null} or blank
     * @return a {@link DateValidationResult} describing the outcome; never
     *         {@code null}
     */
    public DateValidationResult validateDate(String dateStr, String formatMask) {
        if (dateStr == null || dateStr.isBlank()) {
            log.debug("Date validation: insufficient data (null/blank date), mask='{}'", formatMask);
            return DateValidationResult.invalid(SEVERITY_INSUFFICIENT, MSG_INSUFFICIENT, dateStr, formatMask);
        }
        if (formatMask == null || formatMask.isBlank()) {
            log.debug("Date validation: bad picture string (null/blank mask), date='{}'", dateStr);
            return DateValidationResult.invalid(SEVERITY_BAD_PIC_STRING, MSG_BAD_PIC_STRING, dateStr, formatMask);
        }

        try {
            String javaPattern = translateCobolMask(formatMask);
            DateTimeFormatter formatter =
                DateTimeFormatter.ofPattern(javaPattern).withResolverStyle(ResolverStyle.STRICT);
            // Strict parse throws DateTimeParseException for any out-of-range or
            // malformed field; success means the date is valid (CEEDAYS '0000').
            LocalDate.parse(dateStr, formatter);
            log.debug("Date validation passed: date='{}' mask='{}'", dateStr, formatMask);
            return DateValidationResult.valid(dateStr, formatMask);
        } catch (DateTimeParseException ex) {
            String message = inferMessageFromException(ex, dateStr);
            log.debug("Date validation failed: date='{}' mask='{}' message='{}'", dateStr, formatMask, message);
            return DateValidationResult.invalid(SEVERITY_INVALID, message, dateStr, formatMask);
        } catch (IllegalArgumentException ex) {
            // DateTimeFormatter.ofPattern rejected the (translated) mask itself.
            log.debug("Date validation: unparseable mask '{}' (date='{}')", formatMask, dateStr);
            return DateValidationResult.invalid(SEVERITY_BAD_PIC_STRING, MSG_BAD_PIC_STRING, dateStr, formatMask);
        }
    }

    /**
     * Translates a COBOL date format mask into an equivalent Java
     * {@link DateTimeFormatter} pattern suitable for {@link ResolverStyle#STRICT}
     * parsing.
     *
     * <p>Mappings:
     * <ul>
     *   <li>{@code CCYY} / {@code YYYY} (and lowercase {@code yyyy}) &rarr;
     *       {@code uuuu} — the proleptic year symbol. {@code uuuu} (not the
     *       year-of-era {@code yyyy}) is mandatory under {@code STRICT}, which
     *       otherwise cannot resolve a {@link LocalDate} without an era.</li>
     *   <li>{@code DD} &rarr; {@code dd} — day-of-month (Java uses lowercase
     *       {@code d}).</li>
     *   <li>{@code MM} is left unchanged — it is already the Java month-of-year
     *       symbol.</li>
     * </ul>
     *
     * <p>Examples: {@code "YYYY-MM-DD"} &rarr; {@code "uuuu-MM-dd"};
     * {@code "CCYYMMDD"} &rarr; {@code "uuuuMMdd"}; {@code "MM/DD/YYYY"} &rarr;
     * {@code "MM/dd/uuuu"}.
     *
     * @param cobolMask the COBOL format mask (guaranteed non-blank by the caller)
     * @return the equivalent Java {@link DateTimeFormatter} pattern
     */
    private String translateCobolMask(String cobolMask) {
        return cobolMask
            .replace("CCYY", "uuuu")
            .replace("YYYY", "uuuu")
            .replace("yyyy", "uuuu")
            .replace("DD", "dd");
    }

    /**
     * Classifies a {@link DateTimeParseException} into the corresponding CEEDAYS
     * feedback message, reproducing the {@code CSUTLDTC} {@code EVALUATE} dispatch
     * [app/cbl/CSUTLDTC.cbl L128-L149].
     *
     * <p>The JDK's parse-failure messages are matched on precise field tokens to
     * avoid misclassification — note that the day-range message
     * ({@code "DayOfMonth"}) itself contains the substring {@code "Month"}, so the
     * checks are ordered and tokenised deliberately:
     * <ol>
     *   <li>{@code MonthOfYear} &rarr; {@value #MSG_INVALID_MONTH}</li>
     *   <li>{@code DayOfMonth} or {@code "Invalid date"} &rarr;
     *       {@value #MSG_DATEVALUE_ERROR}</li>
     *   <li>{@code YearOfEra} / {@code Year} &rarr; {@value #MSG_YEAR_IN_ERA_ZERO}</li>
     *   <li>a non-numeric character in the input &rarr;
     *       {@value #MSG_NON_NUMERIC_DATA}</li>
     *   <li>otherwise &rarr; {@value #MSG_INVALID}</li>
     * </ol>
     *
     * @param ex      the parse exception thrown by {@link LocalDate#parse}
     * @param dateStr the original date string (used for the non-numeric heuristic)
     * @return the CEEDAYS-equivalent feedback message
     */
    private String inferMessageFromException(DateTimeParseException ex, String dateStr) {
        String message = ex.getMessage() == null ? "" : ex.getMessage();
        if (message.contains("MonthOfYear")) {
            return MSG_INVALID_MONTH;
        }
        if (message.contains("DayOfMonth") || message.contains("Invalid date")) {
            return MSG_DATEVALUE_ERROR;
        }
        if (message.contains("YearOfEra") || message.contains("Year")) {
            return MSG_YEAR_IN_ERA_ZERO;
        }
        if (dateStr != null && dateStr.matches(".*[^0-9/-].*")) {
            return MSG_NON_NUMERIC_DATA;
        }
        return MSG_INVALID;
    }

    /**
     * Converts a {@link LocalDateTime} to the 26-character DB2 external timestamp
     * format ({@code yyyy-MM-dd-HH.mm.ss.SS'0000'}) preserved from {@code CBACT04C}
     * per PR-11, by delegating to {@link DateConversionUtil#toDb2Timestamp}.
     *
     * @param ldt the timestamp to format; may be {@code null}
     * @return the DB2-formatted timestamp string, or {@code null} if {@code ldt}
     *         is {@code null}
     */
    public String convertToDb2Timestamp(LocalDateTime ldt) {
        return DateConversionUtil.toDb2Timestamp(ldt);
    }

    /**
     * Returns the current timestamp in the 26-character DB2 external format
     * ({@code yyyy-MM-dd-HH.mm.ss.SS'0000'}) per PR-11, by delegating to
     * {@link DateConversionUtil#nowAsDb2Timestamp}.
     *
     * @return the current timestamp in DB2 format (26 characters, ending in
     *         {@code "0000"})
     */
    public String getCurrentDb2Timestamp() {
        return DateConversionUtil.nowAsDb2Timestamp();
    }

    /**
     * Parses an 8-digit {@code CCYYMMDD} date string (e.g. {@code "20250115"})
     * into a {@link LocalDate}, using {@link DateConversionUtil#CCYYMMDD_FORMATTER}.
     *
     * <p>Mirrors {@link DateConversionUtil}'s graceful null handling: a
     * {@code null} or blank input yields {@code null} rather than an exception.
     * A non-blank but malformed input propagates {@link DateTimeParseException}.
     *
     * @param ccyymmdd the compact 8-digit date string; may be {@code null}/blank
     * @return the parsed {@link LocalDate}, or {@code null} if the input is
     *         {@code null}/blank
     * @throws DateTimeParseException if a non-blank input is not a valid
     *         {@code CCYYMMDD} date
     */
    public LocalDate parseCcyymmdd(String ccyymmdd) {
        if (ccyymmdd == null || ccyymmdd.isBlank()) {
            return null;
        }
        return LocalDate.parse(ccyymmdd, DateConversionUtil.CCYYMMDD_FORMATTER);
    }

    /**
     * Formats a {@link LocalDate} as a {@code MM/dd/yyyy} US display string, using
     * {@link DateConversionUtil#MM_DD_YYYY_FORMATTER}.
     *
     * @param date the date to format; may be {@code null}
     * @return the {@code MM/dd/yyyy} string, or {@code null} if {@code date} is
     *         {@code null}
     */
    public String formatAsMmDdYyyy(LocalDate date) {
        return date == null ? null : date.format(DateConversionUtil.MM_DD_YYYY_FORMATTER);
    }

    /**
     * Formats a {@link LocalDate} as a {@code yyyy-MM-dd} ISO string, using
     * {@link DateConversionUtil#ISO_DATE_FORMATTER}.
     *
     * @param date the date to format; may be {@code null}
     * @return the {@code yyyy-MM-dd} string, or {@code null} if {@code date} is
     *         {@code null}
     */
    public String formatAsYyyyMmDd(LocalDate date) {
        return date == null ? null : date.format(DateConversionUtil.ISO_DATE_FORMATTER);
    }
}
