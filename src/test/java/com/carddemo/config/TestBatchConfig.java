package com.carddemo.config;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.batch.core.launch.JobLauncher;
import org.springframework.batch.core.launch.support.TaskExecutorJobLauncher;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.context.annotation.Profile;
import org.springframework.core.task.SyncTaskExecutor;

/**
 * Test Configuration for Spring Batch Jobs
 * 
 * <p>This test configuration overrides production batch configuration to provide
 * synchronous job execution during testing. The production BatchConfig uses an
 * asynchronous TaskExecutor for the JobLauncher, which causes tests to complete
 * before the job finishes executing, resulting in STARTING status instead of COMPLETED.</p>
 * 
 * <p><strong>Problem with Async JobLauncher:</strong></p>
 * <p>Production configuration uses ThreadPoolTaskExecutor:</p>
 * <pre>
 * {@code
 * @Bean
 * public JobLauncher jobLauncher(JobRepository jobRepository) {
 *     TaskExecutorJobLauncher jobLauncher = new TaskExecutorJobLauncher();
 *     jobLauncher.setTaskExecutor(batchTaskExecutor()); // Async!
 *     return jobLauncher;
 * }
 * }
 * </pre>
 * 
 * <p>This causes test failures:</p>
 * <ul>
 *   <li>jobLauncherTestUtils.launchJob() returns immediately with STARTING status</li>
 *   <li>Job executes asynchronously in background thread</li>
 *   <li>Test assertions fail: expected COMPLETED but was STARTING</li>
 * </ul>
 * 
 * <p><strong>Solution - Synchronous JobLauncher:</strong></p>
 * <p>Override JobLauncher with SyncTaskExecutor for tests:</p>
 * <ul>
 *   <li>SyncTaskExecutor executes tasks in the calling thread</li>
 *   <li>jobLauncherTestUtils.launchJob() waits for job completion</li>
 *   <li>Test assertions see final job status (COMPLETED, FAILED, etc.)</li>
 * </ul>
 * 
 * <p><strong>@Primary Annotation:</strong></p>
 * <p>The @Primary annotation ensures this synchronous JobLauncher bean takes precedence
 * over the async JobLauncher from production BatchConfig when the test context loads.</p>
 * 
 * <p><strong>Test Profile Activation:</strong></p>
 * <p>This configuration is activated by @TestConfiguration and loaded automatically
 * when tests use @SpringBootTest. No explicit profile activation needed.</p>
 * 
 * @see org.springframework.batch.test.JobLauncherTestUtils
 * @see com.carddemo.config.BatchConfig
 */
@Slf4j
@Configuration
@Profile("test")
@RequiredArgsConstructor
public class TestBatchConfig {

    /**
     * Configure synchronous JobLauncher for testing.
     * 
     * <p>This bean overrides the production JobLauncher (marked with @Primary) to use
     * SyncTaskExecutor instead of ThreadPoolTaskExecutor. This ensures that batch job
     * execution completes synchronously within test methods, allowing tests to assert
     * on final job execution status.</p>
     * 
     * <p><strong>Synchronous Execution Flow:</strong></p>
     * <ol>
     *   <li>Test calls jobLauncherTestUtils.launchJob()</li>
     *   <li>JobLauncherTestUtils delegates to this JobLauncher</li>
     *   <li>SyncTaskExecutor runs job in calling thread (blocking)</li>
     *   <li>launchJob() returns only after job completes</li>
     *   <li>Test assertions see final status (COMPLETED/FAILED)</li>
     * </ol>
     * 
     * <p><strong>Production vs Test Behavior:</strong></p>
     * <table border="1">
     *   <tr>
     *     <th>Aspect</th>
     *     <th>Production (Async)</th>
     *     <th>Test (Sync)</th>
     *   </tr>
     *   <tr>
     *     <td>TaskExecutor</td>
     *     <td>ThreadPoolTaskExecutor</td>
     *     <td>SyncTaskExecutor</td>
     *   </tr>
     *   <tr>
     *     <td>Execution</td>
     *     <td>Background thread</td>
     *     <td>Calling thread</td>
     *   </tr>
     *   <tr>
     *     <td>launchJob() Returns</td>
     *     <td>Immediately (STARTING)</td>
     *     <td>After completion (COMPLETED/FAILED)</td>
     *   </tr>
     *   <tr>
     *     <td>Use Case</td>
     *     <td>Non-blocking REST endpoints</td>
     *     <td>Deterministic test assertions</td>
     *   </tr>
     * </table>
     * 
     * <p><strong>Example Test Behavior:</strong></p>
     * <pre>
     * {@code
     * // With async JobLauncher (FAILS):
     * JobExecution execution = jobLauncherTestUtils.launchJob();
     * // Returns immediately, execution.getStatus() == STARTING
     * assertThat(execution.getStatus()).isEqualTo(BatchStatus.COMPLETED); // FAILS!
     * 
     * // With sync JobLauncher (PASSES):
     * JobExecution execution = jobLauncherTestUtils.launchJob();
     * // Blocks until job completes, execution.getStatus() == COMPLETED
     * assertThat(execution.getStatus()).isEqualTo(BatchStatus.COMPLETED); // PASSES!
     * }
     * </pre>
     * 
     * @param jobRepository Spring Batch job repository for metadata persistence
     * @return JobLauncher configured with synchronous executor for testing
     * @throws Exception if launcher configuration fails
     */
    @Bean
    @Primary
    public JobLauncher jobLauncher(JobRepository jobRepository) throws Exception {
        log.info("Configuring SYNCHRONOUS job launcher for testing");
        
        TaskExecutorJobLauncher jobLauncher = new TaskExecutorJobLauncher();
        jobLauncher.setJobRepository(jobRepository);
        
        // Use SyncTaskExecutor instead of ThreadPoolTaskExecutor
        // This makes job execution synchronous (blocking) for tests
        jobLauncher.setTaskExecutor(new SyncTaskExecutor());
        
        jobLauncher.afterPropertiesSet();
        
        log.debug("Synchronous job launcher configured successfully");
        return jobLauncher;
    }
}
