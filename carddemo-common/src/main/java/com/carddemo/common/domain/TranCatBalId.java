package com.carddemo.common.domain;

import java.io.Serializable;
import java.util.Objects;

/**
 * Composite primary key for the ``TranCatBal`` entity.
 *
 * Models the COBOL ``TRAN-CAT-KEY`` group of ``TRAN-CAT-BAL-RECORD``
 * (copybook ``CVTRA01Y``): the account identifier, the transaction type
 * code, and the transaction category code that together uniquely identify
 * a transaction-category-balance row.
 *
 * Referenced by ``TranCatBal`` through ``@IdClass(TranCatBalId.class)``; the
 * field names and types declared here mirror the entity's ``@Id`` fields
 * exactly so the JPA metamodel resolves the composite key.
 *
 * :output: value-based key object with consistent ``equals``/``hashCode``.
 */
public class TranCatBalId implements Serializable {

    private static final long serialVersionUID = 1L;

    /** Account identifier component (COBOL ``TRANCAT-ACCT-ID`` PIC 9(11)). */
    private Long trancatAcctId;

    /** Transaction type code component (COBOL ``TRANCAT-TYPE-CD`` PIC X(02)). */
    private String trancatTypeCd;

    /** Transaction category code component (COBOL ``TRANCAT-CD`` PIC 9(04)). */
    private Integer trancatCd;

    /**
     * Creates an empty composite key.
     *
     * Required by JPA for ``@IdClass`` instantiation and reflective population.
     */
    public TranCatBalId() {
        // No-argument constructor required by the JPA specification.
    }

    /**
     * Creates a fully populated composite key.
     *
     * :param trancatAcctId: account identifier component
     * :param trancatTypeCd: transaction type code component
     * :param trancatCd: transaction category code component
     */
    public TranCatBalId(Long trancatAcctId, String trancatTypeCd, Integer trancatCd) {
        this.trancatAcctId = trancatAcctId;
        this.trancatTypeCd = trancatTypeCd;
        this.trancatCd = trancatCd;
    }

    /**
     * :purpose: Read ``trancatAcctId``.
     * :return: the account identifier component.
     */
    public Long getTrancatAcctId() {
        return trancatAcctId;
    }

    /**
     * :purpose: Set ``trancatAcctId``.
     * :param trancatAcctId: the account identifier component to set.
     */
    public void setTrancatAcctId(Long trancatAcctId) {
        this.trancatAcctId = trancatAcctId;
    }

    /**
     * :purpose: Read ``trancatTypeCd``.
     * :return: the transaction type code component.
     */
    public String getTrancatTypeCd() {
        return trancatTypeCd;
    }

    /**
     * :purpose: Set ``trancatTypeCd``.
     * :param trancatTypeCd: the transaction type code component to set.
     */
    public void setTrancatTypeCd(String trancatTypeCd) {
        this.trancatTypeCd = trancatTypeCd;
    }

    /**
     * :purpose: Read ``trancatCd``.
     * :return: the transaction category code component.
     */
    public Integer getTrancatCd() {
        return trancatCd;
    }

    /**
     * :purpose: Set ``trancatCd``.
     * :param trancatCd: the transaction category code component to set.
     */
    public void setTrancatCd(Integer trancatCd) {
        this.trancatCd = trancatCd;
    }

    /**
     * Compares this key with another for value equality across all three
     * key components.
     *
     * :param o: the object to compare with.
     * :return: ``true`` when ``o`` is a ``TranCatBalId`` with identical
     *          account identifier, transaction type code, and category code.
     */
    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }
        TranCatBalId that = (TranCatBalId) o;
        return Objects.equals(trancatAcctId, that.trancatAcctId)
                && Objects.equals(trancatTypeCd, that.trancatTypeCd)
                && Objects.equals(trancatCd, that.trancatCd);
    }

    /**
     * :purpose: Hash consistent with :java:meth:`equals`.
     * :return: a hash code derived from all three key components,
     *          consistent with {@link #equals(Object)}.
     */
    @Override
    public int hashCode() {
        return Objects.hash(trancatAcctId, trancatTypeCd, trancatCd);
    }
}
