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
package com.carddemo.reporting.controller;

import com.carddemo.common.dto.ReportRequestDto;
import com.carddemo.common.dto.ReportResponseDto;
import com.carddemo.reporting.service.ReportService;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * REST entry point for the CardDemo reporting microservice.
 *
 * :purpose: Expose ``POST /reports`` (CICS transaction ``CR00``, legacy program
 *     ``CORPT00C``) as the stateless HTTP boundary of the reporting-service.
 *     Report-type resolution, date-range validation, the confirmation gate, and
 *     asynchronous report/statement job launch are delegated in full to
 *     {@link ReportService}; the controller itself holds no conversational
 *     state and performs no business logic.
 */
@RestController
@RequestMapping("/reports")
public class ReportController {

    private final ReportService reportService;

    /**
     * :purpose: Construct the controller with its collaborating report service.
     * :param reportService: the report application service that performs all
     *     report-request business logic (validation, confirmation gate, and
     *     asynchronous job launch).
     */
    public ReportController(ReportService reportService) {
        this.reportService = reportService;
    }

    /**
     * :purpose: Submit a transaction-report request (CICS ``CR00``, legacy
     *     program ``CORPT00C``). Report-type resolution, date validation, the
     *     confirmation gate, and asynchronous job launch are delegated to
     *     {@link ReportService}.
     * :param request: the report-request payload (report-type selector, optional
     *     custom start/end date parts, and the confirmation flag).
     * :returns: the report-response DTO carrying the confirmation prompt, a
     *     validation message, or the submission acknowledgement (HTTP 200).
     * :raises com.carddemo.common.exception.CardDemoException: when the
     *     asynchronous report job cannot be launched; mapped to HTTP 400 by the
     *     shared ``GlobalExceptionHandler`` and therefore not caught here.
     */
    @PostMapping
    public ReportResponseDto requestReport(@RequestBody ReportRequestDto request) {
        return reportService.requestReport(request);
    }
}
