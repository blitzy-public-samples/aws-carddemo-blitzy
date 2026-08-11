package com.carddemo.common.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PostLoad;
import jakarta.persistence.PostPersist;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import org.springframework.data.domain.Persistable;

import java.math.BigDecimal;

/**
 * JPA entity for a posted transaction.
 * :purpose: Maps the legacy COBOL ``TRAN-RECORD`` layout (copybook ``CVTRA05Y``, fixed
 *     record length 350 bytes) onto the relational ``transactions`` table.
 * :output: A persistent transaction row keyed by the 16-character transaction id. The
 *     monetary ``tran_amt`` column is declared ``NUMERIC(11,2)`` and backed by {@link
 *     BigDecimal} so that the COBOL ``S9(09)V99`` packed-decimal scale is preserved exactly;
 *     timestamp fields retain their 26-character wire format as {@link String} values, and the
 *     card number is a scalar foreign-key column rather than a mapped association.
 * :note: The entity implements {@link Persistable} because ``tran_id`` is an
 *     APPLICATION-ASSIGNED key drawn from the ``transaction_id_seq`` database sequence rather
 *     than a generated identity. Without it Spring Data's default ``isNew`` rule (id is null)
 *     reports every instance as already persisted, so ``save`` performs a ``merge`` that turns
 *     an id collision into a silent UPDATE over an existing financial record instead of the
 *     duplicate-key rejection the legacy ``WRITE`` produced (``COTRN02C``
 *     ``DUPKEY``/``DUPREC``, ``COBIL00C``).
 */
@Entity
@Table(name = "transactions")
public class Transaction implements Persistable<String> {

    /** ``TRAN-ID`` PIC X(16) — 16-character transaction identifier (primary key). */
    @Id
    @Column(name = "tran_id", length = 16, nullable = false)
    private String tranId;

    /** ``TRAN-TYPE-CD`` PIC X(02) — transaction type code (relates to tran_type). */
    @Column(name = "tran_type_cd", length = 2)
    private String tranTypeCd;

    /** ``TRAN-CAT-CD`` PIC 9(04) — transaction category code. */
    @Column(name = "tran_cat_cd")
    private Integer tranCatCd;

    /** ``TRAN-SOURCE`` PIC X(10) — origination source of the transaction. */
    @Column(name = "tran_source", length = 10)
    private String tranSource;

    /** ``TRAN-DESC`` PIC X(100) — free-text transaction description. */
    @Column(name = "tran_desc", length = 100)
    private String tranDesc;

    /** ``TRAN-AMT`` PIC S9(09)V99 — signed monetary amount as NUMERIC(11,2). */
    @Column(name = "tran_amt", precision = 11, scale = 2)
    private BigDecimal tranAmt;

    /** ``TRAN-MERCHANT-ID`` PIC 9(09) — merchant identifier. */
    @Column(name = "tran_merchant_id")
    private Long tranMerchantId;

    /** ``TRAN-MERCHANT-NAME`` PIC X(50) — merchant name. */
    @Column(name = "tran_merchant_name", length = 50)
    private String tranMerchantName;

    /** ``TRAN-MERCHANT-CITY`` PIC X(50) — merchant city. */
    @Column(name = "tran_merchant_city", length = 50)
    private String tranMerchantCity;

    /** ``TRAN-MERCHANT-ZIP`` PIC X(10) — merchant postal code. */
    @Column(name = "tran_merchant_zip", length = 10)
    private String tranMerchantZip;

    /** ``TRAN-CARD-NUM`` PIC X(16) — card number; scalar FK to cards.card_num. */
    @Column(name = "tran_card_num", length = 16)
    private String tranCardNum;

    /** ``TRAN-ORIG-TS`` PIC X(26) — origination timestamp (YYYY-MM-DD-HH.MM.SS.mmmmmm). */
    @Column(name = "tran_orig_ts", length = 26)
    private String tranOrigTs;

    /** ``TRAN-PROC-TS`` PIC X(26) — processing timestamp (YYYY-MM-DD-HH.MM.SS.mmmmmm). */
    @Column(name = "tran_proc_ts", length = 26)
    private String tranProcTs;

    /**
     * Creates an empty transaction instance.
     *
     * :purpose: Required no-argument constructor used by the JPA provider to
     *     materialize entity instances from persistent state.
     */
    public Transaction() {
    }

    /**
     * Return the transaction identifier.
     *
     * :output: the 16-character ``tran_id`` primary key.
     */
    public String getTranId() {
        return tranId;
    }

    /**
     * Set the transaction identifier.
     *
     * :param tranId: the 16-character ``tran_id`` primary key.
     */
    public void setTranId(String tranId) {
        this.tranId = tranId;
    }

    /**
     * Return the transaction type code.
     *
     * :output: the two-character ``tran_type_cd`` value.
     */
    public String getTranTypeCd() {
        return tranTypeCd;
    }

    /**
     * Set the transaction type code.
     *
     * :param tranTypeCd: the two-character ``tran_type_cd`` value.
     */
    public void setTranTypeCd(String tranTypeCd) {
        this.tranTypeCd = tranTypeCd;
    }

    /**
     * Return the transaction category code.
     *
     * :output: the four-digit ``tran_cat_cd`` value.
     */
    public Integer getTranCatCd() {
        return tranCatCd;
    }

    /**
     * Set the transaction category code.
     *
     * :param tranCatCd: the four-digit ``tran_cat_cd`` value.
     */
    public void setTranCatCd(Integer tranCatCd) {
        this.tranCatCd = tranCatCd;
    }

    /**
     * Return the transaction origination source.
     *
     * :output: the ``tran_source`` value.
     */
    public String getTranSource() {
        return tranSource;
    }

    /**
     * Set the transaction origination source.
     *
     * :param tranSource: the ``tran_source`` value.
     */
    public void setTranSource(String tranSource) {
        this.tranSource = tranSource;
    }

    /**
     * Return the free-text transaction description.
     *
     * :output: the ``tran_desc`` value.
     */
    public String getTranDesc() {
        return tranDesc;
    }

    /**
     * Set the free-text transaction description.
     *
     * :param tranDesc: the ``tran_desc`` value.
     */
    public void setTranDesc(String tranDesc) {
        this.tranDesc = tranDesc;
    }

    /**
     * Return the monetary transaction amount.
     *
     * :output: the ``tran_amt`` value as a scale-2 {@link BigDecimal}.
     */
    public BigDecimal getTranAmt() {
        return tranAmt;
    }

    /**
     * Set the monetary transaction amount.
     *
     * :param tranAmt: the ``tran_amt`` value as a scale-2 {@link BigDecimal}.
     */
    public void setTranAmt(BigDecimal tranAmt) {
        this.tranAmt = tranAmt;
    }

    /**
     * Return the merchant identifier.
     *
     * :output: the ``tran_merchant_id`` value.
     */
    public Long getTranMerchantId() {
        return tranMerchantId;
    }

    /**
     * Set the merchant identifier.
     *
     * :param tranMerchantId: the ``tran_merchant_id`` value.
     */
    public void setTranMerchantId(Long tranMerchantId) {
        this.tranMerchantId = tranMerchantId;
    }

    /**
     * Return the merchant name.
     *
     * :output: the ``tran_merchant_name`` value.
     */
    public String getTranMerchantName() {
        return tranMerchantName;
    }

    /**
     * Set the merchant name.
     *
     * :param tranMerchantName: the ``tran_merchant_name`` value.
     */
    public void setTranMerchantName(String tranMerchantName) {
        this.tranMerchantName = tranMerchantName;
    }

    /**
     * Return the merchant city.
     *
     * :output: the ``tran_merchant_city`` value.
     */
    public String getTranMerchantCity() {
        return tranMerchantCity;
    }

    /**
     * Set the merchant city.
     *
     * :param tranMerchantCity: the ``tran_merchant_city`` value.
     */
    public void setTranMerchantCity(String tranMerchantCity) {
        this.tranMerchantCity = tranMerchantCity;
    }

    /**
     * Return the merchant postal code.
     *
     * :output: the ``tran_merchant_zip`` value.
     */
    public String getTranMerchantZip() {
        return tranMerchantZip;
    }

    /**
     * Set the merchant postal code.
     *
     * :param tranMerchantZip: the ``tran_merchant_zip`` value.
     */
    public void setTranMerchantZip(String tranMerchantZip) {
        this.tranMerchantZip = tranMerchantZip;
    }

    /**
     * Return the card number associated with this transaction.
     *
     * :output: the ``tran_card_num`` scalar foreign-key value.
     */
    public String getTranCardNum() {
        return tranCardNum;
    }

    /**
     * Set the card number associated with this transaction.
     *
     * :param tranCardNum: the ``tran_card_num`` scalar foreign-key value.
     */
    public void setTranCardNum(String tranCardNum) {
        this.tranCardNum = tranCardNum;
    }

    /**
     * Return the origination timestamp.
     *
     * :output: the 26-character ``tran_orig_ts`` value (YYYY-MM-DD-HH.MM.SS.mmmmmm).
     */
    public String getTranOrigTs() {
        return tranOrigTs;
    }

    /**
     * Set the origination timestamp.
     *
     * :param tranOrigTs: the 26-character ``tran_orig_ts`` value (YYYY-MM-DD-HH.MM.SS.mmmmmm).
     */
    public void setTranOrigTs(String tranOrigTs) {
        this.tranOrigTs = tranOrigTs;
    }

    /**
     * Return the processing timestamp.
     *
     * :output: the 26-character ``tran_proc_ts`` value (YYYY-MM-DD-HH.MM.SS.mmmmmm).
     */
    public String getTranProcTs() {
        return tranProcTs;
    }

    /**
     * Set the processing timestamp.
     *
     * :param tranProcTs: the 26-character ``tran_proc_ts`` value (YYYY-MM-DD-HH.MM.SS.mmmmmm).
     */
    public void setTranProcTs(String tranProcTs) {
        this.tranProcTs = tranProcTs;
    }

    /**
     * Compares two transactions by their persistent identity.
     *
     * :purpose: Establishes entity equality on the ``tran_id`` primary key.
     * :param o: the object to compare with this transaction.
     * :output: ``true`` when the other object is a transaction with an equal
     *     {@code tranId}; ``false`` otherwise.
     */
    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof Transaction other)) {
            return false;
        }
        return tranId != null && tranId.equals(other.getTranId());
    }

    /**
     * Computes the identity hash for this transaction.
     *
     * :purpose: Returns a proxy-stable constant hash derived from the entity class so
     *     the value never changes across the entity lifecycle (including before the
     *     primary key is assigned), consistent with {@link #equals(Object)}.
     * :output: a constant hash code for the {@code Transaction} type.
     */
    @Override
    public int hashCode() {
        return Transaction.class.hashCode();
    }

    /**
     * Renders a non-sensitive summary of this transaction.
     *
     * :purpose: Provides a diagnostic representation for logging.
     * :output: a string containing non-PII summary fields; the card number and
     *     free-text description are intentionally omitted.
     */
    @Override
    public String toString() {
        return "Transaction{"
                + "tranId='" + tranId + '\''
                + ", tranTypeCd='" + tranTypeCd + '\''
                + ", tranCatCd=" + tranCatCd
                + ", tranSource='" + tranSource + '\''
                + ", tranAmt=" + tranAmt
                + ", tranMerchantId=" + tranMerchantId
                + ", tranOrigTs='" + tranOrigTs + '\''
                + ", tranProcTs='" + tranProcTs + '\''
                + '}';
    }

    /**
     * :purpose: Tracks whether this instance represents a row that already exists in
     *     the ``transactions`` table. It is ``false`` for an instance built in Java
     *     (a new posting) and flipped to ``true`` once the row has been inserted or
     *     loaded, so JPA never converts an insert into an update.
     */
    @Transient
    private boolean persisted;

    /**
     * :purpose: Return the entity identifier required by {@link Persistable}.
     * :output: the 16-character ``TRAN-ID`` primary key.
     */
    @Override
    public String getId() {
        return tranId;
    }

    /**
     * :purpose: Report whether this instance must be INSERTed rather than merged.
     *     A ``true`` result makes Spring Data call ``EntityManager.persist``, so an
     *     id already present in the table raises a duplicate-key violation instead
     *     of silently overwriting the existing transaction.
     * :output: ``true`` until the row has been inserted or loaded from the database.
     */
    @Override
    public boolean isNew() {
        return !persisted;
    }

    /**
     * :purpose: Mark the instance as representing an existing row after it has been
     *     loaded from or inserted into the database, so a subsequent ``save`` on the
     *     same instance updates rather than attempting a second insert.
     */
    @PostLoad
    @PostPersist
    void markPersisted() {
        this.persisted = true;
    }

}
