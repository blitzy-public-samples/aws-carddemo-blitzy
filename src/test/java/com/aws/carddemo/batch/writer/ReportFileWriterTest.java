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

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.aws.carddemo.exception.IoStatusException;
import java.io.IOException;
import java.io.StringWriter;
import java.io.Writer;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Pure-POJO JUnit&nbsp;5 unit tests for {@link ReportFileWriter}, the physical {@code TRANREPT}
 * report sink migrated from the report side of {@code legacy/app/cbl/CBTRN03C.cbl} (paragraphs
 * {@code 0100-REPTFILE-OPEN}, {@code 1111-WRITE-REPORT-REC}, {@code 9100-REPTFILE-CLOSE}).
 *
 * <p>The legacy record is a fixed {@code 01 FD-REPTFILE-REC PIC X(133)} written to {@code SELECT
 * REPORT-FILE ASSIGN TO TRANREPT}, whose DD is {@code DCB=(LRECL=133,RECFM=FB,BLKSIZE=0)} in both
 * {@code legacy/app/jcl/TRANREPT.jcl} and {@code legacy/app/proc/TRANREPT.prc}. These tests pin the
 * parity invariants the report job depends on (Agent Action Plan &sect;0.4.1, &sect;0.6.4,
 * &sect;0.7.3):
 *
 * <ul>
 *   <li>every accepted line is defensively normalized to exactly {@code 133} characters — right
 *       space-padded when shorter, right-truncated when longer, and {@code 133} spaces when {@code
 *       null} — reproducing a COBOL {@code MOVE} into the {@code PIC X(133)} print record;
 *   <li>the record separator is the fixed literal {@code "\n"} (never {@link
 *       System#lineSeparator()}) so golden-file output is byte-identical across platforms;
 *   <li>any physical open/write {@link IOException} is re-thrown as an {@link IoStatusException}
 *       (operation {@code OPEN}/{@code WRITE}, status {@code "30"}), mirroring the COBOL
 *       "unexpected status &rarr; abend" path; and
 *   <li>{@link ReportFileWriter#close()} is idempotent.
 * </ul>
 *
 * <p>Expected padded/truncated values are re-derived independently here (via {@code " ".repeat(n)}
 * and exact substrings) rather than by importing {@code com.aws.carddemo.util.CobolStringUtils}, so
 * the normalization contract is verified against a second, hand-derived source of truth. The suite
 * uses no Spring context, Spring Batch, Testcontainers, Mockito, or AssertJ.
 */
class ReportFileWriterTest {

  /** The fixed COBOL report record length ({@code FD-REPTFILE-REC PIC X(133)}). */
  private static final int LRECL = 133;

  /**
   * Returns a string of exactly {@code n} ASCII spaces, used to re-derive the expected
   * right-padding independently of the production normalization helper.
   *
   * @param n the number of spaces
   * @return {@code n} space characters
   */
  private static String spaces(int n) {
    return " ".repeat(n);
  }

  // ---------------------------------------------------------------------------------------------
  // Defensive normalization (the core parity contract)
  // ---------------------------------------------------------------------------------------------

  @Test
  void constants_match_tranrept_contract() {
    assertEquals("TRANREPT", ReportFileWriter.DD_NAME);
    assertEquals(133, ReportFileWriter.LRECL);
  }

  @Test
  void accept_pads_short_line_to_133() {
    ReportFileWriter w = new ReportFileWriter();
    StringWriter sw = new StringWriter();
    w.open(sw);

    String line = "HELLO";
    w.accept(line);

    String expected = line + spaces(LRECL - line.length()) + "\n";
    assertEquals(expected, sw.toString());
    assertEquals(134, sw.toString().length());
    assertEquals(133, sw.toString().substring(0, LRECL).length());
    w.close();
  }

  @Test
  void accept_truncates_long_line_to_133() {
    ReportFileWriter w = new ReportFileWriter();
    StringWriter sw = new StringWriter();
    w.open(sw);

    String longLine = "A".repeat(200);
    w.accept(longLine);

    String expected = "A".repeat(LRECL) + "\n";
    assertEquals(expected, sw.toString());
    assertEquals(134, sw.toString().length());
    w.close();
  }

  @Test
  void accept_keeps_exact_133_line_unchanged() {
    ReportFileWriter w = new ReportFileWriter();
    StringWriter sw = new StringWriter();
    w.open(sw);

    String exact = "B".repeat(LRECL);
    w.accept(exact);

    assertEquals(exact + "\n", sw.toString());
    w.close();
  }

  @Test
  void accept_null_line_becomes_133_spaces() {
    ReportFileWriter w = new ReportFileWriter();
    StringWriter sw = new StringWriter();
    w.open(sw);

    w.accept(null);

    assertEquals(spaces(LRECL) + "\n", sw.toString());
    w.close();
  }

  @Test
  void getRecordsWritten_is_zero_on_fresh_open() {
    ReportFileWriter w = new ReportFileWriter();
    w.open(new StringWriter());

    assertEquals(0L, w.getRecordsWritten());
  }

  @Test
  void getRecordsWritten_increments_per_accept() {
    ReportFileWriter w = new ReportFileWriter();
    w.open(new StringWriter());

    w.accept("a");
    w.accept("b");
    w.accept("c");

    assertEquals(3L, w.getRecordsWritten());
  }

  @Test
  void getRecordsWritten_resets_on_reopen() {
    ReportFileWriter w = new ReportFileWriter();
    w.open(new StringWriter());
    w.accept("a");
    w.accept("b");
    assertEquals(2L, w.getRecordsWritten());

    // Re-opening onto a new stream resets the running counter to zero.
    w.open(new StringWriter());
    assertEquals(0L, w.getRecordsWritten());
  }

  @Test
  void null_constructor_arguments_coalesce_to_utf8_and_newline() {
    ReportFileWriter w = new ReportFileWriter(null, null);
    StringWriter sw = new StringWriter();
    w.open(sw);

    w.accept("Z");

    assertEquals("Z" + spaces(LRECL - 1) + "\n", sw.toString());
    assertEquals(134, sw.toString().length());
  }

  @Test
  void custom_line_separator_is_appended_after_each_record() {
    ReportFileWriter w = new ReportFileWriter(StandardCharsets.UTF_8, "\r\n");
    StringWriter sw = new StringWriter();
    w.open(sw);

    w.accept("Q");

    // 133 normalized record characters followed by the two-character CRLF separator.
    assertEquals("Q" + spaces(LRECL - 1) + "\r\n", sw.toString());
    assertEquals(135, sw.toString().length());
  }

  // ---------------------------------------------------------------------------------------------
  // Guard + abend parity
  // ---------------------------------------------------------------------------------------------

  @Test
  void accept_before_open_throws() {
    ReportFileWriter w = new ReportFileWriter();

    IoStatusException ex = assertThrows(IoStatusException.class, () -> w.accept("x"));

    assertEquals("TRANREPT", ex.getFileName());
    assertEquals("WRITE", ex.getOperation());
    assertEquals("30", ex.getFileStatus());
  }

  @Test
  void accept_wraps_io_exception_with_cause() {
    ReportFileWriter w = new ReportFileWriter();
    w.open(new FailingWriter());

    IoStatusException ex = assertThrows(IoStatusException.class, () -> w.accept("anything"));

    assertEquals("TRANREPT", ex.getFileName());
    assertEquals("WRITE", ex.getOperation());
    assertEquals("30", ex.getFileStatus());
    // FailingWriter throws a plain IOException("boom"); the abend wrapper chains it verbatim.
    assertEquals(IOException.class, ex.getCause().getClass());
    assertEquals("boom", ex.getCause().getMessage());
  }

  @Test
  void open_null_writer_throws() {
    ReportFileWriter w = new ReportFileWriter();

    IoStatusException ex = assertThrows(IoStatusException.class, () -> w.open((Writer) null));

    assertEquals("TRANREPT", ex.getFileName());
    assertEquals("OPEN", ex.getOperation());
    assertEquals("30", ex.getFileStatus());
  }

  // ---------------------------------------------------------------------------------------------
  // close() idempotency
  // ---------------------------------------------------------------------------------------------

  @Test
  void close_is_idempotent_without_open() {
    assertDoesNotThrow(new ReportFileWriter()::close);
  }

  @Test
  void close_is_idempotent_when_called_twice() {
    ReportFileWriter w = new ReportFileWriter();
    w.open(new StringWriter());
    w.accept("x");

    w.close();

    assertDoesNotThrow(w::close);
  }

  // ---------------------------------------------------------------------------------------------
  // open(Path) byte-level fidelity (@TempDir)
  // ---------------------------------------------------------------------------------------------

  @Test
  void open_path_writes_padded_bytes(@TempDir Path dir) throws IOException {
    Path f = dir.resolve("tranrept.txt");
    ReportFileWriter w = new ReportFileWriter();

    w.open(f);
    w.accept("LINE");
    w.close();

    byte[] actual = Files.readAllBytes(f);
    byte[] expected = ("LINE" + spaces(LRECL - 4) + "\n").getBytes(StandardCharsets.UTF_8);
    assertArrayEquals(expected, actual);
    assertEquals(134, actual.length);
  }

  @Test
  void open_path_with_explicit_charset_writes_record(@TempDir Path dir) throws IOException {
    Path f = dir.resolve("ascii.txt");
    // The constructor charset differs from the explicit open charset, proving the
    // open(Path, Charset) argument wins.
    ReportFileWriter w = new ReportFileWriter(StandardCharsets.ISO_8859_1, "\n");

    w.open(f, StandardCharsets.US_ASCII);
    w.accept("ASCII");
    w.close();

    byte[] actual = Files.readAllBytes(f);
    byte[] expected = ("ASCII" + spaces(LRECL - 5) + "\n").getBytes(StandardCharsets.US_ASCII);
    assertArrayEquals(expected, actual);
  }

  @Test
  void open_path_with_null_charset_falls_back_to_configured(@TempDir Path dir) throws IOException {
    Path f = dir.resolve("fallback.txt");
    ReportFileWriter w = new ReportFileWriter(StandardCharsets.UTF_8, "\n");

    w.open(f, null); // null charset -> configured UTF-8
    w.accept("FALLBACK");
    w.close();

    byte[] actual = Files.readAllBytes(f);
    byte[] expected = ("FALLBACK" + spaces(LRECL - 8) + "\n").getBytes(StandardCharsets.UTF_8);
    assertArrayEquals(expected, actual);
  }

  @Test
  void open_path_truncates_existing_file_on_reopen(@TempDir Path dir) throws IOException {
    Path f = dir.resolve("reused.txt");

    ReportFileWriter first = new ReportFileWriter();
    first.open(f);
    first.accept("first-run-1");
    first.accept("first-run-2");
    first.close();
    assertEquals(2 * (LRECL + 1), Files.readAllBytes(f).length);

    // A fresh open of the same path must TRUNCATE_EXISTING, not append.
    ReportFileWriter second = new ReportFileWriter();
    second.open(f);
    second.accept("second-run-1");
    second.close();
    assertEquals(LRECL + 1, Files.readAllBytes(f).length);
  }

  @Test
  void open_path_that_cannot_be_created_throws_open(@TempDir Path dir) {
    // The parent directory does not exist, so newBufferedWriter raises an IOException that the
    // writer must translate into an OPEN/"30" abend.
    Path badPath = dir.resolve("missing-subdir").resolve("report.txt");
    ReportFileWriter w = new ReportFileWriter();

    IoStatusException ex = assertThrows(IoStatusException.class, () -> w.open(badPath));

    assertEquals("TRANREPT", ex.getFileName());
    assertEquals("OPEN", ex.getOperation());
    assertEquals("30", ex.getFileStatus());
  }

  // ---------------------------------------------------------------------------------------------
  // Deterministic golden-file parity (always runs; no skip)
  // ---------------------------------------------------------------------------------------------

  @Test
  void golden_report_matches() throws Exception {
    URL url = getClass().getResource("/golden/reports/daily-transaction-report-basic.txt");
    assertNotNull(url, "golden report fixture must ship under /golden/reports/");

    byte[] golden = Files.readAllBytes(Path.of(url.toURI()));
    // Every record is exactly LRECL characters plus the one-byte "\n" separator.
    assertEquals(0, golden.length % (LRECL + 1));
    int records = golden.length / (LRECL + 1);
    for (int i = 0; i < records; i++) {
      assertEquals('\n', golden[i * (LRECL + 1) + LRECL]);
    }
  }

  /**
   * A {@link Writer} whose {@code write(char[], int, int)} always fails, used to drive the {@link
   * IoStatusException} re-throw path in {@link ReportFileWriter#accept(String)} without touching
   * the file system. {@code ReportFileWriter} writes via {@code Writer.write(String)}, which
   * delegates to {@code write(char[], int, int)}, so overriding only that method is sufficient;
   * {@code flush()} and {@code close()} are no-ops.
   */
  private static final class FailingWriter extends Writer {

    @Override
    public void write(char[] cbuf, int off, int len) throws IOException {
      throw new IOException("boom");
    }

    @Override
    public void flush() {
      // no-op
    }

    @Override
    public void close() {
      // no-op
    }
  }
}
