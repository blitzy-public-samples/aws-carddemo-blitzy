package com.carddemo.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.io.Serializable;
import java.math.BigDecimal;
import java.util.Objects;

/**
 * JPA entity mapping the legacy VSAM {@code DISCGRP} KSDS dataset (copybook
 * {@code app/cpy/CVTRA02Y.cpy}, {@code DIS-GROUP-RECORD}, RECLN = 50) to the relational table
 * {@code disclosure_group}. The dataset is the <strong>interest-disclosure rate table</strong>:
 * it carries the per-group, per-transaction-type, per-transaction-category interest rate that the
 * interest-calculation batch program {@code app/cbl/CBACT04C.cbl} reads to compute monthly
 * interest, falling back to the {@code 'DEFAULT'} group when an account's specific group has no
 * matching row (AAP &sect;0.6.3).
 *
 * <p>This is a <strong>tier-0 foundational</strong> entity: a standalone lookup table with a
 * three-column composite primary key and <strong>no foreign keys and no associations</strong>.
 * Its {@code (tran_type_cd, tran_cat_cd)} pairs are deliberately <em>not</em> FK-constrained
 * against {@code transaction_category}, mirroring the application-enforced (rather than
 * declaratively-enforced) integrity of the original VSAM design. Because it imports nothing from
 * the {@code com.carddemo} tree, it can never participate in a cyclic dependency.</p>
 *
 * <h2>Record layout (CVTRA02Y.cpy &mdash; RECLN = 50)</h2>
 * <pre>
 *   COBOL field                 PIC           Java field   Column         SQL type
 *   -------------------------   -----------   ----------   ------------   -------------
 *   DIS-GROUP-KEY.
 *     DIS-ACCT-GROUP-ID         X(10)         groupId      group_id       VARCHAR(10)   (PK)
 *     DIS-TRAN-TYPE-CD          X(02)         tranTypeCd   tran_type_cd   CHAR(2)       (PK)
 *     DIS-TRAN-CAT-CD           9(04)         tranCatCd    tran_cat_cd    INTEGER       (PK)
 *   DIS-INT-RATE                S9(04)V99     disIntRate   dis_int_rate   NUMERIC(6,2)
 *   FILLER                      X(28)         &mdash;            &mdash;              (not persisted)
 *   -------------------------   -----------   ----------   ------------   -------------
 *   10 + 2 + 4 + 6 (stored) + 28 (filler) = 50 bytes; key length = 10 + 2 + 4 = 16
 * </pre>
 *
 * <p>The 16-byte composite key and 50-byte record length are corroborated by the VSAM catalog
 * listing {@code app/catlg/LISTCAT.txt} for the {@code DISCGRP} cluster
 * ({@code KEYLEN = 16}, {@code MAXLRECL = 50}).</p>
 *
 * <h2>Binding schema contract</h2>
 * <p>Hibernate boots with {@code spring.jpa.hibernate.ddl-auto=validate}, so this mapping is
 * validated against the Flyway-authoritative DDL in
 * {@code src/main/resources/db/migration/V1__schema.sql}:</p>
 * <pre>
 *   CREATE TABLE disclosure_group (
 *       group_id     VARCHAR(10) NOT NULL,
 *       tran_type_cd CHAR(2)     NOT NULL,
 *       tran_cat_cd  INTEGER     NOT NULL,
 *       dis_int_rate NUMERIC(6,2),
 *       CONSTRAINT pk_disclosure_group PRIMARY KEY (group_id, tran_type_cd, tran_cat_cd)
 *   );
 * </pre>
 * <p>The composite-key column names {@code tran_type_cd} / {@code tran_cat_cd} are intentionally
 * <strong>different</strong> from {@code transaction_category}'s {@code type_cd} / {@code cat_cd}
 * and are reproduced exactly. {@code dis_int_rate} is modelled as a {@link BigDecimal} with
 * {@code precision = 6, scale = 2} to preserve the fixed-point semantics of the COBOL
 * {@code S9(04)V99} field; monetary/rate arithmetic with {@code RoundingMode.HALF_UP} is performed
 * in the service layer ({@code InterestCalculationService}), never in this entity.</p>
 *
 * <h2>Repository contract (downstream)</h2>
 * <p>Spring Data repositories reference this entity as
 * {@code JpaRepository<DisclosureGroup, DisclosureGroup.DisclosureGroupId>}. The embedded id type
 * is therefore declared {@code public static} so it is freely referenceable, and the interest
 * service can perform both a keyed lookup and a {@code 'DEFAULT'}-group fallback.</p>
 *
 * @see DisclosureGroupId
 * @see <a href="file:app/cpy/CVTRA02Y.cpy">CVTRA02Y.cpy</a>
 * @see <a href="file:app/cbl/CBACT04C.cbl">CBACT04C.cbl</a>
 * @see <a href="file:app/catlg/LISTCAT.txt">LISTCAT.txt</a>
 */
@Entity
@Table(name = "disclosure_group")
public class DisclosureGroup {

    /**
     * Three-part composite primary key mapping the COBOL {@code DIS-GROUP-KEY}
     * ({@code group_id}, {@code tran_type_cd}, {@code tran_cat_cd}).
     */
    @EmbeddedId
    private DisclosureGroupId id;

    /**
     * Interest disclosure rate, COBOL {@code DIS-INT-RATE PIC S9(04)V99}, mapped to
     * {@code dis_int_rate NUMERIC(6,2)}. Held as {@link BigDecimal} (never {@code double}/
     * {@code float}) so the service layer can reproduce COBOL fixed-point interest arithmetic
     * exactly. Nullable, matching the DDL (no {@code NOT NULL} on this column).
     */
    @Column(name = "dis_int_rate", precision = 6, scale = 2)
    private BigDecimal disIntRate;

    /**
     * JPA-required no-argument constructor.
     */
    public DisclosureGroup() {
        // Required by JPA / Hibernate for entity instantiation.
    }

    /**
     * Convenience constructor.
     *
     * @param id         the composite primary key (group, transaction type, transaction category)
     * @param disIntRate the interest disclosure rate; may be {@code null}
     */
    public DisclosureGroup(DisclosureGroupId id, BigDecimal disIntRate) {
        this.id = id;
        this.disIntRate = disIntRate;
    }

    /**
     * Returns the composite primary key.
     *
     * @return the embedded id
     */
    public DisclosureGroupId getId() {
        return id;
    }

    /**
     * Sets the composite primary key.
     *
     * @param id the embedded id
     */
    public void setId(DisclosureGroupId id) {
        this.id = id;
    }

    /**
     * Returns the interest disclosure rate.
     *
     * @return the rate as a {@link BigDecimal}, or {@code null} if unset
     */
    public BigDecimal getDisIntRate() {
        return disIntRate;
    }

    /**
     * Sets the interest disclosure rate.
     *
     * @param disIntRate the rate as a {@link BigDecimal}; may be {@code null}
     */
    public void setDisIntRate(BigDecimal disIntRate) {
        this.disIntRate = disIntRate;
    }

    /**
     * Entity equality is defined by the composite primary key, per JPA best practice.
     *
     * @param o the object to compare with
     * @return {@code true} if the other object is a {@code DisclosureGroup} with an equal id
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
        return Objects.equals(id, that.id);
    }

    /**
     * Hash code derived from the composite primary key.
     *
     * @return the id-based hash code
     */
    @Override
    public int hashCode() {
        return Objects.hashCode(id);
    }

    /**
     * Diagnostic representation. Contains only non-sensitive reference data (group/type/category
     * codes and the disclosure rate); no PII is involved.
     *
     * @return a string representation of this disclosure-group row
     */
    @Override
    public String toString() {
        return "DisclosureGroup{id=" + id + ", disIntRate=" + disIntRate + '}';
    }

    /**
     * Embeddable composite primary key for {@link DisclosureGroup}, mapping the COBOL
     * {@code DIS-GROUP-KEY} group field. Declared {@code public static} so Spring Data
     * repositories and the interest service can reference it as
     * {@code DisclosureGroup.DisclosureGroupId}.
     *
     * <p>Implements {@link Serializable} and provides value-based {@link #equals(Object)} /
     * {@link #hashCode()} over all three key columns, as JPA requires for composite ids.</p>
     */
    @Embeddable
    public static class DisclosureGroupId implements Serializable {

        private static final long serialVersionUID = 1L;

        /**
         * COBOL {@code DIS-ACCT-GROUP-ID PIC X(10)} &rarr; {@code group_id VARCHAR(10)}.
         * Examples: {@code 'DEFAULT'}, {@code 'A000000000'}, {@code 'ZEROAPR'}. Variable-length
         * character, so no {@link JdbcTypeCode} override is applied (default VARCHAR mapping).
         */
        @Column(name = "group_id", length = 10)
        private String groupId;

        /**
         * COBOL {@code DIS-TRAN-TYPE-CD PIC X(02)} &rarr; {@code tran_type_cd CHAR(2)}.
         * The {@link JdbcTypeCode}{@code (SqlTypes.CHAR)} override forces the fixed-length
         * {@code CHAR} JDBC type (code 1) so Hibernate {@code validate} matches the {@code CHAR(2)}
         * column exactly rather than defaulting to {@code VARCHAR}.
         */
        @Column(name = "tran_type_cd", length = 2)
        @JdbcTypeCode(SqlTypes.CHAR)
        private String tranTypeCd;

        /**
         * COBOL {@code DIS-TRAN-CAT-CD PIC 9(04)} &rarr; {@code tran_cat_cd INTEGER}. The numeric
         * COBOL field is stored as an {@link Integer} (leading zeros are not significant), matching
         * the relational {@code INTEGER} column.
         */
        @Column(name = "tran_cat_cd")
        private Integer tranCatCd;

        /**
         * JPA-required no-argument constructor.
         */
        public DisclosureGroupId() {
            // Required by JPA / Hibernate for embeddable instantiation.
        }

        /**
         * Constructs a fully-populated composite key.
         *
         * @param groupId    the account group id ({@code group_id})
         * @param tranTypeCd the transaction type code ({@code tran_type_cd})
         * @param tranCatCd  the transaction category code ({@code tran_cat_cd})
         */
        public DisclosureGroupId(String groupId, String tranTypeCd, Integer tranCatCd) {
            this.groupId = groupId;
            this.tranTypeCd = tranTypeCd;
            this.tranCatCd = tranCatCd;
        }

        /**
         * Returns the account group id.
         *
         * @return the {@code group_id} key part
         */
        public String getGroupId() {
            return groupId;
        }

        /**
         * Sets the account group id.
         *
         * @param groupId the {@code group_id} key part
         */
        public void setGroupId(String groupId) {
            this.groupId = groupId;
        }

        /**
         * Returns the transaction type code.
         *
         * @return the {@code tran_type_cd} key part
         */
        public String getTranTypeCd() {
            return tranTypeCd;
        }

        /**
         * Sets the transaction type code.
         *
         * @param tranTypeCd the {@code tran_type_cd} key part
         */
        public void setTranTypeCd(String tranTypeCd) {
            this.tranTypeCd = tranTypeCd;
        }

        /**
         * Returns the transaction category code.
         *
         * @return the {@code tran_cat_cd} key part
         */
        public Integer getTranCatCd() {
            return tranCatCd;
        }

        /**
         * Sets the transaction category code.
         *
         * @param tranCatCd the {@code tran_cat_cd} key part
         */
        public void setTranCatCd(Integer tranCatCd) {
            this.tranCatCd = tranCatCd;
        }

        /**
         * Value-based equality over all three composite-key columns.
         *
         * @param o the object to compare with
         * @return {@code true} if all three key parts are equal
         */
        @Override
        public boolean equals(Object o) {
            if (this == o) {
                return true;
            }
            if (!(o instanceof DisclosureGroupId)) {
                return false;
            }
            DisclosureGroupId that = (DisclosureGroupId) o;
            return Objects.equals(groupId, that.groupId)
                    && Objects.equals(tranTypeCd, that.tranTypeCd)
                    && Objects.equals(tranCatCd, that.tranCatCd);
        }

        /**
         * Hash code over all three composite-key columns.
         *
         * @return the combined hash code
         */
        @Override
        public int hashCode() {
            return Objects.hash(groupId, tranTypeCd, tranCatCd);
        }

        /**
         * Diagnostic representation of the composite key.
         *
         * @return a string representation of the three key parts
         */
        @Override
        public String toString() {
            return "DisclosureGroupId{groupId='" + groupId + '\''
                    + ", tranTypeCd='" + tranTypeCd + '\''
                    + ", tranCatCd=" + tranCatCd + '}';
        }
    }
}
