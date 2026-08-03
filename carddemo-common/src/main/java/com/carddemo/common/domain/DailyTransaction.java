package com.carddemo.common.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.util.Objects;

/**
 * JPA entity for one record of the daily-transaction feed (``DALYTRAN``).
 *
 * :purpose: Maps the legacy COBOL ``DALYTRAN-RECORD`` layout
 *     (``app/cpy/CVTRA06Y.cpy``, record length 350) onto the relational
 *     ``daily_transactions`` table, which is the staged image of the sequential
 *     ``DALYTRAN`` data set that the ``CBTRN02C`` transaction-posting job reads
 *     (``app/jcl/POSTTRAN.jcl`` DD ``DALYTRAN``). Persisting the feed keeps the
 *     staged records readable by the posting ``ItemReader`` across job launches
 *     and across service instances, exactly as the legacy job re-reads its input
 *     data set.
 * :output: A persistent feed row keyed by the 16-character ``DALYTRAN-ID``
 *     (the trailing 20-byte COBOL ``FILLER`` carries no data and is not modeled).
 *     Monetary and identifier fields retain exact fixed-point
 *     (``BigDecimal``/``NUMERIC(11,2)``) and integral types so downstream posting
 *     validation (over-limit, cross-reference, and expiry checks) reproduces the
 *     legacy results byte-for-byte.
 */
@Entity
@Table(name = "daily_transactions")
public class DailyTransaction {

    /** ``DALYTRAN-ID`` PIC X(16) — natural identifier of the feed record (primary key). */
    @Id
    @Column(name = "dalytran_id", length = 16, nullable = false)
    private String dalytranId;

    /**
     * ``DALYTRAN-TYPE-CD`` PIC X(02) — transaction type code.
     *
     * Mandatory: the fixed-width 350-byte feed record always carries it and
     * ``2900-WRITE-TRANSACTION-FILE`` posts it into the transaction master, so a
     * staged row without one is not a ``DALYTRAN`` record.
     */
    @Column(name = "dalytran_type_cd", length = 2, nullable = false)
    private String dalytranTypeCd;

    /**
     * ``DALYTRAN-CAT-CD`` PIC 9(04) — transaction category code.
     *
     * Mandatory: it forms part of the ``TRAN-CAT-BAL`` key
     * (``TRANCAT-ACCT-ID``/``TRANCAT-TYPE-CD``/``TRANCAT-CD``) that
     * ``2700-UPDATE-TCATBAL`` maintains.
     */
    @Column(name = "dalytran_cat_cd", nullable = false)
    private Integer dalytranCatCd;

    /** ``DALYTRAN-SOURCE`` PIC X(10) — originating source channel. */
    @Column(name = "dalytran_source", length = 10)
    private String dalytranSource;

    /** ``DALYTRAN-DESC`` PIC X(100) — transaction description. */
    @Column(name = "dalytran_desc", length = 100)
    private String dalytranDesc;

    /**
     * ``DALYTRAN-AMT`` PIC S9(09)V99 — signed transaction amount as NUMERIC(11,2).
     *
     * Held as {@link BigDecimal} (never a binary floating-point type) so the
     * over-limit reject computation
     * ``WS-TEMP-BAL = ACCT-CURR-CYC-CREDIT - ACCT-CURR-CYC-DEBIT + DALYTRAN-AMT``
     * preserves exact packed-decimal precision and scale. Mandatory: that
     * computation has no meaning without an amount.
     */
    @Column(name = "dalytran_amt", precision = 11, scale = 2, nullable = false)
    private BigDecimal dalytranAmt;

    /** ``DALYTRAN-MERCHANT-ID`` PIC 9(09) — merchant identifier. */
    @Column(name = "dalytran_merchant_id")
    private Long dalytranMerchantId;

    /** ``DALYTRAN-MERCHANT-NAME`` PIC X(50) — merchant name. */
    @Column(name = "dalytran_merchant_name", length = 50)
    private String dalytranMerchantName;

    /** ``DALYTRAN-MERCHANT-CITY`` PIC X(50) — merchant city. */
    @Column(name = "dalytran_merchant_city", length = 50)
    private String dalytranMerchantCity;

    /** ``DALYTRAN-MERCHANT-ZIP`` PIC X(10) — merchant postal code. */
    @Column(name = "dalytran_merchant_zip", length = 10)
    private String dalytranMerchantZip;

    /**
     * ``DALYTRAN-CARD-NUM`` PIC X(16) — card number used for cross-reference lookup.
     *
     * Mandatory: ``1500-A-LOOKUP-XREF`` keys the cross-reference read on it.
     */
    @Column(name = "dalytran_card_num", length = 16, nullable = false)
    private String dalytranCardNum;

    /**
     * ``DALYTRAN-ORIG-TS`` PIC X(26) — origination timestamp in
     * ``YYYY-MM-DD-HH.MM.SS.mmmmmm`` form. Retained as the full 26-character
     * string because the posting job compares its first ten characters against
     * the account expiration date for the expiry reject check. Mandatory, and at
     * least those ten date characters must be present, or the expiry check has
     * nothing to compare.
     */
    @Column(name = "dalytran_orig_ts", length = 26, nullable = false)
    private String dalytranOrigTs;

    /** ``DALYTRAN-PROC-TS`` PIC X(26) — processing timestamp (26-character form). */
    @Column(name = "dalytran_proc_ts", length = 26)
    private String dalytranProcTs;

    /**
     * Creates an empty daily-transaction record.
     *
     * :purpose: No-argument constructor required by the JPA provider and used by
     *     the batch flat-file record mapper.
     */
    public DailyTransaction() {
        // Intentionally empty; fields are populated by JPA or the field-set mapper.
    }

    /**
     * :return: the ``DALYTRAN-ID`` record identifier.
     */
    public String getDalytranId() {
        return dalytranId;
    }

    /**
     * :param dalytranId: the ``DALYTRAN-ID`` record identifier to set.
     */
    public void setDalytranId(String dalytranId) {
        this.dalytranId = dalytranId;
    }

    /**
     * :return: the ``DALYTRAN-TYPE-CD`` transaction type code.
     */
    public String getDalytranTypeCd() {
        return dalytranTypeCd;
    }

    /**
     * :param dalytranTypeCd: the ``DALYTRAN-TYPE-CD`` transaction type code to set.
     */
    public void setDalytranTypeCd(String dalytranTypeCd) {
        this.dalytranTypeCd = dalytranTypeCd;
    }

    /**
     * :return: the ``DALYTRAN-CAT-CD`` transaction category code.
     */
    public Integer getDalytranCatCd() {
        return dalytranCatCd;
    }

    /**
     * :param dalytranCatCd: the ``DALYTRAN-CAT-CD`` transaction category code to set.
     */
    public void setDalytranCatCd(Integer dalytranCatCd) {
        this.dalytranCatCd = dalytranCatCd;
    }

    /**
     * :return: the ``DALYTRAN-SOURCE`` originating source channel.
     */
    public String getDalytranSource() {
        return dalytranSource;
    }

    /**
     * :param dalytranSource: the ``DALYTRAN-SOURCE`` originating source channel to set.
     */
    public void setDalytranSource(String dalytranSource) {
        this.dalytranSource = dalytranSource;
    }

    /**
     * :return: the ``DALYTRAN-DESC`` transaction description.
     */
    public String getDalytranDesc() {
        return dalytranDesc;
    }

    /**
     * :param dalytranDesc: the ``DALYTRAN-DESC`` transaction description to set.
     */
    public void setDalytranDesc(String dalytranDesc) {
        this.dalytranDesc = dalytranDesc;
    }

    /**
     * :return: the ``DALYTRAN-AMT`` signed transaction amount.
     */
    public BigDecimal getDalytranAmt() {
        return dalytranAmt;
    }

    /**
     * :param dalytranAmt: the ``DALYTRAN-AMT`` signed transaction amount to set.
     */
    public void setDalytranAmt(BigDecimal dalytranAmt) {
        this.dalytranAmt = dalytranAmt;
    }

    /**
     * :return: the ``DALYTRAN-MERCHANT-ID`` merchant identifier.
     */
    public Long getDalytranMerchantId() {
        return dalytranMerchantId;
    }

    /**
     * :param dalytranMerchantId: the ``DALYTRAN-MERCHANT-ID`` merchant identifier to set.
     */
    public void setDalytranMerchantId(Long dalytranMerchantId) {
        this.dalytranMerchantId = dalytranMerchantId;
    }

    /**
     * :return: the ``DALYTRAN-MERCHANT-NAME`` merchant name.
     */
    public String getDalytranMerchantName() {
        return dalytranMerchantName;
    }

    /**
     * :param dalytranMerchantName: the ``DALYTRAN-MERCHANT-NAME`` merchant name to set.
     */
    public void setDalytranMerchantName(String dalytranMerchantName) {
        this.dalytranMerchantName = dalytranMerchantName;
    }

    /**
     * :return: the ``DALYTRAN-MERCHANT-CITY`` merchant city.
     */
    public String getDalytranMerchantCity() {
        return dalytranMerchantCity;
    }

    /**
     * :param dalytranMerchantCity: the ``DALYTRAN-MERCHANT-CITY`` merchant city to set.
     */
    public void setDalytranMerchantCity(String dalytranMerchantCity) {
        this.dalytranMerchantCity = dalytranMerchantCity;
    }

    /**
     * :return: the ``DALYTRAN-MERCHANT-ZIP`` merchant postal code.
     */
    public String getDalytranMerchantZip() {
        return dalytranMerchantZip;
    }

    /**
     * :param dalytranMerchantZip: the ``DALYTRAN-MERCHANT-ZIP`` merchant postal code to set.
     */
    public void setDalytranMerchantZip(String dalytranMerchantZip) {
        this.dalytranMerchantZip = dalytranMerchantZip;
    }

    /**
     * :return: the ``DALYTRAN-CARD-NUM`` card number.
     */
    public String getDalytranCardNum() {
        return dalytranCardNum;
    }

    /**
     * :param dalytranCardNum: the ``DALYTRAN-CARD-NUM`` card number to set.
     */
    public void setDalytranCardNum(String dalytranCardNum) {
        this.dalytranCardNum = dalytranCardNum;
    }

    /**
     * :return: the ``DALYTRAN-ORIG-TS`` origination timestamp (26-character form).
     */
    public String getDalytranOrigTs() {
        return dalytranOrigTs;
    }

    /**
     * :param dalytranOrigTs: the ``DALYTRAN-ORIG-TS`` origination timestamp to set.
     */
    public void setDalytranOrigTs(String dalytranOrigTs) {
        this.dalytranOrigTs = dalytranOrigTs;
    }

    /**
     * :return: the ``DALYTRAN-PROC-TS`` processing timestamp (26-character form).
     */
    public String getDalytranProcTs() {
        return dalytranProcTs;
    }

    /**
     * :param dalytranProcTs: the ``DALYTRAN-PROC-TS`` processing timestamp to set.
     */
    public void setDalytranProcTs(String dalytranProcTs) {
        this.dalytranProcTs = dalytranProcTs;
    }

    /**
     * Compares two daily-transaction records by their ``DALYTRAN-ID`` value.
     *
     * :param o: the object to compare against.
     * :return: ``true`` when the other object is a ``DailyTransaction`` with an
     *     equal ``DALYTRAN-ID`` value.
     */
    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof DailyTransaction other)) {
            return false;
        }
        return Objects.equals(dalytranId, other.getDalytranId());
    }

    /**
     * :return: a hash code derived from the ``DALYTRAN-ID`` value.
     */
    @Override
    public int hashCode() {
        return Objects.hash(dalytranId);
    }

    /**
     * :return: a diagnostic string representation. The card number is
     *     deliberately omitted to avoid exposing sensitive data.
     */
    @Override
    public String toString() {
        return "DailyTransaction{"
                + "dalytranId='" + dalytranId + '\''
                + ", dalytranTypeCd='" + dalytranTypeCd + '\''
                + ", dalytranCatCd=" + dalytranCatCd
                + ", dalytranSource='" + dalytranSource + '\''
                + ", dalytranAmt=" + dalytranAmt
                + ", dalytranMerchantId=" + dalytranMerchantId
                + ", dalytranMerchantName='" + dalytranMerchantName + '\''
                + ", dalytranMerchantCity='" + dalytranMerchantCity + '\''
                + ", dalytranMerchantZip='" + dalytranMerchantZip + '\''
                + ", dalytranOrigTs='" + dalytranOrigTs + '\''
                + ", dalytranProcTs='" + dalytranProcTs + '\''
                + '}';
    }
}
