package com.carddemo.account.messaging;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;

import com.carddemo.events.serde.EventJsonValidator;
import com.carddemo.events.serde.EventSchemas;
import com.carddemo.events.serde.EventWireBounds;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Asserts the account mutation event passes the one gate every event of this platform passes.
 *
 * <p>This service previously published its mutation event with a plain string serializer while the
 * shared schema-validating serializer governed only the five core events. The payload of an account
 * change was therefore never checked against the contract that describes it, so neither the closed
 * property set nor the size ceiling nor the governed-type list applied to it. The event is now
 * produced by the shared serializer and checked again by the shared validator on the way to the
 * broker.
 *
 * <p>The event exists because the authorization service keeps a credit projection current from it:
 * the credit limit, both cycle accumulators and the expiry date the decline rules read. Those are the
 * values a stale projection would authorize against, which is why the contract governing them is
 * worth enforcing rather than assuming.
 */
@DisplayName("AccountStateChanged, the account mutation contract")
class AccountStateChangedGateTest {

    /** The account this event belongs to, eleven digits with leading zeros. */
    private static final String ACCOUNT_ID = "00000000011";

    @Test
    @DisplayName("the event type is governed by the shared table")
    void theEventTypeIsGovernedByTheSharedTable() {
        assertEquals("AccountStateChanged", AccountStateChanged.EVENT_TYPE,
                "the event type changed, so the schema its name selects no longer describes it");
        assertEquals("schemas/account-state-changed-v1.json",
                EventSchemas.SCHEMA_RESOURCES.get(AccountStateChanged.EVENT_TYPE),
                "the shared table no longer governs this event, so it would leave this service "
                        + "unchecked");
    }

    @Test
    @DisplayName("the event serializes through the shared gate and passes it again on publish")
    void theEventPassesTheSharedGateTwice() {
        String json = accountUpdated().toValidatedJson();

        EventJsonValidator.shared().validate(json);

        assertTrue(json.contains("\"eventType\":\"AccountStateChanged\""),
                "the wire form does not name its event type: " + json);
        assertTrue(json.getBytes(StandardCharsets.UTF_8).length <= EventWireBounds.MAX_EVENT_BYTES,
                "the event exceeds the platform ceiling, so it would not fit the outbox row");
    }

    @Test
    @DisplayName("every monetary value travels as a decimal string at two fractional digits")
    void everyMonetaryValueTravelsAsADecimalString() {
        String json = accountUpdated().toValidatedJson();

        for (String property : new String[] {"creditLimit", "currentCycleCredit",
                "currentCycleDebit"}) {
            assertTrue(json.contains("\"" + property + "\":\""),
                    property + " travels as a JSON number, which returns binary floating point to a "
                            + "platform whose correctness rests on fixed-point arithmetic: " + json);
        }
        assertTrue(json.contains("\"creditLimit\":\"5000.00\""),
                "the credit limit lost its scale on the wire: " + json);
    }

    @Test
    @DisplayName("a cycle close reports both accumulators at zero, which the rules then read")
    void aCycleCloseReportsBothAccumulatorsAtZero() {
        AccountStateChanged closed = AccountStateChanged.of(ACCOUNT_ID,
                AccountStateChanged.ChangeKind.BILLING_CYCLE_CLOSED, new BigDecimal("5000.00"),
                BigDecimal.ZERO.setScale(2), BigDecimal.ZERO.setScale(2), "2024-12-31");

        String json = closed.toValidatedJson();
        EventJsonValidator.shared().validate(json);

        assertTrue(json.contains("\"currentCycleCredit\":\"0.00\""),
                "the cycle credit accumulator is not reported at zero: " + json);
        assertTrue(json.contains("\"currentCycleDebit\":\"0.00\""),
                "the cycle debit accumulator is not reported at zero: " + json);
    }

    @Test
    @DisplayName("the event carries no customer identity, no social security number and no card")
    void theEventCarriesNoCustomerIdentity() {
        String json = accountUpdated().toValidatedJson().toLowerCase();

        for (String forbidden : new String[] {"social", "ssn", "governmentissued", "dateofbirth",
                "eft", "phone", "address", "cardnumber", "firstname", "lastname", "fico"}) {
            assertFalse(json.contains(forbidden),
                    "the account mutation event names " + forbidden + ", which belongs to the "
                            + "customer record and has no place on this topic: " + json);
        }
    }

    @Test
    @DisplayName("the shared gate refuses an event whose account identifier is not eleven digits")
    void theSharedGateRefusesAMalformedIdentifier() {
        assertThrows(IllegalArgumentException.class,
                () -> AccountStateChanged.of("7", AccountStateChanged.ChangeKind.ACCOUNT_UPDATED,
                        new BigDecimal("5000.00"), new BigDecimal("0.00"), new BigDecimal("0.00"),
                        "2024-12-31"),
                "an account identifier of one digit was accepted, so an event could reach the "
                        + "partition of an account it does not name");
    }

    /** One account field update, the change the online update path performs. */
    private static AccountStateChanged accountUpdated() {
        return AccountStateChanged.of(ACCOUNT_ID, AccountStateChanged.ChangeKind.ACCOUNT_UPDATED,
                new BigDecimal("5000.00"), new BigDecimal("250.00"), new BigDecimal("-75.25"),
                "2024-12-31");
    }
}
