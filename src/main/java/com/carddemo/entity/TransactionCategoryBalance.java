package com.carddemo.entity;

import jakarta.persistence.Column;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

import java.math.BigDecimal;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * JPA entity for per-account, per-type, per-category running balances.
 *
 * <p>Maps the 50-byte {@code TRAN-CAT-BAL-RECORD} from {@code app/cpy/CVTRA01Y.cpy}
 * (CardDemo_v1.0-15-g27d6c6f-68):</p>
 * <pre>
 *   01 TRAN-CAT-BAL-RECORD.
 *     05 TRAN-CAT-KEY.
 *       10 TRANCAT-ACCT-ID  PIC 9(11).      --&gt; id.accountId  (account_id BIGINT)
 *       10 TRANCAT-TYPE-CD  PIC X(02).      --&gt; id.typeCd     (type_cd    CHAR(2))
 *       10 TRANCAT-CD       PIC 9(04).      --&gt; id.categoryCd (cat_cd     CHAR(4))
 *     05 TRAN-CAT-BAL       PIC S9(09)V99.  --&gt; tranCatBal    (balance    NUMERIC(15,2))
 *     05 FILLER             PIC X(22).      --&gt; NOT mapped (reserved padding)
 * </pre>
 *
 * <p>The composite primary key {@code (account_id, type_cd, cat_cd)} is modelled with
 * {@link TransactionCategoryBalanceId} through {@code @EmbeddedId} (PR-15), preserving the
 * COBOL {@code TRAN-CAT-KEY} group field order. {@code account_id} is a foreign-key-by-value
 * to {@code accounts.acct_id}; the type-code and category-code components are
 * foreign-keys-by-value to {@link TransactionType} / {@link TransactionCategory}. No JPA
 * association is modelled because the original COBOL design uses bare key fields
 * (AAP &sect;0.7.2 &mdash; "no data model redesign").</p>
 *
 * <p>This is the central balance-tracking table; unlike the immutable lookup tables it is
 * actively mutated by two critical batch programs:</p>
 * <ul>
 *   <li><strong>CBTRN02C 2700-UPDATE-TCATBAL</strong> [app/cbl/CBTRN02C.cbl:L467-L501] &mdash;
 *       PR-06 upsert pattern: on a missing row create one with the composite key and
 *       {@code balance = DALYTRAN-AMT}; on a found row UPDATE with
 *       {@code balance += DALYTRAN-AMT}. The upsert is performed by the downstream
 *       {@code TransactionCategoryBalanceUpsertWriter} via
 *       {@code existsById(id) ? update : insert}.</li>
 *   <li><strong>CBACT04C interest calculation</strong> [app/cbl/CBACT04C.cbl:L462-L470] &mdash;
 *       reads {@link #tranCatBal} as the {@code TRAN-CAT-BAL} operand of the preserved
 *       monthly-interest formula
 *       {@code monthlyInt = (TRAN-CAT-BAL * DIS-INT-RATE) / 1200}, evaluated in Java as
 *       {@code tranCatBal.multiply(disIntRate).divide(BigDecimal.valueOf(1200), 2,
 *       RoundingMode.HALF_UP)} (PR-01).</li>
 * </ul>
 *
 * <p>The 100 standard transaction-category-balance rows are seeded once from
 * {@code app/data/ASCII/tcatbal.txt} via Flyway {@code V5__seed_master_data.sql}.</p>
 *
 * <p><strong>Money (PR-16).</strong> The single monetary field {@link #tranCatBal} maps the
 * COBOL {@code PIC S9(09)V99} packed-decimal field to {@link BigDecimal} with scale 2
 * ({@code NUMERIC(15,2)}). {@code float}/{@code double} are forbidden for any monetary value;
 * all arithmetic on this field uses {@code RoundingMode.HALF_UP} via {@code BigDecimalUtil},
 * and comparisons use {@link BigDecimal#compareTo(BigDecimal)} (never {@code equals}).</p>
 *
 * <p><strong>Schema conformance (mandatory).</strong> Every mapping below mirrors the
 * committed Flyway DDL {@code src/main/resources/db/migration/V1__schema.sql}
 * (table {@code tran_cat_balances}, Section 8) exactly. The {@code dev}, {@code prod}, and
 * {@code test} profiles all run Hibernate with {@code ddl-auto: validate}, so any divergence
 * in column name, SQL type, or precision/scale would prevent the Spring application context
 * from starting. The committed table declares precisely four columns &mdash;
 * {@code account_id BIGINT}, {@code type_cd CHAR(2)}, {@code cat_cd CHAR(4)} (the three key
 * columns mapped inside {@link TransactionCategoryBalanceId}) and
 * {@code balance NUMERIC(15,2) NOT NULL DEFAULT 0.00} (mapped here as {@link #tranCatBal}).
 * The monetary field therefore binds to the physical column {@code balance} (not
 * {@code tran_cat_bal}); the Java field retains the {@code tranCatBal} name so its accessors
 * read {@code getTranCatBal()} / {@code setTranCatBal()} in line with the COBOL
 * {@code TRAN-CAT-BAL} field. The 22 {@code FILLER} bytes are reserved padding and are
 * intentionally not mapped to a Java field (PR-13). Persistence annotations use the
 * {@code jakarta.*} namespace (PR-28).</p>
 *
 * <p><strong>No optimistic locking / no auditing.</strong> Consistent with the committed DDL
 * (which declares no {@code version} or audit columns on {@code tran_cat_balances}) and with
 * AAP &sect;0.3.3 &mdash; which restricts the {@code @Version} optimistic-locking pattern to
 * {@code Account}, {@code Card}, {@code Customer}, and {@code Transaction} &mdash; this entity
 * carries no {@code @Version} field and is not registered with the JPA
 * {@code AuditingEntityListener}. This matches the sibling composite-key reference entities
 * {@link DisclosureGroup} and {@link TransactionCategory}. Lost-update safety for the
 * concurrent CBTRN02C / CBACT04C balance upserts is instead provided at the service layer by
 * the surrounding {@code @Transactional} boundary (PR-24) under the default
 * {@code READ_COMMITTED} isolation; adding non-existent mapped columns here would fail
 * {@code ddl-auto: validate} and break context startup for every integration test.</p>
 *
 * <p>Identity-based {@link #equals(Object)} / {@link #hashCode()} derive from the composite
 * primary key {@link #id} alone &mdash; hand-written rather than Lombok {@code @Data} /
 * {@code @EqualsAndHashCode} to avoid the well-known JPA proxy/lazy-loading pitfalls of
 * all-field equality on entities.</p>
 *
 * @see TransactionCategoryBalanceId
 * @see TransactionType
 * @see TransactionCategory
 */
@Entity
@Table(name = "tran_cat_balances")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class TransactionCategoryBalance {

    /**
     * Composite primary key: account ID + transaction type code + transaction category code.
     * Maps the COBOL {@code TRAN-CAT-KEY} group ({@code TRANCAT-ACCT-ID PIC 9(11)} +
     * {@code TRANCAT-TYPE-CD PIC X(02)} + {@code TRANCAT-CD PIC 9(04)}). The physical key
     * columns ({@code account_id BIGINT}, {@code type_cd CHAR(2)}, {@code cat_cd CHAR(4)})
     * and their JDBC bindings are declared inside {@link TransactionCategoryBalanceId}, so no
     * {@code @Column} mapping is needed here (PR-15).
     */
    @EmbeddedId
    private TransactionCategoryBalanceId id;

    /**
     * Running balance for this {@code account + type + category}. Maps COBOL
     * {@code TRAN-CAT-BAL PIC S9(09)V99} &rarr; the {@code balance NUMERIC(15,2) NOT NULL}
     * column. Modelled as {@link BigDecimal} (PR-16 &mdash; {@code float}/{@code double}
     * forbidden for money) with {@code precision = 15}, {@code scale = 2} (PR-13).
     *
     * <p>Maintained by the {@code CBTRN02C} TCATBAL upsert (PR-06: created with
     * {@code balance = DALYTRAN-AMT}, otherwise {@code balance += DALYTRAN-AMT}) and read by
     * the {@code CBACT04C} interest calculation as the {@code TRAN-CAT-BAL} operand of the
     * preserved {@code (TRAN-CAT-BAL * DIS-INT-RATE) / 1200} formula (PR-01).</p>
     */
    @Column(name = "balance", precision = 15, scale = 2, nullable = false)
    private BigDecimal tranCatBal;

    // -------------------- equals / hashCode (composite-key identity) --------------------

    /**
     * Identity equality based solely on the composite primary key {@link #id}. Two
     * {@code TransactionCategoryBalance} instances are equal only when both have a non-null,
     * equal {@code id}. Transient (unsaved) instances with a {@code null} id are therefore
     * never equal to one another, which is the recommended equality contract for JPA entities
     * with an assigned (non-generated) identifier.
     */
    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof TransactionCategoryBalance)) {
            return false;
        }
        TransactionCategoryBalance that = (TransactionCategoryBalance) o;
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
