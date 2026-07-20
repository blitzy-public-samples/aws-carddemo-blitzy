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
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.ExitStatus;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.JobExecution;
import org.springframework.batch.core.JobParameters;
import org.springframework.batch.core.Step;
import org.springframework.batch.core.StepExecution;
import org.springframework.batch.core.launch.JobLauncher;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.item.ExecutionContext;
import org.springframework.batch.test.JobLauncherTestUtils;
import org.springframework.batch.test.JobRepositoryTestUtils;
import org.springframework.batch.test.MetaDataInstanceFactory;
import org.springframework.batch.test.context.SpringBatchTest;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.core.io.FileSystemResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import com.aws.carddemo.batch.reader.CombinedTransactionItemReader;
import com.aws.carddemo.common.util.FixedWidthCodec;
import com.aws.carddemo.common.util.FixedWidthCodec.FieldDef;
import com.aws.carddemo.domain.Transaction;
import com.aws.carddemo.repository.TransactionRepository;

/**
 * Spring Boot + Spring Batch Testcontainers integration test that proves the behavioral parity of
 * {@link TransactionCombineJob} &mdash; the Java re-platform of the mainframe SORT/combine job
 * {@code legacy/jcl/COMBTRAN.jcl} (source {@code app/jcl/COMBTRAN.jcl}; transaction layout copybook
 * {@code legacy/cpy/CVTRA05Y.cpy}, {@code TRAN-RECORD}, {@code RECLN = 350}).
 *
 * <h2>Parity contract under test (COMBTRAN.jcl)</h2>
 * <ol>
 *   <li><strong>{@code STEP05R} ({@code PGM=SORT}).</strong> {@code SORTIN} is the DD concatenation
 *       of {@code AWS.M2.CARDDEMO.TRANSACT.BKUP(0)} <em>followed by</em>
 *       {@code AWS.M2.CARDDEMO.SYSTRAN(0)} &mdash; the transaction <em>backup</em> dataset first, the
 *       <em>system-generated</em> transactions second. {@code SYMNAMES} declares
 *       {@code TRAN-ID,1,16,CH} and {@code SYSIN} declares {@code SORT FIELDS=(TRAN-ID,A)}: the sort
 *       key is the 16-character {@code TRAN-ID} at record position&nbsp;1, ascending. The Java target
 *       reproduces this with {@link CombinedTransactionItemReader}, which reads the backup source
 *       before the system source and emits every record ordered by {@link Transaction#getTranId()}
 *       ascending (a <em>stable</em> sort, so equal keys keep their backup-before-system order).</li>
 *   <li><strong>{@code STEP10} ({@code PGM=IDCAMS}).</strong> {@code REPRO INFILE(TRANSACT)
 *       OUTFILE(TRANVSAM)} loads the sorted combined file into the transaction master KSDS. The Java
 *       target reproduces this with {@code batch/writer/TransactionJpaItemWriter}, which inserts each
 *       row into the relational {@code transaction} table keyed on {@code tran_id}.</li>
 * </ol>
 *
 * <h2>How each parity claim is asserted</h2>
 * <ul>
 *   <li>The two inputs are materialised as byte-exact 350-character fixed-width files (built with the
 *       same {@code CVTRA05Y.cpy} offsets and {@link FixedWidthCodec} the production writer uses) in a
 *       per-test {@link TempDir temporary directory}, and their locations are passed to the job as the
 *       {@code backupResource} and {@code systemResource} job parameters &mdash; exactly the late-bound
 *       parameters {@link CombinedTransactionItemReader} resolves. No path or credential is hardcoded.</li>
 *   <li>The <em>combine</em> claim is checked against the {@code transaction} table (every input record
 *       is loaded exactly once).</li>
 *   <li>The <em>{@code SORT FIELDS=(TRAN-ID,A)}</em> claim is checked at the reader &mdash; the
 *       component that, by the job's own contract, owns the sort key &mdash; by driving it over the
 *       same deliberately <em>unsorted</em> inputs and asserting the emitted stream is strictly
 *       ascending by {@code tranId}. (The relational load target is a set, so a plain {@code SELECT}
 *       cannot observe row order; the reader's emitted stream is the direct analog of the mainframe
 *       {@code SORTOUT}.)</li>
 *   <li>The <em>stable, backup-first</em> tie-break for equal keys is likewise checked at the reader,
 *       because the REPRO load ({@code TransactionJpaItemWriter}, no-{@code REPLACE} insert semantics)
 *       rejects a duplicate {@code tran_id} rather than loading it, so equal keys cannot be observed
 *       through the table.</li>
 * </ul>
 *
 * <h2>Harness</h2>
 * <ul>
 *   <li>{@link SpringBootTest} boots the full application context under the {@code test} profile so the
 *       real {@code transactionCombineJob} beans, the Spring Batch infrastructure, and Spring Data JPA
 *       are all present.</li>
 *   <li>{@link Testcontainers} with a single static {@code postgres:16-alpine}
 *       {@link PostgreSQLContainer} wired into Spring Boot through {@link ServiceConnection}, so no JDBC
 *       URL, username, or password is ever hardcoded (Technical Specification &sect;0.8.1 / &sect;0.9.3).
 *       Flyway ({@code V1__schema.sql} + {@code V2__reference_data.sql}) owns the schema and seeds the
 *       reference tables; Hibernate only validates the mappings.</li>
 *   <li>{@link SpringBatchTest} contributes the batch test utilities. Because the CardDemo context
 *       declares several {@link Job} beans, the auto-provided {@code jobLauncherTestUtils} cannot
 *       resolve a unique job; this test therefore supplies its own {@link JobLauncherTestUtils} through
 *       the nested {@link BatchTestConfig}, bound explicitly to the {@code transactionCombineJob}
 *       bean.</li>
 *   <li>{@code spring.batch.job.enabled=false} (from {@code application-test.yml}) keeps the job from
 *       auto-running at startup; every run here is launched explicitly with unique parameters, and
 *       {@link JobRepositoryTestUtils#removeJobExecutions()} clears the batch metadata between
 *       tests.</li>
 * </ul>
 *
 * <p>Collaborators are injected through the constructor (the suite enables
 * {@code spring.test.constructor.autowire.mode=all}), consistent with the project-wide
 * constructor-injection convention. All monetary fixtures are {@link BigDecimal} at scale 2; binary
 * floating point is never used.</p>
 *
 * @see TransactionCombineJob
 * @see CombinedTransactionItemReader
 * @see com.aws.carddemo.batch.writer.TransactionJpaItemWriter
 * @see FixedWidthCodec
 * @see <a href="http://www.apache.org/licenses/LICENSE-2.0">Apache License 2.0</a>
 */
@SpringBootTest
@ActiveProfiles("test")
@SpringBatchTest
@Testcontainers
class TransactionCombineJobTest {

    /**
     * Real PostgreSQL 16 engine shared by every test in this class. Declared {@code static} so the
     * {@link Testcontainers} extension starts it once before the Spring context is created;
     * {@link ServiceConnection} publishes its connection coordinates to Spring Boot so the datasource
     * is configured with no hardcoded credentials.
     */
    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>(DockerImageName.parse("postgres:16-alpine"));

    /** The 350-character fixed-width record length of {@code TRAN-RECORD} ({@code CVTRA05Y.cpy}). */
    private static final int RECORD_LENGTH = 350;

    /** Record delimiter used when materialising the fixed-width input files (deterministic newline). */
    private static final String RECORD_DELIMITER = "\n";

    // ------------------------------------------------------------------------
    // 350-byte TRAN-RECORD field descriptors (copybook CVTRA05Y.cpy). Offsets are zero-based and
    // contiguous; identical to the offsets TransactionBackupItemWriter uses, so a record built here
    // round-trips exactly through CombinedTransactionItemReader.
    // ------------------------------------------------------------------------

    /** {@code TRAN-ID PIC X(16)} at offset 0 &mdash; the sort/primary key. */
    private static final FieldDef TRAN_ID = FieldDef.alphanumeric("TRAN-ID", 0, 16);

    /** {@code TRAN-TYPE-CD PIC X(02)} at offset 16. */
    private static final FieldDef TRAN_TYPE_CD = FieldDef.alphanumeric("TRAN-TYPE-CD", 16, 2);

    /** {@code TRAN-CAT-CD PIC 9(04)} at offset 18. */
    private static final FieldDef TRAN_CAT_CD = FieldDef.numeric("TRAN-CAT-CD", 18, 4);

    /** {@code TRAN-SOURCE PIC X(10)} at offset 22. */
    private static final FieldDef TRAN_SOURCE = FieldDef.alphanumeric("TRAN-SOURCE", 22, 10);

    /** {@code TRAN-DESC PIC X(100)} at offset 32. */
    private static final FieldDef TRAN_DESC = FieldDef.alphanumeric("TRAN-DESC", 32, 100);

    /** {@code TRAN-AMT PIC S9(09)V99} at offset 132 (length 11, scale 2, overpunch-signed). */
    private static final FieldDef TRAN_AMT = FieldDef.signedDecimal("TRAN-AMT", 132, 11, 2);

    /** {@code TRAN-MERCHANT-ID PIC 9(09)} at offset 143. */
    private static final FieldDef TRAN_MERCHANT_ID = FieldDef.numeric("TRAN-MERCHANT-ID", 143, 9);

    /** {@code TRAN-MERCHANT-NAME PIC X(50)} at offset 152. */
    private static final FieldDef TRAN_MERCHANT_NAME = FieldDef.alphanumeric("TRAN-MERCHANT-NAME", 152, 50);

    /** {@code TRAN-MERCHANT-CITY PIC X(50)} at offset 202. */
    private static final FieldDef TRAN_MERCHANT_CITY = FieldDef.alphanumeric("TRAN-MERCHANT-CITY", 202, 50);

    /** {@code TRAN-MERCHANT-ZIP PIC X(10)} at offset 252. */
    private static final FieldDef TRAN_MERCHANT_ZIP = FieldDef.alphanumeric("TRAN-MERCHANT-ZIP", 252, 10);

    /** {@code TRAN-CARD-NUM PIC X(16)} at offset 262. */
    private static final FieldDef TRAN_CARD_NUM = FieldDef.alphanumeric("TRAN-CARD-NUM", 262, 16);

    /** {@code TRAN-ORIG-TS PIC X(26)} at offset 278. */
    private static final FieldDef TRAN_ORIG_TS = FieldDef.alphanumeric("TRAN-ORIG-TS", 278, 26);

    /** {@code TRAN-PROC-TS PIC X(26)} at offset 304. */
    private static final FieldDef TRAN_PROC_TS = FieldDef.alphanumeric("TRAN-PROC-TS", 304, 26);

    // ------------------------------------------------------------------------
    // Fixture reference data (reuses the V2 reference seed so the transaction foreign keys are
    // satisfied without inserting new reference rows).
    // ------------------------------------------------------------------------

    /** Owning account id for the fixture card ({@code ACCT-ID PIC 9(11)} &rarr; {@code BIGINT}). */
    private static final long TEST_ACCOUNT_ID = 90000000001L;

    /** Fixture card number ({@code CARD-NUM PIC X(16)}); parent of every combined transaction. */
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

    /** Default fixture amount ({@link BigDecimal} scale 2); binary floating point is never used. */
    private static final BigDecimal TEST_AMOUNT = new BigDecimal("100.00");

    /**
     * Per-test temporary directory into which the {@code backupResource} and {@code systemResource}
     * fixed-width input files are written. JUnit creates and removes it around each test method, so
     * no input path is ever hardcoded and no fixture leaks between tests.
     */
    @TempDir
    Path inputDir;

    /**
     * Utilities bound to the {@code transactionCombineJob} bean (see {@link BatchTestConfig}). Used to
     * launch the job with unique parameters and to assert its wiring.
     */
    private final JobLauncherTestUtils jobLauncherTestUtils;

    /** Batch metadata helper used to clear job executions between relaunches. */
    private final JobRepositoryTestUtils jobRepositoryTestUtils;

    /** The running application context, used to assert the job/step beans are registered. */
    private final ApplicationContext applicationContext;

    /** Repository for the {@code transaction} master (the REPRO-load target of the combine job). */
    private final TransactionRepository transactionRepository;

    /**
     * JDBC template used to seed and remove the parent {@code account} and {@code card} rows the
     * transaction foreign keys require. Native SQL keeps this test's dependencies limited to the
     * transaction domain type and its repository; the two parent rows carry no field the combine job
     * exercises.
     */
    private final JdbcTemplate jdbcTemplate;

    /**
     * Constructor injection of the Spring-managed collaborators. Only field assignments are performed
     * (no overridable method is invoked and no reference to {@code this} escapes), so a
     * partially-constructed instance is never published.
     *
     * @param jobLauncherTestUtils   the job launcher utilities bound to {@code transactionCombineJob}
     * @param jobRepositoryTestUtils the batch metadata helper for inter-test cleanup
     * @param applicationContext     the running application context
     * @param transactionRepository  the transaction master repository (combine-load target)
     * @param jdbcTemplate           the JDBC template used to seed the parent account/card rows
     */
    TransactionCombineJobTest(JobLauncherTestUtils jobLauncherTestUtils,
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
     * Prepares a clean, foreign-key-satisfying baseline before each test.
     *
     * <p>Under the {@code test} profile the {@code transaction} table is empty (Flyway seeds only the
     * reference tables; the bulk CSV seed is {@code local}-profile only), so the parent
     * {@code account} and {@code card} rows are inserted first (via native SQL) to satisfy the
     * {@code card_num} &rarr; {@code card} &rarr; {@code account} foreign keys. The combine job then
     * populates the transactions from the input files, so &mdash; unlike a read-side test &mdash; no
     * transaction rows are pre-seeded here. Any residue from a prior test is removed first.</p>
     *
     * <p>The test class is not transactional, so each write commits immediately and is visible to the
     * batch job (which runs in its own transaction).</p>
     */
    @BeforeEach
    void seedForeignKeyParents() {
        cleanBusinessData();

        // Parent account: only acct_id is mandatory (monetary columns default to 0.00, the JPA
        // @Version column defaults to 0). The combine job never reads the account, so a minimal row
        // is sufficient to satisfy the card -> account foreign key.
        jdbcTemplate.update(
                "INSERT INTO account (acct_id, acct_active_status, group_id) VALUES (?, ?, ?)",
                TEST_ACCOUNT_ID, "Y", TEST_GROUP_ID);

        // Parent card: card_num and acct_id are mandatory; the remaining columns carry representative
        // values. Satisfies the transaction -> card foreign key for every combined record.
        jdbcTemplate.update(
                "INSERT INTO card (card_num, acct_id, cvv, card_embossed_name, "
                        + "card_expiration_date, card_active_status) VALUES (?, ?, ?, ?, ?, ?)",
                TEST_CARD_NUM, TEST_ACCOUNT_ID, "123", "TEST CARDHOLDER", "2030-12-31", "Y");
    }

    /**
     * Clears the batch job-execution metadata (so subsequent relaunches start clean) and removes the
     * loaded transactions and their parent card/account rows after each test.
     */
    @AfterEach
    void tidyUp() {
        jobRepositoryTestUtils.removeJobExecutions();
        cleanBusinessData();
    }

    // -------------------------------------------------------------------------
    // Tests
    // -------------------------------------------------------------------------

    /**
     * The context wires the combine job. Confirms the {@code transactionCombineJob} {@link Job} bean
     * and the {@code transactionCombineStep} {@link Step} bean are registered under their expected
     * names, and that the test's {@link JobLauncherTestUtils} is bound to the correct job (proving the
     * multi-job resolution in {@link BatchTestConfig} succeeded).
     */
    @Test
    @DisplayName("context loads and the transactionCombineJob/Step beans are registered")
    void contextLoadsAndJobRegistered() {
        assertThat(applicationContext).isNotNull();

        assertThat(applicationContext.containsBean("transactionCombineJob")).isTrue();
        Job job = applicationContext.getBean("transactionCombineJob", Job.class);
        assertThat(job.getName()).isEqualTo("transactionCombineJob");

        assertThat(applicationContext.containsBean("transactionCombineStep")).isTrue();
        assertThat(applicationContext.getBean("transactionCombineStep", Step.class)).isNotNull();

        assertThat(jobLauncherTestUtils.getJob()).isNotNull();
        assertThat(jobLauncherTestUtils.getJob().getName()).isEqualTo("transactionCombineJob");
    }

    /**
     * A clean run completes successfully. Arranges a small backup and system input, launches the job,
     * and asserts {@link BatchStatus#COMPLETED} and the return-code-0 analog
     * ({@link ExitStatus#COMPLETED}), mirroring the {@code MAXCC=0} of the mainframe
     * {@code SORT}/{@code IDCAMS} steps.
     *
     * @throws Exception if writing the inputs or launching the job fails
     */
    @Test
    @DisplayName("runs clean with return code 0 (COMPLETED)")
    void runsCleanWithReturnCodeZero() throws Exception {
        Path backup = writeFixedWidthFile("backup.dat",
                List.of(newTransaction("0000000000000010", "BKUP", TEST_AMOUNT)));
        Path system = writeFixedWidthFile("system.dat",
                List.of(newTransaction("0000000000000020", "SYS", TEST_AMOUNT)));

        JobExecution execution = launchCombineJob(backup, system);

        assertThat(execution.getStatus()).isEqualTo(BatchStatus.COMPLETED);
        assertThat(execution.getExitStatus().getExitCode())
                .isEqualTo(ExitStatus.COMPLETED.getExitCode());
    }

    /**
     * The job concatenates the backup source and the system source and loads every record exactly
     * once. The two inputs carry <em>distinct but interleaved</em> transaction ids (so a correct merge
     * is required, not a simple append of one range after another), and each file lists its ids out of
     * order. After the run, the {@code transaction} table must hold exactly the union of both inputs:
     * the row count equals the sum of the input sizes and every input id is present, with its
     * monetary amount preserved at {@link BigDecimal} scale 2.
     *
     * @throws Exception if writing the inputs or launching the job fails
     */
    @Test
    @DisplayName("combines the backup and system sources, loading every record exactly once")
    void combinesBackupThenSystemSources() throws Exception {
        List<String> backupIds = List.of(
                "0000000000000030", "0000000000000010", "0000000000000050");
        List<String> systemIds = List.of(
                "0000000000000040", "0000000000000020", "0000000000000060");

        Path backup = writeFixedWidthFile("backup.dat", transactionsFor(backupIds, "BKUP"));
        Path system = writeFixedWidthFile("system.dat", transactionsFor(systemIds, "SYS"));

        JobExecution execution = launchCombineJob(backup, system);
        assertThat(execution.getStatus()).isEqualTo(BatchStatus.COMPLETED);

        assertThat(transactionRepository.count())
                .as("every backup and system record is loaded exactly once (no loss, no duplication)")
                .isEqualTo(backupIds.size() + systemIds.size());

        List<String> allIds = new ArrayList<>(backupIds);
        allIds.addAll(systemIds);
        assertThat(allIds)
                .allSatisfy(id -> assertThat(transactionRepository.findById(id))
                        .as("combined record %s must be present in the transaction master", id)
                        .isPresent());

        Transaction loaded = transactionRepository.findById("0000000000000030").orElseThrow();
        assertThat(loaded.getCardNum()).isEqualTo(TEST_CARD_NUM);
        assertThat(loaded.getTypeCd()).isEqualTo(TEST_TYPE_CD);
        assertThat(loaded.getCatCd()).isEqualTo(TEST_CAT_CD);
        assertThat(loaded.getTranAmt())
                .as("monetary amount preserved as BigDecimal at scale 2")
                .isEqualByComparingTo(TEST_AMOUNT);
        assertThat(loaded.getTranAmt().scale()).isEqualTo(2);
    }

    /**
     * The combined output is ordered strictly ascending by {@code tranId}, reproducing
     * {@code SORT FIELDS=(TRAN-ID,A)}. The inputs are deliberately <em>unsorted</em> (and interleaved
     * across the two sources), so the assertion genuinely exercises the sort rather than an incidental
     * insertion order.
     *
     * <p>The job is launched to prove the end-to-end run loads the full set. Row order, however, is not
     * observable through the relational load target (a {@code SELECT} without {@code ORDER BY} returns a
     * set), so the ordering itself is asserted at {@link CombinedTransactionItemReader} &mdash; the
     * component that, per the job's contract, owns {@code SORT FIELDS=(TRAN-ID,A)}. Driving the reader
     * over the very same input files yields the combined stream (the analog of the mainframe
     * {@code SORTOUT}); its emitted {@code tranId}s must be strictly ascending and equal to the sorted
     * union of both inputs. When a golden fixture {@code golden/combined-transactions.txt} is present on
     * the classpath, the emitted records (re-serialised to their 350-character images) are additionally
     * asserted equal to it row-for-row, without trimming.
     *
     * @throws Exception if writing the inputs, launching the job, or driving the reader fails
     */
    @Test
    @DisplayName("combined output is sorted by tranId ascending (SORT FIELDS=(TRAN-ID,A))")
    void outputIsSortedByTranIdAscending() throws Exception {
        List<String> backupIds = List.of(
                "0000000000000007", "0000000000000003", "0000000000000009");
        List<String> systemIds = List.of(
                "0000000000000005", "0000000000000001", "0000000000000008");

        List<Transaction> backupTx = transactionsFor(backupIds, "BKUP");
        List<Transaction> systemTx = transactionsFor(systemIds, "SYS");
        Path backup = writeFixedWidthFile("backup.dat", backupTx);
        Path system = writeFixedWidthFile("system.dat", systemTx);

        // End-to-end: the full combined set is loaded into the master.
        JobExecution execution = launchCombineJob(backup, system);
        assertThat(execution.getStatus()).isEqualTo(BatchStatus.COMPLETED);

        List<String> expectedAscending = new ArrayList<>(backupIds);
        expectedAscending.addAll(systemIds);
        expectedAscending = expectedAscending.stream().sorted().toList();

        assertThat(transactionRepository.count()).isEqualTo(expectedAscending.size());
        assertThat(loadedTranIdsSorted())
                .as("the master must hold exactly the combined set")
                .containsExactlyElementsOf(expectedAscending);

        // Sort proof: drive the reader (the SORT owner) over the same deliberately-unsorted inputs.
        List<Transaction> emitted = readCombinedStream(backup, system);
        List<String> emittedIds = emitted.stream().map(Transaction::getTranId).toList();

        assertThat(emittedIds)
                .as("SORT FIELDS=(TRAN-ID,A): the combined stream is strictly ascending by tranId")
                .isSorted();
        assertThat(emittedIds)
                .as("the sort must reorder the deliberately-unsorted, interleaved inputs")
                .containsExactlyElementsOf(expectedAscending);

        // Optional golden-file row-for-row parity: only asserted when the fixture is present on the
        // classpath (absent by default). Comparison is exact against the 350-character record images.
        var goldenUrl = getClass().getResource("/golden/combined-transactions.txt");
        if (goldenUrl != null) {
            List<String> expectedLines =
                    Files.readAllLines(Path.of(goldenUrl.toURI()), StandardCharsets.ISO_8859_1);
            List<String> emittedLines = emitted.stream().map(this::toFixedWidthRecord).toList();
            assertThat(emittedLines)
                    .as("row-for-row parity with the golden combined fixture (no trimming)")
                    .containsExactlyElementsOf(expectedLines);
        }
    }

    /**
     * Equal {@code tranId}s keep their backup-before-system order (a <em>stable</em> sort). The
     * mainframe {@code SORT} is invoked without the {@code EQUALS} option, which leaves the relative
     * order of equal-key records formally unspecified; {@link CombinedTransactionItemReader} resolves
     * this deterministically by concatenating the backup source before the system source and sorting
     * stably, so a record present in both sources surfaces from the backup first.
     *
     * <p>This tie-break is asserted at the reader rather than through the {@code transaction} table:
     * the REPRO load ({@code TransactionJpaItemWriter}) inserts on {@code tran_id} with no
     * {@code REPLACE}, so the second record of an equal-key pair is <em>rejected</em> (the
     * {@code IDC1440I} skip) rather than loaded as a second row &mdash; equal keys therefore cannot be
     * observed as two rows through the table (the run merely ends {@code COMPLETED_WITH_REJECTS}). The
     * two records sharing a key are distinguished here by their {@code TRAN-SOURCE} field.</p>
     *
     * @throws Exception if writing the inputs or driving the reader fails
     */
    @Test
    @DisplayName("stable for equal keys: a shared tranId surfaces from the backup source first")
    void stableForEqualKeys() throws Exception {
        String sharedId = "0000000000000002";

        // Backup listed out of order (0002 before 0001) so the stable sort is genuinely exercised.
        List<Transaction> backupTx = List.of(
                newTransaction(sharedId, "BKUP", TEST_AMOUNT),
                newTransaction("0000000000000001", "BKUP", TEST_AMOUNT));
        List<Transaction> systemTx = List.of(
                newTransaction(sharedId, "SYS", TEST_AMOUNT),
                newTransaction("0000000000000003", "SYS", TEST_AMOUNT));

        Path backup = writeFixedWidthFile("backup.dat", backupTx);
        Path system = writeFixedWidthFile("system.dat", systemTx);

        List<Transaction> emitted = readCombinedStream(backup, system);
        List<String> emittedIds = emitted.stream().map(Transaction::getTranId).toList();

        assertThat(emittedIds)
                .as("the combined stream is ascending by tranId, with equal keys adjacent")
                .isSorted();
        assertThat(emittedIds)
                .containsExactly("0000000000000001", sharedId, sharedId, "0000000000000003");

        List<String> sharedSources = emitted.stream()
                .filter(tx -> sharedId.equals(tx.getTranId()))
                .map(Transaction::getTranSource)
                .toList();
        assertThat(sharedSources)
                .as("stable, backup-first tie-break: the backup record precedes the system record")
                .containsExactly("BKUP", "SYS");
    }

    /**
     * A pre-existing "stale" master row that is <em>not</em> re-supplied by the combined input
     * survives the load, and the run completes clean (return code {@code 0}).
     *
     * <p>This reproduces IDCAMS {@code REPRO} without {@code REPLACE} into an existing KSDS opened
     * {@code DISP=SHR}: {@code STEP10} performs no {@code DELETE}/{@code DEFINE}, so a record already in
     * the master that is absent from {@code SORTIN} is never removed. The combine job is a merge-insert,
     * not a rebuild &mdash; stale-row survival is the faithful legacy behavior (see the COMBTRAN
     * decision-log entry), not a defect. Because no input record collides, nothing is rejected and the
     * return code is {@code 0}.</p>
     *
     * @throws Exception if writing the inputs or launching the job fails
     */
    @Test
    @DisplayName("REPRO merge-insert: a pre-existing stale row survives; disjoint inputs load, RC 0")
    void staleRowSurvivesWithReturnCodeZero() throws Exception {
        // Pre-seed a stale master row whose id appears in NEITHER input.
        String staleId = "0000000000000999";
        transactionRepository.save(newTransaction(staleId, "SEED", new BigDecimal("555.55")));

        Path backup = writeFixedWidthFile("backup.dat",
                List.of(newTransaction("0000000000000030", "BKUP", TEST_AMOUNT)));
        Path system = writeFixedWidthFile("system.dat",
                List.of(newTransaction("0000000000000040", "SYS", TEST_AMOUNT)));

        JobExecution execution = launchCombineJob(backup, system);

        assertThat(execution.getStatus()).isEqualTo(BatchStatus.COMPLETED);
        assertThat(execution.getExitStatus().getExitCode())
                .as("no duplicate was rejected, so the run is clean (RC 0)")
                .isEqualTo(ExitStatus.COMPLETED.getExitCode());

        assertThat(loadedTranIdsSorted())
                .as("the two disjoint inputs are loaded AND the stale row survives (no rebuild)")
                .containsExactly("0000000000000030", "0000000000000040", staleId);
        assertThat(transactionRepository.findById(staleId).orElseThrow().getTranAmt())
                .as("the stale row is untouched (its amount is unchanged)")
                .isEqualByComparingTo(new BigDecimal("555.55"));
    }

    /**
     * A combined record whose {@code tranId} already exists in the master is rejected (the
     * {@code IDC1440I} skip) &mdash; the existing row is left untouched, never overwritten &mdash;
     * while every non-duplicate record is still loaded; the run ends {@code COMPLETED_WITH_REJECTS}
     * (return code {@code 4}).
     *
     * <p>This is the core REPRO-without-{@code REPLACE} contract: a duplicate key does not fail the
     * step (contrast the pre-fix {@code saveAll} behavior, which failed the whole step on the first
     * collision), and it does not overwrite the existing row.</p>
     *
     * @throws Exception if seeding, writing the inputs, or launching the job fails
     */
    @Test
    @DisplayName("REPRO reject: an input row already in the master is skipped (RC 4); the rest load")
    void duplicateInTableIsRejectedAndRestLoadedWithReturnCodeFour() throws Exception {
        // Pre-seed the master with id 0030 at a DISTINCT amount so we can prove it is not overwritten.
        String dupId = "0000000000000030";
        transactionRepository.save(newTransaction(dupId, "SEED", new BigDecimal("777.77")));

        // Backup carries the colliding 0030 (at a different amount) plus a fresh 0031; system a fresh 0040.
        Path backup = writeFixedWidthFile("backup.dat", List.of(
                newTransaction(dupId, "BKUP", new BigDecimal("100.00")),
                newTransaction("0000000000000031", "BKUP", TEST_AMOUNT)));
        Path system = writeFixedWidthFile("system.dat",
                List.of(newTransaction("0000000000000040", "SYS", TEST_AMOUNT)));

        JobExecution execution = launchCombineJob(backup, system);

        assertThat(execution.getStatus())
                .as("a rejected duplicate must NOT fail the step (reject-and-continue)")
                .isEqualTo(BatchStatus.COMPLETED);
        assertThat(execution.getExitStatus().getExitCode())
                .as("one record was rejected, so the return code is 4")
                .isEqualTo("COMPLETED_WITH_REJECTS");

        assertThat(loadedTranIdsSorted())
                .as("the duplicate is skipped, the two fresh records load, the seed survives")
                .containsExactly(dupId, "0000000000000031", "0000000000000040");
        assertThat(transactionRepository.findById(dupId).orElseThrow().getTranAmt())
                .as("the existing row is untouched (not overwritten by the combined duplicate)")
                .isEqualByComparingTo(new BigDecimal("777.77"));
    }

    /**
     * When the same {@code tranId} appears twice <em>within the same combined input</em> (once in the
     * backup source and once in the system source), the first occurrence (backup, by the reader's
     * stable backup-first order) is loaded and the second is rejected; the run ends
     * {@code COMPLETED_WITH_REJECTS} (return code {@code 4}).
     *
     * <p>This proves the record-at-a-time commit ({@code CHUNK_SIZE == 1}) makes an intra-input
     * duplicate detectable: the second occurrence's {@code existsById} probe sees the first, already
     * committed, occurrence &mdash; exactly as a record-at-a-time {@code REPRO} would.</p>
     *
     * @throws Exception if writing the inputs or launching the job fails
     */
    @Test
    @DisplayName("REPRO reject: an intra-input duplicate is skipped on its second occurrence (RC 4)")
    void duplicateWithinInputIsRejectedWithReturnCodeFour() throws Exception {
        // 0030 appears in BOTH sources at distinct amounts; the reader emits backup 0030 before system 0030.
        String dupId = "0000000000000030";
        Path backup = writeFixedWidthFile("backup.dat", List.of(
                newTransaction(dupId, "BKUP", new BigDecimal("111.11")),
                newTransaction("0000000000000031", "BKUP", TEST_AMOUNT)));
        Path system = writeFixedWidthFile("system.dat", List.of(
                newTransaction(dupId, "SYS", new BigDecimal("222.22")),
                newTransaction("0000000000000041", "SYS", TEST_AMOUNT)));

        JobExecution execution = launchCombineJob(backup, system);

        assertThat(execution.getStatus()).isEqualTo(BatchStatus.COMPLETED);
        assertThat(execution.getExitStatus().getExitCode())
                .as("the second occurrence of the shared id is rejected, so the return code is 4")
                .isEqualTo("COMPLETED_WITH_REJECTS");

        assertThat(loadedTranIdsSorted())
                .as("the shared id is loaded exactly once; the two unique records also load")
                .containsExactly(dupId, "0000000000000031", "0000000000000041");
        assertThat(transactionRepository.findById(dupId).orElseThrow().getTranAmt())
                .as("backup-first wins: the backup occurrence is loaded, the system occurrence rejected")
                .isEqualByComparingTo(new BigDecimal("111.11"));
    }

    /**
     * A contiguous fixed-block input &mdash; two 350-byte {@code TRAN-RECORD} images written
     * back-to-back with <strong>no</strong> line delimiter, the true {@code RECFM=FB} contract
     * &mdash; loads <em>every</em> record. The pre-fix line-oriented reader saw such a file as a
     * single 700-character "line", decoded only the first record, and silently dropped the second
     * (QA finding F3). Framing by position now yields both. The assertion is made both at the reader
     * (the {@code SORTOUT} analog, which must emit all three records) and end-to-end through the load.
     *
     * @throws Exception if writing the inputs, driving the reader, or launching the job fails
     */
    @Test
    @DisplayName("F3: a contiguous fixed-block input (no delimiters) loads EVERY record, not just the first")
    void combinesContiguousFixedBlockInputLoadingEveryRecord() throws Exception {
        // backup.dat: TWO 350-byte records back-to-back with NO delimiter (contiguous RECFM=FB).
        Path backup = writeContiguousFixedWidthFile("backup.dat", List.of(
                newTransaction("0000000000000010", "BKUP", TEST_AMOUNT),
                newTransaction("0000000000000020", "BKUP", TEST_AMOUNT)));
        // system.dat: one record, also written contiguously (a single record is contiguous by definition).
        Path system = writeContiguousFixedWidthFile("system.dat", List.of(
                newTransaction("0000000000000030", "SYS", TEST_AMOUNT)));

        // Reader-level proof (SORTOUT): all three records are emitted, not just the first of the
        // contiguous backup file.
        assertThat(readCombinedStream(backup, system))
                .as("the reader emits every contiguous record (both backup records + the system record)")
                .hasSize(3);

        JobExecution execution = launchCombineJob(backup, system);

        assertThat(execution.getStatus()).isEqualTo(BatchStatus.COMPLETED);
        assertThat(execution.getExitStatus().getExitCode())
                .isEqualTo(ExitStatus.COMPLETED.getExitCode());
        assertThat(loadedTranIdsSorted())
                .as("all three records load end-to-end (no contiguous record is dropped)")
                .containsExactly("0000000000000010", "0000000000000020", "0000000000000030");
    }

    /**
     * When <em>neither</em> {@code SORTIN} member resolves to an existing dataset, the job
     * <strong>fails</strong> rather than completing a silent no-op with return code 0 (QA finding
     * F6): a missing {@code SORTIN} is an operator error the mainframe {@code SORT} would abend on,
     * not an empty combine.
     *
     * @throws Exception if launching the job fails
     */
    @Test
    @DisplayName("F6: the job FAILS (not a silent RC-0 no-op) when NEITHER SORTIN input resolves")
    void failsWhenNoInputResolves() throws Exception {
        Path missingBackup = nonexistentFile("missing-backup.dat");
        Path missingSystem = nonexistentFile("missing-system.dat");

        JobExecution execution = launchCombineJob(missingBackup, missingSystem);

        assertThat(execution.getStatus())
                .as("a missing SORTIN must fail the step, not complete a no-op with RC 0")
                .isEqualTo(BatchStatus.FAILED);
        assertThat(transactionRepository.count())
                .as("nothing is loaded when the combine input is missing")
                .isZero();
    }

    /**
     * A supplied-but-missing input is skipped (and logged at {@code WARN}); as long as at least one
     * input resolves, the present dataset still loads and the run completes cleanly (QA finding F6:
     * single-input flexibility is preserved, but a missing input is no longer invisible).
     *
     * @throws Exception if writing the input or launching the job fails
     */
    @Test
    @DisplayName("F6: a supplied-but-missing input is skipped; the present input still loads (RC 0)")
    void skipsMissingInputAndLoadsThePresentOne() throws Exception {
        Path missingBackup = nonexistentFile("missing-backup.dat");
        Path system = writeFixedWidthFile("system.dat", List.of(
                newTransaction("0000000000000040", "SYS", TEST_AMOUNT),
                newTransaction("0000000000000050", "SYS", TEST_AMOUNT)));

        JobExecution execution = launchCombineJob(missingBackup, system);

        assertThat(execution.getStatus())
                .as("one resolving input is enough: the present system input loads, RC 0")
                .isEqualTo(BatchStatus.COMPLETED);
        assertThat(loadedTranIdsSorted())
                .containsExactly("0000000000000040", "0000000000000050");
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    /**
     * Scope-context factory consumed by {@code @SpringBatchTest}'s {@code JobScopeTestExecutionListener}.
     *
     * <p>{@code @SpringBatchTest} registers job- and step-scope {@code TestExecutionListener}s that, during
     * test-instance preparation, scan this class for a method returning a {@link JobExecution} (respectively a
     * {@link StepExecution}) to seed the synchronization context used by {@code @JobScope} / {@code @StepScope}
     * beans. The listener selects the matching method by return type &mdash; always preferring one named exactly
     * {@code getJobExecution} &mdash; then invokes it with <em>no arguments</em>. Because the real launch helper
     * {@link #launchCombineJob(Path, Path)} also returns a {@link JobExecution} but requires two {@link Path}
     * arguments, this explicit no-argument factory guarantees the listener binds to it instead of trying (and
     * failing) to invoke the parameterized launcher. These tests launch the real job explicitly through
     * {@link JobLauncherTestUtils} and never rely on this throwaway scope context.</p>
     *
     * @return a throwaway {@link JobExecution} used only to satisfy the job-scope test listener
     */
    private JobExecution getJobExecution() {
        return MetaDataInstanceFactory.createJobExecution();
    }

    /**
     * Scope-context factory consumed by {@code @SpringBatchTest}'s {@code StepScopeTestExecutionListener},
     * mirroring {@link #getJobExecution()}. Provided for symmetry and future-proofing so the step-scope listener
     * always binds to this safe no-argument factory rather than to any {@link StepExecution}-returning method
     * that might later be added to this class.
     *
     * @return a throwaway {@link StepExecution} used only to satisfy the step-scope test listener
     */
    private StepExecution getStepExecution() {
        return MetaDataInstanceFactory.createStepExecution();
    }

    /**
     * Launches {@code transactionCombineJob} with the two input files bound to the late-bound
     * {@code backupResource} and {@code systemResource} job parameters (as {@code file:} URLs, exactly
     * how {@link CombinedTransactionItemReader} resolves them), plus a unique {@code run.id} so every
     * launch is a fresh {@code JobInstance}.
     *
     * @param backup the backup transaction input ({@code SORTIN} member 1)
     * @param system the system-generated transaction input ({@code SORTIN} member 2)
     * @return the resulting {@link JobExecution}
     * @throws Exception if the launch fails
     */
    private JobExecution launchCombineJob(Path backup, Path system) throws Exception {
        JobParameters parameters = jobLauncherTestUtils.getUniqueJobParametersBuilder()
                .addString("backupResource", fileUrl(backup))
                .addString("systemResource", fileUrl(system))
                .addString("run.id", UUID.randomUUID().toString())
                .toJobParameters();
        return jobLauncherTestUtils.launchJob(parameters);
    }

    /**
     * Drives {@link CombinedTransactionItemReader} directly over the two input files and collects the
     * combined, sorted stream it emits &mdash; the analog of the mainframe {@code SORTOUT}.
     *
     * <p>The reader is a {@code @StepScope @Component} in production (its inputs are late-bound job
     * parameters); constructing it directly with the two {@link FileSystemResource}s bypasses only the
     * Spring scoping proxy, exercising the same {@code open}/{@code read}/{@code close} logic and the
     * same stable {@code tranId}-ascending sort the batch step uses.</p>
     *
     * @param backup the backup transaction input ({@code SORTIN} member 1)
     * @param system the system-generated transaction input ({@code SORTIN} member 2)
     * @return the emitted transactions, in the reader's output order
     * @throws Exception if the reader cannot open or read an input
     */
    private List<Transaction> readCombinedStream(Path backup, Path system) throws Exception {
        CombinedTransactionItemReader reader = new CombinedTransactionItemReader(
                new FileSystemResource(backup), new FileSystemResource(system));
        List<Transaction> emitted = new ArrayList<>();
        reader.open(new ExecutionContext());
        try {
            Transaction transaction;
            while ((transaction = reader.read()) != null) {
                emitted.add(transaction);
            }
        } finally {
            reader.close();
        }
        return emitted;
    }

    /**
     * Returns the {@code tranId}s currently in the {@code transaction} master, ascending. Used only to
     * assert set membership of the combined load (the relational target does not preserve insertion
     * order, so this is a completeness check, not an order-of-load check).
     *
     * @return the loaded transaction ids in ascending order
     */
    private List<String> loadedTranIdsSorted() {
        return transactionRepository.findAll().stream()
                .map(Transaction::getTranId)
                .sorted()
                .toList();
    }

    /**
     * Writes the given transactions to a byte-exact fixed-width file in the per-test temporary
     * directory: one 350-character {@code TRAN-RECORD} image per line, encoded as ISO-8859-1 (so the
     * zoned-decimal overpunch bytes survive) and delimited with a single {@code '\n'} &mdash; the
     * form {@link CombinedTransactionItemReader} reads.
     *
     * @param fileName     the file name to create inside {@link #inputDir}
     * @param transactions the records to serialise, in the order given (the file's on-disk order)
     * @return the path of the written file
     * @throws IOException if the file cannot be written
     */
    private Path writeFixedWidthFile(String fileName, List<Transaction> transactions)
            throws IOException {
        StringBuilder content = new StringBuilder(transactions.size() * (RECORD_LENGTH + 1));
        for (Transaction transaction : transactions) {
            content.append(toFixedWidthRecord(transaction)).append(RECORD_DELIMITER);
        }
        Path file = inputDir.resolve(fileName);
        Files.writeString(file, content.toString(), StandardCharsets.ISO_8859_1);
        return file;
    }

    /**
     * Writes the given transactions to a byte-exact <em>contiguous</em> fixed-width file in the
     * per-test temporary directory: the 350-character {@code TRAN-RECORD} images are concatenated
     * back-to-back with <strong>no</strong> delimiter, reproducing the true {@code RECFM=FB} layout
     * (fixed blocks, no in-band newline). This is the input form that exposed QA finding F3, where a
     * line-oriented read would collapse the file into a single over-length "line".
     *
     * @param fileName     the file name to create inside {@link #inputDir}
     * @param transactions the records to serialise, in the order given (the file's on-disk order)
     * @return the path of the written file
     * @throws IOException if the file cannot be written
     */
    private Path writeContiguousFixedWidthFile(String fileName, List<Transaction> transactions)
            throws IOException {
        StringBuilder content = new StringBuilder(transactions.size() * RECORD_LENGTH);
        for (Transaction transaction : transactions) {
            content.append(toFixedWidthRecord(transaction)); // NO delimiter -> contiguous fixed blocks
        }
        Path file = inputDir.resolve(fileName);
        Files.writeString(file, content.toString(), StandardCharsets.ISO_8859_1);
        return file;
    }

    /**
     * Resolves a path inside {@link #inputDir} that is deliberately <em>never written</em>, so the
     * reader's {@code Resource.exists()} probe returns {@code false}. Used to exercise the
     * missing-{@code SORTIN} handling (QA finding F6).
     *
     * @param fileName the file name to reference (but not create) inside {@link #inputDir}
     * @return a path to a non-existent file
     */
    private Path nonexistentFile(String fileName) {
        return inputDir.resolve(fileName);
    }

    /**
     * Serialises a {@link Transaction} to its 350-character {@code TRAN-RECORD} image using the
     * {@code CVTRA05Y.cpy} field offsets and {@link FixedWidthCodec}. The trailing
     * {@code FILLER PIC X(20)} at offset 330 is left as the builder's default spaces.
     *
     * @param transaction the record to serialise
     * @return the 350-character fixed-width image
     */
    private String toFixedWidthRecord(Transaction transaction) {
        return FixedWidthCodec.of(RECORD_LENGTH)
                .put(TRAN_ID, transaction.getTranId())
                .put(TRAN_TYPE_CD, transaction.getTypeCd())
                .put(TRAN_CAT_CD, transaction.getCatCd().longValue())
                .put(TRAN_SOURCE, transaction.getTranSource())
                .put(TRAN_DESC, transaction.getTranDesc())
                .put(TRAN_AMT, transaction.getTranAmt())
                .put(TRAN_MERCHANT_ID, transaction.getTranMerchantId().longValue())
                .put(TRAN_MERCHANT_NAME, transaction.getTranMerchantName())
                .put(TRAN_MERCHANT_CITY, transaction.getTranMerchantCity())
                .put(TRAN_MERCHANT_ZIP, transaction.getTranMerchantZip())
                .put(TRAN_CARD_NUM, transaction.getCardNum())
                .put(TRAN_ORIG_TS, transaction.getOrigTs())
                .put(TRAN_PROC_TS, transaction.getProcTs())
                .build();
    }

    /**
     * Builds an unpersisted {@link Transaction} for the given id and source in the copybook field
     * order of {@code CVTRA05Y.cpy}, reusing the {@code V2} reference type/category codes and the
     * fixture card so the foreign keys are satisfied. The monetary amount is a {@link BigDecimal} at
     * scale 2.
     *
     * @param tranId the 16-character transaction id
     * @param source the {@code TRAN-SOURCE} value (used to distinguish backup vs system records)
     * @param amount the signed monetary amount at scale 2
     * @return a new, unpersisted transaction
     */
    private static Transaction newTransaction(String tranId, String source, BigDecimal amount) {
        return new Transaction(
                tranId,
                TEST_TYPE_CD,
                TEST_CAT_CD,
                source,
                "COMBINE PARITY TEST",
                amount,
                TEST_MERCHANT_ID,
                "TEST MERCHANT",
                "TEST CITY",
                "12345",
                TEST_CARD_NUM,
                TEST_TIMESTAMP,
                TEST_TIMESTAMP);
    }

    /**
     * Builds a list of fixture transactions for the given ids, all carrying the same source and the
     * default fixture amount.
     *
     * @param tranIds the transaction ids, in the order they should appear in the file
     * @param source  the {@code TRAN-SOURCE} value for every record
     * @return the fixture transactions in the given order
     */
    private static List<Transaction> transactionsFor(List<String> tranIds, String source) {
        return tranIds.stream()
                .map(id -> newTransaction(id, source, TEST_AMOUNT))
                .toList();
    }

    /**
     * Renders a filesystem path as a {@code file:} URL string for a job parameter. The reader converts
     * this to a {@code Resource} on late binding; a {@code file:} URL is unambiguous across
     * environments.
     *
     * @param path the file to reference
     * @return the {@code file:}-prefixed absolute location
     */
    private static String fileUrl(Path path) {
        return "file:" + path.toAbsolutePath();
    }

    /**
     * Removes the loaded transactions and their parent card/account rows in foreign-key-safe order.
     * The reference tables seeded by Flyway ({@code transaction_type}, {@code transaction_category},
     * {@code disclosure_group}) are left untouched.
     */
    private void cleanBusinessData() {
        transactionRepository.deleteAll();
        jdbcTemplate.update("DELETE FROM card WHERE card_num = ?", TEST_CARD_NUM);
        jdbcTemplate.update("DELETE FROM account WHERE acct_id = ?", TEST_ACCOUNT_ID);
    }

    /**
     * Supplies a {@link JobLauncherTestUtils} bound explicitly to the {@code transactionCombineJob}
     * bean.
     *
     * <p>The CardDemo context declares several {@link Job} beans, so the {@link SpringBatchTest}
     * auto-provided {@code jobLauncherTestUtils} leaves its job unset (its wiring uses
     * {@code ObjectProvider.ifUnique}, which is a no-op when multiple jobs exist). This bean is given a
     * distinct name and marked {@link Primary} so it is the one injected by type into the test, with
     * its job resolved by the {@code transactionCombineJob} qualifier.</p>
     */
    @TestConfiguration
    static class BatchTestConfig {

        /**
         * Creates the primary {@link JobLauncherTestUtils} wired to the combine job.
         *
         * @param jobLauncher           the auto-configured Spring Batch job launcher
         * @param jobRepository         the auto-configured Spring Batch job repository
         * @param transactionCombineJob the combine job bean, resolved by qualifier
         * @return the configured, primary job launcher utilities
         */
        @Bean
        @Primary
        JobLauncherTestUtils transactionCombineJobLauncherTestUtils(
                JobLauncher jobLauncher,
                JobRepository jobRepository,
                @Qualifier("transactionCombineJob") Job transactionCombineJob) {
            JobLauncherTestUtils utils = new JobLauncherTestUtils();
            utils.setJobLauncher(jobLauncher);
            utils.setJobRepository(jobRepository);
            utils.setJob(transactionCombineJob);
            return utils;
        }
    }
}
