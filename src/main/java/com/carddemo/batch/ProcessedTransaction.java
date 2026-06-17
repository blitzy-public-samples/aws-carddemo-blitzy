package com.carddemo.batch;

import com.carddemo.entity.Transaction;

/**
 * Plain Java transport model that carries the <strong>outcome</strong> of validating and posting a
 * single daily-transaction feed record. It is the <strong>output item</strong> produced by
 * {@code TransactionPostingProcessor} and the <strong>input item</strong> consumed by
 * {@code TransactionPostingWriter} in the daily transaction-posting batch step
 * ({@code TransactionPostingJobConfig}).
 *
 * <h2>Source of truth</h2>
 * <p>This class re-expresses the read-validate-post / read-validate-reject loop of the legacy COBOL
 * batch program {@code app/cbl/CBTRN02C.cbl}. Each {@link DailyTransactionRecord} read from the
 * {@code dailytran.txt} feed is run through an ordered validation gauntlet; the processor decides
 * validity and the writer performs the persistence. The two stages each map onto fields of this
 * object:</p>
 * <ul>
 *   <li>The <strong>processor</strong> decides whether the record is valid (and builds the
 *       {@link Transaction} to persist) or is rejected with one of the validation reject codes.</li>
 *   <li>The <strong>writer</strong> may later flip a previously-valid item to reject code
 *       {@link #REJECT_ACCOUNT_UPDATE_FAILED 109} via {@link #markRejected(int, String)} when the
 *       account update fails — mirroring the COBOL {@code REWRITE ... INVALID KEY} guard of
 *       {@code 2800-UPDATE-ACCOUNT-REC} ({@code CBTRN02C} L554-L559).</li>
 * </ul>
 *
 * <h2>Reject-code superset (CBTRN02C parity)</h2>
 * <p>The prompt's summary lists only 100/102/103, but the COBOL source implements two additional
 * codes that this migration preserves (AAP &sect;0.6.1, &sect;0.7.1, &sect;0.7.3 #3). A
 * {@code rejectCode} of {@link #VALID 0} means the record posted; any other value is a reject:</p>
 * <table border="1">
 *   <caption>Validation reject codes</caption>
 *   <tr><th>Code</th><th>Constant</th><th>Meaning</th><th>COBOL origin</th></tr>
 *   <tr><td>0</td><td>{@link #VALID}</td><td>Valid / posted</td><td>(post path)</td></tr>
 *   <tr><td>100</td><td>{@link #REJECT_INVALID_CARD}</td><td>INVALID CARD NUMBER FOUND</td>
 *       <td>{@code 1500-A-LOOKUP-XREF} L383-L387</td></tr>
 *   <tr><td>101</td><td>{@link #REJECT_ACCOUNT_NOT_FOUND}</td><td>ACCOUNT RECORD NOT FOUND</td>
 *       <td>{@code 1500-B-LOOKUP-ACCT} L396-L399</td></tr>
 *   <tr><td>102</td><td>{@link #REJECT_OVERLIMIT}</td><td>OVERLIMIT TRANSACTION</td>
 *       <td>{@code 1500-B-LOOKUP-ACCT} L407-L413</td></tr>
 *   <tr><td>103</td><td>{@link #REJECT_EXPIRED}</td><td>TRANSACTION RECEIVED AFTER ACCT EXPIRATION</td>
 *       <td>{@code 1500-B-LOOKUP-ACCT} L414-L419</td></tr>
 *   <tr><td>109</td><td>{@link #REJECT_ACCOUNT_UPDATE_FAILED}</td><td>account REWRITE/update failed</td>
 *       <td>{@code 2800-UPDATE-ACCOUNT-REC} L555-L558</td></tr>
 * </table>
 *
 * <h2>Why rejected items are never {@code null}</h2>
 * <p>The processor MUST return a {@code ProcessedTransaction} (with a non-zero {@code rejectCode})
 * for rejected records rather than returning {@code null}. Returning {@code null} from a Spring Batch
 * {@code ItemProcessor} <em>filters</em> the item out of the chunk, which would silently discard the
 * reject record. Because the {@link #getSource() source} feed image is always retained, the writer
 * can emit the fixed-width 430-byte DALYREJS reject line (350-byte feed image + 80-byte trailer,
 * {@code CBTRN02C} L446-L451) for every rejected item, including code 109.</p>
 *
 * <h2>Design notes</h2>
 * <ul>
 *   <li>This is a <strong>plain POJO</strong>: it carries NO Spring or JPA annotations and depends
 *       only on the in-package {@link DailyTransactionRecord} and the {@link Transaction} entity.</li>
 *   <li>{@link #getSource() source} is {@code final} and is guaranteed non-{@code null} (the
 *       constructor rejects {@code null}); it is required to build the reject feed image even for a
 *       writer-time code-109 rejection.</li>
 *   <li>{@link #getRejectDesc() rejectDesc} is the human-readable reason text held <em>untruncated</em>
 *       here; the writer pads/truncates it to the 76-character trailer width
 *       ({@code WS-VALIDATION-FAIL-DESC X(76)}, {@code CBTRN02C} L180-L182).</li>
 * </ul>
 *
 * @see DailyTransactionRecord
 * @see Transaction
 * @see <a href="file:app/cbl/CBTRN02C.cbl">CBTRN02C.cbl — daily transaction posting</a>
 */
public class ProcessedTransaction {

    // -------------------------------------------------------------------------------------------------
    // Reject-code constants (documentation-grade; the mandated public API uses the int values directly).
    // -------------------------------------------------------------------------------------------------

    /** Reject code denoting a <strong>valid</strong> / successfully posted record (no rejection). */
    public static final int VALID = 0;

    /**
     * Reject code 100 — {@code INVALID CARD NUMBER FOUND}. Raised when the card-cross-reference
     * lookup by card number finds no row ({@code CBTRN02C} {@code 1500-A-LOOKUP-XREF} L383-L387).
     */
    public static final int REJECT_INVALID_CARD = 100;

    /**
     * Reject code 101 — {@code ACCOUNT RECORD NOT FOUND}. Raised when the account lookup by the
     * cross-referenced account id finds no row ({@code CBTRN02C} {@code 1500-B-LOOKUP-ACCT}
     * L396-L399). Omitted by the prompt summary; preserved per AAP &sect;0.7.3 #3.
     */
    public static final int REJECT_ACCOUNT_NOT_FOUND = 101;

    /**
     * Reject code 102 — {@code OVERLIMIT TRANSACTION}. Raised when the cycle-based projected balance
     * exceeds the credit limit, i.e. {@code ACCT-CREDIT-LIMIT < (cyc_credit - cyc_debit + amt)}
     * ({@code CBTRN02C} {@code 1500-B-LOOKUP-ACCT} L403-L413).
     */
    public static final int REJECT_OVERLIMIT = 102;

    /**
     * Reject code 103 — {@code TRANSACTION RECEIVED AFTER ACCT EXPIRATION}. Raised when the account
     * expiration date precedes the transaction origination date, i.e.
     * {@code ACCT-EXPIRAION-DATE < DALYTRAN-ORIG-TS(1:10)} ({@code CBTRN02C}
     * {@code 1500-B-LOOKUP-ACCT} L414-L419).
     */
    public static final int REJECT_EXPIRED = 103;

    /**
     * Reject code 109 — account update failed. Raised by the <strong>writer</strong> when the account
     * {@code REWRITE} fails ({@code CBTRN02C} {@code 2800-UPDATE-ACCOUNT-REC} L555-L558). Applied via
     * {@link #markRejected(int, String)} to flip a previously-valid item to a reject.
     */
    public static final int REJECT_ACCOUNT_UPDATE_FAILED = 109;

    // -------------------------------------------------------------------------------------------------
    // Fields
    // -------------------------------------------------------------------------------------------------

    /**
     * The original 350-byte daily-transaction feed record. Always present (never {@code null}); it is
     * required to build the 430-byte DALYREJS reject image even for a writer-time code-109 rejection.
     */
    private final DailyTransactionRecord source;

    /**
     * The validation outcome: {@link #VALID 0} means valid/posted; otherwise one of the reject codes
     * 100 / 101 / 102 / 103 / 109.
     */
    private int rejectCode;

    /**
     * Human-readable reject reason text (the 76-character trailer description, held untruncated here;
     * the writer pads/truncates to 76). {@code null} on the valid path.
     */
    private String rejectDesc;

    /**
     * The fully-built posted-transaction entity, populated by the processor on the valid path;
     * {@code null} when the record is rejected (whether at validation time or by a writer-time flip
     * to code 109).
     */
    private Transaction transaction;

    // -------------------------------------------------------------------------------------------------
    // Constructors & factory methods
    // -------------------------------------------------------------------------------------------------

    /**
     * Constructs a processed-transaction wrapper around the supplied feed record, initialised to the
     * <strong>valid</strong> state ({@code rejectCode == }{@link #VALID}, no description, no built
     * transaction). Callers set the outcome via {@link #valid(DailyTransactionRecord, Transaction)},
     * {@link #rejected(DailyTransactionRecord, int, String)} or {@link #markRejected(int, String)}.
     *
     * @param source the original daily-transaction feed record; must not be {@code null}
     * @throws IllegalArgumentException if {@code source} is {@code null} (the source is required to
     *                                  build the reject feed image for every outcome)
     */
    public ProcessedTransaction(DailyTransactionRecord source) {
        if (source == null) {
            throw new IllegalArgumentException(
                    "source DailyTransactionRecord must not be null: it is required to build the "
                            + "430-byte DALYREJS reject image for every processing outcome");
        }
        this.source = source;
        this.rejectCode = VALID;
        this.rejectDesc = null;
        this.transaction = null;
    }

    /**
     * Factory for a <strong>valid</strong> (posted) outcome: {@code rejectCode == }{@link #VALID},
     * no reject description, and the built {@link Transaction} attached for persistence.
     *
     * @param source the original feed record; must not be {@code null}
     * @param tx     the fully-built transaction entity to persist on the valid path
     * @return a valid {@code ProcessedTransaction} carrying {@code tx}
     * @throws IllegalArgumentException if {@code source} is {@code null}
     */
    public static ProcessedTransaction valid(DailyTransactionRecord source, Transaction tx) {
        ProcessedTransaction processed = new ProcessedTransaction(source);
        processed.rejectCode = VALID;
        processed.rejectDesc = null;
        processed.transaction = tx;
        return processed;
    }

    /**
     * Factory for a <strong>rejected</strong> outcome: the given reject code and description are set
     * and no transaction is attached ({@code transaction == null}).
     *
     * @param source the original feed record; must not be {@code null} (required for the reject image)
     * @param code   the validation reject code (e.g. 100 / 101 / 102 / 103)
     * @param desc   the human-readable reject reason text (untruncated)
     * @return a rejected {@code ProcessedTransaction} with no attached transaction
     * @throws IllegalArgumentException if {@code source} is {@code null}
     */
    public static ProcessedTransaction rejected(DailyTransactionRecord source, int code, String desc) {
        ProcessedTransaction processed = new ProcessedTransaction(source);
        processed.rejectCode = code;
        processed.rejectDesc = desc;
        processed.transaction = null;
        return processed;
    }

    // -------------------------------------------------------------------------------------------------
    // Accessors
    // -------------------------------------------------------------------------------------------------

    /**
     * Returns the original daily-transaction feed record. Never {@code null}.
     *
     * @return the source feed record (used to emit the reject feed image)
     */
    public DailyTransactionRecord getSource() {
        return source;
    }

    /**
     * Returns the validation outcome code.
     *
     * @return {@link #VALID 0} when valid/posted; otherwise 100 / 101 / 102 / 103 / 109
     */
    public int getRejectCode() {
        return rejectCode;
    }

    /**
     * Returns the human-readable reject reason text (untruncated), or {@code null} on the valid path.
     *
     * @return the reject description, or {@code null}
     */
    public String getRejectDesc() {
        return rejectDesc;
    }

    /**
     * Returns the built posted-transaction entity on the valid path, or {@code null} when rejected.
     *
     * @return the transaction to persist, or {@code null}
     */
    public Transaction getTransaction() {
        return transaction;
    }

    // -------------------------------------------------------------------------------------------------
    // State predicates & mutator
    // -------------------------------------------------------------------------------------------------

    /**
     * Indicates whether this item represents a valid (postable) transaction.
     *
     * @return {@code true} iff {@code rejectCode == }{@link #VALID}
     */
    public boolean isValid() {
        return rejectCode == VALID;
    }

    /**
     * Indicates whether this item represents a rejected transaction.
     *
     * @return {@code true} iff {@code rejectCode != }{@link #VALID}
     */
    public boolean isRejected() {
        return rejectCode != VALID;
    }

    /**
     * Converts this item to a <strong>rejected</strong> state. Used by the writer to flip a
     * previously-valid item to reject code {@link #REJECT_ACCOUNT_UPDATE_FAILED 109} when the account
     * {@code REWRITE}/update fails ({@code CBTRN02C} {@code 2800-UPDATE-ACCOUNT-REC} L555-L558). The
     * built {@link #getTransaction() transaction} is cleared so the failed post is neither persisted
     * nor counted as posted.
     *
     * @param code the reject code to apply (typically {@link #REJECT_ACCOUNT_UPDATE_FAILED 109})
     * @param desc the human-readable reject reason text (untruncated)
     */
    public void markRejected(int code, String desc) {
        this.rejectCode = code;
        this.rejectDesc = desc;
        this.transaction = null;
    }

    // -------------------------------------------------------------------------------------------------
    // Diagnostics
    // -------------------------------------------------------------------------------------------------

    /**
     * Returns a concise, PII-safe diagnostic string. Only the non-sensitive {@code DALYTRAN-ID} of
     * the source record and the posted transaction id (when present) are included; the card number
     * and other feed fields are intentionally omitted (AAP &sect;0.6.8 PII suppression).
     *
     * @return a diagnostic representation safe for logging
     */
    @Override
    public String toString() {
        String sourceId = (source == null) ? null : source.getId();
        String tranId = (transaction == null) ? null : transaction.getTranId();
        return "ProcessedTransaction{"
                + "sourceId=" + sourceId
                + ", rejectCode=" + rejectCode
                + ", rejected=" + isRejected()
                + ", rejectDesc=" + rejectDesc
                + ", transactionId=" + tranId
                + '}';
    }
}
