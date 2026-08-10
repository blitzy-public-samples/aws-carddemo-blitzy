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
 * <p>This class holds message text and nothing else. The caller holds the edit order
 * and picks the first failing message.</p>
 *
 * <p>Constants appear in source line order. The six constants in the last group of
 * this class never reach a caller.</p>
 *
 * <p>Design decisions: {@code card-platform/docs/decision-log.md}.
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

    // Additive text. The prefix marks the one constant in this class that no CardDemo program
    // carries. It states a transport width and nothing about the calendar.

    /**
     * No COBOL ancestor. Text for an expiry day outside the transport width.
     *
     * <p>No source literal exists. The card update program moves
     * {@code CCUP-NEW-EXPDAY PIC X(2)} at {@code app/cbl/COCRDUPC.cbl:L312} into the reassembled
     * date at {@code app/cbl/COCRDUPC.cbl:L1471} and edits it nowhere. Paragraph
     * {@code 1260-EDIT-EXPIRY-YEAR-EXIT.} closes the edit chain at L945 and
     * {@code 2000-DECIDE-ACTION.} opens at L948, so no paragraph between them reaches the day. A
     * 3270 field two characters wide cannot deliver a third character, and a Representational State
     * Transfer request can. This text answers a value the source screen could not have produced.
     *
     * <p>This constant adds no calendar rule. It states the two-character width of the source field
     * and nothing more, which is why the name carries the additive prefix.
     */
    public static final String ADDITIVE_CARD_EXPIRY_DAY_WIDTH =
            "Card expiry day must be two digits";

    /**
     * ADDITIVE. Text for a year, month and day that name no day of the calendar.
     *
     * <p>No source literal exists, and the source needs none. {@code app/cbl/COCRDUPC.cbl:L1467-L1474}
     * joins the year, the month and the day with hyphens into
     * {@code CARD-EXPIRAION-DATE PIC X(10)} at {@code app/cpy/CVACT02Y.cpy:L9}, which is ten
     * characters of text. Ten characters hold {@code 2026-02-31} as readily as they hold
     * {@code 2026-02-28}, so the source stores an impossible day and reports nothing.
     *
     * <p>Column {@code expiration_date} is a {@code DATE}. Section 0.3.1 of the plan requires that
     * type for this one field, because the field is positionally a calendar date and the source
     * itself decomposes it into a year, a month and a day at
     * {@code app/cbl/COCRDUPC.cbl:L117-L121}. A {@code DATE} column cannot store a day that does
     * not exist, so this platform refuses what the source would have stored, and this text says so.
     *
     * <p>This is the one divergence the card update path carries, and it is a divergence the column
     * type forces rather than a rule this platform added.
     * {@code card-platform/docs/business-rule-flags.md} carries it for a human decision on whether
     * the source behaviour or the column type should win.
     */
    public static final String ADDITIVE_CARD_EXPIRY_NOT_A_CALENDAR_DATE =
            "Card expiry year, month and day must name a day of the calendar";

    /**
     * ADDITIVE. Reported when a paging cursor is not the token the card list issues.
     *
     * <p>The source keeps a full card number in working storage between screen turns. A REST
     * response cannot publish that Primary Account Number, so the target carries the irreversible
     * card token from {@code PanMasker} instead. No source message corresponds because the source
     * has no token-shaped input.
     */
    public static final String ADDITIVE_CARD_CURSOR_MALFORMED =
            "Card cursor must be a 64-character lower-case hexadecimal token";

    /**
     * ADDITIVE. Reported when the token in a card path is not the shape a card token takes.
     *
     * <p>The two routes that name one card carry the card token rather than the card number, so no
     * Primary Account Number reaches an access log, a proxy log, a distributed trace or a browser
     * history. The source carried the number itself, in the search key
     * {@code app/cbl/COCRDSLC.cbl:L740} reads by, and its only edit on that value is
     * {@code Card number if supplied must be a 16 digit number} at
     * {@code app/cbl/COCRDUPC.cbl:L193-L194}. That edit still runs, on the number this service
     * resolves from the token, so the source rule is reproduced where the source applied it.
     *
     * <p>No source message corresponds, because the source has no token-shaped input. The wording
     * matches {@link #ADDITIVE_CARD_CURSOR_MALFORMED}, which refuses the same shape in the paging
     * cursor.
     */
    public static final String ADDITIVE_CARD_TOKEN_MALFORMED =
            "Card token must be a 64-character lower-case hexadecimal token";

    /**
     * ADDITIVE. Reported when a requested row count falls outside the range the card list admits.
     *
     * <p>{@code WS-MAX-SCREEN-LINES PIC S9(4) COMP VALUE 7} at
     * {@code app/cbl/COCRDLIC.cbl:L177-L178} fixes the row count of a 3270 screen, and the source
     * accepts no other. A caller of {@code GET /cards} names its own row count, and
     * {@code domain/CardQueryService} holds the range.
     *
     * <p>No source message corresponds.
     */
    public static final String ADDITIVE_PAGE_SIZE_OUT_OF_RANGE =
            "Page size falls outside the range this list admits";

    /**
     * ADDITIVE. Reported when a requested row count is not a whole number at all.
     *
     * <p>{@link #ADDITIVE_PAGE_SIZE_OUT_OF_RANGE} answers a number the range does not admit. This
     * text answers a value that is no number, which is a different failure and reaches the framework
     * earlier: a query string is text, and the row count is the one value of
     * {@code GET /cards} that is not text, so it is converted before any constraint runs and a
     * conversion that fails never reaches one.
     *
     * <p>No source message corresponds. A 3270 screen delivered its row count as a compile-time
     * constant, {@code WS-MAX-SCREEN-LINES PIC S9(4) COMP VALUE 7} at
     * {@code app/cbl/COCRDLIC.cbl:L177-L178}, so no operator could supply a value of any kind for it
     * and no edit existed to refuse one.
     */
    public static final String ADDITIVE_PAGE_SIZE_NOT_A_NUMBER =
            "Page size must be a whole number";

    /**
     * ADDITIVE. Reported when a well-formed paging cursor names no card this list can browse from.
     *
     * <p>{@link #ADDITIVE_CARD_CURSOR_MALFORMED} answers a value of the wrong shape, which a
     * constraint on the header refuses. This text answers a value of the right shape that resolves to
     * no row, which only a read can discover. A cursor this service never issued, or one naming a
     * card since removed, reaches this text.
     *
     * <p>The read answers this text rather than starting the browse over, so a caller asking to
     * continue from page nine is told its position is gone instead of reading page one and believing it
     * had reached rows it already held.
     *
     * <p>No source message corresponds. The source kept its browse key in working storage between
     * screen turns, at {@code app/cbl/COCRDLIC.cbl:L1010} and its neighbours, so its position could
     * not name a row the file did not hold.
     */
    public static final String ADDITIVE_CARD_CURSOR_UNKNOWN =
            "Card cursor names no card of this list";

    /**
     * ADDITIVE. Reported when one request names both browse directions.
     *
     * <p>The source browses in one direction per screen turn. {@code 9000-READ-FORWARD} at
     * {@code app/cbl/COCRDLIC.cbl:L1123} and {@code 9100-READ-BACKWARDS} at {@code :L1264} are
     * reached from different function keys, and neither runs alongside the other.
     *
     * <p>No source message corresponds.
     */
    public static final String ADDITIVE_ONE_BROWSE_DIRECTION =
            "A request names one browse direction, forward or backward";

    private CardValidationMessages() {
    }
}
