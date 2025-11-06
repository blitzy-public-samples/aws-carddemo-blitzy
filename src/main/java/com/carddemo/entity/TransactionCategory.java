package com.carddemo.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

/**
 * JPA Entity representing Transaction Category reference data.
 * 
 * This entity is mapped from COBOL copybook CVTRA04Y.cpy (TRAN-CAT-RECORD)
 * and represents the transaction_category table in PostgreSQL.
 * 
 * The entity uses a composite primary key consisting of transaction type code
 * and category code, matching the VSAM KSDS key structure from the mainframe.
 * 
 * Transaction categories are used to classify and group transaction types
 * for reporting, billing, and business intelligence purposes in the CardDemo
 * credit card management system.
 * 
 * Record Structure from COBOL:
 * - TRAN-TYPE-CD: PIC X(02) - 2 character transaction type code
 * - TRAN-CAT-CD: PIC 9(04) - 4 digit transaction category code
 * - TRAN-CAT-TYPE-DESC: PIC X(50) - Transaction category description
 * 
 * @see TransactionCategoryId
 */
@Entity
@Table(name = "transaction_category")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class TransactionCategory implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * Composite primary key containing transaction type code and category code.
     * Maps to TRAN-CAT-KEY from COBOL copybook CVTRA04Y.cpy.
     */
    @EmbeddedId
    private TransactionCategoryId id;

    /**
     * Transaction category description.
     * Maps to TRAN-CAT-TYPE-DESC (PIC X(50)) from COBOL copybook.
     * Provides human-readable description of the transaction category.
     */
    @Column(name = "category_description", length = 50, nullable = false)
    private String categoryDescription;

    /**
     * Version field for optimistic locking.
     * Prevents concurrent update conflicts when multiple transactions
     * attempt to modify the same transaction category record simultaneously.
     * 
     * JPA automatically increments this value on each update operation.
     */
    @Version
    @Column(name = "version")
    private Long version;

    /**
     * Composite Primary Key class for TransactionCategory entity.
     * 
     * This embeddable class represents the composite key structure from
     * COBOL copybook CVTRA04Y.cpy (TRAN-CAT-KEY), consisting of:
     * - Transaction Type Code (TRAN-TYPE-CD)
     * - Transaction Category Code (TRAN-CAT-CD)
     * 
     * Both fields are required to uniquely identify a transaction category
     * in the system, maintaining the same key structure as the original
     * VSAM KSDS file from the mainframe application.
     * 
     * This class must be Serializable as required by JPA specification
     * for composite primary key classes.
     */
    @Embeddable
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class TransactionCategoryId implements Serializable {

        private static final long serialVersionUID = 1L;

        /**
         * Transaction type code - first part of composite key.
         * Maps to TRAN-TYPE-CD (PIC X(02)) from COBOL copybook.
         * 
         * This 2-character code identifies the transaction type
         * (e.g., "01" for purchases, "02" for cash advances).
         */
        @Column(name = "type_code", length = 2, nullable = false)
        private String typeCode;

        /**
         * Transaction category code - second part of composite key.
         * Maps to TRAN-CAT-CD (PIC 9(04)) from COBOL copybook.
         * 
         * This 4-digit code identifies the specific category within
         * the transaction type (e.g., "0001" for retail purchases,
         * "0002" for online purchases).
         * 
         * Note: Although COBOL defines this as PIC 9(04) (numeric),
         * it is stored as String to preserve leading zeros and maintain
         * compatibility with the original mainframe data format.
         */
        @Column(name = "category_code", length = 4, nullable = false)
        private String categoryCode;
    }
}
