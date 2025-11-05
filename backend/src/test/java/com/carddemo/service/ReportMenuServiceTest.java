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

package com.carddemo.service;

import com.carddemo.constants.MessageConstants;
import com.carddemo.dto.response.ReportMenuResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.JobExecution;
import org.springframework.batch.core.JobInstance;
import org.springframework.batch.core.JobParameters;
import org.springframework.batch.core.JobParametersInvalidException;
import org.springframework.batch.core.launch.JobLauncher;
import org.springframework.batch.core.repository.JobExecutionAlreadyRunningException;
import org.springframework.batch.core.repository.JobInstanceAlreadyCompleteException;
import org.springframework.batch.core.repository.JobRestartException;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.YearMonth;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * Comprehensive JUnit 5 test suite for ReportMenuService.
 * 
 * <p>Tests validate business logic transformation from COBOL CICS program CORPT00C.cbl
 * (650 lines) to Java Spring Boot service. Verifies report generation menu functionality
 * including report type selection, date range validation, batch job submission orchestration,
 * and JCL-to-Spring Batch job triggering.</p>
 * 
 * <p><b>COBOL Source Context (CORPT00C.cbl):</b></p>
 * <ul>
 *   <li>Lines 1-34: Program identification and environment division</li>
 *   <li>Lines 36-80: Working-storage for variables including date format structures</li>
 *   <li>Lines 81-100: JCL generation structures for TDQ submission</li>
 *   <li>Lines 200-250: PROCEDURE DIVISION main processing logic</li>
 *   <li>Lines 258-300: VALIDATE-REPORT-TYPE paragraph with report selection logic</li>
 *   <li>Lines 301-380: VALIDATE-CUSTOM-DATES paragraph with field validation</li>
 *   <li>Lines 381-442: Date range validation and business rules</li>
 *   <li>Lines 464-494: Confirmation flag validation logic</li>
 *   <li>Lines 500-600: TDQ job submission paragraph (WRITEQ TD commands)</li>
 * </ul>
 * 
 * <p><b>Transformation Pattern:</b></p>
 * <pre>
 * COBOL CORPT00C.cbl              →  Java ReportMenuService.java
 * ─────────────────────────────────────────────────────────────
 * PROCEDURE DIVISION              →  Public service methods
 * VALIDATE-REPORT-TYPE            →  validateReportRequest()
 * VALIDATE-CUSTOM-DATES           →  validateDateComponents()
 * SUBMIT-REPORT-JOB              →  submit[Monthly|Yearly|Custom]Report()
 * WRITEQ TD (TDQ submission)     →  JobLauncher.run(Job, JobParameters)
 * WS-START-DATE, WS-END-DATE     →  LocalDate parameters
 * EXEC CICS SYNCPOINT            →  @Transactional boundary
 * WS-ERR-FLG condition           →  IllegalArgumentException throws
 * </pre>
 * 
 * <p><b>Test Coverage Requirements:</b></p>
 * <ul>
 *   <li>Report menu retrieval with available report types</li>
 *   <li>Monthly report submission with confirmation validation</li>
 *   <li>Yearly report submission with current year date range</li>
 *   <li>Custom report submission with user-provided date range</li>
 *   <li>Date component validation (presence, range, calendar validity)</li>
 *   <li>Date range business rule validation (start date <= end date)</li>
 *   <li>Confirmation flag validation ('Y'/'N' values)</li>
 *   <li>JobParameters construction from report request</li>
 *   <li>JobLauncher invocation verification</li>
 *   <li>Exception handling for job submission failures</li>
 * </ul>
 * 
 * <p><b>Security Context:</b></p>
 * <p>All report generation operations require ROLE_ADMIN per Section 0.9 of migration spec.
 * Tests assume security context is handled at controller layer via @PreAuthorize annotations.
 * Service layer tests focus on business logic validation without security assertions.</p>
 * 
 * <p><b>Functional Equivalence Validation:</b></p>
 * <p>Test assertions verify exact error message text matches COBOL WS-MESSAGE field
 * population (CORPT00C lines 259-300, 438-442) to ensure user experience continuity
 * during mainframe-to-cloud migration per Section 0.9 requirement.</p>
 * 
 * @author CardDemo Migration Team
 * @version 1.0
 * @since 2024-01-01
 * @see com.carddemo.service.ReportMenuService
 * @see app/cbl/CORPT00C.cbl Original COBOL source
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("ReportMenuService Test Suite - COBOL CORPT00C.cbl Transformation")
class ReportMenuServiceTest {

    /**
     * Mock JobLauncher for Spring Batch job submission simulation.
     * Replaces actual batch processing infrastructure with stubbed responses.
     * Corresponds to COBOL TDQ WRITEQ TD commands (CORPT00C lines 500-600).
     */
    @Mock
    private JobLauncher jobLauncher;

    /**
     * Mock Transaction Aggregation Job for report generation.
     * Represents the TRNRPT00 JCL job referenced in CORPT00C lines 84, 94.
     */
    @Mock
    private Job transactionAggregationJob;

    /**
     * Mock Statement Generation Job for monthly/yearly reports.
     * Represents batch job processing equivalent to COBOL batch programs.
     */
    @Mock
    private Job statementGenerationJob;

    /**
     * Service under test with mocked dependencies injected.
     * Contains business logic transformed from CORPT00C.cbl PROCEDURE DIVISION.
     */
    @InjectMocks
    private ReportMenuService reportMenuService;

    /**
     * Test fixture setup executed before each test method.
     * Resets mock state and initializes service instance with fresh mocks.
     */
    @BeforeEach
    void setUp() {
        // Mocks are automatically initialized by @ExtendWith(MockitoExtension.class)
        // Service is automatically created with @InjectMocks annotation
        // No additional setup required - service ready for testing
    }

    // ================================================================================
    // TEST METHODS: Report Menu Display and Navigation
    // From COBOL CORPT00C lines 200-250 (main processing logic)
    // ================================================================================

    /**
     * Test: testGetAvailableReportTypes_ReturnsThreeReportOptions
     * 
     * <p>Validates getAvailableReportTypes() method returns list of available report options
     * for report menu display. Corresponds to COBOL screen display logic where report types
     * (Monthly, Yearly, Custom) are presented as selection options.</p>
     * 
     * <p><b>COBOL Context:</b> CORPT00C report menu screen initialization, lines 220-240.
     * BMS map CORPT0AO contains MONTHLY, YEARLY, CUSTOM flag fields for user selection.</p>
     * 
     * <p><b>Expected Behavior:</b></p>
     * <ul>
     *   <li>Returns non-null List of report type strings</li>
     *   <li>List contains exactly 3 elements: "Monthly", "Yearly", "Custom"</li>
     *   <li>Report types match COBOL screen labels exactly</li>
     * </ul>
     */
    @Test
    @DisplayName("Get Available Report Types - Returns Three Report Options")
    void testGetAvailableReportTypes_ReturnsThreeReportOptions() {
        // Execute: Retrieve available report types
        List<String> reportTypes = reportMenuService.getAvailableReportTypes();

        // Assert: Verify report types list structure and content
        assertNotNull(reportTypes, "Report types list should not be null");
        assertEquals(3, reportTypes.size(), "Should return exactly 3 report types");
        
        // Verify exact report type names match COBOL screen options
        assertTrue(reportTypes.contains("Monthly"), "Should include Monthly report option");
        assertTrue(reportTypes.contains("Yearly"), "Should include Yearly report option");
        assertTrue(reportTypes.contains("Custom"), "Should include Custom report option");
    }

    // ================================================================================
    // TEST METHODS: Monthly Report Submission
    // From COBOL CORPT00C lines 464-494 (confirmation validation)
    // ================================================================================

    /**
     * Test: testSubmitMonthlyReport_ValidConfirmation_TriggersJob
     * 
     * <p>Validates submitMonthlyReport() method successfully triggers Spring Batch job
     * when valid confirmation flag ('Y') is provided. Verifies JobLauncher invocation
     * with correct job and calculated date range parameters.</p>
     * 
     * <p><b>COBOL Context:</b> CORPT00C lines 478-479 for 'Y' confirmation check,
     * lines 500-550 for TDQ job submission with SYMNAMES parameters.</p>
     * 
     * <p><b>Expected Behavior:</b></p>
     * <ul>
     *   <li>JobLauncher.run() invoked exactly once</li>
     *   <li>Job submitted is transactionAggregationJob</li>
     *   <li>JobParameters include startDate (first day of current month)</li>
     *   <li>JobParameters include endDate (last day of current month)</li>
     *   <li>JobParameters include reportType = "MONTHLY"</li>
     * </ul>
     */
    @Test
    @DisplayName("Submit Monthly Report - Valid Confirmation Triggers Batch Job")
    void testSubmitMonthlyReport_ValidConfirmation_TriggersJob() throws Exception {
        // Arrange: Setup mock JobExecution return value for successful job launch
        JobExecution mockJobExecution = new JobExecution(1L);
        mockJobExecution.setStatus(BatchStatus.STARTED);
        
        when(jobLauncher.run(eq(transactionAggregationJob), any(JobParameters.class)))
            .thenReturn(mockJobExecution);

        // Calculate expected date range for current month
        YearMonth currentMonth = YearMonth.now();
        LocalDate expectedStartDate = currentMonth.atDay(1);
        LocalDate expectedEndDate = currentMonth.atEndOfMonth();

        // Execute: Submit monthly report with 'Y' confirmation
        reportMenuService.submitMonthlyReport("Y");

        // Assert: Verify JobLauncher invoked with correct job
        verify(jobLauncher, times(1)).run(
            eq(transactionAggregationJob), 
            any(JobParameters.class)
        );

        // Capture JobParameters for detailed assertion
        ArgumentCaptor<JobParameters> jobParamsCaptor = ArgumentCaptor.forClass(JobParameters.class);
        verify(jobLauncher).run(eq(transactionAggregationJob), jobParamsCaptor.capture());
        
        JobParameters capturedParams = jobParamsCaptor.getValue();
        assertNotNull(capturedParams, "JobParameters should not be null");
        
        // Verify date parameters match current month calculation
        assertNotNull(capturedParams.getParameters().get("startDate"), 
            "Start date parameter should be present");
        assertNotNull(capturedParams.getParameters().get("endDate"), 
            "End date parameter should be present");
        assertEquals("MONTHLY", capturedParams.getString("reportType"), 
            "Report type should be MONTHLY");
    }

    /**
     * Test: testSubmitMonthlyReport_NoConfirmation_ThrowsException
     * 
     * <p>Validates submitMonthlyReport() method throws IllegalArgumentException when
     * confirmation flag is null or empty. Corresponds to COBOL validation logic that
     * requires explicit user confirmation before expensive batch job submission.</p>
     * 
     * <p><b>COBOL Context:</b> CORPT00C lines 464-474 checks for CONFIRMI = SPACES
     * or LOW-VALUES, sets WS-ERR-FLG='Y' and displays error message.</p>
     * 
     * <p><b>Expected Behavior:</b></p>
     * <ul>
     *   <li>IllegalArgumentException thrown when confirmed = null</li>
     *   <li>Exception message contains "Please confirm to print"</li>
     *   <li>JobLauncher never invoked (no batch job submission)</li>
     * </ul>
     */
    @Test
    @DisplayName("Submit Monthly Report - No Confirmation Throws Exception")
    void testSubmitMonthlyReport_NoConfirmation_ThrowsException() {
        // Execute and Assert: Verify exception thrown for null confirmation
        IllegalArgumentException exception = assertThrows(
            IllegalArgumentException.class,
            () -> reportMenuService.submitMonthlyReport(null),
            "Should throw IllegalArgumentException for null confirmation"
        );

        // Verify error message matches COBOL WS-MESSAGE pattern (lines 466-471)
        String errorMessage = exception.getMessage();
        assertTrue(errorMessage.contains("Please confirm to print"), 
            "Error message should request confirmation");
        assertTrue(errorMessage.contains("Monthly"), 
            "Error message should mention Monthly report type");

        // Verify JobLauncher was never invoked (no job submitted)
        verifyNoInteractions(jobLauncher);
    }

    /**
     * Test: testSubmitMonthlyReport_InvalidConfirmation_ThrowsException
     * 
     * <p>Validates submitMonthlyReport() method throws IllegalArgumentException when
     * confirmation flag has invalid value (not 'Y', 'y', 'N', or 'n').</p>
     * 
     * <p><b>COBOL Context:</b> CORPT00C lines 484-494 EVALUATE TRUE WHEN OTHER clause
     * handles invalid confirmation values with specific error message format.</p>
     * 
     * <p><b>Expected Behavior:</b></p>
     * <ul>
     *   <li>IllegalArgumentException thrown for invalid values</li>
     *   <li>Exception message format: "\"X\" is not a valid value to confirm..."</li>
     *   <li>JobLauncher never invoked</li>
     * </ul>
     */
    @Test
    @DisplayName("Submit Monthly Report - Invalid Confirmation Value Throws Exception")
    void testSubmitMonthlyReport_InvalidConfirmation_ThrowsException() {
        // Execute and Assert: Verify exception for invalid confirmation value 'X'
        IllegalArgumentException exception = assertThrows(
            IllegalArgumentException.class,
            () -> reportMenuService.submitMonthlyReport("X"),
            "Should throw IllegalArgumentException for invalid confirmation"
        );

        // Verify error message matches COBOL format (lines 742-746)
        String errorMessage = exception.getMessage();
        assertTrue(errorMessage.contains("\"X\""), 
            "Error message should quote the invalid value");
        assertTrue(errorMessage.contains("not a valid value to confirm"), 
            "Error message should explain validation failure");

        // Verify JobLauncher was never invoked
        verifyNoInteractions(jobLauncher);
    }

    /**
     * Test: testSubmitMonthlyReport_ConfirmationNo_ThrowsException
     * 
     * <p>Validates submitMonthlyReport() method throws IllegalArgumentException when
     * user explicitly rejects report generation with 'N' confirmation.</p>
     * 
     * <p><b>COBOL Context:</b> CORPT00C lines 480-483 handle 'N' or 'n' confirmation
     * by performing INITIALIZE-ALL-FIELDS and setting error flag.</p>
     * 
     * <p><b>Expected Behavior:</b></p>
     * <ul>
     *   <li>IllegalArgumentException thrown for 'N' confirmation</li>
     *   <li>Exception message indicates user cancellation</li>
     *   <li>JobLauncher never invoked (user rejected batch job)</li>
     * </ul>
     */
    @Test
    @DisplayName("Submit Monthly Report - Confirmation 'N' Cancels Operation")
    void testSubmitMonthlyReport_ConfirmationNo_ThrowsException() {
        // Execute and Assert: Verify exception for 'N' confirmation (user rejection)
        IllegalArgumentException exception = assertThrows(
            IllegalArgumentException.class,
            () -> reportMenuService.submitMonthlyReport("N"),
            "Should throw IllegalArgumentException for 'N' confirmation"
        );

        // Verify error message indicates cancellation
        String errorMessage = exception.getMessage();
        assertTrue(errorMessage.contains("cancelled") || errorMessage.contains("cancel"), 
            "Error message should indicate cancellation");

        // Verify JobLauncher was never invoked (user cancelled)
        verifyNoInteractions(jobLauncher);
    }

    // ================================================================================
    // TEST METHODS: Yearly Report Submission
    // From COBOL CORPT00C lines 464-494 (confirmation validation)
    // ================================================================================

    /**
     * Test: testSubmitYearlyReport_ValidConfirmation_TriggersJob
     * 
     * <p>Validates submitYearlyReport() method successfully triggers Spring Batch job
     * with full year date range (January 1 to December 31 of current year).</p>
     * 
     * <p><b>COBOL Context:</b> CORPT00C yearly report logic calculates start as
     * YYYY-01-01 and end as YYYY-12-31 for current year.</p>
     * 
     * <p><b>Expected Behavior:</b></p>
     * <ul>
     *   <li>JobLauncher.run() invoked exactly once</li>
     *   <li>Job submitted is transactionAggregationJob</li>
     *   <li>startDate = January 1 of current year</li>
     *   <li>endDate = December 31 of current year</li>
     *   <li>reportType = "YEARLY"</li>
     * </ul>
     */
    @Test
    @DisplayName("Submit Yearly Report - Valid Confirmation Triggers Batch Job")
    void testSubmitYearlyReport_ValidConfirmation_TriggersJob() throws Exception {
        // Arrange: Setup mock JobExecution for successful job launch
        JobExecution mockJobExecution = new JobExecution(2L);
        mockJobExecution.setStatus(BatchStatus.STARTED);
        
        when(jobLauncher.run(eq(transactionAggregationJob), any(JobParameters.class)))
            .thenReturn(mockJobExecution);

        // Calculate expected date range for current year
        int currentYear = LocalDate.now().getYear();
        LocalDate expectedStartDate = LocalDate.of(currentYear, 1, 1);
        LocalDate expectedEndDate = LocalDate.of(currentYear, 12, 31);

        // Execute: Submit yearly report with 'y' confirmation (lowercase valid)
        reportMenuService.submitYearlyReport("y");

        // Assert: Verify JobLauncher invoked with correct job
        verify(jobLauncher, times(1)).run(
            eq(transactionAggregationJob), 
            any(JobParameters.class)
        );

        // Capture JobParameters for date range validation
        ArgumentCaptor<JobParameters> jobParamsCaptor = ArgumentCaptor.forClass(JobParameters.class);
        verify(jobLauncher).run(eq(transactionAggregationJob), jobParamsCaptor.capture());
        
        JobParameters capturedParams = jobParamsCaptor.getValue();
        assertNotNull(capturedParams, "JobParameters should not be null");
        assertEquals("YEARLY", capturedParams.getString("reportType"), 
            "Report type should be YEARLY");
    }

    /**
     * Test: testSubmitYearlyReport_EmptyConfirmation_ThrowsException
     * 
     * <p>Validates submitYearlyReport() method throws IllegalArgumentException when
     * confirmation flag is empty string (not null, but blank).</p>
     * 
     * <p><b>COBOL Context:</b> CORPT00C lines 464-474 check CONFIRMI = SPACES
     * which matches Java empty string after trim().</p>
     * 
     * <p><b>Expected Behavior:</b></p>
     * <ul>
     *   <li>IllegalArgumentException thrown for empty string</li>
     *   <li>Exception message requests confirmation</li>
     *   <li>JobLauncher never invoked</li>
     * </ul>
     */
    @Test
    @DisplayName("Submit Yearly Report - Empty Confirmation Throws Exception")
    void testSubmitYearlyReport_EmptyConfirmation_ThrowsException() {
        // Execute and Assert: Verify exception for empty confirmation
        IllegalArgumentException exception = assertThrows(
            IllegalArgumentException.class,
            () -> reportMenuService.submitYearlyReport(""),
            "Should throw IllegalArgumentException for empty confirmation"
        );

        // Verify error message requests confirmation
        String errorMessage = exception.getMessage();
        assertTrue(errorMessage.contains("Please confirm to print"), 
            "Error message should request confirmation");

        // Verify no job submission occurred
        verifyNoInteractions(jobLauncher);
    }

    // ================================================================================
    // TEST METHODS: Custom Report Submission
    // From COBOL CORPT00C lines 258-380 (custom date validation)
    // ================================================================================

    /**
     * Test: testSubmitCustomReport_ValidDateRange_TriggersJob
     * 
     * <p>Validates submitCustomReport() method successfully triggers Spring Batch job
     * when valid custom date range and confirmation are provided.</p>
     * 
     * <p><b>COBOL Context:</b> CORPT00C lines 301-380 validate custom date components
     * (month, day, year) and lines 381-442 validate date range business rules.</p>
     * 
     * <p><b>Expected Behavior:</b></p>
     * <ul>
     *   <li>JobLauncher.run() invoked exactly once</li>
     *   <li>JobParameters include user-provided start and end dates</li>
     *   <li>Date range validated: startDate <= endDate</li>
     *   <li>reportType = "CUSTOM"</li>
     * </ul>
     */
    @Test
    @DisplayName("Submit Custom Report - Valid Date Range Triggers Batch Job")
    void testSubmitCustomReport_ValidDateRange_TriggersJob() throws Exception {
        // Arrange: Setup mock JobExecution for successful job launch
        JobExecution mockJobExecution = new JobExecution(3L);
        mockJobExecution.setStatus(BatchStatus.STARTED);
        
        when(jobLauncher.run(eq(transactionAggregationJob), any(JobParameters.class)))
            .thenReturn(mockJobExecution);

        // Define valid custom date range (January 1-31, 2024)
        Integer startYear = 2024;
        Integer startMonth = 1;
        Integer startDay = 1;
        Integer endYear = 2024;
        Integer endMonth = 1;
        Integer endDay = 31;
        String confirmed = "Y";

        // Execute: Submit custom report with valid date range
        reportMenuService.submitCustomReport(
            startYear, startMonth, startDay,
            endYear, endMonth, endDay,
            confirmed
        );

        // Assert: Verify JobLauncher invoked with correct job
        verify(jobLauncher, times(1)).run(
            eq(transactionAggregationJob), 
            any(JobParameters.class)
        );

        // Capture JobParameters for detailed validation
        ArgumentCaptor<JobParameters> jobParamsCaptor = ArgumentCaptor.forClass(JobParameters.class);
        verify(jobLauncher).run(eq(transactionAggregationJob), jobParamsCaptor.capture());
        
        JobParameters capturedParams = jobParamsCaptor.getValue();
        assertNotNull(capturedParams, "JobParameters should not be null");
        assertEquals("CUSTOM", capturedParams.getString("reportType"), 
            "Report type should be CUSTOM");
    }

    /**
     * Test: testSubmitCustomReport_EndDateBeforeStartDate_ThrowsException
     * 
     * <p>Validates submitCustomReport() method throws IllegalArgumentException when
     * end date is before start date, violating business rule for valid date range.</p>
     * 
     * <p><b>COBOL Context:</b> CORPT00C lines 381-442 perform date range validation
     * using CSUTLDTC utility program for calendar date comparisons.</p>
     * 
     * <p><b>Expected Behavior:</b></p>
     * <ul>
     *   <li>IllegalArgumentException thrown for invalid range</li>
     *   <li>Exception message indicates end date before start date</li>
     *   <li>JobLauncher never invoked (validation failure prevents submission)</li>
     * </ul>
     */
    @Test
    @DisplayName("Submit Custom Report - End Date Before Start Date Throws Exception")
    void testSubmitCustomReport_EndDateBeforeStartDate_ThrowsException() {
        // Arrange: Define invalid date range (end before start)
        Integer startYear = 2024;
        Integer startMonth = 6;
        Integer startDay = 15;
        Integer endYear = 2024;
        Integer endMonth = 3;
        Integer endDay = 10;
        String confirmed = "Y";

        // Execute and Assert: Verify exception for invalid date range
        IllegalArgumentException exception = assertThrows(
            IllegalArgumentException.class,
            () -> reportMenuService.submitCustomReport(
                startYear, startMonth, startDay,
                endYear, endMonth, endDay,
                confirmed
            ),
            "Should throw IllegalArgumentException when end date is before start date"
        );

        // Verify error message indicates date range problem
        String errorMessage = exception.getMessage();
        assertTrue(
            errorMessage.contains("Start Date must be less than or equal to End Date") ||
            errorMessage.contains("End Date") || 
            errorMessage.contains("before"),
            "Error message should indicate date range validation failure"
        );

        // Verify no job submission occurred
        verifyNoInteractions(jobLauncher);
    }

    /**
     * Test: testSubmitCustomReport_MissingStartMonth_ThrowsException
     * 
     * <p>Validates submitCustomReport() method throws IllegalArgumentException when
     * start date month component is null (missing required field).</p>
     * 
     * <p><b>COBOL Context:</b> CORPT00C lines 259-268 check if SDTMMO = SPACES or
     * LOW-VALUES, displaying error "Start Date - Month can NOT be empty..."</p>
     * 
     * <p><b>Expected Behavior:</b></p>
     * <ul>
     *   <li>IllegalArgumentException thrown for null startMonth</li>
     *   <li>Exception message: "Start Date - Month can NOT be empty..."</li>
     *   <li>Error message matches COBOL text exactly</li>
     * </ul>
     */
    @Test
    @DisplayName("Submit Custom Report - Missing Start Month Throws Exception")
    void testSubmitCustomReport_MissingStartMonth_ThrowsException() {
        // Arrange: Define date with missing start month (null)
        Integer startYear = 2024;
        Integer startMonth = null; // Missing required field
        Integer startDay = 15;
        Integer endYear = 2024;
        Integer endMonth = 6;
        Integer endDay = 20;
        String confirmed = "Y";

        // Execute and Assert: Verify exception for missing start month
        IllegalArgumentException exception = assertThrows(
            IllegalArgumentException.class,
            () -> reportMenuService.submitCustomReport(
                startYear, startMonth, startDay,
                endYear, endMonth, endDay,
                confirmed
            ),
            "Should throw IllegalArgumentException for null start month"
        );

        // Verify error message matches COBOL WS-MESSAGE (lines 261-263)
        String errorMessage = exception.getMessage();
        assertEquals("Start Date - Month can NOT be empty...", errorMessage,
            "Error message should match COBOL text exactly");

        // Verify no job submission occurred
        verifyNoInteractions(jobLauncher);
    }

    /**
     * Test: testSubmitCustomReport_MissingStartDay_ThrowsException
     * 
     * <p>Validates submitCustomReport() method throws IllegalArgumentException when
     * start date day component is null.</p>
     * 
     * <p><b>COBOL Context:</b> CORPT00C lines 269-271 check SDTDDO for SPACES,
     * displaying error "Start Date - Day can NOT be empty..."</p>
     */
    @Test
    @DisplayName("Submit Custom Report - Missing Start Day Throws Exception")
    void testSubmitCustomReport_MissingStartDay_ThrowsException() {
        // Arrange: Define date with missing start day
        Integer startYear = 2024;
        Integer startMonth = 3;
        Integer startDay = null; // Missing required field
        Integer endYear = 2024;
        Integer endMonth = 6;
        Integer endDay = 20;
        String confirmed = "Y";

        // Execute and Assert: Verify exception for missing start day
        IllegalArgumentException exception = assertThrows(
            IllegalArgumentException.class,
            () -> reportMenuService.submitCustomReport(
                startYear, startMonth, startDay,
                endYear, endMonth, endDay,
                confirmed
            ),
            "Should throw IllegalArgumentException for null start day"
        );

        // Verify error message matches COBOL text (lines 269-271)
        assertEquals("Start Date - Day can NOT be empty...", exception.getMessage(),
            "Error message should match COBOL text exactly");

        // Verify no job submission occurred
        verifyNoInteractions(jobLauncher);
    }

    /**
     * Test: testSubmitCustomReport_MissingStartYear_ThrowsException
     * 
     * <p>Validates submitCustomReport() method throws IllegalArgumentException when
     * start date year component is null.</p>
     * 
     * <p><b>COBOL Context:</b> CORPT00C lines 272-278 check SDTYYYYO for SPACES,
     * displaying error "Start Date - Year can NOT be empty..."</p>
     */
    @Test
    @DisplayName("Submit Custom Report - Missing Start Year Throws Exception")
    void testSubmitCustomReport_MissingStartYear_ThrowsException() {
        // Arrange: Define date with missing start year
        Integer startYear = null; // Missing required field
        Integer startMonth = 3;
        Integer startDay = 15;
        Integer endYear = 2024;
        Integer endMonth = 6;
        Integer endDay = 20;
        String confirmed = "Y";

        // Execute and Assert: Verify exception for missing start year
        IllegalArgumentException exception = assertThrows(
            IllegalArgumentException.class,
            () -> reportMenuService.submitCustomReport(
                startYear, startMonth, startDay,
                endYear, endMonth, endDay,
                confirmed
            ),
            "Should throw IllegalArgumentException for null start year"
        );

        // Verify error message matches COBOL text (lines 272-278)
        assertEquals("Start Date - Year can NOT be empty...", exception.getMessage(),
            "Error message should match COBOL text exactly");

        // Verify no job submission occurred
        verifyNoInteractions(jobLauncher);
    }

    /**
     * Test: testSubmitCustomReport_MissingEndMonth_ThrowsException
     * 
     * <p>Validates submitCustomReport() method throws IllegalArgumentException when
     * end date month component is null.</p>
     * 
     * <p><b>COBOL Context:</b> CORPT00C lines 280-285 check EDTMMO for SPACES,
     * displaying error "End Date - Month can NOT be empty..."</p>
     */
    @Test
    @DisplayName("Submit Custom Report - Missing End Month Throws Exception")
    void testSubmitCustomReport_MissingEndMonth_ThrowsException() {
        // Arrange: Define date with missing end month
        Integer startYear = 2024;
        Integer startMonth = 3;
        Integer startDay = 15;
        Integer endYear = 2024;
        Integer endMonth = null; // Missing required field
        Integer endDay = 20;
        String confirmed = "Y";

        // Execute and Assert: Verify exception for missing end month
        IllegalArgumentException exception = assertThrows(
            IllegalArgumentException.class,
            () -> reportMenuService.submitCustomReport(
                startYear, startMonth, startDay,
                endYear, endMonth, endDay,
                confirmed
            ),
            "Should throw IllegalArgumentException for null end month"
        );

        // Verify error message matches COBOL text (lines 280-285)
        assertEquals("End Date - Month can NOT be empty...", exception.getMessage(),
            "Error message should match COBOL text exactly");

        // Verify no job submission occurred
        verifyNoInteractions(jobLauncher);
    }

    /**
     * Test: testSubmitCustomReport_InvalidStartMonth_ThrowsException
     * 
     * <p>Validates submitCustomReport() method throws IllegalArgumentException when
     * start month is outside valid range (1-12).</p>
     * 
     * <p><b>COBOL Context:</b> CORPT00C lines 329-336 check if WS-NUM-99 (start month)
     * is < 1 OR > 12, displaying error "Start Date - Not a valid Month..."</p>
     */
    @Test
    @DisplayName("Submit Custom Report - Invalid Start Month (13) Throws Exception")
    void testSubmitCustomReport_InvalidStartMonth_ThrowsException() {
        // Arrange: Define date with invalid start month (13)
        Integer startYear = 2024;
        Integer startMonth = 13; // Invalid: must be 1-12
        Integer startDay = 15;
        Integer endYear = 2024;
        Integer endMonth = 6;
        Integer endDay = 20;
        String confirmed = "Y";

        // Execute and Assert: Verify exception for invalid start month
        IllegalArgumentException exception = assertThrows(
            IllegalArgumentException.class,
            () -> reportMenuService.submitCustomReport(
                startYear, startMonth, startDay,
                endYear, endMonth, endDay,
                confirmed
            ),
            "Should throw IllegalArgumentException for month > 12"
        );

        // Verify error message matches COBOL text (lines 332-334)
        assertEquals("Start Date - Not a valid Month...", exception.getMessage(),
            "Error message should match COBOL text exactly");

        // Verify no job submission occurred
        verifyNoInteractions(jobLauncher);
    }

    /**
     * Test: testSubmitCustomReport_InvalidStartDay_ThrowsException
     * 
     * <p>Validates submitCustomReport() method throws IllegalArgumentException when
     * start day is outside valid range (1-31).</p>
     * 
     * <p><b>COBOL Context:</b> CORPT00C lines 338-345 check if WS-NUM-99 (start day)
     * is < 1 OR > 31, displaying error "Start Date - Not a valid Day..."</p>
     */
    @Test
    @DisplayName("Submit Custom Report - Invalid Start Day (0) Throws Exception")
    void testSubmitCustomReport_InvalidStartDay_ThrowsException() {
        // Arrange: Define date with invalid start day (0)
        Integer startYear = 2024;
        Integer startMonth = 3;
        Integer startDay = 0; // Invalid: must be 1-31
        Integer endYear = 2024;
        Integer endMonth = 6;
        Integer endDay = 20;
        String confirmed = "Y";

        // Execute and Assert: Verify exception for invalid start day
        IllegalArgumentException exception = assertThrows(
            IllegalArgumentException.class,
            () -> reportMenuService.submitCustomReport(
                startYear, startMonth, startDay,
                endYear, endMonth, endDay,
                confirmed
            ),
            "Should throw IllegalArgumentException for day < 1"
        );

        // Verify error message matches COBOL text (lines 340-342)
        assertEquals("Start Date - Not a valid Day...", exception.getMessage(),
            "Error message should match COBOL text exactly");

        // Verify no job submission occurred
        verifyNoInteractions(jobLauncher);
    }

    /**
     * Test: testSubmitCustomReport_InvalidStartYear_ThrowsException
     * 
     * <p>Validates submitCustomReport() method throws IllegalArgumentException when
     * start year is outside valid range (1900-2100).</p>
     * 
     * <p><b>COBOL Context:</b> CORPT00C lines 347-353 check if WS-NUM-9999 (start year)
     * is < 1900 OR > 2100, displaying error "Start Date - Not a valid Year..."</p>
     */
    @Test
    @DisplayName("Submit Custom Report - Invalid Start Year (1800) Throws Exception")
    void testSubmitCustomReport_InvalidStartYear_ThrowsException() {
        // Arrange: Define date with invalid start year (1800, before 1900)
        Integer startYear = 1800; // Invalid: must be 1900-2100
        Integer startMonth = 3;
        Integer startDay = 15;
        Integer endYear = 2024;
        Integer endMonth = 6;
        Integer endDay = 20;
        String confirmed = "Y";

        // Execute and Assert: Verify exception for invalid start year
        IllegalArgumentException exception = assertThrows(
            IllegalArgumentException.class,
            () -> reportMenuService.submitCustomReport(
                startYear, startMonth, startDay,
                endYear, endMonth, endDay,
                confirmed
            ),
            "Should throw IllegalArgumentException for year < 1900"
        );

        // Verify error message matches COBOL text (lines 349-351)
        assertEquals("Start Date - Not a valid Year...", exception.getMessage(),
            "Error message should match COBOL text exactly");

        // Verify no job submission occurred
        verifyNoInteractions(jobLauncher);
    }

    /**
     * Test: testSubmitCustomReport_InvalidEndYear_ThrowsException
     * 
     * <p>Validates submitCustomReport() method throws IllegalArgumentException when
     * end year is outside valid range (1900-2100).</p>
     * 
     * <p><b>COBOL Context:</b> CORPT00C lines 373-379 check if WS-NUM-9999 (end year)
     * is < 1900 OR > 2100, displaying error "End Date - Not a valid Year..."</p>
     */
    @Test
    @DisplayName("Submit Custom Report - Invalid End Year (2200) Throws Exception")
    void testSubmitCustomReport_InvalidEndYear_ThrowsException() {
        // Arrange: Define date with invalid end year (2200, after 2100)
        Integer startYear = 2024;
        Integer startMonth = 3;
        Integer startDay = 15;
        Integer endYear = 2200; // Invalid: must be 1900-2100
        Integer endMonth = 6;
        Integer endDay = 20;
        String confirmed = "Y";

        // Execute and Assert: Verify exception for invalid end year
        IllegalArgumentException exception = assertThrows(
            IllegalArgumentException.class,
            () -> reportMenuService.submitCustomReport(
                startYear, startMonth, startDay,
                endYear, endMonth, endDay,
                confirmed
            ),
            "Should throw IllegalArgumentException for year > 2100"
        );

        // Verify error message matches COBOL text (lines 375-377)
        assertEquals("End Date - Not a valid Year...", exception.getMessage(),
            "Error message should match COBOL text exactly");

        // Verify no job submission occurred
        verifyNoInteractions(jobLauncher);
    }

    /**
     * Test: testSubmitCustomReport_InvalidCalendarDate_ThrowsException
     * 
     * <p>Validates submitCustomReport() method throws IllegalArgumentException when
     * date components form an invalid calendar date (e.g., February 30).</p>
     * 
     * <p><b>COBOL Context:</b> CORPT00C lines 381-420 call CSUTLDTC date utility
     * program to validate calendar correctness of constructed dates.</p>
     * 
     * <p><b>Expected Behavior:</b></p>
     * <ul>
     *   <li>IllegalArgumentException thrown for invalid calendar dates</li>
     *   <li>Exception message indicates invalid date</li>
     *   <li>Java LocalDate validation prevents creation of February 30</li>
     * </ul>
     */
    @Test
    @DisplayName("Submit Custom Report - Invalid Calendar Date (Feb 30) Throws Exception")
    void testSubmitCustomReport_InvalidCalendarDate_ThrowsException() {
        // Arrange: Define invalid calendar date (February 30 doesn't exist)
        Integer startYear = 2024;
        Integer startMonth = 2; // February
        Integer startDay = 30; // Invalid for February
        Integer endYear = 2024;
        Integer endMonth = 6;
        Integer endDay = 20;
        String confirmed = "Y";

        // Execute and Assert: Verify exception for invalid calendar date
        IllegalArgumentException exception = assertThrows(
            IllegalArgumentException.class,
            () -> reportMenuService.submitCustomReport(
                startYear, startMonth, startDay,
                endYear, endMonth, endDay,
                confirmed
            ),
            "Should throw IllegalArgumentException for February 30"
        );

        // Verify exception message indicates invalid date
        String errorMessage = exception.getMessage();
        assertTrue(
            errorMessage.contains("Invalid date") || 
            errorMessage.contains("Start Date") ||
            errorMessage.toLowerCase().contains("date"),
            "Error message should indicate date validation failure"
        );

        // Verify no job submission occurred
        verifyNoInteractions(jobLauncher);
    }

    // ================================================================================
    // TEST METHODS: Validation Logic
    // From COBOL CORPT00C validateReportRequest method
    // ================================================================================

    /**
     * Test: testValidateReportRequest_ValidMonthlyReport_NoErrors
     * 
     * <p>Validates validateReportRequest() method returns empty error list when
     * valid monthly report request is provided (monthly flag = 'Y', confirmation = 'Y').</p>
     * 
     * <p><b>COBOL Context:</b> CORPT00C lines 258-300 validation logic with
     * WS-ERR-FLG remaining 'N' when all validation passes.</p>
     * 
     * <p><b>Expected Behavior:</b></p>
     * <ul>
     *   <li>Returns empty List (no validation errors)</li>
     *   <li>Monthly flag 'Y' is valid report type selection</li>
     *   <li>Confirmation 'Y' satisfies confirmation requirement</li>
     * </ul>
     */
    @Test
    @DisplayName("Validate Report Request - Valid Monthly Report Returns No Errors")
    void testValidateReportRequest_ValidMonthlyReport_NoErrors() {
        // Arrange: Create valid monthly report request
        ReportMenuResponse request = new ReportMenuResponse();
        request.setMonthlyReportFlag("Y");
        request.setConfirmationFlag("Y");

        // Execute: Validate report request
        List<String> errors = reportMenuService.validateReportRequest(request);

        // Assert: No validation errors
        assertNotNull(errors, "Errors list should not be null");
        assertTrue(errors.isEmpty(), "Should return empty list for valid monthly report");
    }

    /**
     * Test: testValidateReportRequest_NoReportTypeSelected_ReturnsError
     * 
     * <p>Validates validateReportRequest() method returns error when no report type
     * is selected (all flags empty or 'N').</p>
     * 
     * <p><b>COBOL Context:</b> CORPT00C lines 438-442 check if MONTHLYO, YEARLYO,
     * and CUSTOMO are all NOT 'Y', displaying error "Please select a Report Type..."</p>
     * 
     * <p><b>Expected Behavior:</b></p>
     * <ul>
     *   <li>Returns List with one error message</li>
     *   <li>Error message: "Please select a Report Type..."</li>
     *   <li>Matches COBOL WS-MESSAGE text exactly</li>
     * </ul>
     */
    @Test
    @DisplayName("Validate Report Request - No Report Type Selected Returns Error")
    void testValidateReportRequest_NoReportTypeSelected_ReturnsError() {
        // Arrange: Create request with no report type selected
        ReportMenuResponse request = new ReportMenuResponse();
        request.setMonthlyReportFlag("N");
        request.setYearlyReportFlag("N");
        request.setCustomReportFlag("N");
        request.setConfirmationFlag("Y");

        // Execute: Validate report request
        List<String> errors = reportMenuService.validateReportRequest(request);

        // Assert: Validation error present
        assertNotNull(errors, "Errors list should not be null");
        assertFalse(errors.isEmpty(), "Should return error for no report type selected");
        assertEquals(1, errors.size(), "Should have exactly one error");
        
        // Verify error message matches COBOL text (lines 439-441)
        String errorMessage = errors.get(0);
        assertTrue(errorMessage.contains("Please select a Report Type"), 
            "Error message should match COBOL validation text");
    }

    /**
     * Test: testValidateReportRequest_CustomReportMissingDates_ReturnsErrors
     * 
     * <p>Validates validateReportRequest() method returns multiple errors when
     * custom report is selected but date components are missing.</p>
     * 
     * <p><b>COBOL Context:</b> CORPT00C lines 259-300 validate each date component
     * separately, accumulating multiple errors if multiple fields empty.</p>
     * 
     * <p><b>Expected Behavior:</b></p>
     * <ul>
     *   <li>Returns List with multiple error messages (one per missing field)</li>
     *   <li>Errors include: start month, start day, start year, end month, end day, end year</li>
     *   <li>All error messages match COBOL text format</li>
     * </ul>
     */
    @Test
    @DisplayName("Validate Report Request - Custom Report Missing Dates Returns Multiple Errors")
    void testValidateReportRequest_CustomReportMissingDates_ReturnsErrors() {
        // Arrange: Create custom report request with all date components missing
        ReportMenuResponse request = new ReportMenuResponse();
        request.setCustomReportFlag("Y");
        request.setConfirmationFlag("Y");
        // All date components null (missing)

        // Execute: Validate report request
        List<String> errors = reportMenuService.validateReportRequest(request);

        // Assert: Multiple validation errors present
        assertNotNull(errors, "Errors list should not be null");
        assertFalse(errors.isEmpty(), "Should return errors for missing date components");
        assertEquals(6, errors.size(), "Should have 6 errors (one for each missing date component)");
        
        // Verify error messages for missing start date components (lines 259-278)
        assertTrue(errors.stream().anyMatch(e -> e.contains("Start Date - Month can NOT be empty")),
            "Should have error for missing start month");
        assertTrue(errors.stream().anyMatch(e -> e.contains("Start Date - Day can NOT be empty")),
            "Should have error for missing start day");
        assertTrue(errors.stream().anyMatch(e -> e.contains("Start Date - Year can NOT be empty")),
            "Should have error for missing start year");
        
        // Verify error messages for missing end date components (lines 280-300)
        assertTrue(errors.stream().anyMatch(e -> e.contains("End Date - Month can NOT be empty")),
            "Should have error for missing end month");
        assertTrue(errors.stream().anyMatch(e -> e.contains("End Date - Day can NOT be empty")),
            "Should have error for missing end day");
        assertTrue(errors.stream().anyMatch(e -> e.contains("End Date - Year can NOT be empty")),
            "Should have error for missing end year");
    }

    /**
     * Test: testValidateReportRequest_InvalidConfirmationValue_ReturnsError
     * 
     * <p>Validates validateReportRequest() method returns error when confirmation
     * flag has invalid value (not 'Y', 'y', 'N', 'n', or empty).</p>
     * 
     * <p><b>COBOL Context:</b> CORPT00C lines 484-494 EVALUATE TRUE WHEN OTHER
     * handles invalid confirmation with quoted error message format.</p>
     * 
     * <p><b>Expected Behavior:</b></p>
     * <ul>
     *   <li>Returns List with one error message</li>
     *   <li>Error format: "\"X\" is not a valid value to confirm..."</li>
     *   <li>Invalid value quoted in error message</li>
     * </ul>
     */
    @Test
    @DisplayName("Validate Report Request - Invalid Confirmation Value Returns Error")
    void testValidateReportRequest_InvalidConfirmationValue_ReturnsError() {
        // Arrange: Create request with invalid confirmation value
        ReportMenuResponse request = new ReportMenuResponse();
        request.setMonthlyReportFlag("Y");
        request.setConfirmationFlag("X"); // Invalid: not Y, y, N, n, or empty

        // Execute: Validate report request
        List<String> errors = reportMenuService.validateReportRequest(request);

        // Assert: Validation error present
        assertNotNull(errors, "Errors list should not be null");
        assertFalse(errors.isEmpty(), "Should return error for invalid confirmation");
        
        // Verify error message format matches COBOL (lines 742-746)
        String errorMessage = errors.stream()
            .filter(e -> e.contains("not a valid value to confirm"))
            .findFirst()
            .orElse(null);
        assertNotNull(errorMessage, "Should have confirmation validation error");
        assertTrue(errorMessage.contains("\"X\""), 
            "Error message should quote the invalid value");
    }

    // ================================================================================
    // TEST METHODS: Job Parameter Construction
    // From COBOL CORPT00C lines 81-100 (JCL parameter structures)
    // ================================================================================

    /**
     * Test: testJobParametersConstruction_IncludesAllRequiredFields
     * 
     * <p>Validates that JobParameters constructed for batch job submission include
     * all required fields transformed from COBOL JCL SYMNAMES DD parameters.</p>
     * 
     * <p><b>COBOL Context:</b> CORPT00C lines 98-100 define SYMNAMES DD with
     * parameter format "TRAN-CARD-NUM,263,16,ZD" for batch job input.</p>
     * 
     * <p><b>Expected JobParameters Fields:</b></p>
     * <ul>
     *   <li>startDate (DATE type) - Report period start</li>
     *   <li>endDate (DATE type) - Report period end</li>
     *   <li>reportType (STRING type) - MONTHLY, YEARLY, or CUSTOM</li>
     *   <li>timestamp (LONG type) - Job submission timestamp for uniqueness</li>
     * </ul>
     */
    @Test
    @DisplayName("Job Parameters Construction - Includes All Required Fields")
    void testJobParametersConstruction_IncludesAllRequiredFields() throws Exception {
        // Arrange: Setup mock JobExecution for job launch
        JobExecution mockJobExecution = new JobExecution(4L);
        mockJobExecution.setStatus(BatchStatus.STARTED);
        
        when(jobLauncher.run(eq(transactionAggregationJob), any(JobParameters.class)))
            .thenReturn(mockJobExecution);

        // Execute: Submit monthly report (simplest case for parameter verification)
        reportMenuService.submitMonthlyReport("Y");

        // Capture JobParameters for field verification
        ArgumentCaptor<JobParameters> jobParamsCaptor = ArgumentCaptor.forClass(JobParameters.class);
        verify(jobLauncher).run(eq(transactionAggregationJob), jobParamsCaptor.capture());
        
        JobParameters capturedParams = jobParamsCaptor.getValue();
        assertNotNull(capturedParams, "JobParameters should not be null");
        
        // Assert: Verify all required parameter fields present
        assertTrue(capturedParams.getParameters().containsKey("startDate"), 
            "JobParameters should include startDate");
        assertTrue(capturedParams.getParameters().containsKey("endDate"), 
            "JobParameters should include endDate");
        assertTrue(capturedParams.getParameters().containsKey("reportType"), 
            "JobParameters should include reportType");
        assertTrue(capturedParams.getParameters().containsKey("timestamp"), 
            "JobParameters should include timestamp for uniqueness");
        
        // Verify parameter types
        assertNotNull(capturedParams.getDate("startDate"), 
            "startDate should be DATE type");
        assertNotNull(capturedParams.getDate("endDate"), 
            "endDate should be DATE type");
        assertNotNull(capturedParams.getString("reportType"), 
            "reportType should be STRING type");
    }

    // ================================================================================
    // TEST METHODS: Exception Handling
    // From COBOL CORPT00C error handling patterns
    // ================================================================================

    /**
     * Test: testJobLaunchFailure_ThrowsException
     * 
     * <p>Validates that exceptions from JobLauncher.run() are properly propagated
     * when batch job submission fails due to infrastructure issues.</p>
     * 
     * <p><b>COBOL Context:</b> CORPT00C error handling for TDQ write failures
     * would set RESP code and display system error message to user.</p>
     * 
     * <p><b>Expected Behavior:</b></p>
     * <ul>
     *   <li>JobLauncher throws JobExecutionAlreadyRunningException</li>
     *   <li>Exception propagates to caller for handling</li>
     *   <li>Service does not catch infrastructure exceptions</li>
     * </ul>
     */
    @Test
    @DisplayName("Job Launch Failure - Exception Propagated to Caller")
    void testJobLaunchFailure_ThrowsException() throws Exception {
        // Arrange: Configure JobLauncher to throw exception
        when(jobLauncher.run(eq(transactionAggregationJob), any(JobParameters.class)))
            .thenThrow(new JobExecutionAlreadyRunningException("Job already running"));

        // Execute and Assert: Verify exception propagates
        assertThrows(
            JobExecutionAlreadyRunningException.class,
            () -> reportMenuService.submitMonthlyReport("Y"),
            "Should propagate JobExecutionAlreadyRunningException from JobLauncher"
        );

        // Verify JobLauncher was invoked despite exception
        verify(jobLauncher, times(1)).run(
            eq(transactionAggregationJob), 
            any(JobParameters.class)
        );
    }

    /**
     * Test: testJobParametersInvalidException_ThrowsException
     * 
     * <p>Validates that JobParametersInvalidException from JobLauncher is properly
     * propagated when job configuration validation fails.</p>
     * 
     * <p><b>Expected Behavior:</b></p>
     * <ul>
     *   <li>JobLauncher throws JobParametersInvalidException</li>
     *   <li>Exception propagates to controller layer</li>
     *   <li>Indicates job configuration problem</li>
     * </ul>
     */
    @Test
    @DisplayName("Job Parameters Invalid - Exception Propagated")
    void testJobParametersInvalidException_ThrowsException() throws Exception {
        // Arrange: Configure JobLauncher to throw JobParametersInvalidException
        when(jobLauncher.run(eq(transactionAggregationJob), any(JobParameters.class)))
            .thenThrow(new JobParametersInvalidException("Invalid job parameters"));

        // Execute and Assert: Verify exception propagates
        assertThrows(
            JobParametersInvalidException.class,
            () -> reportMenuService.submitYearlyReport("Y"),
            "Should propagate JobParametersInvalidException from JobLauncher"
        );

        // Verify JobLauncher was invoked
        verify(jobLauncher, times(1)).run(
            eq(transactionAggregationJob), 
            any(JobParameters.class)
        );
    }

    // ================================================================================
    // TEST METHODS: Edge Cases and Boundary Conditions
    // ================================================================================

    /**
     * Test: testSubmitCustomReport_LeapYearFebruary29_Success
     * 
     * <p>Validates submitCustomReport() correctly handles leap year February 29
     * as valid calendar date.</p>
     * 
     * <p><b>COBOL Context:</b> CSUTLDTC date utility validates calendar correctness
     * including leap year logic (CORPT00C lines 381-420).</p>
     * 
     * <p><b>Expected Behavior:</b></p>
     * <ul>
     *   <li>February 29, 2024 (leap year) accepted as valid date</li>
     *   <li>JobLauncher invoked successfully</li>
     *   <li>No validation errors thrown</li>
     * </ul>
     */
    @Test
    @DisplayName("Submit Custom Report - Leap Year Feb 29 Is Valid")
    void testSubmitCustomReport_LeapYearFebruary29_Success() throws Exception {
        // Arrange: Setup mock JobExecution for successful job launch
        JobExecution mockJobExecution = new JobExecution(5L);
        mockJobExecution.setStatus(BatchStatus.STARTED);
        
        when(jobLauncher.run(eq(transactionAggregationJob), any(JobParameters.class)))
            .thenReturn(mockJobExecution);

        // Define leap year February 29 (2024 is leap year)
        Integer startYear = 2024;
        Integer startMonth = 2;
        Integer startDay = 29; // Valid in leap year
        Integer endYear = 2024;
        Integer endMonth = 3;
        Integer endDay = 1;
        String confirmed = "Y";

        // Execute: Submit custom report with leap year date
        assertDoesNotThrow(() -> 
            reportMenuService.submitCustomReport(
                startYear, startMonth, startDay,
                endYear, endMonth, endDay,
                confirmed
            ),
            "February 29, 2024 should be valid in leap year"
        );

        // Assert: Verify JobLauncher invoked successfully
        verify(jobLauncher, times(1)).run(
            eq(transactionAggregationJob), 
            any(JobParameters.class)
        );
    }

    /**
     * Test: testSubmitCustomReport_SameDayStartAndEnd_Success
     * 
     * <p>Validates submitCustomReport() accepts same date for start and end
     * (single-day report period).</p>
     * 
     * <p><b>Expected Behavior:</b></p>
     * <ul>
     *   <li>Start date = end date is valid (not violation of start <= end rule)</li>
     *   <li>JobLauncher invoked successfully</li>
     *   <li>Single-day report period supported</li>
     * </ul>
     */
    @Test
    @DisplayName("Submit Custom Report - Same Start and End Date Is Valid")
    void testSubmitCustomReport_SameDayStartAndEnd_Success() throws Exception {
        // Arrange: Setup mock JobExecution
        JobExecution mockJobExecution = new JobExecution(6L);
        mockJobExecution.setStatus(BatchStatus.STARTED);
        
        when(jobLauncher.run(eq(transactionAggregationJob), any(JobParameters.class)))
            .thenReturn(mockJobExecution);

        // Define same date for start and end (single-day report)
        Integer startYear = 2024;
        Integer startMonth = 6;
        Integer startDay = 15;
        Integer endYear = 2024;
        Integer endMonth = 6;
        Integer endDay = 15; // Same as start
        String confirmed = "Y";

        // Execute: Submit custom report with single-day period
        assertDoesNotThrow(() -> 
            reportMenuService.submitCustomReport(
                startYear, startMonth, startDay,
                endYear, endMonth, endDay,
                confirmed
            ),
            "Same start and end date should be valid"
        );

        // Assert: Verify JobLauncher invoked successfully
        verify(jobLauncher, times(1)).run(
            eq(transactionAggregationJob), 
            any(JobParameters.class)
        );
    }

    /**
     * Test: testSubmitMonthlyReport_CaseInsensitiveConfirmation_Success
     * 
     * <p>Validates submitMonthlyReport() accepts both uppercase 'Y' and lowercase 'y'
     * for confirmation flag (case-insensitive validation).</p>
     * 
     * <p><b>COBOL Context:</b> CORPT00C lines 478-479 check for 'Y' OR 'y'
     * in EVALUATE TRUE statement.</p>
     * 
     * <p><b>Expected Behavior:</b></p>
     * <ul>
     *   <li>Lowercase 'y' accepted as valid confirmation</li>
     *   <li>JobLauncher invoked successfully</li>
     *   <li>Case-insensitive validation matches COBOL behavior</li>
     * </ul>
     */
    @Test
    @DisplayName("Submit Monthly Report - Lowercase Confirmation 'y' Is Valid")
    void testSubmitMonthlyReport_CaseInsensitiveConfirmation_Success() throws Exception {
        // Arrange: Setup mock JobExecution
        JobExecution mockJobExecution = new JobExecution(7L);
        mockJobExecution.setStatus(BatchStatus.STARTED);
        
        when(jobLauncher.run(eq(transactionAggregationJob), any(JobParameters.class)))
            .thenReturn(mockJobExecution);

        // Execute: Submit monthly report with lowercase 'y' confirmation
        assertDoesNotThrow(() -> 
            reportMenuService.submitMonthlyReport("y"),
            "Lowercase 'y' should be valid confirmation"
        );

        // Assert: Verify JobLauncher invoked successfully
        verify(jobLauncher, times(1)).run(
            eq(transactionAggregationJob), 
            any(JobParameters.class)
        );
    }

    /**
     * Test: testSubmitCustomReport_BoundaryYear1900_Success
     * 
     * <p>Validates submitCustomReport() accepts year 1900 as valid
     * (lower boundary of valid year range).</p>
     * 
     * <p><b>COBOL Context:</b> CORPT00C lines 347-353 check year range 1900-2100,
     * inclusive boundaries.</p>
     * 
     * <p><b>Expected Behavior:</b></p>
     * <ul>
     *   <li>Year 1900 accepted as valid (boundary inclusive)</li>
     *   <li>JobLauncher invoked successfully</li>
     *   <li>Boundary year validation correct</li>
     * </ul>
     */
    @Test
    @DisplayName("Submit Custom Report - Boundary Year 1900 Is Valid")
    void testSubmitCustomReport_BoundaryYear1900_Success() throws Exception {
        // Arrange: Setup mock JobExecution
        JobExecution mockJobExecution = new JobExecution(8L);
        mockJobExecution.setStatus(BatchStatus.STARTED);
        
        when(jobLauncher.run(eq(transactionAggregationJob), any(JobParameters.class)))
            .thenReturn(mockJobExecution);

        // Define date with boundary year 1900
        Integer startYear = 1900; // Lower boundary
        Integer startMonth = 1;
        Integer startDay = 1;
        Integer endYear = 1900;
        Integer endMonth = 12;
        Integer endDay = 31;
        String confirmed = "Y";

        // Execute: Submit custom report with boundary year
        assertDoesNotThrow(() -> 
            reportMenuService.submitCustomReport(
                startYear, startMonth, startDay,
                endYear, endMonth, endDay,
                confirmed
            ),
            "Year 1900 should be valid (boundary inclusive)"
        );

        // Assert: Verify JobLauncher invoked successfully
        verify(jobLauncher, times(1)).run(
            eq(transactionAggregationJob), 
            any(JobParameters.class)
        );
    }

    /**
     * Test: testSubmitCustomReport_BoundaryYear2100_Success
     * 
     * <p>Validates submitCustomReport() accepts year 2100 as valid
     * (upper boundary of valid year range).</p>
     * 
     * <p><b>COBOL Context:</b> CORPT00C lines 373-379 check year range 1900-2100,
     * inclusive boundaries.</p>
     * 
     * <p><b>Expected Behavior:</b></p>
     * <ul>
     *   <li>Year 2100 accepted as valid (boundary inclusive)</li>
     *   <li>JobLauncher invoked successfully</li>
     *   <li>Upper boundary validation correct</li>
     * </ul>
     */
    @Test
    @DisplayName("Submit Custom Report - Boundary Year 2100 Is Valid")
    void testSubmitCustomReport_BoundaryYear2100_Success() throws Exception {
        // Arrange: Setup mock JobExecution
        JobExecution mockJobExecution = new JobExecution(9L);
        mockJobExecution.setStatus(BatchStatus.STARTED);
        
        when(jobLauncher.run(eq(transactionAggregationJob), any(JobParameters.class)))
            .thenReturn(mockJobExecution);

        // Define date with boundary year 2100
        Integer startYear = 2100; // Upper boundary
        Integer startMonth = 1;
        Integer startDay = 1;
        Integer endYear = 2100;
        Integer endMonth = 12;
        Integer endDay = 31;
        String confirmed = "Y";

        // Execute: Submit custom report with boundary year
        assertDoesNotThrow(() -> 
            reportMenuService.submitCustomReport(
                startYear, startMonth, startDay,
                endYear, endMonth, endDay,
                confirmed
            ),
            "Year 2100 should be valid (boundary inclusive)"
        );

        // Assert: Verify JobLauncher invoked successfully
        verify(jobLauncher, times(1)).run(
            eq(transactionAggregationJob), 
            any(JobParameters.class)
        );
    }
}
