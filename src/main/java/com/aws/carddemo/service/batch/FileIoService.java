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
import com.aws.carddemo.domain.CardXref;
import com.aws.carddemo.domain.Customer;
import com.aws.carddemo.domain.Transaction;
import com.aws.carddemo.repository.AccountRepository;
import com.aws.carddemo.repository.CardXrefRepository;
import com.aws.carddemo.repository.CustomerRepository;
import com.aws.carddemo.repository.TransactionRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.util.Iterator;
import java.util.Optional;
import java.util.stream.Stream;
import org.springframework.stereotype.Component;

/**
 * Centralized file-I/O subprogram — the 1:1 Java modernization of the legacy COBOL batch subroutine
 * {@code CBSTM03B.CBL} (legacy source {@code legacy/app/cbl/CBSTM03B.CBL}).
 *
 * <p>In the z/OS CardDemo application, every statement-generation read was funnelled through a
 * single subprogram invoked with {@code CALL 'CBSTM03B' USING WS-M03B-AREA}. {@code CBSTM03B}
 * served four VSAM files — the transaction master ({@code TRNXFILE}), the card cross-reference
 * ({@code XREFFILE}), the customer master ({@code CUSTFILE}) and the account master ({@code
 * ACCTFILE}) — exposing them through a single linkage record ({@code LK-M03B-AREA}) whose {@code
 * LK-M03B-DD} field selected the file and whose {@code LK-M03B-OPER} field selected the operation
 * (open / read / keyed-read / close). The subprogram performed the I/O and returned the file's
 * 2-byte COBOL FILE STATUS in {@code LK-M03B-RC}; it never abended and never raised an error of its
 * own — the decision to abend on a bad status belonged to the caller ({@code CBSTM03A}). See AAP
 * &sect;0.3.3 (the {@code CALL 'CBSTM03B'} &rarr; {@code @Autowired FileIoService} bean-injection
 * pattern), &sect;0.4.1 (StatementGeneration row) and &sect;0.4.2 (subprogram calls &rarr; bean
 * injection).
 *
 * <p>Here the subprogram becomes a Spring {@link Component} whose single public entry point {@link
 * #process(WorkArea)} dispatches on the ddname exactly as the COBOL {@code EVALUATE LK-M03B-DD}
 * did, backed by the four Spring Data JPA repositories that replace the VSAM datasets. The legacy
 * {@code LK-M03B-AREA} linkage record is reproduced as the public static nested {@link WorkArea} so
 * the caller (the sibling {@code StatementGenerationService}) can construct it and pass it in just
 * as COBOL passed {@code WS-M03B-AREA}. Reads return the freshly read domain entity through {@link
 * WorkArea#getRecord()} (the modern equivalent of the {@code READ ... INTO LK-M03B-FLDT} record
 * image), and every call sets the 2-byte status through {@link WorkArea#getReturnCode()}.
 *
 * <p><strong>FILE STATUS contract</strong> (faithful to {@code CBSTM03B}, AAP &sect;0.6.4): this
 * service only <em>returns</em> status codes and never throws for an I/O outcome —
 *
 * <ul>
 *   <li>{@link #STATUS_OK "00"} — successful open, close, or read that returned a record;
 *   <li>{@link #STATUS_EOF "10"} — end-of-file on a sequential read ({@code TRNXFILE} / {@code
 *       XREFFILE});
 *   <li>{@link #STATUS_RECORD_NOT_FOUND "23"} — record-not-found on a keyed read ({@code CUSTFILE}
 *       / {@code ACCTFILE}), mirroring VSAM {@code INVALID KEY}.
 * </ul>
 *
 * <p>It deliberately does <strong>not</strong> import or throw {@code IoStatusException}; abend
 * decisions remain with the caller. Genuinely unexpected infrastructure failures (for example a
 * repository raising {@code DataAccessException}) propagate naturally as unchecked exceptions
 * rather than being caught and swallowed.
 *
 * <p><strong>Sequential cursor state.</strong> {@code CBSTM03B} kept each file open across calls
 * (open once, read many, close once). That is reproduced for the two sequential files by the
 * per-bean cursor fields {@link #trnxCursor} and {@link #xrefCursor}, each backed by a
 * bounded-memory streaming cursor ({@link #trnxStream} / {@link #xrefStream}): an {@code OPEN}
 * opens the stream and takes its iterator, a {@code READ} advances it (detaching each returned
 * record so the persistence context never accumulates the whole table), and a {@code CLOSE} closes
 * the stream to release the underlying JDBC cursor. The sequential reads return records in
 * deterministic ascending key order ({@code TRNXFILE} by {@code tranId}, {@code XREFFILE} by {@code
 * xrefCardNum}) so downstream statement output is reproducible for golden-file parity. The two
 * keyed files ({@code CUSTFILE} / {@code ACCTFILE}) need no cursor — each keyed read is an
 * independent {@code findById}.
 *
 * <p><strong>Thread-safety.</strong> Because this bean holds mutable sequential-cursor state it is
 * <em>not</em> thread-safe and is intended for single-threaded batch statement generation, matching
 * the single-threaded COBOL subprogram. No synchronization is added (that would diverge from the
 * legacy behavior without being required).
 *
 * @implNote COBOL paragraph &rarr; Java method traceability (for {@code
 *     docs/traceability-matrix.md}):
 *     <pre>
 *   COBOL paragraph                                   Java method
 *   ------------------------------------------------  -----------------------
 *   0000-START (EVALUATE LK-M03B-DD dispatcher)        process(WorkArea)
 *   1000-TRNXFILE-PROC / 1900-EXIT / 1999-EXIT         trnxfileProc(WorkArea)
 *   2000-XREFFILE-PROC / 2900-EXIT / 2999-EXIT         xreffileProc(WorkArea)
 *   3000-CUSTFILE-PROC / 3900-EXIT / 3999-EXIT         custfileProc(WorkArea)
 *   4000-ACCTFILE-PROC / 4900-EXIT / 4999-EXIT         acctfileProc(WorkArea)
 *   9999-GOBACK                                        dispatcher WHEN OTHER fall-through (return)
 *     </pre>
 */
@Component
public class FileIoService {

  /** COBOL FILE STATUS {@code '00'} — successful I/O (open, close, or read returning a record). */
  public static final String STATUS_OK = "00";

  /** COBOL FILE STATUS {@code '10'} — end-of-file on a sequential read (not an error). */
  public static final String STATUS_EOF = "10";

  /**
   * COBOL FILE STATUS {@code '23'} — record not found on a keyed read (VSAM {@code INVALID KEY}).
   */
  public static final String STATUS_RECORD_NOT_FOUND = "23";

  /** Transaction master repository — replaces VSAM {@code TRNX-FILE} ({@code TRNXFILE}). */
  private final TransactionRepository transactionRepository;

  /** Card cross-reference repository — replaces VSAM {@code XREF-FILE} ({@code XREFFILE}). */
  private final CardXrefRepository cardXrefRepository;

  /** Customer master repository — replaces VSAM {@code CUST-FILE} ({@code CUSTFILE}). */
  private final CustomerRepository customerRepository;

  /** Account master repository — replaces VSAM {@code ACCT-FILE} ({@code ACCTFILE}). */
  private final AccountRepository accountRepository;

  /**
   * Transaction-scoped entity manager used to {@code detach} each streamed sequential record
   * immediately after a {@code READ} returns it, so the persistence context does not accumulate the
   * whole table across the open-once/read-many/close-once cycle (the bounded-memory complement to
   * the streaming cursors). Injected by the container; bound to the tasklet transaction that
   * brackets the calling batch service's {@code run(...)} (the cursors stay valid for the full
   * cycle because that whole cycle executes inside one tasklet transaction).
   */
  @PersistenceContext private EntityManager entityManager;

  /**
   * Open streaming cursor over the transaction master (ascending {@code tranId}), held so it can be
   * closed on {@code CLOSE} to release the underlying JDBC cursor. {@code null} when the file is
   * closed.
   */
  private Stream<Transaction> trnxStream;

  /**
   * Open streaming cursor over the card cross-reference (ascending {@code xrefCardNum}), held so it
   * can be closed on {@code CLOSE} to release the underlying JDBC cursor. {@code null} when the
   * file is closed.
   */
  private Stream<CardXref> xrefStream;

  /**
   * Sequential cursor over the transaction master, established by a {@code TRNXFILE} {@code OPEN}
   * and advanced by each {@code READ}; {@code null} when the file is closed. Mirrors the
   * open-once/read-many/close-once lifecycle of the legacy {@code TRNX-FILE}.
   */
  private Iterator<Transaction> trnxCursor;

  /**
   * Sequential cursor over the card cross-reference, established by an {@code XREFFILE} {@code
   * OPEN} and advanced by each {@code READ}; {@code null} when the file is closed. Mirrors the
   * open-once/read-many/close-once lifecycle of the legacy {@code XREF-FILE}.
   */
  private Iterator<CardXref> xrefCursor;

  /**
   * Creates the file-I/O service with the four repositories that replace the VSAM files served by
   * {@code CBSTM03B}. Constructor injection keeps every dependency {@code final} (no field
   * injection), matching the convention used across the {@code com.aws.carddemo} codebase.
   *
   * @param transactionRepository transaction master repository ({@code TRNXFILE})
   * @param cardXrefRepository card cross-reference repository ({@code XREFFILE})
   * @param customerRepository customer master repository ({@code CUSTFILE})
   * @param accountRepository account master repository ({@code ACCTFILE})
   */
  public FileIoService(
      TransactionRepository transactionRepository,
      CardXrefRepository cardXrefRepository,
      CustomerRepository customerRepository,
      AccountRepository accountRepository) {
    this.transactionRepository = transactionRepository;
    this.cardXrefRepository = cardXrefRepository;
    this.customerRepository = customerRepository;
    this.accountRepository = accountRepository;
  }

  /**
   * Single entry point reproducing {@code 0000-START} — the COBOL {@code PROCEDURE DIVISION USING
   * LK-M03B-AREA}. Dispatches on {@link WorkArea#getDdname()} exactly as {@code EVALUATE
   * LK-M03B-DD} did: {@code TRNXFILE} &rarr; {@link #trnxfileProc(WorkArea)}, {@code XREFFILE}
   * &rarr; {@link #xreffileProc(WorkArea)}, {@code CUSTFILE} &rarr; {@link
   * #custfileProc(WorkArea)}, {@code ACCTFILE} &rarr; {@link #acctfileProc(WorkArea)}. Any other
   * (including {@code null}) ddname is the {@code WHEN OTHER &rarr; GO TO 9999-GOBACK} case: the
   * method returns without action, without setting a status and without throwing.
   *
   * <p>On return, {@link WorkArea#getReturnCode()} holds the 2-byte FILE STATUS and — for a
   * successful read — {@link WorkArea#getRecord()} holds the freshly read domain entity.
   *
   * @param area the work area selecting the file, operation and (for keyed reads) the key; mutated
   *     in place with the resulting status and record
   */
  public void process(WorkArea area) {
    // <- CBSTM03B 0000-START (EVALUATE LK-M03B-DD)
    String ddname = area.getDdname();
    if (WorkArea.TRNXFILE.equals(ddname)) {
      trnxfileProc(area);
    } else if (WorkArea.XREFFILE.equals(ddname)) {
      xreffileProc(area);
    } else if (WorkArea.CUSTFILE.equals(ddname)) {
      custfileProc(area);
    } else if (WorkArea.ACCTFILE.equals(ddname)) {
      acctfileProc(area);
    }
    // WHEN OTHER -> 9999-GOBACK: no matching ddname, return without action.
  }

  /**
   * Transaction master handler — supports {@code OPEN} / sequential {@code READ} / {@code CLOSE}
   * only, mirroring the three {@code IF} branches of the COBOL paragraph. {@code WRITE} / {@code
   * REWRITE} have no branch in {@code CBSTM03B} and are therefore no-ops here (the status is left
   * untouched), exactly as the legacy subprogram offered no write path for this file.
   *
   * @param area the work area; its {@link WorkArea#getReturnCode() returnCode} and, on a successful
   *     read, its {@link WorkArea#getRecord() record} are set before returning
   */
  private void trnxfileProc(WorkArea area) {
    // <- CBSTM03B 1000-TRNXFILE-PROC
    WorkArea.Operation operation = area.getOperation();
    if (operation == WorkArea.Operation.OPEN) {
      // OPEN INPUT TRNX-FILE: deterministic ascending-tranId iteration (golden-file parity) via a
      // bounded-memory streaming cursor (HINT_FETCH_SIZE / HINT_READ_ONLY), replacing the
      // findAllByOrderByTranIdAsc() full-table materialization. Closed on CLOSE to release the
      // JDBC cursor; valid across all READs because the whole open/read/close cycle runs inside one
      // tasklet transaction.
      trnxStream = transactionRepository.streamAllByOrderByTranIdAsc();
      trnxCursor = trnxStream.iterator();
      area.setReturnCode(STATUS_OK);
    } else if (operation == WorkArea.Operation.READ) {
      // READ TRNX-FILE INTO LK-M03B-FLDT: advance the cursor; "10" at end-of-file.
      if (trnxCursor != null && trnxCursor.hasNext()) {
        Transaction record = trnxCursor.next();
        // Detach immediately so the streamed scan does not accumulate the whole table in the
        // persistence context. Transaction has no JPA associations, so the detached record stays
        // fully readable for the caller (the WS-TRNX-TABLE build holds detached read-only rows).
        entityManager.detach(record);
        area.setRecord(record);
        area.setReturnCode(STATUS_OK);
      } else {
        area.setRecord(null);
        area.setReturnCode(STATUS_EOF);
      }
    } else if (operation == WorkArea.Operation.CLOSE) {
      // CLOSE TRNX-FILE: close the streaming JDBC cursor, then discard the cursor (null-safe).
      if (trnxStream != null) {
        trnxStream.close();
        trnxStream = null;
      }
      trnxCursor = null;
      area.setReturnCode(STATUS_OK);
    }
    // WRITE / REWRITE: no branch in CBSTM03B for this file -> no-op.
  }

  /**
   * Card cross-reference handler — supports {@code OPEN} / sequential {@code READ} / {@code CLOSE}
   * only, mirroring the three {@code IF} branches of the COBOL paragraph. Deterministic
   * ascending-key order over the primary key {@code xrefCardNum} is obtained from the
   * bounded-memory streaming finder {@code streamAllByOrderByXrefCardNumAsc()}. {@code WRITE} /
   * {@code REWRITE} are no-ops (no branch in {@code CBSTM03B}).
   *
   * @param area the work area; its {@link WorkArea#getReturnCode() returnCode} and, on a successful
   *     read, its {@link WorkArea#getRecord() record} are set before returning
   */
  private void xreffileProc(WorkArea area) {
    // <- CBSTM03B 2000-XREFFILE-PROC
    WorkArea.Operation operation = area.getOperation();
    if (operation == WorkArea.Operation.OPEN) {
      // OPEN INPUT XREF-FILE: deterministic ascending-xrefCardNum iteration via a bounded-memory
      // streaming cursor (HINT_FETCH_SIZE / HINT_READ_ONLY), replacing the findAll(Sort)
      // full-table materialization. Closed on CLOSE; valid across all READs (single tasklet tx).
      xrefStream = cardXrefRepository.streamAllByOrderByXrefCardNumAsc();
      xrefCursor = xrefStream.iterator();
      area.setReturnCode(STATUS_OK);
    } else if (operation == WorkArea.Operation.READ) {
      // READ XREF-FILE INTO LK-M03B-FLDT: advance the cursor; "10" at end-of-file.
      if (xrefCursor != null && xrefCursor.hasNext()) {
        CardXref record = xrefCursor.next();
        // Detach immediately so the streamed scan does not accumulate the whole table in the
        // persistence context. CardXref has no JPA associations, so the detached record stays fully
        // readable for the caller.
        entityManager.detach(record);
        area.setRecord(record);
        area.setReturnCode(STATUS_OK);
      } else {
        area.setRecord(null);
        area.setReturnCode(STATUS_EOF);
      }
    } else if (operation == WorkArea.Operation.CLOSE) {
      // CLOSE XREF-FILE: close the streaming JDBC cursor, then discard the cursor (null-safe).
      if (xrefStream != null) {
        xrefStream.close();
        xrefStream = null;
      }
      xrefCursor = null;
      area.setReturnCode(STATUS_OK);
    }
    // WRITE / REWRITE: no branch in CBSTM03B for this file -> no-op.
  }

  /**
   * Customer master handler — supports {@code OPEN} / keyed {@code READ_KEY} / {@code CLOSE} only,
   * mirroring the three {@code IF} branches of the COBOL paragraph. The keyed read takes {@code
   * key.substring(0, keyLength)} (the COBOL {@code LK-M03B-KEY (1:LK-M03B-KEY-LN)} reference move
   * into {@code FD-CUST-ID}), parses the zoned-numeric 9-digit id to the {@link Customer} primary
   * key type ({@link Long}) and looks it up; a missing row yields {@link #STATUS_RECORD_NOT_FOUND
   * "23"} (never an exception).
   *
   * @param area the work area; its {@link WorkArea#getReturnCode() returnCode} and, on a successful
   *     read, its {@link WorkArea#getRecord() record} are set before returning
   */
  private void custfileProc(WorkArea area) {
    // <- CBSTM03B 3000-CUSTFILE-PROC
    WorkArea.Operation operation = area.getOperation();
    if (operation == WorkArea.Operation.OPEN) {
      // OPEN INPUT CUST-FILE: random (keyed) access needs no cursor.
      area.setReturnCode(STATUS_OK);
    } else if (operation == WorkArea.Operation.READ_KEY) {
      // MOVE LK-M03B-KEY (1:LK-M03B-KEY-LN) TO FD-CUST-ID; READ CUST-FILE.
      Long custId = parseKey(area);
      Optional<Customer> found = customerRepository.findById(custId);
      if (found.isPresent()) {
        area.setRecord(found.get());
        area.setReturnCode(STATUS_OK);
      } else {
        area.setRecord(null);
        area.setReturnCode(STATUS_RECORD_NOT_FOUND);
      }
    } else if (operation == WorkArea.Operation.CLOSE) {
      // CLOSE CUST-FILE.
      area.setReturnCode(STATUS_OK);
    }
    // WRITE / REWRITE: no branch in CBSTM03B for this file -> no-op.
  }

  /**
   * Account master handler — supports {@code OPEN} / keyed {@code READ_KEY} / {@code CLOSE} only,
   * mirroring the three {@code IF} branches of the COBOL paragraph. The keyed read takes {@code
   * key.substring(0, keyLength)} (the COBOL {@code LK-M03B-KEY (1:LK-M03B-KEY-LN)} reference move
   * into {@code FD-ACCT-ID}), parses the zoned-numeric 11-digit id to the {@link Account} primary
   * key type ({@link Long}) and looks it up; a missing row yields {@link #STATUS_RECORD_NOT_FOUND
   * "23"} (never an exception).
   *
   * @param area the work area; its {@link WorkArea#getReturnCode() returnCode} and, on a successful
   *     read, its {@link WorkArea#getRecord() record} are set before returning
   */
  private void acctfileProc(WorkArea area) {
    // <- CBSTM03B 4000-ACCTFILE-PROC
    WorkArea.Operation operation = area.getOperation();
    if (operation == WorkArea.Operation.OPEN) {
      // OPEN INPUT ACCT-FILE: random (keyed) access needs no cursor.
      area.setReturnCode(STATUS_OK);
    } else if (operation == WorkArea.Operation.READ_KEY) {
      // MOVE LK-M03B-KEY (1:LK-M03B-KEY-LN) TO FD-ACCT-ID; READ ACCT-FILE.
      Long acctId = parseKey(area);
      Optional<Account> found = accountRepository.findById(acctId);
      if (found.isPresent()) {
        area.setRecord(found.get());
        area.setReturnCode(STATUS_OK);
      } else {
        area.setRecord(null);
        area.setReturnCode(STATUS_RECORD_NOT_FOUND);
      }
    } else if (operation == WorkArea.Operation.CLOSE) {
      // CLOSE ACCT-FILE.
      area.setReturnCode(STATUS_OK);
    }
    // WRITE / REWRITE: no branch in CBSTM03B for this file -> no-op.
  }

  /**
   * Extracts and parses the keyed-read lookup key, reproducing the COBOL reference modification
   * {@code LK-M03B-KEY (1:LK-M03B-KEY-LN)}: it takes the leading {@code keyLength} characters of
   * the work-area key and parses the (blank-trimmed) zoned-numeric digits into the {@link Long}
   * primary key shared by {@link Customer} and {@link Account}. Leading zeros are preserved by
   * COBOL when the numeric id is moved into the key field and are accepted by {@link
   * Long#parseLong(String)}.
   *
   * <p>A malformed (non-numeric) key is a caller contract violation rather than an I/O outcome, so
   * — per the {@code CBSTM03B} FILE STATUS contract — the resulting {@link NumberFormatException}
   * is allowed to propagate unchecked rather than being mapped to a status code.
   *
   * @param area the work area carrying {@link WorkArea#getKey() key} and {@link
   *     WorkArea#getKeyLength() keyLength}
   * @return the parsed numeric primary key
   */
  private Long parseKey(WorkArea area) {
    String keyDigits = area.getKey().substring(0, area.getKeyLength());
    return Long.parseLong(keyDigits.trim());
  }

  /**
   * Modern equivalent of the COBOL {@code LK-M03B-AREA} linkage record passed on every {@code CALL
   * 'CBSTM03B' USING WS-M03B-AREA}. The caller constructs a {@code WorkArea}, sets the file ({@link
   * #ddname}), the operation ({@link #operation}) and — for keyed reads — the {@link #key} and
   * {@link #keyLength}, then calls {@link FileIoService#process(WorkArea)}. The service writes back
   * the 2-byte FILE STATUS ({@link #returnCode}) and, for a successful read, the freshly read
   * domain entity ({@link #record}).
   *
   * <p>The instance is reusable across the open/read/close lifecycle exactly as COBOL reused {@code
   * WS-M03B-AREA}: change {@link #setOperation(Operation)} between calls and re-invoke {@code
   * process}.
   */
  public static class WorkArea {

    /** ddname selecting the transaction master (legacy {@code TRNX-FILE} / {@code TRNXFILE}). */
    public static final String TRNXFILE = "TRNXFILE";

    /** ddname selecting the card cross-reference (legacy {@code XREF-FILE} / {@code XREFFILE}). */
    public static final String XREFFILE = "XREFFILE";

    /** ddname selecting the customer master (legacy {@code CUST-FILE} / {@code CUSTFILE}). */
    public static final String CUSTFILE = "CUSTFILE";

    /** ddname selecting the account master (legacy {@code ACCT-FILE} / {@code ACCTFILE}). */
    public static final String ACCTFILE = "ACCTFILE";

    /**
     * The operation selector, reproducing the COBOL {@code LK-M03B-OPER PIC X(01)} 88-levels. Each
     * constant carries the original one-character COBOL code for traceability.
     *
     * <p>{@code CBSTM03B} only <em>implements</em> {@link #OPEN} / {@link #READ} / {@link #CLOSE}
     * for the sequential files and {@link #OPEN} / {@link #READ_KEY} / {@link #CLOSE} for the keyed
     * files. {@link #WRITE} and {@link #REWRITE} existed as 88-levels but had no handler branch, so
     * they are preserved here for fidelity yet remain no-ops in every handler.
     */
    public enum Operation {
      /** {@code M03B-OPEN VALUE 'O'} — open the file. */
      OPEN('O'),
      /** {@code M03B-CLOSE VALUE 'C'} — close the file. */
      CLOSE('C'),
      /** {@code M03B-READ VALUE 'R'} — sequential read (next record). */
      READ('R'),
      /** {@code M03B-READ-K VALUE 'K'} — keyed (random) read by key. */
      READ_KEY('K'),
      /** {@code M03B-WRITE VALUE 'W'} — write; no handler branch in CBSTM03B (no-op). */
      WRITE('W'),
      /** {@code M03B-REWRITE VALUE 'Z'} — rewrite; no handler branch in CBSTM03B (no-op). */
      REWRITE('Z');

      private final char code;

      Operation(char code) {
        this.code = code;
      }

      /**
       * Returns the original one-character COBOL operation code from the {@code LK-M03B-OPER}
       * 88-levels (for example {@code 'O'} for {@link #OPEN}).
       *
       * @return the COBOL operation character
       */
      public char code() {
        return code;
      }
    }

    /** {@code LK-M03B-DD PIC X(08)} — the 8-character file selector (a ddname constant). */
    private String ddname;

    /** {@code LK-M03B-OPER PIC X(01)} — the requested operation. */
    private Operation operation;

    /** {@code LK-M03B-RC PIC X(02)} — the 2-byte FILE STATUS returned by {@code process}. */
    private String returnCode;

    /** {@code LK-M03B-KEY PIC X(25)} — the lookup key for keyed reads. */
    private String key;

    /** {@code LK-M03B-KEY-LN PIC S9(4) COMP} — the significant length of {@link #key}. */
    private int keyLength;

    /**
     * Modern equivalent of the 1000-byte {@code LK-M03B-FLDT PIC X(1000)} record image populated by
     * {@code READ ... INTO LK-M03B-FLDT}. After a successful {@code READ} / {@code READ_KEY} this
     * holds the freshly read domain entity ({@link Transaction}, {@link CardXref}, {@link Customer}
     * or {@link Account}); it is {@code null} when a read returns no record.
     */
    private Object record;

    /**
     * Creates a work area for a non-keyed operation ({@code OPEN}, sequential {@code READ} or
     * {@code CLOSE}).
     *
     * @param ddname the file selector (one of {@link #TRNXFILE}, {@link #XREFFILE}, {@link
     *     #CUSTFILE}, {@link #ACCTFILE})
     * @param operation the operation to perform
     */
    public WorkArea(String ddname, Operation operation) {
      this(ddname, operation, null, 0);
    }

    /**
     * Creates a work area for a keyed read ({@code READ_KEY}), or any operation, supplying the
     * lookup key and its significant length.
     *
     * @param ddname the file selector (one of {@link #TRNXFILE}, {@link #XREFFILE}, {@link
     *     #CUSTFILE}, {@link #ACCTFILE})
     * @param operation the operation to perform
     * @param key the lookup key ({@code LK-M03B-KEY})
     * @param keyLength the significant key length ({@code LK-M03B-KEY-LN})
     */
    public WorkArea(String ddname, Operation operation, String key, int keyLength) {
      this.ddname = ddname;
      this.operation = operation;
      this.key = key;
      this.keyLength = keyLength;
    }

    /**
     * Returns the file selector ddname.
     *
     * @return the ddname ({@code LK-M03B-DD})
     */
    public String getDdname() {
      return ddname;
    }

    /**
     * Sets the file selector ddname.
     *
     * @param ddname the ddname ({@code LK-M03B-DD})
     * @return this work area, for fluent chaining
     */
    public WorkArea setDdname(String ddname) {
      this.ddname = ddname;
      return this;
    }

    /**
     * Returns the requested operation.
     *
     * @return the operation ({@code LK-M03B-OPER})
     */
    public Operation getOperation() {
      return operation;
    }

    /**
     * Sets the requested operation. Changing the operation between calls and re-invoking {@link
     * FileIoService#process(WorkArea)} reproduces the COBOL reuse of {@code WS-M03B-AREA} across
     * the open/read/close lifecycle.
     *
     * @param operation the operation ({@code LK-M03B-OPER})
     * @return this work area, for fluent chaining
     */
    public WorkArea setOperation(Operation operation) {
      this.operation = operation;
      return this;
    }

    /**
     * Returns the 2-byte FILE STATUS set by the most recent {@link FileIoService#process(WorkArea)}
     * call.
     *
     * @return the return code ({@code LK-M03B-RC}); one of {@link FileIoService#STATUS_OK}, {@link
     *     FileIoService#STATUS_EOF} or {@link FileIoService#STATUS_RECORD_NOT_FOUND}, or {@code
     *     null} if no status has been set yet
     */
    public String getReturnCode() {
      return returnCode;
    }

    /**
     * Sets the 2-byte FILE STATUS. Invoked by {@link FileIoService} to report the outcome of the
     * operation.
     *
     * @param returnCode the return code ({@code LK-M03B-RC})
     * @return this work area, for fluent chaining
     */
    public WorkArea setReturnCode(String returnCode) {
      this.returnCode = returnCode;
      return this;
    }

    /**
     * Returns the lookup key for keyed reads.
     *
     * @return the key ({@code LK-M03B-KEY})
     */
    public String getKey() {
      return key;
    }

    /**
     * Sets the lookup key for keyed reads.
     *
     * @param key the key ({@code LK-M03B-KEY})
     * @return this work area, for fluent chaining
     */
    public WorkArea setKey(String key) {
      this.key = key;
      return this;
    }

    /**
     * Returns the significant key length used to slice {@link #getKey()} on a keyed read.
     *
     * @return the key length ({@code LK-M03B-KEY-LN})
     */
    public int getKeyLength() {
      return keyLength;
    }

    /**
     * Sets the significant key length used to slice {@link #getKey()} on a keyed read.
     *
     * @param keyLength the key length ({@code LK-M03B-KEY-LN})
     * @return this work area, for fluent chaining
     */
    public WorkArea setKeyLength(int keyLength) {
      this.keyLength = keyLength;
      return this;
    }

    /**
     * Returns the record image populated by the most recent successful read — the modern equivalent
     * of {@code LK-M03B-FLDT}.
     *
     * @return the freshly read domain entity, or {@code null} if the last read returned no record
     */
    public Object getRecord() {
      return record;
    }

    /**
     * Sets the record image. Invoked by {@link FileIoService} after a successful read (and reset to
     * {@code null} when a read returns no record).
     *
     * @param record the domain entity just read, or {@code null}
     * @return this work area, for fluent chaining
     */
    public WorkArea setRecord(Object record) {
      this.record = record;
      return this;
    }

    /**
     * Type-safe convenience accessor for {@link #getRecord()}: returns the record image cast to the
     * requested domain type. Using {@link Class#cast(Object)} keeps the cast checked, so callers
     * avoid an unchecked-cast warning. Returns {@code null} when the last read returned no record.
     *
     * @param type the expected domain type (for example {@code Customer.class})
     * @param <T> the expected domain type
     * @return the record cast to {@code T}, or {@code null} if no record is present
     */
    public <T> T getRecordAs(Class<T> type) {
      return type.cast(record);
    }
  }
}
