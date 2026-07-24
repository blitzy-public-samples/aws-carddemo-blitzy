package com.carddemo.common.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.util.Objects;

/**
 * JPA entity for the CardDemo transaction-category-balance record.
 *
 * :purpose: Maps the legacy COBOL ``TRAN-CAT-BAL-RECORD`` layout (copybook
 *     ``CVTRA01Y``, fixed length 50 bytes) onto the ``tran_cat_bal`` relational
 *     table, preserving the legacy field names and monetary scale. The
 *     composite key mirrors the COBOL ``TRAN-CAT-KEY`` group (account id,
 *     transaction type code and transaction category code) through
 *     {@link TranCatBalId}. The trailing COBOL ``FILLER`` is intentionally not
 *     persisted.
 * :output: A persistent per-account/type/category running balance whose
 *     ``tranCatBal`` amount is the multiplicand of the monthly-interest
 *     computation ``(TRAN-CAT-BAL * DIS-INT-RATE) / 1200``; the amount is held
 *     as a fixed-scale {@link BigDecimal} so migrated financial output remains
 *     byte-identical.
 */
@Entity
@Table(name = "tran_cat_bal")
@IdClass(TranCatBalId.class)
public class TranCatBal {

    /** TRANCAT-ACCT-ID PIC 9(11): account identifier, part of the composite key. */
    @Id
    @Column(name = "trancat_acct_id", nullable = false)
    private Long trancatAcctId;

    /** TRANCAT-TYPE-CD PIC X(02): transaction type code, part of the composite key. */
    @Id
    @Column(name = "trancat_type_cd", length = 2, nullable = false)
    private String trancatTypeCd;

    /** TRANCAT-CD PIC 9(04): transaction category code, part of the composite key. */
    @Id
    @Column(name = "trancat_cd", nullable = false)
    private Integer trancatCd;

    /** TRAN-CAT-BAL PIC S9(09)V99 -> NUMERIC(11,2): the category running balance. */
    @Column(name = "tran_cat_bal", precision = 11, scale = 2)
    private BigDecimal tranCatBal;

    /**
     * Creates an empty transaction-category-balance instance.
     *
     * :purpose: Required no-argument constructor used by the JPA provider when
     *     materializing entities from the database.
     */
    public TranCatBal() {
        // Required by JPA for entity instantiation.
    }

    /**
     * Convenience constructor that populates every mapped field.
     *
     * :param trancatAcctId: account identifier component of the composite key.
     * :param trancatTypeCd: transaction type code component of the composite key.
     * :param trancatCd: transaction category code component of the composite key.
     * :param tranCatBal: the category running balance (scale 2).
     */
    public TranCatBal(Long trancatAcctId, String trancatTypeCd, Integer trancatCd, BigDecimal tranCatBal) {
        this.trancatAcctId = trancatAcctId;
        this.trancatTypeCd = trancatTypeCd;
        this.trancatCd = trancatCd;
        this.tranCatBal = tranCatBal;
    }

    /**
     * :returns: the account identifier component of the composite key.
     */
    public Long getTrancatAcctId() {
        return trancatAcctId;
    }

    /**
     * :param trancatAcctId: the account identifier component to assign.
     */
    public void setTrancatAcctId(Long trancatAcctId) {
        this.trancatAcctId = trancatAcctId;
    }

    /**
     * :returns: the transaction type code component of the composite key.
     */
    public String getTrancatTypeCd() {
        return trancatTypeCd;
    }

    /**
     * :param trancatTypeCd: the transaction type code component to assign.
     */
    public void setTrancatTypeCd(String trancatTypeCd) {
        this.trancatTypeCd = trancatTypeCd;
    }

    /**
     * :returns: the transaction category code component of the composite key.
     */
    public Integer getTrancatCd() {
        return trancatCd;
    }

    /**
     * :param trancatCd: the transaction category code component to assign.
     */
    public void setTrancatCd(Integer trancatCd) {
        this.trancatCd = trancatCd;
    }

    /**
     * :returns: the category running balance as a fixed-scale value.
     */
    public BigDecimal getTranCatBal() {
        return tranCatBal;
    }

    /**
     * :param tranCatBal: the category running balance to assign (scale 2);
     *     stored verbatim without rounding to preserve financial precision.
     */
    public void setTranCatBal(BigDecimal tranCatBal) {
        this.tranCatBal = tranCatBal;
    }

    /**
     * Compare two records by their composite key components.
     *
     * :param o: the object to compare with this entity.
     * :output: ``true`` when the other object is a {@code TranCatBal} with equal
     *     account identifier, transaction type code and transaction category
     *     code (the three fields that form the primary key).
     */
    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof TranCatBal other)) {
            return false;
        }
        return Objects.equals(trancatAcctId, other.trancatAcctId)
                && Objects.equals(trancatTypeCd, other.trancatTypeCd)
                && Objects.equals(trancatCd, other.trancatCd);
    }

    /**
     * :output: a hash code derived from the three composite-key components,
     *     consistent with {@link #equals(Object)}.
     */
    @Override
    public int hashCode() {
        return Objects.hash(trancatAcctId, trancatTypeCd, trancatCd);
    }

    /**
     * Render a diagnostic representation of this entity.
     *
     * :output: a string containing the composite-key components and the
     *     category balance; contains no personally identifiable information.
     */
    @Override
    public String toString() {
        return "TranCatBal{trancatAcctId=" + trancatAcctId
                + ", trancatTypeCd='" + trancatTypeCd + '\''
                + ", trancatCd=" + trancatCd
                + ", tranCatBal=" + tranCatBal + '}';
    }
}
