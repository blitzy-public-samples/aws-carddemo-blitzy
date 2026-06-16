package com.carddemo.service;

import java.time.LocalDate;
import java.time.YearMonth;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.JobExecution;
import org.springframework.batch.core.JobParameters;

import com.carddemo.config.ReportJobSubmitter;
import com.carddemo.dto.ReportRequest;
import com.carddemo.dto.ReportResponse;
import com.carddemo.exception.ValidationException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.anyString;
import static org.mockito.Mockito.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Pure Mockito unit test for {@link ReportService}, the Spring Boot replacement for the
 * legacy CICS online report-request program {@code app/cbl/CORPT00C.cbl} ("Print Transaction
 * reports by submitting batch job from online").
 *
 * <h2>What this test pins (parity with CORPT00C)</h2>
 * <p>{@code ReportService} is a thin <em>launcher</em>: it resolves an effective reporting
 * window from the request's {@code reportType}, (for {@code CUSTOM}) validates the keyed dates
 * through the {@code CSUTLDTC}-equivalent {@link DateValidationService}, resolves the batch job
 * from an injected name&rarr;{@link Job} map, fires it via {@link ReportJobSubmitter}, and returns the
 * resulting {@link JobExecution} id/status inside a {@link ReportResponse} (AAP&nbsp;&sect;0.3.2 —
 * asynchronous job submission reproducing the legacy {@code SUBMIT-JOB-TO-INTRDR} hand-off). The
 * cases below verify, against the <strong>real production class</strong>:</p>
 * <ul>
 *   <li><strong>Date-range derivation</strong> for {@code MONTHLY} / {@code YEARLY} / {@code CUSTOM};</li>
 *   <li><strong>Job resolution</strong> from the map by the exact bean name {@code "transactionReportJob"};</li>
 *   <li>the <strong>launch interaction</strong> and the {@link JobParameters} carried to the job;</li>
 *   <li>the <strong>empty-map &rarr; {@link IllegalStateException}</strong> server-side guard;</li>
 *   <li>{@code CUSTOM} validation failures (missing / reversed / invalid dates).</li>
 * </ul>
 *
 * <h2>Alignment note — MONTHLY end date</h2>
 * <p>The production {@code ReportService} derives the {@code MONTHLY} window as
 * {@code [first-day-of-current-month .. last-day-of-current-month]} via
 * {@code YearMonth.from(today).atEndOfMonth()} (the end is the <em>last day of the month</em>,
 * not "today"). Both committed DTO contracts ({@code dto/ReportRequest}, {@code dto/ReportResponse})
 * document this same "whole current calendar month" semantics, so the assertions below align to the
 * real class accordingly.</p>
 *
 * <h2>Test character</h2>
 * <p>Strictly a JUnit&nbsp;5 + Mockito&nbsp;5 unit test: <em>no</em> Spring context, <em>no</em>
 * database, and <em>no</em> real batch execution. The four collaborators are mocked. Because a
 * {@code Map<String,Job>} cannot be cleanly populated through {@code @InjectMocks}, the service is
 * constructed <strong>explicitly per test</strong> with the real constructor argument order
 * ({@code reportJobSubmitter}, {@code jobs}, {@code dateValidationService}).
 * {@link ReportJobSubmitter#submit} declares a checked Spring Batch exception, so test methods declare
 * {@code throws Exception}. Strict stubbing is honored: the {@code jobExecution}/{@code reportJobSubmitter}
 * stubs appear only in the launch tests, never in the validation-failure or empty-map tests.</p>
 *
 * <p>No personally identifiable information (PII) appears in any test data — no CVV, SSN, card number,
 * or password values are used (AAP&nbsp;&sect;0.6.8, &sect;0.7.1).</p>
 *
 * @see ReportService
 * @see ReportRequest
 * @see ReportResponse
 * @see DateValidationService
 */
@ExtendWith(MockitoExtension.class)
class ReportServiceTest {

    /** The exact bean name under which the transaction-report job is registered and resolved. */
    private static final String JOB_BEAN_NAME = "transactionReportJob";

    /** Asynchronous report-submission port; stubbed only in the launch (happy-path) tests. */
    @Mock
    private ReportJobSubmitter reportJobSubmitter;

    /**
     * The transaction-report {@link Job} mock. Its variable name intentionally matches the
     * {@value #JOB_BEAN_NAME} bean key so the populated map mirrors production wiring exactly.
     */
    @Mock
    private Job transactionReportJob;

    /** The {@link JobExecution} returned by a successful launch; stubbed only in launch tests. */
    @Mock
    private JobExecution jobExecution;

    /** The {@code CSUTLDTC}-equivalent date validator used for {@code CUSTOM} ranges. */
    @Mock
    private DateValidationService dateValidationService;

    // -----------------------------------------------------------------------------------------
    // Helpers — construct the service explicitly (the Map cannot be supplied via @InjectMocks).
    // -----------------------------------------------------------------------------------------

    /**
     * Builds a service whose job map contains the single {@value #JOB_BEAN_NAME} entry, matching
     * production wiring. Used by every launch / happy-path test.
     *
     * @return a {@link ReportService} able to resolve and launch the report job
     */
    private ReportService serviceWithJob() {
        return new ReportService(
                reportJobSubmitter,
                Map.of(JOB_BEAN_NAME, transactionReportJob),
                dateValidationService);
    }

    /**
     * Common launch stubs shared by the happy-path tests: a successful launch returns a
     * {@link JobExecution} with id {@code 7} and status {@link BatchStatus#COMPLETED}. Kept out of
     * the validation-failure / empty-map tests so MockitoExtension strict stubbing stays clean.
     */
    private void stubSuccessfulLaunch() throws Exception {
        when(jobExecution.getId()).thenReturn(7L);
        when(jobExecution.getStatus()).thenReturn(BatchStatus.COMPLETED);
        when(reportJobSubmitter.submit(eq(transactionReportJob), any(JobParameters.class)))
                .thenReturn(jobExecution);
    }

    // =========================================================================================
    // MONTHLY range derivation
    // =========================================================================================

    @Test
    @DisplayName("MONTHLY derives first-day-through-last-day of the current month, launches the job, "
            + "and echoes the execution id/status and date job-parameters")
    void monthly_derivesFirstDayThroughEndOfMonth_andLaunches() throws Exception {
        // Read the clock once for the expected values. The service reads its own LocalDate.now()
        // microseconds later, so the only mismatch window is crossing midnight between the two
        // reads — negligible for a unit test that runs in milliseconds.
        LocalDate today = LocalDate.now();
        LocalDate expectedStart = today.withDayOfMonth(1);
        // Real class: end = last day of the current month (NOT today). See class-level alignment note.
        LocalDate expectedEnd = YearMonth.from(today).atEndOfMonth();

        ReportService service = serviceWithJob();
        stubSuccessfulLaunch();

        ReportResponse resp = service.submitReport(new ReportRequest("MONTHLY", null, null));

        assertThat(resp.reportType()).isEqualTo("MONTHLY");
        assertThat(resp.startDate()).isEqualTo(expectedStart);
        assertThat(resp.endDate()).isEqualTo(expectedEnd);
        assertThat(resp.jobExecutionId()).isEqualTo(7L);
        assertThat(resp.status()).isNotNull().isEqualTo("COMPLETED");

        // The resolved range must be forwarded to the job as run parameters (ISO yyyy-MM-dd strings).
        ArgumentCaptor<JobParameters> paramsCaptor = ArgumentCaptor.forClass(JobParameters.class);
        verify(reportJobSubmitter).submit(eq(transactionReportJob), paramsCaptor.capture());
        JobParameters params = paramsCaptor.getValue();
        assertThat(params.getString("reportType")).isEqualTo("MONTHLY");
        assertThat(params.getString("startDate")).isEqualTo(expectedStart.toString());
        assertThat(params.getString("endDate")).isEqualTo(expectedEnd.toString());
    }

    // =========================================================================================
    // YEARLY range derivation
    // =========================================================================================

    @Test
    @DisplayName("YEARLY derives Jan 1 through Dec 31 of the current year and launches the job")
    void yearly_derivesJan1ThroughDec31() throws Exception {
        int year = LocalDate.now().getYear();
        LocalDate expectedStart = LocalDate.of(year, 1, 1);
        LocalDate expectedEnd = LocalDate.of(year, 12, 31);

        ReportService service = serviceWithJob();
        stubSuccessfulLaunch();

        ReportResponse resp = service.submitReport(new ReportRequest("YEARLY", null, null));

        assertThat(resp.reportType()).isEqualTo("YEARLY");
        assertThat(resp.startDate()).isEqualTo(expectedStart);
        assertThat(resp.endDate()).isEqualTo(expectedEnd);
        assertThat(resp.jobExecutionId()).isEqualTo(7L);

        ArgumentCaptor<JobParameters> paramsCaptor = ArgumentCaptor.forClass(JobParameters.class);
        verify(reportJobSubmitter).submit(eq(transactionReportJob), paramsCaptor.capture());
        JobParameters params = paramsCaptor.getValue();
        assertThat(params.getString("startDate")).isEqualTo(expectedStart.toString());
        assertThat(params.getString("endDate")).isEqualTo(expectedEnd.toString());
    }

    // =========================================================================================
    // CUSTOM range derivation + validation
    // =========================================================================================

    @Test
    @DisplayName("CUSTOM uses the supplied range verbatim, validates each date via "
            + "DateValidationService (CSUTLDTC parity), and launches the job")
    void custom_usesSuppliedRangeVerbatim_validatesEachDate_andLaunches() throws Exception {
        LocalDate start = LocalDate.of(2023, 1, 1);
        LocalDate end = LocalDate.of(2023, 3, 31);

        ReportService service = serviceWithJob();
        stubSuccessfulLaunch();

        ReportResponse resp = service.submitReport(new ReportRequest("CUSTOM", start, end));

        assertThat(resp.reportType()).isEqualTo("CUSTOM");
        assertThat(resp.startDate()).isEqualTo(start);
        assertThat(resp.endDate()).isEqualTo(end);
        assertThat(resp.jobExecutionId()).isEqualTo(7L);

        // CSUTLDTC parity: each custom date is re-validated through the date utility on its
        // ISO-8601 yyyy-MM-dd text, with the field label the production service passes.
        verify(dateValidationService).validateAndParseDate("2023-01-01", "Start date");
        verify(dateValidationService).validateAndParseDate("2023-03-31", "End date");
        verify(reportJobSubmitter).submit(eq(transactionReportJob), any(JobParameters.class));
    }

    @Test
    @DisplayName("CUSTOM with missing start/end dates throws ValidationException and never launches")
    void custom_missingDates_throwsValidationException() throws Exception {
        ReportService service = serviceWithJob();

        // The real ReportService enforces CUSTOM date presence INLINE and raises ValidationException
        // (NOT BusinessRuleException) before it ever delegates to DateValidationService.
        assertThatThrownBy(() -> service.submitReport(new ReportRequest("CUSTOM", null, null)))
                .isInstanceOf(ValidationException.class);

        verify(reportJobSubmitter, never()).submit(any(), any());
        verifyNoInteractions(dateValidationService);
    }

    @Test
    @DisplayName("CUSTOM with a reversed range (start after end) throws ValidationException and never launches")
    void custom_reversedRange_throwsValidationException() throws Exception {
        ReportService service = serviceWithJob();

        LocalDate start = LocalDate.of(2023, 3, 31);
        LocalDate end = LocalDate.of(2023, 1, 1);

        // Real class: both dates are first routed through DateValidationService (here unstubbed →
        // returns null, which the service ignores), then the inline ordering guard rejects start>end
        // with ValidationException("Start date must not be after end date").
        assertThatThrownBy(() -> service.submitReport(new ReportRequest("CUSTOM", start, end)))
                .isInstanceOf(ValidationException.class);

        verify(reportJobSubmitter, never()).submit(any(), any());
    }

    @Test
    @DisplayName("CUSTOM propagates a ValidationException thrown by DateValidationService and never launches")
    void custom_whenDateValidationFails_propagatesValidationException() throws Exception {
        ReportService service = serviceWithJob();

        // Simulate the CSUTLDTC-equivalent rejecting an impossible/badly-formatted date. The stub IS
        // used (the service validates the start date first), keeping strict stubbing clean.
        when(dateValidationService.validateAndParseDate(anyString(), anyString()))
                .thenThrow(new ValidationException("Start date - Not a valid date..."));

        assertThatThrownBy(() -> service.submitReport(
                        new ReportRequest("CUSTOM", LocalDate.of(2023, 1, 1), LocalDate.of(2023, 3, 31))))
                .isInstanceOf(ValidationException.class);

        verify(reportJobSubmitter, never()).submit(any(), any());
    }

    // =========================================================================================
    // Job resolution by name + empty-map guard
    // =========================================================================================

    @Test
    @DisplayName("Resolves the batch job from the injected map by the exact bean name 'transactionReportJob'")
    void reportJob_resolvedByExactBeanName() throws Exception {
        ReportService service = serviceWithJob();
        stubSuccessfulLaunch();

        ReportResponse resp = service.submitReport(new ReportRequest("MONTHLY", null, null));

        // Proves the entry keyed "transactionReportJob" is the one actually launched, and that its
        // JobExecution id is surfaced in the response.
        verify(reportJobSubmitter).submit(eq(transactionReportJob), any(JobParameters.class));
        assertThat(resp.jobExecutionId()).isEqualTo(7L);
    }

    @Test
    @DisplayName("Empty job map throws IllegalStateException (missing transactionReportJob bean) and never launches")
    void emptyJobMap_throwsIllegalStateException() throws Exception {
        // No "transactionReportJob" entry → resolveReportJob() raises IllegalStateException (a
        // server-side configuration gap, surfaced as HTTP 500), rather than letting an NPE escape.
        ReportService service = new ReportService(reportJobSubmitter, Map.of(), dateValidationService);

        assertThatThrownBy(() -> service.submitReport(new ReportRequest("MONTHLY", null, null)))
                .isInstanceOf(IllegalStateException.class);

        verify(reportJobSubmitter, never()).submit(any(), any());
    }

    // =========================================================================================
    // Defensive request guards (align to the real class: all raise ValidationException)
    // =========================================================================================

    @Test
    @DisplayName("Null request throws ValidationException and never launches")
    void nullRequest_throwsValidationException() throws Exception {
        ReportService service = serviceWithJob();

        assertThatThrownBy(() -> service.submitReport(null))
                .isInstanceOf(ValidationException.class);

        verify(reportJobSubmitter, never()).submit(any(), any());
    }

    @Test
    @DisplayName("Null report type throws ValidationException and never launches")
    void nullReportType_throwsValidationException() throws Exception {
        ReportService service = serviceWithJob();

        assertThatThrownBy(() -> service.submitReport(new ReportRequest(null, null, null)))
                .isInstanceOf(ValidationException.class);

        verify(reportJobSubmitter, never()).submit(any(), any());
    }

    @Test
    @DisplayName("Unknown report type throws ValidationException and never launches")
    void unknownReportType_throwsValidationException() throws Exception {
        ReportService service = serviceWithJob();

        assertThatThrownBy(() -> service.submitReport(new ReportRequest("WEEKLY", null, null)))
                .isInstanceOf(ValidationException.class);

        verify(reportJobSubmitter, never()).submit(any(), any());
    }
}
