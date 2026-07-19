package com.aws.carddemo.batch;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import com.aws.carddemo.AbstractPostgresIntegrationTest;
import com.aws.carddemo.util.DateConversionService;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
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
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.autoconfigure.data.jpa.JpaRepositoriesAutoConfiguration;
import org.springframework.boot.autoconfigure.orm.jpa.HibernateJpaAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

/**
 * End-to-end Failsafe integration/parity test for {@link DateConversionJobConfig} &mdash; the Spring
 * Batch job ({@code dateConversionJob}) that exposes the mainframe date-validation utility
 * {@code CSUTLDTC} as a batch-invocable {@link Job}. It launches the real job against a real
 * PostgreSQL database (via {@link AbstractPostgresIntegrationTest}'s Testcontainers instance) and
 * asserts that valid and invalid dates are surfaced exactly as the COBOL does.
 *
 * <p><strong>Origin (read-only oracle):</strong> {@code legacy/cbl/CSUTLDTC.cbl}
 * (source branch {@code app/cbl/CSUTLDTC.cbl}). On z/OS {@code CSUTLDTC} is a called subprogram
 * &mdash; {@code PROCEDURE DIVISION USING LS-DATE PIC X(10), LS-DATE-FORMAT PIC X(10),
 * LS-RESULT PIC X(80)} (L88) &mdash; that calls the Language Environment service {@code CEEDAYS}
 * (L116-L120) to validate {@code LS-DATE} against the {@code LS-DATE-FORMAT} picture mask, evaluates
 * the {@code CEEDAYS} feedback-code 88-levels ({@code FC-INVALID-DATE}, {@code FC-BAD-DATE-VALUE},
 * {@code FC-INVALID-MONTH}, {@code FC-NON-NUMERIC-DATA}, &hellip; at L62-L70), then moves the CEE
 * severity to {@code RETURN-CODE} and {@code EXIT PROGRAM}s (L97-L100). Governing spec: AAP
 * &sect;0.6.9 ({@code CSUTLDTC} &rarr; {@code DateConversionService}, exposed as a batch job).</p>
 *
 * <p><strong>Central parity quirk verified here &mdash; an invalid date does NOT abend.</strong>
 * {@code CSUTLDTC} never abnormally terminates for a bad date: {@code CEEDAYS} returns a non-zero
 * feedback code, the program records the severity in {@code RETURN-CODE}, and control returns to the
 * caller normally. The Java parity, asserted by these tests, is that an invalid date <em>completes</em>
 * the job (batch status {@link BatchStatus#COMPLETED}, never {@link BatchStatus#FAILED}) with an exit
 * status whose code is {@link DateConversionJobConfig#EXIT_CODE_INVALID_DATE} &mdash; the launch does
 * <strong>not</strong> raise an exception and the step does <strong>not</strong> fail the job. A valid
 * date completes with the default {@link ExitStatus#COMPLETED}. A test that expected an exception on a
 * bad date would invert the parity; the invalid-date cases therefore assert
 * {@code assertThatCode(...).doesNotThrowAnyException()} plus a non-failed status and the
 * invalid-outcome exit code.</p>
 *
 * <p><strong>Harness.</strong> This test extends {@link AbstractPostgresIntegrationTest}
 * ({@code @ActiveProfiles("test")} + {@code @Testcontainers}) for the real {@code postgres:18-alpine}
 * container, and pins its own {@code @SpringBootTest} {@code classes} to exactly two nested members:
 * {@link DateConversionSliceConfig} (the focused slice &mdash; the {@code DataSource}, Flyway, Spring
 * Batch and the two beans under test) and {@link DateConversionJobTestConfig} (the
 * {@link JobLauncherTestUtils} provider). Both are enumerated explicitly because naming {@code classes}
 * suppresses Spring's default detection of nested configuration classes, so a nested
 * {@code @TestConfiguration} is <em>not</em> auto-registered and must be listed here. It deliberately
 * does <strong>not</strong> use {@code @SpringBatchTest}; instead {@link DateConversionJobTestConfig}
 * supplies exactly one {@link JobLauncherTestUtils}, wired to the auto-configured
 * {@link JobLauncher}/{@link JobRepository} and the {@code dateConversionJob} bean selected by
 * {@link Qualifier @Qualifier}. Jobs are launched explicitly (the {@code test} profile sets
 * {@code spring.batch.job.enabled=false}, so nothing runs at startup), and every launch carries a
 * unique {@code run.id} parameter so each run is a fresh job instance. No business tables are touched
 * and no monetary values are involved.</p>
 *
 * @see DateConversionJobConfig
 * @see DateConversionSliceConfig
 * @see com.aws.carddemo.util.DateConversionService
 */
@SpringBootTest(classes = {
        DateConversionJobConfigIT.DateConversionSliceConfig.class,
        DateConversionJobConfigIT.DateConversionJobTestConfig.class
})
class DateConversionJobConfigIT extends AbstractPostgresIntegrationTest {

    /**
     * A real leap day (29 February 2024) rendered in the default {@code YYYYMMDD} mask applied when no
     * {@code format} parameter is supplied. {@code CEEDAYS} accepts it &rarr; the job completes valid.
     */
    private static final String VALID_LEAP_DATE = "20240229";

    /**
     * 29 February 2023 &mdash; February 29 in a non-leap year, which {@code CEEDAYS} rejects
     * ({@code FC-BAD-DATE-VALUE}). Well-formed but non-existent, so it exercises the invalid-but-no-abend
     * path without being a mere formatting error.
     */
    private static final String INVALID_LEAP_DATE = "20230229";

    /** 28 February 2023 &mdash; a well-formed, existing non-leap date; the valid boundary case. */
    private static final String VALID_NON_LEAP_DATE = "20230228";

    /** Month component {@code 13}, outside 1-12 &mdash; {@code CEEDAYS} {@code FC-INVALID-MONTH}. */
    private static final String INVALID_MONTH_DATE = "20241301";

    /** Explicit {@code CEEDAYS} picture mask with separators, mirroring {@code LS-DATE-FORMAT}. */
    private static final String ISO_MASK = "YYYY-MM-DD";

    /** The same leap day as {@link #VALID_LEAP_DATE}, formatted for the {@link #ISO_MASK} mask. */
    private static final String VALID_ISO_LEAP_DATE = "2024-02-29";

    /** Job-parameter key for the date to validate (COBOL {@code LS-DATE}). */
    private static final String PARAM_DATE = "date";

    /** Job-parameter key for the optional picture mask (COBOL {@code LS-DATE-FORMAT}). */
    private static final String PARAM_FORMAT = "format";

    /** Job-parameter key carrying a unique value so every launch is a distinct job instance. */
    private static final String PARAM_RUN_ID = "run.id";

    /** The single {@link JobLauncherTestUtils} supplied by {@link DateConversionJobTestConfig}. */
    @Autowired
    private JobLauncherTestUtils jobLauncherTestUtils;

    /**
     * Focused Spring Boot slice that boots ONLY the collaborators the date-conversion job needs, so the
     * parity test is hermetic and independent of the rest of the migrated module:
     * <ul>
     *   <li>the auto-configured {@code DataSource} &mdash; the Testcontainers {@code postgres:18-alpine}
     *       instance supplied by {@link AbstractPostgresIntegrationTest};</li>
     *   <li>Flyway, which applies the real {@code V0}-{@code V3} migrations into the container
     *       (crucially {@code V0__spring_batch_metadata.sql}, which creates the Spring Batch metadata
     *       tables the {@link JobRepository} requires);</li>
     *   <li>Spring Boot's Batch auto-configuration &mdash; the {@link JobRepository},
     *       {@link JobLauncher} and the
     *       {@link org.springframework.transaction.PlatformTransactionManager} that
     *       {@link DateConversionJobConfig} constructor-injects;</li>
     *   <li>the two beans under test, imported explicitly: {@link DateConversionJobConfig} and its
     *       {@link DateConversionService} delegate.</li>
     * </ul>
     *
     * <p>It performs no component scanning, so the online-service, controller, security and web beans of
     * the full application &mdash; none of which the date-conversion job touches &mdash; never enter this
     * context; the test is therefore immune to unrelated wiring elsewhere in the module. The
     * JPA/Hibernate auto-configurations are excluded because the job performs no entity or
     * business-table I/O; excluding them also means no Hibernate schema-validation runs, decoupling this
     * batch test from the module-wide fixed-width {@code CHAR} entity-mapping work
     * (AAP &sect;0.6.2/&sect;0.7.3) that is owned elsewhere. Flyway still creates the production schema in
     * the container, so the job runs against the genuine migrated database.</p>
     */
    @Configuration(proxyBeanMethods = false)
    @EnableAutoConfiguration(exclude = {
            HibernateJpaAutoConfiguration.class,
            JpaRepositoriesAutoConfiguration.class
    })
    @Import({DateConversionJobConfig.class, DateConversionService.class})
    static class DateConversionSliceConfig {
    }

    /**
     * Supplies exactly one {@link JobLauncherTestUtils} bound to the {@code dateConversionJob} without
     * relying on {@code @SpringBatchTest}. The mandated {@link Qualifier @Qualifier} on the {@link Job}
     * parameter selects {@code dateConversionJob} by name (guarding against ambiguity should further
     * {@link Job} beans ever be added to the slice); {@link JobLauncher} and {@link JobRepository} come
     * from Spring Boot's Batch auto-configuration.
     */
    @TestConfiguration
    static class DateConversionJobTestConfig {

        /**
         * Builds the test utility, wiring the launcher, repository and the specific job under test.
         *
         * @param jobLauncher       the auto-configured Spring Batch {@link JobLauncher}
         * @param jobRepository     the auto-configured Spring Batch {@link JobRepository}
         * @param dateConversionJob the {@code dateConversionJob} bean (selected by {@link Qualifier})
         * @return a {@link JobLauncherTestUtils} ready to launch {@code dateConversionJob}
         */
        @Bean
        JobLauncherTestUtils dateConversionJobLauncherTestUtils(
                JobLauncher jobLauncher,
                JobRepository jobRepository,
                @Qualifier(DateConversionJobConfig.JOB_NAME) Job dateConversionJob) {
            JobLauncherTestUtils utils = new JobLauncherTestUtils();
            utils.setJobLauncher(jobLauncher);
            utils.setJobRepository(jobRepository);
            utils.setJob(dateConversionJob);
            return utils;
        }
    }

    /**
     * A valid date completes the job with {@link BatchStatus#COMPLETED} and the default success exit
     * status &mdash; the {@code CSUTLDTC} {@code RETURN-CODE 0} equivalent.
     *
     * @throws Exception if the launcher fails for an infrastructural reason (never for a bad date)
     */
    @Test
    void validDateCompletesSuccessfully() throws Exception {
        JobExecution execution = jobLauncherTestUtils.launchJob(dateParameters(VALID_LEAP_DATE));

        assertThat(execution.getStatus()).isEqualTo(BatchStatus.COMPLETED);
        assertThat(execution.getExitStatus().getExitCode())
                .isEqualTo(ExitStatus.COMPLETED.getExitCode());
    }

    /**
     * Headline parity assertion: an invalid date must NOT throw and must NOT fail the job. Mirrors
     * {@code CSUTLDTC}, where {@code CEEDAYS} returns a feedback code and the program {@code EXIT
     * PROGRAM}s normally rather than abending. The launch is wrapped in
     * {@code assertThatCode(...).doesNotThrowAnyException()} to prove no exception escapes, and the
     * resulting status is asserted {@link BatchStatus#COMPLETED} (never {@link BatchStatus#FAILED}).
     */
    @Test
    void invalidDateDoesNotThrowAndJobDoesNotFail() {
        JobParameters parameters = dateParameters(INVALID_LEAP_DATE);
        AtomicReference<JobExecution> executionRef = new AtomicReference<>();

        assertThatCode(() -> executionRef.set(jobLauncherTestUtils.launchJob(parameters)))
                .doesNotThrowAnyException();

        JobExecution execution = executionRef.get();
        assertThat(execution).isNotNull();
        assertThat(execution.getStatus())
                .isEqualTo(BatchStatus.COMPLETED)
                .isNotEqualTo(BatchStatus.FAILED);
    }

    /**
     * An invalid date completes the job but surfaces the invalid outcome through the exit status code
     * {@link DateConversionJobConfig#EXIT_CODE_INVALID_DATE} &mdash; the non-zero {@code RETURN-CODE}
     * equivalent that a launcher or downstream step can branch on, while the batch status stays
     * {@link BatchStatus#COMPLETED}.
     *
     * @throws Exception if the launcher fails for an infrastructural reason (never for a bad date)
     */
    @Test
    void invalidDateExitStatusReflectsInvalidOutcome() throws Exception {
        JobExecution execution = jobLauncherTestUtils.launchJob(dateParameters(INVALID_LEAP_DATE));

        assertThat(execution.getStatus()).isEqualTo(BatchStatus.COMPLETED);
        assertThat(execution.getExitStatus().getExitCode())
                .isEqualTo(DateConversionJobConfig.EXIT_CODE_INVALID_DATE);
    }

    /**
     * A month component outside 1-12 ({@code CEEDAYS} {@code FC-INVALID-MONTH}) is treated as an
     * invalid date: no exception is thrown, the job completes (not failed), and the exit status carries
     * {@link DateConversionJobConfig#EXIT_CODE_INVALID_DATE}. Confirms the invalid-but-no-abend contract
     * across a second feedback-code category.
     */
    @Test
    void invalidMonthTreatedAsInvalid_noThrow() {
        JobParameters parameters = dateParameters(INVALID_MONTH_DATE);
        AtomicReference<JobExecution> executionRef = new AtomicReference<>();

        assertThatCode(() -> executionRef.set(jobLauncherTestUtils.launchJob(parameters)))
                .doesNotThrowAnyException();

        JobExecution execution = executionRef.get();
        assertThat(execution).isNotNull();
        assertThat(execution.getStatus()).isEqualTo(BatchStatus.COMPLETED);
        assertThat(execution.getExitStatus().getExitCode())
                .isEqualTo(DateConversionJobConfig.EXIT_CODE_INVALID_DATE);
    }

    /**
     * Boundary case: a well-formed, existing non-leap date (28 February 2023) is valid and completes
     * with the default success exit status, confirming the valid path is not over-eagerly rejecting
     * dates adjacent to the leap-day boundary.
     *
     * @throws Exception if the launcher fails for an infrastructural reason
     */
    @Test
    void wellFormedNonLeapDateIsValid() throws Exception {
        JobExecution execution = jobLauncherTestUtils.launchJob(dateParameters(VALID_NON_LEAP_DATE));

        assertThat(execution.getStatus()).isEqualTo(BatchStatus.COMPLETED);
        assertThat(execution.getExitStatus().getExitCode())
                .isEqualTo(ExitStatus.COMPLETED.getExitCode());
    }

    /**
     * Exercises the explicit-mask branch of the tasklet: supplying a {@code format} job parameter
     * ({@link #ISO_MASK}) alongside a date in that mask ({@link #VALID_ISO_LEAP_DATE}) routes through
     * the two-argument {@code validateDate} overload (COBOL {@code LS-DATE-FORMAT}) and still completes
     * valid, proving the mask is honored rather than ignored in favor of the default.
     *
     * @throws Exception if the launcher fails for an infrastructural reason
     */
    @Test
    void validDateWithExplicitFormatMaskCompletesSuccessfully() throws Exception {
        JobExecution execution =
                jobLauncherTestUtils.launchJob(dateParameters(VALID_ISO_LEAP_DATE, ISO_MASK));

        assertThat(execution.getStatus()).isEqualTo(BatchStatus.COMPLETED);
        assertThat(execution.getExitStatus().getExitCode())
                .isEqualTo(ExitStatus.COMPLETED.getExitCode());
    }

    /**
     * Builds job parameters for a date validated with the job's default {@code YYYYMMDD} mask (no
     * {@code format} parameter), plus a unique {@code run.id} so each launch is a fresh job instance.
     *
     * @param date the date to validate (COBOL {@code LS-DATE})
     * @return the job parameters for a single launch
     */
    private JobParameters dateParameters(String date) {
        return new JobParametersBuilder()
                .addString(PARAM_DATE, date)
                .addLong(PARAM_RUN_ID, System.nanoTime())
                .toJobParameters();
    }

    /**
     * Builds job parameters for a date validated against an explicit picture mask, plus a unique
     * {@code run.id} so each launch is a fresh job instance.
     *
     * @param date   the date to validate (COBOL {@code LS-DATE})
     * @param format the picture mask (COBOL {@code LS-DATE-FORMAT})
     * @return the job parameters for a single launch
     */
    private JobParameters dateParameters(String date, String format) {
        return new JobParametersBuilder()
                .addString(PARAM_DATE, date)
                .addString(PARAM_FORMAT, format)
                .addLong(PARAM_RUN_ID, System.nanoTime())
                .toJobParameters();
    }
}
