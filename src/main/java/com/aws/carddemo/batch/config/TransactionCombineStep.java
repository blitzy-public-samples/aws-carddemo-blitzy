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

import com.aws.carddemo.domain.Transaction;
import com.aws.carddemo.exception.IoStatusException;
import com.aws.carddemo.repository.TransactionRepository;
import com.aws.carddemo.util.CobolStringUtils;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.io.BufferedWriter;
import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.Iterator;
import java.util.stream.Stream;
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
 * Spring Batch configuration that reproduces the legacy combine-transactions job {@code
 * legacy/app/jcl/COMBTRAN.jcl} (with IDCAMS control {@code legacy/app/ctl/REPROCT.ctl}, invoked via
 * {@code legacy/app/proc/REPROC.prc}). This config has <strong>no</strong> {@code service.batch}
 * counterpart because the legacy job is pure DFSORT/IDCAMS infrastructure rather than business
 * logic; the tasklet bodies orchestrate the file work directly.
 *
 * <p><strong>Legacy job structure (two steps).</strong>
 *
 * <ol>
 *   <li>{@code STEP05R EXEC PGM=SORT} &mdash; {@code SORT FIELDS=(TRAN-ID,A)} (ascending) over the
 *       concatenation of {@code TRANSACT.BKUP(0)} and {@code SYSTRAN(0)}, with {@code SYMNAMES
 *       TRAN-ID,1,16,CH}, producing {@code SORTOUT = TRANSACT.COMBINED(+1)}. Each record is the
 *       350-byte transaction record described by copybook {@code CVTRA05Y}.
 *   <li>{@code STEP10 EXEC PGM=IDCAMS} &mdash; {@code REPRO INFILE(TRANSACT=COMBINED)
 *       OUTFILE(TRANVSAM=TRANSACT.VSAM.KSDS)}, loading the combined sequential file into the
 *       transaction master KSDS.
 * </ol>
 *
 * <p><strong>Java translation.</strong> The single transaction master is already migrated to the
 * PostgreSQL {@code transaction} table (entity {@link Transaction}), so the combine job is realized
 * as two {@link org.springframework.batch.core.step.tasklet.Tasklet}-based steps:
 *
 * <ol>
 *   <li><em>sort step</em> &mdash; streams every transaction ordered ascending by {@code TRAN-ID}
 *       via {@link TransactionRepository#streamAllByOrderByTranIdAsc()} (a JDBC-cursor-backed
 *       stream that bounds heap, QA F-2) and writes each one as a 350-byte fixed-width {@code
 *       CVTRA05Y} record (newline-separated) into the {@code TRANSACT.COMBINED} output file;
 *   <li><em>repro step</em> &mdash; copies {@code TRANSACT.COMBINED} to {@code TRANSACT.VSAM.KSDS},
 *       mirroring the IDCAMS {@code REPRO} load of the combined file into the transaction master.
 * </ol>
 *
 * <p><strong>Ordering parity (AAP &sect;0.6.3).</strong> The combine output must be ascending by
 * {@code TRAN-ID}. Because {@code TRAN-ID} is the primary key the ordering is deterministic with no
 * ties (the legacy {@code SORT} specified no {@code EQUALS} option), so the repository-driven
 * {@code ORDER BY tran_id ASC} reproduces the DFSORT result exactly.
 *
 * <p><strong>Decimal fidelity (AAP &sect;0.6.1).</strong> The signed transaction amount {@code
 * TRAN-AMT PIC S9(09)V99} is encoded from {@link BigDecimal} using {@link RoundingMode#DOWN}
 * truncation (never {@code float}/{@code double}) and rendered as an 11-character zoned-decimal
 * field whose final byte carries the sign via the standard ASCII trailing overpunch.
 *
 * <p><strong>Spring Batch wiring.</strong> Following the project convention there is no
 * {@code @EnableBatchProcessing}; the Boot-provided {@link JobRepository} and {@link
 * PlatformTransactionManager} are injected as {@code @Bean} method parameters. The two
 * {@code @StepScope} tasklets and the two {@link Step}s are injected by parameter name (parameter
 * names are retained at compile time) so the duplicate bean types are disambiguated unambiguously.
 *
 * <p><strong>Output location.</strong> File destinations are resolved through a locked convention:
 * an optional {@code outputDir} job parameter takes precedence, otherwise the configured {@code
 * carddemo.batch.output-dir} property is used, defaulting to the JVM temporary directory ({@code
 * java.io.tmpdir}). Parent directories are created as needed.
 */
@Configuration
public class TransactionCombineStep {

  /**
   * Logical output file name for the sorted, combined transactions (legacy {@code
   * TRANSACT.COMBINED} generation dataset, the {@code SORTOUT} of {@code STEP05R} and the {@code
   * INFILE} of the {@code STEP10} REPRO).
   */
  private static final String COMBINED_FILE = "TRANSACT.COMBINED";

  /**
   * Logical output file name for the transaction master load target (legacy {@code
   * TRANSACT.VSAM.KSDS}, the {@code OUTFILE} of the {@code STEP10} REPRO).
   */
  private static final String TRANSACT_FILE = "TRANSACT.VSAM.KSDS";

  /**
   * DD/file name used when wrapping I/O failures from the combine (sort) step into an {@link
   * IoStatusException}; mirrors the {@code TRANSACT} DD that addresses {@code COMBINED} in the
   * legacy JCL.
   */
  private static final String COMBINED_DD = "COMBINED";

  /**
   * DD/file name used when wrapping I/O failures from the repro (load) step into an {@link
   * IoStatusException}; mirrors the {@code TRANVSAM} DD that addresses the transaction master.
   */
  private static final String TRANSACT_DD = "TRANSACT";

  /** Exact fixed-width length, in characters, of a {@code CVTRA05Y} transaction record. */
  private static final int RECORD_LENGTH = 350;

  /**
   * Number of integer digits in the {@code TRAN-AMT PIC S9(09)V99} amount (the {@code 9(09)}
   * portion).
   */
  private static final int AMOUNT_INT_DIGITS = 9;

  /**
   * Number of fractional digits in the {@code TRAN-AMT PIC S9(09)V99} amount (the {@code V99}
   * portion).
   */
  private static final int AMOUNT_FRAC_DIGITS = 2;

  /**
   * Trailing-overpunch characters for a <em>non-negative</em> final digit, indexed by that digit
   * {@code 0..9}: {@code 0 -> '{'} and {@code 1..9 -> 'A'..'I'}. Standard ASCII zoned-decimal
   * positive overpunch (confirmed by the fixture amount {@code 504.77 -> 0000005047G}, where {@code
   * G} encodes a final digit of {@code 7} with a positive sign).
   */
  private static final char[] POS_OVERPUNCH = {'{', 'A', 'B', 'C', 'D', 'E', 'F', 'G', 'H', 'I'};

  /**
   * Trailing-overpunch characters for a <em>negative</em> final digit, indexed by that digit {@code
   * 0..9}: {@code 0 -> '}'} and {@code 1..9 -> 'J'..'R'}. Standard ASCII zoned-decimal negative
   * overpunch.
   */
  private static final char[] NEG_OVERPUNCH = {'}', 'J', 'K', 'L', 'M', 'N', 'O', 'P', 'Q', 'R'};

  /**
   * Repository over the migrated transaction master; supplies the ascending {@code TRAN-ID} read.
   */
  private final TransactionRepository transactionRepository;

  /**
   * The configured base output directory, resolved from the {@code carddemo.batch.output-dir}
   * property and defaulting to {@code java.io.tmpdir}. Used whenever the job is launched without an
   * explicit {@code outputDir} job parameter.
   */
  private final String configuredOutputDir;

  /**
   * JPA persistence context used to detach each transaction immediately after it is written to the
   * combined file, so the streaming sort tasklet holds only the JDBC fetch window in heap rather
   * than the whole transaction master (QA F-2). The tasklet's only output is a file (no database
   * writes), so detaching each just-read row is always safe.
   */
  @PersistenceContext private EntityManager entityManager;

  /**
   * Creates the combine-job configuration.
   *
   * @param transactionRepository repository used to read all transactions ordered ascending by
   *     {@code TRAN-ID} (must not be {@code null})
   * @param configuredOutputDir base output directory bound from {@code carddemo.batch.output-dir},
   *     defaulting to the JVM temporary directory ({@code java.io.tmpdir}) when the property is
   *     absent
   */
  public TransactionCombineStep(
      TransactionRepository transactionRepository,
      @Value("${carddemo.batch.output-dir:#{systemProperties['java.io.tmpdir']}}")
          String configuredOutputDir) {
    this.transactionRepository = transactionRepository;
    this.configuredOutputDir = configuredOutputDir;
  }

  /**
   * Resolves the absolute path of an output file, applying the locked output-directory convention.
   *
   * <p>The base directory is the supplied {@code jobParamDir} when it is non-blank, otherwise the
   * {@linkplain #configuredOutputDir configured directory}. Any missing parent directories are
   * created so that the subsequent write/copy cannot fail merely because the directory is absent.
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
   * Serializes a {@link Transaction} into exactly {@value #RECORD_LENGTH} characters following the
   * {@code CVTRA05Y} fixed-width layout (the 14 contiguous fields, in copybook order, ending with a
   * 20-byte {@code FILLER}).
   *
   * <p>Text and unsigned-numeric fields use {@link CobolStringUtils} (left-justified space padding
   * for {@code PIC X}, right-justified zero padding for {@code PIC 9}); the signed amount uses
   * {@link #encodeSignedAmount(BigDecimal, int, int)}. The assembled record length is asserted to
   * guarantee byte-faithful parity with the legacy file.
   *
   * @param t the transaction to serialize (must not be {@code null})
   * @return the 350-character fixed-width record
   * @throws IllegalStateException if the assembled record is not exactly {@value #RECORD_LENGTH}
   *     characters (a programming error in the layout)
   */
  private static String toFixedWidthRecord(Transaction t) {
    long merchantId = (t.getTranMerchantId() == null) ? 0L : t.getTranMerchantId();
    StringBuilder record = new StringBuilder(RECORD_LENGTH);
    record
        .append(CobolStringUtils.fixedWidth(t.getTranId(), 16))
        .append(CobolStringUtils.fixedWidth(t.getTranTypeCd(), 2))
        .append(CobolStringUtils.padLeftZeros(t.getTranCatCd(), 4))
        .append(CobolStringUtils.fixedWidth(t.getTranSource(), 10))
        .append(CobolStringUtils.fixedWidth(t.getTranDesc(), 100))
        .append(encodeSignedAmount(t.getTranAmt(), AMOUNT_INT_DIGITS, AMOUNT_FRAC_DIGITS))
        .append(CobolStringUtils.padLeftZeros(merchantId, 9))
        .append(CobolStringUtils.fixedWidth(t.getTranMerchantName(), 50))
        .append(CobolStringUtils.fixedWidth(t.getTranMerchantCity(), 50))
        .append(CobolStringUtils.fixedWidth(t.getTranMerchantZip(), 10))
        .append(CobolStringUtils.fixedWidth(t.getTranCardNum(), 16))
        .append(CobolStringUtils.fixedWidth(t.getTranOrigTs(), 26))
        .append(CobolStringUtils.fixedWidth(t.getTranProcTs(), 26))
        .append(CobolStringUtils.spaces(20));
    String result = record.toString();
    if (result.length() != RECORD_LENGTH) {
      throw new IllegalStateException(
          "Assembled transaction record length "
              + result.length()
              + " != expected "
              + RECORD_LENGTH);
    }
    return result;
  }

  /**
   * Encodes a {@link BigDecimal} amount as a COBOL zoned-decimal display field with a
   * <em>trailing</em> overpunch sign, reproducing {@code PIC S9(intDigits)V(fracDigits)} byte for
   * byte.
   *
   * <p>The value is first truncated (never rounded up) to {@code fracDigits} using {@link
   * RoundingMode#DOWN}, matching COBOL fixed-point assignment. Its magnitude digits are then
   * left-padded with zeros to {@code intDigits + fracDigits} characters, and the final digit is
   * replaced by the overpunch character that encodes both that digit and the sign &mdash; positive
   * via {@link #POS_OVERPUNCH}, negative via {@link #NEG_OVERPUNCH}. A {@code null} amount and a
   * truncated value of zero are both treated as non-negative, matching the COBOL sign convention.
   *
   * @param amount the amount to encode; {@code null} is treated as {@link BigDecimal#ZERO}
   * @param intDigits the number of integer digits in the picture (the {@code 9(n)} portion)
   * @param fracDigits the number of fractional digits in the picture (the {@code V99} portion)
   * @return the zoned-decimal string of length {@code intDigits + fracDigits}
   */
  private static String encodeSignedAmount(BigDecimal amount, int intDigits, int fracDigits) {
    BigDecimal value = (amount == null) ? BigDecimal.ZERO : amount;
    BigDecimal scaled = value.setScale(fracDigits, RoundingMode.DOWN);
    boolean negative = scaled.signum() < 0;
    String digits = scaled.abs().movePointRight(fracDigits).toBigInteger().toString();
    digits = CobolStringUtils.padLeftZeros(digits, intDigits + fracDigits);
    int lastDigit = digits.charAt(digits.length() - 1) - '0';
    char overpunch = negative ? NEG_OVERPUNCH[lastDigit] : POS_OVERPUNCH[lastDigit];
    return digits.substring(0, digits.length() - 1) + overpunch;
  }

  /**
   * Sort-step tasklet: reads every transaction ascending by {@code TRAN-ID} and writes each one as
   * a 350-byte fixed-width {@code CVTRA05Y} record (newline-terminated) into {@code
   * TRANSACT.COMBINED}, reproducing {@code STEP05R} ({@code SORT FIELDS=(TRAN-ID,A)} &rarr; {@code
   * SORTOUT}). Step-scoped so the {@code outputDir} job parameter can be late-bound.
   *
   * @param outputDir the optional {@code outputDir} job-parameter value (may be {@code null})
   * @return the tasklet that performs the combine/sort write
   */
  @Bean
  @StepScope
  public Tasklet transactionCombineSortTasklet(
      @Value("#{jobParameters['outputDir']}") String outputDir) {
    return (contribution, chunkContext) -> {
      try {
        Path combinedPath = resolveOutputPath(outputDir, COMBINED_FILE);
        try (BufferedWriter writer = Files.newBufferedWriter(combinedPath, StandardCharsets.UTF_8);
            Stream<Transaction> transactions =
                transactionRepository.streamAllByOrderByTranIdAsc()) {
          Iterator<Transaction> it = transactions.iterator();
          while (it.hasNext()) {
            Transaction transaction = it.next();
            writer.write(toFixedWidthRecord(transaction));
            writer.write("\n");
            // Release each row after writing so heap stays bounded to the JDBC fetch window rather
            // than the full transaction master (QA F-2). The output is a file, not the database, so
            // there is no pending mutation to discard.
            entityManager.detach(transaction);
          }
        }
      } catch (IOException e) {
        throw new IoStatusException(COMBINED_DD, "WRITE", "30", e);
      }
      return RepeatStatus.FINISHED;
    };
  }

  /**
   * Repro-step tasklet: copies {@code TRANSACT.COMBINED} onto {@code TRANSACT.VSAM.KSDS} (replacing
   * any existing file), reproducing {@code STEP10} ({@code REPRO INFILE(TRANSACT=COMBINED)
   * OUTFILE(TRANVSAM=TRANSACT.VSAM.KSDS)}). Step-scoped so the {@code outputDir} job parameter can
   * be late-bound.
   *
   * @param outputDir the optional {@code outputDir} job-parameter value (may be {@code null})
   * @return the tasklet that performs the repro/load copy
   */
  @Bean
  @StepScope
  public Tasklet transactionCombineReproTasklet(
      @Value("#{jobParameters['outputDir']}") String outputDir) {
    return (contribution, chunkContext) -> {
      try {
        Path combinedPath = resolveOutputPath(outputDir, COMBINED_FILE);
        Path transactPath = resolveOutputPath(outputDir, TRANSACT_FILE);
        Files.copy(combinedPath, transactPath, StandardCopyOption.REPLACE_EXISTING);
      } catch (IOException e) {
        throw new IoStatusException(TRANSACT_DD, "WRITE", "30", e);
      }
      return RepeatStatus.FINISHED;
    };
  }

  /**
   * Defines the sort step ({@code transactionCombineSortStep}) wrapping {@link
   * #transactionCombineSortTasklet(String)}.
   *
   * @param jobRepository the Boot-provided Spring Batch job repository
   * @param transactionManager the Boot-provided platform transaction manager
   * @param transactionCombineSortTasklet the step-scoped sort tasklet (injected by bean name)
   * @return the configured sort step
   */
  @Bean
  public Step transactionCombineSortStep(
      JobRepository jobRepository,
      PlatformTransactionManager transactionManager,
      Tasklet transactionCombineSortTasklet) {
    return new StepBuilder("transactionCombineSortStep", jobRepository)
        .tasklet(transactionCombineSortTasklet, transactionManager)
        .build();
  }

  /**
   * Defines the repro step ({@code transactionCombineReproStep}) wrapping {@link
   * #transactionCombineReproTasklet(String)}.
   *
   * @param jobRepository the Boot-provided Spring Batch job repository
   * @param transactionManager the Boot-provided platform transaction manager
   * @param transactionCombineReproTasklet the step-scoped repro tasklet (injected by bean name)
   * @return the configured repro step
   */
  @Bean
  public Step transactionCombineReproStep(
      JobRepository jobRepository,
      PlatformTransactionManager transactionManager,
      Tasklet transactionCombineReproTasklet) {
    return new StepBuilder("transactionCombineReproStep", jobRepository)
        .tasklet(transactionCombineReproTasklet, transactionManager)
        .build();
  }

  /**
   * Defines the two-step combine job ({@code transactionCombineJob}): the sort step followed by the
   * repro step, mirroring {@code COMBTRAN.jcl}'s {@code STEP05R} &rarr; {@code STEP10} sequence.
   *
   * @param jobRepository the Boot-provided Spring Batch job repository
   * @param transactionCombineSortStep the sort step (injected by bean name)
   * @param transactionCombineReproStep the repro step (injected by bean name)
   * @return the configured combine job
   */
  @Bean
  public Job transactionCombineJob(
      JobRepository jobRepository,
      Step transactionCombineSortStep,
      Step transactionCombineReproStep) {
    return new JobBuilder("transactionCombineJob", jobRepository)
        .start(transactionCombineSortStep)
        .next(transactionCombineReproStep)
        .build();
  }
}
