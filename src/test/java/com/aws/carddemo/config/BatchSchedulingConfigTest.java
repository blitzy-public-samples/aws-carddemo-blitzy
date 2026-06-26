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

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.JobExecution;
import org.springframework.batch.core.JobParameters;
import org.springframework.batch.core.launch.JobLauncher;
import org.springframework.batch.core.repository.JobInstanceAlreadyCompleteException;

/**
 * Pure-Mockito unit test for {@link BatchSchedulingConfig}. It proves the production batch
 * scheduler wires each of the eleven {@code @Scheduled} trigger methods to its own job bean,
 * launches every job through the injected {@link JobLauncher} with a unique {@code scheduledAt}
 * identifying parameter (so each tick creates a fresh {@code JobInstance}), and swallows a launch
 * failure rather than letting it escape the scheduler thread.
 *
 * <p>The test is deliberately context-free: it constructs the configuration directly with mock
 * collaborators, so it neither starts a Spring context nor activates {@code @EnableScheduling} (no
 * cron ever fires) nor touches AWS or a real {@code JobRepository}. This mirrors the gating that
 * keeps {@link BatchSchedulingConfig} inert in every other test (the {@code
 * carddemo.batch.scheduling.enabled} property is never set under {@code @ActiveProfiles("test")}).
 */
@ExtendWith(MockitoExtension.class)
class BatchSchedulingConfigTest {

  @Mock private JobLauncher jobLauncher;
  @Mock private JobExecution jobExecution;

  @Mock private Job accountExtractJob;
  @Mock private Job cardExtractJob;
  @Mock private Job customerExtractJob;
  @Mock private Job xrefExtractJob;
  @Mock private Job dailyTransactionPostJob;
  @Mock private Job transactionPostingJob;
  @Mock private Job interestCalculationJob;
  @Mock private Job transactionCombineJob;
  @Mock private Job statementGenerationJob;
  @Mock private Job transactionReportJob;
  @Mock private Job categoryBalanceReportJob;

  private BatchSchedulingConfig config;

  @BeforeEach
  void setUp() {
    config =
        new BatchSchedulingConfig(
            jobLauncher,
            accountExtractJob,
            cardExtractJob,
            customerExtractJob,
            xrefExtractJob,
            dailyTransactionPostJob,
            transactionPostingJob,
            interestCalculationJob,
            transactionCombineJob,
            statementGenerationJob,
            transactionReportJob,
            categoryBalanceReportJob);
  }

  @Test
  void everyScheduleMethodLaunchesItsOwnJobWithAUniqueScheduledAtParameter() throws Exception {
    when(jobLauncher.run(any(Job.class), any(JobParameters.class))).thenReturn(jobExecution);

    // Invoke all eleven scheduled triggers (the order is irrelevant - each is independent).
    config.scheduleAccountExtractJob();
    config.scheduleCardExtractJob();
    config.scheduleCustomerExtractJob();
    config.scheduleXrefExtractJob();
    config.scheduleDailyTransactionPostJob();
    config.scheduleTransactionPostingJob();
    config.scheduleInterestCalculationJob();
    config.scheduleTransactionCombineJob();
    config.scheduleStatementGenerationJob();
    config.scheduleTransactionReportJob();
    config.scheduleCategoryBalanceReportJob();

    // Each trigger must launch its OWN job bean exactly once.
    verify(jobLauncher).run(eq(accountExtractJob), any(JobParameters.class));
    verify(jobLauncher).run(eq(cardExtractJob), any(JobParameters.class));
    verify(jobLauncher).run(eq(customerExtractJob), any(JobParameters.class));
    verify(jobLauncher).run(eq(xrefExtractJob), any(JobParameters.class));
    verify(jobLauncher).run(eq(dailyTransactionPostJob), any(JobParameters.class));
    verify(jobLauncher).run(eq(transactionPostingJob), any(JobParameters.class));
    verify(jobLauncher).run(eq(interestCalculationJob), any(JobParameters.class));
    verify(jobLauncher).run(eq(transactionCombineJob), any(JobParameters.class));
    verify(jobLauncher).run(eq(statementGenerationJob), any(JobParameters.class));
    verify(jobLauncher).run(eq(transactionReportJob), any(JobParameters.class));
    verify(jobLauncher).run(eq(categoryBalanceReportJob), any(JobParameters.class));

    // Every launch must carry the unique, identifying scheduledAt parameter.
    ArgumentCaptor<JobParameters> captor = ArgumentCaptor.forClass(JobParameters.class);
    verify(jobLauncher, times(11)).run(any(Job.class), captor.capture());
    assertEquals(11, captor.getAllValues().size());
    for (JobParameters parameters : captor.getAllValues()) {
      assertNotNull(
          parameters.getLong(BatchSchedulingConfig.PARAM_SCHEDULED_AT),
          "each scheduled launch must add the identifying '"
              + BatchSchedulingConfig.PARAM_SCHEDULED_AT
              + "' parameter");
    }
  }

  @Test
  void launchSwallowsJobExecutionExceptionInsteadOfPropagating() throws Exception {
    // JobInstanceAlreadyCompleteException is one of the checked subclasses of JobExecutionException
    // that JobLauncher.run(...) declares, so the production catch(JobExecutionException) handles
    // it.
    when(jobLauncher.run(any(Job.class), any(JobParameters.class)))
        .thenThrow(new JobInstanceAlreadyCompleteException("simulated launch failure"));

    // A launch failure must be logged and contained, never thrown out of the scheduler thread.
    assertDoesNotThrow(() -> config.scheduleInterestCalculationJob());
    verify(jobLauncher).run(eq(interestCalculationJob), any(JobParameters.class));
  }
}
