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
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.aws.carddemo.batch.processor.StatementProcessor;
import com.aws.carddemo.exception.FileStatusException;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
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
 * are read back as ISO-8859-1 and <strong>sliced into fixed-width records</strong>. The golden
 * expectations encoded here derive directly from the COBOL FD widths and the STEP040 DCBs in
 * {@code CREASTMT.JCL}: text records are exactly 80 bytes, HTML records exactly 100 bytes, written
 * back-to-back with <strong>no in-band delimiter</strong> ({@code RECFM=FB}), so a file of
 * <var>n</var> records is exactly <var>n</var>&nbsp;&times;&nbsp;width bytes with no trailing
 * newline.</p>
 *
 * <p>The tests also verify the writer's atomic-publish integrity contract: records are streamed to
 * temporary work files and published onto the final paths only on success, so a failed or aborted
 * run never leaves a partial artifact or an orphaned temporary file (CWE-459).</p>
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
    // Case 1 — exact record widths and exact whole-file lengths (no delimiter)
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

        List<String> textRecords = readTextRecords();
        List<String> htmlRecords = readHtmlRecords();
        assertThat(textRecords).hasSize(3);
        assertThat(htmlRecords).hasSize(3);
        assertThat(textRecords).allSatisfy(record -> assertThat(record).hasSize(TEXT_WIDTH));
        assertThat(htmlRecords).allSatisfy(record -> assertThat(record).hasSize(HTML_WIDTH));
        // RECFM=FB: whole file is an exact multiple of the record width (no in-band delimiter bytes).
        assertThat(Files.size(textPath())).isEqualTo((long) 3 * TEXT_WIDTH);
        assertThat(Files.size(htmlPath())).isEqualTo((long) 3 * HTML_WIDTH);
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

        assertThat(readTextRecords().get(0)).isEqualTo("HELLO" + " ".repeat(TEXT_WIDTH - 5));
        assertThat(readHtmlRecords().get(0)).isEqualTo("HELLO" + " ".repeat(HTML_WIDTH - 5));
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

        assertThat(readTextRecords().get(0)).hasSize(TEXT_WIDTH).isEqualTo("T".repeat(TEXT_WIDTH));
        assertThat(readHtmlRecords().get(0)).hasSize(HTML_WIDTH).isEqualTo("H".repeat(HTML_WIDTH));
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
        assertThat(readTextRecords()).hasSize(3);
        assertThat(readHtmlRecords()).hasSize(4);
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

        List<String> text = readTextRecords();
        List<String> html = readHtmlRecords();
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
        assertThat(readTextRecords()).isEmpty();
        assertThat(readHtmlRecords()).isEmpty();
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
        // A successful empty run publishes the empty work files and leaves no temporary artifact.
        assertThat(leftoverTempFiles()).isEmpty();
    }

    // ------------------------------------------------------------------------
    // Case 8 — a re-run truncates prior output rather than appending
    // ------------------------------------------------------------------------

    @Test
    @DisplayName("Re-running the full cycle replaces prior output rather than appending")
    void reRunTruncatesPriorOutput() throws Exception {
        StatementItemWriter first = newWriter();
        StepExecution se1 = newStep();
        first.beforeStep(se1);
        first.write(Chunk.of(doc(List.of("A", "B", "C"), List.of("H1", "H2"))));
        first.afterStep(se1);
        assertThat(readTextRecords()).hasSize(3);
        assertThat(readHtmlRecords()).hasSize(2);

        StatementItemWriter second = newWriter();
        StepExecution se2 = newStep();
        second.beforeStep(se2);
        second.write(Chunk.of(doc(List.of("ONLY"), List.of("ONLYH"))));
        second.afterStep(se2);

        List<String> text = readTextRecords();
        List<String> html = readHtmlRecords();
        assertThat(text).hasSize(1);
        assertThat(text.get(0)).startsWith("ONLY");
        assertThat(html).hasSize(1);
        assertThat(html.get(0)).startsWith("ONLYH");
        // The publish replaced the finals atomically and left no temporary artifact behind.
        assertThat(leftoverTempFiles()).isEmpty();
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
        // The accented character is the first byte of the first (and only) record in each file.
        assertThat(textBytes[0]).isEqualTo((byte) 0xC9);
        assertThat(htmlBytes[0]).isEqualTo((byte) 0xC9);
        // Each file is exactly one fixed-width record — no trailing newline / in-band delimiter.
        assertThat(textBytes).hasSize(TEXT_WIDTH);
        assertThat(htmlBytes).hasSize(HTML_WIDTH);
        // The single content byte is followed only by ISO-8859-1 space padding (0x20), never a delimiter.
        assertThat(textBytes[1]).isEqualTo((byte) 0x20);
        assertThat(htmlBytes[1]).isEqualTo((byte) 0x20);
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
    // Case 13 — F02: fixed records carry NO in-band delimiter (RECFM=FB)
    // ------------------------------------------------------------------------

    @Test
    @DisplayName("Fixed records contain no in-band delimiter; each record sits at offset k*width")
    void noInBandRecordDelimiter() throws Exception {
        StatementItemWriter writer = newWriter();
        StepExecution se = newStep();
        writer.beforeStep(se);
        writer.write(Chunk.of(doc(
                List.of("AAA", "BBB", "CCC"),
                List.of("<h1>", "<h2>"))));
        writer.afterStep(se);

        byte[] textBytes = Files.readAllBytes(textPath());
        byte[] htmlBytes = Files.readAllBytes(htmlPath());

        // Whole-file length is an exact multiple of the record width (3 text records, 2 html records).
        assertThat(textBytes).hasSize(3 * TEXT_WIDTH);
        assertThat(htmlBytes).hasSize(2 * HTML_WIDTH);

        // No newline or carriage-return byte appears anywhere — records are self-delimiting.
        assertThat(containsByte(textBytes, (byte) 0x0A)).isFalse();
        assertThat(containsByte(textBytes, (byte) 0x0D)).isFalse();
        assertThat(containsByte(htmlBytes, (byte) 0x0A)).isFalse();
        assertThat(containsByte(htmlBytes, (byte) 0x0D)).isFalse();

        // Record k begins exactly at byte offset k*width (no drift from a stray delimiter byte).
        List<String> text = readTextRecords();
        assertThat(text.get(0)).startsWith("AAA");
        assertThat(text.get(1)).startsWith("BBB");
        assertThat(text.get(2)).startsWith("CCC");
        assertThat(new String(textBytes, 0, 3, StandardCharsets.ISO_8859_1)).isEqualTo("AAA");
        assertThat(new String(textBytes, TEXT_WIDTH, 3, StandardCharsets.ISO_8859_1)).isEqualTo("BBB");
        assertThat(new String(textBytes, 2 * TEXT_WIDTH, 3, StandardCharsets.ISO_8859_1)).isEqualTo("CCC");
    }

    // ------------------------------------------------------------------------
    // Case 14 — F20: a FAILED step publishes nothing and leaves no partial/temp artifact
    // ------------------------------------------------------------------------

    @Test
    @DisplayName("A FAILED step publishes no final files and removes the temporary work files")
    void failedStepPublishesNothingAndCleansUp() throws Exception {
        StatementItemWriter writer = newWriter();
        StepExecution se = newStep();
        writer.beforeStep(se);
        writer.write(Chunk.of(doc(List.of("A", "B"), List.of("H1", "H2"))));
        se.setStatus(BatchStatus.FAILED);

        assertThat(writer.afterStep(se)).isEqualTo(ExitStatus.FAILED);
        // No partial or stale final artifact was published for the failed run.
        assertThat(Files.exists(textPath())).isFalse();
        assertThat(Files.exists(htmlPath())).isFalse();
        // The temporary work files were deleted — nothing is left behind in the output directory.
        assertThat(leftoverTempFiles()).isEmpty();
        assertThat(anyFilesInOutputDir()).isEmpty();
    }

    // ------------------------------------------------------------------------
    // Case 15 — F20: an open failure leaves no partial final file and no orphaned temp
    // ------------------------------------------------------------------------

    @Test
    @DisplayName("An open failure leaves no final files and no orphaned temporary work file")
    void openFailureLeavesNoArtifacts() throws Exception {
        // A regular file sits where the output directory tree would be needed, so createDirectories
        // fails before any temporary work file can be created.
        Path blockingFile = tempDir.resolve("blocker");
        Files.writeString(blockingFile, "x", StandardCharsets.ISO_8859_1);
        Path unusableDir = blockingFile.resolve("out"); // resolves under a regular file -> IOException

        StatementItemWriter writer =
                new StatementItemWriter(unusableDir.toString(), TEXT_FILE, HTML_FILE);
        StepExecution se = newStep();

        assertThatThrownBy(() -> writer.beforeStep(se))
                .isInstanceOf(FileStatusException.class)
                .hasCauseInstanceOf(IOException.class);

        // Neither final file was published, and no temporary work file was left behind anywhere in the
        // (accessible) working directory.
        assertThat(Files.exists(unusableDir.resolve(TEXT_FILE))).isFalse();
        assertThat(Files.exists(unusableDir.resolve(HTML_FILE))).isFalse();
        assertThat(leftoverTempFiles()).isEmpty();
    }

    // ------------------------------------------------------------------------
    // Case 16 — F20: a successful run publishes owner-only files atomically (POSIX)
    // ------------------------------------------------------------------------

    @Test
    @DisplayName("A successful run publishes both final files owner-only and leaves no temp file")
    void successfulRunPublishesOwnerOnlyFiles() throws Exception {
        assumeTrue(FileSystems.getDefault().supportedFileAttributeViews().contains("posix"),
                "POSIX file permissions are required for this assertion");

        StatementItemWriter writer = newWriter();
        StepExecution se = newStep();
        writer.beforeStep(se);
        writer.write(Chunk.of(doc(List.of("line"), List.of("html"))));

        assertThat(writer.afterStep(se)).isEqualTo(ExitStatus.COMPLETED);
        assertThat(Files.exists(textPath())).isTrue();
        assertThat(Files.exists(htmlPath())).isTrue();
        // The atomic publish preserves the owner-only permissions applied to the temporary work files.
        assertThat(PosixFilePermissions.toString(Files.getPosixFilePermissions(textPath())))
                .isEqualTo("rw-------");
        assertThat(PosixFilePermissions.toString(Files.getPosixFilePermissions(htmlPath())))
                .isEqualTo("rw-------");
        // Only the two published files remain; no temporary work file is left behind.
        assertThat(leftoverTempFiles()).isEmpty();
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

    /** Reads the text file back and slices it into fixed {@value #TEXT_WIDTH}-byte records. */
    private List<String> readTextRecords() throws IOException {
        return sliceFixedWidth(textPath(), TEXT_WIDTH);
    }

    /** Reads the HTML file back and slices it into fixed {@value #HTML_WIDTH}-byte records. */
    private List<String> readHtmlRecords() throws IOException {
        return sliceFixedWidth(htmlPath(), HTML_WIDTH);
    }

    /**
     * Reads a fixed-width {@code RECFM=FB} file back as ISO-8859-1 and slices it into records of
     * exactly {@code recordWidth} bytes. Asserts the whole-file length is an exact multiple of the
     * record width — the writer emits no in-band delimiter, so any leftover bytes would signal a
     * regression of the F02 fixed-record contract.
     */
    private static List<String> sliceFixedWidth(Path file, int recordWidth) throws IOException {
        byte[] bytes = Files.readAllBytes(file);
        assertThat(bytes.length % recordWidth)
                .as("file %s length %d must be an exact multiple of the %d-byte record width "
                        + "(no in-band delimiter)", file, bytes.length, recordWidth)
                .isZero();
        List<String> records = new ArrayList<>();
        for (int offset = 0; offset < bytes.length; offset += recordWidth) {
            records.add(new String(bytes, offset, recordWidth, StandardCharsets.ISO_8859_1));
        }
        return records;
    }

    /** True if {@code haystack} contains the given byte value anywhere. */
    private static boolean containsByte(byte[] haystack, byte needle) {
        for (byte b : haystack) {
            if (b == needle) {
                return true;
            }
        }
        return false;
    }

    /** Names of any temporary work files still present in the output directory (should be none). */
    private List<String> leftoverTempFiles() throws IOException {
        try (Stream<Path> entries = Files.list(tempDir)) {
            List<String> names = new ArrayList<>();
            entries.forEach(p -> {
                String name = p.getFileName().toString();
                if (name.endsWith(".inprogress") || name.contains(".inprogress")) {
                    names.add(name);
                }
            });
            return names;
        }
    }

    /** Names of the regular output files present directly in the output directory. */
    private List<String> anyFilesInOutputDir() throws IOException {
        try (Stream<Path> entries = Files.list(tempDir)) {
            List<String> names = new ArrayList<>();
            entries.filter(Files::isRegularFile).forEach(p -> names.add(p.getFileName().toString()));
            return names;
        }
    }
}
