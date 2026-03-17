package com.cardemo.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.util.Objects;

/**
 * JPA entity representing a transaction type reference record.
 *
 * <p>Maps the VSAM TRANTYPE reference dataset to the PostgreSQL
 * {@code transaction_type_ref} table. The original COBOL source data
 * resides in {@code app/data/ASCII/trantype.txt} — 7 fixed-width records
 * with a 2-byte type code primary key and a 50-character description field.</p>
 *
 * <h3>Source Data Layout (trantype.txt):</h3>
 * <pre>
 *   Positions [0:2]   — 2-digit type code (PK): "01" through "07"
 *   Positions [2:52]  — Description (right-padded with spaces, 50 chars)
 *   Positions [52:60] — Filler "00000000"
 * </pre>
 *
 * <h3>Reference Records:</h3>
 * <ul>
 *   <li>01 — Purchase</li>
 *   <li>02 — Payment</li>
 *   <li>03 — Credit</li>
 *   <li>04 — Authorization</li>
 *   <li>05 — Refund</li>
 *   <li>06 — Reversal</li>
 *   <li>07 — Adjustment</li>
 * </ul>
 *
 * <p>COBOL equivalent: TRAN-TYPE PIC X(02) as the primary key.</p>
 */
@Entity
@Table(name = "transaction_type_ref")
public class TransactionTypeRef {

    /**
     * Two-character transaction type code serving as the primary key.
     * Values: "01" through "07", matching the COBOL TRAN-TYPE PIC X(02).
     */
    @Id
    @Column(name = "type_code", length = 2, nullable = false)
    private String typeCode;

    /**
     * Human-readable description of the transaction type.
     * Trimmed from the 50-character space-padded source field.
     * Examples: "Purchase", "Payment", "Credit", "Authorization",
     * "Refund", "Reversal", "Adjustment".
     */
    @Column(name = "type_description", length = 50, nullable = false)
    private String typeDescription;

    /**
     * Default no-argument constructor required by JPA specification.
     * Not intended for direct use by application code.
     */
    protected TransactionTypeRef() {
        // Required by JPA
    }

    /**
     * Constructs a new {@code TransactionTypeRef} with the specified type code
     * and description.
     *
     * @param typeCode        the 2-character transaction type code (e.g., "01")
     * @param typeDescription the human-readable description (e.g., "Purchase")
     */
    public TransactionTypeRef(String typeCode, String typeDescription) {
        this.typeCode = typeCode;
        this.typeDescription = typeDescription;
    }

    /**
     * Returns the 2-character transaction type code.
     *
     * @return the type code (e.g., "01" for Purchase)
     */
    public String getTypeCode() {
        return typeCode;
    }

    /**
     * Sets the 2-character transaction type code.
     *
     * @param typeCode the type code to set (e.g., "01")
     */
    public void setTypeCode(String typeCode) {
        this.typeCode = typeCode;
    }

    /**
     * Returns the human-readable description of this transaction type.
     *
     * @return the type description (e.g., "Purchase")
     */
    public String getTypeDescription() {
        return typeDescription;
    }

    /**
     * Sets the human-readable description of this transaction type.
     *
     * @param typeDescription the description to set (e.g., "Purchase")
     */
    public void setTypeDescription(String typeDescription) {
        this.typeDescription = typeDescription;
    }

    /**
     * Compares this entity with another object for equality based on
     * the {@code typeCode} primary key field.
     *
     * <p>Follows JPA entity best practices: two {@code TransactionTypeRef}
     * instances are considered equal if and only if they share the same
     * non-null {@code typeCode} value.</p>
     *
     * @param o the object to compare with
     * @return {@code true} if the objects have equal type codes
     */
    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof TransactionTypeRef other)) {
            return false;
        }
        return Objects.equals(typeCode, other.typeCode);
    }

    /**
     * Returns a hash code based on the {@code typeCode} primary key field.
     *
     * @return hash code consistent with {@link #equals(Object)}
     */
    @Override
    public int hashCode() {
        return Objects.hash(typeCode);
    }

    /**
     * Returns a string representation of this transaction type reference,
     * including both the type code and description.
     *
     * @return a descriptive string for logging and debugging
     */
    @Override
    public String toString() {
        return "TransactionTypeRef{"
                + "typeCode='" + typeCode + '\''
                + ", typeDescription='" + typeDescription + '\''
                + '}';
    }
}
