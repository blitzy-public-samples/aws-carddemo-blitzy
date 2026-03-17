package com.cardemo.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;

import java.util.Objects;

/**
 * JPA entity representing the card cross-reference junction record.
 *
 * <p>Migrated from COBOL copybook CVACT03Y.cpy (CARD-XREF-RECORD, 50 bytes).
 * This entity maps the VSAM CARDXREF KSDS dataset which serves as a junction
 * table linking credit cards to customers and accounts.</p>
 *
 * <h3>Original COBOL Record Layout (CVACT03Y.cpy):</h3>
 * <pre>
 * 01 CARD-XREF-RECORD.
 *     05  XREF-CARD-NUM     PIC X(16).    → xrefCardNum (PK)
 *     05  XREF-CUST-ID      PIC 9(09).    → custId
 *     05  XREF-ACCT-ID      PIC 9(11).    → accountId (indexed — AIX equivalent)
 *     05  FILLER             PIC X(14).    → not mapped (padding)
 * </pre>
 *
 * <p>The VSAM alternate index (AIX) on XREF-ACCT-ID (position 25, length 11) is
 * mapped to a database index {@code idx_cardxref_acct_id} on the {@code account_id}
 * column, preserving the same lookup capability for account-based card queries.</p>
 *
 * <p>All numeric COBOL fields (PIC 9) are stored as {@link String} to preserve
 * leading zeros, matching the original fixed-width data representation.</p>
 *
 * @see <a href="app/cpy/CVACT03Y.cpy">CVACT03Y.cpy — CARD-XREF-RECORD</a>
 */
@Entity
@Table(
    name = "card_xref",
    indexes = {
        @Index(name = "idx_cardxref_acct_id", columnList = "account_id")
    }
)
public class CardXref {

    /**
     * Cross-reference card number — primary key.
     * Maps to COBOL field XREF-CARD-NUM PIC X(16).
     * This is the VSAM KSDS primary key (16 bytes).
     */
    @Id
    @Column(name = "card_num", length = 16, nullable = false)
    private String xrefCardNum;

    /**
     * Customer identifier linked to this card.
     * Maps to COBOL field XREF-CUST-ID PIC 9(09).
     * Stored as String to preserve leading zeros from the original
     * 9-digit numeric COBOL field.
     */
    @Column(name = "customer_id", length = 9, nullable = false)
    private String custId;

    /**
     * Account identifier linked to this card.
     * Maps to COBOL field XREF-ACCT-ID PIC 9(11).
     * Stored as String to preserve leading zeros from the original
     * 11-digit numeric COBOL field.
     * This column is indexed (idx_cardxref_acct_id) to replicate the
     * VSAM alternate index (AIX) on XREF-ACCT-ID at position 25, length 11.
     */
    @Column(name = "account_id", length = 11, nullable = false)
    private String accountId;

    /**
     * Default no-argument constructor required by JPA.
     * Protected access to discourage direct instantiation outside of
     * the persistence framework.
     */
    protected CardXref() {
        // Required by JPA specification
    }

    /**
     * Constructs a new CardXref with all fields populated.
     *
     * @param xrefCardNum the 16-character card number (XREF-CARD-NUM)
     * @param custId      the 9-digit customer ID (XREF-CUST-ID)
     * @param accountId   the 11-digit account ID (XREF-ACCT-ID)
     */
    public CardXref(String xrefCardNum, String custId, String accountId) {
        this.xrefCardNum = xrefCardNum;
        this.custId = custId;
        this.accountId = accountId;
    }

    /**
     * Returns the cross-reference card number (primary key).
     *
     * @return the 16-character card number (XREF-CARD-NUM PIC X(16))
     */
    public String getXrefCardNum() {
        return xrefCardNum;
    }

    /**
     * Sets the cross-reference card number (primary key).
     *
     * @param xrefCardNum the 16-character card number
     */
    public void setXrefCardNum(String xrefCardNum) {
        this.xrefCardNum = xrefCardNum;
    }

    /**
     * Returns the customer identifier linked to this card.
     *
     * @return the 9-digit customer ID (XREF-CUST-ID PIC 9(09))
     */
    public String getCustId() {
        return custId;
    }

    /**
     * Sets the customer identifier linked to this card.
     *
     * @param custId the 9-digit customer ID
     */
    public void setCustId(String custId) {
        this.custId = custId;
    }

    /**
     * Returns the account identifier linked to this card.
     * This field is indexed to replicate the VSAM AIX on XREF-ACCT-ID.
     *
     * @return the 11-digit account ID (XREF-ACCT-ID PIC 9(11))
     */
    public String getAccountId() {
        return accountId;
    }

    /**
     * Sets the account identifier linked to this card.
     *
     * @param accountId the 11-digit account ID
     */
    public void setAccountId(String accountId) {
        this.accountId = accountId;
    }

    /**
     * Compares this CardXref with another object for equality.
     * Equality is determined solely by the primary key field {@code xrefCardNum}
     * (XREF-CARD-NUM), consistent with JPA entity identity semantics and the
     * VSAM KSDS primary key definition.
     *
     * @param o the object to compare with
     * @return {@code true} if the other object is a CardXref with the same card number
     */
    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof CardXref other)) {
            return false;
        }
        return Objects.equals(xrefCardNum, other.xrefCardNum);
    }

    /**
     * Returns the hash code based on the primary key field {@code xrefCardNum}.
     *
     * @return hash code computed from the card number
     */
    @Override
    public int hashCode() {
        return Objects.hash(xrefCardNum);
    }

    /**
     * Returns a string representation of this CardXref record, including
     * all mapped fields from the original COBOL CARD-XREF-RECORD.
     *
     * @return string representation with card number, customer ID, and account ID
     */
    @Override
    public String toString() {
        return "CardXref{"
                + "xrefCardNum='" + xrefCardNum + '\''
                + ", custId='" + custId + '\''
                + ", accountId='" + accountId + '\''
                + '}';
    }
}
