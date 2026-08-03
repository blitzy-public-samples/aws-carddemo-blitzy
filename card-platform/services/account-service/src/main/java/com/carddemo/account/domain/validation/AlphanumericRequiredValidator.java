package com.carddemo.account.domain.validation;

/**
 * Edits a required field that may hold letters, digits and spaces only.
 *
 * <p>Translates paragraph {@code 1230-EDIT-ALPHANUM-REQD} at
 * {@code app/cbl/COACTUPC.cbl:L1955}, whose exit paragraph sits on line 2009. The
 * edit fails when the field arrives with no content. It fails again when the field
 * holds any character outside the allowed set.</p>
 *
 * <p>The allowed set holds 62 characters: 26 upper-case letters, 26 lower-case
 * letters and ten digits. {@code app/cbl/COACTUPC.cbl:L586-L593} declares them as
 * group {@code LIT-ALL-ALPHANUM-FROM-X}, and line 1982 moves that group into
 * {@code LIT-ALL-ALPHANUM-FROM PIC X(62)}. A space is not a member of the set.
 * Spaces survive the conversion on line 1984, and the trim on line 1988 removes
 * them.</p>
 *
 * <p>Line 1984 runs {@code INSPECT ... CONVERTING} over the edit field itself, which
 * overwrites it. This class converts a copy. The supplied value reaches the caller
 * unchanged, and a second call on the same value returns the same verdict.</p>
 *
 * <p>The guard {@code IF WS-RETURN-MSG-OFF} on lines 1969 and 1996 keeps the first
 * message of a validation pass. {@code EditResult} carries one message, and the
 * caller that collects results applies the guard.</p>
 *
 * <p>Rationale for every choice in this class lives in
 * {@code card-platform/docs/decision-log.md}.</p>
 */
public final class AlphanumericRequiredValidator {

    /**
     * The 62 characters the edit accepts. Group {@code LIT-ALL-ALPHANUM-FROM-X} at
     * {@code app/cbl/COACTUPC.cbl:L586-L593} declares them in three parts:
     * {@code LIT-UPPER PIC X(26)}, {@code LIT-LOWER PIC X(26)} and
     * {@code LIT-NUMBERS PIC X(10)}.
     */
    private static final String ALLOWED_CHARACTERS =
            "ABCDEFGHIJKLMNOPQRSTUVWXYZ"
                    + "abcdefghijklmnopqrstuvwxyz"
                    + "0123456789";

    /**
     * Width of {@code WS-EDIT-ALPHANUM-ONLY PIC X(256)} at
     * {@code app/cbl/COACTUPC.cbl:L61}.
     */
    private static final int EDIT_FIELD_WIDTH = 256;

    /**
     * The character {@code LIT-ALPHANUM-SPACES-TO PIC X(62) VALUE SPACES} at
     * {@code app/cbl/COACTUPC.cbl:L611} writes over an allowed character.
     */
    private static final char SPACE = ' ';

    /**
     * Message literal at {@code app/cbl/COACTUPC.cbl:L1972}, eighteen characters
     * wide, carrying a leading space and a trailing period.
     */
    private static final String NOT_SUPPLIED_MESSAGE = " must be supplied.";

    /**
     * Message literal at {@code app/cbl/COACTUPC.cbl:L1999}, thirty-six characters
     * wide, carrying a leading space and a trailing period.
     */
    private static final String NOT_ALPHANUMERIC_MESSAGE =
            " can have numbers or alphabets only.";

    /** This class holds static members only. */
    private AlphanumericRequiredValidator() {
    }

    /**
     * Applies the edit to one field.
     *
     * <p>Line 1957 opens the paragraph with a failing verdict, and line 2007 sets the
     * passing verdict once both tests clear. Each {@code GO TO
     * 1230-EDIT-ALPHANUM-REQD-EXIT}, on lines 1978 and 2004, returns here with a
     * message.</p>
     *
     * @param fieldLabel the field name the message opens with, trimmed as
     *                   {@code FUNCTION TRIM(WS-EDIT-VARIABLE-NAME)} does on lines
     *                   1971 and 1998
     * @param value      the submitted text, read and never changed
     * @param length     the field width the edit covers, taken from
     *                   {@code WS-EDIT-ALPHANUM-LENGTH} at
     *                   {@code app/cbl/COACTUPC.cbl:L62}
     * @return a passing verdict, or a failing verdict carrying one message
     */
    public static EditResult validate(String fieldLabel, String value, int length) {
        String editField = referenceModifiedField(value, length);

        if (isNotSupplied(value, editField)) {
            return EditResult.failure(trimmedLabel(fieldLabel) + NOT_SUPPLIED_MESSAGE);
        }

        if (!convertAllowedCharactersToSpaces(editField).trim().isEmpty()) {
            return EditResult.failure(trimmedLabel(fieldLabel) + NOT_ALPHANUMERIC_MESSAGE);
        }

        return EditResult.ok();
    }

    /**
     * Builds the content that
     * {@code WS-EDIT-ALPHANUM-ONLY(1:WS-EDIT-ALPHANUM-LENGTH)} holds when the edit
     * starts.
     *
     * <p>A COBOL {@code MOVE} into {@code PIC X(256)} pads on the right with spaces
     * and drops any character past the 256th. The returned copy is exactly
     * {@code length} characters wide, capped at {@link #EDIT_FIELD_WIDTH}. A null
     * value yields a copy of spaces. A length of zero or less yields a copy of no
     * characters.</p>
     *
     * @param value  the submitted text, which this method never changes
     * @param length the requested width
     * @return the reference-modified field content
     */
    private static String referenceModifiedField(String value, int length) {
        int width = Math.min(Math.max(length, 0), EDIT_FIELD_WIDTH);
        String moved = value == null ? "" : value;

        if (moved.length() >= width) {
            return moved.substring(0, width);
        }

        return moved + String.valueOf(SPACE).repeat(width - moved.length());
    }

    /**
     * Applies the three-way not-supplied test at
     * {@code app/cbl/COACTUPC.cbl:L1960-L1965}.
     *
     * <p>A null value and an empty value hold the place of {@code EQUAL LOW-VALUES}.
     * The other two arms are {@code EQUAL SPACES} and
     * {@code FUNCTION LENGTH(FUNCTION TRIM(...)) = 0}.</p>
     *
     * @param value     the submitted text
     * @param editField the reference-modified field content
     * @return true when the field arrived with no content
     */
    private static boolean isNotSupplied(String value, String editField) {
        return value == null
                || value.isEmpty()
                || isAllSpaces(editField)
                || editField.trim().isEmpty();
    }

    /**
     * Answers the {@code EQUAL SPACES} arm on line 1963.
     *
     * @param text the field content to test
     * @return true when the argument holds no character other than a space
     */
    private static boolean isAllSpaces(String text) {
        for (int index = 0; index < text.length(); index++) {
            if (text.charAt(index) != SPACE) {
                return false;
            }
        }

        return true;
    }

    /**
     * Applies {@code INSPECT ... CONVERTING LIT-ALL-ALPHANUM-FROM TO
     * LIT-ALPHANUM-SPACES-TO} from {@code app/cbl/COACTUPC.cbl:L1984-L1986} to a
     * copy. Every character of {@code ALLOWED_CHARACTERS} becomes a space. Every
     * other character keeps its position.
     *
     * @param editField the reference-modified field content
     * @return the converted copy
     */
    private static String convertAllowedCharactersToSpaces(String editField) {
        StringBuilder converted = new StringBuilder(editField.length());

        for (int index = 0; index < editField.length(); index++) {
            char character = editField.charAt(index);
            converted.append(ALLOWED_CHARACTERS.indexOf(character) < 0 ? character : SPACE);
        }

        return converted.toString();
    }

    /**
     * Trims the field label, as {@code FUNCTION TRIM(WS-EDIT-VARIABLE-NAME)} does on
     * lines 1971 and 1998. {@code WS-EDIT-VARIABLE-NAME} is {@code PIC X(25)} at
     * {@code app/cbl/COACTUPC.cbl:L53}, so the caller's assignment sets that width.
     * This method returns the label at the width it arrives.
     *
     * @param fieldLabel the field name, null tolerated
     * @return the label with leading and trailing white space removed
     */
    private static String trimmedLabel(String fieldLabel) {
        return fieldLabel == null ? "" : fieldLabel.trim();
    }
}
