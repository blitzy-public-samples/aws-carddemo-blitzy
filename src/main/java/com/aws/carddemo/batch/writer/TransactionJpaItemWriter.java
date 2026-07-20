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

import com.aws.carddemo.domain.Transaction;
import com.aws.carddemo.repository.TransactionRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.ExitStatus;
import org.springframework.batch.core.StepExecution;
import org.springframework.batch.core.StepExecutionListener;
import org.springframework.batch.item.Chunk;
import org.springframework.batch.item.ItemWriter;
import org.springframework.stereotype.Component;

/**
 * Spring Batch {@link ItemWriter} that persists each {@link Transaction} of a chunk into the
 * relational transaction master (PostgreSQL table {@code transaction}). It is the Java re-platform
 * of the <em>REPRO load</em> performed by the legacy mainframe job {@code legacy/jcl/COMBTRAN.jcl}
 * (source {@code app/jcl/COMBTRAN.jcl}).
 *
 * <h2>COBOL / JCL lineage</h2>
 * <p>{@code COMBTRAN.jcl} is a pure <em>combine &rarr; sort &rarr; load</em> job that carries no
 * business logic:</p>
 * <ul>
 *   <li><strong>{@code STEP05R} ({@code PGM=SORT}).</strong> Concatenates the current transaction
 *       backup generation {@code TRANSACT.BKUP(0)} with the system-generated transactions
 *       {@code SYSTRAN(0)} (350-byte {@code TRAN-RECORD} images, copybook
 *       {@code legacy/cpy/CVTRA05Y.cpy}) and sorts them by transaction id ascending
 *       ({@code SYMNAMES TRAN-ID,1,16,CH}; {@code SORT FIELDS=(TRAN-ID,A)}) into the new generation
 *       {@code TRANSACT.COMBINED(+1)}.</li>
 *   <li><strong>{@code STEP10} ({@code PGM=IDCAMS}).</strong> Loads the sorted combined file into
 *       the key-sequenced transaction master with {@code REPRO INFILE(TRANSACT)
 *       OUTFILE(TRANVSAM)}, where {@code TRANVSAM} is {@code TRANSACT.VSAM.KSDS}.</li>
 * </ul>
 *
 * <p>In the relational target the master KSDS becomes the {@code transaction} table. The
 * chunk-oriented step {@code transactionCombineStep} of {@code batch/TransactionCombineJob} wires an
 * upstream reader ({@code batch/reader/CombinedTransactionItemReader}) that already yields the
 * combined stream in {@code tranId}-ascending order together with <em>this</em> writer as the
 * persistence sink. Consequently this writer performs <strong>no sorting</strong> (ordering is the
 * reader's responsibility, mirroring {@code STEP05R}) and <strong>no business mutation</strong>
 * &mdash; its sole responsibility is to load each {@link Transaction} into the master, exactly as
 * {@code STEP10}'s {@code REPRO} does.</p>
 *
 * <h2>REPRO-without-REPLACE semantics (reject duplicate, load the rest, RC&nbsp;4)</h2>
 * <p>{@link Transaction} carries an <em>assigned</em> natural primary key ({@code tranId}, a
 * 16-character {@code String}) and implements {@link org.springframework.data.domain.Persistable
 * Persistable&lt;String&gt;}. The records this writer receives are freshly decoded from the two
 * external sequential inputs by {@code batch/reader/CombinedTransactionItemReader} (they are
 * constructed, never JPA-loaded), so each reports {@code isNew() == true} and
 * {@code TransactionRepository.save(...)} issues {@code EntityManager.persist(...)} &mdash; an
 * <strong>insert</strong> keyed on {@code tran_id}, never a {@code merge}/overwrite.</p>
 *
 * <p>{@code STEP10}'s control statement is {@code REPRO INFILE(TRANSACT) OUTFILE(TRANVSAM)} into the
 * <em>existing</em> {@code TRANSACT.VSAM.KSDS} opened {@code DISP=SHR}, with <strong>no</strong>
 * preceding {@code DELETE}/{@code DEFINE} and <strong>no</strong> {@code REPLACE} option
 * [legacy/jcl/COMBTRAN.jcl]. That is a <em>merge-insert</em>, not a replacement: IDCAMS inserts every
 * combined record whose key is new, and for a record whose key already exists it writes the
 * informational message {@code IDC1440I}, <em>skips that record</em>, continues with the rest, and
 * ends the step with condition code {@code 4}. It never overwrites an existing record and it never
 * deletes a record that is absent from the input (there is no {@code DELETE}/{@code DEFINE}), so
 * pre-existing "stale" rows that are not re-supplied by the combined input <strong>survive</strong>
 * &mdash; this is the faithful REPRO-without-REPLACE behavior, not a defect (see decision-log entry
 * <em>COMBTRAN merge-insert vs. replacement</em>).</p>
 *
 * <p>This writer reproduces that contract exactly: for each item it first probes the master with
 * {@link TransactionRepository#existsById(Object)}. If the key is already present the record is
 * <strong>rejected</strong> (the {@code IDC1440I} skip) &mdash; the running reject count is
 * incremented and the item is not persisted; otherwise the item is inserted. When at least one record
 * was rejected the step ends {@code COMPLETED_WITH_REJECTS} (RC&nbsp;4); a clean pass ends
 * {@code COMPLETED} (RC&nbsp;0); a genuine read/persist failure fails the step ({@code FAILED},
 * RC&nbsp;8). This is the same reject-and-continue return-code contract the posting writer
 * ({@code DailyTransactionPostingWriter}) uses for {@code CBTRN02C}.</p>
 *
 * <h2>Transaction boundary and state</h2>
 * <p>The chunk commit interval and the surrounding {@code @Transactional} boundary are owned by the
 * Spring Batch {@code Step} declared in {@code batch/TransactionCombineJob}; this writer must not
 * &mdash; and does not &mdash; open its own transaction or annotate {@link #write(Chunk)} with
 * {@code @Transactional}. The combine step commits <strong>one record per chunk</strong>
 * ({@code CHUNK_SIZE == 1}) so that each insert is committed before the next record's
 * {@code existsById} probe; this makes a duplicate key that appears twice <em>within the same
 * combined input</em> detectable (the second occurrence sees the first, already committed) exactly as
 * a record-at-a-time {@code REPRO} would. The writer keeps a single per-run counter
 * ({@link #rejectCount}) and therefore implements {@link StepExecutionListener}: Spring Batch
 * auto-registers the step's writer as a listener, so {@link #beforeStep(StepExecution)} resets the
 * counter and {@link #afterStep(StepExecution)} maps the outcome to the return code &mdash; it must
 * not be registered a second time. The counter is reset per step, mirroring the singleton
 * writer+{@code StepExecutionListener} pattern already used by {@code DailyTransactionPostingWriter}.</p>
 *
 * <h2>Registration and security</h2>
 * <p>The bean's default name is {@code transactionJpaItemWriter} (the decapitalized class name); it
 * is injected under exactly that name by {@code batch/TransactionCombineJob.transactionCombineStep}.
 * The writer logs only non-sensitive operational metadata &mdash; per-run insert/reject counts and,
 * at {@code DEBUG}, the {@code tranId} (an identifier, not a financial value) of a rejected duplicate;
 * it never logs a transaction amount, a full card number, a CVV, an SSN, a password, or any other
 * sensitive field.</p>
 *
 * @see Transaction
 * @see TransactionRepository
 * @see ItemWriter
 * @see StepExecutionListener
 * @see Chunk
 * @see <a href="http://www.apache.org/licenses/LICENSE-2.0">Apache License 2.0</a>
 */
@Component
public class TransactionJpaItemWriter implements ItemWriter<Transaction>, StepExecutionListener {

    /**
     * The {@link ExitStatus} code published when a run completes but at least one record was rejected
     * (a duplicate key). This is the frozen coordination contract shared with
     * {@code com.aws.carddemo.config.BatchExitCodeGenerator} (which maps it to process exit code 4)
     * and with {@code DailyTransactionPostingWriter}; it must match theirs exactly and must not be
     * changed in isolation.
     */
    private static final String COMPLETED_WITH_REJECTS = "COMPLETED_WITH_REJECTS";

    /** SLF4J logger; emits only non-sensitive operational metadata (insert/reject counts, tranId). */
    private static final Logger LOGGER =
            LoggerFactory.getLogger(TransactionJpaItemWriter.class);

    /** Spring Data JPA repository for the {@code transaction} master; the REPRO-load target. */
    private final TransactionRepository transactionRepository;

    /**
     * Running count of records rejected in the current step because their {@code tranId} already
     * existed in the master (the {@code IDC1440I} skip). Reset in {@link #beforeStep(StepExecution)}
     * and read in {@link #afterStep(StepExecution)} to map the return code. Mutated only on the
     * single batch step thread.
     */
    private long rejectCount;

    /**
     * Running count of records inserted in the current step. Reset in
     * {@link #beforeStep(StepExecution)} and logged in {@link #afterStep(StepExecution)}. Mutated
     * only on the single batch step thread.
     */
    private long insertCount;

    /**
     * Creates the writer with the transaction repository it loads into.
     *
     * <p>Constructor injection only: the repository is stored in a {@code private final} field and
     * there is no field-level {@code @Autowired}.</p>
     *
     * @param transactionRepository the Spring Data JPA repository for the {@code transaction}
     *                              master; never {@code null}
     */
    public TransactionJpaItemWriter(TransactionRepository transactionRepository) {
        this.transactionRepository = transactionRepository;
    }

    /**
     * Resets the per-run insert and reject counters at the start of {@code transactionCombineStep}.
     *
     * <p>Spring Batch auto-registers this bean as the step's {@link StepExecutionListener} because it
     * is the step's writer, so this callback fires automatically &mdash; it must not be registered a
     * second time.</p>
     *
     * @param stepExecution the current step execution (supplied by Spring Batch); never {@code null}
     */
    @Override
    public void beforeStep(StepExecution stepExecution) {
        this.rejectCount = 0L;
        this.insertCount = 0L;
    }

    /**
     * Loads each {@link Transaction} in the supplied chunk into the {@code transaction} master table
     * with REPRO-without-REPLACE semantics, reproducing the {@code STEP10} {@code REPRO} load of
     * {@code legacy/jcl/COMBTRAN.jcl}.
     *
     * <p>For each item, in the exact order the reader supplied it ({@code tranId} ascending; this
     * method neither reorders nor mutates items), the master is first probed with
     * {@link TransactionRepository#existsById(Object)}:</p>
     * <ul>
     *   <li>if the {@code tranId} is <strong>already present</strong>, the record is
     *       <strong>rejected</strong> &mdash; the {@code IDC1440I} skip: {@link #rejectCount} is
     *       incremented and the item is not persisted, leaving the existing row untouched;</li>
     *   <li>otherwise the record is <strong>inserted</strong> via
     *       {@link TransactionRepository#save(Object)} (a {@code persist}, because the reader-built
     *       entity reports {@code isNew() == true}), and {@link #insertCount} is incremented.</li>
     * </ul>
     *
     * <p>Because the combine step commits one record per chunk, each insert is committed before the
     * next item's {@code existsById} probe, so a duplicate {@code tranId} appearing twice within the
     * same combined input is detected and rejected on its second occurrence &mdash; matching a
     * record-at-a-time {@code REPRO}. Rejecting rather than failing lets the load continue and load
     * every non-duplicate record, exactly as {@code REPRO} without {@code REPLACE} does; the run then
     * ends {@code COMPLETED_WITH_REJECTS} (RC&nbsp;4) via {@link #afterStep(StepExecution)}.</p>
     *
     * <p>This method opens no transaction of its own; the chunk is committed within the
     * {@code Step}-owned transaction of {@code batch/TransactionCombineJob}. A genuine read/persist
     * failure propagates out unchanged (the method is declared {@code throws Exception}) so the batch
     * fails the step and reports RC&nbsp;8 instead of silently dropping records.</p>
     *
     * @param chunk the chunk of transactions to load, supplied by the upstream reader in
     *              {@code tranId}-ascending order; never {@code null}
     * @throws Exception if the underlying repository/persistence operation fails, failing the step
     */
    @Override
    public void write(Chunk<? extends Transaction> chunk) throws Exception {
        for (Transaction transaction : chunk.getItems()) {
            if (transactionRepository.existsById(transaction.getTranId())) {
                rejectCount++;
                LOGGER.debug(
                        "REPRO-load: rejected duplicate tranId {} (already present in the master; "
                                + "IDC1440I skip).",
                        transaction.getTranId());
            } else {
                transactionRepository.save(transaction);
                insertCount++;
            }
        }
    }

    /**
     * Maps the run outcome to the mainframe {@code RETURN-CODE} contract, reproducing the condition
     * code {@code STEP10}'s {@code REPRO} would have set.
     *
     * <ul>
     *   <li>{@link BatchStatus#FAILED} (a read/persist failure aborted the step) &rarr;
     *       {@link ExitStatus#FAILED} (RC&nbsp;8);</li>
     *   <li>otherwise {@link #rejectCount} {@code > 0} &rarr;
     *       {@code new ExitStatus("COMPLETED_WITH_REJECTS")} (RC&nbsp;4; the {@code IDC1440I}
     *       duplicate-skip condition code);</li>
     *   <li>otherwise &rarr; {@link ExitStatus#COMPLETED} (RC&nbsp;0).</li>
     * </ul>
     *
     * <p>The returned {@link ExitStatus} becomes the step's exit status and, for this single-step job,
     * the job's exit status, which {@code com.aws.carddemo.config.BatchExitCodeGenerator} translates
     * into the process exit code.</p>
     *
     * @param stepExecution the current step execution (supplied by Spring Batch); never {@code null}
     * @return the mapped {@link ExitStatus}
     */
    @Override
    public ExitStatus afterStep(StepExecution stepExecution) {
        LOGGER.info("REPRO-load complete: {} inserted, {} rejected (duplicate keys).",
                insertCount, rejectCount);
        if (stepExecution.getStatus() == BatchStatus.FAILED) {
            return ExitStatus.FAILED;                       // RC 8
        }
        if (rejectCount > 0) {
            return new ExitStatus(COMPLETED_WITH_REJECTS);  // RC 4
        }
        return ExitStatus.COMPLETED;                        // RC 0
    }
}
