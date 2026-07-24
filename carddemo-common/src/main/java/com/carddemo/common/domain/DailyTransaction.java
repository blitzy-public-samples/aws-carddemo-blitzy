package com.carddemo.common.domain;

import java.math.BigDecimal;
import java.util.Objects;

/**
 * Plain batch-input model for the daily-transaction feed (``DALYTRAN``).
 *
 * :purpose: Represents one record of the legacy COBOL ``DALYTRAN-RECORD`` layout
 *     (``app/cpy/CVTRA06Y.cpy``, record length 350) as read by the
 *     transaction-posting batch job. This is a sequential feed record, NOT a
 *     persistent table: the target schema defines exactly ten tables, so this
 *     type is a non-entity value object (no ``@Entity``/``@Table``/``@Id``
 *     mapping) consumed by the Spring Batch ``ItemReader``/``ItemProcessor`` and
 *     never persisted directly.
 * :output: A mutable carrier exposing the thirteen feed fields (the trailing
 *     20-byte COBOL ``FILLER`` carries no data and is not modeled). Monetary and
 *     identifier fields retain exact fixed-point (``BigDecimal``) and integral
 *     types so downstream posting validation (over-limit, cross-reference, and
 *     expiry checks) reproduces the legacy results byte-for-byte.
 */
public class DailyTransaction {

    /** ``DALYTRAN-ID`` PIC X(16) — natural identifier of the feed record. */
    private String dalytranId;

    /** ``DALYTRAN-TYPE-CD`` PIC X(02) — transaction type code. */
    private String dalytranTypeCd;

    /** ``DALYTRAN-CAT-CD`` PIC 9(04) — transaction category code. */
    private Integer dalytranCatCd;

    /** ``DALYTRAN-SOURCE`` PIC X(10) — originating source channel. */
    private String dalytranSource;

    /** ``DALYTRAN-DESC`` PIC X(100) — transaction description. */
    private String dalytranDesc;

    /**
     * ``DALYTRAN-AMT`` PIC S9(09)V99 — signed transaction amount.
     *
     * Held as {@link BigDecimal} (never a binary floating-point type) so the
     * over-limit reject computation
     * ``WS-TEMP-BAL = ACCT-CURR-CYC-CREDIT - ACCT-CURR-CYC-DEBIT + DALYTRAN-AMT``
     * preserves exact packed-decimal precision and scale.
     */
    private BigDecimal dalytranAmt;

    /** ``DALYTRAN-MERCHANT-ID`` PIC 9(09) — merchant identifier. */
    private Long dalytranMerchantId;

    /** ``DALYTRAN-MERCHANT-NAME`` PIC X(50) — merchant name. */
    private String dalytranMerchantName;

    /** ``DALYTRAN-MERCHANT-CITY`` PIC X(50) — merchant city. */
    private String dalytranMerchantCity;

    /** ``DALYTRAN-MERCHANT-ZIP`` PIC X(10) — merchant postal code. */
    private String dalytranMerchantZip;

    /** ``DALYTRAN-CARD-NUM`` PIC X(16) — card number used for cross-reference lookup. */
    private String dalytranCardNum;

    /**
     * ``DALYTRAN-ORIG-TS`` PIC X(26) — origination timestamp in
     * ``YYYY-MM-DD-HH.MM.SS.mmmmmm`` form. Retained as the full 26-character
     * string because the posting job compares its first ten characters against
     * the account expiration date for the expiry reject check.
     */
    private String dalytranOrigTs;

    /** ``DALYTRAN-PROC-TS`` PIC X(26) — processing timestamp (26-character form). */
    private String dalytranProcTs;

    /**
     * Creates an empty daily-transaction record.
     *
     * :purpose: No-argument constructor used by the batch item reader/mapper.
     */
    public DailyTransaction() {
        // Intentionally empty; fields are populated by the batch field-set mapper.
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
