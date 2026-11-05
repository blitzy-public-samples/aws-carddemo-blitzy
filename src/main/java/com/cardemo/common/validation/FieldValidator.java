package com.cardemo.common.validation;

import com.cardemo.common.util.DateConversionUtil;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.Set;

/**
 * Translated from CSUTLDPY.cpy + CSUTLDWY.cpy — Field and date validation utility
 * replicating COBOL EDIT-DATE-CCYYMMDD and related validation paragraphs.
 *
 * <p>This Spring-managed bean provides 100% business-logic-parity with the COBOL
 * procedural copybook {@code CSUTLDPY.cpy} (validation procedures) and its companion
 * working-storage copybook {@code CSUTLDWY.cpy} (flags, constants, result structures).
 * The LE date service call ({@code CALL 'CSUTLDTC'}) is replaced by
 * {@link DateConversionUtil#validateDate(String, String)}.
 *
 * <p>Online services such as {@code AccountUpdateService}, {@code CreditCardUpdateService},
 * and other CICS-migrated services inject this bean via {@code @Autowired} to perform
 * field and date validation matching the COBOL
 * {@code PERFORM EDIT-DATE-CCYYMMDD THRU EDIT-DATE-CCYYMMDD-EXIT} pattern.
 *
 * @see DateConversionUtil
 */
@Component
public class FieldValidator {

    // ========================================================================
    // Constants — translated from CSUTLDWY.cpy 88-level conditions
    // ========================================================================

    /**
     * Months that have 31 days.
     * Translated from CSUTLDWY.cpy line 21-23:
     * {@code 88 WS-31-DAY-MONTH VALUES 1, 3, 5, 7, 8, 10, 12}.
     */
    private static final Set<Integer> THIRTY_ONE_DAY_MONTHS = Set.of(1, 3, 5, 7, 8, 10, 12);

    /**
     * February month number.
     * Translated from CSUTLDWY.cpy line 24: {@code 88 WS-FEBRUARY VALUE 2}.
     */
    private static final int FEBRUARY = 2;

    /** Flag value indicating valid state — maps COBOL LOW-VALUES. */
    static final char VALID_FLAG = '\0';

    /** Flag value indicating not-ok state — maps COBOL '0'. */
    static final char NOT_OK_FLAG = '0';

    /** Flag value indicating blank input — maps COBOL 'B'. */
    static final char BLANK_FLAG = 'B';

    /** CCYYMMDD date format for {@link LocalDate} parsing. */
    private static final DateTimeFormatter CCYYMMDD_FORMAT =
            DateTimeFormatter.ofPattern("yyyyMMdd");

    /** Valid century: 20 — from CSUTLDWY.cpy line 9: {@code 88 THIS-CENTURY VALUE 20}. */
    private static final int THIS_CENTURY = 20;

    /** Valid century: 19 — from CSUTLDWY.cpy line 10: {@code 88 LAST-CENTURY VALUE 19}. */
    private static final int LAST_CENTURY = 19;

    // ========================================================================
    // Inner Class: DateValidationResult
    // ========================================================================

    /**
     * Maps the COBOL {@code WS-DATE-VALIDATION-RESULT} structure from CSUTLDWY.cpy
     * (lines 60-86). Holds the result of LE-service-equivalent date validation.
     *
     * <p>COBOL layout (80 bytes total):
     * <pre>
     * 10 WS-DATE-VALIDATION-RESULT.
     *    20 WS-SEVERITY      PIC X(04).     → severity (int)
     *    20 FILLER            PIC X(11).     → "Mesg Code:"
     *    20 WS-MSG-NO         PIC X(04).     → messageCode (int)
     *    20 FILLER            PIC X(01).     → space
     *    20 WS-RESULT         PIC X(15).     → resultText
     *    20 FILLER            PIC X(01).     → space
     *    20 FILLER            PIC X(09).     → "TstDate:"
     *    20 WS-DATE           PIC X(10).     → testedDate
     *    20 FILLER            PIC X(01).     → space
     *    20 FILLER            PIC X(10).     → "Mask used:"
     *    20 WS-DATE-FMT       PIC X(10).     → formatUsed
     * </pre>
     */
    public static class DateValidationResult {

        private final int severity;
        private final int messageCode;
        private final String resultText;
        private final String testedDate;
        private final String formatUsed;

        /**
         * Constructs a new DateValidationResult with all fields.
         *
         * @param severity    maps WS-SEVERITY-N (0 = valid, non-zero = error)
         * @param messageCode maps WS-MSG-NO-N (CEEDAYS message number)
         * @param resultText  maps WS-RESULT (up to 15 characters)
         * @param testedDate  maps WS-DATE (up to 10 characters)
         * @param formatUsed  maps WS-DATE-FMT (up to 10 characters)
         */
        public DateValidationResult(int severity, int messageCode,
                                    String resultText, String testedDate,
                                    String formatUsed) {
            this.severity = severity;
            this.messageCode = messageCode;
            this.resultText = resultText != null ? resultText : "";
            this.testedDate = testedDate != null ? testedDate : "";
            this.formatUsed = formatUsed != null ? formatUsed : "";
        }

        /** @return WS-SEVERITY-N (0 = valid, non-zero = error) */
        public int getSeverity() {
            return severity;
        }

        /** @return WS-MSG-NO-N (CEEDAYS message number) */
        public int getMessageCode() {
            return messageCode;
        }

        /** @return WS-RESULT (human-readable result text, up to 15 characters) */
        public String getResultText() {
            return resultText;
        }

        /** @return WS-DATE (the date string that was tested, up to 10 characters) */
        public String getTestedDate() {
            return testedDate;
        }

        /** @return WS-DATE-FMT (the format mask used, up to 10 characters) */
        public String getFormatUsed() {
            return formatUsed;
        }

        /**
         * Convenience method: {@code true} when severity is 0.
         *
         * @return {@code true} if the date passed validation
         */
        public boolean isValid() {
            return severity == 0;
        }

        /**
         * Reconstructs the 80-byte WS-DATE-VALIDATION-RESULT layout for traceability.
         * Matches the exact COBOL fixed-width structure with fillers.
         *
         * @return formatted string replicating the 80-byte COBOL result layout
         */
        public String toFormattedString() {
            return String.format("%-4d%sMesg Code:%-4d %s %sTstDate:%-10s %sMask used:%-10s",
                    severity, "", messageCode, padRight(resultText, 15), "",
                    testedDate, "", formatUsed);
        }

        private static String padRight(String text, int length) {
            if (text == null) {
                return " ".repeat(length);
            }
            if (text.length() >= length) {
                return text.substring(0, length);
            }
            return text + " ".repeat(length - text.length());
        }
    }

    // ========================================================================
    // Inner Class: FieldValidationFlags
    // ========================================================================

    /**
     * Maps the COBOL {@code WS-EDIT-DATE-FLGS} structure from CSUTLDWY.cpy (lines 43-57).
     *
     * <p>Flag values:
     * <ul>
     *   <li>{@code '\0'} (LOW-VALUES) — valid</li>
     *   <li>{@code '0'} — not-ok / invalid</li>
     *   <li>{@code 'B'} — blank / missing input</li>
     * </ul>
     *
     * <p>COBOL structure:
     * <pre>
     * 10 WS-EDIT-DATE-FLGS.
     *    88 WS-EDIT-DATE-IS-VALID    VALUE LOW-VALUES.
     *    88 WS-EDIT-DATE-IS-INVALID  VALUE '000'.
     *    20 WS-EDIT-YEAR-FLG         PIC X(01).
     *    20 WS-EDIT-MONTH            PIC X(01).
     *    20 WS-EDIT-DAY              PIC X(01).
     * </pre>
     */
    public static class FieldValidationFlags {

        private char yearFlag;
        private char monthFlag;
        private char dayFlag;

        /**
         * Constructs flags with specified initial values.
         *
         * @param yearFlag  year validation flag
         * @param monthFlag month validation flag
         * @param dayFlag   day validation flag
         */
        public FieldValidationFlags(char yearFlag, char monthFlag, char dayFlag) {
            this.yearFlag = yearFlag;
            this.monthFlag = monthFlag;
            this.dayFlag = dayFlag;
        }

        /** @return year validation flag character */
        public char getYearFlag() {
            return yearFlag;
        }

        /** @return month validation flag character */
        public char getMonthFlag() {
            return monthFlag;
        }

        /** @return day validation flag character */
        public char getDayFlag() {
            return dayFlag;
        }

        /**
         * All three flags are LOW-VALUES — date is valid.
         * Maps COBOL: {@code 88 WS-EDIT-DATE-IS-VALID VALUE LOW-VALUES}.
         *
         * @return {@code true} if year, month, and day flags are all {@code '\0'}
         */
        public boolean isDateValid() {
            return yearFlag == VALID_FLAG && monthFlag == VALID_FLAG && dayFlag == VALID_FLAG;
        }

        /**
         * All three flags are '0' — date is invalid.
         * Maps COBOL: {@code 88 WS-EDIT-DATE-IS-INVALID VALUE '000'}.
         *
         * @return {@code true} if all flags are {@code '0'}
         */
        public boolean isDateInvalid() {
            return yearFlag == NOT_OK_FLAG && monthFlag == NOT_OK_FLAG && dayFlag == NOT_OK_FLAG;
        }

        /**
         * Year flag is LOW-VALUES.
         * Maps COBOL: {@code 88 FLG-YEAR-ISVALID VALUE LOW-VALUES}.
         *
         * @return {@code true} if year flag is {@code '\0'}
         */
        public boolean isYearValid() {
            return yearFlag == VALID_FLAG;
        }

        /**
         * Month flag is LOW-VALUES.
         * Maps COBOL: {@code 88 FLG-MONTH-ISVALID VALUE LOW-VALUES}.
         *
         * @return {@code true} if month flag is {@code '\0'}
         */
        public boolean isMonthValid() {
            return monthFlag == VALID_FLAG;
        }

        /**
         * Day flag is LOW-VALUES.
         * Maps COBOL: {@code 88 FLG-DAY-ISVALID VALUE LOW-VALUES}.
         *
         * @return {@code true} if day flag is {@code '\0'}
         */
        public boolean isDayValid() {
            return dayFlag == VALID_FLAG;
        }

        /**
         * Year flag is 'B' (blank input).
         * Maps COBOL: {@code 88 FLG-YEAR-BLANK VALUE 'B'}.
         *
         * @return {@code true} if year flag is {@code 'B'}
         */
        public boolean isYearBlank() {
            return yearFlag == BLANK_FLAG;
        }

        /**
         * Month flag is 'B' (blank input).
         * Maps COBOL: {@code 88 FLG-MONTH-BLANK VALUE 'B'}.
         *
         * @return {@code true} if month flag is {@code 'B'}
         */
        public boolean isMonthBlank() {
            return monthFlag == BLANK_FLAG;
        }

        /**
         * Day flag is 'B' (blank input).
         * Maps COBOL: {@code 88 FLG-DAY-BLANK VALUE 'B'}.
         *
         * @return {@code true} if day flag is {@code 'B'}
         */
        public boolean isDayBlank() {
            return dayFlag == BLANK_FLAG;
        }

        /**
         * Returns the 3-character flag representation for COBOL compatibility.
         * Each character is the flag value for year, month, day in order.
         *
         * @return 3-char string (e.g., {@code "\0\0\0"} for all valid, {@code "000"} for all invalid)
         */
        public String toFlagString() {
            return "" + yearFlag + monthFlag + dayFlag;
        }

        /* Package-private setters for use by validation methods */

        void setYearFlag(char flag) {
            this.yearFlag = flag;
        }

        void setMonthFlag(char flag) {
            this.monthFlag = flag;
        }

        void setDayFlag(char flag) {
            this.dayFlag = flag;
        }
    }

    // ========================================================================
    // Inner Class: ValidationResult
    // ========================================================================

    /**
     * General validation result for both date and non-date field validations.
     * Carries validity state, error flag, variable name, message, and date flags.
     *
     * <p>Maps COBOL working storage:
     * <ul>
     *   <li>{@code valid} → inverse of {@code 88 INPUT-ERROR VALUE '1'}</li>
     *   <li>{@code inputError} → {@code 88 INPUT-ERROR VALUE '1'}</li>
     *   <li>{@code variableName} → {@code WS-EDIT-VARIABLE-NAME PIC X(25)}</li>
     *   <li>{@code returnMessage} → {@code WS-RETURN-MSG PIC X(75)}</li>
     *   <li>{@code flags} → {@code WS-EDIT-DATE-FLGS} (if date validation)</li>
     * </ul>
     */
    public static class ValidationResult {

        private final boolean valid;
        private final boolean inputError;
        private final String variableName;
        private final String returnMessage;
        private final FieldValidationFlags flags;

        /**
         * Constructs a ValidationResult with all fields.
         *
         * @param valid         {@code true} if field is valid
         * @param inputError    maps COBOL {@code 88 INPUT-ERROR VALUE '1'}
         * @param variableName  maps {@code WS-EDIT-VARIABLE-NAME PIC X(25)}
         * @param returnMessage maps {@code WS-RETURN-MSG PIC X(75)}
         * @param flags         date validation flags (may be {@code null} for non-date validation)
         */
        public ValidationResult(boolean valid, boolean inputError,
                                String variableName, String returnMessage,
                                FieldValidationFlags flags) {
            this.valid = valid;
            this.inputError = inputError;
            this.variableName = variableName != null ? variableName : "";
            this.returnMessage = returnMessage != null ? returnMessage : "";
            this.flags = flags;
        }

        /** @return {@code true} if the field passed validation */
        public boolean isValid() {
            return valid;
        }

        /**
         * Maps COBOL: {@code 88 INPUT-ERROR VALUE '1'}.
         *
         * @return {@code true} if an input error was detected
         */
        public boolean isInputError() {
            return inputError;
        }

        /**
         * Convenience method — equivalent to {@link #isInputError()}.
         *
         * @return {@code true} if an error was detected
         */
        public boolean hasError() {
            return inputError;
        }

        /** @return the field name used in error messages (WS-EDIT-VARIABLE-NAME) */
        public String getVariableName() {
            return variableName;
        }

        /** @return the error message (WS-RETURN-MSG), empty string if no error */
        public String getReturnMessage() {
            return returnMessage;
        }

        /** @return the date validation flags, or {@code null} if not a date validation */
        public FieldValidationFlags getFlags() {
            return flags;
        }
    }

    // ========================================================================
    // Core Date Validation Methods
    // ========================================================================

    /**
     * Main entry point for CCYYMMDD date validation — orchestrates the full validation flow.
     *
     * <p>Translated from CSUTLDPY.cpy lines 18-20 (EDIT-DATE-CCYYMMDD paragraph).
     * Performs sequential validation: year → month → day → day-month-year combination
     * → LE service check. Returns on the first error encountered, preserving the
     * COBOL WS-RETURN-MSG-OFF guard pattern (only first error message kept).
     *
     * @param variableName the field name for error messages (maps WS-EDIT-VARIABLE-NAME)
     * @param date         8-character CCYYMMDD date string
     * @return validation result with flags and message
     */
    public ValidationResult editDateCcyymmdd(String variableName, String date) {
        // SET WS-EDIT-DATE-IS-INVALID TO TRUE (line 19) — all flags to '0'
        FieldValidationFlags flags = new FieldValidationFlags(NOT_OK_FLAG, NOT_OK_FLAG, NOT_OK_FLAG);
        String safeVar = variableName != null ? variableName : "";

        // Null/length guard for the 8-char date string
        if (date == null || date.length() != 8) {
            return new ValidationResult(false, true, safeVar,
                    safeVar.trim() + " : Year must be supplied.", flags);
        }

        // Parse components: CC(2) YY(2) MM(2) DD(2)
        String ccyy = date.substring(0, 4);
        String mm = date.substring(4, 6);
        String dd = date.substring(6, 8);

        // EDIT-YEAR-CCYY — if error, return immediately (GO TO EXIT)
        ValidationResult yearResult = editYearCcyy(safeVar, ccyy);
        if (yearResult.isInputError()) {
            return yearResult;
        }

        // EDIT-MONTH — if error, return immediately
        ValidationResult monthResult = editMonth(safeVar, mm);
        if (monthResult.isInputError()) {
            return monthResult;
        }

        // EDIT-DAY — if error, return immediately
        ValidationResult dayResult = editDay(safeVar, dd);
        if (dayResult.isInputError()) {
            return dayResult;
        }

        // EDIT-DAY-MONTH-YEAR — combined check, if error return
        ValidationResult dmyResult = editDayMonthYear(safeVar, ccyy, mm, dd);
        if (dmyResult.isInputError()) {
            return dmyResult;
        }

        // EDIT-DATE-LE — final LE service check
        ValidationResult leResult = editDateLe(safeVar, date, "YYYYMMDD");
        if (leResult.isInputError()) {
            return leResult;
        }

        // All passed — SET WS-EDIT-DATE-IS-VALID TO TRUE (line 327)
        FieldValidationFlags validFlags = new FieldValidationFlags(VALID_FLAG, VALID_FLAG, VALID_FLAG);
        return new ValidationResult(true, false, safeVar, "", validFlags);
    }

    /**
     * Validates the 4-digit year (CCYY) component of a date.
     *
     * <p>Translated from CSUTLDPY.cpy lines 25-90 (EDIT-YEAR-CCYY paragraph).
     * Checks: not blank, numeric, century is 19 or 20.
     *
     * @param variableName the field name for error messages
     * @param ccyy         4-character year string (e.g., "2023")
     * @return validation result with year flag set
     */
    public ValidationResult editYearCcyy(String variableName, String ccyy) {
        String safeVar = variableName != null ? variableName : "";
        // SET FLG-YEAR-NOT-OK TO TRUE (line 27)
        FieldValidationFlags flags = new FieldValidationFlags(NOT_OK_FLAG, VALID_FLAG, VALID_FLAG);

        // Blank check (lines 30-42)
        if (isBlankOrNull(ccyy)) {
            flags.setYearFlag(BLANK_FLAG);
            return new ValidationResult(false, true, safeVar,
                    safeVar.trim() + " : Year must be supplied.", flags);
        }

        // Numeric check (lines 48-58)
        if (!isNumeric(ccyy)) {
            return new ValidationResult(false, true, safeVar,
                    safeVar.trim() + " must be 4 digit number.", flags);
        }

        // Century reasonableness (lines 70-84)
        // CSUTLDWY.cpy lines 9-10: 88 THIS-CENTURY VALUE 20, 88 LAST-CENTURY VALUE 19
        int century = Integer.parseInt(ccyy.substring(0, 2));
        if (century != LAST_CENTURY && century != THIS_CENTURY) {
            return new ValidationResult(false, true, safeVar,
                    safeVar.trim() + " : Century is not valid.", flags);
        }

        // All checks pass — SET FLG-YEAR-ISVALID TO TRUE (line 86)
        flags.setYearFlag(VALID_FLAG);
        return new ValidationResult(true, false, safeVar, "", flags);
    }

    /**
     * Validates the 2-digit month (MM) component of a date.
     *
     * <p>Translated from CSUTLDPY.cpy lines 91-147 (EDIT-MONTH paragraph).
     * Checks: not blank, numeric, value in range 1-12.
     *
     * @param variableName the field name for error messages
     * @param mm           2-character month string (e.g., "12")
     * @return validation result with month flag set
     */
    public ValidationResult editMonth(String variableName, String mm) {
        String safeVar = variableName != null ? variableName : "";
        // SET FLG-MONTH-NOT-OK TO TRUE (line 92)
        FieldValidationFlags flags = new FieldValidationFlags(VALID_FLAG, NOT_OK_FLAG, VALID_FLAG);

        // Blank check (lines 94-105)
        if (isBlankOrNull(mm)) {
            flags.setMonthFlag(BLANK_FLAG);
            return new ValidationResult(false, true, safeVar,
                    safeVar.trim() + " : Month must be supplied.", flags);
        }

        // Numeric check / TEST-NUMVAL equivalent (lines 126-141)
        if (!isNumeric(mm)) {
            return new ValidationResult(false, true, safeVar,
                    safeVar.trim() + ": Month must be a number between 1 and 12.", flags);
        }

        // Valid month range check (CSUTLDWY.cpy line 19-20: VALUES 1 THROUGH 12)
        int monthVal = Integer.parseInt(mm);
        if (monthVal < 1 || monthVal > 12) {
            return new ValidationResult(false, true, safeVar,
                    safeVar.trim() + ": Month must be a number between 1 and 12.", flags);
        }

        // All checks pass — SET FLG-MONTH-ISVALID TO TRUE (line 143)
        flags.setMonthFlag(VALID_FLAG);
        return new ValidationResult(true, false, safeVar, "", flags);
    }

    /**
     * Validates the 2-digit day (DD) component of a date.
     *
     * <p>Translated from CSUTLDPY.cpy lines 150-207 (EDIT-DAY paragraph).
     * NOTE: Unlike year and month, the COBOL sets day flag to VALID first (line 152).
     * Checks: not blank, numeric, value in range 1-31.
     *
     * @param variableName the field name for error messages
     * @param dd           2-character day string (e.g., "15")
     * @return validation result with day flag set
     */
    public ValidationResult editDay(String variableName, String dd) {
        String safeVar = variableName != null ? variableName : "";
        // SET FLG-DAY-ISVALID TO TRUE (line 152) — NOTE: starts VALID, unlike year/month
        FieldValidationFlags flags = new FieldValidationFlags(VALID_FLAG, VALID_FLAG, VALID_FLAG);

        // Blank check (lines 154-165)
        if (isBlankOrNull(dd)) {
            flags.setDayFlag(BLANK_FLAG);
            return new ValidationResult(false, true, safeVar,
                    safeVar.trim() + " : Day must be supplied.", flags);
        }

        // Numeric check / TEST-NUMVAL equivalent (lines 170-185)
        if (!isNumeric(dd)) {
            flags.setDayFlag(NOT_OK_FLAG);
            return new ValidationResult(false, true, safeVar,
                    safeVar.trim() + ":day must be a number between 1 and 31.", flags);
        }

        // Valid day range check (CSUTLDWY.cpy lines 28-29: VALUES 1 THROUGH 31)
        int dayVal = Integer.parseInt(dd);
        if (dayVal < 1 || dayVal > 31) {
            flags.setDayFlag(NOT_OK_FLAG);
            return new ValidationResult(false, true, safeVar,
                    safeVar.trim() + ":day must be a number between 1 and 31.", flags);
        }

        // All checks pass — FLG-DAY-ISVALID stays TRUE (line 203)
        return new ValidationResult(true, false, safeVar, "", flags);
    }

    /**
     * Validates the day-month-year combination for calendar correctness.
     *
     * <p>Translated from CSUTLDPY.cpy lines 209-282 (EDIT-DAY-MONTH-YEAR paragraph).
     * The most complex validation — checks:
     * <ol>
     *   <li>31 days not allowed in non-31-day months</li>
     *   <li>30 days not allowed in February</li>
     *   <li>29 days in February only if leap year (COBOL-specific leap year logic)</li>
     * </ol>
     *
     * <p><strong>COBOL Leap Year Logic</strong> (lines 245-254):
     * If year ends in 00 (century year), check divisible by 400.
     * Otherwise, check divisible by 4 only. This intentionally omits the standard
     * Gregorian "divisible by 100" exception for non-century years.
     *
     * @param variableName the field name for error messages
     * @param ccyy         4-character year string
     * @param mm           2-character month string
     * @param dd           2-character day string
     * @return validation result with appropriate flags set
     */
    public ValidationResult editDayMonthYear(String variableName, String ccyy,
                                             String mm, String dd) {
        String safeVar = variableName != null ? variableName : "";

        int monthVal = Integer.parseInt(mm);
        int dayVal = Integer.parseInt(dd);
        int yearVal = Integer.parseInt(ccyy);
        int yy = yearVal % 100; // last 2 digits

        // 31-day check (lines 213-226):
        // IF NOT WS-31-DAY-MONTH AND WS-DAY-31
        if (!THIRTY_ONE_DAY_MONTHS.contains(monthVal) && dayVal == 31) {
            FieldValidationFlags flags = new FieldValidationFlags(
                    VALID_FLAG, NOT_OK_FLAG, NOT_OK_FLAG);
            return new ValidationResult(false, true, safeVar,
                    safeVar.trim() + ":Cannot have 31 days in this month.", flags);
        }

        // February 30 check (lines 228-241):
        // IF WS-FEBRUARY AND WS-DAY-30
        if (monthVal == FEBRUARY && dayVal == 30) {
            FieldValidationFlags flags = new FieldValidationFlags(
                    VALID_FLAG, NOT_OK_FLAG, NOT_OK_FLAG);
            return new ValidationResult(false, true, safeVar,
                    safeVar.trim() + ":Cannot have 30 days in this month.", flags);
        }

        // February 29 / leap year check (lines 243-272):
        // IF WS-FEBRUARY AND WS-DAY-29
        if (monthVal == FEBRUARY && dayVal == 29) {
            // COBOL leap year logic (lines 245-254):
            // If YY = 0 (century year), divisor is 400; else divisor is 4
            int divisor;
            if (yy == 0) {
                divisor = 400;
            } else {
                divisor = 4;
            }

            int remainder = yearVal % divisor;
            if (remainder != 0) {
                // Not a leap year
                FieldValidationFlags flags = new FieldValidationFlags(
                        NOT_OK_FLAG, NOT_OK_FLAG, NOT_OK_FLAG);
                return new ValidationResult(false, true, safeVar,
                        safeVar.trim() + ":Not a leap year.Cannot have 29 days in this month.",
                        flags);
            }
            // Is a leap year — allow day 29 (CONTINUE in COBOL)
        }

        // Final validity check (lines 274-278):
        // Date combination is valid — return success
        FieldValidationFlags flags = new FieldValidationFlags(
                VALID_FLAG, VALID_FLAG, VALID_FLAG);
        return new ValidationResult(true, false, safeVar, "", flags);
    }

    /**
     * Delegates to the LE date service for final date validation.
     *
     * <p>Translated from CSUTLDPY.cpy lines 284-331 (EDIT-DATE-LE paragraph).
     * Replaces {@code CALL 'CSUTLDTC' USING date, format, result} with
     * {@link DateConversionUtil#validateDate(String, String)}.
     *
     * @param variableName the field name for error messages
     * @param date         the date string to validate
     * @param format       the date format (e.g., "YYYYMMDD")
     * @return validation result from LE service validation
     */
    public ValidationResult editDateLe(String variableName, String date, String format) {
        String safeVar = variableName != null ? variableName : "";

        // CALL 'CSUTLDTC' USING date, format, result (lines 293-296)
        DateConversionUtil.DateValidationResult leResult =
                DateConversionUtil.validateDate(date, format);

        // Check WS-SEVERITY-N (line 298): if severity != 0 → error
        if (leResult.severity() != 0) {
            FieldValidationFlags flags = new FieldValidationFlags(
                    NOT_OK_FLAG, NOT_OK_FLAG, NOT_OK_FLAG);
            // Message: variableName + " validation error Sev code: " + sev + " Message code: " + msg
            String message = safeVar.trim() + " validation error Sev code: "
                    + leResult.severity() + " Message code: " + leResult.messageCode();
            return new ValidationResult(false, true, safeVar, message, flags);
        }

        // If no input error: SET FLG-DAY-ISVALID (line 319)
        FieldValidationFlags flags = new FieldValidationFlags(
                VALID_FLAG, VALID_FLAG, VALID_FLAG);
        return new ValidationResult(true, false, safeVar, "", flags);
    }

    /**
     * Validates that a date is not in the future (date-of-birth check).
     *
     * <p>Translated from CSUTLDPY.cpy lines 341-372 (EDIT-DATE-OF-BIRTH paragraph).
     * Uses strictly-greater-than comparison: current date must be AFTER the birth date.
     * Birth date equal to today is REJECTED (you cannot be born today and use the system).
     *
     * <p>COBOL comparison (line 350):
     * {@code IF WS-CURRENT-DATE-BINARY > WS-EDIT-DATE-BINARY} →
     * current &gt; input means valid. If current ≤ input → error.
     *
     * @param variableName the field name for error messages
     * @param date         8-character CCYYMMDD date string
     * @return validation result indicating if the date is in the past
     */
    public ValidationResult editDateOfBirth(String variableName, String date) {
        String safeVar = variableName != null ? variableName : "";
        FieldValidationFlags flags = new FieldValidationFlags(VALID_FLAG, VALID_FLAG, VALID_FLAG);

        // MOVE FUNCTION CURRENT-DATE TO WS-CURRENT-DATE-YYYYMMDD (line 343)
        LocalDate currentDate = LocalDate.now();

        try {
            // Parse input date from CCYYMMDD format
            LocalDate editDate = LocalDate.parse(date, CCYYMMDD_FORMAT);

            // COBOL: IF WS-CURRENT-DATE-BINARY > WS-EDIT-DATE-BINARY → CONTINUE (valid)
            // Strictly greater than: current must be AFTER the birth date.
            // Same-day (born today) is REJECTED.
            if (currentDate.isAfter(editDate)) {
                // Valid — birth date is in the past
                return new ValidationResult(true, false, safeVar, "", flags);
            }

            // Birth date is today or in the future — error
            flags = new FieldValidationFlags(NOT_OK_FLAG, NOT_OK_FLAG, NOT_OK_FLAG);
            return new ValidationResult(false, true, safeVar,
                    safeVar.trim() + ":cannot be in the future ", flags);

        } catch (DateTimeParseException e) {
            // Malformed date that cannot be parsed
            flags = new FieldValidationFlags(NOT_OK_FLAG, NOT_OK_FLAG, NOT_OK_FLAG);
            return new ValidationResult(false, true, safeVar,
                    safeVar.trim() + ":cannot be in the future ", flags);
        }
    }

    // ========================================================================
    // General Field Validation Utility Methods
    // ========================================================================

    /**
     * Checks if all characters in the string are digits (0-9).
     * Maps the COBOL {@code IS NUMERIC} check.
     *
     * @param value the string to check
     * @return {@code true} if non-null, non-empty, and all characters are digits
     */
    public static boolean isNumeric(String value) {
        if (value == null || value.isEmpty()) {
            return false;
        }
        for (int i = 0; i < value.length(); i++) {
            if (!Character.isDigit(value.charAt(i))) {
                return false;
            }
        }
        return true;
    }

    /**
     * Checks for null, empty, or all-spaces input.
     * Maps the COBOL {@code EQUAL LOW-VALUES OR EQUAL SPACES} check.
     *
     * @param value the string to check
     * @return {@code true} if value is null, empty, or consists entirely of spaces
     */
    public static boolean isBlankOrNull(String value) {
        if (value == null || value.isEmpty()) {
            return true;
        }
        for (int i = 0; i < value.length(); i++) {
            if (value.charAt(i) != ' ') {
                return false;
            }
        }
        return true;
    }

    /**
     * Validates that a field is not blank and contains only numeric digits.
     * Used by COACTUPC.cbl for numeric fields like Credit Limit, FICO Score.
     *
     * @param variableName the field name for error messages
     * @param value        the field value to validate
     * @return validation result
     */
    public static ValidationResult validateNumericField(String variableName, String value) {
        String safeVar = variableName != null ? variableName : "";

        if (isBlankOrNull(value)) {
            return new ValidationResult(false, true, safeVar,
                    safeVar.trim() + " must be supplied.", null);
        }

        if (!isNumeric(value)) {
            return new ValidationResult(false, true, safeVar,
                    safeVar.trim() + " must be numeric.", null);
        }

        return new ValidationResult(true, false, safeVar, "", null);
    }

    /**
     * Validates that a required field is not blank or null.
     * Maps the COBOL blank/low-values check with error message pattern.
     *
     * @param variableName the field name for error messages
     * @param value        the field value to validate
     * @return validation result
     */
    public static ValidationResult validateRequiredField(String variableName, String value) {
        String safeVar = variableName != null ? variableName : "";

        if (isBlankOrNull(value)) {
            return new ValidationResult(false, true, safeVar,
                    safeVar.trim() + " must be supplied.", null);
        }

        return new ValidationResult(true, false, safeVar, "", null);
    }

    /**
     * Validates that a field does not exceed the maximum length.
     * Maps COBOL {@code PIC X(n)} length enforcement semantics.
     *
     * @param variableName the field name for error messages
     * @param value        the field value to validate
     * @param maxLength    the maximum allowed length
     * @return validation result
     */
    public static ValidationResult validateFieldLength(String variableName, String value,
                                                       int maxLength) {
        String safeVar = variableName != null ? variableName : "";

        if (value != null && value.length() > maxLength) {
            return new ValidationResult(false, true, safeVar,
                    safeVar.trim() + " exceeds maximum length of " + maxLength + ".", null);
        }

        return new ValidationResult(true, false, safeVar, "", null);
    }
}
