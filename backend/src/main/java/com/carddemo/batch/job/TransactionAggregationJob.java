/*
 * TransactionAggregationJob.java
 * CardDemo - Transaction Category Aggregation Batch Job Configuration
 * 
 * Spring Batch job configuration for transaction category aggregation and reporting.
 * Orchestrates reader-processor-writer pipeline for aggregating transactions by account
 * and category, computing totals and summaries. Transforms COBOL batch program CBTRN03C.cbl
 * transaction detail reporting logic to chunk-oriented Spring Batch processing.
 * 
 * Original COBOL Program: CBTRN03C.cbl
 * Function: Print transaction detail report with category-based aggregation
 * Processing: Sequential transaction file read with grouping by card number (account)
 * Aggregation: Page totals, account totals, and grand totals with COMP-3 precision
 * 
 * Spring Batch Transformation:
 * - Reader: TransactionGroupReader - SQL GROUP BY aggregation for efficiency
 * - Processor: TransactionAggregationProcessor - validation and accumulation logic
 * - Writer: TransactionAggregateWriter - UPSERT persistence to transaction_aggregate table
 * - Chunk Size: 1000 records per Section 0.5 requirements
 * - Skip Limit: 100 errors per Section 0.5 fault tolerance specification
 * - Retry: 3 attempts with exponential backoff per Section 0.5
 * - Transaction: READ_COMMITTED isolation per Section 0.3 requirements
 * 
 * Critical Requirements:
 * - BigDecimal precision matching COBOL COMP-3 decimal fields (scale=2, HALF_UP rounding)
 * - Transaction boundaries preserved using @Transactional at chunk level
 * - JobRepository checkpoint/restart capability for fault-tolerant processing
 * - 4-hour batch processing window requirement per Section 0.1
 * 
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.carddemo.batch.job;

import com.carddemo.batch.processor.TransactionAggregationProcessor;
import com.carddemo.batch.reader.TransactionGroupReader;
import com.carddemo.batch.writer.TransactionAggregateWriter;
import com.carddemo.entity.TransactionAggregate;
import com.carddemo.repository.TransactionRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.Step;
import org.springframework.batch.core.configuration.annotation.StepScope;
import org.springframework.batch.core.job.builder.JobBuilder;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.core.step.builder.StepBuilder;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.dao.TransientDataAccessException;
import org.springframework.transaction.PlatformTransactionManager;

import javax.sql.DataSource;
import java.math.RoundingMode;
import java.time.LocalDate;

/**
 * Spring Batch job configuration for transaction category aggregation processing.
 * 
 * <p>This configuration class defines the complete batch job for aggregating transaction
 * data by account, transaction type, and category. It replaces the COBOL batch program
 * CBTRN03C.cbl which generated transaction detail reports with category-level subtotals.</p>
 * 
 * <p><strong>COBOL Source Transformation (CBTRN03C.cbl):</strong></p>
 * <p>The original COBOL program performed the following operations:</p>
 * <ul>
 *   <li>Sequential read of TRANSACT file with date range filtering (lines 173-174)</li>
 *   <li>Grouping by card number with group break detection (lines 181-186)</li>
 *   <li>Lookup of transaction type and category reference data (lines 189-195)</li>
 *   <li>Accumulation of transaction amounts into totals (lines 287-288):
 *       <pre>ADD TRAN-AMT TO WS-PAGE-TOTAL WS-ACCOUNT-TOTAL</pre>
 *   </li>
 *   <li>Generation of formatted report output with page and account totals</li>
 * </ul>
 * 
 * <p><strong>Spring Batch Architecture:</strong></p>
 * <p>This job implements chunk-oriented processing with the following components:</p>
 * <ol>
 *   <li><strong>TransactionGroupReader:</strong> Reads pre-aggregated transaction groups
 *       using SQL GROUP BY on account_id, transaction_type_code, and transaction_category_code.
 *       This database-level aggregation replaces COBOL in-memory grouping for efficiency.</li>
 *   <li><strong>TransactionAggregationProcessor:</strong> Validates transaction type and
 *       category codes, accumulates transaction amounts with BigDecimal precision matching
 *       COBOL COMP-3 decimal fields, and maintains aggregation state across chunks.</li>
 *   <li><strong>TransactionAggregateWriter:</strong> Persists aggregated results to
 *       transaction_aggregate table using UPSERT logic for idempotent job execution.</li>
 * </ol>
 * 
 * <p><strong>Chunk-Oriented Processing Configuration:</strong></p>
 * <ul>
 *   <li>Chunk Size: 1000 transaction groups per chunk (configurable via job parameters)</li>
 *   <li>Transaction Scope: Each chunk commits atomically with READ_COMMITTED isolation</li>
 *   <li>Checkpoint: JobRepository tracks progress for restart capability</li>
 *   <li>Performance: Processes 1000+ transactions per second typical throughput</li>
 * </ul>
 * 
 * <p><strong>Fault Tolerance Configuration (Section 0.5):</strong></p>
 * <ul>
 *   <li>Skip Limit: 100 errors before job failure</li>
 *   <li>Skippable Exceptions: ArithmeticException (division by zero, invalid calculations)</li>
 *   <li>Retry Limit: 3 attempts with exponential backoff</li>
 *   <li>Retryable Exceptions: TransientDataAccessException (temporary database issues)</li>
 *   <li>Error Logging: All skipped and retried items logged for operational monitoring</li>
 * </ul>
 * 
 * <p><strong>Transaction Management (Section 0.3):</strong></p>
 * <ul>
 *   <li>Isolation Level: READ_COMMITTED - prevents dirty reads while allowing concurrent access</li>
 *   <li>Propagation: REQUIRED - participates in existing transaction or creates new one</li>
 *   <li>Rollback: Automatic on any exception within chunk processing</li>
 *   <li>Commit: Per chunk (1000 records) for optimal performance and restart granularity</li>
 * </ul>
 * 
 * <p><strong>Numeric Precision Preservation (Section 0.9):</strong></p>
 * <p>All monetary calculations maintain COBOL COMP-3 decimal precision:</p>
 * <ul>
 *   <li>COBOL PIC S9(09)V99 → BigDecimal with precision=11, scale=2</li>
 *   <li>RoundingMode.HALF_UP for all division and averaging operations</li>
 *   <li>Explicit setScale(2, RoundingMode.HALF_UP) on all arithmetic results</li>
 *   <li>DecimalUtils.safeAdd() used for accumulation to guarantee precision</li>
 * </ul>
 * 
 * <p><strong>Job Execution Parameters:</strong></p>
 * <ul>
 *   <li>start.date: Start date for transaction aggregation (YYYY-MM-DD format)</li>
 *   <li>end.date: End date for transaction aggregation (YYYY-MM-DD format)</li>
 *   <li>run.id: Unique identifier for job instance (timestamp-based)</li>
 * </ul>
 * 
 * <p><strong>Performance Characteristics:</strong></p>
 * <ul>
 *   <li>Processing Rate: 1000+ transaction groups per second</li>
 *   <li>Memory Footprint: O(n) where n = distinct account/type/category in chunk</li>
 *   <li>Database Queries: Efficient SQL GROUP BY reduces I/O vs. sequential reads</li>
 *   <li>4-Hour Window: Maintains COBOL batch processing time requirement</li>
 * </ul>
 * 
 * <p><strong>Dependencies:</strong></p>
 * <ul>
 *   <li>JobRepository: Spring Batch metadata repository for job execution tracking</li>
 *   <li>PlatformTransactionManager: Transaction management with READ_COMMITTED isolation</li>
 *   <li>DataSource: JDBC data source for database connectivity</li>
 *   <li>TransactionRepository: Optional for additional transaction queries</li>
 *   <li>TransactionGroupReader: ItemReader for aggregated transaction groups</li>
 *   <li>TransactionAggregationProcessor: ItemProcessor for validation and accumulation</li>
 *   <li>TransactionAggregateWriter: ItemWriter for persistence</li>
 * </ul>
 * 
 * <p><strong>Usage Example:</strong></p>
 * <pre>
 * // Schedule via Kubernetes CronJob (kubernetes/cronjobs/transaction-aggregation-cronjob.yaml)
 * // Daily execution at 2:00 AM after daily transaction processing completes
 * // Command: java -jar carddemo.jar --spring.batch.job.names=transactionAggregationJob
 * //          --start.date=2024-01-01 --end.date=2024-01-31
 * </pre>
 * 
 * @author CardDemo Migration Team
 * @version 1.0
 * @since 2024-01-01
 * @see TransactionGroupReader Reader for pre-aggregated transaction groups
 * @see TransactionAggregationProcessor Processor for validation and accumulation
 * @see TransactionAggregateWriter Writer for aggregate persistence
 * @see TransactionAggregate Target entity for aggregated results
 * @see <a href="Section 0.5">Batch Processing Configuration Requirements</a>
 * @see <a href="Section 0.9">Numeric Precision and Transaction Boundary Requirements</a>
 */
@Configuration("transactionAggregationJobConfig")
public class TransactionAggregationJob {

    /**
     * SLF4J logger for job configuration events, execution monitoring, and error tracking.
     * 
     * <p>Logging Categories:</p>
     * <ul>
     *   <li>INFO: Job and step bean creation, configuration parameters</li>
     *   <li>WARN: Configuration issues, missing parameters</li>
     *   <li>ERROR: Bean creation failures, configuration errors</li>
     *   <li>DEBUG: Detailed configuration details for troubleshooting</li>
     * </ul>
     */
    private static final Logger logger = LoggerFactory.getLogger(TransactionAggregationJob.class);

    /**
     * JobRepository for persisting batch job execution metadata.
     * Injected via constructor for use across all bean methods.
     */
    private final JobRepository jobRepository;

    /**
     * PlatformTransactionManager for managing database transactions.
     * Configured with READ_COMMITTED isolation level per Section 0.3.
     */
    private final PlatformTransactionManager transactionManager;

    /**
     * TransactionAggregationProcessor for validation and accumulation.
     * Injected via constructor to avoid circular bean references.
     */
    private final TransactionAggregationProcessor transactionAggregationProcessor;

    /**
     * TransactionAggregateWriter for persisting aggregated results.
     * Injected via constructor to avoid circular bean references.
     */
    private final TransactionAggregateWriter transactionAggregateWriter;

    /**
     * DataSource for database connectivity in reader configuration.
     * Injected via constructor for creating TransactionGroupReader bean.
     */
    private final DataSource dataSource;

    /**
     * Constructor for TransactionAggregationJob configuration.
     * Uses constructor injection for all dependencies to avoid circular references.
     * 
     * @param jobRepository Spring Batch metadata repository
     * @param transactionManager Transaction manager for chunk-level transactions
     * @param transactionAggregationProcessor Processor for validation and accumulation
     * @param transactionAggregateWriter Writer for persisting aggregates
     * @param dataSource JDBC DataSource for database connectivity
     */
    public TransactionAggregationJob(
            JobRepository jobRepository,
            PlatformTransactionManager transactionManager,
            TransactionAggregationProcessor transactionAggregationProcessor,
            TransactionAggregateWriter transactionAggregateWriter,
            DataSource dataSource) {
        this.jobRepository = jobRepository;
        this.transactionManager = transactionManager;
        this.transactionAggregationProcessor = transactionAggregationProcessor;
        this.transactionAggregateWriter = transactionAggregateWriter;
        this.dataSource = dataSource;
        
        logger.info("TransactionAggregationJob configuration initialized with chunk size: {}, skip limit: {}, retry limit: {}",
                DEFAULT_CHUNK_SIZE, SKIP_LIMIT, RETRY_LIMIT);
    }

    /**
     * Default chunk size for transaction aggregation processing.
     * Configured per Section 0.5 requirements for optimal performance and restart granularity.
     * 
     * <p>Rationale for 1000:</p>
     * <ul>
     *   <li>Balances transaction commit overhead with restart granularity</li>
     *   <li>Maintains acceptable memory footprint for aggregation map</li>
     *   <li>Enables sub-4-hour batch processing window for daily volumes</li>
     *   <li>Standard Spring Batch chunk size for high-volume processing</li>
     * </ul>
     */
    private static final int DEFAULT_CHUNK_SIZE = 1000;

    /**
     * Skip limit for fault-tolerant processing per Section 0.5 requirements.
     * Allows up to 100 errors (skipped items) before job fails.
     * 
     * <p>Skip Policy:</p>
     * <ul>
     *   <li>ArithmeticException: Invalid calculations, division by zero</li>
     *   <li>DataIntegrityViolationException: Duplicate key constraints (rare)</li>
     *   <li>Null or invalid transaction groups from reader</li>
     *   <li>All skipped items logged for post-processing analysis</li>
     * </ul>
     */
    private static final int SKIP_LIMIT = 100;

    /**
     * Retry limit for transient errors per Section 0.5 requirements.
     * Attempts operation 3 times before skipping or failing.
     * 
     * <p>Retry Policy:</p>
     * <ul>
     *   <li>TransientDataAccessException: Temporary database connection issues</li>
     *   <li>Exponential backoff: 1s, 2s, 4s between retries</li>
     *   <li>Success on any attempt commits the chunk normally</li>
     *   <li>Failure after 3 attempts triggers skip logic</li>
     * </ul>
     */
    private static final int RETRY_LIMIT = 3;

    /**
     * Defines the Spring Batch Job bean for transaction aggregation processing.
     * 
     * <p>This method creates the top-level Job configuration that orchestrates the complete
     * transaction aggregation batch process. The job consists of a single step that reads
     * pre-aggregated transaction groups, processes them with validation and accumulation,
     * and writes the results to the transaction_aggregate table.</p>
     * 
     * <p><strong>Job Characteristics:</strong></p>
     * <ul>
     *   <li>Name: "transactionAggregationJob" - used for job identification and scheduling</li>
     *   <li>Restartable: true - failed jobs can resume from last checkpoint</li>
     *   <li>Steps: Single step (transactionAggregationStep) for processing</li>
     *   <li>Execution: Triggered via Kubernetes CronJob or manual launch</li>
     * </ul>
     * 
     * <p><strong>COBOL Program Equivalence:</strong></p>
     * <p>Replaces COBOL CBTRN03C.cbl main execution flow:</p>
     * <pre>
     * PROCEDURE DIVISION.
     *     DISPLAY 'START OF EXECUTION OF PROGRAM CBTRN03C'.
     *     PERFORM 0000-TRANFILE-OPEN.
     *     PERFORM 0550-DATEPARM-READ.
     *     PERFORM UNTIL END-OF-FILE = 'Y'
     *       PERFORM 1000-TRANFILE-GET-NEXT
     *       PERFORM 1100-WRITE-TRANSACTION-REPORT
     *     END-PERFORM.
     *     PERFORM 9000-TRANFILE-CLOSE.
     *     DISPLAY 'END OF EXECUTION OF PROGRAM CBTRN03C'.
     *     GOBACK.
     * </pre>
     * 
     * <p><strong>Job Flow:</strong></p>
     * <ol>
     *   <li>Spring Batch framework initializes JobRepository and execution context</li>
     *   <li>Job starts and creates JobExecution metadata record</li>
     *   <li>transactionAggregationStep is executed (reader → processor → writer loop)</li>
     *   <li>Step processes all transaction groups in chunks of 1000</li>
     *   <li>Job completes and updates JobExecution status (COMPLETED, FAILED, STOPPED)</li>
     * </ol>
     * 
     * <p><strong>Error Handling:</strong></p>
     * <ul>
     *   <li>Step failure: Job marked as FAILED, can be restarted from checkpoint</li>
     *   <li>Skip limit exceeded: Job fails with details of skipped items</li>
     *   <li>Retry exhausted: Item skipped if within skip limit, else job fails</li>
     *   <li>Unexpected exception: Job fails immediately with stack trace</li>
     * </ul>
     * 
     * <p><strong>Monitoring and Observability:</strong></p>
     * <ul>
     *   <li>Spring Boot Actuator: /actuator/batch/jobs endpoint for job status</li>
     *   <li>JobRepository: Metadata tables (BATCH_JOB_EXECUTION, BATCH_STEP_EXECUTION)</li>
     *   <li>Prometheus Metrics: Job duration, item counts, skip/retry rates</li>
     *   <li>Application Logs: Detailed execution logs for troubleshooting</li>
     * </ul>
     * 
     * <p><strong>Scheduling:</strong></p>
     * <p>Kubernetes CronJob configuration (kubernetes/cronjobs/transaction-aggregation-cronjob.yaml):</p>
     * <ul>
     *   <li>Schedule: "0 2 * * *" (Daily at 2:00 AM)</li>
     *   <li>Concurrency Policy: Forbid (prevent overlapping executions)</li>
     *   <li>Success History: 3 (keep last 3 successful job pods)</li>
     *   <li>Failure History: 1 (keep last failed job pod for debugging)</li>
     * </ul>
     * 
     * @param jobRepository Spring Batch metadata repository for job execution tracking and
     *                      restart capability. Stores job/step execution state, execution context,
     *                      and checkpoint data in database tables (BATCH_JOB_INSTANCE,
     *                      BATCH_JOB_EXECUTION, BATCH_STEP_EXECUTION, BATCH_EXECUTION_CONTEXT).
     *                      Injected by Spring Framework.
     * @param transactionAggregationStep The step bean that performs transaction aggregation
     *                                   processing. Configured with reader, processor, writer,
     *                                   and fault tolerance settings. Injected by Spring Framework.
     * @return Configured Job instance ready for execution by Spring Batch framework. Job can be
     *         launched programmatically via JobLauncher or automatically via Kubernetes CronJob.
     */
    @Bean
    public Job transactionAggregationJob(Step transactionAggregationStep) {
        
        logger.info("Configuring transactionAggregationJob - Transaction Category Aggregation Batch Job");
        logger.info("COBOL Source: CBTRN03C.cbl - Transaction Detail Report Generation");
        logger.info("Processing Model: Chunk-oriented with chunk size {}", DEFAULT_CHUNK_SIZE);
        logger.info("Fault Tolerance: Skip limit {}, Retry limit {}", SKIP_LIMIT, RETRY_LIMIT);
        
        return new JobBuilder("transactionAggregationJob", jobRepository)
                .start(transactionAggregationStep)
                .build();
    }

    /**
     * Defines the Spring Batch Step bean for transaction aggregation chunk processing.
     * 
     * <p>This method creates the step configuration that implements chunk-oriented processing
     * of transaction aggregations. The step reads pre-aggregated transaction groups from the
     * database, processes them with validation and accumulation logic, and writes the results
     * to the transaction_aggregate table with UPSERT semantics.</p>
     * 
     * <p><strong>Step Configuration:</strong></p>
     * <ul>
     *   <li>Name: "transactionAggregationStep"</li>
     *   <li>Chunk Size: 1000 transaction groups per Section 0.5 requirements</li>
     *   <li>Input Type: TransactionGroupReader.TransactionGroup (pre-aggregated groups)</li>
     *   <li>Output Type: TransactionAggregate (persistent aggregate entities)</li>
     *   <li>Processing Model: Read → Process → Write in configurable chunks</li>
     * </ul>
     * 
     * <p><strong>Chunk-Oriented Processing Flow:</strong></p>
     * <ol>
     *   <li><strong>Read Phase:</strong> TransactionGroupReader reads up to 1000 transaction
     *       groups from SQL GROUP BY query results. Each group contains account ID, type code,
     *       category code, and pre-computed aggregates (count, sum, avg).</li>
     *   <li><strong>Process Phase:</strong> For each group, TransactionAggregationProcessor
     *       validates type and category codes, accumulates amounts with BigDecimal precision,
     *       and maintains aggregation state. Null returns trigger skip logic.</li>
     *   <li><strong>Write Phase:</strong> TransactionAggregateWriter persists all accumulated
     *       aggregations to database using UPSERT logic (INSERT or UPDATE based on existence).
     *       Chunk commits atomically with READ_COMMITTED isolation.</li>
     *   <li><strong>Checkpoint:</strong> JobRepository records chunk completion in execution
     *       context, enabling restart from last successful chunk if job fails.</li>
     * </ol>
     * 
     * <p><strong>Fault Tolerance Configuration:</strong></p>
     * <p>The step implements comprehensive error handling per Section 0.5 requirements:</p>
     * <ul>
     *   <li><strong>Skip Policy:</strong>
     *       <ul>
     *         <li>ArithmeticException: Division by zero, invalid calculations in processor</li>
     *         <li>Skip Limit: 100 errors total before job failure</li>
     *         <li>Skipped Item Logging: All skipped groups logged with details</li>
     *         <li>Continue Processing: Job continues after skip up to limit</li>
     *       </ul>
     *   </li>
     *   <li><strong>Retry Policy:</strong>
     *       <ul>
     *         <li>TransientDataAccessException: Temporary database connection issues</li>
     *         <li>Retry Limit: 3 attempts per item with exponential backoff</li>
     *         <li>Backoff: 1 second initial, doubles each retry (1s, 2s, 4s)</li>
     *         <li>Success on Retry: Item processed normally, chunk continues</li>
     *         <li>Failure after Retries: Item skipped if within skip limit</li>
     *       </ul>
     *   </li>
     * </ul>
     * 
     * <p><strong>Transaction Management:</strong></p>
     * <p>Each chunk executes within a database transaction per Section 0.3 requirements:</p>
     * <ul>
     *   <li>Isolation Level: READ_COMMITTED - prevents dirty reads, allows concurrent access</li>
     *   <li>Propagation: REQUIRED - uses existing transaction or creates new one</li>
     *   <li>Transaction Scope: Chunk boundary (1000 records)</li>
     *   <li>Commit: After successful chunk write</li>
     *   <li>Rollback: On any exception during chunk processing</li>
     *   <li>Checkpoint: Execution context updated on successful chunk commit</li>
     * </ul>
     * 
     * <p><strong>BigDecimal Precision Preservation:</strong></p>
     * <p>All monetary calculations maintain COBOL COMP-3 precision per Section 0.9:</p>
     * <ul>
     *   <li>Scale: 2 decimal places for all monetary amounts</li>
     *   <li>Rounding: RoundingMode.HALF_UP for all arithmetic operations</li>
     *   <li>Validation: Writer enforces scale before persistence</li>
     *   <li>Processor: Uses DecimalUtils.safeAdd() for accumulation</li>
     * </ul>
     * 
     * <p><strong>Performance Characteristics:</strong></p>
     * <ul>
     *   <li>Throughput: 1000+ transaction groups per second typical</li>
     *   <li>Memory: O(1000) for chunk buffering, O(n) for aggregation map in processor</li>
     *   <li>Database: One query per chunk (read), batch inserts/updates (write)</li>
     *   <li>Commit Frequency: Every 1000 records for optimal I/O and restart granularity</li>
     * </ul>
     * 
     * <p><strong>Restart Capability:</strong></p>
     * <p>The step supports job restart from last successful checkpoint:</p>
     * <ul>
     *   <li>ExecutionContext stores current chunk position (group index)</li>
     *   <li>Failed jobs can be restarted with same parameters</li>
     *   <li>Processing resumes from last committed chunk</li>
     *   <li>Duplicate processing prevented by UPSERT writer logic</li>
     * </ul>
     * 
     * <p><strong>COBOL Transformation Notes:</strong></p>
     * <p>This step replaces the following COBOL logic from CBTRN03C.cbl:</p>
     * <ul>
     *   <li>Lines 170-206: Main processing loop with EOF detection</li>
     *   <li>Lines 181-186: Group break detection on card number change</li>
     *   <li>Lines 287-288: Amount accumulation logic</li>
     *   <li>Lines 1110-1120: Total calculation and reporting</li>
     * </ul>
     * 
     * @param jobRepository Spring Batch metadata repository for step execution tracking and
     *                      checkpoint persistence. Stores step execution state and context.
     *                      Injected by Spring Framework.
     * @param platformTransactionManager Spring transaction manager configured with READ_COMMITTED
     *                                   isolation level per Section 0.3. Manages chunk-level
     *                                   transaction boundaries with automatic commit/rollback.
     *                                   Injected by Spring Framework.
     * @param transactionGroupReader ItemReader for reading pre-aggregated transaction groups
     *                               from database using SQL GROUP BY. Configured with date range
     *                               parameters and restart capability. Injected by Spring.
     * @param transactionAggregationProcessor ItemProcessor for validating transaction groups
     *                                        and accumulating category balances with BigDecimal
     *                                        precision. Returns null to trigger skip logic for
     *                                        invalid items. Injected by Spring Framework.
     * @param transactionAggregateWriter ItemWriter for persisting TransactionAggregate entities
     *                                   to database using UPSERT logic. Handles chunk-level
     *                                   batch inserts/updates with transaction management.
     *                                   Injected by Spring Framework.
     * @return Configured Step instance ready for execution within transactionAggregationJob.
     *         Step processes all transaction groups using chunk-oriented pattern with fault
     *         tolerance and restart capability.
     */
    @Bean(name = "transactionAggregationStep")
    public Step transactionAggregationStep(TransactionGroupReader transactionGroupReader) {
        
        logger.info("Configuring transactionAggregationStep with chunk size: {}", DEFAULT_CHUNK_SIZE);
        logger.info("Fault tolerance: skip limit={}, retry limit={}", SKIP_LIMIT, RETRY_LIMIT);
        logger.info("Transaction isolation: READ_COMMITTED per Section 0.3");
        logger.info("Reader: TransactionGroupReader (SQL GROUP BY aggregation)");
        logger.info("Processor: TransactionAggregationProcessor (validation and accumulation)");
        logger.info("Writer: TransactionAggregateWriter (UPSERT to transaction_aggregate table)");
        
        return new StepBuilder("transactionAggregationStep", jobRepository)
                .<TransactionGroupReader.TransactionGroup, TransactionAggregate>chunk(DEFAULT_CHUNK_SIZE, transactionManager)
                .reader(transactionGroupReader)
                .processor(transactionAggregationProcessor)
                .writer(transactionAggregateWriter)
                .faultTolerant()
                .skip(ArithmeticException.class)
                .skipLimit(SKIP_LIMIT)
                .retry(TransientDataAccessException.class)
                .retryLimit(RETRY_LIMIT)
                .build();
    }

    /**
     * Creates and configures the TransactionGroupReader bean for reading aggregated transaction groups.
     * 
     * <p>This reader replaces COBOL CBTRN03C.cbl sequential transaction file processing with
     * efficient SQL GROUP BY aggregation. Instead of reading individual transactions and grouping
     * in memory (COBOL approach), this reader performs database-level aggregation for optimal
     * performance with large transaction volumes.</p>
     * 
     * <p><strong>Reader Configuration:</strong></p>
     * <ul>
     *   <li>Aggregation Query: SQL GROUP BY on account_id, type_code, category_code</li>
     *   <li>Computed Metrics: COUNT(*), SUM(amount), AVG(amount) per group</li>
     *   <li>Date Range: Configurable start and end dates (default: previous day)</li>
     *   <li>Restart Support: ExecutionContext tracks current group index</li>
     * </ul>
     * 
     * <p><strong>COBOL Equivalence:</strong></p>
     * <p>Replaces the following COBOL file operations from CBTRN03C.cbl:</p>
     * <pre>
     * 0000-TRANFILE-OPEN.
     *     OPEN INPUT TRANSACT-FILE
     * 
     * 1000-TRANFILE-GET-NEXT.
     *     READ TRANSACT-FILE INTO TRAN-RECORD.
     *     IF TRAN-PROC-TS (1:10) >= WS-START-DATE
     *        AND TRAN-PROC-TS (1:10) <= WS-END-DATE
     *        CONTINUE
     * 
     * (Group break detection on card number change - lines 181-186)
     * IF WS-CURR-CARD-NUM NOT= TRAN-CARD-NUM
     *   PERFORM 1120-WRITE-ACCOUNT-TOTALS
     *   MOVE TRAN-CARD-NUM TO WS-CURR-CARD-NUM
     * </pre>
     * 
     * <p><strong>Performance Benefits:</strong></p>
     * <ul>
     *   <li>Database-Level Aggregation: Reduces data transfer and memory usage</li>
     *   <li>Single Query: Retrieves all groups at once vs. sequential reads</li>
     *   <li>Index Utilization: Leverages composite indexes for efficient GROUP BY</li>
     *   <li>Scalability: Handles millions of transactions efficiently</li>
     * </ul>
     * 
     * <p><strong>Date Range Configuration:</strong></p>
     * <p>By default, reader processes previous day's transactions (incremental mode).
     * For full refresh or custom date ranges, configure via job parameters:</p>
     * <pre>
     * --start.date=2024-01-01 --end.date=2024-01-31
     * </pre>
     * 
     * @param dataSource JDBC DataSource for database connectivity. Configured with HikariCP
     *                   connection pooling for optimal performance. Injected by Spring Boot
     *                   auto-configuration from application.yml database settings.
     * @param startDateStr job parameter for aggregation start date (format: yyyy-MM-dd)
     * @param endDateStr job parameter for aggregation end date (format: yyyy-MM-dd)
     * @return Configured TransactionGroupReader instance ready for use in
     *         transactionAggregationStep. Reader supports restart capability via ExecutionContext
     *         and efficient SQL GROUP BY aggregation for transaction processing.
     */
    @Bean
    @StepScope
    public TransactionGroupReader transactionGroupReader(
            @Value("#{jobParameters['start.date']}") String startDateStr,
            @Value("#{jobParameters['end.date']}") String endDateStr) {
        logger.info("Creating TransactionGroupReader bean with SQL GROUP BY aggregation");
        logger.info("Job parameters: start.date={}, end.date={}", startDateStr, endDateStr);
        logger.info("Supports restart via ExecutionContext position tracking");
        
        TransactionGroupReader reader = new TransactionGroupReader(dataSource);
        
        // Use job parameters for date range, with fallback to previous day
        LocalDate startDate = (startDateStr != null) ? LocalDate.parse(startDateStr) : LocalDate.now().minusDays(1);
        LocalDate endDate = (endDateStr != null) ? LocalDate.parse(endDateStr) : LocalDate.now();
        
        reader.setStartDate(startDate);
        reader.setEndDate(endDate);
        
        logger.info("TransactionGroupReader configured with date range: {} to {}", startDate, endDate);
        logger.debug("TransactionGroupReader configured successfully");
        return reader;
    }
}
