package com.carddemo.account.entity;

import java.math.BigDecimal;

import com.carddemo.cobol.PicClause;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * One row of the {@code account} table, transformed field by field from the group
 * {@code 01 ACCOUNT-RECORD.} at {@code app/cpy/CVACT01Y.cpy:L4}.
 *
 * <p>Twelve mapped fields carry the twelve copybook fields at
 * {@code app/cpy/CVACT01Y.cpy:L5-L16}. Their widths total 122 bytes. The trailing
 * {@code FILLER PIC X(178)} at {@code app/cpy/CVACT01Y.cpy:L17} maps to no column and brings the
 * record to {@link PicClause#ACCOUNT_RECORD_LENGTH} bytes, matching
 * {@code RECORDSIZE(300 300)} at {@code app/jcl/ACCTFILE.jcl:L41}.
 * {@code card-platform/docs/traceability-matrix.md} records the dropped filler.</p>
 *
 * <p>{@link #getAccountId()} is the primary key. Its eleven digits come from
 * {@code KEYS(11 0)} at {@code app/jcl/ACCTFILE.jcl:L40}, the key of the Virtual Storage Access
 * Method (VSAM) key-sequenced data set this table replaces. Each value arrives with the record, and
 * no database sequence supplies one.</p>
 *
 * <p>Column names, numeric precision, numeric scale and text length match
 * {@code src/main/resources/db/migration/V1__schema.sql}. Flyway applies that migration, and
 * Jakarta Persistence checks this mapping against the migrated schema at start-up. Every precision,
 * scale and length below reads from {@link PicClause}, which publishes one number per copybook
 * field. The schema name arrives from configuration, and {@link Table} names the table alone.</p>
 *
 * <p>The record carries two separate notions of balance with no documented relationship between
 * them. {@link #getCurrentBalance()} at {@code app/cpy/CVACT01Y.cpy:L7} holds the posted balance,
 * and the two billing-cycle accumulators at {@code app/cpy/CVACT01Y.cpy:L13-L14} drive the
 * credit-limit decision. {@code card-platform/docs/business-rule-flags.md} carries that finding and
 * the other flagged account rules.</p>
 *
 * <p>This class holds no arithmetic, no format check and no status check. It declares no
 * association, no version column and no generated value.
 * {@code card-platform/docs/data-model.md} draws this table with the other seven tables of the
 * account schema and the lookup path between them.
 * {@code card-platform/docs/decision-log.md} records the column type and field naming
 * decisions.</p>
 */
@Entity
@Table(name = "account")
public class AccountEntity {

    /**
     * Primary key. {@code ACCT-ID PIC 9(11)} at {@code app/cpy/CVACT01Y.cpy:L5}. Eleven digits,
     * scale 0. Several source programs compare an account identifier as text, and the column keeps
     * the digits of the source key.
     */
    @Id
    @Column(name = "account_id", nullable = false, precision = PicClause.ACCT_ID_WIDTH)
    private BigDecimal accountId;

    /**
     * Status flag. {@code ACCT-ACTIVE-STATUS PIC X(01)} at {@code app/cpy/CVACT01Y.cpy:L6}. No
     * program reads this field before it posts a transaction. A closed account still posts. All 50
     * records of {@code app/data/ASCII/acctdata.txt} hold {@code Y}.
     *
     * <p>The column is {@code CHAR(1)}, the one fixed-width text column of this table. PostgreSQL
     * reports that type as {@code bpchar} through Java Database Connectivity (JDBC) metadata, and
     * {@link Column#columnDefinition()} names it verbatim. The start-up check compares the mapped
     * type with the reported type and accepts a match on that name.</p>
     */
    @Column(name = "active_status", nullable = false,
            length = PicClause.ACCT_ACTIVE_STATUS_WIDTH,
            columnDefinition = "bpchar(" + PicClause.ACCT_ACTIVE_STATUS_WIDTH + ")")
    private String activeStatus;

    /**
     * Posted balance. {@code ACCT-CURR-BAL PIC S9(10)V99} at {@code app/cpy/CVACT01Y.cpy:L7}.
     * Twelve total digits, scale 2. {@code app/cbl/CBTRN02C.cbl:L547} adds a posted transaction
     * amount to this field, and {@code app/cbl/CBACT04C.cbl:L352} adds an accrued interest total.
     * The credit-limit decision reads neither this field nor its value.
     */
    @Column(name = "current_balance", nullable = false,
            precision = PicClause.ACCT_CURR_BAL_PRECISION, scale = PicClause.ACCT_CURR_BAL_SCALE)
    private BigDecimal currentBalance;

    /**
     * Credit limit. {@code ACCT-CREDIT-LIMIT PIC S9(10)V99} at
     * {@code app/cpy/CVACT01Y.cpy:L8}. Twelve total digits, scale 2.
     * {@code app/cbl/CBTRN02C.cbl:L407} compares this field with the working balance the two
     * billing-cycle accumulators produce. A failed comparison assigns reject reason 102,
     * {@code OVERLIMIT TRANSACTION}, at {@code app/cbl/CBTRN02C.cbl:L410-L412}.
     */
    @Column(name = "credit_limit", nullable = false,
            precision = PicClause.ACCT_CREDIT_LIMIT_PRECISION,
            scale = PicClause.ACCT_CREDIT_LIMIT_SCALE)
    private BigDecimal creditLimit;

    /**
     * Cash credit limit. {@code ACCT-CASH-CREDIT-LIMIT PIC S9(10)V99} at
     * {@code app/cpy/CVACT01Y.cpy:L9}. Twelve total digits, scale 2. No authorization rule reads
     * this field. {@code app/cbl/COACTUPC.cbl:L4121} compares it before a rewrite.
     */
    @Column(name = "cash_credit_limit", nullable = false,
            precision = PicClause.ACCT_CASH_CREDIT_LIMIT_PRECISION,
            scale = PicClause.ACCT_CASH_CREDIT_LIMIT_SCALE)
    private BigDecimal cashCreditLimit;

    /**
     * Open date as ten characters. {@code ACCT-OPEN-DATE PIC X(10)} at
     * {@code app/cpy/CVACT01Y.cpy:L10}. {@code app/cbl/COACTUPC.cbl:L4127-L4129} slices the field
     * as {@code (1:4)}, {@code (6:2)} and {@code (9:2)}. Row 1 of
     * {@code app/data/ASCII/acctdata.txt} carries {@code 2014-11-20} at columns 49 through 58, with
     * a separator at relative position 5 and at relative position 8.
     */
    @Column(name = "open_date", nullable = false, length = PicClause.ACCT_OPEN_DATE_WIDTH)
    private String openDate;

    /**
     * Expiration date as ten characters. The copybook spells the field
     * {@code ACCT-EXPIRAION-DATE PIC X(10)} at {@code app/cpy/CVACT01Y.cpy:L11}. The column type is
     * {@link PicClause#ACCT_EXPIRATION_DATE_COLUMN_TYPE}.
     *
     * <p>{@code app/cbl/CBTRN02C.cbl:L414} compares the field with the first ten characters of a
     * 26-character transaction origin timestamp, {@code DALYTRAN-ORIG-TS (1:10)}, as text. A failed
     * comparison assigns reject reason 103,
     * {@code TRANSACTION RECEIVED AFTER ACCT EXPIRATION}, at
     * {@code app/cbl/CBTRN02C.cbl:L417-L419}. {@code app/cbl/COACTUPC.cbl:L4131-L4133} slices the
     * field as {@code (1:4)}, {@code (6:2)} and {@code (9:2)}.</p>
     */
    @Column(name = "expiration_date", nullable = false,
            length = PicClause.ACCT_EXPIRATION_DATE_WIDTH)
    private String expirationDate;

    /**
     * Reissue date as ten characters. {@code ACCT-REISSUE-DATE PIC X(10)} at
     * {@code app/cpy/CVACT01Y.cpy:L12}. {@code app/cbl/COACTUPC.cbl:L4135-L4137} slices the field
     * as {@code (1:4)}, {@code (6:2)} and {@code (9:2)}.
     */
    @Column(name = "reissue_date", nullable = false, length = PicClause.ACCT_REISSUE_DATE_WIDTH)
    private String reissueDate;

    /**
     * Billing-cycle credit accumulator. {@code ACCT-CURR-CYC-CREDIT PIC S9(10)V99} at
     * {@code app/cpy/CVACT01Y.cpy:L13}. Twelve total digits, scale 2.
     * {@code app/cbl/CBTRN02C.cbl:L549} adds a transaction amount of zero or more to this field,
     * and {@code app/cbl/CBTRN02C.cbl:L403} reads it as the first term of the credit-limit
     * comparison. {@code app/cbl/CBACT04C.cbl:L353} moves zero into it at billing-cycle close, and
     * the cycle-close operation of this module writes the same value.
     */
    @Column(name = "current_cycle_credit", nullable = false,
            precision = PicClause.ACCT_CURR_CYC_CREDIT_PRECISION,
            scale = PicClause.ACCT_CURR_CYC_CREDIT_SCALE)
    private BigDecimal currentCycleCredit;

    /**
     * Billing-cycle debit accumulator. {@code ACCT-CURR-CYC-DEBIT PIC S9(10)V99} at
     * {@code app/cpy/CVACT01Y.cpy:L14}. Twelve total digits, scale 2.
     * {@code app/cbl/CBTRN02C.cbl:L551} adds a transaction amount below zero to this field, and
     * {@code app/cbl/CBTRN02C.cbl:L404} subtracts it in the credit-limit comparison.
     * {@code app/cbl/CBACT04C.cbl:L354} moves zero into it at billing-cycle close.
     *
     * <p>Both accumulators hold {@code 00000000000} closed by an overpunched zero byte in all 50
     * records of {@code app/data/ASCII/acctdata.txt}. A freshly seeded account therefore reaches
     * the credit-limit comparison with zero on both terms.</p>
     */
    @Column(name = "current_cycle_debit", nullable = false,
            precision = PicClause.ACCT_CURR_CYC_DEBIT_PRECISION,
            scale = PicClause.ACCT_CURR_CYC_DEBIT_SCALE)
    private BigDecimal currentCycleDebit;

    /**
     * Address postal code. {@code ACCT-ADDR-ZIP PIC X(10)} at
     * {@code app/cpy/CVACT01Y.cpy:L15}. No source paragraph edits this field, and the account
     * update program edits {@code CUST-ADDR-ZIP} alone. All 50 records of
     * {@code app/data/ASCII/acctdata.txt} hold {@code A000000000}.
     */
    @Column(name = "address_zip", nullable = false, length = PicClause.ACCT_ADDR_ZIP_WIDTH)
    private String addressZip;

    /**
     * Account group identifier. {@code ACCT-GROUP-ID PIC X(10)} at
     * {@code app/cpy/CVACT01Y.cpy:L16}. The column holds a value and carries no foreign key.
     *
     * <p>{@code app/cbl/COACTUPC.cbl:L4139-L4140} applies {@code FUNCTION LOWER-CASE} to both sides
     * when it compares the field. {@code app/cbl/CBACT04C.cbl:L210} keys the disclosure group
     * lookup on it, and {@code app/cbl/CBACT04C.cbl:L437} substitutes the literal {@code DEFAULT}
     * on a record-not-found status. All 50 records of {@code app/data/ASCII/acctdata.txt} hold ten
     * spaces.</p>
     */
    @Column(name = "group_id", nullable = false, length = PicClause.ACCT_GROUP_ID_WIDTH)
    private String groupId;

    /**
     * Creates an account with every field unset. Jakarta Persistence calls this constructor when it
     * materialises a row, and a caller fills the twelve fields through the setters.
     */
    public AccountEntity() {
    }

    /**
     * Returns the primary key.
     *
     * @return the eleven-digit account identifier, or {@code null} when it is unset
     */
    public BigDecimal getAccountId() {
        return accountId;
    }

    /**
     * Sets the primary key.
     *
     * @param accountId the eleven-digit account identifier
     */
    public void setAccountId(BigDecimal accountId) {
        this.accountId = accountId;
    }

    /**
     * Returns the status flag.
     *
     * @return one character, or {@code null} when the flag is unset
     */
    public String getActiveStatus() {
        return activeStatus;
    }

    /**
     * Sets the status flag.
     *
     * @param activeStatus one character
     */
    public void setActiveStatus(String activeStatus) {
        this.activeStatus = activeStatus;
    }

    /**
     * Returns the posted balance.
     *
     * @return the balance at scale 2, or {@code null} when it is unset
     */
    public BigDecimal getCurrentBalance() {
        return currentBalance;
    }

    /**
     * Sets the posted balance.
     *
     * @param currentBalance the balance at scale 2
     */
    public void setCurrentBalance(BigDecimal currentBalance) {
        this.currentBalance = currentBalance;
    }

    /**
     * Returns the credit limit the authorization decision compares against.
     *
     * @return the credit limit at scale 2, or {@code null} when it is unset
     */
    public BigDecimal getCreditLimit() {
        return creditLimit;
    }

    /**
     * Sets the credit limit.
     *
     * @param creditLimit the credit limit at scale 2
     */
    public void setCreditLimit(BigDecimal creditLimit) {
        this.creditLimit = creditLimit;
    }

    /**
     * Returns the cash credit limit.
     *
     * @return the cash credit limit at scale 2, or {@code null} when it is unset
     */
    public BigDecimal getCashCreditLimit() {
        return cashCreditLimit;
    }

    /**
     * Sets the cash credit limit.
     *
     * @param cashCreditLimit the cash credit limit at scale 2
     */
    public void setCashCreditLimit(BigDecimal cashCreditLimit) {
        this.cashCreditLimit = cashCreditLimit;
    }

    /**
     * Returns the open date as text.
     *
     * @return ten characters, or {@code null} when the date is unset
     */
    public String getOpenDate() {
        return openDate;
    }

    /**
     * Sets the open date as text.
     *
     * @param openDate ten characters
     */
    public void setOpenDate(String openDate) {
        this.openDate = openDate;
    }

    /**
     * Returns the expiration date as text. The authorization decision compares this value with the
     * leading ten characters of a transaction origin timestamp.
     *
     * @return ten characters, or {@code null} when the date is unset
     */
    public String getExpirationDate() {
        return expirationDate;
    }

    /**
     * Sets the expiration date as text.
     *
     * @param expirationDate ten characters
     */
    public void setExpirationDate(String expirationDate) {
        this.expirationDate = expirationDate;
    }

    /**
     * Returns the reissue date as text.
     *
     * @return ten characters, or {@code null} when the date is unset
     */
    public String getReissueDate() {
        return reissueDate;
    }

    /**
     * Sets the reissue date as text.
     *
     * @param reissueDate ten characters
     */
    public void setReissueDate(String reissueDate) {
        this.reissueDate = reissueDate;
    }

    /**
     * Returns the billing-cycle credit accumulator.
     *
     * @return the accumulator at scale 2, or {@code null} when it is unset
     */
    public BigDecimal getCurrentCycleCredit() {
        return currentCycleCredit;
    }

    /**
     * Sets the billing-cycle credit accumulator.
     *
     * @param currentCycleCredit the accumulator at scale 2
     */
    public void setCurrentCycleCredit(BigDecimal currentCycleCredit) {
        this.currentCycleCredit = currentCycleCredit;
    }

    /**
     * Returns the billing-cycle debit accumulator.
     *
     * @return the accumulator at scale 2, or {@code null} when it is unset
     */
    public BigDecimal getCurrentCycleDebit() {
        return currentCycleDebit;
    }

    /**
     * Sets the billing-cycle debit accumulator.
     *
     * @param currentCycleDebit the accumulator at scale 2
     */
    public void setCurrentCycleDebit(BigDecimal currentCycleDebit) {
        this.currentCycleDebit = currentCycleDebit;
    }

    /**
     * Returns the address postal code.
     *
     * @return ten characters, or {@code null} when the code is unset
     */
    public String getAddressZip() {
        return addressZip;
    }

    /**
     * Sets the address postal code.
     *
     * @param addressZip ten characters
     */
    public void setAddressZip(String addressZip) {
        this.addressZip = addressZip;
    }

    /**
     * Returns the account group identifier.
     *
     * @return ten characters, or {@code null} when the identifier is unset
     */
    public String getGroupId() {
        return groupId;
    }

    /**
     * Sets the account group identifier.
     *
     * @param groupId ten characters
     */
    public void setGroupId(String groupId) {
        this.groupId = groupId;
    }

    /**
     * Compares this account with another object by account identifier. Two accounts are equal when
     * both identifiers are present and numerically equal. {@code BigDecimal.equals} separates two
     * values that differ only in scale, and this method compares with {@code compareTo}.
     *
     * @param other the object to compare with this account
     * @return {@code true} when the other object is an account carrying the same identifier
     */
    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof AccountEntity account)) {
            return false;
        }
        return accountId != null
                && account.accountId != null
                && accountId.compareTo(account.accountId) == 0;
    }

    /**
     * Returns a hash code taken from the account identifier. {@code stripTrailingZeros} supplies a
     * canonical form, which keeps this code consistent with {@link #equals(Object)} across scales.
     *
     * @return the hash code of the canonical identifier, or zero when the identifier is unset
     */
    @Override
    public int hashCode() {
        return accountId == null ? 0 : accountId.stripTrailingZeros().hashCode();
    }

    /**
     * Returns the account identifier and the status flag. No monetary value and no date appears in
     * the text.
     *
     * @return a short description of this account
     */
    @Override
    public String toString() {
        return "AccountEntity[accountId=" + accountId + ", activeStatus=" + activeStatus + "]";
    }
}
