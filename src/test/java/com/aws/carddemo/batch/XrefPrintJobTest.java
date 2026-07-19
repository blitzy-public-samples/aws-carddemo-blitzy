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

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
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
import org.springframework.batch.test.JobRepositoryTestUtils;
import org.springframework.batch.test.context.SpringBatchTest;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import com.aws.carddemo.domain.CardXref;
import com.aws.carddemo.repository.CardXrefRepository;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.slf4j.LoggerFactory;

/**
 * Spring Boot + Spring Batch Testcontainers integration test asserting the behavioral parity of
 * {@link XrefPrintJob} &mdash; the Java re-platform of the legacy COBOL batch program
 * {@code CBACT03C.cbl} (layout copybook {@code CVACT03Y.cpy}).
 *
 * <h2>Parity contract under test</h2>
 * <p>{@code CBACT03C} opens the {@code XREFFILE} VSAM KSDS {@code INPUT} with
 * {@code ACCESS MODE SEQUENTIAL} keyed on {@code FD-XREF-CARD-NUM}, walks it front-to-back in
 * ascending primary-key order, {@code DISPLAY}s every {@code CARD-XREF-RECORD}, and falls through
 * to {@code GOBACK} with return code {@code 0}; it never issues a {@code WRITE}, {@code REWRITE},
 * or {@code DELETE}, so the pass is strictly read-only, and any non end-of-file I/O error abends
 * through {@code CEE3ABD} with a non-zero return code. The Java target re-expresses this as a
 * single chunk-oriented step whose {@code RepositoryItemReader} streams the {@code card_xref}
 * table ascending by {@code xrefCardNum} and whose logging writer prints one line per row. This
 * test locks in the four observable guarantees that define parity:</p>
 * <ol>
 *   <li>the job bean {@code xrefPrintJob} is wired and registered;</li>
 *   <li>a clean run finishes {@link BatchStatus#COMPLETED} with the {@link ExitStatus#COMPLETED}
 *       exit code &mdash; the framework analog of the COBOL return code {@code 0};</li>
 *   <li>every cross-reference row is printed exactly once, in ascending card-number order
 *       (the VSAM {@code RECORD KEY} browse), with the step read count equal to the table row
 *       count; and</li>
 *   <li>the run mutates no {@code card_xref} data (read-only parity).</li>
 * </ol>
 *
 * <h2>Harness</h2>
 * <p>The test boots the full application context under the {@code test} profile against a real
 * PostgreSQL&nbsp;16 provisioned by Testcontainers and wired through
 * {@link ServiceConnection}, so the datasource carries no hardcoded URL or credentials. Flyway
 * applies the production migrations and Hibernate validates the entity mappings against that
 * schema (per {@code application-test.yml}); {@code spring.batch.job.enabled=false} keeps the job
 * from running at startup, so it is launched explicitly through {@link JobLauncherTestUtils}.</p>
 *
 * <p>Because the application declares many {@link Job} beans, the {@link JobLauncherTestUtils}
 * auto-registered by {@link SpringBatchTest} cannot resolve a unique job (its
 * {@code ObjectProvider.ifUnique} job binding is silently skipped). The nested
 * {@link BatchTestConfig} therefore contributes a {@link Primary @Primary}
 * {@link JobLauncherTestUtils} whose job is bound explicitly by qualifier to
 * {@code xrefPrintJob}, so this test always launches the correct job.</p>
 *
 * <p>The {@code card_xref} table is empty in the {@code test} profile (the bulk seed loader is
 * {@code local}-only), so each test seeds its own deterministic fixture: minimal
 * foreign-key parents (customer, account) via {@link JdbcTemplate} and the cross-reference rows
 * via the in-scope {@link CardXrefRepository} in scrambled insertion order, proving the ascending
 * ordering comes from the job rather than from insertion order. The printed output is captured by
 * attaching a Logback {@link ListAppender} to the {@link XrefPrintJob} logger.</p>
 */
@SpringBootTest
@ActiveProfiles("test")
@SpringBatchTest
@Testcontainers
class XrefPrintJobTest {

    /**
     * Shared, single-instance PostgreSQL&nbsp;16 container for the whole test class.
     *
     * <p>{@link Container} on a {@code static} field starts it once for all methods, and
     * {@link ServiceConnection} publishes its JDBC coordinates to Spring Boot's auto-configured
     * datasource with no hardcoded credentials. The {@code postgres:16-alpine} image matches the
     * PostgreSQL&nbsp;16 production target and is resolved locally, so no network pull is required.
     */
    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>(DockerImageName.parse("postgres:16-alpine"));

    /** Name of the single {@link Job} bean under test, as declared by {@link XrefPrintJob}. */
    private static final String JOB_NAME = "xrefPrintJob";

    /** Name of the single {@link org.springframework.batch.core.Step} of {@link #JOB_NAME}. */
    private static final String STEP_NAME = "xrefPrintStep";

    /** Prefix of every log line the read-only writer emits (one per cross-reference row). */
    private static final String RECORD_MARKER = "CARD-XREF-RECORD";

    /** Extracts the {@code XREF-CARD-NUM} token from a captured writer log line. */
    private static final Pattern CARD_NUM_PATTERN = Pattern.compile("XREF-CARD-NUM=(\\S+)");

    /** Optional golden fixture of expected printed lines; compared row-for-row only if present. */
    private static final String GOLDEN_RESOURCE = "golden/xref-print.txt";

    /**
     * Deterministic 16-character, zero-padded numeric card numbers seeded per test, in
     * <em>scrambled</em> insertion order. Because every value is the same length, natural
     * {@link String} ordering coincides with numeric ordering, which is the VSAM
     * {@code RECORD KEY} browse order the job must reproduce.
     */
    private static final List<String> SEED_CARD_NUMBERS = List.of(
            "0000000000000040",
            "0000000000000010",
            "0000000000000070",
            "0000000000000030",
            "0000000000000020",
            "0000000000000060",
            "0000000000000050");

    /** Global source of unique {@code run.id} job-parameter values across all launches. */
    private static final AtomicLong RUN_ID = new AtomicLong();

    /** Explicitly-bound test launcher for {@code xrefPrintJob} (see {@link BatchTestConfig}). */
    private final JobLauncherTestUtils jobLauncherTestUtils;

    /** Utility used to clear Spring Batch metadata between launches. */
    private final JobRepositoryTestUtils jobRepositoryTestUtils;

    /** In-scope repository used to seed cross-reference rows and to assert row counts/order. */
    private final CardXrefRepository cardXrefRepository;

    /** Plain-JDBC access used to seed and clean the foreign-key parent tables. */
    private final JdbcTemplate jdbcTemplate;

    /** The job bean under test, injected by qualifier for the registration assertion. */
    private final Job xrefPrintJob;

    /** Logback logger of the writer; the target the {@link #logAppender} attaches to. */
    private Logger writerLogger;

    /** In-memory appender capturing the writer's printed lines for one test method. */
    private ListAppender<ILoggingEvent> logAppender;

    /**
     * Constructor injection of all collaborators (the project-wide convention; JUnit's
     * {@code spring.test.constructor.autowire.mode=all} autowires every parameter). The
     * {@link JobLauncherTestUtils} resolves to the {@link Primary @Primary} bean from
     * {@link BatchTestConfig}; the {@link Job} is disambiguated by {@link Qualifier}.
     *
     * @param jobLauncherTestUtils   the explicitly-bound launcher for {@code xrefPrintJob}
     * @param jobRepositoryTestUtils the batch-metadata cleanup helper
     * @param cardXrefRepository     the cross-reference repository under exercise
     * @param jdbcTemplate           plain JDBC used for foreign-key parent fixtures
     * @param xrefPrintJob           the job bean under test
     */
    XrefPrintJobTest(JobLauncherTestUtils jobLauncherTestUtils,
                     JobRepositoryTestUtils jobRepositoryTestUtils,
                     CardXrefRepository cardXrefRepository,
                     JdbcTemplate jdbcTemplate,
                     @Qualifier(JOB_NAME) Job xrefPrintJob) {
        this.jobLauncherTestUtils = jobLauncherTestUtils;
        this.jobRepositoryTestUtils = jobRepositoryTestUtils;
        this.cardXrefRepository = cardXrefRepository;
        this.jdbcTemplate = jdbcTemplate;
        this.xrefPrintJob = xrefPrintJob;
    }

    /**
     * Resets batch metadata, clears the cross-reference table and its parents, seeds a fresh
     * deterministic fixture, and attaches a capturing appender to the writer's logger so each
     * test method is fully independent.
     */
    @BeforeEach
    void setUp() {
        jobRepositoryTestUtils.removeJobExecutions();
        cleanFixtures();
        seedFixtures();
        attachLogCapture();
    }

    /**
     * Detaches the capturing appender, removes the seeded fixture, and clears batch metadata so no
     * state leaks into the next test method.
     */
    @AfterEach
    void tearDown() {
        detachLogCapture();
        cleanFixtures();
        jobRepositoryTestUtils.removeJobExecutions();
    }

    /**
     * The context wires together and the {@code xrefPrintJob} bean is registered and bound to the
     * launcher. Reaching this assertion transitively proves the full context (all layers, Flyway
     * migration, and Hibernate schema validation) started successfully.
     */
    @Test
    @DisplayName("context loads and the xrefPrintJob bean is registered")
    void contextLoadsAndJobRegistered() {
        assertThat(jobLauncherTestUtils).isNotNull();
        assertThat(xrefPrintJob).isNotNull();
        assertThat(xrefPrintJob.getName()).isEqualTo(JOB_NAME);
        assertThat(jobLauncherTestUtils.getJob()).isSameAs(xrefPrintJob);
    }

    /**
     * A clean pass over the cross-reference file finishes {@link BatchStatus#COMPLETED} with the
     * {@link ExitStatus#COMPLETED} exit code &mdash; the framework analog of the COBOL
     * {@code GOBACK} with return code {@code 0}.
     *
     * @throws Exception if the job launch fails
     */
    @Test
    @DisplayName("runs clean and finishes COMPLETED (return code 0)")
    void runsCleanWithReturnCodeZero() throws Exception {
        JobExecution execution = jobLauncherTestUtils.launchJob(uniqueParameters());

        assertThat(execution.getStatus()).isEqualTo(BatchStatus.COMPLETED);
        assertThat(execution.getExitStatus().getExitCode())
                .isEqualTo(ExitStatus.COMPLETED.getExitCode());
    }

    /**
     * Every seeded cross-reference row is printed exactly once, in ascending card-number order,
     * and the step read count equals the table row count &mdash; reproducing the sequential
     * ascending {@code RECORD KEY} browse of {@code CBACT03C}. When a
     * {@code golden/xref-print.txt} fixture is present, the printed lines are additionally
     * asserted equal to it row-for-row.
     *
     * @throws Exception if the job launch fails or the golden fixture cannot be read
     */
    @Test
    @DisplayName("prints all cross-references once, ascending by card number")
    void printsAllXrefsInAscendingCardNumOrder() throws Exception {
        long rowCount = cardXrefRepository.count();
        assertThat(rowCount).isEqualTo(SEED_CARD_NUMBERS.size());

        JobExecution execution = jobLauncherTestUtils.launchJob(uniqueParameters());
        assertThat(execution.getStatus()).isEqualTo(BatchStatus.COMPLETED);

        assertThat(stepReadCount(execution, STEP_NAME)).isEqualTo(rowCount);

        List<String> printedCardNumbers = capturedCardNumbers();
        assertThat(printedCardNumbers).hasSize((int) rowCount);

        // Ascending card-number order: identical to a natural sort of the printed keys.
        List<String> ascending = printedCardNumbers.stream().sorted().toList();
        assertThat(printedCardNumbers).containsExactlyElementsOf(ascending);

        // Cross-check the printed order against the repository's ascending browse query.
        List<String> repositoryOrder = cardXrefRepository.findAllByOrderByXrefCardNumAsc()
                .stream()
                .map(CardXref::getXrefCardNum)
                .toList();
        assertThat(printedCardNumbers).containsExactlyElementsOf(repositoryOrder);

        assertGoldenIfPresent();
    }

    /**
     * The job is strictly read-only: the {@code card_xref} row count is unchanged after the run,
     * mirroring the {@code OPEN INPUT} / read-only semantics of {@code CBACT03C}.
     *
     * @throws Exception if the job launch fails
     */
    @Test
    @DisplayName("is read-only: leaves the card_xref table unchanged")
    void isReadOnly_noMutations() throws Exception {
        long before = cardXrefRepository.count();
        assertThat(before).isEqualTo(SEED_CARD_NUMBERS.size());

        JobExecution execution = jobLauncherTestUtils.launchJob(uniqueParameters());
        assertThat(execution.getStatus()).isEqualTo(BatchStatus.COMPLETED);

        long after = cardXrefRepository.count();
        assertThat(after).isEqualTo(before);
    }

    // ------------------------------------------------------------------------
    // Fixtures
    // ------------------------------------------------------------------------

    /**
     * Seeds the deterministic fixture: one {@code customer} and one {@code account} parent per row
     * (minimal, primary-key-only inserts &mdash; every other column is nullable or defaulted),
     * then the cross-reference rows through the in-scope repository in scrambled insertion order.
     */
    private void seedFixtures() {
        for (long id = 1; id <= SEED_CARD_NUMBERS.size(); id++) {
            jdbcTemplate.update("INSERT INTO customer (cust_id) VALUES (?)", id);
            jdbcTemplate.update("INSERT INTO account (acct_id) VALUES (?)", id);
        }
        List<CardXref> rows = new ArrayList<>(SEED_CARD_NUMBERS.size());
        long parentId = 1;
        for (String cardNumber : SEED_CARD_NUMBERS) {
            rows.add(new CardXref(cardNumber, parentId, parentId));
            parentId++;
        }
        cardXrefRepository.saveAll(rows);
    }

    /**
     * Removes the seeded fixture in foreign-key dependency order (children first): the
     * cross-reference rows through the repository, then the {@code account} and {@code customer}
     * parents through plain JDBC. Safe to call before any data exists.
     */
    private void cleanFixtures() {
        cardXrefRepository.deleteAll();
        jdbcTemplate.update("DELETE FROM account");
        jdbcTemplate.update("DELETE FROM customer");
    }

    // ------------------------------------------------------------------------
    // Log capture
    // ------------------------------------------------------------------------

    /**
     * Attaches a fresh {@link ListAppender} to the {@link XrefPrintJob} logger so the writer's
     * printed lines can be inspected. The launcher runs the job synchronously on the calling
     * thread, so all lines are captured by the time {@code launchJob} returns.
     */
    private void attachLogCapture() {
        writerLogger = (Logger) LoggerFactory.getLogger(XrefPrintJob.class);
        logAppender = new ListAppender<>();
        logAppender.start();
        writerLogger.addAppender(logAppender);
    }

    /** Detaches and stops the capturing appender attached by {@link #attachLogCapture()}. */
    private void detachLogCapture() {
        if (writerLogger != null && logAppender != null) {
            writerLogger.detachAppender(logAppender);
            logAppender.stop();
        }
    }

    /**
     * Returns the {@code XREF-CARD-NUM} values from the captured writer lines, in emission order.
     *
     * @return the printed card numbers in the exact order the writer emitted them
     */
    private List<String> capturedCardNumbers() {
        List<String> cardNumbers = new ArrayList<>();
        for (ILoggingEvent event : logAppender.list) {
            String message = event.getFormattedMessage();
            if (message != null && message.startsWith(RECORD_MARKER)) {
                Matcher matcher = CARD_NUM_PATTERN.matcher(message);
                if (matcher.find()) {
                    cardNumbers.add(matcher.group(1));
                }
            }
        }
        return cardNumbers;
    }

    /**
     * Returns the full captured writer lines (formatted messages) for cross-reference records, in
     * emission order &mdash; used for row-for-row golden comparison.
     *
     * @return the printed record lines in emission order
     */
    private List<String> capturedRecordLines() {
        List<String> lines = new ArrayList<>();
        for (ILoggingEvent event : logAppender.list) {
            String message = event.getFormattedMessage();
            if (message != null && message.startsWith(RECORD_MARKER)) {
                lines.add(message);
            }
        }
        return lines;
    }

    // ------------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------------

    /**
     * Builds a unique {@link JobParameters} for each launch so every run is a distinct job
     * instance (the modern analog of one scheduled JCL execution).
     *
     * @return job parameters carrying a unique {@code run.id}
     */
    private JobParameters uniqueParameters() {
        return new JobParametersBuilder()
                .addLong("run.id", RUN_ID.incrementAndGet())
                .toJobParameters();
    }

    /**
     * The job reproduces the legacy CBACT03C SYSOUT execution banners (QA finding F4): a
     * {@code START OF EXECUTION OF PROGRAM CBACT03C} line before the run and, on a normal
     * (COMPLETED) run, an {@code END OF EXECUTION OF PROGRAM CBACT03C} line after it &mdash; emitted
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
            JobExecution execution = jobLauncherTestUtils.launchJob(uniqueParameters());
            assertThat(execution.getStatus()).isEqualTo(BatchStatus.COMPLETED);

            List<String> banners = capture.list.stream()
                    .map(ILoggingEvent::getFormattedMessage)
                    .filter(message -> message.contains("OF EXECUTION OF PROGRAM"))
                    .toList();

            assertThat(banners).containsExactly(
                    "START OF EXECUTION OF PROGRAM CBACT03C",
                    "END OF EXECUTION OF PROGRAM CBACT03C");
        } finally {
            bannerLogger.detachAppender(capture);
            capture.stop();
        }
    }

    /**
     * Returns the read count of the named step within a completed {@link JobExecution}.
     *
     * <p>Deliberately returns a primitive {@code long} rather than a {@link StepExecution}: a
     * test-class method whose return type is {@link StepExecution} (or {@code JobExecution}) would
     * be picked up by {@code @SpringBatchTest}'s step/job-scope test listeners as a scope factory
     * and invoked with no arguments, which fails for a parameterized helper. Returning the count
     * keeps the lookup encapsulated without exposing such a factory method.</p>
     *
     * @param execution the completed job execution
     * @param stepName  the step name to locate
     * @return the number of items the step read
     */
    private long stepReadCount(JobExecution execution, String stepName) {
        return execution.getStepExecutions().stream()
                .filter(step -> stepName.equals(step.getStepName()))
                .findFirst()
                .map(StepExecution::getReadCount)
                .orElseThrow(() -> new AssertionError("step not found: " + stepName));
    }

    /**
     * Asserts the printed lines equal the {@code golden/xref-print.txt} fixture row-for-row when
     * that classpath resource exists; otherwise does nothing. Lines are compared exactly, without
     * trimming, to preserve fixed-width fidelity.
     *
     * @throws IOException if the golden resource exists but cannot be read
     */
    private void assertGoldenIfPresent() throws IOException {
        ClassPathResource golden = new ClassPathResource(GOLDEN_RESOURCE);
        if (!golden.exists()) {
            return;
        }
        List<String> goldenLines;
        try (InputStream in = golden.getInputStream();
             BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            goldenLines = reader.lines().toList();
        }
        assertThat(capturedRecordLines()).containsExactlyElementsOf(goldenLines);
    }

    /**
     * Test-scoped configuration that supplies an explicitly-bound {@link JobLauncherTestUtils}.
     *
     * <p>With many {@link Job} beans on the context, the {@link SpringBatchTest}-provided
     * {@code JobLauncherTestUtils} cannot bind a unique job. This {@link Primary @Primary} bean
     * (registered under a distinct name to avoid clashing with the auto-registered one) binds the
     * job explicitly by qualifier and reuses the auto-configured {@link JobLauncher} and
     * {@link JobRepository}, so the test always launches {@code xrefPrintJob}.
     */
    @TestConfiguration
    static class BatchTestConfig {

        /**
         * Builds the {@link Primary @Primary} launcher-test utility bound to {@code xrefPrintJob}.
         *
         * @param xrefPrintJob  the job under test, bound by qualifier
         * @param jobLauncher   the auto-configured, unique batch job launcher
         * @param jobRepository the auto-configured, unique batch job repository
         * @return a {@link JobLauncherTestUtils} that launches only {@code xrefPrintJob}
         */
        @Bean
        @Primary
        JobLauncherTestUtils xrefPrintJobLauncherTestUtils(@Qualifier(JOB_NAME) Job xrefPrintJob,
                                                           JobLauncher jobLauncher,
                                                           JobRepository jobRepository) {
            JobLauncherTestUtils utils = new JobLauncherTestUtils();
            utils.setJob(xrefPrintJob);
            utils.setJobLauncher(jobLauncher);
            utils.setJobRepository(jobRepository);
            return utils;
        }
    }
}
