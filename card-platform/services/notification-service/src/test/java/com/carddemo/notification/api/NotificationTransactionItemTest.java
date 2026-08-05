package com.carddemo.notification.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.carddemo.cobol.PanMasker;
import com.carddemo.notification.entity.StatementTransactionEntity;
import com.carddemo.notification.entity.StatementTransactionEntity.StatementTransactionId;
import java.lang.reflect.RecordComponent;
import java.math.BigDecimal;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import org.junit.jupiter.api.Test;

/**
 * Record-shape and mapping tests for {@link NotificationTransactionItem}.
 *
 * <p>Thirteen components map the record {@code 01 TRNX-RECORD.} at
 * {@code app/cpy/COSTM01.CPY:L20}. Two take the transaction identifier and masked display value,
 * and eleven take the fields at {@code app/cpy/COSTM01.CPY:L25-L35}.
 *
 * <p>Two source constructs carry no component. {@code TRNX-CARD-NUM PIC X(16)} at {@code
 * app/cpy/COSTM01.CPY:L22} sits inside the group {@code 05 TRNX-KEY.} at {@code
 * app/cpy/COSTM01.CPY:L21} and is envelope-level. The trailing {@code FILLER PIC X(20)} at {@code
 * app/cpy/COSTM01.CPY:L36} is dropped.
 *
 * <p>The statement program splits the envelope from the item. The move into
 * {@code TRNX-CARD-NUM} at {@code app/cbl/CBSTM03A.CBL:L421} runs once per card group. The move
 * into {@code TRNX-ID} at {@code app/cbl/CBSTM03A.CBL:L424-L425} and the move into
 * {@code TRNX-REST} at {@code app/cbl/CBSTM03A.CBL:L426-L427} run once per transaction.
 *
 * <p>Each component is text. The two digit identifiers arrive zero-padded to the widths
 * {@code TRNX-CAT-CD PIC 9(04)} at {@code app/cpy/COSTM01.CPY:L26} and
 * {@code TRNX-MERCHANT-ID PIC 9(09)} at {@code app/cpy/COSTM01.CPY:L30} declare. The amount
 * arrives as a decimal string, never as a JavaScript Object Notation (JSON) number.
 *
 * <p>All inputs below are built in this class. These tests read no file, start no application
 * context, open no database connection and reach no broker.
 */
final class NotificationTransactionItemTest {

    /**
     * The thirteen component names, in the order the response declares them. Source order:
     * {@code app/cpy/COSTM01.CPY:L23} then {@code app/cpy/COSTM01.CPY:L25-L35}.
     */
    private static final List<String> DECLARED_COMPONENTS = List.of(
            "transactionId", "maskedCardNumber", "typeCode", "categoryCode", "source",
            "description", "amount", "merchantId", "merchantName", "merchantCity",
            "merchantZip", "originTimestamp", "processingTimestamp");

    /**
     * Names a row-creation or audit stamp carries. {@code statement_transaction} in
     * {@code src/main/resources/db/migration/V1__schema.sql} declares fourteen columns and none of
     * them stamps the row.
     */
    private static final List<String> AUDIT_STAMP_NAMES =
            List.of("createdat", "created", "insertedat", "rowcreatedat", "updatedat");

    /**
     * Fields other services own, lower-cased. The statement program renders six of them:
     * {@code ST-NAME PIC X(75)} at {@code app/cbl/CBSTM03A.CBL:L91}, the three address groups at
     * {@code app/cbl/CBSTM03A.CBL:L93}, {@code app/cbl/CBSTM03A.CBL:L96} and
     * {@code app/cbl/CBSTM03A.CBL:L99}, {@code ST-CURR-BAL PIC 9(9).99-} at
     * {@code app/cbl/CBSTM03A.CBL:L113} and {@code ST-FICO-SCORE PIC X(20)} at
     * {@code app/cbl/CBSTM03A.CBL:L118}. The account service owns all five.
     */
    private static final List<String> FIELDS_OTHER_SERVICES_OWN = List.of("accountid",
            "currentbalance", "creditlimit", "cashcreditlimit", "activestatus", "opendate", "expir",
            "reissuedate", "groupid", "ficoscore", "customername", "addressline1", "addressline2",
            "addressline3", "declinereason", "cardstatus", "accountstatus", "deliverychannel",
            "emailaddress", "phonenumber");

    /**
     * The abbreviated verification field of the source card record, lower-cased. Source:
     * {@code CARD-CVV-CD PIC 9(03)} at {@code app/cpy/CVACT02Y.cpy:L7}.
     */
    private static final String VERIFICATION_FIELD_ABBREVIATION =
            "CARD-CVV-CD".split("-")[1].toLowerCase(Locale.ROOT);

    /** The masked card number the display column holds: twelve mask characters then four digits. */
    private static final String MASKED_CARD_NUMBER = "************7065";

    /** Card token of that card, and the card half of the key every row below carries. */
    private static final String CARD_TOKEN = PanMasker.cardToken("4859452612877065");

    /**
     * A transaction identifier at the sixteen characters {@code TRNX-ID PIC X(16)} at
     * {@code app/cpy/COSTM01.CPY:L23} declares.
     */
    private static final String TRANSACTION_ID = "TRN0000000000001";

    /** A type code at the two characters {@code app/cpy/COSTM01.CPY:L25} declares. */
    private static final String TYPE_CODE = "01";

    /** A category code already at the four digits {@code app/cpy/COSTM01.CPY:L26} declares. */
    private static final String CATEGORY_CODE_AT_FULL_WIDTH = "1234";

    /** A source value shorter than the ten characters {@code app/cpy/COSTM01.CPY:L27} declares. */
    private static final String SOURCE = "POS TERM";

    /**
     * A description filling the hundred characters {@code TRNX-DESC PIC X(100)} at
     * {@code app/cpy/COSTM01.CPY:L28} declares. Twenty characters repeated five times, ending on a
     * character other than a space.
     */
    private static final String DESCRIPTION_AT_FULL_WIDTH = "PURCHASE HARBOUR CO.".repeat(5);

    /** An amount at the two fractional digits {@code app/cpy/COSTM01.CPY:L29} declares. */
    private static final BigDecimal AMOUNT = new BigDecimal("50.47");

    /**
     * A merchant identifier already at the nine digits {@code app/cpy/COSTM01.CPY:L30} declares.
     */
    private static final String MERCHANT_ID_AT_FULL_WIDTH = "800000000";

    /**
     * A merchant name shorter than the fifty characters {@code app/cpy/COSTM01.CPY:L31} declares.
     */
    private static final String MERCHANT_NAME = "HARBOUR SUPPLY CO";

    /**
     * A merchant city shorter than the fifty characters {@code app/cpy/COSTM01.CPY:L32} declares.
     */
    private static final String MERCHANT_CITY = "AUSTIN";

    /** A mail code shorter than the ten characters {@code app/cpy/COSTM01.CPY:L33} declares. */
    private static final String MERCHANT_ZIP = "72112";

    /**
     * An origin timestamp at the twenty-six characters {@code TRNX-ORIG-TS PIC X(26)} at
     * {@code app/cpy/COSTM01.CPY:L34} declares. A space separates the day from the hour, colons
     * separate the time parts, and six fractional digits follow.
     */
    private static final String ORIGIN_TIMESTAMP = "2022-06-10 19:27:53.000000";

    /**
     * A processing timestamp at the twenty-six characters {@code TRNX-PROC-TS PIC X(26)} at
     * {@code app/cpy/COSTM01.CPY:L35} declares. A dash separates the day from the hour, dots
     * separate the time parts, two hundredths digits follow, then four zero characters.
     */
    private static final String PROCESSING_TIMESTAMP = "2022-07-19-23.16.01.470000";

    /** The character count both timestamp columns hold. */
    private static final int TIMESTAMP_WIDTH = 26;

    /**
     * Asserts the item is a record. Source: {@code 01 TRNX-RECORD.} at
     * {@code app/cpy/COSTM01.CPY:L20}.
     */
    @Test
    void theItemIsARecord() {
        assertTrue(NotificationTransactionItem.class.isRecord(),
                "NotificationTransactionItem is a record");
    }

    /**
     * Asserts the component count is thirteen, including the display-only masked card number.
     */
    @Test
    void theItemDeclaresThirteenComponents() {
        assertEquals(13, NotificationTransactionItem.class.getRecordComponents().length,
                "one display value plus the transaction fields");
    }

    /**
     * Asserts the whole ordered name list in one comparison, so a reordering fails loudly. Source
     * order: {@code app/cpy/COSTM01.CPY:L23} then {@code app/cpy/COSTM01.CPY:L25-L35}.
     */
    @Test
    void theComponentNamesFollowTheOrderTheCopybookDeclares() {
        assertEquals(DECLARED_COMPONENTS, componentNames(), "component names in declared order");
    }

    /**
     * Asserts each component type is {@link String}. The two digit identifiers at
     * {@code app/cpy/COSTM01.CPY:L26} and {@code app/cpy/COSTM01.CPY:L30} are digit-patterned text.
     */
    @Test
    void eachComponentIsText() {
        for (RecordComponent component : NotificationTransactionItem.class.getRecordComponents()) {
            assertEquals(String.class, component.getType(),
                    component.getName() + " carries text and not a number");
        }
    }

    /**
     * Asserts no component name carries a row-creation or audit stamp.
     * {@code src/main/resources/db/migration/V1__schema.sql} declares thirteen columns on
     * {@code statement_transaction} and no fourteenth.
     */
    @Test
    void noComponentCarriesARowCreationOrAuditStamp() {
        for (String stamp : AUDIT_STAMP_NAMES) {
            assertFalse(anyComponentNameContains(stamp), "a component name carries " + stamp);
        }
    }

    /**
     * Asserts the item carries only the masked display form of the card number.
     */
    @Test
    void theItemCarriesOnlyTheMaskedCardNumber() {
        NotificationTransactionItem item = NotificationTransactionItem.from(row());
        assertEquals(MASKED_CARD_NUMBER, item.maskedCardNumber(), "display value unchanged");
        assertTrue(item.maskedCardNumber().matches("^\\*{12}[0-9]{4}$"), "masked shape");
        assertFalse(item.maskedCardNumber().matches("^[0-9]{16}$"), "no full card number");
    }

    /**
     * Asserts no component name carries a card verification value or a primary account number. The
     * source card record declares the verification field at {@code app/cpy/CVACT02Y.cpy:L7}.
     */
    @Test
    void noComponentCarriesACardVerificationValue() {
        for (String token : List.of("pan", VERIFICATION_FIELD_ABBREVIATION, "cardverification",
                "securitycode")) {
            assertFalse(anyComponentNameContains(token), "a component name carries " + token);
        }
    }

    /**
     * Asserts no component name carries a field another service owns. The account service owns the
     * six the statement program renders at {@code app/cbl/CBSTM03A.CBL:L91},
     * {@code app/cbl/CBSTM03A.CBL:L93}, {@code app/cbl/CBSTM03A.CBL:L96},
     * {@code app/cbl/CBSTM03A.CBL:L99}, {@code app/cbl/CBSTM03A.CBL:L113} and
     * {@code app/cbl/CBSTM03A.CBL:L118}.
     */
    @Test
    void noComponentCarriesAFieldAnotherServiceOwns() {
        for (String owned : FIELDS_OTHER_SERVICES_OWN) {
            assertFalse(anyComponentNameContains(owned), "a component name carries " + owned);
        }
    }

    /**
     * Asserts an entity category value of {@code 5} arrives as {@code 0005}. The width comes from
     * {@code TRNX-CAT-CD PIC 9(04)} at {@code app/cpy/COSTM01.CPY:L26}.
     */
    @Test
    void theCategoryCodeArrivesZeroPaddedToFourDigits() {
        NotificationTransactionItem item = NotificationTransactionItem.from(
                row("5", MERCHANT_ID_AT_FULL_WIDTH, AMOUNT, DESCRIPTION_AT_FULL_WIDTH));
        assertEquals("0005", item.categoryCode(),
                "TRNX-CAT-CD PIC 9(04) at app/cpy/COSTM01.CPY:L26 holds four digits");
    }

    /**
     * Asserts an entity merchant value of {@code 42} arrives as {@code 000000042}. The width comes
     * from {@code TRNX-MERCHANT-ID PIC 9(09)} at {@code app/cpy/COSTM01.CPY:L30}.
     */
    @Test
    void theMerchantIdentifierArrivesZeroPaddedToNineDigits() {
        NotificationTransactionItem item = NotificationTransactionItem.from(
                row(CATEGORY_CODE_AT_FULL_WIDTH, "42", AMOUNT, DESCRIPTION_AT_FULL_WIDTH));
        assertEquals("000000042", item.merchantId(),
                "TRNX-MERCHANT-ID PIC 9(09) at app/cpy/COSTM01.CPY:L30 holds nine digits");
    }

    /**
     * Asserts a four-digit category value and a nine-digit merchant value arrive with no added
     * zero. Widths from {@code app/cpy/COSTM01.CPY:L26} and {@code app/cpy/COSTM01.CPY:L30}.
     */
    @Test
    void anIdentifierAlreadyAtItsColumnWidthPassesThroughUnchanged() {
        NotificationTransactionItem item = NotificationTransactionItem.from(row());
        assertEquals(CATEGORY_CODE_AT_FULL_WIDTH, item.categoryCode(), "four digits unchanged");
        assertEquals(MERCHANT_ID_AT_FULL_WIDTH, item.merchantId(), "nine digits unchanged");
        assertEquals(4, item.categoryCode().length(), "no leading zero added to the category");
        assertEquals(9, item.merchantId().length(), "no leading zero added to the merchant");
    }

    /**
     * Asserts the factory returns four and nine characters from each supplied input width.
     * {@code src/main/resources/db/migration/V1__schema.sql} declares
     * {@code category_code CHAR(4)} and {@code merchant_id CHAR(9)}.
     */
    @Test
    void bothDigitIdentifiersHoldTheirColumnWidthForEachInputWidth() {
        for (String category : List.of("5", "05", "005", "1234")) {
            NotificationTransactionItem item = NotificationTransactionItem.from(
                    row(category, MERCHANT_ID_AT_FULL_WIDTH, AMOUNT, DESCRIPTION_AT_FULL_WIDTH));
            assertEquals(4, item.categoryCode().length(),
                    "category_code CHAR(4) width from the input " + category);
        }
        for (String merchant : List.of("42", "000000042", "800000000")) {
            NotificationTransactionItem item = NotificationTransactionItem.from(row(
                    CATEGORY_CODE_AT_FULL_WIDTH, merchant, AMOUNT, DESCRIPTION_AT_FULL_WIDTH));
            assertEquals(9, item.merchantId().length(),
                    "merchant_id CHAR(9) width from the input " + merchant);
        }
    }

    /**
     * Asserts {@code 50.47} arrives as {@code 50.47}. The scale comes from
     * {@code TRNX-AMT PIC S9(09)V99} at {@code app/cpy/COSTM01.CPY:L29}.
     */
    @Test
    void aPositiveAmountRendersAsAPlainDecimalStringAtTwoFractionalDigits() {
        NotificationTransactionItem item = NotificationTransactionItem.from(row());
        assertEquals("50.47", item.amount(),
                "TRNX-AMT PIC S9(09)V99 at app/cpy/COSTM01.CPY:L29 carries two fractional digits");
    }

    /**
     * Asserts {@code -125.00} arrives with its leading minus and two fractional digits. The sign
     * comes from {@code TRNX-AMT PIC S9(09)V99} at {@code app/cpy/COSTM01.CPY:L29}.
     */
    @Test
    void aNegativeAmountRendersWithALeadingMinusAndTwoFractionalDigits() {
        NotificationTransactionItem item = NotificationTransactionItem.from(
                row(CATEGORY_CODE_AT_FULL_WIDTH, MERCHANT_ID_AT_FULL_WIDTH,
                        new BigDecimal("-125.00"), DESCRIPTION_AT_FULL_WIDTH));
        assertEquals("-125.00", item.amount(), "the sign of a refund survives");
        assertTrue(item.amount().startsWith("-"), "a leading minus");
    }

    /**
     * Asserts an amount written with an exponent arrives as plain digits, carrying neither
     * {@code E} nor {@code e}.
     */
    @Test
    void theRenderedAmountCarriesNoExponentMarker() {
        BigDecimal writtenWithAnExponent = new BigDecimal("5.047E+1");
        assertEquals(2, writtenWithAnExponent.scale(), "the fixture carries the column scale");
        NotificationTransactionItem item = NotificationTransactionItem.from(
                row(CATEGORY_CODE_AT_FULL_WIDTH, MERCHANT_ID_AT_FULL_WIDTH, writtenWithAnExponent,
                        DESCRIPTION_AT_FULL_WIDTH));
        assertEquals("50.47", item.amount(), "plain digits and a decimal point");
        assertFalse(item.amount().contains("E"), "no upper-case exponent marker");
        assertFalse(item.amount().contains("e"), "no lower-case exponent marker");
    }

    /**
     * Asserts nine integer digits arrive in full. The ceiling comes from
     * {@code TRNX-AMT PIC S9(09)V99} at {@code app/cpy/COSTM01.CPY:L29}, which the column
     * {@code amount NUMERIC(11,2)} holds as nine integer digits and two fractional digits.
     */
    @Test
    void anAmountWithNineIntegerDigitsRendersInFull() {
        NotificationTransactionItem item = NotificationTransactionItem.from(
                row(CATEGORY_CODE_AT_FULL_WIDTH, MERCHANT_ID_AT_FULL_WIDTH,
                        new BigDecimal("999999999.99"), DESCRIPTION_AT_FULL_WIDTH));
        assertEquals("999999999.99", item.amount(),
                "nine integer digits and two fractional digits, the ceiling"
                        + " TRNX-AMT PIC S9(09)V99 at app/cpy/COSTM01.CPY:L29 declares");
        assertEquals(9, item.amount().indexOf('.'), "nine digits precede the decimal point");
    }

    /**
     * Asserts a hundred-character description arrives character for character, with no truncation.
     * The width comes from {@code TRNX-DESC PIC X(100)} at {@code app/cpy/COSTM01.CPY:L28} and the
     * column {@code description CHAR(100)}.
     */
    @Test
    void theDescriptionSurvivesAtItsHundredCharacterWidth() {
        assertEquals(100, DESCRIPTION_AT_FULL_WIDTH.length(), "the fixture fills the column");
        NotificationTransactionItem item = NotificationTransactionItem.from(row());
        assertEquals(DESCRIPTION_AT_FULL_WIDTH, item.description(),
                "TRNX-DESC PIC X(100) at app/cpy/COSTM01.CPY:L28 survives character"
                        + " for character");
    }

    /**
     * Asserts the origin timestamp arrives as its twenty-six characters. Source:
     * {@code TRNX-ORIG-TS PIC X(26)} at {@code app/cpy/COSTM01.CPY:L34}.
     */
    @Test
    void theOriginTimestampPassesThroughCharacterForCharacter() {
        NotificationTransactionItem item = NotificationTransactionItem.from(row());
        assertEquals(ORIGIN_TIMESTAMP, item.originTimestamp(),
                "TRNX-ORIG-TS PIC X(26) at app/cpy/COSTM01.CPY:L34 keeps its space,"
                        + " its colons and its six fractional digits");
    }

    /**
     * Asserts the processing timestamp arrives as its twenty-six characters. Source:
     * {@code TRNX-PROC-TS PIC X(26)} at {@code app/cpy/COSTM01.CPY:L35}.
     */
    @Test
    void theProcessingTimestampPassesThroughCharacterForCharacter() {
        NotificationTransactionItem item = NotificationTransactionItem.from(row());
        assertEquals(PROCESSING_TIMESTAMP, item.processingTimestamp(),
                "TRNX-PROC-TS PIC X(26) at app/cpy/COSTM01.CPY:L35 keeps its dash,"
                        + " its dots, its hundredths and its four zero characters");
    }

    /**
     * Asserts both timestamps hold twenty-six characters and match the values the row stored, so
     * the factory reformats neither. Widths from {@code app/cpy/COSTM01.CPY:L34} and
     * {@code app/cpy/COSTM01.CPY:L35}.
     */
    @Test
    void bothTimestampsHoldTwentySixCharactersAndTheFactoryReformatsNeither() {
        StatementTransactionEntity stored = row();
        NotificationTransactionItem item = NotificationTransactionItem.from(stored);
        assertEquals(TIMESTAMP_WIDTH, item.originTimestamp().length(), "origin timestamp width");
        assertEquals(TIMESTAMP_WIDTH, item.processingTimestamp().length(),
                "processing timestamp width");
        assertEquals(stored.getOriginTimestamp(), item.originTimestamp(),
                "the origin timestamp arrives as stored");
        assertEquals(stored.getProcessingTimestamp(), item.processingTimestamp(),
                "the processing timestamp arrives as stored");
    }

    /**
     * Asserts the six remaining text fields arrive unchanged. Sources:
     * {@code app/cpy/COSTM01.CPY:L23}, {@code app/cpy/COSTM01.CPY:L25},
     * {@code app/cpy/COSTM01.CPY:L27}, {@code app/cpy/COSTM01.CPY:L31},
     * {@code app/cpy/COSTM01.CPY:L32} and {@code app/cpy/COSTM01.CPY:L33}.
     */
    @Test
    void theSixRemainingTextFieldsPassThroughUnchanged() {
        NotificationTransactionItem item = NotificationTransactionItem.from(row());
        assertEquals(TRANSACTION_ID, item.transactionId(), "app/cpy/COSTM01.CPY:L23");
        assertEquals(TYPE_CODE, item.typeCode(), "app/cpy/COSTM01.CPY:L25");
        assertEquals(SOURCE, item.source(), "app/cpy/COSTM01.CPY:L27");
        assertEquals(MERCHANT_NAME, item.merchantName(), "app/cpy/COSTM01.CPY:L31");
        assertEquals(MERCHANT_CITY, item.merchantCity(), "app/cpy/COSTM01.CPY:L32");
        assertEquals(MERCHANT_ZIP, item.merchantZip(), "app/cpy/COSTM01.CPY:L33");
    }

    /**
     * Lists the component names the record declares, in declaration order.
     *
     * @return the thirteen names
     */
    private static List<String> componentNames() {
        return Arrays.stream(NotificationTransactionItem.class.getRecordComponents())
                .map(RecordComponent::getName)
                .toList();
    }

    /**
     * Reports whether a component name carries the token, comparing without case.
     *
     * @param lowerCaseToken the token, already lower-cased
     * @return true when one of the declared component names carries the token
     */
    private static boolean anyComponentNameContains(String lowerCaseToken) {
        return componentNames().stream()
                .map(name -> name.toLowerCase(Locale.ROOT))
                .anyMatch(name -> name.contains(lowerCaseToken));
    }

    /**
     * Builds one read-model row from the four values the tests vary. The remaining values come
     * from the constants above.
     *
     * @param categoryCode the category code, up to four digits
     * @param merchantId the merchant identifier, up to nine digits
     * @param amount the amount at two fractional digits
     * @param description the description, up to a hundred characters
     * @return the row
     */
    private static StatementTransactionEntity row(String categoryCode, String merchantId,
            BigDecimal amount, String description) {
        return new StatementTransactionEntity(
                new StatementTransactionId(CARD_TOKEN, TRANSACTION_ID), MASKED_CARD_NUMBER,
                TYPE_CODE,
                categoryCode, SOURCE, description, amount, merchantId, MERCHANT_NAME,
                MERCHANT_CITY, MERCHANT_ZIP, ORIGIN_TIMESTAMP, PROCESSING_TIMESTAMP);
    }

    /**
     * Builds one read-model row whose values are already at their column widths.
     *
     * @return the row
     */
    private static StatementTransactionEntity row() {
        return row(CATEGORY_CODE_AT_FULL_WIDTH, MERCHANT_ID_AT_FULL_WIDTH, AMOUNT,
                DESCRIPTION_AT_FULL_WIDTH);
    }
}
