/*
 * AccountService.java
 *
 * Account management service handling business logic from COBOL programs:
 * - COACTUPC.cbl (account update and maintenance)
 * - COACTVWC.cbl (account view and inquiry)
 *
 * Converted from COBOL PROCEDURE DIVISION business logic with COBOL COMP-3 precision
 * preservation using BigDecimal arithmetic per Agent Action Plan Section 0.7.2.
 *
 * Original COBOL files:
 * - Source: app/cbl/COACTUPC.cbl (186KB, complex business rules for account updates)
 * - Source: app/cbl/COACTVWC.cbl (account view display logic)
 * - Copybook: app/cpy/CVACT01Y.cpy (ACCOUNT-RECORD structure, 300-byte record)
 *
 * Key COBOL-to-Java transformations:
 * 1. EXEC CICS READ FILE('ACCTFILE') → accountRepository.findById()
 * 2. EXEC CICS REWRITE FILE('ACCTFILE') → accountRepository.save() with version check
 * 3. EXEC CICS SYNCPOINT → @Transactional annotation
 * 4. COBOL PIC S9(10)V99 COMP-3 → BigDecimal with scale 2, RoundingMode.HALF_UP
 * 5. COBOL validation flags → ValidationService methods
 *
 * Business Rules Preserved:
 * - Credit limit must not be less than current balance (COACTUPC line 196-199)
 * - Credit limit must not exceed $50,000.00 maximum
 * - Account balance calculated from current balance + cycle credit - cycle debit
 * - All financial calculations use BigDecimal.HALF_UP rounding
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
package com.carddemo.service;

import com.carddemo.exception.BusinessException;
import com.carddemo.exception.DataNotFoundException;
import com.carddemo.exception.ValidationException;
import com.carddemo.model.dto.AccountDto;
import com.carddemo.model.entity.Account;
import com.carddemo.repository.AccountRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Account management service implementing business logic from COBOL programs
 * COACTUPC.cbl and COACTVWC.cbl.
 * 
 * <p>This service replaces COBOL PROCEDURE DIVISION logic for account operations:</p>
 * <ul>
 *   <li><b>Account View:</b> Read account details (COACTVWC.cbl → getAccountById)</li>
 *   <li><b>Account Update:</b> Modify account with validation (COACTUPC.cbl → updateAccount)</li>
 *   <li><b>Account Create:</b> Add new account (COACTUPC.cbl → createAccount)</li>
 *   <li><b>Balance Calculation:</b> Compute current balance (COBOL COMPUTE → calculateCurrentBalance)</li>
 *   <li><b>Credit Limit Validation:</b> Business rules (COACTUPC line 196-199 → validateCreditLimit)</li>
 * </ul>
 * 
 * <h3>COBOL to Java Method Mapping:</h3>
 * <table border="1">
 *   <tr>
 *     <th>COBOL Program</th>
 *     <th>COBOL Paragraph</th>
 *     <th>Java Method</th>
 *   </tr>
 *   <tr>
 *     <td>COACTVWC.cbl</td>
 *     <td>READ-ACCOUNT-RECORD</td>
 *     <td>getAccountById(Long accountId)</td>
 *   </tr>
 *   <tr>
 *     <td>COACTUPC.cbl</td>
 *     <td>UPDATE-ACCOUNT-RECORD</td>
 *     <td>updateAccount(Long accountId, AccountDto accountDto)</td>
 *   </tr>
 *   <tr>
 *     <td>COACTUPC.cbl</td>
 *     <td>VALIDATE-CREDIT-LIMIT</td>
 *     <td>validateCreditLimit(BigDecimal, BigDecimal)</td>
 *   </tr>
 *   <tr>
 *     <td>COACTUPC.cbl</td>
 *     <td>CALCULATE-BALANCE</td>
 *     <td>calculateCurrentBalance(Account)</td>
 *   </tr>
 * </table>
 * 
 * <h3>Transaction Management:</h3>
 * <p>Spring @Transactional annotations replace COBOL EXEC CICS SYNCPOINT commands:</p>
 * <ul>
 *   <li><b>@Transactional(readOnly=true):</b> Read operations (COACTVWC.cbl patterns)</li>
 *   <li><b>@Transactional:</b> Write operations with automatic rollback on exception</li>
 *   <li><b>Optimistic Locking:</b> JPA @Version field replicates VSAM RBA locking</li>
 * </ul>
 * 
 * <h3>Data Precision Requirements:</h3>
 * <p>Per Agent Action Plan Section 0.7.2, all financial calculations must maintain
 * COBOL COMP-3 packed decimal precision:</p>
 * <ul>
 *   <li>Use BigDecimal for all currency amounts</li>
 *   <li>Scale fixed at 2 decimal places</li>
 *   <li>RoundingMode.HALF_UP matches COBOL rounding behavior</li>
 *   <li>Calculations must produce bit-identical results to COBOL</li>
 * </ul>
 * 
 * @see Account
 * @see AccountDto
 * @see AccountRepository
 * @see ValidationService
 * @author CardDemo Modernization Team
 * @version 1.0
 * @since 2024
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AccountService {

    private final AccountRepository accountRepository;
    private final ValidationService validationService;

    /**
     * Retrieve account by ID with complete account details.
     * 
     * <p>Converted from COBOL program COACTVWC.cbl:</p>
     * <pre>
     * EXEC CICS READ FILE('ACCTFILE')
     *      INTO(ACCOUNT-RECORD)
     *      RIDFLD(ACCT-ID)
     *      RESP(WS-RESP-CD)
     *      RESP2(WS-REAS-CD)
     * END-EXEC
     * </pre>
     * 
     * <p>Validation rules:</p>
     * <ul>
     *   <li>Account ID must be valid 11-digit number</li>
     *   <li>Account must exist in database</li>
     * </ul>
     * 
     * <p>Error handling:</p>
     * <ul>
     *   <li>ValidationException: Invalid account ID format</li>
     *   <li>DataNotFoundException: Account not found (COBOL file-status 23)</li>
     * </ul>
     * 
     * @param accountId The 11-digit account identifier (COBOL PIC 9(11))
     * @return AccountDto with complete account details
     * @throws ValidationException if accountId is invalid
     * @throws DataNotFoundException if account does not exist
     */
    @Transactional(readOnly = true)
    public AccountDto getAccountById(Long accountId) {
        log.info("Getting account by ID: {}", accountId);
        
        // Validate account ID format (replaces COBOL FLG-ACCTFILTER-NOT-OK)
        validationService.validateAccountId(accountId);
        
        // Read account from repository (replaces EXEC CICS READ FILE('ACCTFILE'))
        Account account = accountRepository.findById(accountId)
                .orElseThrow(() -> {
                    log.warn("Account not found: {}", accountId);
                    return new DataNotFoundException("Account", accountId);
                });
        
        log.debug("Account retrieved successfully: {}", accountId);
        return mapToDto(account);
    }

    /**
     * Update account with credit limit validation and balance calculation.
     * 
     * <p>Converted from COBOL program COACTUPC.cbl (186KB file with complex business rules).
     * Implements exact business logic from COBOL UPDATE-ACCOUNT-RECORD paragraph:</p>
     * <pre>
     * EXEC CICS READ FILE('ACCTFILE')
     *      INTO(ACCOUNT-RECORD)
     *      RIDFLD(ACCT-ID)
     *      UPDATE
     * END-EXEC
     * 
     * [Validation and update logic]
     * 
     * EXEC CICS REWRITE FILE('ACCTFILE')
     *      FROM(ACCOUNT-RECORD)
     * END-EXEC
     * 
     * EXEC CICS SYNCPOINT
     * END-EXEC
     * </pre>
     * 
     * <p>Business rules enforced (from COACTUPC.cbl line 196-199):</p>
     * <ul>
     *   <li>Credit limit cannot be less than current balance</li>
     *   <li>Credit limit cannot exceed maximum ($50,000.00)</li>
     *   <li>Credit limit must be positive</li>
     *   <li>All currency fields use BigDecimal with scale 2</li>
     * </ul>
     * 
     * <p>Fields updated:</p>
     * <ul>
     *   <li>acctCreditLimit - Purchase credit limit</li>
     *   <li>acctCashCreditLimit - Cash advance limit</li>
     *   <li>acctActiveStatus - Account status (Y/N/C/S)</li>
     *   <li>acctExpirationDate - Account expiration date</li>
     *   <li>acctAddrZip - Billing address ZIP code</li>
     *   <li>acctGroupId - Account group identifier</li>
     * </ul>
     * 
     * @param accountId The account ID to update
     * @param accountDto DTO containing updated account data
     * @return Updated AccountDto
     * @throws ValidationException if input data is invalid
     * @throws DataNotFoundException if account does not exist
     * @throws BusinessException if business rules violated (e.g., credit limit < balance)
     */
    @Transactional
    public AccountDto updateAccount(Long accountId, AccountDto accountDto) {
        log.info("Updating account: {}", accountId);
        
        // Validate account ID (replaces COBOL FLG-ACCTFILTER-NOT-OK)
        validationService.validateAccountId(accountId);
        
        // Read existing account with FOR UPDATE lock (replaces EXEC CICS READ UPDATE)
        Account existingAccount = accountRepository.findById(accountId)
                .orElseThrow(() -> {
                    log.warn("Account not found for update: {}", accountId);
                    return new DataNotFoundException("Account", accountId);
                });
        
        // Optimistic locking check: Verify version matches if provided
        // Replaces COBOL VSAM RBA (Relative Byte Address) check from COACTUPC.cbl
        log.debug("Version check - DTO version: {}, Entity version: {}", 
                accountDto.getVersion(), existingAccount.getVersion());
        if (accountDto.getVersion() != null && !accountDto.getVersion().equals(existingAccount.getVersion())) {
            log.warn("Version mismatch for account {}: expected {}, got {}",
                    accountId, existingAccount.getVersion(), accountDto.getVersion());
            throw new jakarta.persistence.OptimisticLockException(
                    "Account was modified by another transaction");
        }
        
        // Validate credit limit if being updated
        if (accountDto.getAcctCreditLimit() != null) {
            validationService.validateCreditLimit(accountDto.getAcctCreditLimit());
            
            // Business rule: Credit limit cannot be less than current balance
            // (COACTUPC.cbl line 196-199)
            BigDecimal currentBalance = calculateCurrentBalance(existingAccount);
            validateCreditLimit(accountDto.getAcctCreditLimit(), currentBalance);
            
            existingAccount.setAcctCreditLimit(accountDto.getAcctCreditLimit());
            log.debug("Updated credit limit to: {}", accountDto.getAcctCreditLimit());
        }
        
        // Update cash credit limit if provided
        if (accountDto.getAcctCashCreditLimit() != null) {
            validationService.validateAmount(accountDto.getAcctCashCreditLimit());
            existingAccount.setAcctCashCreditLimit(accountDto.getAcctCashCreditLimit());
            log.debug("Updated cash credit limit to: {}", accountDto.getAcctCashCreditLimit());
        }
        
        // Update account status if provided
        if (accountDto.getAcctActiveStatus() != null) {
            validationService.validateMandatoryField(accountDto.getAcctActiveStatus(), "acctActiveStatus");
            existingAccount.setAcctActiveStatus(accountDto.getAcctActiveStatus());
            log.debug("Updated account status to: {}", accountDto.getAcctActiveStatus());
        }
        
        // Update expiration date if provided
        if (accountDto.getAcctExpirationDate() != null) {
            existingAccount.setAcctExpirationDate(accountDto.getAcctExpirationDate());
            log.debug("Updated expiration date to: {}", accountDto.getAcctExpirationDate());
        }
        
        // Update address ZIP if provided
        if (accountDto.getAcctAddrZip() != null) {
            validationService.validateFieldLength(accountDto.getAcctAddrZip(), 10, "acctAddrZip");
            existingAccount.setAcctAddrZip(accountDto.getAcctAddrZip());
            log.debug("Updated address ZIP to: {}", accountDto.getAcctAddrZip());
        }
        
        // Update group ID if provided
        if (accountDto.getAcctGroupId() != null) {
            validationService.validateFieldLength(accountDto.getAcctGroupId(), 10, "acctGroupId");
            existingAccount.setAcctGroupId(accountDto.getAcctGroupId());
            log.debug("Updated group ID to: {}", accountDto.getAcctGroupId());
        }
        
        // Update current balance if provided (used by TransactionService for balance updates)
        // Otherwise, recalculate balance from transactions (used by AccountController for manual updates)
        if (accountDto.getAcctCurrBal() != null) {
            existingAccount.setAcctCurrBal(accountDto.getAcctCurrBal());
            log.debug("Updated current balance to: {}", accountDto.getAcctCurrBal());
        } else {
            // Recalculate current balance (replaces COBOL COMPUTE statements)
            BigDecimal updatedBalance = calculateCurrentBalance(existingAccount);
            existingAccount.setAcctCurrBal(updatedBalance);
            log.debug("Recalculated current balance to: {}", updatedBalance);
        }
        
        // CRITICAL VALIDATION: Balance must not exceed credit limit
        // (COBOL business rule from COACTUPC.cbl)
        if (existingAccount.getAcctCurrBal() != null && existingAccount.getAcctCreditLimit() != null) {
            if (existingAccount.getAcctCurrBal().compareTo(existingAccount.getAcctCreditLimit()) > 0) {
                log.warn("Balance {} exceeds credit limit {} for account {}",
                        existingAccount.getAcctCurrBal(), existingAccount.getAcctCreditLimit(), accountId);
                throw new ValidationException("VAL011",
                        "Account balance cannot exceed credit limit",
                        "acctCurrBal");
            }
        }
        
        // Update current cycle credit if provided (used by TransactionService)
        if (accountDto.getAcctCurrCycCredit() != null) {
            existingAccount.setAcctCurrCycCredit(accountDto.getAcctCurrCycCredit());
            log.debug("Updated current cycle credit to: {}", accountDto.getAcctCurrCycCredit());
        }
        
        // Update current cycle debit if provided (used by TransactionService)
        if (accountDto.getAcctCurrCycDebit() != null) {
            existingAccount.setAcctCurrCycDebit(accountDto.getAcctCurrCycDebit());
            log.debug("Updated current cycle debit to: {}", accountDto.getAcctCurrCycDebit());
        }
        
        // Save with optimistic locking (replaces EXEC CICS REWRITE + SYNCPOINT)
        Account savedAccount = accountRepository.save(existingAccount);
        
        log.info("Account updated successfully: {}", accountId);
        return mapToDto(savedAccount);
    }

    /**
     * Create new account with default values and validation.
     * 
     * <p>Implements account creation logic with defaults:</p>
     * <ul>
     *   <li>Current balance initialized to 0.00</li>
     *   <li>Current cycle credit initialized to 0.00</li>
     *   <li>Current cycle debit initialized to 0.00</li>
     *   <li>Default active status 'Y' if not specified</li>
     * </ul>
     * 
     * <p>Validation rules:</p>
     * <ul>
     *   <li>Account ID must be valid 11-digit number</li>
     *   <li>Credit limit must be positive and within bounds</li>
     *   <li>Open date must be valid</li>
     *   <li>All mandatory fields must be provided</li>
     * </ul>
     * 
     * @param accountDto DTO containing new account data
     * @return Created AccountDto
     * @throws ValidationException if input data is invalid
     * @throws BusinessException if account ID already exists
     */
    @Transactional
    public AccountDto createAccount(AccountDto accountDto) {
        log.info("Creating new account: {}", accountDto.getAcctId());
        
        // Validate account ID
        validationService.validateAccountId(accountDto.getAcctId());
        
        // Check if account already exists
        if (accountRepository.findById(accountDto.getAcctId()).isPresent()) {
            log.warn("Account already exists: {}", accountDto.getAcctId());
            throw new BusinessException("BUS003", 
                "Account ID already exists: " + accountDto.getAcctId());
        }
        
        // Validate credit limit
        validationService.validateCreditLimit(accountDto.getAcctCreditLimit());
        
        // Validate cash credit limit
        validationService.validateAmount(accountDto.getAcctCashCreditLimit());
        
        // Validate mandatory fields
        validationService.validateMandatoryField(accountDto.getAcctActiveStatus(), "acctActiveStatus");
        validationService.validateDate(accountDto.getAcctOpenDate());
        
        // Build new account entity with defaults
        Account newAccount = Account.builder()
                .acctId(accountDto.getAcctId())
                .acctActiveStatus(accountDto.getAcctActiveStatus() != null ? 
                        accountDto.getAcctActiveStatus() : "Y")
                .acctCurrBal(BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP))
                .acctCreditLimit(accountDto.getAcctCreditLimit())
                .acctCashCreditLimit(accountDto.getAcctCashCreditLimit())
                .acctOpenDate(accountDto.getAcctOpenDate())
                .acctExpirationDate(accountDto.getAcctExpirationDate())
                .acctReissueDate(accountDto.getAcctReissueDate())
                .acctCurrCycCredit(BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP))
                .acctCurrCycDebit(BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP))
                .acctAddrZip(accountDto.getAcctAddrZip())
                .acctGroupId(accountDto.getAcctGroupId())
                .build();
        
        // Save new account (replaces EXEC CICS WRITE FILE('ACCTFILE'))
        Account savedAccount = accountRepository.save(newAccount);
        
        log.info("Account created successfully: {}", savedAccount.getAcctId());
        return mapToDto(savedAccount);
    }

    /**
     * Get account active status indicator.
     * 
     * <p>Returns the current status of an account:</p>
     * <ul>
     *   <li>'Y' - Active (can process transactions)</li>
     *   <li>'N' - Inactive (cannot process transactions)</li>
     *   <li>'C' - Closed (permanently closed)</li>
     *   <li>'S' - Suspended (temporarily suspended)</li>
     * </ul>
     * 
     * @param accountId The account ID to check
     * @return Account status indicator ('Y', 'N', 'C', or 'S')
     * @throws DataNotFoundException if account does not exist
     */
    @Transactional(readOnly = true)
    public String getAccountStatus(Long accountId) {
        log.debug("Getting account status for: {}", accountId);
        
        validationService.validateAccountId(accountId);
        
        Account account = accountRepository.findById(accountId)
                .orElseThrow(() -> new DataNotFoundException("Account", accountId));
        
        return account.getAcctActiveStatus();
    }

    /**
     * Find all accounts by group ID.
     * 
     * <p>Retrieves all accounts belonging to a specific group. Used for:</p>
     * <ul>
     *   <li>Batch processing jobs operating on account groups</li>
     *   <li>Reports grouped by product type or market segment</li>
     *   <li>Interest calculation jobs with group-specific rates</li>
     * </ul>
     * 
     * @param groupId The account group identifier (max 10 characters)
     * @return List of AccountDto objects in the group
     */
    @Transactional(readOnly = true)
    public List<AccountDto> findAccountsByGroupId(String groupId) {
        log.info("Finding accounts for group: {}", groupId);
        
        validationService.validateMandatoryField(groupId, "groupId");
        validationService.validateFieldLength(groupId, 10, "groupId");
        
        List<Account> accounts = accountRepository.findByAcctGroupId(groupId);
        
        log.debug("Found {} accounts in group {}", accounts.size(), groupId);
        return accounts.stream()
                .map(this::mapToDto)
                .collect(Collectors.toList());
    }

    /**
     * Get all accounts for report generation and batch processing.
     * 
     * <p>Retrieves all accounts from the database for use in:</p>
     * <ul>
     *   <li>Account summary reports showing aggregated statistics</li>
     *   <li>Batch processing jobs that operate on all accounts</li>
     *   <li>Data export and migration operations</li>
     *   <li>Administrative dashboards displaying system-wide metrics</li>
     * </ul>
     * 
     * <p><b>Performance Considerations:</b></p>
     * <ul>
     *   <li>This method retrieves all accounts - may return large result sets</li>
     *   <li>Use with caution in production environments with many accounts</li>
     *   <li>Consider pagination for UI-facing operations</li>
     *   <li>Optimized for report generation and batch jobs where full dataset is needed</li>
     * </ul>
     * 
     * @return List of all AccountDto objects in the system
     */
    @Transactional(readOnly = true)
    public List<AccountDto> getAllAccounts() {
        log.info("Retrieving all accounts for report generation");
        
        List<Account> accounts = accountRepository.findAll();
        
        log.debug("Retrieved {} total accounts", accounts.size());
        return accounts.stream()
                .map(this::mapToDto)
                .collect(Collectors.toList());
    }

    /**
     * Calculate current balance with COBOL COMP-3 precision.
     * 
     * <p>Converted from COBOL calculation logic in CVACT01Y.cpy:</p>
     * <pre>
     * COMPUTE ACCT-CURR-BAL = ACCT-CURR-BAL
     *                       + ACCT-CURR-CYC-CREDIT
     *                       - ACCT-CURR-CYC-DEBIT
     * </pre>
     * 
     * <p>Per Agent Action Plan Section 0.7.2, this calculation must:</p>
     * <ul>
     *   <li>Use BigDecimal to preserve COBOL COMP-3 packed decimal precision</li>
     *   <li>Maintain scale of 2 decimal places</li>
     *   <li>Use RoundingMode.HALF_UP to match COBOL rounding behavior</li>
     *   <li>Produce bit-identical results to COBOL computation</li>
     * </ul>
     * 
     * <p>Formula: CurrentBalance = Balance + CycleCredit - CycleDebit</p>
     * 
     * @param account The account entity with balance fields
     * @return Calculated current balance with scale 2, rounded HALF_UP
     */
    private BigDecimal calculateCurrentBalance(Account account) {
        log.debug("Calculating current balance for account: {}", account.getAcctId());
        
        // Get current values, defaulting to ZERO if null
        BigDecimal currentBal = account.getAcctCurrBal() != null ? 
                account.getAcctCurrBal() : BigDecimal.ZERO;
        BigDecimal cycleCredit = account.getAcctCurrCycCredit() != null ? 
                account.getAcctCurrCycCredit() : BigDecimal.ZERO;
        BigDecimal cycleDebit = account.getAcctCurrCycDebit() != null ? 
                account.getAcctCurrCycDebit() : BigDecimal.ZERO;
        
        // Calculate balance using COBOL COMP-3 precision rules
        // Formula: balance + credit - debit
        BigDecimal calculatedBalance = currentBal
                .add(cycleCredit)
                .subtract(cycleDebit)
                .setScale(2, RoundingMode.HALF_UP);
        
        log.debug("Balance calculation: {} + {} - {} = {}", 
                currentBal, cycleCredit, cycleDebit, calculatedBalance);
        
        return calculatedBalance;
    }

    /**
     * Validate credit limit change against current balance.
     * 
     * <p>Implements business rule from COACTUPC.cbl line 196-199:</p>
     * <pre>
     * IF NEW-CREDIT-LIMIT < CURRENT-BALANCE
     *    MOVE 12 TO APPL-RESULT
     *    STRING 'Credit limit cannot be less than current balance'
     *           INTO WS-RETURN-MSG
     * END-IF
     * </pre>
     * 
     * <p>Business rules enforced:</p>
     * <ul>
     *   <li>New credit limit must be greater than or equal to current balance</li>
     *   <li>Cannot lower credit limit below outstanding balance</li>
     *   <li>Prevents customer from being over-limit due to limit reduction</li>
     * </ul>
     * 
     * @param newLimit The new credit limit being set
     * @param currentBalance The current account balance
     * @throws BusinessException if newLimit < currentBalance (COBOL APPL-RESULT = 12)
     */
    private void validateCreditLimit(BigDecimal newLimit, BigDecimal currentBalance) {
        log.debug("Validating credit limit change: newLimit={}, currentBalance={}", 
                newLimit, currentBalance);
        
        // Business rule: Credit limit cannot be less than current balance
        if (newLimit.compareTo(currentBalance) < 0) {
            log.warn("Credit limit {} is less than current balance {}", 
                    newLimit, currentBalance);
            throw new BusinessException("BUS001", 
                    "Credit limit cannot be less than current balance");
        }
        
        log.debug("Credit limit validation passed");
    }

    /**
     * Map Account entity to AccountDto.
     * 
     * <p>Converts JPA entity to Data Transfer Object for API responses.
     * Uses AccountDto.fromEntity() static factory method for consistent mapping.</p>
     * 
     * @param account The Account entity to convert
     * @return AccountDto with all fields mapped
     */
    private AccountDto mapToDto(Account account) {
        return AccountDto.fromEntity(account);
    }
}
