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
import java.util.Objects;

/**
 * JPA Entity representing transaction category reference data.
 * 
 * <p>Transformed from COBOL copybook CVTRA04Y.cpy (TRAN-CAT-RECORD structure).
 * This entity provides detailed categorization of transactions by combining transaction
 * type with specific category codes. Uses composite primary key consisting of transaction
 * type code (2 characters) and category code (4 digits) for granular transaction classification
 * in the credit card management system.</p>
 * 
 * <p>Original COBOL Structure (60-byte record):
 * <pre>
 * 01  TRAN-CAT-RECORD.
 *     05  TRAN-CAT-KEY.
 *        10  TRAN-TYPE-CD         PIC X(02).
 *        10  TRAN-CAT-CD          PIC 9(04).
 *     05  TRAN-CAT-TYPE-DESC      PIC X(50).
 *     05  FILLER                  PIC X(04).
 * </pre>
 * </p>
 * 
 * <p>Database Mapping:
 * <ul>
 *   <li>Table: transaction_category</li>
 *   <li>Composite Primary Key: (type_code, category_code)</li>
 *   <li>Foreign Key: type_code references transaction_type(type_code)</li>
 *   <li>Reference data loaded via Flyway migration V9__load_reference_data.sql</li>
 * </ul>
 * </p>
 * 
 * <p>Usage: This entity is referenced by Transaction entities to provide detailed
 * categorization beyond basic transaction type. For example, a "Purchase" transaction type
 * might have categories like "Groceries" (0001), "Gas" (0002), "Dining" (0003), etc.
 * Enables sophisticated transaction reporting and analysis as required by COTRN01C.cbl
 * (TransactionCategoryService.java) per Section 0.4 transformation mapping.</p>
 * 
 * <p>Composite Key Strategy:
 * Uses JPA @EmbeddedId annotation with CategoryId embeddable class containing both
 * typeCode and categoryCode components. This approach:
 * <ul>
 *   <li>Maintains COBOL TRAN-CAT-KEY compound key semantics</li>
 *   <li>Supports efficient PostgreSQL composite index lookups</li>
 *   <li>Enables type-safe composite key operations in repository queries</li>
 *   <li>Provides proper equals/hashCode for entity identity management</li>
 * </ul>
 * </p>
 * 
 * <p>Relationship to TransactionType:
 * Maintains optional @ManyToOne relationship to TransactionType entity via typeCode
 * field component of composite key. Uses LAZY fetch type to optimize performance by
 * deferring parent type loading until explicitly accessed, supporting sub-200ms response
 * times under 10,000 TPS load per Section 0.2 performance requirements.</p>
 * 
 * <p>Performance Characteristics:
 * <ul>
 *   <li>Static reference data - ideal for Redis caching</li>
 *   <li>Medium-sized dataset (typically 50-200 category combinations)</li>
 *   <li>Read-heavy access pattern with no runtime modifications</li>
 *   <li>Composite index supports efficient category lookup by type</li>
 *   <li>LAZY loading prevents unnecessary type entity fetches</li>
 * </ul>
 * </p>
 * 
 * @author CardDemo Migration Team
 * @version 1.0
 * @since 2024-01-01
 */
@Entity
@Table(name = "transaction_category")
@Data
@NoArgsConstructor
@AllArgsConstructor
public class TransactionCategory implements Serializable {

    /**
     * Serial version UID for Serializable interface.
     * Supports distributed caching with Redis-backed Spring Session for
     * clustered deployment environments per Section 0.5 caching strategy.
     * Required by JPA specification for entity serialization.
     */
    private static final long serialVersionUID = 1L;

    /**
     * Transaction category code - 6-character primary key.
     * 
     * <p>Maps from database field: transaction_category_code CHAR(6)</p>
     * 
     * <p>Format: 6-digit numeric string where first 2 digits match the transaction type code</p>
     * <ul>
     *   <li>Example: "010001" where "01" is the type code and "0001" is the category within that type</li>
     *   <li>Range: "000001" to "999999"</li>
     * </ul>
     * 
     * <p>Database constraint ensures first 2 characters match transaction_type_code field.</p>
     * 
     * <p>Usage in queries:
     * <pre>
     * String categoryCode = "010001";
     * TransactionCategory category = repository.findById(categoryCode).orElseThrow();
     * </pre>
     * </p>
     */
    @Id
    @Column(name = "transaction_category_code", length = 6, nullable = false)
    private String categoryCode;

    /**
     * Transaction type code - 2-character foreign key to transaction_type.
     * 
     * <p>Maps from database field: transaction_type_code CHAR(2)</p>
     * 
     * <p>References the parent transaction type. Must match the first 2 characters of categoryCode
     * per database check constraint.</p>
     * 
     * <p>Examples: "01", "02", "03", etc.</p>
     */
    @Column(name = "transaction_type_code", length = 2, nullable = false)
    private String typeCode;

    /**
     * Transaction category description (50 characters).
     * 
     * <p>Maps from COBOL field: TRAN-CAT-TYPE-DESC PIC X(50)</p>
     * 
     * <p>Human-readable description of the transaction category displayed in:
     * <ul>
     *   <li>Transaction list screens (COTRN00M.bms → TransactionListComponent.jsx)</li>
     *   <li>Category summary views (COTRN01M.bms → TransactionCategoryComponent.jsx)</li>
     *   <li>Transaction reports (CBTRN03C.cbl → TransactionAggregationJob.java)</li>
     *   <li>Monthly statements (CBSTM03A.cbl → StatementGenerationJob.java)</li>
     * </ul>
     * </p>
     * 
     * <p>Example values by transaction type:
     * <ul>
     *   <li>Type "PU" (Purchase): "Groceries", "Gas", "Dining", "Online Shopping"</li>
     *   <li>Type "CA" (Cash Advance): "ATM Withdrawal", "Branch Cash", "Check Cashing"</li>
     *   <li>Type "PM" (Payment): "Online Payment", "Mail Payment", "Phone Payment"</li>
     *   <li>Type "FE" (Fee): "Annual Fee", "Late Fee", "Over Limit Fee"</li>
     * </ul>
     * </p>
     * 
     * <p>Constraints:
     * <ul>
     *   <li>Length: Maximum 50 characters (matches COBOL PIC X(50))</li>
     *   <li>NOT NULL: Required field per COBOL copybook structure</li>
     *   <li>Immutable reference data: Values loaded via Flyway migration</li>
     * </ul>
     * </p>
     * 
     * <p>Note: COBOL FILLER (4 bytes) not mapped - unused padding for 60-byte alignment</p>
     */
    @Column(name = "category_description", length = 50, nullable = false)
    private String categoryDescription;

    /**
     * Many-to-one relationship to TransactionType entity via typeCode.
     * 
     * <p>Establishes foreign key constraint from transaction_category.transaction_type_code to
     * transaction_type.transaction_type_code, ensuring referential integrity per Section 0.9
     * cross-reference data relationship requirements. Supports navigation from category
     * to parent transaction type for hierarchical categorization and reporting.</p>
     * 
     * <p>Relationship Characteristics:
     * <ul>
     *   <li>Fetch Type: LAZY - defers loading until explicitly accessed</li>
     *   <li>Optional: false - every category must have a valid transaction type</li>
     *   <li>Join Column: transaction_type_code</li>
     *   <li>Cardinality: Many categories can share the same transaction type</li>
     * </ul>
     * </p>
     * 
     * <p>Performance Optimization:
     * LAZY fetch type prevents unnecessary TransactionType entity loads when only
     * category information is needed, reducing query overhead and memory usage.
     * Critical for maintaining sub-200ms response times under high transaction
     * volumes per Section 0.2 performance requirements.</p>
     * 
     * <p>Usage Example:
     * <pre>
     * TransactionCategory category = repository.findById(categoryCode).orElseThrow();
     * // TransactionType not loaded yet (LAZY)
     * String typeDesc = category.getTransactionType().getTypeDescription();
     * // Now TransactionType is loaded on demand
     * </pre>
     * </p>
     * 
     * <p>JSON Serialization:
     * Annotated with @JsonIgnore to prevent circular reference issues when
     * TransactionCategory entities are serialized in REST API responses. Breaks
     * bidirectional relationship serialization loop if TransactionType also
     * references TransactionCategory collection, avoiding infinite recursion
     * during JSON conversion.</p>
     */
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "transaction_type_code", referencedColumnName = "type_code", 
                insertable = false, updatable = false)
    @JsonIgnore
    private TransactionType transactionType;



    /**
     * Getter for category description.
     * Provided by Lombok @Data annotation.
     * 
     * @return the 50-character category description
     */
    // Lombok @Data generates: public String getCategoryDescription()

    /**
     * Setter for category description.
     * Provided by Lombok @Data annotation.
     * 
     * @param categoryDescription the category description to set (max 50 characters)
     */
    // Lombok @Data generates: public void setCategoryDescription(String categoryDescription)

    /**
     * Getter for TransactionType relationship.
     * Provided by Lombok @Data annotation.
     * 
     * <p>Note: Accessing this method will trigger LAZY loading of TransactionType entity
     * if not already loaded. May result in additional database query if entity not in
     * persistence context or cache.</p>
     * 
     * @return the associated TransactionType entity
     */
    // Lombok @Data generates: public TransactionType getTransactionType()

    /**
     * Setter for TransactionType relationship.
     * Provided by Lombok @Data annotation.
     * 
     * <p>Note: Setting relationship directly is generally not needed as typeCode in
     * composite key establishes the foreign key reference. This setter primarily
     * exists for JPA relationship management and bidirectional association maintenance.</p>
     * 
     * @param transactionType the TransactionType entity to associate
     */
    // Lombok @Data generates: public void setTransactionType(TransactionType transactionType)
}
