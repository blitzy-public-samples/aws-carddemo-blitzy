package com.carddemo.notification.api;

import com.carddemo.cobol.PanMasker;
import com.carddemo.cobol.PicClause;
import com.carddemo.notification.entity.StatementTransactionEntity;
import java.math.BigDecimal;
import java.util.List;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * The response body of {@code GET /notifications/{maskedCardNumber}}.
 *
 * <p>Four components carry one card's alert history: the masked card number, the count of
 * transactions, the per-card total and the transactions. The schema {@code NotificationHistory} in
 * {@code src/main/resources/openapi.yaml} declares those four properties in this order and sets
 * {@code additionalProperties: false}.
 *
 * <p>A card with no row in the read model carries an empty array, a count of {@code 0} and a total
 * of {@code "0.00"}.
 *
 * <p>Design decisions: {@code card-platform/docs/decision-log.md} (planned).
 *
 * @param cardNumber the masked card number: twelve mask characters then the last four digits, and
 *        never a full Primary Account Number. Width from {@code TRNX-CARD-NUM PIC X(16)} at
 *        {@code app/cpy/COSTM01.CPY:L22}. ADDITIVE. The source masks nothing.
 *        {@code app/bms/COCRDSL.bms:L99} shows the card field at {@code LENGTH=16},
 *        {@code CARD-NUM PIC X(16)} at {@code app/cpy/CVACT02Y.cpy:L5} is plain character data, and
 *        {@code app/cpy/CVACT02Y.cpy:L7} keeps the card verification value in the clear
 * @param transactionCount how many items {@code transactions} carries. Never negative, and always
 *        equal to the number of items in {@code transactions}
 * @param totalAmount the per-card total as a decimal string: at most nine integer digits, a point,
 *        then two fractional digits. A refund total carries a leading minus. From
 *        {@code WS-TOTAL-AMT PIC S9(9)V99} at {@code app/cbl/CBSTM03A.CBL:L65}, zeroed for each
 *        card at {@code app/cbl/CBSTM03A.CBL:L325}, accumulated one row at a time at
 *        {@code app/cbl/CBSTM03A.CBL:L429} and moved to the print field at
 *        {@code app/cbl/CBSTM03A.CBL:L433-L434}. The print edit
 *        {@code ST-TOTAL-TRAMT PIC Z(9).99-} at {@code app/cbl/CBSTM03A.CBL:L142} carries a
 *        trailing sign
 * @param transactions one item per transaction the read model holds for the card, in the ascending
 *        transaction-identifier order {@code app/jcl/CREASTMT.JCL:L53} produces. Unmodifiable
 */
public record NotificationHistoryResponse(String cardNumber, int transactionCount,
        String totalAmount, List<NotificationTransactionItem> transactions) {

    /**
     * Shape of a masked card number: twelve mask characters then four digits.
     *
     * <p>The property {@code cardNumber} of {@code src/main/resources/openapi.yaml} declares this
     * shape, and {@code ck_statement_transaction_card_number} in
     * {@code src/main/resources/db/migration/V1__schema.sql} holds the stored column to it. Total
     * width stays {@value PicClause#TRAN_CARD_NUM_WIDTH}, matching
     * {@code TRNX-CARD-NUM PIC X(16)} at {@code app/cpy/COSTM01.CPY:L22}.</p>
     */
    private static final Pattern MASKED_CARD_NUMBER = Pattern.compile("^\\*{12}[0-9]{4}$");

    /**
     * Shape of the total: an optional leading minus, at most nine integer digits, a point and two
     * fractional digits.
     *
     * <p>The property {@code totalAmount} of {@code src/main/resources/openapi.yaml} declares this
     * shape. The nine integer digits and the {@value PicClause#TRAN_AMT_SCALE} fractional digits
     * come from {@code WS-TOTAL-AMT PIC S9(9)V99} at {@code app/cbl/CBSTM03A.CBL:L65}.</p>
     */
    private static final Pattern TOTAL_AMOUNT = Pattern.compile("^-?\\d{1,9}\\.\\d{2}$");

    /**
     * Checks every component, then copies the item list.
     *
     * <p>The card number arrives masked and the total arrives rendered. This constructor changes
     * neither, and no message it raises names a card number.</p>
     *
     * @throws NullPointerException when a component or an item is null
     * @throws IllegalArgumentException when the card number misses the masked shape, when the total
     *         misses the decimal shape, when the count is negative, or when the count and the item
     *         count differ
     */
    public NotificationHistoryResponse {
        Objects.requireNonNull(cardNumber, "cardNumber is required");
        Objects.requireNonNull(totalAmount, "totalAmount is required");
        Objects.requireNonNull(transactions, "transactions is required");

        if (!MASKED_CARD_NUMBER.matcher(cardNumber).matches()) {
            throw new IllegalArgumentException(
                    "cardNumber must carry twelve mask characters then four digits");
        }
        if (!TOTAL_AMOUNT.matcher(totalAmount).matches()) {
            throw new IllegalArgumentException("totalAmount must carry at most nine integer "
                    + "digits, a point and " + PicClause.TRAN_AMT_SCALE + " fractional digits");
        }
        if (transactionCount < 0) {
            throw new IllegalArgumentException(
                    "transactionCount reads " + transactionCount + " and carries no negative "
                    + "value");
        }

        transactions = List.copyOf(transactions);

        if (transactionCount != transactions.size()) {
            throw new IllegalArgumentException("transactionCount reads " + transactionCount
                    + " and the items supplied number " + transactions.size());
        }
    }

    /**
     * Builds one response from one card's read-model rows and the total the domain computed.
     *
     * <p>{@link PanMasker#maskCardNumber(String)} masks the card number here, and this method is
     * the one place in this service that masks one. Each row maps through
     * {@link NotificationTransactionItem#from(StatementTransactionEntity)} in the order supplied,
     * and the count comes from the mapped items.</p>
     *
     * <p>The total arrives at scale {@value PicClause#TRAN_AMT_SCALE} and renders through
     * {@link BigDecimal#toPlainString()}. {@code NotificationService.totalOf} in
     * {@code domain/NotificationService.java} accumulates it, truncating toward zero at every step.
     * A total at any other scale is refused here.</p>
     *
     * @param cardNumber the card this history covers, masked here before it reaches the response;
     *        must not be null
     * @param total the per-card total at scale {@value PicClause#TRAN_AMT_SCALE}; must not be null
     * @param rows the card's rows in ascending transaction-identifier order; must not be null
     * @return the response carrying the masked card number, the item count, the rendered total and
     *         one item per row
     * @throws NullPointerException when an argument is null, or when a row is null
     * @throws IllegalArgumentException when {@code total} carries another scale, or when the masked
     *         card number misses the shape the interface description declares
     */
    public static NotificationHistoryResponse fromCardRows(String cardNumber, BigDecimal total,
            List<StatementTransactionEntity> rows) {
        Objects.requireNonNull(cardNumber, "cardNumber is required");
        Objects.requireNonNull(total, "total is required");
        Objects.requireNonNull(rows, "rows is required");

        if (total.scale() != PicClause.TRAN_AMT_SCALE) {
            throw new IllegalArgumentException("total carries scale " + total.scale()
                    + " and the column declares " + PicClause.TRAN_AMT_SCALE);
        }

        List<NotificationTransactionItem> items = rows.stream()
                .map(NotificationTransactionItem::from)
                .toList();

        return new NotificationHistoryResponse(PanMasker.maskCardNumber(cardNumber), items.size(),
                total.toPlainString(), items);
    }
}
