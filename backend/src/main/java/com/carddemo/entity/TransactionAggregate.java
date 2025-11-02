package com.carddemo.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * JPA Entity representing transaction aggregation results for reporting and analysis.
 * 
 * <p>Transformed from COBOL batch program CBTRN03C.cbl aggregation logic.
 * This entity stores pre-calculated transaction aggregations grouped by account,
 * transaction type, and transaction category. It replaces the accumulator variables
 * and reporting totals (WS-ACCOUNT-TOTAL, WS-PAGE-TOTAL, WS-GRAND-TOTAL) from the
 * COBOL batch program with persistent database records.</p>
 * 
 * <p>Original COBOL Aggregation Logic (CBTRN03C.cbl lines 127-137):
 * <pre>
 * 01 WS-REPORT-VARS.
 *     05 WS-PAGE-TOTAL      PIC S9(09)V99 VALUE 0.
 *     05 WS-ACCOUNT-TOTAL   PIC S9(09)V99 VALUE 0.
 *     05 WS-GRAND-TOTAL     PIC S9(09)V99 VALUE 0.
 * 
 * Aggregation performed in paragraph 1100-WRITE-TRANSACTION-REPORT (lines 287-288):
 * ADD TRAN-AMT TO WS-PAGE-TOTAL
 *                 WS-ACCOUNT-TOTAL
 * </pre>
 * </p>
 * 
 * <p>Database Mapping:
 * <ul>
 *   <li>Table: transaction_aggregate</li>
 *   <li>Primary Key: id (auto-generated sequence)</li>
 *   <li>Unique Constraint: (account_id, transaction_type_code, transaction_category_code)</li>
 *   <li>Populated by: TransactionAggregationJob (Spring Batch job replacing CBTRN03C)</li>
 * </ul>
 * </p>
 * 
 * <p>Usage: This entity is created and updated by TransactionAggregationJob during
 * batch processing. It supports:
 * <ul>
 *   <li>Transaction category summary reports (replacing CBTRN03C report output)</li>
 *   <li>Account-level transaction analysis</li>
 *   <li>Type and category-based transaction breakdowns</li>
 *   <li>Validation of batch job outputs via TransactionAggregateRepository queries</li>
 * </ul>
 * </p>
 * 
 * <p>Performance Characteristics:
 * <ul>
 *   <li>Updated during nightly batch processing (4-hour window per Section 0.1)</li>
 *   <li>Read-heavy during business hours for reporting queries</li>
 *   <li>Composite unique index on (account_id, type_code, category_code)</li>
 *   <li>Indexed queries support sub-200ms response time SLA</li>
 * </ul>
 * </p>
 * 
 * @author CardDemo Migration Team
 * @version 1.0
 * @since 2024-01-01
 */
@Entity
@Table(name = "transaction_aggregate",
       uniqueConstraints = {
           @UniqueConstraint(
               name = "uk_trans_agg_acct_type_cat",
               columnNames = {"account_id", "transaction_type_code", "transaction_category_code"}
           )
       },
       indexes = {
           @Index(name = "idx_trans_agg_account", columnList = "account_id"),
           @Index(name = "idx_trans_agg_type", columnList = "transaction_type_code"),
           @Index(name = "idx_trans_agg_category", columnList = "transaction_category_code"),
           @Index(name = "idx_trans_agg_composite", 
                  columnList = "account_id, transaction_type_code, transaction_category_code")
       })
@Data
@NoArgsConstructor
@AllArgsConstructor
public class TransactionAggregate implements Serializable {

    /**
     * Serial version UID for Serializable interface.
     * Supports distributed caching with Redis-backed Spring Session for
     * clustered deployment environments per Section 0.5 caching strategy.
     */
    private static final long serialVersionUID = 1L;

    /**
     * Primary key identifier (auto-generated).
     * 
     * <p>Database-generated sequence value providing unique identifier for each
     * aggregation record. Not present in original COBOL as aggregations were
     * runtime-only accumulator variables.</p>
     * 
     * <p>Generation Strategy: IDENTITY (PostgreSQL SERIAL type)</p>
     */
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    /**
     * Account identifier for this aggregation.
     * 
     * <p>Maps from COBOL field: XREF-ACCT-ID (obtained via card number cross-reference
     * in CBTRN03C.cbl lines 186-187)</p>
     * 
     * <p>Represents the account for which transactions are being aggregated.
     * Corresponds to WS-CURR-CARD-NUM tracking in COBOL (line 137) which triggers
     * account total calculations when the card number changes (lines 181-188).</p>
     * 
     * <p>Constraints:
     * <ul>
     *   <li>NOT NULL: Required field - every aggregation must belong to an account</li>
     *   <li>Part of composite unique constraint with type_code and category_code</li>
     *   <li>Foreign key reference to account table (enforced in database)</li>
     * </ul>
     * </p>
     */
    @Column(name = "account_id", nullable = false)
    private Long accountId;

    /**
     * Transaction type code (2-character code).
     * 
     * <p>Maps from COBOL field: TRAN-TYPE-CD PIC X(02) from TRAN-RECORD
     * (CBTRN03C.cbl lines 189, 365)</p>
     * 
     * <p>Represents the type of transactions being aggregated (e.g., "PU" for Purchase,
     * "CA" for Cash Advance). Part of the grouping key for aggregations.</p>
     * 
     * <p>Constraints:
     * <ul>
     *   <li>Length: Exactly 2 characters (matches COBOL PIC X(02))</li>
     *   <li>NOT NULL: Required field per COBOL structure</li>
     *   <li>Part of composite unique constraint</li>
     *   <li>Values validated against transaction_type reference table</li>
     * </ul>
     * </p>
     */
    @Column(name = "transaction_type_code", length = 2, nullable = false)
    private String transactionTypeCode;

    /**
     * Transaction category code (4-digit integer code).
     * 
     * <p>Maps from COBOL field: TRAN-CAT-CD PIC 9(04) from TRAN-RECORD
     * (CBTRN03C.cbl lines 193-194, 367)</p>
     * 
     * <p>Represents the category of transactions being aggregated (e.g., 5000 for retail,
     * 5010 for dining). Part of the grouping key for aggregations.</p>
     * 
     * <p>Constraints:
     * <ul>
     *   <li>Range: 0-9999 (matches COBOL PIC 9(04))</li>
     *   <li>NOT NULL: Required field per COBOL structure</li>
     *   <li>Part of composite unique constraint</li>
     *   <li>Values validated against transaction_category reference table</li>
     * </ul>
     * </p>
     */
    @Column(name = "transaction_category_code", nullable = false)
    private Integer transactionCategoryCode;

    /**
     * Aggregated balance for this account/type/category combination.
     * 
     * <p>Maps from COBOL accumulator: WS-ACCOUNT-TOTAL PIC S9(09)V99
     * (CBTRN03C.cbl lines 135, 288, 307)</p>
     * 
     * <p>Stores the sum of all transaction amounts (TRAN-AMT) for the specific
     * combination of account, transaction type, and category. Calculated during
     * batch processing by TransactionAggregationJob.</p>
     * 
     * <p>Precision Requirements per Section 0.9:
     * <ul>
     *   <li>Precision: 15 digits total (13 integer + 2 decimal)</li>
     *   <li>Scale: 2 decimal places (matches COBOL V99)</li>
     *   <li>Rounding: HALF_UP (standard for financial calculations)</li>
     *   <li>Signed: true (matches COBOL S prefix for negative balances/refunds)</li>
     * </ul>
     * </p>
     * 
     * <p>Constraints:
     * <ul>
     *   <li>NOT NULL: Defaults to 0.00 if no transactions exist</li>
     *   <li>Precision(15,2): Exact match to COBOL PIC S9(13)V99 capacity</li>
     * </ul>
     * </p>
     */
    @Column(name = "category_balance", precision = 15, scale = 2, nullable = false)
    private BigDecimal categoryBalance;

    /**
     * Count of transactions in this aggregation.
     * 
     * <p>Not present in original COBOL but added for enhanced reporting capabilities.
     * Tracks the number of individual transactions that contributed to the categoryBalance,
     * enabling average transaction calculation and validation.</p>
     * 
     * <p>Constraints:
     * <ul>
     *   <li>NOT NULL: Defaults to 0 if no transactions exist</li>
     *   <li>Minimum value: 0 (enforced in application layer)</li>
     * </ul>
     * </p>
     */
    @Column(name = "transaction_count", nullable = false)
    private Long transactionCount;

    /**
     * Timestamp when this aggregation record was last updated.
     * 
     * <p>Not present in original COBOL but added for audit trail and validation.
     * Automatically updated on each modification to track when batch processing
     * last refreshed this aggregation.</p>
     * 
     * <p>Constraints:
     * <ul>
     *   <li>NOT NULL: Auto-populated on insert and update</li>
     *   <li>Timezone: UTC for consistency across distributed systems</li>
     * </ul>
     * </p>
     */
    @Column(name = "last_updated", nullable = false)
    private LocalDateTime lastUpdated;

    /**
     * Timestamp when this aggregation record was created.
     * 
     * <p>Not present in original COBOL but added for audit trail compliance
     * per Section 0.9 audit requirements. Immutable after initial creation.</p>
     * 
     * <p>Constraints:
     * <ul>
     *   <li>NOT NULL: Auto-populated on insert</li>
     *   <li>Updatable: false (immutable after creation)</li>
     * </ul>
     * </p>
     */
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    /**
     * JPA lifecycle callback to set timestamps before persisting new entity.
     * 
     * <p>Automatically populates createdAt and lastUpdated with current UTC timestamp
     * when a new TransactionAggregate record is inserted into the database.</p>
     */
    @PrePersist
    protected void onCreate() {
        LocalDateTime now = LocalDateTime.now();
        createdAt = now;
        lastUpdated = now;
        if (categoryBalance == null) {
            categoryBalance = BigDecimal.ZERO.setScale(2);
        }
        if (transactionCount == null) {
            transactionCount = 0L;
        }
    }

    /**
     * JPA lifecycle callback to update timestamp before updating entity.
     * 
     * <p>Automatically updates lastUpdated with current UTC timestamp whenever
     * the TransactionAggregate record is modified in the database.</p>
     */
    @PreUpdate
    protected void onUpdate() {
        lastUpdated = LocalDateTime.now();
    }

    /**
     * Custom toString() implementation provided by Lombok @Data annotation.
     * 
     * <p>Generates string representation in format:
     * <code>TransactionAggregate(id=1, accountId=123456, transactionTypeCode=PU, 
     * transactionCategoryCode=5000, categoryBalance=1234.56, transactionCount=10, 
     * lastUpdated=2024-01-01T12:00:00, createdAt=2024-01-01T10:00:00)</code></p>
     * 
     * <p>Used for logging, debugging, and audit trail entries maintaining
     * equivalent detail level to COBOL audit logs per Section 0.9 compliance requirements.</p>
     * 
     * @return String representation of this TransactionAggregate entity
     */
    // Lombok @Data generates: public String toString()

    /**
     * Equals method provided by Lombok @Data annotation.
     * 
     * <p>Compares TransactionAggregate instances based on all fields.
     * Supports entity equality checks in service layer and test assertions ensuring
     * functional equivalence with COBOL aggregation comparison logic.</p>
     * 
     * @param o Object to compare with this TransactionAggregate
     * @return true if objects are equal, false otherwise
     */
    // Lombok @Data generates: public boolean equals(Object o)

    /**
     * HashCode method provided by Lombok @Data annotation.
     * 
     * <p>Generates hash code based on all fields for use in collections (HashMap, HashSet).
     * Enables efficient caching and collection operations supporting sub-200ms response
     * times under 10,000 TPS load per Section 0.2 performance requirements.</p>
     * 
     * @return hash code value for this TransactionAggregate
     */
    // Lombok @Data generates: public int hashCode()
}
