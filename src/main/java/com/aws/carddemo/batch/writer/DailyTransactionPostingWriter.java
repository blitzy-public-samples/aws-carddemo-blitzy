/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License").
 * You may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.aws.carddemo.batch.writer;

import java.io.BufferedWriter;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

import com.aws.carddemo.batch.processor.DailyTransactionPostingProcessor;
import com.aws.carddemo.batch.processor.DailyTransactionPostingProcessor.PostingResult;
import com.aws.carddemo.common.util.FixedWidthCodec;
import com.aws.carddemo.common.util.FixedWidthCodec.FieldDef;
import com.aws.carddemo.common.util.FixedWidthCodec.RecordBuilder;
import com.aws.carddemo.domain.Account;
import com.aws.carddemo.domain.DailyTransaction;
import com.aws.carddemo.domain.Transaction;
import com.aws.carddemo.domain.TransactionCategoryBalance;
import com.aws.carddemo.domain.TransactionCategoryBalance.TransactionCategoryBalanceId;
import com.aws.carddemo.exception.FileStatusException;
import com.aws.carddemo.exception.RejectCode;
import com.aws.carddemo.repository.AccountRepository;
import com.aws.carddemo.repository.TransactionCategoryBalanceRepository;
import com.aws.carddemo.repository.TransactionRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.ExitStatus;
import org.springframework.batch.core.StepExecution;
import org.springframework.batch.core.StepExecutionListener;
import org.springframework.batch.item.Chunk;
import org.springframework.batch.item.ItemWriter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Spring Batch {@link ItemWriter} that reproduces the <em>persistence and file-output side</em> of
 * the legacy COBOL batch program {@code CBTRN02C} (source {@code legacy/cbl/CBTRN02C.cbl}, formerly
 * {@code app/cbl/CBTRN02C.cbl}), triggered by {@code POSTTRAN.jcl}. It is the writer half of the
 * {@code DailyTransactionPostingJob}; the parent {@code batch/} configuration wires it into
 * {@code dailyTransactionPostingStep} with a <strong>chunk size of 1</strong>, consuming each
 * {@link PostingResult} emitted by the sibling {@link DailyTransactionPostingProcessor}.
 *
 * <p>This is the highest parity-risk component in the posting flow (Technical Specification
 * &sect;0.7.1 hotspots H4 &mdash; reject-code semantics &mdash; and H6 &mdash; read-update-rewrite
 * concurrency &mdash; and &sect;0.9.2 reject-path parity).</p>
 *
 * <h2>Responsibility boundary &mdash; ALL persistence and output</h2>
 * <p>The processor performs only read-only validation, assigns the {@link RejectCode} (or none),
 * and &mdash; for a valid record &mdash; builds the candidate {@link Transaction}; it mutates and
 * persists nothing. This writer performs every mutating COBOL paragraph: the
 * transaction-category-balance upsert, the account balance / cycle rewrite, the transaction insert,
 * and the reject-file write. The exact COBOL write order and arithmetic are preserved.</p>
 *
 * <h2>Per-record routing (MAIN loop, {@code CBTRN02C} L205-L216)</h2>
 * <ul>
 *   <li><strong>valid</strong> ({@link PostingResult#isRejected()} {@code == false}) &rarr;
 *       {@code 2000-POST-TRANSACTION} (L440-L442) = {@code 2700-UPDATE-TCATBAL} &rarr;
 *       {@code 2800-UPDATE-ACCOUNT-REC} &rarr; {@code 2900-WRITE-TRANSACTION-FILE}, in that exact
 *       order;</li>
 *   <li><strong>reject</strong> ({@link PostingResult#isRejected()} {@code == true}) &rarr;
 *       increment the reject count then {@code 2500-WRITE-REJECT-REC} (L214-L215, L446-L451),
 *       writing the 430-byte {@code DALYREJS} record.</li>
 * </ul>
 * <p>Every record increments the processed count first ({@code ADD 1 TO WS-TRANSACTION-COUNT},
 * L206).</p>
 *
 * <h2>430-byte {@code DALYREJS} layout ({@code POSTTRAN.jcl} {@code DCB=(RECFM=F,LRECL=430)})</h2>
 * <p>The reject record is the 350-byte {@code DALYTRAN} image (copybook {@code CVTRA06Y}) followed
 * by an 80-byte validation trailer of {@code WS-VALIDATION-FAIL-REASON PIC 9(04)} and
 * {@code WS-VALIDATION-FAIL-REASON-DESC PIC X(76)} (mirroring {@code CBTRN02C} L448). The 20-byte
 * {@code FILLER} at offset 330 and any short-field padding remain spaces, matching the COBOL
 * fixed-field image.</p>
 *
 * <h2>Return-code mapping (MAIN L221-L231)</h2>
 * <ul>
 *   <li><strong>RC 0</strong> &mdash; {@link ExitStatus#COMPLETED}: no rejects and no I/O error;</li>
 *   <li><strong>RC 4</strong> &mdash; {@code new ExitStatus("COMPLETED_WITH_REJECTS")}: at least one
 *       reject ({@code IF WS-REJECT-COUNT > 0 MOVE 4 TO RETURN-CODE}). The parent
 *       {@code DailyTransactionPostingJob} maps this exit-status string to process return code 4;
 *       the literal is a coordination contract with the job's exit-code mapping and must not be
 *       changed without updating the job;</li>
 *   <li><strong>RC 8</strong> &mdash; {@link ExitStatus#FAILED}: an I/O error opening/writing/closing
 *       the reject file, or an integrity abend (see below). Mirrors the COBOL
 *       {@code 9999-ABEND-PROGRAM} paths.</li>
 * </ul>
 *
 * <h2>Documented deviation &mdash; reason {@code 109} (account rewrite not found)</h2>
 * <p>The COBOL {@code 2800-UPDATE-ACCOUNT-REC} sets reason {@code 109} on the account {@code REWRITE
 * INVALID KEY} and continues; because {@code 109} is set after the {@code reason == 0} gate, it is
 * latent (the COBOL neither rejects nor abends on it). The Java target instead treats an account
 * that cannot be re-read at write time as a hard integrity error &mdash; a
 * {@link FileStatusException} that fails the step (RC 8). {@code 109} is therefore
 * <strong>not</strong> a {@link RejectCode} constant. This intentional deviation is recorded in
 * {@code docs/decision-log.md} (owned by the documentation deliverable, not edited here). Because the
 * processor already validated that the account exists and this writer re-reads it within the same
 * short chunk transaction, the branch is defensive and does not trigger on a consistent dataset.</p>
 *
 * <h2>Concurrency improvement (AAP H6)</h2>
 * <p>Both {@link Account} and {@link TransactionCategoryBalance} carry a JPA {@code @Version} column,
 * so the read-update-rewrite cycles reproduce last-writer integrity via optimistic locking &mdash; a
 * documented improvement over the COBOL VSAM {@code REWRITE}, also captured in the decision log.</p>
 *
 * <h2>Transaction boundary</h2>
 * <p>The step runs with chunk size 1 and Spring Batch wraps each chunk in its own transaction, so a
 * record's {@code 2700}/{@code 2800}/{@code 2900} JPA writes are atomic per record. The
 * {@code write} method is deliberately <strong>not</strong> annotated {@code @Transactional} &mdash;
 * adding a self-managed transaction would fork an incorrect nested transaction. The reject-file
 * write is plain file I/O, intentionally outside the database transaction (the COBOL writes rejects
 * sequentially; with chunk size 1 and no retry each record is processed exactly once).</p>
 *
 * <h2>Monetary discipline &amp; PII</h2>
 * <p>All monetary arithmetic uses {@link BigDecimal} at scale 2; {@code double}/{@code float} are
 * never used. Sensitive fields (SSN, CVV, password, full card number) and the assembled 430-byte
 * record are never logged; only the numeric reject counts and reason codes are logged.</p>
 */
@Component
public class DailyTransactionPostingWriter
        implements ItemWriter<PostingResult>, StepExecutionListener {

    /**
     * Logger for non-sensitive diagnostics only (record counts and, at most, the numeric reject
     * reason code and the business transaction id). Card numbers (PAN), CVV, SSN, passwords, and the
     * assembled reject record are never logged.
     */
    private static final Logger log = LoggerFactory.getLogger(DailyTransactionPostingWriter.class);

    /** Total {@code DALYREJS} record width in characters ({@code POSTTRAN.jcl LRECL=430}). */
    private static final int DALYREJS_RECORD_LENGTH = 430;

    /**
     * Record framing delimiter appended after each 430-byte record. A literal line feed
     * ({@code "\n"}) is used rather than {@link System#lineSeparator()} so golden-file fixtures stay
     * deterministic across platforms; the 430-byte record content is the authoritative fixed-width
     * contract and the trailing LF is a documented Java file-framing convention consistent with the
     * sibling {@code TransactionBackupItemWriter}.
     */
    private static final String RECORD_DELIMITER = "\n";

    // ------------------------------------------------------------------------
    // 430-byte DALYREJS field descriptors (350-byte DALYTRAN image, copybook
    // CVTRA06Y, + 80-byte validation trailer, CBTRN02C L448). Offsets 0-based.
    // ------------------------------------------------------------------------

    /** {@code DALYTRAN-ID PIC X(16)} at offset 0. */
    private static final FieldDef F_ID = FieldDef.alphanumeric("DALYTRAN-ID", 0, 16);
    /** {@code DALYTRAN-TYPE-CD PIC X(02)} at offset 16. */
    private static final FieldDef F_TYPE_CD = FieldDef.alphanumeric("DALYTRAN-TYPE-CD", 16, 2);
    /** {@code DALYTRAN-CAT-CD PIC 9(04)} at offset 18. */
    private static final FieldDef F_CAT_CD = FieldDef.numeric("DALYTRAN-CAT-CD", 18, 4);
    /** {@code DALYTRAN-SOURCE PIC X(10)} at offset 22. */
    private static final FieldDef F_SOURCE = FieldDef.alphanumeric("DALYTRAN-SOURCE", 22, 10);
    /** {@code DALYTRAN-DESC PIC X(100)} at offset 32. */
    private static final FieldDef F_DESC = FieldDef.alphanumeric("DALYTRAN-DESC", 32, 100);
    /** {@code DALYTRAN-AMT PIC S9(09)V99} (11 chars, scale 2) at offset 132. */
    private static final FieldDef F_AMT = FieldDef.signedDecimal("DALYTRAN-AMT", 132, 11, 2);
    /** {@code DALYTRAN-MERCHANT-ID PIC 9(09)} at offset 143. */
    private static final FieldDef F_MERCHANT_ID = FieldDef.numeric("DALYTRAN-MERCHANT-ID", 143, 9);
    /** {@code DALYTRAN-MERCHANT-NAME PIC X(50)} at offset 152. */
    private static final FieldDef F_MERCHANT_NAME = FieldDef.alphanumeric("DALYTRAN-MERCHANT-NAME", 152, 50);
    /** {@code DALYTRAN-MERCHANT-CITY PIC X(50)} at offset 202. */
    private static final FieldDef F_MERCHANT_CITY = FieldDef.alphanumeric("DALYTRAN-MERCHANT-CITY", 202, 50);
    /** {@code DALYTRAN-MERCHANT-ZIP PIC X(10)} at offset 252. */
    private static final FieldDef F_MERCHANT_ZIP = FieldDef.alphanumeric("DALYTRAN-MERCHANT-ZIP", 252, 10);
    /** {@code DALYTRAN-CARD-NUM PIC X(16)} at offset 262. */
    private static final FieldDef F_CARD_NUM = FieldDef.alphanumeric("DALYTRAN-CARD-NUM", 262, 16);
    /** {@code DALYTRAN-ORIG-TS PIC X(26)} at offset 278. */
    private static final FieldDef F_ORIG_TS = FieldDef.alphanumeric("DALYTRAN-ORIG-TS", 278, 26);
    /** {@code DALYTRAN-PROC-TS PIC X(26)} at offset 304. */
    private static final FieldDef F_PROC_TS = FieldDef.alphanumeric("DALYTRAN-PROC-TS", 304, 26);
    // DALYTRAN FILLER PIC X(20) at offset 330 stays spaces (RecordBuilder initializes to spaces).
    /** Validation trailer {@code WS-VALIDATION-FAIL-REASON PIC 9(04)} at offset 350. */
    private static final FieldDef F_REASON_CODE = FieldDef.numeric("VALIDATION-FAIL-REASON", 350, 4);
    /** Validation trailer {@code WS-VALIDATION-FAIL-REASON-DESC PIC X(76)} at offset 354. */
    private static final FieldDef F_REASON_DESC = FieldDef.alphanumeric("VALIDATION-FAIL-REASON-DESC", 354, 76);

    // ------------------------------------------------------------------------
    // Injected collaborators (constructor injection only; no field @Autowired).
    // ------------------------------------------------------------------------

    /** Account master repository ({@code ACCTFILE}); used by {@code 2800-UPDATE-ACCOUNT-REC}. */
    private final AccountRepository accountRepository;

    /** Transaction master repository ({@code TRANSACT}); used by {@code 2900-WRITE-TRANSACTION-FILE}. */
    private final TransactionRepository transactionRepository;

    /** Transaction-category-balance repository ({@code TCATBALF}); used by {@code 2700-UPDATE-TCATBAL}. */
    private final TransactionCategoryBalanceRepository tranCatBalanceRepository;

    /** Configurable output directory for the reject file (no hardcoded paths, AAP constraint). */
    private final String rejectDirectory;

    /** Configurable file name for the reject file within {@link #rejectDirectory}. */
    private final String rejectFileName;

    // ------------------------------------------------------------------------
    // Per-run mutable state (reset in beforeStep; not shared between step runs).
    // ------------------------------------------------------------------------

    /** Count of every record processed ({@code WS-TRANSACTION-COUNT}). */
    private long transactionCount;

    /** Count of rejected records ({@code WS-REJECT-COUNT}). */
    private long rejectCount;

    /** Open reject-file writer for the current step, or {@code null} before/after the step. */
    private BufferedWriter rejectWriter;

    /** Sticky flag set on any reject-file I/O failure; drives the RC 8 mapping in {@code afterStep}. */
    private boolean ioError;

    /**
     * Creates the writer with its collaborating repositories and the externalized reject-file
     * location injected by constructor (the project uses constructor injection exclusively; no field
     * injection). {@link FixedWidthCodec} is a stateless static-only utility and is therefore never
     * injected &mdash; its static methods are called directly.
     *
     * @param accountRepository          account master repository ({@code 2800})
     * @param transactionRepository      transaction master repository ({@code 2900})
     * @param tranCatBalanceRepository   transaction-category-balance repository ({@code 2700})
     * @param rejectDirectory            output directory for the reject file; defaults to
     *                                   {@code ./target/batch}
     * @param rejectFileName             reject file name; defaults to {@code DALYREJS.dat}
     */
    public DailyTransactionPostingWriter(
            AccountRepository accountRepository,
            TransactionRepository transactionRepository,
            TransactionCategoryBalanceRepository tranCatBalanceRepository,
            @Value("${carddemo.batch.posting.reject-directory:./target/batch}") String rejectDirectory,
            @Value("${carddemo.batch.posting.reject-file:DALYREJS.dat}") String rejectFileName) {
        this.accountRepository = accountRepository;
        this.transactionRepository = transactionRepository;
        this.tranCatBalanceRepository = tranCatBalanceRepository;
        this.rejectDirectory = rejectDirectory;
        this.rejectFileName = rejectFileName;
    }

    /**
     * Resets the per-run counters and opens the reject file, reproducing {@code CBTRN02C}
     * {@code 0300-DALYREJS-OPEN} ({@code OPEN OUTPUT DALYREJS-FILE}, L291-L307). Spring Batch
     * auto-registers this bean as the step's {@link StepExecutionListener} because it is the step's
     * writer, so this callback fires automatically at the start of {@code dailyTransactionPostingStep}
     * &mdash; it must not be registered a second time.
     *
     * <p>The reject writer is opened <strong>unconditionally</strong> and truncates/creates the file
     * (COBOL {@code OPEN OUTPUT} always creates the dataset), so a zero-reject run still yields a
     * valid, empty reject file. {@link StandardCharsets#ISO_8859_1} guarantees a one-character to
     * one-byte mapping for byte-exact fixed-width output. An open failure sets {@link #ioError} and is
     * rethrown as a {@link FileStatusException} so the step fails fast (RC 8), mirroring the COBOL
     * open-failure {@code 9999-ABEND-PROGRAM} path.</p>
     *
     * @param stepExecution the current step execution (supplied by Spring Batch)
     */
    @Override
    public void beforeStep(StepExecution stepExecution) {
        this.transactionCount = 0L;
        this.rejectCount = 0L;
        this.ioError = false;
        this.rejectWriter = null;
        try {
            Path dir = Path.of(rejectDirectory);
            Files.createDirectories(dir);
            Path rejectPath = dir.resolve(rejectFileName);
            this.rejectWriter = Files.newBufferedWriter(rejectPath, StandardCharsets.ISO_8859_1);
        } catch (IOException e) {
            this.ioError = true;
            log.error("Unable to open reject file in directory '{}'", rejectDirectory, e);
            throw new FileStatusException(
                    FileStatusException.STATUS_OK, "Unable to open reject file", e);
        }
    }

    /**
     * Processes one chunk (chunk size 1) of {@link PostingResult} items, reproducing the body of the
     * {@code CBTRN02C} MAIN loop (L205-L216). Every item increments the processed count
     * ({@code ADD 1 TO WS-TRANSACTION-COUNT}, L206), then routes to exactly one path:
     * <ul>
     *   <li>rejected &rarr; increment the reject count and {@code 2500-WRITE-REJECT-REC} (L214-L215);</li>
     *   <li>valid &rarr; {@code 2000-POST-TRANSACTION}: {@code 2700-UPDATE-TCATBAL} then
     *       {@code 2800-UPDATE-ACCOUNT-REC} then {@code 2900-WRITE-TRANSACTION-FILE}, in that exact
     *       order (L440-L442).</li>
     * </ul>
     *
     * <p>This method is intentionally <strong>not</strong> {@code @Transactional}: the per-record JPA
     * writes execute inside the Spring Batch chunk transaction, so with chunk size 1 each record's
     * {@code 2700}/{@code 2800}/{@code 2900} mutations are atomic. The reject write is plain file I/O
     * and is intentionally outside that transaction.</p>
     *
     * @param chunk the chunk of posting results to persist (never {@code null})
     * @throws Exception if a repository operation or reject-file write fails; the exception fails the
     *                   step (RC 8), matching the COBOL {@code 9999-ABEND-PROGRAM} behavior
     */
    @Override
    public void write(Chunk<? extends PostingResult> chunk) throws Exception {
        for (PostingResult result : chunk.getItems()) {
            // ADD 1 TO WS-TRANSACTION-COUNT (MAIN L206) -- counts every record.
            transactionCount++;

            if (result.isRejected()) {
                // ELSE branch (MAIN L213-L215): ADD 1 TO WS-REJECT-COUNT + 2500-WRITE-REJECT-REC.
                rejectCount++;
                writeRejectRecord(result);
            } else {
                // reason == 0 (MAIN L211-L212) -> 2000-POST-TRANSACTION (L440-L442), exact order.
                updateTranCatBalance(result);                 // 2700-UPDATE-TCATBAL
                updateAccount(result);                        // 2800-UPDATE-ACCOUNT-REC
                Transaction posted = result.posted();
                transactionRepository.save(posted);           // 2900-WRITE-TRANSACTION-FILE
            }
        }
    }

    /**
     * Upserts the transaction-category-balance row, reproducing {@code CBTRN02C}
     * {@code 2700-UPDATE-TCATBAL} (L467-L543). The compound key is
     * {@code {XREF-ACCT-ID, DALYTRAN-TYPE-CD, DALYTRAN-CAT-CD}}.
     * <ul>
     *   <li><strong>present</strong> ({@code 2700-B-UPDATE-TCATBAL-REC}, L526-L541):
     *       {@code ADD DALYTRAN-AMT TO TRAN-CAT-BAL} then {@code REWRITE};</li>
     *   <li><strong>absent</strong> ({@code 2700-A-CREATE-TCATBAL-REC}, L503-L524; COBOL
     *       {@code INVALID KEY} &rarr; {@code INITIALIZE} + {@code ADD} amount + {@code WRITE}):
     *       create a new row with balance {@code 0.00 + DALYTRAN-AMT} (scale 2).</li>
     * </ul>
     *
     * <p>The domain type exposes only a {@code protected} no-argument constructor (reserved for the
     * JPA provider), so the create path uses the public all-arguments constructor
     * {@link TransactionCategoryBalance#TransactionCategoryBalance(TransactionCategoryBalanceId,
     * BigDecimal)} to build a populated instance from this package. The entity's {@code @Version}
     * column reproduces the read-update-rewrite integrity (AAP H6). All arithmetic is
     * {@link BigDecimal} at scale 2.</p>
     *
     * @param result the valid posting result whose balance key/amount drive the upsert
     */
    private void updateTranCatBalance(PostingResult result) {
        // MOVE XREF-ACCT-ID / DALYTRAN-TYPE-CD / DALYTRAN-CAT-CD TO FD-TRAN-CAT-KEY (L469-L471).
        TransactionCategoryBalanceId key =
                new TransactionCategoryBalanceId(result.acctId(), result.typeCd(), result.catCd());

        // READ TCATBAL-FILE ... INVALID KEY -> create (L473-L500).
        Optional<TransactionCategoryBalance> existing = tranCatBalanceRepository.findById(key);
        if (existing.isPresent()) {
            // 2700-B-UPDATE-TCATBAL-REC: ADD DALYTRAN-AMT TO TRAN-CAT-BAL then REWRITE (L526-L528).
            TransactionCategoryBalance tcb = existing.get();
            tcb.setBal(tcb.getBal().add(result.tranAmt()));
            tranCatBalanceRepository.save(tcb);
        } else {
            // 2700-A-CREATE-TCATBAL-REC: INITIALIZE (balance 0.00) + ADD amount + WRITE (L503-L510).
            BigDecimal initialBal = BigDecimal.ZERO.setScale(2).add(result.tranAmt());
            TransactionCategoryBalance tcb = new TransactionCategoryBalance(key, initialBal);
            tranCatBalanceRepository.save(tcb);
        }
    }

    /**
     * Updates the account balance and cycle accumulators, reproducing {@code CBTRN02C}
     * {@code 2800-UPDATE-ACCOUNT-REC} (L545-L560). The record is re-read for a managed, versioned
     * entity, then:
     * <ul>
     *   <li>{@code ADD DALYTRAN-AMT TO ACCT-CURR-BAL} &mdash; always (L547);</li>
     *   <li>{@code IF DALYTRAN-AMT >= 0} then {@code ADD DALYTRAN-AMT TO ACCT-CURR-CYC-CREDIT}
     *       {@code ELSE ADD DALYTRAN-AMT TO ACCT-CURR-CYC-DEBIT} (L548-L552). The debit branch
     *       <strong>adds the negative amount</strong> (the debit accumulator moves negative); the
     *       value is never negated, {@code abs()}-ed, or subtracted. {@code signum() >= 0} reproduces
     *       {@code >= 0}, so a zero amount takes the credit branch.</li>
     * </ul>
     *
     * <p><strong>Documented deviation (reason {@code 109}).</strong> The COBOL {@code REWRITE
     * INVALID KEY} sets latent reason {@code 109} and continues; the Java target treats an account
     * that cannot be re-read as a hard integrity error &mdash; a {@link FileStatusException} that
     * fails the step (RC 8) &mdash; rather than a {@link RejectCode}. See the class Javadoc and
     * {@code docs/decision-log.md}. Because the processor already validated the account and this
     * re-read runs in the same short chunk transaction, the branch is defensive. The {@code @Version}
     * column reproduces last-writer integrity via optimistic locking (AAP H6).</p>
     *
     * @param result the valid posting result whose account id and amount drive the update
     */
    private void updateAccount(PostingResult result) {
        // Re-read for a managed, versioned entity. orElseThrow == the 109 INVALID-KEY analog (L555-L558).
        Account acct = accountRepository.findById(result.acctId())
                .orElseThrow(() -> new FileStatusException(
                        FileStatusException.STATUS_RECORD_NOT_FOUND,
                        "Account not found on posting rewrite: " + result.acctId()));

        BigDecimal amt = result.tranAmt();

        // ADD DALYTRAN-AMT TO ACCT-CURR-BAL (L547) -- always.
        acct.setCurrBal(acct.getCurrBal().add(amt));

        // IF DALYTRAN-AMT >= 0 credit ELSE debit (L548-L552); debit ADDs the NEGATIVE amount.
        if (amt.signum() >= 0) {
            acct.setCurrCycCredit(acct.getCurrCycCredit().add(amt));
        } else {
            acct.setCurrCycDebit(acct.getCurrCycDebit().add(amt));
        }

        // REWRITE FD-ACCTFILE-REC (L554); @Version optimistic lock == last-writer integrity (AAP H6).
        accountRepository.save(acct);
    }

    /**
     * Writes one 430-byte {@code DALYREJS} record for a rejected result, reproducing {@code CBTRN02C}
     * {@code 2500-WRITE-REJECT-REC} (L446-L465): the 350-byte {@code DALYTRAN} image
     * ({@code MOVE DALYTRAN-RECORD TO REJECT-TRAN-DATA}) followed by the 80-byte validation trailer
     * ({@code MOVE WS-VALIDATION-TRAILER TO VALIDATION-TRAILER}) of a 4-digit zero-padded reason code
     * and a 76-character space-padded reason description.
     *
     * <p>The record is assembled with a {@link RecordBuilder} whose buffer is initialized to 430
     * spaces, so the 20-byte {@code FILLER} at offset 330 and any short-field padding remain spaces
     * (matching the COBOL fixed-field image). Defensive null handling mirrors the COBOL fixed fields:
     * numeric nulls render as {@code 0}, alphanumeric nulls render as spaces, and a null amount
     * renders as {@code 0.00} with a non-sensitive {@code WARN}. Each record is followed by a literal
     * {@code "\n"}. A write failure sets {@link #ioError} and throws a {@link FileStatusException}
     * (RC 8), mirroring the COBOL {@code 'ERROR WRITING TO REJECTS FILE'} &rarr;
     * {@code 9999-ABEND-PROGRAM} path (L459-L463).</p>
     *
     * @param result the rejected posting result (its {@link PostingResult#rejectCode()} is non-null)
     */
    private void writeRejectRecord(PostingResult result) {
        DailyTransaction src = result.source();
        RejectCode reject = result.rejectCode();

        // Null-safety mirroring COBOL fixed numeric/decimal fields.
        BigDecimal amt = src.getTranAmt();
        if (amt == null) {
            log.warn("Null transaction amount on reject record; defaulting to zero. dalytranId={}",
                    src.getDalytranId());
            amt = BigDecimal.ZERO;
        }
        long catCd = src.getCatCd() == null ? 0L : src.getCatCd().longValue();
        long merchantId = src.getMerchantId() == null ? 0L : src.getMerchantId().longValue();

        // Assemble the 350-byte DALYTRAN image + 80-byte trailer = 430 bytes.
        RecordBuilder builder = FixedWidthCodec.of(DALYREJS_RECORD_LENGTH)
                .put(F_ID, src.getDalytranId())
                .put(F_TYPE_CD, src.getTypeCd())
                .put(F_CAT_CD, catCd)
                .put(F_SOURCE, src.getTranSource())
                .put(F_DESC, src.getTranDesc())
                .put(F_AMT, amt)
                .put(F_MERCHANT_ID, merchantId)
                .put(F_MERCHANT_NAME, src.getMerchantName())
                .put(F_MERCHANT_CITY, src.getMerchantCity())
                .put(F_MERCHANT_ZIP, src.getMerchantZip())
                .put(F_CARD_NUM, src.getCardNum())
                .put(F_ORIG_TS, src.getOrigTs())
                .put(F_PROC_TS, src.getProcTs())
                // FILLER at offset 330 (20 bytes) stays spaces from the builder initialization.
                .put(F_REASON_CODE, reject.getCode())
                .put(F_REASON_DESC, reject.getDescription());

        String line = builder.build();
        // Defensive: the RecordBuilder guarantees exactly DALYREJS_RECORD_LENGTH characters.
        if (line.length() != DALYREJS_RECORD_LENGTH) {
            throw new FileStatusException(FileStatusException.STATUS_OK,
                    "Assembled reject record length " + line.length()
                            + " does not match expected " + DALYREJS_RECORD_LENGTH);
        }

        try {
            rejectWriter.write(line);
            rejectWriter.write(RECORD_DELIMITER);
        } catch (IOException e) {
            ioError = true;
            log.error("Error writing to rejects file", e);
            throw new FileStatusException(
                    FileStatusException.STATUS_OK, "Error writing to rejects file", e);
        }
    }

    /**
     * Closes the reject file and maps the run outcome to a batch return code, reproducing the close
     * and return-code logic at the end of the {@code CBTRN02C} MAIN section (L221-L231). Spring Batch
     * invokes this automatically because the bean is the step's writer.
     *
     * <p>The reject writer is flushed and closed (a close failure sets {@link #ioError}); a null
     * writer (open failed) is guarded so no {@link NullPointerException} occurs. The processed and
     * reject counts are promoted to the step {@code ExecutionContext} for observability/restart and
     * logged at {@code INFO} (counts only), mirroring the COBOL {@code DISPLAY} lines (L227-L228).</p>
     *
     * <p>Return-code mapping (MAIN L229-L231):</p>
     * <ul>
     *   <li>{@link #ioError} or {@link BatchStatus#FAILED} &rarr; {@link ExitStatus#FAILED} (RC 8);</li>
     *   <li>otherwise {@link #rejectCount} {@code > 0} &rarr; {@code new ExitStatus(
     *       "COMPLETED_WITH_REJECTS")} (RC 4; {@code IF WS-REJECT-COUNT > 0 MOVE 4 TO RETURN-CODE}).
     *       The literal is a coordination contract with the parent
     *       {@code DailyTransactionPostingJob} exit-code mapping and must not be changed without
     *       updating the job;</li>
     *   <li>otherwise &rarr; {@link ExitStatus#COMPLETED} (RC 0).</li>
     * </ul>
     *
     * @param stepExecution the current step execution (supplied by Spring Batch)
     * @return the mapped {@link ExitStatus}
     */
    @Override
    public ExitStatus afterStep(StepExecution stepExecution) {
        // 9300-DALYREJS-CLOSE: flush and close (null-guarded so a failed open does not NPE).
        if (rejectWriter != null) {
            try {
                rejectWriter.flush();
                rejectWriter.close();
            } catch (IOException e) {
                ioError = true;
                log.error("Error closing rejects file", e);
            }
        }

        // Promote counts for observability/restart.
        stepExecution.getExecutionContext().putLong("transactionCount", transactionCount);
        stepExecution.getExecutionContext().putLong("rejectCount", rejectCount);

        // DISPLAY 'TRANSACTIONS PROCESSED :' / 'TRANSACTIONS REJECTED :' (L227-L228) -- counts only.
        log.info("Transactions processed: {}", transactionCount);
        log.info("Transactions rejected: {}", rejectCount);

        // Return-code mapping (L229-L231).
        if (ioError || stepExecution.getStatus() == BatchStatus.FAILED) {
            return ExitStatus.FAILED;                       // RC 8
        }
        if (rejectCount > 0) {
            return new ExitStatus("COMPLETED_WITH_REJECTS"); // RC 4
        }
        return ExitStatus.COMPLETED;                        // RC 0
    }
}
