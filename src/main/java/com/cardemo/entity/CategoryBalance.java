package com.cardemo.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;

import java.io.Serial;
import java.io.Serializable;
import java.math.BigDecimal;
import java.util.Objects;

/**
 * JPA entity mapping the VSAM TCATBALF dataset — transaction category balance aggregation.
 *
 * <p>Faithfully translates the COBOL TRAN-CAT-BAL-RECORD layout defined in
 * {@code app/cpy/CVTRA07Y.cpy} (50-byte KSDS record) to a PostgreSQL relational table.
 *
 * <h3>COBOL Source Record Layout (CVTRA07Y.cpy):</h3>
 * <pre>
 * 01  TRAN-CAT-BAL-RECORD.
 *     05  TRAN-CAT-KEY.
 *        10 TRANCAT-ACCT-ID    PIC 9(11).     positions [0:11]  → accountId
 *        10 TRANCAT-TYPE-CD    PIC X(02).     positions [11:13] → typeCode
 *        10 TRANCAT-CD         PIC 9(04).     positions [13:17] → categoryCode
 *     05  TRAN-CAT-BAL         PIC S9(09)V99. positions [17:28] → balance
 *     05  FILLER               PIC X(22).     positions [28:50] (not mapped)
 * </pre>
 *
 * <p>Total record length: 11 + 2 + 4 + 11 + 22 = 50 bytes.
 *
 * <h3>Database Schema (V1__create_schema.sql):</h3>
 * <pre>
 * CREATE TABLE category_balances (
 *     trancat_acct_id   VARCHAR(11)    NOT NULL,
 *     trancat_type_cd   VARCHAR(2)     NOT NULL,
 *     trancat_cd        INTEGER        NOT NULL,
 *     tran_cat_bal      NUMERIC(11,2)  NOT NULL DEFAULT 0,
 *     PRIMARY KEY (trancat_acct_id, trancat_type_cd, trancat_cd)
 * );
 * </pre>
 *
 * <h3>Key Design Decisions:</h3>
 * <ul>
 *   <li>Uses a composite primary key via {@code @IdClass} matching the VSAM KSDS
 *       primary key TRAN-CAT-KEY = (TRANCAT-ACCT-ID, TRANCAT-TYPE-CD, TRANCAT-CD).</li>
 *   <li>The balance field uses {@link BigDecimal} with precision=11, scale=2 to exactly match
 *       the COBOL PIC S9(09)V99 specification — 9 integer digits plus 2 decimal digits.
 *       NEVER use floating-point for this field.</li>
 *   <li>{@code categoryCode} is stored as {@code Integer} matching the PostgreSQL INTEGER type.</li>
 *   <li>{@code equals()} and {@code hashCode()} are based on the composite primary key
 *       (accountId + typeCode + categoryCode).</li>
 * </ul>
 *
 * @see <a href="app/cpy/CVTRA07Y.cpy">COBOL TRAN-CAT-BAL-RECORD copybook</a>
 * @see <a href="app/data/ASCII/tcatbal.txt">Test fixture data (50 records, 50 chars each)</a>
 */
@Entity
@Table(name = "category_balances")
@IdClass(CategoryBalance.CategoryBalanceId.class)
public class CategoryBalance {

    /**
     * Account identifier — maps to COBOL TRANCAT-ACCT-ID PIC 9(11).
     * Stored as {@code String} to preserve leading zeros (e.g., "00000000001").
     * Part of the composite primary key matching the VSAM KSDS primary key.
     */
    @Id
    @Column(name = "trancat_acct_id", length = 11, nullable = false)
    private String accountId;

    /**
     * Transaction type code — maps to COBOL TRANCAT-TYPE-CD PIC X(02).
     * Two-character alphanumeric code identifying the transaction type (e.g., "01").
     * Part of the composite primary key matching the VSAM KSDS primary key.
     */
    @Id
    @Column(name = "trancat_type_cd", length = 2, nullable = false)
    private String typeCode;

    /**
     * Transaction category code — maps to COBOL TRANCAT-CD PIC 9(04).
     * Stored as {@code Integer} matching the PostgreSQL INTEGER column type.
     * Part of the composite primary key matching the VSAM KSDS primary key.
     */
    @Id
    @Column(name = "trancat_cd", nullable = false)
    private Integer categoryCode;

    /**
     * Category balance amount — maps to COBOL TRAN-CAT-BAL PIC S9(09)V99.
     *
     * <p>Uses {@link BigDecimal} with precision=11 (9 integer + 2 decimal digits) and scale=2
     * to exactly replicate COBOL packed decimal semantics. The signed nature of the COBOL
     * field (S prefix) is naturally handled by {@link BigDecimal}'s sign support.
     *
     * <p><strong>CRITICAL:</strong> NEVER use {@code float} or {@code double} for this field.
     * All monetary calculations must use {@link BigDecimal} with
     * {@link java.math.RoundingMode#HALF_UP} per the AAP mandate for zero floating-point
     * rounding errors.
     */
    @Column(name = "tran_cat_bal", precision = 11, scale = 2, nullable = false)
    private BigDecimal balance;

    /**
     * Default no-argument constructor required by the JPA specification (JSR 338).
     * Protected access prevents direct instantiation outside of the JPA framework
     * while still allowing proxy creation by Hibernate.
     */
    protected CategoryBalance() {
        // Required by JPA specification — no initialization needed
    }

    /**
     * Constructs a new {@code CategoryBalance} with the composite key fields and balance.
     *
     * @param accountId    the 11-character zero-padded account identifier (TRANCAT-ACCT-ID)
     * @param typeCode     the 2-character transaction type code (TRANCAT-TYPE-CD)
     * @param categoryCode the category code as Integer (TRANCAT-CD)
     * @param balance      the category balance amount (TRAN-CAT-BAL), must not be null
     */
    public CategoryBalance(String accountId, String typeCode, Integer categoryCode,
                           BigDecimal balance) {
        this.accountId = accountId;
        this.typeCode = typeCode;
        this.categoryCode = categoryCode;
        this.balance = balance;
    }

    /**
     * Returns the account identifier (TRANCAT-ACCT-ID).
     *
     * @return the 11-character zero-padded account ID
     */
    public String getAccountId() {
        return accountId;
    }

    /**
     * Sets the account identifier (TRANCAT-ACCT-ID).
     *
     * @param accountId the 11-character zero-padded account ID
     */
    public void setAccountId(String accountId) {
        this.accountId = accountId;
    }

    /**
     * Returns the transaction type code (TRANCAT-TYPE-CD).
     *
     * @return the 2-character type code
     */
    public String getTypeCode() {
        return typeCode;
    }

    /**
     * Sets the transaction type code (TRANCAT-TYPE-CD).
     *
     * @param typeCode the 2-character type code
     */
    public void setTypeCode(String typeCode) {
        this.typeCode = typeCode;
    }

    /**
     * Returns the transaction category code (TRANCAT-CD).
     *
     * @return the category code as Integer
     */
    public Integer getCategoryCode() {
        return categoryCode;
    }

    /**
     * Sets the transaction category code (TRANCAT-CD).
     *
     * @param categoryCode the category code as Integer
     */
    public void setCategoryCode(Integer categoryCode) {
        this.categoryCode = categoryCode;
    }

    /**
     * Returns the category balance amount (TRAN-CAT-BAL).
     *
     * @return the balance as {@link BigDecimal} with precision=11 and scale=2
     */
    public BigDecimal getBalance() {
        return balance;
    }

    /**
     * Sets the category balance amount (TRAN-CAT-BAL).
     *
     * @param balance the balance as {@link BigDecimal}, must not be null
     */
    public void setBalance(BigDecimal balance) {
        this.balance = balance;
    }

    /**
     * Determines equality based on the composite primary key
     * (accountId + typeCode + categoryCode), mirroring the VSAM KSDS composite
     * primary key TRAN-CAT-KEY = TRANCAT-ACCT-ID || TRANCAT-TYPE-CD || TRANCAT-CD.
     *
     * @param o the reference object with which to compare
     * @return {@code true} if this entity has the same composite key as the other entity
     */
    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }
        CategoryBalance that = (CategoryBalance) o;
        return Objects.equals(accountId, that.accountId)
                && Objects.equals(typeCode, that.typeCode)
                && Objects.equals(categoryCode, that.categoryCode);
    }

    /**
     * Computes the hash code based on the composite primary key
     * (accountId + typeCode + categoryCode).
     * Consistent with the {@link #equals(Object)} contract.
     *
     * @return hash code derived from the composite key fields
     */
    @Override
    public int hashCode() {
        return Objects.hash(accountId, typeCode, categoryCode);
    }

    /**
     * Returns a string representation including the composite key fields and balance.
     * Useful for logging and debugging. Does not expose sensitive data.
     *
     * @return string representation of this category balance record
     */
    @Override
    public String toString() {
        return "CategoryBalance{"
                + "accountId='" + accountId + '\''
                + ", typeCode='" + typeCode + '\''
                + ", categoryCode=" + categoryCode
                + ", balance=" + balance
                + '}';
    }

    // =========================================================================
    // Composite Primary Key Class
    // =========================================================================

    /**
     * Composite primary key class for {@link CategoryBalance}.
     *
     * <p>Implements {@link Serializable} as required by the JPA specification
     * for {@code @IdClass} composite keys. The field names must match exactly
     * the {@code @Id} fields in the entity class.</p>
     *
     * <p>The composite key (trancat_acct_id, trancat_type_cd, trancat_cd) mirrors
     * the VSAM KSDS primary key TRAN-CAT-KEY.</p>
     */
    public static class CategoryBalanceId implements Serializable {

        @Serial
        private static final long serialVersionUID = 1L;

        /** Account identifier — matches entity field {@code accountId}. */
        private String accountId;

        /** Transaction type code — matches entity field {@code typeCode}. */
        private String typeCode;

        /** Transaction category code — matches entity field {@code categoryCode}. */
        private Integer categoryCode;

        /** Default constructor required by JPA. */
        public CategoryBalanceId() {
            // Required by JPA specification
        }

        /**
         * Constructs a composite key with all key components.
         *
         * @param accountId    the 11-character account identifier
         * @param typeCode     the 2-character transaction type code
         * @param categoryCode the category code as Integer
         */
        public CategoryBalanceId(String accountId, String typeCode, Integer categoryCode) {
            this.accountId = accountId;
            this.typeCode = typeCode;
            this.categoryCode = categoryCode;
        }

        /** Returns the account identifier. */
        public String getAccountId() {
            return accountId;
        }

        /** Sets the account identifier. */
        public void setAccountId(String accountId) {
            this.accountId = accountId;
        }

        /** Returns the transaction type code. */
        public String getTypeCode() {
            return typeCode;
        }

        /** Sets the transaction type code. */
        public void setTypeCode(String typeCode) {
            this.typeCode = typeCode;
        }

        /** Returns the transaction category code. */
        public Integer getCategoryCode() {
            return categoryCode;
        }

        /** Sets the transaction category code. */
        public void setCategoryCode(Integer categoryCode) {
            this.categoryCode = categoryCode;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) {
                return true;
            }
            if (o == null || getClass() != o.getClass()) {
                return false;
            }
            CategoryBalanceId that = (CategoryBalanceId) o;
            return Objects.equals(accountId, that.accountId)
                    && Objects.equals(typeCode, that.typeCode)
                    && Objects.equals(categoryCode, that.categoryCode);
        }

        @Override
        public int hashCode() {
            return Objects.hash(accountId, typeCode, categoryCode);
        }

        @Override
        public String toString() {
            return "CategoryBalanceId{"
                    + "accountId='" + accountId + '\''
                    + ", typeCode='" + typeCode + '\''
                    + ", categoryCode=" + categoryCode
                    + '}';
        }
    }
}
