package com.carddemo.account.domain.validation;

/**
 * Required alphabetic field edit, from paragraph {@code 1225-EDIT-ALPHA-REQD} at
 * app/cbl/COACTUPC.cbl:L1898 through its exit paragraph on line 1951.
 *
 * <p>A value passes when it is supplied and holds letters and spaces only. The allowed set is the
 * 52 characters of group {@code LIT-ALL-ALPHA-FROM-X} at app/cbl/COACTUPC.cbl:L586-L591, being
 * {@code LIT-UPPER PIC X(26)} followed by {@code LIT-LOWER PIC X(26)}. Upper case and lower case
 * both pass. A digit, a hyphen, an apostrophe, a period and an accented letter each fail.</p>
 *
 * <p>A space is not a member of the 52-character set. Line 1925 moves the group into
 * {@code LIT-ALL-ALPHA-FROM PIC X(52)}, and lines 1926 to 1928 convert every member to a space with
 * {@code INSPECT ... CONVERTING}. Lines 1930 to 1933 then pass the value when
 * {@code FUNCTION LENGTH(FUNCTION TRIM(...)) = 0}. A space survives the conversion, and the trim
 * removes it.</p>
 *
 * <p>This class runs those two steps over a copy. The argument is unchanged, and two calls with the
 * same value return the same verdict.</p>
 *
 * <p>app/cbl/COACTUPC.cbl applies this edit to five fields. They are First Name at line 1563, Last
 * Name at 1579, State at 1595, City at 1618 and Country at 1627. The optional form of the same edit
 * is paragraph {@code 1235-EDIT-ALPHA-OPT} at line 2012, which accepts an absent value.</p>
 */
public final class AlphabeticRequiredValidator {

    /**
     * The 26 upper-case letters of {@code LIT-UPPER PIC X(26)} at app/cbl/COACTUPC.cbl:L588-L589.
     */
    private static final String LIT_UPPER = "ABCDEFGHIJKLMNOPQRSTUVWXYZ";

    /**
     * The 26 lower-case letters of {@code LIT-LOWER PIC X(26)} at app/cbl/COACTUPC.cbl:L590-L591.
     */
    private static final String LIT_LOWER = "abcdefghijklmnopqrstuvwxyz";

    /**
     * The 52 characters the conversion replaces with a space. Line 1925 moves group
     * {@code LIT-ALL-ALPHA-FROM-X} into {@code LIT-ALL-ALPHA-FROM PIC X(52)},
     * declared at app/cbl/COACTUPC.cbl:L607. The group holds {@code LIT-UPPER} then
     * {@code LIT-LOWER}, and it excludes {@code LIT-NUMBERS PIC X(10)} at line 592.
     */
    private static final String LIT_ALL_ALPHA_FROM = LIT_UPPER + LIT_LOWER;

    /**
     * The character every member of the 52-character set becomes. The conversion
     * target is {@code LIT-ALPHA-SPACES-TO PIC X(52) VALUE SPACES} at
     * app/cbl/COACTUPC.cbl:L610. The same character pads a short value, matching the
     * {@code MOVE} into {@code WS-EDIT-ALPHANUM-ONLY PIC X(256)} at line 61.
     */
    private static final char SPACE = ' ';

    /**
     * The character a COBOL comparison against the figurative constant
     * {@code LOW-VALUES} tests for, one position at a time.
     */
    private static final char NULL_CHARACTER = '\0';

    /**
     * Message text for an absent value, from the literal at
     * app/cbl/COACTUPC.cbl:L1915. The leading space separates it from the label, and
     * the period ends the sentence the source builds.
     */
    private static final String MUST_BE_SUPPLIED = " must be supplied.";

    /**
     * Message text for a character outside the 52-character set, from the literal at
     * app/cbl/COACTUPC.cbl:L1941.
     */
    private static final String CAN_HAVE_ALPHABETS_ONLY = " can have alphabets only.";

    /**
     * ADDITIVE. Opens the message text for a value wider than the edited field; no source literal
     * carries this text. Rationale: {@code card-platform/docs/decision-log.md}.
     */
    private static final String ADDITIVE_NO_LONGER_THAN = " must be no longer than ";

    /** No COBOL ancestor. Closes the message text {@link #ADDITIVE_NO_LONGER_THAN} opens. */
    private static final String ADDITIVE_CHARACTERS = " characters.";

    private AlphabeticRequiredValidator() {
    }

    /**
     * Applies the edit to one field and returns the verdict.
     *
     * <p>A value wider than {@code length} is refused before any other test, and the failure
     * text names the width. That arm has no COBOL ancestor.</p>
     *
     * <p>Otherwise the first {@code length} characters of {@code value} are copied and padded
     * with spaces to {@code length}. That copy matches the {@code MOVE} into
     * {@code WS-EDIT-ALPHANUM-ONLY PIC X(256)} at app/cbl/COACTUPC.cbl:L61 and the
     * reference modification {@code (1:WS-EDIT-ALPHANUM-LENGTH)} at line 1903. A
     * {@code length} of zero or less yields an empty copy, and the field counts as
     * not supplied.</p>
     *
     * <p>Every test on the copy compares against the space character alone, as COBOL
     * {@code FUNCTION TRIM} and {@code EQUAL SPACES} do. A tab, a newline and a null character
     * are therefore not blank here, and each one fails the 52-character set test.</p>
     *
     * <p>Both messages start from the trimmed label, matching
     * {@code FUNCTION TRIM(WS-EDIT-VARIABLE-NAME)} at lines 1914 and 1940. Line 1915
     * supplies {@code " must be supplied."} for an absent value. Line 1941 supplies
     * {@code " can have alphabets only."} for a character outside the 52-character
     * set.</p>
     *
     * @param fieldLabel screen label of the field, trimmed into both messages; the
     *                   source field is {@code WS-EDIT-VARIABLE-NAME PIC X(25)} at
     *                   app/cbl/COACTUPC.cbl:L53, and a null label adds no text
     * @param value      submitted value; null and the empty string take the
     *                   {@code LOW-VALUES} arm at line 1904
     * @param length     count of characters to inspect, held in
     *                   {@code WS-EDIT-ALPHANUM-LENGTH PIC S9(4) COMP-3} at
     *                   app/cbl/COACTUPC.cbl:L62; the five call sites pass 25, 25, 2,
     *                   50 and 3
     * @return {@link EditResult#ok()} when the value passes, and
     *         {@link EditResult#failure(String)} carrying one message when it fails
     */
    public static EditResult validate(String fieldLabel, String value, int length) {
        // No COBOL ancestor. A value wider than the edited field is refused.
        if (carriesContentPastEditedWidth(value, length)) {
            return EditResult.failure(trimmedLabel(fieldLabel) + ADDITIVE_NO_LONGER_THAN
                    + length + ADDITIVE_CHARACTERS);
        }

        final String inspected = fixedWidthCopy(value, length);

        // Not supplied: the three-way test at app/cbl/COACTUPC.cbl:L1903-L1908. The third arm,
        // FUNCTION LENGTH(FUNCTION TRIM(slice)) = 0, holds exactly when the second arm holds,
        // because FUNCTION TRIM removes the space and no other character.
        if (isLowValues(value) || holdsLowValues(inspected) || isAllSpaces(inspected)) {
            return EditResult.failure(trimmedLabel(fieldLabel) + MUST_BE_SUPPLIED);
        }

        // Only alphabets and space allowed: app/cbl/COACTUPC.cbl:L1924-L1933. A character
        // outside the 52-character set survives the conversion, and only a space is trimmed.
        final String converted = convertAlphabetsToSpaces(inspected);
        if (!isAllSpaces(converted)) {
            return EditResult.failure(trimmedLabel(fieldLabel) + CAN_HAVE_ALPHABETS_ONLY);
        }

        // Success: SET FLG-ALPHA-ISVALID TO TRUE at app/cbl/COACTUPC.cbl:L1949.
        return EditResult.ok();
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

    /**
     * Copies the first {@code length} characters of {@code value} and pads the copy
     * with spaces to {@code length}. A null value, a short value and a
     * {@code length} of zero or less all produce a copy of spaces or an empty copy.
     * The argument is read and never written.
     *
     * @param value  submitted value, which may be null or shorter than {@code length}
     * @param length width of the copy, clamped at zero
     */
    private static String fixedWidthCopy(String value, int length) {
        final int width = Math.max(length, 0);
        final String source = value == null ? "" : value;
        final StringBuilder copy = new StringBuilder(width);
        for (int position = 0; position < width; position++) {
            copy.append(position < source.length() ? source.charAt(position) : SPACE);
        }
        return copy.toString();
    }

    /**
     * Reports the {@code EQUAL LOW-VALUES} arm at app/cbl/COACTUPC.cbl:L1904. Null
     * and the empty string map to that arm.
     *
     * @param value submitted value, which may be null
     */
    private static boolean isLowValues(String value) {
        return value == null || value.isEmpty();
    }

    /**
     * Reports whether the inspected copy holds the figurative constant
     * {@code LOW-VALUES}, the first arm at app/cbl/COACTUPC.cbl:L1904. A COBOL
     * comparison against {@code LOW-VALUES} holds when every character position of
     * the slice carries {@code X'00'}, so one character other than the null
     * character answers false.
     *
     * @param inspected the fixed-width copy
     * @return true when the copy is not empty and every character is the null
     *         character
     */
    private static boolean holdsLowValues(String inspected) {
        if (inspected.isEmpty()) {
            return false;
        }
        for (int position = 0; position < inspected.length(); position++) {
            if (inspected.charAt(position) != NULL_CHARACTER) {
                return false;
            }
        }
        return true;
    }

    /**
     * Reports the {@code EQUAL SPACES} arm at app/cbl/COACTUPC.cbl:L1906. An empty
     * copy holds no character that is not a space, so it reports true.
     *
     * @param inspected the fixed-width copy
     */
    private static boolean isAllSpaces(String inspected) {
        for (int position = 0; position < inspected.length(); position++) {
            if (inspected.charAt(position) != SPACE) {
                return false;
            }
        }
        return true;
    }

    /**
     * Converts every member of the 52-character set to a space, as
     * {@code INSPECT ... CONVERTING} does at app/cbl/COACTUPC.cbl:L1926-L1928. Any
     * other character is carried through unchanged, so it remains for the trim test
     * at lines 1930 to 1933. The conversion writes to a new array of characters.
     *
     * @param inspected the fixed-width copy
     */
    private static String convertAlphabetsToSpaces(String inspected) {
        final char[] converted = inspected.toCharArray();
        for (int position = 0; position < converted.length; position++) {
            if (LIT_ALL_ALPHA_FROM.indexOf(converted[position]) >= 0) {
                converted[position] = SPACE;
            }
        }
        return new String(converted);
    }

    /**
     * Trims the label for both messages, as
     * {@code FUNCTION TRIM(WS-EDIT-VARIABLE-NAME)} does at app/cbl/COACTUPC.cbl:L1914
     * and line 1940. The label is trimmed and never shortened to a fixed width.
     *
     * @param fieldLabel screen label of the field, which may be null
     * @return the trimmed label, empty when the label is null
     */
    private static String trimmedLabel(String fieldLabel) {
        return fieldLabel == null ? "" : fieldLabel.trim();
    }
}
