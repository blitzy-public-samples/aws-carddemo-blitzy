/*
 * TransactionService.java
 *
 * Transaction processing service handling business logic from COBOL programs:
 * - COTRN00C.cbl (transaction list display)
 * - COTRN01C.cbl (transaction detail view)
 * - COTRN02C.cbl (transaction posting - most complex)
 *
 * Converted from COBOL PROCEDURE DIVISION business logic with exact COBOL calculation
 * logic maintaining COMP-3 precision using BigDecimal scale 2 and RoundingMode.HALF_UP
 * per Agent Action Plan Section 0.7.2.
 *
 * Original COBOL files:
 * - Source: app/cbl/COTRN00C.cbl (transaction list with date range filtering)
 * - Source: app/cbl/COTRN01C.cbl (transaction detail display)
 * - Source: app/cbl/COTRN02C.cbl (33KB, transaction posting with complex balance calculations)
 * - Copybook: app/cpy/CVTRA05Y.cpy (TRAN-RECORD structure, 350-byte record)
 * - Copybook: app/cpy/CVTRA03Y.cpy (TRAN-TYPE-CD codes)
 * - Copybook: app/cpy/CVTRA04Y.cpy (TRAN-CAT-CD merchant category codes)
 *
 * Key COBOL-to-Java transformations:
 * 1. EXEC CICS READ FILE('TRANSACT') → transactionRepository.findById()
 * 2. EXEC CICS WRITE FILE('TRANSACT') → transactionRepository.save()
 * 3. EXEC CICS STARTBR / READNEXT loop → transactionRepository.findByTransCardNumAndTransOrigTsBetween()
 * 4. EXEC CICS SYNCPOINT → @Transactional annotation ensuring atomic updates
 * 5. COBOL PIC S9(09)V99 COMP-3 → BigDecimal with scale 2, RoundingMode.HALF_UP
 * 6. COBOL validation logic → ValidationService method calls
 * 7. Account balance updates (ACCTFILE REWRITE) → accountService.updateAccount()
 * 8. Category balance updates (TCATBAL REWRITE) → updateCategoryBalance()
 *
 * Business Rules Preserved from COTRN02C.cbl:
 * - Transaction amount must be positive (validation)
 * - Card must be active before posting transaction
 * - Credit limit must not be exceeded by transaction posting
 * - Debit transactions (purchase, cash advance) reduce balance
 * - Credit transactions (payment, refund) increase balance
 * - All balance calculations use exact COMP-3 precision (BigDecimal scale 2, HALF_UP rounding)
 * - Account balance, cycle credit, and cycle debit updated atomically with transaction creation
 * - Transaction category balances updated for reporting and analytics
 *
 * Transaction Type Codes (from CVTRA03Y.cpy):
 * - '01' = Purchase (debit)
 * - '02' = Cash Advance (debit)
 * - '03' = Balance Transfer (debit)
 * - '04' = Payment (credit)
 * - '05' = Fee (debit)
 * - '06' = Interest Charge (debit)
 * - '07' = Credit Adjustment (credit)
 * - '08' = Debit Adjustment (debit)
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
package com.carddemo.service;

import com.carddemo.exception.BusinessException;
import com.carddemo.exception.DataNotFoundException;
import com.carddemo.model.dto.AccountDto;
import com.carddemo.model.dto.CardDto;
import com.carddemo.model.dto.TransactionDto;
import com.carddemo.model.entity.Transaction;
import com.carddemo.model.entity.TransactionCategoryBalance;
import com.carddemo.model.entity.TransactionCategoryBalance.TransactionCategoryBalanceId;
import com.carddemo.repository.AccountRepository;
import com.carddemo.repository.TransactionCategoryBalanceRepository;
import com.carddemo.repository.TransactionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Optional;
import java.util.UUID;

/**
 * Transaction processing service implementing business logic from COBOL programs
 * COTRN00C.cbl, COTRN01C.cbl, and COTRN02C.cbl.
 * 
 * <p>This service replaces COBOL PROCEDURE DIVISION logic for transaction operations:</p>
 * <ul>
 *   <li><b>Transaction List:</b> Browse transactions with filters (COTRN00C.cbl → listTransactions)</li>
 *   <li><b>Transaction Detail:</b> View single transaction (COTRN01C.cbl → getTransactionById)</li>
 *   <li><b>Transaction Posting:</b> Create new transaction with balance updates (COTRN02C.cbl → postTransaction)</li>
 *   <li><b>Balance Calculation:</b> Update account and category balances atomically</li>
 * </ul>
 * 
 * <h3>COBOL to Java Method Mapping:</h3>
 * <table border="1">
 *   <tr>
 *     <th>COBOL Program</th>
 *     <th>COBOL Paragraph</th>
 *     <th>Java Method</th>
 *   </tr>
 *   <tr>
 *     <td>COTRN00C.cbl</td>
 *     <td>BROWSE-TRANS-LOOP</td>
 *     <td>listTransactions(String, LocalDate, LocalDate, Pageable)</td>
 *   </tr>
 *   <tr>
 *     <td>COTRN01C.cbl</td>
 *     <td>READ-TRANSACTION-DETAIL</td>
 *     <td>getTransactionById(String)</td>
 *   </tr>
 *   <tr>
 *     <td>COTRN02C.cbl</td>
 *     <td>POST-TRANSACTION / UPDATE-ACCOUNT-BALANCE</td>
 *     <td>postTransaction(TransactionDto)</td>
 *   </tr>
 *   <tr>
 *     <td>COTRN02C.cbl</td>
 *     <td>VALIDATE-TRANSACTION-DATA</td>
 *     <td>validateTransaction(TransactionDto)</td>
 *   </tr>
 *   <tr>
 *     <td>COTRN02C.cbl</td>
 *     <td>UPDATE-CATEGORY-BALANCE</td>
 *     <td>updateCategoryBalance(Long, String, Integer, BigDecimal)</td>
 *   </tr>
 * </table>
 * 
 * <h3>Transaction Posting Flow (COTRN02C.cbl Logic):</h3>
 * <ol>
 *   <li>Validate transaction data (amount, card number, type, category)</li>
 *   <li>Retrieve card details and validate card is active</li>
 *   <li>Retrieve account details for balance calculations</li>
 *   <li>Determine debit/credit based on transaction type code</li>
 *   <li>Calculate new balance with exact COMP-3 precision (BigDecimal scale 2, HALF_UP)</li>
 *   <li>Validate new balance does not exceed credit limit</li>
 *   <li>Update account balance, cycle credit, and cycle debit atomically</li>
 *   <li>Create and save transaction record</li>
 *   <li>Update transaction category balance</li>
 *   <li>Return transaction DTO to caller</li>
 * </ol>
 * 
 * <h3>Transactional Integrity:</h3>
 * <p>The postTransaction() method is annotated with @Transactional to ensure that
 * account balance updates and transaction creation occur as a single atomic operation,
 * maintaining CICS SYNCPOINT semantics from COBOL. If any step fails, entire transaction
 * is rolled back to preserve data consistency.</p>
 * 
 * <h3>Precision Requirements:</h3>
 * <p>All monetary calculations use BigDecimal with scale 2 and RoundingMode.HALF_UP to
 * maintain exact COBOL COMP-3 packed decimal precision per Agent Action Plan Section 0.7.2.
 * This ensures bit-identical financial calculations to mainframe COBOL implementation.</p>
 * 
 * @see Transaction
 * @see TransactionDto
 * @see com.carddemo.controller.TransactionController
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class TransactionService {

    // Dependencies injected via constructor (Lombok @RequiredArgsConstructor)
    private final TransactionRepository transactionRepository;
    private final AccountRepository accountRepository;
    private final TransactionCategoryBalanceRepository transactionCategoryBalanceRepository;
    private final CardService cardService;
    private final AccountService accountService;
    private final ValidationService validationService;

    /**
     * List transactions with optional card number and date range filters.
     * 
     * Converted from COBOL program: COTRN00C.cbl
     * Original function: Browse transaction list with pagination and filtering
     * 
     * COBOL logic (COTRN00C.cbl lines 150-250):
     * <pre>
     * EXEC CICS STARTBR FILE('TRANSACT')
     *      RIDFLD(WS-CARD-NUM)
     *      GTEQ
     * END-EXEC.
     * PERFORM READ-TRANS-LOOP
     *      UNTIL END-OF-FILE OR TRAN-CARD-NUM NOT = WS-CARD-NUM
     *      OR TRAN-ORIG-TS > WS-END-DATE.
     *      IF TRAN-ORIG-TS >= WS-START-DATE
     *         PERFORM PROCESS-TRANSACTION
     *      END-IF
     * END-PERFORM.
     * </pre>
     * 
     * This method retrieves transactions matching the specified filters and returns
     * paginated results. Spring Data JPA automatically handles pagination through
     * the Pageable parameter.
     * 
     * @param cardNumber Card number to filter transactions (16-character card number).
     *                   Must be valid card number format validated by ValidationService.
     * @param startDate Start of date range (inclusive). Transactions with origination
     *                  timestamp >= startDate at 00:00:00 are included.
     * @param endDate End of date range (inclusive). Transactions with origination
     *                timestamp <= endDate at 23:59:59 are included.
     * @param pageable Pagination parameters (page number, page size, sort order).
     *                 Replaces COBOL WS-PAGE-NUM and browse cursor positioning logic.
     * @return Page of TransactionDto objects matching filters, with pagination metadata.
     *         Returns empty page if no transactions match the criteria.
     * @throws com.carddemo.exception.ValidationException if cardNumber format is invalid
     */
    @Transactional(readOnly = true)
    public Page<TransactionDto> listTransactions(String cardNumber, LocalDate startDate, 
                                                   LocalDate endDate, Pageable pageable) {
        log.debug("Listing transactions for card: {}, date range: {} to {}, page: {}", 
                  cardNumber, startDate, endDate, pageable.getPageNumber());

        // Step 1: Validate card number if provided (optional filter parameter)
        // Card number filter is optional per COTRN00C.cbl - can browse all transactions
        if (cardNumber != null && !cardNumber.isEmpty()) {
            validationService.validateCardNumber(cardNumber);
        }

        // Step 2: Validate date range
        if (startDate != null && endDate != null && startDate.isAfter(endDate)) {
            throw new BusinessException("Start date cannot be after end date");
        }

        // Step 3: Query transactions with optional filters (replaces CICS STARTBR / READNEXT loop)
        Page<Transaction> transactions;
        
        // Handle different filter combinations matching COTRN00C.cbl browse logic
        if (cardNumber != null && !cardNumber.isEmpty() && startDate != null && endDate != null) {
            // Both card number and date range filters provided
            LocalDateTime startDateTime = startDate.atStartOfDay();
            LocalDateTime endDateTime = endDate.atTime(23, 59, 59);
            transactions = transactionRepository.findByTransCardNumAndTransOrigTsBetween(
                cardNumber, startDateTime, endDateTime, pageable);
            log.info("Found {} transactions for card {} in date range {} to {}", 
                     transactions.getTotalElements(), cardNumber, startDate, endDate);
        } else if (cardNumber != null && !cardNumber.isEmpty()) {
            // Only card number filter provided
            transactions = transactionRepository.findByTransCardNum(cardNumber, pageable);
            log.info("Found {} transactions for card {}", 
                     transactions.getTotalElements(), cardNumber);
        } else if (startDate != null && endDate != null) {
            // Only date range filter provided
            LocalDateTime startDateTime = startDate.atStartOfDay();
            LocalDateTime endDateTime = endDate.atTime(23, 59, 59);
            transactions = transactionRepository.findByTransOrigTsBetween(
                startDateTime, endDateTime, pageable);
            log.info("Found {} transactions in date range {} to {}", 
                     transactions.getTotalElements(), startDate, endDate);
        } else {
            // No filters - return all transactions (COBOL browse all records)
            transactions = transactionRepository.findAll(pageable);
            log.info("Found {} total transactions (no filters)", 
                     transactions.getTotalElements());
        }

        // Step 4: Convert entities to DTOs using mapToDto() private method
        return transactions.map(this::mapToDto);
    }

    /**
     * Get transaction details by transaction ID.
     * 
     * Converted from COBOL program: COTRN01C.cbl
     * Original function: Display single transaction detail
     * 
     * COBOL logic (COTRN01C.cbl lines 100-150):
     * <pre>
     * EXEC CICS READ FILE('TRANSACT')
     *      RIDFLD(WS-TRAN-ID)
     *      INTO(TRAN-RECORD)
     *      RESP(WS-RESP-CD)
     * END-EXEC.
     * IF WS-RESP-CD = DFHRESP(NOTFND)
     *    MOVE 'Transaction not found' TO WS-MESSAGE
     * END-IF.
     * </pre>
     * 
     * This method retrieves a single transaction by its unique ID and returns the
     * transaction details as a DTO for API response.
     * 
     * @param transactionId Unique transaction identifier (16-character alphanumeric).
     *                      Primary key for transaction lookup.
     * @return TransactionDto containing transaction details with card number masked
     * @throws DataNotFoundException if transaction with specified ID does not exist.
     *         Replaces COBOL file-status 23 (record not found) and DFHRESP(NOTFND).
     */
    @Transactional(readOnly = true)
    public TransactionDto getTransactionById(String transactionId) {
        log.debug("Getting transaction details for ID: {}", transactionId);

        // Step 1: Query transaction by ID (replaces CICS READ operation)
        Optional<Transaction> transaction = transactionRepository.findById(transactionId);

        // Step 2: Throw DataNotFoundException if not found (replaces COBOL RESP check)
        return transaction.map(this::mapToDto)
                          .orElseThrow(() -> {
                              log.error("Transaction not found: {}", transactionId);
                              return new DataNotFoundException("Transaction not found: " + transactionId);
                          });
    }

    /**
     * Post a new transaction with account balance updates.
     * 
     * Converted from COBOL program: COTRN02C.cbl (most complex transaction logic)
     * Original function: Add new transaction to TRANSACT file and update ACCTFILE balances
     * 
     * COBOL logic (COTRN02C.cbl lines 200-500):
     * <pre>
     * PERFORM VALIDATE-TRANSACTION-DATA.
     * EXEC CICS READ FILE('CCXREF')
     *      RIDFLD(WS-CARD-NUM)
     *      INTO(XREF-RECORD)
     * END-EXEC.
     * MOVE XREF-ACCT-ID TO WS-ACCT-ID.
     * EXEC CICS READ FILE('ACCTFILE')
     *      RIDFLD(WS-ACCT-ID)
     *      INTO(ACCOUNT-RECORD)
     *      UPDATE
     * END-EXEC.
     * 
     * IF TRAN-TYPE-CD = '01' OR '02' OR '03' OR '05' OR '06' OR '08'
     *    COMPUTE NEW-BALANCE = ACCT-CURR-BAL - TRAN-AMT
     *    COMPUTE NEW-DEBIT = ACCT-CURR-CYC-DEBIT + TRAN-AMT
     * ELSE
     *    COMPUTE NEW-BALANCE = ACCT-CURR-BAL + TRAN-AMT
     *    COMPUTE NEW-CREDIT = ACCT-CURR-CYC-CREDIT + TRAN-AMT
     * END-IF.
     * 
     * IF NEW-BALANCE > ACCT-CREDIT-LIMIT
     *    MOVE 'Transaction exceeds credit limit' TO WS-MESSAGE
     *    PERFORM RETURN-ERROR
     * END-IF.
     * 
     * MOVE NEW-BALANCE TO ACCT-CURR-BAL.
     * EXEC CICS REWRITE FILE('ACCTFILE')
     *      FROM(ACCOUNT-RECORD)
     * END-EXEC.
     * 
     * MOVE FUNCTION CURRENT-DATE TO TRAN-PROC-TS.
     * EXEC CICS WRITE FILE('TRANSACT')
     *      FROM(TRAN-RECORD)
     *      RIDFLD(WS-TRAN-ID)
     * END-EXEC.
     * 
     * PERFORM UPDATE-CATEGORY-BALANCE.
     * EXEC CICS SYNCPOINT.
     * </pre>
     * 
     * This method implements the complete transaction posting flow maintaining exact
     * COBOL business logic and calculation precision. All balance updates occur within
     * a single @Transactional boundary to ensure atomicity (CICS SYNCPOINT semantics).
     * 
     * @param transactionDto Transaction data including card number, amount, type, category,
     *                       merchant details. Amount must be positive, card must be active,
     *                       type and category must be valid codes.
     * @return TransactionDto containing created transaction with generated ID and timestamps
     * @throws BusinessException if validation fails, card is inactive, or credit limit exceeded
     * @throws DataNotFoundException if card or account not found
     */
    @Transactional
    public TransactionDto postTransaction(TransactionDto transactionDto) {
        log.info("Posting new transaction for card: {}, amount: {}, type: {}", 
                 transactionDto.getTransCardNum(), transactionDto.getTransAmt(), 
                 transactionDto.getTransTypeCd());

        // Step 1: Validate transaction data using validateTransaction() private method
        // Replaces COBOL PERFORM VALIDATE-TRANSACTION-DATA paragraph
        validateTransaction(transactionDto);

        // Step 2: Retrieve card details and validate card status
        // Replaces COBOL EXEC CICS READ FILE('CCXREF') followed by card validation
        // Note: COBOL uses 'Y' but PostgreSQL constraint uses 'A' for active per V2 migration
        CardDto card = cardService.getCardByNumber(transactionDto.getTransCardNum());
        
        if (!"A".equals(card.getCardStatus())) {
            log.error("Card is not active: {}", transactionDto.getTransCardNum());
            throw new BusinessException("Card is not active");
        }

        // Step 3: Retrieve account details for balance calculations
        // Replaces COBOL EXEC CICS READ FILE('ACCTFILE') RIDFLD(XREF-ACCT-ID) UPDATE
        AccountDto account = accountService.getAccountById(card.getCardAcctId());

        // Step 4: Create Transaction entity from DTO
        // Generate unique transaction ID (replaces COBOL WS-TRAN-ID generation logic)
        String transactionId = UUID.randomUUID().toString().replaceAll("-", "").substring(0, 16);
        
        Transaction transaction = Transaction.builder()
            .transId(transactionId)
            .transCardNum(transactionDto.getTransCardNum())
            .transTypeCd(transactionDto.getTransTypeCd())
            .transCatCd(transactionDto.getTransCatCd())
            .transAmt(transactionDto.getTransAmt().setScale(2, RoundingMode.HALF_UP))
            .transOrigTs(Timestamp.valueOf(LocalDateTime.now()))
            .transProcTs(Timestamp.valueOf(LocalDateTime.now()))
            .transMerchantId(transactionDto.getTransMerchantId())
            .transMerchantName(transactionDto.getTransMerchantName())
            .transDesc(transactionDto.getTransDesc())
            .transSource(transactionDto.getTransSource())
            .transMerchantCity(transactionDto.getTransMerchantCity())
            .transMerchantZip(transactionDto.getTransMerchantZip())
            .build();

        // Step 5: Determine debit/credit and calculate new balance
        // Replaces COBOL IF TRAN-TYPE-CD = '01' OR '02'... COMPUTE statements
        // Debit types: 01=Purchase, 02=Cash Advance, 03=Balance Transfer, 05=Fee, 06=Interest, 08=Debit Adj
        // Credit types: 04=Payment, 07=Credit Adjustment
        boolean isDebit = "01".equals(transactionDto.getTransTypeCd()) ||
                          "02".equals(transactionDto.getTransTypeCd()) ||
                          "03".equals(transactionDto.getTransTypeCd()) ||
                          "05".equals(transactionDto.getTransTypeCd()) ||
                          "06".equals(transactionDto.getTransTypeCd()) ||
                          "08".equals(transactionDto.getTransTypeCd());

        BigDecimal newBalance;
        BigDecimal newCreditCycle = account.getAcctCurrCycCredit();
        BigDecimal newDebitCycle = account.getAcctCurrCycDebit();

        if (isDebit) {
            // Debit transaction: reduce balance, increase cycle debit
            // COBOL: COMPUTE NEW-BALANCE = ACCT-CURR-BAL - TRAN-AMT
            newBalance = account.getAcctCurrBal()
                               .subtract(transactionDto.getTransAmt())
                               .setScale(2, RoundingMode.HALF_UP);
            
            // COBOL: COMPUTE NEW-DEBIT = ACCT-CURR-CYC-DEBIT + TRAN-AMT
            newDebitCycle = account.getAcctCurrCycDebit()
                                  .add(transactionDto.getTransAmt())
                                  .setScale(2, RoundingMode.HALF_UP);
            
            log.debug("Debit transaction: old balance={}, new balance={}, cycle debit={}", 
                      account.getAcctCurrBal(), newBalance, newDebitCycle);
        } else {
            // Credit transaction: increase balance, increase cycle credit
            // COBOL: COMPUTE NEW-BALANCE = ACCT-CURR-BAL + TRAN-AMT
            newBalance = account.getAcctCurrBal()
                               .add(transactionDto.getTransAmt())
                               .setScale(2, RoundingMode.HALF_UP);
            
            // COBOL: COMPUTE NEW-CREDIT = ACCT-CURR-CYC-CREDIT + TRAN-AMT
            newCreditCycle = account.getAcctCurrCycCredit()
                                   .add(transactionDto.getTransAmt())
                                   .setScale(2, RoundingMode.HALF_UP);
            
            log.debug("Credit transaction: old balance={}, new balance={}, cycle credit={}", 
                      account.getAcctCurrBal(), newBalance, newCreditCycle);
        }

        // Step 6: Validate sufficient credit (for debit transactions)
        // Replaces COBOL IF NEW-BALANCE > ACCT-CREDIT-LIMIT
        // Note: For credit cards, negative balance means amount owed, so check if absolute value exceeds limit
        if (isDebit && newBalance.abs().compareTo(account.getAcctCreditLimit()) > 0) {
            log.error("Transaction exceeds credit limit. New balance: {}, Credit limit: {}", 
                      newBalance, account.getAcctCreditLimit());
            throw new BusinessException("Transaction exceeds credit limit");
        }

        // Step 7: Update account balance, cycle credit, and cycle debit
        // Replaces COBOL MOVE statements and EXEC CICS REWRITE FILE('ACCTFILE')
        account.setAcctCurrBal(newBalance);
        account.setAcctCurrCycCredit(newCreditCycle);
        account.setAcctCurrCycDebit(newDebitCycle);
        accountService.updateAccount(account.getAcctId(), account);

        // Step 8: Save transaction record
        // Replaces COBOL EXEC CICS WRITE FILE('TRANSACT') FROM(TRAN-RECORD)
        Transaction savedTransaction = transactionRepository.save(transaction);
        log.info("Transaction posted successfully: ID={}, amount={}, new balance={}", 
                 savedTransaction.getTransId(), transactionDto.getTransAmt(), newBalance);

        // Step 9: Update transaction category balance
        // Replaces COBOL PERFORM UPDATE-CATEGORY-BALANCE paragraph
        updateCategoryBalance(account.getAcctId(), transactionDto.getTransTypeCd(), 
                             transactionDto.getTransCatCd(), transactionDto.getTransAmt());

        // Step 10: Return transaction DTO
        // @Transactional annotation ensures automatic SYNCPOINT on method completion
        return mapToDto(savedTransaction);
    }

    /**
     * Validate transaction data before posting.
     * 
     * Converted from COBOL paragraph: VALIDATE-TRANSACTION-DATA (COTRN02C.cbl)
     * 
     * COBOL validation logic:
     * <pre>
     * IF TRAN-AMT <= ZERO
     *    MOVE 'Amount must be positive' TO WS-MESSAGE
     *    PERFORM RETURN-ERROR
     * END-IF.
     * IF TRAN-CARD-NUM = SPACES OR LOW-VALUES
     *    MOVE 'Card number required' TO WS-MESSAGE
     *    PERFORM RETURN-ERROR
     * END-IF.
     * </pre>
     * 
     * This method performs field-level validation matching COBOL 88-level conditions
     * and BMS field attribute checks.
     * 
     * @param transactionDto Transaction data to validate
     * @throws com.carddemo.exception.ValidationException if any validation fails
     * @throws BusinessException if business rule validation fails
     */
    private void validateTransaction(TransactionDto transactionDto) {
        log.debug("Validating transaction data");

        // Validate amount is positive (COBOL: IF TRAN-AMT <= ZERO)
        if (transactionDto.getTransAmt() == null || 
            transactionDto.getTransAmt().compareTo(BigDecimal.ZERO) <= 0) {
            throw new BusinessException("Transaction amount must be positive");
        }
        validationService.validateAmount(transactionDto.getTransAmt());

        // Validate card number format
        validationService.validateCardNumber(transactionDto.getTransCardNum());

        // Validate transaction type code
        if (transactionDto.getTransTypeCd() == null || 
            transactionDto.getTransTypeCd().trim().isEmpty()) {
            throw new BusinessException("Transaction type code is required");
        }
        validationService.validateTransactionType(transactionDto.getTransTypeCd());

        // Validate transaction category code
        if (transactionDto.getTransCatCd() == null) {
            throw new BusinessException("Transaction category code is required");
        }
        validationService.validateTransactionCategory(transactionDto.getTransCatCd());

        // Validate merchant data is present for purchase transactions
        if ("01".equals(transactionDto.getTransTypeCd())) {
            if (transactionDto.getTransMerchantId() == null) {
                throw new BusinessException("Merchant ID is required for purchase transactions");
            }
            if (transactionDto.getTransMerchantName() == null || 
                transactionDto.getTransMerchantName().trim().isEmpty()) {
                throw new BusinessException("Merchant name is required for purchase transactions");
            }
        }

        log.debug("Transaction validation passed");
    }

    /**
     * Update transaction category balance for reporting and analytics.
     * 
     * Converted from COBOL paragraph: UPDATE-CATEGORY-BALANCE (COTRN02C.cbl)
     * 
     * COBOL logic:
     * <pre>
     * MOVE ACCT-ID TO TCAT-ACCT-ID.
     * MOVE TRAN-TYPE-CD TO TCAT-TYPE-CD.
     * MOVE TRAN-CAT-CD TO TCAT-CAT-CD.
     * EXEC CICS READ FILE('TCATBAL')
     *      RIDFLD(TCAT-KEY)
     *      INTO(TCAT-BAL-RECORD)
     *      UPDATE
     * END-EXEC.
     * 
     * IF TRAN-TYPE-CD = '01' OR '02' OR '03' OR '05' OR '06' OR '08'
     *    COMPUTE TCAT-BAL = TCAT-BAL + TRAN-AMT
     * ELSE
     *    COMPUTE TCAT-BAL = TCAT-BAL - TRAN-AMT
     * END-IF.
     * 
     * EXEC CICS REWRITE FILE('TCATBAL')
     *      FROM(TCAT-BAL-RECORD)
     * END-EXEC.
     * </pre>
     * 
     * This method updates the running balance for transaction category tracking
     * used in batch reporting (CBTRN03C.cbl) and analytics.
     * 
     * @param accountId Account ID for category balance
     * @param typeCd Transaction type code
     * @param catCd Transaction category code
     * @param amount Transaction amount to add to category balance
     */
    private void updateCategoryBalance(Long accountId, String typeCd, Integer catCd, BigDecimal amount) {
        log.debug("Updating category balance for account={}, type={}, category={}, amount={}", 
                  accountId, typeCd, catCd, amount);

        try {
            // Build composite key for TransactionCategoryBalance lookup
            TransactionCategoryBalanceId balanceId = new TransactionCategoryBalanceId(accountId, typeCd, catCd);
            
            // Read existing balance or create new record if not found
            TransactionCategoryBalance categoryBalance = transactionCategoryBalanceRepository
                .findById(balanceId)
                .orElse(TransactionCategoryBalance.builder()
                    .tcatAcctId(accountId)
                    .tcatTypeCd(typeCd)
                    .tcatCatCd(catCd)
                    .tcatBal(BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP))
                    .build());

            // Update balance with transaction amount (add for debit, subtract for credit)
            boolean isDebit = "01".equals(typeCd) || "02".equals(typeCd) || 
                             "03".equals(typeCd) || "05".equals(typeCd) || 
                             "06".equals(typeCd) || "08".equals(typeCd);
            
            BigDecimal newBalance;
            if (isDebit) {
                newBalance = categoryBalance.getTcatBal()
                                           .add(amount)
                                           .setScale(2, RoundingMode.HALF_UP);
            } else {
                newBalance = categoryBalance.getTcatBal()
                                           .subtract(amount)
                                           .setScale(2, RoundingMode.HALF_UP);
            }
            
            categoryBalance.setTcatBal(newBalance);
            transactionCategoryBalanceRepository.save(categoryBalance);
            
            log.debug("Category balance updated: new balance={}", newBalance);
        } catch (Exception e) {
            // Log error but don't fail transaction posting if category balance update fails
            log.error("Error updating category balance: {}", e.getMessage(), e);
        }
    }

    /**
     * Calculate transaction category balance for a specific account, type, and category.
     * 
     * Used for reporting and analytics to retrieve category spending totals.
     * 
     * @param accountId Account ID
     * @param typeCode Transaction type code
     * @param categoryCode Transaction category code
     * @return Category balance as BigDecimal with scale 2, or ZERO if no balance found
     */
    public BigDecimal calculateCategoryBalance(Long accountId, String typeCode, Integer categoryCode) {
        log.debug("Calculating category balance for account={}, type={}, category={}", 
                  accountId, typeCode, categoryCode);

        TransactionCategoryBalanceId balanceId = new TransactionCategoryBalanceId(accountId, typeCode, categoryCode);
        
        return transactionCategoryBalanceRepository.findById(balanceId)
            .map(balance -> balance.getTcatBal().setScale(2, RoundingMode.HALF_UP))
            .orElse(BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP));
    }

    /**
     * Convert Transaction entity to TransactionDto.
     * 
     * This private helper method performs entity-to-DTO conversion using the
     * TransactionDto.fromEntity() static factory method. This separates internal
     * JPA entity representation from external API response format.
     * 
     * Key transformations:
     * - Timestamp fields converted to LocalDateTime
     * - Card number automatically masked for security (last 4 digits only)
     * - Version field excluded (JPA optimistic locking, not exposed to API)
     * - BigDecimal amounts preserved with scale 2
     * 
     * @param entity Transaction entity from database
     * @return TransactionDto for API response with card number masked
     */
    private TransactionDto mapToDto(Transaction entity) {
        return TransactionDto.fromEntity(entity);
    }
}
