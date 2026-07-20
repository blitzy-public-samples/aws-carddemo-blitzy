/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License").
 * You may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.aws.carddemo.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import java.util.Objects;

/**
 * JPA entity mapping the legacy COBOL card master record to the relational
 * {@code card} table.
 *
 * <p>This entity is the faithful re-platforming of the {@code CARD-RECORD}
 * copybook (originally {@code app/cpy/CVACT02Y.cpy}, retained for reference at
 * {@code legacy/cpy/CVACT02Y.cpy}, record length 150 bytes) that backed the
 * {@code CARDDATA.VSAM.KSDS} indexed dataset. Each field preserves the name,
 * type and length semantics of its COBOL counterpart so that behavioral parity
 * with the mainframe application is maintained.</p>
 *
 * <p>The original copybook layout (field order preserved) is:</p>
 * <pre>
 * 01  CARD-RECORD.
 *     05  CARD-NUM             PIC X(16).   card number, primary key
 *     05  CARD-ACCT-ID         PIC 9(11).   owning account id, foreign key
 *     05  CARD-CVV-CD          PIC 9(03).   card verification value (SENSITIVE)
 *     05  CARD-EMBOSSED-NAME   PIC X(50).   name embossed on the card
 *     05  CARD-EXPIRAION-DATE  PIC X(10).   expiration date, stored as text
 *     05  CARD-ACTIVE-STATUS   PIC X(01).   active status flag
 *     05  FILLER               PIC X(59).   record padding (NOT persisted)
 * </pre>
 *
 * <p><strong>Schema ownership.</strong> The relational schema is owned by the
 * Flyway migration {@code V1__schema.sql}; Hibernate runs with
 * {@code ddl-auto=validate} and therefore never creates or alters the table.
 * The column names, types and lengths declared here must match that DDL
 * exactly. The {@code card_num} column is the primary key (VSAM
 * {@code KEYLEN=16}); the {@code acct_id} column carries a database index
 * ({@code idx_card_acct_id}, formalizing the {@code CARDDATA.VSAM.AIX}
 * alternate index) and a foreign key to {@code account(acct_id)}. Both the
 * index and the foreign key are created and managed by Flyway, so the scalar
 * {@code acctId} field is intentionally <em>not</em> modeled as a JPA
 * association ({@code @ManyToOne}/{@code @JoinColumn}) and no
 * {@code @Table(indexes = ...)} declaration is present.</p>
 *
 * <p><strong>Sensitive data (PCI-DSS; decision-log D22-revised).</strong> The
 * {@link #cvv} field maps the legacy card verification value, but the real value
 * is <em>never stored at rest</em>: persisting the CVV/CVV2 after authorization is
 * prohibited by PCI-DSS Requirement 3.2, and behavioral parity requires nothing of
 * it (it is never mapped to a DTO, read by any service or batch job, logged, or
 * returned). The column is retained only to preserve the 150-byte
 * {@code CARD-RECORD} layout shape and its copybook-to-column traceability, and it
 * holds a fixed non-reversible masked placeholder ({@code "***"}). Redaction is
 * applied at the ingestion boundary ({@code LocalSeedDataLoader.redactAtRest}) and
 * at the schema level ({@code V6__redact_cvv_at_rest.sql}). The field is also
 * deliberately excluded from {@link #toString()}.</p>
 *
 * <p><strong>Optimistic locking.</strong> The card record is updated by the
 * online card-update flow. A JPA {@link Version} column reproduces the
 * integrity of the legacy READ-UPDATE-REWRITE cycle within a transactional
 * boundary, so concurrent updates cannot silently overwrite one another.</p>
 *
 * <p><strong>Dates as text.</strong> The expiration date is preserved as a
 * fixed-width {@code VARCHAR(10)} string exactly as the copybook defined it
 * ({@code PIC X(10)}); it is intentionally not converted to a temporal type.</p>
 */
@Entity
@Table(name = "card")
public class Card {

    /**
     * Card number &mdash; maps {@code CARD-NUM PIC X(16)}. Primary key
     * (VSAM {@code KEYLEN=16}).
     */
    @Id
    @Column(name = "card_num", length = 16, nullable = false)
    private String cardNum;

    /**
     * Owning account identifier &mdash; maps {@code CARD-ACCT-ID PIC 9(11)}.
     * Stored as a scalar {@code BIGINT} foreign key to {@code account(acct_id)};
     * the foreign key and supporting index are owned by Flyway.
     */
    @Column(name = "acct_id", nullable = false)
    private Long acctId;

    /**
     * Card verification value (CVV) &mdash; maps {@code CARD-CVV-CD PIC 9(03)}.
     * <strong>SENSITIVE (PCI-DSS; decision-log D22-revised):</strong> the real
     * value is never stored at rest. This column persists only a fixed
     * non-reversible masked placeholder ({@code "***"}); it is never logged,
     * mapped to a DTO, or returned, and is excluded from {@link #toString()}.
     * Redaction is enforced on the ingestion path
     * ({@code LocalSeedDataLoader.redactAtRest}) and documented/scrubbed by the
     * {@code V6__redact_cvv_at_rest.sql} migration. Kept as a {@code String} to
     * preserve the copybook layout shape (and any leading zeros the numeric COBOL
     * picture allowed) without converting to a numeric type.
     */
    @Column(name = "cvv", length = 3)
    private String cvv;

    /**
     * Name embossed on the card &mdash; maps {@code CARD-EMBOSSED-NAME PIC X(50)}.
     */
    @Column(name = "card_embossed_name", length = 50)
    private String cardEmbossedName;

    /**
     * Card expiration date, preserved as text &mdash; maps
     * {@code CARD-EXPIRAION-DATE PIC X(10)}. (The copybook misspells
     * "EXPIRAION"; the corrected column name {@code card_expiration_date} is
     * used.)
     */
    @Column(name = "card_expiration_date", length = 10)
    private String cardExpirationDate;

    /**
     * Card active status flag &mdash; maps {@code CARD-ACTIVE-STATUS PIC X(01)}.
     */
    @Column(name = "card_active_status", length = 1)
    private String cardActiveStatus;

    /**
     * Optimistic-lock version counter. Managed by JPA/Hibernate; not derived
     * from any COBOL field. Reproduces the last-writer integrity of the legacy
     * READ-UPDATE-REWRITE cycle.
     */
    @Version
    @Column(name = "version", nullable = false)
    private Long version;

    /**
     * Protected no-argument constructor required by JPA for entity
     * instantiation via reflection. Application code should prefer
     * {@link #Card(String, Long, String, String, String, String)}.
     */
    protected Card() {
        // Required by the JPA specification; intentionally empty.
    }

    /**
     * Convenience constructor for the persistent (non-managed) fields.
     *
     * <p>Fields are assigned directly rather than through setters so that no
     * overridable method observes a partially constructed instance. The
     * {@link #version} field is deliberately left unset; it is managed by the
     * persistence provider.</p>
     *
     * @param cardNum            card number (primary key)
     * @param acctId             owning account identifier
     * @param cvv                card verification value (sensitive)
     * @param cardEmbossedName   name embossed on the card
     * @param cardExpirationDate expiration date as text
     * @param cardActiveStatus   active status flag
     */
    public Card(String cardNum,
                Long acctId,
                String cvv,
                String cardEmbossedName,
                String cardExpirationDate,
                String cardActiveStatus) {
        this.cardNum = cardNum;
        this.acctId = acctId;
        this.cvv = cvv;
        this.cardEmbossedName = cardEmbossedName;
        this.cardExpirationDate = cardExpirationDate;
        this.cardActiveStatus = cardActiveStatus;
    }

    /**
     * Returns the card number (primary key).
     *
     * @return the card number
     */
    public String getCardNum() {
        return cardNum;
    }

    /**
     * Sets the card number (primary key).
     *
     * @param cardNum the card number
     */
    public void setCardNum(String cardNum) {
        this.cardNum = cardNum;
    }

    /**
     * Returns the owning account identifier.
     *
     * @return the account identifier
     */
    public Long getAcctId() {
        return acctId;
    }

    /**
     * Sets the owning account identifier.
     *
     * @param acctId the account identifier
     */
    public void setAcctId(Long acctId) {
        this.acctId = acctId;
    }

    /**
     * Returns the card verification value.
     *
     * <p><strong>SENSITIVE:</strong> the returned value must never be logged or
     * echoed back to a caller in full.</p>
     *
     * @return the card verification value
     */
    public String getCvv() {
        return cvv;
    }

    /**
     * Sets the card verification value (sensitive).
     *
     * @param cvv the card verification value
     */
    public void setCvv(String cvv) {
        this.cvv = cvv;
    }

    /**
     * Returns the name embossed on the card.
     *
     * @return the embossed name
     */
    public String getCardEmbossedName() {
        return cardEmbossedName;
    }

    /**
     * Sets the name embossed on the card.
     *
     * @param cardEmbossedName the embossed name
     */
    public void setCardEmbossedName(String cardEmbossedName) {
        this.cardEmbossedName = cardEmbossedName;
    }

    /**
     * Returns the card expiration date (text).
     *
     * @return the expiration date
     */
    public String getCardExpirationDate() {
        return cardExpirationDate;
    }

    /**
     * Sets the card expiration date (text).
     *
     * @param cardExpirationDate the expiration date
     */
    public void setCardExpirationDate(String cardExpirationDate) {
        this.cardExpirationDate = cardExpirationDate;
    }

    /**
     * Returns the card active status flag.
     *
     * @return the active status flag
     */
    public String getCardActiveStatus() {
        return cardActiveStatus;
    }

    /**
     * Sets the card active status flag.
     *
     * @param cardActiveStatus the active status flag
     */
    public void setCardActiveStatus(String cardActiveStatus) {
        this.cardActiveStatus = cardActiveStatus;
    }

    /**
     * Returns the optimistic-lock version counter.
     *
     * @return the version counter
     */
    public Long getVersion() {
        return version;
    }

    /**
     * Sets the optimistic-lock version counter. Normally managed by the
     * persistence provider; exposed to support detached-entity scenarios.
     *
     * @param version the version counter
     */
    public void setVersion(Long version) {
        this.version = version;
    }

    /**
     * Two {@code Card} instances are equal when they share the same
     * {@link #cardNum} primary key.
     *
     * @param o the object to compare with
     * @return {@code true} if the other object is a {@code Card} with an equal
     *         card number
     */
    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }
        Card other = (Card) o;
        return Objects.equals(cardNum, other.cardNum);
    }

    /**
     * Returns a hash code derived from the {@link #cardNum} primary key.
     *
     * @return the hash code
     */
    @Override
    public int hashCode() {
        return Objects.hash(cardNum);
    }

    /**
     * Returns a diagnostic string representation of this card.
     *
     * <p>The {@link #cvv} field is intentionally omitted: it is sensitive and
     * must never appear in logs or diagnostics.</p>
     *
     * @return a string representation excluding the CVV
     */
    @Override
    public String toString() {
        return "Card{"
                + "cardNum='" + cardNum + '\''
                + ", acctId=" + acctId
                + ", cardEmbossedName='" + cardEmbossedName + '\''
                + ", cardExpirationDate='" + cardExpirationDate + '\''
                + ", cardActiveStatus='" + cardActiveStatus + '\''
                + ", version=" + version
                + '}';
    }
}
