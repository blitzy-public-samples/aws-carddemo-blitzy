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

import com.aws.carddemo.service.batch.AccountExtractService;
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
 * Spring Batch configuration that reproduces the legacy account-extract job {@code
 * legacy/app/jcl/READACCT.jcl}. That JCL is a single-step job ({@code STEP05 EXEC PGM=CBACT01C})
 * whose only DD of interest is {@code ACCTFILE} (the {@code AWS.M2.CARDDEMO.ACCTDATA.VSAM.KSDS}
 * account-master KSDS); it runs the COBOL program {@code CBACT01C} ("Read and print account data
 * file", source {@code legacy/app/cbl/CBACT01C.cbl}).
 *
 * <p>The job is therefore translated as a <strong>single-step</strong> Spring Batch {@link Job}
 * whose {@link Step} is a {@link org.springframework.batch.core.step.tasklet.Tasklet} that
 * delegates to the already-migrated business service {@link AccountExtractService}. All of the
 * {@code CBACT01C} business logic — opening the store, the ascending-key read loop, the per-record
 * and whole-record displays, the close, and the {@code FILE STATUS}-to-abend handling — lives in
 * {@link AccountExtractService#run()}; this configuration intentionally contains <em>no</em>
 * business logic and only wires the Spring Batch plumbing (Agent Action Plan &sect;0.4.1, the
 * {@code AccountExtractJobConfig} + {@code AccountExtractService} pair).
 *
 * <p><strong>Abend parity (AAP &sect;0.6.6).</strong> The tasklet does <em>not</em> catch any
 * exception raised by {@link AccountExtractService#run()}. The service translates an unexpected
 * {@code FILE STATUS} I/O failure into a {@code com.aws.carddemo.exception.IoStatusException} (the
 * Java counterpart of the COBOL {@code 9999-ABEND-PROGRAM}/{@code CEE3ABD} abend). By letting that
 * exception propagate out of the tasklet, the surrounding step ends with a {@code FAILED} {@link
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
 * @see AccountExtractService
 */
@Configuration
public class AccountExtractJobConfig {

  /**
   * The migrated {@code CBACT01C} business service that performs the actual read-and-print of the
   * account master. Constructor-injected so this configuration stays trivially unit-testable with a
   * mock service.
   */
  private final AccountExtractService accountExtractService;

  /**
   * Creates the configuration with its single collaborator.
   *
   * @param accountExtractService the migrated {@code CBACT01C} read-and-print service the tasklet
   *     delegates to; must not be {@code null}
   */
  public AccountExtractJobConfig(AccountExtractService accountExtractService) {
    this.accountExtractService = accountExtractService;
  }

  /**
   * Defines the single step ({@code accountExtractStep}) of the account-extract job, reproducing
   * the lone {@code STEP05 EXEC PGM=CBACT01C} step of {@code READACCT.jcl}.
   *
   * <p>The step is a tasklet that invokes {@link AccountExtractService#run()} exactly once and then
   * reports {@link RepeatStatus#FINISHED}, mirroring the one-shot execution of the legacy batch
   * program. Any exception raised by the service (notably the abend-equivalent {@code
   * IoStatusException}) is allowed to propagate so the step fails, per AAP &sect;0.6.6.
   *
   * @param jobRepository the Boot-provided Spring Batch job repository (injected as a method
   *     parameter, never via {@code @EnableBatchProcessing})
   * @param transactionManager the Boot-provided platform transaction manager that brackets the
   *     tasklet execution
   * @return the configured single-execution tasklet step
   */
  @Bean
  public Step accountExtractStep(
      JobRepository jobRepository, PlatformTransactionManager transactionManager) {
    return new StepBuilder("accountExtractStep", jobRepository)
        .tasklet(
            (contribution, chunkContext) -> {
              accountExtractService.run();
              return RepeatStatus.FINISHED;
            },
            transactionManager)
        .build();
  }

  /**
   * Defines the account-extract job ({@code accountExtractJob}) as a single-step job that runs
   * {@link #accountExtractStep(JobRepository, PlatformTransactionManager)}, mirroring the
   * single-step structure of {@code READACCT.jcl}.
   *
   * @param jobRepository the Boot-provided Spring Batch job repository (injected as a method
   *     parameter)
   * @param accountExtractStep the single step of this job (injected by bean name)
   * @return the configured single-step job
   */
  @Bean
  public Job accountExtractJob(JobRepository jobRepository, Step accountExtractStep) {
    return new JobBuilder("accountExtractJob", jobRepository).start(accountExtractStep).build();
  }
}
