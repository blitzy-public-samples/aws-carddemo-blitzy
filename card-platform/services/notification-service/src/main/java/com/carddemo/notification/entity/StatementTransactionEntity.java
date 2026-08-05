package com.carddemo.notification.entity;

import com.carddemo.cobol.PanMasker;
import com.carddemo.cobol.PicClause;
import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import java.io.Serializable;
import java.math.BigDecimal;
import java.util.Objects;
import java.util.regex.Pattern;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * The account-keyed read model this service serves its alert history from.
 *
 * <p>Fourteen columns map the record {@code 01 TRNX-RECORD.} at {@code app/cpy/COSTM01.CPY:L20}.
 * The composite key is the group {@code 05 TRNX-KEY.} at {@code app/cpy/COSTM01.CPY:L21}, which
 * the cluster definition declares as {@code KEYS(32 0)} at {@code app/jcl/CREASTMT.JCL:L30}, a Job
 * Control Language (JCL) member. The sort step orders the two key parts as
 * {@code SORT FIELDS=(263,16,CH,A,1,16,CH,A)} at {@code app/jcl/CREASTMT.JCL:L53}, and that order
 * is the key column order here.
 *
 * <p>Each column, with the source field and source line it maps:
 *
 * <pre>
 * TRNX-CARD-NUM       L22   card_token             CHAR(64)  identity
 *                           masked_card_number     CHAR(16)  display
 * TRNX-ID             L23   transaction_id         CHAR(16)
 * TRNX-CARD-NUM       L22   card_number            CHAR(16)   display only, ADDITIVE form
 * TRNX-TYPE-CD        L25   type_code              CHAR(2)
 * TRNX-CAT-CD         L26   category_code          CHAR(4)
 * TRNX-SOURCE         L27   source                 CHAR(10)
 * TRNX-DESC           L28   description            CHAR(100)
 * TRNX-AMT            L29   amount                 NUMERIC(11,2)
 * TRNX-MERCHANT-ID    L30   merchant_id            CHAR(9)
 * TRNX-MERCHANT-NAME  L31   merchant_name          CHAR(50)
 * TRNX-MERCHANT-CITY  L32   merchant_city          CHAR(50)
 * TRNX-MERCHANT-ZIP   L33   merchant_zip           CHAR(10)
 * TRNX-ORIG-TS        L34   origin_timestamp       CHAR(26)
 * TRNX-PROC-TS        L35   processing_timestamp   CHAR(26)
 * </pre>
 *
 * <p>One source field becomes two columns, because one value cannot do both jobs. The source key
 * holds a full Primary Account Number (PAN) and the card field on the card detail map occupies its
 * full sixteen characters at {@code app/bms/COCRDSL.bms:L99}. This platform stores neither a full
 * card number nor a masked one as an identity: {@code card_token} carries the card identity that
 * {@code PanMasker.cardToken} derives, one value per card, and {@code masked_card_number} carries
 * the twelve-mask-character display form beside it. Both columns are additions, and the source masks
 * and tokenizes nothing.
 *
 * <p>The masked form keys nothing here on purpose. Twelve of its sixteen characters are mask
 * characters, so two cards sharing their last four digits mask to one value: a key over it would
 * merge two cards' histories into one row set, and an ownership rule over it would admit a caller to
 * a card it does not hold.
 *
 * <p>Both timestamp columns hold 26 characters of text, and neither maps to a date or time type.
 * Two source constructs carry no column: the group {@code 05 TRNX-REST.} at {@code
 * app/cpy/COSTM01.CPY:L24}, and the trailing {@code FILLER PIC X(20)} at {@code
 * app/cpy/COSTM01.CPY:L36}.
 *
 * <p>The one secondary index declared below is the index
 * {@code src/main/resources/db/migration/V1__schema.sql} creates over
 * {@code processing_timestamp}.
 */
@Entity
@Table(name = "statement_transaction",
        indexes = @Index(name = "ix_statement_transaction_processing_timestamp",
                columnList = "processing_timestamp"))
public class StatementTransactionEntity {

    /** Shape of a masked card number: twelve mask characters then four digits. */
    private static final Pattern MASKED_CARD_NUMBER =
            Pattern.compile("^\\*{12}[0-9]{4}$");

    /**
     * Shape of a card token: {@value PanMasker#CARD_TOKEN_LENGTH} lower-case hexadecimal
     * characters, the rendering {@code PanMasker.cardToken} produces.
     */
    private static final Pattern CARD_TOKEN = Pattern.compile(PanMasker.CARD_TOKEN_PATTERN);

    /** Holds the composite key. Source: {@code 05 TRNX-KEY.} at {@code app/cpy/COSTM01.CPY:L21}. */
    @EmbeddedId
    private StatementTransactionId id;

    /**
     * Holds the masked card number as display data, never as an identity.
     *
     * <p>Twelve mask characters then the last four digits, at the width
     * {@code TRNX-CARD-NUM PIC X(16)} at {@code app/cpy/COSTM01.CPY:L22} declares. The key carries
     * the card identity; this column carries what a cardholder recognises.</p>
     */
    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "masked_card_number", nullable = false,
            length = PicClause.TRAN_CARD_NUM_WIDTH)
    private String maskedCardNumber;

    /**
     * Holds the transaction type code. Source: {@code TRNX-TYPE-CD PIC X(02)} at
     * {@code app/cpy/COSTM01.CPY:L25}.
     */
    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "type_code", nullable = false, length = PicClause.TRAN_TYPE_CD_WIDTH)
    private String typeCode;

    /**
     * Holds the transaction category code, four digit characters with any leading zero kept.
     *
     * <p>Source: {@code TRNX-CAT-CD PIC 9(04)} at {@code app/cpy/COSTM01.CPY:L26}. The migration
     * declares the column {@code CHAR(4)} and constrains it to four digits.</p>
     */
    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "category_code", nullable = false, length = PicClause.TRAN_CAT_CD_WIDTH)
    private String categoryCode;

    /**
     * Holds the channel the transaction arrived through. Source:
     * {@code TRNX-SOURCE PIC X(10)} at {@code app/cpy/COSTM01.CPY:L27}.
     */
    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "source", nullable = false, length = PicClause.TRAN_SOURCE_WIDTH)
    private String source;

    /**
     * Holds the text describing the transaction, at its full hundred characters. Source:
     * {@code TRNX-DESC PIC X(100)} at {@code app/cpy/COSTM01.CPY:L28}.
     */
    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "description", nullable = false, length = PicClause.TRAN_DESC_WIDTH)
    private String description;

    /**
     * Holds the transaction amount at scale {@value PicClause#TRAN_AMT_SCALE}. A refund carries a
     * negative amount. Source: {@code TRNX-AMT PIC S9(09)V99} at
     * {@code app/cpy/COSTM01.CPY:L29}.
     */
    @Column(name = "amount", nullable = false,
            precision = PicClause.TRAN_AMT_PRECISION, scale = PicClause.TRAN_AMT_SCALE)
    private BigDecimal amount;

    /**
     * Holds the merchant identifier, nine digit characters with any leading zero kept.
     *
     * <p>Source: {@code TRNX-MERCHANT-ID PIC 9(09)} at {@code app/cpy/COSTM01.CPY:L30}. The
     * migration declares the column {@code CHAR(9)} and constrains it to nine digits.</p>
     */
    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "merchant_id", nullable = false, length = PicClause.TRAN_MERCHANT_ID_WIDTH)
    private String merchantId;

    /**
     * Holds the merchant name. Source: {@code TRNX-MERCHANT-NAME PIC X(50)} at
     * {@code app/cpy/COSTM01.CPY:L31}.
     */
    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "merchant_name", nullable = false,
            length = PicClause.TRAN_MERCHANT_NAME_WIDTH)
    private String merchantName;

    /**
     * Holds the merchant city. Source: {@code TRNX-MERCHANT-CITY PIC X(50)} at
     * {@code app/cpy/COSTM01.CPY:L32}.
     */
    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "merchant_city", nullable = false,
            length = PicClause.TRAN_MERCHANT_CITY_WIDTH)
    private String merchantCity;

    /**
     * Holds the merchant mail code. Source: {@code TRNX-MERCHANT-ZIP PIC X(10)} at
     * {@code app/cpy/COSTM01.CPY:L33}.
     */
    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "merchant_zip", nullable = false,
            length = PicClause.TRAN_MERCHANT_ZIP_WIDTH)
    private String merchantZip;

    /**
     * Holds when the transaction originated, as 26 characters of text. Source:
     * {@code TRNX-ORIG-TS PIC X(26)} at {@code app/cpy/COSTM01.CPY:L34}.
     */
    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "origin_timestamp", nullable = false,
            length = PicClause.TRAN_ORIG_TS_WIDTH)
    private String originTimestamp;

    /**
     * Holds when the platform recorded the transaction, as 26 characters of text. Source:
     * {@code TRNX-PROC-TS PIC X(26)} at {@code app/cpy/COSTM01.CPY:L35}.
     */
    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "processing_timestamp", nullable = false,
            length = PicClause.TRAN_PROC_TS_WIDTH)
    private String processingTimestamp;

    /** Required by the persistence provider. */
    protected StatementTransactionEntity() {
    }

    /**
     * Takes the key and the twelve non-key values in column order.
     *
     * @param id the composite key
     * @param maskedCardNumber the masked card number, twelve mask characters then four digits
     * @param typeCode the transaction type code
     * @param categoryCode the transaction category code, four digits
     * @param source where the transaction entered the platform
     * @param description free text describing the transaction
     * @param amount the transaction amount, scale {@value PicClause#TRAN_AMT_SCALE}
     * @param merchantId the merchant identifier, nine digits
     * @param merchantName the merchant name
     * @param merchantCity the merchant city
     * @param merchantZip the merchant mail code
     * @param originTimestamp when the transaction originated, 26 characters
     * @param processingTimestamp when the platform recorded the transaction, 26 characters
     * @throws NullPointerException when an argument is null
     * @throws IllegalArgumentException when a decimal argument carries the wrong scale, when
     *         {@code categoryCode} or {@code merchantId} is not the digit count its column holds,
     *         or when {@code maskedCardNumber} is not the masked form
     */
    public StatementTransactionEntity(StatementTransactionId id, String maskedCardNumber,
            String typeCode, String categoryCode, String source, String description,
            BigDecimal amount, String merchantId, String merchantName, String merchantCity,
            String merchantZip, String originTimestamp, String processingTimestamp) {
        this.id = Objects.requireNonNull(id, "id is required");
        this.maskedCardNumber = requireMasked(maskedCardNumber);
        this.typeCode = Objects.requireNonNull(typeCode, "typeCode is required");
        this.categoryCode =
                requireDigits(categoryCode, PicClause.TRAN_CAT_CD_WIDTH, "categoryCode");
        this.source = Objects.requireNonNull(source, "source is required");
        this.description = Objects.requireNonNull(description, "description is required");
        this.amount = requireScale(amount, PicClause.TRAN_AMT_SCALE, "amount");
        this.merchantId =
                requireDigits(merchantId, PicClause.TRAN_MERCHANT_ID_WIDTH, "merchantId");
        this.merchantName = Objects.requireNonNull(merchantName, "merchantName is required");
        this.merchantCity = Objects.requireNonNull(merchantCity, "merchantCity is required");
        this.merchantZip = Objects.requireNonNull(merchantZip, "merchantZip is required");
        this.originTimestamp =
                Objects.requireNonNull(originTimestamp, "originTimestamp is required");
        this.processingTimestamp =
                Objects.requireNonNull(processingTimestamp, "processingTimestamp is required");
    }

    /**
     * Checks the display card number against the masked shape its column enforces.
     *
     * <p>{@code ck_statement_transaction_masked_card_number} in
     * {@code src/main/resources/db/migration/V1__schema.sql} holds the stored column to the same
     * shape, so a full Primary Account Number is refused here and again at the column. No message
     * this method raises names the argument.</p>
     *
     * @param maskedCardNumber the argument
     * @return the argument
     * @throws NullPointerException when the argument is null
     * @throws IllegalArgumentException when the argument is not twelve mask characters then four
     *         digits
     */
    private static String requireMasked(String maskedCardNumber) {
        Objects.requireNonNull(maskedCardNumber, "maskedCardNumber is required");
        if (!MASKED_CARD_NUMBER.matcher(maskedCardNumber).matches()) {
            throw new IllegalArgumentException("maskedCardNumber holds "
                    + maskedCardNumber.length() + " characters and this column holds "
                    + PicClause.TRAN_CARD_NUM_WIDTH + " in the masked form");
        }
        return maskedCardNumber;
    }

    /**
     * Checks a decimal argument against the scale its column declares.
     *
     * @param value the argument
     * @param scale the scale the column declares
     * @param field the field name the message reports
     * @return the argument
     * @throws NullPointerException when the argument is null
     * @throws IllegalArgumentException when the argument carries another scale
     */
    private static BigDecimal requireScale(BigDecimal value, int scale, String field) {
        Objects.requireNonNull(value, field + " is required");
        if (value.scale() != scale) {
            throw new IllegalArgumentException(field + " carries scale " + value.scale()
                    + " and the column declares " + scale);
        }
        return value;
    }

    /**
     * Checks a digit-character argument against the width its column declares, then left-pads a
     * shorter value with zeros.
     *
     * <p>The column holds exactly {@code width} characters. This method returns {@code 0001} for a
     * four-digit category code supplied as {@code 1}, the form
     * {@code TRNX-CAT-CD PIC 9(04)} at {@code app/cpy/COSTM01.CPY:L26} declares.</p>
     *
     * @param value the argument
     * @param width the digit count the column holds
     * @param field the field name the message reports
     * @return the argument at {@code width} digits
     * @throws NullPointerException when the argument is null
     * @throws IllegalArgumentException when the argument is empty, wider than the column, or
     *         carries a character that is not a digit
     */
    private static String requireDigits(String value, int width, String field) {
        Objects.requireNonNull(value, field + " is required");
        if (value.isEmpty() || value.length() > width
                || !value.chars().allMatch(Character::isDigit)) {
            throw new IllegalArgumentException(field + " holds \"" + value
                    + "\" and the column declares up to " + width + " digits");
        }
        return "0".repeat(width - value.length()) + value;
    }

    /** @return the composite key */
    public StatementTransactionId getId() {
        return id;
    }

    /** @return the masked card number this row displays, which identifies no single card */
    public String getMaskedCardNumber() {
        return maskedCardNumber;
    }

    /** @return the transaction type code */
    public String getTypeCode() {
        return typeCode;
    }

    /** @return the merchant category code */
    public String getCategoryCode() {
        return categoryCode;
    }

    /** @return where the transaction entered the platform */
    public String getSource() {
        return source;
    }

    /** @return free text describing the transaction */
    public String getDescription() {
        return description;
    }

    /** @return the transaction amount */
    public BigDecimal getAmount() {
        return amount;
    }

    /** @return the merchant identifier */
    public String getMerchantId() {
        return merchantId;
    }

    /** @return the merchant name */
    public String getMerchantName() {
        return merchantName;
    }

    /** @return the merchant city */
    public String getMerchantCity() {
        return merchantCity;
    }

    /** @return the merchant mail code */
    public String getMerchantZip() {
        return merchantZip;
    }

    /** @return when the transaction originated */
    public String getOriginTimestamp() {
        return originTimestamp;
    }

    /** @return when the platform recorded the transaction */
    public String getProcessingTimestamp() {
        return processingTimestamp;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof StatementTransactionEntity that)) {
            return false;
        }
        return Objects.equals(id, that.id);
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(id);
    }

    /**
     * The composite key: the card token then the transaction identifier.
     *
     * <p>The card half stands in for {@code TRNX-CARD-NUM PIC X(16)} at
     * {@code app/cpy/COSTM01.CPY:L22} and holds {@value PanMasker#CARD_TOKEN_LENGTH} characters
     * rather than sixteen. The transaction half is {@code TRNX-ID PIC X(16)} at
     * {@code app/cpy/COSTM01.CPY:L23} at its declared width.
     *
     * <p>Neither a full nor a masked card number can key this table. A full one would reach every
     * index page and backup that holds the key. A masked one identifies no single card, so it would
     * merge the histories of two cards sharing their last four digits.
     */
    @Embeddable
    public static class StatementTransactionId implements Serializable {

        /** Serialization identity of this key. */
        private static final long serialVersionUID = 1L;

        /**
         * Holds the card token. Stands in for {@code TRNX-CARD-NUM PIC X(16)} at
         * {@code app/cpy/COSTM01.CPY:L22}, whose value was a full Primary Account Number.
         */
        @JdbcTypeCode(SqlTypes.CHAR)
        @Column(name = "card_token", nullable = false, length = PanMasker.CARD_TOKEN_LENGTH)
        private String cardToken;

        /**
         * Holds the transaction identifier. Source: {@code TRNX-ID PIC X(16)} at
         * {@code app/cpy/COSTM01.CPY:L23}.
         */
        @JdbcTypeCode(SqlTypes.CHAR)
        @Column(name = "transaction_id", nullable = false, length = PicClause.TRAN_ID_WIDTH)
        private String transactionId;

        /** Required by the persistence provider. */
        protected StatementTransactionId() {
        }

        /**
         * Takes both key parts.
         *
         * @param cardToken the card token, exactly {@value PanMasker#CARD_TOKEN_LENGTH} lower-case
         *        hexadecimal characters
         * @param transactionId the transaction identifier, exactly
         *        {@value PicClause#TRAN_ID_WIDTH} characters
         * @throws NullPointerException when an argument is null
         * @throws IllegalArgumentException when the card token misses the token shape, or when the
         *         transaction identifier is not exactly {@value PicClause#TRAN_ID_WIDTH}
         *         characters
         */
        public StatementTransactionId(String cardToken, String transactionId) {
            Objects.requireNonNull(cardToken, "cardToken is required");
            Objects.requireNonNull(transactionId, "transactionId is required");
            if (!CARD_TOKEN.matcher(cardToken).matches()) {
                throw new IllegalArgumentException("cardToken holds " + cardToken.length()
                        + " characters and this column holds " + PanMasker.CARD_TOKEN_LENGTH
                        + " lower-case hexadecimal characters");
            }
            if (transactionId.length() != PicClause.TRAN_ID_WIDTH) {
                throw new IllegalArgumentException("transactionId holds "
                        + transactionId.length() + " characters and this column holds "
                        + PicClause.TRAN_ID_WIDTH);
            }
            this.cardToken = cardToken;
            this.transactionId = transactionId;
        }

        /** @return the card token, which identifies the card and discloses no card number */
        public String getCardToken() {
            return cardToken;
        }

        /** @return the transaction identifier */
        public String getTransactionId() {
            return transactionId;
        }

        @Override
        public boolean equals(Object other) {
            if (this == other) {
                return true;
            }
            if (!(other instanceof StatementTransactionId that)) {
                return false;
            }
            return Objects.equals(cardToken, that.cardToken)
                    && Objects.equals(transactionId, that.transactionId);
        }

        @Override
        public int hashCode() {
            return Objects.hash(cardToken, transactionId);
        }
    }
}
