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
import com.aws.carddemo.domain.DailyTransaction;
import com.aws.carddemo.exception.IoStatusException;
import com.aws.carddemo.repository.AccountRepository;
import com.aws.carddemo.repository.CardXrefRepository;
import com.aws.carddemo.repository.DailyTransactionRepository;
import java.util.Iterator;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;

/**
 * Batch service that reproduces &mdash; with 100% behavioral parity (Agent Action Plan &sect;0.1.1,
 * &sect;0.7.1) &mdash; the legacy COBOL batch program {@code CBTRN01C} (source {@code
 * legacy/app/cbl/CBTRN01C.cbl}).
 *
 * <p><strong>Validation-only pass (despite the "post" name).</strong> {@code CBTRN01C} reads every
 * record of the sequential daily-transaction file and, for each one, verifies that the card can be
 * resolved through the card cross-reference and that the referenced account exists. It performs
 * <em>no</em> posting, <em>no</em> writes/rewrites, and sets <em>no</em> {@code RETURN-CODE} (the
 * COBOL {@code GOBACK} leaves RC at its default {@code 0}). The actual posting archetype is {@code
 * CBTRN02C}, migrated separately as {@code TransactionPostingService}; this class deliberately
 * never mutates any store. The {@link #run()} method is therefore {@code void}: the absence of a
 * return value is the faithful translation of "no RETURN-CODE is set".
 *
 * <p><strong>Files actually read vs. merely opened.</strong> The COBOL program opens six files
 * (DALYTRAN, CUSTFILE, XREFFILE, CARDFILE, ACCTFILE, TRANFILE) and closes them again, but inside
 * the processing loop it reads only three: the sequential {@code DALYTRAN-FILE}, the keyed {@code
 * XREF-FILE} (random read by card number) and the keyed {@code ACCOUNT-FILE} (random read by
 * account id). Consequently only three Spring Data repositories are injected &mdash; {@link
 * DailyTransactionRepository}, {@link CardXrefRepository} and {@link AccountRepository}. The
 * customer, card and transaction files are opened/closed but never read, so their {@code
 * OPEN}/{@code CLOSE} paragraphs map to documented no-op methods (retained purely for 1:1 paragraph
 * traceability, AAP &sect;0.6.7) and their repositories are intentionally <em>not</em> injected to
 * keep the zero-warning build free of unused dependencies (AAP &sect;0.7.3).
 *
 * <p><strong>Control-flow fidelity, including the read-ahead idiom.</strong> The {@code MAIN-PARA}
 * loop (CBTRN01C L168-L188) advances the daily-transaction cursor at the top of each iteration and
 * guards only the {@code DISPLAY DALYTRAN-RECORD} with the post-read end-of-file test; the
 * cross-reference lookup and account read run on <em>every</em> iteration of the outer guard. The
 * legacy {@code READ ... INTO} leaves the record area unchanged at end-of-file, so on the iteration
 * that detects EOF the verification runs once more against the last record already in storage. This
 * service preserves that idiom verbatim (the last record is re-verified once at EOF) so the emitted
 * diagnostics match the legacy run exactly; because the pass never posts, the extra verification
 * has no side effect beyond the duplicated log lines.
 *
 * <p><strong>FILE STATUS / abend mapping (AAP &sect;0.6.4, &sect;0.6.6).</strong> For the
 * sequential daily-transaction file, status {@code '00'} is a normal read, status {@code '10'} is
 * end-of-file (loop termination, not an error), and any other condition is an unrecoverable I/O
 * error that the COBOL handles via {@code Z-DISPLAY-IO-STATUS} + {@code Z-ABEND-PROGRAM} ({@code
 * MOVE 999 TO ABCODE}; {@code CALL 'CEE3ABD'}). In the JPA model a genuine I/O failure surfaces as
 * a Spring {@link DataAccessException}; this service translates it into an {@link
 * IoStatusException} (see {@link #abend(String, String, DataAccessException)}) so the externally
 * observable status line is preserved and the Spring Batch step fails. A card or account that
 * cannot be resolved is <em>not</em> an abend: it is the ordinary VSAM {@code INVALID KEY} / FILE
 * STATUS {@code '23'} skip path, modeled as {@link Optional#empty()} with a status flag of {@code
 * 4} and a diagnostic message &mdash; never a thrown {@code RecordNotFoundException} (which is
 * reserved for the online layer).
 *
 * <p><strong>Decimal fidelity.</strong> No monetary arithmetic occurs in this validation pass, but
 * the project-wide rule still holds: decimal fields are always {@link java.math.BigDecimal} on the
 * entities, never {@code float}/{@code double} (AAP &sect;0.6.1).
 *
 * <p><strong>Execution model.</strong> Mirroring the single-threaded COBOL run unit, the per-run
 * working state (cursor, current records, lookup keys, status flags and the end-of-file switch) is
 * held in instance fields that reproduce the program's {@code WORKING-STORAGE}. {@link #run()}
 * re-initializes that state on entry, so the bean may be reused for successive runs, but &mdash;
 * like the original program &mdash; a single {@link #run()} invocation constitutes one run unit and
 * the bean is not designed for concurrent invocation. Spring Batch executes the step that drives
 * this service single-threaded. Per AAP &sect;0.4.1 no JCL driver shipped for {@code CBTRN01C}, so
 * {@link #run()} is a plain method (no Spring Batch types are referenced here) intended to be
 * invoked by a future {@code DailyTransactionPostJobConfig} in {@code com.aws.carddemo.batch}.
 *
 * <p><strong>COBOL paragraph &rarr; Java method traceability</strong> (AAP &sect;0.6.7):
 *
 * <ul>
 *   <li>{@code MAIN-PARA} &rarr; {@link #run()}
 *   <li>{@code 1000-DALYTRAN-GET-NEXT} &rarr; {@link #dalytranGetNext()}
 *   <li>{@code 2000-LOOKUP-XREF} &rarr; {@link #lookupXref()}
 *   <li>{@code 3000-READ-ACCOUNT} &rarr; {@link #readAccount()}
 *   <li>{@code 0000-DALYTRAN-OPEN} &rarr; {@link #openDalytran()}
 *   <li>{@code 0100-CUSTFILE-OPEN} &rarr; {@link #openCustfile()} (no-op)
 *   <li>{@code 0200-XREFFILE-OPEN} &rarr; {@link #openXreffile()} (no-op)
 *   <li>{@code 0300-CARDFILE-OPEN} &rarr; {@link #openCardfile()} (no-op)
 *   <li>{@code 0400-ACCTFILE-OPEN} &rarr; {@link #openAcctfile()} (no-op)
 *   <li>{@code 0500-TRANFILE-OPEN} &rarr; {@link #openTranfile()} (no-op)
 *   <li>{@code 9000-DALYTRAN-CLOSE} &rarr; {@link #closeDalytran()}
 *   <li>{@code 9100-CUSTFILE-CLOSE} &rarr; {@link #closeCustfile()} (no-op)
 *   <li>{@code 9200-XREFFILE-CLOSE} &rarr; {@link #closeXreffile()} (no-op)
 *   <li>{@code 9300-CARDFILE-CLOSE} &rarr; {@link #closeCardfile()} (no-op)
 *   <li>{@code 9400-ACCTFILE-CLOSE} &rarr; {@link #closeAcctfile()} (no-op)
 *   <li>{@code 9500-TRANFILE-CLOSE} &rarr; {@link #closeTranfile()} (no-op)
 *   <li>{@code Z-ABEND-PROGRAM} + {@code Z-DISPLAY-IO-STATUS} &rarr; {@link #abend(String, String,
 *       DataAccessException)}
 * </ul>
 */
@Service
public class DailyTransactionPostService {

  /** SLF4J logger; the COBOL {@code DISPLAY} verb maps to {@code LOG} output. */
  private static final Logger LOG = LoggerFactory.getLogger(DailyTransactionPostService.class);

  /**
   * Synthetic two-byte FILE STATUS used when translating a Spring {@link DataAccessException} into
   * an {@link IoStatusException}. The JPA model has no native COBOL FILE STATUS byte pair, so the
   * non-{@code '00'}/{@code '10'} "other status" abend branch (CBTRN01C {@code
   * Z-DISPLAY-IO-STATUS}) is represented with {@code "99"}, mirroring the abend status convention
   * used across the migrated batch services.
   */
  private static final String ABEND_FILE_STATUS = "99";

  /** Repository backing the sequential {@code DALYTRAN-FILE} (copybook {@code CVTRA06Y}). */
  private final DailyTransactionRepository dailyTransactionRepository;

  /** Repository backing the keyed {@code XREF-FILE} (copybook {@code CVACT03Y}). */
  private final CardXrefRepository cardXrefRepository;

  /** Repository backing the keyed {@code ACCOUNT-FILE} (copybook {@code CVACT01Y}). */
  private final AccountRepository accountRepository;

  // ---------------------------------------------------------------------------------------------
  // Per-run-unit working state mirroring the COBOL WORKING-STORAGE SECTION. Reset by run() on
  // entry;
  // not safe for concurrent invocation (one run() call == one COBOL run unit), as documented above.
  // ---------------------------------------------------------------------------------------------

  /** Sequential cursor over the daily-transaction store (the {@code DALYTRAN-FILE} read order). */
  private Iterator<DailyTransaction> dalytranCursor;

  /**
   * Current {@code DALYTRAN-RECORD} (CVTRA06Y); retained at EOF, mirroring {@code READ ... INTO}.
   */
  private DailyTransaction dalytranRecord;

  /** Current {@code CARD-XREF-RECORD} (CVACT03Y) resolved by {@link #lookupXref()}. */
  private CardXref cardXrefRecord;

  /**
   * Lookup key {@code XREF-CARD-NUM}, copied from {@code DALYTRAN-CARD-NUM} before the xref read.
   */
  private String xrefCardNum;

  /** Lookup key {@code ACCT-ID}, copied from {@code XREF-ACCT-ID} before the account read. */
  private Long acctId;

  /**
   * The {@code END-OF-DAILY-TRANS-FILE} switch ({@code 'Y'} once the sequential read is exhausted).
   */
  private boolean endOfDailyTransFile;

  /**
   * {@code WS-XREF-READ-STATUS} (PIC 9(04)); {@code 0} = resolved, {@code 4} = INVALID KEY skip.
   */
  private int xrefReadStatus;

  /** {@code WS-ACCT-READ-STATUS} (PIC 9(04)); {@code 0} = resolved, {@code 4} = INVALID KEY. */
  private int acctReadStatus;

  /**
   * Creates the service, injecting only the three repositories whose files are actually read by
   * {@code CBTRN01C} (DALYTRAN, XREF, ACCOUNT). The customer, card and transaction repositories are
   * intentionally omitted because those files are opened and closed but never read, so injecting
   * them would introduce unused dependencies (AAP &sect;0.7.3 zero-warning build).
   *
   * @param dailyTransactionRepository repository for the sequential daily-transaction store
   * @param cardXrefRepository repository for the card cross-reference store (keyed by card number)
   * @param accountRepository repository for the account master store (keyed by account id)
   */
  public DailyTransactionPostService(
      DailyTransactionRepository dailyTransactionRepository,
      CardXrefRepository cardXrefRepository,
      AccountRepository accountRepository) {
    this.dailyTransactionRepository = dailyTransactionRepository;
    this.cardXrefRepository = cardXrefRepository;
    this.accountRepository = accountRepository;
  }

  /**
   * Executes the daily-transaction validation pass, reproducing {@code CBTRN01C MAIN-PARA}
   * (L156-L197) statement-for-statement and in order.
   *
   * <p>The method logs the start banner, opens the six files in the legacy order, drives the
   * read-ahead processing loop until end-of-file, closes the six files in the legacy order, and
   * logs the end banner. It returns {@code void} and sets no return code, faithfully reproducing
   * the COBOL {@code GOBACK} that leaves {@code RETURN-CODE} at its default {@code 0}. No store is
   * ever mutated.
   *
   * @throws IoStatusException if an unrecoverable I/O error occurs while opening or reading the
   *     daily-transaction file (the {@code Z-ABEND-PROGRAM} equivalent); a card or account that
   *     cannot be resolved is handled as a normal skip and never raises this exception
   */
  public void run() {
    // <- CBTRN01C MAIN-PARA
    LOG.info("START OF EXECUTION OF PROGRAM CBTRN01C");

    openDalytran(); // PERFORM 0000-DALYTRAN-OPEN
    openCustfile(); // PERFORM 0100-CUSTFILE-OPEN
    openXreffile(); // PERFORM 0200-XREFFILE-OPEN
    openCardfile(); // PERFORM 0300-CARDFILE-OPEN
    openAcctfile(); // PERFORM 0400-ACCTFILE-OPEN
    openTranfile(); // PERFORM 0500-TRANFILE-OPEN

    // PERFORM UNTIL END-OF-DAILY-TRANS-FILE = 'Y'
    while (!endOfDailyTransFile) {
      // IF END-OF-DAILY-TRANS-FILE = 'N' -- the COBOL outer guard. It is logically redundant with
      // the loop condition (the loop is only entered when the switch is 'N'), but it is preserved
      // verbatim for 1:1 control-flow traceability.
      if (!endOfDailyTransFile) {
        dalytranGetNext(); // PERFORM 1000-DALYTRAN-GET-NEXT

        // IF END-OF-DAILY-TRANS-FILE = 'N' -- guards ONLY the record display; the verification
        // below
        // runs even on the EOF-detecting iteration (the legacy read-ahead idiom, see the class
        // docs).
        if (!endOfDailyTransFile) {
          LOG.info("{}", dalytranRecord); // DISPLAY DALYTRAN-RECORD
        }

        xrefReadStatus = 0; // MOVE 0 TO WS-XREF-READ-STATUS
        xrefCardNum =
            dalytranRecord.getDalytranCardNum(); // MOVE DALYTRAN-CARD-NUM TO XREF-CARD-NUM
        lookupXref(); // PERFORM 2000-LOOKUP-XREF

        if (xrefReadStatus == 0) { // IF WS-XREF-READ-STATUS = 0
          acctReadStatus = 0; // MOVE 0 TO WS-ACCT-READ-STATUS
          acctId = cardXrefRecord.getXrefAcctId(); // MOVE XREF-ACCT-ID TO ACCT-ID
          readAccount(); // PERFORM 3000-READ-ACCOUNT
          if (acctReadStatus != 0) { // IF WS-ACCT-READ-STATUS NOT = 0
            LOG.info("ACCOUNT {} NOT FOUND", acctId); // DISPLAY 'ACCOUNT ' ACCT-ID ' NOT FOUND'
          }
        } else { // ELSE -- card could not be verified; skip (no posting occurs in this pass)
          // COBOL parity (CBTRN01C): DISPLAY 'CARD NUMBER ' DALYTRAN-CARD-NUM ' COULD NOT BE
          // VERIFIED. SKIPPING TRANSACTION ID-' DALYTRAN-ID. The card number (PAN) is masked to its
          // last four digits so no full PAN reaches the logs (AAP §0.6.6, §0.7.3); the (non-PAN)
          // transaction id is retained for operational triage of the skip.
          LOG.info(
              "CARD NUMBER {} COULD NOT BE VERIFIED. SKIPPING TRANSACTION ID-{}",
              maskCardNumber(dalytranRecord.getDalytranCardNum()),
              dalytranRecord.getDalytranId());
        }
      }
    }

    closeDalytran(); // PERFORM 9000-DALYTRAN-CLOSE
    closeCustfile(); // PERFORM 9100-CUSTFILE-CLOSE
    closeXreffile(); // PERFORM 9200-XREFFILE-CLOSE
    closeCardfile(); // PERFORM 9300-CARDFILE-CLOSE
    closeAcctfile(); // PERFORM 9400-ACCTFILE-CLOSE
    closeTranfile(); // PERFORM 9500-TRANFILE-CLOSE

    LOG.info("END OF EXECUTION OF PROGRAM CBTRN01C");
    // GOBACK -- no RETURN-CODE is set; the method returns void.
  }

  /**
   * Advances the sequential daily-transaction cursor, reproducing {@code 1000-DALYTRAN-GET-NEXT}
   * (L202-L225).
   *
   * <p>When a record is available it becomes the current {@code DALYTRAN-RECORD} (FILE STATUS
   * {@code '00'}). When the cursor is exhausted the {@code END-OF-DAILY-TRANS-FILE} switch is set
   * (FILE STATUS {@code '10'}, end-of-file &mdash; loop termination, not an error) and, exactly as
   * the COBOL {@code READ ... INTO} leaves its record area unchanged at EOF, the current record is
   * left in place so the read-ahead idiom in {@link #run()} re-verifies it once. Any other failure
   * surfaces as a {@link DataAccessException} and is translated into an abend.
   */
  private void dalytranGetNext() {
    // <- CBTRN01C 1000-DALYTRAN-GET-NEXT
    try {
      if (dalytranCursor.hasNext()) {
        dalytranRecord = dalytranCursor.next(); // FILE STATUS '00'
      } else {
        endOfDailyTransFile = true; // FILE STATUS '10' -- end of file, not an error
      }
    } catch (DataAccessException ex) {
      LOG.error("ERROR READING DAILY TRANSACTION FILE");
      throw abend("DALYTRAN", "READ", ex);
    }
  }

  /**
   * Resolves the current card number through the card cross-reference, reproducing {@code
   * 2000-LOOKUP-XREF} (L227-L239).
   *
   * <p>A present row (the COBOL {@code NOT INVALID KEY} branch) is kept as the current {@code
   * CARD-XREF-RECORD} and the four diagnostic lines are emitted. An absent row (VSAM {@code INVALID
   * KEY}, FILE STATUS {@code '23'}) maps to {@link Optional#empty()}: the invalid-card message is
   * logged and {@link #xrefReadStatus} is set to {@code 4}. This is a normal skip path &mdash; no
   * exception is thrown.
   */
  private void lookupXref() {
    // <- CBTRN01C 2000-LOOKUP-XREF
    // MOVE XREF-CARD-NUM TO FD-XREF-CARD-NUM; READ XREF-FILE KEY IS FD-XREF-CARD-NUM
    Optional<CardXref> xref = cardXrefRepository.findById(xrefCardNum);
    if (xref.isPresent()) { // NOT INVALID KEY
      cardXrefRecord = xref.get();
      // COBOL parity (CBTRN01C 2000-LOOKUP-XREF): DISPLAY 'SUCCESSFUL READ OF XREF' / CARD NUMBER /
      // ACCOUNT ID / CUSTOMER ID. Reproduced ONLY as a guarded, DEBUG-level (off by default)
      // diagnostic so no cardholder PII reaches production logs (AAP §0.1.1 security hardening,
      // §0.7.3). The card number (PAN) is masked to its last four digits using the project-wide
      // CardDemoCommarea convention (AAP §0.6.6); the account and customer ids surface only when
      // DEBUG diagnostics are explicitly enabled.
      if (LOG.isDebugEnabled()) {
        LOG.debug("SUCCESSFUL READ OF XREF");
        LOG.debug("CARD NUMBER: {}", maskCardNumber(cardXrefRecord.getXrefCardNum()));
        LOG.debug("ACCOUNT ID : {}", cardXrefRecord.getXrefAcctId());
        LOG.debug("CUSTOMER ID: {}", cardXrefRecord.getXrefCustId());
      }
    } else { // INVALID KEY -> FILE STATUS '23'
      LOG.info("INVALID CARD NUMBER FOR XREF");
      xrefReadStatus = 4;
    }
  }

  /**
   * Verifies that the referenced account exists, reproducing {@code 3000-READ-ACCOUNT} (L241-L250).
   *
   * <p>The account is read by its key purely to confirm existence (this validation pass never uses
   * the account fields). A present row is the COBOL {@code NOT INVALID KEY} branch; an absent row
   * (VSAM {@code INVALID KEY}, FILE STATUS {@code '23'}) logs the invalid-account message and sets
   * {@link #acctReadStatus} to {@code 4}. No exception is thrown.
   */
  private void readAccount() {
    // <- CBTRN01C 3000-READ-ACCOUNT
    // MOVE ACCT-ID TO FD-ACCT-ID; READ ACCOUNT-FILE KEY IS FD-ACCT-ID
    if (accountRepository.findById(acctId).isPresent()) { // NOT INVALID KEY
      LOG.info("SUCCESSFUL READ OF ACCOUNT FILE");
    } else { // INVALID KEY -> FILE STATUS '23'
      LOG.info("INVALID ACCOUNT NUMBER FOUND");
      acctReadStatus = 4;
    }
  }

  /**
   * Opens the daily-transaction file, reproducing {@code 0000-DALYTRAN-OPEN} (L252-L268).
   *
   * <p>In the JPA model "opening" the sequential file means materializing its records in
   * primary-key order and establishing a cursor over them. The ordering uses a {@link Sort} on the
   * {@code dalytranId} property (ascending by the primary key) rather than a bespoke repository
   * query, preserving the legacy sequential read order. The working state is reset here so the bean
   * can be reused across runs; the current record is initialized to a blank instance so the
   * degenerate empty-file case performs exactly one verification pass over a blank record &mdash;
   * mirroring the COBOL space-initialized {@code WORKING-STORAGE} record and the read-ahead loop
   * &mdash; without a {@link NullPointerException}. A failure to open maps to an abend.
   */
  private void openDalytran() {
    // <- CBTRN01C 0000-DALYTRAN-OPEN
    try {
      dalytranCursor = dailyTransactionRepository.findAll(Sort.by("dalytranId")).iterator();
    } catch (DataAccessException ex) {
      LOG.error("ERROR OPENING DAILY TRANSACTION FILE");
      throw abend("DALYTRAN", "OPEN", ex);
    }
    endOfDailyTransFile = false;
    dalytranRecord = new DailyTransaction();
    dalytranRecord.setDalytranCardNum("");
    dalytranRecord.setDalytranId("");
  }

  /**
   * Reproduces {@code 0100-CUSTFILE-OPEN} (L271-L287) as a no-op.
   *
   * <p>{@code CBTRN01C} opens {@code CUSTOMER-FILE} but never reads it in the processing loop; in
   * the JPA model there is no file handle to open, so this method intentionally does nothing. It is
   * retained for 100% paragraph-to-method traceability (AAP &sect;0.6.7).
   */
  private void openCustfile() {
    // <- CBTRN01C 0100-CUSTFILE-OPEN (no-op: file opened in COBOL but never read; no JPA open
    // needed)
  }

  /**
   * Reproduces {@code 0200-XREFFILE-OPEN} (L289-L305) as a no-op.
   *
   * <p>The cross-reference is reached by key via {@link CardXrefRepository#findById(Object)}, which
   * needs no cursor or explicit open. Retained for paragraph traceability (AAP &sect;0.6.7).
   */
  private void openXreffile() {
    // <- CBTRN01C 0200-XREFFILE-OPEN (no-op: keyed access via findById needs no cursor)
  }

  /**
   * Reproduces {@code 0300-CARDFILE-OPEN} (L307-L323) as a no-op.
   *
   * <p>{@code CBTRN01C} opens {@code CARD-FILE} but never reads it in the processing loop; there is
   * no JPA open to perform. Retained for paragraph traceability (AAP &sect;0.6.7).
   */
  private void openCardfile() {
    // <- CBTRN01C 0300-CARDFILE-OPEN (no-op: file opened in COBOL but never read; no JPA open
    // needed)
  }

  /**
   * Reproduces {@code 0400-ACCTFILE-OPEN} (L325-L341) as a no-op.
   *
   * <p>The account master is reached by key via {@link AccountRepository#findById(Object)}, which
   * needs no cursor or explicit open. Retained for paragraph traceability (AAP &sect;0.6.7).
   */
  private void openAcctfile() {
    // <- CBTRN01C 0400-ACCTFILE-OPEN (no-op: keyed access via findById needs no cursor)
  }

  /**
   * Reproduces {@code 0500-TRANFILE-OPEN} (L343-L359) as a no-op.
   *
   * <p>{@code CBTRN01C} opens {@code TRANSACT-FILE} but never reads it in the processing loop;
   * there is no JPA open to perform. Retained for paragraph traceability (AAP &sect;0.6.7).
   */
  private void openTranfile() {
    // <- CBTRN01C 0500-TRANFILE-OPEN (no-op: file opened in COBOL but never read; no JPA open
    // needed)
  }

  /**
   * Closes the daily-transaction file, reproducing {@code 9000-DALYTRAN-CLOSE} (L361-L377) by
   * releasing the sequential cursor. Closing an in-memory cursor cannot raise an I/O error, so
   * unlike the open and read paragraphs there is no abend branch here.
   */
  private void closeDalytran() {
    // <- CBTRN01C 9000-DALYTRAN-CLOSE
    dalytranCursor = null; // release the sequential cursor
  }

  /**
   * Reproduces {@code 9100-CUSTFILE-CLOSE} (L379-L395) as a no-op (the customer file was never
   * opened/read in the JPA model). Retained for paragraph traceability (AAP &sect;0.6.7).
   */
  private void closeCustfile() {
    // <- CBTRN01C 9100-CUSTFILE-CLOSE (no-op)
  }

  /**
   * Reproduces {@code 9200-XREFFILE-CLOSE} (L397-L413) as a no-op (keyed access needs no cursor to
   * release). Retained for paragraph traceability (AAP &sect;0.6.7).
   */
  private void closeXreffile() {
    // <- CBTRN01C 9200-XREFFILE-CLOSE (no-op)
  }

  /**
   * Reproduces {@code 9300-CARDFILE-CLOSE} (L415-L431) as a no-op (the card file was never
   * opened/read in the JPA model). Retained for paragraph traceability (AAP &sect;0.6.7).
   */
  private void closeCardfile() {
    // <- CBTRN01C 9300-CARDFILE-CLOSE (no-op)
  }

  /**
   * Reproduces {@code 9400-ACCTFILE-CLOSE} (L433-L449) as a no-op (keyed access needs no cursor to
   * release). Retained for paragraph traceability (AAP &sect;0.6.7).
   */
  private void closeAcctfile() {
    // <- CBTRN01C 9400-ACCTFILE-CLOSE (no-op)
  }

  /**
   * Reproduces {@code 9500-TRANFILE-CLOSE} (L451-L467) as a no-op (the transaction file was never
   * opened/read in the JPA model). Retained for paragraph traceability (AAP &sect;0.6.7).
   */
  private void closeTranfile() {
    // <- CBTRN01C 9500-TRANFILE-CLOSE (no-op)
  }

  /**
   * Builds the unrecoverable-I/O abend, reproducing {@code Z-DISPLAY-IO-STATUS} (L476-L489) and
   * {@code Z-ABEND-PROGRAM} (L469-L473).
   *
   * <p>The returned {@link IoStatusException} carries the synthetic {@link #ABEND_FILE_STATUS} so
   * its {@link IoStatusException#getDisplayMessage()} reproduces the byte-faithful {@code 'FILE
   * STATUS IS: NNNN...'} operator line, which is logged here together with the {@code 'ABENDING
   * PROGRAM'} banner before the exception is returned to the caller to be thrown (the {@code CALL
   * 'CEE3ABD'} equivalent). The caller logs the operation-specific {@code 'ERROR ...'} line first,
   * matching the COBOL {@code DISPLAY} sequence.
   *
   * @param fileName the logical file name that failed (for example {@code "DALYTRAN"})
   * @param operation the failing I/O verb ({@code "OPEN"} or {@code "READ"})
   * @param cause the underlying Spring data-access failure
   * @return the {@link IoStatusException} for the caller to throw
   */
  private IoStatusException abend(String fileName, String operation, DataAccessException cause) {
    // <- CBTRN01C Z-DISPLAY-IO-STATUS + Z-ABEND-PROGRAM
    IoStatusException ex = new IoStatusException(fileName, operation, ABEND_FILE_STATUS, cause);
    LOG.error(ex.getDisplayMessage()); // Z-DISPLAY-IO-STATUS: 'FILE STATUS IS: NNNN'...
    LOG.error("ABENDING PROGRAM"); // Z-ABEND-PROGRAM
    return ex;
  }

  /**
   * Masks a card number (PAN) for safe inclusion in diagnostic log output, revealing at most the
   * last four digits and replacing every earlier digit with {@code '*'}. This matches the
   * project-wide convention used by {@code CardDemoCommarea} and {@code TransactionReportService}
   * (AAP &sect;0.6.6 PII hygiene), so the full PAN is never written to application or test logs.
   *
   * @param cardNum the raw card number; {@code null} renders as the literal {@code "null"}
   * @return the masked card number (for example {@code "************3456"}), never the full PAN
   */
  private static String maskCardNumber(String cardNum) {
    if (cardNum == null) {
      return "null";
    }
    String trimmed = cardNum.trim();
    if (trimmed.length() <= 4) {
      return "*".repeat(trimmed.length());
    }
    int maskLength = trimmed.length() - 4;
    return "*".repeat(maskLength) + trimmed.substring(maskLength);
  }

  /**
   * Returns the {@code WS-XREF-READ-STATUS} flag after a run for white-box unit testing: {@code 0}
   * when the last cross-reference lookup resolved, {@code 4} when it hit an INVALID KEY skip.
   *
   * @return the cross-reference read-status flag ({@code 0} or {@code 4})
   */
  int getXrefReadStatus() {
    return xrefReadStatus;
  }

  /**
   * Returns the {@code WS-ACCT-READ-STATUS} flag after a run for white-box unit testing: {@code 0}
   * when the last account read resolved (or was never attempted), {@code 4} when it hit an INVALID
   * KEY.
   *
   * @return the account read-status flag ({@code 0} or {@code 4})
   */
  int getAcctReadStatus() {
    return acctReadStatus;
  }
}
