package com.carddemo.batch;

import java.io.File;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;
import java.util.regex.Pattern;

import org.junit.jupiter.api.AfterEach;
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
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import com.carddemo.entity.Account;
import com.carddemo.entity.Card;
import com.carddemo.entity.CardXref;
import com.carddemo.entity.Customer;
import com.carddemo.entity.Transaction;
import com.carddemo.repository.AccountRepository;
import com.carddemo.repository.CardRepository;
import com.carddemo.repository.CardXrefRepository;
import com.carddemo.repository.CustomerRepository;
import com.carddemo.repository.TransactionRepository;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Spring Batch integration test for {@link StatementGenerationJobConfig} — the
 * {@code statementGenerationJob} bean that is the Java/PostgreSQL replacement for the legacy
 * mainframe statement-creation flow {@code app/jcl/CREASTMT.JCL} driving
 * {@code app/cbl/CBSTM03A.CBL} (with I/O subroutine {@code app/cbl/CBSTM03B.CBL}),
 * CardDemo_v1.0-15-g27d6c6f-68.
 *
 * <h2>What the legacy flow did (source references)</h2>
 * <ul>
 *   <li>{@code app/jcl/CREASTMT.JCL} — four functional EXEC steps: <b>DELDEF01</b> (IDCAMS
 *       DELETE/DEFINE of a temporary VSAM staging KSDS), <b>STEP010</b> (DFSORT of the
 *       {@code TRANSACT} master by {@code TRAN-CARD-NUM} then {@code TRAN-ID}), <b>STEP020</b>
 *       (IDCAMS REPRO loading the sorted output into the staging cluster) and <b>STEP040</b>
 *       ({@code EXEC PGM=CBSTM03A}, the real statement emission).</li>
 *   <li>{@code app/cbl/CBSTM03A.CBL} — file definitions {@code STMT-FILE} (80-byte plain text) and
 *       {@code HTML-FILE} (100-byte HTML) (L1-L35); {@code MAINLINE} iterates the card
 *       cross-reference, reads the owning customer + account, and emits one statement per
 *       customer/account (L316-L342); {@code 5100-WRITE-HTML-HEADER} (L506-L555) defines the HTML
 *       header whose byte structure must be preserved (<b>PR-09</b>).</li>
 *   <li>{@code app/cbl/CBSTM03B.CBL} — the centralized open/close/read/write I/O subroutine.</li>
 *   <li>Record-defining copybooks {@code CVCUS01Y.cpy} (customer), {@code CVACT01Y.cpy} (account),
 *       {@code CVACT02Y.cpy} (card), {@code CVACT03Y.cpy} (card cross-reference),
 *       {@code CVTRA05Y.cpy} (transaction) and the statement-template constants
 *       {@code COSTM01.CPY}.</li>
 * </ul>
 *
 * <h2>System under test (modernized behavior)</h2>
 * <p>The {@code statementGenerationJob} chains <b>four</b> {@link org.springframework.batch.core.Step}
 * beans in the exact CREASTMT order — {@code statementPurgeStagingStep} (DELDEF01),
 * {@code statementSortStep} (STEP010), {@code statementLoadStagingStep} (STEP020) and
 * {@code statementEmissionStep} (STEP040) — where the first three are no-ops (PostgreSQL needs no
 * VSAM staging cluster, DFSORT or REPRO) and the fourth does the real work via
 * {@code StatementGenerationTasklet}. The tasklet streams the card cross-reference in
 * {@code (custId, accountId, xrefCardNum)} order, performs a control-break on
 * {@code (customer, account)} (aggregating every owned card's transactions), and writes one
 * <b>HTML</b> statement and one <b>plain-text</b> statement <em>file</em> per {@code (customer,
 * account)} pair into the directory resolved by {@code BatchOutputPathResolver} under the
 * {@code carddemo.batch.output.base-dir} property (default sub-directory {@code statements}). Each
 * file is named {@code statement-acct-NNNNNNNNNNN.html} / {@code .txt} where {@code NNNNNNNNNNN} is
 * the 11-digit zero-padded account id.</p>
 *
 * <h2>Refactoring rules exercised</h2>
 * <ul>
 *   <li><b>PR-09</b> — the emitted HTML preserves the {@code CBSTM03A 5100-WRITE-HTML-HEADER}
 *       structure (DOCTYPE, bank-info block, "Basic Details" / "Transaction Summary" tables and the
 *       "End of Statement" footer); the structural strings asserted here match the committed
 *       golden-master fixture {@code src/test/resources/fixtures/reference-statement.html} that
 *       {@code StatementGenerationParityTest} compares byte-for-byte.</li>
 *   <li><b>PR-16</b> — monetary amounts are exact {@link BigDecimal} scale-2 values; the plain-text
 *       statement renders cents verbatim (e.g. {@code 0.05}, {@code 100.50}) and the persisted
 *       amount is read back and compared with {@code isEqualByComparingTo}.</li>
 *   <li><b>PR-20</b> — the customer SSN never appears in plain form in either statement artifact.</li>
 *   <li><b>PR-22 / PR-28</b> — entities carry {@code @Version} optimistic locking and use the
 *       {@code jakarta.*} namespace; this test seeds/reads them through the production
 *       repositories.</li>
 *   <li>The 4-step chained-job shape (DELDEF01 &rarr; STEP010 &rarr; STEP020 &rarr; STEP040) is
 *       asserted, and one statement is emitted per customer/account (aggregating multiple cards).</li>
 * </ul>
 *
 * <h2>Test topology</h2>
 * <p>{@code @SpringBootTest} boots the full application context; {@code @SpringBatchTest} contributes
 * {@link JobLauncherTestUtils} / {@link JobRepositoryTestUtils}. A real PostgreSQL 15 database is
 * supplied by Testcontainers, Flyway applies every {@code V*.sql} migration (so the master tables
 * exist and foreign keys can be satisfied) and Hibernate validates the schema against the committed
 * DDL ({@code ddl-auto: validate}). No collaborators are mocked — {@code SecurityConfig} contributes
 * the real {@code PasswordEncoder} / {@code AuthenticationManager} beans required for the eager
 * instantiation of every {@code @Configuration}-declared {@link Job} under {@code @SpringBatchTest}.</p>
 *
 * <p><strong>Datasource note.</strong> {@link #registerDatabaseProperties(DynamicPropertyRegistry)}
 * binds this class's dedicated {@link #POSTGRES} container and explicitly overrides
 * {@code spring.datasource.driver-class-name} to {@code org.postgresql.Driver}: {@code
 * application-test.yml} configures the Testcontainers magic-URL {@code ContainerDatabaseDriver},
 * which rejects the plain {@code jdbc:postgresql://} URL returned by
 * {@link PostgreSQLContainer#getJdbcUrl()}. The same hook points
 * {@code carddemo.batch.output.base-dir} at a per-run temporary directory created in a static
 * initializer (so it is available before the context refreshes and {@code BatchOutputPathResolver}
 * reads the property — a static {@code @TempDir} is intentionally avoided because its injection
 * timing relative to context startup is not guaranteed).</p>
 *
 * <p><strong>Transactionality.</strong> The class is intentionally <em>not</em>
 * {@code @Transactional}: seeds must commit so the synchronous job launcher (and its own
 * transaction) observes them, and the files the job writes must be visible to the post-run
 * assertions. {@link #setUp()} clears Spring Batch metadata plus the {@code transactions},
 * {@code card_xref} and {@code cards} tables (the only tables with no inbound foreign key after the
 * leaf rows are removed — {@code accounts}/{@code customers} are intentionally left in place because
 * {@code tran_cat_balances} references {@code accounts}, and orphaned masters without a
 * cross-reference simply produce no statement), and empties the statement output directory so the
 * file-count assertions are deterministic across the methods that share the container.</p>
 *
 * @see StatementGenerationJobConfig
 * @see StatementGenerationTasklet
 * @see com.carddemo.businesslogic.StatementGenerationParityTest
 */
@SpringBootTest
@SpringBatchTest
@Testcontainers
@ActiveProfiles("test")
@DisplayName("CREASTMT → StatementGenerationJob (CBSTM03A) integration tests")
class StatementGenerationJobIT {

    // ------------------------------------------------------------------------
    // Output directory (created at class-load — see class Javadoc)
    // ------------------------------------------------------------------------

    /**
     * Per-run base output directory created in a static initializer so it is guaranteed to exist
     * before the Spring context refreshes and {@code BatchOutputPathResolver} reads
     * {@code carddemo.batch.output.base-dir} (a static {@code @TempDir} cannot guarantee that
     * ordering). The {@code statementGenerationJob} writes its statement files beneath this base in
     * the default {@link #STATEMENTS_SUBDIR} sub-directory.
     */
    private static final Path STATEMENT_BASE_DIR = createStatementBaseDir();

    /** Default sub-directory the tasklet writes statements to (the {@code outputDir} default). */
    private static final String STATEMENTS_SUBDIR = "statements";

    /**
     * Creates the per-run statement base directory. Invoked from the static initializer of
     * {@link #STATEMENT_BASE_DIR}; an {@link IOException} is fatal to class loading.
     *
     * @return an absolute temporary directory bounding all statement output
     */
    private static Path createStatementBaseDir() {
        try {
            return Files.createTempDirectory("carddemo-stmt-it-");
        } catch (IOException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    // ------------------------------------------------------------------------
    // Testcontainers PostgreSQL 15
    // ------------------------------------------------------------------------

    /**
     * Dedicated PostgreSQL 15 container for the statement-generation job.
     * {@code @SuppressWarnings("resource")} is applied because the
     * {@code @Container}/{@code @Testcontainers} lifecycle (not a try-with-resources block) owns
     * container startup and shutdown.
     */
    @Container
    @SuppressWarnings("resource")
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>(
            DockerImageName.parse("postgres:15"))
            .withDatabaseName("carddemo_statement_test")
            .withUsername("carddemo")
            .withPassword("test_password");

    /**
     * Binds the container's JDBC coordinates and the statement output base directory onto the
     * Spring {@code Environment} before the application context starts. The
     * {@code driver-class-name} override is mandatory (see class Javadoc) so the plain
     * {@code jdbc:postgresql://} URL is handled by the real PostgreSQL JDBC driver rather than the
     * Testcontainers magic-URL driver configured in {@code application-test.yml}.
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
        registry.add("carddemo.batch.output.base-dir", STATEMENT_BASE_DIR::toString);
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
     * the job runs inline so its committed files are visible to the post-run assertions. It is set
     * onto {@link JobLauncherTestUtils} explicitly in {@link #setUp()} because
     * {@code @SpringBatchTest}'s own {@code @Autowired(required = false)} setter silently skips
     * injection under that ambiguity.
     */
    @Autowired
    private JobLauncher jobLauncher;

    /** The system under test — selected by bean name so the correct {@link Job} is exercised. */
    @Autowired
    @Qualifier("statementGenerationJob")
    private Job statementGenerationJob;

    /** Repository used to seed/clear/read {@code accounts} master rows. */
    @Autowired
    private AccountRepository accountRepository;

    /** Repository used to seed/clear {@code cards} rows. */
    @Autowired
    private CardRepository cardRepository;

    /** Repository used to seed/clear {@code card_xref} junction rows (drives statement emission). */
    @Autowired
    private CardXrefRepository cardXrefRepository;

    /** Repository used to seed/clear {@code customers} master rows. */
    @Autowired
    private CustomerRepository customerRepository;

    /** Repository used to seed/clear/read {@code transactions} (statement line items). */
    @Autowired
    private TransactionRepository transactionRepository;

    /**
     * The configured statement output base directory (bound by
     * {@link #registerDatabaseProperties(DynamicPropertyRegistry)} to {@link #STATEMENT_BASE_DIR}).
     * Used to locate the emitted statement files under {@link #STATEMENTS_SUBDIR}.
     */
    @Value("${carddemo.batch.output.base-dir}")
    private String configuredBaseDir;

    // ------------------------------------------------------------------------
    // Lifecycle
    // ------------------------------------------------------------------------

    /**
     * Resets state before every test: clears Spring Batch metadata; removes the leaf tables
     * ({@code transactions} &rarr; {@code card_xref} &rarr; {@code cards}, in inbound-FK order);
     * empties the statement output directory; and binds the synchronous {@link #jobLauncher} and the
     * {@link #statementGenerationJob} onto {@link JobLauncherTestUtils}.
     *
     * @throws IOException if the statement output directory cannot be cleaned
     */
    @BeforeEach
    void setUp() throws IOException {
        jobRepositoryTestUtils.removeJobExecutions();
        transactionRepository.deleteAll();
        cardXrefRepository.deleteAll();
        cardRepository.deleteAll();
        cleanStatementsDir();
        jobLauncherTestUtils.setJobLauncher(jobLauncher);
        jobLauncherTestUtils.setJob(statementGenerationJob);
    }

    /**
     * Empties the statement output directory after every test so that no emitted file leaks into a
     * subsequent test sharing the same container and base directory.
     *
     * @throws IOException if the statement output directory cannot be cleaned
     */
    @AfterEach
    void tearDown() throws IOException {
        cleanStatementsDir();
    }

    // ------------------------------------------------------------------------
    // Seed helpers (use the verified entity setters; all NOT-NULL fields set)
    // ------------------------------------------------------------------------

    /**
     * Seeds (saves) a {@link Customer}. Sets all not-null fields ({@code custId}, {@code firstName},
     * {@code lastName}) plus the optional name/address/SSN/FICO fields exercised by the statement.
     * The seed data is deliberately free of HTML metacharacters ({@code < > & " '}) so it renders
     * verbatim in the HTML statement.
     *
     * @param custId    the customer id (use {@code >= 1000} to avoid colliding with V5 seed data)
     * @param firstName the first name (rendered in the statement)
     * @param lastName  the last name (rendered in the statement)
     * @param ssn       the 9-character SSN (must NOT surface in statement output — PR-20)
     * @return the persisted customer
     */
    private Customer seedCustomer(long custId, String firstName, String lastName, String ssn) {
        Customer c = new Customer();
        c.setCustId(custId);
        c.setFirstName(firstName);
        c.setLastName(lastName);
        c.setMiddleName("Q");
        c.setAddrLine1("123 Main St");
        c.setAddrLine2("Suite 100");
        c.setAddrLine3("Anytown");
        c.setStateCd("CA");
        c.setCountryCd("USA");
        c.setZipCd("90210");
        c.setPhoneNum1("5551234567");
        c.setPhoneNum2("5557654321");
        c.setSsn(ssn);
        c.setGovtIssuedId("DL0001");
        c.setEftAccountId("EFT0001");
        c.setPrimaryCardHolderInd("Y");
        c.setFicoScore(750);
        return customerRepository.save(c);
    }

    /**
     * Seeds (saves) an {@link Account}. Sets all not-null fields including the five scale-2
     * {@link BigDecimal} money fields; the optional date fields are left null.
     *
     * @param acctId      the account id (use {@code >= 1000} to avoid colliding with V5 seed data)
     * @param currBal     the current balance (rendered as the statement "Current Balance")
     * @param creditLimit the credit limit
     * @return the persisted account
     */
    private Account seedAccount(long acctId, BigDecimal currBal, BigDecimal creditLimit) {
        Account a = new Account();
        a.setAcctId(acctId);
        a.setActiveStatus("Y");
        a.setCurrBal(currBal);
        a.setCreditLimit(creditLimit);
        a.setCashCreditLimit(new BigDecimal("5000.00"));
        a.setCurrCycCredit(new BigDecimal("0.00"));
        a.setCurrCycDebit(new BigDecimal("0.00"));
        a.setAddrZip("90210");
        a.setGroupId("A");
        return accountRepository.save(a);
    }

    /**
     * Seeds (saves) a {@link Card} linked to an account. Sets all not-null fields ({@code cardNum},
     * {@code accountId}, {@code cvvCd} as a {@code Short}, {@code activeStatus}).
     *
     * @param cardNum      the 16-character card number (primary key)
     * @param acctId       the owning account id (FK to {@code accounts})
     * @param embossedName the embossed name
     * @return the persisted card
     */
    private Card seedCard(String cardNum, long acctId, String embossedName) {
        Card c = new Card();
        c.setCardNum(cardNum);
        c.setAccountId(acctId);
        c.setCvvCd((short) 123);
        c.setEmbossedName(embossedName);
        c.setActiveStatus("Y");
        return cardRepository.save(c);
    }

    /**
     * Seeds (saves) a {@link CardXref} junction row (card &rarr; account &rarr; customer linkage) —
     * the record the statement job iterates.
     *
     * @param cardNum the 16-character card number (primary key / FK to {@code cards})
     * @param acctId  the account id (FK to {@code accounts})
     * @param custId  the customer id (FK to {@code customers})
     * @return the persisted cross-reference
     */
    private CardXref seedXref(String cardNum, long acctId, long custId) {
        CardXref x = new CardXref();
        x.setXrefCardNum(cardNum);
        x.setCustId(custId);
        x.setAccountId(acctId);
        return cardXrefRepository.save(x);
    }

    /**
     * Seeds (saves) a {@link Transaction} (a statement line item). Sets all not-null fields
     * ({@code tranId}, {@code typeCd}, {@code categoryCd}, {@code amount}, {@code cardNum}); the
     * nullable {@code origTimestamp}/{@code procTimestamp} are intentionally left null (statement
     * ordering is by {@code tranId}, not by timestamp).
     *
     * @param tranId  the 16-character transaction id (primary key)
     * @param cardNum the owning card number (FK to {@code cards})
     * @param amount  the scale-2 monetary amount (PR-16)
     * @param desc    the transaction description (rendered in the statement)
     * @return the persisted transaction
     */
    private Transaction seedTransaction(String tranId, String cardNum, BigDecimal amount,
                                        String desc) {
        Transaction t = new Transaction();
        t.setTranId(tranId);
        t.setTypeCd("01");
        t.setCategoryCd("0005");
        t.setSource("POS");
        t.setDescription(desc);
        t.setAmount(amount);
        t.setMerchantId(987L);
        t.setMerchantName("Test Merchant");
        t.setMerchantCity("Anytown");
        t.setMerchantZip("54321");
        t.setCardNum(cardNum);
        return transactionRepository.save(t);
    }

    /**
     * Builds a 16-character (zero-padded) transaction id from a small integer seed.
     *
     * @param n the sequence seed
     * @return a 16-character transaction id (matches the {@code tran_id VARCHAR(16)} width)
     */
    private static String tranId(int n) {
        return String.format("%016d", n);
    }

    // ------------------------------------------------------------------------
    // Launch + output helpers
    // ------------------------------------------------------------------------

    /**
     * Builds unique, non-identifying {@link JobParameters} (a {@code run.id} nanosecond stamp) so
     * each launch is a fresh {@link JobExecution}; {@code outputDir} is intentionally omitted so the
     * tasklet uses its default {@link #STATEMENTS_SUBDIR} sub-directory under the configured base.
     *
     * @return unique job parameters for one launch
     */
    private JobParameters uniqueJobParameters() {
        return new JobParametersBuilder()
                .addLong("run.id", System.nanoTime())
                .toJobParameters();
    }

    // NOTE: there is intentionally NO helper method that returns JobExecution. Under
    // @SpringBatchTest, JobScopeTestExecutionListener scans the test class for ANY (even private)
    // no-argument method whose return type is JobExecution and eagerly invokes it during
    // beforeTestMethod — which runs BEFORE @BeforeEach installs the launcher — producing a
    // NullPointerException ("getJobLauncher() is null"). Each test therefore launches the job
    // inline via jobLauncherTestUtils.launchJob(uniqueJobParameters()); the uniqueJobParameters()
    // helper above is safe because it returns JobParameters, not JobExecution.

    /**
     * @return the directory the statement files are written to ({@code <base-dir>/statements})
     */
    private Path statementsDir() {
        return Paths.get(configuredBaseDir).resolve(STATEMENTS_SUBDIR);
    }

    /**
     * @param acctId the account id
     * @return the {@link File} for the HTML statement of the given account
     */
    private File htmlStatementFile(long acctId) {
        return statementsDir().resolve(String.format("statement-acct-%011d.html", acctId)).toFile();
    }

    /**
     * @param acctId the account id
     * @return the {@link File} for the plain-text statement of the given account
     */
    private File txtStatementFile(long acctId) {
        return statementsDir().resolve(String.format("statement-acct-%011d.txt", acctId)).toFile();
    }

    /**
     * Reads the plain-text statement for an account.
     *
     * @param acctId the account id
     * @return the file content
     * @throws IOException if the file cannot be read
     */
    private String readTxt(long acctId) throws IOException {
        return Files.readString(txtStatementFile(acctId).toPath());
    }

    /**
     * Reads the HTML statement for an account.
     *
     * @param acctId the account id
     * @return the file content
     * @throws IOException if the file cannot be read
     */
    private String readHtml(long acctId) throws IOException {
        return Files.readString(htmlStatementFile(acctId).toPath());
    }

    /**
     * Lists the statement files in {@link #statementsDir()} with the given extension.
     *
     * @param extension the file extension to match (e.g. {@code ".html"} or {@code ".txt"})
     * @return the matching file paths (empty if the directory does not yet exist)
     * @throws IOException if the directory cannot be listed
     */
    private List<Path> listStatementFiles(String extension) throws IOException {
        Path dir = statementsDir();
        if (!Files.exists(dir)) {
            return List.of();
        }
        try (var stream = Files.list(dir)) {
            return stream
                    .filter(p -> p.getFileName().toString().endsWith(extension))
                    .collect(Collectors.toList());
        }
    }

    /**
     * Empties the statement output directory (deletes regular files only), creating nothing.
     *
     * @throws IOException if the directory cannot be listed or a file cannot be deleted
     */
    private void cleanStatementsDir() throws IOException {
        Path dir = statementsDir();
        if (!Files.exists(dir)) {
            return;
        }
        List<Path> files;
        try (var stream = Files.list(dir)) {
            files = stream.collect(Collectors.toList());
        }
        for (Path p : files) {
            if (Files.isRegularFile(p)) {
                Files.deleteIfExists(p);
            }
        }
    }

    // =========================================================================
    // Job-level execution
    // =========================================================================

    @Nested
    @DisplayName("Job-level execution")
    class JobExecutionTests {

        @Test
        @DisplayName("Job ExitStatus == COMPLETED for canonical single-customer scenario")
        void shouldCompleteJobWithExitStatusCompleted() throws Exception {
            long custId = 1001L;
            long acctId = 1001L;
            String card = "1000000000001001";
            seedCustomer(custId, "JOHN", "DOE", "123456789");
            seedAccount(acctId, new BigDecimal("100.00"), new BigDecimal("5000.00"));
            seedCard(card, acctId, "JOHN DOE");
            seedXref(card, acctId, custId);
            seedTransaction(tranId(1), card, new BigDecimal("25.00"), "PURCHASE ONE");
            seedTransaction(tranId(2), card, new BigDecimal("50.00"), "PURCHASE TWO");
            seedTransaction(tranId(3), card, new BigDecimal("10.00"), "PURCHASE THREE");

            JobExecution execution = jobLauncherTestUtils.launchJob(uniqueJobParameters());

            assertThat(execution.getExitStatus()).isEqualTo(ExitStatus.COMPLETED);
            assertThat(execution.getStatus()).isEqualTo(BatchStatus.COMPLETED);
        }

        @Test
        @DisplayName("Should execute all 4 steps in CREASTMT order "
                + "(DELDEF01 → STEP010 → STEP020 → STEP040)")
        void shouldExecuteAllFourChainedStepsInOrder() throws Exception {
            long custId = 1002L;
            long acctId = 1002L;
            String card = "1000000000001002";
            seedCustomer(custId, "JANE", "ROE", "987654321");
            seedAccount(acctId, new BigDecimal("0.00"), new BigDecimal("1000.00"));
            seedCard(card, acctId, "JANE ROE");
            seedXref(card, acctId, custId);
            seedTransaction(tranId(10), card, new BigDecimal("12.34"), "MINIMAL TX");

            JobExecution execution = jobLauncherTestUtils.launchJob(uniqueJobParameters());

            assertThat(execution.getExitStatus()).isEqualTo(ExitStatus.COMPLETED);

            var stepExecutions = execution.getStepExecutions();
            assertThat(stepExecutions).hasSize(4);
            assertThat(stepExecutions)
                    .allSatisfy(se -> assertThat(se.getExitStatus()).isEqualTo(ExitStatus.COMPLETED));

            // Prove the EXACT CREASTMT step order (DELDEF01 -> STEP010 -> STEP020 -> STEP040).
            // execution.getStepExecutions() does not guarantee iteration order, so sort the executions
            // by their real execution order — start time, with the monotonically-increasing
            // step-execution id as a tiebreaker for steps that start within the same clock tick —
            // before asserting the precise sequence with containsExactly (order-sensitive).
            List<String> stepNames = stepExecutions.stream()
                    .sorted(Comparator.comparing(StepExecution::getStartTime)
                            .thenComparing(StepExecution::getId))
                    .map(StepExecution::getStepName)
                    .collect(Collectors.toList());
            assertThat(stepNames).containsExactly(
                    "statementPurgeStagingStep",   // DELDEF01 — purge prior output / recreate staging
                    "statementSortStep",           // STEP010  — sort by card + transaction id
                    "statementLoadStagingStep",    // STEP020  — load the sorted rows into staging
                    "statementEmissionStep");      // STEP040  — CBSTM03A statement emission
        }
    }

    // =========================================================================
    // Per-customer emission
    // =========================================================================

    @Nested
    @DisplayName("Per-customer statement emission")
    class PerCustomerEmissionTests {

        @Test
        @DisplayName("Should produce exactly one statement file per customer/account")
        void shouldEmitOneStatementPerCustomer() throws Exception {
            // Customer A (account 1003) — 5 transactions.
            seedCustomer(1003L, "ALICE", "SMITH", "111111111");
            seedAccount(1003L, new BigDecimal("100.00"), new BigDecimal("5000.00"));
            seedCard("1000000000001003", 1003L, "ALICE SMITH");
            seedXref("1000000000001003", 1003L, 1003L);
            for (int i = 0; i < 5; i++) {
                seedTransaction(tranId(300 + i), "1000000000001003",
                        new BigDecimal("10.00"), "A TXN " + i);
            }

            // Customer B (account 2003) — 3 transactions.
            seedCustomer(2003L, "BOB", "JONES", "222222222");
            seedAccount(2003L, new BigDecimal("200.00"), new BigDecimal("5000.00"));
            seedCard("2000000000002003", 2003L, "BOB JONES");
            seedXref("2000000000002003", 2003L, 2003L);
            for (int i = 0; i < 3; i++) {
                seedTransaction(tranId(400 + i), "2000000000002003",
                        new BigDecimal("20.00"), "B TXN " + i);
            }

            jobLauncherTestUtils.launchJob(uniqueJobParameters());

            assertThat(listStatementFiles(".html")).hasSize(2);
            assertThat(listStatementFiles(".txt")).hasSize(2);
            assertThat(htmlStatementFile(1003L)).exists();
            assertThat(txtStatementFile(1003L)).exists();
            assertThat(htmlStatementFile(2003L)).exists();
            assertThat(txtStatementFile(2003L)).exists();
        }

        @Test
        @DisplayName("Statement should include all transactions belonging to the customer's card")
        void shouldIncludeAllTransactionsForCustomerInStatement() throws Exception {
            long custId = 1004L;
            long acctId = 1004L;
            String card = "1000000000001004";
            seedCustomer(custId, "CARL", "DOE", "333333333");
            seedAccount(acctId, new BigDecimal("500.00"), new BigDecimal("5000.00"));
            seedCard(card, acctId, "CARL DOE");
            seedXref(card, acctId, custId);
            String[] descriptions = {
                "DESCRIPTION ALPHA", "DESCRIPTION BRAVO", "DESCRIPTION CHARLIE",
                "DESCRIPTION DELTA", "DESCRIPTION ECHO"
            };
            for (int i = 0; i < descriptions.length; i++) {
                seedTransaction(tranId(410 + i), card, new BigDecimal("15.00"), descriptions[i]);
            }

            jobLauncherTestUtils.launchJob(uniqueJobParameters());

            String txt = readTxt(acctId);
            assertThat(txt).contains(descriptions);
            for (int i = 0; i < descriptions.length; i++) {
                assertThat(txt).contains(tranId(410 + i));
            }
        }

        @Test
        @DisplayName("Should complete and still emit a statement for a customer with no transactions")
        void shouldHandleCustomerWithNoTransactionsGracefully() throws Exception {
            long custId = 1011L;
            long acctId = 1011L;
            String card = "1000000000001011";
            seedCustomer(custId, "DANA", "POE", "444444444");
            seedAccount(acctId, new BigDecimal("0.00"), new BigDecimal("1000.00"));
            seedCard(card, acctId, "DANA POE");
            seedXref(card, acctId, custId);
            // No transactions seeded.

            JobExecution execution = jobLauncherTestUtils.launchJob(uniqueJobParameters());

            assertThat(execution.getExitStatus()).isEqualTo(ExitStatus.COMPLETED);
            assertThat(htmlStatementFile(acctId)).exists();
            assertThat(txtStatementFile(acctId)).exists();
            String txt = readTxt(acctId);
            assertThat(txt).contains("START OF STATEMENT", "END OF STATEMENT");
        }

        @Test
        @DisplayName("Should aggregate transactions across multiple cards for the same account "
                + "into one statement")
        void shouldHandleMultipleCardsPerAccountAggregatingTransactions() throws Exception {
            long custId = 1012L;
            long acctId = 1012L;
            String card1 = "1000000000001012";
            String card2 = "2000000000001012";
            seedCustomer(custId, "EVAN", "ROW", "555555555");
            seedAccount(acctId, new BigDecimal("300.00"), new BigDecimal("5000.00"));
            seedCard(card1, acctId, "EVAN ROW ONE");
            seedCard(card2, acctId, "EVAN ROW TWO");
            seedXref(card1, acctId, custId);
            seedXref(card2, acctId, custId);
            seedTransaction(tranId(120), card1, new BigDecimal("11.00"), "CARD1 TX A");
            seedTransaction(tranId(121), card1, new BigDecimal("12.00"), "CARD1 TX B");
            seedTransaction(tranId(122), card1, new BigDecimal("13.00"), "CARD1 TX C");
            seedTransaction(tranId(123), card2, new BigDecimal("14.00"), "CARD2 TX A");
            seedTransaction(tranId(124), card2, new BigDecimal("15.00"), "CARD2 TX B");

            jobLauncherTestUtils.launchJob(uniqueJobParameters());

            // One statement per (customer, account) — both cards collapse into a single file.
            assertThat(listStatementFiles(".txt")).hasSize(1);
            assertThat(listStatementFiles(".html")).hasSize(1);
            String txt = readTxt(acctId);
            assertThat(txt).contains(
                    "CARD1 TX A", "CARD1 TX B", "CARD1 TX C", "CARD2 TX A", "CARD2 TX B");
        }
    }

    // =========================================================================
    // HTML + plain-text structure (PR-09)
    // =========================================================================

    @Nested
    @DisplayName("Statement structure (PR-09)")
    class HtmlAndPlainTextStructureTests {

        @Test
        @DisplayName("Should emit HTML statement preserving CBSTM03A 5100-WRITE-HTML-HEADER structure")
        void shouldEmitHtmlStatementWithCBSTM03AHeaderStructure() throws Exception {
            long custId = 1005L;
            long acctId = 1005L;
            String card = "1000000000001005";
            seedCustomer(custId, "JOHN", "DOE", "123456789");
            seedAccount(acctId, new BigDecimal("100.00"), new BigDecimal("5000.00"));
            seedCard(card, acctId, "JOHN DOE");
            seedXref(card, acctId, custId);
            seedTransaction(tranId(50), card, new BigDecimal("25.00"), "PURCHASE AT STORE");
            seedTransaction(tranId(51), card, new BigDecimal("50.00"), "PAYMENT THANK YOU");

            jobLauncherTestUtils.launchJob(uniqueJobParameters());

            File html = htmlStatementFile(acctId);
            assertThat(html).exists();
            String content = readHtml(acctId);
            assertThat(content).contains(
                    "<!DOCTYPE html>",
                    "<html lang=\"en\">",
                    "<title>HTML Table Layout</title>",
                    "Statement for Account Number",
                    "Bank of XYZ",
                    "410 Terry Ave N",
                    "Seattle WA 99999",
                    "Basic Details",
                    "Transaction Summary",
                    "End of Statement",
                    "</html>");
            // Customer name (rendered FIRST [MIDDLE] LAST) must appear.
            assertThat(content).contains("JOHN");
            assertThat(content).contains("DOE");
        }

        @Test
        @DisplayName("HTML output is a multi-line, line-oriented document (not a single blob)")
        void shouldEmitHtmlAsLineOrientedDocument() throws Exception {
            long custId = 1006L;
            long acctId = 1006L;
            String card = "1000000000001006";
            seedCustomer(custId, "FRAN", "KOE", "666666666");
            seedAccount(acctId, new BigDecimal("75.00"), new BigDecimal("5000.00"));
            seedCard(card, acctId, "FRAN KOE");
            seedXref(card, acctId, custId);
            seedTransaction(tranId(60), card, new BigDecimal("9.99"), "SINGLE TX");

            jobLauncherTestUtils.launchJob(uniqueJobParameters());

            String content = readHtml(acctId);
            String[] lines = content.split("\n", -1);
            // The CBSTM03A HTML scaffolding emits one element per line — many lines, not one blob.
            assertThat(lines.length).isGreaterThan(20);
            assertThat(lines[0]).isEqualTo("<!DOCTYPE html>");
            // Line-oriented (not COBOL FB LRECL=100 fixed-width): no line is pathologically long.
            assertThat(content.lines().allMatch(line -> line.length() <= 200)).isTrue();
        }

        @Test
        @DisplayName("Plain-text statement lines all fit within 80 columns "
                + "(CBSTM03A STMT-FILE LRECL=80)")
        void shouldEmitPlainTextStatementWith80ByteLines() throws Exception {
            long custId = 1007L;
            long acctId = 1007L;
            String card = "1000000000001007";
            seedCustomer(custId, "GREG", "LOE", "777777777");
            seedAccount(acctId, new BigDecimal("123.45"), new BigDecimal("5000.00"));
            seedCard(card, acctId, "GREG LOE");
            seedXref(card, acctId, custId);
            seedTransaction(tranId(70), card, new BigDecimal("19.95"), "SHORT DESC");
            // A description well beyond the 37-column transaction field to prove the row truncates
            // and the line still respects the 80-column STMT-FILE width.
            seedTransaction(tranId(71), card, new BigDecimal("250.00"),
                    "A VERY LONG TRANSACTION DESCRIPTION THAT EXCEEDS THIRTY SEVEN CHARACTERS");

            jobLauncherTestUtils.launchJob(uniqueJobParameters());

            String content = readTxt(acctId);
            assertThat(content).isNotEmpty();
            content.lines().forEach(line ->
                    assertThat(line.length())
                            .as("line must fit within 80 columns: '%s'", line)
                            .isLessThanOrEqualTo(80));
        }
    }

    // =========================================================================
    // Statement data integrity (PR-16, PR-20)
    // =========================================================================

    @Nested
    @DisplayName("Statement data integrity (PR-16, PR-20)")
    class StatementDataIntegrityTests {

        @Test
        @DisplayName("Statement should include the customer first + last name and address")
        void shouldDisplayCustomerNameAndAddressInStatement() throws Exception {
            long custId = 1008L;
            long acctId = 1008L;
            String card = "1000000000001008";
            seedCustomer(custId, "JOHN", "DOE", "123456789");
            seedAccount(acctId, new BigDecimal("100.00"), new BigDecimal("5000.00"));
            seedCard(card, acctId, "JOHN DOE");
            seedXref(card, acctId, custId);
            seedTransaction(tranId(80), card, new BigDecimal("42.00"), "ANY TX");

            jobLauncherTestUtils.launchJob(uniqueJobParameters());

            String txt = readTxt(acctId);
            assertThat(txt).contains("JOHN");
            assertThat(txt).contains("DOE");
            assertThat(txt).contains("123 Main St");
        }

        @Test
        @DisplayName("Transaction amounts render with scale 2 / cents preserved (PR-16)")
        void shouldDisplayTransactionAmountsPreservingScale() throws Exception {
            long custId = 1009L;
            long acctId = 1009L;
            String card = "1000000000001009";
            seedCustomer(custId, "HOPE", "MOE", "888888888");
            seedAccount(acctId, new BigDecimal("0.00"), new BigDecimal("5000.00"));
            seedCard(card, acctId, "HOPE MOE");
            seedXref(card, acctId, custId);
            seedTransaction(tranId(91), card, new BigDecimal("100.50"), "TX ONE");
            seedTransaction(tranId(92), card, new BigDecimal("25.99"), "TX TWO");
            seedTransaction(tranId(93), card, new BigDecimal("0.05"), "TX THREE");

            jobLauncherTestUtils.launchJob(uniqueJobParameters());

            // The plain-text renderer prints amounts with exactly two decimals (cents preserved).
            String txt = readTxt(acctId);
            assertThat(txt).contains("100.50");
            assertThat(txt).contains("25.99");
            assertThat(txt).contains("0.05");
            // Guard against scientific notation or stray-scale formatting of the smallest amount.
            assertThat(txt).doesNotContain("5E-2");
            assertThat(txt).doesNotContain("0.0500");

            // PR-16 read-back: the persisted amount keeps scale 2 and compares by value.
            Optional<Transaction> persisted = transactionRepository.findById(tranId(91));
            assertThat(persisted).isPresent();
            assertThat(persisted.get().getAmount()).isEqualByComparingTo(new BigDecimal("100.50"));
            assertThat(persisted.get().getAmount().scale()).isEqualTo(2);
        }

        @Test
        @DisplayName("PR-20: customer SSN must NOT appear in plain form in statement output")
        void shouldNotIncludePlainTextSsnInStatement() throws Exception {
            long custId = 1010L;
            long acctId = 1010L;
            String card = "1000000000001010";
            String ssn = "123456789";
            seedCustomer(custId, "IRIS", "NOE", ssn);
            seedAccount(acctId, new BigDecimal("100.00"), new BigDecimal("5000.00"));
            seedCard(card, acctId, "IRIS NOE");
            seedXref(card, acctId, custId);
            seedTransaction(tranId(100), card, new BigDecimal("33.33"), "ANY TX");

            jobLauncherTestUtils.launchJob(uniqueJobParameters());

            String txt = readTxt(acctId);
            String html = readHtml(acctId);
            // The raw 9-digit SSN must not appear in either artifact.
            assertThat(txt).doesNotContain(ssn);
            assertThat(html).doesNotContain(ssn);
            // Nor any dashed SSN pattern (defensive — CBSTM03A never emits the SSN at all).
            Pattern ssnPattern = Pattern.compile("\\d{3}-\\d{2}-\\d{4}");
            assertThat(ssnPattern.matcher(txt).find()).isFalse();
            assertThat(ssnPattern.matcher(html).find()).isFalse();
        }

        @Test
        @DisplayName("Should generate a statement for an account with zero balance")
        void shouldGenerateStatementForZeroBalanceAccount() throws Exception {
            long custId = 1013L;
            long acctId = 1013L;
            String card = "1000000000001013";
            seedCustomer(custId, "JACK", "OWE", "999999999");
            seedAccount(acctId, new BigDecimal("0.00"), new BigDecimal("5000.00"));
            seedCard(card, acctId, "JACK OWE");
            seedXref(card, acctId, custId);
            seedTransaction(tranId(130), card, new BigDecimal("0.00"), "ZERO TX");

            JobExecution execution = jobLauncherTestUtils.launchJob(uniqueJobParameters());

            assertThat(execution.getExitStatus()).isEqualTo(ExitStatus.COMPLETED);
            assertThat(txtStatementFile(acctId)).exists();
            String content = readTxt(acctId);
            assertThat(content).contains("Current Balance :");
            assertThat(content).contains("0.00");

            // PR-16 read-back: zero balance is stored at scale 2.
            Optional<Account> persisted = accountRepository.findById(acctId);
            assertThat(persisted).isPresent();
            assertThat(persisted.get().getCurrBal()).isEqualByComparingTo(new BigDecimal("0.00"));
        }
    }
}
