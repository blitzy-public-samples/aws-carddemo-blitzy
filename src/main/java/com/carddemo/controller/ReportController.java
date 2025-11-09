/*
 * ReportController.java
 * 
 * REST API controller for transaction reporting operations replacing CICS
 * transaction CR00 from COBOL program CORPT00C.cbl.
 * 
 * This controller transforms the mainframe batch job submission pattern (writing
 * JCL to internal reader via TDQ) into a modern synchronous REST API endpoint
 * providing immediate transaction report generation with flexible date range
 * filtering and pagination support.
 * 
 * COBOL Program Replaced: CORPT00C.cbl (CR00 transaction)
 * Original Functionality:
 * - Lines 60-72: WS-START-DATE and WS-END-DATE structures for date range input
 * - Lines 79-100: JCL job card generation for batch report submission
 * - Lines 236-354: Date validation logic and report type selection
 * - Lines 355-410: JCL submission to JOBS TDQ via EXEC CICS WRITEQ TD
 * 
 * Modern Transformation:
 * - Synchronous HTTP GET endpoint replacing asynchronous batch job submission
 * - Direct database queries via Spring Data JPA replacing VSAM TRANSACT file reads
 * - JSON response with aggregated statistics replacing printed batch report output
 * - Stateless request/response pattern replacing CICS COMMAREA state management
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

import com.carddemo.dto.response.ReportResponse;
import com.carddemo.service.reporting.ReportGenerationService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;

/**
 * REST controller providing transaction reporting endpoints for the CardDemo application.
 * 
 * <p>This controller replaces the mainframe CICS transaction CR00 (CORPT00C.cbl) which
 * generated and submitted batch JCL jobs to an internal reader queue for asynchronous
 * report processing. The modern implementation provides synchronous, on-demand report
 * generation through HTTP GET requests with immediate JSON responses.</p>
 * 
 * <h2>COBOL Program Transformation</h2>
 * <p><b>Original CORPT00C.cbl Pattern:</b></p>
 * <pre>
 * 1. User enters date range on BMS screen CORPT00
 * 2. COBOL validates dates using CSUTLDTC utility
 * 3. COBOL builds JCL job card with embedded parameters
 * 4. COBOL writes JCL line-by-line to TDQ 'JOBS' via EXEC CICS WRITEQ TD
 * 5. Batch job runs asynchronously, reading VSAM TRANSACT file
 * 6. Printed report output to SYSOUT dataset
 * </pre>
 * 
 * <p><b>Modern Spring Boot Pattern:</b></p>
 * <pre>
 * 1. Client sends GET /api/reports/transactions with date range query parameters
 * 2. Controller validates parameters via @DateTimeFormat and @RequestParam
 * 3. Controller delegates to ReportGenerationService
 * 4. Service queries PostgreSQL transaction table via JPA repository
 * 5. Service aggregates statistics and formats response
 * 6. Controller returns JSON ReportResponse with HTTP 200 OK
 * </pre>
 * 
 * <h2>Security</h2>
 * <p>All report endpoints require JWT authentication and are accessible to both
 * ADMIN and USER roles, matching the original RACF security profile for CR00
 * transaction which allowed all authorized users to generate reports.</p>
 * 
 * <h2>Date Range Handling</h2>
 * <p>The controller accepts optional startDate and endDate parameters. If not provided,
 * defaults to current month date range (first day to last day of current month),
 * matching CORPT00C.cbl default behavior for 'Monthly' report type.</p>
 * 
 * <h2>Pagination</h2>
 * <p>Large result sets are automatically paginated using Spring Data Pageable,
 * preventing performance degradation and maintaining sub-200ms response time target
 * even for date ranges containing thousands of transactions.</p>
 * 
 * @see ReportGenerationService
 * @see ReportResponse
 * @since 1.0
 */
@RestController
@RequestMapping("/api/reports")
@RequiredArgsConstructor
@Slf4j
@Tag(
    name = "Transaction Reporting",
    description = "Transaction report generation operations with date range filtering, " +
                  "aggregation, and export capabilities replacing CICS transaction CR00 " +
                  "(CORPT00C.cbl). Provides synchronous report generation via REST API " +
                  "replacing mainframe batch job submission pattern."
)
public class ReportController {

    /**
     * Service layer component for report generation business logic.
     * 
     * <p>Injected via constructor using Lombok @RequiredArgsConstructor,
     * following Spring Boot best practices for immutable dependency injection.</p>
     */
    private final ReportGenerationService reportGenerationService;

    /**
     * Generates transaction report for specified date range with optional filtering.
     * 
     * <p>This endpoint replaces CORPT00C.cbl CR00 transaction which built and submitted
     * batch JCL jobs for asynchronous report processing. The modern implementation provides
     * synchronous, on-demand report generation with immediate HTTP JSON response.</p>
     * 
     * <h3>COBOL Date Validation Replaced:</h3>
     * <p>Original CORPT00C.cbl lines 236-354 performed extensive date validation:</p>
     * <ul>
     *   <li>Date format validation (YYYY-MM-DD)</li>
     *   <li>Date component validation (month 01-12, day 01-31)</li>
     *   <li>Date range validation (start date <= end date)</li>
     *   <li>Lillian date conversion using CSUTLDTC utility</li>
     * </ul>
     * 
     * <p>Modern validation uses Spring @DateTimeFormat annotation for automatic
     * ISO 8601 date parsing with validation, and service layer business logic
     * for date range validation, throwing ValidationException for invalid ranges.</p>
     * 
     * <h3>Default Date Range Behavior:</h3>
     * <p>When startDate and endDate are not provided, defaults to current month
     * date range, matching CORPT00C.cbl 'Monthly' report type default behavior.</p>
     * 
     * <h3>Pagination Support:</h3>
     * <p>Uses Spring Data Pageable with default page size 100 records, configurable
     * via query parameters (page, size, sort). Prevents performance degradation for
     * large result sets while maintaining sub-200ms response time target.</p>
     * 
     * <h3>Example Requests:</h3>
     * <pre>
     * GET /api/reports/transactions?startDate=2024-01-01&endDate=2024-01-31
     * GET /api/reports/transactions?startDate=2024-01-01&endDate=2024-01-31&accountId=123
     * GET /api/reports/transactions?startDate=2024-01-01&endDate=2024-01-31&cardNumber=4111111111111111
     * GET /api/reports/transactions?startDate=2024-01-01&endDate=2024-01-31&page=0&size=50
     * GET /api/reports/transactions (defaults to current month)
     * </pre>
     * 
     * @param startDate optional start date for report date range in ISO 8601 format (YYYY-MM-DD),
     *                  defaults to first day of current month if not provided,
     *                  replacing CORPT00C.cbl WS-START-DATE structure (lines 60-65)
     * @param endDate optional end date for report date range in ISO 8601 format (YYYY-MM-DD),
     *                defaults to last day of current month if not provided,
     *                replacing CORPT00C.cbl WS-END-DATE structure (lines 66-71)
     * @param accountId optional account ID filter for transactions,
     *                  if provided only includes transactions for specified account,
     *                  enabling account-specific report generation
     * @param cardNumber optional card number filter for transactions,
     *                   if provided only includes transactions for specified card,
     *                   enabling card-specific report generation,
     *                   must match exact 16-digit card number format
     * @param pageable pagination parameters (page number, page size, sort),
     *                 defaults to page 0, size 100, unsorted if not provided,
     *                 automatically resolved from query parameters by Spring Data
     * @return ResponseEntity containing ReportResponse DTO with aggregated transaction
     *         statistics and detail records, HTTP 200 OK status for successful generation,
     *         or error responses (400 Bad Request for invalid parameters, 404 Not Found
     *         when no transactions match criteria, 401 Unauthorized for missing JWT,
     *         403 Forbidden for insufficient role)
     * @throws com.carddemo.exception.ValidationException if date range is invalid
     *         (startDate > endDate), automatically handled by GlobalExceptionHandler
     *         returning HTTP 400 Bad Request with field-level error details
     * @throws com.carddemo.exception.ResourceNotFoundException if no transactions found
     *         for specified date range and filters, automatically handled by
     *         GlobalExceptionHandler returning HTTP 404 Not Found with descriptive message
     */
    @Operation(
        summary = "Generate transaction report",
        description = "Generates comprehensive transaction report for specified date range " +
                      "with optional account and card filtering. Returns aggregated statistics " +
                      "including total transaction count, total amount, and category-wise breakdown " +
                      "along with detailed transaction list supporting pagination. Replaces mainframe " +
                      "CICS transaction CR00 (CORPT00C.cbl) which submitted batch JCL jobs for " +
                      "asynchronous report processing. Modern implementation provides synchronous " +
                      "on-demand report generation with immediate JSON response.",
        security = @SecurityRequirement(name = "bearerAuth")
    )
    @ApiResponses(value = {
        @ApiResponse(
            responseCode = "200",
            description = "Report generated successfully",
            content = @Content(
                mediaType = "application/json",
                schema = @Schema(implementation = ReportResponse.class)
            )
        ),
        @ApiResponse(
            responseCode = "400",
            description = "Invalid request parameters - date range validation failed " +
                          "(start date after end date, invalid date format, future dates)",
            content = @Content(mediaType = "application/json")
        ),
        @ApiResponse(
            responseCode = "401",
            description = "Unauthorized - missing or invalid JWT authentication token",
            content = @Content(mediaType = "application/json")
        ),
        @ApiResponse(
            responseCode = "403",
            description = "Forbidden - user lacks required role (ADMIN or USER)",
            content = @Content(mediaType = "application/json")
        ),
        @ApiResponse(
            responseCode = "404",
            description = "No transactions found for specified date range and filters",
            content = @Content(mediaType = "application/json")
        ),
        @ApiResponse(
            responseCode = "500",
            description = "Internal server error - unexpected system error during report generation",
            content = @Content(mediaType = "application/json")
        )
    })
    @GetMapping("/transactions")
    @PreAuthorize("hasAnyRole('ADMIN', 'USER')")
    public ResponseEntity<ReportResponse> generateTransactionReport(
            @Parameter(
                description = "Start date for report date range in ISO 8601 format (YYYY-MM-DD). " +
                              "Defaults to first day of current month if not provided. " +
                              "Must be on or before end date.",
                example = "2024-01-01",
                required = false
            )
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
            LocalDate startDate,
            
            @Parameter(
                description = "End date for report date range in ISO 8601 format (YYYY-MM-DD). " +
                              "Defaults to last day of current month if not provided. " +
                              "Must be on or after start date.",
                example = "2024-01-31",
                required = false
            )
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
            LocalDate endDate,
            
            @Parameter(
                description = "Optional account ID filter. If provided, only transactions " +
                              "for the specified account are included in the report.",
                example = "12345678901",
                required = false
            )
            @RequestParam(required = false)
            Long accountId,
            
            @Parameter(
                description = "Optional card number filter. If provided, only transactions " +
                              "for the specified 16-digit card number are included in the report.",
                example = "4111111111111111",
                required = false
            )
            @RequestParam(required = false)
            String cardNumber,
            
            @Parameter(
                description = "Pagination parameters. Supports 'page' (0-based page number), " +
                              "'size' (records per page, max 1000), and 'sort' (field,direction) " +
                              "query parameters for controlling result pagination and sorting.",
                example = "page=0&size=100&sort=transactionDate,desc"
            )
            @PageableDefault(size = 100)
            Pageable pageable) {
        
        // Log incoming report request with parameters for audit trail and monitoring
        log.info("Received transaction report request - startDate: {}, endDate: {}, " +
                 "accountId: {}, cardNumber: {}, page: {}, size: {}",
                startDate, endDate, accountId,
                cardNumber != null ? "****" + cardNumber.substring(cardNumber.length() - 4) : null,
                pageable.getPageNumber(), pageable.getPageSize());
        
        // Apply default date range if not provided (current month)
        // Matches CORPT00C.cbl default 'Monthly' report type behavior
        LocalDate effectiveStartDate = startDate;
        LocalDate effectiveEndDate = endDate;
        
        if (effectiveStartDate == null || effectiveEndDate == null) {
            LocalDate now = LocalDate.now();
            if (effectiveStartDate == null) {
                effectiveStartDate = now.withDayOfMonth(1);
                log.debug("Defaulting startDate to first day of current month: {}", effectiveStartDate);
            }
            if (effectiveEndDate == null) {
                effectiveEndDate = now.withDayOfMonth(now.lengthOfMonth());
                log.debug("Defaulting endDate to last day of current month: {}", effectiveEndDate);
            }
        }
        
        // Delegate report generation to service layer
        // Service performs date range validation, database queries, aggregation, and formatting
        // Throws ValidationException if date range invalid (startDate > endDate)
        // Throws ResourceNotFoundException if no transactions found for criteria
        ReportResponse response = reportGenerationService.generateTransactionReport(
                effectiveStartDate,
                effectiveEndDate,
                accountId,
                cardNumber,
                pageable
        );
        
        // Log successful report generation with record count for monitoring
        log.info("Successfully generated transaction report - total transactions: {}, " +
                 "total amount: {}, date range: {} to {}",
                response.getTransactionSummary().getTotalCount(),
                response.getTransactionSummary().getTotalAmount(),
                effectiveStartDate, effectiveEndDate);
        
        // Return HTTP 200 OK with ReportResponse body containing aggregated statistics
        // and transaction detail list, replacing CORPT00C.cbl batch report output
        // with structured JSON response for modern REST API client consumption
        return ResponseEntity.ok(response);
    }
}
