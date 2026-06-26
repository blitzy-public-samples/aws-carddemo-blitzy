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
 * Pure-POJO JUnit 5 unit tests for {@link StatementFileWriter}, the physical {@code STMTFILE}
 * plain-text output sink migrated from the statement side of the legacy batch program {@code
 * legacy/app/cbl/CBSTM03A.CBL} (DD {@code STMTFILE}, {@code RECFM=FB LRECL=80} in {@code
 * legacy/app/jcl/CREASTMT.JCL} STEP040).
 *
 * <p>The fixed COBOL record these tests pin is {@code 01 FD-STMTFILE-REC PIC X(80)}: each {@code
 * WRITE FD-STMTFILE-REC FROM ST-LINEn} emits exactly eighty bytes (right-space-padded when shorter,
 * right-truncated when longer). The writer reproduces that contract by normalizing every accepted
 * line to exactly {@value StatementFileWriter#LRECL} characters and appending the deterministic
 * {@code "\n"} separator, so the produced file is byte-faithful and platform-stable for golden-file
 * parity (Agent Action Plan &sect;0.4.1, &sect;0.6.1, &sect;0.7.3).
 *
 * <p>The suite is intentionally framework-light: there is no Spring context, no Spring Batch, no
 * database, and no Testcontainers or Mockito. The writer is exercised as a plain POJO over an
 * in-memory {@link StringWriter} (and a {@link TempDir}-backed real file for the {@code open(Path)}
 * lifecycle). The abend-parity failure path is driven by the tiny {@link FailingWriter} stub so the
 * translation of an {@link IOException} into an {@link IoStatusException} is asserted directly
 * (&sect;0.6.4, &sect;0.6.6).
 *
 * <p>The expected padding and truncation vectors are <em>re-derived independently</em> here (via
 * {@link #spaces(int)} and {@code String} slicing) rather than by importing the production {@code
 * com.aws.carddemo.util.CobolStringUtils}, so this test is an independent oracle of the eighty-byte
 * record contract. Method names use underscores and rely on the platform-default {@code
 * ReplaceUnderscores} display-name generator (no {@code @DisplayName}).
 */
class StatementFileWriterTest {

  /**
   * The fixed logical record length of the {@code STMTFILE} dataset ({@code LRECL=80} in {@code
   * CREASTMT.JCL} STEP040, matching {@code 01 FD-STMTFILE-REC PIC X(80)}). Declared locally so the
   * expectations remain independent of the production constant.
   */
  private static final int LRECL = 80;

  // ---------------------------------------------------------------------------
  // accept(String) — defensive fixed-width normalization to exactly 80 chars
  // ---------------------------------------------------------------------------

  @Test
  void accept_pads_short_line_to_80() {
    StringWriter sink = new StringWriter();
    StatementFileWriter writer = new StatementFileWriter();
    writer.open(sink);

    writer.accept("HELLO");

    String produced = sink.toString();
    String expected = "HELLO" + spaces(75) + "\n";
    assertEquals(expected, produced);
    assertEquals(LRECL + 1, produced.length());
    // The single separator sits at index 80, so the record portion is exactly 80 characters.
    assertEquals(LRECL, produced.indexOf('\n'));
  }

  @Test
  void accept_truncates_long_line_to_80() {
    StringWriter sink = new StringWriter();
    StatementFileWriter writer = new StatementFileWriter();
    writer.open(sink);

    writer.accept("A".repeat(120));

    String produced = sink.toString();
    String expected = "A".repeat(LRECL) + "\n";
    assertEquals(expected, produced);
    assertEquals(LRECL + 1, produced.length());
  }

  @Test
  void accept_keeps_exact_80_line_unchanged() {
    StringWriter sink = new StringWriter();
    StatementFileWriter writer = new StatementFileWriter();
    writer.open(sink);

    String exact = "B".repeat(LRECL);
    writer.accept(exact);

    assertEquals(exact + "\n", sink.toString());
  }

  @Test
  void accept_null_line_becomes_80_spaces() {
    StringWriter sink = new StringWriter();
    StatementFileWriter writer = new StatementFileWriter();
    writer.open(sink);

    writer.accept(null);

    assertEquals(spaces(LRECL) + "\n", sink.toString());
  }

  @Test
  void getRecordsWritten_starts_at_zero() {
    StatementFileWriter writer = new StatementFileWriter();
    writer.open(new StringWriter());

    assertEquals(0L, writer.getRecordsWritten());
  }

  @Test
  void getRecordsWritten_increments_per_accept() {
    StatementFileWriter writer = new StatementFileWriter();
    writer.open(new StringWriter());

    writer.accept("ONE");
    writer.accept("TWO");
    writer.accept("THREE");

    assertEquals(3L, writer.getRecordsWritten());
  }

  // ---------------------------------------------------------------------------
  // Not-open guard and IOException -> IoStatusException abend parity
  // ---------------------------------------------------------------------------

  @Test
  void accept_before_open_throws() {
    StatementFileWriter writer = new StatementFileWriter();

    IoStatusException ex = assertThrows(IoStatusException.class, () -> writer.accept("anything"));
    assertEquals("STMTFILE", ex.getFileName());
    assertEquals("WRITE", ex.getOperation());
  }

  @Test
  void accept_wraps_io_exception_with_cause() {
    StatementFileWriter writer = new StatementFileWriter();
    writer.open(new FailingWriter());

    IoStatusException ex = assertThrows(IoStatusException.class, () -> writer.accept("DATA"));
    assertEquals("STMTFILE", ex.getFileName());
    assertEquals("WRITE", ex.getOperation());
    // The underlying IOException is chained verbatim (4-arg IoStatusException constructor).
    assertEquals(IOException.class, ex.getCause().getClass());
    assertEquals("boom", ex.getCause().getMessage());
  }

  @Test
  void open_null_writer_throws() {
    StatementFileWriter writer = new StatementFileWriter();

    // The (Writer) cast disambiguates open(Writer) from open(Path) for the null argument.
    IoStatusException ex = assertThrows(IoStatusException.class, () -> writer.open((Writer) null));
    assertEquals("STMTFILE", ex.getFileName());
    assertEquals("OPEN", ex.getOperation());
  }

  // ---------------------------------------------------------------------------
  // close() idempotency (CLOSE STMT-FILE)
  // ---------------------------------------------------------------------------

  @Test
  void close_is_idempotent_without_open() {
    StatementFileWriter writer = new StatementFileWriter();

    assertDoesNotThrow(writer::close);
  }

  @Test
  void close_is_idempotent_when_called_twice() {
    StatementFileWriter writer = new StatementFileWriter();
    writer.open(new StringWriter());
    writer.accept("LINE");

    writer.close();
    assertDoesNotThrow(writer::close);
  }

  // ---------------------------------------------------------------------------
  // open(Path) byte-level fidelity (OPEN OUTPUT STMT-FILE)
  // ---------------------------------------------------------------------------

  @Test
  void open_path_writes_padded_bytes(@TempDir Path dir) throws IOException {
    StatementFileWriter writer = new StatementFileWriter();
    Path file = dir.resolve("stmtfile.txt");

    writer.open(file);
    writer.accept("LINE");
    writer.close();

    byte[] expected = ("LINE" + spaces(76) + "\n").getBytes(StandardCharsets.UTF_8);
    byte[] actual = Files.readAllBytes(file);
    assertArrayEquals(expected, actual);
    assertEquals(LRECL + 1, actual.length);
  }

  // ---------------------------------------------------------------------------
  // Deterministic golden-file round-trip parity (always runs; no skip)
  // ---------------------------------------------------------------------------

  @Test
  void golden_statement_reproduced_byte_for_byte(@TempDir Path dir) throws Exception {
    // Canonical STMTFILE golden reference shipped under src/test/resources — the SAME fixture the
    // statement-generation job golden parity test (StatementGenerationJobConfigTest GOLDEN_TXT)
    // byte-compares against. Every record is exactly LRECL (80) chars then the fixed "\n" separator
    // (an exact multiple of 81 bytes).
    URL url = getClass().getResource("/golden/statements/acct-00000000050.statement.txt");
    assertNotNull(url, "canonical golden statement fixture must ship under /golden/statements/");

    byte[] golden = Files.readAllBytes(Path.of(url.toURI()));
    assertEquals(0, golden.length % (LRECL + 1));
    int recordCount = golden.length / (LRECL + 1);
    // The fixture is pure 7-bit ASCII, so one byte equals one char and fixed-offset slicing is
    // exact.
    String goldenText = new String(golden, StandardCharsets.UTF_8);

    // Round-trip parity: feed each fixed 80-char record back through the production writer and
    // prove
    // it reproduces the canonical STMTFILE bytes EXACTLY. Fixed-offset reads mirror a RECFM=FB
    // reader and are immune to any byte resembling a line terminator inside a record image.
    Path file = dir.resolve("stmtfile.txt");
    StatementFileWriter writer = new StatementFileWriter();
    writer.open(file);
    for (int i = 0; i < recordCount; i++) {
      int base = i * (LRECL + 1);
      assertEquals('\n', goldenText.charAt(base + LRECL), "record separator must sit on the grid");
      writer.accept(goldenText.substring(base, base + LRECL));
    }
    writer.close();

    assertArrayEquals(golden, Files.readAllBytes(file));
    assertEquals(recordCount, (int) writer.getRecordsWritten());
  }

  // ---------------------------------------------------------------------------
  // Helpers
  // ---------------------------------------------------------------------------

  /**
   * Returns a string of exactly {@code n} space characters, re-deriving the COBOL {@code SPACES}
   * right-padding independently of the production {@code CobolStringUtils}.
   *
   * @param n the number of spaces to produce (non-negative)
   * @return a string consisting of {@code n} space characters
   */
  private static String spaces(int n) {
    return " ".repeat(n);
  }

  /**
   * Minimal {@link Writer} stub whose {@code write(char[], int, int)} always throws an {@link
   * IOException} carrying the fixed message {@code "boom"}, used to drive the abend-parity
   * translation into an {@link IoStatusException} without any mocking framework. {@code flush()}
   * and {@code close()} are deliberate no-ops.
   */
  private static final class FailingWriter extends Writer {

    @Override
    public void write(char[] cbuf, int off, int len) throws IOException {
      throw new IOException("boom");
    }

    @Override
    public void flush() {
      // no-op: failure is driven solely by write(char[], int, int)
    }

    @Override
    public void close() {
      // no-op: failure is driven solely by write(char[], int, int)
    }
  }
}
