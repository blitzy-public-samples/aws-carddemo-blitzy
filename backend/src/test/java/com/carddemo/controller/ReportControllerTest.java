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
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.carddemo.controller;

import com.carddemo.dto.response.ReportMenuResponse;
import com.carddemo.exception.GlobalExceptionHandler;
import com.carddemo.service.ReportMenuService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;
import org.springframework.batch.core.JobExecution;
import org.springframework.batch.core.JobInstance;
import org.springframework.batch.core.JobParameters;
import org.springframework.batch.core.JobParametersBuilder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.context.annotation.Import;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.time.LocalDate;
import java.time.LocalTime;

import static org.hamcrest.Matchers.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import org.springframework.context.annotation.Configuration;

/**
 * JUnit 5 test class for ReportController REST endpoint validation.
 * 
 * <p>Tests transformation from COBOL CICS transaction program CORPT00C.cbl to Spring Boot REST API.
 * Validates GET /api/reports endpoint for report menu display and POST /api/reports/* endpoints
 * for report generation with various types (monthly, yearly, custom date range).</p>
 * 
 * <p><b>COBOL Source Program:</b> app/cbl/CORPT00C.cbl - Report Menu Transaction (CR00)</p>
 * <p><b>BMS Screen Definition:</b> app/bms/CORPT00.bms - Report menu 3270 screen layout</p>
 * 
 * <p><b>Test Coverage:</b></p>
 * <ul>
 *   <li>Report menu retrieval (GET /api/reports) with role-based filtering</li>
 *   <li>Monthly report submission (POST /api/reports/monthly) with confirmation validation</li>
 *   <li>Yearly report submission (POST /api/reports/yearly) with confirmation validation</li>
 *   <li>Custom report submission (POST /api/reports/custom) with date range validation</li>
 *   <li>Authorization testing: ROLE_ADMIN required, ROLE_USER forbidden</li>
 *   <li>Parameter validation: missing fields, invalid dates, future dates, date range logic</li>
 *   <li>Error handling: account not found, invalid report type, service exceptions</li>
 *   <li>Response time assertions for report request processing</li>
 * </ul>
 * 
 * <p><b>Security Model:</b></p>
 * <p>Tests preserve exact authorization patterns from COBOL USRSEC file where only administrative
 * users (USER-TYPE='A') can access report generation. Tests verify ROLE_ADMIN requirement using
 * @WithMockUser annotation and validate HTTP 403 Forbidden for non-admin users.</p>
 * 
 * <p><b>Functional Equivalence:</b></p>
 * <p>Test scenarios replicate COBOL business logic including:</p>
 * <ul>
 *   <li>Monthly report: Current month date range (lines 213-238 of CORPT00C.cbl)</li>
 *   <li>Yearly report: Current year full date range (lines 239-255 of CORPT00C.cbl)</li>
 *   <li>Custom report: User-specified date validation (lines 256-436 of CORPT00C.cbl)</li>
 *   <li>Confirmation requirement: 'Y' flag before submission (lines 464-474 of CORPT00C.cbl)</li>
 * </ul>
 * 
 * @author CardDemo Migration Team
 * @version 1.0
 * @since 2024-01-01
 * @see com.carddemo.controller.ReportController
 * @see com.carddemo.service.ReportMenuService
 */
@WebMvcTest(ReportController.class)
@AutoConfigureMockMvc(addFilters = false)
@ContextConfiguration(classes = {ReportController.class, GlobalExceptionHandler.class, ReportControllerTest.TestSecurityConfig.class})
@DisplayName("ReportController REST Endpoint Tests - CORPT00C.cbl Transformation Validation")
public class ReportControllerTest {

    /**
     * Test security configuration to enable method-level security for @PreAuthorize annotations.
     * Filter-level security is disabled via addFilters=false, but method-level security (@PreAuthorize)
     * is enabled to test authorization logic at the controller method level.
     */
    @Configuration
    @EnableMethodSecurity
    static class TestSecurityConfig {
        // Enables method-level security for @PreAuthorize checks in tests
    }

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private ReportMenuService reportMenuService;

    private ReportMenuResponse validReportMenuResponse;
    private JobExecution mockJobExecution;

    /**
     * Test fixture setup before each test execution.
     * Initializes mock response data matching COBOL screen output structure.
     */
    @BeforeEach
    public void setUp() {
        // Initialize valid report menu response matching CORPT0AO structure
        validReportMenuResponse = new ReportMenuResponse();
        validReportMenuResponse.setTransactionName("CR00");
        validReportMenuResponse.setTitle01("CardDemo - Transaction Reports");
        validReportMenuResponse.setTitle02("Report Generation Menu");
        validReportMenuResponse.setProgramName("CORPT00C");
        validReportMenuResponse.setCurrentDate(LocalDate.now());
        validReportMenuResponse.setCurrentTime(LocalTime.now());
        validReportMenuResponse.setMonthlyReportFlag("N");
        validReportMenuResponse.setYearlyReportFlag("N");
        validReportMenuResponse.setCustomReportFlag("N");
        validReportMenuResponse.setConfirmationFlag("N");

        // Initialize mock JobExecution for batch job submission tests
        JobInstance jobInstance = new JobInstance(1001L, "TransactionAggregationJob");
        JobParameters jobParameters = new JobParametersBuilder()
                .addString("reportType", "monthly")
                .addLong("timestamp", System.currentTimeMillis())
                .toJobParameters();
        mockJobExecution = new JobExecution(jobInstance, 1001L, jobParameters);
        mockJobExecution.setStatus(org.springframework.batch.core.BatchStatus.COMPLETED);
        mockJobExecution.setExitStatus(org.springframework.batch.core.ExitStatus.COMPLETED);
    }

    /**
     * Test GET /api/reports endpoint returns report menu for admin users.
     * 
     * <p>Validates transformation of COBOL MAIN-PARA paragraph (lines 163-202) which displayed
     * BMS map CORPT00M on 3270 terminal. Verifies REST endpoint returns JSON representation
     * of report menu with available report types.</p>
     * 
     * <p><b>COBOL Equivalent:</b> MAIN-PARA → SEND MAP('CORPT00') (lines 195-206)</p>
     */
    @Test
    @WithMockUser(username = "admin", roles = {"ADMIN"})
    @DisplayName("GET /api/reports - Admin User - Returns Report Menu")
    public void testGetReportMenu_AdminUser_ReturnsReportMenu() throws Exception {
        // Arrange
        when(reportMenuService.getAvailableReportTypes()).thenReturn(validReportMenuResponse);

        // Act & Assert
        long startTime = System.currentTimeMillis();
        
        mockMvc.perform(get("/api/reports")
                .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.transactionName", is("CR00")))
                .andExpect(jsonPath("$.title01", is("CardDemo - Transaction Reports")))
                .andExpect(jsonPath("$.title02", is("Report Generation Menu")))
                .andExpect(jsonPath("$.programName", is("CORPT00C")))
                .andExpect(jsonPath("$.currentDate", notNullValue()))
                .andExpect(jsonPath("$.currentTime", notNullValue()));

        long responseTime = System.currentTimeMillis() - startTime;
        
        // Verify response time under 200ms per Section 0.9 performance requirements
        assert responseTime < 200 : "Response time " + responseTime + "ms exceeds 200ms requirement";

        // Verify service method was called
        verify(reportMenuService, times(1)).getAvailableReportTypes();
    }

    /**
     * Test GET /api/reports with regular user returns 403 Forbidden.
     * 
     * <p>Validates authorization enforcement where only ROLE_ADMIN users can access report
     * generation functions. Maps COBOL security check where only USER-TYPE='A' administrative
     * users have report access.</p>
     */
    @Test
    @WithMockUser(username = "user", roles = {"USER"})
    @DisplayName("GET /api/reports - Regular User - Returns 403 Forbidden")
    public void testGetReportMenu_RegularUser_ReturnsForbidden() throws Exception {
        // Act & Assert
        mockMvc.perform(get("/api/reports")
                .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isForbidden());

        // Verify service was never called due to authorization failure
        verify(reportMenuService, never()).getAvailableReportTypes();
    }

    /**
     * Test GET /api/reports without authentication returns 401 Unauthorized.
     */
    @Test
    @DisplayName("GET /api/reports - No Authentication - Returns 401 Unauthorized")
    public void testGetReportMenu_NoAuthentication_ReturnsUnauthorized() throws Exception {
        // Act & Assert
        mockMvc.perform(get("/api/reports")
                .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isUnauthorized());

        // Verify service was never called
        verify(reportMenuService, never()).getAvailableReportTypes();
    }

    /**
     * Test POST /api/reports/monthly with valid confirmation submits monthly report.
     * 
     * <p>Validates transformation of COBOL monthly report logic (lines 217-238) which calculated
     * current month date range and submitted JCL batch job via TDQ.</p>
     * 
     * <p><b>COBOL Equivalent:</b> WHEN MONTHLYI = 'Y' → SUBMIT-JOB-TO-INTRDR</p>
     */
    @Test
    @WithMockUser(username = "admin", roles = {"ADMIN"})
    @DisplayName("POST /api/reports/monthly - Valid Confirmation - Submits Monthly Report")
    public void testSubmitMonthlyReport_ValidConfirmation_ReturnsSuccess() throws Exception {
        // Arrange
        when(reportMenuService.submitMonthlyReport(eq("Y"))).thenReturn(mockJobExecution);

        // Act & Assert
        long startTime = System.currentTimeMillis();
        
        mockMvc.perform(post("/api/reports/monthly")
                .param("confirmationFlag", "Y")
                .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.jobId", is(mockJobExecution.getId().intValue())))
                .andExpect(jsonPath("$.jobStatus", is("COMPLETED")));

        long responseTime = System.currentTimeMillis() - startTime;
        assert responseTime < 200 : "Response time " + responseTime + "ms exceeds 200ms requirement";

        // Verify service method was called with correct parameter
        verify(reportMenuService, times(1)).submitMonthlyReport(eq("Y"));
    }

    /**
     * Test POST /api/reports/monthly with missing confirmation returns 400 Bad Request.
     * 
     * <p>Validates COBOL confirmation prompt logic (lines 464-474) where CONFIRMI must be 'Y'
     * before job submission proceeds.</p>
     */
    @Test
    @WithMockUser(username = "admin", roles = {"ADMIN"})
    @DisplayName("POST /api/reports/monthly - Missing Confirmation - Returns 400 Bad Request")
    public void testSubmitMonthlyReport_MissingConfirmation_ReturnsBadRequest() throws Exception {
        // Act & Assert
        mockMvc.perform(post("/api/reports/monthly")
                .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isBadRequest());

        // Verify service was never called
        verify(reportMenuService, never()).submitMonthlyReport(anyString());
    }

    /**
     * Test POST /api/reports/monthly with invalid confirmation returns 400 Bad Request.
     */
    @Test
    @WithMockUser(username = "admin", roles = {"ADMIN"})
    @DisplayName("POST /api/reports/monthly - Invalid Confirmation - Returns 400 Bad Request")
    public void testSubmitMonthlyReport_InvalidConfirmation_ReturnsBadRequest() throws Exception {
        // Arrange
        when(reportMenuService.submitMonthlyReport(eq("N")))
                .thenThrow(new IllegalArgumentException("Confirmation flag must be 'Y' to submit report"));

        // Act & Assert
        mockMvc.perform(post("/api/reports/monthly")
                .param("confirmationFlag", "N")
                .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isBadRequest());

        verify(reportMenuService, times(1)).submitMonthlyReport(eq("N"));
    }

    /**
     * Test POST /api/reports/yearly with valid confirmation submits yearly report.
     * 
     * <p>Validates transformation of COBOL yearly report logic (lines 239-255) which calculated
     * full year date range (January 1 to December 31) and submitted batch job.</p>
     * 
     * <p><b>COBOL Equivalent:</b> WHEN YEARLYI = 'Y' → SUBMIT-JOB-TO-INTRDR</p>
     */
    @Test
    @WithMockUser(username = "admin", roles = {"ADMIN"})
    @DisplayName("POST /api/reports/yearly - Valid Confirmation - Submits Yearly Report")
    public void testSubmitYearlyReport_ValidConfirmation_ReturnsSuccess() throws Exception {
        // Arrange
        when(reportMenuService.submitYearlyReport(eq("Y"))).thenReturn(mockJobExecution);

        // Act & Assert
        mockMvc.perform(post("/api/reports/yearly")
                .param("confirmationFlag", "Y")
                .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.jobId", is(mockJobExecution.getId().intValue())))
                .andExpect(jsonPath("$.jobStatus", is("COMPLETED")));

        verify(reportMenuService, times(1)).submitYearlyReport(eq("Y"));
    }

    /**
     * Test POST /api/reports/yearly with regular user returns 403 Forbidden.
     */
    @Test
    @WithMockUser(username = "user", roles = {"USER"})
    @DisplayName("POST /api/reports/yearly - Regular User - Returns 403 Forbidden")
    public void testSubmitYearlyReport_RegularUser_ReturnsForbidden() throws Exception {
        // Act & Assert
        mockMvc.perform(post("/api/reports/yearly")
                .param("confirmationFlag", "Y")
                .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isForbidden());

        verify(reportMenuService, never()).submitYearlyReport(anyString());
    }

    /**
     * Test POST /api/reports/custom with valid date range submits custom report.
     * 
     * <p>Validates transformation of COBOL custom report logic (lines 256-436) which validated
     * user-entered start and end dates, checked date ranges, and submitted batch job with
     * date parameters.</p>
     * 
     * <p><b>COBOL Equivalent:</b> WHEN CUSTOMI = 'Y' → date validation → SUBMIT-JOB-TO-INTRDR</p>
     */
    @Test
    @WithMockUser(username = "admin", roles = {"ADMIN"})
    @DisplayName("POST /api/reports/custom - Valid Date Range - Submits Custom Report")
    public void testSubmitCustomReport_ValidDateRange_ReturnsSuccess() throws Exception {
        // Arrange
        LocalDate startDate = LocalDate.now().minusMonths(1);
        LocalDate endDate = LocalDate.now();
        
        when(reportMenuService.submitCustomReport(any(Integer.class), any(Integer.class), any(Integer.class), 
                any(Integer.class), any(Integer.class), any(Integer.class), eq("Y")))
                .thenReturn(mockJobExecution);

        // Act & Assert
        mockMvc.perform(post("/api/reports/custom")
                .param("startYear", String.valueOf(startDate.getYear()))
                .param("startMonth", String.valueOf(startDate.getMonthValue()))
                .param("startDay", String.valueOf(startDate.getDayOfMonth()))
                .param("endYear", String.valueOf(endDate.getYear()))
                .param("endMonth", String.valueOf(endDate.getMonthValue()))
                .param("endDay", String.valueOf(endDate.getDayOfMonth()))
                .param("confirmationFlag", "Y")
                .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.jobId", is(mockJobExecution.getId().intValue())))
                .andExpect(jsonPath("$.jobStatus", is("COMPLETED")));

        verify(reportMenuService, times(1)).submitCustomReport(any(Integer.class), any(Integer.class), any(Integer.class), 
                any(Integer.class), any(Integer.class), any(Integer.class), eq("Y"));
    }

    /**
     * Test POST /api/reports/custom with missing parameters returns 400 Bad Request.
     */
    @Test
    @WithMockUser(username = "admin", roles = {"ADMIN"})
    @DisplayName("POST /api/reports/custom - Missing Parameters - Returns 400 Bad Request")
    public void testSubmitCustomReport_MissingParameters_ReturnsBadRequest() throws Exception {
        LocalDate date = LocalDate.now();
        
        // Act & Assert - Missing endDay
        mockMvc.perform(post("/api/reports/custom")
                .param("startYear", String.valueOf(date.getYear()))
                .param("startMonth", String.valueOf(date.getMonthValue()))
                .param("startDay", String.valueOf(date.getDayOfMonth()))
                .param("endYear", String.valueOf(date.getYear()))
                .param("endMonth", String.valueOf(date.getMonthValue()))
                .param("confirmationFlag", "Y")
                .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isBadRequest());

        // Act & Assert - Missing startDay
        mockMvc.perform(post("/api/reports/custom")
                .param("startYear", String.valueOf(date.getYear()))
                .param("startMonth", String.valueOf(date.getMonthValue()))
                .param("endYear", String.valueOf(date.getYear()))
                .param("endMonth", String.valueOf(date.getMonthValue()))
                .param("endDay", String.valueOf(date.getDayOfMonth()))
                .param("confirmationFlag", "Y")
                .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isBadRequest());

        verify(reportMenuService, never()).submitCustomReport(any(), any(), any(), any(), any(), any(), anyString());
    }

    /**
     * Test POST /api/reports/custom with invalid date formats returns 400 Bad Request.
     * 
     * <p>Validates date format validation matching COBOL WS-DATE-FORMAT (YYYY-MM-DD).</p>
     */
    @Test
    @WithMockUser(username = "admin", roles = {"ADMIN"})
    @DisplayName("POST /api/reports/custom - Invalid Date Format - Returns 400 Bad Request")
    public void testSubmitCustomReport_InvalidDateFormat_ReturnsBadRequest() throws Exception {
        LocalDate endDate = LocalDate.now();
        
        // Arrange - Mock service to throw IllegalArgumentException for invalid date
        when(reportMenuService.submitCustomReport(
                eq(2024), eq(13), eq(15), 
                eq(endDate.getYear()), eq(endDate.getMonthValue()), eq(endDate.getDayOfMonth()), 
                eq("Y")))
                .thenThrow(new IllegalArgumentException("Invalid month: 13"));
        
        // Act & Assert - Invalid month (13 is not a valid month)
        mockMvc.perform(post("/api/reports/custom")
                .param("startYear", "2024")
                .param("startMonth", "13") // Invalid month
                .param("startDay", "15")
                .param("endYear", String.valueOf(endDate.getYear()))
                .param("endMonth", String.valueOf(endDate.getMonthValue()))
                .param("endDay", String.valueOf(endDate.getDayOfMonth()))
                .param("confirmationFlag", "Y")
                .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isBadRequest());

        verify(reportMenuService, times(1)).submitCustomReport(
                eq(2024), eq(13), eq(15), 
                eq(endDate.getYear()), eq(endDate.getMonthValue()), eq(endDate.getDayOfMonth()), 
                eq("Y"));
    }

    /**
     * Test POST /api/reports/custom with future start date returns 400 Bad Request.
     * 
     * <p>Validates COBOL date validation logic (lines 392-426) where future dates are rejected
     * for historical transaction reports.</p>
     */
    @Test
    @WithMockUser(username = "admin", roles = {"ADMIN"})
    @DisplayName("POST /api/reports/custom - Future Start Date - Returns 400 Bad Request")
    public void testSubmitCustomReport_FutureStartDate_ReturnsBadRequest() throws Exception {
        // Arrange
        LocalDate futureDate = LocalDate.now().plusDays(1);
        LocalDate endDate = LocalDate.now().plusDays(7);
        
        when(reportMenuService.submitCustomReport(any(Integer.class), any(Integer.class), any(Integer.class), 
                any(Integer.class), any(Integer.class), any(Integer.class), eq("Y")))
                .thenThrow(new IllegalArgumentException("Start date cannot be in the future for historical reports"));

        // Act & Assert
        mockMvc.perform(post("/api/reports/custom")
                .param("startYear", String.valueOf(futureDate.getYear()))
                .param("startMonth", String.valueOf(futureDate.getMonthValue()))
                .param("startDay", String.valueOf(futureDate.getDayOfMonth()))
                .param("endYear", String.valueOf(endDate.getYear()))
                .param("endMonth", String.valueOf(endDate.getMonthValue()))
                .param("endDay", String.valueOf(endDate.getDayOfMonth()))
                .param("confirmationFlag", "Y")
                .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isBadRequest());

        verify(reportMenuService, times(1)).submitCustomReport(any(Integer.class), any(Integer.class), any(Integer.class), 
                any(Integer.class), any(Integer.class), any(Integer.class), eq("Y"));
    }

    /**
     * Test POST /api/reports/custom with start date after end date returns 400 Bad Request.
     * 
     * <p>Validates COBOL date range validation where WS-START-DATE must be less than or equal
     * to WS-END-DATE.</p>
     */
    @Test
    @WithMockUser(username = "admin", roles = {"ADMIN"})
    @DisplayName("POST /api/reports/custom - Start Date After End Date - Returns 400 Bad Request")
    public void testSubmitCustomReport_StartDateAfterEndDate_ReturnsBadRequest() throws Exception {
        // Arrange
        LocalDate startDate = LocalDate.now();
        LocalDate endDate = LocalDate.now().minusDays(7);
        
        when(reportMenuService.submitCustomReport(any(Integer.class), any(Integer.class), any(Integer.class), 
                any(Integer.class), any(Integer.class), any(Integer.class), eq("Y")))
                .thenThrow(new IllegalArgumentException("Start date must be before or equal to end date"));

        // Act & Assert
        mockMvc.perform(post("/api/reports/custom")
                .param("startYear", String.valueOf(startDate.getYear()))
                .param("startMonth", String.valueOf(startDate.getMonthValue()))
                .param("startDay", String.valueOf(startDate.getDayOfMonth()))
                .param("endYear", String.valueOf(endDate.getYear()))
                .param("endMonth", String.valueOf(endDate.getMonthValue()))
                .param("endDay", String.valueOf(endDate.getDayOfMonth()))
                .param("confirmationFlag", "Y")
                .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isBadRequest());

        verify(reportMenuService, times(1)).submitCustomReport(any(Integer.class), any(Integer.class), any(Integer.class), 
                any(Integer.class), any(Integer.class), any(Integer.class), eq("Y"));
    }

    /**
     * Test POST /api/reports/custom with date range exceeding one year returns 400 Bad Request.
     * 
     * <p>Business rule validation for reasonable report date ranges.</p>
     */
    @Test
    @WithMockUser(username = "admin", roles = {"ADMIN"})
    @DisplayName("POST /api/reports/custom - Date Range Exceeds One Year - Returns 400 Bad Request")
    public void testSubmitCustomReport_DateRangeExceedsOneYear_ReturnsBadRequest() throws Exception {
        // Arrange
        LocalDate startDate = LocalDate.now().minusYears(2);
        LocalDate endDate = LocalDate.now();
        
        when(reportMenuService.submitCustomReport(any(Integer.class), any(Integer.class), any(Integer.class), 
                any(Integer.class), any(Integer.class), any(Integer.class), eq("Y")))
                .thenThrow(new IllegalArgumentException("Date range cannot exceed one year for performance reasons"));

        // Act & Assert
        mockMvc.perform(post("/api/reports/custom")
                .param("startYear", String.valueOf(startDate.getYear()))
                .param("startMonth", String.valueOf(startDate.getMonthValue()))
                .param("startDay", String.valueOf(startDate.getDayOfMonth()))
                .param("endYear", String.valueOf(endDate.getYear()))
                .param("endMonth", String.valueOf(endDate.getMonthValue()))
                .param("endDay", String.valueOf(endDate.getDayOfMonth()))
                .param("confirmationFlag", "Y")
                .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isBadRequest());

        verify(reportMenuService, times(1)).submitCustomReport(any(Integer.class), any(Integer.class), any(Integer.class), 
                any(Integer.class), any(Integer.class), any(Integer.class), eq("Y"));
    }

    /**
     * Test report menu retrieval handles service exception gracefully.
     * 
     * <p>Validates error handling transformation from COBOL WS-ERR-FLG and WS-MESSAGE to
     * Spring exception handling with HTTP 500 Internal Server Error.</p>
     */
    @Test
    @WithMockUser(username = "admin", roles = {"ADMIN"})
    @DisplayName("GET /api/reports - Service Exception - Returns 500 Internal Server Error")
    public void testGetReportMenu_ServiceException_ReturnsInternalServerError() throws Exception {
        // Arrange
        when(reportMenuService.getAvailableReportTypes())
                .thenThrow(new RuntimeException("Database connection error"));

        // Act & Assert
        mockMvc.perform(get("/api/reports")
                .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.errorMessage", containsString("Unable to retrieve report menu")));

        verify(reportMenuService, times(1)).getAvailableReportTypes();
    }

    /**
     * Test monthly report submission handles job execution failure.
     */
    @Test
    @WithMockUser(username = "admin", roles = {"ADMIN"})
    @DisplayName("POST /api/reports/monthly - Job Execution Failure - Returns 500 Internal Server Error")
    public void testSubmitMonthlyReport_JobExecutionFailure_ReturnsInternalServerError() throws Exception {
        // Arrange
        when(reportMenuService.submitMonthlyReport(eq("Y")))
                .thenThrow(new RuntimeException("Job execution failed: Unable to launch batch job"));

        // Act & Assert
        mockMvc.perform(post("/api/reports/monthly")
                .param("confirmationFlag", "Y")
                .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isInternalServerError());

        verify(reportMenuService, times(1)).submitMonthlyReport(eq("Y"));
    }

    /**
     * Test custom report handles null date parameters gracefully.
     */
    @Test
    @WithMockUser(username = "admin", roles = {"ADMIN"})
    @DisplayName("POST /api/reports/custom - Null Date Parameters - Returns 400 Bad Request")
    public void testSubmitCustomReport_NullDateParameters_ReturnsBadRequest() throws Exception {
        // Act & Assert
        mockMvc.perform(post("/api/reports/custom")
                .param("confirmationFlag", "Y")
                .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isBadRequest());

        verify(reportMenuService, never()).submitCustomReport(any(), any(), any(), any(), any(), any(), anyString());
    }

    /**
     * Test response time for report menu retrieval is under 200ms.
     * 
     * <p>Performance requirement validation per Section 0.9: "Transaction response times
     * remain under 200ms at 95th percentile".</p>
     */
    @Test
    @WithMockUser(username = "admin", roles = {"ADMIN"})
    @DisplayName("GET /api/reports - Response Time - Under 200ms")
    public void testGetReportMenu_ResponseTime_Under200ms() throws Exception {
        // Arrange
        when(reportMenuService.getAvailableReportTypes()).thenReturn(validReportMenuResponse);

        // Act
        long startTime = System.nanoTime();
        
        MvcResult result = mockMvc.perform(get("/api/reports")
                .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andReturn();

        long endTime = System.nanoTime();
        long responseTimeMs = (endTime - startTime) / 1_000_000;

        // Assert
        assert responseTimeMs < 200 : String.format(
                "Response time %dms exceeds 200ms performance requirement", responseTimeMs);
    }

    /**
     * Test monthly report submission response time is under 200ms.
     */
    @Test
    @WithMockUser(username = "admin", roles = {"ADMIN"})
    @DisplayName("POST /api/reports/monthly - Response Time - Under 200ms")
    public void testSubmitMonthlyReport_ResponseTime_Under200ms() throws Exception {
        // Arrange
        when(reportMenuService.submitMonthlyReport(eq("Y"))).thenReturn(mockJobExecution);

        // Act
        long startTime = System.nanoTime();
        
        MvcResult result = mockMvc.perform(post("/api/reports/monthly")
                .param("confirmationFlag", "Y")
                .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andReturn();

        long endTime = System.nanoTime();
        long responseTimeMs = (endTime - startTime) / 1_000_000;

        // Assert
        assert responseTimeMs < 200 : String.format(
                "Response time %dms exceeds 200ms performance requirement", responseTimeMs);
    }

    /**
     * Test custom report submission response time is under 200ms.
     */
    @Test
    @WithMockUser(username = "admin", roles = {"ADMIN"})
    @DisplayName("POST /api/reports/custom - Response Time - Under 200ms")
    public void testSubmitCustomReport_ResponseTime_Under200ms() throws Exception {
        // Arrange
        LocalDate startDate = LocalDate.now().minusMonths(1);
        LocalDate endDate = LocalDate.now();
        
        when(reportMenuService.submitCustomReport(any(Integer.class), any(Integer.class), any(Integer.class), 
                any(Integer.class), any(Integer.class), any(Integer.class), eq("Y")))
                .thenReturn(mockJobExecution);

        // Act
        long startTime = System.nanoTime();
        
        MvcResult result = mockMvc.perform(post("/api/reports/custom")
                .param("startYear", String.valueOf(startDate.getYear()))
                .param("startMonth", String.valueOf(startDate.getMonthValue()))
                .param("startDay", String.valueOf(startDate.getDayOfMonth()))
                .param("endYear", String.valueOf(endDate.getYear()))
                .param("endMonth", String.valueOf(endDate.getMonthValue()))
                .param("endDay", String.valueOf(endDate.getDayOfMonth()))
                .param("confirmationFlag", "Y")
                .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andReturn();

        long endTime = System.nanoTime();
        long responseTimeMs = (endTime - startTime) / 1_000_000;

        // Assert
        assert responseTimeMs < 200 : String.format(
                "Response time %dms exceeds 200ms performance requirement", responseTimeMs);
    }

    /**
     * Test report menu returns proper HTTP headers.
     */
    @Test
    @WithMockUser(username = "admin", roles = {"ADMIN"})
    @DisplayName("GET /api/reports - HTTP Headers - Proper Content Type")
    public void testGetReportMenu_HttpHeaders_ProperContentType() throws Exception {
        // Arrange
        when(reportMenuService.getAvailableReportTypes()).thenReturn(validReportMenuResponse);

        // Act & Assert
        mockMvc.perform(get("/api/reports")
                .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(header().exists("Content-Type"));

        verify(reportMenuService, times(1)).getAvailableReportTypes();
    }

    /**
     * Test report submission with empty confirmation flag returns 400 Bad Request.
     */
    @Test
    @WithMockUser(username = "admin", roles = {"ADMIN"})
    @DisplayName("POST /api/reports/monthly - Empty Confirmation Flag - Returns 400 Bad Request")
    public void testSubmitMonthlyReport_EmptyConfirmationFlag_ReturnsBadRequest() throws Exception {
        // Arrange - Mock service to throw IllegalArgumentException for empty confirmation
        when(reportMenuService.submitMonthlyReport(""))
                .thenThrow(new IllegalArgumentException("Please confirm to print the Monthly report..."));

        // Act & Assert
        mockMvc.perform(post("/api/reports/monthly")
                .param("confirmationFlag", "")
                .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isBadRequest());

        verify(reportMenuService, times(1)).submitMonthlyReport("");
    }

    /**
     * Test custom report with valid same start and end date is accepted.
     * 
     * <p>Edge case validation: Single day report generation.</p>
     */
    @Test
    @WithMockUser(username = "admin", roles = {"ADMIN"})
    @DisplayName("POST /api/reports/custom - Same Start and End Date - Accepted")
    public void testSubmitCustomReport_SameStartEndDate_Accepted() throws Exception {
        // Arrange
        LocalDate sameDate = LocalDate.now().minusDays(7);
        
        when(reportMenuService.submitCustomReport(any(Integer.class), any(Integer.class), any(Integer.class), 
                any(Integer.class), any(Integer.class), any(Integer.class), eq("Y")))
                .thenReturn(mockJobExecution);

        // Act & Assert
        mockMvc.perform(post("/api/reports/custom")
                .param("startYear", String.valueOf(sameDate.getYear()))
                .param("startMonth", String.valueOf(sameDate.getMonthValue()))
                .param("startDay", String.valueOf(sameDate.getDayOfMonth()))
                .param("endYear", String.valueOf(sameDate.getYear()))
                .param("endMonth", String.valueOf(sameDate.getMonthValue()))
                .param("endDay", String.valueOf(sameDate.getDayOfMonth()))
                .param("confirmationFlag", "Y")
                .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.jobId", is(mockJobExecution.getId().intValue())));

        verify(reportMenuService, times(1)).submitCustomReport(any(Integer.class), any(Integer.class), any(Integer.class), 
                any(Integer.class), any(Integer.class), any(Integer.class), eq("Y"));
    }

    /**
     * Test report menu returns current date and time matching COBOL screen display.
     * 
     * <p>Validates CURDATEO and CURTIMEO field population from CORPT0AO structure.</p>
     */
    @Test
    @WithMockUser(username = "admin", roles = {"ADMIN"})
    @DisplayName("GET /api/reports - Current Date and Time - Populated")
    public void testGetReportMenu_CurrentDateAndTime_Populated() throws Exception {
        // Arrange
        when(reportMenuService.getAvailableReportTypes()).thenReturn(validReportMenuResponse);

        // Act & Assert
        mockMvc.perform(get("/api/reports")
                .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.currentDate", notNullValue()))
                .andExpect(jsonPath("$.currentTime", notNullValue()))
                .andExpect(jsonPath("$.currentDate", matchesPattern("\\d{4}-\\d{2}-\\d{2}")))
                .andExpect(jsonPath("$.currentTime", matchesPattern("\\d{2}:\\d{2}:\\d{2}")));

        verify(reportMenuService, times(1)).getAvailableReportTypes();
    }
}
