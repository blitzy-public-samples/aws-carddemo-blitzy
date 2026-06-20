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

import com.aws.carddemo.batch.processor.TransactionPostingProcessor;
import com.aws.carddemo.batch.reader.DailyTransactionItemReader;
import com.aws.carddemo.batch.writer.RejectFileWriter;
import com.aws.carddemo.domain.DailyTransaction;
import com.aws.carddemo.service.batch.TransactionPostingService;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import org.springframework.batch.core.ExitStatus;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.Step;
import org.springframework.batch.core.StepExecution;
import org.springframework.batch.core.StepExecutionListener;
import org.springframework.batch.core.configuration.annotation.StepScope;
import org.springframework.batch.core.job.builder.JobBuilder;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.core.step.builder.StepBuilder;
import org.springframework.batch.item.Chunk;
import org.springframework.batch.item.ExecutionContext;
import org.springframework.batch.item.ItemStreamWriter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * Spring Batch configuration that reproduces the legacy daily transaction posting job, the Java
 * translation of JCL {@code legacy/app/jcl/POSTTRAN.jcl} (single step {@code STEP15 EXEC
 * PGM=CBTRN02C}) and COBOL program {@code legacy/app/cbl/CBTRN02C.cbl}.
 *
 * <p><strong>Parity archetype.</strong> This is the only chunk-oriented step in the migration. For
 * every inbound daily transaction the program validates the record and then either posts it (a
 * transaction-category-balance upsert, an account-balance update and an insert into the transaction
 * master) or rejects it (writing a fixed 430-byte record to the {@code DALYREJS} reject file). The
 * step is assembled as:
 *
 * <ul>
 *   <li>{@code Reader} &mdash; the sibling {@link DailyTransactionItemReader} bean, which pages
 *       through the {@code daily_transaction} table in primary-key order (the JPA replacement for
 *       the sequential {@code DALYTRAN} input dataset).
 *   <li>{@code Processor} &mdash; the sibling {@link TransactionPostingProcessor} bean, which
 *       delegates to {@link TransactionPostingService#processOneTransaction(DailyTransaction)} and
 *       returns a {@link TransactionPostingService.PostingResult} describing whether the record was
 *       posted or rejected.
 *   <li>{@code Writer} &mdash; the {@link RejectRoutingWriter} below, which routes <em>only</em>
 *       rejected results to the {@link RejectFileWriter} ({@code DALYREJS}). The posting database
 *       writes are performed inside the service while processing each item, so they participate in
 *       the chunk's {@link PlatformTransactionManager} transaction (the analogue of the COBOL
 *       per-record {@code REWRITE}/{@code WRITE} sequence).
 * </ul>
 *
 * <p><strong>Exit-status parity (AAP &sect;0.6.6).</strong> COBOL {@code CBTRN02C} executes {@code
 * MOVE 4 TO RETURN-CODE} when {@code WS-REJECT-COUNT &gt; 0} &mdash; it is the only batch program
 * that sets {@code RC=4}. The Spring Batch step therefore reports an {@link ExitStatus} exit code
 * of {@value #REJECT_EXIT_CODE} (while the {@code BatchStatus} stays {@code COMPLETED}) when at
 * least one record was rejected, and the default {@code COMPLETED} ("0") otherwise. An
 * unrecoverable I/O failure surfaced by the service as an unchecked {@code IoStatusException} is
 * deliberately <em>not</em> swallowed: it propagates out of the chunk so the step ends {@code
 * FAILED}, mirroring the COBOL {@code 9999-ABEND-PROGRAM} termination.
 *
 * <p><strong>Configuration conventions (AAP &sect;0.7.3).</strong> No
 * {@code @EnableBatchProcessing} is declared anywhere; the Boot-provided {@link JobRepository} and
 * {@link PlatformTransactionManager} are injected as {@code @Bean} method parameters. The Spring
 * Batch 5 {@link JobBuilder}/{@link StepBuilder} fluent API assembles the job and step. All
 * monetary arithmetic lives in the service and uses {@code BigDecimal}; no floating-point type
 * appears here.
 */
@Configuration
public class TransactionPostingJobConfig {

  /**
   * Step/job {@link ExitStatus} exit code emitted when one or more daily transactions are rejected,
   * reproducing COBOL {@code MOVE 4 TO RETURN-CODE} in {@code CBTRN02C}. Exposed publicly so the
   * integration test can assert the exit code without duplicating the literal.
   */
  public static final String REJECT_EXIT_CODE = "4";

  /**
   * Logical file name of the reject dataset, matching the {@code DALYREJS} DD in {@code
   * legacy/app/jcl/POSTTRAN.jcl} and {@link RejectFileWriter#DD_NAME}.
   */
  private static final String REJECT_FILE = "DALYREJS";

  /** Reader bean ({@code DALYTRAN} input); consumed only by {@link #transactionPostingStep}. */
  private final DailyTransactionItemReader dailyTransactionItemReader;

  /** Processor bean that delegates to the posting service for each daily transaction. */
  private final TransactionPostingProcessor transactionPostingProcessor;

  /**
   * Base output directory for the {@code DALYREJS} reject file, bound from {@code
   * carddemo.batch.output-dir} and defaulting to the JVM temporary directory ({@code
   * java.io.tmpdir}) when the property is absent. An explicit {@code outputDir} job parameter takes
   * precedence (see {@link #resolveOutputPath(String, String)}).
   */
  private final String configuredOutputDir;

  /**
   * Chunk (commit interval) size, bound from {@code carddemo.batch.posting.chunk-size} and
   * defaulting to {@code 100} to match the reader page size. The final database state is identical
   * regardless of the chunk size; a value of {@code 1} approximates per-record commit semantics.
   */
  private final int chunkSize;

  /**
   * Creates the daily-transaction-posting job configuration.
   *
   * @param dailyTransactionItemReader the reader bean for the {@code daily_transaction} table; must
   *     not be {@code null}
   * @param transactionPostingProcessor the processor bean delegating to the posting service; must
   *     not be {@code null}
   * @param configuredOutputDir base output directory bound from {@code carddemo.batch.output-dir},
   *     defaulting to {@code java.io.tmpdir}
   * @param chunkSize chunk/commit-interval size bound from {@code
   *     carddemo.batch.posting.chunk-size} (default {@code 100})
   */
  public TransactionPostingJobConfig(
      DailyTransactionItemReader dailyTransactionItemReader,
      TransactionPostingProcessor transactionPostingProcessor,
      @Value("${carddemo.batch.output-dir:#{systemProperties['java.io.tmpdir']}}")
          String configuredOutputDir,
      @Value("${carddemo.batch.posting.chunk-size:100}") int chunkSize) {
    this.dailyTransactionItemReader = dailyTransactionItemReader;
    this.transactionPostingProcessor = transactionPostingProcessor;
    this.configuredOutputDir = configuredOutputDir;
    this.chunkSize = chunkSize;
  }

  /**
   * Resolves the absolute path of an output file, applying the locked output-directory convention
   * shared by the sibling batch configurations.
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
   * Step-scoped reject-routing writer bean. {@code @StepScope} lets the optional {@code outputDir}
   * job parameter be late-bound at step start so the {@code DALYREJS} path can vary per run.
   *
   * @param outputDir the optional {@code outputDir} job-parameter value (may be {@code null})
   * @return a writer that streams rejected results to the {@code DALYREJS} file
   * @throws IOException if the reject file's parent directory cannot be created
   */
  @Bean
  @StepScope
  public RejectRoutingWriter rejectRoutingWriter(
      @Value("#{jobParameters['outputDir']}") String outputDir) throws IOException {
    return new RejectRoutingWriter(resolveOutputPath(outputDir, REJECT_FILE));
  }

  /**
   * Defines the chunk-oriented posting step ({@code STEP15} / {@code CBTRN02C}).
   *
   * <p>The chunk uses the Boot-provided {@link PlatformTransactionManager} so that the per-item
   * posting writes performed inside the service commit on the chunk boundary. The reject-routing
   * writer is registered both as the {@code ItemWriter} (to receive every processed result) and as
   * the {@code StepExecutionListener} (to translate the running reject count into the {@code "4"}
   * exit code). The listener argument is passed without an explicit upcast: {@link
   * RejectRoutingWriter} statically implements {@link StepExecutionListener}, so overload
   * resolution selects the {@code listener(StepExecutionListener)} method, and an explicit cast
   * would be flagged as redundant by the {@code -Xlint:cast}/{@code -Werror} build gate.
   *
   * @param jobRepository the Boot-provided job repository
   * @param transactionManager the Boot-provided transaction manager bounding each chunk
   * @param rejectRoutingWriter the step-scoped reject-routing writer (the {@code @StepScope} proxy)
   * @return the configured chunk-oriented posting step
   */
  @Bean
  public Step transactionPostingStep(
      JobRepository jobRepository,
      PlatformTransactionManager transactionManager,
      RejectRoutingWriter rejectRoutingWriter) {
    return new StepBuilder("transactionPostingStep", jobRepository)
        .<DailyTransaction, TransactionPostingService.PostingResult>chunk(
            chunkSize, transactionManager)
        .reader(dailyTransactionItemReader)
        .processor(transactionPostingProcessor)
        .writer(rejectRoutingWriter)
        .listener(rejectRoutingWriter)
        .build();
  }

  /**
   * Defines the single-step posting job ({@code POSTTRAN}).
   *
   * @param jobRepository the Boot-provided job repository
   * @param transactionPostingStep the chunk-oriented posting step (injected by bean name)
   * @return the configured posting job
   */
  @Bean
  public Job transactionPostingJob(JobRepository jobRepository, Step transactionPostingStep) {
    return new JobBuilder("transactionPostingJob", jobRepository)
        .start(transactionPostingStep)
        .build();
  }

  /**
   * Reject-routing {@link ItemStreamWriter} that forwards only rejected {@link
   * TransactionPostingService.PostingResult} items to the {@link RejectFileWriter} ({@code
   * DALYREJS}) and, acting as a {@link StepExecutionListener}, reports the reject-driven exit code.
   *
   * <p>Posted results are intentionally a no-op here: the database writes that post a transaction
   * already occurred inside the service while the item was processed, within the chunk transaction.
   * Each rejected result carries an exactly 430-character record (the COBOL {@code REJECT-RECORD}
   * layout of {@code PIC X(350)} transaction data, {@code PIC 9(04)} reason code and {@code PIC
   * X(76)} reason description); {@link RejectFileWriter} enforces that length and abends (throws
   * {@code IoStatusException}) on any deviation.
   *
   * <p>The class is {@code @StepScope} so a fresh reject counter and reject-file handle exist per
   * step execution; {@link #write(Chunk)} and {@link #afterStep(StepExecution)} therefore observe
   * the same instance and the count accumulated during writing is visible when the exit status is
   * computed.
   */
  @StepScope
  public static class RejectRoutingWriter
      implements ItemStreamWriter<TransactionPostingService.PostingResult>, StepExecutionListener {

    /** Absolute path of the {@code DALYREJS} reject file for this step execution. */
    private final Path rejectPath;

    /** Lazily opened physical reject-file sink (created in {@link #open(ExecutionContext)}). */
    private RejectFileWriter rejectFileWriter;

    /** Running count of rejected records, mirroring COBOL {@code WS-REJECT-COUNT}. */
    private long rejectCount;

    /**
     * Creates a reject-routing writer targeting the supplied reject-file path.
     *
     * @param rejectPath the absolute path of the {@code DALYREJS} reject file; must not be {@code
     *     null}
     */
    public RejectRoutingWriter(Path rejectPath) {
      this.rejectPath = rejectPath;
    }

    /**
     * Opens the underlying {@link RejectFileWriter} at step start. The remaining {@code ItemStream}
     * lifecycle methods ({@code update}) keep their default no-op behaviour.
     *
     * @param executionContext the step execution context (unused; reject state is held in memory)
     */
    @Override
    public void open(ExecutionContext executionContext) {
      rejectFileWriter = new RejectFileWriter();
      rejectFileWriter.open(rejectPath);
    }

    /**
     * Routes the rejected results of one chunk to the reject file and accumulates the reject count.
     * Posted results require no action because their database writes already occurred in the
     * service.
     *
     * @param chunk the processed results for one chunk
     */
    @Override
    public void write(Chunk<? extends TransactionPostingService.PostingResult> chunk) {
      for (TransactionPostingService.PostingResult result : chunk) {
        if (!result.posted()) {
          rejectFileWriter.accept(result.rejectRecord());
          rejectCount++;
        }
      }
    }

    /**
     * Flushes and closes the reject-file sink at step end, mirroring the COBOL {@code
     * 9300-DALYREJS-CLOSE} paragraph. Safe to call when the file was never opened.
     */
    @Override
    public void close() {
      if (rejectFileWriter != null) {
        rejectFileWriter.close();
      }
    }

    /**
     * Translates the reject count into the step exit status once the step has finished.
     *
     * <p>If the step ended unsuccessfully (an unhandled exception, e.g. an {@code
     * IoStatusException} abend), {@code null} is returned so the existing {@code FAILED}/abend exit
     * status is preserved. Otherwise, a {@value #REJECT_EXIT_CODE} exit code is reported when any
     * record was rejected (COBOL {@code MOVE 4 TO RETURN-CODE}); when there are no rejects the
     * step's existing (default {@code COMPLETED} / "0") exit status is left unchanged.
     *
     * @param stepExecution the completed step execution
     * @return the reject-driven exit status, or {@code null} to preserve the current status
     */
    @Override
    public ExitStatus afterStep(StepExecution stepExecution) {
      if (stepExecution.getStatus().isUnsuccessful()) {
        return null;
      }
      return (rejectCount > 0) ? new ExitStatus(REJECT_EXIT_CODE) : stepExecution.getExitStatus();
    }
  }
}
