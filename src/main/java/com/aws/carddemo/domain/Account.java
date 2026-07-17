/*
 * Copyright Amazon.com, Inc. or its affiliates.
 * All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License").
 * You may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND,
 * either express or implied. See the License for the specific
 * language governing permissions and limitations under the License.
 */
package com.aws.carddemo.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import java.math.BigDecimal;
import java.util.Objects;

/**
 * JPA entity for the master account record.
 *
 * <p>This entity is the direct Java re-platforming of the legacy COBOL copybook
 * {@code ACCOUNT-RECORD} (source {@code legacy/cpy/CVACT01Y.cpy}, VSAM dataset
 * {@code ACCTDATA.VSAM.KSDS}, record length 300) onto the PostgreSQL table
 * {@code account}. Field order below preserves the copybook layout exactly.</p>
 *
 * <h2>COBOL {@code CVACT01Y.cpy} &rarr; {@code account} column mapping</h2>
 * <table border="1">
 *   <caption>Copybook-to-column mapping</caption>
 *   <tr><th>COBOL field (PIC)</th><th>Java field</th><th>Column</th></tr>
 *   <tr><td>ACCT-ID PIC 9(11)</td><td>acctId</td><td>acct_id (PK, BIGINT)</td></tr>
 *   <tr><td>ACCT-ACTIVE-STATUS PIC X(01)</td><td>acctActiveStatus</td><td>acct_active_status VARCHAR(1)</td></tr>
 *   <tr><td>ACCT-CURR-BAL PIC S9(10)V99</td><td>currBal</td><td>curr_bal DECIMAL(12,2)</td></tr>
 *   <tr><td>ACCT-CREDIT-LIMIT PIC S9(10)V99</td><td>creditLimit</td><td>credit_limit DECIMAL(12,2)</td></tr>
 *   <tr><td>ACCT-CASH-CREDIT-LIMIT PIC S9(10)V99</td><td>cashCreditLimit</td><td>cash_credit_limit DECIMAL(12,2)</td></tr>
 *   <tr><td>ACCT-OPEN-DATE PIC X(10)</td><td>acctOpenDate</td><td>acct_open_date VARCHAR(10)</td></tr>
 *   <tr><td>ACCT-EXPIRAION-DATE PIC X(10)</td><td>acctExpirationDate</td><td>acct_expiration_date VARCHAR(10)</td></tr>
 *   <tr><td>ACCT-REISSUE-DATE PIC X(10)</td><td>acctReissueDate</td><td>acct_reissue_date VARCHAR(10)</td></tr>
 *   <tr><td>ACCT-CURR-CYC-CREDIT PIC S9(10)V99</td><td>currCycCredit</td><td>curr_cyc_credit DECIMAL(12,2)</td></tr>
 *   <tr><td>ACCT-CURR-CYC-DEBIT PIC S9(10)V99</td><td>currCycDebit</td><td>curr_cyc_debit DECIMAL(12,2)</td></tr>
 *   <tr><td>ACCT-ADDR-ZIP PIC X(10)</td><td>acctAddrZip</td><td>acct_addr_zip VARCHAR(10)</td></tr>
 *   <tr><td>ACCT-GROUP-ID PIC X(10)</td><td>groupId</td><td>group_id VARCHAR(10)</td></tr>
 *   <tr><td>FILLER PIC X(178)</td><td colspan="2"><em>intentionally not mapped (padding)</em></td></tr>
 * </table>
 *
 * <h2>Migration notes</h2>
 * <ul>
 *   <li><strong>Monetary fidelity:</strong> the five COBOL {@code COMP-3}
 *       packed-decimal money fields ({@code S9(10)V99}) become
 *       {@link java.math.BigDecimal} columns declared {@code DECIMAL(12,2)}
 *       (10 integer digits + 2 fractional = 12 total digits, scale 2). Floating
 *       point ({@code double}/{@code float}) is never used for monetary values.</li>
 *   <li><strong>Dates as text:</strong> the three {@code PIC X(10)} date fields
 *       were stored as fixed-width character data on the mainframe and are
 *       preserved as {@link String} ({@code VARCHAR(10)}), not {@code LocalDate},
 *       to keep the external record contract byte-for-byte compatible.</li>
 *   <li><strong>Optimistic locking:</strong> the {@link Version} column reproduces
 *       the integrity of the COBOL online READ-UPDATE-REWRITE cycle within a JPA
 *       transaction boundary. This is a documented intentional improvement
 *       (see {@code docs/decision-log.md}), not a behavioral change.</li>
 *   <li><strong>{@code group_id} is a scalar column, not an association:</strong>
 *       {@code disclosure_group} has a three-part compound primary key, so
 *       {@code group_id} alone is not unique there and there is no database
 *       foreign key. The relationship is enforced in application logic exactly
 *       as the COBOL original did, so the field remains a plain {@link String}.</li>
 * </ul>
 *
 * <p>The database schema is owned by the Flyway migration
 * {@code V1__schema.sql}; Hibernate runs in {@code validate} mode only. The
 * column names, types, lengths, and decimal precision/scale declared here must
 * match that DDL exactly.</p>
 */
@Entity
@Table(name = "account")
public class Account {

    /**
     * Primary key. Maps {@code ACCT-ID PIC 9(11)} to a {@code BIGINT} column.
     * The account identifier is a natural key supplied by the legacy data (not
     * database-generated), so no {@code @GeneratedValue} strategy is applied.
     */
    @Id
    @Column(name = "acct_id", nullable = false)
    private Long acctId;

    /**
     * Account active status flag. Maps {@code ACCT-ACTIVE-STATUS PIC X(01)}
     * (typically {@code "Y"}/{@code "N"}) to {@code VARCHAR(1)}.
     */
    @Column(name = "acct_active_status", length = 1)
    private String acctActiveStatus;

    /** Current balance. Maps {@code ACCT-CURR-BAL PIC S9(10)V99} to {@code DECIMAL(12,2)}. */
    @Column(name = "curr_bal", precision = 12, scale = 2)
    private BigDecimal currBal;

    /** Credit limit. Maps {@code ACCT-CREDIT-LIMIT PIC S9(10)V99} to {@code DECIMAL(12,2)}. */
    @Column(name = "credit_limit", precision = 12, scale = 2)
    private BigDecimal creditLimit;

    /** Cash credit limit. Maps {@code ACCT-CASH-CREDIT-LIMIT PIC S9(10)V99} to {@code DECIMAL(12,2)}. */
    @Column(name = "cash_credit_limit", precision = 12, scale = 2)
    private BigDecimal cashCreditLimit;

    /** Account open date (fixed-width text). Maps {@code ACCT-OPEN-DATE PIC X(10)} to {@code VARCHAR(10)}. */
    @Column(name = "acct_open_date", length = 10)
    private String acctOpenDate;

    /**
     * Account expiration date (fixed-width text). Maps
     * {@code ACCT-EXPIRAION-DATE PIC X(10)} to {@code VARCHAR(10)}. The legacy
     * copybook misspells the field name as "EXPIRAION"; the corrected spelling
     * is used for the Java field and the {@code acct_expiration_date} column.
     */
    @Column(name = "acct_expiration_date", length = 10)
    private String acctExpirationDate;

    /** Account reissue date (fixed-width text). Maps {@code ACCT-REISSUE-DATE PIC X(10)} to {@code VARCHAR(10)}. */
    @Column(name = "acct_reissue_date", length = 10)
    private String acctReissueDate;

    /** Current-cycle credit total. Maps {@code ACCT-CURR-CYC-CREDIT PIC S9(10)V99} to {@code DECIMAL(12,2)}. */
    @Column(name = "curr_cyc_credit", precision = 12, scale = 2)
    private BigDecimal currCycCredit;

    /** Current-cycle debit total. Maps {@code ACCT-CURR-CYC-DEBIT PIC S9(10)V99} to {@code DECIMAL(12,2)}. */
    @Column(name = "curr_cyc_debit", precision = 12, scale = 2)
    private BigDecimal currCycDebit;

    /** Account address ZIP code. Maps {@code ACCT-ADDR-ZIP PIC X(10)} to {@code VARCHAR(10)}. */
    @Column(name = "acct_addr_zip", length = 10)
    private String acctAddrZip;

    /**
     * Disclosure-group identifier (scalar). Maps {@code ACCT-GROUP-ID PIC X(10)}
     * to {@code VARCHAR(10)}. Deliberately a plain string, not a JPA association
     * (see class documentation).
     */
    @Column(name = "group_id", length = 10)
    private String groupId;

    /**
     * Optimistic-locking version counter. Managed by the JPA provider to
     * reproduce the COBOL READ-UPDATE-REWRITE integrity guarantee.
     */
    @Version
    @Column(name = "version", nullable = false)
    private Long version;

    /**
     * Protected no-argument constructor required by the JPA specification.
     * Application code should prefer the all-arguments convenience constructor
     * or the setters.
     */
    protected Account() {
        // Required by JPA; intentionally empty.
    }

    /**
     * Convenience constructor that populates every persistent business field in
     * copybook order. The optimistic-lock {@link #version} is intentionally
     * excluded because it is managed exclusively by the JPA provider.
     *
     * <p>Fields are assigned directly (no setter invocation) so that no
     * overridable method observes a partially-constructed instance.</p>
     *
     * @param acctId             account identifier (primary key)
     * @param acctActiveStatus   active status flag
     * @param currBal            current balance
     * @param creditLimit        credit limit
     * @param cashCreditLimit    cash credit limit
     * @param acctOpenDate       account open date (text)
     * @param acctExpirationDate account expiration date (text)
     * @param acctReissueDate    account reissue date (text)
     * @param currCycCredit      current-cycle credit total
     * @param currCycDebit       current-cycle debit total
     * @param acctAddrZip        account address ZIP code
     * @param groupId            disclosure-group identifier
     */
    public Account(Long acctId,
                   String acctActiveStatus,
                   BigDecimal currBal,
                   BigDecimal creditLimit,
                   BigDecimal cashCreditLimit,
                   String acctOpenDate,
                   String acctExpirationDate,
                   String acctReissueDate,
                   BigDecimal currCycCredit,
                   BigDecimal currCycDebit,
                   String acctAddrZip,
                   String groupId) {
        this.acctId = acctId;
        this.acctActiveStatus = acctActiveStatus;
        this.currBal = currBal;
        this.creditLimit = creditLimit;
        this.cashCreditLimit = cashCreditLimit;
        this.acctOpenDate = acctOpenDate;
        this.acctExpirationDate = acctExpirationDate;
        this.acctReissueDate = acctReissueDate;
        this.currCycCredit = currCycCredit;
        this.currCycDebit = currCycDebit;
        this.acctAddrZip = acctAddrZip;
        this.groupId = groupId;
    }

    /**
     * Returns the account identifier (primary key).
     *
     * @return the account identifier
     */
    public Long getAcctId() {
        return acctId;
    }

    /**
     * Sets the account identifier (primary key).
     *
     * @param acctId the account identifier
     */
    public void setAcctId(Long acctId) {
        this.acctId = acctId;
    }

    /**
     * Returns the account active status flag.
     *
     * @return the active status flag
     */
    public String getAcctActiveStatus() {
        return acctActiveStatus;
    }

    /**
     * Sets the account active status flag.
     *
     * @param acctActiveStatus the active status flag
     */
    public void setAcctActiveStatus(String acctActiveStatus) {
        this.acctActiveStatus = acctActiveStatus;
    }

    /**
     * Returns the current balance.
     *
     * @return the current balance
     */
    public BigDecimal getCurrBal() {
        return currBal;
    }

    /**
     * Sets the current balance.
     *
     * @param currBal the current balance
     */
    public void setCurrBal(BigDecimal currBal) {
        this.currBal = currBal;
    }

    /**
     * Returns the credit limit.
     *
     * @return the credit limit
     */
    public BigDecimal getCreditLimit() {
        return creditLimit;
    }

    /**
     * Sets the credit limit.
     *
     * @param creditLimit the credit limit
     */
    public void setCreditLimit(BigDecimal creditLimit) {
        this.creditLimit = creditLimit;
    }

    /**
     * Returns the cash credit limit.
     *
     * @return the cash credit limit
     */
    public BigDecimal getCashCreditLimit() {
        return cashCreditLimit;
    }

    /**
     * Sets the cash credit limit.
     *
     * @param cashCreditLimit the cash credit limit
     */
    public void setCashCreditLimit(BigDecimal cashCreditLimit) {
        this.cashCreditLimit = cashCreditLimit;
    }

    /**
     * Returns the account open date (text).
     *
     * @return the account open date
     */
    public String getAcctOpenDate() {
        return acctOpenDate;
    }

    /**
     * Sets the account open date (text).
     *
     * @param acctOpenDate the account open date
     */
    public void setAcctOpenDate(String acctOpenDate) {
        this.acctOpenDate = acctOpenDate;
    }

    /**
     * Returns the account expiration date (text).
     *
     * @return the account expiration date
     */
    public String getAcctExpirationDate() {
        return acctExpirationDate;
    }

    /**
     * Sets the account expiration date (text).
     *
     * @param acctExpirationDate the account expiration date
     */
    public void setAcctExpirationDate(String acctExpirationDate) {
        this.acctExpirationDate = acctExpirationDate;
    }

    /**
     * Returns the account reissue date (text).
     *
     * @return the account reissue date
     */
    public String getAcctReissueDate() {
        return acctReissueDate;
    }

    /**
     * Sets the account reissue date (text).
     *
     * @param acctReissueDate the account reissue date
     */
    public void setAcctReissueDate(String acctReissueDate) {
        this.acctReissueDate = acctReissueDate;
    }

    /**
     * Returns the current-cycle credit total.
     *
     * @return the current-cycle credit total
     */
    public BigDecimal getCurrCycCredit() {
        return currCycCredit;
    }

    /**
     * Sets the current-cycle credit total.
     *
     * @param currCycCredit the current-cycle credit total
     */
    public void setCurrCycCredit(BigDecimal currCycCredit) {
        this.currCycCredit = currCycCredit;
    }

    /**
     * Returns the current-cycle debit total.
     *
     * @return the current-cycle debit total
     */
    public BigDecimal getCurrCycDebit() {
        return currCycDebit;
    }

    /**
     * Sets the current-cycle debit total.
     *
     * @param currCycDebit the current-cycle debit total
     */
    public void setCurrCycDebit(BigDecimal currCycDebit) {
        this.currCycDebit = currCycDebit;
    }

    /**
     * Returns the account address ZIP code.
     *
     * @return the account address ZIP code
     */
    public String getAcctAddrZip() {
        return acctAddrZip;
    }

    /**
     * Sets the account address ZIP code.
     *
     * @param acctAddrZip the account address ZIP code
     */
    public void setAcctAddrZip(String acctAddrZip) {
        this.acctAddrZip = acctAddrZip;
    }

    /**
     * Returns the disclosure-group identifier.
     *
     * @return the disclosure-group identifier
     */
    public String getGroupId() {
        return groupId;
    }

    /**
     * Sets the disclosure-group identifier.
     *
     * @param groupId the disclosure-group identifier
     */
    public void setGroupId(String groupId) {
        this.groupId = groupId;
    }

    /**
     * Returns the optimistic-locking version counter.
     *
     * @return the version counter
     */
    public Long getVersion() {
        return version;
    }

    /**
     * Sets the optimistic-locking version counter. Normally managed by the JPA
     * provider; exposed to support detached-entity merge scenarios and testing.
     *
     * @param version the version counter
     */
    public void setVersion(Long version) {
        this.version = version;
    }

    /**
     * Accounts are equal when they share the same primary key ({@code acctId}).
     *
     * @param o the object to compare with
     * @return {@code true} if the objects represent the same account
     */
    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }
        Account other = (Account) o;
        return Objects.equals(acctId, other.acctId);
    }

    /**
     * Hash code derived from the primary key ({@code acctId}).
     *
     * @return the hash code
     */
    @Override
    public int hashCode() {
        return Objects.hash(acctId);
    }

    /**
     * Returns a diagnostic string representation. All fields are included; none
     * are sensitive for this entity (no SSN, government id, or CVV is present).
     *
     * @return a string representation of this account
     */
    @Override
    public String toString() {
        return "Account{"
                + "acctId=" + acctId
                + ", acctActiveStatus='" + acctActiveStatus + '\''
                + ", currBal=" + currBal
                + ", creditLimit=" + creditLimit
                + ", cashCreditLimit=" + cashCreditLimit
                + ", acctOpenDate='" + acctOpenDate + '\''
                + ", acctExpirationDate='" + acctExpirationDate + '\''
                + ", acctReissueDate='" + acctReissueDate + '\''
                + ", currCycCredit=" + currCycCredit
                + ", currCycDebit=" + currCycDebit
                + ", acctAddrZip='" + acctAddrZip + '\''
                + ", groupId='" + groupId + '\''
                + ", version=" + version
                + '}';
    }
}
