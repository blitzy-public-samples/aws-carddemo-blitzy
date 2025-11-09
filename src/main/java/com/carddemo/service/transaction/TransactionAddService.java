/*
 * TransactionAddService.java
 * 
 * Transaction creation and validation service implementing card authorization validation,
 * available credit validation with exact BigDecimal arithmetic matching COBOL COMP-3 precision,
 * merchant validation, transaction type business rules, atomic account balance updates,
 * transaction category balance updates, transaction record creation with unique ID generation,
 * and comprehensive error handling.
 * 
 * Transforms COBOL COTRN02C.cbl multi-file update logic to Spring @Transactional service
 * ensuring ACID properties matching CICS SYNCPOINT behavior.
 *
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.carddemo.service.transaction;

import com.carddemo.entity.Account;
import com.carddemo.entity.Card;
import com.carddemo.entity.Transaction;
import com.carddemo.entity.TransactionCategoryBalance;
import com.carddemo.repository.AccountRepository;
import com.carddemo.repository.CardRepository;
import com.carddemo.repository.TransactionCategoryBalanceRepository;
import com.carddemo.repository.TransactionRepository;
import com.carddemo.dto.request.TransactionRequest;
import com.carddemo.dto.response.TransactionResponse;
import com.carddemo.exception.BusinessLogicException;
import com.carddemo.exception.ResourceNotFoundException;
import com.carddemo.exception.ValidationException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Service class for adding new credit card transactions.
 * 
 * Replaces COBOL program COTRN02C.cbl with equivalent Java implementation
 * that maintains identical business logic while leveraging Spring Boot
 * framework capabilities.
 * 
 * Business Logic Flow:
 * 1. Validate card exists and retrieve card details (replaces READ CCXREF)
 * 2. Validate card is active and not expired
 * 3. Retrieve associated account (replaces READ CXACAIX)
 * 4. Validate available credit with exact BigDecimal arithmetic
 * 5. Generate unique transaction ID (replaces STARTBR/READPREV logic)
 * 6. Create transaction record (replaces WRITE TRANSACT)
 * 7. Update account balance (replaces REWRITE ACCTDAT - enhancement)
 * 8. Update transaction category balance (enhancement for analytics)
 * 
 * All operations execute within a single @Transactional boundary ensuring
 * atomic commit/rollback matching CICS SYNCPOINT behavior.
 */
@Slf4j
@Service
public class TransactionAddService {

    private final TransactionRepository transactionRepository;
    private final CardRepository cardRepository;
    private final AccountRepository accountRepository;
    private final TransactionCategoryBalanceRepository categoryBalanceRepository;

    // Date formatter matching COBOL date format YYYY-MM-DD
    private static final DateTimeFormatter DATE_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd");
    
    // Scale for all monetary BigDecimal operations (2 decimal places)
    private static final int DECIMAL_SCALE = 2;
    
    // Rounding mode matching COBOL COMP-3 arithmetic behavior
    private static final RoundingMode DECIMAL_ROUNDING = RoundingMode.HALF_UP;

    /**
     * Constructor with dependency injection.
     * Uses constructor injection (best practice) rather than field injection.
     * 
     * @param transactionRepository Repository for transaction CRUD operations
     * @param cardRepository Repository for card lookups
     * @param accountRepository Repository for account operations
     * @param categoryBalanceRepository Repository for category balance tracking
     */
    public TransactionAddService(
            TransactionRepository transactionRepository,
            CardRepository cardRepository,
            AccountRepository accountRepository,
            TransactionCategoryBalanceRepository categoryBalanceRepository) {
        this.transactionRepository = transactionRepository;
        this.cardRepository = cardRepository;
        this.accountRepository = accountRepository;
        this.categoryBalanceRepository = categoryBalanceRepository;
    }

    /**
     * Creates a new credit card transaction with comprehensive validation and processing.
     * 
     * This method implements the complete transaction add flow from COBOL COTRN02C.cbl:
     * - VALIDATE-INPUT-KEY-FIELDS paragraph → validateCardAndAccount
     * - VALIDATE-INPUT-DATA-FIELDS paragraph → Bean Validation + custom validation
     * - ADD-TRANSACTION paragraph → transaction creation logic
     * - File I/O operations → JPA repository operations
     * 
     * The @Transactional annotation ensures that all database operations
     * (transaction insert, account update, category balance update) either
     * all succeed or all roll back, matching CICS transaction behavior.
     * 
     * @param request TransactionRequest DTO containing all transaction details
     *                validated with Jakarta Bean Validation annotations
     * @return TransactionResponse DTO with created transaction details including
     *         generated transaction ID
     * @throws ResourceNotFoundException if card or account not found
     * @throws BusinessLogicException if card is inactive, expired, or insufficient credit
     * @throws ValidationException if any field validation fails
     */
    @Transactional
    public TransactionResponse createTransaction(TransactionRequest request) {
        log.info("Starting transaction creation for card number: {}", 
                maskCardNumber(request.getCardNumber()));

        try {
            // Phase 1: Validate card and retrieve card details
            // Replaces COBOL: READ CCXREF FILE
            Card card = validateAndRetrieveCard(request.getCardNumber());
            
            // Phase 2: Validate card authorization (active status and expiration)
            // Replaces COBOL: Card status validation logic
            validateCardAuthorization(card);
            
            // Phase 3: Retrieve associated account
            // Replaces COBOL: READ CXACAIX FILE or READ ACCTDAT FILE
            Account account = retrieveAccount(card.getAccount().getAccountId());
            
            // Phase 4: Validate available credit
            // Replaces COBOL: COMPUTE ACCT-CURR-BAL = ACCT-CURR-BAL + TRAN-AMT
            // with exact COMP-3 decimal precision using BigDecimal
            validateAvailableCredit(account, request.getAmount());
            
            // Phase 5: Validate merchant information
            // Replaces COBOL: Merchant field validation
            validateMerchantInformation(request);
            
            // Phase 6: Generate unique transaction ID
            // Replaces COBOL: STARTBR-TRANSACT-FILE → READPREV-TRANSACT-FILE
            // → ADD 1 TO WS-TRAN-ID-N logic
            String transactionId = generateTransactionId();
            
            // Phase 7: Create transaction record
            // Replaces COBOL: MOVE statements populating TRAN-RECORD
            Transaction transaction = buildTransaction(request, transactionId, card);
            
            // Phase 8: Save transaction to database
            // Replaces COBOL: WRITE TRANSACT FILE
            transaction = transactionRepository.save(transaction);
            log.info("Transaction created with ID: {}", transaction.getTransactionId());
            
            // Phase 9: Update account balance
            // Enhancement: Update account current balance
            // (May be in separate COBOL program, implemented here for atomicity)
            updateAccountBalance(account, request.getAmount());
            
            // Phase 10: Update transaction category balance for analytics
            // Enhancement: Track spending by category
            updateCategoryBalance(account.getAccountId(), request.getTypeCode(),
                    request.getCategoryCode(), request.getAmount());
            
            // Phase 11: Build and return response
            TransactionResponse response = buildResponse(transaction);
            log.info("Transaction processing completed successfully for ID: {}", 
                    transaction.getTransactionId());
            
            return response;
            
        } catch (ResourceNotFoundException | BusinessLogicException | ValidationException e) {
            // Re-throw known exceptions to trigger rollback
            log.error("Transaction creation failed: {}", e.getMessage());
            throw e;
        } catch (Exception e) {
            // Wrap unexpected exceptions
            log.error("Unexpected error during transaction creation", e);
            throw new BusinessLogicException("Transaction processing failed: " + e.getMessage(), e);
        }
    }

    /**
     * Validates card exists and retrieves card details.
     * 
     * Replaces COBOL READ CCXREF FILE operation:
     * <pre>
     * EXEC CICS READ
     *      DATASET   (WS-CCXREF-FILE)
     *      INTO      (CARD-XREF-RECORD)
     *      RIDFLD    (XREF-CARD-NUM)
     *      RESP      (WS-RESP-CD)
     * END-EXEC
     * </pre>
     * 
     * COBOL error handling:
     * - DFHRESP(NOTFND): "Card Number NOT found..."
     * - OTHER: "Unable to lookup Card # in XREF file..."
     * 
     * @param cardNumber Card number to validate and retrieve
     * @return Card entity with all details
     * @throws ResourceNotFoundException if card not found (replaces RESP-CD NOTFND)
     */
    private Card validateAndRetrieveCard(String cardNumber) {
        log.debug("Validating card number: {}", maskCardNumber(cardNumber));
        
        if (cardNumber == null || cardNumber.trim().isEmpty()) {
            throw new ValidationException("Card number is required");
        }
        
        return cardRepository.findByCardNumber(cardNumber)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Card number not found: " + maskCardNumber(cardNumber)));
    }

    /**
     * Validates card authorization by checking active status and expiration date.
     * 
     * Replaces COBOL card validation logic that checks:
     * 1. Card active status
     * 2. Card expiration date comparison with current date
     * 
     * @param card Card entity to validate
     * @throws BusinessLogicException if card is inactive or expired
     */
    private void validateCardAuthorization(Card card) {
        log.debug("Validating card authorization for card: {}", 
                maskCardNumber(card.getCardNumber()));
        
        // Check if card is active
        // Replaces COBOL: Card status field validation
        if (!"Y".equalsIgnoreCase(card.getActiveStatus())) {
            throw new BusinessLogicException(
                    "Card is inactive: " + maskCardNumber(card.getCardNumber()));
        }
        
        // Check if card is not expired
        // Replaces COBOL: Date comparison using CSUTLDTC utility
        LocalDate currentDate = LocalDate.now();
        LocalDate expirationDate = card.getExpirationDate();
        
        if (expirationDate != null && expirationDate.isBefore(currentDate)) {
            throw new BusinessLogicException(
                    "Card is expired: " + maskCardNumber(card.getCardNumber()) +
                    ", expiration date: " + expirationDate.format(DATE_FORMATTER));
        }
        
        log.debug("Card authorization validated successfully");
    }

    /**
     * Retrieves account associated with the card.
     * 
     * Replaces COBOL READ CXACAIX FILE or READ ACCTDAT FILE:
     * <pre>
     * EXEC CICS READ
     *      DATASET   (WS-CXACAIX-FILE)
     *      INTO      (CARD-XREF-RECORD)
     *      RIDFLD    (XREF-ACCT-ID)
     *      RESP      (WS-RESP-CD)
     * END-EXEC
     * </pre>
     * 
     * @param accountId Account ID from card relationship
     * @return Account entity with balance and credit limit details
     * @throws ResourceNotFoundException if account not found
     */
    private Account retrieveAccount(Long accountId) {
        log.debug("Retrieving account: {}", accountId);
        
        if (accountId == null) {
            throw new ValidationException("Account ID is required");
        }
        
        return accountRepository.findById(accountId)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Account not found: " + accountId));
    }

    /**
     * Validates available credit by checking if new balance would exceed credit limit.
     * 
     * Replaces COBOL COMPUTE logic:
     * <pre>
     * COMPUTE ACCT-CURR-BAL = ACCT-CURR-BAL + TRAN-AMT
     * IF ACCT-CURR-BAL > ACCT-CREDIT-LIMIT
     *     MOVE 'Insufficient credit' TO WS-MESSAGE
     * END-IF
     * </pre>
     * 
     * Uses BigDecimal arithmetic with scale=2 and RoundingMode.HALF_UP to match
     * COBOL COMP-3 packed decimal precision exactly. The calculation:
     * newBalance = currentBalance + transactionAmount
     * must satisfy: newBalance <= creditLimit
     * 
     * @param account Account entity with current balance and credit limit
     * @param transactionAmount Transaction amount to validate
     * @throws BusinessLogicException if insufficient credit available
     */
    private void validateAvailableCredit(Account account, BigDecimal transactionAmount) {
        log.debug("Validating available credit for account: {}", account.getAccountId());
        
        // Get current balance and credit limit
        BigDecimal currentBalance = account.getCurrentBalance();
        BigDecimal creditLimit = account.getCreditLimit();
        
        // Ensure proper scale for all monetary values
        currentBalance = currentBalance.setScale(DECIMAL_SCALE, DECIMAL_ROUNDING);
        creditLimit = creditLimit.setScale(DECIMAL_SCALE, DECIMAL_ROUNDING);
        BigDecimal amount = transactionAmount.setScale(DECIMAL_SCALE, DECIMAL_ROUNDING);
        
        // Calculate new balance after transaction
        // Replaces COBOL: COMPUTE ACCT-CURR-BAL = ACCT-CURR-BAL + TRAN-AMT
        BigDecimal newBalance = currentBalance.add(amount);
        newBalance = newBalance.setScale(DECIMAL_SCALE, DECIMAL_ROUNDING);
        
        log.debug("Credit validation - Current: {}, Amount: {}, New: {}, Limit: {}",
                currentBalance, amount, newBalance, creditLimit);
        
        // Validate new balance does not exceed credit limit
        // Replaces COBOL: IF ACCT-CURR-BAL > ACCT-CREDIT-LIMIT
        if (newBalance.compareTo(creditLimit) > 0) {
            BigDecimal availableCredit = creditLimit.subtract(currentBalance);
            availableCredit = availableCredit.setScale(DECIMAL_SCALE, DECIMAL_ROUNDING);
            
            throw new BusinessLogicException(
                    String.format("Insufficient credit. Transaction amount: %s, " +
                            "Available credit: %s, Credit limit: %s",
                            amount, availableCredit, creditLimit));
        }
        
        log.debug("Available credit validated successfully");
    }

    /**
     * Validates merchant information fields.
     * 
     * Replaces COBOL VALIDATE-INPUT-DATA-FIELDS paragraph that checks:
     * - Merchant ID (MIDI)
     * - Merchant Name (MNAMEI)
     * - Merchant City (MCITYI)
     * - Merchant ZIP (MZIPI)
     * 
     * @param request Transaction request containing merchant details
     * @throws ValidationException if any merchant field is invalid
     */
    private void validateMerchantInformation(TransactionRequest request) {
        log.debug("Validating merchant information");
        
        Map<String, String> fieldErrors = new HashMap<>();
        
        // Validate merchant ID
        if (request.getMerchantId() == null) {
            fieldErrors.put("merchantId", "Merchant ID is required");
        }
        
        // Validate merchant name
        if (request.getMerchantName() == null || request.getMerchantName().trim().isEmpty()) {
            fieldErrors.put("merchantName", "Merchant name is required");
        }
        
        // Validate merchant city
        if (request.getMerchantCity() == null || request.getMerchantCity().trim().isEmpty()) {
            fieldErrors.put("merchantCity", "Merchant city is required");
        }
        
        // Validate merchant ZIP
        if (request.getMerchantZip() == null || request.getMerchantZip().trim().isEmpty()) {
            fieldErrors.put("merchantZip", "Merchant ZIP is required");
        }
        
        if (!fieldErrors.isEmpty()) {
            throw new ValidationException(fieldErrors);
        }
        
        log.debug("Merchant information validated successfully");
    }

    /**
     * Generates unique transaction ID by finding maximum existing ID and incrementing.
     * 
     * Replaces COBOL logic:
     * <pre>
     * STARTBR-TRANSACT-FILE.
     * EXEC CICS STARTBR
     *      DATASET   (WS-TRANSACT-FILE)
     *      RIDFLD    (TRAN-ID)
     * END-EXEC
     * 
     * READPREV-TRANSACT-FILE.
     * EXEC CICS READPREV
     *      DATASET   (WS-TRANSACT-FILE)
     *      INTO      (TRAN-RECORD)
     * END-EXEC
     * 
     * ADD 1 TO WS-TRAN-ID-N
     * MOVE WS-TRAN-ID-N TO TRAN-ID
     * </pre>
     * 
     * The STARTBR/READPREV pattern in COBOL positions the browse at the end
     * of the file and reads backwards to get the highest transaction ID.
     * We replicate this by finding the transaction with the highest ID
     * (ordered descending) and incrementing by 1.
     * 
     * @return Unique transaction ID as 16-character string with leading zeros
     */
    private String generateTransactionId() {
        log.debug("Generating unique transaction ID");
        
        // Find the transaction with the highest ID
        // Replaces COBOL: STARTBR → READPREV to get last transaction
        Optional<Transaction> lastTransaction = transactionRepository.findTopByOrderByTransactionIdDesc();
        
        long nextId;
        if (lastTransaction.isPresent()) {
            String lastId = lastTransaction.get().getTransactionId();
            try {
                // Parse the numeric portion and increment
                // Replaces COBOL: ADD 1 TO WS-TRAN-ID-N
                nextId = Long.parseLong(lastId) + 1;
            } catch (NumberFormatException e) {
                log.warn("Failed to parse last transaction ID: {}, starting from 1", lastId);
                nextId = 1;
            }
        } else {
            // No transactions exist yet, start from 1
            // Replaces COBOL: WHEN DFHRESP(ENDFILE) MOVE ZEROS TO TRAN-ID
            nextId = 1;
        }
        
        // Format as 16-character string with leading zeros
        // Matches COBOL: TRAN-ID PIC X(16)
        String transactionId = String.format("%016d", nextId);
        log.debug("Generated transaction ID: {}", transactionId);
        
        return transactionId;
    }

    /**
     * Builds Transaction entity from request DTO.
     * 
     * Replaces COBOL MOVE statements populating TRAN-RECORD:
     * <pre>
     * MOVE TTYPCDI    TO TRAN-TYPE-CD
     * MOVE TCATCDI    TO TRAN-CAT-CD
     * MOVE TRNSRCI    TO TRAN-SOURCE
     * MOVE TDESCI     TO TRAN-DESC
     * MOVE WS-TRAN-AMT-N TO TRAN-AMT
     * MOVE MIDI       TO TRAN-MERCHANT-ID
     * MOVE MNAMEI     TO TRAN-MERCHANT-NAME
     * MOVE MCITYI     TO TRAN-MERCHANT-CITY
     * MOVE MZIPI      TO TRAN-MERCHANT-ZIP
     * MOVE CARDNINI   TO TRAN-CARD-NUM
     * </pre>
     * 
     * Timestamps (TRAN-ORIG-TS, TRAN-PROC-TS) are set to current time,
     * replacing COBOL timestamp fields PIC X(26).
     * 
     * @param request Transaction request with all details
     * @param transactionId Generated unique transaction ID
     * @param card Card entity associated with this transaction
     * @return Transaction entity ready to persist
     */
    private Transaction buildTransaction(TransactionRequest request, String transactionId, Card card) {
        log.debug("Building transaction entity");
        
        LocalDateTime now = LocalDateTime.now();
        
        Transaction transaction = Transaction.builder()
                .transactionId(transactionId)
                .typeCode(request.getTypeCode())
                .categoryCode(request.getCategoryCode())
                .transactionSource(request.getTransactionSource())
                .description(request.getDescription())
                .amount(request.getAmount().setScale(DECIMAL_SCALE, DECIMAL_ROUNDING))
                .merchantId(request.getMerchantId())
                .merchantName(request.getMerchantName())
                .merchantCity(request.getMerchantCity())
                .merchantZip(request.getMerchantZip())
                .card(card)
                .originationTimestamp(now)
                .processingTimestamp(now)
                .build();
        
        log.debug("Transaction entity built successfully");
        return transaction;
    }

    /**
     * Updates account current balance after transaction.
     * 
     * This is an enhancement that may be in a separate COBOL batch program
     * but is implemented here for atomicity within the same transaction boundary.
     * 
     * Replaces COBOL REWRITE ACCTDAT-RECORD:
     * <pre>
     * COMPUTE ACCT-CURR-BAL = ACCT-CURR-BAL + TRAN-AMT
     * EXEC CICS REWRITE
     *      DATASET  (WS-ACCTDAT-FILE)
     *      FROM     (ACCOUNT-RECORD)
     * END-EXEC
     * </pre>
     * 
     * Uses BigDecimal arithmetic with exact scale and rounding to match
     * COBOL COMP-3 precision.
     * 
     * @param account Account entity to update
     * @param transactionAmount Amount to add to current balance
     */
    private void updateAccountBalance(Account account, BigDecimal transactionAmount) {
        log.debug("Updating account balance for account: {}", account.getAccountId());
        
        BigDecimal currentBalance = account.getCurrentBalance()
                .setScale(DECIMAL_SCALE, DECIMAL_ROUNDING);
        BigDecimal amount = transactionAmount.setScale(DECIMAL_SCALE, DECIMAL_ROUNDING);
        
        // Add transaction amount to current balance
        // Replaces COBOL: COMPUTE ACCT-CURR-BAL = ACCT-CURR-BAL + TRAN-AMT
        BigDecimal newBalance = currentBalance.add(amount);
        newBalance = newBalance.setScale(DECIMAL_SCALE, DECIMAL_ROUNDING);
        
        account.setCurrentBalance(newBalance);
        
        // Save updated account
        // Replaces COBOL: REWRITE ACCTDAT-RECORD
        accountRepository.save(account);
        
        log.info("Account balance updated - Account: {}, Old: {}, New: {}",
                account.getAccountId(), currentBalance, newBalance);
    }

    /**
     * Updates transaction category balance for spending analytics.
     * 
     * This enhancement tracks spending by transaction type and category
     * for analytical reporting purposes.
     * 
     * @param accountId Account ID for the transaction
     * @param typeCode Transaction type code
     * @param categoryCode Transaction category code
     * @param transactionAmount Amount to add to category balance
     */
    private void updateCategoryBalance(Long accountId, String typeCode, 
                                      String categoryCode, BigDecimal transactionAmount) {
        log.debug("Updating category balance - Account: {}, Type: {}, Category: {}",
                accountId, typeCode, categoryCode);
        
        // Find existing category balance or create new one
        Optional<TransactionCategoryBalance> existingBalance = 
                categoryBalanceRepository.findByIdAccountIdAndIdTransactionTypeCodeAndIdCategoryCode(
                        accountId, typeCode, categoryCode);
        
        TransactionCategoryBalance categoryBalance;
        if (existingBalance.isPresent()) {
            // Update existing balance
            categoryBalance = existingBalance.get();
            BigDecimal currentCategoryBalance = categoryBalance.getBalance()
                    .setScale(DECIMAL_SCALE, DECIMAL_ROUNDING);
            BigDecimal amount = transactionAmount.setScale(DECIMAL_SCALE, DECIMAL_ROUNDING);
            BigDecimal newCategoryBalance = currentCategoryBalance.add(amount);
            newCategoryBalance = newCategoryBalance.setScale(DECIMAL_SCALE, DECIMAL_ROUNDING);
            
            categoryBalance.setBalance(newCategoryBalance);
            
            log.debug("Updated existing category balance from {} to {}", 
                    currentCategoryBalance, newCategoryBalance);
        } else {
            // Create new category balance entry
            // Build composite key first
            TransactionCategoryBalance.TransactionCategoryBalanceId balanceId = 
                    TransactionCategoryBalance.TransactionCategoryBalanceId.builder()
                            .accountId(accountId)
                            .transactionTypeCode(typeCode)
                            .categoryCode(categoryCode)
                            .build();
            
            categoryBalance = TransactionCategoryBalance.builder()
                    .id(balanceId)
                    .balance(transactionAmount.setScale(DECIMAL_SCALE, DECIMAL_ROUNDING))
                    .build();
            
            log.debug("Created new category balance with amount: {}", transactionAmount);
        }
        
        categoryBalanceRepository.save(categoryBalance);
        log.info("Category balance updated successfully");
    }

    /**
     * Builds TransactionResponse DTO from Transaction entity.
     * 
     * Maps all transaction fields to response DTO with proper formatting:
     * - BigDecimal amounts formatted to 2 decimal places
     * - Card number masked for security (last 4 digits only)
     * - Timestamps formatted as ISO-8601 strings
     * 
     * Replaces COBOL success message:
     * <pre>
     * STRING 'Transaction added successfully. '
     *        ' Your Tran ID is ' TRAN-ID '.'
     *   INTO WS-MESSAGE
     * </pre>
     * 
     * @param transaction Persisted transaction entity
     * @return TransactionResponse DTO for API response
     */
    private TransactionResponse buildResponse(Transaction transaction) {
        log.debug("Building transaction response");
        
        // Convert categoryCode from String to Integer for DTO
        Integer categoryCodeInt = transaction.getCategoryCode() != null ? 
                Integer.parseInt(transaction.getCategoryCode()) : null;
        
        return TransactionResponse.builder()
                .transactionId(transaction.getTransactionId())
                .typeCode(transaction.getTypeCode())
                .categoryCode(categoryCodeInt)
                .source(transaction.getTransactionSource())
                .description(transaction.getDescription())
                .amount(transaction.getAmount())
                .merchantId(transaction.getMerchantId())
                .merchantName(transaction.getMerchantName())
                .merchantCity(transaction.getMerchantCity())
                .merchantZip(transaction.getMerchantZip())
                .cardNumber(maskCardNumber(transaction.getCard().getCardNumber()))
                .originationTimestamp(transaction.getOriginationTimestamp())
                .processingTimestamp(transaction.getProcessingTimestamp())
                .build();
    }

    /**
     * Masks card number for security, showing only last 4 digits.
     * 
     * Example: "4111111111111111" becomes "************1111"
     * 
     * @param cardNumber Full card number
     * @return Masked card number with only last 4 digits visible
     */
    private String maskCardNumber(String cardNumber) {
        if (cardNumber == null || cardNumber.length() < 4) {
            return "****";
        }
        
        int visibleDigits = 4;
        int maskedLength = cardNumber.length() - visibleDigits;
        return "*".repeat(maskedLength) + cardNumber.substring(maskedLength);
    }
}
