package com.carddemo.common.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import java.math.BigDecimal;

/**
 * JPA entity for the CardDemo account master record.
 *
 * :purpose: Maps the legacy COBOL ``ACCOUNT-RECORD`` layout (copybook
 *     ``CVACT01Y``, fixed length 300 bytes) onto the ``accounts`` relational
 *     table, preserving the legacy field names, field widths and monetary
 *     scale so that migrated financial output remains byte-identical.
 * :output: A persistent account aggregate exposing the current balance, the
 *     credit and cash-credit limits, the current-cycle credit/debit figures,
 *     the lifecycle dates (open, expiration, reissue), the active status, the
 *     address ZIP, the account group id and a JPA-managed optimistic-locking
 *     version used to detect concurrent updates.
 */
@Entity
@Table(name = "accounts")
public class Account {

    /** ACCT-ID PIC 9(11): 11-digit natural key, stored as BIGINT. */
    @Id
    @Column(name = "acct_id", nullable = false)
    private Long acctId;

    /** ACCT-ACTIVE-STATUS PIC X(01): single-character active flag (e.g. "Y"). */
    @Column(name = "acct_active_status", length = 1, nullable = false)
    private String acctActiveStatus;

    /** ACCT-CURR-BAL PIC S9(10)V99 -> NUMERIC(12,2). */
    @Column(name = "acct_curr_bal", precision = 12, scale = 2, nullable = false)
    private BigDecimal acctCurrBal;

    /** ACCT-CREDIT-LIMIT PIC S9(10)V99 -> NUMERIC(12,2). */
    @Column(name = "acct_credit_limit", precision = 12, scale = 2, nullable = false)
    private BigDecimal acctCreditLimit;

    /** ACCT-CASH-CREDIT-LIMIT PIC S9(10)V99 -> NUMERIC(12,2). */
    @Column(name = "acct_cash_credit_limit", precision = 12, scale = 2, nullable = false)
    private BigDecimal acctCashCreditLimit;

    /** ACCT-OPEN-DATE PIC X(10): "YYYY-MM-DD" wire format kept as String. */
    @Column(name = "acct_open_date", length = 10, nullable = false)
    private String acctOpenDate;

    /**
     * ACCT-EXPIRAION-DATE PIC X(10): "YYYY-MM-DD" expiration date.
     *
     * :purpose: The field name preserves the legacy copybook misspelling
     *     ``ACCT-EXPIRAION-DATE`` (missing the second ``T``) verbatim as a
     *     frozen identifier contract; it must not be renamed to "expiration".
     */
    @Column(name = "acct_expiraion_date", length = 10)
    private String acctExpiraionDate;

    /** ACCT-REISSUE-DATE PIC X(10): "YYYY-MM-DD" wire format kept as String. */
    @Column(name = "acct_reissue_date", length = 10)
    private String acctReissueDate;

    /** ACCT-CURR-CYC-CREDIT PIC S9(10)V99 -> NUMERIC(12,2). */
    @Column(name = "acct_curr_cyc_credit", precision = 12, scale = 2, nullable = false)
    private BigDecimal acctCurrCycCredit;

    /** ACCT-CURR-CYC-DEBIT PIC S9(10)V99 -> NUMERIC(12,2). */
    @Column(name = "acct_curr_cyc_debit", precision = 12, scale = 2, nullable = false)
    private BigDecimal acctCurrCycDebit;

    /** ACCT-ADDR-ZIP PIC X(10): postal ZIP code. */
    @Column(name = "acct_addr_zip", length = 10)
    private String acctAddrZip;

    /** ACCT-GROUP-ID PIC X(10): disclosure/pricing group identifier. */
    @Column(name = "acct_group_id", length = 10)
    private String acctGroupId;

    /** Optimistic-locking version (JPA-managed) detecting concurrent account updates. */
    @Version
    @Column(name = "version")
    private Long version;

    /**
     * Creates an empty account instance.
     *
     * :purpose: Required no-argument constructor used by the JPA provider when
     *     materializing entities from the database.
     */
    public Account() {
    }

    /**
     * :returns: the 11-digit account identifier (natural key).
     */
    public Long getAcctId() {
        return acctId;
    }

    /**
     * :param acctId: the 11-digit account identifier to assign.
     */
    public void setAcctId(Long acctId) {
        this.acctId = acctId;
    }

    /**
     * :returns: the single-character active status flag.
     */
    public String getAcctActiveStatus() {
        return acctActiveStatus;
    }

    /**
     * :param acctActiveStatus: the single-character active status flag.
     */
    public void setAcctActiveStatus(String acctActiveStatus) {
        this.acctActiveStatus = acctActiveStatus;
    }

    /**
     * :returns: the current account balance.
     */
    public BigDecimal getAcctCurrBal() {
        return acctCurrBal;
    }

    /**
     * :param acctCurrBal: the current account balance.
     */
    public void setAcctCurrBal(BigDecimal acctCurrBal) {
        this.acctCurrBal = acctCurrBal;
    }

    /**
     * :returns: the account credit limit.
     */
    public BigDecimal getAcctCreditLimit() {
        return acctCreditLimit;
    }

    /**
     * :param acctCreditLimit: the account credit limit.
     */
    public void setAcctCreditLimit(BigDecimal acctCreditLimit) {
        this.acctCreditLimit = acctCreditLimit;
    }

    /**
     * :returns: the account cash-credit limit.
     */
    public BigDecimal getAcctCashCreditLimit() {
        return acctCashCreditLimit;
    }

    /**
     * :param acctCashCreditLimit: the account cash-credit limit.
     */
    public void setAcctCashCreditLimit(BigDecimal acctCashCreditLimit) {
        this.acctCashCreditLimit = acctCashCreditLimit;
    }

    /**
     * :returns: the account open date in "YYYY-MM-DD" form.
     */
    public String getAcctOpenDate() {
        return acctOpenDate;
    }

    /**
     * :param acctOpenDate: the account open date in "YYYY-MM-DD" form.
     */
    public void setAcctOpenDate(String acctOpenDate) {
        this.acctOpenDate = acctOpenDate;
    }

    /**
     * :returns: the account expiration date in "YYYY-MM-DD" form (legacy
     *     misspelled identifier preserved).
     */
    public String getAcctExpiraionDate() {
        return acctExpiraionDate;
    }

    /**
     * :param acctExpiraionDate: the account expiration date in "YYYY-MM-DD"
     *     form (legacy misspelled identifier preserved).
     */
    public void setAcctExpiraionDate(String acctExpiraionDate) {
        this.acctExpiraionDate = acctExpiraionDate;
    }

    /**
     * :returns: the account reissue date in "YYYY-MM-DD" form.
     */
    public String getAcctReissueDate() {
        return acctReissueDate;
    }

    /**
     * :param acctReissueDate: the account reissue date in "YYYY-MM-DD" form.
     */
    public void setAcctReissueDate(String acctReissueDate) {
        this.acctReissueDate = acctReissueDate;
    }

    /**
     * :returns: the current-cycle credit total.
     */
    public BigDecimal getAcctCurrCycCredit() {
        return acctCurrCycCredit;
    }

    /**
     * :param acctCurrCycCredit: the current-cycle credit total.
     */
    public void setAcctCurrCycCredit(BigDecimal acctCurrCycCredit) {
        this.acctCurrCycCredit = acctCurrCycCredit;
    }

    /**
     * :returns: the current-cycle debit total.
     */
    public BigDecimal getAcctCurrCycDebit() {
        return acctCurrCycDebit;
    }

    /**
     * :param acctCurrCycDebit: the current-cycle debit total.
     */
    public void setAcctCurrCycDebit(BigDecimal acctCurrCycDebit) {
        this.acctCurrCycDebit = acctCurrCycDebit;
    }

    /**
     * :returns: the account address ZIP code.
     */
    public String getAcctAddrZip() {
        return acctAddrZip;
    }

    /**
     * :param acctAddrZip: the account address ZIP code.
     */
    public void setAcctAddrZip(String acctAddrZip) {
        this.acctAddrZip = acctAddrZip;
    }

    /**
     * :returns: the disclosure/pricing group identifier.
     */
    public String getAcctGroupId() {
        return acctGroupId;
    }

    /**
     * :param acctGroupId: the disclosure/pricing group identifier.
     */
    public void setAcctGroupId(String acctGroupId) {
        this.acctGroupId = acctGroupId;
    }

    /**
     * :returns: the optimistic-locking version managed by the JPA provider.
     */
    public Long getVersion() {
        return version;
    }

    /**
     * :param version: the optimistic-locking version; normally managed by the
     *     JPA provider and set explicitly only in tests or detached-merge flows.
     */
    public void setVersion(Long version) {
        this.version = version;
    }

    /**
     * Compares accounts by identity.
     *
     * :param o: the object to compare with.
     * :returns: ``true`` when the other object is an {@code Account} with an
     *     equal {@code acctId}.
     */
    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof Account other)) {
            return false;
        }
        return acctId != null && acctId.equals(other.getAcctId());
    }

    /**
     * :returns: a proxy-stable hash code consistent with {@link #equals(Object)}.
     */
    @Override
    public int hashCode() {
        return Account.class.hashCode();
    }

    /**
     * :returns: a diagnostic representation of the account (no sensitive PII).
     */
    @Override
    public String toString() {
        return "Account{"
                + "acctId=" + acctId
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
                + ", version=" + version
                + '}';
    }
}
