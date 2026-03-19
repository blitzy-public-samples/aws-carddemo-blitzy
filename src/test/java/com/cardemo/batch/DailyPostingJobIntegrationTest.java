package com.cardemo.batch;

import com.cardemo.entity.Account;
import com.cardemo.entity.CardXref;
import com.cardemo.entity.CategoryBalance;
import com.cardemo.entity.Transaction;
import com.cardemo.repository.AccountRepository;
import com.cardemo.repository.CardXrefRepository;
import com.cardemo.repository.CategoryBalanceRepository;
import com.cardemo.repository.DailyTransactionRepository;
import com.cardemo.repository.TransactionRepository;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.JobExecution;
import org.springframework.batch.core.JobParametersBuilder;
import org.springframework.batch.test.JobLauncherTestUtils;
import org.springframework.batch.test.JobRepositoryTestUtils;
import org.springframework.batch.test.context.SpringBatchTest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import org.springframework.jdbc.core.JdbcTemplate;

import java.io.BufferedWriter;
import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration test for the daily transaction posting Spring Batch job.
 *
 * <p>Tests the complete JCL POSTTRAN → Spring Batch migration pipeline:
 * Reader(DALYTRAN fixed-width file) → Processor(validate reject codes 100-103)
 * → Writer(TRANSACT table + DALYREJS reject file).</p>
 *
 * <p>Business logic faithfully mirrors CBTRN02C.cbl paragraphs:
 * <ul>
 *   <li>1500-A-LOOKUP-XREF — card number validation (reject 100)</li>
 *   <li>1500-B-LOOKUP-ACCT — account lookup (reject 101), credit limit (reject 102),
 *       expiration (reject 103)</li>
 *   <li>2700-UPDATE-TCATBAL — category balance create/update</li>
 *   <li>2800-UPDATE-ACCOUNT-REC — account balance, cycle credit/debit updates</li>
 * </ul></p>
 *
 * <p>Uses Testcontainers PostgreSQL 16-alpine for a real database backend
 * and writes test-specific fixed-width input files matching CVTRA06Y.cpy
 * (350-byte DALYTRAN-RECORD layout with zoned decimal overpunch encoding).</p>
 */
@SpringBatchTest
@SpringBootTest
@ActiveProfiles("test")
@Testcontainers
class DailyPostingJobIntegrationTest {

    /** Testcontainers PostgreSQL 16+ container shared across all test methods. */
    @Container
    static PostgreSQLContainer postgres =
            new PostgreSQLContainer("postgres:16-alpine");

    /**
     * Temporary fixed-width input file written before each test. The path is
     * registered as the {@code cardemo.batch.daily-transaction-file} property
     * so the DailyPostingJobConfig reader resolves to this file.
     */
    private static final Path TEMP_INPUT_FILE;

    static {
        try {
            TEMP_INPUT_FILE = Files.createTempFile("blitzy-dailytran-", ".txt");
            TEMP_INPUT_FILE.toFile().deleteOnExit();
        } catch (IOException e) {
            throw new ExceptionInInitializerError(
                    "Failed to create temp input file for daily posting test: " + e.getMessage());
        }
    }

    /**
     * Injects Testcontainers JDBC connection details and the path to the
     * temporary daily transaction input file into the Spring application context.
     */
    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
        registry.add("cardemo.batch.daily-transaction-file",
                () -> "file:" + TEMP_INPUT_FILE.toAbsolutePath());
    }

    // --- Spring Batch test infrastructure ---
    @Autowired
    private JobLauncherTestUtils jobLauncherTestUtils;

    @Autowired
    private JobRepositoryTestUtils jobRepositoryTestUtils;

    @Autowired
    @Qualifier("dailyPostingJob")
    private Job dailyPostingJob;

    // --- JPA repositories for seed data and assertions ---
    @Autowired
    private TransactionRepository transactionRepository;

    @Autowired
    private AccountRepository accountRepository;

    @Autowired
    private CardXrefRepository cardXrefRepository;

    @Autowired
    private DailyTransactionRepository dailyTransactionRepository;

    @Autowired
    private CategoryBalanceRepository categoryBalanceRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    /**
     * Cleans Spring Batch metadata and all data tables before each test,
     * ensuring complete isolation. Uses TRUNCATE CASCADE to handle FK
     * constraints from related tables (e.g. cards → accounts).
     */
    @BeforeEach
    void setUp() {
        jobRepositoryTestUtils.removeJobExecutions();
        jobLauncherTestUtils.setJob(dailyPostingJob);

        // Use TRUNCATE CASCADE to safely clean all domain tables regardless
        // of FK constraints from seed data in related tables (cards, customers, etc.)
        jdbcTemplate.execute("TRUNCATE TABLE transactions, category_balances, "
                + "daily_transactions, card_xrefs, cards, accounts, customers CASCADE");

        // Seed minimal customer records required by card_xrefs FK constraint.
        // Only cust_id is NOT NULL; all other columns are nullable.
        jdbcTemplate.execute("INSERT INTO customers (cust_id) VALUES "
                + "('000000001'), ('000000002'), ('000000003'), ('000000004'), "
                + "('000000005')");
    }

    // =========================================================================
    // Test 1: Happy Path — Valid transaction posted successfully
    // =========================================================================

    @Test
    @DisplayName("Daily posting job completes successfully with valid transactions")
    void testDailyPostingJobCompletesSuccessfully() throws Exception {
        // Seed reference data: valid account with sufficient credit limit
        Account acct = buildAccount("00000000001", "Y", "500.00", "10000.00",
                "2030-12-31", "0.00", "0.00");
        accountRepository.save(acct);
        cardXrefRepository.save(
                new CardXref("4000000000000001", "000000001", "00000000001"));

        // Write one valid daily transaction to the input file
        writeInputFile(List.of(
                buildInputRecord("TRAN000000000001", "01", "0001", "POS TERM",
                        "GROCERY STORE PURCHASE", new BigDecimal("50.00"),
                        "000000001", "WALMART", "ARLINGTON", "22201",
                        "4000000000000001", "2025-03-15-10.30.00.000000")
        ));

        // Execute the daily posting job
        JobExecution execution = jobLauncherTestUtils.launchJob(
                new JobParametersBuilder().addLong("run.id", System.currentTimeMillis()).toJobParameters());

        // Assert job completed successfully
        assertThat(execution.getStatus()).isEqualTo(BatchStatus.COMPLETED);

        // Verify transaction was posted to the Transaction table
        List<Transaction> txns = transactionRepository.findAll();
        assertThat(txns).hasSize(1);

        // Verify complete field mapping: DailyTransaction → Transaction
        Transaction posted = txns.get(0);
        assertThat(posted.getTranId()).isEqualTo("TRAN000000000001");
        assertThat(posted.getTypeCode()).isEqualTo("01");
        assertThat(posted.getCategoryCode()).isEqualTo(1);
        assertThat(posted.getSource()).isEqualTo("POS TERM");
        assertThat(posted.getDescription()).isEqualTo("GROCERY STORE PURCHASE");
        assertThat(posted.getAmount())
                .isEqualByComparingTo(new BigDecimal("50.00"));
        assertThat(posted.getMerchantId()).isEqualTo("000000001");
        assertThat(posted.getMerchantName()).isEqualTo("WALMART");
        assertThat(posted.getMerchantCity()).isEqualTo("ARLINGTON");
        assertThat(posted.getMerchantZip()).isEqualTo("22201");
        assertThat(posted.getCardNum()).isEqualTo("4000000000000001");
        assertThat(posted.getOrigTimestamp())
                .isEqualTo("2025-03-15-10.30.00.000000");

        // Verify procTimestamp follows DB2 format: YYYY-MM-DD-HH.MM.SS.mmmmmm
        assertThat(posted.getProcTimestamp()).isNotNull();
        assertThat(posted.getProcTimestamp()).hasSize(26);

        // Verify account balance updated: 500.00 + 50.00 = 550.00
        Account updated = accountRepository.findById("00000000001").orElseThrow();
        assertThat(updated.getAcctId()).isEqualTo("00000000001");
        assertThat(updated.getCreditLimit())
                .isEqualByComparingTo(new BigDecimal("10000.00"));
        assertThat(updated.getExpirationDate()).isEqualTo("2030-12-31");
        assertThat(updated.getCurrBal())
                .isEqualByComparingTo(new BigDecimal("550.00"));
        assertThat(updated.getCurrCycCredit())
                .isEqualByComparingTo(new BigDecimal("50.00"));

        // Verify category balance created for composite key (acctId, type, cat)
        Optional<CategoryBalance> catBal = categoryBalanceRepository
                .findByAccountIdAndTypeCodeAndCategoryCode("00000000001", "01", 1);
        assertThat(catBal).isPresent();
        assertThat(catBal.get().getBalance())
                .isEqualByComparingTo(new BigDecimal("50.00"));
    }

    // =========================================================================
    // Test 2: Reject Code 100 — Card number not in XREF
    // Tests CBTRN02C.cbl paragraph 1500-A-LOOKUP-XREF (lines 380-391)
    // =========================================================================

    @Test
    @DisplayName("Reject code 100: Invalid card number not found in XREF")
    void testRejectCode100_InvalidCardNumber() throws Exception {
        // Seed account but NO CardXref for card "9999999999999999"
        Account acct = buildAccount("00000000001", "Y", "500.00", "10000.00",
                "2030-12-31", "0.00", "0.00");
        accountRepository.save(acct);

        // Write transaction with a card number not present in XREF table
        writeInputFile(List.of(
                buildInputRecord("TRAN000000000002", "01", "0001", "POS TERM",
                        "INVALID CARD PURCHASE", new BigDecimal("25.00"),
                        "000000002", "TARGET", "DALLAS", "75201",
                        "9999999999999999", "2025-03-15-10.30.00.000000")
        ));

        JobExecution execution = jobLauncherTestUtils.launchJob(
                new JobParametersBuilder().addLong("run.id", System.currentTimeMillis()).toJobParameters());

        // Job should complete — COBOL sets RETURN-CODE=4 for rejects
        assertThat(execution.getStatus()).isEqualTo(BatchStatus.COMPLETED);

        // Verify no transaction posted for the rejected record
        assertThat(transactionRepository.count()).isEqualTo(0);

        // Account balance should remain unchanged
        Account unchanged = accountRepository.findById("00000000001").orElseThrow();
        assertThat(unchanged.getCurrBal())
                .isEqualByComparingTo(new BigDecimal("500.00"));
    }

    // =========================================================================
    // Test 3: Reject Code 101 — Account not found for XREF account ID
    // Tests CBTRN02C.cbl paragraph 1500-B-LOOKUP-ACCT (lines 393-399)
    // =========================================================================

    @Test
    @DisplayName("Reject code 101: Account record not found for XREF account ID")
    void testRejectCode101_AccountNotFound() throws Exception {
        // CardXref points to non-existent account "99999999999".
        // Must use raw SQL with FK checks disabled because the FK on xref_acct_id
        // references accounts(acct_id), but the whole point of reject 101 is that
        // the account does NOT exist.
        insertCardXrefBypassFk("4000000000000002", "000000002", "99999999999");

        writeInputFile(List.of(
                buildInputRecord("TRAN000000000003", "01", "0001", "POS TERM",
                        "MISSING ACCT PURCHASE", new BigDecimal("30.00"),
                        "000000003", "COSTCO", "SEATTLE", "98101",
                        "4000000000000002", "2025-03-15-10.30.00.000000")
        ));

        JobExecution execution = jobLauncherTestUtils.launchJob(
                new JobParametersBuilder().addLong("run.id", System.currentTimeMillis()).toJobParameters());

        assertThat(execution.getStatus()).isEqualTo(BatchStatus.COMPLETED);
        assertThat(transactionRepository.count()).isEqualTo(0);
    }

    // =========================================================================
    // Test 4: Reject Code 102 — Credit limit exceeded (overlimit)
    // Tests CBTRN02C.cbl paragraph 1500-B-LOOKUP-ACCT (lines 403-413)
    // Formula: WS-TEMP-BAL = CURR-CYC-CREDIT - CURR-CYC-DEBIT + DALYTRAN-AMT
    // Reject if CREDIT-LIMIT < WS-TEMP-BAL
    // =========================================================================

    @Test
    @DisplayName("Reject code 102: Credit limit exceeded (overlimit transaction)")
    void testRejectCode102_CreditLimitExceeded() throws Exception {
        // Account with low credit limit: limit=100, cyc_credit=80, cyc_debit=0
        // tempBal = 80 - 0 + 25 = 105 > 100 → REJECT 102
        Account acct = buildAccount("00000000003", "Y", "80.00", "100.00",
                "2030-12-31", "80.00", "0.00");
        accountRepository.save(acct);
        cardXrefRepository.save(
                new CardXref("4000000000000003", "000000003", "00000000003"));

        writeInputFile(List.of(
                buildInputRecord("TRAN000000000004", "01", "0001", "POS TERM",
                        "OVERLIMIT PURCHASE", new BigDecimal("25.00"),
                        "000000004", "BESTBUY", "HOUSTON", "77001",
                        "4000000000000003", "2025-03-15-10.30.00.000000")
        ));

        JobExecution execution = jobLauncherTestUtils.launchJob(
                new JobParametersBuilder().addLong("run.id", System.currentTimeMillis()).toJobParameters());

        assertThat(execution.getStatus()).isEqualTo(BatchStatus.COMPLETED);
        assertThat(transactionRepository.count()).isEqualTo(0);

        // Verify account balance unchanged
        Account unchanged = accountRepository.findById("00000000003").orElseThrow();
        assertThat(unchanged.getCurrBal())
                .isEqualByComparingTo(new BigDecimal("80.00"));
        assertThat(unchanged.getCurrCycCredit())
                .isEqualByComparingTo(new BigDecimal("80.00"));
        assertThat(unchanged.getCurrCycDebit())
                .isEqualByComparingTo(new BigDecimal("0.00"));
    }

    // =========================================================================
    // Test 5: Reject Code 103 — Account expired
    // Tests CBTRN02C.cbl paragraph 1500-B-LOOKUP-ACCT (lines 414-420)
    // Compares first 10 chars of DALYTRAN-ORIG-TS to ACCT-EXPIRAION-DATE
    // =========================================================================

    @Test
    @DisplayName("Reject code 103: Transaction received after account expiration")
    void testRejectCode103_AccountExpired() throws Exception {
        // Account expired 2020-01-01; transaction dated 2022-06-10
        Account acct = buildAccount("00000000004", "Y", "500.00", "10000.00",
                "2020-01-01", "0.00", "0.00");
        accountRepository.save(acct);
        cardXrefRepository.save(
                new CardXref("4000000000000004", "000000004", "00000000004"));

        writeInputFile(List.of(
                buildInputRecord("TRAN000000000005", "01", "0001", "POS TERM",
                        "EXPIRED ACCT PURCHASE", new BigDecimal("40.00"),
                        "000000005", "AMAZON", "SEATTLE", "98101",
                        "4000000000000004", "2022-06-10-19.27.53.000000")
        ));

        JobExecution execution = jobLauncherTestUtils.launchJob(
                new JobParametersBuilder().addLong("run.id", System.currentTimeMillis()).toJobParameters());

        assertThat(execution.getStatus()).isEqualTo(BatchStatus.COMPLETED);
        assertThat(transactionRepository.count()).isEqualTo(0);

        // Verify account balance unchanged
        Account unchanged = accountRepository.findById("00000000004").orElseThrow();
        assertThat(unchanged.getCurrBal())
                .isEqualByComparingTo(new BigDecimal("500.00"));
    }

    // =========================================================================
    // Test 6: Category Balance — Create and Update (TCATBAL pattern)
    // Tests CBTRN02C.cbl paragraphs 2700-UPDATE-TCATBAL,
    //   2700-A-CREATE-TCATBAL-REC, 2700-B-UPDATE-TCATBAL-REC (lines 467-542)
    // Composite key: (accountId, typeCode, categoryCode)
    // =========================================================================

    @Test
    @DisplayName("Category balance created on first posting and updated on subsequent same-key posting")
    void testCategoryBalanceCreateOrUpdate() throws Exception {
        Account acct = buildAccount("00000000001", "Y", "1000.00", "10000.00",
                "2030-12-31", "0.00", "0.00");
        accountRepository.save(acct);
        cardXrefRepository.save(
                new CardXref("4000000000000001", "000000001", "00000000001"));

        // Two transactions with the same composite key (acctId, type=01, cat=0001)
        writeInputFile(List.of(
                buildInputRecord("TRAN000000000006", "01", "0001", "POS TERM",
                        "FIRST PURCHASE", new BigDecimal("50.00"),
                        "000000006", "STORE A", "CITY A", "10001",
                        "4000000000000001", "2025-03-15-10.30.00.000000"),
                buildInputRecord("TRAN000000000007", "01", "0001", "POS TERM",
                        "SECOND PURCHASE", new BigDecimal("30.00"),
                        "000000007", "STORE B", "CITY B", "10002",
                        "4000000000000001", "2025-03-15-11.30.00.000000")
        ));

        JobExecution execution = jobLauncherTestUtils.launchJob(
                new JobParametersBuilder().addLong("run.id", System.currentTimeMillis()).toJobParameters());

        assertThat(execution.getStatus()).isEqualTo(BatchStatus.COMPLETED);
        assertThat(transactionRepository.count()).isEqualTo(2);

        // Category balance should be accumulated: 50.00 + 30.00 = 80.00
        Optional<CategoryBalance> catBal = categoryBalanceRepository
                .findByAccountIdAndTypeCodeAndCategoryCode("00000000001", "01", 1);
        assertThat(catBal).isPresent();
        assertThat(catBal.get().getBalance())
                .isEqualByComparingTo(new BigDecimal("80.00"));
        assertThat(catBal.get().getAccountId()).isEqualTo("00000000001");
        assertThat(catBal.get().getTypeCode()).isEqualTo("01");
        assertThat(catBal.get().getCategoryCode()).isEqualTo(1);
    }

    // =========================================================================
    // Test 7: Account Balance — Credit and Debit Updates
    // Tests CBTRN02C.cbl paragraph 2800-UPDATE-ACCOUNT-REC (lines 545-560)
    // CURR-BAL += DALYTRAN-AMT for all; CYC-CREDIT for positive, CYC-DEBIT for negative
    // =========================================================================

    @Test
    @DisplayName("Account balance correctly updated for both credit and debit transactions")
    void testAccountBalanceUpdate_CreditAndDebit() throws Exception {
        Account acct = buildAccount("00000000001", "Y", "500.00", "10000.00",
                "2030-12-31", "0.00", "0.00");
        accountRepository.save(acct);
        cardXrefRepository.save(
                new CardXref("4000000000000001", "000000001", "00000000001"));

        // Credit (positive amount) and debit (negative amount) transactions
        writeInputFile(List.of(
                buildInputRecord("TRAN000000000008", "01", "0001", "POS TERM",
                        "CREDIT TRANSACTION", new BigDecimal("100.00"),
                        "000000008", "STORE C", "CITY C", "10003",
                        "4000000000000001", "2025-03-15-10.30.00.000000"),
                buildInputRecord("TRAN000000000009", "02", "0002", "POS TERM",
                        "DEBIT TRANSACTION", new BigDecimal("-30.00"),
                        "000000009", "STORE D", "CITY D", "10004",
                        "4000000000000001", "2025-03-15-11.30.00.000000")
        ));

        JobExecution execution = jobLauncherTestUtils.launchJob(
                new JobParametersBuilder().addLong("run.id", System.currentTimeMillis()).toJobParameters());

        assertThat(execution.getStatus()).isEqualTo(BatchStatus.COMPLETED);
        assertThat(transactionRepository.count()).isEqualTo(2);

        // Verify account balance: 500 + 100 + (-30) = 570
        Account updated = accountRepository.findById("00000000001").orElseThrow();
        assertThat(updated.getCurrBal())
                .isEqualByComparingTo(new BigDecimal("570.00"));

        // Verify cycle credits: only the +100 credit
        assertThat(updated.getCurrCycCredit())
                .isEqualByComparingTo(new BigDecimal("100.00"));

        // Verify cycle debits: COBOL ADD DALYTRAN-AMT TO ACCT-CURR-CYC-DEBIT
        // Amount is -30.00, so CYC-DEBIT = 0 + (-30.00) = -30.00
        assertThat(updated.getCurrCycDebit())
                .isEqualByComparingTo(new BigDecimal("-30.00"));
    }

    // =========================================================================
    // Test 8: Mixed Valid and Invalid Transactions
    // Tests the overall CBTRN02C.cbl main loop (lines 202-219)
    // Verifies correct processing when batch contains both postable and
    // rejectable records in a single job execution.
    // =========================================================================

    @Test
    @DisplayName("Mixed valid and invalid transactions processed correctly in single batch")
    void testMixedValidAndInvalidTransactions() throws Exception {
        // Seed accounts: one valid with high limit, one with low limit for reject 102
        Account acct1 = buildAccount("00000000001", "Y", "500.00", "10000.00",
                "2030-12-31", "0.00", "0.00");
        Account acct3 = buildAccount("00000000003", "Y", "80.00", "100.00",
                "2030-12-31", "80.00", "0.00");
        accountRepository.saveAll(List.of(acct1, acct3));

        // Seed cross-references: valid, no-account, and overlimit
        cardXrefRepository.save(
                new CardXref("4000000000000001", "000000001", "00000000001"));
        // XREF with non-existent account — must bypass FK constraint
        insertCardXrefBypassFk("4000000000000002", "000000002", "99999999999");
        cardXrefRepository.save(
                new CardXref("4000000000000003", "000000003", "00000000003"));

        writeInputFile(List.of(
                // Valid transaction #1
                buildInputRecord("TRAN000000000010", "01", "0001", "POS TERM",
                        "VALID PURCHASE ONE", new BigDecimal("50.00"),
                        "000000010", "STORE E", "CITY E", "10005",
                        "4000000000000001", "2025-03-15-10.30.00.000000"),
                // Reject 100: card not in XREF
                buildInputRecord("TRAN000000000011", "01", "0001", "POS TERM",
                        "INVALID CARD TXN", new BigDecimal("25.00"),
                        "000000011", "STORE F", "CITY F", "10006",
                        "8888888888888888", "2025-03-15-10.30.00.000000"),
                // Reject 101: account not found
                buildInputRecord("TRAN000000000012", "01", "0001", "POS TERM",
                        "MISSING ACCT TXN", new BigDecimal("30.00"),
                        "000000012", "STORE G", "CITY G", "10007",
                        "4000000000000002", "2025-03-15-10.30.00.000000"),
                // Valid transaction #2
                buildInputRecord("TRAN000000000013", "01", "0001", "POS TERM",
                        "VALID PURCHASE TWO", new BigDecimal("75.00"),
                        "000000013", "STORE H", "CITY H", "10008",
                        "4000000000000001", "2025-03-15-11.30.00.000000"),
                // Reject 102: overlimit (tempBal = 80 - 0 + 25 = 105 > 100)
                buildInputRecord("TRAN000000000014", "01", "0001", "POS TERM",
                        "OVERLIMIT TXN", new BigDecimal("25.00"),
                        "000000014", "STORE I", "CITY I", "10009",
                        "4000000000000003", "2025-03-15-10.30.00.000000")
        ));

        JobExecution execution = jobLauncherTestUtils.launchJob(
                new JobParametersBuilder().addLong("run.id", System.currentTimeMillis()).toJobParameters());

        assertThat(execution.getStatus()).isEqualTo(BatchStatus.COMPLETED);

        // 2 valid transactions posted, 3 rejected
        assertThat(transactionRepository.count()).isEqualTo(2);

        // Verify valid transactions exist
        List<Transaction> posted = transactionRepository.findAll();
        assertThat(posted).hasSize(2);

        // Verify account 1 balance: 500 + 50 + 75 = 625
        Account updated1 = accountRepository.findById("00000000001").orElseThrow();
        assertThat(updated1.getCurrBal())
                .isEqualByComparingTo(new BigDecimal("625.00"));
        assertThat(updated1.getCurrCycCredit())
                .isEqualByComparingTo(new BigDecimal("125.00"));

        // Verify account 3 balance unchanged (reject 102)
        Account unchanged3 = accountRepository.findById("00000000003").orElseThrow();
        assertThat(unchanged3.getCurrBal())
                .isEqualByComparingTo(new BigDecimal("80.00"));
        assertThat(unchanged3.getCurrCycCredit())
                .isEqualByComparingTo(new BigDecimal("80.00"));

        // Category balance for the 2 valid txns on account 1
        Optional<CategoryBalance> catBal = categoryBalanceRepository
                .findByAccountIdAndTypeCodeAndCategoryCode("00000000001", "01", 1);
        assertThat(catBal).isPresent();
        assertThat(catBal.get().getBalance())
                .isEqualByComparingTo(new BigDecimal("125.00"));
    }

    // =========================================================================
    // Helper Methods
    // =========================================================================

    /**
     * Inserts a card_xref record using raw SQL with FK constraints temporarily
     * disabled. Required for reject code 101 scenarios where the XREF must
     * reference a non-existent account (which PostgreSQL FK would otherwise block).
     *
     * <p>Uses PostgreSQL {@code session_replication_role = replica} to disable
     * trigger-based FK enforcement for the insert, then immediately restores
     * the default role.</p>
     *
     * @param cardNum   16-char card number (PK)
     * @param custId    9-char customer ID
     * @param accountId 11-char account ID (may be invalid)
     */
    private void insertCardXrefBypassFk(String cardNum, String custId,
                                        String accountId) {
        jdbcTemplate.execute("SET session_replication_role = replica");
        jdbcTemplate.update(
                "INSERT INTO card_xrefs (xref_card_num, xref_cust_id, xref_acct_id) "
                        + "VALUES (?, ?, ?)",
                cardNum, custId, accountId);
        jdbcTemplate.execute("SET session_replication_role = DEFAULT");
    }

    /**
     * Builds an Account entity for test seeding.
     *
     * @param acctId         11-char account ID (PK)
     * @param activeStatus   "Y" or "N"
     * @param currBal        current balance as BigDecimal string
     * @param creditLimit    credit limit as BigDecimal string
     * @param expirationDate expiration date in YYYY-MM-DD format
     * @param currCycCredit  current cycle credit as BigDecimal string
     * @param currCycDebit   current cycle debit as BigDecimal string
     * @return populated Account entity ready for persistence
     */
    private Account buildAccount(String acctId, String activeStatus,
                                 String currBal, String creditLimit,
                                 String expirationDate,
                                 String currCycCredit, String currCycDebit) {
        return new Account(
                acctId,
                activeStatus,
                new BigDecimal(currBal),
                new BigDecimal(creditLimit),
                new BigDecimal("5000.00"),     // cashCreditLimit — not tested
                "2020-01-01",                  // openDate
                expirationDate,
                "2025-01-01",                  // reissueDate
                new BigDecimal(currCycCredit),
                new BigDecimal(currCycDebit),
                "10001",                       // addrZip
                "DEFAULT"                      // groupId
        );
    }

    /**
     * Writes a list of 350-byte fixed-width records to the temp input file.
     * Each record matches the CVTRA06Y.cpy DALYTRAN-RECORD layout with
     * zoned decimal overpunch encoding for the amount field.
     *
     * @param records list of pre-formatted 350-char fixed-width strings
     * @throws IOException if file writing fails
     */
    private void writeInputFile(List<String> records) throws IOException {
        try (BufferedWriter writer = Files.newBufferedWriter(TEMP_INPUT_FILE)) {
            for (String record : records) {
                writer.write(record);
                writer.newLine();
            }
        }
    }

    /**
     * Builds a single 350-byte fixed-width record matching the DALYTRAN
     * layout from CVTRA06Y.cpy.
     *
     * <p>Field layout (positions are 1-based):
     * <ul>
     *   <li>  1- 16: DALYTRAN-ID (16 chars)</li>
     *   <li> 17- 18: DALYTRAN-TYPE-CD (2 chars)</li>
     *   <li> 19- 22: DALYTRAN-CAT-CD (4 chars)</li>
     *   <li> 23- 32: DALYTRAN-SOURCE (10 chars)</li>
     *   <li> 33-132: DALYTRAN-DESC (100 chars)</li>
     *   <li>133-143: DALYTRAN-AMT (11 chars, zoned decimal PIC S9(09)V99)</li>
     *   <li>144-152: DALYTRAN-MERCHANT-ID (9 chars)</li>
     *   <li>153-202: DALYTRAN-MERCHANT-NAME (50 chars)</li>
     *   <li>203-252: DALYTRAN-MERCHANT-CITY (50 chars)</li>
     *   <li>253-262: DALYTRAN-MERCHANT-ZIP (10 chars)</li>
     *   <li>263-278: DALYTRAN-CARD-NUM (16 chars)</li>
     *   <li>279-304: DALYTRAN-ORIG-TS (26 chars)</li>
     *   <li>305-330: DALYTRAN-PROC-TS (26 chars)</li>
     *   <li>331-350: FILLER (20 chars)</li>
     * </ul></p>
     */
    private String buildInputRecord(String tranId, String typeCode,
                                    String catCode, String source,
                                    String description, BigDecimal amount,
                                    String merchantId, String merchantName,
                                    String merchantCity, String merchantZip,
                                    String cardNum, String origTimestamp) {
        StringBuilder sb = new StringBuilder(350);
        sb.append(padRight(tranId, 16));                    //   1- 16
        sb.append(padRight(typeCode, 2));                   //  17- 18
        sb.append(padRight(catCode, 4));                    //  19- 22
        sb.append(padRight(source, 10));                    //  23- 32
        sb.append(padRight(description, 100));              //  33-132
        sb.append(formatZonedDecimal(amount, 9, 2));        // 133-143 (11 chars)
        sb.append(padRight(merchantId, 9));                 // 144-152
        sb.append(padRight(merchantName, 50));              // 153-202
        sb.append(padRight(merchantCity, 50));              // 203-252
        sb.append(padRight(merchantZip, 10));               // 253-262
        sb.append(padRight(cardNum, 16));                   // 263-278
        sb.append(padRight(origTimestamp, 26));              // 279-304
        sb.append(padRight("", 26));                        // 305-330 PROC-TS (blank)
        sb.append(padRight("", 20));                        // 331-350 FILLER
        return sb.toString();
    }

    /**
     * Formats a BigDecimal value into zoned decimal with overpunch encoding
     * matching the COBOL PIC S9(intDigits)V9(fracDigits) representation.
     *
     * <p>Overpunch encoding (ASCII representation):
     * <ul>
     *   <li>Positive: {@code {=0, A=1, B=2, C=3, D=4, E=5, F=6, G=7, H=8, I=9}</li>
     *   <li>Negative: {@code }=0, J=1, K=2, L=3, M=4, N=5, O=6, P=7, Q=8, R=9}</li>
     * </ul></p>
     *
     * @param value     the decimal value to encode
     * @param intDigits number of integer digits (before implied decimal point)
     * @param fracDigits number of fractional digits (after implied decimal point)
     * @return zoned decimal string of length intDigits + fracDigits
     */
    private static String formatZonedDecimal(BigDecimal value,
                                             int intDigits, int fracDigits) {
        boolean negative = value.signum() < 0;
        BigDecimal abs = value.abs();
        long unscaledValue = abs.movePointRight(fracDigits)
                .setScale(0, RoundingMode.HALF_UP)
                .longValueExact();

        int totalDigits = intDigits + fracDigits;
        String digits = String.format("%0" + totalDigits + "d", unscaledValue);

        // Replace the last digit with its overpunch character
        int lastDigitVal = digits.charAt(totalDigits - 1) - '0';
        char overpunch;
        if (negative) {
            // Negative: } = 0, J = 1, K = 2, ..., R = 9
            overpunch = lastDigitVal == 0 ? '}' : (char) ('I' + lastDigitVal);
        } else {
            // Positive: { = 0, A = 1, B = 2, ..., I = 9
            overpunch = lastDigitVal == 0 ? '{' : (char) ('@' + lastDigitVal);
        }

        return digits.substring(0, totalDigits - 1) + overpunch;
    }

    /**
     * Right-pads or truncates a string to the exact specified length.
     *
     * @param value  the string value (null-safe)
     * @param length target length
     * @return string of exactly {@code length} characters
     */
    private static String padRight(String value, int length) {
        if (value == null) {
            value = "";
        }
        if (value.length() >= length) {
            return value.substring(0, length);
        }
        return value + " ".repeat(length - value.length());
    }

}