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

import org.springframework.context.annotation.Configuration;

/**
 * Spring Batch infrastructure configuration for the CardDemo application.
 *
 * <p>This configuration class serves as the anchor for Spring Batch customization
 * in the migrated CardDemo application. Spring Boot 3.5.x auto-configures the
 * core Spring Batch infrastructure — {@code JobRepository}, {@code JobLauncher},
 * {@code JobExplorer}, and {@code PlatformTransactionManager} — without requiring
 * {@code @EnableBatchProcessing}. The auto-configured {@code JobLauncher} already
 * uses a {@code SyncTaskExecutor} (synchronous execution in the calling thread),
 * which preserves the original JCL batch window semantics where each step completed
 * before the next began.</p>
 *
 * <p><strong>Important:</strong> No custom {@code JobLauncher} bean is defined here
 * because it would conflict with the Spring Boot auto-configured bean and cause a
 * {@code BeanDefinitionOverrideException} at startup (bean overriding is disabled
 * by default in Spring Boot 3.x). The auto-configured launcher provides identical
 * synchronous execution semantics.</p>
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
 * <p>Individual job bean definitions reside in their own *JobConfig.java classes
 * within the {@code batch/job/} package. This class provides the shared configuration
 * anchor and may be extended with additional batch infrastructure beans (e.g., custom
 * listeners, skip policies) as individual job configurations are implemented.</p>
 *
 * @see org.springframework.batch.core.launch.JobLauncher
 */
@Configuration
public class BatchConfig {
    // Spring Boot 3.5.x auto-configures Spring Batch infrastructure:
    //   - JobRepository: persists batch metadata (BATCH_JOB_INSTANCE, etc.)
    //   - JobLauncher: executes jobs synchronously via SyncTaskExecutor (default)
    //   - PlatformTransactionManager: shared with JPA for single-datasource setup
    //
    // No custom beans are needed at this point. The auto-configured JobLauncher
    // uses SyncTaskExecutor by default, matching the JCL batch window semantics
    // where POSTTRAN → INTCALC → COMBTRAN → CREASTMT ran sequentially.
    //
    // Future job-specific beans (listeners, skip policies, etc.) can be added
    // here as individual job configurations are implemented.
}
