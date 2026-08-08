package com.carddemo.authorization.domain;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.carddemo.cobol.PanMasker;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Measures which caller may authorize against which subject.
 *
 * <p>ADDITIVE in full. {@code app/cbl/CBTRN02C.cbl} authorizes a record the nightly feed supplied and
 * has no caller to entitle, and {@code app/cbl/COTRN02C.cbl} captures whatever card number the operator
 * keyed. Neither compares the identity that asked against the account or card the request names, so
 * these tests measure an addition rather than a transformation.
 *
 * <p>The gap the check closes is measured by
 * {@code AuthorizationServiceTest.CallerEntitlement}: an ordinary credential could authorize against
 * any account in the platform, because a request may name an account alone and the decision path
 * resolves that account's first card. This file measures the rule itself.
 */
@DisplayName("CallerEntitlement, which caller reaches which subject")
class CallerEntitlementTest {

    /** The account a card resolves to in every case below, eleven digits with a leading zero. */
    private static final String RESOLVED_ACCOUNT = "00000000077";

    /** An account no caller below owns. */
    private static final String OTHER_ACCOUNT = "00000000008";

    /** The card number every case below resolves, sixteen digits. */
    private static final String CARD_NUMBER = "4859452612877065";

    /** Another card, so a caller owning one card is measured against the other. */
    private static final String OTHER_CARD_NUMBER = "4859452612877073";

    /** The name every caller below records on its decision row. */
    private static final String ACTOR = "user0001";

    @Nested
    @DisplayName("The administrator limb of app/cbl/COSGN00C.cbl:L232-L236")
    class Administrator {

        @Test
        @DisplayName("reaches an account it holds no scope for")
        void reachesAnAccountItHoldsNoScopeFor() {
            assertDoesNotThrow(() -> CallerEntitlement.require(
                    RequestCaller.administrator(ACTOR), RESOLVED_ACCOUNT, CARD_NUMBER),
                    "the administrator reaches every subject here, as it does on every "
                            + "ownership-scoped route of this platform");
        }

        @Test
        @DisplayName("reaches a card that resolved no account at all")
        void reachesACardThatResolvedNoAccount() {
            assertDoesNotThrow(() -> CallerEntitlement.require(
                    RequestCaller.administrator(ACTOR), null, CARD_NUMBER),
                    "reject code 0100 at app/cbl/CBTRN02C.cbl:L385-L387 is a decision an "
                            + "administrator may still ask for");
        }
    }

    /**
     * The one identity {@code POST /authorizations} is for.
     *
     * <p>These two cases are the seam between the route rule and this check. {@code SecurityConfig}
     * admits {@code ROLE_ACQUIRER} on this route and on no other, and an acquiring workload owns no
     * row, so it can hold no ownership scope. A check that passed only the administrator would admit
     * this identity at the chain and decline every call it made, which is a route nobody can use.
     */
    @Nested
    @DisplayName("The acquiring workload the one decision route admits")
    class Acquirer {

        @Test
        @DisplayName("reaches an account it holds no scope for")
        void reachesAnAccountItHoldsNoScopeFor() {
            assertDoesNotThrow(() -> CallerEntitlement.require(
                    RequestCaller.acquirer(ACTOR), RESOLVED_ACCOUNT, CARD_NUMBER),
                    "an acquirer legitimately presents transactions for cards it does not own");
        }

        @Test
        @DisplayName("reaches a card that resolved no account at all")
        void reachesACardThatResolvedNoAccount() {
            assertDoesNotThrow(() -> CallerEntitlement.require(
                    RequestCaller.acquirer(ACTOR), null, CARD_NUMBER),
                    "reject code 0100 at app/cbl/CBTRN02C.cbl:L385-L387 is the answer an acquirer "
                            + "presenting an unknown card is owed");
        }

        @Test
        @DisplayName("holds the acquirer authority alone, and no ownership scope")
        void holdsTheAcquirerAuthorityAlone() {
            assertEquals(Set.of(RequestCaller.ACQUIRER_AUTHORITY),
                    RequestCaller.acquirer(ACTOR).authorities(),
                    "an identity owning no row is granted no scope over one");
        }
    }

    @Nested
    @DisplayName("An ownership-scoped identity")
    class OwnershipScoped {

        @Test
        @DisplayName("reaches the account its own scope names")
        void reachesTheAccountItsScopeNames() {
            assertDoesNotThrow(() -> CallerEntitlement.require(
                    ownerOfAccount(RESOLVED_ACCOUNT), RESOLVED_ACCOUNT, CARD_NUMBER),
                    "the comparison is against the account the cross-reference row named");
        }

        @Test
        @DisplayName("reaches a card its own scope names when it holds no account scope")
        void reachesACardItsScopeNames() {
            assertDoesNotThrow(() -> CallerEntitlement.require(
                    ownerOfCard(CARD_NUMBER), RESOLVED_ACCOUNT, CARD_NUMBER),
                    "a cardholder entitled to one card reaches it whichever account it belongs to");
        }

        @Test
        @DisplayName("is refused another subject's account")
        void isRefusedAnotherAccount() {
            assertThrows(CallerNotEntitledException.class, () -> CallerEntitlement.require(
                    ownerOfAccount(OTHER_ACCOUNT), RESOLVED_ACCOUNT, CARD_NUMBER),
                    "a request naming an account alone resolves that account's first card, so "
                            + "without this check no card number had to be known");
        }

        @Test
        @DisplayName("is refused another subject's card")
        void isRefusedAnotherCard() {
            assertThrows(CallerNotEntitledException.class, () -> CallerEntitlement.require(
                    ownerOfCard(OTHER_CARD_NUMBER), RESOLVED_ACCOUNT, CARD_NUMBER),
                    "owning one card entitles a caller to that card and to no other");
        }

        @Test
        @DisplayName("is refused when it owns nothing at all")
        void isRefusedWhenItOwnsNothing() {
            assertThrows(CallerNotEntitledException.class, () -> CallerEntitlement.require(
                    RequestCaller.of(ACTOR, List.of("ROLE_USER")), RESOLVED_ACCOUNT, CARD_NUMBER),
                    "an absent entitlement is not an unrestricted one");
        }

        /**
         * Asserts an unentitled caller cannot learn whether a card exists.
         *
         * <p>A card that resolved no account is reject code 0100 for an entitled caller. For a caller
         * owning neither subject the answer is the same refusal it receives for a card that does
         * resolve, so the reject code stops being an existence oracle.
         */
        @Test
        @DisplayName("is refused a card that resolved no account, so card existence stays hidden")
        void isRefusedAnUnresolvedCardToo() {
            CallerNotEntitledException unresolved = assertThrows(CallerNotEntitledException.class,
                    () -> CallerEntitlement.require(ownerOfAccount(OTHER_ACCOUNT), null,
                            CARD_NUMBER));
            CallerNotEntitledException resolved = assertThrows(CallerNotEntitledException.class,
                    () -> CallerEntitlement.require(ownerOfAccount(OTHER_ACCOUNT),
                            RESOLVED_ACCOUNT, CARD_NUMBER));

            assertEquals(resolved.getMessage(), unresolved.getMessage(),
                    "a caller owning neither subject learns nothing about whether the card exists");
        }
    }

    @Nested
    @DisplayName("The refusal itself")
    class TheRefusal {

        @Test
        @DisplayName("carries one fixed text and names no identifier")
        void carriesOneFixedTextAndNamesNoIdentifier() {
            CallerNotEntitledException refused = assertThrows(CallerNotEntitledException.class,
                    () -> CallerEntitlement.require(ownerOfAccount(OTHER_ACCOUNT),
                            RESOLVED_ACCOUNT, CARD_NUMBER));

            assertAll(
                    () -> assertEquals(CallerNotEntitledException.DETAIL, refused.getMessage(),
                            "the text is fixed, so no refusal can quote what it refused"),
                    () -> assertFalse(refused.getMessage().contains(RESOLVED_ACCOUNT),
                            "an account identifier in the message would reach a log line"),
                    () -> assertFalse(refused.getMessage().contains(CARD_NUMBER),
                            "a card number in the message would reach a stack trace"),
                    () -> assertFalse(
                            refused.getMessage().contains(PanMasker.cardToken(CARD_NUMBER)),
                            "and so would the token that names the card"));
        }

        @Test
        @DisplayName("refuses an absent caller rather than treating it as unrestricted")
        void refusesAnAbsentCaller() {
            assertThrows(NullPointerException.class,
                    () -> CallerEntitlement.require(null, RESOLVED_ACCOUNT, CARD_NUMBER),
                    "a null caller is a programming fault and not an entitlement");
            assertThrows(NullPointerException.class, () -> CallerEntitlement.require(
                    RequestCaller.administrator(ACTOR), RESOLVED_ACCOUNT, null),
                    "the card number is what the card ownership comparison is derived from");
        }
    }

    @Nested
    @DisplayName("The caller record")
    class TheCallerRecord {

        @Test
        @DisplayName("holds its entitlements immutably and discloses none of them")
        void holdsItsEntitlementsImmutablyAndDisclosesNone() {
            RequestCaller caller = ownerOfAccount(RESOLVED_ACCOUNT);

            assertAll(
                    () -> assertThrows(UnsupportedOperationException.class,
                            () -> caller.authorities().add("SCOPE_ACCOUNT_00000000001"),
                            "a caller whose entitlements a later line can extend entitles nothing"),
                    () -> assertFalse(caller.toString().contains(RESOLVED_ACCOUNT),
                            "an ownership authority names a cardholder, so the rendering withholds "
                                    + "it"),
                    () -> assertTrue(caller.toString().contains(ACTOR),
                            "the actor is already recorded in the clear on every decision row"));
        }

        @Test
        @DisplayName("refuses a name the audit column cannot record whole")
        void refusesANameTheAuditColumnCannotRecord() {
            assertAll(
                    () -> assertThrows(IllegalArgumentException.class,
                            () -> RequestCaller.of("   ", List.of()),
                            "a blank name is the absence the unauthenticated sentinel stands for"),
                    () -> assertThrows(IllegalArgumentException.class,
                            () -> RequestCaller.of(ACTOR, List.of("")),
                            "a blank authority entitles nothing and hides a configuration fault"));
        }
    }

    /**
     * Builds a caller entitled to one account and nothing else.
     *
     * @param accountId the eleven-digit account it owns
     * @return the caller
     */
    private static RequestCaller ownerOfAccount(String accountId) {
        return RequestCaller.of(ACTOR,
                Set.of("ROLE_USER", RequestCaller.ACCOUNT_SCOPE_PREFIX + accountId));
    }

    /**
     * Builds a caller entitled to one card and nothing else.
     *
     * @param cardNumber the sixteen-digit card it owns, named in the authority by its token
     * @return the caller
     */
    private static RequestCaller ownerOfCard(String cardNumber) {
        return RequestCaller.of(ACTOR, Set.of("ROLE_USER",
                RequestCaller.CARD_SCOPE_PREFIX + PanMasker.cardToken(cardNumber)));
    }
}
