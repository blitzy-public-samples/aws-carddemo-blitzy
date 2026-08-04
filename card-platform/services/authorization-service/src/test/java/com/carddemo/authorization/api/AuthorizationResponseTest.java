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

    /** The one reject code that names no account. */
    private static final DeclineReason UNRESOLVED_CARD = DeclineReason.INVALID_CARD_NUMBER;

    /** The three reject codes that follow a resolved account. */
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

    /** Asserts each of the three declines that follow a resolved account carries that account. */
    @Test
    void eachDeclineThatFollowsAResolvedAccountCarriesThatAccount() {
        for (DeclineReason reason : RESOLVED_ACCOUNT_REASONS) {
            AuthorizationResponse response =
                    AuthorizationResponse.decline(TRANSACTION_ID, ACCOUNT_ID, reason);

            assertFalse(response.approved(), "the response reports a decline");
            assertEquals(ACCOUNT_ID, response.accountId(),
                    reason.code() + " follows the account read, so it names the account");
            assertEquals(reason, response.declineReasonCode(), "the reject code travels unchanged");
            assertEquals(reason.description(), response.declineReasonDescription(),
                    "the text comes from the reject code and is not restated");
        }
    }

    /** Asserts each of the three declines that follow a resolved account requires that account. */
    @Test
    void eachDeclineThatFollowsAResolvedAccountRequiresThatAccount() {
        for (DeclineReason reason : RESOLVED_ACCOUNT_REASONS) {
            IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class,
                    () -> AuthorizationResponse.decline(TRANSACTION_ID, null, reason),
                    reason.code() + " is reached only after an account resolved");

            assertTrue(thrown.getMessage().contains(reason.code()),
                    "the rejection names the reject code that requires an account");
        }
    }

    /**
     * Asserts the unresolved-card factory is the only path to a response naming no account, and that
     * it fixes the reject code at {@code 0100}.
     */
    @Test
    void theUnresolvedCardFactoryIsTheOnlyPathToAResponseNamingNoAccount() {
        AuthorizationResponse response =
                AuthorizationResponse.declineUnresolvedCard(TRANSACTION_ID);

        assertFalse(response.approved(), "the response reports a decline");
        assertNull(response.accountId(), "no cross-reference row resolved an account");
        assertEquals(UNRESOLVED_CARD, response.declineReasonCode(),
                "the factory fixes the reject code the source assigns");
        assertEquals(UNRESOLVED_CARD.description(), response.declineReasonDescription(),
                "the text comes from the reject code");
    }

    /**
     * Asserts the general decline factory refuses reject code {@code 0100}, so one outcome has one
     * factory and a caller cannot pair that code with an invented account.
     */
    @Test
    void theGeneralDeclineFactoryRefusesTheUnresolvedCardCode() {
        IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class,
                () -> AuthorizationResponse.decline(TRANSACTION_ID, ACCOUNT_ID, UNRESOLVED_CARD),
                "the reject code that names no account has its own factory");

        assertTrue(thrown.getMessage().contains(UNRESOLVED_CARD.code()),
                "the rejection names the reject code that was misrouted");
        assertTrue(thrown.getMessage().contains("declineUnresolvedCard"),
                "the rejection names the factory that builds the outcome");
    }

    /**
     * Asserts a response carrying reject code {@code 0100} and an account identifier is refused.
     *
     * <p>The cross-reference read failed, so an identifier arriving beside that code was not read
     * from a row. Accepting it would let a substitute reach an event as though it had resolved.
     */
    @Test
    void aResponseCarryingTheUnresolvedCardCodeAndAnAccountIsRefused() {
        IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class,
                () -> new AuthorizationResponse(TRANSACTION_ID, ACCOUNT_ID, false, UNRESOLVED_CARD,
                        UNRESOLVED_CARD.description()),
                "reject code 0100 names no account");

        assertTrue(thrown.getMessage().contains("accountId"),
                "the rejection names the component that must be absent");
        assertFalse(thrown.getMessage().contains(ACCOUNT_ID),
                "the rejection carries no account identifier");
    }

    /**
     * Asserts the two factories cover every reject code the platform defines, and cover none twice.
     *
     * <p>A fifth reject code added to the enum without a factory to build it fails here.
     */
    @Test
    void theTwoFactoriesCoverEveryRejectCodeAndCoverNoneTwice() {
        EnumSet<DeclineReason> covered = EnumSet.copyOf(RESOLVED_ACCOUNT_REASONS);

        assertFalse(covered.contains(UNRESOLVED_CARD),
                "the unresolved-card code sits outside the three the general factory builds");
        covered.add(UNRESOLVED_CARD);
        assertEquals(EnumSet.allOf(DeclineReason.class), covered,
                "the two factories together build every reject code app/cbl/CBTRN02C.cbl assigns");
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
