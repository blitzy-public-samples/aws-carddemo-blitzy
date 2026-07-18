package com.aws.carddemo.dto;

import java.math.BigDecimal;

/**
 * Statement / report transaction data-transfer object.
 *
 * <p><strong>Origin:</strong> {@code legacy/cpy/COSTM01.CPY (TRNX-RECORD)} &mdash; a faithful,
 * behavior-preserving translation of the COBOL copybook {@code COSTM01.CPY} record
 * {@code TRNX-RECORD} ("Transaction altered Layout for use in reporting") from the AWS CardDemo
 * mainframe application.</p>
 *
 * <p>This layout is the transaction record shape consumed by the statement-generation batch
 * program {@code CBSTM03A} together with its I/O subprogram {@code CBSTM03B}. It is a
 * <em>non-persistent</em> read/format model used to build statements and reports; it is
 * deliberately NOT a JPA entity and carries no persistence annotations. Currency/edit-mask
 * formatting for display is the responsibility of the statement/report writer, not of this DTO
 * &mdash; values are held here unformatted.</p>
 *
 * <p><strong>Composite key.</strong> The COBOL group {@code TRNX-KEY} is a 32-byte composite of
 * {@code TRNX-CARD-NUM} (16) followed by {@code TRNX-ID} (16). Accordingly, {@link #getCardNum()
 * cardNum} is the FIRST half of the key and {@link #getId() id} is the SECOND half.
 * {@link #getKey()} exposes the concatenated key value, and {@link #equals(Object)} /
 * {@link #hashCode()} are based on these two fields, mirroring {@code TRNX-KEY}.</p>
 *
 * <p><strong>Distinct from {@code DailyTransaction}.</strong> Although this record carries the
 * same field set as {@code DALYTRAN-RECORD} (copybook {@code CVTRA06Y}, translated to
 * {@code com.aws.carddemo.dto.DailyTransaction}), the two are intentionally independent classes:
 * in {@code TRNX-RECORD} the composite key is reordered so that {@code CARD-NUM} precedes
 * {@code ID}. The two DTOs must not be shared or aliased.</p>
 *
 * <p><strong>Fixed-width layout (byte-exact; 1-based offsets; total = 350 bytes).</strong> These
 * offsets are authoritative for any fixed-width record mapper. The trailing 20-byte {@code FILLER}
 * is reserved pad and is intentionally not modeled as a property.</p>
 * <pre>
 *   COBOL field         COBOL PIC        Offset      Len  Java field
 *   ------------------  ---------------  ----------  ---  --------------------------
 *   TRNX-CARD-NUM       X(16)            1-16         16  String     cardNum
 *   TRNX-ID             X(16)            17-32        16  String     id
 *   TRNX-TYPE-CD        X(02)            33-34         2  String     typeCd
 *   TRNX-CAT-CD         9(04)            35-38         4  Integer    catCd
 *   TRNX-SOURCE         X(10)            39-48        10  String     source
 *   TRNX-DESC           X(100)           49-148      100  String     description
 *   TRNX-AMT            S9(09)V99        149-159      11  BigDecimal amount (scale 2)
 *   TRNX-MERCHANT-ID    9(09)            160-168       9  Long       merchantId
 *   TRNX-MERCHANT-NAME  X(50)            169-218      50  String     merchantName
 *   TRNX-MERCHANT-CITY  X(50)            219-268      50  String     merchantCity
 *   TRNX-MERCHANT-ZIP   X(10)            269-278      10  String     merchantZip
 *   TRNX-ORIG-TS        X(26)            279-304      26  String     origTs
 *   TRNX-PROC-TS        X(26)            305-330      26  String     procTs
 *   FILLER              X(20)            331-350      20  (not modeled - reserved pad)
 * </pre>
 *
 * <p><strong>Decimal fidelity.</strong> The monetary field {@code TRNX-AMT}
 * ({@code PIC S9(09)V99}) is modeled as {@link java.math.BigDecimal} with an intended scale of 2
 * (precision 11, scale 2). Floating-point types ({@code float}/{@code double}) are never used for
 * monetary values, preserving the exact fixed-scale decimal semantics of the source record.</p>
 *
 * <p>This is a plain Java object: no Lombok, no framework annotations, and self-contained apart
 * from {@link java.math.BigDecimal}.</p>
 */
public class StatementTransaction {

    /**
     * Card number; first half of the composite {@code TRNX-KEY}.
     * <p>COBOL {@code TRNX-CARD-NUM PIC X(16)}, offset 1-16. Fixed-width, 16 characters; leading
     * zeros are significant and must be preserved.</p>
     */
    private String cardNum;

    /**
     * Transaction identifier; second half of the composite {@code TRNX-KEY}.
     * <p>COBOL {@code TRNX-ID PIC X(16)}, offset 17-32. Fixed-width, 16 characters.</p>
     */
    private String id;

    /**
     * Transaction type code.
     * <p>COBOL {@code TRNX-TYPE-CD PIC X(02)}, offset 33-34.</p>
     */
    private String typeCd;

    /**
     * Transaction category code.
     * <p>COBOL {@code TRNX-CAT-CD PIC 9(04)}, offset 35-38. Unsigned four-digit numeric.</p>
     */
    private Integer catCd;

    /**
     * Transaction source.
     * <p>COBOL {@code TRNX-SOURCE PIC X(10)}, offset 39-48.</p>
     */
    private String source;

    /**
     * Transaction description.
     * <p>COBOL {@code TRNX-DESC PIC X(100)}, offset 49-148.</p>
     */
    private String description;

    /**
     * Transaction amount. Signed, two implied decimal places.
     * <p>COBOL {@code TRNX-AMT PIC S9(09)V99}, offset 149-159. Modeled as {@link BigDecimal} with
     * scale 2 (precision 11, scale 2). Never {@code float}/{@code double}.</p>
     */
    private BigDecimal amount;

    /**
     * Merchant identifier.
     * <p>COBOL {@code TRNX-MERCHANT-ID PIC 9(09)}, offset 160-168. Unsigned nine-digit numeric.</p>
     */
    private Long merchantId;

    /**
     * Merchant name.
     * <p>COBOL {@code TRNX-MERCHANT-NAME PIC X(50)}, offset 169-218.</p>
     */
    private String merchantName;

    /**
     * Merchant city.
     * <p>COBOL {@code TRNX-MERCHANT-CITY PIC X(50)}, offset 219-268.</p>
     */
    private String merchantCity;

    /**
     * Merchant ZIP / postal code.
     * <p>COBOL {@code TRNX-MERCHANT-ZIP PIC X(10)}, offset 269-278.</p>
     */
    private String merchantZip;

    /**
     * Original timestamp, retained as the raw 26-character source value.
     * <p>COBOL {@code TRNX-ORIG-TS PIC X(26)}, offset 279-304.</p>
     */
    private String origTs;

    /**
     * Processing timestamp, retained as the raw 26-character source value.
     * <p>COBOL {@code TRNX-PROC-TS PIC X(26)}, offset 305-330.</p>
     */
    private String procTs;

    /**
     * Creates an empty statement transaction with all fields unset.
     * <p>Provided so the record can be populated field-by-field by a fixed-width record mapper or
     * statement reader, mirroring how {@code CBSTM03A}/{@code CBSTM03B} build the record.</p>
     */
    public StatementTransaction() {
        // No-argument constructor; fields are populated via setters.
    }

    /**
     * Returns the composite key value ({@code TRNX-KEY}) as the concatenation of {@link #cardNum}
     * (first 16 bytes) followed by {@link #id} (next 16 bytes). A {@code null} component is treated
     * as an empty string so the result is never {@code null}.
     * <p>This is an additive convenience for the statement/report writer; it does not exist as a
     * discrete field in the source copybook.</p>
     *
     * @return the concatenated {@code cardNum + id} composite key; never {@code null}
     */
    public String getKey() {
        return (cardNum == null ? "" : cardNum) + (id == null ? "" : id);
    }

    /**
     * Returns the card number ({@code TRNX-CARD-NUM}, first half of the composite key).
     *
     * @return the card number, or {@code null} if unset
     */
    public String getCardNum() {
        return cardNum;
    }

    /**
     * Sets the card number ({@code TRNX-CARD-NUM}, first half of the composite key).
     *
     * @param cardNum the 16-character card number (leading zeros preserved)
     */
    public void setCardNum(String cardNum) {
        this.cardNum = cardNum;
    }

    /**
     * Returns the transaction identifier ({@code TRNX-ID}, second half of the composite key).
     *
     * @return the transaction id, or {@code null} if unset
     */
    public String getId() {
        return id;
    }

    /**
     * Sets the transaction identifier ({@code TRNX-ID}, second half of the composite key).
     *
     * @param id the 16-character transaction id
     */
    public void setId(String id) {
        this.id = id;
    }

    /**
     * Returns the transaction type code ({@code TRNX-TYPE-CD}).
     *
     * @return the type code, or {@code null} if unset
     */
    public String getTypeCd() {
        return typeCd;
    }

    /**
     * Sets the transaction type code ({@code TRNX-TYPE-CD}).
     *
     * @param typeCd the two-character type code
     */
    public void setTypeCd(String typeCd) {
        this.typeCd = typeCd;
    }

    /**
     * Returns the transaction category code ({@code TRNX-CAT-CD}).
     *
     * @return the category code, or {@code null} if unset
     */
    public Integer getCatCd() {
        return catCd;
    }

    /**
     * Sets the transaction category code ({@code TRNX-CAT-CD}).
     *
     * @param catCd the four-digit category code
     */
    public void setCatCd(Integer catCd) {
        this.catCd = catCd;
    }

    /**
     * Returns the transaction source ({@code TRNX-SOURCE}).
     *
     * @return the source, or {@code null} if unset
     */
    public String getSource() {
        return source;
    }

    /**
     * Sets the transaction source ({@code TRNX-SOURCE}).
     *
     * @param source the ten-character source
     */
    public void setSource(String source) {
        this.source = source;
    }

    /**
     * Returns the transaction description ({@code TRNX-DESC}).
     *
     * @return the description, or {@code null} if unset
     */
    public String getDescription() {
        return description;
    }

    /**
     * Sets the transaction description ({@code TRNX-DESC}).
     *
     * @param description the up-to-100-character description
     */
    public void setDescription(String description) {
        this.description = description;
    }

    /**
     * Returns the transaction amount ({@code TRNX-AMT}) as a {@link BigDecimal} of scale 2.
     *
     * @return the amount, or {@code null} if unset
     */
    public BigDecimal getAmount() {
        return amount;
    }

    /**
     * Sets the transaction amount ({@code TRNX-AMT}). The value is stored as provided; callers are
     * responsible for supplying a {@link BigDecimal} of scale 2 to preserve fixed-scale semantics.
     *
     * @param amount the signed amount with two decimal places
     */
    public void setAmount(BigDecimal amount) {
        this.amount = amount;
    }

    /**
     * Returns the merchant identifier ({@code TRNX-MERCHANT-ID}).
     *
     * @return the merchant id, or {@code null} if unset
     */
    public Long getMerchantId() {
        return merchantId;
    }

    /**
     * Sets the merchant identifier ({@code TRNX-MERCHANT-ID}).
     *
     * @param merchantId the nine-digit merchant id
     */
    public void setMerchantId(Long merchantId) {
        this.merchantId = merchantId;
    }

    /**
     * Returns the merchant name ({@code TRNX-MERCHANT-NAME}).
     *
     * @return the merchant name, or {@code null} if unset
     */
    public String getMerchantName() {
        return merchantName;
    }

    /**
     * Sets the merchant name ({@code TRNX-MERCHANT-NAME}).
     *
     * @param merchantName the up-to-50-character merchant name
     */
    public void setMerchantName(String merchantName) {
        this.merchantName = merchantName;
    }

    /**
     * Returns the merchant city ({@code TRNX-MERCHANT-CITY}).
     *
     * @return the merchant city, or {@code null} if unset
     */
    public String getMerchantCity() {
        return merchantCity;
    }

    /**
     * Sets the merchant city ({@code TRNX-MERCHANT-CITY}).
     *
     * @param merchantCity the up-to-50-character merchant city
     */
    public void setMerchantCity(String merchantCity) {
        this.merchantCity = merchantCity;
    }

    /**
     * Returns the merchant ZIP / postal code ({@code TRNX-MERCHANT-ZIP}).
     *
     * @return the merchant ZIP, or {@code null} if unset
     */
    public String getMerchantZip() {
        return merchantZip;
    }

    /**
     * Sets the merchant ZIP / postal code ({@code TRNX-MERCHANT-ZIP}).
     *
     * @param merchantZip the ten-character merchant ZIP
     */
    public void setMerchantZip(String merchantZip) {
        this.merchantZip = merchantZip;
    }

    /**
     * Returns the original timestamp ({@code TRNX-ORIG-TS}) as the raw 26-character source value.
     *
     * @return the original timestamp string, or {@code null} if unset
     */
    public String getOrigTs() {
        return origTs;
    }

    /**
     * Sets the original timestamp ({@code TRNX-ORIG-TS}).
     *
     * @param origTs the raw 26-character original timestamp
     */
    public void setOrigTs(String origTs) {
        this.origTs = origTs;
    }

    /**
     * Returns the processing timestamp ({@code TRNX-PROC-TS}) as the raw 26-character source value.
     *
     * @return the processing timestamp string, or {@code null} if unset
     */
    public String getProcTs() {
        return procTs;
    }

    /**
     * Sets the processing timestamp ({@code TRNX-PROC-TS}).
     *
     * @param procTs the raw 26-character processing timestamp
     */
    public void setProcTs(String procTs) {
        this.procTs = procTs;
    }

    /**
     * Indicates whether another object is "equal to" this one based on the composite key
     * components ({@link #cardNum} and {@link #id}), mirroring the COBOL {@code TRNX-KEY}.
     *
     * @param o the reference object with which to compare
     * @return {@code true} if {@code o} is a {@code StatementTransaction} with the same composite
     *         key; {@code false} otherwise
     */
    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }
        StatementTransaction that = (StatementTransaction) o;
        return java.util.Objects.equals(cardNum, that.cardNum)
                && java.util.Objects.equals(id, that.id);
    }

    /**
     * Returns a hash code derived from the composite key components ({@link #cardNum} and
     * {@link #id}), consistent with {@link #equals(Object)}.
     *
     * @return the composite-key hash code
     */
    @Override
    public int hashCode() {
        return java.util.Objects.hash(cardNum, id);
    }

    /**
     * Returns a diagnostic string containing every modeled field. Intended for logging and
     * debugging only; this is not a statement/report rendering, since display formatting is the
     * responsibility of the statement/report writer.
     *
     * @return a string representation of this statement transaction
     */
    @Override
    public String toString() {
        return "StatementTransaction{"
                + "cardNum='" + cardNum + '\''
                + ", id='" + id + '\''
                + ", typeCd='" + typeCd + '\''
                + ", catCd=" + catCd
                + ", source='" + source + '\''
                + ", description='" + description + '\''
                + ", amount=" + amount
                + ", merchantId=" + merchantId
                + ", merchantName='" + merchantName + '\''
                + ", merchantCity='" + merchantCity + '\''
                + ", merchantZip='" + merchantZip + '\''
                + ", origTs='" + origTs + '\''
                + ", procTs='" + procTs + '\''
                + '}';
    }
}
