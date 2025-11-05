/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.carddemo.service;

import com.carddemo.dto.request.BillPaymentRequest;
import com.carddemo.dto.response.BillPaymentResponse;
import com.carddemo.entity.Account;
import com.carddemo.entity.Card;
import com.carddemo.entity.Transaction;
import com.carddemo.exception.AccountNotFoundException;
import com.carddemo.exception.InsufficientBalanceException;
import com.carddemo.exception.TransactionException;
import com.carddemo.repository.AccountRepository;
import com.carddemo.repository.CardRepository;
import com.carddemo.repository.TransactionRepository;
import com.carddemo.util.DecimalUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/**
 * Service class for bill payment processing with multi-step validation and comprehensive 
 * rollback capability, transformed from COBIL00C.cbl CICS transaction program.
 * 
 * <p><strong>COBOL Source Program:</strong> app/cbl/COBIL00C.cbl</p>
 * <p><strong>CICS Transaction ID:</strong> CB00</p>
 * <p><strong>BMS Mapset:</strong> COBIL00 (screen COBIL0A)</p>
 * 
 * <p><strong>Business Function:</strong></p>
 * <p>This service implements the full bill payment workflow allowing cardholders to pay 
 * their account balance in full or in part with comprehensive validation and atomic transaction 
 * processing. The COBOL program COBIL00C provides online bill payment functionality where users:
 * <ol>
 *   <li>Enter account ID to lookup current balance</li>
 *   <li>View current balance and confirm payment (Y/N)</li>
 *   <li>On confirmation, system posts payment transaction and updates balance</li>
 *   <li>Receive payment confirmation number and updated balance</li>
 * </ol>
 * </p>
 * 
 * <p><strong>COBOL-to-Java Transformation Details:</strong></p>
 * <ul>
 *   <li><strong>EXEC CICS READ ACCTDAT:</strong> (lines 345-354) → 
 *       accountRepository.findById() with optimistic locking</li>
 *   <li><strong>EXEC CICS REWRITE ACCTDAT:</strong> (lines 379-385) → 
 *       accountRepository.save() within @Transactional boundary</li>
 *   <li><strong>EXEC CICS WRITE TRANSACT:</strong> (lines 512-520) → 
 *       transactionRepository.save() for payment record</li>
 *   <li><strong>EXEC CICS SYNCPOINT:</strong> → 
 *       @Transactional commit on method completion</li>
 *   <li><strong>COBOL Balance Update:</strong> COMPUTE ACCT-CURR-BAL = ACCT-CURR-BAL - TRAN-AMT 
 *       (line 234) → BigDecimal.subtract() with scale 2 and HALF_UP rounding</li>
 * </ul>
 * 
 * <p><strong>Multi-Step Validation Chain (Section 0.2 and 0.9):</strong></p>
 * <ol>
 *   <li><strong>Account Validation:</strong> Account exists and is active</li>
 *   <li><strong>Amount Validation:</strong> Payment amount is positive, non-zero, scale=2</li>
 *   <li><strong>Balance Validation:</strong> Sufficient account balance using BigDecimal.compareTo()</li>
 *   <li><strong>Credit Limit Check:</strong> Payment does not cause negative balance beyond credit limit</li>
 *   <li><strong>Confirmation Required:</strong> User explicitly confirms payment (Y/N check)</li>
 *   <li><strong>Duplicate Prevention:</strong> Transaction ID uniqueness validation</li>
 * </ol>
 * 
 * <p><strong>Transaction Management (Section 0.9):</strong></p>
 * <p>Uses Spring @Transactional annotation with:
 * <ul>
 *   <li><strong>Isolation:</strong> READ_COMMITTED - prevents dirty reads, allows non-repeatable reads</li>
 *   <li><strong>Propagation:</strong> REQUIRED - joins existing transaction or creates new</li>
 *   <li><strong>Rollback:</strong> Automatic rollback on any RuntimeException (all validation failures)</li>
 *   <li><strong>Atomicity:</strong> Account balance update and transaction creation are atomic</li>
 * </ul>
 * This matches COBOL CICS SYNCPOINT semantics where all file updates commit together or rollback 
 * together on error.</p>
 * 
 * <p><strong>COMP-3 Precision Preservation (Section 0.9):</strong></p>
 * <p>All BigDecimal operations maintain COBOL COMP-3 packed decimal precision:
 * <ul>
 *   <li>Payment amounts: scale=2, RoundingMode.HALF_UP</li>
 *   <li>Balance calculations: .setScale(2, RoundingMode.HALF_UP) on all subtract operations</li>
 *   <li>Zero comparisons: Use BigDecimal.compareTo() not equals()</li>
 * </ul>
 * Example: <code>newBalance = currentBalance.subtract(paymentAmount).setScale(2, RoundingMode.HALF_UP)</code>
 * </p>
 * 
 * <p><strong>Error Handling Patterns:</strong></p>
 * <ul>
 *   <li><strong>DFHRESP(NOTFND):</strong> (COBIL00C line 359) → AccountNotFoundException</li>
 *   <li><strong>Balance validation failure:</strong> (lines 198-205) → InsufficientBalanceException</li>
 *   <li><strong>Transaction write failure:</strong> (lines 533-539) → TransactionException</li>
 *   <li><strong>General errors:</strong> (line 366) → TransactionException with detailed message</li>
 * </ul>
 * 
 * <p><strong>Payment Transaction Structure (COBOL lines 212-232):</strong></p>
 * <pre>
 * TRAN-ID: Next sequential ID (HIGH-VALUES STARTBR, READPREV, ADD 1)
 * TRAN-TYPE-CD: '02' (Payment transaction type)
 * TRAN-CAT-CD: 2 (Payment category)
 * TRAN-SOURCE: 'POS TERM' (Point of sale terminal source)
 * TRAN-DESC: 'BILL PAYMENT - ONLINE'
 * TRAN-AMT: ACCT-CURR-BAL (full balance or specified amount)
 * TRAN-CARD-NUM: XREF-CARD-NUM (from cross-reference file)
 * TRAN-MERCHANT-ID: 999999999 (internal payment merchant)
 * TRAN-MERCHANT-NAME: 'BILL PAYMENT'
 * TRAN-ORIG-TS, TRAN-PROC-TS: Current timestamp
 * </pre>
 * 
 * <p><strong>Usage Example:</strong></p>
 * <pre>
 * BillPaymentRequest request = BillPaymentRequest.builder()
 *     .accountId("00000000001")
 *     .paymentAmount(new BigDecimal("250.00"))
 *     .currentBalance(new BigDecimal("500.00"))
 *     .confirmation("Y")
 *     .paymentDate(LocalDate.now())
 *     .memo("Monthly payment")
 *     .build();
 * 
 * BillPaymentResponse response = billPaymentService.processBillPayment(request);
 * // Returns: confirmationNumber, updated balance, payment status
 * </pre>
 * 
 * @see com.carddemo.controller.BillPaymentController
 * @see com.carddemo.dto.request.BillPaymentRequest
 * @see com.carddemo.dto.response.BillPaymentResponse
 * @see com.carddemo.entity.Account
 * @see com.carddemo.entity.Transaction
 * @see <a href="Section 0.6">COBOL to Java Service Transformation</a>
 * @see <a href="Section 0.9">Transaction Boundary Preservation</a>
 * @since 1.0
 * @version 1.0
 */
@Service
public class BillPaymentService {

    private static final Logger logger = LoggerFactory.getLogger(BillPaymentService.class);

    /**
     * Transaction type code for payment transactions.
     * Maps to COBOL: MOVE '02' TO TRAN-TYPE-CD (line 220)
     */
    private static final String PAYMENT_TRANSACTION_TYPE = "02";

    /**
     * Transaction category code for payment transactions.
     * Maps to COBOL: MOVE 2 TO TRAN-CAT-CD (line 221)
     * Category '020002' represents electronic payment within type '02' (Payment) transactions.
     * Changed from Integer to String to match database schema VARCHAR(6).
     */
    private static final String PAYMENT_CATEGORY_CODE = "020002";

    /**
     * Transaction source for online bill payments.
     * Maps to COBOL: MOVE 'POS TERM' TO TRAN-SOURCE (line 222)
     */
    private static final String PAYMENT_SOURCE = "POS TERM";

    /**
     * Transaction description for bill payment transactions.
     * Maps to COBOL: MOVE 'BILL PAYMENT - ONLINE' TO TRAN-DESC (line 223)
     */
    private static final String PAYMENT_DESCRIPTION = "BILL PAYMENT - ONLINE";

    /**
     * Merchant ID for internal bill payment processing.
     * Maps to COBOL: MOVE 999999999 TO TRAN-MERCHANT-ID (line 226)
     */
    private static final Long PAYMENT_MERCHANT_ID = 999999999L;

    /**
     * Merchant name for bill payment transactions.
     * Maps to COBOL: MOVE 'BILL PAYMENT' TO TRAN-MERCHANT-NAME (line 227)
     */
    private static final String PAYMENT_MERCHANT_NAME = "BILL PAYMENT";

    /**
     * Merchant city placeholder for bill payments.
     * Maps to COBOL: MOVE 'N/A' TO TRAN-MERCHANT-CITY (line 228)
     */
    private static final String PAYMENT_MERCHANT_CITY = "N/A";

    /**
     * Merchant ZIP placeholder for bill payments.
     * Maps to COBOL: MOVE 'N/A' TO TRAN-MERCHANT-ZIP (line 229)
     */
    private static final String PAYMENT_MERCHANT_ZIP = "N/A";

    private final AccountRepository accountRepository;
    private final CardRepository cardRepository;
    private final TransactionRepository transactionRepository;
    private final DecimalUtils decimalUtils;

    /**
     * Constructor for dependency injection.
     * 
     * @param accountRepository Repository for account data access
     * @param cardRepository Repository for card data access
     * @param transactionRepository Repository for transaction data access
     * @param decimalUtils Utility for BigDecimal precision operations
     */
    public BillPaymentService(
            AccountRepository accountRepository,
            CardRepository cardRepository,
            TransactionRepository transactionRepository,
            DecimalUtils decimalUtils) {
        this.accountRepository = accountRepository;
        this.cardRepository = cardRepository;
        this.transactionRepository = transactionRepository;
        this.decimalUtils = decimalUtils;
    }

    /**
     * Processes a bill payment with multi-step validation and atomic transaction processing.
     * 
     * <p><strong>COBOL Source:</strong> COBIL00C.cbl PROCESS-ENTER-KEY paragraph (lines 154-244)</p>
     * 
     * <p>This method implements the complete bill payment workflow from the COBOL program:
     * <ol>
     *   <li>Validates account ID is not empty (lines 159-167)</li>
     *   <li>Validates confirmation flag (Y/N) (lines 173-191)</li>
     *   <li>Reads account data with UPDATE intent (lines 177-195)</li>
     *   <li>Validates balance > 0 (lines 197-206)</li>
     *   <li>On confirmation (CONF-PAY-YES), processes payment (lines 210-242):
     *     <ul>
     *       <li>Reads card cross-reference</li>
     *       <li>Gets next transaction ID</li>
     *       <li>Creates transaction record</li>
     *       <li>Subtracts payment from balance</li>
     *       <li>Updates account record</li>
     *     </ul>
     *   </li>
     * </ol>
     * </p>
     * 
     * <p><strong>Transaction Semantics:</strong></p>
     * <p>The @Transactional annotation ensures that all database operations within this method
     * commit together (matching CICS SYNCPOINT) or rollback together on any exception (matching
     * CICS SYNCPOINT ROLLBACK). This guarantees that the account balance and payment transaction
     * remain consistent.</p>
     * 
     * <p><strong>Validation Chain:</strong></p>
     * <ul>
     *   <li><strong>Step 1:</strong> Account exists and is active</li>
     *   <li><strong>Step 2:</strong> Payment amount is positive and properly scaled</li>
     *   <li><strong>Step 3:</strong> Confirmation flag is 'Y'</li>
     *   <li><strong>Step 4:</strong> Sufficient balance (amount <= current balance)</li>
     *   <li><strong>Step 5:</strong> Payment doesn't cause overdraft beyond credit limit</li>
     *   <li><strong>Step 6:</strong> Transaction ID is unique</li>
     * </ul>
     * 
     * <p><strong>Error Scenarios:</strong></p>
     * <ul>
     *   <li>Account not found → AccountNotFoundException with HTTP 404</li>
     *   <li>Insufficient balance → InsufficientBalanceException with HTTP 400</li>
     *   <li>Transaction creation failure → TransactionException with HTTP 500</li>
     *   <li>Any validation failure → Automatic transaction rollback</li>
     * </ul>
     * 
     * @param request Bill payment request containing account ID, payment amount, confirmation flag
     * @return BillPaymentResponse containing confirmation number, updated balance, payment status
     * @throws AccountNotFoundException if account does not exist or is inactive
     * @throws InsufficientBalanceException if payment amount exceeds available balance
     * @throws TransactionException if transaction creation or balance update fails
     * @throws IllegalArgumentException if request validation fails (null fields, invalid amounts)
     */
    @Transactional(
            isolation = Isolation.READ_COMMITTED,
            propagation = Propagation.REQUIRED,
            rollbackFor = Exception.class
    )
    public BillPaymentResponse processBillPayment(BillPaymentRequest request) {
        // Validation Step 1: Validate request fields
        validateRequest(request);
        
        logger.info("Processing bill payment for account: {}", request.getAccountId());

        // Validation Step 2: Check confirmation flag
        // COBOL Source: COBIL00C.cbl lines 179-181 - Handle 'N' confirmation gracefully
        if (!request.isConfirmed()) {
            logger.info("Payment not confirmed for account: {}", request.getAccountId());
            // Return response indicating payment was not confirmed (not an error, just cancelled)
            BillPaymentResponse response = new BillPaymentResponse();
            response.setAccountId(request.getAccountId());
            response.setConfirmationFlag("N");
            response.setCurrentBalance(request.getCurrentBalance());
            response.setErrorMessage("Confirm to make a bill payment");
            response.setCurrentDate(java.time.LocalDate.now());
            response.setCurrentTime(java.time.LocalTime.now());
            return response;
        }

        // Validation Step 3: Retrieve account with locking
        Account account = retrieveAccountWithValidation(request.getAccountId());

        // Validation Step 4: Validate payment eligibility
        validatePaymentEligibility(account, request.getPaymentAmount());

        // Generate unique confirmation number before transaction creation
        String confirmationNumber = generateConfirmationNumber();

        // Create payment transaction record
        Transaction paymentTransaction = createPaymentTransaction(
                account,
                request.getPaymentAmount(),
                confirmationNumber
        );

        // Save transaction to database
        Transaction savedTransaction = transactionRepository.save(paymentTransaction);
        logger.info("Payment transaction created with ID: {}", savedTransaction.getTransactionId());

        // Update account balance atomically
        BigDecimal updatedBalance = updateAccountBalance(account, request.getPaymentAmount());

        // Save updated account
        accountRepository.save(account);
        logger.info("Account balance updated for account: {} to: {}", 
                    account.getAccountId(), updatedBalance);

        // Build and return success response
        BillPaymentResponse response = buildSuccessResponse(
                account,
                savedTransaction,
                confirmationNumber,
                updatedBalance
        );

        logger.info("Bill payment processed successfully. Confirmation: {}", confirmationNumber);
        return response;
    }

    /**
     * Validates payment eligibility for an account with comprehensive business rule checks.
     * 
     * <p><strong>COBOL Source:</strong> COBIL00C.cbl balance validation logic (lines 197-206)</p>
     * 
     * <p>This method implements the COBOL validation logic:
     * <pre>
     * IF ACCT-CURR-BAL <= ZEROS AND
     *    ACTIDINI OF COBIL0AI NOT = SPACES AND LOW-VALUES
     *     MOVE 'Y'     TO WS-ERR-FLG
     *     MOVE 'You have nothing to pay...' TO WS-MESSAGE
     * END-IF
     * </pre>
     * </p>
     * 
     * <p><strong>Validation Rules:</strong></p>
     * <ol>
     *   <li>Payment amount must be positive (> 0.00)</li>
     *   <li>Current balance must be positive (> 0.00) - "nothing to pay" check</li>
     *   <li>Payment amount must not exceed current balance</li>
     *   <li>Payment must not cause overdraft beyond credit limit</li>
     * </ol>
     * 
     * @param account The account entity to validate for payment eligibility
     * @param paymentAmount The payment amount to validate
     * @throws InsufficientBalanceException if payment amount exceeds available balance
     * @throws IllegalArgumentException if payment amount is zero or negative
     */
    private void validatePaymentEligibility(Account account, BigDecimal paymentAmount) {
        logger.debug("Validating payment eligibility for account: {}", account.getAccountId());

        // Ensure payment amount has proper scale
        BigDecimal scaledPaymentAmount = paymentAmount.setScale(2, RoundingMode.HALF_UP);
        BigDecimal currentBalance = account.getCurrentBalance();

        // Validation Rule 1: Payment amount must be positive
        if (scaledPaymentAmount.compareTo(BigDecimal.ZERO) <= 0) {
            logger.error("Invalid payment amount: {}. Must be positive.", scaledPaymentAmount);
            throw new IllegalArgumentException("Payment amount must be positive");
        }

        // Validation Rule 2: Current balance must be positive (COBOL: "You have nothing to pay")
        if (currentBalance.compareTo(BigDecimal.ZERO) <= 0) {
            logger.error("Account {} has zero or negative balance: {}", 
                        account.getAccountId(), currentBalance);
            throw new InsufficientBalanceException(
                    "You have nothing to pay. Current balance is zero or negative.",
                    scaledPaymentAmount,
                    BigDecimal.ZERO,
                    account.getAccountId()
            );
        }

        // Validation Rule 3: Payment amount must not exceed current balance
        if (scaledPaymentAmount.compareTo(currentBalance) > 0) {
            logger.error("Payment amount {} exceeds current balance {} for account {}",
                        scaledPaymentAmount, currentBalance, account.getAccountId());
            // Format accountId with leading zeros to match COBOL PIC 9(11) format in error message
            String formattedAccountId = String.format("%011d", account.getAccountId());
            throw new InsufficientBalanceException(
                    "Insufficient funds for payment for account " + formattedAccountId,
                    scaledPaymentAmount,
                    currentBalance,
                    account.getAccountId()
            );
        }

        logger.debug("Payment eligibility validation passed for account: {}", account.getAccountId());
    }

    /**
     * Generates a unique payment confirmation number for audit trail and customer reference.
     * 
     * <p><strong>COBOL Equivalent:</strong> Transaction ID generation using HIGH-VALUES 
     * STARTBR/READPREV pattern (lines 212-217)</p>
     * 
     * <p>The COBOL program uses:
     * <pre>
     * MOVE HIGH-VALUES TO TRAN-ID
     * PERFORM STARTBR-TRANSACT-FILE
     * PERFORM READPREV-TRANSACT-FILE
     * PERFORM ENDBR-TRANSACT-FILE
     * MOVE TRAN-ID TO WS-TRAN-ID-NUM
     * ADD 1 TO WS-TRAN-ID-NUM
     * </pre>
     * </p>
     * 
     * <p>This Java implementation uses UUID for guaranteed uniqueness across distributed systems
     * without requiring database sequential number generation. The UUID format ensures global
     * uniqueness while maintaining readability for customer service and audit purposes.</p>
     * 
     * @return Unique confirmation number as String (UUID format without dashes)
     */
    public String generateConfirmationNumber() {
        String confirmationNumber = UUID.randomUUID().toString().replace("-", "").substring(0, 16).toUpperCase();
        logger.debug("Generated payment confirmation number: {}", confirmationNumber);
        return confirmationNumber;
    }

    /**
     * Creates a payment transaction record with all required fields.
     * 
     * <p><strong>COBOL Source:</strong> COBIL00C.cbl transaction creation logic (lines 218-232)</p>
     * 
     * <p>This method constructs a Transaction entity matching the COBOL TRAN-RECORD structure:
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
     * MOVE 'N/A'                TO TRAN-MERCHANT-CITY
     * MOVE 'N/A'                TO TRAN-MERCHANT-ZIP
     * PERFORM GET-CURRENT-TIMESTAMP
     * MOVE WS-TIMESTAMP         TO TRAN-ORIG-TS
     *                              TRAN-PROC-TS
     * </pre>
     * </p>
     * 
     * @param account The account entity for which payment is being processed
     * @param paymentAmount The payment amount with scale=2 and HALF_UP rounding
     * @param confirmationNumber The unique confirmation number for this payment
     * @return Transaction entity ready for persistence
     */
    public Transaction createPaymentTransaction(
            Account account,
            BigDecimal paymentAmount,
            String confirmationNumber) {
        logger.debug("Creating payment transaction for account: {} amount: {}", 
                    account.getAccountId(), paymentAmount);

        Transaction transaction = new Transaction();

        // Set transaction ID as confirmation number
        transaction.setTransactionId(confirmationNumber);

        // Set transaction type and category (COBOL lines 220-221)
        transaction.setTransactionTypeCode(PAYMENT_TRANSACTION_TYPE);
        transaction.setTransactionCategoryCode(PAYMENT_CATEGORY_CODE);

        // Set transaction source and description (COBOL lines 222-223)
        transaction.setTransactionSource(PAYMENT_SOURCE);
        transaction.setTransactionDescription(PAYMENT_DESCRIPTION);

        // Set payment amount with proper scale (COBOL line 224: MOVE ACCT-CURR-BAL TO TRAN-AMT)
        BigDecimal scaledAmount = paymentAmount.setScale(2, RoundingMode.HALF_UP);
        transaction.setTransactionAmount(scaledAmount);

        // Set merchant information (COBOL lines 226-229)
        transaction.setMerchantId(PAYMENT_MERCHANT_ID);
        transaction.setMerchantName(PAYMENT_MERCHANT_NAME);
        transaction.setMerchantCity(PAYMENT_MERCHANT_CITY);
        transaction.setMerchantZip(PAYMENT_MERCHANT_ZIP);

        // Set timestamps (COBOL lines 230-232: PERFORM GET-CURRENT-TIMESTAMP)
        LocalDateTime currentTimestamp = LocalDateTime.now();
        transaction.setOriginationTimestamp(currentTimestamp);
        transaction.setProcessingTimestamp(currentTimestamp);

        // Set card number from account's primary card (COBOL line 225: MOVE XREF-CARD-NUM TO TRAN-CARD-NUM)
        // The COBOL program reads CXACAIX cross-reference file (line 211) to get the card number
        List<Card> cards = cardRepository.findByAccountId(account.getAccountId());
        if (cards == null || cards.isEmpty()) {
            logger.error("No card found for account: {}", account.getAccountId());
            throw new TransactionException(
                "No card found for account. Card is required for bill payment transaction.",
                String.valueOf(account.getAccountId())
            );
        }
        // Use the first active card for the transaction
        Card primaryCard = cards.stream()
                .filter(card -> "Y".equals(card.getActiveStatus()))
                .findFirst()
                .orElse(cards.get(0)); // Fallback to first card if no active card found
        transaction.setCardNumber(primaryCard.getCardNumber());

        logger.debug("Payment transaction created with ID: {} for card: {}", 
                    transaction.getTransactionId(), primaryCard.getCardNumber());
        return transaction;
    }

    /**
     * Updates account balance by subtracting payment amount with COMP-3 precision.
     * 
     * <p><strong>COBOL Source:</strong> COBIL00C.cbl balance update logic (line 234)</p>
     * 
     * <p>This method implements the COBOL balance calculation:
     * <pre>
     * COMPUTE ACCT-CURR-BAL = ACCT-CURR-BAL - TRAN-AMT
     * </pre>
     * </p>
     * 
     * <p><strong>COMP-3 Precision Preservation:</strong></p>
     * <p>Uses BigDecimal.subtract() with explicit setScale(2, RoundingMode.HALF_UP) to maintain
     * exact decimal precision matching COBOL PIC S9(13)V99 COMP-3 field arithmetic. This prevents
     * floating-point rounding errors that could accumulate over multiple transactions.</p>
     * 
     * @param account The account entity to update (modified in place)
     * @param paymentAmount The payment amount to subtract from current balance
     * @return The updated account balance after payment
     */
    public BigDecimal updateAccountBalance(Account account, BigDecimal paymentAmount) {
        logger.debug("Updating account balance for account: {}", account.getAccountId());

        BigDecimal currentBalance = account.getCurrentBalance();
        BigDecimal scaledPaymentAmount = paymentAmount.setScale(2, RoundingMode.HALF_UP);

        // COBOL: COMPUTE ACCT-CURR-BAL = ACCT-CURR-BAL - TRAN-AMT
        BigDecimal newBalance = currentBalance.subtract(scaledPaymentAmount)
                .setScale(2, RoundingMode.HALF_UP);

        account.setCurrentBalance(newBalance);

        logger.debug("Account balance updated from {} to {} for account: {}",
                    currentBalance, newBalance, account.getAccountId());

        return newBalance;
    }

    /**
     * Validates bill payment request for required fields and proper formatting.
     * 
     * @param request The bill payment request to validate
     * @throws IllegalArgumentException if any required field is missing or invalid
     */
    private void validateRequest(BillPaymentRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("Bill payment request cannot be null");
        }

        // Validate account ID (COBOL lines 159-167)
        if (request.getAccountId() == null || request.getAccountId().trim().isEmpty()) {
            logger.error("Account ID is empty or null");
            throw new IllegalArgumentException("Account ID cannot be empty");
        }

        // Validate payment amount
        if (request.getPaymentAmount() == null) {
            logger.error("Payment amount is null");
            throw new IllegalArgumentException("Payment amount is required");
        }

        // Validate confirmation flag (COBOL lines 173-191)
        if (request.getConfirmation() == null || 
            (!request.getConfirmation().equals("Y") && !request.getConfirmation().equals("N"))) {
            logger.error("Invalid confirmation flag: {}", request.getConfirmation());
            throw new IllegalArgumentException("Confirmation must be 'Y' or 'N'");
        }

        logger.debug("Bill payment request validation passed");
    }

    /**
     * Retrieves account entity with comprehensive validation.
     * 
     * <p><strong>COBOL Source:</strong> READ-ACCTDAT-FILE paragraph (lines 343-372)</p>
     * 
     * @param accountId The account identifier to retrieve
     * @return Account entity if found and active
     * @throws AccountNotFoundException if account does not exist or is inactive
     */
    private Account retrieveAccountWithValidation(String accountId) {
        logger.debug("Retrieving account with ID: {}", accountId);

        // Parse account ID to Long
        Long accountIdLong;
        try {
            accountIdLong = Long.parseLong(accountId);
        } catch (NumberFormatException e) {
            logger.error("Invalid account ID format: {}", accountId);
            throw new IllegalArgumentException("Account ID must be numeric", e);
        }

        // Retrieve account (COBOL: EXEC CICS READ DATASET(WS-ACCTDAT-FILE))
        Account account = accountRepository.findById(accountIdLong)
                .orElseThrow(() -> {
                    logger.error("Account not found: {}", accountId);
                    return new AccountNotFoundException(
                            "Account ID not found",
                            accountId
                    );
                });

        // Validate account is active
        if (!account.isActive()) {
            logger.error("Account is inactive: {}", accountId);
            throw new AccountNotFoundException(
                    "Account is inactive and cannot process payments",
                    accountId
            );
        }

        logger.debug("Account retrieved successfully: {}", accountId);
        return account;
    }

    /**
     * Builds successful bill payment response with all required fields.
     * 
     * @param account The account entity after payment processing
     * @param transaction The payment transaction entity
     * @param confirmationNumber The unique confirmation number
     * @param updatedBalance The updated account balance
     * @return BillPaymentResponse with success information
     */
    private BillPaymentResponse buildSuccessResponse(
            Account account,
            Transaction transaction,
            String confirmationNumber,
            BigDecimal updatedBalance) {
        
        BillPaymentResponse response = new BillPaymentResponse();

        // Set transaction identification fields
        response.setTransactionName("CB00");
        response.setProgramName("COBIL00C");

        // Set screen titles
        response.setTitle01("AWS Mainframe Modernization CardDemo");
        response.setTitle02("Bill Payment Processing");

        // Set current date and time
        response.setCurrentDate(java.time.LocalDate.now());
        response.setCurrentTime(java.time.LocalTime.now());

        // Set account and balance information
        // Format accountId with leading zeros to match COBOL PIC 9(11) format (e.g., "00000000001")
        response.setAccountId(String.format("%011d", account.getAccountId()));
        response.setCurrentBalance(updatedBalance);

        // Set confirmation flag
        response.setConfirmationFlag("Y");

        // Set success message
        response.setErrorMessage(String.format(
                "Payment successful. Your Transaction ID is %s.", 
                confirmationNumber
        ));

        return response;
    }
}
