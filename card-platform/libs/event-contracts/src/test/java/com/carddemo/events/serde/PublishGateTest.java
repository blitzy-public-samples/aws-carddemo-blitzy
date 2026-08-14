package com.carddemo.events.serde;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.carddemo.events.DeclineReason;
import com.carddemo.events.EventEnvelope;
import com.carddemo.events.FraudFlagged;
import com.carddemo.events.TransactionAuthorized;
import com.carddemo.events.TransactionDeclined;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Holds the one pre-outbox boundary to what it promises: every check runs, and the text it returns is
 * the text it checked.
 *
 * <p>These cases exist because two produce-side gates existed and they did not agree. Four services
 * wrote their own payload text with a plain mapper, measured it against the schema document alone,
 * stored that text in an outbox row and relayed it unchanged, so a card number written into a
 * free-text property committed with the business state and reached a topic — where the consume side
 * refused it, leaving a transaction approved that could never post. A security review recorded that as
 * a producer-side gate bypass, and {@link PublishGate} is the single boundary that closes it.
 *
 * <p>Each test below names the check it holds, so a change that removes one of them fails here rather
 * than in a consumer.
 */
@DisplayName("the one publish-side gate every producer crosses")
class PublishGateTest {

    /** The account identifier every event in this suite belongs to. */
    private static final String ACCOUNT_ID = "00000000007";

    /** A transaction identifier at the sixteen characters {@code TRAN-ID PIC X(16)} holds. */
    private static final String TRANSACTION_ID = "0000000000683580";

    /** The masked card number every event in this suite carries. */
    private static final String MASKED_CARD_NUMBER = "************7065";

    /** Card token every card-bearing sample carries. Sixty-four lower-case hexadecimal characters. */
    private static final String CARD_TOKEN =
            "c41b7e6039fa25d81c0b94e7635af8021d4e9c78b6035f1ae284d70b9c3f6512";

    /** The twenty-six character capture timestamp shape the source records carry. */
    private static final String AUTHORIZED_AT = "2022-06-10 19:27:53.000000";

    /** The consume side, which reads back what the gate returned. */
    private final JsonSchemaValidatingDeserializer deserializer =
            new JsonSchemaValidatingDeserializer();

    @Test
    @DisplayName("the text returned is the text a consumer reads back as the same event")
    void theTextReturnedIsTheTextAConsumerReadsBackAsTheSameEvent() {
        TransactionAuthorized event = authorized("Purchase at Abshire-Lowe");

        String payload = PublishGate.checkedJsonOf(event);

        assertEquals(event, deserializer.deserialize(
                        EventContracts.defaultTopicFor(EventContracts.TRANSACTION_AUTHORIZED),
                        payload.getBytes(StandardCharsets.UTF_8)),
                "the row stores what the gate returned and the relay publishes those bytes, so text"
                        + " the consume side cannot read back is text no producer may store");
    }

    @Test
    @DisplayName("a card number in a free-text property is refused before a row can be stored")
    void aCardNumberInAFreeTextPropertyIsRefused() {
        IllegalArgumentException refused = assertThrows(IllegalArgumentException.class,
                () -> PublishGate.checkedJsonOf(authorized("refund to 4111111111111111")),
                "this is the exact bypass a security review found: the schema document accepts any"
                        + " text a description's length admits, so only the screens refuse a card"
                        + " number written into one");

        assertTrue(refused.getMessage().contains("description"), refused.getMessage());
        assertFalse(refused.getMessage().contains("4111"),
                "the refusal repeated the value it refused, which puts it in a log");
    }

    @Test
    @DisplayName("a government identifier in a free-text property is refused")
    void aGovernmentIdentifierInAFreeTextPropertyIsRefused() {
        for (String written : List.of("holder 020-97-3888", "holder 020973888")) {
            IllegalArgumentException refused = assertThrows(IllegalArgumentException.class,
                    () -> PublishGate.checkedJsonOf(authorized(written)),
                    "a description of " + written + " reached an outbox row");

            assertTrue(refused.getMessage().contains("description"), refused.getMessage());
        }
    }

    @Test
    @DisplayName("a bare card code in a free-text property is refused")
    void aBareCardCodeInAFreeTextPropertyIsRefused() {
        IllegalArgumentException refused = assertThrows(IllegalArgumentException.class,
                () -> PublishGate.checkedJsonOf(authorized("123")));

        assertTrue(refused.getMessage().contains("description"), refused.getMessage());
    }

    @Test
    @DisplayName("a contract version no producer may write is refused")
    void aContractVersionNoProducerMayWriteIsRefused() {
        TransactionDeclined retained = TransactionDeclined.of(ACCOUNT_ID, TRANSACTION_ID,
                DeclineReason.OVER_CREDIT_LIMIT, new BigDecimal("50.47"), MASKED_CARD_NUMBER);

        IllegalArgumentException refused = assertThrows(IllegalArgumentException.class,
                () -> PublishGate.checkedJsonOf(retained),
                "the retained version-one declined contract has no producer left, and the posture"
                        + " gate is what keeps it that way");

        assertTrue(refused.getMessage().contains("posture")
                        || refused.getMessage().contains("schemaVersion"),
                refused.getMessage());
    }

    @Test
    @DisplayName("the account-less declined contract passes the gate, because one outcome writes it")
    void theAccountLessDeclinedContractPassesTheGate() {
        TransactionDeclined accountLess = TransactionDeclined.ofUnresolvedAccount(TRANSACTION_ID,
                new BigDecimal("50.47"), MASKED_CARD_NUMBER);

        String payload = PublishGate.checkedJsonOf(accountLess);

        assertTrue(payload.contains("\"aggregateId\":\"" + TRANSACTION_ID + "\""),
                "the checked text keys on the identifier the authorization service minted: "
                        + payload);
        assertFalse(payload.contains("\"accountId\""),
                "and it names no account, because the read that would have resolved one failed: "
                        + payload);
    }

    @Test
    @DisplayName("a published contract version passes the posture gate")
    void aPublishedContractVersionPassesThePostureGate() {
        TransactionDeclined published = declined(DeclineReason.OVER_CREDIT_LIMIT);

        assertDoesNotThrow(() -> PublishGate.checkedJsonOf(published),
                "the contract every decline publishes under was refused, which would leave the"
                        + " service unable to record an outcome at all");
    }

    @Test
    @DisplayName("a record naming no registered event type is refused by class and not by payload")
    void aRecordNamingNoRegisteredEventTypeIsRefused() {
        record NotAnEvent(UUID eventId, String eventType, int schemaVersion, Instant occurredAt,
                String aggregateId) {
        }

        IllegalArgumentException refused = assertThrows(IllegalArgumentException.class,
                () -> PublishGate.checkedJsonOf(new NotAnEvent(UUID.randomUUID(),
                        EventContracts.TRANSACTION_AUTHORIZED, EventEnvelope.SCHEMA_VERSION,
                        Instant.parse("2022-06-10T19:27:53.412Z"), ACCOUNT_ID)),
                "an arbitrary record whose payload names a supported event type was accepted, so the"
                        + " event type came from the JSON rather than from the class");

        assertTrue(refused.getMessage().contains("registered event type"), refused.getMessage());
    }

    @Test
    @DisplayName("every event this module publishes crosses the gate unchanged")
    void everyEventThisModulePublishesCrossesTheGateUnchanged() {
        for (Record event : List.of(
                authorized("Purchase at Abshire-Lowe"),
                declined(DeclineReason.ACCOUNT_NOT_FOUND),
                FraudFlagged.of(ACCOUNT_ID, TRANSACTION_ID, 82, List.of("VELOCITY"),
                        Instant.parse("2022-06-10T19:27:53.412Z")))) {

            String payload = assertDoesNotThrow(() -> PublishGate.checkedJsonOf(event),
                    "a legitimate " + event.getClass().getSimpleName() + " was refused");

            assertTrue(EventContracts.publishViolationsOf(
                            event.getClass().getSimpleName(), payload).isEmpty(),
                    "the two producer paths disagree about the same payload, and a relay re-checking"
                            + " a stored row would refuse to publish what the writer stored");
        }
    }

    /**
     * One declined event at the contract version every decline publishes under.
     *
     * @param reason the reject reason the decline carries
     * @return the event
     */
    private static TransactionDeclined declined(DeclineReason reason) {
        return TransactionDeclined.withTransactionDetail(ACCOUNT_ID, TRANSACTION_ID, reason, "01",
                "0001", "POS TERM", "Purchase at Abshire-Lowe", new BigDecimal("50.47"),
                "800000000", "Abshire-Lowe", "North Enoshaven", "72112", MASKED_CARD_NUMBER,
                AUTHORIZED_AT);
    }

    /**
     * One authorized event carrying the supplied description and legitimate values everywhere else.
     *
     * @param description the narrative text under test
     * @return the event
     */
    private static TransactionAuthorized authorized(String description) {
        return TransactionAuthorized.of(ACCOUNT_ID, TRANSACTION_ID, "01", "0001", "POS TERM",
                description, new BigDecimal("50.47"), "800000000", "Abshire-Lowe",
                "North Enoshaven", "72112", MASKED_CARD_NUMBER, CARD_TOKEN, AUTHORIZED_AT);
    }
}
