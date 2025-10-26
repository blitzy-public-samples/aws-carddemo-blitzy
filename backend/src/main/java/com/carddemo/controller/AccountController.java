package com.carddemo.controller;

import com.carddemo.model.dto.AccountDto;
import com.carddemo.model.dto.ErrorResponse;
import com.carddemo.model.entity.Account;
import com.carddemo.repository.AccountRepository;
import com.carddemo.exception.DataNotFoundException;
import com.carddemo.exception.ValidationException;
import com.carddemo.exception.BusinessException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.util.Optional;

/**
 * REST Controller for Account operations converted from COBOL programs
 * COACTUPC.cbl (account update) and COACTVWC.cbl (account view).
 * 
 * This controller provides REST API endpoints for account management functionality,
 * replacing the CICS transaction processing from the mainframe environment.
 * 
 * COBOL Program Mapping:
 * - COACTUPC.cbl → PUT /api/accounts/{id}, POST /api/accounts
 * - COACTVWC.cbl → GET /api/accounts/{id}
 * 
 * @author CardDemo Migration Team
 */
@RestController
@RequestMapping("/api/accounts")
public class AccountController {

    @Autowired
    private AccountRepository accountRepository;

    /**
     * GET /api/accounts/{id} - Retrieve account by ID
     * COBOL Equivalent: COACTVWC.cbl EXEC CICS READ operation
     * 
     * @param id Account ID
     * @return AccountDto with account details
     */
    @GetMapping("/{id}")
    public ResponseEntity<?> getAccountById(@PathVariable Long id) {
        try {
            Optional<Account> accountOpt = accountRepository.findById(id);
            
            if (accountOpt.isEmpty()) {
                return ResponseEntity
                    .status(HttpStatus.NOT_FOUND)
                    .body(ErrorResponse.notFound(
                        "Account with ID " + id + " not found",
                        "No account exists with the specified ID",
                        "/api/accounts/" + id
                    ));
            }
            
            Account account = accountOpt.get();
            AccountDto dto = convertToDto(account);
            
            return ResponseEntity.ok(dto);
            
        } catch (Exception e) {
            return ResponseEntity
                .status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(ErrorResponse.internalError(
                    "Error retrieving account: " + e.getMessage(),
                    e.getClass().getSimpleName() + ": " + e.getMessage(),
                    "/api/accounts/" + id
                ));
        }
    }

    /**
     * PUT /api/accounts/{id} - Update existing account
     * COBOL Equivalent: COACTUPC.cbl EXEC CICS REWRITE operation
     * 
     * @param id Account ID
     * @param accountDto Account data to update
     * @return Updated AccountDto
     */
    @PutMapping("/{id}")
    public ResponseEntity<?> updateAccount(@PathVariable Long id, @RequestBody AccountDto accountDto) {
        try {
            // Validate account exists
            Optional<Account> existingOpt = accountRepository.findById(id);
            
            if (existingOpt.isEmpty()) {
                return ResponseEntity
                    .status(HttpStatus.NOT_FOUND)
                    .body(ErrorResponse.notFound(
                        "Account with ID " + id + " not found",
                        "No account exists with the specified ID",
                        "/api/accounts/" + id
                    ));
            }
            
            Account existing = existingOpt.get();
            
            // Validate input
            validateAccountData(accountDto);
            
            // Update fields
            existing.setAcctActiveStatus(accountDto.getAcctActiveStatus());
            existing.setAcctCurrBal(accountDto.getAcctCurrBal());
            existing.setAcctCreditLimit(accountDto.getAcctCreditLimit());
            existing.setAcctCashCreditLimit(accountDto.getAcctCashCreditLimit());
            existing.setAcctOpenDate(accountDto.getAcctOpenDate());
            existing.setAcctExpirationDate(accountDto.getAcctExpirationDate());
            existing.setAcctReissueDate(accountDto.getAcctReissueDate());
            existing.setAcctCurrCycCredit(accountDto.getAcctCurrCycCredit());
            existing.setAcctCurrCycDebit(accountDto.getAcctCurrCycDebit());
            existing.setAcctAddrZip(accountDto.getAcctAddrZip());
            existing.setAcctGroupId(accountDto.getAcctGroupId());
            
            Account saved = accountRepository.save(existing);
            AccountDto responseDto = convertToDto(saved);
            
            return ResponseEntity.ok(responseDto);
            
        } catch (ValidationException e) {
            return ResponseEntity
                .status(HttpStatus.BAD_REQUEST)
                .body(ErrorResponse.badRequest(
                    e.getMessage(),
                    "Validation failed for account data",
                    "/api/accounts/" + id
                ));
        } catch (org.springframework.orm.ObjectOptimisticLockingFailureException e) {
            return ResponseEntity
                .status(HttpStatus.CONFLICT)
                .body(ErrorResponse.conflict(
                    "Concurrent modification detected - record was updated by another user",
                    "The account was modified by another transaction. Please refresh and try again.",
                    "/api/accounts/" + id
                ));
        } catch (Exception e) {
            return ResponseEntity
                .status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(ErrorResponse.internalError(
                    "Error updating account: " + e.getMessage(),
                    e.getClass().getSimpleName() + ": " + e.getMessage(),
                    "/api/accounts/" + id
                ));
        }
    }

    /**
     * POST /api/accounts - Create new account
     * COBOL Equivalent: COACTUPC.cbl EXEC CICS WRITE operation
     * 
     * @param accountDto Account data for new account
     * @return Created AccountDto
     */
    @PostMapping
    public ResponseEntity<?> createAccount(@RequestBody AccountDto accountDto) {
        try {
            // Check if account already exists
            if (accountRepository.existsById(accountDto.getAcctId())) {
                return ResponseEntity
                    .status(HttpStatus.CONFLICT)
                    .body(ErrorResponse.conflict(
                        "Account with ID " + accountDto.getAcctId() + " already exists",
                        "Cannot create account with duplicate ID",
                        "/api/accounts"
                    ));
            }
            
            // Validate input
            validateAccountData(accountDto);
            
            // Create new account
            Account newAccount = Account.builder()
                .acctId(accountDto.getAcctId())
                .acctActiveStatus(accountDto.getAcctActiveStatus())
                .acctCurrBal(accountDto.getAcctCurrBal())
                .acctCreditLimit(accountDto.getAcctCreditLimit())
                .acctCashCreditLimit(accountDto.getAcctCashCreditLimit())
                .acctOpenDate(accountDto.getAcctOpenDate())
                .acctExpirationDate(accountDto.getAcctExpirationDate())
                .acctReissueDate(accountDto.getAcctReissueDate())
                .acctCurrCycCredit(accountDto.getAcctCurrCycCredit())
                .acctCurrCycDebit(accountDto.getAcctCurrCycDebit())
                .acctAddrZip(accountDto.getAcctAddrZip())
                .acctGroupId(accountDto.getAcctGroupId())
                .build();
            
            Account saved = accountRepository.save(newAccount);
            AccountDto responseDto = convertToDto(saved);
            
            return ResponseEntity
                .status(HttpStatus.CREATED)
                .body(responseDto);
            
        } catch (ValidationException e) {
            return ResponseEntity
                .status(HttpStatus.BAD_REQUEST)
                .body(ErrorResponse.badRequest(
                    e.getMessage(),
                    "Validation failed for account data",
                    "/api/accounts"
                ));
        } catch (Exception e) {
            return ResponseEntity
                .status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(ErrorResponse.internalError(
                    "Error creating account: " + e.getMessage(),
                    e.getClass().getSimpleName() + ": " + e.getMessage(),
                    "/api/accounts"
                ));
        }
    }

    /**
     * Validate account data according to COBOL business rules.
     * From COACTUPC.cbl validation logic.
     * 
     * @param accountDto Account data to validate
     * @throws ValidationException if validation fails
     */
    private void validateAccountData(AccountDto accountDto) throws ValidationException {
        // Validate account status (must be 'Y' or 'N')
        String status = accountDto.getAcctActiveStatus();
        if (status == null || (!status.equals("Y") && !status.equals("N"))) {
            throw new ValidationException("Account status must be 'Y' or 'N'");
        }
        
        // Validate credit limit (must be positive)
        BigDecimal creditLimit = accountDto.getAcctCreditLimit();
        if (creditLimit == null || creditLimit.compareTo(BigDecimal.ZERO) <= 0) {
            throw new ValidationException("Credit limit must be greater than 0");
        }
        
        // Validate current balance doesn't exceed credit limit
        BigDecimal currentBalance = accountDto.getAcctCurrBal();
        if (currentBalance != null && currentBalance.compareTo(creditLimit) > 0) {
            throw new ValidationException("Current balance cannot exceed credit limit");
        }
    }

    /**
     * Convert Account entity to AccountDto.
     * 
     * @param account Account entity
     * @return AccountDto
     */
    private AccountDto convertToDto(Account account) {
        return AccountDto.builder()
            .acctId(account.getAcctId())
            .acctActiveStatus(account.getAcctActiveStatus())
            .acctCurrBal(account.getAcctCurrBal())
            .acctCreditLimit(account.getAcctCreditLimit())
            .acctCashCreditLimit(account.getAcctCashCreditLimit())
            .acctOpenDate(account.getAcctOpenDate())
            .acctExpirationDate(account.getAcctExpirationDate())
            .acctReissueDate(account.getAcctReissueDate())
            .acctCurrCycCredit(account.getAcctCurrCycCredit())
            .acctCurrCycDebit(account.getAcctCurrCycDebit())
            .acctAddrZip(account.getAcctAddrZip())
            .acctGroupId(account.getAcctGroupId())
            .build();
    }
}
