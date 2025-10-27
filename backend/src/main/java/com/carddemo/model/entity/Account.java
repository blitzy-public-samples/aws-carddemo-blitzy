package com.carddemo.model.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.PostLoad;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.LocalDate;

/**
 * JPA entity representing account master data.
 * 
 * Converted from COBOL copybook: CVACT01Y.cpy (ACCOUNT-RECORD)
 * Original record length: 300 bytes
 * 
 * This entity stores credit card account information including balances, credit limits,
 * dates, and status. Core financial entity requiring exact numeric precision per
 * Section 0.7.2 of the migration specification.
 * 
 * Conversion notes:
 * - COBOL PIC 9(11) ACCT-ID converted to Long primary key
 * - COBOL PIC S9(10)V99 COMP-3 packed decimal fields converted to BigDecimal with scale 2 and precision 12
 *   to preserve exact financial calculation precision from mainframe
 * - COBOL PIC X(10) date fields converted to LocalDate using YYYY-MM-DD format
 * - COBOL PIC X(01) status field converted to String with length 1
 * - COBOL PIC X(10) string fields converted to String with length 10
 * - COBOL FILLER field (178 bytes) removed as not used
 * - Added audit fields createdAt and updatedAt for tracking record lifecycle
 * - Added version field for JPA optimistic locking (replicates VSAM RBA locking semantics)
 * 
 * Referenced by:
 * - Card entity (card.card_acct_id references account.acct_id)
 * - CardAccountXref entity (cross-reference table)
 * - TransactionCategoryBalance entity (transaction category balances by account)
 * 
 * Database indexes:
 * - Primary key index on acct_id (automatic)
 * - Index on acct_active_status for filtering active accounts
 * - Index on acct_group_id for group-based queries
 * 
 * @see Card
 * @see CardAccountXref
 * @see TransactionCategoryBalance
 */
@Entity
@Table(name = "account", indexes = {
    @Index(name = "idx_account_status", columnList = "acct_active_status"),
    @Index(name = "idx_account_group", columnList = "acct_group_id")
})
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Account {

    /**
     * Account identifier (primary key).
     * 
     * Converted from: COBOL PIC 9(11) ACCT-ID
     * Maximum value: 99,999,999,999 (11 digits)
     * 
     * This is the primary key for account records, equivalent to the VSAM KSDS
     * primary key in the mainframe ACCTFILE dataset.
     */
    @Id
    @Column(name = "acct_id", nullable = false)
    private Long acctId;

    /**
     * Account active status indicator.
     * 
     * Converted from: COBOL PIC X(01) ACCT-ACTIVE-STATUS
     * Valid values: 'Y' (active), 'N' (inactive), 'C' (closed), 'S' (suspended)
     * 
     * Used to filter active accounts in queries and enforce business rules
     * for transaction processing.
     * 
     * Column Definition: VARCHAR(1) for Hibernate compatibility with PostgreSQL.
     * Per Section 0.7.2: Must maintain exact COBOL PIC X(01) behavior.
     * Note: Changed from CHAR(1) to VARCHAR(1) to resolve Hibernate schema validation issues.
     */
    @Column(name = "acct_active_status", nullable = false, length = 1)
    private String acctActiveStatus;

    /**
     * Current account balance.
     * 
     * Converted from: COBOL PIC S9(10)V99 COMP-3 ACCT-CURR-BAL
     * Precision: 12 digits total, 2 decimal places
     * Range: -9,999,999,999.99 to 9,999,999,999.99
     * 
     * Uses BigDecimal to preserve COBOL COMP-3 packed decimal precision and
     * ensure bit-identical financial calculations per Section 0.7.2 requirement.
     * Updated by transaction posting batch jobs and online transaction processing.
     */
    @Column(name = "acct_curr_bal", nullable = false, precision = 12, scale = 2, columnDefinition = "NUMERIC(12,2) DEFAULT 0.00")
    private BigDecimal acctCurrBal;

    /**
     * Credit limit for purchases.
     * 
     * Converted from: COBOL PIC S9(10)V99 COMP-3 ACCT-CREDIT-LIMIT
     * Precision: 12 digits total, 2 decimal places
     * 
     * Maximum credit limit allowed for purchase transactions on this account.
     * Used in transaction authorization logic to decline over-limit purchases.
     */
    @Column(name = "acct_credit_limit", nullable = false, precision = 12, scale = 2)
    private BigDecimal acctCreditLimit;

    /**
     * Cash advance credit limit.
     * 
     * Converted from: COBOL PIC S9(10)V99 COMP-3 ACCT-CASH-CREDIT-LIMIT
     * Precision: 12 digits total, 2 decimal places
     * 
     * Maximum credit limit allowed for cash advance transactions on this account.
     * Typically lower than purchase credit limit per banking regulations.
     */
    @Column(name = "acct_cash_credit_limit", nullable = false, precision = 12, scale = 2)
    private BigDecimal acctCashCreditLimit;

    /**
     * Account opening date.
     * 
     * Converted from: COBOL PIC X(10) ACCT-OPEN-DATE
     * Format: YYYY-MM-DD
     * 
     * Date the account was originally opened. Used for account age calculations,
     * anniversary processing, and reporting.
     */
    @Column(name = "acct_open_date", nullable = false)
    private LocalDate acctOpenDate;

    /**
     * Account expiration date.
     * 
     * Converted from: COBOL PIC X(10) ACCT-EXPIRAION-DATE
     * (Note: COBOL field name contains typo "EXPIRAION" but mapped correctly to "acctExpirationDate")
     * Format: YYYY-MM-DD
     * 
     * Date when account expires and renewal processing is required.
     * Nullable as some account types may not have expiration dates.
     */
    @Column(name = "acct_expiration_date")
    private LocalDate acctExpirationDate;

    /**
     * Account reissue date.
     * 
     * Converted from: COBOL PIC X(10) ACCT-REISSUE-DATE
     * Format: YYYY-MM-DD
     * 
     * Most recent date when account was reissued (e.g., after card theft/loss).
     * Nullable as not all accounts have been reissued.
     */
    @Column(name = "acct_reissue_date")
    private LocalDate acctReissueDate;

    /**
     * Current cycle credit total.
     * 
     * Converted from: COBOL PIC S9(10)V99 COMP-3 ACCT-CURR-CYC-CREDIT
     * Precision: 12 digits total, 2 decimal places
     * 
     * Sum of all credit transactions (payments, refunds) posted during the current
     * billing cycle. Reset to zero at cycle close. Used in billing statement generation.
     */
    @Column(name = "acct_curr_cyc_credit", precision = 12, scale = 2, columnDefinition = "NUMERIC(12,2) DEFAULT 0.00")
    private BigDecimal acctCurrCycCredit;

    /**
     * Current cycle debit total.
     * 
     * Converted from: COBOL PIC S9(10)V99 COMP-3 ACCT-CURR-CYC-DEBIT
     * Precision: 12 digits total, 2 decimal places
     * 
     * Sum of all debit transactions (purchases, cash advances, fees) posted during
     * the current billing cycle. Reset to zero at cycle close. Used in billing
     * statement generation and available credit calculations.
     */
    @Column(name = "acct_curr_cyc_debit", precision = 12, scale = 2, columnDefinition = "NUMERIC(12,2) DEFAULT 0.00")
    private BigDecimal acctCurrCycDebit;

    /**
     * Account billing address ZIP code.
     * 
     * Converted from: COBOL PIC X(10) ACCT-ADDR-ZIP
     * Maximum length: 10 characters (supports ZIP+4 format)
     * 
     * ZIP code for account billing address. Used for geographic reporting and
     * fraud detection (e.g., comparing transaction location to billing address).
     */
    @Column(name = "acct_addr_zip", length = 10)
    private String acctAddrZip;

    /**
     * Account group identifier.
     * 
     * Converted from: COBOL PIC X(10) ACCT-GROUP-ID
     * Maximum length: 10 characters
     * 
     * Grouping code for account categorization (e.g., by product type, market segment,
     * or organizational unit). Used for batch processing grouping and reporting.
     */
    @Column(name = "acct_group_id", length = 10)
    private String acctGroupId;

    /**
     * Record creation timestamp.
     * 
     * Added field (not in original COBOL copybook).
     * 
     * Automatically set to current timestamp when record is first inserted into
     * the database. Used for audit tracking and data lineage.
     */
    @Column(name = "created_at", nullable = false, updatable = false, columnDefinition = "TIMESTAMP DEFAULT CURRENT_TIMESTAMP")
    private Timestamp createdAt;

    /**
     * Record last update timestamp.
     * 
     * Added field (not in original COBOL copybook).
     * 
     * Automatically updated to current timestamp whenever record is modified.
     * Used for audit tracking, cache invalidation, and optimistic locking support.
     */
    @Column(name = "updated_at", nullable = false, columnDefinition = "TIMESTAMP DEFAULT CURRENT_TIMESTAMP")
    private Timestamp updatedAt;

    /**
     * Version number for optimistic locking.
     * 
     * Added field (not in original COBOL copybook).
     * 
     * JPA version field automatically incremented on each update to prevent
     * concurrent update conflicts. Replicates COBOL VSAM RBA (Relative Byte Address)
     * optimistic locking semantics from mainframe CICS transaction processing.
     * 
     * When a transaction attempts to update an account record, JPA compares the
     * version number in memory with the database. If they differ, another transaction
     * has modified the record, and an OptimisticLockException is thrown, requiring
     * the transaction to re-read and retry.
     */
    @Version
    @Column(name = "version", nullable = false, columnDefinition = "INTEGER DEFAULT 0")
    private Integer version;

    /**
     * JPA lifecycle callback: Automatically set timestamps before persisting new entity.
     * 
     * This method is invoked automatically by JPA before INSERT operations.
     * Sets both createdAt and updatedAt to current timestamp for new records.
     * 
     * Replaces manual timestamp setting that would be required in COBOL programs
     * where developers explicitly set CURRENT-DATE fields.
     */
    @PrePersist
    protected void onCreate() {
        Timestamp now = new Timestamp(System.currentTimeMillis());
        this.createdAt = now;
        this.updatedAt = now;
        if (this.version == null) {
            this.version = 0;
        }
    }

    /**
     * JPA lifecycle callback: Automatically update timestamp before updating entity.
     * 
     * This method is invoked automatically by JPA before UPDATE operations.
     * Updates the updatedAt timestamp to current time, leaving createdAt unchanged.
     * 
     * Replaces manual timestamp updating that would be required in COBOL programs
     * during REWRITE operations.
     */
    @PreUpdate
    protected void onUpdate() {
        this.updatedAt = new Timestamp(System.currentTimeMillis());
    }

    /**
     * JPA lifecycle callback: Normalize BigDecimal scales after loading entity from database.
     * 
     * PostgreSQL NUMERIC columns don't always preserve scale when returning values via JDBC.
     * For example, 0.00 might be returned as 0 with scale=0 instead of scale=2.
     * This method ensures all financial BigDecimal fields maintain scale=2 to preserve
     * COBOL COMP-3 packed decimal precision per Section 0.7.2 requirement.
     * 
     * This normalization is critical for:
     * - Bit-identical financial calculations matching mainframe COBOL behavior
     * - Consistent decimal precision across all account balance operations
     * - Meeting requirement that BigDecimal scale must always be 2 for currency amounts
     * 
     * Invoked automatically by JPA after loading entity from database queries.
     */
    @PostLoad
    protected void normalizeDecimalScales() {
        if (this.acctCurrBal != null) {
            this.acctCurrBal = this.acctCurrBal.setScale(2, java.math.RoundingMode.HALF_UP);
        }
        if (this.acctCreditLimit != null) {
            this.acctCreditLimit = this.acctCreditLimit.setScale(2, java.math.RoundingMode.HALF_UP);
        }
        if (this.acctCashCreditLimit != null) {
            this.acctCashCreditLimit = this.acctCashCreditLimit.setScale(2, java.math.RoundingMode.HALF_UP);
        }
        if (this.acctCurrCycCredit != null) {
            this.acctCurrCycCredit = this.acctCurrCycCredit.setScale(2, java.math.RoundingMode.HALF_UP);
        }
        if (this.acctCurrCycDebit != null) {
            this.acctCurrCycDebit = this.acctCurrCycDebit.setScale(2, java.math.RoundingMode.HALF_UP);
        }
    }
}
