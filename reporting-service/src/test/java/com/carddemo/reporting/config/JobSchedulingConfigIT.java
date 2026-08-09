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
package com.carddemo.reporting.config;

import com.carddemo.common.domain.Account;
import com.carddemo.common.domain.CardXref;
import com.carddemo.common.domain.Customer;
import com.carddemo.common.domain.Transaction;
import com.carddemo.common.exception.CardDemoException;
import com.carddemo.common.testsupport.MigratedSchemaContainer;
import com.carddemo.reporting.repository.AccountRepository;
import com.carddemo.reporting.repository.CardXrefRepository;
import com.carddemo.reporting.repository.CustomerRepository;
import com.carddemo.reporting.repository.TransactionRepository;

import org.awaitility.Awaitility;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.job.Job;
import org.springframework.batch.core.job.JobExecution;
import org.springframework.batch.core.job.parameters.JobParameter;
import org.springframework.batch.core.job.parameters.JobParameters;
import org.springframework.batch.core.launch.JobExecutionAlreadyRunningException;
import org.springframework.batch.core.launch.JobOperator;
import org.springframework.batch.core.launch.support.TaskExecutorJobOperator;
import org.springframework.batch.core.step.StepExecution;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.task.AsyncTaskExecutor;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * :purpose: Integration test for {@link JobSchedulingConfig}, the re-platformed
 *   ``CORPT00C`` ``WIRTE-JOBSUB-TDQ`` submission
 *   (``EXEC CICS WRITEQ TD QUEUE('JOBS')`` -> asynchronous JES submission ->
 *   {@link JobOperator}, AAP 0.4.4). Where the sibling unit test can only prove
 *   that a *mocked* launcher was invoked, this test boots the full reporting-service
 *   context against a real PostgreSQL carrying the committed Flyway schema, submits
 *   the report through the very same entry point the online screen uses, and then
 *   **awaits the run's terminal status** so submission is never mistaken for
 *   completion: the terminal {@link BatchStatus}/``ExitStatus``, the step read/write
 *   counts, and the statement artifacts the job actually produced are all asserted.
 * :output: A Failsafe (``*IT``) integration test whose scenarios confirm (1) a
 *   submission runs the real ``statementGenerationJob`` to ``COMPLETED`` and writes
 *   both statement files inside the configured batch output root, (2) the submission
 *   hands the run to an asynchronous, bounded launcher so the online caller is never
 *   blocked, (3) a job that fails at runtime surfaces as a ``FAILED``
 *   {@link JobExecution} carrying its failure exception rather than as a lost error,
 *   and (4) a *launch* failure surfaces synchronously as the frozen-message
 *   {@link CardDemoException}.
 */
@SpringBootTest
@ActiveProfiles("test")
class JobSchedulingConfigIT {

    /** :purpose: Synthetic, PII-safe card number shared by the seeded card and transactions. */
    private static final String SYNTHETIC_CARD = "0000000000000000";

    /**
     * :purpose: The writers' compiled-in default output directory. A submission that
     *   names no files carries the configured default ``stmtFile``/``htmlFile`` names,
     *   so ``statementItemWriter`` resolves ``output/statements.txt`` and
     *   ``output/statements.html`` relative to the process working directory — the
     *   module base directory under Failsafe.
     */
    private static final Path DEFAULT_OUTPUT_DIR = Paths.get("output");

    /** :purpose: Default plain-text statement destination (``STMTFILE``, LRECL=80). */
    private static final Path DEFAULT_TEXT_FILE = DEFAULT_OUTPUT_DIR.resolve("statements.txt");

    /** :purpose: Default HTML statement destination (``HTMLFILE``, LRECL=100). */
    private static final Path DEFAULT_HTML_FILE = DEFAULT_OUTPUT_DIR.resolve("statements.html");

    /** :purpose: Upper bound on how long a submitted statement job may take to finish. */
    private static final long JOB_TIMEOUT_SECONDS = 300L;

    @Autowired
    private JobSchedulingConfig jobSchedulingConfig;

    @Autowired
    private CustomerRepository customerRepository;

    @Autowired
    private AccountRepository accountRepository;

    @Autowired
    private CardXrefRepository cardXrefRepository;

    @Autowired
    private TransactionRepository transactionRepository;

    /** :purpose: Raw JDBC access used only to clear the seeded cards before their parents. */
    @Autowired
    private JdbcTemplate jdbcTemplate;

    /**
     * :purpose: The context's batch output-path resolver, handed to the hand-built component
     *   below so it validates output names exactly as the injected one does.
     */
    @Autowired
    private com.carddemo.common.batch.BatchOutputPathResolver batchOutputPathResolver;

    /**
     * :purpose: Bind the datasource to the shared, already-migrated ``postgres:18``
     *   container so both the business tables and the Spring Batch metadata tables
     *   come from the owning modules' committed migrations and Hibernate only
     *   validates the mapping.
     * :param registry: the dynamic property registry supplied by the test context.
     */
    @DynamicPropertySource
    static void provisionSchema(DynamicPropertyRegistry registry) {
        MigratedSchemaContainer.registerDataSource(registry);
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "validate");
        // Batch writers resolve every path inside carddemo.batch.output-dir and refuse one
        // that escapes it (CWE-22), so the default statement file names no longer land in
        // the process working directory. Declaring this class's own output directory as that
        // root keeps the containment check in force AND keeps the artifact paths asserted
        // below authoritative.
        registry.add("carddemo.batch.output-dir",
                () -> DEFAULT_OUTPUT_DIR.toAbsolutePath().toString());
    }

    /**
     * :purpose: Remove any previous run's default output destination and seed a
     *   deterministic, PII-safe dataset in dependency order (customer, account, card
     *   cross-reference, then two transactions summing to ``300.75``) so exactly one
     *   statement is produced with known figures.
     * :raises IOException: when the stale output destination cannot be removed.
     */
    @BeforeEach
    void prepareOutputAndSeedData() throws IOException {
        removeDefaultOutput();

        transactionRepository.deleteAll();
        cardXrefRepository.deleteAll();
        // The migration seed carries 50 cards that foreign-key into accounts, so they are
        // removed before their parents; the job reads whole tables, so this test owns their
        // content.
        jdbcTemplate.update("DELETE FROM cards");
        // tran_cat_bal foreign-keys into accounts (fk_tran_cat_bal_acct), so the seeded
        // category balances go before their parent accounts.
        jdbcTemplate.update("DELETE FROM tran_cat_bal");
        accountRepository.deleteAll();
        customerRepository.deleteAll();

        Customer customer = new Customer();
        customer.setCustId(1L);
        customer.setCustFirstName("JOHN");
        customer.setCustMiddleName("A");
        customer.setCustLastName("DOE");
        customer.setCustAddrLine1("123 MAIN ST");
        customer.setCustAddrLine2("SUITE 100");
        customer.setCustAddrLine3("ANYTOWN");
        customer.setCustAddrStateCd("WA");
        customer.setCustAddrCountryCd("USA");
        customer.setCustAddrZip("99999");
        customer.setCustPhoneNum1("(000)000-0000");
        customer.setCustPhoneNum2("(000)000-0000");
        customer.setCustDobYyyyMmDd("1970-01-01");
        customer.setCustPriCardHolderInd("Y");
        customer.setCustFicoCreditScore(750);
        // The migrated NOT NULL PII columns carry blank values: no PII is seeded (AAP 0.6.7)
        // and the CryptoConverter passes empty strings through unchanged.
        customer.setCustSsn("");
        customer.setCustGovtIssuedId("");
        customer.setCustEftAccountId("");
        customerRepository.save(customer);

        Account account = new Account();
        account.setAcctId(1L);
        account.setAcctActiveStatus("Y");
        account.setAcctCurrBal(new BigDecimal("1234.56"));
        account.setAcctCreditLimit(new BigDecimal("5000.00"));
        account.setAcctCashCreditLimit(new BigDecimal("1000.00"));
        account.setAcctOpenDate("2020-01-01");
        // The legacy copybook misspelling ACCT-EXPIRAION-DATE is preserved verbatim.
        account.setAcctExpiraionDate("2099-12-31");
        account.setAcctReissueDate("2020-01-01");
        account.setAcctCurrCycCredit(new BigDecimal("0.00"));
        account.setAcctCurrCycDebit(new BigDecimal("0.00"));
        account.setAcctAddrZip("99999");
        accountRepository.save(account);

        // The card master row backs the cross-reference: a transaction's tran_card_num
        // foreign-keys into cards (fk_transactions_card, V8__transactions_card_fk.sql), so a
        // fixture carrying only the xref would describe a state the database does not permit.
        jdbcTemplate.update(
                "INSERT INTO cards (card_num, card_acct_id, card_cvv_cd, card_embossed_name, "
                        // The legacy copybook misspelling CARD-EXPIRAION-DATE is preserved.
                        + "card_expiraion_date, card_active_status, version) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?)",
                SYNTHETIC_CARD, 1L, null, "Statement Holder", "2099-12-31", "Y", 0L);

        CardXref cardXref = new CardXref();
        cardXref.setXrefCardNum(SYNTHETIC_CARD);
        cardXref.setXrefCustId(1L);
        cardXref.setXrefAcctId(1L);
        cardXrefRepository.save(cardXref);

        Transaction transactionOne = new Transaction();
        transactionOne.setTranId("0000000000000001");
        transactionOne.setTranCardNum(SYNTHETIC_CARD);
        transactionOne.setTranAmt(new BigDecimal("100.50"));
        transactionOne.setTranDesc("PURCHASE ONE");
        transactionOne.setTranTypeCd("01");
        transactionOne.setTranCatCd(1);
        transactionOne.setTranSource("POS");
        transactionOne.setTranOrigTs("2024-01-15-10.30.00.123456");
        transactionOne.setTranProcTs("2024-01-15-10.30.00.123456");

        Transaction transactionTwo = new Transaction();
        transactionTwo.setTranId("0000000000000002");
        transactionTwo.setTranCardNum(SYNTHETIC_CARD);
        transactionTwo.setTranAmt(new BigDecimal("200.25"));
        transactionTwo.setTranDesc("PURCHASE TWO");
        transactionTwo.setTranTypeCd("01");
        transactionTwo.setTranCatCd(1);
        transactionTwo.setTranSource("POS");
        transactionTwo.setTranOrigTs("2024-01-16-11.45.00.654321");
        transactionTwo.setTranProcTs("2024-01-16-11.45.00.654321");

        transactionRepository.saveAll(List.of(transactionOne, transactionTwo));
    }

    /**
     * :purpose: Leave no generated statement artifact behind in the module working
     *   directory once the scenario has asserted on it.
     * :raises IOException: when the output destination cannot be removed.
     */
    @AfterEach
    void cleanUpOutput() throws IOException {
        removeDefaultOutput();
    }

    /**
     * :purpose: Delete the default output destination whether it currently exists as
     *   the writers' directory or as the regular file one scenario substitutes to
     *   make the job fail.
     * :raises IOException: when a path below the destination cannot be removed.
     */
    private static void removeDefaultOutput() throws IOException {
        if (!Files.exists(DEFAULT_OUTPUT_DIR)) {
            return;
        }
        if (Files.isDirectory(DEFAULT_OUTPUT_DIR)) {
            try (Stream<Path> paths = Files.walk(DEFAULT_OUTPUT_DIR)) {
                for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                    Files.deleteIfExists(path);
                }
            }
            return;
        }
        Files.delete(DEFAULT_OUTPUT_DIR);
    }

    /**
     * :purpose: Wait for an accepted run to finish. Submission is synchronous but the run
     *     itself executes on the launcher's task executor, so the live
     *     {@link JobExecution} the launcher returns is polled until the worker thread has
     *     recorded a terminal status.
     * :param execution: the execution the launcher accepted.
     * :returns: the same execution once it is no longer running.
     * :raises org.awaitility.core.ConditionTimeoutException: if the run is still going when
     *     the timeout expires, reporting the status it was left in.
     */
    private JobExecution awaitCompletion(JobExecution execution) {
        Awaitility.await("job execution " + execution.getId() + " reaches a terminal status")
                .atMost(Duration.ofSeconds(JOB_TIMEOUT_SECONDS))
                .pollInterval(Duration.ofMillis(50))
                .pollDelay(Duration.ZERO)
                .until(() -> !execution.isRunning());
        return execution;
    }

    /**
     * :purpose: Submit a Monthly report through the production entry point and await
     *   the returned future, asserting the *real* ``statementGenerationJob`` reached
     *   ``COMPLETED``/``COMPLETED``, that the job instance and its parameters are the
     *   ones the submission built, that the single chunk-oriented step read and wrote
     *   exactly the one seeded card, and that both statement artifacts were produced
     *   at the writers' default destination with the legacy line widths and the
     *   scale-2 monetary figures of the seeded data.
     * :raises Exception: propagated from the awaited future or the artifact reads.
     */
    @Test
    void submissionRunsTheRealStatementJobToCompletionAndProducesArtifacts() throws Exception {
        JobExecution execution = awaitCompletion(jobSchedulingConfig.launchStatementGeneration());

        assertThat(execution).isNotNull();
        assertThat(execution.getJobInstance().getJobName()).isEqualTo("statementGenerationJob");
        assertThat(execution.getStatus()).isEqualTo(BatchStatus.COMPLETED);
        assertThat(execution.getExitStatus().getExitCode()).isEqualTo("COMPLETED");
        assertThat(execution.getAllFailureExceptions()).isEmpty();

        // The submitted parameters reached the real launcher, not a captor: the two
        // output file names of CREASTMT and nothing else, since the job stream carries
        // no PARM and nothing in the job could read a date window.
        assertThat(execution.getJobParameters().getString("stmtFile")).isEqualTo("statements.txt");
        assertThat(execution.getJobParameters().getString("htmlFile")).isEqualTo("statements.html");
        assertThat(execution.getJobParameters().getString("run.id")).isNotBlank();
        assertThat(execution.getJobParameters().parameters())
                .extracting(JobParameter::name)
                .containsExactlyInAnyOrder("stmtFile", "htmlFile", "run.id");

        assertThat(execution.getStepExecutions()).hasSize(1);
        StepExecution stepExecution = execution.getStepExecutions().iterator().next();
        assertThat(stepExecution.getStatus()).isEqualTo(BatchStatus.COMPLETED);
        assertThat(stepExecution.getReadCount()).isEqualTo(1);
        assertThat(stepExecution.getWriteCount()).isEqualTo(1);

        assertThat(DEFAULT_TEXT_FILE).exists();
        assertThat(DEFAULT_HTML_FILE).exists();

        List<String> textLines = Files.readAllLines(DEFAULT_TEXT_FILE);
        List<String> htmlLines = Files.readAllLines(DEFAULT_HTML_FILE);
        String startBanner = "*".repeat(31) + "START OF STATEMENT" + "*".repeat(31);
        String endBanner = "*".repeat(32) + "END OF STATEMENT" + "*".repeat(32);

        assertAll("statement artifacts produced by the awaited submission",
                () -> assertThat(textLines).isNotEmpty(),
                () -> assertThat(textLines).allSatisfy(line -> assertThat(line).hasSize(80)),
                () -> assertThat(textLines).contains(startBanner),
                () -> assertThat(textLines).contains(endBanner),
                () -> assertThat(textLines).anyMatch(line -> line.contains("000001234.56")),
                () -> assertThat(textLines).anyMatch(line -> line.contains("100.50")),
                () -> assertThat(textLines).anyMatch(line -> line.contains("200.25")),
                () -> assertThat(textLines)
                        .anyMatch(line -> line.startsWith("Total EXP:") && line.contains("300.75")),
                () -> assertThat(htmlLines).isNotEmpty(),
                () -> assertThat(htmlLines).allSatisfy(line -> assertThat(line).hasSize(100)),
                () -> assertThat(htmlLines)
                        .anyMatch(line -> line.stripTrailing().equals("<!DOCTYPE html>")),
                () -> assertThat(htmlLines).anyMatch(line -> line.contains("End of Statement")));
    }

    /**
     * :purpose: Prove the submission runs the job OFF the caller thread in the running
     *   context, so an online report request never blocks for the whole statement run. The
     *   component owns a {@link TaskExecutorJobOperator} driven by an asynchronous, bounded
     *   POOL; a synchronous operator would run the job on the request thread.
     *   ``@Async`` on a ``@Configuration`` class was the superseded way of achieving this,
     *   so the advice itself is no longer the contract - the operator's executor is.
     * :note: The executor is a {@link ThreadPoolTaskExecutor}, not a
     *   ``SimpleAsyncTaskExecutor``: the latter's ``concurrencyLimit`` is a THROTTLE that
     *   blocks the calling thread inside ``execute()`` rather than a queue, which held an
     *   online submission for tens of seconds once the limit was reached - the very thing
     *   this scenario exists to prevent. Backpressure is now a bounded queue plus an
     *   admission check that refuses over-budget submissions immediately. Rationale:
     *   docs/decision-log.md section 50.6.
     */
    @Test
    void submissionRunsTheJobOffTheCallerThreadSoTheOnlineCallerNeverBlocks() {
        Object operator = ReflectionTestUtils.getField(jobSchedulingConfig, "jobOperator");
        assertThat(operator).isInstanceOf(TaskExecutorJobOperator.class);

        Object taskExecutor = ReflectionTestUtils.getField(operator, "taskExecutor");
        assertThat(taskExecutor)
                .as("a synchronous executor would block the online caller")
                .isInstanceOf(AsyncTaskExecutor.class);
        assertThat(taskExecutor)
                .as("a throttling executor blocks the caller once its limit is reached")
                .isInstanceOf(ThreadPoolTaskExecutor.class);

        ThreadPoolTaskExecutor pool = (ThreadPoolTaskExecutor) taskExecutor;
        assertThat(pool.getThreadNamePrefix()).isEqualTo("statement-");
        // The pool is bounded on BOTH axes: workers, so concurrent runs cannot collide on
        // the frozen output file names, and queue depth, so a burst of report requests is
        // refused rather than accumulated without limit.
        assertThat(pool.getCorePoolSize()).isPositive();
        assertThat(pool.getMaxPoolSize()).isEqualTo(pool.getCorePoolSize());
        assertThat(pool.getQueueCapacity()).isPositive();
        assertThat(pool.getQueueCapacity()).isNotEqualTo(Integer.MAX_VALUE);
    }

    /**
     * :purpose: A job that fails once running must surface through the future as a
     *   ``FAILED`` {@link JobExecution} carrying its failure exception, not as a
     *   silently swallowed error. The failure is injected by obstructing the writers'
     *   own destination FILE — a directory standing where the statement file belongs —
     *   which the launcher's synchronous name check cannot pre-empt, because that check
     *   proves the name resolves inside the output root and says nothing about what
     *   already occupies it. The run therefore starts and then fails while writing,
     *   which is the surface under test.
     * :raises Exception: propagated from the awaited future or the output setup.
     */
    @Test
    void jobFailureIsDeliveredThroughTheAwaitedFuture() throws Exception {
        removeDefaultOutput();
        Files.createDirectories(DEFAULT_OUTPUT_DIR);
        Path obstruction = DEFAULT_OUTPUT_DIR.resolve("unwritable.txt");
        Files.createDirectories(obstruction);
        // The obstruction must be a NON-EMPTY directory: the writers replace an existing
        // destination, and an empty directory would simply be deleted, letting the run
        // succeed and defeating the injection.
        Files.createFile(obstruction.resolve("occupied"));

        // Distinct output names are used so this case observes a failure of its OWN
        // destination rather than re-running the success case's files, which the
        // identifying ``run.id`` would otherwise happily re-execute and overwrite.
        JobExecution execution = awaitCompletion(jobSchedulingConfig
                .launchStatementGeneration("unwritable.txt", "unwritable.html"));

        assertThat(execution.getStatus()).isEqualTo(BatchStatus.FAILED);
        assertThat(execution.getExitStatus().getExitCode()).isEqualTo("FAILED");
        assertThat(execution.getAllFailureExceptions()).isNotEmpty();
        assertThat(execution.getAllFailureExceptions())
                .anySatisfy(failure -> assertThat(failure).hasMessageContaining("unwritable.txt"));
        // The injected obstruction is still in place and no statement artifact was produced.
        assertThat(Files.isDirectory(obstruction)).isTrue();
        assertThat(Files.exists(DEFAULT_TEXT_FILE)).isFalse();
    }

    /**
     * :purpose: An output destination that cannot be used AT ALL — the configured output
     *   root obstructed by a regular file, so no statement file can be created under it —
     *   must be refused SYNCHRONOUSLY at the call site, before a job instance exists.
     * :raises Exception: propagated from the output setup.
     * :note: This is the surface the QA run exercised with
     *   ``stmtFile=../../../../etc/passwd``: the containment rule was enforced, but only
     *   later, inside the step-scoped writer factory. The caller was told ``202 ACCEPTED``,
     *   a ``BATCH_JOB_EXECUTION`` row was created for a run that could never produce a
     *   statement, and the run then failed with a ``BeanCreationException`` whose stack
     *   trace was persisted into ``exit_message`` as the operator-visible outcome.
     */
    @Test
    void anUnusableOutputDestinationIsRefusedAtTheCallSite() throws Exception {
        removeDefaultOutput();
        Files.createFile(DEFAULT_OUTPUT_DIR);
        try {
            assertThatThrownBy(() ->
                    jobSchedulingConfig.launchStatementGeneration("refused.txt", "refused.html"))
                    .isInstanceOf(CardDemoException.class)
                    .hasMessageContaining("refused.txt")
                    .hasMessageNotContaining("BeanCreationException")
                    .hasMessageNotContaining("scopedTarget");

            // The obstruction is untouched and nothing was written anywhere.
            assertThat(Files.isRegularFile(DEFAULT_OUTPUT_DIR)).isTrue();
        } finally {
            // Restore a usable root so the shared context's remaining cases are unaffected.
            removeDefaultOutput();
            Files.createDirectories(DEFAULT_OUTPUT_DIR);
        }
    }

    /**
     * :purpose: Assert the *launch-failure* surface with the launcher replaced by a mock
     *   that raises a checked Spring Batch launch exception. The production code
     *   translates it to a {@link CardDemoException} carrying the frozen operator
     *   message. A refused submission is reported to the caller at the call site — the
     *   run never started, so there is nothing to await, and the online screen must not
     *   be told a report is on its way.
     */
    @Nested
    class LaunchFailureSurface {

        /** :purpose: The real statement job, so only the launcher is substituted. */
        @Autowired
        @Qualifier("statementGenerationJob")
        private Job realStatementGenerationJob;

        /**
         * :purpose: A checked launch failure is translated to the frozen-message
         *   {@link CardDemoException} and delivered through the returned future.
         * :raises Exception: propagated from the mocked operator signature.
         */
        @Test
        void launchFailureSurfacesThroughTheFutureAsCardDemoException() throws Exception {
            JobExecutionAlreadyRunningException cause =
                    new JobExecutionAlreadyRunningException("job already running");
            JobOperator refusingOperator = mock(JobOperator.class);
            when(refusingOperator.start(any(Job.class), any(JobParameters.class))).thenThrow(cause);

            // The component builds its own asynchronous operator from the JobRepository, so a
            // context-level JobOperator mock would never be consumed; the second, public
            // constructor is the seam it exposes for exactly this substitution.
            JobSchedulingConfig refusingComponent = new JobSchedulingConfig(
                    refusingOperator, realStatementGenerationJob,
                    "statements.txt", "statements.html", batchOutputPathResolver);

            // The operator refuses the submission, so the caller learns about it
            // immediately instead of receiving a success message over a job that never ran.
            Throwable thrown = catchThrowable(() -> refusingComponent
                    .launchStatementGeneration("refused.txt", "refused.html"));

            assertThat(thrown)
                    .isInstanceOf(CardDemoException.class)
                    .hasRootCauseInstanceOf(JobExecutionAlreadyRunningException.class)
                    .hasMessage("Unable to Write TDQ (JOBS)...");
        }
    }
}
