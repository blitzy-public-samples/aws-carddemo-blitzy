package com.aws.carddemo.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Objects;

/**
 * JPA entity mapping the AWS CardDemo account master record.
 *
 * <p>Migrated one-for-one from the legacy COBOL copybook {@code ACCOUNT-RECORD}
 * (record length 300 bytes) defined at {@code legacy/cpy/CVACT01Y.cpy}, which backed
 * the VSAM {@code ACCTDAT} key-sequenced data set (KSDS). The single primary key
 * ({@code ACCT-ID}) and the exact field layout are preserved to guarantee behavioral
 * parity with the mainframe application.</p>
 *
 * <p>All five monetary fields are declared as {@link java.math.BigDecimal} with
 * {@code precision = 12, scale = 2} (COBOL {@code S9(10)V99}). Floating-point types are
 * deliberately never used for money so that the COBOL packed-decimal / fixed-scale
 * arithmetic semantics are reproduced exactly and no precision is lost.</p>
 *
 * <p>The three date fields ({@code ACCT-OPEN-DATE}, {@code ACCT-EXPIRAION-DATE} and
 * {@code ACCT-REISSUE-DATE}) were {@code PIC X(10)} character strings in the copybook and
 * are modelled here as {@link java.time.LocalDate}; Hibernate maps {@code java.time.LocalDate}
 * to SQL {@code DATE} without requiring {@code @Temporal}. Blank legacy date values decode to
 * {@code null} in the fixed-width mapper rather than in this entity. The trailing
 * {@code FILLER PIC X(178)} bytes carry no business data and are not persisted, so this entity
 * exposes exactly the 12 meaningful record fields.</p>
 *
 * @implNote The Java field {@code expiraionDate} and its column {@code acct_expiraion_date}
 *           intentionally preserve the misspelling ("EXPIRAION") of the original COBOL field
 *           {@code ACCT-EXPIRAION-DATE}. The misspelling is retained verbatim for complete
 *           source-to-target traceability and is deliberately not corrected.
 */
@Entity
@Table(name = "account")
public class Account {

    /** {@code ACCT-ID PIC 9(11)} — natural primary key of the VSAM ACCTDAT KSDS. */
    @Id
    @Column(name = "acct_id", precision = 11)
    private Long acctId;

    /** {@code ACCT-ACTIVE-STATUS PIC X(01)} — single-character active flag. */
    @Column(name = "acct_active_status", length = 1)
    private String activeStatus;

    /** {@code ACCT-CURR-BAL PIC S9(10)V99} — current account balance. */
    @Column(name = "acct_curr_bal", precision = 12, scale = 2)
    private BigDecimal currBal;

    /** {@code ACCT-CREDIT-LIMIT PIC S9(10)V99} — total credit limit. */
    @Column(name = "acct_credit_limit", precision = 12, scale = 2)
    private BigDecimal creditLimit;

    /** {@code ACCT-CASH-CREDIT-LIMIT PIC S9(10)V99} — cash-advance credit limit. */
    @Column(name = "acct_cash_credit_limit", precision = 12, scale = 2)
    private BigDecimal cashCreditLimit;

    /** {@code ACCT-OPEN-DATE PIC X(10)} — account open date. */
    @Column(name = "acct_open_date")
    private LocalDate openDate;

    /**
     * {@code ACCT-EXPIRAION-DATE PIC X(10)} — account expiration date.
     *
     * @implNote Misspelling preserved from the COBOL source; see the class-level {@code @implNote}.
     */
    @Column(name = "acct_expiraion_date")
    private LocalDate expiraionDate;

    /** {@code ACCT-REISSUE-DATE PIC X(10)} — account reissue date. */
    @Column(name = "acct_reissue_date")
    private LocalDate reissueDate;

    /** {@code ACCT-CURR-CYC-CREDIT PIC S9(10)V99} — current cycle credit total. */
    @Column(name = "acct_curr_cyc_credit", precision = 12, scale = 2)
    private BigDecimal currCycCredit;

    /** {@code ACCT-CURR-CYC-DEBIT PIC S9(10)V99} — current cycle debit total. */
    @Column(name = "acct_curr_cyc_debit", precision = 12, scale = 2)
    private BigDecimal currCycDebit;

    /** {@code ACCT-ADDR-ZIP PIC X(10)} — account holder postal ZIP code. */
    @Column(name = "acct_addr_zip", length = 10)
    private String addrZip;

    /**
     * {@code ACCT-GROUP-ID PIC X(10)} — disclosure-group identifier.
     *
     * <p>This is the linkage value matched against {@code DisclosureGroup.acctGroupId} for
     * interest-rate lookup. It is intentionally kept as a plain {@code String} scalar and is
     * deliberately NOT modelled as a JPA association, mirroring the VSAM design in which no
     * enforced referential relationship existed.</p>
     */
    @Column(name = "acct_group_id", length = 10)
    private String groupId;

    /**
     * Creates an empty {@code Account}. Required by the JPA specification so that the
     * persistence provider can instantiate the entity via reflection.
     */
    public Account() {
        // No-argument constructor required by JPA; intentionally performs no work.
    }

    // ------------------------------------------------------------------
    // Getters and setters — JavaBean property access for the JPA provider.
    // ------------------------------------------------------------------

    public Long getAcctId() {
        return acctId;
    }

    public void setAcctId(Long acctId) {
        this.acctId = acctId;
    }

    public String getActiveStatus() {
        return activeStatus;
    }

    public void setActiveStatus(String activeStatus) {
        this.activeStatus = activeStatus;
    }

    public BigDecimal getCurrBal() {
        return currBal;
    }

    public void setCurrBal(BigDecimal currBal) {
        this.currBal = currBal;
    }

    public BigDecimal getCreditLimit() {
        return creditLimit;
    }

    public void setCreditLimit(BigDecimal creditLimit) {
        this.creditLimit = creditLimit;
    }

    public BigDecimal getCashCreditLimit() {
        return cashCreditLimit;
    }

    public void setCashCreditLimit(BigDecimal cashCreditLimit) {
        this.cashCreditLimit = cashCreditLimit;
    }

    public LocalDate getOpenDate() {
        return openDate;
    }

    public void setOpenDate(LocalDate openDate) {
        this.openDate = openDate;
    }

    public LocalDate getExpiraionDate() {
        return expiraionDate;
    }

    public void setExpiraionDate(LocalDate expiraionDate) {
        this.expiraionDate = expiraionDate;
    }

    public LocalDate getReissueDate() {
        return reissueDate;
    }

    public void setReissueDate(LocalDate reissueDate) {
        this.reissueDate = reissueDate;
    }

    public BigDecimal getCurrCycCredit() {
        return currCycCredit;
    }

    public void setCurrCycCredit(BigDecimal currCycCredit) {
        this.currCycCredit = currCycCredit;
    }

    public BigDecimal getCurrCycDebit() {
        return currCycDebit;
    }

    public void setCurrCycDebit(BigDecimal currCycDebit) {
        this.currCycDebit = currCycDebit;
    }

    public String getAddrZip() {
        return addrZip;
    }

    public void setAddrZip(String addrZip) {
        this.addrZip = addrZip;
    }

    public String getGroupId() {
        return groupId;
    }

    public void setGroupId(String groupId) {
        this.groupId = groupId;
    }

    // ------------------------------------------------------------------
    // Identity — based on the primary key (ACCT-ID) only.
    // ------------------------------------------------------------------

    /**
     * Two {@code Account} instances are equal when they represent the same persistent
     * record, i.e. when their primary-key {@code acctId} values are equal.
     *
     * @param o the object to compare with
     * @return {@code true} if {@code o} is an {@code Account} with an equal {@code acctId}
     */
    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof Account other)) {
            return false;
        }
        return Objects.equals(acctId, other.acctId);
    }

    /**
     * Returns a hash code derived solely from the primary key {@code acctId}.
     *
     * @return the primary-key-based hash code
     */
    @Override
    public int hashCode() {
        return Objects.hash(acctId);
    }

    /**
     * Returns a diagnostic string representation of this account, intended for logging and
     * debugging. No field masking is applied for {@code Account}.
     *
     * @return a string containing the key, status, balances and dates
     */
    @Override
    public String toString() {
        return "Account{"
                + "acctId=" + acctId
                + ", activeStatus='" + activeStatus + '\''
                + ", currBal=" + currBal
                + ", creditLimit=" + creditLimit
                + ", cashCreditLimit=" + cashCreditLimit
                + ", openDate=" + openDate
                + ", expiraionDate=" + expiraionDate
                + ", reissueDate=" + reissueDate
                + ", currCycCredit=" + currCycCredit
                + ", currCycDebit=" + currCycDebit
                + ", addrZip='" + addrZip + '\''
                + ", groupId='" + groupId + '\''
                + '}';
    }
}
