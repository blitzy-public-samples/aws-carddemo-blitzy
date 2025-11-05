/*
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

import com.carddemo.constants.BalanceType;
import com.carddemo.entity.Account;
import com.carddemo.entity.AccountBalance;
import com.carddemo.entity.Transaction;
import com.carddemo.repository.TransactionRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.batch.item.ItemProcessor;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/**
 * Spring Batch ItemProcessor for calculating account balances.
 * 
 * <p>This processor transforms Account entities into AccountBalance entities
 * by aggregating all transactions for each account and calculating the
 * resulting balance with COMP-3 precision preservation.</p>
 * 
 * <p>COBOL COMP-3 Precision Mapping:
 * COBOL: 01 BALANCE PIC S9(13)V99 COMP-3
 * Java: BigDecimal with scale 2, RoundingMode.HALF_UP</p>
 * 
 * <p>Balance Calculation Logic:
 * newBalance = previousBalance + SUM(credits) - SUM(debits)</p>
 * 
 * <p>Migrated from: CBACT03C.cbl (Account Balance Calculation Batch Program)</p>
 * 
 * @author CardDemo Migration Team
 * @version 1.0
 * @since 2024-01-01
 */
@Component
public class AccountBalanceProcessor implements ItemProcessor<Account, AccountBalance> {

    private static final Logger logger = LoggerFactory.getLogger(AccountBalanceProcessor.class);

    /**
     * Scale for BigDecimal calculations matching COBOL COMP-3 PIC S9(13)V99.
     * Two decimal places for currency precision.
     */
    private static final int DECIMAL_SCALE = 2;

    /**
     * Rounding mode for BigDecimal calculations matching COBOL COMP-3 behavior.
     * HALF_UP ensures identical rounding to mainframe COBOL COMP-3 arithmetic.
     */
    private static final RoundingMode ROUNDING_MODE = RoundingMode.HALF_UP;

    /**
     * Repository for accessing transaction data.
     * Used to aggregate all transactions for a given account.
     */
    private final TransactionRepository transactionRepository;

    /**
     * Constructor for dependency injection.
     * 
     * @param transactionRepository Repository for transaction data access
     */
    public AccountBalanceProcessor(TransactionRepository transactionRepository) {
        this.transactionRepository = transactionRepository;
    }

    /**
     * Processes an Account entity and calculates its balance.
     * 
     * <p>Processing Steps:
     * 1. Retrieve the account's previous/opening balance
     * 2. Query all transactions for the account
     * 3. Aggregate credit transactions (deposits, credits)
     * 4. Aggregate debit transactions (withdrawals, charges)
     * 5. Calculate new balance: openingBalance + credits - debits
     * 6. Apply COMP-3 precision (scale 2, HALF_UP rounding)
     * 7. Create and return AccountBalance entity</p>
     * 
     * <p>Transaction Aggregation Logic:
     * - Credits: Transactions with positive amounts (transaction type codes: 01, 04, 07)
     * - Debits: Transactions with negative amounts (transaction type codes: 02, 03, 05, 06)</p>
     * 
     * <p>COMP-3 Precision Preservation:
     * All BigDecimal operations use setScale(2, RoundingMode.HALF_UP) to maintain
     * exact equivalence with COBOL COMP-3 PIC S9(13)V99 packed decimal fields.</p>
     * 
     * @param account The account entity to process
     * @return AccountBalance entity with calculated balance, or null if account has no transactions
     * @throws Exception if an error occurs during processing
     */
    @Override
    public AccountBalance process(Account account) throws Exception {
        if (account == null) {
            logger.warn("Received null account for processing, skipping");
            return null;
        }

        Long accountId = account.getAccountId();
        logger.debug("Processing account balance calculation for account ID: {}", accountId);

        try {
            // Step 1: Retrieve opening balance from account
            BigDecimal openingBalance = account.getCurrentBalance();
            if (openingBalance == null) {
                openingBalance = BigDecimal.ZERO;
            }
            // Apply COMP-3 precision to opening balance
            openingBalance = openingBalance.setScale(DECIMAL_SCALE, ROUNDING_MODE);

            logger.debug("Account {} opening balance: {}", accountId, openingBalance);

            // Step 2: Query all transactions for this account
            List<Transaction> transactions = transactionRepository.findByAccountId(accountId);

            if (transactions == null || transactions.isEmpty()) {
                logger.info("No transactions found for account {}, skipping balance calculation", accountId);
                return null;
            }

            logger.debug("Found {} transactions for account {}", transactions.size(), accountId);

            // Step 3 & 4: Aggregate credits and debits with COMP-3 precision
            BigDecimal totalCredits = BigDecimal.ZERO.setScale(DECIMAL_SCALE, ROUNDING_MODE);
            BigDecimal totalDebits = BigDecimal.ZERO.setScale(DECIMAL_SCALE, ROUNDING_MODE);

            for (Transaction transaction : transactions) {
                BigDecimal amount = transaction.getTransactionAmount();
                if (amount == null) {
                    logger.warn("Transaction {} has null amount, skipping", transaction.getTransactionId());
                    continue;
                }

                // Apply COMP-3 precision to transaction amount
                amount = amount.setScale(DECIMAL_SCALE, ROUNDING_MODE);

                // Determine if transaction is credit or debit based on transaction type
                String transactionTypeCode = transaction.getTransactionTypeCode();
                
                if (isCreditTransaction(transactionTypeCode, amount)) {
                    // Credit transactions increase account balance
                    totalCredits = totalCredits.add(amount.abs()).setScale(DECIMAL_SCALE, ROUNDING_MODE);
                    logger.trace("Added credit: {} from transaction {}", amount, transaction.getTransactionId());
                } else {
                    // Debit transactions decrease account balance
                    totalDebits = totalDebits.add(amount.abs()).setScale(DECIMAL_SCALE, ROUNDING_MODE);
                    logger.trace("Added debit: {} from transaction {}", amount, transaction.getTransactionId());
                }
            }

            logger.debug("Account {} - Total credits: {}, Total debits: {}", 
                        accountId, totalCredits, totalDebits);

            // Step 5: Calculate closing balance with COMP-3 precision
            // Formula: closingBalance = openingBalance + credits - debits
            BigDecimal closingBalance = openingBalance
                .add(totalCredits)
                .subtract(totalDebits)
                .setScale(DECIMAL_SCALE, ROUNDING_MODE);

            logger.info("Account {} balance calculation complete - Opening: {}, Credits: {}, Debits: {}, Closing: {}",
                       accountId, openingBalance, totalCredits, totalDebits, closingBalance);

            // Step 6: Create and populate AccountBalance entity
            AccountBalance accountBalance = new AccountBalance();
            accountBalance.setAccountId(accountId);
            accountBalance.setBalanceType(BalanceType.HISTORICAL); // CBACT03C creates historical snapshots
            accountBalance.setOpeningBalance(openingBalance);
            accountBalance.setCreditAmount(totalCredits);
            accountBalance.setDebitAmount(totalDebits);
            accountBalance.setBalanceAmount(closingBalance);
            accountBalance.setEffectiveDate(LocalDate.now());
            accountBalance.setCreatedDate(LocalDateTime.now()); // Set audit timestamp for record creation
            
            // Calculate and set closing balance using the entity's calculation method
            accountBalance.calculateClosingBalance();

            // Log warning if closing balance is negative (business rule validation)
            if (closingBalance.compareTo(BigDecimal.ZERO) < 0) {
                logger.warn("Account {} has negative balance: {}", accountId, closingBalance);
            }

            return accountBalance;

        } catch (Exception e) {
            logger.error("Error processing balance for account {}: {}", accountId, e.getMessage(), e);
            throw new Exception("Failed to calculate balance for account " + accountId, e);
        }
    }

    /**
     * Determines if a transaction is a credit (increases balance) or debit (decreases balance).
     * 
     * <p>Transaction Type Code Mapping (from COBOL CVTRA02Y copybook):
     * - '01': Purchase/Sale - Credit
     * - '02': Cash Advance - Debit
     * - '03': Payment - Credit
     * - '04': Refund - Credit
     * - '05': Fee - Debit
     * - '06': Interest Charge - Debit
     * - '07': Deposit - Credit</p>
     * 
     * <p>Fallback Logic:
     * If transaction type is unknown, use amount sign:
     * - Positive amount = Credit
     * - Negative amount = Debit</p>
     * 
     * @param transactionTypeCode The transaction type code
     * @param amount The transaction amount
     * @return true if transaction is a credit, false if debit
     */
    private boolean isCreditTransaction(String transactionTypeCode, BigDecimal amount) {
        if (transactionTypeCode == null || transactionTypeCode.trim().isEmpty()) {
            // Fallback: use amount sign
            return amount.compareTo(BigDecimal.ZERO) >= 0;
        }

        // Normalize transaction type code (trim and uppercase)
        String normalizedCode = transactionTypeCode.trim().toUpperCase();

        // Credit transaction types (increase balance)
        switch (normalizedCode) {
            case "01": // Purchase/Sale
            case "03": // Payment
            case "04": // Refund
            case "07": // Deposit
            case "CREDIT":
            case "CR":
            case "DEP":
            case "DEPOSIT":
            case "PAYMENT":
            case "REFUND":
                return true;

            // Debit transaction types (decrease balance)
            case "02": // Cash Advance
            case "05": // Fee
            case "06": // Interest Charge
            case "DEBIT":
            case "DR":
            case "DB": // Debit abbreviation used in test data
            case "FEE":
            case "CHARGE":
            case "WITHDRAWAL":
                return false;

            default:
                // Unknown transaction type - use amount sign as fallback
                logger.debug("Unknown transaction type code: {}, using amount sign for classification", normalizedCode);
                return amount.compareTo(BigDecimal.ZERO) >= 0;
        }
    }
}
