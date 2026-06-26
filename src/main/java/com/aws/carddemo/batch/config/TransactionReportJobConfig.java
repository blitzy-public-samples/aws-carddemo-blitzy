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

import com.aws.carddemo.batch.writer.ReportFileWriter;
import com.aws.carddemo.service.batch.TransactionReportService;
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
 * Spring Batch configuration that reproduces the legacy transaction-detail-report job {@code
 * legacy/app/jcl/TRANREPT.jcl} together with its in-stream PROC {@code
 * legacy/app/proc/TRANREPT.prc}. The report-producing driver of that job is {@code STEP10R EXEC
 * PGM=CBTRN03C}, whose {@code TRANREPT} output DD is declared {@code
 * DCB=(LRECL=133,RECFM=FB,BLKSIZE=0)} — a fixed 133-byte print dataset (Agent Action Plan
 * &sect;0.4.1, the {@code TransactionReportJobConfig} + {@code TransactionReportService} pair).
 *
 * <p><strong>Legacy job shape.</strong> The JCL first unloads {@code TRANSACT} (IDCAMS {@code
 * REPRO} via {@code REPROC.prc}), then runs a {@code SORT FIELDS=(TRAN-CARD-NUM,A)} with {@code
 * INCLUDE COND=(TRAN-PROC-DT,GE,PARM-START-DATE,AND,TRAN-PROC-DT,LE,PARM-END-DATE)} where {@code
 * PARM-START-DATE=C'2022-01-01'} and {@code PARM-END-DATE=C'2022-07-06'}, producing a
 * card-number-ordered, date-filtered working set that {@code CBTRN03C} reads to emit a detail
 * report with <em>page</em>, <em>account</em> and <em>grand</em> totals. In the Java translation
 * the card-number sort and the in-memory date filter are handled <em>inside</em> {@link
 * TransactionReportService}; the two date bounds become Spring Batch {@code JobParameters} ({@code
 * startDate}, {@code endDate}) — the faithful counterpart of the JCL {@code PARM}/{@code SYMNAMES}
 * date constants (AAP &sect;0.1.1: JCL {@code PARM} &rarr; {@code JobParameter}).
 *
 * <p><strong>Responsibility split.</strong> All business logic (the {@code CBTRN03C} control flow,
 * the three indexed lookups, decimal-faithful totalling, and the byte-exact 133-character line
 * formatting) lives in {@link TransactionReportService}; this configuration contains <em>no</em>
 * business logic. Its single {@link Step} is a {@link Tasklet} that (1) resolves the {@code
 * TRANREPT} output path, (2) instantiates and opens a {@link ReportFileWriter} sink, (3) invokes
 * {@link TransactionReportService#run(String, String, java.util.function.Consumer)} passing the
 * writer directly as the {@code reportSink}, and (4) closes the writer in a {@code finally} block
 * so the dataset is always released.
 *
 * <p><strong>Abend parity (AAP &sect;0.6.6).</strong> The tasklet does <em>not</em> catch the
 * exceptions raised by the service or the writer. {@link TransactionReportService} translates an
 * unexpected {@code FILE STATUS} I/O failure into a {@code com.aws.carddemo.exception
 * .IoStatusException} (the Java counterpart of the COBOL {@code 9999-ABEND-PROGRAM}), and {@link
 * ReportFileWriter} raises the same exception type on any open/write/close failure. By letting
 * these propagate out of the tasklet, the surrounding step ends with a {@code FAILED} {@link
 * org.springframework.batch.core.BatchStatus}/{@code ExitStatus}, the faithful abend-equivalent for
 * a batch run. The checked {@link IOException} raised purely by output-directory resolution is
 * likewise allowed to propagate (a {@link Tasklet} may throw any {@link Exception}).
 *
 * <p><strong>Output location.</strong> The report destination is resolved through the locked
 * project convention shared by the sibling batch configurations: an optional {@code outputDir} job
 * parameter takes precedence, otherwise the configured {@code carddemo.batch.output-dir} property
 * is used, defaulting to the JVM temporary directory ({@code java.io.tmpdir}). Missing parent
 * directories are created so the subsequent write cannot fail merely because the directory is
 * absent. The fixed file name is {@code TRANREPT}, matching the legacy output DD name.
 *
 * <p><strong>Spring Batch wiring conventions.</strong> Following the project-wide convention this
 * class deliberately omits {@code @EnableBatchProcessing}: under Spring Boot 3.5.x the
 * auto-configuration already supplies a {@link JobRepository} and a {@code JobLauncher}, and adding
 * the annotation would switch that off. The Boot-provided {@link JobRepository} and {@link
 * PlatformTransactionManager} are injected as {@code @Bean} <em>method parameters</em>, and the job
 * and step are assembled with the Spring Batch 5 {@link JobBuilder}/{@link StepBuilder} fluent API.
 * The report tasklet is {@code @StepScope} so the {@code startDate}, {@code endDate} and {@code
 * outputDir} job parameters are late-bound at step-execution time; when absent, the run dates fall
 * back to the legacy defaults {@value #DEFAULT_START_DATE} and {@value #DEFAULT_END_DATE} so the
 * job is runnable without parameters.
 *
 * @see TransactionReportService
 * @see ReportFileWriter
 */
@Configuration
public class TransactionReportJobConfig {

  /**
   * Default report start date ({@code PARM-START-DATE=C'2022-01-01'} from {@code TRANREPT.jcl} /
   * {@code TRANREPT.prc}), applied when the {@code startDate} job parameter is absent or blank.
   */
  private static final String DEFAULT_START_DATE = "2022-01-01";

  /**
   * Default report end date ({@code PARM-END-DATE=C'2022-07-06'} from {@code TRANREPT.jcl} / {@code
   * TRANREPT.prc}), applied when the {@code endDate} job parameter is absent or blank.
   */
  private static final String DEFAULT_END_DATE = "2022-07-06";

  /**
   * Logical output file name for the transaction-detail report; mirrors the legacy {@code TRANREPT}
   * output DD ({@code AWS.M2.CARDDEMO.TRANREPT}, {@code LRECL=133}).
   */
  private static final String REPORT_FILE_NAME = "TRANREPT";

  /**
   * The migrated {@code CBTRN03C} business service that produces the report lines. Constructor-
   * injected so this configuration stays trivially unit-testable with a mock service.
   */
  private final TransactionReportService transactionReportService;

  /**
   * The configured base output directory, bound from the {@code carddemo.batch.output-dir} property
   * and defaulting to {@code java.io.tmpdir}. Used whenever the job is launched without an explicit
   * {@code outputDir} job parameter.
   */
  private final String configuredOutputDir;

  /**
   * Creates the transaction-report configuration.
   *
   * @param transactionReportService the migrated {@code CBTRN03C} report service the tasklet
   *     delegates to; must not be {@code null}
   * @param configuredOutputDir base output directory bound from {@code carddemo.batch.output-dir},
   *     defaulting to the JVM temporary directory ({@code java.io.tmpdir}) when the property is
   *     absent
   */
  public TransactionReportJobConfig(
      TransactionReportService transactionReportService,
      @Value("${carddemo.batch.output-dir:#{systemProperties['java.io.tmpdir']}}")
          String configuredOutputDir) {
    this.transactionReportService = transactionReportService;
    this.configuredOutputDir = configuredOutputDir;
  }

  /**
   * Resolves the absolute path of the report file, applying the locked output-directory convention.
   *
   * <p>The base directory is the supplied {@code jobParamDir} when it is non-blank, otherwise the
   * {@linkplain #configuredOutputDir configured directory}. Any missing parent directories are
   * created so the subsequent write cannot fail merely because the directory is absent.
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
   * Defines the step-scoped report tasklet, the single unit of work that reproduces {@code STEP10R
   * EXEC PGM=CBTRN03C}.
   *
   * <p>The {@code startDate}/{@code endDate} job parameters select the inclusive date window
   * applied by {@link TransactionReportService}; when either is absent or blank the legacy defaults
   * ({@value #DEFAULT_START_DATE} / {@value #DEFAULT_END_DATE}) are used so the job runs without
   * parameters. The optional {@code outputDir} parameter overrides the configured base output
   * directory. The bean is {@code @StepScope} so all three parameters are late-bound at
   * step-execution time rather than at context-refresh time.
   *
   * <p>The tasklet resolves the report path, opens a fresh {@link ReportFileWriter} sink, runs the
   * service with the writer as the {@code reportSink}, and always closes the writer in a {@code
   * finally} block. Exceptions from path resolution, the service, or the writer are intentionally
   * left to propagate so the step fails with abend parity (AAP &sect;0.6.6).
   *
   * @param startDate the {@code startDate} job-parameter value (may be {@code null} or blank)
   * @param endDate the {@code endDate} job-parameter value (may be {@code null} or blank)
   * @param outputDir the optional {@code outputDir} job-parameter value (may be {@code null})
   * @return the configured transaction-report tasklet
   */
  @Bean
  @StepScope
  public Tasklet transactionReportTasklet(
      @Value("#{jobParameters['startDate']}") String startDate,
      @Value("#{jobParameters['endDate']}") String endDate,
      @Value("#{jobParameters['outputDir']}") String outputDir) {
    final String start =
        (startDate != null && !startDate.isBlank()) ? startDate : DEFAULT_START_DATE;
    final String end = (endDate != null && !endDate.isBlank()) ? endDate : DEFAULT_END_DATE;
    return (contribution, chunkContext) -> {
      Path reportPath = resolveOutputPath(outputDir, REPORT_FILE_NAME);
      ReportFileWriter reportWriter = new ReportFileWriter();
      try {
        reportWriter.open(reportPath);
        transactionReportService.run(start, end, reportWriter);
      } finally {
        reportWriter.close();
      }
      return RepeatStatus.FINISHED;
    };
  }

  /**
   * Defines the single step ({@code transactionReportStep}) of the transaction-report job, wrapping
   * {@link #transactionReportTasklet(String, String, String)}.
   *
   * @param jobRepository the Boot-provided Spring Batch job repository (injected as a method
   *     parameter, never via {@code @EnableBatchProcessing})
   * @param transactionManager the Boot-provided platform transaction manager that brackets the
   *     tasklet execution
   * @param transactionReportTasklet the step-scoped report tasklet (injected by bean name)
   * @return the configured single-execution tasklet step
   */
  @Bean
  public Step transactionReportStep(
      JobRepository jobRepository,
      PlatformTransactionManager transactionManager,
      Tasklet transactionReportTasklet) {
    return new StepBuilder("transactionReportStep", jobRepository)
        .tasklet(transactionReportTasklet, transactionManager)
        .build();
  }

  /**
   * Defines the transaction-report job ({@code transactionReportJob}) as a single-step job that
   * runs {@link #transactionReportStep(JobRepository, PlatformTransactionManager, Tasklet)},
   * mirroring the report-producing structure of {@code TRANREPT.jcl} ({@code STEP10R EXEC
   * PGM=CBTRN03C}).
   *
   * @param jobRepository the Boot-provided Spring Batch job repository (injected as a method
   *     parameter)
   * @param transactionReportStep the single step of this job (injected by bean name)
   * @return the configured single-step job
   */
  @Bean
  public Job transactionReportJob(JobRepository jobRepository, Step transactionReportStep) {
    return new JobBuilder("transactionReportJob", jobRepository)
        .start(transactionReportStep)
        .build();
  }
}
