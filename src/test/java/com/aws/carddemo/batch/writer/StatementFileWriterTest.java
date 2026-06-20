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
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import com.aws.carddemo.exception.IoStatusException;
import java.io.Closeable;
import java.io.IOException;
import java.io.StringWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.function.Consumer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Pure JUnit&nbsp;5 + AssertJ unit tests for {@link StatementFileWriter}, the Java migration of the
 * {@code STMTFILE} plain-text output side of the legacy batch program {@code
 * legacy/app/cbl/CBSTM03A.CBL} (DD {@code STMTFILE}, {@code RECFM=FB LRECL=80} in {@code
 * legacy/app/jcl/CREASTMT.JCL} STEP040).
 *
 * <p>The fixed COBOL record these tests pin is {@code 01 FD-STMTFILE-REC PIC X(80)}: every {@code
 * WRITE FD-STMTFILE-REC FROM ST-LINEn} emits exactly eighty bytes (right-space-padded or
 * right-truncated). The writer reproduces that contract by normalizing each accepted line through
 * {@link com.aws.carddemo.util.CobolStringUtils#fixedWidth(String, int)} and appending a
 * deterministic {@code "\n"} separator, so the produced file is byte-faithful and platform-stable
 * for golden-file parity (Agent Action Plan &sect;0.6.1, &sect;0.7.3).
 *
 * <p>The suite is intentionally framework-light: the writer is exercised as a plain POJO over an
 * in-memory {@link StringWriter} (and a {@link TempDir}-backed file for the path lifecycle), with
 * no Spring context, database, Testcontainers, or Mockito. Failure paths are driven by a tiny
 * {@link FailingWriter} stub so the abend-parity translation of an {@link IOException} into an
 * {@link IoStatusException} (carrying {@link IoStatusException#BATCH_ABEND_CODE} = {@code 999}) is
 * asserted directly (&sect;0.6.6, &sect;0.1.2).
 */
@DisplayName("StatementFileWriter — STMTFILE PIC X(80) fixed-width sink (CBSTM03A)")
class StatementFileWriterTest {

  /** Classpath location of the golden plain-text statement fixture asserted for byte parity. */
  private static final String GOLDEN_TXT_RESOURCE =
      "/golden/statements/acct-00000000050.statement.txt";

  /** The published MD5 of the golden fixture (see the fixture folder README). */
  private static final String GOLDEN_TXT_MD5 = "b361344c8174e5e9aac060149f28f374";

  // ---------------------------------------------------------------------------
  // Type contract — usable directly as the stmtSink and in try-with-resources
  // ---------------------------------------------------------------------------

  @Test
  @DisplayName("implements Consumer<String> and Closeable")
  void implements_consumer_and_closeable() {
    StatementFileWriter writer = new StatementFileWriter();
    // Must be assignable to the exact sink type StatementGenerationService.run(...) expects.
    Consumer<String> asSink = writer;
    Closeable asCloseable = writer;
    assertThat(asSink).isSameAs(writer);
    assertThat(asCloseable).isSameAs(writer);
  }

  @Test
  @DisplayName("exposes the STMTFILE DD name and LRECL=80 contract constants")
  void exposes_contract_constants() {
    assertThat(StatementFileWriter.DD_NAME).isEqualTo("STMTFILE");
    assertThat(StatementFileWriter.LRECL).isEqualTo(80);
  }

  // ---------------------------------------------------------------------------
  // accept(String) — defensive fixed-width normalization to exactly 80 chars
  // ---------------------------------------------------------------------------

  @Test
  @DisplayName("a short line is right-space-padded to exactly 80 chars plus the separator")
  void short_line_is_padded_to_eighty() {
    StringWriter sink = new StringWriter();
    StatementFileWriter writer = new StatementFileWriter();
    writer.open(sink);

    writer.accept("HELLO");

    String out = sink.toString();
    assertThat(out).hasSize(81); // 80 record + 1 separator
    assertThat(out).isEqualTo("HELLO" + " ".repeat(75) + "\n");
    assertThat(out.substring(0, 80)).hasSize(80);
    assertThat(out.charAt(80)).isEqualTo('\n');
  }

  @Test
  @DisplayName("a line longer than 80 chars is right-truncated to 80 plus the separator")
  void over_length_line_is_truncated_to_eighty() {
    StringWriter sink = new StringWriter();
    StatementFileWriter writer = new StatementFileWriter();
    writer.open(sink);

    String tooLong = "X".repeat(100);
    writer.accept(tooLong);

    String out = sink.toString();
    assertThat(out).hasSize(81);
    assertThat(out).isEqualTo("X".repeat(80) + "\n");
  }

  @Test
  @DisplayName("an exactly-80-char line is written unchanged plus the separator")
  void exactly_eighty_line_is_unchanged() {
    StringWriter sink = new StringWriter();
    StatementFileWriter writer = new StatementFileWriter();
    writer.open(sink);

    String exact = "Z".repeat(80);
    writer.accept(exact);

    String out = sink.toString();
    assertThat(out).isEqualTo(exact + "\n");
    assertThat(out.substring(0, 80)).isEqualTo(exact);
  }

  @Test
  @DisplayName("accept(null) is rendered as 80 spaces (COBOL SPACES) plus the separator")
  void null_line_is_eighty_spaces() {
    StringWriter sink = new StringWriter();
    StatementFileWriter writer = new StatementFileWriter();
    writer.open(sink);

    writer.accept(null);

    String out = sink.toString();
    assertThat(out).isEqualTo(" ".repeat(80) + "\n");
  }

  @Test
  @DisplayName("getRecordsWritten() starts at zero and increments once per accepted line")
  void records_written_increments_per_line() {
    StringWriter sink = new StringWriter();
    StatementFileWriter writer = new StatementFileWriter();
    writer.open(sink);
    assertThat(writer.getRecordsWritten()).isZero();

    writer.accept("ONE");
    writer.accept("TWO");
    writer.accept("THREE");

    assertThat(writer.getRecordsWritten()).isEqualTo(3L);
    // Three fixed-width records, each 80 + 1 separator.
    assertThat(sink.toString()).hasSize(3 * 81);
  }

  @Test
  @DisplayName("a custom line separator and charset are honored")
  void custom_separator_and_charset_are_honored() {
    StringWriter sink = new StringWriter();
    StatementFileWriter writer = new StatementFileWriter(StandardCharsets.US_ASCII, "\r\n");
    writer.open(sink);

    writer.accept("ABC");

    assertThat(sink.toString()).isEqualTo("ABC" + " ".repeat(77) + "\r\n");
  }

  @Test
  @DisplayName("a null charset / separator constructor coalesces to UTF-8 and \"\\n\"")
  void null_constructor_args_coalesce_to_defaults() {
    StringWriter sink = new StringWriter();
    StatementFileWriter writer = new StatementFileWriter(null, null);
    writer.open(sink);

    writer.accept("DEF");

    assertThat(sink.toString()).isEqualTo("DEF" + " ".repeat(77) + "\n");
  }

  // ---------------------------------------------------------------------------
  // Open lifecycle and not-open guard
  // ---------------------------------------------------------------------------

  @Test
  @DisplayName("accept() before open() abends with IoStatusException(STMTFILE, WRITE, 30)")
  void accept_before_open_throws_io_status_exception() {
    StatementFileWriter writer = new StatementFileWriter();

    assertThatExceptionOfType(IoStatusException.class)
        .isThrownBy(() -> writer.accept("anything"))
        .satisfies(
            ex -> {
              assertThat(ex.getFileName()).isEqualTo("STMTFILE");
              assertThat(ex.getOperation()).isEqualTo("WRITE");
              assertThat(ex.getFileStatus()).isEqualTo("30");
            });
  }

  @Test
  @DisplayName("open(Writer) with null abends with IoStatusException(STMTFILE, OPEN, 30)")
  void open_null_writer_throws_io_status_exception() {
    StatementFileWriter writer = new StatementFileWriter();

    assertThatExceptionOfType(IoStatusException.class)
        .isThrownBy(() -> writer.open((Writer) null))
        .satisfies(
            ex -> {
              assertThat(ex.getFileName()).isEqualTo("STMTFILE");
              assertThat(ex.getOperation()).isEqualTo("OPEN");
              assertThat(ex.getFileStatus()).isEqualTo("30");
            });
  }

  @Test
  @DisplayName("open(Path) with null path abends with IoStatusException(STMTFILE, OPEN, 30)")
  void open_null_path_throws_io_status_exception() {
    StatementFileWriter writer = new StatementFileWriter();

    assertThatExceptionOfType(IoStatusException.class)
        .isThrownBy(() -> writer.open((Path) null))
        .satisfies(ex -> assertThat(ex.getOperation()).isEqualTo("OPEN"));
  }

  @Test
  @DisplayName("open(Path) round-trips 80-char records to a real file (OPEN OUTPUT semantics)")
  void open_path_round_trip(@TempDir Path tempDir) throws IOException {
    Path file = tempDir.resolve("statement.ps");
    StatementFileWriter writer = new StatementFileWriter();

    writer.open(file);
    writer.accept("LINE-A");
    writer.accept("LINE-B");
    writer.close();

    List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
    assertThat(lines).hasSize(2);
    assertThat(lines).allSatisfy(line -> assertThat(line).hasSize(80));
    assertThat(lines.get(0)).isEqualTo("LINE-A" + " ".repeat(74));
    assertThat(lines.get(1)).isEqualTo("LINE-B" + " ".repeat(74));
    assertThat(writer.getRecordsWritten()).isEqualTo(2L);
  }

  @Test
  @DisplayName("open(Path) truncates an existing file (TRUNCATE_EXISTING)")
  void open_path_truncates_existing(@TempDir Path tempDir) throws IOException {
    Path file = tempDir.resolve("statement.ps");
    Files.writeString(file, "STALE PREVIOUS CONTENT THAT MUST BE WIPED\n", StandardCharsets.UTF_8);

    StatementFileWriter writer = new StatementFileWriter();
    writer.open(file);
    writer.accept("FRESH");
    writer.close();

    String content = Files.readString(file, StandardCharsets.UTF_8);
    assertThat(content).isEqualTo("FRESH" + " ".repeat(75) + "\n");
  }

  @Test
  @DisplayName("open(Path, null charset) falls back to the writer's configured charset")
  void open_path_null_charset_falls_back_to_configured(@TempDir Path tempDir) throws IOException {
    Path file = tempDir.resolve("statement.ps");
    StatementFileWriter writer = new StatementFileWriter(StandardCharsets.UTF_8, "\n");

    writer.open(file, null);
    writer.accept("CS");
    writer.close();

    assertThat(Files.readString(file, StandardCharsets.UTF_8))
        .isEqualTo("CS" + " ".repeat(78) + "\n");
  }

  @Test
  @DisplayName("an open IOException is rethrown as IoStatusException(OPEN, 30) chaining the cause")
  void open_path_io_failure_translates_to_io_status_exception(@TempDir Path tempDir) {
    // The parent directory does not exist, so Files.newBufferedWriter raises an IOException.
    Path unreachable = tempDir.resolve("missing-directory").resolve("statement.ps");
    StatementFileWriter writer = new StatementFileWriter();

    assertThatExceptionOfType(IoStatusException.class)
        .isThrownBy(() -> writer.open(unreachable))
        .satisfies(
            ex -> {
              assertThat(ex.getFileName()).isEqualTo("STMTFILE");
              assertThat(ex.getOperation()).isEqualTo("OPEN");
              assertThat(ex.getFileStatus()).isEqualTo("30");
              assertThat(ex.getCause()).isInstanceOf(IOException.class);
            });
  }

  // ---------------------------------------------------------------------------
  // Write/close failure paths — IOException -> IoStatusException (abend parity)
  // ---------------------------------------------------------------------------

  @Test
  @DisplayName("a write IOException is rethrown as IoStatusException(WRITE, 30) chaining the cause")
  void write_failure_translates_to_io_status_exception() {
    FailingWriter failing = new FailingWriter(true, false);
    StatementFileWriter writer = new StatementFileWriter();
    writer.open(failing);

    assertThatExceptionOfType(IoStatusException.class)
        .isThrownBy(() -> writer.accept("DATA"))
        .satisfies(
            ex -> {
              assertThat(ex.getFileName()).isEqualTo("STMTFILE");
              assertThat(ex.getOperation()).isEqualTo("WRITE");
              assertThat(ex.getFileStatus()).isEqualTo("30");
              assertThat(ex.getCause()).isInstanceOf(IOException.class);
            });
    // A failed write must not be counted as a successful record.
    assertThat(writer.getRecordsWritten()).isZero();
  }

  @Test
  @DisplayName("a close IOException is rethrown as IoStatusException(CLOSE, 30) chaining the cause")
  void close_failure_translates_to_io_status_exception() {
    FailingWriter failing = new FailingWriter(false, true);
    StatementFileWriter writer = new StatementFileWriter();
    writer.open(failing);
    writer.accept("OK");

    assertThatExceptionOfType(IoStatusException.class)
        .isThrownBy(writer::close)
        .satisfies(
            ex -> {
              assertThat(ex.getOperation()).isEqualTo("CLOSE");
              assertThat(ex.getFileStatus()).isEqualTo("30");
              assertThat(ex.getCause()).isInstanceOf(IOException.class);
            });
    // Even though close failed, the underlying writer reference is cleared, so a second
    // close() is a harmless no-op (does not invoke the underlying close again).
    writer.close();
    assertThat(failing.closeCount()).isEqualTo(1);
  }

  // ---------------------------------------------------------------------------
  // close() lifecycle and idempotency
  // ---------------------------------------------------------------------------

  @Test
  @DisplayName("close() flushes and closes the underlying writer exactly once")
  void close_flushes_and_closes_underlying_writer() {
    FailingWriter ok = new FailingWriter(false, false);
    StatementFileWriter writer = new StatementFileWriter();
    writer.open(ok);
    writer.accept("LINE");

    writer.close();

    assertThat(ok.closeCount()).isEqualTo(1);
    assertThat(ok.flushCount()).isEqualTo(1);
  }

  @Test
  @DisplayName("close() is idempotent and safe before any open()")
  void close_is_idempotent() {
    StatementFileWriter neverOpened = new StatementFileWriter();
    // Safe to close without opening.
    neverOpened.close();

    FailingWriter ok = new FailingWriter(false, false);
    StatementFileWriter writer = new StatementFileWriter();
    writer.open(ok);
    writer.close();
    // Second close must be a no-op: the underlying close is not invoked again.
    writer.close();
    assertThat(ok.closeCount()).isEqualTo(1);
  }

  // ---------------------------------------------------------------------------
  // Golden-file parity — byte-faithful reproduction of the STMTFILE fixture
  // ---------------------------------------------------------------------------

  @Test
  @DisplayName("feeding the golden statement lines reproduces the fixture byte-for-byte (MD5)")
  void golden_statement_parity() throws Exception {
    Path goldenPath =
        Path.of(StatementFileWriterTest.class.getResource(GOLDEN_TXT_RESOURCE).toURI());
    byte[] expectedBytes = Files.readAllBytes(goldenPath);
    List<String> goldenLines = Files.readAllLines(goldenPath, StandardCharsets.UTF_8);

    // Sanity: the fixture is the documented 22-line, 80-byte-per-line plain-text statement.
    assertThat(goldenLines).hasSize(22);
    assertThat(goldenLines).allSatisfy(line -> assertThat(line).hasSize(80));

    StringWriter sink = new StringWriter();
    StatementFileWriter writer = new StatementFileWriter();
    writer.open(sink);
    goldenLines.forEach(writer::accept);
    writer.close();

    byte[] producedBytes = sink.toString().getBytes(StandardCharsets.UTF_8);

    // Primary parity assertion: the produced stream is byte-identical to the golden fixture.
    assertThat(producedBytes).isEqualTo(expectedBytes);
    // Documented MD5 of the fixture must match the produced output.
    assertThat(md5Hex(producedBytes)).isEqualTo(GOLDEN_TXT_MD5);
    assertThat(writer.getRecordsWritten()).isEqualTo(22L);
  }

  /**
   * Computes the lower-case hexadecimal MD5 digest of {@code data} for the golden-file parity
   * assertion. MD5 is used here purely as a fixture checksum (not for security).
   *
   * @param data the bytes to digest
   * @return the 32-character lower-case hex MD5
   * @throws Exception if the MD5 algorithm is unavailable (never on a standard JDK)
   */
  private static String md5Hex(byte[] data) throws Exception {
    MessageDigest md = MessageDigest.getInstance("MD5");
    return HexFormat.of().formatHex(md.digest(data));
  }

  /**
   * Minimal {@link Writer} stub whose {@code write}/{@code close} can be configured to throw {@link
   * IOException}, and which counts {@code flush}/{@code close} invocations. Used to drive the
   * abend-parity and idempotency paths without any mocking framework.
   */
  private static final class FailingWriter extends Writer {

    private final boolean failOnWrite;
    private final boolean failOnClose;
    private int flushCount;
    private int closeCount;

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
    public void flush() throws IOException {
      flushCount++;
    }

    @Override
    public void close() throws IOException {
      closeCount++;
      if (failOnClose) {
        throw new IOException("simulated close failure");
      }
    }

    int flushCount() {
      return flushCount;
    }

    int closeCount() {
      return closeCount;
    }
  }
}
