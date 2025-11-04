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

import com.carddemo.entity.Account;
import com.carddemo.entity.AccountGroup;
import com.carddemo.entity.AccountXref;
import com.carddemo.entity.Transaction;
import com.carddemo.entity.TransactionAggregate;
import com.carddemo.repository.AccountRepository;
import com.carddemo.repository.AccountXrefRepository;
import com.carddemo.util.DateUtils;
import com.carddemo.util.DecimalUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.batch.item.ItemProcessor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Spring Batch ItemProcessor implementation for COBOL CBACT04C.cbl interest calculation batch program.
 * 
 * <p><strong>CRITICAL: This processor maintains EXACT interest calculation formula precision from
 * COBOL CBACT04C.cbl lines 464-465 with mandatory BigDecimal scale preservation per Section 0.9.</strong></p>
 * 
 * <p>Transforms COBOL mainframe batch interest calculation logic to Java Spring Batch chunk-oriented
 * processing. Implements ItemProcessor&lt;TransactionAggregate, Transaction&gt; interface to process
 * transaction category balance aggregations and generate interest charge transaction records.</p>
 * 
 * <p><strong>COBOL Source Transformation:</strong></p>
 * <pre>
 * COBOL Program: CBACT04C.cbl (lines 1-6: Interest Calculator Batch Program)
 * Key Logic: Lines 464-465
 *   COMPUTE WS-MONTHLY-INT = (TRAN-CAT-BAL * DIS-INT-RATE) / 1200
 *   
 * Java Equivalent (with CRITICAL precision preservation):
 *   BigDecimal monthlyInterest = balance
 *       .multiply(interestRate)
 *       .divide(new BigDecimal("1200"), 2, RoundingMode.HALF_UP);
 * </pre>
 * 
 * <p><strong>Business Logic Preservation (Section 0.1):</strong></p>
 * <ul>
 *   <li>Preserves EXACT COBOL COMP-3 decimal precision using BigDecimal with RoundingMode.HALF_UP</li>
 *   <li>Balance amounts: PIC S9(09)V99 → BigDecimal scale 2 (11 total digits)</li>
 *   <li>Interest rates: PIC S9(3)V9(5) → BigDecimal scale 5 (8 total digits) - Note: May be stored as scale 2 percentage</li>
 *   <li>Monthly interest result: BigDecimal scale 2 matching COBOL WS-MONTHLY-INT PIC S9(09)V99</li>
 *   <li>Formula: monthlyInterest = (balance * interestRate) / 1200 with explicit scale and rounding</li>
 *   <li>Zero-interest rate validation: Returns null to filter accounts with zero interest rates</li>
 * </ul>
 * 
 * <p><strong>Input/Output Contract:</strong></p>
 * <ul>
 *   <li>Input: TransactionAggregate from TCATBAL-FILE sequential read (account ID, type, category, balance)</li>
 *   <li>Output: Transaction entity with interest charge (type '01', category '05', source 'System')</li>
 *   <li>Returns null: For zero-interest rate accounts enabling Spring Batch skip logic</li>
 * </ul>
 * 
 * <p><strong>Processing Steps (matching COBOL CBACT04C.cbl logic):</strong></p>
 * <ol>
 *   <li>Retrieve Account entity by accountId to extract accountGroupId (COBOL line 202-203)</li>
 *   <li>Lookup AccountGroup by (accountGroupId, transactionTypeCode, transactionCategoryCode) for interest rate (COBOL lines 210-213)</li>
 *   <li>Validate interest rate is non-zero (COBOL line 214: IF DIS-INT-RATE NOT = 0)</li>
 *   <li>Calculate monthly interest using EXACT formula (COBOL lines 464-465)</li>
 *   <li>Lookup AccountXref to retrieve card number for transaction record (COBOL lines 204-205)</li>
 *   <li>Generate transaction ID using statement date + suffix (COBOL lines 476-480)</li>
 *   <li>Create Transaction entity with interest amount and metadata (COBOL lines 482-498)</li>
 *   <li>Return Transaction for writing to transaction table by ItemWriter</li>
 * </ol>
 * 
 * <p><strong>Data Type Precision Mapping (Section 0.3 and Section 0.9):</strong></p>
 * <table>
 *   <tr>
 *     <th>COBOL Field</th>
 *     <th>COBOL Type</th>
 *     <th>Java Type</th>
 *     <th>Precision</th>
 *     <th>Scale</th>
 *     <th>Rounding</th>
 *   </tr>
 *   <tr>
 *     <td>TRAN-CAT-BAL</td>
 *     <td>PIC S9(09)V99 COMP-3</td>
 *     <td>BigDecimal</td>
 *     <td>11</td>
 *     <td>2</td>
 *     <td>HALF_UP</td>
 *   </tr>
 *   <tr>
 *     <td>DIS-INT-RATE</td>
 *     <td>PIC S9(3)V9(5) COMP-3</td>
 *     <td>BigDecimal</td>
 *     <td>8</td>
 *     <td>5 (or 2 as percentage)</td>
 *     <td>HALF_UP</td>
 *   </tr>
 *   <tr>
 *     <td>WS-MONTHLY-INT</td>
 *     <td>PIC S9(09)V99</td>
 *     <td>BigDecimal</td>
 *     <td>11</td>
 *     <td>2</td>
 *     <td>HALF_UP</td>
 *   </tr>
 * </table>
 * 
 * <p><strong>Regulatory Compliance and Audit Trail (Section 0.9):</strong></p>
 * <ul>
 *   <li>Comprehensive SLF4J logging captures all interest calculations for regulatory audit trail</li>
 *   <li>Logs: account ID, interest rate, balance, computed interest, transaction ID, timestamp</li>
 *   <li>Financial institution compliance requirements for interest accrual accuracy maintained</li>
 *   <li>Zero data loss or corruption during calculation with complete traceability</li>
 * </ul>
 * 
 * <p><strong>Dependency Injection:</strong></p>
 * <ul>
 *   <li>AccountRepository: Retrieve account master data for accountGroupId extraction</li>
 *   <li>AccountXrefRepository: Lookup card number via XREF cross-reference file equivalent</li>
 *   <li>DateUtils: Generate DB2-format timestamps for transaction origination and processing</li>
 *   <li>DecimalUtils: Ensure COBOL COMP-3 precision preservation for all arithmetic operations</li>
 * </ul>
 * 
 * <p><strong>Spring Batch Integration:</strong></p>
 * <ul>
 *   <li>Chunk-oriented processing: Processes transaction aggregates in configurable chunks (typically 1000 records)</li>
 *   <li>Skip logic support: Returns null for zero-interest accounts enabling ItemWriter skip without error</li>
 *   <li>Transaction boundaries: Processing occurs within Spring Batch transaction managed by job framework</li>
 *   <li>Error handling: Throws exceptions for data integrity issues enabling retry/skip policies</li>
 *   <li>Performance: Stateless processing supports parallel execution and scalability to 10,000 TPS</li>
 * </ul>
 * 
 * <p><strong>Critical Notes:</strong></p>
 * <ul>
 *   <li>This processor is FOUNDATIONAL for financial institution regulatory compliance</li>
 *   <li>ANY modification to the interest calculation formula requires regulatory approval</li>
 *   <li>Precision preservation is NON-NEGOTIABLE per Section 0.1 and Section 0.9 requirements</li>
 *   <li>100% functional equivalence with COBOL CBACT04C.cbl is mandatory for migration success</li>
 * </ul>
 * 
 * @see TransactionAggregate
 * @see Transaction
 * @see Account
 * @see AccountGroup
 * @see AccountXref
 * @see <a href="Section 0.1">Business Logic Preservation Mandate</a>
 * @see <a href="Section 0.3">COBOL to Java Type Conversion Rules</a>
 * @see <a href="Section 0.6">Interest Calculation Job Transformation</a>
 * @see <a href="Section 0.9">Critical Numeric Precision Requirements</a>
 * @author CardDemo Migration Team
 * @version 1.0
 * @since 2024-01-01
 */
@Component
public class InterestCalculationProcessor implements ItemProcessor<TransactionAggregate, Transaction> {

    /**
     * SLF4J logger for comprehensive audit trail and regulatory compliance logging.
     * Captures all interest calculations with account ID, rates, balances, and computed interest
     * per Section 0.9 audit trail completeness requirements.
     */
    private static final Logger logger = LoggerFactory.getLogger(InterestCalculationProcessor.class);

    /**
     * Transaction type code for interest charges.
     * Maps to COBOL line 482: MOVE '01' TO TRAN-TYPE-CD
     * Type '01' represents interest charge transactions in the transaction type reference data.
     */
    private static final String INTEREST_TRANSACTION_TYPE_CODE = "01";

    /**
     * Transaction category code for interest charges.
     * Maps to COBOL line 483: MOVE '05' TO TRAN-CAT-CD
     * Category '05' represents interest category within type '01' transactions.
     */
    private static final Integer INTEREST_TRANSACTION_CATEGORY_CODE = 5;

    /**
     * Transaction source identifier for system-generated interest transactions.
     * Maps to COBOL line 484: MOVE 'System' TO TRAN-SOURCE
     * Distinguishes automated interest calculations from user-initiated transactions.
     */
    private static final String INTEREST_TRANSACTION_SOURCE = "System";

    /**
     * Divisor constant for monthly interest calculation.
     * Maps to COBOL line 465: / 1200
     * Formula: 1200 = 12 months * 100 (percentage to decimal conversion)
     * CRITICAL: Must be BigDecimal to maintain precision in division operation.
     */
    private static final BigDecimal MONTHLY_DIVISOR = new BigDecimal("1200");

    /**
     * Date formatter for transaction ID generation.
     * Maps to COBOL lines 476-480: STRING PARM-DATE, WS-TRANID-SUFFIX
     * Format: YYYYMMDD for the date component of the 16-character transaction ID.
     */
    private static final DateTimeFormatter TRANSACTION_ID_DATE_FORMATTER = DateTimeFormatter.ofPattern("yyyyMMdd");

    /**
     * Atomic counter for transaction ID suffix generation.
     * Maps to COBOL line 173-174: WS-TRANID-SUFFIX PIC 9(06) VALUE 0
     * Incremented for each generated interest transaction (COBOL line 474: ADD 1 TO WS-TRANID-SUFFIX).
     * Thread-safe for concurrent processing in Spring Batch parallel execution mode.
     */
    private final AtomicLong transactionIdSuffix = new AtomicLong(0);

    /**
     * Spring Data JPA repository for Account entity operations.
     * Used to retrieve account master data for accountGroupId extraction.
     * Maps to COBOL lines 202-203, 291, 1100-GET-ACCT-DATA paragraph.
     */
    private final AccountRepository accountRepository;

    /**
     * Spring Data JPA repository for AccountXref cross-reference lookups.
     * Used to retrieve card number for interest transaction record population.
     * Maps to COBOL lines 204-205, 1110-GET-XREF-DATA paragraph (lines 394-398).
     */
    private final AccountXrefRepository accountXrefRepository;

    /**
     * Spring Data JPA repository for AccountGroup discount group lookups.
     * Used to retrieve interest rate configuration by account group ID, transaction type, and category.
     * Maps to COBOL lines 210-213, 1200-GET-INTEREST-RATE paragraph (lines 416-420).
     * Note: This repository interface is created as a local inner interface since AccountGroupRepository
     * is not in the depends_on_files list, but AccountGroup entity is available.
     */
    private final AccountGroupRepository accountGroupRepository;

    /**
     * Constructor for Spring dependency injection.
     * 
     * <p>Injects all required repositories and utility classes for interest calculation processing.
     * Spring Framework automatically provides implementations via @Autowired constructor injection.</p>
     * 
     * @param accountRepository JPA repository for Account entity operations
     * @param accountXrefRepository JPA repository for AccountXref cross-reference lookups
     * @param accountGroupRepository JPA repository for AccountGroup interest rate lookups
     */
    @Autowired
    public InterestCalculationProcessor(
            AccountRepository accountRepository,
            AccountXrefRepository accountXrefRepository,
            AccountGroupRepository accountGroupRepository) {
        this.accountRepository = accountRepository;
        this.accountXrefRepository = accountXrefRepository;
        this.accountGroupRepository = accountGroupRepository;
        
        logger.info("InterestCalculationProcessor initialized - COBOL CBACT04C.cbl interest calculator equivalent");
    }

    /**
     * Processes a single transaction aggregate record to calculate and generate interest charge transaction.
     * 
     * <p><strong>CRITICAL: Implements EXACT interest calculation formula from COBOL CBACT04C.cbl lines 464-465
     * with mandatory BigDecimal precision preservation per Section 0.9 requirements.</strong></p>
     * 
     * <p><strong>Formula:</strong> monthlyInterest = (categoryBalance * interestRate) / 1200</p>
     * 
     * <p><strong>Processing Logic (matching COBOL CBACT04C.cbl):</strong></p>
     * <ol>
     *   <li>Extract account ID, transaction type, category, and balance from input TransactionAggregate</li>
     *   <li>Retrieve Account entity to extract accountGroupId for interest rate lookup</li>
     *   <li>Lookup AccountGroup to retrieve interest rate for account group + type + category combination</li>
     *   <li>Validate interest rate is non-zero (COBOL line 214: IF DIS-INT-RATE NOT = 0)</li>
     *   <li>Calculate monthly interest using EXACT formula with BigDecimal precision (COBOL lines 464-465)</li>
     *   <li>Lookup AccountXref to retrieve card number for transaction record (COBOL lines 204-205)</li>
     *   <li>Generate unique transaction ID using date + atomic counter (COBOL lines 476-480)</li>
     *   <li>Create Transaction entity with interest amount and required metadata (COBOL lines 482-498)</li>
     *   <li>Set transaction timestamps using DateUtils DB2-format generation (COBOL line 496)</li>
     *   <li>Return Transaction entity for persistence by ItemWriter</li>
     * </ol>
     * 
     * <p><strong>Zero-Interest Rate Handling:</strong></p>
     * <p>When interestRate is zero or null, this method returns null to filter the item from processing.
     * This enables Spring Batch skip logic and avoids generating unnecessary zero-amount interest transactions.
     * Maps to COBOL line 214: IF DIS-INT-RATE NOT = 0 conditional processing.</p>
     * 
     * <p><strong>Precision Preservation Example:</strong></p>
     * <pre>
     * Input:
     *   categoryBalance = 1000.00 (BigDecimal scale 2)
     *   interestRate = 18.00 (BigDecimal scale 2, representing 18% APR)
     *   
     * Calculation:
     *   monthlyInterest = 1000.00 * 18.00 / 1200
     *                   = 18000.00 / 1200
     *                   = 15.00 (with scale 2, RoundingMode.HALF_UP)
     *   
     * Output Transaction:
     *   transactionAmount = 15.00 (BigDecimal scale 2)
     *   transactionTypeCode = "01"
     *   transactionCategoryCode = 5
     *   transactionSource = "System"
     *   transactionDescription = "Int. for a/c 12345678901"
     * </pre>
     * 
     * <p><strong>Error Handling:</strong></p>
     * <ul>
     *   <li>IllegalStateException: Account not found for accountId (data integrity issue)</li>
     *   <li>IllegalStateException: AccountXref not found for accountId (missing cross-reference)</li>
     *   <li>IllegalStateException: AccountGroup not found (missing interest rate configuration)</li>
     *   <li>ArithmeticException: Division by zero (should never occur with MONTHLY_DIVISOR = 1200)</li>
     *   <li>NullPointerException: Unexpected null values in required fields</li>
     * </ul>
     * 
     * <p><strong>Audit Trail Logging:</strong></p>
     * <p>Comprehensive SLF4J logging captures:</p>
     * <ul>
     *   <li>DEBUG: Processing start with account ID, type, category, balance</li>
     *   <li>DEBUG: Interest rate lookup result</li>
     *   <li>INFO: Successful interest calculation with all parameters and result</li>
     *   <li>WARN: Zero or null interest rate causing null return (filtered item)</li>
     *   <li>ERROR: Any exception with full context for troubleshooting</li>
     * </ul>
     * 
     * @param item TransactionAggregate input containing account ID, transaction type/category, and category balance
     * @return Transaction entity with computed interest charge, or null if interest rate is zero (filtered)
     * @throws IllegalStateException if required entities (Account, AccountXref, AccountGroup) are not found
     * @throws ArithmeticException if division operation fails (should not occur with valid inputs)
     * @throws Exception for any other unexpected processing errors
     */
    @Override
    public Transaction process(TransactionAggregate item) throws Exception {
        if (item == null) {
            logger.warn("Received null TransactionAggregate item - skipping processing");
            return null;
        }

        // Extract input fields from TransactionAggregate
        Long accountId = item.getAccountId();
        String transactionTypeCode = item.getTransactionTypeCode();
        Integer transactionCategoryCode = item.getTransactionCategoryCode();
        BigDecimal categoryBalance = item.getCategoryBalance();

        if (logger.isDebugEnabled()) {
            logger.debug("Processing interest calculation for account ID: {}, type: {}, category: {}, balance: {}",
                    accountId, transactionTypeCode, transactionCategoryCode, 
                    DecimalUtils.toPlainString(categoryBalance));
        }

        // Validate required fields
        if (accountId == null || transactionTypeCode == null || transactionCategoryCode == null || categoryBalance == null) {
            logger.warn("Missing required fields in TransactionAggregate - accountId: {}, typeCode: {}, categoryCode: {}, balance: {}",
                    accountId, transactionTypeCode, transactionCategoryCode, categoryBalance);
            return null;
        }

        // Step 1: Retrieve Account entity to extract accountGroupId (COBOL lines 202-203, 1100-GET-ACCT-DATA)
        Account account = accountRepository.findByAccountId(String.valueOf(accountId))
                .orElseThrow(() -> new IllegalStateException(
                        "Account not found for accountId: " + accountId + " - Data integrity issue"));

        String accountGroupId = account.getAccountGroupId();
        if (accountGroupId == null || accountGroupId.trim().isEmpty()) {
            logger.warn("Account {} has null or empty accountGroupId - cannot determine interest rate, skipping", accountId);
            return null;
        }

        if (logger.isDebugEnabled()) {
            logger.debug("Retrieved account {} with accountGroupId: {}", accountId, accountGroupId);
        }

        // Step 2: Lookup AccountGroup to retrieve interest rate (COBOL lines 210-213, 1200-GET-INTEREST-RATE)
        AccountGroup.GroupId groupId = new AccountGroup.GroupId(
                accountGroupId,
                transactionTypeCode,
                transactionCategoryCode
        );

        AccountGroup accountGroup = accountGroupRepository.findById(groupId)
                .orElseThrow(() -> new IllegalStateException(
                        String.format("AccountGroup not found for groupId: %s, typeCode: %s, categoryCode: %s",
                                accountGroupId, transactionTypeCode, transactionCategoryCode)));

        BigDecimal interestRate = accountGroup.getInterestRate();

        if (logger.isDebugEnabled()) {
            logger.debug("Retrieved interest rate {} for account group {} type {} category {}",
                    DecimalUtils.toPlainString(interestRate), accountGroupId, transactionTypeCode, transactionCategoryCode);
        }

        // Step 3: Validate interest rate is non-zero (COBOL line 214: IF DIS-INT-RATE NOT = 0)
        if (DecimalUtils.isZero(interestRate)) {
            logger.warn("Interest rate is zero for account {} group {} type {} category {} - skipping interest calculation",
                    accountId, accountGroupId, transactionTypeCode, transactionCategoryCode);
            return null;
        }

        // Step 4: Calculate monthly interest using EXACT formula (COBOL lines 464-465)
        // CRITICAL: COMPUTE WS-MONTHLY-INT = (TRAN-CAT-BAL * DIS-INT-RATE) / 1200
        // Preserve COBOL COMP-3 precision using BigDecimal with explicit scale and RoundingMode.HALF_UP
        BigDecimal monthlyInterest = DecimalUtils.safeMultiply(categoryBalance, interestRate);
        monthlyInterest = DecimalUtils.safeDivide(monthlyInterest, MONTHLY_DIVISOR, DecimalUtils.MONEY_SCALE);

        if (logger.isDebugEnabled()) {
            logger.debug("Calculated monthly interest: {} = ({} * {}) / {}",
                    DecimalUtils.toPlainString(monthlyInterest),
                    DecimalUtils.toPlainString(categoryBalance),
                    DecimalUtils.toPlainString(interestRate),
                    MONTHLY_DIVISOR);
        }

        // Step 5: Lookup AccountXref to retrieve card number (COBOL lines 204-205, 1110-GET-XREF-DATA)
        AccountXref accountXref = accountXrefRepository.findByAccountId(String.valueOf(accountId))
                .orElseThrow(() -> new IllegalStateException(
                        "AccountXref not found for accountId: " + accountId + " - Missing cross-reference"));

        String cardNumber = accountXref.getCardNumber();

        if (logger.isDebugEnabled()) {
            logger.debug("Retrieved card number from XREF for account {}", accountId);
        }

        // Step 6: Generate transaction ID (COBOL lines 474-480)
        // STRING PARM-DATE, WS-TRANID-SUFFIX DELIMITED BY SIZE INTO TRAN-ID
        String transactionId = generateTransactionId();

        if (logger.isDebugEnabled()) {
            logger.debug("Generated transaction ID: {}", transactionId);
        }

        // Step 7: Create Transaction entity with interest charge (COBOL lines 482-498)
        Transaction transaction = new Transaction();
        
        // COBOL line 476-480: Transaction ID generation
        transaction.setTransactionId(transactionId);
        
        // COBOL line 482: MOVE '01' TO TRAN-TYPE-CD
        transaction.setTransactionTypeCode(INTEREST_TRANSACTION_TYPE_CODE);
        
        // COBOL line 483: MOVE '05' TO TRAN-CAT-CD
        transaction.setTransactionCategoryCode(INTEREST_TRANSACTION_CATEGORY_CODE);
        
        // COBOL line 484: MOVE 'System' TO TRAN-SOURCE
        transaction.setTransactionSource(INTEREST_TRANSACTION_SOURCE);
        
        // COBOL lines 485-488: STRING 'Int. for a/c ', ACCT-ID DELIMITED BY SIZE INTO TRAN-DESC
        String transactionDescription = String.format("Int. for a/c %s", accountId);
        transaction.setTransactionDescription(transactionDescription);
        
        // COBOL line 490: MOVE WS-MONTHLY-INT TO TRAN-AMT
        transaction.setTransactionAmount(monthlyInterest);
        
        // COBOL lines 491-494: Merchant fields (zero/spaces for interest transactions)
        transaction.setMerchantId(0L);
        transaction.setMerchantName("");
        transaction.setMerchantCity("");
        transaction.setMerchantZip("");
        
        // COBOL line 495: MOVE XREF-CARD-NUM TO TRAN-CARD-NUM
        transaction.setCardNumber(cardNumber);
        
        // COBOL lines 496-498: PERFORM Z-GET-DB2-FORMAT-TIMESTAMP, MOVE DB2-FORMAT-TS TO TRAN-ORIG-TS/TRAN-PROC-TS
        LocalDateTime currentTimestamp = DateUtils.getCurrentTimestamp();
        transaction.setOriginationTimestamp(currentTimestamp);
        transaction.setProcessingTimestamp(currentTimestamp);

        // Comprehensive audit trail logging for regulatory compliance (Section 0.9)
        logger.info("Interest calculation completed - Account: {}, Group: {}, Type: {}, Category: {}, " +
                "Balance: {}, Rate: {}, MonthlyInterest: {}, TransactionID: {}, CardNumber: {}",
                accountId, accountGroupId, transactionTypeCode, transactionCategoryCode,
                DecimalUtils.toPlainString(categoryBalance),
                DecimalUtils.toPlainString(interestRate),
                DecimalUtils.toPlainString(monthlyInterest),
                transactionId,
                maskCardNumber(cardNumber));

        return transaction;
    }

    /**
     * Generates unique transaction ID using date + atomic counter.
     * 
     * <p>Maps to COBOL lines 474-480:
     * <pre>
     * ADD 1 TO WS-TRANID-SUFFIX
     * STRING PARM-DATE, WS-TRANID-SUFFIX DELIMITED BY SIZE INTO TRAN-ID
     * </pre>
     * </p>
     * 
     * <p>Format: YYYYMMDD + 8-digit zero-padded sequence number = 16 characters total</p>
     * <p>Example: 20240115 + 00000042 = "2024011500000042"</p>
     * 
     * <p>Thread-safe implementation using AtomicLong for concurrent Spring Batch processing.</p>
     * 
     * @return 16-character transaction ID (8-digit date + 8-digit sequence)
     */
    private String generateTransactionId() {
        String dateComponent = LocalDate.now().format(TRANSACTION_ID_DATE_FORMATTER);
        long suffix = transactionIdSuffix.incrementAndGet();
        String suffixComponent = String.format("%08d", suffix);
        return dateComponent + suffixComponent;
    }

    /**
     * Masks card number for secure logging (displays only last 4 digits).
     * 
     * <p>Protects sensitive cardholder data in log files per PCI-DSS compliance requirements.
     * Card numbers are masked as "****1234" showing only the last 4 digits for identification.</p>
     * 
     * @param cardNumber the full 16-digit card number
     * @return masked card number with only last 4 digits visible
     */
    private String maskCardNumber(String cardNumber) {
        if (cardNumber == null || cardNumber.length() < 4) {
            return "****";
        }
        return "****" + cardNumber.substring(cardNumber.length() - 4);
    }

    /**
     * Local Spring Data JPA repository interface for AccountGroup entity.
     * 
     * <p>This interface is defined locally because AccountGroupRepository is not included in the
     * depends_on_files list, but the AccountGroup entity is available. Spring Data JPA automatically
     * provides the implementation at runtime for standard CRUD operations and composite key lookups.</p>
     * 
     * <p>Enables interest rate lookup by composite key (accountGroupId, transactionTypeCode, transactionCategoryCode)
     * matching COBOL DISCGRP-FILE random read operations (lines 210-213, 416-420, 1200-GET-INTEREST-RATE paragraph).</p>
     * 
     * @see AccountGroup
     * @see AccountGroup.GroupId
     */
    public interface AccountGroupRepository extends JpaRepository<AccountGroup, AccountGroup.GroupId> {
        // Spring Data JPA provides automatic implementation for:
        // - findById(AccountGroup.GroupId id) -> Random read by composite key
        // - Standard CRUD operations
        // Maps to COBOL: READ DISCGRP-FILE KEY IS FD-DISCGRP-KEY
    }
}
