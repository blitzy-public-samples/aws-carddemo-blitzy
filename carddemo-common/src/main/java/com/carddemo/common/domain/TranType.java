package com.carddemo.common.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * JPA entity for the transaction-type reference table (``tran_type``).
 *
 * Maps the legacy COBOL ``TRAN-TYPE-RECORD`` copybook (``CVTRA03Y``, RECLN 60)
 * onto a single-column-key relational reference entity. Each row associates a
 * two-character transaction-type code with its human-readable description; the
 * trailing COBOL ``FILLER`` is intentionally not persisted.
 *
 * :field tranType: two-character transaction-type code, primary key (``TRAN-TYPE`` PIC X(02)).
 * :field tranTypeDesc: description of the transaction type (``TRAN-TYPE-DESC`` PIC X(50)).
 */
@Entity
@Table(name = "tran_type")
public class TranType {

    /**
     * Two-character transaction-type code and primary key.
     *
     * :output: the persisted ``tran_type`` code (for example ``"01"``).
     */
    @Id
    @Column(name = "tran_type", length = 2, nullable = false)
    private String tranType;

    /**
     * Human-readable description of the transaction type.
     *
     * :output: the persisted ``tran_type_desc`` text (for example ``"Purchase"``).
     */
    @Column(name = "tran_type_desc", length = 50)
    private String tranTypeDesc;

    /**
     * Public no-argument constructor required by the JPA provider.
     */
    public TranType() {
        // Required by JPA for entity instantiation.
    }

    /**
     * Convenience constructor that populates every mapped field.
     *
     * :param tranType: two-character transaction-type code (primary key).
     * :param tranTypeDesc: description of the transaction type.
     */
    public TranType(String tranType, String tranTypeDesc) {
        this.tranType = tranType;
        this.tranTypeDesc = tranTypeDesc;
    }

    /**
     * Return the two-character transaction-type code.
     *
     * :output: the primary-key ``tran_type`` value.
     */
    public String getTranType() {
        return tranType;
    }

    /**
     * Set the two-character transaction-type code.
     *
     * :param tranType: two-character transaction-type code (primary key).
     */
    public void setTranType(String tranType) {
        this.tranType = tranType;
    }

    /**
     * Return the transaction-type description.
     *
     * :output: the ``tran_type_desc`` value.
     */
    public String getTranTypeDesc() {
        return tranTypeDesc;
    }

    /**
     * Set the transaction-type description.
     *
     * :param tranTypeDesc: description of the transaction type.
     */
    public void setTranTypeDesc(String tranTypeDesc) {
        this.tranTypeDesc = tranTypeDesc;
    }

    /**
     * Compare two transaction types by their primary-key code.
     *
     * :param o: the object to compare with this entity.
     * :output: ``true`` when the other object is a {@code TranType} with an equal,
     *     non-null ``tranType`` code. The comparison uses the accessor rather than
     *     direct field access so that Hibernate lazy proxies are compared correctly.
     */
    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof TranType other)) {
            return false;
        }
        return tranType != null && tranType.equals(other.getTranType());
    }

    /**
     * Compute a proxy-stable hash code for this entity.
     *
     * :output: a constant hash code derived from the entity class so that the value
     *     never changes across the entity lifecycle (including before the primary key
     *     is assigned), consistent with {@link #equals(Object)}.
     */
    @Override
    public int hashCode() {
        return TranType.class.hashCode();
    }

    /**
     * Render a diagnostic representation of this entity.
     *
     * :output: a string containing the ``tranType`` code and its description.
     */
    @Override
    public String toString() {
        return "TranType{tranType='" + tranType + "', tranTypeDesc='" + tranTypeDesc + "'}";
    }
}
