package com.aws.carddemo.domain;

import java.io.Serializable;
import java.util.Objects;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * JPA entity mapping the AWS CardDemo transaction-category reference table.
 *
 * <p>Origin: COBOL copybook {@code legacy/cpy/CVTRA04Y.cpy} record
 * {@code TRAN-CAT-RECORD} (RECLN 60), which describes the layout of the legacy
 * VSAM {@code TRANCATG} reference/lookup file. Each row associates a
 * (transaction-type, transaction-category) pair with a human-readable
 * description and is used purely for lookup; there are no JPA relationships to
 * other entities, mirroring the decoupled parity design of the migration.</p>
 *
 * <p>The legacy record is keyed by the composite {@code TRAN-CAT-KEY}
 * (transaction type + transaction category). That composite key is preserved
 * here as a JPA composite identifier declared with {@link IdClass}; see the
 * nested {@link TransactionCategoryId} class. The trailing
 * {@code FILLER PIC X(04)} of the copybook carries no business data and is
 * intentionally not persisted as a column, in keeping with the migration's
 * record-layout parity rules.</p>
 *
 * <p>Field-level provenance (COBOL PIC to Java field / SQL type):</p>
 * <ul>
 *   <li>{@code TRAN-TYPE-CD PIC X(02)} to {@link #typeCd} ({@code CHAR(2)})</li>
 *   <li>{@code TRAN-CAT-CD PIC 9(04)} to {@link #catCd} ({@code NUMERIC(4)})</li>
 *   <li>{@code TRAN-CAT-TYPE-DESC PIC X(50)} to {@link #description}
 *       ({@code CHAR(50)})</li>
 * </ul>
 */
@Entity
@Table(name = "transaction_category")
@IdClass(TransactionCategory.TransactionCategoryId.class)
public class TransactionCategory {

    /**
     * Transaction type code. Legacy field {@code TRAN-TYPE-CD PIC X(02)}; the
     * first component of the composite primary key {@code TRAN-CAT-KEY}.
     */
    @Id
    @Column(name = "tran_type_cd", length = 2)
    private String typeCd;

    /**
     * Transaction category code. Legacy field {@code TRAN-CAT-CD PIC 9(04)};
     * the second component of the composite primary key {@code TRAN-CAT-KEY}.
     * Modeled as {@link Integer} (mapped to {@code NUMERIC(4)}) and never a
     * floating-point type, to preserve exact fixed-scale decimal semantics.
     * {@link JdbcTypeCode}({@link SqlTypes#NUMERIC}) forces Hibernate to expect a
     * {@code NUMERIC} column rather than the default {@code INTEGER} (review
     * finding F1).
     */
    @Id
    @Column(name = "tran_cat_cd", precision = 4)
    @JdbcTypeCode(SqlTypes.NUMERIC)
    private Integer catCd;

    /**
     * Human-readable description of the (type, category) pair. Legacy field
     * {@code TRAN-CAT-TYPE-DESC PIC X(50)}.
     */
    @Column(name = "tran_cat_type_desc", length = 50)
    private String description;

    /**
     * Default no-argument constructor required by the JPA provider. Fields are
     * populated by the persistence provider (or by the JavaBean setters), so no
     * initialization logic is performed here.
     */
    public TransactionCategory() {
        // Intentionally empty: JPA instantiates the entity via this constructor.
    }

    /**
     * Returns the transaction type code ({@code TRAN-TYPE-CD}).
     *
     * @return the transaction type code
     */
    public String getTypeCd() {
        return typeCd;
    }

    /**
     * Sets the transaction type code ({@code TRAN-TYPE-CD}).
     *
     * @param typeCd the transaction type code
     */
    public void setTypeCd(String typeCd) {
        this.typeCd = typeCd;
    }

    /**
     * Returns the transaction category code ({@code TRAN-CAT-CD}).
     *
     * @return the transaction category code
     */
    public Integer getCatCd() {
        return catCd;
    }

    /**
     * Sets the transaction category code ({@code TRAN-CAT-CD}).
     *
     * @param catCd the transaction category code
     */
    public void setCatCd(Integer catCd) {
        this.catCd = catCd;
    }

    /**
     * Returns the transaction-category description ({@code TRAN-CAT-TYPE-DESC}).
     *
     * @return the description text
     */
    public String getDescription() {
        return description;
    }

    /**
     * Sets the transaction-category description ({@code TRAN-CAT-TYPE-DESC}).
     *
     * @param description the description text
     */
    public void setDescription(String description) {
        this.description = description;
    }

    /**
     * Entity equality is defined over the composite primary key
     * ({@link #typeCd}, {@link #catCd}), mirroring the legacy VSAM key
     * {@code TRAN-CAT-KEY}. The non-key {@link #description} field is excluded so
     * that identity matches the underlying primary-key semantics. Uses an
     * {@code instanceof} pattern so a Hibernate proxy compares equal to its
     * underlying entity, and treats an instance whose key is not fully populated
     * (any component {@code null}) as not equal to any other instance, so
     * distinct transient rows are never collapsed (review finding F10).
     *
     * @param o the object to compare with
     * @return {@code true} if the other object is a {@code TransactionCategory}
     *         with the same fully populated composite key
     */
    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof TransactionCategory that)) {
            return false;
        }
        return typeCd != null && catCd != null
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
        return TransactionCategory.class.hashCode();
    }

    /**
     * Returns a diagnostic representation including all three mapped fields.
     *
     * @return a string representation of this transaction category
     */
    @Override
    public String toString() {
        return "TransactionCategory{"
                + "typeCd='" + typeCd + '\''
                + ", catCd=" + catCd
                + ", description='" + description + '\''
                + '}';
    }

    /**
     * Composite primary-key class for {@link TransactionCategory}, corresponding
     * to the legacy VSAM composite key {@code TRAN-CAT-KEY} (transaction type +
     * transaction category).
     *
     * <p>Per the JPA specification an {@link IdClass} must be
     * {@link Serializable}, must expose a public no-argument constructor, and
     * must declare fields whose names and types exactly match the owning
     * entity's {@link Id} fields ({@code typeCd} of type {@link String} and
     * {@code catCd} of type {@link Integer}).</p>
     */
    public static class TransactionCategoryId implements Serializable {

        /** Serialization version identifier for this composite-key class. */
        private static final long serialVersionUID = 1L;

        /** Matches {@link TransactionCategory#typeCd} ({@code TRAN-TYPE-CD}). */
        private String typeCd;

        /** Matches {@link TransactionCategory#catCd} ({@code TRAN-CAT-CD}). */
        private Integer catCd;

        /**
         * Default no-argument constructor required by the JPA provider for
         * composite identifier classes.
         */
        public TransactionCategoryId() {
            // Intentionally empty: required by the JPA specification.
        }

        /**
         * Convenience constructor that builds a fully-populated composite key.
         *
         * @param typeCd the transaction type code
         * @param catCd  the transaction category code
         */
        public TransactionCategoryId(String typeCd, Integer catCd) {
            this.typeCd = typeCd;
            this.catCd = catCd;
        }

        /**
         * Returns the transaction type code component of this key.
         *
         * @return the transaction type code
         */
        public String getTypeCd() {
            return typeCd;
        }

        /**
         * Sets the transaction type code component of this key.
         *
         * @param typeCd the transaction type code
         */
        public void setTypeCd(String typeCd) {
            this.typeCd = typeCd;
        }

        /**
         * Returns the transaction category code component of this key.
         *
         * @return the transaction category code
         */
        public Integer getCatCd() {
            return catCd;
        }

        /**
         * Sets the transaction category code component of this key.
         *
         * @param catCd the transaction category code
         */
        public void setCatCd(Integer catCd) {
            this.catCd = catCd;
        }

        /**
         * Two composite keys are equal when both the type code and the category
         * code are equal.
         *
         * @param o the object to compare with
         * @return {@code true} if the other object is a
         *         {@code TransactionCategoryId} with equal type and category
         *         codes
         */
        @Override
        public boolean equals(Object o) {
            if (this == o) {
                return true;
            }
            if (!(o instanceof TransactionCategoryId that)) {
                return false;
            }
            return Objects.equals(typeCd, that.typeCd)
                    && Objects.equals(catCd, that.catCd);
        }

        /**
         * Hash code derived from both key components, consistent with
         * {@link #equals(Object)}.
         *
         * @return the composite-key hash code
         */
        @Override
        public int hashCode() {
            return Objects.hash(typeCd, catCd);
        }

        /**
         * Returns a diagnostic representation of this composite key.
         *
         * @return a string representation of this key
         */
        @Override
        public String toString() {
            return "TransactionCategoryId{"
                    + "typeCd='" + typeCd + '\''
                    + ", catCd=" + catCd
                    + '}';
        }
    }
}
