package com.carddemo.batch;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
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
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;

import com.carddemo.entity.CardXref;
import com.carddemo.entity.Transaction;
import com.carddemo.repository.CardXrefRepository;
import com.carddemo.repository.TransactionRepository;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code @SpringBatchTest} integration test for {@link StatementCreationJobConfig}, the Spring Batch&nbsp;5
 * configuration that hosts the <strong>two</strong> reporting jobs of the migrated CardDemo application.
 * This single class deliberately covers both, because both are produced by the same configuration and
 * both must be byte-faithful to their COBOL ancestry:
 *
 * <ol>
 *   <li><strong>{@code statementCreationJob}</strong> &mdash; the Java port of the COBOL statement batch
 *       {@code app/cbl/CBSTM03A.CBL} (and its VSAM-I/O subroutine {@code CBSTM03B.CBL}). It iterates every
 *       account in {@code acctId} order and delegates per-account assembly to
 *       {@code StatementService.generateStatement(acctId, startDate, endDate)}, which writes a plain-text
 *       {@code statement_<acctId>_<start>_<end>.txt} and a matching {@code .html} into the
 *       {@code report.output.path} directory. It is <em>database read-only</em> (it writes files only).</li>
 *   <li><strong>{@code transactionReportJob}</strong> &mdash; the Java port of the COBOL transaction detail
 *       report {@code app/cbl/CBTRN03C.cbl} (the F-009 batch utility, layout {@code app/cpy/CVTRA07Y.cpy}).
 *       It is a self-contained, single-tasklet <em>control-break</em> report: it scans accounts in order,
 *       pages each account's transactions, filters to an inclusive {@code [startDate, endDate]} window,
 *       resolves transaction-type / category descriptions, prints a detail line per transaction, and emits
 *       a per-account subtotal plus a grand total to a
 *       {@code transaction-report-<label>-<epochMillis>.txt} file under {@code report.output.path}.</li>
 * </ol>
 *
 * <h2>Why both jobs are injected by {@code @Qualifier}</h2>
 * <p>Autowiring each {@link Job} by its exact bean name proves both beans exist with the names the rest of
 * the system depends on. The {@code transactionReportJob} name is contractually load-bearing:
 * {@code com.carddemo.service.ReportService} resolves the report job by this exact key from an injected
 * {@code Map<String, Job>} ({@code jobs.get("transactionReportJob")}); renaming the bean would silently
 * break asynchronous report submission. Because the application context defines several {@code Job} beans,
 * {@link JobLauncherTestUtils} cannot auto-bind a unique job, so each test selects the job it exercises via
 * {@link JobLauncherTestUtils#setJob(Job)} (the shared, auto-configured {@code JobLauncher} /
 * {@code JobRepository} are injected automatically by {@link SpringBatchTest}).</p>
 *
 * <h2>Test profile &amp; seed</h2>
 * <p>The {@code test} profile ({@code src/test/resources/application-test.yml}) runs against an in-memory
 * H2 database in PostgreSQL-compatibility mode, applies Flyway migrations {@code V1}&ndash;{@code V4} at
 * context startup, creates the {@code BATCH_*} metadata tables
 * ({@code spring.batch.jdbc.initialize-schema=always}) and disables auto-launch
 * ({@code spring.batch.job.enabled=false}) so these tests drive the jobs explicitly. The master seed
 * provides 50 aligned accounts / customers / card-cross-references (so every account resolves an owning
 * customer and card number for its statement), while the {@code transactions} table is seeded
 * <strong>empty</strong>. Consequently every statement renders zero transactions and the transaction
 * report renders a {@code 0.00} grand total on the raw seed; {@link
 * #transactionReportJob_aggregatesGrandTotal_overCommittedTransactions()} pre-commits two transactions to
 * exercise a non-zero control-break total.</p>
 *
 * <h2>Isolation strategy (no {@code @Transactional})</h2>
 * <p>These tests are intentionally <strong>not</strong> {@code @Transactional}: a batch job runs in its own
 * transactions, so a surrounding test transaction would hide committed rows from the job. Instead:</p>
 * <ul>
 *   <li>{@link JobRepositoryTestUtils#removeJobExecutions()} clears Spring Batch metadata before each test,
 *       and {@link JobLauncherTestUtils#getUniqueJobParameters()} guarantees a fresh {@code JobInstance};</li>
 *   <li>{@link #cleanUp()} deletes every generated {@code statement_*} / {@code transaction-report-*} file so
 *       file-name assertions stay deterministic across re-runs;</li>
 *   <li>the single test that commits domain rows is annotated
 *       {@code @DirtiesContext(methodMode = AFTER_METHOD)} (per the test contract) <em>and</em> the committed
 *       rows are explicitly removed in {@link #cleanUp()}. The defensive deletion is required because the
 *       {@code test} H2 database is configured with {@code DB_CLOSE_DELAY=-1} and therefore <em>survives</em>
 *       the context rebuild that {@code @DirtiesContext} triggers; without the deletion the committed
 *       transactions would leak into other test classes sharing the (cached) context. {@link #cleanUp()}
 *       runs while the context and database are still alive (JUnit {@code @AfterEach} fires before the
 *       {@code DirtiesContext} eviction), and deleting an absent id is a no-op, so the cleanup is safe for the
 *       statement and empty-report tests too.</li>
 * </ul>
 *
 * @see StatementCreationJobConfig
 * @see com.carddemo.service.StatementService
 * @see <a href="file:app/cbl/CBSTM03A.CBL">app/cbl/CBSTM03A.CBL (statement text + HTML layout)</a>
 * @see <a href="file:app/cbl/CBTRN03C.cbl">app/cbl/CBTRN03C.cbl (transaction detail control-break report)</a>
 * @see <a href="file:app/cpy/CVTRA07Y.cpy">app/cpy/CVTRA07Y.cpy (report record layout)</a>
 */
@SpringBatchTest
@SpringBootTest
@ActiveProfiles("test")
class StatementCreationJobTest {

    /** ISO start of the all-encompassing statement window; yields a deterministic statement file name. */
    private static final String STATEMENT_START_DATE = "2000-01-01";

    /** ISO end of the all-encompassing statement window; yields a deterministic statement file name. */
    private static final String STATEMENT_END_DATE = "2100-01-01";

    /** File-name prefix of the per-account statements written by {@code StatementService}. */
    private static final String STATEMENT_FILE_PREFIX = "statement_";

    /** File-name prefix of the transaction detail report written by {@code transactionReportJob}. */
    private static final String REPORT_FILE_PREFIX = "transaction-report-";

    /**
     * First synthetic transaction id committed by the grand-total test. It is well outside the seed range
     * (the {@code transactions} table is seeded empty), so it cannot collide with seeded data.
     */
    private static final String TEST_TRAN_ID_1 = "9000000000000001";

    /** Second synthetic transaction id committed by the grand-total test (see {@link #TEST_TRAN_ID_1}). */
    private static final String TEST_TRAN_ID_2 = "9000000000000002";

    /** Launches the job under test and supplies unique {@code JobParameters} per run. */
    @Autowired
    private JobLauncherTestUtils jobLauncherTestUtils;

    /** Clears Spring Batch metadata ({@code BATCH_*}) between test methods. */
    @Autowired
    private JobRepositoryTestUtils jobRepositoryTestUtils;

    /** The statement-generation job (CBSTM03A port), selected explicitly by its exact bean name. */
    @Autowired
    @Qualifier("statementCreationJob")
    private Job statementCreationJob;

    /**
     * The transaction detail report job (CBTRN03C port). Injecting it by the <strong>exact</strong> bean
     * name {@code transactionReportJob} guards the contract that {@code ReportService} relies on.
     */
    @Autowired
    @Qualifier("transactionReportJob")
    private Job transactionReportJob;

    /** Transaction repository &mdash; used to pre-commit (and afterwards remove) rows for the grand-total test. */
    @Autowired
    private TransactionRepository transactionRepository;

    /** Card cross-reference repository &mdash; supplies a valid {@code (acctId, cardNum)} pair from the seed. */
    @Autowired
    private CardXrefRepository cardXrefRepository;

    /** Output directory for generated statements / reports, bound from {@code report.output.path}. */
    @Value("${report.output.path}")
    private String reportOutputPath;

    /**
     * Clears any Spring Batch execution metadata left over from a previous test so each test starts from a
     * clean batch-metadata baseline. The job is intentionally <em>not</em> set here &mdash; two jobs are in
     * play, so each test selects the one it exercises via {@link JobLauncherTestUtils#setJob(Job)}.
     */
    @BeforeEach
    void setUp() {
        jobRepositoryTestUtils.removeJobExecutions();
    }

    /**
     * Restores a clean baseline after every test: removes the synthetic transactions the grand-total test
     * commits (a no-op for the other tests), then deletes every statement / report file produced under
     * {@code report.output.path}. See the class Javadoc for why the transaction deletion is required despite
     * {@code @DirtiesContext}.
     *
     * @throws IOException if the report directory cannot be listed
     */
    @AfterEach
    void cleanUp() throws IOException {
        // Remove any domain rows committed by the grand-total test. The test H2 DB persists across the
        // @DirtiesContext rebuild (DB_CLOSE_DELAY=-1), so these rows would otherwise leak into other test
        // classes. deleteAllById is a no-op for absent ids, so this is harmless for the other tests.
        transactionRepository.deleteAllById(List.of(TEST_TRAN_ID_1, TEST_TRAN_ID_2));

        // Best-effort removal of every artifact this class produced, so re-runs are deterministic.
        Path dir = Paths.get(reportOutputPath);
        if (Files.exists(dir)) {
            try (Stream<Path> entries = Files.list(dir)) {
                entries.filter(StatementCreationJobTest::isGeneratedArtifact)
                        .forEach(StatementCreationJobTest::deleteQuietly);
            }
        }
    }

    // ------------------------------------------------------------------------
    // Tests
    // ------------------------------------------------------------------------

    /**
     * Test&nbsp;1 &mdash; {@code statementCreationJob} runs to completion and, for the seeded account&nbsp;1,
     * writes both a plain-text and an HTML statement carrying the legacy banners / section headings while
     * suppressing PII (no card CVV, no customer SSN).
     *
     * @throws Exception if the job launch fails
     */
    @Test
    void statementCreationJob_writesTextAndHtmlStatements_withBannersAndNoPii() throws Exception {
        jobLauncherTestUtils.setJob(statementCreationJob);

        // An all-encompassing window makes the file name deterministic and keeps every transaction in range.
        JobParameters params = new JobParametersBuilder(jobLauncherTestUtils.getUniqueJobParameters())
                .addString("startDate", STATEMENT_START_DATE)
                .addString("endDate", STATEMENT_END_DATE)
                .toJobParameters();

        JobExecution execution = jobLauncherTestUtils.launchJob(params);
        assertThat(execution.getStatus()).isEqualTo(BatchStatus.COMPLETED);

        // The plain-text statement for account 1, named from the supplied window by StatementService.
        Path txt = Paths.get(reportOutputPath, statementFileName(1L, "txt"));
        assertThat(Files.exists(txt)).as("text statement %s", txt).isTrue();
        String text = Files.readString(txt);
        assertThat(text).contains(
                "START OF STATEMENT", "Basic Details", "TRANSACTION SUMMARY", "END OF STATEMENT");

        // The matching HTML statement.
        Path html = Paths.get(reportOutputPath, statementFileName(1L, "html"));
        assertThat(Files.exists(html)).as("html statement %s", html).isTrue();
        String htmlBody = Files.readString(html);
        assertThat(htmlBody).contains("<!DOCTYPE html>", "End of Statement");

        // PII suppression (AAP §0.6.8): neither rendition may carry the card CVV or the customer SSN.
        assertThat(text).doesNotContain("CVV");
        assertThat(htmlBody).doesNotContain("CVV");
        assertThat(text).doesNotContain("SSN");
        assertThat(htmlBody).doesNotContain("SSN");
    }

    /**
     * Test&nbsp;2 &mdash; the {@code transactionReportJob} bean exists under its exact name, runs to
     * completion against the empty-transactions seed, and produces a well-formed control-break report whose
     * grand total is zero. The {@code 0.00} token proves the report renders even with no detail rows.
     *
     * @throws Exception if the job launch fails
     */
    @Test
    void transactionReportJob_beanExists_andCompletes_onEmptyTransactions() throws Exception {
        jobLauncherTestUtils.setJob(transactionReportJob);

        JobParameters params = new JobParametersBuilder(jobLauncherTestUtils.getUniqueJobParameters())
                .addString("reportType", "CUSTOM")
                .addString("startDate", "2000-01-01")
                .addString("endDate", "2100-01-01")
                .toJobParameters();

        JobExecution execution = jobLauncherTestUtils.launchJob(params);
        assertThat(execution.getStatus()).isEqualTo(BatchStatus.COMPLETED);

        Path report = newestReportFile(REPORT_FILE_PREFIX);
        assertThat(Files.exists(report)).isTrue();

        String body = Files.readString(report);
        assertThat(body).isNotBlank();
        // The grand total is rendered with an explicit sign ("+0.00"); the "0.00" token must be present.
        assertThat(body).contains("0.00");
    }

    /**
     * Test&nbsp;3 &mdash; proves the CBTRN03C control-break grand total over real rows. Two transactions
     * ({@code 100.00} and {@code 50.00}) are committed for a single seeded account, the report is run over a
     * window covering them, and the rendered report is asserted to carry both detail amounts plus the
     * {@code 150.00} per-account subtotal / grand total.
     *
     * <p>This is the only method that commits domain rows, so it carries
     * {@code @DirtiesContext(AFTER_METHOD)} and its rows are explicitly removed in {@link #cleanUp()}.</p>
     *
     * @throws Exception if the job launch fails
     */
    @Test
    @DirtiesContext(methodMode = DirtiesContext.MethodMode.AFTER_METHOD)
    void transactionReportJob_aggregatesGrandTotal_overCommittedTransactions() throws Exception {
        // Resolve a valid (acctId, cardNum) pair from the seeded cross-reference so the committed
        // transactions satisfy the transactions table's card / account / category foreign keys.
        CardXref xref = cardXrefRepository.findAll().get(0);
        Long acctId = xref.getXrefAcctId();
        String cardNum = xref.getXrefCardNum();

        LocalDateTime ts = LocalDateTime.of(2023, 6, 15, 10, 0, 0);
        Transaction t1 = newTransaction(TEST_TRAN_ID_1, acctId, cardNum, "TEST TXN ONE",
                new BigDecimal("100.00"), ts);
        Transaction t2 = newTransaction(TEST_TRAN_ID_2, acctId, cardNum, "TEST TXN TWO",
                new BigDecimal("50.00"), ts);

        transactionRepository.saveAll(List.of(t1, t2));
        transactionRepository.flush(); // commit — the test is non-transactional

        jobLauncherTestUtils.setJob(transactionReportJob);

        JobParameters params = new JobParametersBuilder(jobLauncherTestUtils.getUniqueJobParameters())
                .addString("reportType", "CUSTOM")
                .addString("startDate", "2023-01-01")
                .addString("endDate", "2023-12-31")
                .toJobParameters();

        JobExecution execution = jobLauncherTestUtils.launchJob(params);
        assertThat(execution.getStatus()).isEqualTo(BatchStatus.COMPLETED);

        String report = Files.readString(newestReportFile(REPORT_FILE_PREFIX));
        // Detail amounts are rendered "%,.2f"; the subtotal / grand total are signed ("+150.00"). All three
        // decimal tokens must appear regardless of column padding or the leading total sign.
        assertThat(report).contains("100.00");
        assertThat(report).contains("50.00");
        assertThat(report).contains("150.00");
    }

    // ------------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------------

    /**
     * Returns the most recently modified file under {@code report.output.path} whose name starts with
     * {@code prefix}.
     *
     * @param prefix the file-name prefix to match (e.g. {@code "transaction-report-"})
     * @return the newest matching file
     * @throws IOException if the report directory cannot be listed
     */
    private Path newestReportFile(String prefix) throws IOException {
        Path dir = Paths.get(reportOutputPath);
        assertThat(Files.exists(dir))
                .as("report output directory %s should exist after a report run", dir)
                .isTrue();
        try (Stream<Path> entries = Files.list(dir)) {
            return entries
                    .filter(p -> p.getFileName().toString().startsWith(prefix))
                    .max(Comparator.comparingLong(p -> p.toFile().lastModified()))
                    .orElseThrow(() -> new AssertionError(
                            "No report file with prefix '" + prefix + "' was found in " + dir));
        }
    }

    /**
     * Builds the statement file name {@code StatementService} produces for the all-encompassing window used
     * by Test&nbsp;1: {@code statement_<acctId>_<start>_<end>.<ext>}.
     *
     * @param acctId    the account id
     * @param extension the file extension without the dot ({@code "txt"} or {@code "html"})
     * @return the expected statement file name
     */
    private static String statementFileName(long acctId, String extension) {
        return STATEMENT_FILE_PREFIX + acctId + "_" + STATEMENT_START_DATE + "_"
                + STATEMENT_END_DATE + "." + extension;
    }

    /**
     * Builds a fully populated {@link Transaction} for the grand-total test. {@code typeCd}/{@code catCd}
     * are fixed to the seeded {@code ('01', 1)} category so the {@code (type_cd, cat_cd)} foreign key is
     * satisfied; non-asserted merchant fields are set to neutral, non-null values.
     *
     * @param tranId      the (16-char) transaction id / primary key
     * @param acctId      the owning account id (a valid foreign key)
     * @param cardNum     the card number (a valid foreign key)
     * @param description the free-text description
     * @param amount      the transaction amount
     * @param ts          the origination and processing timestamp
     * @return the populated, unsaved transaction
     */
    private static Transaction newTransaction(String tranId, Long acctId, String cardNum,
                                              String description, BigDecimal amount, LocalDateTime ts) {
        Transaction t = new Transaction();
        t.setTranId(tranId);
        t.setAcctId(acctId);
        t.setCardNum(cardNum);
        t.setTypeCd("01");
        t.setCatCd(1);
        t.setSource("POS");
        t.setDescription(description);
        t.setAmt(amount);
        t.setMerchantId(0L);
        t.setMerchantName("");
        t.setMerchantCity("");
        t.setMerchantZip("");
        t.setOrigTs(ts);
        t.setProcTs(ts);
        return t;
    }

    /**
     * @param path a file under the report directory
     * @return {@code true} if the file is a generated statement or transaction report artifact
     */
    private static boolean isGeneratedArtifact(Path path) {
        String name = path.getFileName().toString();
        return name.startsWith(STATEMENT_FILE_PREFIX) || name.startsWith(REPORT_FILE_PREFIX);
    }

    /**
     * Deletes a file, swallowing any {@link IOException} (cleanup is best-effort; unique file names mean a
     * stray leftover can never affect a subsequent assertion).
     *
     * @param path the file to delete
     */
    private static void deleteQuietly(Path path) {
        try {
            Files.deleteIfExists(path);
        } catch (IOException ignored) {
            // best-effort cleanup
        }
    }
}
