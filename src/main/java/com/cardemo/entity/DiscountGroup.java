/*
 * DiscountGroup.java — JPA Entity for VSAM DISCGRP reference data
 *
 * Faithfully translates the COBOL copybook CVTRA02Y.cpy DIS-GROUP-RECORD
 * (50 bytes) into a JPA entity mapped to the PostgreSQL 'discount_group' table.
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
 * Data source: app/data/ASCII/discgrp.txt — 51 records in 3 blocks:
 *   Lines  1-17: Group "A"       (17 category combinations)
 *   Lines 18-34: Group "DEFAULT" (17 category combinations)
 *   Lines 35-51: Group "ZEROAPR" (17 category combinations, zero interest)
 *
 * Key Decision: Uses a surrogate @Id (Long) with a @UniqueConstraint on the
 * composite natural key (group_id + tran_type_code + tran_cat_code) to preserve
 * VSAM KSDS DIS-GROUP-KEY semantics while simplifying JPA identity management.
 *
 * @see app/cpy/CVTRA02Y.cpy — COBOL source copybook
 * @see app/data/ASCII/discgrp.txt — Reference data (51 records)
 */
package com.cardemo.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import java.math.BigDecimal;
import java.util.Objects;

/**
 * JPA entity representing a discount group reference record.
 *
 * <p>Maps the VSAM DISCGRP dataset (DIS-GROUP-RECORD from CVTRA02Y.cpy, 50 bytes)
 * to the PostgreSQL {@code discount_group} table. Each record defines an interest
 * rate for a specific combination of account group, transaction type, and
 * transaction category.</p>
 *
 * <p>The composite natural key (groupId + tranTypeCode + tranCatCode) mirrors the
 * COBOL DIS-GROUP-KEY structure and is enforced via a unique constraint. A surrogate
 * {@code id} serves as the JPA primary key for simplified entity management.</p>
 *
 * <p><strong>BigDecimal mandate:</strong> The {@code interestRate} field uses
 * {@link BigDecimal} (never {@code float} or {@code double}) to match the COBOL
 * PIC S9(04)V99 specification with exact decimal precision (scale=2).</p>
 */
@Entity
@Table(
    name = "discount_group",
    uniqueConstraints = @UniqueConstraint(
        name = "uk_discount_group_natural_key",
        columnNames = {"group_id", "tran_type_code", "tran_cat_code"}
    )
)
public class DiscountGroup {

    /**
     * Surrogate primary key — auto-generated identity column.
     * Replaces the composite VSAM KSDS key for JPA simplicity.
     */
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    /**
     * Discount account group identifier.
     * Maps to DIS-ACCT-GROUP-ID PIC X(10).
     * Values in seed data: "A", "DEFAULT", "ZEROAPR" (space-padded to 10 chars in COBOL source).
     */
    @Column(name = "group_id", length = 10, nullable = false)
    private String groupId;

    /**
     * Transaction type code.
     * Maps to DIS-TRAN-TYPE-CD PIC X(02).
     * 2-character code (e.g., "01" through "07") linking to transaction types.
     */
    @Column(name = "tran_type_code", length = 2, nullable = false)
    private String tranTypeCode;

    /**
     * Transaction category code.
     * Maps to DIS-TRAN-CAT-CD PIC 9(04).
     * Stored as String to preserve leading zeros (e.g., "0001", "0002").
     */
    @Column(name = "tran_cat_code", length = 4, nullable = false)
    private String tranCatCode;

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
    @Column(name = "interest_rate", precision = 6, scale = 2, nullable = false)
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
     * @param tranCatCode  the transaction category code (DIS-TRAN-CAT-CD, 4 chars)
     * @param interestRate the interest rate (DIS-INT-RATE, BigDecimal with scale 2)
     */
    public DiscountGroup(String groupId, String tranTypeCode, String tranCatCode,
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
     * Returns the surrogate primary key.
     *
     * @return the auto-generated entity identifier, or {@code null} if not yet persisted
     */
    public Long getId() {
        return id;
    }

    /**
     * Sets the surrogate primary key.
     * Typically managed by JPA; manual assignment is discouraged.
     *
     * @param id the entity identifier
     */
    public void setId(Long id) {
        this.id = id;
    }

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
     * @return the 4-character transaction category code (DIS-TRAN-CAT-CD)
     */
    public String getTranCatCode() {
        return tranCatCode;
    }

    /**
     * Sets the transaction category code.
     *
     * @param tranCatCode the 4-character category code (leading zeros preserved)
     */
    public void setTranCatCode(String tranCatCode) {
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
    // equals, hashCode, toString — based on composite natural key
    // ========================================================================

    /**
     * Compares this entity with another object for equality based on the
     * composite natural key: {@code groupId}, {@code tranTypeCode}, and
     * {@code tranCatCode}. This matches the VSAM KSDS DIS-GROUP-KEY semantics.
     *
     * @param o the object to compare with
     * @return {@code true} if both objects have the same natural key values
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
     * Computes the hash code based on the composite natural key:
     * {@code groupId}, {@code tranTypeCode}, and {@code tranCatCode}.
     *
     * @return the hash code derived from the natural key fields
     */
    @Override
    public int hashCode() {
        return Objects.hash(groupId, tranTypeCode, tranCatCode);
    }

    /**
     * Returns a string representation of this discount group record.
     * Includes the entity name, all key fields, and the interest rate.
     *
     * @return a human-readable string representation
     */
    @Override
    public String toString() {
        return "DiscountGroup{"
                + "id=" + id
                + ", groupId='" + groupId + '\''
                + ", tranTypeCode='" + tranTypeCode + '\''
                + ", tranCatCode='" + tranCatCode + '\''
                + ", interestRate=" + interestRate
                + '}';
    }
}
