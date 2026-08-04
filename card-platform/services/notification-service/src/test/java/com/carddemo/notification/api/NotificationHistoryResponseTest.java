package com.carddemo.notification.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

import com.carddemo.notification.config.ObservabilityConfig;
import com.carddemo.notification.domain.NotificationService;
import com.carddemo.notification.entity.StatementTransactionEntity;
import com.carddemo.notification.entity.StatementTransactionEntity.StatementTransactionId;
import com.carddemo.notification.repository.NotificationLogRepository;
import com.carddemo.notification.repository.StatementTransactionRepository;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * Response tests for {@link NotificationHistoryResponse} and {@link NotificationTransactionItem}.
 *
 * <p>{@link NotificationService#totalOf(List)} owns the per-card total, and this record renders it.
 * {@code MOVE ZERO TO WS-TOTAL-AMT} at {@code app/cbl/CBSTM03A.CBL:L325} resets it for each card and
 * {@code ADD TRNX-AMT TO WS-TOTAL-AMT} at {@code app/cbl/CBSTM03A.CBL:L429} adds one row at a time,
 * truncating toward zero. Every total below travels through that service, so the two agree.
 *
 * <p>The item mapping decodes fixed-width storage. A {@code CHAR} column returns space-padded, and
 * the two numeric identifiers return without their leading zeros.
 */
final class NotificationHistoryResponseTest {

    /** A masked card number. */
    private static final String MASKED_CARD = "************7065";

    /** A full card number, split so no sixteen-digit literal appears in one piece. */
    private static final String FULL_CARD = "4859452612877" + "065";

    /** Shape the interface description declares for a monetary value. */
    private static final Pattern MONEY = Pattern.compile("^-?\\d{1,9}\\.\\d{2}$");

    /** Totals rows the way the endpoint does. */
    private static final NotificationService NOTIFICATIONS = new NotificationService(List.of(),
            mock(StatementTransactionRepository.class), mock(NotificationLogRepository.class),
            new ObservabilityConfig().notificationMetrics(new SimpleMeterRegistry()));

    @Test
    void aCardWithNoRowsCarriesAnEmptyArrayAndAZeroTotal() {
        NotificationHistoryResponse response = response(List.of());
        assertEquals(MASKED_CARD, response.cardNumber(), "card number");
        assertEquals(0, response.transactionCount(), "count");
        assertEquals("0.00", response.totalAmount(), "total");
        assertEquals(List.of(), response.transactions(), "transactions");
    }

    @Test
    void theCountMatchesTheRowsSupplied() {
        NotificationHistoryResponse response = response(
                List.of(row("0000000000000001", "10.00"), row("0000000000000002", "5.50")));
        assertEquals(2, response.transactionCount(), "count");
        assertEquals(2, response.transactions().size(), "items");
    }

    @Test
    void theTotalAddsEveryAmountAtTwoFractionalDigits() {
        NotificationHistoryResponse response = response(
                List.of(row("0000000000000001", "194.00"), row("0000000000000002", "310.77")));
        assertEquals("504.77", response.totalAmount(), "total");
        assertTrue(MONEY.matcher(response.totalAmount()).matches(), "shape");
    }

    @Test
    void aRefundLowersTheTotalAndKeepsItsSign() {
        NotificationHistoryResponse response = response(
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
        NotificationHistoryResponse response = response(
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
        NotificationHistoryResponse response = response(List.of(row("0000000000000001", "1.00")));
        assertThrows(UnsupportedOperationException.class, () -> response.transactions().clear(),
                "the array a caller receives cannot be edited");
    }

    @Test
    void theFactoryMasksTheCardNumberItIsGiven() {
        NotificationHistoryResponse response = NotificationHistoryResponse.fromCardRows(FULL_CARD,
                NOTIFICATIONS.totalOf(List.of()), List.of());
        assertEquals(MASKED_CARD, response.cardNumber(), "the response carries the masked form");
        assertFalse(response.cardNumber().contains(FULL_CARD.substring(0, 12)),
                "no digit but the last four reaches the response");
    }

    @Test
    void aMaskedCardNumberPassesThroughTheFactoryUnchanged() {
        NotificationHistoryResponse response = NotificationHistoryResponse.fromCardRows(MASKED_CARD,
                NOTIFICATIONS.totalOf(List.of()), List.of());
        assertEquals(MASKED_CARD, response.cardNumber(), "masking the masked form changes nothing");
    }

    @Test
    void aTotalAtAnotherScaleIsRefused() {
        IllegalArgumentException refused = assertThrows(IllegalArgumentException.class,
                () -> NotificationHistoryResponse.fromCardRows(MASKED_CARD, new BigDecimal("1.5"),
                        List.of()),
                "a total at one fractional digit");
        assertTrue(refused.getMessage().contains("scale"), "the message names the scale");
    }

    @Test
    void aCardNumberThatIsNotMaskedIsRefusedByTheConstructor() {
        IllegalArgumentException refused = assertThrows(IllegalArgumentException.class,
                () -> new NotificationHistoryResponse(FULL_CARD, 0, "0.00", List.of()),
                "a full card number on the envelope");
        assertFalse(refused.getMessage().contains(FULL_CARD),
                "no message names a card number");
    }

    @Test
    void aTotalOutsideTheDeclaredShapeIsRefusedByTheConstructor() {
        assertThrows(IllegalArgumentException.class,
                () -> new NotificationHistoryResponse(MASKED_CARD, 0, "0", List.of()),
                "a total without its two fractional digits");
        assertThrows(IllegalArgumentException.class,
                () -> new NotificationHistoryResponse(MASKED_CARD, 0, "1234567890.00", List.of()),
                "a total at ten integer digits");
    }

    @Test
    void aCountThatDisagreesWithTheItemsIsRefused() {
        List<NotificationTransactionItem> one =
                List.of(NotificationTransactionItem.from(row("0000000000000001", "1.00")));
        assertThrows(IllegalArgumentException.class,
                () -> new NotificationHistoryResponse(MASKED_CARD, 2, "1.00", one),
                "a count above the items supplied");
        assertThrows(IllegalArgumentException.class,
                () -> new NotificationHistoryResponse(MASKED_CARD, -1, "0.00", List.of()),
                "a negative count");
    }

    @Test
    void theSerializedBodyCarriesExactlyFourPropertiesInTheSchemaOrder() {
        NotificationHistoryResponse response = response(
                List.of(row("0000000000000001", "194.00"), row("0000000000000002", "310.77")));
        ObjectMapper mapper = JsonMapper.builder().build();

        JsonNode body = mapper.readTree(mapper.writeValueAsString(response));
        List<String> properties = new ArrayList<>();
        for (Iterator<String> names = body.propertyNames().iterator(); names.hasNext();) {
            properties.add(names.next());
        }

        assertEquals(List.of("cardNumber", "transactionCount", "totalAmount", "transactions"),
                properties, "the four properties the schema declares, in that order");
        assertEquals(4, body.size(), "additionalProperties is false, so there is no fifth");
        assertTrue(body.get("totalAmount").isString(), "money travels as text, never as a number");
        assertEquals(response, mapper.readValue(mapper.writeValueAsString(response),
                NotificationHistoryResponse.class), "the body round trips field for field");
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
        NullPointerException noCard = assertThrows(NullPointerException.class,
                () -> NotificationHistoryResponse.fromCardRows(null,
                        NOTIFICATIONS.totalOf(List.of()), List.of()),
                "a null card number");
        assertTrue(noCard.getMessage().contains("cardNumber"), "named");

        NullPointerException noTotal = assertThrows(NullPointerException.class,
                () -> NotificationHistoryResponse.fromCardRows(MASKED_CARD, null, List.of()),
                "a null total");
        assertTrue(noTotal.getMessage().contains("total"), "named");

        assertThrows(NullPointerException.class,
                () -> NotificationHistoryResponse.fromCardRows(MASKED_CARD,
                        NOTIFICATIONS.totalOf(List.of()), null),
                "null rows");
    }

    /**
     * Builds one response the way the endpoint does: the domain totals the rows, and the record
     * renders them.
     *
     * @param rows the card's rows in ascending transaction-identifier order
     * @return the response
     */
    private static NotificationHistoryResponse response(List<StatementTransactionEntity> rows) {
        return NotificationHistoryResponse.fromCardRows(MASKED_CARD, NOTIFICATIONS.totalOf(rows),
                rows);
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
