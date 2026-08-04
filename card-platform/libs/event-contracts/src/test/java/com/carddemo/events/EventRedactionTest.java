package com.carddemo.events;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Proves that no event record of this module discloses an account identifier, a monetary value or a
 * card number through the text it renders.
 *
 * <p>A record renders every component unless it overrides the method the compiler generates. Each of
 * the seven records here overrides it, and these tests fail when an override is removed or when a
 * component is added without being withheld.
 *
 * <p>Every value below is a marker chosen so that a leak is unmistakable. The account identifier
 * {@value #ACCOUNT_ID} and the amount {@value #AMOUNT} appear in no correct rendering, so a test
 * that finds either has found a leak.
 */
class EventRedactionTest {

    /** Account identifier every fixture carries, and a value no rendering may disclose. */
    private static final String ACCOUNT_ID = "00000000017";

    /** Transaction identifier every fixture carries. Sixteen characters, and disclosed on purpose. */
    private static final String TRANSACTION_ID = "0000000000000042";

    /** Monetary value every fixture carries, and a value no rendering may disclose. */
    private static final String AMOUNT = "1234.56";

    /** Balance the posted event carries, and a value no rendering may disclose. */
    private static final String BALANCE = "9876.54";

    /** Masked card number every fixture carries. Twelve mask characters then four digits. */
    private static final String MASKED_CARD_NUMBER = "************4321";

    /**
     * Event identifier every fixture carries, fixed so a rendering stays comparable. The value
     * holds none of the markers above, so it cannot collide with an assertion below.
     */
    private static final UUID EVENT_ID = UUID.fromString("0e5f7a6c-9b1d-4c8e-a7f0-5d2b6e9c1a3f");

    /** Moment every fixture carries, fixed so a rendering stays comparable. */
    private static final Instant WHEN = Instant.parse("2026-08-03T18:31:53.613Z");

    /** Timestamp text the transaction records carry, twenty-six characters. */
    private static final String ORIGIN_TIMESTAMP = "2026-08-03 18:31:53.613000";

    /** Timestamp text the posted record carries, shaped by the source's own routine. */
    private static final String POSTED_TIMESTAMP = "2026-08-03-18.31.53.610000";

    /**
     * Builds one instance of every record this module declares, each carrying the marker values.
     *
     * @return one instance per record type
     */
    private static List<Object> allEventRecords() {
        EventEnvelope envelope =
                new EventEnvelope(EVENT_ID, "TransactionAuthorized", 1, WHEN, ACCOUNT_ID);
        return List.of(
                envelope,
                TransactionAuthorized.of(ACCOUNT_ID, TRANSACTION_ID, "01", "0001", "POS",
                        "a description", new BigDecimal(AMOUNT), "000000123", "a merchant",
                        "a city", "0000012345", MASKED_CARD_NUMBER, ORIGIN_TIMESTAMP),
                TransactionDeclined.of(ACCOUNT_ID, TRANSACTION_ID,
                        DeclineReason.OVER_CREDIT_LIMIT, new BigDecimal(AMOUNT),
                        MASKED_CARD_NUMBER),
                TransactionPosted.forAccount(ACCOUNT_ID, TRANSACTION_ID, new BigDecimal(BALANCE),
                        POSTED_TIMESTAMP, new BigDecimal(AMOUNT), MASKED_CARD_NUMBER),
                FraudFlagged.of(ACCOUNT_ID, TRANSACTION_ID, 87,
                        List.of("VELOCITY", "AMOUNT_ANOMALY"), WHEN),
                FraudCleared.of(TRANSACTION_ID, ACCOUNT_ID, WHEN));
    }

    @ParameterizedTest
    @MethodSource("allEventRecords")
    @DisplayName("no event record renders an account identifier, an amount or a card number")
    void rendersNoSensitiveValue(Object event) {
        String rendered = event.toString();

        assertThat(rendered)
                .as("rendering of %s must not disclose the account identifier",
                        event.getClass().getSimpleName())
                .doesNotContain(ACCOUNT_ID);
        assertThat(rendered)
                .as("rendering of %s must not disclose a monetary value",
                        event.getClass().getSimpleName())
                .doesNotContain(AMOUNT)
                .doesNotContain(BALANCE);
        assertThat(rendered)
                .as("rendering of %s must not disclose a card number in any form",
                        event.getClass().getSimpleName())
                .doesNotContain(MASKED_CARD_NUMBER)
                .doesNotContain("4321");
    }

    @ParameterizedTest
    @MethodSource("allEventRecords")
    @DisplayName("every event record overrides the generated rendering")
    void overridesGeneratedRendering(Object event) {
        String rendered = event.toString();

        assertThat(rendered)
                .as("%s must render its own text and not the default object form",
                        event.getClass().getSimpleName())
                .startsWith(event.getClass().getSimpleName() + "[")
                .contains(EventEnvelope.WITHHELD);
    }

    @Test
    @DisplayName("a rendering keeps the identifiers a reader needs to correlate an event")
    void keepsCorrelationIdentifiers() {
        EventEnvelope envelope =
                new EventEnvelope(EVENT_ID, "FraudCleared", 1, WHEN, ACCOUNT_ID);

        assertThat(envelope.toString())
                .contains(envelope.eventId().toString())
                .contains("FraudCleared")
                .contains(WHEN.toString());

        assertThat(FraudCleared.of(TRANSACTION_ID, ACCOUNT_ID, WHEN).toString())
                .contains(TRANSACTION_ID);
    }

    @Test
    @DisplayName("a flagged event reports how many rules fired without naming one")
    void reportsRuleCountWithoutNames() {
        FraudFlagged flagged = FraudFlagged.of(ACCOUNT_ID, TRANSACTION_ID, 87,
                List.of("VELOCITY", "AMOUNT_ANOMALY"), WHEN);

        assertThat(flagged.toString())
                .contains("2 entries")
                .doesNotContain("VELOCITY")
                .doesNotContain("AMOUNT_ANOMALY");
    }

    @Test
    @DisplayName("a refused amount is described by precision and scale, never by its value")
    void aRefusedAmountIsNotEchoed() {
        BigDecimal tooWide = new BigDecimal("1234567890.12");

        IllegalArgumentException refused = assertThrows(IllegalArgumentException.class,
                () -> TransactionAuthorized.of(ACCOUNT_ID, TRANSACTION_ID, "01", "0001", "POS TERM",
                        "Purchase at Abshire-Lowe", tooWide, "800000000", "Abshire-Lowe",
                        "North Enoshaven", "72112", MASKED_CARD_NUMBER, ORIGIN_TIMESTAMP));

        assertFalse(refused.getMessage().contains("1234567890.12"),
                "the refusal echoed the rejected amount: " + refused.getMessage());
        assertTrue(refused.getMessage().contains("precision"),
                "the refusal does not describe the shape it refused: " + refused.getMessage());
    }

    @Test
    @DisplayName("a refused balance is described by precision and scale, never by its value")
    void aRefusedBalanceIsNotEchoed() {
        BigDecimal tooWide = new BigDecimal("12345678901.12");

        IllegalArgumentException refused = assertThrows(IllegalArgumentException.class,
                () -> TransactionPosted.of(EventEnvelope.of("TransactionPosted", ACCOUNT_ID),
                        TRANSACTION_ID, tooWide, POSTED_TIMESTAMP, new BigDecimal(AMOUNT),
                        MASKED_CARD_NUMBER));

        assertFalse(refused.getMessage().contains("12345678901.12"),
                "the refusal echoed the rejected balance: " + refused.getMessage());
        assertTrue(refused.getMessage().contains("precision"),
                "the refusal does not describe the shape it refused: " + refused.getMessage());
    }

    @Test
    @DisplayName("a refused timestamp and a refused merchant identifier are described by length")
    void aRefusedTextComponentIsNotEchoed() {
        IllegalArgumentException refusedTimestamp = assertThrows(IllegalArgumentException.class,
                () -> TransactionPosted.of(EventEnvelope.of("TransactionPosted", ACCOUNT_ID),
                        TRANSACTION_ID, new BigDecimal(BALANCE), ORIGIN_TIMESTAMP,
                        new BigDecimal(AMOUNT), MASKED_CARD_NUMBER));

        assertFalse(refusedTimestamp.getMessage().contains(ORIGIN_TIMESTAMP),
                "the refusal echoed the rejected timestamp: " + refusedTimestamp.getMessage());
        assertTrue(refusedTimestamp.getMessage().contains("characters"),
                "the refusal does not describe the value by length: "
                        + refusedTimestamp.getMessage());

        IllegalArgumentException refusedMerchant = assertThrows(IllegalArgumentException.class,
                () -> TransactionAuthorized.of(ACCOUNT_ID, TRANSACTION_ID, "01", "0001", "POS TERM",
                        "Purchase at Abshire-Lowe", new BigDecimal(AMOUNT), "80000000A",
                        "Abshire-Lowe", "North Enoshaven", "72112", MASKED_CARD_NUMBER,
                        ORIGIN_TIMESTAMP));

        assertFalse(refusedMerchant.getMessage().contains("80000000A"),
                "the refusal echoed the rejected merchant identifier: "
                        + refusedMerchant.getMessage());
        assertTrue(refusedMerchant.getMessage().contains("characters"),
                "the refusal does not describe the value by length: "
                        + refusedMerchant.getMessage());
    }

    @Test
    @DisplayName("the dead-letter envelope carries no exception message and no payload")
    void theDeadLetterEnvelopeCarriesNoFailureText() {
        DeadLetterEnvelope envelope = DeadLetterEnvelope.fromFailure(ACCOUNT_ID, "0999",
                new IllegalStateException("card 0500024453765740 amount 50.47"), "SCHEMA",
                "validation refused the record\r\ninjected line", "transaction.authorized", 1, 42L,
                null, null, 3);

        assertFalse(envelope.message().contains("\r"),
                "a carriage return reached the dead-letter message: " + envelope.message());
        assertFalse(envelope.message().contains("\n"),
                "a line break reached the dead-letter message: " + envelope.message());
        assertEquals("IllegalS", envelope.culprit(),
                "the culprit was not cut to the width the source field holds");
        assertFalse(envelope.toString().contains("0500024453765740"),
                "the rendering carries a card number from the failure: " + envelope);
        assertFalse(envelope.toString().contains("50.47"),
                "the rendering carries an amount from the failure: " + envelope);
        assertTrue(envelope.truncatedComponents().contains("culprit"),
                "the record does not report that it shortened the culprit");
    }
}
