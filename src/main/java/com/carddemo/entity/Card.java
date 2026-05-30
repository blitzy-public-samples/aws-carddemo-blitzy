package com.carddemo.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import java.sql.Types;
import java.time.LocalDate;
import java.time.LocalDateTime;

import org.hibernate.annotations.JdbcTypeCode;
import org.springframework.data.annotation.CreatedBy;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedBy;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * JPA entity for credit-card data.
 *
 * <p>Maps the 150-byte {@code CARD-RECORD} from {@code app/cpy/CVACT02Y.cpy}
 * (CardDemo_v1.0-15-g27d6c6f-68). The copybook declares six user fields plus a
 * trailing {@code FILLER PIC X(59)}; the filler carries no business meaning and is
 * intentionally not represented in Java (PR-13 maps only the named fields).</p>
 *
 * <p>This is the system-of-record card table. Rows are seeded by the
 * data-initialization load ({@code DataInitializationJobConfig}) from
 * {@code app/data/ASCII/carddata.txt}; they are read and maintained by the card
 * list/view/update flows ({@code COCRDLIC}/{@code COCRDSLC}/{@code COCRDUPC} &rarr;
 * {@code CardController}/{@code CardService}) and referenced by transactions
 * ({@code transactions.card_num}) and by the cross-reference junction
 * ({@link CardXref}).</p>
 *
 * <p><strong>Index (replaces VSAM AIX per AAP &sect;0.6.13):</strong></p>
 * <ul>
 *   <li>{@code idx_card_account_id} on {@code account_id} replaces VSAM
 *       {@code CARDDATA.AIX} (alternate-index path {@code CARDAIX}, originally keyed on
 *       {@code CARD-ACCT-ID}); it backs {@code CardRepository.findByAccountId(...)} used by
 *       the paginated card-list controller ({@code COCRDLIC}). The physical index is
 *       created by Flyway {@code V2__indexes.sql}; declaring it here keeps the entity
 *       self-describing and is harmless under {@code ddl-auto: validate} (which validates
 *       tables and columns, not indexes).</li>
 * </ul>
 *
 * <p><strong>Note (PR-14).</strong> The COBOL field {@code CARD-EXPIRAION-DATE}
 * [sic — misspelled in the copybook] is normalized to the correctly spelled Java field
 * {@code expirationDate} and the database column {@code expiration_date}, consistent with
 * the same normalization applied to {@code accounts.expiration_date}.</p>
 *
 * <p><strong>Schema conformance (mandatory).</strong> Every {@code @Column} mapping below
 * mirrors the committed Flyway DDL {@code src/main/resources/db/migration/V1__schema.sql}
 * (table {@code cards}). The {@code dev}, {@code prod}, and {@code test} profiles all run
 * Hibernate with {@code ddl-auto: validate}, so any divergence in column name, SQL type, or
 * length would cause the Spring context to fail to start. In particular, the committed DDL
 * dictates the following Java types (which differ from a naive 1:1 string mapping of the
 * COBOL {@code PIC} clauses):</p>
 * <ul>
 *   <li>{@code cvv_cd SMALLINT NOT NULL} &rarr; {@link Short} (not a {@code String}); the
 *       three-digit {@code CARD-CVV-CD PIC 9(03)} is held as an exact small integer.</li>
 *   <li>{@code expiration_date DATE} &rarr; {@link LocalDate} (not a {@code String}); the
 *       {@code CARD-EXPIRAION-DATE PIC X(10)} text date is normalized to a SQL {@code DATE}.</li>
 *   <li>{@code active_status CHAR(1) NOT NULL} &rarr; {@link String} annotated with
 *       {@link JdbcTypeCode}{@code (Types.CHAR)} so Hibernate binds it as a fixed-width
 *       {@code CHAR} (a plain {@code String} resolves to {@code VARCHAR} and would fail
 *       validation against the committed schema), consistent with the {@code CHAR} mappings
 *       in {@link Transaction}.</li>
 *   <li>{@code version INTEGER NOT NULL DEFAULT 0} &rarr; {@link Integer}.</li>
 *   <li>The audit fields keep the canonical Java names
 *       {@code createdAt}/{@code updatedAt}/{@code createdBy}/{@code updatedBy} but map to the
 *       DDL column names
 *       {@code created_date}/{@code last_modified_date}/{@code created_by}/{@code last_modified_by}.</li>
 * </ul>
 *
 * <p><strong>Relationships (AAP &sect;0.4.2).</strong> {@link #accountId} is modeled as a plain
 * {@link Long} foreign-key value (column {@code account_id}, DB constraint
 * {@code fk_cards_account} &rarr; {@code accounts.acct_id}) rather than a JPA
 * {@code @ManyToOne} association, matching the simpler entity-by-FK pattern used across this
 * module ({@link CardXref}, {@link Transaction}).</p>
 *
 * <p><strong>Optimistic locking (PR-22).</strong> {@code @Version version} replaces the VSAM
 * {@code READ UPDATE} exclusive-lock semantics with non-blocking optimistic concurrency; a
 * concurrent modification raises {@code OptimisticLockException} (mapped to HTTP 409). The
 * column is {@code INTEGER NOT NULL DEFAULT 0}, so the version is modeled as an
 * {@link Integer}.</p>
 *
 * <p><strong>Auditing (AAP &sect;0.6.12).</strong> {@code @CreatedDate}/{@code @LastModifiedDate}/
 * {@code @CreatedBy}/{@code @LastModifiedBy} are populated by Spring Data's
 * {@code AuditingEntityListener} (wired via {@link EntityListeners}) when
 * {@code @EnableJpaAuditing} is active &mdash; addressing the audit-log gap noted in the
 * original system.</p>
 *
 * <p>Persistence and auditing annotations use the {@code jakarta.*} namespace (PR-28).
 * Identity-based {@link #equals(Object)}/{@link #hashCode()} derive from the natural primary
 * key {@code cardNum} alone ({@code card_num VARCHAR(16) NOT NULL PRIMARY KEY}).</p>
 *
 * @see Account
 * @see CardXref
 * @see Transaction
 */
@Entity
@Table(name = "cards", indexes = {
    @Index(name = "idx_card_account_id", columnList = "account_id")
})
@EntityListeners(AuditingEntityListener.class)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Card {

    /**
     * Maps COBOL {@code CARD-NUM PIC X(16)} &rarr; {@code card_num VARCHAR(16) NOT NULL PRIMARY
     * KEY}. The natural 16-character primary key; also the target of the foreign keys
     * {@code transactions.card_num} and {@code card_xref.xref_card_num}.
     */
    @Id
    @Column(name = "card_num", length = 16, nullable = false)
    private String cardNum;

    /**
     * Maps COBOL {@code CARD-ACCT-ID PIC 9(11)} &rarr; {@code account_id BIGINT NOT NULL}.
     * Foreign key {@code fk_cards_account} to {@code accounts.acct_id}; the account this card
     * draws on. Held as a {@link Long} value (no {@code @ManyToOne}) per AAP &sect;0.4.2.
     * Indexed by {@code idx_card_account_id} (replaces VSAM {@code CARDDATA.AIX} path
     * {@code CARDAIX}) to back {@code CardRepository.findByAccountId}.
     */
    @Column(name = "account_id", nullable = false)
    private Long accountId;

    /**
     * Maps COBOL {@code CARD-CVV-CD PIC 9(03)} &rarr; {@code cvv_cd SMALLINT NOT NULL}. The
     * three-digit card verification value held as an exact {@link Short} (the committed DDL
     * chose {@code SMALLINT}); modeled as {@code Short} so Hibernate's {@code ddl-auto: validate}
     * matches the {@code SMALLINT} column.
     */
    @Column(name = "cvv_cd", nullable = false)
    private Short cvvCd;

    /** Maps COBOL {@code CARD-EMBOSSED-NAME PIC X(50)} &rarr; {@code embossed_name VARCHAR(50)}. */
    @Column(name = "embossed_name", length = 50)
    private String embossedName;

    /**
     * Maps COBOL {@code CARD-EXPIRAION-DATE} [sic] {@code PIC X(10)} &rarr;
     * {@code expiration_date DATE}. The COBOL field name is misspelled; the Java field corrects
     * it to {@code expirationDate} and the column is {@code expiration_date} (PR-14). The
     * 10-character text date is normalized to a SQL {@code DATE} / {@link LocalDate}.
     */
    @Column(name = "expiration_date")
    private LocalDate expirationDate;

    /**
     * Maps COBOL {@code CARD-ACTIVE-STATUS PIC X(01)} &rarr; {@code active_status CHAR(1) NOT
     * NULL} (e.g. {@code "Y"}/{@code "N"}). {@code @JdbcTypeCode(Types.CHAR)} forces the
     * fixed-width {@code CHAR} binding required by {@code ddl-auto: validate} (a plain
     * {@code String} resolves to {@code VARCHAR} and would fail validation against the committed
     * schema), consistent with the {@code CHAR} mappings in {@link Transaction}.
     */
    @JdbcTypeCode(Types.CHAR)
    @Column(name = "active_status", length = 1, nullable = false)
    private String activeStatus;

    /**
     * Optimistic-locking version (PR-22). Maps {@code version INTEGER NOT NULL DEFAULT 0};
     * managed by Hibernate. Modeled as {@link Integer} to match the {@code INTEGER} column.
     */
    @Version
    @Column(name = "version")
    private Integer version;

    /**
     * Audit timestamp set once on initial persist by Spring Data's
     * {@code AuditingEntityListener} via {@code @CreatedDate} (active when
     * {@code @EnableJpaAuditing} is present); immutable thereafter ({@code updatable = false}).
     * Maps to {@code created_date TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP} (AAP &sect;0.6.12).
     */
    @CreatedDate
    @Column(name = "created_date", updatable = false)
    private LocalDateTime createdAt;

    /**
     * Audit timestamp refreshed on every update by Spring Data's
     * {@code AuditingEntityListener} via {@code @LastModifiedDate}. Maps to
     * {@code last_modified_date TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP} (AAP &sect;0.6.12).
     */
    @LastModifiedDate
    @Column(name = "last_modified_date")
    private LocalDateTime updatedAt;

    /**
     * Identity of the principal that created the record, populated once on persist via
     * {@code @CreatedBy} ({@code updatable = false}). Maps to {@code created_by VARCHAR(50)}.
     */
    @CreatedBy
    @Column(name = "created_by", length = 50, updatable = false)
    private String createdBy;

    /**
     * Identity of the principal that last modified the record, refreshed on update via
     * {@code @LastModifiedBy}. Maps to {@code last_modified_by VARCHAR(50)}.
     */
    @LastModifiedBy
    @Column(name = "last_modified_by", length = 50)
    private String updatedBy;

    // -------------------- equals / hashCode (natural-key identity) --------------------

    /**
     * Identity equality based solely on the natural primary key {@link #cardNum}. Two
     * {@code Card} instances are equal only when both have a non-null, equal {@code cardNum}.
     * Transient (unsaved) instances with a {@code null} card number are therefore never equal
     * to one another.
     */
    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof Card)) {
            return false;
        }
        Card that = (Card) o;
        return cardNum != null && cardNum.equals(that.cardNum);
    }

    /**
     * Hash code derived from the natural primary key {@link #cardNum} (0 when {@code cardNum}
     * is {@code null}), consistent with {@link #equals(Object)}.
     */
    @Override
    public int hashCode() {
        return cardNum != null ? cardNum.hashCode() : 0;
    }
}
