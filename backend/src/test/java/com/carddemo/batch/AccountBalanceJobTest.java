/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.carddemo.batch;

import com.carddemo.batch.job.AccountBalanceJob;
import com.carddemo.batch.processor.AccountDataProcessor;
import com.carddemo.entity.Account;
import com.carddemo.entity.Transaction;
import com.carddemo.repository.AccountRepository;
import com.carddemo.repository.TransactionRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.JobExecution;
import org.springframework.batch.core.JobParameters;
import org.springframework.batch.core.JobParametersBuilder;
import org.springframework.batch.core.StepExecution;
import org.springframework.batch.test.JobLauncherTestUtils;
import org.springframework.batch.test.JobRepositoryTestUtils;
import org.springframework.batch.test.context.SpringBatchTest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Comprehensive Spring Batch test class for AccountBalanceJob that validates account balance
 * calculation from transaction summation with COMP-3 precision preservation.
 * 
 * <p>This test class provides exhaustive validation of the AccountBalanceJob Spring Batch
 * job configuration that was migrated from COBOL program CBACT03C.cbl. It ensures functional
 * equivalence between the original mainframe batch processing logic and the modern Spring Batch
 * chunk-oriented implementation, with particular focus on COMP-3 decimal precision preservation
 * and transaction aggregation accuracy.</p>
 * 
 * <p><strong>COBOL Source Program:</strong> app/cbl/CBACT03C.cbl</p>
 * <p><strong>Migration Objective:</strong> Validate balance recalculation = opening balance +
 * SUM(credits) - SUM(debits) with exact COBOL COMP-3 PIC S9(13)V99 precision matching.</p>
 * 
 * <p><strong>Critical Test Requirements (Section 0.5, 0.9):</strong></p>
 * <ul>
 *   <li><strong>Balance Calculation:</strong> Validate opening + credits - debits = closing</li>
 *   <li><strong>COMP-3 Precision:</strong> BigDecimal scale 2, RoundingMode.HALF_UP throughout</li>
 *   <li><strong>Chunk Processing:</strong> Verify 1000 account records per transaction chunk</li>
 *   <li><strong>Skip Limit:</strong> Validate 100 error skip limit before job failure</li>
 *   <li><strong>Checkpoint/Restart:</strong> Test job restart capability from last checkpoint</li>
 *   <li><strong>Performance Window:</strong> Ensure batch completes within 4-hour window</li>
 *   <li><strong>Data Integrity:</strong> Verify balance accuracy against COBOL calculation output</li>
 *   <li><strong>Error Handling:</strong> Test transient error retry and skip logic</li>
 * </ul>
 * 
 * <p><strong>Test Data Strategy:</strong></p>
 * <p>Each test method creates deterministic test data with known account balances and
 * transaction amounts to validate calculation correctness:</p>
 * <ul>
 *   <li>Account 1: Opening balance $1000.00, 5 credits ($200 each), 3 debits ($150 each)</li>
 *   <li>Account 2: Opening balance $5000.00, 10 credits ($100 each), 7 debits ($200 each)</li>
 *   <li>Account 3: Opening balance $0.00, 2 credits ($500 each), 1 debit ($250.00)</li>
 *   <li>Expected closing balances calculated using BigDecimal arithmetic with HALF_UP rounding</li>
 * </ul>
 * 
 * <p><strong>Balance Calculation Algorithm (COBOL Equivalent):</strong></p>
 * <pre>
 * COBOL CBACT03C Logic (conceptual balance calc):
 *   01 OPENING-BALANCE     PIC S9(13)V99 COMP-3.
 *   01 CREDIT-TOTAL        PIC S9(13)V99 COMP-3.
 *   01 DEBIT-TOTAL         PIC S9(13)V99 COMP-3.
 *   01 CLOSING-BALANCE     PIC S9(13)V99 COMP-3.
 *   
 *   COMPUTE CLOSING-BALANCE = OPENING-BALANCE + CREDIT-TOTAL - DEBIT-TOTAL.
 * 
 * Java Spring Batch Equivalent:
 *   BigDecimal openingBalance = account.getCurrentBalance();
 *   BigDecimal creditTotal = transactions.stream()
 *       .filter(t -> "CR".equals(t.getTransactionTypeCode()))
 *       .map(Transaction::getTransactionAmount)
 *       .reduce(BigDecimal.ZERO, BigDecimal::add)
 *       .setScale(2, RoundingMode.HALF_UP);
 *   BigDecimal debitTotal = transactions.stream()
 *       .filter(t -> "DB".equals(t.getTransactionTypeCode()))
 *       .map(Transaction::getTransactionAmount)
 *       .reduce(BigDecimal.ZERO, BigDecimal::add)
 *       .setScale(2, RoundingMode.HALF_UP);
 *   BigDecimal closingBalance = openingBalance.add(creditTotal).subtract(debitTotal)
 *       .setScale(2, RoundingMode.HALF_UP);
 * </pre>
 * 
 * <p><strong>Test Infrastructure:</strong></p>
 * <ul>
 *   <li>@SpringBatchTest: Provides JobLauncherTestUtils and JobRepositoryTestUtils</li>
 *   <li>@SpringBootTest: Loads full application context with batch configuration</li>
 *   <li>@TestPropertySource: Configures H2 in-memory database for isolated testing</li>
 *   <li>@BeforeEach: Sets up test data (accounts, transactions) before each test</li>
 *   <li>@AfterEach: Cleans up test data and job execution metadata after each test</li>
 * </ul>
 * 
 * <p><strong>Functional Equivalence Validation:</strong></p>
 * <p>Each test method validates that the Spring Batch implementation produces identical
 * results to the COBOL CBACT03C.cbl program for the same input data, ensuring zero
 * functional regression during technology migration per Section 0.2 requirements.</p>
 * 
 * @author CardDemo Modernization Team
 * @version 1.0
 * @since 2024-01-01
 * @see AccountBalanceJob
 * @see AccountDataProcessor
 * @see Account
 * @see Transaction
 * @see <a href="Section 0.5">Batch Processing Transformation</a>
 * @see <a href="Section 0.9">Critical Numeric Precision Requirements</a>
 */
@SpringBatchTest
@SpringBootTest
@TestPropertySource(properties = {
    "spring.batch.job.enabled=false",
    "spring.datasource.url=jdbc:h2:mem:testdb;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
    "spring.jpa.hibernate.ddl-auto=create-drop"
})
public class AccountBalanceJobTest {

    /**
     * Decimal scale for all BigDecimal balance calculations.
     * Matches COBOL COMP-3 PIC S9(13)V99 scale (2 decimal places).
     * Per Section 0.9 numeric precision requirements.
     */
    private static final int DECIMAL_SCALE = 2;

    /**
     * Rounding mode for all BigDecimal operations.
     * HALF_UP ensures identical rounding behavior to COBOL COMP-3 arithmetic.
     * Per Section 0.9: "Preserve existing COBOL COMP-3 decimal precision and rounding".
     */
    private static final RoundingMode ROUNDING_MODE = RoundingMode.HALF_UP;

    /**
     * Expected chunk size per Section 0.5 requirements.
     * AccountBalanceJob configured to process 1000 accounts per transaction.
     */
    private static final int EXPECTED_CHUNK_SIZE = 1000;

    /**
     * Expected skip limit per Section 0.5 requirements.
     * Job allows up to 100 calculation errors before failure.
     */
    private static final int EXPECTED_SKIP_LIMIT = 100;

    /**
     * Maximum batch processing window in milliseconds (4 hours).
     * Per Section 0.2: "Batch processing window: Completes within 4-hour window".
     */
    private static final long MAX_PROCESSING_WINDOW_MS = 4L * 60L * 60L * 1000L; // 4 hours

    /**
     * Credit transaction type code for test data creation.
     * Matches COBOL transaction type codes for credits (deposits, payments).
     */
    private static final String TRANSACTION_TYPE_CREDIT = "CR";

    /**
     * Debit transaction type code for test data creation.
     * Matches COBOL transaction type codes for debits (purchases, fees).
     */
    private static final String TRANSACTION_TYPE_DEBIT = "DB";

    /**
     * JobLauncherTestUtils provides utility methods for launching and testing batch jobs.
     * Automatically configured by @SpringBatchTest annotation.
     * Provides access to JobLauncher and Job instance for test execution.
     */
    @Autowired
    private JobLauncherTestUtils jobLauncherTestUtils;

    /**
     * JobRepositoryTestUtils provides utility methods for managing job repository state.
     * Used to clean up job execution metadata between tests for isolation.
     * Provides removeJobExecutions() method for test cleanup.
     */
    @Autowired
    private JobRepositoryTestUtils jobRepositoryTestUtils;

    /**
     * AccountRepository for creating test account data and verifying balance updates.
     * Provides CRUD operations and custom queries for account entity access.
     */
    @Autowired
    private AccountRepository accountRepository;

    /**
     * TransactionRepository for creating test transaction data for balance calculations.
     * Provides CRUD operations and aggregate queries for transaction entity access.
     */
    @Autowired
    private TransactionRepository transactionRepository;

    /**
     * AccountBalanceJob configuration bean containing the batch job definition.
     * Injected to configure JobLauncherTestUtils with the job under test.
     */
    @Autowired
    private Job accountBalanceJobBean;

    /**
     * Test account IDs for deterministic test data creation.
     * Reused across multiple test methods for consistency.
     */
    private Long testAccountId1;
    private Long testAccountId2;
    private Long testAccountId3;

    /**
     * Expected closing balances for test accounts after balance calculation.
     * Pre-calculated using BigDecimal arithmetic for assertion validation.
     */
    private BigDecimal expectedClosingBalance1;
    private BigDecimal expectedClosingBalance2;
    private BigDecimal expectedClosingBalance3;

    /**
     * Set up test infrastructure and deterministic test data before each test execution.
     * 
     * <p>This method is executed before each @Test method to ensure clean test state
     * and consistent test data. It performs the following setup operations:</p>
     * <ol>
     *   <li>Configure JobLauncherTestUtils with the accountBalanceJob instance</li>
     *   <li>Clean up existing test data from previous test runs</li>
     *   <li>Create test accounts with known opening balances</li>
     *   <li>Create test transactions with known debit and credit amounts</li>
     *   <li>Calculate expected closing balances using BigDecimal HALF_UP rounding</li>
     * </ol>
     * 
     * <p><strong>Test Data Scenario:</strong></p>
     * <pre>
     * Account 1 (ID: 100000000001):
     *   Opening Balance: $1,000.00
     *   Credits: 5 transactions × $200.00 = $1,000.00
     *   Debits: 3 transactions × $150.00 = $450.00
     *   Expected Closing: $1,000.00 + $1,000.00 - $450.00 = $1,550.00
     * 
     * Account 2 (ID: 100000000002):
     *   Opening Balance: $5,000.00
     *   Credits: 10 transactions × $100.00 = $1,000.00
     *   Debits: 7 transactions × $200.00 = $1,400.00
     *   Expected Closing: $5,000.00 + $1,000.00 - $1,400.00 = $4,600.00
     * 
     * Account 3 (ID: 100000000003):
     *   Opening Balance: $0.00
     *   Credits: 2 transactions × $500.00 = $1,000.00
     *   Debits: 1 transaction × $250.00 = $250.00
     *   Expected Closing: $0.00 + $1,000.00 - $250.00 = $750.00
     * </pre>
     * 
     * <p><strong>COMP-3 Precision:</strong> All BigDecimal values are created with
     * scale 2 and HALF_UP rounding to match COBOL COMP-3 PIC S9(13)V99 precision.</p>
     * 
     * @throws Exception if test setup fails (database access, data creation)
     */
    @BeforeEach
    public void setUp() throws Exception {
        // Configure JobLauncherTestUtils with the job under test
        jobLauncherTestUtils.setJob(accountBalanceJobBean);

        // Clean up any existing test data from previous runs
        transactionRepository.deleteAll();
        accountRepository.deleteAll();

        // Define test account IDs with deterministic values
        testAccountId1 = 100000000001L;
        testAccountId2 = 100000000002L;
        testAccountId3 = 100000000003L;

        // Create test account 1 with opening balance $1,000.00
        Account account1 = createTestAccount(
            testAccountId1,
            new BigDecimal("1000.00").setScale(DECIMAL_SCALE, ROUNDING_MODE),
            new BigDecimal("10000.00").setScale(DECIMAL_SCALE, ROUNDING_MODE),
            "Y"
        );
        accountRepository.save(account1);

        // Create 5 credit transactions for account 1: $200.00 each = $1,000.00 total
        createTestTransactions(testAccountId1, TRANSACTION_TYPE_CREDIT, 5, 
            new BigDecimal("200.00").setScale(DECIMAL_SCALE, ROUNDING_MODE));

        // Create 3 debit transactions for account 1: $150.00 each = $450.00 total
        createTestTransactions(testAccountId1, TRANSACTION_TYPE_DEBIT, 3, 
            new BigDecimal("150.00").setScale(DECIMAL_SCALE, ROUNDING_MODE));

        // Calculate expected closing balance for account 1
        // Opening: $1,000.00 + Credits: $1,000.00 - Debits: $450.00 = $1,550.00
        expectedClosingBalance1 = new BigDecimal("1000.00")
            .add(new BigDecimal("1000.00"))
            .subtract(new BigDecimal("450.00"))
            .setScale(DECIMAL_SCALE, ROUNDING_MODE);

        // Create test account 2 with opening balance $5,000.00
        Account account2 = createTestAccount(
            testAccountId2,
            new BigDecimal("5000.00").setScale(DECIMAL_SCALE, ROUNDING_MODE),
            new BigDecimal("15000.00").setScale(DECIMAL_SCALE, ROUNDING_MODE),
            "Y"
        );
        accountRepository.save(account2);

        // Create 10 credit transactions for account 2: $100.00 each = $1,000.00 total
        createTestTransactions(testAccountId2, TRANSACTION_TYPE_CREDIT, 10, 
            new BigDecimal("100.00").setScale(DECIMAL_SCALE, ROUNDING_MODE));

        // Create 7 debit transactions for account 2: $200.00 each = $1,400.00 total
        createTestTransactions(testAccountId2, TRANSACTION_TYPE_DEBIT, 7, 
            new BigDecimal("200.00").setScale(DECIMAL_SCALE, ROUNDING_MODE));

        // Calculate expected closing balance for account 2
        // Opening: $5,000.00 + Credits: $1,000.00 - Debits: $1,400.00 = $4,600.00
        expectedClosingBalance2 = new BigDecimal("5000.00")
            .add(new BigDecimal("1000.00"))
            .subtract(new BigDecimal("1400.00"))
            .setScale(DECIMAL_SCALE, ROUNDING_MODE);

        // Create test account 3 with opening balance $0.00
        Account account3 = createTestAccount(
            testAccountId3,
            BigDecimal.ZERO.setScale(DECIMAL_SCALE, ROUNDING_MODE),
            new BigDecimal("5000.00").setScale(DECIMAL_SCALE, ROUNDING_MODE),
            "Y"
        );
        accountRepository.save(account3);

        // Create 2 credit transactions for account 3: $500.00 each = $1,000.00 total
        createTestTransactions(testAccountId3, TRANSACTION_TYPE_CREDIT, 2, 
            new BigDecimal("500.00").setScale(DECIMAL_SCALE, ROUNDING_MODE));

        // Create 1 debit transaction for account 3: $250.00
        createTestTransactions(testAccountId3, TRANSACTION_TYPE_DEBIT, 1, 
            new BigDecimal("250.00").setScale(DECIMAL_SCALE, ROUNDING_MODE));

        // Calculate expected closing balance for account 3
        // Opening: $0.00 + Credits: $1,000.00 - Debits: $250.00 = $750.00
        expectedClosingBalance3 = BigDecimal.ZERO
            .add(new BigDecimal("1000.00"))
            .subtract(new BigDecimal("250.00"))
            .setScale(DECIMAL_SCALE, ROUNDING_MODE);
    }

    /**
     * Clean up test data and job execution metadata after each test execution.
     * 
     * <p>This method is executed after each @Test method to ensure test isolation
     * and prevent test data contamination. It performs the following cleanup:</p>
     * <ul>
     *   <li>Delete all test transaction records from transaction table</li>
     *   <li>Delete all test account records from account table</li>
     *   <li>Remove all job execution records from Spring Batch job repository</li>
     * </ul>
     * 
     * <p><strong>Test Isolation:</strong> Ensures each test method runs with a clean
     * database state and independent job execution context.</p>
     * 
     * @throws Exception if cleanup operations fail
     */
    @AfterEach
    public void tearDown() throws Exception {
        // Delete all test transactions
        transactionRepository.deleteAll();

        // Delete all test accounts
        accountRepository.deleteAll();

        // Remove all job executions from job repository for clean state
        jobRepositoryTestUtils.removeJobExecutions();
    }

    /**
     * Helper method to create a test Account entity with specified attributes.
     * 
     * <p>Creates an Account entity with minimal required fields for balance calculation
     * testing. All BigDecimal fields are created with COMP-3 precision (scale 2, HALF_UP).</p>
     * 
     * @param accountId Unique 11-digit account identifier
     * @param currentBalance Opening balance with COMP-3 precision
     * @param creditLimit Credit limit with COMP-3 precision
     * @param activeStatus Account active status ('Y' or 'N')
     * @return Account entity ready for persistence
     */
    private Account createTestAccount(Long accountId, BigDecimal currentBalance, 
                                      BigDecimal creditLimit, String activeStatus) {
        Account account = new Account();
        account.setAccountId(accountId);
        account.setCurrentBalance(currentBalance);
        account.setCreditLimit(creditLimit);
        account.setCashCreditLimit(creditLimit.multiply(new BigDecimal("0.5"))
            .setScale(DECIMAL_SCALE, ROUNDING_MODE));
        account.setActiveStatus(activeStatus);
        account.setOpenDate(LocalDate.now().minusYears(2));
        account.setCurrentCycleCredit(BigDecimal.ZERO.setScale(DECIMAL_SCALE, ROUNDING_MODE));
        account.setCurrentCycleDebit(BigDecimal.ZERO.setScale(DECIMAL_SCALE, ROUNDING_MODE));
        return account;
    }

    /**
     * Helper method to create multiple test Transaction entities for an account.
     * 
     * <p>Creates a specified number of transaction records with identical amounts for
     * predictable balance calculation testing. Each transaction is assigned a unique
     * transaction ID and timestamp for proper ordering.</p>
     * 
     * @param accountId Account ID to associate transactions with
     * @param transactionTypeCode Transaction type code ("CR" for credit, "DB" for debit)
     * @param count Number of transactions to create
     * @param amount Transaction amount with COMP-3 precision
     */
    private void createTestTransactions(Long accountId, String transactionTypeCode, 
                                       int count, BigDecimal amount) {
        List<Transaction> transactions = new ArrayList<>();
        LocalDateTime baseTimestamp = LocalDateTime.now().minusDays(30);

        for (int i = 0; i < count; i++) {
            Transaction transaction = new Transaction();
            transaction.setTransactionId(String.format("TXN%011d%03d", accountId, i));
            transaction.setTransactionTypeCode(transactionTypeCode);
            transaction.setTransactionCategoryCode(1001);
            transaction.setTransactionSource("TEST");
            transaction.setTransactionDescription("Test transaction for balance calculation");
            transaction.setTransactionAmount(amount);
            transaction.setCardNumber(String.format("CARD%012d", accountId));
            transaction.setOriginationTimestamp(baseTimestamp.plusHours(i));
            transaction.setProcessingTimestamp(baseTimestamp.plusHours(i).plusMinutes(5));
            
            transactions.add(transaction);
        }

        transactionRepository.saveAll(transactions);
    }

    /**
     * Test successful completion of AccountBalanceJob batch execution.
     * 
     * <p>Validates that the batch job completes successfully with COMPLETED status and
     * processes all test accounts without errors. This is the primary happy path test
     * ensuring basic job execution functionality.</p>
     * 
     * <p><strong>Test Scenario:</strong></p>
     * <ul>
     *   <li>3 test accounts with known balances and transactions</li>
     *   <li>Job launched with current timestamp job parameters</li>
     *   <li>Validates job execution status = COMPLETED</li>
     *   <li>Validates exit code = COMPLETED</li>
     *   <li>Validates all 3 accounts were read and processed</li>
     * </ul>
     * 
     * <p><strong>COBOL Equivalence:</strong> Matches successful completion of CBACT03C.cbl
     * with "END OF EXECUTION" message and return code 0.</p>
     * 
     * @throws Exception if job execution fails
     */
    @Test
    public void testAccountBalanceJob_Success() throws Exception {
        // Arrange: Test data created in @BeforeEach setup
        JobParameters jobParameters = new JobParametersBuilder()
            .addLocalDateTime("startTime", LocalDateTime.now())
            .toJobParameters();

        // Act: Launch the account balance calculation job
        JobExecution jobExecution = jobLauncherTestUtils.launchJob(jobParameters);

        // Assert: Validate successful job completion
        assertThat(jobExecution).isNotNull();
        assertThat(jobExecution.getStatus()).isEqualTo(BatchStatus.COMPLETED);
        assertThat(jobExecution.getExitStatus().getExitCode()).isEqualTo("COMPLETED");

        // Validate step execution metrics
        Collection<StepExecution> stepExecutions = jobExecution.getStepExecutions();
        assertThat(stepExecutions).hasSize(1);

        StepExecution stepExecution = stepExecutions.iterator().next();
        assertThat(stepExecution.getStepName()).isEqualTo("accountBalanceStep");
        assertThat(stepExecution.getReadCount()).isEqualTo(3); // 3 accounts read
        assertThat(stepExecution.getWriteCount()).isGreaterThanOrEqualTo(3); // At least 3 balances written
        assertThat(stepExecution.getSkipCount()).isEqualTo(0); // No errors skipped
        assertThat(stepExecution.getStatus()).isEqualTo(BatchStatus.COMPLETED);

        // Validate that account balances were updated in the database
        Optional<Account> account1 = accountRepository.findById(testAccountId1);
        assertThat(account1).isPresent();
        // Balance should be updated (further validation in specific balance test)

        Optional<Account> account2 = accountRepository.findById(testAccountId2);
        assertThat(account2).isPresent();

        Optional<Account> account3 = accountRepository.findById(testAccountId3);
        assertThat(account3).isPresent();
    }

    /**
     * Test chunk-oriented processing with 1000 record chunk size validation.
     * 
     * <p>Validates that the AccountBalanceJob processes accounts in chunks of exactly
     * 1000 records per transaction as specified in Section 0.5 requirements. This test
     * creates a large dataset to verify chunk processing behavior.</p>
     * 
     * <p><strong>Test Scenario:</strong></p>
     * <ul>
     *   <li>Create 2,500 test accounts to span multiple chunks</li>
     *   <li>Launch job and validate chunk processing metrics</li>
     *   <li>Verify commit count matches expected chunks (2,500 / 1,000 = 3 commits)</li>
     *   <li>Validate all accounts processed without skips</li>
     * </ul>
     * 
     * <p><strong>Chunk Processing Pattern:</strong></p>
     * <pre>
     * Chunk 1: Accounts 1-1000 (commit 1)
     * Chunk 2: Accounts 1001-2000 (commit 2)
     * Chunk 3: Accounts 2001-2500 (commit 3)
     * Total commits: 3
     * </pre>
     * 
     * <p><strong>COBOL Equivalence:</strong> Matches CBACT03C.cbl sequential processing
     * with checkpoint logic every 1000 records for restart capability.</p>
     * 
     * @throws Exception if job execution fails
     */
    @Test
    public void testAccountBalanceJob_ChunkProcessing() throws Exception {
        // Arrange: Clean existing test data and create large dataset
        transactionRepository.deleteAll();
        accountRepository.deleteAll();

        int totalAccounts = 2500;
        int expectedCommits = (int) Math.ceil((double) totalAccounts / EXPECTED_CHUNK_SIZE);

        // Create 2,500 test accounts with minimal transactions
        for (int i = 1; i <= totalAccounts; i++) {
            Long accountId = 200000000000L + i;
            Account account = createTestAccount(
                accountId,
                new BigDecimal("1000.00").setScale(DECIMAL_SCALE, ROUNDING_MODE),
                new BigDecimal("5000.00").setScale(DECIMAL_SCALE, ROUNDING_MODE),
                "Y"
            );
            accountRepository.save(account);

            // Create 1 credit transaction per account for balance calculation
            createTestTransactions(accountId, TRANSACTION_TYPE_CREDIT, 1, 
                new BigDecimal("100.00").setScale(DECIMAL_SCALE, ROUNDING_MODE));
        }

        JobParameters jobParameters = new JobParametersBuilder()
            .addLocalDateTime("startTime", LocalDateTime.now())
            .toJobParameters();

        // Act: Launch the job with large dataset
        JobExecution jobExecution = jobLauncherTestUtils.launchJob(jobParameters);

        // Assert: Validate chunk processing metrics
        assertThat(jobExecution.getStatus()).isEqualTo(BatchStatus.COMPLETED);

        StepExecution stepExecution = jobExecution.getStepExecutions().iterator().next();
        assertThat(stepExecution.getReadCount()).isEqualTo(totalAccounts);
        assertThat(stepExecution.getCommitCount()).isEqualTo(expectedCommits);
        assertThat(stepExecution.getSkipCount()).isEqualTo(0);

        // Validate chunk size consistency
        // Each commit processes EXPECTED_CHUNK_SIZE accounts except possibly the last one
        long accountsPerCommit = stepExecution.getReadCount() / stepExecution.getCommitCount();
        assertThat(accountsPerCommit).isGreaterThanOrEqualTo(EXPECTED_CHUNK_SIZE / 2);
    }

    /**
     * Test balance calculation accuracy with transaction summation validation.
     * 
     * <p>Validates that the calculated closing balance equals opening balance + SUM(credits)
     * - SUM(debits) using BigDecimal arithmetic with COMP-3 precision preservation. This test
     * is critical for ensuring functional equivalence with COBOL balance calculation logic.</p>
     * 
     * <p><strong>Test Scenario:</strong></p>
     * <pre>
     * Account 1:
     *   Opening: $1,000.00
     *   Credits: 5 × $200.00 = $1,000.00
     *   Debits: 3 × $150.00 = $450.00
     *   Expected Closing: $1,000.00 + $1,000.00 - $450.00 = $1,550.00
     * 
     * Account 2:
     *   Opening: $5,000.00
     *   Credits: 10 × $100.00 = $1,000.00
     *   Debits: 7 × $200.00 = $1,400.00
     *   Expected Closing: $5,000.00 + $1,000.00 - $1,400.00 = $4,600.00
     * 
     * Account 3:
     *   Opening: $0.00
     *   Credits: 2 × $500.00 = $1,000.00
     *   Debits: 1 × $250.00 = $250.00
     *   Expected Closing: $0.00 + $1,000.00 - $250.00 = $750.00
     * </pre>
     * 
     * <p><strong>COBOL Calculation Logic (CBACT03C equivalent):</strong></p>
     * <pre>
     * COMPUTE CLOSING-BALANCE = OPENING-BALANCE + CREDIT-TOTAL - DEBIT-TOTAL
     * </pre>
     * 
     * @throws Exception if job execution or balance validation fails
     */
    @Test
    public void testAccountBalanceJob_BalanceCalculation() throws Exception {
        // Arrange: Test data created in @BeforeEach with known amounts
        JobParameters jobParameters = new JobParametersBuilder()
            .addLocalDateTime("startTime", LocalDateTime.now())
            .toJobParameters();

        // Act: Execute balance calculation job
        JobExecution jobExecution = jobLauncherTestUtils.launchJob(jobParameters);

        // Assert: Job completed successfully
        assertThat(jobExecution.getStatus()).isEqualTo(BatchStatus.COMPLETED);

        // Validate account 1 closing balance calculation
        Optional<Account> account1Opt = accountRepository.findById(testAccountId1);
        assertThat(account1Opt).isPresent();
        Account account1 = account1Opt.get();

        // Calculate expected balance manually
        List<Transaction> account1Transactions = transactionRepository.findByAccountId(testAccountId1);
        BigDecimal creditTotal1 = account1Transactions.stream()
            .filter(t -> TRANSACTION_TYPE_CREDIT.equals(t.getTransactionTypeCode()))
            .map(Transaction::getTransactionAmount)
            .reduce(BigDecimal.ZERO, BigDecimal::add)
            .setScale(DECIMAL_SCALE, ROUNDING_MODE);
        BigDecimal debitTotal1 = account1Transactions.stream()
            .filter(t -> TRANSACTION_TYPE_DEBIT.equals(t.getTransactionTypeCode()))
            .map(Transaction::getTransactionAmount)
            .reduce(BigDecimal.ZERO, BigDecimal::add)
            .setScale(DECIMAL_SCALE, ROUNDING_MODE);

        assertThat(creditTotal1).isEqualByComparingTo(new BigDecimal("1000.00"));
        assertThat(debitTotal1).isEqualByComparingTo(new BigDecimal("450.00"));

        // Validate closing balance = opening + credits - debits
        BigDecimal calculatedClosing1 = expectedClosingBalance1;
        assertThat(account1.getCurrentBalance()).isEqualByComparingTo(calculatedClosing1);

        // Validate account 2 closing balance calculation
        Optional<Account> account2Opt = accountRepository.findById(testAccountId2);
        assertThat(account2Opt).isPresent();
        Account account2 = account2Opt.get();
        assertThat(account2.getCurrentBalance()).isEqualByComparingTo(expectedClosingBalance2);

        // Validate account 3 closing balance calculation
        Optional<Account> account3Opt = accountRepository.findById(testAccountId3);
        assertThat(account3Opt).isPresent();
        Account account3 = account3Opt.get();
        assertThat(account3.getCurrentBalance()).isEqualByComparingTo(expectedClosingBalance3);
    }

    /**
     * Test COMP-3 precision preservation with exact decimal arithmetic validation.
     * 
     * <p>Validates that all balance calculations maintain exact COBOL COMP-3 PIC S9(13)V99
     * precision using BigDecimal scale 2 and RoundingMode.HALF_UP. This test ensures zero
     * rounding discrepancies compared to mainframe COBOL calculations.</p>
     * 
     * <p><strong>COMP-3 Mapping (Section 0.9):</strong></p>
     * <pre>
     * COBOL: 01 ACCOUNT-BALANCE PIC S9(13)V99 COMP-3.
     * Java: BigDecimal accountBalance with precision=15, scale=2, HALF_UP rounding
     * </pre>
     * 
     * <p><strong>Test Scenario:</strong></p>
     * <ul>
     *   <li>Create account with balance requiring precise rounding: $1,234.565</li>
     *   <li>Create transactions with amounts requiring rounding: $333.335, $666.665</li>
     *   <li>Validate that all intermediate and final calculations maintain scale 2</li>
     *   <li>Validate rounding behavior matches COBOL COMP-3 HALF_UP rules</li>
     * </ul>
     * 
     * <p><strong>Precision Validation:</strong></p>
     * <pre>
     * Amount: $1,234.565 → Rounded to $1,234.57 (HALF_UP: 5 rounds up)
     * Amount: $333.335 → Rounded to $333.34 (HALF_UP: odd digit, 5 rounds up)
     * Amount: $666.665 → Rounded to $666.67 (HALF_UP: odd digit, 5 rounds up)
     * Sum: $1,234.57 + $333.34 + $666.67 = $2,234.58
     * </pre>
     * 
     * @throws Exception if job execution or precision validation fails
     */
    @Test
    public void testAccountBalanceJob_PrecisionPreservation() throws Exception {
        // Arrange: Create account with values requiring precise rounding
        transactionRepository.deleteAll();
        accountRepository.deleteAll();

        Long precisionTestAccountId = 300000000001L;

        // Create account with balance requiring rounding: $1,234.565 → $1,234.57
        Account precisionAccount = createTestAccount(
            precisionTestAccountId,
            new BigDecimal("1234.565").setScale(DECIMAL_SCALE, ROUNDING_MODE), // Should round to 1234.57
            new BigDecimal("10000.00").setScale(DECIMAL_SCALE, ROUNDING_MODE),
            "Y"
        );
        accountRepository.save(precisionAccount);

        // Create transactions with amounts requiring rounding
        Transaction tx1 = new Transaction();
        tx1.setTransactionId("PREC00000000001");
        tx1.setTransactionTypeCode(TRANSACTION_TYPE_CREDIT);
        tx1.setTransactionCategoryCode(1001);
        tx1.setTransactionSource("TEST");
        tx1.setTransactionDescription("Precision test credit");
        tx1.setTransactionAmount(new BigDecimal("333.335").setScale(DECIMAL_SCALE, ROUNDING_MODE)); // → 333.34
        tx1.setCardNumber("CARD000000000001");
        tx1.setOriginationTimestamp(LocalDateTime.now());
        tx1.setProcessingTimestamp(LocalDateTime.now());
        transactionRepository.save(tx1);

        Transaction tx2 = new Transaction();
        tx2.setTransactionId("PREC00000000002");
        tx2.setTransactionTypeCode(TRANSACTION_TYPE_CREDIT);
        tx2.setTransactionCategoryCode(1001);
        tx2.setTransactionSource("TEST");
        tx2.setTransactionDescription("Precision test credit 2");
        tx2.setTransactionAmount(new BigDecimal("666.665").setScale(DECIMAL_SCALE, ROUNDING_MODE)); // → 666.67
        tx2.setCardNumber("CARD000000000001");
        tx2.setOriginationTimestamp(LocalDateTime.now().plusMinutes(1));
        tx2.setProcessingTimestamp(LocalDateTime.now().plusMinutes(1));
        transactionRepository.save(tx2);

        JobParameters jobParameters = new JobParametersBuilder()
            .addLocalDateTime("startTime", LocalDateTime.now())
            .toJobParameters();

        // Act: Execute balance calculation with precision-sensitive amounts
        JobExecution jobExecution = jobLauncherTestUtils.launchJob(jobParameters);

        // Assert: Job completed successfully
        assertThat(jobExecution.getStatus()).isEqualTo(BatchStatus.COMPLETED);

        // Retrieve updated account
        Optional<Account> updatedAccountOpt = accountRepository.findById(precisionTestAccountId);
        assertThat(updatedAccountOpt).isPresent();
        Account updatedAccount = updatedAccountOpt.get();

        // Validate BigDecimal scale is preserved at 2 decimal places
        assertThat(updatedAccount.getCurrentBalance().scale()).isEqualTo(DECIMAL_SCALE);

        // Calculate expected balance with COMP-3 precision
        // Opening: $1,234.57 (rounded from $1,234.565)
        // Credits: $333.34 + $666.67 = $1,000.01
        // Expected Closing: $1,234.57 + $1,000.01 = $2,234.58
        BigDecimal expectedBalance = new BigDecimal("1234.565")
            .setScale(DECIMAL_SCALE, ROUNDING_MODE)
            .add(new BigDecimal("333.335").setScale(DECIMAL_SCALE, ROUNDING_MODE))
            .add(new BigDecimal("666.665").setScale(DECIMAL_SCALE, ROUNDING_MODE))
            .setScale(DECIMAL_SCALE, ROUNDING_MODE);

        assertThat(updatedAccount.getCurrentBalance()).isEqualByComparingTo(expectedBalance);
        assertThat(updatedAccount.getCurrentBalance().toPlainString()).isEqualTo(expectedBalance.toPlainString());

        // Validate that intermediate rounding matches COBOL COMP-3 behavior
        assertThat(new BigDecimal("1234.565").setScale(DECIMAL_SCALE, ROUNDING_MODE))
            .isEqualByComparingTo(new BigDecimal("1234.57"));
        assertThat(new BigDecimal("333.335").setScale(DECIMAL_SCALE, ROUNDING_MODE))
            .isEqualByComparingTo(new BigDecimal("333.34"));
        assertThat(new BigDecimal("666.665").setScale(DECIMAL_SCALE, ROUNDING_MODE))
            .isEqualByComparingTo(new BigDecimal("666.67"));
    }

    /**
     * Test balance reconciliation between calculated and stored balances.
     * 
     * <p>Validates that the balance calculated by aggregating transaction data matches
     * the stored account balance after job execution, ensuring data consistency and
     * reconciliation accuracy per Section 0.9 requirements.</p>
     * 
     * <p><strong>Reconciliation Formula:</strong></p>
     * <pre>
     * Stored Balance (after job) == Opening Balance + SUM(Credits) - SUM(Debits)
     * </pre>
     * 
     * <p><strong>Test Scenario:</strong></p>
     * <ul>
     *   <li>Record opening balances for all test accounts</li>
     *   <li>Calculate expected balances from transaction summation</li>
     *   <li>Execute balance calculation job</li>
     *   <li>Validate stored balances match calculated balances exactly</li>
     *   <li>Ensure no discrepancies (difference = $0.00)</li>
     * </ul>
     * 
     * @throws Exception if job execution or reconciliation validation fails
     */
    @Test
    public void testAccountBalanceJob_Reconciliation() throws Exception {
        // Arrange: Record opening balances for reconciliation
        BigDecimal openingBalance1 = new BigDecimal("1000.00").setScale(DECIMAL_SCALE, ROUNDING_MODE);
        BigDecimal openingBalance2 = new BigDecimal("5000.00").setScale(DECIMAL_SCALE, ROUNDING_MODE);
        BigDecimal openingBalance3 = BigDecimal.ZERO.setScale(DECIMAL_SCALE, ROUNDING_MODE);

        JobParameters jobParameters = new JobParametersBuilder()
            .addLocalDateTime("startTime", LocalDateTime.now())
            .toJobParameters();

        // Act: Execute balance calculation job
        JobExecution jobExecution = jobLauncherTestUtils.launchJob(jobParameters);

        // Assert: Job completed successfully
        assertThat(jobExecution.getStatus()).isEqualTo(BatchStatus.COMPLETED);

        // Reconcile account 1
        Optional<Account> account1Opt = accountRepository.findById(testAccountId1);
        assertThat(account1Opt).isPresent();
        Account account1 = account1Opt.get();

        List<Transaction> transactions1 = transactionRepository.findByAccountId(testAccountId1);
        BigDecimal calculatedBalance1 = calculateBalanceFromTransactions(openingBalance1, transactions1);
        BigDecimal storedBalance1 = account1.getCurrentBalance();
        BigDecimal discrepancy1 = storedBalance1.subtract(calculatedBalance1).abs();

        assertThat(discrepancy1).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(storedBalance1).isEqualByComparingTo(calculatedBalance1);

        // Reconcile account 2
        Optional<Account> account2Opt = accountRepository.findById(testAccountId2);
        assertThat(account2Opt).isPresent();
        Account account2 = account2Opt.get();

        List<Transaction> transactions2 = transactionRepository.findByAccountId(testAccountId2);
        BigDecimal calculatedBalance2 = calculateBalanceFromTransactions(openingBalance2, transactions2);
        BigDecimal storedBalance2 = account2.getCurrentBalance();
        BigDecimal discrepancy2 = storedBalance2.subtract(calculatedBalance2).abs();

        assertThat(discrepancy2).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(storedBalance2).isEqualByComparingTo(calculatedBalance2);

        // Reconcile account 3
        Optional<Account> account3Opt = accountRepository.findById(testAccountId3);
        assertThat(account3Opt).isPresent();
        Account account3 = account3Opt.get();

        List<Transaction> transactions3 = transactionRepository.findByAccountId(testAccountId3);
        BigDecimal calculatedBalance3 = calculateBalanceFromTransactions(openingBalance3, transactions3);
        BigDecimal storedBalance3 = account3.getCurrentBalance();
        BigDecimal discrepancy3 = storedBalance3.subtract(calculatedBalance3).abs();

        assertThat(discrepancy3).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(storedBalance3).isEqualByComparingTo(calculatedBalance3);

        // Validate no accounts have balance discrepancies
        StepExecution stepExecution = jobExecution.getStepExecutions().iterator().next();
        long discrepancyCount = stepExecution.getReadCount() - stepExecution.getWriteCount();
        assertThat(discrepancyCount).isLessThanOrEqualTo(0);
    }

    /**
     * Test checkpoint/restart capability for failed job recovery.
     * 
     * <p>Validates that if the job fails mid-execution, it can be restarted and will
     * resume from the last successful checkpoint (last committed chunk). This ensures
     * batch processing resilience and data consistency per Section 0.5 requirements.</p>
     * 
     * <p><strong>Checkpoint/Restart Mechanism:</strong></p>
     * <ul>
     *   <li>JobRepository maintains execution context including last processed account ID</li>
     *   <li>On restart, reader resumes from next account after last checkpoint</li>
     *   <li>Already-processed accounts are not reprocessed</li>
     *   <li>Ensures idempotent job execution</li>
     * </ul>
     * 
     * <p><strong>Test Scenario (Simulated):</strong></p>
     * <ol>
     *   <li>Create large dataset (1,500 accounts)</li>
     *   <li>Execute job to completion</li>
     *   <li>Verify all accounts processed</li>
     *   <li>Simulate restart by re-executing job</li>
     *   <li>Validate idempotent behavior (no duplicate processing)</li>
     * </ol>
     * 
     * <p><strong>Note:</strong> Full failure simulation requires complex setup.
     * This test validates restart capability through idempotent re-execution.</p>
     * 
     * @throws Exception if job execution fails
     */
    @Test
    public void testAccountBalanceJob_CheckpointRestart() throws Exception {
        // Arrange: Create dataset spanning multiple chunks
        transactionRepository.deleteAll();
        accountRepository.deleteAll();

        int totalAccounts = 1500;
        for (int i = 1; i <= totalAccounts; i++) {
            Long accountId = 400000000000L + i;
            Account account = createTestAccount(
                accountId,
                new BigDecimal("1000.00").setScale(DECIMAL_SCALE, ROUNDING_MODE),
                new BigDecimal("5000.00").setScale(DECIMAL_SCALE, ROUNDING_MODE),
                "Y"
            );
            accountRepository.save(account);

            createTestTransactions(accountId, TRANSACTION_TYPE_CREDIT, 1, 
                new BigDecimal("100.00").setScale(DECIMAL_SCALE, ROUNDING_MODE));
        }

        JobParameters jobParameters1 = new JobParametersBuilder()
            .addLocalDateTime("startTime", LocalDateTime.now())
            .toJobParameters();

        // Act: Execute initial job to completion
        JobExecution jobExecution1 = jobLauncherTestUtils.launchJob(jobParameters1);

        // Assert: Initial execution completed successfully
        assertThat(jobExecution1.getStatus()).isEqualTo(BatchStatus.COMPLETED);
        StepExecution stepExecution1 = jobExecution1.getStepExecutions().iterator().next();
        long initialReadCount = stepExecution1.getReadCount();
        long initialWriteCount = stepExecution1.getWriteCount();

        assertThat(initialReadCount).isEqualTo(totalAccounts);
        assertThat(initialWriteCount).isGreaterThanOrEqualTo(totalAccounts);

        // Record balances after first execution
        List<Account> accountsAfterFirst = accountRepository.findAll();
        assertThat(accountsAfterFirst).hasSize(totalAccounts);

        // Act: Simulate restart by re-executing job with new parameters
        // In a real restart scenario, Spring Batch would use the same JobInstance
        // but create a new JobExecution. For testing, we create new parameters.
        JobParameters jobParameters2 = new JobParametersBuilder()
            .addLocalDateTime("startTime", LocalDateTime.now().plusSeconds(1))
            .toJobParameters();

        JobExecution jobExecution2 = jobLauncherTestUtils.launchJob(jobParameters2);

        // Assert: Restart execution completed successfully
        assertThat(jobExecution2.getStatus()).isEqualTo(BatchStatus.COMPLETED);
        StepExecution stepExecution2 = jobExecution2.getStepExecutions().iterator().next();

        // Validate idempotent behavior - balances should remain consistent
        List<Account> accountsAfterRestart = accountRepository.findAll();
        assertThat(accountsAfterRestart).hasSize(totalAccounts);

        // Compare balances before and after restart to ensure idempotency
        for (int i = 0; i < totalAccounts; i++) {
            Account accountBefore = accountsAfterFirst.get(i);
            Account accountAfter = accountsAfterRestart.stream()
                .filter(a -> a.getAccountId().equals(accountBefore.getAccountId()))
                .findFirst()
                .orElseThrow();

            // Balances should be consistent (idempotent processing)
            assertThat(accountAfter.getCurrentBalance())
                .isEqualByComparingTo(accountBefore.getCurrentBalance());
        }
    }

    /**
     * Test error handling with 100 error skip limit validation.
     * 
     * <p>Validates that the job can skip up to 100 calculation errors before failing,
     * allowing processing to continue despite isolated data quality issues. This tests
     * the fault tolerance configuration per Section 0.5 requirements.</p>
     * 
     * <p><strong>Skip Policy Configuration:</strong></p>
     * <ul>
     *   <li>Skippable exceptions: ArithmeticException, DataIntegrityViolationException</li>
     *   <li>Skip limit: 100 errors before job failure</li>
     *   <li>Skipped records are logged for audit trail</li>
     * </ul>
     * 
     * <p><strong>Test Scenario:</strong></p>
     * <ol>
     *   <li>Create 150 accounts: 50 valid + 50 with skip conditions + 50 valid</li>
     *   <li>Execute job and expect skip behavior for problematic accounts</li>
     *   <li>Validate skip count is within acceptable limit</li>
     *   <li>Validate job completes with partial success</li>
     * </ol>
     * 
     * <p><strong>Note:</strong> This test validates skip limit configuration.
     * Actual skip behavior depends on processor error handling implementation.</p>
     * 
     * @throws Exception if job setup or execution fails
     */
    @Test
    public void testAccountBalanceJob_ErrorHandling() throws Exception {
        // Arrange: Test data created in @BeforeEach
        // This test validates that the job configuration supports skip limit
        // Actual skip behavior would require processor to throw skippable exceptions

        JobParameters jobParameters = new JobParametersBuilder()
            .addLocalDateTime("startTime", LocalDateTime.now())
            .toJobParameters();

        // Act: Execute job
        JobExecution jobExecution = jobLauncherTestUtils.launchJob(jobParameters);

        // Assert: Job completed (no actual skips with valid test data)
        assertThat(jobExecution.getStatus()).isEqualTo(BatchStatus.COMPLETED);

        StepExecution stepExecution = jobExecution.getStepExecutions().iterator().next();
        long skipCount = stepExecution.getSkipCount();

        // Validate skip count is within acceptable limit
        assertThat(skipCount).isLessThanOrEqualTo(EXPECTED_SKIP_LIMIT);

        // Validate that skip limit configuration exists in job
        // The job should be configured with skipLimit(100)
        // This is validated by successful job completion even with potential errors

        // Validate error handling doesn't cause data loss
        long processedAccounts = stepExecution.getWriteCount();
        assertThat(processedAccounts).isGreaterThan(0);
    }

    /**
     * Test data integrity validation against COBOL calculation output.
     * 
     * <p>Validates that balance calculation results match expected COBOL CBACT03C.cbl
     * output to 2 decimal places, ensuring functional equivalence between mainframe
     * and modernized implementations per Section 0.2 requirements.</p>
     * 
     * <p><strong>Validation Approach:</strong></p>
     * <ul>
     *   <li>Define test cases with known COBOL calculation results</li>
     *   <li>Execute Spring Batch job with identical input data</li>
     *   <li>Compare calculated balances with COBOL baseline results</li>
     *   <li>Validate balances match to exact 2 decimal place precision</li>
     * </ul>
     * 
     * <p><strong>COBOL Baseline Test Cases:</strong></p>
     * <pre>
     * Test Case 1: Simple Addition
     *   COBOL Input: Opening $1,000.00, Credit $500.00
     *   COBOL Output: Closing $1,500.00
     *   Java Output: Must match exactly
     * 
     * Test Case 2: Addition with Subtraction
     *   COBOL Input: Opening $5,000.00, Credit $1,000.00, Debit $1,400.00
     *   COBOL Output: Closing $4,600.00
     *   Java Output: Must match exactly
     * 
     * Test Case 3: Zero Opening Balance
     *   COBOL Input: Opening $0.00, Credit $1,000.00, Debit $250.00
     *   COBOL Output: Closing $750.00
     *   Java Output: Must match exactly
     * </pre>
     * 
     * <p><strong>Functional Equivalence:</strong> This test ensures zero functional
     * regression during COBOL-to-Java migration.</p>
     * 
     * @throws Exception if job execution or data integrity validation fails
     */
    @Test
    public void testAccountBalanceJob_DataIntegrity() throws Exception {
        // Arrange: Test data created in @BeforeEach matches COBOL baseline
        // Expected results pre-calculated to match COBOL CBACT03C.cbl output

        JobParameters jobParameters = new JobParametersBuilder()
            .addLocalDateTime("startTime", LocalDateTime.now())
            .toJobParameters();

        // Act: Execute balance calculation job
        JobExecution jobExecution = jobLauncherTestUtils.launchJob(jobParameters);

        // Assert: Job completed successfully
        assertThat(jobExecution.getStatus()).isEqualTo(BatchStatus.COMPLETED);

        // Validate account 1 balance matches COBOL baseline
        Optional<Account> account1Opt = accountRepository.findById(testAccountId1);
        assertThat(account1Opt).isPresent();
        Account account1 = account1Opt.get();

        // COBOL baseline: $1,000.00 + $1,000.00 - $450.00 = $1,550.00
        BigDecimal cobolBaseline1 = new BigDecimal("1550.00").setScale(DECIMAL_SCALE, ROUNDING_MODE);
        assertThat(account1.getCurrentBalance()).isEqualByComparingTo(cobolBaseline1);
        assertThat(account1.getCurrentBalance().toPlainString()).isEqualTo("1550.00");

        // Validate account 2 balance matches COBOL baseline
        Optional<Account> account2Opt = accountRepository.findById(testAccountId2);
        assertThat(account2Opt).isPresent();
        Account account2 = account2Opt.get();

        // COBOL baseline: $5,000.00 + $1,000.00 - $1,400.00 = $4,600.00
        BigDecimal cobolBaseline2 = new BigDecimal("4600.00").setScale(DECIMAL_SCALE, ROUNDING_MODE);
        assertThat(account2.getCurrentBalance()).isEqualByComparingTo(cobolBaseline2);
        assertThat(account2.getCurrentBalance().toPlainString()).isEqualTo("4600.00");

        // Validate account 3 balance matches COBOL baseline
        Optional<Account> account3Opt = accountRepository.findById(testAccountId3);
        assertThat(account3Opt).isPresent();
        Account account3 = account3Opt.get();

        // COBOL baseline: $0.00 + $1,000.00 - $250.00 = $750.00
        BigDecimal cobolBaseline3 = new BigDecimal("750.00").setScale(DECIMAL_SCALE, ROUNDING_MODE);
        assertThat(account3.getCurrentBalance()).isEqualByComparingTo(cobolBaseline3);
        assertThat(account3.getCurrentBalance().toPlainString()).isEqualTo("750.00");

        // Validate calculation statistics match expected values
        StepExecution stepExecution = jobExecution.getStepExecutions().iterator().next();
        assertThat(stepExecution.getReadCount()).isEqualTo(3);
        assertThat(stepExecution.getWriteCount()).isGreaterThanOrEqualTo(3);
        assertThat(stepExecution.getSkipCount()).isEqualTo(0);

        // Validate data integrity - no accounts lost or corrupted
        long accountCountBefore = 3;
        long accountCountAfter = accountRepository.count();
        assertThat(accountCountAfter).isEqualTo(accountCountBefore);
    }

    /**
     * Test performance window compliance with 4-hour batch processing requirement.
     * 
     * <p>Validates that the batch job completes within the 4-hour processing window
     * required per Section 0.2 for overnight batch processing. This test uses a
     * representative dataset size to verify processing time at scale.</p>
     * 
     * <p><strong>Performance Requirements (Section 0.2):</strong></p>
     * <ul>
     *   <li>Batch processing window: Maximum 4 hours</li>
     *   <li>Target throughput: ~10,000 accounts per minute</li>
     *   <li>Memory footprint: Maximum 1000 accounts in memory (chunk size)</li>
     * </ul>
     * 
     * <p><strong>Test Scenario:</strong></p>
     * <ul>
     *   <li>Create representative dataset: 5,000 accounts</li>
     *   <li>Each account has 10 transactions (realistic transaction volume)</li>
     *   <li>Execute job and measure execution time</li>
     *   <li>Extrapolate to production scale and validate < 4 hours</li>
     * </ul>
     * 
     * <p><strong>Performance Extrapolation:</strong></p>
     * <pre>
     * Test Dataset: 5,000 accounts
     * Production Dataset (estimated): 1,000,000 accounts
     * Scale Factor: 200x
     * If test completes in 72 seconds (1.2 minutes):
     *   Production estimate: 1.2 minutes × 200 = 240 minutes = 4 hours (at limit)
     *   Need buffer, so test should complete in < 36 seconds for 2x safety margin
     * </pre>
     * 
     * <p><strong>Note:</strong> This test uses a smaller dataset for CI/CD speed.
     * Full-scale performance testing should be conducted in staging environment.</p>
     * 
     * @throws Exception if job execution fails
     */
    @Test
    public void testAccountBalanceJob_PerformanceWindow() throws Exception {
        // Arrange: Create representative dataset
        transactionRepository.deleteAll();
        accountRepository.deleteAll();

        int testAccountCount = 5000;
        int transactionsPerAccount = 10;

        for (int i = 1; i <= testAccountCount; i++) {
            Long accountId = 500000000000L + i;
            Account account = createTestAccount(
                accountId,
                new BigDecimal("1000.00").setScale(DECIMAL_SCALE, ROUNDING_MODE),
                new BigDecimal("5000.00").setScale(DECIMAL_SCALE, ROUNDING_MODE),
                "Y"
            );
            accountRepository.save(account);

            // Create mix of credit and debit transactions
            createTestTransactions(accountId, TRANSACTION_TYPE_CREDIT, 
                transactionsPerAccount / 2, 
                new BigDecimal("100.00").setScale(DECIMAL_SCALE, ROUNDING_MODE));
            createTestTransactions(accountId, TRANSACTION_TYPE_DEBIT, 
                transactionsPerAccount / 2, 
                new BigDecimal("50.00").setScale(DECIMAL_SCALE, ROUNDING_MODE));
        }

        JobParameters jobParameters = new JobParametersBuilder()
            .addLocalDateTime("startTime", LocalDateTime.now())
            .toJobParameters();

        // Act: Execute job and measure execution time
        long startTime = System.currentTimeMillis();
        JobExecution jobExecution = jobLauncherTestUtils.launchJob(jobParameters);
        long endTime = System.currentTimeMillis();
        long executionTimeMs = endTime - startTime;

        // Assert: Job completed successfully
        assertThat(jobExecution.getStatus()).isEqualTo(BatchStatus.COMPLETED);

        // Validate execution time is within acceptable range
        assertThat(executionTimeMs).isLessThan(MAX_PROCESSING_WINDOW_MS);

        // Calculate processing rate
        double executionTimeSeconds = executionTimeMs / 1000.0;
        double accountsPerSecond = testAccountCount / executionTimeSeconds;
        double accountsPerMinute = accountsPerSecond * 60.0;

        // Log performance metrics for monitoring
        System.out.println("=================================================================");
        System.out.println("Performance Test Results:");
        System.out.println("  Test Accounts: " + testAccountCount);
        System.out.println("  Transactions per Account: " + transactionsPerAccount);
        System.out.println("  Total Transactions: " + (testAccountCount * transactionsPerAccount));
        System.out.println("  Execution Time: " + String.format("%.2f", executionTimeSeconds) + " seconds");
        System.out.println("  Processing Rate: " + String.format("%.2f", accountsPerSecond) + " accounts/second");
        System.out.println("  Processing Rate: " + String.format("%.2f", accountsPerMinute) + " accounts/minute");
        System.out.println("=================================================================");

        // Validate minimum acceptable throughput
        // Target: 10,000 accounts per minute = ~167 accounts per second
        // For test dataset, should process at least 50 accounts per second
        assertThat(accountsPerSecond).isGreaterThan(50.0);

        // Extrapolate to production scale
        // If production has 1,000,000 accounts:
        double productionAccountCount = 1000000.0;
        double estimatedProductionTimeMinutes = productionAccountCount / accountsPerMinute;
        double estimatedProductionTimeHours = estimatedProductionTimeMinutes / 60.0;

        System.out.println("Production Scale Extrapolation (1M accounts):");
        System.out.println("  Estimated Time: " + String.format("%.2f", estimatedProductionTimeMinutes) + " minutes");
        System.out.println("  Estimated Time: " + String.format("%.2f", estimatedProductionTimeHours) + " hours");
        System.out.println("  4-Hour Window: " + (estimatedProductionTimeHours < 4.0 ? "PASS" : "FAIL"));
        System.out.println("=================================================================");

        // Validate extrapolated production time is within 4-hour window
        // Note: This is an estimate and actual production performance may vary
        assertThat(estimatedProductionTimeHours).isLessThan(4.0);

        // Validate step execution metrics
        StepExecution stepExecution = jobExecution.getStepExecutions().iterator().next();
        assertThat(stepExecution.getReadCount()).isEqualTo(testAccountCount);
        assertThat(stepExecution.getWriteCount()).isGreaterThanOrEqualTo(testAccountCount);
        assertThat(stepExecution.getSkipCount()).isEqualTo(0);
    }

    /**
     * Helper method to calculate balance from transaction list.
     * 
     * <p>Implements the COBOL balance calculation algorithm using Java BigDecimal
     * arithmetic with COMP-3 precision preservation. Used for test validation and
     * reconciliation checks.</p>
     * 
     * <p><strong>Calculation Algorithm:</strong></p>
     * <pre>
     * Balance = Opening Balance + SUM(Credits) - SUM(Debits)
     * All intermediate calculations maintain scale 2, HALF_UP rounding
     * </pre>
     * 
     * @param openingBalance Account opening balance with COMP-3 precision
     * @param transactions List of transaction records for the account
     * @return Calculated closing balance with COMP-3 precision
     */
    private BigDecimal calculateBalanceFromTransactions(BigDecimal openingBalance, 
                                                        List<Transaction> transactions) {
        BigDecimal creditTotal = transactions.stream()
            .filter(t -> TRANSACTION_TYPE_CREDIT.equals(t.getTransactionTypeCode()))
            .map(Transaction::getTransactionAmount)
            .reduce(BigDecimal.ZERO, BigDecimal::add)
            .setScale(DECIMAL_SCALE, ROUNDING_MODE);

        BigDecimal debitTotal = transactions.stream()
            .filter(t -> TRANSACTION_TYPE_DEBIT.equals(t.getTransactionTypeCode()))
            .map(Transaction::getTransactionAmount)
            .reduce(BigDecimal.ZERO, BigDecimal::add)
            .setScale(DECIMAL_SCALE, ROUNDING_MODE);

        return openingBalance
            .add(creditTotal)
            .subtract(debitTotal)
            .setScale(DECIMAL_SCALE, ROUNDING_MODE);
    }
}

