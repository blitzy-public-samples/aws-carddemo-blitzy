package com.carddemo.ledger.entity;

import com.carddemo.cobol.PicClause;
import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.io.Serializable;
import java.math.BigDecimal;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * One row of {@code transaction_category_balance}, the running balance one account holds for one
 * transaction type and category.
 *
 * <p>The row maps the fifty-byte {@code TRAN-CAT-BAL-RECORD} at
 * {@code app/cpy/CVTRA01Y.cpy:L4-L10}. Four fields get a column. The trailing
 * {@code FILLER PIC X(22)} at {@code app/cpy/CVTRA01Y.cpy:L10} gets none.</p>
 *
 * <pre>
 * TRANCAT-ACCT-ID   PIC 9(11)      L6    account_id        VARCHAR(11)
 * TRANCAT-TYPE-CD   PIC X(02)      L7    type_code         CHAR(2)
 * TRANCAT-CD        PIC 9(04)      L8    category_code     VARCHAR(4)
 * TRAN-CAT-BAL      PIC S9(09)V99  L9    category_balance  NUMERIC(11,2)
 * </pre>
 *
 * <p>{@link TransactionCategoryBalanceId} holds the first three columns. Their widths sum to the
 * seventeen bytes {@code app/jcl/TCATBALF.jcl:L40} states as {@code KEYS(17 0)}, and the three
 * together are the primary key.</p>
 *
 * <p>The balance carries eleven digits, two of them after the decimal point, and a negative
 * balance is ordinary traffic. This class stores that value and computes nothing:
 * {@code app/cbl/CBTRN02C.cbl:L508} and {@code app/cbl/CBTRN02C.cbl:L527} add a transaction amount
 * to it, and {@code CategoryBalanceUpdater} in the {@code domain} package owns that arithmetic.</p>
 *
 * <p>Rationale for this mapping: {@code card-platform/docs/decision-log.md}.</p>
 */
@Entity
@Table(name = "transaction_category_balance")
public class TransactionCategoryBalanceEntity {

    /** The seventeen-byte composite key, held as three columns. */
    @EmbeddedId
    private TransactionCategoryBalanceId id;

    /**
     * Running balance for that key, from {@code TRAN-CAT-BAL PIC S9(09)V99} at
     * {@code app/cpy/CVTRA01Y.cpy:L9}.
     */
    @Column(name = "category_balance", nullable = false,
            precision = PicClause.TRAN_CAT_BAL_PRECISION, scale = PicClause.TRAN_CAT_BAL_SCALE)
    private BigDecimal categoryBalance;

    /** Required by the persistence provider, which sets both fields directly. */
    protected TransactionCategoryBalanceEntity() {
    }

    /**
     * Builds one row from its key and its balance.
     *
     * @param id              the three-part key
     * @param categoryBalance the running balance, holding two digits after the decimal point
     * @throws NullPointerException     if either argument is null
     * @throws IllegalArgumentException if the balance carries a scale or a digit count the column
     *                                  does not hold. The failure text names the scale or the
     *                                  digit count, never the balance
     */
    public TransactionCategoryBalanceEntity(TransactionCategoryBalanceId id,
            BigDecimal categoryBalance) {
        this.id = Objects.requireNonNull(id, "id");
        Objects.requireNonNull(categoryBalance, "categoryBalance");
        if (categoryBalance.scale() != PicClause.TRAN_CAT_BAL_SCALE) {
            throw new IllegalArgumentException("categoryBalance holds "
                    + PicClause.TRAN_CAT_BAL_SCALE
                    + " digits after the decimal point; the value supplied holds "
                    + categoryBalance.scale());
        }
        if (categoryBalance.precision() > PicClause.TRAN_CAT_BAL_PRECISION) {
            throw new IllegalArgumentException("categoryBalance holds at most "
                    + PicClause.TRAN_CAT_BAL_PRECISION + " digits; the value supplied holds "
                    + categoryBalance.precision());
        }
        this.categoryBalance = categoryBalance;
    }

    /**
     * Returns the three-part key.
     *
     * @return the key
     */
    public TransactionCategoryBalanceId getId() {
        return id;
    }

    /**
     * Returns the running balance.
     *
     * @return the balance, at two digits after the decimal point
     */
    public BigDecimal getCategoryBalance() {
        return categoryBalance;
    }

    /**
     * Compares two rows by key.
     *
     * @param other the object to compare with
     * @return true when {@code other} is a row carrying an equal key
     */
    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof TransactionCategoryBalanceEntity that)) {
            return false;
        }
        return Objects.equals(this.id, that.id);
    }

    /**
     * Hashes the key.
     *
     * @return the hash of the key
     */
    @Override
    public int hashCode() {
        return Objects.hashCode(id);
    }

    /**
     * The three-part key, declared as the COBOL (Common Business Oriented Language) group
     * {@code 05 TRAN-CAT-KEY.} at {@code app/cpy/CVTRA01Y.cpy:L5}.
     *
     * <p>Its parts are {@code TRANCAT-ACCT-ID} at L6, {@code TRANCAT-TYPE-CD} at L7 and
     * {@code TRANCAT-CD} at L8. Each part keeps the zero padding its source record carries, so
     * {@code 00000000001}, {@code 01} and {@code 0001} survive a round trip.</p>
     */
    @Embeddable
    public static class TransactionCategoryBalanceId implements Serializable {

        private static final long serialVersionUID = 1L;

        /** Eleven digits, the shape of {@code TRANCAT-ACCT-ID PIC 9(11)}. */
        private static final Pattern ACCOUNT_ID =
                Pattern.compile("^[0-9]{" + PicClause.TRANCAT_ACCT_ID_WIDTH + "}$");

        /** Four digits, the shape of {@code TRANCAT-CD PIC 9(04)}. */
        private static final Pattern CATEGORY_CODE =
                Pattern.compile("^[0-9]{" + PicClause.TRANCAT_CD_WIDTH + "}$");

        /**
         * First part of the key, from {@code TRANCAT-ACCT-ID PIC 9(11)} at
         * {@code app/cpy/CVTRA01Y.cpy:L6}.
         */
        @Column(name = "account_id", nullable = false, length = PicClause.TRANCAT_ACCT_ID_WIDTH)
        private String accountId;

        /**
         * Second part of the key, from {@code TRANCAT-TYPE-CD PIC X(02)} at
         * {@code app/cpy/CVTRA01Y.cpy:L7}. The column type is {@code character(2)}, which
         * PostgreSQL also names {@code bpchar}, and it pads a shorter value with spaces.
         */
        @Column(name = "type_code", nullable = false, length = PicClause.TRANCAT_TYPE_CD_WIDTH,
                columnDefinition = "bpchar(" + PicClause.TRANCAT_TYPE_CD_WIDTH + ")")
        private String typeCode;

        /**
         * Third part of the key, from {@code TRANCAT-CD PIC 9(04)} at
         * {@code app/cpy/CVTRA01Y.cpy:L8}.
         */
        @Column(name = "category_code", nullable = false, length = PicClause.TRANCAT_CD_WIDTH)
        private String categoryCode;

        /** Required by the persistence provider, which sets all three fields directly. */
        protected TransactionCategoryBalanceId() {
        }

        /**
         * Builds one key from its three parts.
         *
         * @param accountId    eleven digits
         * @param typeCode     two characters
         * @param categoryCode four digits
         * @throws NullPointerException     if any argument is null
         * @throws IllegalArgumentException if a part is not the shape its source field declares.
         *                                  The failure text names the part, its shape and the
         *                                  length received, never the value
         */
        public TransactionCategoryBalanceId(String accountId, String typeCode,
                String categoryCode) {
            Objects.requireNonNull(accountId, "accountId");
            Objects.requireNonNull(typeCode, "typeCode");
            Objects.requireNonNull(categoryCode, "categoryCode");
            requireShape("accountId", accountId, ACCOUNT_ID);
            requireShape("categoryCode", categoryCode, CATEGORY_CODE);
            if (typeCode.length() != PicClause.TRANCAT_TYPE_CD_WIDTH) {
                throw new IllegalArgumentException("typeCode holds "
                        + PicClause.TRANCAT_TYPE_CD_WIDTH
                        + " characters; the value supplied holds " + typeCode.length());
            }
            this.accountId = accountId;
            this.typeCode = typeCode;
            this.categoryCode = categoryCode;
        }

        /**
         * Checks one part against its shape.
         *
         * @param field the part name the failure text carries
         * @param value the part
         * @param shape the shape the part matches
         * @throws IllegalArgumentException if {@code value} does not match {@code shape}
         */
        private static void requireShape(String field, String value, Pattern shape) {
            if (!shape.matcher(value).matches()) {
                throw new IllegalArgumentException(field + " matches " + shape.pattern()
                        + "; the value supplied holds " + value.length() + " characters");
            }
        }

        /**
         * Returns the account identifier.
         *
         * @return eleven digits, leading zeros kept
         */
        public String getAccountId() {
            return accountId;
        }

        /**
         * Returns the transaction type code.
         *
         * @return two characters
         */
        public String getTypeCode() {
            return typeCode;
        }

        /**
         * Returns the transaction category code.
         *
         * @return four digits, leading zeros kept
         */
        public String getCategoryCode() {
            return categoryCode;
        }

        /**
         * Compares two keys part by part.
         *
         * @param other the object to compare with
         * @return true when {@code other} is a key carrying all three equal parts
         */
        @Override
        public boolean equals(Object other) {
            if (this == other) {
                return true;
            }
            if (!(other instanceof TransactionCategoryBalanceId that)) {
                return false;
            }
            return Objects.equals(this.accountId, that.accountId)
                    && Objects.equals(this.typeCode, that.typeCode)
                    && Objects.equals(this.categoryCode, that.categoryCode);
        }

        /**
         * Hashes all three parts.
         *
         * @return the hash of the three parts
         */
        @Override
        public int hashCode() {
            return Objects.hash(accountId, typeCode, categoryCode);
        }
    }
}
