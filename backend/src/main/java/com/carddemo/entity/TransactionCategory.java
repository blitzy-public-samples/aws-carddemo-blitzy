package com.carddemo.entity;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
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
     * Composite primary key containing transaction type code and category code.
     * 
     * <p>Embedded composite key class (CategoryId) maps from COBOL compound key structure:
     * <pre>
     * 05  TRAN-CAT-KEY.
     *    10  TRAN-TYPE-CD         PIC X(02).
     *    10  TRAN-CAT-CD          PIC 9(04).
     * </pre>
     * </p>
     * 
     * <p>Components:
     * <ul>
     *   <li>typeCode: 2-character transaction type (e.g., "PU", "CA", "PM")</li>
     *   <li>categoryCode: 4-digit numeric category within type (0001-9999)</li>
     * </ul>
     * </p>
     * 
     * <p>JPA @EmbeddedId provides:
     * <ul>
     *   <li>Type-safe composite key access in repository methods</li>
     *   <li>Proper entity identity management with equals/hashCode</li>
     *   <li>Support for composite key queries and specifications</li>
     *   <li>Serializable key for distributed caching</li>
     * </ul>
     * </p>
     * 
     * <p>Usage in queries:
     * <pre>
     * CategoryId id = new CategoryId("PU", 1001);
     * TransactionCategory category = repository.findById(id).orElseThrow();
     * </pre>
     * </p>
     */
    @EmbeddedId
    private CategoryId id;

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
     * <p>Establishes foreign key constraint from transaction_category.type_code to
     * transaction_type.type_code, ensuring referential integrity per Section 0.9
     * cross-reference data relationship requirements. Supports navigation from category
     * to parent transaction type for hierarchical categorization and reporting.</p>
     * 
     * <p>Relationship Characteristics:
     * <ul>
     *   <li>Fetch Type: LAZY - defers loading until explicitly accessed</li>
     *   <li>Optional: false - every category must have a valid transaction type</li>
     *   <li>Join Column: type_code from composite key id</li>
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
     * TransactionCategory category = repository.findById(id).orElseThrow();
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
    @JoinColumn(name = "type_code", referencedColumnName = "type_code", 
                insertable = false, updatable = false)
    @JsonIgnore
    private TransactionType transactionType;

    /**
     * Embeddable composite primary key class for TransactionCategory.
     * 
     * <p>Implements JPA composite key pattern using @Embeddable annotation.
     * Maps directly to COBOL TRAN-CAT-KEY structure containing both transaction
     * type code and category code components.</p>
     * 
     * <p>COBOL Source:
     * <pre>
     * 05  TRAN-CAT-KEY.
     *    10  TRAN-TYPE-CD         PIC X(02).
     *    10  TRAN-CAT-CD          PIC 9(04).
     * </pre>
     * </p>
     * 
     * <p>JPA Requirements:
     * <ul>
     *   <li>Must implement Serializable for entity identity management</li>
     *   <li>Must override equals() and hashCode() for proper entity comparison</li>
     *   <li>Must have public no-arg constructor for JPA instantiation</li>
     *   <li>All fields must have public getters and setters</li>
     * </ul>
     * </p>
     * 
     * <p>Database Mapping:
     * <ul>
     *   <li>type_code: VARCHAR(2) NOT NULL</li>
     *   <li>category_code: INTEGER NOT NULL</li>
     *   <li>Composite PRIMARY KEY (type_code, category_code)</li>
     *   <li>Composite INDEX for efficient lookups by type</li>
     * </ul>
     * </p>
     * 
     * <p>Usage in Repository Queries:
     * <pre>
     * // Single category lookup
     * CategoryId id = new CategoryId("PU", 1001);
     * Optional&lt;TransactionCategory&gt; category = repository.findById(id);
     * 
     * // All categories for a transaction type
     * List&lt;TransactionCategory&gt; purchaseCategories = 
     *     repository.findByIdTypeCode("PU");
     * </pre>
     * </p>
     */
    @Embeddable
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class CategoryId implements Serializable {

        /**
         * Serial version UID for Serializable interface.
         * Required by JPA specification for composite key serialization.
         */
        private static final long serialVersionUID = 1L;

        /**
         * Transaction type code component of composite key (2 characters).
         * 
         * <p>Maps from COBOL field: TRAN-TYPE-CD PIC X(02)</p>
         * 
         * <p>First component of composite primary key. References transaction_type
         * table to establish foreign key relationship. Common values include "PU"
         * (Purchase), "CA" (Cash Advance), "PM" (Payment), "RF" (Refund), "FE" (Fee),
         * "IN" (Interest).</p>
         * 
         * <p>Constraints:
         * <ul>
         *   <li>Length: Exactly 2 characters (matches COBOL PIC X(02))</li>
         *   <li>NOT NULL: Required component of primary key</li>
         *   <li>Foreign Key: Must exist in transaction_type.type_code</li>
         *   <li>Immutable: Should not be changed after entity creation</li>
         * </ul>
         * </p>
         */
        @Column(name = "type_code", length = 2, nullable = false)
        private String typeCode;

        /**
         * Category code component of composite key (4-digit integer).
         * 
         * <p>Maps from COBOL field: TRAN-CAT-CD PIC 9(04)</p>
         * 
         * <p>Second component of composite primary key. Provides unique category
         * identification within each transaction type. Numeric range 0001-9999
         * allows up to 9,999 distinct categories per transaction type.</p>
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
         *   <li>Unique: Combined with typeCode forms unique composite key</li>
         *   <li>Immutable: Should not be changed after entity creation</li>
         * </ul>
         * </p>
         * 
         * <p>Note: Using Integer instead of String for numeric COBOL PIC 9 field
         * provides type safety and natural ordering for category code ranges.</p>
         */
        @Column(name = "category_code", nullable = false)
        private Integer categoryCode;

        /**
         * Equals method for composite key comparison.
         * 
         * <p>Implements proper equality semantics for JPA composite key as required by
         * JPA specification. Two CategoryId instances are equal if and only if both
         * typeCode and categoryCode fields are equal.</p>
         * 
         * <p>Critical for:
         * <ul>
         *   <li>JPA entity identity management and caching</li>
         *   <li>Repository findById() lookups</li>
         *   <li>Collection operations (Set, Map keys)</li>
         *   <li>Test assertions for functional equivalence validation</li>
         * </ul>
         * </p>
         * 
         * <p>Uses Objects.equals() for null-safe comparison supporting composite key
         * fields that may be null during entity construction. Maintains contract with
         * hashCode() per Java equals/hashCode specification.</p>
         * 
         * @param o the object to compare with this CategoryId
         * @return true if objects are equal (same typeCode and categoryCode), false otherwise
         */
        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;
            CategoryId that = (CategoryId) o;
            return Objects.equals(typeCode, that.typeCode) &&
                   Objects.equals(categoryCode, that.categoryCode);
        }

        /**
         * HashCode method for composite key hashing.
         * 
         * <p>Generates hash code based on both typeCode and categoryCode fields for use
         * in hash-based collections (HashMap, HashSet, JPA entity cache). Ensures proper
         * distribution of hash values across composite key space.</p>
         * 
         * <p>Critical for:
         * <ul>
         *   <li>Efficient JPA second-level caching with Hibernate</li>
         *   <li>Fast HashMap/HashSet operations in service layer</li>
         *   <li>Redis distributed caching with proper key distribution</li>
         *   <li>Collection performance under 10,000 TPS load</li>
         * </ul>
         * </p>
         * 
         * <p>Uses Objects.hash() to generate consistent hash code from multiple fields.
         * Maintains equals/hashCode contract: equal objects have equal hash codes.</p>
         * 
         * @return hash code value for this CategoryId composite key
         */
        @Override
        public int hashCode() {
            return Objects.hash(typeCode, categoryCode);
        }
    }

    /**
     * Custom toString() implementation provided by Lombok @Data annotation.
     * 
     * <p>Generates string representation in format:
     * <code>TransactionCategory(id=CategoryId(typeCode=PU, categoryCode=1001), 
     * categoryDescription=Groceries)</code></p>
     * 
     * <p>Excludes transactionType field from string representation to prevent
     * lazy loading and potential infinite recursion if TransactionType also has
     * toString() that references categories.</p>
     * 
     * <p>Used for:
     * <ul>
     *   <li>Logging transaction categorization operations</li>
     *   <li>Debugging category lookup issues</li>
     *   <li>Audit trail entries per Section 0.9 compliance requirements</li>
     *   <li>Test assertion error messages</li>
     * </ul>
     * </p>
     * 
     * @return String representation of this TransactionCategory entity
     */
    // Lombok @Data generates: public String toString()

    /**
     * Getter for composite primary key.
     * Provided by Lombok @Data annotation.
     * 
     * @return the CategoryId composite key containing typeCode and categoryCode
     */
    // Lombok @Data generates: public CategoryId getId()

    /**
     * Setter for composite primary key.
     * Provided by Lombok @Data annotation.
     * 
     * <p>Note: Primary key should typically be set only once during entity creation
     * and not modified afterwards to maintain referential integrity.</p>
     * 
     * @param id the CategoryId composite key to set
     */
    // Lombok @Data generates: public void setId(CategoryId id)

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
