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
 * JPA entity for disclosure-group reference data containing annual interest rates.
 *
 * <p>Maps the 50-byte {@code DIS-GROUP-RECORD} from {@code app/cpy/CVTRA02Y.cpy}
 * (CardDemo_v1.0-15-g27d6c6f-68):</p>
 * <pre>
 *   01 DIS-GROUP-RECORD.
 *     05 DIS-GROUP-KEY.
 *       10 DIS-ACCT-GROUP-ID  PIC X(10).      --&gt; id.accountGroupId (group_id     VARCHAR(10))
 *       10 DIS-TRAN-TYPE-CD   PIC X(02).      --&gt; id.tranTypeCd     (type_cd      CHAR(2))
 *       10 DIS-TRAN-CAT-CD    PIC 9(04).      --&gt; id.tranCatCd      (cat_cd       CHAR(4))
 *     05 DIS-INT-RATE         PIC S9(04)V99.  --&gt; disIntRate        (dis_int_rate NUMERIC(6,2))
 *     05 FILLER               PIC X(28).      --&gt; NOT mapped (reserved padding)
 * </pre>
 *
 * <p>The composite primary key {@code (group_id, type_cd, cat_cd)} is modelled with
 * {@link DisclosureGroupId} through {@code @EmbeddedId} (PR-15), preserving the COBOL
 * {@code DIS-GROUP-KEY} group field order. The transaction type-code and category-code
 * components are foreign-keys-by-value to {@link TransactionType} and
 * {@link TransactionCategory}; no JPA association is modelled because the original COBOL
 * design uses bare key fields (AAP &sect;0.7.2 &mdash; "no data model redesign").</p>
 *
 * <p><strong>Used by CBACT04C interest calculation</strong> (AAP &sect;0.6.11):</p>
 * <ul>
 *   <li>The {@link #disIntRate} field provides the annual interest rate used in the
 *       preserved formula
 *       {@code monthlyInt = (TRAN-CAT-BAL * DIS-INT-RATE) / 1200}, evaluated in Java as
 *       {@code tranCatBal.multiply(disIntRate).divide(BigDecimal.valueOf(1200), 2,
 *       RoundingMode.HALF_UP)} with scale&nbsp;2 and {@code HALF_UP} rounding (PR-01).</li>
 *   <li><strong>DEFAULT fallback (PR-02).</strong> If no row matches
 *       {@code (accountGroupId + tranTypeCd + tranCatCd)}, the
 *       {@code InterestCalculationTasklet} retries the lookup with
 *       {@code accountGroupId = "DEFAULT"}; if still missing it raises
 *       {@code DiscloseGroupNotFoundException}. That fallback logic lives in the service
 *       layer, not in this entity &mdash; this class only models the persisted row.</li>
 * </ul>
 *
 * <p>The 51 standard disclosure groups (including the {@code DEFAULT} fallback rows) are
 * seeded once from {@code app/data/ASCII/discgrp.txt} via Flyway
 * {@code V3__seed_reference_data.sql}.</p>
 *
 * <p><strong>No optimistic locking / no auditing.</strong> Like the other lookup tables,
 * this reference entity carries no {@code @Version} column and is not registered with the
 * JPA {@code AuditingEntityListener}: rows are seeded once and are not subject to
 * concurrent modification (AAP &sect;0.3.3 restricts {@code @Version} to
 * {@code Account}/{@code Card}/{@code Customer}/{@code Transaction}; the committed
 * {@code disclosure_groups} DDL declares no audit/version columns).</p>
 *
 * <p><strong>Schema conformance (mandatory).</strong> Every mapping below mirrors the
 * committed Flyway DDL {@code src/main/resources/db/migration/V1__schema.sql}
 * (table {@code disclosure_groups}) exactly. The {@code dev}, {@code prod}, and
 * {@code test} profiles all run Hibernate with {@code ddl-auto: validate}, so any
 * divergence in column name, SQL type, length, or precision/scale would prevent the
 * Spring application context from starting. The {@code dis_int_rate} column is declared
 * {@code NUMERIC(6,2) NOT NULL}: it maps to {@link BigDecimal} (never {@code float} or
 * {@code double}, per PR-16) with {@code precision = 6} and {@code scale = 2} mirroring
 * the COBOL {@code PIC S9(04)V99} clause &mdash; four integer digits plus two decimal
 * digits give a total precision of six, distinct from the {@code NUMERIC(15,2)} monetary
 * fields because an interest rate is a percentage, not a money amount (PR-13). Persistence
 * annotations use the {@code jakarta.*} namespace (PR-28). The 28 {@code FILLER} bytes are
 * reserved padding and are intentionally not mapped to a Java field.</p>
 *
 * <p>Identity-based {@link #equals(Object)} / {@link #hashCode()} derive from the
 * composite primary key {@link #id} alone &mdash; hand-written rather than Lombok
 * {@code @Data} / {@code @EqualsAndHashCode} to avoid the well-known JPA
 * proxy/lazy-loading pitfalls of all-field equality on entities.</p>
 *
 * @see DisclosureGroupId
 * @see TransactionType
 * @see TransactionCategory
 */
@Entity
@Table(name = "disclosure_groups")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class DisclosureGroup {

    /**
     * Composite primary key: account group ID + transaction type code + transaction
     * category code. Maps the COBOL {@code DIS-GROUP-KEY} group
     * ({@code DIS-ACCT-GROUP-ID PIC X(10)} + {@code DIS-TRAN-TYPE-CD PIC X(02)} +
     * {@code DIS-TRAN-CAT-CD PIC 9(04)}). The physical key columns
     * ({@code group_id VARCHAR(10)}, {@code type_cd CHAR(2)}, {@code cat_cd CHAR(4)})
     * and their JDBC bindings are declared inside {@link DisclosureGroupId}, so no
     * {@code @Column} mapping is needed here (PR-15).
     *
     * <p>The {@code accountGroupId} component supports the special value
     * {@code "DEFAULT"} used as the interest-rate fallback lookup per AAP &sect;0.6.11.</p>
     */
    @EmbeddedId
    private DisclosureGroupId id;

    /**
     * Disclosure-group interest rate (annual percentage). Maps COBOL
     * {@code DIS-INT-RATE PIC S9(04)V99} &rarr; {@code dis_int_rate NUMERIC(6,2) NOT NULL}.
     * Modelled as {@link BigDecimal} (PR-16 &mdash; {@code float}/{@code double} forbidden
     * for money/rate fields) with {@code precision = 6}, {@code scale = 2} (PR-13).
     *
     * <p>Consumed by the {@code CBACT04C} interest calculation
     * ({@code InterestCalculationTasklet}) in the preserved formula
     * {@code monthlyInt = (tranCatBal * disIntRate) / 1200} with scale&nbsp;2 and
     * {@code RoundingMode.HALF_UP} (PR-01).</p>
     */
    @Column(name = "dis_int_rate", precision = 6, scale = 2, nullable = false)
    private BigDecimal disIntRate;

    // -------------------- equals / hashCode (composite-key identity) --------------------

    /**
     * Identity equality based solely on the composite primary key {@link #id}. Two
     * {@code DisclosureGroup} instances are equal only when both have a non-null, equal
     * {@code id}. Transient (unsaved) instances with a {@code null} id are therefore never
     * equal to one another, which is the recommended equality contract for JPA entities
     * with an assigned (non-generated) identifier.
     */
    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof DisclosureGroup)) {
            return false;
        }
        DisclosureGroup that = (DisclosureGroup) o;
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
