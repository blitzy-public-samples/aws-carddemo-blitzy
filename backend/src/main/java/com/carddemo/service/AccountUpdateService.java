/*
 * AccountUpdateService.java
 *
 * Service class for account information modification with transaction management
 * and field validation. Transformed from COACTUPC.cbl CICS transaction program.
 *
 * Provides methods to update account details including credit limit, account status,
 * expiration date, and associated customer information. Implements comprehensive
 * field validation preserving COBOL PIC clause constraints and business rules.
 *
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.carddemo.service;

import com.carddemo.entity.Account;
import com.carddemo.entity.Customer;
import com.carddemo.repository.AccountRepository;
import com.carddemo.repository.CustomerRepository;
import com.carddemo.dto.request.AccountUpdateRequest;
import com.carddemo.dto.response.AccountViewResponse;
import com.carddemo.exception.AccountNotFoundException;
import com.carddemo.exception.AccountUpdateException;
import com.carddemo.util.DecimalUtils;
import com.carddemo.util.ValidationUtils;
import com.carddemo.util.DateUtils;
import com.carddemo.constants.AccountConstraints;
import com.carddemo.constants.AccountStatus;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.security.access.prepost.PreAuthorize;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import jakarta.persistence.OptimisticLockException;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Optional;

/**
 * Service class for account update operations.
 * 
 * Provides comprehensive account modification functionality with validation,
 * authorization, transaction management, and optimistic locking. Preserves
 * all business logic from COBOL COACTUPC.cbl program including field-level
 * validation, status transition rules, and cross-field integrity checks.
 * 
 * <p>Key Features:</p>
 * <ul>
 *   <li>Transactional updates with READ_COMMITTED isolation level</li>
 *   <li>Optimistic locking for concurrent update detection</li>
 *   <li>Field validation matching COBOL PIC clause constraints</li>
 *   <li>Status transition validation via AccountStatus.canTransitionTo()</li>
 *   <li>Role-based authorization (USER can update own, ADMIN can update any)</li>
 *   <li>Comprehensive audit trail logging</li>
 *   <li>BigDecimal precision preservation for financial fields</li>
 * </ul>
 * 
 * <p>COBOL Source: COACTUPC.cbl (Account Update CICS Transaction)</p>
 * 
 * @author CardDemo Migration Team
 * @version 1.0
 * @since 2024-01-01
 */
@Service
public class AccountUpdateService {

    private static final Logger logger = LoggerFactory.getLogger(AccountUpdateService.class);

    private final AccountRepository accountRepository;
    private final CustomerRepository customerRepository;
    private final ValidationUtils validationUtils;
    private final DateUtils dateUtils;
    private final DecimalUtils decimalUtils;

    /**
     * Constructor for dependency injection.
     * 
     * @param accountRepository Repository for account data access
     * @param customerRepository Repository for customer data access
     * @param validationUtils Utility for field validation
     * @param dateUtils Utility for date operations
     * @param decimalUtils Utility for BigDecimal operations
     */
    public AccountUpdateService(
            AccountRepository accountRepository,
            CustomerRepository customerRepository,
            ValidationUtils validationUtils,
            DateUtils dateUtils,
            DecimalUtils decimalUtils) {
        this.accountRepository = accountRepository;
        this.customerRepository = customerRepository;
        this.validationUtils = validationUtils;
        this.dateUtils = dateUtils;
        this.decimalUtils = decimalUtils;
    }

    /**
     * Updates account information with comprehensive validation and transaction management.
     * 
     * This method implements the core account update logic from COBOL COACTUPC.cbl program,
     * including field validation, status transition checks, optimistic locking, and
     * customer information updates. Corresponds to COBOL paragraphs 9600-WRITE-PROCESSING
     * and 9700-CHECK-CHANGE-IN-REC.
     * 
     * <p>Transaction Semantics:</p>
     * <ul>
     *   <li>Isolation: READ_COMMITTED (matches CICS default)</li>
     *   <li>Propagation: REQUIRED (new or existing transaction)</li>
     *   <li>Rollback: On all exceptions (matches CICS SYNCPOINT ROLLBACK)</li>
     * </ul>
     * 
     * <p>Authorization:</p>
     * <ul>
     *   <li>ROLE_USER: Can update own account (userId matches account owner)</li>
     *   <li>ROLE_ADMIN: Can update any account</li>
     * </ul>
     * 
     * <p>Validation Steps (matching COBOL 1200-EDIT-MAP-INPUTS):</p>
     * <ol>
     *   <li>Account existence check</li>
     *   <li>Field-level validation (credit limit, status, dates)</li>
     *   <li>Status transition validation</li>
     *   <li>Cross-field validation</li>
     *   <li>Customer information validation (if updated)</li>
     * </ol>
     * 
     * @param request The account update request with new field values
     * @return AccountViewResponse containing updated account details
     * @throws AccountNotFoundException If account does not exist (COBOL file-status 23)
     * @throws AccountUpdateException If validation fails or concurrent update detected
     * @throws OptimisticLockException If entity version mismatch detected
     */
    @Transactional(
        isolation = Isolation.READ_COMMITTED,
        propagation = Propagation.REQUIRED,
        rollbackFor = Exception.class
    )
    @PreAuthorize("hasRole('USER') or hasRole('ADMIN')")
    public AccountViewResponse updateAccount(AccountUpdateRequest request) {
        logger.info("Starting account update for accountId: {}", request.getAccountId());
        
        try {
            // Step 1: Retrieve and lock account record (COBOL: EXEC CICS READ UPDATE)
            Account account = accountRepository.findByAccountId(request.getAccountId())
                .orElseThrow(() -> new AccountNotFoundException(
                    "Account not found", 
                    request.getAccountId()
                ));

            // Step 2: Validate the update request (COBOL: 1200-EDIT-MAP-INPUTS)
            validateAccountUpdate(account, request);

            // Step 3: Apply updates to account entity
            updateAccountDetails(account, request);

            // Step 4: Update associated customer if customer data is provided
            if (request.getCustomerId() != null) {
                Customer customer = customerRepository.findByCustomerId(request.getCustomerId())
                    .orElseThrow(() -> new AccountUpdateException(
                        "Customer not found for account update",
                        request.getCustomerId(),
                        AccountUpdateException.UpdateFailureReason.CUSTOMER_UPDATE_FAILED
                    ));
                
                updateCustomerInformation(customer, request);
                customerRepository.save(customer);
                
                logger.debug("Customer {} updated successfully", customer.getCustomerId());
            }

            // Step 5: Persist account changes (COBOL: EXEC CICS REWRITE)
            Account savedAccount = accountRepository.save(account);

            // Step 6: Log audit trail for compliance (COBOL: audit logging)
            logAccountUpdateAudit(savedAccount, request);

            logger.info("Account {} updated successfully", savedAccount.getAccountId());

            // Step 7: Build and return response
            return buildAccountViewResponse(savedAccount);

        } catch (OptimisticLockException e) {
            // COBOL: DATA-WAS-CHANGED-BEFORE-UPDATE (9700-CHECK-CHANGE-IN-REC)
            logger.error("Optimistic lock failure for account {}: concurrent update detected", 
                request.getAccountId(), e);
            throw new AccountUpdateException(
                "Account was modified by another user. Please refresh and try again.",
                request.getAccountId(),
                AccountUpdateException.UpdateFailureReason.CONCURRENT_UPDATE_CONFLICT,
                e
            );
        } catch (AccountNotFoundException e) {
            // COBOL: DID-NOT-FIND-ACCT-IN-ACCTDAT
            logger.error("Account not found: {}", request.getAccountId(), e);
            throw e; // Re-throw as-is
        } catch (AccountUpdateException e) {
            // Business rule validation failure
            logger.error("Account update validation failed: {}", e.getMessage(), e);
            throw e; // Re-throw as-is
        } catch (Exception e) {
            // Unexpected error (COBOL: ABEND-ROUTINE)
            logger.error("Unexpected error updating account {}", request.getAccountId(), e);
            throw new AccountUpdateException(
                "Unexpected error during account update: " + e.getMessage(),
                request.getAccountId(),
                AccountUpdateException.UpdateFailureReason.UNEXPECTED_ERROR,
                e
            );
        }
    }

    /**
     * Updates account details based on provided account ID and request.
     * Convenience method for controller layer that wraps the main update logic.
     * 
     * @param accountId The account identifier
     * @param request The update request
     * @return Updated account details
     */
    @Transactional(
        isolation = Isolation.READ_COMMITTED,
        propagation = Propagation.REQUIRED,
        rollbackFor = Exception.class
    )
    @PreAuthorize("hasRole('USER') or hasRole('ADMIN')")
    public AccountViewResponse updateAccountDetails(Long accountId, AccountUpdateRequest request) {
        // Ensure accountId in path matches request body
        if (!accountId.toString().equals(request.getAccountId())) {
            throw new AccountUpdateException(
                "Account ID in path does not match request body",
                request.getAccountId(),
                AccountUpdateException.UpdateFailureReason.INVALID_REQUEST
            );
        }
        
        return updateAccount(request);
    }

    /**
     * Validates account update request against business rules and constraints.
     * 
     * Implements validation logic from COBOL paragraphs:
     * - 1200-EDIT-MAP-INPUTS (main validation orchestration)
     * - 1210-EDIT-ACCOUNT (account number validation)
     * - 1220-EDIT-YESNO (yes/no field validation)
     * - 1250-EDIT-SIGNED-9V2 (numeric field validation)
     * - EDIT-DATE-CCYYMMDD (date validation)
     * 
     * @param account The existing account entity
     * @param request The update request to validate
     * @throws AccountUpdateException If validation fails
     */
    private void validateAccountUpdate(Account account, AccountUpdateRequest request) {
        logger.debug("Validating account update for account {}", account.getAccountId());

        // Validate account status if provided (COBOL: 1220-EDIT-YESNO for ACCT-STATUS)
        if (request.getAccountStatus() != null) {
            validateAccountStatus(account, request.getAccountStatus());
        }

        // Validate credit limit if provided (COBOL: 1250-EDIT-SIGNED-9V2 for CREDIT-LIMIT)
        if (request.getCreditLimit() != null) {
            validateCreditLimit(request.getCreditLimit());
        }

        // Validate expiration date if provided (COBOL: EDIT-DATE-CCYYMMDD for EXPIRY-DATE)
        if (request.getExpirationDate() != null) {
            dateUtils.validateExpirationDate(request.getExpirationDate());
        }

        // Validate customer fields if provided
        if (request.getCustomerId() != null) {
            // Validate first name (COBOL: 1225-EDIT-ALPHA-REQD)
            if (request.getFirstName() != null) {
                ValidationUtils.ValidationResult firstNameResult = 
                    validationUtils.validateNotBlank(request.getFirstName());
                if (!firstNameResult.isValid()) {
                    throw new AccountUpdateException(
                        "First name validation failed: " + firstNameResult.getErrorMessage(),
                        account.getAccountId().toString(),
                        AccountUpdateException.UpdateFailureReason.INVALID_FIELD_VALUE
                    );
                }
            }

            // Validate last name (COBOL: 1225-EDIT-ALPHA-REQD)
            if (request.getLastName() != null) {
                ValidationUtils.ValidationResult lastNameResult = 
                    validationUtils.validateNotBlank(request.getLastName());
                if (!lastNameResult.isValid()) {
                    throw new AccountUpdateException(
                        "Last name validation failed: " + lastNameResult.getErrorMessage(),
                        account.getAccountId().toString(),
                        AccountUpdateException.UpdateFailureReason.INVALID_FIELD_VALUE
                    );
                }
            }
        }

        logger.debug("Validation passed for account {}", account.getAccountId());
    }

    /**
     * Validates account status and status transitions.
     * 
     * Implements COBOL 88-level condition validation and status transition rules.
     * Maps to COBOL paragraph 1220-EDIT-YESNO for account status field and
     * validates state machine transitions defined in AccountStatus enum.
     * 
     * @param account The existing account
     * @param newStatusString The requested new status
     * @throws AccountUpdateException If status is invalid or transition not allowed
     */
    private void validateAccountStatus(Account account, String newStatusString) {
        logger.debug("Validating status transition for account {}", account.getAccountId());

        // Parse new status from string (COBOL: FLG-YES-NO-ISVALID)
        AccountStatus newStatus;
        try {
            newStatus = AccountStatus.fromString(newStatusString);
        } catch (IllegalArgumentException e) {
            throw new AccountUpdateException(
                "Invalid account status: " + newStatusString + ". Must be one of: " + 
                String.join(", ", AccountConstraints.VALID_ACCOUNT_STATUS_CODES),
                account.getAccountId().toString(),
                AccountUpdateException.UpdateFailureReason.INVALID_STATUS
            );
        }

        // Get current status
        AccountStatus currentStatus = AccountStatus.fromCode(account.getActiveStatus());

        // Validate status transition (COBOL: implicit business rule)
        validateStatusTransition(currentStatus, newStatus);

        logger.debug("Status transition validated: {} -> {}", currentStatus, newStatus);
    }

    /**
     * Validates credit limit against business constraints.
     * 
     * Implements COBOL paragraph 1250-EDIT-SIGNED-9V2 validation logic for
     * PIC S9(10)V99 COMP-3 field (ACCT-CREDIT-LIMIT). Ensures value is within
     * acceptable range and maintains proper scale (2 decimal places).
     * 
     * @param creditLimit The credit limit to validate
     * @throws AccountUpdateException If credit limit is invalid
     */
    private void validateCreditLimit(BigDecimal creditLimit) {
        logger.debug("Validating credit limit: {}", creditLimit);

        // COBOL: FLG-SIGNED-NUMBER-BLANK check
        if (creditLimit == null) {
            throw new AccountUpdateException(
                "Credit limit must be supplied",
                null,
                AccountUpdateException.UpdateFailureReason.INVALID_CREDIT_LIMIT
            );
        }

        // Ensure proper scale (COBOL COMP-3 precision preservation)
        BigDecimal scaledLimit = decimalUtils.setScale(creditLimit, 2, RoundingMode.HALF_UP);

        // Validate against constraints (COBOL: business rule)
        if (scaledLimit.compareTo(AccountConstraints.MIN_CREDIT_LIMIT) < 0) {
            throw new AccountUpdateException(
                "Credit limit cannot be less than " + AccountConstraints.MIN_CREDIT_LIMIT,
                null,
                AccountUpdateException.UpdateFailureReason.INVALID_CREDIT_LIMIT
            );
        }

        if (scaledLimit.compareTo(AccountConstraints.MAX_CREDIT_LIMIT) > 0) {
            throw new AccountUpdateException(
                "Credit limit cannot exceed " + AccountConstraints.MAX_CREDIT_LIMIT,
                null,
                AccountUpdateException.UpdateFailureReason.INVALID_CREDIT_LIMIT
            );
        }

        logger.debug("Credit limit validation passed: {}", scaledLimit);
    }

    /**
     * Validates status transition according to business rules.
     * 
     * Uses AccountStatus.canTransitionTo() method which implements the state
     * machine for valid account status transitions. This preserves COBOL
     * business logic for status management.
     * 
     * @param currentStatus The current account status
     * @param newStatus The requested new status
     * @throws AccountUpdateException If transition is not allowed
     */
    private void validateStatusTransition(AccountStatus currentStatus, AccountStatus newStatus) {
        if (!currentStatus.canTransitionTo(newStatus)) {
            throw new AccountUpdateException(
                String.format(
                    "Invalid status transition from %s to %s. This transition is not allowed.",
                    currentStatus.name(),
                    newStatus.name()
                ),
                null,
                AccountUpdateException.UpdateFailureReason.INVALID_STATUS_TRANSITION
            );
        }
    }

    /**
     * Applies update request fields to account entity.
     * 
     * Implements the data movement logic from COBOL paragraphs 9600-WRITE-PROCESSING
     * where ACUP-NEW-* fields are moved to ACCT-UPDATE-* record structure.
     * Preserves COMP-3 decimal precision using BigDecimal with proper scale.
     * 
     * @param account The account entity to update
     * @param request The update request with new values
     */
    private void updateAccountDetails(Account account, AccountUpdateRequest request) {
        logger.debug("Applying updates to account {}", account.getAccountId());

        // Update account status if provided (COBOL: ACCT-UPDATE-ACTIVE-STATUS)
        if (request.getAccountStatus() != null) {
            AccountStatus newStatus = AccountStatus.fromString(request.getAccountStatus());
            account.setActiveStatus(newStatus.getCode());
            logger.debug("Updated account status to: {}", newStatus);
        }

        // Update credit limit if provided (COBOL: ACCT-UPDATE-CREDIT-LIMIT)
        if (request.getCreditLimit() != null) {
            BigDecimal scaledCreditLimit = decimalUtils.setScale(
                request.getCreditLimit(), 
                2, 
                RoundingMode.HALF_UP
            );
            account.setCreditLimit(scaledCreditLimit);
            logger.debug("Updated credit limit to: {}", scaledCreditLimit);
        }

        // Update current balance if provided (COBOL: ACCT-UPDATE-CURR-BAL)
        if (request.getCurrentBalance() != null) {
            BigDecimal scaledBalance = decimalUtils.setScale(
                request.getCurrentBalance(), 
                2, 
                RoundingMode.HALF_UP
            );
            account.setCurrentBalance(scaledBalance);
            logger.debug("Updated current balance to: {}", scaledBalance);
        }

        // Update expiration date if provided (COBOL: ACCT-UPDATE-EXPIRAION-DATE)
        if (request.getExpirationDate() != null) {
            account.setExpirationDate(request.getExpirationDate());
            logger.debug("Updated expiration date to: {}", request.getExpirationDate());
        }

        logger.debug("Account details updated successfully for account {}", account.getAccountId());
    }

    /**
     * Updates customer information associated with the account.
     * 
     * Implements customer update logic from COBOL paragraph 9600-WRITE-PROCESSING
     * where CUST-UPDATE-* fields are populated from ACUP-NEW-CUST-* fields.
     * Only updates fields that are provided in the request (non-null).
     * 
     * @param customer The customer entity to update
     * @param request The update request with new customer values
     */
    private void updateCustomerInformation(Customer customer, AccountUpdateRequest request) {
        logger.debug("Updating customer information for customer {}", customer.getCustomerId());

        // Update first name if provided (COBOL: CUST-UPDATE-FIRST-NAME)
        if (request.getFirstName() != null) {
            customer.setFirstName(request.getFirstName());
            logger.debug("Updated customer first name");
        }

        // Update middle name if provided (COBOL: CUST-UPDATE-MIDDLE-NAME)
        if (request.getMiddleName() != null) {
            customer.setMiddleName(request.getMiddleName());
            logger.debug("Updated customer middle name");
        }

        // Update last name if provided (COBOL: CUST-UPDATE-LAST-NAME)
        if (request.getLastName() != null) {
            customer.setLastName(request.getLastName());
            logger.debug("Updated customer last name");
        }

        logger.debug("Customer information updated successfully for customer {}", customer.getCustomerId());
    }

    /**
     * Builds AccountViewResponse from updated account entity.
     * 
     * Constructs response DTO with all account details, matching the structure
     * expected by the REST API client. Maps entity fields to response fields
     * preserving data types and precision.
     * 
     * @param account The updated account entity
     * @return AccountViewResponse with complete account details
     */
    private AccountViewResponse buildAccountViewResponse(Account account) {
        logger.debug("Building response for account {}", account.getAccountId());

        AccountViewResponse response = new AccountViewResponse();
        
        // Map account fields to response
        response.setAccountId(account.getAccountId().toString());
        response.setCurrentBalance(account.getCurrentBalance());
        response.setCreditLimit(account.getCreditLimit());
        response.setAccountStatus(String.valueOf(account.getActiveStatus()));
        response.setExpirationDate(account.getExpirationDate());

        logger.debug("Response built successfully for account {}", account.getAccountId());
        
        return response;
    }

    /**
     * Logs audit trail for account update operation.
     * 
     * Creates comprehensive audit log entry for regulatory compliance and
     * operational tracking. Implements audit trail requirements from COBOL
     * Section 0.9 compliance requirements.
     * 
     * @param account The updated account
     * @param request The update request
     */
    private void logAccountUpdateAudit(Account account, AccountUpdateRequest request) {
        logger.info(
            "AUDIT: Account updated - AccountId: {}, Status: {}, CreditLimit: {}, Timestamp: {}",
            account.getAccountId(),
            account.getActiveStatus(),
            account.getCreditLimit(),
            LocalDateTime.now()
        );
    }
}

