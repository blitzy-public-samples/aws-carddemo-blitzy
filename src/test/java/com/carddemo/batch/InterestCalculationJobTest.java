package com.carddemo.batch;

import com.carddemo.entity.Account;
import com.carddemo.entity.Transaction;
import com.carddemo.entity.TransactionCategoryBalance;
import com.carddemo.repository.AccountRepository;
import com.carddemo.repository.TransactionCategoryBalanceRepository;
import com.carddemo.repository.TransactionRepository;
import com.carddemo.util.CardDemoConstants;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
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
import org.springframework.data.domain.Pageable;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code @SpringBatchTest} integration test for the Spring Batch <strong>{@code interestCalculationJob}</strong>,
 * the Java port of the legacy COBOL interest-calculation program {@code app/cbl/CBACT04C.cbl}.
 *
 * <h2>Parity goal (AAP &sect;0.2.1.3, &sect;0.4.1.5, &sect;0.6.3, &sect;0.7.1, discrepancy&nbsp;#4)</h2>
 * <p>This class proves the same financial arithmetic that the service-level sibling
 * {@code FinancialParityTest} pins, but reached <strong>through the batch job</strong>
 * (tasklet&nbsp;&rarr;&nbsp;service) and asserting the job reaches {@link BatchStatus#COMPLETED}. The
 * governing COBOL contract (verified against the source) is:</p>
 * <ul>
 *   <li><strong>{@code 1300-COMPUTE-INTEREST} [CBACT04C L464-465]:</strong> monthly interest =
 *       {@code (TRAN-CAT-BAL * DIS-INT-RATE) / 1200}; the Java port computes it with
 *       {@link RoundingMode#HALF_UP} at scale {@link CardDemoConstants#MONEY_SCALE} (AAP &sect;0.6.3 / &sect;0.7.1
 *       govern over COBOL truncation).</li>
 *   <li><strong>{@code 1200-GET-INTEREST-RATE} / {@code 1200-A} [L415-460]:</strong> the
 *       {@code DISCGRP} rate keyed by {@code (account group_id, tran_type_cd, tran_cat_cd)}, with a
 *       <strong>{@code 'DEFAULT'}-group fallback</strong> on a not-found (VSAM status {@code '23'}) keyed read.</li>
 *   <li><strong>{@code 1300-B-WRITE-TX} [L473-500]:</strong> an interest {@link Transaction} is written
 *       per category <em>only when the rate is non-zero</em> ({@code IF DIS-INT-RATE NOT = 0}, L214) with
 *       type {@code '01'}, category {@code '05'} (numeric {@code 5}), source {@code 'System'}, and
 *       description {@code 'Int. for a/c ' || ACCT-ID}.</li>
 *   <li><strong>{@code 1050-UPDATE-ACCOUNT} [L352-356]:</strong> {@code ADD WS-TOTAL-INT TO ACCT-CURR-BAL},
 *       then zero both current-cycle totals, then {@code REWRITE}.</li>
 * </ul>
 *
 * <h2>Why a non-zero balance must be injected</h2>
 * <p>The Flyway seed reproduces the byte-exact ASCII fixtures: each of the 50 {@code tcatbal} rows
 * (one per account, key {@code (acctId, "01", 1)}) carries a {@code 0.00} balance, while {@code discgrp}
 * carries {@code (A000000000, "01", 1) = 15.00} and {@code (DEFAULT, "01", 1) = 15.00}. On the raw seed
 * the arithmetic is therefore {@code 0.00 * 15.00 / 1200 = 0.00}; but because the rate is non-zero the
 * service still writes a {@code 0.00} interest transaction and adds {@code 0.00} to the balance (which is
 * left unchanged). {@link #interestJob_onRawSeed_writesZeroInterestTx_andLeavesBalancesUnchanged()} pins
 * that exact behavior, and the other two tests inject a known {@code 1000.00} category balance to
 * exercise the real money math ({@code 12.50}).</p>
 *
 * <h2>Isolation contract (MANDATORY)</h2>
 * <p>The job <strong>commits</strong> domain mutations (interest transactions, account-balance updates,
 * zeroed cycle totals). The class is therefore deliberately <strong>not</strong> {@code @Transactional}
 * &mdash; a surrounding test transaction would corrupt the Spring Batch metadata and the job's own commit
 * boundary. Every test method additionally carries
 * {@link DirtiesContext @DirtiesContext(methodMode = AFTER_METHOD)} so the Spring context is rebuilt
 * fresh after each method (this also resets the service's in-memory transaction-id suffix counter).</p>
 *
 * <p><strong>Why context rebuilding alone is insufficient &mdash; and what actually guarantees
 * isolation here.</strong> The {@code test} profile's H2 URL pins the in-memory database with
 * {@code DB_CLOSE_DELAY=-1}, so the named database {@code carddemo-test} survives for the whole JVM
 * (it is <em>not</em> dropped when the context is torn down), and Flyway is idempotent (it will not
 * re-apply {@code V1..V4} to an already-migrated database, hence does not re-seed). Consequently a
 * context rebuild does <em>not</em> restore the seed, and a committing job would otherwise leak its
 * interest rows and balance changes into sibling tests and downstream test classes. This class
 * therefore performs an <strong>explicit, deterministic reset to the pristine seed</strong> in both
 * {@link #setUp()} (so each test starts from a known state regardless of execution order or prior
 * contamination) and {@link #tearDown()} (so the class leaves the shared database pristine for
 * downstream classes): it deletes every transaction (the seed contains none) and restores account
 * {@value #ACCT_ID} and its single category-balance row to their seeded values. Pre-seed mutations and
 * the resets are committed with {@code saveAndFlush}/{@code deleteAllInBatch} (the class is not
 * {@code @Transactional}) so the job &mdash; running in its own transaction &mdash; observes them.</p>
 *
 * <h2>Multi-job scaffold</h2>
 * <p>The context defines several {@link Job} beans, so {@link JobLauncherTestUtils} cannot auto-select
 * one; the {@code interestCalculationJob} is injected with a mandatory
 * {@link Qualifier @Qualifier("interestCalculationJob")} and bound explicitly in {@link #setUp()}.
 * The {@code test} profile ({@code src/test/resources/application-test.yml}) runs against in-memory H2 in
 * PostgreSQL mode with {@code spring.batch.job.enabled=false} (jobs launched explicitly) and Flyway
 * {@code V1..V4} applied on each context startup.</p>
 *
 * <p>Money equality is always asserted with AssertJ {@code isEqualByComparingTo} (value, not
 * representation) and the interest amount's {@code scale()} is pinned to {@link CardDemoConstants#MONEY_SCALE}
 * (2) to lock the {@code NUMERIC(12,2)} precision. The exact {@code tranId} string is intentionally
 * <em>not</em> asserted (it is a run-date + suffix id, not a parity anchor; AAP &sect;0.6.5).</p>
 *
 * @see InterestCalculationJobConfig
 * @see com.carddemo.service.InterestCalculationService
 * @see com.carddemo.FinancialParityTest
 * @see <a href="file:app/cbl/CBACT04C.cbl">app/cbl/CBACT04C.cbl (the COBOL authority)</a>
 */
@SpringBatchTest
@SpringBootTest
@ActiveProfiles("test")
@DisplayName("Interest calculation batch job: interestCalculationJob vs COBOL CBACT04C")
class InterestCalculationJobTest {

    /**
     * The account exercised by every test. Account ids {@code 1..50} exist in the seed; account {@code 1}
     * is group {@code A000000000} (whose {@code (01,1)} rate is {@code 15.00}), has a seeded
     * {@code curr_bal} of {@code 194.00} with both cycle totals {@code 0.00}, and owns exactly one
     * {@code tcatbal} row keyed {@code (1, "01", 1)} with a {@code 0.00} balance.
     */
    private static final Long ACCT_ID = 1L;

    /**
     * Run/parameter date handed to the job as the {@code runDate} job parameter (COBOL {@code PARM-DATE}).
     * Any valid date works because this test never asserts {@code tranId} string parity (AAP &sect;0.6.5).
     */
    private static final String RUN_DATE = "2024-01-31";

    /**
     * The seeded {@code discgrp} interest rate for {@code (group, "01", 1)} &mdash; {@code 15.00} for both
     * {@code A000000000} and {@code DEFAULT}. Used to compute the expected interest with the same formula
     * the production service uses.
     */
    private static final BigDecimal SEEDED_RATE = new BigDecimal("15.00");

    /**
     * The non-zero category balance injected onto account {@code 1}'s single {@code tcatbal} row to
     * exercise the real arithmetic ({@code 1000.00 * 15.00 / 1200 = 12.50}).
     */
    private static final BigDecimal INJECTED_BALANCE = new BigDecimal("1000.00");

    /**
     * A 10-character disclosure-group id guaranteed to have <strong>no</strong> {@code discgrp} row (the
     * seed defines only {@code A000000000}, {@code DEFAULT}, and {@code ZEROAPR}); assigning it to the
     * account forces the COBOL {@code 1200-A} fallback to the {@code 'DEFAULT'} group. The accounts table
     * {@code group_id} column is {@code VARCHAR(10)}, so this 10-character value fits exactly.
     */
    private static final String UNKNOWN_GROUP_ID = "ZZZZZZZZZZ";

    /**
     * The numeric interest transaction category ({@code 5}), derived from the 2-character COBOL literal
     * {@link CardDemoConstants#INTEREST_TRAN_CAT_CD} ({@code "05"}) exactly as the production service does
     * ({@code Integer.parseInt("05")}). {@link Transaction#getCatCd()} is an {@link Integer}.
     */
    private static final int INTEREST_CAT_CD = Integer.parseInt(CardDemoConstants.INTEREST_TRAN_CAT_CD);

    /** Scale-2 monetary zero ({@code 0.00}) reused by the cycle-total assertions and the seed reset. */
    private static final String ZERO_MONEY = "0.00";

    /**
     * Account {@value #ACCT_ID}'s seeded {@code curr_bal} ({@code 194.00}, verified against
     * {@code V3__seed_master.sql} / {@code app/data/ASCII/acctdata.txt}). Used by the deterministic seed
     * reset so each test starts from, and the class leaves behind, the pristine balance.
     */
    private static final BigDecimal SEED_CURR_BAL = new BigDecimal("194.00");

    /**
     * Account {@value #ACCT_ID}'s seeded disclosure group ({@code A000000000}), whose {@code (01,1)} rate
     * is {@code 15.00}. Restored by the seed reset so the third test's {@link #UNKNOWN_GROUP_ID}
     * reassignment never leaks to the other tests or downstream classes.
     */
    private static final String SEED_GROUP_ID = "A000000000";

    /**
     * Auto-registered by {@link SpringBatchTest @SpringBatchTest}; drives explicit job launches. The
     * specific job to launch is bound in {@link #setUp()} via {@link JobLauncherTestUtils#setJob(Job)}.
     */
    @Autowired
    private JobLauncherTestUtils jobLauncherTestUtils;

    /** Auto-registered by {@link SpringBatchTest @SpringBatchTest}; clears batch metadata between runs. */
    @Autowired
    private JobRepositoryTestUtils jobRepositoryTestUtils;

    /**
     * The job under test, disambiguated among the context's {@link Job} beans by its exact bean name.
     * The {@link Qualifier @Qualifier} is mandatory: without it Spring raises
     * {@code NoUniqueBeanDefinitionException} because several {@code Job} beans exist.
     */
    @Autowired
    @Qualifier("interestCalculationJob")
    private Job interestCalculationJob;

    /** Account master access &mdash; reads/commits the pre-run state and asserts the post-run flush. */
    @Autowired
    private AccountRepository accountRepository;

    /** Transaction access &mdash; locates the interest transaction the job writes. */
    @Autowired
    private TransactionRepository transactionRepository;

    /** Per-category balance access &mdash; injects the non-zero balance that exercises the arithmetic. */
    @Autowired
    private TransactionCategoryBalanceRepository tcbRepository;

    /**
     * Binds {@code interestCalculationJob} as the job {@link JobLauncherTestUtils} launches, clears any
     * batch metadata from a prior run so each test starts from a clean {@code JobInstance}/{@code JobExecution}
     * state, and resets the shared in-memory database to the pristine seed for account {@value #ACCT_ID}
     * (see {@link #resetAccountToSeed()}). The explicit reset is required because the persistent H2
     * database survives the {@code @DirtiesContext} context rebuild and Flyway does not re-seed it, so
     * without this each test would otherwise observe the committed mutations of the previously executed
     * test.
     */
    @BeforeEach
    void setUp() {
        jobLauncherTestUtils.setJob(interestCalculationJob);
        jobRepositoryTestUtils.removeJobExecutions();
        resetAccountToSeed();
    }

    /**
     * Restores the shared in-memory database to its pristine seed state after each test so the committed
     * interest transactions and balance changes this class produces never leak into downstream test
     * classes that share the JVM-lived H2 database. Delegates to {@link #resetAccountToSeed()}.
     */
    @AfterEach
    void tearDown() {
        resetAccountToSeed();
    }

    /**
     * Deterministically restores the pristine Flyway seed for the data this class touches, committing each
     * step (the class is not {@code @Transactional}):
     * <ol>
     *   <li>deletes <em>every</em> transaction &mdash; the seed contains none, so this removes the
     *       {@code 'System'} interest rows the job writes for all accounts (the {@code transactions} table
     *       is a leaf: nothing references it, so the bulk delete is foreign-key safe);</li>
     *   <li>restores account {@value #ACCT_ID} to its seeded balance ({@link #SEED_CURR_BAL}), zeroed cycle
     *       totals, and seeded disclosure group ({@link #SEED_GROUP_ID}); and</li>
     *   <li>restores account {@value #ACCT_ID}'s single {@code (acctId,"01",1)} category-balance row to the
     *       seeded {@code 0.00}.</li>
     * </ol>
     * Accounts {@code 2..50} need no balance restoration because the all-zero seed yields {@code 0.00}
     * interest for them (their balances are never changed); deleting their {@code 0.00} interest rows in
     * step&nbsp;1 fully restores them.
     */
    private void resetAccountToSeed() {
        // (1) Remove all transactions (the seed has none) — clears every interest row the job(s) wrote.
        transactionRepository.deleteAllInBatch();

        // (2) Restore account ACCT_ID to its seeded balance, zeroed cycle totals, and seeded group.
        Account account = accountRepository.findById(ACCT_ID).orElseThrow();
        account.setCurrBal(SEED_CURR_BAL);
        account.setCurrCycCredit(new BigDecimal(ZERO_MONEY));
        account.setCurrCycDebit(new BigDecimal(ZERO_MONEY));
        account.setGroupId(SEED_GROUP_ID);
        accountRepository.saveAndFlush(account);

        // (3) Restore account ACCT_ID's single category-balance row to the seeded 0.00.
        setCategoryBalance(ACCT_ID, new BigDecimal(ZERO_MONEY));
    }

    /**
     * Builds the job parameters for a run, combining a unique parameter set (so the {@code JobInstance} is
     * re-runnable across tests) with the {@code runDate} parameter the step's {@code @StepScope} tasklet
     * binds via SpEL.
     *
     * @param runDate the run date to pass as the {@code runDate} job parameter
     * @return the assembled {@link JobParameters}
     */
    private JobParameters runDateParams(String runDate) {
        return new JobParametersBuilder(jobLauncherTestUtils.getUniqueJobParameters())
                .addString("runDate", runDate)
                .toJobParameters();
    }

    /**
     * Reproduces the production monthly-interest formula <em>exactly</em> so the expectations track the
     * production constants. Mirrors {@code InterestCalculationService}'s
     * {@code categoryBalance.multiply(rate).divide(BigDecimal.valueOf(INTEREST_DIVISOR), MONEY_SCALE,
     * RoundingMode.HALF_UP)} (CBACT04C {@code 1300-COMPUTE-INTEREST}, L464-465).
     *
     * @param balance the per-category balance ({@code TRAN-CAT-BAL})
     * @param rate    the resolved disclosure-group rate ({@code DIS-INT-RATE})
     * @return the monthly interest at scale {@link CardDemoConstants#MONEY_SCALE}, rounded HALF_UP
     */
    private static BigDecimal expectedMonthlyInterest(BigDecimal balance, BigDecimal rate) {
        return balance.multiply(rate)
                .divide(BigDecimal.valueOf(CardDemoConstants.INTEREST_DIVISOR),
                        CardDemoConstants.MONEY_SCALE,
                        RoundingMode.HALF_UP);
    }

    /**
     * Locates the single interest transaction the job writes for an account, identified by the parity
     * fields {@code type_cd = "01"}, {@code cat_cd = 5}, and {@code source = "System"} (CBACT04C
     * {@code 1300-B-WRITE-TX}). The lookup walks all of the account's transactions via the production
     * browse query {@link TransactionRepository#findByAcctIdOrderByOrigTs(Long, Pageable)} with
     * {@link Pageable#unpaged()} so every row is returned regardless of count. The filter is null-safe so
     * a missing field never throws.
     *
     * @param acctId the owning account id
     * @return the interest {@link Transaction}, or {@code null} if none was written
     */
    private Transaction findInterestTxForAccount(Long acctId) {
        List<Transaction> transactions = transactionRepository
                .findByAcctIdOrderByOrigTs(acctId, Pageable.unpaged())
                .getContent();
        return transactions.stream()
                .filter(t -> CardDemoConstants.INTEREST_TRAN_TYPE_CD.equals(t.getTypeCd()))
                .filter(t -> t.getCatCd() != null && t.getCatCd() == INTEREST_CAT_CD)
                .filter(t -> CardDemoConstants.INTEREST_TRAN_SOURCE.equals(t.getSource()))
                .findFirst()
                .orElse(null);
    }

    /**
     * Commits a new per-category balance onto the account's first (and, in the seed, only) {@code tcatbal}
     * row so the non-transactional job observes it. Uses {@code saveAndFlush} because this test class is
     * deliberately <strong>not</strong> {@code @Transactional}.
     *
     * @param acctId the owning account id (must have at least one category-balance row)
     * @param newBal the balance to set on the row
     */
    private void setCategoryBalance(Long acctId, BigDecimal newBal) {
        List<TransactionCategoryBalance> rows = tcbRepository.findByIdAcctId(acctId);
        assertThat(rows)
                .as("account %s must have at least one tcatbal row to inject a balance onto", acctId)
                .isNotEmpty();
        TransactionCategoryBalance row = rows.get(0);
        row.setTranCatBal(newBal);
        tcbRepository.saveAndFlush(row);
    }

    // -------------------------------------------------------------------------------------------
    // Test 1 — raw seed: a 0.00 interest transaction is written and balances are left unchanged.
    // -------------------------------------------------------------------------------------------

    /**
     * On the genuine all-zero seed the job must still write a {@code 0.00} interest transaction for the
     * account (because the seeded {@code (01,1)} rate of {@code 15.00} is non-zero, so CBACT04C's
     * {@code IF DIS-INT-RATE NOT = 0} branch fires &mdash; L214) while leaving the account balance
     * unchanged (the interest added is {@code 0.00 * 15.00 / 1200 = 0.00}) and zeroing the cycle totals.
     *
     * <p>This proves the end-to-end wiring through the batch job: the {@code @StepScope} tasklet binds the
     * {@code runDate} parameter, invokes {@link com.carddemo.service.InterestCalculationService#calculateInterest(String)},
     * the per-category path runs, a {@code 0.00} interest transaction is emitted, and the job reaches
     * {@link BatchStatus#COMPLETED}.</p>
     *
     * @throws Exception if launching the job fails (declared by {@link JobLauncherTestUtils#launchJob})
     */
    @Test
    @DirtiesContext(methodMode = DirtiesContext.MethodMode.AFTER_METHOD)
    @DisplayName("Test 1: raw seed writes a 0.00 interest tx and leaves the account balance unchanged")
    void interestJob_onRawSeed_writesZeroInterestTx_andLeavesBalancesUnchanged() throws Exception {
        // Capture the pre-run balance (seeded curr_bal = 194.00). The snapshot is an immutable BigDecimal.
        Account before = accountRepository.findById(ACCT_ID).orElseThrow();
        BigDecimal balBefore = before.getCurrBal();

        // Launch the job through the batch test harness (tasklet -> service).
        JobExecution execution = jobLauncherTestUtils.launchJob(runDateParams(RUN_DATE));

        // The job must complete successfully.
        assertThat(execution.getStatus()).isEqualTo(BatchStatus.COMPLETED);

        // An interest transaction must have been written with the exact CBACT04C parity fields and a
        // ZERO amount (rate 15.00 != 0 -> tx written; balance 0.00 -> amount 0.00).
        Transaction tx = findInterestTxForAccount(ACCT_ID);
        assertThat(tx).as("a 0.00 interest transaction must be written even on the zero-balance seed")
                .isNotNull();
        assertThat(tx.getTypeCd()).isEqualTo(CardDemoConstants.INTEREST_TRAN_TYPE_CD);
        assertThat(tx.getCatCd()).isEqualTo(INTEREST_CAT_CD);
        assertThat(tx.getSource()).isEqualTo(CardDemoConstants.INTEREST_TRAN_SOURCE);
        assertThat(tx.getDescription()).startsWith(CardDemoConstants.INTEREST_TRAN_DESC_PREFIX);
        assertThat(tx.getAmt()).isEqualByComparingTo(ZERO_MONEY);
        assertThat(tx.getOrigTs()).isNotNull();
        assertThat(tx.getProcTs()).isNotNull();

        // The account flush (1050-UPDATE-ACCOUNT) added 0.00, so the balance is unchanged and both cycle
        // totals are zeroed.
        Account after = accountRepository.findById(ACCT_ID).orElseThrow();
        assertThat(after.getCurrBal())
                .as("curr_bal must be unchanged when the accrued interest is 0.00")
                .isEqualByComparingTo(balBefore);
        assertThat(after.getCurrCycCredit()).isEqualByComparingTo(ZERO_MONEY);
        assertThat(after.getCurrCycDebit()).isEqualByComparingTo(ZERO_MONEY);
    }

    // -------------------------------------------------------------------------------------------
    // Test 2 — injected non-zero balance: the interest is computed exactly (the core arithmetic gate).
    // -------------------------------------------------------------------------------------------

    /**
     * Injects a known {@code 1000.00} category balance on account {@code 1} (group {@code A000000000},
     * seeded {@code (01,1)} rate {@code 15.00}), runs the job, and asserts the exact interest amount
     * ({@code 1000.00 * 15.00 / 1200 = 12.50}) plus the {@code NUMERIC(12,2)} scale, and that the account
     * balance increased by exactly that interest with both cycle totals zeroed.
     *
     * @throws Exception if launching the job fails (declared by {@link JobLauncherTestUtils#launchJob})
     */
    @Test
    @DirtiesContext(methodMode = DirtiesContext.MethodMode.AFTER_METHOD)
    @DisplayName("Test 2: an injected 1000.00 category balance yields exactly 12.50 interest")
    void interestJob_withInjectedCategoryBalance_computesInterestExactly() throws Exception {
        // Pre-run balance snapshot (seeded curr_bal = 194.00).
        Account before = accountRepository.findById(ACCT_ID).orElseThrow();
        BigDecimal balBefore = before.getCurrBal();

        // Inject a non-zero category balance and COMMIT it (the test is not @Transactional).
        setCategoryBalance(ACCT_ID, INJECTED_BALANCE);

        // Launch the job and require success.
        JobExecution execution = jobLauncherTestUtils.launchJob(runDateParams(RUN_DATE));
        assertThat(execution.getStatus()).isEqualTo(BatchStatus.COMPLETED);

        // Expected via the same formula the production service uses: 1000.00 * 15.00 / 1200 = 12.50.
        BigDecimal expected = expectedMonthlyInterest(INJECTED_BALANCE, SEEDED_RATE);
        assertThat(expected).as("sanity: the seeded (01,1) rate yields 12.50").isEqualByComparingTo("12.50");

        // The interest transaction amount must equal the expected value, and money must carry scale 2.
        Transaction tx = findInterestTxForAccount(ACCT_ID);
        assertThat(tx).as("an interest transaction must be written for the non-zero rate").isNotNull();
        assertThat(tx.getAmt()).isEqualByComparingTo(expected);
        assertThat(tx.getAmt()).isEqualByComparingTo("12.50");
        assertThat(tx.getAmt().scale())
                .as("interest amount must carry NUMERIC(12,2) scale")
                .isEqualTo(CardDemoConstants.MONEY_SCALE);

        // The account flush must add exactly the interest and zero the cycle totals.
        Account after = accountRepository.findById(ACCT_ID).orElseThrow();
        assertThat(after.getCurrBal())
                .as("curr_bal must increase by exactly the accrued interest")
                .isEqualByComparingTo(balBefore.add(expected));
        assertThat(after.getCurrCycCredit()).isEqualByComparingTo(ZERO_MONEY);
        assertThat(after.getCurrCycDebit()).isEqualByComparingTo(ZERO_MONEY);
    }

    // -------------------------------------------------------------------------------------------
    // Test 3 — DEFAULT-group fallback (CBACT04C 1200-A-GET-DEFAULT-INT-RATE) reached via the job.
    // -------------------------------------------------------------------------------------------

    /**
     * Proves the {@code 'DEFAULT'}-group fallback at the job level (parity with CBACT04C: a keyed
     * {@code DISCGRP} read that misses with VSAM status {@code '23'} is retried against the {@code 'DEFAULT'}
     * group). The account is reassigned to {@link #UNKNOWN_GROUP_ID} (which has no {@code discgrp} row),
     * a known {@code 1000.00} balance is injected, and the resulting interest must still be {@code 12.50}
     * &mdash; computed from the seeded {@code (DEFAULT, "01", 1) = 15.00} rate. If the fallback did not
     * fire, the rate would resolve to {@code 0.00} and no interest transaction would be written, so a
     * non-null {@code 12.50} transaction specifically evidences the fallback.
     *
     * @throws Exception if launching the job fails (declared by {@link JobLauncherTestUtils#launchJob})
     */
    @Test
    @DirtiesContext(methodMode = DirtiesContext.MethodMode.AFTER_METHOD)
    @DisplayName("Test 3: an account group with no disclosure row falls back to the DEFAULT group rate")
    void interestJob_usesDefaultGroup_whenAccountGroupHasNoDisclosureRow() throws Exception {
        // Reassign the account to a group with NO discgrp row and COMMIT it, forcing the DEFAULT fallback.
        Account acct = accountRepository.findById(ACCT_ID).orElseThrow();
        BigDecimal balBefore = acct.getCurrBal();
        acct.setGroupId(UNKNOWN_GROUP_ID);
        accountRepository.saveAndFlush(acct);

        // Inject the known non-zero category balance and COMMIT it.
        setCategoryBalance(ACCT_ID, INJECTED_BALANCE);

        // Launch the job and require success.
        JobExecution execution = jobLauncherTestUtils.launchJob(runDateParams(RUN_DATE));
        assertThat(execution.getStatus()).isEqualTo(BatchStatus.COMPLETED);

        // The DEFAULT (01,1) rate is also 15.00, so the fallback path yields the same 12.50.
        BigDecimal expected = expectedMonthlyInterest(INJECTED_BALANCE, SEEDED_RATE);

        // A non-null interest transaction equal to 12.50 proves the keyed (ZZZZZZZZZZ,'01',1) read missed
        // and the service fell back to (DEFAULT,'01',1) = 15.00.
        Transaction tx = findInterestTxForAccount(ACCT_ID);
        assertThat(tx)
                .as("the DEFAULT-group fallback must produce an interest transaction (a missed keyed read "
                        + "with no fallback would write none)")
                .isNotNull();
        assertThat(tx.getAmt()).isEqualByComparingTo(expected);
        assertThat(tx.getAmt()).isEqualByComparingTo("12.50");
        assertThat(tx.getAmt().scale())
                .as("interest amount must carry NUMERIC(12,2) scale")
                .isEqualTo(CardDemoConstants.MONEY_SCALE);

        // The account flush still applies on the fallback path.
        Account after = accountRepository.findById(ACCT_ID).orElseThrow();
        assertThat(after.getCurrBal())
                .as("curr_bal must increase by the DEFAULT-rate interest total")
                .isEqualByComparingTo(balBefore.add(expected));
        assertThat(after.getCurrCycCredit()).isEqualByComparingTo(ZERO_MONEY);
        assertThat(after.getCurrCycDebit()).isEqualByComparingTo(ZERO_MONEY);
    }
}
