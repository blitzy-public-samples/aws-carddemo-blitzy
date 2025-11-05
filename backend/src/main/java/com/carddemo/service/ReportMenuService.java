/*
 * ReportMenuService.java
 * 
 * Service class for report generation menu operations and batch job orchestration.
 * Transformed from COBOL CICS program CORPT00C.cbl transaction CR00 (Report Menu).
 * 
 * Original COBOL Program: app/cbl/CORPT00C.cbl (650 lines)
 * Function: Print Transaction reports by submitting batch job from online using TDQ
 * Transaction ID: CR00 (Report Menu transaction)
 * 
 * Key Transformations from COBOL:
 * - CORPT00C PROCEDURE DIVISION → ReportMenuService methods
 * - PROCESS-ENTER-KEY paragraph (lines 208-456) → submit*Report() methods
 * - SUBMIT-JOB-TO-INTRDR (lines 462-510) → Spring Batch JobLauncher invocation
 * - EXEC CICS WRITEQ TD (lines 517-523) → JobLauncher.run() calls
 * - Report type selection EVALUATE (lines 212-443) → separate service methods
 * - Date validation CALL CSUTLDTC (lines 392-426) → Java LocalDate validation
 * - JCL JOB-DATA structure (lines 81-127) → Spring Batch JobParameters
 * - Confirmation prompt logic (lines 464-510) → validateReportRequest()
 * 
 * Business Logic Preservation (per Section 0.9):
 * - Monthly report: Current month start to end (lines 213-238)
 * - Yearly report: Current year January 1 to December 31 (lines 239-255)
 * - Custom report: User-provided date range with validation (lines 256-436)
 * - Confirmation required before job submission (lines 464-474)
 * - Authorization: ROLE_ADMIN only per COBOL administrative function
 * 
 * Report Job Orchestration:
 * - Monthly/Yearly → TransactionAggregationJob with computed date ranges
 * - Custom → TransactionAggregationJob with user-specified dates
 * - Statement generation → StatementGenerationJob for account statements
 * 
 * Security Model (per Section 0.9):
 * - @PreAuthorize("hasRole('ADMIN')") on all report generation methods
 * - Maps COBOL USRSEC USER-TYPE='A' administrative authorization check
 * - Only administrative users can access report generation functions
 * 
 * Performance Requirements:
 * - Response time: Sub-200ms for menu display and validation
 * - Job submission: Asynchronous via JobLauncher
 * - No blocking on batch job completion
 * 
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * Licensed under the Apache License, Version 2.0 (the "License").
 */

package com.carddemo.service;

import com.carddemo.batch.job.StatementGenerationJob;
import com.carddemo.batch.job.TransactionAggregationJob;
import com.carddemo.constants.MessageConstants;
import com.carddemo.dto.response.ReportMenuResponse;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.JobExecution;
import org.springframework.batch.core.JobParameters;
import org.springframework.batch.core.JobParametersBuilder;
import org.springframework.batch.core.launch.JobLauncher;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;

/**
 * ReportMenuService - Report Generation Menu and Orchestration Service
 * 
 * <p>This service class provides report menu operations and orchestrates batch job submission
 * for report generation. It transforms COBOL CICS program CORPT00C.cbl which handled online
 * report submission via transient data queue (TDQ) JCL submission to modern Spring Batch
 * JobLauncher execution.</p>
 * 
 * <p><strong>COBOL Source Program Structure (CORPT00C.cbl):</strong></p>
 * <ul>
 *   <li>Lines 163-202: Main processing - Screen display and navigation control</li>
 *   <li>Lines 208-456: PROCESS-ENTER-KEY - Report type selection and validation</li>
 *   <li>Lines 213-238: Monthly report logic - Current month date range calculation</li>
 *   <li>Lines 239-255: Yearly report logic - Current year full date range</li>
 *   <li>Lines 256-436: Custom report logic - User date input validation</li>
 *   <li>Lines 462-510: SUBMIT-JOB-TO-INTRDR - JCL job submission via TDQ</li>
 *   <li>Lines 515-535: WIRTE-JOBSUB-TDQ - Write JCL records to JOBS TDQ</li>
 * </ul>
 * 
 * <p><strong>Report Types Supported:</strong></p>
 * <ol>
 *   <li><strong>Monthly Report:</strong> Transaction aggregation for current calendar month</li>
 *   <li><strong>Yearly Report:</strong> Transaction aggregation for current calendar year</li>
 *   <li><strong>Custom Report:</strong> Transaction aggregation for user-specified date range</li>
 *   <li><strong>Statement Generation:</strong> Monthly account statement reports</li>
 * </ol>
 * 
 * <p><strong>Date Range Calculation Logic (from COBOL lines 215-236):</strong></p>
 * <p>Monthly report calculates current month date range:</p>
 * <pre>
 * COBOL Logic:
 *   MOVE FUNCTION CURRENT-DATE TO WS-CURDATE-DATA
 *   MOVE WS-CURDATE-YEAR TO WS-START-DATE-YYYY
 *   MOVE WS-CURDATE-MONTH TO WS-START-DATE-MM
 *   MOVE '01' TO WS-START-DATE-DD
 *   
 *   ADD 1 TO WS-CURDATE-MONTH
 *   IF WS-CURDATE-MONTH > 12
 *       ADD 1 TO WS-CURDATE-YEAR
 *       MOVE 1 TO WS-CURDATE-MONTH
 *   END-IF
 *   COMPUTE WS-CURDATE-N = FUNCTION DATE-OF-INTEGER(
 *           FUNCTION INTEGER-OF-DATE(WS-CURDATE-N) - 1)
 * 
 * Java Equivalent:
 *   YearMonth currentMonth = YearMonth.now();
 *   LocalDate startDate = currentMonth.atDay(1);
 *   LocalDate endDate = currentMonth.atEndOfMonth();
 * </pre>
 * 
 * <p><strong>Custom Date Validation (from COBOL lines 258-426):</strong></p>
 * <p>Validation rules from original COBOL implementation:</p>
 * <ul>
 *   <li>All date components (month, day, year) must be present (lines 259-300)</li>
 *   <li>Month must be numeric 1-12 (lines 329-336)</li>
 *   <li>Day must be numeric 1-31 (lines 338-345)</li>
 *   <li>Year must be numeric (lines 347-353)</li>
 *   <li>Complete date must be valid per calendar (lines 392-426 CSUTLDTC call)</li>
 *   <li>Start date must be less than or equal to end date (implicit)</li>
 * </ul>
 * 
 * <p><strong>Job Submission Pattern (from COBOL lines 462-535):</strong></p>
 * <pre>
 * COBOL TDQ Write Pattern:
 *   PERFORM VARYING WS-IDX FROM 1 BY 1 UNTIL WS-IDX > 1000 OR END-LOOP-YES
 *       MOVE JOB-LINES(WS-IDX) TO JCL-RECORD
 *       EXEC CICS WRITEQ TD
 *           QUEUE ('JOBS')
 *           FROM (JCL-RECORD)
 *       END-EXEC
 *   END-PERFORM
 * 
 * Java Spring Batch Equivalent:
 *   JobParameters jobParameters = new JobParametersBuilder()
 *       .addString("start.date", startDate.toString())
 *       .addString("end.date", endDate.toString())
 *       .addLong("run.id", System.currentTimeMillis())
 *       .toJobParameters();
 *   JobExecution execution = jobLauncher.run(job, jobParameters);
 * </pre>
 * 
 * <p><strong>Authorization Requirements (per Section 0.9):</strong></p>
 * <p>All report generation methods require ROLE_ADMIN authorization. This preserves
 * the COBOL authorization check where only administrative users (USRSEC USER-TYPE='A')
 * could access the report menu transaction (CR00).</p>
 * 
 * <p><strong>Transaction Management:</strong></p>
 * <p>Job submission methods use @Transactional with READ_COMMITTED isolation to ensure
 * atomic persistence of job submission metadata. Matches COBOL EXEC CICS SYNCPOINT
 * transaction boundary semantics (per Section 0.9).</p>
 * 
 * <p><strong>Error Handling:</strong></p>
 * <ul>
 *   <li>IllegalArgumentException: Invalid date range or missing required parameters</li>
 *   <li>DateTimeParseException: Date component parsing failures</li>
 *   <li>JobExecutionException: Batch job submission failures</li>
 *   <li>All exceptions logged for operational monitoring</li>
 * </ul>
 * 
 * @author CardDemo Migration Team
 * @version 1.0
 * @since 2024-01-01
 * @see ReportMenuResponse Response DTO for report menu screen
 * @see TransactionAggregationJob Batch job for transaction reports
 * @see StatementGenerationJob Batch job for statement generation
 * @see MessageConstants Application message constants
 */
@Service
public class ReportMenuService {

    /**
     * SLF4J logger for service operation logging and error tracking.
     * Logs all report submissions, validations, and job execution results.
     */
    private static final Logger logger = LoggerFactory.getLogger(ReportMenuService.class);

    /**
     * Date formatter for job parameter date strings.
     * Format: yyyy-MM-dd (ISO-8601) matching Spring Batch JobParameters convention.
     */
    private static final DateTimeFormatter DATE_FORMATTER = DateTimeFormatter.ISO_LOCAL_DATE;

    /**
     * Program name constant for audit trail and response headers.
     * Maps to COBOL WS-PGMNAME (line 37): PIC X(08) VALUE 'CORPT00C'.
     */
    private static final String PROGRAM_NAME = "CORPT00C";

    /**
     * Transaction ID constant for audit trail and response headers.
     * Maps to COBOL WS-TRANID (line 38): PIC X(04) VALUE 'CR00'.
     */
    private static final String TRANSACTION_ID = "CR00";

    /**
     * Spring Batch JobLauncher for asynchronous job submission.
     * Replaces COBOL EXEC CICS WRITEQ TD transient data queue writes.
     * Configured with async task executor to prevent blocking on job completion.
     */
    private final JobLauncher jobLauncher;

    /**
     * TransactionAggregationJob bean for monthly, yearly, and custom transaction reports.
     * Injected via @Qualifier to disambiguate from other Job beans in context.
     * Corresponds to COBOL batch program CBTRN03C.cbl execution via JCL.
     */
    private final Job transactionAggregationJob;

    /**
     * StatementGenerationJob bean for monthly account statement reports.
     * Injected via @Qualifier to disambiguate from other Job beans in context.
     * Corresponds to COBOL batch program CBSTM03A.cbl execution via JCL.
     */
    private final Job statementGenerationJob;

    /**
     * Constructor with dependency injection for all required beans.
     * 
     * @param jobLauncher Spring Batch job launcher for asynchronous job submission
     * @param transactionAggregationJob Transaction aggregation batch job bean
     * @param statementGenerationJob Statement generation batch job bean
     */
    @Autowired
    public ReportMenuService(
            JobLauncher jobLauncher,
            @Qualifier("transactionAggregationJob") Job transactionAggregationJob,
            @Qualifier("statementGenerationJob") Job statementGenerationJob) {
        this.jobLauncher = jobLauncher;
        this.transactionAggregationJob = transactionAggregationJob;
        this.statementGenerationJob = statementGenerationJob;
        
        logger.info("ReportMenuService initialized with JobLauncher and batch job configurations");
    }

    /**
     * Retrieves available report types and current menu state for display.
     * 
     * <p>Constructs ReportMenuResponse DTO with all available report options and
     * current system time/date for screen display. This method corresponds to the
     * COBOL SEND-TRNRPT-SCREEN paragraph (lines 556-578) which prepares screen
     * data for terminal display.</p>
     * 
     * <p><strong>COBOL Equivalent:</strong></p>
     * <pre>
     * SEND-TRNRPT-SCREEN.
     *     PERFORM POPULATE-HEADER-INFO
     *     MOVE WS-MESSAGE TO ERRMSGO OF CORPT0AO
     *     EXEC CICS SEND
     *         MAP('CORPT0A')
     *         MAPSET('CORPT00')
     *         FROM(CORPT0AO)
     *         ERASE
     *     END-EXEC
     * </pre>
     * 
     * <p><strong>Report Types Returned:</strong></p>
     * <ul>
     *   <li>Monthly - Current month transaction aggregation</li>
     *   <li>Yearly - Current year transaction aggregation</li>
     *   <li>Custom - User-specified date range aggregation</li>
     * </ul>
     * 
     * <p><strong>Security:</strong> ROLE_ADMIN authorization required</p>
     * 
     * @return ReportMenuResponse containing available report types and screen metadata
     */
    @PreAuthorize("hasRole('ADMIN')")
    public ReportMenuResponse getAvailableReportTypes() {
        logger.debug("Retrieving available report types for report menu display");
        
        ReportMenuResponse response = new ReportMenuResponse();
        
        // Populate header information (COBOL POPULATE-HEADER-INFO lines 609-628)
        response.setTransactionName(TRANSACTION_ID);
        response.setProgramName(PROGRAM_NAME);
        response.setTitle01("CardDemo - Report Generation Menu");
        response.setTitle02("Select Report Type and Date Range");
        response.setCurrentDate(LocalDate.now());
        response.setCurrentTime(LocalTime.now());
        
        // Initialize report selection flags to empty (no selection)
        response.setMonthlyReportFlag("");
        response.setYearlyReportFlag("");
        response.setCustomReportFlag("");
        response.setConfirmationFlag("");
        
        // Clear any previous error messages
        response.setErrorMessage("");
        
        logger.debug("Report menu response constructed with transaction: {}, program: {}", 
                TRANSACTION_ID, PROGRAM_NAME);
        
        return response;
    }

    /**
     * Submits monthly transaction aggregation report for current calendar month.
     * 
     * <p>Calculates current month date range (first day to last day) and submits
     * TransactionAggregationJob for execution. This method transforms COBOL logic
     * from lines 213-238 which computed monthly date range and submitted JCL job.</p>
     * 
     * <p><strong>COBOL Date Calculation Logic (lines 215-236):</strong></p>
     * <pre>
     * MOVE FUNCTION CURRENT-DATE TO WS-CURDATE-DATA
     * MOVE WS-CURDATE-YEAR TO WS-START-DATE-YYYY
     * MOVE WS-CURDATE-MONTH TO WS-START-DATE-MM
     * MOVE '01' TO WS-START-DATE-DD
     * 
     * MOVE 1 TO WS-CURDATE-DAY
     * ADD 1 TO WS-CURDATE-MONTH
     * IF WS-CURDATE-MONTH > 12
     *     ADD 1 TO WS-CURDATE-YEAR
     *     MOVE 1 TO WS-CURDATE-MONTH
     * END-IF
     * COMPUTE WS-CURDATE-N = FUNCTION DATE-OF-INTEGER(
     *         FUNCTION INTEGER-OF-DATE(WS-CURDATE-N) - 1)
     * </pre>
     * 
     * <p><strong>Java Simplification:</strong></p>
     * <p>Java's YearMonth class eliminates complex COBOL date arithmetic by providing
     * atDay(1) for month start and atEndOfMonth() for month end, automatically handling
     * variable month lengths (28-31 days) without explicit calculation.</p>
     * 
     * <p><strong>Job Parameters:</strong></p>
     * <ul>
     *   <li>start.date: First day of current month (yyyy-MM-dd)</li>
     *   <li>end.date: Last day of current month (yyyy-MM-dd)</li>
     *   <li>run.id: Unique timestamp for job instance</li>
     *   <li>report.type: "MONTHLY" for job identification</li>
     * </ul>
     * 
     * <p><strong>Security:</strong> ROLE_ADMIN authorization required</p>
     * 
     * <p><strong>Transaction Boundary:</strong> @Transactional ensures atomic job submission</p>
     * 
     * @param confirmed Confirmation flag ('Y'/'y' = confirmed, other = not confirmed)
     * @return JobExecution containing job instance ID, status, and execution details
     * @throws IllegalArgumentException if confirmation not provided or invalid
     * @throws org.springframework.batch.core.repository.JobExecutionAlreadyRunningException if job already running
     * @throws org.springframework.batch.core.repository.JobRestartException if restart not allowed
     * @throws org.springframework.batch.core.repository.JobInstanceAlreadyCompleteException if already completed
     */
    @PreAuthorize("hasRole('ADMIN')")
    @Transactional(isolation = Isolation.READ_COMMITTED, rollbackFor = Exception.class)
    public JobExecution submitMonthlyReport(String confirmed) {
        logger.info("Submitting monthly transaction aggregation report with confirmation: {}", confirmed);
        
        // Validate confirmation (COBOL lines 464-474, 476-494)
        validateConfirmation(confirmed, "Monthly");
        
        // Calculate current month date range (COBOL lines 215-236)
        YearMonth currentMonth = YearMonth.now();
        LocalDate startDate = currentMonth.atDay(1);
        LocalDate endDate = currentMonth.atEndOfMonth();
        
        logger.debug("Monthly report date range calculated: {} to {}", startDate, endDate);
        
        // Build job parameters (replaces COBOL JOB-DATA structure lines 81-127)
        JobParameters jobParameters = new JobParametersBuilder()
                .addString("start.date", startDate.format(DATE_FORMATTER))
                .addString("end.date", endDate.format(DATE_FORMATTER))
                .addString("report.type", "MONTHLY")
                .addLong("run.id", System.currentTimeMillis())
                .toJobParameters();
        
        // Submit job (replaces COBOL EXEC CICS WRITEQ TD lines 517-523)
        try {
            JobExecution execution = jobLauncher.run(transactionAggregationJob, jobParameters);
            logger.info("Monthly report job submitted successfully. Job ID: {}, Status: {}", 
                    execution.getJobId(), execution.getStatus());
            return execution;
        } catch (Exception e) {
            logger.error("Failed to submit monthly report job", e);
            throw new IllegalStateException("Unable to submit monthly report job: " + e.getMessage(), e);
        }
    }

    /**
     * Submits yearly transaction aggregation report for current calendar year.
     * 
     * <p>Calculates current year date range (January 1 to December 31) and submits
     * TransactionAggregationJob for execution. This method transforms COBOL logic
     * from lines 239-255 which computed yearly date range and submitted JCL job.</p>
     * 
     * <p><strong>COBOL Date Calculation Logic (lines 239-254):</strong></p>
     * <pre>
     * MOVE FUNCTION CURRENT-DATE TO WS-CURDATE-DATA
     * MOVE WS-CURDATE-YEAR TO WS-START-DATE-YYYY
     *                         WS-END-DATE-YYYY
     * MOVE '01' TO WS-START-DATE-MM
     *              WS-START-DATE-DD
     * MOVE WS-START-DATE TO PARM-START-DATE-1
     *                       PARM-START-DATE-2
     * 
     * MOVE '12' TO WS-END-DATE-MM
     * MOVE '31' TO WS-END-DATE-DD
     * MOVE WS-END-DATE TO PARM-END-DATE-1
     *                     PARM-END-DATE-2
     * </pre>
     * 
     * <p><strong>Job Parameters:</strong></p>
     * <ul>
     *   <li>start.date: January 1 of current year (yyyy-01-01)</li>
     *   <li>end.date: December 31 of current year (yyyy-12-31)</li>
     *   <li>run.id: Unique timestamp for job instance</li>
     *   <li>report.type: "YEARLY" for job identification</li>
     * </ul>
     * 
     * <p><strong>Security:</strong> ROLE_ADMIN authorization required</p>
     * 
     * <p><strong>Transaction Boundary:</strong> @Transactional ensures atomic job submission</p>
     * 
     * @param confirmed Confirmation flag ('Y'/'y' = confirmed, other = not confirmed)
     * @return JobExecution containing job instance ID, status, and execution details
     * @throws IllegalArgumentException if confirmation not provided or invalid
     * @throws org.springframework.batch.core.repository.JobExecutionAlreadyRunningException if job already running
     */
    @PreAuthorize("hasRole('ADMIN')")
    @Transactional(isolation = Isolation.READ_COMMITTED, rollbackFor = Exception.class)
    public JobExecution submitYearlyReport(String confirmed) {
        logger.info("Submitting yearly transaction aggregation report with confirmation: {}", confirmed);
        
        // Validate confirmation (COBOL lines 464-474, 476-494)
        validateConfirmation(confirmed, "Yearly");
        
        // Calculate current year date range (COBOL lines 239-254)
        int currentYear = LocalDate.now().getYear();
        LocalDate startDate = LocalDate.of(currentYear, 1, 1);
        LocalDate endDate = LocalDate.of(currentYear, 12, 31);
        
        logger.debug("Yearly report date range calculated: {} to {}", startDate, endDate);
        
        // Build job parameters
        JobParameters jobParameters = new JobParametersBuilder()
                .addString("start.date", startDate.format(DATE_FORMATTER))
                .addString("end.date", endDate.format(DATE_FORMATTER))
                .addString("report.type", "YEARLY")
                .addLong("run.id", System.currentTimeMillis())
                .toJobParameters();
        
        // Submit job (replaces COBOL EXEC CICS WRITEQ TD)
        try {
            JobExecution execution = jobLauncher.run(transactionAggregationJob, jobParameters);
            logger.info("Yearly report job submitted successfully. Job ID: {}, Status: {}", 
                    execution.getJobId(), execution.getStatus());
            return execution;
        } catch (Exception e) {
            logger.error("Failed to submit yearly report job", e);
            throw new IllegalStateException("Unable to submit yearly report job: " + e.getMessage(), e);
        }
    }

    /**
     * Submits custom date range transaction aggregation report.
     * 
     * <p>Validates user-provided start and end dates, then submits TransactionAggregationJob
     * for the specified date range. This method transforms complex COBOL validation logic
     * from lines 256-436 including field presence checks, numeric validation, and date
     * validity verification via CSUTLDTC utility program call.</p>
     * 
     * <p><strong>COBOL Validation Logic (lines 258-426):</strong></p>
     * <p>Original COBOL performed extensive field-by-field validation:</p>
     * <ol>
     *   <li>Check each date component (month, day, year) for presence (lines 259-300)</li>
     *   <li>Convert input fields to numeric using FUNCTION NUMVAL-C (lines 305-327)</li>
     *   <li>Validate month range 1-12 (lines 329-336)</li>
     *   <li>Validate day range 1-31 (lines 338-345)</li>
     *   <li>Validate year is numeric (lines 347-353)</li>
     *   <li>Validate complete date via CALL 'CSUTLDTC' (lines 392-426)</li>
     * </ol>
     * 
     * <p><strong>Java Validation Approach:</strong></p>
     * <p>Java's LocalDate class provides built-in validation, eliminating need for
     * external utility calls. Invalid dates throw DateTimeException automatically.</p>
     * 
     * <p><strong>Validation Rules:</strong></p>
     * <ul>
     *   <li>Start date and end date must be provided</li>
     *   <li>Month must be 1-12</li>
     *   <li>Day must be 1-31 (validated against month)</li>
     *   <li>Year must be valid integer</li>
     *   <li>Complete date must be valid per calendar (February 29, etc.)</li>
     *   <li>Start date must be less than or equal to end date</li>
     * </ul>
     * 
     * <p><strong>Job Parameters:</strong></p>
     * <ul>
     *   <li>start.date: User-specified start date (yyyy-MM-dd)</li>
     *   <li>end.date: User-specified end date (yyyy-MM-dd)</li>
     *   <li>run.id: Unique timestamp for job instance</li>
     *   <li>report.type: "CUSTOM" for job identification</li>
     * </ul>
     * 
     * <p><strong>Security:</strong> ROLE_ADMIN authorization required</p>
     * 
     * <p><strong>Transaction Boundary:</strong> @Transactional ensures atomic job submission</p>
     * 
     * @param startYear Start date year component (YYYY format, e.g., 2024)
     * @param startMonth Start date month component (1-12)
     * @param startDay Start date day component (1-31)
     * @param endYear End date year component (YYYY format)
     * @param endMonth End date month component (1-12)
     * @param endDay End date day component (1-31)
     * @param confirmed Confirmation flag ('Y'/'y' = confirmed, other = not confirmed)
     * @return JobExecution containing job instance ID, status, and execution details
     * @throws IllegalArgumentException if date components invalid or date range invalid
     * @throws DateTimeParseException if date construction fails
     */
    @PreAuthorize("hasRole('ADMIN')")
    @Transactional(isolation = Isolation.READ_COMMITTED, rollbackFor = Exception.class)
    public JobExecution submitCustomReport(
            Integer startYear, Integer startMonth, Integer startDay,
            Integer endYear, Integer endMonth, Integer endDay,
            String confirmed) {
        
        logger.info("Submitting custom date range report: {}/{}/{} to {}/{}/{} with confirmation: {}",
                startMonth, startDay, startYear, endMonth, endDay, endYear, confirmed);
        
        // Validate confirmation (COBOL lines 464-474)
        validateConfirmation(confirmed, "Custom");
        
        // Validate date components (COBOL lines 258-380)
        validateDateComponents(startYear, startMonth, startDay, endYear, endMonth, endDay);
        
        // Construct LocalDate objects (COBOL lines 381-386)
        LocalDate startDate;
        LocalDate endDate;
        
        try {
            startDate = LocalDate.of(startYear, startMonth, startDay);
            endDate = LocalDate.of(endYear, endMonth, endDay);
        } catch (DateTimeException e) {
            logger.error("Invalid date construction: start={}/{}/{}, end={}/{}/{}", 
                    startMonth, startDay, startYear, endMonth, endDay, endYear, e);
            throw new IllegalArgumentException("Invalid date: " + e.getMessage(), e);
        }
        
        // Validate date range (COBOL implicit validation)
        if (startDate.isAfter(endDate)) {
            logger.error("Start date {} is after end date {}", startDate, endDate);
            throw new IllegalArgumentException("Start date must be less than or equal to end date");
        }
        
        logger.debug("Custom report date range validated: {} to {}", startDate, endDate);
        
        // Build job parameters (COBOL lines 429-432)
        JobParameters jobParameters = new JobParametersBuilder()
                .addString("start.date", startDate.format(DATE_FORMATTER))
                .addString("end.date", endDate.format(DATE_FORMATTER))
                .addString("report.type", "CUSTOM")
                .addLong("run.id", System.currentTimeMillis())
                .toJobParameters();
        
        // Submit job (replaces COBOL PERFORM SUBMIT-JOB-TO-INTRDR line 435)
        try {
            JobExecution execution = jobLauncher.run(transactionAggregationJob, jobParameters);
            logger.info("Custom report job submitted successfully. Job ID: {}, Status: {}", 
                    execution.getJobId(), execution.getStatus());
            return execution;
        } catch (Exception e) {
            logger.error("Failed to submit custom report job", e);
            throw new IllegalStateException("Unable to submit custom report job: " + e.getMessage(), e);
        }
    }

    /**
     * Validates report request parameters before job submission.
     * 
     * <p>This method provides comprehensive validation of report request parameters,
     * ensuring all required fields are present and valid before batch job submission.
     * Corresponds to COBOL validation logic distributed across PROCESS-ENTER-KEY
     * paragraph (lines 208-456).</p>
     * 
     * <p><strong>Validation Performed:</strong></p>
     * <ul>
     *   <li>Report type selection (at least one must be chosen)</li>
     *   <li>Date component validation for custom reports</li>
     *   <li>Confirmation flag validation</li>
     *   <li>Date range logical validation (start <= end)</li>
     * </ul>
     * 
     * <p><strong>COBOL Equivalent Logic:</strong></p>
     * <p>Consolidates multiple COBOL validation paragraphs:</p>
     * <ul>
     *   <li>Lines 212-443: EVALUATE TRUE report type selection</li>
     *   <li>Lines 258-300: Field presence validation for custom dates</li>
     *   <li>Lines 329-380: Numeric and range validation</li>
     *   <li>Lines 392-426: Date validity via CSUTLDTC call</li>
     *   <li>Lines 464-474: Confirmation validation</li>
     * </ul>
     * 
     * <p><strong>Error Messages:</strong></p>
     * <p>Returns descriptive error messages matching COBOL WS-MESSAGE field content
     * (PIC X(80)) for user-friendly error display in UI.</p>
     * 
     * @param response ReportMenuResponse containing user input for validation
     * @return List of validation error messages (empty if validation passes)
     */
    public List<String> validateReportRequest(@Valid ReportMenuResponse response) {
        logger.debug("Validating report request: monthly={}, yearly={}, custom={}", 
                response.isMonthlyReport(), response.isYearlyReport(), response.isCustomReport());
        
        List<String> errors = new ArrayList<>();
        
        // Validate at least one report type selected (COBOL lines 437-442)
        if (!response.isMonthlyReport() && !response.isYearlyReport() && !response.isCustomReport()) {
            errors.add("Select a report type to print report...");
            logger.warn("No report type selected");
        }
        
        // Validate custom report date components if custom report selected (COBOL lines 258-380)
        if (response.isCustomReport()) {
            if (response.getStartDateMonth() == null) {
                errors.add("Start Date - Month can NOT be empty...");
            }
            if (response.getStartDateDay() == null) {
                errors.add("Start Date - Day can NOT be empty...");
            }
            if (response.getStartDateYear() == null) {
                errors.add("Start Date - Year can NOT be empty...");
            }
            if (response.getEndDateMonth() == null) {
                errors.add("End Date - Month can NOT be empty...");
            }
            if (response.getEndDateDay() == null) {
                errors.add("End Date - Day can NOT be empty...");
            }
            if (response.getEndDateYear() == null) {
                errors.add("End Date - Year can NOT be empty...");
            }
            
            // Validate date component ranges (COBOL lines 329-380)
            if (response.getStartDateMonth() != null && 
                    (response.getStartDateMonth() < 1 || response.getStartDateMonth() > 12)) {
                errors.add("Start Date - Not a valid Month...");
            }
            if (response.getStartDateDay() != null && 
                    (response.getStartDateDay() < 1 || response.getStartDateDay() > 31)) {
                errors.add("Start Date - Not a valid Day...");
            }
            if (response.getEndDateMonth() != null && 
                    (response.getEndDateMonth() < 1 || response.getEndDateMonth() > 12)) {
                errors.add("End Date - Not a valid Month...");
            }
            if (response.getEndDateDay() != null && 
                    (response.getEndDateDay() < 1 || response.getEndDateDay() > 31)) {
                errors.add("End Date - Not a valid Day...");
            }
            
            // Validate complete date validity (COBOL lines 388-426 CSUTLDTC calls)
            try {
                LocalDate startDate = response.getStartDate();
                if (startDate == null && response.getStartDateYear() != null && 
                        response.getStartDateMonth() != null && response.getStartDateDay() != null) {
                    errors.add("Start Date - Not a valid date...");
                }
            } catch (DateTimeException e) {
                errors.add("Start Date - Not a valid date...");
            }
            
            try {
                LocalDate endDate = response.getEndDate();
                if (endDate == null && response.getEndDateYear() != null && 
                        response.getEndDateMonth() != null && response.getEndDateDay() != null) {
                    errors.add("End Date - Not a valid date...");
                }
            } catch (DateTimeException e) {
                errors.add("End Date - Not a valid date...");
            }
            
            // Validate date range (implicit in COBOL logic)
            if (!response.isValidDateRange()) {
                errors.add("Start date must be less than or equal to end date...");
            }
        }
        
        // Validate confirmation if report type selected (COBOL lines 464-474)
        if ((response.isMonthlyReport() || response.isYearlyReport() || response.isCustomReport()) &&
                !response.isConfirmed()) {
            String reportType = response.isMonthlyReport() ? "Monthly" : 
                               response.isYearlyReport() ? "Yearly" : "Custom";
            errors.add("Please confirm to print the " + reportType + " report...");
        }
        
        // Validate confirmation flag value (COBOL lines 478-493)
        if (response.getConfirmationFlag() != null && !response.getConfirmationFlag().isEmpty()) {
            String confirm = response.getConfirmationFlag();
            if (!confirm.equalsIgnoreCase("Y") && !confirm.equalsIgnoreCase("N")) {
                errors.add("\"" + confirm + "\" is not a valid value to confirm...");
            }
        }
        
        logger.debug("Validation completed with {} errors", errors.size());
        return errors;
    }

    // ===============================================================================
    // PRIVATE HELPER METHODS
    // ===============================================================================

    /**
     * Validates confirmation flag for report submission.
     * 
     * <p>Corresponds to COBOL confirmation validation logic (lines 464-494).
     * Ensures user has explicitly confirmed report generation before expensive
     * batch job submission.</p>
     * 
     * <p><strong>COBOL Logic (lines 464-474, 476-494):</strong></p>
     * <pre>
     * IF CONFIRMI OF CORPT0AI = SPACES OR LOW-VALUES
     *     STRING 'Please confirm to print the ' DELIMITED BY SIZE
     *         WS-REPORT-NAME DELIMITED BY SPACE
     *         ' report...' DELIMITED BY SIZE
     *         INTO WS-MESSAGE
     *     MOVE 'Y' TO WS-ERR-FLG
     * END-IF
     * 
     * EVALUATE TRUE
     *     WHEN CONFIRMI OF CORPT0AI = 'Y' OR 'y'
     *         CONTINUE
     *     WHEN CONFIRMI OF CORPT0AI = 'N' OR 'n'
     *         PERFORM INITIALIZE-ALL-FIELDS
     *         MOVE 'Y' TO WS-ERR-FLG
     *     WHEN OTHER
     *         STRING '"' DELIMITED BY SIZE
     *             CONFIRMI OF CORPT0AI DELIMITED BY SPACE
     *             '" is not a valid value to confirm...' DELIMITED BY SIZE
     *             INTO WS-MESSAGE
     *         MOVE 'Y' TO WS-ERR-FLG
     * END-EVALUATE
     * </pre>
     * 
     * @param confirmed Confirmation flag value ('Y', 'y', 'N', 'n', or empty)
     * @param reportType Report type name for error message (Monthly, Yearly, Custom)
     * @throws IllegalArgumentException if confirmation invalid or not provided
     */
    private void validateConfirmation(String confirmed, String reportType) {
        // Check for empty or null confirmation (COBOL lines 464-474)
        if (confirmed == null || confirmed.trim().isEmpty()) {
            String message = "Please confirm to print the " + reportType + " report...";
            logger.warn("Confirmation not provided for {} report", reportType);
            throw new IllegalArgumentException(message);
        }
        
        // Check for 'N' or 'n' rejection (COBOL lines 480-483)
        if (confirmed.equalsIgnoreCase("N")) {
            String message = reportType + " report generation cancelled by user";
            logger.info(message);
            throw new IllegalArgumentException(message);
        }
        
        // Check for valid 'Y' or 'y' confirmation (COBOL lines 478-479)
        if (!confirmed.equalsIgnoreCase("Y")) {
            String message = "\"" + confirmed + "\" is not a valid value to confirm...";
            logger.warn("Invalid confirmation value: {}", confirmed);
            throw new IllegalArgumentException(message);
        }
        
        logger.debug("{} report confirmed by user", reportType);
    }

    /**
     * Validates individual date components for custom report date range.
     * 
     * <p>Performs field-level validation matching COBOL logic from lines 258-380.
     * Original COBOL validated each date component separately with detailed error
     * messages for each field.</p>
     * 
     * <p><strong>Validation Rules (from COBOL):</strong></p>
     * <ul>
     *   <li>Lines 259-278: Start month, day, year presence check</li>
     *   <li>Lines 280-300: End month, day, year presence check</li>
     *   <li>Lines 329-336: Start month numeric 1-12 validation</li>
     *   <li>Lines 338-345: Start day numeric 1-31 validation</li>
     *   <li>Lines 347-353: Start year numeric validation</li>
     *   <li>Lines 355-362: End month numeric 1-12 validation</li>
     *   <li>Lines 364-371: End day numeric 1-31 validation</li>
     *   <li>Lines 373-379: End year numeric validation</li>
     * </ul>
     * 
     * @param startYear Start date year component
     * @param startMonth Start date month component
     * @param startDay Start date day component
     * @param endYear End date year component
     * @param endMonth End date month component
     * @param endDay End date day component
     * @throws IllegalArgumentException if any date component is null or out of valid range
     */
    private void validateDateComponents(
            Integer startYear, Integer startMonth, Integer startDay,
            Integer endYear, Integer endMonth, Integer endDay) {
        
        // Validate start date components presence (COBOL lines 259-278)
        if (startMonth == null) {
            throw new IllegalArgumentException("Start Date - Month can NOT be empty...");
        }
        if (startDay == null) {
            throw new IllegalArgumentException("Start Date - Day can NOT be empty...");
        }
        if (startYear == null) {
            throw new IllegalArgumentException("Start Date - Year can NOT be empty...");
        }
        
        // Validate end date components presence (COBOL lines 280-300)
        if (endMonth == null) {
            throw new IllegalArgumentException("End Date - Month can NOT be empty...");
        }
        if (endDay == null) {
            throw new IllegalArgumentException("End Date - Day can NOT be empty...");
        }
        if (endYear == null) {
            throw new IllegalArgumentException("End Date - Year can NOT be empty...");
        }
        
        // Validate start date component ranges (COBOL lines 329-353)
        if (startMonth < 1 || startMonth > 12) {
            throw new IllegalArgumentException("Start Date - Not a valid Month...");
        }
        if (startDay < 1 || startDay > 31) {
            throw new IllegalArgumentException("Start Date - Not a valid Day...");
        }
        if (startYear < 1900 || startYear > 2100) {
            throw new IllegalArgumentException("Start Date - Not a valid Year...");
        }
        
        // Validate end date component ranges (COBOL lines 355-379)
        if (endMonth < 1 || endMonth > 12) {
            throw new IllegalArgumentException("End Date - Not a valid Month...");
        }
        if (endDay < 1 || endDay > 31) {
            throw new IllegalArgumentException("End Date - Not a valid Day...");
        }
        if (endYear < 1900 || endYear > 2100) {
            throw new IllegalArgumentException("End Date - Not a valid Year...");
        }
        
        logger.debug("Date components validated successfully");
    }
}
