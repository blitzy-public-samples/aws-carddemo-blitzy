package com.carddemo.notification.api;

import com.carddemo.cobol.PanMasker;
import com.carddemo.cobol.PicClause;
import com.carddemo.notification.domain.NotificationRenderer;
import com.carddemo.notification.entity.StatementTransactionEntity;
import com.fasterxml.jackson.annotation.JsonInclude;
import java.math.BigDecimal;
import java.util.List;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * The response body of {@code GET /notifications/{cardToken}}.
 *
 * <p>Five components carry one card's alert history: the card token, the masked card number, the
 * count of transactions, the per-card total and the transactions. The schema
 * {@code NotificationHistory} in {@code src/main/resources/openapi.yaml} declares those five
 * properties in this order and sets {@code additionalProperties: false}.
 *
 * <p>Identity and display are two components, not one. {@code cardToken} identifies the card: it is
 * the key the read model is stored under, the path this response was reached by, and the value a
 * caller uses to reach the next page. {@code cardNumber} identifies nothing, because two cards
 * sharing their last four digits mask to one value; it is there so a person reading the response
 * recognises the card.
 *
 * <p>A card with no row in the read model carries an empty array, a count of {@code 0}, a total of
 * {@code "0.00"} and no {@code cardNumber} at all. The masked form of a card is read from the rows
 * this service holds, so a card with no rows has no masked form to report and none is invented.
 *
 * <p>The array is bounded by {@value NotificationRenderer#MAXIMUM_STATEMENT_ROWS} items, the ceiling
 * {@link NotificationRenderer#MAXIMUM_STATEMENT_ROWS} declares and
 * {@code src/main/resources/openapi.yaml} repeats as {@code maxItems}. Nothing removes a statement
 * row, so an unbounded array would grow with the life of the card.
 *
 * <p>Design decisions: {@code card-platform/docs/decision-log.md}.
 *
 * @param cardToken the card identity: {@value PanMasker#CARD_TOKEN_LENGTH} lower-case hexadecimal
 *        characters that {@code PanMasker.cardToken} derives. ADDITIVE. It stands in for
 *        {@code TRNX-CARD-NUM PIC X(16)} at {@code app/cpy/COSTM01.CPY:L22}, whose value was a full
 *        Primary Account Number. The derivation is one-way and keyed under a deployment-supplied
 *        key, so this value discloses no card number and a reader holding it cannot recompute it
 *        over the card-number space
 * @param cardNumber the masked card number: twelve mask characters then the last four digits, and
 *        never a full Primary Account Number. Display data alone, and {@code null} when the read
 *        model holds no row for the card. Width from {@code TRNX-CARD-NUM PIC X(16)} at
 *        {@code app/cpy/COSTM01.CPY:L22}. ADDITIVE. The source masks nothing.
 *        {@code app/bms/COCRDSL.bms:L99} shows the card field at {@code LENGTH=16},
 *        {@code CARD-NUM PIC X(16)} at {@code app/cpy/CVACT02Y.cpy:L5} is plain character data, and
 *        {@code app/cpy/CVACT02Y.cpy:L7} keeps the card verification value in the clear
 * @param transactionCount how many items {@code transactions} carries. Never negative, never above
 *        {@value NotificationRenderer#MAXIMUM_STATEMENT_ROWS}, and always equal to the number of
 *        items in {@code transactions}
 * @param totalAmount the total of the items this response carries, as a decimal string: at most nine
 *        integer digits, a point, then two fractional digits. A refund total carries a leading minus.
 *        From {@code WS-TOTAL-AMT PIC S9(9)V99} at {@code app/cbl/CBSTM03A.CBL:L65}, zeroed for each
 *        card at {@code app/cbl/CBSTM03A.CBL:L325}, accumulated one row at a time at
 *        {@code app/cbl/CBSTM03A.CBL:L429} and moved to the print field at
 *        {@code app/cbl/CBSTM03A.CBL:L433-L434}. The print edit
 *        {@code ST-TOTAL-TRAMT PIC Z(9).99-} at {@code app/cbl/CBSTM03A.CBL:L142} carries a
 *        trailing sign
 * @param transactions one item per transaction this response carries, in the ascending
 *        transaction-identifier order {@code app/jcl/CREASTMT.JCL:L53} produces. Unmodifiable, and at
 *        most {@value NotificationRenderer#MAXIMUM_STATEMENT_ROWS} items
 */
public record NotificationHistoryResponse(String cardToken,
        @JsonInclude(JsonInclude.Include.NON_NULL) String cardNumber, int transactionCount,
        String totalAmount, List<NotificationTransactionItem> transactions) {

    /**
     * Shape of a card number this response carries: twelve mask characters then four digits.
     *
     * <p>The property {@code cardNumber} of {@code src/main/resources/openapi.yaml} declares this
     * shape, and {@code ck_statement_transaction_masked_card_number} in
     * {@code src/main/resources/db/migration/V1__schema.sql} holds the stored column to the same
     * form. A response with no row carries {@code null}, so no second fully-masked sentinel is
     * needed. Total width stays
     * {@value PicClause#TRAN_CARD_NUM_WIDTH}, matching {@code TRNX-CARD-NUM PIC X(16)} at
     * {@code app/cpy/COSTM01.CPY:L22}.</p>
     */
    private static final Pattern MASKED_CARD_NUMBER =
            Pattern.compile("^\\*{12}[0-9]{4}$");

    /**
     * Shape of a card token: {@value PanMasker#CARD_TOKEN_LENGTH} lower-case hexadecimal characters.
     *
     * <p>The property {@code cardToken} of {@code src/main/resources/openapi.yaml} declares this
     * shape, {@code ck_statement_transaction_card_token} in
     * {@code src/main/resources/db/migration/V1__schema.sql} holds the stored key column to it, and
     * {@code PanMasker.cardToken} produces it.</p>
     */
    private static final Pattern CARD_TOKEN = Pattern.compile(PanMasker.CARD_TOKEN_PATTERN);

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
     * <p>The card token arrives derived, the card number arrives masked and the total arrives
     * rendered. This constructor changes none of the three, and no message it raises names a card
     * number or a token.</p>
     *
     * @throws NullPointerException when {@code cardToken}, {@code totalAmount} or
     *         {@code transactions} is null, or when an item is null
     * @throws IllegalArgumentException when the card token misses the token shape, when a supplied
     *         card number misses the masked shape, when the total misses the decimal shape, when the
     *         count is negative, when the count and the item count differ, or when the items exceed
     *         {@value NotificationRenderer#MAXIMUM_STATEMENT_ROWS}
     */
    public NotificationHistoryResponse {
        Objects.requireNonNull(cardToken, "cardToken is required");
        Objects.requireNonNull(totalAmount, "totalAmount is required");
        Objects.requireNonNull(transactions, "transactions is required");

        if (!CARD_TOKEN.matcher(cardToken).matches()) {
            throw new IllegalArgumentException("cardToken must carry "
                    + PanMasker.CARD_TOKEN_LENGTH + " lower-case hexadecimal characters");
        }
        if (cardNumber != null && !MASKED_CARD_NUMBER.matcher(cardNumber).matches()) {
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
        if (transactions.size() > NotificationRenderer.MAXIMUM_STATEMENT_ROWS) {
            throw new IllegalArgumentException("transactions holds " + transactions.size()
                    + " items and one response carries at most "
                    + NotificationRenderer.MAXIMUM_STATEMENT_ROWS);
        }
        if (transactions.isEmpty() && cardNumber != null) {
            throw new IllegalArgumentException(
                    "cardNumber is absent when transactions holds no item");
        }
        if (!transactions.isEmpty() && cardNumber == null) {
            throw new IllegalArgumentException(
                    "cardNumber is required when transactions holds an item");
        }
        if (cardNumber != null && transactions.stream()
                .anyMatch(item -> !cardNumber.equals(item.maskedCardNumber()))) {
            throw new IllegalArgumentException(
                    "cardNumber must equal the masked card number of every transaction");
        }
    }

    /**
     * Builds one response from one page of an account's read-model rows and the total the domain
     * computed over that page.
     *
     * <p>The card token arrives from the request path and is returned as it stands. The masked card
     * number is read from the rows rather than derived here: every row of one card carries the same
     * masked form, so the first row supplies it, and a card with no rows reports none. Masking
     * happens where a row is written, which is why no card number reaches this method.</p>
     *
     * <p>Each row maps through
     * {@link NotificationTransactionItem#from(StatementTransactionEntity)} in the order supplied,
     * and the count comes from the mapped items. Each item carries the masked card number its row
     * stores, so this method masks nothing: a full Primary Account Number never reaches this service
     * and therefore never reaches this method.</p>
     *
     * <p>The total arrives at scale {@value PicClause#TRAN_AMT_SCALE} and renders through
     * {@link BigDecimal#toPlainString()}. {@code NotificationService.totalOf} in
     * {@code domain/NotificationService.java} accumulates it, truncating toward zero at every step.
     * A total at any other scale is refused here.</p>
     *
     * @param cardToken the card this history covers, as the request path carried it; must not be null
     * @param total the total of the rows supplied, at scale {@value PicClause#TRAN_AMT_SCALE}; must
     *        not be null
     * @param rows the card's rows in ascending transaction-identifier order, at most
     *        {@value NotificationRenderer#MAXIMUM_STATEMENT_ROWS} of them; must not be null
     * @return the response carrying the card token, the masked card number the rows report, the item
     *         count, the rendered total and one item per row
     * @throws NullPointerException when an argument is null, or when a row is null
     * @throws IllegalArgumentException when {@code total} carries another scale, when the card token
     *         misses the shape the interface description declares, or when {@code rows} exceeds
     *         {@value NotificationRenderer#MAXIMUM_STATEMENT_ROWS}
     */
    public static NotificationHistoryResponse fromCardRows(String cardToken, BigDecimal total,
            List<StatementTransactionEntity> rows) {
        Objects.requireNonNull(cardToken, "cardToken is required");
        Objects.requireNonNull(total, "total is required");
        Objects.requireNonNull(rows, "rows is required");

        if (total.scale() != PicClause.TRAN_AMT_SCALE) {
            throw new IllegalArgumentException("total carries scale " + total.scale()
                    + " and the column declares " + PicClause.TRAN_AMT_SCALE);
        }

        List<NotificationTransactionItem> items = rows.stream()
                .map(NotificationTransactionItem::from)
                .toList();
        String maskedCardNumber = rows.isEmpty()
                ? null
                : rows.getFirst().getMaskedCardNumber().stripTrailing();

        return new NotificationHistoryResponse(cardToken, maskedCardNumber, items.size(),
                total.toPlainString(), items);
    }

    /**
     * Renders the card token, the item count and the total, and no transaction.
     *
     * <p>The rendering a record generates carries every item, and each item names a merchant, an
     * amount and a description of what a cardholder bought. That reaches any log line or exception
     * message naming this record, so the items are withheld and a caller reads them through
     * {@link #transactions()}.</p>
     *
     * <p>The card token is withheld with them. It discloses no card number, but it names one card
     * for as long as the key behind it stands, so a log line carrying it would let a reader of the
     * log follow that card across every request that touched it. The count and the total are what a
     * reader of a history problem needs.
     *
     * @return the item count and the total, with the card token and the items withheld
     */
    @Override
    public String toString() {
        return "NotificationHistoryResponse[cardToken=withheld"
                + ", transactionCount=" + transactionCount
                + ", totalAmount=" + totalAmount
                + ", transactions=" + transactionCount + " items withheld]";
    }
}
