package com.carddemo.common.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.util.Objects;

/**
 * JPA entity for the daily-transaction feed (``DALYTRAN``).
 *
 * :purpose: Maps the legacy COBOL ``DALYTRAN-RECORD`` layout
 *     (``app/cpy/CVTRA06Y.cpy``, record length 350) onto the PostgreSQL
 *     ``daily_transactions`` table. It is the batch-posting input feed consumed
 *     by the transaction-posting job and mirrors the posted ``Transaction``
 *     record field-for-field with the ``DALYTRAN-`` prefix preserved.
 * :output: A persistable ``daily_transactions`` row exposing the thirteen mapped
 *     columns; the trailing 20-byte COBOL ``FILLER`` carries no data and is
 *     intentionally not mapped. Monetary and identifier fields retain exact
 *     fixed-point (``BigDecimal``) and integral types so downstream posting
 *     validation (over-limit, cross-reference, and expiry checks) reproduces the
 *     legacy results byte-for-byte.
 */
@Entity
@Table(name = "daily_transactions")
public class DailyTransaction {

    /** ``DALYTRAN-ID`` PIC X(16) — natural primary key of the feed record. */
    @Id
    @Column(name = "dalytran_id", length = 16, nullable = false)
    private String dalytranId;

    /** ``DALYTRAN-TYPE-CD`` PIC X(02) — transaction type code. */
    @Column(name = "dalytran_type_cd", length = 2)
    private String dalytranTypeCd;

    /** ``DALYTRAN-CAT-CD`` PIC 9(04) — transaction category code. */
    @Column(name = "dalytran_cat_cd")
    private Integer dalytranCatCd;

    /** ``DALYTRAN-SOURCE`` PIC X(10) — originating source channel. */
    @Column(name = "dalytran_source", length = 10)
    private String dalytranSource;

    /** ``DALYTRAN-DESC`` PIC X(100) — transaction description. */
    @Column(name = "dalytran_desc", length = 100)
    private String dalytranDesc;

    /**
     * ``DALYTRAN-AMT`` PIC S9(09)V99 — signed transaction amount stored as
     * ``NUMERIC(11,2)``. Held as {@link BigDecimal} (never a binary
     * floating-point type) so the over-limit reject computation
     * ``WS-TEMP-BAL = ACCT-CURR-CYC-CREDIT - ACCT-CURR-CYC-DEBIT + DALYTRAN-AMT``
     * preserves exact packed-decimal precision and scale.
     */
    @Column(name = "dalytran_amt", precision = 11, scale = 2)
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

    /** ``DALYTRAN-CARD-NUM`` PIC X(16) — card number used for cross-reference lookup. */
    @Column(name = "dalytran_card_num", length = 16)
    private String dalytranCardNum;

    /**
     * ``DALYTRAN-ORIG-TS`` PIC X(26) — origination timestamp in
     * ``YYYY-MM-DD-HH.MM.SS.mmmmmm`` form. Retained as the full 26-character
     * string because the posting job compares its first ten characters against
     * the account expiration date for the expiry reject check.
     */
    @Column(name = "dalytran_orig_ts", length = 26)
    private String dalytranOrigTs;

    /** ``DALYTRAN-PROC-TS`` PIC X(26) — processing timestamp (26-character form). */
    @Column(name = "dalytran_proc_ts", length = 26)
    private String dalytranProcTs;

    /**
     * Creates an empty daily-transaction record.
     *
     * :purpose: No-argument constructor required by the JPA provider for entity
     *     instantiation and hydration.
     */
    public DailyTransaction() {
        // Intentionally empty; JPA populates fields via property access.
    }

    /**
     * :return: the ``DALYTRAN-ID`` primary key.
     */
    public String getDalytranId() {
        return dalytranId;
    }

    /**
     * :param dalytranId: the ``DALYTRAN-ID`` primary key to set.
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
     * Compares two daily-transaction records for identity equality.
     *
     * :param o: the object to compare against.
     * :return: ``true`` when the other object is a ``DailyTransaction`` with an
     *     equal ``DALYTRAN-ID`` primary key.
     */
    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }
        DailyTransaction that = (DailyTransaction) o;
        return Objects.equals(dalytranId, that.dalytranId);
    }

    /**
     * :return: a hash code derived from the ``DALYTRAN-ID`` primary key.
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
