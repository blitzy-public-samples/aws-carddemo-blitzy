package com.carddemo.authorization.domain;

import com.carddemo.cobol.PanMasker;
import java.util.Objects;

/**
 * Refuses a caller that may not authorize against the account and card its request resolved to.
 *
 * <p>ADDITIVE in full. The gap it closes is that {@code POST /authorizations} admitted any identity
 * holding an ordinary role and then authorized whatever subject the request resolved to. Because a
 * request may name an account identifier alone and
 * {@code domain/AuthorizationService#resolveCard} reads the first card of that account, an
 * ordinary credential could move money against any account in the platform without ever knowing a
 * card number. Every other ownership-scoped route on the platform compares the requested identifier
 * against the caller's authorities; this one did not.
 *
 * <p>The rule, in the order it is applied:</p>
 *
 * <ol>
 *   <li>A caller holding {@value RequestCaller#ADMINISTRATOR_AUTHORITY} passes. That is the
 *       administrator limb of {@code app/cbl/COSGN00C.cbl:L232-L236}, and it is the same allowance
 *       {@code config/SecurityConfig} makes on every ownership-scoped route.</li>
 *   <li>A caller holding {@value RequestCaller#ACQUIRER_AUTHORITY} passes as well. That is the
 *       workload identity this route exists for: it presents transactions for cards it does not own,
 *       so it holds no ownership scope, and {@code config/SecurityConfig} admits it here and nowhere
 *       else. Refusing it would leave the route admitting an identity and then declining every call
 *       it made.</li>
 *   <li>Any other caller passes when it owns the resolved account, which is the account the
 *       cross-reference row named and never the one the request supplied.</li>
 *   <li>Otherwise it passes when it owns the card the request resolved, named by the card token
 *       {@link PanMasker#cardToken(String)} derives.</li>
 *   <li>Otherwise it is refused.</li>
 * </ol>
 *
 * <p>Order matters for cost as well as for correctness. The account comparison is a set lookup and the
 * card comparison derives a keyed message code, so the token is derived only when the account
 * comparison has already failed, and never at all for an administrator.
 *
 * <p>A call whose card resolved no account reaches this check with a {@code null} account identifier,
 * so only card ownership can carry it. That is deliberate: an unentitled caller probing a card number
 * receives the same refusal whether or not that card exists. Card existence stops being observable to
 * a caller that owns nothing.
 *
 * <p>The account this check reads is the resolved one, and no account read from the request body
 * reaches it or reaches any other decision. A security review found the earlier behaviour, where a
 * card resolving no row was decided against the account the caller had declared: any caller owning
 * any account could then pair it with an unknown card and learn from the answer whether that card
 * existed. {@code domain/AuthorizationService} now refuses such a call outright, immediately after
 * this check, and {@code card-platform/docs/decision-log.md} records what the review found.
 *
 * <p>Where this check runs is as important as what it decides. {@code domain/AuthorizationService}
 * applies it after the chain has resolved the card and the account, before the refusal above and
 * before it allocates a transaction identifier, so a refused call consumes no sequence value, records
 * no decision and produces no event. Running it first is what keeps the two refusals
 * indistinguishable to a caller that owns nothing.
 *
 * <p>Every member is static, so no instance is created and no thread shares state.
 */
public final class CallerEntitlement {

    /** No instance is created. */
    private CallerEntitlement() {
    }

    /**
     * Refuses a caller that owns neither the resolved account nor the resolved card.
     *
     * @param caller            the identity that asked for the decision
     * @param resolvedAccountId the account the cross-reference row named, eleven digits, or
     *                          {@code null} when the card resolved none
     * @param cardNumber        the full card number the request resolved to, which is used to derive
     *                          the ownership token and reaches no output
     * @throws NullPointerException        when {@code caller} or {@code cardNumber} is {@code null}
     * @throws CallerNotEntitledException  when the caller owns neither subject
     */
    public static void require(RequestCaller caller, String resolvedAccountId, String cardNumber) {
        Objects.requireNonNull(caller, "caller must be present");
        Objects.requireNonNull(cardNumber, "cardNumber must be present");

        if (caller.reachesEverySubject() || caller.ownsAccount(resolvedAccountId)) {
            return;
        }
        if (caller.ownsCard(PanMasker.cardToken(cardNumber))) {
            return;
        }
        throw new CallerNotEntitledException();
    }
}
