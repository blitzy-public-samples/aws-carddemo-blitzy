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

import com.aws.carddemo.domain.Account;
import com.aws.carddemo.exception.IoStatusException;
import com.aws.carddemo.repository.AccountRepository;
import java.util.Iterator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;

/**
 * Batch "read-and-print account master" service, migrated with 100% behavioral parity from the
 * legacy z/OS COBOL program {@code CBACT01C} (source {@code legacy/app/cbl/CBACT01C.cbl}, "Read and
 * print account data file"). This is the <em>extract archetype</em> for the four CardDemo
 * read-and-print master batch programs; its control flow is reproduced paragraph-for-paragraph in
 * the original {@code PERFORM} order (Agent Action Plan &sect;0.1.2 control-flow preservation,
 * &sect;0.6.7 paragraph&rarr;method traceability).
 *
 * <p>The legacy program opens the {@code ACCTFILE} VSAM KSDS ({@code SELECT ACCTFILE-FILE ...
 * ORGANIZATION IS INDEXED, ACCESS MODE IS SEQUENTIAL, RECORD KEY IS FD-ACCT-ID}), reads every
 * record in ascending key order, and {@code DISPLAY}s each one to the job log, then closes the
 * file. Here the VSAM file becomes the {@link AccountRepository} JPA store and the sequential read
 * becomes an iteration over {@code findAll(Sort.by("acctId"))}, so records are visited in ascending
 * {@code acctId} order exactly as the {@code FD-ACCT-ID} record-key sequence dictated (AAP
 * &sect;0.6.2). Every COBOL {@code DISPLAY} is mapped to an SLF4J log statement; the COBOL {@code
 * FILE STATUS}-to-abend handling is mapped to {@link IoStatusException} (AAP &sect;0.6.4,
 * &sect;0.6.6).
 *
 * <p>Per migration convention this service contains <strong>no Spring Batch types</strong>: {@link
 * #run()} is a plain method that the batch tier ({@code AccountExtractJobConfig} in {@code
 * com.aws.carddemo.batch}) later invokes from a tasklet, so an unhandled {@link IoStatusException}
 * naturally becomes a failed Spring Batch step exit status (the abend-equivalent, AAP &sect;0.6.6).
 *
 * <p><strong>COBOL paragraph &rarr; Java method traceability</strong> (AAP &sect;0.6.7):
 *
 * <table border="1">
 *   <caption>CBACT01C paragraph to Java method mapping</caption>
 *   <tr><th>COBOL paragraph</th><th>Java method</th></tr>
 *   <tr><td>{@code PROCEDURE DIVISION} (main)</td><td>{@link #run()}</td></tr>
 *   <tr><td>{@code 0000-ACCTFILE-OPEN}</td><td>{@link #openAcctfile()}</td></tr>
 *   <tr><td>{@code 1000-ACCTFILE-GET-NEXT}</td><td>{@link #acctfileGetNext()}</td></tr>
 *   <tr><td>{@code 1100-DISPLAY-ACCT-RECORD}</td><td>{@link #displayAcctRecord(Account)}</td></tr>
 *   <tr><td>{@code 9000-ACCTFILE-CLOSE}</td><td>{@link #closeAcctfile()}</td></tr>
 *   <tr><td>{@code 9999-ABEND-PROGRAM} + {@code 9910-DISPLAY-IO-STATUS}</td>
 *       <td>{@link #abend(String, String, DataAccessException)} &rarr; throw {@link
 *       IoStatusException}</td></tr>
 * </table>
 *
 * <p><strong>Statefulness / threading.</strong> Mirroring the single-run COBOL program, this
 * service keeps the in-flight scan state ({@link #cursor}, {@link #endOfFile}, {@link
 * #currentAccount}) in instance fields and resets it at the start of every {@link #run()}. A single
 * {@code run()} is therefore self-contained and repeatable, but the method is <em>not</em>
 * re-entrant: it must be driven by one thread per execution, exactly as the batch tasklet drives
 * it. No decimal arithmetic occurs here; the {@link java.math.BigDecimal} balance fields are logged
 * verbatim and are never converted to {@code float}/{@code double} (AAP &sect;0.6.1).
 */
@Service
public class AccountExtractService {

  /** SLF4J logger; every COBOL {@code DISPLAY} in {@code CBACT01C} routes through this logger. */
  private static final Logger LOG = LoggerFactory.getLogger(AccountExtractService.class);

  /**
   * Logical file name reported on the abend path, mirroring the COBOL {@code ASSIGN TO ACCTFILE} dd
   * name shown in the {@code DISPLAY 'ERROR ... ACCTFILE'} lines.
   */
  private static final String ACCTFILE_DDNAME = "ACCTFILE";

  /**
   * Sentinel two-byte {@code FILE STATUS} used when an unexpected {@link DataAccessException}
   * surfaces from the repository. It corresponds to the COBOL {@code 9910-DISPLAY-IO-STATUS}
   * "9-prefixed / abnormal" branch ({@code IF IO-STATUS NOT NUMERIC OR IO-STAT1 = '9'}); {@link
   * IoStatusException#BATCH_ABEND_CODE} (999 / {@code CEE3ABD}) already encodes the run-unit abend.
   */
  private static final String ABEND_FILE_STATUS = "99";

  /** {@code DISPLAY 'START OF EXECUTION OF PROGRAM CBACT01C'} (CBACT01C L71). */
  private static final String START_MESSAGE = "START OF EXECUTION OF PROGRAM CBACT01C";

  /** {@code DISPLAY 'END OF EXECUTION OF PROGRAM CBACT01C'} (CBACT01C L85). */
  private static final String END_MESSAGE = "END OF EXECUTION OF PROGRAM CBACT01C";

  /** {@code DISPLAY 'ERROR OPENING ACCTFILE'} (CBACT01C L144). */
  private static final String OPEN_ERROR_MESSAGE = "ERROR OPENING ACCTFILE";

  /** {@code DISPLAY 'ERROR READING ACCOUNT FILE'} (CBACT01C L110). */
  private static final String READ_ERROR_MESSAGE = "ERROR READING ACCOUNT FILE";

  /** {@code DISPLAY 'ERROR CLOSING ACCOUNT FILE'} (CBACT01C L162). */
  private static final String CLOSE_ERROR_MESSAGE = "ERROR CLOSING ACCOUNT FILE";

  /** {@code DISPLAY 'ABENDING PROGRAM'} from {@code 9999-ABEND-PROGRAM} (CBACT01C L170). */
  private static final String ABEND_MESSAGE = "ABENDING PROGRAM";

  /**
   * The trailing separator emitted by {@code 1100-DISPLAY-ACCT-RECORD} (CBACT01C L130): exactly 49
   * dash characters, preserved byte-for-byte.
   */
  private static final String RECORD_SEPARATOR =
      "-------------------------------------------------";

  /**
   * The {@code ACCTFILE} store ({@code SELECT ACCTFILE-FILE ... RECORD KEY IS FD-ACCT-ID}).
   * Injected by constructor so the service is trivially unit-testable with a mock repository.
   */
  private final AccountRepository accountRepository;

  /**
   * Ascending-key cursor over the account master, established by {@link #openAcctfile()} and
   * released by {@link #closeAcctfile()}. Models the open VSAM file handle; {@code null} when the
   * file is not open.
   */
  private Iterator<Account> cursor;

  /**
   * End-of-file flag modeling the COBOL {@code END-OF-FILE PIC X(01)} ({@code 'N'}/{@code 'Y'})
   * working-storage switch. Set {@code true} when the sequential read reaches exhaustion (FILE
   * STATUS {@code '10'}).
   */
  private boolean endOfFile;

  /**
   * The record most recently read by {@link #acctfileGetNext()}, modeling the COBOL working-storage
   * {@code ACCOUNT-RECORD} (copybook {@code CVACT01Y}) that both the field-by-field display and the
   * whole-record display read.
   */
  private Account currentAccount;

  /**
   * Creates the service with its single collaborator.
   *
   * @param accountRepository the account-master repository replacing the VSAM {@code ACCTFILE}
   *     KSDS; must not be {@code null}
   */
  public AccountExtractService(AccountRepository accountRepository) {
    this.accountRepository = accountRepository;
  }

  /**
   * Reads and prints the entire account master, reproducing the {@code CBACT01C} {@code PROCEDURE
   * DIVISION} main paragraph (L70-87) statement-for-statement.
   *
   * <p>The control flow is: announce start &rarr; {@code PERFORM 0000-ACCTFILE-OPEN} &rarr; {@code
   * PERFORM UNTIL END-OF-FILE = 'Y'} reading one record per iteration and, while not at
   * end-of-file, emitting the whole-record image ({@code DISPLAY ACCOUNT-RECORD}) &rarr; {@code
   * PERFORM 9000-ACCTFILE-CLOSE} &rarr; announce end. Each accepted record is therefore emitted
   * <em>twice</em>, exactly as the legacy program does: first as the labeled field-by-field block
   * from {@code 1100-DISPLAY-ACCT-RECORD} (performed inside {@link #acctfileGetNext()}), then as
   * the single whole-record line emitted here. This double emission is preserved deliberately.
   *
   * <p>The legacy {@code GOBACK} sets no {@code RETURN-CODE}, so a normal completion is a
   * successful run; the method returns {@code void}. An unexpected I/O condition instead propagates
   * an {@link IoStatusException} (the abend-equivalent).
   *
   * @throws IoStatusException if opening, reading, or closing the account store raises an
   *     unexpected {@link DataAccessException}
   */
  public void run() {
    // <- CBACT01C PROCEDURE DIVISION (main)
    this.currentAccount = null;
    LOG.info(START_MESSAGE); // DISPLAY 'START OF EXECUTION OF PROGRAM CBACT01C'
    openAcctfile(); // PERFORM 0000-ACCTFILE-OPEN

    // PERFORM UNTIL END-OF-FILE = 'Y'
    while (!endOfFile) {
      if (!endOfFile) { // IF END-OF-FILE = 'N'
        acctfileGetNext(); // PERFORM 1000-ACCTFILE-GET-NEXT
        if (!endOfFile) { // IF END-OF-FILE = 'N'
          // DISPLAY ACCOUNT-RECORD — the whole 300-byte record image, in addition to the
          // field-by-field 1100 display already emitted inside acctfileGetNext().
          LOG.info("{}", formatAccountRecord(currentAccount));
        }
      }
    }

    closeAcctfile(); // PERFORM 9000-ACCTFILE-CLOSE
    LOG.info(END_MESSAGE); // DISPLAY 'END OF EXECUTION OF PROGRAM CBACT01C'
    // GOBACK — normal return (no RETURN-CODE set => success).
  }

  /**
   * Opens the account store, reproducing {@code 0000-ACCTFILE-OPEN} (CBACT01C L133-149).
   *
   * <p>The VSAM {@code OPEN INPUT} on an {@code INDEXED} file with {@code ACCESS MODE IS
   * SEQUENTIAL} reads in ascending {@code RECORD KEY (FD-ACCT-ID)} order, so the Java equivalent
   * establishes an ascending-{@code acctId} cursor via {@code findAll(Sort.by("acctId"))} (the
   * {@link Sort} is used directly rather than adding a bespoke repository method). The end-of-file
   * switch is (re)armed to {@code 'N'}. A {@link DataAccessException} is the realistic counterpart
   * of the COBOL "{@code FILE STATUS} not {@code '00'}" open failure and triggers the abend path.
   */
  private void openAcctfile() {
    // <- CBACT01C 0000-ACCTFILE-OPEN
    try {
      this.cursor = accountRepository.findAll(Sort.by("acctId")).iterator();
      this.endOfFile = false; // MOVE 'N' is the initial END-OF-FILE state.
    } catch (DataAccessException ex) {
      // ELSE branch (L143-148): DISPLAY 'ERROR OPENING ACCTFILE' + 9910 + 9999-ABEND.
      throw abend("OPEN", OPEN_ERROR_MESSAGE, ex);
    }
  }

  /**
   * Reads the next account record, reproducing {@code 1000-ACCTFILE-GET-NEXT} (CBACT01C L92-116).
   *
   * <p>Mirrors the COBOL {@code READ ACCTFILE-FILE INTO ACCOUNT-RECORD} status branching:
   *
   * <ul>
   *   <li>a record is available (FILE STATUS {@code '00'}) &rarr; it becomes the current record and
   *       {@code 1100-DISPLAY-ACCT-RECORD} is performed via {@link #displayAcctRecord(Account)};
   *   <li>the cursor is exhausted (FILE STATUS {@code '10'}) &rarr; {@link #endOfFile} is set
   *       {@code true}; this is normal loop termination, <em>not</em> an error;
   *   <li>any other condition &mdash; here an unexpected {@link DataAccessException} &mdash; &rarr;
   *       the abend path ({@code DISPLAY 'ERROR READING ACCOUNT FILE'} + {@code 9910} + {@code
   *       9999}).
   * </ul>
   */
  private void acctfileGetNext() {
    // <- CBACT01C 1000-ACCTFILE-GET-NEXT
    try {
      // READ ACCTFILE-FILE INTO ACCOUNT-RECORD.
      if (cursor.hasNext()) {
        // ACCTFILE-STATUS = '00': record read; PERFORM 1100-DISPLAY-ACCT-RECORD.
        this.currentAccount = cursor.next();
        displayAcctRecord(currentAccount);
      } else {
        // ACCTFILE-STATUS = '10' (APPL-EOF): end of file -> MOVE 'Y' TO END-OF-FILE. Not an error.
        this.endOfFile = true;
      }
    } catch (DataAccessException ex) {
      // Any other status (L109-114): DISPLAY 'ERROR READING ACCOUNT FILE' + 9910 + 9999-ABEND.
      throw abend("READ", READ_ERROR_MESSAGE, ex);
    }
  }

  /**
   * Emits the labeled field-by-field display of one account record, reproducing {@code
   * 1100-DISPLAY-ACCT-RECORD} (CBACT01C L118-131) byte-faithfully.
   *
   * <p>Each line is the verbatim COBOL label literal (a 25-character, colon-terminated caption)
   * immediately followed by the field value, exactly as the COBOL {@code DISPLAY 'label' field}
   * concatenates its operands, followed by the 49-dash separator. The field order and captions
   * match the source exactly, including the legacy "{@code EXPIRAION}" misspelling. Note the legacy
   * paragraph deliberately does <em>not</em> display {@code ACCT-ADDR-ZIP}, so it is omitted here
   * (it does, however, appear in the whole-record image &mdash; see {@link
   * #formatAccountRecord(Account)}). The {@link java.math.BigDecimal} balance/limit fields are
   * logged as-is and are never converted to a floating-point type (AAP &sect;0.6.1).
   *
   * @param account the record to display; never {@code null} on the normal {@code '00'} path
   */
  private void displayAcctRecord(Account account) {
    // <- CBACT01C 1100-DISPLAY-ACCT-RECORD
    LOG.info("ACCT-ID                 :{}", account.getAcctId());
    LOG.info("ACCT-ACTIVE-STATUS      :{}", account.getAcctActiveStatus());
    LOG.info("ACCT-CURR-BAL           :{}", account.getAcctCurrBal());
    LOG.info("ACCT-CREDIT-LIMIT       :{}", account.getAcctCreditLimit());
    LOG.info("ACCT-CASH-CREDIT-LIMIT  :{}", account.getAcctCashCreditLimit());
    LOG.info("ACCT-OPEN-DATE          :{}", account.getAcctOpenDate());
    LOG.info("ACCT-EXPIRAION-DATE     :{}", account.getAcctExpiraionDate());
    LOG.info("ACCT-REISSUE-DATE       :{}", account.getAcctReissueDate());
    LOG.info("ACCT-CURR-CYC-CREDIT    :{}", account.getAcctCurrCycCredit());
    LOG.info("ACCT-CURR-CYC-DEBIT     :{}", account.getAcctCurrCycDebit());
    LOG.info("ACCT-GROUP-ID           :{}", account.getAcctGroupId());
    LOG.info(RECORD_SEPARATOR);
  }

  /**
   * Renders the whole-record image emitted by the main-loop {@code DISPLAY ACCOUNT-RECORD}
   * (CBACT01C L78).
   *
   * <p>The COBOL statement displays the contiguous {@code ACCOUNT-RECORD} group (copybook {@code
   * CVACT01Y}) as one line. This method reproduces that image by concatenating every business field
   * in copybook declaration order &mdash; including {@code ACCT-ADDR-ZIP}, which the field-by-field
   * {@code 1100} display omits &mdash; and excluding only the trailing {@code FILLER PIC X(178)},
   * which carries no business data. Character fields read back from the {@code char(n)} columns are
   * already blank-padded to their fixed width (matching COBOL fixed-width semantics, AAP
   * &sect;0.6.2), and the {@link java.math.BigDecimal} values are rendered verbatim. Each field is
   * appended null-safely so a partially populated record can never raise a {@link
   * NullPointerException} during diagnostics.
   *
   * @param account the record to render; never {@code null} on the normal {@code '00'} path
   * @return the concatenated whole-record image, in copybook field order
   */
  private String formatAccountRecord(Account account) {
    // <- CBACT01C main-loop DISPLAY ACCOUNT-RECORD (CVACT01Y field order)
    return new StringBuilder()
        .append(account.getAcctId())
        .append(account.getAcctActiveStatus())
        .append(account.getAcctCurrBal())
        .append(account.getAcctCreditLimit())
        .append(account.getAcctCashCreditLimit())
        .append(account.getAcctOpenDate())
        .append(account.getAcctExpiraionDate())
        .append(account.getAcctReissueDate())
        .append(account.getAcctCurrCycCredit())
        .append(account.getAcctCurrCycDebit())
        .append(account.getAcctAddrZip())
        .append(account.getAcctGroupId())
        .toString();
  }

  /**
   * Closes the account store, reproducing {@code 9000-ACCTFILE-CLOSE} (CBACT01C L151-167).
   *
   * <p>Releasing the in-memory cursor is the Java counterpart of the VSAM {@code CLOSE}. The COBOL
   * paragraph still guards the close with its own {@code FILE STATUS} check and abend branch, so
   * the release is wrapped to translate any unexpected {@link DataAccessException} into the same
   * abend path ({@code DISPLAY 'ERROR CLOSING ACCOUNT FILE'} + {@code 9910} + {@code 9999}),
   * preserving full paragraph parity.
   */
  private void closeAcctfile() {
    // <- CBACT01C 9000-ACCTFILE-CLOSE
    try {
      this.cursor = null; // CLOSE ACCTFILE-FILE: release the file handle.
    } catch (DataAccessException ex) {
      // ELSE branch (L161-166): DISPLAY 'ERROR CLOSING ACCOUNT FILE' + 9910 + 9999-ABEND.
      throw abend("CLOSE", CLOSE_ERROR_MESSAGE, ex);
    }
  }

  /**
   * Builds the abend, reproducing {@code 9999-ABEND-PROGRAM} together with the {@code
   * 9910-DISPLAY-IO-STATUS} operator display (CBACT01C L169-189).
   *
   * <p>The three externally observable {@code DISPLAY}s are emitted in the legacy order: the {@code
   * 'ERROR ...'} line for the failing verb, then the {@code 'FILE STATUS IS: NNNN....'} line (via
   * {@link IoStatusException#getDisplayMessage()}), then {@code 'ABENDING PROGRAM'}. The returned
   * {@link IoStatusException} carries the {@code ACCTFILE} name, the failing operation, the {@link
   * #ABEND_FILE_STATUS} sentinel, and the originating cause; its {@link
   * IoStatusException#BATCH_ABEND_CODE} (999 / {@code CEE3ABD}) is the migrated abend code. Callers
   * {@code throw} the result so control transfers immediately, mirroring {@code CALL 'CEE3ABD'}.
   *
   * @param operation the failing COBOL I/O verb ({@code "OPEN"}, {@code "READ"}, or {@code
   *     "CLOSE"})
   * @param errorDisplay the verb-specific {@code DISPLAY 'ERROR ...'} literal to emit first
   * @param cause the originating {@link DataAccessException}
   * @return the {@link IoStatusException} for the caller to throw
   */
  private IoStatusException abend(
      String operation, String errorDisplay, DataAccessException cause) {
    // <- CBACT01C 9999-ABEND-PROGRAM + 9910-DISPLAY-IO-STATUS
    LOG.error("{}", errorDisplay); // DISPLAY 'ERROR ...'
    IoStatusException ioStatusException =
        new IoStatusException(ACCTFILE_DDNAME, operation, ABEND_FILE_STATUS, cause);
    LOG.error("{}", ioStatusException.getDisplayMessage()); // 9910-DISPLAY-IO-STATUS
    LOG.error(ABEND_MESSAGE); // DISPLAY 'ABENDING PROGRAM'
    return ioStatusException;
  }
}
