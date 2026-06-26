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
 * Physical output sink for the {@code DALYREJS} reject file produced by the daily transaction
 * posting job &mdash; the Java translation of the {@code DALYREJS} (rejects) output side of {@code
 * legacy/app/cbl/CBTRN02C.cbl} paragraph {@code 2500-WRITE-REJECT-REC} (Agent Action Plan
 * &sect;0.6.6).
 *
 * <p><strong>Record layout (exactly 430 bytes).</strong> Each reject record mirrors the COBOL file
 * description {@code 01 FD-REJS-RECORD} whose two subordinate items are {@code 05 FD-REJECT-RECORD
 * PIC X(350)} (the verbatim 350-byte {@code DALYTRAN} image) followed by {@code 05
 * FD-VALIDATION-TRAILER PIC X(80)} (the validation trailer: a {@code PIC 9(04)} zero-padded reason
 * code in columns 351-354 and a {@code PIC X(76)} space-padded description in columns 355-430). The
 * total length is therefore {@value #LRECL} bytes, matching the {@code DALYREJS} DD in {@code
 * legacy/app/jcl/POSTTRAN.jcl}: {@code DISP=(NEW,CATLG,DELETE) DCB=(RECFM=F,LRECL=430,BLKSIZE=0)}
 * with a generation-data-group {@code (+1)} destination.
 *
 * <p><strong>Verbatim persistence (parity-critical).</strong> The upstream {@code
 * com.aws.carddemo.service.batch.TransactionPostingService} builds the complete {@value
 * #LRECL}-char string (it pads the reason code and description) and hands it to this writer through
 * the {@link Consumer#accept(Object)} contract. This class persists each record <em>verbatim</em>:
 * it never trims, re-pads, re-justifies, truncates, or changes case. Golden-file parity tests
 * byte-compare the output against the expected COBOL {@code DALYREJS} dataset, so any mutation here
 * would break parity. The only content check performed is a NON-mutating length assertion against
 * {@value #LRECL}.
 *
 * <p><strong>Abend parity.</strong> The legacy paragraph inspects {@code DALYREJS-STATUS} after the
 * {@code WRITE}; on any value other than {@code '00'} it performs {@code DISPLAY 'ERROR WRITING TO
 * REJECTS FILE'}, {@code 9910-DISPLAY-IO-STATUS}, then {@code 9999-ABEND-PROGRAM} ({@code MOVE 999
 * TO ABCODE}; {@code CALL 'CEE3ABD'}). Here, any physical open/write/close failure (an {@link
 * IOException}) is re-thrown as an {@link IoStatusException} (which carries {@link
 * IoStatusException#BATCH_ABEND_CODE} {@code = 999}); leaving that unchecked exception unhandled
 * surfaces as a failed Spring Batch step exit status. A write failure is never swallowed.
 *
 * <p><strong>Framework-light by design.</strong> This is a plain POJO with no Spring Batch
 * dependency and no Spring stereotype annotation. The {@code com.aws.carddemo.batch.config} layer
 * constructs and wires it &mdash; typically passing this instance directly as the {@code
 * Consumer<String> rejectSink} to {@code TransactionPostingService.run(rejectSink)}, or adapting it
 * as the writer half of a chunk-oriented step &mdash; resolving a fresh per-run output {@link Path}
 * to honor the GDG {@code (+1)} "new generation per run" semantics.
 *
 * <p><strong>Threading.</strong> This writer holds mutable {@code out}/{@code recordsWritten} state
 * and is therefore intended for <em>single-threaded</em> batch use only; it is not safe for
 * concurrent {@link #accept(String)} calls.
 *
 * @see com.aws.carddemo.exception.IoStatusException
 */
public class RejectFileWriter implements Consumer<String>, Closeable {

  /**
   * SLF4J logger; the {@code DISPLAY 'ERROR WRITING TO REJECTS FILE'} parity line is logged here.
   */
  private static final Logger LOG = LoggerFactory.getLogger(RejectFileWriter.class);

  /**
   * The COBOL {@code DDNAME} of the reject file ({@code DALYREJS} in {@code
   * legacy/app/jcl/POSTTRAN.jcl}). Used as the file-name argument of every {@link
   * IoStatusException} thrown by this writer so the operator diagnostics name the failing dataset
   * exactly as the legacy JCL does.
   */
  public static final String DD_NAME = "DALYREJS";

  /**
   * The fixed logical record length of the reject file in characters: {@code 350} ({@code
   * FD-REJECT-RECORD PIC X(350)}) + {@code 80} ({@code FD-VALIDATION-TRAILER PIC X(80)}) = {@code
   * 430}. This matches {@code DCB=(RECFM=F,LRECL=430,...)} on the {@code DALYREJS} DD. Every record
   * accepted by {@link #accept(String)} must be exactly this many characters.
   */
  public static final int LRECL = 430;

  /**
   * The charset used to encode records when the writer is opened against a {@link Path}. Defaults
   * to {@link StandardCharsets#UTF_8} (the migrated application reads/writes modern ASCII text
   * files, not EBCDIC). Never {@code null}.
   */
  private final Charset charset;

  /**
   * The record separator written after each record. Defaults to a fixed line feed ({@code "\n"}).
   *
   * <p>A platform-dependent separator (for example {@code System.lineSeparator()}, which is {@code
   * "\r\n"} on Windows) is deliberately avoided: golden-file byte-parity must be deterministic
   * across CI platforms. Supplying an empty string ({@code ""}) concatenates records with no
   * delimiter, reproducing a true {@code RECFM=F} fixed-record-image stream. Never {@code null}.
   */
  private final String lineSeparator;

  /**
   * The underlying physical sink. {@code null} until {@link #open(Writer)} or one of the {@code
   * open(Path...)} overloads is called, and reset to {@code null} by {@link #close()} so the writer
   * fails fast (rather than writing to a stale, closed stream) if reused without re-opening.
   */
  private Writer out;

  /**
   * The number of records successfully written since the most recent {@code open(...)}. Exposed via
   * {@link #getRecordsWritten()} as an independent cross-check against the posting job's own {@code
   * WS-REJECT-COUNT} (the job counter remains authoritative).
   */
  private long recordsWritten;

  /**
   * Creates a writer with the default configuration: {@link StandardCharsets#UTF_8} encoding and a
   * fixed line-feed ({@code "\n"}) record separator.
   */
  public RejectFileWriter() {
    this(StandardCharsets.UTF_8, "\n");
  }

  /**
   * Creates a writer with an explicit charset and record separator (constructor injection used by
   * the {@code batch/config} layer).
   *
   * <p>Both arguments are null-coalesced for robustness: a {@code null} {@code charset} falls back
   * to {@link StandardCharsets#UTF_8} and a {@code null} {@code lineSeparator} falls back to {@code
   * "\n"}. An <em>empty</em> separator is retained as-is (it is a valid choice that emits no
   * delimiter between fixed-length records).
   *
   * @param charset the charset used when opening against a {@link Path}; {@code null} &rarr; UTF-8
   * @param lineSeparator the separator emitted after each record; {@code null} &rarr; {@code "\n"}
   */
  public RejectFileWriter(Charset charset, String lineSeparator) {
    this.charset = (charset == null) ? StandardCharsets.UTF_8 : charset;
    this.lineSeparator = (lineSeparator == null) ? "\n" : lineSeparator;
  }

  /**
   * Opens this writer against an already-constructed {@link Writer} sink, resetting the
   * records-written counter to zero.
   *
   * <p>This overload is primarily used by unit tests, which supply an in-memory {@link
   * java.io.StringWriter} so the produced bytes can be asserted without touching the file system.
   *
   * @param writer the destination writer; must not be {@code null}
   * @throws IoStatusException if {@code writer} is {@code null} (file status {@code "30"},
   *     operation {@code "OPEN"}) &mdash; opening with no sink is a wiring error that must abend
   */
  public void open(Writer writer) {
    if (writer == null) {
      throw new IoStatusException(DD_NAME, "OPEN", "30");
    }
    this.out = writer;
    this.recordsWritten = 0;
  }

  /**
   * Opens this writer against a file {@link Path} using the configured {@link #charset}.
   *
   * @param path the per-run output path to (re)create and write; see {@link #open(Path, Charset)}
   *     for the open semantics and GDG note
   * @throws IoStatusException if the file cannot be opened (file status {@code "30"}, operation
   *     {@code "OPEN"})
   */
  public void open(Path path) {
    open(path, this.charset);
  }

  /**
   * Opens this writer against a file {@link Path} using an explicit charset, truncating any
   * existing content, and resets the records-written counter to zero.
   *
   * <p>The legacy {@code DALYREJS} DD is {@code DISP=(NEW,CATLG,DELETE)} with a
   * generation-data-group {@code (+1)}, i.e. a brand-new generation per run. The {@code
   * batch/config} layer is responsible for resolving the concrete per-run {@link Path} (for example
   * a timestamped or sequence-numbered file name); this method opens it with {@link
   * StandardOpenOption#CREATE} + {@link StandardOpenOption#TRUNCATE_EXISTING} so the writer is
   * re-runnable for tests and the supplied path need not exist beforehand.
   *
   * @param path the per-run output path to (re)create and write; must not be {@code null}
   * @param cs the charset used to encode records; must not be {@code null}
   * @throws IoStatusException if the file cannot be opened (file status {@code "30"}, operation
   *     {@code "OPEN"}), wrapping the underlying {@link IOException}
   */
  public void open(Path path, Charset cs) {
    try {
      this.out =
          Files.newBufferedWriter(
              path,
              cs,
              StandardOpenOption.CREATE,
              StandardOpenOption.TRUNCATE_EXISTING,
              StandardOpenOption.WRITE);
      this.recordsWritten = 0;
      LOG.info("Opened reject file {} ({} charset={})", path, DD_NAME, cs);
    } catch (IOException e) {
      throw new IoStatusException(DD_NAME, "OPEN", "30", e);
    }
  }

  /**
   * Persists a single, pre-built reject record verbatim, reproducing the {@code WRITE
   * FD-REJS-RECORD FROM REJECT-RECORD} of {@code 2500-WRITE-REJECT-REC}.
   *
   * <p>The record is written exactly as supplied followed by the configured {@link #lineSeparator};
   * it is never trimmed, re-padded, re-justified, truncated, or case-folded. Two NON-mutating
   * guards run first:
   *
   * <ul>
   *   <li><strong>not-open</strong> &mdash; writing before {@code open(...)} is a wiring error with
   *       no sink, so it abends rather than silently dropping the record; and
   *   <li><strong>length</strong> &mdash; the upstream {@code TransactionPostingService} is
   *       contractually required to supply exactly {@value #LRECL} characters. A {@code null}
   *       record or a wrong length would corrupt the fixed-width file, so it abends (validation
   *       only &mdash; the record is neither trimmed nor padded to fit).
   * </ul>
   *
   * <p>Any physical {@link IOException} during the write is logged with the legacy {@code 'ERROR
   * WRITING TO REJECTS FILE'} line and re-thrown as an {@link IoStatusException} (the {@code
   * 9910-DISPLAY-IO-STATUS} + {@code 9999-ABEND-PROGRAM} path), never swallowed.
   *
   * @param record the complete {@value #LRECL}-character reject record to persist
   * @throws IoStatusException if the writer is not open, if {@code record} is {@code null} or not
   *     exactly {@value #LRECL} characters, or if the physical write fails (all with operation
   *     {@code "WRITE"} and file status {@code "30"})
   */
  @Override
  public void accept(String record) {
    if (out == null) {
      // A write before open() has no sink: abend rather than silently dropping the reject record.
      LOG.error("Reject file {} is not open; call open(...) before accept(record)", DD_NAME);
      throw new IoStatusException(DD_NAME, "WRITE", "30");
    }
    if (record == null || record.length() != LRECL) {
      // Defensive, NON-mutating data-integrity guard: a wrong-length record would corrupt the
      // fixed-width DALYREJS file, so abend rather than trim/pad to fit (Cardinal Rule 2). The
      // upstream TransactionPostingService must supply exactly LRECL (430) characters.
      int actualLength = (record == null) ? -1 : record.length();
      LOG.error(
          "Reject record length {} does not match required LRECL {} for DD {}; refusing to write",
          actualLength,
          LRECL,
          DD_NAME);
      throw new IoStatusException(DD_NAME, "WRITE", "30");
    }
    try {
      out.write(record); // VERBATIM image — never trim/re-pad/justify (Cardinal Rule 2)
      out.write(lineSeparator);
      recordsWritten++;
    } catch (IOException e) {
      // <- CBTRN02C 2500-WRITE-REJECT-REC: DISPLAY 'ERROR WRITING TO REJECTS FILE'
      LOG.error("ERROR WRITING TO REJECTS FILE");
      // <- 9910-DISPLAY-IO-STATUS + 9999-ABEND-PROGRAM (MOVE 999 TO ABCODE; CALL 'CEE3ABD')
      throw new IoStatusException(DD_NAME, "WRITE", "30", e);
    }
  }

  /**
   * Flushes and closes the underlying sink, mirroring {@code 9300-DALYREJS-CLOSE}.
   *
   * <p>The method is idempotent: calling it when the writer was never opened, or calling it more
   * than once, is a safe no-op (the {@code out == null} early return guards both cases). The {@code
   * out} reference is always cleared in a {@code finally} block so a failed close still leaves the
   * writer in a defined, reusable-after-reopen state.
   *
   * @throws IoStatusException if flushing or closing the underlying sink fails (file status {@code
   *     "30"}, operation {@code "CLOSE"}), wrapping the underlying {@link IOException}
   */
  @Override
  public void close() {
    if (out == null) {
      return;
    }
    try {
      out.flush();
      out.close();
      LOG.info("Closed reject file {} ({} records written)", DD_NAME, recordsWritten);
    } catch (IOException e) {
      throw new IoStatusException(DD_NAME, "CLOSE", "30", e);
    } finally {
      out = null;
    }
  }

  /**
   * Returns the number of records successfully written since the most recent {@code open(...)}.
   *
   * <p>Provided as an independent assertion hook for tests and as a cross-check against the posting
   * job's own {@code WS-REJECT-COUNT} (which remains the authoritative reject tally).
   *
   * @return the count of records emitted; never negative
   */
  public long getRecordsWritten() {
    return recordsWritten;
  }
}
