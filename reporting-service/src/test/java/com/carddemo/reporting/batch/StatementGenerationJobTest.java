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
package com.carddemo.reporting.batch;

import com.carddemo.reporting.mapper.StatementMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.batch.infrastructure.item.Chunk;
import org.springframework.batch.infrastructure.item.ExecutionContext;
import org.springframework.core.io.FileSystemResource;

import java.io.File;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * :purpose: Pure JUnit 5 unit test for {@link StatementGenerationJob} that
 *   verifies the COBOL-to-Java byte-for-byte rendering fidelity of the batch
 *   statement engine re-platformed from ``CBSTM03A``/``CBSTM03B`` with the
 *   ``COSTM01`` record layout: the numeric-edit pictures (``9(9).99-`` and
 *   ``Z(9).99-``, width 13), the 80-column plain-text statement, the 100-column
 *   HTML statement, the frozen FILLER labels/banners/color literals, the
 *   per-card emission order, and scale-2 truncate-toward-zero ``BigDecimal``
 *   precision.
 * :output: Assertions confirming byte-exact edited numeric fields, exact line
 *   lengths (text 80, HTML 100), the frozen literals, the emission ordering for
 *   two/one/zero transactions, and drift-free monetary values. Binds to the
 *   system-under-test via ACCESS TIER 1: the render methods and numeric-edit
 *   helpers are package-private ``static`` members of {@link StatementGenerationJob}
 *   and are invoked directly (no Spring context, no mocking framework, no
 *   persistence layer, no Docker, no reflection).
 */
public class StatementGenerationJobTest {

    /**
     * :purpose: Obviously-synthetic 16-character card number literal used for any
     *   card field so that no real PAN is ever present in the test.
     */
    private static final String SYNTHETIC_CARD = "0000000000000000";

    // ------------------------------------------------------------------
    // Fixture factory helpers
    // ------------------------------------------------------------------

    /**
     * :purpose: Build a statement transaction fixture populated with only the
     *   fields the renderers consume (transaction id, description, amount).
     * :param tranId: the 16-character transaction identifier.
     * :param description: the transaction description text.
     * :param amount: the transaction amount (string-constructed ``BigDecimal``).
     * :returns: a populated {@link StatementMapper.StatementTransaction}.
     */
    private static StatementMapper.StatementTransaction newTransaction(String tranId,
                                                                       String description,
                                                                       BigDecimal amount) {
        StatementMapper.StatementTransaction transaction = new StatementMapper.StatementTransaction();
        transaction.setTranId(tranId);
        transaction.setDescription(description);
        transaction.setAmount(amount);
        return transaction;
    }

    /**
     * :purpose: Build the standard statement model fixture with the given
     *   transaction list and total, holding all header/basic-detail fields fixed.
     * :param transactions: the per-card transaction list.
     * :param totalAmount: the statement total (string-constructed ``BigDecimal``).
     * :returns: a populated {@link StatementMapper.StatementModel}.
     */
    private static StatementMapper.StatementModel newModel(
            List<StatementMapper.StatementTransaction> transactions, BigDecimal totalAmount) {
        StatementMapper.StatementModel model = new StatementMapper.StatementModel();
        model.setCustomerName("JOHN A DOE");
        model.setAddressLine1("123 MAIN ST");
        model.setAddressLine2("SUITE 100");
        model.setAddressLine3("SEATTLE WA USA 99999");
        model.setAccountId(123L);
        model.setCardNumber(SYNTHETIC_CARD);
        model.setCurrentBalance(new BigDecimal("1234.56"));
        model.setFicoScore(750);
        model.setTransactions(transactions);
        model.setTotalAmount(totalAmount);
        return model;
    }

    /**
     * :purpose: Build the canonical two-transaction model used by the emission,
     *   line-length and HTML scenarios.
     * :returns: a {@link StatementMapper.StatementModel} with two transactions and
     *   a total of ``300.75``.
     */
    private static StatementMapper.StatementModel standardModel() {
        List<StatementMapper.StatementTransaction> transactions = new ArrayList<>();
        transactions.add(newTransaction("0000000000000001", "PURCHASE ONE", new BigDecimal("100.50")));
        transactions.add(newTransaction("0000000000000002", "PURCHASE TWO", new BigDecimal("200.25")));
        return newModel(transactions, new BigDecimal("300.75"));
    }

    // ------------------------------------------------------------------
    // 5.A - Numeric-edit helpers, byte-exact (width 13)
    // ------------------------------------------------------------------

    /**
     * :purpose: Verify the ``9(9).99-`` zero-filled edit of a positive amount
     *   yields nine zero-filled integer digits and a trailing space.
     */
    @Test
    void formatSignedZeroFilledRendersPositiveWithTrailingSpace() {
        String edited = StatementGenerationJob.formatSignedZeroFilled(new BigDecimal("1234.56"));
        assertThat(edited).isEqualTo("000001234.56 ");
        assertThat(edited).hasSize(13);
    }

    /**
     * :purpose: Verify the ``9(9).99-`` zero-filled edit of a negative amount
     *   yields a trailing minus sign.
     */
    @Test
    void formatSignedZeroFilledRendersNegativeWithTrailingMinus() {
        String edited = StatementGenerationJob.formatSignedZeroFilled(new BigDecimal("-1234.56"));
        assertThat(edited).isEqualTo("000001234.56-");
        assertThat(edited).hasSize(13);
    }

    /**
     * :purpose: Verify the ``9(9).99-`` zero-filled edit of zero yields an
     *   all-zero integer part and a trailing space.
     */
    @Test
    void formatSignedZeroFilledRendersZero() {
        String edited = StatementGenerationJob.formatSignedZeroFilled(new BigDecimal("0.00"));
        assertThat(edited).isEqualTo("000000000.00 ");
        assertThat(edited).hasSize(13);
    }

    /**
     * :purpose: Verify the ``Z(9).99-`` suppressed edit of a positive amount
     *   suppresses leading zeros to spaces and keeps a trailing space.
     */
    @Test
    void formatSuppressedRendersPositiveWithLeadingSpaces() {
        String edited = StatementGenerationJob.formatSuppressed(new BigDecimal("1234.56"));
        assertThat(edited).isEqualTo("     1234.56 ");
        assertThat(edited).hasSize(13);
    }

    /**
     * :purpose: Verify the ``Z(9).99-`` suppressed edit of zero suppresses the
     *   whole integer part to spaces while never suppressing the fraction digits.
     */
    @Test
    void formatSuppressedRendersZeroAsSpaces() {
        String edited = StatementGenerationJob.formatSuppressed(new BigDecimal("0.00"));
        assertThat(edited).isEqualTo("         .00 ");
        assertThat(edited).hasSize(13);
    }

    /**
     * :purpose: Verify the ``Z(9).99-`` suppressed edit of a negative amount
     *   yields a trailing minus sign.
     */
    @Test
    void formatSuppressedRendersNegativeWithTrailingMinus() {
        String edited = StatementGenerationJob.formatSuppressed(new BigDecimal("-1234.56"));
        assertThat(edited).isEqualTo("     1234.56-");
        assertThat(edited).hasSize(13);
    }

    /**
     * :purpose: Verify both numeric-edit pictures normalize to scale 2 by truncating
     *   toward zero, so a third fraction digit of five is dropped rather than
     *   rounding the second digit up (COBOL ``MOVE`` without ``ROUNDED``).
     */
    @Test
    void numericEditTruncatesToScale2() {
        assertThat(StatementGenerationJob.formatSignedZeroFilled(new BigDecimal("100.005")))
                .isEqualTo("000000100.00 ");
        assertThat(StatementGenerationJob.formatSuppressed(new BigDecimal("100.005")))
                .isEqualTo("      100.00 ");
    }

    /**
     * :purpose: Verify ``pad`` right-pads a short value with spaces and truncates
     *   a long value on the right to the requested width.
     */
    @Test
    void padRightPadsAndTruncates() {
        assertThat(StatementGenerationJob.pad("AB", 5)).isEqualTo("AB   ");
        assertThat(StatementGenerationJob.pad("AB", 5)).hasSize(5);
        assertThat(StatementGenerationJob.pad("ABCDEF", 3)).isEqualTo("ABC");
    }

    /**
     * :purpose: Verify ``trimAtDoubleSpace`` returns the substring up to the first
     *   double space, the whole string when none is present, and preserves single
     *   spaces.
     */
    @Test
    void trimAtDoubleSpaceStopsAtFirstDoubleSpace() {
        assertThat(StatementGenerationJob.trimAtDoubleSpace("ABC  DEF")).isEqualTo("ABC");
        assertThat(StatementGenerationJob.trimAtDoubleSpace("NOSPACE")).isEqualTo("NOSPACE");
        assertThat(StatementGenerationJob.trimAtDoubleSpace("A B C")).isEqualTo("A B C");
    }

    // ------------------------------------------------------------------
    // 5.B - TEXT statement rendering (every line exactly 80 chars)
    // ------------------------------------------------------------------

    /**
     * :purpose: Verify every rendered text line of the statement is exactly the
     *   frozen 80-column width.
     */
    @Test
    void renderTextEveryLineIsEightyChars() {
        List<String> lines = StatementGenerationJob.renderText(standardModel());
        assertThat(lines).allSatisfy(line -> assertThat(line).hasSize(80));
    }

    /**
     * :purpose: Verify the exact emission order, size and byte layout of the text
     *   statement for a two-transaction model (19 + N = 21 lines), covering the
     *   banners, dashed rules, frozen labels, the balance/total numeric edits and
     *   the per-transaction rows.
     */
    @Test
    void renderTextEmissionOrderForTwoTransactions() {
        List<String> lines = StatementGenerationJob.renderText(standardModel());
        String dashes = "-".repeat(80);

        assertThat(lines).hasSize(21);
        assertThat(lines.get(0)).isEqualTo("*".repeat(31) + "START OF STATEMENT" + "*".repeat(31));
        assertThat(lines.get(5)).isEqualTo(dashes);
        assertThat(lines.get(7)).isEqualTo(dashes);
        assertThat(lines.get(6)).isEqualTo(" ".repeat(33) + "Basic Details " + " ".repeat(33));
        assertThat(lines.get(8)).startsWith("Account ID         :");
        assertThat(lines.get(8).substring(0, 20)).isEqualTo("Account ID         :");
        assertThat(lines.get(9)).startsWith("Current Balance    :");
        assertThat(lines.get(9).substring(20, 33)).isEqualTo("000001234.56 ");
        assertThat(lines.get(10)).startsWith("FICO Score         :");
        assertThat(lines.get(10)).contains("750");
        assertThat(lines.get(11)).isEqualTo(dashes);
        assertThat(lines.get(13)).isEqualTo(dashes);
        assertThat(lines.get(12)).isEqualTo(" ".repeat(30) + "TRANSACTION SUMMARY " + " ".repeat(30));
        assertThat(lines.get(14)).isEqualTo(
                "Tran ID         " + StatementGenerationJob.pad("Tran Details    ", 51) + "  Tran Amount");
        assertThat(lines.get(15)).isEqualTo(dashes);
        assertThat(lines.get(16)).startsWith("0000000000000001");
        assertThat(lines.get(16).substring(16, 17)).isEqualTo(" ");
        assertThat(lines.get(16)).endsWith(StatementGenerationJob.formatSuppressed(new BigDecimal("100.50")));
        assertThat(lines.get(16).charAt(66)).isEqualTo('$');
        assertThat(lines.get(17)).startsWith("0000000000000002");
        assertThat(lines.get(17)).endsWith(StatementGenerationJob.formatSuppressed(new BigDecimal("200.25")));
        assertThat(lines.get(18)).isEqualTo(dashes);
        assertThat(lines.get(19)).startsWith("Total EXP:");
        assertThat(lines.get(19)).endsWith(StatementGenerationJob.formatSuppressed(new BigDecimal("300.75")));
        assertThat(lines.get(19).charAt(66)).isEqualTo('$');
        assertThat(lines.get(20)).isEqualTo("*".repeat(32) + "END OF STATEMENT" + "*".repeat(32));
    }

    /**
     * :purpose: Verify a single-transaction model renders 19 + 1 = 20 text lines
     *   and closes with the dashed rule, the ``Total EXP:`` line and the end banner.
     */
    @Test
    void renderTextSingleTransactionSizing() {
        List<StatementMapper.StatementTransaction> transactions = new ArrayList<>();
        transactions.add(newTransaction("0000000000000001", "PURCHASE ONE", new BigDecimal("100.50")));
        List<String> lines = StatementGenerationJob.renderText(newModel(transactions, new BigDecimal("100.50")));

        assertThat(lines).hasSize(20);
        assertThat(lines).allSatisfy(line -> assertThat(line).hasSize(80));
        assertThat(lines.get(16)).startsWith("0000000000000001");
        assertThat(lines.get(17)).isEqualTo("-".repeat(80));
        assertThat(lines.get(18)).startsWith("Total EXP:");
        assertThat(lines.get(19)).isEqualTo("*".repeat(32) + "END OF STATEMENT" + "*".repeat(32));
    }

    /**
     * :purpose: Verify a zero-transaction model renders 19 + 0 = 19 text lines, the
     *   transaction-summary header is still emitted, and no transaction rows appear
     *   between the two closing dashed rules.
     */
    @Test
    void renderTextZeroTransactionSizing() {
        List<String> lines =
                StatementGenerationJob.renderText(newModel(new ArrayList<>(), new BigDecimal("0.00")));

        assertThat(lines).hasSize(19);
        assertThat(lines).allSatisfy(line -> assertThat(line).hasSize(80));
        assertThat(lines.get(12)).isEqualTo(" ".repeat(30) + "TRANSACTION SUMMARY " + " ".repeat(30));
        assertThat(lines.get(14)).isEqualTo(
                "Tran ID         " + StatementGenerationJob.pad("Tran Details    ", 51) + "  Tran Amount");
        assertThat(lines.get(15)).isEqualTo("-".repeat(80));
        assertThat(lines.get(16)).isEqualTo("-".repeat(80));
        assertThat(lines.get(17)).startsWith("Total EXP:");
        assertThat(lines.get(18)).isEqualTo("*".repeat(32) + "END OF STATEMENT" + "*".repeat(32));
    }

    // ------------------------------------------------------------------
    // 5.C - HTML statement rendering (every line exactly 100 chars)
    // ------------------------------------------------------------------

    /**
     * :purpose: Verify every rendered HTML line of the statement is exactly the
     *   frozen 100-column width.
     */
    @Test
    void renderHtmlEveryLineIsHundredChars() {
        List<String> html = StatementGenerationJob.renderHtml(standardModel());
        assertThat(html).allSatisfy(line -> assertThat(line).hasSize(100));
    }

    /**
     * :purpose: Verify the frozen HTML literals - document scaffold, section
     *   headers, account header, basic-detail label, the two-space ``&lt;table``
     *   tag, and the five background-color literals - are all present byte-for-byte.
     */
    @Test
    void renderHtmlContainsFrozenLiterals() {
        List<String> html = StatementGenerationJob.renderHtml(standardModel());

        assertThat(html).anyMatch(line -> line.stripTrailing().equals("<!DOCTYPE html>"));
        assertThat(html).anyMatch(line -> line.stripTrailing().equals("<html lang=\"en\">"));
        assertThat(html).anyMatch(line -> line.stripTrailing().equals("<head>"));
        assertThat(html).anyMatch(line -> line.stripTrailing().equals("<meta charset=\"utf-8\">"));
        assertThat(html).anyMatch(line -> line.stripTrailing().equals("<title>HTML Table Layout</title>"));
        assertThat(html).anyMatch(line -> line.stripTrailing().equals("</head>"));
        assertThat(html).anyMatch(line -> line.stripTrailing().equals("<body style=\"margin:0px;\">"));
        assertThat(html).anyMatch(line -> line.stripTrailing().equals("<p style=\"font-size:16px\">Bank of XYZ</p>"));
        assertThat(html).anyMatch(line -> line.stripTrailing().equals("<p>410 Terry Ave N</p>"));
        assertThat(html).anyMatch(line -> line.stripTrailing().equals("<p>Seattle WA 99999</p>"));
        assertThat(html).anyMatch(line -> line.stripTrailing().equals("<p style=\"font-size:16px\">Basic Details</p>"));
        assertThat(html).anyMatch(line -> line.stripTrailing().equals("<p style=\"font-size:16px\">Transaction Summary</p>"));
        assertThat(html).anyMatch(line -> line.stripTrailing().equals("<p style=\"font-size:16px\">Tran ID</p>"));
        assertThat(html).anyMatch(line -> line.stripTrailing().equals("<p style=\"font-size:16px\">Tran Details</p>"));
        assertThat(html).anyMatch(line -> line.stripTrailing().equals("<p style=\"font-size:16px\">Amount</p>"));
        assertThat(html).anyMatch(line -> line.stripTrailing().equals("<h3>End of Statement</h3>"));
        assertThat(html).anyMatch(line -> line.stripTrailing().equals("</table>"));
        assertThat(html).anyMatch(line -> line.stripTrailing().equals("</body>"));
        assertThat(html).anyMatch(line -> line.stripTrailing().equals("</html>"));

        assertThat(html).anyMatch(line -> line.contains("<table  align="));
        assertThat(html).anyMatch(line -> line.contains("<h3>Statement for Account Number: "));
        assertThat(html).anyMatch(line -> line.contains("<p>Account ID         : "));

        assertThat(html).anyMatch(line -> line.contains("#1d1d96b3"));
        assertThat(html).anyMatch(line -> line.contains("#FFAF33"));
        assertThat(html).anyMatch(line -> line.contains("#f2f2f2"));
        assertThat(html).anyMatch(line -> line.contains("#33FFD1"));
        assertThat(html).anyMatch(line -> line.contains("#33FF5E"));
    }

    /**
     * :purpose: Verify the same edited monetary values flow into the HTML with no
     *   drift: the balance is rendered by the zero-filled edit and a transaction
     *   amount by the suppressed edit.
     */
    @Test
    void renderHtmlMonetaryValuesHaveNoDrift() {
        List<String> html = StatementGenerationJob.renderHtml(standardModel());
        assertThat(html).anyMatch(line -> line.contains("000001234.56 "));
        assertThat(html).anyMatch(
                line -> line.contains(StatementGenerationJob.formatSuppressed(new BigDecimal("100.50"))));
    }

    // ------------------------------------------------------------------
    // 5.D - HTML escaping of datastore text (stored-XSS prevention)
    // ------------------------------------------------------------------

    /**
     * :purpose: Verify the escape helper replaces exactly the five characters that
     *   carry meaning in HTML and leaves ordinary statement text untouched.
     */
    @Test
    void htmlEscapeReplacesTheFiveMarkupCharacters() {
        assertThat(StatementGenerationJob.htmlEscape("JOHN A DOE")).isEqualTo("JOHN A DOE");
        assertThat(StatementGenerationJob.htmlEscape(null)).isEmpty();
        assertThat(StatementGenerationJob.htmlEscape("<script>alert(1)</script>"))
                .isEqualTo("&lt;script&gt;alert(1)&lt;/script&gt;");
        assertThat(StatementGenerationJob.htmlEscape("A & B \"quoted\" 'single'"))
                .isEqualTo("A &amp; B &quot;quoted&quot; &#39;single&#39;");
        // The ampersand is replaced first, so an introduced entity is not escaped twice.
        assertThat(StatementGenerationJob.htmlEscape("&lt;")).isEqualTo("&amp;lt;");
    }

    /**
     * :purpose: Verify customer-supplied name, address and transaction-description
     *   text carrying markup reaches the HTML statement escaped, so no browser can
     *   execute it, while every line stays at the frozen 100-column width.
     */
    @Test
    void renderHtmlEscapesCustomerSuppliedMarkup() {
        List<StatementMapper.StatementTransaction> transactions = new ArrayList<>();
        transactions.add(newTransaction("0000000000000001",
                "<img src=x onerror=alert(2)>", new BigDecimal("100.50")));
        StatementMapper.StatementModel model = newModel(transactions, new BigDecimal("100.50"));
        model.setCustomerName("<script>alert(1)</script>");
        model.setAddressLine1("<b>123 MAIN ST</b>");

        List<String> html = StatementGenerationJob.renderHtml(model);

        assertThat(html).allSatisfy(line -> assertThat(line).hasSize(100));
        assertThat(html).noneMatch(line -> line.contains("<script>"));
        assertThat(html).noneMatch(line -> line.contains("onerror=alert(2)>"));
        assertThat(html).anyMatch(line -> line.contains("&lt;script&gt;alert(1)&lt;/script&gt;"));
        assertThat(html).anyMatch(line -> line.contains("&lt;b&gt;123 MAIN ST&lt;/b&gt;"));
        assertThat(html).anyMatch(line -> line.contains("&lt;img src=x onerror=alert(2)&gt;"));
    }

    /**
     * :purpose: Verify the plain-text statement is NOT escaped - it is not an HTML
     *   document, and altering its text would break the frozen ``STMTFILE`` layout.
     */
    @Test
    void renderTextLeavesMarkupCharactersUnescaped() {
        List<StatementMapper.StatementTransaction> transactions = new ArrayList<>();
        transactions.add(newTransaction("0000000000000001", "A & B <x>", new BigDecimal("100.50")));
        StatementMapper.StatementModel model = newModel(transactions, new BigDecimal("100.50"));

        List<String> text = StatementGenerationJob.renderText(model);

        assertThat(text).allSatisfy(line -> assertThat(line).hasSize(80));
        assertThat(text).anyMatch(line -> line.contains("A & B <x>"));
        assertThat(text).noneMatch(line -> line.contains("&amp;"));
    }

    // ------------------------------------------------------------------
    // 5.E - Byte contracts for text outside Latin-1
    // ------------------------------------------------------------------

    /**
     * :purpose: Verify a name or description outside Latin-1 keeps every text line
     *   at exactly 80 BYTES and every HTML line at exactly 100 BYTES in the
     *   ISO-8859-1 encoding both files are written in. An accented character is two
     *   bytes under UTF-8, and a supplementary code point is two Java characters
     *   that encode to a single ISO-8859-1 byte, so the two directions must both
     *   hold.
     */
    @Test
    void nonLatin1TextKeepsTheEightyAndHundredByteContracts() {
        List<StatementMapper.StatementTransaction> transactions = new ArrayList<>();
        transactions.add(newTransaction("0000000000000001",
                "Café Müller \uD83C\uDF63", new BigDecimal("100.50")));
        StatementMapper.StatementModel model = newModel(transactions, new BigDecimal("100.50"));
        model.setCustomerName("JOSÉ MÜLLER");
        model.setAddressLine1("12 RUE DE L\u2019ÉGLISE");

        List<String> text = StatementGenerationJob.renderText(model);
        List<String> html = StatementGenerationJob.renderHtml(model);

        assertThat(text).allSatisfy(line ->
                assertThat(line.getBytes(StandardCharsets.ISO_8859_1)).hasSize(80));
        assertThat(html).allSatisfy(line ->
                assertThat(line.getBytes(StandardCharsets.ISO_8859_1)).hasSize(100));
        // Latin-1 characters survive; the emoji and the typographic apostrophe are
        // each replaced by one substitute character.
        assertThat(text).anyMatch(line -> line.contains("JOSÉ MÜLLER"));
        assertThat(text).anyMatch(line -> line.contains("Café Müller ?"));
        assertThat(html).anyMatch(line -> line.contains("12 RUE DE L?ÉGLISE"));
    }

    /**
     * :purpose: Verify the record delimiter both statement sinks emit is a single
     *   ``LF``, so an ``STMTFILE`` record occupies exactly 81 bytes and an
     *   ``HTMLFILE`` record exactly 101 (``LRECL`` payload plus one delimiter).
     *   ``System.lineSeparator()`` would emit ``CRLF`` on a Windows host or under
     *   ``-Dline.separator``, adding a byte to every record on a frozen contract
     *   [app/jcl/CREASTMT.jcl]. Reading the files back line-by-line cannot detect
     *   that, so the assertion is made on the raw bytes.
     * :param tempDir: JUnit-managed directory holding the two statement sinks.
     */
    @Test
    void statementSinksDelimitRecordsWithASingleLineFeed(@TempDir Path tempDir) throws Exception {
        File textFile = tempDir.resolve("stmtfile.txt").toFile();
        File htmlFile = tempDir.resolve("htmlfile.html").toFile();
        StatementMapper.StatementModel model = standardModel();

        StatementGenerationJob.StatementItemWriter writer =
                new StatementGenerationJob.StatementItemWriter(
                        new FileSystemResource(textFile), new FileSystemResource(htmlFile));
        writer.open(new ExecutionContext());
        try {
            writer.write(new Chunk<>(List.of(model)));
            writer.update(new ExecutionContext());
        } finally {
            writer.close();
        }

        byte[] textBytes = Files.readAllBytes(textFile.toPath());
        byte[] htmlBytes = Files.readAllBytes(htmlFile.toPath());

        assertThat(textBytes).hasSize(StatementGenerationJob.renderText(model).size() * (80 + 1));
        assertThat(htmlBytes).hasSize(StatementGenerationJob.renderHtml(model).size() * (100 + 1));
        assertThat(textBytes[80]).isEqualTo((byte) '\n');
        assertThat(htmlBytes[100]).isEqualTo((byte) '\n');
        assertThat(textBytes).doesNotContain((byte) '\r');
        assertThat(htmlBytes).doesNotContain((byte) '\r');
    }
}
