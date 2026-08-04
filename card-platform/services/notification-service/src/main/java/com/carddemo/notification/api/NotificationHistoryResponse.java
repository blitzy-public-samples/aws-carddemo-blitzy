package com.carddemo.notification.api;

import com.carddemo.cobol.CobolDecimal;
import com.carddemo.cobol.PicClause;
import com.carddemo.notification.entity.StatementTransactionEntity;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * One card's alert history.
 *
 * <p>Four components: the masked card number, how many transactions the response carries, the
 * per-card total, and the transactions themselves.
 *
 * <p>The total resets per card. {@code MOVE ZERO TO WS-TOTAL-AMT} at
 * {@code app/cbl/CBSTM03A.CBL:L325} sits inside the per-card loop, and
 * {@code ADD TRNX-AMT TO WS-TOTAL-AMT} at {@code app/cbl/CBSTM03A.CBL:L429} accumulates one row at
 * a time. {@code WS-TOTAL-AMT PIC S9(9)V99} at {@code app/cbl/CBSTM03A.CBL:L65} fixes the scale.
 *
 * <p>Accumulation runs through {@link CobolDecimal}, which truncates toward zero. The
 * {@code ROUNDED} phrase appears zero times across the twenty-eight programs in {@code app/cbl/},
 * so every arithmetic store in the source truncates.
 *
 * @param cardNumber the masked card number: twelve mask characters then the last four digits
 * @param transactionCount how many items {@code transactions} carries
 * @param totalAmount the per-card total as a decimal string with two fractional digits. A leading
 *        minus is valid
 * @param transactions one item per transaction the read model holds for the card, in ascending
 *        transaction-identifier order
 */
public record NotificationHistoryResponse(String cardNumber, int transactionCount,
        String totalAmount, List<NotificationTransactionItem> transactions) {

    /** The total a card with no transactions carries. */
    private static final BigDecimal NO_TRANSACTIONS =
            BigDecimal.ZERO.setScale(PicClause.TRAN_AMT_SCALE);

    /**
     * Builds one response from one card's rows.
     *
     * <p>Each row is mapped and then its amount is added, preserving the order
     * {@code app/cbl/CBSTM03A.CBL:L428-L429} runs: write the detail, then accumulate.
     *
     * @param maskedCardNumber the masked card number the caller asked for
     * @param rows the card's rows in ascending transaction-identifier order
     * @return the response, carrying an empty array and a total of {@code "0.00"} when
     *         {@code rows} is empty
     * @throws NullPointerException when an argument is null
     */
    public static NotificationHistoryResponse of(String maskedCardNumber,
            List<StatementTransactionEntity> rows) {
        Objects.requireNonNull(maskedCardNumber, "maskedCardNumber is required");
        Objects.requireNonNull(rows, "rows is required");

        List<NotificationTransactionItem> items = new ArrayList<>(rows.size());
        BigDecimal total = NO_TRANSACTIONS;
        for (StatementTransactionEntity row : rows) {
            items.add(NotificationTransactionItem.from(row));
            total = CobolDecimal.add(total, row.getAmount(), PicClause.TRAN_AMT_SCALE);
        }
        return new NotificationHistoryResponse(maskedCardNumber, items.size(),
                total.toPlainString(), List.copyOf(items));
    }
}
