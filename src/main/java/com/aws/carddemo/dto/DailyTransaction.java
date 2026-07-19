package com.aws.carddemo.dto;

import java.math.BigDecimal;

/**
 * Daily-transaction input-feed record for the AWS CardDemo batch pipeline.
 *
 * <p><strong>Origin:</strong> {@code legacy/cpy/CVTRA06Y.cpy} (COBOL record
 * {@code DALYTRAN-RECORD}, fixed record length {@code RECLN=350}). This class is
 * a faithful translation of that copybook produced during the COBOL to
 * Java/Spring Boot migration of AWS CardDemo.</p>
 *
 * <p><strong>Role:</strong> this is the daily-transaction input feed consumed by
 * the batch posting and backup programs (legacy {@code CBTRN02C} and
 * {@code CBTRN01C}). In the target architecture it is read from the sequential
 * {@code DALYTRAN} file by a Spring Batch {@code FlatFileItemReader<DailyTransaction>}.</p>
 *
 * <p><strong>Non-persistent:</strong> this is a plain data-transfer object. It is
 * intentionally <em>not</em> a JPA entity and carries no persistence annotations;
 * it never touches the database directly. The batch processor converts it into the
 * persistent {@code Transaction} entity.</p>
 *
 * <p><strong>FixedWidthRecordMapper pairing:</strong> this DTO holds the record's
 * fields as typed Java values and performs the typed modeling only. It deliberately
 * contains no byte-offset parsing logic. The sibling utility
 * {@code com.aws.carddemo.util.FixedWidthRecordMapper} (plus the {@code batch} layer)
 * performs the byte-exact fixed-width read/write using a per-layout field-definition
 * list whose canonical layout oracle is the 350-byte {@code DALYTRAN} record documented
 * below. The reject-file writer ({@code DALYREJS} = 430 bytes = 350-byte transaction
 * prefix + 80-byte reason) reuses this same 350-byte prefix. The mapper imports and
 * consumes this DTO; this DTO imports nothing from other project packages.</p>
 *
 * <p><strong>Byte-exact layout</strong> (offsets are 1-based; the trailing 20-byte
 * {@code FILLER} is reserved pad and is intentionally not modeled as a property):</p>
 *
 * <pre>
 * 01  DALYTRAN-RECORD.                         (total = 350 bytes)
 *     05  DALYTRAN-ID            PIC X(16).    pos   1- 16  (16)
 *     05  DALYTRAN-TYPE-CD       PIC X(02).    pos  17- 18  ( 2)
 *     05  DALYTRAN-CAT-CD        PIC 9(04).    pos  19- 22  ( 4)
 *     05  DALYTRAN-SOURCE        PIC X(10).    pos  23- 32  (10)
 *     05  DALYTRAN-DESC          PIC X(100).   pos  33-132  (100)
 *     05  DALYTRAN-AMT           PIC S9(09)V99 pos 133-143  (11)  signed, 2 implied decimals
 *     05  DALYTRAN-MERCHANT-ID   PIC 9(09).    pos 144-152  ( 9)
 *     05  DALYTRAN-MERCHANT-NAME PIC X(50).    pos 153-202  (50)
 *     05  DALYTRAN-MERCHANT-CITY PIC X(50).    pos 203-252  (50)
 *     05  DALYTRAN-MERCHANT-ZIP  PIC X(10).    pos 253-262  (10)
 *     05  DALYTRAN-CARD-NUM      PIC X(16).    pos 263-278  (16)
 *     05  DALYTRAN-ORIG-TS       PIC X(26).    pos 279-304  (26)
 *     05  DALYTRAN-PROC-TS       PIC X(26).    pos 305-330  (26)
 *     05  FILLER                 PIC X(20).    pos 331-350  (20)  reserved pad (not modeled)
 * </pre>
 *
 * <p>Field widths sum: 16 + 2 + 4 + 10 + 100 + 11 + 9 + 50 + 50 + 10 + 16 + 26 + 26 + 20 = 350.</p>
 *
 * <p><strong>Decimal fidelity:</strong> the monetary field {@link #amount}
 * ({@code DALYTRAN-AMT S9(09)V99}) is modeled as {@link java.math.BigDecimal} with an
 * intended scale of 2 (precision 11). Floating-point types ({@code float}/{@code double})
 * are never used for monetary values. This DTO does not round or reformat the value;
 * scale and rounding decisions are made by the batch processor per Agent Action Plan
 * section 0.6.1 when the value is promoted to the {@code Transaction} entity.</p>
 *
 * <p><strong>Timestamps:</strong> {@link #origTs} and {@link #procTs} are preserved as
 * their raw 26-character strings rather than parsed into {@code java.time} types, so the
 * feed record stays byte-faithful; the batch processor performs the conversion downstream.</p>
 */
public class DailyTransaction {

    /**
     * Transaction identifier.
     * <p>COBOL: {@code DALYTRAN-ID PIC X(16)} at bytes 1-16 (16 chars).</p>
     */
    private String id;

    /**
     * Transaction type code.
     * <p>COBOL: {@code DALYTRAN-TYPE-CD PIC X(02)} at bytes 17-18 (2 chars).</p>
     */
    private String typeCd;

    /**
     * Transaction category code.
     * <p>COBOL: {@code DALYTRAN-CAT-CD PIC 9(04)} at bytes 19-22 (4 digits).</p>
     */
    private Integer catCd;

    /**
     * Transaction source.
     * <p>COBOL: {@code DALYTRAN-SOURCE PIC X(10)} at bytes 23-32 (10 chars).</p>
     */
    private String source;

    /**
     * Transaction description.
     * <p>COBOL: {@code DALYTRAN-DESC PIC X(100)} at bytes 33-132 (100 chars).</p>
     */
    private String description;

    /**
     * Transaction amount. Signed, two implied decimal places.
     * <p>COBOL: {@code DALYTRAN-AMT PIC S9(09)V99} at bytes 133-143 (11 chars);
     * modeled as {@link java.math.BigDecimal} (scale 2, precision 11). Never
     * {@code float}/{@code double}.</p>
     */
    private BigDecimal amount;

    /**
     * Merchant identifier.
     * <p>COBOL: {@code DALYTRAN-MERCHANT-ID PIC 9(09)} at bytes 144-152 (9 digits).</p>
     */
    private Long merchantId;

    /**
     * Merchant name.
     * <p>COBOL: {@code DALYTRAN-MERCHANT-NAME PIC X(50)} at bytes 153-202 (50 chars).</p>
     */
    private String merchantName;

    /**
     * Merchant city.
     * <p>COBOL: {@code DALYTRAN-MERCHANT-CITY PIC X(50)} at bytes 203-252 (50 chars).</p>
     */
    private String merchantCity;

    /**
     * Merchant ZIP code.
     * <p>COBOL: {@code DALYTRAN-MERCHANT-ZIP PIC X(10)} at bytes 253-262 (10 chars).</p>
     */
    private String merchantZip;

    /**
     * Card number. Preserved as a fixed-width string to retain leading zeros.
     * <p>COBOL: {@code DALYTRAN-CARD-NUM PIC X(16)} at bytes 263-278 (16 chars).</p>
     */
    private String cardNum;

    /**
     * Raw origination timestamp (unparsed 26-character string).
     * <p>COBOL: {@code DALYTRAN-ORIG-TS PIC X(26)} at bytes 279-304 (26 chars).</p>
     */
    private String origTs;

    /**
     * Raw processing timestamp (unparsed 26-character string).
     * <p>COBOL: {@code DALYTRAN-PROC-TS PIC X(26)} at bytes 305-330 (26 chars).</p>
     */
    private String procTs;

    /**
     * Creates an empty daily-transaction record.
     * <p>The public no-argument constructor is required so that Spring Batch's
     * {@code FlatFileItemReader} field-set mapping (and general bean instantiation)
     * can construct instances reflectively before populating fields via setters.</p>
     */
    public DailyTransaction() {
        // No-argument constructor for bean/field-set instantiation.
    }

    /**
     * Creates a fully populated daily-transaction record.
     *
     * <p>Provided as a convenience for constructing records in the batch layer and in
     * tests. Field order follows the byte layout of {@code DALYTRAN-RECORD}.</p>
     *
     * @param id           transaction identifier ({@code DALYTRAN-ID})
     * @param typeCd       transaction type code ({@code DALYTRAN-TYPE-CD})
     * @param catCd        transaction category code ({@code DALYTRAN-CAT-CD})
     * @param source       transaction source ({@code DALYTRAN-SOURCE})
     * @param description  transaction description ({@code DALYTRAN-DESC})
     * @param amount       transaction amount ({@code DALYTRAN-AMT}); must not be
     *                     represented with floating point
     * @param merchantId   merchant identifier ({@code DALYTRAN-MERCHANT-ID})
     * @param merchantName merchant name ({@code DALYTRAN-MERCHANT-NAME})
     * @param merchantCity merchant city ({@code DALYTRAN-MERCHANT-CITY})
     * @param merchantZip  merchant ZIP code ({@code DALYTRAN-MERCHANT-ZIP})
     * @param cardNum      card number ({@code DALYTRAN-CARD-NUM})
     * @param origTs       raw origination timestamp ({@code DALYTRAN-ORIG-TS})
     * @param procTs       raw processing timestamp ({@code DALYTRAN-PROC-TS})
     */
    public DailyTransaction(String id,
                            String typeCd,
                            Integer catCd,
                            String source,
                            String description,
                            BigDecimal amount,
                            Long merchantId,
                            String merchantName,
                            String merchantCity,
                            String merchantZip,
                            String cardNum,
                            String origTs,
                            String procTs) {
        this.id = id;
        this.typeCd = typeCd;
        this.catCd = catCd;
        this.source = source;
        this.description = description;
        this.amount = amount;
        this.merchantId = merchantId;
        this.merchantName = merchantName;
        this.merchantCity = merchantCity;
        this.merchantZip = merchantZip;
        this.cardNum = cardNum;
        this.origTs = origTs;
        this.procTs = procTs;
    }

    /**
     * Returns the transaction identifier ({@code DALYTRAN-ID}).
     *
     * @return the transaction identifier, or {@code null} if unset
     */
    public String getId() {
        return id;
    }

    /**
     * Sets the transaction identifier ({@code DALYTRAN-ID}).
     *
     * @param id the transaction identifier
     */
    public void setId(String id) {
        this.id = id;
    }

    /**
     * Returns the transaction type code ({@code DALYTRAN-TYPE-CD}).
     *
     * @return the transaction type code, or {@code null} if unset
     */
    public String getTypeCd() {
        return typeCd;
    }

    /**
     * Sets the transaction type code ({@code DALYTRAN-TYPE-CD}).
     *
     * @param typeCd the transaction type code
     */
    public void setTypeCd(String typeCd) {
        this.typeCd = typeCd;
    }

    /**
     * Returns the transaction category code ({@code DALYTRAN-CAT-CD}).
     *
     * @return the transaction category code, or {@code null} if unset
     */
    public Integer getCatCd() {
        return catCd;
    }

    /**
     * Sets the transaction category code ({@code DALYTRAN-CAT-CD}).
     *
     * @param catCd the transaction category code
     */
    public void setCatCd(Integer catCd) {
        this.catCd = catCd;
    }

    /**
     * Returns the transaction source ({@code DALYTRAN-SOURCE}).
     *
     * @return the transaction source, or {@code null} if unset
     */
    public String getSource() {
        return source;
    }

    /**
     * Sets the transaction source ({@code DALYTRAN-SOURCE}).
     *
     * @param source the transaction source
     */
    public void setSource(String source) {
        this.source = source;
    }

    /**
     * Returns the transaction description ({@code DALYTRAN-DESC}).
     *
     * @return the transaction description, or {@code null} if unset
     */
    public String getDescription() {
        return description;
    }

    /**
     * Sets the transaction description ({@code DALYTRAN-DESC}).
     *
     * @param description the transaction description
     */
    public void setDescription(String description) {
        this.description = description;
    }

    /**
     * Returns the transaction amount ({@code DALYTRAN-AMT}).
     *
     * @return the transaction amount as a {@link java.math.BigDecimal}, or
     *         {@code null} if unset
     */
    public BigDecimal getAmount() {
        return amount;
    }

    /**
     * Sets the transaction amount ({@code DALYTRAN-AMT}).
     * <p>The value is stored as provided; this DTO performs no scaling or rounding.
     * Scale and rounding are applied by the batch processor per Agent Action Plan
     * section 0.6.1.</p>
     *
     * @param amount the transaction amount as a {@link java.math.BigDecimal}
     */
    public void setAmount(BigDecimal amount) {
        this.amount = amount;
    }

    /**
     * Returns the merchant identifier ({@code DALYTRAN-MERCHANT-ID}).
     *
     * @return the merchant identifier, or {@code null} if unset
     */
    public Long getMerchantId() {
        return merchantId;
    }

    /**
     * Sets the merchant identifier ({@code DALYTRAN-MERCHANT-ID}).
     *
     * @param merchantId the merchant identifier
     */
    public void setMerchantId(Long merchantId) {
        this.merchantId = merchantId;
    }

    /**
     * Returns the merchant name ({@code DALYTRAN-MERCHANT-NAME}).
     *
     * @return the merchant name, or {@code null} if unset
     */
    public String getMerchantName() {
        return merchantName;
    }

    /**
     * Sets the merchant name ({@code DALYTRAN-MERCHANT-NAME}).
     *
     * @param merchantName the merchant name
     */
    public void setMerchantName(String merchantName) {
        this.merchantName = merchantName;
    }

    /**
     * Returns the merchant city ({@code DALYTRAN-MERCHANT-CITY}).
     *
     * @return the merchant city, or {@code null} if unset
     */
    public String getMerchantCity() {
        return merchantCity;
    }

    /**
     * Sets the merchant city ({@code DALYTRAN-MERCHANT-CITY}).
     *
     * @param merchantCity the merchant city
     */
    public void setMerchantCity(String merchantCity) {
        this.merchantCity = merchantCity;
    }

    /**
     * Returns the merchant ZIP code ({@code DALYTRAN-MERCHANT-ZIP}).
     *
     * @return the merchant ZIP code, or {@code null} if unset
     */
    public String getMerchantZip() {
        return merchantZip;
    }

    /**
     * Sets the merchant ZIP code ({@code DALYTRAN-MERCHANT-ZIP}).
     *
     * @param merchantZip the merchant ZIP code
     */
    public void setMerchantZip(String merchantZip) {
        this.merchantZip = merchantZip;
    }

    /**
     * Returns the card number ({@code DALYTRAN-CARD-NUM}).
     *
     * @return the card number, or {@code null} if unset
     */
    public String getCardNum() {
        return cardNum;
    }

    /**
     * Sets the card number ({@code DALYTRAN-CARD-NUM}).
     *
     * @param cardNum the card number
     */
    public void setCardNum(String cardNum) {
        this.cardNum = cardNum;
    }

    /**
     * Returns the raw origination timestamp ({@code DALYTRAN-ORIG-TS}).
     *
     * @return the raw 26-character origination timestamp, or {@code null} if unset
     */
    public String getOrigTs() {
        return origTs;
    }

    /**
     * Sets the raw origination timestamp ({@code DALYTRAN-ORIG-TS}).
     *
     * @param origTs the raw 26-character origination timestamp
     */
    public void setOrigTs(String origTs) {
        this.origTs = origTs;
    }

    /**
     * Returns the raw processing timestamp ({@code DALYTRAN-PROC-TS}).
     *
     * @return the raw 26-character processing timestamp, or {@code null} if unset
     */
    public String getProcTs() {
        return procTs;
    }

    /**
     * Sets the raw processing timestamp ({@code DALYTRAN-PROC-TS}).
     *
     * @param procTs the raw 26-character processing timestamp
     */
    public void setProcTs(String procTs) {
        this.procTs = procTs;
    }

    /**
     * Returns a non-sensitive diagnostic representation containing only the class name and an opaque
     * per-instance identity token.
     *
     * <p>The daily-transaction feed carries the full unmasked card number ({@code DALYTRAN-CARD-NUM}),
     * merchant data, and monetary amount. Emitting those into logs or error messages would leak PAN
     * and cardholder data (CWE-532), so no business field is ever rendered here (review finding F9).
     * Callers that genuinely need field values must read the typed accessors explicitly rather than
     * interpolate the object.</p>
     *
     * @return a non-sensitive string representation
     */
    @Override
    public String toString() {
        return "DailyTransaction@" + Integer.toHexString(System.identityHashCode(this));
    }

    /**
     * Compares two records for logical equality on the transaction identifier ({@code id},
     * i.e. {@code DALYTRAN-ID} / {@code TRAN-ID}) alone.
     *
     * <p>The legacy {@code TRANSACT} file and the target {@code Transaction} entity are keyed by the
     * transaction id alone, so posting identity is the transaction id and nothing else. Two records
     * with a {@code null} id are never considered equal, so malformed feed records are not collapsed
     * and this equality is safe to use for de-duplication before persistence (review finding F19).</p>
     *
     * @param o the object to compare with
     * @return {@code true} if the other object is a {@code DailyTransaction} with the same non-null
     *         {@code id}; {@code false} otherwise
     */
    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof DailyTransaction other)) {
            return false;
        }
        return id != null && id.equals(other.id);
    }

    /**
     * Returns a hash code consistent with {@link #equals(Object)}, derived from the transaction
     * identifier ({@code id}) alone.
     *
     * @return the hash code for this record ({@code 0} when {@code id} is {@code null})
     */
    @Override
    public int hashCode() {
        return (id == null) ? 0 : id.hashCode();
    }
}
