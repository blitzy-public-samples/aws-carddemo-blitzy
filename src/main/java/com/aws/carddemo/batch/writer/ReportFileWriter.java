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
 * Physical output sink for the transaction-detail report, the Java translation of the {@code
 * TRANREPT} output side of {@code legacy/app/cbl/CBTRN03C.cbl}.
 *
 * <p>In the legacy program the report is a sequential print file declared {@code SELECT REPORT-FILE
 * ASSIGN TO TRANREPT} whose record is a fixed {@code 01 FD-REPTFILE-REC PIC X(133)}. Its DD is
 * defined as {@code DCB=(LRECL=133,RECFM=FB,BLKSIZE=0)} in both {@code legacy/app/jcl/TRANREPT.jcl}
 * and {@code legacy/app/proc/TRANREPT.prc}, so every record on the dataset is exactly 133 bytes.
 * This writer reproduces that contract: it persists each pre-formatted report line — page headers,
 * account totals, page totals, grand totals, and transaction detail lines — normalized to exactly
 * {@value #LRECL} characters.
 *
 * <p>The line content itself is produced upstream by {@code
 * com.aws.carddemo.service.batch.TransactionReportService}, which already builds and pads each line
 * to {@value #LRECL}. This class is the {@link Consumer Consumer&lt;String&gt;} {@code reportSink}
 * that the service writes to; it owns only the physical {@code OPEN} / {@code WRITE} / {@code
 * CLOSE} of the underlying character stream and the defensive re-normalization of each line to the
 * fixed record length (see {@link #accept(String)}). It is wired with a per-run output {@link Path}
 * by the batch configuration in {@code com.aws.carddemo.batch.config}; it is deliberately a plain
 * POJO with no Spring or Spring Batch dependencies.
 *
 * <p><strong>Abend parity.</strong> The COBOL paragraphs {@code 0100-REPTFILE-OPEN}, {@code
 * 1111-WRITE-REPORT-REC}, and {@code 9100-REPTFILE-CLOSE} each inspect {@code TRANREPT-STATUS}
 * after the I/O verb and, on any non-{@code '00'} status, {@code DISPLAY} an error, then {@code
 * PERFORM 9910-DISPLAY-IO-STATUS} and {@code PERFORM 9999-ABEND-PROGRAM} (which moves {@code 999}
 * to {@code ABCODE}). This writer mirrors that "unexpected status &rarr; abend" path: any {@link
 * IOException} raised while opening, writing, or closing the stream is re-thrown as an {@link
 * IoStatusException} (which carries {@link IoStatusException#BATCH_ABEND_CODE}), never swallowed,
 * so the batch step fails with the preserved abend semantics (Agent Action Plan &sect;0.6.6).
 *
 * <p><strong>Threading.</strong> This class is <em>not</em> thread-safe. It holds a mutable {@link
 * Writer} reference and a running record counter and is intended for single-threaded,
 * chunk-oriented batch use (one instance per report run), exactly as the original single-task COBOL
 * step executed.
 */
public class ReportFileWriter implements Consumer<String>, Closeable {

  /** SLF4J logger used for lifecycle and parity-display messages. */
  private static final Logger LOG = LoggerFactory.getLogger(ReportFileWriter.class);

  /**
   * The logical file / DD name of the report dataset ({@code TRANREPT}), reproduced from {@code
   * SELECT REPORT-FILE ASSIGN TO TRANREPT}. Used as the {@code fileName} on every {@link
   * IoStatusException} this writer raises so operator messages stay byte-faithful.
   */
  public static final String DD_NAME = "TRANREPT";

  /**
   * The fixed report record length in characters ({@code 133}), reproduced from {@code
   * FD-REPTFILE-REC PIC X(133)} and the DD {@code DCB=(LRECL=133,RECFM=FB)}. Every accepted line is
   * normalized to exactly this width before it is written.
   */
  public static final int LRECL = 133;

  /** Charset used when this writer opens a {@link Path} itself; never {@code null}. */
  private final Charset charset;

  /**
   * The record terminator appended after every {@value #LRECL}-character record; never {@code
   * null}. Fixed to {@code "\n"} by default (never {@link System#lineSeparator()}) so golden-file
   * parity output is byte-identical across platforms.
   */
  private final String lineSeparator;

  /**
   * The destination character stream, or {@code null} when the writer is not open. Mutable by
   * design for the single-threaded open/write/close lifecycle.
   */
  private Writer out;

  /** Count of records successfully written since the most recent {@code open}. */
  private long recordsWritten;

  /**
   * Creates a writer that emits UTF-8 bytes with a fixed {@code "\n"} record separator.
   *
   * <p>The {@code "\n"} default is deliberate and must not be replaced by {@link
   * System#lineSeparator()}: deterministic, platform-independent output is required for the
   * golden-file parity tests that compare this writer's output against the expected {@code
   * TRANREPT} fixture.
   */
  public ReportFileWriter() {
    this(StandardCharsets.UTF_8, "\n");
  }

  /**
   * Creates a writer with an explicit charset and record separator.
   *
   * @param charset the charset used when this writer opens a {@link Path}; a {@code null} value
   *     defaults to {@link StandardCharsets#UTF_8}
   * @param lineSeparator the terminator appended after each fixed-width record; a {@code null}
   *     value defaults to {@code "\n"}
   */
  public ReportFileWriter(Charset charset, String lineSeparator) {
    this.charset = (charset == null) ? StandardCharsets.UTF_8 : charset;
    this.lineSeparator = (lineSeparator == null) ? "\n" : lineSeparator;
  }

  /**
   * Opens this writer onto a caller-supplied {@link Writer}.
   *
   * <p>Primarily used by tests (for example a {@link java.io.StringWriter}) and by callers that
   * manage the underlying stream themselves. The record counter is reset to zero.
   *
   * @param writer the destination stream; must not be {@code null}
   * @throws IoStatusException with operation {@code "OPEN"} and status {@code "30"} if {@code
   *     writer} is {@code null}
   */
  public void open(Writer writer) {
    if (writer == null) {
      // No stream to attach — mirrors a failed OPEN of the report file.
      throw new IoStatusException(DD_NAME, "OPEN", "30");
    }
    this.out = writer;
    this.recordsWritten = 0;
  }

  /**
   * Opens (creating or truncating) the report file at {@code path} using this writer's configured
   * {@linkplain #ReportFileWriter(Charset, String) charset}.
   *
   * @param path the report output file to create or overwrite
   * @throws IoStatusException with operation {@code "OPEN"} and status {@code "30"} if the file
   *     cannot be opened
   */
  public void open(Path path) {
    open(path, this.charset);
  }

  /**
   * Opens (creating or truncating) the report file at {@code path} using an explicit charset.
   *
   * <p>The file is opened with {@link StandardOpenOption#CREATE}, {@link
   * StandardOpenOption#TRUNCATE_EXISTING}, and {@link StandardOpenOption#WRITE}, reproducing the
   * legacy {@code OPEN OUTPUT REPORT-FILE} of {@code 0100-REPTFILE-OPEN} (a fresh, empty report
   * each run). The record counter is reset to zero on success.
   *
   * @param path the report output file to create or overwrite
   * @param cs the charset to encode records with; a {@code null} value falls back to this writer's
   *     configured charset
   * @throws IoStatusException with operation {@code "OPEN"} and status {@code "30"} (cause chained)
   *     if the file cannot be opened
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
      // ERROR OPENING REPTFILE -> 9999-ABEND-PROGRAM
      throw new IoStatusException(DD_NAME, "OPEN", "30", e);
    }
    this.recordsWritten = 0;
    LOG.info("Opened report file {} (DD {} RECFM=FB LRECL={})", path, DD_NAME, LRECL);
  }

  /**
   * Writes one report line as a fixed {@value #LRECL}-character record followed by the configured
   * record separator.
   *
   * <p>This is the {@link Consumer} entry point invoked once per report line by {@code
   * TransactionReportService}. The line is defensively normalized to exactly {@value #LRECL}
   * characters via {@link CobolStringUtils#fixedWidth(String, int)} — right-padded with spaces when
   * shorter, right-truncated when longer, and rendered as {@value #LRECL} spaces when {@code null}
   * — replicating a COBOL {@code MOVE} into the {@code PIC X(133)} print record on a {@code
   * RECFM=FB} dataset. Because the service already pads to {@value #LRECL}, this step is idempotent
   * for correctly sized input and merely corrective for any deviation. {@link String#format(String,
   * Object...)} width specifiers are intentionally avoided so the result never diverges from COBOL
   * justification/truncation.
   *
   * @param line the pre-formatted report line; {@code null} is treated as a blank record
   * @throws IoStatusException with operation {@code "WRITE"} and status {@code "30"} if the writer
   *     is not open, or (with the cause chained) if the underlying stream fails
   */
  @Override
  public void accept(String line) {
    if (out == null) {
      // report file not open
      throw new IoStatusException(DD_NAME, "WRITE", "30");
    }
    String rec = CobolStringUtils.fixedWidth(line, LRECL);
    try {
      out.write(rec); // <- CBTRN03C report WRITE paragraph (1111-WRITE-REPORT-REC)
      out.write(lineSeparator);
      recordsWritten++;
    } catch (IOException e) {
      LOG.error("ERROR WRITING TO TRANSACTION REPORT FILE"); // parity DISPLAY
      throw new IoStatusException(DD_NAME, "WRITE", "30", e);
    }
  }

  /**
   * Flushes and closes the underlying stream, reproducing {@code 9100-REPTFILE-CLOSE}.
   *
   * <p>The method is idempotent: if the writer is not open it returns immediately, and the stream
   * reference is always cleared in a {@code finally} block so a second call (or a call after a
   * failed close) is a no-op. Narrowing {@link Closeable#close()}'s declared {@link IOException} to
   * the unchecked {@link IoStatusException} is intentional and mirrors the COBOL transfer of
   * control to the abend paragraph rather than a declared checked condition.
   *
   * @throws IoStatusException with operation {@code "CLOSE"} and status {@code "30"} (cause
   *     chained) if the underlying stream fails to flush or close
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
      // ERROR CLOSING REPORT FILE -> 9999-ABEND-PROGRAM
      throw new IoStatusException(DD_NAME, "CLOSE", "30", e);
    } finally {
      out = null;
    }
  }

  /**
   * Returns the number of records written since the most recent {@code open}.
   *
   * @return the running count of successfully written report records
   */
  public long getRecordsWritten() {
    return recordsWritten;
  }
}
