package com.carddemo.entity;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;

/**
 * JPA Entity representing the Account master data table.
 * 
 * <p>This entity is transformed from COBOL copybook CVACT01Y.cpy (ACCOUNT-RECORD)
 * which defines a 300-byte record structure for account information stored in the
 * VSAM ACCTDAT KSDS file. This entity establishes the critical account-to-customer
 * relationship and contains all financial balance and limit data.</p>
 * 
 * <p><strong>Critical Numeric Precision Requirements (Section 0.2 and 0.9):</strong></p>
 * <ul>
 *   <li>All COBOL PIC S9(10)V99 fields map to BigDecimal with precision=12, scale=2</li>
 *   <li>COMP-3 packed decimal precision preserved using RoundingMode.HALF_UP</li>
 *   <li>All monetary calculations must explicitly call setScale(2, RoundingMode.HALF_UP)</li>
 *   <li>NO float or double types allowed for financial amounts</li>
 * </ul>
 * 
 * <p><strong>Entity Relationships:</strong></p>
 * <ul>
 *   <li>Parent entity: Customer (many-to-one via customer_id foreign key)</li>
 *   <li>Child entities: Card (one-to-many), AccountXref, AccountBalance</li>
 *   <li>11-digit account ID serves as primary key</li>
 * </ul>
 * 
 * <p><strong>Data Transformation Details:</strong></p>
 * <ul>
 *   <li>ACCT-ID PIC 9(11) → Long accountId (primary key)</li>
 *   <li>ACCT-ACTIVE-STATUS PIC X(01) → String activeStatus ('Y'/'N')</li>
 *   <li>ACCT-CURR-BAL PIC S9(10)V99 → BigDecimal currentBalance (12,2)</li>
 *   <li>ACCT-CREDIT-LIMIT PIC S9(10)V99 → BigDecimal creditLimit (12,2)</li>
 *   <li>ACCT-CASH-CREDIT-LIMIT PIC S9(10)V99 → BigDecimal cashCreditLimit (12,2)</li>
 *   <li>ACCT-OPEN-DATE PIC X(10) → LocalDate openDate</li>
 *   <li>ACCT-EXPIRAION-DATE PIC X(10) → LocalDate expirationDate (typo in COBOL)</li>
 *   <li>ACCT-REISSUE-DATE PIC X(10) → LocalDate reissueDate</li>
 *   <li>ACCT-CURR-CYC-CREDIT PIC S9(10)V99 → BigDecimal currentCycleCredit (12,2)</li>
 *   <li>ACCT-CURR-CYC-DEBIT PIC S9(10)V99 → BigDecimal currentCycleDebit (12,2)</li>
 *   <li>ACCT-ADDR-ZIP PIC X(10) → String addressZip</li>
 *   <li>ACCT-GROUP-ID PIC X(10) → String accountGroupId</li>
 *   <li>FILLER PIC X(178) → NOT MAPPED (unused COBOL padding)</li>
 * </ul>
 * 
 * <p><strong>Note on Customer Relationship:</strong> The COBOL ACCOUNT-RECORD doesn't 
 * explicitly store customer_id, but the relationship exists via the XREF cross-reference 
 * file. In the normalized PostgreSQL design, customer_id is added as a foreign key column
 * with proper referential integrity constraints per Section 0.9 requirements.</p>
 * 
 * <p><strong>COBOL Source:</strong> app/cpy/CVACT01Y.cpy</p>
 * <p><strong>VSAM File:</strong> ACCTDAT KSDS (Key-Sequenced Dataset)</p>
 * <p><strong>Record Length:</strong> 300 bytes</p>
 * 
 * @see Customer
 * @see <a href="Section 0.6">File-by-File Transformation Plan</a>
 * @see <a href="Section 0.3">COBOL to Java Type Conversion Rules</a>
 * @see <a href="Section 0.9">Critical Numeric Precision Requirements</a>
 */
@Entity
@Table(name = "account")
@Data
@NoArgsConstructor
@AllArgsConstructor
public class Account implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * Account unique identifier (11-digit numeric).
     * Maps to COBOL field: ACCT-ID PIC 9(11)
     * Primary key for Account entity.
     */
    @Id
    @Column(name = "account_id", nullable = false)
    private Long accountId;

    /**
     * Account active status indicator (1 character).
     * Maps to COBOL field: ACCT-ACTIVE-STATUS PIC X(01)
     * Typical values: 'Y' (Yes/Active), 'N' (No/Inactive)
     */
    @Column(name = "active_status", length = 1, nullable = false)
    private String activeStatus;

    /**
     * Current account balance with COBOL COMP-3 precision preservation.
     * Maps to COBOL field: ACCT-CURR-BAL PIC S9(10)V99
     * 
     * <p><strong>CRITICAL:</strong> This field represents monetary balance and MUST maintain
     * exact decimal precision matching COBOL COMP-3 packed decimal format.</p>
     * <ul>
     *   <li>Precision: 12 digits total (10 integer + 2 decimal)</li>
     *   <li>Scale: 2 decimal places</li>
     *   <li>Rounding: RoundingMode.HALF_UP (round to nearest neighbor, ties round up)</li>
     *   <li>All arithmetic operations MUST call: .setScale(2, RoundingMode.HALF_UP)</li>
     * </ul>
     * 
     * <p>Example: balance.add(amount).setScale(2, RoundingMode.HALF_UP)</p>
     */
    @Column(name = "current_balance", precision = 12, scale = 2, nullable = false)
    private BigDecimal currentBalance;

    /**
     * Credit limit for purchases with COBOL COMP-3 precision preservation.
     * Maps to COBOL field: ACCT-CREDIT-LIMIT PIC S9(10)V99
     * 
     * <p>Maximum credit amount available for purchases. Precision and rounding
     * requirements identical to currentBalance field.</p>
     */
    @Column(name = "credit_limit", precision = 12, scale = 2, nullable = false)
    private BigDecimal creditLimit;

    /**
     * Cash advance credit limit with COBOL COMP-3 precision preservation.
     * Maps to COBOL field: ACCT-CASH-CREDIT-LIMIT PIC S9(10)V99
     * 
     * <p>Maximum credit amount available for cash advances. Precision and rounding
     * requirements identical to currentBalance field.</p>
     */
    @Column(name = "cash_credit_limit", precision = 12, scale = 2, nullable = false)
    private BigDecimal cashCreditLimit;

    /**
     * Account opening date.
     * Maps to COBOL field: ACCT-OPEN-DATE PIC X(10)
     * 
     * <p>Original COBOL format: String date representation (YYYY-MM-DD or similar).</p>
     * <p>Converted to LocalDate for type-safe date handling with ISO-8601 formatting.</p>
     */
    @Column(name = "open_date")
    private LocalDate openDate;

    /**
     * Account expiration date.
     * Maps to COBOL field: ACCT-EXPIRAION-DATE PIC X(10)
     * 
     * <p><strong>Note:</strong> Field name in COBOL has typo "EXPIRAION" instead of "EXPIRATION".</p>
     * <p>Date when the account expires or requires renewal.</p>
     */
    @Column(name = "expiration_date")
    private LocalDate expirationDate;

    /**
     * Account reissue date.
     * Maps to COBOL field: ACCT-REISSUE-DATE PIC X(10)
     * 
     * <p>Date when account was last reissued (e.g., card replacement, account renewal).</p>
     */
    @Column(name = "reissue_date")
    private LocalDate reissueDate;

    /**
     * Current billing cycle credit total with COBOL COMP-3 precision preservation.
     * Maps to COBOL field: ACCT-CURR-CYC-CREDIT PIC S9(10)V99
     * 
     * <p>Sum of all credit transactions (payments, returns) in the current billing cycle.
     * Precision and rounding requirements identical to currentBalance field.</p>
     */
    @Column(name = "current_cycle_credit", precision = 12, scale = 2, nullable = false)
    private BigDecimal currentCycleCredit;

    /**
     * Current billing cycle debit total with COBOL COMP-3 precision preservation.
     * Maps to COBOL field: ACCT-CURR-CYC-DEBIT PIC S9(10)V99
     * 
     * <p>Sum of all debit transactions (purchases, fees) in the current billing cycle.
     * Precision and rounding requirements identical to currentBalance field.</p>
     */
    @Column(name = "current_cycle_debit", precision = 12, scale = 2, nullable = false)
    private BigDecimal currentCycleDebit;

    /**
     * Account billing address ZIP code (up to 10 characters).
     * Maps to COBOL field: ACCT-ADDR-ZIP PIC X(10)
     * 
     * <p>ZIP/postal code for account billing address. May differ from customer's
     * primary address ZIP code.</p>
     */
    @Column(name = "address_zip", length = 10)
    private String addressZip;

    /**
     * Account group identifier (up to 10 characters).
     * Maps to COBOL field: ACCT-GROUP-ID PIC X(10)
     * 
     * <p>Identifies the account group for reporting, pricing tiers, or
     * promotional categorization purposes.</p>
     */
    @Column(name = "account_group_id", length = 10)
    private String accountGroupId;

    /**
     * Parent customer relationship (many-to-one).
     * 
     * <p>Establishes foreign key relationship from Account to Customer entity.
     * The COBOL ACCOUNT-RECORD structure doesn't explicitly contain customer_id,
     * but the relationship exists via XREF cross-reference file. In the normalized
     * PostgreSQL design, customer_id is added as a foreign key column with proper
     * referential integrity per Section 0.9 requirements.</p>
     * 
     * <p><strong>Fetch Strategy:</strong> LAZY loading defers parent customer retrieval
     * until explicitly accessed, optimizing query performance and reducing memory
     * footprint. This maintains sub-200ms response times per Section 0.2 performance
     * requirements under 10,000 TPS load.</p>
     * 
     * <p><strong>JSON Serialization:</strong> @JsonIgnore prevents circular reference
     * issues when Account entities are serialized in REST API responses, avoiding
     * infinite recursion between Account and Customer entities.</p>
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "customer_id", nullable = false)
    @JsonIgnore
    private Customer customer;

    /**
     * Custom setter for currentBalance ensuring COBOL COMP-3 precision preservation.
     * 
     * <p>All balance updates MUST use this setter to guarantee proper scale and
     * rounding mode. This prevents precision loss and ensures identical results
     * to mainframe COBOL financial calculations per Section 0.9 requirements.</p>
     * 
     * @param currentBalance The balance value to set (will be scaled to 2 decimals with HALF_UP rounding)
     */
    public void setCurrentBalance(BigDecimal currentBalance) {
        this.currentBalance = currentBalance != null 
            ? currentBalance.setScale(2, RoundingMode.HALF_UP)
            : BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);
    }

    /**
     * Custom setter for creditLimit ensuring COBOL COMP-3 precision preservation.
     * 
     * @param creditLimit The credit limit value to set (will be scaled to 2 decimals with HALF_UP rounding)
     */
    public void setCreditLimit(BigDecimal creditLimit) {
        this.creditLimit = creditLimit != null 
            ? creditLimit.setScale(2, RoundingMode.HALF_UP)
            : BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);
    }

    /**
     * Custom setter for cashCreditLimit ensuring COBOL COMP-3 precision preservation.
     * 
     * @param cashCreditLimit The cash credit limit value to set (will be scaled to 2 decimals with HALF_UP rounding)
     */
    public void setCashCreditLimit(BigDecimal cashCreditLimit) {
        this.cashCreditLimit = cashCreditLimit != null 
            ? cashCreditLimit.setScale(2, RoundingMode.HALF_UP)
            : BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);
    }

    /**
     * Custom setter for currentCycleCredit ensuring COBOL COMP-3 precision preservation.
     * 
     * @param currentCycleCredit The current cycle credit value to set (will be scaled to 2 decimals with HALF_UP rounding)
     */
    public void setCurrentCycleCredit(BigDecimal currentCycleCredit) {
        this.currentCycleCredit = currentCycleCredit != null 
            ? currentCycleCredit.setScale(2, RoundingMode.HALF_UP)
            : BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);
    }

    /**
     * Custom setter for currentCycleDebit ensuring COBOL COMP-3 precision preservation.
     * 
     * @param currentCycleDebit The current cycle debit value to set (will be scaled to 2 decimals with HALF_UP rounding)
     */
    public void setCurrentCycleDebit(BigDecimal currentCycleDebit) {
        this.currentCycleDebit = currentCycleDebit != null 
            ? currentCycleDebit.setScale(2, RoundingMode.HALF_UP)
            : BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);
    }

    /**
     * Calculates available credit on the account.
     * 
     * <p>Available credit = Credit Limit - Current Balance</p>
     * <p>This calculation maintains COBOL COMP-3 precision with proper scale and rounding.</p>
     * 
     * @return Available credit amount with 2 decimal places precision
     */
    public BigDecimal getAvailableCredit() {
        if (creditLimit == null || currentBalance == null) {
            return BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);
        }
        return creditLimit.subtract(currentBalance).setScale(2, RoundingMode.HALF_UP);
    }

    /**
     * Calculates available cash credit on the account.
     * 
     * <p>Available cash credit = Cash Credit Limit - Current Balance</p>
     * <p>This calculation maintains COBOL COMP-3 precision with proper scale and rounding.</p>
     * 
     * @return Available cash credit amount with 2 decimal places precision
     */
    public BigDecimal getAvailableCashCredit() {
        if (cashCreditLimit == null || currentBalance == null) {
            return BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);
        }
        return cashCreditLimit.subtract(currentBalance).setScale(2, RoundingMode.HALF_UP);
    }

    /**
     * Checks if the account is currently active.
     * 
     * @return true if activeStatus is 'Y', false otherwise
     */
    public boolean isActive() {
        return "Y".equalsIgnoreCase(activeStatus);
    }

    /**
     * Checks if the account has expired.
     * 
     * @return true if expirationDate is in the past, false otherwise or if expirationDate is null
     */
    public boolean isExpired() {
        if (expirationDate == null) {
            return false;
        }
        return LocalDate.now().isAfter(expirationDate);
    }

    /**
     * Custom toString implementation that provides detailed account information
     * without exposing the parent Customer relationship (to prevent circular references).
     * 
     * @return String representation of Account entity
     */
    @Override
    public String toString() {
        return "Account{" +
                "accountId=" + accountId +
                ", activeStatus='" + activeStatus + '\'' +
                ", currentBalance=" + currentBalance +
                ", creditLimit=" + creditLimit +
                ", cashCreditLimit=" + cashCreditLimit +
                ", openDate=" + openDate +
                ", expirationDate=" + expirationDate +
                ", reissueDate=" + reissueDate +
                ", currentCycleCredit=" + currentCycleCredit +
                ", currentCycleDebit=" + currentCycleDebit +
                ", addressZip='" + addressZip + '\'' +
                ", accountGroupId='" + accountGroupId + '\'' +
                '}';
    }
}

