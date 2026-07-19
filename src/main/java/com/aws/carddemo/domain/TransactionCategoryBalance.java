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
 * JPA entity for the transaction-category running balance.
 *
 * <p>Migrated one-for-one from the AWS CardDemo COBOL copybook
 * {@code TRAN-CAT-BAL-RECORD} (record length 50 bytes) defined at
 * {@code legacy/cpy/CVTRA01Y.cpy}, which described the VSAM KSDS file
 * {@code TCATBAL}. Each row holds the running balance for a unique
 * (account, transaction-type, transaction-category) triple. The record is
 * written and updated by the daily transaction posting job and read by the
 * monthly interest-calculation job.</p>
 *
 * <p>The legacy composite VSAM key {@code TRAN-CAT-KEY} (account id +
 * transaction-type code + transaction-category code) is preserved as a JPA
 * composite primary key via {@link IdClass}, realized through the nested
 * {@link TransactionCategoryBalanceId} class. Record layout and key
 * semantics are preserved exactly.</p>
 *
 * <p>The monetary balance field originates from the fixed-scale COBOL
 * picture {@code PIC S9(09)V99} and is therefore mapped to
 * {@link java.math.BigDecimal} with precision 11 and scale 2
 * ({@code NUMERIC(11,2)}); floating-point types are never used for money.
 * The trailing {@code FILLER PIC X(22)} reserved bytes are intentionally
 * not persisted as a column.</p>
 *
 * <p>Origin: {@code legacy/cpy/CVTRA01Y.cpy} &mdash;
 * {@code TRAN-CAT-BAL-RECORD}, RECLN 50; VSAM file {@code TCATBAL}.</p>
 */
@Entity
@Table(name = "transaction_category_balance")
@IdClass(TransactionCategoryBalance.TransactionCategoryBalanceId.class)
public class TransactionCategoryBalance {

    /**
     * Account identifier &mdash; legacy {@code TRANCAT-ACCT-ID PIC 9(11)}.
     * First component of the composite primary key ({@code NUMERIC(11)}).
     * {@link JdbcTypeCode}({@link SqlTypes#NUMERIC}) forces Hibernate to expect a
     * {@code NUMERIC} column rather than the default {@code BIGINT} (review
     * finding F1).
     */
    @Id
    @Column(name = "trancat_acct_id", precision = 11)
    @JdbcTypeCode(SqlTypes.NUMERIC)
    private Long acctId;

    /**
     * Transaction-type code &mdash; legacy {@code TRANCAT-TYPE-CD PIC X(02)}.
     * Second component of the composite primary key ({@code CHAR(2)}).
     */
    @Id
    @Column(name = "trancat_type_cd", length = 2)
    private String typeCd;

    /**
     * Transaction-category code &mdash; legacy {@code TRANCAT-CD PIC 9(04)}.
     * Third component of the composite primary key ({@code NUMERIC(4)}).
     * {@link JdbcTypeCode}({@link SqlTypes#NUMERIC}) forces Hibernate to expect a
     * {@code NUMERIC} column rather than the default {@code INTEGER} (review
     * finding F1).
     */
    @Id
    @Column(name = "trancat_cd", precision = 4)
    @JdbcTypeCode(SqlTypes.NUMERIC)
    private Integer catCd;

    /**
     * Running category balance &mdash; legacy {@code TRAN-CAT-BAL PIC S9(09)V99}.
     * Nine integer digits plus two fractional digits map to
     * {@code NUMERIC(11,2)}; represented as {@link java.math.BigDecimal} so that
     * COBOL fixed-scale decimal arithmetic is reproduced without any loss of
     * precision. Never a floating-point type.
     */
    @Column(name = "tran_cat_bal", precision = 11, scale = 2)
    private BigDecimal balance;

    /**
     * Default no-argument constructor required by the JPA specification.
     */
    public TransactionCategoryBalance() {
        // Required by JPA; fields populated by the persistence provider or setters.
    }

    /**
     * Returns the account identifier component of the composite key.
     *
     * @return the account id, or {@code null} if unset
     */
    public Long getAcctId() {
        return acctId;
    }

    /**
     * Sets the account identifier component of the composite key.
     *
     * @param acctId the account id to set
     */
    public void setAcctId(Long acctId) {
        this.acctId = acctId;
    }

    /**
     * Returns the transaction-type code component of the composite key.
     *
     * @return the transaction-type code, or {@code null} if unset
     */
    public String getTypeCd() {
        return typeCd;
    }

    /**
     * Sets the transaction-type code component of the composite key.
     *
     * @param typeCd the transaction-type code to set
     */
    public void setTypeCd(String typeCd) {
        this.typeCd = typeCd;
    }

    /**
     * Returns the transaction-category code component of the composite key.
     *
     * @return the transaction-category code, or {@code null} if unset
     */
    public Integer getCatCd() {
        return catCd;
    }

    /**
     * Sets the transaction-category code component of the composite key.
     *
     * @param catCd the transaction-category code to set
     */
    public void setCatCd(Integer catCd) {
        this.catCd = catCd;
    }

    /**
     * Returns the running category balance.
     *
     * @return the balance as a {@link java.math.BigDecimal}, or {@code null} if unset
     */
    public BigDecimal getBalance() {
        return balance;
    }

    /**
     * Sets the running category balance.
     *
     * @param balance the balance to set
     */
    public void setBalance(BigDecimal balance) {
        this.balance = balance;
    }

    /**
     * Entity equality is defined over the composite primary key
     * (account id, transaction-type code, transaction-category code),
     * mirroring the uniqueness of the legacy VSAM {@code TRAN-CAT-KEY}.
     * The non-key {@code balance} field is intentionally excluded. Uses an
     * {@code instanceof} pattern so a Hibernate proxy compares equal to its
     * underlying entity, and treats an instance whose key is not fully populated
     * (any component {@code null}) as not equal to any other instance, so
     * distinct transient rows are never collapsed (review finding F10).
     *
     * @param o the object to compare with
     * @return {@code true} if the other object is a
     *         {@code TransactionCategoryBalance} with an equal, fully populated
     *         composite key
     */
    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof TransactionCategoryBalance that)) {
            return false;
        }
        return acctId != null && typeCd != null && catCd != null
                && acctId.equals(that.acctId)
                && typeCd.equals(that.typeCd)
                && catCd.equals(that.catCd);
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
        return TransactionCategoryBalance.class.hashCode();
    }

    /**
     * Returns a non-sensitive diagnostic representation containing only the class
     * name and an opaque per-instance identity token. The account id, type code,
     * category code and running balance are deliberately never emitted so that
     * account/financial data cannot leak into logs or error messages (CWE-532;
     * review finding F9).
     *
     * @return a non-sensitive string representation
     */
    @Override
    public String toString() {
        return "TransactionCategoryBalance@" + Integer.toHexString(System.identityHashCode(this));
    }

    /**
     * Composite primary-key class for {@link TransactionCategoryBalance},
     * referenced by {@link IdClass}. Preserves the legacy VSAM composite key
     * {@code TRAN-CAT-KEY} (account id + transaction-type code +
     * transaction-category code).
     *
     * <p>Per the JPA {@code @IdClass} contract, the field names and types
     * declared here match the owning entity's {@code @Id} fields exactly:
     * {@code acctId} ({@link Long}), {@code typeCd} ({@link String}), and
     * {@code catCd} ({@link Integer}).</p>
     */
    public static class TransactionCategoryBalanceId implements Serializable {

        private static final long serialVersionUID = 1L;

        private Long acctId;
        private String typeCd;
        private Integer catCd;

        /**
         * Default no-argument constructor required by the JPA specification.
         */
        public TransactionCategoryBalanceId() {
            // Required by JPA; fields populated by the persistence provider or setters.
        }

        /**
         * All-arguments constructor.
         *
         * @param acctId the account identifier component
         * @param typeCd the transaction-type code component
         * @param catCd  the transaction-category code component
         */
        public TransactionCategoryBalanceId(Long acctId, String typeCd, Integer catCd) {
            this.acctId = acctId;
            this.typeCd = typeCd;
            this.catCd = catCd;
        }

        /**
         * Returns the account identifier component.
         *
         * @return the account id, or {@code null} if unset
         */
        public Long getAcctId() {
            return acctId;
        }

        /**
         * Sets the account identifier component.
         *
         * @param acctId the account id to set
         */
        public void setAcctId(Long acctId) {
            this.acctId = acctId;
        }

        /**
         * Returns the transaction-type code component.
         *
         * @return the transaction-type code, or {@code null} if unset
         */
        public String getTypeCd() {
            return typeCd;
        }

        /**
         * Sets the transaction-type code component.
         *
         * @param typeCd the transaction-type code to set
         */
        public void setTypeCd(String typeCd) {
            this.typeCd = typeCd;
        }

        /**
         * Returns the transaction-category code component.
         *
         * @return the transaction-category code, or {@code null} if unset
         */
        public Integer getCatCd() {
            return catCd;
        }

        /**
         * Sets the transaction-category code component.
         *
         * @param catCd the transaction-category code to set
         */
        public void setCatCd(Integer catCd) {
            this.catCd = catCd;
        }

        /**
         * Equality over all three key components.
         *
         * @param other the object to compare with
         * @return {@code true} if the other object is a
         *         {@code TransactionCategoryBalanceId} with equal components
         */
        @Override
        public boolean equals(Object other) {
            if (this == other) {
                return true;
            }
            if (other == null || getClass() != other.getClass()) {
                return false;
            }
            TransactionCategoryBalanceId that = (TransactionCategoryBalanceId) other;
            return (acctId == null ? that.acctId == null : acctId.equals(that.acctId))
                    && (typeCd == null ? that.typeCd == null : typeCd.equals(that.typeCd))
                    && (catCd == null ? that.catCd == null : catCd.equals(that.catCd));
        }

        /**
         * Hash code consistent with {@link #equals(Object)} over all three
         * key components.
         *
         * @return the hash code derived from (acctId, typeCd, catCd)
         */
        @Override
        public int hashCode() {
            int result = (acctId == null) ? 0 : acctId.hashCode();
            result = 31 * result + ((typeCd == null) ? 0 : typeCd.hashCode());
            result = 31 * result + ((catCd == null) ? 0 : catCd.hashCode());
            return result;
        }

        /**
         * Returns a string representation including all three key components.
         *
         * @return a diagnostic string for this composite key
         */
        @Override
        public String toString() {
            return "TransactionCategoryBalanceId{"
                    + "acctId=" + acctId
                    + ", typeCd=" + typeCd
                    + ", catCd=" + catCd
                    + '}';
        }
    }
}
