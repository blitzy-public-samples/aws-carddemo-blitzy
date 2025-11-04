/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.carddemo.batch.job;

import com.carddemo.batch.reader.CardItemReader;
import com.carddemo.batch.writer.CardItemWriter;
import com.carddemo.config.BatchConfig;
import com.carddemo.entity.Card;
import jakarta.validation.ValidationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.Step;
import org.springframework.batch.core.job.builder.JobBuilder;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.core.step.builder.StepBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.dao.DataAccessException;
import org.springframework.retry.RetryPolicy;
import org.springframework.retry.backoff.BackOffPolicy;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * Spring Batch job configuration for card data loading from database to processing pipeline.
 * 
 * <p>This class transforms the COBOL batch program CBCRD01C.cbl from sequential VSAM file
 * processing to modern Spring Batch chunk-oriented processing with database persistence.</p>
 * 
 * <p><strong>COBOL Source Transformation:</strong></p>
 * <ul>
 *   <li><strong>COBOL Program:</strong> app/cbl/CBCRD01C.cbl</li>
 *   <li><strong>Program Function:</strong> Read and process card data file with account relationship validation</li>
 *   <li><strong>VSAM File:</strong> CARDFILE (CARDDAT KSDS - Key-Sequenced Dataset)</li>
 *   <li><strong>Access Mode:</strong> Sequential read with indexed organization</li>
 *   <li><strong>Record Structure:</strong> CARD-RECORD (COPY CVCRD01Y)</li>
 * </ul>
 * 
 * <p><strong>Spring Batch Transformation Strategy:</strong></p>
 * <ul>
 *   <li><code>OPEN CARDFILE</code> → CardItemReader.open() initialization</li>
 *   <li><code>PERFORM UNTIL END-OF-FILE</code> → Spring Batch chunk loop framework</li>
 *   <li><code>READ CARDFILE</code> → CardItemReader.read() method</li>
 *   <li><code>WRITE OUTPUT</code> → CardItemWriter.write() persistence</li>
 *   <li><code>CLOSE CARDFILE</code> → CardItemReader.close() cleanup</li>
 *   <li>COBOL file-status checking → Spring Batch exception handling with retry/skip</li>
 * </ul>
 * 
 * <p><strong>Batch Processing Configuration:</strong></p>
 * <ul>
 *   <li><strong>Chunk Size:</strong> 1000 records per transaction (Section 0.5)</li>
 *   <li><strong>Skip Limit:</strong> 100 errors before job failure (Section 0.5)</li>
 *   <li><strong>Retry Policy:</strong> 3 attempts with exponential backoff (Section 0.5)</li>
 *   <li><strong>Transaction Isolation:</strong> READ_COMMITTED (Section 0.3)</li>
 *   <li><strong>Concurrency:</strong> Single-threaded sequential processing</li>
 * </ul>
 * 
 * <p><strong>Foreign Key Validation:</strong></p>
 * <ul>
 *   <li>Validates card.account_id foreign key constraint to account table</li>
 *   <li>DataIntegrityViolationException thrown for orphaned card records</li>
 *   <li>Skips invalid records up to configured skip limit</li>
 * </ul>
 * 
 * <p><strong>Checkpoint/Restart Capability:</strong></p>
 * <ul>
 *   <li>JobRepository stores execution context after each chunk commit</li>
 *   <li>ExecutionContext persists current page number and last card ID</li>
 *   <li>On job restart, reader resumes from last successfully processed card</li>
 *   <li>Equivalent to COBOL batch checkpoint intervals for failure recovery</li>
 * </ul>
 * 
 * <p><strong>Performance Characteristics:</strong></p>
 * <ul>
 *   <li>Processes card records within 4-hour batch window requirement</li>
 *   <li>Database connection pooling via HikariCP (20-50 connections)</li>
 *   <li>Memory-efficient with chunk-based commit intervals</li>
 * </ul>
 * 
 * @see CardItemReader
 * @see CardItemWriter
 * @see Card
 * @see BatchConfig
 * @see <a href="Section 0.3">Transaction Semantics Preservation</a>
 * @see <a href="Section 0.5">Batch Configuration Requirements</a>
 * @see <a href="Section 0.6">File-by-File Transformation Plan</a>
 * @since 1.0
 */
@Configuration
public class CardDataLoadJob {

    private static final Logger logger = LoggerFactory.getLogger(CardDataLoadJob.class);

    /**
     * Job name constant for card data loading.
     * Used for job identification in JobRepository and Kubernetes CronJob configuration.
     */
    private static final String JOB_NAME = "cardDataLoadJob";
    
    /**
     * Bean name for the card data load job bean.
     * Different from JOB_NAME to avoid conflicts with @Configuration class naming.
     */
    private static final String JOB_BEAN_NAME = "cardDataLoadJobBean";

    /**
     * Step name constant for card data loading step.
     * Used for step identification in execution metrics and logging.
     */
    private static final String STEP_NAME = "cardDataLoadStep";

    private final JobRepository jobRepository;
    private final PlatformTransactionManager transactionManager;
    private final CardItemReader cardItemReader;
    private final CardItemWriter cardItemWriter;
    private final RetryPolicy retryPolicy;
    private final BackOffPolicy backOffPolicy;

    /**
     * Constructs a new CardDataLoadJob with required dependencies.
     * 
     * <p>All dependencies are injected via constructor for immutability and testability.
     * This follows Spring Framework best practices and enables proper dependency management.</p>
     * 
     * @param jobRepository Spring Batch job repository for execution metadata persistence
     * @param transactionManager Platform transaction manager for chunk-level transaction boundaries
     * @param cardItemReader ItemReader implementation for reading card records from database
     * @param cardItemWriter ItemWriter implementation for writing card records
     * @param retryPolicy Retry policy for transient errors (3 attempts per Section 0.5)
     * @param backOffPolicy Exponential backoff policy for retry intervals
     */
    public CardDataLoadJob(
            JobRepository jobRepository,
            PlatformTransactionManager transactionManager,
            CardItemReader cardItemReader,
            CardItemWriter cardItemWriter,
            RetryPolicy retryPolicy,
            BackOffPolicy backOffPolicy) {
        
        this.jobRepository = jobRepository;
        this.transactionManager = transactionManager;
        this.cardItemReader = cardItemReader;
        this.cardItemWriter = cardItemWriter;
        this.retryPolicy = retryPolicy;
        this.backOffPolicy = backOffPolicy;
        
        logger.info("Initialized CardDataLoadJob configuration");
    }

    /**
     * Defines the card data load Job bean.
     * 
     * <p>This method creates the Spring Batch Job that orchestrates the card data loading process.
     * The job consists of a single step that reads card records from the database using paginated
     * queries and writes them to the target destination with foreign key validation.</p>
     * 
     * <p><strong>Job Configuration:</strong></p>
     * <ul>
     *   <li>Job Name: "cardDataLoadJob" (maps to CBCRD01C program ID)</li>
     *   <li>Job Repository: Database-backed for execution tracking and restart capability</li>
     *   <li>Start Step: cardDataLoadStep (sequential execution)</li>
     *   <li>Restart: Enabled via ExecutionContext state persistence</li>
     * </ul>
     * 
     * <p><strong>Execution Flow:</strong></p>
     * <ol>
     *   <li>Job launcher creates JobExecution instance in JobRepository</li>
     *   <li>Job starts cardDataLoadStep</li>
     *   <li>Step processes card records in 1000-record chunks</li>
     *   <li>Each chunk is committed as a separate transaction</li>
     *   <li>ExecutionContext is updated after each chunk for restart capability</li>
     *   <li>Job completes when step returns COMPLETED status</li>
     *   <li>Final job status (COMPLETED/FAILED) persisted to JobRepository</li>
     * </ol>
     * 
     * <p><strong>Restart Behavior:</strong></p>
     * <ul>
     *   <li>If job fails mid-execution, ExecutionContext contains last committed position</li>
     *   <li>On restart, step resumes from last successfully processed card ID</li>
     *   <li>Already-processed records are skipped to avoid duplicate processing</li>
     *   <li>Equivalent to COBOL batch checkpoint/restart mechanism</li>
     * </ul>
     * 
     * @return Job instance configured for card data loading
     * @see JobBuilder
     * @see JobRepository
     * @see <a href="Section 0.5">Batch Job Configuration</a>
     */
    @Bean(name = JOB_BEAN_NAME)
    public Job cardDataLoadJobBean() {
        logger.info("Building {} - Card data load batch job", JOB_NAME);
        
        return new JobBuilder(JOB_NAME, jobRepository)
                .start(cardDataLoadStep())
                .listener(new CardDataLoadJobExecutionListener())
                .build();
    }

    /**
     * Defines the card data load Step bean.
     * 
     * <p>This method creates the Spring Batch Step that performs the actual card data processing.
     * The step uses chunk-oriented processing with configurable reader, processor (none for this job),
     * and writer components.</p>
     * 
     * <p><strong>Step Configuration:</strong></p>
     * <ul>
     *   <li><strong>Step Name:</strong> "cardDataLoadStep"</li>
     *   <li><strong>Chunk Size:</strong> 1000 records per transaction (Section 0.5)</li>
     *   <li><strong>Reader:</strong> CardItemReader (database cursor-based pagination)</li>
     *   <li><strong>Processor:</strong> None (passthrough)</li>
     *   <li><strong>Writer:</strong> CardItemWriter (batch persistence to database)</li>
     *   <li><strong>Transaction Manager:</strong> Platform transaction manager with READ_COMMITTED</li>
     * </ul>
     * 
     * <p><strong>Fault Tolerance Configuration:</strong></p>
     * <ul>
     *   <li><strong>Skip Limit:</strong> 100 errors before job failure (Section 0.5)</li>
     *   <li><strong>Skippable Exceptions:</strong> ValidationException, DataAccessException</li>
     *   <li><strong>Retry Limit:</strong> 3 attempts per record (Section 0.5)</li>
     *   <li><strong>Retryable Exceptions:</strong> DataAccessException (transient database errors)</li>
     *   <li><strong>Backoff Policy:</strong> Exponential (1s → 2s → 4s → 8s, max 10s)</li>
     * </ul>
     * 
     * <p><strong>Transaction Boundaries:</strong></p>
     * <ul>
     *   <li>Each chunk of 1000 records is processed in a single transaction</li>
     *   <li>Transaction commits after successful write of entire chunk</li>
     *   <li>Transaction rolls back on exception if retry/skip limits exceeded</li>
     *   <li>Isolation level: READ_COMMITTED (prevents dirty reads, allows repeatable reads)</li>
     * </ul>
     * 
     * @return Step instance configured with chunk-oriented processing
     * @see StepBuilder
     * @see CardItemReader
     * @see CardItemWriter
     * @see <a href="Section 0.5">Batch Step Configuration</a>
     * @see <a href="Section 0.9">Error Handling Requirements</a>
     */
    @Bean(name = STEP_NAME)
    public Step cardDataLoadStep() {
        logger.info("Building {} - Card data load processing step with chunk size {}", 
                    STEP_NAME, BatchConfig.CHUNK_SIZE);
        
        return new StepBuilder(STEP_NAME, jobRepository)
                .<Card, Card>chunk(BatchConfig.CHUNK_SIZE, transactionManager)
                .reader(cardItemReader)
                .writer(cardItemWriter)
                .faultTolerant()
                .skipLimit(BatchConfig.SKIP_LIMIT)
                .skip(ValidationException.class)
                .skip(DataAccessException.class)
                .retryLimit(BatchConfig.MAX_RETRY_ATTEMPTS)
                .retry(DataAccessException.class)
                .retryPolicy(retryPolicy)
                .backOffPolicy(backOffPolicy)
                .listener(new CardDataLoadStepExecutionListener())
                .build();
    }

    /**
     * Job execution listener for card data load job.
     * 
     * <p>This listener provides job-level logging and monitoring, equivalent to COBOL
     * DISPLAY statements at the beginning and end of program execution.</p>
     */
    private static class CardDataLoadJobExecutionListener 
            implements org.springframework.batch.core.JobExecutionListener {
        
        private static final Logger logger = LoggerFactory.getLogger(CardDataLoadJobExecutionListener.class);

        /**
         * Called before job execution starts.
         * 
         * <p>Logs job start event with job parameters for audit trail and troubleshooting.</p>
         * 
         * @param jobExecution the job execution context
         */
        @Override
        public void beforeJob(org.springframework.batch.core.JobExecution jobExecution) {
            logger.info("========================================");
            logger.info("START OF EXECUTION OF CARD DATA LOAD JOB");
            logger.info("Job Name: {}", jobExecution.getJobInstance().getJobName());
            logger.info("Job Instance ID: {}", jobExecution.getJobInstance().getId());
            logger.info("Job Execution ID: {}", jobExecution.getId());
            logger.info("Job Parameters: {}", jobExecution.getJobParameters());
            logger.info("========================================");
        }

        /**
         * Called after job execution completes.
         * 
         * <p>Logs job completion status, duration, and processing statistics for monitoring.</p>
         * 
         * @param jobExecution the job execution context
         */
        @Override
        public void afterJob(org.springframework.batch.core.JobExecution jobExecution) {
            logger.info("========================================");
            logger.info("END OF EXECUTION OF CARD DATA LOAD JOB");
            logger.info("Job Status: {}", jobExecution.getStatus());
            logger.info("Job Exit Status: {}", jobExecution.getExitStatus().getExitCode());
            logger.info("Job Duration: {} ms", 
                java.time.Duration.between(
                    jobExecution.getStartTime(), 
                    jobExecution.getEndTime()
                ).toMillis());
            logger.info("========================================");
        }
    }

    /**
     * Step execution listener for card data load step.
     * 
     * <p>This listener provides step-level statistics logging for monitoring and troubleshooting.</p>
     */
    private static class CardDataLoadStepExecutionListener 
            implements org.springframework.batch.core.StepExecutionListener {
        
        private static final Logger logger = LoggerFactory.getLogger(CardDataLoadStepExecutionListener.class);

        /**
         * Called after step execution completes.
         * 
         * <p>Logs step processing statistics including read/write/skip counts.</p>
         * 
         * @param stepExecution the step execution context
         * @return ExitStatus to indicate step completion status
         */
        @Override
        public org.springframework.batch.core.ExitStatus afterStep(org.springframework.batch.core.StepExecution stepExecution) {
            logger.info("Card data load step completed:");
            logger.info("  Read Count: {}", stepExecution.getReadCount());
            logger.info("  Write Count: {}", stepExecution.getWriteCount());
            logger.info("  Skip Count: {}", stepExecution.getSkipCount());
            logger.info("  Commit Count: {}", stepExecution.getCommitCount());
            logger.info("  Rollback Count: {}", stepExecution.getRollbackCount());
            return stepExecution.getExitStatus();
        }
    }
}
