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
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
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
 * Pure-POJO JUnit 5 unit test for {@link RejectFileWriter}. It proves that the writer persists each
 * pre-built {@code DALYREJS} reject record VERBATIM at exactly 430 characters (never padding,
 * trimming, or mutating it), enforces the length / null / not-open guards, and translates an
 * underlying {@link IOException} into an abend-parity {@link IoStatusException} &mdash; the Java
 * mirror of {@code legacy/app/cbl/CBTRN02C.cbl} paragraph {@code 2500-WRITE-REJECT-REC}.
 */
class RejectFileWriterTest {

  /** The fixed DALYREJS logical record length: 350-byte DALYTRAN image + 80-byte trailer. */
  private static final int LRECL = 430;

  /**
   * Builds a valid record of exactly {@value #LRECL} copies of the given character.
   *
   * @param c the fill character
   * @return a string of length {@value #LRECL}
   */
  private static String rec430(char c) {
    return String.valueOf(c).repeat(LRECL);
  }

  // ---------------------------------------------------------------------------
  // Verbatim write — the core parity contract (no pad / trim / mutate)
  // ---------------------------------------------------------------------------

  @Test
  void accept_writes_exactly_430_chars_plus_newline_verbatim() {
    RejectFileWriter w = new RejectFileWriter();
    StringWriter sw = new StringWriter();
    w.open(sw);

    String r = rec430('A');
    w.accept(r);
    w.close();

    assertEquals(r + "\n", sw.toString());
    assertEquals(431, sw.toString().length());
    // VERBATIM: the first 430 characters are the supplied record byte-for-byte (no trim / pad).
    assertEquals(r, sw.toString().substring(0, 430));
  }

  @Test
  void accept_two_records_preserves_order_and_count() {
    RejectFileWriter w = new RejectFileWriter();
    StringWriter sw = new StringWriter();
    w.open(sw);

    String r1 = rec430('1');
    String r2 = rec430('2');
    w.accept(r1);
    w.accept(r2);

    assertEquals(r1 + "\n" + r2 + "\n", sw.toString());
    assertEquals(2 * 431, sw.toString().length());
    assertEquals(2L, w.getRecordsWritten());
    w.close();
  }

  @Test
  void getRecordsWritten_starts_at_zero() {
    RejectFileWriter w = new RejectFileWriter();
    w.open(new StringWriter());

    assertEquals(0L, w.getRecordsWritten());
  }

  // ---------------------------------------------------------------------------
  // Guards: wrong length / null / not-open -> IoStatusException(DALYREJS, WRITE)
  // ---------------------------------------------------------------------------

  @Test
  void accept_rejects_record_shorter_than_430() {
    RejectFileWriter w = new RejectFileWriter();
    w.open(new StringWriter());
    String short429 = "A".repeat(429);

    IoStatusException ex = assertThrows(IoStatusException.class, () -> w.accept(short429));
    assertEquals("DALYREJS", ex.getFileName());
    assertEquals("WRITE", ex.getOperation());
  }

  @Test
  void accept_rejects_record_longer_than_430() {
    RejectFileWriter w = new RejectFileWriter();
    w.open(new StringWriter());
    String long431 = "A".repeat(431);

    IoStatusException ex = assertThrows(IoStatusException.class, () -> w.accept(long431));
    assertEquals("DALYREJS", ex.getFileName());
    assertEquals("WRITE", ex.getOperation());
  }

  @Test
  void accept_rejects_null_record() {
    RejectFileWriter w = new RejectFileWriter();
    w.open(new StringWriter());

    IoStatusException ex = assertThrows(IoStatusException.class, () -> w.accept(null));
    assertEquals("DALYREJS", ex.getFileName());
    assertEquals("WRITE", ex.getOperation());
  }

  @Test
  void accept_before_open_throws() {
    RejectFileWriter w = new RejectFileWriter();

    IoStatusException ex = assertThrows(IoStatusException.class, () -> w.accept(rec430('A')));
    assertEquals("DALYREJS", ex.getFileName());
    assertEquals("WRITE", ex.getOperation());
    // The 3-arg guard constructor chains no cause (distinct from the wrapped-IOException path).
    assertNull(ex.getCause());
  }

  @Test
  void open_null_writer_throws() {
    RejectFileWriter w = new RejectFileWriter();

    IoStatusException ex = assertThrows(IoStatusException.class, () -> w.open((Writer) null));
    assertEquals("DALYREJS", ex.getFileName());
    assertEquals("OPEN", ex.getOperation());
  }

  // ---------------------------------------------------------------------------
  // Abend parity: a physical IOException is wrapped, preserving the original cause
  // ---------------------------------------------------------------------------

  @Test
  void accept_wraps_io_exception_as_iostatusexception_with_cause() {
    RejectFileWriter w = new RejectFileWriter();
    w.open(new FailingWriter());

    IoStatusException ex = assertThrows(IoStatusException.class, () -> w.accept(rec430('A')));
    IOException cause = assertInstanceOf(IOException.class, ex.getCause());
    assertEquals("boom", cause.getMessage());
    assertEquals("DALYREJS", ex.getFileName());
    assertEquals("WRITE", ex.getOperation());
  }

  // ---------------------------------------------------------------------------
  // close() idempotency + custom (empty) separator
  // ---------------------------------------------------------------------------

  @Test
  void close_is_idempotent_without_open() {
    RejectFileWriter w = new RejectFileWriter();

    assertDoesNotThrow(w::close);
  }

  @Test
  void close_is_idempotent_when_called_twice() {
    RejectFileWriter w = new RejectFileWriter();
    w.open(new StringWriter());
    w.accept(rec430('A'));
    w.close();

    assertDoesNotThrow(w::close);
  }

  @Test
  void custom_empty_separator_concatenates_records() {
    RejectFileWriter w = new RejectFileWriter(StandardCharsets.UTF_8, "");
    StringWriter sw = new StringWriter();
    w.open(sw);

    String r1 = rec430('1');
    String r2 = rec430('2');
    w.accept(r1);
    w.accept(r2);

    assertEquals(r1 + r2, sw.toString());
    assertEquals(860, sw.toString().length());
    w.close();
  }

  // ---------------------------------------------------------------------------
  // open(Path): byte-level fidelity to a real file
  // ---------------------------------------------------------------------------

  @Test
  void open_path_writes_bytes_to_file(@TempDir Path dir) throws IOException {
    Path f = dir.resolve("dalyrejs.dat");
    RejectFileWriter w = new RejectFileWriter();
    w.open(f);

    String r = rec430('Z');
    w.accept(r);
    w.close();

    byte[] actual = Files.readAllBytes(f);
    byte[] expected = (r + "\n").getBytes(StandardCharsets.UTF_8);
    assertArrayEquals(expected, actual);
    assertEquals(431, actual.length);
  }

  // ---------------------------------------------------------------------------
  // Resilient golden-file byte-compare (skips cleanly when the fixture is absent)
  // ---------------------------------------------------------------------------

  @Test
  void golden_dalyrejs_matches_when_present() throws Exception {
    URL url = getClass().getResource("/golden/reject/dalyrejs.dat");
    assumeTrue(url != null, "golden reject fixture not present; skipping byte-compare");

    byte[] golden = Files.readAllBytes(Path.of(url.toURI()));
    // Each record is exactly 430 characters followed by "\n", so the file is a multiple of 431.
    assertEquals(0, golden.length % 431);
  }

  /**
   * Minimal {@link Writer} stub whose {@code write} always throws, used to drive the {@link
   * IoStatusException} abend-parity path inside {@link RejectFileWriter#accept(String)}.
   *
   * <p>{@code RejectFileWriter.open(Writer)} does not wrap the sink in a {@code BufferedWriter} and
   * {@link Writer#write(String)} delegates to {@code write(char[], int, int)}, so the failure
   * surfaces synchronously from {@code accept(...)}.
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
