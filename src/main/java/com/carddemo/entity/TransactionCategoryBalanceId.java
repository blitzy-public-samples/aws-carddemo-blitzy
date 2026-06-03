package com.carddemo.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;

import java.io.Serializable;
import java.sql.Types;

import org.hibernate.annotations.JdbcTypeCode;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * Composite primary key for {@link TransactionCategoryBalance}.
 *
 * <p>Mirrors the {@code TRAN-CAT-KEY} group of the 50-byte {@code TRAN-CAT-BAL-RECORD}
 * defined in {@code app/cpy/CVTRA01Y.cpy} (CardDemo_v1.0-15-g27d6c6f-68), lines 5-8:</p>
 * <pre>
 *   05 TRAN-CAT-KEY.
 *     10 TRANCAT-ACCT-ID  PIC 9(11).   --&gt; accountId  (account_id BIGINT)
 *     10 TRANCAT-TYPE-CD  PIC X(02).   --&gt; typeCd     (type_cd    CHAR(2))
 *     10 TRANCAT-CD       PIC 9(04).   --&gt; categoryCd (cat_cd     CHAR(4))
 * </pre>
 *
 * <p>Field ordering matches the COBOL key concatenation order required by PR-15:
 * {@code account_id} then {@code type_cd} then {@code cat_cd}. This {@code @Embeddable}
 * is referenced by {@link TransactionCategoryBalance} through {@code @EmbeddedId}, so the
 * {@code @Column} mappings declared here define the physical primary-key columns of the
 * {@code tran_cat_balances} table. The {@code (account_id, type_cd, cat_cd)} key is read
 * and maintained by the CBTRN02C TCATBAL upsert (PR-06) and the CBACT04C interest run.</p>
 *
 * <p><strong>Schema conformance (mandatory).</strong> The committed Flyway DDL
 * {@code src/main/resources/db/migration/V1__schema.sql} (Section 8) declares the key
 * columns as {@code account_id BIGINT NOT NULL}, {@code type_cd CHAR(2) NOT NULL}, and
 * {@code cat_cd CHAR(4) NOT NULL}, with {@code PRIMARY KEY (account_id, type_cd, cat_cd)}.
 * All persistence profiles (dev/prod/test) run Hibernate with {@code ddl-auto: validate},
 * so every mapping below mirrors that DDL exactly &mdash; any divergence in column name,
 * SQL type, or length would prevent the Spring application context from starting. In
 * particular:</p>
 * <ul>
 *   <li>{@code accountId} maps to {@code account_id BIGINT NOT NULL}; the COBOL
 *       {@code PIC 9(11)} (eleven digits) exceeds the {@code int} range, so a 64-bit
 *       {@link Long} is used (BIGINT) and no {@code @JdbcTypeCode} override is required.
 *       The value is a foreign-key-by-value to {@code accounts.acct_id}.</li>
 *   <li>The transaction category code maps to the physical column {@code cat_cd} (not
 *       {@code category_cd}) and is held as a fixed-width {@code String} of length 4
 *       (rather than a numeric type) to preserve any leading zeros of the COBOL
 *       {@code PIC 9(04)} value (e.g. {@code "0001"}), consistent with the sibling
 *       {@link TransactionCategoryId}, {@link DisclosureGroupId}, {@link Transaction},
 *       {@link DailyTransaction}, and {@link RejectedTransaction} entities that map the
 *       same {@code (type_cd, cat_cd)} columns.</li>
 *   <li>Both {@code typeCd} and {@code categoryCd} carry {@code @JdbcTypeCode(Types.CHAR)}
 *       to force the fixed-width {@code CHAR} JDBC binding; a plain {@code String} resolves
 *       to {@code VARCHAR} and would fail {@code ddl-auto: validate} against the committed
 *       {@code CHAR(2)} / {@code CHAR(4)} columns. This mirrors the sibling
 *       {@link TransactionCategoryId} and {@link DisclosureGroupId} which map the identical
 *       {@code (type_cd, cat_cd)} columns the same way.</li>
 * </ul>
 *
 * <p><strong>JPA composite-key contract.</strong> As required by the JPA specification
 * for {@code @EmbeddedId} key types, this class implements {@link Serializable}, exposes
 * a public no-argument constructor (Lombok {@code @NoArgsConstructor}), and provides
 * {@code equals(Object)} / {@code hashCode()} derived from <em>all</em> key fields (Lombok
 * {@code @EqualsAndHashCode}). All-field value equality is the correct contract for an
 * embeddable identifier (unlike a JPA entity, whose identity should derive from its
 * primary key alone). Persistence annotations use the {@code jakarta.*} namespace
 * (PR-28); column widths mirror the COBOL {@code PIC} clauses (PR-13).</p>
 *
 * @see TransactionCategoryBalance
 * @see TransactionCategoryId
 * @see DisclosureGroupId
 */
@Embeddable
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@EqualsAndHashCode
public class TransactionCategoryBalanceId implements Serializable {

    /** Serialization version identifier for this JPA composite-key class. */
    private static final long serialVersionUID = 1L;

    /**
     * Account ID &mdash; first component of the composite key. Maps COBOL
     * {@code TRANCAT-ACCT-ID PIC 9(11)} &rarr; {@code account_id BIGINT NOT NULL} and is a
     * foreign-key-by-value to {@code accounts.acct_id}. The eleven-digit COBOL value
     * exceeds the 32-bit {@code int} range, so it is held as a 64-bit {@link Long}
     * (BIGINT); no {@code @JdbcTypeCode} override is needed for the numeric binding.
     */
    @Column(name = "account_id", nullable = false)
    private Long accountId;

    /**
     * Transaction type code &mdash; second component of the composite key. Maps COBOL
     * {@code TRANCAT-TYPE-CD PIC X(02)} &rarr; {@code type_cd CHAR(2) NOT NULL} and is a
     * foreign-key-by-value to {@code transaction_types.tran_type}.
     * {@code @JdbcTypeCode(Types.CHAR)} forces the fixed-width {@code CHAR} binding so
     * Hibernate {@code ddl-auto: validate} matches the committed {@code CHAR(2)} column
     * (a plain {@code String} resolves to {@code VARCHAR} and would fail validation).
     */
    @JdbcTypeCode(Types.CHAR)
    @Column(name = "type_cd", length = 2, nullable = false)
    private String typeCd;

    /**
     * Transaction category code &mdash; third component of the composite key. Maps COBOL
     * {@code TRANCAT-CD PIC 9(04)} &rarr; {@code cat_cd CHAR(4) NOT NULL}. Held as a
     * fixed-width {@code String} (rather than a numeric type) to preserve any leading
     * zeros of the four-digit code (e.g. {@code "0001"}) and to match the committed DDL
     * and the sibling {@link TransactionCategoryId} / {@link DisclosureGroupId} /
     * {@link Transaction} / {@link DailyTransaction} / {@link RejectedTransaction}
     * entities that map the same {@code cat_cd} column. {@code @JdbcTypeCode(Types.CHAR)}
     * forces the fixed-width {@code CHAR} binding required by {@code ddl-auto: validate}
     * against the {@code CHAR(4)} column.
     */
    @JdbcTypeCode(Types.CHAR)
    @Column(name = "cat_cd", length = 4, nullable = false)
    private String categoryCd;
}
