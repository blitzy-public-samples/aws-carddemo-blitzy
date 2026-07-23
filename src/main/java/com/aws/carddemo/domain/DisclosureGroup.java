package com.aws.carddemo.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.io.Serializable;
import java.math.BigDecimal;

/**
 * JPA entity for the disclosure-group / interest-rate reference data.
 *
 * <p><strong>Origin (traceability):</strong> migrated one-for-one from the COBOL
 * copybook {@code DIS-GROUP-RECORD} (RECLN 50) defined at
 * {@code legacy/cpy/CVTRA02Y.cpy} (retained read-only). It persists what was the
 * VSAM KSDS {@code DISCGRP} file in the mainframe AWS CardDemo application.</p>
 *
 * <p><strong>Composite key:</strong> the legacy {@code DIS-GROUP-KEY} is a
 * three-part VSAM key &mdash; account-group id + transaction-type code +
 * transaction-category code. It is reproduced here as a JPA composite primary
 * key using {@link IdClass} over {@link DisclosureGroupId}, preserving the
 * original key semantics (AAP &sect;0.6.2).</p>
 *
 * <p><strong>Interest rate:</strong> {@link #getIntRate() intRate} is the
 * disclosure interest rate for the (group, type, category) tuple. It is the
 * lookup value consumed by the interest-calculation batch job
 * ({@code InterestCalcJobConfig}, migrated from {@code CBACT04C}) in the
 * fixed-scale computation {@code (TRAN-CAT-BAL * DIS-INT-RATE) / 1200}. To
 * preserve COBOL packed-decimal fidelity it is a {@link java.math.BigDecimal}
 * (never a floating-point type) with {@code precision = 6, scale = 2},
 * mirroring the COBOL {@code PIC S9(04)V99} declaration (AAP &sect;0.6.1).</p>
 *
 * <p>The trailing {@code FILLER PIC X(28)} from the copybook carries no business
 * data and is intentionally not persisted; only the four business fields are
 * mapped to columns.</p>
 *
 * <p>Verified legacy layout ({@code legacy/cpy/CVTRA02Y.cpy}, 50 bytes):</p>
 * <pre>
 * 01  DIS-GROUP-RECORD.
 *     05  DIS-GROUP-KEY.
 *        10  DIS-ACCT-GROUP-ID     PIC X(10).
 *        10  DIS-TRAN-TYPE-CD      PIC X(02).
 *        10  DIS-TRAN-CAT-CD       PIC 9(04).
 *     05  DIS-INT-RATE             PIC S9(04)V99.
 *     05  FILLER                   PIC X(28).
 * </pre>
 */
@Entity
@Table(name = "disclosure_group")
@IdClass(DisclosureGroup.DisclosureGroupId.class)
public class DisclosureGroup {

    /**
     * Account-group id &mdash; first component of the composite key.
     * Legacy: {@code DIS-ACCT-GROUP-ID PIC X(10)} &rarr; fixed-width
     * {@code CHAR(10)} to preserve trailing-space semantics.
     */
    @Id
    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "dis_acct_group_id", length = 10)
    private String acctGroupId;

    /**
     * Transaction-type code &mdash; second component of the composite key.
     * Legacy: {@code DIS-TRAN-TYPE-CD PIC X(02)} &rarr; {@code CHAR(2)}.
     */
    @Id
    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "dis_tran_type_cd", length = 2)
    private String tranTypeCd;

    /**
     * Transaction-category code &mdash; third component of the composite key.
     * Legacy: {@code DIS-TRAN-CAT-CD PIC 9(04)} (unsigned 4-digit numeric)
     * &rarr; {@code NUMERIC(4)} mapped to {@link Integer}.
     * {@link JdbcTypeCode}({@link SqlTypes#NUMERIC}) forces Hibernate to expect
     * a {@code NUMERIC} column rather than the default {@code INTEGER} (review
     * finding F1).
     */
    @Id
    @Column(name = "dis_tran_cat_cd", precision = 4)
    @JdbcTypeCode(SqlTypes.NUMERIC)
    private Integer tranCatCd;

    /**
     * Disclosure interest rate for the (group, type, category) tuple.
     * Legacy: {@code DIS-INT-RATE PIC S9(04)V99} &rarr; {@code NUMERIC(6,2)}.
     * Held as {@link java.math.BigDecimal} to preserve exact packed-decimal
     * arithmetic in the interest calculation; floating-point types are
     * prohibited for monetary/rate values (AAP &sect;0.6.1).
     */
    @Column(name = "dis_int_rate", precision = 6, scale = 2)
    private BigDecimal intRate;

    /**
     * Default no-argument constructor required by JPA.
     */
    public DisclosureGroup() {
        // No initialization: JPA populates fields via reflection.
    }

    /**
     * Returns the account-group id (key component 1).
     *
     * @return the account-group id
     */
    public String getAcctGroupId() {
        return acctGroupId;
    }

    /**
     * Sets the account-group id (key component 1).
     *
     * @param acctGroupId the account-group id
     */
    public void setAcctGroupId(String acctGroupId) {
        this.acctGroupId = acctGroupId;
    }

    /**
     * Returns the transaction-type code (key component 2).
     *
     * @return the transaction-type code
     */
    public String getTranTypeCd() {
        return tranTypeCd;
    }

    /**
     * Sets the transaction-type code (key component 2).
     *
     * @param tranTypeCd the transaction-type code
     */
    public void setTranTypeCd(String tranTypeCd) {
        this.tranTypeCd = tranTypeCd;
    }

    /**
     * Returns the transaction-category code (key component 3).
     *
     * @return the transaction-category code
     */
    public Integer getTranCatCd() {
        return tranCatCd;
    }

    /**
     * Sets the transaction-category code (key component 3).
     *
     * @param tranCatCd the transaction-category code
     */
    public void setTranCatCd(Integer tranCatCd) {
        this.tranCatCd = tranCatCd;
    }

    /**
     * Returns the disclosure interest rate.
     *
     * @return the interest rate as a {@link java.math.BigDecimal}
     */
    public BigDecimal getIntRate() {
        return intRate;
    }

    /**
     * Sets the disclosure interest rate.
     *
     * @param intRate the interest rate as a {@link java.math.BigDecimal}
     */
    public void setIntRate(BigDecimal intRate) {
        this.intRate = intRate;
    }

    /**
     * Entity equality is defined over the composite primary key (account-group
     * id, transaction-type code, transaction-category code), consistent with the
     * legacy VSAM key semantics. Uses an {@code instanceof} pattern so a
     * Hibernate proxy compares equal to its underlying entity, and treats an
     * instance whose key is not fully populated (any component {@code null}) as
     * not equal to any other instance, so distinct transient rows are never
     * collapsed (review finding F10).
     *
     * @param o the object to compare with
     * @return {@code true} if {@code o} is a {@code DisclosureGroup} with an
     *         equal, fully populated composite key
     */
    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof DisclosureGroup that)) {
            return false;
        }
        return acctGroupId != null && tranTypeCd != null && tranCatCd != null
                && acctGroupId.equals(that.acctGroupId)
                && tranTypeCd.equals(that.tranTypeCd)
                && tranCatCd.equals(that.tranCatCd);
    }

    /**
     * Returns a constant, identity-stable hash code. A constant (rather than one
     * derived from the mutable composite key) is used so the hash does not change
     * as the key components are assigned, keeping instances locatable in
     * hash-based collections and consistent with {@link #equals(Object)} (review
     * finding F10).
     *
     * @return a stable, class-level hash code
     */
    @Override
    public int hashCode() {
        return DisclosureGroup.class.hashCode();
    }

    /**
     * Diagnostic representation including all mapped fields.
     *
     * @return a string representation of this disclosure group
     */
    @Override
    public String toString() {
        return "DisclosureGroup{"
                + "acctGroupId='" + acctGroupId + '\''
                + ", tranTypeCd='" + tranTypeCd + '\''
                + ", tranCatCd=" + tranCatCd
                + ", intRate=" + intRate
                + '}';
    }

    /**
     * Composite primary-key class for {@link DisclosureGroup}, referenced by
     * the entity's {@link IdClass} declaration. Its fields mirror the entity's
     * {@code @Id} fields exactly, by name and type, as JPA requires. This
     * reproduces the legacy {@code DIS-GROUP-KEY} (account-group id +
     * transaction-type code + transaction-category code).
     *
     * <p>Defined as a nested static class (never a separate top-level file) so
     * it stays co-located with the entity it keys.</p>
     */
    public static class DisclosureGroupId implements Serializable {

        /** Serialization version, required for a {@link Serializable} id class. */
        private static final long serialVersionUID = 1L;

        /** Account-group id key component; mirrors {@link DisclosureGroup#acctGroupId}. */
        private String acctGroupId;

        /** Transaction-type code key component; mirrors {@link DisclosureGroup#tranTypeCd}. */
        private String tranTypeCd;

        /** Transaction-category code key component; mirrors {@link DisclosureGroup#tranCatCd}. */
        private Integer tranCatCd;

        /**
         * Default no-argument constructor required by JPA for id classes.
         */
        public DisclosureGroupId() {
            // No initialization: JPA constructs the key via reflection.
        }

        /**
         * Convenience all-arguments constructor.
         *
         * @param acctGroupId the account-group id
         * @param tranTypeCd  the transaction-type code
         * @param tranCatCd   the transaction-category code
         */
        public DisclosureGroupId(String acctGroupId, String tranTypeCd, Integer tranCatCd) {
            this.acctGroupId = acctGroupId;
            this.tranTypeCd = tranTypeCd;
            this.tranCatCd = tranCatCd;
        }

        /**
         * Returns the account-group id key component.
         *
         * @return the account-group id
         */
        public String getAcctGroupId() {
            return acctGroupId;
        }

        /**
         * Sets the account-group id key component.
         *
         * @param acctGroupId the account-group id
         */
        public void setAcctGroupId(String acctGroupId) {
            this.acctGroupId = acctGroupId;
        }

        /**
         * Returns the transaction-type code key component.
         *
         * @return the transaction-type code
         */
        public String getTranTypeCd() {
            return tranTypeCd;
        }

        /**
         * Sets the transaction-type code key component.
         *
         * @param tranTypeCd the transaction-type code
         */
        public void setTranTypeCd(String tranTypeCd) {
            this.tranTypeCd = tranTypeCd;
        }

        /**
         * Returns the transaction-category code key component.
         *
         * @return the transaction-category code
         */
        public Integer getTranCatCd() {
            return tranCatCd;
        }

        /**
         * Sets the transaction-category code key component.
         *
         * @param tranCatCd the transaction-category code
         */
        public void setTranCatCd(Integer tranCatCd) {
            this.tranCatCd = tranCatCd;
        }

        /**
         * Value equality over all three key components.
         *
         * @param o the object to compare with
         * @return {@code true} if {@code o} is a {@code DisclosureGroupId} with
         *         equal components
         */
        @Override
        public boolean equals(Object o) {
            if (this == o) {
                return true;
            }
            if (!(o instanceof DisclosureGroupId that)) {
                return false;
            }
            return java.util.Objects.equals(acctGroupId, that.acctGroupId)
                    && java.util.Objects.equals(tranTypeCd, that.tranTypeCd)
                    && java.util.Objects.equals(tranCatCd, that.tranCatCd);
        }

        /**
         * Hash code over all three key components, consistent with
         * {@link #equals(Object)}.
         *
         * @return the hash code of the key components
         */
        @Override
        public int hashCode() {
            return java.util.Objects.hash(acctGroupId, tranTypeCd, tranCatCd);
        }

        /**
         * Diagnostic representation including all key components.
         *
         * @return a string representation of this composite key
         */
        @Override
        public String toString() {
            return "DisclosureGroupId{"
                    + "acctGroupId='" + acctGroupId + '\''
                    + ", tranTypeCd='" + tranTypeCd + '\''
                    + ", tranCatCd=" + tranCatCd
                    + '}';
        }
    }
}
