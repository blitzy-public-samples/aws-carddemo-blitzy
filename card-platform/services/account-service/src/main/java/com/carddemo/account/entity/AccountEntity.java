package com.carddemo.account.entity;

import com.carddemo.events.EventEnvelope;
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
 * <p>Twelve mapped fields carry the twelve copybook fields at {@code app/cpy/CVACT01Y.cpy:L5-L16}.
 * Their widths total 122 bytes. The trailing {@code FILLER PIC X(178)} at
 * {@code app/cpy/CVACT01Y.cpy:L17} maps to no column and brings the record to
 * {@link PicClause#ACCOUNT_RECORD_LENGTH} bytes, matching {@code RECORDSIZE(300 300)} at
 * {@code app/jcl/ACCTFILE.jcl:L41}.</p>
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
 * credit-limit decision.</p>
 *
 * <p>This class holds no arithmetic, no format check and no status check. It declares no
 * association, no version column and no generated value.</p>
 *
 * <p>Design decisions: {@code card-platform/docs/decision-log.md}.
 */
@Entity
@Table(name = "account")
public class AccountEntity {

    /**
     * Primary key. {@code ACCT-ID PIC 9(11)} at {@code app/cpy/CVACT01Y.cpy:L5}. Exactly eleven
     * digit characters, held in column {@code account_id CHAR(11)}.
     *
     * <p>Several source programs compare an account identifier as text, and the column now keeps
     * the digits of the source key without change. {@code PIC 9(11)} is a display field, and every
     * record of {@code app/data/ASCII/acctdata.txt} fills all eleven positions: record one holds
     * {@code 00000000001}. A numeric column stores that as one and returns {@code 1}. Every
     * consumer would then have to re-pad it before use, to reach one of three destinations.
     *
     * <ul>
     *   <li>the eleven-character key at {@code KEYS(11 0)} in {@code app/jcl/ACCTFILE.jcl:L40}</li>
     *   <li>the account identifier the card service holds</li>
     *   <li>the aggregate identifier an event carries</li>
     * </ul>
     *
     * <p>The check constraint {@code ck_account_account_id_digits} holds the width and the digit
     * class.</p>
     */
    @Id
    @Column(name = "account_id", nullable = false,
            length = PicClause.ACCT_ID_WIDTH,
            columnDefinition = "bpchar(" + PicClause.ACCT_ID_WIDTH + ")")
    private String accountId;

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

    public AccountEntity() {
    }

    /**
     * Returns the primary key.
     *
     * @return the eleven-digit account identifier, or {@code null} when it is unset
     */
    public String getAccountId() {
        return accountId;
    }

    /**
     * Sets the primary key.
     *
     * <p>The guard runs here rather than at flush time. A caller that passes {@code "1"} for the
     * account the record writes as {@code 00000000001} learns so at the call site. The database
     * would otherwise report a check-constraint violation several statements later.</p>
     *
     * @param accountId the account identifier, exactly {@value PicClause#ACCT_ID_WIDTH} digits
     * @throws IllegalArgumentException when the argument is absent, the wrong width, or holds a
     *                                 character that is not a digit
     */
    public void setAccountId(String accountId) {
        this.accountId = requireDigits("accountId", accountId, PicClause.ACCT_ID_WIDTH);
    }

    public String getActiveStatus() {
        return activeStatus;
    }

    public void setActiveStatus(String activeStatus) {
        this.activeStatus = activeStatus;
    }

    public BigDecimal getCurrentBalance() {
        return currentBalance;
    }

    public void setCurrentBalance(BigDecimal currentBalance) {
        this.currentBalance = currentBalance;
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

    public String getOpenDate() {
        return openDate;
    }

    public void setOpenDate(String openDate) {
        this.openDate = openDate;
    }

    public String getExpirationDate() {
        return expirationDate;
    }

    public void setExpirationDate(String expirationDate) {
        this.expirationDate = expirationDate;
    }

    public String getReissueDate() {
        return reissueDate;
    }

    public void setReissueDate(String reissueDate) {
        this.reissueDate = reissueDate;
    }

    public BigDecimal getCurrentCycleCredit() {
        return currentCycleCredit;
    }

    public void setCurrentCycleCredit(BigDecimal currentCycleCredit) {
        this.currentCycleCredit = currentCycleCredit;
    }

    public BigDecimal getCurrentCycleDebit() {
        return currentCycleDebit;
    }

    public void setCurrentCycleDebit(BigDecimal currentCycleDebit) {
        this.currentCycleDebit = currentCycleDebit;
    }

    public String getAddressZip() {
        return addressZip;
    }

    public void setAddressZip(String addressZip) {
        this.addressZip = addressZip;
    }

    public String getGroupId() {
        return groupId;
    }

    public void setGroupId(String groupId) {
        this.groupId = groupId;
    }

    /**
     * Compares this account with another object by account identifier. Two accounts are equal when
     * both identifiers are present and hold the same characters.
     *
     * <p>The identifier is a fixed-width digit string, so character equality is the whole test. No
     * canonical form applies: the width guard admits exactly one spelling of each identifier.</p>
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
                && accountId.equals(account.accountId);
    }

    /**
     * Returns a hash code taken from the account identifier.
     *
     * <p>The identifier carries a fixed count of digits, so the stored characters are already the
     * canonical form and this code agrees with {@link #equals(Object)} without normalising
     * anything.
     *
     * @return the hash code of the identifier, or zero when the identifier is unset
     */
    @Override
    public int hashCode() {
        return accountId == null ? 0 : accountId.hashCode();
    }

    /**
     * Names the account identifier and the status flag, and withholds both values.
     *
     * <p>An account identifier identifies the customer this row belongs to, so neither value
     * reaches the text. Each appears as {@link EventEnvelope#WITHHELD}, the platform-wide redaction
     * marker. No monetary value and no date appears either.
     *
     * @return a short description of this account that discloses no value
     */
    @Override
    public String toString() {
        return "AccountEntity[accountId=" + EventEnvelope.WITHHELD + ", activeStatus="
                + EventEnvelope.WITHHELD + "]";
    }

    /**
     * Rejects an identifier that is the wrong width or carries a character outside {@code 0}
     * through {@code 9}.
     *
     * <p>A {@code PIC 9(n)} display field is exactly n characters wide and holds only digits, and
     * the column check constraint repeats both halves in the database. Neither message carries a
     * character of the rejected value. The width message reports a length and the digit message
     * reports a position, which keeps a Social Security Number out of any log line a caller
     * writes from a failure.</p>
     *
     * @param field the field name the message reports
     * @param value the value under test
     * @param width the exact number of digits the source picture clause declares
     * @return the supplied value
     * @throws IllegalArgumentException when the value is absent, the wrong width, or holds a
     *                                 character that is not a digit
     */
    private static String requireDigits(String field, String value, int width) {
        if (value == null) {
            throw new IllegalArgumentException(field + " is required");
        }
        if (value.length() != width) {
            throw new IllegalArgumentException(field + " must be exactly " + width
                    + " digits wide, found width " + value.length());
        }
        for (int position = 0; position < width; position++) {
            char character = value.charAt(position);
            if (character < '0' || character > '9') {
                throw new IllegalArgumentException(field
                        + " must hold digits only, found a character outside 0 through 9 at "
                        + "position " + (position + 1));
            }
        }
        return value;
    }
}
