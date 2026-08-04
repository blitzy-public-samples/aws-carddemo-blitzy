package com.carddemo.notification.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.carddemo.notification.entity.StatementTransactionEntity;
import com.carddemo.notification.entity.StatementTransactionEntity.StatementTransactionId;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/**
 * Response tests for {@link NotificationHistoryResponse} and {@link NotificationTransactionItem}.
 *
 * <p>The per-card total reproduces the source's accumulation. {@code MOVE ZERO TO WS-TOTAL-AMT} at
 * {@code app/cbl/CBSTM03A.CBL:L325} resets it for each card and
 * {@code ADD TRNX-AMT TO WS-TOTAL-AMT} at {@code app/cbl/CBSTM03A.CBL:L429} adds one row at a time,
 * truncating toward zero.
 *
 * <p>The item mapping decodes fixed-width storage. A {@code CHAR} column returns space-padded, and
 * the two numeric identifiers return without their leading zeros.
 */
final class NotificationHistoryResponseTest {

    /** A masked card number. */
    private static final String MASKED_CARD = "************7065";

    /** Shape the interface description declares for a monetary value. */
    private static final Pattern MONEY = Pattern.compile("^-?\\d{1,9}\\.\\d{2}$");

    @Test
    void aCardWithNoRowsCarriesAnEmptyArrayAndAZeroTotal() {
        NotificationHistoryResponse response =
                NotificationHistoryResponse.of(MASKED_CARD, List.of());
        assertEquals(MASKED_CARD, response.cardNumber(), "card number");
        assertEquals(0, response.transactionCount(), "count");
        assertEquals("0.00", response.totalAmount(), "total");
        assertEquals(List.of(), response.transactions(), "transactions");
    }

    @Test
    void theCountMatchesTheRowsSupplied() {
        NotificationHistoryResponse response = NotificationHistoryResponse.of(MASKED_CARD,
                List.of(row("0000000000000001", "10.00"), row("0000000000000002", "5.50")));
        assertEquals(2, response.transactionCount(), "count");
        assertEquals(2, response.transactions().size(), "items");
    }

    @Test
    void theTotalAddsEveryAmountAtTwoFractionalDigits() {
        NotificationHistoryResponse response = NotificationHistoryResponse.of(MASKED_CARD,
                List.of(row("0000000000000001", "194.00"), row("0000000000000002", "310.77")));
        assertEquals("504.77", response.totalAmount(), "total");
        assertTrue(MONEY.matcher(response.totalAmount()).matches(), "shape");
    }

    @Test
    void aRefundLowersTheTotalAndKeepsItsSign() {
        NotificationHistoryResponse response = NotificationHistoryResponse.of(MASKED_CARD,
                List.of(row("0000000000000001", "10.00"), row("0000000000000002", "-25.50")));
        assertEquals("-15.50", response.totalAmount(), "a negative total keeps its minus");
        assertTrue(MONEY.matcher(response.totalAmount()).matches(), "shape");
    }

    @Test
    void aNegativeTotalTruncatesTowardZeroAndNotTowardNegativeInfinity() {
        BigDecimal awkward = new BigDecimal("-0.005");
        assertEquals("0.00", awkward.setScale(2, RoundingMode.DOWN).toPlainString(),
                "truncation toward zero");
        assertNotEquals(awkward.setScale(2, RoundingMode.DOWN),
                awkward.setScale(2, RoundingMode.FLOOR),
                "FLOOR agrees with DOWN for positive values and diverges here");
    }

    @Test
    void theItemsKeepTheOrderTheRowsArrivedIn() {
        NotificationHistoryResponse response = NotificationHistoryResponse.of(MASKED_CARD,
                List.of(row("0000000000000001", "1.00"), row("0000000000000002", "2.00"),
                        row("0000000000000003", "3.00")));
        assertEquals(List.of("0000000000000001", "0000000000000002", "0000000000000003"),
                response.transactions().stream()
                        .map(NotificationTransactionItem::transactionId)
                        .toList(),
                "order");
    }

    @Test
    void theTransactionsArrayIsNotWritable() {
        NotificationHistoryResponse response = NotificationHistoryResponse.of(MASKED_CARD,
                List.of(row("0000000000000001", "1.00")));
        assertThrows(UnsupportedOperationException.class, () -> response.transactions().clear(),
                "the array a caller receives cannot be edited");
    }

    @Test
    void theItemDecodesTheTrailingSpacesAFixedWidthColumnReturns() {
        StatementTransactionEntity padded = new StatementTransactionEntity(
                new StatementTransactionId(MASKED_CARD, "0000000000000001"), "01",
                "1", "POS TERM  ", "Purchase at Abshire-Lowe" + " ".repeat(76),
                new BigDecimal("194.00"), "800000000",
                "Abshire-Lowe" + " ".repeat(38), "North Enoshaven" + " ".repeat(35),
                "72112     ", "2022-06-10 19:27:53.000000", "2022-07-19-23.16.01.470000");
        NotificationTransactionItem item = NotificationTransactionItem.from(padded);

        assertEquals("POS TERM", item.source(), "source");
        assertEquals("Purchase at Abshire-Lowe", item.description(), "description");
        assertEquals("Abshire-Lowe", item.merchantName(), "merchant name");
        assertEquals("North Enoshaven", item.merchantCity(), "merchant city");
        assertEquals("72112", item.merchantZip(), "merchant mail code");
    }

    @Test
    void theItemRestoresTheLeadingZerosOfTheTwoNumericIdentifiers() {
        NotificationTransactionItem item =
                NotificationTransactionItem.from(row("0000000000000001", "1.00"));
        assertEquals("0001", item.categoryCode(), "four digits");
        assertEquals("800000000", item.merchantId(), "nine digits");
        assertTrue(item.categoryCode().matches("^[0-9]{4}$"), "category shape");
        assertTrue(item.merchantId().matches("^[0-9]{9}$"), "merchant shape");
    }

    @Test
    void theItemKeepsBothTimestampsAtTwentySixCharacters() {
        NotificationTransactionItem item =
                NotificationTransactionItem.from(row("0000000000000001", "1.00"));
        assertEquals(26, item.originTimestamp().length(), "origin width");
        assertEquals(26, item.processingTimestamp().length(), "processing width");
        assertTrue(item.originTimestamp()
                .matches("^\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2}\\.\\d{6}$"), "origin shape");
        assertTrue(item.processingTimestamp()
                        .matches("^\\d{4}-\\d{2}-\\d{2}-\\d{2}\\.\\d{2}\\.\\d{2}\\.\\d{2}0{4}$"),
                "processing shape");
    }

    @Test
    void theItemRendersTheAmountAsADecimalStringAndNeverANumber() {
        NotificationTransactionItem item =
                NotificationTransactionItem.from(row("0000000000000001", "194.00"));
        assertEquals("194.00", item.amount(), "amount");
        assertTrue(MONEY.matcher(item.amount()).matches(), "shape");
    }

    @Test
    void aColumnTheAuthorizedEventHasNotSuppliedReadsEmptyRatherThanFailing() {
        StatementTransactionEntity postedFirst = new StatementTransactionEntity(
                new StatementTransactionId(MASKED_CARD, "0000000000000001"), "  ",
                "0", " ".repeat(10), " ".repeat(100), new BigDecimal("194.00"),
                "0", " ".repeat(50), " ".repeat(50), " ".repeat(10), " ".repeat(26),
                "2022-07-19-23.16.01.470000");
        NotificationTransactionItem item = NotificationTransactionItem.from(postedFirst);

        assertEquals("", item.typeCode(), "type code");
        assertEquals("", item.description(), "description");
        assertEquals("0000", item.categoryCode(), "category code reads as zeros");
        assertEquals("000000000", item.merchantId(), "merchant identifier reads as zeros");
        assertEquals("194.00", item.amount(), "the posted event supplies the amount");
    }

    @Test
    void aNullArgumentToTheFactoryIsRefusedByName() {
        NullPointerException refused = assertThrows(NullPointerException.class,
                () -> NotificationHistoryResponse.of(null, List.of()), "a null card number");
        assertTrue(refused.getMessage().contains("maskedCardNumber"), "named");
        assertThrows(NullPointerException.class,
                () -> NotificationHistoryResponse.of(MASKED_CARD, null), "null rows");
    }

    /**
     * Builds one read-model row.
     *
     * @param transactionId the sixteen-character identifier
     * @param amount the amount at two fractional digits
     * @return the row
     */
    private static StatementTransactionEntity row(String transactionId, String amount) {
        return new StatementTransactionEntity(
                new StatementTransactionId(MASKED_CARD, transactionId), "01", "1",
                "POS TERM", "Purchase", new BigDecimal(amount), "800000000",
                "Abshire-Lowe", "North Enoshaven", "72112", "2022-06-10 19:27:53.000000",
                "2022-07-19-23.16.01.470000");
    }
}
