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

import com.aws.carddemo.service.batch.CardExtractService;
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
 * Spring Batch configuration that reproduces the legacy card-master extract job {@code
 * legacy/app/jcl/READCARD.jcl} (source-branch {@code app/jcl/READCARD.jcl}). The legacy job is a
 * single step — {@code STEP05 EXEC PGM=CBACT02C} with DD {@code CARDFILE} addressing the card
 * master VSAM KSDS ({@code AWS.M2.CARDDEMO.CARDDATA.VSAM.KSDS}) — that runs the batch COBOL program
 * {@code CBACT02C} ("Read and print card data file").
 *
 * <p>This configuration is intentionally thin: all business logic — the sequential ascending-key
 * scan of the card master and the per-record emit framed by the start/end banners — lives in {@link
 * CardExtractService}, the one-to-one translation of {@code CBACT02C} (behavioral spec {@code
 * legacy/app/cbl/CBACT02C.cbl}). This class only performs the Spring Batch wiring that the legacy
 * JCL step boundary implied (Agent Action Plan &sect;0.4.1, "JCL &rarr; Spring Batch jobs"): it
 * exposes a single-step {@link Job} whose {@link Step} is a {@link
 * org.springframework.batch.core.step.tasklet.Tasklet} delegating to {@link
 * CardExtractService#run()}.
 *
 * <p><strong>Tasklet (not chunk) modeling.</strong> The legacy program performs one self-contained
 * read-and-print pass over {@code CARDFILE} with no externally chunked commit semantics, so the
 * faithful Spring Batch shape is a single {@code Tasklet} invocation rather than a
 * reader/processor/writer chunk step. The tasklet runs {@link CardExtractService#run()} exactly
 * once and returns {@link RepeatStatus#FINISHED}, mirroring the single {@code EXEC PGM=CBACT02C}
 * step that runs to {@code GOBACK} and ends.
 *
 * <p><strong>Failure / abend parity</strong> (AAP &sect;0.6.4, &sect;0.6.6). {@link
 * CardExtractService#run()} lets an internal {@link com.aws.carddemo.exception.IoStatusException}
 * (the {@code 9999-ABEND-PROGRAM} equivalent, raised on an unexpected FILE STATUS) propagate. That
 * exception is allowed to escape the tasklet here, so Spring Batch marks the step (and job) {@code
 * FAILED} — the batch analog of the COBOL {@code CALL 'CEE3ABD'} abend that terminated the legacy
 * run unit. The clean (non-abend) end-of-file path is handled entirely inside the service and is
 * never an error.
 *
 * <p><strong>Spring Batch wiring convention</strong> (AAP &sect;0.7.3, zero-warning gate).
 * Following the project-wide convention there is <em>no</em> {@code @EnableBatchProcessing}
 * anywhere; Spring Boot's batch auto-configuration supplies the {@link JobRepository} and {@code
 * JobLauncher}. The Boot-provided {@link JobRepository} and {@link PlatformTransactionManager} are
 * therefore injected as {@code @Bean} method parameters, and the Spring Batch 5 fluent builders
 * ({@link JobBuilder}, {@link StepBuilder}) are used to assemble the step and job.
 */
@Configuration
public class CardExtractJobConfig {

  /**
   * The card-master extract service ({@code CBACT02C} translation) invoked by the job's single
   * tasklet step. Constructor-injected and immutable.
   */
  private final CardExtractService cardExtractService;

  /**
   * Creates the configuration with its service collaborator.
   *
   * @param cardExtractService the batch service that reads and prints the card master (the {@code
   *     CBACT02C} translation); must not be {@code null}
   */
  public CardExtractJobConfig(CardExtractService cardExtractService) {
    this.cardExtractService = cardExtractService;
  }

  /**
   * Defines the single tasklet step that executes the card-master extract. The tasklet delegates to
   * {@link CardExtractService#run()} once and returns {@link RepeatStatus#FINISHED}, reproducing
   * the legacy {@code STEP05 EXEC PGM=CBACT02C} step. Any {@link
   * com.aws.carddemo.exception.IoStatusException} thrown by the service propagates out of the
   * tasklet and fails the step (the abend equivalent).
   *
   * @param jobRepository the Boot-provided Spring Batch job repository (no
   *     {@code @EnableBatchProcessing}); must not be {@code null}
   * @param transactionManager the Boot-provided transaction manager governing the step's metadata
   *     transaction; must not be {@code null}
   * @return the configured single-tasklet {@link Step} named {@code "cardExtractStep"}
   */
  @Bean
  public Step cardExtractStep(
      JobRepository jobRepository, PlatformTransactionManager transactionManager) {
    return new StepBuilder("cardExtractStep", jobRepository)
        .tasklet(
            (contribution, chunkContext) -> {
              cardExtractService.run();
              return RepeatStatus.FINISHED;
            },
            transactionManager)
        .build();
  }

  /**
   * Defines the card-master extract {@link Job} as a single-step job starting with {@link
   * #cardExtractStep(JobRepository, PlatformTransactionManager)}. This is the Spring Batch
   * counterpart of the legacy {@code READCARD.jcl} job, which contained exactly one executable
   * step.
   *
   * @param jobRepository the Boot-provided Spring Batch job repository; must not be {@code null}
   * @param cardExtractStep the single tasklet step defined by {@link
   *     #cardExtractStep(JobRepository, PlatformTransactionManager)}; injected by bean name
   * @return the configured {@link Job} named {@code "cardExtractJob"}
   */
  @Bean
  public Job cardExtractJob(JobRepository jobRepository, Step cardExtractStep) {
    return new JobBuilder("cardExtractJob", jobRepository).start(cardExtractStep).build();
  }
}
