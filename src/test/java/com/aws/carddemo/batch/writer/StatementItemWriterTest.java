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

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.aws.carddemo.batch.processor.StatementProcessor;
import com.aws.carddemo.exception.FileStatusException;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.ExitStatus;
import org.springframework.batch.core.StepExecution;
import org.springframework.batch.item.Chunk;
import org.springframework.batch.test.MetaDataInstanceFactory;

/**
 * Behavioral-parity unit tests for {@link StatementItemWriter}, the Spring Batch item writer that
 * reproduces the output side of the legacy COBOL statement program {@code legacy/cbl/CBSTM03A.CBL}
 * ({@code STMTFILE} / {@code FD-STMTFILE-REC PIC X(80)} and {@code HTMLFILE} /
 * {@code FD-HTMLFILE-REC PIC X(100)}).
 *
 * <p>These tests exercise the writer as a self-contained file producer: a fresh
 * {@link StatementItemWriter} is constructed with a {@link TempDir}-backed output directory (so no
 * Spring context or database is required), driven through its
 * {@link StatementItemWriter#beforeStep(StepExecution) beforeStep} /
 * {@link StatementItemWriter#write(Chunk) write} /
 * {@link StatementItemWriter#afterStep(StepExecution) afterStep} lifecycle, and the produced files
 * are read back as ISO-8859-1 and split on {@code "\n"} for assertion. The golden expectations
 * encoded here derive directly from the COBOL FD widths and the STEP040 DCBs in
 * {@code CREASTMT.JCL}: text records are exactly 80 bytes, HTML records exactly 100 bytes.</p>
 */
class StatementItemWriterTest {

    /** Text record width — {@code FD-STMTFILE-REC PIC X(80)}. */
    private static final int TEXT_WIDTH = 80;

    /** HTML record width — {@code FD-HTMLFILE-REC PIC X(100)}. */
    private static final int HTML_WIDTH = 100;

    /** Plain-text statement file name used for every test run. */
    private static final String TEXT_FILE = "statements.txt";

    /** HTML statement file name used for every test run. */
    private static final String HTML_FILE = "statements.html";

    @TempDir
    Path tempDir;

    // ------------------------------------------------------------------------
    // Case 1 — exact record widths
    // ------------------------------------------------------------------------

    @Test
    @DisplayName("Every text record is exactly 80 bytes and every HTML record exactly 100 bytes")
    void exactRecordWidths() throws Exception {
        StatementItemWriter writer = newWriter();
        StepExecution se = newStep();
        writer.beforeStep(se);
        writer.write(Chunk.of(doc(
                List.of("STATEMENT", "Line two", "Total: 12.34"),
                List.of("<html>", "<body>", "</body></html>"))));
        writer.afterStep(se);

        List<String> textRecords = readRecords(textPath());
        List<String> htmlRecords = readRecords(htmlPath());
        assertThat(textRecords).hasSize(3);
        assertThat(htmlRecords).hasSize(3);
        assertThat(textRecords).allSatisfy(record -> assertThat(record).hasSize(TEXT_WIDTH));
        assertThat(htmlRecords).allSatisfy(record -> assertThat(record).hasSize(HTML_WIDTH));
    }

    // ------------------------------------------------------------------------
    // Case 2 — right space-padding (COBOL PIC X(n) MOVE, left-justify + space-fill)
    // ------------------------------------------------------------------------

    @Test
    @DisplayName("A short line is left-justified and right space-padded to the record width")
    void shortLineIsRightSpacePadded() throws Exception {
        StatementItemWriter writer = newWriter();
        StepExecution se = newStep();
        writer.beforeStep(se);
        writer.write(Chunk.of(doc(List.of("HELLO"), List.of("HELLO"))));
        writer.afterStep(se);

        assertThat(readRecords(textPath()).get(0)).isEqualTo("HELLO" + " ".repeat(TEXT_WIDTH - 5));
        assertThat(readRecords(htmlPath()).get(0)).isEqualTo("HELLO" + " ".repeat(HTML_WIDTH - 5));
    }

    // ------------------------------------------------------------------------
    // Case 3 — defensive right truncation
    // ------------------------------------------------------------------------

    @Test
    @DisplayName("A line longer than the record width is truncated on the right to the exact width")
    void longLineIsRightTruncated() throws Exception {
        String longText = "T".repeat(100); // > 80
        String longHtml = "H".repeat(150); // > 100
        StatementItemWriter writer = newWriter();
        StepExecution se = newStep();
        writer.beforeStep(se);
        writer.write(Chunk.of(doc(List.of(longText), List.of(longHtml))));
        writer.afterStep(se);

        assertThat(readRecords(textPath()).get(0)).hasSize(TEXT_WIDTH).isEqualTo("T".repeat(TEXT_WIDTH));
        assertThat(readRecords(htmlPath()).get(0)).hasSize(HTML_WIDTH).isEqualTo("H".repeat(HTML_WIDTH));
    }

    // ------------------------------------------------------------------------
    // Case 4 — record and document counts promoted to the execution context
    // ------------------------------------------------------------------------

    @Test
    @DisplayName("Document, text-record, and HTML-record counts are promoted to the execution context")
    void countsPromotedToExecutionContext() throws Exception {
        StatementItemWriter writer = newWriter();
        StepExecution se = newStep();
        writer.beforeStep(se);
        writer.write(Chunk.of(
                doc(List.of("a", "b"), List.of("x")),        // 2 text, 1 html
                doc(List.of("c"), List.of("y", "z", "w"))));  // 1 text, 3 html
        ExitStatus status = writer.afterStep(se);

        assertThat(status).isEqualTo(ExitStatus.COMPLETED);
        assertThat(se.getExecutionContext().getLong("statement.documentCount")).isEqualTo(2L);
        assertThat(se.getExecutionContext().getLong("statement.textRecordCount")).isEqualTo(3L);
        assertThat(se.getExecutionContext().getLong("statement.htmlRecordCount")).isEqualTo(4L);
        // Cross-check the promoted counts against the actual files.
        assertThat(readRecords(textPath())).hasSize(3);
        assertThat(readRecords(htmlPath())).hasSize(4);
    }

    // ------------------------------------------------------------------------
    // Case 5 — multi-document ordering preserved within each file
    // ------------------------------------------------------------------------

    @Test
    @DisplayName("Documents are written in order: doc1 lines precede doc2 lines within each file")
    void multiDocumentOrderingPreserved() throws Exception {
        StatementItemWriter writer = newWriter();
        StepExecution se = newStep();
        writer.beforeStep(se);
        writer.write(Chunk.of(
                doc(List.of("DOC1-T1", "DOC1-T2"), List.of("DOC1-H1")),
                doc(List.of("DOC2-T1"), List.of("DOC2-H1", "DOC2-H2"))));
        writer.afterStep(se);

        List<String> text = readRecords(textPath());
        List<String> html = readRecords(htmlPath());
        assertThat(text.get(0)).startsWith("DOC1-T1");
        assertThat(text.get(1)).startsWith("DOC1-T2");
        assertThat(text.get(2)).startsWith("DOC2-T1");
        assertThat(html.get(0)).startsWith("DOC1-H1");
        assertThat(html.get(1)).startsWith("DOC2-H1");
        assertThat(html.get(2)).startsWith("DOC2-H2");
    }

    // ------------------------------------------------------------------------
    // Case 6 — empty or null line lists write nothing (defensive null-guard)
    // ------------------------------------------------------------------------

    @Test
    @DisplayName("A document with empty or null line lists writes zero records without throwing")
    void emptyOrNullDocumentWritesNothing() throws Exception {
        StatementItemWriter writer = newWriter();
        StepExecution se = newStep();
        writer.beforeStep(se);
        // An empty-lists document, then a null-lists document exercising the defensive null-guard.
        writer.write(Chunk.of(doc(List.of(), List.of()), doc(null, null)));
        ExitStatus status = writer.afterStep(se);

        assertThat(status).isEqualTo(ExitStatus.COMPLETED);
        assertThat(Files.exists(textPath())).isTrue();
        assertThat(Files.exists(htmlPath())).isTrue();
        assertThat(readRecords(textPath())).isEmpty();
        assertThat(readRecords(htmlPath())).isEmpty();
        assertThat(se.getExecutionContext().getLong("statement.documentCount")).isEqualTo(2L);
        assertThat(se.getExecutionContext().getLong("statement.textRecordCount")).isEqualTo(0L);
        assertThat(se.getExecutionContext().getLong("statement.htmlRecordCount")).isEqualTo(0L);
    }

    // ------------------------------------------------------------------------
    // Case 7 — zero-card run still leaves two empty files (parity with OPEN OUTPUT)
    // ------------------------------------------------------------------------

    @Test
    @DisplayName("A run with no write() leaves two empty files present and returns COMPLETED")
    void zeroCardRunLeavesTwoEmptyFiles() throws Exception {
        StatementItemWriter writer = newWriter();
        StepExecution se = newStep();
        writer.beforeStep(se);
        ExitStatus status = writer.afterStep(se);

        assertThat(status).isEqualTo(ExitStatus.COMPLETED);
        assertThat(Files.exists(textPath())).isTrue();
        assertThat(Files.exists(htmlPath())).isTrue();
        assertThat(Files.size(textPath())).isZero();
        assertThat(Files.size(htmlPath())).isZero();
    }

    // ------------------------------------------------------------------------
    // Case 8 — a re-run truncates prior output rather than appending
    // ------------------------------------------------------------------------

    @Test
    @DisplayName("Re-running the full cycle truncates prior output rather than appending")
    void reRunTruncatesPriorOutput() throws Exception {
        StatementItemWriter first = newWriter();
        StepExecution se1 = newStep();
        first.beforeStep(se1);
        first.write(Chunk.of(doc(List.of("A", "B", "C"), List.of("H1", "H2"))));
        first.afterStep(se1);
        assertThat(readRecords(textPath())).hasSize(3);
        assertThat(readRecords(htmlPath())).hasSize(2);

        StatementItemWriter second = newWriter();
        StepExecution se2 = newStep();
        second.beforeStep(se2);
        second.write(Chunk.of(doc(List.of("ONLY"), List.of("ONLYH"))));
        second.afterStep(se2);

        List<String> text = readRecords(textPath());
        List<String> html = readRecords(htmlPath());
        assertThat(text).hasSize(1);
        assertThat(text.get(0)).startsWith("ONLY");
        assertThat(html).hasSize(1);
        assertThat(html.get(0)).startsWith("ONLYH");
    }

    // ------------------------------------------------------------------------
    // Case 9 — RC0 on normal completion
    // ------------------------------------------------------------------------

    @Test
    @DisplayName("Normal completion maps to ExitStatus.COMPLETED (RC0)")
    void normalCompletionReturnsCompleted() throws Exception {
        StatementItemWriter writer = newWriter();
        StepExecution se = newStep();
        writer.beforeStep(se);
        writer.write(Chunk.of(doc(List.of("line"), List.of("html"))));
        assertThat(writer.afterStep(se)).isEqualTo(ExitStatus.COMPLETED);
    }

    // ------------------------------------------------------------------------
    // Case 10 — RC8 on a FAILED step and on an open failure (no reject path)
    // ------------------------------------------------------------------------

    @Test
    @DisplayName("A FAILED step maps to ExitStatus.FAILED (RC8)")
    void failedStepReturnsFailed() throws Exception {
        StatementItemWriter writer = newWriter();
        StepExecution se = newStep();
        writer.beforeStep(se);
        writer.write(Chunk.of(doc(List.of("line"), List.of("html"))));
        se.setStatus(BatchStatus.FAILED);
        assertThat(writer.afterStep(se)).isEqualTo(ExitStatus.FAILED);
    }

    @Test
    @DisplayName("An open failure in beforeStep fails fast with a chained FileStatusException (RC8)")
    void openFailureThrowsFileStatusException() throws Exception {
        // A regular file sits where a parent directory would be needed, so createDirectories/open fails.
        Path blockingFile = tempDir.resolve("not-a-directory");
        Files.writeString(blockingFile, "x", StandardCharsets.ISO_8859_1);
        Path unusableDir = blockingFile.resolve("sub");

        StatementItemWriter writer =
                new StatementItemWriter(unusableDir.toString(), TEXT_FILE, HTML_FILE);
        StepExecution se = newStep();

        assertThatThrownBy(() -> writer.beforeStep(se))
                .isInstanceOf(FileStatusException.class)
                .hasMessageContaining("STMTFILE")
                .hasCauseInstanceOf(IOException.class);
    }

    // ------------------------------------------------------------------------
    // Case 11 — ISO-8859-1 encoding (one character maps to exactly one byte)
    // ------------------------------------------------------------------------

    @Test
    @DisplayName("A high-Latin-1 character is written as a single 0xC9 byte (ISO-8859-1, not UTF-8)")
    void writesIso8859Encoding() throws Exception {
        StatementItemWriter writer = newWriter();
        StepExecution se = newStep();
        writer.beforeStep(se);
        // 'E' with acute accent = U+00C9: one 0xC9 byte in ISO-8859-1 (two bytes 0xC3 0x89 in UTF-8).
        writer.write(Chunk.of(doc(List.of("\u00C9"), List.of("\u00C9"))));
        writer.afterStep(se);

        byte[] textBytes = Files.readAllBytes(textPath());
        byte[] htmlBytes = Files.readAllBytes(htmlPath());
        // The accented character is the first byte of the first record in each file.
        assertThat(textBytes[0]).isEqualTo((byte) 0xC9);
        assertThat(htmlBytes[0]).isEqualTo((byte) 0xC9);
        // Each file is exactly one record (content bytes) plus the single LF separator.
        assertThat(textBytes).hasSize(TEXT_WIDTH + 1);
        assertThat(htmlBytes).hasSize(HTML_WIDTH + 1);
        assertThat(textBytes[TEXT_WIDTH]).isEqualTo((byte) '\n');
        assertThat(htmlBytes[HTML_WIDTH]).isEqualTo((byte) '\n');
    }

    // ------------------------------------------------------------------------
    // Case 12 — statement / HTML line content is never logged (PII protection)
    // ------------------------------------------------------------------------

    @Test
    @DisplayName("No log statement ever contains statement or HTML line content")
    void noStatementContentIsEverLogged() throws Exception {
        String secretText = "SENSITIVE-CUSTOMER-NAME-DOE-JOHN";
        String secretHtml = "SENSITIVE-HTML-SSN-123-45-6789";

        Logger logbackLogger =
                (Logger) LoggerFactory.getLogger(StatementItemWriter.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logbackLogger.addAppender(appender);
        try {
            StatementItemWriter writer = newWriter();
            StepExecution se = newStep();
            writer.beforeStep(se);
            writer.write(Chunk.of(
                    doc(List.of(secretText), List.of(secretHtml)),
                    doc(null, null))); // also exercises the DEBUG null-guard log path
            writer.afterStep(se);
        } finally {
            logbackLogger.detachAppender(appender);
        }

        for (ILoggingEvent event : appender.list) {
            assertThat(event.getFormattedMessage())
                    .doesNotContain(secretText)
                    .doesNotContain(secretHtml);
            Object[] args = event.getArgumentArray();
            if (args != null) {
                for (Object arg : args) {
                    assertThat(String.valueOf(arg))
                            .doesNotContain(secretText)
                            .doesNotContain(secretHtml);
                }
            }
        }
        // Sanity: the writer did emit at least one (non-content) log line, so the check is meaningful.
        assertThat(appender.list).isNotEmpty();
    }

    // ------------------------------------------------------------------------
    // Test helpers
    // ------------------------------------------------------------------------

    private StatementItemWriter newWriter() {
        return new StatementItemWriter(tempDir.toString(), TEXT_FILE, HTML_FILE);
    }

    private static StepExecution newStep() {
        return MetaDataInstanceFactory.createStepExecution();
    }

    private static StatementProcessor.StatementDocument doc(List<String> textLines, List<String> htmlLines) {
        return new StatementProcessor.StatementDocument(textLines, htmlLines);
    }

    private Path textPath() {
        return tempDir.resolve(TEXT_FILE);
    }

    private Path htmlPath() {
        return tempDir.resolve(HTML_FILE);
    }

    /** Reads a fixed-width file back as ISO-8859-1 and returns its records (trailing empty dropped). */
    private static List<String> readRecords(Path file) throws IOException {
        byte[] bytes = Files.readAllBytes(file);
        String content = new String(bytes, StandardCharsets.ISO_8859_1);
        List<String> records = new ArrayList<>();
        for (String part : content.split("\n", -1)) {
            if (!part.isEmpty()) {
                records.add(part);
            }
        }
        return records;
    }
}
