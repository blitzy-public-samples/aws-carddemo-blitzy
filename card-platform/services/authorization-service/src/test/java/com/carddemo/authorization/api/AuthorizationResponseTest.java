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
 * <p>So there are two decline shapes, not one, and the tests below hold each of them from both
 * directions: the three codes following the read must name an account, and reject code {@code 0100}
 * must not. Two of them build a real {@link TransactionDeclined} to prove each response shape can
 * produce the event its contract requires — version 3 for the three, and version 2, the one declined
 * document declaring no {@code accountId} property, for {@code 0100}.
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

    /** Asserts each decline following a resolved account carries the account it applies to. */
    @Test
    void eachDeclineFollowingAResolvedAccountCarriesIt() {
        for (DeclineReason reason : RESOLVED_ACCOUNT_REASONS) {
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

    /** Asserts each decline following a resolved account requires it. */
    @Test
    void eachDeclineFollowingAResolvedAccountRequiresIt() {
        for (DeclineReason reason : RESOLVED_ACCOUNT_REASONS) {
            IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class,
                    () -> AuthorizationResponse.decline(TRANSACTION_ID, null, reason),
                    reason.code() + " names the account its decision applies to");

            assertTrue(thrown.getMessage().contains(reason.code()),
                    "the rejection names the reject code that requires an account");
        }
    }

    /**
     * Asserts reject code {@code 0100} has its own factory, and that the outcome it builds names no
     * account.
     *
     * <p>The code is assigned inside the {@code INVALID KEY} branch of the cross-reference read at
     * {@code app/cbl/CBTRN02C.cbl:L385}, so the read that would have resolved an account is the read
     * that failed. There is nothing to name, and the alternative — naming whatever account the request
     * declared — is what a security review objected to: this service holds no row tying that value to
     * the card, so a caller could attach a decision to an account it had merely typed.
     *
     * <p>Two factories therefore exist rather than one with a nullable argument, so neither shape can
     * be built by accident. Everything else about the body is identical to the other three codes.
     */
    @Test
    void theUnresolvedCardCodeIsBuiltByItsOwnFactoryAndNamesNoAccount() {
        AuthorizationResponse response =
                AuthorizationResponse.declineUnresolvedCard(TRANSACTION_ID);

        assertFalse(response.approved(), "the response reports a decline");
        assertNull(response.accountId(),
                "reject code 0100 resolved no account, so it names none");
        assertEquals(TRANSACTION_ID, response.transactionId(),
                "and it names the identifier this service minted for the decision");
        assertEquals(UNRESOLVED_CARD, response.declineReasonCode(),
                "the reject code app/cbl/CBTRN02C.cbl:L385 assigns");
        assertEquals(UNRESOLVED_CARD.description(), response.declineReasonDescription(),
                "the text comes from the reject code");
    }

    /**
     * Asserts a response carrying reject code {@code 0100} and an account identifier is refused, and
     * that the general decline factory refuses the code outright.
     *
     * <p>This is the invariant in its second direction, and it is the one that keeps the security
     * finding closed. The three codes assigned after the cross-reference read require an account;
     * {@code 0100} is assigned by that read failing and so must carry none. Enforcing only the first
     * direction would leave the account a caller declared able to reach a {@code 0100} decision by way
     * of the general factory, which is exactly the path that was withdrawn.
     */
    @Test
    void aResponseCarryingTheUnresolvedCardCodeAndAnAccountIsRefused() {
        IllegalArgumentException carried = assertThrows(IllegalArgumentException.class,
                () -> new AuthorizationResponse(TRANSACTION_ID, ACCOUNT_ID, false, UNRESOLVED_CARD,
                        UNRESOLVED_CARD.description()),
                "reject code 0100 resolved no account, so it may name none");

        assertTrue(carried.getMessage().contains("accountId"),
                "the rejection names the component that must be absent");

        IllegalArgumentException viaFactory = assertThrows(IllegalArgumentException.class,
                () -> AuthorizationResponse.decline(TRANSACTION_ID, ACCOUNT_ID, UNRESOLVED_CARD),
                "the general factory builds the three codes that resolved an account");

        assertTrue(viaFactory.getMessage().contains(UNRESOLVED_CARD.code()),
                "the rejection names the reject code that has its own factory: "
                        + viaFactory.getMessage());
    }

    /**
     * Asserts the two decline factories between them cover every reject code the platform defines,
     * and that the split follows one predicate rather than a hand-kept list.
     *
     * <p>{@code DeclineReason.resolvesAccount()} decides which factory a code reaches, so a fifth
     * reject code added to the enum is routed by the predicate it declares rather than by an edit
     * here. The partition is what the assertions measure: neither factory is reachable for a code the
     * other owns, and the union is the whole enum.
     */
    @Test
    void theTwoDeclineFactoriesCoverEveryRejectCode() {
        EnumSet<DeclineReason> covered = EnumSet.noneOf(DeclineReason.class);

        for (DeclineReason reason : DeclineReason.values()) {
            AuthorizationResponse response = reason.resolvesAccount()
                    ? AuthorizationResponse.decline(TRANSACTION_ID, ACCOUNT_ID, reason)
                    : AuthorizationResponse.declineUnresolvedCard(TRANSACTION_ID);

            assertEquals(reason, response.declineReasonCode(),
                    "the factory changed the reject code it was given");
            assertEquals(reason.resolvesAccount(), response.accountId() != null,
                    reason.code() + " names an account exactly when its read resolved one");
            covered.add(response.declineReasonCode());
        }

        assertEquals(EnumSet.allOf(DeclineReason.class), covered,
                "two factories build every reject code app/cbl/CBTRN02C.cbl assigns");
        assertTrue(covered.contains(UNRESOLVED_CARD),
                "reject code 0100 stopped reaching a caller at all");
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
     * Asserts a declined response carrying no account produces the event its contract requires, keyed
     * on the transaction identifier rather than on an account.
     *
     * <p>This is the second decline shape reaching its second contract.
     * {@code transaction-declined-v2.json} declares no {@code accountId} property and retypes
     * {@code aggregateId} to the sixteen printable characters a transaction identifier occupies, so the
     * value this response already carries is the message key. Nothing has to be invented for it, which
     * is the whole reason this contract was chosen over a sentinel account.
     */
    @Test
    void aDeclinedResponseCarryingNoAccountProducesItsEvent() {
        AuthorizationResponse response =
                AuthorizationResponse.declineUnresolvedCard(TRANSACTION_ID);

        TransactionDeclined event = TransactionDeclined.ofUnresolvedAccount(
                response.transactionId(), new BigDecimal("504.77"), "************7065");

        assertEquals(response.transactionId(), event.envelope().aggregateId(),
                "the response identifier becomes the message key");
        assertNull(event.accountId(), "and no account is named, because none resolved");
        assertEquals(TransactionDeclined.UNRESOLVED_ACCOUNT_SCHEMA_VERSION,
                event.envelope().schemaVersion(),
                "the event travels under the one declined document declaring no accountId");
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
