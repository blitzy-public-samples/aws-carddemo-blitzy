package com.carddemo.authorization.entity;

import com.carddemo.cobol.PicClause;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.util.Objects;

/**
 * Credit and expiry values the authorization decline rules read for one account.
 *
 * <p>Five of the thirteen fields declared in {@code app/cpy/CVACT01Y.cpy} appear here as columns.
 * The account service owns the whole account record, and account state-change events keep these
 * rows current. The table is {@code account_credit_snapshot}, created by
 * {@code src/main/resources/db/migration/V1__schema.sql}.</p>
 *
 * <p>Mapped fields, in source order:</p>
 *
 * <pre>
 * account_id               ACCT-ID               PIC 9(11)      L5
 * credit_limit             ACCT-CREDIT-LIMIT     PIC S9(10)V99  L8
 * account_expiration_date  ACCT-EXPIRAION-DATE   PIC X(10)      L11
 * current_cycle_credit     ACCT-CURR-CYC-CREDIT  PIC S9(10)V99  L13
 * current_cycle_debit      ACCT-CURR-CYC-DEBIT   PIC S9(10)V99  L14
 * </pre>
 *
 * <p>The primary key comes from {@code KEYS(11 0)} at {@code app/jcl/ACCTFILE.jcl:L40}: eleven
 * bytes at offset zero of the 300-byte record that {@code RECORDSIZE(300 300)} at L41 fixes.
 * Nothing generates the identifier.</p>
 *
 * <p>Paragraph {@code 1500-B-LOOKUP-ACCT} at {@code app/cbl/CBTRN02C.cbl:L393-L422} reads the four
 * business fields. Lines L403 to L405 compute a working balance from the two cycle accumulators and
 * the transaction amount, and L407 compares the credit limit against it. Line L414 compares
 * {@code ACCT-EXPIRAION-DATE} against the first ten characters of the transaction origin timestamp,
 * character by character. The working field {@code WS-TEMP-BAL PIC S9(09)V99} at L187 is one
 * integer digit narrower than the two accumulators and the credit limit, and
 * {@code domain/rules/CreditLimitRule.java} carries that narrower width.</p>
 *
 * <p>Both accumulators are signed and unconstrained. Line L551 of the same program adds a negative
 * amount to {@code ACCT-CURR-CYC-DEBIT}, and 50 of the 300 records in
 * {@code app/data/ASCII/dailytran.txt} carry one. The account service zeroes both accumulators at
 * its cycle-close operation, reproducing {@code app/cbl/CBACT04C.cbl:L353-L354}.</p>
 *
 * <p>Eight source fields carry no column. Three of them sit outside every decline rule:
 * {@code ACCT-CASH-CREDIT-LIMIT PIC S9(10)V99} at L9, {@code ACCT-OPEN-DATE PIC X(10)} at L10 and
 * {@code ACCT-REISSUE-DATE PIC X(10)} at L12. The five others:</p>
 *
 * <ul>
 *   <li>{@code ACCT-ACTIVE-STATUS PIC X(01)} at L6. No program reads it before posting.</li>
 *   <li>{@code ACCT-CURR-BAL PIC S9(10)V99} at L7. The formula at L403 to L405 omits it.</li>
 *   <li>{@code ACCT-ADDR-ZIP PIC X(10)} at L15 and {@code ACCT-GROUP-ID PIC X(10)} at L16. The
 *       account service holds both.</li>
 *   <li>{@code FILLER PIC X(178)} at L17. No column models these bytes.</li>
 * </ul>
 *
 * <p>{@code card-platform/docs/traceability-matrix.md} accounts for all thirteen fields,
 * {@code card-platform/docs/decision-log.md} carries the decisions behind this projection, and
 * {@code card-platform/docs/business-rule-flags.md} registers the source behaviour cited above.</p>
 */
@Entity
@Table(name = "account_credit_snapshot")
public class AccountCreditSnapshotEntity {

    /**
     * Digits after the decimal point in {@code ACCT-ID}, {@code PIC 9(11)} at
     * {@code app/cpy/CVACT01Y.cpy:L5}. The picture declares no decimal positions.
     * {@link PicClause} publishes a width for the field and no scale.
     */
    private static final int ACCOUNT_ID_SCALE = 0;

    /**
     * {@code ACCT-ID PIC 9(11)} at {@code app/cpy/CVACT01Y.cpy:L5}, held as
     * {@code NUMERIC(11,0)}: up to eleven integer digits and no fractional digit. Account record 7
     * of {@code app/data/ASCII/acctdata.txt} writes the identifier as {@code 00000000007}, and the
     * numeric value is 7. A caller that needs the eleven-character padded form applies the padding
     * itself.
     */
    @Id
    @Column(name = "account_id", nullable = false,
            precision = PicClause.ACCT_ID_WIDTH, scale = ACCOUNT_ID_SCALE)
    private BigDecimal accountId;

    /**
     * {@code ACCT-CREDIT-LIMIT PIC S9(10)V99} at {@code app/cpy/CVACT01Y.cpy:L8}, held as
     * {@code NUMERIC(12,2)}. Ten integer digits and two fractional digits total twelve.
     */
    @Column(name = "credit_limit", nullable = false,
            precision = PicClause.ACCT_CREDIT_LIMIT_PRECISION,
            scale = PicClause.ACCT_CREDIT_LIMIT_SCALE)
    private BigDecimal creditLimit;

    /**
     * {@code ACCT-EXPIRAION-DATE PIC X(10)} at {@code app/cpy/CVACT01Y.cpy:L11}, held as
     * {@code VARCHAR(10)} text. The column name and this field name correct the transposed
     * spelling the copybook carries. {@code app/cbl/CBTRN02C.cbl:L414} compares the ten characters
     * against the leading ten characters of a 26-character timestamp.
     */
    @Column(name = "account_expiration_date", nullable = false,
            length = PicClause.ACCT_EXPIRATION_DATE_WIDTH)
    private String accountExpirationDate;

    /**
     * {@code ACCT-CURR-CYC-CREDIT PIC S9(10)V99} at {@code app/cpy/CVACT01Y.cpy:L13}, held as
     * {@code NUMERIC(12,2)}. {@code app/cbl/CBTRN02C.cbl:L549} adds an amount of zero or more to
     * the accumulator, and L403 reads it.
     */
    @Column(name = "current_cycle_credit", nullable = false,
            precision = PicClause.ACCT_CURR_CYC_CREDIT_PRECISION,
            scale = PicClause.ACCT_CURR_CYC_CREDIT_SCALE)
    private BigDecimal currentCycleCredit;

    /**
     * {@code ACCT-CURR-CYC-DEBIT PIC S9(10)V99} at {@code app/cpy/CVACT01Y.cpy:L14}, held as
     * {@code NUMERIC(12,2)}. {@code app/cbl/CBTRN02C.cbl:L551} adds a negative amount to the
     * accumulator, and L404 subtracts it. Any sign is valid.
     */
    @Column(name = "current_cycle_debit", nullable = false,
            precision = PicClause.ACCT_CURR_CYC_DEBIT_PRECISION,
            scale = PicClause.ACCT_CURR_CYC_DEBIT_SCALE)
    private BigDecimal currentCycleDebit;

    /**
     * No-argument constructor for the persistence provider, which sets the five fields by
     * reflection after it builds the instance.
     */
    protected AccountCreditSnapshotEntity() {
        // The provider populates every field. Callers use the five-argument constructor.
    }

    /**
     * Builds one snapshot row.
     *
     * <p>Each argument is mandatory. The three monetary arguments carry scale 2 and the identifier
     * carries scale 0, matching the picture clauses at {@code app/cpy/CVACT01Y.cpy:L5},
     * {@code :L8}, {@code :L13} and {@code :L14}. The expiration date carries exactly ten
     * characters, matching {@code PIC X(10)} at {@code :L11}. An argument that misses one of those
     * shapes fails here.</p>
     *
     * <p>Account record 7 of {@code app/data/ASCII/acctdata.txt} supplies a worked set of values:
     * identifier {@code 00000000007}, credit limit {@code 2065.00}, expiration date
     * {@code 2024-12-13}, and {@code 0.00} in each accumulator.</p>
     *
     * @param accountId             {@code ACCT-ID}, zero or greater, scale 0
     * @param creditLimit           {@code ACCT-CREDIT-LIMIT}, scale 2, either sign
     * @param accountExpirationDate {@code ACCT-EXPIRAION-DATE}, exactly ten characters
     * @param currentCycleCredit    {@code ACCT-CURR-CYC-CREDIT}, scale 2, either sign
     * @param currentCycleDebit     {@code ACCT-CURR-CYC-DEBIT}, scale 2, either sign
     * @throws NullPointerException     when any argument is {@code null}
     * @throws IllegalArgumentException when a scale, a sign or a length does not match the source
     *                                  picture clause
     */
    public AccountCreditSnapshotEntity(BigDecimal accountId, BigDecimal creditLimit,
            String accountExpirationDate, BigDecimal currentCycleCredit,
            BigDecimal currentCycleDebit) {

        Objects.requireNonNull(accountId, "accountId is required");
        Objects.requireNonNull(creditLimit, "creditLimit is required");
        Objects.requireNonNull(accountExpirationDate, "accountExpirationDate is required");
        Objects.requireNonNull(currentCycleCredit, "currentCycleCredit is required");
        Objects.requireNonNull(currentCycleDebit, "currentCycleDebit is required");

        requireScale(accountId, ACCOUNT_ID_SCALE, "accountId");
        if (accountId.signum() < 0) {
            throw new IllegalArgumentException(
                    "accountId must be zero or greater, found " + accountId.toPlainString());
        }

        requireScale(creditLimit, PicClause.ACCT_CREDIT_LIMIT_SCALE, "creditLimit");
        requireScale(currentCycleCredit, PicClause.ACCT_CURR_CYC_CREDIT_SCALE,
                "currentCycleCredit");
        requireScale(currentCycleDebit, PicClause.ACCT_CURR_CYC_DEBIT_SCALE, "currentCycleDebit");

        if (accountExpirationDate.length() != PicClause.ACCT_EXPIRATION_DATE_WIDTH) {
            throw new IllegalArgumentException("accountExpirationDate must hold exactly "
                    + PicClause.ACCT_EXPIRATION_DATE_WIDTH + " characters, found "
                    + accountExpirationDate.length());
        }

        this.accountId = accountId;
        this.creditLimit = creditLimit;
        this.accountExpirationDate = accountExpirationDate;
        this.currentCycleCredit = currentCycleCredit;
        this.currentCycleDebit = currentCycleDebit;
    }

    /**
     * Checks one argument against the scale its source picture clause declares.
     *
     * <p>The check reads {@link BigDecimal#scale()} and adjusts nothing. A value at another scale
     * signals a mapping defect, and it stops here.</p>
     *
     * @param value the argument under test
     * @param scale the scale the source picture clause declares
     * @param field the field name the message reports
     * @throws IllegalArgumentException when the scales differ
     */
    private static void requireScale(BigDecimal value, int scale, String field) {
        if (value.scale() != scale) {
            throw new IllegalArgumentException(field + " must carry scale " + scale + ", found "
                    + value.scale() + " in " + value.toPlainString());
        }
    }

    /**
     * Returns {@code ACCT-ID}, the primary key.
     *
     * @return the account identifier at scale 0
     */
    public BigDecimal getAccountId() {
        return accountId;
    }

    /**
     * Returns {@code ACCT-CREDIT-LIMIT}, the value {@code app/cbl/CBTRN02C.cbl:L407} compares
     * against the working balance.
     *
     * @return the credit limit at scale 2
     */
    public BigDecimal getCreditLimit() {
        return creditLimit;
    }

    /**
     * Returns the account expiration date as the ten characters
     * {@code app/cbl/CBTRN02C.cbl:L414} compares as text.
     *
     * @return the expiration date, ten characters
     */
    public String getAccountExpirationDate() {
        return accountExpirationDate;
    }

    /**
     * Returns {@code ACCT-CURR-CYC-CREDIT}, the first operand of the working balance at
     * {@code app/cbl/CBTRN02C.cbl:L403}.
     *
     * @return the cycle credit accumulator at scale 2
     */
    public BigDecimal getCurrentCycleCredit() {
        return currentCycleCredit;
    }

    /**
     * Returns {@code ACCT-CURR-CYC-DEBIT}, the operand {@code app/cbl/CBTRN02C.cbl:L404}
     * subtracts. The value carries either sign.
     *
     * @return the cycle debit accumulator at scale 2
     */
    public BigDecimal getCurrentCycleDebit() {
        return currentCycleDebit;
    }

    /**
     * Compares on {@code accountId} alone, the primary key of
     * {@code account_credit_snapshot}.
     *
     * @param other the object under comparison
     * @return {@code true} when both hold the same account identifier
     */
    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof AccountCreditSnapshotEntity that)) {
            return false;
        }
        return Objects.equals(accountId, that.accountId);
    }

    /**
     * Hashes {@code accountId} alone, matching {@link #equals(Object)}.
     *
     * @return the hash of the account identifier
     */
    @Override
    public int hashCode() {
        return Objects.hashCode(accountId);
    }

    /**
     * Renders the identifier and the four business values. No card number reaches this text.
     *
     * @return a single-line rendering of all five columns
     */
    @Override
    public String toString() {
        return "AccountCreditSnapshotEntity[accountId=" + accountId
                + ", creditLimit=" + creditLimit
                + ", accountExpirationDate=" + accountExpirationDate
                + ", currentCycleCredit=" + currentCycleCredit
                + ", currentCycleDebit=" + currentCycleDebit
                + "]";
    }
}
