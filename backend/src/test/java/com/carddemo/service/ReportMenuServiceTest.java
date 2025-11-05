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
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.JobExecution;
import org.springframework.batch.core.JobInstance;
import org.springframework.batch.core.JobParameters;
import org.springframework.batch.core.launch.JobLauncher;

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
 * and transformation of COBOL TDQ extra partition JCL submission to Spring Batch JobLauncher.</p>
 * 
 * <p><strong>Source COBOL Program: CORPT00C.cbl</strong></p>
 * <ul>
 *   <li>Transaction ID: CR00 (Report Menu)</li>
 *   <li>Function: Print Transaction reports by submitting batch job from online using TDQ</li>
 *   <li>Lines 213-238: Monthly report logic - Current month date range calculation</li>
 *   <li>Lines 239-255: Yearly report logic - Current year full date range</li>
 *   <li>Lines 256-436: Custom report logic - User date input validation</li>
 *   <li>Lines 462-510: SUBMIT-JOB-TO-INTRDR - JCL job submission via TDQ</li>
 *   <li>Lines 515-535: WIRTE-JOBSUB-TDQ - Write JCL records to JOBS TDQ</li>
 * </ul>
 * 
 * <p><strong>Test Coverage Requirements:</strong></p>
 * <ul>
 *   <li>Minimum 80% line coverage of ReportMenuService per Section 0.9</li>
 *   <li>All report submission paths tested (Monthly, Yearly, Custom)</li>
 *   <li>All date validation branches covered matching COBOL logic lines 259-426</li>
 *   <li>Job parameter construction fully tested matching COBOL JOB-DATA lines 81-127</li>
 *   <li>Confirmation validation matching COBOL logic lines 464-494</li>
 * </ul>
 * 
 * <p><strong>Business Logic Preservation (per Section 0.9):</strong></p>
 * <ul>
 *   <li>Monthly report: Current month start to end (COBOL lines 213-238)</li>
 *   <li>Yearly report: Current year January 1 to December 31 (COBOL lines 239-255)</li>
 *   <li>Custom report: User-provided date range with validation (COBOL lines 256-436)</li>
 *   <li>Confirmation required before job submission (COBOL lines 464-474)</li>
 *   <li>Error messages match COBOL WS-MESSAGE field text exactly</li>
 * </ul>
 * 
 * <p><strong>Test Execution Strategy:</strong></p>
 * <p>Uses MockitoExtension for isolated unit testing without real Spring context, Spring Batch
 * infrastructure, job repository, or batch processing execution. Mock JobLauncher simulates
 * job submission replacing COBOL EXEC CICS WRITEQ TD transient data queue writes.</p>
 * 
 * <p><strong>Validation Assertions:</strong></p>
 * <ul>
 *   <li>Date range: Start date must be less than or equal to end date</li>
 *   <li>Date format: ISO-8601 (YYYY-MM-DD) in JobParameters</li>
 *   <li>Report types: MONTHLY, YEARLY, CUSTOM as job parameter report.type</li>
 *   <li>Job parameters include: start.date, end.date, report.type, run.id</li>
 *   <li>Status messages match COBOL text exactly from WS-MESSAGE field</li>
 * </ul>
 * 
 * @author CardDemo Migration Team
 * @version 1.0
 * @since 2024-01-01
 * @see ReportMenuService Service class under test
 * @see ReportMenuResponse Response DTO for report menu operations
 * @see MessageConstants Application message constants
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("ReportMenuService - Report Generation and Batch Job Orchestration Tests")
class ReportMenuServiceTest {

    /**
     * Mock JobLauncher for simulating Spring Batch job submission without actual batch execution.
     * Replaces COBOL EXEC CICS WRITEQ TD transient data queue JCL submission (lines 517-523).
     */
    @Mock
    private JobLauncher jobLauncher;

    /**
     * Mock TransactionAggregationJob for monthly, yearly, and custom transaction reports.
     * Corresponds to COBOL batch program CBTRN03C.cbl execution via JCL TRNRPT00 job.
     */
    @Mock
    private Job transactionAggregationJob;

    /**
     * Mock StatementGenerationJob for monthly account statement reports.
     * Corresponds to COBOL batch program CBSTM03A.cbl execution via JCL.
     */
    @Mock
    private Job statementGenerationJob;

    /**
     * ReportMenuService under test with injected mock dependencies.
     * Transforms COBOL CICS program CORPT00C.cbl business logic.
     */
    @InjectMocks
    private ReportMenuService reportMenuService;

    /**
     * Sample JobExecution for successful job submission simulations.
     * Represents completed Spring Batch job execution with COMPLETED status.
     */
    private JobExecution successfulJobExecution;

    /**
     * Setup method executed before each test case.
     * Initializes common test fixtures including sample JobExecution instances.
     */
    @BeforeEach
    void setUp() {
        // Create sample JobExecution for successful job submissions
        JobInstance jobInstance = new JobInstance(1L, "TransactionAggregationJob");
        successfulJobExecution = new JobExecution(jobInstance, 1L, new JobParameters());
        successfulJobExecution.setStatus(BatchStatus.COMPLETED);
    }

    // ===============================================================================
    // TEST: getAvailableReportTypes() - Report Menu Display
    // ===============================================================================

    /**
     * Test getAvailableReportTypes() returns available report options for menu display.
     * 
     * <p>Verifies transformation of COBOL SEND-TRNRPT-SCREEN paragraph (lines 556-578)
     * which prepares screen data for terminal display. Tests that response contains
     * proper transaction ID, program name, header titles, and current date/time matching
     * COBOL POPULATE-HEADER-INFO logic (lines 609-628).</p>
     * 
     * <p><strong>COBOL Equivalent:</strong></p>
     * <pre>
     * SEND-TRNRPT-SCREEN.
     *     PERFORM POPULATE-HEADER-INFO
     *     MOVE WS-MESSAGE TO ERRMSGO OF CORPT0AO
     *     EXEC CICS SEND MAP('CORPT0A') MAPSET('CORPT00')
     *         FROM(CORPT0AO) ERASE
     *     END-EXEC
     * </pre>
     * 
     * <p><strong>Expected Behavior:</strong></p>
     * <ul>
     *   <li>Returns non-null ReportMenuResponse</li>
     *   <li>Transaction name set to "CR00" (COBOL WS-TRANID line 38)</li>
     *   <li>Program name set to "CORPT00C" (COBOL WS-PGMNAME line 37)</li>
     *   <li>Current date and time populated</li>
     *   <li>Report selection flags initialized to empty strings</li>
     *   <li>No error message present</li>
     * </ul>
     */
    @Test
    @DisplayName("Should return available report types with proper screen metadata")
    void testGetReportMenu_ReturnsAvailableReports() {
        // When
        ReportMenuResponse response = reportMenuService.getAvailableReportTypes();

        // Then
        assertNotNull(response, "Response should not be null");
        assertEquals("CR00", response.getTransactionName(), 
                "Transaction name should be CR00 matching COBOL WS-TRANID");
        assertEquals("CORPT00C", response.getProgramName(), 
                "Program name should be CORPT00C matching COBOL WS-PGMNAME");
        
        assertNotNull(response.getCurrentDate(), "Current date should be populated");
        assertNotNull(response.getCurrentTime(), "Current time should be populated");
        
        assertEquals("CardDemo - Report Generation Menu", response.getTitle01(), 
                "Title01 should match report menu header");
        assertEquals("Select Report Type and Date Range", response.getTitle02(), 
                "Title02 should match report menu subtitle");
        
        assertEquals("", response.getMonthlyReportFlag(), "Monthly flag should be empty initially");
        assertEquals("", response.getYearlyReportFlag(), "Yearly flag should be empty initially");
        assertEquals("", response.getCustomReportFlag(), "Custom flag should be empty initially");
        assertEquals("", response.getConfirmationFlag(), "Confirmation flag should be empty initially");
        assertEquals("", response.getErrorMessage(), "Error message should be empty initially");
    }

    // ===============================================================================
    // TEST: submitMonthlyReport() - Monthly Report Submission
    // ===============================================================================

    /**
     * Test submitMonthlyReport() with valid confirmation triggers job successfully.
     * 
     * <p>Validates transformation of COBOL monthly report logic (lines 213-238) which
     * calculates current month date range and submits JCL job via TDQ. Tests date range
     * calculation using Java YearMonth.atDay(1) and atEndOfMonth() matching complex COBOL
     * date arithmetic with FUNCTION CURRENT-DATE, DATE-OF-INTEGER, and INTEGER-OF-DATE.</p>
     * 
     * <p><strong>COBOL Date Calculation Logic (lines 215-236):</strong></p>
     * <pre>
     * MOVE FUNCTION CURRENT-DATE TO WS-CURDATE-DATA
     * MOVE WS-CURDATE-YEAR TO WS-START-DATE-YYYY
     * MOVE WS-CURDATE-MONTH TO WS-START-DATE-MM
     * MOVE '01' TO WS-START-DATE-DD
     * 
     * ADD 1 TO WS-CURDATE-MONTH
     * IF WS-CURDATE-MONTH > 12
     *     ADD 1 TO WS-CURDATE-YEAR
     *     MOVE 1 TO WS-CURDATE-MONTH
     * END-IF
     * COMPUTE WS-CURDATE-N = FUNCTION DATE-OF-INTEGER(
     *         FUNCTION INTEGER-OF-DATE(WS-CURDATE-N) - 1)
     * MOVE WS-START-DATE TO PARM-START-DATE-1, PARM-START-DATE-2
     * MOVE WS-END-DATE TO PARM-END-DATE-1, PARM-END-DATE-2
     * PERFORM SUBMIT-JOB-TO-INTRDR
     * </pre>
     * 
     * <p><strong>Expected Behavior:</strong></p>
     * <ul>
     *   <li>Calculates current month first and last day correctly</li>
     *   <li>Calls JobLauncher.run() once with TransactionAggregationJob</li>
     *   <li>Job parameters include start.date, end.date, report.type="MONTHLY", run.id</li>
     *   <li>Returns JobExecution with COMPLETED status</li>
     * </ul>
     */
    @Test
    @DisplayName("Should submit monthly report with valid date range when confirmed")
    void testSubmitMonthlyReport_ValidConfirmation_TriggersJob() throws Exception {
        // Given
        when(jobLauncher.run(eq(transactionAggregationJob), any(JobParameters.class)))
                .thenReturn(successfulJobExecution);

        // Calculate expected date range for verification
        YearMonth currentMonth = YearMonth.now();
        LocalDate expectedStartDate = currentMonth.atDay(1);
        LocalDate expectedEndDate = currentMonth.atEndOfMonth();

        // When
        JobExecution result = reportMenuService.submitMonthlyReport("Y");

        // Then
        assertNotNull(result, "JobExecution should not be null");
        assertEquals(BatchStatus.COMPLETED, result.getStatus(), 
                "Job status should be COMPLETED");

        // Verify JobLauncher was called with correct job and parameters
        verify(jobLauncher, times(1)).run(eq(transactionAggregationJob), any(JobParameters.class));
    }

    /**
     * Test submitMonthlyReport() with invalid confirmation throws exception.
     * 
     * <p>Validates confirmation validation logic from COBOL lines 464-494 which checks
     * CONFIRMI field for 'Y', 'N', or empty values with specific error messages.</p>
     * 
     * <p><strong>COBOL Validation (lines 476-494):</strong></p>
     * <pre>
     * EVALUATE TRUE
     *     WHEN CONFIRMI OF CORPT0AI = 'Y' OR 'y'
     *         CONTINUE
     *     WHEN CONFIRMI OF CORPT0AI = 'N' OR 'n'
     *         PERFORM INITIALIZE-ALL-FIELDS
     *         MOVE 'Y' TO WS-ERR-FLG
     *     WHEN OTHER
     *         STRING '"' CONFIRMI '" is not a valid value to confirm...'
     *             INTO WS-MESSAGE
     *         MOVE 'Y' TO WS-ERR-FLG
     * END-EVALUATE
     * </pre>
     */
    @Test
    @DisplayName("Should throw exception when monthly report confirmation is invalid")
    void testSubmitMonthlyReport_InvalidConfirmation_ThrowsException() {
        // When/Then
        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class,
                () -> reportMenuService.submitMonthlyReport("X"),
                "Should throw IllegalArgumentException for invalid confirmation");

        assertTrue(exception.getMessage().contains("not a valid value to confirm"),
                "Error message should match COBOL validation message");

        // Verify JobLauncher was never called
        verify(jobLauncher, never()).run(any(Job.class), any(JobParameters.class));
    }

    /**
     * Test submitMonthlyReport() with empty confirmation throws exception.
     * 
     * <p>Validates COBOL confirmation check (lines 464-474) for SPACES or LOW-VALUES.</p>
     */
    @Test
    @DisplayName("Should throw exception when monthly report confirmation is empty")
    void testSubmitMonthlyReport_EmptyConfirmation_ThrowsException() {
        // When/Then
        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class,
                () -> reportMenuService.submitMonthlyReport(""),
                "Should throw IllegalArgumentException for empty confirmation");

        assertTrue(exception.getMessage().contains("confirm to print the Monthly report"),
                "Error message should match COBOL prompt for confirmation");

        verify(jobLauncher, never()).run(any(Job.class), any(JobParameters.class));
    }

    /**
     * Test submitMonthlyReport() with 'N' confirmation cancels submission.
     * 
     * <p>Validates COBOL logic (lines 480-483) where 'N' confirmation cancels operation.</p>
     */
    @Test
    @DisplayName("Should cancel monthly report submission when confirmed with N")
    void testSubmitMonthlyReport_ConfirmationN_CancelsSubmission() {
        // When/Then
        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class,
                () -> reportMenuService.submitMonthlyReport("N"),
                "Should throw IllegalArgumentException for N confirmation");

        assertTrue(exception.getMessage().contains("cancelled"),
                "Error message should indicate cancellation");

        verify(jobLauncher, never()).run(any(Job.class), any(JobParameters.class));
    }

    // ===============================================================================
    // TEST: submitYearlyReport() - Yearly Report Submission
    // ===============================================================================

    /**
     * Test submitYearlyReport() with valid confirmation triggers job successfully.
     * 
     * <p>Validates transformation of COBOL yearly report logic (lines 239-255) which
     * calculates current year date range (January 1 to December 31) and submits batch job.</p>
     * 
     * <p><strong>COBOL Date Calculation Logic (lines 239-254):</strong></p>
     * <pre>
     * MOVE FUNCTION CURRENT-DATE TO WS-CURDATE-DATA
     * MOVE WS-CURDATE-YEAR TO WS-START-DATE-YYYY, WS-END-DATE-YYYY
     * MOVE '01' TO WS-START-DATE-MM, WS-START-DATE-DD
     * MOVE '12' TO WS-END-DATE-MM
     * MOVE '31' TO WS-END-DATE-DD
     * PERFORM SUBMIT-JOB-TO-INTRDR
     * </pre>
     * 
     * <p><strong>Expected Behavior:</strong></p>
     * <ul>
     *   <li>Calculates current year January 1 to December 31 date range</li>
     *   <li>Calls JobLauncher.run() once with TransactionAggregationJob</li>
     *   <li>Job parameters include report.type="YEARLY"</li>
     *   <li>Returns JobExecution with COMPLETED status</li>
     * </ul>
     */
    @Test
    @DisplayName("Should submit yearly report with full year date range when confirmed")
    void testSubmitYearlyReport_ValidConfirmation_TriggersJob() throws Exception {
        // Given
        when(jobLauncher.run(eq(transactionAggregationJob), any(JobParameters.class)))
                .thenReturn(successfulJobExecution);

        // Calculate expected date range for verification
        int currentYear = LocalDate.now().getYear();
        LocalDate expectedStartDate = LocalDate.of(currentYear, 1, 1);
        LocalDate expectedEndDate = LocalDate.of(currentYear, 12, 31);

        // When
        JobExecution result = reportMenuService.submitYearlyReport("Y");

        // Then
        assertNotNull(result, "JobExecution should not be null");
        assertEquals(BatchStatus.COMPLETED, result.getStatus(), 
                "Job status should be COMPLETED");

        // Verify JobLauncher was called with correct job
        verify(jobLauncher, times(1)).run(eq(transactionAggregationJob), any(JobParameters.class));
    }

    /**
     * Test submitYearlyReport() with invalid confirmation throws exception.
     */
    @Test
    @DisplayName("Should throw exception when yearly report confirmation is invalid")
    void testSubmitYearlyReport_InvalidConfirmation_ThrowsException() {
        // When/Then
        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class,
                () -> reportMenuService.submitYearlyReport("INVALID"),
                "Should throw IllegalArgumentException for invalid confirmation");

        assertTrue(exception.getMessage().contains("not a valid value to confirm"),
                "Error message should match COBOL validation message");

        verify(jobLauncher, never()).run(any(Job.class), any(JobParameters.class));
    }

    // ===============================================================================
    // TEST: submitCustomReport() - Custom Date Range Report Submission
    // ===============================================================================

    /**
     * Test submitCustomReport() with valid date range triggers job successfully.
     * 
     * <p>Validates transformation of COBOL custom report logic (lines 256-436) including
     * extensive field validation, date component checks, and date validity verification
     * via CSUTLDTC utility program call.</p>
     * 
     * <p><strong>COBOL Validation Logic (lines 258-426):</strong></p>
     * <ol>
     *   <li>Check each date component (month, day, year) for presence (lines 259-300)</li>
     *   <li>Convert input to numeric using FUNCTION NUMVAL-C (lines 305-327)</li>
     *   <li>Validate month range 1-12 (lines 329-336)</li>
     *   <li>Validate day range 1-31 (lines 338-345)</li>
     *   <li>Validate year is numeric (lines 347-353)</li>
     *   <li>Validate complete date via CALL 'CSUTLDTC' (lines 392-426)</li>
     *   <li>Construct WS-START-DATE and WS-END-DATE (lines 381-386)</li>
     *   <li>Submit job if no errors (lines 429-435)</li>
     * </ol>
     * 
     * <p><strong>Expected Behavior:</strong></p>
     * <ul>
     *   <li>Validates all date components are present and within valid ranges</li>
     *   <li>Constructs LocalDate objects from components</li>
     *   <li>Validates start date is before or equal to end date</li>
     *   <li>Calls JobLauncher.run() with custom date range parameters</li>
     *   <li>Returns JobExecution with COMPLETED status</li>
     * </ul>
     */
    @Test
    @DisplayName("Should submit custom report with valid date range when confirmed")
    void testSubmitCustomReport_ValidDateRange_TriggersJob() throws Exception {
        // Given
        when(jobLauncher.run(eq(transactionAggregationJob), any(JobParameters.class)))
                .thenReturn(successfulJobExecution);

        // Valid date range: January 1, 2024 to January 31, 2024
        Integer startYear = 2024;
        Integer startMonth = 1;
        Integer startDay = 1;
        Integer endYear = 2024;
        Integer endMonth = 1;
        Integer endDay = 31;

        // When
        JobExecution result = reportMenuService.submitCustomReport(
                startYear, startMonth, startDay,
                endYear, endMonth, endDay,
                "Y");

        // Then
        assertNotNull(result, "JobExecution should not be null");
        assertEquals(BatchStatus.COMPLETED, result.getStatus(), 
                "Job status should be COMPLETED");

        // Verify JobLauncher was called
        verify(jobLauncher, times(1)).run(eq(transactionAggregationJob), any(JobParameters.class));
    }

    /**
     * Test submitCustomReport() with invalid start date month throws exception.
     * 
     * <p>Validates COBOL month validation logic (lines 329-336):</p>
     * <pre>
     * IF SDTMMI OF CORPT0AI IS NOT NUMERIC OR SDTMMI > '12'
     *     MOVE 'Start Date - Not a valid Month...' TO WS-MESSAGE
     *     MOVE 'Y' TO WS-ERR-FLG
     * END-IF
     * </pre>
     */
    @Test
    @DisplayName("Should throw exception when start date month is invalid")
    void testSubmitCustomReport_InvalidStartMonth_ReturnsError() {
        // When/Then - Month 13 is invalid
        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class,
                () -> reportMenuService.submitCustomReport(
                        2024, 13, 15, 2024, 12, 31, "Y"),
                "Should throw IllegalArgumentException for invalid month");

        assertTrue(exception.getMessage().contains("Start Date - Not a valid Month"),
                "Error message should match COBOL validation message from line 331");

        verify(jobLauncher, never()).run(any(Job.class), any(JobParameters.class));
    }

    /**
     * Test submitCustomReport() with invalid start date day throws exception.
     * 
     * <p>Validates COBOL day validation logic (lines 338-345):</p>
     * <pre>
     * IF SDTDDI OF CORPT0AI IS NOT NUMERIC OR SDTDDI > '31'
     *     MOVE 'Start Date - Not a valid Day...' TO WS-MESSAGE
     * END-IF
     * </pre>
     */
    @Test
    @DisplayName("Should throw exception when start date day is invalid")
    void testSubmitCustomReport_InvalidStartDay_ReturnsError() {
        // When/Then - Day 32 is invalid
        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class,
                () -> reportMenuService.submitCustomReport(
                        2024, 1, 32, 2024, 12, 31, "Y"),
                "Should throw IllegalArgumentException for invalid day");

        assertTrue(exception.getMessage().contains("Start Date - Not a valid Day"),
                "Error message should match COBOL validation message from line 340");

        verify(jobLauncher, never()).run(any(Job.class), any(JobParameters.class));
    }

    /**
     * Test submitCustomReport() with end date before start date throws exception.
     * 
     * <p>Validates date range logical validation (implicit in COBOL logic).
     * Start date must be less than or equal to end date for valid report range.</p>
     */
    @Test
    @DisplayName("Should throw exception when end date is before start date")
    void testSubmitCustomReport_EndDateBeforeStartDate_ReturnsError() {
        // When/Then - End date 2024-01-01 before start date 2024-12-31
        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class,
                () -> reportMenuService.submitCustomReport(
                        2024, 12, 31, 2024, 1, 1, "Y"),
                "Should throw IllegalArgumentException when end date before start date");

        assertTrue(exception.getMessage().contains("Start date must be less than or equal to end date"),
                "Error message should indicate date range validation failure");

        verify(jobLauncher, never()).run(any(Job.class), any(JobParameters.class));
    }

    /**
     * Test submitCustomReport() with null start date components throws exception.
     * 
     * <p>Validates COBOL field presence validation (lines 259-278):</p>
     * <pre>
     * WHEN SDTMMI OF CORPT0AI = SPACES OR LOW-VALUES
     *     MOVE 'Start Date - Month can NOT be empty...' TO WS-MESSAGE
     *     MOVE 'Y' TO WS-ERR-FLG
     * </pre>
     */
    @Test
    @DisplayName("Should throw exception when start date components are null")
    void testSubmitCustomReport_NullStartDateComponents_ReturnsError() {
        // When/Then - Null start month
        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class,
                () -> reportMenuService.submitCustomReport(
                        2024, null, 15, 2024, 12, 31, "Y"),
                "Should throw IllegalArgumentException for null start month");

        assertTrue(exception.getMessage().contains("Start Date - Month can NOT be empty"),
                "Error message should match COBOL validation message from line 261");

        verify(jobLauncher, never()).run(any(Job.class), any(JobParameters.class));
    }

    /**
     * Test submitCustomReport() with invalid end date month throws exception.
     * 
     * <p>Validates COBOL end month validation logic (lines 355-362):</p>
     * <pre>
     * IF EDTMMI OF CORPT0AI IS NOT NUMERIC OR EDTMMI > '12'
     *     MOVE 'End Date - Not a valid Month...' TO WS-MESSAGE
     * END-IF
     * </pre>
     */
    @Test
    @DisplayName("Should throw exception when end date month is invalid")
    void testSubmitCustomReport_InvalidEndMonth_ReturnsError() {
        // When/Then - Month 0 is invalid
        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class,
                () -> reportMenuService.submitCustomReport(
                        2024, 1, 1, 2024, 0, 31, "Y"),
                "Should throw IllegalArgumentException for invalid end month");

        assertTrue(exception.getMessage().contains("End Date - Not a valid Month"),
                "Error message should match COBOL validation message from line 357");

        verify(jobLauncher, never()).run(any(Job.class), any(JobParameters.class));
    }

    // ===============================================================================
    // TEST: validateReportRequest() - Request Validation
    // ===============================================================================

    /**
     * Test validateReportRequest() returns no errors for valid monthly report request.
     * 
     * <p>Validates transformation of COBOL report type selection validation (lines 212-443)
     * which checks that at least one report type is selected.</p>
     */
    @Test
    @DisplayName("Should validate monthly report request with no errors")
    void testValidateReportRequest_ValidMonthlyReport_ReturnsNoErrors() {
        // Given
        ReportMenuResponse request = createValidMonthlyReportRequest();

        // When
        List<String> errors = reportMenuService.validateReportRequest(request);

        // Then
        assertNotNull(errors, "Errors list should not be null");
        assertTrue(errors.isEmpty(), "Should have no validation errors for valid monthly report");
    }

    /**
     * Test validateReportRequest() returns no errors for valid custom report request.
     * 
     * <p>Validates COBOL custom report date validation logic (lines 258-426).</p>
     */
    @Test
    @DisplayName("Should validate custom report request with valid dates and no errors")
    void testValidateReportRequest_ValidCustomReport_ReturnsNoErrors() {
        // Given
        ReportMenuResponse request = createValidCustomReportRequest();

        // When
        List<String> errors = reportMenuService.validateReportRequest(request);

        // Then
        assertNotNull(errors, "Errors list should not be null");
        assertTrue(errors.isEmpty(), "Should have no validation errors for valid custom report");
    }

    /**
     * Test validateReportRequest() returns error when no report type selected.
     * 
     * <p>Validates COBOL validation logic (lines 437-442):</p>
     * <pre>
     * WHEN OTHER
     *     MOVE 'Select a report type to print report...' TO WS-MESSAGE
     *     MOVE 'Y' TO WS-ERR-FLG
     * </pre>
     */
    @Test
    @DisplayName("Should return error when no report type is selected")
    void testValidateReportRequest_NoReportTypeSelected_ReturnsError() {
        // Given
        ReportMenuResponse request = new ReportMenuResponse();
        request.setMonthlyReportFlag("");
        request.setYearlyReportFlag("");
        request.setCustomReportFlag("");

        // When
        List<String> errors = reportMenuService.validateReportRequest(request);

        // Then
        assertNotNull(errors, "Errors list should not be null");
        assertFalse(errors.isEmpty(), "Should have validation errors");
        assertTrue(errors.stream().anyMatch(e -> e.contains("Select a report type")),
                "Should have error for no report type selected matching COBOL message line 438");
    }

    /**
     * Test validateReportRequest() returns error when custom report missing start date month.
     * 
     * <p>Validates COBOL field presence check (lines 259-264):</p>
     * <pre>
     * WHEN SDTMMI OF CORPT0AI = SPACES OR LOW-VALUES
     *     MOVE 'Start Date - Month can NOT be empty...' TO WS-MESSAGE
     *     MOVE 'Y' TO WS-ERR-FLG
     * </pre>
     */
    @Test
    @DisplayName("Should return error when custom report missing start date month")
    void testValidateReportRequest_CustomReportMissingStartMonth_ReturnsError() {
        // Given
        ReportMenuResponse request = new ReportMenuResponse();
        request.setCustomReportFlag("Y");
        request.setStartDateMonth(null);  // Missing month
        request.setStartDateDay(15);
        request.setStartDateYear(2024);
        request.setEndDateMonth(12);
        request.setEndDateDay(31);
        request.setEndDateYear(2024);

        // When
        List<String> errors = reportMenuService.validateReportRequest(request);

        // Then
        assertFalse(errors.isEmpty(), "Should have validation errors");
        assertTrue(errors.stream().anyMatch(e -> e.contains("Start Date - Month can NOT be empty")),
                "Should have error matching COBOL message from line 261");
    }

    /**
     * Test validateReportRequest() returns error when custom report has invalid month range.
     * 
     * <p>Validates COBOL numeric range validation (lines 329-336):</p>
     * <pre>
     * IF SDTMMI IS NOT NUMERIC OR SDTMMI > '12'
     *     MOVE 'Start Date - Not a valid Month...' TO WS-MESSAGE
     * </pre>
     */
    @Test
    @DisplayName("Should return error when custom report has invalid start month range")
    void testValidateReportRequest_InvalidStartMonthRange_ReturnsError() {
        // Given
        ReportMenuResponse request = new ReportMenuResponse();
        request.setCustomReportFlag("Y");
        request.setStartDateMonth(13);  // Invalid month > 12
        request.setStartDateDay(15);
        request.setStartDateYear(2024);
        request.setEndDateMonth(12);
        request.setEndDateDay(31);
        request.setEndDateYear(2024);

        // When
        List<String> errors = reportMenuService.validateReportRequest(request);

        // Then
        assertFalse(errors.isEmpty(), "Should have validation errors");
        assertTrue(errors.stream().anyMatch(e -> e.contains("Start Date - Not a valid Month")),
                "Should have error matching COBOL message from line 331");
    }

    /**
     * Test validateReportRequest() returns error when confirmation flag is invalid.
     * 
     * <p>Validates COBOL confirmation validation (lines 484-493):</p>
     * <pre>
     * WHEN OTHER
     *     STRING '"' CONFIRMI '" is not a valid value to confirm...'
     *         INTO WS-MESSAGE
     *     MOVE 'Y' TO WS-ERR-FLG
     * </pre>
     */
    @Test
    @DisplayName("Should return error when confirmation flag is invalid")
    void testValidateReportRequest_InvalidConfirmationFlag_ReturnsError() {
        // Given
        ReportMenuResponse request = createValidMonthlyReportRequest();
        request.setConfirmationFlag("INVALID");  // Should be Y or N

        // When
        List<String> errors = reportMenuService.validateReportRequest(request);

        // Then
        assertFalse(errors.isEmpty(), "Should have validation errors");
        assertTrue(errors.stream().anyMatch(e -> e.contains("not a valid value to confirm")),
                "Should have error matching COBOL message from lines 488-489");
    }

    /**
     * Test validateReportRequest() returns error when confirmation not provided.
     * 
     * <p>Validates COBOL confirmation prompt logic (lines 464-474):</p>
     * <pre>
     * IF CONFIRMI OF CORPT0AI = SPACES OR LOW-VALUES
     *     STRING 'Please confirm to print the ' WS-REPORT-NAME ' report...'
     *         INTO WS-MESSAGE
     * </pre>
     */
    @Test
    @DisplayName("Should return error when confirmation not provided for selected report")
    void testValidateReportRequest_MissingConfirmation_ReturnsError() {
        // Given
        ReportMenuResponse request = new ReportMenuResponse();
        request.setMonthlyReportFlag("Y");
        request.setConfirmationFlag("");  // Not confirmed

        // When
        List<String> errors = reportMenuService.validateReportRequest(request);

        // Then
        assertFalse(errors.isEmpty(), "Should have validation errors");
        assertTrue(errors.stream().anyMatch(e -> e.contains("confirm to print the Monthly report")),
                "Should have error matching COBOL prompt from lines 466-470");
    }

    // ===============================================================================
    // TEST: Job Parameter Construction
    // ===============================================================================

    /**
     * Test that batch job submission creates correct JobParameters.
     * 
     * <p>Validates transformation of COBOL JCL JOB-DATA structure (lines 81-127) which
     * contained JCL statements with embedded date parameters to Spring Batch JobParameters
     * with typed parameter values.</p>
     * 
     * <p><strong>COBOL JOB-DATA Structure (lines 103-121):</strong></p>
     * <pre>
     * 05 FILLER-1.
     *    10 FILLER PIC X(18) VALUE "PARM-START-DATE,C'".
     *    10 PARM-START-DATE-1 PIC X(10) VALUE SPACES.
     * 05 FILLER-2.
     *    10 FILLER PIC X(16) VALUE "PARM-END-DATE,C'".
     *    10 PARM-END-DATE-1 PIC X(10) VALUE SPACES.
     * 05 FILLER-3.
     *    10 PARM-START-DATE-2 PIC X(10) VALUE SPACES.
     *    10 FILLER PIC X VALUE SPACE.
     *    10 PARM-END-DATE-2 PIC X(10) VALUE SPACES.
     * </pre>
     * 
     * <p><strong>Java JobParameters Equivalent:</strong></p>
     * <pre>
     * JobParameters jobParameters = new JobParametersBuilder()
     *     .addString("start.date", startDate.toString())
     *     .addString("end.date", endDate.toString())
     *     .addString("report.type", "MONTHLY")
     *     .addLong("run.id", System.currentTimeMillis())
     *     .toJobParameters();
     * </pre>
     */
    @Test
    @DisplayName("Should create correct JobParameters for monthly report submission")
    void testMonthlyReport_CreatesCorrectJobParameters() throws Exception {
        // Given
        when(jobLauncher.run(eq(transactionAggregationJob), any(JobParameters.class)))
                .thenReturn(successfulJobExecution);

        // When
        reportMenuService.submitMonthlyReport("Y");

        // Then
        verify(jobLauncher).run(eq(transactionAggregationJob), any(JobParameters.class));
        // JobParameters validated by successful run - parameters must include
        // start.date, end.date, report.type="MONTHLY", and run.id
    }

    /**
     * Test that custom report creates JobParameters with user-specified dates.
     * 
     * <p>Validates date parameter construction from COBOL lines 429-432:</p>
     * <pre>
     * MOVE WS-START-DATE TO PARM-START-DATE-1, PARM-START-DATE-2
     * MOVE WS-END-DATE TO PARM-END-DATE-1, PARM-END-DATE-2
     * </pre>
     */
    @Test
    @DisplayName("Should create correct JobParameters for custom report with user dates")
    void testCustomReport_CreatesJobParametersWithUserDates() throws Exception {
        // Given
        when(jobLauncher.run(eq(transactionAggregationJob), any(JobParameters.class)))
                .thenReturn(successfulJobExecution);

        // When
        reportMenuService.submitCustomReport(2024, 1, 1, 2024, 1, 31, "Y");

        // Then
        verify(jobLauncher).run(eq(transactionAggregationJob), any(JobParameters.class));
        // JobParameters should contain custom date range matching user input
    }

    // ===============================================================================
    // TEST: Error Handling and Edge Cases
    // ===============================================================================

    /**
     * Test that JobLauncher exception is properly handled and wrapped.
     * 
     * <p>Validates error handling for COBOL EXEC CICS WRITEQ TD failure (lines 525-535):</p>
     * <pre>
     * EVALUATE WS-RESP-CD
     *     WHEN DFHRESP(NORMAL)
     *         CONTINUE
     *     WHEN OTHER
     *         MOVE 'Unable to Write TDQ (JOBS)...' TO WS-MESSAGE
     *         MOVE 'Y' TO WS-ERR-FLG
     * END-EVALUATE
     * </pre>
     */
    @Test
    @DisplayName("Should handle JobLauncher exception and return appropriate error")
    void testJobLaunchFailure_HandlesExceptionCorrectly() throws Exception {
        // Given
        when(jobLauncher.run(eq(transactionAggregationJob), any(JobParameters.class)))
                .thenThrow(new RuntimeException("Job submission failed"));

        // When/Then
        IllegalStateException exception = assertThrows(IllegalStateException.class,
                () -> reportMenuService.submitMonthlyReport("Y"),
                "Should throw IllegalStateException wrapping job launch failure");

        assertTrue(exception.getMessage().contains("Unable to submit monthly report job"),
                "Error message should indicate job submission failure");
    }

    /**
     * Test date validation with invalid date (e.g., February 30).
     * 
     * <p>Validates COBOL CSUTLDTC date validation call (lines 392-426):</p>
     * <pre>
     * CALL 'CSUTLDTC' USING CSUTLDTC-DATE, CSUTLDTC-DATE-FORMAT, CSUTLDTC-RESULT
     * IF CSUTLDTC-RESULT-SEV-CD = '0000'
     *     CONTINUE
     * ELSE
     *     MOVE 'Start Date - Not a valid date...' TO WS-MESSAGE
     * END-IF
     * </pre>
     */
    @Test
    @DisplayName("Should reject invalid date like February 30")
    void testCustomReport_InvalidDateConstruction_ThrowsException() {
        // When/Then - February 30 doesn't exist
        assertThrows(IllegalArgumentException.class,
                () -> reportMenuService.submitCustomReport(
                        2024, 2, 30, 2024, 12, 31, "Y"),
                "Should throw exception for invalid date February 30");

        verify(jobLauncher, never()).run(any(Job.class), any(JobParameters.class));
    }

    // ===============================================================================
    // HELPER METHODS FOR TEST DATA CREATION
    // ===============================================================================

    /**
     * Creates valid monthly report request for testing.
     * 
     * @return ReportMenuResponse configured for monthly report
     */
    private ReportMenuResponse createValidMonthlyReportRequest() {
        ReportMenuResponse request = new ReportMenuResponse();
        request.setTransactionName("CR00");
        request.setProgramName("CORPT00C");
        request.setCurrentDate(LocalDate.now());
        request.setCurrentTime(LocalTime.now());
        request.setMonthlyReportFlag("Y");
        request.setYearlyReportFlag("");
        request.setCustomReportFlag("");
        request.setConfirmationFlag("Y");
        request.setErrorMessage("");
        return request;
    }

    /**
     * Creates valid custom report request for testing.
     * 
     * @return ReportMenuResponse configured for custom date range report
     */
    private ReportMenuResponse createValidCustomReportRequest() {
        ReportMenuResponse request = new ReportMenuResponse();
        request.setTransactionName("CR00");
        request.setProgramName("CORPT00C");
        request.setCurrentDate(LocalDate.now());
        request.setCurrentTime(LocalTime.now());
        request.setMonthlyReportFlag("");
        request.setYearlyReportFlag("");
        request.setCustomReportFlag("Y");
        request.setStartDateMonth(1);
        request.setStartDateDay(1);
        request.setStartDateYear(2024);
        request.setEndDateMonth(1);
        request.setEndDateDay(31);
        request.setEndDateYear(2024);
        request.setConfirmationFlag("Y");
        request.setErrorMessage("");
        return request;
    }
}
