package com.carddemo.dto.request;

import com.carddemo.constants.AccountStatus;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.*;
import lombok.AllArgsConstructor;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;

/**
 * Request DTO for updating existing credit card account information.
 * 
 * This class transforms COBOL COACTUP BMS copybook fields into a modern Java request object
 * for RESTful API consumption. It encompasses comprehensive account data including:
 * - Account identification and status
 * - Financial limits and balances (credit, cash, cycle amounts)
 * - Temporal attributes (open date, expiration, reissue dates)
 * - Associated customer personal information
 * - Contact details and address information
 * 
 * Source Transformation:
 * COBOL Copybook: app/cpy-bms/COACTUP.CPY (BMS CACTUPAI input structure)
 * Target: Spring Boot REST API request DTO with Bean Validation
 * 
 * Field Mapping Strategy:
 * - COBOL PIC X(n) fields → String with @Size(max=n) validation
 * - COBOL separate date components (YEAR/MON/DAY) → Single LocalDate field
 * - COBOL PIC X(15) amount fields → BigDecimal(scale=2, RoundingMode.HALF_UP)
 * - COBOL PIC X(1) status code → String validated against AccountStatus enum
 * - COBOL multi-part fields (SSN, phone) → Combined into single fields
 * 
 * Numeric Precision Requirements:
 * All monetary amounts (creditLimit, cashLimit, currentBalance, etc.) use BigDecimal
 * with scale=2 and RoundingMode.HALF_UP to preserve COBOL COMP-3 packed decimal precision
 * per section 0.9 critical numeric precision requirements. This ensures byte-for-byte
 * financial accuracy matching mainframe decimal arithmetic.
 * 
 * Validation Strategy:
 * - @NotNull for required fields per COBOL business rules
 * - @Size for length constraints matching COBOL PIC clauses
 * - @Pattern for format validation (status codes, zip codes)
 * - @DecimalMin/@DecimalMax for amount range validation
 * - @Past/@Future for temporal constraint validation
 * - Custom validators for complex business rules (status transitions)
 * 
 * Usage Example:
 * <pre>
 * AccountUpdateRequest request = new AccountUpdateRequest();
 * request.setAccountId("00000000001");
 * request.setAccountStatus("A");  // Active
 * request.setCreditLimit(new BigDecimal("15000.00").setScale(2, RoundingMode.HALF_UP));
 * request.setExpirationDate(LocalDate.of(2025, 12, 31));
 * 
 * // In Controller:
 * {@literal @}PutMapping("/api/accounts/{id}")
 * public ResponseEntity{@literal <}AccountViewResponse{@literal >} updateAccount(
 *     {@literal @}PathVariable String id,
 *     {@literal @}Valid {@literal @}RequestBody AccountUpdateRequest request) {
 *     return accountUpdateService.updateAccount(id, request);
 * }
 * </pre>
 * 
 * Thread Safety: This DTO is not thread-safe. Create new instances per request.
 * Immutability: Uses Lombok @Data for mutable access. Consider @Value for immutable variant.
 * 
 * @see com.carddemo.service.AccountUpdateService
 * @see com.carddemo.constants.AccountStatus
 * @see com.carddemo.entity.Account
 * @see com.carddemo.dto.response.AccountViewResponse
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode
public class AccountUpdateRequest {
    
    // ==================== Account Identification Fields ====================
    
    /**
     * Unique account identifier (11 digits).
     * Source: ACCTSIDI PIC X(11) from COACTUP copybook
     * 
     * Format: Zero-padded 11-digit numeric string (e.g., "00000000001")
     * Business Rule: Must exist in database for update operations
     * Validation: Required, exactly 11 characters, numeric pattern
     */
    @NotNull(message = "Account ID is required")
    @Size(min = 11, max = 11, message = "Account ID must be exactly 11 characters")
    @Pattern(regexp = "^[0-9]{11}$", message = "Account ID must be 11 digits")
    @JsonProperty("accountId")
    private String accountId;
    
    /**
     * Account status code (single character).
     * Source: ACSTTUSI PIC X(1) from COACTUP copybook
     * 
     * Valid Values: A=Active, I=Inactive, C=Closed, S=Suspended, P=Pending
     * Business Rule: Status transitions validated via AccountStatus.canTransitionTo()
     * Validation: Required, must be valid AccountStatus enum code
     * 
     * @see AccountStatus#fromString(String)
     * @see AccountStatus#canTransitionTo(AccountStatus)
     */
    @NotNull(message = "Account status is required")
    @Pattern(regexp = "^[AICSP]$", message = "Account status must be one of: A, I, C, S, P")
    @JsonProperty("accountStatus")
    private String accountStatus;
    
    // ==================== Temporal Fields ====================
    
    /**
     * Account open date.
     * Source: OPNYEARI/OPNMONI/OPNDAYI (PIC X(4)/X(2)/X(2)) from COACTUP copybook
     * 
     * Combined from three separate COBOL fields:
     * - OPNYEARI: 4-digit year (e.g., "2024")
     * - OPNMONI: 2-digit month (e.g., "01")
     * - OPNDAYI: 2-digit day (e.g., "15")
     * 
     * Business Rule: Cannot be in the future (accounts can't open before creation)
     * Validation: Must be past or present date
     */
    @NotNull(message = "Account open date is required")
    @Past(message = "Account open date must be in the past")
    @JsonProperty("openDate")
    private LocalDate openDate;
    
    /**
     * Account expiration date.
     * Source: EXPYEARI/EXPMONI/EXPDAYI (PIC X(4)/X(2)/X(2)) from COACTUP copybook
     * 
     * Combined from three separate COBOL fields representing card/account expiration.
     * Business Rule: Must be future date for active accounts
     * Validation: Must be future date
     */
    @NotNull(message = "Expiration date is required")
    @Future(message = "Expiration date must be in the future")
    @JsonProperty("expirationDate")
    private LocalDate expirationDate;
    
    /**
     * Card reissue date.
     * Source: RISYEARI/RISMONI/RISDAYI (PIC X(4)/X(2)/X(2)) from COACTUP copybook
     * 
     * Date when card was last reissued (replaced due to expiration, loss, or damage).
     * Nullable: Only populated if card has been reissued
     */
    @JsonProperty("reissueDate")
    private LocalDate reissueDate;
    
    // ==================== Financial Limit and Balance Fields ====================
    
    /**
     * Credit limit amount in dollars and cents.
     * Source: ACRDLIMI PIC X(15) from COACTUP copybook
     * 
     * Maximum credit available to account holder.
     * Precision: BigDecimal with scale=2, RoundingMode.HALF_UP
     * Business Rule: Must be positive, typically ranges from $500 to $50,000
     * 
     * COBOL COMP-3 Precision Preservation:
     * Original COBOL field is PIC X(15) display format, but represents COMP-3 packed decimal
     * in actual processing. Java BigDecimal with scale=2 provides equivalent precision.
     */
    @NotNull(message = "Credit limit is required")
    @DecimalMin(value = "0.00", message = "Credit limit must be non-negative")
    @DecimalMax(value = "999999999999.99", message = "Credit limit exceeds maximum")
    @Digits(integer = 13, fraction = 2, message = "Credit limit must have at most 13 integer digits and 2 decimal places")
    @JsonProperty("creditLimit")
    private BigDecimal creditLimit;
    
    /**
     * Cash advance limit amount in dollars and cents.
     * Source: ACSHLIMI PIC X(15) from COACTUP copybook
     * 
     * Maximum cash advance available (typically lower than credit limit).
     * Precision: BigDecimal with scale=2, RoundingMode.HALF_UP
     * Business Rule: Should not exceed credit limit
     */
    @NotNull(message = "Cash limit is required")
    @DecimalMin(value = "0.00", message = "Cash limit must be non-negative")
    @DecimalMax(value = "999999999999.99", message = "Cash limit exceeds maximum")
    @Digits(integer = 13, fraction = 2, message = "Cash limit must have at most 13 integer digits and 2 decimal places")
    @JsonProperty("cashLimit")
    private BigDecimal cashLimit;
    
    /**
     * Current account balance in dollars and cents.
     * Source: ACURBALI PIC X(15) from COACTUP copybook
     * 
     * Current outstanding balance owed by account holder.
     * Precision: BigDecimal with scale=2, RoundingMode.HALF_UP
     * Business Rule: Can be negative (credit balance) or positive (debt balance)
     */
    @NotNull(message = "Current balance is required")
    @DecimalMin(value = "-999999999999.99", message = "Current balance below minimum")
    @DecimalMax(value = "999999999999.99", message = "Current balance exceeds maximum")
    @Digits(integer = 13, fraction = 2, message = "Current balance must have at most 13 integer digits and 2 decimal places")
    @JsonProperty("currentBalance")
    private BigDecimal currentBalance;
    
    /**
     * Cash cycle credit amount in dollars and cents.
     * Source: ACRCYCRI PIC X(15) from COACTUP copybook
     * 
     * Total cash advances in current billing cycle.
     * Precision: BigDecimal with scale=2, RoundingMode.HALF_UP
     * Business Rule: Resets to zero at end of billing cycle
     */
    @NotNull(message = "Cash cycle credit is required")
    @DecimalMin(value = "0.00", message = "Cash cycle credit must be non-negative")
    @DecimalMax(value = "999999999999.99", message = "Cash cycle credit exceeds maximum")
    @Digits(integer = 13, fraction = 2, message = "Cash cycle credit must have at most 13 integer digits and 2 decimal places")
    @JsonProperty("cashCycleCredit")
    private BigDecimal cashCycleCredit;
    
    /**
     * Cash cycle debit amount in dollars and cents.
     * Source: ACRCYDBI PIC X(15) from COACTUP copybook
     * 
     * Total cash repayments in current billing cycle.
     * Precision: BigDecimal with scale=2, RoundingMode.HALF_UP
     * Business Rule: Resets to zero at end of billing cycle
     */
    @NotNull(message = "Cash cycle debit is required")
    @DecimalMin(value = "0.00", message = "Cash cycle debit must be non-negative")
    @DecimalMax(value = "999999999999.99", message = "Cash cycle debit exceeds maximum")
    @Digits(integer = 13, fraction = 2, message = "Cash cycle debit must have at most 13 integer digits and 2 decimal places")
    @JsonProperty("cashCycleDebit")
    private BigDecimal cashCycleDebit;
    
    // ==================== Account Classification Fields ====================
    
    /**
     * Account group identifier.
     * Source: AADDGRPI PIC X(10) from COACTUP copybook
     * 
     * Groups accounts by product type, rewards program, or organizational unit.
     * Business Rule: Must reference valid account group in ACCTGRP table
     */
    @NotNull(message = "Account group ID is required")
    @Size(max = 10, message = "Account group ID must not exceed 10 characters")
    @JsonProperty("accountGroupId")
    private String accountGroupId;
    
    /**
     * Statement number for current billing cycle.
     * Source: ACSTNUMI PIC X(9) from COACTUP copybook
     * 
     * Sequential statement identifier for billing cycle tracking.
     * Format: Numeric string, zero-padded
     */
    @Size(max = 9, message = "Statement number must not exceed 9 characters")
    @Pattern(regexp = "^[0-9]{0,9}$", message = "Statement number must be numeric")
    @JsonProperty("statementNumber")
    private String statementNumber;
    
    // ==================== Customer Personal Information Fields ====================
    
    /**
     * Social Security Number (combined from three parts).
     * Source: ACTSSN1I/ACTSSN2I/ACTSSN3I (PIC X(3)/X(2)/X(4)) from COACTUP copybook
     * 
     * Combined format: XXX-XX-XXXX (9 digits with hyphens)
     * Storage format: 9 digits without hyphens (e.g., "123456789")
     * Business Rule: Must be valid SSN format, used for identity verification
     * 
     * Security Note: Should be encrypted at rest and masked in responses
     */
    @NotNull(message = "SSN is required")
    @Pattern(regexp = "^[0-9]{9}$", message = "SSN must be exactly 9 digits")
    @JsonProperty("ssn")
    private String ssn;
    
    /**
     * Customer date of birth.
     * Source: DOBYEARI/DOBMONI/DOBDAYI (PIC X(4)/X(2)/X(2)) from COACTUP copybook
     * 
     * Combined from three separate COBOL date component fields.
     * Business Rule: Customer must be at least 18 years old
     * Validation: Must be past date (cannot be born in future)
     */
    @NotNull(message = "Date of birth is required")
    @Past(message = "Date of birth must be in the past")
    @JsonProperty("dateOfBirth")
    private LocalDate dateOfBirth;
    
    /**
     * FICO credit score.
     * Source: ACSTFCOI PIC X(3) from COACTUP copybook
     * 
     * Credit score ranging from 300 to 850.
     * Business Rule: Used for credit limit decisions and risk assessment
     */
    @Min(value = 300, message = "FICO score must be at least 300")
    @Max(value = 850, message = "FICO score must not exceed 850")
    @JsonProperty("ficoScore")
    private Integer ficoScore;
    
    /**
     * Customer first name.
     * Source: ACSFNAMI PIC X(25) from COACTUP copybook
     * 
     * Legal first name as appears on identification documents.
     * Business Rule: Required for account holder identification
     */
    @NotNull(message = "First name is required")
    @Size(min = 1, max = 25, message = "First name must be between 1 and 25 characters")
    @JsonProperty("firstName")
    private String firstName;
    
    /**
     * Customer middle name or initial.
     * Source: ACSMNAMI PIC X(25) from COACTUP copybook
     * 
     * Optional middle name or initial.
     * Nullable: Not all customers have middle names
     */
    @Size(max = 25, message = "Middle name must not exceed 25 characters")
    @JsonProperty("middleName")
    private String middleName;
    
    /**
     * Customer last name.
     * Source: ACSLNAMI PIC X(25) from COACTUP copybook
     * 
     * Legal last name (surname) as appears on identification documents.
     * Business Rule: Required for account holder identification
     */
    @NotNull(message = "Last name is required")
    @Size(min = 1, max = 25, message = "Last name must be between 1 and 25 characters")
    @JsonProperty("lastName")
    private String lastName;
    
    // ==================== Address Information Fields ====================
    
    /**
     * Address line 1 (street address).
     * Source: ACSADL1I PIC X(50) from COACTUP copybook
     * 
     * Primary street address including house number and street name.
     * Example: "123 Main Street" or "456 Oak Avenue Apt 2B"
     * Business Rule: Required for billing and correspondence
     */
    @NotNull(message = "Address line 1 is required")
    @Size(min = 1, max = 50, message = "Address line 1 must be between 1 and 50 characters")
    @JsonProperty("addressLine1")
    private String addressLine1;
    
    /**
     * Address line 2 (additional address details).
     * Source: ACSADL2I PIC X(50) from COACTUP copybook
     * 
     * Optional additional address information (apartment, suite, building, etc.).
     * Nullable: Not all addresses require second line
     */
    @Size(max = 50, message = "Address line 2 must not exceed 50 characters")
    @JsonProperty("addressLine2")
    private String addressLine2;
    
    /**
     * City name.
     * Source: ACSCITYI PIC X(50) from COACTUP copybook
     * 
     * City or municipality name for billing address.
     * Business Rule: Required for address validation and correspondence
     */
    @NotNull(message = "City is required")
    @Size(min = 1, max = 50, message = "City must be between 1 and 50 characters")
    @JsonProperty("city")
    private String city;
    
    /**
     * State or province code.
     * Source: ACSSTTEI PIC X(2) from COACTUP copybook
     * 
     * Two-character US state abbreviation (e.g., "CA", "NY", "TX").
     * Business Rule: Must be valid US state code
     */
    @NotNull(message = "State is required")
    @Size(min = 2, max = 2, message = "State must be exactly 2 characters")
    @Pattern(regexp = "^[A-Z]{2}$", message = "State must be 2 uppercase letters")
    @JsonProperty("state")
    private String state;
    
    /**
     * ZIP code or postal code.
     * Source: ACSZIPCI PIC X(5) from COACTUP copybook
     * 
     * Five-digit US ZIP code.
     * Business Rule: Required for address validation and billing
     * Format: XXXXX (5 digits)
     */
    @NotNull(message = "ZIP code is required")
    @Pattern(regexp = "^[0-9]{5}$", message = "ZIP code must be exactly 5 digits")
    @JsonProperty("zipCode")
    private String zipCode;
    
    /**
     * Country code.
     * Source: ACSCTRYI PIC X(3) from COACTUP copybook
     * 
     * Three-character country code (ISO 3166-1 alpha-3 format).
     * Example: "USA", "CAN", "MEX"
     * Business Rule: Defaults to "USA" for domestic accounts
     */
    @NotNull(message = "Country is required")
    @Size(min = 3, max = 3, message = "Country must be exactly 3 characters")
    @Pattern(regexp = "^[A-Z]{3}$", message = "Country must be 3 uppercase letters")
    @JsonProperty("country")
    private String country;
    
    // ==================== Contact Information Fields ====================
    
    /**
     * Primary phone number (combined from three parts).
     * Source: ACSPH1AI/ACSPH1BI/ACSPH1CI (PIC X(3)/X(3)/X(4)) from COACTUP copybook
     * 
     * Combined format: (XXX) XXX-XXXX or stored as 10 digits: XXXXXXXXXX
     * Storage format: 10 digits without formatting (e.g., "5551234567")
     * Business Rule: Required for account contact and verification
     */
    @NotNull(message = "Primary phone number is required")
    @Pattern(regexp = "^[0-9]{10}$", message = "Phone number must be exactly 10 digits")
    @JsonProperty("phoneNumber1")
    private String phoneNumber1;
    
    /**
     * Secondary phone number (combined from three parts).
     * Source: ACSPH2AI/ACSPH2BI/ACSPH2CI (PIC X(3)/X(3)/X(4)) from COACTUP copybook
     * 
     * Optional alternative contact phone number.
     * Storage format: 10 digits without formatting
     * Nullable: Not all customers provide secondary phone
     */
    @Pattern(regexp = "^[0-9]{10}$", message = "Secondary phone number must be exactly 10 digits")
    @JsonProperty("phoneNumber2")
    private String phoneNumber2;
    
    /**
     * Government-issued identification number.
     * Source: ACSGOVTI PIC X(20) from COACTUP copybook
     * 
     * Alternative identification document number (driver's license, passport, etc.).
     * Business Rule: Used as secondary identification when SSN not available
     * 
     * Security Note: Should be encrypted at rest
     */
    @Size(max = 20, message = "Government ID must not exceed 20 characters")
    @JsonProperty("governmentId")
    private String governmentId;
    
    /**
     * Electronic Funds Transfer account number.
     * Source: ACSEFTCI PIC X(10) from COACTUP copybook
     * 
     * Bank account number linked for automatic payments or deposits.
     * Nullable: Only populated if customer has EFT setup
     * 
     * Security Note: Should be encrypted at rest and masked in responses
     */
    @Size(max = 10, message = "EFT account must not exceed 10 characters")
    @JsonProperty("eftAccount")
    private String eftAccount;
    
    /**
     * Profile update flag.
     * Source: ACSPFLGI PIC X(1) from COACTUP copybook
     * 
     * Indicates whether customer profile has been updated.
     * Valid Values: "Y" = Yes (updated), "N" = No (not updated)
     * Business Rule: Set to "Y" when customer modifies profile information
     */
    @Pattern(regexp = "^[YN]$", message = "Profile flag must be Y or N")
    @JsonProperty("profileFlag")
    private String profileFlag;
    
    // ==================== Helper Methods ====================
    
    /**
     * Gets the AccountStatus enum corresponding to the accountStatus string code.
     * 
     * Convenience method to convert the string status code to the type-safe enum.
     * This enables validation and business logic operations using AccountStatus methods.
     * 
     * Example Usage:
     * <pre>
     * AccountStatus status = request.getAccountStatusEnum();
     * if (status == AccountStatus.ACTIVE) {
     *     // Process active account
     * }
     * </pre>
     * 
     * @return AccountStatus enum value corresponding to accountStatus field
     * @throws IllegalArgumentException if accountStatus is null or invalid code
     * @see AccountStatus#fromString(String)
     */
    @JsonIgnore
    public AccountStatus getAccountStatusEnum() {
        return AccountStatus.fromString(this.accountStatus);
    }
    
    /**
     * Sets the account status from an AccountStatus enum value.
     * 
     * Convenience method to set the string status code from enum.
     * This ensures type-safe status assignment in service layer logic.
     * 
     * Example Usage:
     * <pre>
     * request.setAccountStatusEnum(AccountStatus.ACTIVE);
     * // This sets accountStatus field to "A"
     * </pre>
     * 
     * @param status AccountStatus enum value to set
     * @throws NullPointerException if status is null
     * @see AccountStatus#getCode()
     */
    @JsonIgnore
    public void setAccountStatusEnum(AccountStatus status) {
        if (status == null) {
            throw new NullPointerException("Account status cannot be null");
        }
        this.accountStatus = String.valueOf(status.getCode());
    }
    
    /**
     * Validates and normalizes all BigDecimal monetary fields to ensure consistent precision.
     * 
     * This method enforces COBOL COMP-3 precision preservation by setting scale=2 and
     * RoundingMode.HALF_UP on all monetary amounts. Should be called by service layer
     * before persisting to database to ensure financial accuracy.
     * 
     * Fields normalized:
     * - creditLimit
     * - cashLimit
     * - currentBalance
     * - cashCycleCredit
     * - cashCycleDebit
     * 
     * Example Usage:
     * <pre>
     * {@literal @}Service
     * public class AccountUpdateService {
     *     public void updateAccount(AccountUpdateRequest request) {
     *         request.normalizeMonetaryFields();  // Ensure precision
     *         // Proceed with update...
     *     }
     * }
     * </pre>
     * 
     * Business Justification:
     * Per section 0.9 critical numeric precision requirements, all monetary calculations
     * must maintain exact COBOL COMP-3 decimal precision to prevent financial discrepancies
     * during COBOL-to-Java migration. This method ensures byte-for-byte accuracy.
     */
    public void normalizeMonetaryFields() {
        if (this.creditLimit != null) {
            this.creditLimit = this.creditLimit.setScale(2, RoundingMode.HALF_UP);
        }
        if (this.cashLimit != null) {
            this.cashLimit = this.cashLimit.setScale(2, RoundingMode.HALF_UP);
        }
        if (this.currentBalance != null) {
            this.currentBalance = this.currentBalance.setScale(2, RoundingMode.HALF_UP);
        }
        if (this.cashCycleCredit != null) {
            this.cashCycleCredit = this.cashCycleCredit.setScale(2, RoundingMode.HALF_UP);
        }
        if (this.cashCycleDebit != null) {
            this.cashCycleDebit = this.cashCycleDebit.setScale(2, RoundingMode.HALF_UP);
        }
    }
    
    /**
     * Validates that cash limit does not exceed credit limit.
     * 
     * Business Rule: Cash advance limit should always be less than or equal to
     * the total credit limit. This prevents over-extension of cash credit.
     * 
     * @return true if cash limit is valid (≤ credit limit), false otherwise
     */
    public boolean isCashLimitValid() {
        if (this.creditLimit == null || this.cashLimit == null) {
            return true;  // Cannot validate if either is null
        }
        return this.cashLimit.compareTo(this.creditLimit) <= 0;
    }
    
    /**
     * Validates that expiration date is after open date.
     * 
     * Business Rule: Account cannot expire before it was opened.
     * This ensures temporal consistency in account lifecycle.
     * 
     * @return true if expiration date is after open date, false otherwise
     */
    public boolean isExpirationDateValid() {
        if (this.openDate == null || this.expirationDate == null) {
            return true;  // Cannot validate if either is null
        }
        return this.expirationDate.isAfter(this.openDate);
    }
    
    /**
     * Custom toString() method that excludes sensitive fields.
     * 
     * This method overrides Lombok's @Data generated toString() to ensure
     * sensitive fields (SSN, government ID, EFT account) are not included
     * in the string representation, preventing exposure in logs and error messages.
     * 
     * @return String representation without sensitive data
     */
    @Override
    public String toString() {
        return "AccountUpdateRequest(" +
                "accountId=" + accountId +
                ", accountStatus=" + accountStatus +
                ", openDate=" + openDate +
                ", expirationDate=" + expirationDate +
                ", reissueDate=" + reissueDate +
                ", creditLimit=" + creditLimit +
                ", cashLimit=" + cashLimit +
                ", currentBalance=" + currentBalance +
                ", cashCycleCredit=" + cashCycleCredit +
                ", cashCycleDebit=" + cashCycleDebit +
                ", accountGroupId=" + accountGroupId +
                ", statementNumber=" + statementNumber +
                ", dateOfBirth=" + dateOfBirth +
                ", ficoScore=" + ficoScore +
                ", firstName=" + firstName +
                ", middleName=" + middleName +
                ", lastName=" + lastName +
                ", addressLine1=" + addressLine1 +
                ", addressLine2=" + addressLine2 +
                ", city=" + city +
                ", state=" + state +
                ", zipCode=" + zipCode +
                ", country=" + country +
                ", phoneNumber1=" + phoneNumber1 +
                ", phoneNumber2=" + phoneNumber2 +
                ", profileFlag=" + profileFlag +
                ")";
    }
}

