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
package com.carddemo.reporting;

import com.carddemo.common.dto.ReportRequestDto;
import com.carddemo.common.dto.ReportResponseDto;
import com.carddemo.common.exception.CardDemoException;
import com.carddemo.reporting.config.JobSchedulingConfig;
import com.carddemo.reporting.mapper.ReportMapper;
import com.carddemo.reporting.service.ReportService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.temporal.TemporalAdjusters;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * :purpose: Pure Mockito JUnit 5 unit test for {@link ReportService}, the
 *   re-platform of the online report-request program ``CORPT00C`` (CICS
 *   transaction ``CR00``). Pins the FROZEN user-facing message literals,
 *   report-type resolution (``Monthly`` -> ``Yearly`` -> ``Custom`` -> none),
 *   the twelve-step custom date validation (six empty checks, six
 *   component-validity checks, two impossible-date checks), the confirmation
 *   gate (``Y``/``y`` submit, ``N``/``n`` reset, blank prompt, invalid value),
 *   the fire-and-forget statement-generation launch with its FROZEN argument
 *   order and derived date range, and {@link CardDemoException} propagation.
 * :output: JUnit 5 assertions executed under Surefire with Mockito mocks only;
 *   no Spring application context, no Testcontainers harness and no database are
 *   involved. Only the two constructor collaborators
 *   ({@link JobSchedulingConfig}, {@link ReportMapper}) are mocked; the static
 *   {@code com.carddemo.common.util.DateUtil} runs for real so the impossible-date
 *   scenarios exercise genuine strict-calendar validation.
 */
@ExtendWith(MockitoExtension.class)
class ReportServiceTest {

    /** :purpose: FROZEN ``uuuu-MM-dd`` formatter mirroring the service's computed range. */
    private static final DateTimeFormatter ISO = DateTimeFormatter.ofPattern("uuuu-MM-dd");

    /** :purpose: Valid custom start-month component reused as the isolation baseline. */
    private static final String VALID_START_MONTH = "03";
    /** :purpose: Valid custom start-day component reused as the isolation baseline. */
    private static final String VALID_START_DAY = "15";
    /** :purpose: Valid custom start-year component reused as the isolation baseline. */
    private static final String VALID_START_YEAR = "2024";
    /** :purpose: Valid custom end-month component reused as the isolation baseline. */
    private static final String VALID_END_MONTH = "03";
    /** :purpose: Valid custom end-day component reused as the isolation baseline. */
    private static final String VALID_END_DAY = "20";
    /** :purpose: Valid custom end-year component reused as the isolation baseline. */
    private static final String VALID_END_YEAR = "2024";

    /** :purpose: Mocked non-blocking statement-generation job launcher. */
    @Mock
    private JobSchedulingConfig jobSchedulingConfig;

    /** :purpose: Mocked request/response field-echo mapper. */
    @Mock
    private ReportMapper reportMapper;

    /** :purpose: Service under test, constructor-injected with the two mocks above. */
    @InjectMocks
    private ReportService reportService;

    /** :purpose: Real response instance the mocked mapper returns for the service to mutate. */
    private ReportResponseDto responseDto;

    /**
     * :purpose: Wire the base-response builder so every control-flow path has a
     *   real {@link ReportResponseDto} to carry the outcome message. This stub is
     *   exercised by every scenario because ``requestReport`` calls the mapper
     *   before any branching.
     */
    @BeforeEach
    void setUp() {
        responseDto = new ReportResponseDto();
        when(reportMapper.toResponse(any(ReportRequestDto.class))).thenReturn(responseDto);
    }

    /**
     * :purpose: Stub the fire-and-forget launch to a completed future so submit
     *   scenarios proceed without blocking.
     */
    private void stubLaunchCompleted() {
        when(jobSchedulingConfig.launchStatementGeneration(any(), any(), any()))
                .thenReturn(CompletableFuture.completedFuture(null));
    }

    /**
     * :purpose: Build a request with the MONTHLY selector set and the supplied
     *   confirmation flag; all other selectors and date parts remain unset.
     * :param confirm: the confirmation flag value (``null`` represents a blank field).
     * :returns: a MONTHLY report request.
     */
    private ReportRequestDto monthlyRequest(String confirm) {
        ReportRequestDto request = new ReportRequestDto();
        request.setMonthly("Y");
        request.setConfirm(confirm);
        return request;
    }

    /**
     * :purpose: Build a request with the YEARLY selector set and the supplied
     *   confirmation flag; all other selectors and date parts remain unset.
     * :param confirm: the confirmation flag value (``null`` represents a blank field).
     * :returns: a YEARLY report request.
     */
    private ReportRequestDto yearlyRequest(String confirm) {
        ReportRequestDto request = new ReportRequestDto();
        request.setYearly("Y");
        request.setConfirm(confirm);
        return request;
    }

    /**
     * :purpose: Build a request with the CUSTOM selector set, the supplied start
     *   and end date parts, and the supplied confirmation flag.
     * :param startMonth: custom start-date month component.
     * :param startDay: custom start-date day component.
     * :param startYear: custom start-date year component.
     * :param endMonth: custom end-date month component.
     * :param endDay: custom end-date day component.
     * :param endYear: custom end-date year component.
     * :param confirm: the confirmation flag value.
     * :returns: a CUSTOM report request populated with the supplied components.
     */
    private ReportRequestDto customRequest(String startMonth, String startDay, String startYear,
                                           String endMonth, String endDay, String endYear,
                                           String confirm) {
        ReportRequestDto request = new ReportRequestDto();
        request.setCustom("Y");
        request.setStartDateMonth(startMonth);
        request.setStartDateDay(startDay);
        request.setStartDateYear(startYear);
        request.setEndDateMonth(endMonth);
        request.setEndDateDay(endDay);
        request.setEndDateYear(endYear);
        request.setConfirm(confirm);
        return request;
    }

    /**
     * :purpose: Build a request with no report-type selector set (all blank).
     * :returns: a request that resolves to no report type.
     */
    private ReportRequestDto emptyTypeRequest() {
        return new ReportRequestDto();
    }

    /**
     * :purpose: Assert that the statement-generation job was never launched.
     */
    private void verifyNoLaunch() {
        verify(jobSchedulingConfig, never()).launchStatementGeneration(any(), any(), any());
    }

    /**
     * :purpose: With no report-type selector set, the service redisplays the
     *   report-type prompt and launches nothing.
     */
    @Test
    void noReportTypeSelected() {
        ReportResponseDto result = reportService.requestReport(emptyTypeRequest());

        assertThat(result.getErrorMessage()).isEqualTo("Select a report type to print report...");
        verifyNoLaunch();
    }

    /**
     * :purpose: A Monthly request with a blank confirmation flag redisplays the
     *   confirmation prompt naming the Monthly report and launches nothing.
     */
    @Test
    void monthlyBlankConfirm() {
        ReportResponseDto result = reportService.requestReport(monthlyRequest(""));

        assertThat(result.getErrorMessage()).isEqualTo("Please confirm to print the Monthly report...");
        verifyNoLaunch();
    }

    /**
     * :purpose: A confirmed (``Y``) Monthly request launches the generation job
     *   with report name ``Monthly`` and the current-month first/last day range,
     *   then reports the submission success message.
     */
    @Test
    void monthlyConfirmY_submits() {
        stubLaunchCompleted();
        LocalDate now = LocalDate.now();
        String expectedStart = now.withDayOfMonth(1).format(ISO);
        String expectedEnd = now.with(TemporalAdjusters.lastDayOfMonth()).format(ISO);

        ReportResponseDto result = reportService.requestReport(monthlyRequest("Y"));

        verify(jobSchedulingConfig).launchStatementGeneration("Monthly", expectedStart, expectedEnd);
        assertThat(result.getErrorMessage()).isEqualTo("Monthly report submitted for printing ...");
    }

    /**
     * :purpose: A lowercase ``y`` confirmation is treated as a submit for a
     *   Monthly request: the job is launched once and the success message returned.
     */
    @Test
    void monthlyConfirmLowercaseY_submits() {
        stubLaunchCompleted();
        LocalDate now = LocalDate.now();
        String expectedStart = now.withDayOfMonth(1).format(ISO);
        String expectedEnd = now.with(TemporalAdjusters.lastDayOfMonth()).format(ISO);

        ReportResponseDto result = reportService.requestReport(monthlyRequest("y"));

        verify(jobSchedulingConfig).launchStatementGeneration("Monthly", expectedStart, expectedEnd);
        assertThat(result.getErrorMessage()).isEqualTo("Monthly report submitted for printing ...");
    }

    /**
     * :purpose: An ``N`` confirmation resets the screen (clearing the outcome
     *   message to blank) and launches nothing.
     */
    @Test
    void monthlyConfirmN_doesNotLaunch() {
        responseDto.setErrorMessage("SHOULD BE CLEARED");

        ReportResponseDto result = reportService.requestReport(monthlyRequest("N"));

        assertThat(result.getErrorMessage()).isNull();
        verifyNoLaunch();
    }

    /**
     * :purpose: A lowercase ``n`` confirmation behaves as ``N``: the screen resets
     *   with a blank message and nothing is launched.
     */
    @Test
    void monthlyConfirmLowercaseN_doesNotLaunch() {
        responseDto.setErrorMessage("SHOULD BE CLEARED");

        ReportResponseDto result = reportService.requestReport(monthlyRequest("n"));

        assertThat(result.getErrorMessage()).isNull();
        verifyNoLaunch();
    }

    /**
     * :purpose: An unrecognized confirmation value is echoed inside double quotes
     *   in the invalid-value message and launches nothing.
     */
    @Test
    void monthlyInvalidConfirm() {
        ReportResponseDto result = reportService.requestReport(monthlyRequest("X"));

        assertThat(result.getErrorMessage()).isEqualTo("\"X\" is not a valid value to confirm...");
        verifyNoLaunch();
    }

    /**
     * :purpose: A confirmed (``Y``) Yearly request launches the generation job
     *   with report name ``Yearly`` and the current calendar-year range, then
     *   reports the submission success message.
     */
    @Test
    void yearlyConfirmY_submits() {
        stubLaunchCompleted();
        int yr = LocalDate.now().getYear();

        ReportResponseDto result = reportService.requestReport(yearlyRequest("Y"));

        verify(jobSchedulingConfig).launchStatementGeneration(
                "Yearly", String.format("%04d-01-01", yr), String.format("%04d-12-31", yr));
        assertThat(result.getErrorMessage()).isEqualTo("Yearly report submitted for printing ...");
    }

    /**
     * :purpose: A Yearly request with a blank confirmation flag redisplays the
     *   confirmation prompt naming the Yearly report and launches nothing.
     */
    @Test
    void yearlyBlankConfirm() {
        ReportResponseDto result = reportService.requestReport(yearlyRequest(""));

        assertThat(result.getErrorMessage()).isEqualTo("Please confirm to print the Yearly report...");
        verifyNoLaunch();
    }

    /**
     * :purpose: A confirmed (``Y``) Custom request with a fully valid window
     *   launches the generation job with report name ``Custom`` and the entered
     *   dates formatted as ``YYYY-MM-DD``, then reports submission success.
     */
    @Test
    void customAllValidConfirmY_submits() {
        stubLaunchCompleted();

        ReportResponseDto result = reportService.requestReport(
                customRequest("03", "01", "2024", "03", "31", "2024", "Y"));

        verify(jobSchedulingConfig).launchStatementGeneration("Custom", "2024-03-01", "2024-03-31");
        assertThat(result.getErrorMessage()).isEqualTo("Custom report submitted for printing ...");
    }

    /**
     * :purpose: A Custom request with a blank start-date month is rejected with the
     *   empty-start-month message before the confirmation gate; nothing launches.
     */
    @Test
    void customEmptyStartMonth() {
        ReportResponseDto result = reportService.requestReport(
                customRequest("", VALID_START_DAY, VALID_START_YEAR,
                        VALID_END_MONTH, VALID_END_DAY, VALID_END_YEAR, "Y"));

        assertThat(result.getErrorMessage()).isEqualTo("Start Date - Month can NOT be empty...");
        verifyNoLaunch();
    }

    /**
     * :purpose: A Custom request with a blank start-date day is rejected with the
     *   empty-start-day message; nothing launches.
     */
    @Test
    void customEmptyStartDay() {
        ReportResponseDto result = reportService.requestReport(
                customRequest(VALID_START_MONTH, "", VALID_START_YEAR,
                        VALID_END_MONTH, VALID_END_DAY, VALID_END_YEAR, "Y"));

        assertThat(result.getErrorMessage()).isEqualTo("Start Date - Day can NOT be empty...");
        verifyNoLaunch();
    }

    /**
     * :purpose: A Custom request with a blank start-date year is rejected with the
     *   empty-start-year message; nothing launches.
     */
    @Test
    void customEmptyStartYear() {
        ReportResponseDto result = reportService.requestReport(
                customRequest(VALID_START_MONTH, VALID_START_DAY, "",
                        VALID_END_MONTH, VALID_END_DAY, VALID_END_YEAR, "Y"));

        assertThat(result.getErrorMessage()).isEqualTo("Start Date - Year can NOT be empty...");
        verifyNoLaunch();
    }

    /**
     * :purpose: A Custom request with a blank end-date month is rejected with the
     *   empty-end-month message; nothing launches.
     */
    @Test
    void customEmptyEndMonth() {
        ReportResponseDto result = reportService.requestReport(
                customRequest(VALID_START_MONTH, VALID_START_DAY, VALID_START_YEAR,
                        "", VALID_END_DAY, VALID_END_YEAR, "Y"));

        assertThat(result.getErrorMessage()).isEqualTo("End Date - Month can NOT be empty...");
        verifyNoLaunch();
    }

    /**
     * :purpose: A Custom request with a blank end-date day is rejected with the
     *   empty-end-day message; nothing launches.
     */
    @Test
    void customEmptyEndDay() {
        ReportResponseDto result = reportService.requestReport(
                customRequest(VALID_START_MONTH, VALID_START_DAY, VALID_START_YEAR,
                        VALID_END_MONTH, "", VALID_END_YEAR, "Y"));

        assertThat(result.getErrorMessage()).isEqualTo("End Date - Day can NOT be empty...");
        verifyNoLaunch();
    }

    /**
     * :purpose: A Custom request with a blank end-date year is rejected with the
     *   empty-end-year message; nothing launches.
     */
    @Test
    void customEmptyEndYear() {
        ReportResponseDto result = reportService.requestReport(
                customRequest(VALID_START_MONTH, VALID_START_DAY, VALID_START_YEAR,
                        VALID_END_MONTH, VALID_END_DAY, "", "Y"));

        assertThat(result.getErrorMessage()).isEqualTo("End Date - Year can NOT be empty...");
        verifyNoLaunch();
    }

    /**
     * :purpose: A start-date month above the upper bound (``13`` > ``12``) yields
     *   the invalid-start-month message; nothing launches.
     */
    @Test
    void customInvalidStartMonth() {
        ReportResponseDto result = reportService.requestReport(
                customRequest("13", VALID_START_DAY, VALID_START_YEAR,
                        VALID_END_MONTH, VALID_END_DAY, VALID_END_YEAR, "Y"));

        assertThat(result.getErrorMessage()).isEqualTo("Start Date - Not a valid Month...");
        verifyNoLaunch();
    }

    /**
     * :purpose: A start-date day above the upper bound (``32`` > ``31``) yields the
     *   invalid-start-day message; nothing launches.
     */
    @Test
    void customInvalidStartDay() {
        ReportResponseDto result = reportService.requestReport(
                customRequest(VALID_START_MONTH, "32", VALID_START_YEAR,
                        VALID_END_MONTH, VALID_END_DAY, VALID_END_YEAR, "Y"));

        assertThat(result.getErrorMessage()).isEqualTo("Start Date - Not a valid Day...");
        verifyNoLaunch();
    }

    /**
     * :purpose: The custom year carries no numeric range bound in the service, so a
     *   non-numeric start-date year (``20X4``) is the sole invalid-start-year
     *   trigger; it yields the invalid-start-year message and nothing launches.
     */
    @Test
    void customInvalidStartYear() {
        ReportResponseDto result = reportService.requestReport(
                customRequest(VALID_START_MONTH, VALID_START_DAY, "20X4",
                        VALID_END_MONTH, VALID_END_DAY, VALID_END_YEAR, "Y"));

        assertThat(result.getErrorMessage()).isEqualTo("Start Date - Not a valid Year...");
        verifyNoLaunch();
    }

    /**
     * :purpose: An end-date month above the upper bound (``13`` > ``12``) yields the
     *   invalid-end-month message; nothing launches.
     */
    @Test
    void customInvalidEndMonth() {
        ReportResponseDto result = reportService.requestReport(
                customRequest(VALID_START_MONTH, VALID_START_DAY, VALID_START_YEAR,
                        "13", VALID_END_DAY, VALID_END_YEAR, "Y"));

        assertThat(result.getErrorMessage()).isEqualTo("End Date - Not a valid Month...");
        verifyNoLaunch();
    }

    /**
     * :purpose: An end-date day above the upper bound (``32`` > ``31``) yields the
     *   invalid-end-day message; nothing launches.
     */
    @Test
    void customInvalidEndDay() {
        ReportResponseDto result = reportService.requestReport(
                customRequest(VALID_START_MONTH, VALID_START_DAY, VALID_START_YEAR,
                        VALID_END_MONTH, "32", VALID_END_YEAR, "Y"));

        assertThat(result.getErrorMessage()).isEqualTo("End Date - Not a valid Day...");
        verifyNoLaunch();
    }

    /**
     * :purpose: A non-numeric end-date year (``20X4``) is the sole invalid-end-year
     *   trigger (the year has no numeric range bound); it yields the
     *   invalid-end-year message and nothing launches.
     */
    @Test
    void customInvalidEndYear() {
        ReportResponseDto result = reportService.requestReport(
                customRequest(VALID_START_MONTH, VALID_START_DAY, VALID_START_YEAR,
                        VALID_END_MONTH, VALID_END_DAY, "20X4", "Y"));

        assertThat(result.getErrorMessage()).isEqualTo("End Date - Not a valid Year...");
        verifyNoLaunch();
    }

    /**
     * :purpose: A start date whose components are all in range but which is not a
     *   real calendar date (``2024-02-30``) is rejected by the real DateUtil with
     *   the not-a-valid-start-date message; nothing launches.
     */
    @Test
    void customImpossibleStartDate() {
        ReportResponseDto result = reportService.requestReport(
                customRequest("02", "30", "2024",
                        VALID_END_MONTH, VALID_END_DAY, VALID_END_YEAR, "Y"));

        assertThat(result.getErrorMessage()).isEqualTo("Start Date - Not a valid date...");
        verifyNoLaunch();
    }

    /**
     * :purpose: An end date whose components are all in range but which is not a
     *   real calendar date (``2023-02-29``; 2023 is not a leap year) is rejected by
     *   the real DateUtil with the not-a-valid-end-date message; nothing launches.
     */
    @Test
    void customImpossibleEndDate() {
        ReportResponseDto result = reportService.requestReport(
                customRequest(VALID_START_MONTH, VALID_START_DAY, VALID_START_YEAR,
                        "02", "29", "2023", "Y"));

        assertThat(result.getErrorMessage()).isEqualTo("End Date - Not a valid date...");
        verifyNoLaunch();
    }

    /**
     * :purpose: When the job launcher fails, the resulting {@link CardDemoException}
     *   (carrying the frozen TDQ-write failure message) propagates unchanged out of
     *   ``requestReport``.
     */
    @Test
    void launchFailurePropagatesCardDemoException() {
        when(jobSchedulingConfig.launchStatementGeneration(any(), any(), any()))
                .thenThrow(new CardDemoException("Unable to Write TDQ (JOBS)..."));
        ReportRequestDto request = monthlyRequest("Y");

        assertThatThrownBy(() -> reportService.requestReport(request))
                .isInstanceOf(CardDemoException.class)
                .hasMessage("Unable to Write TDQ (JOBS)...");
    }

    /**
     * :purpose: The launch is fire-and-forget: even when the returned future never
     *   completes, ``requestReport`` returns promptly with the submission success
     *   message, proving the service never calls ``.get()`` / ``.join()`` on the
     *   future. The timeout guards against a regression that would block the caller.
     */
    @Test
    @Timeout(5)
    void requestDoesNotBlockOnJob() {
        when(jobSchedulingConfig.launchStatementGeneration(any(), any(), any()))
                .thenReturn(new CompletableFuture<>());

        ReportResponseDto result = reportService.requestReport(monthlyRequest("Y"));

        verify(jobSchedulingConfig).launchStatementGeneration(any(), any(), any());
        assertThat(result.getErrorMessage()).isEqualTo("Monthly report submitted for printing ...");
    }

}
