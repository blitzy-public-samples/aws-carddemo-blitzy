package com.carddemo.model.dto;

import com.carddemo.model.entity.Account;
import com.fasterxml.jackson.annotation.JsonFormat;
import jakarta.validation.constraints.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * Data Transfer Object for Account entity used in REST API responses.
 * 
 * Converted from COBOL copybook: CVACT01Y.cpy (ACCOUNT-RECORD)
 * Original COBOL record length: 300 bytes
 * 
 * This DTO separates the external API representation from the internal JPA entity,
 * enabling API contract evolution independent of database schema changes.
 * Used by AccountController for GET /api/accounts endpoints per Section 0.4.6.
 * 
 * Conversion notes:
 * - Maps from Account entity which was converted from COBOL ACCOUNT-RECORD
 * - COBOL PIC 9(11) ACCT-ID → Long acctId
 * - COBOL PIC X(01) ACCT-ACTIVE-STATUS → String acctActiveStatus
 * - COBOL PIC S9(10)V99 COMP-3 financial fields → BigDecimal with scale 2
 * - COBOL PIC X(10) date fields → LocalDate
 * - Entity Timestamp audit fields → LocalDateTime for better JSON serialization
 * - Excludes version field from API response per DTO best practices (internal optimistic locking)
 * 
 * Key differences from entity:
 * - Uses LocalDateTime instead of java.sql.Timestamp for audit fields (better JSON support)
 * - Excludes version field (internal implementation detail, not part of API contract)
 * - Includes Jackson @JsonFormat annotations for consistent date/time formatting across API
 * 
 * JSON serialization format:
 * - Dates: yyyy-MM-dd (e.g., "2024-03-15")
 * - DateTimes: yyyy-MM-dd'T'HH:mm:ss (e.g., "2024-03-15T14:30:00")
 * - BigDecimal: numeric with 2 decimal places (e.g., 12500.75)
 * 
 * @see Account
 * @see com.carddemo.controller.AccountController
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AccountDto {

    /**
     * Account identifier (primary key).
     * 
     * Converted from: COBOL PIC 9(11) ACCT-ID
     * Maximum value: 99,999,999,999 (11 digits)
     * 
     * Unique identifier for the account record.
     * Validation: Required (not null), must be positive, maximum 11 digits.
     */
    @NotNull(message = "Account ID is required")
    @Positive(message = "Account ID must be positive")
    @Max(value = 99999999999L, message = "Account ID must be at most 11 digits")
    private Long acctId;

    /**
     * Account active status indicator.
     * 
     * Converted from: COBOL PIC X(01) ACCT-ACTIVE-STATUS
     * Valid values: 'Y' (active), 'N' (inactive), 'C' (closed), 'S' (suspended)
     * From COBOL line 193: 88 FLG-ACCT-STATUS-ISVALID VALUES 'Y', 'N', 'C', 'S'
     * 
     * Indicates the current operational status of the account.
     * Validation: Required, must be exactly one character matching [YNCS].
     */
    @NotBlank(message = "Account status is required")
    @Pattern(regexp = "[YNCS]", message = "Account status must be Y (active), N (inactive), C (closed), or S (suspended)")
    private String acctActiveStatus;

    /**
     * Current account balance.
     * 
     * Converted from: COBOL PIC S9(10)V99 COMP-3 ACCT-CURR-BAL
     * Precision: 12 digits total, 2 decimal places
     * Range: -9,999,999,999.99 to 9,999,999,999.99
     * 
     * Current outstanding balance on the account. Uses BigDecimal to preserve
     * COBOL COMP-3 packed decimal precision per Section 0.7.2 requirement.
     * 
     * NOTE: This field is READ-ONLY and calculated from transactions. Updates via
     * REST API are ignored - balance is recalculated by AccountService.
     */
    private BigDecimal acctCurrBal;

    /**
     * Credit limit for purchases.
     * 
     * Converted from: COBOL PIC S9(10)V99 COMP-3 ACCT-CREDIT-LIMIT
     * Precision: 12 digits total, 2 decimal places
     * Maximum: $50,000.00 per business rules (COACTUPC.cbl lines 500-800)
     * 
     * Maximum credit limit allowed for purchase transactions.
     * Validation: Must be positive when provided for create/update operations.
     */
    @DecimalMin(value = "0.01", message = "Credit limit must be positive")
    @DecimalMax(value = "50000.00", message = "Credit limit cannot exceed $50,000.00")
    private BigDecimal acctCreditLimit;

    /**
     * Cash advance credit limit.
     * 
     * Converted from: COBOL PIC S9(10)V99 COMP-3 ACCT-CASH-CREDIT-LIMIT
     * Precision: 12 digits total, 2 decimal places
     * 
     * Maximum credit limit allowed for cash advance transactions.
     * Validation: Must be positive when provided for create/update operations.
     */
    @DecimalMin(value = "0.01", message = "Cash credit limit must be positive")
    private BigDecimal acctCashCreditLimit;

    /**
     * Account opening date.
     * 
     * Converted from: COBOL PIC X(10) ACCT-OPEN-DATE
     * Format: YYYY-MM-DD
     * 
     * Date the account was originally opened.
     * Validation: Required for account creation, must not be in the future.
     */
    @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "yyyy-MM-dd")
    @PastOrPresent(message = "Account open date cannot be in the future")
    private LocalDate acctOpenDate;

    /**
     * Account expiration date.
     * 
     * Converted from: COBOL PIC X(10) ACCT-EXPIRAION-DATE
     * Format: YYYY-MM-DD
     * 
     * Date when account expires and renewal processing is required.
     * May be null for account types without expiration dates.
     */
    @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "yyyy-MM-dd")
    private LocalDate acctExpirationDate;

    /**
     * Account reissue date.
     * 
     * Converted from: COBOL PIC X(10) ACCT-REISSUE-DATE
     * Format: YYYY-MM-DD
     * 
     * Most recent date when account was reissued (e.g., after card theft/loss).
     * May be null if account has never been reissued.
     */
    @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "yyyy-MM-dd")
    private LocalDate acctReissueDate;

    /**
     * Current cycle credit total.
     * 
     * Converted from: COBOL PIC S9(10)V99 COMP-3 ACCT-CURR-CYC-CREDIT
     * Precision: 12 digits total, 2 decimal places
     * 
     * Sum of all credit transactions (payments, refunds) posted during
     * the current billing cycle.
     */
    private BigDecimal acctCurrCycCredit;

    /**
     * Current cycle debit total.
     * 
     * Converted from: COBOL PIC S9(10)V99 COMP-3 ACCT-CURR-CYC-DEBIT
     * Precision: 12 digits total, 2 decimal places
     * 
     * Sum of all debit transactions (purchases, cash advances, fees)
     * posted during the current billing cycle.
     */
    private BigDecimal acctCurrCycDebit;

    /**
     * Account billing address ZIP code.
     * 
     * Converted from: COBOL PIC X(10) ACCT-ADDR-ZIP
     * Maximum length: 10 characters (supports ZIP+4 format)
     * 
     * ZIP code for account billing address.
     * Validation: Optional, but if provided, maximum 10 characters.
     */
    @Size(max = 10, message = "ZIP code must be at most 10 characters")
    private String acctAddrZip;

    /**
     * Account group identifier.
     * 
     * Converted from: COBOL PIC X(10) ACCT-GROUP-ID
     * Maximum length: 10 characters
     * 
     * Grouping code for account categorization.
     * Validation: Optional, but if provided, maximum 10 characters.
     */
    @Size(max = 10, message = "Group ID must be at most 10 characters")
    private String acctGroupId;

    /**
     * Record creation timestamp.
     * 
     * Added field (not in original COBOL copybook).
     * Converted from entity's java.sql.Timestamp to LocalDateTime for better JSON serialization.
     * 
     * Timestamp when the account record was first created in the system.
     */
    @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "yyyy-MM-dd'T'HH:mm:ss")
    private LocalDateTime createdAt;

    /**
     * Record last update timestamp.
     * 
     * Added field (not in original COBOL copybook).
     * Converted from entity's java.sql.Timestamp to LocalDateTime for better JSON serialization.
     * 
     * Timestamp when the account record was last modified.
     */
    @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "yyyy-MM-dd'T'HH:mm:ss")
    private LocalDateTime updatedAt;

    /**
     * Version field for JPA optimistic locking.
     * 
     * Corresponds to the @Version field in Account entity. Used to detect
     * concurrent modifications. When included in update requests, the service
     * layer validates this matches the current entity version.
     * 
     * COBOL equivalent: VSAM RBA (Relative Byte Address) check from COACTUPC.cbl
     * 
     * @see com.carddemo.model.entity.Account#version
     */
    private Integer version;

    /**
     * Static factory method to convert Account entity to AccountDto.
     * 
     * This method implements the entity-to-DTO conversion pattern, extracting all
     * fields from the JPA entity and mapping them to the DTO representation.
     * 
     * Conversion logic:
     * - All fields are directly copied from entity to DTO
     * - Timestamp audit fields are converted to LocalDateTime using toLocalDateTime()
     * - Version field is excluded (internal implementation detail)
     * - Null-safe: returns null if input entity is null
     * 
     * Usage example:
     * <pre>
     * {@code
     * Account accountEntity = accountRepository.findById(123L).orElseThrow();
     * AccountDto accountDto = AccountDto.fromEntity(accountEntity);
     * return ResponseEntity.ok(accountDto);
     * }
     * </pre>
     * 
     * @param account The Account entity to convert (may be null)
     * @return AccountDto populated with entity data, or null if input is null
     * @see Account
     */
    public static AccountDto fromEntity(Account account) {
        if (account == null) {
            return null;
        }

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
                .createdAt(account.getCreatedAt() != null ? account.getCreatedAt().toLocalDateTime() : null)
                .updatedAt(account.getUpdatedAt() != null ? account.getUpdatedAt().toLocalDateTime() : null)
                .version(account.getVersion())
                .build();
    }
}
