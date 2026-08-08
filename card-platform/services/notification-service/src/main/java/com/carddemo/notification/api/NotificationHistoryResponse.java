package com.carddemo.notification.api;

import com.carddemo.cobol.PanMasker;
import com.carddemo.cobol.PicClause;
import com.carddemo.notification.domain.NotificationRenderer;
import com.carddemo.notification.entity.StatementTransactionEntity;
import java.math.BigDecimal;
import java.util.List;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * The response body of {@code GET /notifications/{cardNumber}}.
 *
 * <p>Four components carry one card's alert history: the masked card number, the count of
 * transactions, the per-card total and the transactions. The schema {@code NotificationHistory} in
 * {@code src/main/resources/openapi.yaml} declares those four properties in this order and sets
 * {@code additionalProperties: false}.
 *
 * <p>The card is named once, by the masked form of the number the request path carried. The card
 * token that keys the read model is a storage key and reaches no response: it names one card for as
 * long as its key stands, so publishing it would let a reader of a log follow that card across every
 * request that touched it.
 *
 * <p>Every component is always present. A card with no row in the read model carries an empty array,
 * a count of {@code 0} and a total of {@code "0.00"}, and it still names its card: the masked form is
 * derived from the path value rather than read from a row, so a card with no rows has one too.
 *
 * <p>The array covers the whole history of one card and carries no bound.
 * {@code app/cbl/CBSTM03A.CBL} reads every row of one card between two key breaks and
 * {@code app/cbl/CBSTM03A.CBL:L429} totals all of them, so a count and a total over part of a history
 * would describe a statement the source never produced.
 * {@link NotificationRenderer#MAXIMUM_STATEMENT_ROWS} bounds one rendered alert and not this body.
 *
 * <p>Design decisions: {@code card-platform/docs/decision-log.md}.
 *
 * @param cardNumber the masked card number: twelve mask characters then the last four digits, and
 *        never a full Primary Account Number. Display data alone, derived from the path value by
 *        {@link PanMasker#maskCardNumber(String)}. Width from {@code TRNX-CARD-NUM PIC X(16)} at
 *        {@code app/cpy/COSTM01.CPY:L22}. ADDITIVE. The source masks nothing.
 *        {@code app/bms/COCRDSL.bms:L99} shows the card field at {@code LENGTH=16},
 *        {@code CARD-NUM PIC X(16)} at {@code app/cpy/CVACT02Y.cpy:L5} is plain character data, and
 *        {@code app/cpy/CVACT02Y.cpy:L7} keeps the card verification value in the clear
 * @param transactionCount how many items {@code transactions} carries. Never negative, and always
 *        equal to the number of items in {@code transactions}
 * @param totalAmount the total of the items this response carries, as a decimal string: at most nine
 *        integer digits, a point, then two fractional digits. A refund total carries a leading minus.
 *        From {@code WS-TOTAL-AMT PIC S9(9)V99} at {@code app/cbl/CBSTM03A.CBL:L65}, zeroed for each
 *        card at {@code app/cbl/CBSTM03A.CBL:L325}, accumulated one row at a time at
 *        {@code app/cbl/CBSTM03A.CBL:L429} and moved to the print field at
 *        {@code app/cbl/CBSTM03A.CBL:L433-L434}. The print edit
 *        {@code ST-TOTAL-TRAMT PIC Z(9).99-} at {@code app/cbl/CBSTM03A.CBL:L142} carries a
 *        trailing sign
 * @param transactions one item per transaction of the card, in the ascending transaction-identifier
 *        order {@code app/jcl/CREASTMT.JCL:L53} produces. Unmodifiable
 */
public record NotificationHistoryResponse(String cardNumber, int transactionCount,
        String totalAmount, List<NotificationTransactionItem> transactions) {

    /**
     * Shape of a card number this response carries: twelve mask characters then four digits.
     *
     * <p>The property {@code cardNumber} of {@code src/main/resources/openapi.yaml} declares this
     * shape, and {@code ck_statement_transaction_masked_card_number} in
     * {@code src/main/resources/db/migration/V1__schema.sql} holds the stored column to the same
     * form. Total width stays
     * {@value PicClause#TRAN_CARD_NUM_WIDTH}, matching {@code TRNX-CARD-NUM PIC X(16)} at
     * {@code app/cpy/COSTM01.CPY:L22}.</p>
     */
    private static final Pattern MASKED_CARD_NUMBER =
            Pattern.compile("^\\*{12}[0-9]{4}$");

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
     * @throws NullPointerException when a component is null, or when an item is null
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
     * Builds one response from every read-model row of one card and the total the domain computed
     * over those rows.
     *
     * <p>The masked card number arrives derived from the request path value and is returned as it
     * stands. Deriving it from the path rather than from a row lets a card with no row name its card
     * too, which is what {@code app/cbl/CBSTM03A.CBL:L318-L325} does: the statement header carries
     * the card before any row of that card is read.</p>
     *
     * <p>Each row maps through
     * {@link NotificationTransactionItem#from(StatementTransactionEntity)} in the order supplied,
     * and the count comes from the mapped items. No row supplies a card number, so this method masks
     * nothing.</p>
     *
     * <p>The total arrives at scale {@value PicClause#TRAN_AMT_SCALE} and renders through
     * {@link BigDecimal#toPlainString()}. {@code NotificationService.totalOf} in
     * {@code domain/NotificationService.java} accumulates it, truncating toward zero at every step.
     * A total at any other scale is refused here.</p>
     *
     * @param maskedCardNumber the card this history covers, masked to twelve mask characters then
     *        four digits by {@link PanMasker#maskCardNumber(String)}; must not be null
     * @param total the total of the rows supplied, at scale {@value PicClause#TRAN_AMT_SCALE}; must
     *        not be null
     * @param rows every row of the card in ascending transaction-identifier order; must not be null
     * @return the response carrying the masked card number, the item count, the rendered total and one
     *         item per row
     * @throws NullPointerException when an argument is null, or when a row is null
     * @throws IllegalArgumentException when {@code total} carries another scale, or when
     *         {@code maskedCardNumber} misses the masked shape
     */
    public static NotificationHistoryResponse fromCardRows(String maskedCardNumber, BigDecimal total,
            List<StatementTransactionEntity> rows) {
        Objects.requireNonNull(maskedCardNumber, "maskedCardNumber is required");
        Objects.requireNonNull(total, "total is required");
        Objects.requireNonNull(rows, "rows is required");

        if (total.scale() != PicClause.TRAN_AMT_SCALE) {
            throw new IllegalArgumentException("total carries scale " + total.scale()
                    + " and the column declares " + PicClause.TRAN_AMT_SCALE);
        }

        List<NotificationTransactionItem> items = rows.stream()
                .map(NotificationTransactionItem::from)
                .toList();

        return new NotificationHistoryResponse(maskedCardNumber, items.size(),
                total.toPlainString(), items);
    }

    /**
     * Renders the item count and the total, and neither the card nor a transaction.
     *
     * <p>The rendering a record generates carries every item, and each item names a merchant, an
     * amount and a description of what a cardholder bought. That reaches any log line or exception
     * message naming this record, so the items are withheld and a caller reads them through
     * {@link #transactions()}.</p>
     *
     * <p>The masked card number is withheld with them. Its last four digits name one card for as long
     * as that card stands, so a log line carrying it would let a reader of the log follow that card
     * across every request that touched it. The count and the total are what a reader of a history
     * problem needs.
     *
     * @return the item count and the total, with the card number and the items withheld
     */
    @Override
    public String toString() {
        return "NotificationHistoryResponse[cardNumber=withheld"
                + ", transactionCount=" + transactionCount
                + ", totalAmount=" + totalAmount
                + ", transactions=" + transactionCount + " items withheld]";
    }
}
