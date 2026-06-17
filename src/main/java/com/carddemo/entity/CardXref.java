package com.carddemo.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.util.Objects;

/**
 * JPA entity mapping the legacy VSAM {@code CARDXREF} Key-Sequenced Data Set (KSDS)
 * to the relational table {@code card_xref}.
 *
 * <p>This entity is the canonical <strong>card&nbsp;&rarr;&nbsp;customer&nbsp;&rarr;&nbsp;account</strong>
 * cross-reference. In the mainframe application it is consulted during daily transaction
 * posting (the {@code CBTRN02C} XREF lookup keyed by card number) and during account/card
 * browsing flows. The single-byte-for-byte source of truth is the COBOL copybook
 * {@code app/cpy/CVACT03Y.cpy} ({@code CARD-XREF-RECORD}, record length 50):</p>
 *
 * <pre>
 * 01 CARD-XREF-RECORD.
 *     05  XREF-CARD-NUM   PIC X(16).   &rarr; xrefCardNum  (xref_card_num VARCHAR(16), &#64;Id, FK&rarr;cards)
 *     05  XREF-CUST-ID    PIC 9(09).   &rarr; xrefCustId   (xref_cust_id  BIGINT NOT NULL, FK&rarr;customers)
 *     05  XREF-ACCT-ID    PIC 9(11).   &rarr; xrefAcctId   (xref_acct_id  BIGINT NOT NULL, FK&rarr;accounts)
 *     05  FILLER          PIC X(14).   &rarr; (slack bytes; intentionally NOT persisted)
 * </pre>
 *
 * <p><strong>Binding schema contract.</strong> Hibernate runs with
 * {@code spring.jpa.hibernate.ddl-auto=validate}; therefore the mapping below MUST match the
 * authoritative DDL declared in {@code src/main/resources/db/migration/V1__schema.sql} exactly:</p>
 *
 * <pre>
 * CREATE TABLE card_xref (
 *     xref_card_num VARCHAR(16) NOT NULL,
 *     xref_cust_id  BIGINT      NOT NULL,
 *     xref_acct_id  BIGINT      NOT NULL,
 *     CONSTRAINT pk_card_xref PRIMARY KEY (xref_card_num),
 *     CONSTRAINT fk_xref_card FOREIGN KEY (xref_card_num) REFERENCES cards (card_num),
 *     CONSTRAINT fk_xref_cust FOREIGN KEY (xref_cust_id)  REFERENCES customers (cust_id),
 *     CONSTRAINT fk_xref_acct FOREIGN KEY (xref_acct_id)  REFERENCES accounts (acct_id)
 * );
 * -- secondary index backing CardXrefRepository.findByXrefAcctId(...)
 * CREATE INDEX idx_cardxref_acct_id ON card_xref (xref_acct_id);
 * </pre>
 *
 * <p><strong>Design notes.</strong></p>
 * <ul>
 *   <li><em>Flat, scalar foreign keys.</em> The three relationships ({@code cards},
 *       {@code customers}, {@code accounts}) are intentionally modeled as plain scalar
 *       identifier fields rather than JPA {@code @ManyToOne} associations. Referential
 *       integrity is enforced declaratively by the database foreign keys defined in V1.
 *       This mirrors the flat VSAM record and keeps the cross-reference lookup
 *       ({@code findById} / {@code findByXrefCardNum} and {@code findByXrefAcctId}) cheap
 *       and free of lazy-loading surprises during batch posting.</li>
 *   <li><em>Assigned identifier.</em> {@code xrefCardNum} is the natural primary key copied
 *       verbatim from the source record; there is no {@code @GeneratedValue} (the value is
 *       supplied by the cross-reference seed / card provisioning, never auto-generated).</li>
 *   <li><em>Identifier widths.</em> {@code XREF-CUST-ID PIC 9(09)} and
 *       {@code XREF-ACCT-ID PIC 9(11)} are purely numeric keys and map to {@code Long} /
 *       {@code BIGINT}, consistent with {@code Customer.custId} and {@code Account.acctId}.</li>
 *   <li><em>No sensitive data.</em> All three columns are non-sensitive identifiers, so
 *       {@link #toString()} may safely include every field (no CVV/SSN/PII here).</li>
 * </ul>
 *
 * @see <a href="file:app/cpy/CVACT03Y.cpy">app/cpy/CVACT03Y.cpy (CARD-XREF-RECORD)</a>
 */
@Entity
@Table(name = "card_xref")
public class CardXref {

    /**
     * Cross-reference primary key &mdash; the 16-character card number.
     * <p>Source: {@code XREF-CARD-NUM PIC X(16)}. Column {@code xref_card_num VARCHAR(16)},
     * the table primary key and a foreign key onto {@code cards(card_num)}. The value is
     * assigned (never generated).</p>
     */
    @Id
    @Column(name = "xref_card_num", length = 16)
    private String xrefCardNum;

    /**
     * Owning customer identifier (scalar foreign key onto {@code customers(cust_id)}).
     * <p>Source: {@code XREF-CUST-ID PIC 9(09)}. Column {@code xref_cust_id BIGINT NOT NULL}.</p>
     */
    @Column(name = "xref_cust_id", nullable = false)
    private Long xrefCustId;

    /**
     * Owning account identifier (scalar foreign key onto {@code accounts(acct_id)}).
     * <p>Source: {@code XREF-ACCT-ID PIC 9(11)}. Column {@code xref_acct_id BIGINT NOT NULL}.
     * Backed by index {@code idx_cardxref_acct_id} and queried by
     * {@code CardXrefRepository.findByXrefAcctId(...)} for account-scoped card browsing.</p>
     */
    @Column(name = "xref_acct_id", nullable = false)
    private Long xrefAcctId;

    /**
     * No-argument constructor required by the JPA specification / Hibernate.
     */
    public CardXref() {
        // Required by JPA; fields populated by the persistence provider.
    }

    /**
     * All-arguments constructor for convenient programmatic and test construction.
     *
     * @param xrefCardNum the 16-character card number (primary key)
     * @param xrefCustId  the owning customer identifier
     * @param xrefAcctId  the owning account identifier
     */
    public CardXref(String xrefCardNum, Long xrefCustId, Long xrefAcctId) {
        this.xrefCardNum = xrefCardNum;
        this.xrefCustId = xrefCustId;
        this.xrefAcctId = xrefAcctId;
    }

    /**
     * @return the 16-character card number (primary key)
     */
    public String getXrefCardNum() {
        return xrefCardNum;
    }

    /**
     * @param xrefCardNum the 16-character card number (primary key)
     */
    public void setXrefCardNum(String xrefCardNum) {
        this.xrefCardNum = xrefCardNum;
    }

    /**
     * @return the owning customer identifier
     */
    public Long getXrefCustId() {
        return xrefCustId;
    }

    /**
     * @param xrefCustId the owning customer identifier
     */
    public void setXrefCustId(Long xrefCustId) {
        this.xrefCustId = xrefCustId;
    }

    /**
     * @return the owning account identifier
     */
    public Long getXrefAcctId() {
        return xrefAcctId;
    }

    /**
     * @param xrefAcctId the owning account identifier
     */
    public void setXrefAcctId(Long xrefAcctId) {
        this.xrefAcctId = xrefAcctId;
    }

    /**
     * Equality is based on the primary key {@code xrefCardNum}, which is the stable,
     * assigned natural identifier of the cross-reference record.
     *
     * @param o the object to compare with
     * @return {@code true} if {@code o} is a {@code CardXref} with the same {@code xrefCardNum}
     */
    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }
        CardXref that = (CardXref) o;
        return Objects.equals(xrefCardNum, that.xrefCardNum);
    }

    /**
     * Hash code derived from the primary key {@code xrefCardNum}, consistent with
     * {@link #equals(Object)}.
     *
     * @return the hash code for this cross-reference
     */
    @Override
    public int hashCode() {
        return Objects.hashCode(xrefCardNum);
    }

    /**
     * Human-readable representation including all three identifier fields. None of these
     * fields is sensitive (no CVV, SSN, or other PII), so all may be safely emitted.
     *
     * @return a string describing this cross-reference
     */
    @Override
    public String toString() {
        return "CardXref{" +
                "xrefCardNum='" + xrefCardNum + '\'' +
                ", xrefCustId=" + xrefCustId +
                ", xrefAcctId=" + xrefAcctId +
                '}';
    }
}
