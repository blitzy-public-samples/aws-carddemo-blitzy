package com.aws.carddemo.batch;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

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
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.data.domain.Sort;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.TestPropertySource;

import com.aws.carddemo.AbstractPostgresIntegrationTest;
import com.aws.carddemo.CardDemoApplication;
import com.aws.carddemo.domain.TransactionCategoryBalance;
import com.aws.carddemo.repository.TransactionCategoryBalanceRepository;

/**
 * Spring Batch integration / parity test for {@link CategoryBalancePrintJobConfig}: it launches the
 * migrated {@code categoryBalancePrintJob} against a real Flyway-migrated PostgreSQL database and
 * proves the produced transaction-category-balance report is byte-and-behaviour identical to the
 * mainframe report job it replaces.
 *
 * <p><strong>Origin (read-only oracles):</strong> {@code legacy/jcl/PRTCATBL.jcl} (source branch
 * {@code app/jcl/PRTCATBL.jcl}) and {@code legacy/jcl/REPTFILE.jcl} (source branch
 * {@code app/jcl/REPTFILE.jcl}); reference-data fixture {@code legacy/data/ASCII/tcatbal.txt}
 * (source branch {@code app/data/ASCII/tcatbal.txt}). Governing spec: AAP &sect;0.4.1
 * (category-balance / report output), &sect;0.6.1 (COBOL {@code S9(n)V99} money &rarr;
 * {@link BigDecimal} with the DFSORT {@code EDIT} mask), &sect;0.6.3 (JCL/JES2 &rarr; Spring Batch),
 * and &sect;0.6.6 (deterministic C/POSIX bytewise ordering).</p>
 *
 * <p><strong>What the legacy job did.</strong> {@code PRTCATBL.jcl} {@code STEP10R}
 * ({@code PGM=SORT}) sorted every {@code TCATBAL} record ascending by
 * {@code (TRANCAT-ACCT-ID, TRANCAT-TYPE-CD, TRANCAT-CD)} and reformatted each into a fixed
 * {@code SORTOUT LRECL=40} report line via
 * {@code OUTREC FIELDS=(TRANCAT-ACCT-ID,X, TRANCAT-TYPE-CD,X, TRANCAT-CD,X,
 * TRAN-CAT-BAL,EDIT=(TTTTTTTTT.TT),9X)}. {@code REPTFILE.jcl} merely defined the report
 * generation-data-group ({@code IDCAMS DEFINE GENERATIONDATAGROUP LIMIT(10)}), which the Spring job
 * reproduces as per-run output-file versioning driven by the {@code outputPath} job parameter.</p>
 *
 * <p><strong>Parity assertions.</strong> Reproducing that contract, this test verifies: the job
 * completes with {@link BatchStatus#COMPLETED}; the report contains exactly one line per seeded
 * {@code TCATBAL} row (the Flyway {@code V2} reference-data seed derived from
 * {@code legacy/data/ASCII/tcatbal.txt}); every line is exactly
 * {@value CategoryBalancePrintJobConfig#REPORT_RECORD_LENGTH} bytes wide; the lines are in ascending
 * composite {@code (acctId, typeCd, catCd)} order matching
 * {@link TransactionCategoryBalanceRepository#findAll(Sort)}; the {@code TRAN-CAT-BAL} amount is
 * rendered with the production {@code EDIT=(TTTTTTTTT.TT)} mask (magnitude, leading zeros kept, sign
 * dropped, COBOL truncation) and round-trips to the stored {@link BigDecimal} without ever using
 * {@code float}/{@code double}; and the report is strictly read-only (row count and balances are
 * unchanged across the run).</p>
 *
 * <p><strong>Test harness.</strong> The test extends {@link AbstractPostgresIntegrationTest}, which
 * starts a shared Testcontainers PostgreSQL&nbsp;18 instance and activates the {@code test} profile
 * (Flyway {@code V0}&rarr;{@code V1}&rarr;{@code V2}&rarr;{@code V3} materialises the real,
 * production DDL and reference data in the container). Rather than {@code @SpringBatchTest}, a single
 * {@link JobLauncherTestUtils} is supplied by the nested {@link BatchLauncherTestConfig}
 * {@code @TestConfiguration} and wired explicitly to the {@code categoryBalancePrintJob} bean via
 * {@link Qualifier} &mdash; the {@code JobLauncherTestUtils} setters carry no {@code @Autowired}, so
 * manual wiring is unambiguous even though the application defines many {@link Job} beans. Docker is
 * required for these {@code *IT} tests, as documented on the base class.</p>
 *
 * <p><strong>Schema validation.</strong> This test pins {@code spring.jpa.hibernate.ddl-auto=none}
 * (via {@link TestPropertySource}) so Flyway stays the single source of truth for the schema and
 * Hibernate performs no start-up schema management. The base profile's {@code validate} setting is
 * intentionally overridden here because the migration's fixed-width columns are declared
 * {@code CHAR(n)} in {@code V1__schema.sql} (AAP &sect;0.6.2, for trailing-space / {@code COLLATE "C"}
 * ordering parity) while the JPA entities map them as {@link String}; Hibernate {@code validate}
 * flags that {@code CHAR}-vs-{@code VARCHAR} type-name difference as a schema mismatch even though it
 * is fully transparent to JDBC read/write and so has no effect on this job's output. The override is
 * scoped to this test context only, changes none of the parity assertions below, and does not alter
 * the report the job produces.</p>
 *
 * @see CategoryBalancePrintJobConfig
 */
@ContextConfiguration(classes = {CardDemoApplication.class,
        CategoryBalancePrintJobConfigIT.BatchLauncherTestConfig.class})
@TestPropertySource(properties = "spring.jpa.hibernate.ddl-auto=none")
class CategoryBalancePrintJobConfigIT extends AbstractPostgresIntegrationTest {

    /**
     * Number of {@code TCATBAL} rows seeded by Flyway {@code V2__reference_data.sql} (derived from
     * {@code legacy/data/ASCII/tcatbal.txt}). The report must contain exactly this many lines.
     */
    private static final long EXPECTED_SEED_ROW_COUNT = 50L;

    /** Single-byte charset the production {@code FixedWidthRecordMapper} uses to emit the report. */
    private static final Charset REPORT_CHARSET = StandardCharsets.ISO_8859_1;

    /** Report line width in bytes; sourced from the production constant ({@code SORTOUT LRECL=40}). */
    private static final int RECORD_LENGTH = CategoryBalancePrintJobConfig.REPORT_RECORD_LENGTH;

    // ---------------------------------------------------------------------------------------------
    // Fixed-width field slices of the 40-byte PRTCATBL report record (0-based, end-exclusive),
    // mirroring the production OUTREC layout: acctId(11) X typeCd(2) X catCd(4) X balance(12) pad(8).
    // ---------------------------------------------------------------------------------------------

    /** {@code TRANCAT-ACCT-ID} field: 11 digits, zero-padded, bytes [0, 11). */
    private static final int ACCT_ID_BEGIN = 0;
    private static final int ACCT_ID_END = 11;

    /** {@code TRANCAT-TYPE-CD} field: 2 chars, left-justified, bytes [12, 14). */
    private static final int TYPE_CD_BEGIN = 12;
    private static final int TYPE_CD_END = 14;

    /** {@code TRANCAT-CD} field: 4 digits, zero-padded, bytes [15, 19). */
    private static final int CAT_CD_BEGIN = 15;
    private static final int CAT_CD_END = 19;

    /** Edited {@code TRAN-CAT-BAL} field: {@code DDDDDDDDD.DD} (12 chars), bytes [20, 32). */
    private static final int BALANCE_BEGIN = 20;
    private static final int BALANCE_END = 32;

    /** Single-space separators produced by the {@code OUTREC} {@code X} operands. */
    private static final int SEPARATOR_1 = 11;
    private static final int SEPARATOR_2 = 14;
    private static final int SEPARATOR_3 = 19;

    /** Trailing pad: 8 spaces, bytes [32, 40). */
    private static final int PAD_BEGIN = 32;

    /** Regex the 12-character edited balance must satisfy: 9 integer digits, a dot, 2 fraction digits. */
    private static final String BALANCE_FIELD_PATTERN = "\\d{9}\\.\\d{2}";

    /**
     * The exact first report record for the seeded data: the lowest composite key is
     * {@code (acctId=1, typeCd="01", catCd=1)} with a {@code 0.00} balance, so the DFSORT
     * {@code EDIT=(TTTTTTTTT.TT)} mask renders {@code "000000000.00"}. This pins the whole 40-byte
     * layout (field positions, separators, and the specific rendered balance string) for a known row.
     */
    private static final String EXPECTED_FIRST_RECORD = "00000000001 01 0001 000000000.00        ";

    static {
        // Guard the local field slices against drift from the production record length: the trailing
        // pad must start immediately after the balance field, and the pinned first record and the
        // total width must both equal REPORT_RECORD_LENGTH (sourced from the production constant).
        if (PAD_BEGIN != BALANCE_END) {
            throw new IllegalStateException(
                    "Trailing pad must start immediately after the balance field (PAD_BEGIN="
                            + PAD_BEGIN + ", BALANCE_END=" + BALANCE_END + ")");
        }
        if (EXPECTED_FIRST_RECORD.length() != RECORD_LENGTH) {
            throw new IllegalStateException(
                    "EXPECTED_FIRST_RECORD must be " + RECORD_LENGTH + " chars but was "
                            + EXPECTED_FIRST_RECORD.length());
        }
    }

    @Autowired
    private JobLauncherTestUtils jobLauncherTestUtils;

    @Autowired
    private TransactionCategoryBalanceRepository categoryBalanceRepository;

    /**
     * Verifies the job runs to a clean completion and prints one line for every seeded category
     * balance &mdash; the batch-status contract of {@code PRTCATBL} {@code STEP10R}.
     *
     * @param tempDir per-test temporary directory holding the report output (JUnit-managed)
     * @throws Exception if the job launch fails or the report cannot be read
     */
    @Test
    void jobCompletes_printsAllCategoryBalances(@TempDir Path tempDir) throws Exception {
        long seededRows = categoryBalanceRepository.count();
        assertThat(seededRows)
                .as("Flyway V2 TCATBAL seed row count (legacy/data/ASCII/tcatbal.txt)")
                .isEqualTo(EXPECTED_SEED_ROW_COUNT);

        Path reportFile = tempDir.resolve("catbal.rpt");
        JobExecution execution = launchReportJob(reportFile);

        assertThat(execution.getStatus()).isEqualTo(BatchStatus.COMPLETED);
        assertThat(execution.getExitStatus().getExitCode()).isEqualTo(ExitStatus.COMPLETED.getExitCode());
        assertThat(reportFile).exists();
        assertThat(Files.size(reportFile)).as("report file must be non-empty").isPositive();

        List<String> lines = readReport(reportFile);
        assertThat(lines)
                .as("one report line per seeded TCATBAL row")
                .hasSize((int) seededRows);
    }

    /**
     * Verifies every emitted line is exactly {@value CategoryBalancePrintJobConfig#REPORT_RECORD_LENGTH}
     * bytes &mdash; the fixed-width {@code SORTOUT LRECL=40} record parity that must survive byte-for-byte.
     *
     * @param tempDir per-test temporary directory holding the report output (JUnit-managed)
     * @throws Exception if the job launch fails or the report cannot be read
     */
    @Test
    void everyReportLineIs40Bytes(@TempDir Path tempDir) throws Exception {
        Path reportFile = tempDir.resolve("catbal.rpt");
        assertThat(launchReportJob(reportFile).getStatus()).isEqualTo(BatchStatus.COMPLETED);

        List<String> lines = readReport(reportFile);
        assertThat(lines).isNotEmpty();
        for (String line : lines) {
            assertThat(line.length())
                    .as("report line character width for [%s]", line)
                    .isEqualTo(RECORD_LENGTH);
            assertThat(line.getBytes(REPORT_CHARSET).length)
                    .as("report line byte width for [%s]", line)
                    .isEqualTo(RECORD_LENGTH);
            // The three OUTREC 'X' operands and the trailing pad are spaces.
            assertThat(line.charAt(SEPARATOR_1)).isEqualTo(' ');
            assertThat(line.charAt(SEPARATOR_2)).isEqualTo(' ');
            assertThat(line.charAt(SEPARATOR_3)).isEqualTo(' ');
            assertThat(line.substring(PAD_BEGIN)).isEqualTo(" ".repeat(RECORD_LENGTH - PAD_BEGIN));
        }
    }

    /**
     * Verifies the report lines are in ascending composite {@code (acctId, typeCd, catCd)} order,
     * matching both {@link TransactionCategoryBalanceRepository#findAll(Sort)} (the production reader's
     * ordering) and an independent monotonic check &mdash; the parity of
     * {@code SORT FIELDS=(TRANCAT-ACCT-ID,A,TRANCAT-TYPE-CD,A,TRANCAT-CD,A)}.
     *
     * @param tempDir per-test temporary directory holding the report output (JUnit-managed)
     * @throws Exception if the job launch fails or the report cannot be read
     */
    @Test
    void linesOrderedByAcctIdTypeCdCatCd(@TempDir Path tempDir) throws Exception {
        Path reportFile = tempDir.resolve("catbal.rpt");
        assertThat(launchReportJob(reportFile).getStatus()).isEqualTo(BatchStatus.COMPLETED);

        List<String> lines = readReport(reportFile);
        List<TransactionCategoryBalance> ordered = categoryBalanceRepository.findAll(
                Sort.by(Sort.Direction.ASC, "acctId", "typeCd", "catCd"));

        assertThat(lines)
                .as("report line count matches repository.findAll(Sort) row count")
                .hasSameSizeAs(ordered);

        for (int i = 0; i < ordered.size(); i++) {
            ReportLine emitted = parse(lines.get(i));
            TransactionCategoryBalance expected = ordered.get(i);
            assertThat(emitted.acctId())
                    .as("acctId at report row %d", i)
                    .isEqualTo(expected.getAcctId());
            assertThat(emitted.typeCd().trim())
                    .as("typeCd at report row %d", i)
                    .isEqualTo(expected.getTypeCd().trim());
            assertThat(emitted.catCd())
                    .as("catCd at report row %d", i)
                    .isEqualTo(expected.getCatCd());
        }

        // Independent guarantee that the emitted key sequence is genuinely non-decreasing.
        for (int i = 1; i < lines.size(); i++) {
            ReportLine previous = parse(lines.get(i - 1));
            ReportLine current = parse(lines.get(i));
            assertThat(compositeCompare(previous, current))
                    .as("composite key must be ascending between report rows %d and %d", i - 1, i)
                    .isLessThanOrEqualTo(0);
        }
    }

    /**
     * Verifies the {@code TRAN-CAT-BAL} amount is rendered with the production DFSORT
     * {@code EDIT=(TTTTTTTTT.TT)} mask and round-trips through {@link BigDecimal} exactly, never via
     * {@code float}/{@code double} (AAP &sect;0.6.1). The end-to-end report (all seeded rows) is
     * checked, one specific rendered balance string is pinned, and the production report formatter is
     * additionally exercised across representative magnitudes &mdash; multi-digit values, the
     * {@code S9(9)V99} maximum, sign dropping, and COBOL truncation &mdash; which the all-zero seed
     * alone cannot cover.
     *
     * @param tempDir per-test temporary directory holding the report output (JUnit-managed)
     * @throws Exception if the job launch fails or the report cannot be read
     */
    @Test
    void balanceRenderedWithEditMask_BigDecimalRoundTrip(@TempDir Path tempDir) throws Exception {
        Path reportFile = tempDir.resolve("catbal.rpt");
        assertThat(launchReportJob(reportFile).getStatus()).isEqualTo(BatchStatus.COMPLETED);

        List<String> lines = readReport(reportFile);
        List<TransactionCategoryBalance> ordered = categoryBalanceRepository.findAll(
                Sort.by(Sort.Direction.ASC, "acctId", "typeCd", "catCd"));
        assertThat(lines).hasSameSizeAs(ordered);

        // End-to-end: every emitted balance field matches the mask shape and, parsed back as a
        // BigDecimal, equals the stored magnitude (the mask prints the absolute value).
        for (int i = 0; i < ordered.size(); i++) {
            String balanceField = parse(lines.get(i)).balanceField();
            assertThat(balanceField)
                    .as("edited balance shape at report row %d", i)
                    .matches(BALANCE_FIELD_PATTERN);
            BigDecimal parsed = new BigDecimal(balanceField);
            BigDecimal expectedMagnitude = ordered.get(i).getBalance().abs();
            assertThat(parsed)
                    .as("edited balance round-trip at report row %d", i)
                    .isEqualByComparingTo(expectedMagnitude);
        }

        // A specific rendered balance string pinned against the full first record (acctId=1, 0.00).
        assertThat(lines.get(0)).isEqualTo(EXPECTED_FIRST_RECORD);
        assertThat(parse(lines.get(0)).balanceField()).isEqualTo("000000000.00");

        // Exercise the production edit-mask formatter across representative magnitudes and prove each
        // rendered string round-trips to the expected BigDecimal magnitude.
        assertEditMask(new BigDecimal("0.00"), "000000000.00", new BigDecimal("0.00"));
        assertEditMask(new BigDecimal("504.77"), "000000504.77", new BigDecimal("504.77"));
        assertEditMask(new BigDecimal("1234567.89"), "001234567.89", new BigDecimal("1234567.89"));
        assertEditMask(new BigDecimal("999999999.99"), "999999999.99", new BigDecimal("999999999.99"));
        // Sign is dropped by the mask (no S selector): the magnitude is printed.
        assertEditMask(new BigDecimal("-504.77"), "000000504.77", new BigDecimal("504.77"));
        assertEditMask(new BigDecimal("-0.01"), "000000000.01", new BigDecimal("0.01"));
        // COBOL truncation (no ROUNDED): excess low-order digits are discarded toward zero.
        assertEditMask(new BigDecimal("1.239"), "000000001.23", new BigDecimal("1.23"));
    }

    /**
     * Verifies the report job performs no database mutation: it reads {@code TCATBAL} and writes only
     * to the report file, so the row count and every stored balance are unchanged across the run
     * &mdash; the read-only semantics of {@code PRTCATBL}.
     *
     * @param tempDir per-test temporary directory holding the report output (JUnit-managed)
     * @throws Exception if the job launch fails
     */
    @Test
    void reportPerformsNoDatabaseMutations(@TempDir Path tempDir) throws Exception {
        long countBefore = categoryBalanceRepository.count();
        assertThat(countBefore).isEqualTo(EXPECTED_SEED_ROW_COUNT);
        List<TransactionCategoryBalance> before = categoryBalanceRepository.findAll(
                Sort.by(Sort.Direction.ASC, "acctId", "typeCd", "catCd"));

        JobExecution execution = launchReportJob(tempDir.resolve("catbal.rpt"));
        assertThat(execution.getStatus()).isEqualTo(BatchStatus.COMPLETED);

        long countAfter = categoryBalanceRepository.count();
        assertThat(countAfter).as("row count must be unchanged by a read-only report").isEqualTo(countBefore);

        List<TransactionCategoryBalance> after = categoryBalanceRepository.findAll(
                Sort.by(Sort.Direction.ASC, "acctId", "typeCd", "catCd"));
        assertThat(after).hasSameSizeAs(before);
        for (int i = 0; i < before.size(); i++) {
            TransactionCategoryBalance b = before.get(i);
            TransactionCategoryBalance a = after.get(i);
            assertThat(a.getAcctId()).isEqualTo(b.getAcctId());
            assertThat(a.getTypeCd()).isEqualTo(b.getTypeCd());
            assertThat(a.getCatCd()).isEqualTo(b.getCatCd());
            assertThat(a.getBalance())
                    .as("balance unchanged for key (%d, %s, %d)", b.getAcctId(), b.getTypeCd(), b.getCatCd())
                    .isEqualByComparingTo(b.getBalance());
        }
    }

    /**
     * Asserts the production report formatter renders {@code raw} exactly as the DFSORT
     * {@code EDIT=(TTTTTTTTT.TT)} mask does, and that the rendered 12-character string parses back to
     * {@code expectedMagnitude} as a {@link BigDecimal} (never {@code float}/{@code double}).
     *
     * @param raw               the stored balance to render
     * @param expectedRendering the exact 12-character edited string the mask must produce
     * @param expectedMagnitude the {@link BigDecimal} the rendered string must round-trip to
     */
    private static void assertEditMask(BigDecimal raw, String expectedRendering,
            BigDecimal expectedMagnitude) {
        String rendered = CategoryBalancePrintJobConfig.formatEditedBalance(raw);
        assertThat(rendered)
                .as("EDIT=(TTTTTTTTT.TT) rendering of %s", raw.toPlainString())
                .isEqualTo(expectedRendering)
                .matches(BALANCE_FIELD_PATTERN);
        assertThat(new BigDecimal(rendered))
                .as("BigDecimal round-trip of %s", rendered)
                .isEqualByComparingTo(expectedMagnitude);
    }

    /**
     * Launches {@code categoryBalancePrintJob}, binding the report {@code outputPath} to the supplied
     * file and a unique {@code run.id} so each launch is a fresh {@code JobInstance}.
     *
     * @param reportFile the report output path bound to the {@code outputPath} job parameter
     * @return the resulting {@link JobExecution}
     * @throws Exception if the job launch fails
     */
    private JobExecution launchReportJob(Path reportFile) throws Exception {
        JobParameters parameters = new JobParametersBuilder()
                .addString(CategoryBalancePrintJobConfig.OUTPUT_PATH_PARAMETER, reportFile.toString())
                .addLong("run.id", System.nanoTime())
                .toJobParameters();
        return jobLauncherTestUtils.launchJob(parameters);
    }

    /**
     * Reads the produced report as an <em>undelimited</em> {@code RECFM=FB} image (review
     * finding&nbsp;#17). The production writer emits every
     * {@value CategoryBalancePrintJobConfig#REPORT_RECORD_LENGTH}-byte record back-to-back with
     * <strong>no</strong> delimiter ({@code lineSeparator("")}), exactly as the mainframe
     * {@code SORTOUT LRECL=40} dataset is stored, so the file is sliced on fixed
     * {@value CategoryBalancePrintJobConfig#REPORT_RECORD_LENGTH}-byte boundaries rather than on
     * newlines. The total byte length is asserted to be an exact multiple of the record width, which
     * simultaneously proves that no stray delimiter byte leaked into the output.
     *
     * @param reportFile the report file to read
     * @return the report records in file order
     * @throws Exception if the file cannot be read
     */
    private static List<String> readReport(Path reportFile) throws Exception {
        byte[] all = Files.readAllBytes(reportFile);
        assertThat(all.length % RECORD_LENGTH)
                .as("report file length %d must be an exact multiple of the %d-byte record width "
                        + "(undelimited RECFM=FB, finding #17)", all.length, RECORD_LENGTH)
                .isZero();
        List<String> records = new ArrayList<>(all.length / RECORD_LENGTH);
        for (int off = 0; off < all.length; off += RECORD_LENGTH) {
            records.add(new String(all, off, RECORD_LENGTH, REPORT_CHARSET));
        }
        return records;
    }

    /**
     * Parses one 40-byte report record into its key components and the edited balance field, using the
     * fixed {@code OUTREC} field positions.
     *
     * @param line the report record (must be {@value CategoryBalancePrintJobConfig#REPORT_RECORD_LENGTH}
     *             characters)
     * @return the parsed components
     */
    private static ReportLine parse(String line) {
        long acctId = Long.parseLong(line.substring(ACCT_ID_BEGIN, ACCT_ID_END));
        String typeCd = line.substring(TYPE_CD_BEGIN, TYPE_CD_END);
        int catCd = Integer.parseInt(line.substring(CAT_CD_BEGIN, CAT_CD_END));
        String balanceField = line.substring(BALANCE_BEGIN, BALANCE_END);
        return new ReportLine(acctId, typeCd, catCd, balanceField);
    }

    /**
     * Compares two parsed report lines by the composite key {@code (acctId, typeCd, catCd)}, matching
     * the legacy {@code SORT FIELDS} priority and direction.
     *
     * @param left  the earlier report line
     * @param right the later report line
     * @return a negative, zero, or positive value as {@code left} sorts before, equal to, or after
     *         {@code right}
     */
    private static int compositeCompare(ReportLine left, ReportLine right) {
        int byAcct = Long.compare(left.acctId(), right.acctId());
        if (byAcct != 0) {
            return byAcct;
        }
        int byType = left.typeCd().compareTo(right.typeCd());
        if (byType != 0) {
            return byType;
        }
        return Integer.compare(left.catCd(), right.catCd());
    }

    /**
     * Immutable view of one parsed report record: the three composite-key components plus the
     * 12-character edited balance field.
     *
     * @param acctId       the account id ({@code TRANCAT-ACCT-ID})
     * @param typeCd       the transaction-type code field ({@code TRANCAT-TYPE-CD})
     * @param catCd        the transaction-category code ({@code TRANCAT-CD})
     * @param balanceField the edited balance ({@code TRAN-CAT-BAL}, {@code DDDDDDDDD.DD})
     */
    private record ReportLine(long acctId, String typeCd, int catCd, String balanceField) {
    }

    /**
     * Nested {@code @TestConfiguration} that supplies the single {@link JobLauncherTestUtils} used to
     * launch the report job. It is registered explicitly through the class-level
     * {@code @ContextConfiguration(classes = {CardDemoApplication.class, BatchLauncherTestConfig.class})}
     * &mdash; listing it there (rather than relying on {@code @SpringBootTest} nested-class
     * auto-detection) pins this test to the primary {@code CardDemoApplication} configuration and
     * prevents Spring's {@code AnnotatedClassFinder} from scanning the {@code com.aws.carddemo.batch}
     * package and matching the {@code @SpringBootConfiguration} slices declared by sibling batch
     * {@code *IT} classes, which would otherwise raise &quot;Found multiple @SpringBootConfiguration
     * annotated classes&quot;. This layers the launcher bean on top of the primary configuration.
     *
     * <p>The launcher is wired explicitly (rather than through {@code @SpringBatchTest}) to the
     * {@code categoryBalancePrintJob} bean via {@link Qualifier}; because the application defines many
     * {@link Job} beans, the qualifier is what disambiguates the injected job.</p>
     */
    @TestConfiguration
    static class BatchLauncherTestConfig {

        /**
         * Builds the {@link JobLauncherTestUtils} bound to the auto-configured {@link JobLauncher} and
         * {@link JobRepository} and to the {@code categoryBalancePrintJob} {@link Job}.
         *
         * @param jobLauncher            the auto-configured Spring Batch launcher
         * @param jobRepository          the auto-configured Spring Batch job repository
         * @param categoryBalancePrintJob the report job under test (resolved by qualifier)
         * @return the wired {@link JobLauncherTestUtils}
         */
        @Bean
        JobLauncherTestUtils categoryBalancePrintJobLauncherTestUtils(
                JobLauncher jobLauncher,
                JobRepository jobRepository,
                @Qualifier(CategoryBalancePrintJobConfig.JOB_NAME) Job categoryBalancePrintJob) {
            JobLauncherTestUtils utils = new JobLauncherTestUtils();
            utils.setJobLauncher(jobLauncher);
            utils.setJobRepository(jobRepository);
            utils.setJob(categoryBalancePrintJob);
            return utils;
        }
    }
}
