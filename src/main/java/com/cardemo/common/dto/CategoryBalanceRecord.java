package com.cardemo.common.dto;

import java.math.BigDecimal;
import java.util.Objects;

import jakarta.validation.constraints.Size;

/**
 * Transaction category balance record DTO — translated from CVTRA01Y.cpy
 * (TRAN-CAT-BAL-RECORD, RECLN 50).
 *
 * <p>Represents the transaction category balance aggregation record used by the
 * TCATBALF VSAM dataset. The COBOL group structure is flattened: the 05-level
 * TRAN-CAT-KEY group containing three 10-level fields becomes three top-level
 * Java fields. The composite key consists of
 * ({@code trancatAcctId}, {@code trancatTypeCd}, {@code trancatCd}).
 *
 * <pre>
 * COBOL Source (CVTRA01Y.cpy):
 * 01  TRAN-CAT-BAL-RECORD.
 *     05  TRAN-CAT-KEY.
 *        10 TRANCAT-ACCT-ID           PIC 9(11).
 *        10 TRANCAT-TYPE-CD           PIC X(02).
 *        10 TRANCAT-CD                PIC 9(04).
 *     05  TRAN-CAT-BAL               PIC S9(09)V99.
 *     05  FILLER                     PIC X(22).
 * </pre>
 *
 * @see com.cardemo.entity.CategoryBalance
 */
public class CategoryBalanceRecord {

    // ---------------------------------------------------------------
    // Fields — flattened from TRAN-CAT-KEY group + TRAN-CAT-BAL
    // FILLER PIC X(22) is intentionally NOT mapped.
    // ---------------------------------------------------------------

    /** TRANCAT-ACCT-ID — PIC 9(11), 11-digit account ID (composite key part 1). */
    @Size(max = 11)
    private String trancatAcctId;

    /** TRANCAT-TYPE-CD — PIC X(02), transaction type code (composite key part 2). */
    @Size(max = 2)
    private String trancatTypeCd;

    /** TRANCAT-CD — PIC 9(04), transaction category code (composite key part 3). */
    private int trancatCd;

    /**
     * TRAN-CAT-BAL — PIC S9(09)V99, signed packed decimal category balance.
     * MUST be {@link BigDecimal} with scale 2 — never {@code float} or {@code double}.
     */
    private BigDecimal tranCatBal;

    // ---------------------------------------------------------------
    // Constructors
    // ---------------------------------------------------------------

    /** Default no-arg constructor. */
    public CategoryBalanceRecord() {
        // No-arg constructor required for frameworks (JPA, Jackson, etc.)
    }

    /**
     * All-args constructor.
     *
     * @param trancatAcctId 11-digit account ID
     * @param trancatTypeCd 2-char transaction type code
     * @param trancatCd     4-digit transaction category code
     * @param tranCatBal    category balance (BigDecimal, scale 2)
     */
    public CategoryBalanceRecord(String trancatAcctId, String trancatTypeCd,
                                 int trancatCd, BigDecimal tranCatBal) {
        this.trancatAcctId = trancatAcctId;
        this.trancatTypeCd = trancatTypeCd;
        this.trancatCd = trancatCd;
        this.tranCatBal = tranCatBal;
    }

    // ---------------------------------------------------------------
    // Getters and Setters
    // ---------------------------------------------------------------

    /**
     * Returns the 11-digit account ID (TRANCAT-ACCT-ID).
     *
     * @return account ID
     */
    public String getTrancatAcctId() {
        return trancatAcctId;
    }

    /**
     * Sets the 11-digit account ID (TRANCAT-ACCT-ID).
     *
     * @param trancatAcctId account ID (max 11 characters)
     */
    public void setTrancatAcctId(String trancatAcctId) {
        this.trancatAcctId = trancatAcctId;
    }

    /**
     * Returns the 2-char transaction type code (TRANCAT-TYPE-CD).
     *
     * @return transaction type code
     */
    public String getTrancatTypeCd() {
        return trancatTypeCd;
    }

    /**
     * Sets the 2-char transaction type code (TRANCAT-TYPE-CD).
     *
     * @param trancatTypeCd transaction type code (max 2 characters)
     */
    public void setTrancatTypeCd(String trancatTypeCd) {
        this.trancatTypeCd = trancatTypeCd;
    }

    /**
     * Returns the 4-digit transaction category code (TRANCAT-CD).
     *
     * @return transaction category code
     */
    public int getTrancatCd() {
        return trancatCd;
    }

    /**
     * Sets the 4-digit transaction category code (TRANCAT-CD).
     *
     * @param trancatCd transaction category code (4-digit numeric)
     */
    public void setTrancatCd(int trancatCd) {
        this.trancatCd = trancatCd;
    }

    /**
     * Returns the category balance (TRAN-CAT-BAL, PIC S9(09)V99).
     *
     * @return category balance as {@link BigDecimal} with scale 2
     */
    public BigDecimal getTranCatBal() {
        return tranCatBal;
    }

    /**
     * Sets the category balance (TRAN-CAT-BAL, PIC S9(09)V99).
     *
     * @param tranCatBal category balance as {@link BigDecimal} (scale 2)
     */
    public void setTranCatBal(BigDecimal tranCatBal) {
        this.tranCatBal = tranCatBal;
    }

    // ---------------------------------------------------------------
    // toString, equals, hashCode
    // ---------------------------------------------------------------

    /**
     * Returns a string representation including all four fields.
     *
     * @return formatted string with account ID, type code, category code, and balance
     */
    @Override
    public String toString() {
        return "CategoryBalanceRecord{"
                + "trancatAcctId='" + trancatAcctId + '\''
                + ", trancatTypeCd='" + trancatTypeCd + '\''
                + ", trancatCd=" + trancatCd
                + ", tranCatBal=" + tranCatBal
                + '}';
    }

    /**
     * Equality based on the composite key: trancatAcctId + trancatTypeCd + trancatCd.
     * This mirrors the COBOL TRAN-CAT-KEY group which serves as the VSAM primary key.
     *
     * @param o the object to compare
     * @return {@code true} if the composite keys are equal
     */
    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof CategoryBalanceRecord that)) {
            return false;
        }
        return trancatCd == that.trancatCd
                && Objects.equals(trancatAcctId, that.trancatAcctId)
                && Objects.equals(trancatTypeCd, that.trancatTypeCd);
    }

    /**
     * Hash code based on the composite key: trancatAcctId + trancatTypeCd + trancatCd.
     *
     * @return hash code derived from the three composite key fields
     */
    @Override
    public int hashCode() {
        return Objects.hash(trancatAcctId, trancatTypeCd, trancatCd);
    }
}
