package com.carddemo.batch;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.batch.item.ItemProcessor;

import com.carddemo.entity.Account;
import com.carddemo.entity.CardXref;
import com.carddemo.entity.Transaction;
import com.carddemo.repository.AccountRepository;
import com.carddemo.repository.CardXrefRepository;
import com.carddemo.util.CardDemoConstants;

/**
 * Spring Batch {@link ItemProcessor} that reproduces the per-record <strong>validation
 * gauntlet</strong> of the legacy COBOL daily transaction-posting program
 * {@code app/cbl/CBTRN02C.cbl}. It is the <em>processor</em> stage of the transaction-posting
 * step: for every {@link DailyTransactionRecord} read from the {@code dailytran.txt} feed it
 * decides whether the record is valid (building the {@link Transaction} entity to be persisted) or
 * is rejected with one of the validation reject codes, emitting a {@link ProcessedTransaction} in
 * either case.
 *
 * <h2>Source of truth (CBTRN02C parity)</h2>
 * <p>This class re-expresses the validation paragraphs of {@code CBTRN02C}, in the exact order the
 * COBOL evaluates them:</p>
 * <ol>
 *   <li><strong>{@code 1500-A-LOOKUP-XREF}</strong> ({@code CBTRN02C} L380-L392) &mdash; a keyed
 *       {@code READ XREF-FILE} by card number. {@code INVALID KEY} &rarr; reject
 *       {@code 100 'INVALID CARD NUMBER FOUND'}.</li>
 *   <li><strong>{@code 1500-B-LOOKUP-ACCT}</strong> ({@code CBTRN02C} L393-L420) &mdash; a keyed
 *       {@code READ ACCOUNT-FILE} by the cross-referenced {@code XREF-ACCT-ID}.
 *       {@code INVALID KEY} &rarr; reject {@code 101 'ACCOUNT RECORD NOT FOUND'}. On a successful
 *       read it performs two <em>sequential</em> checks:
 *     <ul>
 *       <li><em>Overlimit (102)</em> &mdash;
 *           {@code COMPUTE WS-TEMP-BAL = ACCT-CURR-CYC-CREDIT - ACCT-CURR-CYC-DEBIT + DALYTRAN-AMT};
 *           {@code IF ACCT-CREDIT-LIMIT >= WS-TEMP-BAL CONTINUE ELSE} reject
 *           {@code 102 'OVERLIMIT TRANSACTION'} (L403-L413).</li>
 *       <li><em>Expiration (103)</em> &mdash;
 *           {@code IF ACCT-EXPIRAION-DATE >= DALYTRAN-ORIG-TS (1:10) CONTINUE ELSE} reject
 *           {@code 103 'TRANSACTION RECEIVED AFTER ACCT EXPIRATION'} (L414-L419).</li>
 *     </ul>
 *   </li>
 *   <li><strong>{@code 2000-POST-TRANSACTION}</strong> ({@code CBTRN02C} L424-L444) &mdash; on the
 *       valid path the posted {@link Transaction} is built field-by-field (see
 *       {@link #buildTransaction(DailyTransactionRecord, Long)}).</li>
 * </ol>
 *
 * <h2>Reject-code superset and ordering rules</h2>
 * <ul>
 *   <li>This processor produces the reject-code superset <strong>100 / 101 / 102 / 103</strong>.
 *       The prompt summary lists only 100/102/103, but {@code CBTRN02C} implements 101 as well; per
 *       AAP &sect;0.6.1 / &sect;0.7.3 #3 <strong>the actual COBOL governs</strong>, so 101 is
 *       implemented here.</li>
 *   <li>Reject code <strong>109</strong> (account {@code REWRITE}/update failure,
 *       {@code 2800-UPDATE-ACCOUNT-REC} L555-L558) is deliberately <em>not</em> produced here; it is
 *       applied later by {@code TransactionPostingWriter} via
 *       {@link ProcessedTransaction#markRejected(int, String)} when the account update fails.</li>
 *   <li>The overlimit (102) and expiration (103) checks are written as <strong>two sequential
 *       {@code if} statements</strong>, exactly like the COBOL &mdash; they are <em>not</em> mutually
 *       exclusive. When <em>both</em> fail, the final reject reason ends as <strong>103</strong>
 *       (it overwrites 102), precisely mirroring the legacy {@code MOVE} order.</li>
 * </ul>
 *
 * <h2>Why a rejected item is never {@code null}</h2>
 * <p>Returning {@code null} from a Spring Batch {@code ItemProcessor} <em>filters</em> the item out
 * of the chunk. That would silently discard the reject record and break the byte-compatible
 * 430-byte {@code DALYREJS} output the writer must emit. Therefore {@link #process(DailyTransactionRecord)}
 * <strong>never returns {@code null}</strong>: rejects are returned as
 * {@link ProcessedTransaction#rejected(DailyTransactionRecord, int, String)} and the valid path as
 * {@link ProcessedTransaction#valid(DailyTransactionRecord, Transaction)}.</p>
 *
 * <h2>Design &amp; layering notes</h2>
 * <ul>
 *   <li><strong>Plain bean.</strong> The class carries no stereotype annotation; it is wired
 *       explicitly as a {@code @Bean} in {@code TransactionPostingJobConfig} (AAP &sect;0.3.1),
 *       avoiding any duplicate-bean ambiguity.</li>
 *   <li><strong>Stateless &amp; thread-safe.</strong> It holds only the two injected, immutable
 *       repository references and performs no mutable instance state, so a single shared instance is
 *       safe across chunk-oriented (and potentially partitioned/multi-threaded) execution.</li>
 *   <li><strong>Non-mutating reads.</strong> The processor only validates and constructs; it never
 *       calls {@code save}. All persistence (transaction-category balance, account balance, posted
 *       transaction, and the code-109 flip) happens in {@code TransactionPostingWriter}, mirroring
 *       the COBOL separation between {@code 1500-VALIDATE-TRAN} and the {@code 2xxx} write
 *       paragraphs.</li>
 *   <li><strong>Verbatim transaction id.</strong> The posted {@code tranId} is the incoming
 *       {@code DALYTRAN-ID} carried through unchanged ({@code CBTRN02C} L425); the online
 *       {@code TranIdGenerator} is <em>never</em> used on the batch path (AAP &sect;0.6.5).</li>
 *   <li><strong>Exact arithmetic / ordering.</strong> Money comparisons use
 *       {@link BigDecimal#compareTo(BigDecimal)} and date comparisons use
 *       {@link LocalDate#isBefore(java.time.chrono.ChronoLocalDate)} &mdash; never {@code ==} or
 *       {@code equals} &mdash; so magnitude/order semantics match COBOL fixed-point comparisons.</li>
 * </ul>
 *
 * @see DailyTransactionRecord
 * @see ProcessedTransaction
 * @see CardDemoConstants
 * @see <a href="file:app/cbl/CBTRN02C.cbl">app/cbl/CBTRN02C.cbl (daily transaction posting)</a>
 * @see <a href="file:app/cpy/CVTRA05Y.cpy">app/cpy/CVTRA05Y.cpy (TRAN-RECORD layout)</a>
 * @see <a href="file:app/cpy/CVTRA06Y.cpy">app/cpy/CVTRA06Y.cpy (DALYTRAN-RECORD layout)</a>
 */
public class TransactionPostingProcessor
        implements ItemProcessor<DailyTransactionRecord, ProcessedTransaction> {

    /** PII-safe diagnostic logger. Only non-sensitive identifiers / reject codes are ever logged. */
    private static final Logger log = LoggerFactory.getLogger(TransactionPostingProcessor.class);

    /**
     * Card&rarr;customer&rarr;account cross-reference access. Replaces the keyed {@code READ XREF-FILE}
     * of {@code 1500-A-LOOKUP-XREF}; an empty {@link Optional} corresponds to the legacy
     * {@code INVALID KEY} branch (reject 100).
     */
    private final CardXrefRepository cardXrefRepository;

    /**
     * Account master access. Replaces the keyed {@code READ ACCOUNT-FILE} of
     * {@code 1500-B-LOOKUP-ACCT}; an empty {@link Optional} corresponds to the legacy
     * {@code INVALID KEY} branch (reject 101). Reads here are non-mutating &mdash; the account
     * balance update and {@code REWRITE} happen in the writer.
     */
    private final AccountRepository accountRepository;

    /**
     * Constructs the processor with its collaborating repositories (constructor injection),
     * replacing the static {@code CALL}/file-control linkage of the COBOL program.
     *
     * @param cardXrefRepository the cross-reference repository (card-number keyed lookup); must not
     *                           be {@code null}
     * @param accountRepository  the account-master repository (account-id keyed lookup); must not be
     *                           {@code null}
     */
    public TransactionPostingProcessor(CardXrefRepository cardXrefRepository,
                                       AccountRepository accountRepository) {
        this.cardXrefRepository = cardXrefRepository;
        this.accountRepository = accountRepository;
    }

    /**
     * Runs one daily-transaction feed record through the {@code CBTRN02C} validation gauntlet and
     * returns its processing outcome.
     *
     * <p>The control flow mirrors {@code CBTRN02C} exactly (see the class Javadoc). The method
     * <strong>never returns {@code null}</strong>:</p>
     * <ul>
     *   <li>missing cross-reference &rarr; reject {@code 100};</li>
     *   <li>missing account &rarr; reject {@code 101};</li>
     *   <li>cycle-based projected balance over the credit limit &rarr; reject {@code 102};</li>
     *   <li>account expiration date earlier than the transaction's origination date &rarr; reject
     *       {@code 103} (overwriting 102 when both fail);</li>
     *   <li>otherwise &rarr; a {@linkplain ProcessedTransaction#valid(DailyTransactionRecord, Transaction)
     *       valid} outcome carrying the built {@link Transaction}.</li>
     * </ul>
     *
     * @param rec the daily-transaction feed record to validate and (if valid) post; must not be
     *            {@code null} (Spring Batch supplies a non-null item from the reader)
     * @return the {@link ProcessedTransaction} outcome &mdash; rejected (code 100/101/102/103) or
     *         valid; never {@code null}
     */
    @Override
    public ProcessedTransaction process(DailyTransactionRecord rec) {
        // ----------------------------------------------------------------------------------------
        // Step 1 — card validation (reject 100). 1500-A-LOOKUP-XREF: READ XREF-FILE by card number.
        // INVALID KEY => 'INVALID CARD NUMBER FOUND'.
        // ----------------------------------------------------------------------------------------
        Optional<CardXref> xrefOpt = cardXrefRepository.findByXrefCardNum(rec.getCardNum());
        if (xrefOpt.isEmpty()) {
            log.debug("Reject {} (invalid card) for daily-tran id={}",
                    CardDemoConstants.REJECT_CODE_INVALID_CARD, rec.getId());
            return ProcessedTransaction.rejected(rec,
                    CardDemoConstants.REJECT_CODE_INVALID_CARD,
                    CardDemoConstants.REJECT_DESC_INVALID_CARD);
        }
        CardXref xref = xrefOpt.get();
        Long acctId = xref.getXrefAcctId();

        // ----------------------------------------------------------------------------------------
        // Step 2 — account validation (reject 101). 1500-B-LOOKUP-ACCT: READ ACCOUNT-FILE by the
        // cross-referenced acct id. INVALID KEY => 'ACCOUNT RECORD NOT FOUND'.
        // ----------------------------------------------------------------------------------------
        Optional<Account> acctOpt = accountRepository.findById(acctId);
        if (acctOpt.isEmpty()) {
            log.debug("Reject {} (account not found) for daily-tran id={}",
                    CardDemoConstants.REJECT_CODE_ACCOUNT_NOT_FOUND, rec.getId());
            return ProcessedTransaction.rejected(rec,
                    CardDemoConstants.REJECT_CODE_ACCOUNT_NOT_FOUND,
                    CardDemoConstants.REJECT_DESC_ACCOUNT_NOT_FOUND);
        }
        Account acct = acctOpt.get();

        // The reject reason/description accumulate across the two sequential checks below, exactly as
        // the COBOL MOVEs into WS-VALIDATION-FAIL-REASON / -DESC. A trailing 103 overwrites 102.
        int reason = ProcessedTransaction.VALID;
        String desc = null;

        // ----------------------------------------------------------------------------------------
        // Step 3 — overlimit (reject 102). CYCLE-BASED formula (NOT currBal + amt > limit):
        //   COBOL: WS-TEMP-BAL = ACCT-CURR-CYC-CREDIT - ACCT-CURR-CYC-DEBIT + DALYTRAN-AMT
        //          IF ACCT-CREDIT-LIMIT >= WS-TEMP-BAL CONTINUE ELSE reject 102
        // Null cycle/limit/amount values are coalesced to ZERO (nz): COBOL COMP-3 numerics are never
        // null, so this is behavior-preserving for all real data while preventing a single malformed
        // row from aborting the entire chunk with a NullPointerException.
        // ----------------------------------------------------------------------------------------
        BigDecimal tempBal = nz(acct.getCurrCycCredit())
                .subtract(nz(acct.getCurrCycDebit()))
                .add(nz(rec.getAmt()));
        if (nz(acct.getCreditLimit()).compareTo(tempBal) < 0) {
            reason = CardDemoConstants.REJECT_CODE_OVERLIMIT;
            desc = CardDemoConstants.REJECT_DESC_OVERLIMIT;
        }

        // ----------------------------------------------------------------------------------------
        // Step 4 — expiration (reject 103). SEQUENTIAL if (NOT else-if): 103 OVERWRITES 102 when both
        // fail, mirroring the COBOL MOVE order.
        //   COBOL: IF ACCT-EXPIRAION-DATE >= DALYTRAN-ORIG-TS (1:10) CONTINUE ELSE reject 103
        // i.e. reject 103 exactly when the account expiry date is strictly BEFORE the transaction's
        // origination date. Equal dates continue (>= is satisfied), so isBefore (strict <) is used.
        // ----------------------------------------------------------------------------------------
        LocalDate origDate = rec.getOrigDate();
        LocalDate acctExp = acct.getExpirationDate();
        if (acctExp != null && origDate != null && acctExp.isBefore(origDate)) {
            reason = CardDemoConstants.REJECT_CODE_TRANSACTION_EXPIRED;
            desc = CardDemoConstants.REJECT_DESC_TRANSACTION_EXPIRED;
        }

        if (reason != ProcessedTransaction.VALID) {
            log.debug("Reject {} ({}) for daily-tran id={}", reason, desc, rec.getId());
            return ProcessedTransaction.rejected(rec, reason, desc);
        }

        // ----------------------------------------------------------------------------------------
        // VALID PATH — build the Transaction to post (2000-POST-TRANSACTION). Persistence (tcat-bal,
        // account balance + REWRITE, transaction write, and any code-109 flip) is the writer's job.
        // ----------------------------------------------------------------------------------------
        Transaction tx = buildTransaction(rec, acctId);
        log.trace("Valid daily-tran id={} posted as transaction id={}", rec.getId(), tx.getTranId());
        return ProcessedTransaction.valid(rec, tx);
    }

    /**
     * Builds the posted {@link Transaction} entity from a validated feed record, mirroring the
     * field-by-field {@code MOVE} sequence of {@code CBTRN02C}'s {@code 2000-POST-TRANSACTION}
     * ({@code app/cbl/CBTRN02C.cbl} L424-L444) against copybook {@code CVTRA05Y} ({@code TRAN-RECORD}).
     *
     * <p>Field correspondence:</p>
     * <ul>
     *   <li>{@code DALYTRAN-ID}            &rarr; {@code tranId}  &mdash; carried <strong>verbatim</strong>
     *       (no {@code TranIdGenerator} on the batch path).</li>
     *   <li>{@code DALYTRAN-TYPE-CD}       &rarr; {@code typeCd}</li>
     *   <li>{@code DALYTRAN-CAT-CD}        &rarr; {@code catCd}</li>
     *   <li>{@code DALYTRAN-SOURCE}        &rarr; {@code source}</li>
     *   <li>{@code DALYTRAN-DESC}          &rarr; {@code description}</li>
     *   <li>{@code DALYTRAN-AMT}           &rarr; {@code amt}</li>
     *   <li>{@code DALYTRAN-MERCHANT-ID}   &rarr; {@code merchantId}</li>
     *   <li>{@code DALYTRAN-MERCHANT-NAME} &rarr; {@code merchantName}</li>
     *   <li>{@code DALYTRAN-MERCHANT-CITY} &rarr; {@code merchantCity}</li>
     *   <li>{@code DALYTRAN-MERCHANT-ZIP}  &rarr; {@code merchantZip}</li>
     *   <li>{@code DALYTRAN-CARD-NUM}      &rarr; {@code cardNum}</li>
     *   <li>{@code DALYTRAN-ORIG-TS}       &rarr; {@code origTs} (parsed to {@link LocalDateTime})</li>
     *   <li>{@code Z-GET-DB2-FORMAT-TIMESTAMP} &rarr; {@code procTs} ({@link LocalDateTime#now()})</li>
     * </ul>
     *
     * <p>The {@code acctId} is set from the cross-reference ({@code XREF-ACCT-ID}). The legacy
     * {@code TRAN-RECORD} copybook has no account-id field (the linkage is implicit through the card
     * number), but the relational {@link Transaction} entity carries a denormalized {@code acct_id}
     * that backs the {@code TRANSACT.AIX} composite index {@code (acct_id, orig_ts)} per AAP
     * &sect;0.3.1 / &sect;0.6.4; populating it here preserves that access path.</p>
     *
     * @param rec    the validated daily-transaction feed record (source of every transaction field)
     * @param acctId the owning account identifier resolved from the cross-reference
     *               ({@link CardXref#getXrefAcctId()})
     * @return a fully-populated {@link Transaction} ready for persistence by the writer
     */
    private Transaction buildTransaction(DailyTransactionRecord rec, Long acctId) {
        Transaction tx = new Transaction();
        tx.setTranId(rec.getId());                  // DALYTRAN-ID carried verbatim
        tx.setTypeCd(rec.getTypeCd());
        tx.setCatCd(rec.getCatCd());
        tx.setSource(rec.getSource());
        tx.setDescription(rec.getDescription());
        tx.setAmt(rec.getAmt());
        tx.setMerchantId(rec.getMerchantId());
        tx.setMerchantName(rec.getMerchantName());
        tx.setMerchantCity(rec.getMerchantCity());
        tx.setMerchantZip(rec.getMerchantZip());
        tx.setCardNum(rec.getCardNum());
        tx.setAcctId(acctId);                        // from the cross-reference (XREF-ACCT-ID)
        tx.setOrigTs(rec.getOrigTimestamp());        // DALYTRAN-ORIG-TS -> TRAN-ORIG-TS
        tx.setProcTs(LocalDateTime.now());           // Z-GET-DB2-FORMAT-TIMESTAMP -> TRAN-PROC-TS
        return tx;
    }

    /**
     * Null-coalescing helper for monetary fields: returns {@link BigDecimal#ZERO} when the supplied
     * value is {@code null}, otherwise the value unchanged.
     *
     * <p>COBOL packed-decimal ({@code COMP-3}) numeric items are never {@code null} &mdash; an
     * uninitialised numeric is zero &mdash; so coalescing a Java {@code null} to {@code ZERO} is
     * behavior-preserving for all real account/feed data. Its sole purpose is defensive robustness:
     * a single malformed row (e.g. a hand-built test fixture with an unset amount) cannot abort an
     * entire Spring Batch chunk with a {@link NullPointerException}.</p>
     *
     * @param value the monetary value, possibly {@code null}
     * @return {@code value} if non-{@code null}, otherwise {@link BigDecimal#ZERO}
     */
    private static BigDecimal nz(BigDecimal value) {
        return (value == null) ? BigDecimal.ZERO : value;
    }
}
