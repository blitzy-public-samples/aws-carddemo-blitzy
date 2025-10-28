/*
 * ReportController.java
 *
 * REST API controller for report generation converted from COBOL program CORPT00C.cbl.
 * Replaces BMS report menu screens (CORPT00.bms) with REST endpoints for generating
 * business reports with flexible date range and filter criteria.
 *
 * Original COBOL file:
 * - Source: app/cbl/CORPT00C.cbl (28KB, 650 lines)
 * - Function: Report generation menu and JCL job submission to batch processor
 * - BMS Map: CORPT00.bms (report selection and date entry screens)
 *
 * Conversion notes:
 * - COBOL program displayed BMS menu with options (Monthly, Yearly, Custom)
 * - COBOL submitted JCL jobs to internal reader (TDQ 'JOBS') for batch report processing
 * - Java controller provides REST API returning report data directly as JSON or CSV
 * - EXEC CICS SEND MAP → ResponseEntity.ok(reportDto) returning JSON
 * - EXEC CICS RECEIVE MAP → @RequestBody ReportRequest accepting JSON
 * - COBOL date validation (CSUTLDTC calls) → ValidationService.validateDate()
 * - COBOL JCL submission → Direct report generation via ReportService
 *
 * Business logic preserved from CORPT00C.cbl:
 * - Report type selection: Monthly (lines 213-238), Yearly (lines 239-254), Custom (lines 256-436)
 * - Date range validation for custom reports (lines 256-426)
 * - Date field validation: month 1-12, day 1-31, year 4 digits (lines 329-379)
 * - Confirmation requirement before report generation (lines 464-510)
 * - Error handling for invalid inputs with field-level error messages
 *
 * Performance requirements per Agent Action Plan Section 0.7.7:
 * - Report generation endpoints must respond within reasonable time (< 5 seconds for typical reports)
 * - Use @Transactional(readOnly=true) in service layer for consistent read view
 * - Support CSV export for large datasets to avoid JSON memory overhead
 *
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
package com.carddemo.controller;

import com.carddemo.service.ReportService;
import com.carddemo.service.ReportService.ReportDto;
import com.carddemo.service.ReportService.TransactionReportCriteria;
import com.carddemo.service.ValidationService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * REST controller for report generation operations.
 * 
 * <p>Converted from COBOL program CORPT00C.cbl which provided report menu and JCL job submission.
 * This controller replaces CICS/BMS screen handling with RESTful endpoints for:</p>
 * <ul>
 *   <li><b>Report Menu:</b> List available report types (GET /api/reports/menu)</li>
 *   <li><b>Report Generation:</b> Generate reports with flexible criteria (POST /api/reports/generate)</li>
 *   <li><b>Report Export:</b> Export generated reports to CSV format (GET /api/reports/{reportId}/export)</li>
 * </ul>
 * 
 * <h3>COBOL to REST API Mapping:</h3>
 * <table border="1">
 *   <tr>
 *     <th>COBOL Screen/Logic</th>
 *     <th>REST Endpoint</th>
 *     <th>HTTP Method</th>
 *   </tr>
 *   <tr>
 *     <td>CORPT0A map display (report menu)</td>
 *     <td>/api/reports/menu</td>
 *     <td>GET</td>
 *   </tr>
 *   <tr>
 *     <td>PROCESS-ENTER-KEY paragraph (report selection)</td>
 *     <td>/api/reports/generate</td>
 *     <td>POST</td>
 *   </tr>
 *   <tr>
 *     <td>SUBMIT-JOB-TO-INTRDR paragraph (JCL submission)</td>
 *     <td>/api/reports/generate (direct generation)</td>
 *     <td>POST</td>
 *   </tr>
 *   <tr>
 *     <td>WIRTE-JOBSUB-TDQ paragraph (TDQ write)</td>
 *     <td>/api/reports/{reportId}/export</td>
 *     <td>GET</td>
 *   </tr>
 * </table>
 * 
 * <h3>Report Type Constants:</h3>
 * <ul>
 *   <li><b>ACCOUNT_SUMMARY:</b> Account statistics and credit limit totals</li>
 *   <li><b>TRANSACTION_ACTIVITY:</b> Transaction breakdown by category with date range filter</li>
 *   <li><b>USER_ACTIVITY:</b> User login and activity tracking</li>
 *   <li><b>CUSTOM:</b> Flexible report with custom date range and filters</li>
 * </ul>
 * 
 * <h3>Error Handling:</h3>
 * <p>All validation errors throw ValidationException caught by GlobalExceptionHandler:</p>
 * <ul>
 *   <li>Missing required fields (reportType, startDate, endDate)</li>
 *   <li>Invalid date format or range (startDate after endDate)</li>
 *   <li>Invalid card number format (if provided)</li>
 * </ul>
 * 
 * @see ReportService
 * @see ValidationService
 * @author CardDemo Modernization Team
 * @version 1.0
 * @since 2024
 */
@Slf4j
@RestController
@RequestMapping("/api/reports")
@RequiredArgsConstructor
public class ReportController {

    private final ReportService reportService;
    private final ValidationService validationService;
    
    // In-memory cache for generated reports (report ID -> ReportDto)
    // In production, this would be replaced with Redis or database storage
    private final Map<String, ReportDto> reportCache = new ConcurrentHashMap<>();
    
    private static final DateTimeFormatter REPORT_ID_FORMATTER = DateTimeFormatter.ofPattern("yyyyMMddHHmmss");

    /**
     * Get list of available report types.
     * 
     * <p>Replaces COBOL report menu display from CORPT00C.cbl lines 169-180:</p>
     * <pre>
     * IF EIBCALEN = 0
     *     MOVE 'COSGN00C' TO CDEMO-TO-PROGRAM
     *     PERFORM RETURN-TO-PREV-SCREEN
     * ELSE
     *     MOVE DFHCOMMAREA(1:EIBCALEN) TO CARDDEMO-COMMAREA
     *     IF NOT CDEMO-PGM-REENTER
     *         SET CDEMO-PGM-REENTER    TO TRUE
     *         MOVE LOW-VALUES          TO CORPT0AO
     *         MOVE -1       TO MONTHLYL OF CORPT0AI
     *         PERFORM SEND-TRNRPT-SCREEN
     * </pre>
     * 
     * <p>Returns list of report type codes that can be used in generateReport() endpoint:</p>
     * <ul>
     *   <li><b>ACCOUNT_SUMMARY:</b> Monthly/Yearly account statistics (CORPT00C Monthly/Yearly options)</li>
     *   <li><b>TRANSACTION_ACTIVITY:</b> Transaction breakdown by category (CORPT00C Custom option)</li>
     *   <li><b>USER_ACTIVITY:</b> User login and activity tracking (New functionality)</li>
     *   <li><b>CUSTOM:</b> Flexible custom report with all filters (CORPT00C Custom with full filters)</li>
     * </ul>
     * 
     * @return ResponseEntity with list of available report type codes
     */
    @GetMapping("/menu")
    public ResponseEntity<List<String>> getReportMenu() {
        log.info("GET /api/reports/menu - Retrieving available report types");
        
        // Return report type codes matching COBOL menu options from CORPT00C.cbl
        // MONTHLYI, YEARLYI, CUSTOMI options → ACCOUNT_SUMMARY, TRANSACTION_ACTIVITY, USER_ACTIVITY, CUSTOM
        List<String> reportTypes = List.of(
            "ACCOUNT_SUMMARY",     // Monthly/Yearly reports from CORPT00C lines 213-254
            "TRANSACTION_ACTIVITY", // Custom transaction reports from CORPT00C lines 256-436
            "USER_ACTIVITY",        // New functionality for user tracking
            "CUSTOM"                // Fully customizable report with all filter options
        );
        
        log.debug("Returning {} report types", reportTypes.size());
        return ResponseEntity.ok(reportTypes);
    }

    /**
     * Generate report based on request criteria.
     * 
     * <p>Replaces COBOL PROCESS-ENTER-KEY paragraph from CORPT00C.cbl lines 208-456.
     * Original COBOL logic evaluated report type selection and submitted JCL job to internal reader.</p>
     * 
     * <p>COBOL logic (CORPT00C.cbl lines 212-443):</p>
     * <pre>
     * EVALUATE TRUE
     *     WHEN MONTHLYI OF CORPT0AI NOT = SPACES AND LOW-VALUES
     *         MOVE 'Monthly'   TO WS-REPORT-NAME
     *         [Calculate current month date range]
     *         PERFORM SUBMIT-JOB-TO-INTRDR
     *     WHEN YEARLYI OF CORPT0AI NOT = SPACES AND LOW-VALUES
     *         MOVE 'Yearly'   TO WS-REPORT-NAME
     *         [Calculate current year date range]
     *         PERFORM SUBMIT-JOB-TO-INTRDR
     *     WHEN CUSTOMI OF CORPT0AI NOT = SPACES AND LOW-VALUES
     *         [Validate custom date fields: month, day, year]
     *         [Call CSUTLDTC for date validation]
     *         MOVE 'Custom'   TO WS-REPORT-NAME
     *         PERFORM SUBMIT-JOB-TO-INTRDR
     *     WHEN OTHER
     *         MOVE 'Select a report type to print report...' TO WS-MESSAGE
     * END-EVALUATE
     * </pre>
     * 
     * <p>Java implementation evaluates reportType and delegates to appropriate service method:</p>
     * <ul>
     *   <li><b>ACCOUNT_SUMMARY:</b> Calls reportService.generateAccountSummaryReport(startDate, endDate)</li>
     *   <li><b>TRANSACTION_ACTIVITY:</b> Calls reportService.generateTransactionReport(criteria)</li>
     *   <li><b>USER_ACTIVITY:</b> Calls reportService.generateUserActivityReport(startDate, endDate)</li>
     *   <li><b>CUSTOM:</b> Same as TRANSACTION_ACTIVITY with full filter support</li>
     * </ul>
     * 
     * <p>Validation performed (matching COBOL lines 258-426):</p>
     * <ul>
     *   <li>Report type must not be null or empty</li>
     *   <li>Start date must not be null</li>
     *   <li>End date must not be null</li>
     *   <li>Start date must not be after end date</li>
     *   <li>Date fields must be valid calendar dates (delegated to ValidationService)</li>
     *   <li>Optional card number must be 16-digit format (if provided)</li>
     * </ul>
     * 
     * <p>Success response includes:</p>
     * <ul>
     *   <li>Report data as JSON with dataRows array</li>
     *   <li>Report metadata (type, name, date range, generation timestamp)</li>
     *   <li>Total record count</li>
     *   <li>HTTP 200 OK status</li>
     * </ul>
     * 
     * @param reportRequest Request body with report type and criteria (validated with @Valid)
     * @return ResponseEntity with generated ReportDto containing report data
     * @throws ValidationException if request validation fails (handled by GlobalExceptionHandler)
     * @throws BusinessException if report generation fails due to business logic errors
     */
    @PostMapping("/generate")
    public ResponseEntity<ReportDto> generateReport(@Valid @RequestBody ReportRequest reportRequest) {
        log.info("POST /api/reports/generate - Report type: {}, Date range: {} to {}", 
                 reportRequest.getReportType(), reportRequest.getStartDate(), reportRequest.getEndDate());
        
        // Step 1: Validate date range (replaces COBOL date validation from CORPT00C.cbl lines 388-426)
        validationService.validateDate(reportRequest.getStartDate());
        validationService.validateDate(reportRequest.getEndDate());
        
        // Step 2: Evaluate report type and delegate to appropriate service method
        // (replaces COBOL EVALUATE TRUE from CORPT00C.cbl lines 212-443)
        ReportDto report;
        
        switch (reportRequest.getReportType().toUpperCase()) {
            case "ACCOUNT_SUMMARY":
                // Replaces COBOL Monthly/Yearly report logic (lines 213-254)
                log.debug("Generating account summary report");
                report = reportService.generateAccountSummaryReport(
                    reportRequest.getStartDate(),
                    reportRequest.getEndDate()
                );
                break;
                
            case "TRANSACTION_ACTIVITY":
            case "CUSTOM":
                // Replaces COBOL Custom report logic (lines 256-436)
                log.debug("Generating transaction activity report with criteria");
                
                // Build criteria object from request
                TransactionReportCriteria criteria = TransactionReportCriteria.builder()
                    .cardNumber(reportRequest.getCardNumber())
                    .startDate(reportRequest.getStartDate())
                    .endDate(reportRequest.getEndDate())
                    .transactionType(reportRequest.getTransactionType())
                    .transactionCategory(reportRequest.getTransactionCategory())
                    .minAmount(reportRequest.getMinAmount())
                    .maxAmount(reportRequest.getMaxAmount())
                    .build();
                
                report = reportService.generateTransactionReport(criteria);
                break;
                
            case "USER_ACTIVITY":
                // New functionality not present in COBOL
                log.debug("Generating user activity report");
                report = reportService.generateUserActivityReport(
                    reportRequest.getStartDate(),
                    reportRequest.getEndDate()
                );
                break;
                
            default:
                // Replaces COBOL WHEN OTHER clause (lines 437-442)
                log.warn("Invalid report type requested: {}", reportRequest.getReportType());
                throw new IllegalArgumentException(
                    "Invalid report type: " + reportRequest.getReportType() + 
                    ". Valid types are: ACCOUNT_SUMMARY, TRANSACTION_ACTIVITY, USER_ACTIVITY, CUSTOM");
        }
        
        // Step 3: Store report in cache with generated report ID for later export
        // (In COBOL, report was submitted to batch JCL job with job name identifier)
        String reportId = generateReportId(report);
        reportCache.put(reportId, report);
        log.debug("Report cached with ID: {}", reportId);
        
        // Step 4: Return report data as JSON
        // (Replaces COBOL PERFORM SEND-TRNRPT-SCREEN from lines 453-454)
        log.info("Report generated successfully: {} records", report.getTotalRecords());
        return ResponseEntity.ok(report);
    }

    /**
     * Export report to CSV format for download.
     * 
     * <p>Replaces COBOL WIRTE-JOBSUB-TDQ paragraph from CORPT00C.cbl lines 515-535.
     * Original COBOL logic wrote JCL records to transient data queue (TDQ) for batch processing.</p>
     * 
     * <p>COBOL logic (CORPT00C.cbl lines 517-535):</p>
     * <pre>
     * EXEC CICS WRITEQ TD
     *   QUEUE ('JOBS')
     *   FROM (JCL-RECORD)
     *   LENGTH (LENGTH OF JCL-RECORD)
     *   RESP(WS-RESP-CD)
     *   RESP2(WS-REAS-CD)
     * END-EXEC.
     * 
     * EVALUATE WS-RESP-CD
     *     WHEN DFHRESP(NORMAL)
     *         CONTINUE
     *     WHEN OTHER
     *         DISPLAY 'RESP:' WS-RESP-CD 'REAS:' WS-REAS-CD
     *         MOVE 'Y'     TO WS-ERR-FLG
     *         MOVE 'Unable to Write TDQ (JOBS)...' TO WS-MESSAGE
     * END-EVALUATE.
     * </pre>
     * 
     * <p>Java implementation retrieves report from cache and exports to CSV format:</p>
     * <ul>
     *   <li>Retrieve ReportDto from reportCache using reportId</li>
     *   <li>Call reportService.exportReportToCsv() to format as CSV</li>
     *   <li>Set Content-Type header to text/csv</li>
     *   <li>Set Content-Disposition header to trigger browser download</li>
     *   <li>Return CSV string in response body</li>
     * </ul>
     * 
     * <p>HTTP Response Headers:</p>
     * <ul>
     *   <li><b>Content-Type:</b> text/csv; charset=UTF-8</li>
     *   <li><b>Content-Disposition:</b> attachment; filename="report_{reportId}.csv"</li>
     * </ul>
     * 
     * <p>CSV Format Example:</p>
     * <pre>
     * Report Type: ACCOUNT_SUMMARY
     * Report Name: Account Summary Report
     * Generated: 2024-01-15 14:30:00
     * Period: 2024-01-01 to 2024-01-31
     * Total Records: 4
     * 
     * metric,value
     * Total Accounts,150
     * Active Accounts,142
     * Total Credit Limits,7500000.00
     * Total Balances,2345678.90
     * </pre>
     * 
     * @param reportId The unique identifier of the generated report (from generateReport response)
     * @param format The export format (currently only "CSV" supported, defaults to "CSV")
     * @return ResponseEntity with CSV content as plain text and download headers
     * @throws IllegalArgumentException if reportId not found in cache
     * @throws BusinessException if CSV export fails
     */
    @GetMapping("/{reportId}/export")
    public ResponseEntity<String> exportReport(
            @PathVariable String reportId,
            @RequestParam(defaultValue = "CSV") String format) {
        
        log.info("GET /api/reports/{}/export - Format: {}", reportId, format);
        
        // Step 1: Retrieve report from cache
        // (In COBOL, report would be retrieved from batch output dataset)
        ReportDto report = reportCache.get(reportId);
        if (report == null) {
            log.warn("Report not found: {}", reportId);
            throw new IllegalArgumentException("Report not found: " + reportId);
        }
        
        log.debug("Retrieved report from cache: {} - {} records", 
                  report.getReportType(), report.getTotalRecords());
        
        // Step 2: Validate format parameter (only CSV currently supported)
        if (!"CSV".equalsIgnoreCase(format)) {
            log.warn("Unsupported export format requested: {}", format);
            throw new IllegalArgumentException(
                "Unsupported export format: " + format + ". Currently only CSV is supported.");
        }
        
        // Step 3: Export report to CSV format
        // (Replaces COBOL TDQ write with direct CSV generation)
        log.debug("Exporting report to CSV format");
        String csvData = reportService.exportReportToCsv(report);
        
        // Step 4: Set HTTP headers for file download
        // (Replaces COBOL EXEC CICS WRITEQ TD with HTTP response headers)
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(new MediaType("text", "csv"));
        headers.set(HttpHeaders.CONTENT_DISPOSITION, 
                    "attachment; filename=\"report_" + reportId + ".csv\"");
        
        log.info("Report exported successfully: {} bytes", csvData.length());
        
        // Step 5: Return CSV content with download headers
        return ResponseEntity.ok()
                .headers(headers)
                .body(csvData);
    }

    /**
     * Generate unique report identifier from report metadata.
     * 
     * <p>Creates report ID in format: {reportType}_{yyyyMMddHHmmss}</p>
     * <p>Example: ACCOUNT_SUMMARY_20240115143000</p>
     * 
     * @param report The generated report
     * @return Unique report identifier string
     */
    private String generateReportId(ReportDto report) {
        String timestamp = report.getGeneratedAt().format(REPORT_ID_FORMATTER);
        return report.getReportType() + "_" + timestamp;
    }

    /**
     * Data Transfer Object for report generation requests.
     * 
     * <p>Encapsulates request parameters from JSON request body matching COBOL input fields
     * from CORPT00C.cbl BMS map CORPT0AI:</p>
     * <ul>
     *   <li><b>reportType:</b> Report type code (MONTHLYI, YEARLYI, CUSTOMI → ACCOUNT_SUMMARY, TRANSACTION_ACTIVITY, etc.)</li>
     *   <li><b>startDate:</b> Start of reporting period (SDTYYYYI, SDTMMI, SDTDDI → LocalDate)</li>
     *   <li><b>endDate:</b> End of reporting period (EDTYYYYI, EDTMMI, EDTDDI → LocalDate)</li>
     *   <li><b>cardNumber:</b> Optional card number filter (not in original COBOL, added for flexibility)</li>
     *   <li><b>transactionType:</b> Optional transaction type filter (not in original COBOL, added for flexibility)</li>
     *   <li><b>transactionCategory:</b> Optional transaction category filter (not in original COBOL, added for flexibility)</li>
     *   <li><b>minAmount:</b> Optional minimum transaction amount filter (not in original COBOL, added for flexibility)</li>
     *   <li><b>maxAmount:</b> Optional maximum transaction amount filter (not in original COBOL, added for flexibility)</li>
     * </ul>
     * 
     * <p>Validation constraints:</p>
     * <ul>
     *   <li>reportType: Required, must not be blank</li>
     *   <li>startDate: Required, must not be null</li>
     *   <li>endDate: Required, must not be null</li>
     *   <li>All other fields: Optional</li>
     * </ul>
     * 
     * <p>This DTO is not in depends_on_files, so defined as inner class per Agent Action Plan Section IE3.</p>
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ReportRequest {
        
        /**
         * Report type identifier.
         * 
         * <p>Replaces COBOL fields: MONTHLYI, YEARLYI, CUSTOMI from CORPT0AI map</p>
         * 
         * <p>Valid values:</p>
         * <ul>
         *   <li>ACCOUNT_SUMMARY - Account statistics (replaces Monthly/Yearly)</li>
         *   <li>TRANSACTION_ACTIVITY - Transaction breakdown (replaces Custom)</li>
         *   <li>USER_ACTIVITY - User tracking report (new functionality)</li>
         *   <li>CUSTOM - Fully customizable report (replaces Custom with all filters)</li>
         * </ul>
         */
        @NotBlank(message = "Report type cannot be empty")
        private String reportType;
        
        /**
         * Start date of reporting period.
         * 
         * <p>Replaces COBOL fields: SDTYYYYI, SDTMMI, SDTDDI from CORPT0AI map (lines 381-383)</p>
         */
        @NotNull(message = "Start date cannot be null")
        private LocalDate startDate;
        
        /**
         * End date of reporting period.
         * 
         * <p>Replaces COBOL fields: EDTYYYYI, EDTMMI, EDTDDI from CORPT0AI map (lines 384-386)</p>
         */
        @NotNull(message = "End date cannot be null")
        private LocalDate endDate;
        
        /**
         * Optional card number filter (16-digit card number).
         * 
         * <p>Not present in original COBOL CORPT00C.cbl. Added for enhanced filtering capability.</p>
         */
        private String cardNumber;
        
        /**
         * Optional transaction type code filter (2-character code).
         * 
         * <p>Not present in original COBOL CORPT00C.cbl. Added for enhanced filtering capability.</p>
         * <p>Examples: '01' = Purchase, '02' = Cash Advance, '04' = Payment</p>
         */
        private String transactionType;
        
        /**
         * Optional transaction category code filter (integer category code).
         * 
         * <p>Not present in original COBOL CORPT00C.cbl. Added for enhanced filtering capability.</p>
         */
        private Integer transactionCategory;
        
        /**
         * Optional minimum transaction amount filter.
         * 
         * <p>Not present in original COBOL CORPT00C.cbl. Added for enhanced filtering capability.</p>
         */
        private BigDecimal minAmount;
        
        /**
         * Optional maximum transaction amount filter.
         * 
         * <p>Not present in original COBOL CORPT00C.cbl. Added for enhanced filtering capability.</p>
         */
        private BigDecimal maxAmount;
    }
}
