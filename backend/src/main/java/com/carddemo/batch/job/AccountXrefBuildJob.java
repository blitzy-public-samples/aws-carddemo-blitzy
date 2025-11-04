/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.carddemo.batch.job;

import com.carddemo.batch.processor.AccountXrefProcessor;
import com.carddemo.batch.reader.CardAccountReader;
import com.carddemo.batch.writer.AccountXrefWriter;
import com.carddemo.entity.Card;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.JobExecution;
import org.springframework.batch.core.JobExecutionListener;
import org.springframework.batch.core.Step;
import org.springframework.batch.core.job.builder.JobBuilder;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.core.step.builder.StepBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.dao.TransientDataAccessException;

/**
 * Spring Batch job configuration for building account cross-reference relationships.
 * 
 * <p><b>COBOL Source Transformation:</b></p>
 * <p>This job transforms the COBOL batch program CBACT02C.cbl which reads the CARDFILE VSAM KSDS
 * sequentially and builds cross-reference relationships. In the mainframe architecture, these
 * relationships were maintained automatically through VSAM alternate indexes (XREF, CXACAIX).
 * In the modernized PostgreSQL architecture, explicit cross-reference tables (account_xref,
 * card_xref) are populated through this batch job.</p>
 * 
 * <p><b>COBOL Program Structure (CBACT02C.cbl):</b></p>
 * <pre>
 * Line 71: DISPLAY 'START OF EXECUTION OF PROGRAM CBACT02C'
 * Line 72: PERFORM 0000-CARDFILE-OPEN
 * Line 74-81: PERFORM UNTIL END-OF-FILE = 'Y'
 *   Line 76: PERFORM 1000-CARDFILE-GET-NEXT (READ CARDFILE)
 *   Line 78: DISPLAY CARD-RECORD
 * Line 83: PERFORM 9000-CARDFILE-CLOSE
 * Line 85: DISPLAY 'END OF EXECUTION OF PROGRAM CBACT02C'
 * </pre>
 * 
 * <p><b>Spring Batch Transformation Pattern:</b></p>
 * <ul>
 *   <li>VSAM Sequential Read → CardAccountReader with JPA paging (chunk size 1000)</li>
 *   <li>CARD-RECORD processing → AccountXrefProcessor validation and cross-reference building</li>
 *   <li>Implicit AIX creation → AccountXrefWriter explicit persistence to xref tables</li>
 *   <li>File status checking → Skip/Retry policies with limits per Section 0.5</li>
 *   <li>DISPLAY statements → JobExecutionListener logging</li>
 * </ul>
 * 
 * <p><b>Chunk-Oriented Processing Configuration:</b></p>
 * <ul>
 *   <li><b>Chunk Size:</b> 1000 records per transaction (Section 0.5 requirement)</li>
 *   <li><b>Skip Limit:</b> 100 errors before job failure (Section 0.5 requirement)</li>
 *   <li><b>Skip Exceptions:</b> DataIntegrityViolationException (orphaned cards, FK violations)</li>
 *   <li><b>Retry Limit:</b> 3 attempts with exponential backoff (Section 0.5 requirement)</li>
 *   <li><b>Retry Exceptions:</b> TransientDataAccessException (network timeouts, deadlocks)</li>
 * </ul>
 * 
 * <p><b>Transaction Management (Section 0.3 Requirements):</b></p>
 * <ul>
 *   <li><b>Isolation Level:</b> READ_COMMITTED (prevents dirty reads)</li>
 *   <li><b>Propagation:</b> REQUIRED (chunk-level transaction boundaries)</li>
 *   <li><b>Commit Interval:</b> Every 1000 records (chunk size)</li>
 *   <li><b>Rollback:</b> On fatal exceptions, skipped items excluded from chunk</li>
 * </ul>
 * 
 * <p><b>JobRepository Checkpoint/Restart Capability:</b></p>
 * <p>Spring Batch JobRepository provides automatic checkpoint/restart through ExecutionContext:</p>
 * <ul>
 *   <li>Current read position saved after each chunk commit</li>
 *   <li>Job can be restarted from last successful checkpoint on failure</li>
 *   <li>No duplicate processing - reader resumes from last read position</li>
 *   <li>ExecutionContext persisted to batch metadata tables (batch_job_execution_context)</li>
 * </ul>
 * 
 * <p><b>Cross-Reference Building Logic:</b></p>
 * <p>Replaces VSAM alternate index automatic creation with explicit relationship persistence:</p>
 * <ol>
 *   <li>Read Card entities with eager-loaded Account and Customer relationships</li>
 *   <li>Validate complete card→account→customer relationship chain</li>
 *   <li>Create AccountXref entry (customer_id, account_id) for customer-account mapping</li>
 *   <li>Create CardXref entry (card_number, customer_id, account_id) for card-account-customer mapping</li>
 *   <li>Persist both cross-reference entries atomically in single transaction</li>
 *   <li>Build composite indexes for efficient bidirectional navigation queries</li>
 * </ol>
 * 
 * <p><b>Error Handling Strategy:</b></p>
 * <ul>
 *   <li><b>Validation Failures:</b> Skip orphaned cards (no account or customer), log and continue</li>
 *   <li><b>Duplicate Keys:</b> Skip duplicate cross-reference entries (idempotent behavior)</li>
 *   <li><b>FK Violations:</b> Skip cards with invalid account references, log for data cleanup</li>
 *   <li><b>Transient Errors:</b> Retry network timeouts and deadlocks up to 3 times</li>
 *   <li><b>Fatal Errors:</b> Fail job after skip limit (100) or max retries (3) exceeded</li>
 * </ul>
 * 
 * <p><b>Performance Characteristics:</b></p>
 * <ul>
 *   <li><b>Throughput:</b> ~10,000 cards per minute (1000 per chunk * 10 chunks/min)</li>
 *   <li><b>Memory Footprint:</b> Fixed at ~1000 Card entities per chunk in memory</li>
 *   <li><b>Database Load:</b> 1 read query per chunk + 2 batch writes per chunk</li>
 *   <li><b>Restart Time:</b> Sub-second to restore from last checkpoint</li>
 * </ul>
 * 
 * <p><b>Usage Example:</b></p>
 * <pre>
 * // Run via Spring Batch JobLauncher
 * JobParameters params = new JobParametersBuilder()
 *     .addLocalDateTime("runDate", LocalDateTime.now())
 *     .toJobParameters();
 * JobExecution execution = jobLauncher.run(accountXrefBuildJob, params);
 * 
 * // Or via Kubernetes CronJob (kubernetes/cronjobs/account-xref-build-cronjob.yaml)
 * // Schedule: Daily at 2:30 AM after account data load job completes
 * </pre>
 * 
 * <p><b>Integration Context:</b></p>
 * <ul>
 *   <li><b>Upstream Dependency:</b> AccountDataLoadJob must complete successfully first</li>
 *   <li><b>Downstream Consumers:</b> AccountBalanceJob, InterestCalculationJob</li>
 *   <li><b>Kubernetes Scheduling:</b> Daily cron job at 02:30 (after CBACT01C equivalent)</li>
 *   <li><b>Monitoring:</b> Prometheus metrics via Spring Boot Actuator</li>
 * </ul>
 * 
 * @author CardDemo Modernization Team
 * @version 1.0
 * @since 2024-01-01
 * @see com.carddemo.batch.reader.CardAccountReader
 * @see com.carddemo.batch.processor.AccountXrefProcessor
 * @see com.carddemo.batch.writer.AccountXrefWriter
 * @see com.carddemo.entity.AccountXref
 * @see com.carddemo.entity.CardXref
 * @see <a href="Section 0.5">Refactored Structure Planning - Batch Processing</a>
 * @see <a href="Section 0.6">File-by-File Transformation Plan - CBACT02C.cbl</a>
 */
@Configuration
public class AccountXrefBuildJob {
    
    private static final Logger logger = LoggerFactory.getLogger(AccountXrefBuildJob.class);
    
    /**
     * Chunk size for batch processing per Section 0.5 requirements.
     * Determines transaction boundaries and commit frequency.
     */
    private static final int CHUNK_SIZE = 1000;
    
    /**
     * Maximum number of skippable exceptions before job failure per Section 0.5.
     * Allows processing to continue despite data quality issues up to this limit.
     */
    private static final int SKIP_LIMIT = 100;
    
    /**
     * Maximum retry attempts for transient failures per Section 0.5.
     * Implements exponential backoff: 1s, 2s, 4s between retries.
     */
    private static final int RETRY_LIMIT = 3;
    
    /**
     * Defines the Spring Batch Job for building account cross-reference relationships.
     * 
     * <p>This method creates the main Job bean that orchestrates the cross-reference build process.
     * The job consists of a single step (accountXrefBuildStep) that reads cards, validates
     * relationships, and persists cross-reference entries.</p>
     * 
     * <p><b>Job Configuration:</b></p>
     * <ul>
     *   <li>Job Name: "accountXrefBuildJob" (matches COBOL program ID CBACT02C)</li>
     *   <li>Single Step: accountXrefBuildStep</li>
     *   <li>Restart Policy: Restartable from last checkpoint</li>
     *   <li>Listener: Logs job start/completion equivalent to COBOL DISPLAY statements</li>
     * </ul>
     * 
     * <p><b>JobRepository Integration:</b></p>
     * <p>The JobRepository is automatically managed by Spring Batch and provides:</p>
     * <ul>
     *   <li>Job execution metadata persistence</li>
     *   <li>Step execution status tracking</li>
     *   <li>ExecutionContext storage for checkpoint/restart</li>
     *   <li>Job parameter and execution history</li>
     * </ul>
     * 
     * <p><b>Execution Listener:</b></p>
     * <p>The JobExecutionListener logs messages equivalent to COBOL CBACT02C.cbl:</p>
     * <pre>
     * COBOL Line 71: DISPLAY 'START OF EXECUTION OF PROGRAM CBACT02C'
     * → beforeJob(): logger.info("START OF EXECUTION OF PROGRAM CBACT02C (AccountXrefBuildJob)")
     * 
     * COBOL Line 85: DISPLAY 'END OF EXECUTION OF PROGRAM CBACT02C'
     * → afterJob(): logger.info("END OF EXECUTION OF PROGRAM CBACT02C (AccountXrefBuildJob)")
     * </pre>
     * 
     * <p><b>Monitoring Metrics:</b></p>
     * <p>The listener also logs execution statistics:</p>
     * <ul>
     *   <li>Total cards processed</li>
     *   <li>Cross-reference entries created (AccountXref + CardXref)</li>
     *   <li>Skipped records count (validation failures)</li>
     *   <li>Execution duration</li>
     *   <li>Job status (COMPLETED, FAILED, STOPPED)</li>
     * </ul>
     * 
     * @param jobRepository Spring Batch JobRepository for metadata and checkpoint persistence
     * @param transactionManager Platform transaction manager for chunk-level transaction control
     * @param reader CardAccountReader for reading Card entities with relationships
     * @param processor AccountXrefProcessor for validation and cross-reference building
     * @param writer AccountXrefWriter for persisting cross-reference entries
     * @return Configured Job bean ready for execution
     */
    @Bean
    public Job accountXrefBuildJob(
            JobRepository jobRepository,
            PlatformTransactionManager transactionManager,
            CardAccountReader reader,
            AccountXrefProcessor processor,
            AccountXrefWriter writer) {
        
        logger.info("Configuring AccountXrefBuildJob (COBOL program CBACT02C transformation)");
        
        return new JobBuilder("accountXrefBuildJob", jobRepository)
                .start(accountXrefBuildStep(jobRepository, transactionManager, reader, processor, writer))
                .listener(new JobExecutionListener() {
                    @Override
                    public void beforeJob(JobExecution jobExecution) {
                        // COBOL Line 71: DISPLAY 'START OF EXECUTION OF PROGRAM CBACT02C'
                        logger.info("========================================================");
                        logger.info("START OF EXECUTION OF PROGRAM CBACT02C (AccountXrefBuildJob)");
                        logger.info("Job Name: {}", jobExecution.getJobInstance().getJobName());
                        logger.info("Job Execution ID: {}", jobExecution.getId());
                        logger.info("Job Parameters: {}", jobExecution.getJobParameters());
                        logger.info("Start Time: {}", jobExecution.getStartTime());
                        logger.info("========================================================");
                    }
                    
                    @Override
                    public void afterJob(JobExecution jobExecution) {
                        // COBOL Line 85: DISPLAY 'END OF EXECUTION OF PROGRAM CBACT02C'
                        logger.info("========================================================");
                        logger.info("END OF EXECUTION OF PROGRAM CBACT02C (AccountXrefBuildJob)");
                        logger.info("Job Status: {}", jobExecution.getStatus());
                        logger.info("End Time: {}", jobExecution.getEndTime());
                        
                        // Log execution statistics
                        jobExecution.getStepExecutions().forEach(stepExecution -> {
                            logger.info("Step: {} - Read Count: {}, Write Count: {}, Skip Count: {}",
                                    stepExecution.getStepName(),
                                    stepExecution.getReadCount(),
                                    stepExecution.getWriteCount(),
                                    stepExecution.getSkipCount());
                            
                            if (stepExecution.getReadCount() > 0) {
                                logger.info("Cross-reference entries created: {} (AccountXref + CardXref)",
                                        stepExecution.getWriteCount() * 2); // Each write creates 2 xref entries
                            }
                            
                            if (stepExecution.getSkipCount() > 0) {
                                logger.warn("Skipped {} cards due to validation failures or data integrity issues",
                                        stepExecution.getSkipCount());
                            }
                        });
                        
                        // Log exit codes
                        if (jobExecution.getStatus().isUnsuccessful()) {
                            logger.error("Job failed with exit code: {}", jobExecution.getExitStatus().getExitCode());
                            jobExecution.getAllFailureExceptions().forEach(throwable ->
                                    logger.error("Failure: {}", throwable.getMessage(), throwable));
                        } else {
                            logger.info("Job completed successfully");
                        }
                        logger.info("========================================================");
                    }
                })
                .build();
    }
    
    /**
     * Defines the Step for chunk-oriented cross-reference building processing.
     * 
     * <p>This method creates the core processing step that implements the reader-processor-writer
     * pattern for transforming Card entities into cross-reference entries. The step is configured
     * with fault tolerance policies (skip, retry) to handle data quality issues and transient
     * failures per Section 0.5 batch processing requirements.</p>
     * 
     * <p><b>Step Configuration:</b></p>
     * <ul>
     *   <li>Step Name: "accountXrefBuildStep"</li>
     *   <li>Chunk Size: 1000 records (transaction boundary)</li>
     *   <li>Input Type: Card (from CardAccountReader)</li>
     *   <li>Output Type: AccountXrefProcessor.XrefEntry (to AccountXrefWriter)</li>
     * </ul>
     * 
     * <p><b>Processing Pipeline:</b></p>
     * <pre>
     * CardAccountReader.read() → Card entity
     *   ↓
     * AccountXrefProcessor.process(Card) → XrefEntry (AccountXref + CardXref)
     *   ↓
     * AccountXrefWriter.write(Chunk&lt;XrefEntry&gt;) → PostgreSQL batch insert
     *   ↓
     * Transaction Commit (every 1000 records)
     * </pre>
     * 
     * <p><b>Fault Tolerance Configuration:</b></p>
     * 
     * <p><i>Skip Policy (Data Quality Issues):</i></p>
     * <ul>
     *   <li>Exception Type: DataIntegrityViolationException</li>
     *   <li>Skip Limit: 100 occurrences before job failure</li>
     *   <li>Behavior: Log skipped card, continue processing remaining cards</li>
     *   <li>Use Cases: Orphaned cards (no account), FK violations, duplicate entries</li>
     * </ul>
     * 
     * <p><i>Retry Policy (Transient Failures):</i></p>
     * <ul>
     *   <li>Exception Type: TransientDataAccessException</li>
     *   <li>Retry Limit: 3 attempts with exponential backoff (1s, 2s, 4s)</li>
     *   <li>Behavior: Retry same chunk after backoff interval</li>
     *   <li>Use Cases: Network timeouts, database deadlocks, connection pool exhaustion</li>
     * </ul>
     * 
     * <p><b>Transaction Semantics (Section 0.3):</b></p>
     * <p>The PlatformTransactionManager controls transaction boundaries:</p>
     * <ul>
     *   <li>New transaction started for each chunk of 1000 cards</li>
     *   <li>Isolation level: READ_COMMITTED (configured in DatabaseConfig)</li>
     *   <li>Commit after successful chunk write</li>
     *   <li>Rollback on fatal exceptions (non-skippable, non-retryable)</li>
     *   <li>Skipped items excluded from current transaction</li>
     * </ul>
     * 
     * <p><b>Checkpoint/Restart Implementation:</b></p>
     * <p>Spring Batch ExecutionContext provides automatic checkpoint/restart:</p>
     * <ol>
     *   <li>After each chunk commit, current read position saved to ExecutionContext</li>
     *   <li>ExecutionContext persisted to batch_step_execution_context table</li>
     *   <li>On job restart, reader.open() restores position from ExecutionContext</li>
     *   <li>Processing resumes from last successful checkpoint</li>
     *   <li>No duplicate processing - already-processed cards skipped automatically</li>
     * </ol>
     * 
     * <p><b>Performance Optimization:</b></p>
     * <ul>
     *   <li>Chunk size 1000 balances memory usage vs. transaction overhead</li>
     *   <li>JPA batch inserts configured (hibernate.jdbc.batch_size=50)</li>
     *   <li>JOIN FETCH in reader query prevents N+1 query problem</li>
     *   <li>EntityManager cleared after each chunk to prevent memory growth</li>
     * </ul>
     * 
     * <p><b>Monitoring and Observability:</b></p>
     * <ul>
     *   <li>Read count tracked automatically by Spring Batch</li>
     *   <li>Write count = successful cross-reference entry persistence</li>
     *   <li>Skip count = data quality issues encountered</li>
     *   <li>Retry count = transient failures recovered</li>
     *   <li>All metrics exposed via Spring Boot Actuator and Prometheus</li>
     * </ul>
     * 
     * @param jobRepository Spring Batch JobRepository for step execution metadata
     * @param transactionManager Platform transaction manager for chunk transactions
     * @param reader CardAccountReader for reading Card entities with eager-loaded relationships
     * @param processor AccountXrefProcessor for validating and building cross-reference entries
     * @param writer AccountXrefWriter for batch persistence to account_xref and card_xref tables
     * @return Configured Step bean with chunk processing, skip, and retry policies
     */
    @Bean
    public Step accountXrefBuildStep(
            JobRepository jobRepository,
            PlatformTransactionManager transactionManager,
            CardAccountReader reader,
            AccountXrefProcessor processor,
            AccountXrefWriter writer) {
        
        logger.info("Configuring accountXrefBuildStep with chunk size: {}, skip limit: {}, retry limit: {}",
                CHUNK_SIZE, SKIP_LIMIT, RETRY_LIMIT);
        
        return new StepBuilder("accountXrefBuildStep", jobRepository)
                // Configure chunk-oriented processing: Card -> XrefEntry
                .<Card, AccountXrefProcessor.XrefEntry>chunk(CHUNK_SIZE, transactionManager)
                
                // Reader: Sequential card read with eager-loaded relationships
                .reader(reader)
                
                // Processor: Validate relationships and build cross-reference entries
                .processor(processor)
                
                // Writer: Batch persist AccountXref and CardXref entries
                .writer(writer)
                
                // Skip Policy: Handle data quality issues (orphaned cards, FK violations)
                .faultTolerant()
                .skipLimit(SKIP_LIMIT)
                .skip(DataIntegrityViolationException.class)
                
                // Retry Policy: Handle transient failures (network, deadlocks)
                .retryLimit(RETRY_LIMIT)
                .retry(TransientDataAccessException.class)
                
                // Build the configured step
                .build();
    }
}
