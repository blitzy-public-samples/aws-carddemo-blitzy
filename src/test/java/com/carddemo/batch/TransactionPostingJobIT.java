package com.carddemo.batch;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
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
import org.springframework.batch.core.StepExecution;
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
import com.carddemo.entity.DailyTransaction;
import com.carddemo.entity.RejectedTransaction;
import com.carddemo.entity.Transaction;
import com.carddemo.entity.TransactionCategoryBalance;
import com.carddemo.entity.TransactionCategoryBalanceId;
import com.carddemo.repository.AccountRepository;
import com.carddemo.repository.CardXrefRepository;
import com.carddemo.repository.DailyTransactionRepository;
import com.carddemo.repository.RejectedTransactionRepository;
import com.carddemo.repository.TransactionRepository;
import com.carddemo.repository.TransactionCategoryBalanceRepository;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Spring Batch integration test for {@link TransactionPostingJobConfig} — the
 * {@code transactionPostingJob} bean that is the Java/PostgreSQL replacement for the legacy
 * JCL job {@code app/jcl/POSTTRAN.jcl}, which executed the COBOL program
 * {@code app/cbl/CBTRN02C.cbl}. This is the most business-critical batch job in CardDemo: it
 * validates each daily transaction through the four COBOL validation codes (100/101/102/103),
 * posts accepted transactions, upserts the transaction-category balance, and updates the account
 * with sign-based cycle bucketing.
 *
 * <h2>What the original POSTTRAN.jcl / CBTRN02C.cbl did</h2>
 * {@code POSTTRAN.jcl} (L23) ran {@code STEP15 EXEC PGM=CBTRN02C} with DDs {@code TRANFILE},
 * {@code DALYTRAN}, {@code XREFFILE}, {@code DALYREJS}, {@code ACCTFILE} and {@code TCATBALF}.
 * {@code CBTRN02C} read the daily-transaction feed (DALYTRAN) sequentially and, for each record,
 * performed the parity-critical paragraphs preserved by the system under test (and verified here
 * through the job's observable end state):
 * <ul>
 *   <li><b>1500-VALIDATE-TRAN</b> [CBTRN02C L370-L422] — the validation chain that yields the four
 *       reject codes. <b>1500-A-LOOKUP-XREF</b> (INVALID KEY → <b>100</b> {@code "INVALID CARD NUMBER FOUND"});
 *       <b>1500-B-LOOKUP-ACCT</b> (INVALID KEY → <b>101</b> {@code "ACCOUNT RECORD NOT FOUND"}); the
 *       over-limit check {@code ACCT-CREDIT-LIMIT >= (ACCT-CURR-CYC-CREDIT - ACCT-CURR-CYC-DEBIT
 *       + DALYTRAN-AMT)} else <b>102</b> {@code "OVERLIMIT TRANSACTION"} (PR-04); and the expiration
 *       check {@code ACCT-EXPIRAION-DATE >= DALYTRAN-ORIG-TS(1:10)} else <b>103</b>
 *       {@code "TRANSACTION RECEIVED AFTER ACCT EXPIRATION"} (PR-05). The 102 and 103 checks are two
 *       independent {@code IF} blocks (NOT else-if): both always run and 103 overwrites 102.</li>
 *   <li><b>2700-UPDATE-TCATBAL</b> [CBTRN02C L467-L501] — composite-key ({@code account_id + type_cd
 *       + cat_cd}) upsert: {@code 2700-A-CREATE-TCATBAL-REC} (INVALID KEY → INITIALIZE + {@code ADD
 *       DALYTRAN-AMT TO TRAN-CAT-BAL} → balance = amount; WRITE) and {@code 2700-B-UPDATE-TCATBAL-REC}
 *       ({@code ADD DALYTRAN-AMT TO TRAN-CAT-BAL} → balance += amount; REWRITE) (PR-06).</li>
 *   <li><b>2800-UPDATE-ACCOUNT-REC</b> [CBTRN02C L545-L560] — {@code ADD DALYTRAN-AMT TO ACCT-CURR-BAL}
 *       (always, regardless of sign); then {@code IF DALYTRAN-AMT >= 0 ADD DALYTRAN-AMT TO
 *       ACCT-CURR-CYC-CREDIT ELSE ADD DALYTRAN-AMT TO ACCT-CURR-CYC-DEBIT} — the COBOL {@code ADD}
 *       preserves sign, so a negative amount drives the debit bucket negative; REWRITE (PR-07).</li>
 * </ul>
 * The record layouts exercised are {@code app/cpy/CVTRA06Y.cpy} (DALYTRAN-RECORD, the input feed),
 * {@code app/cpy/CVTRA05Y.cpy} (TRAN-RECORD, the accepted output), {@code app/cpy/CVTRA01Y.cpy}
 * (TRAN-CAT-BAL-RECORD, the upserted balance), {@code app/cpy/CVACT01Y.cpy} (ACCOUNT-RECORD) and
 * {@code app/cpy/CVACT03Y.cpy} (CARD-XREF-RECORD, the card→account resolution).
 *
 * <h2>What the modernized job does (system under test)</h2>
 * {@code TransactionPostingJobConfig} exposes a single chunk-oriented step
 * ({@code transactionPostingStep}, chunk size {@link TransactionPostingJobConfig#CHUNK_SIZE}) that
 * pages the {@code daily_transactions} staging table for unprocessed rows
 * ({@code findByProcessedFalse}), runs each through {@code TransactionPostingProcessor} (which emits
 * the 100/101/102/103 codes), and dispatches the result: accepted rows are posted to
 * {@code transactions}, their TCATBAL row is upserted, and the owning {@code accounts} row is
 * updated with sign-based bucketing; rejected rows are written to {@code rejected_transactions}
 * (the DALYREJS equivalent) carrying the integer validation code and the exact reason message.
 *
 * <h2>Observable contract verified here (black-box, end-state assertions)</h2>
 * <ul>
 *   <li><b>PR-03</b> — the four validation codes are emitted with their exact COBOL message strings;</li>
 *   <li><b>PR-04</b> — code 102 fires exactly when {@code creditLimit < (cycCredit - cycDebit + amt)};</li>
 *   <li><b>PR-05</b> — code 103 fires exactly when {@code expirationDate < tranOrigTs(1:10)};</li>
 *   <li><b>PR-06</b> — TCATBAL is INSERTed when the composite key is absent and UPDATEd
 *       ({@code balance += amt}) when present;</li>
 *   <li><b>PR-07</b> — {@code currBal += amt} always; a positive amount adds to the credit bucket and a
 *       negative amount adds (signed) to the debit bucket;</li>
 *   <li><b>PR-16</b> — every monetary assertion uses
 *       {@link org.assertj.core.api.AbstractBigDecimalAssert#isEqualByComparingTo} (never {@code isEqualTo});</li>
 *   <li><b>PR-22</b> — {@code Account} carries an {@code @Version} optimistic-lock column (seeded and
 *       re-read here through the repository).</li>
 * </ul>
 *
 * <h2>Test topology and seeding</h2>
 * <p>{@code @SpringBootTest} boots the full application context; {@code @SpringBatchTest}
 * contributes {@link JobLauncherTestUtils} / {@link JobRepositoryTestUtils}. A dedicated
 * PostgreSQL 15 container is supplied by Testcontainers, Flyway applies every {@code V*.sql}
 * migration, and Hibernate validates the schema against the committed DDL.</p>
 *
 * <p><strong>Foreign-key-driven seeding.</strong> {@code transactions.card_num} carries the enforced
 * {@code fk_transactions_card} foreign key, and {@code card_xref} carries enforced
 * {@code fk_xref_card}/{@code fk_xref_customer}/{@code fk_xref_account} foreign keys. Every test that
 * expects an accepted transaction therefore seeds the full {@code customer → card → card_xref}
 * chain. Because neither a {@code CardRepository} nor a {@code CustomerRepository} is on this test's
 * dependency surface, the {@code cards} and {@code customers} prerequisite rows are inserted directly
 * with {@link JdbcTemplate}; the cross-reference itself is seeded through {@link CardXrefRepository}.
 * The code-101 scenario (an XREF that resolves to a missing account) deliberately violates
 * {@code fk_xref_account}, so that single test drops and restores the constraint inside a
 * {@code try/finally} block.</p>
 *
 * <p><strong>Datasource note.</strong> The {@code @DynamicPropertySource} below binds this class's
 * dedicated {@link #POSTGRES} container and explicitly overrides
 * {@code spring.datasource.driver-class-name} to {@code org.postgresql.Driver}. The override is
 * mandatory because {@code application-test.yml} configures the Testcontainers
 * {@code ContainerDatabaseDriver} (which only accepts {@code jdbc:tc:} magic URLs); the plain
 * {@code jdbc:postgresql://} URL returned by {@link PostgreSQLContainer#getJdbcUrl()} must be handled
 * by the real PostgreSQL JDBC driver.</p>
 *
 * <p><strong>Launcher note.</strong> The context holds two {@link JobLauncher} beans (Spring Boot's
 * synchronous {@code jobLauncher} and {@code BatchConfig}'s {@code asyncJobLauncher}); the
 * synchronous one is injected by name and installed onto {@link JobLauncherTestUtils} in
 * {@link #setUp()} so the job runs inline and its committed results are visible to the post-run
 * assertions.</p>
 *
 * <p><strong>Transactionality.</strong> The class is intentionally <em>not</em> {@code @Transactional}:
 * seeds must commit so the synchronous launcher's own transaction observes them, and the committed
 * results must be visible to the post-run assertions. {@link #setUp()} clears all business tables in
 * foreign-key-safe order before every test to guarantee isolation.</p>
 *
 * @see TransactionPostingJobConfig
 * @see TransactionPostingProcessor
 * @see com.carddemo.entity.RejectedTransaction
 * @see com.carddemo.entity.TransactionCategoryBalance
 */
@SpringBootTest
@SpringBatchTest
@Testcontainers
@ActiveProfiles("test")
@DisplayName("POSTTRAN → TransactionPostingJob (CBTRN02C) integration tests")
class TransactionPostingJobIT {

    // ------------------------------------------------------------------------
    // Parity constants (mirror TransactionPostingProcessor / CBTRN02C 1500-VALIDATE-TRAN)
    // ------------------------------------------------------------------------

    /** Fixed-width {@code type_cd} (CHAR(2)) used for the seeded daily transactions and balances. */
    private static final String TYPE_CD = "01";

    /** Fixed-width {@code cat_cd} (CHAR(4)) used for the seeded daily transactions and balances. */
    private static final String CAT_CD = "0005";

    /**
     * Code 100 [CBTRN02C L380-L392] — the XREF lookup found no cross-reference for the card number
     * (or the card number was blank). The literal is asserted independently here to verify the COBOL
     * message string is preserved exactly (PR-03).
     */
    private static final int CODE_INVALID_CARD = 100;
    private static final String MSG_INVALID_CARD = "INVALID CARD NUMBER FOUND";

    /**
     * Code 101 [CBTRN02C L393-L399] — the XREF resolved to an account id that has no ACCTFILE record.
     */
    private static final int CODE_ACCOUNT_NOT_FOUND = 101;
    private static final String MSG_ACCOUNT_NOT_FOUND = "ACCOUNT RECORD NOT FOUND";

    /**
     * Code 102 [CBTRN02C L403-L413] — {@code ACCT-CREDIT-LIMIT < (ACCT-CURR-CYC-CREDIT
     * - ACCT-CURR-CYC-DEBIT + DALYTRAN-AMT)} (PR-04).
     */
    private static final int CODE_OVERLIMIT = 102;
    private static final String MSG_OVERLIMIT = "OVERLIMIT TRANSACTION";

    /**
     * Code 103 [CBTRN02C L414-L420] — {@code ACCT-EXPIRAION-DATE < DALYTRAN-ORIG-TS(1:10)} (PR-05).
     */
    private static final int CODE_EXPIRED = 103;
    private static final String MSG_EXPIRED = "TRANSACTION RECEIVED AFTER ACCT EXPIRATION";

    /**
     * Canonical accepted-transaction timestamp. Its date portion ({@code 2024-06-15}) is the value
     * compared against {@code expirationDate} in the PR-05 expiration check.
     */
    private static final LocalDateTime ACCEPTED_ORIG_TS = LocalDateTime.of(2024, 6, 15, 10, 30, 0);

    /** A future expiration date so the canonical accepted scenarios never trip the PR-05 code 103. */
    private static final LocalDate FUTURE_EXPIRATION = LocalDate.of(2030, 12, 31);

    /** A past expiration date used by the PR-05 code 103 scenario (expired before the tran date). */
    private static final LocalDate EXPIRED_EXPIRATION = LocalDate.of(2020, 1, 1);

    /** A card number with no cross-reference, used to drive the code 100 scenario. */
    private static final String UNKNOWN_CARD_NUM = "9999999999999999";

    // ------------------------------------------------------------------------
    // Testcontainers PostgreSQL 15
    // ------------------------------------------------------------------------

    /**
     * Dedicated PostgreSQL 15 container for the transaction-posting job. {@code @SuppressWarnings("resource")}
     * is applied because the {@code @Container}/{@code @Testcontainers} lifecycle (not a
     * try-with-resources block) owns container startup and shutdown.
     */
    @Container
    @SuppressWarnings("resource")
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>(
            DockerImageName.parse("postgres:15"))
            .withDatabaseName("carddemo_posttran_test")
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
    @Qualifier("transactionPostingJob")
    private Job transactionPostingJob;

    /** Repository used to seed accounts and read back updated balances / cycle buckets (PR-07). */
    @Autowired
    private AccountRepository accountRepository;

    /** Repository used to seed/clear the card cross-references that drive the XREF lookup (codes 100/101). */
    @Autowired
    private CardXrefRepository cardXrefRepository;

    /** Repository used to persist the DALYTRAN staging input rows consumed by the job's reader. */
    @Autowired
    private DailyTransactionRepository dailyTransactionRepository;

    /** Repository used to read back rejected transactions and assert validation codes/messages (PR-03). */
    @Autowired
    private RejectedTransactionRepository rejectedTransactionRepository;

    /** Repository used to read back accepted transactions posted to the {@code transactions} table. */
    @Autowired
    private TransactionRepository transactionRepository;

    /** Repository used to pre-seed and look up {@code tran_cat_balances} rows by composite key (PR-06). */
    @Autowired
    private TransactionCategoryBalanceRepository tcatbalRepository;

    /**
     * Direct JDBC access for seeding/clearing the {@code customers} and {@code cards} prerequisite
     * rows and for the code-101 constraint drop/restore. Neither a {@code CustomerRepository} nor a
     * {@code CardRepository} is on this test's dependency surface, yet both rows are required to
     * satisfy the {@code fk_xref_*} and {@code fk_transactions_card} foreign keys reached when the
     * job posts an accepted transaction.
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
     *   <li>{@code rejected_transactions} and {@code daily_transactions} (no inbound/outbound FK) next;</li>
     *   <li>{@code tran_cat_balances} (FK → {@code accounts}) and {@code card_xref}
     *       (FK → {@code cards}/{@code customers}/{@code accounts});</li>
     *   <li>{@code cards} (FK → {@code accounts}) via raw {@code DELETE};</li>
     *   <li>{@code accounts}, then {@code customers} via raw {@code DELETE}.</li>
     * </ol>
     * Finally the synchronous launcher and the job under test are installed onto
     * {@link JobLauncherTestUtils} (see the {@link #jobLauncher} field Javadoc).
     */
    @BeforeEach
    void setUp() {
        jobRepositoryTestUtils.removeJobExecutions();
        transactionRepository.deleteAll();
        rejectedTransactionRepository.deleteAll();
        dailyTransactionRepository.deleteAll();
        tcatbalRepository.deleteAll();
        cardXrefRepository.deleteAll();
        jdbcTemplate.update("DELETE FROM cards");
        accountRepository.deleteAll();
        jdbcTemplate.update("DELETE FROM customers");
        jobLauncherTestUtils.setJobLauncher(jobLauncher);
        jobLauncherTestUtils.setJob(transactionPostingJob);
    }

    // ------------------------------------------------------------------------
    // Helper methods
    // ------------------------------------------------------------------------

    /**
     * Launches the transaction-posting job inline. The job requires no business job parameters; a
     * single non-identifying {@code uniqueRunId} keeps each launch on its own Spring Batch
     * job-instance identity so re-launches across tests never collide.
     *
     * <p><strong>Why this method returns {@code void}.</strong> {@code @SpringBatchTest} registers a
     * {@code JobScopeTestExecutionListener} that, before each test, scans the test class for a method
     * returning {@link JobExecution} or {@link JobParameters} and invokes it with <em>no
     * arguments</em> to seed a {@code @JobScope} context. Keeping every helper's return type free of
     * those Spring Batch types means the listener finds nothing to seed, and the job is driven
     * exclusively by the explicit {@link JobLauncherTestUtils#launchJob(JobParameters)} call. Tests
     * that need to inspect the {@link JobExecution} (exit status, step read/write/commit counts)
     * build the parameters and launch inline via {@link #uniqueParameters()}, capturing the result
     * in a local variable (local variables are not scanned by the listener).</p>
     *
     * @throws Exception if the launcher rethrows a job failure
     */
    private void launch() throws Exception {
        JobExecution execution = jobLauncherTestUtils.launchJob(uniqueParameters());
        assertThat(execution.getExitStatus().getExitCode())
                .isEqualTo(ExitStatus.COMPLETED.getExitCode());
        assertThat(execution.getStatus()).isEqualTo(BatchStatus.COMPLETED);
    }

    /**
     * Builds a unique, non-identifying {@link JobParameters} for a single job launch. Returns
     * {@code JobParameters} (not {@code JobExecution}); the {@code @SpringBatchTest} scope listener
     * only seeds from {@code JobExecution}-returning methods, so this is safe.
     *
     * @return job parameters carrying a unique {@code uniqueRunId}
     */
    private JobParameters uniqueParameters() {
        return new JobParametersBuilder()
                .addLong("uniqueRunId", System.nanoTime())
                .toJobParameters();
    }

    /**
     * Renders the deterministic 16-character card number used for an account: the zero-padded
     * account id. Kept consistent between the {@code cards} master row, the {@code card_xref}
     * cross-reference, the daily-transaction input and the assertion side.
     *
     * @param acctId the account id
     * @return a 16-character numeric card number
     */
    private String cardNumFor(long acctId) {
        return String.format("%016d", acctId);
    }

    /**
     * Seeds (saves) an {@link Account} (ACCTFILE / {@code app/cpy/CVACT01Y.cpy}) with explicit credit
     * limit, balance, cycle buckets and expiration date. {@code expirationDate} is the
     * {@link LocalDate} compared against the daily-transaction date in the PR-05 expiration check.
     *
     * @param acctId      the account id (primary key)
     * @param creditLimit the credit limit ({@code ACCT-CREDIT-LIMIT}) used by the PR-04 over-limit check
     * @param currBal     the starting current balance ({@code ACCT-CURR-BAL})
     * @param cycCredit   the starting current-cycle credit ({@code ACCT-CURR-CYC-CREDIT})
     * @param cycDebit    the starting current-cycle debit ({@code ACCT-CURR-CYC-DEBIT})
     * @param expiration  the account expiration date ({@code ACCT-EXPIRAION-DATE})
     * @return the persisted account
     */
    private Account seedAccount(long acctId, BigDecimal creditLimit, BigDecimal currBal,
                                BigDecimal cycCredit, BigDecimal cycDebit, LocalDate expiration) {
        Account a = new Account();
        a.setAcctId(acctId);
        a.setActiveStatus("Y");
        a.setCurrBal(currBal);
        a.setCreditLimit(creditLimit);
        a.setCashCreditLimit(new BigDecimal("5000.00"));
        a.setOpenDate(LocalDate.of(2020, 1, 1));
        a.setExpirationDate(expiration);
        a.setReissueDate(LocalDate.of(2025, 1, 1));
        a.setCurrCycCredit(cycCredit);
        a.setCurrCycDebit(cycDebit);
        a.setAddrZip("12345");
        a.setGroupId("A");
        return accountRepository.save(a);
    }

    /**
     * Inserts a {@code customers} row (only its NOT-NULL columns) so a cross-reference can satisfy
     * {@code fk_xref_customer}. Uses {@link JdbcTemplate} because no {@code CustomerRepository} is on
     * this test's dependency surface.
     *
     * @param custId the customer id (primary key)
     */
    private void seedCustomerRow(long custId) {
        jdbcTemplate.update(
                "INSERT INTO customers (cust_id, first_name, last_name) VALUES (?, ?, ?)",
                custId, "Test", "Customer");
    }

    /**
     * Inserts a {@code cards} row (only its NOT-NULL columns) so a cross-reference can satisfy
     * {@code fk_xref_card} and an accepted transaction can satisfy {@code fk_transactions_card}. Must
     * be called <em>after</em> the referenced account exists ({@code cards.account_id} →
     * {@code accounts}).
     *
     * @param cardNum the 16-character card number (primary key)
     * @param acctId  the owning account id (FK → {@code accounts})
     */
    private void seedCardRow(String cardNum, long acctId) {
        jdbcTemplate.update(
                "INSERT INTO cards (card_num, account_id, cvv_cd, active_status) VALUES (?, ?, ?, ?)",
                cardNum, acctId, 123, "Y");
    }

    /**
     * Seeds (saves) one {@link CardXref} cross-reference (XREFFILE / {@code app/cpy/CVACT03Y.cpy})
     * mapping a card number to an account and customer.
     *
     * @param cardNum the cross-reference card number (primary key, {@code XREF-CARD-NUM})
     * @param acctId  the resolved account id ({@code XREF-ACCT-ID})
     * @param custId  the resolved customer id ({@code XREF-CUST-ID})
     * @return the persisted cross-reference
     */
    private CardXref seedXref(String cardNum, long acctId, long custId) {
        CardXref x = new CardXref();
        x.setXrefCardNum(cardNum);
        x.setAccountId(acctId);
        x.setCustId(custId);
        return cardXrefRepository.save(x);
    }

    /**
     * Seeds the full {@code account → customer → card → card_xref} chain for an account so that a
     * daily transaction keyed on {@link #cardNumFor(long)} resolves through the XREF to a present
     * account and, when accepted, posts a transaction that satisfies {@code fk_transactions_card}.
     * The {@code customers} and {@code cards} rows are inserted with {@link JdbcTemplate}; the
     * cross-reference is saved through {@link CardXrefRepository}.
     *
     * @param acctId      the account id; reused as the customer id and as the basis for the card number
     * @param creditLimit the credit limit ({@code ACCT-CREDIT-LIMIT})
     * @param currBal     the starting current balance ({@code ACCT-CURR-BAL})
     * @param cycCredit   the starting current-cycle credit ({@code ACCT-CURR-CYC-CREDIT})
     * @param cycDebit    the starting current-cycle debit ({@code ACCT-CURR-CYC-DEBIT})
     * @param expiration  the account expiration date ({@code ACCT-EXPIRAION-DATE})
     * @return the seeded card number
     */
    private String seedFullChain(long acctId, BigDecimal creditLimit, BigDecimal currBal,
                                 BigDecimal cycCredit, BigDecimal cycDebit, LocalDate expiration) {
        seedAccount(acctId, creditLimit, currBal, cycCredit, cycDebit, expiration);
        String cardNum = cardNumFor(acctId);
        seedCustomerRow(acctId);
        seedCardRow(cardNum, acctId);
        seedXref(cardNum, acctId, acctId);
        return cardNum;
    }

    /**
     * Builds and persists one {@link DailyTransaction} staging row (DALYTRAN / {@code app/cpy/CVTRA06Y.cpy})
     * representing the daily feed consumed by the job's reader. {@code processed} is set {@code false}
     * explicitly so the reader's {@code findByProcessedFalse} predicate selects it.
     *
     * @param tranId  the 16-character transaction id ({@code DALYTRAN-ID}; becomes {@code TRAN-ID})
     * @param cardNum the card number ({@code DALYTRAN-CARD-NUM}) keyed against the XREF
     * @param amount  the signed transaction amount ({@code DALYTRAN-AMT}; scale 2, PR-16)
     * @param origTs  the original timestamp ({@code DALYTRAN-ORIG-TS}); its date drives the PR-05 check
     * @param typeCd  the transaction type code ({@code DALYTRAN-TYPE-CD})
     * @param catCd   the transaction category code ({@code DALYTRAN-CAT-CD})
     * @return the persisted daily transaction
     */
    private DailyTransaction buildAndSaveDailyTran(String tranId, String cardNum, BigDecimal amount,
                                                   LocalDateTime origTs, String typeCd, String catCd) {
        DailyTransaction dt = new DailyTransaction();
        dt.setDalytranId(tranId);
        dt.setCardNum(cardNum);
        dt.setAmount(amount);
        dt.setOrigTimestamp(origTs);
        dt.setTypeCd(typeCd);
        dt.setCategoryCd(catCd);
        dt.setSource("Test");
        dt.setDescription("Test transaction");
        dt.setMerchantId(123L);
        dt.setMerchantName("Test Merchant");
        dt.setMerchantCity("City");
        dt.setMerchantZip("12345");
        dt.setProcessed(false);
        return dailyTransactionRepository.save(dt);
    }

    /**
     * Constructs the composite TCATBAL key ({@code accountId + typeCd + categoryCd}) used to look up
     * a {@link TransactionCategoryBalance} when asserting upsert results (PR-06/PR-15).
     *
     * @param acctId the owning account id
     * @param typeCd the fixed-width {@code type_cd}
     * @param catCd  the fixed-width {@code cat_cd}
     * @return the embeddable composite id
     */
    private TransactionCategoryBalanceId tcatbalId(long acctId, String typeCd, String catCd) {
        return new TransactionCategoryBalanceId(acctId, typeCd, catCd);
    }

    /**
     * Returns the single rejected transaction the job produced, failing if there is not exactly one.
     * Used by the validation-code tests, which each seed exactly one invalid daily transaction.
     *
     * @return the only {@link RejectedTransaction} in the table
     */
    private RejectedTransaction theOnlyRejected() {
        List<RejectedTransaction> all = rejectedTransactionRepository.findAll();
        assertThat(all).hasSize(1);
        return all.get(0);
    }

    // ========================================================================
    // PR-03/04/05 — Validation chain (CBTRN02C 1500-VALIDATE-TRAN, codes 100/101/102/103)
    // ========================================================================

    /**
     * Verifies the four CBTRN02C validation codes (100/101/102/103) and the accepted path, asserting
     * both the exact integer code and the exact COBOL reason message (PR-03), the PR-04 over-limit
     * formula and the PR-05 expiration comparison.
     */
    @Nested
    @DisplayName("CBTRN02C 1500-VALIDATE-TRAN: codes 100/101/102/103 + accepted path")
    class ValidationCodeTests {

        @Test
        @DisplayName("Code 100: INVALID CARD NUMBER FOUND when the XREF lookup misses")
        void shouldRejectWithCode100WhenCardNumberNotFound() throws Exception {
            // Arrange: a daily transaction whose card number has NO cross-reference at all. No
            // account/card/customer chain is seeded, so 1500-A-LOOKUP-XREF fails with INVALID KEY.
            buildAndSaveDailyTran("0000000000000100", UNKNOWN_CARD_NUM, new BigDecimal("10.00"),
                    ACCEPTED_ORIG_TS, TYPE_CD, CAT_CD);

            // Act
            launch();

            // Assert: nothing posted; exactly one reject carrying code 100 and the exact message.
            assertThat(transactionRepository.count()).isZero();
            RejectedTransaction rejected = theOnlyRejected();
            assertThat(rejected.getValidationCode()).isEqualTo(CODE_INVALID_CARD);
            assertThat(rejected.getRejectionReason()).isEqualTo(MSG_INVALID_CARD);
        }

        @Test
        @DisplayName("Code 101: ACCOUNT RECORD NOT FOUND when the XREF resolves to a missing account")
        void shouldRejectWithCode101WhenAccountNotFound() throws Exception {
            // The XREF must resolve to an account id that has no ACCTFILE row. card_xref normally
            // enforces fk_xref_account, so this single scenario drops that constraint while it seeds
            // an orphan cross-reference (acct 999), then restores it. fk_xref_card and
            // fk_xref_customer remain enforced, so a valid card + customer are still seeded.
            jdbcTemplate.execute("ALTER TABLE card_xref DROP CONSTRAINT IF EXISTS fk_xref_account");
            try {
                long realAcct = 1L;
                String cardNum = cardNumFor(realAcct);
                seedAccount(realAcct, new BigDecimal("5000.00"), new BigDecimal("0.00"),
                        new BigDecimal("0.00"), new BigDecimal("0.00"), FUTURE_EXPIRATION);
                seedCustomerRow(realAcct);
                seedCardRow(cardNum, realAcct);
                // Orphan cross-reference: card + customer exist, but account 999 does not.
                seedXref(cardNum, 999L, realAcct);
                buildAndSaveDailyTran("0000000000000101", cardNum, new BigDecimal("10.00"),
                        ACCEPTED_ORIG_TS, TYPE_CD, CAT_CD);

                // Act
                launch();

                // Assert
                assertThat(transactionRepository.count()).isZero();
                RejectedTransaction rejected = theOnlyRejected();
                assertThat(rejected.getValidationCode()).isEqualTo(CODE_ACCOUNT_NOT_FOUND);
                assertThat(rejected.getRejectionReason()).isEqualTo(MSG_ACCOUNT_NOT_FOUND);
            } finally {
                // Remove the orphan row, then restore the constraint for the remaining tests.
                jdbcTemplate.update("DELETE FROM card_xref");
                jdbcTemplate.execute("ALTER TABLE card_xref ADD CONSTRAINT fk_xref_account "
                        + "FOREIGN KEY (xref_acct_id) REFERENCES accounts (acct_id)");
            }
        }

        @Test
        @DisplayName("Code 102: OVERLIMIT TRANSACTION when credit_limit < (cyc_credit - cyc_debit + amt)")
        void shouldRejectWithCode102WhenOverlimit() throws Exception {
            // Arrange (PR-04): credit_limit=1000, cyc_credit=950, cyc_debit=0; amt=100 ⇒ projected
            // position = (950 - 0 + 100) = 1050 > 1000 ⇒ code 102. A future expiration keeps 103 quiet.
            String cardNum = seedFullChain(1L, new BigDecimal("1000.00"), new BigDecimal("0.00"),
                    new BigDecimal("950.00"), new BigDecimal("0.00"), FUTURE_EXPIRATION);
            buildAndSaveDailyTran("0000000000000102", cardNum, new BigDecimal("100.00"),
                    ACCEPTED_ORIG_TS, TYPE_CD, CAT_CD);

            // Act
            launch();

            // Assert
            assertThat(transactionRepository.count()).isZero();
            RejectedTransaction rejected = theOnlyRejected();
            assertThat(rejected.getValidationCode()).isEqualTo(CODE_OVERLIMIT);
            assertThat(rejected.getRejectionReason()).isEqualTo(MSG_OVERLIMIT);
        }

        @Test
        @DisplayName("Code 103: TRANSACTION RECEIVED AFTER ACCT EXPIRATION when expiration < tran_orig_ts(1:10)")
        void shouldRejectWithCode103WhenAccountExpired() throws Exception {
            // Arrange (PR-05): expiration 2020-01-01 is before the transaction date 2024-06-15. The
            // over-limit check passes (5000 >= 50) so 102 does not fire; 103 is the sole reject code.
            String cardNum = seedFullChain(1L, new BigDecimal("5000.00"), new BigDecimal("0.00"),
                    new BigDecimal("0.00"), new BigDecimal("0.00"), EXPIRED_EXPIRATION);
            buildAndSaveDailyTran("0000000000000103", cardNum, new BigDecimal("50.00"),
                    ACCEPTED_ORIG_TS, TYPE_CD, CAT_CD);

            // Act
            launch();

            // Assert
            assertThat(transactionRepository.count()).isZero();
            RejectedTransaction rejected = theOnlyRejected();
            assertThat(rejected.getValidationCode()).isEqualTo(CODE_EXPIRED);
            assertThat(rejected.getRejectionReason()).isEqualTo(MSG_EXPIRED);
        }

        @Test
        @DisplayName("Accepted: a valid transaction is posted to the TRANSACT (transactions) table")
        void shouldAcceptValidTransactionAndPersistToTransactionsTable() throws Exception {
            // Arrange: within credit limit and not expired ⇒ all four checks pass.
            String cardNum = seedFullChain(1L, new BigDecimal("5000.00"), new BigDecimal("0.00"),
                    new BigDecimal("0.00"), new BigDecimal("0.00"), FUTURE_EXPIRATION);
            buildAndSaveDailyTran("0000000000000001", cardNum, new BigDecimal("100.00"),
                    ACCEPTED_ORIG_TS, TYPE_CD, CAT_CD);

            // Act
            launch();

            // Assert: zero rejects; exactly one posted transaction with matching id/amount/card.
            assertThat(rejectedTransactionRepository.count()).isZero();
            List<Transaction> posted = transactionRepository.findAll();
            assertThat(posted).hasSize(1);
            Transaction tx = posted.get(0);
            assertThat(tx.getTranId()).isEqualTo("0000000000000001");
            assertThat(tx.getAmount()).isEqualByComparingTo(new BigDecimal("100.00"));
            assertThat(tx.getCardNum()).isEqualTo(cardNum);
        }
    }

    // ========================================================================
    // PR-06 — TCATBAL composite-key upsert (CBTRN02C 2700-UPDATE-TCATBAL)
    // ========================================================================

    /**
     * Verifies the {@code 2700-UPDATE-TCATBAL} composite-key upsert: INSERT a new balance row when
     * the {@code (account_id, type_cd, cat_cd)} key is absent (balance = amount), and UPDATE in place
     * ({@code balance += amount}) when it is present (PR-06).
     */
    @Nested
    @DisplayName("CBTRN02C 2700-UPDATE-TCATBAL: composite-key upsert (PR-06)")
    class TransactionCategoryBalanceUpsertTests {

        @Test
        @DisplayName("INSERT a new TCATBAL row when the composite (acct + type + cat) key is absent")
        void shouldCreateTcatbalWhenCompositeKeyNotExists() throws Exception {
            // Arrange: a valid transaction whose (acct, type, cat) balance row does not yet exist.
            String cardNum = seedFullChain(1L, new BigDecimal("5000.00"), new BigDecimal("0.00"),
                    new BigDecimal("0.00"), new BigDecimal("0.00"), FUTURE_EXPIRATION);
            buildAndSaveDailyTran("0000000000000006", cardNum, new BigDecimal("100.00"),
                    ACCEPTED_ORIG_TS, TYPE_CD, CAT_CD);

            // Act
            launch();

            // Assert: exactly one balance row was created, holding the transaction amount.
            assertThat(tcatbalRepository.count()).isEqualTo(1);
            Optional<TransactionCategoryBalance> created =
                    tcatbalRepository.findById(tcatbalId(1L, TYPE_CD, CAT_CD));
            assertThat(created).isPresent();
            assertThat(created.get().getTranCatBal()).isEqualByComparingTo(new BigDecimal("100.00"));
        }

        @Test
        @DisplayName("UPDATE an existing TCATBAL row (ADD DALYTRAN-AMT) when the composite key exists")
        void shouldUpdateExistingTcatbalWhenCompositeKeyExists() throws Exception {
            // Arrange: seed the account chain, then pre-seed a TCATBAL of 100.00 for the same
            // composite key (the FK to accounts requires the account to exist first).
            String cardNum = seedFullChain(1L, new BigDecimal("5000.00"), new BigDecimal("0.00"),
                    new BigDecimal("0.00"), new BigDecimal("0.00"), FUTURE_EXPIRATION);
            TransactionCategoryBalance pre = new TransactionCategoryBalance();
            pre.setId(tcatbalId(1L, TYPE_CD, CAT_CD));
            pre.setTranCatBal(new BigDecimal("100.00"));
            tcatbalRepository.save(pre);

            // A second transaction on the same key for 50.00 ⇒ 100.00 + 50.00 = 150.00.
            buildAndSaveDailyTran("0000000000000007", cardNum, new BigDecimal("50.00"),
                    ACCEPTED_ORIG_TS, TYPE_CD, CAT_CD);

            // Act
            launch();

            // Assert: no new row (still exactly one), and the balance was incremented in place.
            assertThat(tcatbalRepository.count()).isEqualTo(1);
            Optional<TransactionCategoryBalance> updated =
                    tcatbalRepository.findById(tcatbalId(1L, TYPE_CD, CAT_CD));
            assertThat(updated).isPresent();
            assertThat(updated.get().getTranCatBal()).isEqualByComparingTo(new BigDecimal("150.00"));
        }
    }

    // ========================================================================
    // PR-07 — Account sign-based bucketing (CBTRN02C 2800-UPDATE-ACCOUNT-REC)
    // ========================================================================

    /**
     * Verifies {@code 2800-UPDATE-ACCOUNT-REC}: {@code ACCT-CURR-BAL += DALYTRAN-AMT} always; a
     * non-negative amount adds to {@code ACCT-CURR-CYC-CREDIT}; a negative amount adds (signed) to
     * {@code ACCT-CURR-CYC-DEBIT}, driving it negative (PR-07).
     */
    @Nested
    @DisplayName("CBTRN02C 2800-UPDATE-ACCOUNT-REC: sign-based cycle bucketing (PR-07)")
    class AccountBalanceUpdateTests {

        @Test
        @DisplayName("Positive amount: curr_bal += amt and curr_cyc_credit += amt (debit unchanged)")
        void shouldAddToCurrCycCreditWhenAmountIsPositive() throws Exception {
            // Arrange: curr_bal=100, cyc_credit=50, cyc_debit=20; amt=+30.00. Projected position
            // (50 - 20 + 30) = 60 << 5000 limit ⇒ accepted.
            String cardNum = seedFullChain(1L, new BigDecimal("5000.00"), new BigDecimal("100.00"),
                    new BigDecimal("50.00"), new BigDecimal("20.00"), FUTURE_EXPIRATION);
            buildAndSaveDailyTran("0000000000000008", cardNum, new BigDecimal("30.00"),
                    ACCEPTED_ORIG_TS, TYPE_CD, CAT_CD);

            // Act
            launch();

            // Assert (PR-07, all PR-16 comparisons)
            assertThat(rejectedTransactionRepository.count()).isZero();
            Account updated = accountRepository.findById(1L).orElseThrow();
            assertThat(updated.getCurrBal()).isEqualByComparingTo(new BigDecimal("130.00"));
            assertThat(updated.getCurrCycCredit()).isEqualByComparingTo(new BigDecimal("80.00"));
            assertThat(updated.getCurrCycDebit()).isEqualByComparingTo(new BigDecimal("20.00"));
        }

        @Test
        @DisplayName("Negative amount: curr_bal += amt and curr_cyc_debit += amt (signed; credit unchanged)")
        void shouldAddToCurrCycDebitWhenAmountIsNegative() throws Exception {
            // Arrange: curr_bal=200, cyc_credit=50, cyc_debit=20; amt=-30.00. Projected position
            // (50 - 20 + (-30)) = 0 << 5000 limit ⇒ accepted. The COBOL ADD preserves sign, so the
            // debit bucket goes 20 + (-30) = -10.
            String cardNum = seedFullChain(1L, new BigDecimal("5000.00"), new BigDecimal("200.00"),
                    new BigDecimal("50.00"), new BigDecimal("20.00"), FUTURE_EXPIRATION);
            buildAndSaveDailyTran("0000000000000009", cardNum, new BigDecimal("-30.00"),
                    ACCEPTED_ORIG_TS, TYPE_CD, CAT_CD);

            // Act
            launch();

            // Assert (PR-07, all PR-16 comparisons)
            assertThat(rejectedTransactionRepository.count()).isZero();
            Account updated = accountRepository.findById(1L).orElseThrow();
            assertThat(updated.getCurrBal()).isEqualByComparingTo(new BigDecimal("170.00"));
            assertThat(updated.getCurrCycCredit()).isEqualByComparingTo(new BigDecimal("50.00"));
            assertThat(updated.getCurrCycDebit()).isEqualByComparingTo(new BigDecimal("-10.00"));
        }
    }

    // ========================================================================
    // Chunk-oriented processing (AAP §0.6.3 — chunk size 100)
    // ========================================================================

    /**
     * Verifies the single chunk-oriented step processes the whole feed in
     * {@link TransactionPostingJobConfig#CHUNK_SIZE}-sized commits. The process-indicator reader
     * re-queries the first unprocessed page after each chunk commit, so 250 rows drain as
     * 100 + 100 + 50 across three commits.
     */
    @Nested
    @DisplayName("Chunk-oriented processing (chunk size 100)")
    class ChunkProcessingTests {

        @Test
        @DisplayName("Should process 250 valid transactions in 3 chunks (100 + 100 + 50)")
        void shouldProcessMultipleTransactionsInChunksOf100() throws Exception {
            // Arrange: one account with a very large credit limit so the accumulating cycle-credit
            // bucket never trips the over-limit check across chunks, plus a future expiration. Seed
            // 250 valid unit-amount transactions, all on the one seeded card.
            String cardNum = seedFullChain(1L, new BigDecimal("1000000.00"), new BigDecimal("0.00"),
                    new BigDecimal("0.00"), new BigDecimal("0.00"), FUTURE_EXPIRATION);
            for (int i = 1; i <= 250; i++) {
                buildAndSaveDailyTran(String.format("%016d", i), cardNum, new BigDecimal("1.00"),
                        ACCEPTED_ORIG_TS, TYPE_CD, CAT_CD);
            }

            // Act: launch inline so the JobExecution / StepExecution counters can be inspected
            // (the result is a local variable, never scanned by the @SpringBatchTest scope listeners).
            JobExecution execution = jobLauncherTestUtils.launchJob(uniqueParameters());

            // Assert: completed, all 250 posted, and the single step read/wrote 250 across 3 commits.
            assertThat(execution.getExitStatus().getExitCode())
                    .isEqualTo(ExitStatus.COMPLETED.getExitCode());
            assertThat(transactionRepository.count()).isEqualTo(250);

            StepExecution step = execution.getStepExecutions().iterator().next();
            assertThat(step.getReadCount()).isEqualTo(250);
            assertThat(step.getWriteCount()).isEqualTo(250);
            // ceil(250 / 100) = 3 chunk commits.
            assertThat(step.getCommitCount()).isEqualTo(3);
        }
    }

    // ========================================================================
    // Job-level behavior
    // ========================================================================

    /**
     * Verifies job-level outcomes: a fully valid feed completes successfully with no rejects, and the
     * bean under test carries the expected job name.
     */
    @Nested
    @DisplayName("Job-level behavior")
    class JobLevelTests {

        @Test
        @DisplayName("ExitStatus == COMPLETED with zero rejects for an all-valid feed")
        void shouldCompleteJobWhenAllTransactionsValid() throws Exception {
            // Arrange: two valid transactions within limits and not expired.
            String cardNum = seedFullChain(1L, new BigDecimal("5000.00"), new BigDecimal("0.00"),
                    new BigDecimal("0.00"), new BigDecimal("0.00"), FUTURE_EXPIRATION);
            buildAndSaveDailyTran("0000000000000011", cardNum, new BigDecimal("25.00"),
                    ACCEPTED_ORIG_TS, TYPE_CD, CAT_CD);
            buildAndSaveDailyTran("0000000000000012", cardNum, new BigDecimal("75.00"),
                    ACCEPTED_ORIG_TS, TYPE_CD, CAT_CD);

            // Act
            JobExecution execution = jobLauncherTestUtils.launchJob(uniqueParameters());

            // Assert
            assertThat(execution.getExitStatus().getExitCode())
                    .isEqualTo(ExitStatus.COMPLETED.getExitCode());
            assertThat(execution.getStatus()).isEqualTo(BatchStatus.COMPLETED);
            assertThat(rejectedTransactionRepository.count()).isZero();
            assertThat(transactionRepository.count()).isEqualTo(2);
        }

        @Test
        @DisplayName("The job under test is the 'transactionPostingJob' bean")
        void shouldExposeJobNamedTransactionPostingJob() {
            assertThat(transactionPostingJob.getName())
                    .isEqualTo(TransactionPostingJobConfig.JOB_NAME);
            assertThat(transactionPostingJob.getName()).isEqualTo("transactionPostingJob");
        }
    }
}
