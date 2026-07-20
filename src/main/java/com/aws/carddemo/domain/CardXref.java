/*
 * Copyright Amazon.com, Inc. or its affiliates.
 * All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License").
 * You may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND,
 * either express or implied. See the License for the specific
 * language governing permissions and limitations under the License.
 */
package com.aws.carddemo.domain;

import java.util.Objects;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * JPA entity for the card cross-reference record.
 *
 * <p>Re-platformed from the legacy COBOL copybook {@code CARD-XREF-RECORD}
 * (source {@code legacy/cpy/CVACT03Y.cpy}, formerly {@code app/cpy/CVACT03Y.cpy}),
 * which described the VSAM KSDS {@code CARDXREF.VSAM.KSDS} with a fixed record
 * length of 50 bytes. This entity maps that layout onto the PostgreSQL table
 * {@code card_xref}. The cross-reference links a card number to the customer and
 * account it belongs to, mirroring the lookups the online and batch programs
 * performed against the VSAM primary key and its alternate index.</p>
 *
 * <h2>Copybook-to-table field mapping</h2>
 * <p>The COBOL field order is preserved. The trailing {@code FILLER} is padding
 * that pads the record to its 50-byte length and is intentionally not mapped to a
 * column.</p>
 *
 * <pre>
 *   COBOL field (CVACT03Y.cpy)        PIC          Java field     Column          SQL type
 *   --------------------------------  -----------  -------------  --------------  ----------------
 *   XREF-CARD-NUM (primary key)       X(16)        xrefCardNum    xref_card_num   VARCHAR(16) PK
 *   XREF-CUST-ID  (FK -> customer)    9(09)        custId         cust_id         BIGINT NOT NULL
 *   XREF-ACCT-ID  (FK -> account)     9(11)        acctId         acct_id         BIGINT NOT NULL
 *   FILLER (padding, not a column)    X(14)        -              -               -
 * </pre>
 *
 * <h2>Schema ownership and validation</h2>
 * <p>The relational schema is owned by the Flyway migration {@code V1__schema.sql};
 * Hibernate runs with {@code ddl-auto=validate} and therefore never creates or
 * alters this table. Consequently the table name, column names, types, and lengths
 * declared here must match the migration exactly. The VSAM {@code KEYLEN=16} unique
 * key becomes the primary key {@code xref_card_num}. The VSAM alternate index over
 * {@code XREF-ACCT-ID} ({@code KEYLEN=11}) is formalized as the database index
 * {@code idx_card_xref_acct_id}, and the application-enforced relationships to
 * {@code customer(cust_id)} and {@code account(acct_id)} are formalized as real
 * foreign-key constraints. Both the index and the foreign keys are created by the
 * Flyway migration, not by this entity, so no {@code @Table(indexes = ...)} or
 * association mappings are declared here.</p>
 *
 * <h2>Modeling notes</h2>
 * <ul>
 *   <li>{@code custId} and {@code acctId} are kept as plain scalar {@code Long}
 *       foreign-key columns rather than {@code @ManyToOne} associations, following
 *       the project-wide scalar-foreign-key convention that keeps the mapping a
 *       one-to-one reflection of the fixed-width record.</li>
 *   <li>The primary key is a natural key carried on the record, so there is no
 *       {@code @GeneratedValue} and no surrogate identifier.</li>
 *   <li>This record is a read-mostly cross-reference; the COBOL programs treat it
 *       as an immutable link, so no optimistic-locking {@code @Version} column is
 *       modeled (consistent with the {@code card_xref} table definition).</li>
 * </ul>
 */
@Entity
@Table(name = "card_xref")
public class CardXref {

    /**
     * Card number that owns this cross-reference row.
     *
     * <p>Maps COBOL {@code XREF-CARD-NUM PIC X(16)} to the primary-key column
     * {@code xref_card_num VARCHAR(16) NOT NULL}. This is the VSAM primary key
     * ({@code KEYLEN=16}) and is a natural, application-supplied key.</p>
     */
    @Id
    @Column(name = "xref_card_num", length = 16, nullable = false)
    private String xrefCardNum;

    /**
     * Identifier of the customer that owns the card.
     *
     * <p>Maps COBOL {@code XREF-CUST-ID PIC 9(09)} to {@code cust_id BIGINT NOT NULL}.
     * This is a scalar foreign key referencing {@code customer(cust_id)}; the
     * constraint itself is defined by the Flyway migration.</p>
     */
    @Column(name = "cust_id", nullable = false)
    private Long custId;

    /**
     * Identifier of the account the card is linked to.
     *
     * <p>Maps COBOL {@code XREF-ACCT-ID PIC 9(11)} to {@code acct_id BIGINT NOT NULL}.
     * This is a scalar foreign key referencing {@code account(acct_id)} and is
     * indexed via {@code idx_card_xref_acct_id} (the formalized VSAM alternate
     * index); both the constraint and the index are defined by the Flyway
     * migration.</p>
     */
    @Column(name = "acct_id", nullable = false)
    private Long acctId;

    /**
     * Protected no-argument constructor required by the JPA provider.
     *
     * <p>Hibernate instantiates entities reflectively; application code should use
     * {@link #CardXref(String, Long, Long)} instead.</p>
     */
    protected CardXref() {
        // Required by JPA; intentionally empty.
    }

    /**
     * Creates a fully-populated cross-reference row.
     *
     * <p>Fields are assigned directly (not through setters) so that no overridable
     * instance method is invoked from the constructor.</p>
     *
     * @param xrefCardNum the 16-character card number (primary key)
     * @param custId      the owning customer identifier
     * @param acctId      the linked account identifier
     */
    public CardXref(String xrefCardNum, Long custId, Long acctId) {
        this.xrefCardNum = xrefCardNum;
        this.custId = custId;
        this.acctId = acctId;
    }

    /**
     * Returns the card number (primary key).
     *
     * @return the 16-character card number, or {@code null} if unset
     */
    public String getXrefCardNum() {
        return xrefCardNum;
    }

    /**
     * Sets the card number (primary key).
     *
     * @param xrefCardNum the 16-character card number
     */
    public void setXrefCardNum(String xrefCardNum) {
        this.xrefCardNum = xrefCardNum;
    }

    /**
     * Returns the owning customer identifier.
     *
     * @return the customer identifier, or {@code null} if unset
     */
    public Long getCustId() {
        return custId;
    }

    /**
     * Sets the owning customer identifier.
     *
     * @param custId the customer identifier
     */
    public void setCustId(Long custId) {
        this.custId = custId;
    }

    /**
     * Returns the linked account identifier.
     *
     * @return the account identifier, or {@code null} if unset
     */
    public Long getAcctId() {
        return acctId;
    }

    /**
     * Sets the linked account identifier.
     *
     * @param acctId the account identifier
     */
    public void setAcctId(Long acctId) {
        this.acctId = acctId;
    }

    /**
     * Equality is based on the natural primary key {@code xrefCardNum}, mirroring
     * the VSAM unique-key identity of a cross-reference record.
     *
     * @param o the object to compare with
     * @return {@code true} if {@code o} is a {@code CardXref} with an equal card number
     */
    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }
        CardXref other = (CardXref) o;
        return Objects.equals(xrefCardNum, other.xrefCardNum);
    }

    /**
     * Hash code derived from the natural primary key {@code xrefCardNum}, kept
     * consistent with {@link #equals(Object)}.
     *
     * @return the hash code for this cross-reference
     */
    @Override
    public int hashCode() {
        return Objects.hash(xrefCardNum);
    }

    /**
     * Returns a diagnostic representation of this cross-reference. All three fields
     * are non-sensitive identifiers, so they are safe to include.
     *
     * @return a string containing the card number, customer id, and account id
     */
    @Override
    public String toString() {
        return "CardXref{"
                + "xrefCardNum='" + xrefCardNum + '\''
                + ", custId=" + custId
                + ", acctId=" + acctId
                + '}';
    }
}
