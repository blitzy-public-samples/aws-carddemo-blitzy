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

package com.carddemo.controller;

import com.carddemo.dto.request.TransactionRequest;
import com.carddemo.dto.response.TransactionCategoryResponse;
import com.carddemo.dto.response.TransactionListResponse;
import com.carddemo.exception.AccountNotFoundException;
import com.carddemo.exception.CardNotFoundException;
import com.carddemo.exception.InsufficientBalanceException;
import com.carddemo.exception.TransactionException;
import com.carddemo.service.TransactionCategoryService;
import com.carddemo.service.TransactionCreationService;
import com.carddemo.service.TransactionListService;
import jakarta.validation.Valid;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Pageable;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;

/**
 * REST controller providing transaction management operations including list display
 * with pagination and filtering, category aggregation, and transaction creation.
 * 
 * <p><strong>COBOL Source Programs Transformed:</strong></p>
 * <ul>
 *   <li>COTRN00C.cbl - Transaction list display with VSAM STARTBR/READNEXT/READPREV browsing</li>
 *   <li>COTRN01C.cbl - Transaction category aggregation with COMP-3 precision preservation</li>
 *   <li>COTRN02C.cbl - Transaction creation with balance validation and atomic updates</li>
 * </ul>
 * 
 * <p><strong>CICS Transaction IDs Replaced:</strong></p>
 * <ul>
 *   <li>CT00 (COTRN00C) → GET /api/transactions with pagination</li>
 *   <li>CT01 (COTRN01C) → GET /api/transactions/categories with aggregation</li>
 *   <li>CT02 (COTRN02C) → POST /api/transactions with validation</li>
 * </ul>
 * 
 * <p><strong>REST Endpoint Summary:</strong></p>
 * <ul>
 *   <li>GET /api/transactions - Paginated transaction list with optional filtering by
 *       date range, account ID, card number, and transaction type. Returns 10 transactions
 *       per page matching COBOL BMS map OCCURS 10 TIMES specification.</li>
 *   <li>GET /api/transactions/categories - Category aggregation with sum totals, transaction
 *       counts, and percentage distributions. Preserves COMP-3 decimal precision using
 *       BigDecimal with scale 2 and RoundingMode.HALF_UP.</li>
 *   <li>POST /api/transactions - Creates new transaction with comprehensive validation,
 *       balance checking, authorization verification, and atomic account balance updates
 *       within @Transactional boundary matching CICS SYNCPOINT semantics.</li>
 * </ul>
 * 
 * <p><strong>Pagination Behavior (Section 0.3 Requirements):</strong></p>
 * <ul>
 *   <li>Fixed page size: Exactly 10 transactions per page (matches COTRN00 BMS map)</li>
 *   <li>Page parameter: 0-based index (page=0 for first page, page=1 for second page)</li>
 *   <li>Sort order: Most recent transactions first (ORDER BY timestamp DESC)</li>
 *   <li>PF7 backward (COBOL line 234) → Previous page navigation via page-1</li>
 *   <li>PF8 forward (COBOL line 257) → Next page navigation via page+1</li>
 *   <li>Response metadata: Includes currentPage, totalPages, totalElements, hasNext, hasPrevious</li>
 * </ul>
 * 
 * <p><strong>Date Range Filtering (Section 0.1 Requirements):</strong></p>
 * <ul>
 *   <li>startDate parameter: Filter transactions >= startDate (inclusive)</li>
 *   <li>endDate parameter: Filter transactions <= endDate (inclusive)</li>
 *   <li>Null handling: Null dates omit filtering (show all transactions)</li>
 *   <li>Validation: startDate must be <= endDate enforced by service layer</li>
 *   <li>Maximum range: 90 days enforced to prevent performance degradation</li>
 * </ul>
 * 
 * <p><strong>Security and Authorization (Section 0.9 Security Model):</strong></p>
 * <ul>
 *   <li>@PreAuthorize: All endpoints require authentication with valid JWT token</li>
 *   <li>ROLE_USER: Users can only view/create transactions for their own accounts</li>
 *   <li>ROLE_ADMIN: Administrative users can access all transactions across accounts</li>
 *   <li>Account ownership validation: Service layer verifies user owns the account</li>
 *   <li>401 Unauthorized: Returned for missing or invalid authentication</li>
 *   <li>403 Forbidden: Returned for insufficient permissions (non-owner, non-admin)</li>
 * </ul>
 * 
 * <p><strong>Error Handling and HTTP Status Codes:</strong></p>
 * <ul>
 *   <li>200 OK: Successful transaction list or category retrieval</li>
 *   <li>201 Created: New transaction successfully posted with transaction ID returned</li>
 *   <li>400 Bad Request: Validation failures including insufficient balance, invalid formats,
 *       missing required fields, or date range validation errors</li>
 *   <li>404 Not Found: Account ID or card number not found in database</li>
 *   <li>422 Unprocessable Entity: Business rule violations (insufficient balance, expired card)</li>
 *   <li>500 Internal Server Error: Unexpected system errors (logged for investigation)</li>
 * </ul>
 * 
 * <p><strong>Transaction Management (Section 0.9 Requirements):</strong></p>
 * <ul>
 *   <li>@Transactional boundaries: Service layer manages transaction atomicity</li>
 *   <li>CICS SYNCPOINT equivalent: Spring @Transactional commit on method completion</li>
 *   <li>CICS SYNCPOINT ROLLBACK equivalent: Automatic rollback on exception</li>
 *   <li>Isolation level: READ_COMMITTED prevents dirty reads during multi-step operations</li>
 *   <li>Propagation: REQUIRED ensures all operations occur within single transaction</li>
 * </ul>
 * 
 * <p><strong>Performance Characteristics (Section 0.2 SLA):</strong></p>
 * <ul>
 *   <li>Response time target: < 200ms at 95th percentile for GET operations</li>
 *   <li>Response time target: < 500ms at 95th percentile for POST operations</li>
 *   <li>Throughput: Supports 10,000 TPS without performance degradation</li>
 *   <li>Connection pooling: HikariCP with 20-50 connections for database access</li>
 *   <li>Index usage: Compound indexes on (account_id, timestamp) for optimal query performance</li>
 * </ul>
 * 
 * <p><strong>Audit Trail and Logging (Section 0.9 Compliance):</strong></p>
 * <ul>
 *   <li>@Slf4j: Comprehensive logging for audit trail and debugging</li>
 *   <li>Request logging: Log all incoming requests with user ID, timestamp, parameters</li>
 *   <li>Error logging: Log all exceptions with stack traces for troubleshooting</li>
 *   <li>Success logging: Log successful transaction creation with transaction ID</li>
 *   <li>Authorization logging: Log authorization failures for security monitoring</li>
 * </ul>
 * 
 * <p><strong>COBOL Program Flow Preserved:</strong></p>
 * <pre>
 * COBOL Program    Lines        Logic                        REST Endpoint
 * --------------------------------------------------------------------------------
 * COTRN00C         146-230      PROCESS-ENTER-KEY            GET /api/transactions
 * COTRN00C         279-328      PROCESS-PAGE-FORWARD         GET /api/transactions?page=N+1
 * COTRN00C         333-376      PROCESS-PAGE-BACKWARD        GET /api/transactions?page=N-1
 * COTRN00C         381-445      POPULATE-TRAN-DATA           TransactionListResponse population
 * COTRN01C         144-193      PROCESS-ENTER-KEY (category) GET /api/transactions/categories
 * COTRN02C         164-188      PROCESS-ENTER-KEY (add)      POST /api/transactions
 * COTRN02C         442-466      ADD-TRANSACTION              transactionCreationService.createTransaction()
 * </pre>
 * 
 * <p><strong>Usage Examples:</strong></p>
 * <pre>
 * // Retrieve first page of transactions for account 100000000001
 * GET /api/transactions?accountId=100000000001&page=0
 * 
 * // Retrieve transactions for last 30 days with pagination
 * GET /api/transactions?accountId=100000000001&page=0
 *     &startDate=2024-12-01&endDate=2024-12-31
 * 
 * // Retrieve category aggregation for account
 * GET /api/transactions/categories?accountId=100000000001
 *     &startDate=2024-12-01&endDate=2024-12-31
 * 
 * // Create new transaction
 * POST /api/transactions
 * {
 *   "accountId": "100000000001",
 *   "cardNumber": "4532123456789012",
 *   "transactionType": "01",
 *   "categoryCode": 1001,
 *   "transactionAmount": 125.50,
 *   "transactionDescription": "Gas Station Purchase",
 *   "merchantId": "MERCHANT123",
 *   "merchantName": "Shell Gas Station",
 *   "merchantCity": "Seattle",
 *   "merchantZipCode": "98101"
 * }
 * </pre>
 * 
 * @see TransactionListService
 * @see TransactionCategoryService
 * @see TransactionCreationService
 * @see TransactionListResponse
 * @see TransactionCategoryResponse
 * @see TransactionRequest
 * @see InsufficientBalanceException
 * @see TransactionException
 * @see <a href="Section 0.6">File-by-File Transformation Plan</a>
 * @see <a href="Section 0.9">Special Instructions for Refactoring</a>
 * @author CardDemo Migration Team
 * @version 1.0
 * @since 2024-01-01
 */
@Slf4j
@RestController
@RequestMapping("/api/transactions")
public class TransactionController {

    /**
     * Service for retrieving paginated transaction lists with filtering.
     * Replaces COBOL COTRN00C.cbl VSAM STARTBR/READNEXT/READPREV browsing pattern
     * with Spring Data JPA pagination supporting 10 transactions per page.
     */
    private final TransactionListService transactionListService;

    /**
     * Service for transaction category aggregation with calculation precision.
     * Replaces COBOL COTRN01C.cbl category accumulation loops with SQL GROUP BY
     * aggregation maintaining BigDecimal precision for all financial calculations.
     */
    private final TransactionCategoryService transactionCategoryService;

    /**
     * Service for creating new transactions with balance validation.
     * Replaces COBOL COTRN02C.cbl transaction posting logic with @Transactional
     * atomic operations ensuring balance updates and transaction creation occur
     * within single database transaction boundary.
     */
    private final TransactionCreationService transactionCreationService;

    /**
     * Constructor for dependency injection of required services.
     * Uses constructor injection pattern per Spring Boot best practices for
     * testability and immutability of service dependencies.
     * 
     * @param transactionListService service for paginated transaction retrieval
     * @param transactionCategoryService service for category aggregation
     * @param transactionCreationService service for transaction posting
     */
    @Autowired
    public TransactionController(
            TransactionListService transactionListService,
            TransactionCategoryService transactionCategoryService,
            TransactionCreationService transactionCreationService) {
        this.transactionListService = transactionListService;
        this.transactionCategoryService = transactionCategoryService;
        this.transactionCreationService = transactionCreationService;
    }

    /**
     * Retrieves paginated list of transactions with optional filtering by date range,
     * account ID, card number, and transaction type.
     * 
     * <p><strong>COBOL Source:</strong> COTRN00C.cbl lines 279-328 (PROCESS-PAGE-FORWARD)
     * and lines 381-445 (POPULATE-TRAN-DATA) transforming VSAM sequential browsing
     * to Spring Data JPA pagination with 10 transactions per page.</p>
     * 
     * <p><strong>CICS Transaction:</strong> CT00 - Transaction List Display</p>
     * 
     * <p><strong>Pagination Behavior:</strong></p>
     * <ul>
     *   <li>Returns exactly 10 transactions per page matching COBOL BMS map OCCURS 10 TIMES</li>
     *   <li>Page parameter is 0-based: page=0 returns first 10 transactions</li>
     *   <li>Results sorted by transaction timestamp descending (most recent first)</li>
     *   <li>Response includes pagination metadata: currentPage, totalPages, hasNext, hasPrevious</li>
     * </ul>
     * 
     * <p><strong>Filtering Parameters:</strong></p>
     * <ul>
     *   <li>accountId: Filter transactions by specific account (required for ROLE_USER)</li>
     *   <li>cardNumber: Filter by specific card number (optional)</li>
     *   <li>startDate: Filter transactions >= startDate inclusive (optional, format: YYYY-MM-DD)</li>
     *   <li>endDate: Filter transactions <= endDate inclusive (optional, format: YYYY-MM-DD)</li>
     *   <li>transactionType: Filter by transaction type code (optional, e.g., "01"=Purchase, "02"=Cash Advance)</li>
     * </ul>
     * 
     * <p><strong>Authorization:</strong> @PreAuthorize enforces ROLE_USER or ROLE_ADMIN.
     * Regular users can only access transactions for their own accounts. Administrative
     * users can access transactions across all accounts. Service layer validates
     * account ownership before returning data.</p>
     * 
     * <p><strong>HTTP Status Codes:</strong></p>
     * <ul>
     *   <li>200 OK: Successfully retrieved transaction list (may be empty page)</li>
     *   <li>400 Bad Request: Invalid parameters (negative page, invalid date format, startDate > endDate)</li>
     *   <li>401 Unauthorized: Missing or invalid authentication token</li>
     *   <li>403 Forbidden: User attempting to access another user's account</li>
     *   <li>404 Not Found: Account ID not found in system</li>
     * </ul>
     * 
     * <p><strong>Example Requests:</strong></p>
     * <pre>
     * // First page of all transactions for account
     * GET /api/transactions?accountId=100000000001&page=0
     * 
     * // Second page with date filtering
     * GET /api/transactions?accountId=100000000001&page=1
     *     &startDate=2024-12-01&endDate=2024-12-31
     * 
     * // Filter by card number
     * GET /api/transactions?cardNumber=4532123456789012&page=0
     * 
     * // Filter by transaction type (01=Purchase)
     * GET /api/transactions?accountId=100000000001&transactionType=01&page=0
     * </pre>
     * 
     * <p><strong>Example Response:</strong></p>
     * <pre>
     * {
     *   "transactions": [
     *     {
     *       "transactionId": "0000000123456789",
     *       "accountId": "100000000001",
     *       "cardNumber": "************9012",
     *       "transactionAmount": 125.50,
     *       "transactionType": "01",
     *       "transactionDescription": "Gas Station Purchase",
     *       "transactionDate": "2024-12-15",
     *       "merchantName": "Shell Gas Station",
     *       "merchantCity": "Seattle"
     *     }
     *   ],
     *   "currentPage": 0,
     *   "totalPages": 5,
     *   "totalElements": 47,
     *   "pageSize": 10,
     *   "hasNext": true,
     *   "hasPrevious": false,
     *   "firstTransactionId": "0000000123456789",
     *   "lastTransactionId": "0000000123456780"
     * }
     * </pre>
     * 
     * @param accountId optional account identifier for filtering (required for ROLE_USER, optional for ROLE_ADMIN)
     * @param cardNumber optional card number for filtering (format: 16-digit string)
     * @param page page number (0-based, default=0, must be >= 0)
     * @param startDate optional start date for filtering (format: YYYY-MM-DD, inclusive)
     * @param endDate optional end date for filtering (format: YYYY-MM-DD, inclusive)
     * @param transactionType optional transaction type code for filtering (e.g., "01", "02")
     * @return ResponseEntity containing TransactionListResponse with up to 10 transactions and pagination metadata
     */
    @GetMapping
    @PreAuthorize("hasRole('USER') or hasRole('ADMIN')")
    public ResponseEntity<TransactionListResponse> getTransactions(
            @RequestParam(required = false) String accountId,
            @RequestParam(required = false) String cardNumber,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(required = false) 
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startDate,
            @RequestParam(required = false) 
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate,
            @RequestParam(required = false) String transactionType) {
        
        // Log incoming request for audit trail
        log.info("GET /api/transactions - accountId={}, cardNumber={}, page={}, startDate={}, endDate={}, transactionType={}", 
                accountId, cardNumber != null ? maskCardNumber(cardNumber) : null, page, startDate, endDate, transactionType);
        
        // Validate page parameter (COBOL doesn't support negative pages)
        if (page < 0) {
            log.warn("Invalid page parameter: page={}", page);
            throw new IllegalArgumentException("Page number must be non-negative");
        }
        
        // Get current authenticated user for authorization checks
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        String currentUserId = authentication.getName();
        boolean isAdmin = authentication.getAuthorities().stream()
                .anyMatch(auth -> auth.getAuthority().equals("ROLE_ADMIN"));
        
        log.debug("User {} (isAdmin={}) requesting transactions", currentUserId, isAdmin);
        
        // Determine filtering strategy based on parameters
        TransactionListResponse response;
        
        try {
            if (accountId != null && !accountId.isBlank()) {
                // Filter by account ID (most common case)
                // Maps to COBOL COTRN00C lines 206-219 (account validation)
                log.debug("Filtering transactions by accountId={}", accountId);
                response = transactionListService.getTransactionList(
                        accountId, page, startDate, endDate);
            } else if (cardNumber != null && !cardNumber.isBlank()) {
                // Filter by card number
                // Maps to COBOL COTRN02C lines 210-223 (card number validation)
                log.debug("Filtering transactions by cardNumber={}", maskCardNumber(cardNumber));
                response = transactionListService.getTransactionListByCard(
                        cardNumber, page, startDate, endDate);
            } else if (isAdmin) {
                // Admin can view all transactions without account filter
                log.debug("Admin user retrieving all transactions");
                response = transactionListService.getAllTransactions(
                        page, startDate, endDate);
            } else {
                // Regular user must specify either accountId or cardNumber
                log.warn("Regular user {} attempted to retrieve transactions without account/card filter", currentUserId);
                throw new IllegalArgumentException(
                        "Account ID or card number required for transaction retrieval");
            }
            
            log.info("Successfully retrieved {} transactions (page {}/{})", 
                    response.getTransactions().size(), 
                    response.getCurrentPage() + 1, 
                    response.getTotalPages());
            
            return ResponseEntity.ok(response);
            
        } catch (AccountNotFoundException e) {
            // Account not found - return 404
            log.error("Account not found: {}", e.getMessage());
            throw e; // Re-throw for GlobalExceptionHandler to return 404
            
        } catch (CardNotFoundException e) {
            // Card not found - return 404
            log.error("Card not found: {}", e.getMessage());
            throw e; // Re-throw for GlobalExceptionHandler to return 404
            
        } catch (IllegalArgumentException e) {
            log.error("Validation error retrieving transactions: {}", e.getMessage());
            throw e; // Re-throw for GlobalExceptionHandler to process
        } catch (Exception e) {
            log.error("Unexpected error retrieving transactions", e);
            throw new TransactionException("Error retrieving transaction list", "TXN_RETRIEVAL_ERROR", e);
        }
    }

    /**
     * Retrieves transaction category aggregation with sum totals, counts, and percentage distributions.
     * 
     * <p><strong>COBOL Source:</strong> COTRN01C.cbl lines 144-193 (PROCESS-ENTER-KEY for category view)
     * transforming COBOL accumulator loops with COMP-3 arithmetic to SQL GROUP BY aggregation
     * with BigDecimal precision preservation.</p>
     * 
     * <p><strong>CICS Transaction:</strong> CT01 - Transaction Category Summary</p>
     * 
     * <p><strong>Aggregation Logic:</strong></p>
     * <ul>
     *   <li>Groups transactions by category code (groceries, fuel, dining, etc.)</li>
     *   <li>Calculates total amount per category with BigDecimal scale=2, RoundingMode.HALF_UP</li>
     *   <li>Counts number of transactions per category</li>
     *   <li>Computes percentage distribution: (categoryTotal / grandTotal) * 100</li>
     *   <li>Calculates average transaction amount per category</li>
     * </ul>
     * 
     * <p><strong>Precision Requirements (Section 0.9):</strong></p>
     * <ul>
     *   <li>ALL calculations use BigDecimal matching COBOL PIC S9(09)V99 COMP-3</li>
     *   <li>Scale: 2 decimal places for all monetary amounts</li>
     *   <li>Rounding: HALF_UP mode prevents rounding discrepancies</li>
     *   <li>Division: safeDivide() prevents ArithmeticException for percentage calculations</li>
     * </ul>
     * 
     * <p><strong>Date Range Filtering:</strong></p>
     * <ul>
     *   <li>startDate: Include transactions >= startDate (inclusive)</li>
     *   <li>endDate: Include transactions <= endDate (inclusive)</li>
     *   <li>Null dates: Aggregate all transactions in account (no date filtering)</li>
     * </ul>
     * 
     * <p><strong>Authorization:</strong> @PreAuthorize enforces ROLE_USER or ROLE_ADMIN.
     * Regular users can only aggregate transactions for their own accounts. Administrative
     * users can aggregate transactions across any account.</p>
     * 
     * <p><strong>HTTP Status Codes:</strong></p>
     * <ul>
     *   <li>200 OK: Successfully retrieved category aggregation (may be empty if no transactions)</li>
     *   <li>400 Bad Request: Invalid parameters (startDate > endDate, invalid account format)</li>
     *   <li>401 Unauthorized: Missing or invalid authentication token</li>
     *   <li>403 Forbidden: User attempting to access another user's account</li>
     *   <li>404 Not Found: Account ID not found in system</li>
     * </ul>
     * 
     * <p><strong>Example Request:</strong></p>
     * <pre>
     * GET /api/transactions/categories?accountId=100000000001
     *     &startDate=2024-12-01&endDate=2024-12-31
     * </pre>
     * 
     * <p><strong>Example Response:</strong></p>
     * <pre>
     * {
     *   "accountId": "100000000001",
     *   "startDate": "2024-12-01",
     *   "endDate": "2024-12-31",
     *   "categories": [
     *     {
     *       "categoryCode": 1001,
     *       "categoryName": "Groceries",
     *       "totalAmount": 542.75,
     *       "transactionCount": 12,
     *       "percentage": 32.50,
     *       "averageAmount": 45.23
     *     },
     *     {
     *       "categoryCode": 1002,
     *       "categoryName": "Fuel",
     *       "totalAmount": 387.20,
     *       "transactionCount": 8,
     *       "percentage": 23.19,
     *       "averageAmount": 48.40
     *     }
     *   ],
     *   "grandTotal": 1669.95,
     *   "totalTransactionCount": 35
     * }
     * </pre>
     * 
     * @param accountId account identifier for aggregation (required for ROLE_USER, optional for ROLE_ADMIN)
     * @param startDate optional start date for filtering (format: YYYY-MM-DD, inclusive)
     * @param endDate optional end date for filtering (format: YYYY-MM-DD, inclusive)
     * @return ResponseEntity containing TransactionCategoryResponse with category aggregation data
     */
    @GetMapping("/categories")
    @PreAuthorize("hasRole('USER') or hasRole('ADMIN')")
    public ResponseEntity<TransactionCategoryService.AggregationResult> getTransactionCategories(
            @RequestParam(required = false) String accountId,
            @RequestParam(required = false) 
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startDate,
            @RequestParam(required = false) 
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate) {
        
        // Log incoming request for audit trail
        log.info("GET /api/transactions/categories - accountId={}, startDate={}, endDate={}", 
                accountId, startDate, endDate);
        
        // Get current authenticated user for authorization checks
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        String currentUserId = authentication.getName();
        boolean isAdmin = authentication.getAuthorities().stream()
                .anyMatch(auth -> auth.getAuthority().equals("ROLE_ADMIN"));
        
        log.debug("User {} (isAdmin={}) requesting category aggregation", currentUserId, isAdmin);
        
        // Validate accountId parameter for non-admin users
        if (!isAdmin && (accountId == null || accountId.isBlank())) {
            log.warn("Regular user {} attempted to retrieve categories without account ID", currentUserId);
            throw new IllegalArgumentException("Account ID is required for category aggregation");
        }
        
        try {
            TransactionCategoryService.AggregationResult response;
            
            // Both admin and regular users need to provide accountId for now
            // The service method requires accountId for category aggregation
            if (accountId == null || accountId.isBlank()) {
                log.warn("Admin attempted to aggregate categories without account ID");
                throw new IllegalArgumentException("Account ID is required for category aggregation");
            }
            
            // Aggregate by specific account
            // Maps to COBOL COTRN01C lines 144-193 with category accumulation
            log.debug("Aggregating transaction categories for accountId={}", accountId);
            response = transactionCategoryService.getTransactionCategorySummary(
                    accountId, startDate, endDate);
            
            log.info("Successfully aggregated {} categories with grand total {}", 
                    response.getCategorySummaries().size(), 
                    response.getGrandTotal());
            
            return ResponseEntity.ok(response);
            
        } catch (AccountNotFoundException e) {
            // Account not found - return 404
            log.error("Account not found: {}", e.getMessage());
            throw e; // Re-throw for GlobalExceptionHandler to return 404
            
        } catch (IllegalArgumentException e) {
            log.error("Validation error aggregating categories: {}", e.getMessage());
            throw e; // Re-throw for GlobalExceptionHandler to process
        } catch (Exception e) {
            log.error("Unexpected error aggregating categories", e);
            throw new TransactionException("Error aggregating transaction categories", "TXN_CATEGORY_ERROR", e);
        }
    }

    /**
     * Creates a new credit card transaction with comprehensive validation, balance checking,
     * and atomic account balance updates.
     * 
     * <p><strong>COBOL Source:</strong> COTRN02C.cbl lines 164-188 (PROCESS-ENTER-KEY) and
     * lines 442-466 (ADD-TRANSACTION) transforming VSAM WRITE operations to JPA repository
     * save with @Transactional boundary ensuring atomic multi-step operations.</p>
     * 
     * <p><strong>CICS Transaction:</strong> CT02 - Add New Transaction</p>
     * 
     * <p><strong>Transaction Processing Steps:</strong></p>
     * <ol>
     *   <li>Validate request payload with Bean Validation annotations (@Valid)</li>
     *   <li>Verify account exists and is in active status (maps to COBOL lines 196-230)</li>
     *   <li>Verify card number exists and belongs to specified account (lines 210-223)</li>
     *   <li>Check available credit/balance for transaction amount (lines 340-351)</li>
     *   <li>Validate transaction type code against reference data (lines 252-257)</li>
     *   <li>Validate category code against reference data (lines 258-263)</li>
     *   <li>Generate unique transaction ID (auto-increment sequence)</li>
     *   <li>Create transaction record with all merchant and timestamp data (lines 444-465)</li>
     *   <li>Update account balance atomically (CICS SYNCPOINT equivalent)</li>
     *   <li>Return 201 Created with transaction ID in response</li>
     * </ol>
     * 
     * <p><strong>Validation Rules (Section 0.9):</strong></p>
     * <ul>
     *   <li>Account ID: Required, max 11 characters, must exist in database</li>
     *   <li>Card Number: Required, 16 digits, must be active and not expired</li>
     *   <li>Transaction Amount: Required, positive, max 10 integer digits + 2 decimal places</li>
     *   <li>Transaction Type: Required, 2-character code, must be valid type</li>
     *   <li>Category Code: Required, 4-digit code, must be valid category</li>
     *   <li>Description: Required, max 50 characters</li>
     *   <li>Merchant ID: Required, numeric, max 10 digits</li>
     *   <li>Merchant Name: Required, max 50 characters</li>
     *   <li>Merchant City: Required, max 25 characters</li>
     *   <li>Merchant Zip: Required, 5 or 9 digits</li>
     * </ul>
     * 
     * <p><strong>Balance Validation:</strong></p>
     * <ul>
     *   <li>Purchase transactions: Check available credit = credit_limit - current_balance</li>
     *   <li>Cash advance: Verify cash advance limit not exceeded</li>
     *   <li>InsufficientBalanceException: Thrown if transaction would exceed credit limit</li>
     *   <li>Maps to COBOL validation: IF TRAN-AMT > AVAILABLE-CREDIT (line 340)</li>
     * </ul>
     * 
     * <p><strong>Transaction Atomicity (Section 0.9):</strong></p>
     * <ul>
     *   <li>@Transactional boundary: All operations within single database transaction</li>
     *   <li>CICS SYNCPOINT equivalent: Spring commit on successful completion</li>
     *   <li>CICS SYNCPOINT ROLLBACK equivalent: Automatic rollback on any exception</li>
     *   <li>Isolation: READ_COMMITTED prevents concurrent balance update conflicts</li>
     *   <li>Account balance update: Atomic increment/decrement preventing race conditions</li>
     * </ul>
     * 
     * <p><strong>Authorization:</strong> @PreAuthorize enforces ROLE_USER or ROLE_ADMIN.
     * Regular users can only create transactions for their own accounts. Administrative
     * users can create transactions for any account.</p>
     * 
     * <p><strong>HTTP Status Codes:</strong></p>
     * <ul>
     *   <li>201 Created: Transaction successfully created and account balance updated</li>
     *   <li>400 Bad Request: Validation failures (missing required fields, invalid formats)</li>
     *   <li>401 Unauthorized: Missing or invalid authentication token</li>
     *   <li>403 Forbidden: User attempting to create transaction for another user's account</li>
     *   <li>404 Not Found: Account ID or card number not found</li>
     *   <li>422 Unprocessable Entity: Business rule violations (insufficient balance, expired card)</li>
     *   <li>500 Internal Server Error: Unexpected system errors</li>
     * </ul>
     * 
     * <p><strong>Example Request:</strong></p>
     * <pre>
     * POST /api/transactions
     * Content-Type: application/json
     * 
     * {
     *   "accountId": "100000000001",
     *   "cardNumber": "4532123456789012",
     *   "transactionType": "01",
     *   "categoryCode": 1002,
     *   "transactionAmount": 125.50,
     *   "transactionDescription": "Gas Station Purchase",
     *   "transactionSource": "POS",
     *   "originationTimestamp": "2024-12-15T14:30:00",
     *   "processingTimestamp": "2024-12-15T14:30:01",
     *   "merchantId": "MERCHANT123",
     *   "merchantName": "Shell Gas Station",
     *   "merchantCity": "Seattle",
     *   "merchantZipCode": "98101"
     * }
     * </pre>
     * 
     * <p><strong>Example Success Response (201 Created):</strong></p>
     * <pre>
     * {
     *   "transactionId": "0000000123456789",
     *   "accountId": "100000000001",
     *   "cardNumber": "************9012",
     *   "transactionAmount": 125.50,
     *   "transactionType": "01",
     *   "categoryCode": 1002,
     *   "transactionDescription": "Gas Station Purchase",
     *   "transactionDate": "2024-12-15T14:30:00",
     *   "merchantName": "Shell Gas Station",
     *   "merchantCity": "Seattle",
     *   "newAccountBalance": 1874.50,
     *   "availableCredit": 3125.50,
     *   "message": "Transaction added successfully. Your Tran ID is 0000000123456789."
     * }
     * </pre>
     * 
     * <p><strong>Example Error Response (422 Insufficient Balance):</strong></p>
     * <pre>
     * {
     *   "timestamp": "2024-12-15T14:30:00",
     *   "status": 422,
     *   "error": "Unprocessable Entity",
     *   "message": "Insufficient available credit. Available: $500.00, Requested: $1250.00",
     *   "path": "/api/transactions"
     * }
     * </pre>
     * 
     * @param transactionRequest validated transaction creation request with all required fields
     * @return ResponseEntity with HTTP 201 Created and transaction details including transaction ID
     * @throws InsufficientBalanceException if transaction amount exceeds available credit
     * @throws TransactionException for general transaction processing errors
     */
    @PostMapping
    @PreAuthorize("hasRole('USER') or hasRole('ADMIN')")
    public ResponseEntity<?> createTransaction(@Valid @RequestBody TransactionRequest transactionRequest) {
        
        // Log incoming request for audit trail (mask sensitive data)
        log.info("POST /api/transactions - accountId={}, cardNumber={}, amount={}", 
                transactionRequest.getAccountId(),
                maskCardNumber(transactionRequest.getCardNumber()),
                transactionRequest.getTransactionAmount());
        
        // Get current authenticated user for authorization checks
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        String currentUserId = authentication.getName();
        boolean isAdmin = authentication.getAuthorities().stream()
                .anyMatch(auth -> auth.getAuthority().equals("ROLE_ADMIN"));
        
        log.debug("User {} (isAdmin={}) creating transaction", currentUserId, isAdmin);
        
        try {
            // Delegate to service layer for transaction processing
            // Service layer handles:
            // 1. Account and card validation (COBOL lines 196-230)
            // 2. Balance checking (COBOL lines 340-351)
            // 3. Transaction creation (COBOL lines 444-465)
            // 4. Atomic balance update (CICS SYNCPOINT equivalent)
            var response = transactionCreationService.createTransaction(transactionRequest);
            
            log.info("Successfully created transaction {} for account {} with amount {}",
                    response.getTransactionId(),
                    transactionRequest.getAccountId(),
                    transactionRequest.getTransactionAmount());
            
            // Return 201 Created with transaction details
            // Maps to COBOL lines 724-734 success message display
            return ResponseEntity
                    .status(HttpStatus.CREATED)
                    .body(response);
            
        } catch (InsufficientBalanceException e) {
            // Maps to COBOL insufficient balance error (line 340)
            log.warn("Insufficient balance for transaction: accountId={}, amount={}, available={}", 
                    transactionRequest.getAccountId(),
                    transactionRequest.getTransactionAmount(),
                    e.getAvailableCredit());
            throw e; // Re-throw for GlobalExceptionHandler to return 422
            
        } catch (AccountNotFoundException e) {
            // Account not found - return 404
            log.error("Account not found: {}", e.getMessage());
            throw e; // Re-throw for GlobalExceptionHandler to return 404
            
        } catch (CardNotFoundException e) {
            // Card not found - return 404
            log.error("Card not found: {}", e.getMessage());
            throw e; // Re-throw for GlobalExceptionHandler to return 404
            
        } catch (IllegalArgumentException e) {
            // Validation error (invalid account, card, or field format)
            // Maps to COBOL validation errors (lines 196-437)
            log.error("Validation error creating transaction: {}", e.getMessage());
            throw e; // Re-throw for GlobalExceptionHandler to return 400
            
        } catch (TransactionException e) {
            // General transaction processing error
            // Maps to COBOL file I/O errors (lines 712-749)
            log.error("Transaction processing error: {}", e.getMessage(), e);
            throw e; // Re-throw for GlobalExceptionHandler to return 500
            
        } catch (Exception e) {
            // Unexpected system error
            log.error("Unexpected error creating transaction", e);
            throw new TransactionException("Unexpected error processing transaction", "TXN_CREATION_ERROR", e);
        }
    }

    /**
     * Utility method to mask card numbers for logging and security.
     * Displays only last 4 digits of card number with asterisks masking first 12 digits.
     * 
     * <p>Example: "4532123456789012" → "************9012"</p>
     * 
     * <p>Complies with PCI-DSS requirement to mask Primary Account Number (PAN) in logs
     * and audit trails per Section 0.9 security requirements.</p>
     * 
     * @param cardNumber the full 16-digit card number
     * @return masked card number showing only last 4 digits
     */
    private String maskCardNumber(String cardNumber) {
        if (cardNumber == null || cardNumber.length() < 4) {
            return "****";
        }
        return "************" + cardNumber.substring(cardNumber.length() - 4);
    }
}
