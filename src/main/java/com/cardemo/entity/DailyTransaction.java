package com.cardemo.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.util.Objects;

/**
 * JPA entity mapping the VSAM DALYTRAN dataset (daily transaction feed staging table).
 *
 * <p>Translated from COBOL copybook {@code CVTRA06Y.cpy} — DALYTRAN-RECORD (350 bytes).
 * This is a sequential VSAM input file with no natural primary key; it serves as a
 * batch staging table for the daily posting process ({@code CBTRN02C.cbl}).
 *
 * <p>A surrogate {@code Long id} is used as the primary key since DALYTRAN records
 * have no unique natural identifier in the COBOL source.
 *
 * <h3>COBOL Record Layout (CVTRA06Y.cpy):</h3>
 * <pre>
 * 01  DALYTRAN-RECORD.
 *     05  DALYTRAN-ID                PIC X(16).
 *     05  DALYTRAN-TYPE-CD           PIC X(02).
 *     05  DALYTRAN-CAT-CD            PIC 9(04).
 *     05  DALYTRAN-SOURCE            PIC X(10).
 *     05  DALYTRAN-DESC              PIC X(100).
 *     05  DALYTRAN-AMT               PIC S9(09)V99.
 *     05  DALYTRAN-MERCHANT-ID       PIC 9(09).
 *     05  DALYTRAN-MERCHANT-NAME     PIC X(50).
 *     05  DALYTRAN-MERCHANT-CITY     PIC X(50).
 *     05  DALYTRAN-MERCHANT-ZIP      PIC X(10).
 *     05  DALYTRAN-CARD-NUM          PIC X(16).
 *     05  DALYTRAN-ORIG-TS           PIC X(26).
 *     05  DALYTRAN-PROC-TS           PIC X(26).
 *     05  FILLER                     PIC X(20).
 * </pre>
 *
 * <h3>Key Design Decisions:</h3>
 * <ul>
 *   <li>Surrogate {@code @GeneratedValue(IDENTITY)} PK — DALYTRAN has no VSAM KSDS key</li>
 *   <li>{@code BigDecimal} for {@code amount} — COBOL PIC S9(09)V99, precision=11, scale=2</li>
 *   <li>Numeric COBOL fields stored as {@code String} to preserve leading zeros</li>
 *   <li>Timestamps stored as 26-char {@code String} preserving ISO-8601 extended format</li>
 * </ul>
 *
 * @see com.cardemo.service.batch.DailyPostingService
 */
@Entity
@Table(name = "daily_transactions")
public class DailyTransaction {

    // =========================================================================
    // Fields — mapped from CVTRA06Y.cpy DALYTRAN-RECORD (350 bytes)
    // =========================================================================

    /**
     * Surrogate primary key. DALYTRAN is a sequential VSAM file with no natural
     * primary key — the database generates an auto-incrementing identifier.
     */
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    /**
     * Transaction identifier within the daily feed.
     * COBOL: DALYTRAN-ID PIC X(16).
     */
    @Column(name = "dalytran_id", length = 16)
    private String dalytranId;

    /**
     * Transaction type code (e.g., "01"=Purchase, "03"=Credit).
     * COBOL: DALYTRAN-TYPE-CD PIC X(02).
     */
    @Column(name = "dalytran_type_cd", length = 2)
    private String typeCode;

    /**
     * Transaction category code, stored as Integer matching PostgreSQL INTEGER column type.
     * COBOL: DALYTRAN-CAT-CD PIC 9(04).
     */
    @Column(name = "dalytran_cat_cd")
    private Integer categoryCode;

    /**
     * Transaction source identifier (e.g., "POS TERM", "OPERATOR").
     * COBOL: DALYTRAN-SOURCE PIC X(10).
     */
    @Column(name = "dalytran_source", length = 10)
    private String source;

    /**
     * Transaction description.
     * COBOL: DALYTRAN-DESC PIC X(100).
     */
    @Column(name = "dalytran_desc", length = 100)
    private String description;

    /**
     * Transaction amount — exact decimal arithmetic required.
     * COBOL: DALYTRAN-AMT PIC S9(09)V99 — signed, 9 integer digits, 2 decimal digits.
     * Precision = 11 (9 integer + 2 decimal), scale = 2 from V99.
     * MUST use BigDecimal — NEVER float/double. RoundingMode.HALF_UP for calculations.
     */
    @Column(name = "dalytran_amt", precision = 11, scale = 2)
    private BigDecimal amount;

    /**
     * Merchant identifier, stored as String to preserve leading zeros.
     * COBOL: DALYTRAN-MERCHANT-ID PIC 9(09).
     */
    @Column(name = "dalytran_merchant_id", length = 9)
    private String merchantId;

    /**
     * Merchant name.
     * COBOL: DALYTRAN-MERCHANT-NAME PIC X(50).
     */
    @Column(name = "dalytran_merchant_name", length = 50)
    private String merchantName;

    /**
     * Merchant city.
     * COBOL: DALYTRAN-MERCHANT-CITY PIC X(50).
     */
    @Column(name = "dalytran_merchant_city", length = 50)
    private String merchantCity;

    /**
     * Merchant ZIP code.
     * COBOL: DALYTRAN-MERCHANT-ZIP PIC X(10).
     */
    @Column(name = "dalytran_merchant_zip", length = 10)
    private String merchantZip;

    /**
     * Card number associated with this daily transaction.
     * COBOL: DALYTRAN-CARD-NUM PIC X(16).
     */
    @Column(name = "dalytran_card_num", length = 16)
    private String cardNum;

    /**
     * Original transaction timestamp in ISO-8601 extended format:
     * {@code YYYY-MM-DD-HH.MM.SS.mmmmmm} (26 characters with microsecond precision).
     * COBOL: DALYTRAN-ORIG-TS PIC X(26).
     */
    @Column(name = "dalytran_orig_ts", length = 26)
    private String origTimestamp;

    /**
     * Processing timestamp in ISO-8601 extended format:
     * {@code YYYY-MM-DD-HH.MM.SS.mmmmmm} (26 characters with microsecond precision).
     * COBOL: DALYTRAN-PROC-TS PIC X(26).
     */
    @Column(name = "dalytran_proc_ts", length = 26)
    private String procTimestamp;

    // =========================================================================
    // Constructors
    // =========================================================================

    /**
     * Default no-argument constructor required by JPA.
     * Protected visibility follows JPA best practices — prevents direct
     * instantiation outside of persistence framework and subclasses.
     */
    protected DailyTransaction() {
        // Required by JPA specification
    }

    /**
     * Parameterized constructor for all business fields (excluding surrogate id).
     *
     * @param dalytranId     transaction identifier in the daily feed (16 chars max)
     * @param typeCode       transaction type code (2 chars max)
     * @param categoryCode   transaction category code (integer value)
     * @param source         transaction source identifier (10 chars max)
     * @param description    transaction description (100 chars max)
     * @param amount         transaction amount as BigDecimal (precision=11, scale=2)
     * @param merchantId     merchant identifier (9 chars max, leading-zero preserved)
     * @param merchantName   merchant name (50 chars max)
     * @param merchantCity   merchant city (50 chars max)
     * @param merchantZip    merchant ZIP code (10 chars max)
     * @param cardNum        card number (16 chars max)
     * @param origTimestamp  original timestamp in ISO-8601 format (26 chars)
     * @param procTimestamp  processing timestamp in ISO-8601 format (26 chars)
     */
    public DailyTransaction(String dalytranId, String typeCode, Integer categoryCode,
                            String source, String description, BigDecimal amount,
                            String merchantId, String merchantName, String merchantCity,
                            String merchantZip, String cardNum, String origTimestamp,
                            String procTimestamp) {
        this.dalytranId = dalytranId;
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

    // =========================================================================
    // Getters and Setters
    // =========================================================================

    /**
     * Returns the surrogate primary key.
     *
     * @return the auto-generated database identifier, or {@code null} if not yet persisted
     */
    public Long getId() {
        return id;
    }

    /**
     * Sets the surrogate primary key. Typically managed by the persistence framework.
     *
     * @param id the database identifier
     */
    public void setId(Long id) {
        this.id = id;
    }

    /**
     * Returns the transaction identifier from the daily feed.
     *
     * @return the DALYTRAN-ID value (up to 16 characters)
     */
    public String getDalytranId() {
        return dalytranId;
    }

    /**
     * Sets the transaction identifier from the daily feed.
     *
     * @param dalytranId the transaction identifier (up to 16 characters)
     */
    public void setDalytranId(String dalytranId) {
        this.dalytranId = dalytranId;
    }

    /**
     * Returns the transaction type code.
     *
     * @return the DALYTRAN-TYPE-CD value (up to 2 characters)
     */
    public String getTypeCode() {
        return typeCode;
    }

    /**
     * Sets the transaction type code.
     *
     * @param typeCode the type code (up to 2 characters)
     */
    public void setTypeCode(String typeCode) {
        this.typeCode = typeCode;
    }

    /**
     * Returns the transaction category code.
     *
     * @return the DALYTRAN-CAT-CD value as Integer
     */
    public Integer getCategoryCode() {
        return categoryCode;
    }

    /**
     * Sets the transaction category code.
     *
     * @param categoryCode the category code as Integer
     */
    public void setCategoryCode(Integer categoryCode) {
        this.categoryCode = categoryCode;
    }

    /**
     * Returns the transaction source identifier.
     *
     * @return the DALYTRAN-SOURCE value (up to 10 characters)
     */
    public String getSource() {
        return source;
    }

    /**
     * Sets the transaction source identifier.
     *
     * @param source the source identifier (up to 10 characters)
     */
    public void setSource(String source) {
        this.source = source;
    }

    /**
     * Returns the transaction description.
     *
     * @return the DALYTRAN-DESC value (up to 100 characters)
     */
    public String getDescription() {
        return description;
    }

    /**
     * Sets the transaction description.
     *
     * @param description the description text (up to 100 characters)
     */
    public void setDescription(String description) {
        this.description = description;
    }

    /**
     * Returns the transaction amount as exact decimal.
     * Translated from COBOL PIC S9(09)V99 — precision=11, scale=2.
     *
     * @return the amount as {@code BigDecimal}, never {@code float} or {@code double}
     */
    public BigDecimal getAmount() {
        return amount;
    }

    /**
     * Sets the transaction amount. Must be {@code BigDecimal} with appropriate scale.
     *
     * @param amount the transaction amount (precision=11, scale=2)
     */
    public void setAmount(BigDecimal amount) {
        this.amount = amount;
    }

    /**
     * Returns the merchant identifier (leading zeros preserved).
     *
     * @return the DALYTRAN-MERCHANT-ID value (up to 9 characters)
     */
    public String getMerchantId() {
        return merchantId;
    }

    /**
     * Sets the merchant identifier.
     *
     * @param merchantId the merchant identifier (up to 9 characters, leading zeros preserved)
     */
    public void setMerchantId(String merchantId) {
        this.merchantId = merchantId;
    }

    /**
     * Returns the merchant name.
     *
     * @return the DALYTRAN-MERCHANT-NAME value (up to 50 characters)
     */
    public String getMerchantName() {
        return merchantName;
    }

    /**
     * Sets the merchant name.
     *
     * @param merchantName the merchant name (up to 50 characters)
     */
    public void setMerchantName(String merchantName) {
        this.merchantName = merchantName;
    }

    /**
     * Returns the merchant city.
     *
     * @return the DALYTRAN-MERCHANT-CITY value (up to 50 characters)
     */
    public String getMerchantCity() {
        return merchantCity;
    }

    /**
     * Sets the merchant city.
     *
     * @param merchantCity the merchant city (up to 50 characters)
     */
    public void setMerchantCity(String merchantCity) {
        this.merchantCity = merchantCity;
    }

    /**
     * Returns the merchant ZIP code.
     *
     * @return the DALYTRAN-MERCHANT-ZIP value (up to 10 characters)
     */
    public String getMerchantZip() {
        return merchantZip;
    }

    /**
     * Sets the merchant ZIP code.
     *
     * @param merchantZip the ZIP code (up to 10 characters)
     */
    public void setMerchantZip(String merchantZip) {
        this.merchantZip = merchantZip;
    }

    /**
     * Returns the card number associated with this daily transaction.
     *
     * @return the DALYTRAN-CARD-NUM value (up to 16 characters)
     */
    public String getCardNum() {
        return cardNum;
    }

    /**
     * Sets the card number.
     *
     * @param cardNum the card number (up to 16 characters)
     */
    public void setCardNum(String cardNum) {
        this.cardNum = cardNum;
    }

    /**
     * Returns the original transaction timestamp in ISO-8601 extended format.
     * Format: {@code YYYY-MM-DD-HH.MM.SS.mmmmmm} (26 characters).
     *
     * @return the DALYTRAN-ORIG-TS value
     */
    public String getOrigTimestamp() {
        return origTimestamp;
    }

    /**
     * Sets the original transaction timestamp.
     *
     * @param origTimestamp the timestamp in ISO-8601 format (26 characters)
     */
    public void setOrigTimestamp(String origTimestamp) {
        this.origTimestamp = origTimestamp;
    }

    /**
     * Returns the processing timestamp in ISO-8601 extended format.
     * Format: {@code YYYY-MM-DD-HH.MM.SS.mmmmmm} (26 characters).
     *
     * @return the DALYTRAN-PROC-TS value
     */
    public String getProcTimestamp() {
        return procTimestamp;
    }

    /**
     * Sets the processing timestamp.
     *
     * @param procTimestamp the timestamp in ISO-8601 format (26 characters)
     */
    public void setProcTimestamp(String procTimestamp) {
        this.procTimestamp = procTimestamp;
    }

    // =========================================================================
    // equals, hashCode, toString
    // =========================================================================

    /**
     * Equality comparison based on the surrogate {@code id} when persisted,
     * or the natural key combination of {@code dalytranId} + {@code origTimestamp}
     * when the entity has not yet been assigned a database identifier.
     *
     * <p>This two-tier approach handles both transient (pre-persist) and managed
     * (post-persist) entity states, which is critical for a staging table entity
     * that may exist in collections before being flushed to the database.
     *
     * @param o the object to compare
     * @return {@code true} if the objects are considered equal
     */
    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof DailyTransaction that)) {
            return false;
        }
        if (id != null && that.id != null) {
            return Objects.equals(id, that.id);
        }
        return Objects.equals(dalytranId, that.dalytranId)
                && Objects.equals(origTimestamp, that.origTimestamp);
    }

    /**
     * Hash code based on the surrogate {@code id} when available, or the natural
     * key combination of {@code dalytranId} + {@code origTimestamp} otherwise.
     *
     * @return a hash code consistent with {@link #equals(Object)}
     */
    @Override
    public int hashCode() {
        if (id != null) {
            return Objects.hash(id);
        }
        return Objects.hash(dalytranId, origTimestamp);
    }

    /**
     * Returns a string representation with key identifying fields.
     * Includes the surrogate id, dalytranId, type, amount, card number,
     * and original timestamp for diagnostic and logging purposes.
     *
     * @return a human-readable summary of this daily transaction record
     */
    @Override
    public String toString() {
        return "DailyTransaction{"
                + "id=" + id
                + ", dalytranId='" + dalytranId + '\''
                + ", typeCode='" + typeCode + '\''
                + ", categoryCode=" + categoryCode
                + ", amount=" + amount
                + ", cardNum='" + cardNum + '\''
                + ", origTimestamp='" + origTimestamp + '\''
                + '}';
    }
}
