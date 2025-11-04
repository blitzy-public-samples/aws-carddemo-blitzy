package com.carddemo.entity;

import com.carddemo.constants.BalanceType;
import com.carddemo.constants.BalanceTypeConverter;
import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
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
import java.time.LocalDateTime;

/**
 * JPA Entity representing account balance tracking table for maintaining historical and current
 * account balance snapshots, created for normalized relational database design supporting balance
 * audit and reconciliation.
 * 
 * <p>This entity enables point-in-time balance queries, balance history tracking, and reconciliation
 * reporting. Used for account balance calculation batch jobs (CBACT03C), interest calculation
 * (CBACT04C), and statement generation (CBSTM03A) per Section 0.5 requirements.</p>
 * 
 * <p><strong>Critical Numeric Precision Requirements (Section 0.2 and 0.9):</strong></p>
 * <ul>
 *   <li>All balance fields use BigDecimal with precision=12, scale=2</li>
 *   <li>Matches COBOL PIC S9(10)V99 (10 digits + 2 decimal places)</li>
 *   <li>COMP-3 packed decimal precision preserved using RoundingMode.HALF_UP</li>
 *   <li>All monetary calculations must explicitly call setScale(2, RoundingMode.HALF_UP)</li>
 * </ul>
 * 
 * <p><strong>Entity Relationships:</strong></p>
 * <ul>
 *   <li>Parent entity: Account (many-to-one via account_id foreign key)</li>
 *   <li>Auto-generated primary key: account_balance_id</li>
 *   <li>Foreign key to Account entity via accountId field</li>
 * </ul>
 * 
 * <p><strong>Balance Type Classification:</strong></p>
 * <ul>
 *   <li>CURRENT ('C'): Current actual balance from Account.currentBalance</li>
 *   <li>AVAILABLE ('A'): Available balance (current - pending charges)</li>
 *   <li>PENDING ('P'): Pending transactions not yet posted</li>
 *   <li>HISTORICAL ('H'): End-of-day/period historical snapshot</li>
 * </ul>
 * 
 * <p><strong>Audit Trail Requirements (Section 0.9):</strong></p>
 * <ul>
 *   <li>Every balance change creates a new account_balance record</li>
 *   <li>Historical snapshots retained for regulatory compliance</li>
 *   <li>createdDate and updatedDate for modification tracking</li>
 *   <li>Effective date enables point-in-time balance reconstruction</li>
 * </ul>
 * 
 * <p><strong>Batch Processing Integration (Section 0.5):</strong></p>
 * <ul>
 *   <li>CBACT03C (AccountBalanceJob): Creates daily balance snapshots</li>
 *   <li>CBACT04C (InterestCalculationJob): Updates interest amounts</li>
 *   <li>CBSTM03A (StatementGenerationJob): Queries historical balances</li>
 * </ul>
 * 
 * <p><strong>Database Constraints:</strong></p>
 * <ul>
 *   <li>Primary key: account_balance_id (auto-increment)</li>
 *   <li>Foreign key: account_id REFERENCES account(account_id) ON DELETE CASCADE</li>
 *   <li>Indexes: composite (account_id, effective_date), single (effective_date)</li>
 * </ul>
 * 
 * @author CardDemo Development Team
 * @version 1.0
 * @since 1.0
 * @see Account
 * @see BalanceType
 */
@Entity
@Table(name = "account_balance", indexes = {
    @Index(name = "idx_account_id_effective_date", columnList = "account_id, effective_date"),
    @Index(name = "idx_effective_date", columnList = "effective_date")
})
@Data
@NoArgsConstructor
@AllArgsConstructor
public class AccountBalance implements Serializable {
    
    private static final long serialVersionUID = 1L;
    
    /**
     * Account Balance ID - Auto-generated primary key.
     * 
     * <p>Unique identifier for each balance record. Uses database identity column
     * for auto-increment generation strategy.</p>
     */
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "account_balance_id", nullable = false)
    private Long accountBalanceId;
    
    /**
     * Account ID - Foreign key to Account entity.
     * 
     * <p>11-digit account identifier matching Account.accountId. Establishes
     * many-to-one relationship enabling navigation from balance snapshot to
     * associated account.</p>
     * 
     * <p>Corresponds to COBOL field: ACCT-ID PIC 9(11)</p>
     */
    @Column(name = "account_id", nullable = false)
    private Long accountId;
    
    /**
     * Balance Type - Classification of balance snapshot category.
     * 
     * <p>Enum value specifying the type of balance this record represents:
     * <ul>
     *   <li>CURRENT: Real-time posted balance</li>
     *   <li>AVAILABLE: Spendable balance after pending holds</li>
     *   <li>PENDING: Authorization holds not yet posted</li>
     *   <li>HISTORICAL: Point-in-time snapshot for audit/statements</li>
     * </ul>
     * </p>
     * 
     * <p>Stored as single character in database ('C', 'A', 'P', 'H') for
     * compact storage and VSAM file compatibility.</p>
     * 
     * <p>Uses BalanceTypeConverter to map enum values to single-character codes.</p>
     */
    @Convert(converter = BalanceTypeConverter.class)
    @Column(name = "balance_type", length = 1, nullable = false)
    private BalanceType balanceType;
    
    /**
     * Balance Amount - The balance value for this record.
     * 
     * <p>CRITICAL PRECISION: BigDecimal(12,2) matching COBOL PIC S9(10)V99.
     * All operations must use setScale(2, RoundingMode.HALF_UP) to maintain
     * COMP-3 packed decimal equivalence per Section 0.2 and 0.9 requirements.</p>
     * 
     * <p>Represents the calculated or recorded balance amount depending on
     * balance_type. For HISTORICAL snapshots, this is the end-of-day balance.
     * For CURRENT, this reflects the latest posted balance.</p>
     */
    @Column(name = "balance_amount", precision = 12, scale = 2, nullable = false)
    private BigDecimal balanceAmount;
    
    /**
     * Effective Date - The date this balance was calculated or recorded.
     * 
     * <p>For HISTORICAL balances, this is the business date of the snapshot
     * (typically end-of-day). For CURRENT balances, this is the date of the
     * last balance update. Enables point-in-time balance queries for specific dates.</p>
     * 
     * <p>Used for:</p>
     * <ul>
     *   <li>Historical balance reconstruction</li>
     *   <li>Statement generation for billing periods</li>
     *   <li>Audit trail compliance</li>
     *   <li>Interest calculation based on daily balances</li>
     * </ul>
     */
    @Column(name = "effective_date", nullable = false)
    private LocalDate effectiveDate;
    
    /**
     * Credit Amount - Total credits for the period.
     * 
     * <p>CRITICAL PRECISION: BigDecimal(12,2) with RoundingMode.HALF_UP.
     * Sum of all credit transactions (deposits, refunds, payments) for the
     * period ending on the effective date.</p>
     * 
     * <p>Used in balance calculation formula:
     * <pre>
     * closingBalance = openingBalance + creditAmount - debitAmount
     * </pre>
     * </p>
     */
    @Column(name = "credit_amount", precision = 12, scale = 2, nullable = false)
    private BigDecimal creditAmount;
    
    /**
     * Debit Amount - Total debits for the period.
     * 
     * <p>CRITICAL PRECISION: BigDecimal(12,2) with RoundingMode.HALF_UP.
     * Sum of all debit transactions (purchases, fees, interest charges) for
     * the period ending on the effective date.</p>
     * 
     * <p>Used in balance calculation formula:
     * <pre>
     * closingBalance = openingBalance + creditAmount - debitAmount
     * </pre>
     * </p>
     */
    @Column(name = "debit_amount", precision = 12, scale = 2, nullable = false)
    private BigDecimal debitAmount;
    
    /**
     * Opening Balance - Balance at start of period.
     * 
     * <p>CRITICAL PRECISION: BigDecimal(12,2) with RoundingMode.HALF_UP.
     * The balance at the beginning of the tracking period. For daily snapshots,
     * this is the balance at start of business day.</p>
     * 
     * <p>Can be null for the very first balance record of an account where
     * no prior balance exists. All subsequent records must have opening balance
     * equal to previous period's closing balance for reconciliation.</p>
     */
    @Column(name = "opening_balance", precision = 12, scale = 2)
    private BigDecimal openingBalance;
    
    /**
     * Closing Balance - Balance at end of period.
     * 
     * <p>CRITICAL PRECISION: BigDecimal(12,2) with RoundingMode.HALF_UP.
     * The balance at the end of the tracking period, calculated as:
     * <pre>
     * closingBalance = openingBalance + creditAmount - debitAmount
     * </pre>
     * </p>
     * 
     * <p>For HISTORICAL balance types, this becomes the opening balance for
     * the next period. Must be calculated using exact BigDecimal arithmetic
     * with explicit scale and rounding mode to match COBOL COMP-3 precision.</p>
     * 
     * <p>Example calculation:</p>
     * <pre>
     * BigDecimal closing = opening
     *     .add(creditAmount)
     *     .subtract(debitAmount)
     *     .setScale(2, RoundingMode.HALF_UP);
     * </pre>
     */
    @Column(name = "closing_balance", precision = 12, scale = 2)
    private BigDecimal closingBalance;
    
    /**
     * Interest Amount - Interest calculated or accrued for the period.
     * 
     * <p>CRITICAL PRECISION: BigDecimal(12,2) with RoundingMode.HALF_UP.
     * Interest amount calculated by CBACT04C (InterestCalculationJob) based
     * on daily balance and applicable interest rate.</p>
     * 
     * <p>Interest calculation must use exact BigDecimal arithmetic per
     * Section 0.9 requirements. The calculation preserves COBOL COMP-3
     * precision and rounding behavior to ensure identical results.</p>
     * 
     * <p>Can be null if no interest is applicable or calculated for this
     * balance period.</p>
     */
    @Column(name = "interest_amount", precision = 12, scale = 2)
    private BigDecimal interestAmount;
    
    /**
     * Created Date - Record creation timestamp.
     * 
     * <p>Audit timestamp tracking when this balance record was initially created.
     * Set automatically at insert time and never updated. Required for regulatory
     * compliance audit trail per Section 0.9 requirements.</p>
     * 
     * <p>Used for:</p>
     * <ul>
     *   <li>Audit trail compliance</li>
     *   <li>Troubleshooting balance discrepancies</li>
     *   <li>Tracking batch job execution times</li>
     *   <li>Regulatory reporting</li>
     * </ul>
     */
    @Column(name = "created_date", nullable = false)
    private LocalDateTime createdDate;
    
    /**
     * Updated Date - Record last modification timestamp.
     * 
     * <p>Audit timestamp tracking when this balance record was last modified.
     * Updated automatically on every update operation. Can be null for records
     * that have never been updated after creation.</p>
     * 
     * <p>Used for:</p>
     * <ul>
     *   <li>Audit trail for modifications</li>
     *   <li>Tracking correction or adjustment operations</li>
     *   <li>Identifying stale or outdated balance data</li>
     * </ul>
     */
    @Column(name = "updated_date")
    private LocalDateTime updatedDate;
    
    /**
     * Account Entity - Many-to-one relationship to Account.
     * 
     * <p>Navigates from balance snapshot to the associated account entity.
     * Uses LAZY fetch type to defer loading until explicitly accessed,
     * optimizing query performance per Section 0.2 performance requirements.</p>
     * 
     * <p>Foreign key constraint ensures referential integrity. CASCADE delete
     * ensures balance records are automatically removed when parent account
     * is deleted.</p>
     * 
     * <p>Annotated with @JsonIgnore to prevent circular reference issues during
     * JSON serialization in REST API responses, avoiding infinite recursion
     * between AccountBalance and Account entities.</p>
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "account_id", insertable = false, updatable = false)
    @JsonIgnore
    private Account account;
    
    /**
     * Sets the balance amount with proper COBOL COMP-3 precision.
     * 
     * <p>CRITICAL: Ensures all balance amount values are set with scale=2 and
     * RoundingMode.HALF_UP to match COBOL COMP-3 packed decimal rounding behavior
     * per Section 0.2 and 0.9 MUST requirements.</p>
     * 
     * @param balanceAmount The balance amount to set (null-safe, defaults to zero)
     */
    public void setBalanceAmount(BigDecimal balanceAmount) {
        this.balanceAmount = balanceAmount != null 
            ? balanceAmount.setScale(2, RoundingMode.HALF_UP)
            : BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);
    }
    
    /**
     * Sets the credit amount with proper COBOL COMP-3 precision.
     * 
     * <p>CRITICAL: Ensures all credit amount values are set with scale=2 and
     * RoundingMode.HALF_UP to maintain precision consistency across all balance
     * calculations.</p>
     * 
     * @param creditAmount The credit amount to set (null-safe, defaults to zero)
     */
    public void setCreditAmount(BigDecimal creditAmount) {
        this.creditAmount = creditAmount != null 
            ? creditAmount.setScale(2, RoundingMode.HALF_UP)
            : BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);
    }
    
    /**
     * Sets the debit amount with proper COBOL COMP-3 precision.
     * 
     * <p>CRITICAL: Ensures all debit amount values are set with scale=2 and
     * RoundingMode.HALF_UP to maintain precision consistency across all balance
     * calculations.</p>
     * 
     * @param debitAmount The debit amount to set (null-safe, defaults to zero)
     */
    public void setDebitAmount(BigDecimal debitAmount) {
        this.debitAmount = debitAmount != null 
            ? debitAmount.setScale(2, RoundingMode.HALF_UP)
            : BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);
    }
    
    /**
     * Sets the opening balance with proper COBOL COMP-3 precision.
     * 
     * <p>CRITICAL: Ensures opening balance values are set with scale=2 and
     * RoundingMode.HALF_UP for precision consistency. Allows null for first
     * balance record where no prior balance exists.</p>
     * 
     * @param openingBalance The opening balance to set (nullable)
     */
    public void setOpeningBalance(BigDecimal openingBalance) {
        this.openingBalance = openingBalance != null 
            ? openingBalance.setScale(2, RoundingMode.HALF_UP)
            : null;
    }
    
    /**
     * Sets the closing balance with proper COBOL COMP-3 precision.
     * 
     * <p>CRITICAL: Ensures closing balance values are set with scale=2 and
     * RoundingMode.HALF_UP for precision consistency. This should typically
     * be calculated using the formula:
     * <pre>
     * closingBalance = openingBalance + creditAmount - debitAmount
     * </pre>
     * with explicit scale and rounding mode application.</p>
     * 
     * @param closingBalance The closing balance to set (nullable)
     */
    public void setClosingBalance(BigDecimal closingBalance) {
        this.closingBalance = closingBalance != null 
            ? closingBalance.setScale(2, RoundingMode.HALF_UP)
            : null;
    }
    
    /**
     * Sets the interest amount with proper COBOL COMP-3 precision.
     * 
     * <p>CRITICAL: Ensures interest amount values are set with scale=2 and
     * RoundingMode.HALF_UP to match COBOL interest calculation precision.
     * Used by InterestCalculationJob (CBACT04C).</p>
     * 
     * @param interestAmount The interest amount to set (nullable)
     */
    public void setInterestAmount(BigDecimal interestAmount) {
        this.interestAmount = interestAmount != null 
            ? interestAmount.setScale(2, RoundingMode.HALF_UP)
            : null;
    }
    
    /**
     * Calculates and sets the closing balance from opening balance, credits, and debits.
     * 
     * <p>CRITICAL: Implements the balance calculation formula with exact BigDecimal
     * arithmetic and proper scale/rounding mode to match COBOL COMP-3 behavior:
     * <pre>
     * closingBalance = openingBalance + creditAmount - debitAmount
     * </pre>
     * </p>
     * 
     * <p>This method ensures identical calculation results to the COBOL batch
     * program CBACT03C by explicitly applying scale=2 and RoundingMode.HALF_UP
     * per Section 0.2 and 0.9 requirements.</p>
     * 
     * <p>If openingBalance is null, treats it as zero for calculation purposes.</p>
     */
    public void calculateClosingBalance() {
        BigDecimal opening = this.openingBalance != null 
            ? this.openingBalance 
            : BigDecimal.ZERO;
        BigDecimal credits = this.creditAmount != null 
            ? this.creditAmount 
            : BigDecimal.ZERO;
        BigDecimal debits = this.debitAmount != null 
            ? this.debitAmount 
            : BigDecimal.ZERO;
            
        this.closingBalance = opening
            .add(credits)
            .subtract(debits)
            .setScale(2, RoundingMode.HALF_UP);
    }
}
