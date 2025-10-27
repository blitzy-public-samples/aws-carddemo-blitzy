package com.carddemo.batch.config;

import org.springframework.batch.core.launch.JobLauncher;
import org.springframework.batch.core.launch.support.TaskExecutorJobLauncher;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.core.task.SyncTaskExecutor;

/**
 * Test-specific Spring Batch configuration.
 * 
 * Provides synchronous JobLauncher for integration tests to ensure
 * jobs complete before test assertions are executed.
 * 
 * Production configuration uses TaskExecutorJobLauncher with thread pool
 * for asynchronous background execution. Test configuration overrides
 * with SyncTaskExecutor to make job execution synchronous and deterministic.
 * 
 * Without this configuration, tests would fail because:
 * - Production JobLauncher returns immediately (async)
 * - Test assertions execute before job completes
 * - Job status remains STARTING instead of COMPLETED
 * 
 * @see org.springframework.batch.core.launch.support.TaskExecutorJobLauncher
 * @see org.springframework.core.task.SyncTaskExecutor
 */
@TestConfiguration
public class TestBatchConfig {

    /**
     * Creates synchronous JobLauncher for tests.
     * 
     * Configures JobLauncher with SyncTaskExecutor to execute jobs
     * synchronously on the calling thread, ensuring job completion
     * before launchJob() method returns.
     * 
     * @Primary annotation ensures this bean overrides production JobLauncher
     * in test context, preventing async execution during tests.
     * 
     * @param jobRepository Spring Batch job repository for metadata
     * @return JobLauncher configured for synchronous execution
     * @throws Exception if JobLauncher initialization fails
     */
    @Bean
    @Primary
    public JobLauncher syncJobLauncher(JobRepository jobRepository) throws Exception {
        TaskExecutorJobLauncher jobLauncher = new TaskExecutorJobLauncher();
        jobLauncher.setJobRepository(jobRepository);
        // Use SyncTaskExecutor for synchronous execution in tests
        jobLauncher.setTaskExecutor(new SyncTaskExecutor());
        jobLauncher.afterPropertiesSet();
        return jobLauncher;
    }
}
