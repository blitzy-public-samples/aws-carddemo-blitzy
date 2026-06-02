package com.carddemo.batch;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.ExitStatus;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.JobExecution;
import org.springframework.batch.core.JobParameters;
import org.springframework.batch.core.JobParametersBuilder;
import org.springframework.batch.core.launch.JobLauncher;
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
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import com.carddemo.entity.Account;
import com.carddemo.entity.CardXref;
import com.carddemo.entity.DisclosureGroup;
import com.carddemo.entity.DisclosureGroupId;
import com.carddemo.entity.Transaction;
import com.carddemo.entity.TransactionCategoryBalance;
import com.carddemo.entity.TransactionCategoryBalanceId;
import com.carddemo.repository.AccountRepository;
import com.carddemo.repository.CardXrefRepository;
import com.carddemo.repository.DisclosureGroupRepository;
import com.carddemo.repository.TransactionRepository;
import com.carddemo.repository.TransactionCategoryBalanceRepository;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Spring Batch integration test for {@link InterestCalculationJobConfig} — the
 * {@code interestCalculationJob} bean that is the Java/PostgreSQL replacement for the legacy
 * JCL job {@code app/jcl/INTCALC.jcl}, which executed the COBOL program
 * {@code app/cbl/CBACT04C.cbl}.
 *
 * <h2>What the original INTCALC.jcl / CBACT04C.cbl did</h2>
 * {@code INTCALC.jcl} (L22) ran {@code STEP15 EXEC PGM=CBACT04C,PARM='2022071800'} with DDs
 * {@code TCATBALF}, {@code XREFFILE}, {@code ACCTFILE}, {@code DISCGRP} and {@code TRANSACT}.
 * {@code CBACT04C} walked the transaction-category-balance master (TCATBAL), and for each
 * account accumulated monthly interest, updated the account record, and emitted one interest
 * transaction per category. The parity-critical paragraphs preserved by the system under test
 * (and verified here through the job's observable end state) are:
 * <ul>
 *   <li><b>1300-COMPUTE-INTEREST</b> [CBACT04C L462-L470] —
 *       {@code WS-MONTHLY-INT = (TRAN-CAT-BAL * DIS-INT-RATE) / 1200} (PR-01). The Java port is
 *       {@code tranCatBal.multiply(disIntRate).divide(BigDecimal.valueOf(1200), 2, RoundingMode.HALF_UP)}.</li>
 *   <li><b>1200-GET-INTEREST-RATE</b> [CBACT04C L415-L440] — when the
 *       {@code (ACCT-GROUP-ID, TRAN-TYPE-CD, TRAN-CAT-CD)} disclosure-group lookup returns the
 *       VSAM {@code '23'} (not-found) status, retry with {@code ACCT-GROUP-ID = 'DEFAULT'} (PR-02).</li>
 *   <li><b>1050-UPDATE-ACCOUNT</b> [CBACT04C L350-L370] — {@code ADD WS-TOTAL-INT TO ACCT-CURR-BAL};
 *       {@code MOVE ZEROS TO ACCT-CURR-CYC-CREDIT}; {@code MOVE ZEROS TO ACCT-CURR-CYC-DEBIT}; REWRITE (PR-08).</li>
 *   <li><b>1300-B-WRITE-TX</b> [CBACT04C L473-L500] — {@code TRAN-ID = PARM-DATE(10) + suffix(6)}
 *       (16 chars, PR-10); {@code TRAN-TYPE-CD='01'}, {@code TRAN-CAT-CD=0005}, {@code TRAN-SOURCE='System'},
 *       {@code TRAN-DESC='Int. for a/c ' + ACCT-ID}.</li>
 *   <li><b>Z-GET-DB2-FORMAT-TIMESTAMP</b> [CBACT04C L613-L626] — {@code TRAN-ORIG-TS}/{@code TRAN-PROC-TS}
 *       in DB2 external format {@code yyyy-MM-dd-HH.mm.ss.SS0000} (26 chars, PR-11).</li>
 * </ul>
 * The record layouts exercised are {@code app/cpy/CVTRA01Y.cpy} (TCATBAL, the interest base),
 * {@code app/cpy/CVTRA02Y.cpy} (DISCGRP, the rate source), {@code app/cpy/CVACT01Y.cpy} (ACCOUNT,
 * carrying {@code ACCT-GROUP-ID}) and {@code app/cpy/CVTRA05Y.cpy} (the emitted TRAN-RECORD).
 *
 * <h2>What the modernized job does (system under test)</h2>
 * {@code InterestCalculationJobConfig} exposes a single-step {@code interestCalculationJob} that
 * delegates all business logic to {@code InterestCalculationTasklet}. The tasklet pages the
 * {@code tran_cat_balances} table ordered by {@code (accountId, typeCd, categoryCd)}, performs an
 * account control-break, accumulates {@code WS-TOTAL-INT}, applies it via
 * {@code AccountBalanceUpdater.applyInterestAndCloseCycle(...)} at every account boundary, and
 * emits one interest {@code Transaction} per non-zero-rate balance row. The job requires a
 * 10-character {@code tranDate} job parameter (the Java equivalent of {@code PARM='2022071800'}),
 * which becomes the 10-character TRAN-ID prefix.
 *
 * <h2>Observable contract verified here (black-box, end-state assertions)</h2>
 * <ul>
 *   <li><b>PR-01</b> — interest equals {@code (balance * rate) / 1200} at scale 2, HALF_UP;</li>
 *   <li><b>PR-02</b> — a missing specific group falls back to {@code 'DEFAULT'}, and a present
 *       specific group is preferred over {@code 'DEFAULT'};</li>
 *   <li><b>PR-08</b> — after interest, {@code currBal += totalInterest} and both cycle buckets are zero;</li>
 *   <li><b>PR-10</b> — emitted TRAN-IDs are 16 chars = {@code tranDate}(10) + zero-padded sequential suffix(6);</li>
 *   <li><b>PR-11</b> — emitted timestamps render to the 26-char DB2 format;</li>
 *   <li><b>PR-16</b> — every monetary assertion uses
 *       {@link org.assertj.core.api.AbstractBigDecimalAssert#isEqualByComparingTo} (never {@code isEqualTo}).</li>
 * </ul>
 *
 * <h2>Test topology and seeding</h2>
 * <p>{@code @SpringBootTest} boots the full application context; {@code @SpringBatchTest}
 * contributes {@link JobLauncherTestUtils} / {@link JobRepositoryTestUtils}. A dedicated
 * PostgreSQL 15 container is supplied by Testcontainers, Flyway applies every {@code V*.sql}
 * migration, and Hibernate validates the schema against the committed DDL.</p>
 *
 * <p><strong>Foreign-key-driven seeding.</strong> {@code transactions.card_num} carries the
 * enforced {@code fk_transactions_card} foreign key, and the tasklet resolves the emitted
 * transaction's card number from the account cross-reference
 * ({@code CardXrefRepository.findByAccountId(...)}); when no cross-reference exists the resolved
 * value is the empty string, which would violate the foreign key and fail the job. Every test that
 * expects an interest transaction therefore seeds the full {@code customer → card → card_xref}
 * chain. Because there is no {@code CardRepository}/{@code CustomerRepository} on this test's
 * dependency surface, the {@code cards} and {@code customers} prerequisite rows are inserted
 * directly with {@link JdbcTemplate}; the cross-reference itself is seeded through the available
 * {@link CardXrefRepository}.</p>
 *
 * <p><strong>Datasource note.</strong> The {@code @DynamicPropertySource} below binds this class's
 * dedicated {@link #POSTGRES} container and explicitly overrides
 * {@code spring.datasource.driver-class-name} to {@code org.postgresql.Driver}. The override is
 * mandatory because {@code application-test.yml} configures the Testcontainers
 * {@code ContainerDatabaseDriver} (which only accepts {@code jdbc:tc:} magic URLs); the plain
 * {@code jdbc:postgresql://} URL returned by {@link PostgreSQLContainer#getJdbcUrl()} must be
 * handled by the real PostgreSQL JDBC driver.</p>
 *
 * <p><strong>Launcher note.</strong> The context holds two {@link JobLauncher} beans (Spring Boot's
 * synchronous {@code jobLauncher} and {@code BatchConfig}'s {@code asyncJobLauncher}); the
 * synchronous one is injected by name and installed onto {@link JobLauncherTestUtils} in
 * {@link #setUp()} so the job runs inline and its committed results are visible to the post-run
 * assertions.</p>
 *
 * <p><strong>Transactionality.</strong> The class is intentionally <em>not</em> {@code @Transactional}:
 * seeds must commit so the synchronous launcher's own transaction observes them, and the committed
 * results must be visible to the post-run assertions. {@link #setUp()} clears all business tables
 * in foreign-key-safe order before every test to guarantee isolation.</p>
 *
 * @see InterestCalculationJobConfig
 * @see InterestCalculationTasklet
 * @see com.carddemo.entity.Transaction
 * @see com.carddemo.entity.Account
 */
@SpringBootTest
@SpringBatchTest
@Testcontainers
@ActiveProfiles("test")
@DisplayName("INTCALC → InterestCalculationJob (CBACT04C) integration tests")
class InterestCalculationJobIT {

    // ------------------------------------------------------------------------
    // Parity constants (mirror InterestCalculationTasklet / CBACT04C 1300-B-WRITE-TX)
    // ------------------------------------------------------------------------

    /** Fixed-width {@code type_cd} (CHAR(2)) used for the interest balances and emitted transactions. */
    private static final String TYPE_CD = "01";

    /** Fixed-width {@code cat_cd} (CHAR(4)) used for the interest balances and emitted transactions. */
    private static final String CAT_CD = "0005";

    /** A secondary type code used by the multi-category accumulation scenario (Test 6). */
    private static final String TYPE_CD_2 = "02";

    /** COBOL divisor {@code 1200} (12 months × 100%) used to reproduce the PR-01 interest formula. */
    private static final BigDecimal INTEREST_DIVISOR = BigDecimal.valueOf(1200);

    /** Monetary scale (2) shared by all amount/rate assertions (PR-16). */
    private static final int MONEY_SCALE = 2;

    /**
     * Regex for the 26-character DB2 external timestamp format
     * {@code yyyy-MM-dd-HH.mm.ss.SS0000} (PR-11). The fractional component is exactly two
     * centisecond digits followed by the literal {@code 0000}, matching
     * {@code DateConversionUtil.DB2_TIMESTAMP_FORMATTER} ("yyyy-MM-dd-HH.mm.ss.SS'0000'").
     */
    private static final String DB2_TIMESTAMP_REGEX =
            "^\\d{4}-\\d{2}-\\d{2}-\\d{2}\\.\\d{2}\\.\\d{2}\\.\\d{2}0000$";

    /**
     * Formatter reproducing the production DB2 timestamp shape. {@code DateConversionUtil} is not on
     * this test's dependency surface, so the stored {@link LocalDateTime} is re-rendered locally to
     * verify the 26-character PR-11 contract is reproducible from the persisted value.
     */
    private static final DateTimeFormatter DB2_TIMESTAMP_FORMATTER =
            DateTimeFormatter.ofPattern("yyyy-MM-dd-HH.mm.ss.SS'0000'");

    // ------------------------------------------------------------------------
    // Testcontainers PostgreSQL 15
    // ------------------------------------------------------------------------

    /**
     * Dedicated PostgreSQL 15 container for the interest-calculation job. {@code @SuppressWarnings("resource")}
     * is applied because the {@code @Container}/{@code @Testcontainers} lifecycle (not a
     * try-with-resources block) owns container startup and shutdown.
     */
    @Container
    @SuppressWarnings("resource")
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>(
            DockerImageName.parse("postgres:15"))
            .withDatabaseName("carddemo_interest_test")
            .withUsername("carddemo")
            .withPassword("test_password");

    /**
     * Binds the container's JDBC coordinates onto the Spring {@code Environment} before the
     * application context starts. The {@code driver-class-name} override is mandatory (see class
     * Javadoc) so the plain {@code jdbc:postgresql://} URL is handled by the real PostgreSQL JDBC
     * driver rather than the Testcontainers magic-URL driver configured in
     * {@code application-test.yml}.
     *
     * @param registry the dynamic property registry supplied by the Spring TestContext framework
     */
    @DynamicPropertySource
    static void registerDatabaseProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
        registry.add("spring.flyway.enabled", () -> "true");
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "validate");
        registry.add("spring.jpa.properties.hibernate.dialect",
                () -> "org.hibernate.dialect.PostgreSQLDialect");
    }

    // ------------------------------------------------------------------------
    // Injected collaborators
    // ------------------------------------------------------------------------

    /** Spring Batch test helper used to launch the job and inspect its {@link JobExecution}. */
    @Autowired
    private JobLauncherTestUtils jobLauncherTestUtils;

    /** Cleans Spring Batch metadata ({@code BATCH_*} tables) between tests for isolation. */
    @Autowired
    private JobRepositoryTestUtils jobRepositoryTestUtils;

    /**
     * The <em>synchronous</em> {@link JobLauncher} that drives the job under test. The context holds
     * two {@link JobLauncher} beans — Spring Boot's auto-configured synchronous {@code jobLauncher}
     * and {@code BatchConfig}'s {@code asyncJobLauncher} — so neither is unambiguous by type. Naming
     * this field {@code jobLauncher} resolves it by bean name to the synchronous one, guaranteeing
     * the job runs inline so its committed results are visible to the post-run assertions. It is set
     * onto {@link JobLauncherTestUtils} explicitly in {@link #setUp()} because {@code @SpringBatchTest}'s
     * own {@code @Autowired(required = false)} setter silently skips injection under that ambiguity,
     * which would otherwise leave {@link JobLauncherTestUtils#getJobLauncher()} {@code null}.
     */
    @Autowired
    private JobLauncher jobLauncher;

    /** The system under test — selected by bean name so the correct {@link Job} is exercised. */
    @Autowired
    @Qualifier("interestCalculationJob")
    private Job interestCalculationJob;

    /** Repository used to seed accounts and read back updated balances / zeroed cycle buckets. */
    @Autowired
    private AccountRepository accountRepository;

    /** Repository used to seed/clear the card cross-references the job reads to resolve card numbers. */
    @Autowired
    private CardXrefRepository cardXrefRepository;

    /** Repository used to seed the disclosure groups (specific group and {@code DEFAULT} fallback). */
    @Autowired
    private DisclosureGroupRepository disclosureGroupRepository;

    /** Repository used to read back the emitted interest transactions and clear them between tests. */
    @Autowired
    private TransactionRepository transactionRepository;

    /** Repository used to seed the {@code tran_cat_balances} input rows (the interest base). */
    @Autowired
    private TransactionCategoryBalanceRepository tcatbalRepository;

    /**
     * Direct JDBC access for seeding/clearing the {@code customers} and {@code cards} prerequisite
     * rows. Neither a {@code CustomerRepository} nor a {@code CardRepository} is on this test's
     * dependency surface, yet both rows are required to satisfy the {@code fk_xref_*} and
     * {@code fk_transactions_card} foreign keys reached when the job emits interest transactions.
     */
    @Autowired
    private JdbcTemplate jdbcTemplate;

    // ------------------------------------------------------------------------
    // Per-test setup
    // ------------------------------------------------------------------------

    /**
     * Resets every table the job interacts with before each test, in foreign-key-safe order so the
     * deletes never violate a referential constraint:
     * <ol>
     *   <li>{@code removeJobExecutions()} clears prior Spring Batch executions;</li>
     *   <li>{@code transactions} (FK → {@code cards}) is emptied first;</li>
     *   <li>{@code tran_cat_balances} (FK → {@code accounts}) and {@code card_xref}
     *       (FK → {@code cards}/{@code customers}/{@code accounts}) next;</li>
     *   <li>{@code cards} (FK → {@code accounts}) via raw {@code DELETE};</li>
     *   <li>{@code accounts}, then {@code customers} via raw {@code DELETE};</li>
     *   <li>{@code disclosure_groups} last (no inbound FK).</li>
     * </ol>
     * Finally the synchronous launcher and the job under test are installed onto
     * {@link JobLauncherTestUtils} (see the {@link #jobLauncher} field Javadoc).
     */
    @BeforeEach
    void setUp() {
        jobRepositoryTestUtils.removeJobExecutions();
        transactionRepository.deleteAll();
        tcatbalRepository.deleteAll();
        cardXrefRepository.deleteAll();
        jdbcTemplate.update("DELETE FROM cards");
        accountRepository.deleteAll();
        jdbcTemplate.update("DELETE FROM customers");
        disclosureGroupRepository.deleteAll();
        jobLauncherTestUtils.setJobLauncher(jobLauncher);
        jobLauncherTestUtils.setJob(interestCalculationJob);
    }

    // ------------------------------------------------------------------------
    // Helper methods
    // ------------------------------------------------------------------------

    /**
     * Launches the interest-calculation job inline with the supplied 10-character {@code tranDate}
     * (the Java equivalent of the JCL {@code PARM='2022071800'}) plus a unique non-identifying
     * {@code uniqueRunId} (so the same logical run can be re-launched across tests without colliding
     * on the Spring Batch job-instance identity), then asserts the job reached the terminal success
     * state.
     *
     * <p><strong>Why the parameters are built inline (and this method returns {@code void}).</strong>
     * {@code @SpringBatchTest} registers a {@code JobScopeTestExecutionListener} that, before each
     * test, scans the test class for a method returning {@link JobExecution} or {@link JobParameters}
     * and invokes it with <em>no arguments</em> to seed a {@code @JobScope} context. A helper that
     * returned either type while requiring a {@code parmDate} argument would make that listener fail
     * with "No matching arguments found" before the test body even runs. Constructing the parameters
     * inside this {@code void} method keeps every test-class method's return type free of those two
     * Spring Batch types, so the listener finds nothing to seed and the job is driven exclusively by
     * the explicit {@link JobLauncherTestUtils#launchJob(JobParameters)} call below.</p>
     *
     * @param parmDate the 10-character run date used as the TRAN-ID prefix (PR-10)
     * @throws Exception if the launcher rethrows a job failure
     */
    private void launch(String parmDate) throws Exception {
        JobParameters params = new JobParametersBuilder()
                .addString("tranDate", parmDate)
                .addLong("uniqueRunId", System.nanoTime())
                .toJobParameters();
        JobExecution execution = jobLauncherTestUtils.launchJob(params);
        assertThat(execution.getExitStatus()).isEqualTo(ExitStatus.COMPLETED);
        assertThat(execution.getStatus()).isEqualTo(BatchStatus.COMPLETED);
    }

    /**
     * Seeds (saves) an {@link Account} with explicit balance and cycle buckets. The nullable
     * {@code LocalDate} date columns ({@code openDate}/{@code expirationDate}/{@code reissueDate})
     * are intentionally omitted — the interest job neither reads nor writes them, and the schema
     * permits NULL. {@code groupId} drives the PR-02 disclosure-group lookup.
     *
     * @param acctId    the account id (primary key)
     * @param groupId   the disclosure-group id ({@code ACCT-GROUP-ID})
     * @param currBal   the starting current balance ({@code ACCT-CURR-BAL})
     * @param cycCredit the starting current-cycle credit ({@code ACCT-CURR-CYC-CREDIT})
     * @param cycDebit  the starting current-cycle debit ({@code ACCT-CURR-CYC-DEBIT})
     * @return the persisted account
     */
    private Account seedAccount(long acctId, String groupId,
                                BigDecimal currBal, BigDecimal cycCredit, BigDecimal cycDebit) {
        Account a = new Account();
        a.setAcctId(acctId);
        a.setActiveStatus("Y");
        a.setCurrBal(currBal);
        a.setCreditLimit(new BigDecimal("10000.00"));
        a.setCashCreditLimit(new BigDecimal("5000.00"));
        a.setCurrCycCredit(cycCredit);
        a.setCurrCycDebit(cycDebit);
        a.setAddrZip("12345");
        a.setGroupId(groupId);
        return accountRepository.save(a);
    }

    /**
     * Seeds (saves) one {@link TransactionCategoryBalance} input row — the interest base
     * ({@code TRAN-CAT-BAL}) keyed by the composite {@code (accountId, typeCd, categoryCd)}.
     *
     * @param acctId  the owning account id (FK → {@code accounts})
     * @param typeCd  the fixed-width {@code type_cd} (CHAR(2))
     * @param catCd   the fixed-width {@code cat_cd} (CHAR(4))
     * @param balance the category balance used as the interest base (scale 2, PR-16)
     * @return the persisted balance row
     */
    private TransactionCategoryBalance seedTCATBAL(long acctId, String typeCd, String catCd,
                                                   BigDecimal balance) {
        TransactionCategoryBalance b = new TransactionCategoryBalance();
        b.setId(new TransactionCategoryBalanceId(acctId, typeCd, catCd));
        b.setTranCatBal(balance);
        return tcatbalRepository.save(b);
    }

    /**
     * Seeds (saves) one {@link DisclosureGroup} — the interest-rate source keyed by the composite
     * {@code (accountGroupId, tranTypeCd, tranCatCd)}. Use {@code groupId = "DEFAULT"} to seed the
     * PR-02 fallback row.
     *
     * @param groupId the disclosure-group id (or {@code "DEFAULT"})
     * @param typeCd  the fixed-width {@code type_cd} (CHAR(2))
     * @param catCd   the fixed-width {@code cat_cd} (CHAR(4))
     * @param rate    the disclosure interest rate as a percentage value, e.g. {@code 12.00} = 12% APR
     * @return the persisted disclosure group
     */
    private DisclosureGroup seedDisclosureGroup(String groupId, String typeCd, String catCd,
                                                BigDecimal rate) {
        DisclosureGroup g = new DisclosureGroup();
        g.setId(new DisclosureGroupId(groupId, typeCd, catCd));
        g.setDisIntRate(rate);
        return disclosureGroupRepository.save(g);
    }

    /**
     * Renders the deterministic 16-character card number used for an account: the zero-padded
     * account id. Kept consistent between the {@code cards} master row, the {@code card_xref}
     * cross-reference, and the assertion side so the resolved {@code TRAN-CARD-NUM} is predictable.
     *
     * @param acctId the account id
     * @return a 16-character numeric card number
     */
    private String cardNumFor(long acctId) {
        return String.format("%016d", acctId);
    }

    /**
     * Seeds the full {@code customer → card → card_xref} prerequisite chain for an account so the
     * job can resolve a valid {@code TRAN-CARD-NUM} and the emitted transaction satisfies the
     * {@code fk_transactions_card} foreign key. Must be called <em>after</em> {@link #seedAccount}
     * (the card and cross-reference both reference {@code accounts}). The {@code customers} and
     * {@code cards} rows are inserted with {@link JdbcTemplate} (only their NOT-NULL columns are
     * supplied; version/audit columns use their schema defaults); the cross-reference is saved
     * through {@link CardXrefRepository}.
     *
     * @param acctId the account id; reused as the customer id and as the basis for the card number
     * @return the persisted cross-reference's card number
     */
    private String seedCardChain(long acctId) {
        String cardNum = cardNumFor(acctId);
        jdbcTemplate.update(
                "INSERT INTO customers (cust_id, first_name, last_name) VALUES (?, ?, ?)",
                acctId, "Test", "Customer");
        jdbcTemplate.update(
                "INSERT INTO cards (card_num, account_id, cvv_cd, active_status) VALUES (?, ?, ?, ?)",
                cardNum, acctId, 123, "Y");
        CardXref x = new CardXref();
        x.setXrefCardNum(cardNum);
        x.setCustId(acctId);
        x.setAccountId(acctId);
        cardXrefRepository.save(x);
        return cardNum;
    }

    /**
     * Reproduces the production DB2 timestamp rendering for a persisted {@link LocalDateTime}, so a
     * stored {@code origTimestamp}/{@code procTimestamp} can be checked against the 26-character
     * PR-11 contract.
     *
     * @param ts the persisted timestamp
     * @return the DB2 external-format string {@code yyyy-MM-dd-HH.mm.ss.SS0000}
     */
    private String db2Format(LocalDateTime ts) {
        return ts.format(DB2_TIMESTAMP_FORMATTER);
    }

    /**
     * Computes the canonical CBACT04C monthly-interest value
     * {@code (balance * rate) / 1200} at scale 2 with HALF_UP rounding (PR-01), used to derive
     * expected assertion values directly from the formula rather than hand-computed literals.
     *
     * @param balance the interest base ({@code TRAN-CAT-BAL})
     * @param rate    the disclosure rate ({@code DIS-INT-RATE})
     * @return the expected monthly interest at scale 2, HALF_UP
     */
    private BigDecimal expectedInterest(BigDecimal balance, BigDecimal rate) {
        return balance.multiply(rate).divide(INTEREST_DIVISOR, MONEY_SCALE, RoundingMode.HALF_UP);
    }

    // ========================================================================
    // PR-01 — Interest formula parity
    // ========================================================================

    /**
     * Verifies the parity-critical interest formula {@code (TRAN-CAT-BAL * DIS-INT-RATE) / 1200}
     * (CBACT04C 1300-COMPUTE-INTEREST) and its HALF_UP scale-2 rounding.
     */
    @Nested
    @DisplayName("PR-01: interest = (TRAN-CAT-BAL * DIS-INT-RATE) / 1200, HALF_UP scale 2")
    class InterestFormulaParityTests {

        @Test
        @DisplayName("Should compute monthly interest as (TRAN-CAT-BAL * DIS-INT-RATE) / 1200 with HALF_UP rounding")
        void shouldComputeInterestUsingCobolFormulaExactly() throws Exception {
            // Arrange: balance 12000.00 at 12% → 12000 * 12 / 1200 = 120.00.
            long acctId = 1L;
            seedAccount(acctId, "A", new BigDecimal("0.00"),
                    new BigDecimal("0.00"), new BigDecimal("0.00"));
            seedCardChain(acctId);
            seedTCATBAL(acctId, TYPE_CD, CAT_CD, new BigDecimal("12000.00"));
            seedDisclosureGroup("A", TYPE_CD, CAT_CD, new BigDecimal("12.00"));
            BigDecimal expected = expectedInterest(new BigDecimal("12000.00"), new BigDecimal("12.00"));
            assertThat(expected).isEqualByComparingTo("120.00");

            // Act
            launch("2024072000");

            // Assert: account balance accrued the interest, and exactly one interest transaction emitted.
            Account updated = accountRepository.findById(acctId).orElseThrow();
            assertThat(updated.getCurrBal()).isEqualByComparingTo(expected);

            List<Transaction> emitted = transactionRepository.findAll();
            assertThat(emitted).hasSize(1);
            assertThat(emitted.get(0).getAmount()).isEqualByComparingTo(expected);
        }

        @Test
        @DisplayName("Should round monthly interest using HALF_UP at scale 2 (0.125 → 0.13, not HALF_EVEN 0.12)")
        void shouldApplyHalfUpRoundingToInterestComputation() throws Exception {
            // Arrange: 100.00 at 1.50% → 100 * 1.50 / 1200 = 0.125. HALF_UP → 0.13 (HALF_EVEN would give 0.12).
            long acctId = 1L;
            seedAccount(acctId, "A", new BigDecimal("0.00"),
                    new BigDecimal("0.00"), new BigDecimal("0.00"));
            seedCardChain(acctId);
            seedTCATBAL(acctId, TYPE_CD, CAT_CD, new BigDecimal("100.00"));
            seedDisclosureGroup("A", TYPE_CD, CAT_CD, new BigDecimal("1.50"));
            BigDecimal expected = expectedInterest(new BigDecimal("100.00"), new BigDecimal("1.50"));
            assertThat(expected).isEqualByComparingTo("0.13");
            // Guard: confirm the scenario truly distinguishes HALF_UP from HALF_EVEN.
            assertThat(expected).isNotEqualByComparingTo("0.12");

            // Act
            launch("2024072000");

            // Assert
            Account updated = accountRepository.findById(acctId).orElseThrow();
            assertThat(updated.getCurrBal()).isEqualByComparingTo("0.13");

            List<Transaction> emitted = transactionRepository.findAll();
            assertThat(emitted).hasSize(1);
            assertThat(emitted.get(0).getAmount()).isEqualByComparingTo("0.13");
        }
    }

    // ========================================================================
    // PR-02 — DISCGRP DEFAULT fallback
    // ========================================================================

    /**
     * Verifies the CBACT04C 1200-GET-INTEREST-RATE {@code 'DEFAULT'} fallback: when no
     * group-specific disclosure row exists, the {@code 'DEFAULT'} row is used; when a specific row
     * exists it is preferred over {@code 'DEFAULT'}.
     */
    @Nested
    @DisplayName("PR-02: DISCGRP DEFAULT fallback")
    class DefaultFallbackTests {

        @Test
        @DisplayName("Should retry with groupId='DEFAULT' when (groupId, typeCd, catCd) lookup misses")
        void shouldFallBackToDefaultDisclosureGroupWhenSpecificGroupNotFound() throws Exception {
            // Arrange: account group 'Z' has no specific disclosure row; only DEFAULT exists at 6%.
            long acctId = 1L;
            seedAccount(acctId, "Z", new BigDecimal("0.00"),
                    new BigDecimal("0.00"), new BigDecimal("0.00"));
            seedCardChain(acctId);
            seedTCATBAL(acctId, TYPE_CD, CAT_CD, new BigDecimal("12000.00"));
            // No ('Z', '01', '0005') row on purpose; DEFAULT supplies the rate.
            seedDisclosureGroup("DEFAULT", TYPE_CD, CAT_CD, new BigDecimal("6.00"));
            BigDecimal expected = expectedInterest(new BigDecimal("12000.00"), new BigDecimal("6.00"));
            assertThat(expected).isEqualByComparingTo("60.00");

            // Act
            launch("2024072000");

            // Assert: DEFAULT rate (6%) drove the calculation.
            Account updated = accountRepository.findById(acctId).orElseThrow();
            assertThat(updated.getCurrBal()).isEqualByComparingTo("60.00");

            List<Transaction> emitted = transactionRepository.findAll();
            assertThat(emitted).hasSize(1);
            assertThat(emitted.get(0).getAmount()).isEqualByComparingTo("60.00");
        }

        @Test
        @DisplayName("Should prefer specific group rate over DEFAULT when both exist")
        void shouldUseSpecificGroupWhenAvailableInsteadOfDefault() throws Exception {
            // Arrange: both specific ('A' @ 18%) and DEFAULT (@ 6%) exist; the specific rate must win.
            long acctId = 1L;
            seedAccount(acctId, "A", new BigDecimal("0.00"),
                    new BigDecimal("0.00"), new BigDecimal("0.00"));
            seedCardChain(acctId);
            seedTCATBAL(acctId, TYPE_CD, CAT_CD, new BigDecimal("12000.00"));
            seedDisclosureGroup("A", TYPE_CD, CAT_CD, new BigDecimal("18.00"));
            seedDisclosureGroup("DEFAULT", TYPE_CD, CAT_CD, new BigDecimal("6.00"));
            BigDecimal expected = expectedInterest(new BigDecimal("12000.00"), new BigDecimal("18.00"));
            assertThat(expected).isEqualByComparingTo("180.00");

            // Act
            launch("2024072000");

            // Assert: specific rate (18% → 180.00) used, NOT the DEFAULT rate (6% → 60.00).
            Account updated = accountRepository.findById(acctId).orElseThrow();
            assertThat(updated.getCurrBal()).isEqualByComparingTo("180.00");
            assertThat(updated.getCurrBal()).isNotEqualByComparingTo("60.00");

            List<Transaction> emitted = transactionRepository.findAll();
            assertThat(emitted).hasSize(1);
            assertThat(emitted.get(0).getAmount()).isEqualByComparingTo("180.00");
        }
    }

    // ========================================================================
    // PR-08 — Account REWRITE (accrue interest, zero cycle buckets)
    // ========================================================================

    /**
     * Verifies the CBACT04C 1050-UPDATE-ACCOUNT REWRITE pattern: the accumulated interest is added
     * to the current balance and both cycle buckets are reset to zero, applied once per account.
     */
    @Nested
    @DisplayName("PR-08: account REWRITE — currBal += totalInt, cycle buckets zeroed")
    class AccountRewriteTests {

        @Test
        @DisplayName("After applying interest: ACCT-CURR-CYC-CREDIT and ACCT-CURR-CYC-DEBIT = 0")
        void shouldZeroOutCycleCreditAndDebitAfterApplyingInterest() throws Exception {
            // Arrange: starting balance 1000.00, cycle credit 500.00, cycle debit 200.00.
            // 5000.00 at 12% → 50.00 interest → currBal 1050.00, both cycle buckets reset to 0.
            long acctId = 1L;
            seedAccount(acctId, "A", new BigDecimal("1000.00"),
                    new BigDecimal("500.00"), new BigDecimal("200.00"));
            seedCardChain(acctId);
            seedTCATBAL(acctId, TYPE_CD, CAT_CD, new BigDecimal("5000.00"));
            seedDisclosureGroup("A", TYPE_CD, CAT_CD, new BigDecimal("12.00"));
            assertThat(expectedInterest(new BigDecimal("5000.00"), new BigDecimal("12.00")))
                    .isEqualByComparingTo("50.00");

            // Act
            launch("2024072000");

            // Assert: balance accrued, both cycle buckets unconditionally zeroed (PR-08).
            Account updated = accountRepository.findById(acctId).orElseThrow();
            assertThat(updated.getCurrBal()).isEqualByComparingTo("1050.00");
            assertThat(updated.getCurrCycCredit()).isEqualByComparingTo("0.00");
            assertThat(updated.getCurrCycDebit()).isEqualByComparingTo("0.00");
        }

        @Test
        @DisplayName("Should sum interest across multiple TCATBAL records per account (WS-TOTAL-INT accumulator)")
        void shouldAccumulateMultipleTcatbalInterestPerAccount() throws Exception {
            // Arrange: one account, two category balances.
            //   ('01','0005') 12000.00 @ 12% → 120.00
            //   ('02','0005')  6000.00 @  6% →  30.00
            // WS-TOTAL-INT = 150.00; emitted as TWO interest transactions (one per category row).
            long acctId = 1L;
            seedAccount(acctId, "A", new BigDecimal("0.00"),
                    new BigDecimal("0.00"), new BigDecimal("0.00"));
            seedCardChain(acctId);
            seedTCATBAL(acctId, TYPE_CD, CAT_CD, new BigDecimal("12000.00"));
            seedTCATBAL(acctId, TYPE_CD_2, CAT_CD, new BigDecimal("6000.00"));
            seedDisclosureGroup("A", TYPE_CD, CAT_CD, new BigDecimal("12.00"));
            seedDisclosureGroup("A", TYPE_CD_2, CAT_CD, new BigDecimal("6.00"));

            // Act
            launch("2024072000");

            // Assert: accumulated balance and two emitted transactions whose amounts sum to 150.00.
            Account updated = accountRepository.findById(acctId).orElseThrow();
            assertThat(updated.getCurrBal()).isEqualByComparingTo("150.00");

            List<Transaction> emitted = transactionRepository.findAll();
            assertThat(emitted).hasSize(2);
            BigDecimal total = emitted.stream()
                    .map(Transaction::getAmount)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
            assertThat(total).isEqualByComparingTo("150.00");
            assertThat(emitted)
                    .extracting(Transaction::getAmount)
                    .anySatisfy(amt -> assertThat(amt).isEqualByComparingTo("120.00"));
            assertThat(emitted)
                    .extracting(Transaction::getAmount)
                    .anySatisfy(amt -> assertThat(amt).isEqualByComparingTo("30.00"));
        }
    }

    // ========================================================================
    // PR-10 — Transaction ID generation (16 chars = PARM-DATE + suffix)
    // ========================================================================

    /**
     * Verifies the CBACT04C 1300-B-WRITE-TX transaction-id format: a 16-character id composed of the
     * 10-character {@code PARM-DATE} prefix followed by a zero-padded 6-digit sequential suffix that
     * starts at {@code 000001} and increments within the job execution.
     */
    @Nested
    @DisplayName("PR-10: TRAN-ID = PARM-DATE(10) + sequential suffix(6) = 16 chars")
    class TransactionIdGenerationTests {

        @Test
        @DisplayName("TRAN-ID = 10-char PARM-DATE + 6-char sequential suffix = 16 chars total")
        void shouldGenerateTransactionIdsWith16CharsParmDatePlusSuffix() throws Exception {
            // Arrange: single account / balance / rate producing exactly one interest transaction.
            long acctId = 1L;
            seedAccount(acctId, "A", new BigDecimal("0.00"),
                    new BigDecimal("0.00"), new BigDecimal("0.00"));
            seedCardChain(acctId);
            seedTCATBAL(acctId, TYPE_CD, CAT_CD, new BigDecimal("12000.00"));
            seedDisclosureGroup("A", TYPE_CD, CAT_CD, new BigDecimal("12.00"));

            // Act
            launch("2022071800");

            // Assert: id is 16 chars, prefixed by the PARM-DATE, suffixed by the first sequence 000001.
            List<Transaction> emitted = transactionRepository.findAll();
            assertThat(emitted).hasSize(1);
            String tranId = emitted.get(0).getTranId();
            assertThat(tranId).hasSize(16);
            assertThat(tranId).startsWith("2022071800");
            assertThat(tranId.substring(10)).isEqualTo("000001");
            assertThat(tranId.substring(10)).matches("\\d{6}");
            assertThat(tranId).isEqualTo("2022071800000001");
        }

        @Test
        @DisplayName("Should produce a sequential suffix counter for multiple interest transactions in one job")
        void shouldIncrementSuffixForMultipleInterestTransactions() throws Exception {
            // Arrange: three accounts, each with one balance row and the shared 'A' rate → three txns.
            String shared = "2022071800";
            for (long acctId : new long[] {1L, 2L, 3L}) {
                seedAccount(acctId, "A", new BigDecimal("0.00"),
                        new BigDecimal("0.00"), new BigDecimal("0.00"));
                seedCardChain(acctId);
                seedTCATBAL(acctId, TYPE_CD, CAT_CD, new BigDecimal("12000.00"));
            }
            seedDisclosureGroup("A", TYPE_CD, CAT_CD, new BigDecimal("12.00"));

            // Act
            launch(shared);

            // Assert: three transactions, all sharing the PARM-DATE prefix, with sequential suffixes.
            List<Transaction> emitted = transactionRepository.findAll();
            assertThat(emitted).hasSize(3);
            assertThat(emitted)
                    .extracting(t -> t.getTranId().substring(0, 10))
                    .containsOnly(shared);
            List<String> suffixes = emitted.stream()
                    .map(t -> t.getTranId().substring(10))
                    .sorted()
                    .toList();
            assertThat(suffixes).containsExactly("000001", "000002", "000003");
        }
    }

    // ========================================================================
    // PR-10 — Emitted interest-transaction field defaults
    // ========================================================================

    /**
     * Verifies the CBACT04C 1300-B-WRITE-TX field defaults stamped on every emitted interest
     * transaction: type {@code '01'}, category {@code '0005'}, source {@code 'System'} and the
     * description {@code "Int. for a/c " + } the 11-digit zero-padded account id.
     */
    @Nested
    @DisplayName("PR-10: interest transaction defaults — TYPE='01', CAT='0005', SOURCE='System', DESC prefix")
    class TransactionDefaultsTests {

        @Test
        @DisplayName("Emitted interest transactions: TYPE='01', CAT='0005', SOURCE='System', DESC='Int. for a/c ' + acctId")
        void shouldSetInterestTransactionDefaultsPerCobolPattern() throws Exception {
            // Arrange: a single interest transaction for account 12345.
            long acctId = 12345L;
            seedAccount(acctId, "A", new BigDecimal("0.00"),
                    new BigDecimal("0.00"), new BigDecimal("0.00"));
            seedCardChain(acctId);
            seedTCATBAL(acctId, TYPE_CD, CAT_CD, new BigDecimal("12000.00"));
            seedDisclosureGroup("A", TYPE_CD, CAT_CD, new BigDecimal("12.00"));

            // Act
            launch("2024072000");

            // Assert: the fixed COBOL defaults plus the 11-digit zero-padded account id in the desc.
            List<Transaction> emitted = transactionRepository.findAll();
            assertThat(emitted).hasSize(1);
            Transaction tx = emitted.get(0);
            assertThat(tx.getTypeCd()).isEqualTo("01");
            assertThat(tx.getCategoryCd()).isEqualTo("0005");
            assertThat(tx.getSource()).isEqualTo("System");
            assertThat(tx.getDescription())
                    .startsWith("Int. for a/c ")
                    .contains("00000012345");
            assertThat(tx.getCardNum()).isEqualTo(cardNumFor(acctId));
        }
    }

    // ========================================================================
    // PR-11 — DB2 timestamp format + job-level behavior
    // ========================================================================

    /**
     * Verifies the PR-11 26-character DB2 timestamp shape on emitted transactions and a couple of
     * job-level behaviors (canonical happy path and the empty-input no-op).
     */
    @Nested
    @DisplayName("PR-11: DB2 timestamp format + job-level behavior")
    class Db2TimestampAndJobLevelTests {

        @Test
        @DisplayName("TRAN-ORIG-TS and TRAN-PROC-TS render to the 26-char DB2 format 'yyyy-MM-dd-HH.mm.ss.SS0000'")
        void shouldEmitTransactionsWithDb2FormattedTimestamps() throws Exception {
            // Arrange
            long acctId = 1L;
            seedAccount(acctId, "A", new BigDecimal("0.00"),
                    new BigDecimal("0.00"), new BigDecimal("0.00"));
            seedCardChain(acctId);
            seedTCATBAL(acctId, TYPE_CD, CAT_CD, new BigDecimal("12000.00"));
            seedDisclosureGroup("A", TYPE_CD, CAT_CD, new BigDecimal("12.00"));

            // Act
            launch("2024072000");

            // Assert: both timestamps are present, render to 26 chars, and match the DB2 pattern.
            List<Transaction> emitted = transactionRepository.findAll();
            assertThat(emitted).hasSize(1);
            Transaction tx = emitted.get(0);

            assertThat(tx.getOrigTimestamp()).isNotNull();
            assertThat(tx.getProcTimestamp()).isNotNull();

            String origDb2 = db2Format(tx.getOrigTimestamp());
            String procDb2 = db2Format(tx.getProcTimestamp());
            assertThat(origDb2).hasSize(26).matches(DB2_TIMESTAMP_REGEX);
            assertThat(procDb2).hasSize(26).matches(DB2_TIMESTAMP_REGEX);

            // CBACT04C stamps a single instant onto both ORIG and PROC timestamps.
            assertThat(tx.getProcTimestamp()).isEqualTo(tx.getOrigTimestamp());
        }

        @Test
        @DisplayName("Should complete successfully for the canonical single-account scenario")
        void shouldCompleteSuccessfullyForCanonicalSingleAccountScenario() throws Exception {
            // Arrange: one account, one balance, one rate.
            long acctId = 1L;
            seedAccount(acctId, "A", new BigDecimal("0.00"),
                    new BigDecimal("0.00"), new BigDecimal("0.00"));
            seedCardChain(acctId);
            seedTCATBAL(acctId, TYPE_CD, CAT_CD, new BigDecimal("12000.00"));
            seedDisclosureGroup("A", TYPE_CD, CAT_CD, new BigDecimal("12.00"));

            // Act: launch(...) asserts both ExitStatus.COMPLETED and BatchStatus.COMPLETED.
            launch("2024072000");

            // Assert the canonical end state was produced.
            Account updated = accountRepository.findById(acctId).orElseThrow();
            assertThat(updated.getCurrBal()).isEqualByComparingTo("120.00");
            assertThat(transactionRepository.findAll()).hasSize(1);
        }

        @Test
        @DisplayName("Should handle empty TCATBAL input gracefully: COMPLETED, no transactions, accounts untouched")
        void shouldHandleEmptyTcatbalInputGracefully() throws Exception {
            // Arrange: an account (with non-zero cycle buckets) and a disclosure group exist, but
            // there are NO TCATBAL rows, so no account is ever processed. The account must be left
            // entirely unchanged — in particular its cycle buckets are NOT zeroed.
            long acctId = 1L;
            seedAccount(acctId, "A", new BigDecimal("1000.00"),
                    new BigDecimal("500.00"), new BigDecimal("200.00"));
            seedDisclosureGroup("A", TYPE_CD, CAT_CD, new BigDecimal("12.00"));

            // Act
            launch("2024072000");

            // Assert: no transactions emitted and the account is byte-for-byte unchanged.
            assertThat(transactionRepository.findAll()).isEmpty();
            Optional<Account> reloaded = accountRepository.findById(acctId);
            assertThat(reloaded).isPresent();
            Account untouched = reloaded.get();
            assertThat(untouched.getCurrBal()).isEqualByComparingTo("1000.00");
            assertThat(untouched.getCurrCycCredit()).isEqualByComparingTo("500.00");
            assertThat(untouched.getCurrCycDebit()).isEqualByComparingTo("200.00");
        }
    }
}
