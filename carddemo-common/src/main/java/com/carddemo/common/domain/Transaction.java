package com.carddemo.common.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.util.Objects;

/**
 * JPA entity for a posted transaction.
 *
 * :purpose: Maps the legacy COBOL ``TRAN-RECORD`` layout (copybook ``CVTRA05Y``,
 *     fixed record length 350 bytes) onto the relational ``transactions`` table.
 * :output: A persistent transaction row keyed by the 16-character transaction id.
 *     The monetary ``tran_amt`` column is declared ``NUMERIC(11,2)`` and backed by
 *     {@link BigDecimal} so that the COBOL ``S9(09)V99`` packed-decimal scale is
 *     preserved exactly; timestamp fields retain their 26-character wire format as
 *     {@link String} values, and the card number is a scalar foreign-key column
 *     rather than a mapped association.
 */
@Entity
@Table(name = "transactions")
public class Transaction {

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

    public String getTranId() {
        return tranId;
    }

    public void setTranId(String tranId) {
        this.tranId = tranId;
    }

    public String getTranTypeCd() {
        return tranTypeCd;
    }

    public void setTranTypeCd(String tranTypeCd) {
        this.tranTypeCd = tranTypeCd;
    }

    public Integer getTranCatCd() {
        return tranCatCd;
    }

    public void setTranCatCd(Integer tranCatCd) {
        this.tranCatCd = tranCatCd;
    }

    public String getTranSource() {
        return tranSource;
    }

    public void setTranSource(String tranSource) {
        this.tranSource = tranSource;
    }

    public String getTranDesc() {
        return tranDesc;
    }

    public void setTranDesc(String tranDesc) {
        this.tranDesc = tranDesc;
    }

    public BigDecimal getTranAmt() {
        return tranAmt;
    }

    public void setTranAmt(BigDecimal tranAmt) {
        this.tranAmt = tranAmt;
    }

    public Long getTranMerchantId() {
        return tranMerchantId;
    }

    public void setTranMerchantId(Long tranMerchantId) {
        this.tranMerchantId = tranMerchantId;
    }

    public String getTranMerchantName() {
        return tranMerchantName;
    }

    public void setTranMerchantName(String tranMerchantName) {
        this.tranMerchantName = tranMerchantName;
    }

    public String getTranMerchantCity() {
        return tranMerchantCity;
    }

    public void setTranMerchantCity(String tranMerchantCity) {
        this.tranMerchantCity = tranMerchantCity;
    }

    public String getTranMerchantZip() {
        return tranMerchantZip;
    }

    public void setTranMerchantZip(String tranMerchantZip) {
        this.tranMerchantZip = tranMerchantZip;
    }

    public String getTranCardNum() {
        return tranCardNum;
    }

    public void setTranCardNum(String tranCardNum) {
        this.tranCardNum = tranCardNum;
    }

    public String getTranOrigTs() {
        return tranOrigTs;
    }

    public void setTranOrigTs(String tranOrigTs) {
        this.tranOrigTs = tranOrigTs;
    }

    public String getTranProcTs() {
        return tranProcTs;
    }

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
        if (o == null || getClass() != o.getClass()) {
            return false;
        }
        Transaction that = (Transaction) o;
        return Objects.equals(tranId, that.tranId);
    }

    /**
     * Computes the identity hash for this transaction.
     *
     * :purpose: Derives the hash code from the ``tran_id`` primary key so it is
     *     consistent with {@link #equals(Object)}.
     * :output: the hash code of {@code tranId}.
     */
    @Override
    public int hashCode() {
        return Objects.hash(tranId);
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

}
