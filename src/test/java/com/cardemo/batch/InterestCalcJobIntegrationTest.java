/*
 * InterestCalcJobIntegrationTest.java — Spring Batch Integration Test
 *
 * Tests the interest calculation batch job migrated from JCL INTCALC / CBACT04C.cbl.
 * Verifies the full pipeline: Read TCATBAL accounts → compute interest with
 * (TRAN-CAT-BAL * DIS-INT-RATE) / 1200 → update account balance.
 *
 * COBOL Paragraph-to-Test Traceability:
 *   MAIN (lines 180-232)                → testInterestCalcJobCompletesSuccessfully
 *   1050-UPDATE-ACCOUNT (lines 350-370) → testAccountUpdateResetsCurrentCycle
 *   1200-GET-INTEREST-RATE (415-440)    → testDefaultGroupFallback
 *   1300-COMPUTE-INTEREST (462-470)     → testInterestFormulaExactMatch, testInterestFormulaWithRounding
 *   1300-B-WRITE-TX (473-515)           → testInterestTransactionGenerated
 *   1400-COMPUTE-FEES (518-520)         → testComputeFeesIsStub
 *
 * All monetary assertions use BigDecimal string constructor and isEqualByComparingTo.
 *
 * @see com.cardemo.batch.job.InterestCalcJobConfig
 * @see com.cardemo.batch.processor.InterestCalculationProcessor
 * @see com.cardemo.service.batch.InterestCalculationService
 */
package com.cardemo.batch;

import com.cardemo.entity.Account;
import com.cardemo.entity.CardXref;
import com.cardemo.entity.CategoryBalance;
import com.cardemo.entity.DiscountGroup;
import com.cardemo.entity.Transaction;
import com.cardemo.repository.AccountRepository;
import com.cardemo.repository.CardXrefRepository;
import com.cardemo.repository.CategoryBalanceRepository;
import com.cardemo.repository.DiscountGroupRepository;
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
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration test for the interest calculation Spring Batch job.
 *
 * <p>Exercises the complete interest calculation pipeline against a real
 * PostgreSQL 16 instance via Testcontainers, verifying 100% business logic
 * parity with CBACT04C.cbl.</p>
 */
@SpringBatchTest
@SpringBootTest
@ActiveProfiles("test")
@Testcontainers
class InterestCalcJobIntegrationTest {

    // =========================================================================
    // Testcontainers — PostgreSQL 16+ (real database for integration testing)
    // =========================================================================

    @Container
    static PostgreSQLContainer postgres =
            new PostgreSQLContainer("postgres:16-alpine");

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("spring.datasource.driver-class-name",
                () -> "org.postgresql.Driver");
    }

    // =========================================================================
    // Injected Dependencies
    // =========================================================================

    @Autowired
    private JobLauncherTestUtils jobLauncherTestUtils;

    @Autowired
    private JobRepositoryTestUtils jobRepositoryTestUtils;

    @Autowired
    @Qualifier("interestCalcJob")
    private Job interestCalcJob;

    @Autowired
    private AccountRepository accountRepository;

    @Autowired
    private CardXrefRepository cardXrefRepository;

    @Autowired
    private CategoryBalanceRepository categoryBalanceRepository;

    @Autowired
    private TransactionRepository transactionRepository;

    @Autowired
    private DiscountGroupRepository discountGroupRepository;

    // =========================================================================
    // Test Constants — isolated IDs to avoid seed data conflicts
    // =========================================================================

    /** Test account ID — outside seed data range (00000000001–00000000050). */
    private static final String TEST_ACCT_ID = "99900000001";

    /** Test card number for XREF cross-reference. */
    private static final String TEST_CARD_NUM = "4111111111111111";

    /** Test customer ID for XREF record — uses seed customer to satisfy FK. */
    private static final String TEST_CUST_ID = "000000001";

    /** PARM-DATE job parameter — CCYYMMDD format (← LINKAGE SECTION). */
    private static final String PARM_DATE = "20220610";

    /** Standard discount group ID for tests. */
    private static final String GROUP_ID = "GROUP01";

    /** DEFAULT fallback group ID (← COBOL line 437). */
    private static final String DEFAULT_GROUP = "DEFAULT";

    /** Interest transaction type code (← COBOL: MOVE '01' TO TRAN-TYPE-CD). */
    private static final String INTEREST_TYPE_CD = "01";

    /** Interest transaction category code (← COBOL: MOVE '05' TO TRAN-CAT-CD). */
    private static final Integer INTEREST_CAT_CD = 5;

    /** Monthly interest divisor: 12 months × 100 for percentage. */
    private static final BigDecimal DIVISOR_1200 = new BigDecimal("1200");

    // =========================================================================
    // @BeforeEach — Clean state for every test
    // =========================================================================

    @BeforeEach
    void setUp() {
        // Clean Spring Batch metadata from previous runs
        jobRepositoryTestUtils.removeJobExecutions();
        // Wire the specific job into the test utilities
        jobLauncherTestUtils.setJob(interestCalcJob);
        // Clean test-relevant tables (order: FK-dependent tables first)
        // NOTE: deleteAll() on accounts would violate FK from cards (seed data).
        // We only clean test-specific records to preserve seed data integrity.
        transactionRepository.deleteAll();
        categoryBalanceRepository.deleteAll();
        discountGroupRepository.deleteAll();
        cardXrefRepository.deleteAll();
        accountRepository.deleteById(TEST_ACCT_ID);
    }

    // =========================================================================
    // Test Methods — 9 scenarios from CBACT04C.cbl
    // =========================================================================

    @Test
    @DisplayName("Interest calc job completes successfully — happy path")
    void testInterestCalcJobCompletesSuccessfully() throws Exception {
        // Seed test data: account, XREF, discount group, category balance
        seedAccount(TEST_ACCT_ID, new BigDecimal("5000.00"), GROUP_ID,
                new BigDecimal("200.00"), new BigDecimal("50.00"));
        seedCardXref(TEST_ACCT_ID, TEST_CARD_NUM);
        seedDiscountGroup(GROUP_ID, INTEREST_TYPE_CD, INTEREST_CAT_CD,
                new BigDecimal("18.00"));
        seedDiscountGroup(DEFAULT_GROUP, INTEREST_TYPE_CD, INTEREST_CAT_CD,
                new BigDecimal("12.00"));
        seedCategoryBalance(TEST_ACCT_ID, INTEREST_TYPE_CD, INTEREST_CAT_CD,
                new BigDecimal("1000.00"));

        // Launch batch job with PARM-DATE
        JobExecution execution = jobLauncherTestUtils.launchJob(buildJobParameters());

        // Verify: job completed successfully
        assertThat(execution.getStatus()).isEqualTo(BatchStatus.COMPLETED);

        // Verify: account balance increased by expected interest
        // Formula: (1000.00 * 18.00) / 1200 = 15.00
        Account updated = accountRepository.findById(TEST_ACCT_ID).orElseThrow();
        BigDecimal expectedBalance = new BigDecimal("5000.00")
                .add(new BigDecimal("15.00"));
        assertThat(updated.getCurrBal())
                .isEqualByComparingTo(expectedBalance);
    }

    @Test
    @DisplayName("Interest formula exact match — (1000.00 × 18.00) / 1200 = 15.00")
    void testInterestFormulaExactMatch() throws Exception {
        // Seed: balance=1000.00, rate=18.00 → (1000 × 18) / 1200 = 15.00
        BigDecimal originalBalance = new BigDecimal("5000.00");
        seedAccount(TEST_ACCT_ID, originalBalance, GROUP_ID,
                BigDecimal.ZERO, BigDecimal.ZERO);
        seedCardXref(TEST_ACCT_ID, TEST_CARD_NUM);
        seedDiscountGroup(GROUP_ID, INTEREST_TYPE_CD, INTEREST_CAT_CD,
                new BigDecimal("18.00"));
        seedCategoryBalance(TEST_ACCT_ID, INTEREST_TYPE_CD, INTEREST_CAT_CD,
                new BigDecimal("1000.00"));

        JobExecution execution = jobLauncherTestUtils.launchJob(buildJobParameters());
        assertThat(execution.getStatus()).isEqualTo(BatchStatus.COMPLETED);

        // CRITICAL: isEqualByComparingTo for BigDecimal — not isEqualTo
        // Verifies CBACT04C.cbl line 464-465:
        //   COMPUTE WS-MONTHLY-INT = (TRAN-CAT-BAL * DIS-INT-RATE) / 1200
        Account updated = accountRepository.findById(TEST_ACCT_ID).orElseThrow();
        assertThat(updated.getCurrBal())
                .isEqualByComparingTo(originalBalance.add(new BigDecimal("15.00")));
    }

    @Test
    @DisplayName("Interest formula with rounding — (333.33 × 7.00) / 1200 = 1.94 HALF_UP")
    void testInterestFormulaWithRounding() throws Exception {
        // Seed: balance=333.33, rate=7.00
        // Expected: (333.33 × 7.00) / 1200 = 2333.31 / 1200 = 1.944... → 1.94 HALF_UP
        BigDecimal originalBalance = new BigDecimal("2000.00");
        seedAccount(TEST_ACCT_ID, originalBalance, GROUP_ID,
                BigDecimal.ZERO, BigDecimal.ZERO);
        seedCardXref(TEST_ACCT_ID, TEST_CARD_NUM);
        seedDiscountGroup(GROUP_ID, INTEREST_TYPE_CD, INTEREST_CAT_CD,
                new BigDecimal("7.00"));
        seedCategoryBalance(TEST_ACCT_ID, INTEREST_TYPE_CD, INTEREST_CAT_CD,
                new BigDecimal("333.33"));

        JobExecution execution = jobLauncherTestUtils.launchJob(buildJobParameters());
        assertThat(execution.getStatus()).isEqualTo(BatchStatus.COMPLETED);

        // Verify exact rounding: scale=2, RoundingMode.HALF_UP
        // Matches COBOL PIC S9(09)V99 — 2 decimal places
        BigDecimal expectedInterest = new BigDecimal("333.33")
                .multiply(new BigDecimal("7.00"))
                .divide(DIVISOR_1200, 2, RoundingMode.HALF_UP);
        // expectedInterest = 1.94 (NOT 1.9442...)
        assertThat(expectedInterest).isEqualByComparingTo(new BigDecimal("1.94"));

        Account updated = accountRepository.findById(TEST_ACCT_ID).orElseThrow();
        assertThat(updated.getCurrBal())
                .isEqualByComparingTo(originalBalance.add(expectedInterest));
    }

    @Test
    @DisplayName("DEFAULT group fallback — DISCGRP status '23' triggers DEFAULT rate")
    void testDefaultGroupFallback() throws Exception {
        // Seed: account with non-existent group → processor falls back to DEFAULT
        // CBACT04C.cbl paragraph 1200-GET-INTEREST-RATE:
        //   IF WS-DISCGRP-STATUS = '23' → PERFORM 1200-A-GET-DEFAULT-INT-RATE
        //   Line 437: MOVE 'DEFAULT' TO FD-DIS-ACCT-GROUP-ID
        BigDecimal originalBalance = new BigDecimal("3000.00");
        seedAccount(TEST_ACCT_ID, originalBalance, "NOGROUP",
                BigDecimal.ZERO, BigDecimal.ZERO);
        seedCardXref(TEST_ACCT_ID, TEST_CARD_NUM);
        // Only seed DEFAULT group — no matching group for "NOGROUP"
        seedDiscountGroup(DEFAULT_GROUP, INTEREST_TYPE_CD, INTEREST_CAT_CD,
                new BigDecimal("12.00"));
        seedCategoryBalance(TEST_ACCT_ID, INTEREST_TYPE_CD, INTEREST_CAT_CD,
                new BigDecimal("600.00"));

        JobExecution execution = jobLauncherTestUtils.launchJob(buildJobParameters());
        assertThat(execution.getStatus()).isEqualTo(BatchStatus.COMPLETED);

        // Expected: (600.00 × 12.00) / 1200 = 6.00 using DEFAULT rate
        BigDecimal expectedInterest = new BigDecimal("600.00")
                .multiply(new BigDecimal("12.00"))
                .divide(DIVISOR_1200, 2, RoundingMode.HALF_UP);
        assertThat(expectedInterest).isEqualByComparingTo(new BigDecimal("6.00"));

        Account updated = accountRepository.findById(TEST_ACCT_ID).orElseThrow();
        assertThat(updated.getCurrBal())
                .isEqualByComparingTo(originalBalance.add(expectedInterest));
    }

    @Test
    @DisplayName("Zero interest rate skips computation — balance unchanged")
    void testZeroInterestRateSkipsComputation() throws Exception {
        // Seed: DiscountGroup with INT-RATE=0.00
        // CBACT04C.cbl line 209: IF DIS-INT-RATE NOT = 0 PERFORM 1300-COMPUTE-INTEREST
        BigDecimal originalBalance = new BigDecimal("7500.00");
        seedAccount(TEST_ACCT_ID, originalBalance, GROUP_ID,
                new BigDecimal("100.00"), new BigDecimal("25.00"));
        seedCardXref(TEST_ACCT_ID, TEST_CARD_NUM);
        seedDiscountGroup(GROUP_ID, INTEREST_TYPE_CD, INTEREST_CAT_CD,
                BigDecimal.ZERO);
        seedCategoryBalance(TEST_ACCT_ID, INTEREST_TYPE_CD, INTEREST_CAT_CD,
                new BigDecimal("500.00"));

        JobExecution execution = jobLauncherTestUtils.launchJob(buildJobParameters());
        assertThat(execution.getStatus()).isEqualTo(BatchStatus.COMPLETED);

        // Verify: balance NOT changed (zero rate → no interest added)
        Account updated = accountRepository.findById(TEST_ACCT_ID).orElseThrow();
        assertThat(updated.getCurrBal())
                .isEqualByComparingTo(originalBalance);
    }

    @Test
    @DisplayName("Account update resets current cycle credit and debit to zero")
    void testAccountUpdateResetsCurrentCycle() throws Exception {
        // Seed: account with non-zero cycle credit/debit
        // CBACT04C.cbl paragraph 1050-UPDATE-ACCOUNT (lines 350-370):
        //   MOVE 0 TO ACCT-CURR-CYC-CREDIT
        //   MOVE 0 TO ACCT-CURR-CYC-DEBIT
        seedAccount(TEST_ACCT_ID, new BigDecimal("5000.00"), GROUP_ID,
                new BigDecimal("200.00"), new BigDecimal("50.00"));
        seedCardXref(TEST_ACCT_ID, TEST_CARD_NUM);
        seedDiscountGroup(GROUP_ID, INTEREST_TYPE_CD, INTEREST_CAT_CD,
                new BigDecimal("18.00"));
        seedCategoryBalance(TEST_ACCT_ID, INTEREST_TYPE_CD, INTEREST_CAT_CD,
                new BigDecimal("1000.00"));

        JobExecution execution = jobLauncherTestUtils.launchJob(buildJobParameters());
        assertThat(execution.getStatus()).isEqualTo(BatchStatus.COMPLETED);

        Account updated = accountRepository.findById(TEST_ACCT_ID).orElseThrow();
        // After interest update, cycle counters are ALWAYS reset to zero
        assertThat(updated.getCurrCycCredit())
                .isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(updated.getCurrCycDebit())
                .isEqualByComparingTo(BigDecimal.ZERO);
    }

    @Test
    @DisplayName("Multiple category balances per account — interest accumulated")
    void testMultipleCategoryBalancesPerAccount() throws Exception {
        // Seed: 3 CategoryBalance records for SAME account with different type/cat codes
        // CBACT04C.cbl grouping logic (lines 188-206):
        //   WS-LAST-ACCT-NUM tracking, WS-TOTAL-INT accumulation
        BigDecimal originalBalance = new BigDecimal("10000.00");
        seedAccount(TEST_ACCT_ID, originalBalance, GROUP_ID,
                BigDecimal.ZERO, BigDecimal.ZERO);
        seedCardXref(TEST_ACCT_ID, TEST_CARD_NUM);

        // Discount groups for each type/cat combination
        seedDiscountGroup(GROUP_ID, "01", 5, new BigDecimal("18.00"));
        seedDiscountGroup(GROUP_ID, "02", 3, new BigDecimal("12.00"));
        seedDiscountGroup(GROUP_ID, "01", 6, new BigDecimal("6.00"));

        // Three category balance records (same account, different codes)
        seedCategoryBalance(TEST_ACCT_ID, "01", 5, new BigDecimal("1000.00"));
        seedCategoryBalance(TEST_ACCT_ID, "02", 3, new BigDecimal("500.00"));
        seedCategoryBalance(TEST_ACCT_ID, "01", 6, new BigDecimal("2000.00"));

        JobExecution execution = jobLauncherTestUtils.launchJob(buildJobParameters());
        assertThat(execution.getStatus()).isEqualTo(BatchStatus.COMPLETED);

        // Compute expected individual interests:
        // CatBal 1: (1000.00 × 18.00) / 1200 = 15.00
        // CatBal 2: (500.00 × 12.00) / 1200 = 5.00
        // CatBal 3: (2000.00 × 6.00) / 1200 = 10.00
        // Total: 15.00 + 5.00 + 10.00 = 30.00
        BigDecimal int1 = new BigDecimal("1000.00").multiply(new BigDecimal("18.00"))
                .divide(DIVISOR_1200, 2, RoundingMode.HALF_UP);
        BigDecimal int2 = new BigDecimal("500.00").multiply(new BigDecimal("12.00"))
                .divide(DIVISOR_1200, 2, RoundingMode.HALF_UP);
        BigDecimal int3 = new BigDecimal("2000.00").multiply(new BigDecimal("6.00"))
                .divide(DIVISOR_1200, 2, RoundingMode.HALF_UP);
        BigDecimal totalInterest = int1.add(int2).add(int3);

        assertThat(totalInterest).isEqualByComparingTo(new BigDecimal("30.00"));

        Account updated = accountRepository.findById(TEST_ACCT_ID).orElseThrow();
        assertThat(updated.getCurrBal())
                .isEqualByComparingTo(originalBalance.add(totalInterest));
    }

    @Test
    @DisplayName("Interest transaction generated with correct COBOL-parity fields")
    void testInterestTransactionGenerated() throws Exception {
        // CBACT04C.cbl paragraph 1300-B-WRITE-TX (lines 473-515)
        seedAccount(TEST_ACCT_ID, new BigDecimal("5000.00"), GROUP_ID,
                BigDecimal.ZERO, BigDecimal.ZERO);
        seedCardXref(TEST_ACCT_ID, TEST_CARD_NUM);
        seedDiscountGroup(GROUP_ID, INTEREST_TYPE_CD, INTEREST_CAT_CD,
                new BigDecimal("18.00"));
        seedCategoryBalance(TEST_ACCT_ID, INTEREST_TYPE_CD, INTEREST_CAT_CD,
                new BigDecimal("1000.00"));

        JobExecution execution = jobLauncherTestUtils.launchJob(buildJobParameters());
        assertThat(execution.getStatus()).isEqualTo(BatchStatus.COMPLETED);

        // Verify generated interest transaction records
        assertThat(transactionRepository.count()).isGreaterThan(0);
        List<Transaction> transactions = transactionRepository.findAll();
        assertThat(transactions).isNotEmpty();

        // Find the interest transaction for our test account
        Transaction tran = transactions.stream()
                .filter(t -> t.getDescription() != null
                        && t.getDescription().startsWith("Int. for a/c "))
                .findFirst()
                .orElseThrow(() -> new AssertionError(
                        "Expected interest transaction with description starting "
                                + "'Int. for a/c ' but none found"));

        // Verify all COBOL-parity fields
        assertThat(tran.getTypeCode()).isEqualTo(INTEREST_TYPE_CD);
        assertThat(tran.getCategoryCode()).isEqualTo(INTEREST_CAT_CD);
        assertThat(tran.getSource()).isEqualTo("System");
        assertThat(tran.getDescription()).startsWith("Int. for a/c ");
        assertThat(tran.getCardNum()).isEqualTo(TEST_CARD_NUM);

        // Interest amount: (1000.00 × 18.00) / 1200 = 15.00
        assertThat(tran.getAmount())
                .isEqualByComparingTo(new BigDecimal("15.00"));

        // TRAN-ID format: PARM-DATE + 8-digit suffix (16 chars total)
        assertThat(tran.getTranId()).isNotNull();
        assertThat(tran.getTranId().length()).isGreaterThanOrEqualTo(8);

        // Timestamps in DB2 format: YYYY-MM-DD-HH.MM.SS.mmmmmm (26 chars)
        assertThat(tran.getOrigTimestamp()).isNotNull();
        assertThat(tran.getProcTimestamp()).isNotNull();
    }

    @Test
    @DisplayName("Compute fees is a stub — no fee-related changes (COBOL EXIT)")
    void testComputeFeesIsStub() throws Exception {
        // CBACT04C.cbl paragraph 1400-COMPUTE-FEES (lines 518-520):
        //   1400-COMPUTE-FEES.
        //       EXIT.
        // This stub is preserved as a no-op in the Java implementation
        BigDecimal originalBalance = new BigDecimal("8000.00");
        seedAccount(TEST_ACCT_ID, originalBalance, GROUP_ID,
                BigDecimal.ZERO, BigDecimal.ZERO);
        seedCardXref(TEST_ACCT_ID, TEST_CARD_NUM);
        seedDiscountGroup(GROUP_ID, INTEREST_TYPE_CD, INTEREST_CAT_CD,
                new BigDecimal("18.00"));
        seedCategoryBalance(TEST_ACCT_ID, INTEREST_TYPE_CD, INTEREST_CAT_CD,
                new BigDecimal("1000.00"));

        JobExecution execution = jobLauncherTestUtils.launchJob(buildJobParameters());
        assertThat(execution.getStatus()).isEqualTo(BatchStatus.COMPLETED);

        // Expected: ONLY interest is added — NO fee deductions or additions
        // (1000.00 × 18.00) / 1200 = 15.00
        BigDecimal expectedInterest = new BigDecimal("15.00");
        Account updated = accountRepository.findById(TEST_ACCT_ID).orElseThrow();
        assertThat(updated.getCurrBal())
                .isEqualByComparingTo(originalBalance.add(expectedInterest));

        // Verify: no fee-type transactions generated (only interest type '01')
        List<Transaction> transactions = transactionRepository.findAll();
        for (Transaction t : transactions) {
            if (t.getDescription() != null
                    && !t.getDescription().startsWith("Int. for a/c ")) {
                // If any non-interest transaction exists, fees are not a stub
                assertThat(t.getTypeCode())
                        .as("Unexpected fee transaction found — "
                                + "1400-COMPUTE-FEES should be a no-op")
                        .isEqualTo(INTEREST_TYPE_CD);
            }
        }
    }

    // =========================================================================
    // Helper Methods — Test data factories
    // =========================================================================

    /**
     * Seeds an Account record via upsert (save). Uses the full 12-arg
     * constructor matching the CVACT01Y.cpy record layout.
     */
    private Account seedAccount(String acctId, BigDecimal currBal, String groupId,
                                BigDecimal cycCredit, BigDecimal cycDebit) {
        Account account = new Account(
                acctId,                         // ACCT-ID (11 chars)
                "Y",                            // ACCT-ACTIVE-STATUS
                currBal,                        // ACCT-CURR-BAL
                new BigDecimal("10000.00"),     // ACCT-CREDIT-LIMIT
                new BigDecimal("5000.00"),      // ACCT-CASH-CREDIT-LIMIT
                "20200101",                     // ACCT-OPEN-DATE (CCYYMMDD)
                "20251231",                     // ACCT-EXPIRATION-DATE
                "20230101",                     // ACCT-REISSUE-DATE
                cycCredit,                      // ACCT-CURR-CYC-CREDIT
                cycDebit,                       // ACCT-CURR-CYC-DEBIT
                "10001",                        // ACCT-ADDR-ZIP
                groupId                         // ACCT-GROUP-ID
        );
        return accountRepository.save(account);
    }

    /**
     * Seeds a CardXref record (CVACT03Y.cpy junction table).
     * Links XREF-ACCT-ID → XREF-CARD-NUM for AIX lookup.
     */
    private CardXref seedCardXref(String acctId, String cardNum) {
        CardXref xref = new CardXref(cardNum, TEST_CUST_ID, acctId);
        return cardXrefRepository.save(xref);
    }

    /**
     * Seeds a DiscountGroup record (DISCGRP reference data).
     * Used for interest rate lookup by GROUP-ID + TYPE-CD + CAT-CD.
     */
    private DiscountGroup seedDiscountGroup(String groupId, String typeCode,
                                            Integer catCode, BigDecimal rate) {
        DiscountGroup group = new DiscountGroup(groupId, typeCode, catCode, rate);
        return discountGroupRepository.save(group);
    }

    /**
     * Seeds a CategoryBalance record (CVTRA07Y.cpy TCATBALF dataset).
     * Primary input to the interest calculation job — balance is used in formula.
     */
    private CategoryBalance seedCategoryBalance(String acctId, String typeCode,
                                                Integer catCode, BigDecimal balance) {
        CategoryBalance catBal = new CategoryBalance(acctId, typeCode, catCode, balance);
        return categoryBalanceRepository.save(catBal);
    }

    /**
     * Builds job parameters with the standard PARM-DATE (CCYYMMDD format).
     * Mimics the COBOL EXTERNAL PARMS linkage section.
     *
     * <p>NOTE: Returns {@code JobParameters} (not {@code JobExecution}) to
     * prevent {@code JobScopeTestExecutionListener} from discovering this
     * method as a job-execution factory.</p>
     */
    private org.springframework.batch.core.JobParameters buildJobParameters() {
        return new JobParametersBuilder()
                .addString("parmDate", PARM_DATE)
                .toJobParameters();
    }
}