package com.aws.carddemo.util;

import java.time.DateTimeException;
import java.time.LocalDate;
import java.util.Locale;
import java.util.OptionalLong;

import org.springframework.stereotype.Service;

/**
 * Java/Spring migration of the COBOL date-validation utility {@code CSUTLDTC}.
 *
 * <p>Origin: {@code legacy/cbl/CSUTLDTC.cbl} (source branch {@code app/cbl/CSUTLDTC.cbl}).
 * This service replaces the IBM Language Environment callable services
 * {@code CEEDAYS}/{@code CEEDATE}/{@code CEECBLDY} with deterministic positional field extraction
 * validated by {@link java.time.LocalDate}, validating a date string against a format mask and
 * returning a structured result (valid flag + severity + message + epoch-day equivalent).
 * Implements AAP &sect;0.6.9.</p>
 *
 * <p>The COBOL contract being reproduced is
 * {@code PROCEDURE DIVISION USING LS-DATE PIC X(10), LS-DATE-FORMAT PIC X(10), LS-RESULT PIC X(80)}:
 * {@code CSUTLDTC} calls {@code CEEDAYS}, then runs an {@code EVALUATE TRUE} over the feedback-code
 * 88-levels to select a 15-character result message, and finally moves the CEE severity to
 * {@code RETURN-CODE} (0 = success). {@code CSUTLDPY} ({@code EDIT-DATE-LE}, {@code IF WS-SEVERITY-N
 * = 0}) branches on <strong>{@code severity == 0} &hArr; valid</strong>, while {@code CORPT00C}
 * additionally branches on the exact CEE message number (notably {@code 2513}); both the severity
 * (0 or 3) and the message number (0, or 2507-2521) are therefore reproduced exactly.</p>
 *
 * <p>Injected wherever COBOL executed {@code CALL 'CSUTLDTC'} (AAP &sect;0.5.3): the online report
 * controller flow ({@code CORPT00C}) and {@code DateConversionSupport} (the {@code CSUTLDPY}
 * {@code EDIT-DATE-LE} step).</p>
 *
 * <p>This bean is <strong>stateless and thread-safe</strong>: it holds no mutable state and every
 * validation works entirely on local variables and immutable {@link java.time.LocalDate} values. The
 * full rationale for the mapping decisions summarized here (severity model, msg-number handling,
 * epoch-day vs. Lillian day, and the CEE feedback-code mapping) lives in
 * {@code docs/decision-log.md}.</p>
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
     * First date supported by {@code CEEDAYS} (Lillian day 1, {@code 1582-10-15}). A well-formed date
     * strictly before this maps to {@link ValidationOutcome#FC_UNSUPP_RANGE} (CEE message {@code 2513}),
     * which {@code CORPT00C} branches on. Java's proleptic calendar would otherwise accept such dates.
     */
    private static final LocalDate LILLIAN_START = LocalDate.of(1582, 10, 15);

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
     * <p>Only {@link #FC_INVALID_DATE} carries severity 0; every failure constant carries the exact
     * CEE severity {@code 3} and the exact CEE message number decoded from the {@code CSUTLDTC}
     * feedback-code 88-level tokens (for example {@code FC-INSUFFICIENT-DATA} =
     * {@code X'000309CB59C3C5C5'} &rarr; severity {@code 0x0003} = {@code 3}, message {@code 0x09CB}
     * = {@code 2507}), through {@code 2521}. These are reproduced verbatim so a consumer that branches
     * on a specific number - notably {@code CORPT00C}, which explicitly tests message {@code 2513}
     * ({@link #FC_UNSUPP_RANGE}) - behaves identically, and so the {@code WS-SEVERITY}/{@code WS-MSG-NO}
     * rendered into the 80-byte result are byte-exact. {@link #FC_INVALID_ERA} ({@code 2509}) has no
     * analog under the era-less {@code YYYYMMDD}/{@code YYYY-MM-DD} masks and is therefore unreachable
     * in practice, but is retained to mirror the COBOL 88-level one-for-one.</p>
     */
    public enum ValidationOutcome {

        /** {@code FC-INVALID-DATE} - CEE000 success (see class quirk note). */
        FC_INVALID_DATE("Date is valid", 0, 0),
        /** {@code FC-INSUFFICIENT-DATA} - feedback {@code X'0003 09CB ...'}; not enough date characters. */
        FC_INSUFFICIENT_DATA("Insufficient", 3, 2507),
        /** {@code FC-BAD-DATE-VALUE} - feedback {@code X'0003 09CC ...'}; well-formed but non-existent date. */
        FC_BAD_DATE_VALUE("Datevalue error", 3, 2508),
        /** {@code FC-INVALID-ERA} - feedback {@code X'0003 09CD ...'}; no era in the supported masks. */
        FC_INVALID_ERA("Invalid Era", 3, 2509),
        /** {@code FC-UNSUPP-RANGE} - feedback {@code X'0003 09D1 ...'}; date before 1582-10-15. */
        FC_UNSUPP_RANGE("Unsupp. Range", 3, 2513),
        /** {@code FC-INVALID-MONTH} - feedback {@code X'0003 09D5 ...'}; month component outside 1-12. */
        FC_INVALID_MONTH("Invalid month", 3, 2517),
        /** {@code FC-BAD-PIC-STRING} - feedback {@code X'0003 09D6 ...'}; unrecognized/empty picture mask. */
        FC_BAD_PIC_STRING("Bad Pic String", 3, 2518),
        /** {@code FC-NON-NUMERIC-DATA} - feedback {@code X'0003 09D8 ...'}; non-digit where a digit was expected. */
        FC_NON_NUMERIC_DATA("Nonnumeric data", 3, 2520),
        /** {@code FC-YEAR-IN-ERA-ZERO} - feedback {@code X'0003 09D9 ...'}; year component is 0000. */
        FC_YEAR_IN_ERA_ZERO("YearInEra is 0", 3, 2521),
        /**
         * {@code WHEN OTHER} - defensive fallback for any feedback condition not matched above. Carries
         * severity {@code 3} (an error) and message number {@code 0} (no canonical CEE number is
         * synthesized). With the supported masks the specific outcomes above are always selected first.
         */
        OTHER_INVALID("Date is invalid", 3, 0);

        private final String message;
        private final int severity;
        private final int msgNo;

        ValidationOutcome(String message, int severity, int msgNo) {
            this.message = message;
            this.severity = severity;
            this.msgNo = msgNo;
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
         * The CEE severity moved to {@code RETURN-CODE} for this outcome: {@code 0} for the success
         * condition and {@code 3} for every failure (the exact {@code CEEDAYS} error severity).
         * {@code CSUTLDPY} {@code EDIT-DATE-LE} branches on {@code severity == 0}.
         *
         * @return the severity (0 = valid, 3 = error)
         */
        public int severity() {
            return severity;
        }

        /**
         * The exact {@code CEEDAYS} feedback message number for this outcome ({@code WS-MSG-NO}):
         * {@code 0} for success, otherwise the number decoded from the feedback-code token
         * ({@code 2507}-{@code 2521}). {@code CORPT00C} branches on {@code 2513}.
         *
         * @return the CEE message number (0 for the success condition)
         */
        public int msgNo() {
            return msgNo;
        }
    }

    /**
     * Immutable structured result of a date validation, modeling the COBOL {@code LS-RESULT}
     * ({@code PIC X(80)}) plus the {@code RETURN-CODE} severity and the parsed date.
     *
     * @param valid           {@code true} when the date is valid ({@code severity == 0}); mirrors
     *                        the COBOL {@code IF WS-SEVERITY-N = 0} check
     * @param severity        CEE-style severity (0 = valid); moved to {@code RETURN-CODE} by COBOL
     * @param msgNo           the {@code WS-MSG-NO} value: the exact {@code CEEDAYS} feedback message
     *                        number ({@code 0}, or {@code 2507}-{@code 2521}); {@code CORPT00C}
     *                        branches on {@code 2513}
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
     * <p>Processing mirrors {@code CSUTLDTC}/{@code CEEDAYS} and selects the exact feedback outcome,
     * so both the {@code severity == 0} valid/invalid decision and the specific CEE message number
     * ({@code CORPT00C} branches on {@code 2513}) match the COBOL:</p>
     * <ol>
     *   <li>The picture mask is validated: {@code Y}/{@code M}/{@code D} components (either case) and
     *       {@code '-'}/{@code '/'} separators are recognized; a mask missing any of year, month or
     *       day, or containing any other character, yields
     *       {@link ValidationOutcome#FC_BAD_PIC_STRING} (2518).</li>
     *   <li>If the (trimmed) date has fewer characters than the (trimmed) mask, the outcome is
     *       {@link ValidationOutcome#FC_INSUFFICIENT_DATA} (2507).</li>
     *   <li>Each mask position is matched against the date: a non-digit where a digit is expected
     *       yields {@link ValidationOutcome#FC_NON_NUMERIC_DATA} (2520); a separator that does not
     *       match yields {@link ValidationOutcome#FC_BAD_DATE_VALUE} (2508).</li>
     *   <li>A month outside 1-12 yields {@link ValidationOutcome#FC_INVALID_MONTH} (2517); a year of
     *       {@code 0000} yields {@link ValidationOutcome#FC_YEAR_IN_ERA_ZERO} (2521) - a condition the
     *       proleptic {@code java.time} calendar would otherwise accept; a day that does not exist for
     *       the month/year yields {@link ValidationOutcome#FC_BAD_DATE_VALUE} (2508).</li>
     *   <li>A well-formed date before {@code 1582-10-15} (the {@code CEEDAYS} lower bound) yields
     *       {@link ValidationOutcome#FC_UNSUPP_RANGE} (2513).</li>
     *   <li>Otherwise the date is valid: {@link ValidationOutcome#FC_INVALID_DATE} (severity 0) with
     *       the parsed {@link LocalDate#toEpochDay()}.</li>
     * </ol>
     *
     * <p>The {@link DateValidationResult#formattedResult()} reproduces the 80-byte {@code LS-RESULT}
     * layout byte-for-byte (including the exact 4-digit {@code WS-SEVERITY} and {@code WS-MSG-NO}), so
     * consumers such as {@code CORPT00C}, which re-slice it at fixed offsets, remain compatible. The
     * era-less masks used by CardDemo cannot raise {@link ValidationOutcome#FC_INVALID_ERA}; that
     * mapping is documented in the decision log.</p>
     *
     * @param lsDate       the date to validate (COBOL {@code LS-DATE PIC X(10)}); trailing spaces are
     *                     trimmed before validation; {@code null} is treated as blank
     * @param lsDateFormat the picture mask (COBOL {@code LS-DATE-FORMAT PIC X(10)}), e.g.
     *                     {@code "YYYYMMDD"} or {@code "YYYY-MM-DD"}; {@code null} is treated as empty
     * @return the structured {@link DateValidationResult}; never {@code null}
     */
    public DateValidationResult validateDate(String lsDate, String lsDateFormat) {
        String date = (lsDate == null) ? "" : lsDate;
        String mask = (lsDateFormat == null) ? "" : lsDateFormat;

        // Step 1: validate the picture mask (CEEDAYS validates the picture first -> 2518).
        String trimmedMask = mask.trim();
        if (!isRecognizedMask(trimmedMask)) {
            return buildResult(ValidationOutcome.FC_BAD_PIC_STRING, OptionalLong.empty(), date, mask);
        }

        // Step 2: insufficient data -> 2507 (this also covers an empty/blank date).
        String trimmedDate = date.trim();
        if (trimmedDate.length() < trimmedMask.length()) {
            return buildResult(ValidationOutcome.FC_INSUFFICIENT_DATA, OptionalLong.empty(), date, mask);
        }

        // Step 3: positionally extract year/month/day, validating digits and literal separators.
        int year = 0;
        int month = 0;
        int day = 0;
        for (int i = 0; i < trimmedMask.length(); i++) {
            char m = trimmedMask.charAt(i);
            char c = trimmedDate.charAt(i);
            switch (m) {
                case 'Y', 'y' -> {
                    if (!isDigit(c)) {
                        return buildResult(ValidationOutcome.FC_NON_NUMERIC_DATA, OptionalLong.empty(), date, mask);
                    }
                    year = year * 10 + (c - '0');
                }
                case 'M', 'm' -> {
                    if (!isDigit(c)) {
                        return buildResult(ValidationOutcome.FC_NON_NUMERIC_DATA, OptionalLong.empty(), date, mask);
                    }
                    month = month * 10 + (c - '0');
                }
                case 'D', 'd' -> {
                    if (!isDigit(c)) {
                        return buildResult(ValidationOutcome.FC_NON_NUMERIC_DATA, OptionalLong.empty(), date, mask);
                    }
                    day = day * 10 + (c - '0');
                }
                default -> {
                    // A literal separator ('-' or '/'): the date must carry the same character here.
                    if (c != m) {
                        return buildResult(ValidationOutcome.FC_BAD_DATE_VALUE, OptionalLong.empty(), date, mask);
                    }
                }
            }
        }

        // Step 4: field-level checks, in CEEDAYS order.
        if (month < 1 || month > 12) {
            return buildResult(ValidationOutcome.FC_INVALID_MONTH, OptionalLong.empty(), date, mask);
        }
        if (year == 0) {
            // java.time's proleptic calendar would accept year 0000; CEEDAYS rejects it (2521).
            return buildResult(ValidationOutcome.FC_YEAR_IN_ERA_ZERO, OptionalLong.empty(), date, mask);
        }
        LocalDate parsed;
        try {
            parsed = LocalDate.of(year, month, day);
        } catch (DateTimeException ex) {
            // A non-existent day for the month/year (e.g. 2022-02-30, or day 00/32).
            return buildResult(ValidationOutcome.FC_BAD_DATE_VALUE, OptionalLong.empty(), date, mask);
        }

        // Step 5: range check -> 2513 (CORPT00C branches on this exact message number).
        if (parsed.isBefore(LILLIAN_START)) {
            return buildResult(ValidationOutcome.FC_UNSUPP_RANGE, OptionalLong.empty(), date, mask);
        }

        // Success: CEE000 (FC-INVALID-DATE = "Date is valid", severity 0).
        return buildResult(ValidationOutcome.FC_INVALID_DATE, OptionalLong.of(parsed.toEpochDay()), date, mask);
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
     * Reports whether {@code mask} is a recognized {@code CEEDAYS} picture: it must be non-empty,
     * contain a year, a month and a day component, and consist solely of {@code Y}/{@code M}/{@code D}
     * (either case) and {@code '-'}/{@code '/'} separators. An unrecognized mask maps to
     * {@link ValidationOutcome#FC_BAD_PIC_STRING}.
     *
     * @param mask the trimmed picture mask
     * @return {@code true} when the mask is a supported year/month/day picture
     */
    private boolean isRecognizedMask(String mask) {
        if (mask.isEmpty()) {
            return false;
        }
        boolean hasYear = false;
        boolean hasMonth = false;
        boolean hasDay = false;
        for (int i = 0; i < mask.length(); i++) {
            switch (mask.charAt(i)) {
                case 'Y', 'y' -> hasYear = true;
                case 'M', 'm' -> hasMonth = true;
                case 'D', 'd' -> hasDay = true;
                case '-', '/' -> {
                    // Recognized literal separator; contributes no date component.
                }
                default -> {
                    return false;
                }
            }
        }
        return hasYear && hasMonth && hasDay;
    }

    /**
     * Reports whether {@code c} is an ASCII digit ({@code '0'}-{@code '9'}). Used instead of
     * {@link Character#isDigit(char)} so only ASCII digits (as {@code CEEDAYS} expects) are accepted.
     *
     * @param c the character to test
     * @return {@code true} when {@code c} is an ASCII digit
     */
    private boolean isDigit(char c) {
        return c >= '0' && c <= '9';
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
        // WS-SEVERITY and WS-MSG-NO are the exact CEEDAYS feedback values (severity 0 for valid, or 3
        // with message 2507-2521 for the failure conditions). CORPT00C branches on the message number
        // (notably 2513), so this must be the real number, not a mirror of severity.
        int msgNo = outcome.msgNo();
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
