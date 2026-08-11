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
package com.carddemo.reporting.mapper;

import com.carddemo.common.constant.Titles;
import com.carddemo.common.dto.ReportRequestDto;
import com.carddemo.common.dto.ReportResponseDto;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * :purpose: Hand-written, stateless mapper that translates between the report
 *   request/response DTOs (``com.carddemo.common.dto``) and the legacy CORPT00
 *   report-request screen field contract (CICS transaction ``CR00``). Performs a
 *   pure, deterministic ``String`` field copy of the report-type selectors
 *   (``MONTHLY`` / ``YEARLY`` / ``CUSTOM``) and the custom start/end date parts
 *   only. Carries no validation, no date or report-window computation, and no
 *   other business logic; those responsibilities belong to ``ReportService``.
 */
@Component
public class ReportMapper {

    /**
     * :purpose: CICS transaction identifier of the report-request screen
     *  (``CORPT00C WS-TRANID PIC X(04) VALUE 'CR00'``), sent to ``TRNNAMEO``.
     */
    public static final String TRAN_NAME = "CR00";

    /**
     * :purpose: Legacy program name of the report-request screen
     *  (``CORPT00C WS-PGMNAME PIC X(08) VALUE 'CORPT00C'``), sent to ``PGMNAMEO``.
     */
    public static final String PROGRAM_NAME = "CORPT00C";

    /**
     * :purpose: ``WS-CURDATE-MM-DD-YY`` layout (``CSDAT01Y`` L30-35): two-digit
     *  month, day and year separated by ``/``.
     */
    private static final DateTimeFormatter CURDATE_FORMATTER = DateTimeFormatter.ofPattern("MM/dd/yy");

    /**
     * :purpose: ``WS-CURTIME-HH-MM-SS`` layout (``CSDAT01Y`` L36-41): two-digit
     *  hours, minutes and seconds separated by ``:``.
     */
    private static final DateTimeFormatter CURTIME_FORMATTER = DateTimeFormatter.ofPattern("HH:mm:ss");

    /** :purpose: Clock supplying the screen header date and time; injectable for tests. */
    private final Clock clock;

    /**
     * :purpose: Construct the mapper with the system default-zone clock.
     */
    public ReportMapper() {
        this(Clock.systemDefaultZone());
    }

    /**
     * :purpose: Construct the mapper with an explicit clock so the screen header
     *  date and time are deterministic under test.
     * :param clock: the clock supplying ``CURDATE``/``CURTIME``.
     */
    public ReportMapper(Clock clock) {
        this.clock = clock;
    }

    /**
     * :purpose: Build a report response DTO that echoes the report-type selector
     *   and the custom start/end date parts entered on the CORPT00 screen back to
     *   the client. Response-only fields (the error/status message, the
     *   confirmation flag, and the screen header fields) are left unset for
     *   ``ReportService`` to populate. Pure field copy with no validation or
     *   business logic.
     * :param request: the inbound report request DTO carrying the CORPT00 input
     *   fields; may be ``null``.
     * :returns: a new ``ReportResponseDto`` populated with the echoed selector and
     *   date-part fields, or ``null`` when ``request`` is ``null``.
     */
    public ReportResponseDto toResponse(ReportRequestDto request) {
        if (request == null) {
            return null;
        }
        ReportResponseDto response = new ReportResponseDto();
        applyRequestToResponse(request, response);
        applyScreenHeader(response);
        return response;
    }

    /**
     * :purpose: Populate the CORPT00 screen header exactly as ``CORPT00C``
     *   ``3000-SEND-MAP`` does (L611-628): the two application titles from
     *   ``COTTL01Y``, the transaction and program names, and the current date and
     *   time in the ``CSDAT01Y`` ``mm/dd/yy`` and ``hh:mm:ss`` layouts.
     * :param target: the report response DTO whose header fields are set; when
     *   ``null`` the method is a no-op.
     */
    public void applyScreenHeader(ReportResponseDto target) {
        if (target == null) {
            return;
        }
        LocalDateTime now = LocalDateTime.now(clock);
        target.setTitle01(Titles.CCDA_TITLE01);
        target.setTitle02(Titles.CCDA_TITLE02);
        target.setTrnName(TRAN_NAME);
        target.setPgmName(PROGRAM_NAME);
        target.setCurrentDate(CURDATE_FORMATTER.format(now));
        target.setCurrentTime(CURTIME_FORMATTER.format(now));
    }

    /**
     * :purpose: Copy the shared CORPT00 report-type selector and custom start/end
     *   date-part fields from a report request DTO into an existing report
     *   response DTO. Pure field copy; response-only fields already present on the
     *   target (error/status message, confirmation flag, header fields) are left
     *   untouched.
     * :param source: the report request DTO to copy field values from; when
     *   ``null`` the method is a no-op.
     * :param target: the report response DTO to copy field values into; when
     *   ``null`` the method is a no-op.
     */
    public void applyRequestToResponse(ReportRequestDto source, ReportResponseDto target) {
        if (source == null || target == null) {
            return;
        }
        // Report-type selectors (single-character CORPT00 flags MONTHLY / YEARLY / CUSTOM).
        target.setMonthly(source.getMonthly());
        target.setYearly(source.getYearly());
        target.setCustom(source.getCustom());
        // Custom report start-date parts (CORPT00 SDTMM / SDTDD / SDTYYYY).
        target.setStartDateMonth(source.getStartDateMonth());
        target.setStartDateDay(source.getStartDateDay());
        target.setStartDateYear(source.getStartDateYear());
        // Custom report end-date parts (CORPT00 EDTMM / EDTDD / EDTYYYY).
        target.setEndDateMonth(source.getEndDateMonth());
        target.setEndDateDay(source.getEndDateDay());
        target.setEndDateYear(source.getEndDateYear());
    }
}
