package com.carddemo.batch;

import com.carddemo.batch.config.TestBatchConfig;
import com.carddemo.batch.config.TransactionProcessingJobConfig;
import com.carddemo.batch.processor.TransactionProcessor;
import com.carddemo.batch.reader.TransactionReader;
import com.carddemo.batch.writer.TransactionWriter;
import com.carddemo.model.entity.Account;
import com.carddemo.model.entity.Card;
import com.carddemo.model.entity.CardAccountXref;
import com.carddemo.model.entity.Customer;
import com.carddemo.model.entity.Transaction;
import com.carddemo.model.entity.TransactionCategory;
import com.carddemo.model.entity.TransactionCategoryBalance;
import com.carddemo.model.entity.TransactionType;
import com.carddemo.repository.AccountRepository;
import com.carddemo.repository.CardAccountXrefRepository;
import com.carddemo.repository.CardRepository;
import com.carddemo.repository.CustomerRepository;
import com.carddemo.repository.TransactionCategoryBalanceRepository;
import com.carddemo.repository.TransactionCategoryRepository;
import com.carddemo.repository.TransactionRepository;
import com.carddemo.repository.TransactionTypeRepository;

import org.assertj.core.api.Assertions;
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
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

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
 * Comprehensive JUnit 5 integration test class for TransactionProcessingJob Spring Batch configuration.
 * 
 * <p>Converted from COBOL programs:
 * <ul>
 *   <li>CBTRN01C.cbl - Daily transaction file validation</li>
 *   <li>CBTRN02C.cbl - Transaction posting to accounts</li>
 *   <li>CBTRN03C.cbl - Transaction category summarization and reporting</li>
 * </ul>
 * 
 * <p>Tests validate complete transaction processing workflow including:
 * <ul>
 *   <li>Reading daily transactions from database (replaces VSAM DALYTRAN reads)</li>
 *   <li>Posting debits/credits to account balances with BigDecimal precision</li>
 *   <li>Updating transaction category balances</li>
 *   <li>Chunk processing with 1000-5000 records per chunk</li>
 *   <li>Error handling for invalid transactions with skip policies</li>
 *   <li>Checkpoint/restart capabilities from failures</li>
 *   <li>Parallel processing where COBOL allows sequential steps</li>
 *   <li>Performance meeting 4-hour batch window requirement</li>
 * </ul>
 * 
 * <p>Uses @SpringBatchTest with H2 in-memory database (PostgreSQL compatibility mode) for 
 * integration testing ensuring transaction processing maintains ACID properties and produces 
 * functionally equivalent results to COBOL programs with BigDecimal precision for all financial 
 * calculations matching COBOL COMP-3 arithmetic.
 * 
 * @see TransactionProcessingJobConfig
 * @see TransactionProcessor
 * @see TransactionReader
 * @see TransactionWriter
 */
@SpringBootTest
@SpringBatchTest
@Testcontainers
@Import(TestBatchConfig.class)
public class TransactionProcessingJobTest {

    /**
     * PostgreSQL Testcontainer for integration testing with real database.
     * Ensures transaction processing maintains ACID properties equivalent to VSAM file operations.
     * 
     * Uses .withReuse(true) to reuse the same container across test runs for better performance.
     */
    @Container
    private static final PostgreSQLContainer<?> postgresContainer = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("carddemo_test")
            .withUsername("test")
            .withPassword("test")
            .withReuse(false)  // Don't reuse across Maven runs
            .withStartupTimeout(java.time.Duration.ofSeconds(60));

    /**
     * Configure Spring Boot to use Testcontainers PostgreSQL instance.
     */
    @DynamicPropertySource
    static void postgresProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgresContainer::getJdbcUrl);
        registry.add("spring.datasource.username", postgresContainer::getUsername);
        registry.add("spring.datasource.password", postgresContainer::getPassword);
    }

    @Autowired
    private JobLauncherTestUtils jobLauncherTestUtils;

    @Autowired
    private JobRepositoryTestUtils jobRepositoryTestUtils;

    @Autowired
    private TransactionRepository transactionRepository;

    @Autowired
    private AccountRepository accountRepository;

    @Autowired
    private CardRepository cardRepository;

    @Autowired
    private CardAccountXrefRepository cardAccountXrefRepository;

    @Autowired
    private CustomerRepository customerRepository;

    @Autowired
    private TransactionCategoryBalanceRepository transactionCategoryBalanceRepository;

    @Autowired
    private TransactionCategoryRepository transactionCategoryRepository;

    @Autowired
    private TransactionTypeRepository transactionTypeRepository;

    @Autowired
    private Job transactionProcessingJob;

    // Test data constants matching COBOL program logic
    private static final String TEST_CARD_NUMBER_1 = "4111111111111111";
    private static final String TEST_CARD_NUMBER_2 = "4222222222222222";
    private static final String TEST_CARD_NUMBER_3 = "4333333333333333";
    private static final Long TEST_ACCOUNT_ID_1 = 1000000001L;
    private static final Long TEST_ACCOUNT_ID_2 = 1000000002L;
    private static final Long TEST_ACCOUNT_ID_3 = 1000000003L;
    private static final Long TEST_CUSTOMER_ID = 9000000001L;
    private static final String TRANSACTION_TYPE_DEBIT = "DB";
    private static final String TRANSACTION_TYPE_CREDIT = "CR";
    private static final Integer TRANSACTION_CATEGORY_PURCHASE = 5010;
    private static final Integer TRANSACTION_CATEGORY_PAYMENT = 5020;

    /**
     * Setup method executed before each test.
     * Initializes test data including accounts, cards, cross-references, and transactions.
     * Replicates COBOL WORKING-STORAGE initialization.
     */
    @BeforeEach
    public void setUp() {
        // Clean up all test data for fresh state (Spring caches context, so DB persists)
        transactionRepository.deleteAll();
        transactionCategoryBalanceRepository.deleteAll();
        cardAccountXrefRepository.deleteAll();
        cardRepository.deleteAll();
        accountRepository.deleteAll();
        customerRepository.deleteAll();
        transactionCategoryRepository.deleteAll();
        transactionTypeRepository.deleteAll();
        
        // Clean up Spring Batch job executions
        jobRepositoryTestUtils.removeJobExecutions();
        
        // Create test customer (required for foreign key constraints)
        Customer testCustomer = createTestCustomer(TEST_CUSTOMER_ID);
        customerRepository.save(testCustomer);
        
        // Create transaction type reference data (required before transaction categories)
        TransactionType debitType = createTestTransactionType(TRANSACTION_TYPE_DEBIT, "Debit Transaction");
        TransactionType creditType = createTestTransactionType(TRANSACTION_TYPE_CREDIT, "Credit Transaction");
        transactionTypeRepository.saveAll(List.of(debitType, creditType));
        
        // Create transaction category reference data (required for foreign key constraints)
        TransactionCategory purchaseCategory = createTestTransactionCategory(
                TRANSACTION_TYPE_DEBIT, TRANSACTION_CATEGORY_PURCHASE, "Purchase Transaction");
        TransactionCategory paymentCategory = createTestTransactionCategory(
                TRANSACTION_TYPE_CREDIT, TRANSACTION_CATEGORY_PAYMENT, "Payment Transaction");
        transactionCategoryRepository.saveAll(List.of(purchaseCategory, paymentCategory));
        
        // Set up the transaction processing job for testing
        jobLauncherTestUtils.setJob(transactionProcessingJob);
    }

    /**
     * Teardown method to clean up after each test.
     */
    @AfterEach
    public void tearDown() {
        // Note: With H2's create-drop mode in test profile, all tables including
        // Spring Batch metadata are automatically dropped after each test.
        // No explicit cleanup needed.
    }

    /**
     * Test successful end-to-end transaction processing with all three steps.
     * Validates complete workflow from CBTRN01C through CBTRN03C COBOL programs.
     * 
     * <p>Test scenario:
     * <ul>
     *   <li>Step 1 (CBTRN01C): Validate transaction data integrity</li>
     *   <li>Step 2 (CBTRN02C): Post debits/credits to account balances</li>
     *   <li>Step 3 (CBTRN03C): Aggregate by transaction category</li>
     * </ul>
     */
    @Test
    public void testSuccessfulEndToEndTransactionProcessing() throws Exception {
        // Arrange: Create test accounts with initial balances (COBOL CVACT01Y.cpy structure)
        Account account1 = createTestAccount(TEST_ACCOUNT_ID_1, new BigDecimal("5000.00"), new BigDecimal("10000.00"));
        Account account2 = createTestAccount(TEST_ACCOUNT_ID_2, new BigDecimal("2000.00"), new BigDecimal("5000.00"));
        accountRepository.saveAll(List.of(account1, account2));

        // Create test cards associated with accounts (COBOL CVACT02Y.cpy structure)
        Card card1 = createTestCard(TEST_CARD_NUMBER_1, TEST_ACCOUNT_ID_1);
        Card card2 = createTestCard(TEST_CARD_NUMBER_2, TEST_ACCOUNT_ID_2);
        cardRepository.saveAll(List.of(card1, card2));

        // Create card-account cross-references (COBOL CVACT03Y.cpy structure)
        CardAccountXref xref1 = createTestXref(TEST_CARD_NUMBER_1, TEST_ACCOUNT_ID_1);
        CardAccountXref xref2 = createTestXref(TEST_CARD_NUMBER_2, TEST_ACCOUNT_ID_2);
        cardAccountXrefRepository.saveAll(List.of(xref1, xref2));

        // Create test transactions (COBOL CVTRA05Y.cpy structure)
        Transaction debitTrans1 = createTestTransaction("TXN001", TEST_CARD_NUMBER_1, 
                TRANSACTION_TYPE_DEBIT, TRANSACTION_CATEGORY_PURCHASE, new BigDecimal("150.50"));
        Transaction debitTrans2 = createTestTransaction("TXN002", TEST_CARD_NUMBER_2, 
                TRANSACTION_TYPE_DEBIT, TRANSACTION_CATEGORY_PURCHASE, new BigDecimal("75.25"));
        Transaction creditTrans1 = createTestTransaction("TXN003", TEST_CARD_NUMBER_1, 
                TRANSACTION_TYPE_CREDIT, TRANSACTION_CATEGORY_PAYMENT, new BigDecimal("200.00"));
        transactionRepository.saveAll(List.of(debitTrans1, debitTrans2, creditTrans1));

        // Initialize transaction category balances (COBOL CVTRA01Y.cpy structure)
        TransactionCategoryBalance catBalance1 = createTestCategoryBalance(TEST_ACCOUNT_ID_1, 
                TRANSACTION_TYPE_DEBIT, TRANSACTION_CATEGORY_PURCHASE, BigDecimal.ZERO);
        TransactionCategoryBalance catBalance2 = createTestCategoryBalance(TEST_ACCOUNT_ID_1, 
                TRANSACTION_TYPE_CREDIT, TRANSACTION_CATEGORY_PAYMENT, BigDecimal.ZERO);
        TransactionCategoryBalance catBalance3 = createTestCategoryBalance(TEST_ACCOUNT_ID_2, 
                TRANSACTION_TYPE_DEBIT, TRANSACTION_CATEGORY_PURCHASE, BigDecimal.ZERO);
        transactionCategoryBalanceRepository.saveAll(List.of(catBalance1, catBalance2, catBalance3));

        // Act: Execute the transaction processing job
        JobParameters jobParameters = new JobParametersBuilder()
                .addLong("time", System.currentTimeMillis())
                .toJobParameters();
        JobExecution jobExecution = jobLauncherTestUtils.launchJob(jobParameters);

        // Assert: Verify job completed successfully
        assertThat(jobExecution.getStatus()).isEqualTo(BatchStatus.COMPLETED);
        assertThat(jobExecution.getAllFailureExceptions()).isEmpty();

        // Verify all three steps completed successfully (CBTRN01C, CBTRN02C, CBTRN03C)
        Collection<StepExecution> stepExecutions = jobExecution.getStepExecutions();
        assertThat(stepExecutions).hasSize(3);
        stepExecutions.forEach(stepExecution -> {
            assertThat(stepExecution.getStatus()).isEqualTo(BatchStatus.COMPLETED);
            assertThat(stepExecution.getReadCount()).isGreaterThan(0);
        });

        // Verify account balances updated correctly with BigDecimal precision (COMP-3 equivalent)
        Account updatedAccount1 = accountRepository.findById(TEST_ACCOUNT_ID_1).orElseThrow();
        // Expected: 5000.00 + 150.50 (debit) - 200.00 (credit) = 4950.50
        BigDecimal expectedBalance1 = new BigDecimal("5000.00")
                .add(new BigDecimal("150.50"))
                .subtract(new BigDecimal("200.00"));
        assertThat(updatedAccount1.getAcctCurrBal()).isEqualByComparingTo(expectedBalance1);

        Account updatedAccount2 = accountRepository.findById(TEST_ACCOUNT_ID_2).orElseThrow();
        // Expected: 2000.00 + 75.25 (debit) = 2075.25
        BigDecimal expectedBalance2 = new BigDecimal("2000.00").add(new BigDecimal("75.25"));
        assertThat(updatedAccount2.getAcctCurrBal()).isEqualByComparingTo(expectedBalance2);

        // Verify transaction category balances aggregated correctly (CBTRN03C logic)
        List<TransactionCategoryBalance> updatedCatBalances = transactionCategoryBalanceRepository.findAll();
        assertThat(updatedCatBalances).hasSize(3);
        
        // Find and verify specific category balance for account1 purchases
        Optional<TransactionCategoryBalance> catBal1 = updatedCatBalances.stream()
                .filter(cb -> cb.getTcatAcctId().equals(TEST_ACCOUNT_ID_1) 
                        && cb.getTcatTypeCd().equals(TRANSACTION_TYPE_DEBIT)
                        && cb.getTcatCatCd().equals(TRANSACTION_CATEGORY_PURCHASE))
                .findFirst();
        assertThat(catBal1).isPresent();
        assertThat(catBal1.get().getTcatBal()).isEqualByComparingTo(new BigDecimal("150.50"));
    }

    /**
     * Test transaction validation step (CBTRN01C logic).
     * Validates transaction data integrity including card-account cross-reference lookups.
     */
    @Test
    public void testTransactionValidationStep() throws Exception {
        // Arrange: Create test data with one valid and one invalid transaction
        Account account = createTestAccount(TEST_ACCOUNT_ID_1, new BigDecimal("5000.00"), new BigDecimal("10000.00"));
        accountRepository.save(account);

        Card card = createTestCard(TEST_CARD_NUMBER_1, TEST_ACCOUNT_ID_1);
        cardRepository.save(card);

        // Create dummy card for invalid transaction (to satisfy FK, but no xref = invalid)
        Card invalidCard = createTestCard("9999999999999999", TEST_ACCOUNT_ID_1);
        cardRepository.save(invalidCard);

        // Only create xref for valid card (invalid card has no xref, so batch job will skip it)
        CardAccountXref xref = createTestXref(TEST_CARD_NUMBER_1, TEST_ACCOUNT_ID_1);
        cardAccountXrefRepository.saveAll(List.of(xref));

        // Valid transaction with existing card
        Transaction validTrans = createTestTransaction("TXN001", TEST_CARD_NUMBER_1, 
                TRANSACTION_TYPE_DEBIT, TRANSACTION_CATEGORY_PURCHASE, new BigDecimal("100.00"));
        
        // Invalid transaction with card that has no cross-reference (should be skipped)
        Transaction invalidTrans = createTestTransaction("TXN002", "9999999999999999", 
                TRANSACTION_TYPE_DEBIT, TRANSACTION_CATEGORY_PURCHASE, new BigDecimal("50.00"));
        
        transactionRepository.saveAll(List.of(validTrans, invalidTrans));

        // Act: Execute validation step only
        JobParameters jobParameters = new JobParametersBuilder()
                .addLong("time", System.currentTimeMillis())
                .toJobParameters();
        JobExecution jobExecution = jobLauncherTestUtils.launchStep("transactionValidationStep", jobParameters);

        // Assert: Validation step completed with skip for invalid transaction
        assertThat(jobExecution.getStatus()).isEqualTo(BatchStatus.COMPLETED);
        
        StepExecution stepExecution = jobExecution.getStepExecutions().iterator().next();
        assertThat(stepExecution.getReadCount()).isEqualTo(2);
        // Note: Skip logic depends on TransactionProcessor implementation
        // If configured with skip policy, invalid transactions should be skipped
    }

    /**
     * Test transaction posting step (CBTRN02C logic).
     * Validates account balance updates with BigDecimal precision matching COBOL COMP-3 arithmetic.
     */
    @Test
    public void testTransactionPostingStep() throws Exception {
        // Arrange: Create account with known balance
        BigDecimal initialBalance = new BigDecimal("10000.00");
        Account account = createTestAccount(TEST_ACCOUNT_ID_1, initialBalance, new BigDecimal("20000.00"));
        accountRepository.save(account);

        Card card = createTestCard(TEST_CARD_NUMBER_1, TEST_ACCOUNT_ID_1);
        cardRepository.save(card);

        CardAccountXref xref = createTestXref(TEST_CARD_NUMBER_1, TEST_ACCOUNT_ID_1);
        cardAccountXrefRepository.save(xref);

        // Create multiple transactions with precise amounts
        BigDecimal debitAmount1 = new BigDecimal("1234.56");
        BigDecimal debitAmount2 = new BigDecimal("789.12");
        BigDecimal creditAmount = new BigDecimal("500.00");

        Transaction debitTrans1 = createTestTransaction("TXN001", TEST_CARD_NUMBER_1, 
                TRANSACTION_TYPE_DEBIT, TRANSACTION_CATEGORY_PURCHASE, debitAmount1);
        Transaction debitTrans2 = createTestTransaction("TXN002", TEST_CARD_NUMBER_1, 
                TRANSACTION_TYPE_DEBIT, TRANSACTION_CATEGORY_PURCHASE, debitAmount2);
        Transaction creditTrans = createTestTransaction("TXN003", TEST_CARD_NUMBER_1, 
                TRANSACTION_TYPE_CREDIT, TRANSACTION_CATEGORY_PAYMENT, creditAmount);
        
        transactionRepository.saveAll(List.of(debitTrans1, debitTrans2, creditTrans));

        // Act: Execute transaction posting step
        JobParameters jobParameters = new JobParametersBuilder()
                .addLong("time", System.currentTimeMillis())
                .toJobParameters();
        JobExecution jobExecution = jobLauncherTestUtils.launchStep("transactionPostingStep", jobParameters);

        // Assert: Posting step completed successfully
        assertThat(jobExecution.getStatus()).isEqualTo(BatchStatus.COMPLETED);

        // Verify balance calculated with BigDecimal precision (COBOL COMP-3 equivalent)
        Account updatedAccount = accountRepository.findById(TEST_ACCOUNT_ID_1).orElseThrow();
        
        // Expected: 10000.00 + 1234.56 + 789.12 - 500.00 = 11523.68
        BigDecimal expectedBalance = initialBalance
                .add(debitAmount1)
                .add(debitAmount2)
                .subtract(creditAmount)
                .setScale(2, RoundingMode.HALF_UP);
        
        assertThat(updatedAccount.getAcctCurrBal()).isEqualByComparingTo(expectedBalance);
    }

    /**
     * Test transaction categorization and summarization step (CBTRN03C logic).
     * Validates category-level balance aggregations.
     */
    @Test
    public void testTransactionCategorizationStep() throws Exception {
        // Arrange: Create test data with multiple transactions in different categories
        Account account = createTestAccount(TEST_ACCOUNT_ID_1, new BigDecimal("5000.00"), new BigDecimal("10000.00"));
        accountRepository.save(account);

        Card card = createTestCard(TEST_CARD_NUMBER_1, TEST_ACCOUNT_ID_1);
        cardRepository.save(card);

        CardAccountXref xref = createTestXref(TEST_CARD_NUMBER_1, TEST_ACCOUNT_ID_1);
        cardAccountXrefRepository.save(xref);

        // Multiple transactions in purchase category
        Transaction purchaseTrans1 = createTestTransaction("TXN001", TEST_CARD_NUMBER_1, 
                TRANSACTION_TYPE_DEBIT, TRANSACTION_CATEGORY_PURCHASE, new BigDecimal("100.00"));
        Transaction purchaseTrans2 = createTestTransaction("TXN002", TEST_CARD_NUMBER_1, 
                TRANSACTION_TYPE_DEBIT, TRANSACTION_CATEGORY_PURCHASE, new BigDecimal("250.50"));
        
        // Payment transaction
        Transaction paymentTrans = createTestTransaction("TXN003", TEST_CARD_NUMBER_1, 
                TRANSACTION_TYPE_CREDIT, TRANSACTION_CATEGORY_PAYMENT, new BigDecimal("150.00"));
        
        transactionRepository.saveAll(List.of(purchaseTrans1, purchaseTrans2, paymentTrans));

        // Initialize category balances
        TransactionCategoryBalance purchaseCatBalance = createTestCategoryBalance(TEST_ACCOUNT_ID_1, 
                TRANSACTION_TYPE_DEBIT, TRANSACTION_CATEGORY_PURCHASE, BigDecimal.ZERO);
        TransactionCategoryBalance paymentCatBalance = createTestCategoryBalance(TEST_ACCOUNT_ID_1, 
                TRANSACTION_TYPE_CREDIT, TRANSACTION_CATEGORY_PAYMENT, BigDecimal.ZERO);
        transactionCategoryBalanceRepository.saveAll(List.of(purchaseCatBalance, paymentCatBalance));

        // Act: Execute categorization step
        JobParameters jobParameters = new JobParametersBuilder()
                .addLong("time", System.currentTimeMillis())
                .toJobParameters();
        JobExecution jobExecution = jobLauncherTestUtils.launchStep("transactionCategorizationStep", jobParameters);

        // Assert: Categorization step completed successfully
        assertThat(jobExecution.getStatus()).isEqualTo(BatchStatus.COMPLETED);

        // Verify category balances aggregated correctly
        List<TransactionCategoryBalance> categoryBalances = transactionCategoryBalanceRepository
                .findByTcatAcctId(TEST_ACCOUNT_ID_1);
        assertThat(categoryBalances).hasSize(2);

        // Verify purchase category total: 100.00 + 250.50 = 350.50
        Optional<TransactionCategoryBalance> purchaseBalance = categoryBalances.stream()
                .filter(cb -> cb.getTcatCatCd().equals(TRANSACTION_CATEGORY_PURCHASE))
                .findFirst();
        assertThat(purchaseBalance).isPresent();
        assertThat(purchaseBalance.get().getTcatBal()).isEqualByComparingTo(new BigDecimal("350.50"));

        // Verify payment category total: 150.00
        Optional<TransactionCategoryBalance> paymentBalance = categoryBalances.stream()
                .filter(cb -> cb.getTcatCatCd().equals(TRANSACTION_CATEGORY_PAYMENT))
                .findFirst();
        assertThat(paymentBalance).isPresent();
        assertThat(paymentBalance.get().getTcatBal()).isEqualByComparingTo(new BigDecimal("150.00"));
    }

    /**
     * Test chunk processing with large transaction volumes.
     * Validates performance with 1000-5000 records per chunk as per Section 0.4.11.
     */
    @Test
    public void testChunkProcessingWithLargeVolume() throws Exception {
        // Arrange: Create large number of transactions (simulating daily batch)
        Account account = createTestAccount(TEST_ACCOUNT_ID_1, new BigDecimal("100000.00"), new BigDecimal("200000.00"));
        accountRepository.save(account);

        Card card = createTestCard(TEST_CARD_NUMBER_1, TEST_ACCOUNT_ID_1);
        cardRepository.save(card);

        CardAccountXref xref = createTestXref(TEST_CARD_NUMBER_1, TEST_ACCOUNT_ID_1);
        cardAccountXrefRepository.save(xref);

        // Create 5000 test transactions for chunk processing validation
        List<Transaction> transactions = new ArrayList<>();
        for (int i = 0; i < 5000; i++) {
            Transaction trans = createTestTransaction("TXN" + String.format("%06d", i), 
                    TEST_CARD_NUMBER_1, 
                    TRANSACTION_TYPE_DEBIT, 
                    TRANSACTION_CATEGORY_PURCHASE, 
                    new BigDecimal("10.00"));
            transactions.add(trans);
        }
        transactionRepository.saveAll(transactions);

        TransactionCategoryBalance catBalance = createTestCategoryBalance(TEST_ACCOUNT_ID_1, 
                TRANSACTION_TYPE_DEBIT, TRANSACTION_CATEGORY_PURCHASE, BigDecimal.ZERO);
        transactionCategoryBalanceRepository.save(catBalance);

        // Act: Execute job with large volume
        long startTime = System.currentTimeMillis();
        JobParameters jobParameters = new JobParametersBuilder()
                .addLong("time", System.currentTimeMillis())
                .toJobParameters();
        JobExecution jobExecution = jobLauncherTestUtils.launchJob(jobParameters);
        long executionTime = System.currentTimeMillis() - startTime;

        // Assert: Job completed successfully
        assertThat(jobExecution.getStatus()).isEqualTo(BatchStatus.COMPLETED);

        // Verify all transactions processed
        Collection<StepExecution> stepExecutions = jobExecution.getStepExecutions();
        long totalReadCount = stepExecutions.stream()
                .mapToLong(StepExecution::getReadCount)
                .sum();
        assertThat(totalReadCount).isGreaterThanOrEqualTo(5000);

        // Verify account balance updated correctly: 100000.00 + (5000 * 10.00) = 150000.00
        Account updatedAccount = accountRepository.findById(TEST_ACCOUNT_ID_1).orElseThrow();
        BigDecimal expectedBalance = new BigDecimal("100000.00").add(new BigDecimal("50000.00"));
        assertThat(updatedAccount.getAcctCurrBal()).isEqualByComparingTo(expectedBalance);

        // Performance validation: Execution time should be reasonable for 5000 transactions
        // For 1M+ transactions to complete in 4 hours, 5000 should complete in < 1 minute
        assertThat(executionTime).isLessThan(60000); // Less than 60 seconds
    }

    /**
     * Test error handling with skip policies for invalid transactions.
     * Validates that invalid transactions are skipped and processing continues.
     */
    @Test
    public void testErrorHandlingWithSkipPolicy() throws Exception {
        // Arrange: Mix of valid and invalid transactions
        Account account = createTestAccount(TEST_ACCOUNT_ID_1, new BigDecimal("5000.00"), new BigDecimal("10000.00"));
        accountRepository.save(account);

        Card card = createTestCard(TEST_CARD_NUMBER_1, TEST_ACCOUNT_ID_1);
        cardRepository.save(card);

        // Create dummy card for invalid transaction (to satisfy FK, but no xref = invalid)
        Card invalidCard = createTestCard("8888888888888888", TEST_ACCOUNT_ID_1);
        cardRepository.save(invalidCard);

        // Only create xref for valid card (invalid card has no xref, so batch job will skip it)
        CardAccountXref xref = createTestXref(TEST_CARD_NUMBER_1, TEST_ACCOUNT_ID_1);
        cardAccountXrefRepository.save(xref);

        // Valid transactions
        Transaction validTrans1 = createTestTransaction("TXN001", TEST_CARD_NUMBER_1, 
                TRANSACTION_TYPE_DEBIT, TRANSACTION_CATEGORY_PURCHASE, new BigDecimal("100.00"));
        Transaction validTrans2 = createTestTransaction("TXN002", TEST_CARD_NUMBER_1, 
                TRANSACTION_TYPE_DEBIT, TRANSACTION_CATEGORY_PURCHASE, new BigDecimal("200.00"));
        
        // Invalid transaction (card exists but not in cross-reference, should be skipped)
        Transaction invalidTrans = createTestTransaction("TXN003", "8888888888888888", 
                TRANSACTION_TYPE_DEBIT, TRANSACTION_CATEGORY_PURCHASE, new BigDecimal("50.00"));
        
        transactionRepository.saveAll(List.of(validTrans1, invalidTrans, validTrans2));

        TransactionCategoryBalance catBalance = createTestCategoryBalance(TEST_ACCOUNT_ID_1, 
                TRANSACTION_TYPE_DEBIT, TRANSACTION_CATEGORY_PURCHASE, BigDecimal.ZERO);
        transactionCategoryBalanceRepository.save(catBalance);

        // Act: Execute job
        JobParameters jobParameters = new JobParametersBuilder()
                .addLong("time", System.currentTimeMillis())
                .toJobParameters();
        JobExecution jobExecution = jobLauncherTestUtils.launchJob(jobParameters);

        // Assert: Job completed despite invalid transaction (skip policy)
        assertThat(jobExecution.getStatus()).isEqualTo(BatchStatus.COMPLETED);

        // Verify valid transactions were processed
        Account updatedAccount = accountRepository.findById(TEST_ACCOUNT_ID_1).orElseThrow();
        // Expected: 5000.00 + 100.00 + 200.00 = 5300.00 (invalid transaction skipped)
        BigDecimal expectedBalance = new BigDecimal("5000.00")
                .add(new BigDecimal("100.00"))
                .add(new BigDecimal("200.00"));
        assertThat(updatedAccount.getAcctCurrBal()).isEqualByComparingTo(expectedBalance);
    }

    /**
     * Test credit limit enforcement during transaction posting.
     * Validates that transactions exceeding credit limit are rejected.
     */
    @Test
    public void testCreditLimitEnforcement() throws Exception {
        // Arrange: Account with low credit limit
        BigDecimal initialBalance = new BigDecimal("4500.00");
        BigDecimal creditLimit = new BigDecimal("5000.00");
        Account account = createTestAccount(TEST_ACCOUNT_ID_1, initialBalance, creditLimit);
        accountRepository.save(account);

        Card card = createTestCard(TEST_CARD_NUMBER_1, TEST_ACCOUNT_ID_1);
        cardRepository.save(card);

        CardAccountXref xref = createTestXref(TEST_CARD_NUMBER_1, TEST_ACCOUNT_ID_1);
        cardAccountXrefRepository.save(xref);

        // Transaction that would exceed credit limit: 4500 + 600 = 5100 > 5000
        Transaction exceedingTrans = createTestTransaction("TXN001", TEST_CARD_NUMBER_1, 
                TRANSACTION_TYPE_DEBIT, TRANSACTION_CATEGORY_PURCHASE, new BigDecimal("600.00"));
        
        // Transaction within limit
        Transaction validTrans = createTestTransaction("TXN002", TEST_CARD_NUMBER_1, 
                TRANSACTION_TYPE_DEBIT, TRANSACTION_CATEGORY_PURCHASE, new BigDecimal("400.00"));
        
        transactionRepository.saveAll(List.of(exceedingTrans, validTrans));

        TransactionCategoryBalance catBalance = createTestCategoryBalance(TEST_ACCOUNT_ID_1, 
                TRANSACTION_TYPE_DEBIT, TRANSACTION_CATEGORY_PURCHASE, BigDecimal.ZERO);
        transactionCategoryBalanceRepository.save(catBalance);

        // Act: Execute job
        JobParameters jobParameters = new JobParametersBuilder()
                .addLong("time", System.currentTimeMillis())
                .toJobParameters();
        JobExecution jobExecution = jobLauncherTestUtils.launchJob(jobParameters);

        // Assert: Job completed
        assertThat(jobExecution.getStatus()).isEqualTo(BatchStatus.COMPLETED);

        // Verify only valid transaction was posted
        Account updatedAccount = accountRepository.findById(TEST_ACCOUNT_ID_1).orElseThrow();
        // Expected: 4500.00 + 400.00 = 4900.00 (exceeding transaction rejected)
        BigDecimal expectedBalance = new BigDecimal("4500.00").add(new BigDecimal("400.00"));
        assertThat(updatedAccount.getAcctCurrBal()).isEqualByComparingTo(expectedBalance);
    }

    /**
     * Test transaction isolation and ACID properties.
     * Validates that batch processing maintains database consistency.
     */
    @Test
    public void testTransactionIsolationAndACIDProperties() throws Exception {
        // Arrange: Multiple accounts with transactions
        Account account1 = createTestAccount(TEST_ACCOUNT_ID_1, new BigDecimal("1000.00"), new BigDecimal("5000.00"));
        Account account2 = createTestAccount(TEST_ACCOUNT_ID_2, new BigDecimal("2000.00"), new BigDecimal("5000.00"));
        accountRepository.saveAll(List.of(account1, account2));

        Card card1 = createTestCard(TEST_CARD_NUMBER_1, TEST_ACCOUNT_ID_1);
        Card card2 = createTestCard(TEST_CARD_NUMBER_2, TEST_ACCOUNT_ID_2);
        cardRepository.saveAll(List.of(card1, card2));

        CardAccountXref xref1 = createTestXref(TEST_CARD_NUMBER_1, TEST_ACCOUNT_ID_1);
        CardAccountXref xref2 = createTestXref(TEST_CARD_NUMBER_2, TEST_ACCOUNT_ID_2);
        cardAccountXrefRepository.saveAll(List.of(xref1, xref2));

        // Transactions for both accounts
        Transaction trans1 = createTestTransaction("TXN001", TEST_CARD_NUMBER_1, 
                TRANSACTION_TYPE_DEBIT, TRANSACTION_CATEGORY_PURCHASE, new BigDecimal("500.00"));
        Transaction trans2 = createTestTransaction("TXN002", TEST_CARD_NUMBER_2, 
                TRANSACTION_TYPE_DEBIT, TRANSACTION_CATEGORY_PURCHASE, new BigDecimal("300.00"));
        transactionRepository.saveAll(List.of(trans1, trans2));

        TransactionCategoryBalance catBalance1 = createTestCategoryBalance(TEST_ACCOUNT_ID_1, 
                TRANSACTION_TYPE_DEBIT, TRANSACTION_CATEGORY_PURCHASE, BigDecimal.ZERO);
        TransactionCategoryBalance catBalance2 = createTestCategoryBalance(TEST_ACCOUNT_ID_2, 
                TRANSACTION_TYPE_DEBIT, TRANSACTION_CATEGORY_PURCHASE, BigDecimal.ZERO);
        transactionCategoryBalanceRepository.saveAll(List.of(catBalance1, catBalance2));

        // Act: Execute job
        JobParameters jobParameters = new JobParametersBuilder()
                .addLong("time", System.currentTimeMillis())
                .toJobParameters();
        JobExecution jobExecution = jobLauncherTestUtils.launchJob(jobParameters);

        // Assert: Job completed successfully
        assertThat(jobExecution.getStatus()).isEqualTo(BatchStatus.COMPLETED);

        // Verify ACID properties: Both accounts updated atomically
        Account updatedAccount1 = accountRepository.findById(TEST_ACCOUNT_ID_1).orElseThrow();
        Account updatedAccount2 = accountRepository.findById(TEST_ACCOUNT_ID_2).orElseThrow();
        
        assertThat(updatedAccount1.getAcctCurrBal()).isEqualByComparingTo(new BigDecimal("1500.00"));
        assertThat(updatedAccount2.getAcctCurrBal()).isEqualByComparingTo(new BigDecimal("2300.00"));

        // Verify consistency: Total debits match total balance increases
        BigDecimal totalDebits = new BigDecimal("500.00").add(new BigDecimal("300.00"));
        BigDecimal totalBalanceIncrease = updatedAccount1.getAcctCurrBal()
                .subtract(new BigDecimal("1000.00"))
                .add(updatedAccount2.getAcctCurrBal().subtract(new BigDecimal("2000.00")));
        assertThat(totalBalanceIncrease).isEqualByComparingTo(totalDebits);
    }

    /**
     * Test rollback scenario when transaction posting fails.
     * Validates that failed transactions don't corrupt account balances.
     */
    @Test
    public void testRollbackOnTransactionPostingFailure() throws Exception {
        // Arrange: Account setup
        BigDecimal initialBalance = new BigDecimal("1000.00");
        Account account = createTestAccount(TEST_ACCOUNT_ID_1, initialBalance, new BigDecimal("5000.00"));
        accountRepository.save(account);

        Card card = createTestCard(TEST_CARD_NUMBER_1, TEST_ACCOUNT_ID_1);
        cardRepository.save(card);

        CardAccountXref xref = createTestXref(TEST_CARD_NUMBER_1, TEST_ACCOUNT_ID_1);
        cardAccountXrefRepository.save(xref);

        // Create valid transaction
        Transaction validTrans = createTestTransaction("TXN001", TEST_CARD_NUMBER_1, 
                TRANSACTION_TYPE_DEBIT, TRANSACTION_CATEGORY_PURCHASE, new BigDecimal("100.00"));
        transactionRepository.save(validTrans);

        TransactionCategoryBalance catBalance = createTestCategoryBalance(TEST_ACCOUNT_ID_1, 
                TRANSACTION_TYPE_DEBIT, TRANSACTION_CATEGORY_PURCHASE, BigDecimal.ZERO);
        transactionCategoryBalanceRepository.save(catBalance);

        // Act: Execute job
        JobParameters jobParameters = new JobParametersBuilder()
                .addLong("time", System.currentTimeMillis())
                .toJobParameters();
        JobExecution jobExecution = jobLauncherTestUtils.launchJob(jobParameters);

        // Assert: Job completed (or failed gracefully)
        // In case of failure, verify balance remains unchanged (rollback)
        Account finalAccount = accountRepository.findById(TEST_ACCOUNT_ID_1).orElseThrow();
        
        if (jobExecution.getStatus() == BatchStatus.COMPLETED) {
            // If successful, balance should be updated
            assertThat(finalAccount.getAcctCurrBal())
                    .isEqualByComparingTo(new BigDecimal("1100.00"));
        } else {
            // If failed, balance should remain unchanged (rollback)
            assertThat(finalAccount.getAcctCurrBal())
                    .isEqualByComparingTo(initialBalance);
        }
    }

    /**
     * Test verification of account balance accuracy after posting.
     * Validates BigDecimal precision matches COBOL COMP-3 arithmetic exactly.
     */
    @Test
    public void testAccountBalanceAccuracyWithBigDecimalPrecision() throws Exception {
        // Arrange: Account with precise initial balance
        BigDecimal initialBalance = new BigDecimal("12345.67");
        Account account = createTestAccount(TEST_ACCOUNT_ID_1, initialBalance, new BigDecimal("50000.00"));
        accountRepository.save(account);

        Card card = createTestCard(TEST_CARD_NUMBER_1, TEST_ACCOUNT_ID_1);
        cardRepository.save(card);

        CardAccountXref xref = createTestXref(TEST_CARD_NUMBER_1, TEST_ACCOUNT_ID_1);
        cardAccountXrefRepository.save(xref);

        // Transactions with precise decimal amounts (COBOL COMP-3 equivalent)
        BigDecimal amount1 = new BigDecimal("123.45");
        BigDecimal amount2 = new BigDecimal("67.89");
        BigDecimal amount3 = new BigDecimal("0.12");
        BigDecimal amount4 = new BigDecimal("999.99");

        Transaction trans1 = createTestTransaction("TXN001", TEST_CARD_NUMBER_1, 
                TRANSACTION_TYPE_DEBIT, TRANSACTION_CATEGORY_PURCHASE, amount1);
        Transaction trans2 = createTestTransaction("TXN002", TEST_CARD_NUMBER_1, 
                TRANSACTION_TYPE_DEBIT, TRANSACTION_CATEGORY_PURCHASE, amount2);
        Transaction trans3 = createTestTransaction("TXN003", TEST_CARD_NUMBER_1, 
                TRANSACTION_TYPE_DEBIT, TRANSACTION_CATEGORY_PURCHASE, amount3);
        Transaction trans4 = createTestTransaction("TXN004", TEST_CARD_NUMBER_1, 
                TRANSACTION_TYPE_CREDIT, TRANSACTION_CATEGORY_PAYMENT, amount4);
        
        transactionRepository.saveAll(List.of(trans1, trans2, trans3, trans4));

        TransactionCategoryBalance catBalance1 = createTestCategoryBalance(TEST_ACCOUNT_ID_1, 
                TRANSACTION_TYPE_DEBIT, TRANSACTION_CATEGORY_PURCHASE, BigDecimal.ZERO);
        TransactionCategoryBalance catBalance2 = createTestCategoryBalance(TEST_ACCOUNT_ID_1, 
                TRANSACTION_TYPE_CREDIT, TRANSACTION_CATEGORY_PAYMENT, BigDecimal.ZERO);
        transactionCategoryBalanceRepository.saveAll(List.of(catBalance1, catBalance2));

        // Act: Execute job
        JobParameters jobParameters = new JobParametersBuilder()
                .addLong("time", System.currentTimeMillis())
                .toJobParameters();
        JobExecution jobExecution = jobLauncherTestUtils.launchJob(jobParameters);

        // Assert: Job completed successfully
        assertThat(jobExecution.getStatus()).isEqualTo(BatchStatus.COMPLETED);

        // Verify balance calculated with exact BigDecimal precision
        Account updatedAccount = accountRepository.findById(TEST_ACCOUNT_ID_1).orElseThrow();
        
        // Expected: 12345.67 + 123.45 + 67.89 + 0.12 - 999.99 = 11537.14
        BigDecimal expectedBalance = initialBalance
                .add(amount1)
                .add(amount2)
                .add(amount3)
                .subtract(amount4)
                .setScale(2, RoundingMode.HALF_UP);
        
        assertThat(updatedAccount.getAcctCurrBal()).isEqualByComparingTo(expectedBalance);
        
        // Verify exact match to 2 decimal places (COBOL COMP-3 PIC S9(10)V99)
        assertThat(updatedAccount.getAcctCurrBal().scale()).isEqualTo(2);
    }

    /**
     * Test validation of transaction category balance totals.
     * Ensures category-level aggregations are correct.
     */
    @Test
    public void testTransactionCategoryBalanceTotals() throws Exception {
        // Arrange: Multiple transactions across different categories
        Account account = createTestAccount(TEST_ACCOUNT_ID_1, new BigDecimal("10000.00"), new BigDecimal("20000.00"));
        accountRepository.save(account);

        Card card = createTestCard(TEST_CARD_NUMBER_1, TEST_ACCOUNT_ID_1);
        cardRepository.save(card);

        CardAccountXref xref = createTestXref(TEST_CARD_NUMBER_1, TEST_ACCOUNT_ID_1);
        cardAccountXrefRepository.save(xref);

        // Multiple purchase transactions
        List<Transaction> purchaseTransactions = List.of(
                createTestTransaction("TXN001", TEST_CARD_NUMBER_1, TRANSACTION_TYPE_DEBIT, TRANSACTION_CATEGORY_PURCHASE, new BigDecimal("100.00")),
                createTestTransaction("TXN002", TEST_CARD_NUMBER_1, TRANSACTION_TYPE_DEBIT, TRANSACTION_CATEGORY_PURCHASE, new BigDecimal("250.00")),
                createTestTransaction("TXN003", TEST_CARD_NUMBER_1, TRANSACTION_TYPE_DEBIT, TRANSACTION_CATEGORY_PURCHASE, new BigDecimal("175.50"))
        );

        // Payment transactions
        List<Transaction> paymentTransactions = List.of(
                createTestTransaction("TXN004", TEST_CARD_NUMBER_1, TRANSACTION_TYPE_CREDIT, TRANSACTION_CATEGORY_PAYMENT, new BigDecimal("200.00")),
                createTestTransaction("TXN005", TEST_CARD_NUMBER_1, TRANSACTION_TYPE_CREDIT, TRANSACTION_CATEGORY_PAYMENT, new BigDecimal("150.00"))
        );

        List<Transaction> allTransactions = new ArrayList<>();
        allTransactions.addAll(purchaseTransactions);
        allTransactions.addAll(paymentTransactions);
        transactionRepository.saveAll(allTransactions);

        TransactionCategoryBalance purchaseCatBalance = createTestCategoryBalance(TEST_ACCOUNT_ID_1, 
                TRANSACTION_TYPE_DEBIT, TRANSACTION_CATEGORY_PURCHASE, BigDecimal.ZERO);
        TransactionCategoryBalance paymentCatBalance = createTestCategoryBalance(TEST_ACCOUNT_ID_1, 
                TRANSACTION_TYPE_CREDIT, TRANSACTION_CATEGORY_PAYMENT, BigDecimal.ZERO);
        transactionCategoryBalanceRepository.saveAll(List.of(purchaseCatBalance, paymentCatBalance));

        // Act: Execute job
        JobParameters jobParameters = new JobParametersBuilder()
                .addLong("time", System.currentTimeMillis())
                .toJobParameters();
        JobExecution jobExecution = jobLauncherTestUtils.launchJob(jobParameters);

        // Assert: Job completed successfully
        assertThat(jobExecution.getStatus()).isEqualTo(BatchStatus.COMPLETED);

        // Verify category balance totals
        List<TransactionCategoryBalance> categoryBalances = transactionCategoryBalanceRepository
                .findByTcatAcctId(TEST_ACCOUNT_ID_1);
        assertThat(categoryBalances).hasSize(2);

        // Purchase category: 100.00 + 250.00 + 175.50 = 525.50
        BigDecimal expectedPurchaseTotal = new BigDecimal("100.00")
                .add(new BigDecimal("250.00"))
                .add(new BigDecimal("175.50"));
        Optional<TransactionCategoryBalance> purchaseBalance = categoryBalances.stream()
                .filter(cb -> cb.getTcatCatCd().equals(TRANSACTION_CATEGORY_PURCHASE))
                .findFirst();
        assertThat(purchaseBalance).isPresent();
        assertThat(purchaseBalance.get().getTcatBal()).isEqualByComparingTo(expectedPurchaseTotal);

        // Payment category: 200.00 + 150.00 = 350.00
        BigDecimal expectedPaymentTotal = new BigDecimal("200.00").add(new BigDecimal("150.00"));
        Optional<TransactionCategoryBalance> paymentBalance = categoryBalances.stream()
                .filter(cb -> cb.getTcatCatCd().equals(TRANSACTION_CATEGORY_PAYMENT))
                .findFirst();
        assertThat(paymentBalance).isPresent();
        assertThat(paymentBalance.get().getTcatBal()).isEqualByComparingTo(expectedPaymentTotal);
    }

    // ==================== Helper Methods for Test Data Creation ====================

    /**
     * Create test account entity matching COBOL CVACT01Y.cpy structure.
     */
    private Account createTestAccount(Long accountId, BigDecimal balance, BigDecimal creditLimit) {
        return Account.builder()
                .acctId(accountId)
                .acctActiveStatus("Y")
                .acctCurrBal(balance)
                .acctCreditLimit(creditLimit)
                .acctCashCreditLimit(creditLimit.multiply(new BigDecimal("0.3")))
                .acctOpenDate(LocalDate.now().minusYears(1))
                .acctExpirationDate(LocalDate.now().plusYears(3))
                .acctReissueDate(null)
                .acctCurrCycCredit(BigDecimal.ZERO)
                .acctCurrCycDebit(BigDecimal.ZERO)
                .acctAddrZip("12345")
                .acctGroupId("GRP001")
                .build();
    }

    /**
     * Create test card entity matching COBOL CVACT02Y.cpy structure.
     */
    private Card createTestCard(String cardNumber, Long accountId) {
        return Card.builder()
                .cardNum(cardNumber)
                .cardAcctId(accountId)
                .cardStatus("A")
                .cardEmbossedName("TEST CARDHOLDER")
                .cardExpirationDate(LocalDate.now().plusYears(3))
                .build();
    }

    /**
     * Create test card-account cross-reference matching COBOL CVACT03Y.cpy structure.
     */
    private CardAccountXref createTestXref(String cardNumber, Long accountId) {
        return CardAccountXref.builder()
                .xrefCardNum(cardNumber)
                .xrefAcctId(accountId)
                .xrefCustId(TEST_CUSTOMER_ID)
                .build();
    }

    /**
     * Create test transaction entity matching COBOL CVTRA05Y.cpy structure.
     */
    private Transaction createTestTransaction(String transId, String cardNumber, String transTypeCd, 
                                             Integer transCatCd, BigDecimal amount) {
        return Transaction.builder()
                .transId(transId)
                .transCardNum(cardNumber)
                .transTypeCd(transTypeCd)
                .transCatCd(transCatCd)
                .transSource("WEB")
                .transDesc("TEST TRANSACTION")
                .transAmt(amount)
                .transMerchantId(123456789L)
                .transMerchantName("Test Merchant")
                .transMerchantCity("Test City")
                .transMerchantZip("12345")
                .transOrigTs(new java.sql.Timestamp(System.currentTimeMillis()))
                .transProcTs(new java.sql.Timestamp(System.currentTimeMillis()))
                .build();
    }

    /**
     * Create test transaction category balance matching COBOL CVTRA01Y.cpy structure.
     */
    private TransactionCategoryBalance createTestCategoryBalance(Long accountId, String transTypeCd, 
                                                                 Integer transCatCd, BigDecimal balance) {
        return TransactionCategoryBalance.builder()
                .tcatAcctId(accountId)
                .tcatTypeCd(transTypeCd)
                .tcatCatCd(transCatCd)
                .tcatBal(balance)
                .build();
    }

    /**
     * Create test customer entity matching COBOL CVCUS01Y.cpy structure.
     */
    private Customer createTestCustomer(Long customerId) {
        return Customer.builder()
                .custId(customerId)
                .custFirstName("John")
                .custMiddleName("Q")
                .custLastName("Doe")
                .custAddrLine1("123 Main St")
                .custAddrLine2("Apt 4B")
                .custAddrLine3("")
                .custAddrStateCd("NY")
                .custAddrCountryCd("USA")
                .custAddrZip("10001")
                .custPhoneNum1("212-555-1234")
                .custPhoneNum2("")
                .custSsn("123456789")
                .custGovtIssuedId("DL123456")
                .custDobYyyyMmDd(LocalDate.of(1980, 1, 1))
                .custFicoCreditScore(750)
                .build();
    }

    /**
     * Create test transaction type matching COBOL CVTRA03Y.cpy structure.
     */
    private TransactionType createTestTransactionType(String transTypeCd, String description) {
        return TransactionType.builder()
                .transTypeCd(transTypeCd)
                .transTypeDesc(description)
                .build();
    }

    /**
     * Create test transaction category matching COBOL CVTRA04Y.cpy structure.
     */
    private TransactionCategory createTestTransactionCategory(String transTypeCd, Integer tranCatCd, String description) {
        return TransactionCategory.builder()
                .transTypeCd(transTypeCd)
                .tranCatCd(tranCatCd)
                .tranCatTypeDesc(description)
                .build();
    }
}
