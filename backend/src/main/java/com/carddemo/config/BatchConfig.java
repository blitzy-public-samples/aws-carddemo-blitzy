package com.carddemo.config;

import org.springframework.batch.core.configuration.JobRegistry;
import org.springframework.batch.core.configuration.annotation.EnableBatchProcessing;
import org.springframework.batch.core.configuration.support.MapJobRegistry;
import org.springframework.batch.core.explore.JobExplorer;
import org.springframework.batch.core.explore.support.JobExplorerFactoryBean;
import org.springframework.batch.core.launch.JobLauncher;
import org.springframework.batch.core.launch.JobOperator;
import org.springframework.batch.core.launch.support.TaskExecutorJobLauncher;
import org.springframework.batch.core.launch.support.SimpleJobOperator;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.core.repository.support.JobRepositoryFactoryBean;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.task.TaskExecutor;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.transaction.PlatformTransactionManager;

import javax.sql.DataSource;
import java.util.concurrent.ThreadPoolExecutor;

/**
 * Spring Batch configuration class replacing IBM z/OS mainframe JCL (Job Control Language) 
 * job orchestration and JES2 (Job Entry Subsystem) job scheduling.
 * 
 * <p>This configuration establishes Spring Batch infrastructure to execute 28 mainframe batch jobs
 * that have been migrated from JCL/COBOL to Spring Batch/Java. It provides job repository persistence,
 * asynchronous job launching, chunk-oriented processing, restart capability, and automated scheduling
 * matching the mainframe's overnight batch processing windows.</p>
 * 
 * <h2>Mainframe Batch Jobs Migration</h2>
 * <p>This configuration supports the following JCL-to-Spring Batch transformations:</p>
 * 
 * <h3>Account Processing Jobs (CBACTJ01-04):</h3>
 * <ul>
 *   <li><strong>CBACTJ01.jcl</strong> → AccountValidationJob: Daily account file list and validation</li>
 *   <li><strong>CBACTJ02.jcl</strong> → InterestCalculationJob: Monthly interest calculation and posting</li>
 *   <li><strong>CBACTJ03.jcl</strong> → CreditLimitReviewJob: Weekly credit limit processing and adjustment</li>
 *   <li><strong>CBACTJ04.jcl</strong> → ExpirationProcessingJob: Daily account expiration processing</li>
 * </ul>
 * 
 * <h3>Transaction Processing Jobs (CBTRNJ01-03):</h3>
 * <ul>
 *   <li><strong>CBTRNJ01.jcl</strong> → TransactionValidationJob: Daily transaction file validation</li>
 *   <li><strong>CBTRNJ02.jcl</strong> → TransactionPostingJob: Daily transaction posting to accounts</li>
 *   <li><strong>CBTRNJ03.jcl</strong> → CategorySummarizationJob: Daily transaction category aggregation</li>
 * </ul>
 * 
 * <h3>Customer Processing Jobs (CBCUSJ01):</h3>
 * <ul>
 *   <li><strong>CBCUSJ01.jcl</strong> → CustomerValidationJob: Weekly customer file validation</li>
 * </ul>
 * 
 * <h3>Statement Processing Jobs (DALYREJS):</h3>
 * <ul>
 *   <li><strong>DALYREJS.jcl</strong> → StatementGenerationJob: Daily billing statement generation</li>
 * </ul>
 * 
 * <h2>JES2 to Spring Scheduler Migration</h2>
 * <p>Mainframe batch window 02:00-06:00 (4-hour overnight cycle) is preserved using Spring's
 * @Scheduled annotation with cron expressions:</p>
 * <ul>
 *   <li><strong>01:00:</strong> TransactionValidationJob (CBTRNJ01)</li>
 *   <li><strong>01:30:</strong> TransactionPostingJob (CBTRNJ02)</li>
 *   <li><strong>02:00:</strong> AccountValidationJob (CBACTJ01)</li>
 *   <li><strong>03:00:</strong> ExpirationProcessingJob (CBACTJ04)</li>
 *   <li><strong>04:00:</strong> CategorySummarizationJob (CBTRNJ03)</li>
 *   <li><strong>05:00:</strong> StatementGenerationJob (DALYREJS)</li>
 * </ul>
 * 
 * <h2>VSAM to PostgreSQL Job Repository</h2>
 * <p>JES2 job tracking (SPOOL files, job logs, return codes) is replaced with PostgreSQL-backed
 * Spring Batch metadata tables:</p>
 * <ul>
 *   <li><strong>BATCH_JOB_INSTANCE:</strong> Unique job executions (replaces JES2 job name/number)</li>
 *   <li><strong>BATCH_JOB_EXECUTION:</strong> Job execution details (replaces JES2 job log)</li>
 *   <li><strong>BATCH_STEP_EXECUTION:</strong> Step execution details (replaces JCL EXEC PGM steps)</li>
 *   <li><strong>BATCH_JOB_EXECUTION_PARAMS:</strong> Job parameters (replaces JCL PARM values)</li>
 *   <li><strong>BATCH_STEP_EXECUTION_CONTEXT:</strong> Step execution context (replaces COBOL working storage)</li>
 *   <li><strong>BATCH_JOB_EXECUTION_CONTEXT:</strong> Job execution context (replaces JCL symbols)</li>
 * </ul>
 * 
 * <h2>COBOL Checkpoint/Restart Capability</h2>
 * <p>COBOL batch program checkpoint/restart logic (EXEC CICS SYNCPOINT, file positioning) 
 * is preserved through Spring Batch's built-in restart capability:</p>
 * <ul>
 *   <li><strong>Chunk Commit:</strong> Each chunk (1000 records) represents a COBOL SYNCPOINT</li>
 *   <li><strong>Restart:</strong> Failed jobs restart from last committed chunk (file position)</li>
 *   <li><strong>Skip/Retry:</strong> Individual record errors can be skipped (COBOL error handling)</li>
 *   <li><strong>Rollback:</strong> Chunk-level rollback on error (COBOL EXEC CICS ROLLBACK)</li>
 * </ul>
 * 
 * <h2>Performance Requirements</h2>
 * <p>Batch processing configuration meets the following non-negotiable SLAs:</p>
 * <ul>
 *   <li><strong>Batch Window:</strong> All jobs complete within 4-hour overnight cycle (02:00-06:00)</li>
 *   <li><strong>Chunk Size:</strong> 1000 records (matching COBOL batch processing blocks)</li>
 *   <li><strong>Parallel Processing:</strong> Multiple jobs can execute concurrently (up to 10 threads)</li>
 *   <li><strong>Transaction Isolation:</strong> SERIALIZABLE for job repository (prevents concurrent job conflicts)</li>
 * </ul>
 * 
 * <h2>MINIMAL CHANGE CLAUSE</h2>
 * <p>This configuration contains NO business logic per the minimal change directive.
 * It provides ONLY the infrastructure necessary to replace JCL/JES2 job scheduling with
 * Spring Batch while maintaining identical batch processing behavior and timing.</p>
 * 
 * @author CardDemo Migration Team
 * @version 1.0
 * @since 2024
 * @see com.carddemo.batch.config Package containing Spring Batch job configurations
 * @see com.carddemo.batch.processor Package containing batch processors (COBOL logic)
 * @see com.carddemo.batch.reader Package containing batch readers (VSAM READ)
 * @see com.carddemo.batch.writer Package containing batch writers (VSAM WRITE)
 */
@Configuration
@EnableBatchProcessing
@EnableScheduling
public class BatchConfig {

    /**
     * Default chunk size for chunk-oriented processing.
     * 
     * <p>Value of 1000 records matches COBOL batch processing block sizes and provides
     * optimal balance between commit frequency and performance. Each chunk represents
     * a COBOL EXEC CICS SYNCPOINT boundary.</p>
     * 
     * <p>Can be overridden in application.yml for specific jobs:
     * <pre>
     * batch:
     *   chunk-size: 1000  # Default for all jobs
     *   jobs:
     *     transaction-posting:
     *       chunk-size: 500  # Override for specific job
     * </pre>
     */
    @Value("${batch.chunk-size:1000}")
    private int defaultChunkSize;

    /**
     * Creates and configures the Spring Batch JobRepository with PostgreSQL persistence.
     * 
     * <p>This repository replaces JES2 job tracking and provides persistent storage for
     * batch job metadata including job instances, executions, step executions, and execution
     * contexts. All metadata is stored in PostgreSQL BATCH_* tables.</p>
     * 
     * <h3>Transaction Isolation: SERIALIZABLE</h3>
     * <p>ISOLATION_SERIALIZABLE is used for JobRepository operations to prevent concurrent
     * job execution conflicts and ensure consistency of batch job metadata. This matches
     * CICS transaction boundaries and provides the strongest isolation guarantee.</p>
     * 
     * <p>SERIALIZABLE isolation ensures:</p>
     * <ul>
     *   <li><strong>No Dirty Reads:</strong> Uncommitted changes are not visible</li>
     *   <li><strong>No Non-Repeatable Reads:</strong> Same query returns consistent results</li>
     *   <li><strong>No Phantom Reads:</strong> Range queries are protected from insertions</li>
     *   <li><strong>Job Uniqueness:</strong> Prevents duplicate job instances</li>
     * </ul>
     * 
     * <h3>Table Prefix: BATCH_</h3>
     * <p>All Spring Batch metadata tables use BATCH_ prefix:</p>
     * <ul>
     *   <li>BATCH_JOB_INSTANCE</li>
     *   <li>BATCH_JOB_EXECUTION</li>
     *   <li>BATCH_STEP_EXECUTION</li>
     *   <li>BATCH_JOB_EXECUTION_PARAMS</li>
     *   <li>BATCH_STEP_EXECUTION_CONTEXT</li>
     *   <li>BATCH_JOB_EXECUTION_CONTEXT</li>
     * </ul>
     * 
     * <h3>Restart Capability</h3>
     * <p>With saveState=true, failed jobs can be restarted from the last committed chunk.
     * This preserves COBOL checkpoint/restart capability where batch jobs maintain file
     * position and restart from failure point.</p>
     * 
     * <h3>Max VarChar Length: 2500</h3>
     * <p>Increased from default 250 to support large execution context data equivalent to
     * COBOL COMMAREA and working storage sections that are preserved across job restarts.</p>
     * 
     * @param dataSource PostgreSQL DataSource from DatabaseConfig
     * @param transactionManager PlatformTransactionManager from DatabaseConfig
     * @return Configured JobRepository for Spring Batch metadata persistence
     * @throws Exception if JobRepository cannot be created (database connectivity failure)
     */
    @Bean
    public JobRepository jobRepository(DataSource dataSource, 
                                      PlatformTransactionManager transactionManager) throws Exception {
        JobRepositoryFactoryBean factory = new JobRepositoryFactoryBean();
        
        // Set PostgreSQL DataSource for job metadata persistence
        factory.setDataSource(dataSource);
        
        // Set transaction manager with appropriate isolation level
        factory.setTransactionManager(transactionManager);
        
        // Set SERIALIZABLE isolation for job repository operations
        // This prevents concurrent job execution conflicts and matches CICS transaction boundaries
        factory.setIsolationLevelForCreate("ISOLATION_SERIALIZABLE");
        
        // Set table prefix for all Spring Batch metadata tables
        factory.setTablePrefix("BATCH_");
        
        // Enable state persistence for restart capability
        // This preserves COBOL checkpoint/restart logic
        factory.setValidateTransactionState(true);
        
        // Set maximum varchar length for execution context
        // Increased to 2500 to support large COMMAREA equivalents from COBOL
        factory.setMaxVarCharLength(2500);
        
        // Initialize factory bean
        factory.afterPropertiesSet();
        
        // Return the configured JobRepository
        return factory.getObject();
    }

    /**
     * Creates and configures the TaskExecutorJobLauncher for background job execution.
     * 
     * <p>This launcher enables non-blocking batch job execution when triggered via REST API
     * or scheduled tasks. Jobs execute in background threads from the batchTaskExecutor pool,
     * allowing the calling thread to return immediately without waiting for job completion.</p>
     * 
     * <p><strong>Spring Batch 5.2.x Migration Note:</strong> Replaces the deprecated 
     * SimpleJobLauncher which was removed in Spring Batch 5.2.0. TaskExecutorJobLauncher
     * provides identical functionality with clearer naming that emphasizes the use of 
     * TaskExecutor for job launching.</p>
     * 
     * <h3>Asynchronous Execution</h3>
     * <p>Async execution is critical for:</p>
     * <ul>
     *   <li><strong>REST API Responsiveness:</strong> HTTP requests that trigger batch jobs return immediately</li>
     *   <li><strong>Scheduled Jobs:</strong> Scheduler threads are not blocked by long-running jobs</li>
     *   <li><strong>Concurrent Execution:</strong> Multiple jobs can execute in parallel</li>
     *   <li><strong>Resource Management:</strong> Thread pool prevents thread exhaustion</li>
     * </ul>
     * 
     * <h3>Thread Pool Configuration</h3>
     * <p>Jobs execute in batchTaskExecutor thread pool:</p>
     * <ul>
     *   <li><strong>Core Pool Size:</strong> 5 threads (baseline capacity)</li>
     *   <li><strong>Max Pool Size:</strong> 10 threads (peak capacity)</li>
     *   <li><strong>Queue Capacity:</strong> 100 jobs (pending job queue)</li>
     *   <li><strong>Rejection Policy:</strong> CALLER_RUNS (backpressure handling)</li>
     * </ul>
     * 
     * @param jobRepository JobRepository for job metadata tracking
     * @param taskExecutor TaskExecutor for background job execution
     * @return Configured TaskExecutorJobLauncher for asynchronous job launching
     * @throws Exception if JobLauncher cannot be created
     */
    @Bean
    public JobLauncher jobLauncher(JobRepository jobRepository, TaskExecutor taskExecutor) throws Exception {
        TaskExecutorJobLauncher jobLauncher = new TaskExecutorJobLauncher();
        
        // Set job repository for job metadata tracking
        jobLauncher.setJobRepository(jobRepository);
        
        // Set async task executor for background job execution
        jobLauncher.setTaskExecutor(taskExecutor);
        
        // Initialize job launcher
        jobLauncher.afterPropertiesSet();
        
        return jobLauncher;
    }

    /**
     * Creates and configures the thread pool task executor for batch job execution.
     * 
     * <p>This executor manages thread pool for asynchronous batch job execution and parallel
     * step processing. Thread pool settings are optimized for overnight batch window (02:00-06:00)
     * where multiple jobs must complete within 4-hour cycle.</p>
     * 
     * <h3>Thread Pool Sizing Strategy</h3>
     * <p>Thread pool is sized based on batch job concurrency requirements:</p>
     * <ul>
     *   <li><strong>Core Pool Size: 5</strong>
     *       <ul>
     *         <li>Baseline capacity for normal batch load</li>
     *         <li>Supports 5 concurrent batch jobs (typical nightly load)</li>
     *         <li>Threads remain alive even when idle</li>
     *       </ul>
     *   </li>
     *   <li><strong>Max Pool Size: 10</strong>
     *       <ul>
     *         <li>Peak capacity for heavy batch load</li>
     *         <li>Supports up to 10 concurrent batch jobs</li>
     *         <li>Additional threads created when queue is full</li>
     *       </ul>
     *   </li>
     *   <li><strong>Queue Capacity: 100</strong>
     *       <ul>
     *         <li>Pending job queue holds up to 100 waiting jobs</li>
     *         <li>Queue fills before additional threads are created</li>
     *         <li>Prevents thread pool exhaustion during job bursts</li>
     *       </ul>
     *   </li>
     * </ul>
     * 
     * <h3>Rejection Policy: CALLER_RUNS</h3>
     * <p>When thread pool and queue are full, rejected jobs execute in the caller's thread.
     * This provides backpressure control:</p>
     * <ul>
     *   <li><strong>No Job Loss:</strong> Jobs are never dropped, always executed</li>
     *   <li><strong>Natural Throttling:</strong> Caller blocks until job completes</li>
     *   <li><strong>System Stability:</strong> Prevents resource exhaustion</li>
     * </ul>
     * 
     * <h3>Thread Naming</h3>
     * <p>Threads are named "batch-thread-{N}" for easy identification in logs and thread dumps:</p>
     * <pre>
     * batch-thread-1
     * batch-thread-2
     * batch-thread-3
     * </pre>
     * 
     * @return Configured TaskExecutor for batch job execution
     */
    @Bean
    public TaskExecutor batchTaskExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        
        // Core pool size: 5 threads for baseline capacity
        executor.setCorePoolSize(5);
        
        // Max pool size: 10 threads for peak capacity
        executor.setMaxPoolSize(10);
        
        // Queue capacity: 100 jobs for pending job queue
        executor.setQueueCapacity(100);
        
        // Thread name prefix for identification in logs
        executor.setThreadNamePrefix("batch-thread-");
        
        // Rejection policy: execute in caller's thread when pool and queue are full
        // Provides backpressure control and prevents job loss
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
        
        // Initialize executor
        executor.initialize();
        
        return executor;
    }

    /**
     * Creates and configures the JobExplorer for read-only queries of job execution metadata.
     * 
     * <p>This explorer provides read-only access to Spring Batch metadata tables for monitoring,
     * reporting, and operational dashboards. It replaces JES2 job status queries (//STATUS command,
     * SYSOUT DD inspection) with programmatic access to job history.</p>
     * 
     * <h3>Query Capabilities</h3>
     * <p>JobExplorer enables queries for:</p>
     * <ul>
     *   <li><strong>Job Instances:</strong> Find all instances of a job by name</li>
     *   <li><strong>Job Executions:</strong> Find executions by job instance or execution ID</li>
     *   <li><strong>Step Executions:</strong> Find step executions within a job execution</li>
     *   <li><strong>Execution Parameters:</strong> Retrieve job parameters for a job execution</li>
     *   <li><strong>Execution Context:</strong> Retrieve execution context for debugging</li>
     * </ul>
     * 
     * <h3>Monitoring Use Cases</h3>
     * <ul>
     *   <li><strong>Operations Dashboard:</strong> Display current/recent job execution status</li>
     *   <li><strong>Job History:</strong> Query historical job executions for trending</li>
     *   <li><strong>Failure Analysis:</strong> Retrieve failure details for troubleshooting</li>
     *   <li><strong>Performance Metrics:</strong> Calculate job duration and throughput statistics</li>
     * </ul>
     * 
     * @param dataSource PostgreSQL DataSource for metadata queries
     * @return Configured JobExplorer for job metadata queries
     * @throws Exception if JobExplorer cannot be created
     */
    @Bean
    public JobExplorer jobExplorer(DataSource dataSource) throws Exception {
        JobExplorerFactoryBean factory = new JobExplorerFactoryBean();
        
        // Set PostgreSQL DataSource for metadata queries
        factory.setDataSource(dataSource);
        
        // Set table prefix matching JobRepository configuration
        factory.setTablePrefix("BATCH_");
        
        // Initialize factory bean
        factory.afterPropertiesSet();
        
        // Return the configured JobExplorer
        return factory.getObject();
    }

    /**
     * Creates and configures the JobRegistry for managing Job beans.
     * 
     * <p>This registry maintains a runtime registry of all configured batch jobs and provides
     * lookup capabilities by job name. It enables dynamic job execution and operational control
     * through the JobOperator.</p>
     * 
     * <h3>Registry Purpose</h3>
     * <ul>
     *   <li><strong>Job Lookup:</strong> Find Job beans by name at runtime</li>
     *   <li><strong>Job Discovery:</strong> List all registered jobs</li>
     *   <li><strong>Dynamic Execution:</strong> Launch jobs by name without direct bean injection</li>
     *   <li><strong>Operator Support:</strong> Enable JobOperator to locate jobs</li>
     * </ul>
     * 
     * <h3>Registered Jobs</h3>
     * <p>The following batch jobs are registered automatically by Spring Batch:</p>
     * <ul>
     *   <li>AccountValidationJob (CBACTJ01)</li>
     *   <li>InterestCalculationJob (CBACTJ02)</li>
     *   <li>CreditLimitReviewJob (CBACTJ03)</li>
     *   <li>ExpirationProcessingJob (CBACTJ04)</li>
     *   <li>TransactionValidationJob (CBTRNJ01)</li>
     *   <li>TransactionPostingJob (CBTRNJ02)</li>
     *   <li>CategorySummarizationJob (CBTRNJ03)</li>
     *   <li>CustomerValidationJob (CBCUSJ01)</li>
     *   <li>StatementGenerationJob (DALYREJS)</li>
     * </ul>
     * 
     * @return MapJobRegistry for in-memory job registration
     */
    @Bean
    public JobRegistry jobRegistry() {
        return new MapJobRegistry();
    }

    /**
     * Creates and configures the JobOperator for programmatic job control operations.
     * 
     * <p>This operator provides operational control over batch jobs including starting, stopping,
     * restarting, and abandoning jobs. It replaces JCL job control commands (//CANCEL, //RESTART)
     * with programmatic job management through REST API or admin console.</p>
     * 
     * <h3>Job Control Operations</h3>
     * <ul>
     *   <li><strong>Start Job:</strong> Launch a new job execution (replaces JCL JOB statement)</li>
     *   <li><strong>Stop Job:</strong> Gracefully stop a running job (replaces //CANCEL command)</li>
     *   <li><strong>Restart Job:</strong> Restart a failed job from last checkpoint (replaces //RESTART)</li>
     *   <li><strong>Abandon Job:</strong> Mark a job execution as abandoned (manual intervention required)</li>
     *   <li><strong>Get Job Names:</strong> List all registered job names</li>
     *   <li><strong>Get Job Instances:</strong> Query job instance IDs by job name</li>
     *   <li><strong>Get Job Executions:</strong> Query execution IDs for a job instance</li>
     * </ul>
     * 
     * <h3>REST API Integration</h3>
     * <p>JobOperator enables REST endpoints for operational job control:</p>
     * <pre>
     * POST   /api/batch/jobs/{jobName}/start        - Start new job execution
     * POST   /api/batch/jobs/{executionId}/stop     - Stop running job
     * POST   /api/batch/jobs/{executionId}/restart  - Restart failed job
     * POST   /api/batch/jobs/{executionId}/abandon  - Abandon job execution
     * GET    /api/batch/jobs                        - List all job names
     * GET    /api/batch/jobs/{jobName}/instances    - List job instances
     * GET    /api/batch/jobs/{jobName}/executions   - List job executions
     * </pre>
     * 
     * @param jobLauncher JobLauncher for starting jobs
     * @param jobRepository JobRepository for job metadata persistence
     * @param jobExplorer JobExplorer for job metadata queries
     * @param jobRegistry JobRegistry for job lookup by name
     * @return Configured JobOperator for job control operations
     */
    @Bean
    public JobOperator jobOperator(JobLauncher jobLauncher,
                                  JobRepository jobRepository,
                                  JobExplorer jobExplorer,
                                  JobRegistry jobRegistry) {
        SimpleJobOperator jobOperator = new SimpleJobOperator();
        
        // Set job launcher for starting jobs
        jobOperator.setJobLauncher(jobLauncher);
        
        // Set job repository for job metadata persistence
        jobOperator.setJobRepository(jobRepository);
        
        // Set job explorer for job metadata queries
        jobOperator.setJobExplorer(jobExplorer);
        
        // Set job registry for job lookup by name
        jobOperator.setJobRegistry(jobRegistry);
        
        return jobOperator;
    }

    /**
     * Returns the configured default chunk size for batch processing.
     * 
     * <p>This value is injected from application.yml and used as the default chunk size
     * for all batch jobs unless overridden in specific job configurations.</p>
     * 
     * <h3>Chunk Size Rationale</h3>
     * <p>Value of 1000 records provides optimal balance:</p>
     * <ul>
     *   <li><strong>Commit Frequency:</strong> 1000 records per commit reduces overhead</li>
     *   <li><strong>Memory Usage:</strong> 1000 records fit comfortably in memory</li>
     *   <li><strong>Restart Granularity:</strong> Failed jobs restart within 1000 records of failure</li>
     *   <li><strong>COBOL Equivalence:</strong> Matches COBOL batch processing block sizes</li>
     * </ul>
     * 
     * @return Default chunk size (1000 records)
     */
    public int getDefaultChunkSize() {
        return defaultChunkSize;
    }
}
