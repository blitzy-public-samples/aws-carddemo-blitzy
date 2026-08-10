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
 * <p>Six components carry one card's alert history: the masked card number, the count of
 * transactions, the per-card total, the transactions of this page, whether a further page exists and
 * the cursor that asks for it. The schema {@code NotificationHistory} in
 * {@code src/main/resources/openapi.yaml} declares those properties in this order and sets
 * {@code additionalProperties: false}.
 *
 * <p>The card is named once, by the masked form the read-model rows already hold in column
 * {@code masked_card_number}. The request path carries that card's token and no digit of its number,
 * so nothing here is derived from the path. The token itself reaches no response either: it names one
 * card for as long as its key stands, so publishing it would let a reader of a log follow that card
 * across every request that touched it.
 *
 * <p>Every component is always present, and this record is built only where at least one row was
 * read. A card with no row has no stored masked number to name it with, so
 * {@code api/NotificationHistoryController} answers {@code 404} there rather than inventing one.
 *
 * <p>The count and the total cover the card's whole history; the array carries one bounded page of
 * it. That split is deliberate and it is what keeps the source's own claim true.
 * {@code app/cbl/CBSTM03A.CBL} reads every row of one card between two key breaks and
 * {@code app/cbl/CBSTM03A.CBL:L429} totals all of them, so a count or a total over part of a history
 * would describe a statement the source never produced. Both are therefore read as one aggregate over
 * the key rather than by counting the items present, which is also why they are the two components a
 * caller cannot derive from this body.
 *
 * <p>The array was once unbounded, and one request then materialised every retained row of a card,
 * totalled them in memory and copied the list. Work and response size both grew with one card's
 * history with nothing capping either. {@code api/NotificationHistoryController} now serves a page at
 * a time and {@link #nextCursor()} continues the walk.
 * {@link NotificationRenderer#MAXIMUM_STATEMENT_ROWS} bounds one rendered alert and not this page.
 *
 * <p>Design decisions: {@code card-platform/docs/decision-log.md}.
 *
 * @param cardNumber the masked card number: twelve mask characters then the last four digits, and
 *        never a full Primary Account Number. Display data alone, read from column
 *        {@code masked_card_number} of the rows, which {@link PanMasker#maskCardNumber(String)}
 *        produced upstream. Width from {@code TRNX-CARD-NUM PIC X(16)} at
 *        {@code app/cpy/COSTM01.CPY:L22}. ADDITIVE. The source masks nothing.
 *        {@code app/bms/COCRDSL.bms:L99} shows the card field at {@code LENGTH=16},
 *        {@code CARD-NUM PIC X(16)} at {@code app/cpy/CVACT02Y.cpy:L5} is plain character data, and
 *        {@code app/cpy/CVACT02Y.cpy:L7} keeps the card verification value in the clear
 * @param transactionCount how many rows the read model holds for this card in all, which is at least
 *        the number of items {@code transactions} carries and is greater wherever a further page
 *        exists. Never negative. Read as {@code COUNT(*)} over the card's key rather than from the
 *        items, so it describes the row set {@code app/cbl/CBSTM03A.CBL:L429} would have totalled
 * @param totalAmount the total of the card's whole history, not of the page, as a decimal string: at most nine
 *        integer digits, a point, then two fractional digits. A refund total carries a leading minus.
 *        From {@code WS-TOTAL-AMT PIC S9(9)V99} at {@code app/cbl/CBSTM03A.CBL:L65}, zeroed for each
 *        card at {@code app/cbl/CBSTM03A.CBL:L325}, accumulated one row at a time at
 *        {@code app/cbl/CBSTM03A.CBL:L429} and moved to the print field at
 *        {@code app/cbl/CBSTM03A.CBL:L433-L434}. The print edit
 *        {@code ST-TOTAL-TRAMT PIC Z(9).99-} at {@code app/cbl/CBSTM03A.CBL:L142} carries a
 *        trailing sign
 * @param transactions one item per transaction on this page, in the ascending transaction-identifier
 *        order {@code app/jcl/CREASTMT.JCL:L53} produces. Bounded by the page size the request asked
 *        for. Unmodifiable
 * @param nextPageExists whether the read model holds a further row of this card beyond the last item
 *        here. Derived from a lookahead row rather than from {@code transactionCount}, so it stays
 *        right even where rows arrive between two requests
 * @param nextCursor the transaction identifier to continue from, present exactly when
 *        {@code nextPageExists} is true and {@code null} otherwise. It is the identifier of the last
 *        item of this page, which the items already carry, so it discloses nothing further
 */
// A last page has no cursor, and the schema declares nextCursor as a string rather than a nullable
// one, so the property is omitted rather than serialized as null. Every other component is checked
// non-null by the constructor, so this drops exactly the one value that can be absent.
@JsonInclude(JsonInclude.Include.NON_NULL)
public record NotificationHistoryResponse(String cardNumber, int transactionCount,
        String totalAmount, List<NotificationTransactionItem> transactions,
        boolean nextPageExists, String nextCursor) {

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
     * @throws NullPointerException when a required component is null, or when an item is null
     * @throws IllegalArgumentException when the card number misses the masked shape, when the total
     *         misses the decimal shape, when the count is negative, when the count is smaller than the
     *         items present, or when the cursor and the further-page flag disagree
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

        // The count covers the whole history and the items cover one page of it, so the two are equal
        // on a history that fits one page and the count is the larger otherwise. It can never be the
        // smaller: that would mean a page carrying rows the card does not hold.
        if (transactionCount < transactions.size()) {
            throw new IllegalArgumentException("transactionCount reads " + transactionCount
                    + " and the items supplied number " + transactions.size());
        }
        // A cursor names where to continue, so it is present exactly when there is somewhere to
        // continue to. A cursor on a last page would ask a caller to fetch an empty page, and a
        // missing one on a further page would strand the walk halfway through a history.
        if (nextPageExists == (nextCursor == null)) {
            throw new IllegalArgumentException(nextPageExists
                    ? "a further page exists, so nextCursor is required"
                    : "no further page exists, so nextCursor must be absent");
        }
        if (nextCursor != null && nextCursor.isBlank()) {
            throw new IllegalArgumentException("nextCursor is blank");
        }
    }

    /**
     * Builds one response from every read-model row of one card and the total the domain computed
     * over those rows.
     *
     * <p>The masked card number arrives read from the first row of the card and is returned as it
     * stands. The source put the card in the statement header before reading any row of it at
     * {@code app/cbl/CBSTM03A.CBL:L318-L325}, which a path carrying the number could reproduce and a
     * path carrying a token cannot, so a card with no row is answered {@code 404} by the controller
     * instead of an invented header.</p>
     *
     * <p>Each row maps through
     * {@link NotificationTransactionItem#from(StatementTransactionEntity)} in the order supplied. No
     * row supplies a card number, so this method masks nothing.</p>
     *
     * <p>The count and the total describe the card's whole history and are supplied rather than
     * derived, because the rows supplied are one page of it. Deriving either from the page would
     * report a statement over part of a card, which {@code app/cbl/CBSTM03A.CBL:L429} never
     * produced.</p>
     *
     * <p>The total arrives at scale {@value PicClause#TRAN_AMT_SCALE} and renders through
     * {@link BigDecimal#toPlainString()}. {@code NotificationService.totalOfCard} in
     * {@code domain/NotificationService.java} answers it, reproducing the truncation the source's
     * accumulation applies. A total at any other scale is refused here.</p>
     *
     * @param maskedCardNumber the card this history covers, read from column
     *        {@code masked_card_number} of the card's rows, which holds twelve mask characters then
     *        four digits; must not be null
     * @param historyCount how many rows the card holds in all, at least the rows supplied
     * @param total the total of the card's whole history, at scale
     *        {@value PicClause#TRAN_AMT_SCALE}; must not be null
     * @param rows this page's rows in ascending transaction-identifier order; must not be null
     * @param furtherPageExists whether the read model holds a row beyond the last one supplied
     * @return the response carrying the masked card number, the whole-history count and total, one
     *         item per supplied row, and the continuation of the walk where one exists
     * @throws NullPointerException when a required argument is null, or when a row is null
     * @throws IllegalArgumentException when {@code total} carries another scale, when
     *         {@code maskedCardNumber} misses the masked shape, when {@code historyCount} is smaller
     *         than the rows supplied, or when a further page is claimed over no rows
     */
    public static NotificationHistoryResponse fromCardRows(String maskedCardNumber, int historyCount,
            BigDecimal total, List<StatementTransactionEntity> rows, boolean furtherPageExists) {
        Objects.requireNonNull(maskedCardNumber, "maskedCardNumber is required");
        Objects.requireNonNull(total, "total is required");
        Objects.requireNonNull(rows, "rows is required");

        if (total.scale() != PicClause.TRAN_AMT_SCALE) {
            throw new IllegalArgumentException("total carries scale " + total.scale()
                    + " and the column declares " + PicClause.TRAN_AMT_SCALE);
        }
        if (furtherPageExists && rows.isEmpty()) {
            throw new IllegalArgumentException(
                    "a further page cannot follow a page carrying no row, because the cursor that"
                            + " would ask for it is the identifier of a row of this page");
        }

        List<NotificationTransactionItem> items = rows.stream()
                .map(NotificationTransactionItem::from)
                .toList();
        // The cursor is the last identifier of this page, present exactly when a further page exists.
        // Taken from the entity rather than the mapped item, so the value is the stored key the keyset
        // read compares against.
        String cursor = furtherPageExists
                ? rows.get(rows.size() - 1).getId().getTransactionId()
                : null;

        return new NotificationHistoryResponse(maskedCardNumber, historyCount,
                total.toPlainString(), items, furtherPageExists, cursor);
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
