/*
 * Copyright Amazon.com, Inc. or its affiliates.
 * All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License").
 * You may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND,
 * either express or implied. See the License for the specific
 * language governing permissions and limitations under the License.
 */
package com.aws.carddemo.batch;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Stream;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.ExitStatus;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.JobExecution;
import org.springframework.batch.core.JobParameters;
import org.springframework.batch.core.JobParametersBuilder;
import org.springframework.batch.core.Step;
import org.springframework.batch.core.launch.JobLauncher;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.test.JobLauncherTestUtils;
import org.springframework.batch.test.JobRepositoryTestUtils;
import org.springframework.batch.test.context.SpringBatchTest;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import com.aws.carddemo.domain.CardXref;
import com.aws.carddemo.domain.Transaction;
import com.aws.carddemo.repository.CardXrefRepository;
import com.aws.carddemo.repository.TransactionRepository;

/**
 * Spring Boot + Spring Batch Testcontainers integration test that proves the behavioral parity of
 * {@link TransactionReportJob} &mdash; the Java re-platform of the legacy COBOL batch program
 * {@code legacy/cbl/CBTRN03C.cbl} (originally {@code app/cbl/CBTRN03C.cbl}), orchestrated on the
 * mainframe by {@code legacy/proc/TRANREPT.prc} / {@code legacy/jcl/TRANREPT.jcl} and laid out by
 * the report copybook {@code legacy/cpy/CVTRA07Y.cpy} ({@code FD-REPTFILE-REC PIC X(133)}).
 *
 * <h2>Parity contract under test</h2>
 * <p>{@code CBTRN03C} reads the transaction file sequentially, keeps only the records whose
 * processing-timestamp date falls inside an inclusive {@code [WS-START-DATE, WS-END-DATE]} window
 * ({@code IF TRAN-PROC-TS (1:10) >= WS-START-DATE AND <= WS-END-DATE}, main loop L173-174), groups
 * the report by card number with a control break ({@code IF WS-CURR-CARD-NUM NOT= TRAN-CARD-NUM},
 * L181), enriches each detail line via the card cross-reference (card&rarr;account), the
 * transaction-type table ({@code CVTRA03Y}) and the transaction-category table ({@code CVTRA04Y}),
 * and emits headers plus three tiers of {@code PIC S9(9)V99} totals: a per-page total
 * ({@code WS-PAGE-TOTAL}) written on each page break ({@code WS-PAGE-SIZE = 20}), a per-account
 * total ({@code WS-ACCOUNT-TOTAL}) written on each card control break, and a single grand total
 * ({@code WS-GRAND-TOTAL}) at end-of-file. Every emitted record is exactly 133 characters. The Java
 * target decomposes that single sequential program into the {@code transactionReportItemReader}
 * &rarr; {@code transactionReportProcessor} &rarr; {@code transactionReportWriter} chunk triad; this
 * test asserts the observable, end-to-end contract of the resulting job.</p>
 *
 * <h2>Golden scenario (byte-exact)</h2>
 * <p>The test loads the canonical report scenario documented in
 * {@code src/test/resources/golden/report/README.md} (two accounts, two cards, six in-range
 * transactions) and launches the job for {@code [2025-01-01, 2025-01-31]}. The produced report is
 * asserted equal, row-for-row and byte-faithfully, to the shipped golden fixture
 * {@code golden/report/expected-report.txt} (15 fixed-width 133-character records, LF line endings,
 * a trailing newline, and a total size of {@value #EXPECTED_FILE_SIZE} bytes). Because the report's
 * name header embeds only the {@code startDate}/{@code endDate} range (never a run timestamp), the
 * output is fully deterministic and needs no header normalization before comparison.</p>
 *
 * <h2>Two documented CBTRN03C quirks reproduced by the scenario</h2>
 * <ul>
 *   <li><strong>End-of-file stale double-add.</strong> On {@code AT END} the COBOL record area still
 *       holds the final {@code TRAN-AMT}, which is added a second time to the page/account totals
 *       just before the final totals are written. The scenario's last transaction has amount
 *       {@code 0.00}, so the double-add is a no-op and the grand total equals the plain
 *       {@link BigDecimal} sum of the six in-range amounts to the cent.</li>
 *   <li><strong>Last account has no Account Total.</strong> Account totals fire only on a card
 *       change, so the final card (account B) never receives an Account Total line &mdash; only
 *       account A's Account Total appears. This test asserts exactly one Account Total line, equal
 *       to account A's per-card sum.</li>
 * </ul>
 *
 * <h2>Harness</h2>
 * <p>The full application context boots under the {@code test} profile against a real
 * PostgreSQL&nbsp;16 supplied by Testcontainers and wired through {@link ServiceConnection}, so the
 * datasource carries no hardcoded URL or credentials (AAP &sect;0.8.1 / &sect;0.9.3). Flyway applies
 * the production migrations ({@code V1__schema.sql} + {@code V2__reference_data.sql}, which seed the
 * transaction-type / transaction-category descriptions the report renders) and Hibernate validates
 * the entity mappings against that schema. {@code spring.batch.job.enabled=false} keeps the job from
 * running at startup, so it is launched explicitly through {@link JobLauncherTestUtils}.</p>
 *
 * <p>Because the application declares many {@link Job} beans, the {@link JobLauncherTestUtils}
 * auto-registered by {@link SpringBatchTest} cannot resolve a unique job. The nested
 * {@link BatchTestConfig} therefore contributes a {@link Primary @Primary}
 * {@link JobLauncherTestUtils} whose job is bound explicitly by qualifier to
 * {@code transactionReportJob}, so this test always launches the correct job.</p>
 *
 * <p>Under the {@code test} profile every data table is empty (the bulk seed loader is
 * {@code local}-only), so each test seeds its own deterministic fixture: the minimal foreign-key
 * parents ({@code customer}, {@code account}, {@code card}) through {@link JdbcTemplate}, then the
 * cross-reference rows through {@link CardXrefRepository} and the six transactions through
 * {@link TransactionRepository}. The report writer's config-driven output location
 * ({@code carddemo.batch.report.output-directory} / {@code output-file}) is redirected to a
 * disposable per-class temporary directory via {@link #reportProperties(DynamicPropertyRegistry)}.
 * The class is intentionally not transactional so each seed save commits immediately and is visible
 * to the batch job's reader (which runs in its own transaction).</p>
 *
 * @see TransactionReportJob
 * @see <a href="http://www.apache.org/licenses/LICENSE-2.0">Apache License 2.0</a>
 */
@SpringBootTest
@ActiveProfiles("test")
@SpringBatchTest
@Testcontainers
class TransactionReportJobTest {

    /**
     * Real PostgreSQL&nbsp;16 engine shared by every test in this class. Declared {@code static} so
     * the {@link Testcontainers} extension starts it once before the Spring context is created;
     * {@link ServiceConnection} publishes its connection coordinates to Spring Boot so the datasource
     * is configured with no hardcoded credentials. The {@code postgres:16-alpine} image matches the
     * PostgreSQL&nbsp;16 production target.
     */
    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>(DockerImageName.parse("postgres:16-alpine"));

    // ------------------------------------------------------------------------
    // Report / fixed-width constants (CVTRA07Y layout + golden fixture facts)
    // ------------------------------------------------------------------------

    /** Fixed record width of {@code FD-REPTFILE-REC PIC X(133)} (CBTRN03C L85). */
    private static final int RECORD_LENGTH = 133;

    /** Zero-based offset of the edited-amount field in the detail and totals lines (CVTRA07Y). */
    private static final int AMOUNT_OFFSET = 97;

    /** Width of the edited-amount field ({@code -ZZZ,ZZZ,ZZZ.ZZ} / {@code +ZZZ,ZZZ,ZZZ.ZZ}). */
    private static final int AMOUNT_WIDTH = 15;

    /** Number of records in the golden report (headers + 6 details + control-break/total lines). */
    private static final int EXPECTED_RECORD_COUNT = 15;

    /** Byte-exact golden size: 15 records &times; (133 data bytes + 1 LF) = 2010 bytes. */
    private static final long EXPECTED_FILE_SIZE = 2010L;

    /** Classpath location of the authoritative expected report. */
    private static final String GOLDEN_RESOURCE = "golden/report/expected-report.txt";

    // ------------------------------------------------------------------------
    // Job parameters (the DATEPARM analog, format YYYY-MM-DD)
    // ------------------------------------------------------------------------

    /** Inclusive lower processing-date bound ({@code WS-START-DATE}); the golden run's start. */
    private static final String START_DATE = "2025-01-01";

    /** Inclusive upper processing-date bound ({@code WS-END-DATE}); the golden run's end. */
    private static final String END_DATE = "2025-01-31";

    // ------------------------------------------------------------------------
    // Scenario identifiers (from golden/report/README.md, section 3)
    // ------------------------------------------------------------------------

    /** Account A customer id ({@code XREF-CUST-ID}). */
    private static final long CUST_A = 900000021L;

    /** Account B customer id ({@code XREF-CUST-ID}). */
    private static final long CUST_B = 900000022L;

    /** Account A id ({@code XREF-ACCT-ID}); printed zero-padded to eleven digits. */
    private static final long ACCT_A = 90000000021L;

    /** Account B id ({@code XREF-ACCT-ID}); printed zero-padded to eleven digits. */
    private static final long ACCT_B = 90000000022L;

    /** Account A card number ({@code TRAN-CARD-NUM}); sorts before {@link #CARD_B}. */
    private static final String CARD_A = "9000000000000021";

    /** Account B card number ({@code TRAN-CARD-NUM}); sorts after {@link #CARD_A}. */
    private static final String CARD_B = "9000000000000022";

    /** Printed (zero-padded) account-id column value for account A. */
    private static final String ACCT_A_PRINTED = "90000000021";

    /** Printed (zero-padded) account-id column value for account B. */
    private static final String ACCT_B_PRINTED = "90000000022";

    /**
     * The six in-range transaction ids in the exact order the report must emit them: ascending by
     * {@code (cardNum, tranId)}, i.e. account A's three transactions then account B's three.
     */
    private static final List<String> EXPECTED_ORDERED_TRAN_IDS = List.of(
            "9000000000000201",
            "9000000000000202",
            "9000000000000203",
            "9000000000000204",
            "9000000000000205",
            "9000000000000206");

    /** Report file name; also pinned onto {@code carddemo.batch.report.output-file}. */
    private static final String REPORT_FILE_NAME = "DALYREPT.txt";

    /**
     * Per-class temporary directory that receives the produced report. Created eagerly at class-load
     * time (not through {@code @TempDir}) so it is available when
     * {@link #reportProperties(DynamicPropertyRegistry)} runs during context preparation.
     */
    private static final Path REPORT_DIR = createReportDirectory();

    /** Supplies unique {@code run.id} values so every launch is a distinct {@link Job} instance. */
    private static final AtomicLong RUN_ID = new AtomicLong();

    // ------------------------------------------------------------------------
    // Injected collaborators (constructor injection; no field @Autowired)
    // ------------------------------------------------------------------------

    /** Utilities bound to the {@code transactionReportJob} bean (see {@link BatchTestConfig}). */
    private final JobLauncherTestUtils jobLauncherTestUtils;

    /** Batch metadata helper used to clear job executions between relaunches. */
    private final JobRepositoryTestUtils jobRepositoryTestUtils;

    /** The running application context, used to assert the job/step beans are registered. */
    private final ApplicationContext applicationContext;

    /** Repository used to seed the six scenario transactions (the report's input). */
    private final TransactionRepository transactionRepository;

    /** Repository used to seed the two card cross-references (card&rarr;account resolution). */
    private final CardXrefRepository cardXrefRepository;

    /** JDBC template used to seed and remove the {@code customer}/{@code account}/{@code card} parents. */
    private final JdbcTemplate jdbcTemplate;

    /**
     * Constructor injection of the Spring-managed collaborators. Only field assignments are performed
     * (no overridable method is invoked), so no reference to a partially-constructed instance escapes.
     *
     * @param jobLauncherTestUtils   the job launcher utilities bound to {@code transactionReportJob}
     * @param jobRepositoryTestUtils the batch metadata helper for inter-test cleanup
     * @param applicationContext     the running application context
     * @param transactionRepository  the transaction repository (report input seeding)
     * @param cardXrefRepository     the card cross-reference repository (scenario seeding)
     * @param jdbcTemplate           the JDBC template used to seed the parent rows
     */
    TransactionReportJobTest(JobLauncherTestUtils jobLauncherTestUtils,
                             JobRepositoryTestUtils jobRepositoryTestUtils,
                             ApplicationContext applicationContext,
                             TransactionRepository transactionRepository,
                             CardXrefRepository cardXrefRepository,
                             JdbcTemplate jdbcTemplate) {
        this.jobLauncherTestUtils = jobLauncherTestUtils;
        this.jobRepositoryTestUtils = jobRepositoryTestUtils;
        this.applicationContext = applicationContext;
        this.transactionRepository = transactionRepository;
        this.cardXrefRepository = cardXrefRepository;
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * Redirects the report writer's config-driven output location to the disposable per-class
     * temporary directory, with no hardcoded path.
     *
     * <p>{@code TransactionReportWriter} resolves its output from
     * {@code carddemo.batch.report.output-directory} (default {@code ./target/batch}) and
     * {@code carddemo.batch.report.output-file} (default {@code DALYREPT.txt}); pinning both here
     * keeps every produced report inside a temporary directory the test fully controls.</p>
     *
     * @param registry the Spring test property registry to populate
     */
    @DynamicPropertySource
    static void reportProperties(DynamicPropertyRegistry registry) {
        registry.add("carddemo.batch.report.output-directory", REPORT_DIR::toString);
        registry.add("carddemo.batch.report.output-file", () -> REPORT_FILE_NAME);
    }

    /**
     * Creates the per-class temporary report directory.
     *
     * @return the created temporary directory
     * @throws UncheckedIOException if the directory cannot be created
     */
    private static Path createReportDirectory() {
        try {
            return Files.createTempDirectory("carddemo-transaction-report-test-");
        } catch (IOException ex) {
            throw new UncheckedIOException("Unable to create temporary report directory", ex);
        }
    }

    /**
     * Removes the temporary report directory (and the report file it may still hold) after all tests.
     *
     * @throws IOException if the directory contents cannot be removed
     */
    @AfterAll
    static void removeReportDirectory() throws IOException {
        Files.deleteIfExists(REPORT_DIR.resolve(REPORT_FILE_NAME));
        Files.deleteIfExists(REPORT_DIR);
    }

    /**
     * Seeds the canonical golden report scenario before each test and removes any report file left by
     * a prior run. Rows are inserted in foreign-key dependency order (parents first): the
     * {@code customer}, {@code account} and {@code card} parents via native SQL (only their mandatory
     * columns are populated &mdash; every other column is nullable or defaulted), then the two
     * cross-references and the six in-range transactions via their in-scope repositories.
     *
     * @throws IOException if a stale report file cannot be deleted
     */
    @BeforeEach
    void seedScenario() throws IOException {
        cleanBusinessData();
        Files.deleteIfExists(reportFile());

        jdbcTemplate.update("INSERT INTO customer (cust_id) VALUES (?)", CUST_A);
        jdbcTemplate.update("INSERT INTO customer (cust_id) VALUES (?)", CUST_B);

        jdbcTemplate.update(
                "INSERT INTO account (acct_id, acct_active_status) VALUES (?, ?)", ACCT_A, "Y");
        jdbcTemplate.update(
                "INSERT INTO account (acct_id, acct_active_status) VALUES (?, ?)", ACCT_B, "Y");

        jdbcTemplate.update(
                "INSERT INTO card (card_num, acct_id, card_active_status) VALUES (?, ?, ?)",
                CARD_A, ACCT_A, "Y");
        jdbcTemplate.update(
                "INSERT INTO card (card_num, acct_id, card_active_status) VALUES (?, ?, ?)",
                CARD_B, ACCT_B, "Y");

        cardXrefRepository.saveAll(List.of(
                new CardXref(CARD_A, CUST_A, ACCT_A),
                new CardXref(CARD_B, CUST_B, ACCT_B)));

        transactionRepository.saveAll(List.of(
                newTransaction("9000000000000201", "01", 1, "POS TERM", new BigDecimal("1234.56"),
                        CARD_A, "2025-01-05"),
                newTransaction("9000000000000202", "01", 2, "POS TERM", new BigDecimal("500.00"),
                        CARD_A, "2025-01-06"),
                newTransaction("9000000000000203", "03", 1, "SYSTEM", new BigDecimal("-250.00"),
                        CARD_A, "2025-01-07"),
                newTransaction("9000000000000204", "02", 1, "WEB", new BigDecimal("-1000.00"),
                        CARD_B, "2025-01-10"),
                newTransaction("9000000000000205", "01", 4, "ATM", new BigDecimal("75.25"),
                        CARD_B, "2025-01-11"),
                newTransaction("9000000000000206", "04", 1, "AUTH", new BigDecimal("0.00"),
                        CARD_B, "2025-01-12")));
    }

    /**
     * Clears the batch job-execution metadata (so relaunches start clean), removes the seeded
     * business data, and deletes any produced report file after each test.
     *
     * @throws IOException if the produced report file cannot be deleted
     */
    @AfterEach
    void tidyUp() throws IOException {
        jobRepositoryTestUtils.removeJobExecutions();
        cleanBusinessData();
        Files.deleteIfExists(reportFile());
    }

    /**
     * Removes all scenario rows in foreign-key-safe order (children before parents). Safe to call
     * before any data exists.
     */
    private void cleanBusinessData() {
        transactionRepository.deleteAll();
        cardXrefRepository.deleteAll();
        jdbcTemplate.update("DELETE FROM card");
        jdbcTemplate.update("DELETE FROM account");
        jdbcTemplate.update("DELETE FROM customer");
    }

    // ------------------------------------------------------------------------
    // Tests
    // ------------------------------------------------------------------------

    /**
     * The context wires the report job. Confirms the {@code transactionReportJob} {@link Job} bean and
     * the {@code transactionReportStep} {@link Step} bean are registered under their expected names,
     * and that the test's {@link JobLauncherTestUtils} is bound to the correct job (proving the
     * multi-job resolution in {@link BatchTestConfig} succeeded). Reaching this assertion transitively
     * proves the full context (all layers, Flyway migration, and Hibernate schema validation) started.
     */
    @Test
    @DisplayName("context loads and the transactionReportJob/Step beans are registered")
    void contextLoadsAndJobRegistered() {
        assertThat(applicationContext).isNotNull();

        assertThat(applicationContext.containsBean("transactionReportJob")).isTrue();
        Job job = applicationContext.getBean("transactionReportJob", Job.class);
        assertThat(job.getName()).isEqualTo("transactionReportJob");

        assertThat(applicationContext.containsBean("transactionReportStep")).isTrue();
        assertThat(applicationContext.getBean("transactionReportStep", Step.class)).isNotNull();

        assertThat(jobLauncherTestUtils.getJob()).isNotNull();
        assertThat(jobLauncherTestUtils.getJob().getName()).isEqualTo("transactionReportJob");
    }

    /**
     * A clean run over the in-range transactions finishes {@link BatchStatus#COMPLETED} with the
     * {@link ExitStatus#COMPLETED} exit code &mdash; the framework analog of the COBOL {@code GOBACK}
     * with return code {@code 0}.
     *
     * @throws Exception if the job launch fails
     */
    @Test
    @DisplayName("runs clean and finishes COMPLETED (return code 0)")
    void runsCleanWithReturnCodeZero() throws Exception {
        JobExecution execution = jobLauncherTestUtils.launchJob(reportParams(START_DATE, END_DATE));

        assertThat(execution.getStatus()).isEqualTo(BatchStatus.COMPLETED);
        assertThat(execution.getExitStatus().getExitCode())
                .isEqualTo(ExitStatus.COMPLETED.getExitCode());
    }

    /**
     * The produced report equals the shipped golden fixture row-for-row and byte-faithfully. Lines are
     * compared exactly (no trimming) to preserve fixed-width fidelity, every record is asserted to be
     * exactly {@value #RECORD_LENGTH} characters, and the total file size is asserted at
     * {@value #EXPECTED_FILE_SIZE} bytes. Fifteen 133-character records plus one single-byte LF each
     * sums to 2010 bytes, which together with the width check proves the {@code RECFM=FB LRECL=133}
     * contract with LF terminators.
     *
     * @throws Exception if the job launch or file/golden read fails
     */
    @Test
    @DisplayName("produced report matches the golden fixture row-for-row (133-byte records)")
    void reportMatchesGoldenRowForRow() throws Exception {
        JobExecution execution = jobLauncherTestUtils.launchJob(reportParams(START_DATE, END_DATE));
        assertThat(execution.getStatus()).isEqualTo(BatchStatus.COMPLETED);

        List<String> produced = producedReportLines();
        List<String> golden = readGoldenLines();

        assertThat(produced)
                .as("row-for-row equality with golden/report/expected-report.txt")
                .containsExactlyElementsOf(golden);
        assertThat(produced)
                .as("15 fixed-width 133-character records")
                .hasSize(EXPECTED_RECORD_COUNT)
                .allSatisfy(line -> assertThat(line).hasSize(RECORD_LENGTH));
        assertThat(Files.size(reportFile()))
                .as("byte-exact fixed-width output with a single LF per 133-byte record")
                .isEqualTo(EXPECTED_FILE_SIZE);
    }

    /**
     * Transactions whose processing-timestamp date falls outside {@code [startDate, endDate]} are
     * excluded, reproducing the CBTRN03C {@code TRAN-PROC-TS (1:10)} range filter (L173-174). Two
     * extra transactions &mdash; one dated the day before the range and one the day after &mdash; are
     * seeded on account A; after the run only the six in-range transactions appear, in the expected
     * order, and neither out-of-range id is present.
     *
     * @throws Exception if the job launch or file read fails
     */
    @Test
    @DisplayName("date-range filter excludes before/after-range transactions")
    void dateRangeFilterExcludesOutOfRange() throws Exception {
        transactionRepository.saveAll(List.of(
                newTransaction("9000000000000207", "01", 1, "POS TERM", new BigDecimal("999.99"),
                        CARD_A, "2024-12-31"),
                newTransaction("9000000000000208", "01", 1, "POS TERM", new BigDecimal("888.88"),
                        CARD_A, "2025-02-01")));

        JobExecution execution = jobLauncherTestUtils.launchJob(reportParams(START_DATE, END_DATE));
        assertThat(execution.getStatus()).isEqualTo(BatchStatus.COMPLETED);

        List<String> detailTranIds = detailTranIds(producedReportLines());
        assertThat(detailTranIds)
                .as("only the six in-range transactions appear, in (cardNum, tranId) order")
                .containsExactlyElementsOf(EXPECTED_ORDERED_TRAN_IDS);
        assertThat(detailTranIds)
                .as("out-of-range transactions are filtered out")
                .doesNotContain("9000000000000207", "9000000000000208")
                .hasSize(6);
    }

    /**
     * Page, account and grand totals are correct to the cent, computed as scale-2
     * {@link BigDecimal}. The grand total equals the {@link BigDecimal} sum of the six in-range
     * amounts (the end-of-file stale double-add is a no-op because the last transaction is
     * {@code 0.00}); the single Account Total equals account A's per-card sum; and, because the six
     * detail rows fit on one page ({@code WS-PAGE-SIZE = 20}), exactly one Page Total is written and
     * equals the grand total. Account B produces no Account Total (the documented last-account quirk).
     *
     * @throws Exception if the job launch or file read fails
     */
    @Test
    @DisplayName("page, account and grand totals are correct to the cent (BigDecimal scale 2)")
    void pageAccountAndGrandTotalsToTheCent() throws Exception {
        JobExecution execution = jobLauncherTestUtils.launchJob(reportParams(START_DATE, END_DATE));
        assertThat(execution.getStatus()).isEqualTo(BatchStatus.COMPLETED);

        List<String> lines = producedReportLines();

        BigDecimal expectedGrand = sumOf("1234.56", "500.00", "-250.00", "-1000.00", "75.25", "0.00");
        assertThat(expectedGrand).isEqualByComparingTo("559.81");

        BigDecimal grand = totalAmount(lines, "Grand Total");
        assertThat(grand).isEqualByComparingTo(expectedGrand);
        assertThat(grand.scale()).isEqualTo(2);

        // Exactly one Account Total (account A); account B has none (last-account control-break quirk).
        List<String> accountTotals = lines.stream()
                .filter(line -> line.startsWith("Account Total"))
                .toList();
        assertThat(accountTotals).hasSize(1);
        BigDecimal accountA = parseAmount(
                accountTotals.get(0).substring(AMOUNT_OFFSET, AMOUNT_OFFSET + AMOUNT_WIDTH));
        assertThat(accountA).isEqualByComparingTo(sumOf("1234.56", "500.00", "-250.00"));
        assertThat(accountA).isEqualByComparingTo("1484.56");
        assertThat(accountA.scale()).isEqualTo(2);

        // Single page: exactly one Page Total, equal to the grand total.
        List<String> pageTotals = lines.stream()
                .filter(line -> line.startsWith("Page Total"))
                .toList();
        assertThat(pageTotals).hasSize(1);
        BigDecimal page = parseAmount(
                pageTotals.get(0).substring(AMOUNT_OFFSET, AMOUNT_OFFSET + AMOUNT_WIDTH));
        assertThat(page).isEqualByComparingTo("559.81");
    }

    /**
     * Detail lines are grouped and ordered by card (account), reproducing the CBTRN03C card control
     * break (L181): account A's three transactions are emitted first (ascending {@code tranId}), then
     * account B's three. Asserts the exact ordered transaction ids and the grouped account-id column
     * (three account-A rows followed by three account-B rows).
     *
     * @throws Exception if the job launch or file read fails
     */
    @Test
    @DisplayName("detail lines are grouped and ordered by card/account")
    void orderedByCard() throws Exception {
        JobExecution execution = jobLauncherTestUtils.launchJob(reportParams(START_DATE, END_DATE));
        assertThat(execution.getStatus()).isEqualTo(BatchStatus.COMPLETED);

        List<String> details = detailLines(producedReportLines());

        List<String> tranIds = details.stream().map(line -> line.substring(0, 16)).toList();
        assertThat(tranIds).containsExactlyElementsOf(EXPECTED_ORDERED_TRAN_IDS);

        List<String> accountIds = details.stream()
                .map(line -> line.substring(17, 28).strip())
                .toList();
        assertThat(accountIds).containsExactly(
                ACCT_A_PRINTED, ACCT_A_PRINTED, ACCT_A_PRINTED,
                ACCT_B_PRINTED, ACCT_B_PRINTED, ACCT_B_PRINTED);
    }

    // ------------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------------

    /**
     * Builds unique {@link JobParameters} for a report run over an inclusive processing-date range.
     *
     * <p>A fresh {@code run.id} makes every launch a distinct job instance. This helper deliberately
     * returns {@link JobParameters} rather than {@link JobExecution}: a test-class method whose return
     * type is {@code JobExecution} (or {@code StepExecution}) is picked up by {@link SpringBatchTest}'s
     * job/step-scope test listeners as a scope factory and invoked with no arguments, which fails for a
     * parameterized helper. Each test therefore launches with
     * {@code jobLauncherTestUtils.launchJob(reportParams(...))}.</p>
     *
     * @param startDate inclusive lower bound, {@code YYYY-MM-DD} (the {@code startDate} parameter)
     * @param endDate   inclusive upper bound, {@code YYYY-MM-DD} (the {@code endDate} parameter)
     * @return unique job parameters carrying {@code startDate}, {@code endDate} and a fresh {@code run.id}
     */
    private JobParameters reportParams(String startDate, String endDate) {
        return new JobParametersBuilder()
                .addString("startDate", startDate)
                .addString("endDate", endDate)
                .addLong("run.id", RUN_ID.incrementAndGet())
                .toJobParameters();
    }

    /**
     * Resolves the produced report file inside the per-class temporary directory.
     *
     * @return the report file path
     */
    private Path reportFile() {
        return REPORT_DIR.resolve(REPORT_FILE_NAME);
    }

    /**
     * Reads the produced report as fixed-width lines using ISO-8859-1 (one byte per character), so the
     * 133-character records are preserved without any charset re-interpretation.
     *
     * @return the produced report lines in emission order
     * @throws IOException if the report file cannot be read
     */
    private List<String> producedReportLines() throws IOException {
        return Files.readAllLines(reportFile(), StandardCharsets.ISO_8859_1);
    }

    /**
     * Reads the golden fixture from the classpath as lines, using ISO-8859-1 to match the report's
     * byte-faithful fixed-width encoding.
     *
     * @return the expected report lines in order
     * @throws IOException if the golden resource cannot be read
     */
    private static List<String> readGoldenLines() throws IOException {
        ClassPathResource golden = new ClassPathResource(GOLDEN_RESOURCE);
        try (InputStream in = golden.getInputStream();
             BufferedReader reader =
                     new BufferedReader(new InputStreamReader(in, StandardCharsets.ISO_8859_1))) {
            return reader.lines().toList();
        }
    }

    /**
     * Returns only the detail lines of a report: those beginning with a transaction-id digit. Header,
     * blank, dashes, and total lines begin with a letter, a dash, or a space and are excluded.
     *
     * @param lines all produced report lines
     * @return the detail lines in emission order
     */
    private static List<String> detailLines(List<String> lines) {
        return lines.stream()
                .filter(line -> !line.isEmpty() && Character.isDigit(line.charAt(0)))
                .toList();
    }

    /**
     * Extracts the 16-character transaction id ({@code TRAN-REPORT-TRANS-ID}, offset 0) from each
     * detail line.
     *
     * @param lines all produced report lines
     * @return the transaction ids of the detail lines, in emission order
     */
    private static List<String> detailTranIds(List<String> lines) {
        return detailLines(lines).stream().map(line -> line.substring(0, 16)).toList();
    }

    /**
     * Reads the edited-amount field of the first line beginning with the given total label and parses
     * it as a scale-2 {@link BigDecimal}.
     *
     * @param lines all produced report lines
     * @param label the total-line label ({@code "Page Total"}, {@code "Account Total"} or
     *              {@code "Grand Total"})
     * @return the parsed total amount at scale 2
     */
    private BigDecimal totalAmount(List<String> lines, String label) {
        String line = lines.stream()
                .filter(candidate -> candidate.startsWith(label))
                .findFirst()
                .orElseThrow(() -> new AssertionError("report is missing the line: " + label));
        return parseAmount(line.substring(AMOUNT_OFFSET, AMOUNT_OFFSET + AMOUNT_WIDTH));
    }

    /**
     * Parses a COBOL edited-amount field ({@code -ZZZ,ZZZ,ZZZ.ZZ} / {@code +ZZZ,ZZZ,ZZZ.ZZ}) into a
     * scale-2 {@link BigDecimal}. Grouping commas and the fixed-position padding spaces are removed;
     * an all-blank field (the COBOL all-{@code Z} zero rule) parses to {@code 0.00}. A leading
     * {@code +} or {@code -} sign is honoured. Parsing never uses {@code double}/{@code float}.
     *
     * @param field the {@value #AMOUNT_WIDTH}-character edited-amount field
     * @return the parsed amount at scale 2
     */
    private static BigDecimal parseAmount(String field) {
        String cleaned = field.replace(",", "").replace(" ", "");
        if (cleaned.isEmpty()) {
            return new BigDecimal("0.00");
        }
        return new BigDecimal(cleaned).setScale(2, RoundingMode.HALF_UP);
    }

    /**
     * Sums the supplied decimal literals as scale-2 {@link BigDecimal} values (never floating point).
     *
     * @param amounts the scale-2 decimal literals to add
     * @return the total at scale 2
     */
    private static BigDecimal sumOf(String... amounts) {
        return Stream.of(amounts)
                .map(BigDecimal::new)
                .reduce(BigDecimal.ZERO, BigDecimal::add)
                .setScale(2, RoundingMode.HALF_UP);
    }

    /**
     * Builds a transient {@link Transaction} for the scenario. The report exercises only the id, type,
     * category, source, amount, card number, and the date portion of the processing timestamp; the
     * merchant fields and description are left {@code null} (all nullable). The processing timestamp is
     * a 26-character {@code YYYY-MM-DD-HH.MM.SS.ffffff} value whose leading ten characters are the
     * supplied date, matching the {@code TRAN-PROC-TS (1:10)} filter operand; the origination timestamp
     * is set to the same value.
     *
     * @param tranId   the 16-character transaction id ({@code TRAN-ID})
     * @param typeCd   the two-character transaction-type code ({@code TRAN-TYPE-CD})
     * @param catCd    the transaction-category code ({@code TRAN-CAT-CD})
     * @param source   the transaction source ({@code TRAN-SOURCE})
     * @param amount   the signed amount at scale 2 ({@code TRAN-AMT})
     * @param cardNum  the card number ({@code TRAN-CARD-NUM})
     * @param procDate the processing date, {@code YYYY-MM-DD}
     * @return a new transient transaction row
     */
    private static Transaction newTransaction(String tranId, String typeCd, int catCd, String source,
                                              BigDecimal amount, String cardNum, String procDate) {
        String timestamp = procDate + "-00.00.00.000000";
        return new Transaction(
                tranId, typeCd, catCd, source, null, amount,
                null, null, null, null, cardNum, timestamp, timestamp);
    }

    /**
     * Test-scoped configuration that supplies an explicitly-bound {@link JobLauncherTestUtils}.
     *
     * <p>With many {@link Job} beans on the context, the {@link SpringBatchTest}-provided
     * {@code JobLauncherTestUtils} cannot bind a unique job. This {@link Primary @Primary} bean
     * (registered under a distinct name to avoid clashing with the auto-registered one) binds the job
     * explicitly by qualifier and reuses the auto-configured {@link JobLauncher} and
     * {@link JobRepository}, so this test always launches {@code transactionReportJob}.</p>
     */
    @TestConfiguration
    static class BatchTestConfig {

        /**
         * Builds the {@link Primary @Primary} launcher-test utility bound to
         * {@code transactionReportJob}.
         *
         * @param transactionReportJob the job under test, bound by qualifier
         * @param jobLauncher          the auto-configured batch job launcher
         * @param jobRepository        the auto-configured batch job repository
         * @return a {@link JobLauncherTestUtils} that launches only {@code transactionReportJob}
         */
        @Bean
        @Primary
        JobLauncherTestUtils reportJobLauncherTestUtils(
                @Qualifier("transactionReportJob") Job transactionReportJob,
                JobLauncher jobLauncher,
                JobRepository jobRepository) {
            JobLauncherTestUtils utils = new JobLauncherTestUtils();
            utils.setJob(transactionReportJob);
            utils.setJobLauncher(jobLauncher);
            utils.setJobRepository(jobRepository);
            return utils;
        }
    }
}
