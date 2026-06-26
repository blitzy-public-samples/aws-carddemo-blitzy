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

import com.aws.carddemo.service.batch.XrefExtractService;
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
 * Spring Batch configuration that reproduces the legacy card cross-reference extract job {@code
 * legacy/app/jcl/READXREF.jcl}. That JCL has a single step &mdash; {@code STEP05 EXEC PGM=CBACT03C}
 * with DD {@code XREFFILE} addressing the {@code AWS.M2.CARDDEMO.CARDXREF.VSAM.KSDS} master &mdash;
 * so this config exposes a single-{@link Step} {@link Job} whose tasklet delegates to {@link
 * XrefExtractService}, the Java/Spring translation of COBOL program {@code
 * legacy/app/cbl/CBACT03C.cbl} ("Read and print card-xref data file"), per the Agent Action Plan
 * transformation mapping (&sect;0.4.1).
 *
 * <p><strong>Separation of concerns.</strong> All business logic &mdash; the sequential read of the
 * cross-reference store and the {@code CBACT03C} double-display parity (each record emitted twice)
 * &mdash; lives inside {@link XrefExtractService#run()}. This configuration only wires Spring
 * Batch; it holds no extract logic of its own.
 *
 * <p><strong>Spring Batch wiring (project convention).</strong> Following the same pattern as the
 * sibling {@link TransactionCombineStep}, there is <em>no</em> {@code @EnableBatchProcessing}: the
 * Boot-auto-configured {@link JobRepository} and {@link PlatformTransactionManager} are injected as
 * {@code @Bean} method parameters, and the Spring Batch 5 {@link StepBuilder} / {@link JobBuilder}
 * fluent API is used throughout. The single {@link Step} is injected into the {@link Job} factory
 * by parameter name ({@code xrefExtractStep}), which Spring resolves against the bean of the same
 * name.
 *
 * <p><strong>Failure semantics (AAP &sect;0.6.4, &sect;0.6.6).</strong> {@link
 * XrefExtractService#run()} lets an unrecoverable I/O condition surface as an unchecked {@code
 * IoStatusException} (the COBOL {@code 9999-ABEND-PROGRAM} equivalent). The tasklet does not catch
 * it, so the exception propagates and the step terminates with a {@code FAILED} exit status &mdash;
 * the Spring Batch analogue of the legacy batch abend. A normal run completes the single step and
 * the job as {@code COMPLETED}.
 *
 * @see XrefExtractService
 * @see TransactionCombineStep
 */
@Configuration
public class XrefExtractJobConfig {

  /**
   * The cross-reference extract service that performs the read-and-print work (the migrated {@code
   * CBACT03C} logic). Constructor-injected and never {@code null}; invoked once per step execution
   * by the {@link #xrefExtractStep(JobRepository, PlatformTransactionManager) tasklet}.
   */
  private final XrefExtractService xrefExtractService;

  /**
   * Creates the job configuration with its required service collaborator (constructor injection).
   *
   * @param xrefExtractService the cross-reference extract service to drive from the single batch
   *     step; must not be {@code null}
   */
  public XrefExtractJobConfig(XrefExtractService xrefExtractService) {
    this.xrefExtractService = xrefExtractService;
  }

  /**
   * Defines the single tasklet {@link Step} of the cross-reference extract job, reproducing {@code
   * STEP05 EXEC PGM=CBACT03C} from {@code legacy/app/jcl/READXREF.jcl}. The tasklet invokes {@link
   * XrefExtractService#run()} exactly once and reports {@link RepeatStatus#FINISHED} so the step
   * runs a single iteration, mirroring the one-shot batch program. Any {@code IoStatusException}
   * raised by the service propagates uncaught, failing the step (abend-equivalent).
   *
   * @param jobRepository the Boot-provided Spring Batch metadata repository
   * @param transactionManager the Boot-provided transaction manager governing the step's chunk
   *     transaction boundary
   * @return the configured single-iteration tasklet step named {@code "xrefExtractStep"}
   */
  @Bean
  public Step xrefExtractStep(
      JobRepository jobRepository, PlatformTransactionManager transactionManager) {
    return new StepBuilder("xrefExtractStep", jobRepository)
        .tasklet(
            (contribution, chunkContext) -> {
              xrefExtractService.run();
              return RepeatStatus.FINISHED;
            },
            transactionManager)
        .build();
  }

  /**
   * Defines the cross-reference extract {@link Job}, reproducing the single-step {@code
   * legacy/app/jcl/READXREF.jcl}. The job starts and consists solely of {@link
   * #xrefExtractStep(JobRepository, PlatformTransactionManager)}; it completes as {@code COMPLETED}
   * when that step succeeds.
   *
   * @param jobRepository the Boot-provided Spring Batch metadata repository
   * @param xrefExtractStep the single step bean (injected by name) that the job starts with
   * @return the configured job named {@code "xrefExtractJob"}
   */
  @Bean
  public Job xrefExtractJob(JobRepository jobRepository, Step xrefExtractStep) {
    return new JobBuilder("xrefExtractJob", jobRepository).start(xrefExtractStep).build();
  }
}
