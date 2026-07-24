package com.carddemo.common.domain;

import java.io.Serializable;
import java.util.Objects;

/**
 * Composite primary-key class for the {@code DiscGroup} disclosure-group entity.
 *
 * :purpose: Mirror the COBOL ``DIS-GROUP-KEY`` group defined in
 *     ``app/cpy/CVTRA02Y.cpy`` (record length 50) so JPA can bind the disclosure
 *     group's three-part identity: account group id, transaction type code, and
 *     transaction category code. Serves as the {@code @IdClass} referenced by
 *     {@code DiscGroup} via {@code @IdClass(DiscGroupId.class)}.
 * :output: A serializable identity value whose field names and types match the
 *     {@code @Id} fields on the {@code DiscGroup} entity, with {@code equals} and
 *     {@code hashCode} computed over all three key components. The non-key fields
 *     ``DIS-INT-RATE`` and ``FILLER`` are intentionally excluded.
 */
public class DiscGroupId implements Serializable {

    private static final long serialVersionUID = 1L;

    /** Account group id; COBOL ``DIS-ACCT-GROUP-ID`` PIC X(10). */
    private String disAcctGroupId;

    /** Transaction type code; COBOL ``DIS-TRAN-TYPE-CD`` PIC X(02). */
    private String disTranTypeCd;

    /** Transaction category code; COBOL ``DIS-TRAN-CAT-CD`` PIC 9(04). */
    private Integer disTranCatCd;

    /**
     * Create an empty identity.
     *
     * :purpose: Satisfy the JPA requirement that an {@code @IdClass} expose a public
     *     no-argument constructor for reflective instantiation.
     */
    public DiscGroupId() {
    }

    /**
     * Create a fully populated identity.
     *
     * :param disAcctGroupId: account group id (``DIS-ACCT-GROUP-ID``).
     * :param disTranTypeCd: transaction type code (``DIS-TRAN-TYPE-CD``).
     * :param disTranCatCd: transaction category code (``DIS-TRAN-CAT-CD``).
     */
    public DiscGroupId(String disAcctGroupId, String disTranTypeCd, Integer disTranCatCd) {
        this.disAcctGroupId = disAcctGroupId;
        this.disTranTypeCd = disTranTypeCd;
        this.disTranCatCd = disTranCatCd;
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
     * Compare identities by all three key components.
     *
     * :param o: object to compare against this identity.
     * :output: {@code true} when {@code o} is a {@code DiscGroupId} whose account group
     *     id, transaction type code, and transaction category code are all equal.
     */
    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }
        DiscGroupId that = (DiscGroupId) o;
        return Objects.equals(disAcctGroupId, that.disAcctGroupId)
                && Objects.equals(disTranTypeCd, that.disTranTypeCd)
                && Objects.equals(disTranCatCd, that.disTranCatCd);
    }

    /**
     * Compute a hash consistent with {@link #equals(Object)}.
     *
     * :output: hash code derived from the account group id, transaction type code, and
     *     transaction category code.
     */
    @Override
    public int hashCode() {
        return Objects.hash(disAcctGroupId, disTranTypeCd, disTranCatCd);
    }

    /**
     * Render the identity for logging and diagnostics.
     *
     * :output: a human-readable representation of the three key components.
     */
    @Override
    public String toString() {
        return "DiscGroupId{"
                + "disAcctGroupId='" + disAcctGroupId + '\''
                + ", disTranTypeCd='" + disTranTypeCd + '\''
                + ", disTranCatCd=" + disTranCatCd
                + '}';
    }
}
