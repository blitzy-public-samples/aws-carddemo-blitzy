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

import com.aws.carddemo.service.batch.CustomerExtractService;
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
 * Spring Batch configuration that reproduces the legacy customer-master extract job {@code
 * legacy/app/jcl/READCUST.jcl} ("Read Customer Data file"). That JCL is a single-step job &mdash;
 * {@code STEP05 EXEC PGM=CBCUS01C} with DD {@code CUSTFILE} addressing the customer master VSAM
 * KSDS ({@code AWS.M2.CARDDEMO.CUSTDATA.VSAM.KSDS}) &mdash; so this configuration exposes a
 * single-step {@link Job} whose tasklet delegates to {@link CustomerExtractService}, the Java
 * translation of batch COBOL program {@code CBCUS01C} ("Read and print customer data file").
 *
 * <p><strong>Separation of concerns.</strong> All business logic &mdash; the sequential
 * ascending-key scan of the customer master, the parity-critical double {@code DISPLAY
 * CUSTOMER-RECORD} per record, and the {@code FILE STATUS} / abend handling &mdash; lives in {@link
 * CustomerExtractService#run()}. This class contributes <em>only</em> the Spring Batch wiring (job
 * &rarr; step &rarr; tasklet), mirroring the way the legacy JCL is pure orchestration around the
 * COBOL program (Agent Action Plan &sect;0.4.1: {@code JCL} &rarr; Spring Batch jobs; {@code
 * PARAGRAPH/SECTION} business logic &rarr; service methods).
 *
 * <p><strong>Spring Batch wiring convention (AAP &sect;0.7.3, zero-warning gate).</strong>
 * Following the project-wide convention (see {@code TransactionCombineStep}), this configuration
 * does <em>not</em> use {@code @EnableBatchProcessing}: Spring Boot's batch auto-configuration
 * already provides a {@link JobRepository} and a {@link PlatformTransactionManager}, which are
 * injected as {@code @Bean} method parameters. The Spring Batch 5 fluent builders ({@link
 * JobBuilder}, {@link StepBuilder}) are used exclusively.
 *
 * <p><strong>Single-action step.</strong> Because {@code CBCUS01C} is a self-contained read/print
 * loop rather than a chunk-oriented read-process-write pipeline, the step is modelled as a {@link
 * org.springframework.batch.core.step.tasklet.Tasklet}: it invokes {@link
 * CustomerExtractService#run()} exactly once and reports {@link RepeatStatus#FINISHED}. The service
 * lets an internal {@code IoStatusException} propagate on an unexpected I/O condition (the COBOL
 * abend path), which surfaces here as a failed step exit status &mdash; preserving the legacy
 * abend-on-I/O-error semantics (AAP &sect;0.6.4, &sect;0.6.6).
 */
@Configuration
public class CustomerExtractJobConfig {

  /**
   * The migrated {@code CBCUS01C} business logic (read &amp; print the customer master). The
   * tasklet in {@link #customerExtractStep(JobRepository, PlatformTransactionManager)} delegates to
   * its {@link CustomerExtractService#run()} method. Constructor-injected for immutability and
   * testability.
   */
  private final CustomerExtractService customerExtractService;

  /**
   * Creates the customer-extract job configuration.
   *
   * @param customerExtractService the migrated {@code CBCUS01C} extract service whose {@link
   *     CustomerExtractService#run() run()} method the step tasklet invokes (must not be {@code
   *     null})
   */
  public CustomerExtractJobConfig(CustomerExtractService customerExtractService) {
    this.customerExtractService = customerExtractService;
  }

  /**
   * Defines the single step ({@code customerExtractStep}) of the customer-extract job, reproducing
   * {@code READCUST.jcl}'s {@code STEP05 EXEC PGM=CBCUS01C}.
   *
   * <p>The step is a {@link org.springframework.batch.core.step.tasklet.Tasklet} that runs once: it
   * calls {@link CustomerExtractService#run()} (the entire {@code CBCUS01C} read/print flow) and
   * returns {@link RepeatStatus#FINISHED}. The Boot-provided {@link JobRepository} and {@link
   * PlatformTransactionManager} are supplied as method parameters per the
   * no-{@code @EnableBatchProcessing} convention. An unexpected I/O condition raised by the service
   * (the COBOL abend path) propagates out of the tasklet, failing the step.
   *
   * @param jobRepository the Boot-provided Spring Batch job repository
   * @param transactionManager the Boot-provided platform transaction manager
   * @return the configured single-action extract step
   */
  @Bean
  public Step customerExtractStep(
      JobRepository jobRepository, PlatformTransactionManager transactionManager) {
    return new StepBuilder("customerExtractStep", jobRepository)
        .tasklet(
            (contribution, chunkContext) -> {
              customerExtractService.run();
              return RepeatStatus.FINISHED;
            },
            transactionManager)
        .build();
  }

  /**
   * Defines the single-step customer-extract job ({@code customerExtractJob}), the Spring Batch
   * equivalent of the legacy {@code READCUST.jcl} job. It starts (and ends) with {@link
   * #customerExtractStep(JobRepository, PlatformTransactionManager)}, mirroring the JCL's lone
   * {@code STEP05}.
   *
   * @param jobRepository the Boot-provided Spring Batch job repository
   * @param customerExtractStep the single extract step (injected by bean name)
   * @return the configured single-step extract job
   */
  @Bean
  public Job customerExtractJob(JobRepository jobRepository, Step customerExtractStep) {
    return new JobBuilder("customerExtractJob", jobRepository).start(customerExtractStep).build();
  }
}
