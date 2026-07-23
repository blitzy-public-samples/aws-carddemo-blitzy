package com.aws.carddemo.util;

import java.time.DateTimeException;
import java.time.LocalDate;
import java.util.Set;

import org.springframework.stereotype.Component;

/**
 * Java/Spring migration of the reusable COBOL date-validation copybooks {@code CSUTLDPY}
 * (procedure division) and {@code CSUTLDWY} (working storage), merged into a single helper.
 *
 * <p>Origin: {@code legacy/cpy/CSUTLDPY.cpy} (date-validation paragraphs) and
 * {@code legacy/cpy/CSUTLDWY.cpy} (date fields, 88-level constants, per-field validity flags,
 * and the 80-byte result structure); source branch {@code app/cpy/CSUTLDPY.cpy} and
 * {@code app/cpy/CSUTLDWY.cpy}. This helper validates an 8-digit {@code CCYYMMDD} date and
 * produces per-field validity flags plus a human-readable error message, implementing the
 * support side of AAP &sect;0.6.9.</p>
 *
 * <p>The COBOL contract being reproduced is the paragraph range performed as
 * {@code PERFORM EDIT-DATE-CCYYMMDD THRU EDIT-DATE-CCYYMMDD-EXIT}. Each numbered paragraph
 * becomes a method here, preserving the original control flow one-for-one (migration rule,
 * &sect;0.1.2 / &sect;0.3.3): year &rarr; month &rarr; day &rarr; cross-field &rarr; the final
 * Language-Environment check. The final calendar check ({@code EDIT-DATE-LE}, which the COBOL
 * performed via {@code CALL 'CSUTLDTC'}) is delegated to {@link DateConversionService}, injected
 * by constructor to mirror that {@code CALL}.</p>
 *
 * <p><strong>Behavioral quirks preserved verbatim</strong> (no &quot;improvements&quot;, per
 * &sect;0.2.2): only centuries {@code 19} and {@code 20} are accepted; the leap-year test uses the
 * COBOL divisor rule ({@code 400} when the two-digit year is {@code 00}, otherwise {@code 4}); the
 * first failing edit wins the returned message ({@code WS-RETURN-MSG-OFF} guard); and every
 * UI-visible message string is reproduced byte-for-byte, including its exact colon/space placement
 * and the trailing space in the date-of-birth message.</p>
 *
 * <p>This bean is <strong>stateless and thread-safe</strong>: all mutable per-call state lives in a
 * short-lived {@link EditState} value created inside each invocation. The rationale for the mapping
 * decisions summarized here (paragraph fall-through model, century/leap quirks, and the delegation
 * to {@link DateConversionService}) lives in {@code docs/decision-log.md}.</p>
 */
@Component
public class DateConversionSupport {

    /** Default label substituted into messages when the caller supplies a blank field name. */
    private static final String DEFAULT_FIELD_NAME = "Date";

    /**
     * Picture mask handed to {@link DateConversionService} for the {@code EDIT-DATE-LE} step; the
     * {@code CSUTLDWY} working-storage default ({@code WS-DATE-FORMAT VALUE 'YYYYMMDD'}).
     */
    private static final String DATE_FORMAT_MASK = "YYYYMMDD";

    /** Fixed byte length of the COBOL {@code WS-EDIT-DATE-CCYYMMDD} field ({@code PIC X(8)}). */
    private static final int DATE_LENGTH = 8;

    /**
     * Months with 31 days, modeling the {@code CSUTLDWY} 88-level
     * {@code WS-31-DAY-MONTH VALUES 1, 3, 5, 7, 8, 10, 12}.
     */
    private static final Set<Integer> MONTHS_WITH_31_DAYS = Set.of(1, 3, 5, 7, 8, 10, 12);

    /** {@code 88 THIS-CENTURY VALUE 20} - the only accepted 2000s century. */
    private static final int THIS_CENTURY = 20;

    /** {@code 88 LAST-CENTURY VALUE 19} - the only accepted 1900s century. */
    private static final int LAST_CENTURY = 19;

    /** Lower bound of {@code 88 WS-VALID-MONTH VALUES 1 THROUGH 12}. */
    private static final int MONTH_MIN = 1;

    /** Upper bound of {@code 88 WS-VALID-MONTH VALUES 1 THROUGH 12}. */
    private static final int MONTH_MAX = 12;

    /** Lower bound of {@code 88 WS-VALID-DAY VALUES 1 THROUGH 31}. */
    private static final int DAY_MIN = 1;

    /** Upper bound of {@code 88 WS-VALID-DAY VALUES 1 THROUGH 31}. */
    private static final int DAY_MAX = 31;

    /** {@code 88 WS-FEBRUARY VALUE 2}. */
    private static final int FEBRUARY = 2;

    /** {@code 88 WS-DAY-31 VALUE 31}. */
    private static final int DAY_31 = 31;

    /** {@code 88 WS-DAY-30 VALUE 30}. */
    private static final int DAY_30 = 30;

    /** {@code 88 WS-DAY-29 VALUE 29}. */
    private static final int DAY_29 = 29;

    /** Leap divisor used by {@code EDIT-DAY-MONTH-YEAR} when the two-digit year is {@code 00}. */
    private static final int LEAP_CENTURY_DIVISOR = 400;

    /** Leap divisor used by {@code EDIT-DAY-MONTH-YEAR} for every non-{@code 00} two-digit year. */
    private static final int LEAP_YEAR_DIVISOR = 4;

    /** Sentinel stored for a date component that is not a run of ASCII digits (COBOL non-numeric). */
    private static final int NON_NUMERIC = -1;

    /**
     * Collaborator reproducing {@code CALL 'CSUTLDTC'} (the Language-Environment {@code CEEDAYS}
     * date check) for the {@code EDIT-DATE-LE} paragraph. Injected by constructor (AAP &sect;0.5.3).
     */
    private final DateConversionService dateConversionService;

    /**
     * Creates the helper with its date-conversion collaborator.
     *
     * @param dateConversionService the {@code CSUTLDTC} migration used by the {@code EDIT-DATE-LE}
     *                              step; must not be {@code null}
     */
    public DateConversionSupport(DateConversionService dateConversionService) {
        this.dateConversionService = dateConversionService;
    }

    /**
     * Per-field validity flag, modeling the {@code CSUTLDWY} single-byte flag fields
     * ({@code WS-EDIT-YEAR-FLG}, {@code WS-EDIT-MONTH}, {@code WS-EDIT-DAY}) and their 88-levels.
     */
    public enum FieldFlag {

        /** {@code FLG-*-ISVALID} ({@code VALUE LOW-VALUES}): the component passed every edit. */
        VALID,

        /** {@code FLG-*-NOT-OK} ({@code VALUE '0'}): the component is present but invalid. */
        NOT_OK,

        /** {@code FLG-*-BLANK} ({@code VALUE 'B'}): the component was not supplied. */
        BLANK
    }

    /**
     * Immutable outcome of a date edit, modeling the observable results of the COBOL
     * {@code EDIT-DATE-CCYYMMDD} / {@code EDIT-DATE-OF-BIRTH} paragraphs.
     *
     * @param valid         {@code true} when every edit passed; mirrors the COBOL
     *                      {@code WS-EDIT-DATE-IS-VALID} group condition
     * @param inputError    {@code true} when any edit failed; mirrors the COBOL {@code INPUT-ERROR}
     *                      switch (always the logical complement of {@code valid})
     * @param yearFlag      validity flag for the {@code CCYY} component ({@code WS-EDIT-YEAR-FLG})
     * @param monthFlag     validity flag for the {@code MM} component ({@code WS-EDIT-MONTH})
     * @param dayFlag       validity flag for the {@code DD} component ({@code WS-EDIT-DAY})
     * @param returnMessage the first failing edit's message ({@code WS-RETURN-MSG}); empty when valid
     */
    public record DateEditResult(
            boolean valid,
            boolean inputError,
            FieldFlag yearFlag,
            FieldFlag monthFlag,
            FieldFlag dayFlag,
            String returnMessage) {

        /**
         * Convenience alias for {@link #valid()} preserving the {@code isValid()} naming expected by
         * callers migrated from the COBOL {@code IF WS-EDIT-DATE-IS-VALID} test.
         *
         * @return {@code true} when the edited date is valid
         */
        public boolean isValid() {
            return valid;
        }
    }

    /**
     * Validates an 8-digit {@code CCYYMMDD} date, reproducing the COBOL
     * {@code PERFORM EDIT-DATE-CCYYMMDD THRU EDIT-DATE-CCYYMMDD-EXIT} paragraph range.
     *
     * <p>The edits run in the original order and with the original fall-through semantics: the year,
     * month and day components are each edited in turn (an intra-component failure only skips the rest
     * of that component's own checks, exactly as the COBOL {@code GO TO <para>-EXIT} falls through to
     * the next paragraph), then the cross-field rules ({@code EDIT-DAY-MONTH-YEAR}) run, and finally -
     * only when every prior edit passed - the calendar check ({@code EDIT-DATE-LE}) is delegated to
     * {@link DateConversionService}. The message returned is that of the <em>first</em> failing edit
     * ({@code WS-RETURN-MSG-OFF} guard); later failures still update the per-field flags.</p>
     *
     * @param ccyymmdd  the date to validate as {@code CCYYMMDD}; treated as a fixed {@code PIC X(8)}
     *                  field (right-padded with spaces if shorter, truncated if longer);
     *                  {@code null} is treated as blank
     * @param fieldName the caller-supplied label (COBOL {@code WS-EDIT-VARIABLE-NAME}) placed at the
     *                  front of every message; trimmed, and defaulted to {@code "Date"} when blank
     * @return the structured {@link DateEditResult}; never {@code null}
     */
    public DateEditResult editDateCcyymmdd(String ccyymmdd, String fieldName) {
        EditState state = new EditState(resolveFieldName(fieldName), normalizeToDate(ccyymmdd));
        // EDIT-DATE-CCYYMMDD: SET WS-EDIT-DATE-IS-INVALID TO TRUE - all component flags start NOT_OK
        // (realized by EditState's initial field values).
        editYearCcyy(state);
        editMonth(state);
        editDay(state);
        editDayMonthYear(state);
        // EDIT-DAY-MONTH-YEAR end / line-274: continue to the LE check only when every prior edit
        // passed (WS-EDIT-DATE-IS-VALID); otherwise the COBOL GO TO EDIT-DATE-CCYYMMDD-EXIT skips it.
        if (!state.inputError) {
            editDateLe(state);
        }
        boolean valid = !state.inputError;
        return new DateEditResult(valid, state.inputError,
                state.yearFlag, state.monthFlag, state.dayFlag, state.returnMessage);
    }

    /**
     * {@code EDIT-YEAR-CCYY}: validates the {@code CCYY} component - present, four digits, and a
     * century of {@code 19} or {@code 20} (the deliberately narrow COBOL rule, preserved verbatim).
     *
     * @param state the mutable per-call edit state
     */
    private void editYearCcyy(EditState state) {
        state.yearFlag = FieldFlag.NOT_OK;
        if (isBlankField(state.ccyyText)) {
            state.inputError = true;
            state.yearFlag = FieldFlag.BLANK;
            setMessage(state, state.fieldName + " : Year must be supplied.");
            return;
        }
        if (!isAllDigits(state.ccyyText)) {
            state.inputError = true;
            state.yearFlag = FieldFlag.NOT_OK;
            setMessage(state, state.fieldName + " must be 4 digit number.");
            return;
        }
        if (state.centuryValue != THIS_CENTURY && state.centuryValue != LAST_CENTURY) {
            state.inputError = true;
            state.yearFlag = FieldFlag.NOT_OK;
            setMessage(state, state.fieldName + " : Century is not valid.");
            return;
        }
        state.yearFlag = FieldFlag.VALID;
    }

    /**
     * {@code EDIT-MONTH}: validates the {@code MM} component - present, numeric, and within
     * {@code 1}-{@code 12} ({@code WS-VALID-MONTH}). The COBOL range check and {@code TEST-NUMVAL}
     * numeric check both emit the same message, so they are combined here.
     *
     * @param state the mutable per-call edit state
     */
    private void editMonth(EditState state) {
        state.monthFlag = FieldFlag.NOT_OK;
        if (isBlankField(state.monthText)) {
            state.inputError = true;
            state.monthFlag = FieldFlag.BLANK;
            setMessage(state, state.fieldName + " : Month must be supplied.");
            return;
        }
        if (state.monthValue < MONTH_MIN || state.monthValue > MONTH_MAX) {
            state.inputError = true;
            state.monthFlag = FieldFlag.NOT_OK;
            setMessage(state, state.fieldName + ": Month must be a number between 1 and 12.");
            return;
        }
        state.monthFlag = FieldFlag.VALID;
    }

    /**
     * {@code EDIT-DAY}: validates the {@code DD} component - present, numeric, and within
     * {@code 1}-{@code 31} ({@code WS-VALID-DAY}). The COBOL {@code TEST-NUMVAL} numeric check and
     * range check both emit the same message, so they are combined here.
     *
     * @param state the mutable per-call edit state
     */
    private void editDay(EditState state) {
        state.dayFlag = FieldFlag.VALID;
        if (isBlankField(state.dayText)) {
            state.inputError = true;
            state.dayFlag = FieldFlag.BLANK;
            setMessage(state, state.fieldName + " : Day must be supplied.");
            return;
        }
        if (state.dayValue < DAY_MIN || state.dayValue > DAY_MAX) {
            state.inputError = true;
            state.dayFlag = FieldFlag.NOT_OK;
            setMessage(state, state.fieldName + ":day must be a number between 1 and 31.");
            return;
        }
        state.dayFlag = FieldFlag.VALID;
    }

    /**
     * {@code EDIT-DAY-MONTH-YEAR}: the cross-field calendar rules a single-component edit cannot
     * catch - a 31st in a month with no 31st, the 30th of February, and the 29th of February in a
     * non-leap year. The leap-year test preserves the COBOL divisor rule exactly: divide the
     * four-digit year by {@code 400} when the two-digit year is {@code 00}, otherwise by {@code 4},
     * treating a zero remainder as a leap year. Each rule short-circuits the remainder (COBOL
     * {@code GO TO EDIT-DATE-CCYYMMDD-EXIT}).
     *
     * @param state the mutable per-call edit state
     */
    private void editDayMonthYear(EditState state) {
        if (!MONTHS_WITH_31_DAYS.contains(state.monthValue) && state.dayValue == DAY_31) {
            state.inputError = true;
            state.dayFlag = FieldFlag.NOT_OK;
            state.monthFlag = FieldFlag.NOT_OK;
            setMessage(state, state.fieldName + ":Cannot have 31 days in this month.");
            return;
        }
        if (state.monthValue == FEBRUARY && state.dayValue == DAY_30) {
            state.inputError = true;
            state.dayFlag = FieldFlag.NOT_OK;
            state.monthFlag = FieldFlag.NOT_OK;
            setMessage(state, state.fieldName + ":Cannot have 30 days in this month.");
            return;
        }
        if (state.monthValue == FEBRUARY && state.dayValue == DAY_29) {
            int divisor = (state.twoDigitYearValue == 0) ? LEAP_CENTURY_DIVISOR : LEAP_YEAR_DIVISOR;
            if (state.fourDigitYearValue % divisor != 0) {
                state.inputError = true;
                state.dayFlag = FieldFlag.NOT_OK;
                state.monthFlag = FieldFlag.NOT_OK;
                state.yearFlag = FieldFlag.NOT_OK;
                setMessage(state,
                        state.fieldName + ":Not a leap year.Cannot have 29 days in this month.");
            }
        }
    }

    /**
     * {@code EDIT-DATE-LE}: the final safety-net calendar check. Reproduces
     * {@code CALL 'CSUTLDTC' USING WS-EDIT-DATE-CCYYMMDD, WS-DATE-FORMAT, WS-DATE-VALIDATION-RESULT}
     * by delegating to {@link DateConversionService#validateDate(String, String)} with the
     * {@code "YYYYMMDD"} mask, then failing the edit when the returned severity is non-zero (COBOL
     * {@code IF WS-SEVERITY-N = 0}). Severity and message-number render as the COBOL four-digit
     * {@code PIC X(04)} views.
     *
     * @param state the mutable per-call edit state
     */
    private void editDateLe(EditState state) {
        DateConversionService.DateValidationResult result =
                dateConversionService.validateDate(state.normalizedDate, DATE_FORMAT_MASK);
        if (result.severity() != 0) {
            state.inputError = true;
            state.yearFlag = FieldFlag.NOT_OK;
            state.monthFlag = FieldFlag.NOT_OK;
            state.dayFlag = FieldFlag.NOT_OK;
            setMessage(state, state.fieldName + " validation error Sev code: "
                    + toFourDigit(result.severity()) + " Message code: " + toFourDigit(result.msgNo()));
        }
    }

    /**
     * Reproduces the {@code EDIT-DATE-OF-BIRTH} reasonableness check: a date of birth may not be
     * today or in the future.
     *
     * <p>The COBOL compares {@code FUNCTION INTEGER-OF-DATE} of the supplied date against the current
     * date and reports an error unless the current date is strictly greater; the Java equivalent is
     * {@code dob.isBefore(LocalDate.now())}. As in the COBOL, this is purely the future check and does
     * <em>not</em> re-impose the {@code EDIT-DATE-CCYYMMDD} century rule. When the supplied value is
     * not a well-formed calendar date (so no meaningful comparison exists), the standard component
     * edits are run instead to return a precise field-level message - matching how the online flows
     * (e.g. {@code COACTUPC}) always validate the date's format before this reasonableness check.</p>
     *
     * @param ccyymmdd  the date of birth as {@code CCYYMMDD}; treated as a fixed {@code PIC X(8)}
     *                  field; {@code null} is treated as blank
     * @param fieldName the caller-supplied label placed at the front of the message; trimmed, and
     *                  defaulted to {@code "Date"} when blank
     * @return the structured {@link DateEditResult}; never {@code null}
     */
    public DateEditResult editDateOfBirth(String ccyymmdd, String fieldName) {
        String resolvedName = resolveFieldName(fieldName);
        String normalized = normalizeToDate(ccyymmdd);
        LocalDate dateOfBirth = tryParseDate(normalized);
        if (dateOfBirth == null) {
            // Not a well-formed calendar date: surface the precise component edit instead of an
            // undefined date comparison (the online DOB flow always edits the format first).
            return editDateCcyymmdd(ccyymmdd, fieldName);
        }
        if (dateOfBirth.isBefore(LocalDate.now())) {
            return new DateEditResult(true, false,
                    FieldFlag.VALID, FieldFlag.VALID, FieldFlag.VALID, "");
        }
        return new DateEditResult(false, true,
                FieldFlag.NOT_OK, FieldFlag.NOT_OK, FieldFlag.NOT_OK,
                resolvedName + ":cannot be in the future ");
    }

    /**
     * Resolves the caller's field label: {@code FUNCTION TRIM(WS-EDIT-VARIABLE-NAME)}, defaulting to
     * {@link #DEFAULT_FIELD_NAME} when the caller supplies {@code null} or a blank label.
     *
     * @param fieldName the caller-supplied label; may be {@code null}
     * @return the trimmed, non-empty label
     */
    private static String resolveFieldName(String fieldName) {
        if (fieldName == null) {
            return DEFAULT_FIELD_NAME;
        }
        String trimmed = fieldName.trim();
        return trimmed.isEmpty() ? DEFAULT_FIELD_NAME : trimmed;
    }

    /**
     * Normalizes arbitrary input to the fixed COBOL {@code WS-EDIT-DATE-CCYYMMDD} field width
     * ({@code PIC X(8)}): {@code null} becomes eight spaces, shorter input is right-padded with
     * spaces, and longer input is truncated to the first eight characters.
     *
     * @param value the raw input; may be {@code null}
     * @return an eight-character string
     */
    private static String normalizeToDate(String value) {
        String text = (value == null) ? "" : value;
        if (text.length() == DATE_LENGTH) {
            return text;
        }
        if (text.length() > DATE_LENGTH) {
            return text.substring(0, DATE_LENGTH);
        }
        StringBuilder padded = new StringBuilder(DATE_LENGTH);
        padded.append(text);
        while (padded.length() < DATE_LENGTH) {
            padded.append(' ');
        }
        return padded.toString();
    }

    /**
     * Reports whether {@code value} is a non-empty run of ASCII digits, matching the COBOL
     * {@code IS NUMERIC} / {@code TEST-NUMVAL} test used for the date components.
     *
     * @param value the component text
     * @return {@code true} when every character is {@code '0'}-{@code '9'} and the text is non-empty
     */
    private static boolean isAllDigits(String value) {
        if (value.isEmpty()) {
            return false;
        }
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c < '0' || c > '9') {
                return false;
            }
        }
        return true;
    }

    /**
     * Reports whether a date component is &quot;not supplied&quot;, matching the COBOL
     * {@code EQUAL LOW-VALUES OR SPACES} test: an empty string, or one consisting solely of spaces
     * and/or {@code LOW-VALUES} ({@code '\u0000'}) bytes.
     *
     * @param value the component text
     * @return {@code true} when the component is blank
     */
    private static boolean isBlankField(String value) {
        if (value.isEmpty()) {
            return true;
        }
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c != ' ' && c != '\u0000') {
                return false;
            }
        }
        return true;
    }

    /**
     * Parses a date component to its integer value, returning {@link #NON_NUMERIC} when the text is
     * not a clean run of ASCII digits (mirroring a COBOL numeric redefinition over non-numeric bytes,
     * which never matches a valid range or 88-level value).
     *
     * @param value the component text
     * @return the parsed value, or {@link #NON_NUMERIC}
     */
    private static int parseNumericComponent(String value) {
        return isAllDigits(value) ? Integer.parseInt(value) : NON_NUMERIC;
    }

    /**
     * Renders a non-negative severity/message value as the COBOL four-digit {@code PIC X(04)} view
     * (zero-padded, low four digits), matching the {@code WS-SEVERITY} / {@code WS-MSG-NO} fields.
     *
     * @param value the value to render
     * @return a four-character numeric string
     */
    private static String toFourDigit(int value) {
        int magnitude = Math.abs(value) % 10000;
        String digits = Integer.toString(magnitude);
        int padding = 4 - digits.length();
        if (padding <= 0) {
            return digits;
        }
        StringBuilder builder = new StringBuilder(4);
        for (int i = 0; i < padding; i++) {
            builder.append('0');
        }
        builder.append(digits);
        return builder.toString();
    }

    /**
     * Sets the return message only when none has been set yet, reproducing the COBOL
     * {@code IF WS-RETURN-MSG-OFF} guard so the first failing edit's message wins.
     *
     * @param state   the mutable per-call edit state
     * @param message the candidate message
     */
    private static void setMessage(EditState state, String message) {
        if (state.returnMessage.isEmpty()) {
            state.returnMessage = message;
        }
    }

    /**
     * Attempts to interpret an eight-character {@code CCYYMMDD} string as a real Gregorian calendar
     * date. Used only by {@link #editDateOfBirth(String, String)} for the future-date comparison.
     *
     * @param normalized the eight-character normalized date
     * @return the {@link LocalDate}, or {@code null} when the text is not a valid calendar date
     */
    private static LocalDate tryParseDate(String normalized) {
        String yearText = normalized.substring(0, 4);
        String monthText = normalized.substring(4, 6);
        String dayText = normalized.substring(6, 8);
        if (!isAllDigits(yearText) || !isAllDigits(monthText) || !isAllDigits(dayText)) {
            return null;
        }
        try {
            return LocalDate.of(Integer.parseInt(yearText),
                    Integer.parseInt(monthText), Integer.parseInt(dayText));
        } catch (DateTimeException ex) {
            return null;
        }
    }

    /**
     * Mutable per-invocation working set, consolidating the {@code CSUTLDWY} date fields, their
     * numeric redefinitions, the per-field validity flags, the {@code INPUT-ERROR} switch, and the
     * accumulated {@code WS-RETURN-MSG}. All parsing of the {@code CCYYMMDD} components happens once,
     * in the constructor; non-numeric components are stored as {@link #NON_NUMERIC}. The initial flag
     * values reproduce {@code SET WS-EDIT-DATE-IS-INVALID TO TRUE} (all components {@code NOT_OK}).
     */
    private static final class EditState {

        private final String fieldName;
        private final String normalizedDate;
        private final String ccyyText;
        private final String monthText;
        private final String dayText;
        private final int centuryValue;
        private final int twoDigitYearValue;
        private final int fourDigitYearValue;
        private final int monthValue;
        private final int dayValue;

        private FieldFlag yearFlag = FieldFlag.NOT_OK;
        private FieldFlag monthFlag = FieldFlag.NOT_OK;
        private FieldFlag dayFlag = FieldFlag.NOT_OK;
        private boolean inputError;
        private String returnMessage = "";

        private EditState(String fieldName, String normalizedDate) {
            this.fieldName = fieldName;
            this.normalizedDate = normalizedDate;
            this.ccyyText = normalizedDate.substring(0, 4);
            this.monthText = normalizedDate.substring(4, 6);
            this.dayText = normalizedDate.substring(6, 8);
            this.centuryValue = parseNumericComponent(normalizedDate.substring(0, 2));
            this.twoDigitYearValue = parseNumericComponent(normalizedDate.substring(2, 4));
            this.fourDigitYearValue = parseNumericComponent(this.ccyyText);
            this.monthValue = parseNumericComponent(this.monthText);
            this.dayValue = parseNumericComponent(this.dayText);
        }
    }
}
