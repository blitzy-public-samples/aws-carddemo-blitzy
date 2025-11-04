/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.carddemo.controller;

import com.carddemo.dto.request.BillPaymentRequest;
import com.carddemo.dto.response.BillPaymentResponse;
import com.carddemo.exception.AccountNotFoundException;
import com.carddemo.exception.InsufficientBalanceException;
import com.carddemo.exception.InvalidPayeeException;
import com.carddemo.service.BillPaymentService;
import jakarta.validation.Valid;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * REST controller for bill payment processing functionality.
 * <p>
 * This controller transforms the CICS bill payment transaction program (COBIL00C.cbl)
 * to Spring Boot REST endpoint architecture, exposing POST /api/payments/bill for
 * processing credit card bill payments with multi-step validation including payee validation,
 * payment amount validation, account balance checking, and payment authorization.
 * </p>
 *
 * <p><strong>COBOL Source Program:</strong> app/cbl/COBIL00C.cbl</p>
 * <p><strong>CICS Transaction ID:</strong> CB00</p>
 * <p><strong>BMS Mapset:</strong> COBIL00 (screen COBIL0A)</p>
 *
 * <p><strong>Business Function:</strong></p>
 * <p>Provides online bill payment functionality allowing cardholders to pay their
 * account balance in full or in part. The COBOL program COBIL00C implements the
 * following workflow:</p>
 * <ol>
 *   <li>Accept account ID input from user</li>
 *   <li>Display current account balance</li>
 *   <li>Accept payment confirmation (Y/N) from user</li>
 *   <li>On confirmation:
 *     <ul>
 *       <li>Read account data with UPDATE intent (EXEC CICS READ UPDATE)</li>
 *       <li>Read card cross-reference (CXACAIX file) to get card number</li>
 *       <li>Generate next transaction ID using STARTBR/READPREV HIGH-VALUES pattern</li>
 *       <li>Create bill payment transaction record (type '02', category 2)</li>
 *       <li>Subtract payment amount from account balance</li>
 *       <li>Update account record (EXEC CICS REWRITE)</li>
 *       <li>Return success message with transaction ID</li>
 *     </ul>
 *   </li>
 * </ol>
 *
 * <p><strong>COBOL-to-Java Transformation Details:</strong></p>
 * <ul>
 *   <li><strong>EXEC CICS RECEIVE MAP:</strong> → @PostMapping with @RequestBody BillPaymentRequest</li>
 *   <li><strong>EXEC CICS READ ACCTDAT UPDATE:</strong> → accountRepository.findById() with optimistic locking</li>
 *   <li><strong>EXEC CICS REWRITE ACCTDAT:</strong> → accountRepository.save() within @Transactional</li>
 *   <li><strong>EXEC CICS WRITE TRANSACT:</strong> → transactionRepository.save() for payment record</li>
 *   <li><strong>EXEC CICS SEND MAP:</strong> → return ResponseEntity&lt;BillPaymentResponse&gt;</li>
 *   <li><strong>EXEC CICS SYNCPOINT:</strong> → @Transactional commit on service method completion</li>
 *   <li><strong>EXEC CICS RETURN:</strong> → HTTP response with status codes</li>
 * </ul>
 *
 * <p><strong>REST API Endpoint Design:</strong></p>
 * <pre>
 * POST /api/payments/bill
 * 
 * Request Body (JSON):
 * {
 *   "accountId": "00000000001",
 *   "currentBalance": 500.00,
 *   "paymentAmount": 250.00,
 *   "confirmation": "Y",
 *   "paymentDate": "2024-01-15",
 *   "payeeId": "PAYEE123",
 *   "payeeName": "Credit Card Payment",
 *   "memo": "Monthly payment"
 * }
 * 
 * Success Response (HTTP 200 OK):
 * {
 *   "transactionName": "CB00",
 *   "programName": "COBIL00C",
 *   "title01": "AWS Mainframe Modernization CardDemo",
 *   "title02": "Bill Payment Processing",
 *   "currentDate": "01/15/2024",
 *   "currentTime": "14:30:45",
 *   "accountId": "00000000001",
 *   "currentBalance": 250.00,
 *   "confirmationFlag": "Y",
 *   "errorMessage": "Payment successful. Your Transaction ID is ABC123456789."
 * }
 * 
 * Error Responses:
 * - HTTP 400 BAD REQUEST: Validation errors, insufficient balance, invalid payee
 * - HTTP 401 UNAUTHORIZED: Missing or invalid authentication
 * - HTTP 403 FORBIDDEN: User not authorized to access account
 * - HTTP 404 NOT FOUND: Account or payee not found
 * - HTTP 500 INTERNAL SERVER ERROR: Unexpected system error
 * </pre>
 *
 * <p><strong>Multi-Step Validation Chain (Section 0.2 and 0.9):</strong></p>
 * <ol>
 *   <li><strong>Authentication Validation:</strong> User must be authenticated (@PreAuthorize)</li>
 *   <li><strong>Authorization Validation:</strong> User owns account or has ROLE_ADMIN</li>
 *   <li><strong>Request Validation:</strong> Bean Validation (@Valid) checks all required fields</li>
 *   <li><strong>Account Validation:</strong> Account exists and is active (service layer)</li>
 *   <li><strong>Amount Validation:</strong> Payment amount is positive, properly scaled (service layer)</li>
 *   <li><strong>Balance Validation:</strong> Sufficient balance for payment (service layer)</li>
 *   <li><strong>Payee Validation:</strong> Payee exists and is authorized (service layer)</li>
 *   <li><strong>Confirmation Validation:</strong> User confirmed payment with 'Y' flag (service layer)</li>
 * </ol>
 *
 * <p><strong>Transaction Management (Section 0.9):</strong></p>
 * <p>The BillPaymentService.processBillPayment() method is annotated with @Transactional,
 * ensuring that:</p>
 * <ul>
 *   <li><strong>Atomicity:</strong> Account balance update and transaction creation occur together</li>
 *   <li><strong>Consistency:</strong> Balance calculations maintain COMP-3 precision</li>
 *   <li><strong>Isolation:</strong> READ_COMMITTED prevents dirty reads</li>
 *   <li><strong>Durability:</strong> Committed transactions survive system failures</li>
 *   <li><strong>Rollback:</strong> Any exception triggers automatic rollback (matching CICS SYNCPOINT ROLLBACK)</li>
 * </ul>
 *
 * <p><strong>Security Model (Section 0.9):</strong></p>
 * <ul>
 *   <li><strong>Role-Based Access:</strong> @PreAuthorize("hasRole('USER')") enforces authenticated access</li>
 *   <li><strong>Account Ownership:</strong> Regular users can only process payments for their own accounts</li>
 *   <li><strong>Admin Override:</strong> ROLE_ADMIN users can process payments for any account</li>
 *   <li><strong>JWT Authentication:</strong> SecurityContext provides user identity from JWT token</li>
 *   <li><strong>Session Management:</strong> Redis-backed session for stateless architecture</li>
 * </ul>
 *
 * <p><strong>Error Handling Patterns:</strong></p>
 * <ul>
 *   <li><strong>AccountNotFoundException:</strong> Maps to COBOL DFHRESP(NOTFND) → HTTP 404</li>
 *   <li><strong>InsufficientBalanceException:</strong> Maps to COBOL balance validation failure → HTTP 400</li>
 *   <li><strong>InvalidPayeeException:</strong> Maps to COBOL payee validation failure → HTTP 400</li>
 *   <li><strong>IllegalArgumentException:</strong> Maps to COBOL field validation → HTTP 400</li>
 *   <li><strong>Generic Exception:</strong> Maps to COBOL general error → HTTP 500</li>
 * </ul>
 *
 * <p><strong>Audit Trail and Logging:</strong></p>
 * <p>All payment attempts (success and failure) are logged with:</p>
 * <ul>
 *   <li>Account ID</li>
 *   <li>Payment amount</li>
 *   <li>User ID (from SecurityContext)</li>
 *   <li>Timestamp</li>
 *   <li>Success/failure status</li>
 *   <li>Error details (if applicable)</li>
 * </ul>
 *
 * <p><strong>Performance Considerations:</strong></p>
 * <ul>
 *   <li>Target response time: &lt; 200ms at 95th percentile</li>
 *   <li>Database connection pooling via HikariCP</li>
 *   <li>Transaction timeout: 30 seconds</li>
 *   <li>Optimistic locking prevents long-held database locks</li>
 * </ul>
 *
 * @see com.carddemo.service.BillPaymentService
 * @see com.carddemo.dto.request.BillPaymentRequest
 * @see com.carddemo.dto.response.BillPaymentResponse
 * @see com.carddemo.entity.Account
 * @see com.carddemo.entity.Transaction
 * @see <a href="Section 0.6">COBOL to Java Service Transformation</a>
 * @see <a href="Section 0.9">Transaction Boundary Preservation</a>
 * @since 1.0
 * @version 1.0
 */
@Slf4j
@RestController
@RequestMapping("/api/payments")
public class BillPaymentController {

    private final BillPaymentService billPaymentService;

    /**
     * Constructor for dependency injection.
     * <p>
     * Uses constructor injection (preferred over field injection) for:
     * <ul>
     *   <li>Immutable dependencies (final fields)</li>
     *   <li>Easier unit testing (no reflection required)</li>
     *   <li>Explicit dependency declaration</li>
     *   <li>Compile-time safety</li>
     * </ul>
     * </p>
     *
     * @param billPaymentService Service for bill payment business logic and transaction processing
     */
    @Autowired
    public BillPaymentController(BillPaymentService billPaymentService) {
        this.billPaymentService = billPaymentService;
    }

    /**
     * Processes a bill payment request with comprehensive validation and authorization.
     * <p>
     * This method transforms the COBOL COBIL00C.cbl PROCESS-ENTER-KEY paragraph (lines 154-244)
     * to a REST API endpoint. It delegates all business logic to BillPaymentService while
     * handling HTTP-specific concerns like authentication, authorization, and response formatting.
     * </p>
     *
     * <p><strong>COBOL Source:</strong> COBIL00C.cbl PROCESS-ENTER-KEY paragraph</p>
     *
     * <p><strong>HTTP Method:</strong> POST</p>
     * <p><strong>Endpoint Path:</strong> /api/payments/bill</p>
     * <p><strong>Content-Type:</strong> application/json</p>
     * <p><strong>Produces:</strong> application/json</p>
     *
     * <p><strong>Security Requirements:</strong></p>
     * <ul>
     *   <li>User must be authenticated (JWT token in Authorization header)</li>
     *   <li>User must have ROLE_USER authority (all authenticated users have this)</li>
     *   <li>Regular users can only process payments for their own accounts</li>
     *   <li>Admin users (ROLE_ADMIN) can process payments for any account</li>
     * </ul>
     *
     * <p><strong>Request Validation:</strong></p>
     * <p>The @Valid annotation triggers Bean Validation on BillPaymentRequest, checking:</p>
     * <ul>
     *   <li><strong>accountId:</strong> @NotBlank, @Size(max=11)</li>
     *   <li><strong>currentBalance:</strong> @NotNull, @DecimalMin("0.00"), @Digits(integer=12, fraction=2)</li>
     *   <li><strong>confirmation:</strong> @NotNull, @Pattern("[YN]")</li>
     *   <li><strong>paymentAmount:</strong> @NotNull, @DecimalMin("0.01"), @Digits(integer=12, fraction=2)</li>
     *   <li><strong>paymentDate:</strong> @FutureOrPresent</li>
     * </ul>
     *
     * <p><strong>Processing Workflow:</strong></p>
     * <ol>
     *   <li><strong>Authentication Check:</strong> Verify JWT token and extract user identity</li>
     *   <li><strong>Authorization Check:</strong> Verify user owns account or is admin</li>
     *   <li><strong>Request Validation:</strong> Bean Validation checks all constraints</li>
     *   <li><strong>Service Invocation:</strong> Delegate to billPaymentService.processBillPayment()</li>
     *   <li><strong>Service Processing:</strong>
     *     <ul>
     *       <li>Validate payment eligibility (balance, confirmation, etc.)</li>
     *       <li>Create payment transaction record</li>
     *       <li>Update account balance atomically</li>
     *       <li>Generate confirmation number</li>
     *     </ul>
     *   </li>
     *   <li><strong>Response Building:</strong> Construct BillPaymentResponse with success details</li>
     *   <li><strong>HTTP Response:</strong> Return 200 OK with response body</li>
     * </ol>
     *
     * <p><strong>Transaction Semantics:</strong></p>
     * <p>The service layer method billPaymentService.processBillPayment() is @Transactional,
     * ensuring:</p>
     * <ul>
     *   <li>All database operations commit together on success</li>
     *   <li>All database operations rollback together on any exception</li>
     *   <li>Account balance and transaction record remain consistent</li>
     *   <li>Matches COBOL CICS SYNCPOINT/SYNCPOINT ROLLBACK behavior</li>
     * </ul>
     *
     * <p><strong>Success Response Example:</strong></p>
     * <pre>
     * HTTP/1.1 200 OK
     * Content-Type: application/json
     * 
     * {
     *   "transactionName": "CB00",
     *   "programName": "COBIL00C",
     *   "title01": "AWS Mainframe Modernization CardDemo",
     *   "title02": "Bill Payment Processing",
     *   "currentDate": "01/15/2024",
     *   "currentTime": "14:30:45",
     *   "accountId": "00000000001",
     *   "currentBalance": 250.00,
     *   "confirmationFlag": "Y",
     *   "errorMessage": "Payment successful. Your Transaction ID is ABC123456789."
     * }
     * </pre>
     *
     * <p><strong>Error Response Examples:</strong></p>
     * <pre>
     * HTTP/1.1 400 BAD REQUEST
     * Content-Type: application/json
     * 
     * {
     *   "timestamp": "2024-01-15T14:30:45.123Z",
     *   "status": 400,
     *   "error": "Bad Request",
     *   "message": "Payment amount exceeds current account balance",
     *   "path": "/api/payments/bill"
     * }
     * 
     * HTTP/1.1 404 NOT FOUND
     * Content-Type: application/json
     * 
     * {
     *   "timestamp": "2024-01-15T14:30:45.123Z",
     *   "status": 404,
     *   "error": "Not Found",
     *   "message": "Account ID NOT found",
     *   "path": "/api/payments/bill"
     * }
     * </pre>
     *
     * <p><strong>COBOL Error Mapping:</strong></p>
     * <ul>
     *   <li><strong>DFHRESP(NOTFND):</strong> (COBIL00C line 359) → AccountNotFoundException → HTTP 404</li>
     *   <li><strong>Balance validation:</strong> (lines 198-205) → InsufficientBalanceException → HTTP 400</li>
     *   <li><strong>Confirmation validation:</strong> (lines 173-191) → IllegalArgumentException → HTTP 400</li>
     *   <li><strong>Empty account ID:</strong> (lines 159-167) → Bean Validation → HTTP 400</li>
     * </ul>
     *
     * @param request Bill payment request containing account ID, payment amount, confirmation flag,
     *                and optional payee information. Request body is automatically deserialized from JSON
     *                and validated using Bean Validation constraints.
     * @return ResponseEntity&lt;BillPaymentResponse&gt; containing:
     *         <ul>
     *           <li><strong>Status 200 OK:</strong> Payment processed successfully with updated balance</li>
     *           <li><strong>Status 400 BAD REQUEST:</strong> Validation errors or insufficient balance</li>
     *           <li><strong>Status 401 UNAUTHORIZED:</strong> Missing or invalid authentication</li>
     *           <li><strong>Status 403 FORBIDDEN:</strong> User not authorized for this account</li>
     *           <li><strong>Status 404 NOT FOUND:</strong> Account or payee not found</li>
     *         </ul>
     * @throws AccountNotFoundException if account does not exist or is inactive (GlobalExceptionHandler returns HTTP 404)
     * @throws InsufficientBalanceException if payment amount exceeds available balance (GlobalExceptionHandler returns HTTP 400)
     * @throws InvalidPayeeException if payee validation fails (GlobalExceptionHandler returns HTTP 400)
     * @throws IllegalArgumentException if request validation fails (GlobalExceptionHandler returns HTTP 400)
     */
    @PostMapping("/bill")
    @PreAuthorize("hasRole('USER')")
    public ResponseEntity<BillPaymentResponse> processBillPayment(
            @Valid @RequestBody BillPaymentRequest request) {
        
        // Log payment attempt for audit trail
        log.info("Bill payment request received for account: {}", request.getAccountId());
        log.debug("Payment request details - Amount: {}, Confirmation: {}", 
                  request.getPaymentAmount(), request.getConfirmation());

        try {
            // Extract authenticated user from SecurityContext (JWT token)
            Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
            String authenticatedUserId = authentication.getName();
            log.debug("Authenticated user: {}", authenticatedUserId);

            // Authorization validation: Verify user owns account or is admin
            // COBOL equivalent: No explicit check in COBIL00C, but CICS security enforces transaction access
            validateAccountOwnership(request.getAccountId(), authenticatedUserId, authentication);

            // Delegate to service layer for business logic processing
            // Service layer is @Transactional ensuring atomic balance update + transaction creation
            BillPaymentResponse response = billPaymentService.processBillPayment(request);

            // Log successful payment for audit trail
            log.info("Bill payment processed successfully for account: {} by user: {}", 
                     request.getAccountId(), authenticatedUserId);
            log.debug("Updated balance: {}, Confirmation: {}", 
                      response.getCurrentBalance(), response.getErrorMessage());

            // Return HTTP 200 OK with payment confirmation details
            return ResponseEntity.ok(response);

        } catch (AccountNotFoundException e) {
            // Maps to COBOL DFHRESP(NOTFND) at line 359
            log.error("Account not found during bill payment: {}", request.getAccountId(), e);
            // GlobalExceptionHandler converts to HTTP 404 NOT FOUND
            throw e;

        } catch (InsufficientBalanceException e) {
            // Maps to COBOL balance validation at lines 198-205
            log.error("Insufficient balance for payment - Account: {}, Requested: {}, Available: {}",
                      e.getAccountId(), e.getRequestedAmount(), e.getAvailableCredit(), e);
            // GlobalExceptionHandler converts to HTTP 400 BAD REQUEST
            throw e;

        } catch (InvalidPayeeException e) {
            // Maps to COBOL payee validation logic
            log.error("Invalid payee for bill payment: {}", e.getPayeeId(), e);
            // GlobalExceptionHandler converts to HTTP 400 BAD REQUEST
            throw e;

        } catch (IllegalArgumentException e) {
            // Maps to COBOL field validation at lines 159-191
            log.error("Invalid payment request parameters: {}", e.getMessage(), e);
            // GlobalExceptionHandler converts to HTTP 400 BAD REQUEST
            throw e;

        } catch (SecurityException e) {
            // Authorization validation failure - user not authorized for account
            log.error("Security violation during bill payment: {}", e.getMessage(), e);
            // GlobalExceptionHandler converts to HTTP 403 FORBIDDEN
            throw e;

        } catch (Exception e) {
            // Catch-all for unexpected errors
            // Maps to COBOL general error handling at line 366
            log.error("Unexpected error processing bill payment for account: {}", 
                      request.getAccountId(), e);
            // GlobalExceptionHandler converts to HTTP 500 INTERNAL SERVER ERROR
            throw new RuntimeException("Unable to process bill payment: " + e.getMessage(), e);
        }
    }

    /**
     * Validates that the authenticated user is authorized to process payments for the specified account.
     * <p>
     * This method implements account ownership validation, ensuring that:
     * <ul>
     *   <li>Regular users can only process payments for accounts they own</li>
     *   <li>Admin users (ROLE_ADMIN) can process payments for any account</li>
     * </ul>
     * </p>
     *
     * <p><strong>Authorization Rules:</strong></p>
     * <ul>
     *   <li>If user has ROLE_ADMIN: Allow payment for any account</li>
     *   <li>If user has ROLE_USER only: Allow payment only if account ID matches user ID</li>
     *   <li>Otherwise: Deny access (403 Forbidden)</li>
     * </ul>
     *
     * <p><strong>COBOL Security Mapping:</strong></p>
     * <p>CICS RACF security enforces transaction-level access control. This method replicates
     * that functionality by checking user authority and account ownership.</p>
     *
     * @param accountId The account ID from the payment request
     * @param userId The authenticated user ID from JWT token (SecurityContext)
     * @param authentication The Spring Security Authentication object containing roles
     * @throws SecurityException if user is not authorized to access the account (converted to HTTP 403 by GlobalExceptionHandler)
     */
    private void validateAccountOwnership(String accountId, String userId, Authentication authentication) {
        // Check if user has admin role - admins can process payments for any account
        boolean isAdmin = authentication.getAuthorities().stream()
                .anyMatch(authority -> authority.getAuthority().equals("ROLE_ADMIN"));

        if (isAdmin) {
            log.debug("Admin user {} authorized to process payment for account: {}", userId, accountId);
            return;
        }

        // For regular users, account ID must match user ID
        // This assumes account ID format aligns with user ID (e.g., zero-padded customer ID)
        // In production, this would query account_user relationship table
        if (!accountId.equals(userId)) {
            log.warn("User {} attempted to process payment for account {} without authorization", 
                     userId, accountId);
            throw new SecurityException(
                String.format("User %s is not authorized to process payments for account %s", 
                             userId, accountId)
            );
        }

        log.debug("User {} authorized to process payment for own account: {}", userId, accountId);
    }
}
