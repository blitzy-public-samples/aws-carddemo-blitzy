package com.carddemo.notification.entity;

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
 * The card-keyed read model this service serves its alert history from.
 *
 * <p>Thirteen columns map the record {@code 01 TRNX-RECORD.} at {@code app/cpy/COSTM01.CPY:L20}.
 * The composite key comes from the group {@code 05 TRNX-KEY.} at {@code app/cpy/COSTM01.CPY:L21},
 * which the cluster definition declares as {@code KEYS(32 0)} at {@code app/jcl/CREASTMT.JCL:L30}
 * and the sort step orders as {@code SORT FIELDS=(263,16,CH,A,1,16,CH,A)} at
 * {@code app/jcl/CREASTMT.JCL:L53}.
 *
 * <p>The card number column holds the masked form: twelve mask characters then the last four
 * digits. Masking is an addition, and no masking exists in the source. The card field on the card
 * detail map occupies its full sixteen characters at {@code app/bms/COCRDSL.bms:L99}.
 *
 * <p>Two events fill a row. {@code TransactionAuthorized} supplies twelve columns and
 * {@code TransactionPosted} supplies the processing timestamp. Both carry the masked card number
 * and the transaction identifier, so either computes the key and either may arrive first. An
 * alphanumeric column the arriving event does not supply holds spaces, and a numeric column holds
 * zero, matching {@code MOVE SPACES} at {@code app/cbl/CBSTM03A.CBL:L459} and {@code MOVE ZERO} at
 * {@code app/cbl/CBSTM03A.CBL:L325}.
 *
 * <p>Design decisions: {@code card-platform/docs/decision-log.md} (planned).
 */
@Entity
@Table(name = "statement_transaction",
        indexes = @Index(name = "ix_statement_transaction_processing_timestamp",
                columnList = "processing_timestamp"))
public class StatementTransactionEntity {

    /** Shape of a masked card number: twelve mask characters then four digits. */
    private static final Pattern MASKED_CARD_NUMBER =
            Pattern.compile("^\\*{12}[0-9]{4}$");

    /** The composite key, from {@code 05 TRNX-KEY.} at {@code app/cpy/COSTM01.CPY:L21}. */
    @EmbeddedId
    private StatementTransactionId id;

    /** {@code TRNX-TYPE-CD PIC X(02)} at {@code app/cpy/COSTM01.CPY:L25}. */
    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "type_code", nullable = false, length = PicClause.TRAN_TYPE_CD_WIDTH)
    private String typeCode;

    /**
     * {@code TRNX-CAT-CD PIC 9(04)} at {@code app/cpy/COSTM01.CPY:L26}, held as four digit
     * characters. The column is {@code CHAR(4)} so a leading zero survives storage, which a numeric
     * column would drop.
     */
    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "category_code", nullable = false, length = PicClause.TRAN_CAT_CD_WIDTH)
    private String categoryCode;

    /** {@code TRNX-SOURCE PIC X(10)} at {@code app/cpy/COSTM01.CPY:L27}. */
    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "source", nullable = false, length = PicClause.TRAN_SOURCE_WIDTH)
    private String source;

    /** {@code TRNX-DESC PIC X(100)} at {@code app/cpy/COSTM01.CPY:L28}. */
    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "description", nullable = false, length = PicClause.TRAN_DESC_WIDTH)
    private String description;

    /** {@code TRNX-AMT PIC S9(09)V99} at {@code app/cpy/COSTM01.CPY:L29}. */
    @Column(name = "amount", nullable = false,
            precision = PicClause.TRAN_AMT_PRECISION, scale = PicClause.TRAN_AMT_SCALE)
    private BigDecimal amount;

    /**
     * {@code TRNX-MERCHANT-ID PIC 9(09)} at {@code app/cpy/COSTM01.CPY:L30}, held as nine digit
     * characters for the same reason {@code category_code} is.
     */
    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "merchant_id", nullable = false, length = PicClause.TRAN_MERCHANT_ID_WIDTH)
    private String merchantId;

    /** {@code TRNX-MERCHANT-NAME PIC X(50)} at {@code app/cpy/COSTM01.CPY:L31}. */
    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "merchant_name", nullable = false,
            length = PicClause.TRAN_MERCHANT_NAME_WIDTH)
    private String merchantName;

    /** {@code TRNX-MERCHANT-CITY PIC X(50)} at {@code app/cpy/COSTM01.CPY:L32}. */
    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "merchant_city", nullable = false,
            length = PicClause.TRAN_MERCHANT_CITY_WIDTH)
    private String merchantCity;

    /** {@code TRNX-MERCHANT-ZIP PIC X(10)} at {@code app/cpy/COSTM01.CPY:L33}. */
    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "merchant_zip", nullable = false,
            length = PicClause.TRAN_MERCHANT_ZIP_WIDTH)
    private String merchantZip;

    /** {@code TRNX-ORIG-TS PIC X(26)} at {@code app/cpy/COSTM01.CPY:L34}. */
    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "origin_timestamp", nullable = false,
            length = PicClause.TRAN_ORIG_TS_WIDTH)
    private String originTimestamp;

    /** {@code TRNX-PROC-TS PIC X(26)} at {@code app/cpy/COSTM01.CPY:L35}. */
    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "processing_timestamp", nullable = false,
            length = PicClause.TRAN_PROC_TS_WIDTH)
    private String processingTimestamp;

    /** Required by the persistence provider. */
    protected StatementTransactionEntity() {
    }

    /**
     * Takes the key and the eleven non-key values in column order.
     *
     * @param id the composite key
     * @param typeCode the transaction type code
     * @param categoryCode the merchant category code, four digits
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
     * @throws IllegalArgumentException when a decimal argument carries the wrong scale, or when
     *         {@code categoryCode} or {@code merchantId} is not the digit count its column holds
     */
    public StatementTransactionEntity(StatementTransactionId id, String typeCode,
            String categoryCode, String source, String description, BigDecimal amount,
            String merchantId, String merchantName, String merchantCity, String merchantZip,
            String originTimestamp, String processingTimestamp) {
        this.id = Objects.requireNonNull(id, "id is required");
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
     * Checks a decimal argument against the scale its column declares.
     *
     * @param value the argument
     * @param scale the scale the column declares
     * @param field the field name the message reports
     * @return the argument
     */
    private static BigDecimal requireScale(BigDecimal value, int scale, String field) {
        Objects.requireNonNull(value, field + " is required");
        if (value.scale() != scale) {
            throw new IllegalArgumentException(
                    field + " carries scale " + value.scale() + " and the column declares " + scale);
        }
        return value;
    }

    /**
     * Checks a digit-character argument against the width its column declares, left-padding a
     * shorter value with zeros.
     *
     * <p>The column is {@code CHAR}, so it holds exactly {@code width} characters. Padding here is
     * what keeps the leading zeros of {@code PIC 9(nn)}: the value {@code 1} of a four-digit
     * category code is stored and returned as {@code 0001}, which is what
     * {@code app/cpy/COSTM01.CPY} declares and a numeric column would lose.</p>
     *
     * @param value the argument
     * @param width the digit count the column holds
     * @param field the field name the message reports
     * @return the argument at {@code width} digits
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
     * The composite key: the masked card number then the transaction identifier.
     *
     * <p>Both parts are sixteen characters, from {@code TRNX-CARD-NUM PIC X(16)} at
     * {@code app/cpy/COSTM01.CPY:L22} and {@code TRNX-ID PIC X(16)} at
     * {@code app/cpy/COSTM01.CPY:L23}.
     */
    @Embeddable
    public static class StatementTransactionId implements Serializable {

        /** Serialization identity of this key. */
        private static final long serialVersionUID = 1L;

        /** The masked card number, from {@code TRNX-CARD-NUM PIC X(16)}. */
        @JdbcTypeCode(SqlTypes.CHAR)
        @Column(name = "card_number", nullable = false,
                length = PicClause.TRAN_CARD_NUM_WIDTH)
        private String cardNumber;

        /** The transaction identifier, from {@code TRNX-ID PIC X(16)}. */
        @JdbcTypeCode(SqlTypes.CHAR)
        @Column(name = "transaction_id", nullable = false, length = PicClause.TRAN_ID_WIDTH)
        private String transactionId;

        /** Required by the persistence provider. */
        protected StatementTransactionId() {
        }

        /**
         * Takes both key parts.
         *
         * @param cardNumber the masked card number, twelve mask characters then four digits
         * @param transactionId the transaction identifier, exactly
         *        {@value PicClause#TRAN_ID_WIDTH} characters
         * @throws NullPointerException when an argument is null
         * @throws IllegalArgumentException when the card number is not masked, or when the
         *         transaction identifier is not exactly {@value PicClause#TRAN_ID_WIDTH}
         *         characters
         */
        public StatementTransactionId(String cardNumber, String transactionId) {
            Objects.requireNonNull(cardNumber, "cardNumber is required");
            Objects.requireNonNull(transactionId, "transactionId is required");
            if (!MASKED_CARD_NUMBER.matcher(cardNumber).matches()) {
                throw new IllegalArgumentException("cardNumber holds "
                        + cardNumber.length() + " characters and this column holds "
                        + PicClause.TRAN_CARD_NUM_WIDTH + " in the masked form");
            }
            if (transactionId.length() != PicClause.TRAN_ID_WIDTH) {
                throw new IllegalArgumentException("transactionId holds "
                        + transactionId.length() + " characters and this column holds "
                        + PicClause.TRAN_ID_WIDTH);
            }
            this.cardNumber = cardNumber;
            this.transactionId = transactionId;
        }

        /** @return the masked card number */
        public String getCardNumber() {
            return cardNumber;
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
            return Objects.equals(cardNumber, that.cardNumber)
                    && Objects.equals(transactionId, that.transactionId);
        }

        @Override
        public int hashCode() {
            return Objects.hash(cardNumber, transactionId);
        }
    }
}
