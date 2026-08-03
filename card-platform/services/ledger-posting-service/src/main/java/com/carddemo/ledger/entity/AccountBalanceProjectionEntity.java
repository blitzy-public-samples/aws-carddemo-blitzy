package com.carddemo.ledger.entity;

import com.carddemo.cobol.PicClause;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Balance and the two billing-cycle accumulators of one account, held in table
 * {@code account_balance_projection}.
 *
 * <p>Four fields of {@code 01 ACCOUNT-RECORD} at {@code app/cpy/CVACT01Y.cpy} become four columns.
 * The key width comes from {@code KEYS(11 0)} at {@code app/jcl/ACCTFILE.jcl:L40}, and the record
 * length from {@code RECORDSIZE(300 300)} at {@code app/jcl/ACCTFILE.jcl:L41}.
 *
 * <pre>
 * ACCT-ID               PIC 9(11)      app/cpy/CVACT01Y.cpy:L5    account_id
 * ACCT-CURR-BAL         PIC S9(10)V99  app/cpy/CVACT01Y.cpy:L7    current_balance
 * ACCT-CURR-CYC-CREDIT  PIC S9(10)V99  app/cpy/CVACT01Y.cpy:L13   cycle_credit
 * ACCT-CURR-CYC-DEBIT   PIC S9(10)V99  app/cpy/CVACT01Y.cpy:L14   cycle_debit
 * </pre>
 *
 * <p>Events keep the projection current. The account service owns the whole 300-byte record and
 * every field this table leaves out.
 *
 * <p>Each monetary column holds twelve total digits, two of them after the decimal point, and
 * accepts a negative value. The all-arguments constructor rejects an argument at any other scale.
 * The class stores values and performs no arithmetic. The domain package computes each posted
 * amount and supplies the result.
 *
 * <p>Rationale for every choice here: {@code card-platform/docs/decision-log.md}. Field-by-field
 * source mapping and every omitted field: {@code card-platform/docs/traceability-matrix.md}.
 */
@Entity
@Table(name = "account_balance_projection")
public class AccountBalanceProjectionEntity {

    /**
     * Shape of a stored account identifier, compiled from {@link PicClause#ACCT_ID_WIDTH} as
     * {@code ^[0-9]{11}$}: exactly eleven digits, from {@code ACCT-ID PIC 9(11)} at
     * {@code app/cpy/CVACT01Y.cpy:L5}.
     */
    private static final Pattern ACCOUNT_ID_PATTERN =
            Pattern.compile("^[0-9]{" + PicClause.ACCT_ID_WIDTH + "}$");

    /**
     * Account identifier and primary key, from {@code ACCT-ID PIC 9(11)} at
     * {@code app/cpy/CVACT01Y.cpy:L5}. The column keeps the padded digits at the width
     * {@code KEYS(11 0)} declares at {@code app/jcl/ACCTFILE.jcl:L40}.
     */
    @Id
    @Column(name = "account_id", nullable = false, length = PicClause.ACCT_ID_WIDTH)
    private String accountId;

    /**
     * Account balance after posting, from {@code ACCT-CURR-BAL PIC S9(10)V99} at
     * {@code app/cpy/CVACT01Y.cpy:L7}. The value may be negative.
     */
    @Column(name = "current_balance", nullable = false,
            precision = PicClause.ACCT_CURR_BAL_PRECISION,
            scale = PicClause.ACCT_CURR_BAL_SCALE)
    private BigDecimal currentBalance;

    /**
     * Accumulated non-negative transaction amounts of the current billing cycle, from
     * {@code ACCT-CURR-CYC-CREDIT PIC S9(10)V99} at {@code app/cpy/CVACT01Y.cpy:L13}. The value
     * may be negative.
     */
    @Column(name = "cycle_credit", nullable = false,
            precision = PicClause.ACCT_CURR_CYC_CREDIT_PRECISION,
            scale = PicClause.ACCT_CURR_CYC_CREDIT_SCALE)
    private BigDecimal cycleCredit;

    /**
     * Accumulated negative transaction amounts of the current billing cycle, from
     * {@code ACCT-CURR-CYC-DEBIT PIC S9(10)V99} at {@code app/cpy/CVACT01Y.cpy:L14}. The value may
     * be negative.
     */
    @Column(name = "cycle_debit", nullable = false,
            precision = PicClause.ACCT_CURR_CYC_DEBIT_PRECISION,
            scale = PicClause.ACCT_CURR_CYC_DEBIT_SCALE)
    private BigDecimal cycleDebit;

    /** No-argument constructor the Jakarta Persistence API provider requires. */
    protected AccountBalanceProjectionEntity() {
        // The provider assigns every field after it constructs the instance.
    }

    /**
     * Creates one projection row.
     *
     * @param accountId      exactly eleven digits, from {@code ACCT-ID} at
     *                       {@code app/cpy/CVACT01Y.cpy:L5}
     * @param currentBalance balance at scale two, from {@code ACCT-CURR-BAL} at
     *                       {@code app/cpy/CVACT01Y.cpy:L7}
     * @param cycleCredit    cycle credit accumulator at scale two, from
     *                       {@code ACCT-CURR-CYC-CREDIT} at {@code app/cpy/CVACT01Y.cpy:L13}
     * @param cycleDebit     cycle debit accumulator at scale two, from
     *                       {@code ACCT-CURR-CYC-DEBIT} at {@code app/cpy/CVACT01Y.cpy:L14}
     * @throws NullPointerException     when any argument is {@code null}
     * @throws IllegalArgumentException when {@code accountId} carries anything other than eleven
     *                                  digits, or when a monetary argument carries the wrong scale
     *                                  or too many digits ahead of the decimal point
     */
    public AccountBalanceProjectionEntity(String accountId, BigDecimal currentBalance,
            BigDecimal cycleCredit, BigDecimal cycleDebit) {
        this.accountId = requireAccountId(accountId);
        this.currentBalance = requireStorable(currentBalance, "currentBalance",
                PicClause.ACCT_CURR_BAL_PRECISION, PicClause.ACCT_CURR_BAL_SCALE);
        this.cycleCredit = requireStorable(cycleCredit, "cycleCredit",
                PicClause.ACCT_CURR_CYC_CREDIT_PRECISION, PicClause.ACCT_CURR_CYC_CREDIT_SCALE);
        this.cycleDebit = requireStorable(cycleDebit, "cycleDebit",
                PicClause.ACCT_CURR_CYC_DEBIT_PRECISION, PicClause.ACCT_CURR_CYC_DEBIT_SCALE);
    }

    /**
     * Returns {@code accountId} when it carries exactly eleven digits.
     *
     * @param accountId candidate identifier
     * @return the identifier unchanged
     * @throws NullPointerException     when {@code accountId} is {@code null}
     * @throws IllegalArgumentException when {@code accountId} carries anything else
     */
    private static String requireAccountId(String accountId) {
        Objects.requireNonNull(accountId, "accountId");
        if (!ACCOUNT_ID_PATTERN.matcher(accountId).matches()) {
            throw new IllegalArgumentException(
                    "accountId must carry exactly " + PicClause.ACCT_ID_WIDTH + " digits");
        }
        return accountId;
    }

    /**
     * Returns {@code value} when a column of the given precision and scale holds it.
     *
     * @param value     candidate amount
     * @param field     field name quoted in every failure message
     * @param precision total digits the column holds
     * @param scale     digits after the decimal point the column holds
     * @return the amount unchanged
     * @throws NullPointerException     when {@code value} is {@code null}
     * @throws IllegalArgumentException when the scale differs, or when the digits ahead of the
     *                                  decimal point exceed what the column holds
     */
    private static BigDecimal requireStorable(BigDecimal value, String field, int precision,
            int scale) {
        Objects.requireNonNull(value, field);
        if (value.scale() != scale) {
            throw new IllegalArgumentException(field + " scale must be " + scale);
        }
        int integerDigitLimit = precision - scale;
        if (value.precision() - value.scale() > integerDigitLimit) {
            throw new IllegalArgumentException(field + " must carry at most " + integerDigitLimit
                    + " digits ahead of the decimal point");
        }
        return value;
    }

    /**
     * Returns the account identifier, eleven digits wide.
     *
     * @return the value of column {@code account_id}
     */
    public String getAccountId() {
        return accountId;
    }

    /**
     * Returns the account balance.
     *
     * @return the value of column {@code current_balance}, at scale two
     */
    public BigDecimal getCurrentBalance() {
        return currentBalance;
    }

    /**
     * Returns the cycle credit accumulator.
     *
     * @return the value of column {@code cycle_credit}, at scale two
     */
    public BigDecimal getCycleCredit() {
        return cycleCredit;
    }

    /**
     * Returns the cycle debit accumulator.
     *
     * @return the value of column {@code cycle_debit}, at scale two
     */
    public BigDecimal getCycleDebit() {
        return cycleDebit;
    }

    /**
     * Compares on the account identifier, the primary key of {@code account_balance_projection}.
     *
     * @param other candidate for comparison
     * @return {@code true} when {@code other} is a projection carrying the same account identifier
     */
    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof AccountBalanceProjectionEntity that)) {
            return false;
        }
        return Objects.equals(accountId, that.accountId);
    }

    /**
     * Hashes the account identifier alone.
     *
     * @return the hash of column {@code account_id}
     */
    @Override
    public int hashCode() {
        return Objects.hashCode(accountId);
    }

    /**
     * Renders the identifier and the three amounts.
     *
     * @return one line naming the class, the account identifier and each amount
     */
    @Override
    public String toString() {
        return "AccountBalanceProjectionEntity[accountId=" + accountId
                + ", currentBalance=" + currentBalance
                + ", cycleCredit=" + cycleCredit
                + ", cycleDebit=" + cycleDebit + "]";
    }
}
