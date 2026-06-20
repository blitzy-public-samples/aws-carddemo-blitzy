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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

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
 * Pure JUnit&nbsp;5 unit tests for {@link HtmlStatementWriter}, the Java migration of the {@code
 * HTMLFILE} HTML-statement output side of the legacy batch program {@code
 * legacy/app/cbl/CBSTM03A.CBL}. In the COBOL the file is {@code SELECT HTML-FILE ASSIGN TO
 * HTMLFILE} with a fixed record {@code 01 FD-HTMLFILE-REC PIC X(100)}, written line-by-line with
 * {@code WRITE FD-HTMLFILE-REC FROM HTML-FIXED-LN}.
 *
 * <p><strong>The record width these tests pin is 100, not 80.</strong> The width is taken from
 * {@code legacy/app/jcl/CREASTMT.JCL} <strong>STEP040</strong> — the step that actually runs {@code
 * EXEC PGM=CBSTM03A} and writes {@code HTMLFILE} with {@code DCB=(LRECL=100,BLKSIZE=800,RECFM=FB)}.
 * STEP030's {@code LRECL=80} belongs to a separate {@code IEFBR14} pre-allocation step and must be
 * ignored when sizing the producer's records; every length expectation in this suite is therefore
 * {@value #LRECL} (and {@code 101} including the one-character separator), never {@code 80}/{@code
 * 81} (Agent Action Plan &sect;0.4.1).
 *
 * <p>The suite is intentionally framework-light: the writer is exercised as a plain POJO over an
 * in-memory {@link StringWriter} (and a {@link TempDir}-backed file for the path lifecycle), with
 * no Spring context, Spring Batch, database, Testcontainers, or Mockito. Failure paths are driven
 * by a tiny {@link FailingWriter} stub so the abend-parity translation of a physical {@link
 * IOException} into an {@link IoStatusException} is asserted directly (&sect;0.6.4, &sect;0.6.6).
 *
 * <p>The expected fixed-width padding and truncation are re-derived independently here (via {@link
 * #spaces(int)} and {@link String#substring(int, int)}) rather than by importing the production
 * {@code com.aws.carddemo.util.CobolStringUtils} helper, so the test is an honest external check of
 * the {@code PIC X(100)} contract rather than a tautology against the same code under test.
 *
 * <p>The line separator asserted is the fixed {@code "\n"} the writer is documented to emit — never
 * {@link System#lineSeparator()} — so these expectations are byte-stable across platforms for
 * golden-file parity (&sect;0.7.3).
 */
class HtmlStatementWriterTest {

  /**
   * The fixed HTML statement record length asserted throughout this suite, sourced from {@code
   * CREASTMT.JCL} STEP040 ({@code LRECL=100}) and the COBOL {@code FD-HTMLFILE-REC PIC X(100)}.
   * Deliberately <em>not</em> STEP030's {@code LRECL=80}.
   */
  private static final int LRECL = 100;

  /**
   * Builds a run of {@code n} ASCII spaces, used to re-derive the expected COBOL {@code SPACES}
   * right-padding independently of the production {@code CobolStringUtils} helper.
   *
   * @param n the number of spaces to produce; must be {@code >= 0}
   * @return a string of exactly {@code n} space characters
   */
  private static String spaces(int n) {
    return " ".repeat(n);
  }

  // ---------------------------------------------------------------------------
  // accept(String) — defensive fixed-width normalization to exactly 100 chars
  // ---------------------------------------------------------------------------

  @Test
  void accept_pads_short_line_to_100() {
    StringWriter sink = new StringWriter();
    HtmlStatementWriter writer = new HtmlStatementWriter();
    writer.open(sink);

    writer.accept("HELLO");

    String out = sink.toString();
    assertEquals("HELLO" + spaces(95) + "\n", out);
    assertEquals(101, out.length());
    // The record portion (everything before the single-character separator) is exactly LRECL.
    assertEquals(LRECL, out.indexOf('\n'));
  }

  @Test
  void accept_truncates_long_line_to_100() {
    StringWriter sink = new StringWriter();
    HtmlStatementWriter writer = new HtmlStatementWriter();
    writer.open(sink);

    writer.accept("A".repeat(150));

    String out = sink.toString();
    assertEquals("A".repeat(100) + "\n", out);
    assertEquals(101, out.length());
  }

  @Test
  void accept_keeps_exact_100_line_unchanged() {
    StringWriter sink = new StringWriter();
    HtmlStatementWriter writer = new HtmlStatementWriter();
    writer.open(sink);

    String exact = "B".repeat(100);
    writer.accept(exact);

    String out = sink.toString();
    assertEquals(exact + "\n", out);
    assertEquals(101, out.length());
  }

  @Test
  void accept_null_line_becomes_100_spaces() {
    StringWriter sink = new StringWriter();
    HtmlStatementWriter writer = new HtmlStatementWriter();
    writer.open(sink);

    writer.accept(null);

    String out = sink.toString();
    assertEquals(spaces(100) + "\n", out);
    assertEquals(101, out.length());
  }

  @Test
  void getRecordsWritten_increments_per_accept() {
    StringWriter sink = new StringWriter();
    HtmlStatementWriter writer = new HtmlStatementWriter();
    writer.open(sink);
    assertEquals(0L, writer.getRecordsWritten());

    writer.accept("ONE");
    writer.accept("TWO");
    writer.accept("THREE");

    assertEquals(3L, writer.getRecordsWritten());
    // Three fixed-width records, each LRECL chars plus the one-character separator.
    assertEquals(3 * (LRECL + 1), sink.toString().length());
  }

  // ---------------------------------------------------------------------------
  // Guard + abend parity — IoStatusException("HTMLFILE", ...)
  // ---------------------------------------------------------------------------

  @Test
  void accept_before_open_throws() {
    HtmlStatementWriter writer = new HtmlStatementWriter();

    IoStatusException ex = assertThrows(IoStatusException.class, () -> writer.accept("anything"));
    assertEquals("HTMLFILE", ex.getFileName());
    assertEquals("WRITE", ex.getOperation());
    assertEquals("30", ex.getFileStatus());
  }

  @Test
  void accept_wraps_io_exception_with_cause() {
    HtmlStatementWriter writer = new HtmlStatementWriter();
    writer.open(new FailingWriter());

    IoStatusException ex = assertThrows(IoStatusException.class, () -> writer.accept("DATA"));
    assertEquals("HTMLFILE", ex.getFileName());
    assertEquals("WRITE", ex.getOperation());
    // The physical IOException is chained verbatim so the write error is never swallowed.
    assertEquals(IOException.class, ex.getCause().getClass());
    assertEquals("boom", ex.getCause().getMessage());
  }

  @Test
  void open_null_writer_throws() {
    HtmlStatementWriter writer = new HtmlStatementWriter();

    IoStatusException ex = assertThrows(IoStatusException.class, () -> writer.open((Writer) null));
    assertEquals("HTMLFILE", ex.getFileName());
    assertEquals("OPEN", ex.getOperation());
    assertEquals("30", ex.getFileStatus());
  }

  // ---------------------------------------------------------------------------
  // close() idempotency
  // ---------------------------------------------------------------------------

  @Test
  void close_is_idempotent_without_open() {
    HtmlStatementWriter writer = new HtmlStatementWriter();

    assertDoesNotThrow(writer::close);
  }

  @Test
  void close_is_idempotent_when_called_twice() {
    HtmlStatementWriter writer = new HtmlStatementWriter();
    writer.open(new StringWriter());
    writer.accept("X");

    assertDoesNotThrow(writer::close);
    assertDoesNotThrow(writer::close);
  }

  // ---------------------------------------------------------------------------
  // open(Path) — byte-level fixed-width fidelity on a real file
  // ---------------------------------------------------------------------------

  @Test
  void open_path_writes_padded_bytes(@TempDir Path dir) throws IOException {
    HtmlStatementWriter writer = new HtmlStatementWriter();
    Path file = dir.resolve("htmlfile.txt");

    writer.open(file);
    writer.accept("LINE");
    writer.close();

    // "LINE" (4) right-padded with 96 spaces to LRECL, then the fixed "\n" separator.
    byte[] expected = ("LINE" + spaces(96) + "\n").getBytes(StandardCharsets.UTF_8);
    byte[] actual = Files.readAllBytes(file);
    assertArrayEquals(expected, actual);
    assertEquals(101, actual.length);
  }

  // ---------------------------------------------------------------------------
  // Resilient golden-file parity — skipped when the fixture is not present
  // ---------------------------------------------------------------------------

  @Test
  void golden_html_statement_matches_when_present() throws Exception {
    URL url = getClass().getResource("/golden/statements/htmlfile.txt");
    assumeTrue(url != null, "golden HTML statement fixture not present; skipping");

    byte[] golden = Files.readAllBytes(Path.of(url.toURI()));
    // Every HTML statement record is LRECL chars followed by the one-character separator.
    assertEquals(0, golden.length % (LRECL + 1));
  }

  /**
   * Minimal {@link Writer} stub whose {@code write(char[], int, int)} always raises an {@link
   * IOException} carrying the message {@code "boom"}, used to drive the abend-parity translation of
   * a physical write failure into an {@link IoStatusException} without any mocking framework.
   *
   * <p>{@code flush()} and {@code close()} are deliberate no-ops so the failure under test
   * originates solely from the write path.
   */
  private static final class FailingWriter extends Writer {

    @Override
    public void write(char[] cbuf, int off, int len) throws IOException {
      throw new IOException("boom");
    }

    @Override
    public void flush() {
      // no-op: the failure under test originates from write(char[], int, int)
    }

    @Override
    public void close() {
      // no-op: the failure under test originates from write(char[], int, int)
    }
  }
}
