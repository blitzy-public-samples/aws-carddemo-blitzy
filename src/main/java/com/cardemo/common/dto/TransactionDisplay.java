package com.cardemo.common.dto;

import java.math.BigDecimal;
import java.util.Objects;

import jakarta.validation.constraints.Size;

/**
 * Transaction display DTO — display-formatted view of category balance data
 * from CVTRA01Y.cpy (TRAN-CAT-BAL-RECORD).
 *
 * <p>While {@code CategoryBalanceRecord} represents the raw record layout,
 * this class serves as the display-oriented DTO for presenting category
 * balance data in online transaction views. It augments the base fields
 * from the COBOL copybook with resolved descriptions and formatted values
 * for presentation purposes.</p>
 *
 * <h3>Source COBOL Structure (CVTRA01Y.cpy):</h3>
 * <pre>{@code
 * 01  TRAN-CAT-BAL-RECORD.
 *     05  TRAN-CAT-KEY.
 *        10 TRANCAT-ACCT-ID           PIC 9(11).
 *        10 TRANCAT-TYPE-CD           PIC X(02).
 *        10 TRANCAT-CD                PIC 9(04).
 *     05  TRAN-CAT-BAL               PIC S9(09)V99.
 *     05  FILLER                     PIC X(22).
 * }</pre>
 *
 * <p>Composite key: {@code trancatAcctId + trancatTypeCd + trancatCd}</p>
 *
 * @see com.cardemo.common.dto.CategoryBalanceRecord
 */
public class TransactionDisplay {

    // ---------------------------------------------------------------
    // Fields from TRAN-CAT-BAL-RECORD (CVTRA01Y.cpy)
    // ---------------------------------------------------------------

    /**
     * Account identifier — TRANCAT-ACCT-ID PIC 9(11).
     * Part of the composite key (TRAN-CAT-KEY).
     */
    @Size(max = 11)
    private String trancatAcctId;

    /**
     * Transaction type code — TRANCAT-TYPE-CD PIC X(02).
     * Part of the composite key (TRAN-CAT-KEY).
     */
    @Size(max = 2)
    private String trancatTypeCd;

    /**
     * Transaction category code — TRANCAT-CD PIC 9(04).
     * Part of the composite key (TRAN-CAT-KEY).
     */
    private int trancatCd;

    /**
     * Category balance — TRAN-CAT-BAL PIC S9(09)V99.
     * Uses {@link BigDecimal} with scale 2 to preserve exact COBOL
     * packed-decimal (COMP-3) semantics. NEVER use float or double
     * for monetary/balance values.
     */
    private BigDecimal tranCatBal;

    // ---------------------------------------------------------------
    // Display-only fields (resolved descriptions for presentation)
    // ---------------------------------------------------------------

    /**
     * Resolved description of the transaction type, looked up from
     * the TransactionType reference data. Display-only field not
     * present in the original COBOL copybook.
     */
    private String tranTypeDesc;

    /**
     * Resolved description of the transaction category, looked up from
     * the TransactionCategory reference data. Display-only field not
     * present in the original COBOL copybook.
     */
    private String tranCatDesc;

    /**
     * Formatted balance string for presentation (e.g., "$1,234.56").
     * Display-only field derived from {@link #tranCatBal}.
     */
    private String formattedBalance;

    // ---------------------------------------------------------------
    // Constructors
    // ---------------------------------------------------------------

    /**
     * Default no-arg constructor required for frameworks (JPA, Jackson,
     * Bean Validation) and general-purpose instantiation.
     */
    public TransactionDisplay() {
        // Default constructor — fields initialized to Java defaults
    }

    /**
     * All-args constructor for creating a fully populated display instance.
     *
     * @param trancatAcctId    account identifier (max 11 chars)
     * @param trancatTypeCd    transaction type code (max 2 chars)
     * @param trancatCd        transaction category code (4 digits)
     * @param tranCatBal       category balance (BigDecimal, scale 2)
     * @param tranTypeDesc     resolved transaction type description
     * @param tranCatDesc      resolved transaction category description
     * @param formattedBalance formatted balance for display
     */
    public TransactionDisplay(String trancatAcctId,
                              String trancatTypeCd,
                              int trancatCd,
                              BigDecimal tranCatBal,
                              String tranTypeDesc,
                              String tranCatDesc,
                              String formattedBalance) {
        this.trancatAcctId = trancatAcctId;
        this.trancatTypeCd = trancatTypeCd;
        this.trancatCd = trancatCd;
        this.tranCatBal = tranCatBal;
        this.tranTypeDesc = tranTypeDesc;
        this.tranCatDesc = tranCatDesc;
        this.formattedBalance = formattedBalance;
    }

    // ---------------------------------------------------------------
    // Getters and Setters
    // ---------------------------------------------------------------

    /**
     * Returns the account identifier (TRANCAT-ACCT-ID).
     *
     * @return account ID string, up to 11 characters
     */
    public String getTrancatAcctId() {
        return trancatAcctId;
    }

    /**
     * Sets the account identifier (TRANCAT-ACCT-ID).
     *
     * @param trancatAcctId account ID string, max 11 characters
     */
    public void setTrancatAcctId(String trancatAcctId) {
        this.trancatAcctId = trancatAcctId;
    }

    /**
     * Returns the transaction type code (TRANCAT-TYPE-CD).
     *
     * @return type code string, up to 2 characters
     */
    public String getTrancatTypeCd() {
        return trancatTypeCd;
    }

    /**
     * Sets the transaction type code (TRANCAT-TYPE-CD).
     *
     * @param trancatTypeCd type code string, max 2 characters
     */
    public void setTrancatTypeCd(String trancatTypeCd) {
        this.trancatTypeCd = trancatTypeCd;
    }

    /**
     * Returns the transaction category code (TRANCAT-CD).
     *
     * @return category code as integer (4 digits)
     */
    public int getTrancatCd() {
        return trancatCd;
    }

    /**
     * Sets the transaction category code (TRANCAT-CD).
     *
     * @param trancatCd category code as integer (4 digits)
     */
    public void setTrancatCd(int trancatCd) {
        this.trancatCd = trancatCd;
    }

    /**
     * Returns the category balance (TRAN-CAT-BAL).
     *
     * @return balance as {@link BigDecimal} with scale 2
     */
    public BigDecimal getTranCatBal() {
        return tranCatBal;
    }

    /**
     * Sets the category balance (TRAN-CAT-BAL).
     *
     * @param tranCatBal balance as {@link BigDecimal}, scale 2 expected
     */
    public void setTranCatBal(BigDecimal tranCatBal) {
        this.tranCatBal = tranCatBal;
    }

    /**
     * Returns the resolved transaction type description.
     *
     * @return human-readable description of the transaction type
     */
    public String getTranTypeDesc() {
        return tranTypeDesc;
    }

    /**
     * Sets the resolved transaction type description.
     *
     * @param tranTypeDesc human-readable description of the transaction type
     */
    public void setTranTypeDesc(String tranTypeDesc) {
        this.tranTypeDesc = tranTypeDesc;
    }

    /**
     * Returns the resolved transaction category description.
     *
     * @return human-readable description of the transaction category
     */
    public String getTranCatDesc() {
        return tranCatDesc;
    }

    /**
     * Sets the resolved transaction category description.
     *
     * @param tranCatDesc human-readable description of the transaction category
     */
    public void setTranCatDesc(String tranCatDesc) {
        this.tranCatDesc = tranCatDesc;
    }

    /**
     * Returns the formatted balance string for display presentation.
     *
     * @return formatted balance (e.g., "$1,234.56")
     */
    public String getFormattedBalance() {
        return formattedBalance;
    }

    /**
     * Sets the formatted balance string for display presentation.
     *
     * @param formattedBalance formatted balance (e.g., "$1,234.56")
     */
    public void setFormattedBalance(String formattedBalance) {
        this.formattedBalance = formattedBalance;
    }

    // ---------------------------------------------------------------
    // toString, equals, hashCode
    // ---------------------------------------------------------------

    /**
     * Returns a string representation of this display DTO including all fields.
     *
     * @return formatted string with all field values
     */
    @Override
    public String toString() {
        return "TransactionDisplay{"
                + "trancatAcctId='" + trancatAcctId + '\''
                + ", trancatTypeCd='" + trancatTypeCd + '\''
                + ", trancatCd=" + trancatCd
                + ", tranCatBal=" + tranCatBal
                + ", tranTypeDesc='" + tranTypeDesc + '\''
                + ", tranCatDesc='" + tranCatDesc + '\''
                + ", formattedBalance='" + formattedBalance + '\''
                + '}';
    }

    /**
     * Compares this display DTO to another object for equality based on
     * the composite key: {@code trancatAcctId + trancatTypeCd + trancatCd}.
     * This mirrors the COBOL TRAN-CAT-KEY composite structure.
     *
     * @param o the object to compare with
     * @return {@code true} if the composite keys are equal
     */
    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof TransactionDisplay that)) {
            return false;
        }
        return trancatCd == that.trancatCd
                && Objects.equals(trancatAcctId, that.trancatAcctId)
                && Objects.equals(trancatTypeCd, that.trancatTypeCd);
    }

    /**
     * Computes a hash code based on the composite key:
     * {@code trancatAcctId + trancatTypeCd + trancatCd}.
     *
     * @return hash code for this display DTO
     */
    @Override
    public int hashCode() {
        return Objects.hash(trancatAcctId, trancatTypeCd, trancatCd);
    }
}
