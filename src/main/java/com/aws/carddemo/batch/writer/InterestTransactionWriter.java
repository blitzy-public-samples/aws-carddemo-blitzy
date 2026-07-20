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
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.EnumSet;

import com.aws.carddemo.batch.processor.InterestCalculationProcessor;
import com.aws.carddemo.common.util.FixedWidthCodec;
import com.aws.carddemo.common.util.FixedWidthCodec.FieldDef;
import com.aws.carddemo.common.util.FixedWidthCodec.RecordBuilder;
import com.aws.carddemo.domain.Account;
import com.aws.carddemo.domain.Transaction;
import com.aws.carddemo.exception.FileStatusException;
import com.aws.carddemo.repository.AccountRepository;
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
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Spring Batch {@link ItemWriter} that reproduces the <strong>output side</strong> of the legacy
 * COBOL interest calculator {@code legacy/cbl/CBACT04C.cbl} (source {@code app/cbl/CBACT04C.cbl}),
 * the batch program triggered by {@code INTCALC.jcl}
 * ({@code EXEC PGM=CBACT04C,PARM='2022071800'}). It is the writer half of
 * {@code InterestCalculationJob}, wired into {@code interestCalculationStep} with
 * <strong>chunk size&nbsp;=&nbsp;1</strong>, and it consumes the
 * {@link InterestCalculationProcessor.InterestResult} items emitted by the sibling
 * {@code processor/} bean. Most items carry an interest transaction to serialize; a zero-rate row
 * arrives as a <em>finalize-only marker</em> ({@code interestTransaction == null},
 * {@code monthlyInterest == 0.00}) that still drives the account control break and finalize but
 * writes no {@code SYSTRAN} record (QA finding F-P5-A). Only an absent-rate row is filtered to
 * {@code null} by the processor and never reaches this writer.
 *
 * <h2>Two outputs (the two CBACT04C output DDs from {@code INTCALC.jcl})</h2>
 * <ol>
 *   <li><strong>Interest transactions &rarr; a 350-byte fixed-width file.</strong> This mirrors the
 *       {@code TRANSACT} DD ({@code DSN=...SYSTRAN(+1)}, {@code DCB=(RECFM=F,LRECL=350)}) written by
 *       {@code 1300-B-WRITE-TX} ({@code WRITE FD-TRANFILE-REC}, CBACT04C L473-515). The file is the
 *       {@code SYSTRAN} analog: downstream {@code TransactionCombineJob} (legacy {@code COMBTRAN.jcl})
 *       sorts {@code SYSTRAN(0)} + {@code TRANSACT.BKUP(0)} by {@code TRAN-ID} and REPROs the combined
 *       file into the transaction master. Its output directory/name are therefore
 *       <em>configuration-driven</em> and must be coordinated with that job's reader via shared
 *       configuration (see {@link #InterestTransactionWriter}).</li>
 *   <li><strong>Account balance rewrite &rarr; the database.</strong> This mirrors the {@code ACCTFILE}
 *       DD ({@code DSN=...ACCTDATA.VSAM.KSDS}, {@code DISP=SHR}, updated in place) rewritten by
 *       {@code 1050-UPDATE-ACCOUNT} (CBACT04C L350-370).</li>
 * </ol>
 *
 * <h2>Restart / re-run safety (QA findings F-P5-D and F-P6-A)</h2>
 * <p>The {@code SYSTRAN} file is written to a hidden, owner-only staging file and only
 * <strong>atomically published</strong> to its final name on a clean run (deleted on any failure), so
 * a restart or failure can never leave a partial final-named file behind. Because the reader re-reads
 * the full input on restart ({@code TransactionCategoryBalanceItemReader.setSaveState(false)}), the
 * staging file always holds the complete output. The account balance posting is made
 * <strong>idempotent per cycle</strong> by {@link AccountRepository#applyInterestForCycle(Long,
 * java.math.BigDecimal, String)}: it applies interest only when the account's
 * {@code last_interest_cycle} marker differs from the {@code parmDate} being posted, so a re-run or a
 * mid-run restart never double-applies interest (the correct outcome is {@code curr_bal 37.51 /
 * version 1}, not {@code 75.02 / version 2}). Together these make the whole job safely
 * restartable/re-runnable for a given cycle.</p>
 *
 * <p><strong>Never persist the interest transaction to the {@code transaction} table.</strong> Doing
 * so would double-load it, because the combine job later loads the {@code SYSTRAN} file into the
 * transaction master. The processor already <em>built</em> the interest {@link Transaction} (type
 * {@code 01}, category {@code 5}, source {@code System}, description, amount = monthly interest, card
 * number, timestamps); this writer only <em>serializes it to the 350-byte file</em> and
 * <em>accumulates + rewrites the account</em>.</p>
 *
 * <h2>Control-break algorithm (CBACT04C main loop, L188-222)</h2>
 * <p>The reader delivers {@code TransactionCategoryBalance} rows ordered by account, so all interest
 * rows for one account arrive contiguously. For each result, in this exact order:</p>
 * <ol>
 *   <li><strong>Control break first</strong> ({@code IF TRANCAT-ACCT-ID NOT= WS-LAST-ACCT-NUM},
 *       L194-206):
 *       <ul>
 *         <li>First interest row overall ({@code WS-FIRST-TIME='Y'} path, L197-198): remember the
 *             account; do <em>not</em> finalize (there is no previous account).</li>
 *         <li>Account changed (non-first-time, L195-196): finalize the <em>previous</em> account via
 *             {@code 1050-UPDATE-ACCOUNT}, then reset the running total to {@code 0.00} (L200) and
 *             remember the new account (L201).</li>
 *         <li>Same account: no break.</li>
 *       </ul>
 *   </li>
 *   <li><strong>Accumulate</strong> ({@code ADD WS-MONTHLY-INT TO WS-TOTAL-INT}, L467).</li>
 *   <li><strong>Write</strong> the interest transaction to the 350-byte file
 *       ({@code 1300-B-WRITE-TX}, L500).</li>
 * </ol>
 *
 * <h2>Transaction context (chunk size = 1)</h2>
 * <p>Because the step uses chunk size&nbsp;1, every {@link #write(Chunk)} call runs inside its own
 * Spring Batch chunk transaction; the control-break {@code finalizeAccount(...)} therefore
 * participates in that transaction. {@link #write(Chunk)} is deliberately <strong>not</strong>
 * annotated {@code @Transactional} &mdash; it relies on the chunk transaction. The 350-byte file
 * write is plain file I/O, intentionally <em>outside</em> the database transaction.</p>
 *
 * <h2>Documented deviation &mdash; the last account is finalized in {@link #afterStep(StepExecution)}</h2>
 * <p>In CBACT04C the end-of-file branch {@code ELSE PERFORM 1050-UPDATE-ACCOUNT} (L219-220) is
 * <strong>structurally unreachable</strong> under {@code PERFORM UNTIL END-OF-FILE = 'Y'} (default
 * {@code TEST BEFORE}): at loop-body entry {@code END-OF-FILE} is always {@code 'N'}, so the outer
 * {@code IF} is always true and the {@code ELSE} never executes. Consequently the legacy program
 * <strong>never applies {@code 1050-UPDATE-ACCOUNT} to the last account</strong> &mdash; the last
 * account's interest transactions are still written to the file, but its {@code ACCT-CURR-BAL} is not
 * updated. This Java target <strong>intentionally finalizes the last account</strong> in
 * {@link #afterStep(StepExecution)}, a deliberate behavioral correction/deviation recorded in
 * {@code docs/decision-log.md} as decision <strong>D32</strong> (which also records how to revert to
 * strict COBOL behavior if ever required &mdash; by removing the {@code afterStep} finalization).
 * Golden-file fixtures for the interest job's account balances must reflect this corrected behavior.
 * This class only references that decision; it does not own or edit the decision log.</p>
 *
 * <h2>Read-update-rewrite integrity (AAP H6)</h2>
 * <p>{@code 1050-UPDATE-ACCOUNT} is a COBOL READ-UPDATE-REWRITE cycle. In the relational target that
 * integrity is provided by the {@link Account} entity's {@code @Version} optimistic lock inside the
 * active transaction &mdash; a documented improvement (AAP hotspot H6), not a behavioral change.</p>
 *
 * <h2>Monetary discipline, configuration, and security</h2>
 * <p>All monetary values are {@link BigDecimal} at scale&nbsp;2; {@code double}/{@code float} are
 * never used. The output location is fully configuration-driven (no hardcoded path or credential).
 * The writer logs only non-sensitive operational metadata (record/account counts and account ids at
 * {@code INFO}/{@code DEBUG}); it never logs a full card number, CVV, SSN, password, or the assembled
 * record.</p>
 *
 * <h2>Concurrency &mdash; single launch per JVM</h2>
 * <p>This writer is a singleton {@code @Component} holding per-run mutable state (the record counter,
 * the last-account accumulator used for the end-of-run finalization, and the open output stream) that
 * is reset in {@code beforeStep}. Running two {@code InterestCalculationJob} executions concurrently in
 * the same JVM is therefore <strong>not</strong> supported. This matches the operating model in which
 * each batch job is launched in its own process by the CI/CD scheduler (AAP &sect;0.4.4), so the
 * constraint is faithful operational parity rather than a defect. The rationale and the
 * {@code @StepScope} alternative are recorded in decision log D37.</p>
 *
 * @see InterestCalculationProcessor
 * @see FixedWidthCodec
 * @see Account
 * @see Transaction
 * @see ItemWriter
 * @see StepExecutionListener
 * @see <a href="http://www.apache.org/licenses/LICENSE-2.0">Apache License 2.0</a>
 */
@Component
public class InterestTransactionWriter
        implements ItemWriter<InterestCalculationProcessor.InterestResult>, StepExecutionListener {

    /** SLF4J logger; emits only non-sensitive operational metadata (counts and account ids). */
    private static final Logger log = LoggerFactory.getLogger(InterestTransactionWriter.class);

    /** Fixed transaction record length in characters/bytes ({@code CVTRA05Y.cpy} {@code RECLN}). */
    private static final int RECORD_LENGTH = 350;

    /**
     * Record delimiter appended after each 350-character record. A literal line feed (never
     * {@link System#lineSeparator()}) keeps golden-file fixtures deterministic across platforms; the
     * authoritative contract is the 350-byte record itself.
     */
    private static final String RECORD_DELIMITER = "\n";

    /** Scale-2 zero, reused for cycle resets and the running-total initial value. */
    private static final BigDecimal ZERO_MONEY = BigDecimal.ZERO.setScale(2);

    /**
     * Suffix of the owner-only staging file the step writes the {@code SYSTRAN} interest records to
     * before the completed file is atomically published to its final name. Writing to a distinct
     * hidden staging file (rather than the final path) means a restart or failure can never leave a
     * partial final-named {@code SYSTRAN} file behind; the whole file is rebuilt and published only on
     * a clean run (QA finding F-P5-D).
     */
    private static final String PART_SUFFIX = ".part";

    /** Owner read/write only ({@code 0600}) staging-file permissions on POSIX filesystems. */
    private static final EnumSet<PosixFilePermission> OWNER_ONLY_PERMISSIONS =
            EnumSet.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE);

    /**
     * Job-parameter name for the CBACT04C {@code PARM} date (the interest cycle). Matches the key the
     * {@code InterestCalculationJob} validates and the {@code InterestCalculationProcessor} late-binds.
     */
    private static final String PARAM_PARM_DATE = "parmDate";

    // ------------------------------------------------------------------------
    // 350-byte TRAN-RECORD field descriptors (copybook CVTRA05Y.cpy).
    // Offsets are zero-based and contiguous and sum to 350; the trailing FILLER PIC X(20) at
    // offset 330 needs no descriptor because RecordBuilder starts all-spaces.
    // ------------------------------------------------------------------------

    /** {@code TRAN-ID PIC X(16)} at offset 0. */
    private static final FieldDef TRAN_ID = FieldDef.alphanumeric("TRAN-ID", 0, 16);

    /** {@code TRAN-TYPE-CD PIC X(02)} at offset 16. */
    private static final FieldDef TRAN_TYPE_CD = FieldDef.alphanumeric("TRAN-TYPE-CD", 16, 2);

    /** {@code TRAN-CAT-CD PIC 9(04)} at offset 18. */
    private static final FieldDef TRAN_CAT_CD = FieldDef.numeric("TRAN-CAT-CD", 18, 4);

    /** {@code TRAN-SOURCE PIC X(10)} at offset 22. */
    private static final FieldDef TRAN_SOURCE = FieldDef.alphanumeric("TRAN-SOURCE", 22, 10);

    /** {@code TRAN-DESC PIC X(100)} at offset 32. */
    private static final FieldDef TRAN_DESC = FieldDef.alphanumeric("TRAN-DESC", 32, 100);

    /** {@code TRAN-AMT PIC S9(09)V99} at offset 132 (length 11, scale 2, overpunch-signed). */
    private static final FieldDef TRAN_AMT = FieldDef.signedDecimal("TRAN-AMT", 132, 11, 2);

    /** {@code TRAN-MERCHANT-ID PIC 9(09)} at offset 143. */
    private static final FieldDef TRAN_MERCHANT_ID = FieldDef.numeric("TRAN-MERCHANT-ID", 143, 9);

    /** {@code TRAN-MERCHANT-NAME PIC X(50)} at offset 152. */
    private static final FieldDef TRAN_MERCHANT_NAME =
            FieldDef.alphanumeric("TRAN-MERCHANT-NAME", 152, 50);

    /** {@code TRAN-MERCHANT-CITY PIC X(50)} at offset 202. */
    private static final FieldDef TRAN_MERCHANT_CITY =
            FieldDef.alphanumeric("TRAN-MERCHANT-CITY", 202, 50);

    /** {@code TRAN-MERCHANT-ZIP PIC X(10)} at offset 252. */
    private static final FieldDef TRAN_MERCHANT_ZIP =
            FieldDef.alphanumeric("TRAN-MERCHANT-ZIP", 252, 10);

    /** {@code TRAN-CARD-NUM PIC X(16)} at offset 262. */
    private static final FieldDef TRAN_CARD_NUM = FieldDef.alphanumeric("TRAN-CARD-NUM", 262, 16);

    /** {@code TRAN-ORIG-TS PIC X(26)} at offset 278. */
    private static final FieldDef TRAN_ORIG_TS = FieldDef.alphanumeric("TRAN-ORIG-TS", 278, 26);

    /** {@code TRAN-PROC-TS PIC X(26)} at offset 304. */
    private static final FieldDef TRAN_PROC_TS = FieldDef.alphanumeric("TRAN-PROC-TS", 304, 26);

    /** Repository for the {@code 1050-UPDATE-ACCOUNT} read-update-rewrite of the account master. */
    private final AccountRepository accountRepository;

    /**
     * Programmatic transaction helper used to run {@code 1050-UPDATE-ACCOUNT} for the <em>last</em>
     * account inside {@link #afterStep(StepExecution)}, where the Spring Batch chunk transaction is
     * no longer active. Built from the injected {@link PlatformTransactionManager}.
     */
    private final TransactionTemplate transactionTemplate;

    /**
     * Configured output directory for the {@code SYSTRAN} interest-transaction file (safe relative
     * default {@code ./target/batch}). Coordinated with {@code TransactionCombineJob}'s reader via
     * shared configuration.
     */
    private final String outputDirectory;

    /**
     * Configured output file name for the {@code SYSTRAN} interest-transaction file (default
     * {@code SYSTRAN.dat}). Coordinated with {@code TransactionCombineJob}'s reader via shared
     * configuration.
     */
    private final String outputFileName;

    /**
     * Control-break key: the account id currently being accumulated. Mirrors COBOL
     * {@code WS-LAST-ACCT-NUM}; {@code null} means "first time" ({@code WS-FIRST-TIME='Y'}) and, after
     * {@link #afterStep(StepExecution)}, acts as the idempotent guard preventing a second finalize.
     */
    private Long currentAcctId;

    /**
     * Running per-account interest total (scale&nbsp;2). Mirrors COBOL {@code WS-TOTAL-INT}; reset to
     * {@code 0.00} on each account control break (L200).
     */
    private BigDecimal totalInterest;

    /** Open output stream over the staging file for the current step; {@code null} outside an active step. */
    private BufferedWriter txnWriter;

    /**
     * Owner-only staging file the step writes the {@code SYSTRAN} records to during the run; atomically
     * renamed to {@link #outputFile} on success or deleted on failure. {@code null} outside an active
     * step (QA finding F-P5-D).
     */
    private Path tempFile;

    /** Resolved final {@code SYSTRAN} output path, retained for the completion log and the atomic publish. */
    private Path outputFile;

    /**
     * The 10-character interest cycle ({@code parmDate}, for example {@code 2022071800}) read from the
     * step's job parameters in {@link #beforeStep(StepExecution)}. Passed to
     * {@code AccountRepository.applyInterestForCycle} so the per-account balance posting is idempotent
     * per cycle (QA findings F-P6-A / F-P5-D). {@code null} outside an active step.
     */
    private String parmDate;

    /** Sticky I/O-error flag; when set, {@link #afterStep(StepExecution)} maps to return code&nbsp;8. */
    private boolean ioError;

    /** Count of interest records written during the current step, for the completion log line. */
    private long recordsWritten;

    /** Count of accounts finalized (balance rewritten) during the current step, for the log line. */
    private long accountsFinalized;

    /**
     * Creates the writer with constructor injection only (never field injection) and a
     * configuration-driven, relative-by-default output location.
     *
     * <p>{@link FixedWidthCodec} is a static-only utility and is therefore called statically, never
     * injected. The {@link TransactionTemplate} is built here from the injected
     * {@link PlatformTransactionManager} so the last-account finalize in
     * {@link #afterStep(StepExecution)} can run in its own transaction.</p>
     *
     * @param accountRepository  repository for the account read-update-rewrite; never {@code null}
     * @param transactionManager the platform transaction manager used to build the
     *                           {@link TransactionTemplate}; never {@code null}
     * @param outputDirectory    the directory for the {@code SYSTRAN} file; bound from
     *                           {@code carddemo.batch.interest.output-directory} (default
     *                           {@code ./target/batch})
     * @param outputFileName     the {@code SYSTRAN} file name; bound from
     *                           {@code carddemo.batch.interest.output-file} (default
     *                           {@code SYSTRAN.dat})
     */
    public InterestTransactionWriter(
            AccountRepository accountRepository,
            PlatformTransactionManager transactionManager,
            @Value("${carddemo.batch.interest.output-directory:./target/batch}") String outputDirectory,
            @Value("${carddemo.batch.interest.output-file:SYSTRAN.dat}") String outputFileName) {
        this.accountRepository = accountRepository;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
        this.outputDirectory = outputDirectory;
        this.outputFileName = outputFileName;
    }

    /**
     * Opens the {@code SYSTRAN} interest-transaction <em>staging</em> file for the step, captures the
     * interest cycle ({@code parmDate}), and resets all per-run control-break state, reproducing
     * {@code 0400-TRANFILE-OPEN} ({@code OPEN OUTPUT}, CBACT04C L307).
     *
     * <p>The step writes to a hidden, owner-only staging file ({@link #tempFile}) rather than the
     * final {@link #outputFile}; the completed staging file is atomically published to the final name
     * in {@link #afterStep(StepExecution)} on a clean run, or deleted on any failure. Because the
     * reader re-reads the full input on restart
     * ({@code TransactionCategoryBalanceItemReader.setSaveState(false)}), the staging file always holds
     * the complete {@code SYSTRAN} output, so a restart or failure can never leave a partial
     * final-named file behind (QA finding F-P5-D). The staging file is opened <em>unconditionally</em>
     * in truncate/create mode, so a run that produces no interest rows still yields a valid, empty
     * published file. It is opened with {@link StandardCharsets#ISO_8859_1} so that each 350-character
     * record serializes to exactly 350 bytes (preserving the {@code LRECL=350} contract).</p>
     *
     * <p>The interest cycle {@code parmDate} is read from the step's job parameters (the
     * {@code InterestCalculationJob} validator guarantees it is present and non-blank) and passed to
     * the idempotent per-account posting in {@link #finalizeAccount(Long, BigDecimal)} (F-P6-A).
     * {@link StepExecutionListener#beforeStep} cannot declare a checked exception, so an
     * {@link IOException} while creating the directory or opening the staging file is rethrown as an
     * (unchecked) {@link FileStatusException}, which aborts the step and job &mdash; the batch
     * return-code-8 analog of the COBOL open-failure abend.</p>
     *
     * @param stepExecution the current step execution; never {@code null}
     * @throws FileStatusException if the output directory cannot be created or the staging file cannot
     *                             be opened for writing
     */
    @Override
    public void beforeStep(StepExecution stepExecution) {
        // Reset per-run control-break state (mirrors WS-LAST-ACCT-NUM, WS-TOTAL-INT, WS-FIRST-TIME).
        this.currentAcctId = null;
        this.totalInterest = ZERO_MONEY;
        this.ioError = false;
        this.recordsWritten = 0L;
        this.accountsFinalized = 0L;
        this.txnWriter = null;
        this.tempFile = null;

        // Capture the interest cycle for the idempotent per-account posting (F-P6-A). The job's
        // JobParametersValidator guarantees a non-blank parmDate, so this is a defensive read.
        this.parmDate = stepExecution.getJobParameters().getString(PARAM_PARM_DATE);

        final Path dir = Path.of(outputDirectory);
        this.outputFile = dir.resolve(outputFileName);
        // Hidden, owner-only staging file in the same directory; atomically published in afterStep.
        final Path staging = dir.resolve("." + outputFileName + PART_SUFFIX);
        try {
            Files.createDirectories(dir);
            this.txnWriter = openOwnerOnlyStagingWriter(staging);
            this.tempFile = staging;
        } catch (IOException ex) {
            this.ioError = true;
            deleteQuietly(staging);
            this.tempFile = null;
            // Fail fast (batch RC 8), mirroring the COBOL open-failure abend. The path carries no PII.
            throw new FileStatusException(FileStatusException.STATUS_OK,
                    "Error opening interest transaction file " + staging, ex);
        }
        log.info("Interest transaction staging opened: {} (350-byte fixed-width records, ISO-8859-1); "
                + "will be atomically published to {} on success.", staging, outputFile);
    }

    /**
     * Creates the owner-only staging file and returns a buffered writer over it. On a POSIX filesystem
     * the file is created with {@code 0600} permissions; on a non-POSIX filesystem it is created
     * without them. Any pre-existing staging file (from an aborted prior attempt or a hard JVM crash)
     * is removed first so the fresh run always starts from an empty file.
     *
     * @param staging the staging file path to create and open; never {@code null}
     * @return a buffered {@link BufferedWriter} over the freshly created staging file
     * @throws IOException if the staging file cannot be created or opened
     */
    private static BufferedWriter openOwnerOnlyStagingWriter(final Path staging) throws IOException {
        Files.deleteIfExists(staging);
        try {
            Files.createFile(staging, PosixFilePermissions.asFileAttribute(OWNER_ONLY_PERMISSIONS));
        } catch (UnsupportedOperationException nonPosixFileSystem) {
            Files.createFile(staging);
        }
        return Files.newBufferedWriter(staging, StandardCharsets.ISO_8859_1,
                StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING);
    }

    /**
     * Processes each {@link InterestCalculationProcessor.InterestResult} in the chunk, reproducing the
     * per-row body of the CBACT04C main loop (L188-222) in this exact order: control break, then
     * accumulate, then write the 350-byte interest record.
     *
     * <p>Because the step uses chunk size&nbsp;1, this call runs inside its own Spring Batch chunk
     * transaction; the control-break {@code finalizeAccount(...)} therefore participates in that
     * transaction. This method is intentionally <strong>not</strong> annotated {@code @Transactional}
     * &mdash; it relies on the chunk transaction. The 350-byte file write is plain file I/O,
     * intentionally outside the database transaction.</p>
     *
     * <p><strong>Finalize-only markers (F-P5-A).</strong> A result whose {@code interestTransaction}
     * is {@code null} is a zero-rate finalize-only marker: it still drives the account control break
     * and accumulation (adding {@code 0.00}) so the account is finalized, but the 350-byte file write
     * in step&nbsp;(3) is skipped because there is no interest transaction to serialize.</p>
     *
     * @param chunk the chunk of interest results to write; never {@code null}
     * @throws Exception           if writing the 350-byte record to the underlying stream fails
     *                             (surfaced as a {@link FileStatusException})
     * @throws FileStatusException if the previous account cannot be found on a control break
     */
    @Override
    public void write(Chunk<? extends InterestCalculationProcessor.InterestResult> chunk)
            throws Exception {
        for (final InterestCalculationProcessor.InterestResult result : chunk.getItems()) {
            // (1) Control break FIRST (CBACT04C L194-206).
            if (currentAcctId == null) {
                // First interest row overall (WS-FIRST-TIME='Y', L197-198): remember the account and
                // leave the running total at 0.00; there is no previous account to finalize.
                currentAcctId = result.acctId();
            } else if (!result.acctId().equals(currentAcctId)) {
                // Account changed (L194, non-first-time): finalize the PREVIOUS account inside the
                // active chunk transaction, then reset the running total (L200) and remember the new
                // account (L201).
                finalizeAccount(currentAcctId, totalInterest);
                totalInterest = ZERO_MONEY;
                currentAcctId = result.acctId();
            }
            // (2) Accumulate the monthly interest (L467: ADD WS-MONTHLY-INT TO WS-TOTAL-INT). A
            // finalize-only marker contributes 0.00, so accumulation is unconditional and harmless.
            totalInterest = totalInterest.add(result.monthlyInterest());
            // (3) Write the interest transaction to the 350-byte file (1300-B-WRITE-TX, L500). A
            // zero-rate finalize-only marker carries no transaction (F-P5-A): the COBOL zero-rate
            // guard writes nothing, so skip the file write while still finalizing the account above.
            final Transaction interestTransaction = result.interestTransaction();
            if (interestTransaction != null) {
                writeInterestTransaction(interestTransaction);
            }
        }
    }

    /**
     * Serializes one interest {@link Transaction} to its 350-byte fixed-width record and appends it,
     * followed by a single line feed, to the open output stream. Reproduces the {@code WRITE
     * FD-TRANFILE-REC} of {@code 1300-B-WRITE-TX} (CBACT04C L500).
     *
     * @param txn the interest transaction to serialize and write; never {@code null}
     * @throws FileStatusException if the underlying stream write fails (batch RC 8 analog of the
     *                             COBOL write-failure abend)
     */
    private void writeInterestTransaction(final Transaction txn) {
        final String line = serialize(txn);
        // Defensive contract check: the assembled record must be exactly 350 characters.
        if (line.length() != RECORD_LENGTH) {
            throw new IllegalStateException(
                    "Assembled interest record width " + line.length()
                            + " does not match the " + RECORD_LENGTH + "-byte contract");
        }
        try {
            txnWriter.write(line);
            txnWriter.write(RECORD_DELIMITER);
            recordsWritten++;
        } catch (IOException ex) {
            ioError = true;
            // Never log the assembled record or any PII; log only the file name and the cause.
            log.error("Error writing interest transaction record to {}", outputFileName, ex);
            throw new FileStatusException(FileStatusException.STATUS_OK,
                    "Error writing interest transaction file", ex);
        }
    }

    /**
     * Assembles the byte-exact 350-character record image for a single interest transaction using the
     * copybook {@code CVTRA05Y.cpy} layout.
     *
     * <p>A {@link RecordBuilder} of length {@value #RECORD_LENGTH} is initialized to all spaces, so
     * the trailing {@code FILLER PIC X(20)} at offset 330 remains spaces automatically. Null-safety
     * mirrors the COBOL fixed-width semantics: null numeric fields ({@code catCd},
     * {@code tranMerchantId}) are written as zero, null alphanumeric fields are space-filled by the
     * codec, and a null monetary amount is defensively encoded as {@code 0.00} (with a WARN log, since
     * the processor should always populate the amount).</p>
     *
     * @param txn the interest transaction to serialize; never {@code null}
     * @return a string of exactly {@value #RECORD_LENGTH} characters
     */
    private String serialize(final Transaction txn) {
        BigDecimal amount = txn.getTranAmt();
        if (amount == null) {
            log.warn("Interest transaction {} has a null amount; encoding 0.00 (data anomaly).",
                    txn.getTranId());
            amount = BigDecimal.ZERO;
        }

        final Integer catCd = txn.getCatCd();
        final Long merchantId = txn.getTranMerchantId();

        final RecordBuilder record = FixedWidthCodec.of(RECORD_LENGTH)
                .put(TRAN_ID, txn.getTranId())
                .put(TRAN_TYPE_CD, txn.getTypeCd())
                .put(TRAN_CAT_CD, catCd == null ? 0L : catCd.longValue())
                .put(TRAN_SOURCE, txn.getTranSource())
                .put(TRAN_DESC, txn.getTranDesc())
                .put(TRAN_AMT, amount)
                .put(TRAN_MERCHANT_ID, merchantId == null ? 0L : merchantId.longValue())
                .put(TRAN_MERCHANT_NAME, txn.getTranMerchantName())
                .put(TRAN_MERCHANT_CITY, txn.getTranMerchantCity())
                .put(TRAN_MERCHANT_ZIP, txn.getTranMerchantZip())
                .put(TRAN_CARD_NUM, txn.getCardNum())
                .put(TRAN_ORIG_TS, txn.getOrigTs())
                .put(TRAN_PROC_TS, txn.getProcTs());

        return record.build();
    }

    /**
     * Applies the accumulated per-account interest to the account master, reproducing
     * {@code 1050-UPDATE-ACCOUNT} (CBACT04C L350-370): add the running interest total to
     * {@code ACCT-CURR-BAL} and zero both cycle amounts, then rewrite the record.
     *
     * <p><strong>Idempotent per-cycle posting (F-P6-A / F-P5-D).</strong> The update is performed by
     * the conditional bulk statement {@link AccountRepository#applyInterestForCycle(Long, BigDecimal,
     * String)}, which adds the accumulated interest to {@code ACCT-CURR-BAL}, zeroes the cycle
     * credit/debit, stamps {@code last_interest_cycle = parmDate}, and advances the {@code @Version}
     * &mdash; but only when this cycle has not already been posted to the account. It returns the
     * number of rows updated: {@code 1} when applied (the normal case), {@code 0} when the cycle was
     * already posted (an idempotent no-op on a re-run or restart). Because the compare-and-set is a
     * single atomic statement, the balance mutation is applied exactly once per {@code (account,
     * cycle)} even across job re-runs, restarts, or concurrent postings. Only {@link BigDecimal}
     * arithmetic is used; {@code double}/{@code float} are never used.</p>
     *
     * <p>A {@code 0}-row result on the <em>first</em> posting would mean the account row is genuinely
     * missing (a data corruption), which the processor's {@code 1100-GET-ACCT-DATA} lookup would
     * already have caught for every row. To preserve the legacy hard-file-error abend for that case,
     * a {@code 0}-row result is only treated as an idempotent skip when the account actually exists;
     * otherwise a {@link FileStatusException} (batch RC&nbsp;8) is thrown, exactly as the COBOL
     * {@code 1050-UPDATE-ACCOUNT} INVALID-KEY path does.</p>
     *
     * <p>This helper assumes an active transaction: the Spring Batch chunk transaction when called
     * from {@link #write(Chunk)} on a control break, and the {@link TransactionTemplate} when called
     * from {@link #afterStep(StepExecution)} for the last account.</p>
     *
     * @param acctId                  the account to update; never {@code null}
     * @param totalInterestForAccount the accumulated monthly interest to add (scale&nbsp;2); never
     *                                {@code null}
     * @throws FileStatusException if the account cannot be found (a genuine miss, not an idempotent skip)
     */
    private void finalizeAccount(final Long acctId, final BigDecimal totalInterestForAccount) {
        // 1050-UPDATE-ACCOUNT as an idempotent conditional UPDATE: applies at most once per cycle.
        final int rowsUpdated =
                accountRepository.applyInterestForCycle(acctId, totalInterestForAccount, parmDate);
        if (rowsUpdated > 0) {
            accountsFinalized++;
            log.debug("Finalized interest for account {} (cycle {}; cycle credit/debit reset to 0.00).",
                    acctId, parmDate);
            return;
        }
        // Zero rows: normally the cycle was already posted (idempotent re-run/restart, F-P6-A). But
        // preserve the COBOL missing-account abend: only skip when the account genuinely exists.
        if (!accountRepository.existsById(acctId)) {
            throw new FileStatusException(FileStatusException.STATUS_RECORD_NOT_FOUND,
                    "Account not found for interest update: " + acctId);
        }
        log.debug("Interest cycle {} already applied to account {}; skipping idempotent re-apply.",
                parmDate, acctId);
    }

    /**
     * Finalizes the <strong>last</strong> account, closes the output stream, and maps the outcome to a
     * batch return code.
     *
     * <p><strong>Last-account deviation.</strong> As documented on the class, the legacy CBACT04C
     * EOF branch {@code ELSE PERFORM 1050-UPDATE-ACCOUNT} (L219-220) is dead code, so the COBOL never
     * updates the last account's balance. This target intentionally finalizes the last account here.
     * Because {@code afterStep} runs <em>outside</em> the Spring Batch chunk transaction, the DB work
     * is wrapped in the {@link TransactionTemplate}. Setting {@link #currentAcctId} to {@code null}
     * afterward is an idempotent guard that prevents a second finalize if {@code afterStep} is invoked
     * again. This deviation is recorded in {@code docs/decision-log.md}.</p>
     *
     * <p>The staging stream is then flushed and closed under a {@code null}-guard (the open may have
     * failed). A close failure is logged (no PII) and sets the sticky I/O-error flag. The completed
     * staging file is then either <strong>atomically published</strong> to the final {@code SYSTRAN}
     * name (on a clean run) or <strong>deleted</strong> (on any failure), so a restart or failure can
     * never leave a partial final-named file behind (QA finding F-P5-D). If the last-account finalize
     * itself throws (a genuine missing account), the staging file is discarded and the exception
     * propagates so the step is marked {@link BatchStatus#FAILED}, preserving the COBOL abend.</p>
     *
     * <p>Finally, the return code is mapped: the interest job has <em>no reject path</em>, so a sticky
     * I/O error, a failed publish, or a {@link BatchStatus#FAILED} step maps to {@link ExitStatus#FAILED}
     * (RC&nbsp;8); otherwise the step maps to {@link ExitStatus#COMPLETED} (RC&nbsp;0). All per-step
     * file state is cleared so the singleton bean carries no state between runs.</p>
     *
     * @param stepExecution the current step execution; never {@code null}
     * @return {@link ExitStatus#FAILED} if an I/O error occurred, the publish failed, or the step
     *         failed, otherwise {@link ExitStatus#COMPLETED}
     * @throws FileStatusException if the last account cannot be found during its finalize
     */
    @Override
    public ExitStatus afterStep(StepExecution stepExecution) {
        final Path temp = this.tempFile;
        final Path published = this.outputFile;

        // Fail-CLOSED gate (QA finding F-P5-E): finalize the LAST account and publish the SYSTRAN file
        // ONLY on a positively-verified clean completion (status COMPLETED, no failure exceptions, no
        // I/O error). On the mid-write connection-loss race the status is still STARTED at afterStep
        // time (FAILED is persisted afterwards and itself fails on the dead DB), so the previous
        // negative "== FAILED" gate was fail-open. Gating the last-account DB finalize on the same
        // signal also prevents it from running against a failing step. See BatchFilePublishDecision.
        boolean clean = BatchFilePublishDecision.isCleanCompletion(stepExecution, ioError);

        // Finalize the LAST account (documented deviation). afterStep runs OUTSIDE the chunk
        // transaction, so run the DB work in a programmatic transaction. If it throws (genuine missing
        // account), discard the staging file and propagate so the step is FAILED (batch RC 8).
        try {
            if (clean && currentAcctId != null) {
                final Long acctToFinalize = currentAcctId;
                final BigDecimal total = totalInterest;
                transactionTemplate.executeWithoutResult(
                        status -> finalizeAccount(acctToFinalize, total));
                currentAcctId = null; // idempotent guard: prevent a second finalize on re-invocation.
            }
        } catch (RuntimeException finalizeFailure) {
            closeWriterQuietly();
            deleteQuietly(temp);
            this.tempFile = null;
            this.outputFile = null;
            this.parmDate = null;
            throw finalizeFailure;
        }

        // Flush and close the staging stream (null-guard: the open may have failed).
        closeWriterQuietly();

        // Re-evaluate the fail-closed gate AFTER close (a close failure sets ioError).
        clean = BatchFilePublishDecision.isCleanCompletion(stepExecution, ioError);
        try {
            if (temp == null) {
                // beforeStep never opened a staging file; nothing to publish or discard.
                log.info("Interest calculation writer finished: {} interest record(s) written, "
                        + "{} account(s) finalized.", recordsWritten, accountsFinalized);
                return clean ? ExitStatus.COMPLETED : ExitStatus.FAILED;
            }
            if (!clean) {
                deleteQuietly(temp);
                log.error("Interest calculation did not complete cleanly; staging file {} discarded "
                        + "and no SYSTRAN file was published (no partial final-named file left "
                        + "behind).", temp);
                return ExitStatus.FAILED;
            }
            try {
                publishAtomically(temp, published);
            } catch (IOException ex) {
                log.error("Failed to publish interest SYSTRAN file {} -> {}", temp, published, ex);
                deleteQuietly(temp);
                return ExitStatus.FAILED;
            }
            log.info("Interest calculation writer finished: {} interest record(s) written, "
                    + "{} account(s) finalized; SYSTRAN published to {}.",
                    recordsWritten, accountsFinalized, published);
            return ExitStatus.COMPLETED;
        } finally {
            this.tempFile = null;
            this.outputFile = null;
            this.parmDate = null;
        }
    }

    /**
     * Flushes and closes the staging stream under a {@code null}-guard (the open may have failed). A
     * close failure is logged (no PII) and sets the sticky I/O-error flag. The stream reference is
     * always cleared so the singleton bean carries no state between runs.
     */
    private void closeWriterQuietly() {
        final BufferedWriter target = this.txnWriter;
        if (target == null) {
            return;
        }
        try {
            target.flush();
            target.close();
        } catch (IOException ex) {
            ioError = true;
            log.error("Failed to flush/close interest transaction staging file {}", tempFile, ex);
        } finally {
            this.txnWriter = null;
        }
    }

    /**
     * Publishes the completed staging file to its final {@code SYSTRAN} path with an atomic move. On
     * POSIX this is a single {@code rename(2)}, so a consumer sees either the previous file or the
     * fully-written new file, never a partial file. The final name is fixed (not a per-run generation),
     * so {@link StandardCopyOption#REPLACE_EXISTING} replaces any file from a prior cycle in the same
     * atomic operation. If the filesystem cannot perform an atomic move a plain same-directory
     * replacing rename is used as the closest available fallback.
     *
     * @param temp      the completed staging file; never {@code null}
     * @param published the final destination path; never {@code null}
     * @throws IOException if the move fails
     */
    private static void publishAtomically(final Path temp, final Path published) throws IOException {
        try {
            Files.move(temp, published,
                    StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException atomicUnsupported) {
            Files.move(temp, published, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    /**
     * Deletes the given path if it exists, swallowing any {@link IOException} (logged at {@code WARN})
     * so cleanup on a failure path never masks the original failure.
     *
     * @param path the path to delete; may be {@code null}
     */
    private static void deleteQuietly(final Path path) {
        if (path == null) {
            return;
        }
        try {
            Files.deleteIfExists(path);
        } catch (IOException ex) {
            log.warn("Unable to delete interest transaction staging file {}: {}",
                    path, ex.getMessage());
        }
    }
}
