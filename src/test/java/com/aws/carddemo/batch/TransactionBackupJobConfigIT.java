package com.aws.carddemo.batch;

import static org.assertj.core.api.Assertions.assertThat;

import com.aws.carddemo.AbstractPostgresIntegrationTest;
import com.aws.carddemo.config.BatchConfig;
import com.aws.carddemo.domain.Transaction;
import com.aws.carddemo.repository.TransactionRepository;
import com.aws.carddemo.util.FixedWidthRecordMapper;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.ExitStatus;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.JobExecution;
import org.springframework.batch.core.JobParameters;
import org.springframework.batch.core.JobParametersBuilder;
import org.springframework.batch.core.StepExecution;
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
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.test.context.TestPropertySource;

/**
 * End-to-end Testcontainers integration test (a byte-parity oracle) for
 * {@link TransactionBackupJobConfig}, the Spring Batch job that reproduces the mainframe
 * transaction-master backup.
 *
 * <p><strong>Origin and traceability (AAP &sect;0.4.1, &sect;0.6.4; Explainability rule).</strong>
 * The authoritative source is {@code legacy/jcl/TRANBKP.jcl} (source branch
 * {@code app/jcl/TRANBKP.jcl}), whose {@code REPROC} PROC runs an {@code IDCAMS REPRO} copying the
 * {@code TRANSACT} VSAM KSDS record-for-record to a sequential backup generation
 * ({@code DCB=(LRECL=350,RECFM=FB)}). This test verifies that the migrated
 * {@code transactionBackupJob} exports every {@link Transaction} row to a fixed-width, 350-byte
 * {@code CVTRA05Y} {@code TRAN-RECORD} flat file, ordered ascending by {@code tranId}, such that the
 * written file re-parses back into records byte-identical to the source rows (the round-trip
 * {@code parse(format(x)) == x} that defines backup parity).</p>
 *
 * <p><strong>{@code CBTRN01C} is SUPERSEDED &mdash; reference only.</strong> The COBOL program
 * {@code legacy/cbl/CBTRN01C.cbl} ("validate the daily transaction file") is a legacy validation-only
 * pass with no JCL runner; its sequential read-and-validate logic is subsumed by the posting job
 * ({@code CBTRN02C} &rarr; {@code PostTransactionJobConfig}) and the batch reader. It is therefore
 * <em>not</em> exercised here; this class realizes only the {@code TRANBKP.jcl} {@code REPRO} export.
 * Documenting the supersession explicitly keeps the traceability matrix at 100% with no silent drops
 * (AAP &sect;0.6.10).</p>
 *
 * <p><strong>What is asserted.</strong> Against a real PostgreSQL 18 database seeded with deterministic
 * rows inserted out of key order:</p>
 * <ol>
 *   <li>the job completes with {@link BatchStatus#COMPLETED};</li>
 *   <li>the step write count equals the number of exported transaction rows;</li>
 *   <li>every written line is exactly {@value TransactionBackupJobConfig#RECORD_LENGTH} bytes
 *       (read with the single-byte {@link StandardCharsets#ISO_8859_1} charset);</li>
 *   <li>records are written in ascending {@code tranId} order (bytewise {@code C}/POSIX collation on
 *       the {@code CHAR(16)} key, AAP &sect;0.6.6);</li>
 *   <li>a written record round-trips: parsing the 350-byte line back through
 *       {@link FixedWidthRecordMapper} yields fields equal to the database {@link Transaction} &mdash;
 *       the monetary amount is compared as a {@link BigDecimal} (never {@code float}/{@code double}),
 *       the trailing {@code FILLER} is 20 spaces, and numeric fields are zero-padded per COBOL
 *       {@code 9(n)};</li>
 *   <li>the export mutates nothing: the transaction row count is unchanged.</li>
 * </ol>
 *
 * <p><strong>Harness (AAP binding constraint).</strong> This test extends
 * {@link AbstractPostgresIntegrationTest} and therefore inherits the shared, single
 * {@code postgres:18-alpine} Testcontainers database, the {@code test} profile, and the dynamic
 * datasource wiring; it deliberately does <em>not</em> use {@code @SpringBatchTest}. Rather than
 * loading the entire application, it pins a small, purpose-built slice ({@link BackupJobSliceConfig})
 * as the sole {@code @SpringBootTest} configuration. Pinning the configuration explicitly is
 * necessary for two reasons: (a) it stops Spring Boot from walking this package for a
 * {@code @SpringBootConfiguration}, which would otherwise adopt an unrelated sibling batch test's
 * nested configuration as the primary context; and (b) it keeps the context to just the beans this
 * parity check needs (auto-configured {@link JobLauncher}/{@link JobRepository}, the domain entity
 * mapping, {@link TransactionRepository}, and the production {@code transactionBackupJob} imported
 * unchanged from {@link TransactionBackupJobConfig}), minimising coupling to unrelated layers. A
 * single {@link JobLauncherTestUtils} is supplied by the nested {@link BatchTestHarnessConfig} and
 * wired explicitly to the {@code transactionBackupJob} via {@link Qualifier}.</p>
 *
 * <p><strong>Schema authority (AAP &sect;0.6.2, &sect;0.6.6).</strong> The slice still runs the real
 * Flyway migrations {@code V0}&rarr;{@code V3}, so the fixed-width {@code CHAR(n)} columns and the
 * {@code COLLATE "C"} bytewise ordering that the 350-byte width and ascending-{@code tranId}
 * assertions depend on are the genuine production schema. Flyway is the authoritative owner of the
 * DDL; Hibernate's optional schema <em>validation</em> is switched off here
 * ({@code spring.jpa.hibernate.ddl-auto=none}) because the entities intentionally map the legacy
 * fixed-width {@code CHAR(n)} fields as {@code String} without a per-column {@code columnDefinition},
 * which is the standard "migration tool owns the schema" arrangement and keeps this test independent
 * of entity-layer column-type annotations that are outside its concern.</p>
 *
 * <p><strong>Runtime prerequisite.</strong> Execution requires a Testcontainers-capable environment
 * (a reachable Docker daemon able to start {@code postgres:18-alpine}). Where Docker is unavailable
 * offline, the same byte-exact layout, 350-byte width, and {@code BigDecimal} amount fidelity are
 * additionally covered fully in-process by {@link TransactionBackupJobConfigTest}.</p>
 *
 * @see TransactionBackupJobConfig
 * @see TransactionBackupJobConfigTest
 * @see AbstractPostgresIntegrationTest
 */
// Non-web batch parity slice: WebEnvironment.NONE suppresses the servlet security auto-config
// generated dev-password WARN, and disabling Prometheus export lets the slice fall back to a
// SimpleMeterRegistry so Spring Batch's duplicate spring.batch.job.active meter never trips the
// Prometheus same-tag-keys collision WARN — keeps start logs warning-free (review finding #35).
@SpringBootTest(classes = {
        TransactionBackupJobConfigIT.BackupJobSliceConfig.class,
        TransactionBackupJobConfigIT.BatchTestHarnessConfig.class
}, webEnvironment = SpringBootTest.WebEnvironment.NONE)
@TestPropertySource(properties = {
        "spring.jpa.hibernate.ddl-auto=none",
        "management.prometheus.metrics.export.enabled=false"
})
class TransactionBackupJobConfigIT extends AbstractPostgresIntegrationTest {

    /**
     * Focused Spring Boot slice pinned as the sole context configuration for this parity test.
     *
     * <p>Enabling {@link EnableAutoConfiguration} brings up exactly the infrastructure the backup job
     * needs &mdash; the container {@code DataSource}, JPA/Hibernate, the Spring Batch
     * {@link JobRepository}/{@link JobLauncher}/{@code PlatformTransactionManager}, and Flyway (which
     * applies the real {@code V0}&rarr;{@code V3} migrations). {@link EntityScan} maps the
     * {@code com.aws.carddemo.domain} entities and {@link EnableJpaRepositories} activates the
     * {@code com.aws.carddemo.repository} repositories (the job streams {@link TransactionRepository}).
     * {@link Import}ing the
     * production {@link TransactionBackupJobConfig} exercises the real {@code transactionBackupJob},
     * step, {@code RepositoryItemReader}, and {@code @StepScope} writer unchanged.</p>
     *
     * <p>It is a plain {@link Configuration} (not a {@code @SpringBootConfiguration}) so it never
     * becomes an auto-detectable primary configuration for any other test in this package; it is used
     * only because it is named explicitly in {@link SpringBootTest#classes()}.</p>
     */
    @Configuration(proxyBeanMethods = false)
    @EnableAutoConfiguration
    @EntityScan(basePackageClasses = Transaction.class)
    @EnableJpaRepositories(basePackageClasses = TransactionRepository.class)
    @Import({TransactionBackupJobConfig.class, BatchConfig.class})
    static class BackupJobSliceConfig {
    }

    /** Mirrors {@link TransactionBackupJobConfig} timestamp rendering when building expected values. */
    private static final DateTimeFormatter TS_FORMATTER =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSSSSS");

    /**
     * Original timestamp for the seeded rows. The nanosecond component is a whole number of
     * microseconds (123_456_000 ns = 123456 &micro;s) so it survives a PostgreSQL {@code TIMESTAMP}
     * (microsecond precision) round trip without rounding, keeping the 26-character rendering exact.
     */
    private static final LocalDateTime ORIG_TS = LocalDateTime.of(2022, 7, 18, 9, 5, 3, 123_456_000);

    /** Processing timestamp for the seeded rows (whole-second, likewise round-trip stable). */
    private static final LocalDateTime PROC_TS = LocalDateTime.of(2022, 7, 18, 9, 5, 4, 0);

    /** Seeded transaction ids &mdash; the {@code CHAR(16)} primary key, fully filled (no padding). */
    private static final String TID_1 = "0000000000000001";
    private static final String TID_2 = "0000000000000002";
    private static final String TID_3 = "0000000000000003";

    /** Width of the trailing {@code FILLER PIC X(20)} that pads the record out to 350 bytes. */
    private static final int FILLER_WIDTH = 20;

    /**
     * Supplies the single {@link JobLauncherTestUtils} for this test. Declared as a nested
     * {@code @TestConfiguration} (named alongside {@link BackupJobSliceConfig} in
     * {@link SpringBootTest#classes()}) rather than via {@code @SpringBatchTest}, per the AAP harness
     * constraint. The utility is wired to the {@code transactionBackupJob} explicitly through
     * {@link Qualifier}; the qualifier keeps the binding unambiguous and intention-revealing even
     * though the slice imports exactly one {@link Job}.
     */
    @TestConfiguration
    static class BatchTestHarnessConfig {

        /**
         * Builds the {@link JobLauncherTestUtils} bound to {@code transactionBackupJob}.
         *
         * @param jobLauncher          the auto-configured shared {@link JobLauncher}
         * @param jobRepository        the auto-configured shared {@link JobRepository}
         * @param transactionBackupJob the job under test, resolved by qualifier
         * @return the configured test utility
         */
        @Bean
        JobLauncherTestUtils transactionBackupJobLauncherTestUtils(
                JobLauncher jobLauncher,
                JobRepository jobRepository,
                @Qualifier("transactionBackupJob") Job transactionBackupJob) {
            JobLauncherTestUtils utils = new JobLauncherTestUtils();
            utils.setJobLauncher(jobLauncher);
            utils.setJobRepository(jobRepository);
            utils.setJob(transactionBackupJob);
            return utils;
        }
    }

    @Autowired
    private JobLauncherTestUtils jobLauncherTestUtils;

    @Autowired
    private TransactionRepository transactionRepository;

    /** Per-method temporary directory for the backup output file (fresh for each test). */
    @TempDir
    private Path tempDir;

    /**
     * Resets the {@code transaction} table to a known, deterministic set of three rows before each
     * test. The table starts empty under the {@code test} profile (the {@code V2} reference-data
     * migration deliberately does not seed transactions), so this arrange step is the sole source of
     * data. The rows are inserted <em>out</em> of {@code tranId} order (3, 1, 2) so the ascending-order
     * assertion genuinely exercises the reader's {@code Sort.by("tranId")}; row {@link #TID_2} carries a
     * negative amount and fully populated fields to make the round-trip assertions crisp.
     */
    @BeforeEach
    void seedTransactions() {
        transactionRepository.deleteAll();
        transactionRepository.saveAll(List.of(
                transaction(TID_3, "03", 7, "504.77"),
                transaction(TID_1, "01", 3, "1234.56"),
                transaction(TID_2, "02", 5, "-98765.43")));
    }

    /**
     * The job completes successfully and exports every transaction row: the step write count and the
     * number of written lines both equal the number of rows in the {@code transaction} table,
     * reproducing the {@code IDCAMS REPRO} record-for-record copy of {@code TRANBKP.jcl}.
     *
     * @throws Exception if the job fails to launch
     */
    @Test
    @DisplayName("job completes and exports every transaction (write count == row count)")
    void jobCompletes_exportsAllTransactions() throws Exception {
        Path backupOut = tempDir.resolve("transact.bkp");
        long rowCount = transactionRepository.count();

        JobExecution execution = launchBackup(backupOut);

        assertThat(execution.getStatus()).isEqualTo(BatchStatus.COMPLETED);
        assertThat(execution.getExitStatus()).isEqualTo(ExitStatus.COMPLETED);
        assertThat(totalWriteCount(execution))
                .as("exported record count")
                .isEqualTo(rowCount);

        List<String> lines = readRecords(backupOut);
        assertThat(lines).hasSize((int) rowCount);
    }

    /**
     * Every exported record is exactly {@value TransactionBackupJobConfig#RECORD_LENGTH} bytes wide,
     * the byte-parity guarantee of the {@code CVTRA05Y} {@code TRAN-RECORD} layout (AAP &sect;0.6.4).
     * Width is measured in encoded bytes using the writer's single-byte {@link StandardCharsets#ISO_8859_1}
     * charset, so a 350-character line re-encodes to exactly 350 record bytes.
     *
     * @throws Exception if the job fails to launch
     */
    @Test
    @DisplayName("every backup line is exactly 350 bytes (CVTRA05Y TRAN-RECORD)")
    void everyBackupLineIs350Bytes() throws Exception {
        Path backupOut = tempDir.resolve("transact.bkp");

        JobExecution execution = launchBackup(backupOut);
        assertThat(execution.getStatus()).isEqualTo(BatchStatus.COMPLETED);

        List<String> lines = readRecords(backupOut);
        assertThat(lines).isNotEmpty();
        for (String line : lines) {
            assertThat(line.getBytes(StandardCharsets.ISO_8859_1))
                    .as("backup record byte width")
                    .hasSize(TransactionBackupJobConfig.RECORD_LENGTH);
        }
    }

    /**
     * Records are written in ascending {@code tranId} order even though they were inserted out of
     * order, reproducing the VSAM KSDS primary-key read order that {@code REPRO} preserves. The key is
     * a numeric {@code CHAR(16)} string, so ascending bytewise ({@code C}/POSIX) ordering equals
     * ascending natural ordering here (AAP &sect;0.6.6).
     *
     * @throws Exception if the job fails to launch
     */
    @Test
    @DisplayName("records are written in ascending tranId order (bytewise C collation)")
    void recordsWrittenInAscendingTranIdOrder() throws Exception {
        Path backupOut = tempDir.resolve("transact.bkp");

        JobExecution execution = launchBackup(backupOut);
        assertThat(execution.getStatus()).isEqualTo(BatchStatus.COMPLETED);

        List<String> lines = readRecords(backupOut);
        // The 16-byte TRAN-ID key occupies the leading bytes of every record.
        List<String> tranIds = lines.stream()
                .map(line -> line.substring(0, 16))
                .toList();

        assertThat(tranIds).containsExactly(TID_1, TID_2, TID_3);
        assertThat(tranIds).isSorted();
    }

    /**
     * A written record round-trips losslessly: parsing the 350-byte line back through the
     * {@code CVTRA05Y} {@link FixedWidthRecordMapper} yields field values equal to the source
     * {@link Transaction} read from the database. The monetary amount is compared as a
     * {@link BigDecimal} (the seeded value is deliberately negative to exercise the COBOL trailing
     * overpunch sign) and never as a {@code float}/{@code double}; the trailing {@code FILLER} is 20
     * spaces; and the {@code 9(n)} numeric fields are zero-padded.
     *
     * @throws Exception if the job fails to launch
     */
    @Test
    @DisplayName("round-trip: parsed 350-byte record equals the DB Transaction (BigDecimal amount)")
    void roundTripParseEqualsSourceRecord_BigDecimalAmount() throws Exception {
        Path backupOut = tempDir.resolve("transact.bkp");

        JobExecution execution = launchBackup(backupOut);
        assertThat(execution.getStatus()).isEqualTo(BatchStatus.COMPLETED);

        // The database row is the source of truth for the comparison.
        Transaction db = transactionRepository.findById(TID_2).orElseThrow();

        // Locate the backup line for that key (match by TRAN-ID rather than assuming a position).
        List<String> lines = readRecords(backupOut);
        String line = lines.stream()
                .filter(candidate -> candidate.startsWith(TID_2))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no backup line for tranId " + TID_2));

        byte[] bytes = line.getBytes(StandardCharsets.ISO_8859_1);
        assertThat(bytes).as("record byte width").hasSize(TransactionBackupJobConfig.RECORD_LENGTH);

        FixedWidthRecordMapper.ParsedRecord parsed =
                TransactionBackupJobConfig.TRAN_RECORD_MAPPER.parse(bytes);

        // Primary key: the 16-character TRAN-ID is fully filled, so it compares exactly.
        assertThat(parsed.getText(TransactionBackupJobConfig.F_TRAN_ID)).isEqualTo(TID_2);

        // Text fields: compare with trailing CHAR(n) padding stripped on both sides for robustness.
        assertThat(parsed.getTrimmedText(TransactionBackupJobConfig.F_TRAN_TYPE_CD))
                .isEqualTo(db.getTranTypeCd().strip());
        assertThat(parsed.getTrimmedText(TransactionBackupJobConfig.F_TRAN_SOURCE))
                .isEqualTo(db.getTranSource().strip());
        assertThat(parsed.getTrimmedText(TransactionBackupJobConfig.F_TRAN_DESC))
                .isEqualTo(db.getTranDesc().strip());
        assertThat(parsed.getTrimmedText(TransactionBackupJobConfig.F_TRAN_MERCHANT_NAME))
                .isEqualTo(db.getMerchantName().strip());
        assertThat(parsed.getTrimmedText(TransactionBackupJobConfig.F_TRAN_MERCHANT_CITY))
                .isEqualTo(db.getMerchantCity().strip());
        assertThat(parsed.getTrimmedText(TransactionBackupJobConfig.F_TRAN_MERCHANT_ZIP))
                .isEqualTo(db.getMerchantZip().strip());
        assertThat(parsed.getTrimmedText(TransactionBackupJobConfig.F_TRAN_CARD_NUM))
                .isEqualTo(db.getCardNum().strip());

        // Monetary amount: BigDecimal comparison (scale-insensitive), sign preserved via overpunch.
        assertThat(parsed.getSignedDecimal(TransactionBackupJobConfig.F_TRAN_AMT))
                .isEqualByComparingTo(db.getTranAmt());
        assertThat(parsed.getSignedDecimal(TransactionBackupJobConfig.F_TRAN_AMT).signum())
                .as("seeded amount is negative").isEqualTo(-1);
        assertThat(parsed.isNegative(TransactionBackupJobConfig.F_TRAN_AMT)).isTrue();

        // Numeric fields: value decodes correctly AND is zero-padded per COBOL 9(n).
        assertThat(parsed.getNumeric(TransactionBackupJobConfig.F_TRAN_CAT_CD))
                .isEqualTo(db.getTranCatCd().longValue());
        assertThat(parsed.getNumeric(TransactionBackupJobConfig.F_TRAN_MERCHANT_ID))
                .isEqualTo(db.getMerchantId().longValue());
        assertThat(new String(parsed.getRawBytes(TransactionBackupJobConfig.F_TRAN_CAT_CD),
                StandardCharsets.ISO_8859_1))
                .isEqualTo(String.format("%04d", db.getTranCatCd()));
        assertThat(new String(parsed.getRawBytes(TransactionBackupJobConfig.F_TRAN_MERCHANT_ID),
                StandardCharsets.ISO_8859_1))
                .isEqualTo(String.format("%09d", db.getMerchantId()));

        // Timestamps: fixed 26-character textual rendering.
        assertThat(parsed.getText(TransactionBackupJobConfig.F_TRAN_ORIG_TS))
                .isEqualTo(TS_FORMATTER.format(db.getOrigTs()));
        assertThat(parsed.getText(TransactionBackupJobConfig.F_TRAN_PROC_TS))
                .isEqualTo(TS_FORMATTER.format(db.getProcTs()));

        // Trailing FILLER PIC X(20) is all spaces, rounding the record out to 350 bytes.
        assertThat(line.substring(TransactionBackupJobConfig.RECORD_LENGTH - FILLER_WIDTH,
                TransactionBackupJobConfig.RECORD_LENGTH))
                .isEqualTo(" ".repeat(FILLER_WIDTH));
    }

    /**
     * The backup is strictly read-only: launching it does not insert, update, or delete any rows, so
     * the transaction row count is unchanged and the seeded rows still exist afterwards. This mirrors
     * the {@code IDCAMS REPRO} export, which copies the master without mutating it.
     *
     * @throws Exception if the job fails to launch
     */
    @Test
    @DisplayName("backup is read-only: transaction row count is unchanged")
    void backupPerformsNoDatabaseMutations() throws Exception {
        Path backupOut = tempDir.resolve("transact.bkp");
        long countBefore = transactionRepository.count();

        JobExecution execution = launchBackup(backupOut);
        assertThat(execution.getStatus()).isEqualTo(BatchStatus.COMPLETED);

        assertThat(transactionRepository.count())
                .as("row count unchanged by read-only backup")
                .isEqualTo(countBefore);
        assertThat(transactionRepository.findById(TID_2)).isPresent();
    }

    /**
     * End-to-end gate for QA finding&nbsp;<strong>F-01</strong>: a failure to <em>publish</em> the
     * completed backup output must fail the job, not be silently swallowed as {@code COMPLETED}.
     *
     * <p>The chunk phase writes every record to the deterministic {@code .inprogress} temp exactly as
     * usual, but the final atomic rename cannot succeed because the {@code outputPath} target already
     * exists as a non-empty directory. Before the fix {@link AtomicFileStepPublisher#afterStep} let the
     * resulting {@code UncheckedIOException} escape the listener callback, where Spring Batch merely
     * logged it after having already marked the step {@code COMPLETED} &mdash; so the job reported
     * {@code COMPLETED}/exit&nbsp;0 while the {@code TRANSACT} backup image was never published. The fix
     * fails the step, which fails the job. This test asserts the job now ends {@code FAILED} with the
     * cause recorded, the target is never partially overwritten, and the {@code .inprogress} temp is
     * left in place for a restart to re-publish once the operator clears the cause.</p>
     *
     * @throws Exception if the launcher fails to run the job
     */
    @Test
    @DisplayName("F-01: a publish failure fails the backup job (not a false COMPLETED)")
    void publishFailureFailsTheJob() throws Exception {
        // outputPath already exists as a NON-EMPTY directory -> the atomic move onto it must fail.
        Path backupOut = tempDir.resolve("transact.bkp");
        Files.createDirectory(backupOut);
        Files.writeString(backupOut.resolve("blocker"), "x");
        long countBefore = transactionRepository.count();

        JobExecution execution = launchBackup(backupOut);

        assertThat(execution.getStatus())
                .as("finalization failure fails the job instead of a false COMPLETED")
                .isEqualTo(BatchStatus.FAILED);
        assertThat(execution.getExitStatus().getExitCode())
                .as("failed job yields a non-COMPLETED exit code (drives RC 8)")
                .isEqualTo(ExitStatus.FAILED.getExitCode());
        assertThat(execution.getAllFailureExceptions())
                .as("the publish failure cause is recorded on the execution").isNotEmpty();
        assertThat(Files.isDirectory(backupOut))
                .as("the target was never partially overwritten").isTrue();

        // The in-progress temp now lives inside the private 0700 per-instance staging directory
        // (<parent>/.carddemo-inprogress-<id>/transact.bkp.<id>.inprogress) that P4-SEC-01 introduced,
        // so scan recursively rather than only the temp-dir root.
        try (var stream = Files.walk(tempDir)) {
            List<Path> temps = stream
                    .filter(p -> p.getFileName().toString().startsWith("transact.bkp.")
                            && p.getFileName().toString().endsWith(".inprogress"))
                    .toList();
            assertThat(temps)
                    .as("the .inprogress temp is left in place for a restart to re-publish")
                    .isNotEmpty();
        }
        assertThat(transactionRepository.count())
                .as("a failed backup mutates no data").isEqualTo(countBefore);
    }

    /**
     * Builds a fully populated {@link Transaction} with deterministic, known field values so that a
     * round-trip parse of its exported record can be asserted field-by-field.
     *
     * @param tranId the 16-character transaction id (primary key)
     * @param typeCd the 2-character transaction type code
     * @param catCd  the numeric transaction category code ({@code 9(04)})
     * @param amount the signed monetary amount as an exact decimal string ({@code S9(09)V99})
     * @return a populated, unsaved transaction
     */
    private static Transaction transaction(String tranId, String typeCd, int catCd, String amount) {
        Transaction tx = new Transaction();
        tx.setTranId(tranId);
        tx.setTranTypeCd(typeCd);
        tx.setTranCatCd(catCd);
        tx.setTranSource("POS");
        tx.setTranDesc("GROCERY PURCHASE");
        tx.setTranAmt(new BigDecimal(amount));
        tx.setMerchantId(123456789L);
        tx.setMerchantName("ACME FOODS");
        tx.setMerchantCity("SEATTLE");
        tx.setMerchantZip("98101");
        tx.setCardNum("4111111111111111");
        tx.setOrigTs(ORIG_TS);
        tx.setProcTs(PROC_TS);
        return tx;
    }

    /**
     * Launches {@code transactionBackupJob} writing to the given file, with a unique {@code run.id} so
     * every invocation is a fresh job instance.
     *
     * @param outputFile the backup output path bound to the required {@code outputPath} job parameter
     * @return the completed {@link JobExecution}
     * @throws Exception if the launcher fails to run the job
     */
    private JobExecution launchBackup(Path outputFile) throws Exception {
        JobParameters parameters = new JobParametersBuilder()
                .addString("outputPath", outputFile.toString())
                .addLong("run.id", System.nanoTime())
                .toJobParameters();
        return jobLauncherTestUtils.launchJob(parameters);
    }

    /**
     * Sums the item write count across all step executions of a job execution.
     *
     * @param execution the job execution to inspect
     * @return the total number of items written
     */
    private static long totalWriteCount(JobExecution execution) {
        return execution.getStepExecutions().stream()
                .mapToLong(StepExecution::getWriteCount)
                .sum();
    }

    /**
     * Reads an <em>undelimited</em> {@code RECFM=FB} backup file and slices it into fixed
     * {@value TransactionBackupJobConfig#RECORD_LENGTH}-byte records (review finding&nbsp;#17). The
     * migrated writer emits records back-to-back with no line delimiter (matching the native EBCDIC
     * {@code TRANSACT} image byte-for-byte; the LF-delimited ASCII fixtures are a convenience form only,
     * AAP&nbsp;&sect;0.6.6), so the file is parsed by fixed width rather than by newline. Each record is
     * decoded with the single-byte {@link StandardCharsets#ISO_8859_1} charset, so one 350-byte record
     * maps to exactly one 350-character string. The total file length is asserted to be a whole multiple
     * of the record width, which itself proves the framing carries no stray delimiter bytes.
     *
     * @param file the published backup file
     * @return the ordered list of 350-character records
     * @throws IOException if the file cannot be read
     */
    private static List<String> readRecords(Path file) throws IOException {
        byte[] all = Files.readAllBytes(file);
        int len = TransactionBackupJobConfig.RECORD_LENGTH;
        assertThat(all.length % len)
                .as("undelimited RECFM=FB file length is a whole multiple of the 350-byte record")
                .isZero();
        List<String> records = new ArrayList<>(all.length / len);
        for (int off = 0; off < all.length; off += len) {
            records.add(new String(all, off, len, StandardCharsets.ISO_8859_1));
        }
        return records;
    }
}
