package com.carddemo.account.domain.validation;

import com.carddemo.cobol.reference.UsStateCodes;

/**
 * Checks that a two-character state code is one of the 56 codes CardDemo accepts.
 *
 * <p>Realises paragraph {@code 1270-EDIT-US-STATE-CD} at app/cbl/COACTUPC.cbl:L2493,
 * whose exit paragraph sits at app/cbl/COACTUPC.cbl:L2511. The accepted band is
 * app/cpy/CSLKPCDY.cpy:L1012-L1069: the host field
 * {@code 01 US-STATE-CODE-TO-EDIT PIC X(2).} at L1012, the condition name
 * {@code VALID-US-STATE-CODE} at L1013, and 56 literals at L1014 through L1069. This
 * edit tests membership in that band and nothing more.</p>
 *
 * <p>app/cbl/COACTUPC.cbl:L2494 fills the {@code PIC X(2)} host field with a plain
 * {@code MOVE}. Letter case therefore stays significant, and no space leaves either end
 * of the submitted value. A value of {@code "ny"}, {@code "N "}, or two spaces matches no
 * literal and fails. The failure text repeats app/cbl/COACTUPC.cbl:L2502-L2503: the
 * label under {@code FUNCTION TRIM}, then {@code ': is not a valid state code'}.</p>
 *
 * <p>The caller owns the edits that surround this one, including the character-class
 * edit at app/cbl/COACTUPC.cbl:L1595 and the state-and-zip edit
 * {@code 1280-EDIT-US-STATE-ZIP-CD} at app/cbl/COACTUPC.cbl:L2536.</p>
 *
 * <p>Every deviation this class carries from paragraph behaviour is labelled ADDITIVE below and
 * recorded in {@code card-platform/docs/decision-log.md} (planned).
 */
public final class UsStateCodeValidator {

    /**
     * Failure text at app/cbl/COACTUPC.cbl:L2503. Twenty-seven characters, opening with a
     * colon and a space, closing with no period.
     */
    private static final String NOT_A_VALID_STATE_CODE = ": is not a valid state code";

    /**
     * Width of the host field {@code 01 US-STATE-CODE-TO-EDIT PIC X(2).} at
     * app/cpy/CSLKPCDY.cpy:L1012.
     */
    private static final int STATE_CODE_LENGTH = 2;

    /** That same host field holding spaces in both positions. */
    private static final String HOST_FIELD_SPACES = "  ";

    private UsStateCodeValidator() {
    }

    /**
     * Applies the state-code edit to one submitted value.
     *
     * <p>app/cbl/COACTUPC.cbl:L2496 answers a match with a bare {@code CONTINUE} and
     * leaves every flag as the preceding edit set it. This method answers a match with a
     * passing verdict, writes nothing back, and mutates neither argument.</p>
     *
     * @param fieldLabel the label the caller moved into {@code WS-EDIT-VARIABLE-NAME},
     *                   declared {@code PIC X(25)} at app/cbl/COACTUPC.cbl:L53;
     *                   app/cbl/COACTUPC.cbl:L1592 supplies {@code 'State'}; may be
     *                   {@code null}
     * @param stateCode  the submitted value of {@code ACUP-NEW-CUST-ADDR-STATE-CD},
     *                   declared {@code PIC X(02)} at app/cbl/COACTUPC.cbl:L807; may be
     *                   {@code null}
     * @return {@link EditResult#ok()} when the two-character value matches one of the 56
     *         literals, otherwise {@link EditResult#failure(String)} carrying the trimmed
     *         label followed by {@code ': is not a valid state code'}
     */
    public static EditResult validate(String fieldLabel, String stateCode) {

        // app/cbl/COACTUPC.cbl:L2494
        //   MOVE ACUP-NEW-CUST-ADDR-STATE-CD TO US-STATE-CODE-TO-EDIT
        String usStateCodeToEdit = movedToUsStateCodeToEdit(stateCode);

        // app/cbl/COACTUPC.cbl:L2495-L2496  IF VALID-US-STATE-CODE / CONTINUE
        if (UsStateCodes.isValidUsStateCode(usStateCodeToEdit)) {
            return EditResult.ok();
        }

        // app/cbl/COACTUPC.cbl:L2501-L2506
        //   STRING FUNCTION TRIM(WS-EDIT-VARIABLE-NAME)
        //          ': is not a valid state code'
        //     DELIMITED BY SIZE INTO WS-RETURN-MSG
        // app/cbl/COACTUPC.cbl:L2508  GO TO 1270-EDIT-US-STATE-CD-EXIT
        return EditResult.failure(functionTrim(fieldLabel) + NOT_A_VALID_STATE_CODE);
    }

    /**
     * Reproduces the {@code MOVE} at app/cbl/COACTUPC.cbl:L2494 into the {@code PIC X(2)} host
     * field at app/cpy/CSLKPCDY.cpy:L1012, with one difference from the source: a longer value
     * keeps its tail.
     *
     * <p>A one-character value, an empty value, and {@code null} fill the positions that remain
     * with spaces. Letter case survives, and no space leaves either end.</p>
     *
     * <p>ADDITIVE. A longer value keeps every character here. The membership test at
     * app/cpy/CSLKPCDY.cpy:L1012 lists two-character codes alone, so a longer value fails it and
     * takes the message the source writes at app/cbl/COACTUPC.cbl:L2501-L2506.</p>
     *
     * @param stateCode the submitted value, may be {@code null}
     * @return two characters, space-filled on the right where the value ran out, and the whole
     *         value when a character other than a space sits past the second position
     */
    private static String movedToUsStateCodeToEdit(String stateCode) {
        if (stateCode == null) {
            return HOST_FIELD_SPACES;
        }
        if (stateCode.length() >= STATE_CODE_LENGTH) {
            return carriesContentPastHostWidth(stateCode)
                    ? stateCode
                    : stateCode.substring(0, STATE_CODE_LENGTH);
        }
        return stateCode + HOST_FIELD_SPACES.substring(stateCode.length());
    }

    /**
     * Reproduces {@code FUNCTION TRIM} at app/cbl/COACTUPC.cbl:L2502, which drops the
     * leading and trailing spaces of {@code WS-EDIT-VARIABLE-NAME}.
     *
     * <p>Interior spaces stay in place. A {@code null} label and an all-space label both
     * yield an empty string. The label keeps every character it has; the source field is
     * {@code PIC X(25)} at app/cbl/COACTUPC.cbl:L53, and that width binds the caller's
     * assignment.</p>
     *
     * @param fieldLabel the label to trim, may be {@code null}
     * @return the label with its leading and trailing spaces dropped
     */
    private static String functionTrim(String fieldLabel) {
        if (fieldLabel == null) {
            return "";
        }
        int firstKept = 0;
        int afterLastKept = fieldLabel.length();
        while (firstKept < afterLastKept && fieldLabel.charAt(firstKept) == ' ') {
            firstKept++;
        }
        while (afterLastKept > firstKept && fieldLabel.charAt(afterLastKept - 1) == ' ') {
            afterLastKept--;
        }
        return fieldLabel.substring(firstKept, afterLastKept);
    }

    /**
     * Reports whether the state code carries a character other than a space past the host width.
     *
     * <p>ADDITIVE. Trailing spaces past the second position are the padding the source
     * {@code PIC X(02)} field itself holds, and any other character there is content the edit does
     * not read.</p>
     *
     * @param stateCode the submitted value, never null
     * @return true when a character other than a space sits past the second position
     */
    private static boolean carriesContentPastHostWidth(String stateCode) {
        for (int position = STATE_CODE_LENGTH; position < stateCode.length(); position++) {
            if (stateCode.charAt(position) != ' ') {
                return true;
            }
        }
        return false;
    }
}
