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

import com.aws.carddemo.batch.writer.HtmlStatementWriter;
import com.aws.carddemo.batch.writer.StatementFileWriter;
import com.aws.carddemo.service.batch.StatementGenerationService;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
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
 * Spring Batch configuration that reproduces the legacy statement-generation job {@code
 * legacy/app/jcl/CREASTMT.JCL}. The driver step of that JCL is {@code STEP040 EXEC PGM=CBSTM03A},
 * which reads four input datasets ({@code TRNXFILE} = {@code TRXFL.VSAM.KSDS}, {@code XREFFILE},
 * {@code ACCTFILE}, {@code CUSTFILE}) and produces two outputs: a fixed-width plain-text statement
 * ({@code STMTFILE}, {@code DCB=(LRECL=80,RECFM=FB)}) and a matching HTML statement ({@code
 * HTMLFILE}, {@code DCB=(LRECL=100,RECFM=FB)}). It runs the COBOL program {@code CBSTM03A} ("Create
 * statement for each card present in the XREF file", source {@code legacy/app/cbl/CBSTM03A.CBL}),
 * which delegates all record I/O to the called subroutine {@code CBSTM03B} (source {@code
 * legacy/app/cbl/CBSTM03B.CBL}).
 *
 * <p>The job is therefore translated as a <strong>single-step</strong> Spring Batch {@link Job}
 * whose {@link Step} is a {@link Tasklet} that delegates to the already-migrated business service
 * {@link StatementGenerationService}. All of the {@code CBSTM03A} business logic &mdash; opening
 * the four input files (via the injected {@code FileIoService} translation of {@code CBSTM03B}),
 * the per-card-cross-reference mainline loop, the customer/account keyed reads, the per-transaction
 * detail formatting, the running expense total, and the {@code FILE STATUS}-to-abend handling
 * &mdash; lives in {@link StatementGenerationService#run(java.util.function.Consumer,
 * java.util.function.Consumer)}; this configuration intentionally contains <em>no</em> business
 * logic and only wires the Spring Batch plumbing together with the two physical file sinks (Agent
 * Action Plan &sect;0.4.1, the {@code StatementGenerationJobConfig} + {@code
 * StatementGenerationService} + {@code FileIoService} triad).
 *
 * <p><strong>Sort handled in the service (AAP &sect;0.6.3).</strong> The legacy {@code CREASTMT}
 * STEP010 {@code SORT FIELDS=(263,16,CH,A,1,16,CH,A)} that orders the transaction work file
 * ascending by {@code (card-number, transaction-id)} is reproduced <em>inside</em> {@link
 * StatementGenerationService}, not by this configuration. This step config is concerned solely with
 * resolving the two output paths, opening/closing the two sink writers, and invoking the service.
 *
 * <p><strong>Output-path resolution (locked convention).</strong> The two outputs are written under
 * a base directory resolved, in order of precedence, from: an optional {@code outputDir} job
 * parameter, then the configured {@code carddemo.batch.output-dir} property, then the JVM temporary
 * directory ({@code java.io.tmpdir}). Missing parent directories are created before either writer
 * is opened. The plain-text statement is written to {@code STMTFILE} ({@value
 * StatementFileWriter#LRECL}-byte records) and the HTML statement to {@code HTMLFILE} ({@value
 * HtmlStatementWriter#LRECL}-byte records), matching the {@code DD} names and {@code LRECL}s of
 * {@code CREASTMT.JCL} STEP040.
 *
 * <p><strong>Deterministic writer lifecycle.</strong> The tasklet opens both sinks before invoking
 * the service and always closes both afterwards, even on failure: the close of the HTML writer is
 * nested in the {@code finally} of the statement-writer close so that a failure to close the first
 * sink cannot leak the second. Both writers are idempotent on {@code close()}.
 *
 * <p><strong>Abend parity (AAP &sect;0.6.6).</strong> The tasklet does <em>not</em> swallow any
 * exception raised by {@link StatementGenerationService#run(java.util.function.Consumer,
 * java.util.function.Consumer)} or by the writers. The service and the writers translate an
 * unrecoverable I/O failure into a {@code com.aws.carddemo.exception.IoStatusException} (the Java
 * counterpart of the COBOL {@code 9999-ABEND-PROGRAM}/{@code CEE3ABD} abend). By letting that
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
 * @see StatementGenerationService
 */
@Configuration
public class StatementGenerationJobConfig {

  /** Output file name (and DD name) of the fixed-width plain-text statement ({@code LRECL=80}). */
  private static final String STMT_FILE_NAME = "STMTFILE";

  /** Output file name (and DD name) of the fixed-width HTML statement ({@code LRECL=100}). */
  private static final String HTML_FILE_NAME = "HTMLFILE";

  /**
   * The migrated {@code CBSTM03A} business service that performs the actual statement production.
   * Constructor-injected so this configuration stays trivially unit-testable with a mock service.
   */
  private final StatementGenerationService statementGenerationService;

  /**
   * The configured base output directory, resolved from the {@code carddemo.batch.output-dir}
   * property and defaulting to {@code java.io.tmpdir}. Used whenever the job is launched without an
   * explicit {@code outputDir} job parameter.
   */
  private final String configuredOutputDir;

  /**
   * Creates the statement-generation job configuration.
   *
   * @param statementGenerationService the migrated {@code CBSTM03A} statement service the tasklet
   *     delegates to; must not be {@code null}
   * @param configuredOutputDir base output directory bound from {@code carddemo.batch.output-dir},
   *     defaulting to the JVM temporary directory ({@code java.io.tmpdir}) when the property is
   *     absent
   */
  public StatementGenerationJobConfig(
      StatementGenerationService statementGenerationService,
      @Value("${carddemo.batch.output-dir:#{systemProperties['java.io.tmpdir']}}")
          String configuredOutputDir) {
    this.statementGenerationService = statementGenerationService;
    this.configuredOutputDir = configuredOutputDir;
  }

  /**
   * Resolves the absolute path of an output file, applying the locked output-directory convention.
   *
   * <p>The base directory is the supplied {@code jobParamDir} when it is non-blank, otherwise the
   * {@linkplain #configuredOutputDir configured directory}. Any missing parent directories are
   * created so that the subsequent {@code open} cannot fail merely because the directory is absent.
   *
   * @param jobParamDir the optional {@code outputDir} job-parameter value (may be {@code null} or
   *     blank)
   * @param fileName the file name to resolve under the base directory
   * @return the resolved path to {@code fileName} under the effective base directory
   * @throws IOException if the parent directory cannot be created
   */
  private Path resolveOutputPath(String jobParamDir, String fileName) throws IOException {
    String base =
        (jobParamDir != null && !jobParamDir.isBlank()) ? jobParamDir : configuredOutputDir;
    Path path = Paths.get(base, fileName);
    Path parent = path.getParent();
    if (parent != null) {
      Files.createDirectories(parent);
    }
    return path;
  }

  /**
   * Defines the step-scoped tasklet that produces both statement files for one job run.
   *
   * <p>The tasklet resolves the two output paths (late-binding the optional {@code outputDir} job
   * parameter), instantiates and opens the two physical sink writers, invokes {@link
   * StatementGenerationService#run(java.util.function.Consumer, java.util.function.Consumer)} once
   * with the writers as the {@code stmtSink}/{@code htmlSink}, and finally closes both writers in a
   * nested {@code finally} so neither sink is leaked if production fails. It then reports {@link
   * RepeatStatus#FINISHED}, mirroring the one-shot execution of the legacy {@code CBSTM03A}. Any
   * exception raised by the service or the writers (notably the abend-equivalent {@code
   * IoStatusException}) is allowed to propagate so the step fails, per AAP &sect;0.6.6.
   *
   * <p>The bean is {@code @StepScope} so the {@code outputDir} job parameter can be late-bound at
   * step-execution time rather than at context-refresh time.
   *
   * @param outputDir the optional {@code outputDir} job-parameter value (may be {@code null})
   * @return the configured statement-generation tasklet
   */
  @Bean
  @StepScope
  public Tasklet statementGenerationTasklet(
      @Value("#{jobParameters['outputDir']}") String outputDir) {
    return (contribution, chunkContext) -> {
      Path stmtPath = resolveOutputPath(outputDir, STMT_FILE_NAME);
      Path htmlPath = resolveOutputPath(outputDir, HTML_FILE_NAME);
      StatementFileWriter stmtWriter = new StatementFileWriter();
      HtmlStatementWriter htmlWriter = new HtmlStatementWriter();
      try {
        stmtWriter.open(stmtPath);
        htmlWriter.open(htmlPath);
        statementGenerationService.run(stmtWriter, htmlWriter);
      } finally {
        try {
          stmtWriter.close();
        } finally {
          htmlWriter.close();
        }
      }
      return RepeatStatus.FINISHED;
    };
  }

  /**
   * Defines the single step ({@code statementGenerationStep}) of the statement-generation job,
   * reproducing the lone {@code STEP040 EXEC PGM=CBSTM03A} step of {@code CREASTMT.JCL}.
   *
   * @param jobRepository the Boot-provided Spring Batch job repository (injected as a method
   *     parameter, never via {@code @EnableBatchProcessing})
   * @param transactionManager the Boot-provided platform transaction manager that brackets the
   *     tasklet execution
   * @param statementGenerationTasklet the step-scoped statement-generation tasklet (injected by
   *     bean name)
   * @return the configured single-execution tasklet step
   */
  @Bean
  public Step statementGenerationStep(
      JobRepository jobRepository,
      PlatformTransactionManager transactionManager,
      Tasklet statementGenerationTasklet) {
    return new StepBuilder("statementGenerationStep", jobRepository)
        .tasklet(statementGenerationTasklet, transactionManager)
        .build();
  }

  /**
   * Defines the statement-generation job ({@code statementGenerationJob}) as a single-step job that
   * runs {@link #statementGenerationStep(JobRepository, PlatformTransactionManager, Tasklet)},
   * mirroring the single-driver-step structure of {@code CREASTMT.JCL}.
   *
   * @param jobRepository the Boot-provided Spring Batch job repository (injected as a method
   *     parameter)
   * @param statementGenerationStep the single step of this job (injected by bean name)
   * @return the configured single-step job
   */
  @Bean
  public Job statementGenerationJob(JobRepository jobRepository, Step statementGenerationStep) {
    return new JobBuilder("statementGenerationJob", jobRepository)
        .start(statementGenerationStep)
        .build();
  }
}
