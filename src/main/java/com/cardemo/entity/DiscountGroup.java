/*
 * DiscountGroup.java — JPA Entity for VSAM DISCGRP reference data
 *
 * Faithfully translates the COBOL copybook CVTRA02Y.cpy DIS-GROUP-RECORD
 * (50 bytes) into a JPA entity mapped to the PostgreSQL 'discount_groups' table.
 *
 * COBOL Record Layout (CVTRA02Y.cpy):
 *   01  DIS-GROUP-RECORD.
 *       05  DIS-GROUP-KEY.
 *          10 DIS-ACCT-GROUP-ID    PIC X(10).
 *          10 DIS-TRAN-TYPE-CD     PIC X(02).
 *          10 DIS-TRAN-CAT-CD      PIC 9(04).
 *       05  DIS-INT-RATE           PIC S9(04)V99.
 *       05  FILLER                 PIC X(28).
 *
 * Database Schema (V1__create_schema.sql):
 *   CREATE TABLE discount_groups (
 *       dis_acct_group_id   VARCHAR(10)    NOT NULL,
 *       dis_tran_type_cd    VARCHAR(2)     NOT NULL,
 *       dis_tran_cat_cd     INTEGER        NOT NULL,
 *       dis_int_rate        NUMERIC(6,2)   NOT NULL DEFAULT 0,
 *       PRIMARY KEY (dis_acct_group_id, dis_tran_type_cd, dis_tran_cat_cd)
 *   );
 *
 * Data source: app/data/ASCII/discgrp.txt — 51 records in 3 blocks:
 *   Lines  1-17: Group "A"       (17 category combinations)
 *   Lines 18-34: Group "DEFAULT" (17 category combinations)
 *   Lines 35-51: Group "ZEROAPR" (17 category combinations, zero interest)
 *
 * Key Decision: Uses @IdClass composite primary key matching the VSAM KSDS
 * DIS-GROUP-KEY structure (dis_acct_group_id, dis_tran_type_cd, dis_tran_cat_cd).
 *
 * @see app/cpy/CVTRA02Y.cpy — COBOL source copybook
 * @see app/data/ASCII/discgrp.txt — Reference data (51 records)
 */
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
 * JPA entity representing a discount group reference record.
 *
 * <p>Maps the VSAM DISCGRP dataset (DIS-GROUP-RECORD from CVTRA02Y.cpy, 50 bytes)
 * to the PostgreSQL {@code discount_groups} table. Each record defines an interest
 * rate for a specific combination of account group, transaction type, and
 * transaction category.</p>
 *
 * <p>Uses a composite primary key via {@code @IdClass} matching the VSAM KSDS
 * DIS-GROUP-KEY structure: (dis_acct_group_id, dis_tran_type_cd, dis_tran_cat_cd).</p>
 *
 * <p><strong>BigDecimal mandate:</strong> The {@code interestRate} field uses
 * {@link BigDecimal} (never {@code float} or {@code double}) to match the COBOL
 * PIC S9(04)V99 specification with exact decimal precision (scale=2).</p>
 */
@Entity
@Table(name = "discount_groups")
@IdClass(DiscountGroup.DiscountGroupId.class)
public class DiscountGroup {

    /**
     * Discount account group identifier.
     * Maps to DIS-ACCT-GROUP-ID PIC X(10).
     * Part of the composite primary key matching the VSAM KSDS DIS-GROUP-KEY.
     * Values in seed data: "A", "DEFAULT", "ZEROAPR" (space-padded to 10 chars in COBOL source).
     */
    @Id
    @Column(name = "dis_acct_group_id", length = 10, nullable = false)
    private String groupId;

    /**
     * Transaction type code.
     * Maps to DIS-TRAN-TYPE-CD PIC X(02).
     * Part of the composite primary key matching the VSAM KSDS DIS-GROUP-KEY.
     * 2-character code (e.g., "01" through "07") linking to transaction types.
     */
    @Id
    @Column(name = "dis_tran_type_cd", length = 2, nullable = false)
    private String tranTypeCode;

    /**
     * Transaction category code.
     * Maps to DIS-TRAN-CAT-CD PIC 9(04).
     * Part of the composite primary key matching the VSAM KSDS DIS-GROUP-KEY.
     * Stored as {@code Integer} matching the PostgreSQL INTEGER column type.
     */
    @Id
    @Column(name = "dis_tran_cat_cd", nullable = false)
    private Integer tranCatCode;

    /**
     * Interest rate for this discount group/type/category combination.
     * Maps to DIS-INT-RATE PIC S9(04)V99 — signed, 4 integer digits, 2 decimal digits.
     *
     * <p><strong>CRITICAL:</strong> Uses {@link BigDecimal} with precision=6 and scale=2.
     * NEVER use {@code float} or {@code double} per AAP mandate. All rate/monetary
     * calculations must use {@code BigDecimal} with {@code RoundingMode.HALF_UP}
     * matching COBOL default rounding.</p>
     *
     * <p>In the COBOL source data (discgrp.txt), the '{' character at the end of
     * the numeric field represents a positive zero in EBCDIC zoned decimal encoding.
     * For example, "00150{" = +0015.00 (interest rate of 15.00).</p>
     */
    @Column(name = "dis_int_rate", precision = 6, scale = 2, nullable = false)
    private BigDecimal interestRate;

    /**
     * Default no-arg constructor required by JPA.
     */
    protected DiscountGroup() {
        // Required by JPA specification for entity instantiation
    }

    /**
     * Parameterized constructor for creating a fully populated DiscountGroup.
     *
     * @param groupId      the discount account group identifier (DIS-ACCT-GROUP-ID, up to 10 chars)
     * @param tranTypeCode the transaction type code (DIS-TRAN-TYPE-CD, 2 chars)
     * @param tranCatCode  the transaction category code (DIS-TRAN-CAT-CD, Integer)
     * @param interestRate the interest rate (DIS-INT-RATE, BigDecimal with scale 2)
     */
    public DiscountGroup(String groupId, String tranTypeCode, Integer tranCatCode,
                         BigDecimal interestRate) {
        this.groupId = groupId;
        this.tranTypeCode = tranTypeCode;
        this.tranCatCode = tranCatCode;
        this.interestRate = interestRate;
    }

    // ========================================================================
    // Getters and Setters
    // ========================================================================

    /**
     * Returns the discount account group identifier.
     *
     * @return the group ID (DIS-ACCT-GROUP-ID), e.g., "A", "DEFAULT", "ZEROAPR"
     */
    public String getGroupId() {
        return groupId;
    }

    /**
     * Sets the discount account group identifier.
     *
     * @param groupId the group ID (up to 10 characters)
     */
    public void setGroupId(String groupId) {
        this.groupId = groupId;
    }

    /**
     * Returns the transaction type code.
     *
     * @return the 2-character transaction type code (DIS-TRAN-TYPE-CD)
     */
    public String getTranTypeCode() {
        return tranTypeCode;
    }

    /**
     * Sets the transaction type code.
     *
     * @param tranTypeCode the 2-character type code
     */
    public void setTranTypeCode(String tranTypeCode) {
        this.tranTypeCode = tranTypeCode;
    }

    /**
     * Returns the transaction category code.
     *
     * @return the transaction category code as Integer (DIS-TRAN-CAT-CD)
     */
    public Integer getTranCatCode() {
        return tranCatCode;
    }

    /**
     * Sets the transaction category code.
     *
     * @param tranCatCode the category code as Integer
     */
    public void setTranCatCode(Integer tranCatCode) {
        this.tranCatCode = tranCatCode;
    }

    /**
     * Returns the interest rate for this group/type/category combination.
     *
     * @return the interest rate as {@link BigDecimal} with scale 2
     */
    public BigDecimal getInterestRate() {
        return interestRate;
    }

    /**
     * Sets the interest rate for this group/type/category combination.
     *
     * @param interestRate the interest rate as {@link BigDecimal} (NEVER float/double)
     */
    public void setInterestRate(BigDecimal interestRate) {
        this.interestRate = interestRate;
    }

    // ========================================================================
    // equals, hashCode, toString — based on composite primary key
    // ========================================================================

    /**
     * Compares this entity with another object for equality based on the
     * composite primary key: {@code groupId}, {@code tranTypeCode}, and
     * {@code tranCatCode}. This matches the VSAM KSDS DIS-GROUP-KEY semantics.
     *
     * @param o the object to compare with
     * @return {@code true} if both objects have the same composite key values
     */
    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }
        DiscountGroup that = (DiscountGroup) o;
        return Objects.equals(groupId, that.groupId)
                && Objects.equals(tranTypeCode, that.tranTypeCode)
                && Objects.equals(tranCatCode, that.tranCatCode);
    }

    /**
     * Computes the hash code based on the composite primary key:
     * {@code groupId}, {@code tranTypeCode}, and {@code tranCatCode}.
     *
     * @return the hash code derived from the composite key fields
     */
    @Override
    public int hashCode() {
        return Objects.hash(groupId, tranTypeCode, tranCatCode);
    }

    /**
     * Returns a string representation of this discount group record.
     * Includes all key fields and the interest rate.
     *
     * @return a human-readable string representation
     */
    @Override
    public String toString() {
        return "DiscountGroup{"
                + "groupId='" + groupId + '\''
                + ", tranTypeCode='" + tranTypeCode + '\''
                + ", tranCatCode=" + tranCatCode
                + ", interestRate=" + interestRate
                + '}';
    }

    // ========================================================================
    // Composite Primary Key Class
    // ========================================================================

    /**
     * Composite primary key class for {@link DiscountGroup}.
     *
     * <p>Implements {@link Serializable} as required by the JPA specification
     * for {@code @IdClass} composite keys. The field names must match exactly
     * the {@code @Id} fields in the entity class.</p>
     *
     * <p>The composite key (dis_acct_group_id, dis_tran_type_cd, dis_tran_cat_cd) mirrors
     * the VSAM KSDS primary key DIS-GROUP-KEY.</p>
     */
    public static class DiscountGroupId implements Serializable {

        @Serial
        private static final long serialVersionUID = 1L;

        /** Account group identifier — matches entity field {@code groupId}. */
        private String groupId;

        /** Transaction type code — matches entity field {@code tranTypeCode}. */
        private String tranTypeCode;

        /** Transaction category code — matches entity field {@code tranCatCode}. */
        private Integer tranCatCode;

        /** Default constructor required by JPA. */
        public DiscountGroupId() {
            // Required by JPA specification
        }

        /**
         * Constructs a composite key with all key components.
         *
         * @param groupId      the discount account group identifier
         * @param tranTypeCode the 2-character transaction type code
         * @param tranCatCode  the category code as Integer
         */
        public DiscountGroupId(String groupId, String tranTypeCode, Integer tranCatCode) {
            this.groupId = groupId;
            this.tranTypeCode = tranTypeCode;
            this.tranCatCode = tranCatCode;
        }

        /** Returns the account group identifier. */
        public String getGroupId() {
            return groupId;
        }

        /** Sets the account group identifier. */
        public void setGroupId(String groupId) {
            this.groupId = groupId;
        }

        /** Returns the transaction type code. */
        public String getTranTypeCode() {
            return tranTypeCode;
        }

        /** Sets the transaction type code. */
        public void setTranTypeCode(String tranTypeCode) {
            this.tranTypeCode = tranTypeCode;
        }

        /** Returns the transaction category code. */
        public Integer getTranCatCode() {
            return tranCatCode;
        }

        /** Sets the transaction category code. */
        public void setTranCatCode(Integer tranCatCode) {
            this.tranCatCode = tranCatCode;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) {
                return true;
            }
            if (o == null || getClass() != o.getClass()) {
                return false;
            }
            DiscountGroupId that = (DiscountGroupId) o;
            return Objects.equals(groupId, that.groupId)
                    && Objects.equals(tranTypeCode, that.tranTypeCode)
                    && Objects.equals(tranCatCode, that.tranCatCode);
        }

        @Override
        public int hashCode() {
            return Objects.hash(groupId, tranTypeCode, tranCatCode);
        }

        @Override
        public String toString() {
            return "DiscountGroupId{"
                    + "groupId='" + groupId + '\''
                    + ", tranTypeCode='" + tranTypeCode + '\''
                    + ", tranCatCode=" + tranCatCode
                    + '}';
        }
    }
}
