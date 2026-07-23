package com.carddemo.common.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.util.Objects;

/**
 * Card cross-reference JPA entity.
 *
 * Maps the legacy COBOL ``CARD-XREF-RECORD`` (copybook ``CVACT03Y``, RECLN 50) to the
 * ``card_xref`` relational table, persisting the card-to-customer-to-account linkage that
 * anchors referential integrity across the application. The card number is the primary key
 * (the legacy VSAM ``XREFFILE`` is keyed on card number); ``xrefAcctId`` carries a secondary
 * index that reproduces the legacy ``CXACAIX`` alternate index and enables account-scoped
 * browses. The customer and account foreign keys are held as plain scalar identifiers so the
 * record mirrors the source copybook; the corresponding database foreign-key constraints and
 * service-layer validation enforce referential integrity. The trailing ``FILLER`` field of the
 * source layout is intentionally not mapped.
 */
@Entity
@Table(name = "card_xref")
public class CardXref {

    /**
     * Card number and primary key, mapped from ``XREF-CARD-NUM PIC X(16)``.
     */
    @Id
    @Column(name = "xref_card_num", length = 16, nullable = false)
    private String xrefCardNum;

    /**
     * Owning customer identifier, mapped from ``XREF-CUST-ID PIC 9(09)``; scalar foreign key to
     * ``customers.cust_id``.
     */
    @Column(name = "xref_cust_id", nullable = false)
    private Long xrefCustId;

    /**
     * Owning account identifier, mapped from ``XREF-ACCT-ID PIC 9(11)``; scalar foreign key to
     * ``accounts.acct_id`` and the column backing the ``CXACAIX`` alternate index.
     */
    @Column(name = "xref_acct_id", nullable = false)
    private Long xrefAcctId;

    /**
     * Default no-argument constructor required by the JPA provider.
     */
    public CardXref() {
    }

    /**
     * Create a fully populated cross-reference.
     *
     * :param xrefCardNum: 16-character card number (primary key).
     * :param xrefCustId: owning customer identifier.
     * :param xrefAcctId: owning account identifier.
     */
    public CardXref(String xrefCardNum, Long xrefCustId, Long xrefAcctId) {
        this.xrefCardNum = xrefCardNum;
        this.xrefCustId = xrefCustId;
        this.xrefAcctId = xrefAcctId;
    }

    /**
     * :returns: the 16-character card number (primary key).
     */
    public String getXrefCardNum() {
        return xrefCardNum;
    }

    /**
     * :param xrefCardNum: the 16-character card number to set.
     */
    public void setXrefCardNum(String xrefCardNum) {
        this.xrefCardNum = xrefCardNum;
    }

    /**
     * :returns: the owning customer identifier.
     */
    public Long getXrefCustId() {
        return xrefCustId;
    }

    /**
     * :param xrefCustId: the owning customer identifier to set.
     */
    public void setXrefCustId(Long xrefCustId) {
        this.xrefCustId = xrefCustId;
    }

    /**
     * :returns: the owning account identifier.
     */
    public Long getXrefAcctId() {
        return xrefAcctId;
    }

    /**
     * :param xrefAcctId: the owning account identifier to set.
     */
    public void setXrefAcctId(Long xrefAcctId) {
        this.xrefAcctId = xrefAcctId;
    }

    /**
     * Compare two cross-references by their primary key.
     *
     * :param o: the object to compare with this instance.
     * :returns: ``true`` when ``o`` is a ``CardXref`` with an equal card number.
     */
    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof CardXref that)) {
            return false;
        }
        return Objects.equals(xrefCardNum, that.xrefCardNum);
    }

    /**
     * :returns: a hash code derived from the card-number primary key.
     */
    @Override
    public int hashCode() {
        return Objects.hash(xrefCardNum);
    }

    /**
     * :returns: a string representation with the card number masked to its last four digits so
     *     the full primary account number is never logged.
     */
    @Override
    public String toString() {
        return "CardXref{xrefCardNum='" + maskCardNumber()
                + "', xrefCustId=" + xrefCustId
                + ", xrefAcctId=" + xrefAcctId
                + '}';
    }

    /**
     * :returns: the card number with every digit except the final four replaced by ``*``; the
     *     literal ``null`` when the card number is unset.
     */
    private String maskCardNumber() {
        if (xrefCardNum == null) {
            return "null";
        }
        int length = xrefCardNum.length();
        if (length <= 4) {
            return "*".repeat(length);
        }
        return "*".repeat(length - 4) + xrefCardNum.substring(length - 4);
    }
}
