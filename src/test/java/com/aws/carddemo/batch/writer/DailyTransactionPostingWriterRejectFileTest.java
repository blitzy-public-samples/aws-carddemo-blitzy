/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License").
 * You may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.aws.carddemo.batch.writer;

import com.aws.carddemo.batch.processor.DailyTransactionPostingProcessor.PostingResult;
import com.aws.carddemo.domain.DailyTransaction;
import com.aws.carddemo.exception.RejectCode;
import com.aws.carddemo.repository.AccountRepository;
import com.aws.carddemo.repository.TransactionCategoryBalanceRepository;
import com.aws.carddemo.repository.TransactionRepository;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.ExitStatus;
import org.springframework.batch.core.JobExecution;
import org.springframework.batch.core.StepExecution;
import org.springframework.batch.item.Chunk;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * Focused robustness tests for the {@code DALYREJS} reject-file production of
 * {@link DailyTransactionPostingWriter}, covering QA findings F9 (decision log D35 — substituting
 * encoder + atomic publish) and F10 (decision log D36 — embedded record-delimiter sanitization) at
 * runtime, plus the byte-stability of clean output.
 *
 * <p>The reject-writing path uses only {@link com.aws.carddemo.common.util.FixedWidthCodec} and the
 * reject file stream (it performs no repository access), so the writer is driven directly with mocked
 * repositories and a JUnit {@link TempDir}; the lifecycle callbacks {@code beforeStep} / {@code write}
 * / {@code afterStep} are invoked in the same order Spring Batch would.</p>
 */
class DailyTransactionPostingWriterRejectFileTest {

    private static final String REJECT_FILE = "DALYREJS.dat";
    private static final int RECORD_LENGTH = 430;
    private static final int FRAMED_LENGTH = RECORD_LENGTH + 1; // + trailing LF
    private static final byte LF = 0x0A;
    private static final byte QUESTION_MARK = 0x3F;

    private final AccountRepository accountRepository = mock(AccountRepository.class);
    private final TransactionRepository transactionRepository = mock(TransactionRepository.class);
    private final TransactionCategoryBalanceRepository tranCatBalanceRepository =
            mock(TransactionCategoryBalanceRepository.class);

    private DailyTransactionPostingWriter newWriter(Path dir) {
        return new DailyTransactionPostingWriter(
                accountRepository, transactionRepository, tranCatBalanceRepository,
                dir.toString(), REJECT_FILE);
    }

    private static StepExecution newStepExecution() {
        return new StepExecution("dailyTransactionPostingStep", new JobExecution(1L));
    }

    /** Builds a reject {@link PostingResult} (code 100) carrying a daily-transaction with the given desc. */
    private static PostingResult rejectWith(String desc, String cardNum) {
        DailyTransaction src = new DailyTransaction(
                "0000000000000001", "01", 1, "POS TERM", desc,
                new BigDecimal("10.00"), 800000000L, "Test Merchant", "Test City", "75010",
                cardNum, "2022-06-10 19:27:53.000000", "");
        return new PostingResult(src, RejectCode.INVALID_CARD_NUMBER, null, null, null, null, null);
    }

    private ExitStatus runReject(DailyTransactionPostingWriter writer, StepExecution step,
                                 PostingResult result) throws Exception {
        writer.beforeStep(step);
        writer.write(new Chunk<>(result));
        return writer.afterStep(step);
    }

    // ------------------------------------------------------------------------
    // F9 / D35 - un-encodable character is substituted, output is not lost
    // ------------------------------------------------------------------------

    @Test
    void unencodableCharacterIsSubstitutedAndRejectFileIsStillProduced(@TempDir Path dir)
            throws Exception {
        DailyTransactionPostingWriter writer = newWriter(dir);
        // U+2615 (coffee) is not representable in ISO-8859-1 -> must be substituted, not fatal.
        PostingResult reject = rejectWith("Order \u2615 latte", "9999999999999999");

        ExitStatus exit = runReject(writer, newStepExecution(), reject);

        Path finalFile = dir.resolve(REJECT_FILE);
        assertThat(finalFile).exists();
        byte[] bytes = Files.readAllBytes(finalFile);
        // Exactly one 430-byte record + one framing LF; the un-encodable char became one '?' byte.
        assertThat(bytes).hasSize(FRAMED_LENGTH);
        assertThat(bytes[FRAMED_LENGTH - 1]).isEqualTo(LF);
        assertThat(new String(bytes, StandardCharsets.ISO_8859_1)).doesNotContain("\u2615");
        assertThat(bytes).contains(QUESTION_MARK);
        // Graceful degradation: the run completes with RC 4 (rejects present), not RC 8.
        assertThat(exit.getExitCode()).isEqualTo("COMPLETED_WITH_REJECTS");
        // No temp file left behind after the atomic publish.
        assertThat(dir.resolve(REJECT_FILE + ".tmp")).doesNotExist();
        verifyNoInteractions(accountRepository, transactionRepository, tranCatBalanceRepository);
    }

    @Test
    void unencodableCharacterDoesNotThrow(@TempDir Path dir) {
        DailyTransactionPostingWriter writer = newWriter(dir);
        PostingResult reject = rejectWith("Caf\u00e9 \u2615\u2615", "9999999999999999");
        assertThatCode(() -> runReject(writer, newStepExecution(), reject)).doesNotThrowAnyException();
    }

    // ------------------------------------------------------------------------
    // F10 / D36 - embedded LF in a field is sanitized; only the framing LF remains
    // ------------------------------------------------------------------------

    @Test
    void embeddedNewlineInFieldIsSanitizedSoOnlyTheFramingDelimiterRemains(@TempDir Path dir)
            throws Exception {
        DailyTransactionPostingWriter writer = newWriter(dir);
        PostingResult reject = rejectWith("BAD\nDESC", "9999999999999999");

        runReject(writer, newStepExecution(), reject);

        byte[] bytes = Files.readAllBytes(dir.resolve(REJECT_FILE));
        assertThat(bytes).hasSize(FRAMED_LENGTH);
        // The record body (offsets 0..429) must contain NO line feed; the sole LF is the framing byte.
        long lfCount = 0;
        for (byte b : bytes) {
            if (b == LF) {
                lfCount++;
            }
        }
        assertThat(lfCount).isEqualTo(1L);
        assertThat(bytes[FRAMED_LENGTH - 1]).isEqualTo(LF);
    }

    // ------------------------------------------------------------------------
    // Clean data - deterministic 431-byte framing, byte-stable across reruns
    // ------------------------------------------------------------------------

    @Test
    void cleanRejectRecordHasCanonicalFramingAndReasonFields(@TempDir Path dir) throws Exception {
        DailyTransactionPostingWriter writer = newWriter(dir);
        PostingResult reject = rejectWith("Purchase at Abshire-Lowe", "4859452612877065");

        ExitStatus exit = runReject(writer, newStepExecution(), reject);

        byte[] bytes = Files.readAllBytes(dir.resolve(REJECT_FILE));
        assertThat(bytes).hasSize(FRAMED_LENGTH);
        assertThat(bytes[FRAMED_LENGTH - 1]).isEqualTo(LF);
        String record = new String(bytes, 0, RECORD_LENGTH, StandardCharsets.ISO_8859_1);
        // Reason code occupies offset 350..353 (PIC 9(4)) and description begins at offset 354.
        assertThat(record.substring(350, 354)).isEqualTo("0100");
        assertThat(record.substring(354)).startsWith("INVALID CARD NUMBER FOUND");
        assertThat(exit.getExitCode()).isEqualTo("COMPLETED_WITH_REJECTS");
    }

    @Test
    void cleanOutputIsByteStableAcrossReruns(@TempDir Path dir) throws Exception {
        PostingResult reject = rejectWith("Purchase at Abshire-Lowe", "4859452612877065");

        DailyTransactionPostingWriter writer1 = newWriter(dir);
        runReject(writer1, newStepExecution(), reject);
        byte[] first = Files.readAllBytes(dir.resolve(REJECT_FILE));

        DailyTransactionPostingWriter writer2 = newWriter(dir);
        runReject(writer2, newStepExecution(), reject);
        byte[] second = Files.readAllBytes(dir.resolve(REJECT_FILE));

        assertThat(second).isEqualTo(first);
    }

    // ------------------------------------------------------------------------
    // F9 / D35 - atomic publish: a failed run never destroys a prior good file
    // ------------------------------------------------------------------------

    @Test
    void priorGoodFileIsPreservedWhenLaterRunFails(@TempDir Path dir) throws Exception {
        // Run 1 (clean): publishes a good reject file.
        DailyTransactionPostingWriter goodWriter = newWriter(dir);
        runReject(goodWriter, newStepExecution(), rejectWith("GOOD RECORD", "4859452612877065"));
        Path finalFile = dir.resolve(REJECT_FILE);
        byte[] goodBytes = Files.readAllBytes(finalFile);

        // Run 2: open (beforeStep) must NOT touch the final file (writes go to the temp sibling).
        DailyTransactionPostingWriter failing = newWriter(dir);
        StepExecution step2 = newStepExecution();
        failing.beforeStep(step2);
        assertThat(Files.readAllBytes(finalFile))
                .as("beforeStep must not truncate or replace the prior good file")
                .isEqualTo(goodBytes);

        // Write a DIFFERENT record, then mark the step FAILED and finalize.
        failing.write(new Chunk<>(rejectWith("REPLACEMENT THAT MUST NOT LAND", "9999999999999999")));
        step2.setStatus(BatchStatus.FAILED);
        ExitStatus exit = failing.afterStep(step2);

        // The prior good file is intact, the temp was discarded, and the run reports RC 8.
        assertThat(Files.readAllBytes(finalFile))
                .as("a failed run must not destroy or replace the prior good reject file")
                .isEqualTo(goodBytes);
        assertThat(dir.resolve(REJECT_FILE + ".tmp")).doesNotExist();
        assertThat(exit.getExitCode()).isEqualTo(ExitStatus.FAILED.getExitCode());
    }
}
