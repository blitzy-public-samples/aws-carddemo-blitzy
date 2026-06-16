package com.carddemo.batch;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.batch.core.ExitStatus;
import org.springframework.batch.core.StepExecution;
import org.springframework.batch.core.StepExecutionListener;
import org.springframework.batch.item.Chunk;
import org.springframework.batch.item.ItemWriter;
import org.springframework.batch.item.file.FlatFileItemWriter;

import com.carddemo.entity.Account;
import com.carddemo.entity.Transaction;
import com.carddemo.entity.TransactionCategoryBalance;
import com.carddemo.repository.AccountRepository;
import com.carddemo.repository.TransactionCategoryBalanceRepository;
import com.carddemo.repository.TransactionRepository;
import com.carddemo.util.CardDemoConstants;

/**
 * Spring Batch&nbsp;5 {@link ItemWriter} that reproduces the <strong>posting and reject-output</strong>
 * side of the legacy COBOL daily-transaction-posting program {@code app/cbl/CBTRN02C.cbl}.
 *
 * <h2>Source of truth</h2>
 * <p>This writer is the faithful Java re-expression of the following {@code CBTRN02C} paragraphs
 * (L424&ndash;L575); <strong>the actual COBOL governs</strong> in every parity decision:</p>
 * <ul>
 *   <li>{@code 2000-POST-TRANSACTION} (L424&ndash;L444) &mdash; the valid-path orchestration that
 *       performs, in order, {@code 2700-UPDATE-TCATBAL}, {@code 2800-UPDATE-ACCOUNT-REC} and
 *       {@code 2900-WRITE-TRANSACTION-FILE}.</li>
 *   <li>{@code 2700-UPDATE-TCATBAL} (L467&ndash;L542) &mdash; the per-category balance
 *       <em>insert-or-add</em> upsert keyed by {@code (acct_id, type_cd, cat_cd)}.</li>
 *   <li>{@code 2800-UPDATE-ACCOUNT-REC} (L545&ndash;L560) &mdash; the signed balance update and the
 *       cycle credit/debit split, whose {@code REWRITE ... INVALID KEY} guard raises reject
 *       code&nbsp;{@code 109}.</li>
 *   <li>{@code 2900-WRITE-TRANSACTION-FILE} (L562&ndash;L575) &mdash; persistence of the posted
 *       transaction.</li>
 *   <li>{@code 2500-WRITE-REJECT-REC} (L446&ndash;L465) &mdash; emission of the fixed-width 430-byte
 *       {@code DALYREJS} reject record.</li>
 * </ul>
 *
 * <h2>Dual output (CBTRN02C parity)</h2>
 * <p>Each {@link ProcessedTransaction} produced by the processor is routed one of two ways, exactly
 * mirroring the COBOL read-validate-post / read-validate-reject loop:</p>
 * <ul>
 *   <li><strong>Valid</strong> &rarr; posted to the database: the transaction-category balance is
 *       upserted, the owning account balance is updated, and the transaction row is written.</li>
 *   <li><strong>Rejected</strong> (validation codes 100/101/102/103, or code&nbsp;109 raised
 *       <em>here</em> when the account update fails) &rarr; written as a 430-byte fixed-width
 *       {@code DALYREJS} line through the injected {@link FlatFileItemWriter} delegate.</li>
 * </ul>
 *
 * <h2>The 109 reject is raised here</h2>
 * <p>Reject code&nbsp;109 ({@code ACCOUNT RECORD NOT FOUND}) completes the 100/101/102/103/109
 * superset (AAP&nbsp;&sect;0.6.1, &sect;0.7.3 #3). It corresponds to the COBOL
 * {@code 2800-UPDATE-ACCOUNT-REC} {@code REWRITE ... INVALID KEY} branch (L555&ndash;L558): when the
 * valid-path persistence throws (a missing account, or an
 * {@link org.springframework.orm.ObjectOptimisticLockingFailureException} from the {@link Account}
 * {@code @Version} guard, or any other persistence failure), {@link #write(Chunk)} catches it, flips
 * the item to reject&nbsp;109 via {@link ProcessedTransaction#markRejected(int, String)} and emits the
 * reject line &mdash; the failure superset. Because the failure is converted to a soft reject rather
 * than rethrown, an ordinary account-update failure never fails the whole job (see exit-status
 * mapping below).</p>
 *
 * <h2>RETURN-CODE&nbsp;=&nbsp;4 &rarr; WARNING exit status</h2>
 * <p>When {@code CBTRN02C} rejects at least one record it sets {@code RETURN-CODE = 4}
 * ({@code IF WS-REJECT-COUNT &gt; 0 MOVE 4 TO RETURN-CODE}, L229&ndash;L230). This writer implements
 * {@link StepExecutionListener} so that a non-zero reject count surfaces as a soft
 * {@link ExitStatus#WARNING WARNING} exit status &mdash; never a hard {@link ExitStatus#FAILED FAILED}
 * &mdash; preserving the legacy "rejects present, job still completed" semantics.</p>
 *
 * <h2>Transaction &amp; concurrency model</h2>
 * <p>This is a <strong>plain class</strong> (no Spring stereotype); it is instantiated as a
 * {@code @Bean} in {@code TransactionPostingJobConfig}. Spring Batch wraps each {@link #write(Chunk)}
 * call in the step's {@link org.springframework.transaction.PlatformTransactionManager} transaction,
 * so the re-fetched {@link Account} and {@link TransactionCategoryBalance} are managed entities whose
 * mutations are flushed at chunk commit. The account is read with the optimistic
 * {@link AccountRepository#findById(Object)} (rather than the pessimistic
 * {@code findByIdForUpdate}) precisely because the {@code @Version} optimistic-lock guard must be
 * allowed to <em>throw</em> on a stale write so the failure can be converted to a code-109 reject; a
 * pessimistic lock would block instead, defeating that design. {@code CBTRN02C} runs as a single
 * sequential program, so the default single-threaded chunk step reproduces its behavior exactly.</p>
 *
 * <h2>Reject-record byte compatibility</h2>
 * <p>{@link #buildRejectLine(ProcessedTransaction)} emits exactly
 * {@link CardDemoConstants#DALYREJS_RECORD_WIDTH 430} characters &mdash; a
 * {@link CardDemoConstants#DALYREJS_FEED_IMAGE_WIDTH 350}-byte image of the original feed record plus
 * an {@link CardDemoConstants#DALYREJS_TRAILER_WIDTH 80}-byte validation trailer
 * ({@link CardDemoConstants#DALYREJS_FAIL_REASON_WIDTH 4}-digit zero-padded reason +
 * {@link CardDemoConstants#DALYREJS_FAIL_DESC_WIDTH 76}-character space-padded description) &mdash; so
 * the artifact stays byte-for-byte compatible with the legacy {@code DALYREJS} dataset. Every width is
 * taken from {@link CardDemoConstants}; none is hard-coded.</p>
 *
 * @see ProcessedTransaction
 * @see DailyTransactionRecord
 * @see CardDemoConstants
 * @see <a href="file:app/cbl/CBTRN02C.cbl">CBTRN02C.cbl &mdash; daily transaction posting</a>
 */
public class TransactionPostingWriter
        implements ItemWriter<ProcessedTransaction>, StepExecutionListener {

    /** PII-safe logger; only non-sensitive ids (transaction id, account id) are ever logged. */
    private static final Logger log = LoggerFactory.getLogger(TransactionPostingWriter.class);

    /** Repository for the owning {@code accounts} master row updated by {@code 2800-UPDATE-ACCOUNT-REC}. */
    private final AccountRepository accountRepository;

    /** Repository for the per-category balance upserted by {@code 2700-UPDATE-TCATBAL}. */
    private final TransactionCategoryBalanceRepository tranCatBalRepository;

    /** Repository for the posted transaction written by {@code 2900-WRITE-TRANSACTION-FILE}. */
    private final TransactionRepository transactionRepository;

    /**
     * Delegate writer for the 430-byte fixed-width {@code DALYREJS} reject lines. Defined as a
     * {@code @StepScope} {@link FlatFileItemWriter} bean in {@code TransactionPostingJobConfig}; its
     * open/update/close lifecycle MUST be registered on the step via {@code .stream(rejectWriter)}
     * there (this writer only invokes {@link FlatFileItemWriter#write(Chunk)}).
     */
    private final FlatFileItemWriter<String> rejectWriter;

    /**
     * Count of records rejected during the current step execution. Reset in {@link #beforeStep} and
     * used by {@link #afterStep} to decide between a {@code WARNING} and a {@code COMPLETED} exit
     * status (the COBOL {@code RETURN-CODE = 4} equivalent).
     */
    private int rejectCount;

    /**
     * Constructs the writer with all collaborators injected (constructor injection; the bean is wired
     * in {@code TransactionPostingJobConfig}).
     *
     * @param accountRepository    repository for account-balance updates ({@code 2800})
     * @param tranCatBalRepository repository for the per-category balance upsert ({@code 2700})
     * @param transactionRepository repository for posted-transaction writes ({@code 2900})
     * @param rejectWriter         the 430-byte {@code DALYREJS} fixed-width reject delegate
     */
    public TransactionPostingWriter(AccountRepository accountRepository,
                                    TransactionCategoryBalanceRepository tranCatBalRepository,
                                    TransactionRepository transactionRepository,
                                    FlatFileItemWriter<String> rejectWriter) {
        this.accountRepository = accountRepository;
        this.tranCatBalRepository = tranCatBalRepository;
        this.transactionRepository = transactionRepository;
        this.rejectWriter = rejectWriter;
    }

    /**
     * Processes one chunk of {@link ProcessedTransaction} items, performing the {@code CBTRN02C} dual
     * output: valid items are posted to the database; rejected items (and any valid item whose post
     * fails) are emitted as 430-byte {@code DALYREJS} reject lines.
     *
     * <p>Reject lines are accumulated and flushed once at the end of the chunk in feed order, then the
     * {@link #rejectCount running reject count} is advanced so {@link #afterStep} can surface the
     * {@code RETURN-CODE = 4} warning condition.</p>
     *
     * @param chunk the chunk of processed items supplied by Spring Batch; never {@code null}
     * @throws Exception if the delegate {@link FlatFileItemWriter#write(Chunk)} fails to write the
     *                   reject lines (an I/O fault on the reject file is a genuine job failure and is
     *                   intentionally allowed to propagate, unlike per-record validation rejects)
     */
    @Override
    public void write(Chunk<? extends ProcessedTransaction> chunk) throws Exception {
        // Accumulate every reject line for this chunk; a single delegate write at the end keeps the
        // DALYREJS output ordered and minimizes flat-file flushes (COBOL wrote one reject per record,
        // but order within the chunk is preserved, which is what matters for byte compatibility).
        List<String> rejectLines = new ArrayList<>();

        for (ProcessedTransaction pt : chunk) {
            // Records already rejected by the processor (codes 100/101/102/103) go straight to the
            // reject file (COBOL 2500-WRITE-REJECT-REC); no posting is attempted.
            if (pt.isRejected()) {
                rejectLines.add(buildRejectLine(pt));
                continue;
            }

            // Valid path: attempt the post (2700 + 2800 + 2900). A persistence failure mirrors the
            // COBOL 2800 "REWRITE ... INVALID KEY" branch and is converted to a soft reject code 109
            // rather than being allowed to fail the whole job.
            try {
                postValid(pt);
            } catch (Exception ex) {
                // Capture the ids BEFORE markRejected(...) clears the built transaction, and do so
                // null-safely so a logging access can never escape this catch block.
                Transaction failed = pt.getTransaction();
                String tranId = (failed != null) ? failed.getTranId() : null;
                Long acctId = (failed != null) ? failed.getAcctId() : null;
                log.warn("Account update/post failed for tranId={} acct={} -> reject 109: {}",
                        tranId, acctId, ex.toString());

                pt.markRejected(CardDemoConstants.REJECT_CODE_ACCOUNT_UPDATE_FAILED,   // 109
                        CardDemoConstants.REJECT_DESC_ACCOUNT_UPDATE_FAILED);          // "ACCOUNT RECORD NOT FOUND"
                rejectLines.add(buildRejectLine(pt));
            }
        }

        if (!rejectLines.isEmpty()) {
            rejectWriter.write(new Chunk<>(rejectLines));
            rejectCount += rejectLines.size();
        }
    }

    /**
     * Persists one valid transaction, reproducing COBOL {@code 2000-POST-TRANSACTION} in its exact
     * order: {@code 2700-UPDATE-TCATBAL}, then {@code 2800-UPDATE-ACCOUNT-REC}, then
     * {@code 2900-WRITE-TRANSACTION-FILE}.
     *
     * <p>All monetary arithmetic uses {@link BigDecimal} (signed {@code add}, {@code signum}) so the
     * COBOL fixed-point semantics are reproduced without any rounding drift; the amount is already
     * scale-2 as decoded by the reader.</p>
     *
     * @param pt a valid processed item carrying the fully-built {@link Transaction} to post
     * @throws RuntimeException (typically {@link IllegalStateException} for a missing account, or
     *                          {@link org.springframework.orm.ObjectOptimisticLockingFailureException}
     *                          for a stale {@code @Version}) when the post fails; the caller converts
     *                          this to a code-109 reject
     */
    private void postValid(ProcessedTransaction pt) {
        Transaction tx = pt.getTransaction();
        Long acctId = tx.getAcctId();
        BigDecimal amount = tx.getAmt();

        // --- 2700-UPDATE-TCATBAL: per-category balance upsert keyed by (acct_id, type_cd, cat_cd). ---
        // COBOL reads TCATBAL by FD-TRAN-CAT-KEY; if INVALID KEY it INITIALIZEs a new record (balance
        // 0) and ADDs the amount, otherwise it ADDs the amount to the existing balance and REWRITEs.
        TransactionCategoryBalance.TransactionCategoryBalanceId key =
                new TransactionCategoryBalance.TransactionCategoryBalanceId(
                        acctId, tx.getTypeCd(), tx.getCatCd());
        TransactionCategoryBalance tcb = tranCatBalRepository.findById(key).orElse(null);
        if (tcb == null) {
            // 2700-A-CREATE-TCATBAL-REC: new bucket starts at 0 then the amount is added => amount.
            tcb = new TransactionCategoryBalance();
            tcb.setId(key);
            tcb.setTranCatBal(amount);
        } else {
            // 2700-B-UPDATE-TCATBAL-REC: accumulate the signed amount into the running balance.
            tcb.setTranCatBal(tcb.getTranCatBal().add(amount));
        }
        tranCatBalRepository.save(tcb);

        // --- 2800-UPDATE-ACCOUNT-REC: signed balance update + cycle credit/debit split. ---
        // Re-fetch via the optimistic findById so the entity is managed within the chunk transaction
        // and the @Version guard can throw on a stale write (converted to reject 109 by the caller).
        // A missing account reproduces the COBOL "REWRITE ... INVALID KEY" -> 109 branch.
        Account acct = accountRepository.findById(acctId)
                .orElseThrow(() -> new IllegalStateException(
                        CardDemoConstants.REJECT_DESC_ACCOUNT_UPDATE_FAILED));

        // ADD DALYTRAN-AMT TO ACCT-CURR-BAL (signed add).
        acct.setCurrBal(acct.getCurrBal().add(amount));

        // IF DALYTRAN-AMT >= 0 ADD TO ACCT-CURR-CYC-CREDIT ELSE ADD TO ACCT-CURR-CYC-DEBIT.
        // PARITY: the negative amount is ADDED to the debit field (it accumulates the negative); the
        // sign is never stripped via abs(). signum() >= 0 matches the COBOL ">= 0" comparison exactly.
        if (amount.signum() >= 0) {
            acct.setCurrCycCredit(acct.getCurrCycCredit().add(amount));
        } else {
            acct.setCurrCycDebit(acct.getCurrCycDebit().add(amount));
        }
        accountRepository.save(acct);

        // --- 2900-WRITE-TRANSACTION-FILE: persist the posted transaction. ---
        transactionRepository.save(tx);
    }

    /**
     * Builds one {@code DALYREJS} reject record for the supplied rejected item: an exactly
     * {@value com.carddemo.util.CardDemoConstants#DALYREJS_RECORD_WIDTH}-character fixed-width line
     * composed of the original feed image, the zero-padded reject code, and the space-padded reason
     * description &mdash; the Java form of COBOL {@code REJECT-RECORD} =
     * {@code FD-REJECT-RECORD X(350)} + {@code FD-VALIDATION-TRAILER X(80)} where the trailer =
     * {@code WS-VALIDATION-FAIL-REASON 9(04)} + {@code WS-VALIDATION-FAIL-DESC X(76)}.
     *
     * @param pt the rejected processed item (its source feed image, reject code and reject
     *           description supply the three segments)
     * @return a reject line of exactly {@link CardDemoConstants#DALYREJS_RECORD_WIDTH} characters
     * @throws IllegalStateException if the assembled width is not exactly the DALYREJS record width
     *                               (an invariant violation that can only arise from a reject code
     *                               wider than {@link CardDemoConstants#DALYREJS_FAIL_REASON_WIDTH}
     *                               digits, which the bounded 100&ndash;109 code set never produces)
     */
    private String buildRejectLine(ProcessedTransaction pt) {
        // 350-byte image of the original daily-transaction feed record (REJECT-TRAN-DATA).
        String feed = fit(pt.getSource().getRawRecord(), CardDemoConstants.DALYREJS_FEED_IMAGE_WIDTH);

        // 4-digit zero-padded reject reason (WS-VALIDATION-FAIL-REASON PIC 9(04), e.g. 0100 / 0109).
        String reason = String.format(
                "%0" + CardDemoConstants.DALYREJS_FAIL_REASON_WIDTH + "d", pt.getRejectCode());

        // 76-char left-justified, space-padded reason description (WS-VALIDATION-FAIL-DESC PIC X(76)).
        String desc = fit(pt.getRejectDesc() == null ? "" : pt.getRejectDesc(),
                CardDemoConstants.DALYREJS_FAIL_DESC_WIDTH);

        String line = feed + reason + desc; // 350 + 4 + 76 = 430

        // Defensive invariant: the DALYREJS dataset is fixed-width, so a wrong length is a hard bug.
        if (line.length() != CardDemoConstants.DALYREJS_RECORD_WIDTH) {
            throw new IllegalStateException(
                    "DALYREJS reject record width " + line.length() + " != expected "
                            + CardDemoConstants.DALYREJS_RECORD_WIDTH
                            + " (rejectCode=" + pt.getRejectCode() + ")");
        }
        return line;
    }

    /**
     * Fits a string to an exact fixed width: right-pads with spaces when shorter than {@code width},
     * or truncates to {@code width} when longer (and treats {@code null} as empty). This reproduces
     * the COBOL fixed-width {@code MOVE} of an alphanumeric value into a {@code PIC X(width)} field.
     *
     * @param s     the source value (may be {@code null})
     * @param width the exact target width in characters; expected to be positive
     * @return a string of exactly {@code width} characters
     */
    private static String fit(String s, int width) {
        String value = (s == null) ? "" : s;
        return value.length() >= width
                ? value.substring(0, width)
                : String.format("%-" + width + "s", value);
    }

    /**
     * Resets the per-execution reject counter at the start of the step so the
     * {@code RETURN-CODE = 4} decision in {@link #afterStep} is based only on the current run.
     *
     * @param stepExecution the starting step execution (unused beyond lifecycle hook)
     */
    @Override
    public void beforeStep(StepExecution stepExecution) {
        this.rejectCount = 0;
    }

    /**
     * Surfaces the COBOL {@code RETURN-CODE = 4} "rejects present" condition as a soft
     * {@link ExitStatus} {@code WARNING} when one or more records were rejected, otherwise reports
     * {@link ExitStatus#COMPLETED}. A reject condition is deliberately <strong>never</strong> mapped
     * to {@link ExitStatus#FAILED}, preserving the legacy "job completed with rejects" semantics.
     *
     * @param stepExecution the completed step execution (unused beyond lifecycle hook)
     * @return {@code WARNING} when {@code rejectCount > 0}, else {@code COMPLETED}
     */
    @Override
    public ExitStatus afterStep(StepExecution stepExecution) {
        if (rejectCount > 0) {
            log.warn("Transaction posting completed with {} rejected record(s) "
                    + "(COBOL RETURN-CODE=4 equivalent)", rejectCount);
            return new ExitStatus("WARNING",
                    rejectCount + " rejected record(s); RETURN-CODE=4 equivalent");
        }
        return ExitStatus.COMPLETED;
    }
}
