package com.carddemo.service;

import com.carddemo.dto.report.OutputFormat;
import com.carddemo.dto.report.ReportRequest;
import com.carddemo.dto.report.ReportType;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.concurrent.CompletableFuture;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.JobExecution;
import org.springframework.batch.core.JobParameters;
import org.springframework.batch.core.JobParametersBuilder;
import org.springframework.batch.core.JobParametersInvalidException;
import org.springframework.batch.core.configuration.JobRegistry;
import org.springframework.batch.core.launch.JobLauncher;
import org.springframework.batch.core.launch.NoSuchJobException;
import org.springframework.batch.core.repository.JobExecutionAlreadyRunningException;
import org.springframework.batch.core.repository.JobInstanceAlreadyCompleteException;
import org.springframework.batch.core.repository.JobRestartException;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

/**
 * Report submission service replacing {@code app/cbl/CORPT00C.cbl} (CICS TRANID {@code 'CR00'}).
 *
 * <p>The COBOL program built an inline JCL stream (job {@code TRNRPT00},
 * {@code //STEP10 EXEC PROC=TRANREPT}) and submitted it for batch execution by writing each
 * JCL line to the internal-reader transient-data queue via
 * {@code EXEC CICS WRITEQ TD QUEUE('JOBS')} ({@code CORPT00C.cbl:L515-L520}). In the modernized
 * stack that internal-reader hand-off is replaced by an in-process Spring Batch launch:
 * {@link org.springframework.batch.core.launch.JobLauncher#run(Job, JobParameters)} on a
 * {@link Job} resolved by name from the {@link JobRegistry} (PR-25 — a single in-process Spring
 * Boot monolith with no external job scheduler).</p>
 *
 * <h2>COBOL parity ({@code PROCESS-ENTER-KEY} / {@code SUBMIT-JOB-TO-INTRDR})</h2>
 * <ul>
 *   <li><b>Report-type dispatch.</b> {@code CORPT00C}'s {@code EVALUATE TRUE}
 *       ({@code CORPT00C.cbl:L212-L443}) branches on {@code MONTHLYI}/{@code YEARLYI}/{@code CUSTOMI}.
 *       Crucially, all three branches derive a start/end date range and then call the <em>same</em>
 *       {@code SUBMIT-JOB-TO-INTRDR} paragraph submitting the <em>same</em> {@code TRANREPT} job —
 *       the report period only changes the {@code PARM-START-DATE}/{@code PARM-END-DATE} values, not
 *       the job identity. This service therefore resolves every {@link ReportType} to the single
 *       registered job {@value #TRANSACTION_REPORT_JOB_NAME} (the
 *       {@code com.carddemo.batch.TransactionReportJobConfig} {@code JOB_NAME}) and conveys the
 *       period through job parameters. The {@link ReportType} is still examined in
 *       {@link #resolveJobName(ReportType)} so the mapping is an explicit, exhaustive extension
 *       point should period-specific jobs ever be registered.</li>
 *   <li><b>Confirmation gate.</b> {@code SUBMIT-JOB-TO-INTRDR} ({@code CORPT00C.cbl:L464-L494})
 *       only proceeds with submission when {@code CONFIRMI = 'Y' OR 'y'} (case-insensitive); any
 *       other value cancels. This is preserved by {@link #submitReport(ReportRequest)} which rejects
 *       a non-affirmative {@link ReportRequest#getConfirmation()} with {@link IllegalArgumentException}.
 *       The wire-format of the field is already constrained to {@code [YyNn]} by
 *       {@code ReportRequest}'s {@code @Pattern} (HTTP 400 on violation).</li>
 *   <li><b>Date range.</b> The derived/supplied start and end dates become Spring Batch
 *       {@code startDate}/{@code endDate} job parameters. They are added as
 *       {@link LocalDateTime} values — {@code startDate} at start-of-day and {@code endDate} at
 *       end-of-day ({@link LocalTime#MAX}) — because the consuming
 *       {@code transactionReportJob} tasklet reads them with
 *       {@code JobParameters.getLocalDateTime(...)} and queries
 *       {@code findByOrigTimestampBetween(start, end)} (inclusive of both bounds). Supplying the
 *       full day window reproduces the COBOL behavior of reporting every transaction whose date
 *       falls within {@code [startDate .. endDate]}.</li>
 * </ul>
 *
 * <h2>Async submission (HTTP 202 Accepted)</h2>
 * <p>{@link #submitReport(ReportRequest)} is annotated {@link Async} (project-wide
 * {@code @EnableAsync} is declared on {@code com.carddemo.config.WebConfig} and
 * {@code com.carddemo.batch.BatchConfig}) and returns a {@link CompletableFuture} of the launched
 * {@code jobExecutionId}, so {@code ReportController} can respond with {@code 202 Accepted} without
 * blocking the request thread — the REST analogue of the fire-and-forget CICS internal-reader
 * submission. Because Spring's async proxy executes the method body on a task-executor thread, any
 * validation or launch failure (e.g. {@link IllegalArgumentException}/{@link IllegalStateException})
 * completes the returned future <em>exceptionally</em> rather than being thrown to the caller
 * synchronously; the controller (or {@code GlobalExceptionHandler}) unwraps it. When the method is
 * invoked directly (bypassing the proxy, e.g. in unit tests) those exceptions propagate
 * synchronously.</p>
 *
 * <h2>Dependencies (PR-29 — constructor injection)</h2>
 * <ul>
 *   <li>{@link JobLauncher} — the Spring Boot 3.2 auto-configured launcher (resolved by type, like
 *       {@code BatchAdminController}); used to start the resolved {@link Job}.</li>
 *   <li>{@link JobRegistry} — the auto-configured, name-based job locator into which every
 *       {@code @Bean Job} (including {@code transactionReportJob}) is registered.</li>
 * </ul>
 *
 * @see com.carddemo.controller.ReportController
 * @see com.carddemo.batch.TransactionReportJobConfig
 * @see ReportRequest
 * @see ReportType
 * @see OutputFormat
 * @since 1.0
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ReportService {

    /**
     * Name of the single Spring Batch report {@link Job} registered with the {@link JobRegistry}.
     *
     * <p>Mirrors {@code com.carddemo.batch.TransactionReportJobConfig.JOB_NAME}. The constant is
     * intentionally referenced by literal value (rather than importing the batch configuration
     * class) to keep this service decoupled from concrete job beans — job resolution happens by
     * name through the {@link JobRegistry}, exactly as the COBOL program submitted a named JCL
     * {@code PROC} to the internal reader.</p>
     */
    private static final String TRANSACTION_REPORT_JOB_NAME = "transactionReportJob";

    /** Job parameter key for the report type (metadata + job-instance identity). */
    private static final String PARAM_REPORT_TYPE = "reportType";

    /** Job parameter key for the desired output format (metadata + job-instance identity). */
    private static final String PARAM_OUTPUT_FORMAT = "outputFormat";

    /** Job parameter key for the inclusive report-period start (read as {@link LocalDateTime}). */
    private static final String PARAM_START_DATE = "startDate";

    /** Job parameter key for the inclusive report-period end (read as {@link LocalDateTime}). */
    private static final String PARAM_END_DATE = "endDate";

    /**
     * Job parameter key for the submission timestamp. Adding the current epoch millisecond as an
     * identifying parameter guarantees a unique {@code JobInstance} per submission so every request
     * launches a fresh {@code JobExecution} — reproducing the COBOL behavior where each
     * {@code WRITEQ TD QUEUE('JOBS')} enqueue triggered a new job invocation.
     */
    private static final String PARAM_SUBMISSION_TIME = "submissionTime";

    /** Default output format applied when the request omits one (per {@link OutputFormat} contract). */
    private static final OutputFormat DEFAULT_OUTPUT_FORMAT = OutputFormat.PDF;

    /**
     * The Spring Boot 3.2 auto-configured {@link JobLauncher} used to start the report job.
     * Injected by type via the Lombok-generated constructor (PR-29).
     */
    private final JobLauncher jobLauncher;

    /**
     * The auto-configured {@link JobRegistry} mapping job names to {@link Job} beans. Injected by
     * type via the Lombok-generated constructor (PR-29).
     */
    private final JobRegistry jobRegistry;

    /**
     * Submits a transaction report for asynchronous batch generation, replacing the COBOL
     * {@code CORPT00C} {@code SUBMIT-JOB-TO-INTRDR} / {@code WRITEQ TD QUEUE('JOBS')} flow.
     *
     * <p>Processing steps (mirroring {@code CORPT00C.cbl:L208-L520}):</p>
     * <ol>
     *   <li>Validate the affirmative confirmation ({@code 'Y'}/{@code 'y'}); reject otherwise.</li>
     *   <li>Resolve the registered report {@link Job} name from the {@link ReportType}.</li>
     *   <li>Look up the {@link Job} in the {@link JobRegistry}.</li>
     *   <li>Build the {@link JobParameters} (report type, output format, inclusive date window, and a
     *       unique submission timestamp).</li>
     *   <li>Launch the job and return its {@code jobExecutionId} wrapped in a {@link CompletableFuture}.</li>
     * </ol>
     *
     * @param req the report submission request; must carry a {@link ReportType}, an affirmative
     *            {@link ReportRequest#getConfirmation() confirmation}, and (for the date window) a
     *            {@link ReportRequest#getStartDate() startDate} and
     *            {@link ReportRequest#getEndDate() endDate}
     * @return a {@link CompletableFuture} completed with the launched {@code jobExecutionId}
     * @throws IllegalArgumentException if {@code req} is {@code null}, the confirmation is not
     *                                  {@code 'Y'}/{@code 'y'}, or the report type is absent
     *                                  (completes the future exceptionally when invoked via the
     *                                  async proxy)
     * @throws IllegalStateException    if the report job is not registered, or the launch fails
     *                                  (completes the future exceptionally when invoked via the
     *                                  async proxy)
     */
    @Async
    public CompletableFuture<Long> submitReport(ReportRequest req) {
        if (req == null) {
            throw new IllegalArgumentException("ReportRequest must not be null");
        }

        // 1. Confirmation gate — CORPT00C.cbl:L476-L494 ("WHEN CONFIRMI = 'Y' OR 'y'": proceed).
        final String confirmation = req.getConfirmation();
        if (confirmation == null || !"Y".equalsIgnoreCase(confirmation)) {
            throw new IllegalArgumentException(
                    "Report submission requires confirmation 'Y' or 'y'; received: '" + confirmation + "'");
        }

        // 2. Report type is required to dispatch (CORPT00C.cbl:L437-L442 "Select a report type...").
        final ReportType reportType = req.getReportType();
        if (reportType == null) {
            throw new IllegalArgumentException("reportType is required to submit a report");
        }

        // 3. Resolve the registered batch job name for this report period.
        final String jobName = resolveJobName(reportType);

        // 4. Look up the Job bean by name (absence is a configuration error -> IllegalStateException).
        final Job job;
        try {
            job = jobRegistry.getJob(jobName);
        } catch (NoSuchJobException e) {
            throw new IllegalStateException("Report job not registered in JobRegistry: " + jobName, e);
        }

        // 5. Build the JobParameters carrying the report period and metadata.
        final OutputFormat outputFormat =
                req.getOutputFormat() != null ? req.getOutputFormat() : DEFAULT_OUTPUT_FORMAT;

        final JobParametersBuilder paramsBuilder = new JobParametersBuilder()
                .addString(PARAM_REPORT_TYPE, reportType.name())
                .addString(PARAM_OUTPUT_FORMAT, outputFormat.name())
                .addLong(PARAM_SUBMISSION_TIME, System.currentTimeMillis());

        // Date window: add as LocalDateTime because the consuming transactionReportJob tasklet reads
        // params.getLocalDateTime("startDate"/"endDate") and queries findByOrigTimestampBetween,
        // which is inclusive of both bounds. Start-of-day .. end-of-day reproduces the COBOL
        // "report every transaction in [startDate .. endDate]" behavior.
        final LocalDate startDate = req.getStartDate();
        if (startDate != null) {
            paramsBuilder.addLocalDateTime(PARAM_START_DATE, startDate.atStartOfDay());
        }
        final LocalDate endDate = req.getEndDate();
        if (endDate != null) {
            paramsBuilder.addLocalDateTime(PARAM_END_DATE, endDate.atTime(LocalTime.MAX));
        }

        final JobParameters parameters = paramsBuilder.toJobParameters();

        // 6. Launch — the REST replacement for EXEC CICS WRITEQ TD QUEUE('JOBS') (CORPT00C.cbl:L517).
        try {
            final JobExecution execution = jobLauncher.run(job, parameters);
            final Long executionId = execution.getId();
            log.info("Report job '{}' launched (reportType={}, outputFormat={}, startDate={}, "
                            + "endDate={}, jobExecutionId={})",
                    jobName, reportType, outputFormat, startDate, endDate, executionId);
            return CompletableFuture.completedFuture(executionId);
        } catch (JobExecutionAlreadyRunningException | JobRestartException
                 | JobInstanceAlreadyCompleteException | JobParametersInvalidException e) {
            throw new IllegalStateException(
                    "Failed to launch report job '" + jobName + "': " + e.getMessage(), e);
        }
    }

    /**
     * Resolves the registered Spring Batch job name for the supplied {@link ReportType}.
     *
     * <p>The COBOL {@code CORPT00C} {@code EVALUATE TRUE} ({@code CORPT00C.cbl:L212-L443}) selected a
     * reporting <em>period</em> — {@code MONTHLY}, {@code YEARLY}, or {@code CUSTOM} — which only
     * changed the computed {@code PARM-START-DATE}/{@code PARM-END-DATE}; all three periods submitted
     * the same {@code TRANREPT} job. The modernized batch layer registers a single unified job
     * ({@value #TRANSACTION_REPORT_JOB_NAME}); the period is conveyed via the {@code startDate}/
     * {@code endDate} job parameters built in {@link #submitReport(ReportRequest)}. The exhaustive
     * switch keeps every {@link ReportType} explicitly accounted for and provides a single, obvious
     * extension point should period-specific jobs be introduced later.</p>
     *
     * @param reportType the requested report period (must not be {@code null})
     * @return the name of the registered report {@link Job} to launch
     */
    private String resolveJobName(ReportType reportType) {
        return switch (reportType) {
            case MONTHLY, YEARLY, CUSTOM -> TRANSACTION_REPORT_JOB_NAME;
        };
    }
}
