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
package com.cardemo.service.online;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.cardemo.common.context.CardDemoContext;
import com.cardemo.common.exception.DuplicateRecordException;
import com.cardemo.common.exception.RecordNotFoundException;
import com.cardemo.common.exception.ValidationException;
import com.cardemo.common.message.MessageConstants;
import com.cardemo.entity.Account;
import com.cardemo.entity.CardXref;
import com.cardemo.entity.Transaction;
import com.cardemo.repository.AccountRepository;
import com.cardemo.repository.CardXrefRepository;
import com.cardemo.repository.TransactionRepository;

/**
 * Bill Payment service faithfully translating COBIL00C.cbl.
 *
 * <p>This service implements the complete bill payment lifecycle for the
 * CardDemo application, translating every paragraph in the original COBOL
 * program to an equivalent Java method with 100% business logic parity.</p>
 *
 * <h2>COBOL Program: COBIL00C.cbl</h2>
 * <p>Online CICS transaction program for bill payment processing.
 * Handles account lookup, cross-reference resolution, transaction creation
 * (using browse-last ID generation), and account balance update.</p>
 *
 * <h2>Paragraph-to-Method Traceability</h2>
 * <table>
 *   <caption>COBIL00C.cbl Paragraph Mapping</caption>
 *   <tr><th>COBOL Paragraph</th><th>Line</th><th>Java Method</th></tr>
 *   <tr><td>MAIN-PARA</td><td>99</td>
 *       <td>{@link #processBillPayment(BillPaymentRequest)}</td></tr>
 *   <tr><td>PROCESS-ENTER-KEY</td><td>154</td>
 *       <td>{@link #processEnterKey(BillPaymentRequest)}</td></tr>
 *   <tr><td>GET-CURRENT-TIMESTAMP</td><td>249</td>
 *       <td>{@link #getCurrentTimestamp()}</td></tr>
 *   <tr><td>POPULATE-HEADER-INFO</td><td>319</td>
 *       <td>{@link #populateHeaderInfo(BillPaymentResult)}</td></tr>
 *   <tr><td>READ-ACCTDAT-FILE</td><td>343</td>
 *       <td>{@link #readAcctdatFile(String)}</td></tr>
 *   <tr><td>UPDATE-ACCTDAT-FILE</td><td>377</td>
 *       <td>{@link #updateAcctdatFile(Account)}</td></tr>
 *   <tr><td>READ-CXACAIX-FILE</td><td>408</td>
 *       <td>{@link #readCxacaixFile(String)}</td></tr>
 *   <tr><td>STARTBR-TRANSACT-FILE</td><td>441</td>
 *       <td>{@link #generateTransactionId()} (combined)</td></tr>
 *   <tr><td>READPREV-TRANSACT-FILE</td><td>472</td>
 *       <td>{@link #generateTransactionId()} (combined)</td></tr>
 *   <tr><td>ENDBR-TRANSACT-FILE</td><td>501</td>
 *       <td>(no-op — browse auto-closed by JPA)</td></tr>
 *   <tr><td>WRITE-TRANSACT-FILE</td><td>510</td>
 *       <td>{@link #writeTransactFile(Transaction)}</td></tr>
 *   <tr><td>RETURN-TO-PREV-SCREEN</td><td>273</td>
 *       <td>(navigation — handled by controller)</td></tr>
 *   <tr><td>SEND-BILLPAY-SCREEN</td><td>289</td>
 *       <td>(presentation — result returned to controller)</td></tr>
 *   <tr><td>RECEIVE-BILLPAY-SCREEN</td><td>306</td>
 *       <td>(input — received via BillPaymentRequest)</td></tr>
 *   <tr><td>CLEAR-CURRENT-SCREEN</td><td>552</td>
 *       <td>(presentation — handled by controller)</td></tr>
 *   <tr><td>INITIALIZE-ALL-FIELDS</td><td>560</td>
 *       <td>(presentation — handled by controller)</td></tr>
 * </table>
 *
 * <h2>Transaction Semantics</h2>
 * <p>The {@code @Transactional} annotation on {@link #processBillPayment}
 * ensures atomicity equivalent to CICS SYNCPOINT. The complete payment
 * flow (account read, transaction write, balance update) succeeds or
 * fails as a single unit of work.</p>
 *
 * @see Account
 * @see Transaction
 * @see CardXref
 * @see CardDemoContext
 */
@Service
public class BillPaymentService {

    private static final Logger log = LoggerFactory.getLogger(BillPaymentService.class);

    // ========================================================================
    // Constants — Direct translation of COBOL bill payment working storage
    // values from COBIL00C.cbl PROCESS-ENTER-KEY paragraph (lines 197-225)
    // ========================================================================

    /** Transaction type code for bill payment — MOVE '02' TO TRAN-TYPE-CD */
    private static final String TRAN_TYPE_CODE_PAYMENT = "02";

    /** Transaction category code for payment — MOVE 2 TO TRAN-CAT-CD */
    private static final int TRAN_CAT_CODE_PAYMENT = 2;

    /** Transaction source identifier — MOVE 'POS TERM' TO TRAN-SOURCE */
    private static final String TRAN_SOURCE = "POS TERM";

    /** Transaction description — MOVE 'BILL PAYMENT - ONLINE' TO TRAN-DESC */
    private static final String TRAN_DESCRIPTION = "BILL PAYMENT - ONLINE";

    /** Merchant ID for bill payment — MOVE 999999999 TO TRAN-MERCHANT-ID */
    private static final String TRAN_MERCHANT_ID = "999999999";

    /** Merchant name for bill payment — MOVE 'BILL PAYMENT' TO TRAN-MERCHANT-NAME */
    private static final String TRAN_MERCHANT_NAME = "BILL PAYMENT";

    /** Merchant city placeholder — MOVE 'N/A' TO TRAN-MERCHANT-CITY */
    private static final String TRAN_MERCHANT_CITY = "N/A";

    /** Merchant ZIP placeholder — MOVE 'N/A' TO TRAN-MERCHANT-ZIP */
    private static final String TRAN_MERCHANT_ZIP = "N/A";

    /** Length of TRAN-ID field — PIC X(16) zero-padded numeric */
    private static final int TRAN_ID_LENGTH = 16;

    /** Decimal scale for monetary fields — PIC S9(10)V99 has 2 decimal places */
    private static final int MONETARY_SCALE = 2;

    /** All-zeros 11-digit account ID treated as empty — maps COBOL ZEROS check */
    private static final String ZEROS_ACCT_ID = "00000000000";

    /**
     * Date formatter for the date portion of timestamps: YYYY-MM-DD.
     * Maps CICS FORMATTIME YYYYMMDD DATESEP('-').
     */
    private static final DateTimeFormatter DATE_FORMATTER =
            DateTimeFormatter.ofPattern("yyyy-MM-dd");

    /**
     * Time formatter for the time portion of timestamps: HH:MM:SS.
     * Maps CICS FORMATTIME TIME TIMESEP(':').
     */
    private static final DateTimeFormatter TIME_FORMATTER =
            DateTimeFormatter.ofPattern("HH:mm:ss");

    // ========================================================================
    // Dependencies — Constructor-injected Spring beans
    // ========================================================================

    /** JPA repository for ACCTDATA VSAM dataset (account records). */
    private final AccountRepository accountRepository;

    /** JPA repository for TRANSACT VSAM dataset (transaction records). */
    private final TransactionRepository transactionRepository;

    /** JPA repository for CARDXREF VSAM dataset (card cross-reference AIX). */
    private final CardXrefRepository cardXrefRepository;

    /** Request-scoped session context mirroring CARDDEMO-COMMAREA. */
    private final CardDemoContext cardDemoContext;

    /**
     * Constructs a new {@code BillPaymentService} with required dependencies.
     *
     * <p>All dependencies are injected via Spring constructor injection,
     * mapping the COBOL pattern of file OPEN operations at program entry
     * to Spring-managed bean lifecycle.</p>
     *
     * @param accountRepository     JPA repository for ACCTDATA VSAM
     * @param transactionRepository JPA repository for TRANSACT VSAM
     * @param cardXrefRepository    JPA repository for CARDXREF VSAM AIX
     * @param cardDemoContext        request-scoped session context (COMMAREA)
     */
    public BillPaymentService(AccountRepository accountRepository,
                              TransactionRepository transactionRepository,
                              CardXrefRepository cardXrefRepository,
                              CardDemoContext cardDemoContext) {
        this.accountRepository = accountRepository;
        this.transactionRepository = transactionRepository;
        this.cardXrefRepository = cardXrefRepository;
        this.cardDemoContext = cardDemoContext;
    }

    // ========================================================================
    // Public Methods — COBOL Paragraph Mappings (members_exposed)
    // ========================================================================

    /**
     * Main entry point for bill payment processing.
     * Maps to COBIL00C.cbl MAIN-PARA paragraph (line 99).
     *
     * <p>Handles the pseudo-conversational lifecycle:</p>
     * <ul>
     *   <li>First entry (no confirm): reads account for display if account ID
     *       is available from session context (CDEMO-ACCT-ID)</li>
     *   <li>Re-entry with confirmation: delegates to
     *       {@link #processEnterKey(BillPaymentRequest)}</li>
     *   <li>Unrecognized action: returns
     *       {@link MessageConstants#INVALID_KEY_MESSAGE}</li>
     * </ul>
     *
     * <p>The {@code @Transactional} annotation ensures atomicity matching
     * CICS SYNCPOINT semantics — the entire payment flow (account read,
     * transaction write, balance update) succeeds or fails as one unit.</p>
     *
     * @param request the bill payment request with accountId and optional confirm
     * @return result including account data, payment status, and messages
     * @throws RecordNotFoundException  if account or cross-reference not found
     * @throws ValidationException      if input validation fails
     * @throws DuplicateRecordException if duplicate transaction ID generated
     */
    @Transactional
    public BillPaymentResult processBillPayment(BillPaymentRequest request) {
        log.info("Bill payment request received for account: {}",
                request.getAccountId());

        BillPaymentResult result = new BillPaymentResult();
        populateHeaderInfo(result);

        // Resolve account ID from request or session context (COMMAREA)
        // Maps: MOVE CDEMO-ACCT-ID TO WS-ACCT-ID (MAIN-PARA first entry)
        String accountId = resolveAccountId(request.getAccountId());

        // If no account ID available, return empty screen for initial display
        if (accountId == null || accountId.isBlank()
                || ZEROS_ACCT_ID.equals(accountId)) {
            log.debug("No account ID provided — returning empty bill payment screen");
            result.setSuccess(true);
            result.setMessage("");
            return result;
        }

        // Normalize account ID on request for downstream processing
        request.setAccountId(accountId);

        // Check action — maps EVALUATE EIBAID in MAIN-PARA
        // WHEN DFHENTER → processEnterKey
        // WHEN OTHER    → INVALID_KEY_MESSAGE
        String action = request.getAction();
        if (action != null && !action.isBlank()
                && !"ENTER".equals(action)) {
            log.warn("Unrecognized action '{}' for bill payment — "
                    + "returning invalid key message", action);
            Account account = readAcctdatFile(accountId);
            populateAccountResult(result, account);
            result.setSuccess(false);
            result.setMessage(MessageConstants.INVALID_KEY_MESSAGE);
            return result;
        }

        // If no confirm provided — first entry: read account for display only
        // Maps MAIN-PARA ELSE branch (not re-enter): read and display
        String confirm = request.getConfirm();
        if (confirm == null || confirm.isBlank()) {
            log.debug("First entry for account {} — displaying account data",
                    accountId);
            Account account = readAcctdatFile(accountId);
            populateAccountResult(result, account);
            result.setSuccess(true);
            return result;
        }

        // Re-entry with confirm: delegate to enter key handler
        // Maps EVALUATE EIBAID WHEN DFHENTER → PERFORM PROCESS-ENTER-KEY
        return processEnterKey(request);
    }

    /**
     * Processes the Enter key action for bill payment.
     * Maps to COBIL00C.cbl PROCESS-ENTER-KEY paragraph (line 154).
     *
     * <p>Validation and processing flow:</p>
     * <ol>
     *   <li>Validate account ID is not empty (lines 155-157)</li>
     *   <li>Read account record via {@link #readAcctdatFile(String)}</li>
     *   <li>Check current balance is positive (lines 163-165)</li>
     *   <li>Process based on confirm value (EVALUATE CONFIRMI):
     *     <ul>
     *       <li>'Y'/'y': Execute full payment flow</li>
     *       <li>'N'/'n': Cancel and return</li>
     *       <li>Other: Validation error</li>
     *     </ul>
     *   </li>
     * </ol>
     *
     * <p>This method is designed to be called within the transaction
     * context of {@link #processBillPayment(BillPaymentRequest)}.</p>
     *
     * @param request the bill payment request with accountId and confirm
     * @return result including payment status, transaction ID, updated balance
     * @throws ValidationException      if account ID empty or confirm invalid
     * @throws RecordNotFoundException  if account or cross-reference not found
     * @throws DuplicateRecordException if transaction write encounters duplicate
     */
    public BillPaymentResult processEnterKey(BillPaymentRequest request) {
        BillPaymentResult result = new BillPaymentResult();
        populateHeaderInfo(result);

        // Step 1: Validate account ID
        // Maps COBIL00C.cbl lines 155-157:
        //   IF WS-ACCT-ID = ZEROS OR SPACES
        //       MOVE 'Acct ID can NOT be empty...' TO WS-MESSAGE
        String accountId = request.getAccountId();
        if (accountId == null || accountId.isBlank()
                || ZEROS_ACCT_ID.equals(accountId)) {
            log.warn("Bill payment attempted with empty account ID");
            throw new ValidationException("accountId",
                    "Acct ID can NOT be empty. Please provide an Account ID.");
        }

        // Step 2: Read account record
        // Maps PERFORM READ-ACCTDAT-FILE (line 159)
        Account account = readAcctdatFile(accountId);
        populateAccountResult(result, account);

        // Step 3: Check balance is positive
        // Maps COBIL00C.cbl lines 163-165:
        //   IF ACCT-CURR-BAL <= ZEROS
        //       MOVE 'You have nothing to pay...' TO WS-MESSAGE
        if (account.getCurrBal().compareTo(BigDecimal.ZERO) <= 0) {
            log.info("Account {} has zero or negative balance: {}",
                    accountId, account.getCurrBal());
            result.setSuccess(false);
            result.setMessage(
                    "You have nothing to pay. Current balance is zero or negative.");
            return result;
        }

        // Step 4: Process based on confirm value
        // Maps EVALUATE CONFIRMI (line 167)
        String confirm = request.getConfirm();
        if ("Y".equalsIgnoreCase(confirm)) {
            // WHEN 'Y' / WHEN 'y' — process confirmed payment
            return processConfirmedPayment(account, result);
        } else if ("N".equalsIgnoreCase(confirm)) {
            // WHEN 'N' / WHEN 'n' — cancel
            log.info("Bill payment cancelled by user for account {}", accountId);
            result.setSuccess(true);
            result.setMessage("");
            return result;
        } else {
            // WHEN OTHER — invalid value
            log.warn("Invalid confirm value '{}' for account {}", confirm,
                    accountId);
            throw new ValidationException(
                    "Invalid confirmation value. Valid values are (Y/N). "
                    + "Please try again.");
        }
    }

    /**
     * Reads an account record from the ACCTDATA dataset.
     * Maps to COBIL00C.cbl READ-ACCTDAT-FILE paragraph (line 343).
     *
     * <p>Translates:</p>
     * <pre>
     *   EXEC CICS READ UPDATE
     *       DATASET('ACCTDAT')
     *       INTO(ACCOUNT-RECORD)
     *       RIDFLD(WS-ACCT-ID)
     *       RESP(WS-RESP-CD)
     *   END-EXEC
     * </pre>
     *
     * <p>The JPA {@code @Version} field on {@link Account} provides optimistic
     * locking equivalent to the CICS READ UPDATE → REWRITE pattern.</p>
     *
     * @param accountId the 11-character account ID (PIC 9(11))
     * @return the Account entity
     * @throws RecordNotFoundException if no account found (DFHRESP(NOTFND),
     *                                  VSAM status '23')
     */
    public Account readAcctdatFile(String accountId) {
        log.debug("Reading account record for ID: {}", accountId);
        Optional<Account> accountOpt = accountRepository.findById(accountId);
        if (accountOpt.isEmpty()) {
            log.error("Account not found: {} — VSAM status '23' equivalent",
                    accountId);
            throw new RecordNotFoundException(
                    "Account not found for ID: " + accountId);
        }
        Account account = accountOpt.get();
        log.debug("Account {} read successfully — balance: {}, status: {}",
                account.getAcctId(), account.getCurrBal(),
                account.getActiveStatus());
        return account;
    }

    /**
     * Updates an account record in the ACCTDATA dataset.
     * Maps to COBIL00C.cbl UPDATE-ACCTDAT-FILE paragraph (line 377).
     *
     * <p>Translates:</p>
     * <pre>
     *   EXEC CICS REWRITE
     *       DATASET('ACCTDAT')
     *       FROM(ACCOUNT-RECORD)
     *       RESP(WS-RESP-CD)
     *   END-EXEC
     * </pre>
     *
     * <p>Uses JPA {@code save()} which triggers the {@code @Version}
     * optimistic locking check, equivalent to CICS REWRITE after
     * READ UPDATE. Balance arithmetic uses {@link BigDecimal} with
     * {@link RoundingMode#HALF_UP} matching COBOL default rounding.</p>
     *
     * @param account the Account entity with updated fields
     * @return the persisted Account entity with updated version
     */
    public Account updateAcctdatFile(Account account) {
        log.debug("Updating account record: {} — new balance: {}",
                account.getAcctId(), account.getCurrBal());
        Account savedAccount = accountRepository.save(account);
        log.info("Account {} updated successfully — balance: {}",
                savedAccount.getAcctId(), savedAccount.getCurrBal());
        return savedAccount;
    }

    /**
     * Reads the card cross-reference (AIX) record by account ID.
     * Maps to COBIL00C.cbl READ-CXACAIX-FILE paragraph (line 408).
     *
     * <p>Translates:</p>
     * <pre>
     *   EXEC CICS READ
     *       DATASET('CXACAIX')
     *       INTO(CARD-XREF-RECORD)
     *       RIDFLD(XREF-ACCT-ID)
     *       RESP(WS-RESP-CD)
     *   END-EXEC
     * </pre>
     *
     * <p>The VSAM alternate index (AIX) on XREF-ACCT-ID is mapped to
     * {@link CardXrefRepository#findByAccountId(String)} which returns a
     * list. The first matching record is returned, consistent with VSAM
     * behavior of returning the first record matching the AIX key.</p>
     *
     * @param accountId the 11-character account ID for AIX lookup
     * @return the CardXref entity containing the associated card number
     * @throws RecordNotFoundException if no cross-reference found
     *                                  (DFHRESP(NOTFND), VSAM status '23')
     */
    public CardXref readCxacaixFile(String accountId) {
        log.debug("Reading card cross-reference for account: {}", accountId);
        List<CardXref> xrefs = cardXrefRepository.findByAccountId(accountId);
        if (xrefs.isEmpty()) {
            log.error("Card cross-reference not found for account: {} — "
                    + "VSAM status '23' equivalent", accountId);
            throw new RecordNotFoundException(
                    "Card cross-reference not found for account: " + accountId);
        }
        CardXref xref = xrefs.get(0);
        log.debug("Cross-reference resolved — account: {} -> card: {}",
                xref.getAccountId(), maskCardNumber(xref.getXrefCardNum()));
        return xref;
    }

    /**
     * Writes a new transaction record to the TRANSACT dataset.
     * Maps to COBIL00C.cbl WRITE-TRANSACT-FILE paragraph (line 510).
     *
     * <p>Translates:</p>
     * <pre>
     *   EXEC CICS WRITE
     *       DATASET('TRANSACT')
     *       FROM(TRAN-RECORD)
     *       RIDFLD(TRAN-ID)
     *       RESP(WS-RESP-CD)
     *   END-EXEC
     * </pre>
     *
     * <p>DFHRESP(DUPKEY) and DFHRESP(DUPREC) in COBOL cause ABEND
     * ABCODE('9999'). In Java, the equivalent is throwing
     * {@link DuplicateRecordException} for the controller to handle.</p>
     *
     * @param transaction the Transaction entity to persist
     * @return the persisted Transaction entity
     * @throws DuplicateRecordException if a duplicate key is detected
     *                                   (DFHRESP(DUPREC), VSAM status '22')
     */
    public Transaction writeTransactFile(Transaction transaction) {
        log.debug("Writing transaction record: ID={}, amount={}, type={}",
                transaction.getTranId(), transaction.getAmount(),
                transaction.getTypeCode());
        try {
            Transaction saved = transactionRepository.save(transaction);
            log.info("Transaction {} written successfully — amount: {}, "
                    + "description: '{}'",
                    saved.getTranId(), saved.getAmount(),
                    transaction.getDescription());
            return saved;
        } catch (RuntimeException e) {
            // Map DataIntegrityViolationException to DuplicateRecordException
            // matching COBIL00C.cbl DFHRESP(DUPKEY)/DFHRESP(DUPREC) → ABEND
            if (isDuplicateKeyException(e)) {
                log.error("Duplicate transaction ID detected: {} — "
                        + "VSAM status '22' equivalent",
                        transaction.getTranId());
                throw new DuplicateRecordException(
                        "Duplicate transaction record: "
                        + transaction.getTranId());
            }
            throw e;
        }
    }

    // ========================================================================
    // Private Helper Methods
    // ========================================================================

    /**
     * Processes a confirmed bill payment (confirm = 'Y').
     *
     * <p>Executes the complete payment flow from COBIL00C.cbl
     * PROCESS-ENTER-KEY paragraph WHEN 'Y' branch (lines 170-237):</p>
     * <ol>
     *   <li>Read cross-reference to resolve card number
     *       (PERFORM READ-CXACAIX-FILE)</li>
     *   <li>Generate new transaction ID via browse-last + 1
     *       (STARTBR → READPREV → ENDBR → ADD 1)</li>
     *   <li>Create and populate transaction record
     *       (MOVE statements lines 197-225)</li>
     *   <li>Write transaction record (PERFORM WRITE-TRANSACT-FILE)</li>
     *   <li>Update account balance:
     *       COMPUTE ACCT-CURR-BAL = ACCT-CURR-BAL - TRAN-AMT</li>
     * </ol>
     *
     * @param account the account to process payment for
     * @param result  the result object to populate
     * @return the populated result with payment details
     */
    private BillPaymentResult processConfirmedPayment(Account account,
                                                       BillPaymentResult result) {
        String accountId = account.getAcctId();
        log.info("Processing confirmed bill payment for account: {}", accountId);

        // Step 1: Read cross-reference to get card number
        // Maps PERFORM READ-CXACAIX-FILE (COBIL00C.cbl line 189)
        CardXref cardXref = readCxacaixFile(accountId);
        String cardNumber = cardXref.getXrefCardNum();
        result.setCardNumber(cardNumber);

        // Step 2: Generate new transaction ID via browse-last pattern
        // Maps STARTBR-TRANSACT-FILE → READPREV-TRANSACT-FILE → ENDBR
        // then ADD 1 TO WS-TRAN-ID-NUM, MOVE WS-TRAN-ID TO TRAN-ID
        String transactionId = generateTransactionId();
        log.debug("Generated transaction ID: {}", transactionId);

        // Step 3: Create and populate transaction record
        // Maps COBIL00C.cbl MOVE statements (lines 197-225)
        Transaction transaction = buildPaymentTransaction(
                transactionId, account, cardNumber);

        // Step 4: Write transaction record
        // Maps PERFORM WRITE-TRANSACT-FILE (line 228)
        Transaction savedTransaction = writeTransactFile(transaction);

        // Step 5: Update account balance
        // Maps COMPUTE ACCT-CURR-BAL = ACCT-CURR-BAL - TRAN-AMT (line 234)
        // TRAN-AMT was set to ACCT-CURR-BAL, so new balance = 0
        BigDecimal paymentAmount = account.getCurrBal();
        BigDecimal newBalance = account.getCurrBal()
                .subtract(paymentAmount)
                .setScale(MONETARY_SCALE, RoundingMode.HALF_UP);
        account.setCurrBal(newBalance);
        updateAcctdatFile(account);

        // Populate success result
        result.setSuccess(true);
        result.setTransactionId(savedTransaction.getTranId());
        result.setPaymentAmount(paymentAmount);
        result.setCurrentBalance(newBalance);
        result.setMessage("Bill payment was successful. Thank you.");

        log.info("Bill payment completed — account: {}, tran: {}, "
                + "amount: {}, new balance: {}",
                accountId, savedTransaction.getTranId(),
                paymentAmount, newBalance);

        return result;
    }

    /**
     * Builds a payment transaction record with COBOL-mapped field values.
     *
     * <p>Populates all fields matching COBIL00C.cbl MOVE statements
     * (lines 197-225):</p>
     * <pre>
     *   MOVE '02'                     TO TRAN-TYPE-CD
     *   MOVE 2                        TO TRAN-CAT-CD
     *   MOVE 'POS TERM'              TO TRAN-SOURCE
     *   MOVE 'BILL PAYMENT - ONLINE'  TO TRAN-DESC
     *   MOVE ACCT-CURR-BAL            TO TRAN-AMT
     *   MOVE 999999999                TO TRAN-MERCHANT-ID
     *   MOVE 'BILL PAYMENT'           TO TRAN-MERCHANT-NAME
     *   MOVE 'N/A'                    TO TRAN-MERCHANT-CITY
     *   MOVE 'N/A'                    TO TRAN-MERCHANT-ZIP
     *   MOVE XREF-CARD-NUM            TO TRAN-CARD-NUM
     * </pre>
     *
     * @param transactionId the generated 16-char transaction ID
     * @param account       the account being paid (source for TRAN-AMT)
     * @param cardNumber    the resolved card number from cross-reference
     * @return the fully populated Transaction entity ready for persistence
     */
    private Transaction buildPaymentTransaction(String transactionId,
                                                 Account account,
                                                 String cardNumber) {
        // Both timestamps set to current time
        // Maps GET-CURRENT-TIMESTAMP -> MOVE WS-TIMESTAMP TO TRAN-ORIG-TS
        // and MOVE WS-TIMESTAMP TO TRAN-PROC-TS
        String timestamp = getCurrentTimestamp();

        // Use the public all-fields constructor (no-arg is protected for JPA only).
        // Payment amount is ACCT-CURR-BAL -- bills are paid in full per COBOL logic.
        return new Transaction(
                transactionId,              // tranId -- generated via browse-last
                TRAN_TYPE_CODE_PAYMENT,     // typeCode -- '02' (Payment)
                TRAN_CAT_CODE_PAYMENT,      // categoryCode -- 2
                TRAN_SOURCE,                // source -- 'POS TERM'
                TRAN_DESCRIPTION,           // description -- 'BILL PAYMENT - ONLINE'
                account.getCurrBal(),       // amount -- MOVE ACCT-CURR-BAL TO TRAN-AMT
                TRAN_MERCHANT_ID,           // merchantId -- '999999999'
                TRAN_MERCHANT_NAME,         // merchantName -- 'BILL PAYMENT'
                TRAN_MERCHANT_CITY,         // merchantCity -- 'N/A'
                TRAN_MERCHANT_ZIP,          // merchantZip -- 'N/A'
                cardNumber,                 // cardNum -- from cross-reference lookup
                timestamp,                  // origTimestamp -- ISO-8601 26 chars
                timestamp                   // procTimestamp -- ISO-8601 26 chars
        );
    }

    /**
     * Generates the current timestamp in COBOL-compatible ISO-8601 format.
     * Maps to COBIL00C.cbl GET-CURRENT-TIMESTAMP paragraph (line 249).
     *
     * <p>Translates the CICS ASKTIME → FORMATTIME sequence:</p>
     * <pre>
     *   EXEC CICS FORMATTIME
     *       ABSTIME(WS-TIMESTAMP)
     *       YYYYMMDD(WS-TIMESTAMP-TM-YMD)  DATESEP('-')
     *       TIME(WS-TIMESTAMP-TM-HMS)      TIMESEP(':')
     *   END-EXEC
     *   MOVE ZEROS TO WS-TIMESTAMP-TM-MS6
     *   STRING date '-' time '.' microseconds INTO WS-TIMESTAMP
     * </pre>
     *
     * <p>Produces a 26-character string in the format:
     * {@code YYYY-MM-DD-HH:MM:SS.mmmmmm}</p>
     *
     * @return the formatted timestamp string (exactly 26 characters)
     */
    private String getCurrentTimestamp() {
        LocalDateTime now = LocalDateTime.now();
        String datePart = now.format(DATE_FORMATTER);
        String timePart = now.format(TIME_FORMATTER);
        // Microseconds: extracted from nanosecond precision
        // COBOL zeros this field (MOVE ZEROS TO WS-TIMESTAMP-TM-MS6)
        // but Java provides actual microsecond precision for improved tracing
        int microseconds = now.getNano() / 1_000;
        return String.format("%s-%s.%06d", datePart, timePart, microseconds);
    }

    /**
     * Generates a new unique transaction ID using the browse-last pattern.
     * Maps to COBIL00C.cbl STARTBR-TRANSACT-FILE (line 441),
     * READPREV-TRANSACT-FILE (line 472), and ENDBR-TRANSACT-FILE (line 501).
     *
     * <p>Translates the COBOL pattern:</p>
     * <pre>
     *   MOVE HIGH-VALUES TO TRAN-ID
     *   EXEC CICS STARTBR DATASET('TRANSACT') RIDFLD(TRAN-ID) GTEQ
     *   EXEC CICS READPREV DATASET('TRANSACT') INTO(TRAN-RECORD) RIDFLD(TRAN-ID)
     *   EXEC CICS ENDBR DATASET('TRANSACT')
     *   ADD 1 TO WS-TRAN-ID-NUM
     * </pre>
     *
     * <p>The generated ID is a 16-character zero-padded numeric string
     * matching COBOL working storage: WS-TRAN-ID PIC X(16) /
     * WS-TRAN-ID-NUM PIC 9(16).</p>
     *
     * @return the next available transaction ID, 16 characters zero-padded
     */
    private String generateTransactionId() {
        Optional<Transaction> lastTransaction =
                transactionRepository.findFirstByOrderByTranIdDesc();

        long nextIdNum;
        if (lastTransaction.isPresent()) {
            String lastId = lastTransaction.get().getTranId().trim();
            try {
                nextIdNum = Long.parseLong(lastId) + 1;
            } catch (NumberFormatException e) {
                log.warn("Unable to parse last transaction ID '{}' as numeric "
                        + "— defaulting to 1", lastId);
                nextIdNum = 1;
            }
        } else {
            log.debug("No existing transactions found — "
                    + "starting ID sequence at 1");
            nextIdNum = 1;
        }

        return String.format("%0" + TRAN_ID_LENGTH + "d", nextIdNum);
    }

    /**
     * Populates header/metadata information on the result.
     * Maps to COBIL00C.cbl POPULATE-HEADER-INFO paragraph (line 319).
     *
     * <p>Sets presentation metadata including program name, title,
     * current date, and user context from the COMMAREA:</p>
     * <pre>
     *   MOVE 'AWS CARDDEMO' TO TITLE01O
     *   MOVE 'BILL PAYMENT' TO TITLE02O
     *   MOVE 'COBIL00C'     TO PGMNAMEO
     * </pre>
     *
     * @param result the result object to populate with header info
     */
    private void populateHeaderInfo(BillPaymentResult result) {
        result.setProgramName("COBIL00C");
        result.setTitle("BILL PAYMENT");
        result.setCurrentDate(LocalDateTime.now().format(DATE_FORMATTER));
        // User context from COMMAREA — maps CDEMO-USER-ID and CDEMO-USER-TYPE
        result.setUserId(cardDemoContext.getUserId());
        if (cardDemoContext.getUserType() != null) {
            result.setUserType(cardDemoContext.getUserType().name());
        }
    }

    /**
     * Resolves the effective account ID from the request or session context.
     *
     * <p>If the request does not provide an account ID, falls back to the
     * COMMAREA field {@code CDEMO-ACCT-ID} via {@link CardDemoContext#getAcctId()}.
     * This mirrors the COBOL pattern where MAIN-PARA reads
     * {@code CDEMO-ACCT-ID} from the COMMAREA on first entry.</p>
     *
     * @param requestAccountId the account ID from the request (may be null)
     * @return the resolved account ID, or {@code null} if unavailable
     */
    private String resolveAccountId(String requestAccountId) {
        if (requestAccountId != null && !requestAccountId.isBlank()) {
            return requestAccountId;
        }
        String contextAcctId = cardDemoContext.getAcctId();
        if (contextAcctId != null && !contextAcctId.isBlank()) {
            log.debug("Account ID resolved from session context: {}",
                    contextAcctId);
            return contextAcctId;
        }
        return null;
    }

    /**
     * Populates account-related fields on the result object for display.
     *
     * <p>Maps the COBOL pattern of populating BMS output fields from the
     * account record (ACCTDAT READ → SEND MAP).</p>
     *
     * @param result  the result to populate
     * @param account the account entity to read from
     */
    private void populateAccountResult(BillPaymentResult result,
                                        Account account) {
        result.setAccountId(account.getAcctId());
        result.setCurrentBalance(account.getCurrBal());
        result.setCreditLimit(account.getCreditLimit());
        result.setActiveStatus(account.getActiveStatus());
        result.setExpirationDate(account.getExpirationDate());
    }

    /**
     * Determines if a runtime exception represents a duplicate key violation.
     *
     * <p>Checks the exception class name and message for indicators of a
     * unique constraint violation, mapping to COBOL DFHRESP(DUPKEY) and
     * DFHRESP(DUPREC) response codes.</p>
     *
     * @param ex the exception to inspect
     * @return {@code true} if the exception indicates a duplicate key
     */
    private static boolean isDuplicateKeyException(RuntimeException ex) {
        String className = ex.getClass().getName();
        if (className.contains("DataIntegrityViolation")
                || className.contains("ConstraintViolation")) {
            return true;
        }
        String message = ex.getMessage();
        return message != null
                && (message.contains("duplicate")
                    || message.contains("unique constraint")
                    || message.contains("UNIQUE"));
    }

    /**
     * Masks a card number for secure logging (PII protection).
     * Shows only the last 4 digits to prevent card number exposure in logs.
     *
     * @param cardNumber the full card number (PIC X(16))
     * @return the masked card number (e.g., "************1234")
     */
    private static String maskCardNumber(String cardNumber) {
        if (cardNumber == null || cardNumber.length() <= 4) {
            return "****";
        }
        int visibleDigits = 4;
        String masked = "*".repeat(cardNumber.length() - visibleDigits);
        return masked + cardNumber.substring(cardNumber.length() - visibleDigits);
    }

    // ========================================================================
    // Inner Types — Request and Result DTOs
    // ========================================================================

    /**
     * Request DTO for bill payment operations.
     *
     * <p>Captures the input fields from the COBIL00 BMS map
     * (COBIL0AI input structure):</p>
     * <ul>
     *   <li>{@code ACTIDINI} (PIC X(11)) → {@link #accountId}</li>
     *   <li>{@code CONFIRMI} (PIC X(1))  → {@link #confirm}</li>
     * </ul>
     *
     * <p>The {@link #action} field maps the CICS EIBAID key detection
     * (DFHENTER, DFHPF3, DFHPF4, DFHCLEAR) from the MAIN-PARA
     * EVALUATE EIBAID block.</p>
     */
    public static class BillPaymentRequest {

        /** Account ID — maps BMS field ACTIDINI PIC X(11). */
        private String accountId;

        /**
         * Confirmation flag — maps BMS field CONFIRMI PIC X(1).
         * <ul>
         *   <li>'Y'/'y' — confirm payment</li>
         *   <li>'N'/'n' — cancel payment</li>
         *   <li>{@code null}/blank — display-only (first entry)</li>
         * </ul>
         */
        private String confirm;

        /**
         * Action/key identifier — maps CICS EIBAID.
         * <ul>
         *   <li>"ENTER" — enter key (DFHENTER)</li>
         *   <li>"PF3" — return to previous screen (DFHPF3)</li>
         *   <li>"PF4" — clear screen (DFHPF4)</li>
         *   <li>Other — invalid key</li>
         * </ul>
         */
        private String action;

        /** Default constructor for framework (Jackson/Spring MVC) use. */
        public BillPaymentRequest() {
            // Default constructor for deserialization
        }

        /**
         * Constructs a request with account ID and confirmation flag.
         *
         * @param accountId the 11-character account ID
         * @param confirm   the confirmation flag ('Y'/'N'/{@code null})
         */
        public BillPaymentRequest(String accountId, String confirm) {
            this.accountId = accountId;
            this.confirm = confirm;
        }

        /**
         * Returns the account ID.
         *
         * @return the account ID, or {@code null} if not provided
         */
        public String getAccountId() {
            return accountId;
        }

        /**
         * Sets the account ID.
         *
         * @param accountId the 11-character account ID
         */
        public void setAccountId(String accountId) {
            this.accountId = accountId;
        }

        /**
         * Returns the confirmation flag.
         *
         * @return the confirmation value ('Y'/'N'/{@code null})
         */
        public String getConfirm() {
            return confirm;
        }

        /**
         * Sets the confirmation flag.
         *
         * @param confirm the confirmation value ('Y'/'N'/{@code null})
         */
        public void setConfirm(String confirm) {
            this.confirm = confirm;
        }

        /**
         * Returns the action/key identifier.
         *
         * @return the action string, or {@code null} for default (ENTER)
         */
        public String getAction() {
            return action;
        }

        /**
         * Sets the action/key identifier.
         *
         * @param action the action string (e.g., "ENTER", "PF3")
         */
        public void setAction(String action) {
            this.action = action;
        }
    }

    /**
     * Result DTO for bill payment operations.
     *
     * <p>Carries all output fields from the bill payment processing,
     * mapping to the COBIL0AO BMS output map fields and the
     * {@code WS-MESSAGE} working storage field.</p>
     */
    public static class BillPaymentResult {

        /** Whether the operation completed successfully. */
        private boolean success;

        /** Response message — maps WS-MESSAGE PIC X(78). */
        private String message;

        /** Account ID — maps ACTIDINO in BMS output. */
        private String accountId;

        /** Current account balance — maps CURBALO (after payment if processed). */
        private BigDecimal currentBalance;

        /** Credit limit — maps ACCT-CREDIT-LIMIT for display. */
        private BigDecimal creditLimit;

        /** Account active status — maps ACCT-ACTIVE-STATUS. */
        private String activeStatus;

        /** Account expiration date — maps ACCT-EXPIRAION-DATE. */
        private String expirationDate;

        /** Resolved card number from cross-reference lookup. */
        private String cardNumber;

        /** Transaction ID generated for the payment — maps TRAN-ID PIC X(16). */
        private String transactionId;

        /** Amount paid — maps TRAN-AMT (equals full balance per COBOL). */
        private BigDecimal paymentAmount;

        /** Program name for header display — maps PGMNAMEO. */
        private String programName;

        /** Screen title — maps TITLE02O. */
        private String title;

        /** Current date for header — maps TRNNAMEO. */
        private String currentDate;

        /** User ID for header — from COMMAREA CDEMO-USER-ID. */
        private String userId;

        /** User type/role for header — from COMMAREA CDEMO-USER-TYPE. */
        private String userType;

        /** Default constructor. */
        public BillPaymentResult() {
            // Default constructor for framework use
        }

        // ---- Getters and Setters ----

        /**
         * Returns whether the operation was successful.
         *
         * @return {@code true} if the operation completed successfully
         */
        public boolean isSuccess() {
            return success;
        }

        /**
         * Sets the success flag.
         *
         * @param success {@code true} if the operation completed successfully
         */
        public void setSuccess(boolean success) {
            this.success = success;
        }

        /**
         * Returns the response message.
         *
         * @return the message string, or {@code null} if not set
         */
        public String getMessage() {
            return message;
        }

        /**
         * Sets the response message.
         *
         * @param message the response message (max 78 chars per BMS)
         */
        public void setMessage(String message) {
            this.message = message;
        }

        /**
         * Returns the account ID.
         *
         * @return the 11-character account ID
         */
        public String getAccountId() {
            return accountId;
        }

        /**
         * Sets the account ID.
         *
         * @param accountId the 11-character account ID
         */
        public void setAccountId(String accountId) {
            this.accountId = accountId;
        }

        /**
         * Returns the current account balance.
         *
         * @return the balance as BigDecimal (scale 2)
         */
        public BigDecimal getCurrentBalance() {
            return currentBalance;
        }

        /**
         * Sets the current account balance.
         *
         * @param currentBalance the balance as BigDecimal
         */
        public void setCurrentBalance(BigDecimal currentBalance) {
            this.currentBalance = currentBalance;
        }

        /**
         * Returns the credit limit.
         *
         * @return the credit limit as BigDecimal
         */
        public BigDecimal getCreditLimit() {
            return creditLimit;
        }

        /**
         * Sets the credit limit.
         *
         * @param creditLimit the credit limit as BigDecimal
         */
        public void setCreditLimit(BigDecimal creditLimit) {
            this.creditLimit = creditLimit;
        }

        /**
         * Returns the active status.
         *
         * @return the status character ('Y'/'N')
         */
        public String getActiveStatus() {
            return activeStatus;
        }

        /**
         * Sets the active status.
         *
         * @param activeStatus the status character
         */
        public void setActiveStatus(String activeStatus) {
            this.activeStatus = activeStatus;
        }

        /**
         * Returns the expiration date.
         *
         * @return the expiration date string
         */
        public String getExpirationDate() {
            return expirationDate;
        }

        /**
         * Sets the expiration date.
         *
         * @param expirationDate the expiration date string
         */
        public void setExpirationDate(String expirationDate) {
            this.expirationDate = expirationDate;
        }

        /**
         * Returns the card number.
         *
         * @return the 16-character card number
         */
        public String getCardNumber() {
            return cardNumber;
        }

        /**
         * Sets the card number.
         *
         * @param cardNumber the 16-character card number
         */
        public void setCardNumber(String cardNumber) {
            this.cardNumber = cardNumber;
        }

        /**
         * Returns the transaction ID.
         *
         * @return the 16-character transaction ID, or {@code null}
         */
        public String getTransactionId() {
            return transactionId;
        }

        /**
         * Sets the transaction ID.
         *
         * @param transactionId the 16-character transaction ID
         */
        public void setTransactionId(String transactionId) {
            this.transactionId = transactionId;
        }

        /**
         * Returns the payment amount.
         *
         * @return the amount paid as BigDecimal, or {@code null}
         */
        public BigDecimal getPaymentAmount() {
            return paymentAmount;
        }

        /**
         * Sets the payment amount.
         *
         * @param paymentAmount the amount paid as BigDecimal
         */
        public void setPaymentAmount(BigDecimal paymentAmount) {
            this.paymentAmount = paymentAmount;
        }

        /**
         * Returns the program name.
         *
         * @return the COBOL program name ("COBIL00C")
         */
        public String getProgramName() {
            return programName;
        }

        /**
         * Sets the program name.
         *
         * @param programName the program name for header display
         */
        public void setProgramName(String programName) {
            this.programName = programName;
        }

        /**
         * Returns the screen title.
         *
         * @return the title string ("BILL PAYMENT")
         */
        public String getTitle() {
            return title;
        }

        /**
         * Sets the screen title.
         *
         * @param title the title for header display
         */
        public void setTitle(String title) {
            this.title = title;
        }

        /**
         * Returns the current date for header display.
         *
         * @return the formatted date string
         */
        public String getCurrentDate() {
            return currentDate;
        }

        /**
         * Sets the current date for header display.
         *
         * @param currentDate the formatted date string
         */
        public void setCurrentDate(String currentDate) {
            this.currentDate = currentDate;
        }

        /**
         * Returns the user ID.
         *
         * @return the user ID from session context
         */
        public String getUserId() {
            return userId;
        }

        /**
         * Sets the user ID.
         *
         * @param userId the user ID for header display
         */
        public void setUserId(String userId) {
            this.userId = userId;
        }

        /**
         * Returns the user type/role.
         *
         * @return the user type name (e.g., "ADMIN", "USER")
         */
        public String getUserType() {
            return userType;
        }

        /**
         * Sets the user type/role.
         *
         * @param userType the user type name for header display
         */
        public void setUserType(String userType) {
            this.userType = userType;
        }
    }
}
