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

import com.aws.carddemo.service.batch.DailyTransactionPostService;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.Step;
import org.springframework.batch.core.job.builder.JobBuilder;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.core.step.builder.StepBuilder;
import org.springframework.batch.repeat.RepeatStatus;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * Spring Batch configuration that reproduces the legacy COBOL batch program {@code CBTRN01C}
 * (source {@code legacy/app/cbl/CBTRN01C.cbl}) as a single-step Spring Batch {@link Job}.
 *
 * <p><strong>No shipped JCL driver; validation-only pass.</strong> Unlike the other migrated batch
 * jobs, {@code CBTRN01C} has <em>no</em> JCL member that executes it (Agent Action Plan
 * &sect;0.4.1); it is wired here as a stand-alone job for completeness and parity. Despite its
 * "post" name, the program performs a <em>validation-only</em> sweep of the sequential
 * daily-transaction file: for every record it resolves the card through the card cross-reference
 * and verifies that the referenced account exists, emitting diagnostics for any card or account
 * that cannot be resolved. It performs <em>no</em> posting, <em>no</em> writes/rewrites, and sets
 * <em>no</em> {@code RETURN-CODE} (the COBOL {@code GOBACK} leaves the return code at its default
 * {@code 0}). All of that business logic — the file opens/closes, the read-ahead processing loop,
 * the cross-reference and account lookups, the skip diagnostics, and the {@code FILE
 * STATUS}-to-abend handling — lives in {@link DailyTransactionPostService#run()}; this
 * configuration intentionally contains <em>no</em> business logic and only wires the Spring Batch
 * plumbing (AAP &sect;0.4.1, the {@code DailyTransactionPostJobConfig} + {@code
 * DailyTransactionPostService} pair).
 *
 * <p><strong>Default exit status (AAP &sect;0.6.6).</strong> Because the legacy program is a
 * validation-only pass that never sets a {@code RETURN-CODE}, this configuration adds <em>no</em>
 * custom exit-status logic: on a clean run the step and job simply complete with the default {@code
 * org.springframework.batch.core.ExitStatus#COMPLETED} (exit code {@code "0"}), the faithful
 * translation of the COBOL {@code GOBACK} with {@code RETURN-CODE = 0}.
 *
 * <p><strong>Abend parity (AAP &sect;0.6.6).</strong> The tasklet does <em>not</em> catch any
 * exception raised by {@link DailyTransactionPostService#run()}. The service translates an
 * unrecoverable {@code FILE STATUS} I/O failure into a {@code
 * com.aws.carddemo.exception.IoStatusException} (the Java counterpart of the COBOL {@code
 * Z-ABEND-PROGRAM}/{@code CEE3ABD} abend). By letting that exception propagate out of the tasklet,
 * the surrounding step ends with a {@code FAILED} {@link
 * org.springframework.batch.core.BatchStatus} / {@code ExitStatus}, which is the faithful
 * abend-equivalent for a batch run. A card or account that cannot be resolved is <em>not</em> an
 * abend: the service handles it as the ordinary VSAM {@code INVALID KEY} skip path, so the step
 * still completes normally.
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
 * @see DailyTransactionPostService
 */
@Configuration
public class DailyTransactionPostJobConfig {

  /**
   * The migrated {@code CBTRN01C} business service that performs the actual daily-transaction
   * validation pass. Constructor-injected so this configuration stays trivially unit-testable with
   * a mock service.
   */
  private final DailyTransactionPostService dailyTransactionPostService;

  /**
   * Creates the configuration with its single collaborator.
   *
   * @param dailyTransactionPostService the migrated {@code CBTRN01C} validation service the tasklet
   *     delegates to; must not be {@code null}
   */
  public DailyTransactionPostJobConfig(DailyTransactionPostService dailyTransactionPostService) {
    this.dailyTransactionPostService = dailyTransactionPostService;
  }

  /**
   * Defines the single step ({@code dailyTransactionPostStep}) of the daily-transaction validation
   * job, reproducing the one-shot execution of the COBOL program {@code CBTRN01C}.
   *
   * <p>The step is a tasklet that invokes {@link DailyTransactionPostService#run()} exactly once
   * and then reports {@link RepeatStatus#FINISHED}, mirroring the single execution of the legacy
   * batch program. No custom exit-status logic is applied: a clean run leaves the step at the
   * default {@code COMPLETED} exit status (exit code {@code "0"}), matching the program's lack of a
   * {@code RETURN-CODE}. Any exception raised by the service (notably the abend-equivalent {@code
   * IoStatusException}) is allowed to propagate so the step fails, per AAP &sect;0.6.6.
   *
   * @param jobRepository the Boot-provided Spring Batch job repository (injected as a method
   *     parameter, never via {@code @EnableBatchProcessing})
   * @param transactionManager the Boot-provided platform transaction manager that brackets the
   *     tasklet execution
   * @return the configured single-execution tasklet step
   */
  @Bean
  public Step dailyTransactionPostStep(
      JobRepository jobRepository, PlatformTransactionManager transactionManager) {
    return new StepBuilder("dailyTransactionPostStep", jobRepository)
        .tasklet(
            (contribution, chunkContext) -> {
              dailyTransactionPostService.run();
              return RepeatStatus.FINISHED;
            },
            transactionManager)
        .build();
  }

  /**
   * Defines the daily-transaction validation job ({@code dailyTransactionPostJob}) as a single-step
   * job that runs {@link #dailyTransactionPostStep(JobRepository, PlatformTransactionManager)},
   * mirroring the single-program structure of {@code CBTRN01C}.
   *
   * @param jobRepository the Boot-provided Spring Batch job repository (injected as a method
   *     parameter)
   * @param dailyTransactionPostStep the single step of this job (injected by bean name)
   * @return the configured single-step job
   */
  @Bean
  public Job dailyTransactionPostJob(JobRepository jobRepository, Step dailyTransactionPostStep) {
    return new JobBuilder("dailyTransactionPostJob", jobRepository)
        .start(dailyTransactionPostStep)
        .build();
  }
}
