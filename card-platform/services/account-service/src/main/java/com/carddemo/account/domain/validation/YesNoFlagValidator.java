package com.carddemo.account.domain.validation;

/**
 * Edits a one-character yes/no flag. A value of {@code Y} or a value of {@code N} passes.
 *
 * <p>Realises {@code 1220-EDIT-YESNO} at {@code app/cbl/COACTUPC.cbl:L1856}, whose exit paragraph
 * {@code 1220-EDIT-YESNO-EXIT} sits at {@code app/cbl/COACTUPC.cbl:L1894}. The edited field is
 * {@code WS-EDIT-YES-NO PIC X(1)} at {@code app/cbl/COACTUPC.cbl:L76-L80}. One byte there holds
 * both the value and the flag, across three condition names:
 * <ul>
 *   <li>{@code 88 FLG-YES-NO-ISVALID VALUES 'Y', 'N'.} at {@code app/cbl/COACTUPC.cbl:L78}</li>
 *   <li>{@code 88 FLG-YES-NO-NOT-OK VALUE '0'.} at {@code app/cbl/COACTUPC.cbl:L79}</li>
 *   <li>{@code 88 FLG-YES-NO-BLANK VALUE 'B'.} at {@code app/cbl/COACTUPC.cbl:L80}</li>
 * </ul>
 *
 * <p>The paragraph runs two tests in order. The first covers a value that was not supplied, at
 * {@code app/cbl/COACTUPC.cbl:L1861-L1863}. The second is {@code IF FLG-YES-NO-ISVALID} at
 * {@code app/cbl/COACTUPC.cbl:L1878}, which reads the two accepted characters. Each failing test
 * ends the paragraph, at {@code app/cbl/COACTUPC.cbl:L1874} and {@code app/cbl/COACTUPC.cbl:L1891}.
 *
 * <p>Two call sites supply the label and the value. {@code 'Account Status'} is edited at
 * {@code app/cbl/COACTUPC.cbl:L1472-L1475}, and {@code 'Primary Card Holder'} at
 * {@code app/cbl/COACTUPC.cbl:L1657-L1661}.
 *
 * <p>The message guard {@code IF WS-RETURN-MSG-OFF} at {@code app/cbl/COACTUPC.cbl:L1866} and
 * {@code app/cbl/COACTUPC.cbl:L1883} belongs to the caller. This class returns one message on every
 * failure, and {@link EditResult#hasMessage()} carries the guard.
 */
public final class YesNoFlagValidator {

    /**
     * The first value {@code 88 FLG-YES-NO-ISVALID} accepts, at {@code app/cbl/COACTUPC.cbl:L78}.
     */
    public static final String YES = "Y";

    /**
     * The second value {@code 88 FLG-YES-NO-ISVALID} accepts, at {@code app/cbl/COACTUPC.cbl:L78}.
     */
    public static final String NO = "N";

    /**
     * The message literal at {@code app/cbl/COACTUPC.cbl:L1869}. Eighteen characters, opening with
     * one space and closing with one period.
     */
    public static final String NOT_SUPPLIED_MESSAGE_SUFFIX = " must be supplied.";

    /**
     * The message literal at {@code app/cbl/COACTUPC.cbl:L1886}. Sixteen characters, opening with
     * one space and closing with one period.
     */
    public static final String NOT_YES_OR_NO_MESSAGE_SUFFIX = " must be Y or N.";

    /** The character {@code LOW-VALUES} supplies to a {@code PIC X} field. */
    private static final char LOW_VALUES_CHARACTER = '\u0000';

    /** The character {@code SPACES} supplies to a {@code PIC X} field. */
    private static final char SPACES_CHARACTER = ' ';

    /** The character {@code ZEROS} supplies to a {@code PIC X} field. */
    private static final char ZEROS_CHARACTER = '0';

    private YesNoFlagValidator() {
    }

    /**
     * Edits one yes/no flag and reports the verdict.
     *
     * <p>{@value #YES} and {@value #NO} pass. A {@code null} value, an empty value, and a value
     * whose every character is the {@code LOW-VALUES} character, a space, or {@code 0} each yield
     * the trimmed {@code fieldLabel} followed by {@value #NOT_SUPPLIED_MESSAGE_SUFFIX}. Every
     * other value yields the trimmed {@code fieldLabel} followed by
     * {@value #NOT_YES_OR_NO_MESSAGE_SUFFIX}.
     *
     * <p>A value of {@code 0} reaches the first test, and a value of {@code B} reaches the second.
     * The test is case sensitive, so a value of {@code y} and a value of {@code n} fail.
     *
     * <p>This method reads its arguments and writes nothing back. Repeated calls with equal
     * arguments return equal verdicts.
     *
     * @param fieldLabel the field name the message opens with, modelled on
     *                   {@code WS-EDIT-VARIABLE-NAME PIC X(25)} at
     *                   {@code app/cbl/COACTUPC.cbl:L53}. Trimmed for the message and never
     *                   truncated. A {@code null} label contributes no characters
     * @param value      the flag to edit, one character wide in the source field
     * @return {@link EditResult#ok()} for {@value #YES} or {@value #NO}, and a failing
     *         {@link EditResult} carrying one message for every other value
     */
    public static EditResult validate(String fieldLabel, String value) {

        // app/cbl/COACTUPC.cbl:L1861-L1863 - EQUAL LOW-VALUES, EQUAL SPACES, EQUAL ZEROS.
        if (value == null
                || value.isEmpty()
                || isEveryCharacter(value, LOW_VALUES_CHARACTER)
                || isEveryCharacter(value, SPACES_CHARACTER)
                || isEveryCharacter(value, ZEROS_CHARACTER)) {

            // app/cbl/COACTUPC.cbl:L1874 - GO TO 1220-EDIT-YESNO-EXIT.
            return EditResult.failure(message(fieldLabel, NOT_SUPPLIED_MESSAGE_SUFFIX));
        }

        // app/cbl/COACTUPC.cbl:L1878 - IF FLG-YES-NO-ISVALID reads the two accepted values.
        if (YES.equals(value) || NO.equals(value)) {

            // app/cbl/COACTUPC.cbl:L1879 - CONTINUE.
            return EditResult.ok();
        }

        // app/cbl/COACTUPC.cbl:L1891 - GO TO 1220-EDIT-YESNO-EXIT.
        return EditResult.failure(message(fieldLabel, NOT_YES_OR_NO_MESSAGE_SUFFIX));
    }

    /**
     * Assembles the message that {@code STRING FUNCTION TRIM(WS-EDIT-VARIABLE-NAME)} builds at
     * {@code app/cbl/COACTUPC.cbl:L1867-L1872} and {@code app/cbl/COACTUPC.cbl:L1884-L1889}. The
     * label loses its leading and trailing spaces, the suffix is copied character for character,
     * and the result carries no width limit.
     *
     * @param fieldLabel    the field name, which may be {@code null}
     */
    private static String message(String fieldLabel, String messageSuffix) {
        String trimmedLabel = fieldLabel == null ? "" : fieldLabel.trim();
        return trimmedLabel + messageSuffix;
    }

    /**
     * Reports whether every character of a value equals one character. A COBOL figurative constant
     * expands to the width of the field it is compared against, so {@code EQUAL LOW-VALUES},
     * {@code EQUAL SPACES} and {@code EQUAL ZEROS} hold only when no character differs.
     *
     * @param value             the value to read, which may be {@code null}
     */
    private static boolean isEveryCharacter(String value, char expectedCharacter) {
        if (value == null || value.isEmpty()) {
            return false;
        }
        for (int index = 0; index < value.length(); index++) {
            if (value.charAt(index) != expectedCharacter) {
                return false;
            }
        }
        return true;
    }
}
