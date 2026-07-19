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

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;

import com.aws.carddemo.domain.Card;
import com.aws.carddemo.repository.CardRepository;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.ExitStatus;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.JobExecution;
import org.springframework.batch.core.JobParameters;
import org.springframework.batch.core.JobParametersBuilder;
import org.springframework.batch.core.StepExecution;
import org.springframework.batch.test.JobLauncherTestUtils;
import org.springframework.batch.test.JobRepositoryTestUtils;
import org.springframework.batch.test.context.SpringBatchTest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Spring Boot + Spring Batch Testcontainers integration test that verifies the
 * behavioral parity of {@link CardMasterPrintJob} with the legacy COBOL batch
 * program {@code CBACT02C} ("Read and print card data file"; source
 * {@code app/cbl/CBACT02C.cbl}, record layout {@code app/cpy/CVACT02Y.cpy},
 * retained for reference under {@code legacy/}).
 *
 * <h2>Parity contract exercised</h2>
 * <ul>
 *   <li><strong>Sequential ascending-key scan.</strong> {@code CBACT02C} opens
 *       the {@code CARDFILE} VSAM KSDS {@code INPUT}, browses it sequentially in
 *       ascending {@code FD-CARD-NUM} order until end-of-file, and
 *       {@code DISPLAY}s every {@code CARD-RECORD}. The Java job reads the
 *       {@code card} table ordered by {@code cardNum} ascending and logs every
 *       row. {@link #printsAllCardsInAscendingCardNumOrder(Path)} asserts the
 *       step read count equals the persisted row count and that the emitted order
 *       is exactly the repository's ascending-key order.</li>
 *   <li><strong>Read-only, return code&nbsp;0.</strong> The COBOL program never
 *       writes, rewrites, or deletes a record and ends with {@code GOBACK}
 *       (RC&nbsp;0). {@link #runsCleanWithReturnCodeZero()} asserts
 *       {@link BatchStatus#COMPLETED} together with the {@link ExitStatus#COMPLETED}
 *       exit code (the RC&nbsp;0 analog), and {@link #isReadOnly_noMutations()}
 *       asserts the card table and a sampled record &mdash; including its
 *       optimistic-lock {@code @Version} &mdash; are unchanged across the run.</li>
 *   <li><strong>CVV never in cleartext.</strong> The card record carries a
 *       sensitive card-verification value ({@code CARD-CVV-CD}). The job masks it
 *       with a fixed {@code ***} token and never reads the real value.
 *       {@link #cvvIsMaskedOrOmitted()} asserts every produced line masks the CVV
 *       field and that no seeded CVV appears in cleartext anywhere in the output
 *       (AAP &sect;0.7.3&nbsp;L1, &sect;0.9.3).</li>
 * </ul>
 *
 * <h2>Harness</h2>
 * <ul>
 *   <li>{@link SpringBootTest} boots the full application context under the
 *       {@code test} profile against a real PostgreSQL&nbsp;16 database supplied
 *       by a single static {@link PostgreSQLContainer} wired into Spring Boot via
 *       {@link ServiceConnection}, so no JDBC URL, username, or password is ever
 *       hardcoded (AAP &sect;0.8.1 / &sect;0.9.3).</li>
 *   <li>{@link SpringBatchTest} contributes the batch test utilities. Because the
 *       application declares many {@code Job} beans, the auto-registered
 *       {@link JobLauncherTestUtils} is left without a job by the framework (it
 *       injects a {@code Job} only when the bean is unique); this test therefore
 *       binds the specific job explicitly in {@link #setUp()} via
 *       {@code setJob(cardMasterPrintJob)}, where the job is injected with
 *       {@link Qualifier @Qualifier("cardMasterPrintJob")}. It does not rely on
 *       ambiguous single-{@code Job} autowiring.</li>
 *   <li>{@code spring.batch.job.enabled=false} (from {@code application-test.yml})
 *       means no job runs at context start; each test launches the job explicitly
 *       with unique {@link JobParameters} (a {@code run.id}), and
 *       {@link JobRepositoryTestUtils#removeJobExecutions()} clears batch metadata
 *       after every test.</li>
 *   <li>The {@code test} profile leaves the {@code card}/{@code account} tables
 *       empty (bulk seeding runs only under the {@code local} profile), so each
 *       test seeds a small, deterministic dataset itself: parent
 *       {@code account} rows through a {@link JdbcTemplate} (only {@code acct_id}
 *       is required) and {@link Card} rows through {@link CardRepository}. The
 *       {@code card.acct_id} foreign key is satisfied by inserting the accounts
 *       first.</li>
 * </ul>
 *
 * <h2>Capturing the produced output</h2>
 * The job's "print" is an {@code INFO} log line per card (the modern
 * {@code SYSOUT} analog). The test attaches a Logback {@link ListAppender} to the
 * {@link CardMasterPrintJob} logger for the duration of each test and asserts
 * against the captured, fully-formatted lines &mdash; which is exactly the output
 * a CVV must never leak into.
 */
@SpringBootTest
@ActiveProfiles("test")
@SpringBatchTest
@Testcontainers
class CardMasterPrintJobTest {

    /**
     * Real PostgreSQL&nbsp;16 engine for the integration test. It is
     * {@code static} so the {@link Testcontainers} extension starts it once for
     * the whole class before the Spring context is created, and
     * {@link ServiceConnection} publishes its connection details to Spring Boot
     * so the datasource is configured with no hardcoded credentials. The
     * {@code postgres:16-alpine} image matches the PostgreSQL&nbsp;16 data-tier
     * target of the migration.
     */
    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>(DockerImageName.parse("postgres:16-alpine"));

    /** Canonical bean name / job name of the job under test. */
    private static final String JOB_NAME = "cardMasterPrintJob";

    /**
     * Leading token of every card line emitted by
     * {@code CardMasterPrintJob.formatCardRecord(Card)}; used to isolate the
     * writer's card records from any other log output on the same logger.
     */
    private static final String CARD_LINE_PREFIX = "CBACT02C CARD-RECORD";

    /** The fixed mask the job emits in place of the sensitive CVV. */
    private static final String CVV_MASK = "***";

    /** A seeded card whose fields are sampled by the read-only assertion. */
    private static final String KNOWN_CARD_NUM = "4000000000000001";

    /** The (never-logged) CVV of {@link #KNOWN_CARD_NUM}. */
    private static final String KNOWN_CARD_CVV = "823";

    /**
     * Parent account identifiers seeded so the {@code card.acct_id} foreign key
     * is satisfied. Only {@code acct_id} is required by the {@code account}
     * schema; all other columns are nullable or defaulted.
     */
    private static final long[] SEED_ACCT_IDS = {9001L, 9002L, 9003L};

    /**
     * The CVVs of the seeded cards. The dataset is deliberately chosen so that no
     * CVV value is a coincidental substring of any other rendered field (card
     * number, account id, dates, status, or the fixed line prefix); this makes
     * the "no cleartext CVV" assertion meaningful &mdash; it would fail if the
     * masking were removed.
     */
    private static final List<String> SEED_CVVS = List.of("917", "823", "456", "731", "642");

    /** Batch test utility (from {@link SpringBatchTest}) used to launch the job. */
    private final JobLauncherTestUtils jobLauncherTestUtils;

    /** Batch test utility (from {@link SpringBatchTest}) used to clear metadata. */
    private final JobRepositoryTestUtils jobRepositoryTestUtils;

    /** The specific job under test, injected by qualifier to avoid ambiguity. */
    private final Job cardMasterPrintJob;

    /** Repository used to seed cards, count them, and read them back. */
    private final CardRepository cardRepository;

    /** Template used to seed and clean the parent {@code account} rows. */
    private final JdbcTemplate jdbcTemplate;

    /** The Logback logger of the job under test (target of the capture appender). */
    private Logger cardJobLogger;

    /** Per-test appender that captures the job's emitted card lines. */
    private ListAppender<ILoggingEvent> logAppender;

    /**
     * Constructor injection of the Spring-managed collaborators. The
     * {@link Qualifier} pins the {@code Job} bean by name because the application
     * defines several {@code Job} beans. Only field assignments are performed so
     * no partially constructed instance is observed.
     *
     * @param jobLauncherTestUtils   the batch launch helper
     * @param jobRepositoryTestUtils the batch metadata helper
     * @param cardMasterPrintJob     the job under test
     * @param cardRepository         the card repository
     * @param jdbcTemplate           the JDBC template for account seeding/cleanup
     */
    @Autowired
    CardMasterPrintJobTest(JobLauncherTestUtils jobLauncherTestUtils,
                           JobRepositoryTestUtils jobRepositoryTestUtils,
                           @Qualifier(JOB_NAME) Job cardMasterPrintJob,
                           CardRepository cardRepository,
                           JdbcTemplate jdbcTemplate) {
        this.jobLauncherTestUtils = jobLauncherTestUtils;
        this.jobRepositoryTestUtils = jobRepositoryTestUtils;
        this.cardMasterPrintJob = cardMasterPrintJob;
        this.cardRepository = cardRepository;
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * Binds the specific job to the launch helper, resets the card master to a
     * known, deterministic dataset, and attaches the log-capture appender.
     */
    @BeforeEach
    void setUp() {
        jobLauncherTestUtils.setJob(cardMasterPrintJob);
        cleanCardMasterData();
        seedCardMasterData();
        attachLogAppender();
    }

    /**
     * Detaches the capture appender, removes the seeded rows, and clears batch
     * job metadata so each test is fully isolated.
     */
    @AfterEach
    void tearDown() {
        detachLogAppender();
        cleanCardMasterData();
        jobRepositoryTestUtils.removeJobExecutions();
    }

    // -------------------------------------------------------------------------
    // Fixture helpers
    // -------------------------------------------------------------------------

    /**
     * Seeds three parent accounts and five cards. Cards are inserted in scrambled
     * card-number order so the ascending-order assertion is meaningful. Only
     * {@code acct_id} is supplied for accounts (plus an active-status flag);
     * every other account column is nullable or defaulted.
     */
    private void seedCardMasterData() {
        for (final long acctId : SEED_ACCT_IDS) {
            jdbcTemplate.update(
                    "INSERT INTO account (acct_id, acct_active_status) VALUES (?, ?)",
                    acctId, "Y");
        }
        cardRepository.save(new Card("4000000000000003", 9001L, "917", "CARDHOLDER GAMMA", "2027-03-31", "Y"));
        cardRepository.save(new Card(KNOWN_CARD_NUM, 9001L, KNOWN_CARD_CVV, "CARDHOLDER ALPHA", "2026-01-31", "Y"));
        cardRepository.save(new Card("4000000000000005", 9003L, "456", "CARDHOLDER EPSILON", "2028-05-31", "N"));
        cardRepository.save(new Card("4000000000000002", 9002L, "731", "CARDHOLDER BETA", "2027-11-30", "Y"));
        cardRepository.save(new Card("4000000000000004", 9002L, "642", "CARDHOLDER DELTA", "2029-07-31", "Y"));
    }

    /**
     * Removes the seeded cards (children) and then the seeded accounts (parents),
     * honoring the {@code fk_card_account} foreign-key order. Safe to call before
     * seeding (defensive) and after each test.
     */
    private void cleanCardMasterData() {
        cardRepository.deleteAll();
        for (final long acctId : SEED_ACCT_IDS) {
            jdbcTemplate.update("DELETE FROM account WHERE acct_id = ?", acctId);
        }
    }

    /**
     * Attaches a fresh {@link ListAppender} to the {@link CardMasterPrintJob}
     * logger so the job's emitted card lines can be inspected. Uses the Logback
     * logger (the runtime SLF4J binding) obtained by class.
     */
    private void attachLogAppender() {
        cardJobLogger = (Logger) LoggerFactory.getLogger(CardMasterPrintJob.class);
        logAppender = new ListAppender<>();
        logAppender.start();
        cardJobLogger.addAppender(logAppender);
    }

    /** Detaches and stops the capture appender if one is attached. */
    private void detachLogAppender() {
        if (cardJobLogger != null && logAppender != null) {
            cardJobLogger.detachAppender(logAppender);
            logAppender.stop();
        }
    }

    /**
     * Builds unique {@link JobParameters} (a distinct {@code run.id} per call) so
     * every launch is a fresh job instance and never trips
     * {@code JobInstanceAlreadyCompleteException}.
     *
     * <p>The launch itself is performed inline in each test via
     * {@code jobLauncherTestUtils.launchJob(uniqueJobParameters())} rather than
     * through a helper returning {@link JobExecution}. This is deliberate: the
     * {@code JobScopeTestExecutionListener} registered by {@link SpringBatchTest}
     * scans the test class for any method returning {@link JobExecution} and
     * invokes it during test-instance preparation (before {@code @BeforeEach}) to
     * establish a job scope. A launch helper returning {@link JobExecution} would
     * therefore be called before {@link #setUp()} binds the job, launching with a
     * null job. Returning {@link JobParameters} keeps the listener inert (this
     * test exercises no {@code @JobScope} beans).
     *
     * @return fresh, unique job parameters for a single launch
     */
    private JobParameters uniqueJobParameters() {
        return new JobParametersBuilder()
                .addLong("run.id", System.nanoTime())
                .toJobParameters();
    }

    /**
     * The job reproduces the legacy CBACT02C SYSOUT execution banners (QA finding F4): a
     * {@code START OF EXECUTION OF PROGRAM CBACT02C} line before the run and, on a normal
     * (COMPLETED) run, an {@code END OF EXECUTION OF PROGRAM CBACT02C} line after it &mdash; emitted
     * in that order by the {@link ExecutionBannerJobListener} registered on the job.
     *
     * @throws Exception if the job launch fails (fails the test)
     */
    @Test
    void emitsStartAndEndExecutionBanners() throws Exception {
        ch.qos.logback.classic.Logger bannerLogger =
                (ch.qos.logback.classic.Logger) LoggerFactory.getLogger(ExecutionBannerJobListener.class);
        ListAppender<ILoggingEvent> capture = new ListAppender<>();
        capture.start();
        bannerLogger.addAppender(capture);
        try {
            JobExecution execution = jobLauncherTestUtils.launchJob(uniqueJobParameters());
            assertThat(execution.getStatus()).isEqualTo(BatchStatus.COMPLETED);

            List<String> banners = capture.list.stream()
                    .map(ILoggingEvent::getFormattedMessage)
                    .filter(message -> message.contains("OF EXECUTION OF PROGRAM"))
                    .toList();

            assertThat(banners).containsExactly(
                    "START OF EXECUTION OF PROGRAM CBACT02C",
                    "END OF EXECUTION OF PROGRAM CBACT02C");
        } finally {
            bannerLogger.detachAppender(capture);
            capture.stop();
        }
    }

    /**
     * Returns the fully-formatted card lines the job emitted during the current
     * test, in emission order (which is the ascending-key read order).
     *
     * @return the captured card lines
     */
    private List<String> capturedCardLines() {
        return logAppender.list.stream()
                .map(ILoggingEvent::getFormattedMessage)
                .filter(message -> message.startsWith(CARD_LINE_PREFIX))
                .toList();
    }

    /**
     * Extracts the {@code cardNum} field value from a formatted card line of the
     * form {@code "... | cardNum=<16 digits> | acctId=..."}.
     *
     * @param line a formatted card line
     * @return the card number rendered on the line
     */
    private static String extractCardNum(String line) {
        final String marker = "cardNum=";
        final int start = line.indexOf(marker) + marker.length();
        final int end = line.indexOf(" |", start);
        return line.substring(start, end);
    }

    /**
     * Returns the value rendered for the trailing {@code cvv=} field of a
     * formatted card line (the CVV field is emitted last), or the empty string if
     * the marker is absent.
     *
     * @param line a formatted card line
     * @return the rendered CVV field value (expected to be the mask)
     */
    private static String cvvFieldValue(String line) {
        final String marker = "cvv=";
        final int idx = line.lastIndexOf(marker);
        return idx < 0 ? "" : line.substring(idx + marker.length());
    }

    // -------------------------------------------------------------------------
    // Tests
    // -------------------------------------------------------------------------

    /**
     * The application context boots and the job under test is present and
     * correctly named. This is the smoke test that the harness (full context +
     * PostgreSQL container + batch test utilities) is wired correctly.
     */
    @Test
    void contextLoadsAndJobRegistered() {
        assertThat(jobLauncherTestUtils).isNotNull();
        assertThat(jobRepositoryTestUtils).isNotNull();
        assertThat(cardRepository).isNotNull();
        assertThat(cardMasterPrintJob).isNotNull();
        assertThat(cardMasterPrintJob.getName()).isEqualTo(JOB_NAME);
    }

    /**
     * Parity: a clean run of {@code CBACT02C} ends with {@code GOBACK} and
     * return code&nbsp;0. The Java job must complete successfully with the
     * {@link ExitStatus#COMPLETED} exit code (the RC&nbsp;0 analog).
     *
     * @throws Exception if the launch fails
     */
    @Test
    void runsCleanWithReturnCodeZero() throws Exception {
        final JobExecution execution = jobLauncherTestUtils.launchJob(uniqueJobParameters());

        assertThat(execution.getStatus()).isEqualTo(BatchStatus.COMPLETED);
        assertThat(execution.getExitStatus().getExitCode())
                .isEqualTo(ExitStatus.COMPLETED.getExitCode());
    }

    /**
     * Parity: {@code CBACT02C} browses {@code CARDFILE} sequentially in ascending
     * {@code FD-CARD-NUM} order and prints every record. Asserts the step read
     * count equals the persisted row count, that every persisted card is emitted
     * exactly once, and that the emitted order is precisely the repository's
     * ascending-key order. The produced print is written to a {@link TempDir}
     * artifact; if a {@code golden/card-master-print.txt} fixture is present it is
     * compared row-for-row (fixed-width, no trimming).
     *
     * @param tempDir a per-test temporary directory for the produced artifact
     * @throws Exception if the launch or artifact I/O fails
     */
    @Test
    void printsAllCardsInAscendingCardNumOrder(@TempDir Path tempDir) throws Exception {
        final JobExecution execution = jobLauncherTestUtils.launchJob(uniqueJobParameters());
        assertThat(execution.getStatus()).isEqualTo(BatchStatus.COMPLETED);

        final long cardCount = cardRepository.count();
        final StepExecution stepExecution = execution.getStepExecutions().iterator().next();
        assertThat(stepExecution.getReadCount()).isEqualTo(cardCount);

        final List<String> lines = capturedCardLines();
        assertThat(lines).hasSize((int) cardCount);

        final List<String> emittedCardNums = lines.stream()
                .map(CardMasterPrintJobTest::extractCardNum)
                .toList();
        final List<String> expectedCardNums = cardRepository.findAllByOrderByCardNumAsc().stream()
                .map(Card::getCardNum)
                .toList();
        assertThat(emittedCardNums).containsExactlyElementsOf(expectedCardNums);
        assertThat(emittedCardNums).isSorted();

        final Path produced = tempDir.resolve("card-master-print.txt");
        Files.write(produced, lines);
        final ClassPathResource golden = new ClassPathResource("golden/card-master-print.txt");
        if (golden.exists()) {
            final List<String> goldenLines;
            try (var in = golden.getInputStream()) {
                goldenLines = new String(in.readAllBytes(), StandardCharsets.UTF_8).lines().toList();
            }
            assertThat(Files.readAllLines(produced)).containsExactlyElementsOf(goldenLines);
        }
    }

    /**
     * Security parity (AAP &sect;0.7.3&nbsp;L1, &sect;0.9.3): the sensitive CVV is
     * masked and never rendered in cleartext. Asserts that every emitted line
     * masks the trailing CVV field with {@code ***}, that the mask token is
     * present, and that no seeded CVV value &mdash; nor a {@code cvv=<value>}
     * rendering of it &mdash; appears anywhere in the produced output. The seed
     * CVVs are chosen so none is a coincidental substring of any other field, so
     * this assertion would fail if the masking were removed.
     *
     * @throws Exception if the launch fails
     */
    @Test
    void cvvIsMaskedOrOmitted() throws Exception {
        final JobExecution execution = jobLauncherTestUtils.launchJob(uniqueJobParameters());
        assertThat(execution.getStatus()).isEqualTo(BatchStatus.COMPLETED);

        final List<String> lines = capturedCardLines();
        assertThat(lines).isNotEmpty();
        assertThat(lines).allSatisfy(line ->
                assertThat(cvvFieldValue(line)).isEqualTo(CVV_MASK));

        final String output = String.join("\n", lines);
        assertThat(output).contains("cvv=" + CVV_MASK);
        for (final String cvv : SEED_CVVS) {
            assertThat(output)
                    .as("CVV %s must never appear in cleartext batch output", cvv)
                    .doesNotContain("cvv=" + cvv)
                    .doesNotContain(cvv);
        }
    }

    /**
     * Parity: {@code CBACT02C} opens {@code CARDFILE} for {@code INPUT} only and
     * performs no writes. Asserts the total row count is unchanged and that a
     * sampled record's fields &mdash; including the optimistic-lock
     * {@code @Version} &mdash; are identical before and after the run, proving the
     * job introduces no mutations.
     *
     * @throws Exception if the launch fails
     */
    @Test
    void isReadOnly_noMutations() throws Exception {
        final long countBefore = cardRepository.count();
        final Card before = cardRepository.findById(KNOWN_CARD_NUM).orElseThrow();
        final Long versionBefore = before.getVersion();

        jobLauncherTestUtils.launchJob(uniqueJobParameters());

        assertThat(cardRepository.count()).isEqualTo(countBefore);
        final Card after = cardRepository.findById(KNOWN_CARD_NUM).orElseThrow();
        assertThat(after.getAcctId()).isEqualTo(before.getAcctId());
        assertThat(after.getCvv()).isEqualTo(before.getCvv());
        assertThat(after.getCardEmbossedName()).isEqualTo(before.getCardEmbossedName());
        assertThat(after.getCardExpirationDate()).isEqualTo(before.getCardExpirationDate());
        assertThat(after.getCardActiveStatus()).isEqualTo(before.getCardActiveStatus());
        assertThat(after.getVersion()).isEqualTo(versionBefore);
    }
}
