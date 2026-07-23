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

import com.carddemo.common.dto.ReportRequestDto;
import com.carddemo.common.dto.ReportResponseDto;
import org.springframework.stereotype.Component;

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
        return response;
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
