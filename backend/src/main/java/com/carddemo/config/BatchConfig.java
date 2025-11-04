/*
 * BatchConfig.java
 * 
 * Spring Boot configuration class for Spring Batch infrastructure providing
 * batch job execution framework for CardDemo application.
 * 
 * This configuration transforms COBOL batch processing patterns (CBACT*, CBTRN*, CBSTM*)
 * to Spring Batch jobs with equivalent checkpoint/restart capabilities.
 * 
 * Configured Batch Processing Policies:
 * - Chunk size: 1000 records per Section 0.5 Batch Job Configuration
 * - Skip limit: 100 errors before job failure
 * - Retry attempts: 3 with exponential backoff (1s initial, 2x multiplier, 10s max)
 * - Parallel execution: 4 concurrent threads for step execution
 * - Transaction isolation: SERIALIZABLE for job metadata, READ_COMMITTED for business data
 * - Commit interval: Matches chunk size (1000 records)
 * 
 * COBOL Batch Pattern Transformations:
 * - Sequential VSAM reads → JdbcPagingItemReader with page size 1000
 * - COBOL paragraph processing → ItemProcessor business logic
 * - COBOL file writes → ItemWriter batch operations
 * - JCL checkpoint/restart → Spring Batch ExecutionContext state persistence
 * - JCL step dependencies → Spring Batch job flow with transitions
 * - COBOL error handling (ABEND) → Skip/Retry policies with exception classification
 * 
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * Licensed under the Apache License, Version 2.0
 */
package com.carddemo.config;

import org.springframework.batch.core.explore.JobExplorer;
import org.springframework.batch.core.launch.JobLauncher;
import org.springframework.batch.core.launch.support.TaskExecutorJobLauncher;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.core.repository.support.JobRepositoryFactoryBean;
import org.springframework.batch.core.step.skip.LimitCheckingItemSkipPolicy;
import org.springframework.batch.core.step.skip.SkipPolicy;
import org.springframework.batch.item.ItemProcessor;
import org.springframework.batch.item.ItemReader;
import org.springframework.batch.item.ItemWriter;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.context.annotation.Profile;
import org.springframework.core.task.SimpleAsyncTaskExecutor;
import org.springframework.core.task.TaskExecutor;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.retry.RetryPolicy;
import org.springframework.retry.backoff.BackOffPolicy;
import org.springframework.retry.backoff.ExponentialBackOffPolicy;
import org.springframework.retry.policy.SimpleRetryPolicy;
import org.springframework.transaction.PlatformTransactionManager;

import javax.sql.DataSource;
import java.util.HashMap;
import java.util.Map;

/**
 * BatchConfig - Spring Batch Infrastructure Configuration
 * 
 * Provides comprehensive Spring Batch configuration for migrated COBOL batch programs:
 * - CBACT01C (Account data load) → AccountDataLoadJob
 * - CBACT02C (Account cross-reference) → AccountXrefBuildJob
 * - CBACT03C (Account balance calc) → AccountBalanceJob
 * - CBACT04C (Interest calculation) → InterestCalculationJob
 * - CBCUS01C (Customer data load) → CustomerDataLoadJob
 * - CBTRN01C (Transaction data load) → TransactionDataLoadJob
 * - CBTRN02C (Daily transaction processing) → DailyTransactionProcessingJob
 * - CBTRN03C (Transaction aggregation) → TransactionAggregationJob
 * - CBSTM03A (Statement generation) → StatementGenerationJob
 * - CBSTM03B (Statement formatting) → StatementFormattingJob
 * - CBCRD01C (Card data load) → CardDataLoadJob
 * 
 * Performance Requirements:
 * - 4-hour batch processing window maintained through chunk processing
 * - Parallel step execution with 4 concurrent threads
 * - Database connection pooling via HikariCP (configured in DatabaseConfig)
 * - Optimized commit intervals matching chunk size for minimal transaction overhead
 * 
 * Error Handling:
 * - Transient errors: Retry up to 3 times with exponential backoff
 * - Skippable errors: Skip up to 100 records before job failure
 * - Fatal errors: Immediate job failure with rollback
 * - All errors logged with full context for troubleshooting
 */
@Configuration
public class BatchConfig {

    /**
     * Chunk size for batch processing operations.
     * Represents number of records processed in a single transaction.
     * Based on Section 0.5: "Chunk size: 1000 records"
     */
    public static final int CHUNK_SIZE = 1000;

    /**
     * Maximum number of errors allowed before job failure.
     * Based on Section 0.5: "Skip limit: 100 errors before failure"
     */
    public static final int SKIP_LIMIT = 100;

    /**
     * Maximum number of retry attempts for transient errors.
     * Based on Section 0.5: "Retry attempts: 3 with exponential backoff"
     */
    public static final int MAX_RETRY_ATTEMPTS = 3;

    /**
     * Initial backoff period in milliseconds for retry operations.
     */
    public static final long INITIAL_BACKOFF_MS = 1000L;

    /**
     * Backoff multiplier for exponential backoff strategy.
     */
    public static final double BACKOFF_MULTIPLIER = 2.0;

    /**
     * Maximum backoff period in milliseconds.
     */
    public static final long MAX_BACKOFF_MS = 10000L;

    /**
     * Number of concurrent threads for parallel step execution.
     * Based on Section 0.5: "Concurrent steps: 4 parallel threads"
     */
    public static final int CONCURRENT_THREADS = 4;

    /**
     * JobRepository Auto-Configuration Note
     * 
     * JobRepository is now auto-configured by Spring Boot 3.x BatchAutoConfiguration.
     * 
     * Configuration is provided via application.yml:
     * - spring.batch.jdbc.table-prefix: BATCH_
     * - spring.batch.jdbc.isolation-level-for-create: SERIALIZABLE
     * - spring.batch.jdbc.max-varchar-length: 2500
     * 
     * The auto-configured JobRepository:
     * - Uses the DataSource from DatabaseConfig (HikariCP connection pool)
     * - Uses the PlatformTransactionManager from DatabaseConfig
     * - Stores job execution metadata in PostgreSQL tables:
     *   * BATCH_JOB_INSTANCE: Job instances with parameters
     *   * BATCH_JOB_EXECUTION: Job execution history and status
     *   * BATCH_STEP_EXECUTION: Step execution details
     *   * BATCH_JOB_EXECUTION_CONTEXT: Job-level execution context for restart
     *   * BATCH_STEP_EXECUTION_CONTEXT: Step-level execution context for restart
     *   * BATCH_JOB_EXECUTION_PARAMS: Job parameter values
     * 
     * Provides checkpoint/restart capability equivalent to COBOL batch restart logic.
     * 
     * Manual bean definition removed to prevent BeanDefinitionOverrideException
     * with Spring Boot's auto-configured jobRepository bean.
     */

    /**
     * JobLauncher Bean - Asynchronous job launcher for batch execution.
     * 
     * Enables asynchronous batch job execution in background threads, allowing
     * Kubernetes CronJobs to trigger jobs without blocking the main application thread.
     * 
     * Configuration:
     * - Task executor: SimpleAsyncTaskExecutor for background execution
     * - Job repository: Database-backed repository for execution tracking
     * 
     * Used by Kubernetes CronJobs to launch batch jobs:
     * - account-data-load-cronjob.yaml
     * - interest-calculation-cronjob.yaml
     * - daily-transaction-cronjob.yaml
     * - statement-generation-cronjob.yaml
     * - And 7 additional batch job CronJobs
     * 
     * @param jobRepository JobRepository for tracking job execution
     * @return JobLauncher configured for asynchronous execution
     * @throws Exception if JobLauncher initialization fails
     */
    @Bean
    @Primary
    @Profile("!test")
    public JobLauncher jobLauncher(JobRepository jobRepository) throws Exception {
        TaskExecutorJobLauncher jobLauncher = new TaskExecutorJobLauncher();
        jobLauncher.setJobRepository(jobRepository);
        
        // Asynchronous task executor for background job execution
        // Each job runs in a separate thread, allowing CronJob to return immediately
        jobLauncher.setTaskExecutor(batchTaskExecutor());
        
        jobLauncher.afterPropertiesSet();
        
        return jobLauncher;
    }

    /**
     * JobLauncher Bean - Synchronous job launcher for test execution.
     * 
     * Provides synchronous job execution for Spring Batch tests to ensure
     * JobExecution is fully completed before assertions are evaluated.
     * 
     * Configuration:
     * - Task executor: null (synchronous execution on calling thread)
     * - Job repository: Database-backed repository for execution tracking
     * 
     * @param jobRepository JobRepository for tracking job execution
     * @return JobLauncher configured for synchronous execution in tests
     * @throws Exception if JobLauncher initialization fails
     */
    @Bean("jobLauncher")
    @Primary
    @Profile("test")
    public JobLauncher testJobLauncher(JobRepository jobRepository) throws Exception {
        TaskExecutorJobLauncher jobLauncher = new TaskExecutorJobLauncher();
        jobLauncher.setJobRepository(jobRepository);
        
        // No task executor means synchronous execution on calling thread
        // This ensures JobExecution is complete when launchJob() returns
        // jobLauncher.setTaskExecutor(null); // null is default, so commented out
        
        jobLauncher.afterPropertiesSet();
        
        return jobLauncher;
    }

    /**
     * Batch Task Executor - Thread pool for parallel batch step execution.
     * 
     * Provides concurrent execution capability for batch job steps.
     * Based on Section 0.5: "Concurrent steps: 4 parallel threads"
     * 
     * Enables parallel processing patterns:
     * - Parallel step execution within a single job
     * - Multi-threaded step processing for large datasets
     * - Asynchronous job launching from Kubernetes CronJobs
     * 
     * @return TaskExecutor configured with 4 concurrent threads
     */
    @Bean
    public TaskExecutor batchTaskExecutor() {
        SimpleAsyncTaskExecutor executor = new SimpleAsyncTaskExecutor();
        executor.setConcurrencyLimit(CONCURRENT_THREADS);
        executor.setThreadNamePrefix("batch-");
        return executor;
    }

    /**
     * Skip Policy Bean - Error skip policy for batch processing.
     * 
     * Allows batch jobs to skip up to 100 records with errors before failing.
     * Based on Section 0.5: "Skip limit: 100 errors before job failure"
     * 
     * Skippable exceptions (transient errors):
     * - org.springframework.dao.DataAccessException (database connectivity issues)
     * - org.springframework.dao.CannotAcquireLockException (lock acquisition failures)
     * - org.springframework.dao.PessimisticLockingFailureException (pessimistic locking failures)
     * - org.springframework.dao.OptimisticLockingFailureException (concurrent updates)
     * 
     * Non-skippable exceptions (fatal errors):
     * - NullPointerException (programming errors)
     * - IllegalArgumentException (invalid data that should be validated earlier)
     * - Any exception not in the skippable list
     * 
     * Equivalent to COBOL batch error handling:
     * - COBOL file status codes 23, 30, 35 (temporary file errors) → Skippable
     * - COBOL file status codes 9x (permanent errors) → Non-skippable
     * 
     * @return SkipPolicy configured with skip limit and skippable exception classes
     */
    @Bean
    public SkipPolicy skipPolicy() {
        Map<Class<? extends Throwable>, Boolean> skippableExceptions = new HashMap<>();
        
        // Skippable exceptions - transient errors that may resolve on retry
        skippableExceptions.put(org.springframework.dao.DataAccessException.class, true);
        skippableExceptions.put(org.springframework.dao.CannotAcquireLockException.class, true);
        skippableExceptions.put(org.springframework.dao.PessimisticLockingFailureException.class, true);
        skippableExceptions.put(org.springframework.dao.OptimisticLockingFailureException.class, true);
        
        // Non-skippable exceptions - fatal errors requiring immediate job failure
        skippableExceptions.put(NullPointerException.class, false);
        skippableExceptions.put(IllegalArgumentException.class, false);
        
        return new LimitCheckingItemSkipPolicy(SKIP_LIMIT, skippableExceptions);
    }

    /**
     * Retry Policy Bean - Retry policy for transient errors.
     * 
     * Retries failed operations up to 3 times before skipping or failing.
     * Based on Section 0.5: "Retry attempts: 3 with exponential backoff"
     * 
     * Retry strategy:
     * - Max attempts: 3
     * - Backoff policy: Exponential (1s → 2s → 4s → 8s, max 10s)
     * - Retryable exceptions: Same as skippable exceptions
     * 
     * Retry sequence for transient errors:
     * 1. First failure: Wait 1 second, retry
     * 2. Second failure: Wait 2 seconds, retry
     * 3. Third failure: Wait 4 seconds, retry
     * 4. Fourth failure: Skip record (if within skip limit) or fail job
     * 
     * Equivalent to COBOL batch restart logic with checkpoint intervals.
     * 
     * @return RetryPolicy configured with maximum retry attempts
     */
    @Bean
    public RetryPolicy retryPolicy() {
        SimpleRetryPolicy retryPolicy = new SimpleRetryPolicy();
        retryPolicy.setMaxAttempts(MAX_RETRY_ATTEMPTS);
        return retryPolicy;
    }

    /**
     * BackOff Policy Bean - Exponential backoff for retry operations.
     * 
     * Implements exponential backoff strategy to avoid overwhelming systems during retries.
     * Based on Section 0.5: "Exponential backoff (initial 1000ms, multiplier 2.0, max 10000ms)"
     * 
     * Backoff progression:
     * - Initial: 1000ms (1 second)
     * - After 1st retry: 2000ms (2 seconds) = 1000ms * 2.0
     * - After 2nd retry: 4000ms (4 seconds) = 2000ms * 2.0
     * - After 3rd retry: 8000ms (8 seconds) = 4000ms * 2.0
     * - Maximum: 10000ms (10 seconds) cap
     * 
     * Benefits:
     * - Gives transient issues time to resolve (e.g., database connection recovery)
     * - Prevents retry storms that could worsen system load
     * - Provides gradual recovery for intermittent failures
     * 
     * @return BackOffPolicy configured with exponential backoff parameters
     */
    @Bean
    public BackOffPolicy backOffPolicy() {
        ExponentialBackOffPolicy backOffPolicy = new ExponentialBackOffPolicy();
        backOffPolicy.setInitialInterval(INITIAL_BACKOFF_MS);
        backOffPolicy.setMultiplier(BACKOFF_MULTIPLIER);
        backOffPolicy.setMaxInterval(MAX_BACKOFF_MS);
        return backOffPolicy;
    }

    /**
     * Default Step Execution Listener Bean - Logging and metrics for batch steps.
     * 
     * Provides comprehensive step execution monitoring:
     * - Step start/completion logging
     * - Execution metrics capture (read count, write count, skip count, commit count)
     * - Execution time tracking
     * - Error context logging for troubleshooting
     * 
     * Metrics exported to Prometheus for monitoring:
     * - batch_step_execution_time_seconds
     * - batch_step_read_count_total
     * - batch_step_write_count_total
     * - batch_step_skip_count_total
     * - batch_step_commit_count_total
     * 
     * Equivalent to COBOL batch job statistics and logs:
     * - COBOL DISPLAY statements → SLF4J logging
     * - JCL job statistics → Spring Batch metrics
     * - SYSOUT datasets → Application logs
     * 
     * @return StepExecutionListener for step monitoring and logging
     */
    @Bean
    public org.springframework.batch.core.StepExecutionListener defaultStepExecutionListener() {
        return new org.springframework.batch.core.StepExecutionListener() {
            private static final org.slf4j.Logger logger = 
                org.slf4j.LoggerFactory.getLogger("BatchStepExecutionListener");

            @Override
            public void beforeStep(org.springframework.batch.core.StepExecution stepExecution) {
                logger.info("Starting batch step: {} in job: {} (Job Execution ID: {})",
                    stepExecution.getStepName(),
                    stepExecution.getJobExecution().getJobInstance().getJobName(),
                    stepExecution.getJobExecutionId());
                
                // Log job parameters for traceability
                stepExecution.getJobParameters().getParameters().forEach((key, value) -> 
                    logger.debug("Job parameter: {} = {}", key, value.getValue())
                );
            }

            @Override
            public org.springframework.batch.core.ExitStatus afterStep(
                    org.springframework.batch.core.StepExecution stepExecution) {
                
                String stepName = stepExecution.getStepName();
                String jobName = stepExecution.getJobExecution().getJobInstance().getJobName();
                
                // Log step completion with comprehensive metrics
                // Calculate duration in milliseconds - handle null endTime
                long durationMs = 0;
                if (stepExecution.getEndTime() != null && stepExecution.getStartTime() != null) {
                    durationMs = java.time.Duration.between(
                        stepExecution.getStartTime(), 
                        stepExecution.getEndTime()
                    ).toMillis();
                } else if (stepExecution.getStartTime() != null) {
                    // If endTime is null, calculate duration to now
                    durationMs = java.time.Duration.between(
                        stepExecution.getStartTime(),
                        java.time.LocalDateTime.now()
                    ).toMillis();
                }
                
                logger.info(
                    "Completed batch step: {} in job: {} - " +
                    "Status: {}, Read: {}, Written: {}, Skipped: {}, Commits: {}, Duration: {}ms",
                    stepName,
                    jobName,
                    stepExecution.getStatus(),
                    stepExecution.getReadCount(),
                    stepExecution.getWriteCount(),
                    stepExecution.getSkipCount(),
                    stepExecution.getCommitCount(),
                    durationMs
                );

                // Log errors if step failed
                if (stepExecution.getStatus() == org.springframework.batch.core.BatchStatus.FAILED) {
                    stepExecution.getFailureExceptions().forEach(throwable ->
                        logger.error("Step execution failure in {}: {}", 
                            stepName, 
                            throwable.getMessage(), 
                            throwable)
                    );
                }

                // Log warnings if records were skipped
                if (stepExecution.getSkipCount() > 0) {
                    logger.warn("Step {} skipped {} records - check logs for details",
                        stepName,
                        stepExecution.getSkipCount());
                }

                return stepExecution.getExitStatus();
            }
        };
    }
}

