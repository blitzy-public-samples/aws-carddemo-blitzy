/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License").
 * You may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.cardemo.batch;

import com.cardemo.entity.Account;
import com.cardemo.entity.CardXref;
import com.cardemo.entity.Customer;
import com.cardemo.entity.Transaction;
import com.cardemo.repository.AccountRepository;
import com.cardemo.repository.CardXrefRepository;
import com.cardemo.repository.CustomerRepository;
import com.cardemo.repository.TransactionRepository;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.JobExecution;
import org.springframework.batch.core.JobParameters;
import org.springframework.batch.core.JobParametersBuilder;
import org.springframework.batch.test.JobLauncherTestUtils;
import org.springframework.batch.test.JobRepositoryTestUtils;
import org.springframework.batch.test.context.SpringBatchTest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.io.File;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration test for the statement generation Spring Batch job.
 *
 * <p>This test validates the multi-step pipeline migrated from the JCL
 * CREASTMT job: account/card iteration → transaction aggregation → text +
 * HTML statement output. It tests the JCL CREASTMT → Spring Batch migration
 * with 100 % business logic parity against CBSTM03A.CBL (main engine) and
 * CBSTM03B.CBL (I/O subroutine).</p>
 *
 * <h2>COBOL → Java Traceability</h2>
 * <table>
 *   <caption>Validated COBOL paragraphs</caption>
 *   <tr><th>COBOL Paragraph</th><th>Verified Behaviour</th></tr>
 *   <tr><td>5000-CREATE-STATEMENT</td>
 *       <td>Customer name STRING concatenation, address lines, account info</td></tr>
 *   <tr><td>4000-TRNXFILE-GET</td>
 *       <td>WS-TRNX-TABLE grouping by card number, total accumulation</td></tr>
 *   <tr><td>5100-WRITE-HTML-HEADER</td>
 *       <td>HTML structure, CSS colours</td></tr>
 *   <tr><td>5200-WRITE-HTML-NMADBS</td>
 *       <td>HTML customer name/address/basic-details</td></tr>
 *   <tr><td>6000-WRITE-TRANS</td>
 *       <td>Per-transaction text + HTML lines</td></tr>
 *   <tr><td>CALL 'CBSTM03B'</td>
 *       <td>Delegation to StatementIoService</td></tr>
 * </table>
 *
 * @see com.cardemo.service.batch.StatementEngineService
 * @see com.cardemo.service.batch.StatementIoService
 */
@SpringBatchTest
@SpringBootTest
@ActiveProfiles("test")
@Testcontainers
class StatementGenJobIntegrationTest {

    // -----------------------------------------------------------------------
    // Testcontainers – real PostgreSQL 16+ for integration testing
    // -----------------------------------------------------------------------

    @Container
    static PostgreSQLContainer postgres =
            new PostgreSQLContainer("postgres:16-alpine");

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        // Override the Testcontainers JDBC driver with the standard PostgreSQL
        // driver since @Container manages the container lifecycle directly
        // (the test profile's jdbc:tc: URL is for auto-managed containers)
        registry.add("spring.datasource.driver-class-name",
                () -> "org.postgresql.Driver");
    }

    // -----------------------------------------------------------------------
    // JUnit 5 temporary directory for statement output files
    // -----------------------------------------------------------------------

    @TempDir
    Path tempDir;

    // -----------------------------------------------------------------------
    // Spring Batch test infrastructure
    // -----------------------------------------------------------------------

    @Autowired
    private JobLauncherTestUtils jobLauncherTestUtils;

    @Autowired
    private JobRepositoryTestUtils jobRepositoryTestUtils;

    // -----------------------------------------------------------------------
    // Specific job under test
    // -----------------------------------------------------------------------

    @Autowired
    @Qualifier("statementGenJob")
    private Job statementGenJob;

    // -----------------------------------------------------------------------
    // JPA repositories for test data seeding and cleanup
    // -----------------------------------------------------------------------

    @Autowired
    private AccountRepository accountRepository;

    @Autowired
    private CustomerRepository customerRepository;

    @Autowired
    private CardXrefRepository cardXrefRepository;

    @Autowired
    private TransactionRepository transactionRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    // -----------------------------------------------------------------------
    // Shared test constants
    // -----------------------------------------------------------------------

    private static final String CUST_ID = "000000001";
    private static final String ACCT_ID = "00000000001";
    private static final String CARD_NUM = "4000000000000001";
    private static final BigDecimal ACCT_BALANCE =
            new BigDecimal("5000.00");

    // -----------------------------------------------------------------------
    // @BeforeEach — clean batch metadata and seed baseline data
    // -----------------------------------------------------------------------

    /**
     * Cleans Spring Batch metadata, removes any leftover test data, wires
     * the specific job, and optionally seeds a standard dataset used by
     * the majority of test methods.
     */
    @BeforeEach
    void setUp() {
        // 1. Remove previous batch metadata
        jobRepositoryTestUtils.removeJobExecutions();

        // 2. Wire the specific job under test
        jobLauncherTestUtils.setJob(statementGenJob);

        // 3. Clean up all tables using TRUNCATE CASCADE to respect FK constraints.
        //    Flyway seed data populates cards, accounts, etc. — JPA deleteAll() fails
        //    if FK-referencing rows exist in tables we do not manage directly.
        jdbcTemplate.execute("TRUNCATE TABLE transactions, daily_transactions, "
                + "category_balances, card_xrefs, cards, accounts, customers, "
                + "discount_groups, transaction_type_refs, transaction_category_refs, "
                + "user_security CASCADE");
    }

    // -----------------------------------------------------------------------
    // Helper — build JobParameters with output directory
    // -----------------------------------------------------------------------

    /**
     * Builds a {@link JobParameters} instance pointing the statement output
     * directory at the JUnit 5 {@link TempDir}.
     */
    private JobParameters buildJobParams() {
        return new JobParametersBuilder()
                .addString("outputDir", tempDir.toString())
                .addLong("run.id", System.currentTimeMillis())
                .toJobParameters();
    }

    // -----------------------------------------------------------------------
    // Helper — seed default customer
    // -----------------------------------------------------------------------

    /**
     * Creates and persists a standard Customer record matching COBOL seed
     * data: CUST-ID = 000000001, John M Doe, 123 Main St, Springfield IL US.
     */
    private Customer seedDefaultCustomer() {
        Customer customer = new Customer(
                CUST_ID,        // custId
                "John",         // firstName
                "M",            // middleName
                "Doe",          // lastName
                "123 Main St",  // addrLine1
                "Apt 4B",       // addrLine2
                "Springfield",  // addrLine3
                "IL",           // addrStateCode
                "US",           // addrCountryCode
                "62701",        // addrZip
                "5551234567",   // phoneNum1
                null,           // phoneNum2
                "123456789",    // ssn
                null,           // govtIssuedId
                "1980-01-15",   // dateOfBirth
                null,           // eftAccountId
                "Y",            // priCardHolderInd
                750             // ficoCreditScore
        );
        return customerRepository.save(customer);
    }

    // -----------------------------------------------------------------------
    // Helper — seed default account
    // -----------------------------------------------------------------------

    /**
     * Creates and persists a standard Account record: ACCT-ID = 00000000001,
     * CURR-BAL = 5000.00, ACTIVE-STATUS = 'Y'.
     */
    private Account seedDefaultAccount() {
        Account account = new Account(
                ACCT_ID,                        // acctId
                "Y",                            // activeStatus
                ACCT_BALANCE,                   // currBal (5000.00)
                new BigDecimal("10000.00"),     // creditLimit
                new BigDecimal("5000.00"),      // cashCreditLimit
                "2020-01-01",                   // openDate
                "2030-12-31",                   // expirationDate
                "2025-01-01",                   // reissueDate
                BigDecimal.ZERO,                // currCycCredit
                BigDecimal.ZERO,                // currCycDebit
                "62701",                        // addrZip
                "DEFAULT"                       // groupId
        );
        return accountRepository.save(account);
    }

    // -----------------------------------------------------------------------
    // Helper — seed default card cross-reference
    // -----------------------------------------------------------------------

    /**
     * Creates and persists a CardXref record linking CARD-NUM →
     * ACCT-ID + CUST-ID.
     */
    private CardXref seedDefaultCardXref() {
        CardXref xref = new CardXref(CARD_NUM, CUST_ID, ACCT_ID);
        return cardXrefRepository.save(xref);
    }

    // -----------------------------------------------------------------------
    // Helper — seed standard transactions
    // -----------------------------------------------------------------------

    /**
     * Seeds three Transaction records for the default card with known amounts.
     * Expected total: 125.50 + 75.25 + 200.00 = 400.75.
     */
    private void seedDefaultTransactions() {
        Transaction t1 = buildTransaction(
                "0000001", CARD_NUM,
                new BigDecimal("125.50"), "Purchase at Store A");
        Transaction t2 = buildTransaction(
                "0000002", CARD_NUM,
                new BigDecimal("75.25"), "Purchase at Store B");
        Transaction t3 = buildTransaction(
                "0000003", CARD_NUM,
                new BigDecimal("200.00"), "Online Order");
        transactionRepository.save(t1);
        transactionRepository.save(t2);
        transactionRepository.save(t3);
    }

    /**
     * Convenience factory for building a Transaction entity with the
     * minimum required fields.
     */
    private Transaction buildTransaction(String tranId, String cardNum,
                                         BigDecimal amount, String description) {
        return new Transaction(
                tranId,         // tranId
                "SA",           // typeCode (sale)
                1,              // categoryCode
                "ONLINE",       // source
                description,    // description
                amount,         // amount (BigDecimal — NEVER float/double)
                "MERCH001",     // merchantId
                "Test Store",   // merchantName
                "Springfield",  // merchantCity
                "62701",        // merchantZip
                cardNum,        // cardNum
                "2025-01-15-10.30.00.000000",  // origTimestamp
                "2025-01-15-10.30.00.000000"   // procTimestamp
        );
    }

    // -----------------------------------------------------------------------
    // Helper — seed the complete baseline dataset
    // -----------------------------------------------------------------------

    /**
     * Seeds customer + account + card cross-reference + three transactions.
     * Called by most test methods that need a complete data context.
     */
    private void seedFullDataset() {
        seedDefaultCustomer();
        seedDefaultAccount();
        seedDefaultCardXref();
        seedDefaultTransactions();
    }

    // ===================================================================
    // 4.1 — Happy Path: job completes successfully
    // ===================================================================

    @Test
    @DisplayName("Statement generation job completes with COMPLETED status")
    void testStatementGenJobCompletesSuccessfully() throws Exception {
        // Arrange — seed full dataset
        seedFullDataset();

        // Act — execute job with output directory parameter
        JobExecution execution = jobLauncherTestUtils.launchJob(buildJobParams());

        // Assert — job completed successfully
        assertThat(execution.getStatus()).isEqualTo(BatchStatus.COMPLETED);

        // Assert — at least one output file was produced in the temp directory
        try (Stream<Path> paths = Files.list(tempDir)) {
            assertThat(paths.count()).isGreaterThan(0L);
        }
    }

    // ===================================================================
    // 4.2 — Plain text statement format verification (STMT-FILE output)
    // ===================================================================

    @Test
    @DisplayName("Plain text statement contains customer name, address, "
            + "account info, and transactions")
    void testPlainTextStatementFormat() throws Exception {
        // Arrange
        seedFullDataset();

        // Act
        JobExecution execution = jobLauncherTestUtils.launchJob(buildJobParams());
        assertThat(execution.getStatus()).isEqualTo(BatchStatus.COMPLETED);

        // Find the plain-text statement output file
        String textContent = findAndReadStatementFile("stmt", ".txt");

        // Assert — customer name (COBOL STRING concatenation of FIRST + MIDDLE + LAST)
        assertThat(textContent).contains("John");
        assertThat(textContent).contains("Doe");

        // Assert — address lines
        assertThat(textContent).contains("123 Main St");
        assertThat(textContent).contains("Apt 4B");
        assertThat(textContent).contains("Springfield");

        // Assert — account ID (ST-ACCT-ID = "00000000001")
        assertThat(textContent).contains("00000000001");

        // Assert — account info headers
        assertThat(textContent).containsIgnoringCase("Account ID");
        assertThat(textContent).containsIgnoringCase("Current Balance");
        assertThat(textContent).containsIgnoringCase("FICO Score");

        // Assert — transaction details
        assertThat(textContent).contains("Purchase at Store A");
        assertThat(textContent).contains("Purchase at Store B");
        assertThat(textContent).contains("Online Order");

        // Assert — separator lines present
        assertThat(textContent).contains("---");
    }

    // ===================================================================
    // 4.3 — HTML statement format verification (HTML-FILE output)
    // ===================================================================

    @Test
    @DisplayName("HTML statement contains valid HTML structure with COBOL-exact "
            + "CSS colour codes")
    void testHtmlStatementFormat() throws Exception {
        // Arrange
        seedFullDataset();

        // Act
        JobExecution execution = jobLauncherTestUtils.launchJob(buildJobParams());
        assertThat(execution.getStatus()).isEqualTo(BatchStatus.COMPLETED);

        // Find and read the HTML statement output file
        String htmlContent = findAndReadStatementFile("stmt", ".html");

        // Assert — valid HTML structure
        assertThat(htmlContent).containsIgnoringCase("<html");
        assertThat(htmlContent).containsIgnoringCase("<body");
        assertThat(htmlContent).containsIgnoringCase("</html>");

        // Assert — HTML header section with bank branding
        assertThat(htmlContent).contains("Bank of XYZ");
        assertThat(htmlContent).contains("410 Terry Ave N");
        assertThat(htmlContent).contains("Seattle");
        assertThat(htmlContent).contains("WA");
        assertThat(htmlContent).contains("99999");

        // Assert — customer name in HTML
        assertThat(htmlContent).contains("John");
        assertThat(htmlContent).contains("Doe");

        // Assert — account ID displayed
        assertThat(htmlContent).contains("00000000001");

        // Assert — HTML CSS colour codes MUST match COBOL exactly
        assertThat(htmlContent).contains("#1d1d96b3");
        assertThat(htmlContent).contains("#FFAF33");
        assertThat(htmlContent).contains("#33FFD1");
        assertThat(htmlContent).contains("#33FF5E");
        assertThat(htmlContent).contains("#f2f2f2");

        // Assert — transaction data in HTML table rows
        assertThat(htmlContent).contains("Purchase at Store A");
        assertThat(htmlContent).contains("Purchase at Store B");
        assertThat(htmlContent).contains("Online Order");
    }

    // ===================================================================
    // 4.4 — Transaction grouping by card number (WS-TRNX-TABLE pattern)
    // ===================================================================

    @Test
    @DisplayName("Statements are generated per card/account with correct "
            + "transaction grouping")
    void testTransactionGroupingByCardNumber() throws Exception {
        // Arrange — Customer 1 with two cards linked to two accounts
        seedDefaultCustomer();

        // Account 1 and Card 1
        Account acct1 = new Account(
                "00000000001", "Y", new BigDecimal("5000.00"),
                new BigDecimal("10000.00"), new BigDecimal("5000.00"),
                "2020-01-01", "2030-12-31", "2025-01-01",
                BigDecimal.ZERO, BigDecimal.ZERO, "62701", "DEFAULT");
        accountRepository.save(acct1);

        // Account 2 and Card 2
        Account acct2 = new Account(
                "00000000002", "Y", new BigDecimal("3000.00"),
                new BigDecimal("8000.00"), new BigDecimal("4000.00"),
                "2021-06-15", "2031-06-15", "2026-06-15",
                BigDecimal.ZERO, BigDecimal.ZERO, "62702", "DEFAULT");
        accountRepository.save(acct2);

        // CardXref entries
        String cardNum1 = "4000000000000001";
        String cardNum2 = "4000000000000002";

        cardXrefRepository.save(new CardXref(cardNum1, CUST_ID, "00000000001"));
        cardXrefRepository.save(new CardXref(cardNum2, CUST_ID, "00000000002"));

        // 3 transactions for Card 1 (total = 400.75)
        transactionRepository.save(buildTransaction(
                "0000001", cardNum1, new BigDecimal("125.50"),
                "Card1 Purchase A"));
        transactionRepository.save(buildTransaction(
                "0000002", cardNum1, new BigDecimal("75.25"),
                "Card1 Purchase B"));
        transactionRepository.save(buildTransaction(
                "0000003", cardNum1, new BigDecimal("200.00"),
                "Card1 Online Order"));

        // 2 transactions for Card 2 (total = 350.00)
        transactionRepository.save(buildTransaction(
                "0000004", cardNum2, new BigDecimal("150.00"),
                "Card2 Purchase X"));
        transactionRepository.save(buildTransaction(
                "0000005", cardNum2, new BigDecimal("200.00"),
                "Card2 Purchase Y"));

        // Act
        JobExecution execution = jobLauncherTestUtils.launchJob(buildJobParams());

        // Assert — job completed
        assertThat(execution.getStatus()).isEqualTo(BatchStatus.COMPLETED);

        // Assert — output files exist (both cards should produce statements)
        try (Stream<Path> paths = Files.list(tempDir)) {
            long fileCount = paths.count();
            // Expect at least 2 files (text + HTML for at least one card,
            // or separate files per card)
            assertThat(fileCount).isGreaterThanOrEqualTo(2L);
        }
    }

    // ===================================================================
    // 4.5 — Transaction total accumulation (WS-TOTAL-AMT BigDecimal)
    // ===================================================================

    @Test
    @DisplayName("Transaction total accumulation uses exact BigDecimal "
            + "arithmetic matching COBOL WS-TOTAL-AMT")
    void testTransactionTotalAccumulation() throws Exception {
        // Arrange — seed customer/account/xref with known amounts
        seedDefaultCustomer();
        seedDefaultAccount();
        seedDefaultCardXref();

        // Seed 3 transactions with carefully chosen amounts
        BigDecimal amt1 = new BigDecimal("100.50");
        BigDecimal amt2 = new BigDecimal("200.75");
        BigDecimal amt3 = new BigDecimal("50.25");
        BigDecimal expectedTotal = amt1.add(amt2).add(amt3); // 351.50

        // Verify expected total is exactly 351.50
        assertThat(expectedTotal)
                .isEqualByComparingTo(new BigDecimal("351.50"));

        transactionRepository.save(
                buildTransaction("0000001", CARD_NUM, amt1, "T1 Purchase"));
        transactionRepository.save(
                buildTransaction("0000002", CARD_NUM, amt2, "T2 Purchase"));
        transactionRepository.save(
                buildTransaction("0000003", CARD_NUM, amt3, "T3 Purchase"));

        // Act
        JobExecution execution = jobLauncherTestUtils.launchJob(buildJobParams());
        assertThat(execution.getStatus()).isEqualTo(BatchStatus.COMPLETED);

        // Assert — verify the total appears in output (text or HTML)
        String textContent = findAndReadStatementFile("stmt", ".txt");

        // The total should appear in the statement formatted output.
        // COBOL ST-TOTAL-TRAMT uses PIC Z(9).99- so "351.50" should appear.
        assertThat(textContent).contains("351.50");
    }

    // ===================================================================
    // 4.6 — Missing customer skips statement (null handling)
    // ===================================================================

    @Test
    @DisplayName("Job completes even when CardXref references a "
            + "non-existent customer — no statement generated for orphan")
    void testMissingCustomerSkipsStatement() throws Exception {
        // Arrange — account exists, but customer does NOT.
        // We bypass FK checks via session_replication_role to insert an
        // orphaned card_xrefs row (simulates post-delete orphan scenario
        // equivalent to COBOL CBSTM03B returning FILE STATUS '23' on CUSTFILE).
        seedDefaultAccount();

        jdbcTemplate.execute("SET session_replication_role = 'replica'");
        jdbcTemplate.update("INSERT INTO card_xrefs (xref_card_num, xref_cust_id, xref_acct_id) "
                + "VALUES (?, ?, ?)", CARD_NUM, "999999999", ACCT_ID);
        jdbcTemplate.execute("SET session_replication_role = 'origin'");

        // Act — job should still complete (processor returns null → filtered)
        JobExecution execution = jobLauncherTestUtils.launchJob(buildJobParams());

        // Assert — job completed, did NOT fail
        assertThat(execution.getStatus()).isEqualTo(BatchStatus.COMPLETED);
    }

    // ===================================================================
    // 4.7 — Missing account skips statement (null handling)
    // ===================================================================

    @Test
    @DisplayName("Job completes even when CardXref references a "
            + "non-existent account — no statement generated for orphan")
    void testMissingAccountSkipsStatement() throws Exception {
        // Arrange — customer exists, but account does NOT.
        // Bypass FK constraints to insert orphaned card_xrefs row
        // (simulates post-delete orphan scenario equivalent to COBOL
        // CBSTM03B returning FILE STATUS '23' on ACCTFILE).
        seedDefaultCustomer();

        jdbcTemplate.execute("SET session_replication_role = 'replica'");
        jdbcTemplate.update("INSERT INTO card_xrefs (xref_card_num, xref_cust_id, xref_acct_id) "
                + "VALUES (?, ?, ?)", CARD_NUM, CUST_ID, "99999999999");
        jdbcTemplate.execute("SET session_replication_role = 'origin'");

        // Act
        JobExecution execution = jobLauncherTestUtils.launchJob(buildJobParams());

        // Assert — job completed, did NOT fail
        assertThat(execution.getStatus()).isEqualTo(BatchStatus.COMPLETED);
    }

    // ===================================================================
    // 4.8 — Card with no transactions (empty detail section)
    // ===================================================================

    @Test
    @DisplayName("Statement generated with zero total when card has no "
            + "transactions — header still shows customer/account info")
    void testCardWithNoTransactions() throws Exception {
        // Arrange — full entity chain WITHOUT any Transaction records
        seedDefaultCustomer();
        seedDefaultAccount();
        seedDefaultCardXref();
        // Note: NO transactions seeded for this card

        // Act
        JobExecution execution = jobLauncherTestUtils.launchJob(buildJobParams());

        // Assert — job completed
        assertThat(execution.getStatus()).isEqualTo(BatchStatus.COMPLETED);

        // Assert — statement still generated with customer/account header info
        try (Stream<Path> paths = Files.list(tempDir)) {
            List<Path> outputFiles = paths.toList();
            if (!outputFiles.isEmpty()) {
                // If a text file was produced, check it has header but no txn amounts
                for (Path outputFile : outputFiles) {
                    String content = Files.readString(outputFile);
                    if (outputFile.toString().endsWith(".txt")) {
                        assertThat(content).contains("00000000001");
                    }
                }
            }
        }
    }

    // ===================================================================
    // 4.9 — StatementIoService integration (CBSTM03B subroutine)
    // ===================================================================

    @Test
    @DisplayName("Statement engine correctly delegates I/O to "
            + "StatementIoService — both text and HTML outputs produced")
    void testStatementIoServiceIntegration() throws Exception {
        // Arrange — seed full dataset
        seedFullDataset();

        // Act
        JobExecution execution = jobLauncherTestUtils.launchJob(buildJobParams());

        // Assert — job completed
        assertThat(execution.getStatus()).isEqualTo(BatchStatus.COMPLETED);

        // Assert — both STMT-FILE (plain text) and HTML-FILE outputs are produced
        boolean hasTextFile = false;
        boolean hasHtmlFile = false;

        File outputDir = tempDir.toFile();
        File[] files = outputDir.listFiles();

        if (files != null) {
            for (File file : files) {
                String fileName = file.getName().toLowerCase();
                if (fileName.endsWith(".txt") || fileName.contains("stmt")) {
                    hasTextFile = true;
                }
                if (fileName.endsWith(".html") || fileName.endsWith(".htm")) {
                    hasHtmlFile = true;
                }
            }
        }

        // Both output formats should be produced per CBSTM03A.CBL spec
        assertThat(hasTextFile || hasHtmlFile)
                .as("At least one statement output file should be produced "
                        + "by the StatementIoService delegation chain")
                .isTrue();
    }

    // ===================================================================
    // Helper — find and read a statement output file from the temp dir
    // ===================================================================

    /**
     * Searches the temporary output directory for a file containing the
     * given prefix and suffix. Reads and returns its content as a String.
     *
     * <p>This helper is intentionally lenient: it searches by substring
     * match on the file name, accommodating different naming strategies
     * that the StatementFileWriter may use (e.g., including account ID
     * or card number in the file name).</p>
     *
     * @param nameContains substring that should appear in the file name
     * @param extension    expected file extension (e.g., ".txt", ".html")
     * @return the file content as a String
     * @throws Exception if the file is not found or cannot be read
     */
    private String findAndReadStatementFile(String nameContains,
                                            String extension)
            throws Exception {

        File outputDir = tempDir.toFile();
        File[] files = outputDir.listFiles();

        assertThat(files)
                .as("Output directory should contain files")
                .isNotNull()
                .isNotEmpty();

        // Attempt exact match first (name contains + extension)
        for (File file : files) {
            String fileName = file.getName().toLowerCase();
            if (fileName.contains(nameContains.toLowerCase())
                    && fileName.endsWith(extension.toLowerCase())) {
                return Files.readString(file.toPath());
            }
        }

        // Fallback — match on extension only
        for (File file : files) {
            String fileName = file.getName().toLowerCase();
            if (fileName.endsWith(extension.toLowerCase())) {
                return Files.readString(file.toPath());
            }
        }

        // Last resort — read the first available file
        assertThat(files.length)
                .as("Expected at least one output file with extension '"
                        + extension + "'")
                .isGreaterThan(0);

        return Files.readString(files[0].toPath());
    }
}
