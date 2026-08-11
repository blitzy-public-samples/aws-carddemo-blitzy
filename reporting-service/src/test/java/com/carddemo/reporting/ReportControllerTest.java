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

import com.carddemo.reporting.config.JobSchedulingConfig;
import com.carddemo.common.config.GlobalExceptionHandler;
import com.carddemo.common.dto.ReportRequestDto;
import com.carddemo.common.dto.ReportResponseDto;
import com.carddemo.common.exception.CardDemoException;
import com.carddemo.reporting.controller.ReportController;
import com.carddemo.reporting.service.ReportService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.params.ParameterizedTest;
import org.mockito.ArgumentCaptor;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * :purpose: Standalone-MockMvc unit tests for {@link ReportController}, the REST
 *     re-platform of CICS transaction ``CR00`` (legacy program ``CORPT00C``,
 *     ``POST /reports``). The controller is exercised in isolation via
 *     {@link MockMvcBuilders#standaloneSetup} with a Mockito-mocked
 *     {@link ReportService} and the shared {@link GlobalExceptionHandler}
 *     registered manually as controller advice, so no Spring
 *     ``ApplicationContext``, servlet container, integration-test
 *     infrastructure, or database is started; the suite runs under Surefire in
 *     the ``test`` phase.
 * :note: Pins three HTTP-contract behaviours: (a) a well-formed request
 *     deserializes into a {@link ReportRequestDto}, delegates to the service,
 *     and returns the {@link ReportResponseDto} as HTTP 200 JSON; (b) an empty
 *     JSON body ``{}`` still yields HTTP 200 because the handler carries no
 *     ``@Valid`` annotation, so COBOL-style validation outcomes are 200 payloads
 *     rather than framework 400 responses; (c) a service {@link CardDemoException}
 *     is mapped to HTTP 400 by the shared advice.
 * :note: The response message is bound to the real accessor
 *     {@link ReportResponseDto#getErrorMessage()} and the JSON is produced and
 *     consumed through Jackson so no wire property names are hard-coded for the
 *     response DTO.
 */
class ReportControllerTest {

    /**
     * :purpose: Verbatim ``CORPT00C`` monthly-submission success banner
     *     (``WS-REPORT-NAME`` ``'Monthly'`` concatenated with the submit suffix).
     */
    private static final String MSG_MONTHLY_SUBMITTED = "Monthly report submitted for printing ...";

    /** :purpose: Verbatim ``CORPT00C`` no-report-type-selected validation message. */
    private static final String MSG_SELECT_REPORT_TYPE = "Select a report type to print report...";

    /** :purpose: Verbatim ``CORPT00C`` TDQ (JOBS) submission-failure message surfaced as a domain error. */
    private static final String MSG_TDQ_WRITE_FAILURE = "Unable to Write TDQ (JOBS)...";

    /** :purpose: Mocked report application service; stubbed per scenario, never really invoked. */
    private ReportService reportService;

    /** :purpose: Standalone MockMvc bound to the controller plus the shared exception advice. */
    private MockMvc mockMvc;

    /** :purpose: Jackson mapper used to build request JSON and read response JSON. */
    private ObjectMapper objectMapper;

    /**
     * :purpose: Build a fresh mocked service, a standalone MockMvc wired only to
     *     the controller instance and the shared {@link GlobalExceptionHandler},
     *     and a plain Jackson mapper before each scenario.
     * :note: ``standaloneSetup`` starts no Spring context; the advice is
     *     registered manually so the base {@link CardDemoException}-to-400
     *     mapping is reproduced without component scanning.
     */
    @BeforeEach
    void setUp() {
        reportService = mock(ReportService.class);
        mockMvc = MockMvcBuilders
                .standaloneSetup(new ReportController(reportService, mock(JobSchedulingConfig.class)))
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
        objectMapper = new ObjectMapper();
    }

    /**
     * :purpose: A well-formed ``POST /reports`` request is deserialized into a
     *     {@link ReportRequestDto}, delegated to {@link ReportService}, and the
     *     returned {@link ReportResponseDto} is serialized as HTTP 200 JSON; the
     *     captured argument proves the ``@RequestBody`` binding round-trips.
     */
    @Test
    @DisplayName("POST /reports well-formed request returns 200 and delegates the bound DTO to the service")
    void wellFormedPost_returns200_andDelegates() throws Exception {
        ReportRequestDto request = new ReportRequestDto();
        request.setMonthly("Y");
        request.setConfirm("Y");
        String requestJson = objectMapper.writeValueAsString(request);

        ReportResponseDto stubbed = new ReportResponseDto();
        stubbed.setErrorMessage(MSG_MONTHLY_SUBMITTED);
        when(reportService.requestReport(any(ReportRequestDto.class))).thenReturn(stubbed);

        String responseBody = mockMvc.perform(post("/reports")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestJson))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();

        ReportResponseDto response = objectMapper.readValue(responseBody, ReportResponseDto.class);
        assertThat(response.getErrorMessage()).isEqualTo(MSG_MONTHLY_SUBMITTED);

        ArgumentCaptor<ReportRequestDto> captor = ArgumentCaptor.forClass(ReportRequestDto.class);
        verify(reportService).requestReport(captor.capture());
        assertThat(captor.getValue().getMonthly()).isEqualTo("Y");
        assertThat(captor.getValue().getConfirm()).isEqualTo("Y");
    }

    /**
     * :purpose: An empty JSON body ``{}`` still returns HTTP 200 and reaches the
     *     service, deserializing to a blank {@link ReportRequestDto}.
     * :note: Guards the intentional absence of ``@Valid`` on the handler, which
     *     preserves the legacy ``CORPT00C`` semantics of surfacing validation
     *     outcomes as 200 payloads rather than framework 400 responses.
     */
    @Test
    @DisplayName("POST /reports empty JSON body {} returns 200 and reaches the service (no @Valid short-circuit)")
    void emptyJsonBody_returns200_noValidation() throws Exception {
        when(reportService.requestReport(any(ReportRequestDto.class))).thenReturn(new ReportResponseDto());

        mockMvc.perform(post("/reports")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isOk());

        verify(reportService).requestReport(any(ReportRequestDto.class));
    }

    /**
     * :purpose: A {@link CardDemoException} thrown by the service is mapped to
     *     HTTP 400 by the shared {@link GlobalExceptionHandler}, and the verbatim
     *     message is echoed in the standard error body.
     */
    @Test
    @DisplayName("POST /reports service CardDemoException maps to 400 via the shared GlobalExceptionHandler")
    void serviceThrowsCardDemoException_returns400() throws Exception {
        when(reportService.requestReport(any(ReportRequestDto.class)))
                .thenThrow(new CardDemoException(MSG_TDQ_WRITE_FAILURE));

        ReportRequestDto request = new ReportRequestDto();
        request.setMonthly("Y");
        request.setConfirm("Y");
        String requestJson = objectMapper.writeValueAsString(request);

        mockMvc.perform(post("/reports")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestJson))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.message").value(MSG_TDQ_WRITE_FAILURE));
    }

    /**
     * :purpose: A COBOL-style validation outcome returned by the service surfaces
     *     as an HTTP 200 payload carrying the verbatim message, not an HTTP error;
     *     this is the whole reason the handler omits ``@Valid``.
     */
    @Test
    @DisplayName("POST /reports validation message surfaces as a 200 payload, not an HTTP error")
    void serviceValidationMessage_returns200_notError() throws Exception {
        ReportResponseDto stubbed = new ReportResponseDto();
        stubbed.setErrorMessage(MSG_SELECT_REPORT_TYPE);
        when(reportService.requestReport(any(ReportRequestDto.class))).thenReturn(stubbed);

        ReportRequestDto request = new ReportRequestDto();
        String requestJson = objectMapper.writeValueAsString(request);

        String responseBody = mockMvc.perform(post("/reports")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestJson))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();

        ReportResponseDto response = objectMapper.readValue(responseBody, ReportResponseDto.class);
        assertThat(response.getErrorMessage()).isEqualTo(MSG_SELECT_REPORT_TYPE);
    }

    // =====================================================================
    // F36 - the CORPT00 screen field widths are enforced (@Valid)
    // =====================================================================

    /**
     * :purpose: A selector longer than the single-character CORPT00 screen field is
     *     rejected with HTTP 400 rather than accepted, and the service is never
     *     reached.
     */
    @Test
    @DisplayName("POST /reports oversized selector returns 400 and never reaches the service")
    void oversizedSelector_returns400() throws Exception {
        String oversized = "A".repeat(200);

        mockMvc.perform(post("/reports")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"monthly\":\"" + oversized + "\",\"confirm\":\"\"}"))
                .andExpect(status().isBadRequest());

        verify(reportService, never()).requestReport(any(ReportRequestDto.class));
    }

    /**
     * :purpose: A path-traversal value in a selector exceeds the one-character
     *     field width and is therefore rejected before any job can be launched.
     */
    @Test
    @DisplayName("POST /reports path-traversal selector returns 400")
    void pathTraversalSelector_returns400() throws Exception {
        mockMvc.perform(post("/reports")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"monthly\":\"../../etc/passwd\",\"confirm\":\"Y\"}"))
                .andExpect(status().isBadRequest());

        verify(reportService, never()).requestReport(any(ReportRequestDto.class));
    }

    /**
     * :purpose: Every over-width field of the CORPT00 screen contract is rejected:
     *     the three selectors and the confirm flag (one character), the month and
     *     day parts (two characters) and the year parts (four characters).
     * :param body: the request body carrying a single over-width field.
     */
    @ParameterizedTest
    @ValueSource(strings = {
        "{\"yearly\":\"YY\"}",
        "{\"custom\":\"YY\"}",
        "{\"confirm\":\"YESPLEASE\"}",
        "{\"custom\":\"Y\",\"startDateMonth\":\"0123456789\"}",
        "{\"custom\":\"Y\",\"startDateDay\":\"123\"}",
        "{\"custom\":\"Y\",\"startDateYear\":\"20244\"}",
        "{\"custom\":\"Y\",\"endDateMonth\":\"123\"}",
        "{\"custom\":\"Y\",\"endDateDay\":\"123\"}",
        "{\"custom\":\"Y\",\"endDateYear\":\"20244\"}"
    })
    @DisplayName("POST /reports every over-width screen field returns 400")
    void overWidthFields_return400(String body) throws Exception {
        mockMvc.perform(post("/reports")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest());

        verify(reportService, never()).requestReport(any(ReportRequestDto.class));
    }

    /**
     * :purpose: Values that fit the screen field widths are still accepted, so the
     *     added validation does not narrow the legacy input contract.
     */
    @Test
    @DisplayName("POST /reports within-width custom date parts still reach the service")
    void withinWidthFields_stillDelegate() throws Exception {
        ReportResponseDto stubbed = new ReportResponseDto();
        when(reportService.requestReport(any(ReportRequestDto.class))).thenReturn(stubbed);

        mockMvc.perform(post("/reports")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"custom\":\"Y\",\"startDateMonth\":\"03\",\"startDateDay\":\"01\","
                                + "\"startDateYear\":\"2024\",\"endDateMonth\":\"03\",\"endDateDay\":\"31\","
                                + "\"endDateYear\":\"2024\",\"confirm\":\"Y\"}"))
                .andExpect(status().isOk());

        verify(reportService).requestReport(any(ReportRequestDto.class));
    }
}
