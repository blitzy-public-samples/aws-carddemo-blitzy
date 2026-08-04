package com.carddemo.notification.api;

import com.carddemo.cobol.PicClause;
import com.carddemo.notification.entity.StatementTransactionEntity;

/**
 * One transaction of one card's alert history.
 *
 * <p>Twelve components map {@code app/cpy/COSTM01.CPY:L23} through {@code :L35}. The masked card
 * number sits on {@link NotificationHistoryResponse} and is not repeated here. The trailing
 * {@code FILLER PIC X(20)} at {@code app/cpy/COSTM01.CPY:L36} carries no component.
 *
 * <p>Every value is text. A monetary value travels as a decimal string so a parser cannot turn it
 * into a binary floating-point number, and a numeric identifier keeps its leading zeros.
 *
 * <p>The stored columns are fixed width, so a stored alphanumeric value returns padded with
 * spaces. {@link #from(StatementTransactionEntity)} removes the trailing spaces from the six
 * free-text components and leaves the two timestamps at their full
 * {@value PicClause#TRAN_ORIG_TS_WIDTH} characters.
 *
 * @param transactionId identifies one transaction within the card. From {@code TRNX-ID PIC X(16)}
 *        at {@code app/cpy/COSTM01.CPY:L23}
 * @param typeCode names the transaction type. From {@code TRNX-TYPE-CD PIC X(02)} at
 *        {@code app/cpy/COSTM01.CPY:L25}
 * @param categoryCode names the transaction category, four digits. From
 *        {@code TRNX-CAT-CD PIC 9(04)} at {@code app/cpy/COSTM01.CPY:L26}
 * @param source names where the transaction entered the platform. From
 *        {@code TRNX-SOURCE PIC X(10)} at {@code app/cpy/COSTM01.CPY:L27}
 * @param description describes the transaction in free text. From {@code TRNX-DESC PIC X(100)} at
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
 * @param merchantZip holds the merchant mail code. From
 *        {@code TRNX-MERCHANT-ZIP PIC X(10)} at {@code app/cpy/COSTM01.CPY:L33}
 * @param originTimestamp when the transaction originated, twenty-six characters with a space
 *        between the day and the hour. From {@code TRNX-ORIG-TS PIC X(26)} at
 *        {@code app/cpy/COSTM01.CPY:L34}
 * @param processingTimestamp when the platform recorded the transaction, twenty-six characters
 *        with a dash between the day and the hour. From {@code TRNX-PROC-TS PIC X(26)} at
 *        {@code app/cpy/COSTM01.CPY:L35}
 */
public record NotificationTransactionItem(String transactionId, String typeCode,
        String categoryCode, String source, String description, String amount, String merchantId,
        String merchantName, String merchantCity, String merchantZip, String originTimestamp,
        String processingTimestamp) {

    /** The character a numeric identifier is left-padded with. */
    private static final String PAD_DIGIT = "0";

    /**
     * Maps one read-model row onto one wire item.
     *
     * @param row the read-model row
     * @return the wire item
     */
    public static NotificationTransactionItem from(StatementTransactionEntity row) {
        return new NotificationTransactionItem(row.getId().getTransactionId(),
                decoded(row.getTypeCode()),
                digits(row.getCategoryCode(), PicClause.TRAN_CAT_CD_WIDTH),
                decoded(row.getSource()), decoded(row.getDescription()),
                row.getAmount().toPlainString(),
                digits(row.getMerchantId(), PicClause.TRAN_MERCHANT_ID_WIDTH),
                decoded(row.getMerchantName()), decoded(row.getMerchantCity()),
                decoded(row.getMerchantZip()), row.getOriginTimestamp(),
                row.getProcessingTimestamp());
    }

    /**
     * Removes the trailing spaces a fixed-width column returns.
     *
     * @param stored the stored value
     * @return the value without its trailing spaces
     */
    private static String decoded(String stored) {
        return stored.stripTrailing();
    }

    /**
     * Renders a stored identifier as digits, left-padded with zeros to its column width.
     *
     * <p>The column is {@code CHAR}, so a value written at its full width returns unchanged. The
     * padding covers a value stored narrower than its column, which is what keeps the leading zeros
     * {@code PIC 9(nn)} carries out of the wire item.</p>
     *
     * @param value the stored value
     * @param width the digits the column holds
     * @return the value at {@code width} digits
     */
    private static String digits(String value, int width) {
        String plain = value.strip();
        if (plain.length() >= width) {
            return plain;
        }
        return PAD_DIGIT.repeat(width - plain.length()) + plain;
    }
}
