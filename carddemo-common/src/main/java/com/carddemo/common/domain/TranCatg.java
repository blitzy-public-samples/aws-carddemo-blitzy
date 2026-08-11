package com.carddemo.common.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;
import java.util.Objects;

/**
 * JPA entity for the transaction-category reference table (``tran_category``).
 *
 * Maps the legacy COBOL ``TRAN-CAT-RECORD`` copybook (``CVTRA04Y``, RECLN 60)
 * onto a relational reference entity keyed by the composite ``TRAN-CAT-KEY``
 * (transaction-type code paired with transaction-category code). The trailing
 * COBOL ``FILLER`` is intentionally not persisted. The compound identifier is
 * bound through ``@IdClass(TranCatgId.class)``.
 *
 * :field tranTypeCd: two-character transaction-type code, key part (``TRAN-TYPE-CD`` PIC X(02)).
 * :field tranCatCd: four-digit transaction-category code, key part (``TRAN-CAT-CD`` PIC 9(04)).
 * :field tranCatTypeDesc: description of the transaction category (``TRAN-CAT-TYPE-DESC`` PIC X(50)).
 */
@Entity
@Table(name = "tran_category")
@IdClass(TranCatgId.class)
public class TranCatg {

    /**
     * Two-character transaction-type code; first component of the composite key.
     *
     * :output: the persisted ``tran_type_cd`` value (for example ``"01"``).
     */
    @Id
    @Column(name = "tran_type_cd", length = 2, nullable = false)
    private String tranTypeCd;

    /**
     * Four-digit transaction-category code; second component of the composite key.
     *
     * :output: the persisted ``tran_cat_cd`` value (for example ``1``).
     */
    @Id
    @Column(name = "tran_cat_cd", nullable = false)
    private Integer tranCatCd;

    /**
     * Human-readable description of the transaction category.
     *
     * :output: the persisted ``tran_cat_type_desc`` text.
     */
    @Column(name = "tran_cat_type_desc", length = 50)
    private String tranCatTypeDesc;

    /**
     * Public no-argument constructor required by the JPA provider.
     */
    public TranCatg() {
        // Required by JPA for entity instantiation.
    }

    /**
     * Convenience constructor that populates every mapped field.
     *
     * :param tranTypeCd: two-character transaction-type code (key part).
     * :param tranCatCd: four-digit transaction-category code (key part).
     * :param tranCatTypeDesc: description of the transaction category.
     */
    public TranCatg(String tranTypeCd, Integer tranCatCd, String tranCatTypeDesc) {
        this.tranTypeCd = tranTypeCd;
        this.tranCatCd = tranCatCd;
        this.tranCatTypeDesc = tranCatTypeDesc;
    }

    /**
     * Return the transaction-type code.
     *
     * :output: the ``tran_type_cd`` key component.
     */
    public String getTranTypeCd() {
        return tranTypeCd;
    }

    /**
     * Set the transaction-type code.
     *
     * :param tranTypeCd: two-character transaction-type code (key part).
     */
    public void setTranTypeCd(String tranTypeCd) {
        this.tranTypeCd = tranTypeCd;
    }

    /**
     * Return the transaction-category code.
     *
     * :output: the ``tran_cat_cd`` key component.
     */
    public Integer getTranCatCd() {
        return tranCatCd;
    }

    /**
     * Set the transaction-category code.
     *
     * :param tranCatCd: four-digit transaction-category code (key part).
     */
    public void setTranCatCd(Integer tranCatCd) {
        this.tranCatCd = tranCatCd;
    }

    /**
     * Return the transaction-category description.
     *
     * :output: the ``tran_cat_type_desc`` value.
     */
    public String getTranCatTypeDesc() {
        return tranCatTypeDesc;
    }

    /**
     * Set the transaction-category description.
     *
     * :param tranCatTypeDesc: description of the transaction category.
     */
    public void setTranCatTypeDesc(String tranCatTypeDesc) {
        this.tranCatTypeDesc = tranCatTypeDesc;
    }

    /**
     * Compare two transaction categories on their composite key components.
     *
     * :param o: the object to compare with this entity.
     * :output: ``true`` when the other object is a {@code TranCatg} whose
     *     ``tranTypeCd`` and ``tranCatCd`` key components are both equal. The
     *     comparison uses the accessors so that Hibernate lazy proxies are
     *     resolved correctly.
     */
    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof TranCatg other)) {
            return false;
        }
        return Objects.equals(tranTypeCd, other.getTranTypeCd())
                && Objects.equals(tranCatCd, other.getTranCatCd());
    }

    /**
     * Derive the hash code from the composite key components.
     *
     * :output: a hash code over ``tranTypeCd`` and ``tranCatCd`` consistent with
     *     {@link #equals(Object)}.
     */
    @Override
    public int hashCode() {
        return Objects.hash(tranTypeCd, tranCatCd);
    }

    /**
     * Render a diagnostic representation of this entity.
     *
     * :output: a string containing both key components and the description.
     */
    @Override
    public String toString() {
        return "TranCatg{tranTypeCd='" + tranTypeCd + "', tranCatCd=" + tranCatCd
                + ", tranCatTypeDesc='" + tranCatTypeDesc + "'}";
    }
}
