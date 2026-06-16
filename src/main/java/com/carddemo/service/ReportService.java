package com.carddemo.service;

import java.time.LocalDate;
import java.time.YearMonth;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.JobExecution;
import org.springframework.batch.core.JobExecutionException;
import org.springframework.batch.core.JobParameters;
import org.springframework.batch.core.JobParametersBuilder;
import org.springframework.batch.core.launch.JobLauncher;
import org.springframework.stereotype.Service;

import com.carddemo.dto.ReportRequest;
import com.carddemo.dto.ReportResponse;
import com.carddemo.exception.ValidationException;

/**
 * Service that ports the legacy CICS online report-request program
 * {@code app/cbl/CORPT00C.cbl} ("Print Transaction reports by submitting batch
 * job from online") to the Spring Boot stack.
 *
 * <h2>COBOL lineage</h2>
 * <p>On the mainframe, {@code CORPT00C} presented the operator with three
 * mutually-exclusive report options &mdash; <strong>Monthly</strong>,
 * <strong>Yearly</strong>, and <strong>Custom</strong> (a keyed start/end date
 * range) &mdash; on its BMS map {@code CORPT00}. After resolving the effective
 * date range and (for the custom option) validating the keyed dates through the
 * shared date utility {@code CSUTLDTC}, the program assembled a JCL job stream
 * and handed it to the CICS internal reader for asynchronous execution via the
 * {@code SUBMIT-JOB-TO-INTRDR} paragraph (an {@code EXEC CICS WRITEQ TD} to the
 * extra-partition transient-data queue {@code 'JOBS'}, CORPT00C&nbsp;L462-535).
 * The online transaction then returned immediately with the confirmation
 * "{@code <report> report submitted for printing ...}"; it never rendered the
 * report itself and never blocked on its completion.</p>
 *
 * <h2>Java analog (AAP &sect;0.3.2 &mdash; asynchronous job submission)</h2>
 * <p>This service preserves that fire-and-forget semantics exactly: instead of
 * writing JCL to the internal reader, it launches a <strong>Spring Batch</strong>
 * job through the auto-configured {@link JobLauncher} and surfaces the resulting
 * {@link JobExecution} identifier as the report reference inside a
 * {@link ReportResponse}. The legacy {@code WS-START-DATE}/{@code WS-END-DATE}
 * character dates become the {@code startDate}/{@code endDate} job parameters,
 * and the legacy report-type screen flags become the {@code reportType}
 * discriminator.</p>
 *
 * <h2>Effective date range &mdash; faithful to CORPT00C (parity, AAP &sect;0.7.1/&sect;0.7.3)</h2>
 * <ul>
 *   <li><strong>MONTHLY</strong> &rarr; the <em>whole current calendar month</em>:
 *       start = the first day of the current month, end = the <em>last day</em> of
 *       the current month. This mirrors {@code CORPT00C} L217-234, which sets the
 *       start day to {@code 01} and then computes the end as (first-of-next-month
 *       &minus; 1&nbsp;day). It is intentionally <em>not</em> "first-of-month
 *       through today": both committed DTO contracts
 *       ({@code dto/ReportRequest}, {@code dto/ReportResponse}) document the
 *       MONTHLY window as ending on the last day of the month, and per AAP
 *       &sect;0.7.3 the actual COBOL source governs where descriptions diverge.</li>
 *   <li><strong>YEARLY</strong> &rarr; the <em>whole current calendar year</em>:
 *       start = January&nbsp;1, end = December&nbsp;31 (mirrors {@code CORPT00C}
 *       L243-251).</li>
 *   <li><strong>CUSTOM</strong> &rarr; the caller-supplied {@code startDate} /
 *       {@code endDate}, validated via {@link DateValidationService} (the
 *       {@code CSUTLDTC} replacement) exactly as {@code CORPT00C} L388-426 did.</li>
 * </ul>
 *
 * <h2>Asynchronous-submission contract</h2>
 * <p>{@code submitReport} returns a {@link ReportResponse} <em>synchronously</em>,
 * carrying the {@code JobExecution} id and the status captured at submission. Two
 * runtime modes are possible and the contract is identical in both:</p>
 * <ul>
 *   <li>With Spring Boot's <strong>auto-configured</strong> {@code JobLauncher}
 *       (a {@code SyncTaskExecutor}), {@link JobLauncher#run} runs the job inline
 *       and returns a {@code COMPLETED} execution.</li>
 *   <li>When the {@code JobLauncher} is backed by the asynchronous
 *       {@code taskExecutor} bean (see {@code config/AsyncConfig}),
 *       {@link JobLauncher#run} returns immediately with a {@code STARTING}/
 *       {@code STARTED} execution &mdash; the true fire-and-forget analog of
 *       {@code CORPT00C}.</li>
 * </ul>
 * <p>Either way a valid {@link JobExecution#getId()} and
 * {@link JobExecution#getStatus()} are available at return time, so this
 * method deliberately returns {@link ReportResponse} directly and is
 * <strong>NOT</strong> annotated {@code @Async}: {@code @Async} on a method that
 * returns a plain value (rather than {@code void} or a {@code Future}) is a Spring
 * anti-pattern that would hand the caller a {@code null} proxy result. Choosing
 * the synchronous-vs-asynchronous {@code JobLauncher} belongs to
 * {@code config}/{@code batch}, not to this service.</p>
 *
 * <h2>Strict layering &amp; no batch leakage (AAP &sect;0.3.2, &sect;0.6/&sect;0.7)</h2>
 * <ul>
 *   <li>This service orchestrates the launcher and date validation <em>only</em>:
 *       no controller/web types, no repository or entity access, constructor
 *       injection throughout.</li>
 *   <li>It depends solely on Spring Batch <em>core</em> types
 *       ({@link Job}, {@link JobExecution}, {@link JobParameters},
 *       {@link JobParametersBuilder}, {@link JobLauncher}) and never imports any
 *       {@code com.carddemo.batch.*} type. The concrete report job lives in the
 *       {@code batch/} package and is resolved <em>by bean name</em> through an
 *       injected {@code Map<String, Job>} (Spring populates it with every
 *       {@link Job} bean keyed by bean name, or an empty map when none exist
 *       yet), which avoids a {@code service &rarr; batch} compile-time cycle and
 *       lets the application boot before {@code batch/} is wired.</li>
 *   <li>The returned {@link ReportResponse} carries only a plain {@link Long} id
 *       and a {@link String} status, so no Spring Batch type ever crosses the API
 *       boundary.</li>
 * </ul>
 *
 * <p>The bean is a stateless singleton with only {@code final} collaborators, so
 * it is inherently thread-safe.</p>
 *
 * @see ReportRequest
 * @see ReportResponse
 * @see DateValidationService
 * @see <a href="file:app/cbl/CORPT00C.cbl">CORPT00C.cbl</a>
 */
@Service
public class ReportService {

    /** Logger for submission diagnostics. Never records PII (only report type, dates, and job id/status). */
    private static final Logger log = LoggerFactory.getLogger(ReportService.class);

    /**
     * Bean name of the Spring Batch report job to launch. The {@code batch/}
     * package registers the transaction-report job under exactly this name; this
     * service depends on it by name only (never by type), keeping the
     * {@code service &rarr; batch} relationship decoupled at compile time.
     */
    private static final String JOB_NAME = "transactionReportJob";

    /** Recognized report-type token: whole current calendar month. */
    private static final String REPORT_TYPE_MONTHLY = "MONTHLY";

    /** Recognized report-type token: whole current calendar year. */
    private static final String REPORT_TYPE_YEARLY = "YEARLY";

    /** Recognized report-type token: caller-supplied start/end date range. */
    private static final String REPORT_TYPE_CUSTOM = "CUSTOM";

    /** JobParameters key carrying the report-type discriminator passed to the batch job. */
    private static final String PARAM_REPORT_TYPE = "reportType";

    /** JobParameters key carrying the effective inclusive start date (ISO-8601 yyyy-MM-dd). */
    private static final String PARAM_START_DATE = "startDate";

    /** JobParameters key carrying the effective inclusive end date (ISO-8601 yyyy-MM-dd). */
    private static final String PARAM_END_DATE = "endDate";

    /**
     * JobParameters key carrying a unique run identifier. A fresh value on every
     * submission makes each launch a distinct {@code JobInstance}, so repeated
     * report requests for the same range are always re-runnable (never blocked as
     * "instance already complete").
     */
    private static final String PARAM_RUN_ID = "run.id";

    /** Spring Batch launcher (auto-configured by Spring Boot). */
    private final JobLauncher jobLauncher;

    /**
     * All {@link Job} beans keyed by bean name. Injected by Spring; empty until
     * the {@code batch/} package contributes job beans. Never {@code null} after
     * construction (normalized in the constructor).
     */
    private final Map<String, Job> jobs;

    /** Date-validation collaborator (the {@code CSUTLDTC} replacement) used for CUSTOM ranges. */
    private final DateValidationService dateValidationService;

    /**
     * Creates the report service with its collaborators.
     *
     * <p>Constructor injection is used throughout (no field injection) to keep the
     * service immutable and trivially unit-testable. The {@code jobs} map is
     * normalized to an empty map when {@code null}: Spring injects a non-{@code null}
     * empty {@code Map} when no {@link Job} beans exist yet, and this guard
     * additionally makes direct construction with a {@code null} map (e.g. in a
     * unit test) safe rather than NPE-prone.</p>
     *
     * @param jobLauncher           the Spring Batch {@link JobLauncher}; must not be {@code null}
     * @param jobs                  every {@link Job} bean keyed by bean name; may be empty (or
     *                              {@code null} when constructed directly), normalized to an empty map
     * @param dateValidationService the {@code CSUTLDTC}-equivalent date validator; must not be {@code null}
     */
    public ReportService(JobLauncher jobLauncher,
                         Map<String, Job> jobs,
                         DateValidationService dateValidationService) {
        this.jobLauncher = jobLauncher;
        this.jobs = (jobs != null) ? jobs : Collections.emptyMap();
        this.dateValidationService = dateValidationService;
    }

    /**
     * Resolves the effective reporting range from the request, validates it,
     * launches the transaction-report batch job, and returns a reference to the
     * submitted job &mdash; the Java analog of {@code CORPT00C}'s
     * "resolve range &rarr; validate &rarr; submit to internal reader &rarr;
     * confirm" flow.
     *
     * <p>The effective {@code [startDate, endDate]} window is derived from
     * {@link ReportRequest#reportType()}:</p>
     * <ul>
     *   <li>{@code "MONTHLY"} &mdash; first day through last day of the current month;</li>
     *   <li>{@code "YEARLY"} &mdash; January&nbsp;1 through December&nbsp;31 of the current year;</li>
     *   <li>{@code "CUSTOM"} &mdash; the supplied {@code startDate}/{@code endDate}, which must both be
     *       present, must each be a valid {@code yyyy-MM-dd} date (validated via
     *       {@link DateValidationService}, the {@code CSUTLDTC} replacement), and must not be reversed.</li>
     * </ul>
     *
     * <p>The resolved range and report type are passed to the batch job as
     * {@link JobParameters}, along with a unique {@code run.id} so each submission
     * is an independent, re-runnable {@code JobInstance}. The captured
     * {@link JobExecution} id and status are echoed back in the
     * {@link ReportResponse}; the report content itself is produced out of band by
     * the batch job (fire-and-forget, exactly as the legacy program behaved).</p>
     *
     * @param request the report request; its {@code reportType} selects the range mode and, for
     *                {@code CUSTOM}, supplies the {@code startDate}/{@code endDate}
     * @return a {@link ReportResponse} carrying the {@link JobExecution} id, the submission-time
     *         status, the echoed report type, and the effective start/end dates
     * @throws ValidationException   if the request or report type is missing, the report type is not
     *                               one of {@code MONTHLY}/{@code YEARLY}/{@code CUSTOM}, or a custom
     *                               range is missing/invalid/reversed (surfaced as HTTP&nbsp;400)
     * @throws IllegalStateException if the {@code transactionReportJob} bean is not available, or the
     *                               launcher fails to start the job (surfaced as HTTP&nbsp;500) &mdash;
     *                               the analog of {@code CORPT00C}'s "Unable to Write TDQ (JOBS)..."
     *                               system-error path
     */
    public ReportResponse submitReport(ReportRequest request) {
        if (request == null) {
            throw new ValidationException("Report request is required");
        }

        String reportType = request.reportType();
        if (reportType == null) {
            throw new ValidationException("Report type is required");
        }

        // Read the clock once so MONTHLY and YEARLY ranges are internally consistent even if the
        // call happens to straddle midnight or a month/year boundary.
        LocalDate today = LocalDate.now();

        LocalDate startDate;
        LocalDate endDate;

        switch (reportType) {
            case REPORT_TYPE_MONTHLY:
                // CORPT00C L217-234: day 01 of the current month through the last day of the current
                // month (computed there as first-of-next-month minus one day).
                startDate = today.withDayOfMonth(1);
                endDate = YearMonth.from(today).atEndOfMonth();
                break;
            case REPORT_TYPE_YEARLY:
                // CORPT00C L243-251: Jan 01 through Dec 31 of the current year.
                int year = today.getYear();
                startDate = LocalDate.of(year, 1, 1);
                endDate = LocalDate.of(year, 12, 31);
                break;
            case REPORT_TYPE_CUSTOM:
                // CORPT00C L256-436: operator-supplied range, validated via CSUTLDTC.
                startDate = request.startDate();
                endDate = request.endDate();
                validateCustomRange(startDate, endDate);
                break;
            default:
                // Defensive: the DTO @Pattern normally rejects unknown values at the controller
                // boundary, but the service must not trust that when invoked directly.
                throw new ValidationException(
                        "Report type must be " + REPORT_TYPE_MONTHLY + ", " + REPORT_TYPE_YEARLY
                                + ", or " + REPORT_TYPE_CUSTOM);
        }

        log.info("Submitting report job '{}': reportType={}, range=[{} .. {}]",
                JOB_NAME, reportType, startDate, endDate);

        Job job = resolveReportJob();
        JobParameters params = buildJobParameters(reportType, startDate, endDate);
        JobExecution execution = launchReportJob(job, params);

        log.info("Report job '{}' submitted: jobExecutionId={}, status={}",
                JOB_NAME, execution.getId(), execution.getStatus());

        return new ReportResponse(
                execution.getId(),
                execution.getStatus().name(),
                reportType,
                startDate,
                endDate);
    }

    /**
     * Validates a {@code CUSTOM} reporting range, reproducing the field-by-field
     * checks {@code CORPT00C} performed before submission (L256-436).
     *
     * <p>Three rules are enforced, in order:</p>
     * <ol>
     *   <li><strong>Presence</strong> &mdash; both dates must be supplied; a missing date yields a
     *       {@link ValidationException} whose per-field map names exactly which boundary
     *       ({@code startDate}/{@code endDate}) was absent, mirroring the legacy
     *       "Start/End Date - ... can NOT be empty..." prompts.</li>
     *   <li><strong>Format / calendar validity</strong> &mdash; each date is re-validated through
     *       {@link DateValidationService#validateAndParseDate(String, String)} on its ISO-8601
     *       {@code yyyy-MM-dd} text. The value is already a typed {@link LocalDate}, but routing it
     *       through the {@code CSUTLDTC} replacement preserves the legacy validation step (and its
     *       leap-year / month-range semantics) for strict parity.</li>
     *   <li><strong>Ordering</strong> &mdash; the start must not be after the end. This is a defensive
     *       guard added by the migration (the original program validated each date independently and
     *       did not compare them); a reversed range is a clear client error, surfaced as HTTP&nbsp;400
     *       rather than silently submitting a job that could never produce meaningful output.</li>
     * </ol>
     *
     * @param startDate the requested inclusive start date, possibly {@code null}
     * @param endDate   the requested inclusive end date, possibly {@code null}
     * @throws ValidationException if either date is missing, not a valid {@code yyyy-MM-dd} date, or
     *                             the start date is after the end date
     */
    private void validateCustomRange(LocalDate startDate, LocalDate endDate) {
        Map<String, String> fieldErrors = new LinkedHashMap<>();
        if (startDate == null) {
            fieldErrors.put("startDate", "Start date is required for a custom report");
        }
        if (endDate == null) {
            fieldErrors.put("endDate", "End date is required for a custom report");
        }
        if (!fieldErrors.isEmpty()) {
            throw new ValidationException(
                    "Start date and end date are required for a custom report", fieldErrors);
        }

        // CSUTLDTC parity (CORPT00C L388-426): validate each date through the date utility. STRICT
        // yyyy-MM-dd parsing rejects impossible dates; a failure is raised as a ValidationException.
        dateValidationService.validateAndParseDate(startDate.toString(), "Start date");
        dateValidationService.validateAndParseDate(endDate.toString(), "End date");

        // Added migration guard: reject a reversed range (no COBOL equivalent).
        if (startDate.isAfter(endDate)) {
            throw new ValidationException("Start date must not be after end date");
        }
    }

    /**
     * Resolves the report {@link Job} from the injected by-name map.
     *
     * <p>The lookup is empty-map-safe: when the {@code batch/} package has not yet
     * contributed the {@code transactionReportJob} bean, {@link Map#get(Object)}
     * returns {@code null} and this method raises a clear
     * {@link IllegalStateException} (surfaced as HTTP&nbsp;500 by the global
     * exception handler) instead of letting a {@code NullPointerException} escape.
     * A missing job bean is a server-side configuration gap, not a client error.</p>
     *
     * @return the resolved, non-{@code null} report {@link Job}
     * @throws IllegalStateException if no job is registered under {@link #JOB_NAME}
     */
    private Job resolveReportJob() {
        Job job = jobs.get(JOB_NAME);
        if (job == null) {
            throw new IllegalStateException("Report job '" + JOB_NAME + "' is not available");
        }
        return job;
    }

    /**
     * Builds the {@link JobParameters} for a report submission.
     *
     * <p>Dates are passed as ISO-8601 {@code yyyy-MM-dd} strings (via
     * {@link LocalDate#toString()}) so the batch job can re-parse them
     * deterministically. A unique {@code run.id} (current epoch millis) guarantees
     * each submission is a distinct, re-runnable {@code JobInstance}.</p>
     *
     * @param reportType the report-type discriminator to forward to the job
     * @param startDate  the effective inclusive start date
     * @param endDate    the effective inclusive end date
     * @return the assembled {@link JobParameters}
     */
    private JobParameters buildJobParameters(String reportType, LocalDate startDate, LocalDate endDate) {
        return new JobParametersBuilder()
                .addString(PARAM_REPORT_TYPE, reportType)
                .addString(PARAM_START_DATE, startDate.toString())
                .addString(PARAM_END_DATE, endDate.toString())
                .addLong(PARAM_RUN_ID, System.currentTimeMillis())
                .toJobParameters();
    }

    /**
     * Launches the report job, translating the checked Spring Batch launch
     * failures into an unchecked {@link IllegalStateException}.
     *
     * <p>{@link JobLauncher#run(Job, JobParameters)} declares four checked
     * exceptions (already-running, restart, instance-already-complete, and
     * invalid-parameters), all of which extend
     * {@link JobExecutionException}. Because this service always supplies a unique
     * {@code run.id}, those conditions are not expected in normal operation;
     * should one occur it represents a server-side failure analogous to
     * {@code CORPT00C}'s "Unable to Write TDQ (JOBS)..." path, so it is surfaced as
     * HTTP&nbsp;500. The original cause is chained for server-side diagnostics; the
     * client-facing message stays free of internal detail.</p>
     *
     * @param job    the resolved report {@link Job}
     * @param params the assembled {@link JobParameters}
     * @return the {@link JobExecution} produced by the launcher
     * @throws IllegalStateException if the launcher fails to start the job
     */
    private JobExecution launchReportJob(Job job, JobParameters params) {
        try {
            return jobLauncher.run(job, params);
        } catch (JobExecutionException ex) {
            throw new IllegalStateException("Failed to submit report job '" + JOB_NAME + "'", ex);
        }
    }
}
