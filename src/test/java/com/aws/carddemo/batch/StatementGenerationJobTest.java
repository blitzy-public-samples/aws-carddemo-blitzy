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
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.verify;

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
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import com.aws.carddemo.batch.reader.StatementFileService;
import com.aws.carddemo.domain.CardXref;
import com.aws.carddemo.domain.Transaction;
import com.aws.carddemo.repository.CardXrefRepository;
import com.aws.carddemo.repository.TransactionRepository;

/**
 * Spring Boot + Spring Batch Testcontainers integration test that proves the behavioral parity of
 * {@link StatementGenerationJob} &mdash; the Java re-platform of the legacy COBOL statement-creation
 * program {@code legacy/cbl/CBSTM03A.CBL} (originally {@code app/cbl/CBSTM03A.CBL}) together with its
 * called generic file-I/O subprogram {@code legacy/cbl/CBSTM03B.CBL} ({@code app/cbl/CBSTM03B.CBL}),
 * orchestrated on the mainframe by {@code legacy/jcl/CREASTMT.JCL} ({@code app/jcl/CREASTMT.JCL}).
 *
 * <h2>Parity contract under test (CBSTM03A / CBSTM03B)</h2>
 * <p>For every card in the cross-reference the job emits one account statement in <strong>two
 * output formats simultaneously</strong>:</p>
 * <ul>
 *   <li><strong>plain text</strong> &mdash; {@code FD-STMTFILE-REC PIC X(80)} (CBSTM03A L45): every
 *       record is exactly <strong>80</strong> characters. The body opens with a
 *       {@code START OF STATEMENT} banner line (CBSTM03A L88), closes with an {@code END OF STATEMENT}
 *       banner (L145), and uses full-width separator lines of eighty {@code '-'} characters
 *       ({@code FILLER VALUE ALL '-' PIC X(80)}).</li>
 *   <li><strong>HTML</strong> &mdash; {@code FD-HTMLFILE-REC PIC X(100)} (CBSTM03A L47): every record
 *       is exactly <strong>100</strong> characters and the document opens with
 *       {@code <!DOCTYPE html>}, then {@code <html lang="en">}, {@code <head>}, &hellip;</li>
 * </ul>
 * <p>The COBOL driver performs no file control of its own: it issues {@code CALL 'CBSTM03B'}
 * (13&times;) for every VSAM read. That subprogram is re-platformed as the injected
 * {@link StatementFileService} bean (AAP &sect;0.5.7), which {@link StatementGenerationJob}'s
 * processor consumes. Within each statement, the transactions are listed ordered by
 * {@code (cardNum, tranId)} &mdash; the {@code CREASTMT.JCL} STEP010
 * {@code SORT FIELDS=(263,16,CH,A,1,16,CH,A)} key (L53) &mdash; and every monetary figure is a
 * scale-2 {@link BigDecimal} (never {@code double}/{@code float}).</p>
 *
 * <h2>Golden scenario (byte-exact, fully deterministic)</h2>
 * <p>The bounded scenario documented in {@code src/test/resources/golden/statement/README.md} is
 * seeded exactly &mdash; one customer, one account, one card, one cross-reference, and three
 * transactions &mdash; and the job is launched. Because the statement body carries no run-clock
 * timestamp, both outputs are fully deterministic and are asserted row-for-row (and byte-for-byte on
 * size) against the shipped golden fixtures {@code golden/statement/expected-statement.txt} (22
 * fixed-width 80-character records, {@value #EXPECTED_TEXT_SIZE} bytes) and
 * {@code golden/statement/expected-statement.html} (97 fixed-width 100-character records,
 * {@value #EXPECTED_HTML_SIZE} bytes). Both fixtures are LF-only and end with a trailing newline,
 * matching the writer's ISO-8859-1 fixed-width emission.</p>
 *
 * <h2>Critical test isolation</h2>
 * <p>{@code CBSTM03A} emits <strong>one statement per cross-reference record</strong> it reads
 * sequentially, so the byte-for-byte comparison only holds when exactly this scenario's rows are
 * present. Under the {@code test} profile every business table starts empty (the bulk seed loader is
 * {@code local}-only), and {@link #cleanBusinessData()} truncates any residue before each test, so
 * the job produces exactly one text statement and one HTML statement equal to the fixtures. The
 * static reference tables ({@code transaction_type}, {@code transaction_category},
 * {@code disclosure_group}) are supplied by Flyway {@code V2__reference_data.sql}.</p>
 *
 * <h2>Harness</h2>
 * <p>The full application context boots under the {@code test} profile against a real
 * PostgreSQL&nbsp;16 supplied by Testcontainers and wired through {@link ServiceConnection}, so the
 * datasource carries no hardcoded URL or credentials (AAP &sect;0.8.1 / &sect;0.9.3). Flyway applies
 * the production migrations and Hibernate validates the entity mappings against that schema.
 * {@code spring.batch.job.enabled=false} keeps the job from running at startup, so it is launched
 * explicitly through {@link JobLauncherTestUtils}. Because the application declares many {@link Job}
 * beans, the auto-registered {@link JobLauncherTestUtils} cannot resolve a unique job; the nested
 * {@link BatchTestConfig} therefore contributes a {@link Primary @Primary} {@link JobLauncherTestUtils}
 * bound by qualifier to {@code statementGenerationJob}. The writer's config-driven output locations
 * ({@code carddemo.batch.statement.output-directory} / {@code text-file} / {@code html-file}) are
 * redirected to a disposable per-class temporary directory via
 * {@link #statementOutputProperties(DynamicPropertyRegistry)}. The class is intentionally not
 * transactional so each seed save commits immediately and is visible to the batch reader (which runs
 * in its own transaction).</p>
 *
 * <p>The injected {@link StatementFileService} is wrapped by {@link MockitoSpyBean} so
 * {@link #statementFileServiceInvoked()} can verify the job routes its reads through that bean
 * (the parity analog of the 13&times; {@code CALL 'CBSTM03B'}); the spy delegates to the real
 * implementation, so the golden outputs are unaffected.</p>
 *
 * @see StatementGenerationJob
 * @see StatementFileService
 * @see <a href="http://www.apache.org/licenses/LICENSE-2.0">Apache License 2.0</a>
 */
@SpringBootTest
@ActiveProfiles("test")
@SpringBatchTest
@Testcontainers
class StatementGenerationJobTest {

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
    // Fixed-width record widths (CBSTM03A FD entries) and golden fixture facts
    // ------------------------------------------------------------------------

    /** Text record width &mdash; {@code FD-STMTFILE-REC PIC X(80)} (CBSTM03A L45). */
    private static final int TEXT_RECORD_LENGTH = 80;

    /** HTML record width &mdash; {@code FD-HTMLFILE-REC PIC X(100)} (CBSTM03A L47). */
    private static final int HTML_RECORD_LENGTH = 100;

    /** Number of records in the golden text statement (header + 3 details + totals/banners). */
    private static final int EXPECTED_TEXT_RECORD_COUNT = 22;

    /** Number of records in the golden HTML statement. */
    private static final int EXPECTED_HTML_RECORD_COUNT = 97;

    /** Byte-exact golden text size: 22 records &times; (80 data bytes + 1 LF) = 1782 bytes. */
    private static final long EXPECTED_TEXT_SIZE = 1782L;

    /** Byte-exact golden HTML size: 97 records &times; (100 data bytes + 1 LF) = 9797 bytes. */
    private static final long EXPECTED_HTML_SIZE = 9797L;

    /** Classpath location of the authoritative expected plain-text statement. */
    private static final String GOLDEN_TEXT_RESOURCE = "golden/statement/expected-statement.txt";

    /** Classpath location of the authoritative expected HTML statement. */
    private static final String GOLDEN_HTML_RESOURCE = "golden/statement/expected-statement.html";

    /** Sentinel contained in the first text record ({@code ST-LINE0}). */
    private static final String START_SENTINEL = "START OF STATEMENT";

    /** Sentinel contained in the last text record ({@code ST-LINE15}). */
    private static final String END_SENTINEL = "END OF STATEMENT";

    /** Full-width plain-text separator rule ({@code FILLER VALUE ALL '-' PIC X(80)}). */
    private static final String TEXT_SEPARATOR = "-".repeat(TEXT_RECORD_LENGTH);

    // ------------------------------------------------------------------------
    // Detail-line column geometry (ST-LINE14: id(16) + ' ' + desc(49) + '$' + amt(13) = 80)
    // ------------------------------------------------------------------------

    /** Zero-based end offset (exclusive) of the 16-character transaction id in a detail line. */
    private static final int TRAN_ID_END = 16;

    /** Zero-based index of the single space that follows the transaction id in a detail line. */
    private static final int TRAN_ID_GAP_INDEX = 16;

    /** Zero-based index of the {@code '$'} marker that precedes the edited amount in a detail line. */
    private static final int AMOUNT_MARKER_INDEX = 66;

    /** Zero-based start offset of the {@code PIC Z(9).99-} edited amount field in a detail line. */
    private static final int AMOUNT_FIELD_START = 67;

    // ------------------------------------------------------------------------
    // Bounded scenario identifiers and values (golden/statement/README.md)
    // ------------------------------------------------------------------------

    /** Scenario customer id ({@code XREF-CUST-ID}); renders "John Q Public". */
    private static final long CUST_ID = 900000090L;

    /** Scenario account id ({@code XREF-ACCT-ID}); current balance +1234.56. */
    private static final long ACCT_ID = 90000000090L;

    /** Scenario card number ({@code XREF-CARD-NUM}); the sole cross-reference row. */
    private static final String CARD_NUM = "9000000000000090";

    /** Account current balance ({@code ACCT-CURR-BAL}); edited to {@code 000001234.56 } on the text. */
    private static final BigDecimal CURR_BAL = new BigDecimal("1234.56");

    /** Disclosure-group family key; {@code account.group_id} carries no DB FK (app-level RI). */
    private static final String GROUP_ID = "DEFAULT";

    /** Customer FICO score ({@code CUST-FICO-CREDIT-SCORE}); edited to {@code 750} on the statement. */
    private static final int FICO_SCORE = 750;

    /** Transaction-type code used for the three scenario transactions (valid FK; irrelevant to output). */
    private static final String TYPE_CD = "01";

    /** Transaction-category code used for the three scenario transactions (valid FK; irrelevant to output). */
    private static final int CAT_CD = 1;

    /** First scenario transaction id (sorts first); "Purchase at Store A", +100.00. */
    private static final String TRAN_ID_1 = "0000000000000001";

    /** Second scenario transaction id; "Purchase at Store B", +250.50. */
    private static final String TRAN_ID_2 = "0000000000000002";

    /** Third scenario transaction id (sorts last); "Refund from Store C", -25.75. */
    private static final String TRAN_ID_3 = "0000000000000003";

    /**
     * The three scenario transaction ids in the exact order the statement must list them: ascending
     * by {@code (cardNum, tranId)} &mdash; with a single card this reduces to {@code tranId} ascending.
     */
    private static final List<String> EXPECTED_ORDERED_TRAN_IDS =
            List.of(TRAN_ID_1, TRAN_ID_2, TRAN_ID_3);

    /** The three scenario amounts in {@code (cardNum, tranId)} order, as scale-2 {@link BigDecimal}. */
    private static final List<BigDecimal> EXPECTED_ORDERED_AMOUNTS = List.of(
            new BigDecimal("100.00"), new BigDecimal("250.50"), new BigDecimal("-25.75"));

    /** Plain-text output file name; pinned onto {@code carddemo.batch.statement.text-file}. */
    private static final String TEXT_FILE_NAME = "statements.txt";

    /** HTML output file name; pinned onto {@code carddemo.batch.statement.html-file}. */
    private static final String HTML_FILE_NAME = "statements.html";

    /**
     * Per-class temporary directory that receives the produced statement files. Created eagerly at
     * class-load time (not through {@code @TempDir}) so it is available when
     * {@link #statementOutputProperties(DynamicPropertyRegistry)} runs during context preparation.
     */
    private static final Path OUTPUT_DIR = createOutputDirectory();

    /** Supplies unique {@code run.id} values so every launch is a distinct {@link Job} instance. */
    private static final AtomicLong RUN_ID = new AtomicLong();

    // ------------------------------------------------------------------------
    // Collaborators (constructor injection; no field @Autowired per AAP 0.6.4)
    // ------------------------------------------------------------------------

    /**
     * The statement file service (the re-platformed {@code CBSTM03B}) wrapped as a Mockito spy so
     * {@link #statementFileServiceInvoked()} can verify the job routes every read through it. The spy
     * delegates to the real bean, so all other tests (including the byte-exact golden comparisons)
     * observe the unmodified production behavior. This is a Spring bean override, not field
     * {@code @Autowired} injection, and is reset by the framework between test methods.
     */
    @MockitoSpyBean
    private StatementFileService statementFileService;

    /** Utilities bound to the {@code statementGenerationJob} bean (see {@link BatchTestConfig}). */
    private final JobLauncherTestUtils jobLauncherTestUtils;

    /** Batch metadata helper used to clear job executions between relaunches. */
    private final JobRepositoryTestUtils jobRepositoryTestUtils;

    /** The running application context, used to assert the job/step beans are registered. */
    private final ApplicationContext applicationContext;

    /** Repository used to seed the single scenario cross-reference (card&rarr;customer&rarr;account). */
    private final CardXrefRepository cardXrefRepository;

    /** Repository used to seed the three scenario transactions (the statement's transaction summary). */
    private final TransactionRepository transactionRepository;

    /** JDBC template used to seed and remove the {@code customer}/{@code account}/{@code card} parents. */
    private final JdbcTemplate jdbcTemplate;

    /**
     * Constructor injection of the Spring-managed collaborators. Only field assignments are performed
     * (no overridable method is invoked), so no reference to a partially-constructed instance escapes.
     * The {@link #statementFileService} spy is a separate bean-override field populated by the
     * framework and therefore not a constructor parameter.
     *
     * @param jobLauncherTestUtils   the job launcher utilities bound to {@code statementGenerationJob}
     * @param jobRepositoryTestUtils the batch metadata helper for inter-test cleanup
     * @param applicationContext     the running application context
     * @param cardXrefRepository     the card cross-reference repository (scenario seeding)
     * @param transactionRepository  the transaction repository (statement input seeding)
     * @param jdbcTemplate           the JDBC template used to seed the parent rows
     */
    StatementGenerationJobTest(JobLauncherTestUtils jobLauncherTestUtils,
                               JobRepositoryTestUtils jobRepositoryTestUtils,
                               ApplicationContext applicationContext,
                               CardXrefRepository cardXrefRepository,
                               TransactionRepository transactionRepository,
                               JdbcTemplate jdbcTemplate) {
        this.jobLauncherTestUtils = jobLauncherTestUtils;
        this.jobRepositoryTestUtils = jobRepositoryTestUtils;
        this.applicationContext = applicationContext;
        this.cardXrefRepository = cardXrefRepository;
        this.transactionRepository = transactionRepository;
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * Redirects the statement writer's config-driven output locations to the disposable per-class
     * temporary directory, with no hardcoded path.
     *
     * <p>{@code StatementItemWriter} resolves its output from
     * {@code carddemo.batch.statement.output-directory} (default {@code ./target/batch}),
     * {@code carddemo.batch.statement.text-file} (default {@code statements.txt}) and
     * {@code carddemo.batch.statement.html-file} (default {@code statements.html}); pinning all three
     * here keeps every produced statement inside a temporary directory the test fully controls.</p>
     *
     * @param registry the Spring test property registry to populate
     */
    @DynamicPropertySource
    static void statementOutputProperties(DynamicPropertyRegistry registry) {
        registry.add("carddemo.batch.statement.output-directory", OUTPUT_DIR::toString);
        registry.add("carddemo.batch.statement.text-file", () -> TEXT_FILE_NAME);
        registry.add("carddemo.batch.statement.html-file", () -> HTML_FILE_NAME);
    }

    /**
     * Creates the per-class temporary output directory.
     *
     * @return the created temporary directory
     * @throws UncheckedIOException if the directory cannot be created
     */
    private static Path createOutputDirectory() {
        try {
            return Files.createTempDirectory("carddemo-statement-generation-test-");
        } catch (IOException ex) {
            throw new UncheckedIOException("Unable to create temporary statement output directory", ex);
        }
    }

    /**
     * Removes the temporary output directory (and the statement files it may still hold) after all
     * tests.
     *
     * @throws IOException if the directory contents cannot be removed
     */
    @AfterAll
    static void removeOutputDirectory() throws IOException {
        Files.deleteIfExists(OUTPUT_DIR.resolve(TEXT_FILE_NAME));
        Files.deleteIfExists(OUTPUT_DIR.resolve(HTML_FILE_NAME));
        Files.deleteIfExists(OUTPUT_DIR);
    }

    /**
     * Seeds the canonical golden statement scenario before each test and removes any statement files
     * left by a prior run. Rows are inserted in foreign-key dependency order (parents first): the
     * {@code customer}, {@code account} and {@code card} parents via native SQL, then the single
     * cross-reference via {@link CardXrefRepository} and the three transactions via
     * {@link TransactionRepository}.
     *
     * <p>The three transactions are deliberately inserted out of natural id order and given
     * <em>descending</em> processing timestamps relative to their ascending ids. Because the backing
     * query orders by {@code proc_ts} and the statement contract requires the
     * {@code CREASTMT SORT FIELDS=(...,1,16,CH,A)} {@code tranId} secondary key, this makes
     * {@link #transactionsOrderedByCardThenTranId()} a genuine ordering assertion (proc_ts order would
     * differ from the required id order). The statement body embeds no timestamp, so the golden
     * byte-for-byte outputs are unaffected by these {@code proc_ts} values.</p>
     *
     * @throws IOException if a stale statement file cannot be deleted
     */
    @BeforeEach
    void seedScenario() throws IOException {
        cleanBusinessData();
        Files.deleteIfExists(textFile());
        Files.deleteIfExists(htmlFile());

        jdbcTemplate.update(
                "INSERT INTO customer (cust_id, cust_first_name, cust_middle_name, cust_last_name, "
                        + "cust_addr_line_1, cust_addr_line_2, cust_addr_line_3, cust_addr_state_cd, "
                        + "cust_addr_country_cd, cust_addr_zip, cust_fico_credit_score) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                CUST_ID, "John", "Q", "Public",
                "123 Main Street", "Apt 4B", "Seattle", "WA",
                "USA", "98101", FICO_SCORE);

        jdbcTemplate.update(
                "INSERT INTO account (acct_id, acct_active_status, curr_bal, group_id) "
                        + "VALUES (?, ?, ?, ?)",
                ACCT_ID, "Y", CURR_BAL, GROUP_ID);

        jdbcTemplate.update(
                "INSERT INTO card (card_num, acct_id, card_active_status) VALUES (?, ?, ?)",
                CARD_NUM, ACCT_ID, "Y");

        cardXrefRepository.save(new CardXref(CARD_NUM, CUST_ID, ACCT_ID));

        // Inserted out of id order with proc_ts descending vs id so the (cardNum, tranId) secondary
        // sort key is genuinely exercised; the statement output carries no timestamp, so the golden
        // fixtures remain byte-identical regardless of these values.
        transactionRepository.saveAll(List.of(
                newTransaction(TRAN_ID_2, "Purchase at Store B", new BigDecimal("250.50"),
                        "2025-02-01-00.00.00.000000"),
                newTransaction(TRAN_ID_3, "Refund from Store C", new BigDecimal("-25.75"),
                        "2025-01-01-00.00.00.000000"),
                newTransaction(TRAN_ID_1, "Purchase at Store A", new BigDecimal("100.00"),
                        "2025-03-01-00.00.00.000000")));
    }

    /**
     * Clears the batch job-execution metadata (so relaunches start clean), removes the seeded
     * business data, and deletes any produced statement files after each test.
     *
     * @throws IOException if a produced statement file cannot be deleted
     */
    @AfterEach
    void tidyUp() throws IOException {
        jobRepositoryTestUtils.removeJobExecutions();
        cleanBusinessData();
        Files.deleteIfExists(textFile());
        Files.deleteIfExists(htmlFile());
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
     * The context wires the statement job. Confirms the {@code statementGenerationJob} {@link Job}
     * bean and the {@code statementGenerationStep} {@link Step} bean are registered under their
     * expected names, and that the test's {@link JobLauncherTestUtils} is bound to the correct job
     * (proving the multi-job resolution in {@link BatchTestConfig} succeeded). Reaching this assertion
     * transitively proves the full context (all layers, Flyway migration, and Hibernate schema
     * validation) started.
     */
    @Test
    @DisplayName("context loads and the statementGenerationJob/Step beans are registered")
    void contextLoadsAndJobRegistered() {
        assertThat(applicationContext).isNotNull();

        assertThat(applicationContext.containsBean("statementGenerationJob")).isTrue();
        Job job = applicationContext.getBean("statementGenerationJob", Job.class);
        assertThat(job.getName()).isEqualTo("statementGenerationJob");

        assertThat(applicationContext.containsBean("statementGenerationStep")).isTrue();
        assertThat(applicationContext.getBean("statementGenerationStep", Step.class)).isNotNull();

        assertThat(jobLauncherTestUtils.getJob().getName()).isEqualTo("statementGenerationJob");
    }

    /**
     * A clean run over the bounded scenario completes with {@link BatchStatus#COMPLETED} and the
     * return-code-0 {@link ExitStatus#COMPLETED} exit status (the {@code CBSTM03A}
     * {@code CLOSE STMT-FILE HTML-FILE} success path). Both configured output files are produced and
     * non-empty.
     *
     * @throws Exception if the job launch fails
     */
    @Test
    @DisplayName("job runs clean with COMPLETED status and return code 0")
    void runsCleanWithReturnCodeZero() throws Exception {
        JobExecution execution = jobLauncherTestUtils.launchJob(statementParams());

        assertThat(execution.getStatus()).isEqualTo(BatchStatus.COMPLETED);
        assertThat(execution.getExitStatus().getExitCode())
                .isEqualTo(ExitStatus.COMPLETED.getExitCode());

        assertThat(Files.exists(textFile())).as("plain-text statement file produced").isTrue();
        assertThat(Files.exists(htmlFile())).as("HTML statement file produced").isTrue();
        assertThat(Files.size(textFile())).as("text statement is non-empty").isPositive();
        assertThat(Files.size(htmlFile())).as("HTML statement is non-empty").isPositive();
    }

    /**
     * The produced plain-text statement equals the golden fixture row-for-row. Also asserts the
     * layout invariants the {@code FD-STMTFILE-REC PIC X(80)} contract dictates: every record is
     * exactly {@value #TEXT_RECORD_LENGTH} characters (no trimming), the {@code START OF STATEMENT}
     * and {@code END OF STATEMENT} banner lines bracket the body, the full-width eighty-dash
     * separators are present, and the total byte size matches the golden exactly.
     *
     * @throws Exception if the job launch or file read fails
     */
    @Test
    @DisplayName("plain-text statement matches golden row-for-row (80-char records)")
    void textStatementMatchesGolden() throws Exception {
        JobExecution execution = jobLauncherTestUtils.launchJob(statementParams());
        assertThat(execution.getStatus()).isEqualTo(BatchStatus.COMPLETED);

        List<String> produced = readProducedLines(textFile());
        List<String> golden = readGoldenLines(GOLDEN_TEXT_RESOURCE);

        assertThat(produced)
                .as("text statement is byte/row-identical to the golden fixture")
                .containsExactlyElementsOf(golden);

        assertThat(produced)
                .as("exactly one statement was emitted (one XREF row)")
                .hasSize(EXPECTED_TEXT_RECORD_COUNT);
        assertThat(produced)
                .as("every text record is exactly %d characters (no trimming)", TEXT_RECORD_LENGTH)
                .allSatisfy(line -> assertThat(line).hasSize(TEXT_RECORD_LENGTH));

        assertThat(produced.get(0))
                .as("first record is the START OF STATEMENT banner")
                .contains(START_SENTINEL);
        assertThat(produced.get(produced.size() - 1))
                .as("last record is the END OF STATEMENT banner")
                .contains(END_SENTINEL);
        assertThat(produced)
                .as("full-width 80-dash separator rules are present")
                .contains(TEXT_SEPARATOR);

        assertThat(Files.size(textFile()))
                .as("byte-exact text statement size")
                .isEqualTo(EXPECTED_TEXT_SIZE);
    }

    /**
     * The produced HTML statement equals the golden fixture row-for-row. Also asserts the
     * {@code FD-HTMLFILE-REC PIC X(100)} contract: every record is exactly
     * {@value #HTML_RECORD_LENGTH} characters, the document opens with {@code <!DOCTYPE html>}, then
     * {@code <html lang="en">}, then {@code <head>}, and the total byte size matches the golden
     * exactly. Prefixes are compared after stripping the fixed-width trailing padding.
     *
     * @throws Exception if the job launch or file read fails
     */
    @Test
    @DisplayName("HTML statement matches golden row-for-row (100-char records)")
    void htmlStatementMatchesGolden() throws Exception {
        JobExecution execution = jobLauncherTestUtils.launchJob(statementParams());
        assertThat(execution.getStatus()).isEqualTo(BatchStatus.COMPLETED);

        List<String> produced = readProducedLines(htmlFile());
        List<String> golden = readGoldenLines(GOLDEN_HTML_RESOURCE);

        assertThat(produced)
                .as("HTML statement is byte/row-identical to the golden fixture")
                .containsExactlyElementsOf(golden);

        assertThat(produced)
                .as("exactly one HTML statement was emitted (one XREF row)")
                .hasSize(EXPECTED_HTML_RECORD_COUNT);
        assertThat(produced)
                .as("every HTML record is exactly %d characters (no trimming)", HTML_RECORD_LENGTH)
                .allSatisfy(line -> assertThat(line).hasSize(HTML_RECORD_LENGTH));

        assertThat(produced.get(0).stripTrailing()).isEqualTo("<!DOCTYPE html>");
        assertThat(produced.get(1).stripTrailing()).isEqualTo("<html lang=\"en\">");
        assertThat(produced.get(2).stripTrailing()).isEqualTo("<head>");

        assertThat(Files.size(htmlFile()))
                .as("byte-exact HTML statement size")
                .isEqualTo(EXPECTED_HTML_SIZE);
    }

    /**
     * Within a card's statement the transactions are listed ordered by {@code (cardNum, tranId)} &mdash;
     * the {@code CREASTMT.JCL} STEP010 {@code SORT FIELDS=(263,16,CH,A,1,16,CH,A)} key. The scenario
     * seeds the three transactions out of id order and with {@code proc_ts} values that <em>descend</em>
     * as the ids ascend, so an ordering that honoured {@code proc_ts} (or insertion order) would emit
     * them reversed. Asserting the emitted detail rows appear in ascending {@code tranId} order &mdash;
     * with their signed amounts as scale-2 {@link BigDecimal} &mdash; therefore proves the required
     * secondary sort key is applied.
     *
     * @throws Exception if the job launch or file read fails
     */
    @Test
    @DisplayName("statement lists transactions ordered by (cardNum, tranId)")
    void transactionsOrderedByCardThenTranId() throws Exception {
        JobExecution execution = jobLauncherTestUtils.launchJob(statementParams());
        assertThat(execution.getStatus()).isEqualTo(BatchStatus.COMPLETED);

        List<String> detailLines = detailLines(readProducedLines(textFile()));

        List<String> tranIds = detailLines.stream()
                .map(line -> line.substring(0, TRAN_ID_END))
                .toList();
        assertThat(tranIds)
                .as("detail rows are ordered ascending by (cardNum, tranId)")
                .containsExactlyElementsOf(EXPECTED_ORDERED_TRAN_IDS)
                .isSorted();

        List<BigDecimal> amounts = detailLines.stream()
                .map(StatementGenerationJobTest::parseSignedAmount)
                .toList();
        assertThat(amounts)
                .as("amounts are scale-2 BigDecimal values in (cardNum, tranId) order")
                .hasSize(EXPECTED_ORDERED_AMOUNTS.size());
        for (int i = 0; i < amounts.size(); i++) {
            assertThat(amounts.get(i)).isEqualByComparingTo(EXPECTED_ORDERED_AMOUNTS.get(i));
            assertThat(amounts.get(i).scale()).isEqualTo(2);
        }
    }

    /**
     * The job routes every statement read through the injected {@link StatementFileService} bean &mdash;
     * the parity analog of the COBOL {@code CALL 'CBSTM03B'} (invoked 13&times;). After a clean run the
     * spy is verified to have resolved the scenario customer, account and per-card transactions,
     * confirming the {@code CBSTM03A} &rarr; {@code CBSTM03B} delegation is preserved as bean injection
     * (AAP &sect;0.5.7). The spy delegates to the real implementation, so the run itself is unaffected.
     *
     * @throws Exception if the job launch fails
     */
    @Test
    @DisplayName("statement reads are routed through the injected StatementFileService (CBSTM03B parity)")
    void statementFileServiceInvoked() throws Exception {
        JobExecution execution = jobLauncherTestUtils.launchJob(statementParams());
        assertThat(execution.getStatus()).isEqualTo(BatchStatus.COMPLETED);

        verify(statementFileService, atLeastOnce()).readCustomer(CUST_ID);
        verify(statementFileService, atLeastOnce()).readAccount(ACCT_ID);
        verify(statementFileService, atLeastOnce()).readTransactionsForCard(CARD_NUM);
    }


    // ------------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------------

    /**
     * Builds unique {@link JobParameters} for a statement run.
     *
     * <p>A fresh {@code run.id} makes every launch a distinct job instance. This helper deliberately
     * returns {@link JobParameters} rather than {@link JobExecution}: a test-class method whose return
     * type is {@code JobExecution} (or {@code StepExecution}) is picked up by {@link SpringBatchTest}'s
     * job/step-scope test listeners as a scope factory and invoked with no arguments, which would fail
     * here. Each test therefore launches with {@code jobLauncherTestUtils.launchJob(statementParams())}.</p>
     *
     * @return unique job parameters carrying a fresh {@code run.id}
     */
    private JobParameters statementParams() {
        return new JobParametersBuilder()
                .addLong("run.id", RUN_ID.incrementAndGet())
                .toJobParameters();
    }

    /**
     * Resolves the produced plain-text statement file inside the per-class temporary directory.
     *
     * @return the plain-text statement file path
     */
    private static Path textFile() {
        return OUTPUT_DIR.resolve(TEXT_FILE_NAME);
    }

    /**
     * Resolves the produced HTML statement file inside the per-class temporary directory.
     *
     * @return the HTML statement file path
     */
    private static Path htmlFile() {
        return OUTPUT_DIR.resolve(HTML_FILE_NAME);
    }

    /**
     * Reads a produced statement file as fixed-width lines using ISO-8859-1 (one byte per character),
     * so the 80-/100-character records are preserved without any charset re-interpretation and without
     * trimming the significant trailing padding.
     *
     * @param file the produced statement file
     * @return the produced records in emission order
     * @throws IOException if the file cannot be read
     */
    private static List<String> readProducedLines(Path file) throws IOException {
        return Files.readAllLines(file, StandardCharsets.ISO_8859_1);
    }

    /**
     * Reads a golden fixture from the classpath as lines, using ISO-8859-1 to match the statement's
     * byte-faithful fixed-width encoding.
     *
     * @param resource the classpath location of the golden fixture
     * @return the expected records in order
     * @throws IOException if the golden resource cannot be read
     */
    private static List<String> readGoldenLines(String resource) throws IOException {
        ClassPathResource golden = new ClassPathResource(resource);
        try (InputStream in = golden.getInputStream();
             BufferedReader reader =
                     new BufferedReader(new InputStreamReader(in, StandardCharsets.ISO_8859_1))) {
            return reader.lines().toList();
        }
    }

    /**
     * Returns only the transaction detail lines of a plain-text statement ({@code ST-LINE14}):
     * records whose first {@value #TRAN_ID_END} characters are the numeric transaction id, whose
     * following character is the field gap, and which carry the {@code '$'} amount marker at its fixed
     * column. Header, address, banner, separator and total lines are thereby excluded.
     *
     * @param lines all produced statement records
     * @return the detail records in emission order
     */
    private static List<String> detailLines(List<String> lines) {
        return lines.stream()
                .filter(line -> line.length() == TEXT_RECORD_LENGTH)
                .filter(line -> line.charAt(TRAN_ID_GAP_INDEX) == ' ')
                .filter(line -> line.charAt(AMOUNT_MARKER_INDEX) == '$')
                .filter(line -> line.substring(0, TRAN_ID_END).chars().allMatch(Character::isDigit))
                .toList();
    }

    /**
     * Parses a {@code PIC Z(9).99-} edited amount ({@code ST-TRANAMT}) from a detail line into a
     * scale-2 {@link BigDecimal}. The field is thirteen characters: nine zero-suppressed integer
     * positions, a decimal point, two fraction digits, and a trailing sign ({@code '-'} for negative,
     * a space for non-negative). Leading padding spaces are removed; the trailing sign is honoured.
     * Parsing never uses {@code double}/{@code float}.
     *
     * @param detailLine a statement detail record
     * @return the parsed amount at scale 2
     */
    private static BigDecimal parseSignedAmount(String detailLine) {
        String field = detailLine.substring(AMOUNT_FIELD_START, TEXT_RECORD_LENGTH);
        boolean negative = field.endsWith("-");
        String magnitude = field.substring(0, field.length() - 1).replace(" ", "");
        BigDecimal amount = new BigDecimal(magnitude).setScale(2, RoundingMode.HALF_UP);
        return negative ? amount.negate() : amount;
    }

    /**
     * Builds a transient {@link Transaction} for the statement scenario. The statement renders only
     * the transaction id, description and edited amount; the transaction type/category are set to a
     * valid reference-data pair (required by the foreign keys but irrelevant to the statement output),
     * and the merchant fields are left {@code null} (all nullable). The origination timestamp is set
     * equal to the supplied processing timestamp.
     *
     * @param tranId the 16-character transaction id ({@code TRAN-ID})
     * @param desc   the transaction description ({@code TRAN-DESC}) shown on the statement
     * @param amount the signed amount at scale 2 ({@code TRAN-AMT})
     * @param procTs the 26-character processing timestamp ({@code TRAN-PROC-TS})
     * @return a new transient transaction row for {@link #CARD_NUM}
     */
    private static Transaction newTransaction(String tranId, String desc, BigDecimal amount,
                                              String procTs) {
        return new Transaction(
                tranId, TYPE_CD, CAT_CD, "POS", desc, amount,
                null, null, null, null, CARD_NUM, procTs, procTs);
    }

    /**
     * Test-scoped configuration that supplies an explicitly-bound {@link JobLauncherTestUtils}.
     *
     * <p>With many {@link Job} beans on the context, the {@link SpringBatchTest}-provided
     * {@code JobLauncherTestUtils} cannot bind a unique job. This {@link Primary @Primary} bean
     * (registered under a distinct name to avoid clashing with the auto-registered one) binds the job
     * explicitly by qualifier and reuses the auto-configured {@link JobLauncher} and
     * {@link JobRepository}, so this test always launches {@code statementGenerationJob}.</p>
     */
    @TestConfiguration
    static class BatchTestConfig {

        /**
         * Builds the {@link Primary @Primary} launcher-test utility bound to
         * {@code statementGenerationJob}.
         *
         * @param statementGenerationJob the job under test, bound by qualifier
         * @param jobLauncher            the auto-configured batch job launcher
         * @param jobRepository          the auto-configured batch job repository
         * @return a {@link JobLauncherTestUtils} that launches only {@code statementGenerationJob}
         */
        @Bean
        @Primary
        JobLauncherTestUtils statementJobLauncherTestUtils(
                @Qualifier("statementGenerationJob") Job statementGenerationJob,
                JobLauncher jobLauncher,
                JobRepository jobRepository) {
            JobLauncherTestUtils utils = new JobLauncherTestUtils();
            utils.setJob(statementGenerationJob);
            utils.setJobLauncher(jobLauncher);
            utils.setJobRepository(jobRepository);
            return utils;
        }
    }
}

