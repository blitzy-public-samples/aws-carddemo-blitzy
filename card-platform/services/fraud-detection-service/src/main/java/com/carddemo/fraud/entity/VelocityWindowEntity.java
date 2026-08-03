package com.carddemo.fraud.entity;

import com.carddemo.cobol.PicClause;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;
import java.io.Serializable;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Objects;

/**
 * One row of {@code velocity_window}, one fixed-width time bucket for one account. The velocity
 * rule in the sibling {@code domain} package reads the two counters the row holds.
 *
 * <p>No COBOL (Common Business Oriented Language) program in {@code app/cbl/} counts authorization
 * velocity or scores risk. ADDITIVE IN FULL: net new; no COBOL ancestor. Two column shapes are
 * borrowed, shape only: {@code account_id} from {@code XREF-ACCT-ID PIC 9(11)} at
 * {@code app/cpy/CVACT03Y.cpy:L7}, and {@code total_amount} from {@code TRAN-AMT PIC S9(09)V99} at
 * {@code app/cpy/CVTRA05Y.cpy:L10}. Every other column is additive with no source counterpart.</p>
 *
 * <p>{@code velocity_window} is a PostgreSQL table, not a cache. Its five columns, in declaration
 * order:</p>
 *
 * <pre>
 * account_id          CHAR(11)                    NOT NULL   key part 1
 * window_start        TIMESTAMP(6) WITH TIME ZONE NOT NULL   key part 2
 * authorization_count INTEGER                     NOT NULL
 * total_amount        NUMERIC(11,2)               NOT NULL
 * updated_at          TIMESTAMP(6) WITH TIME ZONE NOT NULL
 * </pre>
 *
 * <p>The primary key is {@code (account_id, window_start)} in that column order, and it is the only
 * index on the table. {@link VelocityWindowId} carries those two columns at the same two Java
 * types.</p>
 *
 * <p>The consumer adds an amount through {@code com.carddemo.cobol.CobolDecimal}, which truncates
 * every result toward zero. This class stores what the consumer computes and holds no arithmetic.
 * The consumer in the {@code messaging} package checks {@code processed_event} before it acts and
 * writes the marker in the same local transaction as this row, so a duplicate delivery counts
 * once.</p>
 *
 * <p>Flyway creates this table, and Jakarta Persistence validates this mapping against it at
 * start-up, so a column name or a column type that differs stops the application.
 * {@code card-platform/docs/decision-log.md} records the mapping decisions.</p>
 */
@Entity
@Table(name = "velocity_window")
@IdClass(VelocityWindowEntity.VelocityWindowId.class)
public class VelocityWindowEntity {

    /**
     * First part of the key. The column is {@code CHAR(11)}, a width taken from
     * {@code XREF-ACCT-ID PIC 9(11)} at {@code app/cpy/CVACT03Y.cpy:L7}, shape only. Eleven digits,
     * leading zeros significant.
     *
     * <p>PostgreSQL reports {@code CHAR(11)} as {@code bpchar} through Java Database Connectivity
     * (JDBC) metadata, and {@link Column#columnDefinition()} names that type verbatim.</p>
     */
    @Id
    @Column(name = "account_id", nullable = false, updatable = false,
            length = PicClause.XREF_ACCT_ID_WIDTH,
            columnDefinition = "bpchar(" + PicClause.XREF_ACCT_ID_WIDTH + ")")
    private String accountId;

    /**
     * Second part of the key, and the inclusive lower bound of the bucket. ADDITIVE, with no source
     * counterpart. The bucket width arrives from configuration.
     */
    @Id
    @Column(name = "window_start", nullable = false, updatable = false)
    private Instant windowStart;

    /**
     * Authorizations counted in the bucket. ADDITIVE, with no source counterpart. The consumer
     * raises the count by one for each event it has not already processed.
     */
    @Column(name = "authorization_count", nullable = false)
    private int authorizationCount;

    /**
     * Amounts totalled over the bucket. The column is {@code NUMERIC(11,2)}, a precision and a
     * scale taken from {@code TRAN-AMT PIC S9(09)V99} at {@code app/cpy/CVTRA05Y.cpy:L10},
     * shape only. A negative value is ordinary traffic: 50 of the 300 amounts in
     * {@code app/data/ASCII/dailytran.txt} are negative.
     *
     * <p>The value totals amounts over one time bucket. It is neither an account balance nor a
     * billing-cycle accumulator.</p>
     */
    @Column(name = "total_amount", nullable = false,
            precision = PicClause.TRAN_AMT_PRECISION, scale = PicClause.TRAN_AMT_SCALE)
    private BigDecimal totalAmount;

    /**
     * Time of the last change to the row. ADDITIVE, with no source counterpart. The caller supplies
     * the value, and no lifecycle callback sets it.
     */
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    /** Required by the persistence provider, which sets all five fields directly. */
    protected VelocityWindowEntity() {
    }

    /**
     * Builds one bucket.
     *
     * @param accountId          the account the bucket belongs to, eleven digits
     * @param windowStart        inclusive lower bound of the bucket
     * @param authorizationCount authorizations counted so far, zero or more
     * @param totalAmount        amounts totalled so far, at two digits after the decimal point
     * @param updatedAt          time of this change
     * @throws NullPointerException     if any reference argument is null
     * @throws IllegalArgumentException if the identifier is not eleven digits, if the count is
     *                                  negative, or if the total carries a scale or a digit count
     *                                  the column does not hold. The failure text names the length,
     *                                  the count or the scale, never the identifier itself
     */
    public VelocityWindowEntity(String accountId, Instant windowStart, int authorizationCount,
            BigDecimal totalAmount, Instant updatedAt) {
        this.accountId = requireAccountId(accountId);
        this.windowStart = Objects.requireNonNull(windowStart, "windowStart");
        this.authorizationCount = requireAuthorizationCount(authorizationCount);
        this.totalAmount = requireTotalAmount(totalAmount);
        this.updatedAt = Objects.requireNonNull(updatedAt, "updatedAt");
    }

    /**
     * Checks one account identifier against the width and the digits of {@code CHAR(11)}.
     *
     * @param value candidate identifier
     * @return {@code value}
     * @throws NullPointerException     if {@code value} is null
     * @throws IllegalArgumentException if {@code value} is not eleven digits. The failure text
     *                                  names the length or the position, never the identifier
     */
    private static String requireAccountId(String value) {
        Objects.requireNonNull(value, "accountId");
        if (value.length() != PicClause.XREF_ACCT_ID_WIDTH) {
            throw new IllegalArgumentException("accountId holds " + PicClause.XREF_ACCT_ID_WIDTH
                    + " characters; the value supplied holds " + value.length());
        }
        for (int position = 0; position < value.length(); position++) {
            char character = value.charAt(position);
            if (character < '0' || character > '9') {
                throw new IllegalArgumentException("accountId holds digits only; the value supplied"
                        + " carries something else at position " + position);
            }
        }
        return value;
    }

    /**
     * Checks one count against the range an {@code INTEGER} count column holds.
     *
     * @param value candidate count
     * @return {@code value}
     * @throws IllegalArgumentException if {@code value} is negative
     */
    private static int requireAuthorizationCount(int value) {
        if (value < 0) {
            throw new IllegalArgumentException(
                    "authorizationCount counts up from zero; the value supplied is " + value);
        }
        return value;
    }

    /**
     * Checks one total against the scale and the precision of {@code NUMERIC(11,2)}.
     *
     * @param value candidate total
     * @return {@code value}
     * @throws NullPointerException     if {@code value} is null
     * @throws IllegalArgumentException if the scale differs from two, or the digit count exceeds
     *                                  eleven. The failure text names the scale or the digit count,
     *                                  never the total
     */
    private static BigDecimal requireTotalAmount(BigDecimal value) {
        Objects.requireNonNull(value, "totalAmount");
        if (value.scale() != PicClause.TRAN_AMT_SCALE) {
            throw new IllegalArgumentException("totalAmount holds " + PicClause.TRAN_AMT_SCALE
                    + " digits after the decimal point; the value supplied holds " + value.scale());
        }
        if (value.precision() > PicClause.TRAN_AMT_PRECISION) {
            throw new IllegalArgumentException("totalAmount holds at most "
                    + PicClause.TRAN_AMT_PRECISION + " digits; the value supplied holds "
                    + value.precision());
        }
        return value;
    }

    /**
     * Returns the account the bucket belongs to.
     *
     * @return eleven digits, leading zeros kept
     */
    public String getAccountId() {
        return accountId;
    }

    /**
     * Returns the inclusive lower bound of the bucket.
     *
     * @return the bucket start
     */
    public Instant getWindowStart() {
        return windowStart;
    }

    /**
     * Returns the authorizations counted in the bucket.
     *
     * @return zero or more
     */
    public int getAuthorizationCount() {
        return authorizationCount;
    }

    /**
     * Returns the amounts totalled over the bucket.
     *
     * @return a total at two digits after the decimal point, positive or negative
     */
    public BigDecimal getTotalAmount() {
        return totalAmount;
    }

    /**
     * Returns the time of the last change to the row.
     *
     * @return the time the caller supplied
     */
    public Instant getUpdatedAt() {
        return updatedAt;
    }

    /**
     * Replaces the count.
     *
     * @param authorizationCount the new count, zero or more
     * @throws IllegalArgumentException if the count is negative
     */
    public void setAuthorizationCount(int authorizationCount) {
        this.authorizationCount = requireAuthorizationCount(authorizationCount);
    }

    /**
     * Replaces the total. The caller totals amounts through
     * {@code com.carddemo.cobol.CobolDecimal} and passes the result here.
     *
     * @param totalAmount the new total, at two digits after the decimal point
     * @throws NullPointerException     if {@code totalAmount} is null
     * @throws IllegalArgumentException if the scale differs from two, or the digit count exceeds
     *                                  eleven
     */
    public void setTotalAmount(BigDecimal totalAmount) {
        this.totalAmount = requireTotalAmount(totalAmount);
    }

    /**
     * Replaces the time of the last change.
     *
     * @param updatedAt the time of this change
     * @throws NullPointerException if {@code updatedAt} is null
     */
    public void setUpdatedAt(Instant updatedAt) {
        this.updatedAt = Objects.requireNonNull(updatedAt, "updatedAt");
    }

    /**
     * Compares two rows on the two key columns, and on no other column.
     *
     * @param other candidate for comparison
     * @return {@code true} when {@code other} is a bucket carrying an equal key
     */
    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof VelocityWindowEntity that)) {
            return false;
        }
        return Objects.equals(accountId, that.accountId)
                && Objects.equals(windowStart, that.windowStart);
    }

    /**
     * Hashes the two key columns, and no other column.
     *
     * @return the hash of {@code account_id} and {@code window_start}
     */
    @Override
    public int hashCode() {
        return Objects.hash(accountId, windowStart);
    }

    /**
     * Renders the key and the three counters.
     *
     * @return one line naming the class and each of the five columns
     */
    @Override
    public String toString() {
        return "VelocityWindowEntity[accountId=" + accountId
                + ", windowStart=" + windowStart
                + ", authorizationCount=" + authorizationCount
                + ", totalAmount=" + totalAmount
                + ", updatedAt=" + updatedAt + "]";
    }

    /**
     * The two-part key of {@code velocity_window}, in the column order
     * {@code (account_id, window_start)}. The Spring Data interface in the sibling
     * {@code repository} package names this class as its identifier type.
     *
     * <p>Both fields match the entity fields of the same names at the same two types. The entity
     * carries the column mapping, and this class carries none.</p>
     */
    public static class VelocityWindowId implements Serializable {

        private static final long serialVersionUID = 1L;

        /** First part of the key, eleven digits. */
        private String accountId;

        /** Second part of the key, the inclusive lower bound of the bucket. */
        private Instant windowStart;

        /** Required by the persistence provider, which sets both fields directly. */
        public VelocityWindowId() {
        }

        /**
         * Builds one key from its two parts.
         *
         * @param accountId   the account the bucket belongs to, eleven digits
         * @param windowStart inclusive lower bound of the bucket
         * @throws NullPointerException     if either argument is null
         * @throws IllegalArgumentException if the identifier is not eleven digits
         */
        public VelocityWindowId(String accountId, Instant windowStart) {
            this.accountId = requireAccountId(accountId);
            this.windowStart = Objects.requireNonNull(windowStart, "windowStart");
        }

        /**
         * Returns the account the bucket belongs to.
         *
         * @return eleven digits, leading zeros kept
         */
        public String getAccountId() {
            return accountId;
        }

        /**
         * Returns the inclusive lower bound of the bucket.
         *
         * @return the bucket start
         */
        public Instant getWindowStart() {
            return windowStart;
        }

        /**
         * Compares two keys on both parts.
         *
         * @param other candidate for comparison
         * @return {@code true} when {@code other} is a key carrying both equal parts
         */
        @Override
        public boolean equals(Object other) {
            if (this == other) {
                return true;
            }
            if (!(other instanceof VelocityWindowId that)) {
                return false;
            }
            return Objects.equals(accountId, that.accountId)
                    && Objects.equals(windowStart, that.windowStart);
        }

        /**
         * Hashes both parts.
         *
         * @return the hash of {@code account_id} and {@code window_start}
         */
        @Override
        public int hashCode() {
            return Objects.hash(accountId, windowStart);
        }

        /**
         * Renders both parts.
         *
         * @return one line naming the class and both key columns
         */
        @Override
        public String toString() {
            return "VelocityWindowId[accountId=" + accountId
                    + ", windowStart=" + windowStart + "]";
        }
    }
}
