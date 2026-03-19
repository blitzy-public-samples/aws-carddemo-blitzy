/*
 * ReportControllerCoverageTest.java — Coverage tests for ReportController
 * Tests GET /api/reports and POST /api/reports endpoints
 */
package com.cardemo.controller;

import com.cardemo.common.exception.ValidationException;
import com.cardemo.config.SecurityConfig;
import com.cardemo.service.online.ReportService;

import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Coverage tests for ReportController — exercises GET /api/reports
 * (getAvailableReportTypes) and POST /api/reports (submitReport)
 * including all exception handling branches.
 */
@WebMvcTest(ReportController.class)
@AutoConfigureMockMvc
@Import(SecurityConfig.class)
class ReportControllerCoverageTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private ReportService reportService;

    @Autowired
    private ObjectMapper objectMapper;

    // ---- GET /api/reports ----

    @Test
    @WithMockUser
    @DisplayName("GET /api/reports returns 3 report types")
    void getReportTypesReturnsThreeTypes() throws Exception {
        mockMvc.perform(get("/api/reports"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(3))
                .andExpect(jsonPath("$[0].code").value("MONTHLY"))
                .andExpect(jsonPath("$[1].code").value("YEARLY"))
                .andExpect(jsonPath("$[2].code").value("CUSTOM"));
    }

    @Test
    @WithMockUser
    @DisplayName("GET /api/reports returns correct names and descriptions")
    void getReportTypesReturnsNamesAndDescriptions() throws Exception {
        mockMvc.perform(get("/api/reports"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].name").value("Monthly (Current Month)"))
                .andExpect(jsonPath("$[1].name").value("Yearly (Current Year)"))
                .andExpect(jsonPath("$[2].name").value("Custom (Date Range)"))
                .andExpect(jsonPath("$[0].description").exists())
                .andExpect(jsonPath("$[1].description").exists())
                .andExpect(jsonPath("$[2].description").exists());
    }

    // ---- POST /api/reports — MONTHLY success ----

    @Test
    @WithMockUser
    @DisplayName("POST monthly report returns success result")
    void submitMonthlyReportSuccess() throws Exception {
        ReportService.ReportResult result = new ReportService.ReportResult();
        result.setSubmitted(true);
        result.setReportName("Monthly Transaction Report");
        result.setMessage("Report submitted successfully");
        result.setError(false);

        when(reportService.processEnterKey(any(ReportService.ReportRequest.class)))
                .thenReturn(result);

        String body = "{\"reportType\":\"MONTHLY\",\"confirmed\":false}";
        mockMvc.perform(post("/api/reports")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.submitted").value(true))
                .andExpect(jsonPath("$.reportName").value("Monthly Transaction Report"));
    }

    // ---- POST /api/reports — YEARLY success ----

    @Test
    @WithMockUser
    @DisplayName("POST yearly report returns success result")
    void submitYearlyReportSuccess() throws Exception {
        ReportService.ReportResult result = new ReportService.ReportResult();
        result.setSubmitted(true);
        result.setReportName("Yearly Transaction Report");
        result.setError(false);

        when(reportService.processEnterKey(any(ReportService.ReportRequest.class)))
                .thenReturn(result);

        String body = "{\"reportType\":\"YEARLY\",\"confirmed\":false}";
        mockMvc.perform(post("/api/reports")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.submitted").value(true));
    }

    // ---- POST /api/reports — CUSTOM with dates ----

    @Test
    @WithMockUser
    @DisplayName("POST custom report with date range returns success")
    void submitCustomReportWithDatesSuccess() throws Exception {
        ReportService.ReportResult result = new ReportService.ReportResult();
        result.setSubmitted(true);
        result.setError(false);
        result.setStartDate("01/01/2026");
        result.setEndDate("03/31/2026");

        when(reportService.processEnterKey(any(ReportService.ReportRequest.class)))
                .thenReturn(result);

        String body = "{\"reportType\":\"CUSTOM\",\"startDate\":\"01/01/2026\","
                + "\"endDate\":\"03/31/2026\",\"confirmed\":false}";
        mockMvc.perform(post("/api/reports")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.submitted").value(true));
    }

    // ---- POST /api/reports — confirmation flow ----

    @Test
    @WithMockUser
    @DisplayName("POST report with confirmation required and confirmed=true completes submission")
    void submitReportConfirmationFlow() throws Exception {
        // First call returns confirmation required
        ReportService.ReportResult confirmResult = new ReportService.ReportResult();
        confirmResult.setConfirmationRequired(true);
        confirmResult.setError(false);

        // Second call (submitJobToIntrdr) returns submitted
        ReportService.ReportResult submittedResult = new ReportService.ReportResult();
        submittedResult.setSubmitted(true);
        submittedResult.setError(false);
        submittedResult.setReportName("Monthly Transaction Report");

        when(reportService.processEnterKey(any(ReportService.ReportRequest.class)))
                .thenReturn(confirmResult);
        when(reportService.submitJobToIntrdr(any(ReportService.ReportRequest.class)))
                .thenReturn(submittedResult);

        String body = "{\"reportType\":\"MONTHLY\",\"confirmed\":true}";
        mockMvc.perform(post("/api/reports")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.submitted").value(true));
    }

    // ---- POST /api/reports — service returns error ----

    @Test
    @WithMockUser
    @DisplayName("POST report returns 400 when service returns error result")
    void submitReportServiceError() throws Exception {
        ReportService.ReportResult errorResult = new ReportService.ReportResult();
        errorResult.setError(true);
        errorResult.setMessage("Invalid report type selected");

        when(reportService.processEnterKey(any(ReportService.ReportRequest.class)))
                .thenReturn(errorResult);

        String body = "{\"reportType\":\"MONTHLY\",\"confirmed\":false}";
        mockMvc.perform(post("/api/reports")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest());
    }

    // ---- POST /api/reports — ValidationException ----

    @Test
    @WithMockUser
    @DisplayName("POST report returns 400 on ValidationException")
    void submitReportValidationException() throws Exception {
        when(reportService.processEnterKey(any(ReportService.ReportRequest.class)))
                .thenThrow(new ValidationException("Start date is required for custom reports"));

        String body = "{\"reportType\":\"CUSTOM\",\"confirmed\":false}";
        mockMvc.perform(post("/api/reports")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest());
    }

    // ---- POST /api/reports — unexpected exception ----

    @Test
    @WithMockUser
    @DisplayName("POST report returns 500 on unexpected exception")
    void submitReportUnexpectedException() throws Exception {
        when(reportService.processEnterKey(any(ReportService.ReportRequest.class)))
                .thenThrow(new RuntimeException("Database connection lost"));

        String body = "{\"reportType\":\"MONTHLY\",\"confirmed\":false}";
        mockMvc.perform(post("/api/reports")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isInternalServerError());
    }

    // ---- POST /api/reports — custom with blank dates ----

    @Test
    @WithMockUser
    @DisplayName("POST custom report with blank dates handles gracefully")
    void submitCustomReportBlankDates() throws Exception {
        ReportService.ReportResult result = new ReportService.ReportResult();
        result.setSubmitted(false);
        result.setError(false);

        when(reportService.processEnterKey(any(ReportService.ReportRequest.class)))
                .thenReturn(result);

        String body = "{\"reportType\":\"CUSTOM\",\"startDate\":\"\",\"endDate\":\"\","
                + "\"confirmed\":false}";
        mockMvc.perform(post("/api/reports")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk());
    }

    // ---- POST /api/reports — custom with null dates ----

    @Test
    @WithMockUser
    @DisplayName("POST custom report with null dates handles gracefully")
    void submitCustomReportNullDates() throws Exception {
        ReportService.ReportResult result = new ReportService.ReportResult();
        result.setSubmitted(false);
        result.setError(false);

        when(reportService.processEnterKey(any(ReportService.ReportRequest.class)))
                .thenReturn(result);

        String body = "{\"reportType\":\"CUSTOM\",\"confirmed\":false}";
        mockMvc.perform(post("/api/reports")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk());
    }

    // ---- ReportSubmitRequest DTO field coverage ----

    @Test
    @WithMockUser
    @DisplayName("POST report exercises all ReportSubmitRequest fields")
    void submitReportExercisesAllDtoFields() throws Exception {
        ReportService.ReportResult result = new ReportService.ReportResult();
        result.setError(false);
        result.setSubmitted(true);

        when(reportService.processEnterKey(any(ReportService.ReportRequest.class)))
                .thenReturn(result);

        // Exercise all fields: reportType, startDate, endDate, confirmed
        String body = "{\"reportType\":\"CUSTOM\",\"startDate\":\"03/15/2026\","
                + "\"endDate\":\"03/31/2026\",\"confirmed\":true}";
        mockMvc.perform(post("/api/reports")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk());
    }

    // ---- ReportTypeInfo field coverage ----

    @Test
    @WithMockUser
    @DisplayName("ReportTypeInfo fields are all present in GET response")
    void reportTypeInfoFieldsCovered() throws Exception {
        mockMvc.perform(get("/api/reports"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].code").isString())
                .andExpect(jsonPath("$[0].name").isString())
                .andExpect(jsonPath("$[0].description").isString());
    }
}
