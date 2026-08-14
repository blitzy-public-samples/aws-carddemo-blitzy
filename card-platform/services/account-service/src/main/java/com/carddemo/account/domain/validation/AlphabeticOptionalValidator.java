package com.carddemo.account.domain.validation;

/**
 * Edits an optional alphabetic field. A value that is present may hold letters and spaces only.
 *
 * <p>Reproduces paragraph {@code 1235-EDIT-ALPHA-OPT} at {@code app/cbl/COACTUPC.cbl:L2012},
 * through its exit paragraph at {@code app/cbl/COACTUPC.cbl:L2057}.
 *
 * <p>An absent value passes. {@code app/cbl/COACTUPC.cbl:L2024} sets the valid condition for a
 * slice that holds {@code LOW-VALUES} or {@code SPACES}, and
 * {@code app/cbl/COACTUPC.cbl:L2025} leaves the paragraph. The required form of the same edit,
 * {@code 1225-EDIT-ALPHA-REQD} at {@code app/cbl/COACTUPC.cbl:L1898}, raises an error at that
 * point.
 *
 * <p>The accepted characters are {@code LIT-UPPER PIC X(26)} plus {@code LIT-LOWER PIC X(26)} at
 * {@code app/cbl/COACTUPC.cbl:L586-L591}: 52 characters, both cases. A space passes and is not a
 * member of that set.
 *
 * <p>One message reaches the caller, {@code ' can have alphabets only.'} at
 * {@code app/cbl/COACTUPC.cbl:L2047}.
 */
public final class AlphabeticOptionalValidator {

    /**
     * The only message this edit produces, the literal at {@code app/cbl/COACTUPC.cbl:L2047}.
     * Twenty-five characters, opening with a space and closing with a period.
     */
    private static final String ALPHABETIC_ONLY_MESSAGE = " can have alphabets only.";

    /** {@code LIT-UPPER PIC X(26)} at {@code app/cbl/COACTUPC.cbl:L588-L589}. */
    private static final String UPPER_CASE_LETTERS = "ABCDEFGHIJKLMNOPQRSTUVWXYZ";

    /** {@code LIT-LOWER PIC X(26)} at {@code app/cbl/COACTUPC.cbl:L590-L591}. */
    private static final String LOWER_CASE_LETTERS = "abcdefghijklmnopqrstuvwxyz";

    /**
     * The 52 characters the conversion at {@code app/cbl/COACTUPC.cbl:L2032-L2034} replaces,
     * holding what {@code LIT-ALL-ALPHA-FROM PIC X(52)} at {@code app/cbl/COACTUPC.cbl:L607}
     * receives from {@code LIT-ALL-ALPHA-FROM-X}.
     */
    private static final String ALPHABETIC_FROM = UPPER_CASE_LETTERS + LOWER_CASE_LETTERS;

    /**
     * The single character {@code LIT-ALPHA-SPACES-TO PIC X(52) VALUE SPACES} at
     * {@code app/cbl/COACTUPC.cbl:L610} supplies for every replacement, and the padding character
     * of an alphanumeric {@code MOVE}.
     */
    private static final char SPACE = ' ';

    /**
     * The character a COBOL comparison against the figurative constant
     * {@code LOW-VALUES} tests for, one position at a time.
     */
    private static final char NULL_CHARACTER = '\0';

    /**
     * ADDITIVE. Opens the message text for a value wider than the edited field; no source literal
     * carries this text. Design decisions: {@code card-platform/docs/decision-log.md}.
     */
    private static final String ADDITIVE_NO_LONGER_THAN = " must be no longer than ";

    /** No COBOL ancestor. Closes the message text {@link #ADDITIVE_NO_LONGER_THAN} opens. */
    private static final String ADDITIVE_CHARACTERS = " characters.";

    private AlphabeticOptionalValidator() {
    }

    /**
     * Edits one optional alphabetic field.
     *
     * <p>The field is read as the first {@code length} characters of
     * {@code WS-EDIT-ALPHANUM-ONLY PIC X(256)} at {@code app/cbl/COACTUPC.cbl:L61}, padded on the
     * right with spaces when the value is shorter. The argument is never altered, and repeated
     * calls with one value return one verdict.
     *
     * @param fieldLabel the name the message opens with, trimmed for the message and never
     *                   truncated; the width of {@code WS-EDIT-VARIABLE-NAME PIC X(25)} at
     *                   {@code app/cbl/COACTUPC.cbl:L53} is applied by the caller
     * @param value      the field to edit; {@code null}, an empty value and an all-space value
     *                   each pass
     * @param length     the edited width, {@code 25} at the call site at
     *                   {@code app/cbl/COACTUPC.cbl:L1570}; zero or less passes
     * @return {@link EditResult#ok()} when the field is absent or holds letters and spaces only,
     *         and {@link EditResult#failure(String)} carrying the one message otherwise
     */
    public static EditResult validate(String fieldLabel, String value, int length) {
        // app/cbl/COACTUPC.cbl:L2018 tests the slice against LOW-VALUES.
        if (value == null) {
            return EditResult.ok();
        }

        // No COBOL ancestor. A value wider than the edited field is refused.
        if (carriesContentPastEditedWidth(value, length)) {
            return EditResult.failure(trimmedLabel(fieldLabel) + ADDITIVE_NO_LONGER_THAN
                    + length + ADDITIVE_CHARACTERS);
        }

        String slice = editSlice(value, length);

        // app/cbl/COACTUPC.cbl:L2017-L2025 accepts an absent value. The first arm compares the
        // slice against LOW-VALUES and the second against SPACES.
        if (holdsLowValues(slice) || holdsOnlySpaces(slice)) {
            return EditResult.ok();
        }

        // app/cbl/COACTUPC.cbl:L2031-L2034 converts every accepted character to a space.
        String converted = convertAlphabeticToSpaces(slice);

        // app/cbl/COACTUPC.cbl:L2036-L2040 accepts the slice when nothing survives, and
        // app/cbl/COACTUPC.cbl:L2042-L2052 reports the failure otherwise.
        if (holdsOnlySpaces(converted)) {
            return EditResult.ok();
        }

        return EditResult.failure(trimmedLabel(fieldLabel) + ALPHABETIC_ONLY_MESSAGE);
    }

    /**
     * Builds the slice the edit reads, reproducing an alphanumeric {@code MOVE} into
     * {@code WS-EDIT-ALPHANUM-ONLY PIC X(256)} followed by the reference modification
     * {@code (1:WS-EDIT-ALPHANUM-LENGTH)}.
     *
     * <p>The result is always {@code length} characters wide. Characters beyond {@code length} are
     * dropped, and a value shorter than {@code length} is padded on the right with spaces. A
     * {@code length} of zero or less yields an empty slice.
     *
     * @param value  the value to place in the slice
     * @param length the slice width
     * @return exactly {@code length} characters, or an empty string when {@code length} is zero or
     *         less
     */
    private static String editSlice(String value, int length) {
        if (length <= 0) {
            return "";
        }

        StringBuilder slice = new StringBuilder(length);
        slice.append(value, 0, Math.min(value.length(), length));
        while (slice.length() < length) {
            slice.append(SPACE);
        }
        return slice.toString();
    }

    /**
     * Replaces every character of {@link #ALPHABETIC_FROM} with a space, reproducing
     * {@code INSPECT ... CONVERTING} at {@code app/cbl/COACTUPC.cbl:L2032-L2034}. A character
     * outside that set is left unchanged, which includes a space.
     *
     * <p>The source statement writes over its operand. This method returns a new string and reads
     * its argument only.
     *
     * @param slice the slice to convert
     * @return a string of the same width, holding a space at every accepted position
     */
    private static String convertAlphabeticToSpaces(String slice) {
        StringBuilder converted = new StringBuilder(slice.length());
        for (int position = 0; position < slice.length(); position++) {
            char character = slice.charAt(position);
            converted.append(ALPHABETIC_FROM.indexOf(character) < 0 ? character : SPACE);
        }
        return converted.toString();
    }

    /**
     * Reports whether every character is a space, which is the condition
     * {@code FUNCTION LENGTH(FUNCTION TRIM(...)) = 0} tests at
     * {@code app/cbl/COACTUPC.cbl:L2021-L2022} and {@code app/cbl/COACTUPC.cbl:L2036-L2039}.
     * {@code FUNCTION TRIM} removes the space and no other character, so a tab and a line break
     * both survive and both fail this test. An empty string holds no character that is not a
     * space and passes.
     *
     * @param slice the slice to measure
     * @return true when the slice holds no character other than a space
     */
    private static boolean holdsOnlySpaces(String slice) {
        for (int position = 0; position < slice.length(); position++) {
            if (slice.charAt(position) != SPACE) {
                return false;
            }
        }
        return true;
    }

    /**
     * Reports the {@code EQUAL LOW-VALUES} arm at {@code app/cbl/COACTUPC.cbl:L2018}. A COBOL
     * comparison against {@code LOW-VALUES} holds when every character position of the slice
     * carries {@code X'00'}, so one character other than the null character answers false. An
     * empty slice carries no position and answers false, leaving the spaces arm to accept it.
     *
     * @param slice the slice to measure
     * @return true when the slice is not empty and every character is the null character
     */
    private static boolean holdsLowValues(String slice) {
        if (slice.isEmpty()) {
            return false;
        }
        for (int position = 0; position < slice.length(); position++) {
            if (slice.charAt(position) != NULL_CHARACTER) {
                return false;
            }
        }
        return true;
    }

    /**
     * Trims the label for the message, reproducing
     * {@code FUNCTION TRIM(WS-EDIT-VARIABLE-NAME)} at {@code app/cbl/COACTUPC.cbl:L2046}. A
     * {@code null} label yields an empty opening, matching a label field left at
     * {@code SPACES}.
     *
     * @param fieldLabel the label to trim; may be {@code null}
     * @return the label without leading or trailing white space, at its full length
     */
    private static String trimmedLabel(String fieldLabel) {
        return fieldLabel == null ? "" : fieldLabel.trim();
    }

    /**
     * Reports whether the value carries a character other than a space past the edited width.
     *
     * <p>ADDITIVE. Trailing spaces past the width are the padding the source field itself holds;
     * any other character past it is content. A width of zero or less inspects nothing and this
     * test reports false, leaving the not-supplied arm to answer.</p>
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
