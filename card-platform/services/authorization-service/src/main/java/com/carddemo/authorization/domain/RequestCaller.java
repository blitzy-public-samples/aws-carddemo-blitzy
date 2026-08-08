package com.carddemo.authorization.domain;

import com.carddemo.authorization.entity.AuthorizationDecisionEntity;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Set;

/**
 * The identity that asked for one decision, and what that identity is entitled to reach.
 *
 * <p>ADDITIVE, and the reason it exists is that an actor name alone cannot answer an authorization
 * question. {@code app/cbl/COSGN00C.cbl:L232-L236} forks on {@code SEC-USR-TYPE} and grants an
 * administrator a wider menu, which is the whole of the source's authorization model. This record
 * carries the target-side equivalent: one role authority, and one ownership authority per identifier
 * the identity holds.
 *
 * <p>Both components are plain text. A granted-authority type belongs to the access-control library,
 * and {@code config/SecurityConfig} is the one file that declares access control, so the web layer
 * renders each authority to its name and this record carries the names. The vocabulary below is the
 * same vocabulary {@code config/SecurityConfig} composes for its route rules, and
 * {@code SecurityConfigTest} holds the two in step so a rename in one cannot pass the build alone.
 *
 * <p>What the vocabulary means:</p>
 *
 * <ul>
 *   <li>{@value #ADMINISTRATOR_AUTHORITY} reaches every subject. This is the administrator limb of
 *       the source fork.</li>
 *   <li>{@value #ACQUIRER_AUTHORITY} reaches every subject too, and it is the authority
 *       {@code POST /authorizations} is for. An acquiring workload presents transactions for cards it
 *       does not own, so it can hold no ownership scope, and a route that reaches every card the
 *       platform holds is exactly what it needs. It is a role of its own rather than the
 *       administrator authority so that it reaches this one route and no other.</li>
 *   <li>{@value #ACCOUNT_SCOPE_PREFIX} followed by eleven digits reaches one account, written
 *       exactly as {@code XREF-ACCT-ID PIC 9(11)} at {@code app/cpy/CVACT03Y.cpy:L7} holds it,
 *       leading zeros included.</li>
 *   <li>{@value #CARD_SCOPE_PREFIX} followed by a card token reaches one card. The token is the
 *       keyed value {@code com.carddemo.cobol.PanMasker#cardToken(String)} derives, never the card
 *       number.</li>
 * </ul>
 *
 * <p>An ownership authority naming a card number rather than a token would put a Primary Account
 * Number into configuration, into a log line and into every audit of that configuration, so no form
 * of this record accepts one.
 *
 * <p>Design decisions: {@code card-platform/docs/decision-log.md}.
 *
 * @param actor       the name the audit row records, never blank and never wider than
 *                    {@value AuthorizationDecisionEntity#ACTOR_MAX_LENGTH} characters
 * @param authorities the authority names this identity holds, in the order they arrived, held
 *                    immutably and never {@code null}
 */
public record RequestCaller(String actor, Set<String> authorities) {

    /** The authority that reaches every subject, from the administrator limb of the source fork. */
    public static final String ADMINISTRATOR_AUTHORITY = "ROLE_ADMIN";

    /**
     * The additive authority the acquiring workload carries, which reaches every subject as well.
     *
     * <p>{@code config/SecurityConfig} admits this authority on {@code POST /authorizations} and on
     * no other route, and {@code SecurityConfigTest} holds the two names in step. Without it the
     * route would admit the acquirer and then refuse every call it made, because an acquirer owns no
     * row and can therefore hold no ownership scope.
     */
    public static final String ACQUIRER_AUTHORITY = "ROLE_ACQUIRER";

    /** Prefix of the authority that reaches one account, followed by the eleven-digit identifier. */
    public static final String ACCOUNT_SCOPE_PREFIX = "SCOPE_ACCOUNT_";

    /** Prefix of the authority that reaches one card, followed by that card's token. */
    public static final String CARD_SCOPE_PREFIX = "SCOPE_CARD_";

    /**
     * Copies the authority set and refuses an actor the audit column cannot hold.
     *
     * @throws NullPointerException     when either component is {@code null}
     * @throws IllegalArgumentException when {@code actor} is blank, when it is wider than the audit
     *                                  column holds, or when an authority is absent or blank
     */
    public RequestCaller {
        Objects.requireNonNull(actor, "actor must be present");
        Objects.requireNonNull(authorities, "authorities must be present");

        if (actor.isBlank()) {
            throw new IllegalArgumentException("actor must name an identity, and a blank name is "
                    + "the absence AuthenticatedActor.UNAUTHENTICATED_ACTOR stands in for");
        }
        if (actor.length() > AuthorizationDecisionEntity.ACTOR_MAX_LENGTH) {
            throw new IllegalArgumentException("actor holds one to "
                    + AuthorizationDecisionEntity.ACTOR_MAX_LENGTH
                    + " characters and the supplied name holds " + actor.length());
        }
        for (String authority : authorities) {
            if (authority == null || authority.isBlank()) {
                throw new IllegalArgumentException(
                        "authorities holds one name per entitlement and no blank entry");
            }
        }
        authorities = Set.copyOf(authorities);
    }

    /**
     * Builds one caller from a name and the authority names the web layer resolved.
     *
     * @param actor       the name the audit row records
     * @param authorities the authority names, or {@code null} for a call carrying none
     * @return the caller
     * @throws IllegalArgumentException when the name or an authority is unusable
     */
    public static RequestCaller of(String actor, Collection<String> authorities) {
        return new RequestCaller(actor,
                authorities == null ? Set.of() : new LinkedHashSet<>(authorities));
    }

    /**
     * Builds a caller that reaches every subject, from the administrator limb of the source fork.
     *
     * @param actor the name the audit row records
     * @return the caller, holding {@value #ADMINISTRATOR_AUTHORITY} alone
     * @throws IllegalArgumentException when the name is unusable
     */
    public static RequestCaller administrator(String actor) {
        return new RequestCaller(actor, Set.of(ADMINISTRATOR_AUTHORITY));
    }

    /**
     * Builds the acquiring workload, which reaches every subject and owns no row.
     *
     * @param actor the name the audit row records
     * @return the caller, holding {@value #ACQUIRER_AUTHORITY} alone
     * @throws IllegalArgumentException when the name is unusable
     */
    public static RequestCaller acquirer(String actor) {
        return new RequestCaller(actor, Set.of(ACQUIRER_AUTHORITY));
    }

    /**
     * Reports whether this caller reaches every subject.
     *
     * @return {@code true} when the caller holds {@value #ADMINISTRATOR_AUTHORITY} or
     *         {@value #ACQUIRER_AUTHORITY}
     */
    public boolean reachesEverySubject() {
        return authorities.contains(ADMINISTRATOR_AUTHORITY)
                || authorities.contains(ACQUIRER_AUTHORITY);
    }

    /**
     * Reports whether this caller owns one account identifier.
     *
     * @param accountId the account the card resolved, eleven digits, or {@code null} when the card
     *                  resolved none
     * @return {@code true} when an account identifier was resolved and this caller holds its
     *         ownership authority
     */
    public boolean ownsAccount(String accountId) {
        return accountId != null && authorities.contains(ACCOUNT_SCOPE_PREFIX + accountId);
    }

    /**
     * Reports whether this caller owns one card, named by its token.
     *
     * @param cardToken the token of the card this call names, or {@code null}
     * @return {@code true} when a token was supplied and this caller holds its ownership authority
     */
    public boolean ownsCard(String cardToken) {
        return cardToken != null && authorities.contains(CARD_SCOPE_PREFIX + cardToken);
    }

    /**
     * Names the caller and withholds every entitlement.
     *
     * <p>An ownership authority carries the identifier it grants: an account identifier or a card
     * token. Both name a cardholder, and this rendering reaches a log line the moment any code
     * concatenates the record into a message, so the text reports how many entitlements are held and
     * none of their values. The actor is already recorded in the clear on every decision row, so it
     * is named here too.
     *
     * @return a single-line rendering naming the actor and the number of entitlements
     */
    @Override
    public String toString() {
        return "RequestCaller[actor=" + actor + ", authorities=" + authorities.size() + " held]";
    }
}
