package com.carddemo.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.carddemo.dto.report.OutputFormat;
import com.carddemo.dto.report.ReportRequest;
import com.carddemo.dto.report.ReportType;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.concurrent.CompletableFuture;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.JobExecution;
import org.springframework.batch.core.JobParameters;
import org.springframework.batch.core.configuration.JobRegistry;
import org.springframework.batch.core.launch.JobLauncher;
import org.springframework.batch.core.launch.NoSuchJobException;
import org.springframework.batch.core.repository.JobExecutionAlreadyRunningException;

/**
 * Unit test for {@link ReportService} — the modern replacement for COBOL program
 * {@code app/cbl/CORPT00C.cbl} (CICS TRANID {@code 'CR00'}, "Print Transaction reports by
 * submitting batch job from online").
 *
 * <p><b>Architectural mapping verified by these tests.</b> {@code CORPT00C}'s
 * {@code SUBMIT-JOB-TO-INTRDR} paragraph built an inline JCL stream (job {@code TRNRPT00},
 * {@code //STEP10 EXEC PROC=TRANREPT}) and handed it to the internal reader one line at a time via
 * {@code EXEC CICS WRITEQ TD QUEUE('JOBS')} ({@code CORPT00C.cbl:L515-L523}). In the Spring Boot
 * monolith that internal-reader hand-off becomes an in-process
 * {@link JobLauncher#run(Job, JobParameters)} on a {@link Job} resolved by name from the
 * {@link JobRegistry} (PR-25: a single in-process monolith with no external job scheduler /
 * message queue; PR-26: no external services). These tests assert that {@code ReportService}
 * resolves and launches the registered {@code transactionReportJob} and conveys the report period
 * through {@link JobParameters} rather than a JCL text stream.</p>
 *
 * <p><b>Confirmation gate parity.</b> {@code SUBMIT-JOB-TO-INTRDR} ({@code CORPT00C.cbl:L476-L494})
 * only submits when {@code CONFIRMI = 'Y' OR 'y'}; {@code 'N'/'n'} cancels and any other value is
 * rejected as invalid. The error-handling tests assert {@code ReportService} reproduces this gate
 * by rejecting a non-affirmative {@code confirmation} with {@link IllegalArgumentException} before
 * any job interaction occurs.</p>
 *
 * <p><b>Report-type parity.</b> {@code CORPT00C}'s {@code EVALUATE TRUE}
 * ({@code CORPT00C.cbl:L212-L443}) branches on {@code MONTHLYI}/{@code YEARLYI}/{@code CUSTOMI};
 * crucially all three branches submit the <em>same</em> {@code TRANREPT} job — only the derived
 * {@code PARM-START-DATE}/{@code PARM-END-DATE} differ. The report-type tests assert every
 * {@link ReportType} resolves to the single registered {@code transactionReportJob} with the period
 * carried in job parameters.</p>
 *
 * <p><b>Test strategy.</b> Pure Mockito unit test (no Spring context, no database). The
 * {@link JobLauncher} and {@link JobRegistry} collaborators are mocked and injected via the
 * Lombok-generated constructor (PR-29: constructor injection). Although
 * {@link ReportService#submitReport(ReportRequest)} is annotated {@code @Async}, invoking it
 * directly here bypasses Spring's async proxy, so the method runs synchronously and any validation
 * or launch failure propagates synchronously — which is what the {@code assertThatThrownBy}
 * assertions rely on.</p>
 *
 * @see ReportService
 * @see ReportRequest
 * @see ReportType
 * @see OutputFormat
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("ReportService — replaces COBOL CORPT00C TDQ submission with Spring Batch JobLauncher (PR-25/PR-26/PR-29)")
class ReportServiceTest {

    /** The single registered report job name resolved for every {@link ReportType}. */
    private static final String TRANSACTION_REPORT_JOB_NAME = "transactionReportJob";

    /** Canonical report-period start used across scenarios (CORPT00C PARM-START-DATE analogue). */
    private static final LocalDate START_DATE = LocalDate.of(2022, 7, 1);

    /** Canonical report-period end used across scenarios (CORPT00C PARM-END-DATE analogue). */
    private static final LocalDate END_DATE = LocalDate.of(2022, 7, 31);

    @Mock
    private JobLauncher jobLauncher;

    @Mock
    private JobRegistry jobRegistry;

    @Mock
    private Job transactionReportJob;

    @Mock
    private JobExecution jobExecution;

    @InjectMocks
    private ReportService reportService;

    @Captor
    private ArgumentCaptor<JobParameters> jobParametersCaptor;

    /** Baseline, fully-valid request reused (and selectively mutated) by each test. */
    private ReportRequest request;

    @BeforeEach
    void setUp() {
        // A valid request mirrors a confirmed CUSTOM report over an explicit date window.
        // confirmation="Y" satisfies the COBOL CONFIRMI = 'Y' gate (CORPT00C.cbl:L478); without it
        // ReportService rejects the submission with IllegalArgumentException before any job lookup.
        request = new ReportRequest();
        request.setReportType(ReportType.CUSTOM);
        request.setStartDate(START_DATE);
        request.setEndDate(END_DATE);
        request.setOutputFormat(OutputFormat.PDF);
        request.setConfirmation("Y");
    }

    /**
     * Stubs the happy-path collaborators: the registry resolves the report job by name and the
     * launcher returns a {@link JobExecution}. Declared {@code throws Exception} because both
     * {@link JobRegistry#getJob(String)} and {@link JobLauncher#run(Job, JobParameters)} declare
     * checked exceptions in their signatures.
     */
    private void givenReportJobIsLaunchable() throws Exception {
        when(jobRegistry.getJob(anyString())).thenReturn(transactionReportJob);
        when(jobLauncher.run(any(Job.class), any(JobParameters.class))).thenReturn(jobExecution);
    }

    @Nested
    @DisplayName("Submit report (replaces EXEC CICS WRITEQ TD QUEUE('JOBS'))")
    class SubmitReport {

        @Test
        @DisplayName("Resolves the report job from the registry and launches it exactly once")
        void shouldInvokeJobLauncherForReportSubmission() throws Exception {
            givenReportJobIsLaunchable();

            reportService.submitReport(request);

            // The TDQ enqueue is replaced by: look up the Job by name, then launch it once.
            verify(jobRegistry).getJob(anyString());
            verify(jobLauncher).run(eq(transactionReportJob), any(JobParameters.class));
        }

        @Test
        @DisplayName("Carries reportType, outputFormat and the inclusive date window as JobParameters")
        void shouldPassReportTypeAndDatesAsJobParameters() throws Exception {
            givenReportJobIsLaunchable();

            reportService.submitReport(request);

            verify(jobLauncher).run(any(Job.class), jobParametersCaptor.capture());
            JobParameters captured = jobParametersCaptor.getValue();
            assertThat(captured).isNotNull();
            // String metadata params (CORPT00C SYMNAMES / DATEPARM analogues).
            assertThat(captured.getString("reportType")).isEqualTo("CUSTOM");
            assertThat(captured.getString("outputFormat")).isEqualTo("PDF");
            // Date window: start-of-day .. end-of-day reproduces COBOL "every transaction in
            // [PARM-START-DATE .. PARM-END-DATE]" (findByOrigTimestampBetween is inclusive).
            assertThat(captured.getLocalDateTime("startDate")).isEqualTo(START_DATE.atStartOfDay());
            assertThat(captured.getLocalDateTime("endDate")).isEqualTo(END_DATE.atTime(LocalTime.MAX));
        }

        @Test
        @DisplayName("Replaces the TDQ hand-off by launching the registered 'transactionReportJob' by name")
        void shouldReplaceTdqSubmissionByLaunchingJobByName() throws Exception {
            givenReportJobIsLaunchable();

            reportService.submitReport(request);

            // Architectural parity assertion: the named TRANREPT proc submitted to QUEUE('JOBS')
            // becomes a named-job lookup followed by an in-process launch.
            verify(jobRegistry).getJob(TRANSACTION_REPORT_JOB_NAME);
            verify(jobLauncher).run(any(Job.class), any(JobParameters.class));
        }

        @Test
        @DisplayName("Returns the launched jobExecutionId in a completed future (HTTP 202 analogue)")
        void shouldReturnLaunchedJobExecutionIdInCompletedFuture() throws Exception {
            givenReportJobIsLaunchable();
            when(jobExecution.getId()).thenReturn(42L);

            CompletableFuture<Long> result = reportService.submitReport(request);

            assertThat(result).isCompletedWithValue(42L);
        }

        @Test
        @DisplayName("Defaults outputFormat to PDF when the request omits it")
        void shouldDefaultOutputFormatToPdfWhenOmitted() throws Exception {
            request.setOutputFormat(null);
            givenReportJobIsLaunchable();

            reportService.submitReport(request);

            verify(jobLauncher).run(any(Job.class), jobParametersCaptor.capture());
            assertThat(jobParametersCaptor.getValue().getString("outputFormat")).isEqualTo("PDF");
        }
    }

    @Nested
    @DisplayName("Report types (Monthly/Yearly/Custom — CORPT00C EVALUATE TRUE, L212-L443)")
    class ReportTypes {

        @Test
        @DisplayName("MONTHLY report submits the unified transactionReportJob with reportType=MONTHLY")
        void shouldHandleMonthlyReportRequest() throws Exception {
            request.setReportType(ReportType.MONTHLY);
            givenReportJobIsLaunchable();

            reportService.submitReport(request);

            // CORPT00C MONTHLYI branch: derives a month window, then submits the SAME TRANREPT proc.
            verify(jobRegistry).getJob(TRANSACTION_REPORT_JOB_NAME);
            verify(jobLauncher).run(any(Job.class), jobParametersCaptor.capture());
            assertThat(jobParametersCaptor.getValue().getString("reportType")).isEqualTo("MONTHLY");
        }

        @Test
        @DisplayName("YEARLY report submits the unified transactionReportJob with reportType=YEARLY")
        void shouldHandleYearlyReportRequest() throws Exception {
            request.setReportType(ReportType.YEARLY);
            givenReportJobIsLaunchable();

            reportService.submitReport(request);

            // CORPT00C YEARLYI branch: derives a year window, then submits the SAME TRANREPT proc.
            verify(jobRegistry).getJob(TRANSACTION_REPORT_JOB_NAME);
            verify(jobLauncher).run(any(Job.class), jobParametersCaptor.capture());
            assertThat(jobParametersCaptor.getValue().getString("reportType")).isEqualTo("YEARLY");
        }

        @Test
        @DisplayName("CUSTOM report submits the unified transactionReportJob with the supplied date window")
        void shouldHandleCustomReportRequest() throws Exception {
            request.setReportType(ReportType.CUSTOM);
            request.setStartDate(START_DATE);
            request.setEndDate(END_DATE);
            givenReportJobIsLaunchable();

            reportService.submitReport(request);

            // CORPT00C CUSTOMI branch: uses caller-supplied dates verbatim for the SAME TRANREPT proc.
            verify(jobRegistry).getJob(TRANSACTION_REPORT_JOB_NAME);
            verify(jobLauncher).run(any(Job.class), jobParametersCaptor.capture());
            JobParameters captured = jobParametersCaptor.getValue();
            assertThat(captured.getString("reportType")).isEqualTo("CUSTOM");
            assertThat(captured.getLocalDateTime("startDate")).isEqualTo(START_DATE.atStartOfDay());
            assertThat(captured.getLocalDateTime("endDate")).isEqualTo(END_DATE.atTime(LocalTime.MAX));
        }
    }

    @Nested
    @DisplayName("Error handling")
    class ErrorHandling {

        @Test
        @DisplayName("Wraps a missing registered job (NoSuchJobException) as IllegalStateException and never launches")
        void shouldThrowIllegalStateWhenReportJobNotRegistered() throws Exception {
            when(jobRegistry.getJob(anyString()))
                    .thenThrow(new NoSuchJobException("No job configured with name: " + TRANSACTION_REPORT_JOB_NAME));

            assertThatThrownBy(() -> reportService.submitReport(request))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining(TRANSACTION_REPORT_JOB_NAME);

            // A configuration error must short-circuit before any launch attempt.
            verifyNoInteractions(jobLauncher);
        }

        @Test
        @DisplayName("Wraps a JobLauncher failure as IllegalStateException")
        void shouldWrapJobLauncherFailureAsIllegalState() throws Exception {
            when(jobRegistry.getJob(anyString())).thenReturn(transactionReportJob);
            when(jobLauncher.run(any(Job.class), any(JobParameters.class)))
                    .thenThrow(new JobExecutionAlreadyRunningException("A job execution is already running"));

            assertThatThrownBy(() -> reportService.submitReport(request))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("Failed to launch report job");
        }

        @Test
        @DisplayName("Rejects a null request without touching the registry or launcher")
        void shouldRejectNullRequest() {
            assertThatThrownBy(() -> reportService.submitReport(null))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("must not be null");

            verifyNoInteractions(jobRegistry, jobLauncher);
        }

        @Test
        @DisplayName("Rejects a non-affirmative confirmation (COBOL CONFIRMI 'N'/'n' cancels submission)")
        void shouldRejectNonAffirmativeConfirmation() {
            request.setConfirmation("N");

            assertThatThrownBy(() -> reportService.submitReport(request))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("confirmation");

            // The confirmation gate (CORPT00C.cbl:L476-L494) blocks submission before any job work.
            verifyNoInteractions(jobRegistry, jobLauncher);
        }

        @Test
        @DisplayName("Rejects a request whose reportType is missing (no period to dispatch)")
        void shouldRejectMissingReportType() {
            request.setReportType(null);

            assertThatThrownBy(() -> reportService.submitReport(request))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("reportType");

            verifyNoInteractions(jobRegistry, jobLauncher);
        }
    }
}
