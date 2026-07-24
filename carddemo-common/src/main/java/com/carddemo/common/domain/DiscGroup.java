package com.carddemo.common.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.util.Objects;

/**
 * JPA entity for the CardDemo disclosure-group record.
 *
 * :purpose: Maps the legacy COBOL ``DIS-GROUP-RECORD`` layout (copybook
 *     ``CVTRA02Y``, fixed length 50 bytes) onto the ``disclosure_group``
 *     relational table, preserving the legacy field names, field widths and the
 *     monetary scale of the interest rate so that the migrated monthly-interest
 *     calculation remains byte-identical. The three-part ``DIS-GROUP-KEY``
 *     (account group id, transaction type code, transaction category code) is the
 *     composite primary key, bound through {@link DiscGroupId} via
 *     {@code @IdClass(DiscGroupId.class)}. The trailing COBOL ``FILLER`` is
 *     intentionally not persisted.
 * :output: A persistent disclosure-group aggregate exposing the three key
 *     components and the disclosure interest rate. The interest rate is stored as
 *     a fixed-scale {@link java.math.BigDecimal} on a ``NUMERIC(6,2)`` column,
 *     supplying the ``DIS-INT-RATE`` operand of the batch interest formula
 *     ``(TRAN-CAT-BAL * DIS-INT-RATE) / 1200`` without rounding or normalization
 *     at the persistence layer.
 */
@Entity
@Table(name = "disclosure_group")
@IdClass(DiscGroupId.class)
public class DiscGroup {

    /** DIS-ACCT-GROUP-ID PIC X(10): account/pricing group id, part of the composite key. */
    @Id
    @Column(name = "dis_acct_group_id", length = 10, nullable = false)
    private String disAcctGroupId;

    /** DIS-TRAN-TYPE-CD PIC X(02): two-character transaction type code, part of the composite key. */
    @Id
    @Column(name = "dis_tran_type_cd", length = 2, nullable = false)
    private String disTranTypeCd;

    /** DIS-TRAN-CAT-CD PIC 9(04): four-digit transaction category code, part of the composite key. */
    @Id
    @Column(name = "dis_tran_cat_cd", nullable = false)
    private Integer disTranCatCd;

    /** DIS-INT-RATE PIC S9(04)V99 -> NUMERIC(6,2): disclosure-group interest rate (scale 2). */
    @Column(name = "dis_int_rate", precision = 6, scale = 2)
    private BigDecimal disIntRate;

    /**
     * Creates an empty disclosure-group instance.
     *
     * :purpose: Required no-argument constructor used by the JPA provider when
     *     materializing entities from the database.
     */
    public DiscGroup() {
    }

    /**
     * Creates a fully populated disclosure-group instance.
     *
     * :param disAcctGroupId: account group id (``DIS-ACCT-GROUP-ID``).
     * :param disTranTypeCd: transaction type code (``DIS-TRAN-TYPE-CD``).
     * :param disTranCatCd: transaction category code (``DIS-TRAN-CAT-CD``).
     * :param disIntRate: disclosure-group interest rate (``DIS-INT-RATE``, scale 2).
     */
    public DiscGroup(String disAcctGroupId, String disTranTypeCd, Integer disTranCatCd,
                     BigDecimal disIntRate) {
        this.disAcctGroupId = disAcctGroupId;
        this.disTranTypeCd = disTranTypeCd;
        this.disTranCatCd = disTranCatCd;
        this.disIntRate = disIntRate;
    }

    /**
     * Return the account group id key component.
     *
     * :output: the ``DIS-ACCT-GROUP-ID`` value.
     */
    public String getDisAcctGroupId() {
        return disAcctGroupId;
    }

    /**
     * Set the account group id key component.
     *
     * :param disAcctGroupId: the ``DIS-ACCT-GROUP-ID`` value.
     */
    public void setDisAcctGroupId(String disAcctGroupId) {
        this.disAcctGroupId = disAcctGroupId;
    }

    /**
     * Return the transaction type code key component.
     *
     * :output: the ``DIS-TRAN-TYPE-CD`` value.
     */
    public String getDisTranTypeCd() {
        return disTranTypeCd;
    }

    /**
     * Set the transaction type code key component.
     *
     * :param disTranTypeCd: the ``DIS-TRAN-TYPE-CD`` value.
     */
    public void setDisTranTypeCd(String disTranTypeCd) {
        this.disTranTypeCd = disTranTypeCd;
    }

    /**
     * Return the transaction category code key component.
     *
     * :output: the ``DIS-TRAN-CAT-CD`` value.
     */
    public Integer getDisTranCatCd() {
        return disTranCatCd;
    }

    /**
     * Set the transaction category code key component.
     *
     * :param disTranCatCd: the ``DIS-TRAN-CAT-CD`` value.
     */
    public void setDisTranCatCd(Integer disTranCatCd) {
        this.disTranCatCd = disTranCatCd;
    }

    /**
     * Return the disclosure-group interest rate.
     *
     * :output: the ``DIS-INT-RATE`` value as a scale-2 {@link java.math.BigDecimal}.
     */
    public BigDecimal getDisIntRate() {
        return disIntRate;
    }

    /**
     * Set the disclosure-group interest rate.
     *
     * :param disIntRate: the ``DIS-INT-RATE`` value; stored faithfully without
     *     rounding or normalization at the persistence layer.
     */
    public void setDisIntRate(BigDecimal disIntRate) {
        this.disIntRate = disIntRate;
    }

    /**
     * Compare two disclosure groups by their composite key components.
     *
     * :param o: the object to compare with this entity.
     * :output: {@code true} when {@code o} is a {@code DiscGroup} whose account group
     *     id, transaction type code, and transaction category code are all equal. The
     *     comparison covers exactly the three key components that form the
     *     {@link DiscGroupId} identity, consistent with {@link #hashCode()}.
     */
    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }
        DiscGroup that = (DiscGroup) o;
        return Objects.equals(disAcctGroupId, that.disAcctGroupId)
                && Objects.equals(disTranTypeCd, that.disTranTypeCd)
                && Objects.equals(disTranCatCd, that.disTranCatCd);
    }

    /**
     * Compute a hash consistent with {@link #equals(Object)}.
     *
     * :output: hash code derived from the account group id, transaction type code, and
     *     transaction category code (the three composite-key components).
     */
    @Override
    public int hashCode() {
        return Objects.hash(disAcctGroupId, disTranTypeCd, disTranCatCd);
    }

    /**
     * Render a diagnostic representation of this entity.
     *
     * :output: a human-readable representation of the three key components and the
     *     interest rate.
     */
    @Override
    public String toString() {
        return "DiscGroup{"
                + "disAcctGroupId='" + disAcctGroupId + '\''
                + ", disTranTypeCd='" + disTranTypeCd + '\''
                + ", disTranCatCd=" + disTranCatCd
                + ", disIntRate=" + disIntRate
                + '}';
    }
}
