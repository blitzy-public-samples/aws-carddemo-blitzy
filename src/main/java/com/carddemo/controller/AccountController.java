/*
 * AccountController.java
 * 
 * CardDemo Application - Account Management REST API Controller
 * 
 * REST controller implementing account management endpoints for the CardDemo credit card
 * management application. This controller transforms COBOL CICS online transaction programs
 * COACTVWC.cbl (transaction CAVW - Account View) and COACTUPC.cbl (transaction CAUP - 
 * Account Update) to modern RESTful web service architecture.
 * 
 * Original COBOL Programs:
 * - app/cbl/COACTVWC.cbl: Account view and retrieval functionality
 * - app/cbl/COACTUPC.cbl: Account update and credit limit modification
 * 
 * BMS Mapsets Replaced:
 * - app/bms/COACTVWM.bms: Account view screen (3270 terminal display)
 * - app/bms/COACTUPM.bms: Account update screen (3270 terminal form)
 * 
 * Key Transformations from Mainframe to Cloud-Native:
 * 
 * 1. Transaction Processing:
 *    - CICS Transaction CAVW → GET /api/accounts/{id} REST endpoint
 *    - CICS Transaction CAUP → PUT /api/accounts/{id} REST endpoint
 *    - CICS COMMAREA state management → Stateless HTTP with JWT authentication
 *    - CICS SYNCPOINT commit → @Transactional method completion in service layer
 * 
 * 2. Data Access Pattern:
 *    - EXEC CICS READ DATASET('ACCTDAT') → AccountRepository.findByAccountId()
 *    - EXEC CICS REWRITE DATASET('ACCTDAT') → AccountRepository.save()
 *    - VSAM KSDS indexed file access → JPA entity with @Id primary key
 * 
 * 3. Screen Interaction:
 *    - BMS SEND MAP operations → JSON ResponseEntity with HTTP status codes
 *    - BMS RECEIVE MAP operations → @RequestBody parameter with Bean Validation
 *    - 3270 terminal screen flow → RESTful resource-oriented API design
 * 
 * 4. Security:
 *    - RACF transaction security → Spring Security @PreAuthorize annotations
 *    - CICS user ID validation → JWT token authentication and role-based access control
 * 
 * REST API Endpoints:
 * 
 * GET /api/accounts/{id}
 *   Purpose: Retrieve complete account details by account ID
 *   Security: @PreAuthorize("hasAnyRole('ADMIN', 'USER')")
 *   Authorization: Users can only view their own accounts; admins can view any account
 *   Success Response: HTTP 200 OK with AccountResponse JSON body
 *   Error Responses:
 *     - 404 Not Found: Account does not exist
 *     - 403 Forbidden: User not authorized to view this account
 *     - 401 Unauthorized: Invalid or missing JWT token
 *     - 500 Internal Server Error: Unexpected system error
 * 
 * PUT /api/accounts/{id}
 *   Purpose: Update account credit limits and active status
 *   Security: @PreAuthorize("hasAnyRole('ADMIN', 'USER')")
 *   Authorization: Users can only update their own accounts; admins can update any account
 *   Request Body: AccountUpdateRequest with validated fields (creditLimit, cashCreditLimit, activeStatus)
 *   Success Response: HTTP 200 OK with updated AccountResponse JSON body
 *   Error Responses:
 *     - 400 Bad Request: Validation errors (invalid credit limits, out of range values)
 *     - 404 Not Found: Account does not exist
 *     - 403 Forbidden: User not authorized to update this account
 *     - 409 Conflict: Optimistic locking failure (concurrent modification detected)
 *     - 401 Unauthorized: Invalid or missing JWT token
 *     - 500 Internal Server Error: Unexpected system error
 * 
 * COBOL Business Logic Preservation:
 * 
 * All business rules from the original COBOL programs are maintained through delegation
 * to service layer components:
 * 
 * - Account retrieval validation (COACTVWC.cbl lines 789-850)
 * - Credit limit validation: $1,000 minimum, $999,999,999.99 maximum (COACTUPC.cbl)
 * - Cash credit limit must not exceed total credit limit
 * - Active status must be 'Y' or 'N' only
 * - Optimistic locking prevents concurrent modification conflicts
 * - BigDecimal precision matching COBOL COMP-3 PIC S9(10)V99 arithmetic
 * 
 * Error Handling Strategy:
 * 
 * All exceptions are handled by the GlobalExceptionHandler (@ControllerAdvice) which
 * provides consistent RESTful error responses with appropriate HTTP status codes:
 * 
 * - ResourceNotFoundException → HTTP 404 Not Found
 * - ValidationException → HTTP 400 Bad Request with field-level error details
 * - AccessDeniedException → HTTP 403 Forbidden
 * - OptimisticLockException → HTTP 409 Conflict
 * - AuthenticationException → HTTP 401 Unauthorized
 * - All other exceptions → HTTP 500 Internal Server Error
 * 
 * Performance Considerations:
 * 
 * - Stateless design enables horizontal scaling
 * - JPA repository layer provides database connection pooling
 * - Response time target: < 200ms at 95th percentile (matching mainframe SLA)
 * - Supports minimum 150 concurrent users (mainframe capacity requirement)
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

import com.carddemo.dto.request.AccountUpdateRequest;
import com.carddemo.dto.response.AccountResponse;
import com.carddemo.service.account.AccountUpdateService;
import com.carddemo.service.account.AccountViewService;
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
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * REST API Controller for Account Management Operations.
 * 
 * <p>This controller class implements the web service layer for account management functionality,
 * transforming mainframe CICS transaction programs to modern RESTful API endpoints. It provides
 * stateless HTTP request processing with JWT token-based authentication, replacing the original
 * CICS pseudo-conversational processing pattern that used COMMAREA for state management.</p>
 * 
 * <h2>COBOL Program Transformation Summary</h2>
 * 
 * <table border="1">
 *   <caption>Mainframe to Cloud-Native Mapping</caption>
 *   <tr>
 *     <th>COBOL Program</th>
 *     <th>Transaction ID</th>
 *     <th>REST Endpoint</th>
 *     <th>HTTP Method</th>
 *     <th>Service Delegate</th>
 *   </tr>
 *   <tr>
 *     <td>COACTVWC.cbl</td>
 *     <td>CAVW</td>
 *     <td>/api/accounts/{id}</td>
 *     <td>GET</td>
 *     <td>AccountViewService.getAccountById()</td>
 *   </tr>
 *   <tr>
 *     <td>COACTUPC.cbl</td>
 *     <td>CAUP</td>
 *     <td>/api/accounts/{id}</td>
 *     <td>PUT</td>
 *     <td>AccountUpdateService.updateAccount()</td>
 *   </tr>
 * </table>
 * 
 * <h2>Security Model</h2>
 * 
 * <p>This controller implements role-based access control (RBAC) matching the original RACF
 * security model from the mainframe environment:</p>
 * 
 * <ul>
 *   <li><strong>Authentication:</strong> JWT Bearer token required for all endpoints</li>
 *   <li><strong>Authorization:</strong> Both 'ADMIN' and 'USER' roles can access endpoints</li>
 *   <li><strong>Data Access Control:</strong>
 *     <ul>
 *       <li>Regular users (ROLE_USER) can only view/update their own accounts</li>
 *       <li>Admin users (ROLE_ADMIN) can view/update any account</li>
 *       <li>Authorization logic enforced in service layer by comparing JWT user context with account ownership</li>
 *     </ul>
 *   </li>
 * </ul>
 * 
 * <h2>Request/Response Flow</h2>
 * 
 * <pre>
 * Client Request → Spring Security Filter Chain (JWT validation)
 *                → @PreAuthorize role check (ADMIN or USER)
 *                → Controller method (parameter binding and validation)
 *                → Service layer (business logic and authorization)
 *                → Repository layer (JPA database access)
 *                → Response mapping (Entity to DTO)
 *                → JSON serialization
 *                → HTTP Response to client
 * </pre>
 * 
 * <h2>Error Handling</h2>
 * 
 * <p>All exceptions are intercepted by the GlobalExceptionHandler (@ControllerAdvice) which
 * provides standardized error responses with appropriate HTTP status codes and error details.
 * This replaces COBOL error handling patterns (88-level conditions, RESP codes) with modern
 * exception-based error management.</p>
 * 
 * <h2>Validation</h2>
 * 
 * <p>The @Valid annotation on @RequestBody parameters triggers automatic Bean Validation
 * using Jakarta Validation annotations defined in the AccountUpdateRequest DTO. This replaces
 * COBOL WORKING-STORAGE validation logic with declarative constraint annotations:</p>
 * 
 * <ul>
 *   <li>@NotNull: Field is required (replaces COBOL mandatory field checks)</li>
 *   <li>@DecimalMin/@DecimalMax: Range validation (replaces COBOL 88-level VALUE clauses)</li>
 *   <li>@Digits(integer=10, fraction=2): Precision validation (matches COBOL PIC S9(10)V99)</li>
 *   <li>@Pattern: Pattern matching (replaces COBOL alphanumeric validation)</li>
 * </ul>
 * 
 * <p>Validation failures trigger MethodArgumentNotValidException, which is caught by
 * GlobalExceptionHandler and returned as HTTP 400 Bad Request with field-level error details.</p>
 * 
 * <h2>Performance Characteristics</h2>
 * 
 * <ul>
 *   <li><strong>Target Response Time:</strong> &lt; 200ms at 95th percentile (matching mainframe SLA)</li>
 *   <li><strong>Concurrent Users:</strong> Supports minimum 150 concurrent users</li>
 *   <li><strong>Scalability:</strong> Stateless design enables horizontal scaling</li>
 *   <li><strong>Connection Pooling:</strong> HikariCP connection pool managed by Spring Boot</li>
 * </ul>
 * 
 * @see AccountViewService for account retrieval business logic
 * @see AccountUpdateService for account update business logic with transactional boundaries
 * @see AccountResponse for complete account data transfer object structure
 * @see AccountUpdateRequest for account update request validation rules
 * 
 * @author CardDemo Migration Team
 * @version 1.0
 * @since 1.0
 */
@RestController
@RequestMapping("/api/accounts")
@RequiredArgsConstructor
@Slf4j
@Tag(
    name = "Account Management", 
    description = "Account management operations including account retrieval and credit limit updates. "
        + "Replaces mainframe CICS transactions CAVW (Account View from COACTVWC.cbl) and CAUP "
        + "(Account Update from COACTUPC.cbl) with RESTful web service endpoints. All endpoints "
        + "require JWT authentication and enforce role-based access control matching the original "
        + "RACF security model. Regular users can only access their own accounts, while admin users "
        + "can access any account."
)
public class AccountController {

    /**
     * Service component for account retrieval operations.
     * 
     * <p>Transforms COBOL COACTVWC.cbl PROCEDURE DIVISION logic to Java service layer,
     * replacing VSAM KSDS READ operations on ACCTDAT file with JPA repository queries.</p>
     */
    private final AccountViewService accountViewService;

    /**
     * Service component for account update operations.
     * 
     * <p>Transforms COBOL COACTUPC.cbl PROCEDURE DIVISION logic to Java service layer,
     * replacing VSAM KSDS REWRITE operations on ACCTDAT file with JPA repository save
     * operations within @Transactional boundaries ensuring ACID properties.</p>
     */
    private final AccountUpdateService accountUpdateService;

    /**
     * Retrieve account details by account ID.
     * 
     * <p>This endpoint transforms the mainframe CICS transaction CAVW (program COACTVWC.cbl)
     * to a RESTful GET operation. It retrieves complete account information including:</p>
     * 
     * <ul>
     *   <li>Account identification (account ID, status)</li>
     *   <li>Financial details (current balance, credit limits, cycle balances)</li>
     *   <li>Date information (open date, expiration date, reissue date)</li>
     *   <li>Reference data (address zip, group ID)</li>
     * </ul>
     * 
     * <h3>COBOL Source Mapping</h3>
     * <pre>
     * COBOL Program: app/cbl/COACTVWC.cbl
     * Transaction: CAVW
     * BMS Mapset: COACTVWM.bms
     * Key Operation: EXEC CICS READ DATASET('ACCTDAT') INTO(ACCOUNT-RECORD) RIDFLD(ACCT-ID)
     * Java Equivalent: accountRepository.findByAccountId(accountId)
     * </pre>
     * 
     * <h3>COBOL PROCEDURE DIVISION Transformation</h3>
     * <p>The following COBOL paragraphs are transformed to Java service layer methods:</p>
     * <ul>
     *   <li><strong>9300-GETACCTDATA-BYACCT</strong> (lines 789-807)
     *       <br>→ AccountViewService.getAccountById()
     *       <br>Retrieves account record from VSAM ACCTDAT file using account ID as key
     *   </li>
     *   <li><strong>9400-GETCUSTDATA-BYCUST</strong> (lines 839-850)
     *       <br>→ Account.getCustomer() via JPA @ManyToOne relationship
     *       <br>Retrieves associated customer information via foreign key
     *   </li>
     * </ul>
     * 
     * <h3>Security and Authorization</h3>
     * <ul>
     *   <li>Requires valid JWT Bearer token in Authorization header</li>
     *   <li>@PreAuthorize annotation checks for ADMIN or USER role</li>
     *   <li>Service layer enforces data access control:
     *     <ul>
     *       <li>Regular users (ROLE_USER) can only view accounts they own</li>
     *       <li>Admin users (ROLE_ADMIN) can view any account</li>
     *       <li>Ownership determined by comparing JWT user context with account's customer ID</li>
     *     </ul>
     *   </li>
     * </ul>
     * 
     * <h3>Response Codes</h3>
     * <ul>
     *   <li><strong>200 OK:</strong> Account found and successfully retrieved</li>
     *   <li><strong>401 Unauthorized:</strong> Missing or invalid JWT token</li>
     *   <li><strong>403 Forbidden:</strong> User not authorized to view this account</li>
     *   <li><strong>404 Not Found:</strong> Account does not exist
     *       <br>Corresponds to COBOL RESP-CD 13 (NOTFND) from COACTVWC.cbl lines 794-796
     *   </li>
     *   <li><strong>500 Internal Server Error:</strong> Unexpected system error</li>
     * </ul>
     * 
     * <h3>Example Request</h3>
     * <pre>
     * GET /api/accounts/11111111111 HTTP/1.1
     * Host: api.carddemo.example.com
     * Authorization: Bearer eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9...
     * Accept: application/json
     * </pre>
     * 
     * <h3>Example Success Response (HTTP 200 OK)</h3>
     * <pre>
     * {
     *   "accountId": 11111111111,
     *   "activeStatus": "Y",
     *   "currentBalance": "25000.50",
     *   "creditLimit": "50000.00",
     *   "cashCreditLimit": "10000.00",
     *   "openDate": "2020-01-15",
     *   "expirationDate": "2025-01-31",
     *   "reissueDate": "2024-11-01",
     *   "currentCycleCredit": "1500.75",
     *   "currentCycleDebit": "2300.25",
     *   "addressZip": "10001",
     *   "groupId": "GROUP001"
     * }
     * </pre>
     * 
     * <h3>Example Error Response (HTTP 404 Not Found)</h3>
     * <pre>
     * {
     *   "timestamp": "2024-01-15T10:30:45.123Z",
     *   "status": 404,
     *   "error": "Not Found",
     *   "message": "Account not found with ID: 11111111111",
     *   "path": "/api/accounts/11111111111"
     * }
     * </pre>
     * 
     * @param accountId the 11-digit account identifier (primary key from VSAM ACCTDAT file)
     *                  Maps to COBOL field ACCT-ID PIC 9(11) from CVACT01Y.cpy copybook
     * @return ResponseEntity containing AccountResponse with complete account details and HTTP 200 OK status
     * @throws com.carddemo.exception.ResourceNotFoundException if account not found (returns HTTP 404)
     * @throws com.carddemo.exception.AccessDeniedException if user not authorized (returns HTTP 403)
     */
    @Operation(
        summary = "Retrieve account details by account ID",
        description = "Retrieves complete account information including financial details, dates, and status. "
            + "Replaces mainframe CICS transaction CAVW (program COACTVWC.cbl) which performed VSAM KSDS READ "
            + "operations on the ACCTDAT file. Regular users can only view their own accounts; admin users can "
            + "view any account. Returns HTTP 404 if account does not exist, HTTP 403 if user not authorized.",
        security = @SecurityRequirement(name = "bearerAuth")
    )
    @ApiResponses(value = {
        @ApiResponse(
            responseCode = "200",
            description = "Account successfully retrieved with complete details",
            content = @Content(
                mediaType = "application/json",
                schema = @Schema(implementation = AccountResponse.class)
            )
        ),
        @ApiResponse(
            responseCode = "401",
            description = "Unauthorized - Invalid or missing JWT Bearer token in Authorization header",
            content = @Content(mediaType = "application/json")
        ),
        @ApiResponse(
            responseCode = "403",
            description = "Forbidden - User not authorized to view this account. Regular users can only view "
                + "accounts they own; admin users can view any account.",
            content = @Content(mediaType = "application/json")
        ),
        @ApiResponse(
            responseCode = "404",
            description = "Not Found - Account does not exist with specified account ID. Corresponds to COBOL "
                + "RESP-CD 13 (NOTFND) error handling from COACTVWC.cbl lines 794-796.",
            content = @Content(mediaType = "application/json")
        ),
        @ApiResponse(
            responseCode = "500",
            description = "Internal Server Error - Unexpected system error during account retrieval",
            content = @Content(mediaType = "application/json")
        )
    })
    @GetMapping("/{id}")
    @PreAuthorize("hasAnyRole('ADMIN', 'USER')")
    public ResponseEntity<AccountResponse> getAccountById(
        @Parameter(
            description = "11-digit account identifier (primary key). Maps to COBOL field ACCT-ID PIC 9(11) "
                + "from CVACT01Y.cpy copybook. Must be a positive long integer value between 1 and 99999999999.",
            required = true,
            example = "11111111111"
        )
        @PathVariable("id") Long accountId
    ) {
        // Log the incoming request for audit trail and debugging
        // Matches COBOL logging pattern from COACTVWC.cbl working storage section
        log.info("Received GET request for account ID: {}", accountId);
        log.debug("Invoking AccountViewService.getAccountById() to retrieve account details");
        
        // Delegate to service layer for business logic execution
        // This replaces COBOL PROCEDURE DIVISION paragraph 9300-GETACCTDATA-BYACCT
        // which performed EXEC CICS READ DATASET('ACCTDAT') operation
        // Service layer handles:
        // - Account retrieval from database via JPA repository
        // - Authorization check (user can only view own accounts unless admin)
        // - Customer data retrieval via foreign key relationship
        // - Entity to DTO mapping with proper BigDecimal precision
        AccountResponse accountResponse = accountViewService.getAccountById(accountId);
        
        // Log successful retrieval for audit trail
        log.info("Successfully retrieved account ID: {} with status: {}", 
                 accountResponse.getAccountId(), 
                 accountResponse.getActiveStatus());
        log.debug("Account details: balance={}, creditLimit={}, cashCreditLimit={}", 
                  accountResponse.getCurrentBalance(),
                  accountResponse.getCreditLimit(),
                  accountResponse.getCashCreditLimit());
        
        // Return successful response with HTTP 200 OK status
        // Replaces COBOL EXEC CICS SEND MAP operation from COACTVWC.cbl
        // JSON serialization automatically handles BigDecimal to string conversion
        // preventing precision loss in JavaScript clients
        return ResponseEntity.ok(accountResponse);
    }

    /**
     * Update account credit limits and active status.
     * 
     * <p>This endpoint transforms the mainframe CICS transaction CAUP (program COACTUPC.cbl)
     * to a RESTful PUT operation. It updates account financial parameters with comprehensive
     * validation and optimistic locking for concurrency control.</p>
     * 
     * <h3>COBOL Source Mapping</h3>
     * <pre>
     * COBOL Program: app/cbl/COACTUPC.cbl
     * Transaction: CAUP
     * BMS Mapset: COACTUPM.bms
     * Key Operations:
     *   1. EXEC CICS READ DATASET('ACCTDAT') INTO(ACCOUNT-RECORD) RIDFLD(ACCT-ID)
     *   2. Validation logic in WORKING-STORAGE section
     *   3. EXEC CICS REWRITE DATASET('ACCTDAT') FROM(ACCOUNT-RECORD)
     *   4. EXEC CICS SYNCPOINT (commit transaction)
     * Java Equivalent:
     *   1. accountRepository.findByAccountId(accountId)
     *   2. Bean Validation framework + service layer validation
     *   3. accountRepository.save(account)
     *   4. @Transactional method completion (automatic commit)
     * </pre>
     * 
     * <h3>Updatable Fields</h3>
     * <ul>
     *   <li><strong>creditLimit</strong> (BigDecimal with scale=2)
     *     <ul>
     *       <li>Maps to COBOL: ACCT-CREDIT-LIMIT PIC S9(10)V99 COMP-3</li>
     *       <li>Validation: $1,000.00 minimum, $999,999,999.99 maximum</li>
     *       <li>COBOL constraint: 88 LIMIT-VALID VALUE 1000 THRU 999999999</li>
     *     </ul>
     *   </li>
     *   <li><strong>cashCreditLimit</strong> (BigDecimal with scale=2)
     *     <ul>
     *       <li>Maps to COBOL: ACCT-CASH-CREDIT-LIMIT PIC S9(10)V99 COMP-3</li>
     *       <li>Validation: Must not exceed creditLimit, can be $0.00</li>
     *     </ul>
     *   </li>
     *   <li><strong>activeStatus</strong> (String, single character)
     *     <ul>
     *       <li>Maps to COBOL: ACCT-ACTIVE-STATUS PIC X(01)</li>
     *       <li>Validation: Must be 'Y' (active) or 'N' (inactive)</li>
     *       <li>COBOL constraint: 88 FLG-YES-NO-ISVALID VALUES 'Y', 'N'</li>
     *     </ul>
     *   </li>
     * </ul>
     * 
     * <h3>Validation Rules</h3>
     * <p>All validation constraints replicate COBOL WORKING-STORAGE validation logic:</p>
     * <ol>
     *   <li><strong>Field-Level Validation</strong> (Bean Validation annotations)
     *     <ul>
     *       <li>@NotNull: All fields required</li>
     *       <li>@DecimalMin("1000.00"): Credit limit minimum $1,000</li>
     *       <li>@DecimalMax("999999999.99"): Credit limit maximum $999,999,999.99</li>
     *       <li>@Digits(integer=10, fraction=2): Precision matching COBOL PIC S9(10)V99</li>
     *       <li>@Pattern(regexp="^[YN]$"): Active status Y or N only</li>
     *     </ul>
     *   </li>
     *   <li><strong>Business Rule Validation</strong> (Service layer logic)
     *     <ul>
     *       <li>Cash credit limit must not exceed total credit limit</li>
     *       <li>At least one field must be changed (NO-CHANGES-DETECTED check)</li>
     *       <li>Account must exist (RESP-CD 13 NOTFND check)</li>
     *     </ul>
     *   </li>
     * </ol>
     * 
     * <h3>Concurrency Control</h3>
     * <p>Optimistic locking with @Version annotation prevents concurrent modification conflicts:</p>
     * <ul>
     *   <li>Each account entity has version field incremented on every update</li>
     *   <li>If version mismatch detected, OptimisticLockException thrown</li>
     *   <li>Maps to COBOL error: DATA-WAS-CHANGED-BEFORE-UPDATE</li>
     *   <li>Client receives HTTP 409 Conflict and must retry with latest data</li>
     * </ul>
     * 
     * <h3>Transaction Management</h3>
     * <p>@Transactional annotation in service layer ensures ACID properties:</p>
     * <ul>
     *   <li><strong>Atomicity:</strong> All database operations succeed or fail together</li>
     *   <li><strong>Consistency:</strong> Account data remains valid before and after update</li>
     *   <li><strong>Isolation:</strong> Concurrent transactions don't interfere</li>
     *   <li><strong>Durability:</strong> Committed changes persist across system failures</li>
     * </ul>
     * <p>This matches COBOL CICS SYNCPOINT behavior from COACTUPC.cbl</p>
     * 
     * <h3>Security and Authorization</h3>
     * <ul>
     *   <li>Requires valid JWT Bearer token in Authorization header</li>
     *   <li>@PreAuthorize annotation checks for ADMIN or USER role</li>
     *   <li>Service layer enforces data access control:
     *     <ul>
     *       <li>Regular users (ROLE_USER) can only update accounts they own</li>
     *       <li>Admin users (ROLE_ADMIN) can update any account</li>
     *       <li>Ownership determined by comparing JWT user context with account's customer ID</li>
     *     </ul>
     *   </li>
     * </ul>
     * 
     * <h3>Response Codes</h3>
     * <ul>
     *   <li><strong>200 OK:</strong> Account successfully updated, returns updated account data</li>
     *   <li><strong>400 Bad Request:</strong> Validation errors
     *     <ul>
     *       <li>Invalid credit limit (below $1,000 or above $999,999,999.99)</li>
     *       <li>Cash credit limit exceeds total credit limit</li>
     *       <li>Active status not 'Y' or 'N'</li>
     *       <li>No fields changed (NO-CHANGES-DETECTED)</li>
     *       <li>Invalid numeric format or precision</li>
     *     </ul>
     *   </li>
     *   <li><strong>401 Unauthorized:</strong> Missing or invalid JWT token</li>
     *   <li><strong>403 Forbidden:</strong> User not authorized to update this account</li>
     *   <li><strong>404 Not Found:</strong> Account does not exist
     *       <br>Corresponds to COBOL RESP-CD 13 (NOTFND)
     *   </li>
     *   <li><strong>409 Conflict:</strong> Optimistic locking failure
     *       <br>Corresponds to COBOL DATA-WAS-CHANGED-BEFORE-UPDATE
     *       <br>Client must refetch latest data and retry
     *   </li>
     *   <li><strong>500 Internal Server Error:</strong> Unexpected system error</li>
     * </ul>
     * 
     * <h3>Example Request</h3>
     * <pre>
     * PUT /api/accounts/11111111111 HTTP/1.1
     * Host: api.carddemo.example.com
     * Authorization: Bearer eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9...
     * Content-Type: application/json
     * Accept: application/json
     * 
     * {
     *   "accountId": 11111111111,
     *   "creditLimit": "75000.00",
     *   "cashCreditLimit": "15000.00",
     *   "activeStatus": "Y"
     * }
     * </pre>
     * 
     * <h3>Example Success Response (HTTP 200 OK)</h3>
     * <pre>
     * {
     *   "accountId": 11111111111,
     *   "activeStatus": "Y",
     *   "currentBalance": "25000.50",
     *   "creditLimit": "75000.00",
     *   "cashCreditLimit": "15000.00",
     *   "openDate": "2020-01-15",
     *   "expirationDate": "2025-01-31",
     *   "reissueDate": "2024-11-01",
     *   "currentCycleCredit": "1500.75",
     *   "currentCycleDebit": "2300.25",
     *   "addressZip": "10001",
     *   "groupId": "GROUP001"
     * }
     * </pre>
     * 
     * <h3>Example Error Response (HTTP 400 Bad Request)</h3>
     * <pre>
     * {
     *   "timestamp": "2024-01-15T10:30:45.123Z",
     *   "status": 400,
     *   "error": "Bad Request",
     *   "message": "Validation failed",
     *   "fieldErrors": [
     *     {
     *       "field": "creditLimit",
     *       "rejectedValue": "500.00",
     *       "message": "Credit limit must be at least $1,000.00"
     *     },
     *     {
     *       "field": "cashCreditLimit",
     *       "rejectedValue": "80000.00",
     *       "message": "Cash credit limit cannot exceed total credit limit"
     *     }
     *   ],
     *   "path": "/api/accounts/11111111111"
     * }
     * </pre>
     * 
     * @param accountId the 11-digit account identifier (primary key from VSAM ACCTDAT file)
     *                  Maps to COBOL field ACCT-ID PIC 9(11) from CVACT01Y.cpy copybook
     * @param request the validated account update request containing creditLimit, cashCreditLimit, and activeStatus
     *                Replaces COBOL BMS map input fields from COACTUPM.bms
     * @return ResponseEntity containing updated AccountResponse with HTTP 200 OK status
     * @throws com.carddemo.exception.ResourceNotFoundException if account not found (returns HTTP 404)
     * @throws com.carddemo.exception.ValidationException if validation fails (returns HTTP 400)
     * @throws com.carddemo.exception.AccessDeniedException if user not authorized (returns HTTP 403)
     * @throws jakarta.persistence.OptimisticLockException if concurrent modification detected (returns HTTP 409)
     */
    @Operation(
        summary = "Update account credit limits and active status",
        description = "Updates account financial parameters including credit limit, cash credit limit, and active "
            + "status with comprehensive validation. Replaces mainframe CICS transaction CAUP (program COACTUPC.cbl) "
            + "which performed VSAM KSDS REWRITE operations on the ACCTDAT file within CICS SYNCPOINT transaction "
            + "boundaries. All updates execute within @Transactional scope ensuring ACID properties. Regular users "
            + "can only update their own accounts; admin users can update any account. Implements optimistic locking "
            + "to prevent concurrent modification conflicts. Returns HTTP 400 for validation errors, HTTP 404 if "
            + "account not found, HTTP 403 if not authorized, HTTP 409 for optimistic locking failures.",
        security = @SecurityRequirement(name = "bearerAuth")
    )
    @ApiResponses(value = {
        @ApiResponse(
            responseCode = "200",
            description = "Account successfully updated with validated credit limits and status. Returns complete "
                + "updated account details including all financial information, dates, and reference data.",
            content = @Content(
                mediaType = "application/json",
                schema = @Schema(implementation = AccountResponse.class)
            )
        ),
        @ApiResponse(
            responseCode = "400",
            description = "Bad Request - Validation errors in request body. Possible causes: credit limit below "
                + "$1,000 minimum or above $999,999,999.99 maximum (COBOL: 88 LIMIT-VALID VALUE 1000 THRU 999999999), "
                + "cash credit limit exceeds total credit limit, active status not 'Y' or 'N' (COBOL: 88 FLG-YES-NO-ISVALID), "
                + "no fields changed (COBOL: NO-CHANGES-DETECTED), invalid BigDecimal precision (must match PIC S9(10)V99).",
            content = @Content(mediaType = "application/json")
        ),
        @ApiResponse(
            responseCode = "401",
            description = "Unauthorized - Invalid or missing JWT Bearer token in Authorization header",
            content = @Content(mediaType = "application/json")
        ),
        @ApiResponse(
            responseCode = "403",
            description = "Forbidden - User not authorized to update this account. Regular users can only update "
                + "accounts they own; admin users can update any account. Authorization enforced by comparing JWT "
                + "user context with account's customer ID in service layer.",
            content = @Content(mediaType = "application/json")
        ),
        @ApiResponse(
            responseCode = "404",
            description = "Not Found - Account does not exist with specified account ID. Corresponds to COBOL "
                + "RESP-CD 13 (NOTFND) error handling from COACTUPC.cbl.",
            content = @Content(mediaType = "application/json")
        ),
        @ApiResponse(
            responseCode = "409",
            description = "Conflict - Optimistic locking failure detected. Another user modified this account "
                + "concurrently. Client must refetch latest account data and retry the update operation. Corresponds "
                + "to COBOL error condition DATA-WAS-CHANGED-BEFORE-UPDATE.",
            content = @Content(mediaType = "application/json")
        ),
        @ApiResponse(
            responseCode = "500",
            description = "Internal Server Error - Unexpected system error during account update transaction. "
                + "Transaction automatically rolled back preserving data integrity.",
            content = @Content(mediaType = "application/json")
        )
    })
    @PutMapping("/{id}")
    @PreAuthorize("hasAnyRole('ADMIN', 'USER')")
    public ResponseEntity<AccountResponse> updateAccount(
        @Parameter(
            description = "11-digit account identifier (primary key). Maps to COBOL field ACCT-ID PIC 9(11) "
                + "from CVACT01Y.cpy copybook. Must match accountId in request body. Must be a positive long "
                + "integer value between 1 and 99999999999.",
            required = true,
            example = "11111111111"
        )
        @PathVariable("id") Long accountId,
        
        @Parameter(
            description = "Account update request containing validated credit limits and active status. All fields "
                + "are required and must satisfy Bean Validation constraints: creditLimit between $1,000.00 and "
                + "$999,999,999.99 (matching COBOL 88 LIMIT-VALID), cashCreditLimit must not exceed creditLimit, "
                + "activeStatus must be 'Y' or 'N' (matching COBOL 88 FLG-YES-NO-ISVALID), BigDecimal precision "
                + "must be exactly 10 integer digits and 2 decimal places (matching COBOL PIC S9(10)V99 COMP-3).",
            required = true
        )
        @Valid @RequestBody AccountUpdateRequest request
    ) {
        // Log the incoming update request for audit trail and debugging
        // Matches COBOL logging pattern from COACTUPC.cbl working storage section
        log.info("Received PUT request to update account ID: {}", accountId);
        log.debug("Update request details: creditLimit={}, cashCreditLimit={}, activeStatus={}", 
                  request.getCreditLimit(), 
                  request.getCashCreditLimit(),
                  request.getActiveStatus());
        
        // Validate that path parameter accountId matches request body accountId
        // This prevents accidental mismatches where path differs from body
        // Matches COBOL input validation pattern from WORKING-STORAGE section
        if (request.getAccountId() != null && !request.getAccountId().equals(accountId)) {
            log.warn("Account ID mismatch: path={}, body={}", accountId, request.getAccountId());
            throw new IllegalArgumentException(
                "Account ID in path parameter (" + accountId + ") does not match "
                + "account ID in request body (" + request.getAccountId() + ")"
            );
        }
        
        // Delegate to service layer for business logic execution
        // This replaces COBOL PROCEDURE DIVISION logic from COACTUPC.cbl including:
        // - READ operation to fetch current account data
        // - WORKING-STORAGE validation logic for credit limits and status
        // - Business rule validation (cash limit <= credit limit, change detection)
        // - Authorization check (user can only update own accounts unless admin)
        // - REWRITE operation to update account data
        // - SYNCPOINT commit (automatic via @Transactional)
        // All operations execute atomically within Spring transaction boundaries
        AccountResponse updatedAccount = accountUpdateService.updateAccount(accountId, request);
        
        // Log successful update for audit trail
        log.info("Successfully updated account ID: {} with new credit limit: {}, cash credit limit: {}, status: {}", 
                 updatedAccount.getAccountId(),
                 updatedAccount.getCreditLimit(),
                 updatedAccount.getCashCreditLimit(),
                 updatedAccount.getActiveStatus());
        log.debug("Updated account balance: {}, cycle credit: {}, cycle debit: {}",
                  updatedAccount.getCurrentBalance(),
                  updatedAccount.getCurrentCycleCredit(),
                  updatedAccount.getCurrentCycleDebit());
        
        // Return successful response with HTTP 200 OK status
        // Replaces COBOL EXEC CICS SEND MAP operation from COACTUPC.cbl
        // Updated account data returned to client for immediate display
        // JSON serialization automatically handles BigDecimal to string conversion
        // preventing precision loss in JavaScript clients
        return ResponseEntity.ok(updatedAccount);
    }
}
