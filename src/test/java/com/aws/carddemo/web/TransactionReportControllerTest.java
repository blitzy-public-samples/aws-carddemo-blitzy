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
package com.aws.carddemo.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithAnonymousUser;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.aws.carddemo.config.SecurityConfig;
import com.aws.carddemo.dto.PfKeyAction;
import com.aws.carddemo.dto.TransactionReportRequest;
import com.aws.carddemo.mapper.ReportMapper;
import com.aws.carddemo.service.ReportService;
import com.aws.carddemo.service.ReportService.ReportRequest;
import com.aws.carddemo.service.ReportService.ReportResult;
import com.aws.carddemo.service.ReportService.ReportType;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * {@code @WebMvcTest} slice test for {@link TransactionReportController} &mdash; the Java
 * re-platform of the CardDemo COBOL online program {@code CORPT00C} (CICS transaction
 * {@code CR00}; BMS map {@code CORPT00}). It verifies the Transaction Report <em>request</em>
 * REST contract exposed at {@code /api/v1/reports/transactions}: report-type selection
 * (monthly / yearly / custom), the confirmation gate, custom date-range composition, PF-key
 * routing, and the security posture &mdash; without loading the persistence, batch, or
 * observability tiers.
 *
 * <h2>Harness</h2>
 * <p>The web slice loads only {@link TransactionReportController} plus the explicitly imported
 * {@link SecurityConfig} (the real stateless HTTP-Basic filter chain) and the real
 * {@link ReportMapper} (so the header/response projection is exercised for real). The
 * {@link ReportService} is replaced by a Mockito mock via {@link MockitoBean}; because the
 * whole service is mocked, its collaborators &mdash; most importantly the Spring Batch
 * {@code JobLauncher} and the {@code transactionReportJob} {@code Job} &mdash; never enter the
 * context. That is the structural guarantee behind the central invariant of this screen:
 * <strong>the controller launches no batch job; it only asks the service to
 * {@link ReportService#requestReport(ReportRequest)}</strong> (AAP &sect;0.5.4, hotspots H2 /
 * M4). The application-wide {@code GlobalExceptionHandler} ({@code @RestControllerAdvice}) is
 * auto-detected by the slice.</p>
 *
 * <h2>Behavioral parity asserted</h2>
 * <p>Every caller-visible message literal asserted here traces verbatim to the legacy program
 * ({@code legacy/cbl/CORPT00C.cbl}) via {@link ReportService}: the "select a report type"
 * fall-through, the "please confirm" prompt, the quoted invalid-confirm rejection, and the
 * "submitted for printing" success line. The invalid-key text traces to {@code CSMSG01Y.cpy}.
 * Response field names mirror the {@code CORPT00} symbolic copybook one-to-one (test&nbsp;L),
 * and request bodies are serialized from the real {@link TransactionReportRequest} DTO with the
 * autowired {@link ObjectMapper} so the exact field contract is exercised end-to-end.</p>
 *
 * <p>The class is annotated {@link WithMockUser} so every request is authenticated as an
 * ordinary (non-admin) user &mdash; the reports screen requires only {@code authenticated()};
 * the single unauthenticated case overrides this with {@link WithAnonymousUser}. All tests are
 * deterministic and headless (no Docker, no real database); assertions on the time-dependent
 * header fields check presence/shape, never a literal clock value.</p>
 */
@WebMvcTest(TransactionReportController.class)
@Import({SecurityConfig.class, ReportMapper.class})
@WithMockUser
class TransactionReportControllerTest {

    /** The controller endpoint under test ({@code @RequestMapping} of the controller). */
    private static final String ENDPOINT = "/api/v1/reports/transactions";

    // --- Header constants projected by ReportMapper (traceability: CORPT00.CPY / CORPT00C). ---

    /** Constant transaction (screen) name in the header ({@code TRNNAMEO}). */
    private static final String TRANSACTION_NAME = "CR00";

    /** Constant originating program name in the header ({@code PGMNAMEO}). */
    private static final String PROGRAM_NAME = "CORPT00C";

    /** First application title line in the header ({@code TITLE01O}). */
    private static final String TITLE_01 = "AWS Mainframe Modernization";

    /** Second application title line in the header ({@code TITLE02O}). */
    private static final String TITLE_02 = "CardDemo";

    // --- Navigation headers set by the PF3 (back to Main Menu) path. ---

    /** Response header carrying the PF3 navigation target program. */
    private static final String NAV_PROGRAM_HEADER = "X-CardDemo-Next-Program";

    /** Response header carrying the PF3 navigation target transaction id. */
    private static final String NAV_TRANSACTION_HEADER = "X-CardDemo-Next-Transaction";

    /** Main Menu program PF3 returns to ({@code XCTL PROGRAM('COMEN01C')}). */
    private static final String MENU_PROGRAM = "COMEN01C";

    /** Main Menu transaction id PF3 returns to. */
    private static final String MENU_TRANSACTION = "CM00";

    // --- Exact caller-visible messages (verbatim from ReportService / CORPT00C). ---

    /** No report type selected (COBOL {@code WHEN OTHER}). */
    private static final String MSG_SELECT_REPORT_TYPE = "Select a report type to print report...";

    /** Monthly report awaiting confirmation (blank confirm). */
    private static final String MSG_CONFIRM_MONTHLY = "Please confirm to print the Monthly report...";

    /** Monthly report submitted for printing. */
    private static final String MSG_SUBMIT_MONTHLY = "Monthly report submitted for printing ...";

    /** Yearly report submitted for printing. */
    private static final String MSG_SUBMIT_YEARLY = "Yearly report submitted for printing ...";

    /** Custom report submitted for printing. */
    private static final String MSG_SUBMIT_CUSTOM = "Custom report submitted for printing ...";

    /** Invalid confirmation value "X" (COBOL {@code WHEN OTHER} of the confirm evaluate). */
    private static final String MSG_INVALID_CONFIRM = "\"X\" is not a valid value to confirm...";

    /** Invalid attention key (COBOL {@code CCDA-MSG-INVALID-KEY}, {@code CSMSG01Y.cpy}). */
    private static final String MSG_INVALID_KEY = "Invalid key pressed. Please see below...";

    /** Spring MVC test client (filters on; CSRF disabled by {@link SecurityConfig}). */
    @Autowired
    private MockMvc mockMvc;

    /** Application {@link ObjectMapper}; used to serialize request DTOs to JSON request bodies. */
    @Autowired
    private ObjectMapper objectMapper;

    /**
     * The mocked report service. Mocking the whole service keeps this a pure web slice: the
     * service's {@code JobLauncher} and {@code transactionReportJob} {@code Job} never load,
     * so the controller cannot (and does not) launch a batch job.
     */
    @MockitoBean
    private ReportService reportService;

    // =====================================================================
    // A. Security posture
    // =====================================================================

    /**
     * A. An unauthenticated request is rejected with {@code 401 Unauthorized} by the security
     * filter chain before the controller is reached, and the service is never consulted.
     */
    @Test
    @WithAnonymousUser
    @DisplayName("A. unauthenticated request -> 401 and service untouched")
    void unauthenticatedRequestIsRejected() throws Exception {
        mockMvc.perform(get(ENDPOINT))
                .andExpect(status().isUnauthorized());

        verifyNoInteractions(reportService);
    }

    // =====================================================================
    // B. GET blank report-request screen
    // =====================================================================

    /**
     * B. {@code GET} returns the blank report-request screen: {@code 200 OK}, the constant
     * header projection, and no status/error message (the {@code NON_NULL} Jackson contract
     * omits the {@code null} {@code errorMessage}). The service is not invoked for a screen
     * fetch.
     */
    @Test
    @DisplayName("B. GET -> 200 blank screen, no error message, service untouched")
    void getReturnsBlankScreen() throws Exception {
        mockMvc.perform(get(ENDPOINT))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.transactionName").value(TRANSACTION_NAME))
                .andExpect(jsonPath("$.programName").value(PROGRAM_NAME))
                .andExpect(jsonPath("$.title01").value(TITLE_01))
                .andExpect(jsonPath("$.title02").value(TITLE_02))
                .andExpect(jsonPath("$.currentDate").isNotEmpty())
                .andExpect(jsonPath("$.currentTime").isNotEmpty())
                .andExpect(jsonPath("$.errorMessage").doesNotExist());

        verifyNoInteractions(reportService);
    }

    // =====================================================================
    // C. POST ENTER - no report type selected
    // =====================================================================

    /**
     * C. Pressing Enter with no report type marker set drives the controller to call the
     * service with a {@code null} {@link ReportType}; the service returns the legacy
     * "select a report type" fall-through message, which is projected onto the screen at
     * {@code 200 OK}.
     */
    @Test
    @DisplayName("C. POST ENTER no type -> 200 'select a report type', service sees null type")
    void postEnterWithoutTypeSelected() throws Exception {
        when(reportService.requestReport(any()))
                .thenReturn(new ReportResult(false, MSG_SELECT_REPORT_TYPE));

        mockMvc.perform(post(ENDPOINT)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(request(null, null, null, null, PfKeyAction.ENTER))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.errorMessage").value(MSG_SELECT_REPORT_TYPE));

        ArgumentCaptor<ReportRequest> captor = ArgumentCaptor.forClass(ReportRequest.class);
        verify(reportService).requestReport(captor.capture());
        assertThat(captor.getValue().type()).isNull();
    }

    // =====================================================================
    // D. POST ENTER - MONTHLY awaiting confirmation
    // =====================================================================

    /**
     * D. Selecting the monthly report with a blank confirmation yields the "please confirm"
     * prompt; the captured request carries {@link ReportType#MONTHLY} and a {@code null}
     * confirmation flag (no submission has occurred).
     */
    @Test
    @DisplayName("D. POST ENTER MONTHLY, blank confirm -> 200 confirm prompt, type=MONTHLY")
    void postEnterMonthlyAwaitingConfirmation() throws Exception {
        when(reportService.requestReport(any()))
                .thenReturn(new ReportResult(false, MSG_CONFIRM_MONTHLY));

        mockMvc.perform(post(ENDPOINT)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(request("Y", null, null, null, PfKeyAction.ENTER))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.errorMessage").value(MSG_CONFIRM_MONTHLY));

        ArgumentCaptor<ReportRequest> captor = ArgumentCaptor.forClass(ReportRequest.class);
        verify(reportService).requestReport(captor.capture());
        assertThat(captor.getValue().type()).isEqualTo(ReportType.MONTHLY);
        assertThat(captor.getValue().confirmFlag()).isNull();
    }

    // =====================================================================
    // E. POST ENTER - MONTHLY confirmed submission
    // =====================================================================

    /**
     * E. Selecting the monthly report with {@code confirm = "Y"} submits: the service returns
     * the "submitted for printing" message, projected at {@code 200 OK}. The captured request
     * carries {@link ReportType#MONTHLY} and the {@code "Y"} confirmation flag. Monthly and
     * yearly requests carry no operator dates, so the start/end arguments are {@code null}.
     */
    @Test
    @DisplayName("E. POST ENTER MONTHLY, confirm=Y -> 200 submitted, type=MONTHLY, no dates")
    void postEnterMonthlyConfirmedSubmit() throws Exception {
        when(reportService.requestReport(any()))
                .thenReturn(new ReportResult(true, MSG_SUBMIT_MONTHLY));

        mockMvc.perform(post(ENDPOINT)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(request("Y", null, null, "Y", PfKeyAction.ENTER))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.errorMessage").value(MSG_SUBMIT_MONTHLY));

        ArgumentCaptor<ReportRequest> captor = ArgumentCaptor.forClass(ReportRequest.class);
        verify(reportService).requestReport(captor.capture());
        ReportRequest sent = captor.getValue();
        assertThat(sent.type()).isEqualTo(ReportType.MONTHLY);
        assertThat(sent.confirmFlag()).isEqualTo("Y");
        assertThat(sent.startDate()).isNull();
        assertThat(sent.endDate()).isNull();
    }

    // =====================================================================
    // F. POST ENTER - YEARLY confirmed submission
    // =====================================================================

    /**
     * F. Analogous to E for the yearly report: {@code yearly = "Y"} with {@code confirm = "Y"}
     * submits and the captured request carries {@link ReportType#YEARLY} with no operator
     * dates.
     */
    @Test
    @DisplayName("F. POST ENTER YEARLY, confirm=Y -> 200 submitted, type=YEARLY, no dates")
    void postEnterYearlyConfirmedSubmit() throws Exception {
        when(reportService.requestReport(any()))
                .thenReturn(new ReportResult(true, MSG_SUBMIT_YEARLY));

        mockMvc.perform(post(ENDPOINT)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(request(null, "Y", null, "Y", PfKeyAction.ENTER))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.errorMessage").value(MSG_SUBMIT_YEARLY));

        ArgumentCaptor<ReportRequest> captor = ArgumentCaptor.forClass(ReportRequest.class);
        verify(reportService).requestReport(captor.capture());
        ReportRequest sent = captor.getValue();
        assertThat(sent.type()).isEqualTo(ReportType.YEARLY);
        assertThat(sent.confirmFlag()).isEqualTo("Y");
        assertThat(sent.startDate()).isNull();
        assertThat(sent.endDate()).isNull();
    }

    // =====================================================================
    // G. POST ENTER - CUSTOM with a composed date range
    // =====================================================================

    /**
     * G. The custom report path composes the discrete month/day/year screen parts into the
     * {@code year-month-day} strings the service decomposes and validates. For a range of
     * {@code 01/01/2023} through {@code 12/31/2023} the controller must pass
     * {@code startDate = "2023-01-01"} and {@code endDate = "2023-12-31"} (the controller's
     * {@code joinDateParts} composition, which preserves the operator's parts verbatim so the
     * service can emit its granular per-field messages). The captured request is asserted to
     * carry {@link ReportType#CUSTOM}, both composed dates, and the {@code "Y"} confirmation.
     */
    @Test
    @DisplayName("G. POST ENTER CUSTOM range -> service sees CUSTOM + composed start/end dates")
    void postEnterCustomComposesDateRange() throws Exception {
        when(reportService.requestReport(any()))
                .thenReturn(new ReportResult(true, MSG_SUBMIT_CUSTOM));

        TransactionReportRequest custom = new TransactionReportRequest(
                null, null, "Y",
                "01", "01", "2023",
                "12", "31", "2023",
                "Y", PfKeyAction.ENTER);

        mockMvc.perform(post(ENDPOINT)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(custom)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.errorMessage").value(MSG_SUBMIT_CUSTOM));

        ArgumentCaptor<ReportRequest> captor = ArgumentCaptor.forClass(ReportRequest.class);
        verify(reportService).requestReport(captor.capture());
        ReportRequest sent = captor.getValue();
        assertThat(sent.type()).isEqualTo(ReportType.CUSTOM);
        assertThat(sent.startDate()).isEqualTo("2023-01-01");
        assertThat(sent.endDate()).isEqualTo("2023-12-31");
        assertThat(sent.confirmFlag()).isEqualTo("Y");
    }

    // =====================================================================
    // H. POST ENTER - invalid confirmation value
    // =====================================================================

    /**
     * H. An unrecognized confirmation value ({@code "X"}) is rejected with the quoted
     * invalid-confirm message and nothing is submitted; the message is projected at
     * {@code 200 OK}, mirroring the legacy "re-display the screen with a message" behavior.
     */
    @Test
    @DisplayName("H. POST ENTER MONTHLY, confirm=X -> 200 invalid-confirm message, not submitted")
    void postEnterInvalidConfirmValue() throws Exception {
        when(reportService.requestReport(any()))
                .thenReturn(new ReportResult(false, MSG_INVALID_CONFIRM));

        mockMvc.perform(post(ENDPOINT)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(request("Y", null, null, "X", PfKeyAction.ENTER))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.errorMessage").value(MSG_INVALID_CONFIRM));

        ArgumentCaptor<ReportRequest> captor = ArgumentCaptor.forClass(ReportRequest.class);
        verify(reportService).requestReport(captor.capture());
        assertThat(captor.getValue().confirmFlag()).isEqualTo("X");
    }

    // =====================================================================
    // I. POST PF3 - back to the Main Menu
    // =====================================================================

    /**
     * I. PF3 navigates back to the Main Menu program: {@code 200 OK} carrying the navigation
     * headers ({@code X-CardDemo-Next-Program = COMEN01C},
     * {@code X-CardDemo-Next-Transaction = CM00}), a message-free screen body, and
     * <strong>no</strong> service interaction (navigation is the controller's concern, not the
     * service's).
     */
    @Test
    @DisplayName("I. POST PF3 -> 200 with nav headers to Main Menu, service untouched")
    void postPf3NavigatesBackToMainMenu() throws Exception {
        mockMvc.perform(post(ENDPOINT)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(request(null, null, null, null, PfKeyAction.PF3))))
                .andExpect(status().isOk())
                .andExpect(header().string(NAV_PROGRAM_HEADER, MENU_PROGRAM))
                .andExpect(header().string(NAV_TRANSACTION_HEADER, MENU_TRANSACTION))
                .andExpect(jsonPath("$.transactionName").value(TRANSACTION_NAME))
                .andExpect(jsonPath("$.errorMessage").doesNotExist());

        verifyNoInteractions(reportService);
    }

    // =====================================================================
    // J. POST with an unmapped attention key
    // =====================================================================

    /**
     * J. Any attention key other than Enter or PF3 (here {@link PfKeyAction#PF7}) yields the
     * legacy invalid-key message at {@code 200 OK}, and the service is never consulted (the
     * COBOL {@code WHEN OTHER} branch re-sent the screen with {@code CCDA-MSG-INVALID-KEY}).
     */
    @Test
    @DisplayName("J. POST unmapped key (PF7) -> 200 invalid-key message, service untouched")
    void postUnmappedKeyYieldsInvalidKeyMessage() throws Exception {
        mockMvc.perform(post(ENDPOINT)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(request(null, null, null, null, PfKeyAction.PF7))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.errorMessage").value(MSG_INVALID_KEY));

        verifyNoInteractions(reportService);
    }

    // =====================================================================
    // J2. POST with an absent action (default-Enter convention)
    // =====================================================================

    /**
     * J2. An absent {@code action} is treated as Enter (the package-wide default): the request
     * is processed through the service exactly as an explicit {@link PfKeyAction#ENTER}. This
     * exercises the controller's {@code action == null} branch.
     */
    @Test
    @DisplayName("J2. POST with no action -> processed as ENTER (service consulted)")
    void postWithoutActionDefaultsToEnter() throws Exception {
        when(reportService.requestReport(any()))
                .thenReturn(new ReportResult(false, MSG_SELECT_REPORT_TYPE));

        mockMvc.perform(post(ENDPOINT)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(request(null, null, null, null, null))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.errorMessage").value(MSG_SELECT_REPORT_TYPE));

        verify(reportService).requestReport(any());
    }

    // =====================================================================
    // K. The controller launches NO batch job (interacts only with the service)
    // =====================================================================

    /**
     * K. Mandatory invariant (AAP &sect;0.5.4, H2 / M4): on a confirmed submission the
     * controller's <em>only</em> collaborator interaction is a single
     * {@link ReportService#requestReport(ReportRequest)} call &mdash; it neither launches a
     * {@code JobLauncher} nor references the {@code transactionReportJob} {@code Job} (both are
     * guaranteed absent from this slice because the whole service is mocked). The
     * {@code verifyNoMoreInteractions} assertion documents that no further service call is made.
     */
    @Test
    @DisplayName("K. confirmed submit -> exactly one requestReport call and no other service interaction")
    void controllerLaunchesNoBatchJob() throws Exception {
        when(reportService.requestReport(any()))
                .thenReturn(new ReportResult(true, MSG_SUBMIT_MONTHLY));

        mockMvc.perform(post(ENDPOINT)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(request("Y", null, null, "Y", PfKeyAction.ENTER))))
                .andExpect(status().isOk());

        verify(reportService).requestReport(any());
        verifyNoMoreInteractions(reportService);
    }

    // =====================================================================
    // L. Field-contract parity with the CORPT00 symbolic copybook
    // =====================================================================

    /**
     * L. The response projection exposes exactly the header/message fields of the
     * {@code CORPT00} symbolic copybook, under the DTO names that mirror it one-to-one
     * ({@code transactionName -> TRNNAMEO}, {@code title01 -> TITLE01O},
     * {@code currentDate -> CURDATEO}, {@code programName -> PGMNAMEO},
     * {@code title02 -> TITLE02O}, {@code currentTime -> CURTIMEO},
     * {@code errorMessage -> ERRMSGO}). There is deliberately no data payload &mdash; the
     * report itself is produced by the batch/file layer &mdash; so no {@code data} or
     * {@code transactions} field is present.
     */
    @Test
    @DisplayName("L. response field names mirror CORPT00 copybook; no data payload")
    void responseFieldContractMirrorsCopybook() throws Exception {
        mockMvc.perform(get(ENDPOINT))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.transactionName").value(TRANSACTION_NAME))
                .andExpect(jsonPath("$.title01").value(TITLE_01))
                .andExpect(jsonPath("$.currentDate").exists())
                .andExpect(jsonPath("$.programName").value(PROGRAM_NAME))
                .andExpect(jsonPath("$.title02").value(TITLE_02))
                .andExpect(jsonPath("$.currentTime").exists())
                .andExpect(jsonPath("$.data").doesNotExist())
                .andExpect(jsonPath("$.transactions").doesNotExist());

        verifyNoInteractions(reportService);
    }

    /**
     * L (request contract). The request DTO keeps the six custom-range date components as
     * discrete fields (mirroring the {@code SDTMMI}/{@code SDTDDI}/{@code SDTYYYYI} and
     * {@code EDTMMI}/{@code EDTDDI}/{@code EDTYYYYI} map fields). This asserts they are honored
     * independently: a range whose parts differ only in the day component still composes into
     * distinct start/end dates rather than being collapsed on the request side.
     */
    @Test
    @DisplayName("L. request keeps discrete date parts -> composed independently")
    void requestKeepsDiscreteDateParts() throws Exception {
        when(reportService.requestReport(any()))
                .thenReturn(new ReportResult(true, MSG_SUBMIT_CUSTOM));

        TransactionReportRequest custom = new TransactionReportRequest(
                null, null, "Y",
                "03", "05", "2024",
                "03", "09", "2024",
                "Y", PfKeyAction.ENTER);

        mockMvc.perform(post(ENDPOINT)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(custom)))
                .andExpect(status().isOk());

        ArgumentCaptor<ReportRequest> captor = ArgumentCaptor.forClass(ReportRequest.class);
        verify(reportService).requestReport(captor.capture());
        ReportRequest sent = captor.getValue();
        assertThat(sent.startDate()).isEqualTo("2024-03-05");
        assertThat(sent.endDate()).isEqualTo("2024-03-09");
    }

    // =====================================================================
    // Helpers
    // =====================================================================

    /**
     * Builds a {@link TransactionReportRequest} for the non-custom cases, leaving the six
     * custom-range date parts unset (only the report-type markers, confirmation flag, and
     * pressed key are relevant).
     *
     * @param monthly the monthly-report marker (or {@code null})
     * @param yearly  the yearly-report marker (or {@code null})
     * @param custom  the custom-range marker (or {@code null})
     * @param confirm the confirmation flag (or {@code null} to leave blank)
     * @param action  the pressed attention key (or {@code null} for the default-Enter path)
     * @return the assembled request DTO
     */
    private static TransactionReportRequest request(String monthly, String yearly, String custom,
                                                    String confirm, PfKeyAction action) {
        return new TransactionReportRequest(
                monthly, yearly, custom,
                null, null, null,
                null, null, null,
                confirm, action);
    }

    /**
     * Serializes a request DTO to its JSON body using the application {@link ObjectMapper}, so
     * every request exercises the real {@link TransactionReportRequest} field contract.
     *
     * @param request the request DTO to serialize
     * @return the JSON request body
     * @throws Exception if serialization fails
     */
    private String body(TransactionReportRequest request) throws Exception {
        return objectMapper.writeValueAsString(request);
    }
}
