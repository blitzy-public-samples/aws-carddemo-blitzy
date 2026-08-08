package com.carddemo.authorization.domain;

import com.carddemo.authorization.entity.AuthorizationDecisionEntity;
import java.security.Principal;
import java.util.Collection;

/**
 * Names the request identity behind the call being decided, and gathers what that identity holds.
 *
 * <p>ADDITIVE. {@code app/cbl/COSGN00C.cbl:L223-L236} authenticates an identity and forks on its
 * type, and {@code app/cbl/COMEN01C.cbl:L149-L150} carries two commented-out statements that would
 * have copied the signed-on identifier forward. Nothing in the source records that identifier on a
 * decision, so this class is the target-side replacement for statements the source disabled.
 *
 * <p>The identity arrives as a {@link Principal} and its entitlements arrive as plain text, both
 * resolved by the web layer and passed in. Reading either from a thread-bound security context
 * instead would put an access-control type in the domain layer, where {@code config/SecurityConfig}
 * is the one file that declares access control. That is also why the entitlements arrive as
 * {@link String} values rather than as granted-authority objects.
 *
 * <p>The name is recorded whole. It is bounded to
 * {@value AuthorizationDecisionEntity#ACTOR_MAX_LENGTH} characters, which is the audit column width,
 * and a wider name is refused rather than shortened. Shortening is what made two identities share
 * one recorded actor: the shipped nine-character monitoring identity reached the column as
 * {@code monitor0}, and every identity agreeing in its leading characters reached it the same way. An
 * audit row that cannot name one identity does not audit. {@code config/SecurityConfig} holds every
 * configured identity inside the bound at start-up, so the refusal below is a guard on an
 * unreachable state and not a case a deployment meets.
 *
 * <p>An unauthenticated call cannot reach the route that writes an audit row, because
 * {@code config/SecurityConfig} requires a role on {@code POST /authorizations}. A call carrying no
 * principal still reads as {@value #UNAUTHENTICATED_ACTOR} rather than as an absence, so a direct
 * call in a test writes a row the column accepts and an operator can read honestly. Such a call
 * holds no entitlement at all, which is what {@link RequestCaller} then refuses.
 *
 * <p>Every member is static, so no instance is created and no thread shares state.
 */
public final class AuthenticatedActor {

    /** Name recorded when the call carries no authenticated principal. */
    public static final String UNAUTHENTICATED_ACTOR = "ANONYMOUS";

    /** No instance is created. */
    private AuthenticatedActor() {
    }

    /**
     * Returns the name of one principal, whole.
     *
     * @param caller the authenticated principal the web layer resolved, or {@code null}
     * @return the identity name, or {@value #UNAUTHENTICATED_ACTOR} when the call carries none
     * @throws IllegalStateException when the name is wider than the audit column holds, which
     *                               {@code config/SecurityConfig} refuses at start-up
     */
    public static String actorOf(Principal caller) {
        if (caller == null) {
            return UNAUTHENTICATED_ACTOR;
        }

        String name = caller.getName();
        if (name == null || name.isBlank()) {
            return UNAUTHENTICATED_ACTOR;
        }
        if (name.length() > AuthorizationDecisionEntity.ACTOR_MAX_LENGTH) {
            throw new IllegalStateException("a configured identity name holds at most "
                    + AuthorizationDecisionEntity.ACTOR_MAX_LENGTH
                    + " characters and this one holds " + name.length()
                    + "; carddemo.security.users is misconfigured");
        }
        return name;
    }

    /**
     * Returns the caller of one request: its recorded name and the entitlements it holds.
     *
     * <p>The two halves travel together because the decision path needs both. The name reaches the
     * audit row and the entitlements decide whether the resolved account and card may be authorized
     * against at all, which {@code domain/CallerEntitlement} applies.
     *
     * @param caller      the authenticated principal the web layer resolved, or {@code null}
     * @param authorities the authority names the web layer resolved, each already rendered as text,
     *                    or {@code null} for a call carrying none
     * @return the caller, never {@code null}
     * @throws IllegalStateException when the principal name is wider than the audit column holds
     */
    public static RequestCaller callerOf(Principal caller, Collection<String> authorities) {
        return RequestCaller.of(actorOf(caller), authorities);
    }
}
