package com.carddemo.authorization.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.carddemo.events.DeclineReason;
import com.carddemo.events.EventEnvelope;
import com.carddemo.events.TransactionDeclined;
import java.math.BigDecimal;
import java.util.EnumSet;
import java.util.List;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

/**
 * Identity and outcome tests for {@link AuthorizationResponse}.
 *
 * <p>One account identifier reaches the response, the event published beside it and the Kafka
 * message key. {@code app/cbl/CBTRN02C.cbl:L390-L394} reads the cross-reference row and takes the
 * identifier from it, and every outcome except reject code {@code 0100} follows that read. Reject
 * code {@code 0100} is assigned at {@code app/cbl/CBTRN02C.cbl:L385} when the read took its
 * {@code INVALID KEY} branch, so no account exists to name.
 *
 * <p>The tests below hold that invariant from both directions, and one of them builds a real
 * {@link TransactionDeclined} to prove a response carrying an identifier can always produce the
 * event its contract requires.
 */
final class AuthorizationResponseTest {

    /** Identifier of the transaction every response below applies to. */
    private static final String TRANSACTION_ID = "0000000000683580";

    /** The eleven-digit account identifier a resolved card yields. */
    private static final String ACCOUNT_ID = "00000000077";

    /** The one reject code assigned before the cross-reference read resolves an account. */
    private static final DeclineReason UNRESOLVED_CARD = DeclineReason.INVALID_CARD_NUMBER;

    /** The three reject codes assigned after that read resolved one. */
    private static final List<DeclineReason> RESOLVED_ACCOUNT_REASONS =
            List.of(DeclineReason.ACCOUNT_NOT_FOUND, DeclineReason.OVER_CREDIT_LIMIT,
                    DeclineReason.ACCOUNT_EXPIRED);

    /** Writes a response so a test can read the property names it puts on the wire. */
    private static final JsonMapper MAPPER = JsonMapper.builder().build();

    /** Asserts an approval carries the account it authorized against and no decline reason. */
    @Test
    void anApprovalCarriesTheAccountItAuthorizedAgainst() {
        AuthorizationResponse response = AuthorizationResponse.approve(TRANSACTION_ID, ACCOUNT_ID);

        assertTrue(response.approved(), "the response reports an approval");
        assertEquals(ACCOUNT_ID, response.accountId(), "the approval names the resolved account");
        assertNull(response.declineReasonCode(), "an approval carries no reject code");
        assertNull(response.declineReasonDescription(), "an approval carries no reject text");
    }

    /**
     * Asserts an approval without a resolved account is refused.
     *
     * <p>An approval passes the account read, the credit test and the expiry test, and all three
     * read an account that resolved. An approval naming none cannot occur, and accepting one would
     * put a decision in the outbox with no identifier to key its event on.
     */
    @Test
    void anApprovalWithoutAResolvedAccountIsRefused() {
        IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class,
                () -> AuthorizationResponse.approve(TRANSACTION_ID, null),
                "an approval names the account it authorized against");

        assertTrue(thrown.getMessage().contains("accountId"),
                "the rejection names the component that is absent");
    }

    /** Asserts a blank account identifier is refused on an approval, as an absent one is. */
    @Test
    void aBlankAccountIdentifierIsRefusedOnAnApproval() {
        assertThrows(IllegalArgumentException.class,
                () -> AuthorizationResponse.approve(TRANSACTION_ID, "   "),
                "spaces name no account");
    }

    /** Asserts each of the four declines carries the account its decision applies to. */
    @Test
    void eachDeclineCarriesTheAccountItsDecisionAppliesTo() {
        for (DeclineReason reason : DeclineReason.values()) {
            AuthorizationResponse response =
                    AuthorizationResponse.decline(TRANSACTION_ID, ACCOUNT_ID, reason);

            assertFalse(response.approved(), "the response reports a decline");
            assertEquals(ACCOUNT_ID, response.accountId(),
                    reason.code() + " names the account its decision applies to");
            assertEquals(reason, response.declineReasonCode(), "the reject code travels unchanged");
            assertEquals(reason.description(), response.declineReasonDescription(),
                    "the text comes from the reject code and is not restated");
        }
    }

    /** Asserts each of the four declines requires the account its decision applies to. */
    @Test
    void eachDeclineRequiresTheAccountItsDecisionAppliesTo() {
        for (DeclineReason reason : DeclineReason.values()) {
            IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class,
                    () -> AuthorizationResponse.decline(TRANSACTION_ID, null, reason),
                    reason.code() + " names the account its decision applies to");

            assertTrue(thrown.getMessage().contains(reason.code()),
                    "the rejection names the reject code that requires an account");
        }
    }

    /**
     * Asserts reject code {@code 0100} is shaped by the one decline factory like any other code.
     *
     * <p>This record is a shape rather than a rule: one factory builds every decline and none of them
     * decides which outcomes a service may reach. {@code domain/AuthorizationService} refuses a card
     * that resolves no cross-reference row instead of deciding it, so no published response carries
     * this code. The factory is still held to the same invariants for it, because a response of this
     * shape must name an account whatever code it carries, and no factory here invents one.
     */
    @Test
    void theUnresolvedCardCodeIsShapedLikeAnyOtherDecline() {
        AuthorizationResponse response =
                AuthorizationResponse.decline(TRANSACTION_ID, ACCOUNT_ID, UNRESOLVED_CARD);

        assertFalse(response.approved(), "the response reports a decline");
        assertEquals(ACCOUNT_ID, response.accountId(),
                "reject code 0100 names the account its decision applied to");
        assertEquals(UNRESOLVED_CARD, response.declineReasonCode(),
                "the reject code travels unchanged");
        assertEquals(UNRESOLVED_CARD.description(), response.declineReasonDescription(),
                "the text comes from the reject code");
    }

    /**
     * Asserts a response carrying reject code {@code 0100} and no account identifier is refused.
     *
     * <p>Every decided outcome names the account it applies to, and the event contract requires those
     * eleven digits in {@code accountId} and in the message key. A response reaching the outbox without
     * one would fail that contract after the decision had already been taken, and the ledger would
     * have no account to write its reject row against.
     */
    @Test
    void aResponseCarryingTheUnresolvedCardCodeAndNoAccountIsRefused() {
        IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class,
                () -> new AuthorizationResponse(TRANSACTION_ID, null, false, UNRESOLVED_CARD,
                        UNRESOLVED_CARD.description()),
                "reject code 0100 names the account its decision applies to");

        assertTrue(thrown.getMessage().contains("accountId"),
                "the rejection names the component that is absent");
    }

    /**
     * Asserts the one decline factory covers every reject code the platform defines.
     *
     * <p>A fifth reject code added to the enum reaches the same factory, so the assertion is that one
     * shape covers the set rather than that two factories partition it.
     */
    @Test
    void theOneDeclineFactoryCoversEveryRejectCode() {
        EnumSet<DeclineReason> covered = EnumSet.noneOf(DeclineReason.class);

        for (DeclineReason reason : DeclineReason.values()) {
            AuthorizationResponse response =
                    AuthorizationResponse.decline(TRANSACTION_ID, ACCOUNT_ID, reason);

            assertEquals(reason, response.declineReasonCode(),
                    "the factory changed the reject code it was given");
            covered.add(response.declineReasonCode());
        }

        assertEquals(EnumSet.allOf(DeclineReason.class), covered,
                "one factory builds every reject code app/cbl/CBTRN02C.cbl assigns");
        assertTrue(covered.contains(UNRESOLVED_CARD),
                "reject code 0100 stopped reaching a caller through that factory");
        assertEquals(DeclineReason.values().length, RESOLVED_ACCOUNT_REASONS.size() + 1,
                "the three reasons following a resolved account and the one preceding it stopped "
                        + "accounting for every reject code");
    }

    /**
     * Asserts a declined response carrying an account identifier can produce the event its contract
     * requires, using the same identifier as the message key.
     */
    @Test
    void aDeclinedResponseCarryingAnAccountProducesItsEvent() {
        AuthorizationResponse response = AuthorizationResponse.decline(TRANSACTION_ID, ACCOUNT_ID,
                DeclineReason.OVER_CREDIT_LIMIT);

        TransactionDeclined event = TransactionDeclined.of(
                EventEnvelope.of(TransactionDeclined.EVENT_TYPE, response.accountId()),
                response.transactionId(), response.declineReasonCode(),
                new BigDecimal("504.77"), "************7065");

        assertEquals(response.accountId(), event.envelope().aggregateId(),
                "the response identifier becomes the message key");
        assertEquals(response.declineReasonCode(), event.declineReasonCode(),
                "the response and the event name the reject code with one property name");
    }

    /**
     * Asserts the written response names the reject code with the property name the event uses, so a
     * client reading both reads one name.
     */
    @Test
    void theWrittenResponseNamesTheRejectCodeWithTheEventPropertyName() {
        String written = MAPPER.writeValueAsString(
                AuthorizationResponse.decline(TRANSACTION_ID, ACCOUNT_ID,
                        DeclineReason.ACCOUNT_EXPIRED));

        assertTrue(written.contains("\"declineReasonCode\""),
                "the response names the reject code as the event names it");
        assertFalse(written.contains("\"declineReason\":"),
                "the earlier property name reaches no response body");
        assertTrue(written.contains("\"0103\""),
                "the reject code travels as four zero-padded characters");
    }
}
