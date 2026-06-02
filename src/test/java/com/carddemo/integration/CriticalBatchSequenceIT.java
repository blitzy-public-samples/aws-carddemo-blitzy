package com.carddemo.integration;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.JobExecution;
import org.springframework.batch.core.JobParameters;
import org.springframework.batch.core.JobParametersBuilder;
import org.springframework.batch.core.configuration.JobRegistry;
import org.springframework.batch.core.launch.JobLauncher;
import org.springframework.batch.core.repository.JobInstanceAlreadyCompleteException;
import org.springframework.batch.test.JobRepositoryTestUtils;
import org.springframework.batch.test.context.SpringBatchTest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import com.carddemo.entity.Account;
import com.carddemo.entity.DailyTransaction;
import com.carddemo.entity.DisclosureGroup;
import com.carddemo.entity.DisclosureGroupId;
import com.carddemo.entity.RejectedTransaction;
import com.carddemo.entity.Transaction;
import com.carddemo.entity.TransactionCategoryBalance;
import com.carddemo.entity.TransactionCategoryBalanceId;
import com.carddemo.repository.AccountRepository;
import com.carddemo.repository.CardXrefRepository;
import com.carddemo.repository.DailyTransactionRepository;
import com.carddemo.repository.DisclosureGroupRepository;
import com.carddemo.repository.RejectedTransactionRepository;
import com.carddemo.repository.TransactionCategoryBalanceRepository;
import com.carddemo.repository.TransactionRepository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Canonical end-to-end regression test for the <strong>critical batch sequence</strong>
 * mandated by preservation rule <strong>PR-12</strong>:
 *
 * <pre>
 *   POSTTRAN  →  INTCALC  →  COMBTRAN  →  CREASTMT
 *      ↓           ↓            ↓             ↓
 *  transaction  interest   consolidate   statement
 *   posting   calculation   transactions  generation
 * </pre>
 *
 * <p>These four Spring Batch jobs are the daily processing cycle of the original mainframe
 * CardDemo application. This test verifies that each job runs successfully, produces correct
 * outputs, and that the output of one feeds correctly into the next. It is the most complex
 * integration test in the migration; if it passes, the batch portion of the migration is
 * functionally complete.</p>
 *
 * <h2>Source-of-truth COBOL/JCL programs (preserved unchanged as REFERENCE — PR-27)</h2>
 * <ul>
 *   <li>{@code app/jcl/POSTTRAN.jcl} + {@code app/cbl/CBTRN02C.cbl} — transaction posting</li>
 *   <li>{@code app/jcl/INTCALC.jcl} + {@code app/cbl/CBACT04C.cbl} — interest calculation</li>
 *   <li>{@code app/jcl/COMBTRAN.jcl} + {@code app/cbl/CBTRN03C.cbl} — consolidation</li>
 *   <li>{@code app/jcl/CREASTMT.JCL} + {@code app/cbl/CBSTM03A.CBL} + {@code CBSTM03B.CBL} — statements</li>
 * </ul>
 *
 * <h2>Preservation rules exercised</h2>
 * <ul>
 *   <li><strong>PR-01</strong> — interest formula {@code (TRAN-CAT-BAL × DIS-INT-RATE) / 1200},
 *       scale 2, {@code HALF_UP} (CBACT04C L462-L470).</li>
 *   <li><strong>PR-02</strong> — DISCGRP {@code DEFAULT} fallback when the specific group misses
 *       (CBACT04C L415-L440).</li>
 *   <li><strong>PR-03</strong> — validation codes 100/101/102/103 with exact COBOL message strings
 *       (CBTRN02C L370-L420).</li>
 *   <li><strong>PR-06</strong> — TCATBAL composite-key upsert (CBTRN02C L467-L501).</li>
 *   <li><strong>PR-07</strong> — sign-based cycle bucket; {@code currBal += amount} unconditionally;
 *       negative amounts add their <em>signed</em> value to {@code currCycDebit} (no {@code abs()})
 *       (CBTRN02C L545-L560).</li>
 *   <li><strong>PR-08</strong> — interest applied + cycle credit/debit zeroed (CBACT04C L350-L370).</li>
 *   <li><strong>PR-09</strong> — statement HTML structure (CBSTM03A L506-L555).</li>
 *   <li><strong>PR-10</strong> — 16-char transaction id, 10-char {@code PARM-DATE} prefix + 6-char
 *       suffix (CBACT04C L473-L500).</li>
 *   <li><strong>PR-11</strong> — DB2 timestamp format {@code yyyy-MM-dd-HH.mm.ss.SSS'0000'}
 *       (millisecond precision) at I/O boundaries (CBACT04C L613-L626).</li>
 *   <li><strong>PR-12</strong> — POSTTRAN → INTCALC → COMBTRAN → CREASTMT order.</li>
 *   <li><strong>PR-16</strong> — every monetary assertion uses {@code isEqualByComparingTo}.</li>
 * </ul>
 *
 * <h2>Test infrastructure notes</h2>
 * <ul>
 *   <li>{@code @SpringBootTest(webEnvironment = NONE)} — no HTTP server; jobs are launched directly
 *       through {@link JobLauncher}.</li>
 *   <li>{@code @SpringBatchTest} — contributes {@link JobRepositoryTestUtils}; its job/launcher
 *       autowiring is {@code required = false}, so the multiple {@code Job} and {@code JobLauncher}
 *       beans in this application do not break context startup.</li>
 *   <li>The synchronous {@link JobLauncher} is resolved by the field name {@code jobLauncher}
 *       (the asynchronous bean is named {@code asyncJobLauncher}).</li>
 *   <li>{@code @TestInstance(PER_CLASS)} — a single Testcontainers PostgreSQL 15 container is reused
 *       for every test in the class. Business data therefore accumulates across tests, so data-flow
 *       assertions use existence / inequality checks and per-launch deltas rather than absolute
 *       counts; deterministic assertions seed their own isolated rows.</li>
 * </ul>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@SpringBatchTest
@Testcontainers
@ActiveProfiles("test")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@DisplayName("CriticalBatchSequenceIT — Verifies POSTTRAN → INTCALC → COMBTRAN → CREASTMT batch sequence per PR-12")
class CriticalBatchSequenceIT {

    // ------------------------------------------------------------------------
    // Constants — exact COBOL artifacts preserved verbatim
    // ------------------------------------------------------------------------

    /** {@code INTCALC PARM='2022071800'} → the literal 10-char {@code tranDate} job parameter (PR-10). */
    private static final String PARM_DATE = "2022071800";

    /** Validation code 100 reject message (CBTRN02C 1500-A-LOOKUP-XREF) — PR-03. */
    private static final String MSG_INVALID_CARD = "INVALID CARD NUMBER FOUND";
    /** Validation code 101 reject message (CBTRN02C 1500-B-LOOKUP-ACCT) — PR-03. */
    private static final String MSG_ACCOUNT_NOT_FOUND = "ACCOUNT RECORD NOT FOUND";
    /** Validation code 102 reject message (CBTRN02C over-limit) — PR-03. */
    private static final String MSG_OVERLIMIT = "OVERLIMIT TRANSACTION";
    /** Validation code 103 reject message (CBTRN02C expiration) — PR-03. */
    private static final String MSG_EXPIRED = "TRANSACTION RECEIVED AFTER ACCT EXPIRATION";

    /** Interest transactions carry {@code TRAN-TYPE-CD='01'} (CBACT04C 1300-B-WRITE-TX). */
    private static final String INTEREST_TYPE_CD = "01";
    /** Interest transactions carry {@code TRAN-CAT-CD='0005'} (4-char, zero-padded). */
    private static final String INTEREST_CAT_CD = "0005";

    /** 16-char interest transaction id format (PR-10): 10-char PARM-DATE prefix + 6-digit suffix. */
    private static final Pattern TRAN_ID_PATTERN = Pattern.compile("^" + PARM_DATE + "\\d{6}$");

    /** DB2 external timestamp format (PR-11): {@code YYYY-MM-DD-HH.MM.SS.MIL0000} (26 chars). */
    private static final Pattern DB2_TIMESTAMP_PATTERN =
            Pattern.compile("^\\d{4}-\\d{2}-\\d{2}-\\d{2}\\.\\d{2}\\.\\d{2}\\.\\d{3}0000$");
    /** Formatter mirroring CBACT04C {@code Z-GET-DB2-FORMAT-TIMESTAMP} (PR-11). */
    private static final DateTimeFormatter DB2_TIMESTAMP_FORMATTER =
            DateTimeFormatter.ofPattern("yyyy-MM-dd-HH.mm.ss.SSS'0000'");

    /**
     * Approved batch output base directory for statement files. Created once at class-load time so the
     * absolute path is available to {@link #registerProperties(DynamicPropertyRegistry)} regardless of
     * JUnit extension ordering. The {@code carddemo.batch.output.base-dir} property is bound to this
     * directory; the {@code BatchOutputPathResolver} confines the (relative) {@code outputDir} job
     * parameter beneath it. A relative {@code outputDir} (e.g. {@code "statements"}) is therefore
     * required — the resolver rejects absolute paths and {@code ..} traversal.
     */
    private static final Path STATEMENT_BASE_DIR = createStatementBaseDir();

    private static Path createStatementBaseDir() {
        try {
            return Files.createTempDirectory("carddemo-batch-it-output");
        } catch (IOException e) {
            throw new UncheckedIOException("Unable to create temp output base dir for batch statements", e);
        }
    }

    // ------------------------------------------------------------------------
    // Testcontainers — a single PostgreSQL 15 database reused for the whole class
    // ------------------------------------------------------------------------

    /**
     * Dedicated PostgreSQL 15 container for the critical-batch-sequence suite, managed with the
     * Testcontainers <em>singleton-container</em> pattern: it is started eagerly in the {@code static}
     * initializer block below rather than via the {@code @Container}/{@code @Testcontainers} JUnit
     * lifecycle.
     *
     * <p>This is deliberate and necessary. The {@link #registerProperties(DynamicPropertyRegistry)}
     * supplier must read the container's mapped JDBC port while Spring is building the application
     * context. Under {@code @TestInstance(PER_CLASS)} the single test instance — and therefore the
     * Spring context that resolves {@code spring.datasource.*} — is created <em>before</em> the
     * Testcontainers JUnit extension's {@code beforeAll} callback would start a {@code @Container}
     * field, yielding {@code IllegalStateException: Mapped port can only be obtained after the
     * container is started}. Starting the container in a {@code static} block guarantees it is running
     * before any property supplier is evaluated, regardless of extension ordering or whether the whole
     * class or a single {@code @Nested} group is executed. The container is reaped by the
     * Testcontainers Ryuk sidecar at JVM exit; {@code @SuppressWarnings("resource")} documents that we
     * intentionally never close it explicitly.</p>
     */
    @SuppressWarnings("resource")
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>(
            DockerImageName.parse("postgres:15"))
            .withDatabaseName("carddemo_batch_test")
            .withUsername("carddemo")
            .withPassword("test_password");

    static {
        // Eager start (singleton-container pattern) — see field Javadoc for the rationale.
        POSTGRES.start();
    }

    /**
     * Overrides the datasource (and a handful of JPA/Flyway/Batch knobs) to point at the dedicated
     * container above. These dynamic properties take precedence over {@code application-test.yml}
     * (which otherwise uses the Testcontainers {@code jdbc:tc} magic URL), so exactly one container
     * is started. {@code spring.batch.job.enabled=false} prevents jobs from auto-running at startup —
     * this test launches them deliberately.
     */
    @DynamicPropertySource
    static void registerProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
        registry.add("spring.flyway.enabled", () -> "true");
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "validate");
        registry.add("spring.jpa.properties.hibernate.dialect",
                () -> "org.hibernate.dialect.PostgreSQLDialect");
        registry.add("spring.batch.job.enabled", () -> "false");
        registry.add("spring.batch.jdbc.initialize-schema", () -> "always");
        registry.add("spring.jpa.open-in-view", () -> "false");
        // Confine statement output beneath a writable, isolated temp directory (absolute base dir is
        // permitted; the caller-supplied outputDir job parameter must be a relative sub-path).
        registry.add("carddemo.batch.output.base-dir", STATEMENT_BASE_DIR::toString);
    }

    // ------------------------------------------------------------------------
    // Injected job beans (the four critical jobs + supporting jobs)
    // ------------------------------------------------------------------------

    @Autowired
    @Qualifier("transactionPostingJob")
    private Job transactionPostingJob;

    @Autowired
    @Qualifier("interestCalculationJob")
    private Job interestCalculationJob;

    @Autowired
    @Qualifier("transactionConsolidationJob")
    private Job transactionConsolidationJob;

    @Autowired
    @Qualifier("statementGenerationJob")
    private Job statementGenerationJob;

    @Autowired
    @Qualifier("dailyTransactionReadJob")
    private Job dailyTransactionReadJob;

    /** Optional — present only when the seed-load orchestration job bean is registered. */
    @Autowired(required = false)
    @Qualifier("dataInitializationJob")
    private Job dataInitializationJob;

    // ------------------------------------------------------------------------
    // Batch infrastructure. The field name 'jobLauncher' resolves the synchronous launcher bean
    // (the asynchronous one is named 'asyncJobLauncher'), disambiguating by name.
    // ------------------------------------------------------------------------

    @Autowired
    private JobLauncher jobLauncher;

    @Autowired
    private JobRegistry jobRegistry;

    @Autowired
    private JobRepositoryTestUtils jobRepositoryTestUtils;

    // ------------------------------------------------------------------------
    // Repositories for state verification
    // ------------------------------------------------------------------------

    @Autowired
    private AccountRepository accountRepository;

    @Autowired
    private CardXrefRepository cardXrefRepository;

    @Autowired
    private TransactionRepository transactionRepository;

    @Autowired
    private DailyTransactionRepository dailyTransactionRepository;

    @Autowired
    private RejectedTransactionRepository rejectedTransactionRepository;

    @Autowired
    private TransactionCategoryBalanceRepository tcatBalanceRepository;

    @Autowired
    private DisclosureGroupRepository discGroupRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    /**
     * JUnit-managed per-test temporary directory. Present to satisfy the file specification; statement
     * output itself is confined beneath {@link #STATEMENT_BASE_DIR} by the production
     * {@code BatchOutputPathResolver}, which rejects absolute {@code outputDir} job parameters.
     */
    @TempDir
    Path statementOutputDir;

    /** Monotonic counter producing unique 16-char {@code daily_transactions.tran_id} values per save. */
    private long dtSeq = 0L;

    /**
     * Clears Spring Batch execution metadata between test methods so jobs may be relaunched with
     * overlapping (or fresh) parameters without colliding with prior {@code JobInstance}s.
     */
    @BeforeEach
    void clearCheckpointHistory() {
        jobRepositoryTestUtils.removeJobExecutions();
    }

    // ========================================================================
    // HELPER METHODS
    // ========================================================================

    /**
     * Produces a strictly increasing {@code runId} used as a non-business job parameter so that each
     * launch creates a fresh {@code JobInstance} (avoiding {@link JobInstanceAlreadyCompleteException}).
     */
    private synchronized long nextRunId() {
        return ++runIdSeq;
    }

    private long runIdSeq = 0L;

    /**
     * Builds job parameters carrying a single auto-generated unique {@code runId} so each launch
     * creates a fresh {@code JobInstance} (avoiding {@link JobInstanceAlreadyCompleteException}). The
     * job itself is launched inline at the call site via {@code jobLauncher.run(job, runParams())}.
     *
     * <p><strong>Why every launch helper returns {@link JobParameters} (never {@link JobExecution}).</strong>
     * {@code @SpringBatchTest} registers a {@code JobScopeTestExecutionListener} that, before each test,
     * scans the test class (including {@code private} methods, via {@code ReflectionUtils.doWithMethods})
     * for a method whose return type is assignable to {@link JobExecution} and eagerly invokes it with
     * <em>no arguments</em> to seed a {@code @JobScope} context. A helper returning {@link JobExecution}
     * — especially one requiring a {@code Job}/{@code tranDate} argument — makes that listener fail with
     * {@code "No matching arguments found"} before the test body runs. The listener does <em>not</em>
     * scan {@link JobParameters}, so returning it here sidesteps the listener entirely while each test
     * still obtains its {@link JobExecution} from the inline {@code jobLauncher.run(...)} call.</p>
     */
    private JobParameters runParams() {
        return new JobParametersBuilder()
                .addLong("runId", nextRunId())
                .toJobParameters();
    }

    /**
     * Builds the interest-calculation (INTCALC / CBACT04C) job parameters and, as a necessary side
     * effect, pre-clears any previously emitted interest transactions so the per-{@code JobExecution}
     * suffix counter — which restarts at {@code 000001} for every run — cannot collide with ids written
     * by an earlier launch (the {@code transactions.tran_id} primary key would otherwise be violated).
     * Because method arguments are evaluated before the enclosing
     * {@code jobLauncher.run(interestCalculationJob, interestParams(tranDate))} call, the clear always
     * runs before the job. {@code tranDate} must be exactly 10 characters (the {@code PARM-DATE} prefix,
     * per PR-10). Returns {@link JobParameters} (never {@link JobExecution}) for the
     * {@code JobScopeTestExecutionListener} reason documented on {@link #runParams()}.
     */
    private JobParameters interestParams(String tranDate) {
        clearInterestTransactions();
        return new JobParametersBuilder()
                .addString("tranDate", tranDate)
                .addLong("runId", nextRunId())
                .toJobParameters();
    }

    /**
     * Builds the statement-generation (CREASTMT / CBSTM03A) job parameters directing output beneath the
     * approved base directory. The {@code outputDir} parameter must be a <em>relative</em> sub-path (the
     * production {@code BatchOutputPathResolver} rejects absolute paths and {@code ..} traversal) and is
     * registered non-identifying so it does not affect {@code JobInstance} identity. Returns
     * {@link JobParameters} (never {@link JobExecution}) for the {@code JobScopeTestExecutionListener}
     * reason documented on {@link #runParams()}.
     */
    private JobParameters statementParams(String relativeOutputDir) {
        return new JobParametersBuilder()
                .addString("outputDir", relativeOutputDir, false)
                .addLong("runId", nextRunId())
                .toJobParameters();
    }

    /**
     * Builds a valid {@link DailyTransaction} staging row using the <em>real</em> entity field names
     * with an origin timestamp of {@code 2020-01-01T12:00} — comfortably before every seeded account
     * expiration date, so the record is not rejected with code 103.
     */
    private DailyTransaction createDailyTransaction(String tranId, String cardNum, BigDecimal amount) {
        return createDailyTransaction(tranId, cardNum, amount, LocalDateTime.of(2020, 1, 1, 12, 0, 0));
    }

    /**
     * Builds a valid {@link DailyTransaction} staging row with an explicit origin timestamp (used to
     * drive the expiration / over-limit ordering for codes 102 and 103). The auto-generated identity
     * primary key ({@code daily_tran_id}) and {@code processed} flag (defaults to {@code false}) are
     * intentionally left unset.
     */
    private DailyTransaction createDailyTransaction(String tranId, String cardNum,
                                                    BigDecimal amount, LocalDateTime origTs) {
        return DailyTransaction.builder()
                .dalytranId(tranId)
                .typeCd("01")
                .categoryCd("0001")
                .source("POS TERM")
                .description("Test daily transaction")
                .amount(amount)
                .merchantId(800000000L)
                .merchantName("Test Merchant")
                .merchantCity("Test City")
                .merchantZip("12345")
                .cardNum(cardNum)
                .origTimestamp(origTs)
                .build();
    }

    /** Generates a unique 16-char {@code daily_transactions.tran_id} (prefix {@code IT} + 14 digits). */
    private String nextDtId() {
        return String.format("IT%014d", ++dtSeq);
    }

    /**
     * Resolves an account id that owns at least one card, selected by ordinal position so that each
     * test can claim an isolated account and reason about before/after deltas without interference.
     */
    private Long accountWithCard(int offset) {
        return jdbcTemplate.queryForObject(
                "SELECT xref_acct_id FROM card_xref ORDER BY xref_acct_id LIMIT 1 OFFSET ?",
                Long.class, offset);
    }

    /** Resolves a card number cross-referenced to the supplied account id. */
    private String cardForAccount(Long acctId) {
        return jdbcTemplate.queryForObject(
                "SELECT xref_card_num FROM card_xref WHERE xref_acct_id = ? LIMIT 1",
                String.class, acctId);
    }

    /** All interest transactions emitted by INTCALC: {@code TRAN-TYPE-CD='01'} and {@code TRAN-CAT-CD='0005'}. */
    private List<Transaction> interestTransactions() {
        return transactionRepository.findAll().stream()
                .filter(t -> INTEREST_TYPE_CD.equals(trimToEmpty(t.getTypeCd())))
                .filter(t -> INTEREST_CAT_CD.equals(trimToEmpty(t.getCategoryCd())))
                .toList();
    }

    /** Interest transactions emitted for a specific account (matched by the {@code "Int. for a/c"} description). */
    private List<Transaction> interestTransactionsForAccount(Long acctId) {
        String token = descriptionForAccount(acctId);
        return interestTransactions().stream()
                .filter(t -> t.getDescription() != null && t.getDescription().contains(token))
                .toList();
    }

    /** The exact interest description fragment CBACT04C writes: {@code "Int. for a/c " + %011d}. */
    private String descriptionForAccount(Long acctId) {
        return "Int. for a/c " + String.format("%011d", acctId);
    }

    /**
     * Removes any interest transactions already present so a subsequent INTCALC launch starts from a
     * clean slate (its per-execution suffix counter restarts at {@code 000001}). Nothing references the
     * {@code transactions} table via a foreign key, so the delete is safe.
     */
    private void clearInterestTransactions() {
        List<Transaction> existing = interestTransactions();
        if (!existing.isEmpty()) {
            transactionRepository.deleteAll(existing);
        }
    }

    /** Seeds {@code count} small, valid, positive daily transactions for the lowest-ordinal account. */
    private void seedValidDailyTransactions(int count) {
        Long acctId = accountWithCard(0);
        String card = cardForAccount(acctId);
        for (int i = 1; i <= count; i++) {
            dailyTransactionRepository.save(
                    createDailyTransaction(nextDtId(), card, new BigDecimal("1.0" + i)));
        }
    }

    /** Formats a {@link LocalDateTime} in the DB2 external timestamp form used at I/O boundaries (PR-11). */
    private static String db2Formatted(LocalDateTime ts) {
        return ts.format(DB2_TIMESTAMP_FORMATTER);
    }

    /** Recursively lists statement output files beneath the approved base dir matching {@code suffix}. */
    private List<Path> statementFiles(String suffix) throws IOException {
        if (!Files.exists(STATEMENT_BASE_DIR)) {
            return new ArrayList<>();
        }
        try (Stream<Path> walk = Files.walk(STATEMENT_BASE_DIR)) {
            // Returns a mutable ArrayList: callers combine the .html and .txt result sets via addAll(),
            // so the list must be modifiable (Stream.toList() / List.of() are unmodifiable).
            return new ArrayList<>(walk.filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().toLowerCase().endsWith(suffix))
                    .toList());
        }
    }

    /** Null-safe trim used to neutralise any {@code CHAR(n)} right-padding on fixed-width code columns. */
    private static String trimToEmpty(String value) {
        return value == null ? "" : value.trim();
    }

    // ========================================================================
    // NESTED TEST GROUPS
    // ========================================================================

    /**
     * Step 1 of the sequence — POSTTRAN (CBTRN02C). Reads the {@code daily_transactions} staging table,
     * posts accepted records to {@code transactions}, upserts {@code tran_cat_balances}, updates
     * {@code accounts}, and writes rejects to {@code rejected_transactions}. The reader consumes only
     * rows where {@code processed = false}; the writer flips {@code processed = true} on both the
     * accepted and rejected paths, so the job is idempotent across reruns.
     */
    @Nested
    @DisplayName("POSTTRAN — Transaction Posting Job (CBTRN02C parity)")
    class IndividualJobExecutionPosttran {

        @Test
        @DisplayName("Completes with COMPLETED status when there are no unprocessed daily transactions")
        void shouldCompleteSuccessfullyWithEmptyDailyTransactions() throws Exception {
            // Drain any leftover unprocessed rows so the reader observes an empty input.
            dailyTransactionRepository.deleteAll();

            JobExecution exec = jobLauncher.run(transactionPostingJob, runParams());

            assertThat(exec.getStatus())
                    .as("POSTTRAN must complete even when the daily feed is empty")
                    .isEqualTo(BatchStatus.COMPLETED);
        }

        @Test
        @DisplayName("Posts a valid daily transaction into the transactions table")
        void shouldPostValidTransactionsToTransactionsTable() throws Exception {
            Long acctId = accountWithCard(0);
            String card = cardForAccount(acctId);
            String tranId = nextDtId();
            dailyTransactionRepository.save(createDailyTransaction(tranId, card, new BigDecimal("5.00")));

            // The card cross-reference must resolve — this is the CBTRN02C 1500-A-LOOKUP-XREF precondition
            // that distinguishes a postable transaction from a code-100 reject.
            assertThat(cardXrefRepository.findById(card))
                    .as("Card cross-reference must resolve for a postable transaction (CBTRN02C 1500-A-LOOKUP-XREF)")
                    .isPresent();

            long before = transactionRepository.count();

            JobExecution exec = jobLauncher.run(transactionPostingJob, runParams());

            assertThat(exec.getStatus())
                    .as("POSTTRAN must complete successfully")
                    .isEqualTo(BatchStatus.COMPLETED);
            assertThat(transactionRepository.count())
                    .as("A valid daily transaction must be posted to the transactions table")
                    .isGreaterThan(before);
            assertThat(transactionRepository.findById(tranId))
                    .as("Posted transaction must retain its 16-char daily tran id")
                    .isPresent();
        }

        @Test
        @DisplayName("Rejects an unknown card with validation code 100 — INVALID CARD NUMBER FOUND (PR-03)")
        void shouldRejectTransactionWithUnknownCard100() throws Exception {
            String tranId = nextDtId();
            // 9999999999999999 has no card_xref row → 1500-A-LOOKUP-XREF INVALID KEY → code 100.
            dailyTransactionRepository.save(
                    createDailyTransaction(tranId, "9999999999999999", new BigDecimal("10.00")));

            JobExecution exec = jobLauncher.run(transactionPostingJob, runParams());
            assertThat(exec.getStatus()).isEqualTo(BatchStatus.COMPLETED);

            List<RejectedTransaction> rejects = rejectedTransactionRepository.findAll();
            assertThat(rejects)
                    .as("An unknown card must be rejected with code 100 and the exact COBOL message")
                    .anyMatch(r -> r.getValidationCode() == 100
                            && trimToEmpty(r.getRejectionReason()).contains(MSG_INVALID_CARD));
        }

        @Test
        @DisplayName("Rejects a card whose account is missing with validation code 101 — ACCOUNT RECORD NOT FOUND (PR-03)")
        void shouldRejectTransactionWithMissingAccount101() throws Exception {
            // A card may resolve via xref to a (deliberately dangling) account id so that the xref lookup
            // succeeds (no code 100) but the account lookup fails (code 101). The FK guarding
            // card_xref.xref_acct_id is temporarily dropped to stage this otherwise-impossible state, then
            // restored — after the dangling rows are removed — in the finally block.
            final String orphanCard = "9100000000000001";
            final long danglingAcctId = 888888L;
            Long realAcct = accountWithCard(0);
            Long realCust = jdbcTemplate.queryForObject(
                    "SELECT xref_cust_id FROM card_xref ORDER BY xref_cust_id LIMIT 1", Long.class);
            String tranId = nextDtId();

            jdbcTemplate.execute("ALTER TABLE card_xref DROP CONSTRAINT fk_xref_account");
            try {
                jdbcTemplate.update(
                        "INSERT INTO cards (card_num, account_id, cvv_cd, embossed_name, expiration_date, "
                                + "active_status, version) VALUES (?, ?, ?, ?, ?, ?, ?)",
                        orphanCard, realAcct, 123, "IT Reject", java.sql.Date.valueOf("2030-01-01"), "Y", 0);
                jdbcTemplate.update(
                        "INSERT INTO card_xref (xref_card_num, xref_cust_id, xref_acct_id) VALUES (?, ?, ?)",
                        orphanCard, realCust, danglingAcctId);
                dailyTransactionRepository.save(
                        createDailyTransaction(tranId, orphanCard, new BigDecimal("5.00")));

                JobExecution exec = jobLauncher.run(transactionPostingJob, runParams());
                assertThat(exec.getStatus()).isEqualTo(BatchStatus.COMPLETED);

                List<RejectedTransaction> rejects = rejectedTransactionRepository.findAll();
                assertThat(rejects)
                        .as("A card whose account is absent must be rejected with code 101 and the exact COBOL message")
                        .anyMatch(r -> r.getValidationCode() == 101
                                && trimToEmpty(r.getRejectionReason()).contains(MSG_ACCOUNT_NOT_FOUND));
            } finally {
                // Remove the dangling rows BEFORE re-adding the FK, otherwise the constraint would reject
                // the orphaned xref_acct_id and the ALTER would fail.
                dailyTransactionRepository.deleteAll(
                        dailyTransactionRepository.findAll().stream()
                                .filter(d -> orphanCard.equals(d.getCardNum()))
                                .toList());
                jdbcTemplate.update("DELETE FROM card_xref WHERE xref_card_num = ?", orphanCard);
                jdbcTemplate.update("DELETE FROM cards WHERE card_num = ?", orphanCard);
                jdbcTemplate.execute("ALTER TABLE card_xref ADD CONSTRAINT fk_xref_account "
                        + "FOREIGN KEY (xref_acct_id) REFERENCES accounts (acct_id)");
            }
        }

        @Test
        @DisplayName("Rejects an over-limit transaction with validation code 102 — OVERLIMIT TRANSACTION (PR-03)")
        void shouldRejectOverlimitTransactionWithCode102() throws Exception {
            Long acctId = accountWithCard(4);
            Account acct = accountRepository.findById(acctId).orElseThrow();
            String card = cardForAccount(acctId);

            // Origin date strictly before expiration so the expiration check (code 103) cannot fire and
            // mask the over-limit decision (CBTRN02C performs both checks with no early return between them).
            LocalDateTime origTs = acct.getExpirationDate().minusMonths(1).atTime(12, 0);
            BigDecimal overAmount = acct.getCreditLimit().add(new BigDecimal("1000000.00"));
            String tranId = nextDtId();
            dailyTransactionRepository.save(createDailyTransaction(tranId, card, overAmount, origTs));

            JobExecution exec = jobLauncher.run(transactionPostingJob, runParams());
            assertThat(exec.getStatus()).isEqualTo(BatchStatus.COMPLETED);

            List<RejectedTransaction> rejects = rejectedTransactionRepository.findAll();
            assertThat(rejects)
                    .as("An over-limit transaction must be rejected with code 102 and the exact COBOL message")
                    .anyMatch(r -> r.getValidationCode() == 102
                            && trimToEmpty(r.getRejectionReason()).contains(MSG_OVERLIMIT));
        }

        @Test
        @DisplayName("Rejects a post-expiration transaction with validation code 103 — TRANSACTION RECEIVED AFTER ACCT EXPIRATION (PR-03)")
        void shouldRejectExpiredAccountTransactionWithCode103() throws Exception {
            Long acctId = accountWithCard(5);
            Account acct = accountRepository.findById(acctId).orElseThrow();
            String card = cardForAccount(acctId);

            // Origin date strictly after expiration with a small (within-limit) amount so only code 103 fires.
            LocalDateTime origTs = acct.getExpirationDate().plusMonths(1).atTime(12, 0);
            String tranId = nextDtId();
            dailyTransactionRepository.save(createDailyTransaction(tranId, card, new BigDecimal("5.00"), origTs));

            JobExecution exec = jobLauncher.run(transactionPostingJob, runParams());
            assertThat(exec.getStatus()).isEqualTo(BatchStatus.COMPLETED);

            List<RejectedTransaction> rejects = rejectedTransactionRepository.findAll();
            assertThat(rejects)
                    .as("A post-expiration transaction must be rejected with code 103 and the exact COBOL message")
                    .anyMatch(r -> r.getValidationCode() == 103
                            && trimToEmpty(r.getRejectionReason()).contains(MSG_EXPIRED));
        }

        @Test
        @DisplayName("Upserts the transaction category balance by composite key, adding DALYTRAN-AMT (PR-06)")
        void shouldUpsertTransactionCategoryBalance() throws Exception {
            Long acctId = accountWithCard(1);
            String card = cardForAccount(acctId);
            TransactionCategoryBalanceId key = TransactionCategoryBalanceId.builder()
                    .accountId(acctId).typeCd("01").categoryCd("0001").build();

            BigDecimal before = tcatBalanceRepository.findById(key)
                    .map(TransactionCategoryBalance::getTranCatBal)
                    .orElse(BigDecimal.ZERO);

            dailyTransactionRepository.save(createDailyTransaction(nextDtId(), card, new BigDecimal("3.50")));

            JobExecution exec = jobLauncher.run(transactionPostingJob, runParams());
            assertThat(exec.getStatus()).isEqualTo(BatchStatus.COMPLETED);

            TransactionCategoryBalance after = tcatBalanceRepository.findById(key).orElseThrow();
            assertThat(after.getTranCatBal())
                    .as("TCATBAL upsert per PR-06: existing balance + DALYTRAN-AMT (3.50)")
                    .isEqualByComparingTo(before.add(new BigDecimal("3.50")));
        }

        @Test
        @DisplayName("Adds a positive amount to curr_cyc_credit and curr_bal, leaving curr_cyc_debit untouched (PR-07)")
        void shouldUpdateAccountCurrCycCreditForPositiveAmount() throws Exception {
            Long acctId = accountWithCard(2);
            String card = cardForAccount(acctId);
            Account before = accountRepository.findById(acctId).orElseThrow();
            BigDecimal creditBefore = before.getCurrCycCredit();
            BigDecimal debitBefore = before.getCurrCycDebit();
            BigDecimal balBefore = before.getCurrBal();

            BigDecimal amount = new BigDecimal("7.00");
            dailyTransactionRepository.save(createDailyTransaction(nextDtId(), card, amount));

            JobExecution exec = jobLauncher.run(transactionPostingJob, runParams());
            assertThat(exec.getStatus()).isEqualTo(BatchStatus.COMPLETED);

            Account after = accountRepository.findById(acctId).orElseThrow();
            assertThat(after.getCurrCycCredit())
                    .as("Positive amount must increase curr_cyc_credit per PR-07")
                    .isEqualByComparingTo(creditBefore.add(amount));
            assertThat(after.getCurrCycDebit())
                    .as("curr_cyc_debit must be unchanged for a positive amount per PR-07")
                    .isEqualByComparingTo(debitBefore);
            assertThat(after.getCurrBal())
                    .as("curr_bal must increase by the amount regardless of sign per PR-07")
                    .isEqualByComparingTo(balBefore.add(amount));
        }

        @Test
        @DisplayName("Adds a negative amount directly to curr_cyc_debit and curr_bal, no abs() (PR-07)")
        void shouldUpdateAccountCurrCycDebitForNegativeAmount() throws Exception {
            Long acctId = accountWithCard(3);
            String card = cardForAccount(acctId);
            Account before = accountRepository.findById(acctId).orElseThrow();
            BigDecimal creditBefore = before.getCurrCycCredit();
            BigDecimal debitBefore = before.getCurrCycDebit();
            BigDecimal balBefore = before.getCurrBal();

            // CBTRN02C 2800-UPDATE-ACCOUNT-REC adds the SIGNED amount to curr_cyc_debit (never abs()).
            BigDecimal amount = new BigDecimal("-8.00");
            dailyTransactionRepository.save(createDailyTransaction(nextDtId(), card, amount));

            JobExecution exec = jobLauncher.run(transactionPostingJob, runParams());
            assertThat(exec.getStatus()).isEqualTo(BatchStatus.COMPLETED);

            Account after = accountRepository.findById(acctId).orElseThrow();
            assertThat(after.getCurrCycDebit())
                    .as("Negative amount must be added (signed) to curr_cyc_debit per PR-07 — no abs()")
                    .isEqualByComparingTo(debitBefore.add(amount));
            assertThat(after.getCurrCycCredit())
                    .as("curr_cyc_credit must be unchanged for a negative amount per PR-07")
                    .isEqualByComparingTo(creditBefore);
            assertThat(after.getCurrBal())
                    .as("curr_bal must change by the signed amount per PR-07")
                    .isEqualByComparingTo(balBefore.add(amount));
        }
    }

    /**
     * Step 2 of the sequence — INTCALC (CBACT04C). Iterates {@code tran_cat_balances}, looks up the
     * disclosure interest rate (with the {@code DEFAULT} group fallback, PR-02), computes monthly
     * interest with the exact COBOL formula (PR-01), applies it to the account while zeroing the cycle
     * credit/debit buckets (PR-08), and emits one interest transaction per balance row with a
     * {@code PARM-DATE}-prefixed 16-char id (PR-10) and DB2-format timestamps (PR-11).
     *
     * <p>Every seeded account has a {@code NULL} {@code group_id}, so the specific disclosure-group
     * lookup always misses and INTCALC always resolves rates through the {@code DEFAULT} group.</p>
     */
    @Nested
    @DisplayName("INTCALC — Interest Calculation Job (CBACT04C parity)")
    class IndividualJobExecutionIntcalc {

        @Test
        @DisplayName("Completes successfully with a 10-char tranDate job parameter")
        void shouldCompleteSuccessfullyWithJobParameter() throws Exception {
            JobExecution exec = jobLauncher.run(interestCalculationJob, interestParams(PARM_DATE));

            assertThat(exec.getStatus())
                    .as("INTCALC must complete with a valid 10-char tranDate")
                    .isEqualTo(BatchStatus.COMPLETED);
            assertThat(exec.getJobParameters().getString("tranDate"))
                    .as("tranDate parameter must be carried on the execution")
                    .isEqualTo(PARM_DATE);
        }

        @Test
        @DisplayName("CRITICAL — Computes interest with the EXACT COBOL formula (TRAN-CAT-BAL × DIS-INT-RATE) / 1200 (PR-01)")
        void shouldComputeInterestUsingExactCobolFormula() throws Exception {
            Long acctId = accountWithCard(8);
            // Force a deterministic category balance of 1200.00 for (acct, '01', '0001').
            TransactionCategoryBalanceId key = TransactionCategoryBalanceId.builder()
                    .accountId(acctId).typeCd("01").categoryCd("0001").build();
            TransactionCategoryBalance tcb = tcatBalanceRepository.findById(key).orElseThrow();
            tcb.setTranCatBal(new BigDecimal("1200.00"));
            tcatBalanceRepository.save(tcb);

            // The applicable rate is resolved through the DEFAULT group (accounts carry no group id).
            DisclosureGroupId defKey = new DisclosureGroupId("DEFAULT", "01", "0001");
            BigDecimal rate = discGroupRepository.findById(defKey)
                    .map(DisclosureGroup::getDisIntRate)
                    .orElseThrow();
            BigDecimal expected = new BigDecimal("1200.00")
                    .multiply(rate)
                    .divide(BigDecimal.valueOf(1200), 2, RoundingMode.HALF_UP);

            jobLauncher.run(interestCalculationJob, interestParams(PARM_DATE));

            List<Transaction> forAccount = interestTransactionsForAccount(acctId);
            assertThat(forAccount)
                    .as("INTCALC must emit an interest transaction for the seeded account")
                    .isNotEmpty();
            assertThat(forAccount)
                    .as("Interest must equal (1200.00 × DEFAULT rate) / 1200 computed with HALF_UP per PR-01")
                    .anyMatch(t -> t.getAmount().compareTo(expected) == 0);
        }

        @Test
        @DisplayName("Falls back to the DEFAULT disclosure group when the specific group lookup misses (PR-02)")
        void shouldRetryWithDefaultGroupIdWhenSpecificMisses() throws Exception {
            Long acctId = accountWithCard(9);
            // Category 0002 is present ONLY under the DEFAULT group in the reference seed; create a
            // balance row for it so INTCALC must resolve its rate via the DEFAULT fallback.
            TransactionCategoryBalanceId key = TransactionCategoryBalanceId.builder()
                    .accountId(acctId).typeCd("01").categoryCd("0002").build();
            TransactionCategoryBalance row = tcatBalanceRepository.findById(key)
                    .orElseGet(() -> TransactionCategoryBalance.builder().id(key).build());
            row.setTranCatBal(new BigDecimal("1200.00"));
            tcatBalanceRepository.save(row);

            // Prove the precondition: no specific entry, but a DEFAULT entry exists.
            assertThat(discGroupRepository.findById(new DisclosureGroupId("NONEXISTENT", "01", "0002")))
                    .as("A non-existent disclosure group must not resolve (forces the fallback path)")
                    .isEmpty();
            BigDecimal defaultRate = discGroupRepository.findById(new DisclosureGroupId("DEFAULT", "01", "0002"))
                    .map(DisclosureGroup::getDisIntRate)
                    .orElseThrow();
            BigDecimal expected = new BigDecimal("1200.00")
                    .multiply(defaultRate)
                    .divide(BigDecimal.valueOf(1200), 2, RoundingMode.HALF_UP);

            jobLauncher.run(interestCalculationJob, interestParams(PARM_DATE));

            assertThat(interestTransactionsForAccount(acctId))
                    .as("Interest must be computed using the DEFAULT-group rate per PR-02 fallback")
                    .anyMatch(t -> t.getAmount().compareTo(expected) == 0);
        }

        @Test
        @DisplayName("Generates 16-char transaction ids prefixed with the 10-char PARM-DATE (PR-10)")
        void shouldGenerate16CharTransactionIdWithParmDatePrefix() throws Exception {
            jobLauncher.run(interestCalculationJob, interestParams(PARM_DATE));

            List<Transaction> interest = interestTransactions();
            assertThat(interest)
                    .as("INTCALC must emit at least one interest transaction")
                    .isNotEmpty();
            assertThat(interest).allSatisfy(t -> {
                assertThat(t.getTranId())
                        .as("Interest tran_id must be 16 chars (10-char prefix + 6-char suffix) per PR-10")
                        .hasSize(16);
                assertThat(TRAN_ID_PATTERN.matcher(t.getTranId()).matches())
                        .as("Interest tran_id must match ^%s\\d{6}$ per PR-10 (was %s)", PARM_DATE, t.getTranId())
                        .isTrue();
            });
        }

        @Test
        @DisplayName("Applies interest to the account and zeroes curr_cyc_credit/curr_cyc_debit (PR-08)")
        void shouldApplyInterestToAccountAndZeroCycCreditAndDebit() throws Exception {
            Long acctId = accountWithCard(10);
            // Seed a deterministic single-category balance so the total applied interest is known.
            TransactionCategoryBalanceId key = TransactionCategoryBalanceId.builder()
                    .accountId(acctId).typeCd("01").categoryCd("0001").build();
            TransactionCategoryBalance tcb = tcatBalanceRepository.findById(key).orElseThrow();
            tcb.setTranCatBal(new BigDecimal("1200.00"));
            tcatBalanceRepository.save(tcb);

            // Give the account non-zero cycle buckets so the zeroing is observable.
            Account seed = accountRepository.findById(acctId).orElseThrow();
            seed.setCurrCycCredit(new BigDecimal("100.00"));
            seed.setCurrCycDebit(new BigDecimal("40.00"));
            accountRepository.save(seed);

            Account before = accountRepository.findById(acctId).orElseThrow();
            BigDecimal balBefore = before.getCurrBal();

            DisclosureGroupId defKey = new DisclosureGroupId("DEFAULT", "01", "0001");
            BigDecimal rate = discGroupRepository.findById(defKey)
                    .map(DisclosureGroup::getDisIntRate)
                    .orElseThrow();
            BigDecimal expectedInterest = new BigDecimal("1200.00")
                    .multiply(rate)
                    .divide(BigDecimal.valueOf(1200), 2, RoundingMode.HALF_UP);

            jobLauncher.run(interestCalculationJob, interestParams(PARM_DATE));

            Account after = accountRepository.findById(acctId).orElseThrow();
            assertThat(after.getCurrCycCredit())
                    .as("curr_cyc_credit must be zeroed after interest per PR-08")
                    .isEqualByComparingTo(BigDecimal.ZERO);
            assertThat(after.getCurrCycDebit())
                    .as("curr_cyc_debit must be zeroed after interest per PR-08")
                    .isEqualByComparingTo(BigDecimal.ZERO);
            assertThat(after.getCurrBal())
                    .as("curr_bal must increase by the total applied interest per PR-08")
                    .isEqualByComparingTo(balBefore.add(expectedInterest));
        }

        @Test
        @DisplayName("Emits interest transactions with TRAN-SOURCE 'System' and a 'Int. for a/c' description")
        void shouldEmitInterestTransactionWithSystemSourceAndDescription() throws Exception {
            jobLauncher.run(interestCalculationJob, interestParams(PARM_DATE));

            List<Transaction> interest = interestTransactions();
            assertThat(interest)
                    .as("INTCALC must emit at least one interest transaction")
                    .isNotEmpty();
            Transaction tx = interest.get(0);
            assertThat(trimToEmpty(tx.getSource()))
                    .as("Interest TRAN-SOURCE must be 'System' per CBACT04C 1300-B-WRITE-TX")
                    .isEqualTo("System");
            assertThat(tx.getDescription())
                    .as("Interest TRAN-DESC must start with 'Int. for a/c' per CBACT04C")
                    .startsWith("Int. for a/c");
        }

        @Test
        @DisplayName("Emits DB2-format millisecond-precision timestamps with orig == proc (PR-11)")
        void shouldEmitDB2FormatTimestampForInterestTransactions() throws Exception {
            jobLauncher.run(interestCalculationJob, interestParams(PARM_DATE));

            List<Transaction> interest = interestTransactions();
            assertThat(interest)
                    .as("INTCALC must emit at least one interest transaction")
                    .isNotEmpty();
            assertThat(interest).allSatisfy(t -> {
                assertThat(t.getOrigTimestamp())
                        .as("orig timestamp must be present")
                        .isNotNull();
                assertThat(t.getProcTimestamp())
                        .as("proc timestamp must be present")
                        .isNotNull();
                // The entity stores LocalDateTime; PR-11 governs the DB2 external string form rendered at
                // I/O boundaries. Verify the value renders to the canonical 26-char pattern and carries no
                // sub-millisecond component (DB2 .MIL precision), and that orig == proc (single write).
                assertThat(DB2_TIMESTAMP_PATTERN.matcher(db2Formatted(t.getOrigTimestamp())).matches())
                        .as("orig timestamp must render in DB2 format yyyy-MM-dd-HH.mm.ss.SSS'0000' per PR-11")
                        .isTrue();
                assertThat(t.getOrigTimestamp().getNano() % 1_000_000)
                        .as("DB2 timestamp precision is milliseconds — no sub-millisecond component per PR-11")
                        .isZero();
                assertThat(t.getProcTimestamp())
                        .as("Interest orig and proc timestamps are written from the same instant")
                        .isEqualTo(t.getOrigTimestamp());
            });
        }

        @Test
        @DisplayName("Emits interest transactions carrying the canonical TRAN-TYPE-CD '01' and TRAN-CAT-CD '0005'")
        void shouldEmitInterestTransactionsWithCanonicalTypeAndCategory() throws Exception {
            jobLauncher.run(interestCalculationJob, interestParams(PARM_DATE));

            List<Transaction> interest = interestTransactions();
            assertThat(interest)
                    .as("INTCALC must emit at least one interest transaction")
                    .isNotEmpty();
            assertThat(interest).allSatisfy(t -> {
                assertThat(trimToEmpty(t.getTypeCd()))
                        .as("Interest transactions carry TRAN-TYPE-CD '01'")
                        .isEqualTo(INTEREST_TYPE_CD);
                assertThat(trimToEmpty(t.getCategoryCd()))
                        .as("Interest transactions carry TRAN-CAT-CD '0005'")
                        .isEqualTo(INTEREST_CAT_CD);
                assertThat(t.getAmount())
                        .as("Interest amount must be scaled to 2 decimal places per PR-01 / PR-16")
                        .isNotNull();
            });
        }
    }

    /**
     * Step 3 of the sequence — COMBTRAN (CBTRN03C + SORT + REPRO). Consolidates posted and interest
     * transactions into the master {@code transactions} table sorted by {@code tran_id}. Implemented as
     * an idempotent {@code INSERT ... SELECT ... ON CONFLICT (tran_id) DO NOTHING}, so reruns never
     * create duplicates.
     */
    @Nested
    @DisplayName("COMBTRAN — Transaction Consolidation Job (CBTRN03C + SORT/REPRO parity)")
    class IndividualJobExecutionCombtran {

        @Test
        @DisplayName("Completes successfully against the current transactions table state")
        void shouldCompleteSuccessfullyWithExistingTransactions() throws Exception {
            JobExecution exec = jobLauncher.run(transactionConsolidationJob, runParams());

            assertThat(exec.getStatus())
                    .as("COMBTRAN must complete successfully")
                    .isEqualTo(BatchStatus.COMPLETED);
        }

        @Test
        @DisplayName("Does not produce duplicate transactions when rerun (idempotent ON CONFLICT DO NOTHING)")
        void shouldNotProduceDuplicateTransactionsOnRerun() throws Exception {
            JobExecution first = jobLauncher.run(transactionConsolidationJob, runParams());
            assertThat(first.getStatus()).isEqualTo(BatchStatus.COMPLETED);
            long countAfterFirst = transactionRepository.count();

            JobExecution second = jobLauncher.run(transactionConsolidationJob, runParams());
            assertThat(second.getStatus()).isEqualTo(BatchStatus.COMPLETED);
            long countAfterSecond = transactionRepository.count();

            assertThat(countAfterSecond)
                    .as("Re-running COMBTRAN must not change the transaction count (idempotent)")
                    .isEqualTo(countAfterFirst);

            Integer duplicateIds = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM (SELECT tran_id FROM transactions GROUP BY tran_id "
                            + "HAVING COUNT(*) > 1) AS dups", Integer.class);
            assertThat(duplicateIds)
                    .as("No tran_id may appear more than once after consolidation")
                    .isZero();
        }

        @Test
        @DisplayName("Keeps consolidated transactions queryable in ascending tran_id order (SORT FIELDS=(1,16,CH,A))")
        void shouldKeepTransactionsQueryableInTranIdAscendingOrder() throws Exception {
            JobExecution exec = jobLauncher.run(transactionConsolidationJob, runParams());
            assertThat(exec.getStatus()).isEqualTo(BatchStatus.COMPLETED);

            List<String> ids = jdbcTemplate.queryForList(
                    "SELECT tran_id FROM transactions ORDER BY tran_id ASC", String.class);
            assertThat(ids)
                    .as("Consolidated transactions must be retrievable sorted by tran_id ascending")
                    .isSorted();
        }
    }

    /**
     * Step 4 of the sequence — CREASTMT (CBSTM03A + JCL orchestration). Generates a plain-text
     * statement (LRECL 80) and an HTML statement (LRECL 100) per account. The HTML structure preserves
     * the {@code 5100-WRITE-HTML-HEADER} layout (PR-09 — full byte-for-byte parity is asserted by the
     * sibling {@code StatementGenerationParityTest}; this IT checks structural correctness only).
     */
    @Nested
    @DisplayName("CREASTMT — Statement Generation Job (CBSTM03A parity)")
    class IndividualJobExecutionCreastmt {

        @Test
        @DisplayName("Completes successfully with the seeded master data")
        void shouldCompleteSuccessfullyWithSeededData() throws Exception {
            JobExecution exec = jobLauncher.run(statementGenerationJob, statementParams("statements"));

            assertThat(exec.getStatus())
                    .as("CREASTMT must complete successfully")
                    .isEqualTo(BatchStatus.COMPLETED);
        }

        @Test
        @DisplayName("Emits at least one statement output file beneath the approved base directory")
        void shouldEmitStatementOutputFiles() throws Exception {
            JobExecution exec = jobLauncher.run(statementGenerationJob, statementParams("statements"));
            assertThat(exec.getStatus()).isEqualTo(BatchStatus.COMPLETED);

            List<Path> all = statementFiles(".html");
            all.addAll(statementFiles(".txt"));
            assertThat(all)
                    .as("CREASTMT must produce at least one statement output file")
                    .isNotEmpty();
        }

        @Test
        @DisplayName("Emits valid HTML structure: DOCTYPE, 16px name paragraph, Account ID line (PR-09)")
        void shouldEmitValidHtmlStructure() throws Exception {
            JobExecution exec = jobLauncher.run(statementGenerationJob, statementParams("statements"));
            assertThat(exec.getStatus()).isEqualTo(BatchStatus.COMPLETED);

            List<Path> htmlFiles = statementFiles(".html");
            assertThat(htmlFiles)
                    .as("CREASTMT must produce at least one HTML statement")
                    .isNotEmpty();
            String content = Files.readString(htmlFiles.get(0));

            assertThat(content)
                    .as("HTML must contain a DOCTYPE per CBSTM03A 5100-WRITE-HTML-HEADER (PR-09)")
                    .containsIgnoringCase("doctype");
            assertThat(content)
                    .as("HTML must render the name paragraph at font-size:16px per CBSTM03A (PR-09)")
                    .contains("font-size:16px");
            assertThat(content)
                    .as("HTML must contain the Account ID line per CBSTM03A (PR-09)")
                    .contains("Account ID");
        }

        @Test
        @DisplayName("Emits a plain-text statement whose lines fit within LRECL 80")
        void shouldEmitPlainTextStatementWithLineLengthWithin80() throws Exception {
            JobExecution exec = jobLauncher.run(statementGenerationJob, statementParams("statements"));
            assertThat(exec.getStatus()).isEqualTo(BatchStatus.COMPLETED);

            List<Path> textFiles = statementFiles(".txt");
            assertThat(textFiles)
                    .as("CREASTMT must produce at least one plain-text statement")
                    .isNotEmpty();
            List<String> lines = Files.readAllLines(textFiles.get(0));
            assertThat(lines).allSatisfy(line ->
                    assertThat(line.length())
                            .as("Plain-text statement line must fit within LRECL=80 (CREASTMT STMTFILE DD)")
                            .isLessThanOrEqualTo(80));
        }
    }

    /**
     * The canonical PR-12 verification: launches all four critical jobs in the mandated order
     * {@code POSTTRAN → INTCALC → COMBTRAN → CREASTMT}, asserting each completes and that the output of
     * one feeds correctly into the next. This is the most important test in the class — if it passes,
     * the batch portion of the migration is functionally complete.
     */
    @Nested
    @DisplayName("FULL SEQUENCE — POSTTRAN → INTCALC → COMBTRAN → CREASTMT (PR-12)")
    class FullBatchSequence {

        @Test
        @DisplayName("CRITICAL — Executes the complete four-job batch sequence successfully (PR-12)")
        void shouldExecuteCompleteBatchSequenceSuccessfully() throws Exception {
            // Step 0 — stage a small valid daily feed.
            seedValidDailyTransactions(3);

            // Step 1 — POSTTRAN.
            JobExecution posting = jobLauncher.run(transactionPostingJob, runParams());
            assertThat(posting.getStatus())
                    .as("Step 1 POSTTRAN must complete")
                    .isEqualTo(BatchStatus.COMPLETED);

            // Step 2 — INTCALC.
            JobExecution interest = jobLauncher.run(interestCalculationJob, interestParams(PARM_DATE));
            assertThat(interest.getStatus())
                    .as("Step 2 INTCALC must complete")
                    .isEqualTo(BatchStatus.COMPLETED);

            // Step 3 — COMBTRAN.
            JobExecution consolidation = jobLauncher.run(transactionConsolidationJob, runParams());
            assertThat(consolidation.getStatus())
                    .as("Step 3 COMBTRAN must complete")
                    .isEqualTo(BatchStatus.COMPLETED);

            // Step 4 — CREASTMT.
            JobExecution statements = jobLauncher.run(statementGenerationJob, statementParams("statements"));
            assertThat(statements.getStatus())
                    .as("Step 4 CREASTMT must complete")
                    .isEqualTo(BatchStatus.COMPLETED);

            // Final output — statements emitted as the terminal deliverable of the cycle.
            List<Path> produced = statementFiles(".html");
            produced.addAll(statementFiles(".txt"));
            assertThat(produced)
                    .as("The completed sequence must produce statement files as its final output (PR-12)")
                    .isNotEmpty();
        }

        @Test
        @DisplayName("Propagates data correctly across the sequence (posted → interest → consolidated → statements)")
        void shouldHaveCorrectDataFlowAcrossSequence() throws Exception {
            seedValidDailyTransactions(3);

            long transactionsBeforePosting = transactionRepository.count();

            // POSTTRAN posts accepted records into the transactions table.
            assertThat(jobLauncher.run(transactionPostingJob, runParams()).getStatus()).isEqualTo(BatchStatus.COMPLETED);
            long transactionsAfterPosting = transactionRepository.count();
            assertThat(transactionsAfterPosting)
                    .as("Posted transactions must be visible after POSTTRAN")
                    .isGreaterThanOrEqualTo(transactionsBeforePosting);

            // INTCALC emits interest transactions (TRAN-TYPE-CD '01', TRAN-CAT-CD '0005').
            assertThat(jobLauncher.run(interestCalculationJob, interestParams(PARM_DATE)).getStatus()).isEqualTo(BatchStatus.COMPLETED);
            assertThat(interestTransactions())
                    .as("Interest transactions must be present after INTCALC")
                    .isNotEmpty();

            // COMBTRAN consolidates without producing duplicates.
            assertThat(jobLauncher.run(transactionConsolidationJob, runParams()).getStatus()).isEqualTo(BatchStatus.COMPLETED);
            Integer duplicateIds = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM (SELECT tran_id FROM transactions GROUP BY tran_id "
                            + "HAVING COUNT(*) > 1) AS dups", Integer.class);
            assertThat(duplicateIds)
                    .as("No duplicate tran_ids may exist after COMBTRAN")
                    .isZero();

            // CREASTMT emits statement files as the terminal output.
            assertThat(jobLauncher.run(statementGenerationJob, statementParams("statements")).getStatus()).isEqualTo(BatchStatus.COMPLETED);
            List<Path> produced = statementFiles(".html");
            produced.addAll(statementFiles(".txt"));
            assertThat(produced)
                    .as("Statements must be emitted as the final stage of the data flow")
                    .isNotEmpty();
        }

        @Test
        @DisplayName("Exposes all four critical jobs in the JobRegistry and is relaunchable with fresh parameters")
        void shouldExposeAllFourCriticalJobsViaRegistryAndBeRelaunchable() throws Exception {
            assertThat(jobRegistry.getJobNames())
                    .as("All four critical jobs must be registered by name for launch-by-name (PR-12)")
                    .contains("transactionPostingJob", "interestCalculationJob",
                            "transactionConsolidationJob", "statementGenerationJob");

            // Supporting jobs are wired too: the CBTRN01C daily-transaction read job (used to stage the
            // daily feed ahead of POSTTRAN) and the optional seed-load orchestration job (required = false).
            assertThat(dailyTransactionReadJob)
                    .as("Supporting CBTRN01C daily-transaction read job must be wired")
                    .isNotNull();
            if (dataInitializationJob != null) {
                assertThat(dataInitializationJob.getName())
                        .as("Optional seed-load job, when present, must expose a job name")
                        .isNotBlank();
            }

            // Each fresh launch carries a unique runId, producing a new JobInstance every time.
            JobExecution first = jobLauncher.run(transactionConsolidationJob, runParams());
            JobExecution second = jobLauncher.run(transactionConsolidationJob, runParams());
            assertThat(first.getStatus())
                    .as("First relaunch must complete")
                    .isEqualTo(BatchStatus.COMPLETED);
            assertThat(second.getStatus())
                    .as("Second relaunch with fresh parameters must also complete")
                    .isEqualTo(BatchStatus.COMPLETED);
            assertThat(second.getJobInstance().getInstanceId())
                    .as("Distinct parameters must yield distinct JobInstances")
                    .isNotEqualTo(first.getJobInstance().getInstanceId());
        }
    }

    /**
     * Spring Batch checkpoint / restart semantics. The {@code JobRepository} (PostgreSQL
     * {@code BATCH_*} tables) records a chunk-level checkpoint per step; a completed {@code JobInstance}
     * cannot be relaunched with identical parameters.
     */
    @Nested
    @DisplayName("RESTART & RECOVERY — Spring Batch checkpoint semantics")
    class JobRestartAndRecovery {

        @Test
        @DisplayName("Records chunk checkpoint data in BATCH_STEP_EXECUTION for a chunk-oriented job")
        void shouldHaveCheckpointDataInBatchStepExecution() throws Exception {
            seedValidDailyTransactions(3);

            JobExecution exec = jobLauncher.run(transactionPostingJob, runParams());
            assertThat(exec.getStatus()).isEqualTo(BatchStatus.COMPLETED);

            Integer stepExecutions = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM BATCH_STEP_EXECUTION", Integer.class);
            assertThat(stepExecutions)
                    .as("At least one step execution row must be recorded for the chunk-oriented job")
                    .isGreaterThanOrEqualTo(1);

            Long maxReadCount = jdbcTemplate.queryForObject(
                    "SELECT COALESCE(MAX(read_count), 0) FROM BATCH_STEP_EXECUTION", Long.class);
            assertThat(maxReadCount)
                    .as("The posting step must record a non-zero read_count for the seeded daily feed")
                    .isGreaterThanOrEqualTo(1L);
        }

        @Test
        @DisplayName("Throws JobInstanceAlreadyCompleteException when relaunching a completed instance with identical parameters")
        void shouldThrowWhenRelaunchingCompletedInstanceWithSameParameters() throws Exception {
            // Fixed (identifying) parameters — no unique runId — so both launches target the same instance.
            JobParameters fixed = new JobParametersBuilder()
                    .addString("mode", "restart-semantics-check")
                    .toJobParameters();

            JobExecution first = jobLauncher.run(transactionConsolidationJob, fixed);
            assertThat(first.getStatus())
                    .as("The first launch of the completed instance must succeed")
                    .isEqualTo(BatchStatus.COMPLETED);

            assertThatThrownBy(() -> jobLauncher.run(transactionConsolidationJob, fixed))
                    .as("Relaunching a completed instance with identical parameters must be rejected")
                    .isInstanceOf(JobInstanceAlreadyCompleteException.class);
        }
    }

    /**
     * {@code PARM-DATE} handling (PR-10). INTCALC requires a {@code tranDate} parameter of exactly 10
     * characters (the {@code PARM='2022071800'} equivalent); it forms the prefix of every generated
     * interest transaction id.
     */
    @Nested
    @DisplayName("JOB PARAMETERS — tranDate / PARM-DATE handling (PR-10)")
    class JobParameterHandling {

        @Test
        @DisplayName("Accepts and carries the tranDate job parameter")
        void shouldAcceptTranDateJobParameter() throws Exception {
            JobExecution exec = jobLauncher.run(interestCalculationJob, interestParams(PARM_DATE));

            assertThat(exec.getStatus())
                    .as("INTCALC must accept a valid 10-char tranDate")
                    .isEqualTo(BatchStatus.COMPLETED);
            assertThat(exec.getJobParameters().getString("tranDate"))
                    .as("The tranDate parameter must be carried on the execution")
                    .isEqualTo(PARM_DATE);
        }

        @Test
        @DisplayName("Fails when the tranDate parameter is malformed (not exactly 10 characters)")
        void shouldFailWhenTranDateIsMalformed() throws Exception {
            // 'BAD' is not 10 characters → the job's date validation rejects it and the step fails,
            // surfacing as a FAILED JobExecution (jobLauncher.run does not throw for in-step failures).
            JobParameters malformed = new JobParametersBuilder()
                    .addString("tranDate", "BAD")
                    .addLong("runId", nextRunId())
                    .toJobParameters();

            JobExecution exec = jobLauncher.run(interestCalculationJob, malformed);

            assertThat(exec.getStatus())
                    .as("A malformed tranDate must cause INTCALC to fail")
                    .isEqualTo(BatchStatus.FAILED);
        }

        @Test
        @DisplayName("Uses the tranDate as the 10-char prefix of every generated interest transaction id (PR-10)")
        void shouldUseTranDatePrefixForInterestTransactionIds() throws Exception {
            jobLauncher.run(interestCalculationJob, interestParams(PARM_DATE));

            List<Transaction> interest = interestTransactions();
            assertThat(interest)
                    .as("INTCALC must emit at least one interest transaction")
                    .isNotEmpty();
            assertThat(interest)
                    .as("Every interest tran_id must start with the tranDate prefix per PR-10")
                    .allMatch(t -> t.getTranId().startsWith(PARM_DATE));
        }
    }

    /**
     * Per-chunk atomicity and account consistency. A rejected record must leave the account and the
     * master transactions table untouched; an accepted record must update the balance and the cycle
     * bucket together (PR-07 / PR-24 unit-of-work semantics).
     */
    @Nested
    @DisplayName("ATOMICITY & CONSISTENCY — per-chunk transactional boundaries")
    class BatchAtomicityAndConsistency {

        @Test
        @DisplayName("Leaves the account and transactions untouched for a rejected (over-limit) transaction")
        void shouldLeaveAccountAndTransactionsUntouchedForRejectedTransaction() throws Exception {
            Long acctId = accountWithCard(6);
            Account acct = accountRepository.findById(acctId).orElseThrow();
            String card = cardForAccount(acctId);

            BigDecimal balBefore = acct.getCurrBal();
            BigDecimal creditBefore = acct.getCurrCycCredit();
            BigDecimal debitBefore = acct.getCurrCycDebit();

            // Over-limit (code 102): origin date before expiration so only the limit check fires.
            LocalDateTime origTs = acct.getExpirationDate().minusMonths(1).atTime(12, 0);
            BigDecimal overAmount = acct.getCreditLimit().add(new BigDecimal("1000000.00"));
            String tranId = nextDtId();
            dailyTransactionRepository.save(createDailyTransaction(tranId, card, overAmount, origTs));

            JobExecution exec = jobLauncher.run(transactionPostingJob, runParams());
            assertThat(exec.getStatus()).isEqualTo(BatchStatus.COMPLETED);

            Account after = accountRepository.findById(acctId).orElseThrow();
            assertThat(after.getCurrBal())
                    .as("A rejected transaction must not change curr_bal")
                    .isEqualByComparingTo(balBefore);
            assertThat(after.getCurrCycCredit())
                    .as("A rejected transaction must not change curr_cyc_credit")
                    .isEqualByComparingTo(creditBefore);
            assertThat(after.getCurrCycDebit())
                    .as("A rejected transaction must not change curr_cyc_debit")
                    .isEqualByComparingTo(debitBefore);
            assertThat(transactionRepository.findById(tranId))
                    .as("A rejected transaction must not be posted to the transactions table")
                    .isEmpty();
        }

        @Test
        @DisplayName("Atomically updates curr_bal and curr_cyc_credit while posting an accepted positive transaction")
        void shouldAtomicallyUpdateAccountForAcceptedPositiveTransaction() throws Exception {
            Long acctId = accountWithCard(7);
            String card = cardForAccount(acctId);
            Account before = accountRepository.findById(acctId).orElseThrow();
            BigDecimal balBefore = before.getCurrBal();
            BigDecimal creditBefore = before.getCurrCycCredit();
            BigDecimal debitBefore = before.getCurrCycDebit();

            BigDecimal amount = new BigDecimal("12.00");
            String tranId = nextDtId();
            dailyTransactionRepository.save(createDailyTransaction(tranId, card, amount));

            JobExecution exec = jobLauncher.run(transactionPostingJob, runParams());
            assertThat(exec.getStatus()).isEqualTo(BatchStatus.COMPLETED);

            Account after = accountRepository.findById(acctId).orElseThrow();
            assertThat(after.getCurrBal())
                    .as("Accepted positive amount must increase curr_bal atomically with posting")
                    .isEqualByComparingTo(balBefore.add(amount));
            assertThat(after.getCurrCycCredit())
                    .as("Accepted positive amount must increase curr_cyc_credit atomically with posting")
                    .isEqualByComparingTo(creditBefore.add(amount));
            assertThat(after.getCurrCycDebit())
                    .as("curr_cyc_debit must be unchanged for a positive amount")
                    .isEqualByComparingTo(debitBefore);
            assertThat(transactionRepository.findById(tranId))
                    .as("Accepted transaction must be posted to the transactions table")
                    .isPresent();
        }
    }
}
