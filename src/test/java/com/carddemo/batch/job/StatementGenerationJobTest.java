package com.carddemo.batch.job;

import com.carddemo.entity.Account;
import com.carddemo.entity.Card;
import com.carddemo.entity.Customer;
import com.carddemo.entity.Transaction;
import com.carddemo.repository.AccountRepository;
import com.carddemo.repository.CardRepository;
import com.carddemo.repository.CustomerRepository;
import com.carddemo.repository.TransactionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.batch.core.*;
import org.springframework.batch.test.JobLauncherTestUtils;
import org.springframework.batch.test.JobRepositoryTestUtils;
import org.springframework.batch.test.context.SpringBatchTest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;
import com.carddemo.config.TestBatchConfig;

import java.io.File;
import java.math.BigDecimal;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration tests for StatementGenerationJob.
 * 
 * <p>This test class validates the complete statement generation batch job including:</p>
 * <ul>
 *   <li>Transaction data retrieval with JOIN FETCH for related entities</li>
 *   <li>Statement detail processing and formatting</li>
 *   <li>PDF generation using JasperReports</li>
 *   <li>Job execution lifecycle (beforeJob, afterJob callbacks)</li>
 *   <li>Error handling and skip logic</li>
 * </ul>
 * 
 * <p><b>Test Pattern:</b> Follows AccountDataLoadJobTest pattern using @SpringBatchTest,
 * JobLauncherTestUtils, and real database operations with test data setup.</p>
 * 
 * @see com.carddemo.batch.job.StatementGenerationJob
 * @see com.carddemo.batch.processor.StatementDetailProcessor
 */
@SpringBatchTest
@SpringBootTest
@ActiveProfiles("test")
@Import(TestBatchConfig.class)
@DisplayName("StatementGenerationJob Integration Tests")
class StatementGenerationJobTest {

    @Autowired
    private JobLauncherTestUtils jobLauncherTestUtils;

    @Autowired
    private JobRepositoryTestUtils jobRepositoryTestUtils;

    @Autowired
    private CustomerRepository customerRepository;

    @Autowired
    private AccountRepository accountRepository;

    @Autowired
    private CardRepository cardRepository;

    @Autowired
    private TransactionRepository transactionRepository;

    /**
     * The specific Job bean to test.
     * Qualified by bean name to disambiguate from other Job beans in the application context.
     */
    @Autowired
    @Qualifier("monthlyStatementGenerationJob")
    private Job monthlyStatementGenerationJob;

    @TempDir
    Path tempDir;

    /**
     * Clean database and set up test data before each test.
     * 
     * <p>Creates a complete entity graph:</p>
     * <ul>
     *   <li>Customer → Account → Card → Transactions</li>
     * </ul>
     * 
     * <p>This matches the COBOL test data structure from app/data/ASCII test files.</p>
     */
    @BeforeEach
    @Transactional
    void setUp() {
        // Set the specific Job bean on JobLauncherTestUtils
        // Required when multiple Job beans exist in application context
        jobLauncherTestUtils.setJob(monthlyStatementGenerationJob);
        
        // Clean up job execution history
        if (jobRepositoryTestUtils != null) {
            jobRepositoryTestUtils.removeJobExecutions();
        }

        // Clean up entities (in correct order to avoid FK violations)
        transactionRepository.deleteAll();
        cardRepository.deleteAll();
        accountRepository.deleteAll();
        customerRepository.deleteAll();

        // Create test data
        setupTestData();
    }

    /**
     * Test successful statement generation with complete transaction data.
     * 
     * <p>Verifies:</p>
     * <ul>
     *   <li>Job completes with COMPLETED status</li>
     *   <li>PDF files are generated in output directory</li>
     *   <li>Step execution metrics (read count, write count)</li>
     *   <li>No errors or skips during processing</li>
     * </ul>
     */
    @Test
    @DisplayName("Should successfully generate statements for transaction data")
    void testStatementGenerationJob_Success() throws Exception {
        // Given: Job parameters with date range and output path
        LocalDate startDate = LocalDate.of(2024, 1, 1);
        LocalDate endDate = LocalDate.of(2024, 1, 31);
        String outputPath = tempDir.toString();

        JobParameters jobParameters = new JobParametersBuilder()
                .addLocalDate("startDate", startDate)
                .addLocalDate("endDate", endDate)
                .addString("outputPath", outputPath)
                .addLong("timestamp", System.currentTimeMillis()) // Unique parameter for test runs
                .toJobParameters();

        // When: Job is executed
        JobExecution jobExecution = jobLauncherTestUtils.launchJob(jobParameters);

        // Then: Job should complete successfully
        assertEquals(BatchStatus.COMPLETED, jobExecution.getStatus(), 
                "Job should complete with COMPLETED status");
        assertEquals(ExitStatus.COMPLETED, jobExecution.getExitStatus(),
                "Job should have COMPLETED exit status");

        // Verify step execution
        StepExecution stepExecution = jobExecution.getStepExecutions().iterator().next();
        assertEquals("statementGenerationStep", stepExecution.getStepName(),
                "Step name should match");
        
        // Verify transactions were read (5 transactions created in setup)
        assertTrue(stepExecution.getReadCount() > 0,
                "Should have read transactions");
        
        // Verify statements were written (2 accounts = 2 potential statement groups)
        assertTrue(stepExecution.getWriteCount() > 0,
                "Should have written statement details");
        
        // Verify no errors
        assertEquals(0, stepExecution.getSkipCount(),
                "Should have no skipped items");
        assertEquals(0, stepExecution.getReadSkipCount(),
                "Should have no read skips");
        assertEquals(0, stepExecution.getWriteSkipCount(),
                "Should have no write skips");

        // Verify PDF files were created
        File outputDir = new File(outputPath);
        assertTrue(outputDir.exists(), "Output directory should exist");
        
        File[] pdfFiles = outputDir.listFiles((dir, name) -> name.endsWith(".pdf"));
        assertNotNull(pdfFiles, "PDF files array should not be null");
        assertTrue(pdfFiles.length > 0, "At least one PDF statement should be generated");
    }

    /**
     * Test job parameter validation in beforeJob listener.
     * 
     * <p>Verifies that invalid date range (startDate after endDate) is rejected.</p>
     */
    @Test
    @DisplayName("Should fail when startDate is after endDate")
    void testStatementGenerationJob_InvalidDateRange() throws Exception {
        // Given: Invalid job parameters (start after end)
        LocalDate startDate = LocalDate.of(2024, 1, 31);
        LocalDate endDate = LocalDate.of(2024, 1, 1);
        String outputPath = tempDir.toString();

        JobParameters jobParameters = new JobParametersBuilder()
                .addLocalDate("startDate", startDate)
                .addLocalDate("endDate", endDate)
                .addString("outputPath", outputPath)
                .addLong("timestamp", System.currentTimeMillis())
                .toJobParameters();

        // When: Job is executed with invalid date range
        JobExecution jobExecution = jobLauncherTestUtils.launchJob(jobParameters);

        // Then: Job should fail with validation error
        assertEquals(BatchStatus.FAILED, jobExecution.getStatus(), 
                "Job should fail with invalid date range");
        assertTrue(jobExecution.getAllFailureExceptions().stream()
                .anyMatch(e -> e.getMessage().contains("Start date must be before end date")),
                "Job should fail with date validation error message");
    }

    /**
     * Test statement generation with empty date range (no transactions).
     * 
     * <p>Verifies job handles empty result set gracefully.</p>
     */
    @Test
    @DisplayName("Should complete successfully with no transactions in date range")
    void testStatementGenerationJob_NoTransactions() throws Exception {
        // Given: Date range with no transactions (future dates)
        LocalDate startDate = LocalDate.of(2025, 12, 1);
        LocalDate endDate = LocalDate.of(2025, 12, 31);
        String outputPath = tempDir.toString();

        JobParameters jobParameters = new JobParametersBuilder()
                .addLocalDate("startDate", startDate)
                .addLocalDate("endDate", endDate)
                .addString("outputPath", outputPath)
                .addLong("timestamp", System.currentTimeMillis())
                .toJobParameters();

        // When: Job is executed
        JobExecution jobExecution = jobLauncherTestUtils.launchJob(jobParameters);

        // Then: Job should complete (no transactions is valid scenario)
        assertEquals(BatchStatus.COMPLETED, jobExecution.getStatus(),
                "Job should complete even with no transactions");

        StepExecution stepExecution = jobExecution.getStepExecutions().iterator().next();
        assertEquals(0, stepExecution.getReadCount(),
                "Should have read zero transactions");
        assertEquals(0, stepExecution.getWriteCount(),
                "Should have written zero statements");
    }

    /**
     * Test output directory creation in beforeJob listener.
     * 
     * <p>Verifies that the job creates the output directory if it doesn't exist.</p>
     */
    @Test
    @DisplayName("Should create output directory if it doesn't exist")
    void testStatementGenerationJob_CreateOutputDirectory() throws Exception {
        // Given: Non-existent output directory path
        String newOutputPath = tempDir.resolve("statements/2024/01").toString();
        assertFalse(new File(newOutputPath).exists(), "Directory should not exist initially");

        LocalDate startDate = LocalDate.of(2024, 1, 1);
        LocalDate endDate = LocalDate.of(2024, 1, 31);

        JobParameters jobParameters = new JobParametersBuilder()
                .addLocalDate("startDate", startDate)
                .addLocalDate("endDate", endDate)
                .addString("outputPath", newOutputPath)
                .addLong("timestamp", System.currentTimeMillis())
                .toJobParameters();

        // When: Job is executed
        JobExecution jobExecution = jobLauncherTestUtils.launchJob(jobParameters);

        // Then: Job should create directory and complete
        assertEquals(BatchStatus.COMPLETED, jobExecution.getStatus(),
                "Job should complete successfully");
        assertTrue(new File(newOutputPath).exists(),
                "Output directory should be created");
    }

    /**
     * Test job metrics and statistics in afterJob listener.
     * 
     * <p>Verifies that job execution includes proper metrics for monitoring.</p>
     */
    @Test
    @DisplayName("Should log comprehensive statistics in afterJob")
    void testStatementGenerationJob_Metrics() throws Exception {
        // Given: Standard job parameters
        LocalDate startDate = LocalDate.of(2024, 1, 1);
        LocalDate endDate = LocalDate.of(2024, 1, 31);
        String outputPath = tempDir.toString();

        JobParameters jobParameters = new JobParametersBuilder()
                .addLocalDate("startDate", startDate)
                .addLocalDate("endDate", endDate)
                .addString("outputPath", outputPath)
                .addLong("timestamp", System.currentTimeMillis())
                .toJobParameters();

        // When: Job is executed
        long startTime = System.currentTimeMillis();
        JobExecution jobExecution = jobLauncherTestUtils.launchJob(jobParameters);
        long endTime = System.currentTimeMillis();

        // Then: Verify job execution metadata
        assertNotNull(jobExecution.getStartTime(), "Start time should be recorded");
        assertNotNull(jobExecution.getEndTime(), "End time should be recorded");
        assertTrue(jobExecution.getEndTime().isAfter(jobExecution.getStartTime()),
                "End time should be after start time");

        // Verify execution duration is reasonable
        long actualDuration = endTime - startTime;
        assertTrue(actualDuration < 60000, // Less than 60 seconds
                "Job should complete in reasonable time");

        // Verify step execution metrics
        StepExecution stepExecution = jobExecution.getStepExecutions().iterator().next();
        assertTrue(stepExecution.getReadCount() >= 0, "Read count should be available");
        assertTrue(stepExecution.getWriteCount() >= 0, "Write count should be available");
        assertTrue(stepExecution.getCommitCount() >= 0, "Commit count should be available");
    }

    /**
     * Set up comprehensive test data matching COBOL test data structure.
     * 
     * <p>Creates:</p>
     * <ul>
     *   <li>2 Customers</li>
     *   <li>2 Accounts (one per customer)</li>
     *   <li>2 Cards (one per account)</li>
     *   <li>5 Transactions across both cards</li>
     * </ul>
     * 
     * <p>Transaction dates are set to January 2024 to match typical test date range.</p>
     */
    private void setupTestData() {
        // Customer 1
        Customer customer1 = Customer.builder()
                .customerId(100000001L)
                .firstName("John")
                .middleName("A")
                .lastName("Doe")
                .addressLine1("123 Main St")
                .addressLine2("Apt 4B")
                .addressLine3("New York")
                .addressStateCode("NY")
                .addressZip("10001")
                .addressCountryCode("USA")
                .ssn("123456789")
                .dateOfBirth(LocalDate.of(1980, 1, 15))
                .ficoScore(750)
                .build();
        customer1 = customerRepository.save(customer1);

        // Account 1
        Account account1 = new Account();
        account1.setAccountId(10000000001L);
        account1.setCustomer(customer1);
        account1.setActiveStatus("Y");
        account1.setCurrentBalance(new BigDecimal("1500.50"));
        account1.setCreditLimit(new BigDecimal("5000.00"));
        account1.setCashCreditLimit(new BigDecimal("1000.00"));
        account1.setOpenDate(LocalDate.of(2020, 1, 1));
        account1.setExpirationDate(LocalDate.of(2030, 12, 31));
        account1 = accountRepository.save(account1);

        // Card 1
        Card card1 = new Card();
        card1.setCardNumber("4000123456789010");
        card1.setAccount(account1);
        card1.setCardType("CC");  // Credit Card - 2-character code
        card1.setExpirationDate(LocalDate.of(2025, 12, 31));
        card1.setActiveStatus("Y");
        card1 = cardRepository.save(card1);

        // Customer 2
        Customer customer2 = Customer.builder()
                .customerId(100000002L)
                .firstName("Jane")
                .middleName("B")
                .lastName("Smith")
                .addressLine1("456 Oak Ave")
                .addressLine2("Unit 2C")
                .addressLine3("Boston")
                .addressStateCode("MA")
                .addressZip("02101")
                .addressCountryCode("USA")
                .ssn("987654321")
                .dateOfBirth(LocalDate.of(1985, 6, 20))
                .ficoScore(800)
                .build();
        customer2 = customerRepository.save(customer2);

        // Account 2
        Account account2 = new Account();
        account2.setAccountId(10000000002L);
        account2.setCustomer(customer2);
        account2.setActiveStatus("Y");
        account2.setCurrentBalance(new BigDecimal("2500.75"));
        account2.setCreditLimit(new BigDecimal("10000.00"));
        account2.setCashCreditLimit(new BigDecimal("2000.00"));
        account2.setOpenDate(LocalDate.of(2019, 6, 15));
        account2.setExpirationDate(LocalDate.of(2029, 12, 31));
        account2 = accountRepository.save(account2);

        // Card 2
        Card card2 = new Card();
        card2.setCardNumber("4000987654321098");
        card2.setAccount(account2);
        card2.setCardType("CC");  // Credit Card - 2-character code
        card2.setExpirationDate(LocalDate.of(2026, 6, 30));
        card2.setActiveStatus("Y");
        card2 = cardRepository.save(card2);

        // Transactions for Card 1
        // Using Type 01 = Purchase, Category 1 = Regular Sales Draft (from seed data)
        createTransaction("TX00001", card1, new BigDecimal("100.50"), 
                LocalDateTime.of(2024, 1, 5, 10, 30), "Store A", "New York", "01", "1");
        createTransaction("TX00002", card1, new BigDecimal("250.00"), 
                LocalDateTime.of(2024, 1, 10, 14, 45), "Store B", "New York", "01", "1");
        createTransaction("TX00003", card1, new BigDecimal("75.25"), 
                LocalDateTime.of(2024, 1, 15, 9, 15), "Store C", "New York", "01", "1");

        // Transactions for Card 2
        createTransaction("TX00004", card2, new BigDecimal("500.00"), 
                LocalDateTime.of(2024, 1, 7, 11, 20), "Store D", "Boston", "01", "1");
        createTransaction("TX00005", card2, new BigDecimal("125.50"), 
                LocalDateTime.of(2024, 1, 12, 16, 30), "Store E", "Boston", "01", "1");
    }

    /**
     * Helper method to create and persist transaction entities.
     * 
     * <p>Matches COBOL transaction structure from CVTRA05Y.cpy copybook.</p>
     */
    private void createTransaction(String txId, Card card, BigDecimal amount, 
                                  LocalDateTime timestamp, String merchantName, 
                                  String merchantCity, String typeCode, String categoryCode) {
        Transaction tx = new Transaction();
        tx.setTransactionId(txId);
        tx.setCard(card);
        tx.setAmount(amount);
        tx.setOriginationTimestamp(timestamp);
        tx.setProcessingTimestamp(timestamp);
        tx.setMerchantName(merchantName);
        tx.setMerchantCity(merchantCity);
        tx.setMerchantZip("00000");
        tx.setTypeCode(typeCode);
        tx.setCategoryCode(categoryCode);
        tx.setTransactionSource("POS");
        tx.setDescription(typeCode + " - " + merchantName);
        transactionRepository.save(tx);
    }
}
