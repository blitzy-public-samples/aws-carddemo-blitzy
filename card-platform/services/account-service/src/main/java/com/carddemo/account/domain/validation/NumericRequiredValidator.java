package com.carddemo.account.domain.validation;

import com.carddemo.cobol.NumvalParser;
import java.math.BigDecimal;

/**
 * Edits a required numeric field. A valid field holds content, every character of that content is a
 * digit, and the converted value is not zero.
 *
 * <p>Realises paragraph {@code 1245-EDIT-NUM-REQD} at {@code app/cbl/COACTUPC.cbl:L2109}, whose
 * exit paragraph {@code 1245-EDIT-NUM-REQD-EXIT} sits at {@code app/cbl/COACTUPC.cbl:L2176}. Three
 * checks run in a fixed order. The not-supplied check sits at {@code app/cbl/COACTUPC.cbl:L2114},
 * the {@code IS NUMERIC} class check at {@code app/cbl/COACTUPC.cbl:L2137}, and the not-zero check
 * at {@code app/cbl/COACTUPC.cbl:L2156}. A failing check ends the paragraph at its {@code GO TO},
 * so the verdict carries the message of the first check that failed.</p>
 *
 * <p>Host widths: {@code WS-EDIT-VARIABLE-NAME PIC X(25)} at {@code app/cbl/COACTUPC.cbl:L53},
 * {@code WS-EDIT-ALPHANUM-ONLY PIC X(256)} at {@code app/cbl/COACTUPC.cbl:L61}, and
 * {@code WS-EDIT-ALPHANUM-LENGTH PIC S9(4) COMP-3} at {@code app/cbl/COACTUPC.cbl:L62}. A COBOL
 * {@code MOVE} into {@code WS-EDIT-ALPHANUM-ONLY} pads on the right with spaces. A space is not a
 * digit, so content shorter than {@code length} clears the not-supplied check and then fails the
 * class check with {@code ' must be all numeric.'}.</p>
 *
 * <p>Six call sites perform this paragraph, three of them in the customer edit sequence. A credit
 * score of width 3 arrives at {@code app/cbl/COACTUPC.cbl:L1549}, a postal code of width 5 at
 * {@code app/cbl/COACTUPC.cbl:L1608}, and an electronic funds transfer account identifier of width
 * 10 at {@code app/cbl/COACTUPC.cbl:L1652}. The other three sit in {@code 1265-EDIT-US-SSN}, the
 * Social Security Number paragraph at {@code app/cbl/COACTUPC.cbl:L2431}. Their widths are 3, 2,
 * and 4, at {@code app/cbl/COACTUPC.cbl:L2442}, {@code app/cbl/COACTUPC.cbl:L2472}, and
 * {@code app/cbl/COACTUPC.cbl:L2484}.</p>
 *
 * <p>The verdict carries at most one message, matching the single {@code WS-RETURN-MSG} slot at
 * {@code app/cbl/COACTUPC.cbl:L479}. Keeping the first message of a validation pass is the caller's
 * work. This class reads its arguments and changes none of them, so a second call on the same
 * arguments returns the same verdict.</p>
 *
 * <p>Every deviation this class carries from paragraph behaviour is labelled ADDITIVE below and
 * recorded in {@code card-platform/docs/decision-log.md} (planned).
 */
public final class NumericRequiredValidator {

    /**
     * Message literal at {@code app/cbl/COACTUPC.cbl:L2126}, eighteen characters wide, carrying
     * its leading space and its trailing period.
     */
    private static final String NOT_SUPPLIED_MESSAGE = " must be supplied.";

    /**
     * Message literal at {@code app/cbl/COACTUPC.cbl:L2146}, twenty-one characters wide, carrying
     * its leading space and its trailing period.
     */
    private static final String NOT_ALL_NUMERIC_MESSAGE = " must be all numeric.";

    /**
     * Message literal at {@code app/cbl/COACTUPC.cbl:L2163}, eighteen characters wide, carrying
     * its leading space and its trailing period.
     */
    private static final String IS_ZERO_MESSAGE = " must not be zero.";

    /**
     * ADDITIVE. Opens the message text for a value wider than the edited field. No source
     * literal carries this text.
     *
     * <p>The edit refuses a value wider than {@code WS-EDIT-ALPHANUM-LENGTH} at
     * {@code app/cbl/COACTUPC.cbl:L62} and reads none of its
     * characters.</p>
     */
    private static final String ADDITIVE_NO_LONGER_THAN = " must be no longer than ";

    /** ADDITIVE. Closes the message text {@link #ADDITIVE_NO_LONGER_THAN} opens. */
    private static final String ADDITIVE_CHARACTERS = " characters.";

    /**
     * Width of {@code WS-EDIT-ALPHANUM-ONLY PIC X(256)} at {@code app/cbl/COACTUPC.cbl:L61}. A
     * {@code MOVE} into that item drops every character past this position.
     */
    private static final int EDIT_FIELD_WIDTH = 256;

    /** The character COBOL {@code SPACES} supplies, and the character a {@code MOVE} pads with. */
    private static final char SPACE = ' ';

    /** The character COBOL {@code LOW-VALUES} supplies for a {@code PIC X} item. */
    private static final char LOW_VALUE = '\u0000';

    /** Lowest of the ten characters {@code IS NUMERIC} accepts on an alphanumeric item. */
    private static final char ZERO_DIGIT = '0';

    /** Highest of the ten characters {@code IS NUMERIC} accepts on an alphanumeric item. */
    private static final char NINE_DIGIT = '9';

    private NumericRequiredValidator() {
    }

    /**
     * Applies the edit to one field.
     *
     * <p>{@code app/cbl/COACTUPC.cbl:L2111} opens the paragraph with the failing verdict, and
     * {@code app/cbl/COACTUPC.cbl:L2174} sets the passing verdict once all three checks clear.
     * Each {@code GO TO 1245-EDIT-NUM-REQD-EXIT}, at {@code app/cbl/COACTUPC.cbl:L2132},
     * {@code app/cbl/COACTUPC.cbl:L2151}, and {@code app/cbl/COACTUPC.cbl:L2168}, returns from
     * here with one message.</p>
     *
     * <p>A {@code null} value, an empty value, and a {@code length} of zero or below each yield
     * the not-supplied verdict. The label is trimmed and never truncated to its
     * {@code PIC X(25)} host width. No argument is modified.</p>
     *
     * <p>The not-zero check converts through {@link NumvalParser#numval(String)}, which refuses an
     * argument holding more than {@link NumvalParser#MAXIMUM_DIGITS} digits and throws
     * {@link NumberFormatException}. That ceiling is the {@code ARITH(COMPAT)} ceiling the source's
     * own {@code FUNCTION NUMVAL} carries. Every one of the six call-site widths is 10 or below, so
     * no migrated path reaches it.</p>
     *
     * @param fieldLabel the field name the message opens with, held in
     *                   {@code WS-EDIT-VARIABLE-NAME}; may be {@code null}
     * @param value      the submitted characters, moved to {@code WS-EDIT-ALPHANUM-ONLY}; may be
     *                   {@code null}
     * @param length     the declared field width the edit covers, held in
     *                   {@code WS-EDIT-ALPHANUM-LENGTH}
     * @return {@link EditResult#ok()} when all three checks clear, otherwise a failure verdict
     *         carrying one message
     */
    public static EditResult validate(String fieldLabel, String value, int length) {

        // app/cbl/COACTUPC.cbl:L2111 opens the paragraph with the failing verdict.
        // A width of zero or below yields the not-supplied verdict.
        if (length <= 0) {
            return EditResult.failure(trimSpaces(fieldLabel) + NOT_SUPPLIED_MESSAGE);
        }

        // ADDITIVE. A value wider than the edited field is refused.
        if (carriesContentPastEditedWidth(value, length)) {
            return EditResult.failure(trimSpaces(fieldLabel) + ADDITIVE_NO_LONGER_THAN
                    + length + ADDITIVE_CHARACTERS);
        }

        String editField = referenceModifiedField(value, length);

        // app/cbl/COACTUPC.cbl:L2114-L2119, the three-way not-supplied test.
        if (isNotSupplied(value, editField)) {
            // app/cbl/COACTUPC.cbl:L2132 leaves the paragraph here.
            return EditResult.failure(trimSpaces(fieldLabel) + NOT_SUPPLIED_MESSAGE);
        }

        // app/cbl/COACTUPC.cbl:L2137-L2138, the IS NUMERIC class test.
        if (!isNumeric(editField)) {
            // app/cbl/COACTUPC.cbl:L2151 leaves the paragraph here.
            return EditResult.failure(trimSpaces(fieldLabel) + NOT_ALL_NUMERIC_MESSAGE);
        }

        // app/cbl/COACTUPC.cbl:L2156-L2157, the not-zero test. The condition holds when the
        // converted value is zero, and app/cbl/COACTUPC.cbl:L2158 places the failure in the THEN
        // branch. app/cbl/COACTUPC.cbl:L2169-L2170 is the ELSE CONTINUE that falls through.
        if (isZero(editField)) {
            // app/cbl/COACTUPC.cbl:L2168 leaves the paragraph here.
            return EditResult.failure(trimSpaces(fieldLabel) + IS_ZERO_MESSAGE);
        }

        // app/cbl/COACTUPC.cbl:L2174 sets the valid flag.
        return EditResult.ok();
    }

    /**
     * Builds the content {@code WS-EDIT-ALPHANUM-ONLY(1:WS-EDIT-ALPHANUM-LENGTH)} holds when the
     * edit starts.
     *
     * <p>A COBOL {@code MOVE} into {@code PIC X(256)} pads on the right with spaces and drops any
     * character past the 256th. The returned copy is exactly {@code length} characters wide,
     * capped at {@link #EDIT_FIELD_WIDTH}. A {@code null} value yields a copy of spaces.</p>
     *
     * @param value  the submitted characters, which this method never changes; may be {@code null}
     * @param length the declared field width, one or above
     */
    private static String referenceModifiedField(String value, int length) {
        int width = Math.min(length, EDIT_FIELD_WIDTH);
        String moved = value == null ? "" : value;

        if (moved.length() >= width) {
            return moved.substring(0, width);
        }

        return moved + String.valueOf(SPACE).repeat(width - moved.length());
    }

    /**
     * Applies the three-way not-supplied test at {@code app/cbl/COACTUPC.cbl:L2114-L2119}.
     *
     * <p>The first arm is {@code EQUAL LOW-VALUES} at {@code app/cbl/COACTUPC.cbl:L2115}, which
     * covers a {@code null} value, an empty value, and a field of low-value characters. The second
     * arm is {@code EQUAL SPACES} at {@code app/cbl/COACTUPC.cbl:L2117}. The third arm is
     * {@code FUNCTION LENGTH(FUNCTION TRIM(...)) = 0} at
     * {@code app/cbl/COACTUPC.cbl:L2118-L2119}. Any one arm yields the not-supplied verdict.</p>
     *
     * <p>The matching test in {@code 1250-EDIT-SIGNED-9V2} at
     * {@code app/cbl/COACTUPC.cbl:L2184-L2185} carries two arms and no trim arm. Each paragraph
     * stands as the source writes it.</p>
     *
     * @param value     the submitted characters; may be {@code null}
     * @param editField the reference-modified field content, never {@code null}
     */
    private static boolean isNotSupplied(String value, String editField) {
        return value == null
                || value.isEmpty()
                || containsOnly(editField, LOW_VALUE)
                || containsOnly(editField, SPACE)
                || trimSpaces(editField).isEmpty();
    }

    /**
     * Reports whether every character of a field equals one character.
     *
     * <p>Reproduces a COBOL comparison against a figurative constant, which the compiler expands
     * to the width of the compared item.</p>
     *
     * @param editField the reference-modified field content, never {@code null}
     * @param expected  the character every position must hold
     */
    private static boolean containsOnly(String editField, char expected) {
        for (int index = 0; index < editField.length(); index++) {
            if (editField.charAt(index) != expected) {
                return false;
            }
        }

        return true;
    }

    /**
     * Applies the {@code IS NUMERIC} class test at {@code app/cbl/COACTUPC.cbl:L2137-L2138}.
     *
     * <p>{@code WS-EDIT-ALPHANUM-ONLY} is {@code PIC X(256)} at
     * {@code app/cbl/COACTUPC.cbl:L61}, an alphanumeric item. On such an item {@code IS NUMERIC}
     * holds only when every character of the tested range is a digit. A sign, a decimal point, and
     * a space each fail the test, including the space a {@code MOVE} pads short content with.</p>
     */
    private static boolean isNumeric(String editField) {
        for (int index = 0; index < editField.length(); index++) {
            if (!isDigit(editField.charAt(index))) {
                return false;
            }
        }

        return true;
    }

    private static boolean isDigit(char character) {
        return Character.isDigit(character)
                && character >= ZERO_DIGIT
                && character <= NINE_DIGIT;
    }

    /**
     * Applies the not-zero test at {@code app/cbl/COACTUPC.cbl:L2156-L2157}.
     *
     * <p>{@code FUNCTION NUMVAL} converts the tested range, and the COBOL condition holds when
     * the converted value equals zero. {@link NumvalParser#numval(String)} reproduces that
     * function and applies no scale, so this test reads the sign of the value and never its scale.
     * A field of {@code 0} and a field of {@code 000} both answer {@code true}.</p>
     *
     * @param editField the reference-modified field content, every character already a digit
     * @return {@code true} when the converted value is zero
     */
    private static boolean isZero(String editField) {
        BigDecimal converted = NumvalParser.numval(editField);

        return converted.signum() == 0;
    }

    /**
     * Removes leading and trailing spaces.
     *
     * <p>Reproduces {@code FUNCTION TRIM} at {@code app/cbl/COACTUPC.cbl:L2125},
     * {@code app/cbl/COACTUPC.cbl:L2145}, and {@code app/cbl/COACTUPC.cbl:L2162}, which removes
     * the space character alone. Every other character survives, including a low value. The label
     * keeps the width it arrives at, since the caller's own assignment sets the
     * {@code PIC X(25)} host width.</p>
     *
     * @param text the characters to trim; may be {@code null}
     * @return the trimmed text, or an empty string when {@code text} is {@code null}
     */
    private static String trimSpaces(String text) {
        if (text == null) {
            return "";
        }

        int start = 0;
        int end = text.length();

        while (start < end && text.charAt(start) == SPACE) {
            start++;
        }
        while (end > start && text.charAt(end - 1) == SPACE) {
            end--;
        }

        return text.substring(start, end);
    }

    /**
     * Reports whether the value carries a character other than a space past the edited width.
     *
     * <p>ADDITIVE. The source moves a fixed-width screen field into its edit field, so the
     * {@code MOVE} drops nothing but padding. A Representational State Transfer (REST) caller can
     * supply a wider value. Trailing spaces past the width are the padding the source itself holds.
     * Any other character past the width is content the edit does not inspect.</p>
     *
     * <p>A width of zero or less inspects nothing, and this test reports false for it, leaving the
     * not-supplied arm to answer.</p>
     *
     * @param value  submitted value, which may be null
     * @param length count of characters the edit inspects
     * @return true when a character other than a space sits past a positive {@code length}
     */
    private static boolean carriesContentPastEditedWidth(String value, int length) {
        if (length < 1 || value == null || value.length() <= length) {
            return false;
        }

        for (int position = length; position < value.length(); position++) {
            if (value.charAt(position) != SPACE) {
                return true;
            }
        }

        return false;
    }
}
