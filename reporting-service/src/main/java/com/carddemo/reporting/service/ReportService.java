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
package com.carddemo.reporting.service;

import com.carddemo.common.dto.ReportRequestDto;
import com.carddemo.common.dto.ReportResponseDto;
import com.carddemo.common.util.DateUtil;
import com.carddemo.reporting.config.JobSchedulingConfig;
import com.carddemo.reporting.mapper.ReportMapper;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.temporal.TemporalAdjusters;

/**
 * :purpose: Re-platformed business logic of CICS program ``CORPT00C`` (TRANID
 *   ``CR00``): resolves the requested report type, computes the report date
 *   range, validates a custom date window, applies the confirmation gate, and
 *   launches the statement/report generation job. Screen concerns
 *   (``SEND``/``RECEIVE MAP``, header population, PF-key dispatch) are handled by
 *   the controller and mapper; this service owns no data and performs no writes.
 * :output: A {@link ReportResponseDto} carrying the outcome message for every
 *   validation and confirmation redisplay, or the success message after a
 *   non-blocking job submission.
 */
@Service
public class ReportService {

    /** :purpose: Monthly report name (COBOL ``WS-REPORT-NAME`` value ``'Monthly'``). */
    private static final String REPORT_NAME_MONTHLY = "Monthly";

    /** :purpose: Yearly report name (COBOL ``WS-REPORT-NAME`` value ``'Yearly'``). */
    private static final String REPORT_NAME_YEARLY = "Yearly";

    /** :purpose: Custom report name (COBOL ``WS-REPORT-NAME`` value ``'Custom'``). */
    private static final String REPORT_NAME_CUSTOM = "Custom";

    /** :purpose: No-report-type-selected message. */
    private static final String MSG_SELECT_REPORT_TYPE = "Select a report type to print report...";

    /** :purpose: Empty start-date month message. */
    private static final String MSG_START_MONTH_EMPTY = "Start Date - Month can NOT be empty...";

    /** :purpose: Empty start-date day message. */
    private static final String MSG_START_DAY_EMPTY = "Start Date - Day can NOT be empty...";

    /** :purpose: Empty start-date year message. */
    private static final String MSG_START_YEAR_EMPTY = "Start Date - Year can NOT be empty...";

    /** :purpose: Empty end-date month message. */
    private static final String MSG_END_MONTH_EMPTY = "End Date - Month can NOT be empty...";

    /** :purpose: Empty end-date day message. */
    private static final String MSG_END_DAY_EMPTY = "End Date - Day can NOT be empty...";

    /** :purpose: Empty end-date year message. */
    private static final String MSG_END_YEAR_EMPTY = "End Date - Year can NOT be empty...";

    /** :purpose: Invalid start-date month message. */
    private static final String MSG_START_MONTH_INVALID = "Start Date - Not a valid Month...";

    /** :purpose: Invalid start-date day message. */
    private static final String MSG_START_DAY_INVALID = "Start Date - Not a valid Day...";

    /** :purpose: Invalid start-date year message. */
    private static final String MSG_START_YEAR_INVALID = "Start Date - Not a valid Year...";

    /** :purpose: Invalid end-date month message. */
    private static final String MSG_END_MONTH_INVALID = "End Date - Not a valid Month...";

    /** :purpose: Invalid end-date day message. */
    private static final String MSG_END_DAY_INVALID = "End Date - Not a valid Day...";

    /** :purpose: Invalid end-date year message. */
    private static final String MSG_END_YEAR_INVALID = "End Date - Not a valid Year...";

    /** :purpose: Unparseable start-date message (CSUTLDTC rejection). */
    private static final String MSG_START_DATE_INVALID = "Start Date - Not a valid date...";

    /** :purpose: Unparseable end-date message (CSUTLDTC rejection). */
    private static final String MSG_END_DATE_INVALID = "End Date - Not a valid date...";

    /** :purpose: Confirmation-prompt prefix (concatenated with the report name and suffix). */
    private static final String CONFIRM_PROMPT_PREFIX = "Please confirm to print the ";

    /** :purpose: Confirmation-prompt suffix (concatenated after the report name). */
    private static final String CONFIRM_PROMPT_SUFFIX = " report...";

    /** :purpose: Invalid-confirmation-value suffix (concatenated after the quoted value). */
    private static final String INVALID_CONFIRM_SUFFIX = "\" is not a valid value to confirm...";

    /** :purpose: Success-message suffix (concatenated after the report name). */
    private static final String SUBMIT_SUCCESS_SUFFIX = " report submitted for printing ...";

    /**
     * :purpose: CSUTLDTC message number the legacy program explicitly tolerates as
     *   valid (informational Language-Environment ``FC-UNSUPP-RANGE`` condition).
     */
    private static final int DATE_TOLERATED_MSG_NO = 2513;

    /** :purpose: Maximum accepted month value (COBOL ``> '12'`` range test). */
    private static final int MAX_MONTH = 12;

    /** :purpose: Maximum accepted day value (COBOL ``> '31'`` range test). */
    private static final int MAX_DAY = 31;

    /** :purpose: ISO ``YYYY-MM-DD`` wire-format formatter for the computed date range. */
    private static final DateTimeFormatter ISO_FORMATTER = DateTimeFormatter.ofPattern("uuuu-MM-dd");

    /** :purpose: Non-blocking launcher of the statement/report generation job. */
    private final JobSchedulingConfig jobSchedulingConfig;

    /** :purpose: Mapper that echoes the screen field contract onto the response. */
    private final ReportMapper reportMapper;

    /**
     * :purpose: Construct the report service with its job launcher and response mapper.
     * :param jobSchedulingConfig: the asynchronous statement-generation job launcher.
     * :param reportMapper: the report request/response field-echo mapper.
     */
    public ReportService(JobSchedulingConfig jobSchedulingConfig, ReportMapper reportMapper) {
        this.jobSchedulingConfig = jobSchedulingConfig;
        this.reportMapper = reportMapper;
    }

    /**
     * :purpose: Process a report request: resolve the report type, compute or
     *   validate the date range, apply the confirmation gate, and launch the
     *   generation job when confirmed, mirroring ``CORPT00C PROCESS-ENTER-KEY``.
     * :param request: the report-request screen fields (report-type selectors,
     *   custom start/end date parts, and the confirmation flag).
     * :returns: a {@link ReportResponseDto} whose message conveys a validation
     *   outcome, the confirmation prompt, or the submission success.
     * :raises com.carddemo.common.exception.CardDemoException: propagated unchanged
     *   when the job launch fails synchronously.
     */
    public ReportResponseDto requestReport(ReportRequestDto request) {
        ReportResponseDto response = reportMapper.toResponse(request);
        if (response == null) {
            response = new ReportResponseDto();
        }
        if (request == null) {
            return withMessage(response, MSG_SELECT_REPORT_TYPE);
        }

        if (isSelected(request.getMonthly())) {
            LocalDate today = LocalDate.now();
            String startDate = today.withDayOfMonth(1).format(ISO_FORMATTER);
            String endDate = today.with(TemporalAdjusters.lastDayOfMonth()).format(ISO_FORMATTER);
            return confirmAndLaunch(request, response, REPORT_NAME_MONTHLY, startDate, endDate);
        }
        if (isSelected(request.getYearly())) {
            int year = LocalDate.now().getYear();
            String startDate = LocalDate.of(year, 1, 1).format(ISO_FORMATTER);
            String endDate = LocalDate.of(year, 12, 31).format(ISO_FORMATTER);
            return confirmAndLaunch(request, response, REPORT_NAME_YEARLY, startDate, endDate);
        }
        if (isSelected(request.getCustom())) {
            return processCustomReport(request, response);
        }
        return withMessage(response, MSG_SELECT_REPORT_TYPE);
    }

    /**
     * :purpose: Validate the custom date window in ``CORPT00C`` order and, when
     *   valid, hand off to the confirmation gate with report name ``Custom``.
     * :param request: the report-request screen fields.
     * :param response: the response being assembled.
     * :returns: the response carrying the first validation failure, or the
     *   confirmation/launch outcome when the window is valid.
     */
    private ReportResponseDto processCustomReport(ReportRequestDto request, ReportResponseDto response) {
        String startMonth = request.getStartDateMonth();
        String startDay = request.getStartDateDay();
        String startYear = request.getStartDateYear();
        String endMonth = request.getEndDateMonth();
        String endDay = request.getEndDateDay();
        String endYear = request.getEndDateYear();

        if (isEmpty(startMonth)) {
            return withMessage(response, MSG_START_MONTH_EMPTY);
        }
        if (isEmpty(startDay)) {
            return withMessage(response, MSG_START_DAY_EMPTY);
        }
        if (isEmpty(startYear)) {
            return withMessage(response, MSG_START_YEAR_EMPTY);
        }
        if (isEmpty(endMonth)) {
            return withMessage(response, MSG_END_MONTH_EMPTY);
        }
        if (isEmpty(endDay)) {
            return withMessage(response, MSG_END_DAY_EMPTY);
        }
        if (isEmpty(endYear)) {
            return withMessage(response, MSG_END_YEAR_EMPTY);
        }

        Integer startMonthValue = parseNonNegativeInt(startMonth);
        if (startMonthValue == null || startMonthValue > MAX_MONTH) {
            return withMessage(response, MSG_START_MONTH_INVALID);
        }
        Integer startDayValue = parseNonNegativeInt(startDay);
        if (startDayValue == null || startDayValue > MAX_DAY) {
            return withMessage(response, MSG_START_DAY_INVALID);
        }
        Integer startYearValue = parseNonNegativeInt(startYear);
        if (startYearValue == null) {
            return withMessage(response, MSG_START_YEAR_INVALID);
        }
        Integer endMonthValue = parseNonNegativeInt(endMonth);
        if (endMonthValue == null || endMonthValue > MAX_MONTH) {
            return withMessage(response, MSG_END_MONTH_INVALID);
        }
        Integer endDayValue = parseNonNegativeInt(endDay);
        if (endDayValue == null || endDayValue > MAX_DAY) {
            return withMessage(response, MSG_END_DAY_INVALID);
        }
        Integer endYearValue = parseNonNegativeInt(endYear);
        if (endYearValue == null) {
            return withMessage(response, MSG_END_YEAR_INVALID);
        }

        String startDate = String.format("%04d-%02d-%02d", startYearValue, startMonthValue, startDayValue);
        String endDate = String.format("%04d-%02d-%02d", endYearValue, endMonthValue, endDayValue);

        if (!isDateAcceptable(startDate)) {
            return withMessage(response, MSG_START_DATE_INVALID);
        }
        if (!isDateAcceptable(endDate)) {
            return withMessage(response, MSG_END_DATE_INVALID);
        }

        return confirmAndLaunch(request, response, REPORT_NAME_CUSTOM, startDate, endDate);
    }

    /**
     * :purpose: Apply the ``SUBMIT-JOB-TO-INTRDR`` confirmation gate and, on a
     *   ``Y``/``y`` confirmation, launch the generation job without blocking.
     * :param request: the report-request screen fields (source of the confirm flag).
     * :param response: the response being assembled.
     * :param reportName: the resolved report name (``Monthly``/``Yearly``/``Custom``).
     * :param startDate: the computed start date in ``YYYY-MM-DD`` wire form.
     * :param endDate: the computed end date in ``YYYY-MM-DD`` wire form.
     * :returns: the response carrying the confirmation prompt, the invalid-value
     *   message, the reset outcome, or the submission success message.
     */
    private ReportResponseDto confirmAndLaunch(ReportRequestDto request, ReportResponseDto response,
                                               String reportName, String startDate, String endDate) {
        String rawConfirm = request.getConfirm();
        String confirmValue = (rawConfirm == null) ? "" : rawConfirm.trim();

        if (confirmValue.isEmpty()) {
            return withMessage(response, CONFIRM_PROMPT_PREFIX + reportName + CONFIRM_PROMPT_SUFFIX);
        }
        if (confirmValue.equals("Y") || confirmValue.equals("y")) {
            jobSchedulingConfig.launchStatementGeneration(reportName, startDate, endDate);
            resetInputFields(response);
            return withMessage(response, reportName + SUBMIT_SUCCESS_SUFFIX);
        }
        if (confirmValue.equals("N") || confirmValue.equals("n")) {
            resetInputFields(response);
            return withMessage(response, null);
        }
        return withMessage(response, "\"" + confirmValue + INVALID_CONFIRM_SUFFIX);
    }

    /**
     * :purpose: Report whether a selector field is set (COBOL ``NOT SPACES AND NOT LOW-VALUES``).
     * :param value: the selector field value.
     * :returns: ``true`` when non-null and non-blank after trimming.
     */
    private static boolean isSelected(String value) {
        return value != null && !value.trim().isEmpty();
    }

    /**
     * :purpose: Report whether a date-part field is empty (COBOL ``= SPACES OR LOW-VALUES``).
     * :param value: the date-part field value.
     * :returns: ``true`` when null or blank after trimming.
     */
    private static boolean isEmpty(String value) {
        return value == null || value.trim().isEmpty();
    }

    /**
     * :purpose: Parse a date part to a non-negative integer (COBOL ``NUMVAL-C`` + ``IS NUMERIC``).
     * :param value: the date-part field value.
     * :returns: the parsed value, or ``null`` when the trimmed text is not all ASCII digits.
     */
    private static Integer parseNonNegativeInt(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        if (trimmed.isEmpty()) {
            return null;
        }
        for (int i = 0; i < trimmed.length(); i++) {
            char digit = trimmed.charAt(i);
            if (digit < '0' || digit > '9') {
                return null;
            }
        }
        try {
            return Integer.valueOf(trimmed);
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    /**
     * :purpose: Validate a built date via {@link DateUtil}, tolerating message number ``2513``.
     * :param date: the ``YYYY-MM-DD`` candidate date.
     * :returns: ``true`` when the date validates or reports the tolerated ``2513`` condition.
     */
    private static boolean isDateAcceptable(String date) {
        DateUtil.DateValidationResult result = DateUtil.validateDate(date, DateUtil.MASK_ISO);
        return result.isValid() || result.msgNo() == DATE_TOLERATED_MSG_NO;
    }

    /**
     * :purpose: Set the outcome message on the response and return it.
     * :param response: the response being assembled.
     * :param message: the outcome message text, or ``null`` to clear it.
     * :returns: the same response instance.
     */
    private static ReportResponseDto withMessage(ReportResponseDto response, String message) {
        response.setErrorMessage(message);
        return response;
    }

    /**
     * :purpose: Clear the echoed input fields (COBOL ``INITIALIZE-ALL-FIELDS``).
     * :param response: the response whose selector, date-part, and confirm fields are cleared.
     */
    private static void resetInputFields(ReportResponseDto response) {
        response.setMonthly(null);
        response.setYearly(null);
        response.setCustom(null);
        response.setStartDateMonth(null);
        response.setStartDateDay(null);
        response.setStartDateYear(null);
        response.setEndDateMonth(null);
        response.setEndDateDay(null);
        response.setEndDateYear(null);
        response.setConfirm(null);
    }
}
