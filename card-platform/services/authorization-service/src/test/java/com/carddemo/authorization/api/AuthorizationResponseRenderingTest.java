package com.carddemo.authorization.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.carddemo.events.DeclineReason;
import com.carddemo.events.EventEnvelope;
import java.lang.reflect.RecordComponent;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Pins what {@link AuthorizationResponse} renders and what it withholds.
 *
 * <p>The rendering carries the decision and withholds one value. No card number, no amount, no
 * balance and no cardholder value reaches this record, and every description is one of the four
 * literals at {@code app/cbl/CBTRN02C.cbl:L385-L420}, so the transaction identifier, the verdict,
 * the reason code and the reason description all stay: the decision itself is what an operator
 * tracing a declined call most needs to read. The account identifier is withheld, as it is in every
 * rendering on this platform, because it is the key every event of an account carries and one log
 * line naming it beside a decision is a step toward correlating a cardholder.
 *
 * <p>The tests below assert the two halves of that argument. First, that the rendering carries what
 * it claims to and withholds what it claims to. Second, that the record still declares exactly the
 * five components the argument was made about, so a sixth component has to be weighed against a
 * failing test instead of joining the rendering silently.
 *
 * <p>Every test runs in memory. None starts an application context or contacts a broker.
 */
@DisplayName("the authorization response renders the decision and withholds the account")
class AuthorizationResponseRenderingTest {

    /** Row one of {@code app/data/ASCII/dailytran.txt}, columns 1 through 16. */
    private static final String TRANSACTION_ID = "0000000000000001";

    /** Row one of {@code app/data/ASCII/cardxref.txt}, the account identifier it resolves to. */
    private static final String ACCOUNT_ID = "00000000001";

    @Nested
    @DisplayName("The rendering carries the decision")
    class TheRenderingCarriesTheDecision {

        @Test
        @DisplayName("an approval names the transaction, the verdict and no account identifier")
        void anApprovalNamesTheVerdict() {
            String rendered = AuthorizationResponse.approve(TRANSACTION_ID, ACCOUNT_ID).toString();

            assertTrue(rendered.contains("transactionId=" + TRANSACTION_ID), rendered);
            // The value is compared with its component name attached. This transaction identifier
            // holds the account identifier as a substring, so a bare containment check would report
            // a leak that is only a coincidence of two fixture values.
            assertFalse(rendered.contains("accountId=" + ACCOUNT_ID),
                    "the account identifier reached a log line: " + rendered);
            assertTrue(rendered.contains("accountId=" + EventEnvelope.WITHHELD),
                    "the rendering names the component and withholds its value: " + rendered);
            assertTrue(rendered.contains("approved=true"), rendered);
            assertTrue(rendered.contains("declineReasonCode=null"),
                    "an approval carries no reason, and the rendering says so: " + rendered);
        }

        @Test
        @DisplayName("a decline names its reason code and the source description")
        void aDeclineNamesItsReason() {
            String rendered = AuthorizationResponse
                    .decline(TRANSACTION_ID, ACCOUNT_ID, DeclineReason.OVER_CREDIT_LIMIT)
                    .toString();

            assertTrue(rendered.contains("approved=false"), rendered);
            assertTrue(rendered.contains(DeclineReason.OVER_CREDIT_LIMIT.name()),
                    "the reason a caller was refused is the whole diagnostic: " + rendered);
            assertTrue(
                    rendered.contains(DeclineReason.OVER_CREDIT_LIMIT.description()),
                    "the description is a fixed literal of app/cbl/CBTRN02C.cbl and quotes no"
                            + " value that failed: " + rendered);
        }

        @Test
        @DisplayName("no description of any reason quotes a value")
        void noDescriptionQuotesAValue() {
            Set<String> descriptions = new LinkedHashSet<>();
            for (DeclineReason reason : DeclineReason.values()) {
                descriptions.add(reason.description());
            }

            assertEquals(DeclineReason.values().length, descriptions.size(),
                    "each reason carries its own text, so a rendering names the reason it means");
            for (String description : descriptions) {
                assertFalse(description.matches(".*\\d{5,}.*"),
                        "a description carrying a run of five or more digits would be quoting an"
                                + " identifier or an amount rather than naming a rule: "
                                + description);
            }
        }
    }

    @Nested
    @DisplayName("The record still declares the five components the decision covers")
    class TheRecordShape {

        @Test
        @DisplayName("exactly five components, in the order the decision was written about")
        void exactlyFiveComponents() {
            RecordComponent[] components = AuthorizationResponse.class.getRecordComponents();
            List<String> names = new java.util.ArrayList<>();
            for (RecordComponent component : components) {
                names.add(component.getName());
            }

            assertEquals(List.of("transactionId", "accountId", "approved", "declineReasonCode",
                    "declineReasonDescription"), names,
                    "a sixth component, or a renamed one, has to be weighed against the rendering"
                            + " decision rather than joining it silently");
        }

        @Test
        @DisplayName("no component names a card, an amount or a balance")
        void noComponentNamesASensitiveValue() {
            for (RecordComponent component : AuthorizationResponse.class.getRecordComponents()) {
                String name = component.getName().toLowerCase(java.util.Locale.ROOT);
                for (String forbidden : List.of("card", "pan", "amount", "balance", "cvv",
                        "limit")) {
                    assertFalse(name.contains(forbidden),
                            "component " + component.getName() + " names a value this rendering"
                                    + " would then disclose");
                }
            }
        }
    }
}
