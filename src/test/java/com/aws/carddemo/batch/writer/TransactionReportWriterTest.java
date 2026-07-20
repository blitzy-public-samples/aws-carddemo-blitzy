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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.aws.carddemo.batch.processor.TransactionReportProcessor;
import com.aws.carddemo.exception.FileStatusException;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.ExitStatus;
import org.springframework.batch.core.JobParameters;
import org.springframework.batch.core.JobParametersBuilder;
import org.springframework.batch.core.StepExecution;
import org.springframework.batch.item.Chunk;
import org.springframework.batch.item.ExecutionContext;
import org.springframework.batch.test.MetaDataInstanceFactory;

/**
 * Behavioral-parity unit tests for {@link TransactionReportWriter}, the Spring Batch item writer
 * that reproduces the report-generation output of the legacy COBOL batch program
 * {@code legacy/cbl/CBTRN03C.cbl} (report {@code DALYREPT}, copybook
 * {@code legacy/cpy/CVTRA07Y.cpy}).
 *
 * <p>These tests exercise the writer as a self-contained file producer: a fresh
 * {@link TransactionReportWriter} is constructed with a {@link TempDir}-backed output directory
 * (so no Spring context or database is required), driven through its
 * {@link TransactionReportWriter#beforeStep(StepExecution) beforeStep} /
 * {@link TransactionReportWriter#write(Chunk) write} /
 * {@link TransactionReportWriter#afterStep(StepExecution) afterStep} lifecycle, and the produced
 * file is read back as ISO-8859-1 and split on {@code "\n"} for assertion.</p>
 *
 * <p>The golden expectations encoded here were derived directly from the COBOL control flow in
 * {@code CBTRN03C} and the exact byte offsets in {@code CVTRA07Y}: the four-line header block, the
 * 133-byte record width, the {@code -ZZZ,ZZZ,ZZZ.ZZ} / {@code +ZZZ,ZZZ,ZZZ.ZZ} edit masks, the
 * 20-line pagination boundary (16 detail lines on the first page after the 4 header lines), the
 * card-number control break, the grand-total accumulation, and the documented end-of-file
 * stale-amount double-count quirk (recorded in {@code docs/decision-log.md}).</p>
 */
class TransactionReportWriterTest {

    /** Record width of {@code FD-REPTFILE-REC PIC X(133)}. */
    private static final int RECORD_LENGTH = 133;

    /** Zero-based offset of the 15-character edited amount field in detail and totals lines. */
    private static final int AMOUNT_OFFSET = 97;

    /** Report file name used for every test run. */
    private static final String REPORT_FILE = "DALYREPT.txt";

    /** A representative 16-digit card number for single-card scenarios. */
    private static final String CARD_A = "1111111111111111";

    /** A second 16-digit card number used to trigger the control break. */
    private static final String CARD_B = "2222222222222222";

    @TempDir
    Path tempDir;

    // ------------------------------------------------------------------------
    // Case 1 — record width
    // ------------------------------------------------------------------------

    @Test
    @DisplayName("Every emitted line is exactly 133 bytes across all line types")
    void everyLineIsExactly133Bytes() throws Exception {
        // 18 details on card A (forces one in-run page break after 16 lines) then 2 on card B
        // (forces a control break) so the file contains name headers, blank lines, column headers,
        // the dashed rule, detail lines, a page-total line, an account-total line, and the EOF
        // page-total + grand-total lines — every possible record type.
        List<TransactionReportProcessor.ReportLine> items = new ArrayList<>();
        for (int i = 0; i < 18; i++) {
            items.add(line("A" + pad(i), 100L + i, CARD_A, "01", "Purchase", 5, "Groceries", "POS",
                    "1.00"));
        }
        items.add(line("B0000000000001", 200L, CARD_B, "02", "Payment", 6, "Bill", "WEB", "2.00"));
        items.add(line("B0000000000002", 200L, CARD_B, "02", "Payment", 6, "Bill", "WEB", "2.00"));

        List<String> lines = runFull(stepWithDates("2022-07-18", "2022-07-19"), new Chunk<>(items));

        assertThat(lines).isNotEmpty();
        assertThat(lines).allSatisfy(l -> assertThat(l).hasSize(RECORD_LENGTH));
    }

    // ------------------------------------------------------------------------
    // Case 2 — header block
    // ------------------------------------------------------------------------

    @Test
    @DisplayName("First four lines are name header, 133 spaces, column header 1, and 133 dashes")
    void headerBlockIsFourLinesInOrder() throws Exception {
        List<String> lines = runFull(stepWithDates("2022-07-18", "2022-07-19"),
                chunk(line("T0000000000001", 1L, CARD_A, "01", "Purchase", 5, "Groceries", "POS",
                        "1.00")));

        // Line 0: REPORT-NAME-HEADER
        assertThat(lines.get(0)).hasSize(RECORD_LENGTH);
        assertThat(lines.get(0)).startsWith("DALYREPT");
        assertThat(lines.get(0).substring(38, 62)).isEqualTo("Daily Transaction Report");

        // Line 1: WS-BLANK-LINE (133 spaces)
        assertThat(lines.get(1)).isEqualTo(" ".repeat(RECORD_LENGTH));

        // Line 2: TRANSACTION-HEADER-1 (column headers)
        String header1 = lines.get(2);
        assertThat(header1).hasSize(RECORD_LENGTH);
        assertThat(header1.substring(0, 14)).isEqualTo("Transaction ID");
        assertThat(header1.substring(17, 27)).isEqualTo("Account ID");
        assertThat(header1.substring(29, 45)).isEqualTo("Transaction Type");
        assertThat(header1.substring(48, 61)).isEqualTo("Tran Category");
        assertThat(header1.substring(83, 94)).isEqualTo("Tran Source");
        assertThat(header1.substring(98, 112)).isEqualTo("        Amount");

        // Line 3: TRANSACTION-HEADER-2 (133 dashes)
        assertThat(lines.get(3)).isEqualTo("-".repeat(RECORD_LENGTH));
    }

    // ------------------------------------------------------------------------
    // Case 3 — name-header dates at exact offsets
    // ------------------------------------------------------------------------

    @Test
    @DisplayName("Job-parameter start/end dates appear at offsets 91-100 and 105-114 of the name header")
    void nameHeaderCarriesJobParameterDates() throws Exception {
        List<String> lines = runFull(stepWithDates("2022-07-18", "2022-07-19"),
                chunk(line("T0000000000001", 1L, CARD_A, "01", "Purchase", 5, "Groceries", "POS",
                        "1.00")));

        String nameHeader = lines.get(0);
        assertThat(nameHeader.substring(79, 91)).isEqualTo("Date Range: ");
        assertThat(nameHeader.substring(91, 101)).isEqualTo("2022-07-18");
        assertThat(nameHeader.substring(101, 105)).isEqualTo(" to ");
        assertThat(nameHeader.substring(105, 115)).isEqualTo("2022-07-19");
    }

    @Test
    @DisplayName("A missing date job parameter renders as ten spaces in the name header")
    void nameHeaderRendersMissingDatesAsSpaces() throws Exception {
        // No startDate/endDate parameters supplied.
        StepExecution se = MetaDataInstanceFactory.createStepExecution(
                new JobParametersBuilder().toJobParameters());
        List<String> lines = runFull(se,
                chunk(line("T0000000000001", 1L, CARD_A, "01", "Purchase", 5, "Groceries", "POS",
                        "1.00")));

        String nameHeader = lines.get(0);
        assertThat(nameHeader.substring(91, 101)).isEqualTo(" ".repeat(10));
        assertThat(nameHeader.substring(105, 115)).isEqualTo(" ".repeat(10));
    }

    // ------------------------------------------------------------------------
    // Case 4 — detail layout (full byte-exact field placement)
    // ------------------------------------------------------------------------

    @Test
    @DisplayName("Detail line places every field at its exact CVTRA07Y offset")
    void detailLineByteExactLayout() throws Exception {
        List<String> lines = runFull(stepWithDates("2022-07-18", "2022-07-19"),
                chunk(line("TRAN0000000001", 12_345_678_901L, CARD_A, "01", "Purchase", 5,
                        "Groceries", "POS", "504.77")));

        String detail = lines.get(4); // 0-3 headers, 4 = first detail
        assertThat(detail).hasSize(RECORD_LENGTH);
        assertThat(detail.substring(0, 16)).isEqualTo("TRAN0000000001  "); // TRAN-ID X(16)
        assertThat(detail.charAt(16)).isEqualTo(' ');
        assertThat(detail.substring(17, 28)).isEqualTo("12345678901");     // 9(11) zero-padded
        assertThat(detail.charAt(28)).isEqualTo(' ');
        assertThat(detail.substring(29, 31)).isEqualTo("01");              // TYPE-CD X(2)
        assertThat(detail.charAt(31)).isEqualTo('-');                      // literal separator
        assertThat(detail.substring(32, 47)).isEqualTo("Purchase       "); // TYPE-DESC X(15)
        assertThat(detail.charAt(47)).isEqualTo(' ');
        assertThat(detail.substring(48, 52)).isEqualTo("0005");            // 9(4) zero-padded
        assertThat(detail.charAt(52)).isEqualTo('-');                      // literal separator
        assertThat(detail.substring(53, 82)).isEqualTo("Groceries" + " ".repeat(20)); // CAT-DESC X(29)
        assertThat(detail.charAt(82)).isEqualTo(' ');
        assertThat(detail.substring(83, 93)).isEqualTo("POS       ");      // SOURCE X(10)
        assertThat(detail.substring(AMOUNT_OFFSET, AMOUNT_OFFSET + 15)).isEqualTo("         504.77");
    }

    // ------------------------------------------------------------------------
    // Case 5 — detail edit mask vectors
    // ------------------------------------------------------------------------

    @Test
    @DisplayName("Detail amount mask -ZZZ,ZZZ,ZZZ.ZZ reproduces the COBOL golden vectors")
    void detailAmountEditMaskVectors() throws Exception {
        assertThat(detailAmountField("504.77")).isEqualTo("         504.77");
        assertThat(detailAmountField("-919.00")).isEqualTo("-        919.00");
        assertThat(detailAmountField("1234567.89")).isEqualTo("   1,234,567.89");
        assertThat(detailAmountField("0.77")).isEqualTo("            .77");
        assertThat(detailAmountField("0.00")).isEqualTo(" ".repeat(15));
    }

    // ------------------------------------------------------------------------
    // Case 6 — totals edit mask vectors (asserted via account-total lines,
    //          which are written during write() and therefore not affected by
    //          the EOF stale-amount quirk)
    // ------------------------------------------------------------------------

    @Test
    @DisplayName("Totals amount mask +ZZZ,ZZZ,ZZZ.ZZ reproduces the COBOL golden vectors")
    void totalsAmountEditMaskVectors() throws Exception {
        assertThat(accountTotalField("504.77")).isEqualTo("+        504.77");
        assertThat(accountTotalField("-919.00")).isEqualTo("-        919.00");
        assertThat(accountTotalField("0.00")).isEqualTo(" ".repeat(15));
    }

    // ------------------------------------------------------------------------
    // Case 7 — pagination
    // ------------------------------------------------------------------------

    @Test
    @DisplayName("Page breaks at line-counter multiples of 20; first page carries 16 detail lines")
    void paginationFirstPageHas16Details() throws Exception {
        List<TransactionReportProcessor.ReportLine> items = new ArrayList<>();
        for (int i = 0; i < 40; i++) {
            items.add(line("T" + pad(i), 100L + i, CARD_A, "01", "Purchase", 5, "Groc", "POS",
                    "1.00"));
        }
        List<String> lines = runFull(stepWithDates("2022-07-18", "2022-07-19"), new Chunk<>(items));

        int firstPageTotal = indexOfFirst(lines, "Page Total");
        // 4 header lines (indices 0-3) + 16 details (indices 4-19) -> page total at index 20.
        assertThat(firstPageTotal).isEqualTo(20);

        long detailsBeforeFirstPageTotal = lines.subList(0, firstPageTotal).stream()
                .filter(TransactionReportWriterTest::isDetail)
                .count();
        assertThat(detailsBeforeFirstPageTotal).isEqualTo(16L);

        // The page total is immediately followed by the dashed rule and then a fresh header block.
        assertThat(lines.get(firstPageTotal + 1)).isEqualTo("-".repeat(RECORD_LENGTH));
        assertThat(lines.get(firstPageTotal + 2)).startsWith("DALYREPT");
        assertThat(lines.get(firstPageTotal + 3)).isEqualTo(" ".repeat(RECORD_LENGTH));
        assertThat(lines.get(firstPageTotal + 4)).startsWith("Transaction ID");
        assertThat(lines.get(firstPageTotal + 5)).isEqualTo("-".repeat(RECORD_LENGTH));
    }

    // ------------------------------------------------------------------------
    // Case 8 — control break
    // ------------------------------------------------------------------------

    @Test
    @DisplayName("A card-number change emits the previous account's total before the next detail")
    void controlBreakEmitsAccountTotal() throws Exception {
        List<String> lines = runFull(stepWithDates("2022-07-18", "2022-07-19"), chunk(
                line("A0000000000001", 111L, CARD_A, "01", "Purchase", 5, "Groc", "POS", "10.00"),
                line("A0000000000002", 111L, CARD_A, "01", "Purchase", 5, "Groc", "POS", "20.00"),
                line("B0000000000001", 222L, CARD_B, "02", "Payment", 6, "Bill", "WEB", "30.00")));

        // Indices: 0-3 headers, 4 detail A1, 5 detail A2, 6 account total (A), 7 dashes, 8 detail B1
        assertThat(lines.get(4).substring(0, 14)).isEqualTo("A0000000000001");
        assertThat(lines.get(5).substring(0, 14)).isEqualTo("A0000000000002");
        assertThat(lines.get(6)).startsWith("Account Total");
        assertThat(lines.get(6).substring(AMOUNT_OFFSET, AMOUNT_OFFSET + 15)).isEqualTo("+         30.00");
        assertThat(lines.get(7)).isEqualTo("-".repeat(RECORD_LENGTH));
        assertThat(lines.get(8).substring(0, 14)).isEqualTo("B0000000000001");
    }

    // ------------------------------------------------------------------------
    // Case 9 — grand-total accumulation
    // ------------------------------------------------------------------------

    @Test
    @DisplayName("Grand total equals the sum of the page totals (including the EOF double-count)")
    void grandTotalEqualsSumOfPageTotals() throws Exception {
        // 20 details of 1.00 on one card -> one in-run page total of 16.00, then the EOF page total
        // carries the remaining 4 details plus the stale double-count of the last 1.00 = 5.00;
        // grand total = 16.00 + 5.00 = 21.00.
        List<TransactionReportProcessor.ReportLine> items = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            items.add(line("T" + pad(i), 100L + i, CARD_A, "01", "P", 5, "G", "POS", "1.00"));
        }
        List<String> lines = runFull(stepWithDates("2022-07-18", "2022-07-19"), new Chunk<>(items));

        List<String> pageTotals = amountsOf(lines, "Page Total");
        assertThat(pageTotals).containsExactly("+         16.00", "+          5.00");

        String grandTotal = amountsOf(lines, "Grand Total").get(0);
        assertThat(grandTotal).isEqualTo("+         21.00");
    }

    // ------------------------------------------------------------------------
    // Case 10 — EOF stale-amount quirk
    // ------------------------------------------------------------------------

    @Test
    @DisplayName("EOF double-counts the last amount into page/grand totals and emits no final account total")
    void endOfFileStaleAmountQuirk() throws Exception {
        List<String> lines = runFull(stepWithDates("2022-07-18", "2022-07-19"), chunk(
                line("T0000000000001", 111L, CARD_A, "01", "P", 5, "G", "POS", "100.00"),
                line("T0000000000002", 111L, CARD_A, "01", "P", 5, "G", "POS", "200.00"),
                line("T0000000000003", 111L, CARD_A, "01", "P", 5, "G", "POS", "300.00")));

        // True arithmetic sum is 600.00; the legacy quirk adds the last amount (300.00) a second
        // time at end-of-file, so the final page total and the grand total are both 900.00.
        String pageTotal = amountsOf(lines, "Page Total").get(0);
        String grandTotal = amountsOf(lines, "Grand Total").get(0);
        assertThat(pageTotal).isEqualTo("+        900.00");
        assertThat(grandTotal).isEqualTo("+        900.00");

        // The end-of-file branch does not emit a final Account Total line.
        assertThat(lines).noneMatch(l -> l.startsWith("Account Total"));
    }

    // ------------------------------------------------------------------------
    // Case 11 — empty input
    // ------------------------------------------------------------------------

    @Test
    @DisplayName("Empty input still emits a page total and a grand total, no headers, and completes")
    void emptyInputEmitsTotalsOnly() throws Exception {
        TransactionReportWriter writer = newWriter();
        StepExecution se = stepWithDates("2022-07-18", "2022-07-19");
        writer.beforeStep(se);
        writer.write(new Chunk<>(List.<TransactionReportProcessor.ReportLine>of()));
        se.setStatus(BatchStatus.COMPLETED);
        ExitStatus exitStatus = writer.afterStep(se);

        List<String> lines = readLines();
        assertThat(lines).hasSize(3);
        assertThat(lines.get(0)).startsWith("Page Total");
        assertThat(lines.get(0).substring(AMOUNT_OFFSET, AMOUNT_OFFSET + 15)).isEqualTo(" ".repeat(15));
        assertThat(lines.get(1)).isEqualTo("-".repeat(RECORD_LENGTH));
        assertThat(lines.get(2)).startsWith("Grand Total");
        assertThat(lines.get(2).substring(AMOUNT_OFFSET, AMOUNT_OFFSET + 15)).isEqualTo(" ".repeat(15));
        assertThat(lines).noneMatch(l -> l.startsWith("DALYREPT"));
        assertThat(exitStatus).isEqualTo(ExitStatus.COMPLETED);
    }

    // ------------------------------------------------------------------------
    // Case 12 — return-code mapping
    // ------------------------------------------------------------------------

    @Test
    @DisplayName("A clean run returns ExitStatus.COMPLETED (RC0)")
    void normalRunReturnsCompleted() throws Exception {
        TransactionReportWriter writer = newWriter();
        StepExecution se = stepWithDates("2022-07-18", "2022-07-19");
        writer.beforeStep(se);
        writer.write(chunk(line("T0000000000001", 1L, CARD_A, "01", "P", 5, "G", "POS", "1.00")));
        se.setStatus(BatchStatus.COMPLETED);
        ExitStatus exitStatus = writer.afterStep(se);

        assertThat(exitStatus).isEqualTo(ExitStatus.COMPLETED);
    }

    @Test
    @DisplayName("A FAILED step skips EOF finalization and returns ExitStatus.FAILED (RC8)")
    void failedStepSkipsFinalizationAndReturnsFailed() throws Exception {
        TransactionReportWriter writer = newWriter();
        StepExecution se = stepWithDates("2022-07-18", "2022-07-19");
        writer.beforeStep(se);
        writer.write(chunk(line("T0000000000001", 1L, CARD_A, "01", "P", 5, "G", "POS", "1.00")));
        se.setStatus(BatchStatus.FAILED);
        ExitStatus exitStatus = writer.afterStep(se);

        assertThat(exitStatus).isEqualTo(ExitStatus.FAILED);
        // Finalization is skipped and the staging output is discarded, so no final-named report is
        // published (F-P5-C): a consumer never observes a report that lacks its page/grand totals.
        assertThat(Files.exists(tempDir.resolve(REPORT_FILE))).isFalse();
    }

    @Test
    @DisplayName("An open failure in beforeStep fails fast with a FileStatusException (RC8)")
    void openFailureThrowsFileStatusException() throws Exception {
        // Make the target directory impossible to create: a regular file sits where a parent
        // directory would need to be.
        Path blockingFile = tempDir.resolve("not-a-directory");
        Files.writeString(blockingFile, "x", StandardCharsets.ISO_8859_1);
        Path unusableDir = blockingFile.resolve("sub");

        TransactionReportWriter writer = new TransactionReportWriter(unusableDir.toString(), REPORT_FILE);
        StepExecution se = stepWithDates("2022-07-18", "2022-07-19");

        assertThatThrownBy(() -> writer.beforeStep(se))
                .isInstanceOf(FileStatusException.class);
    }

    // ------------------------------------------------------------------------
    // Case 13 — observability counters promoted to the execution context
    // ------------------------------------------------------------------------

    @Test
    @DisplayName("Record, detail, and grand-total counters are promoted to the execution context")
    void countersPromotedToExecutionContext() throws Exception {
        TransactionReportWriter writer = newWriter();
        StepExecution se = stepWithDates("2022-07-18", "2022-07-19");
        writer.beforeStep(se);
        writer.write(chunk(
                line("T0000000000001", 111L, CARD_A, "01", "P", 5, "G", "POS", "100.00"),
                line("T0000000000002", 111L, CARD_A, "01", "P", 5, "G", "POS", "200.00"),
                line("T0000000000003", 111L, CARD_A, "01", "P", 5, "G", "POS", "300.00")));
        se.setStatus(BatchStatus.COMPLETED);
        writer.afterStep(se);

        ExecutionContext ctx = se.getExecutionContext();
        // 4 header lines + 3 details + (EOF) page total + dashed rule + grand total = 10 records.
        assertThat(ctx.getLong("report.recordCount")).isEqualTo(10L);
        assertThat(ctx.getLong("report.detailCount")).isEqualTo(3L);
        // Grand total includes the EOF stale double-count of the last amount (300.00).
        assertThat(ctx.getString("report.grandTotal")).isEqualTo("900.00");

        // The promoted record count must equal the number of lines actually written.
        assertThat(readLines()).hasSize((int) ctx.getLong("report.recordCount"));
    }

    // ------------------------------------------------------------------------
    // Case 14 — encoding (ISO-8859-1, one char == one byte)
    // ------------------------------------------------------------------------

    @Test
    @DisplayName("A Latin-1 character round-trips to a single byte at the correct offset")
    void latin1CharacterRoundTripsToSingleByte() throws Exception {
        // 'é' (U+00E9) encodes to a single byte 0xE9 in ISO-8859-1. Place it in the source field
        // (offset 83, X(10)); "caf\u00e9" -> the accented char lands at detail offset 86.
        List<String> lines = runFull(stepWithDates("2022-07-18", "2022-07-19"),
                chunk(line("T0000000000001", 1L, CARD_A, "01", "P", 5, "G", "caf\u00e9", "1.00")));

        String detail = lines.get(4);
        assertThat(detail).hasSize(RECORD_LENGTH);
        assertThat(detail.charAt(86)).isEqualTo('\u00e9');

        // Verify the on-disk byte is exactly one 0xE9 byte (records are 133 bytes + a 1-byte
        // newline, so detail line 4 starts at byte 4 * 134).
        byte[] bytes = Files.readAllBytes(tempDir.resolve(REPORT_FILE));
        int detailStart = 4 * (RECORD_LENGTH + 1);
        assertThat(bytes[detailStart + 86]).isEqualTo((byte) 0xE9);
    }

    // ------------------------------------------------------------------------
    // Case 15 — F-P5-C atomic publish / restart safety (staging file + atomic
    // rename means a restart or failure never truncates or leaves a partial
    // final-named report).
    // ------------------------------------------------------------------------

    /** The hidden staging file name the writer publishes from ({@code .DALYREPT.txt.part}). */
    private static final String STAGING_FILE = "." + REPORT_FILE + ".part";

    @Test
    @DisplayName("F-P5-C: a clean run atomically publishes the final report and leaves no staging file")
    void cleanRunPublishesFinalReportAndLeavesNoStagingFile() throws Exception {
        List<String> lines = runFull(stepWithDates("2022-07-18", "2022-07-19"),
                chunk(line("T0000000000001", 1L, CARD_A, "01", "P", 5, "G", "POS", "1.00")));

        // The final report exists and every record is a full 133-byte line ...
        assertThat(lines).isNotEmpty();
        assertThat(lines).allSatisfy(l -> assertThat(l).hasSize(RECORD_LENGTH));
        assertThat(Files.exists(tempDir.resolve(REPORT_FILE))).isTrue();
        // ... and the hidden staging file was consumed by the atomic publish (nothing left behind).
        assertThat(Files.exists(tempDir.resolve(STAGING_FILE))).isFalse();
    }

    @Test
    @DisplayName("F-P5-C: a failing step publishes no final report and leaves no partial or staging file")
    void failingStepLeavesNoFinalReportAndNoStagingFile() throws Exception {
        TransactionReportWriter writer = newWriter();
        StepExecution se = stepWithDates("2022-07-18", "2022-07-19");
        writer.beforeStep(se);
        writer.write(chunk(line("T0000000000001", 1L, CARD_A, "01", "P", 5, "G", "POS", "1.00")));

        // Simulate an upstream failure (reader/processor error) surfacing as a FAILED step status
        // before the writer's afterStep runs.
        se.setStatus(BatchStatus.FAILED);
        ExitStatus exit = writer.afterStep(se);

        // The step maps to the I/O-error return code (RC8) ...
        assertThat(exit).isEqualTo(ExitStatus.FAILED);
        // ... no final-named report was ever published (a consumer sees nothing, not a partial file)
        assertThat(Files.exists(tempDir.resolve(REPORT_FILE))).isFalse();
        // ... and the staging file was discarded.
        assertThat(Files.exists(tempDir.resolve(STAGING_FILE))).isFalse();
    }

    @Test
    @DisplayName("F-P5-E: a step still STARTED at afterStep (status-persist race) publishes no report and no staging file")
    void startedStepLeavesNoFinalReportAndNoStagingFile() throws Exception {
        TransactionReportWriter writer = newWriter();
        StepExecution se = stepWithDates("2022-07-18", "2022-07-19");
        writer.beforeStep(se);
        writer.write(chunk(line("T0000000000001", 1L, CARD_A, "01", "P", 5, "G", "POS", "1.00")));

        // Reproduce the exact production race: the datasource dropped mid-step, so the framework ran
        // afterStep while the persisted status was still STARTED (it could not itself transition the
        // row to FAILED against the dead database) and no failure exception had been recorded. The
        // superseded fail-open gate (status != FAILED) treated this as success and published a report
        // that was missing its EOF page/grand totals; the fail-closed gate (status == COMPLETED) must
        // publish nothing.
        se.setStatus(BatchStatus.STARTED);
        assertThat(se.getFailureExceptions())
                .as("precondition: the framework has not yet recorded a failure exception")
                .isEmpty();
        ExitStatus exit = writer.afterStep(se);

        assertThat(exit).isEqualTo(ExitStatus.FAILED);
        assertThat(Files.exists(tempDir.resolve(REPORT_FILE))).isFalse();
        assertThat(Files.exists(tempDir.resolve(STAGING_FILE))).isFalse();
    }

    @Test
    @DisplayName("F-P5-C: a re-run atomically replaces a pre-existing final report with the complete rebuilt report")
    void rerunAtomicallyReplacesPreexistingFinalReport() throws Exception {
        // A stale final-named report from a prior run/attempt. In the pre-fix design a restart
        // truncated this file and resumed mid-stream, silently losing the earlier rows. The fixed
        // writer instead rebuilds the whole report in a staging file (the reader re-reads the entire
        // input on restart) and atomically replaces the final file.
        Path finalReport = tempDir.resolve(REPORT_FILE);
        Files.writeString(finalReport, "STALE-GARBAGE-FROM-A-PRIOR-RUN", StandardCharsets.ISO_8859_1);

        List<String> lines = runFull(stepWithDates("2022-07-18", "2022-07-19"),
                chunk(line("T0000000000001", 1L, CARD_A, "01", "P", 5, "G", "POS", "100.00")));

        // The final file now holds ONLY the complete rebuilt report: every record is a full 133-byte
        // line and none of the stale bytes survive (proving a full replace, not a truncating merge).
        assertThat(lines).isNotEmpty();
        assertThat(lines).allSatisfy(l -> assertThat(l).hasSize(RECORD_LENGTH));
        String content = Files.readString(finalReport, StandardCharsets.ISO_8859_1);
        assertThat(content).doesNotContain("STALE-GARBAGE-FROM-A-PRIOR-RUN");
        assertThat(Files.exists(tempDir.resolve(STAGING_FILE))).isFalse();
    }

    // ------------------------------------------------------------------------
    // Case 16 — F-P12 non-Latin-1 characters are deterministically replaced so
    // the ISO-8859-1 writer never aborts on an unmappable character.
    // ------------------------------------------------------------------------

    @Test
    @DisplayName("F-P12: non-Latin-1 characters in a report field become '?' and the report publishes cleanly (RC0)")
    void nonLatin1CharactersInReportFieldAreReplacedAndReportPublishes() throws Exception {
        TransactionReportWriter writer = newWriter();
        StepExecution se = stepWithDates("2022-07-18", "2022-07-19");
        writer.beforeStep(se);
        // The source field is X(10) at offset 83. It carries an omega (U+03A9, one code unit) and a
        // grinning-face emoji (U+1F600, a surrogate pair = two code units); every one of those code
        // units is above the single-byte charset, so each is replaced 1:1 with '?': "A?" + "??" + "B".
        writer.write(chunk(line("T0000000000001", 1L, CARD_A, "01", "P", 5, "G",
                "A\u03A9\uD83D\uDE00B", "1.00")));
        se.setStatus(BatchStatus.COMPLETED);
        ExitStatus exit = writer.afterStep(se);

        // The writer never aborted on an unmappable character: the step completed with RC0 and the
        // report was atomically published with no staging residue.
        assertThat(exit).isEqualTo(ExitStatus.COMPLETED);
        assertThat(Files.exists(tempDir.resolve(REPORT_FILE))).isTrue();
        assertThat(Files.exists(tempDir.resolve(STAGING_FILE))).isFalse();

        List<String> lines = readLines();
        String detail = lines.get(4);
        assertThat(detail).hasSize(RECORD_LENGTH);
        assertThat(detail.substring(83, 88)).isEqualTo("A???B");

        // Every byte on disk is a single ISO-8859-1 byte (proves no encoding exception was possible).
        byte[] bytes = Files.readAllBytes(tempDir.resolve(REPORT_FILE));
        assertThat(StandardCharsets.ISO_8859_1.newEncoder()
                .canEncode(new String(bytes, StandardCharsets.ISO_8859_1))).isTrue();
    }

    // ------------------------------------------------------------------------
    // Test helpers
    // ------------------------------------------------------------------------

    private TransactionReportWriter newWriter() {
        return new TransactionReportWriter(tempDir.toString(), REPORT_FILE);
    }

    private StepExecution stepWithDates(String startDate, String endDate) {
        JobParameters params = new JobParametersBuilder()
                .addString("startDate", startDate)
                .addString("endDate", endDate)
                .toJobParameters();
        return MetaDataInstanceFactory.createStepExecution(params);
    }

    /**
     * Runs the full clean beforeStep/write/afterStep lifecycle and returns the file's 133-byte lines.
     *
     * <p>The step is transitioned to {@link BatchStatus#COMPLETED} before {@code afterStep} to mirror
     * a real successful launch: Spring Batch's {@code AbstractStep} upgrades the persisted status to
     * {@code COMPLETED} before invoking {@code afterStep} on the success path. The fail-closed publish
     * gate added for F-P5-E ({@link BatchFilePublishDecision#isCleanCompletion}) atomically publishes
     * the report only for a {@code COMPLETED} step, so a clean-lifecycle fixture must present that
     * status.</p>
     */
    private List<String> runFull(StepExecution stepExecution,
            Chunk<TransactionReportProcessor.ReportLine> chunk) throws Exception {
        TransactionReportWriter writer = newWriter();
        writer.beforeStep(stepExecution);
        writer.write(chunk);
        stepExecution.setStatus(BatchStatus.COMPLETED);
        writer.afterStep(stepExecution);
        return readLines();
    }

    /** Reads the report file back as ISO-8859-1 and returns its records (no trailing empty). */
    private List<String> readLines() throws IOException {
        byte[] bytes = Files.readAllBytes(tempDir.resolve(REPORT_FILE));
        String content = new String(bytes, StandardCharsets.ISO_8859_1);
        List<String> records = new ArrayList<>();
        for (String part : content.split("\n", -1)) {
            if (!part.isEmpty()) {
                records.add(part);
            }
        }
        return records;
    }

    /** Renders a single detail line for {@code amount} and returns its 15-char amount field. */
    private String detailAmountField(String amount) throws Exception {
        List<String> lines = runFull(stepWithDates("2022-07-18", "2022-07-19"),
                chunk(line("T0000000000001", 1L, CARD_A, "01", "D", 1, "C", "S", amount)));
        return lines.get(4).substring(AMOUNT_OFFSET, AMOUNT_OFFSET + 15);
    }

    /**
     * Renders an account-total line for a single detail of {@code amount} on card A (followed by a
     * card B detail to trigger the control break) and returns that line's 15-char amount field.
     * The account total is written during {@code write()}, so it is unaffected by the EOF quirk.
     */
    private String accountTotalField(String amount) throws Exception {
        List<String> lines = runFull(stepWithDates("2022-07-18", "2022-07-19"), chunk(
                line("A0000000000001", 1L, CARD_A, "01", "D", 1, "C", "S", amount),
                line("B0000000000001", 2L, CARD_B, "01", "D", 1, "C", "S", "1.00")));
        return lines.get(5).substring(AMOUNT_OFFSET, AMOUNT_OFFSET + 15);
    }

    private static TransactionReportProcessor.ReportLine line(String tranId, Long accountId,
            String cardNum, String typeCd, String typeDesc, Integer catCd, String catDesc,
            String source, String amount) {
        return new TransactionReportProcessor.ReportLine(tranId, accountId, cardNum, typeCd,
                typeDesc, catCd, catDesc, source, new BigDecimal(amount));
    }

    private static Chunk<TransactionReportProcessor.ReportLine> chunk(
            TransactionReportProcessor.ReportLine... items) {
        return new Chunk<>(List.of(items));
    }

    /** A 14-character, zero-padded transaction-id suffix so ids stay within the X(16) field. */
    private static String pad(int i) {
        return String.format("%014d", i);
    }

    private static boolean isDetail(String line) {
        return !(line.equals("-".repeat(RECORD_LENGTH))
                || line.equals(" ".repeat(RECORD_LENGTH))
                || line.startsWith("DALYREPT")
                || line.startsWith("Transaction ID")
                || line.startsWith("Page Total")
                || line.startsWith("Account Total")
                || line.startsWith("Grand Total"));
    }

    private static int indexOfFirst(List<String> lines, String prefix) {
        for (int i = 0; i < lines.size(); i++) {
            if (lines.get(i).startsWith(prefix)) {
                return i;
            }
        }
        return -1;
    }

    private static List<String> amountsOf(List<String> lines, String prefix) {
        List<String> amounts = new ArrayList<>();
        for (String line : lines) {
            if (line.startsWith(prefix)) {
                amounts.add(line.substring(AMOUNT_OFFSET, AMOUNT_OFFSET + 15));
            }
        }
        return amounts;
    }
}
