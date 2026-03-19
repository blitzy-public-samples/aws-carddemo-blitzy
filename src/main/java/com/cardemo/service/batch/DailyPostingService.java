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
import com.cardemo.common.exception.RecordNotFoundException;
import com.cardemo.common.util.DateConversionUtil;
import com.cardemo.entity.Account;
import com.cardemo.entity.CardXref;
import com.cardemo.entity.CategoryBalance;
import com.cardemo.entity.DailyTransaction;
import com.cardemo.entity.Transaction;
import com.cardemo.repository.AccountRepository;
import com.cardemo.repository.CardXrefRepository;
import com.cardemo.repository.CategoryBalanceRepository;
import com.cardemo.repository.DailyTransactionRepository;
import com.cardemo.repository.TransactionRepository;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Daily transaction posting batch service — the most complex batch service in CardDemo.
 *
 * <p>Faithfully translates CBTRN02C.cbl (PROGRAM-ID: CBTRN02C), which consumes the
 * DALYTRAN sequential input file, performs multi-step validation (4 reject codes),
 * posts valid transactions to TRANSACT, updates TCATBAL and ACCOUNT records, and
 * writes rejected records to DALYREJS.</p>
 *
 * <h2>COBOL Paragraph → Java Method Traceability (100% coverage)</h2>
 * <table>
 *   <caption>Paragraph-to-Method Mapping</caption>
 *   <tr><th>COBOL Paragraph</th><th>Java Method</th></tr>
 *   <tr><td>MAIN (Procedure Division)</td><td>{@link #processDailyTransactions(List)}</td></tr>
 *   <tr><td>0000-0500 (file opens)</td><td>N/A — Spring manages connections</td></tr>
 *   <tr><td>1000-DALYTRAN-GET-NEXT</td><td>Iterator pattern within main loop</td></tr>
 *   <tr><td>1500-VALIDATE-TRAN</td><td>{@link #validateTransaction(DailyTransaction)}</td></tr>
 *   <tr><td>1500-A-LOOKUP-XREF</td><td>{@link #lookupXref(String)}</td></tr>
 *   <tr><td>1500-B-LOOKUP-ACCT</td><td>{@link #lookupAccount(String, DailyTransaction)}</td></tr>
 *   <tr><td>2000-POST-TRANSACTION</td><td>{@link #postTransaction(DailyTransaction, CardXref, Account)}</td></tr>
 *   <tr><td>2500-WRITE-REJECT-REC</td><td>{@link #writeRejectRecord(DailyTransaction, int, String)}</td></tr>
 *   <tr><td>2700-UPDATE-TCATBAL</td><td>{@link #updateTcatbal(DailyTransaction, Account)}</td></tr>
 *   <tr><td>2700-A-CREATE-TCATBAL-REC</td><td>{@link #createTcatbalRecord(DailyTransaction, String)}</td></tr>
 *   <tr><td>2700-B-UPDATE-TCATBAL-REC</td><td>{@link #updateTcatbalRecord(CategoryBalance, BigDecimal)}</td></tr>
 *   <tr><td>2800-UPDATE-ACCOUNT-REC</td><td>{@link #updateAccountRecord(Account, DailyTransaction)}</td></tr>
 *   <tr><td>2900-WRITE-TRANSACTION-FILE</td><td>{@link #writeTransactionRecord(Transaction)}</td></tr>
 *   <tr><td>Z-GET-DB2-FORMAT-TIMESTAMP</td><td>{@link #generateDb2Timestamp()}</td></tr>
 *   <tr><td>9000-9500 (file closes)</td><td>N/A — Spring manages connections</td></tr>
 *   <tr><td>9910-DISPLAY-IO-STATUS</td><td>SLF4J error logging with file status details</td></tr>
 *   <tr><td>9999-ABEND-PROGRAM</td><td>{@link CardDemoException} / {@link FileStatusException} throw</td></tr>
 * </table>
 *
 * <h2>Validation Reject Codes</h2>
 * <ul>
 *   <li><b>100</b> — INVALID CARD NUMBER FOUND (XREF lookup failed)</li>
 *   <li><b>101</b> — ACCOUNT RECORD NOT FOUND (account lookup failed)</li>
 *   <li><b>102</b> — OVERLIMIT TRANSACTION (credit limit exceeded)</li>
 *   <li><b>103</b> — TRANSACTION RECEIVED AFTER ACCT EXPIRATION (expired account)</li>
 * </ul>
 *
 * <h2>Return Code Semantics</h2>
 * <ul>
 *   <li>RETURN-CODE = 0 → all transactions posted, no rejects</li>
 *   <li>RETURN-CODE = 4 → some transactions rejected</li>
 * </ul>
 *
 * <h2>Thread Safety</h2>
 * <p>This service uses instance fields for working-storage state ({@code currentXref},
 * {@code currentAccount}, counters) matching the COBOL single-threaded batch execution
 * model. It is NOT thread-safe and should be called sequentially within a Spring Batch
 * job step.</p>
 *
 * @see <a href="app/cbl/CBTRN02C.cbl">CBTRN02C.cbl — Daily Transaction Posting</a>
 * @see <a href="app/cpy/CVTRA06Y.cpy">CVTRA06Y.cpy — DALYTRAN Record Layout</a>
 * @see <a href="app/cpy/CVTRA05Y.cpy">CVTRA05Y.cpy — TRAN Record Layout</a>
 */
@Service
public class DailyPostingService {

    private static final Logger logger = LoggerFactory.getLogger(DailyPostingService.class);

    // ========================================================================
    // Injected Dependencies (Spring Data JPA Repositories)
    // ========================================================================

    private final DailyTransactionRepository dailyTransactionRepository;
    private final TransactionRepository transactionRepository;
    private final CardXrefRepository cardXrefRepository;
    private final AccountRepository accountRepository;
    private final CategoryBalanceRepository categoryBalanceRepository;

    // ========================================================================
    // Working Storage Fields (← CBTRN02C.cbl WORKING-STORAGE SECTION)
    // ========================================================================

    /**
     * WS-TRANSACTION-COUNT PIC 9(09) — total transactions processed in this batch run.
     */
    private int transactionCount;

    /**
     * WS-REJECT-COUNT PIC 9(09) — total transactions rejected in this batch run.
     */
    private int rejectCount;

    /**
     * Current CARD-XREF-RECORD from the most recent 1500-A-LOOKUP-XREF call.
     * Stored as working-storage equivalent for use by subsequent paragraphs
     * (2000-POST-TRANSACTION uses XREF-ACCT-ID from this record).
     */
    private CardXref currentXref;

    /**
     * Current ACCOUNT-RECORD from the most recent 1500-B-LOOKUP-ACCT call.
     * Stored as working-storage equivalent for use by subsequent paragraphs
     * (2800-UPDATE-ACCOUNT-REC modifies this record).
     */
    private Account currentAccount;

    // ========================================================================
    // Constructor Injection
    // ========================================================================

    /**
     * Constructs a new DailyPostingService with all required repository dependencies.
     *
     * <p>Uses constructor injection (Spring-recommended) with {@code @Autowired}.
     * Each repository corresponds to a VSAM file definition (FD) in CBTRN02C.cbl:</p>
     * <ul>
     *   <li>{@code dailyTransactionRepository} → FD DALYTRAN-FILE (sequential input)</li>
     *   <li>{@code transactionRepository} → FD TRANSACT-FILE (indexed output)</li>
     *   <li>{@code cardXrefRepository} → FD XREF-FILE (indexed lookup)</li>
     *   <li>{@code accountRepository} → FD ACCOUNT-FILE (indexed read/update)</li>
     *   <li>{@code categoryBalanceRepository} → FD TCATBAL-FILE (indexed read/write)</li>
     * </ul>
     *
     * @param dailyTransactionRepository repository for DALYTRAN staging records
     * @param transactionRepository      repository for TRANSACT master records
     * @param cardXrefRepository         repository for CARDXREF junction records
     * @param accountRepository          repository for ACCTDATA master records
     * @param categoryBalanceRepository  repository for TCATBALF balance records
     */
    @Autowired
    public DailyPostingService(
            DailyTransactionRepository dailyTransactionRepository,
            TransactionRepository transactionRepository,
            CardXrefRepository cardXrefRepository,
            AccountRepository accountRepository,
            CategoryBalanceRepository categoryBalanceRepository) {
        this.dailyTransactionRepository = dailyTransactionRepository;
        this.transactionRepository = transactionRepository;
        this.cardXrefRepository = cardXrefRepository;
        this.accountRepository = accountRepository;
        this.categoryBalanceRepository = categoryBalanceRepository;
    }

    // ========================================================================
    // Inner Classes: ValidationResult and RejectRecord
    // ========================================================================

    /**
     * Validation result carrying the fail reason code and description.
     *
     * <p>Maps directly to CBTRN02C.cbl working-storage fields:</p>
     * <ul>
     *   <li>{@code failReasonCode} → WS-VALIDATION-FAIL-REASON PIC 9(03)</li>
     *   <li>{@code failReasonDescription} → WS-VALIDATION-FAIL-REASON-DESC PIC X(50)</li>
     *   <li>{@code valid} → convenience flag: true when failReasonCode == 0</li>
     * </ul>
     */
    public static class ValidationResult {

        private final int failReasonCode;
        private final String failReasonDescription;
        private final boolean valid;

        /**
         * Constructs a new ValidationResult.
         *
         * @param failReasonCode        the reject code (0 = valid, 100-103 = reject)
         * @param failReasonDescription the reject description text
         * @param valid                 true if the transaction passed all checks
         */
        public ValidationResult(int failReasonCode, String failReasonDescription,
                                boolean valid) {
            this.failReasonCode = failReasonCode;
            this.failReasonDescription = failReasonDescription;
            this.valid = valid;
        }

        /**
         * Returns the validation fail reason code.
         * <ul>
         *   <li>0 — transaction is valid</li>
         *   <li>100 — INVALID CARD NUMBER FOUND</li>
         *   <li>101 — ACCOUNT RECORD NOT FOUND</li>
         *   <li>102 — OVERLIMIT TRANSACTION</li>
         *   <li>103 — TRANSACTION RECEIVED AFTER ACCT EXPIRATION</li>
         * </ul>
         *
         * @return the fail reason code
         */
        public int getFailReasonCode() {
            return failReasonCode;
        }

        /**
         * Returns the human-readable fail reason description.
         *
         * @return the fail reason description, empty string if valid
         */
        public String getFailReasonDescription() {
            return failReasonDescription;
        }

        /**
         * Returns whether the transaction passed all validation checks.
         *
         * @return true if valid (failReasonCode == 0), false otherwise
         */
        public boolean isValid() {
            return valid;
        }
    }

    /**
     * Reject record containing the original daily transaction and reject details.
     *
     * <p>Maps to CBTRN02C.cbl REJECT-RECORD structure (paragraph 2500):</p>
     * <pre>
     *   01  REJECT-RECORD.
     *       05  REJECT-REASON-CODE   PIC 9(03).
     *       05  REJECT-REASON-DESC   PIC X(50).
     *       05  FILLER               PIC X(01) VALUE SPACES.
     *       05  REJECT-TRAN-DATA     PIC X(350). (copy of DALYTRAN record)
     * </pre>
     */
    public static class RejectRecord {

        private final DailyTransaction dailyTransaction;
        private final int rejectCode;
        private final String rejectDescription;

        /**
         * Constructs a new RejectRecord.
         *
         * @param dailyTransaction  the rejected daily transaction
         * @param rejectCode        the reject code (100, 101, 102, or 103)
         * @param rejectDescription the reject description text
         */
        public RejectRecord(DailyTransaction dailyTransaction, int rejectCode,
                            String rejectDescription) {
            this.dailyTransaction = dailyTransaction;
            this.rejectCode = rejectCode;
            this.rejectDescription = rejectDescription;
        }

        /**
         * Returns the original daily transaction that was rejected.
         *
         * @return the rejected DailyTransaction entity
         */
        public DailyTransaction getDailyTransaction() {
            return dailyTransaction;
        }

        /**
         * Returns the reject reason code.
         *
         * @return the reject code (100, 101, 102, or 103)
         */
        public int getRejectCode() {
            return rejectCode;
        }

        /**
         * Returns the reject reason description.
         *
         * @return the reject description string
         */
        public String getRejectDescription() {
            return rejectDescription;
        }
    }

    // ========================================================================
    // MAIN — Procedure Division main loop → processDailyTransactions()
    // ========================================================================

    /**
     * Convenience entry point that loads all daily transactions from the
     * DALYTRAN staging table via {@link DailyTransactionRepository#findAll()}
     * and delegates to {@link #processDailyTransactions(List)}.
     *
     * <p>Equivalent to CBTRN02C.cbl opening DALYTRAN-FILE and reading all records
     * in the main PERFORM UNTIL END-OF-FILE loop (paragraphs 0000 through 1000).</p>
     *
     * @return list of reject records for transactions that failed validation
     * @throws CardDemoException if a fatal error occurs during transaction posting
     */
    public List<RejectRecord> processDailyTransactions() {
        List<DailyTransaction> transactions = dailyTransactionRepository.findAll();
        logger.info("Loaded {} daily transaction records from staging table",
                transactions.size());
        return processDailyTransactions(transactions);
    }

    /**
     * Main entry point for daily transaction posting batch process.
     *
     * <p>Translates CBTRN02C.cbl PROCEDURE DIVISION main loop (lines 165-230).
     * For each DALYTRAN record:</p>
     * <ol>
     *   <li>Increment WS-TRANSACTION-COUNT</li>
     *   <li>Reset validation fields (WS-VALIDATION-FAIL-REASON = ZERO)</li>
     *   <li>PERFORM 1500-VALIDATE-TRAN</li>
     *   <li>If valid → PERFORM 2000-POST-TRANSACTION</li>
     *   <li>If invalid → increment WS-REJECT-COUNT, PERFORM 2500-WRITE-REJECT-REC</li>
     * </ol>
     *
     * <p>Return code semantics (COBOL RETURN-CODE):</p>
     * <ul>
     *   <li>0 — all transactions posted successfully (rejectCount == 0)</li>
     *   <li>4 — some transactions rejected (rejectCount &gt; 0)</li>
     * </ul>
     *
     * @param transactions list of daily transactions from DALYTRAN staging table
     * @return list of reject records for transactions that failed validation
     * @throws CardDemoException if a fatal error occurs during transaction posting
     */
    public List<RejectRecord> processDailyTransactions(List<DailyTransaction> transactions) {
        // Initialize working storage counters
        transactionCount = 0;
        rejectCount = 0;
        List<RejectRecord> rejectRecords = new ArrayList<>();

        logger.info("Starting daily transaction posting. Records to process: {}",
                transactions.size());

        // Main loop: PERFORM UNTIL END-OF-FILE = 'Y'
        for (DailyTransaction dailyTran : transactions) {
            // 1000-DALYTRAN-GET-NEXT: record already available via iterator
            transactionCount++;

            // Reset working-storage lookup state for each transaction
            currentXref = null;
            currentAccount = null;

            // 1500-VALIDATE-TRAN
            ValidationResult validationResult = validateTransaction(dailyTran);

            if (validationResult.isValid()) {
                // 2000-POST-TRANSACTION
                try {
                    postTransaction(dailyTran, currentXref, currentAccount);
                } catch (CardDemoException ex) {
                    // 9999-ABEND-PROGRAM equivalent: log diagnostics and re-throw
                    // Mirrors 9910-DISPLAY-IO-STATUS → 9999-ABEND-PROGRAM flow
                    logger.error("Fatal error posting transaction ID={}: {}",
                            dailyTran.getDalytranId(), ex.getMessage(), ex);
                    throw ex;
                }
            } else {
                // Increment reject count and write reject record
                rejectCount++;
                RejectRecord reject = writeRejectRecord(
                        dailyTran,
                        validationResult.getFailReasonCode(),
                        validationResult.getFailReasonDescription());
                rejectRecords.add(reject);
            }
        }

        // Summary logging: COBOL DISPLAY WS-TRANSACTION-COUNT / WS-REJECT-COUNT
        logger.info("Daily posting complete. Transactions processed: {}, Rejected: {}",
                transactionCount, rejectCount);

        // RETURN-CODE assignment
        if (rejectCount > 0) {
            logger.warn("RETURN-CODE = 4: {} transactions rejected out of {} processed",
                    rejectCount, transactionCount);
        } else {
            logger.info("RETURN-CODE = 0: All {} transactions posted successfully",
                    transactionCount);
        }

        return rejectRecords;
    }

    // ========================================================================
    // 1500-VALIDATE-TRAN → validateTransaction()
    // ========================================================================

    /**
     * Orchestrates multi-step validation of a daily transaction.
     *
     * <p>Translates CBTRN02C.cbl paragraph 1500-VALIDATE-TRAN (lines 310-320):</p>
     * <pre>
     *   PERFORM 1500-A-LOOKUP-XREF
     *   IF WS-VALIDATION-FAIL-REASON = ZERO
     *      PERFORM 1500-B-LOOKUP-ACCT
     *   END-IF
     * </pre>
     *
     * <p>Validation chain is sequential: XREF lookup first, then account lookup
     * with credit limit and expiration checks only if XREF passed.</p>
     *
     * @param dailyTran the daily transaction to validate
     * @return ValidationResult indicating pass (valid=true) or fail with reject code
     */
    public ValidationResult validateTransaction(DailyTransaction dailyTran) {
        // 1500-A: XREF lookup by card number
        ValidationResult xrefResult = lookupXref(dailyTran.getCardNum());
        if (!xrefResult.isValid()) {
            return xrefResult;
        }

        // 1500-B: Account lookup + credit limit + expiration check
        // Only performed if XREF lookup succeeded (WS-VALIDATION-FAIL-REASON = ZERO)
        return lookupAccount(currentXref.getAccountId(), dailyTran);
    }

    // ========================================================================
    // 1500-A-LOOKUP-XREF → lookupXref()
    // ========================================================================

    /**
     * Looks up card cross-reference record by card number.
     *
     * <p>Translates CBTRN02C.cbl paragraph 1500-A-LOOKUP-XREF (lines 325-345):</p>
     * <pre>
     *   READ XREF-FILE INTO CARD-XREF-RECORD
     *      KEY IS DALYTRAN-CARD-NUM
     *      INVALID KEY
     *         MOVE 100 TO WS-VALIDATION-FAIL-REASON
     *         MOVE 'INVALID CARD NUMBER FOUND'
     *              TO WS-VALIDATION-FAIL-REASON-DESC
     * </pre>
     *
     * <p>If the XREF is found, the record is stored in the instance field
     * {@code currentXref} for use by subsequent methods (COBOL working-storage
     * pattern).</p>
     *
     * @param cardNum the 16-character card number (DALYTRAN-CARD-NUM PIC X(16))
     * @return ValidationResult — valid if XREF found, reject code 100 if not found
     */
    public ValidationResult lookupXref(String cardNum) {
        Optional<CardXref> xref = cardXrefRepository.findByXrefCardNum(cardNum);
        if (xref.isEmpty()) {
            logger.debug("XREF not found for card number: {}", cardNum);
            return new ValidationResult(100, "INVALID CARD NUMBER FOUND", false);
        }
        // Store in working-storage for downstream use by 1500-B and 2000
        currentXref = xref.get();
        logger.debug("XREF found: card={} acct={}", cardNum, currentXref.getAccountId());
        return new ValidationResult(0, "", true);
    }

    // ========================================================================
    // 1500-B-LOOKUP-ACCT → lookupAccount()
    // ========================================================================

    /**
     * Looks up account and performs credit limit and expiration validation.
     *
     * <p>Translates CBTRN02C.cbl paragraph 1500-B-LOOKUP-ACCT (lines 350-420).
     * Performs three sequential checks:</p>
     * <ol>
     *   <li><b>Account lookup</b> — READ ACCOUNT-FILE by XREF-ACCT-ID.
     *       If INVALID KEY → reject 101 "ACCOUNT RECORD NOT FOUND"</li>
     *   <li><b>Credit limit check</b> —
     *       COMPUTE WS-TEMP-BAL = ACCT-CURR-CYC-CREDIT - ACCT-CURR-CYC-DEBIT + DALYTRAN-AMT.
     *       If ACCT-CREDIT-LIMIT &lt; WS-TEMP-BAL → reject 102 "OVERLIMIT TRANSACTION"</li>
     *   <li><b>Expiration check</b> —
     *       If ACCT-EXPIRAION-DATE &lt; DALYTRAN-ORIG-TS(1:10) → reject 103
     *       "TRANSACTION RECEIVED AFTER ACCT EXPIRATION"</li>
     * </ol>
     *
     * <p><b>CRITICAL:</b> Both checks (102 and 103) run sequentially. If both fail,
     * reject 103 OVERWRITES reject 102 — the last failing check wins. This matches
     * the COBOL sequential MOVE semantics exactly.</p>
     *
     * @param acctId    the account ID from XREF-ACCT-ID (11 characters)
     * @param dailyTran the daily transaction being validated
     * @return ValidationResult with reject code 101, 102, or 103 if failed; valid if all pass
     */
    public ValidationResult lookupAccount(String acctId, DailyTransaction dailyTran) {
        // Account lookup: READ ACCOUNT-FILE INTO ACCOUNT-RECORD KEY IS XREF-ACCT-ID
        Optional<Account> acctOpt = accountRepository.findById(acctId);
        if (acctOpt.isEmpty()) {
            logger.debug("Account not found: {}", acctId);
            return new ValidationResult(101, "ACCOUNT RECORD NOT FOUND", false);
        }

        Account acct = acctOpt.get();
        // Store in working-storage for downstream use by 2000/2800
        currentAccount = acct;

        // Initialize validation state: MOVE ZERO TO WS-VALIDATION-FAIL-REASON
        int failReason = 0;
        String failDesc = "";

        // ---- Credit limit check (reject 102) ----
        // COMPUTE WS-TEMP-BAL = ACCT-CURR-CYC-CREDIT
        //                     - ACCT-CURR-CYC-DEBIT
        //                     + DALYTRAN-AMT
        BigDecimal tempBal = acct.getCurrCycCredit()
                .subtract(acct.getCurrCycDebit())
                .add(dailyTran.getAmount());

        // IF ACCT-CREDIT-LIMIT >= WS-TEMP-BAL → pass; ELSE → reject 102
        if (acct.getCreditLimit().compareTo(tempBal) < 0) {
            failReason = 102;
            failDesc = "OVERLIMIT TRANSACTION";
        }

        // ---- Expiration check (reject 103) — may overwrite 102 ----
        // IF ACCT-EXPIRAION-DATE >= DALYTRAN-ORIG-TS(1:10) → pass; ELSE → reject 103
        // DALYTRAN-ORIG-TS(1:10) extracts the first 10 characters (date portion: YYYY-MM-DD)
        String acctExpDate = acct.getExpirationDate();
        String tranTimestamp = dailyTran.getOrigTimestamp();
        String tranDate = "";
        if (tranTimestamp != null && tranTimestamp.length() >= 10) {
            tranDate = tranTimestamp.substring(0, 10);
        }
        if (acctExpDate != null && !tranDate.isEmpty()
                && acctExpDate.compareTo(tranDate) < 0) {
            // Reject 103 overwrites 102 if both fail (COBOL sequential MOVE)
            failReason = 103;
            failDesc = "TRANSACTION RECEIVED AFTER ACCT EXPIRATION";
        }

        // Return result
        if (failReason != 0) {
            logger.debug("Validation failed for account {}: code={} desc={}",
                    acctId, failReason, failDesc);
            return new ValidationResult(failReason, failDesc, false);
        }

        return new ValidationResult(0, "", true);
    }

    // ========================================================================
    // 2000-POST-TRANSACTION → postTransaction()
    // ========================================================================

    /**
     * Posts a validated daily transaction to the transaction master file.
     *
     * <p>Translates CBTRN02C.cbl paragraph 2000-POST-TRANSACTION (lines 430-480).
     * Maps all DALYTRAN fields to TRAN record fields, generates a DB2-FORMAT-TS
     * for the processing timestamp, then calls three sub-paragraphs:</p>
     * <ol>
     *   <li>2700-UPDATE-TCATBAL — update or create category balance record</li>
     *   <li>2800-UPDATE-ACCOUNT-REC — update account current balance and cycle amounts</li>
     *   <li>2900-WRITE-TRANSACTION-FILE — write new transaction record</li>
     * </ol>
     *
     * <p>Field mapping (DALYTRAN → TRAN):</p>
     * <pre>
     *   DALYTRAN-ID            → TRAN-ID
     *   DALYTRAN-TYPE-CD       → TRAN-TYPE-CD
     *   DALYTRAN-CAT-CD        → TRAN-CAT-CD
     *   DALYTRAN-SOURCE        → TRAN-SOURCE
     *   DALYTRAN-DESC          → TRAN-DESC
     *   DALYTRAN-AMT           → TRAN-AMT
     *   DALYTRAN-MERCHANT-ID   → TRAN-MERCHANT-ID
     *   DALYTRAN-MERCHANT-NAME → TRAN-MERCHANT-NAME
     *   DALYTRAN-MERCHANT-CITY → TRAN-MERCHANT-CITY
     *   DALYTRAN-MERCHANT-ZIP  → TRAN-MERCHANT-ZIP
     *   DALYTRAN-CARD-NUM      → TRAN-CARD-NUM
     *   DALYTRAN-ORIG-TS       → TRAN-ORIG-TS
     *   DB2-FORMAT-TS          → TRAN-PROC-TS (current timestamp)
     * </pre>
     *
     * <p>Annotated with {@code @Transactional} to ensure atomicity of the
     * multi-table update (Transaction INSERT + CategoryBalance UPDATE/INSERT +
     * Account UPDATE). Each DALYTRAN posting is atomic — mirrors the COBOL pattern
     * where WRITE/REWRITE failures in paragraphs 2700-2900 trigger ABEND.</p>
     *
     * @param dailyTran the validated daily transaction
     * @param xref      the card cross-reference record from 1500-A lookup
     * @param account   the account record from 1500-B lookup
     * @throws FileStatusException if any database write/update operation fails
     * @throws CardDemoException   if a fatal unrecoverable error occurs
     */
    @Transactional
    public void postTransaction(DailyTransaction dailyTran, CardXref xref,
                                Account account) {
        // Z-GET-DB2-FORMAT-TIMESTAMP → TRAN-PROC-TS
        String procTimestamp = generateDb2Timestamp();

        // Map DALYTRAN fields to TRAN record (MOVE statements in COBOL).
        // Uses the all-args public constructor (protected no-arg is JPA-only).
        // Field mapping:
        //   DALYTRAN-ID            → tranId
        //   DALYTRAN-TYPE-CD       → typeCode
        //   DALYTRAN-CAT-CD        → categoryCode
        //   DALYTRAN-SOURCE        → source
        //   DALYTRAN-DESC          → description
        //   DALYTRAN-AMT           → amount
        //   DALYTRAN-MERCHANT-ID   → merchantId
        //   DALYTRAN-MERCHANT-NAME → merchantName
        //   DALYTRAN-MERCHANT-CITY → merchantCity
        //   DALYTRAN-MERCHANT-ZIP  → merchantZip
        //   DALYTRAN-CARD-NUM      → cardNum
        //   DALYTRAN-ORIG-TS       → origTimestamp
        //   DB2-FORMAT-TS          → procTimestamp (current timestamp)
        Transaction tran = new Transaction(
                dailyTran.getDalytranId(),
                dailyTran.getTypeCode(),
                dailyTran.getCategoryCode(),
                dailyTran.getSource(),
                dailyTran.getDescription(),
                dailyTran.getAmount(),
                dailyTran.getMerchantId(),
                dailyTran.getMerchantName(),
                dailyTran.getMerchantCity(),
                dailyTran.getMerchantZip(),
                dailyTran.getCardNum(),
                dailyTran.getOrigTimestamp(),
                procTimestamp
        );

        // 2700-UPDATE-TCATBAL — update or create category balance
        updateTcatbal(dailyTran, account);

        // 2800-UPDATE-ACCOUNT-REC — update account cycle amounts and balance
        updateAccountRecord(account, dailyTran);

        // 2900-WRITE-TRANSACTION-FILE — write new transaction record
        writeTransactionRecord(tran);

        logger.debug("Posted transaction: ID={} Card={} Amount={}",
                dailyTran.getDalytranId(), dailyTran.getCardNum(),
                dailyTran.getAmount());
    }

    // ========================================================================
    // 2500-WRITE-REJECT-REC → writeRejectRecord()
    // ========================================================================

    /**
     * Creates a reject record for a daily transaction that failed validation.
     *
     * <p>Translates CBTRN02C.cbl paragraph 2500-WRITE-REJECT-REC (lines 485-500).
     * In COBOL, this writes to the DALYREJS sequential output file. In Java, the
     * reject record is collected in a list and returned to the caller for
     * downstream processing (e.g., by {@code RejectFileWriter} in the Spring Batch
     * job).</p>
     *
     * @param dailyTran   the rejected daily transaction
     * @param rejectCode  the reject code (100, 101, 102, or 103)
     * @param rejectDesc  the reject description text
     * @return the created RejectRecord containing original transaction and reject details
     */
    public RejectRecord writeRejectRecord(DailyTransaction dailyTran, int rejectCode,
                                          String rejectDesc) {
        logger.warn("Transaction rejected: ID={} Card={} Code={} Reason={}",
                dailyTran.getDalytranId(), dailyTran.getCardNum(),
                rejectCode, rejectDesc);
        return new RejectRecord(dailyTran, rejectCode, rejectDesc);
    }

    // ========================================================================
    // 2700-UPDATE-TCATBAL → updateTcatbal()
    // ========================================================================

    /**
     * Updates or creates a transaction category balance record.
     *
     * <p>Translates CBTRN02C.cbl paragraph 2700-UPDATE-TCATBAL (lines 505-540).
     * Reads TCATBAL by composite key (ACCT-ID + TYPE-CD + CAT-CD):</p>
     * <ul>
     *   <li>If found (FILE STATUS '00') → 2700-B: ADD DALYTRAN-AMT TO TRAN-CAT-BAL, REWRITE</li>
     *   <li>If not found (INVALID KEY / STATUS '23') → WS-CREATE-TRANCAT-REC = 'Y' →
     *       2700-A: create new record with balance = DALYTRAN-AMT</li>
     * </ul>
     *
     * @param dailyTran the daily transaction providing amount, type code, and category code
     * @param account   the account record providing the account ID for the composite key
     * @throws FileStatusException if the database operation fails unexpectedly
     */
    public void updateTcatbal(DailyTransaction dailyTran, Account account) {
        String accountId = account.getAcctId();
        String typeCode = dailyTran.getTypeCode();
        Integer categoryCode = dailyTran.getCategoryCode();

        // READ TCATBAL-FILE by composite key
        Optional<CategoryBalance> existing;
        try {
            existing = categoryBalanceRepository
                    .findByAccountIdAndTypeCodeAndCategoryCode(
                            accountId, typeCode, categoryCode);
        } catch (RuntimeException ex) {
            // 9910-DISPLAY-IO-STATUS → 9999-ABEND-PROGRAM
            // COBOL checks file status after every I/O; unexpected status triggers ABEND
            logger.error("Error reading TCATBAL record: acct={} type={} cat={}",
                    accountId, typeCode, categoryCode, ex);
            throw new FileStatusException("99",
                    "Error reading TCATBAL record for account " + accountId, ex);
        }

        if (existing.isPresent()) {
            // 2700-B: UPDATE existing TCATBAL record
            updateTcatbalRecord(existing.get(), dailyTran.getAmount());
        } else {
            // 2700-A: CREATE new TCATBAL record (WS-CREATE-TRANCAT-REC = 'Y')
            createTcatbalRecord(dailyTran, accountId);
        }
    }

    // ========================================================================
    // 2700-A-CREATE-TCATBAL-REC → createTcatbalRecord()
    // ========================================================================

    /**
     * Creates a new transaction category balance record.
     *
     * <p>Translates CBTRN02C.cbl paragraph 2700-A-CREATE-TCATBAL-REC (lines 545-575):</p>
     * <pre>
     *   INITIALIZE FD-TRAN-CAT-RECORD
     *   MOVE XREF-ACCT-ID        TO FD-TRANCAT-ACCT-ID
     *   MOVE DALYTRAN-TYPE-CD     TO FD-TRANCAT-TYPE-CD
     *   MOVE DALYTRAN-CAT-CD      TO FD-TRANCAT-CD
     *   ADD DALYTRAN-AMT          TO TRAN-CAT-BAL
     *   WRITE FD-TRAN-CAT-RECORD
     * </pre>
     *
     * <p>Note: INITIALIZE sets TRAN-CAT-BAL to zero, then ADD sets it to DALYTRAN-AMT.</p>
     *
     * @param dailyTran the daily transaction providing type code, category code, and amount
     * @param accountId the account ID from the XREF lookup (XREF-ACCT-ID)
     * @throws FileStatusException if the WRITE operation fails (non-zero file status)
     */
    public void createTcatbalRecord(DailyTransaction dailyTran, String accountId) {
        // Uses the all-args public constructor (protected no-arg is JPA-only).
        // INITIALIZE sets balance to zero, then ADD DALYTRAN-AMT → balance = amount.
        // Constructor equivalent:
        //   MOVE XREF-ACCT-ID        TO FD-TRANCAT-ACCT-ID   → accountId
        //   MOVE DALYTRAN-TYPE-CD     TO FD-TRANCAT-TYPE-CD   → typeCode
        //   MOVE DALYTRAN-CAT-CD      TO FD-TRANCAT-CD        → categoryCode
        //   ADD DALYTRAN-AMT          TO TRAN-CAT-BAL         → balance = amount
        CategoryBalance catBal = new CategoryBalance(
                accountId,
                dailyTran.getTypeCode(),
                dailyTran.getCategoryCode(),
                dailyTran.getAmount()
        );

        try {
            categoryBalanceRepository.save(catBal);
            logger.debug("Created TCATBAL: acct={} type={} cat={} bal={}",
                    accountId, dailyTran.getTypeCode(),
                    dailyTran.getCategoryCode(), dailyTran.getAmount());
        } catch (RuntimeException ex) {
            // 9910-DISPLAY-IO-STATUS → 9999-ABEND-PROGRAM
            logger.error("Error writing TCATBAL record: acct={} type={} cat={}",
                    accountId, dailyTran.getTypeCode(),
                    dailyTran.getCategoryCode(), ex);
            throw new FileStatusException("99",
                    "Error writing TCATBAL record for account " + accountId, ex);
        }
    }

    // ========================================================================
    // 2700-B-UPDATE-TCATBAL-REC → updateTcatbalRecord()
    // ========================================================================

    /**
     * Updates an existing transaction category balance record by adding the
     * transaction amount to the current balance.
     *
     * <p>Translates CBTRN02C.cbl paragraph 2700-B-UPDATE-TCATBAL-REC (lines 580-600):</p>
     * <pre>
     *   ADD DALYTRAN-AMT TO TRAN-CAT-BAL
     *   REWRITE FD-TRAN-CAT-RECORD
     * </pre>
     *
     * <p>Uses {@link BigDecimal#add(BigDecimal)} for exact decimal arithmetic
     * matching COBOL COMP-3 ADD semantics with no floating-point imprecision.</p>
     *
     * @param catBal the existing category balance record to update
     * @param amount the amount to add to the balance (DALYTRAN-AMT)
     * @throws FileStatusException if the REWRITE operation fails (non-zero file status)
     */
    public void updateTcatbalRecord(CategoryBalance catBal, BigDecimal amount) {
        BigDecimal newBalance = catBal.getBalance().add(amount);
        catBal.setBalance(newBalance);

        try {
            categoryBalanceRepository.save(catBal);
            logger.debug("Updated TCATBAL: acct={} type={} cat={} newBal={}",
                    catBal.getAccountId(), catBal.getTypeCode(),
                    catBal.getCategoryCode(), newBalance);
        } catch (RuntimeException ex) {
            // 9910-DISPLAY-IO-STATUS → 9999-ABEND-PROGRAM
            logger.error("Error rewriting TCATBAL record: acct={} type={} cat={}",
                    catBal.getAccountId(), catBal.getTypeCode(),
                    catBal.getCategoryCode(), ex);
            throw new FileStatusException("99",
                    "Error rewriting TCATBAL record for account "
                            + catBal.getAccountId(), ex);
        }
    }

    // ========================================================================
    // 2800-UPDATE-ACCOUNT-REC → updateAccountRecord()
    // ========================================================================

    /**
     * Updates the account record with the posted transaction amount.
     *
     * <p>Translates CBTRN02C.cbl paragraph 2800-UPDATE-ACCOUNT-REC (lines 605-640):</p>
     * <pre>
     *   ADD DALYTRAN-AMT TO ACCT-CURR-BAL
     *   IF DALYTRAN-AMT &gt;= 0
     *      ADD DALYTRAN-AMT TO ACCT-CURR-CYC-CREDIT
     *   ELSE
     *      ADD DALYTRAN-AMT TO ACCT-CURR-CYC-DEBIT
     *   END-IF
     *   REWRITE ACCOUNT-RECORD
     * </pre>
     *
     * <p><b>COBOL ADD semantics:</b> COBOL ADD adds the signed amount. For negative
     * amounts (debits), ADD to ACCT-CURR-CYC-DEBIT makes the debit field more
     * negative (larger in absolute terms). BigDecimal.add() with a negative value
     * achieves the same result.</p>
     *
     * <p>The account entity uses {@code @Version} for optimistic locking, mapping
     * the COBOL READ UPDATE → REWRITE pattern. If a concurrent update occurs,
     * JPA throws an OptimisticLockException.</p>
     *
     * @param account   the account record to update (ACCOUNT-RECORD)
     * @param dailyTran the daily transaction providing the amount (DALYTRAN-AMT)
     * @throws FileStatusException if the REWRITE operation fails (non-zero file status)
     */
    public void updateAccountRecord(Account account, DailyTransaction dailyTran) {
        BigDecimal amount = dailyTran.getAmount();

        // ADD DALYTRAN-AMT TO ACCT-CURR-BAL
        account.setCurrBal(account.getCurrBal().add(amount));

        // Classify debit vs credit and update appropriate cycle accumulator
        if (amount.compareTo(BigDecimal.ZERO) >= 0) {
            // Credit: ADD DALYTRAN-AMT TO ACCT-CURR-CYC-CREDIT
            account.setCurrCycCredit(account.getCurrCycCredit().add(amount));
        } else {
            // Debit: ADD DALYTRAN-AMT TO ACCT-CURR-CYC-DEBIT
            account.setCurrCycDebit(account.getCurrCycDebit().add(amount));
        }

        try {
            accountRepository.save(account);
            logger.debug("Updated account {}: newBal={} cycCredit={} cycDebit={}",
                    account.getAcctId(), account.getCurrBal(),
                    account.getCurrCycCredit(), account.getCurrCycDebit());
        } catch (RuntimeException ex) {
            // 9910-DISPLAY-IO-STATUS → 9999-ABEND-PROGRAM
            logger.error("Error rewriting ACCOUNT record: acct={}",
                    account.getAcctId(), ex);
            throw new FileStatusException("99",
                    "Error rewriting ACCOUNT record for " + account.getAcctId(), ex);
        }
    }

    // ========================================================================
    // 2900-WRITE-TRANSACTION-FILE → writeTransactionRecord()
    // ========================================================================

    /**
     * Writes a new transaction record to the TRANSACT master file.
     *
     * <p>Translates CBTRN02C.cbl paragraph 2900-WRITE-TRANSACTION-FILE (lines 645-660):</p>
     * <pre>
     *   WRITE FD-TRANFILE-REC FROM TRAN-RECORD
     *   IF TRANFILE-STATUS NOT = '00'
     *      PERFORM 9910-DISPLAY-IO-STATUS
     *      PERFORM 9999-ABEND-PROGRAM
     *   END-IF
     * </pre>
     *
     * @param tran the Transaction entity to persist
     * @throws FileStatusException if the WRITE operation fails (non-zero file status)
     */
    public void writeTransactionRecord(Transaction tran) {
        try {
            transactionRepository.save(tran);
            logger.debug("Wrote transaction: ID={} Card={} Amount={}",
                    tran.getTranId(), tran.getCardNum(), tran.getAmount());
        } catch (RuntimeException ex) {
            // 9910-DISPLAY-IO-STATUS → 9999-ABEND-PROGRAM
            logger.error("Error writing TRANSACTION record: ID={}",
                    tran.getTranId(), ex);
            throw new FileStatusException("99",
                    "Error writing TRANSACTION record " + tran.getTranId(), ex);
        }
    }

    // ========================================================================
    // Z-GET-DB2-FORMAT-TIMESTAMP → generateDb2Timestamp()
    // ========================================================================

    /**
     * Generates a DB2-format timestamp from the current date/time.
     *
     * <p>Translates CBTRN02C.cbl paragraph Z-GET-DB2-FORMAT-TIMESTAMP (lines 680-720).
     * COBOL implementation:</p>
     * <pre>
     *   ACCEPT WS-TIMESTAMP FROM TIME
     *   MOVE WS-TIMESTAMP-DD TO DB2-FORMAT-DD
     *   ... (field-by-field formatting)
     *   → Result: YYYY-MM-DD-HH.MM.SS.mmmmmm (26 characters)
     * </pre>
     *
     * <p>Uses {@link DateConversionUtil#getCurrentTimestamp()} which returns the
     * 26-character ISO-8601 extended timestamp in DB2 format with dashes and dots
     * (not colons), matching the COBOL TRAN-ORIG-TS / TRAN-PROC-TS field format
     * per AAP section 0.7.4.</p>
     *
     * @return current timestamp in DB2 format: YYYY-MM-DD-HH.MM.SS.mmmmmm (26 chars)
     */
    public String generateDb2Timestamp() {
        return DateConversionUtil.getCurrentTimestamp();
    }

    // ========================================================================
    // Working Storage Accessors
    // ========================================================================

    /**
     * Returns the total count of transactions processed in the last batch run.
     *
     * <p>Maps to CBTRN02C.cbl WS-TRANSACTION-COUNT PIC 9(09).</p>
     *
     * @return the number of transactions processed (includes both posted and rejected)
     */
    public int getTransactionCount() {
        return transactionCount;
    }

    /**
     * Returns the total count of transactions rejected in the last batch run.
     *
     * <p>Maps to CBTRN02C.cbl WS-REJECT-COUNT PIC 9(09).</p>
     *
     * @return the number of rejected transactions
     */
    public int getRejectCount() {
        return rejectCount;
    }
}
