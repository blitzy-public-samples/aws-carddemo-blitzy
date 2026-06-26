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
package com.aws.carddemo.batch.config;

import com.aws.carddemo.service.batch.InterestCalculationService;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.Step;
import org.springframework.batch.core.configuration.annotation.StepScope;
import org.springframework.batch.core.job.builder.JobBuilder;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.core.step.builder.StepBuilder;
import org.springframework.batch.core.step.tasklet.Tasklet;
import org.springframework.batch.repeat.RepeatStatus;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * Spring Batch configuration that reproduces the legacy interest-calculation job {@code
 * legacy/app/jcl/INTCALC.jcl}. That JCL is a single-step job ({@code STEP15 EXEC PGM=CBACT04C,
 * PARM='2022071800'}) whose DDs ({@code TCATBALF}, {@code XREFFILE}, {@code XREFFIL1}, {@code
 * ACCTFILE}, {@code DISCGRP}, {@code TRANSACT}) are the transaction-category-balance, card
 * cross-reference, account-master, disclosure-group, and transaction stores read/written by the
 * COBOL program {@code CBACT04C} ("Process transaction balance file and compute interest and fees",
 * behavioral spec {@code legacy/app/cbl/CBACT04C.cbl}).
 *
 * <p><strong>Run-date parameter (AAP &sect;0.1.1, &sect;0.6.7).</strong> The legacy {@code
 * PARM='2022071800'} is the 10-character run date (COBOL {@code PARM-DATE PIC X(10)}, format {@code
 * YYYYMMDDHH}) that {@code CBACT04C} receives through {@code PROCEDURE DIVISION USING
 * EXTERNAL-PARMS} and uses verbatim as the high-order 10 characters of every generated {@code
 * TRAN-ID}. In the target it is surfaced as a Spring Batch {@code JobParameter} named {@code
 * runDate} and forwarded to {@link InterestCalculationService#run(String)}. So the job is runnable
 * without explicitly supplying parameters, the tasklet falls back to {@link #DEFAULT_RUN_DATE} (the
 * legacy {@code PARM} value) when the parameter is absent or blank.
 *
 * <p><strong>No business logic here.</strong> The whole of the {@code CBACT04C} business behavior —
 * the file opens, the {@code PERFORM UNTIL END-OF-FILE} category-balance read loop, the per-account
 * control break, the disclosure-rate lookup, the truncated interest computation {@code
 * (TRAN-CAT-BAL * DIS-INT-RATE) / 1200} (no {@code ROUNDED} phrase, hence {@link
 * java.math.RoundingMode#DOWN} at scale 2 per AAP &sect;0.6.1), the fee handling, the accrual into
 * {@code ACCT-CURR-BAL}, and the {@code TRANSACT} write — lives in {@link
 * InterestCalculationService#run(String)}. This configuration intentionally contains <em>no</em>
 * business logic and introduces <em>no</em> floating-point arithmetic; it only wires the Spring
 * Batch plumbing and injects the run date (Agent Action Plan &sect;0.4.1, the {@code
 * InterestCalculationJobConfig} + {@code InterestCalculationService} pair).
 *
 * <p><strong>Abend parity (AAP &sect;0.6.6).</strong> The tasklet does <em>not</em> catch any
 * exception raised by {@link InterestCalculationService#run(String)}. The service translates an
 * unexpected {@code FILE STATUS} I/O failure (or a missing required record on a {@code '00'}-only
 * path) into a {@code com.aws.carddemo.exception.IoStatusException} (the Java counterpart of the
 * COBOL {@code 9999-ABEND-PROGRAM} abend). By letting that exception propagate out of the tasklet,
 * the surrounding step ends with a {@code FAILED} {@link
 * org.springframework.batch.core.BatchStatus} / {@code ExitStatus}, which is the faithful
 * abend-equivalent for a batch run.
 *
 * <p><strong>Spring Batch wiring conventions.</strong> Following the project-wide convention this
 * class deliberately omits {@code @EnableBatchProcessing}: under Spring Boot 3.5.x the {@code
 * DefaultBatchConfiguration} auto-configuration already supplies a {@link JobRepository} and a
 * {@code JobLauncher}, and adding the annotation would switch that auto-configuration off. The
 * Boot-provided {@link JobRepository} and {@link PlatformTransactionManager} are consequently
 * injected as {@code @Bean} <em>method parameters</em> rather than being declared here. The job and
 * step are assembled with the Spring Batch 5 {@link JobBuilder}/{@link StepBuilder} fluent API (the
 * removed {@code JobBuilderFactory}/{@code StepBuilderFactory} types are never used).
 *
 * @see InterestCalculationService
 */
@Configuration
public class InterestCalculationJobConfig {

  /**
   * The default run date used when no {@code runDate} job parameter is supplied. This is the exact
   * {@code PARM='2022071800'} value hard-coded in the legacy {@code INTCALC.jcl} {@code STEP15},
   * preserved so the job is runnable out of the box with identical behavior to the legacy step.
   */
  private static final String DEFAULT_RUN_DATE = "2022071800";

  /**
   * The migrated {@code CBACT04C} business service that performs the actual interest-and-fee
   * processing. Constructor-injected so this configuration stays trivially unit-testable with a
   * mock service.
   */
  private final InterestCalculationService interestCalculationService;

  /**
   * Creates the configuration with its single collaborator.
   *
   * @param interestCalculationService the migrated {@code CBACT04C} interest-calculation service
   *     the tasklet delegates to; must not be {@code null}
   */
  public InterestCalculationJobConfig(InterestCalculationService interestCalculationService) {
    this.interestCalculationService = interestCalculationService;
  }

  /**
   * Defines the step-scoped tasklet that drives the interest calculation, binding the {@code
   * runDate} job parameter at execution time.
   *
   * <p>The bean is {@code @StepScope} so the {@code runDate} job parameter can be late-bound
   * through the SpEL expression {@code #{jobParameters['runDate']}}. When the parameter is absent
   * or blank, the legacy {@link #DEFAULT_RUN_DATE} ({@code PARM='2022071800'}) is used instead,
   * reproducing the hard-coded {@code STEP15} parameter and keeping the job runnable without
   * parameters. The tasklet invokes {@link InterestCalculationService#run(String)} exactly once
   * with the resolved run date and reports {@link RepeatStatus#FINISHED}, mirroring the one-shot
   * execution of the legacy batch program. Any exception raised by the service (notably the
   * abend-equivalent {@code IoStatusException}) is allowed to propagate so the step fails, per AAP
   * &sect;0.6.6.
   *
   * @param runDate the {@code runDate} job parameter (COBOL {@code PARM-DATE PIC X(10)}, format
   *     {@code YYYYMMDDHH}); late-bound by Spring Batch and may be {@code null} or blank when the
   *     job is launched without it
   * @return a single-execution tasklet that delegates to {@link
   *     InterestCalculationService#run(String)} with the resolved run date
   */
  @Bean
  @StepScope
  public Tasklet interestCalculationTasklet(@Value("#{jobParameters['runDate']}") String runDate) {
    final String parmDate = (runDate != null && !runDate.isBlank()) ? runDate : DEFAULT_RUN_DATE;
    return (contribution, chunkContext) -> {
      interestCalculationService.run(parmDate);
      return RepeatStatus.FINISHED;
    };
  }

  /**
   * Defines the single step ({@code interestCalculationStep}) of the interest-calculation job,
   * reproducing the lone {@code STEP15 EXEC PGM=CBACT04C} step of {@code INTCALC.jcl}.
   *
   * @param jobRepository the Boot-provided Spring Batch job repository (injected as a method
   *     parameter, never via {@code @EnableBatchProcessing})
   * @param transactionManager the Boot-provided platform transaction manager that brackets the
   *     tasklet execution
   * @param interestCalculationTasklet the step-scoped interest-calculation tasklet (injected by
   *     bean name; Spring supplies the {@code @StepScope} proxy)
   * @return the configured single-execution tasklet step
   */
  @Bean
  public Step interestCalculationStep(
      JobRepository jobRepository,
      PlatformTransactionManager transactionManager,
      Tasklet interestCalculationTasklet) {
    return new StepBuilder("interestCalculationStep", jobRepository)
        .tasklet(interestCalculationTasklet, transactionManager)
        .build();
  }

  /**
   * Defines the interest-calculation job ({@code interestCalculationJob}) as a single-step job that
   * runs {@link #interestCalculationStep(JobRepository, PlatformTransactionManager, Tasklet)},
   * mirroring the single-step structure of {@code INTCALC.jcl}.
   *
   * @param jobRepository the Boot-provided Spring Batch job repository (injected as a method
   *     parameter)
   * @param interestCalculationStep the single step of this job (injected by bean name)
   * @return the configured single-step job
   */
  @Bean
  public Job interestCalculationJob(JobRepository jobRepository, Step interestCalculationStep) {
    return new JobBuilder("interestCalculationJob", jobRepository)
        .start(interestCalculationStep)
        .build();
  }
}
