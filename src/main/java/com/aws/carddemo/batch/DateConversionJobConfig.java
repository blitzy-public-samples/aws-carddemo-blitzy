package com.aws.carddemo.batch;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.batch.core.ExitStatus;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.Step;
import org.springframework.batch.core.configuration.annotation.StepScope;
import org.springframework.batch.core.job.builder.JobBuilder;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.core.step.builder.StepBuilder;
import org.springframework.batch.core.step.tasklet.Tasklet;
import org.springframework.batch.repeat.RepeatStatus;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.PlatformTransactionManager;

import com.aws.carddemo.util.DateConversionService;
import com.aws.carddemo.util.DateConversionService.DateValidationResult;

/**
 * Spring Batch job configuration exposing the mainframe date-validation utility {@code CSUTLDTC}
 * as a batch-invocable {@link Job}.
 *
 * <p>Origin: {@code legacy/cbl/CSUTLDTC.cbl} (source branch {@code app/cbl/CSUTLDTC.cbl}).
 * Implements AAP &sect;0.4.1 ({@code CSUTLDTC} &rarr; {@code DateConversionService}, exposed as a
 * batch job) and &sect;0.6.9 (Language Environment date services &rarr; {@code java.time}).</p>
 *
 * <p>On z/OS, {@code CSUTLDTC} is a called subprogram &mdash;
 * {@code PROCEDURE DIVISION USING LS-DATE PIC X(10), LS-DATE-FORMAT PIC X(10), LS-RESULT PIC X(80)}
 * &mdash; that wraps the LE callable service {@code CEEDAYS} to validate {@code LS-DATE} against the
 * {@code LS-DATE-FORMAT} picture mask, returning a severity plus a 15-character feedback message in
 * {@code LS-RESULT} and moving the CEE severity to {@code RETURN-CODE}. This configuration adapts
 * that utility to a Spring Batch job so it can be launched on demand (for example from an operator
 * screen or a scheduled invocation) with the date and mask supplied as job parameters.</p>
 *
 * <p><strong>Deliberately thin (delegation, not reimplementation).</strong> All validation logic
 * &mdash; the {@code CEEDAYS}/{@code CEEDATE}/{@code CEECBLDY} replacement, the picture-mask
 * translation, and the {@code CEEDAYS} feedback-code ({@code FC-INVALID-DATE},
 * {@code FC-BAD-DATE-VALUE}, {@code FC-INVALID-MONTH}, {@code FC-NON-NUMERIC-DATA}, &hellip;)
 * mapping &mdash; already lives in {@link DateConversionService} (package {@code com.aws.carddemo.util}).
 * This class only marshals the job parameters into a single {@link DateConversionService#validateDate}
 * call, logs the structured {@link DateValidationResult} (mirroring {@code CSUTLDTC} returning
 * {@code LS-RESULT}), and translates the validation outcome into the step {@link ExitStatus}.
 * Re-implementing the date logic here would violate DRY and risk diverging from the service's
 * feedback-code semantics; the traceability matrix records this class as the batch adapter for
 * {@code CSUTLDTC} and {@link DateConversionService} as the behavioral home.</p>
 *
 * <p><strong>Exit-status mapping (parity with {@code MOVE WS-SEVERITY-N TO RETURN-CODE}).</strong>
 * {@code CSUTLDTC} never abends for a bad date: it returns a feedback code and a non-zero
 * {@code RETURN-CODE}, leaving the caller to branch on the severity. That behavior is preserved
 * here:</p>
 * <ul>
 *   <li><strong>Valid</strong> ({@code severity == 0}): the tasklet returns
 *       {@link RepeatStatus#FINISHED} without overriding the exit status, so the step and job
 *       complete with the default {@link ExitStatus#COMPLETED} &mdash; the {@code RETURN-CODE 0}
 *       equivalent.</li>
 *   <li><strong>Invalid</strong> ({@code severity != 0}): the tasklet still returns
 *       {@link RepeatStatus#FINISHED} (no exception, no abend), but sets a custom
 *       {@link ExitStatus} whose code is {@value #EXIT_CODE_INVALID_DATE} and whose description
 *       carries the severity, outcome and message. The batch status therefore remains
 *       {@code COMPLETED} while the exit code surfaces the invalid-date outcome for a launcher or
 *       downstream step to inspect &mdash; the non-zero {@code RETURN-CODE} equivalent.</li>
 * </ul>
 *
 * <p><strong>Wiring note (AAP binding constraint):</strong> this class deliberately does
 * <strong>not</strong> use {@code @EnableBatchProcessing} and does not depend on
 * {@code config/BatchConfig}. It relies on Spring Boot's Batch auto-configuration, which supplies
 * the persistent, restartable {@link JobRepository} and the {@link PlatformTransactionManager}
 * injected through the constructor &mdash; the same convention used by the sibling batch
 * configurations. The migration rationale is recorded in {@code docs/decision-log.md} rather than in
 * code comments.</p>
 *
 * <p>This configuration is stateless and thread-safe: it holds only immutable collaborators, and the
 * {@code @StepScope} tasklet reads its inputs exclusively from the per-execution job parameters.</p>
 */
@Configuration
public class DateConversionJobConfig {

    /** Logger used to emit the structured validation result, mirroring {@code CSUTLDTC}'s {@code LS-RESULT}. */
    private static final Logger LOGGER = LoggerFactory.getLogger(DateConversionJobConfig.class);

    /** Bean/registry name of the Spring Batch {@link Job} translating {@code CSUTLDTC}. */
    static final String JOB_NAME = "dateConversionJob";

    /** Name of the single step within {@link #JOB_NAME}. */
    static final String STEP_NAME = "dateConversionStep";

    /**
     * Custom {@link ExitStatus} exit code emitted when the supplied date is invalid. Because
     * {@code CSUTLDTC} returns a feedback code instead of abending, this lets the job complete
     * (batch status {@code COMPLETED}) while still surfacing the invalid-date outcome via the exit
     * code, analogous to a non-zero COBOL {@code RETURN-CODE}.
     */
    static final String EXIT_CODE_INVALID_DATE = "DATE_INVALID";

    /**
     * Display-only rendering of the default {@code YYYYMMDD} mask applied by the single-argument
     * {@link DateConversionService#validateDate(String)} overload (the {@code CSUTLDWY}
     * working-storage default). Used solely for the log line; the authoritative default is applied
     * inside {@link DateConversionService}, never duplicated as logic here.
     */
    private static final String DEFAULT_MASK_DISPLAY = "YYYYMMDD";

    /** Auto-configured Spring Batch job repository used to build the step and the job. */
    private final JobRepository jobRepository;

    /** Auto-configured transaction manager bounding the tasklet's execution. */
    private final PlatformTransactionManager transactionManager;

    /** Behavioral home of the {@code CSUTLDTC} logic; this job delegates to it entirely. */
    private final DateConversionService dateConversionService;

    /**
     * Creates the configuration with the collaborators supplied by Spring Boot's Batch
     * auto-configuration and component scanning.
     *
     * @param jobRepository         the auto-configured Spring Batch {@link JobRepository}
     * @param transactionManager    the auto-configured {@link PlatformTransactionManager}
     * @param dateConversionService the {@code CSUTLDTC} replacement to delegate validation to
     */
    public DateConversionJobConfig(JobRepository jobRepository,
            PlatformTransactionManager transactionManager,
            DateConversionService dateConversionService) {
        this.jobRepository = jobRepository;
        this.transactionManager = transactionManager;
        this.dateConversionService = dateConversionService;
    }

    /**
     * The single {@code @StepScope} tasklet that reproduces one {@code CALL 'CSUTLDTC'} invocation.
     *
     * <p>It reads the {@code date} job parameter (the COBOL {@code LS-DATE}, up to 10 characters) and
     * the optional {@code format} job parameter (the {@code LS-DATE-FORMAT} picture mask, up to 10
     * characters), delegates to {@link DateConversionService}, logs the resulting
     * {@link DateValidationResult}, and maps the outcome to the step {@link ExitStatus}. When
     * {@code format} is absent or blank the single-argument
     * {@link DateConversionService#validateDate(String)} overload is used so the {@code YYYYMMDD}
     * default mask ({@code CSUTLDWY}) applies; otherwise the two-argument
     * {@link DateConversionService#validateDate(String, String)} overload is used.</p>
     *
     * <p>A missing {@code date} parameter resolves to {@code null}; {@link DateConversionService}
     * treats that as a blank date and returns an invalid result, so the tasklet never throws for
     * missing or malformed input &mdash; matching {@code CSUTLDTC}, which returns a feedback code
     * rather than abending. The tasklet always returns {@link RepeatStatus#FINISHED}; the
     * valid/invalid decision is expressed purely through the exit status (see the class-level
     * exit-status mapping).</p>
     *
     * <p>Because the bean is {@code @StepScope}, the {@code @Value} job-parameter expressions are
     * resolved lazily per step execution; {@link #dateConversionStep()} passes {@code null}
     * placeholders that the scoped proxy replaces with the real parameter values at run time.</p>
     *
     * @param date   the date to validate, bound from job parameter {@code date} (COBOL {@code LS-DATE})
     * @param format the picture mask, bound from optional job parameter {@code format} (COBOL
     *               {@code LS-DATE-FORMAT}); {@code null}/blank selects the default {@code YYYYMMDD} mask
     * @return a {@link Tasklet} that validates the date, logs the result, sets the exit status and
     *         returns {@link RepeatStatus#FINISHED}
     */
    @Bean
    @StepScope
    public Tasklet dateConversionTasklet(
            @Value("#{jobParameters['date']}") String date,
            @Value("#{jobParameters['format']}") String format) {
        return (contribution, chunkContext) -> {
            boolean explicitMask = format != null && !format.isBlank();
            DateValidationResult result = explicitMask
                    ? dateConversionService.validateDate(date, format)
                    : dateConversionService.validateDate(date);
            String maskUsed = explicitMask ? format : DEFAULT_MASK_DISPLAY;
            logResult(date, maskUsed, result);
            if (!result.isValid()) {
                // CSUTLDTC returns a non-zero RETURN-CODE for a bad date without abending: complete
                // the step but surface the outcome through a custom, non-default exit status.
                contribution.setExitStatus(new ExitStatus(EXIT_CODE_INVALID_DATE, describeInvalid(result)));
            }
            return RepeatStatus.FINISHED;
        };
    }

    /**
     * The single step of {@link #dateConversionJob()}, wrapping the {@code @StepScope}
     * {@link #dateConversionTasklet(String, String)} within the auto-configured transaction
     * boundary. The {@code null} arguments are placeholders: the scoped proxy resolves the actual
     * {@code date}/{@code format} job parameters at run time.
     *
     * @return the {@code dateConversionStep} {@link Step}
     */
    @Bean
    public Step dateConversionStep() {
        return new StepBuilder(STEP_NAME, jobRepository)
                .tasklet(dateConversionTasklet(null, null), transactionManager)
                .build();
    }

    /**
     * The Spring Batch {@link Job} corresponding to the mainframe date-validation utility
     * {@code CSUTLDTC}. It consists of the single {@link #dateConversionStep()} and completes with
     * batch status {@code COMPLETED} for both valid and invalid dates; the validation outcome is
     * conveyed by the job/step exit status (see the class-level exit-status mapping).
     *
     * @return the {@code dateConversionJob} {@link Job}
     */
    @Bean
    public Job dateConversionJob() {
        return new JobBuilder(JOB_NAME, jobRepository)
                .start(dateConversionStep())
                .build();
    }

    /**
     * Logs the {@link DateValidationResult}, reproducing {@code CSUTLDTC} returning its
     * {@code LS-RESULT} record. A valid date is logged at INFO (including the epoch-day equivalent of
     * the CEE Lillian day); an invalid date is logged at WARN. The full 80-byte {@code LS-RESULT}
     * rendering is logged at DEBUG for byte-level diagnostics.
     *
     * @param date   the input date (COBOL {@code LS-DATE})
     * @param mask   the mask actually used (the supplied mask or the {@code YYYYMMDD} default)
     * @param result the structured validation result to log
     */
    private static void logResult(String date, String mask, DateValidationResult result) {
        String epochDay = result.epochDay().isPresent()
                ? Long.toString(result.epochDay().getAsLong())
                : "n/a";
        if (result.isValid()) {
            LOGGER.info("CSUTLDTC date validation succeeded: date=[{}] mask=[{}] "
                            + "(severity={}, msgNo={}, outcome={}, message=[{}], epochDay={})",
                    date, mask, result.severity(), result.msgNo(), result.outcome(),
                    result.message(), epochDay);
        } else {
            LOGGER.warn("CSUTLDTC date validation failed: date=[{}] mask=[{}] "
                            + "(severity={}, msgNo={}, outcome={}, message=[{}])",
                    date, mask, result.severity(), result.msgNo(), result.outcome(),
                    result.message());
        }
        LOGGER.debug("CSUTLDTC LS-RESULT (80-byte record)=[{}]", result.formattedResult());
    }

    /**
     * Builds the {@link ExitStatus} description for an invalid date, carrying the CEE-style severity,
     * the selected feedback outcome and the 15-character message so operators can diagnose the
     * failure from the job exit status alone.
     *
     * @param result the invalid validation result
     * @return a compact, human-readable description of the invalid-date outcome
     */
    private static String describeInvalid(DateValidationResult result) {
        return "severity=" + result.severity()
                + " outcome=" + result.outcome()
                + " message=" + result.message();
    }
}
