package com.carddemo.entity;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
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
import java.time.LocalDateTime;
import java.util.Objects;

/**
 * JPA Entity representing transaction category balance aggregation table.
 * 
 * <p>Transformed from COBOL copybook CVTRA01Y.cpy (TRAN-CAT-BAL-RECORD with RECLN = 50 bytes).
 * This entity stores pre-calculated transaction category balances per account, enabling efficient
 * queries for account transaction totals by type and category. Supports COTRN01C transaction
 * category summary program migration and CBTRN03C transaction aggregation batch processing.</p>
 * 
 * <p>Original COBOL Structure (50-byte record):
 * <pre>
 * 01  TRAN-CAT-BAL-RECORD.
 *     05  TRAN-CAT-KEY.
 *        10 TRANCAT-ACCT-ID                       PIC 9(11).
 *        10 TRANCAT-TYPE-CD                       PIC X(02).
 *        10 TRANCAT-CD                            PIC 9(04).
 *     05  TRAN-CAT-BAL                            PIC S9(09)V99.
 *     05  FILLER                                  PIC X(22).
 * </pre>
 * </p>
 * 
 * <p>Database Mapping:
 * <ul>
 *   <li>Table: transaction_aggregate</li>
 *   <li>Composite Primary Key: (account_id, transaction_type_code, transaction_category_code)</li>
 *   <li>Foreign Keys: account_id → account(account_id), type_code → transaction_type(type_code)</li>
 *   <li>Populated by: TransactionAggregationJob (Spring Batch job replacing CBTRN03C.cbl)</li>
 * </ul>
 * </p>
 * 
 * <p><strong>Critical Numeric Precision Requirements (Section 0.2 and 0.9):</strong></p>
 * <ul>
 *   <li>TRAN-CAT-BAL PIC S9(09)V99 → BigDecimal with precision=11, scale=2</li>
 *   <li>COBOL COMP-3 packed decimal precision preserved using RoundingMode.HALF_UP</li>
 *   <li>All monetary calculations must explicitly call setScale(2, RoundingMode.HALF_UP)</li>
 *   <li>Aggregation calculations must maintain identical precision to COBOL batch jobs</li>
 * </ul>
 * 
 * <p><strong>Composite Key Strategy:</strong></p>
 * <p>Uses JPA @EmbeddedId annotation with AggregateId embeddable class containing all three
 * key components (accountId, transactionTypeCode, transactionCategoryCode). This approach:</p>
 * <ul>
 *   <li>Maintains COBOL TRAN-CAT-KEY compound key semantics from copybook</li>
 *   <li>Supports efficient PostgreSQL composite index lookups</li>
 *   <li>Enables type-safe composite key operations in repository queries</li>
 *   <li>Provides proper equals/hashCode for entity identity management</li>
 *   <li>Ensures one balance per unique account/type/category combination</li>
 * </ul>
 * 
 * <p><strong>Batch Processing Integration (Section 0.5):</strong></p>
 * <ul>
 *   <li>CBTRN03C batch job (TransactionAggregationJob) updates this table daily</li>
 *   <li>Groups transactions by account_id, type_code, category_code</li>
 *   <li>Calculates SUM(transaction_amount) for each group with COMP-3 precision</li>
 *   <li>UPSERT operation: INSERT ... ON CONFLICT UPDATE for PostgreSQL</li>
 *   <li>Chunk-oriented processing: 1000 records per chunk</li>
 *   <li>Error handling: Skip limit 100, retry 3 times with exponential backoff</li>
 * </ul>
 * 
 * <p><strong>Performance Characteristics:</strong></p>
 * <ul>
 *   <li>Static reference data during business hours - ideal for Redis caching</li>
 *   <li>Updated during nightly batch processing (4-hour window per Section 0.1)</li>
 *   <li>Read-heavy access pattern supporting COTRN01C category summary queries</li>
 *   <li>Composite indexes on all key combinations for sub-200ms response times</li>
 *   <li>LAZY loading prevents unnecessary parent entity fetches</li>
 * </ul>
 * 
 * <p><strong>Migration from VSAM:</strong></p>
 * <p>VSAM TRAN-CAT-BAL file stored pre-calculated category balances with key-sequenced access.
 * COBOL batch job CBTRN03C updated balances using READ/REWRITE operations. PostgreSQL uses
 * UPSERT pattern to achieve equivalent functionality with better concurrency control.</p>
 * 
 * @author CardDemo Migration Team
 * @version 1.0
 * @since 2024-01-01
 * @see Account
 * @see TransactionType
 * @see TransactionCategory
 * @see <a href="Section 0.6">File-by-File Transformation Plan</a>
 */
@Entity
@Table(name = "transaction_aggregate",
       indexes = {
           @Index(name = "idx_trans_agg_account", columnList = "account_id"),
           @Index(name = "idx_trans_agg_type", columnList = "transaction_type_code"),
           @Index(name = "idx_trans_agg_category", columnList = "transaction_category_code")
       })
@Data
@NoArgsConstructor
@AllArgsConstructor
public class TransactionAggregate implements Serializable {

    /**
     * Serial version UID for Serializable interface.
     * Supports distributed caching with Redis-backed Spring Session for
     * clustered deployment environments per Section 0.5 caching strategy.
     * Required by JPA specification for entity serialization.
     */
    private static final long serialVersionUID = 1L;

    /**
     * Composite primary key containing account ID, transaction type code, and category code.
     * 
     * <p>Embedded composite key class (AggregateId) maps from COBOL compound key structure:
     * <pre>
     * 05  TRAN-CAT-KEY.
     *    10 TRANCAT-ACCT-ID                       PIC 9(11).
     *    10 TRANCAT-TYPE-CD                       PIC X(02).
     *    10 TRANCAT-CD                            PIC 9(04).
     * </pre>
     * </p>
     * 
     * <p>Components:
     * <ul>
     *   <li>accountId: 11-digit numeric account identifier</li>
     *   <li>transactionTypeCode: 2-character transaction type (e.g., "PU", "CA", "PM")</li>
     *   <li>transactionCategoryCode: 4-digit numeric category within type (0001-9999)</li>
     * </ul>
     * </p>
     * 
     * <p>JPA @EmbeddedId provides:
     * <ul>
     *   <li>Type-safe composite key access in repository methods</li>
     *   <li>Proper entity identity management with equals/hashCode</li>
     *   <li>Support for composite key queries and specifications</li>
     *   <li>Serializable key for distributed caching with Redis</li>
     *   <li>Natural mapping to COBOL compound key semantics</li>
     * </ul>
     * </p>
     * 
     * <p>Usage in queries:
     * <pre>
     * AggregateId id = new AggregateId(12345678901L, "PU", 1001);
     * TransactionAggregate aggregate = repository.findById(id).orElseThrow();
     * </pre>
     * </p>
     */
    @EmbeddedId
    private AggregateId id;

    /**
     * Aggregated category balance for this account/type/category combination with COBOL COMP-3 precision.
     * 
     * <p>Maps from COBOL field: TRAN-CAT-BAL PIC S9(09)V99</p>
     * 
     * <p><strong>CRITICAL PRECISION REQUIREMENTS (Section 0.2 and 0.9):</strong></p>
     * <p>This field represents the sum of all transaction amounts for the specific combination
     * of account, transaction type, and category. Calculated during batch processing by
     * TransactionAggregationJob (Spring Batch job replacing CBTRN03C.cbl).</p>
     * 
     * <ul>
     *   <li>Precision: 11 digits total (9 integer + 2 decimal) matching COBOL S9(09)V99</li>
     *   <li>Scale: 2 decimal places (matches COBOL V99)</li>
     *   <li>Rounding: RoundingMode.HALF_UP (round to nearest neighbor, ties round up)</li>
     *   <li>Signed: true (matches COBOL S prefix for negative balances/refunds)</li>
     *   <li>All arithmetic operations MUST call: .setScale(2, RoundingMode.HALF_UP)</li>
     * </ul>
     * 
     * <p>Example aggregation calculation:
     * <pre>
     * BigDecimal total = BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);
     * for (Transaction txn : transactions) {
     *     total = total.add(txn.getAmount()).setScale(2, RoundingMode.HALF_UP);
     * }
     * aggregate.setCategoryBalance(total);
     * </pre>
     * </p>
     * 
     * <p>Constraints:
     * <ul>
     *   <li>NOT NULL: Defaults to 0.00 if no transactions exist</li>
     *   <li>Precision(11,2): Exact match to COBOL PIC S9(09)V99 capacity</li>
     *   <li>Range: -999999999.99 to +999999999.99</li>
     * </ul>
     * </p>
     */
    @Column(name = "category_balance", precision = 11, scale = 2, nullable = false)
    private BigDecimal categoryBalance;

    /**
     * Timestamp when this aggregation record was last updated.
     * 
     * <p>Not present in original COBOL but added for audit trail and validation per
     * Section 0.9 audit requirements. Automatically updated on each modification to
     * track when batch processing last refreshed this aggregation.</p>
     * 
     * <p>Critical for:
     * <ul>
     *   <li>Identifying stale aggregation data requiring refresh</li>
     *   <li>Audit trail validation matching mainframe audit capabilities</li>
     *   <li>Troubleshooting batch job execution and aggregation accuracy</li>
     *   <li>Regulatory compliance logging per Section 0.9</li>
     * </ul>
     * </p>
     * 
     * <p>Constraints:
     * <ul>
     *   <li>Nullable: true (null indicates never updated, only on initial creation)</li>
     *   <li>Timezone: UTC for consistency across distributed systems</li>
     *   <li>Automatically set by JPA @PreUpdate lifecycle callback</li>
     * </ul>
     * </p>
     */
    @Column(name = "last_updated")
    private LocalDateTime lastUpdated;

    /**
     * Timestamp when this aggregation record was first created.
     * 
     * <p>Added for complete audit trail tracking per Section 0.9 audit requirements.
     * Set once on initial entity creation and never modified afterwards. Enables
     * tracking of when aggregation records are first established versus updated.</p>
     * 
     * <p>Constraints:
     * <ul>
     *   <li>Nullable: true (set on first persistence)</li>
     *   <li>Timezone: UTC for consistency across distributed systems</li>
     *   <li>Immutable after initial creation</li>
     * </ul>
     * </p>
     */
    @Column(name = "created_at")
    private LocalDateTime createdAt;

    /**
     * Count of transactions that have been aggregated into this balance.
     * 
     * <p>Not present in original COBOL but added to support batch processing
     * validation and reporting. Tracks how many individual transactions contributed
     * to the current categoryBalance value. Useful for:
     * <ul>
     *   <li>Validating aggregation accuracy (record count matches source)</li>
     *   <li>Reporting average transaction size per category</li>
     *   <li>Identifying categories with unusual transaction volumes</li>
     *   <li>Troubleshooting aggregation discrepancies</li>
     * </ul>
     * </p>
     * 
     * <p>Constraints:
     * <ul>
     *   <li>Nullable: true (defaults to 0 if not set)</li>
     *   <li>Must be non-negative when set</li>
     *   <li>Updated by TransactionAggregationJob during batch processing</li>
     * </ul>
     * </p>
     */
    @Column(name = "transaction_count")
    private Integer transactionCount;

    /**
     * Many-to-one relationship to Account entity via accountId.
     * 
     * <p>Establishes foreign key constraint from transaction_aggregate.account_id to
     * account.account_id, ensuring referential integrity per Section 0.9 cross-reference
     * data relationship requirements. Enables navigation from aggregation to parent
     * account for hierarchical reporting and account-level queries.</p>
     * 
     * <p>Relationship Characteristics:
     * <ul>
     *   <li>Fetch Type: LAZY - defers loading until explicitly accessed</li>
     *   <li>Optional: false - every aggregation must belong to a valid account</li>
     *   <li>Join Column: account_id from composite key id</li>
     *   <li>Cardinality: Many aggregations can belong to one account</li>
     *   <li>Cascade: NONE - aggregations don't cascade account operations</li>
     *   <li>insertable/updatable: false - key components managed by @EmbeddedId</li>
     * </ul>
     * </p>
     * 
     * <p>Performance Optimization:
     * LAZY fetch type prevents unnecessary Account entity loads when only aggregation
     * summary data is needed, reducing query overhead and memory usage. Critical for
     * maintaining sub-200ms response times under high transaction volumes per Section
     * 0.2 performance requirements.</p>
     * 
     * <p>Usage Example:
     * <pre>
     * TransactionAggregate aggregate = repository.findById(id).orElseThrow();
     * // Account not loaded yet (LAZY)
     * String accountStatus = aggregate.getAccount().getActiveStatus();
     * // Now Account is loaded on demand
     * </pre>
     * </p>
     * 
     * <p>JSON Serialization:
     * Annotated with @JsonIgnore to prevent circular reference issues when
     * TransactionAggregate entities are serialized in REST API responses (e.g.,
     * TransactionCategoryResponse for COTRN01C category summary). Breaks bidirectional
     * relationship serialization loop, avoiding infinite recursion during JSON conversion.</p>
     */
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "account_id", referencedColumnName = "account_id",
                insertable = false, updatable = false)
    @JsonIgnore
    private Account account;

    /**
     * Many-to-one relationship to TransactionType entity via transactionTypeCode.
     * 
     * <p>Establishes foreign key constraint from transaction_aggregate.transaction_type_code
     * to transaction_type.type_code for transaction type classification. Enables navigation
     * from aggregation to transaction type reference data for type descriptions and
     * hierarchical category reporting.</p>
     * 
     * <p>Relationship Characteristics:
     * <ul>
     *   <li>Fetch Type: LAZY - defers loading until explicitly accessed</li>
     *   <li>Optional: false - every aggregation must have a valid transaction type</li>
     *   <li>Join Column: transaction_type_code from composite key id</li>
     *   <li>Cardinality: Many aggregations can share the same transaction type</li>
     *   <li>Cascade: NONE - aggregations don't cascade type operations</li>
     *   <li>insertable/updatable: false - key components managed by @EmbeddedId</li>
     * </ul>
     * </p>
     * 
     * <p>Performance Optimization:
     * LAZY fetch type prevents unnecessary TransactionType entity loads, critical for
     * batch aggregation processing where only type codes are needed, not full type
     * descriptions. Supports efficient processing of thousands of aggregations within
     * the 4-hour batch window per Section 0.2.</p>
     * 
     * <p>JSON Serialization:
     * Annotated with @JsonIgnore to prevent circular reference and unnecessary data
     * exposure in REST API responses. Type code is already available in composite key.</p>
     */
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "transaction_type_code", referencedColumnName = "type_code",
                insertable = false, updatable = false)
    @JsonIgnore
    private TransactionType transactionType;

    /**
     * Many-to-one relationship to TransactionCategory entity via composite foreign key.
     * 
     * <p>Establishes foreign key constraint from transaction_aggregate to transaction_category
     * using both transaction_type_code and transaction_category_code components. This relationship
     * is more complex due to TransactionCategory's own composite primary key structure.</p>
     * 
     * <p>Relationship Characteristics:
     * <ul>
     *   <li>Fetch Type: LAZY - defers loading until explicitly accessed</li>
     *   <li>Optional: false - every aggregation must have a valid category</li>
     *   <li>Join Columns: Both type_code and category_code (composite foreign key)</li>
     *   <li>Cardinality: Many aggregations can share the same category</li>
     *   <li>Cascade: NONE - aggregations don't cascade category operations</li>
     *   <li>insertable/updatable: false - key components managed by @EmbeddedId</li>
     * </ul>
     * </p>
     * 
     * <p>Note: This relationship requires matching both the type code AND category code
     * components from the composite key to the TransactionCategory composite key. The
     * actual foreign key constraint in PostgreSQL enforces referential integrity on
     * both columns together.</p>
     * 
     * <p>Performance Optimization:
     * LAZY fetch minimizes unnecessary category description loads during batch aggregation
     * processing, supporting efficient transaction processing within performance SLAs.</p>
     * 
     * <p>JSON Serialization:
     * Annotated with @JsonIgnore to prevent circular reference and reduce response payload.
     * Category code is already available in composite key for client-side lookup if needed.</p>
     */
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "transaction_type_code", referencedColumnName = "type_code",
                insertable = false, updatable = false)
    @JoinColumn(name = "transaction_category_code", referencedColumnName = "category_code",
                insertable = false, updatable = false)
    @JsonIgnore
    private TransactionCategory transactionCategory;

    /**
     * Custom setter for categoryBalance ensuring COBOL COMP-3 precision preservation.
     * 
     * <p>All balance updates MUST use this setter to guarantee proper scale and
     * rounding mode. This prevents precision loss and ensures identical results
     * to mainframe COBOL financial calculations per Section 0.9 requirements.</p>
     * 
     * <p>This setter is critical for TransactionAggregationJob batch processing
     * which calculates aggregated balances from individual transactions. Every
     * aggregation calculation must maintain COMP-3 packed decimal precision.</p>
     * 
     * @param categoryBalance The balance value to set (will be scaled to 2 decimals with HALF_UP rounding)
     */
    public void setCategoryBalance(BigDecimal categoryBalance) {
        this.categoryBalance = categoryBalance != null
            ? categoryBalance.setScale(2, RoundingMode.HALF_UP)
            : BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);
    }

    /**
     * Adds an amount to the current category balance with COBOL COMP-3 precision.
     * 
     * <p>Convenience method for batch aggregation processing. Performs addition
     * with proper scale and rounding mode to maintain COBOL packed decimal precision.
     * Used by TransactionAggregationJob when accumulating transaction amounts.</p>
     * 
     * <p>Example usage in batch processor:
     * <pre>
     * aggregate.addToCategoryBalance(transaction.getAmount());
     * </pre>
     * </p>
     * 
     * @param amount The amount to add to category balance (must not be null)
     */
    public void addToCategoryBalance(BigDecimal amount) {
        if (amount == null) {
            throw new IllegalArgumentException("Amount to add cannot be null");
        }
        if (this.categoryBalance == null) {
            this.categoryBalance = BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);
        }
        this.categoryBalance = this.categoryBalance.add(amount).setScale(2, RoundingMode.HALF_UP);
    }

    // ========== Convenience Methods for Composite Key Access ==========

    /**
     * Convenience getter for accountId from composite key.
     * 
     * <p>Delegates to id.getAccountId() for easier API access without
     * needing to navigate through the embedded composite key. Commonly
     * used in batch processing and service layer code.</p>
     * 
     * @return the account ID component of the composite key, or null if id is not set
     */
    public Long getAccountId() {
        return id != null ? id.getAccountId() : null;
    }

    /**
     * Convenience getter for transactionTypeCode from composite key.
     * 
     * <p>Delegates to id.getTransactionTypeCode() for easier API access
     * without needing to navigate through the embedded composite key.
     * Commonly used in batch processing and service layer code.</p>
     * 
     * @return the transaction type code component of the composite key, or null if id is not set
     */
    public String getTransactionTypeCode() {
        return id != null ? id.getTransactionTypeCode() : null;
    }

    /**
     * Convenience getter for transactionCategoryCode from composite key.
     * 
     * <p>Delegates to id.getTransactionCategoryCode() for easier API access
     * without needing to navigate through the embedded composite key.
     * Commonly used in batch processing and service layer code.</p>
     * 
     * @return the transaction category code component of the composite key, or null if id is not set
     */
    public Integer getTransactionCategoryCode() {
        return id != null ? id.getTransactionCategoryCode() : null;
    }

    /**
     * Embeddable composite primary key class for TransactionAggregate.
     * 
     * <p>Implements JPA composite key pattern using @Embeddable annotation.
     * Maps directly to COBOL TRAN-CAT-KEY structure containing account ID,
     * transaction type code, and transaction category code components.</p>
     * 
     * <p>COBOL Source:
     * <pre>
     * 05  TRAN-CAT-KEY.
     *    10 TRANCAT-ACCT-ID                       PIC 9(11).
     *    10 TRANCAT-TYPE-CD                       PIC X(02).
     *    10 TRANCAT-CD                            PIC 9(04).
     * </pre>
     * </p>
     * 
     * <p>JPA Requirements:
     * <ul>
     *   <li>Must implement Serializable for entity identity management</li>
     *   <li>Must override equals() and hashCode() for proper entity comparison</li>
     *   <li>Must have public no-arg constructor for JPA instantiation</li>
     *   <li>All fields must have public getters and setters (provided by Lombok)</li>
     * </ul>
     * </p>
     * 
     * <p>Database Mapping:
     * <ul>
     *   <li>account_id: BIGINT NOT NULL (11-digit numeric, PIC 9(11))</li>
     *   <li>transaction_type_code: VARCHAR(2) NOT NULL (PIC X(02))</li>
     *   <li>transaction_category_code: INTEGER NOT NULL (PIC 9(04))</li>
     *   <li>Composite PRIMARY KEY (account_id, transaction_type_code, transaction_category_code)</li>
     *   <li>Composite INDEXES for efficient lookups by account, type, or category</li>
     * </ul>
     * </p>
     * 
     * <p>Usage in Repository Queries:
     * <pre>
     * // Single aggregation lookup
     * AggregateId id = new AggregateId(12345678901L, "PU", 1001);
     * Optional&lt;TransactionAggregate&gt; aggregate = repository.findById(id);
     * 
     * // All aggregations for an account
     * List&lt;TransactionAggregate&gt; accountAggregates = 
     *     repository.findByIdAccountId(12345678901L);
     * 
     * // All aggregations for a transaction type across accounts
     * List&lt;TransactionAggregate&gt; purchaseAggregates = 
     *     repository.findByIdTransactionTypeCode("PU");
     * </pre>
     * </p>
     */
    @Embeddable
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class AggregateId implements Serializable {

        /**
         * Serial version UID for Serializable interface.
         * Required by JPA specification for composite key serialization
         * and distributed caching with Redis.
         */
        private static final long serialVersionUID = 1L;

        /**
         * Account identifier component of composite key (11-digit numeric).
         * 
         * <p>Maps from COBOL field: TRANCAT-ACCT-ID PIC 9(11)</p>
         * 
         * <p>First component of composite primary key. Represents the account
         * for which transaction category balances are being aggregated. References
         * account table to establish foreign key relationship.</p>
         * 
         * <p>Constraints:
         * <ul>
         *   <li>Type: Long to match COBOL PIC 9(11) numeric field (11 digits)</li>
         *   <li>Range: 1 to 99999999999 (11 digits maximum)</li>
         *   <li>NOT NULL: Required component of primary key</li>
         *   <li>Foreign Key: Must exist in account.account_id</li>
         *   <li>Immutable: Should not be changed after entity creation</li>
         * </ul>
         * </p>
         */
        @Column(name = "account_id", nullable = false)
        private Long accountId;

        /**
         * Transaction type code component of composite key (2 characters).
         * 
         * <p>Maps from COBOL field: TRANCAT-TYPE-CD PIC X(02)</p>
         * 
         * <p>Second component of composite primary key. Represents the transaction
         * type for this aggregation. References transaction_type table to establish
         * foreign key relationship. Common values include "PU" (Purchase), "CA"
         * (Cash Advance), "PM" (Payment), "RF" (Refund), "FE" (Fee), "IN" (Interest).</p>
         * 
         * <p>Constraints:
         * <ul>
         *   <li>Length: Exactly 2 characters (matches COBOL PIC X(02))</li>
         *   <li>NOT NULL: Required component of primary key</li>
         *   <li>Foreign Key: Must exist in transaction_type.type_code</li>
         *   <li>Immutable: Should not be changed after entity creation</li>
         *   <li>Case-sensitive: Maintain exact case from reference data</li>
         * </ul>
         * </p>
         */
        @Column(name = "transaction_type_code", length = 2, nullable = false)
        private String transactionTypeCode;

        /**
         * Transaction category code component of composite key (4-digit integer).
         * 
         * <p>Maps from COBOL field: TRANCAT-CD PIC 9(04)</p>
         * 
         * <p>Third component of composite primary key. Provides unique category
         * identification within each transaction type for detailed transaction
         * classification. Numeric range 1-9999 allows up to 9,999 distinct
         * categories per transaction type.</p>
         * 
         * <p>Example category codes by transaction type:
         * <ul>
         *   <li>Type "PU": 1001=Groceries, 1002=Gas, 1003=Dining, 1004=Shopping</li>
         *   <li>Type "CA": 2001=ATM, 2002=Branch, 2003=Check</li>
         *   <li>Type "FE": 3001=Annual, 3002=Late, 3003=OverLimit</li>
         * </ul>
         * </p>
         * 
         * <p>Constraints:
         * <ul>
         *   <li>Type: Integer to match COBOL PIC 9(04) numeric field</li>
         *   <li>Range: 1 to 9999 (4 digits maximum)</li>
         *   <li>NOT NULL: Required component of primary key</li>
         *   <li>Foreign Key: Must exist in transaction_category.category_code (with type_code)</li>
         *   <li>Immutable: Should not be changed after entity creation</li>
         * </ul>
         * </p>
         * 
         * <p>Note: Using Integer instead of String for numeric COBOL PIC 9 field
         * provides type safety and natural ordering for category code ranges.</p>
         */
        @Column(name = "transaction_category_code", nullable = false)
        private Integer transactionCategoryCode;

        /**
         * Equals method for composite key comparison.
         * 
         * <p>Implements proper equality semantics for JPA composite key as required by
         * JPA specification. Two AggregateId instances are equal if and only if all
         * three key components (accountId, transactionTypeCode, transactionCategoryCode)
         * are equal.</p>
         * 
         * <p>Critical for:
         * <ul>
         *   <li>JPA entity identity management and first-level caching</li>
         *   <li>Repository findById() lookups and query result uniqueness</li>
         *   <li>Collection operations (Set, Map keys) in service layer</li>
         *   <li>Test assertions for functional equivalence validation</li>
         *   <li>Hibernate second-level caching with proper key distribution</li>
         * </ul>
         * </p>
         * 
         * <p>Uses Objects.equals() for null-safe comparison supporting composite key
         * fields that may be null during entity construction. Maintains contract with
         * hashCode() per Java equals/hashCode specification.</p>
         * 
         * @param o the object to compare with this AggregateId
         * @return true if objects are equal (all three key components match), false otherwise
         */
        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;
            AggregateId that = (AggregateId) o;
            return Objects.equals(accountId, that.accountId) &&
                   Objects.equals(transactionTypeCode, that.transactionTypeCode) &&
                   Objects.equals(transactionCategoryCode, that.transactionCategoryCode);
        }

        /**
         * HashCode method for composite key hashing.
         * 
         * <p>Generates hash code based on all three key components (accountId,
         * transactionTypeCode, transactionCategoryCode) for use in hash-based
         * collections (HashMap, HashSet, JPA entity cache). Ensures proper
         * distribution of hash values across composite key space.</p>
         * 
         * <p>Critical for:
         * <ul>
         *   <li>Efficient JPA first-level cache (persistence context) lookups</li>
         *   <li>Hibernate second-level caching with Ehcache or Redis</li>
         *   <li>Fast HashMap/HashSet operations in service layer aggregations</li>
         *   <li>Redis distributed caching with proper key distribution</li>
         *   <li>Collection performance under 10,000 TPS load per Section 0.2</li>
         * </ul>
         * </p>
         * 
         * <p>Uses Objects.hash() to generate consistent hash code from multiple fields.
         * Maintains equals/hashCode contract: equal objects have equal hash codes.</p>
         * 
         * @return hash code value for this AggregateId composite key
         */
        @Override
        public int hashCode() {
            return Objects.hash(accountId, transactionTypeCode, transactionCategoryCode);
        }
    }

    /**
     * Custom toString() implementation provided by Lombok @Data annotation.
     * 
     * <p>Generates string representation in format:
     * <code>TransactionAggregate(id=AggregateId(accountId=12345678901, 
     * transactionTypeCode=PU, transactionCategoryCode=1001), 
     * categoryBalance=1234.56, lastUpdated=2024-01-01T12:00:00)</code></p>
     * 
     * <p>Excludes account, transactionType, and transactionCategory fields from string
     * representation to prevent lazy loading and potential infinite recursion if parent
     * entities also have toString() that references aggregations.</p>
     * 
     * <p>Used for:
     * <ul>
     *   <li>Logging transaction aggregation batch processing operations</li>
     *   <li>Debugging category balance calculation issues</li>
     *   <li>Audit trail entries per Section 0.9 compliance requirements</li>
     *   <li>Test assertion error messages for aggregation validation</li>
     * </ul>
     * </p>
     * 
     * @return String representation of this TransactionAggregate entity
     */
    // Lombok @Data generates: public String toString()

    /**
     * Getter for composite primary key.
     * Provided by Lombok @Data annotation.
     * 
     * @return the AggregateId composite key containing accountId, transactionTypeCode, and transactionCategoryCode
     */
    // Lombok @Data generates: public AggregateId getId()

    /**
     * Setter for composite primary key.
     * Provided by Lombok @Data annotation.
     * 
     * <p>Note: Primary key should typically be set only once during entity creation
     * and not modified afterwards to maintain referential integrity and JPA cache consistency.</p>
     * 
     * @param id the AggregateId composite key to set
     */
    // Lombok @Data generates: public void setId(AggregateId id)

    /**
     * Getter for category balance.
     * Provided by Lombok @Data annotation.
     * 
     * <p>Returns the aggregated balance with 2 decimal places precision matching
     * COBOL COMP-3 packed decimal format.</p>
     * 
     * @return the category balance amount with scale 2
     */
    // Lombok @Data generates: public BigDecimal getCategoryBalance()

    /**
     * Getter for last updated timestamp.
     * Provided by Lombok @Data annotation.
     * 
     * @return the timestamp when this aggregation was last updated, or null if never updated
     */
    // Lombok @Data generates: public LocalDateTime getLastUpdated()

    /**
     * Setter for last updated timestamp.
     * Provided by Lombok @Data annotation.
     * 
     * <p>Typically managed automatically by batch processing jobs. Manual updates
     * should be rare and carefully considered for audit trail accuracy.</p>
     * 
     * @param lastUpdated the timestamp to set as last update time
     */
    // Lombok @Data generates: public void setLastUpdated(LocalDateTime lastUpdated)

    /**
     * Getter for Account relationship.
     * Provided by Lombok @Data annotation.
     * 
     * <p>Note: Accessing this method will trigger LAZY loading of Account entity
     * if not already loaded. May result in additional database query if entity not in
     * persistence context or cache. Use with caution in batch processing loops.</p>
     * 
     * @return the associated Account entity
     */
    // Lombok @Data generates: public Account getAccount()

    /**
     * Setter for Account relationship.
     * Provided by Lombok @Data annotation.
     * 
     * <p>Note: Setting relationship directly is generally not needed as accountId in
     * composite key establishes the foreign key reference. This setter primarily
     * exists for JPA relationship management and bidirectional association maintenance.</p>
     * 
     * @param account the Account entity to associate
     */
    // Lombok @Data generates: public void setAccount(Account account)

    /**
     * Getter for TransactionType relationship.
     * Provided by Lombok @Data annotation.
     * 
     * <p>Note: Accessing this method will trigger LAZY loading of TransactionType entity
     * if not already loaded. Consider caching transaction types in memory for batch
     * processing performance optimization.</p>
     * 
     * @return the associated TransactionType entity
     */
    // Lombok @Data generates: public TransactionType getTransactionType()

    /**
     * Setter for TransactionType relationship.
     * Provided by Lombok @Data annotation.
     * 
     * @param transactionType the TransactionType entity to associate
     */
    // Lombok @Data generates: public void setTransactionType(TransactionType transactionType)

    /**
     * Getter for TransactionCategory relationship.
     * Provided by Lombok @Data annotation.
     * 
     * <p>Note: Accessing this method will trigger LAZY loading of TransactionCategory entity
     * if not already loaded. Consider caching transaction categories for reporting queries.</p>
     * 
     * @return the associated TransactionCategory entity
     */
    // Lombok @Data generates: public TransactionCategory getTransactionCategory()

    /**
     * Setter for TransactionCategory relationship.
     * Provided by Lombok @Data annotation.
     * 
     * @param transactionCategory the TransactionCategory entity to associate
     */
    // Lombok @Data generates: public void setTransactionCategory(TransactionCategory transactionCategory)

    /**
     * Equals method based on composite key only (JPA entity equality semantics).
     * 
     * <p>Compares TransactionAggregate instances based on composite key (id field only).
     * Two aggregates are equal if they have the same composite key (accountId, 
     * transactionTypeCode, transactionCategoryCode). This follows JPA best practices
     * where entity equality is determined by the primary key, not by all fields.</p>
     * 
     * <p>Overrides Lombok @Data default equals() which would compare all fields. For
     * JPA entities, only the ID should determine equality to ensure proper behavior
     * in collections and caching.</p>
     * 
     * <p>Supports entity equality checks in service layer and test assertions ensuring
     * functional equivalence with COBOL aggregation comparison logic. Critical for
     * validating batch job outputs match expected results.</p>
     * 
     * @param o Object to compare with this TransactionAggregate
     * @return true if objects are equal (same composite key), false otherwise
     */
    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        TransactionAggregate that = (TransactionAggregate) o;
        return Objects.equals(id, that.id);
    }

    /**
     * HashCode method based on composite key only (JPA entity hash code semantics).
     * 
     * <p>Generates hash code based on composite key (id field) for use in collections
     * (HashMap, HashSet). Overrides Lombok @Data default hashCode() to ensure it's
     * consistent with the custom equals() method that uses only the ID field.</p>
     * 
     * <p>Enables efficient caching and collection operations supporting sub-200ms 
     * response times under 10,000 TPS load per Section 0.2 performance requirements.
     * Maintains equals/hashCode contract: equal entities have equal hash codes.</p>
     * 
     * @return hash code value for this TransactionAggregate based on composite key
     */
    @Override
    public int hashCode() {
        return Objects.hash(id);
    }
}
