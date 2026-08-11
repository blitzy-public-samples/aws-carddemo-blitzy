package com.carddemo.common.domain;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.ForeignKey;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

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
@Table(name = "card_xref", indexes = {
        @Index(name = "idx_card_xref_cust_id", columnList = "xref_cust_id"),
        @Index(name = "idx_card_xref_acct_id", columnList = "xref_acct_id")
})
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
     * Read-only association to the owning customer over the same
     * ``xref_cust_id`` column; declares the ``fk_card_xref_customer`` foreign
     * key without duplicating the scalar mapping.
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "xref_cust_id", referencedColumnName = "cust_id",
            insertable = false, updatable = false,
            foreignKey = @ForeignKey(name = "fk_card_xref_customer"))
    @JsonIgnore
    private Customer customer;

    /**
     * Read-only association to the owning account over the same
     * ``xref_acct_id`` column; declares the ``fk_card_xref_account`` foreign
     * key without duplicating the scalar mapping.
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "xref_acct_id", referencedColumnName = "acct_id",
            insertable = false, updatable = false,
            foreignKey = @ForeignKey(name = "fk_card_xref_account"))
    @JsonIgnore
    private Account account;

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
     * :purpose: Read ``xrefCardNum``.
     * :returns: the 16-character card number (primary key).
     */
    public String getXrefCardNum() {
        return xrefCardNum;
    }

    /**
     * :purpose: Set ``xrefCardNum``.
     * :param xrefCardNum: the 16-character card number to set.
     */
    public void setXrefCardNum(String xrefCardNum) {
        this.xrefCardNum = xrefCardNum;
    }

    /**
     * :purpose: Read ``xrefCustId``.
     * :returns: the owning customer identifier.
     */
    public Long getXrefCustId() {
        return xrefCustId;
    }

    /**
     * :purpose: Set ``xrefCustId``.
     * :param xrefCustId: the owning customer identifier to set.
     */
    public void setXrefCustId(Long xrefCustId) {
        this.xrefCustId = xrefCustId;
    }

    /**
     * :purpose: Read ``xrefAcctId``.
     * :returns: the owning account identifier.
     */
    public Long getXrefAcctId() {
        return xrefAcctId;
    }

    /**
     * :purpose: Set ``xrefAcctId``.
     * :param xrefAcctId: the owning account identifier to set.
     */
    public void setXrefAcctId(Long xrefAcctId) {
        this.xrefAcctId = xrefAcctId;
    }

    /**
     * :purpose: Read ``customer``.
     * :returns: the read-only owning-customer association, or ``null`` when not
     *     loaded.
     */
    public Customer getCustomer() {
        return customer;
    }

    /**
     * :purpose: Read ``account``.
     * :returns: the read-only owning-account association, or ``null`` when not
     *     loaded.
     */
    public Account getAccount() {
        return account;
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
        if (!(o instanceof CardXref other)) {
            return false;
        }
        return xrefCardNum != null && xrefCardNum.equals(other.getXrefCardNum());
    }

    /**
     * :purpose: Hash consistent with :java:meth:`equals`.
     * :returns: a proxy-stable hash code consistent with {@link #equals(Object)}.
     */
    @Override
    public int hashCode() {
        return CardXref.class.hashCode();
    }

    /**
     * :purpose: Diagnostic rendering that never discloses unmasked PII.
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
     * :purpose: Render the card number with only its last four digits visible.
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
