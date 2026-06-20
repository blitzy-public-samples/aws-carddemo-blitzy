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

import com.aws.carddemo.exception.IoStatusException;
import com.aws.carddemo.util.CobolStringUtils;
import java.io.Closeable;
import java.io.IOException;
import java.io.Writer;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Physical output sink for the HTML account statement produced by the statement-generation job.
 *
 * <p>This is the Java translation of the {@code HTMLFILE} (HTML statement) output side of the
 * legacy batch program {@code legacy/app/cbl/CBSTM03A.CBL}. There, the file is declared {@code
 * SELECT HTML-FILE ASSIGN TO HTMLFILE} with a fixed record {@code 01 FD-HTMLFILE-REC PIC X(100)},
 * opened with {@code OPEN OUTPUT ... HTML-FILE}, written line-by-line with {@code WRITE
 * FD-HTMLFILE-REC FROM HTML-FIXED-LN}, and closed with {@code CLOSE ... HTML-FILE}.
 *
 * <p><strong>Record width is 100 (RECFM=FB, LRECL=100).</strong> The width is taken from {@code
 * legacy/app/jcl/CREASTMT.JCL} <strong>STEP040</strong> — the step that actually runs {@code EXEC
 * PGM=CBSTM03A} and writes {@code HTMLFILE} with {@code DCB=(LRECL=100,BLKSIZE=800,RECFM=FB)}. This
 * is intentionally <em>not</em> STEP030's value: STEP030 is a separate {@code IEFBR14}
 * pre-allocation/cleanup step that declares {@code HTMLFILE} at {@code LRECL=80}, and it must be
 * ignored when sizing the producer's records.
 *
 * <p>The {@code StatementGenerationService} pre-formats every HTML statement line before handing it
 * to this writer through the {@link Consumer#accept(String)} contract; the writer is the {@code
 * htmlSink} argument of {@code StatementGenerationService.run(stmtSink, htmlSink)}. As a defensive
 * measure that mirrors the COBOL {@code PIC X(100)} receiving field and RECFM=FB block padding,
 * every accepted line is normalized to exactly {@value #LRECL} characters via {@link
 * CobolStringUtils#fixedWidth(String, int)} (right-pad with spaces, truncate when longer) before it
 * is emitted. The width is therefore enforced here regardless of what the service produced.
 *
 * <p><strong>Abend parity.</strong> The legacy {@code SELECT HTML-FILE} carries no {@code FILE
 * STATUS} clause, so on the mainframe any open/write/close failure terminates the run unit. To
 * preserve that externally observable behavior (Agent Action Plan &sect;0.6.6 / &sect;0.1.2), every
 * physical {@link IOException} raised while opening, writing, or closing the underlying {@link
 * Writer} is re-thrown as an {@link IoStatusException} (which carries {@link
 * IoStatusException#BATCH_ABEND_CODE}); the batch layer translates that into a failed Spring Batch
 * step exit status. A write error is never swallowed.
 *
 * <p><strong>Determinism.</strong> The default line separator is a fixed {@code "\n"} — never
 * {@link System#lineSeparator()} — so the generated file is byte-for-byte stable for golden-file
 * parity tests across platforms.
 *
 * <p><strong>Threading.</strong> This writer is designed for single-threaded batch use (one job
 * step, one open underlying {@link Writer}); it holds mutable per-stream state and is not
 * thread-safe. It is a plain foundational POJO wired by the {@code batch/config} layer through
 * constructor injection — it deliberately does not depend on Spring Batch.
 */
public class HtmlStatementWriter implements Consumer<String>, Closeable {

  /** Logger for open/write diagnostics, mirroring the COBOL operator {@code DISPLAY}s. */
  private static final Logger LOG = LoggerFactory.getLogger(HtmlStatementWriter.class);

  /**
   * The logical DD / dataset name of the HTML statement file, matching the COBOL {@code SELECT
   * HTML-FILE ASSIGN TO HTMLFILE} and the {@code //HTMLFILE DD} card in {@code CREASTMT.JCL}. Used
   * as the file-name component of every {@link IoStatusException} this writer raises.
   */
  public static final String DD_NAME = "HTMLFILE";

  /**
   * The fixed HTML statement record length, in characters. Sourced from {@code CREASTMT.JCL}
   * STEP040 ({@code DCB=(LRECL=100,...)}) and the COBOL {@code FD-HTMLFILE-REC PIC X(100)} — and
   * deliberately not STEP030's {@code LRECL=80}.
   */
  public static final int LRECL = 100;

  /** Charset used when this writer opens a {@link Path} itself; never {@code null}. */
  private final Charset charset;

  /**
   * Record/line terminator written after every {@value #LRECL}-character record; never {@code
   * null}. Defaults to {@code "\n"} for deterministic, platform-independent output.
   */
  private final String lineSeparator;

  /**
   * The currently open underlying sink, or {@code null} when the writer is closed / not yet opened.
   * Mutated only by {@link #open}/{@link #close} and read by {@link #accept(String)}.
   */
  private Writer out;

  /** Count of records successfully written since the most recent {@code open}. */
  private long recordsWritten;

  /**
   * Creates a writer with the default UTF-8 charset and a fixed {@code "\n"} line separator.
   *
   * <p>The fixed separator (rather than {@link System#lineSeparator()}) keeps generated statements
   * byte-stable for golden-file parity assertions.
   */
  public HtmlStatementWriter() {
    this(StandardCharsets.UTF_8, "\n");
  }

  /**
   * Creates a writer with an explicit charset and line separator.
   *
   * @param charset the charset used when this writer opens a {@link Path}; a {@code null} value
   *     falls back to {@link StandardCharsets#UTF_8}
   * @param lineSeparator the terminator written after each record; a {@code null} value falls back
   *     to the deterministic default {@code "\n"}
   */
  public HtmlStatementWriter(Charset charset, String lineSeparator) {
    this.charset = (charset == null) ? StandardCharsets.UTF_8 : charset;
    this.lineSeparator = (lineSeparator == null) ? "\n" : lineSeparator;
  }

  /**
   * Opens this writer over a caller-supplied {@link Writer} (for example a {@code StringWriter} in
   * tests or a buffered file writer in production), replacing any previously open sink and
   * resetting the record counter.
   *
   * <p>The caller retains ownership of {@code writer} lifecycle semantics beyond this object, but
   * {@link #close()} will flush and close it.
   *
   * @param writer the destination to write records to; must not be {@code null}
   * @throws IoStatusException if {@code writer} is {@code null} (the COBOL {@code OPEN}-failure
   *     analogue, file status {@code "30"})
   */
  public void open(Writer writer) {
    if (writer == null) {
      throw new IoStatusException(DD_NAME, "OPEN", "30");
    }
    this.out = writer;
    this.recordsWritten = 0L;
  }

  /**
   * Opens this writer on a filesystem {@link Path} using the charset supplied at construction.
   *
   * @param path the HTML statement file to create/truncate and write
   * @throws IoStatusException if the file cannot be opened (the COBOL {@code OPEN}-failure
   *     analogue, file status {@code "30"})
   */
  public void open(Path path) {
    open(path, this.charset);
  }

  /**
   * Opens this writer on a filesystem {@link Path} with an explicit charset, creating the file if
   * absent and truncating it if it already exists ({@code OPEN OUTPUT} semantics).
   *
   * @param path the HTML statement file to create/truncate and write
   * @param cs the charset to encode records with; a {@code null} value falls back to the charset
   *     supplied at construction
   * @throws IoStatusException if the file cannot be opened (the COBOL {@code OPEN}-failure
   *     analogue, file status {@code "30"})
   */
  public void open(Path path, Charset cs) {
    Charset effective = (cs == null) ? this.charset : cs;
    try {
      this.out =
          Files.newBufferedWriter(
              path,
              effective,
              StandardOpenOption.CREATE,
              StandardOpenOption.TRUNCATE_EXISTING,
              StandardOpenOption.WRITE);
    } catch (IOException e) {
      throw new IoStatusException(DD_NAME, "OPEN", "30", e);
    }
    this.recordsWritten = 0L;
    LOG.info("OPENED HTML STATEMENT FILE {} (LRECL={})", path, LRECL);
  }

  /**
   * Persists a single pre-formatted HTML statement line as one fixed-width record, mirroring {@code
   * WRITE FD-HTMLFILE-REC FROM HTML-FIXED-LN} in {@code CBSTM03A.CBL}.
   *
   * <p>The line is first normalized to exactly {@value #LRECL} characters with {@link
   * CobolStringUtils#fixedWidth(String, int)} — a {@code null} line becomes {@value #LRECL} spaces,
   * a shorter line is right-padded with spaces, and a longer line is truncated on the right — then
   * the configured {@linkplain #HtmlStatementWriter(Charset, String) line separator} is appended.
   *
   * @param line the pre-formatted HTML statement line; {@code null} is tolerated and emitted as a
   *     blank ({@value #LRECL}-space) record
   * @throws IoStatusException if the writer is not open (file status {@code "30"}), or if the
   *     underlying {@link Writer} raises an {@link IOException} (re-thrown so the write error is
   *     never swallowed, the COBOL abend analogue)
   */
  @Override
  public void accept(String line) {
    if (out == null) {
      // HTML statement file not open
      throw new IoStatusException(DD_NAME, "WRITE", "30");
    }
    String rec = CobolStringUtils.fixedWidth(line, LRECL);
    try {
      out.write(rec); // <- CBSTM03A HTML statement WRITE
      out.write(lineSeparator);
      recordsWritten++;
    } catch (IOException e) {
      LOG.error("ERROR WRITING TO HTML STATEMENT FILE");
      throw new IoStatusException(DD_NAME, "WRITE", "30", e);
    }
  }

  /**
   * Flushes and closes the underlying {@link Writer}, mirroring {@code CLOSE ... HTML-FILE}.
   *
   * <p>The method is idempotent: calling it when the writer is already closed (or was never opened)
   * is a no-op. The underlying sink reference is always cleared, even if the flush/close fails, so
   * a subsequent {@code close()} cannot operate on a half-closed stream.
   *
   * @throws IoStatusException if flushing/closing the underlying {@link Writer} raises an {@link
   *     IOException} (file status {@code "30"})
   */
  @Override
  public void close() {
    if (out == null) {
      return;
    }
    try {
      out.flush();
      out.close();
    } catch (IOException e) {
      throw new IoStatusException(DD_NAME, "CLOSE", "30", e);
    } finally {
      out = null;
    }
  }

  /**
   * Returns the number of records successfully written since the most recent {@code open}.
   *
   * @return the running count of written {@value #LRECL}-character records
   */
  public long getRecordsWritten() {
    return recordsWritten;
  }
}
