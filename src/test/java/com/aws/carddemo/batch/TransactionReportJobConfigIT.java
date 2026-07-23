package com.aws.carddemo.batch;

import static org.assertj.core.api.Assertions.assertThat;

import com.aws.carddemo.AbstractPostgresIntegrationTest;
import com.aws.carddemo.config.BatchConfig;
import com.aws.carddemo.domain.CardXref;
import com.aws.carddemo.domain.Transaction;
import com.aws.carddemo.domain.TransactionCategory;
import com.aws.carddemo.domain.TransactionType;
import com.aws.carddemo.dto.report.ReportAccountTotals;
import com.aws.carddemo.dto.report.ReportAmountFormatter;
import com.aws.carddemo.dto.report.ReportGrandTotals;
import com.aws.carddemo.dto.report.ReportPageTotals;
import com.aws.carddemo.repository.CardXrefRepository;
import com.aws.carddemo.repository.TransactionCategoryRepository;
import com.aws.carddemo.repository.TransactionRepository;
import com.aws.carddemo.repository.TransactionTypeRepository;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.ExitStatus;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.JobExecution;
import org.springframework.batch.core.JobParameters;
import org.springframework.batch.core.JobParametersBuilder;
import org.springframework.batch.core.launch.JobLauncher;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.test.JobLauncherTestUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;

/**
 * End-to-end Spring Batch integration / parity test for {@link TransactionReportJobConfig}, the
 * migrated daily transaction detail report.
 *
 * <p><strong>Origin (read-only oracles):</strong> COBOL program {@code legacy/cbl/CBTRN03C.cbl}
 * orchestrated by {@code legacy/jcl/TRANREPT.jcl} which invokes the cataloged procedure
 * {@code legacy/proc/TRANREPT.prc}. This test verifies that the Java job reproduces the report
 * contract expressed by the following COBOL paragraphs:</p>
 * <ul>
 *   <li>{@code 1000-TRANFILE-GET-NEXT} - sequential read of the (card-ordered) transaction file.</li>
 *   <li>{@code 1100-WRITE-TRANSACTION-REPORT} - the first-time header, the page-break check
 *       ({@code IF FUNCTION MOD(WS-LINE-COUNTER, WS-PAGE-SIZE) = 0}) and the running-total
 *       accumulation driving each detail line.</li>
 *   <li>{@code 1110-WRITE-PAGE-TOTALS} - the per-page subtotal line emitted every
 *       {@code WS-PAGE-SIZE = 20} detail lines.</li>
 *   <li>{@code 1120-WRITE-ACCOUNT-TOTALS} - the per-account (per-card) subtotal at each control
 *       break.</li>
 *   <li>{@code 1110-WRITE-GRAND-TOTALS} - the report-wide grand total.</li>
 *   <li>{@code 1120-WRITE-HEADERS} - the four-line page header block.</li>
 * </ul>
 *
 * <p>Governing specification: AAP &sect;0.4.1 (report translation), &sect;0.6.1 (fixed-scale
 * {@code BigDecimal} totals with the {@code PIC -ZZZ,ZZZ,ZZZ.ZZ} edit mask, never floating point)
 * and &sect;0.6.4 (byte-and-behavior identical external report interface).</p>
 *
 * <h2>Harness</h2>
 * <p>The test extends {@link AbstractPostgresIntegrationTest}, so it reuses the shared
 * Testcontainers PostgreSQL database and its {@code @DynamicPropertySource} datasource binding, and
 * inherits the {@code test} Spring profile. Rather than booting the whole application, it pins an
 * explicit set of configuration classes via {@code @SpringBootTest(classes)}: the production
 * {@link TransactionReportJobConfig} under test, plus the nested {@link BatchSliceConfig} and
 * {@link BatchTestHarnessConfig} helpers. This loads only what the report job needs - the JPA
 * entities, the Spring Data repositories, the auto-configured DataSource/JPA/Batch/Flyway
 * infrastructure and the job definition itself - and keeps the unrelated web/menu/security beans out
 * of the context so this data-and-batch parity test stays hermetic. Because at least one pinned
 * class ({@link TransactionReportJobConfig}) is a non-test {@code @Configuration}, Spring Boot uses
 * the given set as-is and does not fall back to searching for a {@code @SpringBootConfiguration},
 * which would otherwise discover one declared by a sibling batch test in this package and shadow the
 * intended configuration.</p>
 *
 * <p>Flyway remains enabled and materialises the authoritative production schema into the container
 * in order ({@code V0} Spring Batch metadata &rarr; {@code V1} relational schema &rarr; {@code V2}
 * reference data &rarr; {@code V3} indexes), so the job runs against the true fixed-width
 * {@code CHAR} columns and their {@code COLLATE "C"} ordering (AAP &sect;0.6.2, &sect;0.6.6) and
 * against the real {@code BATCH_*} metadata tables. Hibernate is set to {@code ddl-auto=none} for
 * this test (overriding the {@code test}-profile default of {@code validate}): schema
 * <em>validation</em> is disabled only because the JPA entities currently map their fixed-width
 * columns as {@code VARCHAR} (Hibernate's default for a {@code String} field) while the DDL declares
 * {@code CHAR(n)}. That entity/DDL alignment is a cross-cutting concern of the domain layer and is
 * out of scope for this report-parity test, whose subject is the report job's byte-and-total
 * contract; reading and writing {@code String} values against {@code CHAR} columns is transparent,
 * so the job still exercises the genuine production schema.</p>
 *
 * <p>The test does <strong>not</strong> use {@code @SpringBatchTest}; instead the nested
 * {@link BatchTestHarnessConfig} contributes exactly one {@link JobLauncherTestUtils} bean wired to
 * the auto-configured {@link JobLauncher}/{@link JobRepository} and the
 * {@code @Qualifier("transactionReportJob")} {@link Job}. Both nested {@code @TestConfiguration}
 * classes are registered explicitly through {@code @SpringBootTest(classes)}, because a nested
 * {@code @TestConfiguration} is only auto-detected when no {@code classes} are declared. The
 * migrated jobs do not auto-run at startup ({@code spring.batch.job.enabled=false}); this test
 * launches the report job explicitly.</p>
 *
 * <p>Because the shared container is reused across test methods and the suite is not wrapped in a
 * rollback transaction, {@link #resetDatabaseAndSeedReferenceData()} clears the transaction table and
 * (idempotently) seeds the small set of reference rows the report lookups require, using type and
 * category codes chosen not to collide with the Flyway reference seed.</p>
 *
 * @see TransactionReportJobConfig
 */
@SpringBootTest(
        classes = {
            TransactionReportJobConfig.class,
            BatchConfig.class,
            TransactionReportJobConfigIT.BatchSliceConfig.class,
            TransactionReportJobConfigIT.BatchTestHarnessConfig.class
        },
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        // Prometheus export disabled (review finding #35): the slice uses a SimpleMeterRegistry so Spring
        // Batch's duplicate spring.batch.job.active meter never trips the Prometheus collision WARN.
        properties = {
            "spring.jpa.hibernate.ddl-auto=none",
            "management.prometheus.metrics.export.enabled=false"
        })
class TransactionReportJobConfigIT extends AbstractPostgresIntegrationTest {

    /** Exact byte width of every report record ({@code FD-REPTFILE-REC PIC X(133)}). */
    private static final int REPORT_RECORD_WIDTH = 133;

    /** Inclusive start column (0-based) of the edited amount on every report line (report column 98). */
    private static final int AMOUNT_BEGIN_INDEX = 97;

    /** Exclusive end column (0-based) of the edited amount on every report line (report column 112). */
    private static final int AMOUNT_END_INDEX = 112;

    /** Inclusive start of the reporting window used by every launch ({@code WS-START-DATE}). */
    private static final String START_DATE = "2022-01-01";

    /** Inclusive end of the reporting window used by every launch ({@code WS-END-DATE}). */
    private static final String END_DATE = "2022-12-31";

    /** Transaction type code owned by this test; chosen not to collide with the V2 reference seed. */
    private static final String TYPE_CD = "99";

    /** Transaction type description for {@link #TYPE_CD}. */
    private static final String TYPE_DESC = "PARITY TEST TYPE";

    /** Transaction category code owned by this test (paired with {@link #TYPE_CD}). */
    private static final int CAT_CD = 5;

    /** Transaction category description for {@link #TYPE_CD}/{@link #CAT_CD}. */
    private static final String CAT_DESC = "PARITY TEST CATEGORY";

    /** Transaction source printed on the detail line ({@code TRAN-REPORT-SOURCE PIC X(10)}). */
    private static final String SOURCE = "POS";

    /** First 16-digit card number; cross-referenced to {@link #ACCT_1}. */
    private static final String CARD_1 = "9990000000000001";

    /** Second 16-digit card number; cross-referenced to {@link #ACCT_2}. */
    private static final String CARD_2 = "9990000000000002";

    /** Customer id cross-referenced for {@link #CARD_1}. */
    private static final long CUST_1 = 70001L;

    /** Account id cross-referenced for {@link #CARD_1} ({@code XREF-ACCT-ID}). */
    private static final long ACCT_1 = 70001L;

    /** Customer id cross-referenced for {@link #CARD_2}. */
    private static final long CUST_2 = 70002L;

    /** Account id cross-referenced for {@link #CARD_2} ({@code XREF-ACCT-ID}). */
    private static final long ACCT_2 = 70002L;

    /** Prefix of the tran ids seeded for the page-break test (its detail lines start with this). */
    private static final String PAGE_TRAN_PREFIX = "TXNPG";

    /** {@code REPT-SHORT-NAME} literal opening every page's name-header line. */
    private static final String NAME_HEADER_PREFIX = "DALYREPT";

    /** Prefix of the column-heading line ({@code TRANSACTION-HEADER-1}), used to classify records. */
    private static final String COLUMN_HEADING_PREFIX = "Transaction ID";

    /**
     * The {@code TRANSACTION-HEADER-2} separator record: exactly {@value #REPORT_RECORD_WIDTH} dash
     * characters ({@code PIC X(133) VALUE ALL '-'}). Emitted in the header block and after every page
     * and account total.
     */
    private static final String DASH_SEPARATOR = "-".repeat(REPORT_RECORD_WIDTH);

    @Autowired
    private JobLauncherTestUtils jobLauncherTestUtils;

    @Autowired
    private TransactionRepository transactionRepository;

    @Autowired
    private CardXrefRepository cardXrefRepository;

    @Autowired
    private TransactionTypeRepository transactionTypeRepository;

    @Autowired
    private TransactionCategoryRepository transactionCategoryRepository;

    /**
     * Test-only infrastructure configuration for this integration test. It enables Spring Boot
     * auto-configuration (DataSource, JPA, Spring Batch and Flyway) and scans the JPA entities in
     * {@code com.aws.carddemo.domain} and the Spring Data repositories in
     * {@code com.aws.carddemo.repository} that the report reads. Flyway (enabled by the active
     * {@code test} profile) creates the {@code BATCH_*} metadata tables and the relational schema,
     * so no Batch-managed schema initialisation is required.
     *
     * <p>It is a {@code @TestConfiguration} rather than a plain {@code @Configuration} so that the
     * main application's component scan never picks it up - which would otherwise let its
     * {@code @EnableAutoConfiguration} bleed into unrelated tests. It is instead registered
     * explicitly through {@code @SpringBootTest(classes)}. The production
     * {@link TransactionReportJobConfig} (a non-test {@code @Configuration}) is pinned alongside it
     * as the context's primary source: it supplies the {@code transactionReportJob} bean under test
     * and, being a non-test component, stops Spring Boot from falling back to a
     * {@code @SpringBootConfiguration} search that would otherwise discover a sibling batch test's
     * nested {@code @SpringBootConfiguration} in this package and shadow the intended
     * configuration.</p>
     */
    @TestConfiguration
    @EnableAutoConfiguration
    @EntityScan(basePackageClasses = Transaction.class)
    @EnableJpaRepositories(basePackageClasses = TransactionRepository.class)
    static class BatchSliceConfig {
    }

    /**
     * Test-only configuration supplying the single {@link JobLauncherTestUtils} used to launch the
     * report job. Deliberately avoids {@code @SpringBatchTest}: the utility is wired by hand to the
     * auto-configured {@link JobLauncher} and {@link JobRepository} and the qualified
     * {@code transactionReportJob} {@link Job}.
     */
    @TestConfiguration
    static class BatchTestHarnessConfig {

        /**
         * Builds the single {@link JobLauncherTestUtils} bound to the transaction-report job.
         *
         * @param jobLauncher          the auto-configured Spring Batch job launcher
         * @param jobRepository        the auto-configured Spring Batch job repository
         * @param transactionReportJob the job under test ({@code transactionReportJob})
         * @return the configured {@link JobLauncherTestUtils}
         */
        @Bean
        JobLauncherTestUtils transactionReportJobLauncherTestUtils(
                JobLauncher jobLauncher,
                JobRepository jobRepository,
                @Qualifier("transactionReportJob") Job transactionReportJob) {
            JobLauncherTestUtils utils = new JobLauncherTestUtils();
            utils.setJobLauncher(jobLauncher);
            utils.setJobRepository(jobRepository);
            utils.setJob(transactionReportJob);
            return utils;
        }
    }

    /**
     * Resets the mutable transaction table and (idempotently) seeds the reference rows every report
     * launch needs: the transaction type, its category, and the card cross-references for the two
     * test cards. Runs before each test so results never depend on data left by a prior test.
     */
    @BeforeEach
    void resetDatabaseAndSeedReferenceData() {
        transactionRepository.deleteAll();

        TransactionType type = new TransactionType();
        type.setTranType(TYPE_CD);
        type.setTranTypeDesc(TYPE_DESC);
        transactionTypeRepository.save(type);

        TransactionCategory category = new TransactionCategory();
        category.setTypeCd(TYPE_CD);
        category.setCatCd(CAT_CD);
        category.setDescription(CAT_DESC);
        transactionCategoryRepository.save(category);

        cardXrefRepository.save(new CardXref(CARD_1, CUST_1, ACCT_1));
        cardXrefRepository.save(new CardXref(CARD_2, CUST_2, ACCT_2));
    }

    /**
     * The job runs to {@code COMPLETED}, writes a non-empty report file, and opens with the
     * {@code DALYREPT} name-header block.
     *
     * @param tempDir a per-test temporary directory for the report output
     * @throws Exception if the job launch fails
     */
    @Test
    void jobProducesReport_statusCompleted(@TempDir Path tempDir) throws Exception {
        transactionRepository.saveAll(List.of(
                transaction("TXNSTATUS0000001", CARD_1, "12.34", at(2022, 6, 15)),
                transaction("TXNSTATUS0000002", CARD_1, "56.78", at(2022, 6, 16))));

        Path reportOut = tempDir.resolve("tranrept.txt");
        JobExecution execution = launchReport(reportOut);

        assertThat(execution.getStatus()).isEqualTo(BatchStatus.COMPLETED);
        assertThat(execution.getExitStatus()).isEqualTo(ExitStatus.COMPLETED);
        assertThat(Files.exists(reportOut)).isTrue();
        assertThat(Files.size(reportOut)).isGreaterThan(0L);

        List<String> lines = readReport(reportOut);
        assertThat(lines).isNotEmpty();
        assertThat(lines.get(0)).startsWith(NAME_HEADER_PREFIX);
    }

    /**
     * Every emitted record - header, detail, page-total, account-total and grand-total - is exactly
     * {@value #REPORT_RECORD_WIDTH} bytes wide. Seeds a data set that exercises all record kinds
     * (a page break plus a second account control break).
     *
     * @param tempDir a per-test temporary directory for the report output
     * @throws Exception if the job launch fails
     */
    @Test
    void everyReportLineIs133Bytes(@TempDir Path tempDir) throws Exception {
        List<Transaction> seed = new ArrayList<>();
        for (int i = 1; i <= 21; i++) {
            seed.add(transaction(pageTranId(i), CARD_1, "1.00", at(2022, 6, 15)));
        }
        seed.add(transaction("TXNWIDTHB0000001", CARD_2, "10.00", at(2022, 6, 16)));
        seed.add(transaction("TXNWIDTHB0000002", CARD_2, "-4.00", at(2022, 6, 17)));
        transactionRepository.saveAll(seed);

        Path reportOut = tempDir.resolve("tranrept.txt");
        assertThat(launchReport(reportOut).getStatus()).isEqualTo(BatchStatus.COMPLETED);

        List<String> lines = readReport(reportOut);
        assertThat(lines).isNotEmpty();
        for (String line : lines) {
            assertThat(line).as("report line char width").hasSize(REPORT_RECORD_WIDTH);
            assertThat(line.getBytes(StandardCharsets.ISO_8859_1)).as("report line byte width")
                    .hasSize(REPORT_RECORD_WIDTH);
        }

        // All total record kinds are present (and therefore also verified at 133 bytes above).
        assertThat(linesStartingWith(lines, ReportPageTotals.LABEL)).isNotEmpty();
        assertThat(linesStartingWith(lines, ReportAccountTotals.LABEL)).isNotEmpty();
        assertThat(linesStartingWith(lines, ReportGrandTotals.LABEL)).isNotEmpty();
    }

    /**
     * Transactions whose processing date falls outside {@code [startDate, endDate]} are excluded,
     * while transactions on both inclusive boundaries are included ({@code TRAN-PROC-TS(1:10)} filter).
     *
     * @param tempDir a per-test temporary directory for the report output
     * @throws Exception if the job launch fails
     */
    @Test
    void transactionsOutsideDateRangeAreExcluded(@TempDir Path tempDir) throws Exception {
        String inRange = "TXNINRANGE000001";
        String startBoundary = "TXNSTARTBND00001";
        String endBoundary = "TXNENDBND0000001";
        String beforeWindow = "TXNBEFORE0000001";
        String afterWindow = "TXNAFTER00000001";

        transactionRepository.saveAll(List.of(
                transaction(inRange, CARD_1, "10.00", at(2022, 6, 15)),
                transaction(startBoundary, CARD_1, "20.00", at(2022, 1, 1)),
                transaction(endBoundary, CARD_1, "30.00", at(2022, 12, 31)),
                transaction(beforeWindow, CARD_1, "40.00", at(2021, 12, 31)),
                transaction(afterWindow, CARD_1, "50.00", at(2023, 1, 1))));

        Path reportOut = tempDir.resolve("tranrept.txt");
        assertThat(launchReport(reportOut).getStatus()).isEqualTo(BatchStatus.COMPLETED);

        String report = String.join("\n", readReport(reportOut));
        assertThat(report).contains(inRange);
        assertThat(report).contains(startBoundary);
        assertThat(report).contains(endBoundary);
        assertThat(report).doesNotContain(beforeWindow);
        assertThat(report).doesNotContain(afterWindow);
    }

    /**
     * Pagination follows the source line counter, not a fixed count of detail rows.
     * {@code CBTRN03C} increments {@code WS-LINE-COUNTER} for <em>every</em> physical record - the
     * four-line header block, each detail line, and each two-line total block - and breaks a page
     * before a detail line when {@code FUNCTION MOD(WS-LINE-COUNTER, WS-PAGE-SIZE) = 0}. Because the
     * opening header pre-charges the counter to 4, the first page carries exactly <strong>16</strong>
     * detail lines (the 17th detail's pre-write test at counter 20 trips the break), not 20.
     *
     * <p>Seeds 21 in-range details on one card. The 17th detail trips the mid-report page break, so a
     * page total of the first 16 amounts (16.00) is emitted; the remaining 5 details follow, and at
     * end of file the source stale-adds the last amount (1.00) and writes the final page total
     * (5.00 + 1.00 = 6.00) and the grand total (16.00 + 6.00 = 22.00). Being a single card, no
     * account total is ever written - the source writes an account total only at a control break to a
     * different card, never for the last card (review finding #31).</p>
     *
     * @param tempDir a per-test temporary directory for the report output
     * @throws Exception if the job launch fails
     */
    @Test
    void pageBreakOccursOnLineCounterMultipleOfPageSize(@TempDir Path tempDir) throws Exception {
        List<Transaction> seed = new ArrayList<>();
        for (int i = 1; i <= 21; i++) {
            seed.add(transaction(pageTranId(i), CARD_1, "1.00", at(2022, 6, 15)));
        }
        transactionRepository.saveAll(seed);

        Path reportOut = tempDir.resolve("tranrept.txt");
        assertThat(launchReport(reportOut).getStatus()).isEqualTo(BatchStatus.COMPLETED);

        List<String> lines = readReport(reportOut);

        // Two page totals: the mid-report break after the first 16 details (16.00), and the
        // end-of-file page total for the remaining 5 details plus the stale-add of the last amount
        // (5.00 + 1.00 = 6.00).
        List<String> pageTotals = linesStartingWith(lines, ReportPageTotals.LABEL);
        assertThat(pageTotals).hasSize(2);
        assertThat(amountField(pageTotals.get(0)))
                .isEqualTo(ReportAmountFormatter.formatForcedSign(new BigDecimal("16.00")));
        assertThat(amountField(pageTotals.get(1)))
                .isEqualTo(ReportAmountFormatter.formatForcedSign(new BigDecimal("6.00")));

        // Single card => no account total is ever written (the last card never gets one).
        assertThat(linesStartingWith(lines, ReportAccountTotals.LABEL)).isEmpty();

        // Grand total = sum of the two page totals = 22.00 (21 details of 1.00 plus the stale-add).
        List<String> grandTotals = linesStartingWith(lines, ReportGrandTotals.LABEL);
        assertThat(grandTotals).hasSize(1);
        assertThat(amountField(grandTotals.get(0)))
                .isEqualTo(ReportAmountFormatter.formatForcedSign(new BigDecimal("22.00")));

        // The first page carries exactly 16 detail lines before the first page total.
        int firstPageTotalIndex = lines.indexOf(pageTotals.get(0));
        long detailLinesBeforeFirstPageTotal = lines.subList(0, firstPageTotalIndex).stream()
                .filter(line -> line.startsWith(PAGE_TRAN_PREFIX))
                .count();
        assertThat(detailLinesBeforeFirstPageTotal).isEqualTo(16L);
    }

    /**
     * Per-account (per-card) subtotals and the grand total are exact {@link BigDecimal} sums (never
     * floating point), including a negative amount, and print with the forced-sign total mask.
     *
     * @param tempDir a per-test temporary directory for the report output
     * @throws Exception if the job launch fails
     */
    @Test
    void accountSubtotalsAndGrandTotalAreCorrect_BigDecimal(@TempDir Path tempDir) throws Exception {
        BigDecimal a1 = new BigDecimal("100.00");
        BigDecimal a2 = new BigDecimal("250.50");
        BigDecimal a3 = new BigDecimal("-30.25");
        BigDecimal b1 = new BigDecimal("1000.00");
        BigDecimal b2 = new BigDecimal("234.56");

        transactionRepository.saveAll(List.of(
                transaction("TXNACCTA00000001", CARD_1, a1.toPlainString(), at(2022, 3, 1)),
                transaction("TXNACCTA00000002", CARD_1, a2.toPlainString(), at(2022, 3, 2)),
                transaction("TXNACCTA00000003", CARD_1, a3.toPlainString(), at(2022, 3, 3)),
                transaction("TXNACCTB00000001", CARD_2, b1.toPlainString(), at(2022, 3, 4)),
                transaction("TXNACCTB00000002", CARD_2, b2.toPlainString(), at(2022, 3, 5))));

        BigDecimal card1Subtotal = a1.add(a2).add(a3);
        BigDecimal card2Subtotal = b1.add(b2);
        assertThat(card1Subtotal).isEqualByComparingTo("320.25");
        assertThat(card2Subtotal).isEqualByComparingTo("1234.56");

        // The source end-of-file branch (CBTRN03C 197-203) adds the retained last-record amount (b2)
        // once more to the page and grand totals - the "stale-add" quirk, because READ ... INTO leaves
        // TRAN-RECORD unchanged at AT END - so the grand total is the naive sum plus b2.
        BigDecimal naiveGrand = card1Subtotal.add(card2Subtotal);
        BigDecimal grandWithStaleAdd = naiveGrand.add(b2);
        assertThat(naiveGrand).isEqualByComparingTo("1554.81");
        assertThat(grandWithStaleAdd).isEqualByComparingTo("1789.37");

        Path reportOut = tempDir.resolve("tranrept.txt");
        assertThat(launchReport(reportOut).getStatus()).isEqualTo(BatchStatus.COMPLETED);

        List<String> lines = readReport(reportOut);

        // Exactly one account total: card 9990000000000001, written at the control break to card
        // 9990000000000002. The last card (card 2) never receives an account total - the source
        // writes account totals only at a control break to a different card, never at end of file.
        List<String> accountTotals = linesStartingWith(lines, ReportAccountTotals.LABEL);
        assertThat(accountTotals).hasSize(1);
        assertThat(amountField(accountTotals.get(0)))
                .isEqualTo(ReportAmountFormatter.formatForcedSign(card1Subtotal));

        // Grand total = sum of the page totals, including the end-of-file stale-add of b2.
        List<String> grandTotals = linesStartingWith(lines, ReportGrandTotals.LABEL);
        assertThat(grandTotals).hasSize(1);
        assertThat(amountField(grandTotals.get(0)))
                .isEqualTo(ReportAmountFormatter.formatForcedSign(grandWithStaleAdd));
    }

    /**
     * Detail amounts render with the COBOL {@code -ZZZ,ZZZ,ZZZ.ZZ} edit mask: leading-blank
     * suppression, comma grouping, a blank sign for positive values and a {@code '-'} for negatives.
     * The report bytes for each amount equal the production {@link ReportAmountFormatter} output.
     *
     * @param tempDir a per-test temporary directory for the report output
     * @throws Exception if the job launch fails
     */
    @Test
    void amountsFormattedWithEditMask(@TempDir Path tempDir) throws Exception {
        String positiveTranId = "TXNFMTPOS0000001";
        String negativeTranId = "TXNFMTNEG0000001";
        BigDecimal positiveAmount = new BigDecimal("1234.56");
        BigDecimal negativeAmount = new BigDecimal("-1234.56");

        transactionRepository.saveAll(List.of(
                transaction(positiveTranId, CARD_1, positiveAmount.toPlainString(), at(2022, 4, 10)),
                transaction(negativeTranId, CARD_1, negativeAmount.toPlainString(), at(2022, 4, 11))));

        // Exact 15-character edited strings (sign position + ZZZ,ZZZ,ZZZ + '.' + ZZ).
        String expectedPositive = " ".repeat(7) + "1,234.56";
        String expectedNegative = "-" + " ".repeat(6) + "1,234.56";
        assertThat(ReportAmountFormatter.formatSigned(positiveAmount)).isEqualTo(expectedPositive);
        assertThat(ReportAmountFormatter.formatSigned(negativeAmount)).isEqualTo(expectedNegative);
        assertThat(expectedPositive).contains("1,234.56").doesNotContain("-");
        assertThat(expectedNegative).startsWith("-").contains("1,234.56");

        Path reportOut = tempDir.resolve("tranrept.txt");
        assertThat(launchReport(reportOut).getStatus()).isEqualTo(BatchStatus.COMPLETED);

        List<String> lines = readReport(reportOut);
        String positiveDetail = requireDetailLine(lines, positiveTranId);
        String negativeDetail = requireDetailLine(lines, negativeTranId);
        assertThat(amountField(positiveDetail)).isEqualTo(expectedPositive);
        assertThat(amountField(negativeDetail)).isEqualTo(expectedNegative);
        assertThat(amountField(negativeDetail)).startsWith("-");
    }

    /**
     * Empty/EOF boundary golden ({@code CBTRN03C}, review finding #31). An empty transaction file
     * produces exactly the source end-of-file output and nothing else: a zero page total, the
     * 133-dash separator, and a zero grand total - with <strong>no</strong> header block, because
     * {@code WS-FIRST-TIME} never flips to {@code 'N'} (no detail line is ever written) and no account
     * total, because there is no control break.
     *
     * @param tempDir a per-test temporary directory for the report output
     * @throws Exception if the job launch fails
     */
    @Test
    void emptyReportEmitsOnlyZeroPageTotalSeparatorAndZeroGrandTotal(@TempDir Path tempDir)
            throws Exception {
        // No transactions seeded: resetDatabaseAndSeedReferenceData cleared the transaction table.
        Path reportOut = tempDir.resolve("tranrept.txt");
        assertThat(launchReport(reportOut).getStatus()).isEqualTo(BatchStatus.COMPLETED);

        List<String> lines = readReport(reportOut);
        assertThat(lines).containsExactly(
                toRecord(new ReportPageTotals(BigDecimal.ZERO).toReportLine()),
                DASH_SEPARATOR,
                toRecord(new ReportGrandTotals(BigDecimal.ZERO).toReportLine()));
    }

    /**
     * Whole-file 133-byte oracle for a card control break plus end of file with no page break
     * ({@code CBTRN03C}, review finding #31). Two cards - card 1 with two details (10.00, 20.00) and
     * card 2 with one detail (5.00) - produce, in exact order:
     * <ol>
     *   <li>the four-line header block (name header, blank, column heading, separator);</li>
     *   <li>card 1's two detail lines;</li>
     *   <li>card 1's account total (30.00) and a separator, written at the control break to card 2;</li>
     *   <li>card 2's single detail line;</li>
     *   <li>the end-of-file page total (40.00) and a separator, then the grand total (40.00).</li>
     * </ol>
     * The card 2 detail amount (5.00) is added once more at end of file (the stale-add quirk), so the
     * final page and grand totals are 35.00 + 5.00 = 40.00, and card 2 (the last card) receives no
     * account total. This pins the exact record sequence, kinds and amounts across the card and EOF
     * boundaries - the strongest {@code CBTRN03C} parity oracle.
     *
     * @param tempDir a per-test temporary directory for the report output
     * @throws Exception if the job launch fails
     */
    @Test
    void wholeFileRecordSequenceMatchesOracle_controlBreakAndEndOfFile(@TempDir Path tempDir)
            throws Exception {
        transactionRepository.saveAll(List.of(
                transaction("TXNSEQA000000001", CARD_1, "10.00", at(2022, 5, 1)),
                transaction("TXNSEQA000000002", CARD_1, "20.00", at(2022, 5, 2)),
                transaction("TXNSEQB000000001", CARD_2, "5.00", at(2022, 5, 3))));

        Path reportOut = tempDir.resolve("tranrept.txt");
        assertThat(launchReport(reportOut).getStatus()).isEqualTo(BatchStatus.COMPLETED);

        List<String> lines = readReport(reportOut);

        // Exact record-kind sequence (finding #31: model every source write paragraph in order).
        List<String> kinds = new ArrayList<>();
        for (String record : lines) {
            kinds.add(classify(record));
        }
        assertThat(kinds).containsExactly(
                "HEADER_NAME", "BLANK", "COLUMN_HEADING", "SEPARATOR",   // 1120-WRITE-HEADERS
                "DETAIL", "DETAIL",                                      // card 1 details
                "ACCOUNT_TOTAL", "SEPARATOR",                            // 1120-WRITE-ACCOUNT-TOTALS
                "DETAIL",                                                // card 2 detail
                "PAGE_TOTAL", "SEPARATOR",                               // 1110-WRITE-PAGE-TOTALS (EOF)
                "GRAND_TOTAL");                                          // 1110-WRITE-GRAND-TOTALS

        // The single account total is card 1's subtotal (10.00 + 20.00 = 30.00).
        List<String> accountTotals = linesStartingWith(lines, ReportAccountTotals.LABEL);
        assertThat(accountTotals).hasSize(1);
        assertThat(amountField(accountTotals.get(0)))
                .isEqualTo(ReportAmountFormatter.formatForcedSign(new BigDecimal("30.00")));

        // The end-of-file page total and grand total both carry the stale-add of the last amount:
        // 35.00 (10 + 20 + 5) + 5.00 = 40.00.
        List<String> pageTotals = linesStartingWith(lines, ReportPageTotals.LABEL);
        assertThat(pageTotals).hasSize(1);
        assertThat(amountField(pageTotals.get(0)))
                .isEqualTo(ReportAmountFormatter.formatForcedSign(new BigDecimal("40.00")));
        List<String> grandTotals = linesStartingWith(lines, ReportGrandTotals.LABEL);
        assertThat(grandTotals).hasSize(1);
        assertThat(amountField(grandTotals.get(0)))
                .isEqualTo(ReportAmountFormatter.formatForcedSign(new BigDecimal("40.00")));
    }

    /**
     * Classifies a 133-byte report record into a coarse record-kind token, used by the whole-file
     * oracle to assert the exact emission sequence. The order of checks matters: label- and
     * header-prefixed records are matched before the {@code DETAIL} fallback (the golden's detail
     * transaction ids all start with {@code TXNSEQ}, so they never collide with a label or heading).
     *
     * @param record a 133-byte report record
     * @return one of {@code HEADER_NAME}, {@code BLANK}, {@code COLUMN_HEADING}, {@code SEPARATOR},
     *         {@code PAGE_TOTAL}, {@code ACCOUNT_TOTAL}, {@code GRAND_TOTAL} or {@code DETAIL}
     */
    private static String classify(String record) {
        if (record.startsWith(NAME_HEADER_PREFIX)) {
            return "HEADER_NAME";
        }
        if (record.equals(DASH_SEPARATOR)) {
            return "SEPARATOR";
        }
        if (record.isBlank()) {
            return "BLANK";
        }
        if (record.startsWith(COLUMN_HEADING_PREFIX)) {
            return "COLUMN_HEADING";
        }
        if (record.startsWith(ReportPageTotals.LABEL)) {
            return "PAGE_TOTAL";
        }
        if (record.startsWith(ReportAccountTotals.LABEL)) {
            return "ACCOUNT_TOTAL";
        }
        if (record.startsWith(ReportGrandTotals.LABEL)) {
            return "GRAND_TOTAL";
        }
        return "DETAIL";
    }

    /**
     * Normalizes a composed line to exactly {@value #REPORT_RECORD_WIDTH} characters (right-padded
     * with spaces or truncated), mirroring the production {@code toRecord} normalization so the
     * empty-report golden can assert exact records built from the same DTO {@code toReportLine()}
     * output.
     *
     * @param line the composed report line
     * @return the line as exactly {@value #REPORT_RECORD_WIDTH} characters
     */
    private static String toRecord(String line) {
        if (line.length() >= REPORT_RECORD_WIDTH) {
            return line.substring(0, REPORT_RECORD_WIDTH);
        }
        return line + " ".repeat(REPORT_RECORD_WIDTH - line.length());
    }

    /**
     * A reversed reporting window ({@code endDate} before {@code startDate}) runs to
     * {@code COMPLETED} rather than abending, and excludes every record from the detail body. This is
     * the Finding K parity behavior: COBOL {@code CBTRN03C} (legacy/cbl/CBTRN03C.cbl:L172-178) applies
     * only the inclusive membership filter {@code TRAN-PROC-TS(1:10) >= WS-START-DATE AND <=
     * WS-END-DATE}; it never compares the two bounds, so a reversed window makes that predicate
     * unsatisfiable for every record and the program ends normally. The same in-2022 data that a
     * normal window would include is seeded here to prove it is excluded (not merely absent), and the
     * window is passed end-before-start. Before the fix an invented {@code endDate.isBefore(startDate)}
     * guard failed the job; this test would then have observed {@code FAILED} with no report file.
     *
     * <p>With no in-window record, paragraph {@code 1100-WRITE-TRANSACTION-REPORT} never runs, so the
     * one-time {@code 1120-WRITE-HEADERS} block - written lazily on the first detail line - is absent:
     * the report carries <strong>no</strong> {@code DALYREPT} name header and no detail lines, and no
     * control break fires so no account-total line is written. The end-of-file trailer still runs
     * (main loop lines 197-203): the retained last physical {@code TRAN-AMT} is added once more (the
     * documented stale-add quirk), then {@code 1110-WRITE-PAGE-TOTALS} and
     * {@code 1110-WRITE-GRAND-TOTALS} emit exactly one page-total and one grand-total line.
     *
     * @param tempDir a per-test temporary directory for the report output
     * @throws Exception if the job launch fails
     */
    @Test
    void reversedDateWindowCompletesWithNoInWindowDetail(@TempDir Path tempDir) throws Exception {
        String inNormalWindow1 = "TXNREV0000000001";
        String inNormalWindow2 = "TXNREV0000000002";
        // Both dated inside 2022 - a normal [2022-01-01 .. 2022-12-31] window would include them.
        transactionRepository.saveAll(List.of(
                transaction(inNormalWindow1, CARD_1, "10.00", at(2022, 6, 15)),
                transaction(inNormalWindow2, CARD_2, "20.00", at(2022, 7, 20))));

        Path reportOut = tempDir.resolve("tranrept-reversed.txt");
        // Reversed window: startDate=2022-12-31 (END_DATE), endDate=2022-01-01 (START_DATE).
        JobExecution execution = launchReport(reportOut, END_DATE, START_DATE);

        // Core Finding K fix: the job COMPLETES, it does not abend on the reversed window.
        assertThat(execution.getStatus()).isEqualTo(BatchStatus.COMPLETED);
        assertThat(execution.getExitStatus()).isEqualTo(ExitStatus.COMPLETED);
        assertThat(Files.exists(reportOut)).isTrue();

        List<String> lines = readReport(reportOut);
        assertThat(lines).isNotEmpty();
        // Lazy header (1120-WRITE-HEADERS) never fires without an in-window detail record: no name header.
        assertThat(linesStartingWith(lines, NAME_HEADER_PREFIX)).isEmpty();
        // Every seeded record is excluded, so no detail line carries either seeded transaction id ...
        String report = String.join("\n", lines);
        assertThat(report).doesNotContain(inNormalWindow1);
        assertThat(report).doesNotContain(inNormalWindow2);
        // ... and no control break occurs, so no account-total line is emitted.
        assertThat(linesStartingWith(lines, ReportAccountTotals.LABEL)).isEmpty();
        // The end-of-file trailer still emits exactly one page-total (stale-add quirk) and one grand total.
        assertThat(linesStartingWith(lines, ReportPageTotals.LABEL)).hasSize(1);
        assertThat(linesStartingWith(lines, ReportGrandTotals.LABEL)).hasSize(1);
    }

    /**
     * Launches {@code transactionReportJob} for the class reporting window, writing to the supplied
     * output path with a unique {@code run.id} so each launch is a fresh job instance.
     *
     * @param outputPath the report output file
     * @return the resulting {@link JobExecution}
     * @throws Exception if the launch fails
     */
    private JobExecution launchReport(Path outputPath) throws Exception {
        return launchReport(outputPath, START_DATE, END_DATE);
    }

    /**
     * Launches {@code transactionReportJob} for an explicit {@code [startDate, endDate]} window,
     * writing to the supplied output path with a unique {@code run.id} so each launch is a fresh job
     * instance. Used to exercise a reversed window (Finding K parity), where the bounds are passed in
     * end-before-start order rather than the class default.
     *
     * @param outputPath the report output file
     * @param startDate  the {@code startDate} job parameter ({@code yyyy-MM-dd})
     * @param endDate    the {@code endDate} job parameter ({@code yyyy-MM-dd})
     * @return the resulting {@link JobExecution}
     * @throws Exception if the launch fails
     */
    private JobExecution launchReport(Path outputPath, String startDate, String endDate)
            throws Exception {
        JobParameters parameters = new JobParametersBuilder()
                .addString("startDate", startDate)
                .addString("endDate", endDate)
                .addString("outputPath", outputPath.toString())
                .addLong("run.id", System.nanoTime())
                .toJobParameters();
        return jobLauncherTestUtils.launchJob(parameters);
    }

    /**
     * Reads the report as fixed-length {@value #REPORT_RECORD_WIDTH}-byte records.
     *
     * <p>The report file is an undelimited fixed-block ({@code LRECL 133 RECFM FB}) dataset with
     * <strong>no</strong> line separator between records (review finding #17), so it must be split on
     * the fixed record boundary rather than read as newline-delimited text. The whole file is read as
     * {@code ISO-8859-1} bytes (one byte per character for the report's pure-ASCII content) and sliced
     * into consecutive {@value #REPORT_RECORD_WIDTH}-character records. The total file length must be
     * an exact multiple of the record width, which this method asserts.</p>
     *
     * @param outputPath the report output file
     * @return the report records, each exactly {@value #REPORT_RECORD_WIDTH} characters, in file order
     * @throws Exception if the file cannot be read
     */
    private List<String> readReport(Path outputPath) throws Exception {
        byte[] bytes = Files.readAllBytes(outputPath);
        assertThat(bytes.length % REPORT_RECORD_WIDTH)
                .as("report file length must be a whole number of %d-byte records "
                        + "(undelimited RECFM FB)", REPORT_RECORD_WIDTH)
                .isZero();
        String content = new String(bytes, StandardCharsets.ISO_8859_1);
        List<String> records = new ArrayList<>(content.length() / REPORT_RECORD_WIDTH);
        for (int offset = 0; offset < content.length(); offset += REPORT_RECORD_WIDTH) {
            records.add(content.substring(offset, offset + REPORT_RECORD_WIDTH));
        }
        return records;
    }

    /**
     * Returns the 15-character edited amount slice (report columns 98-112) shared by detail and total
     * lines.
     *
     * @param reportLine a 133-byte report line
     * @return the amount field
     */
    private static String amountField(String reportLine) {
        return reportLine.substring(AMOUNT_BEGIN_INDEX, AMOUNT_END_INDEX);
    }

    /**
     * Collects, in order, the report lines that start with {@code prefix} (used to isolate the
     * page-total, account-total and grand-total lines by their fixed labels).
     *
     * @param lines  the report lines
     * @param prefix the line-classifying prefix
     * @return the matching lines in report order
     */
    private static List<String> linesStartingWith(List<String> lines, String prefix) {
        List<String> matches = new ArrayList<>();
        for (String line : lines) {
            if (line.startsWith(prefix)) {
                matches.add(line);
            }
        }
        return matches;
    }

    /**
     * Returns the single detail line whose {@code TRAN-REPORT-TRANS-ID} equals {@code tranId}.
     *
     * @param lines  the report lines
     * @param tranId the 16-character transaction id
     * @return the matching detail line
     */
    private static String requireDetailLine(List<String> lines, String tranId) {
        for (String line : lines) {
            if (line.startsWith(tranId)) {
                return line;
            }
        }
        throw new AssertionError("No detail line found for transaction " + tranId);
    }

    /**
     * Builds a page-break test transaction id of the form {@code TXNPG} + 11-digit index (16 bytes).
     *
     * @param index the 1-based sequence number
     * @return the 16-character transaction id
     */
    private static String pageTranId(int index) {
        return String.format("%s%011d", PAGE_TRAN_PREFIX, index);
    }

    /**
     * Builds a persistable {@link Transaction} tagged with this test's type/category/source.
     *
     * @param tranId  the 16-character transaction id ({@code TRAN-ID})
     * @param cardNum the 16-digit card number ({@code TRAN-CARD-NUM})
     * @param amount  the transaction amount as a plain decimal string ({@code TRAN-AMT})
     * @param procTs  the processing timestamp ({@code TRAN-PROC-TS}); its date drives the filter
     * @return the populated transaction
     */
    private static Transaction transaction(String tranId, String cardNum, String amount,
            LocalDateTime procTs) {
        Transaction transaction = new Transaction();
        transaction.setTranId(tranId);
        transaction.setCardNum(cardNum);
        transaction.setTranTypeCd(TYPE_CD);
        transaction.setTranCatCd(CAT_CD);
        transaction.setTranSource(SOURCE);
        transaction.setTranDesc("PARITY TEST TRANSACTION");
        transaction.setTranAmt(new BigDecimal(amount));
        transaction.setOrigTs(procTs);
        transaction.setProcTs(procTs);
        return transaction;
    }

    /**
     * Builds a noon {@link LocalDateTime} for the given calendar date so only the date component is
     * significant to the processing-date filter.
     *
     * @param year  the year
     * @param month the month (1-12)
     * @param day   the day of month
     * @return the timestamp at 12:00:00 on that date
     */
    private static LocalDateTime at(int year, int month, int day) {
        return LocalDateTime.of(year, month, day, 12, 0, 0);
    }
}
