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

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.aws.carddemo.repository.TransactionRepository;
import com.aws.carddemo.service.ReportService.ReportRequest;
import com.aws.carddemo.service.ReportService.ReportResult;
import com.aws.carddemo.service.ReportService.ReportType;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneOffset;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.JobExecution;
import org.springframework.batch.core.JobParameters;
import org.springframework.batch.core.launch.JobLauncher;
import org.springframework.batch.core.repository.JobRestartException;

/**
 * Fast, deterministic unit tests for {@link ReportService} &mdash; the Java re-platform of the
 * CardDemo COBOL online program {@code CORPT00C} (CICS transaction {@code CR00}, BMS map
 * {@code CORPT0A}). The service validates a transaction-report request, derives the reporting
 * range for the Monthly / Yearly / Custom report types, runs the {@code SUBMIT-JOB-TO-INTRDR}
 * confirmation gate, and (only on a {@code "Y"} confirmation) launches the report batch job.
 *
 * <h2>Why this class exists (QA MAJOR &mdash; coverage-integrity)</h2>
 * <p>The online test tranche previously shipped <strong>no</strong> {@code ReportServiceTest}, so
 * {@code ReportService} was ~7% covered and the controller slice mocked it away entirely &mdash; a
 * broken report method could not be detected by any runtime test, and the bundle-level JaCoCo gate
 * masked the gap. These tests exercise the real service logic directly (mocking only its
 * out-of-process collaborators) so every caller-visible outcome is asserted against the verbatim
 * legacy message literals: the "select a report type" fall-through, each report type's range and
 * confirmation prompt, the full field-by-field custom-date edit ({@code validateCustomParts},
 * first-failure-wins), the quoted invalid-confirmation rejection, the "N" cancel, the
 * "submitted for printing" success, and the controlled submit-failure message.</p>
 *
 * <h2>Harness</h2>
 * <p>This is a plain Mockito unit test (no Spring context, no database, no Docker). The
 * {@link DateValidationService} collaborator is the <em>real</em> component (it is dependency-free
 * and pure), so the calendar-validity edits are exercised for real; only the out-of-process
 * collaborators are mocked: the {@link TransactionRepository} (diagnostic count only), the
 * {@link JobLauncher}, and the {@code transactionReportJob} {@link Job}. The service is constructed
 * through its package-private {@link Clock}-accepting constructor with a <strong>fixed</strong>
 * clock pinned to {@code 2026-07-16}, so the Monthly and Yearly ranges are deterministic and never
 * depend on the wall clock (QA MINOR &mdash; wall-clock coupling).</p>
 */
@DisplayName("ReportService — CORPT00C transaction-report request/confirmation/submission logic")
class ReportServiceTest {

    /** Fixed "today" for every derived range, so Monthly/Yearly assertions are deterministic. */
    private static final LocalDate FIXED_TODAY = LocalDate.of(2026, 7, 16);

    /** A clock pinned to {@link #FIXED_TODAY} at UTC midnight (used by monthly/yearly ranges). */
    private static final Clock FIXED_CLOCK =
            Clock.fixed(FIXED_TODAY.atStartOfDay(ZoneOffset.UTC).toInstant(), ZoneOffset.UTC);

    private TransactionRepository transactionRepository;
    private JobLauncher jobLauncher;
    private Job reportJob;
    private ReportService service;

    /**
     * Builds the service under test with the real {@link DateValidationService}, mocked
     * out-of-process collaborators, and the fixed clock. The diagnostic transaction count is
     * stubbed defensively because {@code submit()} reads it under debug logging.
     */
    @BeforeEach
    void setUp() {
        transactionRepository = mock(TransactionRepository.class);
        jobLauncher = mock(JobLauncher.class);
        reportJob = mock(Job.class);
        when(transactionRepository.count()).thenReturn(0L);
        service = new ReportService(
                new DateValidationService(), transactionRepository, jobLauncher, reportJob, FIXED_CLOCK);
    }

    /** Stubs a successful batch launch returning a COMPLETED execution. */
    private void stubLaunchSucceeds() throws Exception {
        JobExecution execution = mock(JobExecution.class);
        when(execution.getStatus()).thenReturn(BatchStatus.COMPLETED);
        when(jobLauncher.run(any(Job.class), any(JobParameters.class))).thenReturn(execution);
    }

    // ------------------------------------------------------------------------------------
    // Report-type dispatch (PROCESS-ENTER-KEY EVALUATE)
    // ------------------------------------------------------------------------------------

    @Test
    @DisplayName("null request -> 'Select a report type to print report...' (WHEN OTHER)")
    void nullRequestPromptsForType() {
        ReportResult result = service.requestReport(null);
        assertThat(result.submitted()).isFalse();
        assertThat(result.message()).isEqualTo("Select a report type to print report...");
    }

    @Test
    @DisplayName("null report type -> 'Select a report type to print report...' (WHEN OTHER)")
    void nullTypePromptsForType() {
        ReportResult result = service.requestReport(new ReportRequest(null, null, null, ""));
        assertThat(result.submitted()).isFalse();
        assertThat(result.message()).isEqualTo("Select a report type to print report...");
    }

    // ------------------------------------------------------------------------------------
    // Monthly report (range derived from the fixed clock)
    // ------------------------------------------------------------------------------------

    @Nested
    @DisplayName("Monthly report")
    class Monthly {

        @Test
        @DisplayName("blank confirmation prompts to confirm the Monthly report")
        void blankConfirmPrompts() {
            ReportResult result =
                    service.requestReport(new ReportRequest(ReportType.MONTHLY, null, null, ""));
            assertThat(result.submitted()).isFalse();
            assertThat(result.message()).isEqualTo("Please confirm to print the Monthly report...");
        }

        @Test
        @DisplayName("'N' confirmation cancels without submitting and clears the message")
        void cancelClears() {
            ReportResult result =
                    service.requestReport(new ReportRequest(ReportType.MONTHLY, null, null, "n"));
            assertThat(result.submitted()).isFalse();
            assertThat(result.message()).isEmpty();
            verifyNoInteractions(jobLauncher);
        }

        @Test
        @DisplayName("an unrecognized confirmation value is rejected, quoting the offending value")
        void invalidConfirmRejected() {
            ReportResult result =
                    service.requestReport(new ReportRequest(ReportType.MONTHLY, null, null, "x"));
            assertThat(result.submitted()).isFalse();
            assertThat(result.message()).isEqualTo("\"x\" is not a valid value to confirm...");
        }

        @Test
        @DisplayName("'Y' submits the job for the current-month range derived from the fixed clock")
        void confirmSubmitsCurrentMonthRange() throws Exception {
            stubLaunchSucceeds();

            ReportResult result =
                    service.requestReport(new ReportRequest(ReportType.MONTHLY, null, null, "Y"));

            assertThat(result.submitted()).isTrue();
            assertThat(result.message()).isEqualTo("Monthly report submitted for printing ...");

            ArgumentCaptor<JobParameters> params = ArgumentCaptor.forClass(JobParameters.class);
            verify(jobLauncher).run(eq(reportJob), params.capture());
            assertThat(params.getValue().getString("startDate")).isEqualTo("2026-07-01");
            assertThat(params.getValue().getString("endDate")).isEqualTo("2026-07-31");
            assertThat(params.getValue().getString("reportType")).isEqualTo("Monthly");
        }
    }

    // ------------------------------------------------------------------------------------
    // Yearly report (range derived from the fixed clock)
    // ------------------------------------------------------------------------------------

    @Nested
    @DisplayName("Yearly report")
    class Yearly {

        @Test
        @DisplayName("blank confirmation prompts to confirm the Yearly report")
        void blankConfirmPrompts() {
            ReportResult result =
                    service.requestReport(new ReportRequest(ReportType.YEARLY, null, null, ""));
            assertThat(result.submitted()).isFalse();
            assertThat(result.message()).isEqualTo("Please confirm to print the Yearly report...");
        }

        @Test
        @DisplayName("'Y' submits the job for the current-year range derived from the fixed clock")
        void confirmSubmitsCurrentYearRange() throws Exception {
            stubLaunchSucceeds();

            ReportResult result =
                    service.requestReport(new ReportRequest(ReportType.YEARLY, null, null, "y"));

            assertThat(result.submitted()).isTrue();
            assertThat(result.message()).isEqualTo("Yearly report submitted for printing ...");

            ArgumentCaptor<JobParameters> params = ArgumentCaptor.forClass(JobParameters.class);
            verify(jobLauncher).run(eq(reportJob), params.capture());
            assertThat(params.getValue().getString("startDate")).isEqualTo("2026-01-01");
            assertThat(params.getValue().getString("endDate")).isEqualTo("2026-12-31");
            assertThat(params.getValue().getString("reportType")).isEqualTo("Yearly");
        }
    }

    // ------------------------------------------------------------------------------------
    // Custom report — validateCustomParts (first-failure-wins, exact COBOL order)
    // ------------------------------------------------------------------------------------

    @Nested
    @DisplayName("Custom report — field-by-field date validation (first failure wins)")
    class CustomValidation {

        private ReportResult custom(String start, String end) {
            return service.requestReport(new ReportRequest(ReportType.CUSTOM, start, end, ""));
        }

        @Test
        @DisplayName("start month empty")
        void startMonthEmpty() {
            assertThat(custom("2026--10", "2026-07-31").message())
                    .isEqualTo("Start Date - Month can NOT be empty...");
        }

        @Test
        @DisplayName("start day empty")
        void startDayEmpty() {
            assertThat(custom("2026-07-", "2026-07-31").message())
                    .isEqualTo("Start Date - Day can NOT be empty...");
        }

        @Test
        @DisplayName("start year empty")
        void startYearEmpty() {
            assertThat(custom("-07-10", "2026-07-31").message())
                    .isEqualTo("Start Date - Year can NOT be empty...");
        }

        @Test
        @DisplayName("end month empty")
        void endMonthEmpty() {
            assertThat(custom("2026-07-10", "2026--31").message())
                    .isEqualTo("End Date - Month can NOT be empty...");
        }

        @Test
        @DisplayName("end day empty")
        void endDayEmpty() {
            assertThat(custom("2026-07-10", "2026-07-").message())
                    .isEqualTo("End Date - Day can NOT be empty...");
        }

        @Test
        @DisplayName("end year empty")
        void endYearEmpty() {
            assertThat(custom("2026-07-10", "-07-31").message())
                    .isEqualTo("End Date - Year can NOT be empty...");
        }

        @Test
        @DisplayName("start month not numeric / > 12")
        void startMonthInvalid() {
            assertThat(custom("2026-13-10", "2026-07-31").message())
                    .isEqualTo("Start Date - Not a valid Month...");
        }

        @Test
        @DisplayName("start day not numeric / > 31")
        void startDayInvalid() {
            assertThat(custom("2026-07-32", "2026-07-31").message())
                    .isEqualTo("Start Date - Not a valid Day...");
        }

        @Test
        @DisplayName("start year not numeric")
        void startYearInvalid() {
            assertThat(custom("20X6-07-10", "2026-07-31").message())
                    .isEqualTo("Start Date - Not a valid Year...");
        }

        @Test
        @DisplayName("end month not numeric / > 12")
        void endMonthInvalid() {
            assertThat(custom("2026-07-10", "2026-13-31").message())
                    .isEqualTo("End Date - Not a valid Month...");
        }

        @Test
        @DisplayName("end day not numeric / > 31")
        void endDayInvalid() {
            assertThat(custom("2026-07-10", "2026-07-32").message())
                    .isEqualTo("End Date - Not a valid Day...");
        }

        @Test
        @DisplayName("end year not numeric")
        void endYearInvalid() {
            assertThat(custom("2026-07-10", "20X6-07-31").message())
                    .isEqualTo("End Date - Not a valid Year...");
        }

        @Test
        @DisplayName("start not a real calendar date (Feb 30) -> 'Start Date - Not a valid date...'")
        void startDateNotACalendarDate() {
            assertThat(custom("2026-02-30", "2026-07-31").message())
                    .isEqualTo("Start Date - Not a valid date...");
        }

        @Test
        @DisplayName("end not a real calendar date (Feb 30) -> 'End Date - Not a valid date...'")
        void endDateNotACalendarDate() {
            assertThat(custom("2026-07-10", "2026-02-30").message())
                    .isEqualTo("End Date - Not a valid date...");
        }

        @Test
        @DisplayName("valid custom range, blank confirmation -> prompt to confirm the Custom report")
        void validRangeBlankConfirmPrompts() {
            ReportResult result = custom("2026-07-01", "2026-07-31");
            assertThat(result.submitted()).isFalse();
            assertThat(result.message()).isEqualTo("Please confirm to print the Custom report...");
        }
    }

    // ------------------------------------------------------------------------------------
    // Custom report — submission (normalized range) and submit-failure handling
    // ------------------------------------------------------------------------------------

    @Test
    @DisplayName("valid custom range with 'Y' submits the normalized operator-supplied range")
    void customConfirmSubmitsOperatorRange() throws Exception {
        stubLaunchSucceeds();

        ReportResult result = service.requestReport(
                new ReportRequest(ReportType.CUSTOM, "2026-03-05", "2026-04-06", "Y"));

        assertThat(result.submitted()).isTrue();
        assertThat(result.message()).isEqualTo("Custom report submitted for printing ...");

        ArgumentCaptor<JobParameters> params = ArgumentCaptor.forClass(JobParameters.class);
        verify(jobLauncher).run(eq(reportJob), params.capture());
        assertThat(params.getValue().getString("startDate")).isEqualTo("2026-03-05");
        assertThat(params.getValue().getString("endDate")).isEqualTo("2026-04-06");
        assertThat(params.getValue().getString("reportType")).isEqualTo("Custom");
    }

    @Test
    @DisplayName("a batch-launch failure is translated to a controlled message (no stack leak)")
    void submitFailureIsTranslated() throws Exception {
        when(jobLauncher.run(any(Job.class), any(JobParameters.class)))
                .thenThrow(new JobRestartException("simulated launch failure"));

        ReportResult result =
                service.requestReport(new ReportRequest(ReportType.MONTHLY, null, null, "Y"));

        assertThat(result.submitted()).isFalse();
        assertThat(result.message()).isEqualTo("Unable to submit the Monthly report for printing ...");
    }
}
