package com.carddemo.config;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.batch.core.configuration.annotation.EnableBatchProcessing;
import org.springframework.batch.core.configuration.annotation.JobBuilderFactory;
import org.springframework.batch.core.configuration.annotation.StepBuilderFactory;
import org.springframework.batch.core.launch.JobLauncher;
import org.springframework.batch.core.launch.support.TaskExecutorJobLauncher;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.core.task.TaskExecutor;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.transaction.PlatformTransactionManager;

import javax.sql.DataSource;

/**
 * Spring Batch Configuration for CardDemo Application
 * 
 * This configuration class provides the core infrastructure for Spring Batch processing,
 * replacing the mainframe JCL batch processing system. It configures:
 * - JobRepository for batch execution metadata persistence
 * - JobLauncher for programmatic and scheduled batch job execution
 * - Transaction management for atomic batch operations
 * - Thread pool for parallel step execution supporting 4-hour processing window
 * - Chunk processing defaults and skip/restart capabilities
 * 
 * Replaces: JCL job definitions and batch scheduling from mainframe
 * Supports: 10 batch jobs including account data load, interest calculation,
 *          daily transaction processing, and statement generation
 * 
 * Performance Target: Complete all batch processing within 4-hour window
 * Transaction Semantics: Maintains ACID properties equivalent to CICS SYNCPOINT/ROLLBACK
 */
@Slf4j
@Configuration
@EnableBatchProcessing
@RequiredArgsConstructor
public class BatchConfig {

    /**
     * Default chunk size for batch processing
     * Externalized to application.properties as spring.batch.chunk-size
     * Default: 1000 records per chunk (tunable based on performance testing)
     */
    @Value("${spring.batch.chunk-size:1000}")
    private int chunkSize;

    /**
     * Thread pool core size for parallel step execution
     * Externalized to application.properties as spring.batch.thread-pool-core-size
     * Default: 4 threads
     */
    @Value("${spring.batch.thread-pool-core-size:4}")
    private int threadPoolCoreSize;

    /**
     * Thread pool maximum size for parallel step execution
     * Externalized to application.properties as spring.batch.thread-pool-max-size
     * Default: 8 threads
     */
    @Value("${spring.batch.thread-pool-max-size:8}")
    private int threadPoolMaxSize;

    /**
     * Thread pool queue capacity for task queueing
     * Externalized to application.properties as spring.batch.thread-pool-queue-capacity
     * Default: 100 tasks
     */
    @Value("${spring.batch.thread-pool-queue-capacity:100}")
    private int threadPoolQueueCapacity;

    /**
     * Transaction timeout for batch operations in seconds
     * Externalized to application.properties as spring.batch.transaction-timeout
     * Default: 7200 seconds (2 hours) to support long-running batch jobs
     */
    @Value("${spring.batch.transaction-timeout:7200}")
    private int transactionTimeout;

    /**
     * Skip limit for transient errors during batch processing
     * Externalized to application.properties as spring.batch.skip-limit
     * Default: 10 records
     */
    @Value("${spring.batch.skip-limit:10}")
    private int skipLimit;

    private final DataSource dataSource;

    /**
     * Configure dedicated transaction manager for batch operations.
     * 
     * Provides transaction management for Spring Batch jobs with:
     * - Long transaction timeout (2 hours default) for batch processing
     * - READ_COMMITTED isolation level for consistent data access
     * - Support for ACID properties matching CICS transaction semantics
     * 
     * Replaces: CICS SYNCPOINT and ROLLBACK transaction boundaries
     * 
     * @param dataSource PostgreSQL datasource configured in DatabaseConfig
     * @return PlatformTransactionManager for batch operations
     */
    @Bean(name = "batchTransactionManager")
    public PlatformTransactionManager batchTransactionManager(DataSource dataSource) {
        log.info("Configuring batch transaction manager with timeout: {} seconds", transactionTimeout);
        
        DataSourceTransactionManager transactionManager = new DataSourceTransactionManager(dataSource);
        transactionManager.setDefaultTimeout(transactionTimeout);
        
        log.debug("Batch transaction manager configured successfully");
        return transactionManager;
    }

    /**
     * Configure JobBuilderFactory for creating Spring Batch jobs.
     * 
     * Note: This class is deprecated in Spring Batch 5.x but included here for
     * compatibility with existing job definitions. New jobs should use JobBuilder
     * with JobRepository directly.
     * 
     * @param jobRepository Spring Batch job repository
     * @return JobBuilderFactory instance
     */
    @Bean
    @SuppressWarnings("deprecation")
    public org.springframework.batch.core.configuration.annotation.JobBuilderFactory jobBuilderFactory(
            JobRepository jobRepository) {
        log.info("Configuring JobBuilderFactory (deprecated, for compatibility)");
        return new org.springframework.batch.core.configuration.annotation.JobBuilderFactory(jobRepository);
    }

    /**
     * Configure StepBuilderFactory for creating Spring Batch steps.
     * 
     * Note: This class is deprecated in Spring Batch 5.x but included here for
     * compatibility with existing step definitions. New steps should use StepBuilder
     * with JobRepository and PlatformTransactionManager directly.
     * 
     * @param jobRepository Spring Batch job repository
     * @return StepBuilderFactory instance
     */
    @Bean
    @SuppressWarnings("deprecation")
    public org.springframework.batch.core.configuration.annotation.StepBuilderFactory stepBuilderFactory(
            JobRepository jobRepository) {
        log.info("Configuring StepBuilderFactory (deprecated, for compatibility)");
        return new org.springframework.batch.core.configuration.annotation.StepBuilderFactory(jobRepository);
    }

    /**
     * Configure JobLauncher for programmatic and scheduled batch job execution.
     * 
     * The JobLauncher provides the entry point for executing batch jobs either:
     * - Programmatically via REST endpoints or service methods
     * - Scheduled via Spring @Scheduled annotations or Kubernetes CronJobs
     * 
     * Configured with TaskExecutor for asynchronous job execution to prevent
     * blocking when launching long-running batch jobs.
     * 
     * Replaces: JCL job submission via mainframe scheduler
     * 
     * @param jobRepository Spring Batch job repository for metadata persistence
     * @return JobLauncher configured for async execution
     * @throws Exception if launcher configuration fails
     */
    @Bean
    @Profile("!test")
    public JobLauncher jobLauncher(JobRepository jobRepository) throws Exception {
        log.info("Configuring job launcher with async task executor");
        
        TaskExecutorJobLauncher jobLauncher = new TaskExecutorJobLauncher();
        jobLauncher.setJobRepository(jobRepository);
        jobLauncher.setTaskExecutor(batchTaskExecutor());
        jobLauncher.afterPropertiesSet();
        
        log.debug("Job launcher configured successfully");
        return jobLauncher;
    }

    /**
     * Configure ThreadPoolTaskExecutor for parallel batch step execution.
     * 
     * Enables parallel processing of batch steps to meet the 4-hour processing
     * window requirement. Multiple steps can execute concurrently, and chunk
     * processing can be parallelized within steps.
     * 
     * Thread Pool Configuration:
     * - Core pool size: 4 threads (configurable)
     * - Max pool size: 8 threads (configurable)
     * - Queue capacity: 100 tasks (configurable)
     * - Thread name prefix: "batch-" for identification in logs
     * 
     * Supports concurrent execution of:
     * - Multiple batch jobs
     * - Multiple steps within a job
     * - Partitioned steps with parallel processing
     * 
     * Performance: Enables meeting 4-hour batch window with large data volumes
     * 
     * @return TaskExecutor configured for batch processing
     */
    @Bean(name = "batchTaskExecutor")
    public TaskExecutor batchTaskExecutor() {
        log.info("Configuring batch task executor - core: {}, max: {}, queue: {}", 
                threadPoolCoreSize, threadPoolMaxSize, threadPoolQueueCapacity);
        
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(threadPoolCoreSize);
        executor.setMaxPoolSize(threadPoolMaxSize);
        executor.setQueueCapacity(threadPoolQueueCapacity);
        executor.setThreadNamePrefix("batch-");
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(300); // 5 minutes graceful shutdown
        executor.initialize();
        
        log.debug("Batch task executor initialized successfully");
        return executor;
    }

    /**
     * Get configured chunk size for batch processing.
     * 
     * Provides the default chunk size for use in batch job definitions.
     * Chunk-oriented processing reads, processes, and writes records in chunks
     * with transaction boundaries around each chunk.
     * 
     * Default: 1000 records per chunk
     * Tunable: Via application.properties spring.batch.chunk-size
     * 
     * Chunk processing pattern:
     * 1. Read up to chunk-size records
     * 2. Process each record (transformations, validation)
     * 3. Write entire chunk in single transaction
     * 4. Commit transaction (matches CICS SYNCPOINT)
     * 5. Repeat until all records processed
     * 
     * @return configured chunk size
     */
    public int getChunkSize() {
        return chunkSize;
    }

    /**
     * Get configured skip limit for error handling.
     * 
     * Provides the default skip limit for use in batch job definitions.
     * Skip limit determines how many records can be skipped due to errors
     * before the entire job fails.
     * 
     * Default: 10 records
     * Tunable: Via application.properties spring.batch.skip-limit
     * 
     * Enables resilient batch processing:
     * - Transient errors (network timeouts, temporary locks) can be skipped
     * - Job continues processing remaining records
     * - Skipped records logged for manual review
     * - Job fails if skip limit exceeded (data quality protection)
     * 
     * @return configured skip limit
     */
    public int getSkipLimit() {
        return skipLimit;
    }

    /**
     * Get configured transaction timeout.
     * 
     * Provides the transaction timeout for use in batch operations.
     * Long timeout supports batch jobs that process large volumes of data
     * within single transactions.
     * 
     * Default: 7200 seconds (2 hours)
     * Tunable: Via application.properties spring.batch.transaction-timeout
     * 
     * @return transaction timeout in seconds
     */
    public int getTransactionTimeout() {
        return transactionTimeout;
    }
}

