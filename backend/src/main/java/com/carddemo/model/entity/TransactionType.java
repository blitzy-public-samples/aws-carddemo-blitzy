package com.carddemo.model.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.sql.Timestamp;

/**
 * JPA Entity representing transaction type reference data.
 * 
 * Converted from COBOL copybook: CVTRA03Y.cpy (TRAN-TYPE-RECORD)
 * Original function: Transaction type code lookup table (60-byte fixed record)
 * 
 * This entity defines transaction type codes and their descriptions, serving as
 * a reference table for categorizing transaction types in the CardDemo system.
 * Examples include '01' for purchase, '02' for credit/payment, etc.
 * 
 * Conversion notes:
 * - COBOL PIC X(02) TRAN-TYPE mapped to String transTypeCd (primary key)
 * - COBOL PIC X(50) TRAN-TYPE-DESC mapped to String transTypeDesc
 * - COBOL FILLER PIC X(08) discarded (not needed in Java entity)
 * - Added audit fields createdAt and updatedAt for tracking record lifecycle
 * - Added version field for JPA optimistic locking (replicates VSAM RBA semantics)
 * - Uses Lombok annotations to reduce boilerplate code
 * 
 * Database mapping: transaction_type table (PostgreSQL)
 * VSAM dataset: TRANTYPE (Key-Sequenced Data Set)
 * 
 * Related entities:
 * - Transaction: References this entity via trans_type_cd foreign key
 * - DailyTransaction: References this entity via trans_type_cd foreign key
 * - DisclosureGroup: May reference transaction types for disclosure rules
 * 
 * @see com.carddemo.model.entity.Transaction
 * @see com.carddemo.model.entity.DailyTransaction
 */
@Entity
@Table(name = "transaction_type")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TransactionType {

    /**
     * Transaction type code (primary key).
     * 
     * 2-character code identifying the transaction type.
     * Examples: '01' = Purchase, '02' = Credit/Payment, '03' = Cash Advance, etc.
     * 
     * COBOL source: TRAN-TYPE PIC X(02)
     * Database column: trans_type_cd VARCHAR(2) PRIMARY KEY
     */
    @Id
    @Column(name = "trans_type_cd", length = 2, nullable = false)
    private String transTypeCd;

    /**
     * Transaction type description.
     * 
     * Human-readable description of the transaction type code.
     * Examples: "Purchase Transaction", "Payment/Credit", "Cash Advance", etc.
     * 
     * COBOL source: TRAN-TYPE-DESC PIC X(50)
     * Database column: trans_type_desc VARCHAR(50) NOT NULL
     */
    @Column(name = "trans_type_desc", length = 50, nullable = false)
    private String transTypeDesc;

    /**
     * Record creation timestamp.
     * 
     * Automatically set when the transaction type record is first created.
     * Provides audit trail capability not present in original COBOL/VSAM system.
     * 
     * Database column: created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
     */
    @Column(name = "created_at", nullable = false, updatable = false)
    private Timestamp createdAt;

    /**
     * Record last update timestamp.
     * 
     * Automatically updated whenever the transaction type record is modified.
     * Provides audit trail capability not present in original COBOL/VSAM system.
     * 
     * Database column: updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
     */
    @Column(name = "updated_at", nullable = false)
    private Timestamp updatedAt;

    /**
     * Optimistic locking version field.
     * 
     * Automatically incremented by JPA on each update to prevent concurrent
     * modification conflicts. Replicates COBOL VSAM RBA (Relative Byte Address)
     * optimistic locking semantics from mainframe environment.
     * 
     * If two transactions attempt to update the same record simultaneously,
     * the second transaction will fail with OptimisticLockException.
     * 
     * Database column: version INTEGER DEFAULT 0
     */
    @Version
    @Column(name = "version", nullable = false)
    private Integer version;

    /**
     * JPA lifecycle callback executed before entity persistence.
     * 
     * Automatically sets the createdAt and updatedAt timestamps when a new
     * transaction type record is inserted into the database.
     * 
     * This replaces COBOL CURRENT-DATE function calls with automatic
     * timestamp management through JPA lifecycle events.
     */
    @PrePersist
    protected void onCreate() {
        Timestamp now = new Timestamp(System.currentTimeMillis());
        this.createdAt = now;
        this.updatedAt = now;
    }

    /**
     * JPA lifecycle callback executed before entity update.
     * 
     * Automatically updates the updatedAt timestamp whenever a transaction
     * type record is modified in the database.
     * 
     * This replaces COBOL CURRENT-DATE function calls with automatic
     * timestamp management through JPA lifecycle events.
     */
    @PreUpdate
    protected void onUpdate() {
        this.updatedAt = new Timestamp(System.currentTimeMillis());
    }
}
