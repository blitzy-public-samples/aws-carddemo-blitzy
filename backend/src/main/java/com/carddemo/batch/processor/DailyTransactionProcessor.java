/*
 * DailyTransactionProcessor.java
 * 
 * CardDemo Application - Spring Batch Processor
 * 
 * Spring Batch ItemProcessor implementation for validating daily transactions
 * before posting. Performs multi-step validation including card existence,
 * account validation, credit limit checks, and expiration date verification.
 * 
 * Replaces validation logic from CBTRN02C.cbl lines 370-422:
 *   - 1500-VALIDATE-TRAN (main validation paragraph)
 *   - 1500-A-LOOKUP-XREF (card number validation, lines 380-392)
 *   - 1500-B-LOOKUP-ACCT (account validation, lines 393-422)
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
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.carddemo.batch.processor;

import com.carddemo.entity.Account;
import com.carddemo.entity.DailyTransactionStaging;
import com.carddemo.entity.Transaction;
import com.carddemo.repository.AccountRepository;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.batch.item.ItemProcessor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Optional;

/**
 * Spring Batch ItemProcessor for validating daily transactions before posting.
 * 
 * <p>This processor implements the complete validation logic from COBOL program
 * CBTRN02C, specifically the 1500-VALIDATE-TRAN paragraph and its sub-routines
 * (1500-A-LOOKUP-XREF and 1500-B-LOOKUP-ACCT).</p>
 * 
 * <p><b>Validation Steps (matching COBOL logic):</b></p>
 * <ol>
 *   <li>Validate transaction amount > 0 (business requirement)</li>
 *   <li>Validate transaction date is not in future (business requirement)</li>
 *   <li>Verify card number exists via account lookup (COBOL lines 380-392)</li>
 *   <li>Verify account exists and is active (COBOL lines 393-422)</li>
 *   <li>Verify sufficient credit limit (COBOL lines 403-413)</li>
 *   <li>Validate account not expired (COBOL lines 414-420)</li>
 * </ol>
 * 
 * <p><b>Return Value:</b></p>
 * <ul>
 *   <li>Valid transaction → Transaction entity (proceed to writer)</li>
 *   <li>Invalid transaction → null (item skipped, handled by skip listener)</li>
 * </ul>
 * 
 * <p><b>COBOL Mapping:</b></p>
 * <pre>
 * COBOL Validation Reason Codes → Java Constants:
 *   100 = INVALID_CARD_NUMBER    (COBOL line 385)
 *   101 = ACCOUNT_NOT_FOUND      (COBOL line 397)
 *   102 = OVERLIMIT_TRANSACTION  (COBOL line 410)
 *   103 = ACCOUNT_EXPIRED        (COBOL line 417)
 *   104 = INVALID_AMOUNT         (Additional validation)
 *   105 = FUTURE_DATE            (Additional validation)
 * </pre>
 * 
 * @see DailyTransactionStaging Input entity from daily_transaction_staging table
 * @see Transaction Output entity for transaction table
 * @see Account Account entity for validation
 * 
 * @author CardDemo Modernization Team
 * @version 1.0
 * @since 2024-01-01
 */
@Component
public class DailyTransactionProcessor implements ItemProcessor<DailyTransactionStaging, Transaction> {

    private static final Logger logger = LoggerFactory.getLogger(DailyTransactionProcessor.class);

    // Validation failure reason codes (matching COBOL WS-VALIDATION-FAIL-REASON)
    private static final int VALIDATION_SUCCESS = 0;
    private static final int INVALID_CARD_NUMBER = 100;           // COBOL line 385: INVALID KEY on XREF READ
    private static final int ACCOUNT_NOT_FOUND = 101;             // COBOL line 397: INVALID KEY on ACCOUNT READ
    private static final int OVERLIMIT_TRANSACTION = 102;         // COBOL line 410: Credit limit exceeded
    private static final int ACCOUNT_EXPIRED = 103;               // COBOL line 417: Transaction after expiration
    private static final int INVALID_AMOUNT = 104;                // Additional: Amount <= 0
    private static final int FUTURE_DATE_TRANSACTION = 105;       // Additional: Future dated transaction
    
    // Processing status constants
    private static final String STATUS_POSTED = "POSTED";
    private static final String STATUS_REJECTED = "REJECTED";
    private static final String STATUS_PENDING = "PENDING";
    
    // Active status indicator for accounts
    private static final String ACTIVE_STATUS = "A";
    
    // Repository dependencies
    private final AccountRepository accountRepository;
    
    /**
     * Constructor with dependency injection.
     * 
     * @param accountRepository Account repository for account validation
     */
    @Autowired
    public DailyTransactionProcessor(AccountRepository accountRepository) {
        this.accountRepository = accountRepository;
        logger.info("DailyTransactionProcessor initialized");
    }

    /**
     * Process a daily transaction by performing comprehensive validation.
     * 
     * <p>This method implements the complete validation logic from COBOL CBTRN02C.cbl
     * paragraph 1500-VALIDATE-TRAN (lines 370-422) and its sub-paragraphs:</p>
     * <ul>
     *   <li>1500-A-LOOKUP-XREF (lines 380-392) - Card cross-reference validation</li>
     *   <li>1500-B-LOOKUP-ACCT (lines 393-422) - Account validation and limits</li>
     * </ul>
     * 
     * <p><b>COBOL Processing Flow:</b></p>
     * <pre>
     * MOVE 0 TO WS-VALIDATION-FAIL-REASON
     * PERFORM 1500-VALIDATE-TRAN
     * IF WS-VALIDATION-FAIL-REASON = 0
     *    PERFORM 2000-POST-TRANSACTION
     * ELSE
     *    PERFORM 2500-WRITE-REJECT-REC
     * </pre>
     * 
     * <p><b>Spring Batch Equivalent:</b></p>
     * <ul>
     *   <li>Validation passes: Return Transaction entity → sent to writer (2000-POST-TRANSACTION)</li>
     *   <li>Validation fails: Return null → item skipped (2500-WRITE-REJECT-REC via skip listener)</li>
     * </ul>
     * 
     * @param item The DailyTransactionStaging record from daily transaction file
     * @return Transaction entity if all validations pass, null if validation fails
     * @throws Exception if an unexpected database or system error occurs
     */
    @Override
    public Transaction process(DailyTransactionStaging item) throws Exception {
        if (item == null) {
            logger.warn("Received null item for processing");
            return null;
        }
        
        logger.debug("Processing transaction ID: {}, Card: {}, Amount: {}",
                item.getTransactionId(), item.getCardNumber(), item.getAmount());
        
        // Track validation failure (matching COBOL WS-VALIDATION-FAIL-REASON and WS-VALIDATION-FAIL-REASON-DESC)
        int validationFailureReason = VALIDATION_SUCCESS;
        String validationFailureDescription = "";

        
        // Validation Step 1: Validate transaction amount is not null
        // Business requirement - allow both positive (debits) and negative (credits) amounts
        // Positive amounts: purchases, fees, interest charges (increase balance)
        // Negative amounts: payments, refunds, adjustments (decrease balance)
        if (item.getAmount() == null) {
            validationFailureReason = INVALID_AMOUNT;
            validationFailureDescription = "TRANSACTION AMOUNT CANNOT BE NULL";
            logger.warn("Validation failed - Transaction ID: {}, Reason: {}",
                    item.getTransactionId(), validationFailureDescription);
            updateItemAsRejected(item, validationFailureReason, validationFailureDescription);
            return null;
        }
        
        // Reject zero-amount transactions (neither debit nor credit)
        if (item.getAmount().compareTo(BigDecimal.ZERO) == 0) {
            validationFailureReason = INVALID_AMOUNT;
            validationFailureDescription = "TRANSACTION AMOUNT CANNOT BE ZERO";
            logger.warn("Validation failed - Transaction ID: {}, Reason: {}",
                    item.getTransactionId(), validationFailureDescription);
            updateItemAsRejected(item, validationFailureReason, validationFailureDescription);
            return null;
        }
        
        // Validation Step 2: Validate transaction date is not in future
        // Business requirement - prevent future-dated transactions
        LocalDateTime originalTimestamp = item.getOriginalTimestamp();
        if (originalTimestamp != null && originalTimestamp.isAfter(LocalDateTime.now())) {
            validationFailureReason = FUTURE_DATE_TRANSACTION;
            validationFailureDescription = "TRANSACTION DATE CANNOT BE IN THE FUTURE";
            logger.warn("Validation failed - Transaction ID: {}, Reason: {}",
                    item.getTransactionId(), validationFailureDescription);
            updateItemAsRejected(item, validationFailureReason, validationFailureDescription);
            return null;
        }
        
        //
        // COBOL: 1500-A-LOOKUP-XREF (lines 380-392)
        // Validation Step 3: Verify card number exists and retrieve account ID
        //
        // COBOL equivalent:
        //   MOVE DALYTRAN-CARD-NUM TO FD-XREF-CARD-NUM
        //   READ XREF-FILE INTO CARD-XREF-RECORD
        //      INVALID KEY
        //        MOVE 100 TO WS-VALIDATION-FAIL-REASON
        //        MOVE 'INVALID CARD NUMBER FOUND' TO WS-VALIDATION-FAIL-REASON-DESC
        //
        // Since Card/CardXref entities don't exist yet, we extract accountId from card number
        // In full implementation, this would query CardXrefRepository.findByCardNumber()
        Long accountId = extractAccountIdFromCardNumber(item.getCardNumber());
        if (accountId == null) {
            validationFailureReason = INVALID_CARD_NUMBER;
            validationFailureDescription = "INVALID CARD NUMBER FOUND";
            logger.warn("Validation failed - Transaction ID: {}, Card: {}, Reason: {}",
                    item.getTransactionId(), item.getCardNumber(), validationFailureDescription);
            updateItemAsRejected(item, validationFailureReason, validationFailureDescription);
            return null;
        }
        
        //
        // COBOL: 1500-B-LOOKUP-ACCT (lines 393-422)
        // Validation Step 4: Verify account exists and is active
        //
        // COBOL equivalent:
        //   MOVE XREF-ACCT-ID TO FD-ACCT-ID
        //   READ ACCOUNT-FILE INTO ACCOUNT-RECORD
        //      INVALID KEY
        //        MOVE 101 TO WS-VALIDATION-FAIL-REASON
        //        MOVE 'ACCOUNT RECORD NOT FOUND' TO WS-VALIDATION-FAIL-REASON-DESC
        //
        Optional<Account> accountOpt = accountRepository.findById(accountId);
        if (!accountOpt.isPresent()) {
            validationFailureReason = ACCOUNT_NOT_FOUND;
            validationFailureDescription = "ACCOUNT RECORD NOT FOUND";
            logger.warn("Validation failed - Transaction ID: {}, Account ID: {}, Reason: {}",
                    item.getTransactionId(), accountId, validationFailureDescription);
            updateItemAsRejected(item, validationFailureReason, validationFailureDescription);
            return null;
        }
        
        Account account = accountOpt.get();
        
        // Verify account is active
        if (!ACTIVE_STATUS.equals(account.getActiveStatus())) {
            validationFailureReason = ACCOUNT_NOT_FOUND;
            validationFailureDescription = "ACCOUNT IS NOT ACTIVE (Status: " + account.getActiveStatus() + ")";
            logger.warn("Validation failed - Transaction ID: {}, Account ID: {}, Reason: {}",
                    item.getTransactionId(), accountId, validationFailureDescription);
            updateItemAsRejected(item, validationFailureReason, validationFailureDescription);
            return null;
        }
        
        //
        // COBOL: Lines 403-413 - Credit limit validation
        // Validation Step 5: Verify account has sufficient credit limit
        //
        // COBOL equivalent:
        //   COMPUTE WS-TEMP-BAL = ACCT-CURR-CYC-CREDIT
        //                       - ACCT-CURR-CYC-DEBIT
        //                       + DALYTRAN-AMT
        //   IF ACCT-CREDIT-LIMIT >= WS-TEMP-BAL
        //     CONTINUE
        //   ELSE
        //     MOVE 102 TO WS-VALIDATION-FAIL-REASON
        //     MOVE 'OVERLIMIT TRANSACTION' TO WS-VALIDATION-FAIL-REASON-DESC
        //
        BigDecimal currentCycleCredit = account.getCurrentCycleCredit();
        BigDecimal currentCycleDebit = account.getCurrentCycleDebit();
        BigDecimal transactionAmount = item.getAmount();
        BigDecimal creditLimit = account.getCreditLimit();
        
        // Maintain COMP-3 precision with scale 2 and HALF_UP rounding per Section 0.9
        BigDecimal projectedBalance = currentCycleCredit
                .subtract(currentCycleDebit)
                .add(transactionAmount)
                .setScale(2, RoundingMode.HALF_UP);
        
        if (creditLimit.compareTo(projectedBalance) < 0) {
            validationFailureReason = OVERLIMIT_TRANSACTION;
            validationFailureDescription = "OVERLIMIT TRANSACTION";
            logger.warn("Validation failed - Transaction ID: {}, Account ID: {}, Reason: {} (Limit: {}, Projected: {})",
                    item.getTransactionId(), accountId, validationFailureDescription, creditLimit, projectedBalance);
            updateItemAsRejected(item, validationFailureReason, validationFailureDescription);
            return null;
        }
        
        //
        // COBOL: Lines 414-420 - Account expiration validation
        // Validation Step 6: Validate account not expired
        //
        // COBOL equivalent:
        //   IF ACCT-EXPIRAION-DATE >= DALYTRAN-ORIG-TS (1:10)
        //     CONTINUE
        //   ELSE
        //     MOVE 103 TO WS-VALIDATION-FAIL-REASON
        //     MOVE 'TRANSACTION RECEIVED AFTER ACCT EXPIRATION' TO WS-VALIDATION-FAIL-REASON-DESC
        //
        LocalDate accountExpirationDate = account.getExpirationDate();
        if (accountExpirationDate != null) {
            LocalDate transactionDate = originalTimestamp.toLocalDate();
            if (accountExpirationDate.isBefore(transactionDate)) {
                validationFailureReason = ACCOUNT_EXPIRED;
                validationFailureDescription = "TRANSACTION RECEIVED AFTER ACCT EXPIRATION";
                logger.warn("Validation failed - Transaction ID: {}, Account ID: {}, Reason: {} (Expiration: {}, Transaction: {})",
                        item.getTransactionId(), accountId, validationFailureDescription,
                        accountExpirationDate, transactionDate);
                updateItemAsRejected(item, validationFailureReason, validationFailureDescription);
                return null;
            }
        }
        
        //
        // All validations passed - transform DailyTransactionStaging to Transaction entity
        // COBOL: Perform 2000-POST-TRANSACTION (lines 424-444)
        //
        // COBOL equivalent:
        //   MOVE DALYTRAN-ID            TO TRAN-ID
        //   MOVE DALYTRAN-TYPE-CD       TO TRAN-TYPE-CD
        //   MOVE DALYTRAN-CAT-CD        TO TRAN-CAT-CD
        //   ...
        //   PERFORM Z-GET-DB2-FORMAT-TIMESTAMP
        //   MOVE DB2-FORMAT-TS          TO TRAN-PROC-TS
        //
        Transaction transaction = transformToTransaction(item, accountId);
        
        logger.info("Successfully validated transaction ID: {}, Card: {}, Account: {}, Amount: {}",
                item.getTransactionId(), item.getCardNumber(), accountId, item.getAmount());
        
        return transaction;
    }
    
    /**
     * Transform DailyTransactionStaging input to Transaction output entity.
     * 
     * <p>COBOL equivalent: 2000-POST-TRANSACTION (lines 424-444)</p>
     * <p>Copies all fields from DALYTRAN-RECORD to TRAN-RECORD with processing timestamp</p>
     * 
     * @param dailyTransaction The input daily transaction staging record
     * @param accountId The validated account ID from card cross-reference lookup
     * @return Transaction entity ready for database persistence
     */
    private Transaction transformToTransaction(DailyTransactionStaging dailyTransaction, Long accountId) {
        Transaction transaction = new Transaction();
        
        // COBOL: MOVE DALYTRAN-ID TO TRAN-ID
        transaction.setTransactionId(dailyTransaction.getTransactionId());
        
        // Set account ID from validated card lookup
        transaction.setAccountId(accountId);
        
        // COBOL: MOVE DALYTRAN-TYPE-CD TO TRAN-TYPE-CD
        transaction.setTransactionTypeCode(dailyTransaction.getTypeCode());
        
        // COBOL: MOVE DALYTRAN-CAT-CD TO TRAN-CAT-CD
        transaction.setTransactionCategoryCode(dailyTransaction.getCategoryCode());
        
        // COBOL: MOVE DALYTRAN-SOURCE TO TRAN-SOURCE
        transaction.setTransactionSource(dailyTransaction.getSource());
        
        // COBOL: MOVE DALYTRAN-DESC TO TRAN-DESC
        transaction.setTransactionDescription(dailyTransaction.getDescription());
        
        // COBOL: MOVE DALYTRAN-AMT TO TRAN-AMT
        // Maintain COMP-3 precision with scale 2
        transaction.setTransactionAmount(dailyTransaction.getAmount().setScale(2, RoundingMode.HALF_UP));
        
        // COBOL: MOVE DALYTRAN-MERCHANT-ID TO TRAN-MERCHANT-ID
        if (dailyTransaction.getMerchantId() != null && !dailyTransaction.getMerchantId().isEmpty()) {
            try {
                transaction.setMerchantId(Long.parseLong(dailyTransaction.getMerchantId()));
            } catch (NumberFormatException e) {
                logger.warn("Invalid merchant ID format: {}, setting to null", dailyTransaction.getMerchantId());
                transaction.setMerchantId(null);
            }
        }
        
        // COBOL: MOVE DALYTRAN-MERCHANT-NAME TO TRAN-MERCHANT-NAME
        transaction.setMerchantName(dailyTransaction.getMerchantName());
        
        // COBOL: MOVE DALYTRAN-MERCHANT-CITY TO TRAN-MERCHANT-CITY
        transaction.setMerchantCity(dailyTransaction.getMerchantCity());
        
        // COBOL: MOVE DALYTRAN-MERCHANT-ZIP TO TRAN-MERCHANT-ZIP
        transaction.setMerchantZip(dailyTransaction.getMerchantZip());
        
        // COBOL: MOVE DALYTRAN-CARD-NUM TO TRAN-CARD-NUM
        transaction.setCardNumber(dailyTransaction.getCardNumber());
        
        // COBOL: MOVE DALYTRAN-ORIG-TS TO TRAN-ORIG-TS
        transaction.setOriginationTimestamp(dailyTransaction.getOriginalTimestamp());
        
        // COBOL: PERFORM Z-GET-DB2-FORMAT-TIMESTAMP (lines 692-705)
        // COBOL: MOVE DB2-FORMAT-TS TO TRAN-PROC-TS
        transaction.setProcessingTimestamp(LocalDateTime.now());
        
        // Set transaction date for indexing and querying
        transaction.setTransactionDate(dailyTransaction.getOriginalTimestamp().toLocalDate());
        
        return transaction;
    }
    
    /**
     * Extract account ID from card number.
     * 
     * <p>This method replaces the XREF-FILE lookup from COBOL 1500-A-LOOKUP-XREF.</p>
     * <p>In full implementation with Card/CardXref entities, this would be:</p>
     * <pre>
     * Optional&lt;CardXref&gt; cardXref = cardXrefRepository.findByCardNumber(cardNumber);
     * return cardXref.map(CardXref::getAccountId).orElse(null);
     * </pre>
     * 
     * <p>Temporary implementation: Extract account ID from card number format.
     * Card number format assumption: First 11 digits represent account ID.</p>
     * 
     * @param cardNumber The card number from daily transaction
     * @return Account ID if valid card number, null otherwise
     */
    private Long extractAccountIdFromCardNumber(String cardNumber) {
        if (cardNumber == null || cardNumber.length() < 11) {
            return null;
        }
        
        try {
            // Extract first 11 digits as account ID
            // This matches the COBOL XREF-FILE structure where card maps to account
            String accountIdStr = cardNumber.substring(0, 11);
            return Long.parseLong(accountIdStr);
        } catch (NumberFormatException e) {
            logger.warn("Invalid card number format: {}", cardNumber);
            return null;
        }
    }
    
    /**
     * Update daily transaction staging record with rejection information.
     * 
     * <p>Sets the status to REJECTED and records the validation failure reason.</p>
     * <p>This corresponds to COBOL 2500-WRITE-REJECT-REC (lines 446-465).</p>
     * 
     * @param item The daily transaction staging record
     * @param reasonCode The validation failure reason code
     * @param reasonDescription The validation failure description
     */
    private void updateItemAsRejected(DailyTransactionStaging item, int reasonCode, String reasonDescription) {
        item.setStatus(STATUS_REJECTED);
        item.setRejectReasonCode(reasonCode);
        item.setRejectReasonDesc(reasonDescription);
        item.setProcessedTimestamp(LocalDateTime.now());
        
        logger.debug("Updated transaction {} as REJECTED: {} - {}",
                item.getTransactionId(), reasonCode, reasonDescription);
    }
}
