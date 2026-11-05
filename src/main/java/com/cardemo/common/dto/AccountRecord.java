package com.cardemo.common.dto;

import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.util.Objects;

/**
 * Account record DTO — translated from CVACT01Y.cpy (ACCOUNT-RECORD, RECLN 300).
 *
 * <p>Represents the account data record layout used by the ACCTDATA VSAM dataset.
 * Each account has an 11-digit primary key ({@code acctId}), status flags,
 * monetary balances (all stored as {@link BigDecimal} with scale 2 to preserve
 * exact COBOL {@code PIC S9(10)V99} packed-decimal semantics), date fields,
 * and reference identifiers.</p>
 *
 * <h3>COBOL Source Mapping (CVACT01Y.cpy):</h3>
 * <pre>
 *   01 ACCOUNT-RECORD.
 *       05 ACCT-ID                PIC 9(11).        → acctId           (PK)
 *       05 ACCT-ACTIVE-STATUS     PIC X(01).        → acctActiveStatus
 *       05 ACCT-CURR-BAL          PIC S9(10)V99.    → acctCurrBal      (BigDecimal, scale 2)
 *       05 ACCT-CREDIT-LIMIT      PIC S9(10)V99.    → acctCreditLimit  (BigDecimal, scale 2)
 *       05 ACCT-CASH-CREDIT-LIMIT PIC S9(10)V99.    → acctCashCreditLimit (BigDecimal, scale 2)
 *       05 ACCT-OPEN-DATE         PIC X(10).        → acctOpenDate
 *       05 ACCT-EXPIRAION-DATE    PIC X(10).        → acctExpiraionDate (typo preserved)
 *       05 ACCT-REISSUE-DATE      PIC X(10).        → acctReissueDate
 *       05 ACCT-CURR-CYC-CREDIT   PIC S9(10)V99.    → acctCurrCycCredit (BigDecimal, scale 2)
 *       05 ACCT-CURR-CYC-DEBIT    PIC S9(10)V99.    → acctCurrCycDebit  (BigDecimal, scale 2)
 *       05 ACCT-ADDR-ZIP          PIC X(10).        → acctAddrZip
 *       05 ACCT-GROUP-ID          PIC X(10).        → acctGroupId
 *       05 FILLER                  PIC X(178).       → not mapped (padding only)
 * </pre>
 *
 * <p>Total COBOL record length: 300 bytes.
 * Mapped fields account for 122 bytes (11 + 1 + 12 + 12 + 12 + 10 + 10 + 10 + 12 + 12 + 10 + 10);
 * the remaining 178 bytes are FILLER padding and are not represented in Java.</p>
 *
 * <p><strong>Monetary Field Rule:</strong> All five monetary fields
 * ({@code acctCurrBal}, {@code acctCreditLimit}, {@code acctCashCreditLimit},
 * {@code acctCurrCycCredit}, {@code acctCurrCycDebit}) use {@link BigDecimal}
 * exclusively — never {@code float} or {@code double} — to preserve the exact
 * decimal arithmetic semantics of COBOL packed-decimal (COMP-3) fields.</p>
 *
 * <p><strong>Intentional Typo:</strong> The field name {@code acctExpiraionDate}
 * preserves the original COBOL misspelling {@code ACCT-EXPIRAION-DATE} from
 * CVACT01Y.cpy for faithful source-to-target traceability.</p>
 *
 * <p>Identity is based on {@code acctId} (the VSAM primary key ACCT-ID).</p>
 */
public class AccountRecord {

    // ========================================================================
    // Fields — mapped 1:1 from CVACT01Y.cpy 05-level entries
    // ========================================================================

    /**
     * Account identifier — maps to COBOL field ACCT-ID PIC 9(11).
     * This is the primary key of the ACCTDATA VSAM dataset.
     * Stored as a zero-padded numeric string (e.g., "00000000041").
     */
    @Size(max = 11)
    private String acctId;

    /**
     * Account active status flag — maps to COBOL field ACCT-ACTIVE-STATUS PIC X(01).
     * Typically 'Y' for active or 'N' for inactive.
     */
    @Size(max = 1)
    private String acctActiveStatus;

    /**
     * Current account balance — maps to COBOL field ACCT-CURR-BAL PIC S9(10)V99.
     * Packed-decimal (COMP-3) field requiring {@link BigDecimal} with scale 2.
     * Signed: may be negative for overdrawn accounts.
     */
    private BigDecimal acctCurrBal;

    /**
     * Credit limit — maps to COBOL field ACCT-CREDIT-LIMIT PIC S9(10)V99.
     * Packed-decimal (COMP-3) field requiring {@link BigDecimal} with scale 2.
     * Maximum allowable balance on the account.
     */
    private BigDecimal acctCreditLimit;

    /**
     * Cash credit limit — maps to COBOL field ACCT-CASH-CREDIT-LIMIT PIC S9(10)V99.
     * Packed-decimal (COMP-3) field requiring {@link BigDecimal} with scale 2.
     * Maximum cash advance limit on the account.
     */
    private BigDecimal acctCashCreditLimit;

    /**
     * Account open date — maps to COBOL field ACCT-OPEN-DATE PIC X(10).
     * Date stored as a character string in the format used by the original system.
     */
    @Size(max = 10)
    private String acctOpenDate;

    /**
     * Account expiration date — maps to COBOL field ACCT-EXPIRAION-DATE PIC X(10).
     *
     * <p><strong>Note:</strong> The field name preserves the original COBOL misspelling
     * ("EXPIRAION" instead of "EXPIRATION") for faithful traceability to
     * CVACT01Y.cpy source.</p>
     */
    @Size(max = 10)
    private String acctExpiraionDate;

    /**
     * Card reissue date — maps to COBOL field ACCT-REISSUE-DATE PIC X(10).
     * Date when the card associated with this account was last reissued.
     */
    @Size(max = 10)
    private String acctReissueDate;

    /**
     * Current cycle credit total — maps to COBOL field ACCT-CURR-CYC-CREDIT PIC S9(10)V99.
     * Packed-decimal field requiring {@link BigDecimal} with scale 2.
     * Sum of all credit (payment) transactions in the current billing cycle.
     */
    private BigDecimal acctCurrCycCredit;

    /**
     * Current cycle debit total — maps to COBOL field ACCT-CURR-CYC-DEBIT PIC S9(10)V99.
     * Packed-decimal field requiring {@link BigDecimal} with scale 2.
     * Sum of all debit (charge) transactions in the current billing cycle.
     */
    private BigDecimal acctCurrCycDebit;

    /**
     * Address ZIP code — maps to COBOL field ACCT-ADDR-ZIP PIC X(10).
     * May contain ZIP+4 format (e.g., "12345-6789") or international postal codes.
     */
    @Size(max = 10)
    private String acctAddrZip;

    /**
     * Account group identifier — maps to COBOL field ACCT-GROUP-ID PIC X(10).
     * References the discount or rate group to which this account belongs.
     */
    @Size(max = 10)
    private String acctGroupId;

    // ========================================================================
    // Constructors
    // ========================================================================

    /**
     * Default no-argument constructor.
     * Required for frameworks (JPA, Jackson, Spring) that instantiate via reflection.
     */
    public AccountRecord() {
    }

    /**
     * All-arguments constructor for creating a fully populated account record.
     *
     * @param acctId              the 11-digit account identifier (ACCT-ID)
     * @param acctActiveStatus    the single-character active status flag (ACCT-ACTIVE-STATUS)
     * @param acctCurrBal         the current balance as {@link BigDecimal} (ACCT-CURR-BAL)
     * @param acctCreditLimit     the credit limit as {@link BigDecimal} (ACCT-CREDIT-LIMIT)
     * @param acctCashCreditLimit the cash credit limit as {@link BigDecimal} (ACCT-CASH-CREDIT-LIMIT)
     * @param acctOpenDate        the account open date string (ACCT-OPEN-DATE)
     * @param acctExpiraionDate   the expiration date string (ACCT-EXPIRAION-DATE, typo preserved)
     * @param acctReissueDate     the reissue date string (ACCT-REISSUE-DATE)
     * @param acctCurrCycCredit   the current cycle credit total as {@link BigDecimal} (ACCT-CURR-CYC-CREDIT)
     * @param acctCurrCycDebit    the current cycle debit total as {@link BigDecimal} (ACCT-CURR-CYC-DEBIT)
     * @param acctAddrZip         the address ZIP code (ACCT-ADDR-ZIP)
     * @param acctGroupId         the account group identifier (ACCT-GROUP-ID)
     */
    public AccountRecord(String acctId,
                         String acctActiveStatus,
                         BigDecimal acctCurrBal,
                         BigDecimal acctCreditLimit,
                         BigDecimal acctCashCreditLimit,
                         String acctOpenDate,
                         String acctExpiraionDate,
                         String acctReissueDate,
                         BigDecimal acctCurrCycCredit,
                         BigDecimal acctCurrCycDebit,
                         String acctAddrZip,
                         String acctGroupId) {
        this.acctId = acctId;
        this.acctActiveStatus = acctActiveStatus;
        this.acctCurrBal = acctCurrBal;
        this.acctCreditLimit = acctCreditLimit;
        this.acctCashCreditLimit = acctCashCreditLimit;
        this.acctOpenDate = acctOpenDate;
        this.acctExpiraionDate = acctExpiraionDate;
        this.acctReissueDate = acctReissueDate;
        this.acctCurrCycCredit = acctCurrCycCredit;
        this.acctCurrCycDebit = acctCurrCycDebit;
        this.acctAddrZip = acctAddrZip;
        this.acctGroupId = acctGroupId;
    }

    // ========================================================================
    // Getters and Setters
    // ========================================================================

    /**
     * Returns the account identifier (ACCT-ID).
     *
     * @return the 11-digit account ID, or {@code null} if not set
     */
    public String getAcctId() {
        return acctId;
    }

    /**
     * Sets the account identifier (ACCT-ID).
     *
     * @param acctId the 11-digit account ID
     */
    public void setAcctId(String acctId) {
        this.acctId = acctId;
    }

    /**
     * Returns the account active status flag (ACCT-ACTIVE-STATUS).
     *
     * @return the single-character status flag, or {@code null} if not set
     */
    public String getAcctActiveStatus() {
        return acctActiveStatus;
    }

    /**
     * Sets the account active status flag (ACCT-ACTIVE-STATUS).
     *
     * @param acctActiveStatus the single-character status flag
     */
    public void setAcctActiveStatus(String acctActiveStatus) {
        this.acctActiveStatus = acctActiveStatus;
    }

    /**
     * Returns the current account balance (ACCT-CURR-BAL).
     *
     * @return the current balance as {@link BigDecimal} with scale 2, or {@code null} if not set
     */
    public BigDecimal getAcctCurrBal() {
        return acctCurrBal;
    }

    /**
     * Sets the current account balance (ACCT-CURR-BAL).
     *
     * @param acctCurrBal the current balance as {@link BigDecimal}
     */
    public void setAcctCurrBal(BigDecimal acctCurrBal) {
        this.acctCurrBal = acctCurrBal;
    }

    /**
     * Returns the credit limit (ACCT-CREDIT-LIMIT).
     *
     * @return the credit limit as {@link BigDecimal} with scale 2, or {@code null} if not set
     */
    public BigDecimal getAcctCreditLimit() {
        return acctCreditLimit;
    }

    /**
     * Sets the credit limit (ACCT-CREDIT-LIMIT).
     *
     * @param acctCreditLimit the credit limit as {@link BigDecimal}
     */
    public void setAcctCreditLimit(BigDecimal acctCreditLimit) {
        this.acctCreditLimit = acctCreditLimit;
    }

    /**
     * Returns the cash credit limit (ACCT-CASH-CREDIT-LIMIT).
     *
     * @return the cash credit limit as {@link BigDecimal} with scale 2, or {@code null} if not set
     */
    public BigDecimal getAcctCashCreditLimit() {
        return acctCashCreditLimit;
    }

    /**
     * Sets the cash credit limit (ACCT-CASH-CREDIT-LIMIT).
     *
     * @param acctCashCreditLimit the cash credit limit as {@link BigDecimal}
     */
    public void setAcctCashCreditLimit(BigDecimal acctCashCreditLimit) {
        this.acctCashCreditLimit = acctCashCreditLimit;
    }

    /**
     * Returns the account open date (ACCT-OPEN-DATE).
     *
     * @return the open date string, or {@code null} if not set
     */
    public String getAcctOpenDate() {
        return acctOpenDate;
    }

    /**
     * Sets the account open date (ACCT-OPEN-DATE).
     *
     * @param acctOpenDate the open date string
     */
    public void setAcctOpenDate(String acctOpenDate) {
        this.acctOpenDate = acctOpenDate;
    }

    /**
     * Returns the account expiration date (ACCT-EXPIRAION-DATE).
     *
     * <p><strong>Note:</strong> Field name preserves the original COBOL misspelling.</p>
     *
     * @return the expiration date string, or {@code null} if not set
     */
    public String getAcctExpiraionDate() {
        return acctExpiraionDate;
    }

    /**
     * Sets the account expiration date (ACCT-EXPIRAION-DATE).
     *
     * <p><strong>Note:</strong> Field name preserves the original COBOL misspelling.</p>
     *
     * @param acctExpiraionDate the expiration date string
     */
    public void setAcctExpiraionDate(String acctExpiraionDate) {
        this.acctExpiraionDate = acctExpiraionDate;
    }

    /**
     * Returns the card reissue date (ACCT-REISSUE-DATE).
     *
     * @return the reissue date string, or {@code null} if not set
     */
    public String getAcctReissueDate() {
        return acctReissueDate;
    }

    /**
     * Sets the card reissue date (ACCT-REISSUE-DATE).
     *
     * @param acctReissueDate the reissue date string
     */
    public void setAcctReissueDate(String acctReissueDate) {
        this.acctReissueDate = acctReissueDate;
    }

    /**
     * Returns the current cycle credit total (ACCT-CURR-CYC-CREDIT).
     *
     * @return the cycle credit total as {@link BigDecimal} with scale 2, or {@code null} if not set
     */
    public BigDecimal getAcctCurrCycCredit() {
        return acctCurrCycCredit;
    }

    /**
     * Sets the current cycle credit total (ACCT-CURR-CYC-CREDIT).
     *
     * @param acctCurrCycCredit the cycle credit total as {@link BigDecimal}
     */
    public void setAcctCurrCycCredit(BigDecimal acctCurrCycCredit) {
        this.acctCurrCycCredit = acctCurrCycCredit;
    }

    /**
     * Returns the current cycle debit total (ACCT-CURR-CYC-DEBIT).
     *
     * @return the cycle debit total as {@link BigDecimal} with scale 2, or {@code null} if not set
     */
    public BigDecimal getAcctCurrCycDebit() {
        return acctCurrCycDebit;
    }

    /**
     * Sets the current cycle debit total (ACCT-CURR-CYC-DEBIT).
     *
     * @param acctCurrCycDebit the cycle debit total as {@link BigDecimal}
     */
    public void setAcctCurrCycDebit(BigDecimal acctCurrCycDebit) {
        this.acctCurrCycDebit = acctCurrCycDebit;
    }

    /**
     * Returns the address ZIP code (ACCT-ADDR-ZIP).
     *
     * @return the ZIP code string, or {@code null} if not set
     */
    public String getAcctAddrZip() {
        return acctAddrZip;
    }

    /**
     * Sets the address ZIP code (ACCT-ADDR-ZIP).
     *
     * @param acctAddrZip the ZIP code string
     */
    public void setAcctAddrZip(String acctAddrZip) {
        this.acctAddrZip = acctAddrZip;
    }

    /**
     * Returns the account group identifier (ACCT-GROUP-ID).
     *
     * @return the group ID string, or {@code null} if not set
     */
    public String getAcctGroupId() {
        return acctGroupId;
    }

    /**
     * Sets the account group identifier (ACCT-GROUP-ID).
     *
     * @param acctGroupId the group ID string
     */
    public void setAcctGroupId(String acctGroupId) {
        this.acctGroupId = acctGroupId;
    }

    // ========================================================================
    // Object overrides
    // ========================================================================

    /**
     * Returns a string representation of this account record, including all 12 mapped fields.
     *
     * @return a descriptive string containing every field value
     */
    @Override
    public String toString() {
        return "AccountRecord{"
                + "acctId='" + acctId + '\''
                + ", acctActiveStatus='" + acctActiveStatus + '\''
                + ", acctCurrBal=" + acctCurrBal
                + ", acctCreditLimit=" + acctCreditLimit
                + ", acctCashCreditLimit=" + acctCashCreditLimit
                + ", acctOpenDate='" + acctOpenDate + '\''
                + ", acctExpiraionDate='" + acctExpiraionDate + '\''
                + ", acctReissueDate='" + acctReissueDate + '\''
                + ", acctCurrCycCredit=" + acctCurrCycCredit
                + ", acctCurrCycDebit=" + acctCurrCycDebit
                + ", acctAddrZip='" + acctAddrZip + '\''
                + ", acctGroupId='" + acctGroupId + '\''
                + '}';
    }

    /**
     * Compares this account record with another object for equality.
     * Identity is based solely on {@code acctId} (the VSAM primary key ACCT-ID).
     *
     * @param o the object to compare with
     * @return {@code true} if the other object is an {@code AccountRecord}
     *         with the same {@code acctId}
     */
    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof AccountRecord other)) {
            return false;
        }
        return Objects.equals(acctId, other.acctId);
    }

    /**
     * Returns a hash code based on {@code acctId} (the VSAM primary key).
     *
     * @return the hash code derived from the account ID
     */
    @Override
    public int hashCode() {
        return Objects.hash(acctId);
    }
}
