package com.carddemo.account.domain.validation;

/**
 * Checks that a mandatory field holds a value within its declared length.
 *
 * <p>Realises paragraph {@code 1215-EDIT-MANDATORY} at {@code app/cbl/COACTUPC.cbl:L1824}, whose
 * exit paragraph sits at {@code app/cbl/COACTUPC.cbl:L1852}. A call site moves a label, a value,
 * and a length into working storage, then performs the paragraph. This class takes those three
 * pieces directly.
 *
 * <p>The not-supplied test at {@code app/cbl/COACTUPC.cbl:L1829-L1834} holds three alternatives.
 * The reference-modified substring equals {@code LOW-VALUES}, or it equals {@code SPACES}, or
 * {@code FUNCTION LENGTH(FUNCTION TRIM(...))} of it is zero. Any one of the three yields the
 * not-supplied verdict.
 *
 * <p>Host widths: {@code WS-EDIT-VARIABLE-NAME PIC X(25)} at {@code app/cbl/COACTUPC.cbl:L53},
 * {@code WS-EDIT-ALPHANUM-ONLY PIC X(256)} at {@code app/cbl/COACTUPC.cbl:L61}, and
 * {@code WS-EDIT-ALPHANUM-LENGTH PIC S9(4) COMP-3} at {@code app/cbl/COACTUPC.cbl:L62}. A
 * {@code MOVE} into {@code WS-EDIT-ALPHANUM-ONLY} pads on the right with spaces, so a value shorter
 * than its declared length is padded the same way here.
 *
 * <p>This class tests presence only. Character class, numeric class, and maximum length each belong
 * to a separate paragraph and a separate class. The verdict carries at most one message, matching
 * the single {@code WS-RETURN-MSG} slot at {@code app/cbl/COACTUPC.cbl:L479}. Keeping the first
 * message of a pass is the caller's work.
 */
public final class MandatoryFieldValidator {

    /**
     * The message text appended to the trimmed label. The literal at
     * {@code app/cbl/COACTUPC.cbl:L1841}, eighteen characters wide, carrying its leading space
     * and its trailing period.
     */
    private static final String NOT_SUPPLIED_SUFFIX = " must be supplied.";

    /** The character COBOL {@code LOW-VALUES} supplies for a {@code PIC X} item. */
    private static final char LOW_VALUE_CHARACTER = '\u0000';

    /** The character COBOL {@code SPACES} supplies, and the character a {@code MOVE} pads with. */
    private static final char SPACE_CHARACTER = ' ';

    private MandatoryFieldValidator() {
    }

    /**
     * Reports whether a mandatory field holds a value.
     *
     * <p>Yields the failure verdict when the first {@code length} characters of {@code value}
     * are all low values, all spaces, or empty once trimmed. The failure message is the trimmed
     * label followed by {@code ' must be supplied.'}.
     *
     * <p>A {@code null} value and an empty value both take the low-values path. A
     * {@code length} of zero or below yields the failure verdict. The label is trimmed and never
     * truncated to the {@code PIC X(25)} host width. No argument is modified, and no validation
     * failure throws.
     *
     * @param fieldLabel the field name, held in {@code WS-EDIT-VARIABLE-NAME}; may be
     *                   {@code null}
     * @param value      the submitted characters, moved to {@code WS-EDIT-ALPHANUM-ONLY}; may be
     *                   {@code null}
     * @param length     the declared field length, held in {@code WS-EDIT-ALPHANUM-LENGTH}
     * @return {@link EditResult#ok()} when the field holds a value, otherwise a failure verdict
     *         carrying one message
     */
    public static EditResult validate(String fieldLabel, String value, int length) {

        // app/cbl/COACTUPC.cbl:L1826 sets the not-ok flag on entry.
        // A length of zero or below cannot reach the paragraph from any call site.
        if (length <= 0) {
            return notSupplied(fieldLabel);
        }

        // app/cbl/COACTUPC.cbl:L1829-L1830, the LOW-VALUES alternative.
        if (value == null || value.isEmpty()) {
            return notSupplied(fieldLabel);
        }

        String field = referenceModified(value, length);

        boolean allLowValues = containsOnly(field, LOW_VALUE_CHARACTER);
        boolean allSpaces = containsOnly(field, SPACE_CHARACTER);
        boolean emptyWhenTrimmed = trimSpaces(field).isEmpty();

        // app/cbl/COACTUPC.cbl:L1829-L1834, the three alternatives.
        if (allLowValues || allSpaces || emptyWhenTrimmed) {
            // app/cbl/COACTUPC.cbl:L1847 leaves the paragraph here.
            return notSupplied(fieldLabel);
        }

        // app/cbl/COACTUPC.cbl:L1850 sets the valid flag.
        return EditResult.ok();
    }

    /**
     * Builds the not-supplied verdict from the label.
     *
     * <p>Assembles the message the {@code STRING} statement at
     * {@code app/cbl/COACTUPC.cbl:L1839-L1844} produces: {@code FUNCTION TRIM} of the label,
     * then the literal, each delimited by size.
     *
     * @param fieldLabel the field name; may be {@code null}
     */
    private static EditResult notSupplied(String fieldLabel) {
        return EditResult.failure(trimSpaces(fieldLabel) + NOT_SUPPLIED_SUFFIX);
    }

    /**
     * Applies COBOL reference modification to a value.
     *
     * <p>Yields the first {@code length} characters. When the value is shorter than
     * {@code length}, the result carries the value followed by spaces, matching the padding a
     * {@code MOVE} into {@code WS-EDIT-ALPHANUM-ONLY PIC X(256)} performs.
     *
     * @param value  the submitted characters, never {@code null} and never empty
     * @param length the width to read, in characters
     */
    private static String referenceModified(String value, int length) {
        if (value.length() >= length) {
            return value.substring(0, length);
        }
        return value + String.valueOf(SPACE_CHARACTER).repeat(length - value.length());
    }

    /**
     * Reports whether every character of a field equals one character.
     *
     * <p>Reproduces a COBOL comparison against a figurative constant, which the compiler
     * expands to the width of the compared item.
     *
     * @param field    the reference-modified characters, never {@code null}
     * @param expected the character every position must hold
     */
    private static boolean containsOnly(String field, char expected) {
        for (int index = 0; index < field.length(); index++) {
            if (field.charAt(index) != expected) {
                return false;
            }
        }
        return true;
    }

    /**
     * Removes leading and trailing spaces.
     *
     * <p>Reproduces {@code FUNCTION TRIM}, which removes the space character alone. Every other
     * character survives, including a low value.
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

        while (start < end && text.charAt(start) == SPACE_CHARACTER) {
            start++;
        }
        while (end > start && text.charAt(end - 1) == SPACE_CHARACTER) {
            end--;
        }

        return text.substring(start, end);
    }
}
