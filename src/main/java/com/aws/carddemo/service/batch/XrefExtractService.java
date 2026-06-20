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
package com.aws.carddemo.service.batch;

import com.aws.carddemo.domain.CardXref;
import com.aws.carddemo.exception.IoStatusException;
import com.aws.carddemo.repository.CardXrefRepository;
import java.util.Iterator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;

/**
 * Batch extract service that reads and prints the card cross-reference store &mdash; the
 * Java/Spring translation of the legacy batch COBOL program {@code legacy/app/cbl/CBACT03C.cbl}
 * ("Read and print card-xref data file"), per the Agent Action Plan transformation mapping
 * (&sect;0.4.1). It follows the same extract archetype as {@code AccountExtractService} (&larr;
 * {@code CBACT01C}) and reproduces the legacy control flow exactly (&sect;0.1.2, &sect;0.6.7).
 *
 * <p><strong>What it does.</strong> The COBOL program opens the indexed {@code XREFFILE} VSAM KSDS
 * for sequential input (ascending by its record key {@code FD-XREF-CARD-NUM}), then loops reading
 * each {@code CARD-XREF-RECORD} until end-of-file and {@code DISPLAY}s every record. The migrated
 * service iterates {@link CardXref} rows ascending by {@code xrefCardNum} (the VSAM primary key
 * &rarr; JPA {@code @Id}) and emits the whole-record rendering through the SLF4J log, which is the
 * modern equivalent of the COBOL {@code DISPLAY} to {@code SYSOUT}.
 *
 * <p><strong>Critical legacy quirk &mdash; double emission (preserved).</strong> {@code CBACT03C}
 * displays each cross-reference record <em>twice</em>: once inside {@code 1000-XREFFILE-GET-NEXT}
 * on a successful read (FILE STATUS {@code '00'}) and again in the main loop body. This duplicate
 * is intentionally preserved for behavioral parity &mdash; every record produces two identical log
 * lines and the duplication is <em>not</em> "optimized" away.
 *
 * <p><strong>I/O model.</strong> Mirroring the {@code 0000-XREFFILE-OPEN} / {@code
 * 1000-XREFFILE-GET-NEXT} / {@code 9000-XREFFILE-CLOSE} paragraphs, {@link #openXreffile()}
 * materializes the ordered result set once (the "open"), {@link #xreffileGetNext()} advances an
 * in-memory cursor (the sequential "read"), and {@link #closeXreffile()} releases the cursor (the
 * "close"). Because the rows are fetched eagerly at open time, any repository {@link
 * DataAccessException} surfaces during open and is mapped to an {@link IoStatusException}
 * (operation {@code "OPEN"}), reproducing the COBOL {@code 9999-ABEND-PROGRAM} + {@code
 * 9910-DISPLAY-IO-STATUS} path (&sect;0.6.4, &sect;0.6.6). The unchecked exception propagates so
 * the invoking Spring Batch step (the {@code com.aws.carddemo.batch.XrefExtractJobConfig} added
 * later) fails with an abend-equivalent exit status. FILE STATUS {@code '00'} is a normal read and
 * {@code '10'} is a benign end-of-file (loop end, never an error); a missing record is never
 * modeled here, so this service never throws {@code RecordNotFoundException} and never swallows an
 * I/O failure.
 *
 * <p><strong>COBOL paragraph &rarr; Java method traceability</strong> (&sect;0.6.7):
 *
 * <ul>
 *   <li>{@code PROCEDURE DIVISION} (main, L70-L88) &rarr; {@link #run()}
 *   <li>{@code 0000-XREFFILE-OPEN} (L118-L134) &rarr; {@link #openXreffile()}
 *   <li>{@code 1000-XREFFILE-GET-NEXT} (L92-L116) &rarr; {@link #xreffileGetNext()}
 *   <li>{@code 9000-XREFFILE-CLOSE} (L136-L152) &rarr; {@link #closeXreffile()}
 *   <li>{@code 9999-ABEND-PROGRAM} (L154-L158) + {@code 9910-DISPLAY-IO-STATUS} (L161-L174) &rarr;
 *       the {@link IoStatusException} thrown by {@link #openXreffile()}
 * </ul>
 *
 * <p><strong>Threading.</strong> This {@link Service} singleton holds per-run mutable cursor state
 * ({@link #cursor}, {@link #cardXrefRecord}, {@link #endOfFile}); it mirrors COBOL working storage
 * and is intended for <em>single-threaded</em> batch use. {@link #run()} re-initializes that state
 * on every invocation, so it is safely re-runnable sequentially but is not safe for concurrent
 * calls. No floating-point types are used (none are required by this extract).
 *
 * @see CardXref
 * @see CardXrefRepository
 * @see IoStatusException
 */
@Service
public class XrefExtractService {

  /**
   * SLF4J logger for this service. The whole-record displays and the legacy {@code DISPLAY 'ERROR
   * ...'} / {@code 9910-DISPLAY-IO-STATUS} parity lines are emitted here, standing in for the COBOL
   * {@code DISPLAY} statements that wrote to {@code SYSOUT}.
   */
  private static final Logger LOG = LoggerFactory.getLogger(XrefExtractService.class);

  /**
   * Logical file / DD name of the cross-reference store, matching the COBOL {@code SELECT
   * XREFFILE-FILE ASSIGN TO XREFFILE}. Used as the file-name argument of every {@link
   * IoStatusException} so operator diagnostics name the failing dataset exactly as the legacy
   * program does.
   */
  private static final String DD_NAME = "XREFFILE";

  /**
   * The synthetic two-character FILE STATUS recorded when an unexpected repository {@link
   * DataAccessException} is mapped to an abend. The legacy program branched to its abend path on
   * any status other than {@code '00'}/{@code '10'}; in the migrated JPA world the concrete VSAM
   * status is unavailable, so {@code "99"} (a non-{@code '00'}/{@code '10'} value) marks the
   * unexpected-error branch (Agent Action Plan &sect;0.6.4).
   */
  private static final String UNEXPECTED_STATUS = "99";

  /** Width of {@code XREF-CARD-NUM PIC X(16)} in the {@code CVACT03Y} layout. */
  private static final int XREF_CARD_NUM_LEN = 16;

  /** Width of {@code XREF-CUST-ID PIC 9(09)} in the {@code CVACT03Y} layout. */
  private static final int XREF_CUST_ID_LEN = 9;

  /** Width of {@code XREF-ACCT-ID PIC 9(11)} in the {@code CVACT03Y} layout. */
  private static final int XREF_ACCT_ID_LEN = 11;

  /** Width of the trailing {@code FILLER PIC X(14)} in the {@code CVACT03Y} layout. */
  private static final int FILLER_LEN = 14;

  /**
   * The trailing {@code FILLER PIC X(14)} rendered as fourteen spaces. The {@code CARD-XREF-RECORD}
   * group is 50 bytes ({@code 16 + 9 + 11 + 14}); the entity does not model the filler, so the
   * whole-record display reproduces it as blanks to keep the rendered length faithful to the
   * copybook ({@code RECLN 50}).
   */
  private static final String FILLER = " ".repeat(FILLER_LEN);

  /**
   * Repository over the {@code card_xref} table (legacy VSAM {@code XREFFILE} KSDS, copybook {@code
   * CVACT03Y}). Injected via the constructor and never {@code null}.
   */
  private final CardXrefRepository cardXrefRepository;

  /**
   * In-memory cursor over the cross-reference rows ascending by {@code xrefCardNum}, established by
   * {@link #openXreffile()} and released ({@code null}) by {@link #closeXreffile()}. Stands in for
   * the open COBOL {@code XREFFILE-FILE} sequential read position.
   */
  private Iterator<CardXref> cursor;

  /**
   * The record most recently read by {@link #xreffileGetNext()}, mirroring the COBOL
   * working-storage {@code CARD-XREF-RECORD} that both {@code DISPLAY} statements reference.
   */
  private CardXref cardXrefRecord;

  /**
   * End-of-file flag, the Java equivalent of the COBOL {@code END-OF-FILE} switch ({@code 'N'} /
   * {@code 'Y'}). Set {@code true} once the cursor is exhausted (FILE STATUS {@code '10'}).
   */
  private boolean endOfFile;

  /**
   * Creates the service with its required repository collaborator (constructor injection, the
   * Spring-managed equivalent of the COBOL {@code SELECT ... ASSIGN TO XREFFILE} binding).
   *
   * @param cardXrefRepository repository over the {@code card_xref} table; must not be {@code null}
   */
  public XrefExtractService(CardXrefRepository cardXrefRepository) {
    this.cardXrefRepository = cardXrefRepository;
  }

  /**
   * Runs the cross-reference extract end to end, reproducing the {@code CBACT03C} {@code PROCEDURE
   * DIVISION} main flow (L70-L88): announce start, open the file, loop reading and displaying every
   * record until end-of-file, close the file, announce end, and return (the COBOL {@code GOBACK};
   * no {@code RETURN-CODE} is set).
   *
   * <p>The loop preserves the source's structure verbatim, including the redundant inner {@code IF
   * END-OF-FILE = 'N'} guard nested inside {@code PERFORM UNTIL END-OF-FILE = 'Y'}. Each
   * successfully read record is emitted twice (once by {@link #xreffileGetNext()} and once here),
   * exactly as the legacy program does.
   *
   * @throws IoStatusException if opening the cross-reference store fails (unexpected {@link
   *     DataAccessException}); the abend-equivalent terminal condition
   */
  public void run() {
    // <- CBACT03C PROCEDURE DIVISION (main)
    LOG.info("START OF EXECUTION OF PROGRAM CBACT03C");
    openXreffile();

    // PERFORM UNTIL END-OF-FILE = 'Y'
    while (!endOfFile) {
      // IF END-OF-FILE = 'N' (the source's redundant guard is preserved for control-flow parity)
      if (!endOfFile) {
        xreffileGetNext();
        // IF END-OF-FILE = 'N' -> DISPLAY CARD-XREF-RECORD (the second, main-loop emission)
        if (!endOfFile) {
          LOG.info(formatRecord(cardXrefRecord));
        }
      }
    }

    closeXreffile();
    LOG.info("END OF EXECUTION OF PROGRAM CBACT03C");
  }

  /**
   * Opens the cross-reference store for sequential, ascending-by-key reading, mirroring {@code
   * 0000-XREFFILE-OPEN}. The ordered result set is fetched eagerly here (the "open"), so this is
   * the single point at which a repository {@link DataAccessException} can arise; it is translated
   * to an {@link IoStatusException} on the abend path. On success the end-of-file switch is reset
   * so the service is re-runnable.
   *
   * @throws IoStatusException if the repository read fails (operation {@code "OPEN"}, FILE STATUS
   *     {@value #UNEXPECTED_STATUS})
   */
  private void openXreffile() {
    // <- CBACT03C 0000-XREFFILE-OPEN
    try {
      this.cursor = cardXrefRepository.findAll(Sort.by("xrefCardNum")).iterator();
      this.endOfFile = false;
    } catch (DataAccessException ex) {
      // DISPLAY 'ERROR OPENING XREFFILE'
      LOG.error("ERROR OPENING XREFFILE");
      throw abendProgram("OPEN", ex);
    }
  }

  /**
   * Advances to the next cross-reference record, mirroring {@code 1000-XREFFILE-GET-NEXT}. When a
   * record is available (FILE STATUS {@code '00'}) it becomes {@link #cardXrefRecord} and is
   * emitted here (the first of the two displays); when the cursor is exhausted (FILE STATUS {@code
   * '10'}) the {@link #endOfFile} switch is raised and no record is emitted.
   *
   * <p>This step performs no repository call: the rows were materialized in {@link
   * #openXreffile()}, so advancing the in-memory cursor cannot raise a {@link DataAccessException}.
   * The legacy per-read error branch therefore collapses into the open-time mapping, keeping the
   * translation free of unreachable error handling.
   */
  private void xreffileGetNext() {
    // <- CBACT03C 1000-XREFFILE-GET-NEXT
    if (cursor.hasNext()) {
      // FILE STATUS '00': record read -> DISPLAY CARD-XREF-RECORD (the first emission)
      this.cardXrefRecord = cursor.next();
      LOG.info(formatRecord(cardXrefRecord));
    } else {
      // FILE STATUS '10': end of file (loop end, not an error)
      this.endOfFile = true;
    }
  }

  /**
   * Closes the cross-reference store, mirroring {@code 9000-XREFFILE-CLOSE}, by releasing the
   * cursor. No repository call is involved (the result set was materialized at open time), so
   * &mdash; unlike the COBOL {@code CLOSE} which inspected FILE STATUS &mdash; this release cannot
   * fail. Clearing the reference lets the underlying list be garbage-collected and leaves the
   * service in a defined, re-runnable state.
   */
  private void closeXreffile() {
    // <- CBACT03C 9000-XREFFILE-CLOSE
    this.cursor = null;
  }

  /**
   * Builds the {@link IoStatusException} for an unexpected I/O condition and logs the {@code
   * 9910-DISPLAY-IO-STATUS} parity line before returning it to the caller to throw, mirroring
   * {@code 9999-ABEND-PROGRAM} (which displayed the status and issued {@code CALL 'CEE3ABD'} to
   * terminate the run unit). Returning the exception (rather than throwing it directly) lets
   * callers write {@code throw abendProgram(...)} so the compiler still sees a definite throw.
   *
   * @param operation the failing I/O verb (for example {@code "OPEN"})
   * @param cause the underlying repository failure being wrapped
   * @return the {@link IoStatusException} to throw; carries {@link
   *     IoStatusException#BATCH_ABEND_CODE}
   */
  private IoStatusException abendProgram(String operation, DataAccessException cause) {
    // <- CBACT03C 9999-ABEND-PROGRAM + 9910-DISPLAY-IO-STATUS
    IoStatusException ioStatusException =
        new IoStatusException(DD_NAME, operation, UNEXPECTED_STATUS, cause);
    // 9910-DISPLAY-IO-STATUS: DISPLAY 'FILE STATUS IS: NNNN' IO-STATUS-04
    LOG.error(ioStatusException.getDisplayMessage());
    return ioStatusException;
  }

  /**
   * Renders a {@link CardXref} as the 50-character {@code CARD-XREF-RECORD} image displayed by the
   * COBOL {@code DISPLAY CARD-XREF-RECORD}, concatenating the fields in {@code CVACT03Y} order:
   * {@code XREF-CARD-NUM} ({@code X(16)}), {@code XREF-CUST-ID} ({@code 9(09)}), {@code
   * XREF-ACCT-ID} ({@code 9(11)}) and the trailing {@code FILLER} ({@code X(14)}). The output is
   * deterministic so the two emissions per record are byte-identical.
   *
   * @param xref the cross-reference row to render; never {@code null} in normal flow
   * @return the fixed-width 50-character record image
   */
  private static String formatRecord(CardXref xref) {
    return padAlpha(xref.getXrefCardNum(), XREF_CARD_NUM_LEN)
        + padNumeric(xref.getXrefCustId(), XREF_CUST_ID_LEN)
        + padNumeric(xref.getXrefAcctId(), XREF_ACCT_ID_LEN)
        + FILLER;
  }

  /**
   * Renders an alphanumeric {@code PIC X(width)} field: left-justified and space-padded on the
   * right, truncated to {@code width} if longer, reproducing how COBOL stores and displays a fixed
   * {@code X(n)} item. A {@code null} value renders as all spaces.
   *
   * @param value the field value (may be {@code null})
   * @param width the fixed field width in characters
   * @return a string of exactly {@code width} characters
   */
  private static String padAlpha(String value, int width) {
    String v = (value == null) ? "" : value;
    if (v.length() >= width) {
      return v.substring(0, width);
    }
    StringBuilder sb = new StringBuilder(width);
    sb.append(v);
    while (sb.length() < width) {
      sb.append(' ');
    }
    return sb.toString();
  }

  /**
   * Renders an unsigned numeric {@code PIC 9(width)} field: the magnitude is left zero-padded to
   * {@code width} digits, reproducing how COBOL displays a zoned {@code 9(n)} item. The sign is
   * dropped ({@code PIC 9} is unsigned) and an oversized value is truncated to its low-order {@code
   * width} digits, matching COBOL high-order truncation on an over-length {@code MOVE}. A {@code
   * null} value renders as all zeros.
   *
   * @param value the field value (may be {@code null})
   * @param width the fixed field width in digits
   * @return a string of exactly {@code width} digit characters
   */
  private static String padNumeric(Long value, int width) {
    long v = (value == null) ? 0L : value;
    String digits = Long.toString(v);
    if (digits.startsWith("-")) {
      // PIC 9(n) is unsigned: drop the sign and keep only the magnitude digits.
      digits = digits.substring(1);
    }
    if (digits.length() > width) {
      // COBOL truncates high-order digits on an over-length MOVE; keep the low-order `width`.
      digits = digits.substring(digits.length() - width);
    }
    if (digits.length() == width) {
      return digits;
    }
    StringBuilder sb = new StringBuilder(width);
    for (int i = digits.length(); i < width; i++) {
      sb.append('0');
    }
    sb.append(digits);
    return sb.toString();
  }
}
