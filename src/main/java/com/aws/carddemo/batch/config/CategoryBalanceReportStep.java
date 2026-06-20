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

import com.aws.carddemo.domain.TransactionCategoryBalance;
import com.aws.carddemo.domain.id.TransactionCategoryBalanceId;
import com.aws.carddemo.exception.IoStatusException;
import com.aws.carddemo.repository.TransactionCategoryBalanceRepository;
import com.aws.carddemo.util.CobolStringUtils;
import java.io.BufferedWriter;
import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
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
import org.springframework.data.domain.Sort;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * Spring Batch configuration that reproduces the legacy print-transaction-category-balance job
 * {@code legacy/app/jcl/PRTCATBL.jcl} (which drives the IDCAMS unload through {@code
 * legacy/app/proc/REPROC.prc} with control statements {@code legacy/app/ctl/REPROCT.ctl}). This
 * config has <strong>no</strong> {@code service.batch} counterpart because the legacy job is pure
 * DFSORT/IDCAMS infrastructure (a reporting unload) rather than business logic; the tasklet body
 * orchestrates the read, ordering, formatting and write directly.
 *
 * <p><strong>Legacy job structure (three steps).</strong>
 *
 * <ol>
 *   <li>{@code DELDEF EXEC PGM=IEFBR14} &mdash; deletes any prior {@code
 *       AWS.M2.CARDDEMO.TCATBALF.REPT} report dataset.
 *   <li>{@code STEP05R EXEC PROC=REPROC} &mdash; an IDCAMS {@code REPRO INFILE(FILEIN)
 *       OUTFILE(FILEOUT)} that unloads the transaction-category-balance KSDS {@code
 *       TCATBALF.VSAM.KSDS} into the 50-byte sequential backup {@code TCATBALF.BKUP}.
 *   <li>{@code STEP10R EXEC PGM=SORT} &mdash; a DFSORT step that sorts the backup ascending by the
 *       three key fields and emits a fixed 40-byte report ({@code SORTOUT = TCATBALF.REPT}, {@code
 *       LRECL=40}). The {@code SYMNAMES} pin the byte offsets {@code TRANCAT-ACCT-ID,1,11,ZD};
 *       {@code TRANCAT-TYPE-CD,12,2,CH}; {@code TRANCAT-CD,14,4,ZD}; {@code TRAN-CAT-BAL,18,11,ZD};
 *       {@code SORT FIELDS=(TRANCAT-ACCT-ID,A,TRANCAT-TYPE-CD,A,TRANCAT-CD,A)} and {@code OUTREC
 *       FIELDS=(TRANCAT-ACCT-ID,X, TRANCAT-TYPE-CD,X, TRANCAT-CD,X,
 *       TRAN-CAT-BAL,EDIT=(TTTTTTTTT.TT), 9X)}.
 * </ol>
 *
 * <p><strong>Java translation.</strong> The transaction-category-balance store is already migrated
 * to the PostgreSQL {@code tran_cat_balance} table (entity {@link TransactionCategoryBalance}), so
 * the three legacy steps collapse into a single {@link Tasklet}-based {@link Step}: read every row
 * directly from {@link TransactionCategoryBalanceRepository} ordered ascending by the composite key
 * (replacing the IDCAMS unload), compose the 40-character report line per record (replacing the
 * DFSORT {@code OUTREC}), and write the report file (the {@code IEFBR14} delete is realized by
 * opening the output file for replacement, truncating any previous content). Because the read is
 * issued with an {@code ORDER BY} the in-Java sort is implicit, so no separate sort step is needed.
 *
 * <p><strong>Ordering parity (AAP &sect;0.6.3).</strong> The report must be ascending by {@code
 * (TRANCAT-ACCT-ID, TRANCAT-TYPE-CD, TRANCAT-CD)} exactly as the legacy {@code SORT FIELDS} list
 * specifies. This is reproduced by {@link TransactionCategoryBalanceRepository#findAll(Sort)} with
 * the ascending {@link Sort} over the embedded-key property paths {@code id.trancatAcctId}, {@code
 * id.trancatTypeCd} and {@code id.trancatCd}. The full composite key is unique, so the ordering is
 * deterministic with no ties (the legacy {@code SORT} declared no {@code EQUALS} option).
 *
 * <p><strong>Report line layout &mdash; fixed 40 characters ({@code LRECL=40}).</strong> Each line
 * concatenates, in DFSORT {@code OUTREC} order: the 11-digit zero-padded account id, a single space
 * ({@code X}), the 2-character type code, a space, the 4-character category code, a space, the
 * 12-character edited balance ({@code EDIT=(TTTTTTTTT.TT)}), then 9 trailing spaces ({@code 9X}).
 * The raw assembly is {@code 11 + 1 + 2 + 1 + 4 + 1 + 12 + 9 = 41} characters; the legacy {@code
 * SORTOUT LRECL=40} keeps only the first 40, dropping the 41st (a trailing space), which {@link
 * #toReportLine(TransactionCategoryBalance)} reproduces by clamping to width 40.
 *
 * <p><strong>Decimal fidelity (AAP &sect;0.6.1).</strong> {@code TRAN-CAT-BAL PIC S9(09)V99} is
 * held as {@link BigDecimal} (never {@code float}/{@code double}). The DFSORT {@code
 * EDIT=(TTTTTTTTT.TT)} mask renders the value as nine leading-zero-preserved integer digits, a
 * literal {@code '.'} and two fraction digits, with <em>no sign and no grouping commas</em>; {@link
 * #formatCatBalAmount(BigDecimal)} matches it by truncating to two decimals with {@link
 * RoundingMode#DOWN} (COBOL fixed-point assignment never rounds up here) and emitting the absolute
 * value zero-padded to that 12-character shape.
 *
 * <p><strong>Spring Batch wiring.</strong> Following the project convention this class deliberately
 * omits {@code @EnableBatchProcessing}: under Spring Boot 3.5.x the auto-configuration already
 * supplies a {@link JobRepository} and a {@code JobLauncher}, and adding the annotation would
 * switch that off. The Boot-provided {@link JobRepository} and {@link PlatformTransactionManager}
 * are injected as {@code @Bean} method parameters, and the job and step are assembled with the
 * Spring Batch 5 {@link JobBuilder}/{@link StepBuilder} fluent API.
 *
 * <p><strong>Abend parity (AAP &sect;0.6.6).</strong> Any {@link IOException} raised while
 * resolving or writing the report is translated into a {@link IoStatusException} (the Java
 * counterpart of the COBOL {@code 9999-ABEND-PROGRAM}/{@code CEE3ABD} abend) with file {@code
 * TCATBALF}, operation {@code WRITE} and status {@code "30"}; allowing it to propagate out of the
 * tasklet ends the step with a {@code FAILED} batch status, the faithful abend-equivalent for a
 * batch run.
 *
 * <p><strong>Output location.</strong> The report destination is resolved through the locked
 * project convention: an optional {@code outputDir} job parameter takes precedence, otherwise the
 * configured {@code carddemo.batch.output-dir} property is used, defaulting to the JVM temporary
 * directory ({@code java.io.tmpdir}). Parent directories are created as needed.
 *
 * <p>The {@code @Bean} method named {@code categoryBalanceReportStep} returns the {@link Step} (the
 * legacy artifact is conceptually a single reporting step); the {@link Job} wraps it. The file name
 * {@code CategoryBalanceReportStep.java} is retained per Agent Action Plan &sect;0.4.1.
 *
 * @see TransactionCategoryBalanceRepository
 * @see TransactionCategoryBalance
 */
@Configuration
public class CategoryBalanceReportStep {

  /**
   * Logical output file name for the category-balance report (legacy {@code
   * AWS.M2.CARDDEMO.TCATBALF.REPT}, the {@code SORTOUT} of {@code STEP10R}, {@code LRECL=40}).
   */
  private static final String REPORT_FILE = "TCATBALF.REPT";

  /**
   * DD / logical file name used when wrapping I/O failures into an {@link IoStatusException};
   * mirrors the {@code TCATBALF} dataset name of the legacy job.
   */
  private static final String TCATBALF_DD = "TCATBALF";

  /** Total fixed length, in characters, of one report line (legacy {@code SORTOUT LRECL=40}). */
  private static final int REPORT_LINE_LENGTH = 40;

  /** Width, in characters, of the zero-padded {@code TRANCAT-ACCT-ID} field ({@code PIC 9(11)}). */
  private static final int ACCT_ID_WIDTH = 11;

  /** Width, in characters, of the {@code TRANCAT-TYPE-CD} field ({@code PIC X(02)}). */
  private static final int TYPE_CD_WIDTH = 2;

  /** Width, in characters, of the {@code TRANCAT-CD} field ({@code PIC 9(04)}, stored as text). */
  private static final int CAT_CD_WIDTH = 4;

  /** Number of trailing spaces emitted after the amount (the DFSORT {@code 9X} suffix). */
  private static final int TRAILING_SPACES = 9;

  /** Number of integer digits in the edited balance ({@code TTTTTTTTT}, the {@code 9(09)} part). */
  private static final int AMOUNT_INT_DIGITS = 9;

  /** Number of fraction digits in the edited balance ({@code TT}, the {@code V99} part). */
  private static final int AMOUNT_FRAC_DIGITS = 2;

  /** Number of cents per whole currency unit; used to split the truncated balance. */
  private static final long CENTS_PER_UNIT = 100L;

  /**
   * Repository over the migrated transaction-category-balance store; supplies the ascending
   * composite-key read that replaces the legacy IDCAMS unload plus DFSORT sort.
   */
  private final TransactionCategoryBalanceRepository transactionCategoryBalanceRepository;

  /**
   * The configured base output directory, bound from the {@code carddemo.batch.output-dir} property
   * and defaulting to {@code java.io.tmpdir}. Used whenever the job is launched without an explicit
   * {@code outputDir} job parameter.
   */
  private final String configuredOutputDir;

  /**
   * Creates the category-balance-report configuration.
   *
   * @param transactionCategoryBalanceRepository repository used to read every category-balance row
   *     ordered ascending by the composite key (must not be {@code null})
   * @param configuredOutputDir base output directory bound from {@code carddemo.batch.output-dir},
   *     defaulting to the JVM temporary directory ({@code java.io.tmpdir}) when the property is
   *     absent
   */
  public CategoryBalanceReportStep(
      TransactionCategoryBalanceRepository transactionCategoryBalanceRepository,
      @Value("${carddemo.batch.output-dir:#{systemProperties['java.io.tmpdir']}}")
          String configuredOutputDir) {
    this.transactionCategoryBalanceRepository = transactionCategoryBalanceRepository;
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
   * Formats a {@code TRAN-CAT-BAL} value to the 12-character DFSORT {@code EDIT=(TTTTTTTTT.TT)}
   * shape: nine leading-zero-preserved integer digits, a literal {@code '.'}, and two fraction
   * digits, rendering the <em>absolute</em> magnitude with no sign and no grouping commas.
   *
   * <p>The value is first truncated (never rounded up) to two decimals with {@link
   * RoundingMode#DOWN}, matching COBOL fixed-point assignment, then split into whole-unit and cents
   * components which are individually zero-padded. For example {@code 0.00} renders as {@code
   * "000000000.00"} and {@code 1234.56} renders as {@code "000001234.56"}.
   *
   * @param bal the category balance to format; {@code null} is treated as {@link BigDecimal#ZERO}
   * @return the 12-character edited amount string
   */
  private static String formatCatBalAmount(BigDecimal bal) {
    BigDecimal value = (bal == null) ? BigDecimal.ZERO : bal;
    BigDecimal abs = value.abs().setScale(AMOUNT_FRAC_DIGITS, RoundingMode.DOWN);
    long cents = abs.movePointRight(AMOUNT_FRAC_DIGITS).longValueExact();
    String intPart = CobolStringUtils.padLeftZeros(cents / CENTS_PER_UNIT, AMOUNT_INT_DIGITS);
    String fracPart = CobolStringUtils.padLeftZeros(cents % CENTS_PER_UNIT, AMOUNT_FRAC_DIGITS);
    return intPart + "." + fracPart;
  }

  /**
   * Composes the fixed 40-character report line for one category-balance row, reproducing the
   * DFSORT {@code OUTREC FIELDS=(TRANCAT-ACCT-ID,X, TRANCAT-TYPE-CD,X, TRANCAT-CD,X,
   * TRAN-CAT-BAL,EDIT=(TTTTTTTTT.TT),9X)} of {@code STEP10R}.
   *
   * <p>The fields are emitted in copybook/{@code OUTREC} order with single-space ({@code X})
   * separators; the raw assembly is 41 characters and is clamped to the legacy {@code SORTOUT
   * LRECL=40}, dropping the final (41st) trailing space.
   *
   * @param tcb the category-balance row to render (must not be {@code null}; its embedded key and
   *     balance are read directly)
   * @return the report line, exactly {@value #REPORT_LINE_LENGTH} characters long
   */
  private static String toReportLine(TransactionCategoryBalance tcb) {
    TransactionCategoryBalanceId id = tcb.getId();
    String line =
        CobolStringUtils.padLeftZeros(id.getTrancatAcctId(), ACCT_ID_WIDTH)
            + " "
            + CobolStringUtils.fixedWidth(id.getTrancatTypeCd(), TYPE_CD_WIDTH)
            + " "
            + CobolStringUtils.fixedWidth(id.getTrancatCd(), CAT_CD_WIDTH)
            + " "
            + formatCatBalAmount(tcb.getTranCatBal())
            + CobolStringUtils.spaces(TRAILING_SPACES);
    return CobolStringUtils.fixedWidth(line, REPORT_LINE_LENGTH);
  }

  /**
   * Step-scoped tasklet that reproduces the whole {@code PRTCATBL.jcl} unload-and-report flow: it
   * reads every {@link TransactionCategoryBalance} ascending by the composite key, writes each one
   * as a 40-character fixed-width line (newline-terminated) into {@code TCATBALF.REPT}, and reports
   * {@link RepeatStatus#FINISHED}. Opening the writer truncates any previous report content,
   * realizing the legacy {@code IEFBR14} delete step.
   *
   * <p>Step-scoped so the {@code outputDir} job parameter can be late-bound. Any {@link
   * IOException} (from path resolution or the write) is wrapped in an {@link IoStatusException} so
   * the step fails with abend parity (AAP &sect;0.6.6).
   *
   * @param outputDir the optional {@code outputDir} job-parameter value (may be {@code null})
   * @return the tasklet that performs the category-balance report write
   */
  @Bean
  @StepScope
  public Tasklet categoryBalanceReportTasklet(
      @Value("#{jobParameters['outputDir']}") String outputDir) {
    return (contribution, chunkContext) -> {
      List<TransactionCategoryBalance> rows =
          transactionCategoryBalanceRepository.findAll(
              Sort.by("id.trancatAcctId", "id.trancatTypeCd", "id.trancatCd"));
      try {
        Path reportPath = resolveOutputPath(outputDir, REPORT_FILE);
        try (BufferedWriter writer = Files.newBufferedWriter(reportPath, StandardCharsets.UTF_8)) {
          for (TransactionCategoryBalance tcb : rows) {
            writer.write(toReportLine(tcb));
            writer.write("\n");
          }
        }
      } catch (IOException e) {
        throw new IoStatusException(TCATBALF_DD, "WRITE", "30", e);
      }
      return RepeatStatus.FINISHED;
    };
  }

  /**
   * Defines the single step ({@code categoryBalanceReportStep}) of the category-balance-report job,
   * wrapping {@link #categoryBalanceReportTasklet(String)}.
   *
   * @param jobRepository the Boot-provided Spring Batch job repository (injected as a method
   *     parameter, never via {@code @EnableBatchProcessing})
   * @param transactionManager the Boot-provided platform transaction manager that brackets the
   *     tasklet execution
   * @param categoryBalanceReportTasklet the step-scoped report tasklet (injected by bean name)
   * @return the configured single-execution tasklet step
   */
  @Bean
  public Step categoryBalanceReportStep(
      JobRepository jobRepository,
      PlatformTransactionManager transactionManager,
      Tasklet categoryBalanceReportTasklet) {
    return new StepBuilder("categoryBalanceReportStep", jobRepository)
        .tasklet(categoryBalanceReportTasklet, transactionManager)
        .build();
  }

  /**
   * Defines the category-balance-report job ({@code categoryBalanceReportJob}) as a single-step job
   * that runs {@link #categoryBalanceReportStep(JobRepository, PlatformTransactionManager,
   * Tasklet)}, mirroring the consolidated structure of {@code PRTCATBL.jcl}.
   *
   * @param jobRepository the Boot-provided Spring Batch job repository (injected as a method
   *     parameter)
   * @param categoryBalanceReportStep the single step of this job (injected by bean name)
   * @return the configured single-step job
   */
  @Bean
  public Job categoryBalanceReportJob(JobRepository jobRepository, Step categoryBalanceReportStep) {
    return new JobBuilder("categoryBalanceReportJob", jobRepository)
        .start(categoryBalanceReportStep)
        .build();
  }
}
