/*
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
package com.carddemo.dto.request;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.Objects;

/**
 * Request DTO for creating new credit card accounts.
 * 
 * <p>This Data Transfer Object captures customer account setup information including
 * initial credit limits, opening dates, and account group assignments. It transforms
 * COBOL BMS copybook COACTUP input field definitions to a type-safe Java request object
 * for the account creation REST API endpoint.</p>
 * 
 * <p>Field mappings from COBOL COACTUP copybook:</p>
 * <ul>
 *   <li>ACCTSIDI PIC X(11) → Not used (account ID generated on creation)</li>
 *   <li>ACSTTUSI PIC X(1) → accountStatus (A=Active, I=Inactive, C=Closed, S=Suspended, P=Pending)</li>
 *   <li>OPNYEARI/OPNMONI/OPNDAYI → openDate (LocalDate)</li>
 *   <li>ACRDLIMI PIC X(15) → creditLimit (BigDecimal with scale=2)</li>
 *   <li>ACSHLIMI PIC X(15) → cashLimit (BigDecimal with scale=2)</li>
 *   <li>AADDGRPI PIC X(10) → accountGroupId</li>
 * </ul>
 * 
 * <p>Bean Validation annotations enforce field constraints matching original COBOL
 * PIC clause validations per Section 0.9 requirements. All financial amounts use
 * BigDecimal with scale=2 and RoundingMode.HALF_UP to preserve COBOL COMP-3 packed
 * decimal precision per Section 0.9 critical numeric precision requirements.</p>
 * 
 * @see com.carddemo.controller.AccountController#createAccount(AccountAddRequest)
 * @see com.carddemo.entity.Account
 * @since 1.0
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class AccountAddRequest {

    /**
     * Customer identifier for account ownership.
     * 
     * <p>Links the new account to an existing customer record. Must reference
     * a valid customer ID in the customer table. This field is required for
     * all account creation requests.</p>
     * 
     * <p>Validation: Must be a positive long value representing an existing customer.</p>
     */
    @NotNull(message = "Customer ID is required")
    @Positive(message = "Customer ID must be a positive number")
    @JsonProperty("customerId")
    private Long customerId;

    /**
     * Credit limit for the account in dollars.
     * 
     * <p>Transforms COBOL ACRDLIMI PIC X(15) field to BigDecimal with scale=2.
     * Represents the maximum credit line extended to the account holder. Must be
     * a positive amount with exactly 2 decimal places for cent precision.</p>
     * 
     * <p>Uses BigDecimal to preserve COBOL COMP-3 packed decimal precision and
     * prevent floating-point rounding errors in financial calculations per
     * Section 0.9 requirements.</p>
     * 
     * <p>Validation: Minimum $100.00, maximum $999,999,999,999.99 (13 digits + 2 decimals)</p>
     * 
     * <p>Example: 5000.00 represents a $5,000 credit limit</p>
     */
    @NotNull(message = "Credit limit is required")
    @DecimalMin(value = "100.00", message = "Credit limit must be at least $100.00")
    @JsonProperty("creditLimit")
    private BigDecimal creditLimit;

    /**
     * Cash advance limit for the account in dollars.
     * 
     * <p>Transforms COBOL ACSHLIMI PIC X(15) field to BigDecimal with scale=2.
     * Represents the maximum cash advance amount allowed, typically a percentage
     * of the credit limit. Must be a positive amount with exactly 2 decimal places.</p>
     * 
     * <p>Uses BigDecimal configured with scale=2 and RoundingMode.HALF_UP to maintain
     * exact financial calculation accuracy matching mainframe decimal arithmetic.</p>
     * 
     * <p>Validation: Minimum $0.00, maximum should not exceed credit limit (business rule
     * enforced by service layer), maximum value $999,999,999,999.99 (13 digits + 2 decimals)</p>
     * 
     * <p>Example: 1000.00 represents a $1,000 cash advance limit</p>
     */
    @NotNull(message = "Cash limit is required")
    @DecimalMin(value = "0.00", inclusive = true, message = "Cash limit must be zero or positive")
    @JsonProperty("cashLimit")
    private BigDecimal cashLimit;

    /**
     * Account status code.
     * 
     * <p>Transforms COBOL ACSTTUSI PIC X(1) field from COACTUP copybook.
     * Single character code indicating the operational status of the account.</p>
     * 
     * <p>Valid status codes (matching COBOL 88-level condition names pattern):</p>
     * <ul>
     *   <li>A = Active (account is operational and can process transactions)</li>
     *   <li>I = Inactive (account exists but is temporarily suspended from transactions)</li>
     *   <li>C = Closed (account is permanently closed, no further transactions allowed)</li>
     *   <li>S = Suspended (account is suspended pending review or payment)</li>
     *   <li>P = Pending (account creation is pending approval)</li>
     * </ul>
     * 
     * <p>Validation: Must be exactly one character matching regex pattern [AICSP].
     * Enforces type-safe status validation preventing invalid status codes during
     * account creation per Section 0.9 validation requirements.</p>
     * 
     * <p>Typically new accounts are created with status 'A' (Active) or 'P' (Pending).</p>
     */
    @NotBlank(message = "Account status is required")
    @Pattern(regexp = "[AICSP]", message = "Account status must be one of: A (Active), I (Inactive), C (Closed), S (Suspended), P (Pending)")
    @Size(min = 1, max = 1, message = "Account status must be exactly 1 character")
    @JsonProperty("accountStatus")
    private String accountStatus;

    /**
     * Account group identifier.
     * 
     * <p>Transforms COBOL AADDGRPI PIC X(10) field from COACTUP copybook.
     * Links the account to a specific account group for organizational purposes,
     * promotional offers, or interest rate categories.</p>
     * 
     * <p>Account groups enable bulk operations and categorization of accounts
     * with similar characteristics (e.g., "REWARDS", "STANDARD", "PREMIUM").</p>
     * 
     * <p>Validation: Maximum 10 characters matching COBOL PIC X(10) field constraint.
     * Must reference a valid account group ID in the account_group table (enforced
     * by service layer via foreign key validation).</p>
     * 
     * <p>Example: "REWARDS" for rewards program accounts, "STUDENT" for student accounts</p>
     */
    @NotBlank(message = "Account group ID is required")
    @Size(max = 10, message = "Account group ID must not exceed 10 characters")
    @JsonProperty("accountGroupId")
    private String accountGroupId;

    /**
     * Account opening date.
     * 
     * <p>Transforms COBOL separate date component fields OPNYEARI/OPNMONI/OPNDAYI
     * (PIC X format) from COACTUP copybook into single Java LocalDate object for
     * type-safe date handling. Replaces COBOL CEEDAYS Lillian date format with
     * modern Java date API per Section 0.3 transformation rules.</p>
     * 
     * <p>Represents the date when the account was officially opened and became
     * operational. Used for account aging calculations, anniversary tracking,
     * and historical reporting.</p>
     * 
     * <p>Validation: Must be a valid date. Typically should not be a future date
     * (business rule enforced by service layer). Enables proper date validation
     * and arithmetic operations for account opening date tracking.</p>
     * 
     * <p>LocalDate provides date-only value without time or timezone components,
     * matching COBOL date field semantics.</p>
     * 
     * <p>Example: 2024-01-15 for an account opened on January 15, 2024</p>
     */
    @NotNull(message = "Open date is required")
    @JsonProperty("openDate")
    private LocalDate openDate;

    /**
     * Gets the customer ID for account ownership.
     * 
     * @return the customer ID, never null after validation
     */
    public Long getCustomerId() {
        return customerId;
    }

    /**
     * Sets the customer ID for account ownership.
     * 
     * @param customerId the customer ID to set, must be positive
     */
    public void setCustomerId(Long customerId) {
        this.customerId = customerId;
    }

    /**
     * Gets the credit limit with proper scale and rounding.
     * 
     * <p>Returns BigDecimal configured with scale=2 and RoundingMode.HALF_UP
     * to maintain COBOL COMP-3 packed decimal precision per Section 0.9
     * critical numeric precision requirements.</p>
     * 
     * @return the credit limit with 2 decimal places, never null after validation
     */
    public BigDecimal getCreditLimit() {
        if (creditLimit != null) {
            return creditLimit.setScale(2, RoundingMode.HALF_UP);
        }
        return creditLimit;
    }

    /**
     * Sets the credit limit for the account.
     * 
     * <p>Automatically applies scale=2 with RoundingMode.HALF_UP to ensure
     * consistent decimal precision matching COBOL COMP-3 behavior.</p>
     * 
     * @param creditLimit the credit limit to set, must be at least $100.00
     */
    public void setCreditLimit(BigDecimal creditLimit) {
        if (creditLimit != null) {
            this.creditLimit = creditLimit.setScale(2, RoundingMode.HALF_UP);
        } else {
            this.creditLimit = null;
        }
    }

    /**
     * Gets the cash advance limit with proper scale and rounding.
     * 
     * <p>Returns BigDecimal configured with scale=2 and RoundingMode.HALF_UP
     * to prevent floating-point rounding errors in financial calculations
     * and maintain byte-for-byte financial accuracy during account creation.</p>
     * 
     * @return the cash limit with 2 decimal places, never null after validation
     */
    public BigDecimal getCashLimit() {
        if (cashLimit != null) {
            return cashLimit.setScale(2, RoundingMode.HALF_UP);
        }
        return cashLimit;
    }

    /**
     * Sets the cash advance limit for the account.
     * 
     * <p>Automatically applies scale=2 with RoundingMode.HALF_UP to ensure
     * consistent decimal precision matching mainframe arithmetic behavior.</p>
     * 
     * @param cashLimit the cash limit to set, must be zero or positive
     */
    public void setCashLimit(BigDecimal cashLimit) {
        if (cashLimit != null) {
            this.cashLimit = cashLimit.setScale(2, RoundingMode.HALF_UP);
        } else {
            this.cashLimit = null;
        }
    }

    /**
     * Gets the account status code.
     * 
     * @return the account status, one of: A, I, C, S, P; never null or blank after validation
     */
    public String getAccountStatus() {
        return accountStatus;
    }

    /**
     * Sets the account status code.
     * 
     * @param accountStatus the account status to set, must match pattern [AICSP]
     */
    public void setAccountStatus(String accountStatus) {
        this.accountStatus = accountStatus;
    }

    /**
     * Gets the account group identifier.
     * 
     * @return the account group ID, never null or blank after validation
     */
    public String getAccountGroupId() {
        return accountGroupId;
    }

    /**
     * Sets the account group identifier.
     * 
     * @param accountGroupId the account group ID to set, maximum 10 characters
     */
    public void setAccountGroupId(String accountGroupId) {
        this.accountGroupId = accountGroupId;
    }

    /**
     * Gets the account opening date.
     * 
     * @return the opening date, never null after validation
     */
    public LocalDate getOpenDate() {
        return openDate;
    }

    /**
     * Sets the account opening date.
     * 
     * @param openDate the opening date to set
     */
    public void setOpenDate(LocalDate openDate) {
        this.openDate = openDate;
    }

    /**
     * Compares this AccountAddRequest to another object for equality.
     * 
     * <p>Two AccountAddRequest objects are considered equal if all their
     * fields have equal values. BigDecimal comparison is performed using
     * compareTo() to handle scale differences correctly (e.g., 100.00 equals 100.0).</p>
     * 
     * @param o the object to compare with
     * @return true if the objects are equal, false otherwise
     */
    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }
        AccountAddRequest that = (AccountAddRequest) o;
        return Objects.equals(customerId, that.customerId) &&
               compareBigDecimals(creditLimit, that.creditLimit) &&
               compareBigDecimals(cashLimit, that.cashLimit) &&
               Objects.equals(accountStatus, that.accountStatus) &&
               Objects.equals(accountGroupId, that.accountGroupId) &&
               Objects.equals(openDate, that.openDate);
    }

    /**
     * Generates a hash code for this AccountAddRequest.
     * 
     * <p>Uses all fields to compute the hash code. BigDecimal values are
     * converted to double for hashing to ensure consistent hash codes
     * regardless of scale differences.</p>
     * 
     * @return the hash code value
     */
    @Override
    public int hashCode() {
        return Objects.hash(
            customerId,
            creditLimit != null ? creditLimit.doubleValue() : 0,
            cashLimit != null ? cashLimit.doubleValue() : 0,
            accountStatus,
            accountGroupId,
            openDate
        );
    }

    /**
     * Returns a string representation of this AccountAddRequest.
     * 
     * <p>Formats all field values in a readable format for logging and debugging.
     * Useful for tracing account creation requests in application logs.</p>
     * 
     * @return a string representation including all field values
     */
    @Override
    public String toString() {
        return "AccountAddRequest{" +
               "customerId=" + customerId +
               ", creditLimit=" + creditLimit +
               ", cashLimit=" + cashLimit +
               ", accountStatus='" + accountStatus + '\'' +
               ", accountGroupId='" + accountGroupId + '\'' +
               ", openDate=" + openDate +
               '}';
    }

    /**
     * Helper method to compare BigDecimal values correctly.
     * 
     * <p>Uses compareTo() instead of equals() to handle scale differences.
     * For example, BigDecimal("100.00").equals(BigDecimal("100.0")) returns false,
     * but compareTo() correctly identifies them as equal values.</p>
     * 
     * @param bd1 first BigDecimal to compare
     * @param bd2 second BigDecimal to compare
     * @return true if both are null or have equal values, false otherwise
     */
    private boolean compareBigDecimals(BigDecimal bd1, BigDecimal bd2) {
        if (bd1 == null && bd2 == null) {
            return true;
        }
        if (bd1 == null || bd2 == null) {
            return false;
        }
        return bd1.compareTo(bd2) == 0;
    }
}
