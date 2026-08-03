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
 * <p>Rationale for every choice in this class lives in
 * {@code card-platform/docs/decision-log.md}.
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
     * The message literal at {@code app/cbl/COACTUPC.cbl:L2095}. It is 36 characters wide and
     * carries its leading space and its trailing period.
     */
    private static final String CHARACTER_CLASS_MESSAGE = " can have numbers or alphabets only.";

    /**
     * The width of {@code WS-EDIT-ALPHANUM-ONLY PIC X(256)} at
     * {@code app/cbl/COACTUPC.cbl:L61}. The inspected width never exceeds it.
     */
    private static final int EDIT_FIELD_WIDTH = 256;

    /** This class holds static members only. */
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
        String inspected = inspectionWindow(value, length);

        // Not supplied: app/cbl/COACTUPC.cbl:L2065-L2073 accepts an absent optional value.
        if (inspected.trim().isEmpty()) {
            return EditResult.ok();
        }

        // app/cbl/COACTUPC.cbl:L2079-L2087. Group members become spaces, and any character
        // surviving the trim sits outside the group.
        String survivors = replaceAllowedCharactersWithSpaces(inspected);
        if (survivors.trim().isEmpty()) {
            // app/cbl/COACTUPC.cbl:L2103.
            return EditResult.ok();
        }

        // app/cbl/COACTUPC.cbl:L2090-L2100.
        return EditResult.failure(trimmedLabel(fieldLabel) + CHARACTER_CLASS_MESSAGE);
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
}
