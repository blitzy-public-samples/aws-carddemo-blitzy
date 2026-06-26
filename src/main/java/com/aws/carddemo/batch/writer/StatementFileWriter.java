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
 * Physical output sink for the plain-text account statement produced by the statement-generation
 * job &mdash; the Java translation of the {@code STMTFILE} output side of the legacy batch program
 * {@code legacy/app/cbl/CBSTM03A.CBL}.
 *
 * <p>In the COBOL program the statement file is declared as a fixed-record-format dataset whose
 * record is exactly eighty characters wide:
 *
 * <pre>
 *   FD  STMT-FILE.
 *   01  FD-STMTFILE-REC         PIC X(80).
 * </pre>
 *
 * The program {@code OPEN OUTPUT STMT-FILE}s the dataset, emits each statement line with {@code
 * WRITE FD-STMTFILE-REC FROM ST-LINEn} (every {@code WRITE FROM} moves the sending line into the
 * eighty-byte FD record, so the record is implicitly right-space-padded or right-truncated to the
 * fixed width), then {@code CLOSE}s it. The JCL data-definition statement that allocates the
 * dataset is in {@code legacy/app/jcl/CREASTMT.JCL} STEP040 ({@code EXEC PGM=CBSTM03A}):
 *
 * <pre>
 *   //STMTFILE DD DISP=(NEW,CATLG,DELETE),
 *   //         DCB=(LRECL=80,BLKSIZE=8000,RECFM=FB),
 *   //         DSN=AWS.M2.CARDDEMO.STATEMNT.PS
 * </pre>
 *
 * &mdash; a physical-sequential ({@code PS}), fixed-blocked ({@code RECFM=FB}) dataset with an
 * eighty-byte logical record length ({@code LRECL=80}). The companion {@code HTMLFILE} DD on the
 * same step has {@code LRECL=100} and is handled by a separate sink; it is not this writer's
 * concern.
 *
 * <p>This class is the {@link Consumer Consumer&lt;String&gt;} {@code stmtSink} passed to {@code
 * com.aws.carddemo.service.batch.StatementGenerationService.run(Consumer, Consumer)}. The service
 * pre-formats every statement line to the full eighty-character width; this writer applies {@link
 * CobolStringUtils#fixedWidth(String, int)} defensively so that the persisted record is always
 * exactly {@link #LRECL} characters &mdash; the normalization is idempotent for already-correct
 * input and corrective (right-pad with spaces, or right-truncate) otherwise. This reproduces the
 * COBOL {@code WRITE FD-STMTFILE-REC FROM} semantics byte-for-byte.
 *
 * <p><strong>Abend parity.</strong> Any physical {@code open}/{@code write}/{@code close} {@link
 * IOException} is re-thrown as an {@link IoStatusException} (which carries {@link
 * IoStatusException#BATCH_ABEND_CODE} = {@code 999}), mirroring the COBOL {@code
 * 9999-ABEND-PROGRAM} path that issues {@code MOVE 999 TO ABCODE} on an unrecoverable I/O
 * condition. The exception then surfaces as a failed Spring Batch step exit status. A write error
 * is never swallowed (Agent Action Plan &sect;0.6.6, &sect;0.1.2).
 *
 * <p><strong>Determinism.</strong> The default line separator is the fixed literal {@code "\n"}
 * &mdash; never {@link System#lineSeparator()} &mdash; so the produced file is byte-identical
 * across platforms and can be compared against the golden {@code STMTFILE} parity fixtures.
 *
 * <p><strong>No floating point.</strong> This writer handles text only and performs no decimal
 * arithmetic (Agent Action Plan &sect;0.6.1).
 *
 * <p><strong>Threading.</strong> Like the single-threaded mainframe batch step it replaces, an
 * instance is <em>not</em> thread-safe: a single thread must {@link #open(Path) open}, {@link
 * #accept(String) write} the statement lines in order, and {@link #close() close} it. It is a plain
 * foundational POJO with no Spring Batch coupling; lifecycle wiring is performed by the {@code
 * com.aws.carddemo.batch.config} layer.
 */
public class StatementFileWriter implements Consumer<String>, Closeable {

  /** SLF4J logger used for open/close lifecycle information and the parity write-error message. */
  private static final Logger LOG = LoggerFactory.getLogger(StatementFileWriter.class);

  /**
   * The logical file / DD name of the statement dataset ({@code STMTFILE} in {@code CREASTMT.JCL}),
   * used as the file-name component of every {@link IoStatusException} raised by this writer.
   */
  public static final String DD_NAME = "STMTFILE";

  /**
   * The fixed logical record length of the statement dataset: {@code LRECL=80} from {@code
   * CREASTMT.JCL} STEP040, matching the COBOL {@code 01 FD-STMTFILE-REC PIC X(80)}. Every persisted
   * line is normalized to exactly this many characters.
   */
  public static final int LRECL = 80;

  /** Character set used when this writer opens a {@link Path}; never {@code null}. */
  private final Charset charset;

  /**
   * The record separator written after every eighty-character record; never {@code null}. Defaults
   * to the deterministic {@code "\n"} for byte-faithful golden-file parity.
   */
  private final String lineSeparator;

  /**
   * The underlying character sink. {@code null} until {@link #open} is called and again after
   * {@link #close()}; the {@code null} state drives the not-open guard and the idempotency of
   * {@link #close()}.
   */
  private Writer out;

  /** Running count of statement records successfully written since the last {@code open}. */
  private long recordsWritten;

  /**
   * Creates a writer using the {@link StandardCharsets#UTF_8 UTF-8} character set and the
   * deterministic {@code "\n"} line separator.
   */
  public StatementFileWriter() {
    this(StandardCharsets.UTF_8, "\n");
  }

  /**
   * Creates a writer with explicit character set and line separator.
   *
   * <p>Both arguments are null-coalesced to their defaults so the constructed instance always has a
   * usable, non-{@code null} configuration: a {@code null} {@code charset} becomes {@link
   * StandardCharsets#UTF_8} and a {@code null} {@code lineSeparator} becomes the deterministic
   * {@code "\n"} (the default separator is intentionally <em>never</em> {@link
   * System#lineSeparator()}, to keep produced files byte-identical across platforms for golden-file
   * parity).
   *
   * <p>This is constructor injection only: there is no field {@code @Autowired}, and the class
   * remains a plain POJO.
   *
   * @param charset the character set used when opening a {@link Path}; {@code null} selects UTF-8
   * @param lineSeparator the record separator written after each record; {@code null} selects
   *     {@code "\n"}
   */
  public StatementFileWriter(Charset charset, String lineSeparator) {
    this.charset = (charset == null) ? StandardCharsets.UTF_8 : charset;
    this.lineSeparator = (lineSeparator == null) ? "\n" : lineSeparator;
  }

  /**
   * Opens this writer against an already-constructed {@link Writer}.
   *
   * <p>This overload exists primarily so tests can drive the writer with an in-memory {@link
   * java.io.StringWriter} and assert the exact bytes produced. The record counter is reset to zero.
   *
   * @param writer the destination writer; must not be {@code null}
   * @throws IoStatusException with file status {@code "30"} if {@code writer} is {@code null}
   *     (mirrors an {@code OPEN} failure)
   */
  public void open(Writer writer) {
    if (writer == null) {
      throw new IoStatusException(DD_NAME, "OPEN", "30");
    }
    this.out = writer;
    this.recordsWritten = 0;
  }

  /**
   * Opens (creating or truncating) the statement dataset at {@code path} using this writer's
   * configured {@link #charset}.
   *
   * @param path the statement file to (re)create
   * @throws IoStatusException with file status {@code "30"} if the file cannot be opened
   */
  public void open(Path path) {
    open(path, this.charset);
  }

  /**
   * Opens (creating or truncating) the statement dataset at {@code path} using the supplied
   * character set.
   *
   * <p>The file is opened with {@link StandardOpenOption#CREATE}, {@link
   * StandardOpenOption#TRUNCATE_EXISTING}, and {@link StandardOpenOption#WRITE}, mirroring the
   * COBOL {@code OPEN OUTPUT} verb (which always starts the dataset empty). The record counter is
   * reset to zero on success.
   *
   * @param path the statement file to (re)create; must not be {@code null}
   * @param cs the character set to encode records with; {@code null} falls back to this writer's
   *     configured {@link #charset}
   * @throws IoStatusException with file status {@code "30"} if {@code path} is {@code null} or the
   *     file cannot be opened (the underlying {@link IOException} is chained as the cause)
   */
  public void open(Path path, Charset cs) {
    if (path == null) {
      throw new IoStatusException(DD_NAME, "OPEN", "30");
    }
    Charset effectiveCharset = (cs == null) ? this.charset : cs;
    try {
      this.out =
          Files.newBufferedWriter(
              path,
              effectiveCharset,
              StandardOpenOption.CREATE,
              StandardOpenOption.TRUNCATE_EXISTING,
              StandardOpenOption.WRITE);
    } catch (IOException e) {
      throw new IoStatusException(DD_NAME, "OPEN", "30", e);
    }
    this.recordsWritten = 0;
    LOG.info("Opened statement file {} (DD={}, LRECL={})", path, DD_NAME, LRECL);
  }

  /**
   * Persists one statement line as a single fixed-width record (the Java equivalent of a COBOL
   * {@code WRITE FD-STMTFILE-REC FROM} statement).
   *
   * <p>The incoming line is normalized to exactly {@link #LRECL} characters via {@link
   * CobolStringUtils#fixedWidth(String, int)} &mdash; right-padded with spaces when shorter,
   * right-truncated when longer, and rendered as eighty spaces when {@code null}. The normalized
   * record is then written, followed by the configured {@link #lineSeparator}, and the record
   * counter is incremented.
   *
   * @param line the pre-formatted statement line; may be {@code null} (treated as COBOL {@code
   *     SPACES} and rendered as eighty blanks)
   * @throws IoStatusException with file status {@code "30"} if the writer is not open, or if the
   *     underlying {@link IOException} occurs during the write (chained as the cause)
   */
  @Override
  public void accept(String line) {
    if (out == null) {
      // statement file not open — accept() invoked before open()
      throw new IoStatusException(DD_NAME, "WRITE", "30");
    }
    String rec = CobolStringUtils.fixedWidth(line, LRECL);
    try {
      out.write(rec); // <- CBSTM03A statement WRITE
      out.write(lineSeparator);
      recordsWritten++;
    } catch (IOException e) {
      LOG.error("ERROR WRITING TO STATEMENT FILE"); // parity DISPLAY
      throw new IoStatusException(DD_NAME, "WRITE", "30", e);
    }
  }

  /**
   * Flushes and closes the underlying writer, mirroring the COBOL {@code CLOSE STMT-FILE} verb.
   *
   * <p>This method is idempotent: if the writer is already closed (or was never opened) it returns
   * immediately. The underlying writer reference is always cleared in a {@code finally} block, so a
   * failed close still leaves this instance in the closed state and a subsequent {@code close()} is
   * a no-op.
   *
   * <p>Although {@link Closeable#close()} declares {@code throws IOException}, this override
   * narrows that away: any {@link IOException} is translated to an unchecked {@link
   * IoStatusException} so callers (including try-with-resources) are not forced to handle a checked
   * exception while abend parity is preserved.
   *
   * @throws IoStatusException with file status {@code "30"} if the underlying close fails (the
   *     {@link IOException} is chained as the cause)
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
   * Returns the number of statement records successfully written since the most recent {@code
   * open}.
   *
   * @return the running record count (zero immediately after {@code open}, never negative)
   */
  public long getRecordsWritten() {
    return recordsWritten;
  }
}
