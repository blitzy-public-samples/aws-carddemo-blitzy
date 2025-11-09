/*
 * BillingController.java
 * 
 * REST API Controller for Bill Payment Processing
 * 
 * This controller transforms CICS transaction CB00 (COBIL00C.cbl) to a modern
 * RESTful API endpoint, replacing CICS pseudo-conversational BMS screen processing
 * with stateless HTTP POST operation for bill payment functionality.
 * 
 * COBOL Source Mapping:
 * - Source Program: COBIL00C.cbl (Bill Payment - Pay account balance in full)
 * - Transaction ID: CB00
 * - BMS Mapset: COBIL00M.bms (Bill Payment Screen)
 * - CICS Transaction: Pseudo-conversational with COMMAREA state management
 * 
 * Key Transformations:
 * - EXEC CICS RECEIVE MAP('COBIL0A') → @RequestBody JSON deserialization
 * - EXEC CICS SEND MAP('COBIL0A') → ResponseEntity<PaymentResponse> JSON response
 * - DFHCOMMAREA state preservation → Stateless HTTP with JWT authentication
 * - PERFORM PROCESS-ENTER-KEY → Delegate to BillPaymentService.processBillPayment()
 * - CICS RESP/RESP2 error handling → Exception-based error handling with HTTP status codes
 * - CICS transaction security → @PreAuthorize role-based security
 * 
 * Transaction Flow Comparison:
 * 
 * COBOL (COBIL00C.cbl):
 * 1. RECEIVE MAP with account ID and confirmation flag (lines 306-314)
 * 2. Validate input fields (lines 159-191)
 * 3. Read account with UPDATE lock (lines 177-195)
 * 4. Check balance > 0 (lines 198-205)
 * 5. Process payment if confirmed (lines 210-235)
 * 6. SEND MAP with result message (lines 289-301)
 * 7. RETURN TRANSID for pseudo-conversational processing (lines 146-149)
 * 
 * Java Spring Boot:
 * 1. Accept PaymentRequest JSON via POST /api/billing/payment
 * 2. Automatic Bean Validation on PaymentRequest (@Valid annotation)
 * 3. Delegate to BillPaymentService.processBillPayment()
 * 4. Service handles transaction within @Transactional boundary
 * 5. Return PaymentResponse JSON with HTTP 200 OK
 * 6. Exception handling via GlobalExceptionHandler for errors
 * 7. Stateless HTTP - no session state carryover
 * 
 * Security Model:
 * - COBOL: RACF security on CICS transaction CB00
 * - Java: Spring Security @PreAuthorize with JWT token authentication
 * - Access: Both ADMIN and USER roles allowed (matching RACF profile)
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

import com.carddemo.dto.request.PaymentRequest;
import com.carddemo.dto.response.PaymentResponse;
import com.carddemo.service.billing.BillPaymentService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * REST Controller for Bill Payment Processing Operations
 * <p>
 * This controller provides a RESTful API endpoint for processing bill payments on credit card
 * accounts, replacing the CICS transaction CB00 (COBIL00C.cbl) with modern HTTP-based stateless
 * operation. The controller serves as the HTTP boundary layer, delegating all business logic
 * to the BillPaymentService for proper separation of concerns.
 * </p>
 * 
 * <h2>Endpoint Summary</h2>
 * <table border="1">
 *   <tr>
 *     <th>HTTP Method</th>
 *     <th>Endpoint Path</th>
 *     <th>COBOL Transaction</th>
 *     <th>Security</th>
 *   </tr>
 *   <tr>
 *     <td>POST</td>
 *     <td>/api/billing/payment</td>
 *     <td>CB00 (COBIL00C)</td>
 *     <td>ADMIN, USER</td>
 *   </tr>
 * </table>
 * 
 * <h2>Request/Response Flow</h2>
 * <ol>
 *   <li>Client sends POST /api/billing/payment with PaymentRequest JSON body</li>
 *   <li>JWT authentication filter validates token and sets SecurityContext</li>
 *   <li>@PreAuthorize validates user has ADMIN or USER role</li>
 *   <li>@Valid triggers Jakarta Bean Validation on PaymentRequest fields</li>
 *   <li>Controller delegates to BillPaymentService.processBillPayment()</li>
 *   <li>Service executes payment within @Transactional boundary</li>
 *   <li>Controller returns ResponseEntity<PaymentResponse> with HTTP 200</li>
 *   <li>On error, GlobalExceptionHandler converts exception to HTTP error response</li>
 * </ol>
 * 
 * <h2>Error Handling</h2>
 * <p>
 * All exceptions are caught and handled by GlobalExceptionHandler, which transforms
 * them into structured JSON error responses with appropriate HTTP status codes:
 * </p>
 * <ul>
 *   <li><b>HTTP 400 Bad Request</b>: Bean Validation errors (invalid amount, missing fields)</li>
 *   <li><b>HTTP 401 Unauthorized</b>: Missing or invalid JWT token</li>
 *   <li><b>HTTP 403 Forbidden</b>: User lacks required role (ADMIN or USER)</li>
 *   <li><b>HTTP 404 Not Found</b>: Account ID does not exist in system</li>
 *   <li><b>HTTP 409 Conflict</b>: Business logic errors (zero balance, insufficient balance)</li>
 *   <li><b>HTTP 500 Internal Server Error</b>: Unexpected system errors, database failures</li>
 * </ul>
 * 
 * <h2>COBOL Comparison</h2>
 * <pre>
 * COBOL (COBIL00C.cbl)                     Java Spring Boot (BillingController)
 * ====================                     =====================================
 * PROCEDURE DIVISION                    →  @RestController class
 * MAIN-PARA                             →  processPayment() method
 * RECEIVE-BILLPAY-SCREEN (306-314)      →  @RequestBody PaymentRequest
 * PROCESS-ENTER-KEY (154-244)           →  billPaymentService.processBillPayment()
 * SEND-BILLPAY-SCREEN (289-301)         →  ResponseEntity<PaymentResponse>
 * WS-ERR-FLG validation (lines 139-141) →  Bean Validation (@Valid annotation)
 * RETURN TRANSID COMMAREA (146-149)     →  No session state (stateless HTTP)
 * CICS security check                   →  @PreAuthorize("hasAnyRole('ADMIN', 'USER')")
 * </pre>
 * 
 * <h2>Stateless Design</h2>
 * <p>
 * Unlike the COBOL CICS pseudo-conversational approach with COMMAREA state carryover,
 * this controller implements true stateless HTTP processing:
 * </p>
 * <ul>
 *   <li>No session state maintained between requests</li>
 *   <li>Each POST /api/billing/payment is independent and atomic</li>
 *   <li>User context derived from JWT token in Authorization header</li>
 *   <li>No confirmation flag handling - single POST completes payment</li>
 *   <li>Payment confirmation logic handled by client-side UI prompts</li>
 * </ul>
 * 
 * <h2>Performance Characteristics</h2>
 * <ul>
 *   <li>Target response time: < 200ms at 95th percentile (matching CICS baseline)</li>
 *   <li>Supports concurrent payment processing via database row locking</li>
 *   <li>Thread-safe stateless design enables horizontal scaling</li>
 *   <li>Connection pooling optimizes database resource usage</li>
 * </ul>
 * 
 * @see BillPaymentService
 * @see PaymentRequest
 * @see PaymentResponse
 * @author CardDemo Development Team
 * @version 1.0
 * @since 1.0
 */
@RestController
@RequestMapping("/api/billing")
@RequiredArgsConstructor
@Slf4j
@Tag(
    name = "Billing", 
    description = "Bill payment processing operations for credit card accounts. " +
                  "Replaces CICS transaction CB00 (COBIL00C.cbl) with RESTful API for processing " +
                  "account balance payments, creating payment transaction records, and updating " +
                  "account balances with ACID transaction guarantees."
)
public class BillingController {

    /**
     * Bill Payment Service for business logic execution
     * <p>
     * This service handles all payment processing logic within @Transactional boundaries,
     * including account validation, balance checking, transaction creation, and account updates.
     * The controller delegates all business operations to this service, maintaining clean
     * separation between HTTP concerns (request/response handling) and business logic.
     * </p>
     * 
     * <p>
     * Constructor injection via @RequiredArgsConstructor ensures immutability and
     * supports Spring's dependency injection pattern.
     * </p>
     * 
     * @see BillPaymentService#processBillPayment(PaymentRequest)
     */
    private final BillPaymentService billPaymentService;

    /**
     * Process Bill Payment for Credit Card Account
     * <p>
     * This endpoint processes a bill payment request, paying the full or partial balance
     * on a credit card account. It replicates the functionality of CICS transaction CB00
     * (COBIL00C.cbl) PROCESS-ENTER-KEY paragraph with stateless HTTP operation.
     * </p>
     * 
     * <h3>COBOL Source Mapping</h3>
     * <pre>
     * COBOL Program: COBIL00C.cbl
     * Paragraph: PROCESS-ENTER-KEY (lines 154-244)
     * Operations:
     *   1. Validate account ID not empty (lines 159-164)
     *   2. Parse and validate confirmation flag (lines 173-191)
     *   3. Read account with UPDATE lock (lines 177, 345-372)
     *   4. Check balance > 0 (lines 198-205)
     *   5. If confirmed, create payment transaction (lines 210-235)
     *   6. Update account balance (line 234)
     *   7. Save changes and return success message (lines 289-301)
     * </pre>
     * 
     * <h3>Request Processing</h3>
     * <ol>
     *   <li><b>Authentication</b>: JWT token validated by Spring Security filter chain</li>
     *   <li><b>Authorization</b>: @PreAuthorize checks user has ADMIN or USER role</li>
     *   <li><b>Validation</b>: @Valid triggers Bean Validation on PaymentRequest
     *       <ul>
     *         <li>accountId: Not null, valid Long</li>
     *         <li>amount: Not null, positive, minimum $0.01, maximum 9 integer + 2 decimal digits</li>
     *         <li>paymentReference: Optional, max 200 characters</li>
     *       </ul>
     *   </li>
     *   <li><b>Business Logic</b>: BillPaymentService.processBillPayment() executes:
     *       <ul>
     *         <li>Account existence verification</li>
     *         <li>Balance validation (must be > 0)</li>
     *         <li>Payment amount vs balance check</li>
     *         <li>Transaction record creation with sequential ID</li>
     *         <li>Account balance reduction with BigDecimal precision</li>
     *         <li>Atomic commit of all changes</li>
     *       </ul>
     *   </li>
     *   <li><b>Response</b>: PaymentResponse JSON with transaction ID and updated balance</li>
     * </ol>
     * 
     * <h3>Success Response (HTTP 200 OK)</h3>
     * <pre>
     * {
     *   "transactionId": "0000000000000123",
     *   "accountId": 12345678901,
     *   "paymentAmount": 150.75,
     *   "previousBalance": 1500.75,
     *   "newBalance": 1350.00,
     *   "paymentDate": "2024-11-06T14:30:00",
     *   "confirmationMessage": "Payment processed successfully"
     * }
     * </pre>
     * 
     * <h3>Error Responses</h3>
     * <p>
     * All error responses handled by GlobalExceptionHandler with structured JSON format:
     * </p>
     * 
     * <h4>HTTP 400 Bad Request - Validation Errors</h4>
     * <pre>
     * {
     *   "timestamp": "2024-11-06T14:30:00",
     *   "status": 400,
     *   "error": "Bad Request",
     *   "message": "Validation failed: Payment amount must be positive",
     *   "path": "/api/billing/payment",
     *   "fieldErrors": {
     *     "amount": "Payment amount must be positive"
     *   }
     * }
     * </pre>
     * <p>Triggered by Bean Validation failures (MethodArgumentNotValidException)</p>
     * <p>COBOL equivalent: WS-ERR-FLG = 'Y' with validation message (lines 160-164, 186-190)</p>
     * 
     * <h4>HTTP 404 Not Found - Account Not Found</h4>
     * <pre>
     * {
     *   "timestamp": "2024-11-06T14:30:00",
     *   "status": 404,
     *   "error": "Not Found",
     *   "message": "Account not found: 12345678901",
     *   "path": "/api/billing/payment"
     * }
     * </pre>
     * <p>Thrown when account ID does not exist (ResourceNotFoundException)</p>
     * <p>COBOL equivalent: DFHRESP(NOTFND) on READ ACCTDAT (lines 359-364)</p>
     * 
     * <h4>HTTP 409 Conflict - Business Logic Error</h4>
     * <pre>
     * {
     *   "timestamp": "2024-11-06T14:30:00",
     *   "status": 409,
     *   "error": "Conflict",
     *   "message": "Account balance is zero. Nothing to pay.",
     *   "path": "/api/billing/payment"
     * }
     * </pre>
     * <p>Thrown for business rule violations (BusinessLogicException)</p>
     * <p>COBOL equivalent: Balance check "You have nothing to pay..." (lines 198-205)</p>
     * 
     * <h4>HTTP 401 Unauthorized - Authentication Failure</h4>
     * <pre>
     * {
     *   "timestamp": "2024-11-06T14:30:00",
     *   "status": 401,
     *   "error": "Unauthorized",
     *   "message": "Invalid or expired JWT token",
     *   "path": "/api/billing/payment"
     * }
     * </pre>
     * <p>Returned when JWT token is missing, invalid, or expired</p>
     * <p>COBOL equivalent: CICS security validation before transaction execution</p>
     * 
     * <h4>HTTP 403 Forbidden - Authorization Failure</h4>
     * <pre>
     * {
     *   "timestamp": "2024-11-06T14:30:00",
     *   "status": 403,
     *   "error": "Forbidden",
     *   "message": "Access denied. Insufficient privileges.",
     *   "path": "/api/billing/payment"
     * }
     * </pre>
     * <p>Returned when user lacks ADMIN or USER role</p>
     * <p>COBOL equivalent: RACF security check on CICS transaction CB00</p>
     * 
     * <h4>HTTP 500 Internal Server Error - System Error</h4>
     * <pre>
     * {
     *   "timestamp": "2024-11-06T14:30:00",
     *   "status": 500,
     *   "error": "Internal Server Error",
     *   "message": "Unable to process payment. Please try again.",
     *   "path": "/api/billing/payment"
     * }
     * </pre>
     * <p>Returned for unexpected runtime errors, database failures, network issues</p>
     * <p>COBOL equivalent: RESP(OTHER) error handling (lines 366-371, 397-402)</p>
     * 
     * <h3>Security</h3>
     * <ul>
     *   <li><b>Authentication</b>: JWT token required in Authorization header (Bearer scheme)</li>
     *   <li><b>Authorization</b>: User must have ADMIN or USER role</li>
     *   <li><b>Matching COBOL</b>: CB00 transaction accessible to both admin and regular users</li>
     * </ul>
     * 
     * <h3>Transaction Atomicity</h3>
     * <p>
     * All database operations execute within @Transactional boundary in BillPaymentService:
     * </p>
     * <ul>
     *   <li>Read account with PESSIMISTIC_WRITE lock (prevents concurrent modifications)</li>
     *   <li>Create payment transaction record</li>
     *   <li>Update account balance</li>
     *   <li>Commit all changes together or rollback on any exception</li>
     * </ul>
     * <p>
     * Matches CICS SYNCPOINT behavior ensuring ACID properties for payment processing.
     * COBOL equivalent: Implicit CICS transaction boundaries (lines 146-149)
     * </p>
     * 
     * <h3>Performance</h3>
     * <ul>
     *   <li>Target response time: < 200ms at 95th percentile</li>
     *   <li>Database row locking prevents lost update anomaly</li>
     *   <li>Connection pooling optimizes resource usage</li>
     *   <li>Stateless design enables horizontal scaling</li>
     * </ul>
     * 
     * <h3>Example Usage</h3>
     * <pre>
     * curl -X POST http://localhost:8080/api/billing/payment \
     *   -H "Authorization: Bearer eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9..." \
     *   -H "Content-Type: application/json" \
     *   -d '{
     *     "accountId": 12345678901,
     *     "amount": 150.75,
     *     "paymentReference": "Payment for November statement"
     *   }'
     * </pre>
     * 
     * @param request Payment request containing account ID, payment amount, and optional reference
     * @return ResponseEntity containing PaymentResponse with transaction details and updated balance
     * @throws ResourceNotFoundException if account ID does not exist (HTTP 404)
     * @throws BusinessLogicException if business rules violated (HTTP 409)
     * @throws MethodArgumentNotValidException if request validation fails (HTTP 400)
     * @see PaymentRequest
     * @see PaymentResponse
     * @see BillPaymentService#processBillPayment(PaymentRequest)
     */
    @PostMapping(
        value = "/payment",
        consumes = MediaType.APPLICATION_JSON_VALUE,
        produces = MediaType.APPLICATION_JSON_VALUE
    )
    @PreAuthorize("hasAnyRole('ADMIN', 'USER')")
    @Operation(
        summary = "Process Bill Payment",
        description = "Processes a bill payment request for a credit card account, paying the " +
                      "specified amount and updating the account balance. Replaces CICS transaction " +
                      "CB00 (COBIL00C.cbl) with stateless RESTful operation. Creates a payment " +
                      "transaction record and reduces account balance within atomic @Transactional " +
                      "boundary ensuring ACID properties. Requires ADMIN or USER role.",
        tags = {"Billing"}
    )
    @ApiResponses(value = {
        @ApiResponse(
            responseCode = "200",
            description = "Payment processed successfully. Returns transaction ID, payment amount, " +
                          "previous balance, new balance, and confirmation message.",
            content = @Content(
                mediaType = "application/json",
                schema = @Schema(implementation = PaymentResponse.class)
            )
        ),
        @ApiResponse(
            responseCode = "400",
            description = "Bad Request - Validation errors in payment request. Occurs when:\n" +
                          "- accountId is null or empty\n" +
                          "- amount is null, zero, negative, or exceeds precision limits\n" +
                          "- paymentReference exceeds 200 characters\n" +
                          "Returns structured error response with field-level validation messages.",
            content = @Content(mediaType = "application/json")
        ),
        @ApiResponse(
            responseCode = "401",
            description = "Unauthorized - Missing, invalid, or expired JWT token. Client must " +
                          "authenticate via POST /api/auth/login to obtain valid JWT token.",
            content = @Content(mediaType = "application/json")
        ),
        @ApiResponse(
            responseCode = "403",
            description = "Forbidden - User lacks required role. Payment processing requires " +
                          "ADMIN or USER role. Regular users can process payments on their own " +
                          "accounts; admins can process payments on any account.",
            content = @Content(mediaType = "application/json")
        ),
        @ApiResponse(
            responseCode = "404",
            description = "Not Found - Account ID does not exist in the system. Verify account " +
                          "ID and retry. COBOL equivalent: DFHRESP(NOTFND) on READ ACCTDAT.",
            content = @Content(mediaType = "application/json")
        ),
        @ApiResponse(
            responseCode = "409",
            description = "Conflict - Business logic error preventing payment processing. Occurs when:\n" +
                          "- Account balance is zero (nothing to pay)\n" +
                          "- Payment amount exceeds account balance\n" +
                          "- Account status is inactive or closed\n" +
                          "- Card associated with account not found\n" +
                          "Returns error message with specific business rule violation details.",
            content = @Content(mediaType = "application/json")
        ),
        @ApiResponse(
            responseCode = "500",
            description = "Internal Server Error - Unexpected system error during payment processing. " +
                          "Includes database failures, network errors, or runtime exceptions. " +
                          "All database changes are automatically rolled back on error. " +
                          "Client should retry request or contact support if error persists.",
            content = @Content(mediaType = "application/json")
        )
    })
    public ResponseEntity<PaymentResponse> processPayment(
        @Parameter(
            description = "Payment request containing account ID, payment amount, and optional " +
                          "payment reference. All fields validated via Jakarta Bean Validation:\n" +
                          "- accountId: Required, 11-digit account identifier\n" +
                          "- amount: Required, positive BigDecimal with min $0.01, max 9 integer + 2 decimal digits\n" +
                          "- paymentReference: Optional, max 200 characters for audit trail",
            required = true,
            schema = @Schema(implementation = PaymentRequest.class)
        )
        @Valid 
        @RequestBody 
        PaymentRequest request
    ) {
        log.info("Received bill payment request for account ID: {}, amount: {}", 
                 request.getAccountId(), 
                 request.getAmount());

        // Delegate all business logic to service layer
        // Service handles:
        // 1. Payment request validation (account exists, balance > 0)
        // 2. Transaction creation with sequential ID generation
        // 3. Account balance update with BigDecimal precision
        // 4. Atomic commit within @Transactional boundary
        // 
        // COBOL equivalent: PERFORM PROCESS-ENTER-KEY (lines 154-244)
        PaymentResponse response = billPaymentService.processBillPayment(request);

        log.info("Bill payment processed successfully for account ID: {}, transaction ID: {}, new balance: {}", 
                 request.getAccountId(), 
                 response.getTransactionId(), 
                 response.getUpdatedBalance());

        // Return HTTP 200 OK with PaymentResponse JSON body
        // COBOL equivalent: SEND-BILLPAY-SCREEN with success message (lines 289-301)
        return ResponseEntity
            .status(HttpStatus.OK)
            .body(response);
    }
}
