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
package com.aws.carddemo.batch.writer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.aws.carddemo.exception.IoStatusException;
import java.io.IOException;
import java.io.StringWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Pure JUnit&nbsp;5 + AssertJ unit tests for {@link ReportFileWriter}, the physical {@code
 * TRANREPT} output sink migrated from the report side of {@code legacy/app/cbl/CBTRN03C.cbl}
 * (paragraphs {@code 0100-REPTFILE-OPEN}, {@code 1111-WRITE-REPORT-REC}, {@code
 * 9100-REPTFILE-CLOSE}).
 *
 * <p>The tests pin the parity invariants the report job depends on:
 *
 * <ul>
 *   <li>every accepted line is normalized to exactly {@value ReportFileWriter#LRECL} characters
 *       (right space-padded when shorter, right-truncated when longer, blank when {@code null}),
 *       reproducing the {@code PIC X(133)} print record on a {@code RECFM=FB} dataset;
 *   <li>the record separator defaults to a fixed {@code "\n"} for deterministic golden-file parity;
 *   <li>any physical open/write/close {@link IOException} is re-thrown as an {@link
 *       IoStatusException} (never swallowed), mirroring the COBOL "unexpected status &rarr; abend"
 *       path; and
 *   <li>{@link ReportFileWriter#close()} is idempotent.
 * </ul>
 */
class ReportFileWriterTest {

  /** A line that is shorter than the fixed record length and must be right space-padded. */
  private static final String SHORT_LINE = "ABCDEFGHIJ";

  @Test
  void constantsMatchTranreptContract() {
    assertThat(ReportFileWriter.DD_NAME).isEqualTo("TRANREPT");
    assertThat(ReportFileWriter.LRECL).isEqualTo(133);
  }

  @Test
  void acceptShortLineIsRightPaddedTo133PlusNewline() {
    StringWriter sw = new StringWriter();
    ReportFileWriter writer = new ReportFileWriter();
    writer.open(sw);

    writer.accept(SHORT_LINE);

    String content = sw.toString();
    assertThat(content).hasSize(ReportFileWriter.LRECL + 1).endsWith("\n");
    String record = content.substring(0, ReportFileWriter.LRECL);
    assertThat(record).hasSize(133).startsWith(SHORT_LINE);
    assertThat(record).isEqualTo(SHORT_LINE + " ".repeat(133 - SHORT_LINE.length()));
  }

  @Test
  void acceptLongerThan133IsTruncatedTo133PlusNewline() {
    StringWriter sw = new StringWriter();
    ReportFileWriter writer = new ReportFileWriter();
    writer.open(sw);
    String longLine = "X".repeat(200);

    writer.accept(longLine);

    String content = sw.toString();
    assertThat(content).hasSize(ReportFileWriter.LRECL + 1).endsWith("\n");
    assertThat(content.substring(0, ReportFileWriter.LRECL)).isEqualTo("X".repeat(133));
  }

  @Test
  void acceptExactly133IsUnchangedPlusNewline() {
    StringWriter sw = new StringWriter();
    ReportFileWriter writer = new ReportFileWriter();
    writer.open(sw);
    String exact = "E".repeat(133);

    writer.accept(exact);

    assertThat(sw.toString()).isEqualTo(exact + "\n");
  }

  @Test
  void acceptNullProduces133SpacesPlusNewline() {
    StringWriter sw = new StringWriter();
    ReportFileWriter writer = new ReportFileWriter();
    writer.open(sw);

    writer.accept(null);

    assertThat(sw.toString()).isEqualTo(" ".repeat(133) + "\n");
  }

  @Test
  void getRecordsWrittenIncrementsPerAcceptAndResetsOnOpen() {
    ReportFileWriter writer = new ReportFileWriter();
    writer.open(new StringWriter());
    assertThat(writer.getRecordsWritten()).isZero();

    writer.accept("a");
    writer.accept("b");
    writer.accept("c");
    assertThat(writer.getRecordsWritten()).isEqualTo(3);

    // Re-opening onto a new stream resets the counter.
    writer.open(new StringWriter());
    assertThat(writer.getRecordsWritten()).isZero();
  }

  @Test
  void nullConstructorArgumentsCoalesceToUtf8AndNewline() {
    ReportFileWriter writer = new ReportFileWriter(null, null);
    StringWriter sw = new StringWriter();
    writer.open(sw);

    writer.accept("Z");

    assertThat(sw.toString()).hasSize(ReportFileWriter.LRECL + 1).endsWith("\n");
  }

  @Test
  void customLineSeparatorIsAppendedAfterEachRecord() {
    ReportFileWriter writer = new ReportFileWriter(StandardCharsets.UTF_8, "\r\n");
    StringWriter sw = new StringWriter();
    writer.open(sw);

    writer.accept("Q");

    // 133 record characters + the two-character CRLF separator.
    assertThat(sw.toString()).hasSize(135).endsWith("\r\n");
  }

  @Test
  void openWriterWithNullThrowsIoStatusExceptionForOpen() {
    ReportFileWriter writer = new ReportFileWriter();

    assertThatThrownBy(() -> writer.open((Writer) null))
        .isInstanceOfSatisfying(
            IoStatusException.class,
            ex -> {
              assertThat(ex.getFileName()).isEqualTo("TRANREPT");
              assertThat(ex.getOperation()).isEqualTo("OPEN");
              assertThat(ex.getFileStatus()).isEqualTo("30");
            });
  }

  @Test
  void acceptWhenNotOpenThrowsIoStatusExceptionForWrite() {
    ReportFileWriter writer = new ReportFileWriter();

    assertThatThrownBy(() -> writer.accept("anything"))
        .isInstanceOfSatisfying(
            IoStatusException.class,
            ex -> {
              assertThat(ex.getFileName()).isEqualTo("TRANREPT");
              assertThat(ex.getOperation()).isEqualTo("WRITE");
            });
  }

  @Test
  void acceptRethrowsUnderlyingWriteFailureAsIoStatusExceptionWithCause() {
    ReportFileWriter writer = new ReportFileWriter();
    writer.open(new FailingWriter(true, false));

    assertThatThrownBy(() -> writer.accept("data"))
        .isInstanceOfSatisfying(
            IoStatusException.class,
            ex -> {
              assertThat(ex.getFileName()).isEqualTo("TRANREPT");
              assertThat(ex.getOperation()).isEqualTo("WRITE");
              assertThat(ex.getCause()).isInstanceOf(IOException.class);
            });
  }

  @Test
  void closeRethrowsUnderlyingCloseFailureAsIoStatusExceptionWithCauseThenIsIdempotent() {
    ReportFileWriter writer = new ReportFileWriter();
    writer.open(new FailingWriter(false, true));
    writer.accept("ok"); // write succeeds; only close() fails

    assertThatThrownBy(writer::close)
        .isInstanceOfSatisfying(
            IoStatusException.class,
            ex -> {
              assertThat(ex.getFileName()).isEqualTo("TRANREPT");
              assertThat(ex.getOperation()).isEqualTo("CLOSE");
              assertThat(ex.getCause()).isInstanceOf(IOException.class);
            });

    // After a failed close the stream reference is cleared, so a second close is a no-op.
    assertThatCode(writer::close).doesNotThrowAnyException();
  }

  @Test
  void closeIsIdempotentBeforeOpenAndAfterClose() {
    ReportFileWriter writer = new ReportFileWriter();

    // close() before any open() is a no-op.
    assertThatCode(writer::close).doesNotThrowAnyException();

    writer.open(new StringWriter());
    writer.accept("x");
    writer.close();

    // A second close() after a successful close() is also a no-op.
    assertThatCode(writer::close).doesNotThrowAnyException();
  }

  @Test
  void openPathWritesFixedWidthRecordsAndRoundTrips(@TempDir Path dir) throws IOException {
    Path output = dir.resolve("tranrept.txt");

    try (ReportFileWriter writer = new ReportFileWriter()) {
      writer.open(output);
      writer.accept("HEADER");
      writer.accept("D".repeat(200)); // overlong -> truncated to 133
      writer.accept(null); // blank line -> 133 spaces
      assertThat(writer.getRecordsWritten()).isEqualTo(3);
    }

    List<String> lines = Files.readAllLines(output, StandardCharsets.UTF_8);
    assertThat(lines).hasSize(3).allSatisfy(line -> assertThat(line).hasSize(133));
    assertThat(lines.get(0)).startsWith("HEADER");
    assertThat(lines.get(1)).isEqualTo("D".repeat(133));
    assertThat(lines.get(2)).isEqualTo(" ".repeat(133));

    String raw = Files.readString(output, StandardCharsets.UTF_8);
    assertThat(raw).hasSize(3 * (ReportFileWriter.LRECL + 1)).endsWith("\n");
  }

  @Test
  void openPathTruncatesExistingFileOnReopen(@TempDir Path dir) throws IOException {
    Path output = dir.resolve("reused.txt");

    try (ReportFileWriter writer = new ReportFileWriter()) {
      writer.open(output);
      writer.accept("first-run-1");
      writer.accept("first-run-2");
    }
    assertThat(Files.readAllLines(output)).hasSize(2);

    // A fresh open of the same path must TRUNCATE_EXISTING, not append.
    try (ReportFileWriter writer = new ReportFileWriter()) {
      writer.open(output);
      writer.accept("second-run-1");
    }
    assertThat(Files.readAllLines(output)).hasSize(1);
  }

  @Test
  void openPathWithExplicitCharsetWritesRecord(@TempDir Path dir) throws IOException {
    Path output = dir.resolve("ascii.txt");
    ReportFileWriter writer = new ReportFileWriter(StandardCharsets.ISO_8859_1, "\n");

    writer.open(output, StandardCharsets.US_ASCII);
    writer.accept("ASCII");
    writer.close();

    List<String> lines = Files.readAllLines(output, StandardCharsets.US_ASCII);
    assertThat(lines).hasSize(1);
    assertThat(lines.get(0)).hasSize(133).startsWith("ASCII");
  }

  @Test
  void openPathWithNullCharsetFallsBackToConfiguredCharset(@TempDir Path dir) throws IOException {
    Path output = dir.resolve("fallback.txt");
    ReportFileWriter writer = new ReportFileWriter(StandardCharsets.UTF_8, "\n");

    writer.open(output, null); // null charset -> configured UTF-8
    writer.accept("FALLBACK");
    writer.close();

    List<String> lines = Files.readAllLines(output, StandardCharsets.UTF_8);
    assertThat(lines).hasSize(1);
    assertThat(lines.get(0)).hasSize(133).startsWith("FALLBACK");
  }

  @Test
  void openPathThatCannotBeCreatedThrowsIoStatusExceptionForOpenWithCause(@TempDir Path dir) {
    // The parent directory does not exist, so newBufferedWriter raises an IOException.
    Path badPath = dir.resolve("missing-subdir").resolve("report.txt");
    ReportFileWriter writer = new ReportFileWriter();

    assertThatThrownBy(() -> writer.open(badPath))
        .isInstanceOfSatisfying(
            IoStatusException.class,
            ex -> {
              assertThat(ex.getFileName()).isEqualTo("TRANREPT");
              assertThat(ex.getOperation()).isEqualTo("OPEN");
              assertThat(ex.getCause()).isInstanceOf(IOException.class);
            });
  }

  /**
   * A {@link Writer} that can be configured to fail on {@code write} and/or {@code close}, used to
   * drive the {@link IoStatusException} re-throw paths without touching the file system.
   */
  private static final class FailingWriter extends Writer {

    private final boolean failOnWrite;
    private final boolean failOnClose;

    FailingWriter(boolean failOnWrite, boolean failOnClose) {
      this.failOnWrite = failOnWrite;
      this.failOnClose = failOnClose;
    }

    @Override
    public void write(char[] cbuf, int off, int len) throws IOException {
      if (failOnWrite) {
        throw new IOException("simulated write failure");
      }
    }

    @Override
    public void flush() {
      // no-op: the close-failure path is driven solely by close()
    }

    @Override
    public void close() throws IOException {
      if (failOnClose) {
        throw new IOException("simulated close failure");
      }
    }
  }
}
