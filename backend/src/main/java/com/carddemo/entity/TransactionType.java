package com.carddemo.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

/**
 * JPA Entity representing transaction type reference data.
 * 
 * <p>Transformed from COBOL copybook CVTRA03Y.cpy (TRAN-TYPE-RECORD structure).
 * This entity serves as a lookup table for categorizing transactions in the credit card
 * management system. Contains transaction type codes (2-character primary key) and their
 * descriptions (50 characters).</p>
 * 
 * <p>Original COBOL Structure (60-byte record):
 * <pre>
 * 01  TRAN-TYPE-RECORD.
 *     05  TRAN-TYPE           PIC X(02).
 *     05  TRAN-TYPE-DESC      PIC X(50).
 *     05  FILLER              PIC X(08).
 * </pre>
 * </p>
 * 
 * <p>Database Mapping:
 * <ul>
 *   <li>Table: transaction_type</li>
 *   <li>Primary Key: type_code (2 characters)</li>
 *   <li>Reference data loaded via Flyway migration V9__load_reference_data.sql</li>
 * </ul>
 * </p>
 * 
 * <p>Usage: This entity is referenced by Transaction entities to categorize transaction
 * types (e.g., "PU" = Purchase, "CA" = Cash Advance, "PM" = Payment). Enables efficient
 * indexed PostgreSQL lookups replacing VSAM sequential reads of transaction type reference
 * files.</p>
 * 
 * <p>Performance Characteristics:
 * <ul>
 *   <li>Static reference data - ideal for caching with Redis</li>
 *   <li>Small dataset (typically 10-20 transaction types)</li>
 *   <li>Read-heavy access pattern with no runtime modifications</li>
 *   <li>Indexed lookups support sub-200ms response time SLA</li>
 * </ul>
 * </p>
 * 
 * @author CardDemo Migration Team
 * @version 1.0
 * @since 2024-01-01
 */
@Entity
@Table(name = "transaction_type")
@Data
@NoArgsConstructor
@AllArgsConstructor
public class TransactionType implements Serializable {

    /**
     * Serial version UID for Serializable interface.
     * Supports distributed caching with Redis-backed Spring Session for
     * clustered deployment environments per Section 0.5 caching strategy.
     */
    private static final long serialVersionUID = 1L;

    /**
     * Transaction type code (2-character primary key).
     * 
     * <p>Maps from COBOL field: TRAN-TYPE PIC X(02)</p>
     * 
     * <p>Common values:
     * <ul>
     *   <li>"PU" - Purchase transaction</li>
     *   <li>"CA" - Cash advance</li>
     *   <li>"PM" - Payment</li>
     *   <li>"RF" - Refund</li>
     *   <li>"FE" - Fee</li>
     *   <li>"IN" - Interest charge</li>
     * </ul>
     * </p>
     * 
     * <p>Constraints:
     * <ul>
     *   <li>Length: Exactly 2 characters (matches COBOL PIC X(02))</li>
     *   <li>NOT NULL: Required field per COBOL copybook structure</li>
     *   <li>Primary Key: Unique identifier for transaction types</li>
     * </ul>
     * </p>
     */
    @Id
    @Column(name = "type_code", length = 2, nullable = false)
    private String typeCode;

    /**
     * Transaction type description (50 characters).
     * 
     * <p>Maps from COBOL field: TRAN-TYPE-DESC PIC X(50)</p>
     * 
     * <p>Human-readable description of the transaction type displayed in:
     * <ul>
     *   <li>Transaction list screens (COTRN00M.bms → TransactionListComponent.jsx)</li>
     *   <li>Transaction detail views (COTRN01M.bms → TransactionCategoryComponent.jsx)</li>
     *   <li>Reports and statements (CBSTM03A.cbl → StatementGenerationJob.java)</li>
     * </ul>
     * </p>
     * 
     * <p>Constraints:
     * <ul>
     *   <li>Length: Maximum 50 characters (matches COBOL PIC X(50))</li>
     *   <li>NOT NULL: Required field per COBOL copybook structure</li>
     *   <li>Typical values: "Purchase", "Cash Advance", "Payment", "Refund"</li>
     * </ul>
     * </p>
     * 
     * <p>Note: COBOL FILLER (8 bytes) not mapped - unused padding for 60-byte alignment</p>
     */
    @Column(name = "type_description", length = 50, nullable = false)
    private String typeDescription;

    /**
     * Custom toString() implementation provided by Lombok @Data annotation.
     * 
     * <p>Generates string representation in format:
     * <code>TransactionType(typeCode=PU, typeDescription=Purchase)</code></p>
     * 
     * <p>Used for logging, debugging, and audit trail entries maintaining
     * equivalent detail level to COBOL audit logs per Section 0.9 compliance requirements.</p>
     * 
     * @return String representation of this TransactionType entity
     */
    // Lombok @Data generates: public String toString()

    /**
     * Equals method provided by Lombok @Data annotation.
     * 
     * <p>Compares TransactionType instances based on all fields (typeCode, typeDescription).
     * Supports entity equality checks in service layer and test assertions ensuring
     * functional equivalence with COBOL record comparison logic.</p>
     * 
     * @param o Object to compare with this TransactionType
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
     * @return hash code value for this TransactionType
     */
    // Lombok @Data generates: public int hashCode()

    /**
     * Getter for typeCode provided by Lombok @Data annotation.
     * 
     * @return the 2-character transaction type code
     */
    // Lombok @Data generates: public String getTypeCode()

    /**
     * Setter for typeCode provided by Lombok @Data annotation.
     * 
     * @param typeCode the 2-character transaction type code to set
     */
    // Lombok @Data generates: public void setTypeCode(String typeCode)

    /**
     * Getter for typeDescription provided by Lombok @Data annotation.
     * 
     * @return the transaction type description (up to 50 characters)
     */
    // Lombok @Data generates: public String getTypeDescription()

    /**
     * Setter for typeDescription provided by Lombok @Data annotation.
     * 
     * @param typeDescription the transaction type description to set (max 50 chars)
     */
    // Lombok @Data generates: public void setTypeDescription(String typeDescription)
}
