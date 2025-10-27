/*
 * TransactionProcessor.java
 *
 * Spring Batch ItemProcessor implementation for transaction validation and processing.
 * Extracts business logic from COBOL batch programs CBTRN01C.cbl, CBTRN02C.cbl, and CBTRN03C.cbl.
 *
 * Converted from COBOL programs:
 * - CBTRN01C.cbl: Transaction file reading and card-account cross-reference validation
 * - CBTRN02C.cbl: Transaction posting with credit limit enforcement and balance updates
 * - CBTRN03C.cbl: Transaction category summarization (balance update logic)
 *
 * Original COBOL functions:
 * - 1000-DALYTRAN-GET-NEXT: Read daily transaction file (replaced by Spring Batch reader)
 * - 2000-LOOKUP-XREF: Card-account cross-reference lookup (validateCardXref method)
 * - 3000-READ-ACCOUNT: Account record retrieval (loadAccount method)
 * - 1500-VALIDATE-TRAN: Transaction validation orchestration (process method)
 * - 1500-A-LOOKUP-XREF: Card number verification (validateCardXref method)
 * - 1500-B-LOOKUP-ACCT: Account lookup with credit limit check (loadAccount + validateCreditLimit methods)
 * - 2700-UPDATE-TCATBAL: Category balance updates (updateCategoryBalance method)
 * - 2800-UPDATE-ACCOUNT-REC: Account balance updates (calculateNewBalance method)
 * - 2500-WRITE-REJECT-REC: Reject record handling (handleRejectedTransaction method)
 *
 * Business Logic Preservation:
 * - Credit limit calculation: COMPUTE WS-TEMP-BAL = ACCT-CURR-CYC-CREDIT - ACCT-CURR-CYC-DEBIT + DALYTRAN-AMT
 * - Credit limit check: IF ACCT-CREDIT-LIMIT >= WS-TEMP-BAL THEN approve ELSE reject (code 102)
 * - Account expiration check: IF ACCT-EXPIRAION-DATE >= DALYTRAN-ORIG-TS THEN valid ELSE reject (code 103)
 * - Balance update: ADD DALYTRAN-AMT TO ACCT-CURR-BAL
 * - Cycle credit/debit tracking: IF DALYTRAN-AMT >= 0 THEN ADD TO ACCT-CURR-CYC-CREDIT ELSE ADD TO ACCT-CURR-CYC-DEBIT
 * - Category balance: ADD DALYTRAN-AMT TO TRAN-CAT-BAL (create record if not found)
 *
 * COMP-3 Precision Preservation:
 * All BigDecimal arithmetic uses scale 2 with RoundingMode.HALF_UP to preserve exact
 * COBOL COMP-3 packed decimal precision per Section 0.7.2 requirement for bit-identical
 * financial calculations.
 *
 * Rejection Handling:
 * Per Spring Batch conventions, rejected transactions return null from process() method.
 * Spring Batch automatically filters null returns from the output, preventing rejected
 * transactions from being written to the database. This replaces COBOL 2500-WRITE-REJECT-REC
 * paragraph that wrote to DALYREJS-FILE.
 *
 * Error Codes (from COBOL WS-VALIDATION-FAIL-REASON):
 * - 100: Invalid card number (COBOL: 'INVALID CARD NUMBER FOUND')
 * - 101: Account not found (COBOL: 'ACCOUNT RECORD NOT FOUND')
 * - 102: Over limit transaction (COBOL: 'OVERLIMIT TRANSACTION')
 * - 103: Transaction after account expiration (COBOL: 'TRANSACTION RECEIVED AFTER ACCT EXPIRATION')
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
package com.carddemo.batch.processor;

import com.carddemo.exception.BusinessException;
import com.carddemo.exception.DataNotFoundException;
import com.carddemo.exception.ValidationException;
import com.carddemo.model.entity.Account;
import com.carddemo.model.entity.CardAccountXref;
import com.carddemo.model.entity.Transaction;
import com.carddemo.model.entity.TransactionCategoryBalance;
import com.carddemo.repository.AccountRepository;
import com.carddemo.repository.CardAccountXrefRepository;
import com.carddemo.repository.TransactionCategoryBalanceRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.batch.item.ItemProcessor;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Optional;

/**
 * Spring Batch ItemProcessor for transaction validation and processing.
 * 
 * <p>Implements ItemProcessor&lt;Transaction, Transaction&gt; interface where:
 * <ul>
 *   <li>Input: Daily transaction record from DALYTRAN file (Transaction entity)</li>
 *   <li>Output: Validated and processed transaction ready for posting, or null if rejected</li>
 * </ul>
 * </p>
 * 
 * <p>Processing Flow (replicates COBOL paragraph execution sequence):
 * <ol>
 *   <li>Validate card number via card-account cross-reference lookup (validateCardXref)</li>
 *   <li>Load account record and validate existence (loadAccount)</li>
 *   <li>Check credit limit against current cycle balance (validateCreditLimit)</li>
 *   <li>Check account expiration date against transaction date (validateAccountExpiration)</li>
 *   <li>Update transaction category balance aggregates (updateCategoryBalance)</li>
 *   <li>Calculate new account balance with cycle credit/debit tracking (calculateNewBalance)</li>
 *   <li>Return validated transaction for writing, or null if validation fails</li>
 * </ol>
 * </p>
 * 
 * <p>Rejection Strategy:
 * When validation fails, this processor logs the error and returns null per Spring Batch
 * ItemProcessor contract. Spring Batch automatically filters null returns from the output
 * stream, preventing rejected transactions from being written. This replaces the COBOL
 * pattern of writing rejected records to a separate DALYREJS-FILE.
 * </p>
 * 
 * <p>Transaction Management:
 * All database operations participate in the Spring Batch chunk transaction boundary.
 * Account and category balance updates are committed with the processed transaction in
 * a single database transaction, replicating COBOL EXEC CICS SYNCPOINT behavior.
 * </p>
 * 
 * @see Transaction input entity from daily transaction file
 * @see Account entity for balance updates
 * @see CardAccountXref entity for card validation
 * @see TransactionCategoryBalance entity for category balance tracking
 * @see org.springframework.batch.item.ItemProcessor Spring Batch processor interface
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TransactionProcessor implements ItemProcessor<Transaction, Transaction> {

    /**
     * Repository for card-account cross-reference lookups.
     * Replaces COBOL EXEC CICS READ FILE('XREFFILE') operations.
     */
    private final CardAccountXrefRepository cardAccountXrefRepository;

    /**
     * Repository for account record retrieval and updates.
     * Replaces COBOL EXEC CICS READ/REWRITE FILE('ACCTFILE') operations.
     */
    private final AccountRepository accountRepository;

    /**
     * Repository for transaction category balance updates.
     * Replaces COBOL EXEC CICS READ/WRITE/REWRITE FILE('TCATBAL') operations.
     */
    private final TransactionCategoryBalanceRepository transactionCategoryBalanceRepository;

    /**
     * Date formatter for transaction timestamp comparisons.
     * COBOL format: YYYY-MM-DD (PIC X(10))
     */
    private static final DateTimeFormatter DATE_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-DD");

    /**
     * Scale for BigDecimal operations to preserve COBOL COMP-3 precision.
     * All financial calculations use scale 2 per Section 0.7.2 requirement.
     */
    private static final int DECIMAL_SCALE = 2;

    /**
     * Rounding mode for BigDecimal operations matching COBOL COMP-3 rounding.
     * Uses HALF_UP (round 0.5 up) to match mainframe arithmetic behavior.
     */
    private static final RoundingMode ROUNDING_MODE = RoundingMode.HALF_UP;

    /**
     * Process a daily transaction record with validation and balance updates.
     * 
     * <p>Main processing method implementing Spring Batch ItemProcessor interface.
     * Orchestrates the complete transaction validation and processing workflow,
     * replicating COBOL programs CBTRN01C (validation), CBTRN02C (posting), and
     * CBTRN03C (category summarization) business logic.</p>
     * 
     * <p>Conversion from COBOL:</p>
     * <pre>
     * COBOL CBTRN02C.cbl Main Processing Loop:
     *   PERFORM UNTIL END-OF-FILE = 'Y'
     *     PERFORM 1000-DALYTRAN-GET-NEXT              [Replaced by Spring Batch reader]
     *     ADD 1 TO WS-TRANSACTION-COUNT
     *     MOVE 0 TO WS-VALIDATION-FAIL-REASON
     *     MOVE SPACES TO WS-VALIDATION-FAIL-REASON-DESC
     *     PERFORM 1500-VALIDATE-TRAN                  [This process() method]
     *     IF WS-VALIDATION-FAIL-REASON = 0
     *       PERFORM 2000-POST-TRANSACTION             [Balance updates in this method]
     *     ELSE
     *       ADD 1 TO WS-REJECT-COUNT
     *       PERFORM 2500-WRITE-REJECT-REC             [handleRejectedTransaction - return null]
     *     END-IF
     *   END-PERFORM
     * </pre>
     * 
     * <p>Processing steps:</p>
     * <ol>
     *   <li>Validate card number exists in cross-reference table (validateCardXref)</li>
     *   <li>Load account record and validate account exists (loadAccount)</li>
     *   <li>Validate credit limit not exceeded (validateCreditLimit)</li>
     *   <li>Validate account not expired (validateAccountExpiration)</li>
     *   <li>Update transaction category balance (updateCategoryBalance)</li>
     *   <li>Update account balance and cycle totals (calculateNewBalance)</li>
     * </ol>
     * 
     * <p>Rejection handling:
     * Returns null for rejected transactions per Spring Batch ItemProcessor convention.
     * Spring Batch automatically filters null returns, preventing rejected transactions
     * from being written to the output. Rejected transactions are logged with error details
     * for monitoring and reconciliation.
     * </p>
     * 
     * @param transaction Daily transaction record to process (from DALYTRAN file)
     *                    Converted from COBOL DALYTRAN-RECORD (CVTRA06Y.cpy)
     * @return Processed transaction ready for database insertion if validation succeeds,
     *         or null if validation fails (transaction rejected)
     * @throws Exception if unexpected processing error occurs (rare, most errors handled via null return)
     */
    @Override
    public Transaction process(Transaction transaction) throws Exception {
        log.info("Processing transaction ID: {}, Card Number: {}, Amount: {}", 
                 transaction.getTransId(), 
                 maskCardNumber(transaction.getTransCardNum()), 
                 transaction.getTransAmt());

        try {
            // Step 1: Validate card number and retrieve account cross-reference
            // COBOL: PERFORM 1500-A-LOOKUP-XREF (CBTRN02C lines 380-392)
            CardAccountXref cardXref = validateCardXref(transaction.getTransCardNum());
            
            // Step 2: Load account record for balance and limit validation
            // COBOL: PERFORM 1500-B-LOOKUP-ACCT (CBTRN02C lines 393-422)
            Account account = loadAccount(cardXref.getXrefAcctId());
            
            // Step 3: Validate credit limit not exceeded
            // COBOL: Lines 403-413 in CBTRN02C (COMPUTE WS-TEMP-BAL, IF ACCT-CREDIT-LIMIT >= WS-TEMP-BAL)
            validateCreditLimit(account, transaction.getTransAmt());
            
            // Step 4: Validate account not expired
            // COBOL: Lines 414-420 in CBTRN02C (IF ACCT-EXPIRAION-DATE >= DALYTRAN-ORIG-TS)
            validateAccountExpiration(account, transaction);
            
            // Step 5: Update transaction category balance
            // COBOL: PERFORM 2700-UPDATE-TCATBAL (CBTRN02C lines 467-542)
            updateCategoryBalance(account.getAcctId(), transaction.getTransTypeCd(), 
                                 transaction.getTransCatCd(), transaction.getTransAmt());
            
            // Step 6: Update account balance and cycle totals
            // COBOL: PERFORM 2800-UPDATE-ACCOUNT-REC (CBTRN02C lines 545-560)
            calculateNewBalance(account, transaction.getTransAmt());
            
            // Save updated account balances
            // COBOL: REWRITE FD-ACCTFILE-REC FROM ACCOUNT-RECORD (line 554)
            accountRepository.save(account);
            
            log.info("Transaction {} successfully processed and validated", transaction.getTransId());
            return transaction;
            
        } catch (DataNotFoundException e) {
            // Invalid card number or account not found (COBOL validation codes 100, 101)
            return handleRejectedTransaction(transaction, e.getMessage(), e);
            
        } catch (BusinessException e) {
            // Business rule violation: credit limit exceeded or account expired (codes 102, 103)
            return handleRejectedTransaction(transaction, e.getMessage(), e);
            
        } catch (ValidationException e) {
            // Field validation failure: invalid transaction type or category
            return handleRejectedTransaction(transaction, e.getMessage(), e);
            
        } catch (Exception e) {
            // Unexpected error: log and reject transaction
            log.error("Unexpected error processing transaction {}: {}", 
                     transaction.getTransId(), e.getMessage(), e);
            return handleRejectedTransaction(transaction, "Unexpected processing error", e);
        }
    }

    /**
     * Validate card number exists in card-account cross-reference table.
     * 
     * <p>Replaces COBOL paragraph 2000-LOOKUP-XREF from CBTRN01C.cbl (lines 227-240)
     * and paragraph 1500-A-LOOKUP-XREF from CBTRN02C.cbl (lines 380-392).</p>
     * 
     * <p>COBOL original logic:</p>
     * <pre>
     * 1500-A-LOOKUP-XREF.
     *     MOVE DALYTRAN-CARD-NUM TO FD-XREF-CARD-NUM
     *     READ XREF-FILE INTO CARD-XREF-RECORD
     *        INVALID KEY
     *          MOVE 100 TO WS-VALIDATION-FAIL-REASON
     *          MOVE 'INVALID CARD NUMBER FOUND'
     *            TO WS-VALIDATION-FAIL-REASON-DESC
     *        NOT INVALID KEY
     *            CONTINUE
     *     END-READ
     *     EXIT.
     * </pre>
     * 
     * <p>Card number validation ensures that:</p>
     * <ul>
     *   <li>Card exists in the card-account cross-reference table</li>
     *   <li>Card is linked to a valid account</li>
     *   <li>Card-account-customer relationship is established</li>
     * </ul>
     * 
     * <p>A card may be linked to multiple accounts (primary and authorized user scenarios),
     * but this method returns the first matching cross-reference record. In the CardDemo
     * system, each card is typically linked to exactly one account.</p>
     * 
     * @param cardNumber Card number from transaction (16 characters, e.g., "4111111111111111")
     *                   Converted from COBOL PIC X(16) DALYTRAN-CARD-NUM
     * @return CardAccountXref record containing account ID and customer ID
     * @throws DataNotFoundException if card number not found in cross-reference table
     *                              (COBOL WS-VALIDATION-FAIL-REASON = 100)
     */
    protected CardAccountXref validateCardXref(String cardNumber) {
        log.debug("Validating card number: {}", maskCardNumber(cardNumber));
        
        // Query cross-reference table by card number
        // COBOL: READ XREF-FILE INTO CARD-XREF-RECORD KEY IS FD-XREF-CARD-NUM
        List<CardAccountXref> xrefs = cardAccountXrefRepository.findByXrefCardNum(cardNumber);
        
        if (xrefs.isEmpty()) {
            // COBOL INVALID KEY condition (file-status 23)
            // MOVE 100 TO WS-VALIDATION-FAIL-REASON
            // MOVE 'INVALID CARD NUMBER FOUND' TO WS-VALIDATION-FAIL-REASON-DESC
            log.warn("Invalid card number: {}. Card not found in cross-reference table.", 
                    maskCardNumber(cardNumber));
            throw new DataNotFoundException("Invalid card number: " + maskCardNumber(cardNumber));
        }
        
        // Return first cross-reference record (cards typically have one account)
        CardAccountXref cardXref = xrefs.get(0);
        log.debug("Card {} validated successfully. Account ID: {}, Customer ID: {}", 
                 maskCardNumber(cardNumber), cardXref.getXrefAcctId(), cardXref.getXrefCustId());
        
        return cardXref;
    }

    /**
     * Load account record by account ID with existence validation.
     * 
     * <p>Replaces COBOL paragraph 3000-READ-ACCOUNT from CBTRN01C.cbl (lines 241-251)
     * and paragraph 1500-B-LOOKUP-ACCT from CBTRN02C.cbl (lines 393-422).</p>
     * 
     * <p>COBOL original logic:</p>
     * <pre>
     * 1500-B-LOOKUP-ACCT.
     *     MOVE XREF-ACCT-ID TO FD-ACCT-ID
     *     READ ACCOUNT-FILE INTO ACCOUNT-RECORD
     *        INVALID KEY
     *          MOVE 101 TO WS-VALIDATION-FAIL-REASON
     *          MOVE 'ACCOUNT RECORD NOT FOUND'
     *            TO WS-VALIDATION-FAIL-REASON-DESC
     *        NOT INVALID KEY
     *          DISPLAY 'SUCCESSFUL READ OF ACCOUNT FILE'
     *     END-READ
     *     EXIT.
     * </pre>
     * 
     * <p>Account loading ensures that:</p>
     * <ul>
     *   <li>Account exists in the account master file</li>
     *   <li>Account balances are available for credit limit validation</li>
     *   <li>Account expiration date is available for validation</li>
     *   <li>Account can be updated with posted transaction</li>
     * </ul>
     * 
     * @param accountId Account identifier (11-digit Long, e.g., 1000000001L)
     *                  Converted from COBOL PIC 9(11) XREF-ACCT-ID / ACCT-ID
     * @return Account entity with current balances and credit limits
     * @throws DataNotFoundException if account ID not found in account master file
     *                              (COBOL WS-VALIDATION-FAIL-REASON = 101)
     */
    protected Account loadAccount(Long accountId) {
        log.debug("Loading account record for account ID: {}", accountId);
        
        // Query account master file by account ID (primary key lookup)
        // COBOL: READ ACCOUNT-FILE INTO ACCOUNT-RECORD KEY IS FD-ACCT-ID
        Optional<Account> accountOpt = accountRepository.findById(accountId);
        
        if (accountOpt.isEmpty()) {
            // COBOL INVALID KEY condition (file-status 23)
            // MOVE 101 TO WS-VALIDATION-FAIL-REASON
            // MOVE 'ACCOUNT RECORD NOT FOUND' TO WS-VALIDATION-FAIL-REASON-DESC
            log.warn("Account {} not found in account master file", accountId);
            throw new DataNotFoundException("Account record not found: " + accountId);
        }
        
        Account account = accountOpt.get();
        log.debug("Account {} loaded successfully. Current balance: {}, Credit limit: {}", 
                 accountId, account.getAcctCurrBal(), account.getAcctCreditLimit());
        
        return account;
    }

    /**
     * Validate transaction amount does not exceed available credit limit.
     * 
     * <p>Replaces COBOL credit limit validation logic from CBTRN02C.cbl lines 403-413.</p>
     * 
     * <p>COBOL original logic:</p>
     * <pre>
     * COMPUTE WS-TEMP-BAL = ACCT-CURR-CYC-CREDIT
     *                     - ACCT-CURR-CYC-DEBIT
     *                     + DALYTRAN-AMT
     * 
     * IF ACCT-CREDIT-LIMIT >= WS-TEMP-BAL
     *   CONTINUE
     * ELSE
     *   MOVE 102 TO WS-VALIDATION-FAIL-REASON
     *   MOVE 'OVERLIMIT TRANSACTION'
     *     TO WS-VALIDATION-FAIL-REASON-DESC
     * END-IF
     * </pre>
     * 
     * <p>Credit limit validation algorithm:</p>
     * <ol>
     *   <li>Calculate net cycle balance: cycle_credit - cycle_debit</li>
     *   <li>Add transaction amount to net balance: net_balance + transaction_amount</li>
     *   <li>Compare result to credit limit: if result > credit_limit, reject</li>
     * </ol>
     * 
     * <p>The cycle credit and cycle debit fields track all transactions posted during
     * the current billing cycle. The net of these values represents the current balance
     * movement for the cycle. Adding the new transaction amount predicts the balance
     * after posting. If this predicted balance exceeds the credit limit, the transaction
     * is rejected as overlimit.</p>
     * 
     * <p>BigDecimal precision:
     * All calculations use BigDecimal with scale 2 and HALF_UP rounding to preserve
     * exact COBOL COMP-3 packed decimal arithmetic precision per Section 0.7.2 requirement.
     * This ensures bit-identical results to mainframe calculations for audit compliance.</p>
     * 
     * @param account Account entity with current balances and credit limit
     *                Contains: acctCreditLimit, acctCurrCycCredit, acctCurrCycDebit
     * @param transactionAmount Transaction amount to validate (positive = charge, negative = credit)
     *                          Converted from COBOL PIC S9(09)V99 COMP-3 DALYTRAN-AMT
     * @throws BusinessException if transaction would exceed credit limit
     *                          (COBOL WS-VALIDATION-FAIL-REASON = 102)
     */
    protected void validateCreditLimit(Account account, BigDecimal transactionAmount) {
        log.debug("Validating credit limit for account {}. Transaction amount: {}", 
                 account.getAcctId(), transactionAmount);
        
        // Calculate predicted balance after transaction posting
        // COBOL: COMPUTE WS-TEMP-BAL = ACCT-CURR-CYC-CREDIT - ACCT-CURR-CYC-DEBIT + DALYTRAN-AMT
        BigDecimal cycleCredit = account.getAcctCurrCycCredit();
        BigDecimal cycleDebit = account.getAcctCurrCycDebit();
        BigDecimal creditLimit = account.getAcctCreditLimit();
        
        // Preserve COBOL COMP-3 precision: scale 2, rounding HALF_UP
        BigDecimal netCycleBalance = cycleCredit.subtract(cycleDebit)
                                                .setScale(DECIMAL_SCALE, ROUNDING_MODE);
        BigDecimal predictedBalance = netCycleBalance.add(transactionAmount)
                                                     .setScale(DECIMAL_SCALE, ROUNDING_MODE);
        
        log.debug("Credit limit validation: Credit Limit={}, Cycle Credit={}, Cycle Debit={}, Net Cycle Balance={}, Predicted Balance={}", 
                 creditLimit, cycleCredit, cycleDebit, netCycleBalance, predictedBalance);
        
        // Validate predicted balance does not exceed credit limit
        // COBOL: IF ACCT-CREDIT-LIMIT >= WS-TEMP-BAL THEN approve ELSE reject
        if (predictedBalance.compareTo(creditLimit) > 0) {
            // Transaction would exceed credit limit - reject
            // MOVE 102 TO WS-VALIDATION-FAIL-REASON
            // MOVE 'OVERLIMIT TRANSACTION' TO WS-VALIDATION-FAIL-REASON-DESC
            log.warn("Transaction rejected: Overlimit. Account {}, Credit Limit: {}, Predicted Balance: {}", 
                    account.getAcctId(), creditLimit, predictedBalance);
            throw new BusinessException("Overlimit transaction: predicted balance " + 
                                      predictedBalance + " exceeds credit limit " + creditLimit);
        }
        
        log.debug("Credit limit validation passed for account {}", account.getAcctId());
    }

    /**
     * Validate transaction is not received after account expiration date.
     * 
     * <p>Replaces COBOL account expiration validation from CBTRN02C.cbl lines 414-420.</p>
     * 
     * <p>COBOL original logic:</p>
     * <pre>
     * IF ACCT-EXPIRAION-DATE >= DALYTRAN-ORIG-TS (1:10)
     *   CONTINUE
     * ELSE
     *   MOVE 103 TO WS-VALIDATION-FAIL-REASON
     *   MOVE 'TRANSACTION RECEIVED AFTER ACCT EXPIRATION'
     *     TO WS-VALIDATION-FAIL-REASON-DESC
     * END-IF
     * </pre>
     * 
     * <p>Expiration validation ensures that:</p>
     * <ul>
     *   <li>Transaction origination timestamp is before account expiration date</li>
     *   <li>Account has not expired at time of transaction</li>
     *   <li>Late-arriving transactions after expiration are rejected</li>
     * </ul>
     * 
     * <p>Date comparison logic:
     * COBOL compares ACCT-EXPIRAION-DATE (PIC X(10) format YYYY-MM-DD) with the first
     * 10 bytes of DALYTRAN-ORIG-TS (PIC X(26) format YYYY-MM-DD-HH.MM.SS.MMMMMM).
     * The substring (1:10) extracts just the date portion for comparison.</p>
     * 
     * <p>In Java, the Transaction.transOrigTs field is a Timestamp. We extract the
     * date portion using LocalDate for comparison with Account.acctExpirationDate.</p>
     * 
     * @param account Account entity with expiration date
     *                Contains: acctExpirationDate (nullable LocalDate)
     * @param transaction Transaction with origination timestamp
     *                    Contains: transOrigTs (Timestamp with date and time)
     * @throws BusinessException if transaction origination is after account expiration
     *                          (COBOL WS-VALIDATION-FAIL-REASON = 103)
     */
    protected void validateAccountExpiration(Account account, Transaction transaction) {
        log.debug("Validating account expiration for account {}. Transaction timestamp: {}", 
                 account.getAcctId(), transaction.getTransOrigTs());
        
        LocalDate expirationDate = account.getAcctExpirationDate();
        
        // Skip validation if account has no expiration date
        if (expirationDate == null) {
            log.debug("Account {} has no expiration date - validation skipped", account.getAcctId());
            return;
        }
        
        // Extract date portion from transaction origination timestamp
        // COBOL: DALYTRAN-ORIG-TS (1:10) extracts first 10 characters (YYYY-MM-DD)
        LocalDate transactionDate = transaction.getTransOrigTs().toLocalDateTime().toLocalDate();
        
        log.debug("Expiration validation: Account Expiration Date={}, Transaction Date={}", 
                 expirationDate, transactionDate);
        
        // Validate transaction date is not after account expiration
        // COBOL: IF ACCT-EXPIRAION-DATE >= DALYTRAN-ORIG-TS (1:10) THEN valid ELSE reject
        if (transactionDate.isAfter(expirationDate)) {
            // Transaction received after account expiration - reject
            // MOVE 103 TO WS-VALIDATION-FAIL-REASON
            // MOVE 'TRANSACTION RECEIVED AFTER ACCT EXPIRATION' TO WS-VALIDATION-FAIL-REASON-DESC
            log.warn("Transaction rejected: Account expired. Account {}, Expiration Date: {}, Transaction Date: {}", 
                    account.getAcctId(), expirationDate, transactionDate);
            throw new BusinessException("Transaction received after account expiration: expiration date " + 
                                      expirationDate + ", transaction date " + transactionDate);
        }
        
        log.debug("Account expiration validation passed for account {}", account.getAcctId());
    }

    /**
     * Calculate new account balance with cycle credit/debit tracking.
     * 
     * <p>Replaces COBOL paragraph 2800-UPDATE-ACCOUNT-REC from CBTRN02C.cbl lines 545-560.</p>
     * 
     * <p>COBOL original logic:</p>
     * <pre>
     * 2800-UPDATE-ACCOUNT-REC.
     *     ADD DALYTRAN-AMT  TO ACCT-CURR-BAL
     *     IF DALYTRAN-AMT >= 0
     *        ADD DALYTRAN-AMT TO ACCT-CURR-CYC-CREDIT
     *     ELSE
     *        ADD DALYTRAN-AMT TO ACCT-CURR-CYC-DEBIT
     *     END-IF
     *     
     *     REWRITE FD-ACCTFILE-REC FROM  ACCOUNT-RECORD
     *        INVALID KEY
     *          MOVE 109 TO WS-VALIDATION-FAIL-REASON
     *          MOVE 'ACCOUNT RECORD NOT FOUND'
     *            TO WS-VALIDATION-FAIL-REASON-DESC
     *     END-REWRITE.
     *     EXIT.
     * </pre>
     * 
     * <p>Balance calculation algorithm:</p>
     * <ol>
     *   <li>Update current account balance: balance += transaction_amount</li>
     *   <li>If transaction_amount >= 0: Update cycle credit += transaction_amount</li>
     *   <li>If transaction_amount < 0: Update cycle debit += transaction_amount</li>
     * </ol>
     * 
     * <p>Transaction amount semantics:
     * <ul>
     *   <li>Positive amount: Charge (purchase, cash advance, fee, interest)</li>
     *   <li>Negative amount: Credit (payment, refund, credit adjustment)</li>
     * </ul>
     * Cycle credit tracks all positive amounts, cycle debit tracks all negative amounts.
     * The net of (cycle_credit - cycle_debit) represents the billing cycle activity.
     * </p>
     * 
     * <p>BigDecimal precision:
     * All balance calculations use BigDecimal with scale 2 and HALF_UP rounding to
     * preserve exact COBOL COMP-3 packed decimal precision per Section 0.7.2. This
     * ensures financial accuracy and reconciliation with payment networks.
     * </p>
     * 
     * <p>Database update:
     * The COBOL REWRITE operation is replaced by accountRepository.save() in the
     * calling process() method after this calculation. JPA automatically updates
     * the account record within the Spring Batch chunk transaction boundary.
     * </p>
     * 
     * @param account Account entity to update with new balances (modified in place)
     *                Contains: acctCurrBal, acctCurrCycCredit, acctCurrCycDebit
     * @param transactionAmount Transaction amount to post (positive = charge, negative = credit)
     *                          Converted from COBOL PIC S9(09)V99 COMP-3 DALYTRAN-AMT
     */
    protected void calculateNewBalance(Account account, BigDecimal transactionAmount) {
        log.debug("Calculating new balance for account {}. Transaction amount: {}", 
                 account.getAcctId(), transactionAmount);
        
        BigDecimal currentBalance = account.getAcctCurrBal();
        BigDecimal currentCycleCredit = account.getAcctCurrCycCredit();
        BigDecimal currentCycleDebit = account.getAcctCurrCycDebit();
        
        // Update current account balance
        // COBOL: ADD DALYTRAN-AMT TO ACCT-CURR-BAL
        BigDecimal newBalance = currentBalance.add(transactionAmount)
                                             .setScale(DECIMAL_SCALE, ROUNDING_MODE);
        account.setAcctCurrBal(newBalance);
        
        // Update cycle credit or cycle debit based on transaction amount sign
        // COBOL: IF DALYTRAN-AMT >= 0 THEN ADD TO ACCT-CURR-CYC-CREDIT ELSE ADD TO ACCT-CURR-CYC-DEBIT
        if (transactionAmount.compareTo(BigDecimal.ZERO) >= 0) {
            // Positive amount: charge transaction - update cycle credit
            BigDecimal newCycleCredit = currentCycleCredit.add(transactionAmount)
                                                          .setScale(DECIMAL_SCALE, ROUNDING_MODE);
            account.setAcctCurrCycCredit(newCycleCredit);
            log.debug("Updated cycle credit: {} -> {}", currentCycleCredit, newCycleCredit);
        } else {
            // Negative amount: credit transaction - update cycle debit
            BigDecimal newCycleDebit = currentCycleDebit.add(transactionAmount)
                                                        .setScale(DECIMAL_SCALE, ROUNDING_MODE);
            account.setAcctCurrCycDebit(newCycleDebit);
            log.debug("Updated cycle debit: {} -> {}", currentCycleDebit, newCycleDebit);
        }
        
        log.debug("Balance calculation complete for account {}. Old Balance: {}, New Balance: {}", 
                 account.getAcctId(), currentBalance, newBalance);
    }

    /**
     * Update transaction category balance aggregate for account-type-category combination.
     * 
     * <p>Replaces COBOL paragraph 2700-UPDATE-TCATBAL from CBTRN02C.cbl lines 467-542,
     * including sub-paragraphs 2700-A-CREATE-TCATBAL-REC (lines 503-524) and
     * 2700-B-UPDATE-TCATBAL-REC (lines 526-542).</p>
     * 
     * <p>COBOL original logic:</p>
     * <pre>
     * 2700-UPDATE-TCATBAL.
     *     MOVE XREF-ACCT-ID TO FD-TRANCAT-ACCT-ID
     *     MOVE DALYTRAN-TYPE-CD TO FD-TRANCAT-TYPE-CD
     *     MOVE DALYTRAN-CAT-CD TO FD-TRANCAT-CD
     *     
     *     MOVE 'N' TO WS-CREATE-TRANCAT-REC
     *     READ TCATBAL-FILE INTO TRAN-CAT-BAL-RECORD
     *        INVALID KEY
     *          DISPLAY 'TCATBAL record not found for key : '
     *             FD-TRAN-CAT-KEY '.. Creating.'
     *          MOVE 'Y' TO WS-CREATE-TRANCAT-REC
     *     END-READ.
     *     
     *     IF WS-CREATE-TRANCAT-REC = 'Y'
     *        PERFORM 2700-A-CREATE-TCATBAL-REC
     *     ELSE
     *        PERFORM 2700-B-UPDATE-TCATBAL-REC
     *     END-IF
     *     EXIT.
     * 
     * 2700-A-CREATE-TCATBAL-REC.
     *     INITIALIZE TRAN-CAT-BAL-RECORD
     *     MOVE XREF-ACCT-ID TO TRANCAT-ACCT-ID
     *     MOVE DALYTRAN-TYPE-CD TO TRANCAT-TYPE-CD
     *     MOVE DALYTRAN-CAT-CD TO TRANCAT-CD
     *     ADD DALYTRAN-AMT TO TRAN-CAT-BAL
     *     WRITE FD-TRAN-CAT-BAL-RECORD FROM TRAN-CAT-BAL-RECORD
     *     ...
     * 
     * 2700-B-UPDATE-TCATBAL-REC.
     *     ADD DALYTRAN-AMT TO TRAN-CAT-BAL
     *     REWRITE FD-TRAN-CAT-BAL-RECORD FROM TRAN-CAT-BAL-RECORD
     *     ...
     * </pre>
     * 
     * <p>Category balance tracking:
     * The TCATBAL file maintains running balance aggregates for each combination of:
     * <ul>
     *   <li>Account ID (which account)</li>
     *   <li>Transaction Type Code (purchase, cash advance, payment, etc.)</li>
     *   <li>Transaction Category Code (grocery, gas, dining, etc.)</li>
     * </ul>
     * This enables spending analysis reports and customer insights by category.
     * </p>
     * 
     * <p>Create-or-update logic:
     * If the category balance record does not exist (COBOL INVALID KEY / file-status 23),
     * a new record is created with initial balance equal to the transaction amount.
     * If the record exists, the transaction amount is added to the existing balance.
     * This matches COBOL upsert pattern using READ followed by conditional WRITE or REWRITE.
     * </p>
     * 
     * <p>BigDecimal precision:
     * Category balance amounts use BigDecimal with scale 2 and HALF_UP rounding to
     * preserve COBOL COMP-3 precision per Section 0.7.2 requirement.
     * </p>
     * 
     * @param accountId Account identifier for category balance grouping
     *                  Converted from COBOL PIC 9(11) XREF-ACCT-ID / TRANCAT-ACCT-ID
     * @param transactionType Transaction type code (2 characters, e.g., "PU" for purchase)
     *                       Converted from COBOL PIC X(02) DALYTRAN-TYPE-CD / TRANCAT-TYPE-CD
     * @param categoryCode Transaction category code (4-digit integer, e.g., 5411 for grocery)
     *                    Converted from COBOL PIC 9(04) DALYTRAN-CAT-CD / TRANCAT-CD
     * @param transactionAmount Amount to add to category balance (positive or negative)
     *                          Converted from COBOL PIC S9(09)V99 COMP-3 DALYTRAN-AMT
     */
    protected void updateCategoryBalance(Long accountId, String transactionType, 
                                        Integer categoryCode, BigDecimal transactionAmount) {
        log.debug("Updating category balance for account {}, type: {}, category: {}, amount: {}", 
                 accountId, transactionType, categoryCode, transactionAmount);
        
        // Build composite key for category balance lookup
        // COBOL: MOVE XREF-ACCT-ID TO FD-TRANCAT-ACCT-ID
        //        MOVE DALYTRAN-TYPE-CD TO FD-TRANCAT-TYPE-CD
        //        MOVE DALYTRAN-CAT-CD TO FD-TRANCAT-CD
        TransactionCategoryBalance.TransactionCategoryBalanceId key = 
            new TransactionCategoryBalance.TransactionCategoryBalanceId(
                accountId, transactionType, categoryCode);
        
        // Attempt to read existing category balance record
        // COBOL: READ TCATBAL-FILE INTO TRAN-CAT-BAL-RECORD INVALID KEY ...
        Optional<TransactionCategoryBalance> balanceOpt = 
            transactionCategoryBalanceRepository.findById(key);
        
        TransactionCategoryBalance categoryBalance;
        
        if (balanceOpt.isEmpty()) {
            // Record not found - create new category balance record
            // COBOL: WS-CREATE-TRANCAT-REC = 'Y', PERFORM 2700-A-CREATE-TCATBAL-REC
            log.debug("Category balance record not found for key ({}, {}, {}). Creating new record.", 
                     accountId, transactionType, categoryCode);
            
            // COBOL: INITIALIZE TRAN-CAT-BAL-RECORD
            //        MOVE XREF-ACCT-ID TO TRANCAT-ACCT-ID
            //        MOVE DALYTRAN-TYPE-CD TO TRANCAT-TYPE-CD
            //        MOVE DALYTRAN-CAT-CD TO TRANCAT-CD
            //        ADD DALYTRAN-AMT TO TRAN-CAT-BAL
            categoryBalance = TransactionCategoryBalance.builder()
                .tcatAcctId(accountId)
                .tcatTypeCd(transactionType)
                .tcatCatCd(categoryCode)
                .tcatBal(transactionAmount.setScale(DECIMAL_SCALE, ROUNDING_MODE))
                .build();
            
        } else {
            // Record found - update existing category balance
            // COBOL: WS-CREATE-TRANCAT-REC = 'N', PERFORM 2700-B-UPDATE-TCATBAL-REC
            categoryBalance = balanceOpt.get();
            BigDecimal currentBalance = categoryBalance.getTcatBal();
            
            // COBOL: ADD DALYTRAN-AMT TO TRAN-CAT-BAL
            BigDecimal newBalance = currentBalance.add(transactionAmount)
                                                  .setScale(DECIMAL_SCALE, ROUNDING_MODE);
            categoryBalance.setTcatBal(newBalance);
            
            log.debug("Updated category balance: {} -> {}", currentBalance, newBalance);
        }
        
        // Save category balance (insert new or update existing)
        // COBOL: WRITE FD-TRAN-CAT-BAL-RECORD (for create) or
        //        REWRITE FD-TRAN-CAT-BAL-RECORD (for update)
        transactionCategoryBalanceRepository.save(categoryBalance);
        
        log.debug("Category balance updated successfully for account {}, type: {}, category: {}", 
                 accountId, transactionType, categoryCode);
    }

    /**
     * Handle rejected transaction by logging error and returning null.
     * 
     * <p>Replaces COBOL paragraph 2500-WRITE-REJECT-REC from CBTRN02C.cbl lines 446-465.</p>
     * 
     * <p>COBOL original logic:</p>
     * <pre>
     * 2500-WRITE-REJECT-REC.
     *     MOVE DALYTRAN-RECORD TO REJECT-TRAN-DATA
     *     MOVE WS-VALIDATION-TRAILER TO VALIDATION-TRAILER
     *     MOVE 8 TO APPL-RESULT
     *     WRITE FD-REJS-RECORD FROM REJECT-RECORD
     *     IF DALYREJS-STATUS = '00'
     *         MOVE 0 TO  APPL-RESULT
     *     ELSE
     *         MOVE 12 TO APPL-RESULT
     *     END-IF
     *     ...
     *     EXIT.
     * </pre>
     * 
     * <p>Rejection handling strategy:
     * In Spring Batch, returning null from ItemProcessor.process() method signals that
     * the item should be filtered out and not written to the output. This replaces the
     * COBOL pattern of writing rejected records to a separate DALYREJS-FILE.
     * </p>
     * 
     * <p>Rejected transactions are logged with full error details including:</p>
     * <ul>
     *   <li>Transaction ID</li>
     *   <li>Card number (masked for security)</li>
     *   <li>Transaction amount</li>
     *   <li>Rejection reason (validation error message)</li>
     *   <li>Exception stack trace</li>
     * </ul>
     * 
     * <p>These logs serve the same audit purpose as the COBOL DALYREJS-FILE and can be
     * exported for reconciliation, dispute resolution, and regulatory reporting.</p>
     * 
     * <p>Alternative implementations could write rejected transactions to a separate
     * database table or publish rejection events to a message queue for asynchronous
     * processing, but the current implementation follows Spring Batch best practices
     * of using null returns for filtering.</p>
     * 
     * @param transaction Rejected transaction record
     * @param reason Rejection reason message (e.g., "Invalid card number", "Overlimit")
     * @param exception Exception that caused rejection (for stack trace logging)
     * @return null to signal Spring Batch to filter this transaction from output
     */
    protected Transaction handleRejectedTransaction(Transaction transaction, String reason, Exception exception) {
        log.warn("Transaction {} REJECTED. Card: {}, Amount: {}, Reason: {}",
                transaction.getTransId(),
                maskCardNumber(transaction.getTransCardNum()),
                transaction.getTransAmt(),
                reason,
                exception);
        
        // Return null per Spring Batch ItemProcessor contract
        // Spring Batch automatically filters null returns from output stream
        // This replaces COBOL WRITE FD-REJS-RECORD to DALYREJS-FILE
        return null;
    }

    /**
     * Mask card number for secure logging (show only last 4 digits).
     * 
     * <p>Security requirement:
     * Card numbers are sensitive PII and must never be logged in full per PCI-DSS
     * compliance requirements. This method masks all but the last 4 digits.</p>
     * 
     * <p>Example: "4111111111111111" → "************1111"</p>
     * 
     * @param cardNumber Full 16-digit card number
     * @return Masked card number with only last 4 digits visible
     */
    private String maskCardNumber(String cardNumber) {
        if (cardNumber == null || cardNumber.length() < 4) {
            return "****";
        }
        int maskLength = cardNumber.length() - 4;
        return "*".repeat(maskLength) + cardNumber.substring(maskLength);
    }
}
