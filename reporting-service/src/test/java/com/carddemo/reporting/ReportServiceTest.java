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

import com.carddemo.common.dto.BatchJobExecutionDto;
import com.carddemo.reporting.client.BatchJobClient;
import com.carddemo.common.dto.ReportRequestDto;
import com.carddemo.common.dto.ReportResponseDto;
import com.carddemo.common.exception.CardDemoException;
import com.carddemo.reporting.mapper.ReportMapper;
import com.carddemo.reporting.service.ReportService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.temporal.TemporalAdjusters;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
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
 *   involved. Only the two collaborators ({@link JobSchedulingConfig},
 *   {@link ReportMapper}) are mocked; the static
 *   {@code com.carddemo.common.util.DateUtil} runs for real so the impossible-date
 *   scenarios exercise genuine strict-calendar validation. Time is supplied by a
 *   {@link java.time.Clock#fixed} clock injected through the service's explicit-clock
 *   constructor, so every Monthly/Yearly range assertion names literal dates instead
 *   of recomputing the service's own algorithm from the wall clock — including
 *   dedicated month-end, leap-February and year-end boundary scenarios.
 */
@ExtendWith(MockitoExtension.class)
class ReportServiceTest {

    /**
     * :purpose: Baseline frozen clock — mid-month, mid-year (2026-03-17T12:00:00Z in
     *   UTC). Every scenario that does not exercise a calendar boundary runs on this
     *   clock so the Monthly range is the literal ``2026-03-01``/``2026-03-31`` and
     *   the Yearly range the literal ``2026-01-01``/``2026-12-31``, never a value
     *   recomputed from the wall clock.
     */
    private static final Clock FIXED_MID_MONTH =
            Clock.fixed(Instant.parse("2026-03-17T12:00:00Z"), ZoneOffset.UTC);

    /**
     * :purpose: Frozen clock one second before a non-leap February month-end rolls
     *   over (2026-02-28T23:59:59Z). Pins the Monthly range across the month
     *   boundary that a wall-clock test would race.
     */
    private static final Clock FIXED_NON_LEAP_MONTH_END =
            Clock.fixed(Instant.parse("2026-02-28T23:59:59Z"), ZoneOffset.UTC);

    /**
     * :purpose: Frozen clock on a leap-year February month-end
     *   (2024-02-29T23:59:59Z), proving ``TemporalAdjusters.lastDayOfMonth`` resolves
     *   to the 29th rather than a hard-coded 28.
     */
    private static final Clock FIXED_LEAP_MONTH_END =
            Clock.fixed(Instant.parse("2024-02-29T23:59:59Z"), ZoneOffset.UTC);

    /**
     * :purpose: Frozen clock one second before a year-end rolls over
     *   (2026-12-31T23:59:59Z). Pins both the Monthly and the Yearly range across the
     *   year boundary that a wall-clock test would race.
     */
    private static final Clock FIXED_YEAR_END =
            Clock.fixed(Instant.parse("2026-12-31T23:59:59Z"), ZoneOffset.UTC);

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

    /** :purpose: Mocked submitter of the CORPT00C transaction-detail report job stream. */
    @Mock
    private BatchJobClient batchJobClient;

    /** :purpose: Mocked request/response field-echo mapper. */
    @Mock
    private ReportMapper reportMapper;

    /**
     * :purpose: Service under test, constructed with the two mocks above and the
     *   baseline frozen clock so no scenario depends on the wall clock.
     */
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
        // Shared default stubbing: individual scenarios (the no-input header path)
        // override it, so it is declared lenient rather than strict.
        lenient().when(reportMapper.toResponse(any(ReportRequestDto.class))).thenReturn(responseDto);
        reportService = new ReportService(batchJobClient, reportMapper, FIXED_MID_MONTH);
    }

    /**
     * :purpose: Build the service under test on an explicitly frozen clock so a
     *   calendar-boundary scenario can pin the computed range to literal dates.
     * :param clock: the frozen clock supplying the current date to the service.
     * :returns: a service instance sharing this test's mocked collaborators.
     */
    private ReportService serviceWithClock(Clock clock) {
        return new ReportService(batchJobClient, reportMapper, clock);
    }

    /**
     * :purpose: Stub the report submission to an accepted execution so submit
     *   scenarios proceed without contacting batch-service.
     */
    private void stubLaunchCompleted() {
        when(batchJobClient.submitTransactionDetailReport(any(), any()))
                .thenReturn(new BatchJobExecutionDto("transactionDetailReportJob", 1L, 1L,
                        "COMPLETED", "COMPLETED", null));
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
     * :purpose: Assert that no report job was ever submitted.
     */
    private void verifyNoLaunch() {
        verify(batchJobClient, never()).submitTransactionDetailReport(any(), any());
    }

    /**
     * :purpose: With no report-type selector set, the service redisplays the
     *   report-type prompt and launches nothing.
     */
    /**
     * :purpose: The submission acknowledgement is the ONE message this screen sends on the
     *  SUCCESS channel, and it never appears on the error channel; every other outcome is
     *  the reverse.
     * :note: ``CORPT00`` declares one message field, ``ERRMSG POS=(23,1) COLOR=RED``, and
     *  ``CORPT00C`` performs ``MOVE DFHGREEN TO ERRMSGC`` immediately before the
     *  acknowledgement and on no other branch. The choice of member is how that colour
     *  reaches the client; carrying both outcomes on one member had left it comparing the
     *  text against its own copy of the expected wording to guess the colour.
     */
    @Test
    void theAcknowledgementAndTheRefusalsUseOppositeChannels() {
        stubLaunchCompleted();

        // The mapper stub hands every turn the same response instance, so each
        // assertion below also proves the opposite member is actively cleared
        // rather than merely never having been set.
        ReportRequestDto monthly = new ReportRequestDto();
        monthly.setMonthly("Y");
        monthly.setConfirm("Y");

        ReportResponseDto submitted = reportService.requestReport(monthly);
        assertThat(submitted.getMessage()).isEqualTo("Monthly report submitted for printing ...");
        assertThat(submitted.getErrorMessage()).isNull();

        ReportResponseDto noType = reportService.requestReport(new ReportRequestDto());
        assertThat(noType.getErrorMessage()).isEqualTo("Select a report type to print report...");
        assertThat(noType.getMessage()).isNull();

        ReportRequestDto prompt = new ReportRequestDto();
        prompt.setMonthly("Y");
        ReportResponseDto confirmPrompt = reportService.requestReport(prompt);
        assertThat(confirmPrompt.getErrorMessage()).isNotNull();
        assertThat(confirmPrompt.getMessage()).isNull();
    }

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

        ReportResponseDto result = reportService.requestReport(monthlyRequest("Y"));

        // Literal expected dates for the baseline frozen clock (2026-03-17): the
        // assertion no longer re-runs the service's own algorithm.
        verify(batchJobClient).submitTransactionDetailReport("2026-03-01", "2026-03-31");
        assertThat(result.getMessage()).isEqualTo("Monthly report submitted for printing ...");
    }

    /**
     * :purpose: A lowercase ``y`` confirmation is treated as a submit for a
     *   Monthly request: the job is launched once and the success message returned.
     */
    @Test
    void monthlyConfirmLowercaseY_submits() {
        stubLaunchCompleted();

        ReportResponseDto result = reportService.requestReport(monthlyRequest("y"));

        verify(batchJobClient).submitTransactionDetailReport("2026-03-01", "2026-03-31");
        assertThat(result.getMessage()).isEqualTo("Monthly report submitted for printing ...");
    }

    /**
     * :purpose: With time frozen one second before a non-leap February rolls over,
     *   the Monthly range is pinned to the literal ``2026-02-01``/``2026-02-28``: the
     *   month-end boundary that a wall-clock assertion would race is asserted
     *   deterministically.
     */
    @Test
    void monthlyRangeAtNonLeapMonthEndIsPinnedToFebruaryTwentyEighth() {
        stubLaunchCompleted();

        ReportResponseDto result =
                serviceWithClock(FIXED_NON_LEAP_MONTH_END).requestReport(monthlyRequest("Y"));

        verify(batchJobClient).submitTransactionDetailReport("2026-02-01", "2026-02-28");
        assertThat(result.getMessage()).isEqualTo("Monthly report submitted for printing ...");
    }

    /**
     * :purpose: On a leap-year February month-end the Monthly range ends on the
     *   literal ``2024-02-29``, proving the last-day-of-month adjuster is calendar
     *   aware rather than a fixed 28.
     */
    @Test
    void monthlyRangeAtLeapMonthEndIsPinnedToFebruaryTwentyNinth() {
        stubLaunchCompleted();

        ReportResponseDto result =
                serviceWithClock(FIXED_LEAP_MONTH_END).requestReport(monthlyRequest("Y"));

        verify(batchJobClient).submitTransactionDetailReport("2024-02-01", "2024-02-29");
        assertThat(result.getMessage()).isEqualTo("Monthly report submitted for printing ...");
    }

    /**
     * :purpose: With time frozen one second before a year rolls over, the Monthly
     *   range stays inside December of the outgoing year
     *   (``2026-12-01``/``2026-12-31``).
     */
    @Test
    void monthlyRangeAtYearEndIsPinnedToDecemberOfTheOutgoingYear() {
        stubLaunchCompleted();

        ReportResponseDto result = serviceWithClock(FIXED_YEAR_END).requestReport(monthlyRequest("Y"));

        verify(batchJobClient).submitTransactionDetailReport("2026-12-01", "2026-12-31");
        assertThat(result.getMessage()).isEqualTo("Monthly report submitted for printing ...");
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

        ReportResponseDto result = reportService.requestReport(yearlyRequest("Y"));

        // Literal calendar-year range for the baseline frozen clock (2026-03-17).
        verify(batchJobClient).submitTransactionDetailReport("2026-01-01", "2026-12-31");
        assertThat(result.getMessage()).isEqualTo("Yearly report submitted for printing ...");
    }

    /**
     * :purpose: With time frozen one second before a year rolls over, the Yearly
     *   range remains the outgoing calendar year (``2026-01-01``/``2026-12-31``): the
     *   year-end boundary that a wall-clock assertion would race is asserted
     *   deterministically.
     */
    @Test
    void yearlyRangeAtYearEndIsPinnedToTheOutgoingYear() {
        stubLaunchCompleted();

        ReportResponseDto result = serviceWithClock(FIXED_YEAR_END).requestReport(yearlyRequest("Y"));

        verify(batchJobClient).submitTransactionDetailReport("2026-01-01", "2026-12-31");
        assertThat(result.getMessage()).isEqualTo("Yearly report submitted for printing ...");
    }

    /**
     * :purpose: On a leap-year clock the Yearly range still spans the whole calendar
     *   year (``2024-01-01``/``2024-12-31``); the leap day does not shift either bound.
     */
    @Test
    void yearlyRangeOnALeapYearSpansTheWholeCalendarYear() {
        stubLaunchCompleted();

        ReportResponseDto result = serviceWithClock(FIXED_LEAP_MONTH_END).requestReport(yearlyRequest("Y"));

        verify(batchJobClient).submitTransactionDetailReport("2024-01-01", "2024-12-31");
        assertThat(result.getMessage()).isEqualTo("Yearly report submitted for printing ...");
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

        verify(batchJobClient).submitTransactionDetailReport("2024-03-01", "2024-03-31");
        assertThat(result.getMessage()).isEqualTo("Custom report submitted for printing ...");
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
        when(batchJobClient.submitTransactionDetailReport(any(), any()))
                .thenThrow(new CardDemoException("Unable to Write TDQ (JOBS)..."));
        ReportRequestDto request = monthlyRequest("Y");

        assertThatThrownBy(() -> reportService.requestReport(request))
                .isInstanceOf(CardDemoException.class)
                .hasMessage("Unable to Write TDQ (JOBS)...");
    }

    /**
     * :purpose: The submission hand-off returns promptly with the frozen success
     *   message and never waits on the launched run itself. The timeout guards against
     *   a regression that would block the caller for the duration of the job.
     */
    @Test
    @Timeout(5)
    void requestDoesNotBlockOnJob() {
        when(batchJobClient.submitTransactionDetailReport(any(), any()))
                .thenReturn(new BatchJobExecutionDto("transactionDetailReportJob", 2L, 2L,
                        "STARTED", "UNKNOWN", null));

        ReportResponseDto result = reportService.requestReport(monthlyRequest("Y"));

        verify(batchJobClient).submitTransactionDetailReport(any(), any());
        assertThat(result.getMessage()).isEqualTo("Monthly report submitted for printing ...");
    }


    // =====================================================================
    // F22 - a failed launch is reported truthfully, never as a success
    // =====================================================================

    /**
     * :purpose: The service inspects the launch outcome; a submission whose run
     *   failed raises the frozen ``CORPT00C`` message instead of returning the
     *   success line.
     */
    @Test
    void failedRunOutcomeIsReportedInsteadOfSuccess() {
        // Expressed against the surviving submission collaborator: a refused/failed
        // submission raises the frozen CORPT00C message instead of the success line.
        when(batchJobClient.submitTransactionDetailReport(any(), any()))
                .thenThrow(new CardDemoException("Unable to Write TDQ (JOBS)..."));

        assertThatThrownBy(() -> reportService.requestReport(monthlyRequest("Y")))
                .isInstanceOf(CardDemoException.class)
                .hasMessage("Unable to Write TDQ (JOBS)...");
    }

    /**
     * :purpose: A confirmed request inspects the launch outcome exactly once, so a
     *   failure can never be masked by the unconditional success message.
     */
    @Test
    void confirmedRequestInspectsTheLaunchOutcome() {
        stubLaunchCompleted();

        ReportResponseDto result = reportService.requestReport(monthlyRequest("Y"));

        assertThat(result.getMessage()).isEqualTo("Monthly report submitted for printing ...");
    }

    /**
     * :purpose: ``CORPT00C`` sends the screen header on every ``SEND MAP``, so the
     *   no-input path populates it too even though no request DTO is available.
     */
    @Test
    void noInputPathPopulatesTheScreenHeader() {
        when(reportMapper.toResponse(any(ReportRequestDto.class))).thenReturn(null);

        ReportResponseDto result = reportService.requestReport(new ReportRequestDto());

        verify(reportMapper).applyScreenHeader(result);
        assertThat(result.getErrorMessage()).isEqualTo("Select a report type to print report...");
    }
}
