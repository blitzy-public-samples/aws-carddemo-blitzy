package com.carddemo.notification.api;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.carddemo.cobol.CobolDecimal;
import com.carddemo.cobol.PanMasker;
import com.carddemo.notification.entity.StatementTransactionEntity;
import com.carddemo.notification.entity.StatementTransactionEntity.StatementTransactionId;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.RecordComponent;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * Envelope and serialized-body tests for {@link NotificationHistoryResponse}.
 *
 * <p>Four components carry one card's alert history. The record declares them in the order
 * {@code cardNumber}, {@code transactionCount}, {@code totalAmount}, {@code transactions}. The card
 * token that keys the read model is a storage key and reaches no response.
 *
 * <p>The statement program splits the envelope from the item. The move into
 * {@code TRNX-CARD-NUM} at {@code app/cbl/CBSTM03A.CBL:L421} runs once per card group. The move
 * into {@code TRNX-ID} at {@code app/cbl/CBSTM03A.CBL:L424-L425} and the move into
 * {@code TRNX-REST} at {@code app/cbl/CBSTM03A.CBL:L426-L427} run once per transaction. The card
 * number sits on the envelope at the width {@code TRNX-CARD-NUM PIC X(16)} at
 * {@code app/cpy/COSTM01.CPY:L22} declares, inside the group {@code 05 TRNX-KEY.} at
 * {@code app/cpy/COSTM01.CPY:L21}. The trailing {@code FILLER PIC X(20)} at
 * {@code app/cpy/COSTM01.CPY:L36} is dropped and carries no component.
 *
 * <p>{@code WS-TOTAL-AMT PIC S9(9)V99 VALUE 0} at {@code app/cbl/CBSTM03A.CBL:L65} sits under
 * {@code 01 COMP3-VARIABLES COMP-3.} at {@code app/cbl/CBSTM03A.CBL:L64}. The program zeroes it
 * for each card at {@code app/cbl/CBSTM03A.CBL:L325}, then enters the transaction loop at
 * {@code app/cbl/CBSTM03A.CBL:L326}. It adds one row at {@code app/cbl/CBSTM03A.CBL:L429}, after
 * the render at {@code app/cbl/CBSTM03A.CBL:L428}, and moves the sum into
 * {@code WS-TRN-AMT PIC S9(9)V99} at {@code app/cbl/CBSTM03A.CBL:L68} through
 * {@code app/cbl/CBSTM03A.CBL:L433}. Nine integer digits is the ceiling on a summed total.
 *
 * <p>{@code NotificationService} in the sibling {@code domain} package accumulates the per-card
 * total. The tests below assert how the factory renders a total handed to it.
 *
 * <p>Masking is an addition. The card detail map shows all sixteen characters: {@code LENGTH=16}
 * at {@code app/bms/COCRDSL.bms:L99}, under the label at {@code app/bms/COCRDSL.bms:L95}. The card
 * record keeps its verification value in the clear as {@code CARD-CVV-CD PIC 9(03)} at
 * {@code app/cpy/CVACT02Y.cpy:L7}, beside {@code CARD-NUM PIC X(16)} at
 * {@code app/cpy/CVACT02Y.cpy:L5}. A full Primary Account Number (PAN) reaches the mask helper and
 * goes no further, and the JavaScript Object Notation (JSON) body carries the masked form alone.
 *
 * <p>{@code card-platform/docs/decision-log.md} carries two decisions. The first is the masked card
 * number as an additive deviation, evidenced by {@code app/bms/COCRDSL.bms:L99} and
 * {@code app/cpy/CVACT02Y.cpy:L7}. The second is truncation toward zero over half-up at every
 * arithmetic site, from Agent Action Plan section 0.1.1 item I1.
 *
 * <p>{@code card-platform/docs/business-rule-flags.md} carries one source finding. The move at
 * {@code app/cbl/CBSTM03A.CBL:L433} drops a high-order digit and raises no diagnostic.
 *
 * <p>Every input below is built in this class. These tests read no file, start no application
 * context, open no database connection and reach no broker.
 */
final class NotificationHistoryResponseTest {

    /**
     * The four component names, in the order {@link NotificationHistoryResponse} declares them.
     */
    private static final List<String> DECLARED_COMPONENTS = List.of(
            "cardNumber", "transactionCount", "totalAmount", "transactions", "nextPageExists",
            "nextCursor");

    /**
     * The properties a serialized last page carries.
     *
     * <p>{@code nextCursor} is absent rather than null. The schema declares it a string and not a
     * nullable one, and a cursor is present exactly when there is a further page to ask for, so the
     * record serializes with nulls omitted. Every response this class builds is a last page.
     */
    private static final List<String> LAST_PAGE_PROPERTIES =
            DECLARED_COMPONENTS.subList(0, DECLARED_COMPONENTS.size() - 1);

    /**
     * Paging names this response does not carry, lower-cased.
     *
     * <p>The route pages its entries, so {@code nextPageExists} and {@code nextCursor} do name paging
     * and are the two the body publishes. What stays out is every name implying a different paging
     * model: an offset, a page number, a total page count or a link envelope. A keyset walk has none of
     * those, and publishing one would invite a request this route cannot serve.</p>
     */
    private static final List<String> PAGING_NAMES = List.of("pagenumber", "pageindex", "offset",
            "totalpages", "pagecount", "links", "sort");

    /**
     * Fields other services own, lower-cased. The statement program renders six of them, and the
     * account service owns every one.
     *
     * <p>{@code ST-NAME PIC X(75)} sits at {@code app/cbl/CBSTM03A.CBL:L91}, under the group at
     * {@code app/cbl/CBSTM03A.CBL:L90}. The three address groups sit at
     * {@code app/cbl/CBSTM03A.CBL:L93}, {@code app/cbl/CBSTM03A.CBL:L96} and
     * {@code app/cbl/CBSTM03A.CBL:L99}. {@code ST-CURR-BAL PIC 9(9).99-} sits at
     * {@code app/cbl/CBSTM03A.CBL:L113}, under the label at {@code app/cbl/CBSTM03A.CBL:L112}.
     * {@code ST-FICO-SCORE PIC X(20)} sits at {@code app/cbl/CBSTM03A.CBL:L118}.</p>
     */
    private static final List<String> FIELDS_OTHER_SERVICES_OWN = List.of("accountid",
            "currentbalance", "creditlimit", "cashcreditlimit", "activestatus", "opendate",
            "expir", "reissuedate", "groupid", "ficoscore", "customername", "addressline1",
            "addressline2", "addressline3", "declinereason", "cardstatus", "accountstatus",
            "deliverychannel", "emailaddress", "phonenumber");

    /**
     * The envelope property names every event schema declares, lower-cased. A history body
     * restates none of them.
     */
    private static final List<String> EVENT_ENVELOPE_NAMES = List.of("eventid", "eventtype",
            "schemaversion", "occurredat", "aggregateid");

    /**
     * The abbreviated verification field of the source card record, lower-cased. Source:
     * {@code CARD-CVV-CD PIC 9(03)} at {@code app/cpy/CVACT02Y.cpy:L7}.
     */
    private static final String VERIFICATION_FIELD_ABBREVIATION =
            "CARD-CVV-CD".split("-")[1].toLowerCase(Locale.ROOT);

    /** Two further spellings of a verification field, lower-cased. */
    private static final List<String> VERIFICATION_FIELD_SPELLINGS =
            List.of("cardverification", "securitycode");

    /**
     * The two fractional digits {@code TRNX-AMT PIC S9(09)V99} at
     * {@code app/cpy/COSTM01.CPY:L29} declares.
     */
    private static final int AMOUNT_SCALE = 2;

    /** Shape of a masked card number: twelve mask characters then four digits. */
    private static final Pattern MASKED_CARD_NUMBER_SHAPE = Pattern.compile("^\\*{12}[0-9]{4}$");

    /**
     * Shape of a rendered total: an optional leading minus, at most nine integer digits, a point
     * and two fractional digits. The nine integer digits come from
     * {@code WS-TOTAL-AMT PIC S9(9)V99} at {@code app/cbl/CBSTM03A.CBL:L65}.
     */
    private static final Pattern TOTAL_SHAPE = Pattern.compile("^-?\\d{1,9}\\.\\d{2}$");

    /** A run of sixteen digits, the shape of a full card number. */
    private static final Pattern SIXTEEN_DIGIT_RUN = Pattern.compile("[0-9]{16}");

    /** A property name made of digits alone, the shape a reject reason code would take. */
    private static final Pattern DIGITS_ONLY = Pattern.compile("^[0-9]+$");

    /** The masked form of {@link #fullCardNumber()}: twelve mask characters then four digits. */
    private static final String MASKED_CARD_NUMBER = PanMasker.maskCardNumber(fullCardNumber());

    /** The masked form of {@link #secondFullCardNumber()}, ending in different digits. */
    private static final String SECOND_MASKED_CARD_NUMBER =
            PanMasker.maskCardNumber(secondFullCardNumber());

    /**
     * The card token every row and response below is keyed on: sixty-four lower-case hexadecimal
     * characters, the shape {@link PanMasker#CARD_TOKEN_PATTERN} declares. Eight characters
     * repeated eight times. No digit run in the value reaches two characters.
     */
    private static final String CARD_TOKEN = "a1b2c3d4".repeat(8);

    /**
     * A transaction identifier at the sixteen characters {@code app/cpy/COSTM01.CPY:L23} declares.
     */
    private static final String FIRST_TRANSACTION_ID = "TRN0000000000001";

    /** A second transaction identifier at the same width. */
    private static final String SECOND_TRANSACTION_ID = "TRN0000000000002";

    /** A third transaction identifier at the same width. */
    private static final String THIRD_TRANSACTION_ID = "TRN0000000000003";

    /** A type code at the two characters {@code app/cpy/COSTM01.CPY:L25} declares. */
    private static final String TYPE_CODE = "01";

    /** A category code below the four digits {@code app/cpy/COSTM01.CPY:L26} declares. */
    private static final String CATEGORY_CODE = "5";

    /** A source value below the ten characters {@code app/cpy/COSTM01.CPY:L27} declares. */
    private static final String SOURCE = "POS TERM";

    /** A description below the hundred characters {@code app/cpy/COSTM01.CPY:L28} declares. */
    private static final String DESCRIPTION = "PURCHASE HARBOUR CO.";

    /** A merchant identifier below the nine digits {@code app/cpy/COSTM01.CPY:L30} declares. */
    private static final String MERCHANT_ID = "42";

    /** A merchant name below the fifty characters {@code app/cpy/COSTM01.CPY:L31} declares. */
    private static final String MERCHANT_NAME = "HARBOUR SUPPLY CO";

    /** A merchant city below the fifty characters {@code app/cpy/COSTM01.CPY:L32} declares. */
    private static final String MERCHANT_CITY = "AUSTIN";

    /** A merchant mail code below the ten characters {@code app/cpy/COSTM01.CPY:L33} declares. */
    private static final String MERCHANT_ZIP = "72112";

    /**
     * An origin timestamp at the twenty-six characters {@code app/cpy/COSTM01.CPY:L34} declares,
     * with a space between the day and the hour.
     */
    private static final String ORIGIN_TIMESTAMP = "2022-06-10 19:27:53.000000";

    /**
     * A processing timestamp at the twenty-six characters {@code app/cpy/COSTM01.CPY:L35}
     * declares, with a dash between the day and the hour.
     */
    private static final String PROCESSING_TIMESTAMP = "2022-07-19-23.16.01.470000";

    /** A row amount at the two fractional digits {@code app/cpy/COSTM01.CPY:L29} declares. */
    private static final BigDecimal ROW_AMOUNT = new BigDecimal("50.47");

    /** A total already at scale two. */
    private static final BigDecimal TOTAL_AT_SCALE_TWO = new BigDecimal("1234.56");

    /** A zero total at scale two, the value a card with no row carries. */
    private static final BigDecimal ZERO_TOTAL = new BigDecimal("0.00");

    /** A total at the nine integer digits {@code app/cbl/CBSTM03A.CBL:L65} declares. */
    private static final BigDecimal NINE_INTEGER_DIGIT_TOTAL = new BigDecimal("999999999.99");

    /** A refund total, which carries a leading minus. */
    private static final BigDecimal REFUND_TOTAL = new BigDecimal("-125.00");

    /** A total one fractional digit past the scale the factory accepts. */
    private static final BigDecimal TOTAL_PAST_SCALE_TWO = new BigDecimal("1.005");

    /** A negative total one fractional digit past the scale the factory accepts. */
    private static final BigDecimal NEGATIVE_TOTAL_PAST_SCALE_TWO = new BigDecimal("-1.005");

    /** Serializes a response the way the module serializes one. */
    private static final ObjectMapper MAPPER = JsonMapper.builder().build();

    /** Asserts {@link NotificationHistoryResponse} is a record. */
    @Test
    void theResponseIsARecord() {
        assertTrue(NotificationHistoryResponse.class.isRecord(),
                "NotificationHistoryResponse is a record");
    }

    /** Asserts the response declares six components and no seventh. */
    @Test
    void theResponseDeclaresSixComponents() {
        assertEquals(DECLARED_COMPONENTS.size(),
                NotificationHistoryResponse.class.getRecordComponents().length,
                "component count");
    }

    /**
     * Asserts the component names and their order. The card number is envelope-level, from
     * {@code TRNX-CARD-NUM PIC X(16)} at {@code app/cpy/COSTM01.CPY:L22}, and the move at
     * {@code app/cbl/CBSTM03A.CBL:L421} runs once per card group where
     * {@code app/cbl/CBSTM03A.CBL:L424-L427} runs once per transaction.
     */
    @Test
    void theComponentNamesFollowTheOrderTheRecordDeclares() {
        assertEquals(DECLARED_COMPONENTS, componentNames(),
                "the six component names, in declaration order");
    }

    /** Asserts the declared type of each component. */
    @Test
    void eachComponentCarriesTheTypeTheRecordDeclares() {
        assertEquals(String.class, componentNamed("cardNumber").getType(), "cardNumber");
        assertEquals(int.class, componentNamed("transactionCount").getType(), "transactionCount");
        assertEquals(String.class, componentNamed("totalAmount").getType(), "totalAmount");
        assertEquals(List.class, componentNamed("transactions").getType(), "transactions");
    }

    /**
     * Asserts the total travels as text. The two fractional digits come from
     * {@code TRNX-AMT PIC S9(09)V99} at {@code app/cpy/COSTM01.CPY:L29}.
     */
    @Test
    void theTotalIsDeclaredAsTextAndNotAsANumericType() {
        assertEquals(String.class, componentNamed("totalAmount").getType(), "declared type");
        assertNotEquals(BigDecimal.class, componentNamed("totalAmount").getType(),
                "the total is text on the wire");
    }

    /** Asserts the element type of the transaction list. */
    @Test
    void theTransactionsComponentIsATypedListOfItems() {
        ParameterizedType listType =
                (ParameterizedType) componentNamed("transactions").getGenericType();

        assertEquals(1, listType.getActualTypeArguments().length, "type argument count");
        assertEquals(NotificationTransactionItem.class, listType.getActualTypeArguments()[0],
                "element type");
    }

    /** Asserts no component names a paging or cursor parameter. */
    @Test
    void noComponentCarriesAPagingParameter() {
        for (String pagingName : PAGING_NAMES) {
            assertFalse(anyComponentNameContains(pagingName),
                    "no component names " + pagingName);
        }
    }

    /**
     * Asserts the display card number carries twelve mask characters then four digits. The width
     * is the sixteen characters {@code TRNX-CARD-NUM PIC X(16)} at
     * {@code app/cpy/COSTM01.CPY:L22} declares.
     */
    @Test
    void theDisplayCardNumberCarriesTwelveMaskCharactersThenFourDigits() {
        NotificationHistoryResponse response = responseWithOneRow();

        assertTrue(MASKED_CARD_NUMBER_SHAPE.matcher(response.cardNumber()).matches(),
                "the masked shape");
        assertEquals("************7065", response.cardNumber(), "the masked value");
    }

    /** Asserts the display card number holds sixteen characters. */
    @Test
    void theDisplayCardNumberHoldsSixteenCharacters() {
        assertEquals(PanMasker.CARD_NUMBER_LENGTH, responseWithOneRow().cardNumber().length(),
                "display width");
    }

    /** Asserts the display card number keeps the last four digits of the card number. */
    @Test
    void theDisplayCardNumberKeepsTheLastFourDigitsOfTheCardNumber() {
        String cardNumber = fullCardNumber();
        String lastFour = cardNumber.substring(cardNumber.length() - PanMasker.VISIBLE_DIGIT_COUNT);

        String displayed = responseWithOneRow().cardNumber();

        assertEquals(lastFour, displayed.substring(displayed.length() - lastFour.length()),
                "the four digits kept");
    }

    /** Asserts the display card number differs from the card number it was masked from. */
    @Test
    void theDisplayCardNumberDiffersFromTheCardNumberItWasMaskedFrom() {
        NotificationHistoryResponse response = responseWithOneRow();

        assertNotEquals(fullCardNumber(), response.cardNumber(), "masked, not passed through");
        assertFalse(response.cardNumber().contains(fullCardNumber().substring(0, 12)),
                "no leading digit group survives");
    }

    /**
     * Asserts no accessor on the response or on any item returns the card number. Four response
     * accessors and twelve item accessors are read.
     */
    @Test
    void noAccessorOnTheResponseOrItsItemsReturnsTheCardNumber() throws Exception {
        String cardNumber = fullCardNumber();

        List<String> values = everyAccessorValue(responseWithThreeRows());

        assertEquals(DECLARED_COMPONENTS.size() + 3 * 12, values.size(), "accessors read");
        for (String value : values) {
            assertFalse(value.contains(cardNumber), "no accessor returns the card number");
            assertFalse(SIXTEEN_DIGIT_RUN.matcher(value).find(),
                    "no accessor returns a sixteen-digit run");
        }
    }

    /**
     * Asserts the serialized body carries no sixteen-digit run and no card number. Masking is an
     * addition: {@code LENGTH=16} at {@code app/bms/COCRDSL.bms:L99} shows the source field
     * unmasked, under the label at {@code app/bms/COCRDSL.bms:L95}.
     */
    @Test
    void noSerializedBodyCarriesASixteenDigitRun() {
        String body = MAPPER.writeValueAsString(responseWithThreeRows());

        assertFalse(SIXTEEN_DIGIT_RUN.matcher(body).find(), "no sixteen-digit run in the body");
        assertFalse(body.contains(fullCardNumber()), "the card number reaches no property");
        assertFalse(body.contains(fullCardNumber().substring(0, 12)),
                "no leading digit group of the card number reaches a property");
        assertTrue(body.contains(MASKED_CARD_NUMBER), "the masked form is what the body carries");
    }

    /**
     * Asserts no property of the body names a verification field. The source card record keeps
     * that value in the clear as {@code CARD-CVV-CD PIC 9(03)} at
     * {@code app/cpy/CVACT02Y.cpy:L7}.
     */
    @Test
    void noSerializedBodyCarriesAVerificationFieldProperty() {
        List<String> names = lowerCasePropertyNames(bodyOf(responseWithThreeRows()));

        for (String name : names) {
            assertFalse(name.contains(VERIFICATION_FIELD_ABBREVIATION),
                    "no property names the abbreviated verification field");
            for (String spelling : VERIFICATION_FIELD_SPELLINGS) {
                assertFalse(name.contains(spelling), "no property names " + spelling);
            }
        }
    }

    /** Asserts the body carries its properties, named and ordered as the record declares them. */
    @Test
    void theSerializedBodyCarriesEveryPropertyInDeclarationOrder() {
        JsonNode body = bodyOf(responseWithThreeRows());

        assertEquals(LAST_PAGE_PROPERTIES, propertiesOf(body), "property names, in that order");
        assertEquals(LAST_PAGE_PROPERTIES.size(), body.size(), "property count");
        assertTrue(body.get("cardNumber").isString(), "the display value travels as text");
        assertTrue(body.get("totalAmount").isString(), "the total travels as text");
        assertTrue(body.get("transactions").isArray(), "the transactions travel as an array");
        assertFalse(body.has("cardToken"), "the storage key reaches no body");
    }

    /**
     * Asserts a body for a card with no row carries the same four properties, display value
     * included. The masked form is derived from the path value rather than read from a row, so a
     * card with no row names its card too, as the statement header does at
     * {@code app/cbl/CBSTM03A.CBL:L318-L325}.
     */
    @Test
    void aBodyForACardWithNoRowStillNamesItsCard() {
        JsonNode body = bodyOf(emptyResponse());

        assertEquals(LAST_PAGE_PROPERTIES, propertiesOf(body), "property names, in that order");
        assertEquals(LAST_PAGE_PROPERTIES.size(), body.size(), "property count");
        assertEquals(MASKED_CARD_NUMBER, body.get("cardNumber").stringValue(),
                "the display value is present");
    }

    /** Asserts three rows yield a count of three. */
    @Test
    void threeRowsYieldACountOfThree() {
        assertEquals(3, responseWithThreeRows().transactionCount(), "count");
    }

    /** Asserts one row yields a count of one. */
    @Test
    void oneRowYieldsACountOfOne() {
        assertEquals(1, responseWithOneRow().transactionCount(), "count");
    }

    /** Asserts the count equals the item count for every row count the factory is handed. */
    @Test
    void theCountEqualsTheItemCountForEveryRowCountSupplied() {
        List<StatementTransactionEntity> rows = threeRows();

        for (int size = 0; size <= rows.size(); size++) {
            List<StatementTransactionEntity> page = rows.subList(0, size);
            NotificationHistoryResponse response =
                    NotificationHistoryResponse.fromCardRows(MASKED_CARD_NUMBER, page.size(), ZERO_TOTAL,
                            page, false);

            assertEquals(response.transactions().size(), response.transactionCount(),
                    "count equals the item count at size " + size);
            assertEquals(size, response.transactionCount(), "count at size " + size);
        }
    }

    /**
     * Asserts the factory keeps the order of the rows it is handed. The ascending order the
     * repository returns is asserted elsewhere.
     */
    @Test
    void theItemsFollowTheOrderTheRowsArrivedIn() {
        NotificationHistoryResponse response = responseWithThreeRows();

        assertEquals(
                List.of(FIRST_TRANSACTION_ID, SECOND_TRANSACTION_ID, THIRD_TRANSACTION_ID),
                response.transactions().stream()
                        .map(NotificationTransactionItem::transactionId)
                        .toList(),
                "item order");
    }

    /** Asserts a total already at scale two renders character for character. */
    @Test
    void aTotalAtScaleTwoRendersCharacterForCharacter() {
        assertEquals("1234.56", responseWithTotal(TOTAL_AT_SCALE_TWO).totalAmount(), "total");
    }

    /** Asserts the rendered total carries exactly two characters after the decimal point. */
    @Test
    void theRenderedTotalCarriesExactlyTwoFractionalDigits() {
        List<BigDecimal> totals = List.of(ZERO_TOTAL, TOTAL_AT_SCALE_TWO, REFUND_TOTAL,
                NINE_INTEGER_DIGIT_TOTAL);

        for (BigDecimal total : totals) {
            String rendered = responseWithTotal(total).totalAmount();

            assertTrue(TOTAL_SHAPE.matcher(rendered).matches(), "shape of " + rendered);
            assertEquals(AMOUNT_SCALE, rendered.length() - rendered.indexOf('.') - 1,
                    "fractional digits of " + rendered);
        }
    }

    /** Asserts the rendered total carries no exponent marker. */
    @Test
    void theRenderedTotalCarriesNoExponentMarker() {
        String rendered = responseWithTotal(NINE_INTEGER_DIGIT_TOTAL).totalAmount();

        assertFalse(rendered.contains("E"), "no upper-case exponent marker");
        assertFalse(rendered.contains("e"), "no lower-case exponent marker");
    }

    /**
     * Asserts a total at nine integer digits renders in full. Nine integer digits is the ceiling
     * {@code WS-TOTAL-AMT PIC S9(9)V99} at {@code app/cbl/CBSTM03A.CBL:L65} sets, and the move at
     * {@code app/cbl/CBSTM03A.CBL:L433} into {@code WS-TRN-AMT PIC S9(9)V99} at
     * {@code app/cbl/CBSTM03A.CBL:L68} drops a tenth digit with no diagnostic.
     */
    @Test
    void aTotalAtNineIntegerDigitsRendersInFull() {
        String rendered = responseWithTotal(NINE_INTEGER_DIGIT_TOTAL).totalAmount();

        assertEquals("999999999.99", rendered, "total");
        assertEquals(9, rendered.indexOf('.'), "integer digits");
    }

    /** Asserts a refund total renders with a leading minus. */
    @Test
    void aRefundTotalRendersWithALeadingMinus() {
        String rendered = responseWithTotal(REFUND_TOTAL).totalAmount();

        assertEquals("-125.00", rendered, "total");
        assertTrue(rendered.startsWith("-"), "leading minus");
    }

    /**
     * Asserts the factory refuses a total past two fractional digits, for both signs. Negative
     * amounts are ordinary traffic: 50 of the 300 records in
     * {@code app/data/ASCII/dailytran.txt} carry a negative amount.
     */
    @Test
    void aTotalPastTwoFractionalDigitsIsRefusedForBothSigns() {
        IllegalArgumentException positive = assertThrows(IllegalArgumentException.class,
                () -> NotificationHistoryResponse.fromCardRows(MASKED_CARD_NUMBER, 0,
                        TOTAL_PAST_SCALE_TWO, List.of(), false),
                "a total at three fractional digits");
        assertTrue(positive.getMessage().contains("scale"), "the message names the scale");

        IllegalArgumentException negative = assertThrows(IllegalArgumentException.class,
                () -> NotificationHistoryResponse.fromCardRows(MASKED_CARD_NUMBER, 0,
                        NEGATIVE_TOTAL_PAST_SCALE_TWO, List.of(), false),
                "a negative total at three fractional digits");
        assertTrue(negative.getMessage().contains("scale"), "the message names the scale");
    }

    /**
     * Asserts truncation toward zero and half-up produce different values at the third fractional
     * digit, for both signs. {@link CobolDecimal} pins truncation toward zero and takes no rounding
     * mode. {@link BigDecimal} supplies the half-up values below.
     */
    @Test
    void truncationTowardZeroDivergesFromHalfUpAtTheThirdFractionalDigit() {
        BigDecimal truncatedPositive =
                CobolDecimal.truncateToScale(TOTAL_PAST_SCALE_TWO, AMOUNT_SCALE);
        BigDecimal truncatedNegative =
                CobolDecimal.truncateToScale(NEGATIVE_TOTAL_PAST_SCALE_TWO, AMOUNT_SCALE);
        BigDecimal halfUpPositive =
                TOTAL_PAST_SCALE_TWO.setScale(AMOUNT_SCALE, RoundingMode.HALF_UP);
        BigDecimal halfUpNegative =
                NEGATIVE_TOTAL_PAST_SCALE_TWO.setScale(AMOUNT_SCALE, RoundingMode.HALF_UP);

        assertEquals("1.00", responseWithTotal(truncatedPositive).totalAmount(),
                "a truncated positive total");
        assertEquals("-1.00", responseWithTotal(truncatedNegative).totalAmount(),
                "a truncated negative total");
        assertEquals(new BigDecimal("1.01"), halfUpPositive, "half-up on a positive total");
        assertEquals(new BigDecimal("-1.01"), halfUpNegative, "half-up on a negative total");
        assertNotEquals(halfUpPositive, truncatedPositive, "the two modes differ at 1.005");
        assertNotEquals(halfUpNegative, truncatedNegative, "the two modes differ at -1.005");
    }

    /**
     * Asserts truncation toward zero agrees with floor for a positive total and diverges from it
     * for a negative one. {@link CobolDecimal} pins truncation toward zero. {@link BigDecimal}
     * supplies the floor values below.
     */
    @Test
    void truncationTowardZeroAgreesWithFloorOnlyForAPositiveTotal() {
        BigDecimal truncatedPositive =
                CobolDecimal.truncateToScale(TOTAL_PAST_SCALE_TWO, AMOUNT_SCALE);
        BigDecimal truncatedNegative =
                CobolDecimal.truncateToScale(NEGATIVE_TOTAL_PAST_SCALE_TWO, AMOUNT_SCALE);
        BigDecimal floorPositive = TOTAL_PAST_SCALE_TWO.setScale(AMOUNT_SCALE, RoundingMode.FLOOR);
        BigDecimal floorNegative =
                NEGATIVE_TOTAL_PAST_SCALE_TWO.setScale(AMOUNT_SCALE, RoundingMode.FLOOR);

        assertEquals(new BigDecimal("1.00"), floorPositive, "floor on a positive total");
        assertEquals(floorPositive, truncatedPositive, "floor agrees on a positive total");
        assertEquals(new BigDecimal("-1.01"), floorNegative, "floor on a negative total");
        assertNotEquals(floorNegative, truncatedNegative, "floor diverges on a negative total");
    }

    /** Asserts a card with no row carries a count of zero. */
    @Test
    void aCardWithNoRowCarriesACountOfZero() {
        assertEquals(0, emptyResponse().transactionCount(), "count");
    }

    /**
     * Asserts a card with no row carries a zero total at two fractional digits. The statement
     * program zeroes the total for each card at {@code app/cbl/CBSTM03A.CBL:L325}.
     */
    @Test
    void aCardWithNoRowCarriesAZeroTotalAtTwoFractionalDigits() {
        String rendered = emptyResponse().totalAmount();

        assertEquals("0.00", rendered, "total");
        assertTrue(TOTAL_SHAPE.matcher(rendered).matches(), "shape");
    }

    /** Asserts a card with no row carries an empty transaction list and never null. */
    @Test
    void aCardWithNoRowCarriesAnEmptyListAndNeverNull() {
        List<NotificationTransactionItem> transactions = emptyResponse().transactions();

        assertNotNull(transactions, "the list is present");
        assertTrue(transactions.isEmpty(), "the list is empty");
        assertEquals(List.of(), transactions, "the list holds no item");
    }

    /** Asserts a card with no row still names its card, by the masked form of the path value. */
    @Test
    void aCardWithNoRowStillNamesItsCard() {
        NotificationHistoryResponse response = emptyResponse();

        assertEquals(MASKED_CARD_NUMBER, response.cardNumber(), "the masked path value");
        assertNotNull(response.cardNumber(), "the card is named whether or not a row exists");
    }

    /** Asserts a transaction list a caller receives cannot be edited. */
    @Test
    void theTransactionListACallerReceivesCannotBeEdited() {
        List<NotificationTransactionItem> transactions = responseWithThreeRows().transactions();

        assertThrows(UnsupportedOperationException.class, transactions::clear,
                "the list is unmodifiable");
    }

    /** Asserts no property of the body, at any depth, names a field another service owns. */
    @Test
    void noSerializedBodyCarriesAFieldAnotherServiceOwns() {
        List<String> names = lowerCasePropertyNames(bodyOf(responseWithThreeRows()));

        assertFalse(names.isEmpty(), "the body carries properties to scan");
        for (String owned : FIELDS_OTHER_SERVICES_OWN) {
            for (String name : names) {
                assertFalse(name.contains(owned), "no property names " + owned);
            }
        }
    }

    /** Asserts no property of the body restates an event envelope property. */
    @Test
    void noSerializedBodyRestatesAnEventEnvelopeProperty() {
        List<String> names = lowerCasePropertyNames(bodyOf(responseWithThreeRows()));

        for (String envelopeName : EVENT_ENVELOPE_NAMES) {
            assertFalse(names.contains(envelopeName), "no property named " + envelopeName);
        }
    }

    /** Asserts no property of the body is named for a reject reason code or a decline reason. */
    @Test
    void noSerializedBodyCarriesARejectReasonProperty() {
        List<String> names = lowerCasePropertyNames(bodyOf(responseWithThreeRows()));

        for (String name : names) {
            assertFalse(DIGITS_ONLY.matcher(name).matches(), "no property named in digits alone");
            assertFalse(name.contains("reason"), "no property names a reason");
            assertFalse(name.contains("reject"), "no property names a reject");
        }
    }

    /** Asserts the body of a card with no row carries no forbidden property either. */
    @Test
    void aBodyForACardWithNoRowCarriesNoForbiddenProperty() {
        List<String> names = lowerCasePropertyNames(bodyOf(emptyResponse()));

        assertEquals(LAST_PAGE_PROPERTIES.size(), names.size(), "property count");
        for (String owned : FIELDS_OTHER_SERVICES_OWN) {
            for (String name : names) {
                assertFalse(name.contains(owned), "no property names " + owned);
            }
        }
        for (String envelopeName : EVENT_ENVELOPE_NAMES) {
            assertFalse(names.contains(envelopeName), "no property named " + envelopeName);
        }
    }

    /** Asserts the constructor refuses a display card number that is not the masked form. */
    @Test
    void aDisplayCardNumberThatIsNotMaskedIsRefused() {
        String cardNumber = fullCardNumber();

        IllegalArgumentException refused = assertThrows(IllegalArgumentException.class,
                () -> new NotificationHistoryResponse(cardNumber, 0, "0.00", List.of(), false, null),
                "a card number in the display component");

        assertFalse(refused.getMessage().contains(cardNumber), "no message names a card number");
    }

    /** Asserts the constructor refuses a card token where the masked card number belongs. */
    @Test
    void aCardTokenWhereTheMaskedCardNumberBelongsIsRefused() {
        assertThrows(IllegalArgumentException.class,
                () -> new NotificationHistoryResponse(CARD_TOKEN, 0, "0.00", List.of(), false, null),
                "a card token in the display component");
        assertThrows(IllegalArgumentException.class,
                () -> new NotificationHistoryResponse(MASKED_CARD_NUMBER.replace('*', '0'), 0,
                        "0.00", List.of(), false, null),
                "a value carrying no mask character");
        assertEquals(PanMasker.CARD_TOKEN_LENGTH, CARD_TOKEN.length(), "token width");
        assertTrue(CARD_TOKEN.matches(PanMasker.CARD_TOKEN_PATTERN), "token shape");
    }

    /**
     * Asserts the constructor refuses a count smaller than the items supplied, and admits a larger one.
     *
     * <p>The count covers the card's whole history and the items cover one page of it, so a count above
     * the items is the ordinary case on any card whose history needs more than one page. A count below
     * them describes a page carrying rows the card does not hold, which cannot be true.</p>
     */
    @Test
    void aCountSmallerThanTheItemsIsRefused() {
        List<NotificationTransactionItem> one = responseWithOneRow().transactions();

        assertThrows(IllegalArgumentException.class,
                () -> new NotificationHistoryResponse(MASKED_CARD_NUMBER, 0, "0.00", one, false,
                        null),
                "a count below the items supplied");
        assertThrows(IllegalArgumentException.class,
                () -> new NotificationHistoryResponse(MASKED_CARD_NUMBER, -1, "0.00", List.of(),
                        false, null),
                "a negative count");
        assertDoesNotThrow(
                () -> new NotificationHistoryResponse(MASKED_CARD_NUMBER, 2, "0.00", one, false,
                        null),
                "a whole-history count above the page is what a further page looks like");
    }

    /**
     * Asserts the cursor is present exactly when a further page exists.
     *
     * <p>A cursor on a last page would ask a caller to fetch an empty page, and a missing one on a
     * further page would strand the walk halfway through a history.</p>
     */
    @Test
    void aCursorDisagreeingWithTheFurtherPageFlagIsRefused() {
        List<NotificationTransactionItem> one = responseWithOneRow().transactions();

        assertThrows(IllegalArgumentException.class,
                () -> new NotificationHistoryResponse(MASKED_CARD_NUMBER, 2, "0.00", one, true, null),
                "a further page with no cursor to ask for it");
        assertThrows(IllegalArgumentException.class,
                () -> new NotificationHistoryResponse(MASKED_CARD_NUMBER, 1, "0.00", one, false,
                        FIRST_TRANSACTION_ID),
                "a cursor on a last page");
        assertThrows(IllegalArgumentException.class,
                () -> new NotificationHistoryResponse(MASKED_CARD_NUMBER, 2, "0.00", one, true, " "),
                "a blank cursor");
        assertDoesNotThrow(
                () -> new NotificationHistoryResponse(MASKED_CARD_NUMBER, 2, "0.00", one, true,
                        FIRST_TRANSACTION_ID),
                "a further page and the cursor that asks for it");
    }

    /**
     * Asserts the factory refuses a further page over a page carrying no row.
     *
     * <p>The cursor is the identifier of the last row of the page, so there is nothing to build one
     * from.</p>
     */
    @Test
    void aFurtherPageOverNoRowIsRefused() {
        assertThrows(IllegalArgumentException.class,
                () -> NotificationHistoryResponse.fromCardRows(MASKED_CARD_NUMBER, 5, ZERO_TOTAL,
                        List.of(), true),
                "a further page after an empty page");
    }

    /** Asserts the constructor refuses a total outside the shape the record declares. */
    @Test
    void aTotalOutsideTheDeclaredShapeIsRefused() {
        assertThrows(IllegalArgumentException.class,
                () -> new NotificationHistoryResponse(MASKED_CARD_NUMBER, 0, "0", List.of(), false, null),
                "a total without its two fractional digits");
        assertThrows(IllegalArgumentException.class,
                () -> new NotificationHistoryResponse(MASKED_CARD_NUMBER, 0, "1234567890.00",
                        List.of(), false, null),
                "a total at ten integer digits");
    }

    /**
     * Asserts the display card number stands on its own, whatever the items carry. No item repeats
     * the card: {@code TRNX-CARD-NUM PIC X(16)} at {@code app/cpy/COSTM01.CPY:L22} sits inside the
     * group {@code 05 TRNX-KEY.} at {@code app/cpy/COSTM01.CPY:L21} and is envelope-level.
     */
    @Test
    void theDisplayCardNumberStandsOnItsOwn() {
        List<NotificationTransactionItem> one = responseWithOneRow().transactions();

        NotificationHistoryResponse response =
                new NotificationHistoryResponse(SECOND_MASKED_CARD_NUMBER, 1, "0.00", one, false,
                        null);

        assertEquals(SECOND_MASKED_CARD_NUMBER, response.cardNumber(), "the value supplied");
        assertNotEquals(MASKED_CARD_NUMBER, SECOND_MASKED_CARD_NUMBER,
                "two cards ending in different digits mask apart");
        assertThrows(NullPointerException.class,
                () -> new NotificationHistoryResponse(null, 1, "0.00", one, false, null),
                "items with no display value beside them");
    }

    /** Asserts the factory names each null argument it refuses. */
    @Test
    void aNullArgumentToTheFactoryIsRefusedByName() {
        NullPointerException noCardNumber = assertThrows(NullPointerException.class,
                () -> NotificationHistoryResponse.fromCardRows(null, 0, ZERO_TOTAL, List.of(), false),
                "a null masked card number");
        assertTrue(noCardNumber.getMessage().contains("maskedCardNumber"),
                "the message names the argument");

        NullPointerException noTotal = assertThrows(NullPointerException.class,
                () -> NotificationHistoryResponse.fromCardRows(MASKED_CARD_NUMBER, 0, null,
                        List.of(), false),
                "a null total");
        assertTrue(noTotal.getMessage().contains("total"), "the message names the argument");

        NullPointerException noRows = assertThrows(NullPointerException.class,
                () -> NotificationHistoryResponse.fromCardRows(MASKED_CARD_NUMBER, 0, ZERO_TOTAL,
                        null, false),
                "null rows");
        assertTrue(noRows.getMessage().contains("rows"), "the message names the argument");
    }

    /**
     * Builds the sixteen-digit card number the row-writing path masks. The value is assembled at
     * run time. No sixteen-digit literal appears in this file.
     *
     * @return sixteen digits ending in the four the masked form keeps
     */
    private static String fullCardNumber() {
        return "5".repeat(12) + "7065";
    }

    /**
     * Builds a second sixteen-digit card number, ending in different digits.
     *
     * @return sixteen digits that mask to a value other than {@link #MASKED_CARD_NUMBER}
     */
    private static String secondFullCardNumber() {
        return "5".repeat(12) + "7066";
    }

    /**
     * Builds one response over three rows, at a total already at scale two.
     *
     * @return the response
     */
    private static NotificationHistoryResponse responseWithThreeRows() {
        List<StatementTransactionEntity> rows = threeRows();
        return NotificationHistoryResponse.fromCardRows(MASKED_CARD_NUMBER, rows.size(),
                TOTAL_AT_SCALE_TWO, rows, false);
    }

    /**
     * Builds one response over a single row, at a total already at scale two.
     *
     * @return the response
     */
    private static NotificationHistoryResponse responseWithOneRow() {
        return NotificationHistoryResponse.fromCardRows(MASKED_CARD_NUMBER, 1,
                TOTAL_AT_SCALE_TWO, List.of(row(FIRST_TRANSACTION_ID)), false);
    }

    /**
     * Builds one response over a single row, at the total supplied.
     *
     * @param total the total, at scale two
     * @return the response
     */
    private static NotificationHistoryResponse responseWithTotal(BigDecimal total) {
        return NotificationHistoryResponse.fromCardRows(MASKED_CARD_NUMBER, 1, total,
                List.of(row(FIRST_TRANSACTION_ID)), false);
    }

    /**
     * Builds one response for a card the read model holds no row for.
     *
     * @return the response
     */
    private static NotificationHistoryResponse emptyResponse() {
        return NotificationHistoryResponse.fromCardRows(MASKED_CARD_NUMBER, 0, ZERO_TOTAL,
                List.of(), false);
    }

    /**
     * Builds three rows of one card, in ascending transaction-identifier order.
     *
     * @return the rows
     */
    private static List<StatementTransactionEntity> threeRows() {
        return List.of(row(FIRST_TRANSACTION_ID), row(SECOND_TRANSACTION_ID),
                row(THIRD_TRANSACTION_ID));
    }

    /**
     * Builds one read-model row carrying the masked display value.
     *
     * @param transactionId the sixteen-character identifier
     * @return the row
     */
    private static StatementTransactionEntity row(String transactionId) {
        return new StatementTransactionEntity(
                new StatementTransactionId(CARD_TOKEN, transactionId), MASKED_CARD_NUMBER,
                TYPE_CODE, CATEGORY_CODE, SOURCE, DESCRIPTION, ROW_AMOUNT, MERCHANT_ID,
                MERCHANT_NAME, MERCHANT_CITY, MERCHANT_ZIP, ORIGIN_TIMESTAMP,
                PROCESSING_TIMESTAMP);
    }

    /**
     * Serializes one response and reads it back as a tree.
     *
     * @param response the response
     * @return the serialized body
     */
    private static JsonNode bodyOf(NotificationHistoryResponse response) {
        return MAPPER.readTree(MAPPER.writeValueAsString(response));
    }

    /**
     * Reads the top-level property names of one body, in wire order.
     *
     * @param body the serialized response
     * @return the property names, in the order the body carries them
     */
    private static List<String> propertiesOf(JsonNode body) {
        return List.copyOf(body.propertyNames());
    }

    /**
     * Collects every property name of one body at every depth, lower-cased. The envelope and each
     * item of the transaction array are read.
     *
     * @param body the serialized response
     * @return the property names, lower-cased
     */
    private static List<String> lowerCasePropertyNames(JsonNode body) {
        List<String> names = new ArrayList<>();
        collectPropertyNames(body, names);
        return names;
    }

    /**
     * Adds the property names of one node, and of every node below it, to a list.
     *
     * @param node the node to read
     * @param names the list the names are added to, lower-cased
     */
    private static void collectPropertyNames(JsonNode node, List<String> names) {
        if (node.isObject()) {
            for (String name : node.propertyNames()) {
                names.add(name.toLowerCase(Locale.ROOT));
                collectPropertyNames(node.get(name), names);
            }
            return;
        }
        if (node.isArray()) {
            for (JsonNode element : node) {
                collectPropertyNames(element, names);
            }
        }
    }

    /**
     * Reads every accessor of one response and of every item it carries. Four response accessors
     * and twelve accessors per item are read.
     *
     * @param response the response
     * @return the rendered value of each accessor
     * @throws Exception if an accessor cannot be read
     */
    private static List<String> everyAccessorValue(NotificationHistoryResponse response)
            throws Exception {
        List<String> values = new ArrayList<>();
        for (RecordComponent component : NotificationHistoryResponse.class.getRecordComponents()) {
            values.add(String.valueOf(component.getAccessor().invoke(response)));
        }
        for (NotificationTransactionItem item : response.transactions()) {
            for (RecordComponent component
                    : NotificationTransactionItem.class.getRecordComponents()) {
                values.add(String.valueOf(component.getAccessor().invoke(item)));
            }
        }
        return values;
    }

    /**
     * Reads the component names of the response, in declaration order.
     *
     * @return the component names
     */
    private static List<String> componentNames() {
        return Arrays.stream(NotificationHistoryResponse.class.getRecordComponents())
                .map(RecordComponent::getName)
                .toList();
    }

    /**
     * Finds one component of the response by name.
     *
     * @param name the component name
     * @return the component
     * @throws IllegalStateException if the response declares no component of that name
     */
    private static RecordComponent componentNamed(String name) {
        return Arrays.stream(NotificationHistoryResponse.class.getRecordComponents())
                .filter(component -> component.getName().equals(name))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException(
                        "NotificationHistoryResponse declares no component named " + name));
    }

    /**
     * Reports whether any component name holds one lower-cased token.
     *
     * @param lowerCaseToken the token to look for
     * @return true when a component name holds the token
     */
    private static boolean anyComponentNameContains(String lowerCaseToken) {
        return componentNames().stream()
                .map(name -> name.toLowerCase(Locale.ROOT))
                .anyMatch(name -> name.contains(lowerCaseToken));
    }
}
