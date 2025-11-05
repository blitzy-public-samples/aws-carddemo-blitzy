/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.carddemo.batch.writer;

import com.carddemo.entity.Account;
import com.carddemo.entity.Transaction;
import com.carddemo.repository.AccountRepository;
import com.carddemo.repository.TransactionRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.batch.item.Chunk;
import org.springframework.batch.item.ItemWriter;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;

/**
 * Spring Batch ItemWriter implementation for posting validated transactions to the permanent
 * transaction table and updating account balances atomically.
 * <p>
 * This class replaces the transaction posting logic from CBTRN02C.cbl:
 * - Lines 424-443: 2000-POST-TRANSACTION paragraph
 * - Lines 545-560: 2800-UPDATE-ACCOUNT-REC paragraph
 * - Lines 562-579: 2900-WRITE-TRANSACTION-FILE paragraph
 * </p>
 * <p>
 * The write operation performs multi-table updates within a single transaction boundary
 * following the exact COBOL sequence (lines 440-442):
 * 1. Updates account current balance: ACCT-CURR-BAL += TRAN-AMT (line 441)
 * 2. Updates account current cycle credit if amount >= 0 (line 441)
 * 3. Updates account current cycle debit if amount < 0 (line 441)
 * 4. Inserts transaction record to transaction table (line 442)
 * </p>
 * <p>
 * All operations are atomic - if any operation fails, all changes in the chunk are rolled back.
 * </p>
 * <p>
 * NOTE: Transaction category balance (TCATBAL) updates from COBOL lines 2700-UPDATE-TCATBAL
 * are handled separately to maintain separation of concerns and are not included in this writer.
 * </p>
 *
 * @see Transaction
 * @see Account
 * @see TransactionRepository
 * @see AccountRepository
 */
@Component
public class TransactionPostWriter implements ItemWriter<Transaction> {

    private static final Logger logger = LoggerFactory.getLogger(TransactionPostWriter.class);

    private final TransactionRepository transactionRepository;
    private final AccountRepository accountRepository;

    /**
     * Constructs a TransactionPostWriter with required repository dependencies.
     *
     * @param transactionRepository repository for transaction persistence
     * @param accountRepository     repository for account balance updates
     */
    public TransactionPostWriter(
            TransactionRepository transactionRepository,
            AccountRepository accountRepository) {
        this.transactionRepository = transactionRepository;
        this.accountRepository = accountRepository;
    }

    /**
     * Writes a chunk of validated transactions to the database and updates corresponding
     * account balances atomically.
     * <p>
     * This method implements the core posting logic from CBTRN02C.cbl:
     * </p>
     * <pre>
     * COBOL Logic (lines 424-443, 545-560, 562-579):
     * 2000-POST-TRANSACTION.
     *     MOVE DALYTRAN-fields TO TRAN-fields
     *     PERFORM Z-GET-DB2-FORMAT-TIMESTAMP
     *     MOVE DB2-FORMAT-TS TO TRAN-PROC-TS
     *     PERFORM 2800-UPDATE-ACCOUNT-REC
     *     PERFORM 2900-WRITE-TRANSACTION-FILE
     *
     * 2800-UPDATE-ACCOUNT-REC.
     *     ADD DALYTRAN-AMT TO ACCT-CURR-BAL
     *     IF DALYTRAN-AMT >= 0
     *        ADD DALYTRAN-AMT TO ACCT-CURR-CYC-CREDIT
     *     ELSE
     *        ADD DALYTRAN-AMT TO ACCT-CURR-CYC-DEBIT
     *     END-IF
     *     REWRITE FD-ACCTFILE-REC FROM ACCOUNT-RECORD
     *
     * 2900-WRITE-TRANSACTION-FILE.
     *     WRITE FD-TRANFILE-REC FROM TRAN-RECORD
     * </pre>
     *
     * @param chunk chunk of Transaction entities to be posted (chunk from Spring Batch)
     * @throws Exception if any database operation fails (triggers rollback of entire chunk)
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public void write(Chunk<? extends Transaction> chunk) throws Exception {
        logger.info("Starting transaction post writer for {} transactions", chunk.size());

        int successCount = 0;
        int errorCount = 0;

        for (Transaction transaction : chunk) {
            try {
                // Set processing timestamp (equivalent to Z-GET-DB2-FORMAT-TIMESTAMP in COBOL line 437)
                // COBOL: PERFORM Z-GET-DB2-FORMAT-TIMESTAMP
                // COBOL: MOVE DB2-FORMAT-TS TO TRAN-PROC-TS (line 438)
                transaction.setProcessingTimestamp(LocalDateTime.now());

                // Extract account ID and transaction amount for balance updates
                Long accountId = transaction.getAccountId();
                BigDecimal transactionAmount = transaction.getTransactionAmount();

                if (accountId == null) {
                    logger.error("Transaction {} has null accountId, skipping", transaction.getTransactionId());
                    errorCount++;
                    throw new IllegalStateException(
                            "Cannot post transaction with null accountId: " + transaction.getTransactionId());
                }

                if (transactionAmount == null) {
                    logger.error("Transaction {} has null amount, skipping", transaction.getTransactionId());
                    errorCount++;
                    throw new IllegalStateException(
                            "Cannot post transaction with null amount: " + transaction.getTransactionId());
                }

                // Ensure BigDecimal scale is set to 2 with HALF_UP rounding (COBOL COMP-3 equivalence)
                transactionAmount = transactionAmount.setScale(2, RoundingMode.HALF_UP);
                transaction.setTransactionAmount(transactionAmount);

                // Step 1: Update account balances FIRST (COBOL line 441: PERFORM 2800-UPDATE-ACCOUNT-REC)
                // This must happen before transaction save to ensure referential integrity
                // and proper error handling (if account not found, transaction should not be saved)
                updateAccountBalances(accountId, transactionAmount);
                logger.debug("Updated account balances for account: {}", accountId);

                // Step 2: Write transaction to database (COBOL line 442: PERFORM 2900-WRITE-TRANSACTION-FILE)
                // Transaction is saved AFTER account update to prevent orphan transactions
                transactionRepository.save(transaction);
                logger.debug("Saved transaction: {}", transaction.getTransactionId());

                successCount++;

            } catch (Exception e) {
                errorCount++;
                logger.error("Error posting transaction {}: {}",
                        transaction.getTransactionId(), e.getMessage(), e);
                // Re-throw to trigger rollback of entire chunk
                throw e;
            }
        }

        logger.info("Transaction post writer completed: {} successful, {} errors", successCount, errorCount);
    }

    /**
     * Updates account balances for a posted transaction.
     * <p>
     * Implements COBOL logic from lines 545-560 (2800-UPDATE-ACCOUNT-REC paragraph):
     * </p>
     * <pre>
     * ADD DALYTRAN-AMT TO ACCT-CURR-BAL                    (line 547)
     * IF DALYTRAN-AMT >= 0                                  (line 548)
     *    ADD DALYTRAN-AMT TO ACCT-CURR-CYC-CREDIT          (line 549)
     * ELSE                                                  (line 550)
     *    ADD DALYTRAN-AMT TO ACCT-CURR-CYC-DEBIT           (line 551)
     * END-IF                                                (line 552)
     * REWRITE FD-ACCTFILE-REC FROM ACCOUNT-RECORD          (line 554)
     * </pre>
     *
     * @param accountId         the account ID to update
     * @param transactionAmount the transaction amount (positive for credits, negative for debits)
     * @throws IllegalStateException if account is not found
     */
    private void updateAccountBalances(Long accountId, BigDecimal transactionAmount) {
        // Read account using AccountRepository.findById() (COBOL line 395: READ ACCOUNT-FILE)
        Account account = accountRepository.findById(accountId)
                .orElseThrow(() -> new IllegalStateException(
                        "Account not found for transaction posting: " + accountId));

        // Get current balances and ensure proper scale
        BigDecimal currentBalance = account.getCurrentBalance();
        BigDecimal currentCycleCredit = account.getCurrentCycleCredit();
        BigDecimal currentCycleDebit = account.getCurrentCycleDebit();

        // Initialize to zero if null (defensive programming)
        if (currentBalance == null) {
            currentBalance = BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);
        }
        if (currentCycleCredit == null) {
            currentCycleCredit = BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);
        }
        if (currentCycleDebit == null) {
            currentCycleDebit = BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);
        }

        // COBOL line 547: ADD DALYTRAN-AMT TO ACCT-CURR-BAL
        BigDecimal newBalance = currentBalance.add(transactionAmount)
                .setScale(2, RoundingMode.HALF_UP);
        account.setCurrentBalance(newBalance);

        // COBOL lines 548-552: IF DALYTRAN-AMT >= 0
        //                         ADD DALYTRAN-AMT TO ACCT-CURR-CYC-CREDIT
        //                      ELSE
        //                         ADD DALYTRAN-AMT TO ACCT-CURR-CYC-DEBIT
        if (transactionAmount.compareTo(BigDecimal.ZERO) >= 0) {
            // Positive amount - this is a credit (payment or refund)
            // COBOL line 549: ADD DALYTRAN-AMT TO ACCT-CURR-CYC-CREDIT
            BigDecimal newCycleCredit = currentCycleCredit.add(transactionAmount)
                    .setScale(2, RoundingMode.HALF_UP);
            account.setCurrentCycleCredit(newCycleCredit);
            
            logger.debug("Account {} credit transaction: amount={}, newBalance={}, newCycleCredit={}",
                    accountId, transactionAmount, newBalance, newCycleCredit);
        } else {
            // Negative amount - this is a debit (purchase or charge)
            // COBOL line 551: ADD DALYTRAN-AMT TO ACCT-CURR-CYC-DEBIT
            // Note: In COBOL, adding a negative value to debit accumulates the absolute value
            // The debit field accumulates negative values
            BigDecimal newCycleDebit = currentCycleDebit.add(transactionAmount)
                    .setScale(2, RoundingMode.HALF_UP);
            account.setCurrentCycleDebit(newCycleDebit);
            
            logger.debug("Account {} debit transaction: amount={}, newBalance={}, newCycleDebit={}",
                    accountId, transactionAmount, newBalance, newCycleDebit);
        }

        // COBOL line 554: REWRITE FD-ACCTFILE-REC FROM ACCOUNT-RECORD
        accountRepository.save(account);
        
        logger.debug("Updated account {}: balance {} -> {}", 
                accountId, currentBalance, newBalance);
    }
}
