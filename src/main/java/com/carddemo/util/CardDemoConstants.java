package com.carddemo.util;

/**
 * Centralized, dependency-free holder for the parity-critical "magic values" that the legacy
 * AWS CardDemo COBOL programs hard-code inline. Migrating those literals into one authoritative
 * Java class lets the {@code service/} and {@code batch/} layers reference a single source of
 * truth instead of re-deriving or duplicating them, which is essential to preserving 100%
 * functional parity with the mainframe application.
 *
 * <p>This is a <strong>tier-0 foundational</strong> type: it has zero dependencies (no Spring,
 * no project imports) so it can never participate in a cyclic dependency. Consumers import it
 * directly, e.g. {@code import com.carddemo.util.CardDemoConstants;}.</p>
 *
 * <h2>Source-of-truth COBOL programs (REFERENCE only — never modified)</h2>
 * <ul>
 *   <li>{@code app/cbl/CBTRN02C.cbl} — daily transaction posting: the reject-code superset
 *       (100/101/102/103/109) and the 430-byte DALYREJS fixed-width reject-record layout.</li>
 *   <li>{@code app/cbl/CBACT04C.cbl} — interest calculation: the monthly-interest divisor and the
 *       interest-transaction type/category/source codes and description prefix.</li>
 *   <li>{@code app/cbl/COCRDLIC.cbl} — card list: corroborates the fixed 3270 browse page size
 *       of 7 rows ({@code OCCURS 7 TIMES}).</li>
 * </ul>
 *
 * <p>All members are {@code public static final}. The class is {@code final} and cannot be
 * instantiated; it carries no mutable state, no logic, and no side effects.</p>
 *
 * @see <a href="file:app/cbl/CBTRN02C.cbl">CBTRN02C.cbl</a>
 * @see <a href="file:app/cbl/CBACT04C.cbl">CBACT04C.cbl</a>
 * @see <a href="file:app/cbl/COCRDLIC.cbl">COCRDLIC.cbl</a>
 */
public final class CardDemoConstants {

    /**
     * Utility class — not instantiable. Throwing here also blocks reflective instantiation.
     */
    private CardDemoConstants() {
        throw new AssertionError("CardDemoConstants is a non-instantiable constants holder.");
    }

    // =================================================================================
    // (1) TRANSACTION-POSTING REJECT CODES + DESCRIPTIONS — source: app/cbl/CBTRN02C.cbl
    // ---------------------------------------------------------------------------------
    // The daily posting program validates each DALYTRAN record through an ordered gauntlet
    // and stamps WS-VALIDATION-FAIL-REASON (PIC 9(04)) with one of the codes below, plus the
    // matching WS-VALIDATION-FAIL-REASON-DESC (PIC X(76)) literal. The full superset is
    // 100 / 101 / 102 / 103 / 109 — all FIVE are reproduced here. Description strings are
    // copied VERBATIM from the COBOL (exact wording and casing) so the DALYREJS reject file
    // remains byte-for-byte compatible with the legacy dataset.
    // =================================================================================

    /** Reject 100: XREF lookup by card number failed. CBTRN02C L385-387. */
    public static final int REJECT_CODE_INVALID_CARD = 100;
    /** Canonical description for reject {@link #REJECT_CODE_INVALID_CARD} (verbatim COBOL literal). */
    public static final String REJECT_DESC_INVALID_CARD = "INVALID CARD NUMBER FOUND";

    /** Reject 101: ACCOUNT lookup by cross-referenced account id failed. CBTRN02C L397-399. */
    public static final int REJECT_CODE_ACCOUNT_NOT_FOUND = 101;
    /** Canonical description for reject {@link #REJECT_CODE_ACCOUNT_NOT_FOUND} (verbatim COBOL literal). */
    public static final String REJECT_DESC_ACCOUNT_NOT_FOUND = "ACCOUNT RECORD NOT FOUND";

    /**
     * Reject 102: overlimit. Rejected when
     * {@code ACCT-CREDIT-LIMIT < (ACCT-CURR-CYC-CREDIT - ACCT-CURR-CYC-DEBIT + DALYTRAN-AMT)}
     * — the cycle-based formula, NOT a simple {@code currBal + amt > limit}. CBTRN02C L403-413.
     */
    public static final int REJECT_CODE_OVERLIMIT = 102;
    /** Canonical description for reject {@link #REJECT_CODE_OVERLIMIT} (verbatim COBOL literal). */
    public static final String REJECT_DESC_OVERLIMIT = "OVERLIMIT TRANSACTION";

    /**
     * Reject 103: transaction received after account expiration. Rejected when
     * {@code ACCT-EXPIRAION-DATE < DALYTRAN-ORIG-TS(1:10)} — account expiry versus the
     * transaction's origination date (NOT a card-expiry-versus-today check). CBTRN02C L414-419.
     * Note: the description preserves the COBOL spelling {@code EXPIRATION}.
     */
    public static final int REJECT_CODE_TRANSACTION_EXPIRED = 103;
    /** Canonical description for reject {@link #REJECT_CODE_TRANSACTION_EXPIRED} (verbatim COBOL literal). */
    public static final String REJECT_DESC_TRANSACTION_EXPIRED = "TRANSACTION RECEIVED AFTER ACCT EXPIRATION";

    /**
     * Reject 109: the account {@code REWRITE} (post-update) failed. CBTRN02C L555-558.
     *
     * <p><strong>Parity quirk:</strong> the COBOL description literal for 109 is the SAME text as
     * code 101 — {@code 'ACCOUNT RECORD NOT FOUND'} — even though 109 denotes a rewrite/update
     * failure rather than a not-found condition. This is intentionally reproduced verbatim for
     * byte-compatible DALYREJS output and is NOT "corrected" to e.g. "ACCOUNT UPDATE FAILED".</p>
     */
    public static final int REJECT_CODE_ACCOUNT_UPDATE_FAILED = 109;
    /**
     * Canonical description for reject {@link #REJECT_CODE_ACCOUNT_UPDATE_FAILED}.
     * Verbatim COBOL literal — deliberately identical to {@link #REJECT_DESC_ACCOUNT_NOT_FOUND}.
     */
    public static final String REJECT_DESC_ACCOUNT_UPDATE_FAILED = "ACCOUNT RECORD NOT FOUND";

    /**
     * Batch return code set by CBTRN02C when at least one record is rejected
     * ({@code IF WS-REJECT-COUNT > 0 MOVE 4 TO RETURN-CODE}, L229-230). The Spring Batch posting
     * job maps a non-zero reject count to a WARNING / non-zero exit status rather than a hard
     * failure. Exposed for the {@code batch/} layer to reference.
     */
    public static final int BATCH_REJECT_RETURN_CODE = 4;

    // =================================================================================
    // (2) DALYREJS 430-BYTE FIXED-WIDTH REJECT-RECORD LAYOUT — source: app/cbl/CBTRN02C.cbl
    // ---------------------------------------------------------------------------------
    // REJECT-RECORD (L176-178):
    //     05 REJECT-TRAN-DATA   PIC X(350)   -> 350-byte image of the original feed record
    //     05 VALIDATION-TRAILER PIC X(80)    ->  80-byte validation trailer
    // WS-VALIDATION-TRAILER (L181-182):
    //     05 WS-VALIDATION-FAIL-REASON      PIC 9(04)  ->  4-digit reject code, zero-padded ("%04d")
    //     05 WS-VALIDATION-FAIL-REASON-DESC PIC X(76)  -> 76-char description, left-justified, space-padded
    //
    // Arithmetic identities (asserted by the reject ItemWriter and the unit tests):
    //     DALYREJS_FEED_IMAGE_WIDTH + DALYREJS_TRAILER_WIDTH == DALYREJS_RECORD_WIDTH   (350 + 80 == 430)
    //     DALYREJS_FAIL_REASON_WIDTH + DALYREJS_FAIL_DESC_WIDTH == DALYREJS_TRAILER_WIDTH (  4 + 76 ==  80)
    // The batch/ reject writer MUST emit a fixed-width 430-byte line for byte compatibility.
    // =================================================================================

    /** Total DALYREJS record width in bytes (350 feed image + 80 trailer = 430). */
    public static final int DALYREJS_RECORD_WIDTH = 430;
    /** Width of the original transaction feed image carried into the reject record. */
    public static final int DALYREJS_FEED_IMAGE_WIDTH = 350;
    /** Width of the validation trailer appended to the feed image (4 reason + 76 desc = 80). */
    public static final int DALYREJS_TRAILER_WIDTH = 80;
    /** Width of the fail-reason field: PIC 9(04), the 4-digit reject code formatted {@code %04d}. */
    public static final int DALYREJS_FAIL_REASON_WIDTH = 4;
    /** Width of the fail-description field: PIC X(76), left-justified and space-padded on the right. */
    public static final int DALYREJS_FAIL_DESC_WIDTH = 76;

    // =================================================================================
    // (3) PAGINATION PAGE SIZE — source: app/cbl/COCRDLIC.cbl (corroborated by AAP §0.6.4)
    // ---------------------------------------------------------------------------------
    // The legacy 3270 browse screens render a fixed 7 rows per page (COCRDLIC defines its
    // screen-row table as OCCURS 7 TIMES, L76/L86). This is a hard parity value: list
    // operations build PageRequest.of(page, CardDemoConstants.PAGE_SIZE). It MUST remain
    // exactly 7 and MUST NOT be made configurable.
    // =================================================================================

    /**
     * Fixed legacy 3270 browse/screen page size: exactly 7 rows. Used by {@code CardService} and
     * {@code TransactionService} list operations, e.g. {@code PageRequest.of(page, PAGE_SIZE)}.
     */
    public static final int PAGE_SIZE = 7;

    // =================================================================================
    // (4) INTEREST-CALCULATION CONSTANTS — source: app/cbl/CBACT04C.cbl
    // ---------------------------------------------------------------------------------
    // CBACT04C computes monthly interest per transaction-category balance and writes an interest
    // transaction row. Monthly interest = (TRAN-CAT-BAL * DIS-INT-RATE) / 1200 (L464-465): the
    // annual percentage rate is divided by 100 to a fraction and by 12 for the month, hence 1200.
    // The interest transaction is stamped with type '01', category '05', source 'System'
    // (L482-484), and a description formed as STRING 'Int. for a/c ' , ACCT-ID (L485-489).
    //
    // NOTE: CBACT04C's 1400-COMPUTE-FEES paragraph is an explicit "To be implemented" stub
    // (L518-520); there is NO fee logic to port and therefore NO fee constants are defined here.
    // =================================================================================

    /**
     * Monthly-interest divisor: {@code monthlyInterest = catBal * rate / 1200}. The annual
     * percentage rate is divided by 100 (to a fraction) and by 12 (to a month) = 1200.
     * Consumed as
     * {@code catBal.multiply(rate).divide(BigDecimal.valueOf(INTEREST_DIVISOR), MONEY_SCALE, RoundingMode.HALF_UP)}.
     */
    public static final int INTEREST_DIVISOR = 1200;

    /** Interest transaction type code (CBACT04C L482: {@code MOVE '01' TO TRAN-TYPE-CD}). */
    public static final String INTEREST_TRAN_TYPE_CD = "01";

    /**
     * Interest transaction category code (CBACT04C L483: {@code MOVE '05' TO TRAN-CAT-CD}).
     * Stored as the 2-character code {@code "05"}; the underlying numeric category is 5.
     */
    public static final String INTEREST_TRAN_CAT_CD = "05";

    /** Interest transaction source (CBACT04C L484: {@code MOVE 'System' TO TRAN-SOURCE}). */
    public static final String INTEREST_TRAN_SOURCE = "System";

    /**
     * Interest transaction description prefix (CBACT04C L485-489:
     * {@code STRING 'Int. for a/c ' , ACCT-ID ...}). The service appends the account id, so the
     * <strong>trailing space is intentional and preserved</strong> — {@code INTEREST_TRAN_DESC_PREFIX + acctId}
     * reproduces the COBOL output exactly.
     */
    public static final String INTEREST_TRAN_DESC_PREFIX = "Int. for a/c ";

    /**
     * Shared scale for all {@code NUMERIC(12,2)} monetary {@link java.math.BigDecimal} arithmetic,
     * applied with {@link java.math.RoundingMode#HALF_UP} to mirror COBOL fixed-point semantics
     * (AAP §0.7.1). Used by interest and posting math.
     */
    public static final int MONEY_SCALE = 2;

    /**
     * Convenience {@link java.math.BigDecimal} form of {@link #INTEREST_DIVISOR} for callers that
     * prefer not to wrap the {@code int} at each call site. The {@code int} form remains the
     * primary contract.
     */
    public static final java.math.BigDecimal INTEREST_DIVISOR_BD = java.math.BigDecimal.valueOf(1200);
}
