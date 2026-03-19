/*
 * TransactionUtilService.java — Spring @Service (← CBTRN01C.cbl)
 *
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * Licensed under the Apache License, Version 2.0 (the "License").
 *
 * Source COBOL: app/cbl/CBTRN01C.cbl
 * Source Copybooks: CVTRA06Y.cpy, CVCUS01Y.cpy, CVACT03Y.cpy, CVACT02Y.cpy,
 *                   CVACT01Y.cpy, CVTRA05Y.cpy
 *
 * COBOL Paragraph → Java Method Mapping (100% traceability):
 *   MAIN-PARA               → processTransactions()
 *   0000-DALYTRAN-OPEN      → JPA manages (DailyTransactionRepository injection)
 *   0100-CUSTFILE-OPEN      → JPA manages (CustomerRepository injection)
 *   0200-XREFFILE-OPEN      → JPA manages (CardXrefRepository injection)
 *   0300-CARDFILE-OPEN      → JPA manages (CardRepository injection)
 *   0400-ACCTFILE-OPEN      → JPA manages (AccountRepository injection)
 *   0500-TRANFILE-OPEN      → JPA manages (TransactionRepository injection)
 *   1000-DALYTRAN-GET-NEXT  → logDailyTransaction(DailyTransaction) / inline iterator
 *   2000-LOOKUP-XREF        → lookupXref(String cardNumber)
 *   3000-READ-ACCOUNT       → readAccount(String accountId)
 *   9000-DALYTRAN-CLOSE     → JPA manages (automatic connection lifecycle)
 *   9100-CUSTFILE-CLOSE     → JPA manages
 *   9200-XREFFILE-CLOSE     → JPA manages
 *   9300-CARDFILE-CLOSE     → JPA manages
 *   9400-ACCTFILE-CLOSE     → JPA manages
 *   9500-TRANFILE-CLOSE     → JPA manages
 *   Z-DISPLAY-IO-STATUS     → logIoStatus(String context, String status)
 *   Z-ABEND-PROGRAM         → throw CardDemoException (ABCODE=999)
 *
 * COBOL Working Storage Equivalents (documented, not used as fields):
 *   APPL-RESULT: APPL-AOK(0), APPL-EOF(16), error(12) → Optional + exceptions
 *   END-OF-DAILY-TRANS-FILE PIC X(01) → implicit in for-each loop exhaustion
 *   WS-XREF-READ-STATUS → implicit in Optional.isEmpty()
 *   WS-ACCT-READ-STATUS → implicit in Optional.isEmpty()
 *
 * Ver: CardDemo_v1.0 — Migrated from COBOL to Java 25 + Spring Boot 3.5.x
 */
package com.cardemo.service.batch;

import com.cardemo.common.exception.CardDemoException;
import com.cardemo.common.exception.FileStatusException;
import com.cardemo.common.exception.RecordNotFoundException;
import com.cardemo.entity.Account;
import com.cardemo.entity.Card;
import com.cardemo.entity.CardXref;
import com.cardemo.entity.Customer;
import com.cardemo.entity.DailyTransaction;
import com.cardemo.entity.Transaction;
import com.cardemo.repository.AccountRepository;
import com.cardemo.repository.CardRepository;
import com.cardemo.repository.CardXrefRepository;
import com.cardemo.repository.CustomerRepository;
import com.cardemo.repository.DailyTransactionRepository;
import com.cardemo.repository.TransactionRepository;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;

/**
 * Transaction file management utility service — translates COBOL CBTRN01C.cbl.
 *
 * <p>This is an operational batch driver that opens and manages 6 datasets
 * (DALYTRAN, CUSTOMER, XREF, CARD, ACCOUNT, TRANSACT), iterates daily
 * transactions sequentially, validates card numbers through cross-reference
 * keyed lookup, retrieves account records by keyed reads, and performs orderly
 * resource cleanup. It provides the foundation for
 * {@code TransactionLoadJobConfig} seed data loading.</p>
 *
 * <h3>COBOL Program: CBTRN01C.cbl</h3>
 * <p>CBTRN01C is <strong>not</strong> a simple sequential dump like
 * CBACT01C/02C/03C/CBCUS01C. It opens 6 datasets, iterates daily transactions,
 * performs XREF keyed lookup validation, keyed account retrieval, and centralizes
 * file I/O patterns and error formatting across paragraphs.</p>
 *
 * <h3>6-File OPEN Pattern (Paragraphs 0000–0500)</h3>
 * <p>In COBOL, six files are explicitly opened for input. In Java, JPA manages
 * all dataset connections automatically via Spring dependency injection:</p>
 * <ol>
 *   <li>DALYTRAN-FILE (sequential daily transactions)
 *       → {@link DailyTransactionRepository}</li>
 *   <li>CUSTOMER-FILE (KSDS customer data)
 *       → {@link CustomerRepository}</li>
 *   <li>XREF-FILE (KSDS card cross-reference)
 *       → {@link CardXrefRepository}</li>
 *   <li>CARD-FILE (KSDS card data)
 *       → {@link CardRepository}</li>
 *   <li>ACCOUNT-FILE (KSDS account data)
 *       → {@link AccountRepository}</li>
 *   <li>TRANSACT-FILE (KSDS transaction data)
 *       → {@link TransactionRepository}</li>
 * </ol>
 *
 * <p>All 6 repositories are injected for structural fidelity with the COBOL
 * source, even though the main processing loop only actively queries DALYTRAN,
 * XREF, and ACCOUNT datasets.</p>
 *
 * @see com.cardemo.entity.DailyTransaction
 * @see com.cardemo.entity.CardXref
 * @see com.cardemo.entity.Account
 */
@Service
public class TransactionUtilService {

    private static final Logger log = LoggerFactory.getLogger(TransactionUtilService.class);

    /**
     * VSAM file status code for record-not-found (STATUS '23').
     * Used in {@link #logIoStatus(String, String)} to map to
     * {@link RecordNotFoundException}.
     */
    private static final String FILE_STATUS_NOT_FOUND = "23";

    // =========================================================================
    // Injected Repositories — 6-file OPEN pattern (paragraphs 0000–0500)
    // =========================================================================

    /**
     * DALYTRAN-FILE → Sequential daily transaction staging table.
     * Maps to paragraph 0000-DALYTRAN-OPEN.
     * Used in {@link #processTransactions()} via {@code findAll()}.
     */
    private final DailyTransactionRepository dailyTransactionRepository;

    /**
     * CUSTOMER-FILE → KSDS customer data (500-byte records, CVCUS01Y.cpy).
     * Maps to paragraph 0100-CUSTFILE-OPEN.
     * Injected for structural parity with CBTRN01C.cbl which opens all 6 files.
     *
     * @see Customer
     */
    private final CustomerRepository customerRepository;

    /**
     * XREF-FILE → KSDS card cross-reference junction (50-byte records, CVACT03Y.cpy).
     * Maps to paragraph 0200-XREFFILE-OPEN.
     * Used in {@link #lookupXref(String)} via {@code findById()}.
     */
    private final CardXrefRepository cardXrefRepository;

    /**
     * CARD-FILE → KSDS card data (150-byte records, CVACT02Y.cpy).
     * Maps to paragraph 0300-CARDFILE-OPEN.
     * Injected for structural parity with CBTRN01C.cbl which opens all 6 files.
     *
     * @see Card
     */
    private final CardRepository cardRepository;

    /**
     * ACCOUNT-FILE → KSDS account data (300-byte records, CVACT01Y.cpy).
     * Maps to paragraph 0400-ACCTFILE-OPEN.
     * Used in {@link #readAccount(String)} via {@code findById()}.
     */
    private final AccountRepository accountRepository;

    /**
     * TRANSACT-FILE → KSDS transaction data (350-byte records, CVTRA05Y.cpy).
     * Maps to paragraph 0500-TRANFILE-OPEN.
     * Injected for structural parity with CBTRN01C.cbl which opens all 6 files.
     *
     * @see Transaction
     */
    private final TransactionRepository transactionRepository;

    // =========================================================================
    // Constructor — maps to 6-file OPEN pattern
    // =========================================================================

    /**
     * Constructs the TransactionUtilService with all 6 repositories injected.
     *
     * <p>Maps to the 6-file OPEN pattern in CBTRN01C.cbl (paragraphs 0000–0500).
     * In COBOL, each file must be explicitly opened before use. In Spring/JPA,
     * repository injection replaces explicit file opening — the database connection
     * is managed by the Spring container.</p>
     *
     * @param dailyTransactionRepository DALYTRAN-FILE (0000-DALYTRAN-OPEN)
     * @param customerRepository         CUSTOMER-FILE (0100-CUSTFILE-OPEN)
     * @param cardXrefRepository         XREF-FILE (0200-XREFFILE-OPEN)
     * @param cardRepository             CARD-FILE (0300-CARDFILE-OPEN)
     * @param accountRepository          ACCOUNT-FILE (0400-ACCTFILE-OPEN)
     * @param transactionRepository      TRANSACT-FILE (0500-TRANFILE-OPEN)
     */
    @Autowired
    public TransactionUtilService(
            DailyTransactionRepository dailyTransactionRepository,
            CustomerRepository customerRepository,
            CardXrefRepository cardXrefRepository,
            CardRepository cardRepository,
            AccountRepository accountRepository,
            TransactionRepository transactionRepository) {
        this.dailyTransactionRepository = dailyTransactionRepository;
        this.customerRepository = customerRepository;
        this.cardXrefRepository = cardXrefRepository;
        this.cardRepository = cardRepository;
        this.accountRepository = accountRepository;
        this.transactionRepository = transactionRepository;
    }

    // =========================================================================
    // MAIN-PARA → processTransactions()
    // =========================================================================

    /**
     * Main batch processing entry point — translates MAIN-PARA from CBTRN01C.cbl.
     *
     * <p>COBOL execution flow:</p>
     * <ol>
     *   <li>DISPLAY 'START OF EXECUTION OF PROGRAM CBTRN01C'</li>
     *   <li>Open all 6 files (0000 through 0500) — JPA manages automatically</li>
     *   <li>PERFORM UNTIL END-OF-DAILY-TRANS-FILE = 'Y':
     *     <ul>
     *       <li>PERFORM 1000-DALYTRAN-GET-NEXT (read next daily transaction)</li>
     *       <li>If not EOF: DISPLAY DALYTRAN-RECORD</li>
     *       <li>PERFORM 2000-LOOKUP-XREF (by DALYTRAN-CARD-NUM)</li>
     *       <li>If XREF found: PERFORM 3000-READ-ACCOUNT (by XREF-ACCT-ID)</li>
     *       <li>If XREF not found: DISPLAY skip message with card number
     *           and transaction ID</li>
     *     </ul>
     *   </li>
     *   <li>Close all 6 files (9000 through 9500) — JPA manages automatically</li>
     *   <li>DISPLAY 'END OF EXECUTION OF PROGRAM CBTRN01C'</li>
     *   <li>GOBACK</li>
     * </ol>
     *
     * @throws CardDemoException if an unrecoverable I/O error occurs during
     *                           data access (equivalent to Z-ABEND-PROGRAM)
     */
    public void processTransactions() {
        log.info("START OF EXECUTION OF PROGRAM CBTRN01C");

        // Paragraphs 0000-0500: JPA manages dataset connections automatically.
        // All 6 repositories are available via Spring dependency injection.

        // 1000-DALYTRAN-GET-NEXT: Read all daily transactions (sequential access).
        // In COBOL, records are read one at a time via PERFORM UNTIL EOF loop.
        // In Java/JPA, findAll() returns the complete set for iteration.
        List<DailyTransaction> dailyTransactions;
        try {
            dailyTransactions = dailyTransactionRepository.findAll();
        } catch (RuntimeException ex) {
            // Error reading DALYTRAN file — maps to APPL-ERROR(12) path in
            // 1000-DALYTRAN-GET-NEXT:
            //   DISPLAY 'ERROR READING DAILY TRANSACTION FILE'
            //   MOVE DALYTRAN-STATUS TO IO-STATUS
            //   PERFORM Z-DISPLAY-IO-STATUS
            //   PERFORM Z-ABEND-PROGRAM
            log.error("ERROR READING DAILY TRANSACTION FILE");
            logIoStatus("DALYTRAN-FILE", "99");
            // logIoStatus always throws CardDemoException (Z-ABEND-PROGRAM).
            // This fallback throw satisfies the compiler's reachability analysis.
            throw new CardDemoException("ERROR READING DAILY TRANSACTION FILE");
        }

        // PERFORM UNTIL END-OF-DAILY-TRANS-FILE = 'Y'
        // In Java, the for-each loop naturally handles end-of-file:
        // when all records are processed, the loop exits (APPL-EOF equivalent).
        for (DailyTransaction dt : dailyTransactions) {

            // 1000-DALYTRAN-GET-NEXT display output:
            //   IF END-OF-DAILY-TRANS-FILE = 'N'
            //       DISPLAY DALYTRAN-RECORD
            logDailyTransaction(dt);

            // MOVE 0 TO WS-XREF-READ-STATUS
            // MOVE DALYTRAN-CARD-NUM TO XREF-CARD-NUM
            // PERFORM 2000-LOOKUP-XREF
            Optional<CardXref> xref = lookupXref(dt.getCardNum());

            if (xref.isPresent()) {
                // WS-XREF-READ-STATUS = 0 (XREF found successfully)
                // MOVE 0 TO WS-ACCT-READ-STATUS
                // MOVE XREF-ACCT-ID TO ACCT-ID
                // PERFORM 3000-READ-ACCOUNT
                Optional<Account> account = readAccount(xref.get().getAccountId());

                // IF WS-ACCT-READ-STATUS NOT = 0
                //     DISPLAY 'ACCOUNT ' ACCT-ID ' NOT FOUND'
                if (account.isEmpty()) {
                    log.warn("ACCOUNT {} NOT FOUND", xref.get().getAccountId());
                }
            } else {
                // WS-XREF-READ-STATUS != 0 (XREF INVALID KEY — card not verified)
                // DISPLAY 'CARD NUMBER ' DALYTRAN-CARD-NUM
                //   ' COULD NOT BE VERIFIED. SKIPPING TRANSACTION ID-'
                //   DALYTRAN-ID
                log.warn(
                        "CARD NUMBER {} COULD NOT BE VERIFIED."
                                + " SKIPPING TRANSACTION ID-{}",
                        dt.getCardNum(), dt.getDalytranId());
            }
        }

        // Paragraphs 9000-9500: JPA manages dataset cleanup automatically.
        // CLOSE DALYTRAN-FILE, CUSTOMER-FILE, XREF-FILE,
        //       CARD-FILE, ACCOUNT-FILE, TRANSACT-FILE

        log.info("END OF EXECUTION OF PROGRAM CBTRN01C");
    }

    // =========================================================================
    // 2000-LOOKUP-XREF → lookupXref(String cardNumber)
    // =========================================================================

    /**
     * Looks up the card cross-reference record by card number.
     *
     * <p>Translates COBOL paragraph 2000-LOOKUP-XREF from CBTRN01C.cbl:</p>
     * <pre>
     * MOVE XREF-CARD-NUM TO FD-XREF-CARD-NUM
     * READ XREF-FILE RECORD INTO CARD-XREF-RECORD
     *    KEY IS FD-XREF-CARD-NUM
     *    INVALID KEY
     *      DISPLAY 'INVALID CARD NUMBER FOR XREF'
     *      MOVE 4 TO WS-XREF-READ-STATUS
     *    NOT INVALID KEY
     *      DISPLAY 'SUCCESSFUL READ OF XREF'
     *      DISPLAY 'CARD NUMBER: ' XREF-CARD-NUM
     *      DISPLAY 'ACCOUNT ID : ' XREF-ACCT-ID
     *      DISPLAY 'CUSTOMER ID: ' XREF-CUST-ID
     * END-READ
     * </pre>
     *
     * <p>On INVALID KEY (record not found), a warning is logged and an empty
     * {@link Optional} is returned. This matches the COBOL behavior of setting
     * {@code WS-XREF-READ-STATUS=4} and continuing to the next record without
     * aborting the batch process.</p>
     *
     * @param cardNumber the 16-character card number
     *                   (DALYTRAN-CARD-NUM / FD-XREF-CARD-NUM)
     * @return Optional containing the {@link CardXref} if found;
     *         empty Optional if not found (INVALID KEY)
     */
    public Optional<CardXref> lookupXref(String cardNumber) {
        Optional<CardXref> result = cardXrefRepository.findById(cardNumber);

        if (result.isEmpty()) {
            // INVALID KEY:
            //   DISPLAY 'INVALID CARD NUMBER FOR XREF'
            //   MOVE 4 TO WS-XREF-READ-STATUS
            log.warn("INVALID CARD NUMBER FOR XREF");
        } else {
            // NOT INVALID KEY:
            //   DISPLAY 'SUCCESSFUL READ OF XREF'
            //   DISPLAY 'CARD NUMBER: ' XREF-CARD-NUM
            //   DISPLAY 'ACCOUNT ID : ' XREF-ACCT-ID
            //   DISPLAY 'CUSTOMER ID: ' XREF-CUST-ID
            CardXref xref = result.get();
            log.info("SUCCESSFUL READ OF XREF");
            log.info("CARD NUMBER: {}", xref.getXrefCardNum());
            log.info("ACCOUNT ID : {}", xref.getAccountId());
            log.info("CUSTOMER ID: {}", xref.getCustId());
        }

        return result;
    }

    // =========================================================================
    // 3000-READ-ACCOUNT → readAccount(String accountId)
    // =========================================================================

    /**
     * Reads an account record by account identifier.
     *
     * <p>Translates COBOL paragraph 3000-READ-ACCOUNT from CBTRN01C.cbl:</p>
     * <pre>
     * MOVE ACCT-ID TO FD-ACCT-ID
     * READ ACCOUNT-FILE RECORD INTO ACCOUNT-RECORD
     *    KEY IS FD-ACCT-ID
     *    INVALID KEY
     *      DISPLAY 'INVALID ACCOUNT NUMBER FOUND'
     *      MOVE 4 TO WS-ACCT-READ-STATUS
     *    NOT INVALID KEY
     *      DISPLAY 'SUCCESSFUL READ OF ACCOUNT FILE'
     * END-READ
     * </pre>
     *
     * <p>On INVALID KEY (record not found), a warning is logged and an empty
     * {@link Optional} is returned. This matches the COBOL behavior of setting
     * {@code WS-ACCT-READ-STATUS=4} and continuing without aborting.</p>
     *
     * @param accountId the 11-character account identifier
     *                  (XREF-ACCT-ID / FD-ACCT-ID)
     * @return Optional containing the {@link Account} if found;
     *         empty Optional if not found (INVALID KEY)
     */
    public Optional<Account> readAccount(String accountId) {
        Optional<Account> result = accountRepository.findById(accountId);

        if (result.isEmpty()) {
            // INVALID KEY:
            //   DISPLAY 'INVALID ACCOUNT NUMBER FOUND'
            //   MOVE 4 TO WS-ACCT-READ-STATUS
            log.warn("INVALID ACCOUNT NUMBER FOUND");
        } else {
            // NOT INVALID KEY:
            //   DISPLAY 'SUCCESSFUL READ OF ACCOUNT FILE'
            Account acct = result.get();
            log.info("SUCCESSFUL READ OF ACCOUNT FILE");
            log.debug("ACCOUNT ID       : {}", acct.getAcctId());
            log.debug("ACTIVE STATUS    : {}", acct.getActiveStatus());
            log.debug("CURRENT BALANCE  : {}", acct.getCurrBal());
            log.debug("CREDIT LIMIT     : {}", acct.getCreditLimit());
            log.debug("EXPIRATION DATE  : {}", acct.getExpirationDate());
        }

        return result;
    }

    // =========================================================================
    // Z-DISPLAY-IO-STATUS + Z-ABEND-PROGRAM → logIoStatus(String, String)
    // =========================================================================

    /**
     * Formats and logs a VSAM file status code, then terminates with an exception.
     *
     * <p>Combines the behavior of two COBOL paragraphs that are always called
     * together in error paths:</p>
     *
     * <h4>Z-DISPLAY-IO-STATUS (formatting and display):</h4>
     * <pre>
     * IF IO-STATUS NOT NUMERIC OR IO-STAT1 = '9'
     *     MOVE IO-STAT1 TO IO-STATUS-04(1:1)
     *     MOVE 0 TO TWO-BYTES-BINARY
     *     MOVE IO-STAT2 TO TWO-BYTES-RIGHT
     *     MOVE TWO-BYTES-BINARY TO IO-STATUS-0403
     *     DISPLAY 'FILE STATUS IS: NNNN' IO-STATUS-04
     * ELSE
     *     MOVE '0000' TO IO-STATUS-04
     *     MOVE IO-STATUS TO IO-STATUS-04(3:2)
     *     DISPLAY 'FILE STATUS IS: NNNN' IO-STATUS-04
     * END-IF
     * </pre>
     *
     * <h4>Z-ABEND-PROGRAM (program termination):</h4>
     * <pre>
     * DISPLAY 'ABENDING PROGRAM'
     * MOVE 0 TO TIMING
     * MOVE 999 TO ABCODE
     * CALL 'CEE3ABD'
     * </pre>
     *
     * <p>The 4-character IO-STATUS-04 field is composed of:</p>
     * <ul>
     *   <li>IO-STATUS-0401 (PIC 9, 1 digit) + IO-STATUS-0403 (PIC 999, 3 digits)</li>
     *   <li>Non-numeric/9x statuses: first byte + binary value of second byte
     *       (TWO-BYTES-BINARY REDEFINES logic)</li>
     *   <li>Standard numeric statuses: "00" + the 2-byte status code</li>
     * </ul>
     *
     * @param context description of the I/O operation that failed
     * @param status  the two-character VSAM file status code (IO-STATUS)
     * @throws CardDemoException always — equivalent to Z-ABEND-PROGRAM (ABCODE=999)
     */
    public void logIoStatus(String context, String status) {
        // Z-DISPLAY-IO-STATUS: Format 4-char IO-STATUS-04 for operator output
        String formattedStatus;
        if (status == null || status.length() < 2) {
            // Defensive handling for invalid status input
            formattedStatus = "0000";
        } else {
            char stat1 = status.charAt(0);
            char stat2 = status.charAt(1);

            // IF IO-STATUS NOT NUMERIC OR IO-STAT1 = '9'
            boolean isNonNumeric = !Character.isDigit(stat1)
                    || !Character.isDigit(stat2);
            if (isNonNumeric || stat1 == '9') {
                // Non-numeric or 9x status: convert second byte to binary value
                // using TWO-BYTES-BINARY / TWO-BYTES-ALPHA REDEFINES logic.
                //   MOVE IO-STAT1 TO IO-STATUS-04(1:1)
                //   MOVE 0 TO TWO-BYTES-BINARY
                //   MOVE IO-STAT2 TO TWO-BYTES-RIGHT
                //   MOVE TWO-BYTES-BINARY TO IO-STATUS-0403
                int binaryValue = stat2;
                formattedStatus = String.format("%c%03d", stat1, binaryValue);
            } else {
                // Standard numeric status:
                //   MOVE '0000' TO IO-STATUS-04
                //   MOVE IO-STATUS TO IO-STATUS-04(3:2)
                formattedStatus = "00" + status;
            }
        }

        // DISPLAY 'FILE STATUS IS: NNNN' IO-STATUS-04
        log.error("{} - FILE STATUS IS: NNNN{}", context, formattedStatus);

        // Map status to appropriate typed exception for diagnostic traceability.
        // Status '23' maps to RecordNotFoundException; all others to FileStatusException.
        FileStatusException fileException;
        if (FILE_STATUS_NOT_FOUND.equals(status)) {
            fileException = new RecordNotFoundException(
                    context + " - record not found, file status: " + formattedStatus);
        } else {
            fileException = new FileStatusException(
                    status != null ? status : "00",
                    context + " - I/O error, file status: " + formattedStatus);
        }

        // Log the mapped VSAM file status code for diagnostic traceability
        log.error("Mapped VSAM file status code: {}", fileException.getFileStatusCode());

        // Z-ABEND-PROGRAM:
        //   DISPLAY 'ABENDING PROGRAM'
        //   MOVE 0 TO TIMING
        //   MOVE 999 TO ABCODE
        //   CALL 'CEE3ABD'
        log.error("ABENDING PROGRAM");
        throw new CardDemoException(
                "ABENDING PROGRAM - " + context
                        + ", ABCODE=999, file status: "
                        + fileException.getFileStatusCode(),
                fileException);
    }

    // =========================================================================
    // 1000-DALYTRAN-GET-NEXT (display) → logDailyTransaction(DailyTransaction)
    // =========================================================================

    /**
     * Logs the contents of a daily transaction record for operator visibility.
     *
     * <p>Translates the DISPLAY statement in COBOL paragraph
     * 1000-DALYTRAN-GET-NEXT and MAIN-PARA from CBTRN01C.cbl:</p>
     * <pre>
     * IF END-OF-DAILY-TRANS-FILE = 'N'
     *     DISPLAY DALYTRAN-RECORD
     * END-IF
     * </pre>
     *
     * <p>In COBOL, {@code DISPLAY DALYTRAN-RECORD} outputs all fields of the
     * 350-byte record structure (CVTRA06Y.cpy) as a single line. This method
     * logs each field individually using SLF4J structured logging, preserving
     * all field values from the original record layout.</p>
     *
     * @param dt the daily transaction record to log (must not be {@code null})
     */
    public void logDailyTransaction(DailyTransaction dt) {
        log.info("--- Daily Transaction Record ---");
        log.info("CARD NUM      : {}", dt.getCardNum());
        log.info("AMOUNT        : {}", dt.getAmount());
        log.info("TYPE CODE     : {}", dt.getTypeCode());
        log.info("CATEGORY CODE : {}", dt.getCategoryCode());
        log.info("SOURCE        : {}", dt.getSource());
        log.info("DESCRIPTION   : {}", dt.getDescription());
        log.info("MERCHANT ID   : {}", dt.getMerchantId());
        log.info("MERCHANT NAME : {}", dt.getMerchantName());
        log.info("MERCHANT CITY : {}", dt.getMerchantCity());
        log.info("MERCHANT ZIP  : {}", dt.getMerchantZip());
        log.info("ORIG TIMESTAMP: {}", dt.getOrigTimestamp());
        log.info("PROC TIMESTAMP: {}", dt.getProcTimestamp());
    }
}
