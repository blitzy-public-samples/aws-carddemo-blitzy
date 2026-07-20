package com.aws.carddemo.batch;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
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
import org.springframework.batch.core.StepExecution;
import org.springframework.batch.core.launch.JobLauncher;
import org.springframework.batch.core.repository.JobInstanceAlreadyCompleteException;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.test.JobLauncherTestUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;

import com.aws.carddemo.AbstractPostgresIntegrationTest;
import com.aws.carddemo.config.BatchConfig;
import com.aws.carddemo.domain.Account;
import com.aws.carddemo.domain.CardXref;
import com.aws.carddemo.domain.Transaction;
import com.aws.carddemo.domain.TransactionCategoryBalance.TransactionCategoryBalanceId;
import com.aws.carddemo.repository.AccountRepository;
import com.aws.carddemo.repository.CardXrefRepository;
import com.aws.carddemo.repository.TransactionCategoryBalanceRepository;
import com.aws.carddemo.repository.TransactionRepository;

/**
 * Spring Batch integration / parity test for {@link PostTransactionJobConfig} &mdash; the daily
 * transaction posting job.
 *
 * <p><strong>Origin (traceability, AAP &sect;0.6.10):</strong> this is the canonical chunk-job
 * parity oracle. It verifies that the {@code postTransactionJob} faithfully reproduces the behaviour
 * of COBOL batch program {@code legacy/cbl/CBTRN02C.cbl} ("Post the records from daily transaction
 * file"), orchestrated by JCL {@code legacy/jcl/POSTTRAN.jcl} (step {@code STEP15}), with the
 * 430-byte reject-file and generation-data-group semantics of {@code legacy/jcl/DALYREJS.jcl}. The
 * 350-byte {@code DALYTRAN} input fixtures follow the {@code CVTRA06Y} record layout and the seed
 * feed {@code legacy/data/ASCII/dailytran.txt} (300 records &times; 350 bytes).</p>
 *
 * <p><strong>COBOL paragraphs exercised:</strong> {@code 1500-A-LOOKUP-XREF} (unknown card &rarr;
 * reason 100), {@code 1500-B-LOOKUP-ACCT} (unknown account &rarr; reason 101, over-limit &rarr;
 * reason 102, expired &rarr; reason 103, and the {@code 103-wins} quirk where two <em>separate</em>
 * sequential {@code IF}s let the expiration reason overwrite the over-limit reason), {@code 2000}
 * (post transaction), {@code 2500-WRITE-REJECT-REC} (430-byte reject line), {@code 2700-UPDATE-TCATBAL}
 * (found-or-created category balance, tolerating the not-found status 23), {@code 2800-UPDATE-ACCOUNT-REC}
 * (balance + cycle credit/debit mutation) and {@code 2900-WRITE-TRANSACTION-FILE}.</p>
 *
 * <p><strong>Context slice:</strong> this IT extends {@link AbstractPostgresIntegrationTest} to inherit
 * its real Testcontainers PostgreSQL, the {@code "test"} profile and the full Flyway migration chain
 * (V0 Spring Batch metadata &rarr; V1 schema &rarr; V2 reference/seed data &rarr; V3 indexes), but it
 * overrides {@code @SpringBootTest(classes = ...)} to pin the context to a deliberately narrow batch
 * slice&nbsp;&mdash;&nbsp;{@link BatchTestConfig} ({@code @SpringBootConfiguration} +
 * {@code @EnableAutoConfiguration} + {@code @EntityScan} over {@code com.aws.carddemo.domain} +
 * {@code @EnableJpaRepositories} over {@code com.aws.carddemo.repository} +
 * {@code @Import(PostTransactionJobConfig.class)}). The slice loads exactly the DataSource, JPA,
 * Spring Batch and the single job-under-test, and pointedly does <em>not</em> component-scan
 * {@code com.aws.carddemo}. This is deliberate on two counts: (1) it avoids the package-local
 * {@code @SpringBootConfiguration} declared by a sibling batch {@code *IT}, which Spring's
 * {@code AnnotatedClassFinder} would otherwise pick up for any {@code @SpringBootTest} in this package
 * that leaves its configuration implicit; and (2) it keeps the online web/service/security beans out of
 * the context, so a batch parity test never depends on collaborators wired by unrelated online-tier
 * migration artifacts. Only {@code postTransactionJob} is present, so no multi-{@code Job} ambiguity
 * can arise.</p>
 *
 * <p><strong>Harness ({@code JobLauncherTestUtils}):</strong> {@code @SpringBatchTest} is deliberately
 * <em>not</em> used; its auto-registered {@link JobLauncherTestUtils} performs an
 * {@code @Autowired(required = false) setJob(Job)} that raises {@code NoUniqueBeanDefinitionException}
 * whenever more than one {@code Job} bean is visible. The nested {@link HarnessConfig}
 * {@code @TestConfiguration} instead supplies exactly one {@link JobLauncherTestUtils}, wired via
 * explicit setters with the {@code @Qualifier("postTransactionJob")} job and Spring Boot's
 * auto-configured {@link JobLauncher} / {@link JobRepository} (production omits
 * {@code @EnableBatchProcessing}, so Boot supplies the persistent JDBC job repository backed by the
 * Flyway&nbsp;V0 {@code BATCH_*} tables).</p>
 *
 * <p><strong>Schema authority:</strong> Flyway (V0&ndash;V3) remains the single owner of the DDL and
 * seed data in the container, exactly as the base harness intends. Hibernate {@code ddl-auto} is set
 * to {@code none} for this test so that Hibernate does not additionally run its strict startup
 * schema-<em>validation</em> pass: the migration schema deliberately uses fixed-width {@code CHAR(n)}
 * columns for trailing-space parity (AAP &sect;0.6.2), whereas the JPA {@code String} mappings resolve
 * to {@code varchar}, a purely cosmetic type difference that is orthogonal to the posting behaviour
 * under test. Runtime reads/writes map {@code CHAR}&harr;{@code String} without issue.</p>
 *
 * <p><strong>Money:</strong> every monetary assertion uses {@link BigDecimal} value comparison
 * ({@code isEqualByComparingTo}); no {@code double}/{@code float} is used anywhere (AAP &sect;0.6.1).
 * <strong>Byte parity:</strong> reject records are asserted at exactly 430 bytes &mdash; the verbatim
 * 350-byte {@code DALYTRAN} prefix plus a 4-digit reason code and 76-char description trailer (AAP
 * &sect;0.6.4/&sect;0.6.5).</p>
 */
// Non-web batch parity slice: WebEnvironment.NONE suppresses the servlet security auto-config
// generated dev-password WARN, and disabling Prometheus export lets the slice fall back to a
// SimpleMeterRegistry so Spring Batch's duplicate spring.batch.job.active meter never trips the
// Prometheus same-tag-keys collision WARN — keeps start logs warning-free (review finding #35).
@SpringBootTest(
        classes = {PostTransactionJobConfigIT.BatchTestConfig.class,
                PostTransactionJobConfigIT.HarnessConfig.class},
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = {
            "spring.jpa.hibernate.ddl-auto=none",
            "management.prometheus.metrics.export.enabled=false"
        })
class PostTransactionJobConfigIT extends AbstractPostgresIntegrationTest {

    /**
     * Narrow batch context slice for this integration test. Enables Spring Boot auto-configuration
     * (DataSource, JPA and Spring Batch), scans the migration entities under
     * {@code com.aws.carddemo.domain} and the Spring Data repositories under
     * {@code com.aws.carddemo.repository}, and imports only {@link PostTransactionJobConfig} (the single
     * job-under-test). It intentionally does <em>not</em> component-scan {@code com.aws.carddemo}, so the
     * online web/service/security tier is excluded and cannot drag in collaborators contributed by other
     * migration artifacts. {@link AbstractPostgresIntegrationTest} still supplies the Testcontainers
     * PostgreSQL, the {@code "test"} profile and the Flyway V0&ndash;V3 schema + seed at runtime; the
     * class-level {@code spring.jpa.hibernate.ddl-auto=none} override leaves Flyway as the sole schema
     * authority and skips Hibernate's startup {@code CHAR}/{@code varchar} validation pass.
     */
    @SpringBootConfiguration
    @EnableAutoConfiguration
    @EntityScan(basePackageClasses = Account.class)
    @EnableJpaRepositories(basePackageClasses = AccountRepository.class)
    @Import({PostTransactionJobConfig.class, BatchConfig.class})
    static class BatchTestConfig {
    }

    /**
     * Supplies exactly one {@link JobLauncherTestUtils} bound to {@code postTransactionJob}, resolving
     * the multiple-{@code Job}-bean ambiguity without {@code @SpringBatchTest}. The setters are invoked
     * explicitly (no field injection), so no {@code @Autowired} single-{@code Job} resolution fires.
     */
    @TestConfiguration
    static class HarnessConfig {

        /**
         * Builds the single job-launcher test utility for the posting job.
         *
         * @param jobLauncher   Spring Boot auto-configured launcher
         * @param jobRepository Spring Boot auto-configured persistent JDBC repository
         * @param job           the qualified {@code postTransactionJob} bean
         * @return the wired {@link JobLauncherTestUtils}
         */
        @Bean
        JobLauncherTestUtils jobLauncherTestUtils(JobLauncher jobLauncher,
                JobRepository jobRepository,
                @Qualifier("postTransactionJob") Job job) {
            JobLauncherTestUtils utils = new JobLauncherTestUtils();
            utils.setJobLauncher(jobLauncher);
            utils.setJobRepository(jobRepository);
            utils.setJob(job);
            return utils;
        }
    }

    @Autowired
    private JobLauncherTestUtils jobLauncherTestUtils;

    @Autowired
    private TransactionRepository transactionRepository;

    @Autowired
    private AccountRepository accountRepository;

    @Autowired
    private TransactionCategoryBalanceRepository categoryBalanceRepository;

    @Autowired
    private CardXrefRepository cardXrefRepository;

    @TempDir
    private Path tempDir;

    // ------------------------------------------------------------------------------------------------
    // Fixture helpers
    // ------------------------------------------------------------------------------------------------

    /** A distant future expiration so the account is never treated as expired. */
    private static final LocalDate FUTURE_EXPIRY = LocalDate.of(2099, 12, 31);

    /** A past expiration so the account is treated as expired for any modern origin date. */
    private static final LocalDate PAST_EXPIRY = LocalDate.of(2000, 1, 1);

    /** A modern origin timestamp (matches the {@code CVTRA06Y} 26-char {@code DALYTRAN-ORIG-TS}). */
    private static final String ORIG_TS = "2024-01-01 12:00:00.000000";

    /** A modern processing timestamp (26 chars). */
    private static final String PROC_TS = "2024-01-02 03:04:05.000000";

    /**
     * Builds a scale-2 {@link BigDecimal} money value from a decimal string. Kept explicit (never
     * {@code double}) so every monetary fixture and assertion is exact.
     *
     * @param value a decimal literal such as {@code "100.00"} or {@code "-50.00"}
     * @return the parsed {@link BigDecimal}
     */
    private static BigDecimal money(String value) {
        return new BigDecimal(value);
    }

    /**
     * Persists an {@link Account} fixture with zeroed balances/cycle totals and the supplied credit
     * limit and expiration date. Only the primary key is mandatory in the flat, foreign-key-free
     * schema; the financial fields drive the posting validation and mutation logic.
     *
     * @param acctId      the account primary key ({@code ACCT-ID})
     * @param creditLimit the credit limit used by the over-limit test ({@code ACCT-CREDIT-LIMIT})
     * @param expiration  the expiration date used by the expiration test ({@code ACCT-EXPIRAION-DATE})
     */
    private void persistAccount(long acctId, BigDecimal creditLimit, LocalDate expiration) {
        Account account = new Account();
        account.setAcctId(acctId);
        account.setActiveStatus("Y");
        account.setCurrBal(money("0.00"));
        account.setCreditLimit(creditLimit);
        account.setCashCreditLimit(money("0.00"));
        account.setCurrCycCredit(money("0.00"));
        account.setCurrCycDebit(money("0.00"));
        account.setOpenDate(LocalDate.of(2020, 1, 1));
        account.setExpiraionDate(expiration);
        accountRepository.save(account);
    }

    /**
     * Persists a {@link CardXref} row mapping a card number to a customer and account, reproducing the
     * {@code CCXREF} keyed read used by {@code 1500-A-LOOKUP-XREF}.
     *
     * @param cardNum the 16-digit card number ({@code XREF-CARD-NUM})
     * @param custId  the customer id ({@code XREF-CUST-ID})
     * @param acctId  the account id ({@code XREF-ACCT-ID})
     */
    private void persistXref(String cardNum, long custId, long acctId) {
        cardXrefRepository.save(new CardXref(cardNum, custId, acctId));
    }

    /**
     * Builds one byte-exact 350-byte {@code DALYTRAN-RECORD} line by reusing the production
     * {@link PostTransactionJobConfig#DALYTRAN_MAPPER}, guaranteeing the fixture is encoded exactly as
     * the reader parses it and as {@code 2500-WRITE-REJECT-REC} reconstructs the reject prefix.
     *
     * @param id      the 16-char transaction id ({@code DALYTRAN-ID})
     * @param typeCd  the 2-char transaction type code ({@code DALYTRAN-TYPE-CD})
     * @param catCd   the transaction category code ({@code DALYTRAN-CAT-CD})
     * @param amount  the signed decimal amount ({@code DALYTRAN-AMT}); scale 2
     * @param cardNum the 16-digit card number ({@code DALYTRAN-CARD-NUM})
     * @param origTs  the 26-char origin timestamp ({@code DALYTRAN-ORIG-TS})
     * @return the 350-character record encoded in ISO-8859-1
     */
    private static String dalytran(String id, String typeCd, int catCd, BigDecimal amount,
            String cardNum, String origTs) {
        var builder = PostTransactionJobConfig.DALYTRAN_MAPPER.newRecord();
        builder.setText("id", id);
        builder.setText("typeCd", typeCd);
        builder.setNumeric("catCd", catCd);
        builder.setText("source", "SYSTEM");
        builder.setText("description", "IT POSTING FIXTURE");
        builder.setSignedDecimal("amount", amount);
        builder.setNumeric("merchantId", 123456789L);
        builder.setText("merchantName", "IT MERCHANT");
        builder.setText("merchantCity", "IT CITY");
        builder.setText("merchantZip", "00000");
        builder.setText("cardNum", cardNum);
        builder.setText("origTs", origTs);
        builder.setText("procTs", PROC_TS);
        return new String(builder.build(), StandardCharsets.ISO_8859_1);
    }

    /**
     * Writes the supplied 350-byte records to a feed file inside the per-test {@link TempDir} in
     * <em>undelimited</em> {@code RECFM=FB} framing (review finding&nbsp;#17): the records are written
     * back-to-back with <strong>no</strong> line delimiter, exactly as the native z/OS
     * {@code DALYTRAN.PS} dataset stores them ({@code LRECL=350,RECFM=FB}) and exactly as the production
     * {@link com.aws.carddemo.util.batch.FixedLengthItemReader} consumes them &mdash; it slices the
     * stream into fixed 350-byte blocks and makes no newline assumption. (The LF-delimited ASCII fixture
     * {@code legacy/data/ASCII/dailytran.txt} is a convenience form only; AAP&nbsp;&sect;0.6.6.) Every
     * record supplied by {@link #dalytran} is byte-exactly 350 characters, so the concatenation aligns
     * on 350-byte boundaries.
     *
     * @param fileName the temp file name
     * @param lines    the fixed-width 350-byte records
     * @return the path to the written feed
     * @throws IOException if the file cannot be written
     */
    private Path writeFeed(String fileName, List<String> lines) throws IOException {
        Path feed = tempDir.resolve(fileName);
        StringBuilder content = new StringBuilder();
        for (String line : lines) {
            content.append(line);
        }
        Files.write(feed, content.toString().getBytes(StandardCharsets.ISO_8859_1));
        return feed;
    }

    /**
     * Launches {@code postTransactionJob} with unique identifying parameters (so every launch is a
     * fresh {@link org.springframework.batch.core.JobInstance}), passing the required {@code inputPath}
     * and the {@code rejectPath} so the produced reject file location is known to the assertions.
     *
     * @param feed       the DALYTRAN input feed path
     * @param rejectFile the desired reject-file path
     * @return the completed {@link JobExecution}
     * @throws Exception if the launch fails
     */
    private JobExecution launch(Path feed, Path rejectFile) throws Exception {
        JobParameters params = new JobParametersBuilder()
                .addString("inputPath", feed.toString())
                .addString("rejectPath", rejectFile.toString())
                .addLong("run.id", System.nanoTime())
                .toJobParameters();
        return jobLauncherTestUtils.launchJob(params);
    }

    /**
     * Returns the {@code postTransactionStep} execution from a completed job execution.
     *
     * @param execution the job execution
     * @return the posting {@link StepExecution}
     */
    private static StepExecution postingStep(JobExecution execution) {
        return execution.getStepExecutions().stream()
                .filter(step -> PostTransactionJobConfig.STEP_NAME.equals(step.getStepName()))
                .findFirst()
                .orElseThrow(() -> new AssertionError(
                        "posting step '" + PostTransactionJobConfig.STEP_NAME + "' not found"));
    }

    /**
     * Reads the accumulated reject count from the posting step's execution context.
     *
     * @param execution the job execution
     * @return the number of rejected records
     */
    private static int rejectCount(JobExecution execution) {
        return postingStep(execution).getExecutionContext()
                .getInt(PostTransactionJobConfig.REJECT_COUNT_KEY, 0);
    }

    /**
     * Reads the reject file and slices it into fixed 430-byte records in <em>undelimited</em>
     * {@code RECFM=FB} framing (review finding&nbsp;#17). The {@code DALYREJS} reject writer emits each
     * 430-byte record (350-byte {@code DALYTRAN} prefix + 80-byte trailer) back-to-back with no
     * delimiter, matching the native reject dataset, so the file is parsed by fixed width using the
     * authoritative {@link PostTransactionJobConfig#REJECT_MAPPER} record length rather than by newline.
     * An absent or empty file yields an empty list; a non-empty file whose length is not a whole
     * multiple of the record width fails fast (proving the framing carries no stray delimiter bytes).
     *
     * @param rejectFile the reject-file path
     * @return the reject records (each exactly 430 characters, decoded with ISO-8859-1)
     * @throws IOException if the file cannot be read
     */
    private static List<String> readRejectRecords(Path rejectFile) throws IOException {
        List<String> records = new ArrayList<>();
        if (!Files.exists(rejectFile)) {
            return records;
        }
        byte[] all = Files.readAllBytes(rejectFile);
        if (all.length == 0) {
            return records;
        }
        int len = PostTransactionJobConfig.REJECT_MAPPER.getRecordLength();
        assertThat(all.length % len)
                .as("undelimited RECFM=FB reject file length is a whole multiple of the 430-byte record")
                .isZero();
        for (int off = 0; off < all.length; off += len) {
            records.add(new String(all, off, len, StandardCharsets.ISO_8859_1));
        }
        return records;
    }

    /**
     * Asserts one reject record is exactly 430 bytes and carries the expected 4-digit reason code and
     * 76-char space-padded description trailer.
     *
     * @param record the reject record line
     * @param reason the expected numeric reason code
     * @param desc   the expected reason description
     */
    private static void assertRejectTrailer(String record, int reason, String desc) {
        assertThat(record).hasSize(430);
        assertThat(record.getBytes(StandardCharsets.ISO_8859_1)).hasSize(430);
        assertThat(record.substring(350, 354)).isEqualTo(String.format("%04d", reason));
        assertThat(record.substring(354, 430)).isEqualTo(padRight(desc, 76));
    }

    /**
     * Right-pads a value with spaces to a fixed width (COBOL {@code PIC X(n)} semantics).
     *
     * @param value the value
     * @param width the target width
     * @return the space-padded value
     */
    private static String padRight(String value, int width) {
        StringBuilder padded = new StringBuilder(value);
        while (padded.length() < width) {
            padded.append(' ');
        }
        return padded.toString();
    }

    /** Right-pads a transaction-id base (&le; 16 chars) to the fixed {@code X(16)} width. */
    private static String id16(String base) {
        return padRight(base, 16);
    }

    // ------------------------------------------------------------------------------------------------
    // Tests
    // ------------------------------------------------------------------------------------------------

    /**
     * Valid posting path ({@code 2000}/{@code 2700}/{@code 2800}/{@code 2900}): a positive amount adds
     * to the balance and the cycle-credit bucket, a negative amount adds to the balance and the
     * cycle-debit bucket, and a first-time category balance is created (the {@code 2700} not-found
     * status 23 is tolerated, not an error). All monetary effects are asserted with {@link BigDecimal}.
     *
     * @throws Exception if the job launch fails
     */
    @Test
    void postsValidTransactionUpdatingTransactionAccountAndCatBalance() throws Exception {
        long acctA = 900_000_001L;
        long acctB = 900_000_002L;
        String cardA = "9000000000000001";
        String cardB = "9000000000000002";
        persistAccount(acctA, money("999999.99"), FUTURE_EXPIRY);
        persistAccount(acctB, money("999999.99"), FUTURE_EXPIRY);
        persistXref(cardA, 900_000_001L, acctA);
        persistXref(cardB, 900_000_002L, acctB);

        String idA = id16("ITVALIDPOS1");
        String idB = id16("ITVALIDNEG2");
        BigDecimal amtA = money("100.00");
        BigDecimal amtB = money("-50.00");
        Path feed = writeFeed("dalytran-valid.txt", List.of(
                dalytran(idA, "01", 5, amtA, cardA, ORIG_TS),
                dalytran(idB, "01", 5, amtB, cardB, ORIG_TS)));
        Path reject = tempDir.resolve("reject-valid.txt");

        JobExecution execution = launch(feed, reject);

        assertThat(execution.getStatus()).isEqualTo(BatchStatus.COMPLETED);
        assertThat(execution.getExitStatus().getExitCode())
                .isEqualTo(ExitStatus.COMPLETED.getExitCode());
        StepExecution step = postingStep(execution);
        assertThat(step.getReadCount()).isEqualTo(2L);
        assertThat(step.getWriteCount()).isEqualTo(2L);
        assertThat(rejectCount(execution)).isZero();
        assertThat(readRejectRecords(reject)).isEmpty();

        // Positive amount (2800): balance and cycle-credit increase; cycle-debit untouched.
        var reloadedA = accountRepository.findById(acctA).orElseThrow();
        assertThat(reloadedA.getCurrBal()).isEqualByComparingTo(amtA);
        assertThat(reloadedA.getCurrCycCredit()).isEqualByComparingTo(amtA);
        assertThat(reloadedA.getCurrCycDebit()).isEqualByComparingTo(money("0.00"));

        // Negative amount (2800): balance and cycle-debit decrease; cycle-credit untouched.
        var reloadedB = accountRepository.findById(acctB).orElseThrow();
        assertThat(reloadedB.getCurrBal()).isEqualByComparingTo(amtB);
        assertThat(reloadedB.getCurrCycDebit()).isEqualByComparingTo(amtB);
        assertThat(reloadedB.getCurrCycCredit()).isEqualByComparingTo(money("0.00"));

        // Transaction-master rows persisted (2900).
        var txA = transactionRepository.findById(idA).orElseThrow();
        assertThat(txA.getTranAmt()).isEqualByComparingTo(amtA);
        assertThat(txA.getCardNum()).isEqualTo(cardA);
        assertThat(txA.getTranTypeCd()).isEqualTo("01");
        assertThat(txA.getTranCatCd()).isEqualTo(5);
        assertThat(transactionRepository.findById(idB)).isPresent();

        // First-time category balances created initialized to the amount (2700-A / status-23 tolerance).
        var cbA = categoryBalanceRepository.findById(new TransactionCategoryBalanceId(acctA, "01", 5))
                .orElseThrow();
        assertThat(cbA.getBalance()).isEqualByComparingTo(amtA);
        var cbB = categoryBalanceRepository.findById(new TransactionCategoryBalanceId(acctB, "01", 5))
                .orElseThrow();
        assertThat(cbB.getBalance()).isEqualByComparingTo(amtB);
    }

    /**
     * Reason 100 ({@code 1500-A}): a card absent from the cross-reference short-circuits validation
     * (no account/limit/expiration checks) and is rejected as {@code INVALID CARD NUMBER FOUND} with
     * no posting side effects.
     *
     * @throws Exception if the job launch fails
     */
    @Test
    void rejectsUnknownCardWithReason100() throws Exception {
        String absentCard = "8000000000000201";
        String id = id16("ITREJECT100");
        Path feed = writeFeed("dalytran-100.txt", List.of(
                dalytran(id, "01", 5, money("10.00"), absentCard, ORIG_TS)));
        Path reject = tempDir.resolve("reject-100.txt");

        JobExecution execution = launch(feed, reject);

        assertThat(execution.getStatus()).isEqualTo(BatchStatus.COMPLETED);
        assertThat(postingStep(execution).getReadCount()).isEqualTo(1L);
        assertThat(rejectCount(execution)).isEqualTo(1);
        assertThat(transactionRepository.findById(id)).isEmpty();

        List<String> rejects = readRejectRecords(reject);
        assertThat(rejects).hasSize(1);
        assertRejectTrailer(rejects.get(0), PostTransactionJobConfig.REASON_INVALID_CARD,
                PostTransactionJobConfig.DESC_INVALID_CARD);
    }

    /**
     * Reason 102 ({@code 1500-B} credit-limit test): with the account <em>not</em> expired, an amount
     * that pushes {@code CYC-CREDIT - CYC-DEBIT + amount} beyond {@code ACCT-CREDIT-LIMIT} is rejected
     * as {@code OVERLIMIT TRANSACTION}.
     *
     * @throws Exception if the job launch fails
     */
    @Test
    void rejectsOverLimitWithReason102() throws Exception {
        long acctId = 900_000_102L;
        String cardNum = "9000000000000102";
        persistAccount(acctId, money("100.00"), FUTURE_EXPIRY);
        persistXref(cardNum, 900_000_102L, acctId);

        String id = id16("ITOVERLIM102");
        Path feed = writeFeed("dalytran-102.txt", List.of(
                dalytran(id, "01", 5, money("500.00"), cardNum, ORIG_TS)));
        Path reject = tempDir.resolve("reject-102.txt");

        JobExecution execution = launch(feed, reject);

        assertThat(execution.getStatus()).isEqualTo(BatchStatus.COMPLETED);
        assertThat(rejectCount(execution)).isEqualTo(1);
        assertThat(transactionRepository.findById(id)).isEmpty();

        List<String> rejects = readRejectRecords(reject);
        assertThat(rejects).hasSize(1);
        assertRejectTrailer(rejects.get(0), PostTransactionJobConfig.REASON_OVERLIMIT,
                PostTransactionJobConfig.DESC_OVERLIMIT);
    }

    /**
     * Reason 103 ({@code 1500-B} expiration test): with the amount within the credit limit but the
     * account expiration date earlier than the origin date, the record is rejected as
     * {@code TRANSACTION RECEIVED AFTER ACCT EXPIRATION}.
     *
     * @throws Exception if the job launch fails
     */
    @Test
    void rejectsExpiredAccountWithReason103() throws Exception {
        long acctId = 900_000_103L;
        String cardNum = "9000000000000103";
        persistAccount(acctId, money("999999.99"), PAST_EXPIRY);
        persistXref(cardNum, 900_000_103L, acctId);

        String id = id16("ITEXPIRED103");
        Path feed = writeFeed("dalytran-103.txt", List.of(
                dalytran(id, "01", 5, money("10.00"), cardNum, ORIG_TS)));
        Path reject = tempDir.resolve("reject-103.txt");

        JobExecution execution = launch(feed, reject);

        assertThat(execution.getStatus()).isEqualTo(BatchStatus.COMPLETED);
        assertThat(rejectCount(execution)).isEqualTo(1);
        assertThat(transactionRepository.findById(id)).isEmpty();

        List<String> rejects = readRejectRecords(reject);
        assertThat(rejects).hasSize(1);
        assertRejectTrailer(rejects.get(0), PostTransactionJobConfig.REASON_EXPIRED,
                PostTransactionJobConfig.DESC_EXPIRED);
    }

    /**
     * Parity gate for the {@code 1500-B} quirk: when a record is BOTH over the credit limit AND after
     * the account expiration, COBOL evaluates two <em>separate</em> sequential {@code IF}s and assigns
     * reason 103 last, so 103 wins over 102. This test fails if the implementation is "corrected" to an
     * {@code else-if} that lets 102 win.
     *
     * @throws Exception if the job launch fails
     */
    @Test
    void whenOverLimitAndExpired_reason103WinsOver102() throws Exception {
        long acctId = 900_000_104L;
        String cardNum = "9000000000000104";
        // Small credit limit (over-limit) AND a past expiration (expired) => BOTH checks fail.
        persistAccount(acctId, money("100.00"), PAST_EXPIRY);
        persistXref(cardNum, 900_000_104L, acctId);

        String id = id16("ITQUIRK10203");
        Path feed = writeFeed("dalytran-quirk.txt", List.of(
                dalytran(id, "01", 5, money("500.00"), cardNum, ORIG_TS)));
        Path reject = tempDir.resolve("reject-quirk.txt");

        JobExecution execution = launch(feed, reject);

        assertThat(execution.getStatus()).isEqualTo(BatchStatus.COMPLETED);
        assertThat(rejectCount(execution)).isEqualTo(1);

        List<String> rejects = readRejectRecords(reject);
        assertThat(rejects).hasSize(1);
        String record = rejects.get(0);
        // 103 wins (assigned last), NOT 102.
        assertThat(record.substring(350, 354)).isEqualTo("0103");
        assertThat(record.substring(350, 354)).isNotEqualTo("0102");
        assertRejectTrailer(record, PostTransactionJobConfig.REASON_EXPIRED,
                PostTransactionJobConfig.DESC_EXPIRED);
    }

    /**
     * Byte-parity of the 430-byte {@code DALYREJS} reject records ({@code 2500}): a feed of one
     * reason-100, one reason-102 and one reason-103 record produces three reject lines, each exactly
     * 430 bytes, whose 350-byte prefix is byte-identical to the originating input record and whose
     * 80-byte trailer carries the exact reason code and description for each reason.
     *
     * @throws Exception if the job launch fails
     */
    @Test
    void rejectFileRecordsAreExactly430BytesWithReasonTrailer() throws Exception {
        long overLimitAcct = 900_000_202L;
        long expiredAcct = 900_000_203L;
        String absentCard = "8000000000000100";
        String overLimitCard = "9000000000000202";
        String expiredCard = "9000000000000203";
        persistAccount(overLimitAcct, money("100.00"), FUTURE_EXPIRY);
        persistAccount(expiredAcct, money("999999.99"), PAST_EXPIRY);
        persistXref(overLimitCard, 900_000_202L, overLimitAcct);
        persistXref(expiredCard, 900_000_203L, expiredAcct);

        String line100 = dalytran(id16("ITBP100"), "01", 5, money("10.00"), absentCard, ORIG_TS);
        String line102 = dalytran(id16("ITBP102"), "01", 5, money("500.00"), overLimitCard, ORIG_TS);
        String line103 = dalytran(id16("ITBP103"), "01", 5, money("10.00"), expiredCard, ORIG_TS);
        List<String> inputs = List.of(line100, line102, line103);
        Path feed = writeFeed("dalytran-byteparity.txt", inputs);
        Path reject = tempDir.resolve("reject-byteparity.txt");

        JobExecution execution = launch(feed, reject);

        assertThat(execution.getStatus()).isEqualTo(BatchStatus.COMPLETED);
        assertThat(postingStep(execution).getReadCount()).isEqualTo(3L);
        assertThat(rejectCount(execution)).isEqualTo(3);

        List<String> rejects = readRejectRecords(reject);
        assertThat(rejects).hasSize(3);

        // Reject order preserves input order (chunk size 1, single-threaded), so index alignment holds.
        int[] reasons = {
            PostTransactionJobConfig.REASON_INVALID_CARD,
            PostTransactionJobConfig.REASON_OVERLIMIT,
            PostTransactionJobConfig.REASON_EXPIRED
        };
        String[] descriptions = {
            PostTransactionJobConfig.DESC_INVALID_CARD,
            PostTransactionJobConfig.DESC_OVERLIMIT,
            PostTransactionJobConfig.DESC_EXPIRED
        };
        for (int i = 0; i < rejects.size(); i++) {
            String record = rejects.get(i);
            assertThat(record).hasSize(430);
            // 350-byte prefix is byte-identical to the original DALYTRAN record (MOVE DALYTRAN-RECORD).
            assertThat(record.substring(0, 350)).isEqualTo(inputs.get(i));
            assertThat(record.substring(0, 350).getBytes(StandardCharsets.ISO_8859_1))
                    .isEqualTo(inputs.get(i).getBytes(StandardCharsets.ISO_8859_1));
            assertRejectTrailer(record, reasons[i], descriptions[i]);
        }
    }

    /**
     * Exit-status mapping ({@code CBTRN02C} L229-231, {@code IF WS-REJECT-COUNT > 0 MOVE 4 TO
     * RETURN-CODE}): a run with any reject completes as {@link BatchStatus#COMPLETED} but surfaces the
     * custom exit code {@code COMPLETED_WITH_REJECTS} (maps to RETURN-CODE 4); a run with zero rejects
     * surfaces the default {@code COMPLETED} exit code.
     *
     * @throws Exception if a job launch fails
     */
    @Test
    void exitCodeIs4WhenRejectsPresent_and0WhenNone() throws Exception {
        // Case 1: a reject present -> COMPLETED status + custom exit code (RETURN-CODE 4).
        String absentCard = "8000000000000300";
        Path rejectFeed = writeFeed("dalytran-exit-reject.txt", List.of(
                dalytran(id16("ITEXITREJ"), "01", 5, money("10.00"), absentCard, ORIG_TS)));
        Path reject1 = tempDir.resolve("reject-exit-1.txt");

        JobExecution rejectRun = launch(rejectFeed, reject1);

        assertThat(rejectRun.getStatus()).isEqualTo(BatchStatus.COMPLETED);
        assertThat(rejectCount(rejectRun)).isEqualTo(1);
        assertThat(postingStep(rejectRun).getExitStatus().getExitCode())
                .isEqualTo(PostTransactionJobConfig.EXIT_STATUS_WITH_REJECTS);
        assertThat(rejectRun.getExitStatus().getExitCode())
                .isEqualTo(PostTransactionJobConfig.EXIT_STATUS_WITH_REJECTS);

        // Case 2: no rejects -> COMPLETED status + default COMPLETED exit code.
        long acctId = 900_000_301L;
        String cardNum = "9000000000000301";
        persistAccount(acctId, money("999999.99"), FUTURE_EXPIRY);
        persistXref(cardNum, 900_000_301L, acctId);
        Path validFeed = writeFeed("dalytran-exit-valid.txt", List.of(
                dalytran(id16("ITEXITOK"), "01", 5, money("25.00"), cardNum, ORIG_TS)));
        Path reject2 = tempDir.resolve("reject-exit-2.txt");

        JobExecution validRun = launch(validFeed, reject2);

        assertThat(validRun.getStatus()).isEqualTo(BatchStatus.COMPLETED);
        assertThat(rejectCount(validRun)).isZero();
        assertThat(validRun.getExitStatus().getExitCode())
                .isEqualTo(ExitStatus.COMPLETED.getExitCode());
    }

    /**
     * Duplicate-transaction-id abend parity ({@code 2900-WRITE-TRANSACTION-FILE}; CBTRN02C
     * L562-577; AAP &sect;0.6.5). The COBOL {@code WRITE} to the {@code TRANSACT} master is an
     * insert: a key that already exists returns {@code FILE STATUS '22'} (a non-{@code '00'}
     * status), which sets {@code APPL-RESULT 12} and routes to {@code 9999-ABEND-PROGRAM}
     * ({@code CALL 'CEE3ABD'} ABCODE 999) &mdash; a hard abend yielding a non-zero RETURN-CODE. A
     * naive Spring Data {@code save()} would instead <em>merge</em> onto the existing primary key
     * (a silent {@code UPDATE}), overwriting the prior transaction row <em>and</em> re-applying the
     * {@code 2800}/{@code 2700} balance mutations, producing an unreconcilable ledger (the balance
     * moved twice, one transaction row) while the job still reported success. The
     * {@code existsById} guard added to the writer restores the abend behaviour.
     *
     * <p>The feed carries one valid, non-duplicate record followed by a record whose id was
     * pre-seeded. Because {@link PostTransactionJobConfig#CHUNK_SIZE} is 1, the valid record
     * commits in its own chunk before the duplicate's chunk runs and fails &mdash; exactly the
     * legacy behaviour where records processed before the abend are already written to the
     * (non-transactional) VSAM dataset. The assertions verify:</p>
     * <ol>
     *   <li>the job ends {@link BatchStatus#FAILED} (the abend / non-zero RETURN-CODE), not
     *       {@link BatchStatus#COMPLETED}, and the duplicate is <em>not</em> a soft reject
     *       (empty {@code DALYREJS});</li>
     *   <li>the pre-existing transaction row is left unchanged &mdash; its distinctive seeded
     *       amount and fields survive, proving the writer never silently merged over it;</li>
     *   <li>the ledger is reconcilable &mdash; the valid record is posted exactly once and the
     *       duplicate's mutations are absent, so the account balance, cycle-credit and category
     *       balance reflect only the single legitimate posting (100.00, never 150.00).</li>
     * </ol>
     *
     * @throws Exception if the job launch fails
     */
    @Test
    void duplicateTransactionIdAbendsJobAndLeavesLedgerReconcilable() throws Exception {
        long acctId = 900_000_400L;
        String cardNum = "9000000000000400";
        persistAccount(acctId, money("999999.99"), FUTURE_EXPIRY);
        persistXref(cardNum, 900_000_400L, acctId);

        // Pre-seed a transaction whose id the feed's second record reuses. Its distinctive amount —
        // never produced by the feed below — is the merge sentinel: if the writer silently UPDATEs
        // the existing primary key, this value (and the other seeded fields) would change.
        String dupId = id16("ITDUPPOST");
        BigDecimal sentinelAmt = money("777.77");
        Transaction seeded = new Transaction();
        seeded.setTranId(dupId);
        seeded.setTranTypeCd("09");
        seeded.setTranCatCd(99);
        seeded.setTranSource("PRESEED");
        seeded.setTranDesc("PRE-EXISTING TRANSACTION (MERGE SENTINEL)");
        seeded.setTranAmt(sentinelAmt);
        seeded.setCardNum(cardNum);
        transactionRepository.save(seeded);

        // Feed: one valid, NON-duplicate record (commits in its own chunk), then the duplicate.
        String validId = id16("ITDUPOK");
        BigDecimal validAmt = money("100.00");
        Path feed = writeFeed("dalytran-dup.txt", List.of(
                dalytran(validId, "01", 5, validAmt, cardNum, ORIG_TS),
                dalytran(dupId, "01", 5, money("50.00"), cardNum, ORIG_TS)));
        Path reject = tempDir.resolve("reject-dup.txt");

        JobExecution execution = launch(feed, reject);

        // (1) Abend parity: the duplicate WRITE fails the job (non-zero RETURN-CODE), and is NOT a
        // soft reject (nothing written to DALYREJS).
        assertThat(execution.getStatus()).isEqualTo(BatchStatus.FAILED);
        assertThat(rejectCount(execution)).isZero();
        assertThat(readRejectRecords(reject)).isEmpty();

        // (2) No silent merge: the pre-existing row is untouched (sentinel amount + fields intact).
        Transaction afterDup = transactionRepository.findById(dupId).orElseThrow();
        assertThat(afterDup.getTranAmt()).isEqualByComparingTo(sentinelAmt);
        assertThat(afterDup.getTranSource().trim()).isEqualTo("PRESEED");
        assertThat(afterDup.getTranTypeCd()).isEqualTo("09");
        assertThat(afterDup.getTranCatCd()).isEqualTo(99);

        // (3) Reconcilable ledger: the valid record committed exactly once (its own chunk); the
        // duplicate's +50 mutation and its category balance were rolled back with the failing chunk,
        // so every figure reflects only the single legitimate posting.
        Transaction validTx = transactionRepository.findById(validId).orElseThrow();
        assertThat(validTx.getTranAmt()).isEqualByComparingTo(validAmt);
        Account reloaded = accountRepository.findById(acctId).orElseThrow();
        assertThat(reloaded.getCurrBal()).isEqualByComparingTo(validAmt);
        assertThat(reloaded.getCurrCycCredit()).isEqualByComparingTo(validAmt);
        assertThat(reloaded.getCurrCycDebit()).isEqualByComparingTo(money("0.00"));
        var catBal = categoryBalanceRepository
                .findById(new TransactionCategoryBalanceId(acctId, "01", 5)).orElseThrow();
        assertThat(catBal.getBalance()).isEqualByComparingTo(validAmt);
    }

    /**
     * Builds the identifying {@link JobParameters} for a posting launch. Reusing one instance's exact
     * parameters across two launches makes the second launch a <em>restart</em> when the first failed
     * (Spring Batch opens a new {@code JobExecution} for the same {@code JobInstance}, and the
     * {@code saveState=true} reader resumes past the already-committed items) or a rejected rerun when
     * the first completed; supplying a fresh {@code runId} yields a brand-new
     * {@link org.springframework.batch.core.JobInstance}. Review finding&nbsp;#19.
     *
     * @param feed       the DALYTRAN input feed path
     * @param rejectFile the desired reject-file path
     * @param runId      the identifying run id (same value = same instance; new value = new instance)
     * @return the identifying job parameters
     */
    private static JobParameters postingParams(Path feed, Path rejectFile, long runId) {
        return new JobParametersBuilder()
                .addString("inputPath", feed.toString())
                .addString("rejectPath", rejectFile.toString())
                .addLong("run.id", runId)
                .toJobParameters();
    }

    /**
     * Counts the {@code .inprogress} atomic-publication temp files currently present in the per-test
     * {@link org.junit.jupiter.api.io.TempDir}. {@link com.aws.carddemo.util.batch.AtomicFileStepPublisher}
     * names each in-flight output {@code <target>.<instanceId>.inprogress} and, on a COMPLETED step,
     * either moves it to its final target or removes it &mdash; so a completed run must leave zero
     * {@code .inprogress} files behind. Review finding&nbsp;#19 (cleanup).
     *
     * @return the number of {@code .inprogress} temp files under {@link #tempDir}
     * @throws IOException if the directory cannot be listed
     */
    private long inProgressTempCount() throws IOException {
        try (var entries = Files.list(tempDir)) {
            return entries.filter(p -> p.getFileName().toString().contains(".inprogress")).count();
        }
    }

    /**
     * Restart correctness + record-level exactly-once + output rollback safety (review finding&nbsp;#19).
     *
     * <p>A two-record feed is posted with {@link PostTransactionJobConfig#CHUNK_SIZE} = 1: the first
     * record commits in its own chunk; the second reuses a pre-seeded transaction id, so the writer's
     * {@code existsById} guard abends its chunk. The job therefore ends {@link BatchStatus#FAILED} with
     * exactly the first record posted. The pre-seeded conflict is then removed and the job is relaunched
     * with the <em>same identifying parameters</em>, which restarts the failed instance. The assertions
     * prove the three properties the finding demands:</p>
     * <ol>
     *   <li><strong>Rollback safety / no overwritten output:</strong> the FAILED step never publishes
     *       its final reject target ({@code !Files.exists(reject)}); the in-progress temp is retained
     *       for the restart to resume.</li>
     *   <li><strong>Restart resume + exactly-once:</strong> the restart resumes <em>past</em> the
     *       already-committed first record (the {@code saveState=true} reader repositions via
     *       {@code jumpToItem}), so it is never posted twice; the final account balance equals the sum
     *       of both records (150.00), never the first record double-counted (200.00).</li>
     *   <li><strong>Cleanup:</strong> the successful restart consumes the in-progress temp, leaving
     *       zero {@code .inprogress} files behind.</li>
     * </ol>
     *
     * @throws Exception if a job launch fails
     */
    @Test
    void failedPostingRestartsResumesAndPostsEachRecordExactlyOnce() throws Exception {
        long acctId = 900_000_410L;
        String cardNum = "9000000000000410";
        persistAccount(acctId, money("999999.99"), FUTURE_EXPIRY);
        persistXref(cardNum, 900_000_410L, acctId);

        // Deterministic mid-run failure: pre-seed a row whose id the feed's SECOND record reuses, so
        // the writer's existsById guard abends the duplicate's chunk AFTER the first record's chunk
        // has already committed (CHUNK_SIZE = 1).
        String recAId = id16("ITRSTOK");
        String recBId = id16("ITRSTDUP");
        BigDecimal amtA = money("100.00");
        BigDecimal amtB = money("50.00");
        Transaction conflict = new Transaction();
        conflict.setTranId(recBId);
        conflict.setTranTypeCd("09");
        conflict.setTranCatCd(99);
        conflict.setTranSource("PRESEED");
        conflict.setTranDesc("PRE-EXISTING (RESTART CONFLICT)");
        conflict.setTranAmt(money("777.77"));
        conflict.setCardNum(cardNum);
        transactionRepository.save(conflict);

        Path feed = writeFeed("dalytran-restart.txt", List.of(
                dalytran(recAId, "01", 5, amtA, cardNum, ORIG_TS),
                dalytran(recBId, "01", 5, amtB, cardNum, ORIG_TS)));
        Path reject = tempDir.resolve("reject-restart.txt");
        JobParameters params = postingParams(feed, reject, 4_100L);

        // Launch #1: record A commits in its own chunk; record B abends the step.
        JobExecution first = jobLauncherTestUtils.launchJob(params);
        assertThat(first.getStatus()).isEqualTo(BatchStatus.FAILED);
        assertThat(transactionRepository.findById(recAId)).isPresent();
        assertThat(accountRepository.findById(acctId).orElseThrow().getCurrBal())
                .isEqualByComparingTo(amtA);

        // Rollback safety: the FAILED step publishes NO final reject output (never overwrites).
        assertThat(Files.exists(reject))
                .as("a failed step must not publish its final reject target")
                .isFalse();

        // Remove the conflict so the restart's record B can post.
        transactionRepository.deleteById(recBId);

        // Launch #2 with the SAME identifying parameters => restart of the failed instance.
        JobExecution second = jobLauncherTestUtils.launchJob(params);
        assertThat(second.getStatus()).isEqualTo(BatchStatus.COMPLETED);

        // Exactly-once: A posted once + B posted once => 150.00; A is NOT reprocessed on restart.
        assertThat(accountRepository.findById(acctId).orElseThrow().getCurrBal())
                .as("restart must resume past the committed record A, never double-post it")
                .isEqualByComparingTo(money("150.00"));
        assertThat(transactionRepository.findById(recBId).orElseThrow().getTranAmt())
                .as("record B posts from the feed (50.00), not the removed 777.77 sentinel")
                .isEqualByComparingTo(amtB);

        // Cleanup: the completed restart leaves no in-progress temp files behind.
        assertThat(inProgressTempCount())
                .as("a completed run consumes every .inprogress temp")
                .isZero();
    }

    /**
     * Completed-instance rerun protection (review finding&nbsp;#19). Once the posting job completes for
     * a given set of identifying parameters, relaunching with those <em>same</em> parameters must be
     * rejected with {@link JobInstanceAlreadyCompleteException} rather than silently reprocessing the
     * feed &mdash; the Spring Batch exactly-once guard that stops an operator double-posting a daily
     * file by rerunning the identical instance.
     *
     * @throws Exception if the initial (successful) launch fails
     */
    @Test
    void rerunningCompletedPostingInstanceIsRejected() throws Exception {
        long acctId = 900_000_420L;
        String cardNum = "9000000000000420";
        persistAccount(acctId, money("999999.99"), FUTURE_EXPIRY);
        persistXref(cardNum, 900_000_420L, acctId);

        Path feed = writeFeed("dalytran-rerun.txt", List.of(
                dalytran(id16("ITRERUN1"), "01", 5, money("100.00"), cardNum, ORIG_TS)));
        Path reject = tempDir.resolve("reject-rerun.txt");
        JobParameters params = postingParams(feed, reject, 4_200L);

        assertThat(jobLauncherTestUtils.launchJob(params).getStatus())
                .isEqualTo(BatchStatus.COMPLETED);

        // Relaunching the SAME completed instance is refused (no second, duplicate posting run).
        assertThatThrownBy(() -> jobLauncherTestUtils.launchJob(params))
                .isInstanceOf(JobInstanceAlreadyCompleteException.class);
    }

    /**
     * Cross-instance duplicate-effect protection (review finding&nbsp;#19). Re-posting the same feed as
     * a brand-new {@link org.springframework.batch.core.JobInstance} (a fresh {@code runId} &mdash; e.g.
     * an operator resubmitting yesterday's file) must not silently double-post: the transaction primary
     * key already exists from the first run, so the writer's {@code existsById} guard abends the second
     * run and the ledger is left untouched. This proves exactly-once is enforced at the data layer even
     * when the per-instance batch guard does not apply.
     *
     * @throws Exception if the initial (successful) launch fails
     */
    @Test
    void freshInstanceReprocessingSameFeedFailsOnDuplicateWithoutDoublePosting() throws Exception {
        long acctId = 900_000_430L;
        String cardNum = "9000000000000430";
        persistAccount(acctId, money("999999.99"), FUTURE_EXPIRY);
        persistXref(cardNum, 900_000_430L, acctId);

        String recId = id16("ITDUPEFF");
        BigDecimal amt = money("100.00");
        Path feed = writeFeed("dalytran-dupeffect.txt", List.of(
                dalytran(recId, "01", 5, amt, cardNum, ORIG_TS)));

        // Run #1 (fresh instance): posts the record; balance = 100.00.
        assertThat(jobLauncherTestUtils
                .launchJob(postingParams(feed, tempDir.resolve("rej-1.txt"), 4_301L)).getStatus())
                .isEqualTo(BatchStatus.COMPLETED);
        assertThat(accountRepository.findById(acctId).orElseThrow().getCurrBal())
                .isEqualByComparingTo(amt);

        // Run #2 (brand-new instance, same feed): the id already exists => abend, no double posting.
        JobExecution second = jobLauncherTestUtils.launchJob(
                postingParams(feed, tempDir.resolve("rej-2.txt"), 4_302L));
        assertThat(second.getStatus()).isEqualTo(BatchStatus.FAILED);

        // Ledger untouched: still exactly one posting; balance unchanged (never 200.00).
        assertThat(accountRepository.findById(acctId).orElseThrow().getCurrBal())
                .as("a duplicate reprocess must not double-post")
                .isEqualByComparingTo(amt);
    }

    /**
     * Volume smoke test: exercises the reader, processor and writer at volume against the full seed
     * feed (300 records &times; 350 bytes).
     *
     * <p>The seed ships as the LF-delimited ASCII <em>convenience</em> form
     * {@code legacy/data/ASCII/dailytran.txt} (AAP&nbsp;&sect;0.6.6); the canonical {@code DALYTRAN}
     * contract &mdash; and the production {@link com.aws.carddemo.util.batch.FixedLengthItemReader}
     * (review finding&nbsp;#17) &mdash; is <em>undelimited</em> {@code RECFM=FB}. The test therefore
     * re-frames the 300 convenience lines into a contiguous 350-byte-per-record undelimited image
     * (byte-identical to the native EBCDIC {@code DALYTRAN.PS} framing) before feeding the job, so the
     * volume run exercises the real fixed-block reader rather than the delimiter-dependent form. Every
     * read record is either posted or rejected (the processor never filters), so read count, write
     * count and posted+rejected all equal 300.</p>
     *
     * @throws Exception if the job launch fails
     */
    @Test
    void volumeSmokeTest_processesAll300SeedFeedRecords() throws Exception {
        Path seed = Path.of("legacy/data/ASCII/dailytran.txt");
        assertThat(Files.exists(seed))
                .as("seed DALYTRAN feed present at %s", seed.toAbsolutePath())
                .isTrue();
        // Re-frame the LF-delimited convenience fixture into the canonical undelimited RECFM=FB image
        // that the production FixedLengthItemReader (finding #17) consumes: strip the convenience LF
        // delimiters and re-concatenate on exact 350-byte boundaries. Assert the per-record width so a
        // malformed fixture fails loudly rather than silently misaligning the fixed-block reader.
        List<String> records = Files.readAllLines(seed, StandardCharsets.ISO_8859_1);
        assertThat(records)
                .as("seed feed must contain exactly 300 convenience records")
                .hasSize(300);
        for (int i = 0; i < records.size(); i++) {
            assertThat(records.get(i).getBytes(StandardCharsets.ISO_8859_1).length)
                    .as("seed feed record %d must be exactly 350 bytes", i + 1)
                    .isEqualTo(350);
        }
        Path feed = writeFeed("dailytran-fb.txt", records);
        Path reject = tempDir.resolve("reject-volume.txt");

        JobExecution execution = launch(feed, reject);

        assertThat(execution.getStatus()).isEqualTo(BatchStatus.COMPLETED);
        StepExecution step = postingStep(execution);
        assertThat(step.getReadCount()).isEqualTo(300L);
        assertThat(step.getWriteCount()).isEqualTo(300L);
        int rejects = rejectCount(execution);
        assertThat(rejects).isBetween(0, 300);
        long posted = step.getWriteCount() - rejects;
        assertThat(posted + rejects).isEqualTo(300L);
    }
}
