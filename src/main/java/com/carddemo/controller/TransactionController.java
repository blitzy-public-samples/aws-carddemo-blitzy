/*
 * TransactionController.java
 * 
 * REST API controller for transaction management operations replacing CICS
 * transactions CT00 (transaction list from COTRN00C.cbl), CT01 (transaction
 * detail view from COTRN01C.cbl), and CT02 (transaction add/validate from
 * COTRN02C.cbl).
 * 
 * Exposes three primary endpoints:
 * - GET /api/transactions: List transactions with pagination and filtering
 * - GET /api/transactions/{id}: Retrieve single transaction detail
 * - POST /api/transactions: Create new transaction with authorization
 * 
 * Transforms CICS pseudo-conversational transaction processing to stateless
 * RESTful HTTP operations with Spring Security JWT authentication replacing
 * RACF security controls.
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

import com.carddemo.dto.request.TransactionRequest;
import com.carddemo.dto.response.TransactionResponse;
import com.carddemo.service.transaction.TransactionAddService;
import com.carddemo.service.transaction.TransactionListService;
import com.carddemo.service.transaction.TransactionViewService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * REST Controller for credit card transaction operations.
 * 
 * <p>This controller implements RESTful endpoints for transaction management,
 * replacing three mainframe CICS COBOL programs with modern Spring Boot REST APIs:</p>
 * 
 * <h2>COBOL Program Transformations</h2>
 * <table border="1" cellpadding="5">
 *   <tr>
 *     <th>CICS Transaction</th>
 *     <th>COBOL Program</th>
 *     <th>REST Endpoint</th>
 *     <th>HTTP Method</th>
 *     <th>Operation</th>
 *   </tr>
 *   <tr>
 *     <td>CT00</td>
 *     <td>COTRN00C.cbl</td>
 *     <td>/api/transactions</td>
 *     <td>GET</td>
 *     <td>List transactions with pagination (10 per page)</td>
 *   </tr>
 *   <tr>
 *     <td>CT01</td>
 *     <td>COTRN01C.cbl</td>
 *     <td>/api/transactions/{id}</td>
 *     <td>GET</td>
 *     <td>View transaction detail</td>
 *   </tr>
 *   <tr>
 *     <td>CT02</td>
 *     <td>COTRN02C.cbl</td>
 *     <td>/api/transactions</td>
 *     <td>POST</td>
 *     <td>Create new transaction with authorization</td>
 *   </tr>
 * </table>
 * 
 * <h2>BMS Mapset Replacements</h2>
 * <ul>
 *   <li><b>COTRN00M.bms</b>: Transaction list screen with PF7/PF8 pagination 
 *       → GET /api/transactions with query parameters ?page=0&size=10</li>
 *   <li><b>COTRN01M.bms</b>: Transaction detail view screen
 *       → GET /api/transactions/{id}</li>
 *   <li><b>COTRN02M.bms</b>: Transaction add screen with field validation
 *       → POST /api/transactions with @Valid TransactionRequest</li>
 * </ul>
 * 
 * <h2>VSAM File Operations Replaced</h2>
 * <ul>
 *   <li><b>EXEC CICS STARTBR TRANSACT</b> → TransactionRepository with pagination</li>
 *   <li><b>EXEC CICS READNEXT TRANSACT</b> → Spring Data JPA query methods</li>
 *   <li><b>EXEC CICS READ TRANSACT</b> → TransactionRepository.findById()</li>
 *   <li><b>EXEC CICS WRITE TRANSACT</b> → TransactionRepository.save()</li>
 * </ul>
 * 
 * <h2>Security Model</h2>
 * <p>All endpoints require authentication with JWT Bearer token replacing RACF security.
 * Both ADMIN and USER roles can perform all transaction operations with ownership
 * verification ensuring users can only view transactions for their own cards/accounts.</p>
 * 
 * <h2>Pagination Strategy</h2>
 * <p>Transaction listing defaults to 10 transactions per page matching the original
 * BMS COTRN00M.bms screen display. The COBOL pseudo-conversational pagination state
 * (CDEMO-CT00-PAGE-NUM, CDEMO-CT00-TRNID-FIRST, CDEMO-CT00-TRNID-LAST stored in
 * COMMAREA) is replaced with stateless HTTP pagination using Spring Data Pageable
 * where the client manages pagination state via query parameters.</p>
 * 
 * <h2>Error Handling</h2>
 * <p>All exceptions are centrally handled by GlobalExceptionHandler returning
 * structured JSON error responses with appropriate HTTP status codes:</p>
 * <ul>
 *   <li>404 Not Found: Transaction not found</li>
 *   <li>400 Bad Request: Validation errors (invalid amount, card number format)</li>
 *   <li>403 Forbidden: Unauthorized access to transactions</li>
 *   <li>409 Conflict: Duplicate transaction detection</li>
 *   <li>500 Internal Server Error: Unexpected processing errors</li>
 * </ul>
 * 
 * @author CardDemo Migration Team
 * @version 1.0
 * @since 2024-01-01
 * 
 * @see TransactionListService
 * @see TransactionViewService
 * @see TransactionAddService
 * @see TransactionRequest
 * @see TransactionResponse
 */
@Slf4j
@RestController
@RequestMapping("/api/transactions")
@RequiredArgsConstructor
@Tag(name = "Transaction Management", description = "Credit card transaction operations including list, view, and creation with authorization")
public class TransactionController {

    private final TransactionListService transactionListService;
    private final TransactionViewService transactionViewService;
    private final TransactionAddService transactionAddService;

    /**
     * Lists credit card transactions with pagination and optional filtering.
     * 
     * <p>This endpoint replaces CICS transaction CT00 (COTRN00C.cbl) which displayed
     * a paginated list of transactions using VSAM STARTBR/READNEXT operations.
     * The COBOL program maintained pagination state in COMMAREA (CDEMO-CT00-PAGE-NUM,
     * CDEMO-CT00-TRNID-FIRST, CDEMO-CT00-TRNID-LAST) in a pseudo-conversational pattern,
     * while this REST API uses stateless HTTP pagination with query parameters.</p>
     * 
     * <h3>COBOL Source Mapping</h3>
     * <pre>
     * COTRN00C.cbl lines 100-150: Transaction browse logic
     * - EXEC CICS STARTBR TRANSACT FILE → Spring Data JPA query with Pageable
     * - PERFORM VARYING loop for 10 transactions → Page size default of 10
     * - PF7/PF8 key navigation → ?page=N query parameter
     * - CDEMO-CT00-NEXT-PAGE-FLG → Page.hasNext() in response metadata
     * </pre>
     * 
     * <h3>BMS Mapset Replacement</h3>
     * <p>COTRN00M.bms transaction list screen (10 transaction lines per screen)
     * is replaced by this paginated endpoint returning JSON array of TransactionResponse
     * objects consumed by React TransactionListComponent.jsx.</p>
     * 
     * <h3>Filtering Capabilities</h3>
     * <ul>
     *   <li><b>cardNumber</b>: Filter transactions by 16-digit card number</li>
     *   <li><b>accountId</b>: Filter transactions by account ID</li>
     *   <li><b>dateFrom/dateTo</b>: Date range filter (inclusive) using transaction origination timestamp</li>
     * </ul>
     * 
     * <h3>Pagination Parameters</h3>
     * <ul>
     *   <li><b>page</b>: Zero-based page number (default: 0)</li>
     *   <li><b>size</b>: Number of transactions per page (default: 10, matching BMS screen)</li>
     *   <li><b>sort</b>: Sort criteria (default: originationTimestamp,desc for newest first)</li>
     * </ul>
     * 
     * <h3>Security and Authorization</h3>
     * <p>Regular users can only view transactions for cards/accounts they own.
     * The service layer validates ownership by comparing the transaction's card's
     * account's customer ID against the authenticated user's customer ID extracted
     * from JWT token. Admin users can view all transactions without ownership restrictions.</p>
     * 
     * <h3>Response Structure</h3>
     * <p>Returns Spring Data Page object containing:</p>
     * <ul>
     *   <li><b>content</b>: Array of TransactionResponse objects</li>
     *   <li><b>pageable</b>: Pagination information (page number, size)</li>
     *   <li><b>totalElements</b>: Total number of transactions matching filters</li>
     *   <li><b>totalPages</b>: Total number of pages</li>
     *   <li><b>first</b>: Boolean indicating if this is the first page</li>
     *   <li><b>last</b>: Boolean indicating if this is the last page</li>
     * </ul>
     * 
     * @param pageable Pagination parameters (page, size, sort) automatically resolved
     *                 from query parameters. Defaults to page=0, size=10,
     *                 sort=originationTimestamp,desc
     * @param cardNumber Optional 16-digit card number filter
     * @param startDate Optional start date for date range filter (inclusive), format yyyy-MM-dd
     * @param endDate Optional end date for date range filter (inclusive), format yyyy-MM-dd
     * @param accountId Optional account ID filter for retrieving all transactions on an account
     * @return ResponseEntity containing Page of TransactionResponse objects with HTTP 200 OK
     */
    @Operation(
        summary = "List credit card transactions",
        description = "Retrieves paginated list of credit card transactions with optional filtering by card number, date range, and account ID. " +
                      "Replaces CICS transaction CT00 (COTRN00C.cbl) VSAM browse operations with Spring Data JPA queries. " +
                      "Default page size is 10 transactions matching original BMS screen display.",
        security = @SecurityRequirement(name = "bearer-jwt")
    )
    @ApiResponses(value = {
        @ApiResponse(
            responseCode = "200",
            description = "Transactions retrieved successfully. Returns paginated list of transactions with metadata.",
            content = @Content(
                mediaType = "application/json",
                schema = @Schema(implementation = Page.class)
            )
        ),
        @ApiResponse(
            responseCode = "400",
            description = "Invalid request parameters. Common errors include invalid date format (must be yyyy-MM-dd), " +
                          "invalid card number format (must be 16 digits), or invalid pagination parameters (negative page number).",
            content = @Content(mediaType = "application/json")
        ),
        @ApiResponse(
            responseCode = "401",
            description = "Unauthorized. JWT token is missing, expired, or invalid. User must authenticate to access transaction data.",
            content = @Content(mediaType = "application/json")
        ),
        @ApiResponse(
            responseCode = "403",
            description = "Forbidden. User attempting to access transactions for cards/accounts they don't own. " +
                          "Regular users can only view their own transactions; admin users can view all transactions.",
            content = @Content(mediaType = "application/json")
        )
    })
    @GetMapping
    @PreAuthorize("hasAnyRole('ADMIN', 'USER')")
    public ResponseEntity<Page<TransactionResponse>> listTransactions(
            @PageableDefault(size = 10, sort = "originationTimestamp", direction = Sort.Direction.DESC)
            @Parameter(
                description = "Pagination and sorting parameters. Use page for page number (0-based), " +
                              "size for transactions per page (default 10), and sort for ordering (default originationTimestamp,desc). " +
                              "Example: ?page=0&size=10&sort=originationTimestamp,desc",
                example = "page=0&size=10&sort=originationTimestamp,desc"
            )
            Pageable pageable,
            
            @RequestParam(required = false)
            @Parameter(
                description = "Optional filter by card number. Must be exactly 16 numeric digits. " +
                              "When provided, returns only transactions for the specified card. " +
                              "Maps to COBOL TRAN-CARD-NUM PIC X(16) field filter.",
                example = "4111111111111111"
            )
            String cardNumber,
            
            @RequestParam(required = false)
            @DateTimeFormat(pattern = "yyyy-MM-dd")
            @Parameter(
                description = "Optional start date for date range filter (inclusive). Format: yyyy-MM-dd. " +
                              "Filters transactions by originationTimestamp >= startDate. " +
                              "Commonly used with endDate to retrieve transactions for a specific period (e.g., monthly statements).",
                example = "2024-01-01"
            )
            LocalDate startDate,
            
            @RequestParam(required = false)
            @DateTimeFormat(pattern = "yyyy-MM-dd")
            @Parameter(
                description = "Optional end date for date range filter (inclusive). Format: yyyy-MM-dd. " +
                              "Filters transactions by originationTimestamp <= endDate. " +
                              "Used with startDate to define a specific reporting period.",
                example = "2024-01-31"
            )
            LocalDate endDate,
            
            @RequestParam(required = false)
            @Parameter(
                description = "Optional filter by transaction type code. Valid values: '01' (purchase), '02' (cash advance), " +
                              "'03' (payment), '04' (refund), '05' (fee). Maps to COBOL TRAN-TYPE-CD field filter.",
                example = "01"
            )
            String transactionType,
            
            @RequestParam(required = false)
            @Parameter(
                description = "Optional merchant name search (case-insensitive partial match). Example: 'WALMART' matches " +
                              "'WALMART SUPERCENTER', 'Walmart Store', etc.",
                example = "WALMART"
            )
            String merchantName,
            
            @RequestParam(required = false)
            @Parameter(
                description = "Optional minimum transaction amount filter (inclusive). Uses BigDecimal for exact precision. " +
                              "Example: 10.00 for transactions >= $10.00",
                example = "10.00"
            )
            BigDecimal minAmount,
            
            @RequestParam(required = false)
            @Parameter(
                description = "Optional maximum transaction amount filter (inclusive). Must be >= minAmount if both provided. " +
                              "Example: 500.00 for transactions <= $500.00",
                example = "500.00"
            )
            BigDecimal maxAmount
    ) {
        log.info("Received transaction list request: page={}, size={}, cardNumber={}, startDate={}, endDate={}, " +
                 "transactionType={}, merchantName={}, minAmount={}, maxAmount={}",
                pageable.getPageNumber(), pageable.getPageSize(), 
                cardNumber != null ? maskCardNumber(cardNumber) : "null",
                startDate, endDate, transactionType, merchantName, minAmount, maxAmount);
        
        Page<TransactionResponse> transactions = transactionListService.listTransactions(
                pageable, cardNumber, startDate, endDate, transactionType, merchantName, minAmount, maxAmount);
        
        log.info("Returning {} transactions (page {} of {})",
                transactions.getNumberOfElements(),
                transactions.getNumber() + 1,
                transactions.getTotalPages());
        
        return ResponseEntity.ok(transactions);
    }

    /**
     * Retrieves detailed information for a single transaction by transaction ID.
     * 
     * <p>This endpoint replaces CICS transaction CT01 (COTRN01C.cbl) which displayed
     * full transaction details using VSAM READ TRANSACT operation with transaction ID
     * as the primary key. The COBOL program retrieved the transaction record and
     * performed table lookups for transaction type and category descriptions.</p>
     * 
     * <h3>COBOL Source Mapping</h3>
     * <pre>
     * COTRN01C.cbl lines 100-200: Transaction detail retrieval logic
     * - EXEC CICS READ TRANSACT RIDFLD(TRAN-ID) → TransactionRepository.findById()
     * - MOVE TRAN-RECORD TO screen fields → TransactionResponse DTO mapping
     * - Type/category table lookups → JPA entity relationships with eager fetch
     * </pre>
     * 
     * <h3>BMS Mapset Replacement</h3>
     * <p>COTRN01M.bms transaction detail screen displaying all transaction fields
     * is replaced by JSON response consumed by React TransactionViewComponent.jsx.</p>
     * 
     * <h3>Response Data Enrichment</h3>
     * <p>The service layer enriches the transaction data with related information:</p>
     * <ul>
     *   <li><b>Card Information</b>: Card number (masked), card type, expiration date</li>
     *   <li><b>Account Information</b>: Account ID, account status, current balance</li>
     *   <li><b>Merchant Details</b>: Merchant name, city, state, ZIP code</li>
     *   <li><b>Category/Type Descriptions</b>: Human-readable transaction type and category names</li>
     * </ul>
     * 
     * <h3>Transaction ID Format</h3>
     * <p>Transaction IDs are 16-character alphanumeric strings matching COBOL
     * TRAN-ID PIC X(16) field definition. Example: "0000000000000001", "TXN2024010112345"</p>
     * 
     * <h3>Security and Authorization</h3>
     * <p>Regular users can only view transactions for cards/accounts they own.
     * The service layer validates ownership before returning transaction details.
     * Admin users can view any transaction without ownership restrictions.</p>
     * 
     * @param transactionId Unique 16-character transaction identifier serving as primary key
     * @return ResponseEntity containing TransactionResponse with HTTP 200 OK
     * @throws com.carddemo.exception.ResourceNotFoundException if transaction not found (HTTP 404)
     * @throws com.carddemo.exception.BusinessLogicException if access denied for ownership (HTTP 403)
     */
    @Operation(
        summary = "Get transaction details",
        description = "Retrieves complete details for a single transaction by transaction ID. " +
                      "Replaces CICS transaction CT01 (COTRN01C.cbl) VSAM READ operation. " +
                      "Returns enriched transaction data including card, account, and merchant information.",
        security = @SecurityRequirement(name = "bearer-jwt")
    )
    @ApiResponses(value = {
        @ApiResponse(
            responseCode = "200",
            description = "Transaction found and returned successfully with complete details including merchant information, " +
                          "card number (masked), transaction type/category, timestamps, and amounts.",
            content = @Content(
                mediaType = "application/json",
                schema = @Schema(implementation = TransactionResponse.class)
            )
        ),
        @ApiResponse(
            responseCode = "400",
            description = "Invalid transaction ID format. Transaction ID must be exactly 16 characters alphanumeric. " +
                          "Example valid ID: 0000000000000001",
            content = @Content(mediaType = "application/json")
        ),
        @ApiResponse(
            responseCode = "401",
            description = "Unauthorized. JWT token is missing, expired, or invalid. Authentication required to view transaction details.",
            content = @Content(mediaType = "application/json")
        ),
        @ApiResponse(
            responseCode = "403",
            description = "Forbidden. User attempting to view transaction for card/account they don't own. " +
                          "Regular users restricted to own transactions; admin users have full access.",
            content = @Content(mediaType = "application/json")
        ),
        @ApiResponse(
            responseCode = "404",
            description = "Transaction not found. No transaction exists with the specified transaction ID. " +
                          "Verify the transaction ID is correct and the transaction exists in the system.",
            content = @Content(mediaType = "application/json")
        )
    })
    @GetMapping("/{id}")
    @PreAuthorize("hasAnyRole('ADMIN', 'USER')")
    public ResponseEntity<TransactionResponse> getTransaction(
            @PathVariable("id")
            @Parameter(
                description = "Unique 16-character transaction identifier. Maps to COBOL TRAN-ID PIC X(16) field. " +
                              "Must match a valid transaction in the system. Example: 0000000000000001",
                example = "0000000000000001",
                required = true
            )
            String transactionId
    ) {
        log.info("Received transaction detail request for transactionId: {}", transactionId);
        
        TransactionResponse transaction = transactionViewService.getTransactionDetail(transactionId);
        
        log.info("Returning transaction detail for transactionId: {}, amount: {}, merchantName: {}",
                transaction.getTransactionId(),
                transaction.getAmount(),
                transaction.getMerchantName());
        
        return ResponseEntity.ok(transaction);
    }

    /**
     * Creates a new credit card transaction with comprehensive validation and authorization.
     * 
     * <p>This endpoint replaces CICS transaction CT02 (COTRN02C.cbl) which validated
     * and recorded new transactions using VSAM WRITE TRANSACT operation with
     * simultaneous account balance updates. The COBOL program performed card
     * authorization validation, available credit checks, and multi-file updates
     * within a CICS SYNCPOINT transaction boundary.</p>
     * 
     * <h3>COBOL Source Mapping</h3>
     * <pre>
     * COTRN02C.cbl lines 100-500: Transaction add and validation logic
     * - VALIDATE-INPUT-KEY-FIELDS paragraph → Bean Validation + validateCardAndAccount()
     * - VALIDATE-INPUT-DATA-FIELDS paragraph → validateAmount(), validateMerchant()
     * - VERIFY-AVAILABLE-CREDIT paragraph → BigDecimal arithmetic matching COMP-3 precision
     * - ADD-TRANSACTION paragraph → TransactionRepository.save()
     * - UPDATE-ACCOUNT-BALANCE paragraph → AccountRepository.save()
     * - EXEC CICS SYNCPOINT → @Transactional method boundary with automatic commit/rollback
     * </pre>
     * 
     * <h3>BMS Mapset Replacement</h3>
     * <p>COTRN02M.bms transaction add screen with field validation is replaced
     * by JSON request body with Jakarta Bean Validation annotations (@NotNull,
     * @Positive, @Digits, @Pattern) providing automatic field-level validation
     * before service method invocation.</p>
     * 
     * <h3>Transaction Processing Flow</h3>
     * <p>The service layer executes the following atomic operations within
     * @Transactional boundary ensuring ACID properties matching CICS SYNCPOINT:</p>
     * <ol>
     *   <li><b>Card Validation</b>: Verify card exists, is active, and not expired</li>
     *   <li><b>Account Retrieval</b>: Lookup associated account via foreign key relationship</li>
     *   <li><b>Credit Validation</b>: Calculate newBalance = currentBalance + amount,
     *       verify newBalance <= creditLimit using exact BigDecimal arithmetic</li>
     *   <li><b>Merchant Validation</b>: Verify merchant ID exists and is active</li>
     *   <li><b>Transaction ID Generation</b>: Generate unique 16-character transaction ID</li>
     *   <li><b>Transaction Record Creation</b>: Save transaction to database</li>
     *   <li><b>Account Balance Update</b>: Update account currentBalance atomically</li>
     *   <li><b>Category Balance Update</b>: Update transaction category balance for analytics</li>
     * </ol>
     * <p>If any step fails, the entire transaction is rolled back automatically,
     * ensuring data integrity equivalent to CICS ROLLBACK behavior.</p>
     * 
     * <h3>COBOL COMP-3 Precision Preservation</h3>
     * <p>Transaction amounts use BigDecimal with scale=2 and RoundingMode.HALF_UP
     * to preserve exact COBOL PIC S9(09)V99 COMP-3 packed decimal precision.
     * This prevents floating-point precision loss in critical financial calculations.</p>
     * 
     * <h3>Validation Rules</h3>
     * <ul>
     *   <li><b>Card Number</b>: Exactly 16 numeric digits, must exist in card table,
     *       card status must be active, card must not be expired</li>
     *   <li><b>Amount</b>: Positive value (> 0), maximum 9 integer digits and 2 decimal places,
     *       must not exceed available credit (creditLimit - currentBalance)</li>
     *   <li><b>Merchant ID</b>: Required, must exist in merchant reference data</li>
     *   <li><b>Type Code</b>: Exactly 2 digits, must exist in transaction_type table</li>
     *   <li><b>Category Code</b>: Exactly 4 digits, must exist in transaction_category table</li>
     * </ul>
     * 
     * <h3>Security and Authorization</h3>
     * <p>Regular users can only create transactions for their own cards.
     * The service layer validates card ownership before processing the transaction.
     * Admin users can create transactions for any card.</p>
     * 
     * @param request TransactionRequest DTO containing all transaction details with
     *                Bean Validation annotations ensuring field-level validation
     * @return ResponseEntity containing created TransactionResponse with HTTP 201 Created
     * @throws com.carddemo.exception.ResourceNotFoundException if card or account not found (HTTP 404)
     * @throws com.carddemo.exception.ValidationException if validation fails (HTTP 400)
     * @throws com.carddemo.exception.BusinessLogicException if insufficient credit or authorization fails (HTTP 400)
     */
    @Operation(
        summary = "Create new transaction",
        description = "Creates a new credit card transaction with comprehensive validation including card authorization, " +
                      "available credit verification, and merchant validation. " +
                      "Replaces CICS transaction CT02 (COTRN02C.cbl) VSAM WRITE operation with @Transactional service ensuring " +
                      "atomic operations matching CICS SYNCPOINT behavior. " +
                      "Uses BigDecimal for exact COBOL COMP-3 monetary precision preservation.",
        security = @SecurityRequirement(name = "bearer-jwt")
    )
    @ApiResponses(value = {
        @ApiResponse(
            responseCode = "201",
            description = "Transaction created successfully. Returns complete transaction details including generated " +
                          "transaction ID, timestamps, and updated account balance. Account balance has been updated atomically.",
            content = @Content(
                mediaType = "application/json",
                schema = @Schema(implementation = TransactionResponse.class)
            )
        ),
        @ApiResponse(
            responseCode = "400",
            description = "Validation failed. Common errors include: " +
                          "invalid card number format (must be 16 digits), " +
                          "invalid amount (must be positive with max 9 integer and 2 decimal digits), " +
                          "insufficient credit limit (amount exceeds available credit), " +
                          "invalid merchant ID, " +
                          "card is inactive or expired, " +
                          "invalid transaction type/category codes.",
            content = @Content(mediaType = "application/json")
        ),
        @ApiResponse(
            responseCode = "401",
            description = "Unauthorized. JWT token is missing, expired, or invalid. Authentication required to create transactions.",
            content = @Content(mediaType = "application/json")
        ),
        @ApiResponse(
            responseCode = "403",
            description = "Forbidden. User attempting to create transaction for card they don't own. " +
                          "Regular users can only create transactions for their own cards; admin users can create for any card.",
            content = @Content(mediaType = "application/json")
        ),
        @ApiResponse(
            responseCode = "404",
            description = "Resource not found. Common causes: " +
                          "card number does not exist in system, " +
                          "merchant ID not found in merchant reference data, " +
                          "transaction type/category code not found in reference tables.",
            content = @Content(mediaType = "application/json")
        ),
        @ApiResponse(
            responseCode = "409",
            description = "Conflict. Duplicate transaction detected. A transaction with identical card number, amount, " +
                          "merchant ID, and timestamp already exists. This prevents accidental double-posting of transactions.",
            content = @Content(mediaType = "application/json")
        )
    })
    @PostMapping
    @PreAuthorize("hasAnyRole('ADMIN', 'USER')")
    public ResponseEntity<TransactionResponse> createTransaction(
            @Valid
            @RequestBody
            @Parameter(
                description = "Transaction request containing card number, amount, merchant details, " +
                              "transaction type/category codes, and optional description. " +
                              "All fields are validated automatically using Jakarta Bean Validation. " +
                              "Amount must be positive with exact BigDecimal precision matching COBOL PIC S9(09)V99 field. " +
                              "Example JSON: " +
                              "{\"cardNumber\": \"4111111111111111\", \"amount\": 125.50, " +
                              "\"merchantId\": 123456789, \"description\": \"Online Purchase\", " +
                              "\"typeCode\": \"01\", \"categoryCode\": \"5732\"}",
                required = true
            )
            TransactionRequest request
    ) {
        log.info("Received transaction creation request for cardNumber: {}, amount: {}, merchantId: {}",
                maskCardNumber(request.getCardNumber()),
                request.getAmount(),
                request.getMerchantId());
        
        TransactionResponse createdTransaction = transactionAddService.createTransaction(request);
        
        log.info("Transaction created successfully with ID: {}, amount: {}, card: {}",
                createdTransaction.getTransactionId(),
                createdTransaction.getAmount(),
                maskCardNumber(createdTransaction.getCardNumber()));
        
        return ResponseEntity.status(HttpStatus.CREATED).body(createdTransaction);
    }

    /**
     * Masks card number for security logging by showing only last 4 digits.
     * 
     * <p>Implements PCI DSS compliance requirement for card data protection in logs.
     * Replaces 16-digit card number with asterisks showing only last 4 digits.</p>
     * 
     * @param cardNumber Full 16-digit card number
     * @return Masked card number (e.g., "************1234")
     */
    private String maskCardNumber(String cardNumber) {
        if (cardNumber == null || cardNumber.length() < 4) {
            return "****";
        }
        return "************" + cardNumber.substring(cardNumber.length() - 4);
    }
}
