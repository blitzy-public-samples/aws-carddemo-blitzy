package com.cardemo.common.dto;

import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.util.Objects;

/**
 * Disclosure group detail DTO — translated from CVTRA02Y.cpy (DIS-GROUP-RECORD, RECLN 50).
 *
 * <p>Represents the interest rate for a specific account-group / transaction-type /
 * transaction-category combination. The composite key is
 * ({@code disAcctGroupId}, {@code disTranTypeCd}, {@code disTranCatCd}).
 *
 * <h3>Source COBOL structure (CVTRA02Y.cpy):</h3>
 * <pre>
 * 01  DIS-GROUP-RECORD.
 *     05  DIS-GROUP-KEY.
 *        10 DIS-ACCT-GROUP-ID         PIC X(10).
 *        10 DIS-TRAN-TYPE-CD          PIC X(02).
 *        10 DIS-TRAN-CAT-CD           PIC 9(04).
 *     05  DIS-INT-RATE                PIC S9(04)V99.
 *     05  FILLER                      PIC X(28).
 * </pre>
 *
 * <p>The FILLER field (28 bytes of padding to reach RECLN 50) is intentionally
 * omitted from the Java representation — it carries no business semantics in the
 * original COBOL record layout.
 *
 * <p>The COBOL group {@code DIS-GROUP-KEY} is flattened: its three elementary items
 * become top-level Java fields. Identity ({@link #equals}/{@link #hashCode}) is
 * defined on the composite key only; {@code disIntRate} is a data attribute, not
 * part of the identity.
 *
 * @see com.cardemo.entity.DiscountGroup
 */
public class TransactionDetail {

    /**
     * Account group identifier (part of composite key).
     * Maps to COBOL field {@code DIS-ACCT-GROUP-ID PIC X(10)}.
     */
    @Size(max = 10)
    private String disAcctGroupId;

    /**
     * Transaction type code (part of composite key).
     * Maps to COBOL field {@code DIS-TRAN-TYPE-CD PIC X(02)}.
     */
    @Size(max = 2)
    private String disTranTypeCd;

    /**
     * Transaction category code (part of composite key).
     * Maps to COBOL field {@code DIS-TRAN-CAT-CD PIC 9(04)}.
     * Valid range: 0–9999 (4-digit unsigned numeric).
     */
    private int disTranCatCd;

    /**
     * Interest rate for this group/type/category combination.
     * Maps to COBOL field {@code DIS-INT-RATE PIC S9(04)V99} (signed, scale 2).
     *
     * <p><strong>MUST</strong> be {@link BigDecimal} — never {@code float} or
     * {@code double} — to preserve exact decimal semantics matching the original
     * COBOL COMP-3 packed-decimal representation.
     */
    private BigDecimal disIntRate;

    /**
     * Default no-arg constructor required for frameworks (JPA, Jackson, Spring).
     */
    public TransactionDetail() {
        // No-arg constructor for framework compatibility
    }

    /**
     * All-args constructor for programmatic construction.
     *
     * @param disAcctGroupId account group identifier (max length 10)
     * @param disTranTypeCd  transaction type code (max length 2)
     * @param disTranCatCd   transaction category code (0–9999)
     * @param disIntRate     interest rate with scale 2 ({@link BigDecimal})
     */
    public TransactionDetail(String disAcctGroupId, String disTranTypeCd,
                             int disTranCatCd, BigDecimal disIntRate) {
        this.disAcctGroupId = disAcctGroupId;
        this.disTranTypeCd = disTranTypeCd;
        this.disTranCatCd = disTranCatCd;
        this.disIntRate = disIntRate;
    }

    /**
     * Returns the account group identifier.
     *
     * @return account group ID, or {@code null} if not set
     */
    public String getDisAcctGroupId() {
        return disAcctGroupId;
    }

    /**
     * Sets the account group identifier.
     *
     * @param disAcctGroupId account group ID (max length 10)
     */
    public void setDisAcctGroupId(String disAcctGroupId) {
        this.disAcctGroupId = disAcctGroupId;
    }

    /**
     * Returns the transaction type code.
     *
     * @return transaction type code, or {@code null} if not set
     */
    public String getDisTranTypeCd() {
        return disTranTypeCd;
    }

    /**
     * Sets the transaction type code.
     *
     * @param disTranTypeCd transaction type code (max length 2)
     */
    public void setDisTranTypeCd(String disTranTypeCd) {
        this.disTranTypeCd = disTranTypeCd;
    }

    /**
     * Returns the transaction category code.
     *
     * @return transaction category code (0–9999)
     */
    public int getDisTranCatCd() {
        return disTranCatCd;
    }

    /**
     * Sets the transaction category code.
     *
     * @param disTranCatCd transaction category code (0–9999)
     */
    public void setDisTranCatCd(int disTranCatCd) {
        this.disTranCatCd = disTranCatCd;
    }

    /**
     * Returns the interest rate.
     *
     * @return interest rate as {@link BigDecimal} with scale 2, or {@code null} if not set
     */
    public BigDecimal getDisIntRate() {
        return disIntRate;
    }

    /**
     * Sets the interest rate.
     *
     * @param disIntRate interest rate as {@link BigDecimal} with scale 2
     */
    public void setDisIntRate(BigDecimal disIntRate) {
        this.disIntRate = disIntRate;
    }

    /**
     * Returns a string representation including all four fields.
     *
     * @return formatted string with disAcctGroupId, disTranTypeCd, disTranCatCd, and disIntRate
     */
    @Override
    public String toString() {
        return "TransactionDetail{" +
                "disAcctGroupId='" + disAcctGroupId + '\'' +
                ", disTranTypeCd='" + disTranTypeCd + '\'' +
                ", disTranCatCd=" + disTranCatCd +
                ", disIntRate=" + disIntRate +
                '}';
    }

    /**
     * Compares for equality based on the composite key
     * ({@code disAcctGroupId}, {@code disTranTypeCd}, {@code disTranCatCd}).
     *
     * <p>The {@code disIntRate} field is intentionally excluded — it is a data
     * attribute, not part of the record identity. Two disclosure-group records
     * are considered equal when they reference the same account-group,
     * transaction-type, and transaction-category combination.
     *
     * @param o the object to compare with
     * @return {@code true} if the other object is a {@code TransactionDetail}
     *         with the same composite key
     */
    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof TransactionDetail that)) {
            return false;
        }
        return disTranCatCd == that.disTranCatCd
                && Objects.equals(disAcctGroupId, that.disAcctGroupId)
                && Objects.equals(disTranTypeCd, that.disTranTypeCd);
    }

    /**
     * Computes hash code based on the composite key
     * ({@code disAcctGroupId}, {@code disTranTypeCd}, {@code disTranCatCd}).
     *
     * @return hash code derived from the composite key fields
     */
    @Override
    public int hashCode() {
        return Objects.hash(disAcctGroupId, disTranTypeCd, disTranCatCd);
    }
}
