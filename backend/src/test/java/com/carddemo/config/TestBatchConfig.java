/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.carddemo.config;

import org.springframework.batch.core.launch.JobLauncher;
import org.springframework.batch.core.launch.support.TaskExecutorJobLauncher;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.context.annotation.Profile;
import org.springframework.core.task.SyncTaskExecutor;

/**
 * Test configuration for Spring Batch that provides a synchronous JobLauncher.
 * 
 * <p>This configuration overrides the production async JobLauncher to ensure
 * batch job tests execute synchronously and can assert on job completion status.
 * 
 * <p>The synchronous executor ensures that when jobLauncher.run() returns,
 * the job has completed execution and the final status can be checked immediately.
 * 
 * <p>This configuration is only active in the "test" profile to avoid interfering
 * with production async job execution.
 * 
 * @see org.springframework.batch.core.launch.JobLauncher
 * @see org.springframework.core.task.SyncTaskExecutor
 */
@Configuration
@Profile("test")
public class TestBatchConfig {

    /**
     * Provides a synchronous JobLauncher for batch job tests.
     * 
     * <p>Uses @Primary to override the async JobLauncher bean from BatchConfig.
     * The SyncTaskExecutor runs jobs in the calling thread, ensuring synchronous execution.
     * 
     * @param jobRepository the Spring Batch job repository
     * @return JobLauncher configured with synchronous execution
     * @throws Exception if job launcher initialization fails
     */
    @Bean
    @Primary
    public JobLauncher jobLauncher(JobRepository jobRepository) throws Exception {
        TaskExecutorJobLauncher jobLauncher = new TaskExecutorJobLauncher();
        jobLauncher.setJobRepository(jobRepository);
        // Use SyncTaskExecutor for synchronous execution in tests
        jobLauncher.setTaskExecutor(new SyncTaskExecutor());
        jobLauncher.afterPropertiesSet();
        return jobLauncher;
    }
}
