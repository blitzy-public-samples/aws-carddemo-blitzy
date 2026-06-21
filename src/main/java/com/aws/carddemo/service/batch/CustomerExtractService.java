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

import com.aws.carddemo.domain.Customer;
import com.aws.carddemo.exception.IoStatusException;
import com.aws.carddemo.repository.CustomerRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.util.Iterator;
import java.util.stream.Stream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;

/**
 * Batch <strong>customer-master extract</strong> service &mdash; the Java translation of the legacy
 * batch COBOL program {@code CBCUS01C} ("Read and print customer data file"; behavioral spec {@code
 * legacy/app/cbl/CBCUS01C.cbl}, record copybook {@code legacy/app/cpy/CVCUS01Y.cpy}).
 *
 * <p>This service reproduces &mdash; with 100% behavioral parity (Agent Action Plan &sect;0.1.2,
 * &sect;0.6.7) &mdash; the simple sequential extract archetype shared with {@code
 * AccountExtractService} (&larr; {@code CBACT01C}): it opens the customer master in ascending
 * primary-key order, walks every record to end-of-file, emits each record to the job log, then
 * closes the file. The legacy VSAM {@code CUSTFILE} KSDS (indexed, {@code ACCESS MODE IS
 * SEQUENTIAL}, {@code RECORD KEY IS FD-CUST-ID}) is replaced by {@link CustomerRepository}; the
 * sequential ascending read becomes a bounded-memory iteration over {@code
 * streamAllByOrderByCustIdAsc()} so the iteration order matches the legacy key order exactly (AAP
 * &sect;0.6.2) without materialising the whole table.
 *
 * <p><strong>COBOL paragraph &rarr; Java method traceability</strong> (AAP &sect;0.6.7; see also
 * {@code docs/traceability-matrix.md}):
 *
 * <ul>
 *   <li>{@code PROCEDURE DIVISION} (main, L70-88) &rarr; {@link #run()}
 *   <li>{@code 0000-CUSTFILE-OPEN} (L118-134) &rarr; {@link #openCustfile()}
 *   <li>{@code 1000-CUSTFILE-GET-NEXT} (L92-116) &rarr; {@link #custfileGetNext()}
 *   <li>{@code 9000-CUSTFILE-CLOSE} (L136-152) &rarr; {@link #closeCustfile()}
 *   <li>{@code Z-DISPLAY-IO-STATUS} + {@code Z-ABEND-PROGRAM} (L154-174) &rarr; {@link
 *       #raiseAbend(String, DataAccessException)}
 * </ul>
 *
 * <p><strong>Parity-critical quirk &mdash; the record is logged twice.</strong> {@code CBCUS01C}
 * emits every customer record <em>two</em> times per iteration: once inside {@code
 * 1000-CUSTFILE-GET-NEXT} on a successful ({@code '00'}) read, and again in the {@code PROCEDURE
 * DIVISION} main loop ({@code DISPLAY CUSTOMER-RECORD}) when the read did not hit end-of-file. Both
 * emissions are preserved here ({@link #custfileGetNext()} performs the first, {@link #run()} the
 * second); they are intentionally <em>not</em> collapsed into a single log call.
 *
 * <p><strong>Abend / {@code FILE STATUS} mapping.</strong> A normal read is {@code FILE STATUS
 * '00'}; sequential end-of-file is {@code '10'} (loop termination, never an error). Any other
 * condition &mdash; surfaced here as a Spring {@link DataAccessException} from the repository
 * &mdash; reproduces the COBOL {@code DISPLAY 'ERROR ...'} + {@code Z-DISPLAY-IO-STATUS} + {@code
 * Z-ABEND-PROGRAM} path by throwing an {@link IoStatusException} (which carries {@link
 * IoStatusException#BATCH_ABEND_CODE} {@code = 999}, the {@code CEE3ABD} {@code ABCODE}). Note that
 * {@code CBCUS01C} names its abend paragraphs {@code Z-ABEND-PROGRAM} / {@code Z-DISPLAY-IO-STATUS}
 * (not the {@code 9999} / {@code 9910} names used by the posting archetype), but the semantics are
 * identical. A record-not-found ({@code '23'}) condition does not arise on a forward sequential
 * scan, so {@code RecordNotFoundException} is never thrown and no error is ever swallowed (AAP
 * &sect;0.6.4).
 *
 * <p><strong>Threading.</strong> Like the single-run legacy batch program, this service drives one
 * extract per {@link #run()} invocation using mutable per-run cursor state ({@link #cursor}, {@link
 * #endOfFile}, {@link #currentCustomer}) that mirrors COBOL {@code WORKING-STORAGE}. {@link #run()}
 * fully re-initializes that state through {@link #openCustfile()} on entry, so successive
 * sequential runs are independent; it is, however, intended for <em>single-threaded</em> batch use
 * (the {@code com.aws.carddemo.batch} job layer invokes it from a single step thread) and is not
 * safe for concurrent {@link #run()} calls.
 *
 * <p><strong>Framework boundary.</strong> This class deliberately carries <em>no</em> Spring Batch
 * dependency: it is a plain Spring {@link Service} exposing {@link #run()}. The {@code
 * com.aws.carddemo.batch} layer ({@code CustomerExtractJobConfig}) wraps this method in a tasklet /
 * step and owns {@code JobParameters}, scheduling, and exit-status translation.
 */
@Service
public class CustomerExtractService {

  /**
   * SLF4J logger. The COBOL {@code DISPLAY} statements (program start/end banners, each {@code
   * CUSTOMER-RECORD} image, the {@code 'ERROR ...'} lines, and the {@code Z-DISPLAY-IO-STATUS} /
   * {@code Z-ABEND-PROGRAM} banners) are reproduced as log events on this logger.
   */
  private static final Logger LOG = LoggerFactory.getLogger(CustomerExtractService.class);

  /**
   * Logical file name reported in {@link IoStatusException} on an unexpected I/O condition,
   * mirroring the COBOL {@code ASSIGN TO CUSTFILE} name.
   */
  private static final String CUSTFILE = "CUSTFILE";

  /** COBOL {@code OPEN} verb, used when {@link #openCustfile()} fails. */
  private static final String OP_OPEN = "OPEN";

  /** COBOL {@code READ} verb, used when {@link #custfileGetNext()} fails. */
  private static final String OP_READ = "READ";

  /**
   * Synthetic two-byte {@code FILE STATUS} stamped onto an {@link IoStatusException} for the
   * "unexpected I/O" branch. The JPA abstraction exposes no genuine VSAM status, so the {@code '9'}
   * status class &mdash; VSAM's implementation-defined error class &mdash; is used, matching the
   * COBOL {@code ELSE MOVE 12 TO APPL-RESULT} "other status" branch (AAP &sect;0.6.4).
   */
  private static final String UNEXPECTED_FILE_STATUS = "99";

  /**
   * Repository backing the legacy {@code CUSTFILE} KSDS (copybook {@code CVCUS01Y}; {@code RECORD
   * KEY IS FD-CUST-ID}). Injected by constructor for immutability and testability.
   */
  private final CustomerRepository customerRepository;

  /**
   * Transaction-scoped entity manager used to {@code detach} each streamed record immediately after
   * it is read, so the persistence context does not accumulate the whole table (the bounded-memory
   * complement to the streaming cursor). Injected by the container; bound to the tasklet
   * transaction that brackets {@link #run()}.
   */
  @PersistenceContext private EntityManager entityManager;

  /**
   * Open streaming cursor over the customer master (ascending {@code custId}), held so it can be
   * closed in {@link #closeCustfile()} to release the underlying JDBC cursor. {@code null} when no
   * scan is in progress.
   */
  private Stream<Customer> customerStream;

  /**
   * Forward cursor over the customer master in ascending {@code custId} order, materialized by
   * {@link #openCustfile()} and consumed by {@link #custfileGetNext()}. The COBOL {@code
   * WORKING-STORAGE} analogue of the open VSAM file position; {@code null} once {@link
   * #closeCustfile()} has released it.
   */
  private Iterator<Customer> cursor;

  /**
   * End-of-file flag, the COBOL {@code 01 END-OF-FILE PIC X(01)} switch ({@code 'N'} &rarr; {@code
   * false}, {@code 'Y'} &rarr; {@code true}). Set {@code true} by {@link #custfileGetNext()} when
   * the cursor is exhausted (the {@code FILE STATUS '10'} end-of-file branch).
   */
  private boolean endOfFile;

  /**
   * The record most recently read by {@link #custfileGetNext()}, the COBOL {@code CUSTOMER-RECORD}
   * working-storage area. Retained so the {@link #run()} main loop can perform its second {@code
   * DISPLAY CUSTOMER-RECORD} on the same record (see the double-display quirk in the class
   * javadoc).
   */
  private Customer currentCustomer;

  /**
   * Creates the extract service.
   *
   * @param customerRepository the customer-master repository (the migrated {@code CUSTFILE} KSDS);
   *     must not be {@code null}
   */
  public CustomerExtractService(CustomerRepository customerRepository) {
    this.customerRepository = customerRepository;
  }

  // <- CBCUS01C PROCEDURE DIVISION (main, L70-88)
  /**
   * Reads and logs the entire customer master in ascending primary-key order, reproducing the
   * {@code CBCUS01C} {@code PROCEDURE DIVISION} main flow (L70-88).
   *
   * <p>The sequence is, byte-for-byte with the COBOL: log the start banner ({@code DISPLAY 'START
   * OF EXECUTION OF PROGRAM CBCUS01C'}); {@link #openCustfile() open the file}; then {@code PERFORM
   * UNTIL END-OF-FILE = 'Y'} &mdash; for each pass, while not at end-of-file, {@link
   * #custfileGetNext() read the next record}; if the read did not reach end-of-file, emit the
   * second {@code DISPLAY CUSTOMER-RECORD}. Finally {@link #closeCustfile() close the file} and log
   * the end banner. The COBOL {@code GOBACK} returns no {@code RETURN-CODE}, so this method is
   * {@code void}.
   *
   * <p>The inner {@code if (!endOfFile)} guard is retained even though the enclosing {@code while}
   * already tests the same flag: it mirrors the COBOL {@code IF END-OF-FILE = 'N'} nested directly
   * inside the {@code PERFORM UNTIL}, preserving the original control flow exactly (AAP
   * &sect;0.7.1).
   *
   * @throws IoStatusException if the underlying repository reports an unexpected I/O condition
   *     while opening or reading the customer master (the COBOL abend path)
   */
  public void run() {
    // L71: DISPLAY 'START OF EXECUTION OF PROGRAM CBCUS01C'.
    LOG.info("START OF EXECUTION OF PROGRAM CBCUS01C");

    // L72: PERFORM 0000-CUSTFILE-OPEN.
    openCustfile();

    // L74-81: PERFORM UNTIL END-OF-FILE = 'Y' ...
    while (!endOfFile) {
      // L75: IF END-OF-FILE = 'N' (preserved verbatim for control-flow parity).
      if (!endOfFile) {
        // L76: PERFORM 1000-CUSTFILE-GET-NEXT.
        custfileGetNext();
        // L77-79: IF END-OF-FILE = 'N' -> DISPLAY CUSTOMER-RECORD (the SECOND emission).
        if (!endOfFile) {
          LOG.info(renderCustomerRecord(currentCustomer));
        }
      }
    }

    // L83: PERFORM 9000-CUSTFILE-CLOSE.
    closeCustfile();

    // L85: DISPLAY 'END OF EXECUTION OF PROGRAM CBCUS01C'. L87: GOBACK (no RETURN-CODE).
    LOG.info("END OF EXECUTION OF PROGRAM CBCUS01C");
  }

  // <- CBCUS01C 0000-CUSTFILE-OPEN (L118-134)
  /**
   * Opens the customer master for sequential ascending input, reproducing {@code
   * 0000-CUSTFILE-OPEN} (L118-134).
   *
   * <p>The COBOL {@code OPEN INPUT CUSTFILE-FILE} on an indexed file with {@code ACCESS MODE IS
   * SEQUENTIAL} establishes a forward scan in {@code RECORD KEY} order. Here that becomes a
   * bounded-memory streaming cursor via {@code streamAllByOrderByCustIdAsc()} (a forward-only JDBC
   * cursor that fetches rows in small windows rather than materialising the whole table); its
   * {@link Iterator} is the scan position. The end-of-file switch is (re)set to {@code 'N'} ({@code
   * endOfFile = false}) and any previously retained record is cleared, so each {@link #run()}
   * starts from a clean working-state.
   *
   * <p>On the COBOL {@code ELSE MOVE 12 TO APPL-RESULT} branch (any non-{@code '00'} open status)
   * the program performs {@code DISPLAY 'ERROR OPENING CUSTFILE'}, {@code Z-DISPLAY-IO-STATUS} and
   * {@code Z-ABEND-PROGRAM}; that is reproduced by catching the repository {@link
   * DataAccessException} and abending via {@link #raiseAbend(String, DataAccessException)}.
   */
  private void openCustfile() {
    try {
      // L120: OPEN INPUT CUSTFILE-FILE -> establish the ascending FD-CUST-ID scan as a
      // bounded-memory streaming cursor (HINT_FETCH_SIZE / HINT_READ_ONLY), replacing the
      // findAll(Sort) full-table materialization. The stream is consumed inside the tasklet
      // transaction and closed in closeCustfile() to release the JDBC cursor.
      this.customerStream = customerRepository.streamAllByOrderByCustIdAsc();
      this.cursor = customerStream.iterator();
      // L65 / L121-122: END-OF-FILE = 'N'; clean per-run working-storage.
      this.endOfFile = false;
      this.currentCustomer = null;
    } catch (DataAccessException ex) {
      // L129: DISPLAY 'ERROR OPENING CUSTFILE'.
      LOG.error("ERROR OPENING CUSTFILE");
      // L130-132: MOVE CUSTFILE-STATUS TO IO-STATUS; Z-DISPLAY-IO-STATUS; Z-ABEND-PROGRAM.
      throw raiseAbend(OP_OPEN, ex);
    }
  }

  // <- CBCUS01C 1000-CUSTFILE-GET-NEXT (L92-116)
  /**
   * Reads the next customer record, reproducing {@code 1000-CUSTFILE-GET-NEXT} (L92-116).
   *
   * <p>The COBOL {@code READ CUSTFILE-FILE INTO CUSTOMER-RECORD} resolves to advancing the cursor:
   *
   * <ul>
   *   <li><strong>{@code FILE STATUS '00'}</strong> (a record was returned): the record becomes the
   *       {@link #currentCustomer} and is emitted immediately ({@code DISPLAY CUSTOMER-RECORD}
   *       inside the paragraph) &mdash; this is the <em>first</em> of the two per-record emissions.
   *   <li><strong>{@code FILE STATUS '10'}</strong> (cursor exhausted): {@code MOVE 16 TO
   *       APPL-RESULT} &rarr; {@code APPL-EOF} &rarr; {@code MOVE 'Y' TO END-OF-FILE}; here {@code
   *       endOfFile = true}. No record is emitted on the end-of-file pass.
   *   <li><strong>any other status</strong>: surfaced as a repository {@link DataAccessException};
   *       reproduces {@code DISPLAY 'ERROR READING CUSTOMER FILE'} then the abend path.
   * </ul>
   */
  private void custfileGetNext() {
    try {
      // L93: READ CUSTFILE-FILE INTO CUSTOMER-RECORD.
      if (cursor.hasNext()) {
        // L94-96: CUSTFILE-STATUS = '00' -> capture record and DISPLAY CUSTOMER-RECORD (FIRST).
        this.currentCustomer = cursor.next();
        // Detach immediately so the streamed scan does not accumulate the whole table in the
        // persistence context. Customer has no JPA associations, so the detached record stays fully
        // readable for both per-record emissions (here and the main-loop DISPLAY).
        entityManager.detach(this.currentCustomer);
        LOG.info(renderCustomerRecord(currentCustomer));
      } else {
        // L98-99, L107-108: CUSTFILE-STATUS = '10' -> APPL-EOF -> MOVE 'Y' TO END-OF-FILE.
        this.endOfFile = true;
      }
    } catch (DataAccessException ex) {
      // L110: DISPLAY 'ERROR READING CUSTOMER FILE'.
      LOG.error("ERROR READING CUSTOMER FILE");
      // L111-113: MOVE CUSTFILE-STATUS TO IO-STATUS; Z-DISPLAY-IO-STATUS; Z-ABEND-PROGRAM.
      throw raiseAbend(OP_READ, ex);
    }
  }

  // <- CBCUS01C 9000-CUSTFILE-CLOSE (L136-152)
  /**
   * Closes the customer master, reproducing {@code 9000-CUSTFILE-CLOSE} (L136-152).
   *
   * <p>The legacy {@code CLOSE CUSTFILE-FILE} releases the open VSAM handle and checks its status.
   * In the JPA translation this closes the streaming cursor opened by {@link #openCustfile()}
   * (releasing the underlying JDBC cursor); the COBOL {@code ELSE ... Z-ABEND-PROGRAM} close-error
   * branch is unreachable in the JPA model. Releasing the stream and cursor references (and the
   * retained record) is the faithful equivalent of the file close. Closing the stream is null-safe
   * and idempotent.
   */
  private void closeCustfile() {
    // L138: CLOSE CUSTFILE-FILE -> close the streaming JDBC cursor, then release the scan position.
    if (this.customerStream != null) {
      this.customerStream.close();
      this.customerStream = null;
    }
    this.cursor = null;
    this.currentCustomer = null;
  }

  // <- CBCUS01C Z-DISPLAY-IO-STATUS (L161-174) + Z-ABEND-PROGRAM (L154-158)
  /**
   * Builds the abend exception for an unexpected {@code FILE STATUS}, reproducing the {@code
   * Z-DISPLAY-IO-STATUS} and {@code Z-ABEND-PROGRAM} paragraphs (L154-174).
   *
   * <p>The returned {@link IoStatusException} renders the operator status line exactly as {@code
   * Z-DISPLAY-IO-STATUS} ({@code DISPLAY 'FILE STATUS IS: NNNN' IO-STATUS-04}); that line is logged
   * here before the exception is handed back to the caller to throw. The subsequent {@code
   * Z-ABEND-PROGRAM} banner ({@code DISPLAY 'ABENDING PROGRAM'}) is also logged, and throwing the
   * exception &mdash; which carries {@link IoStatusException#BATCH_ABEND_CODE} {@code = 999}
   * &mdash; is the Java equivalent of {@code MOVE 999 TO ABCODE} followed by {@code CALL 'CEE3ABD'}
   * (the run-unit terminates / the Spring Batch step fails). The exception is returned (not thrown)
   * so the caller can use {@code throw raiseAbend(...)}, making the abend visible in the caller's
   * own control flow.
   *
   * @param operation the failing COBOL I/O verb ({@link #OP_OPEN} or {@link #OP_READ})
   * @param cause the underlying repository failure that triggered the abend
   * @return the {@link IoStatusException} for the caller to throw
   */
  private IoStatusException raiseAbend(String operation, DataAccessException cause) {
    IoStatusException abend =
        new IoStatusException(CUSTFILE, operation, UNEXPECTED_FILE_STATUS, cause);
    // Z-DISPLAY-IO-STATUS (L168/L172): DISPLAY 'FILE STATUS IS: NNNN' IO-STATUS-04.
    LOG.error(abend.getDisplayMessage());
    // Z-ABEND-PROGRAM (L155): DISPLAY 'ABENDING PROGRAM' (followed by MOVE 999 / CALL 'CEE3ABD').
    LOG.error("ABENDING PROGRAM");
    return abend;
  }

  /**
   * Renders a {@link Customer} as the COBOL {@code DISPLAY CUSTOMER-RECORD} image &mdash; the whole
   * {@code 01 CUSTOMER-RECORD} group ({@code CVCUS01Y}, RECLN 500) concatenated in copybook field
   * order.
   *
   * <p>COBOL {@code DISPLAY} of a group item prints its subordinate fields back-to-back. The {@code
   * X(n)} text fields are stored blank-padded to their fixed widths in the {@code char(n)} columns
   * (COBOL parity), so concatenating them reproduces the fixed-width text segment. The genuine
   * numeric ids ({@code CUST-ID}, {@code CUST-SSN}, {@code CUST-FICO-CREDIT-SCORE}) are rendered
   * as-is via {@link String#valueOf(Object)} (null-safe), per the migration spec for these
   * extract/print jobs (no zoned zero-padding is reintroduced). The trailing {@code FILLER PIC
   * X(168)} carries no business meaning and is not modeled, so it is omitted.
   *
   * @param customer the record to render; its getters are read in {@code CVCUS01Y} declaration
   *     order
   * @return the concatenated record image (never {@code null})
   */
  private static String renderCustomerRecord(Customer customer) {
    return new StringBuilder()
        .append(String.valueOf(customer.getCustId()))
        .append(customer.getCustFirstName())
        .append(customer.getCustMiddleName())
        .append(customer.getCustLastName())
        .append(customer.getCustAddrLine1())
        .append(customer.getCustAddrLine2())
        .append(customer.getCustAddrLine3())
        .append(customer.getCustAddrStateCd())
        .append(customer.getCustAddrCountryCd())
        .append(customer.getCustAddrZip())
        .append(customer.getCustPhoneNum1())
        .append(customer.getCustPhoneNum2())
        .append(String.valueOf(customer.getCustSsn()))
        .append(customer.getCustGovtIssuedId())
        .append(customer.getCustDobYyyyMmDd())
        .append(customer.getCustEftAccountId())
        .append(customer.getCustPriCardHolderInd())
        .append(String.valueOf(customer.getCustFicoCreditScore()))
        .toString();
  }
}
