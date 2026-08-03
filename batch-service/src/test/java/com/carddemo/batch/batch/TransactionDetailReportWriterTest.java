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
import org.junit.jupiter.api.io.TempDir;
import org.springframework.batch.infrastructure.item.Chunk;
import org.springframework.batch.infrastructure.item.ExecutionContext;

/**
 * Unit tests for :class:`TransactionDetailReportWriter`.
 *
 * :purpose: Verify the ``CBTRN03C`` transaction-detail report the ``TRANREPT``
 *     job produces: the 133-character line width of every line, the
 *     ``1110-WRITE-HEADERS`` block, the ``1120-WRITE-DETAIL`` column layout, the
 *     20-line page break with its ``Page Total`` line and 86 dot fillers, the
 *     card control break that emits ``Account Total`` with 84 dots, and the
 *     closing ``Grand Total`` with 86 dots. The edited amount carries a leading
 *     sign position (blank for a detail amount, ``+``/``-`` for a total) and full
 *     zero suppression.
 * :output: JUnit 5 / AssertJ assertions over a real report file in a JUnit
 *     temporary directory; no Spring context and no database.
 */
class TransactionDetailReportWriterTest {

    /** :purpose: Report line width (``REPORT-LINE`` PIC X(133)). */
    private static final int LINE_WIDTH = 133;

    @TempDir
    private Path tempDir;

    private BatchOutputPathResolver pathResolver;

    private Path reportFile;

    @BeforeEach
    void createResolver() throws IOException {
        Path outputRoot = Files.createDirectories(tempDir.resolve("out"));
        Path inputRoot = Files.createDirectories(tempDir.resolve("in"));
        pathResolver = new BatchOutputPathResolver(outputRoot.toString(), inputRoot.toString());
        reportFile = outputRoot.resolve("dalyrept.txt");
    }

    /**
     * Builds a report row.
     *
     * :param tranId: the transaction id.
     * :param cardNum: the card number driving the control break.
     * :param amount: the transaction amount.
     * :output: the report row.
     */
    private static TransactionReportItem row(String tranId, String cardNum, String amount) {
        return new TransactionReportItem(tranId, "00000000001", "01", "Purchase", 1,
                "Regular Sales Draft", "POS TERM", new BigDecimal(amount), cardNum);
    }

    /**
     * Runs the writer over the given rows and returns the report lines.
     *
     * :param rows: the report rows, in reader order.
     * :output: the produced report lines.
     */
    private List<String> render(List<TransactionReportItem> rows) throws Exception {
        TransactionDetailReportWriter writer = new TransactionDetailReportWriter(
                "2022-06-01", "2022-06-30", "dalyrept.txt", pathResolver);
        writer.open(new ExecutionContext());
        try {
            Chunk<TransactionReportItem> chunk = new Chunk<>();
            for (TransactionReportItem item : rows) {
                chunk.add(item);
            }
            writer.write(chunk);
            writer.update(new ExecutionContext());
        } finally {
            writer.close();
        }
        return Files.readAllLines(reportFile, StandardCharsets.UTF_8);
    }

    @Test
    @DisplayName("every report line is exactly 133 characters wide")
    void everyLineIs133CharactersWide() throws Exception {
        List<TransactionReportItem> rows = new ArrayList<>();
        for (int i = 1; i <= 25; i++) {
            rows.add(row(String.format("%016d", i), "4859452612877065", "10.00"));
        }

        assertThat(render(rows)).isNotEmpty()
                .allSatisfy(line -> assertThat(line).hasSize(LINE_WIDTH));
    }

    @Test
    @DisplayName("the header block carries the report name, the date range and the column labels")
    void headerBlockCarriesTheReportNameAndDateRange() throws Exception {
        List<String> lines = render(List.of(row("0000000000000001", "4859452612877065", "10.00")));

        assertThat(lines.get(0)).startsWith("DALYREPT");
        assertThat(lines.get(0)).contains("Daily Transaction Report");
        assertThat(lines.get(0)).contains("Date Range: 2022-06-01 to 2022-06-30");
        assertThat(lines.get(1).trim()).isEmpty();
        assertThat(lines.get(2)).startsWith("Transaction ID");
        assertThat(lines.get(2)).contains("Account ID");
        assertThat(lines.get(2)).contains("Transaction Type");
        assertThat(lines.get(2)).contains("Tran Category");
        assertThat(lines.get(2)).contains("Tran Source");
        assertThat(lines.get(2)).contains("Amount");
        assertThat(lines.get(3)).isEqualTo("-".repeat(LINE_WIDTH));
    }

    @Test
    @DisplayName("the detail line places every field at its CVTRA07Y column with the filler literals")
    void detailLinePlacesEveryFieldAtItsColumn() throws Exception {
        List<String> lines = render(List.of(row("0000000000683580", "4859452612877065", "504.77")));

        String detail = lines.get(4);
        assertThat(detail.substring(0, 16)).isEqualTo("0000000000683580");
        assertThat(detail.charAt(16)).isEqualTo(' ');
        assertThat(detail.substring(17, 28)).isEqualTo("00000000001");
        assertThat(detail.charAt(28)).isEqualTo(' ');
        assertThat(detail.substring(29, 31)).isEqualTo("01");
        assertThat(detail.charAt(31)).isEqualTo('-');
        assertThat(detail.substring(32, 47)).isEqualTo("Purchase       ");
        assertThat(detail.charAt(47)).isEqualTo(' ');
        assertThat(detail.substring(48, 52)).isEqualTo("0001");
        assertThat(detail.charAt(52)).isEqualTo('-');
        assertThat(detail.substring(53, 82)).startsWith("Regular Sales Draft");
        assertThat(detail.substring(83, 93)).isEqualTo("POS TERM  ");
        // Detail amounts carry a BLANK sign position and a comma-grouped magnitude.
        assertThat(detail.substring(97, 112)).isEqualTo("         504.77");
    }

    @Test
    @DisplayName("a page break at twenty lines emits the Page Total with 86 dots and a separator")
    void pageBreakEmitsThePageTotal() throws Exception {
        List<TransactionReportItem> rows = new ArrayList<>();
        for (int i = 1; i <= 20; i++) {
            rows.add(row(String.format("%016d", i), "4859452612877065", "10.00"));
        }

        List<String> lines = render(rows);
        List<String> pageTotals = lines.stream().filter(line -> line.startsWith("Page Total")).toList();

        assertThat(pageTotals).isNotEmpty();
        String pageTotal = pageTotals.get(0);
        assertThat(pageTotal.substring(0, 11)).isEqualTo("Page Total ");
        assertThat(pageTotal.substring(11, 97)).isEqualTo(".".repeat(86));
        // A total amount carries an explicit '+' sign position.
        // The 15-character edited amount is one sign position followed by the
        // right-justified 14-character magnitude (PIC +9(9).99 style edit).
        assertThat(pageTotal.charAt(97)).isEqualTo('+');
        assertThat(pageTotal.substring(97, 112)).isEqualTo("+        160.00");
    }

    @Test
    @DisplayName("a new card emits the Account Total with 84 dots before the next detail line")
    void cardControlBreakEmitsTheAccountTotal() throws Exception {
        List<String> lines = render(List.of(
                row("0000000000000001", "4859452612877065", "10.00"),
                row("0000000000000002", "4859452612877065", "20.00"),
                row("0000000000000003", "0927987108636232", "30.00")));

        List<String> accountTotals =
                lines.stream().filter(line -> line.startsWith("Account Total")).toList();

        // One control-break total for the first card, one closing total for the second.
        assertThat(accountTotals).hasSize(2);
        assertThat(accountTotals.get(0).substring(0, 13)).isEqualTo("Account Total");
        assertThat(accountTotals.get(0).substring(13, 97)).isEqualTo(".".repeat(84));
        assertThat(accountTotals.get(0).substring(97, 112)).isEqualTo("+         30.00");
        assertThat(accountTotals.get(1).substring(97, 112)).isEqualTo("+         30.00");
    }

    @Test
    @DisplayName("closing the writer emits the Account, Page and Grand totals in that order")
    void closeEmitsTheClosingTotals() throws Exception {
        List<String> lines = render(List.of(
                row("0000000000000001", "4859452612877065", "10.00"),
                row("0000000000000002", "0927987108636232", "5.50")));

        // Each closing total is followed by its own separator rule, then the grand total.
        List<String> tail = lines.subList(lines.size() - 5, lines.size());
        assertThat(tail.get(0)).startsWith("Account Total");
        assertThat(tail.get(1)).isEqualTo("-".repeat(LINE_WIDTH));
        assertThat(tail.get(2)).startsWith("Page Total");
        assertThat(tail.get(3)).isEqualTo("-".repeat(LINE_WIDTH));
        assertThat(tail.get(4)).startsWith("Grand Total");
        assertThat(tail.get(4).substring(11, 97)).isEqualTo(".".repeat(86));
        assertThat(tail.get(4).substring(97, 112)).isEqualTo("+         15.50");
    }

    @Test
    @DisplayName("a negative amount renders with a '-' sign in both the detail and the totals")
    void negativeAmountsRenderWithAMinusSign() throws Exception {
        List<String> lines = render(List.of(row("0000000000000001", "4859452612877065", "-42.75")));

        assertThat(lines.get(4).substring(97, 112)).isEqualTo("-         42.75");
        assertThat(lines.stream().filter(line -> line.startsWith("Grand Total")).findFirst())
                .get()
                .satisfies(line -> assertThat(((String) line).substring(97, 112))
                        .isEqualTo("-         42.75"));
    }

    @Test
    @DisplayName("a zero amount is fully zero-suppressed to blanks")
    void zeroAmountIsFullyZeroSuppressed() throws Exception {
        List<String> lines = render(List.of(row("0000000000000001", "4859452612877065", "0.00")));

        assertThat(lines.get(4).substring(97, 112)).isBlank();
    }

    @Test
    @DisplayName("a large amount is comma-grouped inside the fourteen-character magnitude field")
    void largeAmountIsCommaGrouped() throws Exception {
        List<String> lines = render(List.of(row("0000000000000001", "4859452612877065", "1234567.89")));

        assertThat(lines.get(4).substring(97, 112)).isEqualTo("   1,234,567.89");
    }

    @Test
    @DisplayName("closing without writing any row produces an empty report rather than stray totals")
    void closingWithoutRowsProducesAnEmptyReport() throws Exception {
        TransactionDetailReportWriter writer = new TransactionDetailReportWriter(
                "2022-06-01", "2022-06-30", "dalyrept.txt", pathResolver);
        writer.open(new ExecutionContext());
        writer.close();

        assertThat(Files.readAllLines(reportFile, StandardCharsets.UTF_8)).isEmpty();
    }
}
