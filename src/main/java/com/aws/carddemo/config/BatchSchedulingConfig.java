/*
 * Copyright Amazon.com, Inc. or its affiliates.
 * All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License").
 * You may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND,
 * either express or implied. See the License for the specific
 * language governing permissions and limitations under the License.
 */
package com.aws.carddemo.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.JobExecution;
import org.springframework.batch.core.JobExecutionException;
import org.springframework.batch.core.JobParameters;
import org.springframework.batch.core.JobParametersBuilder;
import org.springframework.batch.core.launch.JobLauncher;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

/**
 * Production <strong>batch scheduling</strong> configuration: time-triggers the eleven Spring Batch
 * jobs migrated from the legacy z/OS JCL job streams (Agent Action Plan &sect;0.4.1; Refine-PR
 * directive D4 &mdash; "wire the existing 11 Spring Batch jobs for production scheduling").
 *
 * <p>In the legacy system each batch program is run by submitting a JCL member through the job
 * scheduler / internal reader (for example {@code POSTTRAN.jcl}, {@code INTCALC.jcl}, {@code
 * CREASTMT.JCL}, {@code TRANREPT.jcl}). This class replaces that external scheduling with Spring's
 * {@link Scheduled @Scheduled} cron triggers, launching each job bean through the auto-configured
 * {@link JobLauncher}. It mirrors the on-demand launch pattern already used by {@code
 * com.aws.carddemo.service.online.ReportService} (which submits {@code transactionReportJob} from
 * the {@code CR00} online screen).
 *
 * <h2>Why this is purely additive (no architectural change)</h2>
 *
 * <ul>
 *   <li><b>No {@code @EnableBatchProcessing}.</b> Exactly as {@code CardDemoApplication} documents,
 *       declaring {@code @EnableBatchProcessing} would switch off Spring Boot's batch
 *       auto-configuration ({@code DefaultBatchConfiguration}) and the {@code spring.batch.*}
 *       properties, replacing the auto-wired {@code JobRepository}/{@code JobLauncher}. This class
 *       therefore only <em>consumes</em> the already auto-configured {@link JobLauncher} and the
 *       eleven existing {@link Job} beans &mdash; it defines no new batch infrastructure.
 *   <li><b>{@code spring.batch.job.enabled=false} is preserved.</b> Jobs still never auto-run on
 *       context startup; they run only when a cron trigger here fires (or when {@code
 *       ReportService} launches the report job on demand).
 *   <li><b>{@link EnableScheduling @EnableScheduling} lives here, not on the application class</b>,
 *       so the scheduling infrastructure is created only alongside this configuration and never
 *       perturbs the component-scan root or batch auto-configuration.
 * </ul>
 *
 * <h2>Activation gating (keeps the test suite hermetic)</h2>
 *
 * <p>The whole configuration is guarded by {@link
 * ConditionalOnProperty @ConditionalOnProperty("carddemo.batch.scheduling.enabled" = "true")} with
 * {@code matchIfMissing=false}. The property is set <em>only</em> in the {@code prod} profile
 * ({@code application-prod.yml}); it is absent from {@code application.yml} and {@code
 * application-test.yml}. Because the condition is evaluated before the configuration class is
 * processed, when the property is missing the class &mdash; together with its
 * {@code @EnableScheduling} import and every {@code @Scheduled} trigger &mdash; is not registered
 * at all. Consequently the existing test suite (every test runs under
 * {@code @ActiveProfiles("test")}) never instantiates this bean and no job is ever launched during
 * tests.
 *
 * <h2>Job-instance uniqueness</h2>
 *
 * <p>Spring Batch identifies a {@code JobInstance} by its identifying {@link JobParameters} and
 * refuses to re-run an instance that has already completed ({@code
 * JobInstanceAlreadyCompleteException}). Each scheduled launch therefore adds a unique {@value
 * #PARAM_SCHEDULED_AT} parameter (the current epoch millisecond), so every fire creates a fresh
 * {@code JobInstance} &mdash; the same job can run on every scheduled tick. This reproduces the
 * {@code requestedAt} uniqueness convention already used by {@code ReportService}.
 *
 * <h2>Schedules</h2>
 *
 * <p>Each job has its own cron expression, externalized as {@code
 * carddemo.batch.scheduling.cron.<job>} with a sensible staggered overnight default that follows
 * the legacy nightly batch order (master extracts &rarr; daily-transaction post &rarr; posting
 * &rarr; interest &rarr; combine &rarr; statements &rarr; reports). Operators can retune any
 * expression, or disable an individual job by setting its cron to {@code "-"} (Spring's
 * disabled-trigger sentinel) without touching code. The default single-threaded task scheduler runs
 * the synchronous {@link JobLauncher#run(Job, JobParameters)} calls serially, naturally preserving
 * the sequential legacy batch window; set {@code spring.task.scheduling.pool.size} to run
 * overlapping windows concurrently.
 */
@Configuration
@EnableScheduling
@ConditionalOnProperty(prefix = "carddemo.batch.scheduling", name = "enabled", havingValue = "true")
public class BatchSchedulingConfig {

  private static final Logger log = LoggerFactory.getLogger(BatchSchedulingConfig.class);

  /**
   * Identifying job parameter added to every scheduled launch to guarantee a unique {@code
   * JobInstance} per trigger (the current epoch millisecond).
   */
  static final String PARAM_SCHEDULED_AT = "scheduledAt";

  private final JobLauncher jobLauncher;
  private final Job accountExtractJob;
  private final Job cardExtractJob;
  private final Job customerExtractJob;
  private final Job xrefExtractJob;
  private final Job dailyTransactionPostJob;
  private final Job transactionPostingJob;
  private final Job interestCalculationJob;
  private final Job transactionCombineJob;
  private final Job statementGenerationJob;
  private final Job transactionReportJob;
  private final Job categoryBalanceReportJob;

  /**
   * Injects the auto-configured {@link JobLauncher} and each of the eleven existing batch {@link
   * Job} beans by name. The {@link Qualifier} on every {@link Job} parameter disambiguates the
   * beans (all share the {@code Job} type) and binds each to its {@code @Bean}-method name declared
   * under {@code com.aws.carddemo.batch.config}.
   *
   * @param jobLauncher the Spring Boot auto-configured synchronous job launcher
   * @param accountExtractJob account-master extract/print job ({@code CBACT01C} / {@code
   *     READACCT.jcl})
   * @param cardExtractJob card-master extract/print job ({@code CBACT02C} / {@code READCARD.jcl})
   * @param customerExtractJob customer-master extract/print job ({@code CBCUS01C} / {@code
   *     READCUST.jcl})
   * @param xrefExtractJob card-xref extract/print job ({@code CBACT03C} / {@code READXREF.jcl})
   * @param dailyTransactionPostJob daily-transaction post job ({@code CBTRN01C})
   * @param transactionPostingJob daily transaction posting job ({@code CBTRN02C} / {@code
   *     POSTTRAN.jcl})
   * @param interestCalculationJob interest-calculation job ({@code CBACT04C} / {@code INTCALC.jcl})
   * @param transactionCombineJob transaction sort/combine job ({@code COMBTRAN.jcl} / {@code
   *     REPROCT.ctl})
   * @param statementGenerationJob statement-generation job ({@code CBSTM03A}/{@code CBSTM03B} /
   *     {@code CREASTMT.JCL})
   * @param transactionReportJob transaction-detail report job ({@code CBTRN03C} / {@code
   *     TRANREPT.jcl})
   * @param categoryBalanceReportJob transaction-category-balance report job ({@code PRTCATBL.jcl})
   */
  public BatchSchedulingConfig(
      JobLauncher jobLauncher,
      @Qualifier("accountExtractJob") Job accountExtractJob,
      @Qualifier("cardExtractJob") Job cardExtractJob,
      @Qualifier("customerExtractJob") Job customerExtractJob,
      @Qualifier("xrefExtractJob") Job xrefExtractJob,
      @Qualifier("dailyTransactionPostJob") Job dailyTransactionPostJob,
      @Qualifier("transactionPostingJob") Job transactionPostingJob,
      @Qualifier("interestCalculationJob") Job interestCalculationJob,
      @Qualifier("transactionCombineJob") Job transactionCombineJob,
      @Qualifier("statementGenerationJob") Job statementGenerationJob,
      @Qualifier("transactionReportJob") Job transactionReportJob,
      @Qualifier("categoryBalanceReportJob") Job categoryBalanceReportJob) {
    this.jobLauncher = jobLauncher;
    this.accountExtractJob = accountExtractJob;
    this.cardExtractJob = cardExtractJob;
    this.customerExtractJob = customerExtractJob;
    this.xrefExtractJob = xrefExtractJob;
    this.dailyTransactionPostJob = dailyTransactionPostJob;
    this.transactionPostingJob = transactionPostingJob;
    this.interestCalculationJob = interestCalculationJob;
    this.transactionCombineJob = transactionCombineJob;
    this.statementGenerationJob = statementGenerationJob;
    this.transactionReportJob = transactionReportJob;
    this.categoryBalanceReportJob = categoryBalanceReportJob;
  }

  /** Launches the account-master extract job (default 01:00 daily). */
  @Scheduled(cron = "${carddemo.batch.scheduling.cron.account-extract:0 0 1 * * *}")
  public void scheduleAccountExtractJob() {
    launch(accountExtractJob, "accountExtractJob");
  }

  /** Launches the card-master extract job (default 01:10 daily). */
  @Scheduled(cron = "${carddemo.batch.scheduling.cron.card-extract:0 10 1 * * *}")
  public void scheduleCardExtractJob() {
    launch(cardExtractJob, "cardExtractJob");
  }

  /** Launches the customer-master extract job (default 01:20 daily). */
  @Scheduled(cron = "${carddemo.batch.scheduling.cron.customer-extract:0 20 1 * * *}")
  public void scheduleCustomerExtractJob() {
    launch(customerExtractJob, "customerExtractJob");
  }

  /** Launches the card-xref extract job (default 01:30 daily). */
  @Scheduled(cron = "${carddemo.batch.scheduling.cron.xref-extract:0 30 1 * * *}")
  public void scheduleXrefExtractJob() {
    launch(xrefExtractJob, "xrefExtractJob");
  }

  /** Launches the daily-transaction post job (default 02:00 daily). */
  @Scheduled(cron = "${carddemo.batch.scheduling.cron.daily-transaction-post:0 0 2 * * *}")
  public void scheduleDailyTransactionPostJob() {
    launch(dailyTransactionPostJob, "dailyTransactionPostJob");
  }

  /** Launches the daily transaction posting job (default 02:30 daily). */
  @Scheduled(cron = "${carddemo.batch.scheduling.cron.transaction-posting:0 30 2 * * *}")
  public void scheduleTransactionPostingJob() {
    launch(transactionPostingJob, "transactionPostingJob");
  }

  /** Launches the interest-calculation job (default 03:00 daily). */
  @Scheduled(cron = "${carddemo.batch.scheduling.cron.interest-calculation:0 0 3 * * *}")
  public void scheduleInterestCalculationJob() {
    launch(interestCalculationJob, "interestCalculationJob");
  }

  /** Launches the transaction sort/combine job (default 03:30 daily). */
  @Scheduled(cron = "${carddemo.batch.scheduling.cron.transaction-combine:0 30 3 * * *}")
  public void scheduleTransactionCombineJob() {
    launch(transactionCombineJob, "transactionCombineJob");
  }

  /** Launches the statement-generation job (default 04:00 daily). */
  @Scheduled(cron = "${carddemo.batch.scheduling.cron.statement-generation:0 0 4 * * *}")
  public void scheduleStatementGenerationJob() {
    launch(statementGenerationJob, "statementGenerationJob");
  }

  /** Launches the transaction-detail report job (default 04:30 daily). */
  @Scheduled(cron = "${carddemo.batch.scheduling.cron.transaction-report:0 30 4 * * *}")
  public void scheduleTransactionReportJob() {
    launch(transactionReportJob, "transactionReportJob");
  }

  /** Launches the transaction-category-balance report job (default 05:00 daily). */
  @Scheduled(cron = "${carddemo.batch.scheduling.cron.category-balance-report:0 0 5 * * *}")
  public void scheduleCategoryBalanceReportJob() {
    launch(categoryBalanceReportJob, "categoryBalanceReportJob");
  }

  /**
   * Launches a batch job through the auto-configured {@link JobLauncher}, stamping a unique {@value
   * #PARAM_SCHEDULED_AT} parameter so each scheduled tick creates a fresh {@code JobInstance}.
   *
   * <p>Job parameters other than {@value #PARAM_SCHEDULED_AT} are intentionally omitted so each job
   * uses its own established, legacy-faithful defaults (for example the {@code interestCalculation}
   * {@code runDate} {@code PARM='2022071800'} and the {@code transactionReport} default date
   * window), preserving 100% behavioral parity. The launch is wrapped to handle {@link
   * JobExecutionException} (the checked superclass of every exception {@link JobLauncher#run(Job,
   * JobParameters)} declares) so a launch failure is logged rather than propagated out of the
   * scheduler thread, mirroring the defensive launch in {@code ReportService}.
   *
   * @param job the batch job to launch
   * @param jobName the job's bean name, used only for log correlation
   */
  private void launch(Job job, String jobName) {
    try {
      JobParameters parameters =
          new JobParametersBuilder()
              .addLong(PARAM_SCHEDULED_AT, System.currentTimeMillis())
              .toJobParameters();
      JobExecution execution = jobLauncher.run(job, parameters);
      log.info(
          "Scheduled batch job '{}' launched: jobExecutionId={}, status={}",
          jobName,
          execution.getId(),
          execution.getStatus());
    } catch (JobExecutionException e) {
      log.error("Scheduled batch job '{}' failed to launch", jobName, e);
    }
  }
}
