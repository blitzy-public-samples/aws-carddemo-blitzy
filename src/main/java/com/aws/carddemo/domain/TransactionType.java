package com.aws.carddemo.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * JPA entity mapping the AWS CardDemo transaction-type reference table.
 *
 * <p>This entity is a one-for-one migration of the legacy VSAM {@code TRANTYPE}
 * reference file. It preserves the record layout and key semantics of the COBOL
 * {@code TRAN-TYPE-RECORD} structure (fixed record length 60 bytes): a two-character
 * transaction-type code and its fifty-character description. It is a small, static
 * reference/lookup table used to describe the type of a transaction.</p>
 *
 * <p>The trailing eight-byte {@code FILLER} in the copybook carried no business meaning
 * and is intentionally not persisted as a column. The two-character {@code TRAN-TYPE}
 * code is the primary key; other tables reference this code as a plain scalar value, so
 * the reference table is deliberately decoupled and declares no JPA associations.</p>
 *
 * <p>Origin: legacy/cpy/CVTRA03Y.cpy (TRAN-TYPE-RECORD, RECLN 60)</p>
 */
@Entity
@Table(name = "transaction_type")
public class TransactionType {

    /**
     * Transaction-type code and primary key.
     *
     * <p>Maps COBOL {@code TRAN-TYPE PIC X(02)}; column {@code tran_type} (2 characters).</p>
     */
    @Id
    @Column(name = "tran_type", length = 2)
    private String tranType;

    /**
     * Human-readable description of the transaction type.
     *
     * <p>Maps COBOL {@code TRAN-TYPE-DESC PIC X(50)}; column {@code tran_type_desc}
     * (50 characters).</p>
     */
    @Column(name = "tran_type_desc", length = 50)
    private String tranTypeDesc;

    /**
     * Creates an empty {@code TransactionType}.
     *
     * <p>Required no-argument constructor for JPA / Hibernate entity instantiation.</p>
     */
    public TransactionType() {
        // No-arg constructor required by the JPA specification.
    }

    /**
     * Returns the two-character transaction-type code (primary key).
     *
     * @return the transaction-type code
     */
    public String getTranType() {
        return tranType;
    }

    /**
     * Sets the two-character transaction-type code (primary key).
     *
     * @param tranType the transaction-type code
     */
    public void setTranType(String tranType) {
        this.tranType = tranType;
    }

    /**
     * Returns the transaction-type description.
     *
     * @return the description text
     */
    public String getTranTypeDesc() {
        return tranTypeDesc;
    }

    /**
     * Sets the transaction-type description.
     *
     * @param tranTypeDesc the description text
     */
    public void setTranTypeDesc(String tranTypeDesc) {
        this.tranTypeDesc = tranTypeDesc;
    }

    /**
     * Compares this entity with another for equality based solely on the non-null primary key
     * {@code tranType}. Uses an {@code instanceof} check so a Hibernate proxy compares equal to its
     * underlying entity, and treats an instance with a {@code null} id as not equal to any other
     * instance (including other unsaved instances), so distinct transient rows are never collapsed
     * (review finding F10).
     *
     * @param o the object to compare with
     * @return {@code true} if {@code o} is a {@code TransactionType} with an equal non-null
     *         transaction-type code
     */
    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof TransactionType that)) {
            return false;
        }
        return tranType != null && tranType.equals(that.tranType);
    }

    /**
     * Returns a constant, identity-stable hash code. A constant (rather than one derived from
     * {@code tranType}) is used so the hash does not change when the primary key is assigned, keeping
     * instances locatable in hash-based collections and consistent with {@link #equals(Object)}
     * (review finding F10).
     *
     * @return a stable, class-level hash code
     */
    @Override
    public int hashCode() {
        return TransactionType.class.hashCode();
    }

    /**
     * Returns a string representation containing both mapped fields.
     *
     * @return a string with the transaction-type code and description
     */
    @Override
    public String toString() {
        return "TransactionType{"
                + "tranType='" + tranType + '\''
                + ", tranTypeDesc='" + tranTypeDesc + '\''
                + '}';
    }
}
