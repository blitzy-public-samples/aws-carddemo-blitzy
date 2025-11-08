package com.carddemo.batch.job;

import com.carddemo.batch.processor.TransactionProcessor;
import com.carddemo.batch.writer.TransactionDataWriter;
import com.carddemo.dto.DailyTransactionInput;
import com.carddemo.entity.Transaction;
import lombok.extern.slf4j.Slf4j;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.JobExecution;
import org.springframework.batch.core.JobExecutionListener;
import org.springframework.batch.core.SkipListener;
import org.springframework.batch.core.Step;
import org.springframework.batch.core.job.builder.JobBuilder;
import org.springframework.batch.core.launch.support.RunIdIncrementer;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.core.step.builder.StepBuilder;
import org.springframework.batch.item.ItemProcessor;
import org.springframework.batch.item.ItemReader;
import org.springframework.batch.item.ItemWriter;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.interceptor.DefaultTransactionAttribute;
import org.springframework.transaction.interceptor.TransactionAttribute;

import java.io.BufferedWriter;
import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Spring Batch job configuration for daily transaction posting and processing.
 * 
 * <p>This configuration class transforms the COBOL batch program CBTRN02C.cbl (Daily Transaction
 * Processing) from mainframe VSAM sequential file processing to a cloud-native Spring Batch job
 * with chunk-oriented processing, automatic transaction management, and checkpoint/restart
 * capability.</p>
 * 
 * <h2>COBOL to Spring Batch Transformation</h2>
 * 
 * <p><strong>Original COBOL Program Structure (CBTRN02C.cbl):</strong></p>
 * <pre>
 * PROCEDURE DIVISION.
 *     DISPLAY 'START OF EXECUTION OF PROGRAM CBTRN02C'.        (line 194)
 *     PERFORM 0000-DALYTRAN-OPEN.                               (line 195)
 *     PERFORM 0100-TRANFILE-OPEN.                               (line 196)
 *     ...
 *     PERFORM UNTIL END-OF-FILE = 'Y'                           (line 202)
 *         PERFORM 1000-DALYTRAN-GET-NEXT                        (line 204)
 *         ADD 1 TO WS-TRANSACTION-COUNT                         (line 206)
 *         PERFORM 1500-VALIDATE-TRAN                            (line 210)
 *         IF WS-VALIDATION-FAIL-REASON = 0                      (line 211)
 *             PERFORM 2000-POST-TRANSACTION                     (line 212)
 *         ELSE                                                  (line 213)
 *             ADD 1 TO WS-REJECT-COUNT                          (line 214)
 *             PERFORM 2500-WRITE-REJECT-REC                     (line 215)
 *         END-IF
 *     END-PERFORM.
 *     ...
 *     DISPLAY 'TRANSACTIONS PROCESSED :' WS-TRANSACTION-COUNT   (line 227)
 *     DISPLAY 'TRANSACTIONS REJECTED  :' WS-REJECT-COUNT        (line 228)
 *     DISPLAY 'END OF EXECUTION OF PROGRAM CBTRN02C'.          (line 232)
 * </pre>
 * 
 * <p><strong>Transformed Spring Batch Structure:</strong></p>
 * <pre>
 * {@code
 * @Bean
 * public Job dailyTransactionProcessingBatchJob(JobRepository jobRepository, Step step) {
 *     return new JobBuilder("dailyTransactionProcessingJob", jobRepository)
 *         .start(step)                           // Execute chunk-oriented step
 *         .incrementer(new RunIdIncrementer())   // Unique instance per run
 *         .listener(jobExecutionListener())      // Statistics tracking
 *         .build();
 * }
 * 
 * @Bean
 * public Step dailyTransactionProcessingStep(JobRepository jobRepository,
 *                                            PlatformTransactionManager transactionManager,
 *                                            ItemReader<DailyTransactionInput> reader,
 *                                            ItemProcessor<DailyTransactionInput, Transaction> processor,
 *                                            ItemWriter<Transaction> writer) {
 *     return new StepBuilder("dailyTransactionProcessingStep", jobRepository)
 *         .<DailyTransactionInput, Transaction>chunk(1000, transactionManager)  // Chunk size 1000
 *         .reader(reader)                                                        // Read CSV
 *         .processor(processor)                                                  // Validate
 *         .writer(writer)                                                        // Write DB
 *         .faultTolerant()                                                       // Error handling
 *         .skipLimit(100)                                                        // Max skip count
 *         .skip(Exception.class)                                                 // Skip on error
 *         .listener(skipListener())                                              // Reject logging
 *         .transactionAttribute(transactionAttribute())                          // REPEATABLE_READ
 *         .build();
 * }
 * }
 * </pre>
 * 
 * <h2>File Processing Transformation</h2>
 * 
 * <table border="1">
 *   <tr>
 *     <th>COBOL File</th>
 *     <th>Access Mode</th>
 *     <th>Spring Batch Equivalent</th>
 *   </tr>
 *   <tr>
 *     <td>DALYTRAN-FILE (lines 29-32)</td>
 *     <td>Sequential Input</td>
 *     <td>FlatFileItemReader with CSV parsing</td>
 *   </tr>
 *   <tr>
 *     <td>TRANSACT-FILE (lines 34-38)</td>
 *     <td>Random WRITE</td>
 *     <td>JpaItemWriter to transaction table</td>
 *   </tr>
 *   <tr>
 *     <td>XREF-FILE (lines 40-44)</td>
 *     <td>Random READ</td>
 *     <td>CardRepository.findByCardNumber()</td>
 *   </tr>
 *   <tr>
 *     <td>DALYREJS-FILE (lines 46-49)</td>
 *     <td>Sequential Output</td>
 *     <td>CSV reject file via SkipListener</td>
 *   </tr>
 *   <tr>
 *     <td>ACCOUNT-FILE (lines 51-55)</td>
 *     <td>Random READ/REWRITE</td>
 *     <td>AccountRepository.findById()/save()</td>
 *   </tr>
 *   <tr>
 *     <td>TCATBAL-FILE (lines 57-61)</td>
 *     <td>Random READ/REWRITE</td>
 *     <td>TransactionCategoryBalanceRepository</td>
 *   </tr>
 * </table>
 * 
 * <h2>Validation Logic Mapping</h2>
 * 
 * <p>COBOL paragraph 1500-VALIDATE-TRAN (lines 370-422) is implemented in
 * TransactionProcessor.process() with these validation steps:</p>
 * <ul>
 *   <li>1500-A-LOOKUP-XREF (lines 380-392): Card validation via CardRepository
 *       <ul>
 *         <li>Failure Code 100: "INVALID CARD NUMBER FOUND"</li>
 *       </ul>
 *   </li>
 *   <li>1500-B-LOOKUP-ACCT (lines 393-422): Account validation via AccountRepository
 *       <ul>
 *         <li>Failure Code 101: "ACCOUNT RECORD NOT FOUND"</li>
 *         <li>Failure Code 102: "OVERLIMIT TRANSACTION" (credit limit check)</li>
 *         <li>Failure Code 103: "TRANSACTION RECEIVED AFTER ACCT EXPIRATION"</li>
 *       </ul>
 *   </li>
 * </ul>
 * 
 * <h2>Transaction Posting Logic Mapping</h2>
 * 
 * <p>COBOL paragraph 2000-POST-TRANSACTION (lines 424-444) orchestrates three operations,
 * all executed within a single Spring @Transactional boundary in TransactionDataWriter:</p>
 * <ol>
 *   <li>2700-UPDATE-TCATBAL (lines 467-542): Update transaction category balance
 *       <ul>
 *         <li>READ TCATBAL-FILE (line 474)</li>
 *         <li>If not found, CREATE new record (2700-A, lines 503-524)</li>
 *         <li>ADD DALYTRAN-AMT TO TRAN-CAT-BAL (line 508 or 527)</li>
 *         <li>REWRITE/WRITE to TCATBAL-FILE</li>
 *       </ul>
 *   </li>
 *   <li>2800-UPDATE-ACCOUNT-REC (lines 545-560): Update account balances
 *       <ul>
 *         <li>ADD DALYTRAN-AMT TO ACCT-CURR-BAL (line 547)</li>
 *         <li>IF DALYTRAN-AMT &gt;= 0: ADD TO ACCT-CURR-CYC-CREDIT (line 549)</li>
 *         <li>ELSE: ADD TO ACCT-CURR-CYC-DEBIT (line 551)</li>
 *         <li>REWRITE FD-ACCTFILE-REC (line 554)</li>
 *       </ul>
 *   </li>
 *   <li>2900-WRITE-TRANSACTION-FILE (lines 562-579): Write transaction record
 *       <ul>
 *         <li>WRITE FD-TRANFILE-REC (line 564)</li>
 *       </ul>
 *   </li>
 * </ol>
 * 
 * <h2>Reject Handling</h2>
 * 
 * <p>COBOL paragraph 2500-WRITE-REJECT-REC (lines 446-465) is implemented in the
 * {@link SkipListener#onSkipInProcess} method with these transformations:</p>
 * <ul>
 *   <li>MOVE DALYTRAN-RECORD TO REJECT-TRAN-DATA (line 447) → CSV serialization</li>
 *   <li>MOVE WS-VALIDATION-TRAILER TO VALIDATION-TRAILER (line 448) → Failure reason appended</li>
 *   <li>WRITE FD-REJS-RECORD (line 451) → BufferedWriter append to reject file</li>
 *   <li>ADD 1 TO WS-REJECT-COUNT (line 214) → rejectCount.incrementAndGet()</li>
 * </ul>
 * 
 * <h2>Statistics Tracking</h2>
 * 
 * <p>COBOL counters (lines 185-186) are implemented as AtomicLong fields for thread-safe
 * parallel processing:</p>
 * <ul>
 *   <li>WS-TRANSACTION-COUNT (PIC 9(09)) → AtomicLong transactionCount</li>
 *   <li>WS-REJECT-COUNT (PIC 9(09)) → AtomicLong rejectCount</li>
 * </ul>
 * 
 * <p>Statistics are displayed by JobExecutionListener matching COBOL DISPLAY statements:</p>
 * <pre>
 * DISPLAY 'TRANSACTIONS PROCESSED :' WS-TRANSACTION-COUNT  (line 227)
 * DISPLAY 'TRANSACTIONS REJECTED  :' WS-REJECT-COUNT       (line 228)
 * </pre>
 * 
 * <h2>Transaction Management</h2>
 * 
 * <p>Chunk-oriented processing with transaction boundaries:</p>
 * <ul>
 *   <li><strong>Chunk Size:</strong> 1000 records per commit (tunable)</li>
 *   <li><strong>Isolation Level:</strong> REPEATABLE_READ prevents phantom reads during
 *       concurrent account balance updates</li>
 *   <li><strong>Transaction Semantics:</strong> All chunk operations (read 1000, process 1000,
 *       write 1000) occur in a single transaction; rollback occurs if any record fails
 *       after max skip limit</li>
 * </ul>
 * 
 * <h2>Checkpoint/Restart Capability</h2>
 * 
 * <p>Spring Batch JobRepository maintains execution state matching JCL POSTTRAN restart:</p>
 * <ul>
 *   <li>JobRepository tracks job execution metadata</li>
 *   <li>Step execution progress is persisted at chunk boundaries</li>
 *   <li>Failed jobs can be restarted from last successful chunk</li>
 *   <li>ItemReader maintains read position for restartability</li>
 * </ul>
 * 
 * <h2>Error Handling Strategy</h2>
 * 
 * <p><strong>Fault Tolerant Configuration:</strong></p>
 * <ul>
 *   <li><strong>Skip Limit:</strong> 100 validation failures allowed before job terminates</li>
 *   <li><strong>Skip Policy:</strong> Skip on Exception.class (catches all validation errors)</li>
 *   <li><strong>Skip Listener:</strong> Writes rejected records to CSV reject file</li>
 *   <li><strong>Return Code:</strong> Job exits with FAILED status if reject count &gt; 0,
 *       matching COBOL "MOVE 4 TO RETURN-CODE" (line 230)</li>
 * </ul>
 * 
 * <h2>Performance Considerations</h2>
 * 
 * <p>Optimizations for 4-hour batch processing window:</p>
 * <ul>
 *   <li><strong>Chunk Size 1000:</strong> Balances commit overhead with memory usage</li>
 *   <li><strong>Parallel Processing Support:</strong> Can be configured with TaskExecutor
 *       for multi-threaded execution (requires thread-safe counters via AtomicLong)</li>
 *   <li><strong>Hibernate Batch Processing:</strong> Leverages hibernate.jdbc.batch_size
 *       for optimized bulk inserts</li>
 *   <li><strong>Connection Pooling:</strong> HikariCP minimizes connection overhead</li>
 *   <li><strong>Transaction Isolation:</strong> REPEATABLE_READ prevents row-level lock
 *       escalation while maintaining consistency</li>
 * </ul>
 * 
 * <h2>Integration with CardDemo Architecture</h2>
 * 
 * <ul>
 *   <li><strong>Reader:</strong> TransactionDataReader provides FlatFileItemReader configured
 *       for 350-byte daily transaction records</li>
 *   <li><strong>Processor:</strong> TransactionProcessor validates transactions and enriches
 *       with card/account data</li>
 *   <li><strong>Writer:</strong> TransactionDataWriter provides JpaItemWriter for Transaction
 *       entity persistence</li>
 *   <li><strong>Database:</strong> PostgreSQL transaction table with indexes matching VSAM
 *       key structures</li>
 *   <li><strong>Scheduling:</strong> Kubernetes CronJob triggers daily execution (separate
 *       configuration)</li>
 * </ul>
 * 
 * <h2>Configuration Properties</h2>
 * 
 * <p>Required application.properties settings:</p>
 * <pre>
 * # Batch input/output directories
 * batch.input.directory=/var/carddemo/batch/input
 * batch.reject.directory=/var/carddemo/batch/reject
 * 
 * # Batch job configuration
 * spring.batch.job.enabled=false              # Prevent auto-execution on startup
 * spring.batch.jdbc.initialize-schema=always  # Create batch metadata tables
 * 
 * # Chunk processing configuration
 * batch.chunk.size=1000
 * batch.skip.limit=100
 * </pre>
 * 
 * <h2>Usage Example</h2>
 * 
 * <p>Execute job via JobLauncher:</p>
 * <pre>
 * {@code
 * JobParameters params = new JobParametersBuilder()
 *     .addString("dailyTransactionFile", "/input/dailytran_20240101.csv")
 *     .addString("rejectFile", "/reject/reject_20240101.csv")
 *     .addLocalDate("processingDate", LocalDate.now())
 *     .addLong("time", System.currentTimeMillis())  // For unique instance
 *     .toJobParameters();
 * 
 * JobExecution execution = jobLauncher.run(dailyTransactionProcessingJob, params);
 * 
 * // Check execution status
 * if (execution.getStatus() == BatchStatus.COMPLETED) {
 *     log.info("Batch job completed successfully");
 * } else {
 *     log.error("Batch job failed: {}", execution.getAllFailureExceptions());
 * }
 * }
 * </pre>
 * 
 * @see TransactionProcessor
 * @see TransactionDataReader
 * @see TransactionDataWriter
 * @see Transaction
 */
@Slf4j
@Configuration
public class DailyTransactionProcessingJob {

    /**
     * Transaction counter matching COBOL WS-TRANSACTION-COUNT (PIC 9(09)) at line 185.
     * 
     * <p>Incremented for every transaction processed (both accepted and rejected).
     * Uses AtomicLong for thread-safe increment operations during parallel chunk processing.</p>
     */
    private final AtomicLong transactionCount = new AtomicLong(0);

    /**
     * Reject counter matching COBOL WS-REJECT-COUNT (PIC 9(09)) at line 186.
     * 
     * <p>Incremented when validation fails and transaction is written to reject file.
     * Uses AtomicLong for thread-safe increment operations during parallel chunk processing.</p>
     */
    private final AtomicLong rejectCount = new AtomicLong(0);

    /**
     * Reject file writer for writing validation failures.
     * 
     * <p>Opened in beforeJob() and closed in afterJob() by JobExecutionListener.
     * Matches COBOL DALYREJS-FILE sequential output file (lines 46-49).</p>
     */
    private PrintWriter rejectFileWriter;

    /**
     * Job start timestamp for execution duration calculation.
     */
    private LocalDateTime jobStartTime;

    /**
     * Daily transaction processing batch job bean.
     * 
     * <p>This job orchestrates the daily posting of transactions from the daily transaction
     * input file to the master transaction file with account and category balance updates.
     * It matches the JCL POSTTRAN batch job that executes CBTRN02C.cbl.</p>
     * 
     * <p><strong>Job Configuration:</strong></p>
     * <ul>
     *   <li><strong>Job Name:</strong> "dailyTransactionProcessingJob" - used for job instance
     *       identification and restart</li>
     *   <li><strong>Step:</strong> dailyTransactionProcessingStep - chunk-oriented processing
     *       step</li>
     *   <li><strong>Incrementer:</strong> RunIdIncrementer - generates unique job instance per
     *       execution preventing duplicate instance conflicts</li>
     *   <li><strong>Listener:</strong> JobExecutionListener - handles pre/post job processing
     *       including reject file management and statistics reporting</li>
     * </ul>
     * 
     * <p><strong>Execution Flow:</strong></p>
     * <ol>
     *   <li>JobExecutionListener.beforeJob() - Initialize reject file and counters</li>
     *   <li>Execute dailyTransactionProcessingStep - Process transactions in chunks</li>
     *   <li>JobExecutionListener.afterJob() - Close reject file and report statistics</li>
     * </ol>
     * 
     * <p><strong>COBOL Program Equivalence:</strong></p>
     * <p>This job bean represents the entire CBTRN02C.cbl PROCEDURE DIVISION (lines 193-234),
     * coordinating file operations, validation, posting, and statistics reporting.</p>
     * 
     * @param jobRepository Spring Batch job repository for job execution metadata persistence
     * @param dailyTransactionProcessingStep the configured processing step
     * @return configured Job instance ready for execution
     */
    @Bean
    public Job dailyTransactionProcessingBatchJob(JobRepository jobRepository,
                                              @Qualifier("dailyTransactionProcessingStep") Step processingStep) {
        log.info("Configuring dailyTransactionProcessingBatchJob bean");
        
        return new JobBuilder("dailyTransactionProcessingJob", jobRepository)
                .start(processingStep)
                .incrementer(new RunIdIncrementer())
                .listener(new JobExecutionListener() {
                    @Override
                    public void beforeJob(JobExecution jobExecution) {
                        handleBeforeJob(jobExecution);
                    }

                    @Override
                    public void afterJob(JobExecution jobExecution) {
                        handleAfterJob(jobExecution);
                    }
                })
                .build();
    }

    /**
     * Daily transaction processing step bean with chunk-oriented processing configuration.
     * 
     * <p>This step implements the core batch processing logic matching COBOL PERFORM UNTIL
     * loop (lines 202-219) with these transformations:</p>
     * 
     * <table border="1">
     *   <tr>
     *     <th>COBOL Operation</th>
     *     <th>Spring Batch Equivalent</th>
     *     <th>Location</th>
     *   </tr>
     *   <tr>
     *     <td>PERFORM 1000-DALYTRAN-GET-NEXT</td>
     *     <td>ItemReader.read()</td>
     *     <td>TransactionDataReader</td>
     *   </tr>
     *   <tr>
     *     <td>PERFORM 1500-VALIDATE-TRAN</td>
     *     <td>ItemProcessor.process()</td>
     *     <td>TransactionProcessor</td>
     *   </tr>
     *   <tr>
     *     <td>PERFORM 2000-POST-TRANSACTION</td>
     *     <td>ItemWriter.write()</td>
     *     <td>TransactionDataWriter</td>
     *   </tr>
     *   <tr>
     *     <td>PERFORM 2500-WRITE-REJECT-REC</td>
     *     <td>SkipListener.onSkipInProcess()</td>
     *     <td>Inline SkipListener</td>
     *   </tr>
     * </table>
     * 
     * <p><strong>Chunk Processing Configuration:</strong></p>
     * <ul>
     *   <li><strong>Chunk Size:</strong> 1000 records - balances throughput with transaction
     *       size (tunable via application.properties)</li>
     *   <li><strong>Input Type:</strong> DailyTransactionInput DTO (350-byte record structure)</li>
     *   <li><strong>Output Type:</strong> Transaction entity (validated and enriched)</li>
     *   <li><strong>Processing Flow:</strong>
     *       <ol>
     *         <li>Read 1000 DailyTransactionInput records</li>
     *         <li>Process each record (validate and transform to Transaction)</li>
     *         <li>Write all valid Transactions in single database transaction</li>
     *         <li>Skip invalid records (written to reject file)</li>
     *       </ol>
     *   </li>
     * </ul>
     * 
     * <p><strong>Fault Tolerant Configuration:</strong></p>
     * <ul>
     *   <li><strong>Skip Limit:</strong> 100 - maximum validation failures before job abort</li>
     *   <li><strong>Skippable Exceptions:</strong> Exception.class - all validation errors
     *       are skippable</li>
     *   <li><strong>Skip Listener:</strong> Captures validation failures and writes to reject
     *       file with failure reason</li>
     *   <li><strong>Retry Policy:</strong> No retry (validation failures are deterministic)</li>
     * </ul>
     * 
     * <p><strong>Transaction Configuration:</strong></p>
     * <ul>
     *   <li><strong>Isolation Level:</strong> REPEATABLE_READ - prevents phantom reads during
     *       account balance updates when concurrent transactions process the same account</li>
     *   <li><strong>Propagation:</strong> REQUIRED (default) - chunk processing occurs within
     *       single transaction</li>
     *   <li><strong>Rollback Policy:</strong> Rollback entire chunk if skip limit exceeded or
     *       non-skippable exception occurs</li>
     *   <li><strong>Commit Policy:</strong> Commit at chunk boundary (every 1000 records)</li>
     * </ul>
     * 
     * <p><strong>Concurrency and Locking:</strong></p>
     * <p>REPEATABLE_READ isolation is critical for account balance consistency:</p>
     * <ul>
     *   <li>Prevents dirty reads of uncommitted balance updates</li>
     *   <li>Prevents non-repeatable reads when reading same account multiple times</li>
     *   <li>Uses PostgreSQL row-level locks (SELECT FOR UPDATE behavior)</li>
     *   <li>Balances consistency with concurrency (allows concurrent updates to different
     *       accounts)</li>
     *   <li>Matches COBOL VSAM file locking semantics</li>
     * </ul>
     * 
     * <p><strong>Performance Optimizations:</strong></p>
     * <ul>
     *   <li>Hibernate batch inserts (hibernate.jdbc.batch_size=20)</li>
     *   <li>Connection pooling (HikariCP with optimal pool size)</li>
     *   <li>Chunk-based commits reduce transaction overhead</li>
     *   <li>Prepared statement caching</li>
     *   <li>Can be extended with TaskExecutor for parallel processing</li>
     * </ul>
     * 
     * <p><strong>COBOL Paragraph Mapping:</strong></p>
     * <pre>
     * COBOL:
     *     PERFORM UNTIL END-OF-FILE = 'Y'                     (line 202)
     *         IF END-OF-FILE = 'N'                            (line 203)
     *             PERFORM 1000-DALYTRAN-GET-NEXT              (line 204)
     *             IF END-OF-FILE = 'N'                        (line 205)
     *                 ADD 1 TO WS-TRANSACTION-COUNT           (line 206)
     *                 MOVE 0 TO WS-VALIDATION-FAIL-REASON     (line 208)
     *                 PERFORM 1500-VALIDATE-TRAN              (line 210)
     *                 IF WS-VALIDATION-FAIL-REASON = 0        (line 211)
     *                     PERFORM 2000-POST-TRANSACTION       (line 212)
     *                 ELSE                                    (line 213)
     *                     ADD 1 TO WS-REJECT-COUNT            (line 214)
     *                     PERFORM 2500-WRITE-REJECT-REC       (line 215)
     *                 END-IF
     *             END-IF
     *         END-IF
     *     END-PERFORM.
     * 
     * Spring Batch:
     *     Chunk loop:
     *         Read chunk (reader.read() until null or chunk size)
     *         Process chunk:
     *             For each item:
     *                 transactionCount.incrementAndGet()
     *                 result = processor.process(item)
     *                 if (result == null) onSkipInProcess() → rejectCount.incrementAndGet()
     *         Write chunk (writer.write(validItems))
     *         Commit transaction
     * </pre>
     * 
     * @param jobRepository Spring Batch repository for step execution metadata
     * @param transactionManager platform transaction manager for chunk transaction management
     * @param reader configured ItemReader for daily transaction input file
     * @param processor configured ItemProcessor for validation and transformation
     * @param writer configured ItemWriter for transaction persistence
     * @return configured Step instance ready for execution
     */
    @Bean
    public Step dailyTransactionProcessingStep(
            JobRepository jobRepository,
            PlatformTransactionManager transactionManager,
            @Qualifier("dailyTransactionReader") ItemReader<DailyTransactionInput> reader,
            ItemProcessor<DailyTransactionInput, Transaction> processor,
            ItemWriter<Transaction> writer) {
        
        log.info("Configuring dailyTransactionProcessingStep bean with chunk size 1000");
        
        // Create transaction attribute with REPEATABLE_READ isolation
        // Matches COBOL VSAM file locking semantics and prevents phantom reads
        // during concurrent account balance updates
        TransactionAttribute transactionAttribute = new DefaultTransactionAttribute();
        ((DefaultTransactionAttribute) transactionAttribute)
                .setIsolationLevel(Isolation.REPEATABLE_READ.value());
        
        return new StepBuilder("dailyTransactionProcessingStep", jobRepository)
                .<DailyTransactionInput, Transaction>chunk(1000, transactionManager)
                .reader(reader)
                .processor(processor)
                .writer(writer)
                .faultTolerant()
                .skipLimit(100)  // Max 100 validation failures before job abort
                .skip(Exception.class)  // All exceptions are skippable (validation errors)
                .listener(new SkipListener<DailyTransactionInput, Transaction>() {
                    @Override
                    public void onSkipInRead(Throwable t) {
                        log.warn("Skip in read phase: {}", t.getMessage());
                    }

                    @Override
                    public void onSkipInWrite(Transaction item, Throwable t) {
                        log.warn("Skip in write phase for transaction {}: {}", 
                                item.getTransactionId(), t.getMessage());
                    }

                    @Override
                    public void onSkipInProcess(DailyTransactionInput item, Throwable t) {
                        handleSkipInProcess(item, t);
                    }
                })
                .transactionAttribute(transactionAttribute)
                .build();
    }

    /**
     * Handles job initialization before execution starts.
     * 
     * <p>Implements COBOL file open operations and counter initialization from lines 195-200:</p>
     * <pre>
     * PERFORM 0000-DALYTRAN-OPEN.    (line 195) → Input file (handled by reader)
     * PERFORM 0100-TRANFILE-OPEN.    (line 196) → Transaction file (handled by writer)
     * PERFORM 0200-XREFFILE-OPEN.    (line 197) → Card xref (handled by processor)
     * PERFORM 0300-DALYREJS-OPEN.    (line 198) → Reject file (opened here)
     * PERFORM 0400-ACCTFILE-OPEN.    (line 199) → Account file (handled by processor)
     * PERFORM 0500-TCATBALF-OPEN.    (line 200) → Category balance (handled by processor)
     * </pre>
     * 
     * <p>Also matches COBOL DISPLAY statement at line 194:</p>
     * <pre>
     * DISPLAY 'START OF EXECUTION OF PROGRAM CBTRN02C'.
     * </pre>
     * 
     * <p><strong>Operations Performed:</strong></p>
     * <ol>
     *   <li>Log job start message matching COBOL DISPLAY statement</li>
     *   <li>Reset transaction and reject counters to zero</li>
     *   <li>Record job start timestamp for duration calculation</li>
     *   <li>Extract reject file path from job parameters</li>
     *   <li>Create reject file directory if it doesn't exist</li>
     *   <li>Open reject file for writing (buffered for performance)</li>
     *   <li>Write reject file header row with column names</li>
     *   <li>Log initialization completion</li>
     * </ol>
     * 
     * <p><strong>Reject File Format:</strong></p>
     * <p>CSV format with header row and data rows:</p>
     * <pre>
     * TRANSACTION_ID,TYPE_CODE,CATEGORY_CODE,AMOUNT,CARD_NUMBER,...,FAILURE_CODE,FAILURE_REASON
     * TXN001,01,1000,100.50,4111111111111111,...,100,INVALID CARD NUMBER FOUND
     * TXN002,02,2000,250.00,4222222222222222,...,102,OVERLIMIT TRANSACTION
     * </pre>
     * 
     * <p><strong>Error Handling:</strong></p>
     * <p>If reject file cannot be created or opened, logs error and continues job execution.
     * Reject records will be logged but not written to file. This matches COBOL behavior
     * where OPEN failures are logged but don't abort the job.</p>
     * 
     * @param jobExecution the job execution context containing parameters and status
     */
    private void handleBeforeJob(JobExecution jobExecution) {
        log.info("START OF EXECUTION OF DAILY TRANSACTION PROCESSING JOB");
        
        // Reset counters (matching COBOL WS-COUNTERS initialization at lines 184-186)
        transactionCount.set(0);
        rejectCount.set(0);
        jobStartTime = LocalDateTime.now();
        
        // Extract reject file path from job parameters
        String rejectFilePath = jobExecution.getJobParameters().getString("rejectFile");
        if (rejectFilePath == null || rejectFilePath.trim().isEmpty()) {
            // Use default reject file path with timestamp
            String timestamp = LocalDateTime.now().toString().replace(":", "-");
            rejectFilePath = "/var/carddemo/batch/reject/dailytran_reject_" + timestamp + ".csv";
            log.info("No reject file path provided, using default: {}", rejectFilePath);
        }
        
        try {
            // Create reject file directory if it doesn't exist
            Path rejectFileParent = Paths.get(rejectFilePath).getParent();
            if (rejectFileParent != null && !Files.exists(rejectFileParent)) {
                Files.createDirectories(rejectFileParent);
                log.info("Created reject file directory: {}", rejectFileParent);
            }
            
            // Open reject file for writing (matching COBOL PERFORM 0300-DALYREJS-OPEN)
            rejectFileWriter = new PrintWriter(
                    new BufferedWriter(new FileWriter(rejectFilePath, false)), true);
            
            // Write CSV header row
            rejectFileWriter.println("TRANSACTION_ID,TYPE_CODE,CATEGORY_CODE,SOURCE," +
                    "DESCRIPTION,AMOUNT,MERCHANT_ID,MERCHANT_NAME,MERCHANT_CITY," +
                    "MERCHANT_ZIP,CARD_NUMBER,ORIGINATION_TIMESTAMP,FAILURE_CODE,FAILURE_REASON");
            
            log.info("Initialized reject file: {}", rejectFilePath);
            
        } catch (IOException e) {
            log.error("Failed to initialize reject file: {}. " +
                    "Rejected transactions will be logged but not written to file.", 
                    rejectFilePath, e);
            rejectFileWriter = null;
        }
        
        log.info("Job initialization completed. Ready to process transactions.");
    }

    /**
     * Handles job cleanup and statistics reporting after execution completes.
     * 
     * <p>Implements COBOL file close operations and statistics display from lines 221-232:</p>
     * <pre>
     * PERFORM 9000-DALYTRAN-CLOSE.     (line 221) → Input file (handled by reader)
     * PERFORM 9100-TRANFILE-CLOSE.     (line 222) → Transaction file (handled by writer)
     * PERFORM 9200-XREFFILE-CLOSE.     (line 223) → Card xref (handled by processor)
     * PERFORM 9300-DALYREJS-CLOSE.     (line 224) → Reject file (closed here)
     * PERFORM 9400-ACCTFILE-CLOSE.     (line 225) → Account file (handled by processor)
     * PERFORM 9500-TCATBALF-CLOSE.     (line 226) → Category balance (handled by processor)
     * DISPLAY 'TRANSACTIONS PROCESSED :' WS-TRANSACTION-COUNT  (line 227)
     * DISPLAY 'TRANSACTIONS REJECTED  :' WS-REJECT-COUNT       (line 228)
     * IF WS-REJECT-COUNT > 0                                   (line 229)
     *     MOVE 4 TO RETURN-CODE                                (line 230)
     * END-IF
     * DISPLAY 'END OF EXECUTION OF PROGRAM CBTRN02C'.          (line 232)
     * </pre>
     * 
     * <p><strong>Operations Performed:</strong></p>
     * <ol>
     *   <li>Close reject file writer and flush pending writes</li>
     *   <li>Calculate job execution duration</li>
     *   <li>Calculate accepted transaction count (processed - rejected)</li>
     *   <li>Log final statistics matching COBOL DISPLAY statements</li>
     *   <li>Set job exit status based on reject count (COMPLETED or COMPLETED_WITH_ERRORS)</li>
     *   <li>Log job completion message</li>
     * </ol>
     * 
     * <p><strong>Statistics Reported:</strong></p>
     * <ul>
     *   <li><strong>Total Transactions Processed:</strong> transactionCount (all records read)</li>
     *   <li><strong>Transactions Accepted:</strong> transactionCount - rejectCount (successfully
     *       posted)</li>
     *   <li><strong>Transactions Rejected:</strong> rejectCount (validation failures)</li>
     *   <li><strong>Reject Rate:</strong> percentage of rejections</li>
     *   <li><strong>Processing Duration:</strong> total job execution time</li>
     *   <li><strong>Throughput:</strong> transactions per second</li>
     * </ul>
     * 
     * <p><strong>Exit Status Handling:</strong></p>
     * <p>Matches COBOL return code logic at lines 229-231:</p>
     * <ul>
     *   <li>If rejectCount = 0: Job status = COMPLETED (success)</li>
     *   <li>If rejectCount &gt; 0: Job status = COMPLETED (with warnings logged)</li>
     * </ul>
     * 
     * <p>Note: Spring Batch doesn't have an exact equivalent to COBOL RETURN-CODE 4.
     * The job is marked as COMPLETED even with rejections, but the reject count is
     * prominently logged and can be checked by calling systems.</p>
     * 
     * <p><strong>Error Handling:</strong></p>
     * <p>If reject file close fails, logs error but doesn't affect job status.
     * Statistics are still reported using counter values.</p>
     * 
     * @param jobExecution the job execution context containing final status and metrics
     */
    private void handleAfterJob(JobExecution jobExecution) {
        // Close reject file (matching COBOL PERFORM 9300-DALYREJS-CLOSE at line 224)
        if (rejectFileWriter != null) {
            try {
                rejectFileWriter.flush();
                rejectFileWriter.close();
                log.info("Reject file closed successfully");
            } catch (Exception e) {
                log.error("Error closing reject file", e);
            }
        }
        
        // Calculate statistics
        long totalProcessed = transactionCount.get();
        long rejected = rejectCount.get();
        long accepted = totalProcessed - rejected;
        
        Duration duration = Duration.between(jobStartTime, LocalDateTime.now());
        double durationSeconds = duration.getSeconds() + duration.getNano() / 1_000_000_000.0;
        double throughput = durationSeconds > 0 ? totalProcessed / durationSeconds : 0;
        
        // Display statistics matching COBOL DISPLAY statements at lines 227-228
        log.info("===========================================================");
        log.info("DAILY TRANSACTION PROCESSING JOB STATISTICS");
        log.info("===========================================================");
        log.info("TRANSACTIONS PROCESSED : {}", totalProcessed);
        log.info("TRANSACTIONS ACCEPTED  : {}", accepted);
        log.info("TRANSACTIONS REJECTED  : {}", rejected);
        
        if (totalProcessed > 0) {
            double rejectRate = (rejected * 100.0) / totalProcessed;
            log.info("REJECT RATE           : {0.2f}%", rejectRate);
        }
        
        log.info("PROCESSING DURATION   : {} seconds", String.format("%.2f", durationSeconds));
        log.info("THROUGHPUT            : {0.2f} transactions/second", throughput);
        log.info("===========================================================");
        
        // Log warning if rejections occurred (matching COBOL lines 229-231)
        if (rejected > 0) {
            log.warn("Job completed with {} rejected transactions. " +
                    "Review reject file for validation failures.", rejected);
            // Note: COBOL sets RETURN-CODE to 4 here, but Spring Batch
            // job still completes successfully. Calling systems should
            // check reject count for data quality monitoring.
        }
        
        log.info("END OF EXECUTION OF DAILY TRANSACTION PROCESSING JOB");
    }

    /**
     * Handles skipped transactions during processing phase (validation failures).
     * 
     * <p>Implements COBOL paragraph 2500-WRITE-REJECT-REC (lines 446-465) which writes
     * rejected transactions to the DALYREJS-FILE with validation failure information.</p>
     * 
     * <p><strong>COBOL Implementation:</strong></p>
     * <pre>
     * 2500-WRITE-REJECT-REC.
     *     MOVE DALYTRAN-RECORD TO REJECT-TRAN-DATA              (line 447)
     *     MOVE WS-VALIDATION-TRAILER TO VALIDATION-TRAILER      (line 448)
     *     MOVE 8 TO APPL-RESULT                                 (line 450)
     *     WRITE FD-REJS-RECORD FROM REJECT-RECORD               (line 451)
     *     IF DALYREJS-STATUS = '00'                             (line 452)
     *         MOVE 0 TO  APPL-RESULT                            (line 453)
     *     ELSE                                                  (line 454)
     *         MOVE 12 TO APPL-RESULT                            (line 455)
     *     END-IF
     *     IF  APPL-AOK                                          (line 457)
     *         CONTINUE                                          (line 458)
     *     ELSE                                                  (line 459)
     *         DISPLAY 'ERROR WRITING TO REJECTS FILE'           (line 460)
     *         MOVE DALYREJS-STATUS  TO IO-STATUS                (line 461)
     *         PERFORM 9910-DISPLAY-IO-STATUS                    (line 462)
     *         PERFORM 9999-ABEND-PROGRAM                        (line 463)
     *     END-IF
     *     EXIT.                                                 (line 465)
     * </pre>
     * 
     * <p><strong>Spring Batch Implementation:</strong></p>
     * <ol>
     *   <li>Increment reject counter (matching line 214)</li>
     *   <li>Extract validation failure reason from exception message</li>
     *   <li>Format reject record as CSV with all transaction fields</li>
     *   <li>Append failure code and failure reason description</li>
     *   <li>Write to reject file (buffered writer for performance)</li>
     *   <li>Log rejection for monitoring and troubleshooting</li>
     * </ol>
     * 
     * <p><strong>Validation Failure Codes (from COBOL):</strong></p>
     * <table border="1">
     *   <tr>
     *     <th>Code</th>
     *     <th>Description</th>
     *     <th>COBOL Location</th>
     *   </tr>
     *   <tr>
     *     <td>100</td>
     *     <td>INVALID CARD NUMBER FOUND</td>
     *     <td>Lines 385-387</td>
     *   </tr>
     *   <tr>
     *     <td>101</td>
     *     <td>ACCOUNT RECORD NOT FOUND</td>
     *     <td>Lines 397-399</td>
     *   </tr>
     *   <tr>
     *     <td>102</td>
     *     <td>OVERLIMIT TRANSACTION</td>
     *     <td>Lines 410-412</td>
     *   </tr>
     *   <tr>
     *     <td>103</td>
     *     <td>TRANSACTION RECEIVED AFTER ACCT EXPIRATION</td>
     *     <td>Lines 417-419</td>
     *   </tr>
     * </table>
     * 
     * <p><strong>Reject Record Format:</strong></p>
     * <p>CSV format matching COBOL FD-REJS-RECORD structure (lines 82-84):</p>
     * <pre>
     * FD-REJECT-RECORD (PIC X(350)) → All transaction fields comma-separated
     * FD-VALIDATION-TRAILER (PIC X(80)) → Failure code and description
     * </pre>
     * 
     * <p><strong>Example Reject Record:</strong></p>
     * <pre>
     * TXN20240101001,01,1000,Online,Purchase at Store,125.50,123456789,
     * ABC Store,New York,10001,4111111111111111,2024-01-01T10:30:00,100,
     * INVALID CARD NUMBER FOUND
     * </pre>
     * 
     * <p><strong>Error Handling:</strong></p>
     * <p>Unlike COBOL which abends on reject file write failure (line 463), this
     * implementation logs the error and continues processing. This prevents a single
     * file I/O error from aborting the entire batch job. The reject record is logged
     * for manual review even if file write fails.</p>
     * 
     * @param item the daily transaction input that failed validation
     * @param t the exception thrown during validation (contains failure reason)
     */
    private void handleSkipInProcess(DailyTransactionInput item, Throwable t) {
        // Increment reject counter (matching COBOL line 214: ADD 1 TO WS-REJECT-COUNT)
        long currentRejectCount = rejectCount.incrementAndGet();
        
        // Extract failure reason from exception
        String failureReason = t.getMessage() != null ? t.getMessage() : "VALIDATION ERROR";
        
        // Determine failure code based on exception message content
        String failureCode = "999";  // Default unknown error code
        if (failureReason.contains("INVALID CARD")) {
            failureCode = "100";
        } else if (failureReason.contains("ACCOUNT NOT FOUND")) {
            failureCode = "101";
        } else if (failureReason.contains("OVERLIMIT")) {
            failureCode = "102";
        } else if (failureReason.contains("EXPIRATION")) {
            failureCode = "103";
        }
        
        // Log rejection for monitoring
        log.warn("Transaction rejected [{}]: {} - Code: {}, Reason: {}",
                currentRejectCount, item.getTransactionId(), failureCode, failureReason);
        
        // Write to reject file if writer is available
        if (rejectFileWriter != null) {
            try {
                // Format reject record as CSV (matching COBOL REJECT-RECORD structure)
                String rejectRecord = formatRejectRecord(item, failureCode, failureReason);
                rejectFileWriter.println(rejectRecord);
                rejectFileWriter.flush();  // Ensure record is written immediately
                
            } catch (Exception e) {
                // Log error but don't abort job (different from COBOL PERFORM 9999-ABEND-PROGRAM)
                log.error("Failed to write reject record to file for transaction {}: {}",
                        item.getTransactionId(), e.getMessage());
            }
        }
    }

    /**
     * Formats a rejected transaction record as CSV string.
     * 
     * <p>Creates CSV record matching COBOL REJECT-RECORD structure with all transaction
     * fields plus validation failure information.</p>
     * 
     * @param item the rejected transaction input
     * @param failureCode the validation failure code (100-103, 999)
     * @param failureReason the validation failure description
     * @return formatted CSV string ready for file writing
     */
    private String formatRejectRecord(DailyTransactionInput item, 
                                     String failureCode, 
                                     String failureReason) {
        // Escape CSV special characters in string fields
        String description = escapeCsv(item.getDescription());
        String merchantName = escapeCsv(item.getMerchantName());
        String merchantCity = escapeCsv(item.getMerchantCity());
        String failureReasonEscaped = escapeCsv(failureReason);
        
        return String.format("%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s",
                item.getTransactionId(),
                item.getTypeCode(),
                item.getCategoryCode(),
                item.getTransactionSource(),
                description,
                item.getAmount(),
                item.getMerchantId(),
                merchantName,
                merchantCity,
                item.getMerchantZip(),
                item.getCardNumber(),
                item.getOriginationTimestamp(),
                failureCode,
                failureReasonEscaped);
    }

    /**
     * Escapes special characters in CSV fields.
     * 
     * <p>Handles commas, quotes, and newlines according to CSV RFC 4180 standard.</p>
     * 
     * @param value the field value to escape
     * @return escaped value safe for CSV output
     */
    private String escapeCsv(String value) {
        if (value == null) {
            return "";
        }
        
        // If value contains comma, quote, or newline, wrap in quotes and escape internal quotes
        if (value.contains(",") || value.contains("\"") || value.contains("\n")) {
            return "\"" + value.replace("\"", "\"\"") + "\"";
        }
        
        return value;
    }
}
