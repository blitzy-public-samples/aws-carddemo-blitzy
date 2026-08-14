package com.carddemo.account.domain.validation;

/**
 * Refuses a submitted value wider than the field the source declares.
 *
 * <p>No COBOL ancestor, and the reason is the shape of the original interface. Every field this
 * edit guards arrives from a fixed-width area of the map {@code app/bms/COACTUP.bms} defines, so a
 * value wider than the field could not be keyed and could not be moved anywhere. A
 * Representational State Transfer (REST) caller has no such bound: it sends a string of any length,
 * and the width the request record declares is the only thing that says how wide the field is.</p>
 *
 * <p>The same reasoning already runs inside three edits of this package.
 * {@link NumericRequiredValidator}, {@link AlphabeticRequiredValidator} and
 * {@link AlphanumericRequiredValidator} each refuse content past the width they are given, under
 * the text this class also writes, and {@link DomainEdit#heldWidth()} records why. This class
 * carries the same rule for the fields whose edit takes no width at all: the five monetary fields,
 * whose edit at {@code app/cbl/COACTUPC.cbl:L2180} reads the value whole and checks no width; the
 * four dates, whose edit slices four, two and two characters at
 * {@code app/cpy/CSUTLDPY.cpy} and reads nothing past position eight; and the fields no edit reads
 * at all, which the source bounds by width alone.</p>
 *
 * <p>Order matters and is deliberate. This edit runs before every field edit and before any
 * conversion, because in the source a wider value never reached an edit: the map field bounded it
 * first. Running it later would mean editing, converting and storing a value the field cannot
 * hold, which is how a monetary value wider than
 * {@code WS-EDIT-SIGNED-NUMBER-9V2-X PIC X(15)} at {@code app/cbl/COACTUPC.cbl:L55} reached the
 * store and lost its high-order digits, and how a text value wider than its column reached the
 * database and was answered as a fault rather than as a refused field.</p>
 *
 * <p>Trailing spaces past the width are not content. The source moves a whole fixed-width field
 * into its edit area, so the padding it carries is dropped by the move and inspected by nothing.
 * This edit reads the same way: a value whose characters past the declared width are all spaces
 * passes, and any other character past the width fails. {@link NumericRequiredValidator} applies
 * that same reading to the width it inspects.</p>
 *
 * <p>The message names the field and the width, and never the value. A rejected government
 * identifier, Social Security Number part or monetary figure therefore reaches no response body and
 * no log line through this class.</p>
 */
public final class DeclaredWidthValidator {

    /**
     * No COBOL ancestor. Opens the message text for a value wider than the field it was submitted
     * for, and is the text {@link NumericRequiredValidator} and the two alphanumeric edits already
     * write for the same failure, so one caller reads one wording whichever field it names.
     */
    private static final String ADDITIVE_NO_LONGER_THAN = " must be no longer than ";

    /** No COBOL ancestor. Closes the message text {@link #ADDITIVE_NO_LONGER_THAN} opens. */
    private static final String ADDITIVE_CHARACTERS = " characters.";

    /** The character COBOL {@code SPACES} supplies, and the character a {@code MOVE} pads with. */
    private static final char SPACE = ' ';

    private DeclaredWidthValidator() {
    }

    /**
     * Applies the width edit to one submitted value.
     *
     * <p>A value that is absent, that fits the width, or that carries nothing but spaces past it,
     * passes and carries no message. Any other value fails and carries one message naming the field
     * and the width.</p>
     *
     * @param fieldLabel the field name the message opens with, trimmed as
     *                   {@code FUNCTION TRIM(WS-EDIT-VARIABLE-NAME)} trims it at
     *                   {@code app/cbl/COACTUPC.cbl:L2190}; may be {@code null}
     * @param value      the submitted text, read and never changed; may be {@code null}
     * @param width      the width the source field declares, from its Picture clause
     * @return {@link EditResult#ok()} for a value the field can hold, otherwise a failing verdict
     *         carrying one message
     */
    public static EditResult validate(String fieldLabel, String value, int width) {
        if (!carriesContentPastDeclaredWidth(value, width)) {
            return EditResult.ok();
        }

        return EditResult.failure(trimSpaces(fieldLabel) + ADDITIVE_NO_LONGER_THAN + width
                + ADDITIVE_CHARACTERS);
    }

    /**
     * Reports whether the value carries a character other than a space past the declared width.
     *
     * <p>A width of zero or less declares no field, so this test reports false for it rather than
     * refusing every value.</p>
     *
     * @param value the submitted value, which may be {@code null}
     * @param width the width the source field declares
     * @return {@code true} when a character other than a space sits past a positive {@code width}
     */
    private static boolean carriesContentPastDeclaredWidth(String value, int width) {
        if (width < 1 || value == null || value.length() <= width) {
            return false;
        }

        for (int position = width; position < value.length(); position++) {
            if (value.charAt(position) != SPACE) {
                return true;
            }
        }

        return false;
    }

    /**
     * Trims the spaces a label carries at either end.
     *
     * @param text the label, which may be {@code null}
     * @return the label without its leading or trailing spaces, and an empty text for {@code null}
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
}
