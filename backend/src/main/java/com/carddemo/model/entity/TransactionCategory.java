package com.carddemo.model.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.sql.Timestamp;
import java.util.Objects;

/**
 * JPA Entity representing transaction category reference data.
 * 
 * Converted from COBOL copybook: CVTRA04Y.cpy
 * Original structure: TRAN-CAT-RECORD (60-byte fixed record)
 * 
 * This entity defines transaction categories with a composite key consisting of
 * transaction type code and category code. It serves as a reference table for
 * categorizing transactions in the CardDemo credit card management system.
 * 
 * Conversion notes:
 * - Composite key TRAN-CAT-KEY (TRAN-TYPE-CD PIC X(02) + TRAN-CAT-CD PIC 9(04))
 *   converted to @IdClass pattern with separate @Id annotations
 * - PIC X(02) TRAN-TYPE-CD converted to String with max length 2
 * - PIC 9(04) TRAN-CAT-CD converted to Integer
 * - PIC X(50) TRAN-CAT-TYPE-DESC converted to String with max length 50
 * - FILLER field removed (not needed in relational model)
 * - Added audit fields createdAt and updatedAt with TIMESTAMP columns
 * - Added version field for JPA optimistic locking (replicates VSAM RBA check)
 * 
 * Database mapping: transaction_category table
 * Record count (estimated): 50 transaction category records
 * Access pattern: Reference data loaded at startup, rarely updated
 * 
 * @see TransactionCategoryId Composite primary key class
 */
@Entity
@Table(name = "transaction_category")
@IdClass(TransactionCategoryId.class)
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TransactionCategory implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * Transaction type code (part 1 of composite key).
     * COBOL: TRAN-TYPE-CD PIC X(02)
     * Examples: "01" = Purchase, "02" = Cash Advance, "03" = Payment
     */
    @Id
    @Column(name = "trans_type_cd", length = 2, nullable = false)
    private String transTypeCd;

    /**
     * Transaction category code (part 2 of composite key).
     * COBOL: TRAN-CAT-CD PIC 9(04)
     * Examples: 5001 = Groceries, 5002 = Dining, 5003 = Gas/Fuel
     */
    @Id
    @Column(name = "trans_cat_cd", nullable = false)
    private Integer tranCatCd;

    /**
     * Transaction category type description.
     * COBOL: TRAN-CAT-TYPE-DESC PIC X(50)
     * Contains human-readable description of the transaction category.
     */
    @Column(name = "trans_cat_type_desc", length = 50, nullable = false)
    private String tranCatTypeDesc;

    /**
     * Timestamp when this transaction category record was created.
     * Not present in original COBOL - added for audit tracking.
     */
    @Column(name = "created_at", nullable = false, updatable = false)
    private Timestamp createdAt;

    /**
     * Timestamp when this transaction category record was last updated.
     * Not present in original COBOL - added for audit tracking.
     */
    @Column(name = "updated_at", nullable = false)
    private Timestamp updatedAt;

    /**
     * Version field for optimistic locking.
     * Prevents concurrent update conflicts, replicating COBOL VSAM RBA
     * optimistic locking semantics from mainframe environment.
     * Automatically incremented by JPA on each update operation.
     */
    @Version
    @Column(name = "version", nullable = false)
    private Integer version;

    /**
     * JPA lifecycle callback to set creation timestamp before persist.
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
     * JPA lifecycle callback to update modification timestamp before update.
     */
    @PreUpdate
    protected void onUpdate() {
        this.updatedAt = new Timestamp(System.currentTimeMillis());
    }
}

/**
 * Composite primary key class for TransactionCategory entity.
 * 
 * Required by JPA specification for @IdClass pattern with multi-column keys.
 * Must implement Serializable and provide equals() and hashCode() methods
 * based on all key fields.
 * 
 * Represents the composite key structure from COBOL TRAN-CAT-KEY:
 * - TRAN-TYPE-CD PIC X(02)
 * - TRAN-CAT-CD PIC 9(04)
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
class TransactionCategoryId implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * Transaction type code (part 1 of composite key).
     * Must match the field name and type in TransactionCategory entity.
     */
    private String transTypeCd;

    /**
     * Transaction category code (part 2 of composite key).
     * Must match the field name and type in TransactionCategory entity.
     */
    private Integer tranCatCd;

    /**
     * Equals method for composite key comparison.
     * Required by JPA specification for proper entity identity management.
     * 
     * @param o Object to compare with
     * @return true if both transTypeCd and tranCatCd match
     */
    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        TransactionCategoryId that = (TransactionCategoryId) o;
        return Objects.equals(transTypeCd, that.transTypeCd) &&
               Objects.equals(tranCatCd, that.tranCatCd);
    }

    /**
     * Hash code method for composite key.
     * Required by JPA specification for proper hash-based collection usage.
     * 
     * @return Hash code based on both key fields
     */
    @Override
    public int hashCode() {
        return Objects.hash(transTypeCd, tranCatCd);
    }
}
