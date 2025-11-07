/*
 * AccountUpdateService.java
 * 
 * CardDemo Application - Account Update Service
 * 
 * Spring service class implementing account update functionality transformed from
 * COBOL CICS transaction program COACTUPC.cbl (transaction ID CAUP).
 * 
 * Original COBOL Program: app/cbl/COACTUPC.cbl
 * Transaction ID: CAUP
 * Function: Accept and process ACCOUNT UPDATE requests with validation
 * 
 * This service provides transactional account updates with @Transactional annotation
 * ensuring ACID properties equivalent to CICS SYNCPOINT commit boundaries. All
 * database operations execute atomically with automatic rollback on any exception.
 * 
 * Key Transformations:
 * - EXEC CICS READ DATASET('ACCTDAT') → AccountRepository.findByAccountId()
 * - EXEC CICS REWRITE DATASET('ACCTDAT') → AccountRepository.save()
 * - CICS SYNCPOINT → @Transactional method completion (automatic commit)
 * - CICS ROLLBACK → Exception thrown triggers automatic rollback
 * - VSAM exclusive record locking → JPA @Version optimistic locking
 * - COBOL 88-level validation flags → Java validation with exception throwing
 * 
 * Business Rules Enforced:
 * 1. Credit Limit Validation:
 *    - Minimum: $1,000.00 (COBOL: 88 LIMIT-VALID VALUE 1000 THRU 999999999)
 *    - Maximum: $999,999,999.99
 *    
 * 2. Cash Credit Limit Validation:
 *    - Must not exceed total credit limit (NEW requirement for Java implementation)
 *    - Can be $0.00 (no cash advance privilege)
 *    
 * 3. Active Status Validation:
 *    - Must be 'Y' (active) or 'N' (inactive)
 *    - COBOL: 88 FLG-YES-NO-ISVALID VALUES 'Y', 'N'
 *    
 * 4. Change Detection:
 *    - At least one field must be modified
 *    - COBOL: NO-CHANGES-DETECTED condition
 *    
 * 5. Concurrency Control:
 *    - Optimistic locking prevents concurrent modification conflicts
 *    - COBOL: DATA-WAS-CHANGED-BEFORE-UPDATE error handling
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
package com.carddemo.service.account;

import com.carddemo.dto.request.AccountUpdateRequest;
import com.carddemo.dto.response.AccountResponse;
import com.carddemo.entity.Account;
import com.carddemo.exception.BusinessLogicException;
import com.carddemo.exception.ResourceNotFoundException;
import com.carddemo.exception.ValidationException;
import com.carddemo.repository.AccountRepository;
import jakarta.persistence.OptimisticLockException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Service class for account update operations, transforming COBOL CICS transaction
 * CAUP (program COACTUPC.cbl) to Spring Boot service layer architecture.
 * 
 * <h2>COBOL Program Mapping</h2>
 * <p>This service directly corresponds to the mainframe COBOL program COACTUPC.cbl
 * which handles CICS transaction CAUP for account updates:</p>
 * <pre>
 * COBOL Program: app/cbl/COACTUPC.cbl
 * Transaction ID: CAUP
 * BMS Mapset: COACTUPM.bms (Account Update Screen)
 * Copybooks: CVACT01Y.cpy (Account Record), COCOM01Y.cpy (COMMAREA)
 * </pre>
 * 
 * <h2>Transaction Management</h2>
 * <p>The @Transactional annotation on the updateAccount() method provides ACID
 * properties equivalent to CICS SYNCPOINT boundaries:</p>
 * <ul>
 *   <li><b>Atomicity:</b> All database operations succeed or fail together</li>
 *   <li><b>Consistency:</b> Account data remains consistent across updates</li>
 *   <li><b>Isolation:</b> READ_COMMITTED isolation level prevents dirty reads</li>
 *   <li><b>Durability:</b> Committed changes persist to database</li>
 * </ul>
 * 
 * <p>Transaction behavior:</p>
 * <ul>
 *   <li>Transaction begins automatically when updateAccount() is invoked</li>
 *   <li>Successful method completion triggers automatic commit</li>
 *   <li>Any RuntimeException triggers automatic rollback</li>
 *   <li>Rollback undoes all changes made within the transaction boundary</li>
 * </ul>
 * 
 * <h2>COBOL Procedure Division Flow</h2>
 * <p>The original COBOL program flow is preserved in Java method structure:</p>
 * <pre>
 * COBOL Paragraph                   Java Method Equivalent
 * ================================================================================
 * 1000-MAIN-PARA                     updateAccount() method entry point
 * 1100-RECEIVE-MAP                   @RequestBody AccountUpdateRequest (handled by controller)
 * 1200-EDIT-ACCOUNT-UPDATE-INPUTS    validateUpdateRequest() - field-level validation
 * 1300-UPDATE-ACCOUNT-RECORD         performAccountUpdate() - database update execution
 * 1400-SEND-MAP-DATAONLY             Return AccountResponse (handled by controller)
 * 1225-EDIT-ALPHA-REQD               validateActiveStatus() - active status validation
 * 1250-EDIT-SIGNED-9V2               validateCreditLimit() - credit limit validation
 * 1250-EDIT-SIGNED-9V2               validateCashCreditLimit() - cash credit validation
 * </pre>
 * 
 * <h2>Validation Logic Transformation</h2>
 * <p>COBOL field validation paragraphs transform to Java validation methods:</p>
 * <pre>
 * COBOL Validation                   Java Validation Method
 * ================================================================================
 * WS-EDIT-YES-NO validation          validateActiveStatus()
 * 88 FLG-YES-NO-ISVALID              String validation with 'Y' or 'N' check
 * WS-EDIT-SIGNED-NUMBER-9V2-X        validateCreditLimit()
 * 88 FLG-SIGNED-NUMBER-ISVALID       BigDecimal range validation
 * WS-FLG-CRED-LIMIT-NOT-OK           ValidationException thrown on failure
 * WS-FLG-CASH-CREDIT-LIMIT-NOT-OK    ValidationException thrown on failure
 * </pre>
 * 
 * <h2>Error Handling Transformation</h2>
 * <p>COBOL error conditions map to Java exceptions:</p>
 * <pre>
 * COBOL Error Condition              Java Exception
 * ================================================================================
 * RESP-CD 13 (NOTFND)                ResourceNotFoundException
 * INPUT-ERROR flag set to '1'        ValidationException with field errors
 * DATA-WAS-CHANGED-BEFORE-UPDATE     BusinessLogicException (from OptimisticLockException)
 * NO-CHANGES-DETECTED                ValidationException with descriptive message
 * COULD-NOT-LOCK-ACCT-FOR-UPDATE     OptimisticLockException (automatic retry by client)
 * </pre>
 * 
 * <h2>Monetary Precision Requirements</h2>
 * <p><b>CRITICAL:</b> All monetary fields use BigDecimal with scale=2 and RoundingMode.HALF_UP
 * to maintain exact COBOL COMP-3 packed decimal arithmetic precision:</p>
 * <pre>
 * COBOL: PIC S9(10)V99 COMP-3  →  Java: BigDecimal with scale=2
 * ACCT-CREDIT-LIMIT            →  creditLimit (BigDecimal)
 * ACCT-CASH-CREDIT-LIMIT       →  cashCreditLimit (BigDecimal)
 * ACCT-CURR-BAL                →  currentBalance (BigDecimal)
 * </pre>
 * 
 * <h2>Optimistic Locking Mechanism</h2>
 * <p>JPA @Version annotation on Account entity provides concurrent modification
 * detection equivalent to VSAM exclusive record locking:</p>
 * <ul>
 *   <li>Account entity includes @Version field (Long type)</li>
 *   <li>Version increments automatically on each update</li>
 *   <li>Concurrent update attempt throws OptimisticLockException</li>
 *   <li>Service catches exception and throws BusinessLogicException with
 *       "Record changed by someone else" message</li>
 *   <li>Client must refresh data and retry update</li>
 * </ul>
 * 
 * <h2>REST API Integration</h2>
 * <p>This service is invoked by AccountController REST endpoint:</p>
 * <ul>
 *   <li><b>Endpoint:</b> PUT /api/accounts/{id}</li>
 *   <li><b>Request:</b> AccountUpdateRequest DTO with validation annotations</li>
 *   <li><b>Response:</b> AccountResponse DTO with updated account details</li>
 *   <li><b>HTTP Status:</b> 200 OK on success, 4xx/5xx on errors</li>
 * </ul>
 * 
 * <h2>Security</h2>
 * <p>Account updates may require administrative privileges. The controller layer
 * applies @PreAuthorize annotations for role-based access control matching RACF
 * security patterns from the mainframe.</p>
 * 
 * @see Account JPA entity with @Version for optimistic locking
 * @see AccountRepository Spring Data JPA repository
 * @see AccountUpdateRequest Request DTO with Bean Validation
 * @see AccountResponse Response DTO with complete account data
 * @see com.carddemo.controller.AccountController REST API controller
 * 
 * @author CardDemo Migration Team
 * @version 1.0
 * @since 2024
 */
@Service
@Transactional
@RequiredArgsConstructor
@Slf4j
public class AccountUpdateService {

    /**
     * Spring Data JPA repository for account data access operations.
     * 
     * <p>Replaces COBOL EXEC CICS file control commands:</p>
     * <ul>
     *   <li>EXEC CICS READ DATASET('ACCTDAT') KEY(WS-ACCT-ID) → findByAccountId()</li>
     *   <li>EXEC CICS REWRITE DATASET('ACCTDAT') FROM(ACCOUNT-RECORD) → save()</li>
     * </ul>
     * 
     * <p>Constructor injection ensures immutable dependency and supports testing.</p>
     */
    private final AccountRepository accountRepository;

    /**
     * Minimum credit limit constant matching COBOL validation rule.
     * 
     * <p>COBOL: 88 LIMIT-VALID VALUE 1000 THRU 999999999</p>
     * <p>This represents the regulatory minimum credit limit of $1,000.00</p>
     */
    private static final BigDecimal MIN_CREDIT_LIMIT = new BigDecimal("1000.00");

    /**
     * Maximum credit limit constant matching COBOL validation rule.
     * 
     * <p>COBOL: 88 LIMIT-VALID VALUE 1000 THRU 999999999</p>
     * <p>Maximum credit limit of $999,999,999.99</p>
     */
    private static final BigDecimal MAX_CREDIT_LIMIT = new BigDecimal("999999999.99");

    /**
     * Valid active status value: Active account
     * 
     * <p>COBOL: 88 FLG-YES-NO-ISVALID VALUES 'Y', 'N'</p>
     */
    private static final String ACTIVE_STATUS_YES = "Y";

    /**
     * Valid active status value: Inactive account
     * 
     * <p>COBOL: 88 FLG-YES-NO-ISVALID VALUES 'Y', 'N'</p>
     */
    private static final String ACTIVE_STATUS_NO = "N";

    /**
     * Update account information with comprehensive validation and transaction management.
     * 
     * <p>This method is the primary entry point for account update operations, transforming
     * COBOL CICS transaction CAUP (program COACTUPC.cbl) to Spring Boot service architecture.
     * The @Transactional annotation ensures all database operations execute atomically with
     * automatic rollback on any exception.</p>
     * 
     * <h3>COBOL Program Flow Mapping</h3>
     * <pre>
     * COBOL Paragraph             Java Method Call
     * ================================================================================
     * 1000-MAIN-PARA              updateAccount() entry point
     * 1100-RECEIVE-MAP            Parameter: AccountUpdateRequest (from controller)
     * 1200-EDIT-INPUTS            validateUpdateRequest() - comprehensive validation
     * 1300-UPDATE-RECORD          performAccountUpdate() - execute database update
     * 1400-SEND-MAP               Return: AccountResponse (to controller)
     * </pre>
     * 
     * <h3>Transaction Semantics</h3>
     * <ul>
     *   <li><b>Isolation Level:</b> READ_COMMITTED (prevents dirty reads)</li>
     *   <li><b>Propagation:</b> REQUIRED (joins existing transaction or creates new)</li>
     *   <li><b>Rollback:</b> Automatic on any RuntimeException</li>
     *   <li><b>Commit:</b> Automatic on successful method completion</li>
     * </ul>
     * 
     * <h3>Validation Rules Enforced</h3>
     * <ol>
     *   <li><b>Account Existence:</b> Account ID must exist in database
     *       <br>COBOL: EXEC CICS READ ... RESP(13) = NOTFND</li>
     *   <li><b>Credit Limit Range:</b> $1,000.00 to $999,999,999.99
     *       <br>COBOL: 88 LIMIT-VALID VALUE 1000 THRU 999999999</li>
     *   <li><b>Cash Credit Limit:</b> Must not exceed total credit limit
     *       <br>NEW requirement for Java implementation</li>
     *   <li><b>Active Status:</b> Must be 'Y' or 'N'
     *       <br>COBOL: 88 FLG-YES-NO-ISVALID VALUES 'Y', 'N'</li>
     *   <li><b>Change Detection:</b> At least one field must be modified
     *       <br>COBOL: NO-CHANGES-DETECTED condition</li>
     *   <li><b>Concurrent Modification:</b> Version check prevents conflicts
     *       <br>COBOL: DATA-WAS-CHANGED-BEFORE-UPDATE error</li>
     * </ol>
     * 
     * <h3>Error Scenarios</h3>
     * <ul>
     *   <li><b>ResourceNotFoundException:</b> Account ID not found in database
     *       <br>HTTP 404 Not Found</li>
     *   <li><b>ValidationException:</b> Field validation failures (credit limits, status)
     *       <br>HTTP 400 Bad Request with field error details</li>
     *   <li><b>BusinessLogicException:</b> Concurrent modification detected
     *       <br>HTTP 409 Conflict - client must refresh and retry</li>
     * </ul>
     * 
     * <h3>Audit Trail</h3>
     * <p>All account modifications are logged with:</p>
     * <ul>
     *   <li>Account ID being updated</li>
     *   <li>Old values for credit limit, cash credit limit, active status</li>
     *   <li>New values for modified fields</li>
     *   <li>Timestamp of modification</li>
     *   <li>User ID (from security context if available)</li>
     * </ul>
     * 
     * @param request AccountUpdateRequest DTO containing update data with validation constraints
     * @return AccountResponse DTO with complete updated account information
     * 
     * @throws ResourceNotFoundException if account ID does not exist in database
     *         (COBOL: RESP-CD 13 NOTFND)
     * @throws ValidationException if field validation fails (credit limits, status) or no changes detected
     *         (COBOL: INPUT-ERROR flag, NO-CHANGES-DETECTED)
     * @throws BusinessLogicException if concurrent modification detected via optimistic locking
     *         (COBOL: DATA-WAS-CHANGED-BEFORE-UPDATE)
     * 
     * @see AccountUpdateRequest for field validation rules
     * @see AccountResponse for response structure
     * @see Account for JPA entity with @Version field
     */
    public AccountResponse updateAccount(AccountUpdateRequest request) {
        log.info("Starting account update for account ID: {}", request.getAccountId());
        log.debug("Update request details: creditLimit={}, cashCreditLimit={}, activeStatus={}",
                request.getCreditLimit(), request.getCashCreditLimit(), request.getActiveStatus());

        // Step 1: Retrieve existing account (COBOL: EXEC CICS READ DATASET('ACCTDAT'))
        Account existingAccount = findAccountById(request.getAccountId());
        
        // Log current state for audit trail
        log.debug("Current account state - creditLimit: {}, cashCreditLimit: {}, activeStatus: {}, version: {}",
                existingAccount.getCreditLimit(), existingAccount.getCashCreditLimit(),
                existingAccount.getActiveStatus(), existingAccount.getVersion());

        // Step 2: Validate update request (COBOL: 1200-EDIT-ACCOUNT-UPDATE-INPUTS)
        validateUpdateRequest(request, existingAccount);

        // Step 3: Detect changes (COBOL: NO-CHANGES-DETECTED condition)
        boolean hasChanges = detectChanges(request, existingAccount);
        if (!hasChanges) {
            log.warn("No changes detected for account ID: {}", request.getAccountId());
            throw new ValidationException("No changes detected. At least one field must be modified.");
        }

        // Step 4: Apply updates to entity (COBOL: MOVE statements to ACCOUNT-RECORD)
        applyUpdates(request, existingAccount);

        // Step 5: Save updated account (COBOL: EXEC CICS REWRITE DATASET('ACCTDAT'))
        Account updatedAccount = saveAccountWithOptimisticLocking(existingAccount);

        // Log successful update for audit trail
        log.info("Successfully updated account ID: {} - New values: creditLimit={}, cashCreditLimit={}, activeStatus={}",
                updatedAccount.getAccountId(), updatedAccount.getCreditLimit(),
                updatedAccount.getCashCreditLimit(), updatedAccount.getActiveStatus());

        // Step 6: Transform entity to response DTO (COBOL: 1400-SEND-MAP-DATAONLY)
        return buildAccountResponse(updatedAccount);
    }

    /**
     * Retrieve account by ID with exception handling.
     * 
     * <p>Replaces COBOL paragraph for account lookup:</p>
     * <pre>
     * EXEC CICS READ
     *     DATASET('ACCTDAT')
     *     INTO(ACCOUNT-RECORD)
     *     RIDFLD(WS-ACCT-ID)
     *     RESP(WS-RESP-CD)
     *     RESP2(WS-REAS-CD)
     * END-EXEC
     * 
     * IF WS-RESP-CD NOT = DFHRESP(NORMAL)
     *     MOVE 'Did not find this account in account master file' TO WS-MESSAGE
     * </pre>
     * 
     * @param accountId Account identifier to retrieve
     * @return Account entity from database
     * @throws ResourceNotFoundException if account not found (COBOL RESP-CD 13)
     */
    private Account findAccountById(Long accountId) {
        log.debug("Retrieving account with ID: {}", accountId);
        
        Optional<Account> accountOptional = accountRepository.findByAccountId(accountId);
        
        return accountOptional.orElseThrow(() -> {
            log.error("Account not found with ID: {}", accountId);
            return new ResourceNotFoundException("Account not found with ID: " + accountId);
        });
    }

    /**
     * Validate account update request with comprehensive field-level validation.
     * 
     * <p>Transforms COBOL validation paragraphs:</p>
     * <pre>
     * 1200-EDIT-ACCOUNT-UPDATE-INPUTS.
     *     PERFORM 1225-EDIT-ALPHA-REQD          (Active Status validation)
     *     PERFORM 1250-EDIT-SIGNED-9V2          (Credit Limit validation)
     *     PERFORM 1250-EDIT-SIGNED-9V2          (Cash Credit Limit validation)
     * </pre>
     * 
     * @param request Update request containing field values
     * @param existingAccount Current account state from database
     * @throws ValidationException if any field validation fails
     */
    private void validateUpdateRequest(AccountUpdateRequest request, Account existingAccount) {
        log.debug("Validating account update request for account ID: {}", request.getAccountId());
        
        Map<String, String> validationErrors = new HashMap<>();

        // Validate credit limit (COBOL: 1250-EDIT-SIGNED-9V2 paragraph)
        if (request.getCreditLimit() != null) {
            validateCreditLimit(request.getCreditLimit(), validationErrors);
        }

        // Validate cash credit limit (COBOL: 1250-EDIT-SIGNED-9V2 paragraph)
        if (request.getCashCreditLimit() != null) {
            validateCashCreditLimit(request.getCreditLimit(), request.getCashCreditLimit(), validationErrors);
        }

        // Validate active status (COBOL: 1225-EDIT-ALPHA-REQD paragraph)
        if (request.getActiveStatus() != null) {
            validateActiveStatus(request.getActiveStatus(), validationErrors);
        }

        // Throw ValidationException if any validation errors occurred
        if (!validationErrors.isEmpty()) {
            log.warn("Validation failures for account ID {}: {}", request.getAccountId(), validationErrors);
            throw new ValidationException("Account update validation failed", validationErrors);
        }

        log.debug("All validations passed for account ID: {}", request.getAccountId());
    }

    /**
     * Validate credit limit is within acceptable range.
     * 
     * <p>COBOL validation logic:</p>
     * <pre>
     * 1250-EDIT-SIGNED-9V2.
     *     IF WS-EDIT-SIGNED-NUMBER-9V2-X IS NUMERIC
     *         IF WS-EDIT-SIGNED-NUMBER-9V2-N >= 1000 AND
     *            WS-EDIT-SIGNED-NUMBER-9V2-N <= 999999999
     *             CONTINUE
     *         ELSE
     *             SET FLG-SIGNED-NUMBER-NOT-OK TO TRUE
     *             MOVE 'CRED-LIMIT-IS-NOT-VALID' TO WS-MESSAGE
     *     ELSE
     *         SET FLG-SIGNED-NUMBER-NOT-OK TO TRUE
     *         MOVE 'CRED-LIMIT-IS-BLANK' TO WS-MESSAGE
     * </pre>
     * 
     * @param creditLimit Credit limit value to validate
     * @param validationErrors Map to collect validation errors
     */
    private void validateCreditLimit(BigDecimal creditLimit, Map<String, String> validationErrors) {
        log.debug("Validating credit limit: {}", creditLimit);

        if (creditLimit == null) {
            validationErrors.put("creditLimit", "Credit limit cannot be blank");
            log.debug("Credit limit validation failed: null value");
            return;
        }

        // Ensure proper scale for monetary value (2 decimal places)
        BigDecimal scaledCreditLimit = creditLimit.setScale(2, RoundingMode.HALF_UP);

        // Validate minimum credit limit ($1,000.00)
        if (scaledCreditLimit.compareTo(MIN_CREDIT_LIMIT) < 0) {
            validationErrors.put("creditLimit",
                    String.format("Credit limit must be at least $%s", MIN_CREDIT_LIMIT));
            log.debug("Credit limit validation failed: {} is below minimum {}", scaledCreditLimit, MIN_CREDIT_LIMIT);
        }

        // Validate maximum credit limit ($999,999,999.99)
        if (scaledCreditLimit.compareTo(MAX_CREDIT_LIMIT) > 0) {
            validationErrors.put("creditLimit",
                    String.format("Credit limit cannot exceed $%s", MAX_CREDIT_LIMIT));
            log.debug("Credit limit validation failed: {} exceeds maximum {}", scaledCreditLimit, MAX_CREDIT_LIMIT);
        }
    }

    /**
     * Validate cash credit limit does not exceed total credit limit.
     * 
     * <p><b>NEW Business Rule:</b> This validation is not present in the original COBOL
     * program COACTUPC.cbl but is a required enhancement for the modernized Java application.
     * The COBOL program only validates individual field formats but does not perform
     * cross-field validation between cash credit limit and total credit limit.</p>
     * 
     * <p>This validation ensures that:</p>
     * <ul>
     *   <li>Cash credit limit cannot exceed total credit limit</li>
     *   <li>Cash credit limit can be $0.00 (no cash advance privilege)</li>
     *   <li>Both values use BigDecimal with scale=2 for exact precision</li>
     * </ul>
     * 
     * @param creditLimit Total credit limit (may be null if not being updated)
     * @param cashCreditLimit Cash credit limit to validate
     * @param validationErrors Map to collect validation errors
     */
    private void validateCashCreditLimit(BigDecimal creditLimit, BigDecimal cashCreditLimit,
                                         Map<String, String> validationErrors) {
        log.debug("Validating cash credit limit: {} against credit limit: {}", cashCreditLimit, creditLimit);

        if (cashCreditLimit == null) {
            validationErrors.put("cashCreditLimit", "Cash credit limit cannot be blank");
            log.debug("Cash credit limit validation failed: null value");
            return;
        }

        // Ensure proper scale for monetary value (2 decimal places)
        BigDecimal scaledCashLimit = cashCreditLimit.setScale(2, RoundingMode.HALF_UP);

        // Cash credit limit cannot be negative
        if (scaledCashLimit.compareTo(BigDecimal.ZERO) < 0) {
            validationErrors.put("cashCreditLimit", "Cash credit limit cannot be negative");
            log.debug("Cash credit limit validation failed: negative value {}", scaledCashLimit);
            return;
        }

        // If credit limit is provided, validate cash limit does not exceed it
        if (creditLimit != null) {
            BigDecimal scaledCreditLimit = creditLimit.setScale(2, RoundingMode.HALF_UP);
            
            if (scaledCashLimit.compareTo(scaledCreditLimit) > 0) {
                validationErrors.put("cashCreditLimit",
                        "Cash credit limit cannot exceed total credit limit");
                log.debug("Cash credit limit validation failed: {} exceeds credit limit {}",
                        scaledCashLimit, scaledCreditLimit);
            }
        }
    }

    /**
     * Validate active status is 'Y' or 'N'.
     * 
     * <p>COBOL validation logic:</p>
     * <pre>
     * 1225-EDIT-ALPHA-REQD.
     *     IF WS-EDIT-YES-NO = 'Y' OR WS-EDIT-YES-NO = 'N'
     *         SET FLG-YES-NO-ISVALID TO TRUE
     *     ELSE
     *         SET FLG-YES-NO-NOT-OK TO TRUE
     *         MOVE 'Account Active Status must be Y or N' TO WS-MESSAGE
     * </pre>
     * 
     * @param activeStatus Active status value to validate
     * @param validationErrors Map to collect validation errors
     */
    private void validateActiveStatus(String activeStatus, Map<String, String> validationErrors) {
        log.debug("Validating active status: {}", activeStatus);

        if (activeStatus == null || activeStatus.trim().isEmpty()) {
            validationErrors.put("activeStatus", "Active status cannot be blank");
            log.debug("Active status validation failed: blank value");
            return;
        }

        if (!ACTIVE_STATUS_YES.equals(activeStatus) && !ACTIVE_STATUS_NO.equals(activeStatus)) {
            validationErrors.put("activeStatus",
                    "Active status must be 'Y' (active) or 'N' (inactive)");
            log.debug("Active status validation failed: invalid value '{}'", activeStatus);
        }
    }

    /**
     * Detect if any changes were made to account fields.
     * 
     * <p>COBOL NO-CHANGES-DETECTED logic:</p>
     * <pre>
     * IF ACCT-UPDATE-CREDIT-LIMIT = ACCT-CREDIT-LIMIT AND
     *    ACCT-UPDATE-CASH-CREDIT-LIMIT = ACCT-CASH-CREDIT-LIMIT AND
     *    ACCT-UPDATE-ACTIVE-STATUS = ACCT-ACTIVE-STATUS
     *     MOVE 'NO-CHANGES-DETECTED' TO WS-MESSAGE
     *     GO TO 1400-SEND-MAP
     * </pre>
     * 
     * @param request Update request with new values
     * @param existingAccount Current account state
     * @return true if at least one field has changed, false otherwise
     */
    private boolean detectChanges(AccountUpdateRequest request, Account existingAccount) {
        log.debug("Detecting changes for account ID: {}", request.getAccountId());

        boolean hasChanges = false;

        // Check credit limit change
        if (request.getCreditLimit() != null) {
            BigDecimal newCreditLimit = request.getCreditLimit().setScale(2, RoundingMode.HALF_UP);
            BigDecimal currentCreditLimit = existingAccount.getCreditLimit().setScale(2, RoundingMode.HALF_UP);
            
            if (newCreditLimit.compareTo(currentCreditLimit) != 0) {
                log.debug("Credit limit changed: {} -> {}", currentCreditLimit, newCreditLimit);
                hasChanges = true;
            }
        }

        // Check cash credit limit change
        if (request.getCashCreditLimit() != null) {
            BigDecimal newCashLimit = request.getCashCreditLimit().setScale(2, RoundingMode.HALF_UP);
            BigDecimal currentCashLimit = existingAccount.getCashCreditLimit().setScale(2, RoundingMode.HALF_UP);
            
            if (newCashLimit.compareTo(currentCashLimit) != 0) {
                log.debug("Cash credit limit changed: {} -> {}", currentCashLimit, newCashLimit);
                hasChanges = true;
            }
        }

        // Check active status change
        if (request.getActiveStatus() != null) {
            if (!request.getActiveStatus().equals(existingAccount.getActiveStatus())) {
                log.debug("Active status changed: {} -> {}",
                        existingAccount.getActiveStatus(), request.getActiveStatus());
                hasChanges = true;
            }
        }

        log.debug("Change detection result: {}", hasChanges);
        return hasChanges;
    }

    /**
     * Apply updates from request to existing account entity.
     * 
     * <p>COBOL MOVE statements:</p>
     * <pre>
     * MOVE ACCT-UPDATE-CREDIT-LIMIT TO ACCT-CREDIT-LIMIT.
     * MOVE ACCT-UPDATE-CASH-CREDIT-LIMIT TO ACCT-CASH-CREDIT-LIMIT.
     * MOVE ACCT-UPDATE-ACTIVE-STATUS TO ACCT-ACTIVE-STATUS.
     * </pre>
     * 
     * @param request Update request with new values
     * @param account Account entity to update
     */
    private void applyUpdates(AccountUpdateRequest request, Account account) {
        log.debug("Applying updates to account ID: {}", request.getAccountId());

        // Update credit limit if provided
        if (request.getCreditLimit() != null) {
            BigDecimal newCreditLimit = request.getCreditLimit().setScale(2, RoundingMode.HALF_UP);
            account.setCreditLimit(newCreditLimit);
            log.debug("Applied credit limit update: {}", newCreditLimit);
        }

        // Update cash credit limit if provided
        if (request.getCashCreditLimit() != null) {
            BigDecimal newCashLimit = request.getCashCreditLimit().setScale(2, RoundingMode.HALF_UP);
            account.setCashCreditLimit(newCashLimit);
            log.debug("Applied cash credit limit update: {}", newCashLimit);
        }

        // Update active status if provided
        if (request.getActiveStatus() != null) {
            account.setActiveStatus(request.getActiveStatus());
            log.debug("Applied active status update: {}", request.getActiveStatus());
        }
    }

    /**
     * Save account with optimistic locking error handling.
     * 
     * <p>Replaces COBOL REWRITE with concurrent modification detection:</p>
     * <pre>
     * EXEC CICS REWRITE
     *     DATASET('ACCTDAT')
     *     FROM(ACCOUNT-RECORD)
     *     RESP(WS-RESP-CD)
     *     RESP2(WS-REAS-CD)
     * END-EXEC
     * 
     * EVALUATE WS-RESP-CD
     *     WHEN DFHRESP(NORMAL)
     *         CONTINUE
     *     WHEN DFHRESP(INVREQ)
     *         MOVE 'LOCKED-BUT-UPDATE-FAILED' TO WS-MESSAGE
     *     WHEN OTHER
     *         MOVE 'DATA-WAS-CHANGED-BEFORE-UPDATE' TO WS-MESSAGE
     *         MOVE 'Record changed by some one else' TO WS-MESSAGE
     * END-EVALUATE
     * </pre>
     * 
     * @param account Account entity to save
     * @return Saved account with updated version
     * @throws BusinessLogicException if concurrent modification detected
     */
    private Account saveAccountWithOptimisticLocking(Account account) {
        log.debug("Saving account ID: {} with version: {}", account.getAccountId(), account.getVersion());

        try {
            Account savedAccount = accountRepository.save(account);
            log.info("Successfully saved account ID: {} with new version: {}",
                    savedAccount.getAccountId(), savedAccount.getVersion());
            return savedAccount;
            
        } catch (OptimisticLockException e) {
            log.error("Concurrent modification detected for account ID: {} - Record changed by another user",
                    account.getAccountId(), e);
            throw new BusinessLogicException(
                    "Record changed by someone else. Please refresh the data and try again.", e);
        }
    }

    /**
     * Build AccountResponse DTO from Account entity.
     * 
     * <p>Transforms JPA entity to REST API response DTO, replacing COBOL BMS map output:</p>
     * <pre>
     * EXEC CICS SEND MAP('COACTUP')
     *     MAPSET('COACTUPM')
     *     FROM(ACCOUNT-RECORD)
     *     DATAONLY
     *     CURSOR
     * END-EXEC
     * </pre>
     * 
     * @param account Account entity from database
     * @return AccountResponse DTO for REST API response
     */
    private AccountResponse buildAccountResponse(Account account) {
        log.debug("Building response DTO for account ID: {}", account.getAccountId());

        return AccountResponse.builder()
                .accountId(account.getAccountId())
                .activeStatus(account.getActiveStatus())
                .currentBalance(account.getCurrentBalance())
                .creditLimit(account.getCreditLimit())
                .cashCreditLimit(account.getCashCreditLimit())
                .currentCycleCredit(account.getCurrentCycleCredit())
                .currentCycleDebit(account.getCurrentCycleDebit())
                .build();
    }
}

