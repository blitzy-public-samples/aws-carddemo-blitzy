package com.carddemo.ledger.entity;

import com.carddemo.events.EventEnvelope;
import static com.carddemo.cobol.PicClause.TRAN_AMT_PRECISION;
import static com.carddemo.cobol.PicClause.TRAN_AMT_SCALE;
import static com.carddemo.cobol.PicClause.TRAN_CARD_NUM_WIDTH;
import static com.carddemo.cobol.PicClause.TRAN_CAT_CD_WIDTH;
import static com.carddemo.cobol.PicClause.TRAN_DESC_WIDTH;
import static com.carddemo.cobol.PicClause.TRAN_ID_WIDTH;
import static com.carddemo.cobol.PicClause.TRAN_MERCHANT_CITY_WIDTH;
import static com.carddemo.cobol.PicClause.TRAN_MERCHANT_ID_WIDTH;
import static com.carddemo.cobol.PicClause.TRAN_MERCHANT_NAME_WIDTH;
import static com.carddemo.cobol.PicClause.TRAN_MERCHANT_ZIP_WIDTH;
import static com.carddemo.cobol.PicClause.TRAN_ORIG_TS_WIDTH;
import static com.carddemo.cobol.PicClause.TRAN_PROC_TS_WIDTH;
import static com.carddemo.cobol.PicClause.TRAN_SOURCE_WIDTH;
import static com.carddemo.cobol.PicClause.TRAN_TYPE_CD_WIDTH;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import java.math.BigDecimal;
import java.util.Objects;
import java.util.regex.Pattern;
import org.springframework.data.domain.Persistable;

/**
 * One posted transaction: the thirteen fields of the 350-byte {@code TRAN-RECORD} at
 * {@code app/cpy/CVTRA05Y.cpy:L5-L17}, held in the table {@code transaction}.
 *
 * <p>The primary key is the sixteen-character transaction identifier, whose width comes from
 * {@code KEYS(16 0)} at {@code app/jcl/TRANFILE.jcl:L53}. A non-unique secondary index covers
 * {@code processed_timestamp}, from {@code KEYS(26 304)} and {@code NONUNIQUEKEY} at
 * {@code app/jcl/TRANFILE.jcl:L84-L85}.</p>
 *
 * <p>Field to column, in Common Business Oriented Language (COBOL) copybook order:</p>
 *
 * <pre>
 * TRAN-ID             PIC X(16)      transaction_id       VARCHAR(16)
 * TRAN-TYPE-CD        PIC X(02)      type_code            CHAR(2)
 * TRAN-CAT-CD         PIC 9(04)      category_code        VARCHAR(4)
 * TRAN-SOURCE         PIC X(10)      source               VARCHAR(10)
 * TRAN-DESC           PIC X(100)     description          VARCHAR(100)
 * TRAN-AMT            PIC S9(09)V99  amount               NUMERIC(11,2)
 * TRAN-MERCHANT-ID    PIC 9(09)      merchant_id          VARCHAR(9)
 * TRAN-MERCHANT-NAME  PIC X(50)      merchant_name        VARCHAR(50)
 * TRAN-MERCHANT-CITY  PIC X(50)      merchant_city        VARCHAR(50)
 * TRAN-MERCHANT-ZIP   PIC X(10)      merchant_zip         VARCHAR(10)
 * TRAN-CARD-NUM       PIC X(16)      card_number          VARCHAR(16)
 * TRAN-ORIG-TS        PIC X(26)      origin_timestamp     CHAR(26)
 * TRAN-PROC-TS        PIC X(26)      processed_timestamp  CHAR(26)
 * </pre>
 *
 * <p>Four terms bind every caller. {@code cardNumber} holds the masked form, twelve asterisks and
 * the last four digits of the Primary Account Number (PAN). {@code originTimestamp} holds
 * twenty-six characters shaped {@code YYYY-MM-DD hh:mm:ss.ffffff}. {@code processedTimestamp}
 * holds twenty-six characters shaped {@code YYYY-MM-DD-hh.mm.ss.ff0000}, the form
 * {@code app/cbl/CBTRN02C.cbl:L701} builds and {@code app/cbl/CBTRN02C.cbl:L438} stores.
 * {@code amount} carries exactly two decimal places, and a negative value on a refund.</p>
 *
 * <p>{@code type_code}, {@code origin_timestamp} and {@code processed_timestamp} are blank-padded
 * fixed-width character columns. Nine columns hold variable-width text, and {@code amount} holds a
 * fixed-point decimal. Every field is required, and every accessor is read-only. The trailing
 * {@code FILLER PIC X(20)} at {@code app/cpy/CVTRA05Y.cpy:L18} carries no column.</p>
 *
 * <p><b>Insert only.</b> {@link #isNew()} answers {@code true} for every instance, so a store
 * inserts and never updates. {@code 2900-WRITE-TRANSACTION-FILE} at
 * {@code app/cbl/CBTRN02C.cbl:L562-L579} issues {@code WRITE} and no paragraph of
 * {@code app/cbl/} issues {@code REWRITE} or {@code DELETE} against the transaction master. A
 * second store of an identifier the table already holds therefore raises a primary-key violation
 * and rolls the whole posting transaction back, rather than replacing the stored row with the
 * arriving one and leaving the two balance updates it already applied in place.</p>
 */
@Entity
@Table(
        name = "transaction",
        indexes = @Index(name = "idx_transaction_processed_timestamp",
                columnList = "processed_timestamp"))
public class TransactionEntity implements Persistable<String> {

    /**
     * Shape of the value {@code cardNumber} holds: twelve asterisks and four digits. The
     * card-facing services publish this form, and the full sixteen-digit Primary Account Number
     * reaches no row of the {@code transaction} table.
     */
    public static final String MASKED_CARD_NUMBER_PATTERN = "^\\*{12}[0-9]{4}$";

    /** Compiled form of {@link #MASKED_CARD_NUMBER_PATTERN}. */
    private static final Pattern MASKED_CARD_NUMBER = Pattern.compile(MASKED_CARD_NUMBER_PATTERN);

    /**
     * Primary key. {@code TRAN-ID PIC X(16)} at {@code app/cpy/CVTRA05Y.cpy:L5}.
     *
     * <p>Column {@code transaction_id VARCHAR(16) NOT NULL}. The mapping states the width and the
     * nullability rather than leaving the provider to describe them. A drift between this field and
     * the migration therefore fails the start-up check that {@code ddl-auto: validate} runs.
     *
     * <p>The Picture clause is alphanumeric, so any character may occupy any of the sixteen
     * positions, and the constructor tests the width alone.
     */
    @Id
    @Column(name = "transaction_id", nullable = false, length = TRAN_ID_WIDTH)
    private String transactionId;

    @Column(name = "type_code", columnDefinition = "bpchar(" + TRAN_TYPE_CD_WIDTH + ")",
            nullable = false)
    private String typeCode;

    @Column(name = "category_code", length = TRAN_CAT_CD_WIDTH, nullable = false)
    private String categoryCode;

    @Column(name = "source", length = TRAN_SOURCE_WIDTH, nullable = false)
    private String source;

    @Column(name = "description", length = TRAN_DESC_WIDTH, nullable = false)
    private String description;

    @Column(name = "amount", precision = TRAN_AMT_PRECISION, scale = TRAN_AMT_SCALE,
            nullable = false)
    private BigDecimal amount;

    @Column(name = "merchant_id", length = TRAN_MERCHANT_ID_WIDTH, nullable = false)
    private String merchantId;

    @Column(name = "merchant_name", length = TRAN_MERCHANT_NAME_WIDTH, nullable = false)
    private String merchantName;

    @Column(name = "merchant_city", length = TRAN_MERCHANT_CITY_WIDTH, nullable = false)
    private String merchantCity;

    @Column(name = "merchant_zip", length = TRAN_MERCHANT_ZIP_WIDTH, nullable = false)
    private String merchantZip;

    @Column(name = "card_number", length = TRAN_CARD_NUM_WIDTH, nullable = false)
    private String cardNumber;

    @Column(name = "origin_timestamp", columnDefinition = "bpchar(" + TRAN_ORIG_TS_WIDTH + ")",
            nullable = false)
    private String originTimestamp;

    @Column(name = "processed_timestamp", columnDefinition = "bpchar(" + TRAN_PROC_TS_WIDTH + ")",
            nullable = false)
    private String processedTimestamp;

    protected TransactionEntity() {
    }

    /**
     * Builds one row from the thirteen field values, in copybook order.
     *
     * @param transactionId      {@code TRAN-ID} at {@code app/cpy/CVTRA05Y.cpy:L5}
     * @param typeCode           {@code TRAN-TYPE-CD} at {@code app/cpy/CVTRA05Y.cpy:L6}
     * @param categoryCode       {@code TRAN-CAT-CD} at {@code app/cpy/CVTRA05Y.cpy:L7}
     * @param source             {@code TRAN-SOURCE} at {@code app/cpy/CVTRA05Y.cpy:L8}
     * @param description        {@code TRAN-DESC} at {@code app/cpy/CVTRA05Y.cpy:L9}
     * @param amount             {@code TRAN-AMT} at {@code app/cpy/CVTRA05Y.cpy:L10}, scaled to
     *                           two decimal places, negative on a refund
     * @param merchantId         {@code TRAN-MERCHANT-ID} at {@code app/cpy/CVTRA05Y.cpy:L11}
     * @param merchantName       {@code TRAN-MERCHANT-NAME} at {@code app/cpy/CVTRA05Y.cpy:L12}
     * @param merchantCity       {@code TRAN-MERCHANT-CITY} at {@code app/cpy/CVTRA05Y.cpy:L13}
     * @param merchantZip        {@code TRAN-MERCHANT-ZIP} at {@code app/cpy/CVTRA05Y.cpy:L14}
     * @param cardNumber         the masked form of {@code TRAN-CARD-NUM} at
     *                           {@code app/cpy/CVTRA05Y.cpy:L15}, matching
     *                           {@link #MASKED_CARD_NUMBER_PATTERN}
     * @param originTimestamp    {@code TRAN-ORIG-TS} at {@code app/cpy/CVTRA05Y.cpy:L16}
     * @param processedTimestamp {@code TRAN-PROC-TS} at {@code app/cpy/CVTRA05Y.cpy:L17}
     * @throws NullPointerException     when any argument is {@code null}
     * @throws IllegalArgumentException when {@code transactionId} is not exactly
     *                                  {@value com.carddemo.cobol.PicClause#TRAN_ID_WIDTH}
     *                                  characters wide, when {@code amount} carries any other
     *                                  number of decimal places, or when {@code cardNumber} is
     *                                  unmasked
     */
    public TransactionEntity(String transactionId, String typeCode, String categoryCode,
            String source, String description, BigDecimal amount, String merchantId,
            String merchantName, String merchantCity, String merchantZip, String cardNumber,
            String originTimestamp, String processedTimestamp) {
        this.transactionId = requireTransactionIdWidth(transactionId);
        this.typeCode = Objects.requireNonNull(typeCode, "typeCode is required");
        this.categoryCode = Objects.requireNonNull(categoryCode, "categoryCode is required");
        this.source = Objects.requireNonNull(source, "source is required");
        this.description = Objects.requireNonNull(description, "description is required");
        this.amount = requireTwoDecimalPlaces(amount);
        this.merchantId = Objects.requireNonNull(merchantId, "merchantId is required");
        this.merchantName = Objects.requireNonNull(merchantName, "merchantName is required");
        this.merchantCity = Objects.requireNonNull(merchantCity, "merchantCity is required");
        this.merchantZip = Objects.requireNonNull(merchantZip, "merchantZip is required");
        this.cardNumber = requireMasked(cardNumber);
        this.originTimestamp =
                Objects.requireNonNull(originTimestamp, "originTimestamp is required");
        this.processedTimestamp =
                Objects.requireNonNull(processedTimestamp, "processedTimestamp is required");
    }

    /**
     * Checks that an amount already carries the two decimal places of
     * {@code TRAN-AMT PIC S9(09)V99}. The caller supplies the scaled value.
     *
     * @param amount the monetary value to check
     * @return the same value
     * @throws NullPointerException     when {@code amount} is {@code null}
     * @throws IllegalArgumentException when {@code amount} carries any other scale
     */
    private static BigDecimal requireTwoDecimalPlaces(BigDecimal amount) {
        Objects.requireNonNull(amount, "amount is required");
        if (amount.scale() != TRAN_AMT_SCALE) {
            throw new IllegalArgumentException("amount must carry a scale of " + TRAN_AMT_SCALE
                    + ", found a scale of " + amount.scale());
        }
        return amount;
    }

    /**
     * Checks that a transaction identifier carries exactly the width the source record declares.
     *
     * <p>{@code TRAN-ID PIC X(16)} at {@code app/cpy/CVTRA05Y.cpy:L5} is a fixed sixteen-character
     * field, and the column holds the same width. A shorter or longer value fails here rather than
     * reaching the row, where it would either be rejected by the database or silently pad the key.
     *
     * @param transactionId the value to check
     * @return the same value when it carries the declared width
     * @throws NullPointerException     when {@code transactionId} is {@code null}
     * @throws IllegalArgumentException when {@code transactionId} is not exactly
     *                                  {@value com.carddemo.cobol.PicClause#TRAN_ID_WIDTH}
     *                                  characters wide
     */
    private static String requireTransactionIdWidth(String transactionId) {
        Objects.requireNonNull(transactionId, "transactionId is required");
        if (transactionId.length() != TRAN_ID_WIDTH) {
            throw new IllegalArgumentException("transactionId must be exactly " + TRAN_ID_WIDTH
                    + " characters wide, found width " + transactionId.length());
        }
        return transactionId;
    }

    /**
     * Rejects a card number that is not already masked.
     *
     * @param cardNumber the card number to check
     * @return the same value when it carries the masked form
     * @throws NullPointerException     when {@code cardNumber} is {@code null}
     * @throws IllegalArgumentException when {@code cardNumber} is unmasked
     */
    private static String requireMasked(String cardNumber) {
        Objects.requireNonNull(cardNumber, "cardNumber is required");
        if (!MASKED_CARD_NUMBER.matcher(cardNumber).matches()) {
            throw new IllegalArgumentException(
                    "cardNumber must match " + MASKED_CARD_NUMBER_PATTERN);
        }
        return cardNumber;
    }

    public String getTransactionId() {
        return transactionId;
    }

    /**
     * Returns the primary key of this row.
     *
     * @return the sixteen-character {@code TRAN-ID}, never {@code null} on a constructed instance
     */
    @Override
    public String getId() {
        return transactionId;
    }

    /**
     * Reports this row as new, always.
     *
     * <p>The identifier arrives from the caller rather than from the database, so a provider given
     * a populated key would otherwise read the table and choose an update. This answer removes that
     * choice: every store is an insert.
     *
     * @return {@code true}
     */
    @Override
    @Transient
    public boolean isNew() {
        return true;
    }

    public String getTypeCode() {
        return typeCode;
    }

    public String getCategoryCode() {
        return categoryCode;
    }

    public String getSource() {
        return source;
    }

    public String getDescription() {
        return description;
    }

    public BigDecimal getAmount() {
        return amount;
    }

    public String getMerchantId() {
        return merchantId;
    }

    public String getMerchantName() {
        return merchantName;
    }

    public String getMerchantCity() {
        return merchantCity;
    }

    public String getMerchantZip() {
        return merchantZip;
    }

    public String getCardNumber() {
        return cardNumber;
    }

    public String getOriginTimestamp() {
        return originTimestamp;
    }

    public String getProcessedTimestamp() {
        return processedTimestamp;
    }

    /**
     * Compares on the primary key.
     *
     * @param other the object to compare with
     * @return {@code true} when {@code other} is a transaction with the same identifier
     */
    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof TransactionEntity that)) {
            return false;
        }
        return Objects.equals(transactionId, that.transactionId);
    }

    /**
     * Returns a hash of the primary key.
     *
     * @return the hash of the transaction identifier
     */
    @Override
    public int hashCode() {
        return Objects.hashCode(transactionId);
    }

    /**
     * Renders the transaction identifier and withholds every other value.
     *
     * <p>The transaction identifier stays, because it correlates this row with the event that
     * produced it and with a log line in another service. The amount, the type code, the category
     * code and both timestamps appear as {@link EventEnvelope#WITHHELD}, the platform-wide
     * redaction marker. The card number, the description and the merchant fields reach the text in
     * no form.
     *
     * @return a single-line description of this row that discloses only the transaction identifier
     */
    @Override
    public String toString() {
        return "TransactionEntity[transactionId=" + transactionId
                + ", typeCode=" + EventEnvelope.WITHHELD
                + ", categoryCode=" + EventEnvelope.WITHHELD
                + ", amount=" + EventEnvelope.WITHHELD
                + ", originTimestamp=" + EventEnvelope.WITHHELD
                + ", processedTimestamp=" + EventEnvelope.WITHHELD
                + "]";
    }
}
