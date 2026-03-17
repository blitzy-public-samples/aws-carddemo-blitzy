package com.cardemo.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;

import java.io.Serial;
import java.io.Serializable;
import java.util.Objects;

/**
 * JPA entity representing a transaction category reference record.
 *
 * <p>Migrated from the VSAM TRANCATG reference dataset containing 18 transaction
 * category records parsed from {@code app/data/ASCII/trancatg.txt}. Each record
 * maps a composite key of transaction type code and category code to a
 * human-readable description.</p>
 *
 * <h3>Source Data Format (trancatg.txt, 18 records):</h3>
 * <pre>
 * Positions [0:2]   = 2-digit type code prefix (e.g., "01" through "07")
 * Positions [2:6]   = 4-digit category code   (e.g., "0001", "0002")
 * Positions [6:56]  = 50-char description      (right-padded with spaces)
 * Positions [56:60] = filler "0000"
 * </pre>
 *
 * <h3>Database Schema (V1__create_schema.sql):</h3>
 * <pre>
 * CREATE TABLE transaction_category_refs (
 *     tran_type_cd        VARCHAR(2)     NOT NULL,
 *     tran_cat_cd         INTEGER        NOT NULL,
 *     tran_cat_type_desc  VARCHAR(50)    NOT NULL,
 *     PRIMARY KEY (tran_type_cd, tran_cat_cd)
 * );
 * </pre>
 *
 * <p>The composite primary key consists of {@code typeCode} (VARCHAR(2)) and
 * {@code categoryCode} (INTEGER). Category codes are reused across different
 * transaction types (e.g., category code 1 appears under types 01, 02, 03, etc.),
 * making the composite key essential for uniqueness.</p>
 *
 * <h3>Reference Data Categories (18 records across 7 types):</h3>
 * <ul>
 *   <li>Type 01 (Purchase): 5 categories — Sales Draft, Cash Advance, Check Debit, ATM, Interest</li>
 *   <li>Type 02 (Payment): 3 categories — Cash, Electronic, Check</li>
 *   <li>Type 03 (Credit): 3 categories — Account Credit, Purchase Balance, Cash Balance</li>
 *   <li>Type 04 (Authorization): 3 categories — Zero Dollar, Online Purchase, Travel Booking</li>
 *   <li>Type 05 (Refund): 1 category — Refund Credit</li>
 *   <li>Type 06 (Reversal): 2 categories — Fraud Reversal, Non-Fraud Reversal</li>
 *   <li>Type 07 (Adjustment): 1 category — Sales Draft Credit Adjustment</li>
 * </ul>
 *
 * @see <a href="app/data/ASCII/trancatg.txt">trancatg.txt — 18 transaction category records</a>
 */
@Entity
@Table(name = "transaction_category_refs")
@IdClass(TransactionCategoryRef.TransactionCategoryRefId.class)
public class TransactionCategoryRef {

    /**
     * Transaction type code — part of composite primary key.
     * Derived from positions [0:2] of each trancatg.txt record.
     * A 2-character code (e.g., "01" through "07") that links this category
     * to a parent transaction type in the {@code TransactionTypeRef} entity.
     */
    @Id
    @Column(name = "tran_type_cd", length = 2, nullable = false)
    private String typeCode;

    /**
     * Transaction category code — part of composite primary key.
     * Derived from positions [2:6] of each trancatg.txt record.
     * Stored as Integer matching the PostgreSQL INTEGER column type.
     * Category codes are reused across different transaction types,
     * making the composite key (typeCode, categoryCode) essential.
     */
    @Id
    @Column(name = "tran_cat_cd", nullable = false)
    private Integer categoryCode;

    /**
     * Human-readable description of the transaction category.
     * Derived from positions [6:56] of each trancatg.txt record (trimmed).
     * Examples: "Regular Sales Draft", "Cash payment", "Fraud reversal".
     */
    @Column(name = "tran_cat_type_desc", length = 50, nullable = false)
    private String categoryDescription;

    /**
     * Default no-argument constructor required by JPA.
     * Protected access to discourage direct instantiation outside of
     * the persistence framework.
     */
    protected TransactionCategoryRef() {
        // Required by JPA specification
    }

    /**
     * Constructs a new TransactionCategoryRef with all fields populated.
     *
     * @param typeCode            the 2-character type code prefix (positions [0:2])
     * @param categoryCode        the category code as Integer (positions [2:6])
     * @param categoryDescription the trimmed category description (positions [6:56])
     */
    public TransactionCategoryRef(String typeCode, Integer categoryCode,
                                  String categoryDescription) {
        this.typeCode = typeCode;
        this.categoryCode = categoryCode;
        this.categoryDescription = categoryDescription;
    }

    /**
     * Returns the transaction type code (part of composite primary key).
     *
     * @return the 2-character type code (e.g., "01" through "07")
     */
    public String getTypeCode() {
        return typeCode;
    }

    /**
     * Sets the transaction type code (part of composite primary key).
     *
     * @param typeCode the 2-character type code
     */
    public void setTypeCode(String typeCode) {
        this.typeCode = typeCode;
    }

    /**
     * Returns the transaction category code (part of composite primary key).
     *
     * @return the category code as Integer
     */
    public Integer getCategoryCode() {
        return categoryCode;
    }

    /**
     * Sets the transaction category code (part of composite primary key).
     *
     * @param categoryCode the category code as Integer
     */
    public void setCategoryCode(Integer categoryCode) {
        this.categoryCode = categoryCode;
    }

    /**
     * Returns the human-readable description of the transaction category.
     *
     * @return the category description (e.g., "Regular Sales Draft")
     */
    public String getCategoryDescription() {
        return categoryDescription;
    }

    /**
     * Sets the human-readable description of the transaction category.
     *
     * @param categoryDescription the category description
     */
    public void setCategoryDescription(String categoryDescription) {
        this.categoryDescription = categoryDescription;
    }

    /**
     * Compares this TransactionCategoryRef with another object for equality.
     * Equality is determined by the composite primary key fields
     * {@code typeCode} and {@code categoryCode}.
     *
     * @param o the object to compare with
     * @return {@code true} if the other object is a TransactionCategoryRef
     *         with the same composite key
     */
    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof TransactionCategoryRef other)) {
            return false;
        }
        return Objects.equals(typeCode, other.typeCode)
                && Objects.equals(categoryCode, other.categoryCode);
    }

    /**
     * Returns the hash code based on the composite primary key fields
     * {@code typeCode} and {@code categoryCode}.
     *
     * @return hash code computed from the composite key
     */
    @Override
    public int hashCode() {
        return Objects.hash(typeCode, categoryCode);
    }

    /**
     * Returns a string representation of this TransactionCategoryRef record,
     * including all mapped fields from the original TRANCATG reference dataset.
     *
     * @return string representation with type code, category code, and description
     */
    @Override
    public String toString() {
        return "TransactionCategoryRef{"
                + "typeCode='" + typeCode + '\''
                + ", categoryCode=" + categoryCode
                + ", categoryDescription='" + categoryDescription + '\''
                + '}';
    }

    // =========================================================================
    // Composite Primary Key Class
    // =========================================================================

    /**
     * Composite primary key class for {@link TransactionCategoryRef}.
     *
     * <p>Implements {@link Serializable} as required by the JPA specification
     * for {@code @IdClass} composite keys. The field names must match exactly
     * the {@code @Id} fields in the entity class.</p>
     *
     * <p>The composite key (tran_type_cd, tran_cat_cd) ensures uniqueness
     * because category codes are reused across different transaction types
     * in the source TRANCATG reference data.</p>
     */
    public static class TransactionCategoryRefId implements Serializable {

        @Serial
        private static final long serialVersionUID = 1L;

        /** Transaction type code — matches entity field {@code typeCode}. */
        private String typeCode;

        /** Transaction category code — matches entity field {@code categoryCode}. */
        private Integer categoryCode;

        /** Default constructor required by JPA. */
        public TransactionCategoryRefId() {
            // Required by JPA specification
        }

        /**
         * Constructs a composite key with both key components.
         *
         * @param typeCode     the 2-character transaction type code
         * @param categoryCode the category code as Integer
         */
        public TransactionCategoryRefId(String typeCode, Integer categoryCode) {
            this.typeCode = typeCode;
            this.categoryCode = categoryCode;
        }

        /** Returns the transaction type code. */
        public String getTypeCode() {
            return typeCode;
        }

        /** Sets the transaction type code. */
        public void setTypeCode(String typeCode) {
            this.typeCode = typeCode;
        }

        /** Returns the transaction category code. */
        public Integer getCategoryCode() {
            return categoryCode;
        }

        /** Sets the transaction category code. */
        public void setCategoryCode(Integer categoryCode) {
            this.categoryCode = categoryCode;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) {
                return true;
            }
            if (!(o instanceof TransactionCategoryRefId other)) {
                return false;
            }
            return Objects.equals(typeCode, other.typeCode)
                    && Objects.equals(categoryCode, other.categoryCode);
        }

        @Override
        public int hashCode() {
            return Objects.hash(typeCode, categoryCode);
        }

        @Override
        public String toString() {
            return "TransactionCategoryRefId{"
                    + "typeCode='" + typeCode + '\''
                    + ", categoryCode=" + categoryCode
                    + '}';
        }
    }
}
