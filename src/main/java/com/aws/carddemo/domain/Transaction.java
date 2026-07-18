package com.aws.carddemo.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * JPA entity mapping the legacy VSAM {@code TRANSACT} KSDS onto the relational
 * {@code transaction} table as part of the AWS CardDemo COBOL-to-Java migration.
 *
 * <p><strong>Origin and traceability (AAP 0.6.10):</strong> migrated one-for-one from the COBOL
 * copybook {@code TRAN-RECORD} (record length 350) defined in {@code legacy/cpy/CVTRA05Y.cpy}
 * (retained read-only for reference). Each {@code 05}-level field of that copybook maps to exactly
 * one persisted column below; the trailing {@code FILLER PIC X(20)} is byte padding only and is
 * intentionally not persisted, so the 350-byte fixed-width record maps to the 13 columns declared
 * here.</p>
 *
 * <p><strong>Key semantics (AAP 0.6.2):</strong> the primary key is the 16-character transaction id
 * ({@code TRAN-ID} &rarr; column {@code tran_id}). The legacy transaction-by-card alternate index is
 * reproduced as the secondary database index {@code transaction(card_num)} and surfaced through the
 * Spring Data derived query {@code TransactionRepository.findByCardNum(String)}. To keep that derived
 * query and the index aligned, {@code TRAN-CARD-NUM} is deliberately exposed as the property
 * {@code cardNum} (column {@code card_num}) with the {@code TRAN-} prefix dropped; every other column
 * retains its {@code tran_} prefix.</p>
 *
 * <p><strong>Decimal and timestamp fidelity (AAP 0.6.1):</strong> the monetary amount
 * {@code TRAN-AMT PIC S9(09)V99} maps to {@link java.math.BigDecimal} with precision 11 and scale 2
 * ({@code NUMERIC(11,2)}); monetary values are never represented with binary floating-point types.
 * The two 26-character timestamp fields ({@code TRAN-ORIG-TS} and {@code TRAN-PROC-TS}, formatted
 * {@code yyyy-MM-dd HH:mm:ss.SSSSSS}) map to {@link java.time.LocalDateTime}.</p>
 */
@Entity
@Table(name = "transaction")
public class Transaction {

    /** Transaction id &mdash; {@code TRAN-ID PIC X(16)}; primary key of the {@code TRANSACT} KSDS. */
    @Id
    @Column(name = "tran_id", length = 16)
    private String tranId;

    /** Transaction type code &mdash; {@code TRAN-TYPE-CD PIC X(02)}. */
    @Column(name = "tran_type_cd", length = 2)
    private String tranTypeCd;

    /** Transaction category code &mdash; {@code TRAN-CAT-CD PIC 9(04)}. */
    @Column(name = "tran_cat_cd", precision = 4)
    private Integer tranCatCd;

    /** Transaction source &mdash; {@code TRAN-SOURCE PIC X(10)}. */
    @Column(name = "tran_source", length = 10)
    private String tranSource;

    /** Transaction description &mdash; {@code TRAN-DESC PIC X(100)}. */
    @Column(name = "tran_desc", length = 100)
    private String tranDesc;

    /**
     * Transaction amount &mdash; {@code TRAN-AMT PIC S9(09)V99}; maps to {@code NUMERIC(11,2)}.
     * Represented as {@link java.math.BigDecimal} (precision 11, scale 2); never floating point,
     * to preserve COBOL fixed-scale decimal arithmetic semantics.
     */
    @Column(name = "tran_amt", precision = 11, scale = 2)
    private BigDecimal tranAmt;

    /** Merchant id &mdash; {@code TRAN-MERCHANT-ID PIC 9(09)}. */
    @Column(name = "tran_merchant_id", precision = 9)
    private Long merchantId;

    /** Merchant name &mdash; {@code TRAN-MERCHANT-NAME PIC X(50)}. */
    @Column(name = "tran_merchant_name", length = 50)
    private String merchantName;

    /** Merchant city &mdash; {@code TRAN-MERCHANT-CITY PIC X(50)}. */
    @Column(name = "tran_merchant_city", length = 50)
    private String merchantCity;

    /** Merchant ZIP &mdash; {@code TRAN-MERCHANT-ZIP PIC X(10)}. */
    @Column(name = "tran_merchant_zip", length = 10)
    private String merchantZip;

    /**
     * Card number &mdash; {@code TRAN-CARD-NUM PIC X(16)}. Exposed as {@code cardNum} / column
     * {@code card_num} (the {@code TRAN-} prefix is dropped) so the repository derived query
     * {@code findByCardNum(String)} resolves and matches the {@code transaction(card_num)} index.
     */
    @Column(name = "card_num", length = 16)
    private String cardNum;

    /** Original timestamp &mdash; {@code TRAN-ORIG-TS PIC X(26)}; {@code yyyy-MM-dd HH:mm:ss.SSSSSS}. */
    @Column(name = "tran_orig_ts")
    private LocalDateTime origTs;

    /** Processing timestamp &mdash; {@code TRAN-PROC-TS PIC X(26)}; {@code yyyy-MM-dd HH:mm:ss.SSSSSS}. */
    @Column(name = "tran_proc_ts")
    private LocalDateTime procTs;

    /**
     * Creates an empty transaction. Required by the JPA specification for entity instantiation and
     * used by application code and the fixed-width record mapper when populating fields individually.
     */
    public Transaction() {
        // No-args constructor required by the JPA specification; intentionally empty.
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

    public Long getMerchantId() {
        return merchantId;
    }

    public void setMerchantId(Long merchantId) {
        this.merchantId = merchantId;
    }

    public String getMerchantName() {
        return merchantName;
    }

    public void setMerchantName(String merchantName) {
        this.merchantName = merchantName;
    }

    public String getMerchantCity() {
        return merchantCity;
    }

    public void setMerchantCity(String merchantCity) {
        this.merchantCity = merchantCity;
    }

    public String getMerchantZip() {
        return merchantZip;
    }

    public void setMerchantZip(String merchantZip) {
        this.merchantZip = merchantZip;
    }

    public String getCardNum() {
        return cardNum;
    }

    public void setCardNum(String cardNum) {
        this.cardNum = cardNum;
    }

    public LocalDateTime getOrigTs() {
        return origTs;
    }

    public void setOrigTs(LocalDateTime origTs) {
        this.origTs = origTs;
    }

    public LocalDateTime getProcTs() {
        return procTs;
    }

    public void setProcTs(LocalDateTime procTs) {
        this.procTs = procTs;
    }

    /**
     * Two transactions are equal when they share the same primary key ({@code tranId}), matching the
     * identity semantics of the underlying {@code TRANSACT} KSDS record key.
     *
     * @param o the object to compare with
     * @return {@code true} if {@code o} is a {@code Transaction} with an equal {@code tranId}
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
        return tranId != null ? tranId.equals(that.tranId) : that.tranId == null;
    }

    /**
     * Hash code derived from the primary key ({@code tranId}), consistent with {@link #equals(Object)}.
     *
     * @return the hash code of {@code tranId}, or {@code 0} when the id is not yet assigned
     */
    @Override
    public int hashCode() {
        return tranId != null ? tranId.hashCode() : 0;
    }

    /**
     * Returns a diagnostic representation of this transaction. The card number ({@code cardNum}) is
     * masked to its last four characters so full primary account numbers are never emitted to logs.
     *
     * @return a string containing the transaction fields with the card number masked
     */
    @Override
    public String toString() {
        return "Transaction{"
                + "tranId='" + tranId + '\''
                + ", tranTypeCd='" + tranTypeCd + '\''
                + ", tranCatCd=" + tranCatCd
                + ", tranSource='" + tranSource + '\''
                + ", tranDesc='" + tranDesc + '\''
                + ", tranAmt=" + tranAmt
                + ", merchantId=" + merchantId
                + ", merchantName='" + merchantName + '\''
                + ", merchantCity='" + merchantCity + '\''
                + ", merchantZip='" + merchantZip + '\''
                + ", cardNum='" + maskCardNumber(cardNum) + '\''
                + ", origTs=" + origTs
                + ", procTs=" + procTs
                + '}';
    }

    /**
     * Masks all but the last four characters of a card number for safe logging (PAN hygiene).
     *
     * @param value the raw card number (may be {@code null} or padded with spaces)
     * @return the masked value, or {@code null} when {@code value} is {@code null}
     */
    private static String maskCardNumber(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        int visibleFrom = trimmed.length() - 4;
        if (visibleFrom <= 0) {
            return trimmed;
        }
        return "*".repeat(visibleFrom) + trimmed.substring(visibleFrom);
    }
}
