package com.carddemo.entity;

import jakarta.persistence.Column;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * JPA entity for transaction-category reference data.
 *
 * <p>Maps the 60-byte {@code TRAN-CAT-RECORD} from {@code app/cpy/CVTRA04Y.cpy}
 * (CardDemo_v1.0-15-g27d6c6f-68):</p>
 * <pre>
 *   01 TRAN-CAT-RECORD.
 *     05 TRAN-CAT-KEY.
 *       10 TRAN-TYPE-CD        PIC X(02).   --&gt; id.typeCd     (type_cd       CHAR(2))
 *       10 TRAN-CAT-CD         PIC 9(04).   --&gt; id.categoryCd (cat_cd        CHAR(4))
 *     05 TRAN-CAT-TYPE-DESC    PIC X(50).   --&gt; categoryDesc  (category_desc VARCHAR(50))
 *     05 FILLER                PIC X(04).   --&gt; NOT mapped (reserved padding)
 * </pre>
 *
 * <p>Immutable reference/lookup data: the 18 standard categories are seeded from
 * {@code app/data/ASCII/trancatg.txt} via Flyway {@code V3__seed_reference_data.sql}.
 * The composite primary key {@code (type_cd, cat_cd)} is modelled with
 * {@link TransactionCategoryId} through {@code @EmbeddedId} (PR-15). The transaction
 * type-code component is a foreign-key-by-value to {@link TransactionType}
 * ({@code transaction_types.tran_type}); no JPA association is modelled because the
 * original COBOL design uses bare key fields (AAP &sect;0.7.2 &mdash; "no data model
 * redesign").</p>
 *
 * <p><strong>No optimistic locking / no auditing.</strong> Like the other lookup
 * tables, this reference entity carries no {@code @Version} column and is not registered
 * with the JPA {@code AuditingEntityListener}: rows are seeded once and are not subject
 * to concurrent modification (AAP &sect;0.3.3 restricts {@code @Version} to
 * {@code Account}/{@code Card}/{@code Customer}/{@code Transaction}; the committed
 * {@code transaction_categories} DDL declares no audit/version columns).</p>
 *
 * <p><strong>Schema conformance (mandatory).</strong> Every mapping below mirrors the
 * committed Flyway DDL {@code src/main/resources/db/migration/V1__schema.sql}
 * (table {@code transaction_categories}) exactly. The {@code dev}, {@code prod}, and
 * {@code test} profiles all run Hibernate with {@code ddl-auto: validate}, so any
 * divergence in column name, SQL type, or length would prevent the Spring context from
 * starting. The {@code category_desc} column is declared {@code VARCHAR(50) NOT NULL};
 * it is therefore mapped to a plain {@link String} (Hibernate's default {@code VARCHAR}
 * binding) with <em>no</em> {@code @JdbcTypeCode(Types.CHAR)} override &mdash; unlike the
 * fixed-width {@code CHAR} key columns mapped inside {@link TransactionCategoryId}.
 * Column lengths mirror the COBOL {@code PIC} clauses (PR-13) and persistence annotations
 * use the {@code jakarta.*} namespace (PR-28). The four {@code FILLER} bytes are reserved
 * padding and are intentionally not mapped to a Java field.</p>
 *
 * <p>Identity-based {@link #equals(Object)} / {@link #hashCode()} derive from the
 * composite primary key {@link #id} alone &mdash; hand-written rather than Lombok
 * {@code @Data} / {@code @EqualsAndHashCode} to avoid the well-known JPA
 * proxy/lazy-loading pitfalls of all-field equality on entities.</p>
 *
 * @see TransactionCategoryId
 * @see TransactionType
 */
@Entity
@Table(name = "transaction_categories")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class TransactionCategory {

    /**
     * Composite primary key: transaction type code + category code. Maps the COBOL
     * {@code TRAN-CAT-KEY} group ({@code TRAN-TYPE-CD PIC X(02)} +
     * {@code TRAN-CAT-CD PIC 9(04)}). The physical key columns
     * ({@code type_cd CHAR(2)}, {@code cat_cd CHAR(4)}) and their fixed-width
     * {@code CHAR} JDBC bindings are declared inside {@link TransactionCategoryId}, so no
     * {@code @Column} mapping is needed here (PR-15).
     */
    @EmbeddedId
    private TransactionCategoryId id;

    /**
     * Transaction category description. Maps COBOL
     * {@code TRAN-CAT-TYPE-DESC PIC X(50)} &rarr; {@code category_desc VARCHAR(50) NOT NULL}.
     * Held as a variable-length {@link String}; Hibernate's default {@code VARCHAR}
     * binding matches the committed DDL, so no {@code @JdbcTypeCode} override is required.
     */
    @Column(name = "category_desc", length = 50, nullable = false)
    private String categoryDesc;

    // -------------------- equals / hashCode (composite-key identity) --------------------

    /**
     * Identity equality based solely on the composite primary key {@link #id}. Two
     * {@code TransactionCategory} instances are equal only when both have a non-null,
     * equal {@code id}. Transient (unsaved) instances with a {@code null} id are therefore
     * never equal to one another, which is the recommended equality contract for JPA
     * entities with an assigned (non-generated) identifier.
     */
    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof TransactionCategory)) {
            return false;
        }
        TransactionCategory that = (TransactionCategory) o;
        return id != null && id.equals(that.id);
    }

    /**
     * Hash code derived from the composite primary key {@link #id} (0 when {@code id} is
     * {@code null}), consistent with {@link #equals(Object)}.
     */
    @Override
    public int hashCode() {
        return id != null ? id.hashCode() : 0;
    }
}
