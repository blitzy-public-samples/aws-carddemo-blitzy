package com.carddemo.entity;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.LocalDate;
import java.util.Objects;

/**
 * JPA entity mapping the legacy VSAM {@code CARDDATA} dataset to the relational table
 * {@code cards}.
 *
 * <h2>Legacy lineage</h2>
 * <p>This entity is the Spring Boot / Hibernate re-expression of the COBOL {@code CARD-RECORD}
 * structure defined in copybook {@code app/cpy/CVACT02Y.cpy} (record length 150). In the
 * mainframe system that record lived in the VSAM key-sequenced dataset
 * {@code AWS.M2.CARDDEMO.CARDDATA.VSAM.KSDS}, whose primary key is the 16-byte card number
 * ({@code KEYLEN 16}) and which carries one alternate index over the 11-digit account id
 * (the {@code CARDAIX} path). Those two access paths are preserved in the relational world as
 * the primary key {@code pk_cards (card_num)} and the secondary index
 * {@code idx_cards_acct_id (card_acct_id)} declared in {@code V1__schema.sql}; the latter backs
 * the paginated repository method {@code CardRepository.findByCardAcctId(Long, Pageable)}
 * (legacy fixed page size of 7 rows per screen).</p>
 *
 * <h2>Field mapping ({@code CVACT02Y} record &rarr; column)</h2>
 * <table border="1">
 *   <caption>Source-to-entity field mapping</caption>
 *   <tr><th>COBOL field</th><th>Picture</th><th>Java field</th><th>Column</th><th>SQL type</th></tr>
 *   <tr><td>{@code CARD-NUM}</td><td>{@code X(16)}</td><td>{@link #cardNum}</td><td>{@code card_num}</td><td>{@code VARCHAR(16)} (PK)</td></tr>
 *   <tr><td>{@code CARD-ACCT-ID}</td><td>{@code 9(11)}</td><td>{@link #cardAcctId}</td><td>{@code card_acct_id}</td><td>{@code BIGINT NOT NULL} (FK)</td></tr>
 *   <tr><td>{@code CARD-CVV-CD}</td><td>{@code 9(03)}</td><td>{@link #cvvCode}</td><td>{@code cvv_code}</td><td>{@code INTEGER}</td></tr>
 *   <tr><td>{@code CARD-EMBOSSED-NAME}</td><td>{@code X(50)}</td><td>{@link #embossedName}</td><td>{@code embossed_name}</td><td>{@code VARCHAR(50)}</td></tr>
 *   <tr><td>{@code CARD-EXPIRAION-DATE} (sic)</td><td>{@code X(10)}</td><td>{@link #expirationDate}</td><td>{@code expiration_date}</td><td>{@code DATE}</td></tr>
 *   <tr><td>{@code CARD-ACTIVE-STATUS}</td><td>{@code X(01)}</td><td>{@link #activeStatus}</td><td>{@code active_status}</td><td>{@code CHAR(1)}</td></tr>
 *   <tr><td>{@code FILLER}</td><td>{@code X(59)}</td><td>&mdash;</td><td>&mdash;</td><td>not persisted</td></tr>
 * </table>
 *
 * <p>The trailing {@code FILLER X(59)} of the source record carries no business data and is
 * therefore deliberately not mapped, consistent with the schema policy that COBOL filler bytes
 * are never persisted.</p>
 *
 * <h2>Binding schema contract</h2>
 * <p>Hibernate runs with {@code spring.jpa.hibernate.ddl-auto=validate}; Flyway migration
 * {@code V1__schema.sql} is authoritative. Every column name, type, and length declared here
 * MUST match that DDL exactly:</p>
 * <pre>
 *   CREATE TABLE cards (
 *       card_num        VARCHAR(16) NOT NULL,
 *       card_acct_id    BIGINT      NOT NULL,
 *       cvv_code        INTEGER,
 *       embossed_name   VARCHAR(50),
 *       expiration_date DATE,
 *       active_status   CHAR(1),
 *       CONSTRAINT pk_cards PRIMARY KEY (card_num),
 *       CONSTRAINT fk_cards_acct FOREIGN KEY (card_acct_id) REFERENCES accounts (acct_id)
 *   );
 * </pre>
 *
 * <h2>Sensitive-field suppression (PII) &mdash; AAP &sect;0.6.8 / &sect;0.7.1</h2>
 * <p>The card verification value {@link #cvvCode} ({@code CARD-CVV-CD}) is <strong>persisted but
 * MUST NEVER be serialized to a client nor written to logs</strong>. This entity enforces that as
 * defense-in-depth in two places:</p>
 * <ul>
 *   <li>{@link JsonIgnore} on {@link #cvvCode} guarantees the value is never emitted to JSON even
 *       if an entity instance is ever returned directly from a controller (primary suppression is
 *       still the DTO/mapper boundary, which simply never copies the field).</li>
 *   <li>{@link #toString()} deliberately omits {@link #cvvCode}, so the CVV can never leak into a
 *       log line, stack trace, or debugger string.</li>
 * </ul>
 *
 * <h2>Immutability of the card identity &mdash; AAP &sect;0.6.8</h2>
 * <p>The card number and the owning account linkage are immutable after creation, mirroring the
 * legacy update program {@code COCRDUPC}, which treats the card number and account id as the
 * record key (RID) and never rewrites them:</p>
 * <ul>
 *   <li>{@link #cardNum} is the {@link Id primary key} and is immutable by nature &mdash; JPA never
 *       issues {@code UPDATE} statements against a primary-key column.</li>
 *   <li>{@link #cardAcctId} is annotated {@code updatable = false}, so even if a setter is invoked
 *       on a managed instance, Hibernate will not propagate the change to an existing row. The
 *       service layer additionally rejects attempts to mutate it.</li>
 * </ul>
 *
 * <h2>Design notes</h2>
 * <ul>
 *   <li>The owning account is modelled as the <strong>scalar foreign-key field</strong>
 *       {@link #cardAcctId} ({@code Long}), <em>not</em> a {@code @ManyToOne} association. This flat
 *       design keeps the entity lightweight, matches the COBOL record shape, and directly backs the
 *       derived query {@code findByCardAcctId}. Referential integrity is enforced by the database
 *       constraint {@code fk_cards_acct}.</li>
 *   <li>{@link #cardNum} is a {@link String} (not a numeric type) to preserve the leading zeros of
 *       the 16-character primary account number (PAN).</li>
 *   <li>{@link #activeStatus} carries {@link JdbcTypeCode}{@code (}{@link SqlTypes#CHAR}{@code )} so
 *       that Hibernate maps the {@code String} to the fixed-width {@code CHAR(1)} column rather than
 *       the default {@code VARCHAR}; without it, schema validation against the {@code CHAR(1)} DDL
 *       would fail.</li>
 *   <li>No {@code @GeneratedValue} is declared &mdash; the primary key is application-assigned
 *       (seeded from {@code CARDDATA} and from the card-creation flow), exactly as in VSAM.</li>
 * </ul>
 *
 * @see com.fasterxml.jackson.annotation.JsonIgnore
 * @see org.hibernate.annotations.JdbcTypeCode
 * @see org.hibernate.type.SqlTypes#CHAR
 */
@Entity
@Table(name = "cards")
public class Card {

    /**
     * The 16-character card number / primary account number (PAN); source
     * {@code CARD-NUM PIC X(16)}, primary key {@code pk_cards}.
     *
     * <p>Held as a {@link String} to preserve leading zeros. As the {@link Id}, it is immutable by
     * nature: JPA never updates a primary-key column. No {@code @GeneratedValue} is used because the
     * value is application-assigned.</p>
     */
    @Id
    @Column(name = "card_num", length = 16)
    private String cardNum;

    /**
     * The owning account identifier; source {@code CARD-ACCT-ID PIC 9(11)}, mapped to the
     * {@code card_acct_id BIGINT NOT NULL} scalar foreign-key column (constraint
     * {@code fk_cards_acct} &rarr; {@code accounts.acct_id}).
     *
     * <p>Declared {@code nullable = false} to match the DDL and {@code updatable = false} to enforce
     * immutability of the account linkage at the persistence layer (AAP &sect;0.6.8): Hibernate will
     * not include this column in {@code UPDATE} statements for an existing row. This is intentionally
     * a scalar {@code Long}, not a {@code @ManyToOne} association, and it backs
     * {@code CardRepository.findByCardAcctId(Long, Pageable)} (the relational replacement for the
     * VSAM {@code CARDAIX} browse, page size 7).</p>
     */
    @Column(name = "card_acct_id", nullable = false, updatable = false)
    private Long cardAcctId;

    /**
     * The three-digit card verification value (CVV); source {@code CARD-CVV-CD PIC 9(03)}, mapped to
     * {@code cvv_code INTEGER}.
     *
     * <p><strong>Sensitive &mdash; never exposed.</strong> Annotated {@link JsonIgnore} so it is
     * never serialized to JSON, and deliberately excluded from {@link #toString()} so it never
     * appears in logs (AAP &sect;0.6.8 / &sect;0.7.1). The value is persisted for parity with the
     * source record but must never cross the service boundary in cleartext.</p>
     */
    @JsonIgnore
    @Column(name = "cvv_code")
    private Integer cvvCode;

    /**
     * The cardholder name embossed on the card; source {@code CARD-EMBOSSED-NAME PIC X(50)}, mapped
     * to {@code embossed_name VARCHAR(50)}.
     */
    @Column(name = "embossed_name", length = 50)
    private String embossedName;

    /**
     * The card expiration date; source {@code CARD-EXPIRAION-DATE PIC X(10)} (spelling preserved
     * from the copybook), mapped to {@code expiration_date DATE}.
     *
     * <p>Modelled as {@link LocalDate}; the application-wide Jackson JSR-310 configuration renders it
     * on the wire as an ISO-8601 {@code yyyy-MM-dd} string.</p>
     */
    @Column(name = "expiration_date")
    private LocalDate expirationDate;

    /**
     * The single-character active-status flag (typically {@code "Y"} or {@code "N"}); source
     * {@code CARD-ACTIVE-STATUS PIC X(01)}, mapped to the fixed-width {@code active_status CHAR(1)}
     * column.
     *
     * <p>The {@link JdbcTypeCode}{@code (}{@link SqlTypes#CHAR}{@code )} annotation forces the
     * {@code CHAR} JDBC type so that Hibernate schema validation succeeds against the {@code CHAR(1)}
     * DDL (a plain {@code String} would otherwise be mapped to {@code VARCHAR}).</p>
     */
    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "active_status", length = 1)
    private String activeStatus;

    /**
     * Protected no-argument constructor required by JPA / Hibernate for entity instantiation
     * (reflective proxy creation and result-set hydration).
     */
    public Card() {
        // Required by JPA.
    }

    /**
     * Convenience constructor that initializes every persisted field of the card.
     *
     * @param cardNum        the 16-character card number / PAN (primary key); must not be {@code null}
     * @param cardAcctId     the owning account identifier (immutable after creation)
     * @param cvvCode        the three-digit card verification value (sensitive; never serialized/logged)
     * @param embossedName   the cardholder name embossed on the card
     * @param expirationDate the card expiration date
     * @param activeStatus   the single-character active-status flag (for example {@code "Y"} / {@code "N"})
     */
    public Card(String cardNum,
                Long cardAcctId,
                Integer cvvCode,
                String embossedName,
                LocalDate expirationDate,
                String activeStatus) {
        this.cardNum = cardNum;
        this.cardAcctId = cardAcctId;
        this.cvvCode = cvvCode;
        this.embossedName = embossedName;
        this.expirationDate = expirationDate;
        this.activeStatus = activeStatus;
    }

    /**
     * Returns the 16-character card number / PAN (primary key).
     *
     * @return the card number
     */
    public String getCardNum() {
        return cardNum;
    }

    /**
     * Sets the card number. Intended for object construction only; as the primary key the value is
     * immutable once the row is persisted (JPA never updates a primary-key column).
     *
     * @param cardNum the card number to set
     */
    public void setCardNum(String cardNum) {
        this.cardNum = cardNum;
    }

    /**
     * Returns the owning account identifier.
     *
     * @return the account id this card belongs to
     */
    public Long getCardAcctId() {
        return cardAcctId;
    }

    /**
     * Sets the owning account identifier. Intended for object construction only; because the column
     * is mapped {@code updatable = false}, Hibernate will not propagate a change of this value to an
     * existing row, and the service layer additionally rejects such mutations (AAP &sect;0.6.8).
     *
     * @param cardAcctId the account id to set
     */
    public void setCardAcctId(Long cardAcctId) {
        this.cardAcctId = cardAcctId;
    }

    /**
     * Returns the sensitive card verification value (CVV).
     *
     * <p>The value is annotated {@link JsonIgnore} on the field and excluded from {@link #toString()};
     * callers must never serialize or log the returned value.</p>
     *
     * @return the three-digit CVV, or {@code null} if unset
     */
    public Integer getCvvCode() {
        return cvvCode;
    }

    /**
     * Sets the sensitive card verification value (CVV).
     *
     * @param cvvCode the three-digit CVV to set
     */
    public void setCvvCode(Integer cvvCode) {
        this.cvvCode = cvvCode;
    }

    /**
     * Returns the embossed cardholder name.
     *
     * @return the embossed name
     */
    public String getEmbossedName() {
        return embossedName;
    }

    /**
     * Sets the embossed cardholder name.
     *
     * @param embossedName the embossed name to set
     */
    public void setEmbossedName(String embossedName) {
        this.embossedName = embossedName;
    }

    /**
     * Returns the card expiration date.
     *
     * @return the expiration date
     */
    public LocalDate getExpirationDate() {
        return expirationDate;
    }

    /**
     * Sets the card expiration date.
     *
     * @param expirationDate the expiration date to set
     */
    public void setExpirationDate(LocalDate expirationDate) {
        this.expirationDate = expirationDate;
    }

    /**
     * Returns the single-character active-status flag.
     *
     * @return the active-status flag (for example {@code "Y"} or {@code "N"})
     */
    public String getActiveStatus() {
        return activeStatus;
    }

    /**
     * Sets the single-character active-status flag.
     *
     * @param activeStatus the active-status flag to set
     */
    public void setActiveStatus(String activeStatus) {
        this.activeStatus = activeStatus;
    }

    /**
     * Compares two cards for equality on their natural key, the {@link #cardNum card number}.
     *
     * <p>The card number is an application-assigned, immutable natural key, which makes it a stable
     * basis for identity across the entity's transient and persistent states.</p>
     *
     * @param o the object to compare with
     * @return {@code true} if {@code o} is a {@code Card} with the same card number
     */
    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof Card)) {
            return false;
        }
        Card other = (Card) o;
        return Objects.equals(cardNum, other.cardNum);
    }

    /**
     * Returns a hash code consistent with {@link #equals(Object)}, derived from the
     * {@link #cardNum card number}.
     *
     * @return the hash code based on the card number
     */
    @Override
    public int hashCode() {
        return Objects.hash(cardNum);
    }

    /**
     * Returns a diagnostic string representation of this card.
     *
     * <p><strong>The card verification value ({@link #cvvCode}) is intentionally omitted</strong> so
     * that the CVV can never appear in logs, traces, or debugger output (AAP &sect;0.6.8 / &sect;0.7.1).
     * Do not add it under any circumstances.</p>
     *
     * @return a string containing the non-sensitive fields of this card
     */
    @Override
    public String toString() {
        return "Card{"
                + "cardNum='" + cardNum + '\''
                + ", cardAcctId=" + cardAcctId
                + ", embossedName='" + embossedName + '\''
                + ", expirationDate=" + expirationDate
                + ", activeStatus='" + activeStatus + '\''
                + '}';
    }
}
