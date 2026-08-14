package com.carddemo.account.domain.validation;

/**
 * Checks that a credit score falls inside the inclusive range 300 to 850. Both bounds pass: 300 and
 * 850 pass, 299 and 851 fail.
 *
 * <p>Realises paragraph {@code 1275-EDIT-FICO-SCORE} at {@code app/cbl/COACTUPC.cbl:L2514}, whose
 * exit paragraph sits at {@code app/cbl/COACTUPC.cbl:L2531}. {@code FICO} names a credit score. The
 * two bounds are the values of {@code 88 FICO-RANGE-IS-VALID VALUES 300 THROUGH 850} at
 * {@code app/cbl/COACTUPC.cbl:L848-L849}.
 *
 * <p>That condition sits on {@code ACUP-NEW-CUST-FICO-SCORE}, the {@code PIC 9(03)} redefine
 * declared at {@code app/cbl/COACTUPC.cbl:L846-L847} over the {@code PIC X(03)} field at
 * {@code app/cbl/COACTUPC.cbl:L845}. Three characters hold the value, and a leading zero reads as
 * written, so {@code "030"} reads as 30 and fails the range.
 *
 * <p>The caller owns the numeric class test and the message slot.
 * {@code app/cbl/COACTUPC.cbl:L1545} moves the label {@code 'FICO Score'},
 * {@code app/cbl/COACTUPC.cbl:L1549-L1550} runs {@code 1245-EDIT-NUM-REQD}, and
 * {@code app/cbl/COACTUPC.cbl:L1553} gates this paragraph on that verdict. The guard at
 * {@code app/cbl/COACTUPC.cbl:L2520} belongs to the caller too, which keeps the first message a
 * validation pass produces.
 */
public final class CreditScoreRangeValidator {

    /** Lowest passing value, from {@code VALUES 300} at {@code app/cbl/COACTUPC.cbl:L848}. */
    private static final int LOWEST_PASSING_SCORE = 300;

    /** Highest passing value, from {@code THROUGH 850} at {@code app/cbl/COACTUPC.cbl:L849}. */
    private static final int HIGHEST_PASSING_SCORE = 850;

    /**
     * Width of {@code ACUP-NEW-CUST-FICO-SCORE-X PIC X(03)} at {@code app/cbl/COACTUPC.cbl:L845}.
     */
    private static final int SCORE_WIDTH = 3;

    /** Place value of the first of the three characters. */
    private static final int HUNDREDS_PLACE_VALUE = 100;

    /** Place value of the second of the three characters. */
    private static final int TENS_PLACE_VALUE = 10;

    /**
     * The message text at {@code app/cbl/COACTUPC.cbl:L2523}, carried character for character.
     * Thirty-one characters, opening with a colon and a space, closing with no full stop.
     */
    private static final String RANGE_MESSAGE = ": should be between 300 and 850";

    /**
     * The label text for a caller whose label field holds only spaces.
     * {@code FUNCTION TRIM} reduces such a field to zero characters.
     */
    private static final String EMPTY_LABEL = "";

    private CreditScoreRangeValidator() {
    }

    /**
     * Applies the range test of {@code app/cbl/COACTUPC.cbl:L2515} to one submitted score.
     *
     * <p>A score inside the inclusive range 300 to 850 passes and carries no message. Every
     * other score yields the one message the paragraph builds at
     * {@code app/cbl/COACTUPC.cbl:L2521-L2526}: the trimmed label followed by
     * {@code ": should be between 300 and 850"}. {@code FUNCTION TRIM} at
     * {@code app/cbl/COACTUPC.cbl:L2522} trims the padding from the {@code PIC X(25)} label
     * field at {@code app/cbl/COACTUPC.cbl:L53}, and this method trims the label the same way.
     * The label keeps its full length, and no width limit applies here.
     *
     * <p>A {@code null} score, an empty score, a score of another width, and a score holding a
     * character that is not a digit all yield that same message. No argument value raises an
     * exception. This method modifies no argument, so two calls with equal arguments yield
     * equal verdicts.
     *
     * @param fieldLabel  the label the caller supplies, {@code 'FICO Score'} at
     *                    {@code app/cbl/COACTUPC.cbl:L1545}; a {@code null} label reads as the
     *                    all-space field it models and contributes no text
     * @param creditScore the three characters held by {@code ACUP-NEW-CUST-FICO-SCORE-X}; may
     *                    be {@code null}
     * @return a passing verdict for a score inside the range, otherwise a failing verdict
     *         carrying the range message
     */
    public static EditResult validate(String fieldLabel, String creditScore) {
        if (isWithinRange(creditScore)) {
            return EditResult.ok();
        }
        return EditResult.failure(rangeMessage(fieldLabel));
    }

    /**
     * Evaluates {@code 88 FICO-RANGE-IS-VALID} at {@code app/cbl/COACTUPC.cbl:L848-L849}
     * against the characters of the score.
     *
     * <p>This method reads the three characters in place, as the {@code PIC 9(03)} redefine at
     * {@code app/cbl/COACTUPC.cbl:L846-L847} reads them. It tests each character for the digit
     * class, then assembles the value from the three place values.
     *
     * @param creditScore the characters to read; may be {@code null}
     * @return true when the width is {@value #SCORE_WIDTH}, every character is a digit, and the
     *         assembled value lies inside the inclusive range
     *         {@value #LOWEST_PASSING_SCORE} to {@value #HIGHEST_PASSING_SCORE}
     */
    private static boolean isWithinRange(String creditScore) {
        if (creditScore == null || creditScore.length() != SCORE_WIDTH) {
            return false;
        }
        char hundredsCharacter = creditScore.charAt(0);
        char tensCharacter = creditScore.charAt(1);
        char unitsCharacter = creditScore.charAt(2);
        if (!Character.isDigit(hundredsCharacter)
                || !Character.isDigit(tensCharacter)
                || !Character.isDigit(unitsCharacter)) {
            return false;
        }
        int assembledScore = (hundredsCharacter - '0') * HUNDREDS_PLACE_VALUE
                + (tensCharacter - '0') * TENS_PLACE_VALUE
                + (unitsCharacter - '0');
        return assembledScore >= LOWEST_PASSING_SCORE && assembledScore <= HIGHEST_PASSING_SCORE;
    }

    /**
     * Builds the one message of {@code app/cbl/COACTUPC.cbl:L2521-L2526}.
     *
     * @param fieldLabel the label to trim; may be {@code null}
     */
    private static String rangeMessage(String fieldLabel) {
        String trimmedLabel = fieldLabel == null ? EMPTY_LABEL : fieldLabel.trim();
        return trimmedLabel + RANGE_MESSAGE;
    }
}
