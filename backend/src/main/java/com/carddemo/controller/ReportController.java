/*
 * ReportController.java
 * 
 * CardDemo Application - Report Generation REST Controller
 * 
 * Transforms COBOL CICS transaction program CORPT00C.cbl to Spring Boot REST API.
 * Provides RESTful endpoints for report menu display and report generation job submission.
 * 
 * Original COBOL Program: CORPT00C.cbl (Transaction ID: CR00)
 * Location: app/cbl/CORPT00C.cbl
 * 
 * COBOL-to-Java Transformation Summary:
 * ======================================
 * 
 * 1. CICS Transaction → REST Endpoints:
 *    - CICS SEND MAP('CORPT00') → GET /api/reports (report menu)
 *    - CICS RECEIVE MAP('CORPT00') → POST /api/reports/monthly (monthly report)
 *    - CICS XCTL to batch submission → POST /api/reports/yearly (yearly report)
 *    - EXEC CICS WRITEQ TD → POST /api/reports/custom (custom date range)
 * 
 * 2. WORKING-STORAGE to Service Layer:
 *    - WS-REPORT-NAME (PIC X(10)) → reportType path variable
 *    - WS-START-DATE/WS-END-DATE → LocalDate parameters
 *    - WS-MESSAGE (PIC X(80)) → ErrorResponse DTO errorMessage
 *    - CONFIRMI → confirmationFlag request parameter
 * 
 * 3. PROCEDURE DIVISION to Controller Methods:
 *    - MAIN-PARA → getReportMenu() method
 *    - PROCESS-ENTER-KEY → executeReport() methods
 *    - SUBMIT-JOB-TO-INTRDR → service.submitMonthlyReport() delegation
 * 
 * 4. Security Model Preservation:
 *    - Original: COBOL program access restricted to admin users
 *    - Modern: @PreAuthorize("hasRole('ADMIN')") on all endpoints
 *    - Maintains ROLE_ADMIN authorization from USRSEC file validation
 * 
 * 5. Error Handling Transformation:
 *    - COBOL WS-ERR-FLG and WS-MESSAGE → Spring exception handling
 *    - CICS RESP/RESP2 codes → HTTP status codes
 *    - Field validation errors → HTTP 400 Bad Request with error messages
 * 
 * REST API Endpoints:
 * ===================
 * 
 * GET /api/reports
 *   - Returns available report types and descriptions
 *   - Replaces BMS map CORPT00M display
 *   - HTTP 200 OK with ReportMenuResponse
 * 
 * POST /api/reports/monthly
 *   - Submits monthly transaction aggregation report job
 *   - Replaces COBOL lines 217-238 monthly report logic
 *   - Requires confirmationFlag='Y' parameter
 *   - HTTP 200 OK with JobExecution details
 *   - HTTP 400 Bad Request if confirmation invalid
 * 
 * POST /api/reports/yearly
 *   - Submits yearly transaction aggregation report job
 *   - Replaces COBOL lines 239-255 yearly report logic
 *   - Requires confirmationFlag='Y' parameter
 *   - HTTP 200 OK with JobExecution details
 *   - HTTP 400 Bad Request if confirmation invalid
 * 
 * POST /api/reports/custom
 *   - Submits custom date range transaction aggregation report
 *   - Replaces COBOL lines 256-435 custom report logic
 *   - Requires startDate, endDate, confirmationFlag parameters
 *   - HTTP 200 OK with JobExecution details
 *   - HTTP 400 Bad Request if date validation fails
 * 
 * Copyright Amazon.com, Inc. or its affiliates.
 * All Rights Reserved.
 * 
 * Licensed under the Apache License, Version 2.0 (the "License").
 * You may not use this file except in compliance with the License.
 */
package com.carddemo.controller;

import com.carddemo.dto.response.ReportMenuResponse;
import com.carddemo.service.ReportMenuService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.batch.core.JobExecution;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * REST controller for report generation functionality.
 * 
 * <p>Provides endpoints for displaying the report menu and submitting various types of
 * transaction aggregation reports. All endpoints require ROLE_ADMIN authorization matching
 * the original mainframe security model where only administrative users can generate reports.</p>
 * 
 * <p><b>COBOL Program Transformation:</b></p>
 * <p>This controller transforms the CORPT00C.cbl CICS transaction program which displayed
 * a 3270 screen-based report menu and submitted JCL batch jobs via internal reader TDQ.
 * The modern implementation exposes RESTful endpoints and submits Spring Batch jobs via
 * JobLauncher instead of writing JCL to transient data queues.</p>
 * 
 * <p><b>Authorization Model:</b></p>
 * <p>All methods are annotated with @PreAuthorize("hasRole('ADMIN')") to ensure only users
 * with administrative privileges can access report generation functionality. This preserves
 * the mainframe security model where report access was restricted.</p>
 * 
 * <p><b>Error Handling:</b></p>
 * <p>Controller methods return appropriate HTTP status codes:
 * <ul>
 *   <li>200 OK: Successful report menu retrieval or job submission</li>
 *   <li>400 Bad Request: Invalid parameters, date validation failure, or confirmation rejection</li>
 *   <li>401 Unauthorized: Missing authentication token</li>
 *   <li>403 Forbidden: Non-admin user attempting report access</li>
 *   <li>500 Internal Server Error: Job submission failure or unexpected errors</li>
 * </ul>
 * </p>
 * 
 * @author AWS CardDemo Migration Team
 * @version 1.0
 * @see com.carddemo.service.ReportMenuService
 * @see com.carddemo.dto.response.ReportMenuResponse
 * @since 1.0
 */
@Slf4j
@RestController
@RequestMapping("/api/reports")
public class ReportController {

    /**
     * Service layer for report generation business logic.
     * Injected via Spring dependency injection to replace COBOL CALL statements.
     */
    private final ReportMenuService reportMenuService;

    /**
     * Date formatter for parsing and formatting date strings.
     * Matches COBOL date format WS-DATE-FORMAT PIC X(10) VALUE 'YYYY-MM-DD'.
     */
    private static final DateTimeFormatter DATE_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd");

    /**
     * Constructor-based dependency injection of ReportMenuService.
     * 
     * @param reportMenuService Service layer for report generation operations
     */
    @Autowired
    public ReportController(ReportMenuService reportMenuService) {
        this.reportMenuService = reportMenuService;
        logger.info("ReportController initialized with ReportMenuService");
    }

    /**
     * Retrieves the report menu with available report types and descriptions.
     * 
     * <p>Transforms COBOL MAIN-PARA paragraph (lines 195-206) which displayed the BMS
     * map CORPT00M on the 3270 terminal. This modern REST endpoint returns a JSON
     * representation of the report menu structure for web UI consumption.</p>
     * 
     * <p><b>COBOL Equivalent Logic (lines 195-206):</b></p>
     * <pre>
     * MAIN-PARA.
     *     MOVE LOW-VALUES TO CORPT0AI
     *                        CORPT0AO
     *     PERFORM POPULATE-HEADER-INFO
     *     EXEC CICS SEND MAP('CORPT00')
     *                   MAPSET('CORPT00M')
     *                   ERASE
     *                   CURSOR
     *     END-EXEC
     *     EXEC CICS RETURN TRANSID(WS-TRANID) END-EXEC
     * </pre>
     * 
     * <p><b>REST API Specification:</b></p>
     * <ul>
     *   <li>Method: GET</li>
     *   <li>Path: /api/reports</li>
     *   <li>Response: HTTP 200 OK with ReportMenuResponse JSON</li>
     *   <li>Authorization: ROLE_ADMIN required</li>
     * </ul>
     * 
     * <p><b>Response Structure:</b></p>
     * <p>The ReportMenuResponse contains:</p>
     * <ul>
     *   <li>availableReports: List of report types (Monthly, Yearly, Custom)</li>
     *   <li>screenTitle: Report menu screen title</li>
     *   <li>currentDate: Current date in YYYY-MM-DD format</li>
     *   <li>currentTime: Current time in HH:MM:SS format</li>
     * </ul>
     * 
     * <p><b>Security:</b></p>
     * <p>Only users with ROLE_ADMIN authority can access this endpoint. Non-admin users
     * receive HTTP 403 Forbidden response matching the mainframe restriction where only
     * administrative users had access to report generation functions.</p>
     * 
     * @return ResponseEntity containing ReportMenuResponse with available report types
     */
    @PreAuthorize("hasRole('ADMIN')")
    @GetMapping
    public ResponseEntity<ReportMenuResponse> getReportMenu() {
        logger.info("GET /api/reports - Retrieving report menu");
        
        try {
            // Retrieve available report types from service (replaces POPULATE-HEADER-INFO)
            ReportMenuResponse response = reportMenuService.getAvailableReportTypes();
            
            logger.debug("Report menu retrieved successfully with {} report types", 
                    response.getAvailableReports() != null ? response.getAvailableReports().size() : 0);
            
            return ResponseEntity.ok(response);
            
        } catch (Exception e) {
            logger.error("Error retrieving report menu", e);
            // Return error response with generic message
            ReportMenuResponse errorResponse = new ReportMenuResponse();
            errorResponse.setErrorMessage("Unable to retrieve report menu: " + e.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(errorResponse);
        }
    }

    /**
     * Submits monthly transaction aggregation report for current month.
     * 
     * <p>Transforms COBOL monthly report submission logic (lines 217-238) which calculated
     * the current month's date range and submitted a JCL batch job via internal reader TDQ.
     * This modern endpoint delegates to ReportMenuService which launches a Spring Batch job
     * instead of writing JCL to a transient data queue.</p>
     * 
     * <p><b>COBOL Equivalent Logic (lines 217-238):</b></p>
     * <pre>
     * WHEN MONTHLYI OF CORPT0AI = 'Y' OR 'y'
     *     MOVE 'Monthly' TO WS-REPORT-NAME
     *     MOVE FUNCTION CURRENT-DATE TO WS-CURDATE-DATA
     *     MOVE WS-CURDATE-YEAR  TO WS-START-DATE-YYYY
     *                              WS-END-DATE-YYYY
     *     MOVE WS-CURDATE-MONTH TO WS-START-DATE-MM
     *                              WS-END-DATE-MM
     *     MOVE '01' TO WS-START-DATE-DD
     *     MOVE WS-CURDATE-DAY TO WS-END-DATE-DD
     *     MOVE WS-START-DATE TO PARM-START-DATE-1
     *     MOVE WS-END-DATE TO PARM-END-DATE-1
     *     PERFORM CONFIRM-BEFORE-PRINT
     *     IF WS-ERR-FLG = 'N'
     *         PERFORM SUBMIT-JOB-TO-INTRDR
     *     END-IF
     * </pre>
     * 
     * <p><b>REST API Specification:</b></p>
     * <ul>
     *   <li>Method: POST</li>
     *   <li>Path: /api/reports/monthly</li>
     *   <li>Request Parameter: confirmationFlag (required, must be 'Y' or 'y')</li>
     *   <li>Response: HTTP 200 OK with job execution details</li>
     *   <li>Error: HTTP 400 Bad Request if confirmation invalid or missing</li>
     *   <li>Authorization: ROLE_ADMIN required</li>
     * </ul>
     * 
     * <p><b>Date Range Calculation:</b></p>
     * <p>Monthly report covers first day of current month through current day:
     * <ul>
     *   <li>Start Date: First day of current month (e.g., 2024-01-01)</li>
     *   <li>End Date: Current day (e.g., 2024-01-15)</li>
     * </ul>
     * </p>
     * 
     * <p><b>Confirmation Validation:</b></p>
     * <p>The confirmationFlag parameter must be provided and set to 'Y' or 'y' to confirm
     * report generation. Values of 'N' or 'n' will result in HTTP 400 Bad Request with
     * message "Monthly report generation cancelled by user". Any other value will return
     * HTTP 400 with message indicating invalid confirmation value.</p>
     * 
     * <p><b>Batch Job Submission:</b></p>
     * <p>Upon successful validation, the service submits a TransactionAggregationJob via
     * Spring Batch JobLauncher. The job execution details are returned to the caller
     * including job ID, status, start time, and parameters.</p>
     * 
     * <p><b>Security:</b></p>
     * <p>Only users with ROLE_ADMIN authority can submit monthly reports. This preserves
     * the mainframe security model where report generation was restricted to administrative
     * users per USRSEC file authorization rules.</p>
     * 
     * <p><b>Error Handling:</b></p>
     * <ul>
     *   <li>Missing confirmation: HTTP 400 "Please confirm to print the Monthly report..."</li>
     *   <li>Invalid confirmation: HTTP 400 with specific invalid value message</li>
     *   <li>User cancellation: HTTP 400 "Monthly report generation cancelled by user"</li>
     *   <li>Job submission failure: HTTP 500 with error details</li>
     * </ul>
     * 
     * @param confirmationFlag Confirmation flag ('Y' or 'y' to confirm, 'N' or 'n' to cancel)
     * @return ResponseEntity with job execution details on success, error message on failure
     */
    @PreAuthorize("hasRole('ADMIN')")
    @PostMapping("/monthly")
    public ResponseEntity<Map<String, Object>> submitMonthlyReport(
            @RequestParam(name = "confirmationFlag", required = true) String confirmationFlag) {
        
        logger.info("POST /api/reports/monthly - Submitting monthly report with confirmation: {}", 
                confirmationFlag);
        
        try {
            // Submit monthly report job (replaces COBOL PERFORM SUBMIT-JOB-TO-INTRDR)
            JobExecution jobExecution = reportMenuService.submitMonthlyReport(confirmationFlag);
            
            // Build response with job execution details
            Map<String, Object> response = buildJobExecutionResponse(jobExecution, "Monthly");
            
            logger.info("Monthly report job submitted successfully. Job ID: {}, Status: {}", 
                    jobExecution.getJobId(), jobExecution.getStatus());
            
            return ResponseEntity.ok(response);
            
        } catch (IllegalArgumentException e) {
            // Validation error or user cancellation (COBOL WS-ERR-FLG = 'Y')
            logger.warn("Monthly report submission validation failed: {}", e.getMessage());
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .body(buildErrorResponse(e.getMessage()));
            
        } catch (IllegalStateException e) {
            // Job submission failure
            logger.error("Monthly report job submission failed", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(buildErrorResponse("Unable to submit monthly report job: " + e.getMessage()));
            
        } catch (Exception e) {
            // Unexpected error
            logger.error("Unexpected error submitting monthly report", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(buildErrorResponse("Unexpected error: " + e.getMessage()));
        }
    }

    /**
     * Submits yearly transaction aggregation report for current year.
     * 
     * <p>Transforms COBOL yearly report submission logic (lines 239-255) which calculated
     * the current year's date range (January 1 through December 31) and submitted a JCL
     * batch job via internal reader TDQ. This modern endpoint delegates to ReportMenuService
     * which launches a Spring Batch job for yearly transaction aggregation.</p>
     * 
     * <p><b>COBOL Equivalent Logic (lines 239-255):</b></p>
     * <pre>
     * WHEN YEARLYI OF CORPT0AI = 'Y' OR 'y'
     *     MOVE 'Yearly' TO WS-REPORT-NAME
     *     MOVE FUNCTION CURRENT-DATE TO WS-CURDATE-DATA
     *     MOVE WS-CURDATE-YEAR TO WS-START-DATE-YYYY
     *                             WS-END-DATE-YYYY
     *     MOVE '01' TO WS-START-DATE-MM
     *                  WS-START-DATE-DD
     *     MOVE WS-START-DATE TO PARM-START-DATE-1
     *                           PARM-START-DATE-2
     *     MOVE '12' TO WS-END-DATE-MM
     *     MOVE '31' TO WS-END-DATE-DD
     *     MOVE WS-END-DATE TO PARM-END-DATE-1
     *                         PARM-END-DATE-2
     *     PERFORM CONFIRM-BEFORE-PRINT
     *     IF WS-ERR-FLG = 'N'
     *         PERFORM SUBMIT-JOB-TO-INTRDR
     *     END-IF
     * </pre>
     * 
     * <p><b>REST API Specification:</b></p>
     * <ul>
     *   <li>Method: POST</li>
     *   <li>Path: /api/reports/yearly</li>
     *   <li>Request Parameter: confirmationFlag (required, must be 'Y' or 'y')</li>
     *   <li>Response: HTTP 200 OK with job execution details</li>
     *   <li>Error: HTTP 400 Bad Request if confirmation invalid or missing</li>
     *   <li>Authorization: ROLE_ADMIN required</li>
     * </ul>
     * 
     * <p><b>Date Range Calculation:</b></p>
     * <p>Yearly report covers entire current calendar year:
     * <ul>
     *   <li>Start Date: January 1 of current year (e.g., 2024-01-01)</li>
     *   <li>End Date: December 31 of current year (e.g., 2024-12-31)</li>
     * </ul>
     * </p>
     * 
     * <p><b>Confirmation Validation:</b></p>
     * <p>The confirmationFlag parameter must be provided and set to 'Y' or 'y' to confirm
     * report generation. Values of 'N' or 'n' will result in HTTP 400 Bad Request with
     * message "Yearly report generation cancelled by user". Any other value will return
     * HTTP 400 with message indicating invalid confirmation value.</p>
     * 
     * <p><b>Batch Job Submission:</b></p>
     * <p>Upon successful validation, the service submits a TransactionAggregationJob via
     * Spring Batch JobLauncher with job parameters indicating yearly report type and the
     * calculated date range. The job execution details are returned including job ID,
     * status, start time, and all job parameters.</p>
     * 
     * <p><b>Security:</b></p>
     * <p>Only users with ROLE_ADMIN authority can submit yearly reports. This preserves
     * the mainframe security model where report generation was restricted to administrative
     * users with elevated privileges per COBOL security validation logic.</p>
     * 
     * <p><b>Error Handling:</b></p>
     * <ul>
     *   <li>Missing confirmation: HTTP 400 "Please confirm to print the Yearly report..."</li>
     *   <li>Invalid confirmation: HTTP 400 with specific invalid value message</li>
     *   <li>User cancellation: HTTP 400 "Yearly report generation cancelled by user"</li>
     *   <li>Job submission failure: HTTP 500 with error details</li>
     * </ul>
     * 
     * @param confirmationFlag Confirmation flag ('Y' or 'y' to confirm, 'N' or 'n' to cancel)
     * @return ResponseEntity with job execution details on success, error message on failure
     */
    @PreAuthorize("hasRole('ADMIN')")
    @PostMapping("/yearly")
    public ResponseEntity<Map<String, Object>> submitYearlyReport(
            @RequestParam(name = "confirmationFlag", required = true) String confirmationFlag) {
        
        logger.info("POST /api/reports/yearly - Submitting yearly report with confirmation: {}", 
                confirmationFlag);
        
        try {
            // Submit yearly report job (replaces COBOL PERFORM SUBMIT-JOB-TO-INTRDR)
            JobExecution jobExecution = reportMenuService.submitYearlyReport(confirmationFlag);
            
            // Build response with job execution details
            Map<String, Object> response = buildJobExecutionResponse(jobExecution, "Yearly");
            
            logger.info("Yearly report job submitted successfully. Job ID: {}, Status: {}", 
                    jobExecution.getJobId(), jobExecution.getStatus());
            
            return ResponseEntity.ok(response);
            
        } catch (IllegalArgumentException e) {
            // Validation error or user cancellation (COBOL WS-ERR-FLG = 'Y')
            logger.warn("Yearly report submission validation failed: {}", e.getMessage());
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .body(buildErrorResponse(e.getMessage()));
            
        } catch (IllegalStateException e) {
            // Job submission failure
            logger.error("Yearly report job submission failed", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(buildErrorResponse("Unable to submit yearly report job: " + e.getMessage()));
            
        } catch (Exception e) {
            // Unexpected error
            logger.error("Unexpected error submitting yearly report", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(buildErrorResponse("Unexpected error: " + e.getMessage()));
        }
    }

    /**
     * Submits custom date range transaction aggregation report.
     * 
     * <p>Transforms COBOL custom report submission logic (lines 256-435) which performed
     * extensive validation of user-provided start and end date components, verified date
     * validity via CSUTLDTC utility call, and submitted a JCL batch job with the specified
     * date range. This modern endpoint validates date parameters and delegates to
     * ReportMenuService for Spring Batch job submission.</p>
     * 
     * <p><b>COBOL Equivalent Logic (lines 256-435):</b></p>
     * <p>Original COBOL performed multi-step validation:</p>
     * <ol>
     *   <li>Lines 258-300: Check each date component (month, day, year) for presence</li>
     *   <li>Lines 305-327: Convert input fields to numeric using FUNCTION NUMVAL-C</li>
     *   <li>Lines 329-336: Validate start month range 1-12</li>
     *   <li>Lines 338-345: Validate start day range 1-31</li>
     *   <li>Lines 347-353: Validate start year is numeric</li>
     *   <li>Lines 355-362: Validate end month range 1-12</li>
     *   <li>Lines 364-371: Validate end day range 1-31</li>
     *   <li>Lines 373-379: Validate end year is numeric</li>
     *   <li>Lines 381-426: Validate complete dates via CALL 'CSUTLDTC'</li>
     *   <li>Lines 429-435: Build JCL parameters and submit job</li>
     * </ol>
     * 
     * <p><b>REST API Specification:</b></p>
     * <ul>
     *   <li>Method: POST</li>
     *   <li>Path: /api/reports/custom</li>
     *   <li>Request Parameters:
     *     <ul>
     *       <li>startYear (required, Integer, 1900-2100)</li>
     *       <li>startMonth (required, Integer, 1-12)</li>
     *       <li>startDay (required, Integer, 1-31)</li>
     *       <li>endYear (required, Integer, 1900-2100)</li>
     *       <li>endMonth (required, Integer, 1-12)</li>
     *       <li>endDay (required, Integer, 1-31)</li>
     *       <li>confirmationFlag (required, String, 'Y' or 'y')</li>
     *     </ul>
     *   </li>
     *   <li>Response: HTTP 200 OK with job execution details</li>
     *   <li>Error: HTTP 400 Bad Request if validation fails</li>
     *   <li>Authorization: ROLE_ADMIN required</li>
     * </ul>
     * 
     * <p><b>Date Validation:</b></p>
     * <p>All date components are validated for presence, range, and calendar validity:
     * <ul>
     *   <li>Month: 1-12 (January through December)</li>
     *   <li>Day: 1-31 (validated against month and leap year rules)</li>
     *   <li>Year: 1900-2100 (reasonable date range boundaries)</li>
     *   <li>Complete Date: Must form valid calendar date (e.g., no February 30)</li>
     *   <li>Date Range: Start date must be less than or equal to end date</li>
     * </ul>
     * </p>
     * 
     * <p><b>Java Date Validation Advantage:</b></p>
     * <p>Unlike COBOL which required external CSUTLDTC utility call for date validation,
     * Java's LocalDate class provides built-in validation. Invalid dates automatically
     * throw DateTimeException, eliminating need for external date utility programs.</p>
     * 
     * <p><b>Confirmation Validation:</b></p>
     * <p>The confirmationFlag parameter must be provided and set to 'Y' or 'y' to confirm
     * report generation. Values of 'N' or 'n' result in HTTP 400 Bad Request with message
     * "Custom report generation cancelled by user". Any other value returns HTTP 400 with
     * message indicating invalid confirmation value.</p>
     * 
     * <p><b>Batch Job Submission:</b></p>
     * <p>Upon successful validation, the service submits a TransactionAggregationJob via
     * Spring Batch JobLauncher with job parameters containing the user-specified date range.
     * The job processes all transactions within the specified date range and generates
     * aggregated report data.</p>
     * 
     * <p><b>Security:</b></p>
     * <p>Only users with ROLE_ADMIN authority can submit custom reports. This preserves
     * the mainframe security model where report generation with arbitrary date ranges was
     * restricted to administrative users with elevated privileges.</p>
     * 
     * <p><b>Error Handling:</b></p>
     * <ul>
     *   <li>Missing date components: HTTP 400 with specific field name</li>
     *   <li>Invalid month range: HTTP 400 "Start/End Date - Not a valid Month..."</li>
     *   <li>Invalid day range: HTTP 400 "Start/End Date - Not a valid Day..."</li>
     *   <li>Invalid year range: HTTP 400 "Start/End Date - Not a valid Year..."</li>
     *   <li>Invalid calendar date: HTTP 400 "Start/End Date - Not a valid date..."</li>
     *   <li>Invalid date range: HTTP 400 "Start date must be less than or equal to end date"</li>
     *   <li>Missing confirmation: HTTP 400 "Please confirm to print the Custom report..."</li>
     *   <li>User cancellation: HTTP 400 "Custom report generation cancelled by user"</li>
     *   <li>Job submission failure: HTTP 500 with error details</li>
     * </ul>
     * 
     * @param startYear Start date year component (YYYY format, e.g., 2024)
     * @param startMonth Start date month component (1-12)
     * @param startDay Start date day component (1-31)
     * @param endYear End date year component (YYYY format)
     * @param endMonth End date month component (1-12)
     * @param endDay End date day component (1-31)
     * @param confirmationFlag Confirmation flag ('Y' or 'y' to confirm, 'N' or 'n' to cancel)
     * @return ResponseEntity with job execution details on success, error message on failure
     */
    @PreAuthorize("hasRole('ADMIN')")
    @PostMapping("/custom")
    public ResponseEntity<Map<String, Object>> submitCustomReport(
            @RequestParam(name = "startYear", required = true) Integer startYear,
            @RequestParam(name = "startMonth", required = true) Integer startMonth,
            @RequestParam(name = "startDay", required = true) Integer startDay,
            @RequestParam(name = "endYear", required = true) Integer endYear,
            @RequestParam(name = "endMonth", required = true) Integer endMonth,
            @RequestParam(name = "endDay", required = true) Integer endDay,
            @RequestParam(name = "confirmationFlag", required = true) String confirmationFlag) {
        
        logger.info("POST /api/reports/custom - Submitting custom report: {}/{}/{} to {}/{}/{} with confirmation: {}",
                startMonth, startDay, startYear, endMonth, endDay, endYear, confirmationFlag);
        
        try {
            // Submit custom report job with date range validation (replaces COBOL lines 256-435)
            JobExecution jobExecution = reportMenuService.submitCustomReport(
                    startYear, startMonth, startDay,
                    endYear, endMonth, endDay,
                    confirmationFlag);
            
            // Build response with job execution details
            Map<String, Object> response = buildJobExecutionResponse(jobExecution, "Custom");
            
            // Add date range to response for client confirmation
            LocalDate startDate = LocalDate.of(startYear, startMonth, startDay);
            LocalDate endDate = LocalDate.of(endYear, endMonth, endDay);
            response.put("startDate", startDate.format(DATE_FORMATTER));
            response.put("endDate", endDate.format(DATE_FORMATTER));
            
            logger.info("Custom report job submitted successfully. Job ID: {}, Status: {}, Date Range: {} to {}", 
                    jobExecution.getJobId(), jobExecution.getStatus(), startDate, endDate);
            
            return ResponseEntity.ok(response);
            
        } catch (IllegalArgumentException e) {
            // Validation error or user cancellation (COBOL WS-ERR-FLG = 'Y')
            logger.warn("Custom report submission validation failed: {}", e.getMessage());
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .body(buildErrorResponse(e.getMessage()));
            
        } catch (DateTimeParseException e) {
            // Date parsing error
            logger.error("Custom report date parsing failed", e);
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .body(buildErrorResponse("Invalid date format: " + e.getMessage()));
            
        } catch (IllegalStateException e) {
            // Job submission failure
            logger.error("Custom report job submission failed", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(buildErrorResponse("Unable to submit custom report job: " + e.getMessage()));
            
        } catch (Exception e) {
            // Unexpected error
            logger.error("Unexpected error submitting custom report", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(buildErrorResponse("Unexpected error: " + e.getMessage()));
        }
    }

    /**
     * Builds a standardized success response for job execution details.
     * 
     * <p>Transforms COBOL job submission response data (which was implicit in JCL submission)
     * to a structured JSON response containing job execution metadata. This provides the
     * web UI with job tracking information matching the mainframe batch job monitoring
     * capabilities.</p>
     * 
     * <p><b>Response Structure:</b></p>
     * <p>The response map contains:</p>
     * <ul>
     *   <li>reportType: Type of report (Monthly, Yearly, Custom)</li>
     *   <li>jobId: Spring Batch job execution ID for tracking</li>
     *   <li>jobStatus: Current job status (STARTING, STARTED, COMPLETED, FAILED)</li>
     *   <li>startTime: Job start timestamp in ISO-8601 format</li>
     *   <li>endTime: Job end timestamp (null if still running)</li>
     *   <li>jobParameters: Map of job parameters (dates, report type, etc.)</li>
     *   <li>message: Success message confirming job submission</li>
     * </ul>
     * 
     * <p><b>COBOL Context:</b></p>
     * <p>In COBOL, job submission via internal reader TDQ provided limited feedback.
     * This modern approach returns comprehensive job tracking information enabling
     * real-time status monitoring via additional API calls if needed.</p>
     * 
     * @param jobExecution Spring Batch JobExecution instance with job details
     * @param reportType Type of report being generated (Monthly, Yearly, Custom)
     * @return Map containing formatted job execution details for JSON serialization
     */
    private Map<String, Object> buildJobExecutionResponse(JobExecution jobExecution, String reportType) {
        Map<String, Object> response = new HashMap<>();
        
        response.put("reportType", reportType);
        response.put("jobId", jobExecution.getJobId());
        response.put("jobStatus", jobExecution.getStatus().name());
        response.put("startTime", jobExecution.getStartTime());
        response.put("endTime", jobExecution.getEndTime());
        
        // Extract job parameters for response
        Map<String, Object> jobParams = new HashMap<>();
        jobExecution.getJobParameters().getParameters().forEach((key, value) -> 
                jobParams.put(key, value.getValue()));
        response.put("jobParameters", jobParams);
        
        response.put("message", reportType + " report job submitted successfully. Job ID: " 
                + jobExecution.getJobId());
        
        return response;
    }

    /**
     * Builds a standardized error response for validation failures.
     * 
     * <p>Transforms COBOL error message patterns (WS-MESSAGE field in CORPT00C) to
     * structured JSON error response. Provides consistent error message format across
     * all report endpoints matching the mainframe error display patterns.</p>
     * 
     * <p><b>COBOL Error Message Context:</b></p>
     * <p>In COBOL CORPT00C, error messages were displayed in the WS-MESSAGE field
     * (PIC X(80)) and sent to the terminal screen via BMS map CORPT00M. This modern
     * approach returns error messages as JSON for web UI display.</p>
     * 
     * <p><b>Error Response Structure:</b></p>
     * <p>The response map contains:</p>
     * <ul>
     *   <li>error: Boolean flag set to true</li>
     *   <li>errorMessage: Detailed error message text</li>
     *   <li>timestamp: Error timestamp in ISO-8601 format</li>
     * </ul>
     * 
     * <p><b>Common Error Messages:</b></p>
     * <ul>
     *   <li>Confirmation errors: "Please confirm to print the [Report Type] report..."</li>
     *   <li>Cancellation: "[Report Type] report generation cancelled by user"</li>
     *   <li>Date validation: "Start Date - Not a valid [Month/Day/Year]..."</li>
     *   <li>Date range: "Start date must be less than or equal to end date"</li>
     * </ul>
     * 
     * @param errorMessage Detailed error message text
     * @return Map containing formatted error details for JSON serialization
     */
    private Map<String, Object> buildErrorResponse(String errorMessage) {
        Map<String, Object> response = new HashMap<>();
        response.put("error", true);
        response.put("errorMessage", errorMessage);
        response.put("timestamp", LocalDate.now().atStartOfDay().format(
                DateTimeFormatter.ISO_LOCAL_DATE_TIME));
        return response;
    }
}




