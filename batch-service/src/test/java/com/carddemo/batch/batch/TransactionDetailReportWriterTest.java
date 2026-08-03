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
 *     job produces: the 133-byte line width of every line, the
 *     ``1110-WRITE-HEADERS`` block, the ``1120-WRITE-DETAIL`` column layout, the
 *     20-line page break with its ``Page Total`` line and 86 dot fillers, the
 *     card control break that emits ``Account Total`` with 84 dots, and the
 *     end-of-file block — ``ADD TRAN-AMT TO WS-PAGE-TOTAL WS-ACCOUNT-TOTAL``
 *     against the record area, then ``1110-WRITE-PAGE-TOTALS`` and
 *     ``1110-WRITE-GRAND-TOTALS``, with no closing ``Account Total`` line. The
 *     edited amount carries a leading sign position (blank for a detail amount,
 *     ``+``/``-`` for a total) and full zero suppression.
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
     * Builds a report row whose category description carries non-Latin-1 text.
     *
     * :param tranId: the transaction id.
     * :param cardNum: the card number driving the control break.
     * :param categoryDescription: the category description to render.
     * :output: the report row.
     */
    private static TransactionReportItem rowWithCategoryDescription(
            String tranId, String cardNum, String categoryDescription) {
        return new TransactionReportItem(tranId, "00000000001", "01", "Purchase", 1,
                categoryDescription, "POS TERM", new BigDecimal("10.00"), cardNum);
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
        // The report is a byte contract written in ISO-8859-1, so it is read back
        // in the same encoding.
        return Files.readAllLines(reportFile, StandardCharsets.ISO_8859_1);
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

        // One page break inside the report plus the end-of-file page total.
        assertThat(pageTotals).hasSize(2);
        String pageTotal = pageTotals.get(0);
        assertThat(pageTotal.substring(0, 11)).isEqualTo("Page Total ");
        assertThat(pageTotal.substring(11, 97)).isEqualTo(".".repeat(86));
        // A total amount carries an explicit '+' sign position.
        // The 15-character edited amount is one sign position followed by the
        // right-justified 14-character magnitude (PIC +9(9).99 style edit).
        assertThat(pageTotal.charAt(97)).isEqualTo('+');
        // Page 1 holds four header lines and sixteen details: 16 x 10.00.
        assertThat(pageTotal.substring(97, 112)).isEqualTo("+        160.00");
        // The closing page carries its four remaining details plus the last
        // record once more (the end-of-file ADD against the record area): 5 x 10.00.
        assertThat(pageTotals.get(1).substring(97, 112)).isEqualTo("+         50.00");
        assertThat(lines.get(lines.size() - 1).substring(97, 112)).isEqualTo("+        210.00");
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

        // Exactly one control-break total, for the first card. The end-of-file
        // branch of CBTRN03C performs no 1120-WRITE-ACCOUNT-TOTALS, so the final
        // card group is never closed with an Account Total line.
        assertThat(accountTotals).hasSize(1);
        assertThat(accountTotals.get(0).substring(0, 13)).isEqualTo("Account Total");
        assertThat(accountTotals.get(0).substring(13, 97)).isEqualTo(".".repeat(84));
        assertThat(accountTotals.get(0).substring(97, 112)).isEqualTo("+         30.00");
    }

    @Test
    @DisplayName("the end-of-file block is the Page Total, its separator and the Grand Total only")
    void closeEmitsThePageAndGrandTotalsOnly() throws Exception {
        List<String> lines = render(List.of(
                row("0000000000000001", "4859452612877065", "10.00"),
                row("0000000000000002", "0927987108636232", "5.50")));

        // 1110-WRITE-PAGE-TOTALS writes the page total and the HEADER-2 rule, then
        // 1110-WRITE-GRAND-TOTALS writes the grand total; nothing follows.
        List<String> tail = lines.subList(lines.size() - 3, lines.size());
        assertThat(tail.get(0)).startsWith("Page Total");
        assertThat(tail.get(1)).isEqualTo("-".repeat(LINE_WIDTH));
        assertThat(tail.get(2)).startsWith("Grand Total");
        assertThat(tail.get(2).substring(11, 97)).isEqualTo(".".repeat(86));
        // 10.00 + 5.50 plus the last record counted once more by the end-of-file
        // ADD TRAN-AMT (the record area still holds 5.50).
        assertThat(tail.get(0).substring(97, 112)).isEqualTo("+         21.00");
        assertThat(tail.get(2).substring(97, 112)).isEqualTo("+         21.00");
        // The last line of the report is the grand total, not an account total.
        assertThat(lines.get(lines.size() - 1)).startsWith("Grand Total");
    }

    @Test
    @DisplayName("the end-of-file ADD TRAN-AMT counts the final record in the totals a second time")
    void endOfFileCountsTheFinalRecordTwice() throws Exception {
        List<String> lines = render(List.of(
                row("0000000000000001", "4859452612877065", "100.00"),
                row("0000000000000002", "4859452612877065", "25.00")));

        // Detail lines carry 100.00 and 25.00; the closing totals carry
        // 100.00 + 25.00 + 25.00 = 150.00 because CBTRN03C's end-of-file branch
        // adds the record area's TRAN-AMT — still the last record — once more.
        assertThat(lines.stream().filter(line -> line.startsWith("Page Total")).toList())
                .singleElement()
                .satisfies(line -> assertThat(line.substring(97, 112)).isEqualTo("+        150.00"));
        assertThat(lines.stream().filter(line -> line.startsWith("Grand Total")).toList())
                .singleElement()
                .satisfies(line -> assertThat(line.substring(97, 112)).isEqualTo("+        150.00"));
    }

    @Test
    @DisplayName("a negative amount renders with a '-' sign in both the detail and the totals")
    void negativeAmountsRenderWithAMinusSign() throws Exception {
        List<String> lines = render(List.of(row("0000000000000001", "4859452612877065", "-42.75")));

        assertThat(lines.get(4).substring(97, 112)).isEqualTo("-         42.75");
        // The single row is counted twice in the closing totals by the end-of-file
        // ADD TRAN-AMT, so the grand total is -85.50 and keeps the '-' sign.
        assertThat(lines.stream().filter(line -> line.startsWith("Grand Total")).findFirst())
                .get()
                .satisfies(line -> assertThat(((String) line).substring(97, 112))
                        .isEqualTo("-         85.50"));
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
    @DisplayName("an empty selection window still emits the Page Total and Grand Total block")
    void emptySelectionWindowStillEmitsTheClosingBlock() throws Exception {
        TransactionDetailReportWriter writer = new TransactionDetailReportWriter(
                "2022-06-01", "2022-06-30", "dalyrept.txt", pathResolver);
        writer.open(new ExecutionContext());
        writer.close();

        // CBTRN03C reaches its end-of-file branch even when no record fell in the
        // window: the page total, its separator rule and the grand total are still
        // written, so the report is never a zero-byte file.
        List<String> lines = Files.readAllLines(reportFile, StandardCharsets.ISO_8859_1);
        assertThat(lines).hasSize(3);
        assertThat(lines).allSatisfy(line -> assertThat(line).hasSize(LINE_WIDTH));
        assertThat(lines.get(0)).startsWith("Page Total");
        assertThat(lines.get(1)).isEqualTo("-".repeat(LINE_WIDTH));
        assertThat(lines.get(2)).startsWith("Grand Total");
        // Zero totals are fully zero-suppressed behind the '+' sign position.
        assertThat(lines.get(0).substring(97, 112)).isEqualTo("+" + " ".repeat(14));
        assertThat(lines.get(2).substring(97, 112)).isEqualTo("+" + " ".repeat(14));
    }

    @Test
    @DisplayName("text outside Latin-1 keeps every line at exactly 133 bytes with fields in place")
    void textOutsideLatin1KeepsEveryLineAt133Bytes() throws Exception {
        // A Latin-1 category description (two bytes each under UTF-8) and one
        // carrying a supplementary code point (a surrogate pair, one ISO-8859-1
        // byte) are the two directions in which a byte contract can break.
        render(List.of(
                rowWithCategoryDescription("0000000000000001", "4859452612877065", "Café Müller"),
                rowWithCategoryDescription("0000000000000002", "4859452612877065", "Sushi \uD83C\uDF63 bar")));

        byte[] content = Files.readAllBytes(reportFile);
        assertThat(content.length % (LINE_WIDTH + 1)).isZero();
        int lineStart = 0;
        for (int i = 0; i < content.length; i++) {
            if (content[i] == '\n') {
                assertThat(i - lineStart).as("byte width of line starting at %d", lineStart)
                        .isEqualTo(LINE_WIDTH);
                lineStart = i + 1;
            }
        }

        List<String> lines = Files.readAllLines(reportFile, StandardCharsets.ISO_8859_1);
        // The accented characters survive as single bytes; the supplementary code
        // point becomes one substitute character, so both amounts stay at their
        // CVTRA07Y column and the totals are unaffected.
        assertThat(lines.get(4).substring(53, 82)).isEqualTo(padded("Café Müller", 29));
        assertThat(lines.get(5).substring(53, 82)).isEqualTo(padded("Sushi ? bar", 29));
        assertThat(lines.get(4).substring(97, 112)).isEqualTo("          10.00");
        assertThat(lines.get(5).substring(97, 112)).isEqualTo("          10.00");
    }

    /**
     * Right-pads a value with spaces to a field width.
     *
     * :param value: the field value.
     * :param width: the field width in characters.
     * :output: the padded value.
     */
    private static String padded(String value, int width) {
        return value + " ".repeat(width - value.length());
    }
}
