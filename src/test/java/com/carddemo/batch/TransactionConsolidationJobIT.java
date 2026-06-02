package com.carddemo.batch;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
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
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.data.domain.Sort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import com.carddemo.entity.Transaction;
import com.carddemo.repository.TransactionRepository;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Spring Batch integration test for {@link TransactionConsolidationJobConfig} — the
 * {@code transactionConsolidationJob} bean that is the Java/PostgreSQL replacement for the
 * legacy JCL job {@code app/jcl/COMBTRAN.jcl}.
 *
 * <h2>What the original COMBTRAN.jcl did</h2>
 * The mainframe COMBTRAN job consolidated transactions in two chained steps:
 * <ol>
 *   <li><b>STEP05R</b> ({@code PGM=SORT}) concatenated the transaction-master backup
 *       ({@code TRANSACT.BKUP(0)}) and the system-generated transactions ({@code SYSTRAN(0)}),
 *       then sorted the merged stream ascending by {@code TRAN-ID}
 *       ({@code SORT FIELDS=(TRAN-ID,A)}; key at position 1, length 16, character) into a new
 *       generation {@code TRANSACT.COMBINED(+1)}.</li>
 *   <li><b>STEP10</b> ({@code PGM=IDCAMS}) {@code REPRO}'d the sorted combined file into the
 *       transaction-master VSAM KSDS ({@code TRANSACT.VSAM.KSDS}).</li>
 * </ol>
 * The {@code TRAN-RECORD} layout being consolidated is the 350-byte structure defined by
 * {@code app/cpy/CVTRA05Y.cpy} (primary key {@code TRAN-ID PIC X(16)}).
 *
 * <p><strong>Note on CBTRN03C.cbl.</strong> Although {@code CBTRN03C.cbl} is listed among the
 * COMBTRAN source references, that program is in fact the transaction <em>detail report</em>
 * printer ("Function: Print the transaction detail report"). COMBTRAN.jcl itself invokes only
 * DFSORT and IDCAMS — there is no COBOL program in the consolidation pipeline. The report
 * behavior of CBTRN03C is migrated separately by {@code TransactionReportJobConfig}; this test
 * deliberately exercises only the consolidation job.</p>
 *
 * <h2>What the modernized job does (system under test)</h2>
 * The DFSORT + IDCAMS REPRO pipeline collapses into a single idempotent SQL UPSERT executed in
 * one Spring Batch tasklet:
 * <pre>{@code
 * INSERT INTO transactions (...) SELECT ... FROM daily_transactions dt
 *   WHERE dt.processed = TRUE
 *     AND NOT EXISTS (SELECT 1 FROM transactions t WHERE t.tran_id = dt.tran_id)
 *     AND NOT EXISTS (SELECT 1 FROM rejected_transactions r WHERE r.tran_id = dt.tran_id)
 *   ON CONFLICT (tran_id) DO NOTHING
 * }</pre>
 * Consequently the observable contract verified here is:
 * <ul>
 *   <li>only {@code processed = TRUE} staging rows are consolidated;</li>
 *   <li>staging rows whose {@code tran_id} was rejected by {@code POSTTRAN} (and therefore reside in
 *       {@code rejected_transactions}) are NEVER consolidated into the master — preserving reject
 *       segregation (PR-03 / finding F2-001), verified by the {@code RejectSegregation} group;</li>
 *   <li>the {@code SORT FIELDS=(TRAN-ID,A)} ordering is preserved by the {@code transactions}
 *       primary-key B-tree (asserted via {@code findAll(Sort.by("tranId"))});</li>
 *   <li>{@code ON CONFLICT DO NOTHING} together with the {@code NOT EXISTS} guard preserves any
 *       pre-existing master row (the upstream {@code POSTTRAN} write is never clobbered — PR-22)
 *       and makes reruns idempotent (no duplicate {@code tran_id});</li>
 *   <li>all business fields are copied verbatim (amount compared with
 *       {@link org.assertj.core.api.AbstractBigDecimalAssert#isEqualByComparingTo} per PR-16).</li>
 * </ul>
 *
 * <h2>Test topology</h2>
 * <p>{@code @SpringBootTest} boots the full application context; {@code @SpringBatchTest}
 * contributes {@link JobLauncherTestUtils} / {@link JobRepositoryTestUtils}. A real PostgreSQL 15
 * database is supplied by Testcontainers, Flyway applies every {@code V*.sql} migration (so the
 * {@code cards} master is seeded and the {@code transactions.card_num} foreign key can be
 * satisfied), and Hibernate validates the schema against the committed DDL. The job is driven
 * against production wiring rather than mocks.</p>
 *
 * <p><strong>Datasource note.</strong> The {@code @DynamicPropertySource} below binds this class's
 * dedicated {@link #POSTGRES} container and explicitly overrides
 * {@code spring.datasource.driver-class-name} to {@code org.postgresql.Driver}. The override is
 * mandatory because {@code application-test.yml} configures the Testcontainers
 * {@code ContainerDatabaseDriver} (which only accepts {@code jdbc:tc:} magic URLs); the plain
 * {@code jdbc:postgresql://} URL returned by {@link PostgreSQLContainer#getJdbcUrl()} must be
 * handled by the real PostgreSQL driver. This mirrors the canonical pattern in
 * {@code com.carddemo.integration.SecurityIT}.</p>
 *
 * <p><strong>Staging seed.</strong> The job reads its input from the {@code daily_transactions}
 * staging table ({@code processed = TRUE}). There is no {@code DailyTransactionRepository} on this
 * test's dependency surface, so staging rows are inserted directly with {@link JdbcTemplate} (as
 * anticipated by the file specification). Because the consolidation SQL copies the staged
 * {@code card_num} into {@code transactions} (which carries the {@code fk_transactions_card}
 * foreign key), every seeded {@code card_num} is one of the V5-seeded card numbers.</p>
 *
 * <p><strong>Transactionality.</strong> The class is intentionally <em>not</em> {@code @Transactional}:
 * seeds must commit so the synchronous job launcher (and its own transaction) observes them, and
 * the committed results must be visible to the post-run assertions — matching {@code SecurityIT}.
 * {@link #setUp()} therefore clears Spring Batch metadata, the {@code transactions} master, and the
 * {@code daily_transactions} staging table before every test to guarantee isolation.</p>
 *
 * @see TransactionConsolidationJobConfig
 * @see com.carddemo.entity.Transaction
 * @see com.carddemo.repository.TransactionRepository
 */
@SpringBootTest
@SpringBatchTest
@Testcontainers
@ActiveProfiles("test")
@DisplayName("COMBTRAN → TransactionConsolidationJob integration tests")
class TransactionConsolidationJobIT {

    // ------------------------------------------------------------------------
    // Valid V5-seeded card numbers (transactions.card_num → cards.card_num FK)
    // ------------------------------------------------------------------------

    /** Card seeded by {@code V5__seed_master_data.sql} for account 1. */
    private static final String CARD_ACCT_1 = "9680294154603697";

    /** Card seeded by {@code V5__seed_master_data.sql} for account 2. */
    private static final String CARD_ACCT_2 = "0923877193247330";

    /** Card seeded by {@code V5__seed_master_data.sql} for account 3. */
    private static final String CARD_ACCT_3 = "3999169246375885";

    /** Card seeded by {@code V5__seed_master_data.sql} for account 5. */
    private static final String CARD_ACCT_5 = "6009619150674526";

    /** Card seeded by {@code V5__seed_master_data.sql} for account 50. */
    private static final String CARD_ACCT_50 = "0500024453765740";

    /** Fixed-width {@code transaction_types.tran_type} code ({@code CHAR(2)}). */
    private static final String TYPE_CD = "01";

    /** Fixed-width {@code transaction_categories.cat_cd} code ({@code CHAR(4)}). */
    private static final String CAT_CD = "0005";

    // ------------------------------------------------------------------------
    // Testcontainers PostgreSQL 15
    // ------------------------------------------------------------------------

    /**
     * Dedicated PostgreSQL 15 container for the consolidation job. {@code @SuppressWarnings("resource")}
     * is applied because the {@code @Container}/{@code @Testcontainers} lifecycle (not a
     * try-with-resources block) owns container startup and shutdown.
     */
    @Container
    @SuppressWarnings("resource")
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>(
            DockerImageName.parse("postgres:15"))
            .withDatabaseName("carddemo_consolidation_test")
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
     * and {@code BatchConfig}'s non-primary {@code asyncJobLauncher} — so neither is unambiguous by
     * type. Naming this field {@code jobLauncher} resolves it by bean name to the synchronous one
     * (exactly as {@code BatchConfig} itself does), guaranteeing the job runs inline so its
     * committed results are visible to the post-run assertions. It is set onto
     * {@link JobLauncherTestUtils} explicitly in {@link #setUp()} because {@code @SpringBatchTest}'s
     * own {@code @Autowired(required = false)} setter silently skips injection under that ambiguity,
     * which would otherwise leave {@link JobLauncherTestUtils#getJobLauncher()} {@code null}.
     */
    @Autowired
    private JobLauncher jobLauncher;

    /** The system under test — selected by bean name so the correct {@link Job} is exercised. */
    @Autowired
    @Qualifier("transactionConsolidationJob")
    private Job transactionConsolidationJob;

    /** Repository used to seed/clear/count/fetch rows in the {@code transactions} master table. */
    @Autowired
    private TransactionRepository transactionRepository;

    /**
     * Direct JDBC access for seeding the {@code daily_transactions} staging table (the job's input
     * source). Used because no {@code DailyTransactionRepository} is available to this test.
     */
    @Autowired
    private JdbcTemplate jdbcTemplate;

    // ------------------------------------------------------------------------
    // Context-completion collaborators (mocked)
    // ------------------------------------------------------------------------

    /**
     * Mock {@link PasswordEncoder} contributed so the full {@code @SpringBootTest} context can
     * start. {@code @SpringBatchTest} forces every {@code @Configuration}-declared {@link Job}
     * bean to be instantiated eagerly (via the {@code JobRegistryBeanPostProcessor}); one of those,
     * {@code com.carddemo.batch.UserSeedingJobConfig}, constructor-injects a {@link PasswordEncoder}
     * (it BCrypt-hashes {@code "PASSWORD"} for the 10 default users — PR-17). That encoder would
     * normally be supplied by {@code SecurityConfig}, which is not present in the current partial
     * source tree, so without this mock the context fails to load with
     * {@code NoSuchBeanDefinitionException}.
     *
     * <p>The consolidation job under test never encodes a password, so a Mockito mock is sufficient
     * — it is held by {@code UserSeedingJobConfig} but never invoked here. {@code @MockBean} is used
     * (rather than a nested {@code @TestConfiguration}) precisely because it is honored by JUnit 5
     * {@code @Nested} test classes under the default {@code @NestedTestConfiguration(ENCLOSING_CLASS)}
     * semantics, so the three nested scenario groups below all inherit it.</p>
     */
    @MockBean
    private PasswordEncoder passwordEncoder;

    /**
     * Mock {@link AuthenticationManager} contributed for the same reason as {@link #passwordEncoder}:
     * {@code com.carddemo.service.AuthService} constructor-injects an {@link AuthenticationManager}
     * that {@code SecurityConfig} would normally expose. It is irrelevant to transaction
     * consolidation (no principal is ever authenticated by this test), so a mock that is never
     * invoked satisfies the dependency and lets the context start.
     */
    @MockBean
    private AuthenticationManager authenticationManager;

    // ------------------------------------------------------------------------
    // Per-test setup
    // ------------------------------------------------------------------------

    /**
     * Resets all state the job interacts with before each test:
     * <ul>
     *   <li>{@code jobRepositoryTestUtils.removeJobExecutions()} clears prior Spring Batch
     *       executions so each test launches a clean job instance;</li>
     *   <li>{@code transactionRepository.deleteAll()} empties the {@code transactions} master
     *       (V5 seeds {@code cards} but not {@code transactions}, so this normally removes only
     *       rows created by a previous test);</li>
     *   <li>a raw {@code DELETE FROM daily_transactions} empties the staging table — mandatory,
     *       because {@code deleteAll()} does not touch the staging table the job actually reads,
     *       and leftover {@code processed = TRUE} rows would otherwise bleed across tests;</li>
     *   <li>a raw {@code DELETE FROM rejected_transactions} empties the reject sink — mandatory for
     *       the {@code RejectSegregation} scenarios, which seed rejected rows the consolidation SQL
     *       must exclude; leftover rejects would otherwise bleed across tests;</li>
     *   <li>{@code jobLauncherTestUtils.setJobLauncher(jobLauncher)} installs the synchronous
     *       launcher explicitly (see the {@link #jobLauncher} field Javadoc — {@code @SpringBatchTest}
     *       cannot auto-resolve it because two {@link JobLauncher} beans exist);</li>
     *   <li>{@code jobLauncherTestUtils.setJob(...)} points the launcher at the consolidation job
     *       (the field is not autowired by {@code @SpringBatchTest} and must be set explicitly).</li>
     * </ul>
     */
    @BeforeEach
    void setUp() {
        jobRepositoryTestUtils.removeJobExecutions();
        transactionRepository.deleteAll();
        jdbcTemplate.update("DELETE FROM daily_transactions");
        jdbcTemplate.update("DELETE FROM rejected_transactions");
        jobLauncherTestUtils.setJobLauncher(jobLauncher);
        jobLauncherTestUtils.setJob(transactionConsolidationJob);
    }

    // ------------------------------------------------------------------------
    // Helper methods
    // ------------------------------------------------------------------------

    /**
     * Builds a transient {@link Transaction} entity with sensible defaults for the
     * non-specified fields, ready to be persisted via {@link TransactionRepository} to model a
     * pre-existing master row.
     *
     * @param id      the 16-character transaction id (primary key)
     * @param amount  the monetary amount (held as {@link BigDecimal}, scale 2 — PR-16)
     * @param cardNum the owning card number (must satisfy the {@code fk_transactions_card} FK)
     * @return a populated but unsaved {@link Transaction}
     */
    private Transaction buildTransaction(String id, BigDecimal amount, String cardNum) {
        Transaction t = new Transaction();
        t.setTranId(id);
        t.setTypeCd(TYPE_CD);
        t.setCategoryCd(CAT_CD);
        t.setSource("POS");
        t.setDescription("Pre-existing master row");
        t.setAmount(amount);
        t.setCardNum(cardNum);
        t.setOrigTimestamp(LocalDateTime.now());
        t.setProcTimestamp(LocalDateTime.now());
        return t;
    }

    /**
     * Inserts a {@code processed = TRUE} row into the {@code daily_transactions} staging table
     * with default type/category/source/description and current timestamps. This row is eligible
     * for consolidation by the job under test.
     *
     * @param tranId  the 16-character transaction id
     * @param amount  the monetary amount
     * @param cardNum the owning card number (must be a V5-seeded card for the downstream FK)
     */
    private void seedProcessedDailyTransaction(String tranId, BigDecimal amount, String cardNum) {
        seedDailyTransaction(tranId, amount, cardNum, true);
    }

    /**
     * Inserts a row into the {@code daily_transactions} staging table with an explicit
     * {@code processed} flag. Only the columns required by the consolidation {@code SELECT}
     * (plus the two NOT-NULL staging columns) are populated; nullable columns are omitted so no
     * Java {@code null} is bound (which keeps PostgreSQL parameter typing unambiguous).
     *
     * @param tranId    the 16-character transaction id
     * @param amount    the monetary amount
     * @param cardNum   the owning card number
     * @param processed whether the staging row is marked processed (only processed rows are
     *                  consolidated)
     */
    private void seedDailyTransaction(String tranId, BigDecimal amount, String cardNum,
            boolean processed) {
        jdbcTemplate.update(
                "INSERT INTO daily_transactions "
                        + "(tran_id, type_cd, cat_cd, source, description, amount, card_num, "
                        + "orig_timestamp, proc_timestamp, processed) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                tranId, TYPE_CD, CAT_CD, "POS", "Consolidation test", amount, cardNum,
                LocalDateTime.now(), LocalDateTime.now(), processed);
    }

    /**
     * Inserts a fully-populated {@code processed = TRUE} staging row (including merchant fields),
     * used by the field-preservation test to assert every business column is copied verbatim.
     *
     * @param tranId       the 16-character transaction id
     * @param typeCd       the 2-character transaction type code
     * @param catCd        the 4-character transaction category code
     * @param source       the transaction source
     * @param description  the transaction description
     * @param amount       the monetary amount
     * @param merchantId   the merchant id
     * @param merchantName the merchant name
     * @param merchantCity the merchant city
     * @param merchantZip  the merchant zip
     * @param cardNum      the owning card number
     */
    private void seedFullProcessedDailyTransaction(String tranId, String typeCd, String catCd,
            String source, String description, BigDecimal amount, Long merchantId,
            String merchantName, String merchantCity, String merchantZip, String cardNum) {
        jdbcTemplate.update(
                "INSERT INTO daily_transactions "
                        + "(tran_id, type_cd, cat_cd, source, description, amount, merchant_id, "
                        + "merchant_name, merchant_city, merchant_zip, card_num, orig_timestamp, "
                        + "proc_timestamp, processed) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, TRUE)",
                tranId, typeCd, catCd, source, description, amount, merchantId,
                merchantName, merchantCity, merchantZip, cardNum,
                LocalDateTime.now(), LocalDateTime.now());
    }

    /**
     * Inserts a row into the {@code rejected_transactions} sink (the DALYREJS equivalent) modelling
     * a transaction the upstream {@code POSTTRAN} job rejected. The two {@code NOT NULL} reject
     * columns ({@code validation_code}, {@code rejection_reason}) are populated; {@code rejected_date}
     * defaults to {@code CURRENT_TIMESTAMP}. Used by the {@code RejectSegregation} scenarios to assert
     * that the consolidation job excludes any staging row whose {@code tran_id} also appears here
     * (finding F2-001 / PR-03 reject segregation).
     *
     * @param tranId         the 16-character transaction id (the {@code tran_id} column shared with
     *                       {@code daily_transactions} and {@code transactions})
     * @param amount         the monetary amount
     * @param cardNum        the owning card number
     * @param validationCode the {@code CBTRN02C} validation code (100/101/102/103)
     * @param reason         the exact COBOL rejection message
     */
    private void seedRejectedTransaction(String tranId, BigDecimal amount, String cardNum,
            int validationCode, String reason) {
        jdbcTemplate.update(
                "INSERT INTO rejected_transactions "
                        + "(tran_id, type_cd, cat_cd, source, description, amount, card_num, "
                        + "orig_timestamp, proc_timestamp, validation_code, rejection_reason) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                tranId, TYPE_CD, CAT_CD, "POS", "Rejected by POSTTRAN", amount, cardNum,
                LocalDateTime.now(), LocalDateTime.now(), validationCode, reason);
    }


    // ------------------------------------------------------------------------
    // Tests — grouped by behavioral concern
    // ------------------------------------------------------------------------

    /**
     * Job lifecycle / exit-status behavior, independent of input contents.
     */
    @Nested
    @DisplayName("Job completion semantics")
    class JobCompletion {

        /**
         * T1 — the consolidation job runs to {@link ExitStatus#COMPLETED} for a normal,
         * non-empty processed input.
         */
        @Test
        @DisplayName("Should complete consolidation job successfully")
        void shouldCompleteWithExitStatusCompleted() throws Exception {
            seedProcessedDailyTransaction("0000000000000001", new BigDecimal("125.50"), CARD_ACCT_1);

            JobExecution execution = jobLauncherTestUtils.launchJob();

            assertThat(execution.getExitStatus()).isEqualTo(ExitStatus.COMPLETED);
        }

        /**
         * T4 — an empty staging table is a valid no-op: the job still completes and the master
         * table remains empty (mirrors an empty COMBTRAN SORTIN producing an empty REPRO).
         */
        @Test
        @DisplayName("Should complete with COMPLETED status even when no input transactions exist")
        void shouldHandleEmptyInputGracefully() throws Exception {
            // No staging rows seeded — daily_transactions was cleared in setUp().
            JobExecution execution = jobLauncherTestUtils.launchJob();

            assertThat(execution.getExitStatus()).isEqualTo(ExitStatus.COMPLETED);
            assertThat(transactionRepository.count()).isZero();
        }
    }

    /**
     * Consolidation correctness: which rows are copied, that fields are preserved verbatim, and
     * that the {@code SORT FIELDS=(TRAN-ID,A)} ordering survives.
     */
    @Nested
    @DisplayName("Consolidation, field preservation and tran_id ordering")
    class ConsolidationAndOrdering {

        /**
         * T2 — multiple processed staging rows (seeded out of order) are all consolidated and are
         * returned in ascending {@code tran_id} order; an {@code unprocessed} row is skipped by the
         * {@code WHERE processed = TRUE} predicate.
         */
        @Test
        @DisplayName("Should consolidate transactions and store sorted by tran_id")
        void shouldConsolidateMultipleTransactionsByTranIdOrderBy() throws Exception {
            // Processed rows, seeded deliberately out of tran_id order.
            seedProcessedDailyTransaction("0000000000000030", new BigDecimal("30.00"), CARD_ACCT_3);
            seedProcessedDailyTransaction("0000000000000010", new BigDecimal("10.00"), CARD_ACCT_1);
            seedProcessedDailyTransaction("0000000000000020", new BigDecimal("20.00"), CARD_ACCT_2);
            seedProcessedDailyTransaction("0000000000000040", new BigDecimal("40.00"), CARD_ACCT_5);
            // An UNPROCESSED row must NOT be consolidated.
            seedDailyTransaction("0000000000000099", new BigDecimal("99.00"), CARD_ACCT_50, false);

            JobExecution execution = jobLauncherTestUtils.launchJob();

            assertThat(execution.getExitStatus()).isEqualTo(ExitStatus.COMPLETED);
            // Only the four processed rows are consolidated.
            assertThat(transactionRepository.count()).isEqualTo(4);

            List<Transaction> sorted = transactionRepository.findAll(Sort.by("tranId"));
            assertThat(sorted)
                    .extracting(Transaction::getTranId)
                    .containsExactly(
                            "0000000000000010",
                            "0000000000000020",
                            "0000000000000030",
                            "0000000000000040");
            // The unprocessed staging row never reached the master table.
            assertThat(transactionRepository.findById("0000000000000099")).isEmpty();
        }

        /**
         * T5 — every business column staged is copied verbatim into the master table. The monetary
         * amount is compared with {@code isEqualByComparingTo} so that a scale difference
         * ({@code 123.45} vs {@code 123.450}) does not produce a false negative (PR-16).
         */
        @Test
        @DisplayName("Should preserve all transaction fields verbatim after consolidation")
        void shouldPreserveTransactionFieldsAfterConsolidation() throws Exception {
            String tranId = "0000000000000777";
            BigDecimal amount = new BigDecimal("123.45");
            seedFullProcessedDailyTransaction(
                    tranId, TYPE_CD, CAT_CD, "POS", "Acme Store Purchase", amount,
                    999000111L, "ACME STORE", "SEATTLE", "98101", CARD_ACCT_2);

            JobExecution execution = jobLauncherTestUtils.launchJob();

            assertThat(execution.getExitStatus()).isEqualTo(ExitStatus.COMPLETED);

            Optional<Transaction> persisted = transactionRepository.findById(tranId);
            assertThat(persisted).isPresent();
            Transaction t = persisted.get();
            assertThat(t.getTranId()).isEqualTo(tranId);
            assertThat(t.getTypeCd()).isEqualTo(TYPE_CD);
            assertThat(t.getCategoryCd()).isEqualTo(CAT_CD);
            assertThat(t.getSource()).isEqualTo("POS");
            assertThat(t.getDescription()).isEqualTo("Acme Store Purchase");
            // PR-16 — compare by value, not by scale.
            assertThat(t.getAmount()).isEqualByComparingTo(amount);
            assertThat(t.getMerchantId()).isEqualTo(999000111L);
            assertThat(t.getMerchantName()).isEqualTo("ACME STORE");
            assertThat(t.getMerchantCity()).isEqualTo("SEATTLE");
            assertThat(t.getMerchantZip()).isEqualTo("98101");
            assertThat(t.getCardNum()).isEqualTo(CARD_ACCT_2);
            assertThat(t.getOrigTimestamp()).isNotNull();
            assertThat(t.getProcTimestamp()).isNotNull();
        }

        /**
         * T6 — focused ordering assertion: ids seeded {@code 3, 1, 2} are returned {@code 1, 2, 3}
         * by {@code findAll(Sort.by("tranId"))}, preserving the COMBTRAN {@code SORT FIELDS=(TRAN-ID,A)}
         * semantics via the {@code transactions} primary-key B-tree.
         */
        @Test
        @DisplayName("Output order should be ascending by tran_id (SORT FIELDS=(TRAN-ID,A) preserved)")
        void shouldSortByTranIdAscending() throws Exception {
            seedProcessedDailyTransaction("0000000000000003", new BigDecimal("3.00"), CARD_ACCT_3);
            seedProcessedDailyTransaction("0000000000000001", new BigDecimal("1.00"), CARD_ACCT_1);
            seedProcessedDailyTransaction("0000000000000002", new BigDecimal("2.00"), CARD_ACCT_2);

            JobExecution execution = jobLauncherTestUtils.launchJob();

            assertThat(execution.getExitStatus()).isEqualTo(ExitStatus.COMPLETED);
            List<Transaction> sorted = transactionRepository.findAll(Sort.by("tranId"));
            assertThat(sorted)
                    .extracting(Transaction::getTranId)
                    .containsExactly(
                            "0000000000000001",
                            "0000000000000002",
                            "0000000000000003");
        }
    }

    /**
     * Conflict handling ({@code ON CONFLICT DO NOTHING} + {@code NOT EXISTS}) and rerun
     * idempotency — the safety-net behavior that prevents data loss and duplicates (PR-22).
     */
    @Nested
    @DisplayName("Conflict handling and idempotency (ON CONFLICT DO NOTHING / NOT EXISTS)")
    class ConflictAndIdempotency {

        /**
         * T3 — when a {@code tran_id} already exists in the master table (e.g. written earlier by
         * {@code POSTTRAN}), the staging row with the same id is skipped: the pre-existing row is
         * preserved unchanged and no duplicate is created. The original amount (not the staging
         * amount) must remain, asserted by value via {@code isEqualByComparingTo} (PR-16).
         */
        @Test
        @DisplayName("Should preserve existing master row (ON CONFLICT DO NOTHING) when tran_id collides")
        void shouldPreserveExistingMasterRowOnConflict() throws Exception {
            String tranId = "0000000000000500";
            BigDecimal masterAmount = new BigDecimal("100.00");
            BigDecimal stagingAmount = new BigDecimal("999.99");

            // Pre-existing master row (as if written by an upstream POSTTRAN run).
            transactionRepository.save(buildTransaction(tranId, masterAmount, CARD_ACCT_1));
            // A staging row with the SAME id but a different amount.
            seedProcessedDailyTransaction(tranId, stagingAmount, CARD_ACCT_1);

            JobExecution execution = jobLauncherTestUtils.launchJob();

            assertThat(execution.getExitStatus()).isEqualTo(ExitStatus.COMPLETED);
            // Exactly one row — no duplicate created.
            assertThat(transactionRepository.count()).isEqualTo(1);
            Optional<Transaction> persisted = transactionRepository.findById(tranId);
            assertThat(persisted).isPresent();
            // The original master amount is preserved (DO NOTHING), not overwritten by 999.99.
            assertThat(persisted.get().getAmount()).isEqualByComparingTo(masterAmount);
        }

        /**
         * T7 — relaunching the job with different (non-identifying) parameters reprocesses the same
         * staging rows but inserts nothing the second time, because the {@code NOT EXISTS} guard and
         * {@code ON CONFLICT DO NOTHING} make the operation idempotent. Both executions complete and
         * the master count is unchanged with no duplicate ids.
         */
        @Test
        @DisplayName("Should be idempotent: rerunning with different parameters yields no duplicates")
        void shouldBeIdempotentWhenRerunWithDifferentParameters() throws Exception {
            seedProcessedDailyTransaction("0000000000000061", new BigDecimal("61.00"), CARD_ACCT_1);
            seedProcessedDailyTransaction("0000000000000062", new BigDecimal("62.00"), CARD_ACCT_2);

            JobParameters firstRun = new JobParametersBuilder()
                    .addLong("run.id", 1L)
                    .toJobParameters();
            JobExecution firstExecution = jobLauncherTestUtils.launchJob(firstRun);

            assertThat(firstExecution.getExitStatus()).isEqualTo(ExitStatus.COMPLETED);
            assertThat(transactionRepository.count()).isEqualTo(2);

            // Re-run with DIFFERENT parameters → a new job instance; staging rows still present.
            JobParameters secondRun = new JobParametersBuilder()
                    .addLong("run.id", 2L)
                    .toJobParameters();
            JobExecution secondExecution = jobLauncherTestUtils.launchJob(secondRun);

            assertThat(secondExecution.getExitStatus()).isEqualTo(ExitStatus.COMPLETED);
            // NOT EXISTS + ON CONFLICT DO NOTHING ⇒ the second run inserts nothing.
            assertThat(transactionRepository.count()).isEqualTo(2);
            List<Transaction> sorted = transactionRepository.findAll(Sort.by("tranId"));
            assertThat(sorted)
                    .extracting(Transaction::getTranId)
                    .containsExactly("0000000000000061", "0000000000000062");
        }
    }

    /**
     * Reject-segregation regression coverage for finding <strong>F2-001</strong> (MAJOR).
     *
     * <p>The upstream {@code POSTTRAN} job marks <em>every</em> daily transaction it accounts for —
     * accepted <em>and</em> rejected — with {@code processed = TRUE}, but writes rejected rows only
     * to the {@code rejected_transactions} sink (the DALYREJS equivalent), never to the master
     * {@code transactions} table. Before the fix, the consolidation SQL guarded only against rows
     * already present in {@code transactions}, so a rejected row (processed but absent from the
     * master) satisfied the predicate and was wrongly inserted into the master — contaminating the
     * statements that {@code CREASTMT} renders from {@code transactions}.
     *
     * <p>The fix adds a second {@code NOT EXISTS (SELECT 1 FROM rejected_transactions r WHERE
     * r.tran_id = dt.tran_id)} guard, preserving the {@code COMBTRAN.jcl} contract (PR-03) that a
     * rejected transaction can never enter the master. These tests reproduce the original defect's
     * data shape and assert the leak is closed while accepted rows still consolidate.
     */
    @Nested
    @DisplayName("Reject segregation (F2-001): rejected transactions must never enter the master")
    class RejectSegregation {

        /**
         * T8 — the exact F2-001 reproduction. A staging row flagged {@code processed = TRUE} whose
         * {@code tran_id} also exists in {@code rejected_transactions} (as POSTTRAN leaves it) must
         * NOT be consolidated into the master {@code transactions} table. The QA verification query —
         * counting {@code tran_id}s present in BOTH tables — must return zero (it returned 38 before
         * the fix).
         */
        @Test
        @DisplayName("Should NOT consolidate a processed staging row whose tran_id was rejected by POSTTRAN")
        void shouldNotLeakRejectedTransactionIntoMaster() throws Exception {
            String rejectedId = "0000000040455859";
            // POSTTRAN over-limit reject: written to rejected_transactions AND flagged processed=TRUE
            // in the staging table (the overloaded-flag condition that caused the leak).
            seedRejectedTransaction(rejectedId, new BigDecimal("715.44"), CARD_ACCT_1,
                    102, "OVERLIMIT TRANSACTION");
            seedProcessedDailyTransaction(rejectedId, new BigDecimal("715.44"), CARD_ACCT_1);

            JobExecution execution = jobLauncherTestUtils.launchJob();

            assertThat(execution.getExitStatus()).isEqualTo(ExitStatus.COMPLETED);
            // The rejected transaction must NOT appear in the master table.
            assertThat(transactionRepository.findById(rejectedId))
                    .as("A POSTTRAN-rejected transaction must never be consolidated into the master")
                    .isEmpty();
            assertThat(transactionRepository.count())
                    .as("No master rows should be created from a purely-rejected staging set")
                    .isZero();
            // The QA verification query from finding F2-001 — must be zero.
            Integer contradictions = jdbcTemplate.queryForObject(
                    "SELECT count(*) FROM transactions t WHERE EXISTS ("
                            + "SELECT 1 FROM rejected_transactions r WHERE r.tran_id = t.tran_id)",
                    Integer.class);
            assertThat(contradictions)
                    .as("No tran_id may exist in BOTH transactions and rejected_transactions (F2-001)")
                    .isZero();
        }

        /**
         * T9 — in a mixed staging set, only the accepted row is consolidated. The rejected row
         * (present in {@code rejected_transactions}) is excluded by the second {@code NOT EXISTS}
         * guard, proving the new predicate excludes <em>only</em> rejects and does not regress the
         * normal consolidation of accepted rows.
         */
        @Test
        @DisplayName("Should consolidate the accepted row while excluding the rejected row in a mixed set")
        void shouldConsolidateAcceptedButExcludeRejectedInMixedSet() throws Exception {
            String acceptedId = "0000000000000200";
            String rejectedId = "0000000000000300";

            // Accepted: staged + processed, NOT in rejected_transactions → must reach the master.
            seedProcessedDailyTransaction(acceptedId, new BigDecimal("50.00"), CARD_ACCT_2);
            // Rejected: staged + processed AND present in rejected_transactions → must be excluded.
            seedProcessedDailyTransaction(rejectedId, new BigDecimal("999.99"), CARD_ACCT_3);
            seedRejectedTransaction(rejectedId, new BigDecimal("999.99"), CARD_ACCT_3,
                    103, "TRANSACTION RECEIVED AFTER ACCT EXPIRATION");

            JobExecution execution = jobLauncherTestUtils.launchJob();

            assertThat(execution.getExitStatus()).isEqualTo(ExitStatus.COMPLETED);
            // Exactly one master row — only the accepted transaction.
            assertThat(transactionRepository.count())
                    .as("Only the accepted transaction should be consolidated")
                    .isEqualTo(1);
            assertThat(transactionRepository.findById(acceptedId))
                    .as("The accepted transaction must be consolidated into the master")
                    .isPresent();
            assertThat(transactionRepository.findById(rejectedId))
                    .as("The rejected transaction must be excluded from the master (PR-03)")
                    .isEmpty();
        }
    }
}
