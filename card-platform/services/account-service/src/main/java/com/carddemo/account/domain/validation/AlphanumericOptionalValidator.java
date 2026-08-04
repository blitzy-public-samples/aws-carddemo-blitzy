package com.carddemo.account.domain.validation;

/**
 * Edits one optional alphanumeric field: a value that, when present, holds only letters,
 * digits and spaces.
 *
 * <p>Translates {@code 1240-EDIT-ALPHANUM-OPT} at {@code app/cbl/COACTUPC.cbl:L2061}, whose
 * exit paragraph stands at {@code app/cbl/COACTUPC.cbl:L2105}.
 *
 * <p>An absent value passes. {@code app/cbl/COACTUPC.cbl:L2072} accepts a field that equals
 * {@code LOW-VALUES}, equals spaces, or trims to nothing. A {@code null} argument and an
 * empty argument both reach that branch.
 *
 * <p>A supplied value passes when every character belongs to the 62-character group
 * {@code LIT-ALL-ALPHANUM-FROM-X} at {@code app/cbl/COACTUPC.cbl:L586-L593}: 26 upper-case
 * letters, 26 lower-case letters, and 10 digits. A space also passes, and the group holds no
 * space. {@code app/cbl/COACTUPC.cbl:L2079-L2087} turns every group member into a space and
 * then tests the result for spaces alone.
 *
 * <p>A failing value yields one message, built at {@code app/cbl/COACTUPC.cbl:L2092-L2098}
 * from the trimmed field label and the literal
 * {@code ' can have numbers or alphabets only.'}. This class produces no second message.
 *
 * <p>Every deviation this class carries from paragraph behaviour is labelled ADDITIVE below and
 * recorded in {@code card-platform/docs/decision-log.md} (planned).
 */
public final class AlphanumericOptionalValidator {

    /** The 26 characters of {@code LIT-UPPER PIC X(26)} at {@code app/cbl/COACTUPC.cbl:L588-L589}. */
    private static final String UPPER_CASE_LETTERS = "ABCDEFGHIJKLMNOPQRSTUVWXYZ";

    /** The 26 characters of {@code LIT-LOWER PIC X(26)} at {@code app/cbl/COACTUPC.cbl:L590-L591}. */
    private static final String LOWER_CASE_LETTERS = "abcdefghijklmnopqrstuvwxyz";

    /** The 10 characters of {@code LIT-NUMBERS PIC X(10)} at {@code app/cbl/COACTUPC.cbl:L592-L593}. */
    private static final String DIGIT_CHARACTERS = "0123456789";

    /**
     * The 62 characters of the group {@code LIT-ALL-ALPHANUM-FROM-X} at
     * {@code app/cbl/COACTUPC.cbl:L586-L593}, held in
     * {@code LIT-ALL-ALPHANUM-FROM PIC X(62)} at {@code app/cbl/COACTUPC.cbl:L608}.
     */
    private static final String ALLOWED_CHARACTERS =
            UPPER_CASE_LETTERS + LOWER_CASE_LETTERS + DIGIT_CHARACTERS;

    /**
     * The replacement character supplied by
     * {@code LIT-ALPHANUM-SPACES-TO PIC X(62) VALUE SPACES} at
     * {@code app/cbl/COACTUPC.cbl:L611}. It also pads a value shorter than the inspected
     * width.
     */
    private static final char SPACE = ' ';

    /**
     * The character a COBOL comparison against the figurative constant
     * {@code LOW-VALUES} tests for, one position at a time.
     */
    private static final char NULL_CHARACTER = '\0';

    /**
     * The message literal at {@code app/cbl/COACTUPC.cbl:L2095}. It is 36 characters wide and
     * carries its leading space and its trailing period.
     */
    private static final String CHARACTER_CLASS_MESSAGE = " can have numbers or alphabets only.";

    /**
     * The width of {@code WS-EDIT-ALPHANUM-ONLY PIC X(256)} at
     * {@code app/cbl/COACTUPC.cbl:L61}. The inspected width never exceeds it.
     */
    private static final int EDIT_FIELD_WIDTH = 256;

    /**
     * ADDITIVE. Opens the message text for a value wider than the inspected field. No source
     * literal carries this text.
     *
     * <p>The edit refuses a value wider than {@code WS-EDIT-ALPHANUM-LENGTH} at
     * {@code app/cbl/COACTUPC.cbl:L62} and reads none of its
     * characters.</p>
     */
    private static final String ADDITIVE_NO_LONGER_THAN = " must be no longer than ";

    /** ADDITIVE. Closes the message text {@link #ADDITIVE_NO_LONGER_THAN} opens. */
    private static final String ADDITIVE_CHARACTERS = " characters.";

    private AlphanumericOptionalValidator() {
    }

    /**
     * Edits one optional alphanumeric field and reports the verdict.
     *
     * <p>Reads the first {@code length} characters of {@code value}, padding with spaces when
     * the value is shorter. No argument is mutated: the conversion at
     * {@code app/cbl/COACTUPC.cbl:L2080-L2082} runs on a copy. Two calls with the same
     * argument return the same verdict.
     *
     * @param fieldLabel the field name the message opens with, trimmed and never truncated;
     *                   the source holds it in {@code WS-EDIT-VARIABLE-NAME PIC X(25)} at
     *                   {@code app/cbl/COACTUPC.cbl:L53}; {@code null} contributes no text
     * @param value      the submitted characters; {@code null} and an empty string both pass
     * @param length     the inspected width, held in {@code WS-EDIT-ALPHANUM-LENGTH} at
     *                   {@code app/cbl/COACTUPC.cbl:L62}; a width of zero or less inspects
     *                   nothing and passes
     * @return {@link EditResult#ok()} when the field passes, and
     *         {@link EditResult#failure(String)} carrying one message when it fails
     */
    public static EditResult validate(String fieldLabel, String value, int length) {
        // ADDITIVE. A value wider than the inspected field is refused.
        if (carriesContentPastEditedWidth(value, length)) {
            return EditResult.failure(trimmedLabel(fieldLabel) + ADDITIVE_NO_LONGER_THAN
                    + length + ADDITIVE_CHARACTERS);
        }

        String inspected = inspectionWindow(value, length);

        // Not supplied: app/cbl/COACTUPC.cbl:L2065-L2073 accepts an absent optional value. The
        // first arm compares the window against LOW-VALUES and the second against SPACES. The
        // third holds exactly when the second holds, because FUNCTION TRIM removes the space and
        // no other character.
        if (holdsLowValues(inspected) || holdsOnlySpaces(inspected)) {
            return EditResult.ok();
        }

        // app/cbl/COACTUPC.cbl:L2079-L2087. Group members become spaces, and any character
        // surviving the space-only trim sits outside the group. A tab, a newline and a null
        // character each survive, so each one fails.
        String survivors = replaceAllowedCharactersWithSpaces(inspected);
        if (holdsOnlySpaces(survivors)) {
            // app/cbl/COACTUPC.cbl:L2103.
            return EditResult.ok();
        }

        // app/cbl/COACTUPC.cbl:L2090-L2100.
        return EditResult.failure(trimmedLabel(fieldLabel) + CHARACTER_CLASS_MESSAGE);
    }

    /**
     * Reports whether every character of a field equals one character.
     *
     * <p>Reproduces a COBOL comparison against a figurative constant, which the compiler expands
     * to the width of the compared item. An empty field holds no character that differs, so it
     * reports true.</p>
     *
     * @param field    the inspected characters, never {@code null}
     * @param expected the character every position must hold
     * @return {@code true} when every position holds {@code expected}
     */
    private static boolean holdsOnly(String field, char expected) {
        for (int position = 0; position < field.length(); position++) {
            if (field.charAt(position) != expected) {
                return false;
            }
        }

        return true;
    }

    /**
     * Copies the first {@code length} characters of the value and pads the copy with spaces
     * when the value is shorter.
     *
     * <p>The reference modification {@code WS-EDIT-ALPHANUM-ONLY(1:WS-EDIT-ALPHANUM-LENGTH)}
     * at {@code app/cbl/COACTUPC.cbl:L2066} reads a fixed-width field that a preceding
     * {@code MOVE} pads on the right. A width below zero yields no characters, and a width
     * above {@value #EDIT_FIELD_WIDTH} yields {@value #EDIT_FIELD_WIDTH} characters.
     *
     * @param value  the submitted characters; may be {@code null}
     * @param length the requested width
     * @return exactly the clamped width in characters, and the argument stays untouched
     */
    private static String inspectionWindow(String value, int length) {
        int width = Math.min(Math.max(length, 0), EDIT_FIELD_WIDTH);
        String supplied = value == null ? "" : value;
        StringBuilder window = new StringBuilder(width);
        for (int position = 0; position < width; position++) {
            window.append(position < supplied.length() ? supplied.charAt(position) : SPACE);
        }
        return window.toString();
    }

    /**
     * Returns a copy in which every occurrence of a character from
     * {@link #ALLOWED_CHARACTERS} is a space.
     *
     * <p>Carries out the conversion of {@code INSPECT ... CONVERTING} at
     * {@code app/cbl/COACTUPC.cbl:L2080-L2082} on a copy. The source statement overwrites its
     * field in place.
     *
     * @param inspected the window to copy
     * @return a copy of the same width in which only the characters outside the group remain
     */
    private static String replaceAllowedCharactersWithSpaces(String inspected) {
        StringBuilder converted = new StringBuilder(inspected.length());
        for (int position = 0; position < inspected.length(); position++) {
            char character = inspected.charAt(position);
            converted.append(ALLOWED_CHARACTERS.indexOf(character) < 0 ? character : SPACE);
        }
        return converted.toString();
    }

    /**
     * Reports whether a window holds no character other than a space, which is what
     * {@code FUNCTION LENGTH(FUNCTION TRIM(...)) = 0} answers at
     * {@code app/cbl/COACTUPC.cbl:L2070-L2071} and {@code app/cbl/COACTUPC.cbl:L2086-L2089}.
     * {@code FUNCTION TRIM} removes the space and no other character, so a null character, a tab
     * and a line break each survive and each answer false. An empty window answers true.
     *
     * @param window the window to measure
     * @return true when the window holds no character other than a space
     */
    private static boolean holdsOnlySpaces(String window) {
        for (int position = 0; position < window.length(); position++) {
            if (window.charAt(position) != SPACE) {
                return false;
            }
        }
        return true;
    }

    /**
     * Reports the {@code EQUAL LOW-VALUES} arm at {@code app/cbl/COACTUPC.cbl:L2067}. A COBOL
     * comparison against {@code LOW-VALUES} holds when every character position carries
     * {@code X'00'}, so one character other than the null character answers false. An empty
     * window carries no position and answers false, leaving the spaces arm to accept it.
     *
     * @param window the window to measure
     * @return true when the window is not empty and every character is the null character
     */
    private static boolean holdsLowValues(String window) {
        if (window.isEmpty()) {
            return false;
        }
        for (int position = 0; position < window.length(); position++) {
            if (window.charAt(position) != NULL_CHARACTER) {
                return false;
            }
        }
        return true;
    }

    /**
     * Trims the field label for the message, as {@code FUNCTION TRIM(WS-EDIT-VARIABLE-NAME)}
     * does at {@code app/cbl/COACTUPC.cbl:L2094}. The label keeps its full width; the source
     * truncates it at the assignment its caller makes.
     *
     * @param fieldLabel the field name; may be {@code null}
     * @return the trimmed label, and an empty string for a {@code null} label
     */
    private static String trimmedLabel(String fieldLabel) {
        return fieldLabel == null ? "" : fieldLabel.trim();
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
