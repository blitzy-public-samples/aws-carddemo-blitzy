package com.carddemo.notification.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

import com.carddemo.cobol.PanMasker;
import com.carddemo.notification.config.ObservabilityConfig;
import com.carddemo.notification.domain.NotificationRenderer;
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
import java.util.Locale;
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

    /** A masked card number, which every item displays. */
    private static final String MASKED_CARD = "************7065";

    /** A full card number, split so no sixteen-digit literal appears in one piece. */
    private static final String FULL_CARD = "4859452612877" + "065";

    /** Card token of that card, and the identity every response below is keyed on. */
    private static final String CARD_TOKEN = PanMasker.cardToken(FULL_CARD);

    /** Card token of a second card, used where a token has to differ from {@link #CARD_TOKEN}. */
    private static final String OTHER_CARD_TOKEN = PanMasker.cardToken("4859452612870" + "001");

    /** Shape the interface description declares for a monetary value. */
    private static final Pattern MONEY = Pattern.compile("^-?\\d{1,9}\\.\\d{2}$");

    /** Totals rows the way the endpoint does. */
    private static final NotificationService NOTIFICATIONS = new NotificationService(List.of(),
            mock(StatementTransactionRepository.class), mock(NotificationLogRepository.class),
            new ObservabilityConfig().notificationMetrics(new SimpleMeterRegistry()));

    @Test
    void anAccountWithNoRowsCarriesAnEmptyArrayAndAZeroTotal() {
        NotificationHistoryResponse response = response(List.of());
        assertEquals(CARD_TOKEN, response.cardToken(), "card token");
        assertNull(response.cardNumber(),
                "no row holds the masked form, so the envelope invents none");
        assertEquals(0, response.transactionCount(), "count");
        assertEquals("0.00", response.totalAmount(), "total");
        assertEquals(List.of(), response.transactions(), "transactions");
    }

    @Test
    void theCardNumberComesFromTheStoredRowAndNotFromTheRequest() {
        NotificationHistoryResponse response = response(List.of(row("0000000000000001", "1.00")));
        assertEquals(MASKED_CARD, response.cardNumber(),
                "the response reports the masked form the row holds");
        assertEquals(CARD_TOKEN, response.cardToken(), "the token travels through unchanged");
        assertFalse(response.cardNumber().contains(FULL_CARD.substring(0, 12)),
                "no digit but the last four reaches the response");
    }

    @Test
    void theCountMatchesTheRowsSupplied() {
        NotificationHistoryResponse response = response(
                List.of(row("0000000000000001", "10.00"), row("0000000000000002", "5.50")));
        assertEquals(MASKED_CARD, response.cardNumber(), "the rows supply the masked card number");
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

    /**
     * Asserts each item carries the masked card number its row stores, and that the envelope names
     * the account rather than a card.
     *
     * <p>The masked value is display only. It identifies no card, because every card whose last four
     * digits agree masks to the same sixteen characters, so it cannot address a response.
     */
    @Test
    void theFactoryReadsTheMaskedFormFromTheRowsAndTheTokenFromTheRequest() {
        NotificationHistoryResponse response = response(List.of(row("0000000000000001", "1.00")));

        assertEquals(CARD_TOKEN, response.cardToken(), "the token the request path carried");
        assertEquals(MASKED_CARD, response.cardNumber(), "the masked form the rows report");
        assertFalse(response.cardNumber().contains(FULL_CARD.substring(0, 12)),
                "no digit but the last four reaches the response");
        assertFalse(response.toString().contains(MASKED_CARD),
                "the rendering names the token and no card number");
    }

    /**
     * Asserts no full card number survives anywhere in a serialized response.
     *
     * <p>The row that built each item was written with a masked value, so this is a check that
     * nothing on the path back out reconstructs a full one.
     */
    @Test
    void noCardNumberOfEitherFormCanServeAsTheIdentity() {
        assertThrows(IllegalArgumentException.class,
                () -> NotificationHistoryResponse.fromCardRows(MASKED_CARD,
                        NOTIFICATIONS.totalOf(List.of()), List.of()),
                "a masked card number identifies no single card, so it keys no history");
        assertThrows(IllegalArgumentException.class,
                () -> NotificationHistoryResponse.fromCardRows(FULL_CARD,
                        NOTIFICATIONS.totalOf(List.of()), List.of()),
                "a full card number belongs in no route and no key");
    }

    @Test
    void theTokenIdentifiesOneCardWhereTheMaskedFormIdentifiesNone() {
        assertNotEquals(CARD_TOKEN, OTHER_CARD_TOKEN,
                "two cards sharing no digits tokenize apart");
        assertEquals(PanMasker.CARD_TOKEN_LENGTH, CARD_TOKEN.length(), "token width");
        assertTrue(CARD_TOKEN.matches(PanMasker.CARD_TOKEN_PATTERN), "token shape");
        assertFalse(CARD_TOKEN.contains(FULL_CARD.substring(FULL_CARD.length() - 4)),
                "the token carries no digit group of the card it names");
    }

    @Test
    void aTotalAtAnotherScaleIsRefused() {
        IllegalArgumentException refused = assertThrows(IllegalArgumentException.class,
                () -> NotificationHistoryResponse.fromCardRows(CARD_TOKEN, new BigDecimal("1.5"),
                        List.of()),
                "a total at one fractional digit");
        assertTrue(refused.getMessage().contains("scale"), "the message names the scale");
    }

    /**
     * Asserts a card number of either form is refused where the account identifier belongs.
     *
     * <p>A full one would put a Primary Account Number on the envelope. A masked one would address a
     * response to a value shared by several cards.
     */
    @Test
    void aCardNumberThatIsNotMaskedIsRefusedByTheConstructor() {
        IllegalArgumentException fullCard = assertThrows(IllegalArgumentException.class,
                () -> new NotificationHistoryResponse(CARD_TOKEN, FULL_CARD, 0, "0.00", List.of()),
                "a full card number in the display component");
        assertFalse(fullCard.getMessage().contains(FULL_CARD),
                "no message names a card number");

        assertThrows(IllegalArgumentException.class,
                () -> new NotificationHistoryResponse(MASKED_CARD, MASKED_CARD, 0, "0.00",
                        List.of()),
                "a masked card number where the token belongs");
    }

    @Test
    void aTokenOutsideItsShapeIsRefusedByTheConstructor() {
        assertThrows(IllegalArgumentException.class,
                () -> new NotificationHistoryResponse(MASKED_CARD, null, 0, "0.00", List.of()),
                "a masked card number in the token position");
        assertThrows(IllegalArgumentException.class,
                () -> new NotificationHistoryResponse(CARD_TOKEN.toUpperCase(Locale.ROOT), null, 0,
                        "0.00", List.of()),
                "a token in upper case");
    }

    @Test
    void aCardNumberDisagreeingWithTheItemsIsRefusedByTheConstructor() {
        List<NotificationTransactionItem> one =
                List.of(NotificationTransactionItem.from(row("0000000000000001", "1.00")));
        assertThrows(IllegalArgumentException.class,
                () -> new NotificationHistoryResponse(CARD_TOKEN, MASKED_CARD, 0, "0.00",
                        List.of()),
                "a card number with no item to have read it from");
        assertThrows(IllegalArgumentException.class,
                () -> new NotificationHistoryResponse(CARD_TOKEN, null, 1, "1.00", one),
                "items with no card number read from them");
    }

    @Test
    void aTokenOutsideTheDeclaredShapeIsRefusedByTheConstructor() {
        IllegalArgumentException refused = assertThrows(IllegalArgumentException.class,
                () -> new NotificationHistoryResponse(MASKED_CARD, MASKED_CARD, 0, "0.00",
                        List.of()),
                "a masked card number in the identity component");
        assertFalse(refused.getMessage().contains(MASKED_CARD), "no message names a card number");
        assertThrows(IllegalArgumentException.class,
                () -> new NotificationHistoryResponse(CARD_TOKEN.toUpperCase(Locale.ROOT), null, 0,
                        "0.00", List.of()),
                "an upper-case rendering is not the form PanMasker.cardToken produces");
    }

    @Test
    void anArrayAboveTheRowCeilingIsRefusedByTheConstructor() {
        List<NotificationTransactionItem> overCeiling =
                new ArrayList<>(NotificationRenderer.MAXIMUM_STATEMENT_ROWS + 1);
        for (int index = 0; index <= NotificationRenderer.MAXIMUM_STATEMENT_ROWS; index++) {
            overCeiling.add(NotificationTransactionItem
                    .from(row(String.format("%016d", index + 1), "1.00")));
        }

        IllegalArgumentException refused = assertThrows(IllegalArgumentException.class,
                () -> new NotificationHistoryResponse(CARD_TOKEN, MASKED_CARD,
                        overCeiling.size(), "1.00", overCeiling),
                "one item above the ceiling the renderer declares");
        assertTrue(refused.getMessage()
                        .contains(String.valueOf(NotificationRenderer.MAXIMUM_STATEMENT_ROWS)),
                "the message names the ceiling");

        List<NotificationTransactionItem> atCeiling =
                overCeiling.subList(0, NotificationRenderer.MAXIMUM_STATEMENT_ROWS);
        assertEquals(NotificationRenderer.MAXIMUM_STATEMENT_ROWS,
                new NotificationHistoryResponse(CARD_TOKEN, MASKED_CARD, atCeiling.size(),
                        "1.00", atCeiling).transactionCount(),
                "the ceiling itself is accepted");
    }

    @Test
    void aTotalOutsideTheDeclaredShapeIsRefusedByTheConstructor() {
        assertThrows(IllegalArgumentException.class,
                () -> new NotificationHistoryResponse(CARD_TOKEN, MASKED_CARD, 0, "0", List.of()),
                "a total without its two fractional digits");
        assertThrows(IllegalArgumentException.class,
                () -> new NotificationHistoryResponse(CARD_TOKEN, MASKED_CARD, 0, "1234567890.00", List.of()),
                "a total at ten integer digits");
    }

    @Test
    void aCountThatDisagreesWithTheItemsIsRefused() {
        List<NotificationTransactionItem> one =
                List.of(NotificationTransactionItem.from(row("0000000000000001", "1.00")));
        assertThrows(IllegalArgumentException.class,
                () -> new NotificationHistoryResponse(CARD_TOKEN, MASKED_CARD, 2, "1.00", one),
                "a count above the items supplied");
        assertThrows(IllegalArgumentException.class,
                () -> new NotificationHistoryResponse(CARD_TOKEN, MASKED_CARD, -1, "0.00", List.of()),
                "a negative count");
    }

    @Test
    void theSerializedBodyCarriesExactlyFivePropertiesInTheSchemaOrder() {
        NotificationHistoryResponse response = response(
                List.of(row("0000000000000001", "194.00"), row("0000000000000002", "310.77")));
        ObjectMapper mapper = JsonMapper.builder().build();

        JsonNode body = mapper.readTree(mapper.writeValueAsString(response));

        assertEquals(List.of("cardToken", "cardNumber", "transactionCount", "totalAmount",
                        "transactions"),
                propertiesOf(body), "the five properties the schema declares, in that order");
        assertEquals(5, body.size(), "additionalProperties is false, so there is no sixth");
        assertTrue(body.get("cardToken").isString(), "the identity travels as text");
        assertTrue(body.get("totalAmount").isString(), "money travels as text, never as a number");
        assertEquals(response, mapper.readValue(mapper.writeValueAsString(response),
                NotificationHistoryResponse.class), "the body round trips field for field");
    }

    @Test
    void anEmptyHistoryWithholdsTheDisplayCardNumberRatherThanWritingNull() {
        ObjectMapper mapper = JsonMapper.builder().build();

        JsonNode body = mapper.readTree(mapper.writeValueAsString(response(List.of())));

        assertEquals(List.of("cardToken", "transactionCount", "totalAmount", "transactions"),
                propertiesOf(body),
                "cardNumber is absent, not present and null: the schema leaves it out of required");
        assertEquals(4, body.size(), "four properties when the read model holds no row");
        assertEquals(CARD_TOKEN, body.get("cardToken").stringValue(), "the identity is always there");
    }

    @Test
    void noSerializedBodyCarriesTheFullCardNumberInAnyProperty() {
        ObjectMapper mapper = JsonMapper.builder().build();

        String body = mapper.writeValueAsString(
                response(List.of(row("0000000000000001", "194.00"))));

        assertFalse(body.contains(FULL_CARD), "the full card number reaches no property");
        assertFalse(body.contains(FULL_CARD.substring(0, 12)),
                "no leading digit group of the card number reaches a property");
        assertTrue(body.contains(CARD_TOKEN), "the token is the identity the body carries");
    }

    @Test
    void theItemDecodesTheTrailingSpacesAFixedWidthColumnReturns() {
        StatementTransactionEntity padded = new StatementTransactionEntity(
                new StatementTransactionId(CARD_TOKEN, "0000000000000001"), MASKED_CARD, "01",
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
    void aBlankStoredColumnStillDecodesRatherThanFailing() {
        StatementTransactionEntity blank = new StatementTransactionEntity(
                new StatementTransactionId(CARD_TOKEN, "0000000000000001"), MASKED_CARD, "  ",
                "0", " ".repeat(10), " ".repeat(100), new BigDecimal("194.00"),
                "0", " ".repeat(50), " ".repeat(50), " ".repeat(10), " ".repeat(26),
                "2022-07-19-23.16.01.470000");
        NotificationTransactionItem item = NotificationTransactionItem.from(blank);

        assertEquals("", item.typeCode(), "type code");
        assertEquals("", item.description(), "description");
        assertEquals("0000", item.categoryCode(), "category code reads as zeros");
        assertEquals("000000000", item.merchantId(), "merchant identifier reads as zeros");
        assertEquals("194.00", item.amount(), "the amount decodes beside the blanks");
    }

    @Test
    void aNullArgumentToTheFactoryIsRefusedByName() {
        NullPointerException noToken = assertThrows(NullPointerException.class,
                () -> NotificationHistoryResponse.fromCardRows(null,
                        NOTIFICATIONS.totalOf(List.of()), List.of()),
                "a null card token");
        assertTrue(noToken.getMessage().contains("cardToken"), "named");

        NullPointerException noTotal = assertThrows(NullPointerException.class,
                () -> NotificationHistoryResponse.fromCardRows(CARD_TOKEN, null, List.of()),
                "a null total");
        assertTrue(noTotal.getMessage().contains("total"), "named");

        assertThrows(NullPointerException.class,
                () -> NotificationHistoryResponse.fromCardRows(CARD_TOKEN,
                        NOTIFICATIONS.totalOf(List.of()), null),
                "null rows");
    }

    /**
     * Builds one response the way the endpoint does: the domain totals the rows, and the record
     * renders them.
     *
     * @param rows the account's rows in ascending transaction-identifier order
     * @return the response
     */
    private static NotificationHistoryResponse response(List<StatementTransactionEntity> rows) {
        return NotificationHistoryResponse.fromCardRows(CARD_TOKEN, NOTIFICATIONS.totalOf(rows),
                rows);
    }

    /**
     * Reads the property names of one object body in wire order.
     *
     * @param body the serialized response
     * @return the property names, in the order the body carries them
     */
    private static List<String> propertiesOf(JsonNode body) {
        List<String> properties = new ArrayList<>();
        for (Iterator<String> names = body.propertyNames().iterator(); names.hasNext();) {
            properties.add(names.next());
        }
        return properties;
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
                new StatementTransactionId(CARD_TOKEN, transactionId), MASKED_CARD, "01", "1",
                "POS TERM", "Purchase", new BigDecimal(amount), "800000000",
                "Abshire-Lowe", "North Enoshaven", "72112", "2022-06-10 19:27:53.000000",
                "2022-07-19-23.16.01.470000");
    }
}
