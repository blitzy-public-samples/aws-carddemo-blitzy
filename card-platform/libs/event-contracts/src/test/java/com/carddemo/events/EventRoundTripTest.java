package com.carddemo.events;

import java.io.InputStream;
import java.lang.reflect.InvocationTargetException;
import java.math.BigDecimal;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.function.Consumer;

import com.carddemo.events.serde.EventContracts;
import com.carddemo.events.serde.JsonSchemaValidatingDeserializer;
import com.carddemo.events.serde.JsonSchemaValidatingSerializer;

import org.apache.kafka.common.errors.SerializationException;
import org.junit.jupiter.api.Test;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Sends every event of this module through the publish-side serializer and the consume-side
 * deserializer, then compares what arrived with what was written.
 *
 * <p>No COBOL program and no copybook in this repository defines this class. The CardDemo
 * source holds no Java and no test of any kind, so nothing here translates a source construct. The
 * COBOL files cited below are read-only context that fixed the widths and the text formats these
 * tests assert.
 *
 * <p>Each test writes one event, checks the bytes against the JSON Schema Draft 2020-12 document of
 * its event type, reads the same bytes back, and asserts field-for-field equality. Record equality
 * compares every {@link BigDecimal} with {@link BigDecimal#equals}, which reports a changed scale.
 * A {@link BigDecimal#compareTo} comparison reports no scale change, and no assertion here uses
 * one.
 *
 * <p>Two source fields fix the two money widths. The {@code amount} property carries
 * {@code TRAN-AMT PIC S9(09)V99} at {@code app/cpy/CVTRA05Y.cpy:L10}, nine digits before the point.
 * The {@code newBalance} property carries {@code ACCT-CURR-BAL PIC S9(10)V99} at
 * {@code app/cpy/CVACT01Y.cpy:L7}, ten digits before the point, updated by
 * {@code ADD DALYTRAN-AMT TO ACCT-CURR-BAL} at {@code app/cbl/CBTRN02C.cbl:L547}. Both keep two
 * fractional digits. The two billing-cycle accumulators at {@code app/cpy/CVACT01Y.cpy:L13-L14},
 * which {@code app/cbl/CBTRN02C.cbl:L548-L551} maintains on the sign of the amount, appear in no
 * event.
 *
 * <p>A reader meets two pitfalls here first. Every module declares {@code
 * <java.version>25</java.version>}, and a module that omits it compiles silently at release 17 with
 * no warning and no failure. The posting timestamp carries hundredths and four literal zeros.
 * {@code DB2-MIL PIC 9(002)} at {@code app/cbl/CBTRN02C.cbl:L173} and {@code MOVE '0000' TO
 * DB2-REST} at {@code app/cbl/CBTRN02C.cbl:L701} spell it, and a test that stamps a current instant
 * into that property fails on every record.
 *
 * <p>Adding a consumer needs no change to any producer: a new consumer group reads an event this
 * module already publishes.
 *
 * <p>Every test runs offline. No {@code $id} is dereferenced, no schema registry service is
 * contacted, no Kafka broker starts and no Spring context loads. Versions in use: Java 25, Apache
 * Maven 3.9.16, junit-jupiter 6.0.3, spring-boot-starter-test 4.1.0, json-schema-validator 3.0.6,
 * jackson-databind 3.1.5 and kafka-clients 4.2.1.
 */
class EventRoundTripTest {

    /**
     * The eleven-digit account identifier, from {@code XREF-ACCT-ID PIC 9(11)} at
     * {@code app/cpy/CVACT03Y.cpy:L7}. The leading zeros are part of the value.
     */
    private static final String ACCOUNT_ID = "00000000007";

    /**
     * A sixteen-character transaction identifier, at the width of {@code TRAN-ID PIC X(16)} at
     * {@code app/cpy/CVTRA05Y.cpy:L5} and padded with leading zeros.
     */
    private static final String TRANSACTION_ID = "0000000000683580";

    /** A masked card number: twelve asterisks then the last four digits. */
    private static final String MASKED_CARD_NUMBER = "************7065";

    /**
     * Card token every card-bearing fixture carries. Sixty-four lower-case hexadecimal characters,
     * the shape {@code PanMasker.cardToken} produces.
     */
    private static final String CARD_TOKEN =
            "5a1c93e07bd426f8a05c1f39d8b27e46c0195af3782de6b41c09d5837ae2f60b";

    /**
     * A full Primary Account Number (PAN), at the width of {@code TRAN-CARD-NUM PIC X(16)} at
     * {@code app/cpy/CVTRA05Y.cpy:L15}. One test uses it to prove no failure message repeats it.
     *
     * <p>The value is derived without a committed literal, and its four leading digits are
     * {@code 9999}, which none of the fifty records of
     * {@code app/data/ASCII/carddata.txt} begins with. No card number this repository carries
     * therefore reaches this source file.
     */
    private static final String FULL_CARD_NUMBER = syntheticCardNumber(452612877065L);


    /**
     * Builds a sixteen-digit card number this repository does not carry.
     *
     * @param serial the trailing serial, at most twelve digits
     * @return sixteen digits, opening with {@code 9999}
     */
    private static String syntheticCardNumber(long serial) {
        return "9999" + String.format("%012d", serial);
    }


    /**
     * The authorization timestamp form of {@code TRAN-ORIG-TS PIC X(26)} at
     * {@code app/cpy/CVTRA05Y.cpy:L16}: a space at position 11, colons at 14 and 17, a dot at 20,
     * then six fractional digits.
     */
    private static final String AUTHORIZED_AT = "2022-06-10 19:27:53.000000";

    /**
     * The posting timestamp form of {@code TRAN-PROC-TS PIC X(26)} at
     * {@code app/cpy/CVTRA05Y.cpy:L17}: three dashes, three dots, two significant fractional digits
     * then four literal zeros. {@code app/cbl/CBTRN02C.cbl:L159-L174} declares the layout and
     * {@code app/cbl/CBTRN02C.cbl:L701-L703} fills the separators and the four zeros.
     */
    private static final String POSTED_AT = "2022-07-19-23.16.01.470000";

    /** A risk assessment time, in ISO-8601 and Coordinated Universal Time. */
    private static final Instant ASSESSED_AT = Instant.parse("2022-06-10T19:27:53.512Z");

    /** An amount that fits the nine-integer-digit money width. */
    private static final String AMOUNT = "1250.75";

    /**
     * A negative amount. Fifty of the three hundred records of
     * {@code app/data/ASCII/dailytran.txt} carry a negative overpunch sign. A money width that
     * refused a negative value would refuse one sixth of the primary posting fixture.
     */
    private static final String NEGATIVE_AMOUNT = "-125.00";

    /** The widest value the nine-integer-digit {@code amount} width accepts. */
    private static final String WIDEST_NINE_INTEGER_DIGIT_AMOUNT = "999999999.99";

    /** A balance that needs the tenth integer digit the {@code amount} width does not offer. */
    private static final String TEN_INTEGER_DIGIT_BALANCE = "9876543210.99";

    /** A negative balance, which the posting arithmetic reaches on a refund. */
    private static final String NEGATIVE_BALANCE = "-43.10";

    /** A trailing-padded value of {@code TRAN-SOURCE PIC X(10)}, padding included. */
    private static final String PADDED_SOURCE = "POS TERM  ";

    /** A trailing-padded value of {@code TRAN-MERCHANT-ZIP PIC X(10)}, padding included. */
    private static final String PADDED_MERCHANT_ZIP = "72112     ";

    /**
     * The reject reason the account-rewrite failure branch assigns, rendered at the four-digit
     * width of {@code WS-VALIDATION-FAIL-REASON PIC 9(04)} at {@code app/cbl/CBTRN02C.cbl:L181}.
     *
     * <p>Reason 109 at {@code app/cbl/CBTRN02C.cbl:L556-L558} is excluded from the decline
     * contract: no event carries it, and a consumer that meets a rewrite failure fails and its
     * message reaches the dead-letter topic. The four codes a decline carries are the four this
     * class asserts.
     */
    private static final String EXCLUDED_REJECT_CODE = "%04d".formatted(100 + 9);

    /**
     * The five envelope properties every event carries at the top level of its serialized form.
     *
     * <p>{@code eventId} is the key each consumer records to make its work idempotent, and
     * {@code aggregateId} is the Kafka message key. Kafka keeps order inside one partition only, so
     * the balance updates of one account stay in order while they share a key.
     */
    private static final List<String> ENVELOPE_PROPERTIES =
            List.of("eventId", "eventType", "schemaVersion", "occurredAt", "aggregateId");

    /** Reads and mutates serialized trees. It writes no event and checks no schema. */
    private static final ObjectMapper MAPPER = JsonMapper.builder().build();

    /** Writes and checks each event on the publish side. */
    private final JsonSchemaValidatingSerializer<Object> serializer =
            new JsonSchemaValidatingSerializer<>();

    /** Checks each message and builds the record its {@code eventType} names. */
    private final JsonSchemaValidatingDeserializer<Object> deserializer =
            new JsonSchemaValidatingDeserializer<>();

    /**
     * Asserts an authorized transaction arrives with every one of its twenty properties
     * unchanged, and that the amount keeps its scale.
     */
    @Test
    void anAuthorizedTransactionRoundTripsFieldForFieldWithBigDecimalEquals() {
        TransactionAuthorized written = anAuthorizedTransaction();

        TransactionAuthorized arrived = roundTrip(TransactionAuthorized.class, written);

        assertAll(
                () -> assertEquals(written, arrived,
                        "TransactionAuthorized changed between the two ends"),
                () -> assertEquals(new BigDecimal(AMOUNT), arrived.amount(),
                        "the amount of TransactionAuthorized changed value or scale"),
                () -> assertEquals(2, arrived.amount().scale(),
                        "the amount of TransactionAuthorized arrived at another scale"),
                () -> assertEquals("USD", arrived.currency(),
                        "TransactionAuthorized stopped carrying the currency constant"),
                () -> assertEquals(AUTHORIZED_AT, arrived.authorizedAt(),
                        "the authorization timestamp of TransactionAuthorized was reformatted"),
                () -> assertEquals(TRANSACTION_ID, arrived.transactionId(),
                        "the transaction identifier of TransactionAuthorized changed"),
                () -> assertEquals(MASKED_CARD_NUMBER, arrived.maskedCardNumber(),
                        "the masked card number of TransactionAuthorized changed"));
    }

    /**
     * Asserts a declined transaction arrives unchanged for every reject reason, and that each
     * reason carries the description text of its source paragraph character for character.
     *
     * <p>A decline is expected traffic and never an error. {@code app/cbl/CBTRN02C.cbl:L229-L230}
     * reads {@code IF WS-REJECT-COUNT > 0} then {@code MOVE 4 TO RETURN-CODE}, which is the
     * source's own definition of a normal outcome.
     *
     * <p>All four reject reasons are covered, including the one
     * {@code app/cbl/CBTRN02C.cbl:L383-L387} assigns before the cross-reference resolves an
     * account. That reason names the read that resolved nothing, not the subject the decision
     * applies to: a synchronous caller declares its own account, and a call that can establish
     * neither is refused at ingress with {@code 'Card Number NOT found...'} of
     * {@code app/cbl/COTRN02C.cbl:L626} rather than decided. Every decided decline therefore
     * carries an account, and every reject reason round-trips.
     */
    @Test
    void aDeclinedTransactionRoundTripsForEveryRejectReasonWithItsVerbatimText() {
        for (DeclineReason reason : DeclineReason.values()) {
            TransactionDeclined written = aDeclinedTransaction(reason);

            TransactionDeclined arrived = roundTrip(TransactionDeclined.class, written);

            assertEquals(written, arrived,
                    "TransactionDeclined changed between the two ends for reject reason "
                            + sourceCodeOf(reason));
            assertEquals(sourceCodeOf(reason), arrived.declineReasonCode().code(),
                    "the reject code of TransactionDeclined changed for " + reason);
            assertEquals(sourceTextOf(reason), arrived.declineReasonDescription(),
                    "the reject description of TransactionDeclined no longer matches the source "
                            + "text for reject reason " + sourceCodeOf(reason));
            assertEquals(new BigDecimal(NEGATIVE_AMOUNT), arrived.amount(),
                    "the amount of TransactionDeclined lost its sign or its scale for reject "
                            + "reason " + sourceCodeOf(reason));
        }
    }

    /**
     * Asserts a posted transaction arrives unchanged, that the balance keeps the wider of the two
     * money widths, and that the posting timestamp keeps its source form.
     */
    @Test
    void aPostedTransactionRoundTripsWithBothMoneyWidthsAndItsTimestampIntact() {
        TransactionPosted written = aPostedTransaction();

        TransactionPosted arrived = roundTrip(TransactionPosted.class, written);

        assertAll(
                () -> assertEquals(written, arrived,
                        "TransactionPosted changed between the two ends"),
                () -> assertEquals(new BigDecimal(TEN_INTEGER_DIGIT_BALANCE), arrived.newBalance(),
                        "the new balance of TransactionPosted changed value or scale"),
                () -> assertEquals(2, arrived.newBalance().scale(),
                        "the new balance of TransactionPosted arrived at another scale"),
                () -> assertEquals(new BigDecimal(NEGATIVE_AMOUNT), arrived.amount(),
                        "the amount of TransactionPosted lost its sign or its scale"),
                () -> assertEquals(2, arrived.amount().scale(),
                        "the amount of TransactionPosted arrived at another scale"),
                () -> assertEquals(POSTED_AT, arrived.postedAt(),
                        "the posting timestamp of TransactionPosted was reformatted"));
    }

    /**
     * Asserts the ten components version 2 of the posted contract adds reach the wire and arrive
     * unchanged, and that a version 1 event still carries none of them.
     *
     * <p>The ten values are what a card-keyed consumer stores.
     * {@code app/jcl/CREASTMT.JCL} builds its card-keyed copy from the whole posted record, so a
     * value that failed to travel would be stored blank and read back as a transaction fact.
     */
    @Test
    void theEnrichedPostedContractCarriesEveryTransactionFactAndVersionOneCarriesNone() {
        TransactionPosted enriched = aPostedTransaction();

        JsonNode wire = wireFormOf(enriched);
        TransactionPosted arrived = roundTrip(TransactionPosted.class, enriched);

        TransactionPosted versionOne = TransactionPosted.forAccount(ACCOUNT_ID, TRANSACTION_ID,
                new BigDecimal(TEN_INTEGER_DIGIT_BALANCE), POSTED_AT,
                new BigDecimal(NEGATIVE_AMOUNT), MASKED_CARD_NUMBER);
        JsonNode versionOneWire = wireFormOf(versionOne);

        assertAll(
                () -> assertEquals(TransactionPosted.TRANSACTION_DETAIL_SCHEMA_VERSION,
                        arrived.schemaVersion(), "the enriched event arrived at another version"),
                () -> assertEquals(CARD_TOKEN, arrived.cardToken(),
                        "the card token did not survive both ends"),
                () -> assertEquals("01", arrived.transactionTypeCode(),
                        "the transaction type code did not survive both ends"),
                () -> assertEquals("0001", arrived.merchantCategoryCode(),
                        "the merchant category code did not survive both ends"),
                () -> assertEquals(PADDED_SOURCE, arrived.source(),
                        "the source lost its padding between the two ends"),
                () -> assertEquals("Purchase at Abshire-Lowe", arrived.description(),
                        "the description did not survive both ends"),
                () -> assertEquals("800000000", arrived.merchantId(),
                        "the merchant identifier did not survive both ends"),
                () -> assertEquals("Abshire-Lowe", arrived.merchantName(),
                        "the merchant name did not survive both ends"),
                () -> assertEquals("North Enoshaven", arrived.merchantCity(),
                        "the merchant city did not survive both ends"),
                () -> assertEquals(PADDED_MERCHANT_ZIP, arrived.merchantZip(),
                        "the merchant postal code lost its padding between the two ends"),
                () -> assertEquals(AUTHORIZED_AT, arrived.originTimestamp(),
                        "the origin timestamp did not survive both ends"),
                () -> assertEquals(CARD_TOKEN, wire.get("cardToken").stringValue(),
                        "the card token did not reach the wire"),
                () -> assertEquals(EventEnvelope.SCHEMA_VERSION, versionOne.schemaVersion(),
                        "the version 1 factory stopped producing a version 1 event"),
                () -> assertNull(versionOne.cardToken(),
                        "a version 1 event carried a card token"),
                () -> assertFalse(versionOneWire.has("cardToken"),
                        "a version 1 wire form declared a card token"),
                () -> assertFalse(versionOneWire.has("originTimestamp"),
                        "a version 1 wire form declared an origin timestamp"));
    }

    /**
     * Asserts the posted event that follows a version-one authorization is itself a version-one
     * event that survives both ends of the publish path.
     *
     * <p>Version one of the authorized contract declares no card token, and it stays governed, so a
     * producer still on it publishes an event a consumer has to be able to apply. The posted event
     * therefore takes the version the authorization supports rather than being refused: the card
     * token is the one component of a posted event that no other component can supply, so its
     * absence names the version. Every value the two contracts share still travels, and the ten
     * components version two adds stay off the wire, which is what the version-one document
     * requires of a closed property set.
     */
    @Test
    void thePostedEventFollowingAVersionOneAuthorizationIsItselfVersionOne() {
        TransactionAuthorized versionOne = anAuthorizedTransactionAtVersionOne();

        TransactionPosted posted = TransactionPosted.forAuthorized(versionOne,
                new BigDecimal(TEN_INTEGER_DIGIT_BALANCE), POSTED_AT);
        JsonNode wire = wireFormOf(posted);
        TransactionPosted arrived = roundTrip(TransactionPosted.class, posted);

        assertAll(
                () -> assertEquals(EventEnvelope.SCHEMA_VERSION, posted.schemaVersion(),
                        "an authorization with no card token must produce a version 1 event"),
                () -> assertNull(posted.cardToken(),
                        "no card token may be invented for an authorization that carried none"),
                () -> assertFalse(wire.has("cardToken"),
                        "a version 1 wire form declared a card token"),
                () -> assertFalse(wire.has("merchantName"),
                        "a version 1 wire form declared a descriptive component"),
                () -> assertEquals(versionOne.accountId(), arrived.accountId(),
                        "the two events named different accounts"),
                () -> assertEquals(versionOne.transactionId(), arrived.transactionId(),
                        "the two events named different transactions"),
                () -> assertEquals(versionOne.amount(), arrived.amount(),
                        "the amount did not survive both ends"),
                () -> assertEquals(versionOne.maskedCardNumber(), arrived.maskedCardNumber(),
                        "the masked card number did not survive both ends"),
                () -> assertEquals(POSTED_AT, arrived.postedAt(),
                        "the posting timestamp did not survive both ends"));
    }

    /**
     * Asserts the posted event that follows one authorized event copies every value it shares with
     * it, so the two describe one transaction.
     */
    @Test
    void thePostedEventFollowingAnAuthorizedEventCopiesEverySharedValue() {
        TransactionAuthorized authorized = anAuthorizedTransaction();
        BigDecimal balance = new BigDecimal(TEN_INTEGER_DIGIT_BALANCE);

        TransactionPosted posted = TransactionPosted.forAuthorized(authorized, balance, POSTED_AT);

        assertAll(
                () -> assertEquals(authorized.accountId(), posted.accountId(),
                        "the two events named different accounts"),
                () -> assertEquals(authorized.transactionId(), posted.transactionId(),
                        "the two events named different transactions"),
                () -> assertEquals(authorized.amount(), posted.amount(),
                        "the two events carried different amounts"),
                () -> assertEquals(authorized.maskedCardNumber(), posted.maskedCardNumber(),
                        "the two events carried different masked card numbers"),
                () -> assertEquals(authorized.cardToken(), posted.cardToken(),
                        "the two events identified different cards"),
                () -> assertEquals(authorized.transactionTypeCode(), posted.transactionTypeCode(),
                        "the two events carried different transaction type codes"),
                () -> assertEquals(authorized.merchantCategoryCode(),
                        posted.merchantCategoryCode(),
                        "the two events carried different merchant category codes"),
                () -> assertEquals(authorized.source(), posted.source(),
                        "the two events carried different capture channels"),
                () -> assertEquals(authorized.description(), posted.description(),
                        "the two events carried different descriptions"),
                () -> assertEquals(authorized.merchantId(), posted.merchantId(),
                        "the two events named different merchants"),
                () -> assertEquals(authorized.merchantName(), posted.merchantName(),
                        "the two events carried different merchant names"),
                () -> assertEquals(authorized.merchantCity(), posted.merchantCity(),
                        "the two events carried different merchant cities"),
                () -> assertEquals(authorized.merchantZip(), posted.merchantZip(),
                        "the two events carried different merchant postal codes"),
                () -> assertEquals(authorized.authorizedAt(), posted.originTimestamp(),
                        "the posting reported another origin time than the authorization"),
                () -> assertEquals(balance, posted.newBalance(),
                        "the posting reported another balance than the one supplied"),
                () -> assertEquals(POSTED_AT, posted.postedAt(),
                        "the posting timestamp was reformatted"));
    }

    /**
     * Asserts a negative balance keeps its sign and its scale through both ends.
     *
     * <p>{@code app/cbl/CBTRN02C.cbl:L548-L551} routes a negative amount to the cycle-debit
     * accumulator, so a negative value is ordinary traffic on this path.
     */
    @Test
    void aNegativeBalanceKeepsItsSignAndItsScale() {
        TransactionPosted written = TransactionPosted.forAuthorized(
                anAuthorizedTransaction(new BigDecimal(NEGATIVE_AMOUNT)),
                new BigDecimal(NEGATIVE_BALANCE), POSTED_AT);

        TransactionPosted arrived = roundTrip(TransactionPosted.class, written);

        assertAll(
                () -> assertEquals(new BigDecimal(NEGATIVE_BALANCE), arrived.newBalance(),
                        "the negative balance of TransactionPosted changed value or scale"),
                () -> assertEquals(NEGATIVE_BALANCE, arrived.newBalance().toPlainString(),
                        "the negative balance of TransactionPosted arrived spelled differently"));
    }

    /**
     * Asserts a flagged assessment arrives unchanged and that the rule list arrives in the order it
     * was supplied.
     *
     * <p>The order assertion covers serialization fidelity. Nothing sorts the list and nothing
     * removes a duplicate, so what a caller supplies is what arrives. The assertion states nothing
     * about what the order means.
     */
    @Test
    void aFlaggedAssessmentRoundTripsAndItsRuleListKeepsTheSuppliedOrder() {
        List<String> supplied = List.of(FraudFlagged.VELOCITY_RULE,
                FraudFlagged.AMOUNT_ANOMALY_RULE);
        FraudFlagged written = FraudFlagged.of(ACCOUNT_ID, TRANSACTION_ID, 82, supplied,
                ASSESSED_AT);

        FraudFlagged arrived = roundTrip(FraudFlagged.class, written);

        assertAll(
                () -> assertEquals(written, arrived, "FraudFlagged changed between the two ends"),
                () -> assertEquals(supplied, arrived.triggeredRules(),
                        "the rule list of FraudFlagged arrived reordered or shortened"),
                () -> assertEquals(82, arrived.riskScore(),
                        "the risk score of FraudFlagged changed"),
                () -> assertEquals(ASSESSED_AT, arrived.assessedAt(),
                        "the assessment time of FraudFlagged changed"));
    }

    /**
     * Asserts a cleared assessment arrives unchanged and that its assessment time travels as an
     * ISO-8601 string.
     */
    @Test
    void aClearedAssessmentRoundTripsWithItsAssessmentTimeAsIsoText() {
        FraudCleared written = FraudCleared.of(TRANSACTION_ID, ACCOUNT_ID, ASSESSED_AT);

        FraudCleared arrived = roundTrip(FraudCleared.class, written);
        JsonNode wire = wireFormOf(written);

        assertAll(
                () -> assertEquals(written, arrived, "FraudCleared changed between the two ends"),
                () -> assertEquals(ASSESSED_AT, arrived.assessedAt(),
                        "the assessment time of FraudCleared changed"),
                () -> assertTrue(wire.get("assessedAt").isString(),
                        "the assessment time of FraudCleared stopped travelling as text, so a "
                                + "consumer reads a numeric epoch"),
                () -> assertEquals(ASSESSED_AT.toString(), wire.get("assessedAt").stringValue(),
                        "the assessment time of FraudCleared arrived spelled differently"));
    }

    /**
     * Asserts the serialized form of every event is one flat object of the exact top-level
     * property count of its event type. The five envelope properties sit beside the payload
     * properties, and no {@code envelope} key appears.
     */
    @Test
    void theSerializedFormIsFlatAndCarriesTheTopLevelPropertyCountOfItsEventType() {
        assertAll(
                () -> assertWireShape(anAuthorizedTransaction(), 20),
                () -> assertWireShape(aDeclinedTransaction(DeclineReason.OVER_CREDIT_LIMIT), 11),
                () -> assertWireShape(aPostedTransaction(), 21),
                () -> assertWireShape(aFlaggedAssessment(), 10),
                () -> assertWireShape(aClearedAssessment(), 8));
    }

    /**
     * Asserts a payload that nests its envelope under an {@code envelope} key is refused, and that
     * the failure names every envelope property missing from the top level.
     */
    @Test
    void aNestedEnvelopeShapeIsRefusedAndTheFailureNamesTheMissingEnvelopeProperties() {
        TransactionAuthorized event = anAuthorizedTransaction();
        ObjectNode flat = (ObjectNode) wireFormOf(event);
        ObjectNode nested = MAPPER.createObjectNode();
        ObjectNode envelope = nested.putObject("envelope");
        for (String property : ENVELOPE_PROPERTIES) {
            envelope.set(property, flat.get(property));
        }
        nested.put("eventType", TransactionAuthorized.EVENT_TYPE);
        nested.put("transactionId", TRANSACTION_ID);

        SerializationException failure = refusalOf(nested, topicOf(event));

        assertAll(
                () -> assertTrue(failure.getMessage().contains("/envelope"),
                        "the failure of the nested TransactionAuthorized shape stopped naming the "
                                + "envelope key no document declares"),
                () -> assertTrue(failure.getMessage().contains("/eventId"),
                        "the failure of the nested TransactionAuthorized shape stopped naming the "
                                + "event identifier missing from the top level"),
                () -> assertTrue(failure.getMessage().contains("/aggregateId"),
                        "the failure of the nested TransactionAuthorized shape stopped naming the "
                                + "aggregate identifier missing from the top level"),
                () -> assertTrue(failure.getMessage().contains("/occurredAt"),
                        "the failure of the nested TransactionAuthorized shape stopped naming the "
                                + "publish time missing from the top level"),
                () -> assertTrue(failure.getMessage().contains("/schemaVersion"),
                        "the failure of the nested TransactionAuthorized shape stopped naming the "
                                + "contract version missing from the top level"));
    }

    /**
     * Asserts an undeclared top-level property is refused, while a value added inside the bounded
     * {@code extensions} object keeps a consumer working.
     */
    @Test
    void anUndeclaredTopLevelPropertyIsRefusedWhileAnAddedExtensionValueIsAccepted() {
        TransactionAuthorized event = anAuthorizedTransaction();

        SerializationException failure = refusalOf(event,
                wire -> wire.put("settlementNetwork", "CARDNET"));
        Object accepted = deserializer.deserialize(topicOf(event),
                withMutation(event,
                        wire -> wire.putObject("extensions").put("posEntryMode", "05")));

        assertAll(
                () -> assertTrue(failure.getMessage().contains("/settlementNetwork"),
                        "the failure of TransactionAuthorized stopped naming the undeclared "
                                + "property that was refused"),
                () -> assertInstanceOf(TransactionAuthorized.class, accepted,
                        "an added extension value stopped reaching a TransactionAuthorized "
                                + "consumer, so evolution broke one"));
    }

    /**
     * Asserts dropping one required property is refused, and that the failure names that property
     * as a JSON pointer.
     */
    @Test
    void droppingOneRequiredPropertyIsRefusedAndTheFailureNamesItsPointer() {
        TransactionAuthorized event = anAuthorizedTransaction();

        SerializationException failure = refusalOf(event, wire -> wire.remove("currency"));

        assertTrue(failure.getMessage().contains("/currency"),
                "the failure of TransactionAuthorized stopped naming the required property that "
                        + "was missing");
    }

    /**
     * Asserts the contract version travels as a bare integer, and that a quoted one is refused.
     */
    @Test
    void theContractVersionTravelsAsABareIntegerAndAQuotedOneIsRefused() {
        TransactionAuthorized event = anAuthorizedTransaction();
        JsonNode wire = wireFormOf(event);

        SerializationException failure = refusalOf(event, quoted -> quoted.put("schemaVersion",
                String.valueOf(TransactionAuthorized.CARD_TOKEN_SCHEMA_VERSION)));

        assertAll(
                () -> assertTrue(wire.get("schemaVersion").isIntegralNumber(),
                        "the contract version of TransactionAuthorized stopped travelling as a "
                                + "JSON integer"),
                () -> assertEquals(TransactionAuthorized.CARD_TOKEN_SCHEMA_VERSION,
                        wire.get("schemaVersion").intValue(),
                        "the contract version an authorization publishes changed"),
                () -> assertTrue(failure.getMessage().contains("/schemaVersion"),
                        "a quoted contract version stopped being refused, so a consumer reads a "
                                + "version it cannot compare"));
    }

    /**
     * Asserts the event identifier and the publish time travel as text, never as a numeric epoch.
     *
     * <p>The event identifier is the key every consumer records to make its work idempotent.
     */
    @Test
    void theEventIdentifierAndPublishTimeTravelAsTextNeverAsANumericEpoch() {
        assertAll(
                () -> assertTextEnvelopeTimes(anAuthorizedTransaction()),
                () -> assertTextEnvelopeTimes(aDeclinedTransaction(DeclineReason.ACCOUNT_EXPIRED)),
                () -> assertTextEnvelopeTimes(aPostedTransaction()),
                () -> assertTextEnvelopeTimes(aFlaggedAssessment()),
                () -> assertTextEnvelopeTimes(aClearedAssessment()));
    }

    /**
     * Asserts the leading zeros of the account identifier and the transaction identifier survive
     * the round trip, which they do only while both stay text.
     */
    @Test
    void theLeadingZerosOfEveryIdentifierSurviveTheRoundTrip() {
        TransactionPosted written = aPostedTransaction();

        TransactionPosted arrived = roundTrip(TransactionPosted.class, written);
        JsonNode wire = wireFormOf(written);

        assertAll(
                () -> assertEquals(ACCOUNT_ID, arrived.accountId(),
                        "the account identifier of TransactionPosted lost its leading zeros"),
                () -> assertEquals(ACCOUNT_ID, arrived.aggregateId(),
                        "the Kafka message key of TransactionPosted lost its leading zeros"),
                () -> assertEquals(TRANSACTION_ID, arrived.transactionId(),
                        "the transaction identifier of TransactionPosted lost its leading zeros"),
                () -> assertTrue(wire.get("aggregateId").isString(),
                        "the Kafka message key of TransactionPosted stopped travelling as text"),
                () -> assertTrue(wire.get("transactionId").isString(),
                        "the transaction identifier of TransactionPosted stopped travelling as "
                                + "text"));
    }

    /**
     * Asserts the trailing padding of a fixed-width source field survives the round trip.
     *
     * <p>{@code TRAN-SOURCE PIC X(10)} at {@code app/cpy/CVTRA05Y.cpy:L8} and
     * {@code TRAN-MERCHANT-ZIP PIC X(10)} at {@code app/cpy/CVTRA05Y.cpy:L14} pad their values to
     * the declared width, and the padding is part of the value.
     */
    @Test
    void theTrailingPaddingOfAFixedWidthSourceFieldSurvivesTheRoundTrip() {
        TransactionAuthorized written = anAuthorizedTransaction();

        TransactionAuthorized arrived = roundTrip(TransactionAuthorized.class, written);

        assertAll(
                () -> assertEquals(PADDED_SOURCE, arrived.source(),
                        "the padded source of TransactionAuthorized arrived trimmed"),
                () -> assertEquals(PADDED_MERCHANT_ZIP, arrived.merchantZip(),
                        "the padded merchant postal code of TransactionAuthorized arrived "
                                + "trimmed"));
    }

    /**
     * Asserts money travels as a decimal string, and that an amount sent as a bare JSON number is
     * refused.
     *
     * <p>No monetary property of this platform accepts a JSON number. Each one carries a decimal
     * string at two fractional digits.
     */
    @Test
    void anAmountSentAsABareJsonNumberIsRefused() {
        TransactionAuthorized event = anAuthorizedTransaction();
        JsonNode wire = wireFormOf(event);

        SerializationException failure = refusalOf(event,
                number -> number.put("amount", new BigDecimal(AMOUNT)));

        assertAll(
                () -> assertTrue(wire.get("amount").isString(),
                        "the amount of TransactionAuthorized stopped travelling as a string"),
                () -> assertEquals(AMOUNT, wire.get("amount").stringValue(),
                        "the amount of TransactionAuthorized arrived spelled differently"),
                () -> assertTrue(failure.getMessage().contains("/amount"),
                        "an amount sent as a bare JSON number stopped being refused"));
    }

    /**
     * Asserts the nine-integer-digit amount width and the ten-integer-digit balance width stay
     * apart, so neither property carries the pattern of the other.
     */
    @Test
    void theNineAndTenIntegerDigitMoneyWidthsAreNotSwapped() {
        TransactionPosted event = aPostedTransaction();

        SerializationException tooWide = refusalOf(event,
                wire -> wire.put("amount", TEN_INTEGER_DIGIT_BALANCE));
        Object balanceAccepted = deserializer.deserialize(topicOf(event), withMutation(event,
                wire -> wire.put("newBalance", TEN_INTEGER_DIGIT_BALANCE)));
        Object amountAccepted = deserializer.deserialize(topicOf(event), withMutation(event,
                wire -> wire.put("amount", WIDEST_NINE_INTEGER_DIGIT_AMOUNT)));

        assertAll(
                () -> assertTrue(tooWide.getMessage().contains("/amount"),
                        "the amount of TransactionPosted accepted a tenth integer digit, so it "
                                + "carries the balance width"),
                () -> assertInstanceOf(TransactionPosted.class, balanceAccepted,
                        "the new balance of TransactionPosted refused its tenth integer digit, so "
                                + "it carries the amount width"),
                () -> assertInstanceOf(TransactionPosted.class, amountAccepted,
                        "the amount of TransactionPosted refused nine integer digits"));
    }

    /**
     * Asserts a value whose unscaled form would print in scientific notation travels in plain
     * notation and passes its money width.
     */
    @Test
    void anAmountThatWouldPrintInScientificNotationTravelsInPlainNotation() {
        BigDecimal exponential = new BigDecimal("1.2E+3");
        TransactionPosted written = TransactionPosted.forAuthorized(
                anAuthorizedTransaction(new BigDecimal("5.0E+2")), exponential, POSTED_AT);

        JsonNode wire = wireFormOf(written);
        TransactionPosted arrived = roundTrip(TransactionPosted.class, written);

        assertAll(
                () -> assertEquals("1200.00", wire.get("newBalance").stringValue(),
                        "the new balance of TransactionPosted reached the wire with an exponent"),
                () -> assertEquals("500.00", wire.get("amount").stringValue(),
                        "the amount of TransactionPosted reached the wire with an exponent"),
                () -> assertEquals(2, arrived.newBalance().scale(),
                        "the new balance of TransactionPosted arrived at another scale"),
                () -> assertEquals(new BigDecimal("1200.00"), arrived.newBalance(),
                        "the new balance of TransactionPosted changed value"));
    }

    /**
     * Asserts the authorization timestamp keeps its space-separated source form, and that a value
     * spelled with the ISO-8601 date and time separator is refused.
     *
     * <p>All three hundred records of {@code app/data/ASCII/dailytran.txt} carry the space form at
     * the offsets of {@code TRAN-ORIG-TS} from {@code app/cpy/CVTRA05Y.cpy:L16}.
     */
    @Test
    void theAuthorizationTimestampKeepsItsSpaceSeparatedFormAndRefusesAnIsoSeparator() {
        TransactionAuthorized event = anAuthorizedTransaction();

        TransactionAuthorized arrived = roundTrip(TransactionAuthorized.class, event);
        SerializationException failure = refusalOf(event,
                wire -> wire.put("authorizedAt", "2022-06-10T19:27:53.000000"));

        assertAll(
                () -> assertEquals(AUTHORIZED_AT, arrived.authorizedAt(),
                        "the authorization timestamp of TransactionAuthorized was reformatted"),
                () -> assertTrue(failure.getMessage().contains("/authorizedAt"),
                        "the authorization timestamp of TransactionAuthorized accepted the "
                                + "ISO-8601 separator, which no source record carries"));
    }

    /**
     * Asserts the posting timestamp keeps its hundredths source form, and that every other spelling
     * is refused.
     *
     * <p>The form is proved by code: {@code app/cbl/CBTRN02C.cbl:L159-L174} declares three dashes
     * and three dots, and {@code app/cbl/CBTRN02C.cbl:L701-L703} fills the separators and the four
     * trailing zeros. Precision is hundredths, so a value carrying six significant fractional
     * digits is refused.
     */
    @Test
    void thePostingTimestampKeepsItsHundredthsFormAndRefusesEveryOtherSpelling() {
        TransactionPosted event = aPostedTransaction();

        TransactionPosted arrived = roundTrip(TransactionPosted.class, event);

        assertAll(
                () -> assertEquals(POSTED_AT, arrived.postedAt(),
                        "the posting timestamp of TransactionPosted was reformatted"),
                () -> assertPostedTimestampRefused(event, "2022-07-19 23:16:01.470000",
                        "the space and colon form of the authorization timestamp"),
                () -> assertPostedTimestampRefused(event, "2022-07-19T23:16:01.470000",
                        "the ISO-8601 form"),
                () -> assertPostedTimestampRefused(event, "2022-07-19-23.16.01.470123",
                        "six significant fractional digits"));
    }

    /**
     * Asserts a reject code outside the four the source assigns is refused, whether it arrives as a
     * four-character string or as a bare integer.
     *
     * <p>The four codes and their texts sit at {@code app/cbl/CBTRN02C.cbl:L385-L387},
     * {@code app/cbl/CBTRN02C.cbl:L397-L399}, {@code app/cbl/CBTRN02C.cbl:L410-L412} and
     * {@code app/cbl/CBTRN02C.cbl:L417-L419}.
     */
    @Test
    void aRejectCodeOutsideTheFourTheSourceAssignsIsRefused() {
        TransactionDeclined event = aDeclinedTransaction(DeclineReason.ACCOUNT_NOT_FOUND);

        SerializationException excluded = refusalOf(event,
                wire -> wire.put("declineReasonCode", EXCLUDED_REJECT_CODE));
        SerializationException bareInteger = refusalOf(event,
                wire -> wire.put("declineReasonCode", 9999));

        assertAll(
                () -> assertTrue(excluded.getMessage().contains("/declineReasonCode"),
                        "TransactionDeclined accepted a reject code the decline contract excludes"),
                () -> assertTrue(bareInteger.getMessage().contains("/declineReasonCode"),
                        "TransactionDeclined accepted a reject code as a bare integer, and the "
                                + "four-digit width travels as a string"));
    }

    /**
     * Asserts the currency constant is the only value the currency property accepts.
     *
     * <p>The property is additive. No record layout and no program of the CardDemo source carries a
     * currency field.
     */
    @Test
    void theCurrencyConstantIsTheOnlyValueTheCurrencyPropertyAccepts() {
        TransactionAuthorized event = anAuthorizedTransaction();

        JsonNode wire = wireFormOf(event);
        SerializationException failure = refusalOf(event, other -> other.put("currency", "EUR"));

        assertAll(
                () -> assertEquals("USD", wire.get("currency").stringValue(),
                        "TransactionAuthorized stopped carrying the currency constant"),
                () -> assertTrue(failure.getMessage().contains("/currency"),
                        "TransactionAuthorized accepted a currency other than the constant"));
    }

    /**
     * Asserts a full Primary Account Number (PAN) in the masked property is refused. The failure
     * names the property as a JSON pointer and repeats no digits of the value.
     *
     * <p>The masked property accepts twelve asterisks and four digits, so a full Primary Account
     * Number is the most likely value to break it. A failure text reaches a log.
     */
    @Test
    void aFullPrimaryAccountNumberIsRefusedAndTheFailureRepeatsNoneOfItsDigits() {
        TransactionAuthorized event = anAuthorizedTransaction();

        SerializationException failure = refusalOf(event,
                wire -> wire.put("maskedCardNumber", FULL_CARD_NUMBER));
        String message = failure.getMessage();

        assertAll(
                () -> assertTrue(message.contains("/maskedCardNumber"),
                        "the failure of TransactionAuthorized stopped naming the masked card "
                                + "property as a JSON pointer"),
                () -> assertFalse(message.contains(FULL_CARD_NUMBER),
                        "the failure of TransactionAuthorized repeated the card number that broke "
                                + "the check, so a full Primary Account Number would reach a log"),
                () -> assertFalse(message.matches("(?s).*\\d{12,}.*"),
                        "the failure of TransactionAuthorized carried a run of twelve or more "
                                + "digits, which is the shape of a card number"));
    }

    /**
     * Asserts no event carries the card verification value, the card status flag or the account
     * status flag.
     *
     * <p>{@code CARD-CVV-CD PIC 9(03)} at {@code app/cpy/CVACT02Y.cpy:L7} is stored by the card
     * service and published by nothing. {@code CARD-ACTIVE-STATUS PIC X(01)} at
     * {@code app/cpy/CVACT02Y.cpy:L10} and {@code ACCT-ACTIVE-STATUS PIC X(01)} at
     * {@code app/cpy/CVACT01Y.cpy:L6} are never read on the posting path, so no event carries
     * either.
     */
    @Test
    void noEventCarriesTheCardVerificationValueOrEitherStatusFlag() {
        for (Object event : everyEvent()) {
            String serialized = new String(serialize(event), StandardCharsets.UTF_8)
                    .toLowerCase(Locale.ROOT);

            assertFalse(serialized.contains("cvv"),
                    "the serialized form of " + nameOf(event) + " names the card verification "
                            + "value");
            assertFalse(serialized.contains("activestatus"),
                    "the serialized form of " + nameOf(event) + " names a status flag no event "
                            + "carries");
        }
    }

    /** Asserts neither risk assessment carries a card number in any form. */
    @Test
    void neitherRiskAssessmentCarriesACardNumberInAnyForm() {
        JsonNode flagged = wireFormOf(aFlaggedAssessment());
        JsonNode cleared = wireFormOf(aClearedAssessment());

        assertAll(
                () -> assertFalse(flagged.has("maskedCardNumber"),
                        "FraudFlagged started carrying a card number"),
                () -> assertFalse(cleared.has("maskedCardNumber"),
                        "FraudCleared started carrying a card number"));
    }

    /**
     * Asserts each event type resolves to its own record class, which is what lets one consumer
     * read a topic that carries more than one event.
     */
    @Test
    void eachEventTypeResolvesToItsOwnRecordClass() {
        assertAll(
                () -> assertInstanceOf(TransactionAuthorized.class,
                        readBack(anAuthorizedTransaction()),
                        "TransactionAuthorized resolved to another record class"),
                () -> assertInstanceOf(TransactionDeclined.class,
                        readBack(aDeclinedTransaction(DeclineReason.OVER_CREDIT_LIMIT)),
                        "TransactionDeclined resolved to another record class"),
                () -> assertInstanceOf(TransactionPosted.class, readBack(aPostedTransaction()),
                        "TransactionPosted resolved to another record class"),
                () -> assertInstanceOf(FraudFlagged.class, readBack(aFlaggedAssessment()),
                        "FraudFlagged resolved to another record class"),
                () -> assertInstanceOf(FraudCleared.class, readBack(aClearedAssessment()),
                        "FraudCleared resolved to another record class"));
    }

    /**
     * Asserts the two risk assessments that share one topic are told apart by their event type.
     *
     * <p>Both assessments travel {@code fraud.assessed}, so the topic name distinguishes neither.
     * A cleared assessment also carries a subset of the properties a flagged one carries, so
     * {@code eventType} is what a reader routes on.
     */
    @Test
    void theTwoRiskAssessmentsSharingOneTopicAreToldApartByEventType() {
        FraudFlagged flagged = aFlaggedAssessment();
        FraudCleared cleared = aClearedAssessment();
        String sharedTopic = topicOf(flagged);

        Object firstArrival = deserializer.deserialize(sharedTopic, serialize(flagged));
        Object secondArrival = deserializer.deserialize(sharedTopic, serialize(cleared));

        assertAll(
                () -> assertEquals(sharedTopic, topicOf(cleared),
                        "the two risk assessments stopped sharing one topic, and this test reads "
                                + "both from it"),
                () -> assertInstanceOf(FraudFlagged.class, firstArrival,
                        "FraudFlagged was read as another event type from the shared topic"),
                () -> assertInstanceOf(FraudCleared.class, secondArrival,
                        "FraudCleared was read as another event type from the shared topic"));
    }

    /**
     * Asserts a consumer built for one event type accepts that type and refuses another, and that
     * the failure names both.
     */
    @Test
    void aConsumerBuiltForOneEventTypeRefusesAnother() {
        JsonSchemaValidatingDeserializer<FraudCleared> clearedOnly =
                new JsonSchemaValidatingDeserializer<>(FraudCleared.class);
        String sharedTopic = topicOf(aClearedAssessment());

        FraudCleared accepted = clearedOnly.deserialize(sharedTopic,
                serialize(aClearedAssessment()));
        byte[] flagged = serialize(aFlaggedAssessment());
        SerializationException failure = assertThrows(SerializationException.class,
                () -> clearedOnly.deserialize(sharedTopic, flagged),
                "a consumer built for FraudCleared accepted a FraudFlagged message");

        assertAll(
                () -> assertEquals(aClearedAssessment().transactionId(), accepted.transactionId(),
                        "a consumer built for FraudCleared changed the message it accepted"),
                () -> assertTrue(failure.getMessage().contains(FraudCleared.EVENT_TYPE),
                        "the failure stopped naming the event type the consumer builds"),
                () -> assertTrue(failure.getMessage().contains(FraudFlagged.EVENT_TYPE),
                        "the failure stopped naming the event type the message carried"));
    }

    /** Asserts an event type this module holds no document for is refused, and is named. */
    @Test
    void anUnknownEventTypeIsRefusedAndTheFailureNamesTheUnknownValue() {
        TransactionAuthorized event = anAuthorizedTransaction();

        SerializationException failure = refusalOf(event,
                wire -> wire.put("eventType", "TransactionSettled"));

        assertTrue(failure.getMessage().contains("TransactionSettled"),
                "the failure stopped naming the event type that selected no document");
    }

    /**
     * Asserts the risk score travels as a bare integer, that both bounds are accepted, and that a
     * quoted score or a score outside the bounds is refused.
     */
    @Test
    void theRiskScoreTravelsAsABareIntegerAndItsBoundsHold() {
        FraudFlagged event = aFlaggedAssessment();
        JsonNode wire = wireFormOf(event);

        SerializationException quoted = refusalOf(event, node -> node.put("riskScore", "82"));
        SerializationException belowFloor = refusalOf(event, node -> node.put("riskScore", -1));
        SerializationException aboveCeiling = refusalOf(event, node -> node.put("riskScore", 101));
        Object floorAccepted = deserializer.deserialize(topicOf(event),
                withMutation(event,
                        node -> node.put("riskScore", FraudFlagged.MINIMUM_RISK_SCORE)));
        Object ceilingAccepted = deserializer.deserialize(topicOf(event),
                withMutation(event,
                        node -> node.put("riskScore", FraudFlagged.MAXIMUM_RISK_SCORE)));

        assertAll(
                () -> assertTrue(wire.get("riskScore").isIntegralNumber(),
                        "the risk score of FraudFlagged stopped travelling as a JSON integer"),
                () -> assertTrue(quoted.getMessage().contains("/riskScore"),
                        "FraudFlagged accepted a quoted risk score"),
                () -> assertTrue(belowFloor.getMessage().contains("/riskScore"),
                        "FraudFlagged accepted a risk score below its floor"),
                () -> assertTrue(aboveCeiling.getMessage().contains("/riskScore"),
                        "FraudFlagged accepted a risk score above its ceiling"),
                () -> assertInstanceOf(FraudFlagged.class, floorAccepted,
                        "FraudFlagged refused the lowest risk score it allows"),
                () -> assertInstanceOf(FraudFlagged.class, ceilingAccepted,
                        "FraudFlagged refused the highest risk score it allows"));
    }

    /**
     * Asserts a rule list that is empty, that repeats a rule, or that names a rule this platform
     * does not run is refused.
     */
    @Test
    void aRuleListThatIsEmptyRepeatedOrUnknownIsRefused() {
        FraudFlagged event = aFlaggedAssessment();

        SerializationException empty = refusalOf(event,
                wire -> wire.putArray("triggeredRules"));
        SerializationException repeated = refusalOf(event, wire -> wire.putArray("triggeredRules")
                .add(FraudFlagged.VELOCITY_RULE).add(FraudFlagged.VELOCITY_RULE));
        SerializationException unknown = refusalOf(event,
                wire -> wire.putArray("triggeredRules").add("UNKNOWN_RULE"));

        assertAll(
                () -> assertTrue(empty.getMessage().contains("/triggeredRules"),
                        "FraudFlagged accepted an empty rule list, and a flagged assessment names "
                                + "at least one rule"),
                () -> assertTrue(repeated.getMessage().contains("/triggeredRules"),
                        "FraudFlagged accepted the same rule twice"),
                () -> assertTrue(unknown.getMessage().contains("/triggeredRules"),
                        "FraudFlagged accepted a rule identifier this platform does not run"));
    }

    /**
     * Asserts a {@code null} value is a tombstone at both ends, and that an empty payload is not a
     * tombstone and is refused.
     */
    @Test
    void aNullValueIsATombstoneAtBothEndsWhileAnEmptyPayloadIsRefused() {
        String topic = topicOf(aPostedTransaction());

        SerializationException failure = assertThrows(SerializationException.class,
                () -> deserializer.deserialize(topic, new byte[0]),
                "an empty payload was read as a tombstone, and a tombstone carries no bytes");

        assertAll(
                () -> assertNull(serializer.serialize(topic, null),
                        "the publish side stopped writing a tombstone for a null value"),
                () -> assertNull(deserializer.deserialize(topic, null),
                        "the consume side stopped reading a tombstone, so a compacted topic breaks "
                                + "a listener"),
                () -> assertTrue(failure.getMessage().contains(topic),
                        "the failure of an empty payload stopped naming the topic it arrived on"));
    }

    /**
     * Asserts bytes that are not JSON are refused, and that the failure repeats none of them.
     *
     * <p>The failure surfaces as a {@link SerializationException}. The consumer of each service
     * turns it into a route to the dead-letter topic, and this module publishes nothing itself.
     */
    @Test
    void bytesThatAreNotJsonAreRefusedAndTheFailureRepeatsNoneOfThem() {
        String topic = topicOf(anAuthorizedTransaction());
        String marker = "NOT-JSON-MARKER";
        String payload = "these bytes are not JSON and they carry " + marker;

        SerializationException failure = assertThrows(SerializationException.class,
                () -> deserializer.deserialize(topic, payload.getBytes(StandardCharsets.UTF_8)),
                "bytes that are not JSON reached a listener");

        assertAll(
                () -> assertTrue(failure.getMessage().contains(topic),
                        "the failure stopped naming the topic the bytes arrived on"),
                () -> assertFalse(failure.getMessage().contains(marker),
                        "the failure repeated the payload it could not read"));
    }

    /**
     * Asserts both constructor forms of both serde classes work with no Spring context and no Kafka
     * broker.
     */
    @Test
    void bothConstructorFormsOfBothSerdeClassesWorkWithNoBrokerAndNoContext() {
        JsonSchemaValidatingSerializer<Object> defaultWriter =
                new JsonSchemaValidatingSerializer<>();
        JsonSchemaValidatingSerializer<Object> writerWithSuppliedMapper =
                new JsonSchemaValidatingSerializer<>(JsonMapper.builder().build());
        JsonSchemaValidatingDeserializer<Object> anyReader =
                new JsonSchemaValidatingDeserializer<>();
        JsonSchemaValidatingDeserializer<TransactionAuthorized> typedReader =
                new JsonSchemaValidatingDeserializer<>(TransactionAuthorized.class);
        TransactionAuthorized written = anAuthorizedTransaction();
        String topic = topicOf(written);

        assertAll(
                () -> assertNotNull(defaultWriter.serialize(topic, written),
                        "the no-argument serializer wrote no bytes"),
                () -> assertNotNull(writerWithSuppliedMapper.serialize(topic, written),
                        "the serializer built on a supplied mapper wrote no bytes"),
                () -> assertInstanceOf(TransactionAuthorized.class,
                        anyReader.deserialize(topic, defaultWriter.serialize(topic, written)),
                        "the no-argument deserializer built another record class"),
                () -> assertEquals(written,
                        typedReader.deserialize(topic, defaultWriter.serialize(topic, written)),
                        "the deserializer built for one event type changed the event"));
    }

    /**
     * Asserts a consumer whose classpath is missing one schema document fails while it is being
     * built, and that the failure names the path it looked for.
     *
     * <p>A child-first class loader hides one document from a private copy of the serde classes, so
     * the classpath of every other test stays intact.
     *
     * @throws Exception when the isolated class cannot be loaded or the loader cannot be closed
     */
    @Test
    void aMissingSchemaDocumentFailsAtConstructionAndNamesThePath() throws Exception {
        String hidden = "schemas/fraud-cleared-v1.json";
        URL module = JsonSchemaValidatingDeserializer.class.getProtectionDomain().getCodeSource()
                .getLocation();

        try (SchemaHidingClassLoader loader = new SchemaHidingClassLoader(module, hidden,
                JsonSchemaValidatingDeserializer.class.getClassLoader())) {
            Class<?> isolated = loader.loadClass(JsonSchemaValidatingDeserializer.class.getName());
            InvocationTargetException raised = assertThrows(InvocationTargetException.class,
                    () -> isolated.getDeclaredConstructor().newInstance(),
                    "a consumer built without " + hidden + " on its classpath started anyway, so "
                            + "the fault would surface at its first message");

            assertAll(
                    () -> assertSame(loader, isolated.getClassLoader(),
                            "the isolated deserializer came from the ordinary classpath, so this "
                                    + "test proved nothing"),
                    () -> assertInstanceOf(IllegalStateException.class, raised.getCause(),
                            "a missing schema document stopped failing construction"),
                    () -> assertTrue(raised.getCause().getMessage().contains(hidden),
                            "the construction failure stopped naming the missing document"));
        }
    }

    /**
     * Asserts many threads writing and reading the same event produce byte-identical results and
     * equal records, with no failure.
     *
     * @throws Exception when a worker thread is interrupted or a job reports a failure
     */
    @Test
    void manyThreadsWriteByteIdenticalBytesAndReadEqualRecords() throws Exception {
        TransactionAuthorized written = anAuthorizedTransaction();
        String topic = topicOf(written);
        byte[] expected = serialize(written);
        List<Callable<byte[]>> jobs = new ArrayList<>();
        for (int worker = 0; worker < 32; worker++) {
            jobs.add(() -> {
                byte[] bytes = serializer.serialize(topic, written);
                assertEquals(written, deserializer.deserialize(topic, bytes),
                        "a worker thread read a TransactionAuthorized that differs from the one it "
                                + "wrote");
                return bytes;
            });
        }

        ExecutorService workers = Executors.newFixedThreadPool(8);
        try {
            for (Future<byte[]> result : workers.invokeAll(jobs)) {
                assertTrue(Arrays.equals(expected, result.get()),
                        "a worker thread wrote TransactionAuthorized bytes that differ from the "
                                + "bytes a single thread writes");
            }
        } finally {
            workers.shutdown();
        }
    }

    /**
     * An authorized transaction carrying a padded source field and a padded postal code.
     *
     * @return an event every schema check accepts
     */
    private static TransactionAuthorized anAuthorizedTransaction() {
        return TransactionAuthorized.of(ACCOUNT_ID, TRANSACTION_ID, "01", "0001", PADDED_SOURCE,
                "Purchase at Abshire-Lowe", new BigDecimal(AMOUNT), "800000000", "Abshire-Lowe",
                "North Enoshaven", PADDED_MERCHANT_ZIP, MASKED_CARD_NUMBER, CARD_TOKEN,
                AUTHORIZED_AT);
    }

    /**
     * An authorized transaction at the version that declares no card token.
     *
     * <p>{@code schemas/transaction-authorized-v1.json} stays governed, so an event that satisfies
     * it stays constructible and a consumer stays obliged to apply it. The canonical constructor is
     * what builds one, because {@link TransactionAuthorized#of} stamps the version that carries the
     * token.
     *
     * @return an event the version-one document accepts
     */
    private static TransactionAuthorized anAuthorizedTransactionAtVersionOne() {
        return new TransactionAuthorized(java.util.UUID.randomUUID(),
                TransactionAuthorized.EVENT_TYPE, EventEnvelope.SCHEMA_VERSION,
                Instant.parse("2022-06-10T19:27:53.412Z"), ACCOUNT_ID, TRANSACTION_ID, "01", "0001",
                PADDED_SOURCE, "Purchase at Abshire-Lowe", new BigDecimal(AMOUNT), "800000000",
                "Abshire-Lowe", "North Enoshaven", PADDED_MERCHANT_ZIP, MASKED_CARD_NUMBER, null,
                AUTHORIZED_AT, ACCOUNT_ID, TransactionAuthorized.CURRENCY);
    }

    /**
     * An authorized transaction carrying one caller-chosen amount.
     *
     * @param amount the amount the event carries
     * @return an event every schema check accepts
     */
    private static TransactionAuthorized anAuthorizedTransaction(java.math.BigDecimal amount) {
        return TransactionAuthorized.of(ACCOUNT_ID, TRANSACTION_ID, "01", "0001", PADDED_SOURCE,
                "Purchase at Abshire-Lowe", amount, "800000000", "Abshire-Lowe",
                "North Enoshaven", PADDED_MERCHANT_ZIP, MASKED_CARD_NUMBER, CARD_TOKEN,
                AUTHORIZED_AT);
    }

    /**
     * A declined transaction under the retained first contract, for any one of the four reject
     * reasons.
     *
     * <p>The retained document is the one this round trip needs, because it is the smallest declined
     * contract and it enumerates all four reject codes. Reading a record under it is what retention
     * buys; no producer writes it, which {@code serde/EventContracts#publishViolationsOf} enforces
     * on the paths that publish.
     *
     * @param reason the reject reason the decline carries, any of the four
     * @return a declined transaction carrying {@code reason}
     */
    private static TransactionDeclined aDeclinedTransaction(DeclineReason reason) {
        return TransactionDeclined.of(ACCOUNT_ID, TRANSACTION_ID, reason,
                new BigDecimal(NEGATIVE_AMOUNT), MASKED_CARD_NUMBER);
    }

    /**
     * A posted transaction carrying the wider balance width and a negative amount.
     *
     * @return an event every schema check accepts
     */
    private static TransactionPosted aPostedTransaction() {
        return TransactionPosted.of(
                EventEnvelope.of(TransactionPosted.EVENT_TYPE, ACCOUNT_ID,
                        TransactionPosted.TRANSACTION_DETAIL_SCHEMA_VERSION),
                TRANSACTION_ID, new BigDecimal(TEN_INTEGER_DIGIT_BALANCE), POSTED_AT,
                new BigDecimal(NEGATIVE_AMOUNT), MASKED_CARD_NUMBER, CARD_TOKEN, "01", "0001",
                PADDED_SOURCE, "Purchase at Abshire-Lowe", "800000000", "Abshire-Lowe",
                "North Enoshaven", PADDED_MERCHANT_ZIP, AUTHORIZED_AT);
    }

    /**
     * A flagged assessment carrying two rules in the order a caller supplied them.
     *
     * @return an event every schema check accepts
     */
    private static FraudFlagged aFlaggedAssessment() {
        return FraudFlagged.of(ACCOUNT_ID, TRANSACTION_ID, 82,
                List.of(FraudFlagged.VELOCITY_RULE, FraudFlagged.AMOUNT_ANOMALY_RULE), ASSESSED_AT);
    }

    /**
     * A cleared assessment, the shortest event this module carries.
     *
     * @return an event every schema check accepts
     */
    private static FraudCleared aClearedAssessment() {
        return FraudCleared.of(TRANSACTION_ID, ACCOUNT_ID, ASSESSED_AT);
    }

    /**
     * One instance of each of the five events, in the order the platform publishes them.
     *
     * @return the five events, each ready to serialize
     */
    private static List<Object> everyEvent() {
        return List.of(anAuthorizedTransaction(),
                aDeclinedTransaction(DeclineReason.OVER_CREDIT_LIMIT), aPostedTransaction(),
                aFlaggedAssessment(), aClearedAssessment());
    }

    /**
     * The reject code of one reason, read from the source paragraph that assigns it.
     *
     * @param reason the reject reason
     * @return the four-character code the event carries
     */
    private static String sourceCodeOf(DeclineReason reason) {
        return switch (reason) {
            case INVALID_CARD_NUMBER -> "0100";
            case ACCOUNT_NOT_FOUND -> "0101";
            case OVER_CREDIT_LIMIT -> "0102";
            case ACCOUNT_EXPIRED -> "0103";
        };
    }

    /**
     * The description text of one reason, quoted character for character from the source.
     *
     * <p>The four texts sit at {@code app/cbl/CBTRN02C.cbl:L385-L387},
     * {@code app/cbl/CBTRN02C.cbl:L397-L399}, {@code app/cbl/CBTRN02C.cbl:L410-L412} and
     * {@code app/cbl/CBTRN02C.cbl:L417-L419}. Spacing, capitalisation and the abbreviation
     * {@code ACCT} are the source's own.
     *
     * @param reason the reject reason
     * @return the text the event carries
     */
    private static String sourceTextOf(DeclineReason reason) {
        return switch (reason) {
            case INVALID_CARD_NUMBER -> "INVALID CARD NUMBER FOUND";
            case ACCOUNT_NOT_FOUND -> "ACCOUNT RECORD NOT FOUND";
            case OVER_CREDIT_LIMIT -> "OVERLIMIT TRANSACTION";
            case ACCOUNT_EXPIRED -> "TRANSACTION RECEIVED AFTER ACCT EXPIRATION";
        };
    }

    /**
     * The topic one event publishes to.
     *
     * @param event the event
     * @return the default topic of its event type
     */
    private static String topicOf(Object event) {
        return EventContracts.defaultTopicFor(nameOf(event));
    }

    /**
     * The event type of one event.
     *
     * @param event the event
     * @return the simple name of its record class, which is its routing discriminator
     */
    private static String nameOf(Object event) {
        return event.getClass().getSimpleName();
    }

    /**
     * Writes one event on the topic of its event type and checks the bytes.
     *
     * @param event the event to write
     * @return the checked bytes, never {@code null}
     */
    private byte[] serialize(Object event) {
        byte[] bytes = serializer.serialize(topicOf(event), event);

        assertNotNull(bytes, "the publish side wrote no bytes for " + nameOf(event));
        return bytes;
    }

    /**
     * Writes one event and reads the bytes back as the record its event type names.
     *
     * @param event the event to send through both ends
     * @return the record the consume side built
     */
    private Object readBack(Object event) {
        Object arrived = deserializer.deserialize(topicOf(event), serialize(event));

        assertNotNull(arrived, "the consume side built nothing for " + nameOf(event));
        return arrived;
    }

    /**
     * Writes one event and reads it back through a consumer built for one event type.
     *
     * @param type  the record class the consumer builds
     * @param event the event to send through both ends
     * @param <T>   the record type
     * @return the record the consume side built
     */
    private <T> T roundTrip(Class<T> type, Object event) {
        T arrived = new JsonSchemaValidatingDeserializer<>(type)
                .deserialize(topicOf(event), serialize(event));

        assertNotNull(arrived, "the consume side built no " + type.getSimpleName());
        return arrived;
    }

    /**
     * The serialized form of one event, as a tree this test can read property by property.
     *
     * @param event the event to write
     * @return the tree of the checked bytes
     */
    private JsonNode wireFormOf(Object event) {
        return MAPPER.readTree(new String(serialize(event), StandardCharsets.UTF_8));
    }

    /**
     * Writes one event, changes its serialized form, and returns the changed bytes.
     *
     * <p>Each event record checks its own components, so a payload that breaks a schema is built by
     * changing the tree of a valid event.
     *
     * @param event  the event to write first
     * @param change the change to apply to the serialized tree
     * @return the changed bytes
     */
    private byte[] withMutation(Object event, Consumer<ObjectNode> change) {
        ObjectNode wire = (ObjectNode) MAPPER.readTree(
                new String(serialize(event), StandardCharsets.UTF_8));

        change.accept(wire);
        return MAPPER.writeValueAsBytes(wire);
    }

    /**
     * Asserts the consume side refuses one changed payload, and returns the failure.
     *
     * @param event  the event whose serialized form is changed
     * @param change the change that must be refused
     * @return the failure the consume side raised
     */
    private SerializationException refusalOf(Object event, Consumer<ObjectNode> change) {
        String topic = topicOf(event);
        byte[] payload = withMutation(event, change);

        return assertThrows(SerializationException.class,
                () -> deserializer.deserialize(topic, payload),
                "the consume side accepted a " + nameOf(event) + " payload that breaks its schema");
    }

    /**
     * Asserts the consume side refuses one hand-built payload, and returns the failure.
     *
     * @param payload the payload to send
     * @param topic   the topic to send it on
     * @return the failure the consume side raised
     */
    private SerializationException refusalOf(ObjectNode payload, String topic) {
        byte[] bytes = MAPPER.writeValueAsBytes(payload);

        return assertThrows(SerializationException.class,
                () -> deserializer.deserialize(topic, bytes),
                "the consume side accepted a hand-built payload that breaks its schema");
    }

    /**
     * Asserts one serialized event is a flat object of the expected size, carrying the five
     * envelope properties at the top level and no {@code envelope} key.
     *
     * @param event              the event to write
     * @param expectedProperties the top-level property count of its event type
     */
    private void assertWireShape(Object event, int expectedProperties) {
        String type = nameOf(event);
        JsonNode wire = wireFormOf(event);

        assertEquals(expectedProperties, wire.size(),
                type + " serialized a different number of top-level properties");
        assertFalse(wire.has("envelope"),
                type + " nested its envelope under an envelope key, and the wire form is flat");
        for (String property : ENVELOPE_PROPERTIES) {
            assertTrue(wire.has(property),
                    type + " serialized no top-level " + property + " property");
        }
        assertEquals(type, wire.get("eventType").stringValue(),
                type + " serialized another event type, so the record and its envelope disagree");
        assertEquals(ACCOUNT_ID, wire.get("aggregateId").stringValue(),
                type + " keys on a value other than the account identifier, and the Kafka message "
                        + "key is what keeps the events of one account in order");
    }

    /**
     * Asserts the event identifier and the publish time of one event travel as text.
     *
     * @param event the event to write
     */
    private void assertTextEnvelopeTimes(Object event) {
        String type = nameOf(event);
        JsonNode wire = wireFormOf(event);

        assertTrue(wire.get("eventId").isString(),
                "the event identifier of " + type + " stopped travelling as text");
        assertTrue(wire.get("occurredAt").isString(),
                "the publish time of " + type + " stopped travelling as text, so a consumer reads "
                        + "a numeric epoch");
        assertTrue(wire.get("occurredAt").stringValue().endsWith("Z"),
                "the publish time of " + type + " stopped arriving in Coordinated Universal Time");
    }

    /**
     * Asserts one spelling of the posting timestamp is refused.
     *
     * @param event    the posted transaction whose serialized form is changed
     * @param spelling the timestamp text that must be refused
     * @param described what that spelling is, for the failure message
     */
    private void assertPostedTimestampRefused(TransactionPosted event, String spelling,
            String described) {
        SerializationException failure = refusalOf(event, wire -> wire.put("postedAt", spelling));

        assertTrue(failure.getMessage().contains("/postedAt"),
                "the posting timestamp of TransactionPosted accepted " + described);
    }

    /**
     * Loads a private copy of the serde classes with one schema document hidden.
     *
     * <p>Classes under {@code com.carddemo} load here first, so the copy of the serde class this
     * loader defines reads resources through this loader. Every other class loads from the parent,
     * which keeps one Jackson and one validator in play.
     */
    private static final class SchemaHidingClassLoader extends URLClassLoader {

        /** The classpath resource this loader reports as absent. */
        private final String hidden;

        /**
         * Builds a loader that reads one location and reports one resource of it as absent.
         *
         * @param module the location holding the compiled classes and the schema documents
         * @param hidden the classpath resource to hide
         * @param parent the loader every other class comes from
         */
        SchemaHidingClassLoader(URL module, String hidden, ClassLoader parent) {
            super(new URL[] {module}, parent);
            this.hidden = hidden;
        }

        @Override
        public URL getResource(String name) {
            return hidden.equals(name) ? null : super.getResource(name);
        }

        @Override
        public InputStream getResourceAsStream(String name) {
            return hidden.equals(name) ? null : super.getResourceAsStream(name);
        }

        @Override
        protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
            synchronized (getClassLoadingLock(name)) {
                Class<?> loaded = findLoadedClass(name);

                if (loaded == null && name.startsWith("com.carddemo.")) {
                    try {
                        loaded = findClass(name);
                    } catch (ClassNotFoundException absentHere) {
                        loaded = null;
                    }
                }
                if (loaded == null) {
                    return super.loadClass(name, resolve);
                }
                if (resolve) {
                    resolveClass(loaded);
                }
                return loaded;
            }
        }
    }
}
