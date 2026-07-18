/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License").
 * You may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.aws.carddemo.service;

import java.time.Clock;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Objects;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.JobExecution;
import org.springframework.batch.core.JobExecutionException;
import org.springframework.batch.core.JobParameters;
import org.springframework.batch.core.JobParametersBuilder;
import org.springframework.batch.core.launch.JobLauncher;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;

import com.aws.carddemo.repository.TransactionRepository;

/**
 * Transaction-report request service &mdash; the Java re-platform of the CardDemo COBOL
 * online program {@code CORPT00C} (Transaction Reports, CICS transaction {@code CR00};
 * source {@code legacy/cbl/CORPT00C.cbl}, formerly {@code app/cbl/CORPT00C.cbl}). It
 * reproduces, with no feature expansion, the request-validation and job-submission
 * behavior of that program per AAP &sect;0.5.3.
 *
 * <h2>Legacy behavior reproduced</h2>
 * On the mainframe {@code CORPT00C} validated a report request entered on the
 * {@code CORPT0A} screen and, when the request was valid and confirmed,
 * <strong>submitted a batch job</strong> by writing generated JCL to an extra-partition
 * transient-data queue ({@code WRITEQ TD QUEUE('JOBS')}) that fed the JES internal
 * reader (paragraph {@code SUBMIT-JOB-TO-INTRDR} / {@code WIRTE-JOBSUB-TDQ}). In the
 * Java target that JCL-submission is re-expressed, per the AAP construct-mapping rules
 * (JCL job &rarr; Spring Batch Job/Step), as launching the Spring Batch
 * {@code transactionReportJob} through an injected {@link JobLauncher}. The batch job
 * itself &mdash; {@code TransactionReportJob}, the re-platform of {@code CBTRN03C} &mdash;
 * lives in the {@code batch/} layer and is authored separately; this service only
 * <em>triggers</em> it and never depends on any {@code batch/} class beyond the injected
 * {@link Job} interface.
 *
 * <h2>Report types</h2>
 * The screen offered three mutually exclusive report types (COBOL {@code PROCESS-ENTER-KEY}
 * {@code EVALUATE TRUE}):
 * <ul>
 *   <li><strong>Monthly</strong> &mdash; the current calendar month (first day through the
 *       last day of the current month), matching the COBOL month-range computation that
 *       set the start to {@code 01} of the current month and derived the end as the last
 *       day of that month.</li>
 *   <li><strong>Yearly</strong> &mdash; the current calendar year, {@code YYYY-01-01}
 *       through {@code YYYY-12-31}.</li>
 *   <li><strong>Custom</strong> &mdash; an operator-supplied start and end date, each
 *       validated field-by-field (below).</li>
 * </ul>
 * When no type is selected the COBOL {@code WHEN OTHER} branch produced
 * {@code "Select a report type to print report..."}; that outcome is preserved here.
 *
 * <h2>Custom-date validation (exact strings and evaluation order)</h2>
 * For a custom request the COBOL validated the six date parts in a fixed order and, on the
 * first failure, sent the screen and returned to CICS (paragraph
 * {@code SEND-TRNRPT-SCREEN} ends with {@code GO TO RETURN-TO-CICS}). This service
 * reproduces that <strong>short-circuit-on-first-error</strong> behavior in the identical
 * order: all six "empty" checks first (Start Month, Start Day, Start Year, End Month, End
 * Day, End Year), then the six numeric/range checks in the same field order (month must be
 * numeric and {@code <= 12}; day numeric and {@code <= 31}; year numeric), then the two
 * full-calendar-date checks (Start then End) delegated to {@link DateValidationService}
 * (the {@code CALL 'CSUTLDTC'} analog). Every caller-visible message literal is preserved
 * verbatim (for example {@code "Start Date - Month can NOT be empty..."},
 * {@code "End Date - Not a valid Day..."}, {@code "Start Date - Not a valid date..."}).
 *
 * <h2>Confirmation and submission</h2>
 * Before submitting, the COBOL required a {@code Y}/{@code N} confirmation
 * ({@code SUBMIT-JOB-TO-INTRDR}): a blank confirmation produced
 * {@code "Please confirm to print the <name> report..."}; {@code Y} submitted the job;
 * {@code N} cleared the screen without submitting; any other value produced
 * {@code '"x" is not a valid value to confirm...'}. On a successful submit the program
 * displayed {@code "<name> report submitted for printing ..."}. All four outcomes are
 * reproduced through the returned {@link ReportResult}.
 *
 * <h2>Pseudo-conversational translation (no DTOs)</h2>
 * There is no 3270 terminal in the re-platformed application, so the COBOL
 * {@code SEND MAP}/{@code RETURN TRANSID} screen interaction is surfaced as a plain
 * service-layer value object rather than a screen send: the request is the nested
 * {@link ReportRequest} record and every caller-visible outcome (validation message,
 * confirmation prompt, or submission result) is returned as a {@link ReportResult}. This
 * service deliberately uses no {@code dto/} or {@code mapper/} types. Screen navigation and
 * PF-key handling (PF3 back to the main menu, {@code Enter} to process) are the concern of
 * the web controller, not this service.
 *
 * <h2>Design constraints and a documented deviation</h2>
 * The service is stateless and uses constructor injection only (no field injection, no
 * Lombok). Monetary values are not handled here, so no {@code java.math.BigDecimal} is
 * needed. The card CVV and passwords are never touched or logged.
 *
 * <p><strong>Deviation (range checks).</strong> The migration plan suggested delegating the
 * {@code month <= 12} / {@code day <= 31} range checks to a {@code service.rule.DateRangeRule}
 * component. That rule class is not among this file's declared dependencies and is not
 * present in the module at build time, so &mdash; to keep the module compilable and to avoid
 * importing a type that does not exist &mdash; the two trivial range comparisons are
 * implemented inline here. The observable behavior (the exact {@code "Not a valid Month..."}
 * / {@code "Not a valid Day..."} messages and their evaluation order) is identical.</p>
 */
@Service
public class ReportService {

    /** SLF4J logger; correlation ids are attached by the servlet/batch MDC filters. */
    private static final Logger LOGGER = LoggerFactory.getLogger(ReportService.class);

    /** Display name of the monthly report (COBOL {@code WS-REPORT-NAME = 'Monthly'}). */
    private static final String REPORT_NAME_MONTHLY = "Monthly";

    /** Display name of the yearly report (COBOL {@code WS-REPORT-NAME = 'Yearly'}). */
    private static final String REPORT_NAME_YEARLY = "Yearly";

    /** Display name of the custom report (COBOL {@code WS-REPORT-NAME = 'Custom'}). */
    private static final String REPORT_NAME_CUSTOM = "Custom";

    /**
     * The date mask shared by the legacy {@code WS-START-DATE}/{@code WS-END-DATE}
     * ({@code YYYY-MM-DD}) and expected by {@link DateValidationService} and the report
     * batch job's {@code startDate}/{@code endDate} job parameters.
     */
    private static final String DATE_FORMAT = "YYYY-MM-DD";

    /** Formatter that renders a {@link LocalDate} as a {@code YYYY-MM-DD} string. */
    private static final DateTimeFormatter ISO_DATE = DateTimeFormatter.ISO_LOCAL_DATE;

    /** Job-parameter key for the inclusive range start ({@code YYYY-MM-DD}). */
    private static final String PARAM_START_DATE = "startDate";

    /** Job-parameter key for the inclusive range end ({@code YYYY-MM-DD}). */
    private static final String PARAM_END_DATE = "endDate";

    /** Job-parameter key carrying the report type name (diagnostic / traceability). */
    private static final String PARAM_REPORT_TYPE = "reportType";

    /**
     * Job-parameter key for the unique submission timestamp. Including a distinct value on
     * every submission makes each {@code JobInstance} unique so the report job is
     * re-runnable (the legacy program could be re-invoked to resubmit the same range).
     */
    private static final String PARAM_REQUESTED_AT = "requestedAt";

    /** Upper bound for a valid month (COBOL {@code SDTMMI > '12'} / {@code EDTMMI > '12'}). */
    private static final int MAX_MONTH = 12;

    /** Upper bound for a valid day (COBOL {@code SDTDDI > '31'} / {@code EDTDDI > '31'}). */
    private static final int MAX_DAY = 31;

    /** Exact COBOL literal for a request with no report type selected ({@code WHEN OTHER}). */
    private static final String MSG_SELECT_REPORT_TYPE = "Select a report type to print report...";

    /** Exact COBOL literal: start-date month is blank. */
    private static final String MSG_START_MONTH_EMPTY = "Start Date - Month can NOT be empty...";

    /** Exact COBOL literal: start-date day is blank. */
    private static final String MSG_START_DAY_EMPTY = "Start Date - Day can NOT be empty...";

    /** Exact COBOL literal: start-date year is blank. */
    private static final String MSG_START_YEAR_EMPTY = "Start Date - Year can NOT be empty...";

    /** Exact COBOL literal: end-date month is blank. */
    private static final String MSG_END_MONTH_EMPTY = "End Date - Month can NOT be empty...";

    /** Exact COBOL literal: end-date day is blank. */
    private static final String MSG_END_DAY_EMPTY = "End Date - Day can NOT be empty...";

    /** Exact COBOL literal: end-date year is blank. */
    private static final String MSG_END_YEAR_EMPTY = "End Date - Year can NOT be empty...";

    /** Exact COBOL literal: start-date month is non-numeric or greater than 12. */
    private static final String MSG_START_MONTH_INVALID = "Start Date - Not a valid Month...";

    /** Exact COBOL literal: start-date day is non-numeric or greater than 31. */
    private static final String MSG_START_DAY_INVALID = "Start Date - Not a valid Day...";

    /** Exact COBOL literal: start-date year is non-numeric. */
    private static final String MSG_START_YEAR_INVALID = "Start Date - Not a valid Year...";

    /** Exact COBOL literal: end-date month is non-numeric or greater than 12. */
    private static final String MSG_END_MONTH_INVALID = "End Date - Not a valid Month...";

    /** Exact COBOL literal: end-date day is non-numeric or greater than 31. */
    private static final String MSG_END_DAY_INVALID = "End Date - Not a valid Day...";

    /** Exact COBOL literal: end-date year is non-numeric. */
    private static final String MSG_END_YEAR_INVALID = "End Date - Not a valid Year...";

    /** Exact COBOL literal: start date is not a real calendar date ({@code CSUTLDTC}). */
    private static final String MSG_START_DATE_INVALID = "Start Date - Not a valid date...";

    /** Exact COBOL literal: end date is not a real calendar date ({@code CSUTLDTC}). */
    private static final String MSG_END_DATE_INVALID = "End Date - Not a valid date...";

    /** Prefix of the "please confirm" prompt; the report name and suffix are appended. */
    private static final String MSG_CONFIRM_PREFIX = "Please confirm to print the ";

    /** Suffix of the "please confirm" prompt (COBOL {@code ' report...'}). */
    private static final String MSG_CONFIRM_SUFFIX = " report...";

    /**
     * Suffix of the invalid-confirmation message; the offending value is quoted and this
     * suffix (which supplies the closing quote) is appended, yielding for example
     * {@code '"x" is not a valid value to confirm...'} (COBOL {@code SUBMIT-JOB-TO-INTRDR}).
     */
    private static final String MSG_INVALID_CONFIRM_SUFFIX = "\" is not a valid value to confirm...";

    /** Suffix of the success message (COBOL {@code ' report submitted for printing ...'}). */
    private static final String MSG_SUBMITTED_SUFFIX = " report submitted for printing ...";

    /**
     * Prefix of the controlled submit-failure message. The COBOL analog is the
     * {@code 'Unable to Write TDQ (JOBS)...'} outcome of {@code WIRTE-JOBSUB-TDQ}; here it
     * describes a failure to launch the batch job, without leaking any stack detail.
     */
    private static final String MSG_SUBMIT_FAILED_PREFIX = "Unable to submit the ";

    /** Suffix of the controlled submit-failure message. */
    private static final String MSG_SUBMIT_FAILED_SUFFIX = " report for printing ...";

    /** Date-validation collaborator (the {@code CSUTLDTC} re-platform). Never {@code null}. */
    private final DateValidationService dateValidationService;

    /**
     * Posted-transaction repository. Used only for a best-effort, non-gating diagnostic
     * count when submitting (see {@link #logAvailableTransactions}); the actual report
     * generation is performed by the batch job. Never {@code null}.
     */
    private final TransactionRepository transactionRepository;

    /** Spring Batch launcher used to submit the report job. Never {@code null}. */
    private final JobLauncher jobLauncher;

    /**
     * The report batch job ({@code transactionReportJob}, the {@code CBTRN03C} re-platform),
     * injected by the {@link Job} interface and qualified by bean name so no compile-time
     * dependency on the {@code batch/} package is introduced. Resolved lazily (see the
     * constructor's {@link Lazy} annotation) because the job is a runtime bean contributed by
     * the batch layer and is only needed when a report is actually submitted, never during
     * context startup. Never {@code null} once resolved.
     */
    private final Job reportJob;

    /**
     * The clock used to derive the "current date" for the Monthly and Yearly report ranges
     * ({@link #monthlyReport}/{@link #yearlyReport}). It is a collaborator (never a direct
     * {@code LocalDate.now()} call) so those derived ranges can be pinned deterministically in
     * tests. In production it is {@link Clock#systemDefaultZone()} (supplied by the primary
     * constructor), so behavior is unchanged. Never {@code null}.
     */
    private final Clock clock;

    /**
     * Creates the report service with its collaborators, using the system-default-zone clock for
     * the Monthly/Yearly "current date" ranges. This is the constructor Spring uses to build the
     * singleton bean (the {@link Autowired} annotation is required only because a second,
     * package-private constructor exists for tests). It delegates to
     * {@link #ReportService(DateValidationService, TransactionRepository, JobLauncher, Job, Clock)}
     * with {@link Clock#systemDefaultZone()}, so the derived ranges use the real wall clock exactly
     * as before. Constructor injection only; the constructor merely stores the references and
     * invokes no overridable method, so it is safe against construction-time {@code this}-escape.
     *
     * @param dateValidationService the {@code CSUTLDTC} date-validation re-platform
     * @param transactionRepository the posted-transaction repository (diagnostic use only)
     * @param jobLauncher           the Spring Batch job launcher
     * @param reportJob             the {@code transactionReportJob} bean, injected by
     *                              {@link Job} interface via {@link Qualifier} and resolved
     *                              lazily ({@link Lazy}) since the batch job is a runtime bean
     *                              only needed on report submission, not at context startup
     */
    @Autowired
    public ReportService(DateValidationService dateValidationService,
                         TransactionRepository transactionRepository,
                         JobLauncher jobLauncher,
                         @Lazy @Qualifier("transactionReportJob") Job reportJob) {
        this(dateValidationService, transactionRepository, jobLauncher, reportJob,
                Clock.systemDefaultZone());
    }

    /**
     * Creates the report service with an explicit {@link Clock}, allowing the Monthly/Yearly
     * "current date" ranges to be pinned deterministically. Package-private: it exists so unit tests
     * can inject a fixed {@link Clock} and assert the derived ranges against a known reference date
     * without coupling to the wall clock (QA MINOR finding). Production wiring always goes through
     * the public constructor, which supplies {@link Clock#systemDefaultZone()}.
     *
     * @param dateValidationService the {@code CSUTLDTC} date-validation re-platform
     * @param transactionRepository the posted-transaction repository (diagnostic use only)
     * @param jobLauncher           the Spring Batch job launcher
     * @param reportJob             the {@code transactionReportJob} bean
     * @param clock                 the clock used for the Monthly/Yearly ranges; must not be
     *                              {@code null}
     */
    ReportService(DateValidationService dateValidationService,
                  TransactionRepository transactionRepository,
                  JobLauncher jobLauncher,
                  Job reportJob,
                  Clock clock) {
        this.dateValidationService = dateValidationService;
        this.transactionRepository = transactionRepository;
        this.jobLauncher = jobLauncher;
        this.reportJob = reportJob;
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /**
     * The three mutually exclusive report types offered by the {@code CORPT0A} screen
     * (COBOL fields {@code MONTHLYI}, {@code YEARLYI}, {@code CUSTOMI}).
     */
    public enum ReportType {
        /** Current calendar month. */
        MONTHLY,
        /** Current calendar year. */
        YEARLY,
        /** Operator-supplied start and end date. */
        CUSTOM
    }

    /**
     * Immutable transaction-report request &mdash; the service-layer analog of the fields
     * entered on the {@code CORPT0A} screen. For {@link ReportType#CUSTOM} the
     * {@code startDate} and {@code endDate} are operator-supplied {@code YYYY-MM-DD} values
     * (validated field-by-field); for {@link ReportType#MONTHLY} and
     * {@link ReportType#YEARLY} they are ignored because the range is derived from the
     * current date.
     *
     * @param type        the selected report type; {@code null} means "no type selected"
     * @param startDate   the custom range start ({@code YYYY-MM-DD}); ignored unless
     *                    {@code type} is {@link ReportType#CUSTOM}
     * @param endDate     the custom range end ({@code YYYY-MM-DD}); ignored unless
     *                    {@code type} is {@link ReportType#CUSTOM}
     * @param confirmFlag the confirmation flag: {@code "Y"} to submit, {@code "N"} to
     *                    cancel, blank to be prompted, anything else is rejected
     */
    public record ReportRequest(ReportType type, String startDate, String endDate, String confirmFlag) {
    }

    /**
     * Immutable outcome of a report request &mdash; the service-layer analog of the message
     * the COBOL placed in {@code WS-MESSAGE}/{@code ERRMSGO} before re-displaying the screen.
     *
     * @param submitted {@code true} only when the report batch job was launched
     * @param message   the caller-visible message (never {@code null}); empty when a
     *                  {@code "N"} confirmation cleared the request without submitting
     */
    public record ReportResult(boolean submitted, String message) {
    }

    /**
     * Validates a transaction-report request and, when valid and confirmed, submits the
     * report batch job. This is the service-layer entry point that reproduces the COBOL
     * {@code PROCESS-ENTER-KEY} dispatch: a missing type yields the
     * {@code "Select a report type to print report..."} message, while each type routes to
     * its range computation and then to the shared confirmation/submission flow.
     *
     * @param request the report request; a {@code null} request or a {@code null}
     *                {@link ReportRequest#type()} is treated as "no type selected"
     * @return the outcome, carrying either a validation/confirmation message or the
     *         submission result; never {@code null}
     */
    public ReportResult requestReport(ReportRequest request) {
        // COBOL PROCESS-ENTER-KEY EVALUATE TRUE ... WHEN OTHER: no report type flag set.
        if (request == null || request.type() == null) {
            return new ReportResult(false, MSG_SELECT_REPORT_TYPE);
        }
        return switch (request.type()) {
            case MONTHLY -> monthlyReport(request);
            case YEARLY -> yearlyReport(request);
            case CUSTOM -> customReport(request);
        };
    }

    /**
     * Handles a {@link ReportType#MONTHLY} request. Reproduces the COBOL "Monthly" branch:
     * the range is the first day of the current month through its last day, after which the
     * shared confirmation/submission flow runs.
     *
     * @param request the originating request (for its confirmation flag)
     * @return the confirmation or submission outcome
     */
    private ReportResult monthlyReport(ReportRequest request) {
        // "Today" is read from the injected Clock (not a direct LocalDate.now()) so the derived
        // range is deterministically pinnable in tests; in production the clock is the system zone.
        final LocalDate today = LocalDate.now(clock);
        final String startDate = today.withDayOfMonth(1).format(ISO_DATE);
        final String endDate = today.withDayOfMonth(today.lengthOfMonth()).format(ISO_DATE);
        return handleConfirmation(REPORT_NAME_MONTHLY, startDate, endDate, request.confirmFlag());
    }

    /**
     * Handles a {@link ReportType#YEARLY} request. Reproduces the COBOL "Yearly" branch:
     * the range is {@code YYYY-01-01} through {@code YYYY-12-31} of the current year, after
     * which the shared confirmation/submission flow runs.
     *
     * @param request the originating request (for its confirmation flag)
     * @return the confirmation or submission outcome
     */
    private ReportResult yearlyReport(ReportRequest request) {
        // "Current year" is read from the injected Clock (not a direct LocalDate.now()) so the
        // derived range is deterministically pinnable in tests (see the class Clock field).
        final int year = LocalDate.now(clock).getYear();
        final String startDate = LocalDate.of(year, 1, 1).format(ISO_DATE);
        final String endDate = LocalDate.of(year, 12, 31).format(ISO_DATE);
        return handleConfirmation(REPORT_NAME_YEARLY, startDate, endDate, request.confirmFlag());
    }

    /**
     * Handles a {@link ReportType#CUSTOM} request. Reproduces the COBOL "Custom" branch:
     * the operator-supplied start and end dates are validated field-by-field (short-circuit
     * on the first failure); only when both are valid does the shared confirmation/submission
     * flow run, using the normalized {@code YYYY-MM-DD} values.
     *
     * @param request the originating request (start date, end date, confirmation flag)
     * @return the first validation error, or the confirmation/submission outcome
     */
    private ReportResult customReport(ReportRequest request) {
        final DateParts start = decompose(request.startDate());
        final DateParts end = decompose(request.endDate());

        final String validationError = validateCustomParts(start, end);
        if (validationError != null) {
            return new ReportResult(false, validationError);
        }

        final String startDate = normalize(start);
        final String endDate = normalize(end);
        return handleConfirmation(REPORT_NAME_CUSTOM, startDate, endDate, request.confirmFlag());
    }

    /**
     * Validates the six custom-date parts in the exact COBOL order, returning the first
     * failing message or {@code null} when all parts are valid. The order &mdash; all
     * emptiness checks, then all numeric/range checks, then the two full-date checks &mdash;
     * mirrors {@code CORPT00C} {@code PROCESS-ENTER-KEY}, where the first failure sent the
     * screen and returned to CICS (paragraph {@code SEND-TRNRPT-SCREEN} ends with
     * {@code GO TO RETURN-TO-CICS}), so only the first error was ever surfaced.
     *
     * @param start the decomposed start-date parts
     * @param end   the decomposed end-date parts
     * @return the first caller-visible validation message, or {@code null} if valid
     */
    private String validateCustomParts(DateParts start, DateParts end) {
        // 1) Emptiness checks (COBOL EVALUATE: Start Month, Day, Year; then End Month, Day, Year).
        if (isBlank(start.month())) {
            return MSG_START_MONTH_EMPTY;
        }
        if (isBlank(start.day())) {
            return MSG_START_DAY_EMPTY;
        }
        if (isBlank(start.year())) {
            return MSG_START_YEAR_EMPTY;
        }
        if (isBlank(end.month())) {
            return MSG_END_MONTH_EMPTY;
        }
        if (isBlank(end.day())) {
            return MSG_END_DAY_EMPTY;
        }
        if (isBlank(end.year())) {
            return MSG_END_YEAR_EMPTY;
        }

        // 2) Numeric + range checks (month numeric and <= 12; day numeric and <= 31; year numeric).
        if (!isAllDigits(start.month()) || toIntSafe(start.month()) > MAX_MONTH) {
            return MSG_START_MONTH_INVALID;
        }
        if (!isAllDigits(start.day()) || toIntSafe(start.day()) > MAX_DAY) {
            return MSG_START_DAY_INVALID;
        }
        if (!isAllDigits(start.year())) {
            return MSG_START_YEAR_INVALID;
        }
        if (!isAllDigits(end.month()) || toIntSafe(end.month()) > MAX_MONTH) {
            return MSG_END_MONTH_INVALID;
        }
        if (!isAllDigits(end.day()) || toIntSafe(end.day()) > MAX_DAY) {
            return MSG_END_DAY_INVALID;
        }
        if (!isAllDigits(end.year())) {
            return MSG_END_YEAR_INVALID;
        }

        // 3) Full calendar-date validity (the CALL 'CSUTLDTC' analog), Start then End.
        if (!dateValidationService.isValid(normalize(start), DATE_FORMAT)) {
            return MSG_START_DATE_INVALID;
        }
        if (!dateValidationService.isValid(normalize(end), DATE_FORMAT)) {
            return MSG_END_DATE_INVALID;
        }

        return null;
    }

    /**
     * Reproduces the COBOL {@code SUBMIT-JOB-TO-INTRDR} confirmation gate. A blank
     * confirmation prompts for confirmation; {@code "Y"} (any case) submits the job;
     * {@code "N"} (any case) cancels without submitting and clears the request; any other
     * value is rejected with the offending value quoted.
     *
     * @param reportName  the report display name ("Monthly", "Yearly" or "Custom")
     * @param startDate   the inclusive range start ({@code YYYY-MM-DD})
     * @param endDate     the inclusive range end ({@code YYYY-MM-DD})
     * @param confirmFlag the operator confirmation value
     * @return the confirmation prompt, the rejection message, the cleared (empty) result,
     *         or the submission outcome
     */
    private ReportResult handleConfirmation(String reportName, String startDate, String endDate, String confirmFlag) {
        // COBOL: IF CONFIRMI = SPACES OR LOW-VALUES -> "Please confirm to print the <name> report..."
        if (isBlank(confirmFlag)) {
            return new ReportResult(false, MSG_CONFIRM_PREFIX + reportName + MSG_CONFIRM_SUFFIX);
        }
        // COBOL: WHEN CONFIRMI = 'Y' OR 'y' -> submit the job.
        if ("Y".equalsIgnoreCase(confirmFlag)) {
            return submit(reportName, startDate, endDate);
        }
        // COBOL: WHEN CONFIRMI = 'N' OR 'n' -> clear all fields and re-display (no submission).
        if ("N".equalsIgnoreCase(confirmFlag)) {
            return new ReportResult(false, "");
        }
        // COBOL: WHEN OTHER -> '"x" is not a valid value to confirm...'
        return new ReportResult(false, "\"" + confirmFlag + MSG_INVALID_CONFIRM_SUFFIX);
    }

    /**
     * Launches the report batch job with re-runnable job parameters, reproducing the COBOL
     * {@code SUBMIT-JOB-TO-INTRDR}/{@code WIRTE-JOBSUB-TDQ} job submission. The
     * {@code startDate}/{@code endDate} parameters (both {@code YYYY-MM-DD}) are consumed by
     * the report job's reader; a unique {@code requestedAt} timestamp makes each job instance
     * distinct so the same range can be resubmitted. Any launcher failure is translated into a
     * controlled message that leaks no stack detail (the COBOL {@code 'Unable to Write TDQ
     * (JOBS)...'} analog); the exception itself is logged at error for diagnostics.
     *
     * @param reportName the report display name (also passed as a job parameter)
     * @param startDate  the inclusive range start ({@code YYYY-MM-DD})
     * @param endDate    the inclusive range end ({@code YYYY-MM-DD})
     * @return a submitted result with the success message, or a non-submitted result with a
     *         controlled failure message
     */
    private ReportResult submit(String reportName, String startDate, String endDate) {
        final JobParameters parameters = new JobParametersBuilder()
                .addString(PARAM_START_DATE, startDate)
                .addString(PARAM_END_DATE, endDate)
                .addString(PARAM_REPORT_TYPE, reportName)
                .addLong(PARAM_REQUESTED_AT, System.currentTimeMillis())
                .toJobParameters();

        logAvailableTransactions(reportName, startDate, endDate);

        try {
            final JobExecution execution = jobLauncher.run(reportJob, parameters);
            LOGGER.info("Submitted {} transaction report for printing [range {} .. {}]; batch status={}",
                    reportName, startDate, endDate, execution.getStatus());
            return new ReportResult(true, reportName + MSG_SUBMITTED_SUFFIX);
        } catch (JobExecutionException ex) {
            LOGGER.error("Unable to submit {} transaction report for printing [range {} .. {}]",
                    reportName, startDate, endDate, ex);
            return new ReportResult(false, MSG_SUBMIT_FAILED_PREFIX + reportName + MSG_SUBMIT_FAILED_SUFFIX);
        }
    }

    /**
     * Emits a best-effort diagnostic count of posted transactions, but only when debug
     * logging is enabled. The legacy {@code CORPT00C} performed no such pre-count, so this
     * must never influence the submission outcome: the count is read only under debug and any
     * failure is swallowed after a debug log, preserving behavioral parity.
     *
     * @param reportName the report display name (for the log line)
     * @param startDate  the inclusive range start (for the log line)
     * @param endDate    the inclusive range end (for the log line)
     */
    private void logAvailableTransactions(String reportName, String startDate, String endDate) {
        if (!LOGGER.isDebugEnabled()) {
            return;
        }
        try {
            final long postedTransactions = transactionRepository.count();
            LOGGER.debug("Preparing {} transaction report [range {} .. {}]; {} posted transaction(s) on file",
                    reportName, startDate, endDate, postedTransactions);
        } catch (RuntimeException ex) {
            LOGGER.debug("Unable to read posted-transaction count for {} report diagnostics", reportName, ex);
        }
    }

    /**
     * Splits a candidate {@code YYYY-MM-DD} value into its year, month and day tokens,
     * preserving empty tokens (using a split limit of {@code -1}) so the emptiness checks can
     * distinguish a missing part. A {@code null} value yields three empty tokens.
     *
     * @param date the candidate date string; may be {@code null}
     * @return the decomposed parts (never {@code null}; components never {@code null})
     */
    private static DateParts decompose(String date) {
        final String value = date == null ? "" : date;
        final String[] tokens = value.split("-", -1);
        final String year = tokens.length > 0 ? tokens[0] : "";
        final String month = tokens.length > 1 ? tokens[1] : "";
        final String day = tokens.length > 2 ? tokens[2] : "";
        return new DateParts(year, month, day);
    }

    /**
     * Rebuilds a normalized {@code YYYY-MM-DD} string from already-numeric parts, zero-padding
     * the year to four digits and the month and day to two, mirroring the COBOL move of the
     * {@code NUMVAL-C} results back into the fixed-width date fields. Only invoked after the
     * numeric checks have passed, so each part parses safely.
     *
     * @param parts the validated numeric date parts
     * @return the normalized {@code YYYY-MM-DD} value
     */
    private static String normalize(DateParts parts) {
        return String.format("%04d-%02d-%02d",
                toIntSafe(parts.year()), toIntSafe(parts.month()), toIntSafe(parts.day()));
    }

    /**
     * Reports whether a value is absent or blank &mdash; the analog of the COBOL
     * {@code = SPACES OR LOW-VALUES} test.
     *
     * @param value the value to test; may be {@code null}
     * @return {@code true} if {@code value} is {@code null} or contains only whitespace
     */
    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    /**
     * Reports whether a value is non-empty and composed only of ASCII digits &mdash; the
     * analog of the COBOL {@code IS NOT NUMERIC} test (negated).
     *
     * @param value the value to test; may be {@code null}
     * @return {@code true} if {@code value} is non-empty and every character is {@code '0'}-{@code '9'}
     */
    private static boolean isAllDigits(String value) {
        if (value == null || value.isEmpty()) {
            return false;
        }
        for (int i = 0; i < value.length(); i++) {
            final char c = value.charAt(i);
            if (c < '0' || c > '9') {
                return false;
            }
        }
        return true;
    }

    /**
     * Parses an all-digit token to an {@code int}, returning {@link Integer#MAX_VALUE} when
     * the value overflows {@code int}. Because the caller has already established that the
     * token is all digits, the only failure mode is overflow, which is deliberately mapped to
     * an out-of-range value so it fails the subsequent range comparison.
     *
     * @param digits an all-digit token (per {@link #isAllDigits(String)})
     * @return the parsed value, or {@link Integer#MAX_VALUE} on overflow
     */
    private static int toIntSafe(String digits) {
        try {
            return Integer.parseInt(digits);
        } catch (NumberFormatException ex) {
            return Integer.MAX_VALUE;
        }
    }

    /**
     * The three tokens of a decomposed date. Components are never {@code null}; a missing
     * part is represented by an empty string.
     *
     * @param year  the year token
     * @param month the month token
     * @param day   the day token
     */
    private record DateParts(String year, String month, String day) {
    }
}
