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
package com.carddemo.batch.batch;

import com.carddemo.common.batch.BatchOutputPathResolver;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.carddemo.batch.repository.TransactionRepository;
import com.carddemo.common.domain.Transaction;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.job.JobExecution;
import org.springframework.batch.core.job.JobInstance;
import org.springframework.batch.core.job.parameters.JobParameters;
import org.springframework.batch.core.job.parameters.JobParametersBuilder;
import org.springframework.batch.core.scope.context.ChunkContext;
import org.springframework.batch.core.scope.context.StepContext;
import org.springframework.batch.core.step.StepContribution;
import org.springframework.batch.core.step.StepExecution;
import org.springframework.batch.infrastructure.repeat.RepeatStatus;
import org.springframework.data.domain.Pageable;

/**
 * Unit tests for :class:`CombineTransactionsTasklet`.
 *
 * :purpose: Verify the ``COMBTRAN`` combine step: every posted transaction is
 *     written to the resolved output file as its 350-byte ``CVTRA05Y`` record in
 *     ascending ``TRAN-ID`` order, the repository is paged until a short page ends
 *     the loop, the tasklet reports ``FINISHED``, and an output path that escapes
 *     the configured root is rejected before the file is opened.
 * :output: JUnit 5 / AssertJ / Mockito assertions over a real file in a JUnit
 *     temporary directory; the repository is mocked and no database is involved.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class CombineTransactionsTaskletTest {

    /** :purpose: Page size the tasklet reads with (``PAGE_SIZE``). */
    private static final int PAGE_SIZE = 500;

    @TempDir
    private Path tempDir;

    @Mock
    private TransactionRepository transactionRepository;

    private BatchOutputPathResolver pathResolver;

    private Path outputRoot;

    private CombineTransactionsTasklet tasklet;

    /**
     * Configured combined-print name the tasklet falls back to when the run carries no
     * ``outputFile`` job parameter, mirroring
     * ``carddemo.batch.combined-transaction-file``.
     */
    private static final String DEFAULT_OUTPUT_FILE = "combined-transactions.txt";

    @BeforeEach
    void createTasklet() throws IOException {
        outputRoot = Files.createDirectories(tempDir.resolve("out"));
        Path inputRoot = Files.createDirectories(tempDir.resolve("in"));
        pathResolver = new BatchOutputPathResolver(outputRoot.toString(), inputRoot.toString());
        tasklet = new CombineTransactionsTasklet(transactionRepository, pathResolver,
                DEFAULT_OUTPUT_FILE);
    }

    /**
     * Builds a chunk context carrying the given ``outputFile`` job parameter.
     *
     * :param outputFile: the requested output file, or ``null`` to omit it.
     * :output: the chunk context handed to the tasklet.
     */
    private static ChunkContext chunkContext(String outputFile) {
        JobParametersBuilder builder = new JobParametersBuilder();
        if (outputFile != null) {
            builder.addString("outputFile", outputFile);
        }
        JobParameters parameters = builder.toJobParameters();
        JobExecution jobExecution =
                new JobExecution(1L, new JobInstance(1L, "combineTransactionsJob"), parameters);
        StepExecution stepExecution = new StepExecution(1L, "combineTransactionsStep", jobExecution);
        stepExecution.setStatus(BatchStatus.STARTED);
        return new ChunkContext(new StepContext(stepExecution));
    }

    /**
     * Builds a transaction fixture.
     *
     * :param tranId: the 16-character id.
     * :param amount: the amount.
     * :output: the transaction.
     */
    private static Transaction transaction(String tranId, String amount) {
        Transaction transaction = new Transaction();
        transaction.setTranId(tranId);
        transaction.setTranTypeCd("01");
        transaction.setTranCatCd(1);
        transaction.setTranSource("POS TERM");
        transaction.setTranDesc("Purchase at Abshire-Lowe");
        transaction.setTranAmt(new BigDecimal(amount));
        transaction.setTranMerchantId(123456789L);
        transaction.setTranMerchantName("Mercado Central");
        transaction.setTranMerchantCity("Springfield");
        transaction.setTranMerchantZip("22770");
        transaction.setTranCardNum("4859452612877065");
        transaction.setTranOrigTs("2022-06-10-19.27.53.000000");
        transaction.setTranProcTs("2022-06-11-01.00.00.000000");
        return transaction;
    }

    @Test
    @DisplayName("writes one 350-byte CVTRA05Y record per transaction, in TRAN-ID order, and reports FINISHED")
    void writesEveryTransactionAsAFixedWidthRecord() throws Exception {
        when(transactionRepository.findAllByOrderByTranIdAsc(any(Pageable.class)))
                .thenReturn(List.of(
                        transaction("0000000000000001", "10.00"),
                        transaction("0000000000000002", "-20.50")));

        RepeatStatus status = tasklet.execute(new StepContribution(
                new StepExecution(1L, "combineTransactionsStep",
                        new JobExecution(1L, new JobInstance(1L, "combineTransactionsJob"),
                                new JobParameters()))),
                chunkContext("combined.txt"));

        assertThat(status).isEqualTo(RepeatStatus.FINISHED);
        List<String> lines = Files.readAllLines(outputRoot.resolve("combined.txt"),
                StandardCharsets.ISO_8859_1);
        assertThat(lines).hasSize(2).allSatisfy(line -> assertThat(line).hasSize(350));
        assertThat(lines.get(0)).startsWith("0000000000000001");
        assertThat(lines.get(1)).startsWith("0000000000000002");
        // The amount is the CVTRA05Y S9(09)V99 zoned field with the sign overpunch.
        assertThat(lines.get(0).substring(132, 143)).isEqualTo("0000000100{");
        assertThat(lines.get(1).substring(132, 143)).isEqualTo("0000000205}");
    }

    @Test
    @DisplayName("pages the repository until a short page ends the loop")
    void pagesTheRepositoryUntilAShortPage() throws Exception {
        List<Transaction> fullPage = new ArrayList<>();
        for (int i = 1; i <= PAGE_SIZE; i++) {
            fullPage.add(transaction(String.format("%016d", i), "1.00"));
        }
        when(transactionRepository.findAllByOrderByTranIdAsc(any(Pageable.class)))
                .thenReturn(fullPage)
                .thenReturn(List.of(transaction(String.format("%016d", PAGE_SIZE + 1), "2.00")));

        tasklet.execute(null, chunkContext("paged.txt"));

        assertThat(Files.readAllLines(outputRoot.resolve("paged.txt"), StandardCharsets.ISO_8859_1))
                .hasSize(PAGE_SIZE + 1);
    }

    @Test
    @DisplayName("an empty transaction master produces an empty output file")
    void emptyMasterProducesAnEmptyFile() throws Exception {
        when(transactionRepository.findAllByOrderByTranIdAsc(any(Pageable.class)))
                .thenReturn(List.of());

        assertThat(tasklet.execute(null, chunkContext("empty.txt"))).isEqualTo(RepeatStatus.FINISHED);
        assertThat(Files.readAllLines(outputRoot.resolve("empty.txt"))).isEmpty();
    }

    @Test
    @DisplayName("the requested page size is the configured 500")
    void requestedPageSizeIsFiveHundred() throws Exception {
        when(transactionRepository.findAllByOrderByTranIdAsc(any(Pageable.class)))
                .thenReturn(List.of());

        tasklet.execute(null, chunkContext("size.txt"));

        org.mockito.ArgumentCaptor<Pageable> pageable =
                org.mockito.ArgumentCaptor.forClass(Pageable.class);
        org.mockito.Mockito.verify(transactionRepository)
                .findAllByOrderByTranIdAsc(pageable.capture());
        assertThat(pageable.getValue().getPageSize()).isEqualTo(PAGE_SIZE);
        assertThat(pageable.getValue().getPageNumber()).isZero();
    }

    @Test
    @DisplayName("a traversal outside the output root is rejected before the repository is read")
    void traversalIsRejectedBeforeReading() {
        assertThatThrownBy(() -> tasklet.execute(null, chunkContext("../escaped.txt")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("escapes the allowed directory");
        verifyNoInteractions(transactionRepository);
    }

    @Test
    @DisplayName("a missing outputFile job parameter falls back to the configured default name")
    void missingOutputFileParameterUsesConfiguredDefault() throws Exception {
        org.mockito.Mockito.when(transactionRepository.findAllByOrderByTranIdAsc(any(Pageable.class)))
                .thenReturn(List.of());

        assertThat(tasklet.execute(null, chunkContext(null))).isEqualTo(RepeatStatus.FINISHED);

        // The COMBTRAN combined print must be produced by EVERY launch path, including the
        // --spring.batch.job.name command-line runner the Kubernetes CronJob uses, which
        // supplies no job parameters at all.
        assertThat(outputRoot.resolve(DEFAULT_OUTPUT_FILE)).exists();
    }
}
