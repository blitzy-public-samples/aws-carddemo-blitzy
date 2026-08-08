package com.carddemo.notification.api;

import com.carddemo.cobol.PicClause;
import com.carddemo.notification.entity.StatementTransactionEntity;
import java.math.BigDecimal;
import java.util.Objects;

/**
 * One transaction of one account's alert history.
 *
 * <p>Twelve components map the record {@code 01 TRNX-RECORD.} at
 * {@code app/cpy/COSTM01.CPY:L20}, one component per field from {@code :L23} through
 * {@code :L35}. The masked card number sits on {@link NotificationHistoryResponse}. The trailing
 * {@code FILLER PIC X(20)} at {@code app/cpy/COSTM01.CPY:L36} carries no component.
 *
 * <p>Every component is text, and the amount carries {@value PicClause#TRAN_AMT_SCALE} fractional
 * digits.
 *
 * <p>{@code originTimestamp} holds {@value PicClause#TRAN_ORIG_TS_WIDTH} characters shaped
 * {@code YYYY-MM-DD HH:MM:SS.ffffff}, with a space between the day and the hour.
 *
 * <p>{@code processingTimestamp} holds {@value PicClause#TRAN_PROC_TS_WIDTH} characters shaped
 * {@value PicClause#PROCESSING_TIMESTAMP_SHAPE}, with a dash between the day and the hour. Its
 * precision is hundredths of a second, and {@code app/cbl/CBTRN02C.cbl:L701} fills the last
 * {@value PicClause#PROCESSING_TIMESTAMP_TRAILING_ZERO_DIGITS} characters with zeros.
 *
 * <p>A fixed-width column returns its value padded with spaces.
 * {@link #from(StatementTransactionEntity)} drops those trailing spaces from the six free-text
 * components, left-pads the two digit identifiers to their column widths, and leaves both
 * timestamps at their full width.
 *
 * @param transactionId identifies one transaction within the card. From {@code TRNX-ID PIC X(16)}
 *        at {@code app/cpy/COSTM01.CPY:L23}
 * @param typeCode names the transaction type. From {@code TRNX-TYPE-CD PIC X(02)} at
 *        {@code app/cpy/COSTM01.CPY:L25}
 * @param categoryCode names the transaction category, four digits. From
 *        {@code TRNX-CAT-CD PIC 9(04)} at {@code app/cpy/COSTM01.CPY:L26}
 * @param source names where the transaction entered the platform. From
 *        {@code TRNX-SOURCE PIC X(10)} at {@code app/cpy/COSTM01.CPY:L27}
 * @param description describes the transaction in free text, up to
 *        {@value PicClause#TRAN_DESC_WIDTH} characters. From {@code TRNX-DESC PIC X(100)} at
 *        {@code app/cpy/COSTM01.CPY:L28}
 * @param amount the transaction amount as a decimal string with two fractional digits. From
 *        {@code TRNX-AMT PIC S9(09)V99} at {@code app/cpy/COSTM01.CPY:L29}. A refund carries a
 *        leading minus
 * @param merchantId identifies the merchant, nine digits. From
 *        {@code TRNX-MERCHANT-ID PIC 9(09)} at {@code app/cpy/COSTM01.CPY:L30}
 * @param merchantName names the merchant. From {@code TRNX-MERCHANT-NAME PIC X(50)} at
 *        {@code app/cpy/COSTM01.CPY:L31}
 * @param merchantCity names the merchant city. From {@code TRNX-MERCHANT-CITY PIC X(50)} at
 *        {@code app/cpy/COSTM01.CPY:L32}
 * @param merchantZip holds the merchant mail code. From {@code TRNX-MERCHANT-ZIP PIC X(10)} at
 *        {@code app/cpy/COSTM01.CPY:L33}
 * @param originTimestamp when the transaction originated. From {@code TRNX-ORIG-TS PIC X(26)} at
 *        {@code app/cpy/COSTM01.CPY:L34}
 * @param processingTimestamp when the platform recorded the transaction. From
 *        {@code TRNX-PROC-TS PIC X(26)} at {@code app/cpy/COSTM01.CPY:L35}
 */
public record NotificationTransactionItem(String transactionId, String typeCode,
        String categoryCode, String source, String description, String amount, String merchantId,
        String merchantName, String merchantCity, String merchantZip, String originTimestamp,
        String processingTimestamp) {

    /** The character a digit identifier is left-padded with. */
    private static final String PAD_DIGIT = "0";

    /**
     * Checks that every component carries a value, and changes no value.
     *
     * <p>Every column {@code src/main/resources/db/migration/V1__schema.sql} declares is
     * {@code NOT NULL}.</p>
     *
     * @throws NullPointerException when a component is null
     */
    public NotificationTransactionItem {
        Objects.requireNonNull(transactionId, "transactionId is required");
        Objects.requireNonNull(typeCode, "typeCode is required");
        Objects.requireNonNull(categoryCode, "categoryCode is required");
        Objects.requireNonNull(source, "source is required");
        Objects.requireNonNull(description, "description is required");
        Objects.requireNonNull(amount, "amount is required");
        Objects.requireNonNull(merchantId, "merchantId is required");
        Objects.requireNonNull(merchantName, "merchantName is required");
        Objects.requireNonNull(merchantCity, "merchantCity is required");
        Objects.requireNonNull(merchantZip, "merchantZip is required");
        Objects.requireNonNull(originTimestamp, "originTimestamp is required");
        Objects.requireNonNull(processingTimestamp, "processingTimestamp is required");
    }

    /**
     * Maps one {@link StatementTransactionEntity} row onto one item of the alert history.
     *
     * <p>The transaction identifier comes from the embedded key. No card number is read from the
     * row: the card is named once, by {@link NotificationHistoryResponse#cardNumber()}.</p>
     *
     * @param row the read-model row
     * @return the item built from {@code row}
     * @throws NullPointerException when {@code row}, its key, or one of its columns is null
     * @throws IllegalArgumentException when the amount of {@code row} carries a scale other than
     *         {@value PicClause#TRAN_AMT_SCALE}
     */
    public static NotificationTransactionItem from(StatementTransactionEntity row) {
        Objects.requireNonNull(row, "row is required");
        return new NotificationTransactionItem(
                Objects.requireNonNull(row.getId(), "row key is required").getTransactionId(),
                decoded(row.getTypeCode(), "typeCode"),
                digits(row.getCategoryCode(), PicClause.TRAN_CAT_CD_WIDTH, "categoryCode"),
                decoded(row.getSource(), "source"),
                decoded(row.getDescription(), "description"),
                money(row.getAmount()),
                digits(row.getMerchantId(), PicClause.TRAN_MERCHANT_ID_WIDTH, "merchantId"),
                decoded(row.getMerchantName(), "merchantName"),
                decoded(row.getMerchantCity(), "merchantCity"),
                decoded(row.getMerchantZip(), "merchantZip"),
                row.getOriginTimestamp(),
                row.getProcessingTimestamp());
    }

    /**
     * Drops the trailing spaces a fixed-width column returns.
     *
     * <p>A column holding only spaces yields an empty string.</p>
     *
     * @param stored the stored value
     * @param field the component name the message reports
     * @return the stored value without its trailing spaces
     * @throws NullPointerException when {@code stored} is null
     */
    private static String decoded(String stored, String field) {
        Objects.requireNonNull(stored, field + " is required");
        return stored.stripTrailing();
    }

    /**
     * Renders a stored identifier as digits, left-padded with zeros to its column width.
     *
     * <p>The column holds {@code width} characters, so a value stored at that width returns
     * unchanged.</p>
     *
     * @param value the stored value
     * @param width the digit count the column holds
     * @param field the component name the message reports
     * @return the stored value at {@code width} digits
     * @throws NullPointerException when {@code value} is null
     */
    private static String digits(String value, int width, String field) {
        Objects.requireNonNull(value, field + " is required");
        String plain = value.strip();
        if (plain.length() >= width) {
            return plain;
        }
        return PAD_DIGIT.repeat(width - plain.length()) + plain;
    }

    /**
     * Renders the transaction identifier and the type and category codes, and no other component.
     *
     * <p>The record declares thirteen components. Six describe what a cardholder bought, where and
     * for how much: the description, the amount, the merchant identifier, name and city, and the
     * merchant mail code. The two timestamps place the purchase in time, and
     * {@code maskedCardNumber} names the card. A generated record rendering would carry all
     * thirteen and reach any log line or exception message naming this record, so ten are withheld
     * here and a caller reads them through their accessors.</p>
     *
     * @return the transaction identifier, the type code and the category code
     */
    @Override
    public String toString() {
        return "NotificationTransactionItem[transactionId=" + transactionId
                + ", typeCode=" + typeCode
                + ", categoryCode=" + categoryCode
                + ", 10 transaction fields withheld]";
    }

    /**
     * Renders a stored amount as a plain decimal string.
     *
     * <p>The column declares scale {@value PicClause#TRAN_AMT_SCALE}, and this method requires
     * that scale of the value it is given.</p>
     *
     * @param amount the stored amount
     * @return the amount as a plain decimal string, with a leading minus when negative
     * @throws NullPointerException when {@code amount} is null
     * @throws IllegalArgumentException when {@code amount} carries another scale
     */
    private static String money(BigDecimal amount) {
        Objects.requireNonNull(amount, "amount is required");
        if (amount.scale() != PicClause.TRAN_AMT_SCALE) {
            throw new IllegalArgumentException("amount carries scale " + amount.scale()
                    + " and the column declares " + PicClause.TRAN_AMT_SCALE);
        }
        return amount.toPlainString();
    }
}
