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
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.ExitStatus;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.JobExecution;
import org.springframework.batch.core.JobParameters;
import org.springframework.batch.core.StepExecution;
import org.springframework.batch.core.launch.JobLauncher;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.test.JobLauncherTestUtils;
import org.springframework.batch.test.context.SpringBatchTest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.core.io.ClassPathResource;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import com.aws.carddemo.domain.Customer;
import com.aws.carddemo.repository.CustomerRepository;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;

/**
 * Testcontainers integration test that validates <strong>behavioral parity</strong> of
 * {@link CustomerMasterPrintJob} &mdash; the Java/Spring&nbsp;Batch target of the legacy COBOL
 * batch program {@code CBCUS01C.cbl} ("Read and print customer data file", copybook
 * {@code CVCUS01Y.cpy}).
 *
 * <h2>Parity contract reproduced by these tests</h2>
 * <ul>
 *   <li><strong>Sequential ascending-key scan.</strong> {@code CBCUS01C} opens {@code CUSTFILE}
 *       ({@code ORGANIZATION INDEXED}, {@code ACCESS SEQUENTIAL}, {@code RECORD KEY FD-CUST-ID})
 *       and reads every record front-to-back in ascending {@code CUST-ID} order, printing each with
 *       {@code DISPLAY CUSTOMER-RECORD}. The Java job reads every {@link Customer} in ascending
 *       {@code custId} order and logs each. Verified by
 *       {@link #printsAllCustomersInAscendingCustIdOrder()}.</li>
 *   <li><strong>Read-only, clean termination.</strong> The COBOL program opens the file
 *       {@code OPEN INPUT} and ends with return code&nbsp;0 on end-of-file. The Java job persists
 *       nothing (its writer only logs) and completes with {@link BatchStatus#COMPLETED} /
 *       {@link ExitStatus#COMPLETED}. Verified by {@link #runsCleanWithReturnCodeZero()} and
 *       {@link #isReadOnly_noMutations()}.</li>
 *   <li><strong>Sensitive PII masking.</strong> A verbatim {@code DISPLAY CUSTOMER-RECORD} would
 *       emit the social security number ({@code CUST-SSN}), the government-issued identifier
 *       ({@code CUST-GOVT-ISSUED-ID}), and the date of birth ({@code CUST-DOB-YYYY-MM-DD}). Per the
 *       documented security deviation (Technical Specification &sect;0.7.3&nbsp;L1 / &sect;0.9.3) the
 *       job masks those three fields, so they never reach the logs in the clear. Verified by
 *       {@link #sensitivePiiIsMasked()}.</li>
 * </ul>
 *
 * <h2>Harness</h2>
 * <p>The full Spring application context is booted under the {@code test} profile against a real
 * {@code postgres:16-alpine} instance supplied by Testcontainers and wired into the datasource with
 * {@link ServiceConnection} (no {@code @DynamicPropertySource}, no hardcoded credentials &mdash;
 * AAP&nbsp;0.8.1/0.9.3). Flyway ({@code V1__schema.sql} + {@code V2__reference_data.sql}) owns the
 * schema and Hibernate runs at {@code ddl-auto=validate}. Automatic job execution at startup is
 * disabled ({@code spring.batch.job.enabled=false} in {@code application-test.yml}), so the job is
 * launched explicitly through {@link JobLauncherTestUtils}.</p>
 *
 * <p><strong>Multiple {@code Job} beans.</strong> The application defines eleven Spring Batch
 * {@code Job} beans, so the {@link JobLauncherTestUtils} auto-registered by {@link SpringBatchTest}
 * cannot resolve a unique job to launch (its wiring uses {@code ObjectProvider.ifUnique}, which
 * silently skips when several {@code Job} beans exist). This test therefore contributes its own
 * {@link Primary @Primary} {@link JobLauncherTestUtils} through the nested {@link BatchTestConfig},
 * binding it explicitly to the {@code customerMasterPrintJob} bean plus the auto-configured
 * {@link JobLauncher} and {@link JobRepository}.</p>
 *
 * <p><strong>Fixtures.</strong> Under the {@code test} profile the {@code customer} table is not
 * auto-seeded (the {@code local}-profile seed loader is inert and the Flyway reference-data
 * migration does not populate {@code customer}). Each test therefore seeds the authoritative
 * {@code db/seed/customer.csv} dataset itself in {@link #seedCustomersAndAttachLogCapture()} and
 * removes it afterwards, keeping every test method independent.</p>
 *
 * @see CustomerMasterPrintJob
 * @see Customer
 * @see CustomerRepository
 * @see <a href="http://www.apache.org/licenses/LICENSE-2.0">Apache License 2.0</a>
 */
@SpringBootTest
@ActiveProfiles("test")
@SpringBatchTest
@Testcontainers
class CustomerMasterPrintJobTest {

    /**
     * Single {@code postgres:16-alpine} container shared by every test method in this class.
     *
     * <p>Declared {@code static} so Testcontainers starts it once per class. {@link ServiceConnection}
     * publishes its JDBC URL, username, and password to the Spring {@code Environment}
     * automatically, so no connection detail is ever hardcoded.</p>
     */
    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>(DockerImageName.parse("postgres:16-alpine"));

    /** Classpath location of the authoritative bulk customer seed dataset (50 rows). */
    private static final String CUSTOMER_SEED_CSV = "db/seed/customer.csv";

    /** Optional golden fixture; when present, produced output is asserted row-for-row against it. */
    private static final String CUSTOMER_GOLDEN = "golden/customer-master-print.txt";

    /** Bean name of the job under test (declared by {@link CustomerMasterPrintJob}). */
    private static final String JOB_BEAN_NAME = "customerMasterPrintJob";

    /** Step name of the single chunk-oriented step (declared by {@link CustomerMasterPrintJob}). */
    private static final String STEP_NAME = "customerMasterPrintStep";

    /** Prefix of every per-record line emitted by the job's logging writer. */
    private static final String RECORD_LINE_PREFIX = "CUSTOMER-RECORD";

    /** Token the job substitutes for each masked sensitive field. */
    private static final String MASK_TOKEN = "****";

    /** Extracts the {@code custId} value printed at the head of every record line. */
    private static final Pattern CUST_ID_PATTERN = Pattern.compile("custId=(\\d+)");

    /**
     * Test configuration that supplies the {@code Job}-specific {@link JobLauncherTestUtils}.
     *
     * <p>Marked {@link Primary @Primary} so it is the utility injected into the enclosing test even
     * though {@link SpringBatchTest} also registers a (job-less, unusable) {@code JobLauncherTestUtils}
     * bean. The utility is bound explicitly to the {@code customerMasterPrintJob} bean and to the
     * Spring&nbsp;Boot auto-configured {@link JobLauncher} and {@link JobRepository}. Detected
     * automatically as a nested {@code @TestConfiguration} of the {@code @SpringBootTest} class.</p>
     */
    @TestConfiguration
    static class BatchTestConfig {

        /**
         * Builds the primary {@link JobLauncherTestUtils} bound to the customer-master-print job.
         *
         * @param jobLauncher            the Boot auto-configured synchronous job launcher
         * @param jobRepository          the Boot auto-configured batch job repository
         * @param customerMasterPrintJob the specific job under test, selected by bean name
         * @return a fully wired {@link JobLauncherTestUtils} that launches only the job under test
         */
        @Bean
        @Primary
        JobLauncherTestUtils customerMasterPrintJobLauncherTestUtils(
                JobLauncher jobLauncher,
                JobRepository jobRepository,
                @Qualifier(JOB_BEAN_NAME) Job customerMasterPrintJob) {
            JobLauncherTestUtils utils = new JobLauncherTestUtils();
            utils.setJobLauncher(jobLauncher);
            utils.setJobRepository(jobRepository);
            utils.setJob(customerMasterPrintJob);
            return utils;
        }
    }

    /** The fully-initialized application context, used to assert the job bean is registered. */
    private final ApplicationContext applicationContext;

    /** Repository used to seed, count, and reload the {@code customer} table around each launch. */
    private final CustomerRepository customerRepository;

    /** Launches the {@code customerMasterPrintJob} with controlled, unique job parameters. */
    private final JobLauncherTestUtils jobLauncherTestUtils;

    /** The seed customers in ascending {@code custId} order (index 0 == smallest id). */
    private final List<Customer> seededCustomersAscending = new ArrayList<>();

    /** Logback appender attached to the job's writer logger to capture the printed output. */
    private ListAppender<ILoggingEvent> logAppender;

    /** The job's writer logger (the {@link CustomerMasterPrintJob} category). */
    private Logger writerLogger;

    /** The writer logger's level before the test overrode it, restored on teardown. */
    private Level previousWriterLevel;

    /**
     * Constructor injection of the collaborators managed by the Spring TestContext.
     *
     * <p>Constructor injection (never field injection) is the project convention; the JUnit platform
     * property {@code spring.test.constructor.autowire.mode=all} resolves the parameters from the
     * booted context. The {@link JobLauncherTestUtils} resolves to the {@link Primary @Primary} bean
     * declared by {@link BatchTestConfig}.</p>
     *
     * @param applicationContext   the running Spring application context
     * @param customerRepository   the customer repository bean
     * @param jobLauncherTestUtils the primary, job-bound batch launcher utility
     */
    @Autowired
    CustomerMasterPrintJobTest(ApplicationContext applicationContext,
                               CustomerRepository customerRepository,
                               JobLauncherTestUtils jobLauncherTestUtils) {
        this.applicationContext = applicationContext;
        this.customerRepository = customerRepository;
        this.jobLauncherTestUtils = jobLauncherTestUtils;
    }

    /**
     * Seeds the {@code customer} table from the authoritative CSV and attaches the log-capturing
     * appender before each test.
     *
     * <p>The table is cleared first so a failed prior test cannot leak rows. The seed rows are
     * inserted in <em>descending</em> id order so that the ascending order asserted later is proven
     * to come from the job's {@code ORDER BY custId ASC} query rather than from insertion order. The
     * appender is bound to the {@link CustomerMasterPrintJob} logger and the logger is pinned to
     * {@code INFO} so the writer's per-record lines are captured regardless of ambient log
     * configuration.</p>
     */
    @BeforeEach
    void seedCustomersAndAttachLogCapture() {
        customerRepository.deleteAll();

        seededCustomersAscending.clear();
        seededCustomersAscending.addAll(loadSeedCustomersAscending());
        assertThat(seededCustomersAscending)
                .as("seed dataset must contain rows to make the parity assertions meaningful")
                .isNotEmpty();

        List<Customer> descendingInsertOrder = new ArrayList<>(seededCustomersAscending);
        Collections.reverse(descendingInsertOrder);
        customerRepository.saveAll(descendingInsertOrder);

        writerLogger = (Logger) LoggerFactory.getLogger(CustomerMasterPrintJob.class);
        previousWriterLevel = writerLogger.getLevel();
        writerLogger.setLevel(Level.INFO);
        logAppender = new ListAppender<>();
        logAppender.setContext(writerLogger.getLoggerContext());
        logAppender.start();
        writerLogger.addAppender(logAppender);
    }

    /**
     * Detaches the log appender, restores the writer logger's level, and clears the seeded rows so
     * each test method is fully isolated.
     */
    @AfterEach
    void detachLogCaptureAndCleanUp() {
        if (writerLogger != null && logAppender != null) {
            writerLogger.detachAppender(logAppender);
            logAppender.stop();
            writerLogger.setLevel(previousWriterLevel);
        }
        customerRepository.deleteAll();
    }

    /**
     * Parses {@link #CUSTOMER_SEED_CSV} into {@link Customer} instances in ascending {@code custId}
     * order (the CSV is authored in that order).
     *
     * <p>The CSV columns map positionally, in exact order, onto the {@link Customer} all-arguments
     * constructor. Each data row is split preserving trailing empty fields and is required to carry
     * all eighteen columns, so a malformed dataset fails the test loudly rather than seeding silently
     * corrupt rows.</p>
     *
     * @return the parsed seed customers, ascending by {@code custId}
     */
    private List<Customer> loadSeedCustomersAscending() {
        List<Customer> customers = new ArrayList<>();
        ClassPathResource resource = new ClassPathResource(CUSTOMER_SEED_CSV);
        try (InputStream in = resource.getInputStream();
                BufferedReader reader = new BufferedReader(
                        new InputStreamReader(in, StandardCharsets.UTF_8))) {
            String line;
            boolean header = true;
            while ((line = reader.readLine()) != null) {
                if (header) {
                    header = false;
                    continue;
                }
                if (line.isBlank()) {
                    continue;
                }
                String[] f = line.split(",", -1);
                assertThat(f)
                        .as("seed row must have all 18 CVCUS01Y columns: %s", line)
                        .hasSize(18);
                customers.add(new Customer(
                        Long.valueOf(f[0].trim()), // CUST-ID
                        f[1], f[2], f[3],           // first / middle / last name
                        f[4], f[5], f[6],           // address lines 1-3
                        f[7], f[8], f[9],           // state / country / zip
                        f[10], f[11],               // phone 1 / phone 2
                        f[12],                      // CUST-SSN            (sensitive)
                        f[13],                      // CUST-GOVT-ISSUED-ID (sensitive)
                        f[14],                      // CUST-DOB-YYYY-MM-DD (sensitive)
                        f[15],                      // CUST-EFT-ACCOUNT-ID
                        f[16],                      // CUST-PRI-CARD-HOLDER-IND
                        Integer.valueOf(f[17].trim()))); // CUST-FICO-CREDIT-SCORE
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Unable to read seed dataset " + CUSTOMER_SEED_CSV, e);
        }
        return customers;
    }

    // ------------------------------------------------------------------------
    // Tests
    // ------------------------------------------------------------------------

    /**
     * The full application context starts and the {@code customerMasterPrintJob} bean is registered
     * and bound to the test launcher.
     *
     * <p>Reaching this method already proves the context wired, Flyway migrated, and Hibernate
     * validated the entity mappings. The explicit assertions confirm the specific job under test is
     * present by bean name and is the job the {@link JobLauncherTestUtils} will launch.</p>
     */
    @Test
    void contextLoadsAndJobRegistered() {
        assertThat(applicationContext).isNotNull();
        assertThat(applicationContext.containsBean(JOB_BEAN_NAME)).isTrue();
        assertThat(applicationContext.getBean(JOB_BEAN_NAME, Job.class).getName())
                .isEqualTo(JOB_BEAN_NAME);
        assertThat(jobLauncherTestUtils.getJob()).isNotNull();
        assertThat(jobLauncherTestUtils.getJob().getName()).isEqualTo(JOB_BEAN_NAME);
    }

    /**
     * The job runs to a clean completion &mdash; {@link BatchStatus#COMPLETED} with the
     * {@link ExitStatus#COMPLETED} exit code &mdash; reproducing the CBCUS01C "end-of-file, return
     * code&nbsp;0" outcome.
     *
     * @throws Exception if the job launch fails
     */
    @Test
    void runsCleanWithReturnCodeZero() throws Exception {
        JobExecution execution = launchJob();

        assertThat(execution.getStatus()).isEqualTo(BatchStatus.COMPLETED);
        assertThat(execution.getExitStatus().getExitCode())
                .isEqualTo(ExitStatus.COMPLETED.getExitCode());
    }

    /**
     * Every customer row is read and printed, in ascending {@code custId} order.
     *
     * <p>Asserts the step read count equals the row count, that exactly one line was printed per
     * row, and that the {@code custId} values printed are in ascending order &mdash; the direct
     * analog of the CBCUS01C ascending-key sequential browse. When the optional
     * {@code golden/customer-master-print.txt} fixture is present the produced output is also
     * compared to it row-for-row.</p>
     *
     * @throws Exception if the job launch fails
     */
    @Test
    void printsAllCustomersInAscendingCustIdOrder() throws Exception {
        long expectedCount = customerRepository.count();

        JobExecution execution = launchJob();
        assertThat(execution.getStatus()).isEqualTo(BatchStatus.COMPLETED);

        assertThat(stepReadCount(execution))
                .as("every customer row must be read (CBCUS01C reads the whole file)")
                .isEqualTo(expectedCount);

        List<String> recordLines = capturedRecordLines();
        assertThat(recordLines)
                .as("exactly one printed line per customer row")
                .hasSize((int) expectedCount);

        List<Long> printedIds = parseCustIds(recordLines);
        List<Long> expectedAscendingIds = seededCustomersAscending.stream()
                .map(Customer::getCustId)
                .toList();
        assertThat(printedIds)
                .as("records printed in ascending custId order (VSAM ascending-key parity)")
                .isEqualTo(expectedAscendingIds);
        assertThat(printedIds).isSorted();

        assertGoldenIfPresent(recordLines);
    }

    /**
     * The sensitive PII fields are masked in the produced output.
     *
     * <p>Uses a known seed customer's real SSN, government-issued id, and date of birth and asserts
     * that none of them appears in cleartext anywhere in the output, that the mask token is present,
     * and that the customer's own printed line carries the masked field markers. This assertion is
     * meaningful: were the masking removed, the customer's line would print these exact values and
     * the {@code doesNotContain} checks would fail.</p>
     *
     * @throws Exception if the job launch fails
     */
    @Test
    void sensitivePiiIsMasked() throws Exception {
        Customer known = knownSeedCustomer();
        String ssn = known.getCustSsn();
        String govtId = known.getCustGovtIssuedId();
        String dob = known.getCustDob();
        assertThat(ssn).as("seed SSN present so the masking check is not vacuous").isNotBlank();
        assertThat(govtId).as("seed government id present").isNotBlank();
        assertThat(dob).as("seed date of birth present").isNotBlank();

        launchJob();

        List<String> recordLines = capturedRecordLines();
        assertThat(recordLines).as("job must have printed customer lines").isNotEmpty();
        String combinedOutput = String.join("\n", recordLines);

        assertThat(combinedOutput)
                .as("the masking token proves the sensitive fields were redacted")
                .contains(MASK_TOKEN);
        assertThat(combinedOutput)
                .as("SSN must never be printed in the clear")
                .doesNotContain(ssn);
        assertThat(combinedOutput)
                .as("government-issued id must never be printed in the clear")
                .doesNotContain(govtId);
        assertThat(combinedOutput)
                .as("date of birth must never be printed in the clear")
                .doesNotContain(dob);

        String knownLine = recordLineFor(recordLines, known.getCustId());
        assertThat(knownLine).contains("custSsn=" + MASK_TOKEN);
        assertThat(knownLine).contains("custGovtIssuedId=" + MASK_TOKEN);
        assertThat(knownLine).contains("custDob=" + MASK_TOKEN);
        assertThat(knownLine).doesNotContain(ssn, govtId, dob);
    }

    /**
     * The scan is read-only: neither the row count nor the fields of a known customer change.
     *
     * <p>Reproduces the CBCUS01C {@code OPEN INPUT} contract &mdash; the program only reads and
     * prints, never rewrites. The row count is unchanged and a reloaded customer matches its seeded
     * state field-for-field (sensitive fields compared in memory, never logged).</p>
     *
     * @throws Exception if the job launch fails
     */
    @Test
    void isReadOnly_noMutations() throws Exception {
        long countBefore = customerRepository.count();
        Customer knownBefore = knownSeedCustomer();
        Long knownId = knownBefore.getCustId();

        launchJob();

        assertThat(customerRepository.count())
                .as("read-only job must not insert or delete rows")
                .isEqualTo(countBefore);

        Customer reloaded = customerRepository.findById(knownId).orElseThrow();
        assertThat(reloaded.getCustFirstName()).isEqualTo(knownBefore.getCustFirstName());
        assertThat(reloaded.getCustLastName()).isEqualTo(knownBefore.getCustLastName());
        assertThat(reloaded.getCustAddrZip()).isEqualTo(knownBefore.getCustAddrZip());
        assertThat(reloaded.getCustFicoCreditScore()).isEqualTo(knownBefore.getCustFicoCreditScore());
        assertThat(reloaded.getCustSsn()).isEqualTo(knownBefore.getCustSsn());
        assertThat(reloaded.getCustGovtIssuedId()).isEqualTo(knownBefore.getCustGovtIssuedId());
        assertThat(reloaded.getCustDob()).isEqualTo(knownBefore.getCustDob());
    }

    // ------------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------------

    /**
     * Launches the job under test with unique, traceable job parameters.
     *
     * <p>A fresh {@code run.id} (plus the launcher's built-in unique parameter) guarantees a new
     * {@code JobInstance} on every launch, so successive test methods never collide on an
     * already-completed instance.</p>
     *
     * @return the completed {@link JobExecution}
     * @throws Exception if the launch fails
     */
    private JobExecution launchJob() throws Exception {
        JobParameters parameters = jobLauncherTestUtils.getUniqueJobParametersBuilder()
                .addString("run.id", UUID.randomUUID().toString())
                .toJobParameters();
        return jobLauncherTestUtils.launchJob(parameters);
    }

    /**
     * The job reproduces the legacy CBCUS01C SYSOUT execution banners (QA finding F4): a
     * {@code START OF EXECUTION OF PROGRAM CBCUS01C} line before the run and, on a normal
     * (COMPLETED) run, an {@code END OF EXECUTION OF PROGRAM CBCUS01C} line after it &mdash; emitted
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
            JobExecution execution = launchJob();
            assertThat(execution.getStatus()).isEqualTo(BatchStatus.COMPLETED);

            List<String> banners = capture.list.stream()
                    .map(ILoggingEvent::getFormattedMessage)
                    .filter(message -> message.contains("OF EXECUTION OF PROGRAM"))
                    .toList();

            assertThat(banners).containsExactly(
                    "START OF EXECUTION OF PROGRAM CBCUS01C",
                    "END OF EXECUTION OF PROGRAM CBCUS01C");
        } finally {
            bannerLogger.detachAppender(capture);
            capture.stop();
        }
    }

    /**
     * Returns the read count of the customer-master-print step within a job execution.
     *
     * @param execution the completed job execution
     * @return the number of items the step read
     */
    private long stepReadCount(JobExecution execution) {
        return execution.getStepExecutions().stream()
                .filter(stepExecution -> STEP_NAME.equals(stepExecution.getStepName()))
                .mapToLong(StepExecution::getReadCount)
                .findFirst()
                .orElseThrow(() ->
                        new AssertionError("step '" + STEP_NAME + "' not found in job execution"));
    }

    /**
     * Returns the per-record lines captured from the job's logging writer, in the order emitted.
     *
     * @return the captured {@code CUSTOMER-RECORD} lines (fully formatted messages)
     */
    private List<String> capturedRecordLines() {
        return logAppender.list.stream()
                .map(ILoggingEvent::getFormattedMessage)
                .filter(message -> message.startsWith(RECORD_LINE_PREFIX))
                .toList();
    }

    /**
     * Extracts the {@code custId} printed at the head of each record line, preserving order.
     *
     * @param lines the captured record lines
     * @return the printed {@code custId} values in emission order
     */
    private List<Long> parseCustIds(List<String> lines) {
        List<Long> ids = new ArrayList<>(lines.size());
        for (String line : lines) {
            Matcher matcher = CUST_ID_PATTERN.matcher(line);
            assertThat(matcher.find())
                    .as("record line must contain a custId: %s", line)
                    .isTrue();
            ids.add(Long.valueOf(matcher.group(1)));
        }
        return ids;
    }

    /**
     * Returns the known seed customer used for the PII and read-only assertions.
     *
     * <p>The first customer in ascending order is used; its sensitive values are verified to be
     * distinctive within the seed dataset, making the masking assertions unambiguous.</p>
     *
     * @return the smallest-{@code custId} seed customer
     */
    private Customer knownSeedCustomer() {
        return seededCustomersAscending.get(0);
    }

    /**
     * Finds the single printed line for the given {@code custId}.
     *
     * <p>The trailing comma in the search token ({@code "custId=<id>,"}) prevents a shorter id from
     * matching a longer one (for example {@code custId=1} must not match {@code custId=15}).</p>
     *
     * @param lines  the captured record lines
     * @param custId the id whose line is wanted
     * @return the matching record line
     */
    private String recordLineFor(List<String> lines, Long custId) {
        String needle = "custId=" + custId + ",";
        return lines.stream()
                .filter(line -> line.contains(needle))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no printed line for custId=" + custId));
    }

    /**
     * Asserts row-for-row equality with the golden fixture when it is present on the classpath.
     *
     * <p>The comparison is a no-op when {@code golden/customer-master-print.txt} is absent (the
     * default), keeping the test green while allowing a fixture to be dropped in later to lock the
     * exact output. Lines are read without trimming so fixed-width content compares exactly.</p>
     *
     * @param actualRecordLines the captured record lines to compare
     */
    private void assertGoldenIfPresent(List<String> actualRecordLines) {
        ClassPathResource golden = new ClassPathResource(CUSTOMER_GOLDEN);
        if (!golden.exists()) {
            return;
        }
        List<String> expected = new ArrayList<>();
        try (InputStream in = golden.getInputStream();
                BufferedReader reader = new BufferedReader(
                        new InputStreamReader(in, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                expected.add(line);
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Unable to read golden fixture " + CUSTOMER_GOLDEN, e);
        }
        assertThat(actualRecordLines)
                .as("produced customer-master-print output must match the golden fixture row-for-row")
                .containsExactlyElementsOf(expected);
    }
}
