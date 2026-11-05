/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License").
 * You may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.cardemo.service.batch;

import com.cardemo.common.exception.CardDemoException;
import com.cardemo.common.exception.FileStatusException;
import com.cardemo.entity.Account;
import com.cardemo.entity.CardXref;
import com.cardemo.entity.Customer;
import com.cardemo.entity.Transaction;
import com.cardemo.repository.AccountRepository;
import com.cardemo.repository.CardXrefRepository;
import com.cardemo.repository.CustomerRepository;
import com.cardemo.repository.TransactionRepository;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.Iterator;
import java.util.List;
import java.util.Optional;

/**
 * Statement I/O service — translates COBOL subroutine CBSTM03B.CBL.
 *
 * <p>This is the <strong>FOUNDATIONAL</strong> batch service that centralises
 * all dataset OPEN / READ / READ-KEY / CLOSE operations for the four VSAM
 * datasets consumed by the statement-generation engine
 * ({@code StatementEngineService}, migrated from CBSTM03A.CBL).</p>
 *
 * <h2>COBOL Origin</h2>
 * <p>In COBOL the caller issued:
 * <pre>
 *   CALL 'CBSTM03B' USING WS-M03B-AREA
 * </pre>
 * where {@code WS-M03B-AREA} contained a control block:
 * <pre>
 *   01  LK-M03B-AREA.
 *       05  LK-M03B-DD     PIC X(08).   (dataset: TRNXFILE/XREFFILE/CUSTFILE/ACCTFILE)
 *       05  LK-M03B-OPER   PIC X(01).   (operation: O/C/R/K/W/Z)
 *       05  LK-M03B-RC     PIC X(02).   (return code: 00/10/23/35/99)
 *       05  LK-M03B-KEY    PIC X(25).   (key for keyed reads)
 *       05  LK-M03B-KEY-LN PIC S9(4).   (key length)
 *       05  LK-M03B-FLDT   PIC X(1000). (record data buffer)
 * </pre>
 * In Java this dispatch pattern is replaced by <strong>typed methods</strong>
 * — one set per dataset — injected via {@code @Autowired} into
 * {@code StatementEngineService}.</p>
 *
 * <h2>COBOL Paragraph → Java Method Traceability</h2>
 * <table>
 *   <caption>100 % paragraph mapping</caption>
 *   <tr><th>COBOL Paragraph</th><th>Java Method</th></tr>
 *   <tr><td>0000-START (EVALUATE LK-M03B-DD)</td>
 *       <td>Method dispatch (typed methods replace control block)</td></tr>
 *   <tr><td>1000-TRNXFILE-PROC / 1000-TRNXFILE-OPEN</td>
 *       <td>{@link #openTransactionFile()}</td></tr>
 *   <tr><td>1000-TRNXFILE-PROC / 1000-TRNXFILE-READ</td>
 *       <td>{@link #readNextTransaction()}</td></tr>
 *   <tr><td>(extended — keyed read)</td>
 *       <td>{@link #readTransactionByKey(String)}</td></tr>
 *   <tr><td>1000-TRNXFILE-PROC / 1000-TRNXFILE-CLOSE</td>
 *       <td>{@link #closeTransactionFile()}</td></tr>
 *   <tr><td>2000-XREFFILE-PROC / 2000-XREFFILE-OPEN</td>
 *       <td>{@link #openXrefFile()}</td></tr>
 *   <tr><td>2000-XREFFILE-PROC / 2000-XREFFILE-READ</td>
 *       <td>{@link #readNextXref()}</td></tr>
 *   <tr><td>(extended — keyed read)</td>
 *       <td>{@link #readXrefByKey(String)}</td></tr>
 *   <tr><td>2000-XREFFILE-PROC / 2000-XREFFILE-CLOSE</td>
 *       <td>{@link #closeXrefFile()}</td></tr>
 *   <tr><td>3000-CUSTFILE-PROC / 3000-CUSTFILE-OPEN</td>
 *       <td>{@link #openCustomerFile()}</td></tr>
 *   <tr><td>3000-CUSTFILE-PROC / 3000-CUSTFILE-READ</td>
 *       <td>{@link #readNextCustomer()}</td></tr>
 *   <tr><td>3000-CUSTFILE-PROC / 3000-CUSTFILE-READ-KEY</td>
 *       <td>{@link #readCustomerByKey(String)}</td></tr>
 *   <tr><td>3000-CUSTFILE-PROC / 3000-CUSTFILE-CLOSE</td>
 *       <td>{@link #closeCustomerFile()}</td></tr>
 *   <tr><td>4000-ACCTFILE-PROC / 4000-ACCTFILE-OPEN</td>
 *       <td>{@link #openAccountFile()}</td></tr>
 *   <tr><td>4000-ACCTFILE-PROC / 4000-ACCTFILE-READ</td>
 *       <td>{@link #readNextAccount()}</td></tr>
 *   <tr><td>4000-ACCTFILE-PROC / 4000-ACCTFILE-READ-KEY</td>
 *       <td>{@link #readAccountByKey(String)}</td></tr>
 *   <tr><td>4000-ACCTFILE-PROC / 4000-ACCTFILE-CLOSE</td>
 *       <td>{@link #closeAccountFile()}</td></tr>
 *   <tr><td>9999-GOBACK</td>
 *       <td>Normal method return</td></tr>
 * </table>
 *
 * <h2>Return-Code Mapping</h2>
 * <table>
 *   <caption>LK-M03B-RC → Java semantics</caption>
 *   <tr><th>RC</th><th>Meaning</th><th>Java equivalent</th></tr>
 *   <tr><td>00</td><td>Success</td><td>Populated Optional / List returned</td></tr>
 *   <tr><td>10</td><td>EOF</td><td>{@link Optional#empty()}</td></tr>
 *   <tr><td>23</td><td>Record not found</td><td>{@link Optional#empty()}</td></tr>
 *   <tr><td>35</td><td>File not available</td>
 *       <td>{@link FileStatusException}</td></tr>
 *   <tr><td>99</td><td>Unknown dataset</td>
 *       <td>{@link IllegalArgumentException}</td></tr>
 * </table>
 *
 * @see com.cardemo.entity.Transaction
 * @see com.cardemo.entity.CardXref
 * @see com.cardemo.entity.Customer
 * @see com.cardemo.entity.Account
 */
@Service
public class StatementIoService {

    // -----------------------------------------------------------------------
    // Logger — replaces COBOL DISPLAY statements for operator diagnostics
    // -----------------------------------------------------------------------

    private static final Logger log =
            LoggerFactory.getLogger(StatementIoService.class);

    // -----------------------------------------------------------------------
    // Injected repositories (replacing VSAM FD file definitions)
    // -----------------------------------------------------------------------

    private final TransactionRepository transactionRepository;
    private final CardXrefRepository cardXrefRepository;
    private final CustomerRepository customerRepository;
    private final AccountRepository accountRepository;

    // -----------------------------------------------------------------------
    // Internal iterators — mimic VSAM sequential OPEN→READ→…→EOF→CLOSE
    // -----------------------------------------------------------------------

    /**
     * Sequential-read iterator for the TRNX-FILE dataset.
     * Initialised by {@link #openTransactionFile()}, consumed by
     * {@link #readNextTransaction()}, released by
     * {@link #closeTransactionFile()}.
     */
    private Iterator<Transaction> transactionIterator;

    /**
     * Sequential-read iterator for the XREF-FILE dataset.
     * Initialised by {@link #openXrefFile()}, consumed by
     * {@link #readNextXref()}, released by {@link #closeXrefFile()}.
     */
    private Iterator<CardXref> xrefIterator;

    /**
     * Sequential-read iterator for the CUST-FILE dataset.
     * Initialised by {@link #openCustomerFile()}, consumed by
     * {@link #readNextCustomer()}, released by
     * {@link #closeCustomerFile()}.
     */
    private Iterator<Customer> customerIterator;

    /**
     * Sequential-read iterator for the ACCT-FILE dataset.
     * Initialised by {@link #openAccountFile()}, consumed by
     * {@link #readNextAccount()}, released by
     * {@link #closeAccountFile()}.
     */
    private Iterator<Account> accountIterator;

    // -----------------------------------------------------------------------
    // Constructor (Spring constructor injection)
    // -----------------------------------------------------------------------

    /**
     * Creates a new {@code StatementIoService} with all required repository
     * dependencies.
     *
     * <p>Constructor injection is preferred over field injection for
     * testability and immutability of the required dependencies.</p>
     *
     * @param transactionRepository repository for TRNX-FILE (TRANSACT VSAM)
     * @param cardXrefRepository    repository for XREF-FILE (CARDXREF VSAM)
     * @param customerRepository    repository for CUST-FILE (CUSTDATA VSAM)
     * @param accountRepository     repository for ACCT-FILE (ACCTDATA VSAM)
     */
    @Autowired
    public StatementIoService(TransactionRepository transactionRepository,
                              CardXrefRepository cardXrefRepository,
                              CustomerRepository customerRepository,
                              AccountRepository accountRepository) {
        this.transactionRepository = transactionRepository;
        this.cardXrefRepository = cardXrefRepository;
        this.customerRepository = customerRepository;
        this.accountRepository = accountRepository;
    }

    // ===================================================================
    // TRNX-FILE operations (1000-TRNXFILE-PROC in CBSTM03B.CBL)
    // VSAM: INDEXED, SEQUENTIAL access, key = FD-TRNXS-ID
    // ===================================================================

    /**
     * Opens the transaction file for sequential reading.
     *
     * <p><strong>COBOL equivalent:</strong> paragraph
     * {@code 1000-TRNXFILE-OPEN} — {@code OPEN INPUT TRNX-FILE}.
     * Loads all transaction records from the repository and initialises
     * an internal iterator for sequential traversal.</p>
     *
     * @return the complete list of transactions (RC '00')
     * @throws FileStatusException if the underlying repository access fails
     *                             (RC '35' — file not available)
     */
    public List<Transaction> openTransactionFile() {
        log.info("TRNXFILE OPEN — loading all transaction records");
        try {
            List<Transaction> records = transactionRepository.findAll();
            transactionIterator = records.iterator();
            log.info("TRNXFILE OPEN complete — {} records loaded, RC=00",
                    records.size());
            return records;
        } catch (Exception ex) {
            log.error("TRNXFILE OPEN failed — RC=35", ex);
            throw new FileStatusException("35",
                    "Failed to open TRNXFILE: " + ex.getMessage());
        }
    }

    /**
     * Reads the next transaction record sequentially.
     *
     * <p><strong>COBOL equivalent:</strong> paragraph
     * {@code 1000-TRNXFILE-READ} —
     * {@code READ TRNX-FILE INTO LK-M03B-FLDT}.</p>
     *
     * @return {@link Optional} containing the next transaction (RC '00'),
     *         or {@link Optional#empty()} if end-of-file (RC '10')
     * @throws FileStatusException if the file has not been opened
     *                             (RC '47' — read on file not opened)
     */
    public Optional<Transaction> readNextTransaction() {
        if (transactionIterator == null) {
            log.error("TRNXFILE READ failed — file not opened, RC=47");
            throw new FileStatusException("47",
                    "TRNXFILE not opened — call openTransactionFile() first");
        }
        if (transactionIterator.hasNext()) {
            Transaction record = transactionIterator.next();
            log.debug("TRNXFILE READ — tranId={}, cardNum={}, RC=00",
                    record.getTranId(), record.getCardNum());
            return Optional.of(record);
        }
        log.debug("TRNXFILE READ — end of file reached, RC=10");
        return Optional.empty();
    }

    /**
     * Reads a transaction record by its primary key.
     *
     * <p><strong>COBOL equivalent:</strong> keyed read on TRNX-FILE
     * ({@code READ TRNX-FILE INTO ... KEY IS FD-TRNXS-ID}).
     * While the original CBSTM03B.CBL only implements sequential read
     * for TRNXFILE, this keyed-read method is provided for completeness
     * to support the full OPEN/READ/READ-KEY/CLOSE contract.</p>
     *
     * @param key the transaction identifier (FD-TRNXS-ID, up to 16 chars)
     * @return {@link Optional} containing the transaction if found (RC '00'),
     *         or {@link Optional#empty()} if not found (RC '23')
     * @throws CardDemoException if the repository access fails unexpectedly
     */
    public Optional<Transaction> readTransactionByKey(String key) {
        log.debug("TRNXFILE READ-KEY — key={}", key);
        try {
            Optional<Transaction> result = transactionRepository.findById(key);
            if (result.isPresent()) {
                log.debug("TRNXFILE READ-KEY — found tranId={}, RC=00",
                        result.get().getTranId());
            } else {
                log.debug("TRNXFILE READ-KEY — not found, RC=23");
            }
            return result;
        } catch (Exception ex) {
            log.error("TRNXFILE READ-KEY failed for key={}", key, ex);
            throw new CardDemoException(
                    "TRNXFILE READ-KEY failed: " + ex.getMessage(), ex);
        }
    }

    /**
     * Closes the transaction file and releases the sequential iterator.
     *
     * <p><strong>COBOL equivalent:</strong> paragraph
     * {@code 1000-TRNXFILE-CLOSE} — {@code CLOSE TRNX-FILE}.</p>
     */
    public void closeTransactionFile() {
        log.info("TRNXFILE CLOSE — releasing iterator");
        transactionIterator = null;
        log.info("TRNXFILE CLOSE complete, RC=00");
    }

    // ===================================================================
    // XREF-FILE operations (2000-XREFFILE-PROC in CBSTM03B.CBL)
    // VSAM: INDEXED, SEQUENTIAL access, key = FD-XREF-CARD-NUM
    // ===================================================================

    /**
     * Opens the card cross-reference file for sequential reading.
     *
     * <p><strong>COBOL equivalent:</strong> paragraph
     * {@code 2000-XREFFILE-OPEN} — {@code OPEN INPUT XREF-FILE}.
     * Loads all cross-reference records from the repository and
     * initialises an internal iterator.</p>
     *
     * @return the complete list of card cross-reference records (RC '00')
     * @throws FileStatusException if the underlying repository access fails
     *                             (RC '35' — file not available)
     */
    public List<CardXref> openXrefFile() {
        log.info("XREFFILE OPEN — loading all card xref records");
        try {
            List<CardXref> records = cardXrefRepository.findAll();
            xrefIterator = records.iterator();
            log.info("XREFFILE OPEN complete — {} records loaded, RC=00",
                    records.size());
            return records;
        } catch (Exception ex) {
            log.error("XREFFILE OPEN failed — RC=35", ex);
            throw new FileStatusException("35",
                    "Failed to open XREFFILE: " + ex.getMessage());
        }
    }

    /**
     * Reads the next cross-reference record sequentially.
     *
     * <p><strong>COBOL equivalent:</strong> paragraph
     * {@code 2000-XREFFILE-READ} —
     * {@code READ XREF-FILE INTO LK-M03B-FLDT}.</p>
     *
     * @return {@link Optional} containing the next card xref (RC '00'),
     *         or {@link Optional#empty()} if end-of-file (RC '10')
     * @throws FileStatusException if the file has not been opened
     *                             (RC '47' — read on file not opened)
     */
    public Optional<CardXref> readNextXref() {
        if (xrefIterator == null) {
            log.error("XREFFILE READ failed — file not opened, RC=47");
            throw new FileStatusException("47",
                    "XREFFILE not opened — call openXrefFile() first");
        }
        if (xrefIterator.hasNext()) {
            CardXref record = xrefIterator.next();
            log.debug("XREFFILE READ — cardNum={}, custId={}, acctId={}, RC=00",
                    record.getXrefCardNum(), record.getCustId(),
                    record.getAccountId());
            return Optional.of(record);
        }
        log.debug("XREFFILE READ — end of file reached, RC=10");
        return Optional.empty();
    }

    /**
     * Reads a cross-reference record by its primary key (card number).
     *
     * <p><strong>COBOL equivalent:</strong> paragraph
     * {@code 2000-XREFFILE-READ-KEY} —
     * {@code READ XREF-FILE INTO ... KEY IS FD-XREF-CARD-NUM}.
     * While the original CBSTM03B.CBL only implements sequential read
     * for XREFFILE, this keyed-read method is provided for the full
     * OPEN/READ/READ-KEY/CLOSE contract.</p>
     *
     * @param key the card number (FD-XREF-CARD-NUM, 16 chars)
     * @return {@link Optional} containing the xref if found (RC '00'),
     *         or {@link Optional#empty()} if not found (RC '23')
     * @throws CardDemoException if the repository access fails unexpectedly
     */
    public Optional<CardXref> readXrefByKey(String key) {
        log.debug("XREFFILE READ-KEY — key={}", key);
        try {
            Optional<CardXref> result = cardXrefRepository.findById(key);
            if (result.isPresent()) {
                log.debug("XREFFILE READ-KEY — found cardNum={}, RC=00",
                        result.get().getXrefCardNum());
            } else {
                log.debug("XREFFILE READ-KEY — not found, RC=23");
            }
            return result;
        } catch (Exception ex) {
            log.error("XREFFILE READ-KEY failed for key={}", key, ex);
            throw new CardDemoException(
                    "XREFFILE READ-KEY failed: " + ex.getMessage(), ex);
        }
    }

    /**
     * Closes the cross-reference file and releases the sequential iterator.
     *
     * <p><strong>COBOL equivalent:</strong> paragraph
     * {@code 2000-XREFFILE-CLOSE} — {@code CLOSE XREF-FILE}.</p>
     */
    public void closeXrefFile() {
        log.info("XREFFILE CLOSE — releasing iterator");
        xrefIterator = null;
        log.info("XREFFILE CLOSE complete, RC=00");
    }

    // ===================================================================
    // CUST-FILE operations (3000-CUSTFILE-PROC in CBSTM03B.CBL)
    // VSAM: INDEXED, RANDOM access, key = FD-CUST-ID
    // ===================================================================

    /**
     * Opens the customer file for reading.
     *
     * <p><strong>COBOL equivalent:</strong> paragraph
     * {@code 3000-CUSTFILE-OPEN} — {@code OPEN INPUT CUST-FILE}.
     * Loads all customer records from the repository and initialises
     * an internal iterator for sequential traversal.</p>
     *
     * @return the complete list of customer records (RC '00')
     * @throws FileStatusException if the underlying repository access fails
     *                             (RC '35' — file not available)
     */
    public List<Customer> openCustomerFile() {
        log.info("CUSTFILE OPEN — loading all customer records");
        try {
            List<Customer> records = customerRepository.findAll();
            customerIterator = records.iterator();
            log.info("CUSTFILE OPEN complete — {} records loaded, RC=00",
                    records.size());
            return records;
        } catch (Exception ex) {
            log.error("CUSTFILE OPEN failed — RC=35", ex);
            throw new FileStatusException("35",
                    "Failed to open CUSTFILE: " + ex.getMessage());
        }
    }

    /**
     * Reads the next customer record sequentially.
     *
     * <p>While the COBOL CUST-FILE uses RANDOM access mode (only keyed
     * reads in CBSTM03B.CBL paragraph {@code 3000-CUSTFILE-READ-KEY}),
     * this sequential-read method is provided for completeness to support
     * the full OPEN/READ/READ-KEY/CLOSE contract across all datasets.</p>
     *
     * @return {@link Optional} containing the next customer (RC '00'),
     *         or {@link Optional#empty()} if end-of-file (RC '10')
     * @throws FileStatusException if the file has not been opened
     *                             (RC '47' — read on file not opened)
     */
    public Optional<Customer> readNextCustomer() {
        if (customerIterator == null) {
            log.error("CUSTFILE READ failed — file not opened, RC=47");
            throw new FileStatusException("47",
                    "CUSTFILE not opened — call openCustomerFile() first");
        }
        if (customerIterator.hasNext()) {
            Customer record = customerIterator.next();
            log.debug("CUSTFILE READ — custId={}, RC=00",
                    record.getCustId());
            return Optional.of(record);
        }
        log.debug("CUSTFILE READ — end of file reached, RC=10");
        return Optional.empty();
    }

    /**
     * Reads a customer record by its primary key.
     *
     * <p><strong>COBOL equivalent:</strong> paragraph
     * {@code 3000-CUSTFILE-READ-KEY} — keyed read on CUST-FILE:
     * <pre>
     *   MOVE LK-M03B-KEY (1:LK-M03B-KEY-LN) TO FD-CUST-ID
     *   READ CUST-FILE INTO LK-M03B-FLDT
     * </pre>
     * Returns {@link Optional#empty()} when the record is not found
     * (equivalent to COBOL INVALID KEY → RC '23').</p>
     *
     * @param key the customer identifier (FD-CUST-ID, 9 chars)
     * @return {@link Optional} containing the customer if found (RC '00'),
     *         or {@link Optional#empty()} if not found (RC '23')
     * @throws CardDemoException if the repository access fails unexpectedly
     */
    public Optional<Customer> readCustomerByKey(String key) {
        log.debug("CUSTFILE READ-KEY — key={}", key);
        try {
            Optional<Customer> result = customerRepository.findById(key);
            if (result.isPresent()) {
                log.debug("CUSTFILE READ-KEY — found custId={}, RC=00",
                        result.get().getCustId());
            } else {
                log.debug("CUSTFILE READ-KEY — not found, RC=23");
            }
            return result;
        } catch (Exception ex) {
            log.error("CUSTFILE READ-KEY failed for key={}", key, ex);
            throw new CardDemoException(
                    "CUSTFILE READ-KEY failed: " + ex.getMessage(), ex);
        }
    }

    /**
     * Closes the customer file and releases the sequential iterator.
     *
     * <p><strong>COBOL equivalent:</strong> paragraph
     * {@code 3000-CUSTFILE-CLOSE} — {@code CLOSE CUST-FILE}.</p>
     */
    public void closeCustomerFile() {
        log.info("CUSTFILE CLOSE — releasing iterator");
        customerIterator = null;
        log.info("CUSTFILE CLOSE complete, RC=00");
    }

    // ===================================================================
    // ACCT-FILE operations (4000-ACCTFILE-PROC in CBSTM03B.CBL)
    // VSAM: INDEXED, RANDOM access, key = FD-ACCT-ID
    // ===================================================================

    /**
     * Opens the account file for reading.
     *
     * <p><strong>COBOL equivalent:</strong> paragraph
     * {@code 4000-ACCTFILE-OPEN} — {@code OPEN INPUT ACCT-FILE}.
     * Loads all account records from the repository and initialises
     * an internal iterator for sequential traversal.</p>
     *
     * @return the complete list of account records (RC '00')
     * @throws FileStatusException if the underlying repository access fails
     *                             (RC '35' — file not available)
     */
    public List<Account> openAccountFile() {
        log.info("ACCTFILE OPEN — loading all account records");
        try {
            List<Account> records = accountRepository.findAll();
            accountIterator = records.iterator();
            log.info("ACCTFILE OPEN complete — {} records loaded, RC=00",
                    records.size());
            return records;
        } catch (Exception ex) {
            log.error("ACCTFILE OPEN failed — RC=35", ex);
            throw new FileStatusException("35",
                    "Failed to open ACCTFILE: " + ex.getMessage());
        }
    }

    /**
     * Reads the next account record sequentially.
     *
     * <p>While the COBOL ACCT-FILE uses RANDOM access mode (only keyed
     * reads in CBSTM03B.CBL paragraph {@code 4000-ACCTFILE-READ-KEY}),
     * this sequential-read method is provided for completeness to support
     * the full OPEN/READ/READ-KEY/CLOSE contract across all datasets.</p>
     *
     * @return {@link Optional} containing the next account (RC '00'),
     *         or {@link Optional#empty()} if end-of-file (RC '10')
     * @throws FileStatusException if the file has not been opened
     *                             (RC '47' — read on file not opened)
     */
    public Optional<Account> readNextAccount() {
        if (accountIterator == null) {
            log.error("ACCTFILE READ failed — file not opened, RC=47");
            throw new FileStatusException("47",
                    "ACCTFILE not opened — call openAccountFile() first");
        }
        if (accountIterator.hasNext()) {
            Account record = accountIterator.next();
            log.debug("ACCTFILE READ — acctId={}, RC=00",
                    record.getAcctId());
            return Optional.of(record);
        }
        log.debug("ACCTFILE READ — end of file reached, RC=10");
        return Optional.empty();
    }

    /**
     * Reads an account record by its primary key.
     *
     * <p><strong>COBOL equivalent:</strong> paragraph
     * {@code 4000-ACCTFILE-READ-KEY} — keyed read on ACCT-FILE:
     * <pre>
     *   MOVE LK-M03B-KEY (1:LK-M03B-KEY-LN) TO FD-ACCT-ID
     *   READ ACCT-FILE INTO LK-M03B-FLDT
     * </pre>
     * Returns {@link Optional#empty()} when the record is not found
     * (equivalent to COBOL INVALID KEY → RC '23').</p>
     *
     * @param key the account identifier (FD-ACCT-ID, 11 chars)
     * @return {@link Optional} containing the account if found (RC '00'),
     *         or {@link Optional#empty()} if not found (RC '23')
     * @throws CardDemoException if the repository access fails unexpectedly
     */
    public Optional<Account> readAccountByKey(String key) {
        log.debug("ACCTFILE READ-KEY — key={}", key);
        try {
            Optional<Account> result = accountRepository.findById(key);
            if (result.isPresent()) {
                log.debug("ACCTFILE READ-KEY — found acctId={}, RC=00",
                        result.get().getAcctId());
            } else {
                log.debug("ACCTFILE READ-KEY — not found, RC=23");
            }
            return result;
        } catch (Exception ex) {
            log.error("ACCTFILE READ-KEY failed for key={}", key, ex);
            throw new CardDemoException(
                    "ACCTFILE READ-KEY failed: " + ex.getMessage(), ex);
        }
    }

    /**
     * Closes the account file and releases the sequential iterator.
     *
     * <p><strong>COBOL equivalent:</strong> paragraph
     * {@code 4000-ACCTFILE-CLOSE} — {@code CLOSE ACCT-FILE}.</p>
     */
    public void closeAccountFile() {
        log.info("ACCTFILE CLOSE — releasing iterator");
        accountIterator = null;
        log.info("ACCTFILE CLOSE complete, RC=00");
    }
}
