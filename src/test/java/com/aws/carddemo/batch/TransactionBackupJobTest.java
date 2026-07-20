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

import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import com.aws.carddemo.common.util.FixedWidthCodec;
import com.aws.carddemo.domain.Transaction;
import com.aws.carddemo.repository.TransactionRepository;

/**
 * Spring Boot + Spring Batch Testcontainers integration test that proves the behavioral parity of
 * {@link TransactionBackupJob} &mdash; the Java re-platform of the mainframe IDCAMS transaction
 * backup job {@code legacy/jcl/TRANBKP.jcl} (proc {@code legacy/proc/REPROC.prc}, control member
 * {@code legacy/ctl/REPROCT.ctl}; transaction layout copybook {@code legacy/cpy/CVTRA05Y.cpy},
 * {@code TRAN-RECORD}, {@code RECLN = 350}).
 *
 * <h2>Parity contract under test</h2>
 * <p>On the mainframe, {@code TRANBKP} step {@code STEP05R} runs {@code REPRO INFILE(FILEIN)
 * OUTFILE(FILEOUT)} to copy the key-sequenced transaction master
 * ({@code AWS.M2.CARDDEMO.TRANSACT.VSAM.KSDS}) into a brand-new generation-data-group member
 * declared {@code DCB=(LRECL=350,RECFM=FB)}. The Java target reproduces exactly that
 * REPRO export: it streams every {@code transaction} row in ascending {@code tranId} order
 * (mirroring a REPRO of a KSDS, whose {@code KEYS(16 0)} places the 16-byte {@code TRAN-ID} at
 * offset 0) and serializes each row to a byte-exact 350-byte fixed-width record through
 * {@code batch/writer/TransactionBackupItemWriter} and {@code common/util/FixedWidthCodec}. The
 * five test methods assert:</p>
 * <ol>
 *   <li>the job is registered and the context wires it (bean {@code transactionBackupJob}, step
 *       {@code transactionBackupStep});</li>
 *   <li>a clean run reports {@link BatchStatus#COMPLETED} and the return-code-0 analog
 *       ({@link ExitStatus#COMPLETED});</li>
 *   <li>the backup file contains exactly one 350-character record per {@code transaction} row
 *       (line count equals {@link TransactionRepository#count()}), byte-exact at
 *       {@code count * 351} bytes (350 data bytes plus one {@code '\n'} delimiter per record);</li>
 *   <li>the records are emitted in ascending {@code tranId} order (the id decoded from positions
 *       {@code [0,16)} with {@link FixedWidthCodec}), reproducing the KSDS key order even though
 *       the fixtures are inserted out of order;</li>
 *   <li>the source {@code transaction} table is unchanged after the backup &mdash; the IDCAMS
 *       {@code DELETE}/{@code DEFINE} (drop &amp; recreate cluster) steps of {@code TRANBKP.jcl} are
 *       an intentional, documented deviation (decision log D14) and are deliberately NOT replicated,
 *       so this test never asserts on a delete.</li>
 * </ol>
 *
 * <h2>Harness</h2>
 * <ul>
 *   <li>{@link SpringBootTest} boots the full application context under the {@code test} profile so
 *       the real {@code transactionBackupJob} beans, the Spring Batch infrastructure, and Spring
 *       Data JPA are all present.</li>
 *   <li>{@link Testcontainers} with a single static {@code postgres:16-alpine}
 *       {@link PostgreSQLContainer} wired into Spring Boot through {@link ServiceConnection}, so no
 *       JDBC URL, username, or password is ever hardcoded (Technical Specification &sect;0.8.1 /
 *       &sect;0.9.3). Flyway ({@code V1__schema.sql} + {@code V2__reference_data.sql}) owns the
 *       schema and seeds the reference tables; Hibernate only validates the mappings.</li>
 *   <li>{@link SpringBatchTest} contributes the batch test utilities. Because the CardDemo context
 *       declares several {@link Job} beans, the auto-provided {@code jobLauncherTestUtils} cannot
 *       resolve a unique job (its job is wired through {@code ObjectProvider.ifUnique}, a no-op when
 *       more than one {@link Job} exists). This test therefore supplies its own
 *       {@link JobLauncherTestUtils} through the nested {@link BatchTestConfig}, bound explicitly to
 *       the {@code transactionBackupJob} bean.</li>
 *   <li>{@code spring.batch.job.enabled=false} (from {@code application-test.yml}) keeps the job
 *       from auto-running at startup; every run here is launched explicitly with unique parameters,
 *       and {@link JobRepositoryTestUtils#removeJobExecutions()} clears the batch metadata between
 *       tests.</li>
 *   <li>The backup output location is redirected to a per-class temporary directory via
 *       {@link DynamicPropertySource} (property {@code carddemo.batch.backup.directory}); the
 *       directory is cleared before each launch so the single produced file can be located
 *       deterministically.</li>
 * </ul>
 *
 * <p>Collaborators are injected through the constructor (the suite enables
 * {@code spring.test.constructor.autowire.mode=all}), consistent with the project-wide
 * constructor-injection convention. All monetary fixtures are {@link BigDecimal} at scale 2; binary
 * floating point is never used.</p>
 *
 * @see TransactionBackupJob
 * @see com.aws.carddemo.batch.writer.TransactionBackupItemWriter
 * @see FixedWidthCodec
 * @see <a href="http://www.apache.org/licenses/LICENSE-2.0">Apache License 2.0</a>
 */
@SpringBootTest
@ActiveProfiles("test")
@SpringBatchTest
@Testcontainers
class TransactionBackupJobTest {

    /**
     * Real PostgreSQL 16 engine shared by every test in this class. Declared {@code static} so the
     * {@link Testcontainers} extension starts it once before the Spring context is created;
     * {@link ServiceConnection} publishes its connection coordinates to Spring Boot so the
     * datasource is configured with no hardcoded credentials.
     */
    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>(DockerImageName.parse("postgres:16-alpine"));

    /**
     * Per-class temporary directory into which {@code TransactionBackupItemWriter} writes its
     * backup file. Created eagerly at class-load time (rather than through {@code @TempDir}) so the
     * value is guaranteed to be available when {@link #backupProperties(DynamicPropertyRegistry)}
     * runs during context preparation.
     */
    private static final Path BACKUP_DIR = createBackupDirectory();

    /** The 350-character fixed-width record length of {@code TRAN-RECORD} ({@code CVTRA05Y.cpy}). */
    private static final int RECORD_LENGTH = 350;

    /** Bytes per emitted line: the 350-byte record plus a single {@code '\n'} delimiter. */
    private static final long BYTES_PER_LINE = RECORD_LENGTH + 1L;

    /** Zero-based offset and width of {@code TRAN-ID PIC X(16)} within the fixed-width record. */
    private static final int TRAN_ID_OFFSET = 0;

    /** Width of {@code TRAN-ID PIC X(16)}. */
    private static final int TRAN_ID_LENGTH = 16;

    /**
     * Transaction ids for the known fixture set, intentionally listed <em>out of ascending order</em>
     * so that the ordering test genuinely exercises the reader's {@code tranId}-ascending sort (it
     * would fail if the export preserved insertion order instead of key order). Each id is a
     * zero-padded 16-character string, so lexical order equals numeric order.
     */
    private static final List<String> SEEDED_TRAN_IDS = List.of(
            "0000000000000030",
            "0000000000000010",
            "0000000000000050",
            "0000000000000020",
            "0000000000000040");

    /** The fixture ids in the ascending order the backup export must produce. */
    private static final List<String> SORTED_SEEDED_TRAN_IDS =
            SEEDED_TRAN_IDS.stream().sorted().toList();

    /** Owning account id for the fixture card ({@code ACCT-ID PIC 9(11)} &rarr; {@code BIGINT}). */
    private static final long TEST_ACCOUNT_ID = 90000000001L;

    /** Fixture card number ({@code CARD-NUM PIC X(16)}); parent of every seeded transaction. */
    private static final String TEST_CARD_NUM = "4000000000009999";

    /** Fixture disclosure-group id; present in the {@code V2} reference data (no DB foreign key). */
    private static final String TEST_GROUP_ID = "A000000000";

    /** Transaction type code reused from the {@code V2} reference seed ({@code transaction_type}). */
    private static final String TEST_TYPE_CD = "01";

    /**
     * Transaction category code paired with {@link #TEST_TYPE_CD}; the composite {@code ('01', 1)}
     * exists in the {@code V2} {@code transaction_category} seed, so the fixture transactions satisfy
     * the {@code (type_cd, cat_cd)} foreign key without inserting new reference rows.
     */
    private static final int TEST_CAT_CD = 1;

    /** Fixture merchant id ({@code TRAN-MERCHANT-ID PIC 9(09)} &rarr; {@code BIGINT}). */
    private static final long TEST_MERCHANT_ID = 123456789L;

    /** Fixture processing/origination timestamp text ({@code PIC X(26)}). */
    private static final String TEST_TIMESTAMP = "2024-01-01-10.00.00.000000";

    /**
     * Utilities bound to the {@code transactionBackupJob} bean (see {@link BatchTestConfig}). Used to
     * launch the job with unique parameters and to assert its wiring.
     */
    private final JobLauncherTestUtils jobLauncherTestUtils;

    /** Batch metadata helper used to clear job executions between relaunches. */
    private final JobRepositoryTestUtils jobRepositoryTestUtils;

    /** The running application context, used to assert the job/step beans are registered. */
    private final ApplicationContext applicationContext;

    /** Repository for the {@code transaction} master (the data source of the backup). */
    private final TransactionRepository transactionRepository;

    /**
     * JDBC template used to seed and remove the parent {@code account} and {@code card} rows the
     * transaction foreign keys require. Native SQL keeps this test's internal dependencies limited to
     * the transaction domain type and its repository (the only transaction-tier collaborators it
     * declares a dependency on); the two parent rows carry no fields the backup exercises.
     */
    private final JdbcTemplate jdbcTemplate;

    /**
     * Constructor injection of the Spring-managed collaborators. Only field assignments are performed
     * (no overridable method is invoked), so no reference to a partially-constructed instance escapes.
     *
     * @param jobLauncherTestUtils   the job launcher utilities bound to {@code transactionBackupJob}
     * @param jobRepositoryTestUtils the batch metadata helper for inter-test cleanup
     * @param applicationContext     the running application context
     * @param transactionRepository  the transaction master repository (backup source)
     * @param jdbcTemplate           the JDBC template used to seed the parent account/card rows
     */
    TransactionBackupJobTest(JobLauncherTestUtils jobLauncherTestUtils,
                             JobRepositoryTestUtils jobRepositoryTestUtils,
                             ApplicationContext applicationContext,
                             TransactionRepository transactionRepository,
                             JdbcTemplate jdbcTemplate) {
        this.jobLauncherTestUtils = jobLauncherTestUtils;
        this.jobRepositoryTestUtils = jobRepositoryTestUtils;
        this.applicationContext = applicationContext;
        this.transactionRepository = transactionRepository;
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * Registers the writer's configurable output directory as the per-class temporary directory.
     *
     * <p>{@code TransactionBackupItemWriter} resolves its output location from
     * {@code carddemo.batch.backup.directory} (default {@code ./target/backup}); overriding it here
     * keeps every backup file inside a disposable temporary directory that the test controls, with no
     * hardcoded path.</p>
     *
     * @param registry the Spring test property registry to populate
     */
    @DynamicPropertySource
    static void backupProperties(DynamicPropertyRegistry registry) {
        registry.add("carddemo.batch.backup.directory", BACKUP_DIR::toString);
    }

    /**
     * Creates the per-class temporary backup directory.
     *
     * @return the created temporary directory
     * @throws UncheckedIOException if the directory cannot be created
     */
    private static Path createBackupDirectory() {
        try {
            return Files.createTempDirectory("carddemo-transaction-backup-test-");
        } catch (IOException ex) {
            throw new UncheckedIOException("Unable to create temporary backup directory", ex);
        }
    }

    /**
     * Removes the temporary backup directory (and any files it still holds) after all tests.
     *
     * @throws IOException if the directory contents cannot be removed
     */
    @AfterAll
    static void removeBackupDirectory() throws IOException {
        clearBackupDirectory();
        Files.deleteIfExists(BACKUP_DIR);
    }

    /**
     * Seeds a known, non-empty set of transactions before each test so the width and ordering
     * assertions are meaningful. Under the {@code test} profile the {@code transaction} table is
     * otherwise empty (Flyway seeds only the reference tables; the bulk CSV seed is
     * {@code local}-profile only), so the parent {@code account} and {@code card} rows are inserted
     * first (via native SQL) to satisfy the {@code card_num} &rarr; {@code card} &rarr;
     * {@code account} foreign keys, then the transactions are inserted through
     * {@link TransactionRepository} in shuffled id order reusing the {@code V2} reference codes.
     *
     * <p>The test class is not transactional, so each repository save commits immediately and is
     * visible to the batch job's reader (which runs in its own transaction).</p>
     */
    @BeforeEach
    void seedKnownTransactionSet() {
        cleanBusinessData();

        // Parent account: only acct_id is mandatory (monetary columns default to 0.00 and the
        // JPA @Version column defaults to 0); the backup job never reads the account, so a minimal
        // row is sufficient to satisfy the card -> account foreign key.
        jdbcTemplate.update(
                "INSERT INTO account (acct_id, acct_active_status, group_id) VALUES (?, ?, ?)",
                TEST_ACCOUNT_ID, "Y", TEST_GROUP_ID);

        // Parent card: card_num and acct_id are mandatory; the remaining columns are populated with
        // representative values. Satisfies the transaction -> card foreign key.
        jdbcTemplate.update(
                "INSERT INTO card (card_num, acct_id, cvv, card_embossed_name, "
                        + "card_expiration_date, card_active_status) VALUES (?, ?, ?, ?, ?, ?)",
                TEST_CARD_NUM, TEST_ACCOUNT_ID, "123", "TEST CARDHOLDER", "2030-12-31", "Y");

        List<Transaction> fixtures = SEEDED_TRAN_IDS.stream()
                .map(TransactionBackupJobTest::newBackupTransaction)
                .toList();
        transactionRepository.saveAll(fixtures);
    }

    /**
     * Clears the batch job-execution metadata (so subsequent relaunches start clean) and removes the
     * seeded business data after each test.
     */
    @AfterEach
    void tidyUp() {
        jobRepositoryTestUtils.removeJobExecutions();
        cleanBusinessData();
    }

    /**
     * The context wires the backup job. Confirms the {@code transactionBackupJob} {@link Job} bean
     * and the {@code transactionBackupStep} {@link Step} bean are registered under their expected
     * names, and that the test's {@link JobLauncherTestUtils} is bound to the correct job (proving the
     * multi-job resolution in {@link BatchTestConfig} succeeded).
     */
    @Test
    @DisplayName("context loads and the transactionBackupJob/Step beans are registered")
    void contextLoadsAndJobRegistered() {
        assertThat(applicationContext).isNotNull();

        assertThat(applicationContext.containsBean("transactionBackupJob")).isTrue();
        Job job = applicationContext.getBean("transactionBackupJob", Job.class);
        assertThat(job.getName()).isEqualTo("transactionBackupJob");

        assertThat(applicationContext.containsBean("transactionBackupStep")).isTrue();
        assertThat(applicationContext.getBean("transactionBackupStep", Step.class)).isNotNull();

        assertThat(jobLauncherTestUtils.getJob()).isNotNull();
        assertThat(jobLauncherTestUtils.getJob().getName()).isEqualTo("transactionBackupJob");
    }

    /**
     * A clean run completes successfully. Asserts {@link BatchStatus#COMPLETED} and the return-code-0
     * analog ({@link ExitStatus#COMPLETED}), mirroring an IDCAMS REPRO that ends with {@code MAXCC=0}.
     *
     * @throws Exception if the job launch fails
     */
    @Test
    @DisplayName("runs clean with return code 0 (COMPLETED)")
    void runsCleanWithReturnCodeZero() throws Exception {
        JobExecution execution = launchBackupJob();

        assertThat(execution.getStatus()).isEqualTo(BatchStatus.COMPLETED);
        assertThat(execution.getExitStatus().getExitCode())
                .isEqualTo(ExitStatus.COMPLETED.getExitCode());
    }

    /**
     * The backup contains every transaction row, each a byte-exact 350-byte fixed-width record.
     * Asserts the produced file's line count equals {@link TransactionRepository#count()}, that each
     * line is exactly {@value #RECORD_LENGTH} characters wide, and that the total file size equals
     * {@code count * 351} bytes (each 350-byte record followed by a single {@code '\n'}). Because the
     * file is ISO-8859-1 (one char per byte), the character-width and byte-width checks together
     * prove the {@code LRECL=350 RECFM=FB} contract.
     *
     * @throws Exception if the job launch or file inspection fails
     */
    @Test
    @DisplayName("backup contains every transaction row at 350 bytes each")
    void backupContainsEveryTransactionRow() throws Exception {
        JobExecution execution = launchBackupJob();
        assertThat(execution.getStatus()).isEqualTo(BatchStatus.COMPLETED);

        Path backupFile = locateSingleBackupFile();
        List<String> lines = Files.readAllLines(backupFile, StandardCharsets.ISO_8859_1);

        long rowCount = transactionRepository.count();
        assertThat(rowCount).as("the seeded transaction set must be non-empty").isPositive();

        assertThat((long) lines.size())
                .as("one backup record per transaction row")
                .isEqualTo(rowCount);
        assertThat(lines)
                .as("every backup record is a 350-character fixed-width image")
                .allSatisfy(line -> assertThat(line).hasSize(RECORD_LENGTH));
        assertThat(Files.size(backupFile))
                .as("byte-exact LRECL=350 RECFM=FB: each record is 350 bytes plus one '\\n'")
                .isEqualTo(rowCount * BYTES_PER_LINE);
    }

    /**
     * The backup rows are ordered ascending by {@code tranId}, reproducing the key-sequenced order an
     * IDCAMS REPRO of {@code TRANSACT.VSAM.KSDS} ({@code KEYS(16 0)}) would emit. The 16-character id
     * is decoded from positions {@code [0,16)} of each record with {@link FixedWidthCodec}. Because the
     * fixtures were inserted out of order, {@code isSorted()} together with the exact
     * {@link #SORTED_SEEDED_TRAN_IDS} match is a genuine ordering check. When a golden fixture
     * {@code golden/transaction-backup.txt} is present, the produced rows are additionally asserted
     * equal to it row-for-row without trimming.
     *
     * @throws Exception if the job launch or file inspection fails
     */
    @Test
    @DisplayName("backup is ordered by tranId ascending")
    void backupOrderedByTranIdAscending() throws Exception {
        JobExecution execution = launchBackupJob();
        assertThat(execution.getStatus()).isEqualTo(BatchStatus.COMPLETED);

        Path backupFile = locateSingleBackupFile();
        List<String> lines = Files.readAllLines(backupFile, StandardCharsets.ISO_8859_1);

        List<String> tranIds = lines.stream()
                .map(line -> FixedWidthCodec.readAlphanumeric(line, TRAN_ID_OFFSET, TRAN_ID_LENGTH))
                .toList();

        assertThat(tranIds)
                .as("backup must contain exactly the seeded rows")
                .hasSize(SEEDED_TRAN_IDS.size());
        assertThat(tranIds)
                .as("a REPRO of a KSDS emits records in ascending key order")
                .isSorted();
        assertThat(tranIds)
                .as("records appear in ascending tranId order despite shuffled insertion")
                .containsExactlyElementsOf(SORTED_SEEDED_TRAN_IDS);

        // Optional golden-file row-for-row parity: only asserted when the fixture is present on the
        // classpath (absent by default for the in-test seed). Comparison is exact, without trimming.
        var goldenUrl = getClass().getResource("/golden/transaction-backup.txt");
        if (goldenUrl != null) {
            List<String> expected =
                    Files.readAllLines(Path.of(goldenUrl.toURI()), StandardCharsets.ISO_8859_1);
            assertThat(lines)
                    .as("row-for-row parity with the golden backup fixture (no trimming)")
                    .containsExactlyElementsOf(expected);
        }
    }

    /**
     * The source {@code transaction} table is unchanged after the backup. The IDCAMS
     * {@code DELETE CLUSTER}/{@code DEFINE CLUSTER} steps of {@code TRANBKP.jcl} are an intentional,
     * documented deviation (decision log D14): a relational backup export never drops and recreates
     * the table, so the row count before and after the run is identical.
     *
     * @throws Exception if the job launch fails
     */
    @Test
    @DisplayName("source table is unchanged after backup (DELETE/DEFINE not replicated)")
    void sourceTableUnchangedAfterBackup() throws Exception {
        long countBefore = transactionRepository.count();
        assertThat(countBefore).as("precondition: seeded rows are present").isPositive();

        JobExecution execution = launchBackupJob();
        assertThat(execution.getStatus()).isEqualTo(BatchStatus.COMPLETED);

        long countAfter = transactionRepository.count();
        assertThat(countAfter)
                .as("backup is copy-only; the KSDS DELETE/DEFINE steps are a documented, "
                        + "unreplicated deviation, so the source table is unchanged")
                .isEqualTo(countBefore);
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    /**
     * Clears the backup directory and launches the {@code transactionBackupJob} with unique job
     * parameters, so each launch is a fresh {@code JobInstance} and produces exactly one output file.
     *
     * @return the resulting {@link JobExecution}
     * @throws Exception if the launch fails
     */
    private JobExecution launchBackupJob() throws Exception {
        clearBackupDirectory();
        return jobLauncherTestUtils.launchJob(jobLauncherTestUtils.getUniqueJobParameters());
    }

    /**
     * Locates the single backup file produced by the most recent launch. The directory is cleared
     * before every launch, so exactly one file is expected afterwards.
     *
     * @return the produced backup file
     * @throws IOException if the directory cannot be listed
     */
    private static Path locateSingleBackupFile() throws IOException {
        List<Path> files = listBackupFiles();
        assertThat(files)
                .as("exactly one backup file should be produced per run")
                .hasSize(1);
        return files.get(0);
    }

    /**
     * Deletes every file currently in the backup directory (the writer emits flat files only, so no
     * recursive walk is needed).
     *
     * @throws IOException if a file cannot be listed or deleted
     */
    private static void clearBackupDirectory() throws IOException {
        if (!Files.exists(BACKUP_DIR)) {
            return;
        }
        for (Path file : listBackupFiles()) {
            Files.deleteIfExists(file);
        }
    }

    /**
     * Returns the regular files currently in the backup directory.
     *
     * @return the list of files (never {@code null})
     * @throws IOException if the directory cannot be listed
     */
    private static List<Path> listBackupFiles() throws IOException {
        try (Stream<Path> stream = Files.list(BACKUP_DIR)) {
            return stream.filter(Files::isRegularFile).toList();
        }
    }

    /**
     * Removes the seeded transactions and their parent card/account rows in foreign-key-safe order.
     * The reference tables seeded by Flyway ({@code transaction_type}, {@code transaction_category},
     * {@code disclosure_group}) are left untouched.
     */
    private void cleanBusinessData() {
        transactionRepository.deleteAll();
        jdbcTemplate.update("DELETE FROM card WHERE card_num = ?", TEST_CARD_NUM);
        jdbcTemplate.update("DELETE FROM account WHERE acct_id = ?", TEST_ACCOUNT_ID);
    }

    /**
     * Builds an unpersisted {@link Transaction} fixture for the given id in the copybook field order
     * of {@code CVTRA05Y.cpy}, reusing the {@code V2} reference type/category codes and the fixture
     * card. The monetary amount uses the {@link BigDecimal} string constructor to preserve scale 2.
     *
     * @param tranId the 16-character transaction id
     * @return a new, unpersisted transaction
     */
    private static Transaction newBackupTransaction(String tranId) {
        return new Transaction(
                tranId,
                TEST_TYPE_CD,
                TEST_CAT_CD,
                "POS",
                "BACKUP PARITY TEST",
                new BigDecimal("100.00"),
                TEST_MERCHANT_ID,
                "TEST MERCHANT",
                "TEST CITY",
                "12345",
                TEST_CARD_NUM,
                TEST_TIMESTAMP,
                TEST_TIMESTAMP);
    }

    /**
     * Supplies a {@link JobLauncherTestUtils} bound explicitly to the {@code transactionBackupJob}
     * bean.
     *
     * <p>The CardDemo context declares several {@link Job} beans, so the {@link SpringBatchTest}
     * auto-provided {@code jobLauncherTestUtils} leaves its job unset (its wiring uses
     * {@code ObjectProvider.ifUnique}, which is a no-op when multiple jobs exist). This bean is given
     * a distinct name and marked {@link Primary} so it is the one injected by type into the test,
     * with its job resolved by the {@code transactionBackupJob} qualifier.</p>
     */
    @TestConfiguration
    static class BatchTestConfig {

        /**
         * Creates the primary {@link JobLauncherTestUtils} wired to the backup job.
         *
         * @param jobLauncher          the auto-configured Spring Batch job launcher
         * @param jobRepository        the auto-configured Spring Batch job repository
         * @param transactionBackupJob the backup job bean, resolved by qualifier
         * @return the configured, primary job launcher utilities
         */
        @Bean
        @Primary
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
}
