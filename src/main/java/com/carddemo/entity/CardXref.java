package com.carddemo.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * JPA entity for the card-to-customer-to-account cross-reference junction.
 *
 * <p>Maps the 50-byte {@code CARD-XREF-RECORD} from {@code app/cpy/CVACT03Y.cpy}
 * (CardDemo_v1.0-15-g27d6c6f-68): three user fields plus a trailing
 * {@code FILLER PIC X(14)}. The filler carries no business meaning and is
 * intentionally not represented in Java. One row exists per card; each card maps
 * to exactly one customer and one account.</p>
 *
 * <p>This table is read by transaction validation ({@code CBTRN02C}
 * {@code 1500-A-LOOKUP-XREF}) to resolve a card number to its owning account, and by
 * statement generation ({@code CBSTM03A}) to resolve the customer and account for each
 * card. It is populated by the data-initialization seed load
 * ({@code DataInitializationJobConfig}) from {@code app/data/ASCII/cardxref.txt}.</p>
 *
 * <p><strong>Index (replaces VSAM AIX per AAP &sect;0.6.13):</strong></p>
 * <ul>
 *   <li>{@code idx_xref_account_id} on {@code xref_acct_id} replaces VSAM
 *       {@code CARDXREF.AIX} (alternate-index path {@code CXACAIX}, originally keyed on
 *       {@code XREF-ACCT-ID}); it backs {@code CardXrefRepository.findByAccountId} used by
 *       transaction validation and statement-generation flows. The physical index is
 *       created by Flyway {@code V2__indexes.sql}; declaring it here keeps the entity
 *       self-describing and is harmless under {@code ddl-auto: validate} (which validates
 *       tables and columns, not indexes).</li>
 * </ul>
 *
 * <p><strong>No optimistic locking / no auditing.</strong> Unlike {@link Account},
 * {@link Card}, {@link Customer}, and {@link Transaction}, this junction table carries no
 * {@code @Version} field — cross-reference rows are immutable once seeded and are not
 * concurrently modified (AAP &sect;0.3.3 mandates {@code @Version} only for
 * {@code Account}/{@code Card}/{@code Customer}/{@code Transaction}) — and it is not
 * registered with the JPA {@code AuditingEntityListener} (the {@code card_xref} table has
 * no audit columns, matching the committed DDL comment "No audit or version columns
 * (immutable cross-reference)").</p>
 *
 * <p><strong>Schema conformance.</strong> Every {@code @Column} mapping below mirrors the
 * committed Flyway DDL {@code src/main/resources/db/migration/V1__schema.sql} (table
 * {@code card_xref}). This is mandatory: the {@code dev}, {@code prod}, and {@code test}
 * profiles all run Hibernate with {@code ddl-auto: validate}, so any divergence in column
 * name, SQL type, or length would cause the Spring context to fail to start. The physical
 * columns retain their COBOL-derived {@code xref_} prefix ({@code xref_card_num},
 * {@code xref_cust_id}, {@code xref_acct_id}), while the Java fields use conventional
 * unprefixed camelCase names ({@code xrefCardNum}, {@code custId}, {@code accountId}); the
 * latter two field names back the Spring Data derived queries
 * {@code CardXrefRepository.findByAccountId} / {@code findByCustId} per AAP &sect;0.4.1.5,
 * which resolve the property path from the field name rather than the column name. Column
 * lengths mirror the COBOL {@code PIC} clauses (PR-13); the {@code PIC 9(09)} customer id
 * and {@code PIC 9(11)} account id both map to {@code BIGINT}/{@link Long}. Persistence
 * annotations use the {@code jakarta.*} namespace (PR-28).</p>
 *
 * <p>Identity-based {@link #equals(Object)}/{@link #hashCode()} derive from the natural
 * primary key {@code xrefCardNum} alone.</p>
 *
 * @see Card
 * @see Customer
 * @see Account
 */
@Entity
@Table(name = "card_xref", indexes = {
    @Index(name = "idx_xref_account_id", columnList = "xref_acct_id")
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class CardXref {

    /**
     * Maps COBOL {@code XREF-CARD-NUM PIC X(16)} &rarr; {@code xref_card_num VARCHAR(16) NOT
     * NULL PRIMARY KEY}. The natural primary key: exactly one cross-reference row exists per
     * card. Also the foreign key {@code fk_xref_card} back to {@code cards.card_num}.
     */
    @Id
    @Column(name = "xref_card_num", length = 16, nullable = false)
    private String xrefCardNum;

    /**
     * Maps COBOL {@code XREF-CUST-ID PIC 9(09)} &rarr; {@code xref_cust_id BIGINT NOT NULL}.
     * Foreign key {@code fk_xref_customer} to {@code customers.cust_id}; identifies the
     * customer that owns the card. Held as {@link Long} (BIGINT) with no length attribute,
     * consistent with the other numeric-id mappings in this module.
     */
    @Column(name = "xref_cust_id", nullable = false)
    private Long custId;

    /**
     * Maps COBOL {@code XREF-ACCT-ID PIC 9(11)} &rarr; {@code xref_acct_id BIGINT NOT NULL}.
     * Foreign key {@code fk_xref_account} to {@code accounts.acct_id}; identifies the account
     * the card draws on. Indexed by {@code idx_xref_account_id} (replaces VSAM
     * {@code CARDXREF.AIX} path {@code CXACAIX}) to back
     * {@code CardXrefRepository.findByAccountId}.
     */
    @Column(name = "xref_acct_id", nullable = false)
    private Long accountId;

    // -------------------- equals / hashCode (natural-key identity) --------------------

    /**
     * Identity equality based solely on the natural primary key {@link #xrefCardNum}. Two
     * {@code CardXref} instances are equal only when both have a non-null, equal
     * {@code xrefCardNum}. Transient (unsaved) instances with a {@code null} card number are
     * therefore never equal to one another.
     */
    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof CardXref)) {
            return false;
        }
        CardXref that = (CardXref) o;
        return xrefCardNum != null && xrefCardNum.equals(that.xrefCardNum);
    }

    /**
     * Hash code derived from the natural primary key {@link #xrefCardNum} (0 when
     * {@code xrefCardNum} is {@code null}), consistent with {@link #equals(Object)}.
     */
    @Override
    public int hashCode() {
        return xrefCardNum != null ? xrefCardNum.hashCode() : 0;
    }
}
