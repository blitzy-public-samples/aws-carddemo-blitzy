package com.carddemo.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.io.Serializable;
import java.math.BigDecimal;
import java.util.Objects;

/**
 * JPA entity mapping the legacy VSAM {@code TCATBALF} key-sequenced dataset to the
 * relational table {@code transaction_category_balance}.
 *
 * <p>This entity is the Java/Spring re-expression of the COBOL copybook
 * {@code app/cpy/CVTRA01Y.cpy} ({@code TRAN-CAT-BAL-RECORD}, fixed record length 50,
 * VSAM {@code KEYLEN=17}). It holds the <strong>per-category running balance for a single
 * account</strong> &mdash; the granular balance bucket keyed by
 * {@code (account, transaction type, transaction category)} on which the interest and
 * posting business logic operate.</p>
 *
 * <h2>COBOL source-of-truth mapping (CVTRA01Y.cpy &rarr; columns)</h2>
 * <pre>
 *   01  TRAN-CAT-BAL-RECORD.                              transaction_category_balance
 *       05  TRAN-CAT-KEY.                                 (composite primary key)
 *          10 TRANCAT-ACCT-ID  PIC 9(11).   acctId  -&gt;   acct_id      BIGINT
 *          10 TRANCAT-TYPE-CD  PIC X(02).   typeCd  -&gt;   type_cd      CHAR(2)
 *          10 TRANCAT-CD       PIC 9(04).   catCd   -&gt;   cat_cd       INTEGER
 *       05  TRAN-CAT-BAL       PIC S9(09)V99.  tranCatBal -&gt; tran_cat_bal NUMERIC(12,2) NOT NULL
 *       05  FILLER             PIC X(22).    (NOT persisted &mdash; intentionally omitted)
 * </pre>
 *
 * <h2>Schema contract (binding)</h2>
 * Hibernate boots with {@code spring.jpa.hibernate.ddl-auto=validate}; the column names,
 * SQL types, and nullability below match {@code src/main/resources/db/migration/V1__schema.sql}
 * exactly:
 * <pre>
 *   CREATE TABLE transaction_category_balance (
 *       acct_id      BIGINT        NOT NULL,
 *       type_cd      CHAR(2)       NOT NULL,
 *       cat_cd       INTEGER       NOT NULL,
 *       tran_cat_bal NUMERIC(12,2) NOT NULL DEFAULT 0,
 *       CONSTRAINT pk_tran_cat_bal PRIMARY KEY (acct_id, type_cd, cat_cd),
 *       CONSTRAINT fk_tcb_acct FOREIGN KEY (acct_id) REFERENCES accounts (acct_id),
 *       CONSTRAINT fk_tcb_cat  FOREIGN KEY (type_cd, cat_cd)
 *                              REFERENCES transaction_category (type_cd, cat_cd)
 *   );
 * </pre>
 *
 * <h2>Business usage</h2>
 * <ul>
 *   <li><strong>Interest calculation</strong> ({@code app/cbl/CBACT04C.cbl}; AAP &sect;0.6.3):
 *       the interest job iterates every {@code TCATBAL} row for an account, resolves the
 *       disclosure-group interest rate, and computes
 *       {@code monthlyInterest = tranCatBal * rate / 1200} (with {@code RoundingMode.HALF_UP}
 *       applied in the service/batch layer, not here).</li>
 *   <li><strong>Daily posting</strong> ({@code app/cbl/CBTRN02C.cbl}; AAP &sect;0.6.1): the
 *       posting job reads the single {@code (acct_id, type_cd, cat_cd)} row and either inserts
 *       it (when absent) or adds the transaction amount to the existing balance.</li>
 * </ul>
 *
 * <h2>Repository contract</h2>
 * Persisted via
 * {@code JpaRepository<TransactionCategoryBalance, TransactionCategoryBalance.TransactionCategoryBalanceId>}.
 *
 * <h2>Design notes</h2>
 * <ul>
 *   <li>The two foreign keys ({@code fk_tcb_acct}, {@code fk_tcb_cat}) are enforced at the
 *       database level only; no JPA {@code @ManyToOne}/{@code @OneToMany} associations are
 *       modelled (flat design, per AAP &sect;0.3.2).</li>
 *   <li>The composite key is a natural business key, so there is no {@code @GeneratedValue}.</li>
 *   <li>All monetary arithmetic uses {@link BigDecimal} to reproduce COBOL fixed-point
 *       {@code PIC S9(09)V99} semantics exactly.</li>
 * </ul>
 *
 * @see com.carddemo.entity.TransactionCategoryBalance.TransactionCategoryBalanceId
 */
@Entity
@Table(name = "transaction_category_balance")
public class TransactionCategoryBalance {

    /**
     * Composite primary key {@code (acct_id, type_cd, cat_cd)} mirroring the COBOL
     * {@code TRAN-CAT-KEY} group (VSAM key length 17 = 11 + 2 + 4).
     */
    @EmbeddedId
    private TransactionCategoryBalanceId id;

    /**
     * Per-category running balance &mdash; COBOL {@code TRAN-CAT-BAL PIC S9(09)V99}.
     * Persisted as {@code NUMERIC(12,2)} (a safe superset of the 9-integer/2-fraction source
     * field) and never null; initialised to {@link BigDecimal#ZERO} to mirror the
     * {@code DEFAULT 0} of the column for newly inserted rows (e.g. the posting
     * insert-if-absent path).
     */
    @Column(name = "tran_cat_bal", precision = 12, scale = 2, nullable = false)
    private BigDecimal tranCatBal = BigDecimal.ZERO;

    /**
     * Protected/public no-argument constructor required by the JPA specification so that
     * Hibernate can instantiate the entity via reflection. Public visibility allows the
     * service and batch layers (in other packages) to construct an instance and populate it
     * through setters.
     */
    public TransactionCategoryBalance() {
        // no-arg constructor for JPA
    }

    /**
     * Convenience constructor building a balance row from a fully-formed composite key.
     *
     * @param id         the composite primary key; must not be {@code null} at persist time
     * @param tranCatBal the running balance; a {@code null} value is coerced to
     *                   {@link BigDecimal#ZERO} to honour the {@code NOT NULL DEFAULT 0} contract
     */
    public TransactionCategoryBalance(TransactionCategoryBalanceId id, BigDecimal tranCatBal) {
        this.id = id;
        this.tranCatBal = (tranCatBal != null) ? tranCatBal : BigDecimal.ZERO;
    }

    /**
     * Convenience constructor building both the composite key and the balance from primitive
     * key parts. Useful for the posting job's insert-if-absent path and for test fixtures.
     *
     * @param acctId     account identifier ({@code TRANCAT-ACCT-ID})
     * @param typeCd     transaction type code ({@code TRANCAT-TYPE-CD}, 2 chars)
     * @param catCd      transaction category code ({@code TRANCAT-CD})
     * @param tranCatBal the running balance; {@code null} is coerced to {@link BigDecimal#ZERO}
     */
    public TransactionCategoryBalance(Long acctId, String typeCd, Integer catCd, BigDecimal tranCatBal) {
        this(new TransactionCategoryBalanceId(acctId, typeCd, catCd), tranCatBal);
    }

    /**
     * @return the composite primary key
     */
    public TransactionCategoryBalanceId getId() {
        return id;
    }

    /**
     * @param id the composite primary key to set
     */
    public void setId(TransactionCategoryBalanceId id) {
        this.id = id;
    }

    /**
     * @return the per-category running balance
     */
    public BigDecimal getTranCatBal() {
        return tranCatBal;
    }

    /**
     * @param tranCatBal the per-category running balance to set (column is {@code NOT NULL})
     */
    public void setTranCatBal(BigDecimal tranCatBal) {
        this.tranCatBal = tranCatBal;
    }

    /**
     * Entity equality is defined solely by the composite primary key, consistent with JPA
     * identity semantics.
     */
    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof TransactionCategoryBalance)) {
            return false;
        }
        TransactionCategoryBalance that = (TransactionCategoryBalance) o;
        return Objects.equals(id, that.id);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id);
    }

    @Override
    public String toString() {
        return "TransactionCategoryBalance{id=" + id + ", tranCatBal=" + tranCatBal + '}';
    }

    /**
     * Embeddable composite primary key for {@link TransactionCategoryBalance}, mapping the
     * COBOL {@code TRAN-CAT-KEY} group (CVTRA01Y.cpy) &rarr; {@code (acct_id, type_cd, cat_cd)}.
     *
     * <p>{@code type_cd}/{@code cat_cd} additionally form the database-level foreign key into
     * {@code transaction_category}; that relationship is not modelled as a JPA association.</p>
     */
    @Embeddable
    public static class TransactionCategoryBalanceId implements Serializable {

        private static final long serialVersionUID = 1L;

        /** Account identifier &mdash; COBOL {@code TRANCAT-ACCT-ID PIC 9(11)} &rarr; {@code BIGINT}. */
        @Column(name = "acct_id")
        private Long acctId;

        /**
         * Transaction type code &mdash; COBOL {@code TRANCAT-TYPE-CD PIC X(02)} &rarr;
         * {@code CHAR(2)}. The {@link JdbcTypeCode} with {@link SqlTypes#CHAR} forces
         * fixed-length {@code CHAR} binding (rather than {@code VARCHAR}) so values align with
         * the legacy fixed-width semantics and the {@code transaction_category} foreign key.
         */
        @Column(name = "type_cd", length = 2)
        @JdbcTypeCode(SqlTypes.CHAR)
        private String typeCd;

        /** Transaction category code &mdash; COBOL {@code TRANCAT-CD PIC 9(04)} &rarr; {@code INTEGER}. */
        @Column(name = "cat_cd")
        private Integer catCd;

        /**
         * No-argument constructor required by JPA for embeddable composite keys.
         */
        public TransactionCategoryBalanceId() {
            // no-arg constructor for JPA
        }

        /**
         * All-arguments constructor.
         *
         * @param acctId account identifier
         * @param typeCd transaction type code (2 characters)
         * @param catCd  transaction category code
         */
        public TransactionCategoryBalanceId(Long acctId, String typeCd, Integer catCd) {
            this.acctId = acctId;
            this.typeCd = typeCd;
            this.catCd = catCd;
        }

        /**
         * @return the account identifier
         */
        public Long getAcctId() {
            return acctId;
        }

        /**
         * @param acctId the account identifier to set
         */
        public void setAcctId(Long acctId) {
            this.acctId = acctId;
        }

        /**
         * @return the transaction type code
         */
        public String getTypeCd() {
            return typeCd;
        }

        /**
         * @param typeCd the transaction type code to set
         */
        public void setTypeCd(String typeCd) {
            this.typeCd = typeCd;
        }

        /**
         * @return the transaction category code
         */
        public Integer getCatCd() {
            return catCd;
        }

        /**
         * @param catCd the transaction category code to set
         */
        public void setCatCd(Integer catCd) {
            this.catCd = catCd;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) {
                return true;
            }
            if (!(o instanceof TransactionCategoryBalanceId)) {
                return false;
            }
            TransactionCategoryBalanceId that = (TransactionCategoryBalanceId) o;
            return Objects.equals(acctId, that.acctId)
                    && Objects.equals(typeCd, that.typeCd)
                    && Objects.equals(catCd, that.catCd);
        }

        @Override
        public int hashCode() {
            return Objects.hash(acctId, typeCd, catCd);
        }

        @Override
        public String toString() {
            return "TransactionCategoryBalanceId{acctId=" + acctId
                    + ", typeCd='" + typeCd + '\'' + ", catCd=" + catCd + '}';
        }
    }
}
