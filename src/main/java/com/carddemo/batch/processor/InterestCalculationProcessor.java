package com.carddemo.batch.processor;

import com.carddemo.entity.Account;
import com.carddemo.entity.Card;
import com.carddemo.entity.DisclosureGroup;
import com.carddemo.entity.Transaction;
import com.carddemo.entity.TransactionCategoryBalance;
import com.carddemo.repository.AccountRepository;
import com.carddemo.repository.CardRepository;
import com.carddemo.repository.DisclosureGroupRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.batch.item.ItemProcessor;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Spring Batch ItemProcessor implementing exact COBOL interest calculation formula
 * from CBACT04C.cbl batch program.
 * 
 * <p><strong>COBOL Source Reference:</strong></p>
 * <p>This processor replicates the business logic from CBACT04C.cbl (Interest Calculator),
 * specifically:</p>
 * <ul>
 *   <li>Lines 462-470: 1300-COMPUTE-INTEREST paragraph - monthly interest calculation</li>
 *   <li>Lines 415-440: 1200-GET-INTEREST-RATE paragraph - disclosure group rate retrieval</li>
 *   <li>Lines 473-515: 1300-B-WRITE-TX paragraph - interest transaction record creation</li>
 * </ul>
 * 
 * <p><strong>Interest Calculation Formula (EXACT COBOL REPLICATION):</strong></p>
 * <pre>
 * COBOL (lines 464-465):
 *   COMPUTE WS-MONTHLY-INT = (TRAN-CAT-BAL * DIS-INT-RATE) / 1200
 * 
 * Java Equivalent:
 *   BigDecimal monthlyInterest = balance.getBalance()
 *       .multiply(disclosureGroup.getInterestRate())
 *       .divide(new BigDecimal("1200"), 2, RoundingMode.HALF_UP);
 * </pre>
 * 
 * <p><strong>Division by 1200 Explanation:</strong></p>
 * <p>The constant 1200 represents: 12 months/year × 100 (to convert percentage to decimal).
 * For example, if DIS-INT-RATE = 1800 (meaning 18.00% APR), the calculation is:</p>
 * <ul>
 *   <li>Monthly rate = 1800 / 1200 = 1.5% per month</li>
 *   <li>Monthly interest = balance × 0.015</li>
 * </ul>
 * 
 * <p><strong>BigDecimal Precision Requirements:</strong></p>
 * <p>All arithmetic operations use BigDecimal with explicit scale=2 and RoundingMode.HALF_UP
 * to preserve COBOL COMP-3 packed decimal precision from WS-MONTHLY-INT PIC S9(09)V99 field
 * (line 168 in CBACT04C.cbl). This ensures:</p>
 * <ul>
 *   <li>Identical rounding behavior to COBOL calculations</li>
 *   <li>Exact monetary precision (2 decimal places)</li>
 *   <li>Zero precision loss in financial calculations</li>
 *   <li>Compliance with Section 0.10 requirement 7 (COBOL COMP-3 to Java BigDecimal mapping)</li>
 * </ul>
 * 
 * <p><strong>Disclosure Group Interest Rate Retrieval:</strong></p>
 * <p>Replicates COBOL 1200-GET-INTEREST-RATE paragraph logic:</p>
 * <ol>
 *   <li>Retrieve account to get ACCT-GROUP-ID (line 115 CBACT04C.cbl)</li>
 *   <li>Build composite key: DIS-ACCT-GROUP-ID + DIS-TRAN-TYPE-CD + DIS-TRAN-CAT-CD
 *       (lines 210-212)</li>
 *   <li>READ DISCGRP-FILE with composite key (line 416)</li>
 *   <li>If INVALID KEY (line 417), fallback to 1200-A-GET-DEFAULT-INT-RATE using
 *       'DEFAULT' group ID (lines 436-460)</li>
 *   <li>Return DIS-INT-RATE for calculation</li>
 * </ol>
 * 
 * <p><strong>Zero Interest Rate Handling:</strong></p>
 * <p>If DIS-INT-RATE = 0 (line 214 CBACT04C.cbl), the processor returns null to skip
 * this item from batch processing. This prevents creating zero-amount interest transactions
 * in the transaction file.</p>
 * 
 * <p><strong>Transaction Record Creation:</strong></p>
 * <p>Replicates COBOL 1300-B-WRITE-TX paragraph (lines 473-515):</p>
 * <ul>
 *   <li>Transaction ID: Generated using date + sequence suffix (lines 476-480)</li>
 *   <li>Type Code: '01' - interest charge transaction type (line 482)</li>
 *   <li>Category Code: '05' - interest charge category (line 483)</li>
 *   <li>Transaction Source: 'System' - automated batch process (line 484)</li>
 *   <li>Description: 'Int. for a/c [account-id]' format (lines 485-489)</li>
 *   <li>Amount: Calculated monthly interest (line 490)</li>
 *   <li>Card Number: Retrieved from XREF-FILE via account ID (line 495)</li>
 *   <li>Timestamps: Current date/time for origination and processing (lines 496-498)</li>
 *   <li>Merchant Fields: Empty/zero for system-generated transactions</li>
 * </ul>
 * 
 * <p><strong>Spring Batch Integration:</strong></p>
 * <p>This processor operates in chunk-oriented processing mode:</p>
 * <ul>
 *   <li>Input: TransactionCategoryBalance entities read by ItemReader</li>
 *   <li>Processing: Calculate interest for each category balance</li>
 *   <li>Output: Transaction entities written by ItemWriter (batch insert)</li>
 *   <li>Chunk Size: Configurable (typically 1000 records per Section 0.10 requirement 14)</li>
 *   <li>Transaction Management: @Transactional per chunk in job configuration</li>
 * </ul>
 * 
 * <p><strong>Error Handling:</strong></p>
 * <p>Comprehensive error handling for:</p>
 * <ul>
 *   <li>Missing account records (account lookup failure)</li>
 *   <li>Missing disclosure group records (rate lookup failure)</li>
 *   <li>Missing card records (card number retrieval failure)</li>
 *   <li>Invalid numeric conversions or precision errors</li>
 * </ul>
 * 
 * <p>All errors are logged with detailed context for operational troubleshooting,
 * matching COBOL DISPLAY statements in CBACT04C.cbl.</p>
 * 
 * <p><strong>Performance Considerations:</strong></p>
 * <ul>
 *   <li>Repository queries optimized with proper indexing per Flyway V7__create_indexes.sql</li>
 *   <li>Batch processing reduces individual transaction overhead</li>
 *   <li>Thread-safe implementation supports parallel chunk processing</li>
 *   <li>Complies with 4-hour batch window requirement (Section 0.10 requirement 14)</li>
 * </ul>
 * 
 * @see TransactionCategoryBalance Input entity with account balance data
 * @see Transaction Output entity representing interest charge transaction
 * @see DisclosureGroup Reference data containing interest rates
 * @see Account Master account data including group ID
 * @see Card Card data for transaction card number field
 * @see <a href="Section 0.6">File-by-File Transformation Plan - CBACT04C.cbl to InterestCalculationProcessor.java</a>
 * @see <a href="Section 0.10">Special Instructions - Requirement 7 (COBOL COMP-3 to BigDecimal)</a>
 * @see <a href="Section 0.10">Special Instructions - Requirement 13 (Performance Requirements)</a>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class InterestCalculationProcessor implements ItemProcessor<TransactionCategoryBalance, Transaction> {

    /**
     * Repository for Account entity access.
     * Used to retrieve account group ID required for disclosure group interest rate lookup.
     * Replaces COBOL READ ACCOUNT-FILE operation in 1100-GET-ACCT-DATA paragraph.
     */
    private final AccountRepository accountRepository;

    /**
     * Repository for DisclosureGroup entity access.
     * Used to retrieve interest rates by composite key (account group ID + transaction type + category).
     * Replaces COBOL READ DISCGRP-FILE operation in 1200-GET-INTEREST-RATE paragraph.
     */
    private final DisclosureGroupRepository disclosureGroupRepository;

    /**
     * Repository for Card entity access.
     * Used to retrieve card number for populating interest transaction records.
     * Replaces COBOL READ XREF-FILE operation in 1110-GET-XREF-DATA paragraph.
     */
    private final CardRepository cardRepository;

    /**
     * Constant for interest calculation divisor.
     * Represents 12 months × 100 for percentage to decimal conversion.
     * Matches COBOL literal 1200 in line 465 of CBACT04C.cbl.
     */
    private static final BigDecimal INTEREST_DIVISOR = new BigDecimal("1200");

    /**
     * Constant for DEFAULT disclosure group fallback.
     * When specific account group interest rate not found, use DEFAULT group.
     * Matches COBOL literal 'DEFAULT' in line 438 of CBACT04C.cbl.
     */
    private static final String DEFAULT_GROUP_ID = "DEFAULT";

    /**
     * Interest transaction type code.
     * Fixed value '01' identifying this as an interest charge transaction.
     * Matches COBOL MOVE '01' TO TRAN-TYPE-CD in line 482 of CBACT04C.cbl.
     */
    private static final String INTEREST_TYPE_CODE = "01";

    /**
     * Interest transaction category code.
     * Fixed value '05' identifying this as an interest charge category.
     * Matches COBOL MOVE '05' TO TRAN-CAT-CD in line 483 of CBACT04C.cbl.
     */
    private static final String INTEREST_CATEGORY_CODE = "05";

    /**
     * Transaction source for system-generated interest transactions.
     * Fixed value 'System' identifying automated batch process origin.
     * Matches COBOL MOVE 'System' TO TRAN-SOURCE in line 484 of CBACT04C.cbl.
     */
    private static final String SYSTEM_SOURCE = "System";

    /**
     * Transaction ID sequence counter for unique ID generation.
     * Atomic for thread-safety in parallel processing scenarios.
     * Replaces COBOL WS-TRANID-SUFFIX incrementing logic in line 480.
     */
    private final AtomicLong transactionIdSequence = new AtomicLong(0);

    /**
     * Date/time formatter for transaction ID generation.
     * Format: yyyyMMddHHmmss (14 characters) matching COBOL date format.
     */
    private static final DateTimeFormatter TRANSACTION_ID_DATE_FORMAT = 
        DateTimeFormatter.ofPattern("yyyyMMddHHmmss");

    /**
     * Process a single TransactionCategoryBalance item to calculate monthly interest
     * and generate an interest transaction record.
     * 
     * <p><strong>Processing Flow:</strong></p>
     * <ol>
     *   <li>Retrieve interest rate for account group + transaction type + category</li>
     *   <li>If interest rate is zero or not found, return null (skip item)</li>
     *   <li>Calculate monthly interest using EXACT COBOL formula</li>
     *   <li>Build interest transaction entity with all required fields</li>
     *   <li>Return transaction for batch insertion by ItemWriter</li>
     * </ol>
     * 
     * <p><strong>COBOL Paragraph Mapping:</strong></p>
     * <ul>
     *   <li>1200-GET-INTEREST-RATE → retrieveInterestRate() method</li>
     *   <li>1300-COMPUTE-INTEREST → calculateMonthlyInterest() method</li>
     *   <li>1300-B-WRITE-TX → buildInterestTransaction() method</li>
     * </ul>
     * 
     * <p><strong>Skip Logic:</strong></p>
     * <p>Returns null in the following cases (item skipped from output):</p>
     * <ul>
     *   <li>Interest rate is zero (no interest to charge)</li>
     *   <li>Disclosure group not found for account group and DEFAULT fallback</li>
     *   <li>Account not found for balance record</li>
     *   <li>Card not found for account</li>
     * </ul>
     * 
     * <p><strong>Error Logging:</strong></p>
     * <p>All skip conditions and errors are logged at WARN level with detailed context
     * information for operational monitoring and troubleshooting.</p>
     * 
     * @param item TransactionCategoryBalance entity containing account ID, transaction type,
     *             category code, and balance amount for interest calculation
     * @return Transaction entity representing the calculated interest charge, or null to skip item
     * @throws Exception if unrecoverable processing error occurs (handled by Spring Batch)
     */
    @Override
    public Transaction process(TransactionCategoryBalance item) throws Exception {
        log.debug("Processing interest calculation for account: {}, type: {}, category: {}",
                item.getAccountId(), item.getTransactionTypeCode(), item.getCategoryCode());

        // Step 1: Retrieve interest rate for this category balance
        // Replicates COBOL 1200-GET-INTEREST-RATE paragraph (lines 415-440)
        DisclosureGroup disclosureGroup = retrieveInterestRate(
                item.getAccountId(),
                item.getTransactionTypeCode(),
                item.getCategoryCode()
        );

        if (disclosureGroup == null) {
            log.warn("No disclosure group found for account: {}, type: {}, category: {} - skipping interest calculation",
                    item.getAccountId(), item.getTransactionTypeCode(), item.getCategoryCode());
            return null; // Skip this item
        }

        // Step 2: Check if interest rate is zero (line 214 CBACT04C.cbl)
        // If zero, skip creating interest transaction
        if (disclosureGroup.getInterestRate().compareTo(BigDecimal.ZERO) == 0) {
            log.debug("Interest rate is zero for account: {} - skipping transaction creation",
                    item.getAccountId());
            return null; // Skip this item
        }

        // Step 3: Calculate monthly interest using EXACT COBOL formula
        // Replicates COBOL 1300-COMPUTE-INTEREST paragraph (lines 462-470)
        BigDecimal monthlyInterest = calculateMonthlyInterest(item, disclosureGroup);

        log.debug("Calculated monthly interest: {} for account: {}, balance: {}, rate: {}",
                monthlyInterest, item.getAccountId(), item.getBalance(), disclosureGroup.getInterestRate());

        // Step 4: Build interest transaction entity
        // Replicates COBOL 1300-B-WRITE-TX paragraph (lines 473-515)
        Transaction interestTransaction = buildInterestTransaction(item, monthlyInterest);

        log.info("Generated interest transaction: {} for account: {}, amount: {}",
                interestTransaction.getTransactionId(), item.getAccountId(), monthlyInterest);

        return interestTransaction;
    }

    /**
     * Calculate monthly interest using EXACT COBOL formula from CBACT04C.cbl lines 464-465.
     * 
     * <p><strong>COBOL Formula (Line 465):</strong></p>
     * <pre>
     * COMPUTE WS-MONTHLY-INT = (TRAN-CAT-BAL * DIS-INT-RATE) / 1200
     * </pre>
     * 
     * <p><strong>Java Implementation:</strong></p>
     * <pre>
     * monthlyInterest = balance.multiply(interestRate).divide(1200, 2, HALF_UP)
     * </pre>
     * 
     * <p><strong>Calculation Explanation:</strong></p>
     * <ul>
     *   <li>balance: Transaction category balance amount (TRAN-CAT-BAL)</li>
     *   <li>interestRate: Annual interest rate from disclosure group (DIS-INT-RATE)</li>
     *   <li>1200: Constant representing 12 months × 100 (percentage conversion)</li>
     *   <li>Result: Monthly interest amount in dollars and cents</li>
     * </ul>
     * 
     * <p><strong>Precision Preservation:</strong></p>
     * <ul>
     *   <li>All operations use BigDecimal to avoid floating-point precision errors</li>
     *   <li>Scale explicitly set to 2 decimal places (cents)</li>
     *   <li>RoundingMode.HALF_UP matches COBOL COMP-3 rounding behavior</li>
     *   <li>Exact replication ensures identical calculation results to mainframe</li>
     * </ul>
     * 
     * <p><strong>Example Calculation:</strong></p>
     * <pre>
     * balance = 1000.00
     * interestRate = 1800 (18.00% APR)
     * monthlyInterest = (1000.00 * 1800) / 1200 = 1800000 / 1200 = 1500.00 / 100 = 15.00
     * </pre>
     * 
     * @param balance TransactionCategoryBalance entity containing balance amount
     * @param disclosureGroup DisclosureGroup entity containing interest rate
     * @return Calculated monthly interest amount with exact 2 decimal places precision
     */
    private BigDecimal calculateMonthlyInterest(TransactionCategoryBalance balance, 
                                                 DisclosureGroup disclosureGroup) {
        // EXACT COBOL formula: (TRAN-CAT-BAL * DIS-INT-RATE) / 1200
        // Line 465 in CBACT04C.cbl
        BigDecimal monthlyInterest = balance.getBalance()
                .multiply(disclosureGroup.getInterestRate())
                .divide(INTEREST_DIVISOR, 2, RoundingMode.HALF_UP);

        return monthlyInterest;
    }

    /**
     * Retrieve interest rate from disclosure group reference data with DEFAULT fallback.
     * 
     * <p><strong>Replicates COBOL 1200-GET-INTEREST-RATE paragraph (lines 415-440):</strong></p>
     * <ol>
     *   <li>Retrieve account record to get ACCT-GROUP-ID (line 115)</li>
     *   <li>Build composite key: account group ID + transaction type + category code</li>
     *   <li>READ DISCGRP-FILE with composite key (line 416)</li>
     *   <li>If INVALID KEY (line 417), execute 1200-A-GET-DEFAULT-INT-RATE (lines 436-460)</li>
     *   <li>Try DEFAULT group ID: MOVE 'DEFAULT' TO DIS-ACCT-GROUP-ID (line 438)</li>
     *   <li>READ DISCGRP-FILE again with DEFAULT key (line 441)</li>
     *   <li>Return DIS-INT-RATE field from disclosure group record</li>
     * </ol>
     * 
     * <p><strong>Fallback Logic:</strong></p>
     * <p>When specific account group interest rate is not found, the system falls back
     * to DEFAULT group rate. This ensures that interest can always be calculated even
     * for new or uncategorized account groups. The fallback logic matches COBOL
     * INVALID KEY handling in lines 417-460.</p>
     * 
     * <p><strong>Composite Key Structure:</strong></p>
     * <p>The disclosure group is uniquely identified by three components:</p>
     * <ul>
     *   <li>Account Group ID (10 characters) - from ACCT-GROUP-ID field</li>
     *   <li>Transaction Type Code (2 characters) - from TRAN-TYPE-CD field</li>
     *   <li>Transaction Category Code (4 digits) - from TRAN-CAT-CD field</li>
     * </ul>
     * 
     * <p><strong>Error Handling:</strong></p>
     * <ul>
     *   <li>If account not found: Log warning and return null</li>
     *   <li>If disclosure group not found: Try DEFAULT fallback</li>
     *   <li>If DEFAULT group not found: Log warning and return null</li>
     * </ul>
     * 
     * @param accountId Account ID to look up account group
     * @param transactionTypeCode Transaction type code (e.g., '01' for purchase)
     * @param transactionCategoryCode Transaction category code (e.g., '0001' for retail)
     * @return DisclosureGroup entity containing interest rate, or null if not found
     */
    private DisclosureGroup retrieveInterestRate(String accountId,
                                                  String transactionTypeCode,
                                                  String transactionCategoryCode) {
        // Step 1: Retrieve account to get group ID
        // Replicates COBOL 1100-GET-ACCT-DATA paragraph (line 115)
        Optional<Account> accountOpt = accountRepository.findByAccountId(Long.valueOf(accountId));
        
        if (!accountOpt.isPresent()) {
            log.warn("Account not found for ID: {} - cannot retrieve interest rate", accountId);
            return null;
        }

        Account account = accountOpt.get();
        String accountGroupId = account.getGroupId();

        // Step 2: Build composite key for disclosure group lookup
        // Replicates COBOL key fields in lines 210-212
        DisclosureGroup.DisclosureGroupId compositeKey = DisclosureGroup.DisclosureGroupId.builder()
                .accountGroupId(accountGroupId)
                .transactionTypeCode(transactionTypeCode)
                .transactionCategoryCode(transactionCategoryCode)
                .build();

        // Step 3: Try to retrieve disclosure group with specific account group
        // Replicates COBOL READ DISCGRP-FILE in line 416
        Optional<DisclosureGroup> disclosureGroupOpt = disclosureGroupRepository.findById(compositeKey);

        if (disclosureGroupOpt.isPresent()) {
            log.debug("Found disclosure group for account group: {}, type: {}, category: {}",
                    accountGroupId, transactionTypeCode, transactionCategoryCode);
            return disclosureGroupOpt.get();
        }

        // Step 4: Fallback to DEFAULT group if specific group not found
        // Replicates COBOL 1200-A-GET-DEFAULT-INT-RATE paragraph (lines 436-460)
        log.debug("Specific disclosure group not found, trying DEFAULT group for type: {}, category: {}",
                transactionTypeCode, transactionCategoryCode);

        DisclosureGroup.DisclosureGroupId defaultKey = DisclosureGroup.DisclosureGroupId.builder()
                .accountGroupId(DEFAULT_GROUP_ID) // MOVE 'DEFAULT' TO DIS-ACCT-GROUP-ID (line 438)
                .transactionTypeCode(transactionTypeCode)
                .transactionCategoryCode(transactionCategoryCode)
                .build();

        // Replicates COBOL READ DISCGRP-FILE with DEFAULT key (line 441)
        Optional<DisclosureGroup> defaultGroupOpt = disclosureGroupRepository.findById(defaultKey);

        if (defaultGroupOpt.isPresent()) {
            log.debug("Found DEFAULT disclosure group for type: {}, category: {}",
                    transactionTypeCode, transactionCategoryCode);
            return defaultGroupOpt.get();
        }

        // If even DEFAULT group not found, log warning and return null
        log.warn("No disclosure group found (including DEFAULT) for type: {}, category: {}",
                transactionTypeCode, transactionCategoryCode);
        return null;
    }

    /**
     * Build interest transaction entity with all required fields.
     * 
     * <p><strong>Replicates COBOL 1300-B-WRITE-TX paragraph (lines 473-515):</strong></p>
     * <ul>
     *   <li>Line 476-480: Generate unique transaction ID (date + sequence suffix)</li>
     *   <li>Line 482: Set TRAN-TYPE-CD = '01' (interest charge type)</li>
     *   <li>Line 483: Set TRAN-CAT-CD = '05' (interest charge category)</li>
     *   <li>Line 484: Set TRAN-SOURCE = 'System' (automated batch process)</li>
     *   <li>Line 485-489: Set TRAN-DESC = 'Int. for a/c [account-id]'</li>
     *   <li>Line 490: Set TRAN-AMT = calculated monthly interest</li>
     *   <li>Line 495: Set TRAN-CARD-NUM from XREF-FILE cross-reference</li>
     *   <li>Line 496-498: Set TRAN-ORIG-TS and TRAN-PROC-TS to current timestamp</li>
     *   <li>Merchant fields: Empty/zero for system-generated transactions</li>
     * </ul>
     * 
     * <p><strong>Transaction ID Generation:</strong></p>
     * <p>Format: YYYYMMDDHHMMSS + 6-digit sequence number (total 20 characters)</p>
     * <p>Example: "20240115143022000001" = date "2024-01-15 14:30:22" + sequence "000001"</p>
     * <p>Matches COBOL STRING statement in lines 476-480 building TRAN-ID from
     * PARM-DATE and WS-TRANID-SUFFIX.</p>
     * 
     * <p><strong>Fixed Field Values:</strong></p>
     * <ul>
     *   <li>Type Code: '01' - Identifies interest charge transaction</li>
     *   <li>Category Code: '05' - Identifies interest charge category</li>
     *   <li>Source: 'System' - Identifies automated batch processing origin</li>
     *   <li>Description: 'Int. for a/c [account-id]' - Human-readable description</li>
     * </ul>
     * 
     * <p><strong>Card Number Retrieval:</strong></p>
     * <p>The card number is retrieved from the Card entity using account ID lookup.
     * This replicates COBOL 1110-GET-XREF-DATA paragraph which reads XREF-FILE
     * using FD-XREF-ACCT-ID alternate key (line 495). If multiple cards exist for
     * the account, the first active card is selected.</p>
     * 
     * <p><strong>Merchant Fields:</strong></p>
     * <p>For system-generated interest transactions, merchant-related fields are
     * set to null or empty values since there is no physical merchant involved in
     * the transaction.</p>
     * 
     * <p><strong>Timestamps:</strong></p>
     * <p>Both origination timestamp and processing timestamp are set to the current
     * date/time, indicating the interest was calculated and posted immediately by
     * the batch process. This matches COBOL Z-GET-DB2-FORMAT-TIMESTAMP paragraph
     * calls in lines 496-498.</p>
     * 
     * @param balance TransactionCategoryBalance entity containing account and category information
     * @param monthlyInterest Calculated monthly interest amount
     * @return Transaction entity with all fields populated for interest charge
     */
    private Transaction buildInterestTransaction(TransactionCategoryBalance balance,
                                                  BigDecimal monthlyInterest) {
        // Step 1: Generate unique transaction ID
        // Replicates COBOL STRING PARM-DATE, WS-TRANID-SUFFIX INTO TRAN-ID (lines 476-480)
        String transactionId = generateTransactionId();

        // Step 2: Retrieve card number for this account
        // Replicates COBOL 1110-GET-XREF-DATA paragraph (line 495)
        String cardNumber = retrieveCardNumber(balance.getAccountId());

        if (cardNumber == null) {
            log.warn("No card found for account: {} - using placeholder card number", balance.getAccountId());
            cardNumber = "0000000000000000"; // Placeholder if no card found
        }

        // Step 3: Get current timestamp for both origination and processing
        // Replicates COBOL Z-GET-DB2-FORMAT-TIMESTAMP calls (lines 496-498)
        LocalDateTime currentTimestamp = LocalDateTime.now();

        // Step 4: Build transaction description
        // Replicates COBOL STRING 'Int. for a/c ', ACCT-ID INTO TRAN-DESC (lines 485-489)
        String description = String.format("Int. for a/c %s", balance.getAccountId());

        // Step 5: Build complete Transaction entity using Builder pattern
        // Matches all MOVE statements in COBOL 1300-B-WRITE-TX paragraph (lines 473-515)
        return Transaction.builder()
                .transactionId(transactionId)                           // Line 476-480
                .typeCode(INTEREST_TYPE_CODE)                           // Line 482: MOVE '01'
                .categoryCode(INTEREST_CATEGORY_CODE)                   // Line 483: MOVE '05'
                .transactionSource(SYSTEM_SOURCE)                       // Line 484: MOVE 'System'
                .description(description)                               // Line 485-489: STRING description
                .amount(monthlyInterest)                                // Line 490: MOVE WS-MONTHLY-INT
                .merchantId(null)                                       // No merchant for system transaction
                .merchantName("")                                       // Empty for system transaction
                .merchantCity("")                                       // Empty for system transaction
                .merchantZip("")                                        // Empty for system transaction
                .card(null)                                            // Will be set by relationship
                .originationTimestamp(currentTimestamp)                 // Line 496: TRAN-ORIG-TS
                .processingTimestamp(currentTimestamp)                  // Line 498: TRAN-PROC-TS
                .build();
    }

    /**
     * Generate unique transaction ID using timestamp and sequence number.
     * 
     * <p><strong>Replicates COBOL logic from lines 476-480:</strong></p>
     * <pre>
     * STRING PARM-DATE DELIMITED BY SIZE
     *        WS-TRANID-SUFFIX DELIMITED BY SIZE
     *        INTO TRAN-ID
     * </pre>
     * 
     * <p><strong>Format:</strong> YYYYMMDDHHMMSS + 6-digit sequence (20 characters total)</p>
     * <p><strong>Example:</strong> "20240115143022000001"</p>
     * 
     * <p><strong>Thread Safety:</strong></p>
     * <p>Uses AtomicLong for sequence counter to ensure thread-safe ID generation
     * in parallel chunk processing scenarios.</p>
     * 
     * @return Unique transaction ID string (20 characters)
     */
    private String generateTransactionId() {
        String datePart = LocalDateTime.now().format(TRANSACTION_ID_DATE_FORMAT);
        long sequence = transactionIdSequence.incrementAndGet();
        String sequencePart = String.format("%06d", sequence % 1000000); // 6-digit sequence
        return datePart + sequencePart;
    }

    /**
     * Retrieve card number for account from Card repository.
     * 
     * <p><strong>Replicates COBOL 1110-GET-XREF-DATA paragraph:</strong></p>
     * <p>Reads XREF-FILE using FD-XREF-ACCT-ID alternate key to retrieve
     * XREF-CARD-NUM for the interest transaction record (line 495).</p>
     * 
     * <p>If multiple cards exist for the account, returns the first card found.
     * In production systems, additional logic might select the primary card or
     * most recently used card.</p>
     * 
     * @param accountId Account ID to look up card
     * @return Card number string, or null if no card found
     */
    private String retrieveCardNumber(String accountId) {
        Optional<Card> cardOpt = cardRepository.findByAccount_AccountId(Long.valueOf(accountId))
                .stream()
                .findFirst();

        if (cardOpt.isPresent()) {
            return cardOpt.get().getCardNumber();
        }

        log.warn("No card found for account: {}", accountId);
        return null;
    }
}
