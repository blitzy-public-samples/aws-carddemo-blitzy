package com.carddemo.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.sql.Types;

import org.hibernate.annotations.JdbcTypeCode;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * JPA entity for transaction-type reference data.
 *
 * <p>Maps the 60-byte {@code TRAN-TYPE-RECORD} from {@code app/cpy/CVTRA03Y.cpy}
 * (CardDemo_v1.0-15-g27d6c6f-68):</p>
 * <pre>
 *   01 TRAN-TYPE-RECORD.
 *     05 TRAN-TYPE        PIC X(02).   --&gt; typeCd   (primary key, length 2)
 *     05 TRAN-TYPE-DESC   PIC X(50).   --&gt; typeDesc (length 50)
 *     05 FILLER           PIC X(08).   --&gt; NOT mapped (reserved padding)
 * </pre>
 *
 * <p>Immutable reference/lookup data: the seven standard transaction types
 * (<em>01 Purchase, 02 Payment, 03 Credit, 04 Authorization, 05 Refund,
 * 06 Reversal, 07 Adjustment</em>) are seeded from
 * {@code app/data/ASCII/trantype.txt}. The type code is referenced as a
 * foreign-key-by-value from {@link Transaction} ({@code transactions.type_cd}) and
 * {@link TransactionCategory} ({@code transaction_categories.type_cd}); no JPA
 * association is modelled because the original COBOL design uses bare key fields
 * (AAP &sect;0.7.2 &mdash; "no data model redesign").</p>
 *
 * <p><strong>No optimistic locking / no auditing.</strong> Unlike {@link Account},
 * {@link Card}, {@link Customer}, and {@link Transaction}, this lookup table carries
 * no {@code @Version} column and is not registered with the JPA
 * {@code AuditingEntityListener}: the rows are seeded once and are not subject to
 * concurrent modification (AAP &sect;0.3.3 restricts {@code @Version} to
 * {@code Account}/{@code Card}/{@code Customer}/{@code Transaction}; the committed
 * {@code transaction_types} DDL declares no audit/version columns).</p>
 *
 * <p><strong>Schema conformance.</strong> Every {@code @Column} mapping below mirrors
 * the committed Flyway DDL {@code src/main/resources/db/migration/V1__schema.sql}
 * (table {@code transaction_types}) exactly. This is mandatory: the {@code dev},
 * {@code prod}, and {@code test} profiles all run Hibernate with
 * {@code ddl-auto: validate}, so any divergence in column name, SQL type, or length
 * would prevent the Spring context from starting. The physical primary-key column
 * retains its COBOL-derived name {@code tran_type} while the Java field uses the
 * conventional camelCase name {@code typeCd} (which also backs the schema-declared
 * {@code getTypeCd()} / {@code setTypeCd(String)} accessors). The {@code PIC X(02)}
 * type code maps to a fixed-width {@code CHAR(2)}; {@code @JdbcTypeCode(Types.CHAR)}
 * forces the {@code CHAR} JDBC binding (a plain {@code String} resolves to
 * {@code VARCHAR} and would fail validation against the committed schema). Column
 * lengths mirror the COBOL {@code PIC} clauses (PR-13) and persistence annotations
 * use the {@code jakarta.*} namespace (PR-28).</p>
 *
 * <p>Identity-based {@link #equals(Object)} / {@link #hashCode()} derive from the
 * natural primary key {@code typeCd} alone &mdash; hand-written rather than Lombok
 * {@code @Data} / {@code @EqualsAndHashCode} to avoid the well-known JPA
 * proxy/lazy-loading pitfalls of all-field equality.</p>
 *
 * @see TransactionCategory
 * @see Transaction
 */
@Entity
@Table(name = "transaction_types")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class TransactionType {

    /**
     * Transaction type code &mdash; the natural primary key. Maps COBOL
     * {@code TRAN-TYPE PIC X(02)} &rarr; {@code tran_type CHAR(2) NOT NULL PRIMARY KEY}
     * (e.g. {@code "01"} Purchase, {@code "02"} Payment, {@code "03"} Credit).
     * {@code @JdbcTypeCode(Types.CHAR)} forces the fixed-width {@code CHAR} binding
     * required by {@code ddl-auto: validate} (a plain {@code String} resolves to
     * {@code VARCHAR} and would fail validation against the committed schema). The
     * Java field name {@code typeCd} backs the {@code getTypeCd()} /
     * {@code setTypeCd(String)} accessors while the physical column keeps its
     * COBOL-derived name {@code tran_type}.
     */
    @Id
    @JdbcTypeCode(Types.CHAR)
    @Column(name = "tran_type", length = 2, nullable = false)
    private String typeCd;

    /**
     * Human-readable transaction type description. Maps COBOL
     * {@code TRAN-TYPE-DESC PIC X(50)} &rarr; {@code type_desc VARCHAR(50) NOT NULL}.
     * Held as a variable-length {@code String}; Hibernate's default {@code VARCHAR}
     * binding matches the committed DDL, so no {@code @JdbcTypeCode} override is needed.
     */
    @Column(name = "type_desc", length = 50, nullable = false)
    private String typeDesc;

    // -------------------- equals / hashCode (natural-key identity) --------------------

    /**
     * Identity equality based solely on the natural primary key {@link #typeCd}. Two
     * {@code TransactionType} instances are equal only when both have a non-null, equal
     * {@code typeCd}. Transient (unsaved) instances with a {@code null} code are therefore
     * never equal to one another, which is the recommended equality contract for JPA
     * entities with an assigned (non-generated) identifier.
     */
    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof TransactionType)) {
            return false;
        }
        TransactionType that = (TransactionType) o;
        return typeCd != null && typeCd.equals(that.typeCd);
    }

    /**
     * Hash code derived from the natural primary key {@link #typeCd} (0 when
     * {@code typeCd} is {@code null}), consistent with {@link #equals(Object)}.
     */
    @Override
    public int hashCode() {
        return typeCd != null ? typeCd.hashCode() : 0;
    }
}
