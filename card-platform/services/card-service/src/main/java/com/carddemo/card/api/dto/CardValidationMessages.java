package com.carddemo.card.api.dto;

/**
 * Verbatim validation message texts for the card service.
 *
 * <p>Every string in this class is copied character for character from the
 * working-storage message field of the CardDemo card programs. That field is
 * {@code WS-RETURN-MSG PIC X(75)}, declared at {@code app/cbl/COCRDUPC.cbl:L173},
 * with its blank-state condition name {@code WS-RETURN-MSG-OFF} on line 174.
 * {@code app/cbl/COCRDSLC.cbl} declares its own copy of the same field at line 134,
 * and every text the two programs share matches character for character.
 * {@code app/cbl/COCRDLIC.cbl} carries no copy of the field and supplies no text
 * here.</p>
 *
 * <p>The card update program writes at most one message per pass. Each field edit
 * wraps its write in {@code IF WS-RETURN-MSG-OFF} and writes only while the field
 * is still blank, so the first failing edit keeps the field. Line 384 blanks the
 * field at the start of every pass.</p>
 *
 * <p>{@code app/cbl/COCRDUPC.cbl} holds fifteen of those guards, at lines 730, 743,
 * 773, 787, 816, 833, 855, 868, 888, 903, 921, 939, 1399, 1404 and 1445. Lines 1399,
 * 1404 and 1445 write two spaces after {@code IF}, so a search for the single-space
 * form finds only twelve of them.</p>
 *
 * <p>This class holds message text and nothing else. {@code CardUpdateService} holds
 * the edit order and picks the first failing message.</p>
 *
 * <p>Constants appear in source line order. The six constants in the last group of
 * this class never reach a caller, and
 * {@code card-platform/docs/business-rule-flags.md} carries them as findings.</p>
 */
public final class CardValidationMessages {

    // Live outcome texts. Each one has at least one reachable set site in
    // app/cbl/COCRDUPC.cbl or app/cbl/COCRDSLC.cbl.

    /**
     * Text of condition name {@code WS-PROMPT-FOR-ACCT}.
     *
     * <p>Literal at {@code app/cbl/COCRDUPC.cbl:L178}, set at line 731 under the
     * guard on line 730. {@code app/cbl/COCRDSLC.cbl} repeats the literal at line 139
     * and sets it at line 657.</p>
     */
    public static final String PROMPT_FOR_ACCT = "Account number not provided";

    /**
     * Text of condition name {@code WS-PROMPT-FOR-CARD}.
     *
     * <p>Literal at {@code app/cbl/COCRDUPC.cbl:L180}, set at line 774 under the
     * guard on line 773. {@code app/cbl/COCRDSLC.cbl} repeats the literal at line 141
     * and sets it at line 697.</p>
     */
    public static final String PROMPT_FOR_CARD = "Card number not provided";

    /**
     * Text of condition name {@code WS-PROMPT-FOR-NAME}.
     *
     * <p>Literal at {@code app/cbl/COCRDUPC.cbl:L182}, set at line 817 under the
     * guard on line 816.</p>
     */
    public static final String PROMPT_FOR_NAME = "Card name not provided";

    /**
     * Text of condition name {@code WS-NAME-MUST-BE-ALPHA}.
     *
     * <p>Literal at {@code app/cbl/COCRDUPC.cbl:L184}, set at line 834 under the
     * guard on line 833. The edit that reaches it accepts letters and spaces only.</p>
     */
    public static final String NAME_MUST_BE_ALPHA =
            "Card name can only contain alphabets and spaces";

    /**
     * Text of condition name {@code NO-SEARCH-CRITERIA-RECEIVED}.
     *
     * <p>Literal at {@code app/cbl/COCRDUPC.cbl:L186}, set at line 658 with no guard,
     * ahead of the guarded edit chain. {@code app/cbl/COCRDSLC.cbl} repeats the
     * literal at line 143 and sets it at line 639.</p>
     */
    public static final String NO_SEARCH_CRITERIA_RECEIVED = "No input received";

    /**
     * Text of condition name {@code NO-CHANGES-DETECTED}.
     *
     * <p>Literal at {@code app/cbl/COCRDUPC.cbl:L188}, set at line 682 with no guard.
     * Lines 680 and 681 compare the new and the old card group whole, after folding
     * both to upper case. A caller who changes only letter case sees no change and no
     * update. The trailing full stop belongs to the source literal.</p>
     */
    public static final String NO_CHANGES_DETECTED =
            "No change detected with respect to values fetched.";

    /**
     * Text of condition name {@code CARD-STATUS-MUST-BE-YES-NO}.
     *
     * <p>Literal at {@code app/cbl/COCRDUPC.cbl:L196}, set at line 856 under the guard
     * on line 855 and again at line 869 under the guard on line 868.</p>
     */
    public static final String CARD_STATUS_MUST_BE_YES_NO =
            "Card Active Status must be Y or N";

    /**
     * Text of condition name {@code CARD-EXPIRY-MONTH-NOT-VALID}.
     *
     * <p>Literal at {@code app/cbl/COCRDUPC.cbl:L198}, set at line 889 under the guard
     * on line 888 and again at line 904 under the guard on line 903.</p>
     */
    public static final String CARD_EXPIRY_MONTH_NOT_VALID =
            "Card expiry month must be between 1 and 12";

    /**
     * Text of condition name {@code CARD-EXPIRY-YEAR-NOT-VALID}.
     *
     * <p>Literal at {@code app/cbl/COCRDUPC.cbl:L200}, set at line 922 under the guard
     * on line 921 and again at line 940 under the guard on line 939.</p>
     */
    public static final String CARD_EXPIRY_YEAR_NOT_VALID = "Invalid card expiry year";

    /**
     * Text of condition name {@code DID-NOT-FIND-ACCTCARD-COMBO}.
     *
     * <p>Literal at {@code app/cbl/COCRDUPC.cbl:L204}, set at line 1400 under the
     * guard on line 1399. {@code app/cbl/COCRDSLC.cbl} repeats the literal at line 154
     * and sets it at line 760.</p>
     */
    public static final String DID_NOT_FIND_ACCTCARD_COMBO =
            "Did not find cards for this search condition";

    /**
     * Text of condition name {@code COULD-NOT-LOCK-FOR-UPDATE}.
     *
     * <p>Literal at {@code app/cbl/COCRDUPC.cbl:L206}, set at line 1446 under the
     * guard on line 1445.</p>
     */
    public static final String COULD_NOT_LOCK_FOR_UPDATE =
            "Could not lock record for update";

    /**
     * Text of condition name {@code DATA-WAS-CHANGED-BEFORE-UPDATE}.
     *
     * <p>Literal at {@code app/cbl/COCRDUPC.cbl:L208}, set at line 1511 in a bare
     * {@code ELSE} with no guard, inside the write-processing paragraph.</p>
     */
    public static final String DATA_WAS_CHANGED_BEFORE_UPDATE =
            "Record changed by some one else. Please review";

    /**
     * Text of condition name {@code LOCKED-BUT-UPDATE-FAILED}.
     *
     * <p>Literal at {@code app/cbl/COCRDUPC.cbl:L210}, set at line 1491 in a bare
     * {@code ELSE} with no guard, inside the write-processing paragraph.</p>
     */
    public static final String LOCKED_BUT_UPDATE_FAILED = "Update of record failed";

    // Inline literals. The two numeric edits move an upper-case literal into the
    // message field and use no condition name. Both carry no space after the comma.

    /**
     * Text of the account filter edit, written with no condition name.
     *
     * <p>Literal at {@code app/cbl/COCRDUPC.cbl:L745}, moved into the message field at
     * line 746 under the guard on line 743. The test on line 740 reaches it when the
     * supplied account filter holds a character outside zero through nine.</p>
     */
    public static final String ACCOUNT_FILTER_NOT_NUMERIC =
            "ACCOUNT FILTER,IF SUPPLIED MUST BE A 11 DIGIT NUMBER";

    /**
     * Text of the card filter edit, written with no condition name.
     *
     * <p>Literal at {@code app/cbl/COCRDUPC.cbl:L789}, moved into the message field at
     * line 790 under the guard on line 787, reached by the test on line 784.
     * {@code app/cbl/COCRDSLC.cbl} repeats the same literal at line 711, under the
     * guard on line 709 and the test on line 706.</p>
     */
    public static final String CARD_FILTER_NOT_NUMERIC =
            "CARD ID FILTER,IF SUPPLIED MUST BE A 16 DIGIT NUMBER";

    // Dead declarations. No card program writes any of the six texts below to the
    // message field, so no endpoint emits them. They complete the text inventory.

    /**
     * Text of condition name {@code SEARCHED-ACCT-ZEROES}. Never emitted.
     *
     * <p>Literal at {@code app/cbl/COCRDUPC.cbl:L190} and at
     * {@code app/cbl/COCRDSLC.cbl:L145}. No {@code SET} site exists in
     * {@code app/cbl/COCRDUPC.cbl}, {@code app/cbl/COCRDSLC.cbl} or
     * {@code app/cbl/COCRDLIC.cbl}. The account filter edit writes
     * {@link #ACCOUNT_FILTER_NOT_NUMERIC} at line 745.</p>
     */
    public static final String NEVER_EMITTED_SEARCHED_ACCT_ZEROES =
            "Account number must be a non zero 11 digit number";

    /**
     * Text of condition name {@code SEARCHED-ACCT-NOT-NUMERIC}. Never emitted.
     *
     * <p>Literal at {@code app/cbl/COCRDUPC.cbl:L192} and at
     * {@code app/cbl/COCRDSLC.cbl:L147}, holding the same characters as line 190. No
     * {@code SET} site exists in {@code app/cbl/COCRDUPC.cbl},
     * {@code app/cbl/COCRDSLC.cbl} or {@code app/cbl/COCRDLIC.cbl}.</p>
     */
    public static final String NEVER_EMITTED_SEARCHED_ACCT_NOT_NUMERIC =
            "Account number must be a non zero 11 digit number";

    /**
     * Text of condition name {@code SEARCHED-CARD-NOT-NUMERIC}. Never emitted.
     *
     * <p>Literal at {@code app/cbl/COCRDUPC.cbl:L194} and at
     * {@code app/cbl/COCRDSLC.cbl:L149}. No {@code SET} site exists in
     * {@code app/cbl/COCRDUPC.cbl}, {@code app/cbl/COCRDSLC.cbl} or
     * {@code app/cbl/COCRDLIC.cbl}. The card filter edit writes
     * {@link #CARD_FILTER_NOT_NUMERIC} at line 789.</p>
     */
    public static final String NEVER_EMITTED_SEARCHED_CARD_NOT_NUMERIC =
            "Card number if supplied must be a 16 digit number";

    /**
     * Text of condition name {@code DID-NOT-FIND-ACCT-IN-CARDXREF}. Never emitted.
     *
     * <p>Literal at {@code app/cbl/COCRDUPC.cbl:L202} and at
     * {@code app/cbl/COCRDSLC.cbl:L152}. One {@code SET} site exists,
     * {@code app/cbl/COCRDSLC.cbl:L799}, inside paragraph
     * {@code 9150-GETCARD-BYACCT}. That paragraph spans lines 779 to 810 and no
     * {@code PERFORM} names it, so line 799 never runs.</p>
     */
    public static final String NEVER_EMITTED_DID_NOT_FIND_ACCT_IN_CARDXREF =
            "Did not find this account in cards database";

    /**
     * Text of condition name {@code XREF-READ-ERROR}. Never emitted.
     *
     * <p>Literal at {@code app/cbl/COCRDUPC.cbl:L212} and at
     * {@code app/cbl/COCRDSLC.cbl:L156}. No {@code SET} site exists in
     * {@code app/cbl/COCRDUPC.cbl}, {@code app/cbl/COCRDSLC.cbl} or
     * {@code app/cbl/COCRDLIC.cbl}.</p>
     */
    public static final String NEVER_EMITTED_XREF_READ_ERROR =
            "Error reading Card Data File";

    /**
     * Text of condition name {@code CODING-TO-BE-DONE}. Never emitted.
     *
     * <p>Literal at {@code app/cbl/COCRDUPC.cbl:L214} and at
     * {@code app/cbl/COCRDSLC.cbl:L158}. No {@code SET} site exists in
     * {@code app/cbl/COCRDUPC.cbl}, {@code app/cbl/COCRDSLC.cbl} or
     * {@code app/cbl/COCRDLIC.cbl}. The four full stops belong to the source
     * literal.</p>
     */
    public static final String NEVER_EMITTED_CODING_TO_BE_DONE = "Looks Good.... so far";

    /** Holds constants only, so no instance is created. */
    private CardValidationMessages() {
    }
}
