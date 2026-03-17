/*
 * ============================================================================
 * BatchConfig.java — Spring Batch Infrastructure Configuration
 * ============================================================================
 * Migrated from: JCL batch job orchestration infrastructure
 *
 * This configuration class provides the core Spring Batch infrastructure
 * that replaces the JCL job scheduling and execution environment on the
 * mainframe. The original JCL batch window executed jobs sequentially:
 *
 *   CLOSEFIL → POSTTRAN → INTCALC → COMBTRAN → CREASTMT → OPENFIL
 *
 * In the Java migration:
 *   - CLOSEFIL/OPENFIL have no equivalent (database connections are pooled)
 *   - POSTTRAN   → DailyPostingJobConfig   (← CBTRN02C.cbl)
 *   - INTCALC    → InterestCalcJobConfig    (← CBACT04C.cbl)
 *   - COMBTRAN   → TransactionSortJobConfig (← JCL SORT utility)
 *   - CREASTMT   → StatementGenJobConfig    (← CBSTM03A.CBL / CBSTM03B.CBL)
 *
 * Additional seed-data loader jobs (no JCL batch-window equivalent):
 *   - AccountLoadJobConfig     (← CBACT01C.cbl)
 *   - CustomerLoadJobConfig    (← CBCUS01C.cbl)
 *   - TransactionLoadJobConfig (← CBTRN01C.cbl)
 *
 * Design decisions:
 *   - A custom JobLauncher is defined with a SyncTaskExecutor to enforce
 *     sequential (single-threaded) job execution. This preserves the JCL
 *     batch window semantics where each step completed before the next began.
 *   - The batch metadata tables (BATCH_JOB_INSTANCE, BATCH_JOB_EXECUTION,
 *     etc.) share the same PostgreSQL datasource as the application data,
 *     configured via spring.batch.jdbc.initialize-schema in application.yml.
 *   - The JPA transaction manager is shared with Spring Batch, matching the
 *     single-datasource modular-monolith architecture.
 *   - Individual job bean definitions reside in their own *JobConfig.java
 *     classes within the batch/job/ package — this class provides only the
 *     shared infrastructure.
 *
 * Copyright Amazon.com, Inc. or its affiliates.
 * Licensed under the Apache License, Version 2.0.
 * ============================================================================
 */
package com.cardemo.config;

import org.springframework.batch.core.launch.JobLauncher;
import org.springframework.batch.core.launch.support.TaskExecutorJobLauncher;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.task.SyncTaskExecutor;

/**
 * Spring Batch infrastructure configuration for the CardDemo application.
 *
 * <p>This configuration provides a custom {@link JobLauncher} that executes
 * batch jobs synchronously in the calling thread. This design mirrors the
 * original JCL batch window on the mainframe where jobs ran sequentially
 * (POSTTRAN → INTCALC → COMBTRAN → CREASTMT) with each step completing
 * before the next one started.</p>
 *
 * <h3>JCL-to-Spring-Batch Mapping</h3>
 * <table>
 *   <tr><th>JCL Job</th><th>Spring Batch Config</th><th>COBOL Source</th></tr>
 *   <tr><td>POSTTRAN</td><td>DailyPostingJobConfig</td><td>CBTRN02C.cbl</td></tr>
 *   <tr><td>INTCALC</td><td>InterestCalcJobConfig</td><td>CBACT04C.cbl</td></tr>
 *   <tr><td>COMBTRAN</td><td>TransactionSortJobConfig</td><td>SORT utility</td></tr>
 *   <tr><td>CREASTMT</td><td>StatementGenJobConfig</td><td>CBSTM03A/B.CBL</td></tr>
 *   <tr><td>ACCTFILE</td><td>AccountLoadJobConfig</td><td>CBACT01C.cbl</td></tr>
 *   <tr><td>CUSTFILE</td><td>CustomerLoadJobConfig</td><td>CBCUS01C.cbl</td></tr>
 *   <tr><td>TRANFILE</td><td>TransactionLoadJobConfig</td><td>CBTRN01C.cbl</td></tr>
 * </table>
 *
 * <p>Note: JCL CLOSEFIL and OPENFIL jobs have no Java equivalent — database
 * connection lifecycle is managed by the HikariCP connection pool.</p>
 *
 * @see org.springframework.batch.core.launch.JobLauncher
 * @see org.springframework.batch.core.launch.support.TaskExecutorJobLauncher
 */
@Configuration
public class BatchConfig {

    /**
     * Creates a custom {@link JobLauncher} configured for synchronous execution.
     *
     * <p>The launcher uses a {@link SyncTaskExecutor} to run batch jobs in the
     * calling thread, ensuring sequential execution that matches the original
     * JCL batch window semantics. In the mainframe environment, the JCL batch
     * window enforced strict ordering:</p>
     * <ol>
     *   <li>CLOSEFIL — Close CICS files (not applicable in Java)</li>
     *   <li>POSTTRAN — Post daily transactions (CBTRN02C.cbl)</li>
     *   <li>INTCALC — Calculate interest (CBACT04C.cbl)</li>
     *   <li>COMBTRAN — Sort/combine transactions (SORT utility)</li>
     *   <li>CREASTMT — Generate statements (CBSTM03A/B.CBL)</li>
     *   <li>OPENFIL — Re-open CICS files (not applicable in Java)</li>
     * </ol>
     *
     * <p>Using {@link SyncTaskExecutor} prevents concurrent job execution that
     * would violate the COBOL batch processing parity requirement. When batch
     * jobs are triggered programmatically (e.g., via a REST endpoint or
     * scheduler), they execute one at a time in the order they are launched.</p>
     *
     * @param jobRepository the Spring Batch {@link JobRepository} for persisting
     *                      job metadata (execution status, step progress, restart
     *                      data) — auto-configured by Spring Boot to use the
     *                      application's PostgreSQL datasource
     * @return a fully initialized {@link JobLauncher} with synchronous execution
     * @throws Exception if the launcher initialization fails during
     *                   {@link TaskExecutorJobLauncher#afterPropertiesSet()}
     */
    @Bean
    public JobLauncher jobLauncher(JobRepository jobRepository) throws Exception {
        TaskExecutorJobLauncher launcher = new TaskExecutorJobLauncher();

        // Bind the job repository for batch metadata persistence.
        // Spring Batch stores job instances, executions, step executions,
        // and execution context in the BATCH_* tables managed by this repository.
        launcher.setJobRepository(jobRepository);

        // Configure synchronous task execution to match JCL batch window semantics.
        // SyncTaskExecutor runs tasks in the calling thread, ensuring that each
        // batch job completes before the next one can start — identical to the
        // mainframe JCL step-by-step execution model where COND codes controlled
        // sequential flow: POSTTRAN → INTCALC → COMBTRAN → CREASTMT.
        launcher.setTaskExecutor(new SyncTaskExecutor());

        // Validate the launcher configuration and initialize internal state.
        // This mirrors the JCL JOB card validation that occurred before execution.
        launcher.afterPropertiesSet();

        return launcher;
    }
}
