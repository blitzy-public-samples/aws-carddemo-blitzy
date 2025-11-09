package com.carddemo.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * JPA Entity representing Account master data.
 * 
 * <p>This entity corresponds to the COBOL ACCOUNT-RECORD copybook structure
 * defined in CVACT01Y.cpy with a 300-byte record layout. It represents account
 * information stored in the VSAM KSDS ACCTDAT file, now migrated to PostgreSQL
 * account table.</p>
 * 
 * <p>Key Transformations from COBOL:</p>
 * <ul>
 *   <li>ACCT-ID (PIC 9(11)) → Long accountId (Primary Key)</li>
 *   <li>ACCT-ACTIVE-STATUS (PIC X(01)) → String activeStatus</li>
 *   <li>ACCT-CURR-BAL (PIC S9(10)V99) → BigDecimal currentBalance (precision=12, scale=2)</li>
 *   <li>ACCT-CREDIT-LIMIT (PIC S9(10)V99) → BigDecimal creditLimit (precision=12, scale=2)</li>
 *   <li>ACCT-CASH-CREDIT-LIMIT (PIC S9(10)V99) → BigDecimal cashCreditLimit (precision=12, scale=2)</li>
 *   <li>ACCT-OPEN-DATE (PIC X(10)) → LocalDate openDate</li>
 *   <li>ACCT-EXPIRAION-DATE (PIC X(10)) → LocalDate expirationDate</li>
 *   <li>ACCT-REISSUE-DATE (PIC X(10)) → LocalDate reissueDate</li>
 *   <li>ACCT-CURR-CYC-CREDIT (PIC S9(10)V99) → BigDecimal currentCycleCredit (precision=12, scale=2)</li>
 *   <li>ACCT-CURR-CYC-DEBIT (PIC S9(10)V99) → BigDecimal currentCycleDebit (precision=12, scale=2)</li>
 *   <li>ACCT-ADDR-ZIP (PIC X(10)) → String addressZip</li>
 *   <li>ACCT-GROUP-ID (PIC X(10)) → String groupId</li>
 * </ul>
 * 
 * <p><strong>COBOL COMP-3 to BigDecimal Precision Mapping:</strong></p>
 * <p>All monetary fields use BigDecimal with precision=12 and scale=2 to preserve
 * exact decimal precision from COBOL COMP-3 packed decimal fields (PIC S9(10)V99).
 * This ensures identical financial calculation results matching mainframe behavior
 * with RoundingMode.HALF_UP for all arithmetic operations.</p>
 * 
 * <p><strong>Cross-Reference Relationships:</strong></p>
 * <p>Based on CVACT03Y.cpy (CARD-XREF-RECORD), accounts are linked to customers
 * through XREF-CUST-ID to XREF-ACCT-ID mapping. This relationship is implemented
 * as @ManyToOne to Customer entity, establishing foreign key constraint.</p>
 * 
 * <p>The entity includes optimistic locking via the @Version annotation to handle
 * concurrent access patterns that replicate VSAM record locking behavior, ensuring
 * data consistency during concurrent updates.</p>
 * 
 * <p>Lombok annotations are used to reduce boilerplate:</p>
 * <ul>
 *   <li>@Data - generates getters, setters, equals, hashCode, toString</li>
 *   <li>@NoArgsConstructor - generates no-args constructor required by JPA</li>
 *   <li>@AllArgsConstructor - generates constructor with all fields</li>
 *   <li>@Builder - generates builder pattern for fluent object construction</li>
 * </ul>
 * 
 * <p><strong>Performance Considerations:</strong></p>
 * <p>The account_id field is indexed as primary key matching VSAM KSDS key structure.
 * Additional composite indexes are created in Flyway migration V7__create_indexes.sql
 * to support efficient queries by customer_id, active_status, and date ranges.</p>
 * 
 * @see Customer
 * @see <a href="Section 0.4">Agent Action Plan - Source File app/cpy/CVACT01Y.cpy</a>
 * @see <a href="Section 0.6">File-by-File Transformation Plan</a>
 * @see <a href="Section 0.10">Special Instructions - COBOL COMP-3 to Java BigDecimal</a>
 */
@Entity
@Table(name = "account")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Account implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * Account ID - Primary Key
     * 
     * <p>Maps from COBOL ACCT-ID field (PIC 9(11)).</p>
     * <p>11-digit numeric identifier uniquely identifying each account.</p>
     * <p>This field serves as the primary key matching VSAM KSDS key structure
     * for account master file (ACCTDAT).</p>
     */
    @Id
    @Column(name = "account_id", nullable = false, precision = 11)
    private Long accountId;

    /**
     * Account Active Status
     * 
     * <p>Maps from COBOL ACCT-ACTIVE-STATUS field (PIC X(01)).</p>
     * <p>Single character indicator for account status:</p>
     * <ul>
     *   <li>'Y' - Active account</li>
     *   <li>'N' - Inactive account</li>
     * </ul>
     */
    @Column(name = "active_status", length = 1)
    private String activeStatus;

    /**
     * Current Account Balance
     * 
     * <p>Maps from COBOL ACCT-CURR-BAL field (PIC S9(10)V99 COMP-3).</p>
     * <p>Current outstanding balance on the account. Uses BigDecimal with
     * precision=12 and scale=2 to preserve exact COBOL COMP-3 packed decimal
     * precision for financial calculations.</p>
     * <p>Positive values indicate amount owed by customer.</p>
     */
    @Column(name = "current_balance", precision = 12, scale = 2)
    private BigDecimal currentBalance;

    /**
     * Credit Limit
     * 
     * <p>Maps from COBOL ACCT-CREDIT-LIMIT field (PIC S9(10)V99 COMP-3).</p>
     * <p>Maximum credit limit authorized for this account. Uses BigDecimal with
     * precision=12 and scale=2 to preserve exact decimal precision matching
     * COBOL COMP-3 behavior.</p>
     * <p>All purchases must not exceed this limit minus current balance.</p>
     */
    @Column(name = "credit_limit", precision = 12, scale = 2)
    private BigDecimal creditLimit;

    /**
     * Cash Credit Limit
     * 
     * <p>Maps from COBOL ACCT-CASH-CREDIT-LIMIT field (PIC S9(10)V99 COMP-3).</p>
     * <p>Maximum cash advance limit authorized for this account. Uses BigDecimal
     * with precision=12 and scale=2 to preserve exact decimal precision.</p>
     * <p>Cash advances are subject to this separate limit within the overall
     * credit limit.</p>
     */
    @Column(name = "cash_credit_limit", precision = 12, scale = 2)
    private BigDecimal cashCreditLimit;

    /**
     * Account Open Date
     * 
     * <p>Maps from COBOL ACCT-OPEN-DATE field (PIC X(10)).</p>
     * <p>Date when the account was originally opened, stored in ISO format
     * (YYYY-MM-DD). Uses LocalDate for timezone-agnostic date storage.</p>
     */
    @Column(name = "open_date")
    private LocalDate openDate;

    /**
     * Account Expiration Date
     * 
     * <p>Maps from COBOL ACCT-EXPIRAION-DATE field (PIC X(10)).</p>
     * <p>Date when the account expires or is scheduled for renewal, stored in
     * ISO format (YYYY-MM-DD). Note: Original COBOL field name has typo
     * "EXPIRAION" preserved in documentation for traceability.</p>
     */
    @Column(name = "expiration_date")
    private LocalDate expirationDate;

    /**
     * Account Reissue Date
     * 
     * <p>Maps from COBOL ACCT-REISSUE-DATE field (PIC X(10)).</p>
     * <p>Date when the account was last reissued (e.g., after card replacement),
     * stored in ISO format (YYYY-MM-DD).</p>
     */
    @Column(name = "reissue_date")
    private LocalDate reissueDate;

    /**
     * Current Cycle Credit Amount
     * 
     * <p>Maps from COBOL ACCT-CURR-CYC-CREDIT field (PIC S9(10)V99 COMP-3).</p>
     * <p>Total credit (payments, refunds) posted to account in current billing
     * cycle. Uses BigDecimal with precision=12 and scale=2 to preserve exact
     * decimal precision for statement generation.</p>
     */
    @Column(name = "current_cycle_credit", precision = 12, scale = 2)
    private BigDecimal currentCycleCredit;

    /**
     * Current Cycle Debit Amount
     * 
     * <p>Maps from COBOL ACCT-CURR-CYC-DEBIT field (PIC S9(10)V99 COMP-3).</p>
     * <p>Total debit (purchases, fees) posted to account in current billing
     * cycle. Uses BigDecimal with precision=12 and scale=2 to preserve exact
     * decimal precision for statement generation and interest calculation.</p>
     */
    @Column(name = "current_cycle_debit", precision = 12, scale = 2)
    private BigDecimal currentCycleDebit;

    /**
     * Account Address ZIP Code
     * 
     * <p>Maps from COBOL ACCT-ADDR-ZIP field (PIC X(10)).</p>
     * <p>ZIP or postal code for account billing address, maximum 10 characters
     * to accommodate ZIP+4 format or international postal codes.</p>
     */
    @Column(name = "address_zip", length = 10)
    private String addressZip;

    /**
     * Account Group ID
     * 
     * <p>Maps from COBOL ACCT-GROUP-ID field (PIC X(10)).</p>
     * <p>Identifier for grouping related accounts (e.g., corporate accounts,
     * family accounts), maximum 10 characters.</p>
     */
    @Column(name = "group_id", length = 10)
    private String groupId;

    /**
     * Customer - Foreign Key Relationship
     * 
     * <p>ManyToOne relationship to Customer entity based on cross-reference
     * file CVACT03Y.cpy (CARD-XREF-RECORD). Multiple accounts can belong to
     * a single customer (XREF-CUST-ID to XREF-ACCT-ID mapping).</p>
     * <p>Replaces VSAM cross-reference file relationship with PostgreSQL
     * foreign key constraint, ensuring referential integrity.</p>
     * <p>This relationship enables customer information retrieval for account
     * operations and supports account listing by customer.</p>
     */
    @ManyToOne
    @JoinColumn(name = "customer_id", referencedColumnName = "customer_id")
    private Customer customer;

    /**
     * Version - Optimistic Locking
     * 
     * <p>JPA version field for optimistic locking support, replicating VSAM
     * record locking behavior for concurrent access patterns.</p>
     * <p>Automatically incremented by JPA on each update, preventing lost
     * updates when multiple users attempt to modify the same account
     * simultaneously.</p>
     * <p>If a concurrent modification is detected, JPA throws
     * OptimisticLockException, allowing the application to handle the
     * conflict appropriately (e.g., refresh and retry).</p>
     */
    @Version
    @Column(name = "version")
    private Long version;
}
