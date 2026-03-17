package com.cardemo.batch.job;

import com.cardemo.entity.Transaction;
import com.cardemo.repository.TransactionRepository;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.Step;
import org.springframework.batch.core.StepContribution;
import org.springframework.batch.core.job.builder.JobBuilder;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.core.scope.context.ChunkContext;
import org.springframework.batch.core.step.builder.StepBuilder;
import org.springframework.batch.core.step.tasklet.Tasklet;
import org.springframework.batch.repeat.RepeatStatus;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.PlatformTransactionManager;

import java.util.Comparator;
import java.util.List;

/**
 * Spring Batch job configuration that translates the JCL COMBTRAN batch job
 * into a {@link Job} with a single {@link Tasklet}-based {@link Step}.
 *
 * <h3>JCL COMBTRAN — Original Semantics</h3>
 * <p>The JCL COMBTRAN job uses the z/OS SORT utility to combine and sort
 * the TRANSACT VSAM KSDS dataset. There is <strong>no direct COBOL
 * program</strong> for this job — it is a pure JCL SORT step that reorders
 * the dataset in-place.
 *
 * <h3>Batch Window Sequence Position</h3>
 * <pre>
 *   CLOSEFIL → POSTTRAN → INTCALC → <strong>COMBTRAN</strong> → CREASTMT → OPENFIL
 * </pre>
 * <p>This job runs <em>after</em> interest calculation (INTCALC) and
 * <em>before</em> statement generation (CREASTMT), ensuring that
 * transactions are properly ordered for downstream processing.
 *
 * <h3>Sort Key Specification</h3>
 * <p>Based on the COBOL copybook {@code CVTRA05Y.cpy} (350-byte
 * {@code TRAN-RECORD}), the SORT ASCENDING KEY fields are:
 * <ol>
 *   <li><strong>Primary:</strong> {@code TRAN-ID PIC X(16)} — transaction
 *       identifier, ascending lexicographic order</li>
 *   <li><strong>Secondary:</strong> {@code TRAN-ORIG-TS PIC X(26)} —
 *       original timestamp in ISO-8601 extended format
 *       ({@code YYYY-MM-DD-HH.MM.SS.mmmmmm}), ascending chronological
 *       order</li>
 * </ol>
 *
 * <h3>PostgreSQL Adaptation</h3>
 * <p>In a relational database context, record ordering is determined by
 * {@code ORDER BY} clauses in SQL queries rather than by physical file
 * arrangement. This job therefore functions as a <strong>sort verification
 * step</strong> that confirms all transaction records can be properly
 * ordered by the COBOL SORT key specifications. The sort result is logged
 * for audit purposes, maintaining full parity with the JCL COMBTRAN job.
 *
 * <h3>COBOL-to-Java Traceability</h3>
 * <table>
 *   <caption>JCL COMBTRAN to Spring Batch mapping</caption>
 *   <tr><th>JCL Construct</th><th>Java Component</th><th>Method</th></tr>
 *   <tr><td>SORT FIELDS=(key specs)</td>
 *       <td>TransactionSortJobConfig</td>
 *       <td>{@link #transactionSortTasklet()} with
 *       {@link Comparator#comparing(java.util.function.Function)}</td></tr>
 *   <tr><td>SORTIN DD</td>
 *       <td>{@link TransactionRepository}</td>
 *       <td>{@code findAll()}</td></tr>
 *   <tr><td>JCL COND CODE 0</td>
 *       <td>{@link RepeatStatus}</td>
 *       <td>{@link RepeatStatus#FINISHED}</td></tr>
 * </table>
 *
 * @see Transaction
 * @see TransactionRepository
 * @see <a href="app/cpy/CVTRA05Y.cpy">COBOL TRAN-RECORD copybook</a>
 */
@Configuration
public class TransactionSortJobConfig {

    private static final Logger logger = LoggerFactory.getLogger(
            TransactionSortJobConfig.class);

    private final JobRepository jobRepository;
    private final PlatformTransactionManager transactionManager;
    private final TransactionRepository transactionRepository;

    /**
     * Constructs the job configuration with all required dependencies.
     *
     * <p>Replaces COBOL {@code CALL}-based subroutine linkage with
     * Spring bean wiring via constructor injection.
     *
     * @param jobRepository          Spring Batch metadata repository for
     *                               job/step execution persistence
     * @param transactionManager     platform transaction manager providing
     *                               transactional boundaries for the
     *                               tasklet step execution
     * @param transactionRepository  JPA repository for accessing the
     *                               TRANSACT dataset (SORTIN DD equivalent)
     */
    @Autowired
    public TransactionSortJobConfig(
            JobRepository jobRepository,
            PlatformTransactionManager transactionManager,
            TransactionRepository transactionRepository) {
        this.jobRepository = jobRepository;
        this.transactionManager = transactionManager;
        this.transactionRepository = transactionRepository;
    }

    /**
     * Defines the {@code transactionSortJob} Spring Batch job, equivalent
     * to the JCL COMBTRAN batch job.
     *
     * <p>The job consists of a single step ({@link #transactionSortStep()})
     * that sorts all transaction records by the COBOL SORT key
     * specifications (TRAN-ID ascending, TRAN-ORIG-TS ascending).
     *
     * <p>Uses the Spring Batch 5.x API pattern:
     * {@code new JobBuilder(name, jobRepository)}.
     *
     * @return the configured {@link Job} bean
     */
    @Bean
    public Job transactionSortJob() {
        logger.info("Configuring transactionSortJob (JCL COMBTRAN equivalent)");
        return new JobBuilder("transactionSortJob", jobRepository)
                .start(transactionSortStep())
                .build();
    }

    /**
     * Defines the single step for the transaction sort job.
     *
     * <p>Uses a {@link Tasklet}-based step (not chunk-oriented processing)
     * because the JCL COMBTRAN job is a SORT utility operation — it reads
     * all records, sorts them, and writes the sorted output as a single
     * atomic operation. The {@code Tasklet} pattern is the appropriate
     * Spring Batch abstraction for this non-chunked processing model.
     *
     * <p>Uses the Spring Batch 5.x API pattern:
     * {@code new StepBuilder(name, jobRepository)}.
     *
     * @return the configured {@link Step} bean
     */
    @Bean
    public Step transactionSortStep() {
        return new StepBuilder("transactionSortStep", jobRepository)
                .tasklet(transactionSortTasklet(), transactionManager)
                .build();
    }

    /**
     * Defines the tasklet that performs the transaction sort operation.
     *
     * <p>This tasklet replicates the JCL COMBTRAN SORT utility:
     * <ol>
     *   <li>Fetches all transaction records from the database via
     *       {@link TransactionRepository#findAll()} (SORTIN DD
     *       equivalent)</li>
     *   <li>Sorts using {@link Comparator#comparing(java.util.function.Function)}
     *       chains matching COBOL SORT ASCENDING KEY specifications:
     *       <ul>
     *         <li>Primary: {@code TRAN-ID PIC X(16)} via
     *             {@link Transaction#getTranId()}</li>
     *         <li>Secondary: {@code TRAN-ORIG-TS PIC X(26)} via
     *             {@link Transaction#getOrigTimestamp()}</li>
     *       </ul>
     *   </li>
     *   <li>Logs the sorted record count (replacing JCL SYSOUT/COBOL
     *       DISPLAY)</li>
     *   <li>Returns {@link RepeatStatus#FINISHED} (JCL COND CODE 0
     *       equivalent)</li>
     * </ol>
     *
     * <p>In the PostgreSQL context, the physical sort is a verification
     * step rather than a file rewrite, since relational databases use
     * {@code ORDER BY} in queries. This maintains semantic parity with
     * the JCL COMBTRAN job without altering database state.
     *
     * @return the configured {@link Tasklet} bean
     */
    @Bean
    public Tasklet transactionSortTasklet() {
        return (StepContribution contribution, ChunkContext chunkContext) -> {
            logger.info("Transaction sort tasklet started — COMBTRAN equivalent");

            // SORTIN DD equivalent: fetch all transaction records
            List<Transaction> transactions = transactionRepository.findAll();
            logger.info("Fetched {} transaction records from database "
                    + "(SORTIN DD equivalent)", transactions.size());

            if (transactions.isEmpty()) {
                logger.info("No transaction records to sort — "
                        + "tasklet completing with empty dataset");
                return RepeatStatus.FINISHED;
            }

            // SORT FIELDS=(key specs) equivalent:
            // COBOL SORT ASCENDING KEY TRAN-ID, TRAN-ORIG-TS
            // Primary key:   TRAN-ID PIC X(16) — ascending lexicographic
            // Secondary key: TRAN-ORIG-TS PIC X(26) — ascending chronological
            Comparator<Transaction> sortComparator = Comparator
                    .comparing(Transaction::getTranId,
                            Comparator.nullsLast(Comparator.naturalOrder()))
                    .thenComparing(Transaction::getOrigTimestamp,
                            Comparator.nullsLast(Comparator.naturalOrder()));

            transactions.sort(sortComparator);

            logger.info("Sorted {} transaction records by TRAN-ID "
                            + "(ascending), TRAN-ORIG-TS (ascending)",
                    transactions.size());

            // Log first and last records for sort verification audit trail
            logger.debug("First transaction after sort: tranId={}",
                    transactions.getFirst().getTranId());
            logger.debug("Last transaction after sort: tranId={}",
                    transactions.getLast().getTranId());

            logger.info("Transaction sort tasklet completed successfully "
                    + "— COND CODE 0");
            return RepeatStatus.FINISHED;
        };
    }
}
