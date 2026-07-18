package com.aws.carddemo.util;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.format.ResolverStyle;
import java.util.Locale;
import java.util.OptionalLong;

import org.springframework.stereotype.Service;

/**
 * Java/Spring migration of the COBOL date-validation utility {@code CSUTLDTC}.
 *
 * <p>Origin: {@code legacy/cbl/CSUTLDTC.cbl} (source branch {@code app/cbl/CSUTLDTC.cbl}).
 * This service replaces the IBM Language Environment callable services
 * {@code CEEDAYS}/{@code CEEDATE}/{@code CEECBLDY} with {@link java.time.DateTimeFormatter},
 * validating a date string against a format mask and returning a structured result
 * (valid flag + severity + message + epoch-day equivalent). Implements AAP &sect;0.6.9.</p>
 *
 * <p>The COBOL contract being reproduced is
 * {@code PROCEDURE DIVISION USING LS-DATE PIC X(10), LS-DATE-FORMAT PIC X(10), LS-RESULT PIC X(80)}:
 * {@code CSUTLDTC} calls {@code CEEDAYS}, then runs an {@code EVALUATE TRUE} over the feedback-code
 * 88-levels to select a 15-character result message, and finally moves the CEE severity to
 * {@code RETURN-CODE} (0 = success). The behaviorally-critical output is
 * <strong>{@code severity == 0} &hArr; valid</strong>; both {@code CSUTLDPY} ({@code EDIT-DATE-LE},
 * {@code IF WS-SEVERITY-N = 0}) and {@code CORPT00C} branch only on that. The exact CEE
 * message-number is cosmetic.</p>
 *
 * <p>Injected wherever COBOL executed {@code CALL 'CSUTLDTC'} (AAP &sect;0.5.3): the online report
 * controller flow ({@code CORPT00C}) and {@code DateConversionSupport} (the {@code CSUTLDPY}
 * {@code EDIT-DATE-LE} step).</p>
 *
 * <p>This bean is <strong>stateless and thread-safe</strong>: it holds no mutable state and
 * {@link java.time.format.DateTimeFormatter} instances are immutable. The full rationale for the
 * mapping decisions summarized here (severity model, msg-number handling, epoch-day vs. Lillian day,
 * and the CEE feedback-code approximation) lives in {@code docs/decision-log.md}.</p>
 */
@Service
public class DateConversionService {

    /**
     * Byte 5-15 of the 80-byte {@code LS-RESULT} record: {@code FILLER PIC X(11) VALUE 'Mesg Code:'}.
     * The COBOL 10-character literal is space-padded to 11 bytes, hence the trailing space.
     */
    private static final String LITERAL_MESG_CODE = "Mesg Code: ";

    /**
     * {@code FILLER PIC X(09) VALUE 'TstDate:'} - the 8-character literal padded to 9 bytes
     * (trailing space).
     */
    private static final String LITERAL_TST_DATE = "TstDate: ";

    /** {@code FILLER PIC X(10) VALUE 'Mask used:'} - exactly 10 bytes, no trailing space. */
    private static final String LITERAL_MASK_USED = "Mask used:";

    /** Fixed width of the {@code WS-RESULT} message field ({@code PIC X(15)}). */
    private static final int MESSAGE_WIDTH = 15;

    /** Fixed width of the {@code WS-DATE} ({@code PIC X(10)}) and {@code WS-DATE-FMT} fields. */
    private static final int DATE_WIDTH = 10;

    /** Total byte length of the COBOL {@code LS-RESULT} record ({@code PIC X(80)}). */
    private static final int RESULT_WIDTH = 80;

    /**
     * Default picture mask ({@code CSUTLDWY} working-storage default) used by the single-argument
     * convenience overload for the {@code DateConversionSupport} / {@code EDIT-DATE-LE} path.
     */
    private static final String DEFAULT_MASK = "YYYYMMDD";

    /**
     * Mirror of the {@code CEEDAYS} feedback-code 88-levels evaluated by {@code CSUTLDTC}
     * (see {@code EVALUATE TRUE} in {@code A000-MAIN}). Each constant carries the exact 15-character
     * COBOL result message (trimmed of trailing pad) and a CEE-style severity.
     *
     * <p><strong>Quirk preserved:</strong> {@link #FC_INVALID_DATE} is named after the COBOL
     * 88-level {@code FC-INVALID-DATE} whose feedback token is all binary zeros ({@code CEE000},
     * SUCCESS). Despite the misleading name it is the <em>success</em> condition and maps to
     * {@code "Date is valid"} with severity 0. The name and behavior are preserved verbatim.</p>
     *
     * <p>Only {@link #FC_INVALID_DATE} carries severity 0; every failure constant carries a non-zero
     * severity (12, the conventional CEE "error" severity - a documented simplification of the real
     * LE severities, since the only value inspected downstream is {@code severity == 0} vs
     * {@code != 0}). Several constants ({@code FC_INVALID_ERA}, {@code FC_UNSUPP_RANGE},
     * {@code FC_INSUFFICIENT_DATA}, {@code FC_YEAR_IN_ERA_ZERO}) exist purely to mirror the COBOL
     * 88-levels one-for-one; the {@code java.time} proleptic parser has no equivalent condition to
     * raise them, which is acceptable because parity is defined on the valid/invalid decision.</p>
     */
    public enum ValidationOutcome {

        /** {@code FC-INVALID-DATE} - CEE000 success (see class quirk note). */
        FC_INVALID_DATE("Date is valid", 0),
        /** {@code FC-INSUFFICIENT-DATA}. */
        FC_INSUFFICIENT_DATA("Insufficient", 12),
        /** {@code FC-BAD-DATE-VALUE} - a syntactically well-formed but non-existent date. */
        FC_BAD_DATE_VALUE("Datevalue error", 12),
        /** {@code FC-INVALID-ERA}. */
        FC_INVALID_ERA("Invalid Era", 12),
        /** {@code FC-UNSUPP-RANGE}. */
        FC_UNSUPP_RANGE("Unsupp. Range", 12),
        /** {@code FC-INVALID-MONTH} - month component outside 1-12. */
        FC_INVALID_MONTH("Invalid month", 12),
        /** {@code FC-BAD-PIC-STRING} - unrecognized/empty picture mask. */
        FC_BAD_PIC_STRING("Bad Pic String", 12),
        /** {@code FC-NON-NUMERIC-DATA} - non-numeric character where a digit was expected. */
        FC_NON_NUMERIC_DATA("Nonnumeric data", 12),
        /** {@code FC-YEAR-IN-ERA-ZERO}. */
        FC_YEAR_IN_ERA_ZERO("YearInEra is 0", 12),
        /** {@code WHEN OTHER} - any other failure (including an empty/blank date string). */
        OTHER_INVALID("Date is invalid", 12);

        private final String message;
        private final int severity;

        ValidationOutcome(String message, int severity) {
            this.message = message;
            this.severity = severity;
        }

        /**
         * The exact 15-character COBOL {@code WS-RESULT} message for this outcome, trimmed of the
         * COBOL trailing space padding (the padding is re-applied when rendering {@code LS-RESULT}).
         *
         * @return the outcome message text
         */
        public String message() {
            return message;
        }

        /**
         * The CEE-style severity moved to {@code RETURN-CODE} for this outcome: 0 for the success
         * condition, non-zero for every failure. This is the only value examined by downstream
         * COBOL logic.
         *
         * @return the severity (0 = valid)
         */
        public int severity() {
            return severity;
        }
    }

    /**
     * Immutable structured result of a date validation, modeling the COBOL {@code LS-RESULT}
     * ({@code PIC X(80)}) plus the {@code RETURN-CODE} severity and the parsed date.
     *
     * @param valid           {@code true} when the date is valid ({@code severity == 0}); mirrors
     *                        the COBOL {@code IF WS-SEVERITY-N = 0} check
     * @param severity        CEE-style severity (0 = valid); moved to {@code RETURN-CODE} by COBOL
     * @param msgNo           the {@code WS-MSG-NO} value; cosmetic (the real LE message number is not
     *                        reproduced - see class/decision-log note) and mirrors {@code severity}
     * @param message         the 15-character {@code WS-RESULT} message, trimmed
     * @param formattedResult the exact 80-character {@code LS-RESULT} record, byte-for-byte
     * @param epochDay        {@link LocalDate#toEpochDay()} of the parsed date when valid, otherwise
     *                        {@link OptionalLong#empty()}
     * @param outcome         the {@link ValidationOutcome} selected (mirrors the COBOL 88-level)
     */
    public record DateValidationResult(
            boolean valid,
            int severity,
            int msgNo,
            String message,
            String formattedResult,
            OptionalLong epochDay,
            ValidationOutcome outcome) {

        /**
         * Convenience alias for {@link #valid()} preserving the {@code isValid()} naming used by
         * callers migrated from the COBOL {@code WS-SEVERITY-N = 0} test.
         *
         * @return {@code true} when the validated date is valid
         */
        public boolean isValid() {
            return valid;
        }
    }

    /**
     * Validates {@code lsDate} against {@code lsDateFormat}, reproducing
     * {@code CALL 'CSUTLDTC' USING LS-DATE, LS-DATE-FORMAT, LS-RESULT}.
     *
     * <p>Processing mirrors {@code CSUTLDTC}/{@code CEEDAYS}:</p>
     * <ol>
     *   <li>The COBOL/CEEDAYS picture mask is translated to a {@code java.time} pattern
     *       ({@code Y}&rarr;{@code u} proleptic year so {@link ResolverStyle#STRICT} rejects
     *       impossible dates without needing an era, {@code D}&rarr;{@code d}, {@code M} kept, and
     *       {@code '-'}/{@code '/'} separators preserved). An unrecognized or empty mask yields
     *       {@link ValidationOutcome#FC_BAD_PIC_STRING}.</li>
     *   <li>A {@link DateTimeFormatter} with {@code ResolverStyle.STRICT} attempts to parse the
     *       trimmed date.</li>
     *   <li>Success &rarr; {@link ValidationOutcome#FC_INVALID_DATE} (valid, severity 0) with the
     *       parsed {@link LocalDate#toEpochDay()}.</li>
     *   <li>Failure &rarr; the closest failure outcome is selected: a non-numeric character where a
     *       digit was expected &rarr; {@link ValidationOutcome#FC_NON_NUMERIC_DATA}; a month outside
     *       1-12 &rarr; {@link ValidationOutcome#FC_INVALID_MONTH}; an empty/blank date &rarr;
     *       {@link ValidationOutcome#OTHER_INVALID}; otherwise
     *       {@link ValidationOutcome#FC_BAD_DATE_VALUE}.</li>
     * </ol>
     *
     * <p>The exact CEE feedback-code selection is <em>approximated</em> - {@code java.time} cannot
     * reproduce every Language Environment nuance - but the valid/invalid decision and the
     * {@code severity == 0} semantics match the COBOL exactly (see decision log). The returned
     * {@link DateValidationResult#formattedResult()} reproduces the 80-byte {@code LS-RESULT} layout
     * byte-for-byte so consumers such as {@code CORPT00C}, which re-slice it at fixed offsets, remain
     * compatible.</p>
     *
     * @param lsDate       the date to validate (COBOL {@code LS-DATE PIC X(10)}); may be shorter and
     *                     is trimmed before parsing; {@code null} is treated as blank
     * @param lsDateFormat the picture mask (COBOL {@code LS-DATE-FORMAT PIC X(10)}), e.g.
     *                     {@code "YYYYMMDD"} or {@code "YYYY-MM-DD"}; {@code null} is treated as empty
     * @return the structured {@link DateValidationResult}; never {@code null}
     */
    public DateValidationResult validateDate(String lsDate, String lsDateFormat) {
        String date = (lsDate == null) ? "" : lsDate;
        String mask = (lsDateFormat == null) ? "" : lsDateFormat;

        // Step 1: translate and validate the picture mask (CEEDAYS validates the picture first).
        String pattern = translateMask(mask);
        if (pattern == null) {
            return buildResult(ValidationOutcome.FC_BAD_PIC_STRING, OptionalLong.empty(), date, mask);
        }

        DateTimeFormatter formatter;
        try {
            formatter = DateTimeFormatter.ofPattern(pattern, Locale.US)
                    .withResolverStyle(ResolverStyle.STRICT);
        } catch (IllegalArgumentException ex) {
            // Defensive: a translated pattern that java.time still rejects is a bad picture string.
            return buildResult(ValidationOutcome.FC_BAD_PIC_STRING, OptionalLong.empty(), date, mask);
        }

        // Step 2: an empty/blank date is the WHEN OTHER path (no numeric content to validate).
        String trimmedDate = date.trim();
        if (trimmedDate.isEmpty()) {
            return buildResult(ValidationOutcome.OTHER_INVALID, OptionalLong.empty(), date, mask);
        }

        // Step 3: strict parse. Success is CEE000 (FC-INVALID-DATE = "Date is valid").
        try {
            LocalDate parsed = LocalDate.parse(trimmedDate, formatter);
            return buildResult(ValidationOutcome.FC_INVALID_DATE,
                    OptionalLong.of(parsed.toEpochDay()), date, mask);
        } catch (DateTimeParseException ex) {
            ValidationOutcome outcome = classifyFailure(trimmedDate, mask, ex);
            return buildResult(outcome, OptionalLong.empty(), date, mask);
        }
    }

    /**
     * Convenience overload defaulting the mask to {@code "YYYYMMDD"} - the {@code CSUTLDWY}
     * working-storage default used by {@code DateConversionSupport} ({@code EDIT-DATE-LE}).
     *
     * @param lsDate the date to validate (COBOL {@code LS-DATE PIC X(10)})
     * @return the structured {@link DateValidationResult}; never {@code null}
     */
    public DateValidationResult validateDate(String lsDate) {
        return validateDate(lsDate, DEFAULT_MASK);
    }

    /**
     * Translates a COBOL/CEEDAYS picture mask into a {@code java.time} pattern.
     *
     * <p>{@code Y}/{@code y}&rarr;{@code u} (proleptic year), {@code M}/{@code m}&rarr;{@code M}
     * (month), {@code D}/{@code d}&rarr;{@code d} (day-of-month); {@code '-'} and {@code '/'}
     * separators are preserved. Trailing {@code PIC X(10)} space padding is trimmed first. The mask
     * must contain a year, a month and a day component and must consist solely of the recognized
     * characters; otherwise it is rejected.</p>
     *
     * @param mask the raw picture mask
     * @return the equivalent {@code java.time} pattern, or {@code null} when the mask is empty or
     *         unrecognized (signaling {@link ValidationOutcome#FC_BAD_PIC_STRING})
     */
    private String translateMask(String mask) {
        String trimmed = mask.trim();
        if (trimmed.isEmpty()) {
            return null;
        }
        StringBuilder pattern = new StringBuilder(trimmed.length());
        boolean hasYear = false;
        boolean hasMonth = false;
        boolean hasDay = false;
        for (int i = 0; i < trimmed.length(); i++) {
            char c = trimmed.charAt(i);
            switch (c) {
                case 'Y':
                case 'y':
                    pattern.append('u');
                    hasYear = true;
                    break;
                case 'M':
                case 'm':
                    pattern.append('M');
                    hasMonth = true;
                    break;
                case 'D':
                case 'd':
                    pattern.append('d');
                    hasDay = true;
                    break;
                case '-':
                case '/':
                    pattern.append(c);
                    break;
                default:
                    // Any other character is not a recognized CEEDAYS picture element.
                    return null;
            }
        }
        if (!hasYear || !hasMonth || !hasDay) {
            return null;
        }
        return pattern.toString();
    }

    /**
     * Chooses the failure {@link ValidationOutcome} closest to the corresponding {@code CEEDAYS}
     * feedback condition when a strict parse fails.
     *
     * @param trimmedDate the trimmed date that failed to parse
     * @param mask        the original picture mask (used to identify literal separators)
     * @param ex          the parse failure
     * @return the selected failure outcome
     */
    private ValidationOutcome classifyFailure(String trimmedDate, String mask, DateTimeParseException ex) {
        String separators = separatorsOf(mask);
        for (int i = 0; i < trimmedDate.length(); i++) {
            char c = trimmedDate.charAt(i);
            if (!Character.isDigit(c) && separators.indexOf(c) < 0) {
                return ValidationOutcome.FC_NON_NUMERIC_DATA;
            }
        }
        if (mentionsMonth(ex)) {
            return ValidationOutcome.FC_INVALID_MONTH;
        }
        return ValidationOutcome.FC_BAD_DATE_VALUE;
    }

    /**
     * Extracts the literal separator characters ({@code '-'}, {@code '/'}) present in the mask, so a
     * non-numeric-content check can distinguish an expected separator from an unexpected character.
     *
     * @param mask the picture mask
     * @return a string containing every separator character found (possibly empty)
     */
    private String separatorsOf(String mask) {
        StringBuilder separators = new StringBuilder();
        for (int i = 0; i < mask.length(); i++) {
            char c = mask.charAt(i);
            if (c == '-' || c == '/') {
                separators.append(c);
            }
        }
        return separators.toString();
    }

    /**
     * Reports whether the parse failure (or any of its causes) was raised because the month
     * component fell outside the valid 1-12 range. Detection relies on the stable
     * {@code MONTH_OF_YEAR} field name emitted by {@code java.time}.
     *
     * @param ex the parse failure
     * @return {@code true} when the failure is attributable to an out-of-range month
     */
    private boolean mentionsMonth(DateTimeParseException ex) {
        for (Throwable t = ex; t != null; t = t.getCause()) {
            String message = t.getMessage();
            if (message != null && message.contains("MonthOfYear")) {
                return true;
            }
        }
        return false;
    }

    /**
     * Assembles a {@link DateValidationResult} for the given outcome, including the byte-exact
     * 80-character {@code LS-RESULT} rendering.
     *
     * @param outcome  the selected outcome
     * @param epochDay the epoch day when valid, otherwise {@link OptionalLong#empty()}
     * @param date     the original input date (rendered into {@code WS-DATE})
     * @param mask     the original input mask (rendered into {@code WS-DATE-FMT})
     * @return the fully populated result
     */
    private DateValidationResult buildResult(ValidationOutcome outcome, OptionalLong epochDay,
            String date, String mask) {
        int severity = outcome.severity();
        // WS-MSG-NO is cosmetic (CORPT00C displays but never branches on it). The real LE message
        // number is not reproducible from java.time, so it mirrors severity - documented in the
        // decision log.
        int msgNo = severity;
        String message = outcome.message();
        String formattedResult = formatResult(severity, msgNo, message, date, mask);
        boolean valid = severity == 0;
        return new DateValidationResult(valid, severity, msgNo, message, formattedResult, epochDay, outcome);
    }

    /**
     * Renders the 80-byte COBOL {@code LS-RESULT} ({@code WS-MESSAGE}) record byte-for-byte:
     * {@code WS-SEVERITY X(4)} + {@code "Mesg Code: " X(11)} + {@code WS-MSG-NO X(4)} +
     * {@code SPACE X(1)} + {@code WS-RESULT X(15)} + {@code SPACE X(1)} + {@code "TstDate: " X(9)} +
     * {@code WS-DATE X(10)} + {@code SPACE X(1)} + {@code "Mask used:" X(10)} +
     * {@code WS-DATE-FMT X(10)} + {@code SPACE X(1)} + {@code SPACES X(3)} = 80. Severity and
     * msg-number render as 4-digit zero-padded numerics (the COBOL {@code 9(4)} redefinitions).
     *
     * @param severity the severity value ({@code WS-SEVERITY-N})
     * @param msgNo     the message number ({@code WS-MSG-NO-N})
     * @param message   the 15-character message content ({@code WS-RESULT})
     * @param date      the input date ({@code WS-DATE})
     * @param mask      the input mask ({@code WS-DATE-FMT})
     * @return the exact 80-character result record
     */
    private String formatResult(int severity, int msgNo, String message, String date, String mask) {
        StringBuilder result = new StringBuilder(RESULT_WIDTH);
        result.append(fourDigit(severity));            // WS-SEVERITY        X(4)
        result.append(LITERAL_MESG_CODE);              // FILLER 'Mesg Code:' X(11)
        result.append(fourDigit(msgNo));               // WS-MSG-NO          X(4)
        result.append(' ');                            // FILLER SPACE       X(1)
        result.append(rightPad(message, MESSAGE_WIDTH)); // WS-RESULT        X(15)
        result.append(' ');                            // FILLER SPACE       X(1)
        result.append(LITERAL_TST_DATE);               // FILLER 'TstDate:'  X(9)
        result.append(rightPad(date, DATE_WIDTH));     // WS-DATE            X(10)
        result.append(' ');                            // FILLER SPACE       X(1)
        result.append(LITERAL_MASK_USED);              // FILLER 'Mask used:' X(10)
        result.append(rightPad(mask, DATE_WIDTH));     // WS-DATE-FMT        X(10)
        result.append(' ');                            // FILLER SPACE       X(1)
        result.append("   ");                          // FILLER SPACES      X(3)
        return result.toString();
    }

    /**
     * Left-justifies {@code value} in a fixed field of {@code width} characters, space-padding on the
     * right and truncating any excess (COBOL {@code MOVE} to a shorter alphanumeric field).
     *
     * @param value the value to render ({@code null} treated as empty)
     * @param width the fixed field width
     * @return a string of exactly {@code width} characters
     */
    private String rightPad(String value, int width) {
        String text = (value == null) ? "" : value;
        if (text.length() >= width) {
            return text.substring(0, width);
        }
        StringBuilder padded = new StringBuilder(width);
        padded.append(text);
        while (padded.length() < width) {
            padded.append(' ');
        }
        return padded.toString();
    }

    /**
     * Renders a non-negative integer as a 4-digit zero-padded string, mirroring a COBOL {@code 9(4)}
     * display field (only the low four digits are retained, matching fixed-field truncation).
     *
     * @param value the value to render
     * @return a 4-character numeric string
     */
    private String fourDigit(int value) {
        int magnitude = Math.abs(value) % 10000;
        return String.format(Locale.US, "%04d", magnitude);
    }
}
