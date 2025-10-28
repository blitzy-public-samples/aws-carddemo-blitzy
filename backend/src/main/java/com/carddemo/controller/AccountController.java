/*
 * AccountController.java
 *
 * REST API controller for account management operations.
 *
 * Converted from COBOL programs:
 * - Source: app/cbl/COACTUPC.cbl (186KB, complex account update logic)
 * - Source: app/cbl/COACTVWC.cbl (account view and inquiry)
 *
 * Original COBOL programs functionality:
 * - COACTUPC.cbl: Account update and maintenance with credit limit validation (lines 500-800)
 * - COACTVWC.cbl: Account view display logic with read-only operations
 *
 * Key COBOL-to-Java transformations:
 * 1. EXEC CICS SEND MAP → REST API JSON response (ResponseEntity)
 * 2. EXEC CICS RECEIVE MAP → REST API JSON request (@RequestBody)
 * 3. EXEC CICS READ FILE('ACCTFILE') → AccountService.getAccountById()
 * 4. EXEC CICS REWRITE FILE('ACCTFILE') → AccountService.updateAccount()
 * 5. EXEC CICS SYNCPOINT → @Transactional in service layer
 * 6. COBOL file-status 23 (NOTFND) → DataNotFoundException → HTTP 404
 * 7. COBOL APPL-RESULT error codes → BusinessException → HTTP 400/409
 * 8. COBOL PIC S9(10)V99 COMP-3 fields → BigDecimal with scale 2
 *
 * REST API Endpoints:
 * - GET    /api/accounts/{accountId}        → View account details (COACTVWC.cbl)
 * - PUT    /api/accounts/{accountId}        → Update account (COACTUPC.cbl)
 * - POST   /api/accounts                    → Create new account (COACTUPC.cbl)
 *
 * Business Rules Preserved (from COACTUPC.cbl lines 500-800):
 * - Credit limit cannot be less than current balance
 * - Credit limit must not exceed maximum ($50,000.00)
 * - Account status must be Y, N, C, or S
 * - All financial calculations maintain COMP-3 precision using BigDecimal
 * - Validation errors return HTTP 400 Bad Request
 * - Not found errors return HTTP 404 Not Found
 * - Business rule violations return HTTP 400 Bad Request or 409 Conflict
 *
 * Per Agent Action Plan Section 0.7.2:
 * - Maintain identical business logic to COBOL with zero functional deviation
 * - Use BigDecimal for all currency amounts with scale 2 and RoundingMode.HALF_UP
 * - Preserve exact field-level validations from COBOL programs
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

import com.carddemo.exception.BusinessException;
import com.carddemo.exception.DataNotFoundException;
import com.carddemo.model.dto.AccountDto;
import com.carddemo.service.AccountService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/**
 * REST API controller for account management operations.
 * 
 * <p>This controller replaces COBOL CICS transaction processing programs:</p>
 * <ul>
 *   <li><b>COACTVWC.cbl:</b> Account view and inquiry (transaction ID CAVW)</li>
 *   <li><b>COACTUPC.cbl:</b> Account update and maintenance (transaction ID CAUP)</li>
 * </ul>
 * 
 * <h3>COBOL to REST API Mapping:</h3>
 * <table border="1">
 *   <tr>
 *     <th>COBOL Program</th>
 *     <th>CICS Transaction</th>
 *     <th>BMS Screen</th>
 *     <th>REST Endpoint</th>
 *     <th>HTTP Method</th>
 *   </tr>
 *   <tr>
 *     <td>COACTVWC.cbl</td>
 *     <td>CAVW</td>
 *     <td>COACTVW</td>
 *     <td>/api/accounts/{accountId}</td>
 *     <td>GET</td>
 *   </tr>
 *   <tr>
 *     <td>COACTUPC.cbl</td>
 *     <td>CAUP</td>
 *     <td>COACTUP</td>
 *     <td>/api/accounts/{accountId}</td>
 *     <td>PUT</td>
 *   </tr>
 *   <tr>
 *     <td>COACTUPC.cbl</td>
 *     <td>CAUP</td>
 *     <td>COACTUP</td>
 *     <td>/api/accounts</td>
 *     <td>POST</td>
 *   </tr>
 * </table>
 * 
 * <h3>Request/Response Handling:</h3>
 * <p>All endpoints use JSON format for request and response bodies, replacing COBOL
 * BMS map I/O operations:</p>
 * <ul>
 *   <li><b>EXEC CICS SEND MAP:</b> Replaced by ResponseEntity with JSON body</li>
 *   <li><b>EXEC CICS RECEIVE MAP:</b> Replaced by @RequestBody with JSON deserialization</li>
 *   <li><b>DFHCOMMAREA:</b> No longer needed; state managed via database and JWT tokens</li>
 * </ul>
 * 
 * <h3>Error Handling:</h3>
 * <p>COBOL error patterns mapped to HTTP status codes:</p>
 * <ul>
 *   <li><b>File-status 23 (NOTFND):</b> {@link DataNotFoundException} → HTTP 404 Not Found</li>
 *   <li><b>APPL-RESULT = 12:</b> {@link BusinessException} → HTTP 400 Bad Request</li>
 *   <li><b>Validation Errors:</b> MethodArgumentNotValidException → HTTP 400 Bad Request</li>
 *   <li><b>Optimistic Lock:</b> OptimisticLockException → HTTP 409 Conflict</li>
 * </ul>
 * 
 * <p>All exceptions are caught and handled by GlobalExceptionHandler, which returns
 * standardized ErrorResponse DTOs with error codes, messages, and timestamps.</p>
 * 
 * <h3>Validation:</h3>
 * <p>Input validation uses Jakarta Bean Validation (@Valid annotation):</p>
 * <ul>
 *   <li>@NotNull constraints on required fields (account ID, credit limit, status)</li>
 *   <li>@Positive constraints on numeric fields (credit limit, amounts)</li>
 *   <li>@Size constraints on string fields (status, ZIP code, group ID)</li>
 *   <li>@PastOrPresent constraints on date fields (open date)</li>
 * </ul>
 * 
 * <h3>Transaction Management:</h3>
 * <p>Spring @Transactional annotations in service layer replace COBOL CICS commands:</p>
 * <ul>
 *   <li><b>EXEC CICS SYNCPOINT:</b> Automatic commit at end of @Transactional method</li>
 *   <li><b>EXEC CICS ROLLBACK:</b> Automatic rollback on exception</li>
 *   <li><b>Optimistic Locking:</b> JPA @Version field prevents concurrent update issues</li>
 * </ul>
 * 
 * @see AccountService
 * @see AccountDto
 * @see DataNotFoundException
 * @see BusinessException
 * @author CardDemo Modernization Team
 * @version 1.0
 * @since 2024
 */
@Slf4j
@RestController
@RequestMapping("/api/accounts")
@RequiredArgsConstructor
public class AccountController {

    private final AccountService accountService;

    /**
     * Retrieve account details by account ID.
     * 
     * <p>Converted from COBOL program COACTVWC.cbl (Account View):</p>
     * <pre>
     * PROCEDURE DIVISION.
     *     PERFORM PROCESS-ENTER-ACCOUNT.
     *     EXEC CICS READ FILE('ACCTFILE')
     *          INTO(ACCOUNT-RECORD)
     *          RIDFLD(ACCT-ID)
     *          RESP(WS-RESP-CD)
     *          RESP2(WS-REAS-CD)
     *     END-EXEC
     *     IF WS-RESP-CD = DFHRESP(NORMAL)
     *        MOVE ACCOUNT-RECORD TO COACTVWO
     *        EXEC CICS SEND MAP('COACTVW')
     *             MAPSET('COACTVW')
     *             FROM(COACTVWO)
     *             ERASE
     *        END-EXEC
     *     ELSE
     *        PERFORM ACCOUNT-NOT-FOUND-ERROR
     *     END-IF.
     * </pre>
     * 
     * <h3>Business Rules:</h3>
     * <ul>
     *   <li>Account ID must be valid 11-digit number</li>
     *   <li>Account must exist in database (ACCTFILE)</li>
     *   <li>Returns complete account details including balance, credit limit, dates</li>
     *   <li>Read-only operation (CICS READ without UPDATE option)</li>
     * </ul>
     * 
     * <h3>Response Format:</h3>
     * <p>Returns AccountDto with all account fields:</p>
     * <ul>
     *   <li>acctId: Account identifier (11 digits)</li>
     *   <li>acctActiveStatus: Status code (Y/N/C/S)</li>
     *   <li>acctCurrBal: Current balance (BigDecimal scale 2)</li>
     *   <li>acctCreditLimit: Purchase credit limit</li>
     *   <li>acctCashCreditLimit: Cash advance limit</li>
     *   <li>acctOpenDate: Account opening date</li>
     *   <li>acctExpirationDate: Account expiration date</li>
     *   <li>acctCurrCycCredit: Current cycle credit total</li>
     *   <li>acctCurrCycDebit: Current cycle debit total</li>
     *   <li>acctAddrZip: Billing address ZIP code</li>
     *   <li>acctGroupId: Account group identifier</li>
     * </ul>
     * 
     * <h3>Example Request:</h3>
     * <pre>
     * GET /api/accounts/12345678901 HTTP/1.1
     * Host: api.carddemo.com
     * Authorization: Bearer eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9...
     * Accept: application/json
     * </pre>
     * 
     * <h3>Example Successful Response (HTTP 200 OK):</h3>
     * <pre>
     * {
     *   "acctId": 12345678901,
     *   "acctActiveStatus": "Y",
     *   "acctCurrBal": 1250.75,
     *   "acctCreditLimit": 5000.00,
     *   "acctCashCreditLimit": 1000.00,
     *   "acctOpenDate": "2020-01-15",
     *   "acctExpirationDate": "2025-01-31",
     *   "acctReissueDate": null,
     *   "acctCurrCycCredit": 500.00,
     *   "acctCurrCycDebit": 750.25,
     *   "acctAddrZip": "10001",
     *   "acctGroupId": "PREMIUM",
     *   "createdAt": "2020-01-15T10:30:00",
     *   "updatedAt": "2024-03-20T14:25:30"
     * }
     * </pre>
     * 
     * <h3>Example Error Response (HTTP 404 Not Found):</h3>
     * <pre>
     * {
     *   "timestamp": "2024-03-20T14:30:00",
     *   "status": 404,
     *   "error": "Not Found",
     *   "message": "Account with ID 12345678901 not found",
     *   "errorCode": "DNF001",
     *   "path": "/api/accounts/12345678901"
     * }
     * </pre>
     * 
     * @param accountId The 11-digit account identifier (COBOL PIC 9(11) ACCT-ID)
     * @return ResponseEntity with AccountDto and HTTP 200 OK if found
     * @throws DataNotFoundException if account does not exist (COBOL file-status 23)
     * @throws ValidationException if accountId is invalid format
     */
    @GetMapping("/{accountId}")
    public ResponseEntity<AccountDto> getAccountById(@PathVariable Long accountId) {
        log.info("GET /api/accounts/{} - Retrieving account details", accountId);
        log.debug("Processing account view request for account ID: {}", accountId);
        
        // Call service layer which handles validation and database access
        // Replaces COBOL EXEC CICS READ FILE('ACCTFILE')
        AccountDto accountDto = accountService.getAccountById(accountId);
        
        log.info("GET /api/accounts/{} - Account retrieved successfully", accountId);
        log.debug("Account data: acctId={}, status={}, balance={}, creditLimit={}", 
                accountDto.getAcctId(), 
                accountDto.getAcctActiveStatus(),
                accountDto.getAcctCurrBal(),
                accountDto.getAcctCreditLimit());
        
        // Return HTTP 200 OK with account data
        // Replaces COBOL EXEC CICS SEND MAP with JSON response
        return ResponseEntity.ok(accountDto);
    }

    /**
     * Update account details with validation.
     * 
     * <p>Converted from COBOL program COACTUPC.cbl (Account Update) lines 1-1200:</p>
     * <pre>
     * PROCEDURE DIVISION.
     *     PERFORM PROCESS-ENTER-ACCOUNT.
     *     PERFORM VALIDATE-ACCOUNT-CHANGES.
     *     IF VALIDATION-OK
     *        EXEC CICS READ FILE('ACCTFILE')
     *             INTO(ACCOUNT-RECORD)
     *             RIDFLD(ACCT-ID)
     *             UPDATE
     *             RESP(WS-RESP-CD)
     *        END-EXEC
     *        IF WS-RESP-CD = DFHRESP(NORMAL)
     *           MOVE NEW-CREDIT-LIMIT TO ACCT-CREDIT-LIMIT
     *           MOVE NEW-ACTIVE-STATUS TO ACCT-ACTIVE-STATUS
     *           EXEC CICS REWRITE FILE('ACCTFILE')
     *                FROM(ACCOUNT-RECORD)
     *                RESP(WS-RESP-CD)
     *           END-EXEC
     *           EXEC CICS SYNCPOINT
     *           END-EXEC
     *        END-IF
     *     END-IF.
     * </pre>
     * 
     * <h3>Business Rules (from COACTUPC.cbl lines 500-800):</h3>
     * <ul>
     *   <li><b>Credit Limit Validation:</b> New limit cannot be less than current balance
     *       (line 196-199: IF NEW-CREDIT-LIMIT &lt; CURRENT-BALANCE MOVE 12 TO APPL-RESULT)</li>
     *   <li><b>Credit Limit Maximum:</b> Cannot exceed $50,000.00</li>
     *   <li><b>Credit Limit Minimum:</b> Must be positive non-zero value</li>
     *   <li><b>Status Validation:</b> Must be Y (active), N (inactive), C (closed), or S (suspended)</li>
     *   <li><b>Concurrent Update Protection:</b> Uses JPA optimistic locking (@Version field)</li>
     * </ul>
     * 
     * <h3>Updatable Fields:</h3>
     * <p>The following fields can be modified via this endpoint:</p>
     * <ul>
     *   <li>acctCreditLimit: Purchase credit limit (validated against balance)</li>
     *   <li>acctCashCreditLimit: Cash advance limit</li>
     *   <li>acctActiveStatus: Account status (Y/N/C/S)</li>
     *   <li>acctExpirationDate: Account expiration date</li>
     *   <li>acctAddrZip: Billing address ZIP code (max 10 characters)</li>
     *   <li>acctGroupId: Account group identifier (max 10 characters)</li>
     * </ul>
     * 
     * <h3>Read-Only Fields:</h3>
     * <p>The following fields are calculated or managed by system and cannot be updated:</p>
     * <ul>
     *   <li>acctId: Account identifier (primary key)</li>
     *   <li>acctCurrBal: Current balance (calculated from transactions)</li>
     *   <li>acctCurrCycCredit: Current cycle credit (updated by batch jobs)</li>
     *   <li>acctCurrCycDebit: Current cycle debit (updated by batch jobs)</li>
     *   <li>acctOpenDate: Account opening date (immutable)</li>
     *   <li>acctReissueDate: Managed by card reissue process</li>
     * </ul>
     * 
     * <h3>Example Request:</h3>
     * <pre>
     * PUT /api/accounts/12345678901 HTTP/1.1
     * Host: api.carddemo.com
     * Authorization: Bearer eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9...
     * Content-Type: application/json
     * 
     * {
     *   "acctCreditLimit": 7500.00,
     *   "acctCashCreditLimit": 1500.00,
     *   "acctActiveStatus": "Y",
     *   "acctExpirationDate": "2026-12-31",
     *   "acctAddrZip": "10002",
     *   "acctGroupId": "PREMIUM"
     * }
     * </pre>
     * 
     * <h3>Example Successful Response (HTTP 200 OK):</h3>
     * <pre>
     * {
     *   "acctId": 12345678901,
     *   "acctActiveStatus": "Y",
     *   "acctCurrBal": 1250.75,
     *   "acctCreditLimit": 7500.00,
     *   "acctCashCreditLimit": 1500.00,
     *   "acctOpenDate": "2020-01-15",
     *   "acctExpirationDate": "2026-12-31",
     *   "acctReissueDate": null,
     *   "acctCurrCycCredit": 500.00,
     *   "acctCurrCycDebit": 750.25,
     *   "acctAddrZip": "10002",
     *   "acctGroupId": "PREMIUM",
     *   "createdAt": "2020-01-15T10:30:00",
     *   "updatedAt": "2024-03-20T14:35:00"
     * }
     * </pre>
     * 
     * <h3>Example Error Response - Credit Limit Violation (HTTP 400 Bad Request):</h3>
     * <pre>
     * {
     *   "timestamp": "2024-03-20T14:35:00",
     *   "status": 400,
     *   "error": "Bad Request",
     *   "message": "Credit limit cannot be less than current balance",
     *   "errorCode": "BUS001",
     *   "path": "/api/accounts/12345678901"
     * }
     * </pre>
     * 
     * <h3>Example Error Response - Account Not Found (HTTP 404 Not Found):</h3>
     * <pre>
     * {
     *   "timestamp": "2024-03-20T14:35:00",
     *   "status": 404,
     *   "error": "Not Found",
     *   "message": "Account with ID 12345678901 not found",
     *   "errorCode": "DNF001",
     *   "path": "/api/accounts/12345678901"
     * }
     * </pre>
     * 
     * @param accountId The account ID to update (must match existing account)
     * @param accountDto DTO containing updated account data with JSR-380 validation constraints
     * @return ResponseEntity with updated AccountDto and HTTP 200 OK
     * @throws DataNotFoundException if account does not exist (COBOL DFHRESP(NOTFND))
     * @throws BusinessException if credit limit validation fails (COBOL APPL-RESULT = 12)
     * @throws ValidationException if input data violates constraints (COBOL validation flags)
     */
    @PutMapping("/{accountId}")
    public ResponseEntity<AccountDto> updateAccount(
            @PathVariable Long accountId,
            @Valid @RequestBody AccountDto accountDto) {
        
        log.info("PUT /api/accounts/{} - Updating account", accountId);
        log.debug("Update request for account {}: creditLimit={}, status={}, zip={}, groupId={}", 
                accountId,
                accountDto.getAcctCreditLimit(),
                accountDto.getAcctActiveStatus(),
                accountDto.getAcctAddrZip(),
                accountDto.getAcctGroupId());
        
        // Call service layer which handles validation, business rules, and database update
        // Replaces COBOL EXEC CICS READ UPDATE + REWRITE + SYNCPOINT sequence
        AccountDto updatedAccount = accountService.updateAccount(accountId, accountDto);
        
        log.info("PUT /api/accounts/{} - Account updated successfully", accountId);
        log.debug("Updated account: acctId={}, status={}, creditLimit={}, balance={}", 
                updatedAccount.getAcctId(),
                updatedAccount.getAcctActiveStatus(),
                updatedAccount.getAcctCreditLimit(),
                updatedAccount.getAcctCurrBal());
        
        // Return HTTP 200 OK with updated account data
        // Replaces COBOL EXEC CICS SEND MAP with success message
        return ResponseEntity.ok(updatedAccount);
    }

    /**
     * Create a new account.
     * 
     * <p>Implements account creation logic based on COACTUPC.cbl patterns.
     * While COACTUPC.cbl primarily handles updates, the same validation rules
     * and field requirements apply to account creation.</p>
     * 
     * <p>COBOL equivalent pattern:</p>
     * <pre>
     * PROCEDURE DIVISION.
     *     PERFORM VALIDATE-NEW-ACCOUNT.
     *     IF VALIDATION-OK
     *        EXEC CICS WRITE FILE('ACCTFILE')
     *             FROM(ACCOUNT-RECORD)
     *             RIDFLD(ACCT-ID)
     *             RESP(WS-RESP-CD)
     *        END-EXEC
     *        IF WS-RESP-CD = DFHRESP(NORMAL)
     *           EXEC CICS SYNCPOINT
     *           END-EXEC
     *        END-IF
     *     END-IF.
     * </pre>
     * 
     * <h3>Business Rules:</h3>
     * <ul>
     *   <li><b>Account ID:</b> Must be unique 11-digit number</li>
     *   <li><b>Credit Limit:</b> Required, must be positive and within bounds</li>
     *   <li><b>Cash Credit Limit:</b> Required, must be positive</li>
     *   <li><b>Active Status:</b> Required, defaults to 'Y' if not specified</li>
     *   <li><b>Open Date:</b> Required, must be valid date (not in future)</li>
     *   <li><b>Initial Balance:</b> Set to 0.00 (COBOL: MOVE ZEROS TO ACCT-CURR-BAL)</li>
     *   <li><b>Cycle Totals:</b> Set to 0.00 (credit and debit both zero)</li>
     * </ul>
     * 
     * <h3>Required Fields:</h3>
     * <ul>
     *   <li>acctId: Account identifier (11 digits, unique)</li>
     *   <li>acctCreditLimit: Purchase credit limit (positive, ≤ $50,000.00)</li>
     *   <li>acctCashCreditLimit: Cash advance limit (positive)</li>
     *   <li>acctActiveStatus: Status code (Y/N/C/S, defaults to Y)</li>
     *   <li>acctOpenDate: Opening date (not in future)</li>
     * </ul>
     * 
     * <h3>Optional Fields:</h3>
     * <ul>
     *   <li>acctExpirationDate: Account expiration date</li>
     *   <li>acctReissueDate: Account reissue date</li>
     *   <li>acctAddrZip: Billing address ZIP code</li>
     *   <li>acctGroupId: Account group identifier</li>
     * </ul>
     * 
     * <h3>Default Values (COBOL initialization):</h3>
     * <ul>
     *   <li>acctCurrBal: 0.00 (MOVE ZEROS TO ACCT-CURR-BAL)</li>
     *   <li>acctCurrCycCredit: 0.00 (MOVE ZEROS TO ACCT-CURR-CYC-CREDIT)</li>
     *   <li>acctCurrCycDebit: 0.00 (MOVE ZEROS TO ACCT-CURR-CYC-DEBIT)</li>
     *   <li>acctActiveStatus: 'Y' if not specified (MOVE 'Y' TO ACCT-ACTIVE-STATUS)</li>
     * </ul>
     * 
     * <h3>Example Request:</h3>
     * <pre>
     * POST /api/accounts HTTP/1.1
     * Host: api.carddemo.com
     * Authorization: Bearer eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9...
     * Content-Type: application/json
     * 
     * {
     *   "acctId": 98765432109,
     *   "acctActiveStatus": "Y",
     *   "acctCreditLimit": 5000.00,
     *   "acctCashCreditLimit": 1000.00,
     *   "acctOpenDate": "2024-03-20",
     *   "acctExpirationDate": "2027-03-31",
     *   "acctAddrZip": "10001",
     *   "acctGroupId": "STANDARD"
     * }
     * </pre>
     * 
     * <h3>Example Successful Response (HTTP 201 Created):</h3>
     * <pre>
     * {
     *   "acctId": 98765432109,
     *   "acctActiveStatus": "Y",
     *   "acctCurrBal": 0.00,
     *   "acctCreditLimit": 5000.00,
     *   "acctCashCreditLimit": 1000.00,
     *   "acctOpenDate": "2024-03-20",
     *   "acctExpirationDate": "2027-03-31",
     *   "acctReissueDate": null,
     *   "acctCurrCycCredit": 0.00,
     *   "acctCurrCycDebit": 0.00,
     *   "acctAddrZip": "10001",
     *   "acctGroupId": "STANDARD",
     *   "createdAt": "2024-03-20T14:40:00",
     *   "updatedAt": "2024-03-20T14:40:00"
     * }
     * </pre>
     * 
     * <h3>Example Error Response - Duplicate Account (HTTP 409 Conflict):</h3>
     * <pre>
     * {
     *   "timestamp": "2024-03-20T14:40:00",
     *   "status": 409,
     *   "error": "Conflict",
     *   "message": "Account ID already exists: 98765432109",
     *   "errorCode": "BUS003",
     *   "path": "/api/accounts"
     * }
     * </pre>
     * 
     * <h3>Example Error Response - Invalid Data (HTTP 400 Bad Request):</h3>
     * <pre>
     * {
     *   "timestamp": "2024-03-20T14:40:00",
     *   "status": 400,
     *   "error": "Bad Request",
     *   "message": "Validation failed: acctCreditLimit must be positive",
     *   "errorCode": "VAL001",
     *   "path": "/api/accounts"
     * }
     * </pre>
     * 
     * @param accountDto DTO containing new account data with JSR-380 validation constraints
     * @return ResponseEntity with created AccountDto and HTTP 201 Created
     * @throws BusinessException if account ID already exists (COBOL DUPREC condition)
     * @throws ValidationException if input data violates constraints (COBOL validation errors)
     */
    @PostMapping
    public ResponseEntity<AccountDto> createAccount(@Valid @RequestBody AccountDto accountDto) {
        log.info("POST /api/accounts - Creating new account with ID: {}", accountDto.getAcctId());
        log.debug("Create request: acctId={}, creditLimit={}, cashLimit={}, status={}, openDate={}", 
                accountDto.getAcctId(),
                accountDto.getAcctCreditLimit(),
                accountDto.getAcctCashCreditLimit(),
                accountDto.getAcctActiveStatus(),
                accountDto.getAcctOpenDate());
        
        // Call service layer which handles validation, duplicate check, and database insert
        // Replaces COBOL EXEC CICS WRITE FILE('ACCTFILE') + SYNCPOINT
        AccountDto createdAccount = accountService.createAccount(accountDto);
        
        log.info("POST /api/accounts - Account created successfully: {}", createdAccount.getAcctId());
        log.debug("Created account: acctId={}, status={}, creditLimit={}, balance={}", 
                createdAccount.getAcctId(),
                createdAccount.getAcctActiveStatus(),
                createdAccount.getAcctCreditLimit(),
                createdAccount.getAcctCurrBal());
        
        // Return HTTP 201 Created with new account data
        // HTTP 201 indicates successful resource creation per REST standards
        // Replaces COBOL EXEC CICS SEND MAP with success message
        return ResponseEntity
                .status(HttpStatus.CREATED)
                .body(createdAccount);
    }
}
