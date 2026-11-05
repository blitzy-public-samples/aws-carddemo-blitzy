package com.cardemo.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.util.Objects;

/**
 * JPA entity representing the transaction master record.
 *
 * <p>Faithfully maps the VSAM TRANSACT KSDS dataset (350-byte records)
 * defined by the COBOL copybook {@code CVTRA05Y.cpy} ({@code TRAN-RECORD}).
 *
 * <h3>COBOL Record Layout (350 bytes)</h3>
 * <pre>
 * 01  TRAN-RECORD.
 *     05  TRAN-ID                PIC X(16).
 *     05  TRAN-TYPE-CD           PIC X(02).
 *     05  TRAN-CAT-CD            PIC 9(04).
 *     05  TRAN-SOURCE            PIC X(10).
 *     05  TRAN-DESC              PIC X(100).
 *     05  TRAN-AMT               PIC S9(09)V99.
 *     05  TRAN-MERCHANT-ID       PIC 9(09).
 *     05  TRAN-MERCHANT-NAME     PIC X(50).
 *     05  TRAN-MERCHANT-CITY     PIC X(50).
 *     05  TRAN-MERCHANT-ZIP      PIC X(10).
 *     05  TRAN-CARD-NUM          PIC X(16).
 *     05  TRAN-ORIG-TS           PIC X(26).
 *     05  TRAN-PROC-TS           PIC X(26).
 *     05  FILLER                 PIC X(20).
 * </pre>
 *
 * <h3>Key Design Decisions</h3>
 * <ul>
 *   <li>Table name is {@code card_transaction} to avoid the PostgreSQL
 *       reserved word {@code transaction}.</li>
 *   <li>A secondary index on {@code orig_timestamp} replicates the VSAM
 *       alternate index (AIX) on {@code TRAN-ORIG-TS} (position 304,
 *       length 26) for chronological query access.</li>
 *   <li>{@code amount} uses {@link BigDecimal} (precision 11, scale 2)
 *       instead of floating-point, matching the COBOL
 *       {@code PIC S9(09)V99} packed-decimal semantics exactly.</li>
 *   <li>Numeric fields {@code TRAN-CAT-CD PIC 9(04)} and
 *       {@code TRAN-MERCHANT-ID PIC 9(09)} are stored as {@link String}
 *       to preserve leading zeros from the COBOL record.</li>
 *   <li>Timestamps are stored as 26-character {@link String} values in
 *       ISO-8601 extended format ({@code YYYY-MM-DD-HH.MM.SS.mmmmmm})
 *       to preserve the exact COBOL representation with microsecond
 *       precision.</li>
 * </ul>
 *
 * @see <a href="app/cpy/CVTRA05Y.cpy">COBOL TRAN-RECORD copybook</a>
 */
@Entity
@Table(
    name = "transactions",
    indexes = {
        @Index(name = "idx_transaction_orig_ts", columnList = "tran_orig_ts")
    }
)
public class Transaction {

    // ---------------------------------------------------------------
    // Fields — mapped 1-to-1 from CVTRA05Y.cpy TRAN-RECORD (350 bytes)
    // ---------------------------------------------------------------

    /**
     * Transaction identifier (primary key).
     * COBOL: {@code TRAN-ID PIC X(16)}.
     */
    @Id
    @Column(name = "tran_id", length = 16, nullable = false)
    private String tranId;

    /**
     * Transaction type code.
     * COBOL: {@code TRAN-TYPE-CD PIC X(02)}.
     * References the {@code TRANTYPE} reference data (7 types).
     */
    @Column(name = "tran_type_cd", length = 2)
    private String typeCode;

    /**
     * Transaction category code.
     * COBOL: {@code TRAN-CAT-CD PIC 9(04)}.
     * Stored as Integer matching the PostgreSQL INTEGER column type.
     * References the {@code TRANCATG} reference data (18 categories).
     */
    @Column(name = "tran_cat_cd")
    private Integer categoryCode;

    /**
     * Transaction source identifier.
     * COBOL: {@code TRAN-SOURCE PIC X(10)}.
     */
    @Column(name = "tran_source", length = 10)
    private String source;

    /**
     * Transaction description.
     * COBOL: {@code TRAN-DESC PIC X(100)}.
     */
    @Column(name = "tran_desc", length = 100)
    private String description;

    /**
     * Transaction amount.
     * COBOL: {@code TRAN-AMT PIC S9(09)V99} — signed, 9 integer digits,
     * 2 decimal digits.
     *
     * <p><strong>CRITICAL:</strong> Uses {@link BigDecimal} with
     * precision = 11 and scale = 2. Never use {@code float} or
     * {@code double}. Calculations must use
     * {@link java.math.RoundingMode#HALF_UP} to match COBOL default
     * rounding behaviour.
     */
    @Column(name = "tran_amt", precision = 11, scale = 2)
    private BigDecimal amount;

    /**
     * Merchant identifier.
     * COBOL: {@code TRAN-MERCHANT-ID PIC 9(09)}.
     * Stored as String to preserve leading zeros.
     */
    @Column(name = "tran_merchant_id", length = 9)
    private String merchantId;

    /**
     * Merchant name.
     * COBOL: {@code TRAN-MERCHANT-NAME PIC X(50)}.
     */
    @Column(name = "tran_merchant_name", length = 50)
    private String merchantName;

    /**
     * Merchant city.
     * COBOL: {@code TRAN-MERCHANT-CITY PIC X(50)}.
     */
    @Column(name = "tran_merchant_city", length = 50)
    private String merchantCity;

    /**
     * Merchant ZIP / postal code.
     * COBOL: {@code TRAN-MERCHANT-ZIP PIC X(10)}.
     */
    @Column(name = "tran_merchant_zip", length = 10)
    private String merchantZip;

    /**
     * Card number associated with this transaction.
     * COBOL: {@code TRAN-CARD-NUM PIC X(16)}.
     */
    @Column(name = "tran_card_num", length = 16)
    private String cardNum;

    /**
     * Original transaction timestamp in ISO-8601 extended format.
     * COBOL: {@code TRAN-ORIG-TS PIC X(26)}.
     * Format: {@code YYYY-MM-DD-HH.MM.SS.mmmmmm} (26 characters with
     * microsecond precision).
     *
     * <p><strong>INDEXED</strong> — corresponds to the VSAM alternate
     * index (AIX) on {@code TRAN-ORIG-TS} (position 304, length 26)
     * enabling chronological range queries.
     */
    @Column(name = "tran_orig_ts", length = 26)
    private String origTimestamp;

    /**
     * Processing timestamp in ISO-8601 extended format.
     * COBOL: {@code TRAN-PROC-TS PIC X(26)}.
     * Format: {@code YYYY-MM-DD-HH.MM.SS.mmmmmm} (26 characters with
     * microsecond precision).
     */
    @Column(name = "tran_proc_ts", length = 26)
    private String procTimestamp;

    // ---------------------------------------------------------------
    // Constructors
    // ---------------------------------------------------------------

    /**
     * JPA-required no-arg constructor.
     * Protected to discourage direct use outside the persistence layer.
     */
    protected Transaction() {
        // Required by JPA specification
    }

    /**
     * All-fields constructor for programmatic creation.
     *
     * @param tranId        transaction identifier (PK, 16 chars)
     * @param typeCode      transaction type code (2 chars)
     * @param categoryCode  transaction category code (integer value)
     * @param source        transaction source (10 chars)
     * @param description   transaction description (up to 100 chars)
     * @param amount        transaction amount (BigDecimal, precision 11, scale 2)
     * @param merchantId    merchant identifier (9 digits as String)
     * @param merchantName  merchant name (up to 50 chars)
     * @param merchantCity  merchant city (up to 50 chars)
     * @param merchantZip   merchant ZIP code (up to 10 chars)
     * @param cardNum       card number (16 chars)
     * @param origTimestamp original timestamp (26-char ISO-8601 extended)
     * @param procTimestamp processing timestamp (26-char ISO-8601 extended)
     */
    public Transaction(String tranId, String typeCode, Integer categoryCode,
                       String source, String description, BigDecimal amount,
                       String merchantId, String merchantName,
                       String merchantCity, String merchantZip,
                       String cardNum, String origTimestamp,
                       String procTimestamp) {
        this.tranId = tranId;
        this.typeCode = typeCode;
        this.categoryCode = categoryCode;
        this.source = source;
        this.description = description;
        this.amount = amount;
        this.merchantId = merchantId;
        this.merchantName = merchantName;
        this.merchantCity = merchantCity;
        this.merchantZip = merchantZip;
        this.cardNum = cardNum;
        this.origTimestamp = origTimestamp;
        this.procTimestamp = procTimestamp;
    }

    // ---------------------------------------------------------------
    // Getters and Setters
    // ---------------------------------------------------------------

    /** Returns the transaction identifier (primary key). */
    public String getTranId() {
        return tranId;
    }

    /** Sets the transaction identifier (primary key). */
    public void setTranId(String tranId) {
        this.tranId = tranId;
    }

    /** Returns the transaction type code. */
    public String getTypeCode() {
        return typeCode;
    }

    /** Sets the transaction type code. */
    public void setTypeCode(String typeCode) {
        this.typeCode = typeCode;
    }

    /** Returns the transaction category code. */
    public Integer getCategoryCode() {
        return categoryCode;
    }

    /** Sets the transaction category code. */
    public void setCategoryCode(Integer categoryCode) {
        this.categoryCode = categoryCode;
    }

    /** Returns the transaction source identifier. */
    public String getSource() {
        return source;
    }

    /** Sets the transaction source identifier. */
    public void setSource(String source) {
        this.source = source;
    }

    /** Returns the transaction description. */
    public String getDescription() {
        return description;
    }

    /** Sets the transaction description. */
    public void setDescription(String description) {
        this.description = description;
    }

    /** Returns the transaction amount as {@link BigDecimal}. */
    public BigDecimal getAmount() {
        return amount;
    }

    /** Sets the transaction amount. Must be {@link BigDecimal}. */
    public void setAmount(BigDecimal amount) {
        this.amount = amount;
    }

    /** Returns the merchant identifier. */
    public String getMerchantId() {
        return merchantId;
    }

    /** Sets the merchant identifier. */
    public void setMerchantId(String merchantId) {
        this.merchantId = merchantId;
    }

    /** Returns the merchant name. */
    public String getMerchantName() {
        return merchantName;
    }

    /** Sets the merchant name. */
    public void setMerchantName(String merchantName) {
        this.merchantName = merchantName;
    }

    /** Returns the merchant city. */
    public String getMerchantCity() {
        return merchantCity;
    }

    /** Sets the merchant city. */
    public void setMerchantCity(String merchantCity) {
        this.merchantCity = merchantCity;
    }

    /** Returns the merchant ZIP / postal code. */
    public String getMerchantZip() {
        return merchantZip;
    }

    /** Sets the merchant ZIP / postal code. */
    public void setMerchantZip(String merchantZip) {
        this.merchantZip = merchantZip;
    }

    /** Returns the card number associated with this transaction. */
    public String getCardNum() {
        return cardNum;
    }

    /** Sets the card number associated with this transaction. */
    public void setCardNum(String cardNum) {
        this.cardNum = cardNum;
    }

    /** Returns the original transaction timestamp (ISO-8601 extended, 26 chars). */
    public String getOrigTimestamp() {
        return origTimestamp;
    }

    /** Sets the original transaction timestamp (ISO-8601 extended, 26 chars). */
    public void setOrigTimestamp(String origTimestamp) {
        this.origTimestamp = origTimestamp;
    }

    /** Returns the processing timestamp (ISO-8601 extended, 26 chars). */
    public String getProcTimestamp() {
        return procTimestamp;
    }

    /** Sets the processing timestamp (ISO-8601 extended, 26 chars). */
    public void setProcTimestamp(String procTimestamp) {
        this.procTimestamp = procTimestamp;
    }

    // ---------------------------------------------------------------
    // equals / hashCode — identity based on primary key (tranId)
    // ---------------------------------------------------------------

    /**
     * Equality based on the {@code tranId} primary key, following JPA
     * entity best practices. Two {@code Transaction} instances are equal
     * if and only if they share the same non-null {@code tranId}.
     */
    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof Transaction other)) {
            return false;
        }
        return tranId != null && Objects.equals(tranId, other.tranId);
    }

    /**
     * Hash code derived from {@code tranId} to maintain consistency with
     * {@link #equals(Object)}.
     */
    @Override
    public int hashCode() {
        return Objects.hash(tranId);
    }

    // ---------------------------------------------------------------
    // toString
    // ---------------------------------------------------------------

    /**
     * Returns a human-readable string containing the key identifying
     * fields of this transaction. Sensitive data (full card number) is
     * intentionally excluded to avoid leaking PII in logs.
     */
    @Override
    public String toString() {
        return "Transaction{" +
                "tranId='" + tranId + '\'' +
                ", typeCode='" + typeCode + '\'' +
                ", categoryCode=" + categoryCode +
                ", amount=" + amount +
                ", cardNum='" + maskCardNum(cardNum) + '\'' +
                ", origTimestamp='" + origTimestamp + '\'' +
                '}';
    }

    // ---------------------------------------------------------------
    // Private helpers
    // ---------------------------------------------------------------

    /**
     * Masks the card number for safe logging, showing only the last four
     * digits. Returns {@code "null"} for null input and {@code "****"}
     * for input shorter than five characters.
     */
    private static String maskCardNum(String cardNumber) {
        if (cardNumber == null) {
            return "null";
        }
        int len = cardNumber.length();
        if (len <= 4) {
            return "****";
        }
        return "****" + cardNumber.substring(len - 4);
    }
}
