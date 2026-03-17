/*
 * Account.java — JPA Entity mapping VSAM ACCTDATA (300-byte KSDS record)
 *
 * Source: app/cpy/CVACT01Y.cpy (ACCOUNT-RECORD)
 * VSAM Dataset: AWS.M2.CARDDEMO.ACCTDATA.VSAM.KSDS
 * Record Length: 300 bytes
 * Primary Key: ACCT-ID PIC 9(11) — 11-character account identifier
 *
 * COBOL Record Layout (CVACT01Y.cpy):
 *   01  ACCOUNT-RECORD.
 *       05  ACCT-ID                           PIC 9(11).       -> acctId (String, 11 chars)
 *       05  ACCT-ACTIVE-STATUS                PIC X(01).       -> activeStatus (String, 1 char)
 *       05  ACCT-CURR-BAL                     PIC S9(10)V99.   -> currBal (BigDecimal, p=12, s=2)
 *       05  ACCT-CREDIT-LIMIT                 PIC S9(10)V99.   -> creditLimit (BigDecimal, p=12, s=2)
 *       05  ACCT-CASH-CREDIT-LIMIT            PIC S9(10)V99.   -> cashCreditLimit (BigDecimal, p=12, s=2)
 *       05  ACCT-OPEN-DATE                    PIC X(10).       -> openDate (String, 10 chars)
 *       05  ACCT-EXPIRAION-DATE               PIC X(10).       -> expirationDate (String, 10 chars)
 *       05  ACCT-REISSUE-DATE                 PIC X(10).       -> reissueDate (String, 10 chars)
 *       05  ACCT-CURR-CYC-CREDIT              PIC S9(10)V99.   -> currCycCredit (BigDecimal, p=12, s=2)
 *       05  ACCT-CURR-CYC-DEBIT               PIC S9(10)V99.   -> currCycDebit (BigDecimal, p=12, s=2)
 *       05  ACCT-ADDR-ZIP                     PIC X(10).       -> addrZip (String, 10 chars)
 *       05  ACCT-GROUP-ID                     PIC X(10).       -> groupId (String, 10 chars)
 *       05  FILLER                            PIC X(178).      -> not mapped
 *   Total: 11+1+12+12+12+10+10+10+12+12+10+10+178 = 300 bytes
 *
 * Migration Notes:
 * - All 5 monetary COMP-3 fields use BigDecimal (never float/double) per AAP mandate
 * - COBOL PIC S9(10)V99 maps to BigDecimal with precision=12, scale=2
 * - ACCT-ID stored as String to preserve leading zeros (PIC 9(11) is numeric display)
 * - COBOL typo "ACCT-EXPIRAION-DATE" corrected to "expirationDate" in Java
 * - @Version for optimistic locking replaces CICS READ UPDATE -> REWRITE pattern
 * - Date fields stored as String (YYYY-MM-DD format) matching COBOL PIC X(10) storage
 *
 * Ver: CardDemo_v1.0 — Migrated from COBOL to Java 25 + Spring Boot 3.5.x
 */
package com.cardemo.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.math.BigDecimal;
import java.util.Objects;

/**
 * JPA entity representing an account master record.
 *
 * <p>Maps the VSAM ACCTDATA KSDS dataset (300-byte records) defined
 * in COBOL copybook CVACT01Y.cpy. This is the most foundational entity
 * in the CardDemo system, referenced by cards, transactions, cross-references,
 * category balances, and virtually every online and batch service.</p>
 *
 * <p>All five monetary fields ({@code currBal}, {@code creditLimit},
 * {@code cashCreditLimit}, {@code currCycCredit}, {@code currCycDebit})
 * use {@link BigDecimal} with precision 12 and scale 2, matching the
 * COBOL PIC S9(10)V99 packed decimal specification exactly.</p>
 */
@Entity
@Table(name = "account")
public class Account {

    // =========================================================================
    // Primary Key — ACCT-ID PIC 9(11)
    // =========================================================================

    /**
     * Account identifier (11-character numeric string).
     * Maps to COBOL ACCT-ID PIC 9(11). Stored as String to preserve
     * leading zeros (e.g., "00000000001").
     */
    @Id
    @Column(name = "acct_id", length = 11, nullable = false)
    private String acctId;

    // =========================================================================
    // Status Field — ACCT-ACTIVE-STATUS PIC X(01)
    // =========================================================================

    /**
     * Account active status flag (single character).
     * Maps to COBOL ACCT-ACTIVE-STATUS PIC X(01).
     * Typical values: 'Y' = active, 'N' = inactive.
     */
    @Column(name = "active_status", length = 1)
    private String activeStatus;

    // =========================================================================
    // Monetary Fields — All PIC S9(10)V99 -> BigDecimal(precision=12, scale=2)
    // CRITICAL: These 5 fields MUST use BigDecimal, NEVER float/double.
    // COBOL S9(10)V99 = signed, 10 integer digits, 2 decimal places.
    // =========================================================================

    /**
     * Current account balance.
     * Maps to COBOL ACCT-CURR-BAL PIC S9(10)V99.
     * BigDecimal with precision 12 (10 integer + 2 decimal), scale 2.
     * All calculations must use RoundingMode.HALF_UP matching COBOL default.
     */
    @Column(name = "curr_bal", precision = 12, scale = 2)
    private BigDecimal currBal;

    /**
     * Credit limit for this account.
     * Maps to COBOL ACCT-CREDIT-LIMIT PIC S9(10)V99.
     * BigDecimal with precision 12, scale 2.
     */
    @Column(name = "credit_limit", precision = 12, scale = 2)
    private BigDecimal creditLimit;

    /**
     * Cash credit limit for this account.
     * Maps to COBOL ACCT-CASH-CREDIT-LIMIT PIC S9(10)V99.
     * BigDecimal with precision 12, scale 2.
     */
    @Column(name = "cash_credit_limit", precision = 12, scale = 2)
    private BigDecimal cashCreditLimit;

    // =========================================================================
    // Date Fields — All PIC X(10) -> String (YYYY-MM-DD format)
    // =========================================================================

    /**
     * Account open date in YYYY-MM-DD format.
     * Maps to COBOL ACCT-OPEN-DATE PIC X(10).
     */
    @Column(name = "open_date", length = 10)
    private String openDate;

    /**
     * Account expiration date in YYYY-MM-DD format.
     * Maps to COBOL ACCT-EXPIRAION-DATE PIC X(10).
     * Note: COBOL source has typo "EXPIRAION"; Java uses corrected spelling.
     */
    @Column(name = "expiration_date", length = 10)
    private String expirationDate;

    /**
     * Card reissue date in YYYY-MM-DD format.
     * Maps to COBOL ACCT-REISSUE-DATE PIC X(10).
     */
    @Column(name = "reissue_date", length = 10)
    private String reissueDate;

    // =========================================================================
    // Cycle Monetary Fields — PIC S9(10)V99 -> BigDecimal(precision=12, scale=2)
    // =========================================================================

    /**
     * Current billing cycle total credits.
     * Maps to COBOL ACCT-CURR-CYC-CREDIT PIC S9(10)V99.
     * BigDecimal with precision 12, scale 2.
     */
    @Column(name = "curr_cyc_credit", precision = 12, scale = 2)
    private BigDecimal currCycCredit;

    /**
     * Current billing cycle total debits.
     * Maps to COBOL ACCT-CURR-CYC-DEBIT PIC S9(10)V99.
     * BigDecimal with precision 12, scale 2.
     */
    @Column(name = "curr_cyc_debit", precision = 12, scale = 2)
    private BigDecimal currCycDebit;

    // =========================================================================
    // Reference Fields — PIC X(10) -> String
    // =========================================================================

    /**
     * Account address ZIP code.
     * Maps to COBOL ACCT-ADDR-ZIP PIC X(10).
     */
    @Column(name = "addr_zip", length = 10)
    private String addrZip;

    /**
     * Discount group identifier linking to the DiscountGroup entity.
     * Maps to COBOL ACCT-GROUP-ID PIC X(10).
     */
    @Column(name = "group_id", length = 10)
    private String groupId;

    // =========================================================================
    // Optimistic Locking — Replaces CICS READ UPDATE -> REWRITE pattern
    // =========================================================================

    /**
     * JPA version field for optimistic locking.
     * Translates the CICS READ UPDATE followed by REWRITE pattern
     * into JPA {@code @Version}-based concurrency control.
     */
    @Version
    @Column(name = "version")
    private Long version;

    // =========================================================================
    // Constructors
    // =========================================================================

    /**
     * Default no-argument constructor required by JPA.
     * Protected access prevents direct instantiation outside of JPA/subclasses.
     */
    protected Account() {
        // Required by JPA specification
    }

    /**
     * Parameterized constructor for creating Account instances with all business fields.
     * The version field is managed by JPA and is not included.
     *
     * @param acctId          account identifier (11 chars, PK)
     * @param activeStatus    active status flag ('Y'/'N')
     * @param currBal         current balance (BigDecimal, precision=12, scale=2)
     * @param creditLimit     credit limit (BigDecimal, precision=12, scale=2)
     * @param cashCreditLimit cash credit limit (BigDecimal, precision=12, scale=2)
     * @param openDate        account open date (YYYY-MM-DD)
     * @param expirationDate  account expiration date (YYYY-MM-DD)
     * @param reissueDate     card reissue date (YYYY-MM-DD)
     * @param currCycCredit   current cycle credits (BigDecimal, precision=12, scale=2)
     * @param currCycDebit    current cycle debits (BigDecimal, precision=12, scale=2)
     * @param addrZip         address ZIP code
     * @param groupId         discount group identifier
     */
    public Account(String acctId, String activeStatus, BigDecimal currBal,
                   BigDecimal creditLimit, BigDecimal cashCreditLimit,
                   String openDate, String expirationDate, String reissueDate,
                   BigDecimal currCycCredit, BigDecimal currCycDebit,
                   String addrZip, String groupId) {
        this.acctId = acctId;
        this.activeStatus = activeStatus;
        this.currBal = currBal;
        this.creditLimit = creditLimit;
        this.cashCreditLimit = cashCreditLimit;
        this.openDate = openDate;
        this.expirationDate = expirationDate;
        this.reissueDate = reissueDate;
        this.currCycCredit = currCycCredit;
        this.currCycDebit = currCycDebit;
        this.addrZip = addrZip;
        this.groupId = groupId;
    }

    // =========================================================================
    // Getters and Setters
    // =========================================================================

    /**
     * Returns the account identifier.
     * @return 11-character account ID string
     */
    public String getAcctId() {
        return acctId;
    }

    /**
     * Sets the account identifier.
     * @param acctId 11-character account ID string
     */
    public void setAcctId(String acctId) {
        this.acctId = acctId;
    }

    /**
     * Returns the active status flag.
     * @return single-character status ('Y' = active, 'N' = inactive)
     */
    public String getActiveStatus() {
        return activeStatus;
    }

    /**
     * Sets the active status flag.
     * @param activeStatus single-character status ('Y'/'N')
     */
    public void setActiveStatus(String activeStatus) {
        this.activeStatus = activeStatus;
    }

    /**
     * Returns the current account balance.
     * @return current balance as BigDecimal (precision=12, scale=2)
     */
    public BigDecimal getCurrBal() {
        return currBal;
    }

    /**
     * Sets the current account balance.
     * @param currBal current balance as BigDecimal (precision=12, scale=2)
     */
    public void setCurrBal(BigDecimal currBal) {
        this.currBal = currBal;
    }

    /**
     * Returns the credit limit.
     * @return credit limit as BigDecimal (precision=12, scale=2)
     */
    public BigDecimal getCreditLimit() {
        return creditLimit;
    }

    /**
     * Sets the credit limit.
     * @param creditLimit credit limit as BigDecimal (precision=12, scale=2)
     */
    public void setCreditLimit(BigDecimal creditLimit) {
        this.creditLimit = creditLimit;
    }

    /**
     * Returns the cash credit limit.
     * @return cash credit limit as BigDecimal (precision=12, scale=2)
     */
    public BigDecimal getCashCreditLimit() {
        return cashCreditLimit;
    }

    /**
     * Sets the cash credit limit.
     * @param cashCreditLimit cash credit limit as BigDecimal (precision=12, scale=2)
     */
    public void setCashCreditLimit(BigDecimal cashCreditLimit) {
        this.cashCreditLimit = cashCreditLimit;
    }

    /**
     * Returns the account open date.
     * @return date string in YYYY-MM-DD format
     */
    public String getOpenDate() {
        return openDate;
    }

    /**
     * Sets the account open date.
     * @param openDate date string in YYYY-MM-DD format
     */
    public void setOpenDate(String openDate) {
        this.openDate = openDate;
    }

    /**
     * Returns the account expiration date.
     * @return date string in YYYY-MM-DD format
     */
    public String getExpirationDate() {
        return expirationDate;
    }

    /**
     * Sets the account expiration date.
     * @param expirationDate date string in YYYY-MM-DD format
     */
    public void setExpirationDate(String expirationDate) {
        this.expirationDate = expirationDate;
    }

    /**
     * Returns the card reissue date.
     * @return date string in YYYY-MM-DD format
     */
    public String getReissueDate() {
        return reissueDate;
    }

    /**
     * Sets the card reissue date.
     * @param reissueDate date string in YYYY-MM-DD format
     */
    public void setReissueDate(String reissueDate) {
        this.reissueDate = reissueDate;
    }

    /**
     * Returns the current billing cycle total credits.
     * @return cycle credits as BigDecimal (precision=12, scale=2)
     */
    public BigDecimal getCurrCycCredit() {
        return currCycCredit;
    }

    /**
     * Sets the current billing cycle total credits.
     * @param currCycCredit cycle credits as BigDecimal (precision=12, scale=2)
     */
    public void setCurrCycCredit(BigDecimal currCycCredit) {
        this.currCycCredit = currCycCredit;
    }

    /**
     * Returns the current billing cycle total debits.
     * @return cycle debits as BigDecimal (precision=12, scale=2)
     */
    public BigDecimal getCurrCycDebit() {
        return currCycDebit;
    }

    /**
     * Sets the current billing cycle total debits.
     * @param currCycDebit cycle debits as BigDecimal (precision=12, scale=2)
     */
    public void setCurrCycDebit(BigDecimal currCycDebit) {
        this.currCycDebit = currCycDebit;
    }

    /**
     * Returns the address ZIP code.
     * @return ZIP code string (up to 10 characters)
     */
    public String getAddrZip() {
        return addrZip;
    }

    /**
     * Sets the address ZIP code.
     * @param addrZip ZIP code string (up to 10 characters)
     */
    public void setAddrZip(String addrZip) {
        this.addrZip = addrZip;
    }

    /**
     * Returns the discount group identifier.
     * @return group ID string (up to 10 characters)
     */
    public String getGroupId() {
        return groupId;
    }

    /**
     * Sets the discount group identifier.
     * @param groupId group ID string (up to 10 characters)
     */
    public void setGroupId(String groupId) {
        this.groupId = groupId;
    }

    /**
     * Returns the JPA version for optimistic locking.
     * @return version number managed by JPA
     */
    public Long getVersion() {
        return version;
    }

    /**
     * Sets the JPA version for optimistic locking.
     * Typically managed by JPA; manual setting should be avoided.
     * @param version version number
     */
    public void setVersion(Long version) {
        this.version = version;
    }

    // =========================================================================
    // equals() and hashCode() — Based on acctId (VSAM KSDS primary key)
    // =========================================================================

    /**
     * Compares this Account with another object for equality based on the
     * primary key ({@code acctId}). Follows JPA entity best practices where
     * equality is determined by the natural/business key rather than object identity.
     *
     * @param o the object to compare with
     * @return true if both objects represent the same account (same acctId)
     */
    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }
        Account account = (Account) o;
        return Objects.equals(acctId, account.acctId);
    }

    /**
     * Returns a hash code based on the primary key ({@code acctId}).
     * Consistent with {@link #equals(Object)} — two Account objects
     * with the same acctId will have the same hash code.
     *
     * @return hash code derived from acctId
     */
    @Override
    public int hashCode() {
        return Objects.hash(acctId);
    }

    // =========================================================================
    // toString() — All fields (no PII in account record)
    // =========================================================================

    /**
     * Returns a string representation of this Account entity including all fields.
     * The account record contains no PII fields, so all values are safe to include.
     *
     * @return formatted string with all account fields
     */
    @Override
    public String toString() {
        return "Account{"
                + "acctId='" + acctId + '\''
                + ", activeStatus='" + activeStatus + '\''
                + ", currBal=" + currBal
                + ", creditLimit=" + creditLimit
                + ", cashCreditLimit=" + cashCreditLimit
                + ", openDate='" + openDate + '\''
                + ", expirationDate='" + expirationDate + '\''
                + ", reissueDate='" + reissueDate + '\''
                + ", currCycCredit=" + currCycCredit
                + ", currCycDebit=" + currCycDebit
                + ", addrZip='" + addrZip + '\''
                + ", groupId='" + groupId + '\''
                + ", version=" + version
                + '}';
    }
}
