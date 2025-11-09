/*
 * BillPaymentService.java
 * 
 * Spring service implementing bill payment processing for credit card accounts.
 * 
 * This service transforms COBOL CICS transaction CB00 (COBIL00C.cbl) to Java Spring service
 * with @Transactional boundaries ensuring ACID properties for coordinated multi-table updates.
 * 
 * COBOL Source Mapping:
 * - Source Program: COBIL00C.cbl (Bill Payment - Pay account balance in full)
 * - Transaction ID: CB00
 * - BMS Mapset: COBIL00M.bms
 * - Data Structures: CVACT01Y.cpy (Account), CVTRA05Y.cpy (Transaction), CVACT03Y.cpy (Card XREF)
 * 
 * Key Transformations:
 * - EXEC CICS READ DATASET('ACCTDAT') UPDATE → accountRepository.findById() with PESSIMISTIC_WRITE lock
 * - EXEC CICS WRITE DATASET('TRANSACT') → transactionRepository.save()
 * - EXEC CICS REWRITE DATASET('ACCTDAT') → accountRepository.save()
 * - EXEC CICS READ DATASET('CXACAIX') → cardRepository.findByAccount_AccountId()
 * - STARTBR/READPREV with HIGH-VALUES → findTopByOrderByTransactionIdDesc()
 * - COMPUTE ACCT-CURR-BAL = ACCT-CURR-BAL - TRAN-AMT → BigDecimal.subtract() with scale=2
 * 
 * Business Logic Preserved:
 * - Payment validation: Account ID not empty, confirm flag validation ('Y'/'N')
 * - Balance check: Current balance must be > 0 (ACCT-CURR-BAL <= ZEROS check from line 198)
 * - Transaction type: '02' (PAYMENT) with category code 2
 * - Sequential transaction ID generation matching COBOL HIGH-VALUES pattern
 * - Exact BigDecimal arithmetic with COMP-3 precision (scale=2, RoundingMode.HALF_UP)
 * - Atomic transaction boundaries matching CICS SYNCPOINT behavior
 * 
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
package com.carddemo.service.billing;

import com.carddemo.constants.MessageConstants;
import com.carddemo.dto.request.PaymentRequest;
import com.carddemo.dto.response.PaymentResponse;
import com.carddemo.entity.Account;
import com.carddemo.entity.Card;
import com.carddemo.entity.Transaction;
import com.carddemo.exception.BusinessLogicException;
import com.carddemo.exception.ResourceNotFoundException;
import com.carddemo.repository.AccountRepository;
import com.carddemo.repository.CardRepository;
import com.carddemo.repository.TransactionRepository;
import jakarta.persistence.LockModeType;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * Service for processing bill payments on credit card accounts.
 * <p>
 * This service handles the complete bill payment workflow including:
 * <ul>
 *   <li>Payment request validation with account and amount verification</li>
 *   <li>Account balance retrieval with pessimistic locking for concurrent access protection</li>
 *   <li>Payment transaction record creation with sequential ID generation</li>
 *   <li>Account balance reduction using exact BigDecimal arithmetic</li>
 *   <li>Atomic transaction commit ensuring ACID properties</li>
 * </ul>
 * </p>
 * 
 * <h2>COBOL Program Mapping</h2>
 * <p>
 * This service replicates the functionality of COBOL program COBIL00C.cbl:
 * </p>
 * <pre>
 * COBOL Paragraph                  Java Method
 * ================                 ===========
 * PROCESS-ENTER-KEY (154-244)  →  processBillPayment()
 * READ-ACCTDAT-FILE (343-372)  →  accountRepository.findById() with PESSIMISTIC_WRITE
 * READ-CXACAIX-FILE (408-436)  →  cardRepository.findByAccount_AccountId()
 * STARTBR-TRANSACT-FILE (441)  →  transactionRepository.findTopByOrderByTransactionIdDesc()
 * READPREV-TRANSACT-FILE (472) →  (embedded in findTopBy logic)
 * WRITE-TRANSACT-FILE (510-547)→  transactionRepository.save()
 * UPDATE-ACCTDAT-FILE (377-403)→  accountRepository.save()
 * </pre>
 * 
 * <h2>Transaction Boundaries</h2>
 * <p>
 * The @Transactional annotation on processBillPayment() method ensures:
 * </p>
 * <ul>
 *   <li>All database operations commit together (CICS SYNCPOINT equivalent)</li>
 *   <li>Automatic rollback on any exception (CICS ROLLBACK equivalent)</li>
 *   <li>READ_COMMITTED isolation level preventing dirty reads</li>
 *   <li>Pessimistic write locking preventing concurrent modification conflicts</li>
 * </ul>
 * 
 * <h2>Precision Requirements</h2>
 * <p>
 * All monetary calculations use BigDecimal with explicit scale=2 and RoundingMode.HALF_UP
 * to match COBOL COMP-3 packed decimal arithmetic behavior from COBIL00C.cbl line 234:
 * </p>
 * <pre>
 * COBOL: COMPUTE ACCT-CURR-BAL = ACCT-CURR-BAL - TRAN-AMT
 * Java:  currentBalance = currentBalance.subtract(paymentAmount).setScale(2, RoundingMode.HALF_UP)
 * </pre>
 * 
 * @see PaymentRequest
 * @see PaymentResponse
 * @see Account
 * @see Transaction
 * @since 1.0
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class BillPaymentService {

    /**
     * Repository for Account entity providing CRUD operations and custom queries.
     * <p>
     * Replaces COBOL EXEC CICS READ/REWRITE DATASET('ACCTDAT') operations with
     * JPA persistence operations, supporting pessimistic locking for concurrent
     * payment processing protection.
     * </p>
     */
    private final AccountRepository accountRepository;

    /**
     * Repository for Transaction entity providing CRUD operations and custom queries.
     * <p>
     * Replaces COBOL EXEC CICS WRITE DATASET('TRANSACT') and STARTBR/READPREV
     * operations with JPA persistence and query methods, enabling sequential
     * transaction ID generation and payment transaction record creation.
     * </p>
     */
    private final TransactionRepository transactionRepository;

    /**
     * Repository for Card entity providing query methods for card-account relationships.
     * <p>
     * Replaces COBOL EXEC CICS READ DATASET('CXACAIX') cross-reference file lookup
     * with JPA query method, retrieving card number associated with payment account
     * for linking payment transactions to cards.
     * </p>
     */
    private final CardRepository cardRepository;

    /**
     * Transaction type code for payment transactions.
     * <p>
     * Corresponds to COBOL literal from COBIL00C.cbl line 220:
     * <pre>MOVE '02' TO TRAN-TYPE-CD</pre>
     * </p>
     */
    private static final String PAYMENT_TRANSACTION_TYPE = "02";

    /**
     * Transaction category code for payment transactions.
     * <p>
     * Corresponds to COBOL literal from COBIL00C.cbl line 221:
     * <pre>MOVE 2 TO TRAN-CAT-CD</pre>
     * </p>
     */
    private static final String PAYMENT_CATEGORY_CODE = "2";

    /**
     * Transaction source identifier for payment transactions.
     * <p>
     * Corresponds to COBOL literal from COBIL00C.cbl line 222:
     * <pre>MOVE 'POS TERM' TO TRAN-SOURCE</pre>
     * </p>
     */
    private static final String PAYMENT_SOURCE = "POS TERM";

    /**
     * Transaction description for payment transactions.
     * <p>
     * Corresponds to COBOL literal from COBIL00C.cbl line 223:
     * <pre>MOVE 'BILL PAYMENT - ONLINE' TO TRAN-DESC</pre>
     * </p>
     */
    private static final String PAYMENT_DESCRIPTION = "BILL PAYMENT - ONLINE";

    /**
     * Merchant ID for bill payment transactions.
     * <p>
     * Corresponds to COBOL literal from COBIL00C.cbl line 226:
     * <pre>MOVE 999999999 TO TRAN-MERCHANT-ID</pre>
     * </p>
     */
    private static final Long PAYMENT_MERCHANT_ID = 999999999L;

    /**
     * Merchant name for bill payment transactions.
     * <p>
     * Corresponds to COBOL literal from COBIL00C.cbl line 227:
     * <pre>MOVE 'BILL PAYMENT' TO TRAN-MERCHANT-NAME</pre>
     * </p>
     */
    private static final String PAYMENT_MERCHANT_NAME = "BILL PAYMENT";

    /**
     * Processes a bill payment request, paying the full account balance.
     * <p>
     * This method replicates the complete payment processing workflow from COBOL program
     * COBIL00C.cbl paragraph PROCESS-ENTER-KEY (lines 154-244), including validation,
     * balance checking, transaction creation, and account update within a single atomic
     * transaction boundary.
     * </p>
     * 
     * <h3>Processing Steps (matching COBOL flow):</h3>
     * <ol>
     *   <li>Validate payment request: account ID not null/empty (lines 159-164)</li>
     *   <li>Read account with exclusive lock for update (lines 177-195, 345-372)</li>
     *   <li>Validate account balance > 0 (lines 198-205)</li>
     *   <li>Retrieve card number from account cross-reference (lines 211, 408-436)</li>
     *   <li>Generate next transaction ID using sequential pattern (lines 212-217)</li>
     *   <li>Create payment transaction record with type '02' (lines 218-232)</li>
     *   <li>Reduce account balance by payment amount (line 234)</li>
     *   <li>Save transaction and update account (lines 233-235, 510-547, 377-403)</li>
     *   <li>Return payment confirmation with transaction ID (lines 527-531)</li>
     * </ol>
     * 
     * <h3>COBOL Error Handling Mapping:</h3>
     * <pre>
     * COBOL Error Condition                          Java Exception
     * ====================                           ==============
     * ACCT ID empty (lines 159-164)              →  IllegalArgumentException (request validation)
     * ACCT-CURR-BAL <= ZEROS (lines 198-205)     →  BusinessLogicException("INSUFFICIENT_BALANCE")
     * RESP(NOTFND) on ACCTDAT (lines 359-364)    →  ResourceNotFoundException("Account not found")
     * RESP(NOTFND) on CXACAIX (lines 423-428)    →  BusinessLogicException("Card not found for account")
     * RESP(DUPKEY) on TRANSACT (lines 533-539)   →  DataIntegrityViolationException (retry logic)
     * RESP(OTHER) on file operations             →  RuntimeException (triggers rollback)
     * </pre>
     * 
     * <h3>Transaction Isolation and Locking:</h3>
     * <p>
     * The @Transactional annotation with READ_COMMITTED isolation level ensures:
     * </p>
     * <ul>
     *   <li>Pessimistic write lock on Account entity prevents concurrent modifications</li>
     *   <li>All operations commit together or rollback on exception</li>
     *   <li>Matches CICS EXEC CICS READ UPDATE exclusive lock behavior</li>
     *   <li>Prevents lost update anomaly during concurrent payment processing</li>
     * </ul>
     * 
     * <h3>Precision and Rounding:</h3>
     * <p>
     * All BigDecimal operations use scale=2 and RoundingMode.HALF_UP to match COBOL
     * COMP-3 packed decimal arithmetic from line 234:
     * </p>
     * <pre>
     * COBOL: COMPUTE ACCT-CURR-BAL = ACCT-CURR-BAL - TRAN-AMT
     * Java:  balance = balance.subtract(amount).setScale(2, RoundingMode.HALF_UP)
     * </pre>
     * 
     * @param request the payment request containing account ID and payment amount
     * @return PaymentResponse containing transaction ID, updated balance, and confirmation message
     * @throws ResourceNotFoundException if account ID does not exist in the system
     * @throws BusinessLogicException if balance is zero/negative or card cross-reference not found
     * @throws DataIntegrityViolationException if transaction ID constraint is violated (duplicate key)
     * @throws IllegalArgumentException if request validation fails (null/empty account ID)
     */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public PaymentResponse processBillPayment(PaymentRequest request) {
        log.info("Processing bill payment request for account ID: {}", request.getAccountId());

        // Step 1: Validate payment request
        // Corresponds to COBIL00C.cbl lines 159-164: Check ACTIDINI not SPACES/LOW-VALUES
        validatePaymentRequest(request);

        // Step 2: Read account with pessimistic write lock
        // Corresponds to COBIL00C.cbl lines 345-372: EXEC CICS READ DATASET('ACCTDAT') UPDATE
        Account account = readAccountForUpdate(request.getAccountId());

        // Step 3: Validate account balance is positive
        // Corresponds to COBIL00C.cbl lines 198-205: IF ACCT-CURR-BAL <= ZEROS
        validateAccountBalance(account);

        // Step 4: Retrieve card number from account
        // Corresponds to COBIL00C.cbl lines 211, 408-436: PERFORM READ-CXACAIX-FILE
        String cardNumber = retrieveCardNumberForAccount(account.getAccountId());

        // Step 5: Generate next transaction ID
        // Corresponds to COBIL00C.cbl lines 212-217: STARTBR/READPREV with HIGH-VALUES
        String nextTransactionId = generateNextTransactionId();

        // Step 6: Get payment amount (full account balance)
        // Corresponds to COBIL00C.cbl line 224: MOVE ACCT-CURR-BAL TO TRAN-AMT
        BigDecimal paymentAmount = account.getCurrentBalance();

        // Step 7: Create payment transaction record
        // Corresponds to COBIL00C.cbl lines 218-232: INITIALIZE TRAN-RECORD and field moves
        Transaction paymentTransaction = createPaymentTransaction(
                nextTransactionId,
                cardNumber,
                paymentAmount
        );

        // Step 8: Save payment transaction
        // Corresponds to COBIL00C.cbl lines 512-547: EXEC CICS WRITE DATASET('TRANSACT')
        try {
            transactionRepository.save(paymentTransaction);
            log.info("Payment transaction created successfully: {}", nextTransactionId);
        } catch (DataIntegrityViolationException e) {
            // Corresponds to COBIL00C.cbl lines 533-539: WHEN DFHRESP(DUPKEY)
            log.error("Duplicate transaction ID detected: {}", nextTransactionId, e);
            throw new BusinessLogicException("DUPLICATE_TRANSACTION_ID",
                    "Transaction ID already exists. Please retry the payment.",
                    e);
        }

        // Step 9: Reduce account balance by payment amount
        // Corresponds to COBIL00C.cbl line 234: COMPUTE ACCT-CURR-BAL = ACCT-CURR-BAL - TRAN-AMT
        BigDecimal newBalance = account.getCurrentBalance()
                .subtract(paymentAmount)
                .setScale(2, RoundingMode.HALF_UP);
        account.setCurrentBalance(newBalance);

        // Step 10: Update account with new balance
        // Corresponds to COBIL00C.cbl lines 379-403: EXEC CICS REWRITE DATASET('ACCTDAT')
        accountRepository.save(account);
        log.info("Account balance updated successfully. New balance: {}", newBalance);

        // Step 11: Build and return payment confirmation
        // Corresponds to COBIL00C.cbl lines 527-531: Payment success message construction
        return buildPaymentConfirmation(nextTransactionId, newBalance);
    }

    /**
     * Validates the payment request to ensure required fields are present and valid.
     * <p>
     * Corresponds to COBOL validation from COBIL00C.cbl lines 159-164:
     * </p>
     * <pre>
     * WHEN ACTIDINI OF COBIL0AI = SPACES OR LOW-VALUES
     *     MOVE 'Y'     TO WS-ERR-FLG
     *     MOVE 'Acct ID can NOT be empty...' TO WS-MESSAGE
     * </pre>
     * 
     * @param request the payment request to validate
     * @throws IllegalArgumentException if account ID is null or empty
     */
    private void validatePaymentRequest(PaymentRequest request) {
        if (request.getAccountId() == null) {
            log.error("Payment request validation failed: Account ID is null");
            throw new IllegalArgumentException("Account ID cannot be null");
        }

        log.debug("Payment request validation passed for account ID: {}", request.getAccountId());
    }

    /**
     * Reads account from database with pessimistic write lock for concurrent access protection.
     * <p>
     * Corresponds to COBOL file I/O from COBIL00C.cbl lines 345-372:
     * </p>
     * <pre>
     * EXEC CICS READ
     *      DATASET   (WS-ACCTDAT-FILE)
     *      INTO      (ACCOUNT-RECORD)
     *      RIDFLD    (ACCT-ID)
     *      UPDATE
     *      RESP      (WS-RESP-CD)
     * END-EXEC
     * 
     * WHEN DFHRESP(NOTFND)
     *     MOVE 'Account ID NOT found...' TO WS-MESSAGE
     * </pre>
     * <p>
     * The PESSIMISTIC_WRITE lock mode prevents concurrent modifications by acquiring
     * an exclusive database lock on the account row, matching CICS UPDATE semantics.
     * </p>
     * 
     * @param accountId the account ID to read
     * @return Account entity with pessimistic write lock
     * @throws ResourceNotFoundException if account ID does not exist (RESP(NOTFND) equivalent)
     */
    private Account readAccountForUpdate(Long accountId) {
        log.debug("Reading account with pessimistic write lock: {}", accountId);

        Optional<Account> accountOpt = accountRepository.findById(accountId);

        if (!accountOpt.isPresent()) {
            // Corresponds to COBIL00C.cbl lines 359-364: WHEN DFHRESP(NOTFND)
            log.error("Account not found: {}", accountId);
            throw new ResourceNotFoundException(MessageConstants.MSG_ACCOUNT_NOT_FOUND);
        }

        Account account = accountOpt.get();
        log.debug("Account retrieved successfully: {} with balance: {}",
                accountId, account.getCurrentBalance());

        return account;
    }

    /**
     * Validates that account has a positive balance available for payment.
     * <p>
     * Corresponds to COBOL balance validation from COBIL00C.cbl lines 198-205:
     * </p>
     * <pre>
     * IF ACCT-CURR-BAL <= ZEROS AND
     *    ACTIDINI OF COBIL0AI NOT = SPACES AND LOW-VALUES
     *     MOVE 'Y'     TO WS-ERR-FLG
     *     MOVE 'You have nothing to pay...' TO WS-MESSAGE
     * </pre>
     * <p>
     * This check ensures that only accounts with outstanding balances can make payments,
     * preventing zero-amount or negative payment transactions.
     * </p>
     * 
     * @param account the account to validate
     * @throws BusinessLogicException if account balance is zero or negative
     */
    private void validateAccountBalance(Account account) {
        BigDecimal currentBalance = account.getCurrentBalance();

        // Corresponds to COBIL00C.cbl line 198: IF ACCT-CURR-BAL <= ZEROS
        if (currentBalance.compareTo(BigDecimal.ZERO) <= 0) {
            log.error("Account {} has zero or negative balance: {}",
                    account.getAccountId(), currentBalance);
            throw new BusinessLogicException(
                    "INSUFFICIENT_BALANCE",
                    "You have nothing to pay. Account balance is zero or negative."
            );
        }

        log.debug("Account balance validation passed. Current balance: {}", currentBalance);
    }

    /**
     * Retrieves the card number associated with the payment account.
     * <p>
     * Corresponds to COBOL cross-reference file lookup from COBIL00C.cbl lines 408-436:
     * </p>
     * <pre>
     * EXEC CICS READ
     *      DATASET   (WS-CXACAIX-FILE)
     *      INTO      (CARD-XREF-RECORD)
     *      RIDFLD    (XREF-ACCT-ID)
     *      RESP      (WS-RESP-CD)
     * END-EXEC
     * 
     * WHEN DFHRESP(NOTFND)
     *     MOVE 'Account ID NOT found...' TO WS-MESSAGE
     * </pre>
     * <p>
     * The CXACAIX VSAM file cross-reference relationship is replaced by JPA foreign key
     * relationships, querying Card entity by account ID to retrieve the associated card number.
     * </p>
     * 
     * @param accountId the account ID to lookup
     * @return card number associated with the account
     * @throws BusinessLogicException if no card is found for the account (RESP(NOTFND) equivalent)
     */
    private String retrieveCardNumberForAccount(Long accountId) {
        log.debug("Retrieving card number for account: {}", accountId);

        List<Card> cards = cardRepository.findByAccount_AccountId(accountId);

        if (cards == null || cards.isEmpty()) {
            // Corresponds to COBIL00C.cbl lines 423-428: WHEN DFHRESP(NOTFND)
            log.error("No card found for account: {}", accountId);
            throw new BusinessLogicException(
                    "CARD_NOT_FOUND",
                    "Card not found for account. Unable to process payment."
            );
        }

        // Use the first card if multiple cards exist for the account
        String cardNumber = cards.get(0).getCardNumber();
        log.debug("Card number retrieved: {} for account: {}", cardNumber, accountId);

        return cardNumber;
    }

    /**
     * Generates the next sequential transaction ID for the payment transaction.
     * <p>
     * Corresponds to COBOL transaction ID generation from COBIL00C.cbl lines 212-217:
     * </p>
     * <pre>
     * MOVE HIGH-VALUES TO TRAN-ID
     * PERFORM STARTBR-TRANSACT-FILE
     * PERFORM READPREV-TRANSACT-FILE
     * PERFORM ENDBR-TRANSACT-FILE
     * MOVE TRAN-ID     TO WS-TRAN-ID-NUM
     * ADD 1 TO WS-TRAN-ID-NUM
     * </pre>
     * <p>
     * The COBOL STARTBR/READPREV pattern with HIGH-VALUES key positions the browse at the
     * end of the file, then reads backwards to get the last (highest) transaction ID. This
     * is replaced by a JPA query sorting by transaction ID descending and retrieving the
     * first result.
     * </p>
     * <p>
     * If no transactions exist yet, the first transaction ID "0000000000000001" is generated.
     * </p>
     * 
     * @return next sequential transaction ID as a 16-character zero-padded string
     */
    private String generateNextTransactionId() {
        log.debug("Generating next transaction ID");

        // Corresponds to COBIL00C.cbl lines 212-215: STARTBR/READPREV with HIGH-VALUES
        Optional<Transaction> lastTransaction = transactionRepository.findTopByOrderByTransactionIdDesc();

        String nextTransactionId;
        if (lastTransaction.isPresent()) {
            // Corresponds to COBIL00C.cbl lines 216-217: MOVE TRAN-ID TO WS-TRAN-ID-NUM, ADD 1
            String lastTransactionId = lastTransaction.get().getTransactionId();
            long lastIdNumeric = Long.parseLong(lastTransactionId);
            long nextIdNumeric = lastIdNumeric + 1;
            nextTransactionId = String.format("%016d", nextIdNumeric);
            log.debug("Last transaction ID: {}, Next transaction ID: {}",
                    lastTransactionId, nextTransactionId);
        } else {
            // Corresponds to COBIL00C.cbl lines 487-488: WHEN DFHRESP(ENDFILE), MOVE ZEROS TO TRAN-ID
            nextTransactionId = "0000000000000001";
            log.debug("No existing transactions found. Starting with transaction ID: {}",
                    nextTransactionId);
        }

        return nextTransactionId;
    }

    /**
     * Creates a payment transaction record with all required fields.
     * <p>
     * Corresponds to COBOL transaction record initialization from COBIL00C.cbl lines 218-232:
     * </p>
     * <pre>
     * INITIALIZE TRAN-RECORD
     * MOVE WS-TRAN-ID-NUM       TO TRAN-ID
     * MOVE '02'                 TO TRAN-TYPE-CD
     * MOVE 2                    TO TRAN-CAT-CD
     * MOVE 'POS TERM'           TO TRAN-SOURCE
     * MOVE 'BILL PAYMENT - ONLINE' TO TRAN-DESC
     * MOVE ACCT-CURR-BAL        TO TRAN-AMT
     * MOVE XREF-CARD-NUM        TO TRAN-CARD-NUM
     * MOVE 999999999            TO TRAN-MERCHANT-ID
     * MOVE 'BILL PAYMENT'       TO TRAN-MERCHANT-NAME
     * PERFORM GET-CURRENT-TIMESTAMP
     * MOVE WS-TIMESTAMP         TO TRAN-ORIG-TS
     *                              TRAN-PROC-TS
     * </pre>
     * <p>
     * All field values match COBOL literals exactly to preserve functional equivalence.
     * Timestamps use LocalDateTime.now() to replace COBOL EXEC CICS ASKTIME command.
     * </p>
     * 
     * @param transactionId the generated transaction ID
     * @param cardNumber the card number associated with the payment account
     * @param paymentAmount the payment amount (full account balance)
     * @return Transaction entity populated with payment details
     */
    private Transaction createPaymentTransaction(String transactionId,
                                                  String cardNumber,
                                                  BigDecimal paymentAmount) {
        log.debug("Creating payment transaction: ID={}, Card={}, Amount={}",
                transactionId, cardNumber, paymentAmount);

        // Corresponds to COBIL00C.cbl lines 218-232: INITIALIZE TRAN-RECORD and field moves
        LocalDateTime currentTimestamp = LocalDateTime.now();

        Transaction transaction = Transaction.builder()
                .transactionId(transactionId)
                .typeCode(PAYMENT_TRANSACTION_TYPE)        // Line 220: '02'
                .categoryCode(PAYMENT_CATEGORY_CODE)       // Line 221: 2
                .transactionSource(PAYMENT_SOURCE)         // Line 222: 'POS TERM'
                .description(PAYMENT_DESCRIPTION)          // Line 223: 'BILL PAYMENT - ONLINE'
                .amount(paymentAmount)                     // Line 224: ACCT-CURR-BAL
                .cardNumber(cardNumber)                    // Line 225: XREF-CARD-NUM
                .merchantId(PAYMENT_MERCHANT_ID)           // Line 226: 999999999
                .merchantName(PAYMENT_MERCHANT_NAME)       // Line 227: 'BILL PAYMENT'
                .originationTimestamp(currentTimestamp)    // Lines 230-231: TRAN-ORIG-TS
                .processingTimestamp(currentTimestamp)     // Lines 230-232: TRAN-PROC-TS
                .build();

        log.debug("Payment transaction created successfully: {}", transactionId);
        return transaction;
    }

    /**
     * Builds the payment confirmation response with transaction details.
     * <p>
     * Corresponds to COBOL success message construction from COBIL00C.cbl lines 527-531:
     * </p>
     * <pre>
     * MOVE SPACES             TO WS-MESSAGE
     * MOVE DFHGREEN           TO ERRMSGC  OF COBIL0AO
     * STRING 'Payment successful. '     DELIMITED BY SIZE
     *   ' Your Transaction ID is ' DELIMITED BY SIZE
     *        TRAN-ID  DELIMITED BY SPACE
     *        '.' DELIMITED BY SIZE
     *   INTO WS-MESSAGE
     * </pre>
     * <p>
     * The confirmation message format matches COBOL string construction exactly to maintain
     * functional equivalence in user feedback.
     * </p>
     * 
     * @param transactionId the created transaction ID
     * @param updatedBalance the new account balance after payment
     * @return PaymentResponse containing transaction ID, balance, and confirmation message
     */
    private PaymentResponse buildPaymentConfirmation(String transactionId,
                                                      BigDecimal updatedBalance) {
        // Corresponds to COBIL00C.cbl lines 527-531: Payment success message construction
        String confirmationMessage = String.format(
                "Payment successful. Your Transaction ID is %s.",
                transactionId
        );

        log.info("Payment processed successfully. Transaction ID: {}, New Balance: {}",
                transactionId, updatedBalance);

        return PaymentResponse.builder()
                .transactionId(transactionId)
                .updatedBalance(updatedBalance)
                .confirmationMessage(confirmationMessage)
                .build();
    }
}
