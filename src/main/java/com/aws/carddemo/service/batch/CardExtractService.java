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

import com.aws.carddemo.domain.Card;
import com.aws.carddemo.exception.IoStatusException;
import com.aws.carddemo.repository.CardRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.util.Iterator;
import java.util.stream.Stream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;

/**
 * Batch service that reads and prints the card master, migrated one-to-one from the legacy z/OS
 * COBOL batch program {@code CBACT02C} ("Read and print card data file"; behavioral spec {@code
 * legacy/app/cbl/CBACT02C.cbl}, source-branch {@code app/cbl/CBACT02C.cbl}). It reproduces that
 * program with 100% behavioral parity (Agent Action Plan &sect;0.1.2, &sect;0.6.7): the COBOL
 * {@code PROCEDURE DIVISION} drives a sequential pass over the VSAM {@code CARDFILE} KSDS, emitting
 * every record to the operator log between a start banner and an end banner.
 *
 * <p>This is the card-master sibling of the account-master extract ({@code AccountExtractService}
 * &larr; {@code CBACT01C}); the two share the same archetype but differ in one observable behavior
 * documented below. The legacy {@code CARDFILE} ({@code SELECT ... ORGANIZATION IS INDEXED ACCESS
 * MODE IS SEQUENTIAL RECORD KEY IS FD-CARD-NUM}, {@code CBACT02C} L29-L33) is replaced by the
 * {@link CardRepository} JPA repository over the {@code card} table; the legacy {@code COPY
 * CVACT02Y} record ({@code 01 CARD-RECORD}) is the {@link Card} entity.
 *
 * <p><strong>COBOL paragraph &rarr; Java method traceability</strong> (for {@code
 * docs/traceability-matrix.md}, AAP &sect;0.6.7). Each method below carries a {@code // <- CBACT02C
 * nnnn-NAME} tag identifying its source paragraph:
 *
 * <ul>
 *   <li>{@code PROCEDURE DIVISION} main (L70-L88) &rarr; {@link #run()}
 *   <li>{@code 0000-CARDFILE-OPEN} (L118-L134) &rarr; {@link #openCardfile()}
 *   <li>{@code 1000-CARDFILE-GET-NEXT} (L92-L116) &rarr; {@link #cardfileGetNext()}
 *   <li>{@code 9000-CARDFILE-CLOSE} (L136-L152) &rarr; {@link #closeCardfile()}
 *   <li>{@code 9999-ABEND-PROGRAM} (L154-L158) + {@code 9910-DISPLAY-IO-STATUS} (L161-L174) &rarr;
 *       {@link #buildAbend(String, String, DataAccessException)} (thrown as {@link
 *       IoStatusException})
 * </ul>
 *
 * <p><strong>Single-emit parity (the difference from {@code CBACT01C}).</strong> In {@code
 * CBACT01C} every record is displayed twice — once field-by-field in {@code
 * 1100-DISPLAY-ACCT-RECORD} and again as the whole record in the main loop. {@code CBACT02C} is
 * deliberately different: its per-read {@code DISPLAY CARD-RECORD} inside {@code
 * 1000-CARDFILE-GET-NEXT} is <em>commented out</em> ({@code *DISPLAY CARD-RECORD}, L96) and the
 * program has <em>no</em> {@code 1100} display paragraph. Consequently each card is emitted
 * <strong>exactly once</strong>, by the main-loop {@code DISPLAY CARD-RECORD} (L78). This service
 * preserves that precisely: {@link #cardfileGetNext()} never logs, and {@link #run()} performs the
 * single per-record {@link Logger#info(String) LOG.info} emit.
 *
 * <p><strong>Ordering parity.</strong> The legacy {@code ACCESS MODE IS SEQUENTIAL} read over an
 * {@code INDEXED} KSDS keyed on {@code FD-CARD-NUM} returns records in ascending card-number order.
 * {@link #openCardfile()} reproduces that exactly with {@code streamAllByOrderByCardNumAsc()} — a
 * bounded-memory streaming cursor that visits cards in ascending card-number order without
 * materialising the whole table.
 *
 * <p><strong>Record rendering parity.</strong> A COBOL {@code DISPLAY} of the group item {@code
 * CARD-RECORD} writes the raw fixed-width 150-byte image (the concatenation of every {@code
 * CVACT02Y} field in declaration order, including the trailing {@code FILLER PIC X(59)}). {@link
 * #formatCardRecord(Card)} rebuilds that image byte-faithfully using the COBOL {@code PIC X(n)}
 * (left-justify, space-pad) and {@code PIC 9(n)} (right-justify, zero-pad) {@code MOVE} rules. The
 * unmodeled {@code FILLER} (positional padding with no business meaning, AAP &sect;0.4.1) is
 * rendered as 59 spaces. No floating-point type is used anywhere; the card extract performs no
 * arithmetic (AAP &sect;0.6.1).
 *
 * <p><strong>FILE STATUS &rarr; exception parity</strong> (AAP &sect;0.6.4, &sect;0.6.6). The COBOL
 * branches on the two-byte {@code CARDFILE-STATUS}: {@code '00'} is a normal I/O, {@code '10'} is
 * end-of-file (loop termination, not an error), and any other value performs {@code
 * 9910-DISPLAY-IO-STATUS} followed by {@code 9999-ABEND-PROGRAM} ({@code MOVE 999 TO ABCODE},
 * {@code CALL 'CEE3ABD'}). Because persistence is now JPA/PostgreSQL rather than VSAM, the
 * unexpected-status branch is signaled by a Spring {@link DataAccessException}; this service
 * translates it into an {@link IoStatusException} (which carries {@link
 * IoStatusException#BATCH_ABEND_CODE} = 999) after logging the {@code 9910} operator line, exactly
 * mirroring the abend path. A missing primary-key record is never relevant here (a full sequential
 * scan never looks one up), so {@code RecordNotFoundException} is never thrown and no status is
 * ever swallowed.
 *
 * <p><strong>Concurrency / lifecycle.</strong> Like the single-threaded legacy batch program, a
 * single instance processes one run at a time. The per-run cursor and end-of-file state are
 * instance fields (the Java analog of the COBOL {@code WORKING-STORAGE} {@code END-OF-FILE} flag
 * and file position) that {@link #openCardfile()} resets at the start of every {@link #run()}; the
 * driving Spring Batch step ({@code CardExtractJobConfig}, package {@code com.aws.carddemo.batch},
 * added separately) invokes {@link #run()} single-threaded, so this matches the original execution
 * model.
 */
@Service
public class CardExtractService {

  /** SLF4J logger; the Java target of the COBOL {@code DISPLAY} statements. */
  private static final Logger LOG = LoggerFactory.getLogger(CardExtractService.class);

  /**
   * Start banner — {@code CBACT02C} L71 {@code DISPLAY 'START OF EXECUTION OF PROGRAM CBACT02C'}.
   */
  private static final String START_BANNER = "START OF EXECUTION OF PROGRAM CBACT02C";

  /** End banner — {@code CBACT02C} L85 {@code DISPLAY 'END OF EXECUTION OF PROGRAM CBACT02C'}. */
  private static final String END_BANNER = "END OF EXECUTION OF PROGRAM CBACT02C";

  /**
   * Logical file name reported on the abend path, mirroring the COBOL {@code ASSIGN TO CARDFILE}
   * ({@code CBACT02C} L29) shown in the {@code DISPLAY 'ERROR ... CARDFILE'} lines.
   */
  private static final String CARDFILE_DD_NAME = "CARDFILE";

  /**
   * Synthetic two-byte FILE STATUS used when a Spring {@link DataAccessException} stands in for an
   * unexpected VSAM status. The {@code '9'} class marks a non-COBOL (JDBC) I/O failure, which
   * {@link IoStatusException} renders through its {@code 9910-DISPLAY-IO-STATUS} branch.
   */
  private static final String IO_ERROR_STATUS = "99";

  /** Operation label for the {@code OPEN} abend path (COBOL {@code 0000-CARDFILE-OPEN}). */
  private static final String OP_OPEN = "OPEN";

  /** Operation label for the {@code READ} abend path (COBOL {@code 1000-CARDFILE-GET-NEXT}). */
  private static final String OP_READ = "READ";

  /**
   * Error banner for the open failure — {@code CBACT02C} L129 {@code DISPLAY 'ERROR OPENING
   * CARDFILE'}.
   */
  private static final String ERR_OPEN = "ERROR OPENING CARDFILE";

  /**
   * Error banner for the read failure — {@code CBACT02C} L110 {@code DISPLAY 'ERROR READING
   * CARDFILE'}.
   */
  private static final String ERR_READ = "ERROR READING CARDFILE";

  // Fixed-width field lengths of CVACT02Y 01 CARD-RECORD (RECLN 150), in declaration order.
  /** {@code CARD-NUM PIC X(16)}. */
  private static final int CARD_NUM_LENGTH = 16;

  /** {@code CARD-ACCT-ID PIC 9(11)}. */
  private static final int CARD_ACCT_ID_LENGTH = 11;

  /** {@code CARD-CVV-CD PIC 9(03)}. */
  private static final int CARD_CVV_CD_LENGTH = 3;

  /** {@code CARD-EMBOSSED-NAME PIC X(50)}. */
  private static final int CARD_EMBOSSED_NAME_LENGTH = 50;

  /** {@code CARD-EXPIRAION-DATE PIC X(10)} (legacy misspelling preserved). */
  private static final int CARD_EXPIRAION_DATE_LENGTH = 10;

  /** {@code CARD-ACTIVE-STATUS PIC X(01)}. */
  private static final int CARD_ACTIVE_STATUS_LENGTH = 1;

  /** Trailing {@code FILLER PIC X(59)} — positional padding, rendered as spaces. */
  private static final int CARD_FILLER_LENGTH = 59;

  /** Repository over the {@code card} table; the JPA replacement for the VSAM {@code CARDFILE}. */
  private final CardRepository cardRepository;

  /**
   * Transaction-scoped entity manager used to {@code detach} each streamed record immediately after
   * it is read, so the persistence context does not accumulate the whole table (the bounded-memory
   * complement to the streaming cursor). Injected by the container; bound to the tasklet
   * transaction that brackets {@link #run()}.
   */
  @PersistenceContext private EntityManager entityManager;

  /**
   * Open streaming cursor over the card master (ascending {@code cardNum}), held so it can be
   * closed in {@link #closeCardfile()} to release the underlying JDBC cursor. {@code null} when no
   * scan is in progress.
   */
  private Stream<Card> cardStream;

  /**
   * Sequential cursor over the card master in ascending {@code cardNum} order, established by
   * {@link #openCardfile()} and advanced by {@link #cardfileGetNext()} (the Java analog of the
   * COBOL file position). Reset on every {@link #run()}.
   */
  private Iterator<Card> cursor;

  /**
   * End-of-file flag — the Java analog of the COBOL {@code WORKING-STORAGE} {@code END-OF-FILE PIC
   * X(01)} switch. {@code false} mirrors {@code 'N'}; {@code true} mirrors {@code 'Y'} (FILE STATUS
   * {@code '10'} reached).
   */
  private boolean endOfFile;

  /**
   * The record returned by the most recent {@link #cardfileGetNext()} — the Java analog of the
   * COBOL {@code CARD-RECORD} working area that the main-loop {@code DISPLAY} renders.
   */
  private Card currentCard;

  /**
   * Creates the service with its repository collaborator (constructor injection; the JPA equivalent
   * of opening the COBOL {@code CARDFILE}).
   *
   * @param cardRepository the card-master repository; must not be {@code null}
   */
  public CardExtractService(CardRepository cardRepository) {
    this.cardRepository = cardRepository;
  }

  // <- CBACT02C PROCEDURE DIVISION (main, L70-L88)
  /**
   * Reads the entire card master in ascending card-number order and logs every record once, framed
   * by the start and end banners — the faithful translation of the {@code CBACT02C} main {@code
   * PROCEDURE DIVISION} (L70-L88).
   *
   * <p>Control flow, preserved verbatim (AAP &sect;0.6.7):
   *
   * <ol>
   *   <li>L71 {@code DISPLAY 'START ...'} &rarr; log {@link #START_BANNER}.
   *   <li>L72 {@code PERFORM 0000-CARDFILE-OPEN} &rarr; {@link #openCardfile()}.
   *   <li>L74-L81 {@code PERFORM UNTIL END-OF-FILE = 'Y'} &rarr; loop: {@link #cardfileGetNext()}
   *       then, when a record was returned, emit the single {@code DISPLAY CARD-RECORD} (L78).
   *   <li>L83 {@code PERFORM 9000-CARDFILE-CLOSE} &rarr; {@link #closeCardfile()}.
   *   <li>L85 {@code DISPLAY 'END ...'} &rarr; log {@link #END_BANNER}; L87 {@code GOBACK} returns
   *       with no {@code RETURN-CODE}.
   * </ol>
   *
   * @throws IoStatusException if an unexpected (non-EOF) I/O failure occurs while opening or
   *     reading the card master — the {@code 9999-ABEND-PROGRAM} equivalent
   */
  public void run() {
    // L71: DISPLAY 'START OF EXECUTION OF PROGRAM CBACT02C'.
    LOG.info(START_BANNER);

    // L72: PERFORM 0000-CARDFILE-OPEN.
    openCardfile();

    // L74-L81: PERFORM UNTIL END-OF-FILE = 'Y'. The inner "IF END-OF-FILE = 'N'" (L75) is redundant
    // under the UNTIL guard but is preserved verbatim for control-flow parity; the second guard
    // (L77) suppresses the display when 1000-CARDFILE-GET-NEXT has just reached end-of-file.
    while (!endOfFile) {
      if (!endOfFile) {
        // L76: PERFORM 1000-CARDFILE-GET-NEXT.
        cardfileGetNext();
        if (!endOfFile) {
          // L78: DISPLAY CARD-RECORD. This is the ONLY per-record emit: the per-read display in
          // 1000 is commented out (L96) and CBACT02C has no 1100 display paragraph.
          LOG.info(formatCardRecord(currentCard));
        }
      }
    }

    // L83: PERFORM 9000-CARDFILE-CLOSE.
    closeCardfile();

    // L85: DISPLAY 'END OF EXECUTION OF PROGRAM CBACT02C'. L87: GOBACK.
    LOG.info(END_BANNER);
  }

  // <- CBACT02C 0000-CARDFILE-OPEN (L118-L134)
  /**
   * Opens the card master for sequential ascending-key reading, the JPA analog of {@code OPEN INPUT
   * CARDFILE-FILE}. Opens a bounded-memory streaming cursor via {@code
   * streamAllByOrderByCardNumAsc()} and takes its iterator as the run cursor, and clears the
   * end-of-file flag (COBOL {@code END-OF-FILE = 'N'} initial state).
   *
   * <p>A successful open mirrors FILE STATUS {@code '00'}. Any {@link DataAccessException} is the
   * unexpected-status branch (L123-L132): it is translated into an {@link IoStatusException} for
   * the {@code OPEN} operation via {@link #buildAbend(String, String, DataAccessException)} and
   * thrown (the {@code 9999-ABEND-PROGRAM} equivalent).
   */
  private void openCardfile() {
    try {
      // Bounded-memory streaming cursor (HINT_FETCH_SIZE / HINT_READ_ONLY) ascending by cardNum,
      // replacing the findAll(Sort) full-table materialization. The stream is consumed inside the
      // tasklet transaction and closed in closeCardfile() to release the JDBC cursor.
      this.cardStream = cardRepository.streamAllByOrderByCardNumAsc();
      this.cursor = cardStream.iterator();
      this.endOfFile = false;
    } catch (DataAccessException ex) {
      throw buildAbend(OP_OPEN, ERR_OPEN, ex);
    }
  }

  // <- CBACT02C 1000-CARDFILE-GET-NEXT (L92-L116)
  /**
   * Advances the cursor by one record, the JPA analog of {@code READ CARDFILE-FILE INTO
   * CARD-RECORD}. When a record is available it becomes {@link #currentCard} (FILE STATUS {@code
   * '00'}); when the cursor is exhausted the end-of-file flag is set and the current record cleared
   * (FILE STATUS {@code '10'}), which terminates the main loop — end-of-file is normal control
   * flow, never an error.
   *
   * <p><strong>No emit here.</strong> Faithful to {@code CBACT02C}, this method does not log: the
   * per-read {@code DISPLAY CARD-RECORD} is commented out at L96, so the record is rendered solely
   * by the main-loop {@code DISPLAY} in {@link #run()}.
   *
   * <p>Any {@link DataAccessException} is the unexpected-status branch (L100-L114): it is
   * translated into an {@link IoStatusException} for the {@code READ} operation via {@link
   * #buildAbend(String, String, DataAccessException)} and thrown.
   */
  private void cardfileGetNext() {
    try {
      if (cursor.hasNext()) {
        // FILE STATUS '00': a record was read into the working area (no DISPLAY — L96 is
        // commented).
        this.currentCard = cursor.next();
        // Detach immediately so the streamed scan does not accumulate the whole table in the
        // persistence context. Card has no JPA associations, so the detached record stays fully
        // readable for the main-loop DISPLAY.
        entityManager.detach(this.currentCard);
      } else {
        // FILE STATUS '10': end of file — set the COBOL END-OF-FILE = 'Y' switch and stop the loop.
        this.endOfFile = true;
        this.currentCard = null;
      }
    } catch (DataAccessException ex) {
      throw buildAbend(OP_READ, ERR_READ, ex);
    }
  }

  // <- CBACT02C 9000-CARDFILE-CLOSE (L136-L152)
  /**
   * Closes the card master, the JPA analog of {@code CLOSE CARDFILE-FILE}. Closes the streaming
   * cursor opened by {@link #openCardfile()} to release the underlying JDBC cursor, then drops the
   * in-memory references; unlike a VSAM {@code CLOSE} it issues no failing-status branch to
   * translate. Closing the stream is null-safe and idempotent.
   */
  private void closeCardfile() {
    // Release the streaming JDBC cursor before dropping the in-memory references (the streamed
    // analog of CLOSE CARDFILE-FILE). Closing is null-safe and idempotent.
    if (this.cardStream != null) {
      this.cardStream.close();
      this.cardStream = null;
    }
    this.cursor = null;
    this.currentCard = null;
  }

  // <- CBACT02C 9999-ABEND-PROGRAM (L154-L158) + 9910-DISPLAY-IO-STATUS (L161-L174)
  /**
   * Builds the abend exception for an unexpected I/O status, reproducing the COBOL {@code DISPLAY
   * 'ERROR ...'} &rarr; {@code 9910-DISPLAY-IO-STATUS} &rarr; {@code 9999-ABEND-PROGRAM} sequence.
   * The caller {@code throw}s the returned value, so control transfers immediately just as the
   * COBOL {@code CALL 'CEE3ABD'} terminated the run unit.
   *
   * <p>It logs the operation-specific {@code ERROR ...} banner, constructs the {@link
   * IoStatusException} (which encodes {@link IoStatusException#BATCH_ABEND_CODE} = 999 and the
   * underlying cause), then logs the {@code 9910} operator line ({@link
   * IoStatusException#getDisplayMessage()}, the byte-faithful {@code 'FILE STATUS IS: NNNN...'}).
   *
   * @param operation the failing I/O verb ({@link #OP_OPEN} or {@link #OP_READ})
   * @param errorBanner the COBOL {@code DISPLAY 'ERROR ...'} text for this operation
   * @param cause the Spring data-access failure standing in for the unexpected VSAM status
   * @return the {@link IoStatusException} for the caller to throw
   */
  private IoStatusException buildAbend(
      String operation, String errorBanner, DataAccessException cause) {
    // DISPLAY 'ERROR OPENING/READING CARDFILE' (L110 / L129).
    LOG.error(errorBanner);
    IoStatusException failure =
        new IoStatusException(CARDFILE_DD_NAME, operation, IO_ERROR_STATUS, cause);
    // PERFORM 9910-DISPLAY-IO-STATUS: DISPLAY 'FILE STATUS IS: NNNN' IO-STATUS-04 (L168 / L172).
    LOG.error(failure.getDisplayMessage());
    return failure;
  }

  /**
   * Rebuilds the fixed-width 150-byte {@code CARD-RECORD} image that a COBOL {@code DISPLAY
   * CARD-RECORD} writes, concatenating every {@code CVACT02Y} field in declaration order using the
   * COBOL {@code MOVE} rules: {@code PIC X(n)} fields are left-justified and space-padded ({@link
   * #padX(String, int)}); {@code PIC 9(n)} fields are right-justified and zero-padded ({@link
   * #pad9(long, int)} / {@link #pad9(String, int)}). The trailing {@code FILLER PIC X(59)}, not
   * modeled on {@link Card}, is rendered as 59 spaces.
   *
   * @param card the record to render; never {@code null} (only called for a record just read)
   * @return the 150-character fixed-width record image
   */
  private static String formatCardRecord(Card card) {
    long acctId = (card.getCardAcctId() == null) ? 0L : card.getCardAcctId();
    return padX(card.getCardNum(), CARD_NUM_LENGTH)
        + pad9(acctId, CARD_ACCT_ID_LENGTH)
        + pad9(card.getCardCvvCd(), CARD_CVV_CD_LENGTH)
        + padX(card.getCardEmbossedName(), CARD_EMBOSSED_NAME_LENGTH)
        + padX(card.getCardExpiraionDate(), CARD_EXPIRAION_DATE_LENGTH)
        + padX(card.getCardActiveStatus(), CARD_ACTIVE_STATUS_LENGTH)
        + " ".repeat(CARD_FILLER_LENGTH);
  }

  /**
   * Reproduces a COBOL {@code MOVE} into a {@code PIC X(width)} alphanumeric field: left-justified,
   * right-space-padded, and right-truncated when the source is longer. A {@code null} source is
   * treated as COBOL {@code SPACES}.
   *
   * @param value the source text ({@code null} treated as spaces)
   * @param width the fixed receiving-field length
   * @return a string of exactly {@code width} characters
   */
  private static String padX(String value, int width) {
    String source = (value == null) ? "" : value;
    if (source.length() >= width) {
      return source.substring(0, width);
    }
    return source + " ".repeat(width - source.length());
  }

  /**
   * Reproduces a COBOL numeric-display {@code MOVE} into a {@code PIC 9(width)} field from a
   * string: right-justified, left-zero-padded, with high-order (left-most) truncation when the
   * source is longer. A {@code null} or empty source yields {@code width} zeros.
   *
   * @param value the source digits ({@code null} treated as empty)
   * @param width the fixed receiving-field length
   * @return a string of exactly {@code width} characters
   */
  private static String pad9(String value, int width) {
    String source = (value == null) ? "" : value;
    if (source.length() >= width) {
      return source.substring(source.length() - width);
    }
    return "0".repeat(width - source.length()) + source;
  }

  /**
   * Renders a {@code long} as zero-padded {@code PIC 9(width)} numeric-display digits. The
   * absolute-value digits are emitted (any sign is dropped, matching an unsigned display item);
   * overflow follows the same high-order truncation as {@link #pad9(String, int)}.
   *
   * @param value the numeric value whose absolute-value digits are emitted
   * @param width the fixed receiving-field length
   * @return a string of exactly {@code width} unsigned digits
   */
  private static String pad9(long value, int width) {
    String digits = Long.toString(value);
    if (!digits.isEmpty() && digits.charAt(0) == '-') {
      digits = digits.substring(1);
    }
    return pad9(digits, width);
  }
}
