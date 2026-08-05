package com.carddemo.authorization.domain;

import com.carddemo.authorization.entity.AuthorizationDecisionEntity;
import java.security.Principal;

/**
 * Names the request identity behind the call being decided, at the width the audit column holds.
 *
 * <p>ADDITIVE. {@code app/cbl/COSGN00C.cbl:L223-L236} authenticates an identity and forks on its
 * type, and {@code app/cbl/COMEN01C.cbl:L149-L150} carries two commented-out statements that would
 * have copied the signed-on identifier forward. Nothing in the source records that identifier on a
 * decision, so this class is the target-side replacement for statements the source disabled.
 *
 * <p>The identity arrives as a {@link Principal}, which the web layer resolves and passes in. Reading
 * it from a thread-bound security context instead would put an access-control type in the domain
 * layer, where {@code config/SecurityConfig} is the one file that declares access control.
 *
 * <p>The name is bounded to {@value AuthorizationDecisionEntity#ACTOR_MAX_LENGTH} characters, the width
 * {@code SEC-USR-ID PIC X(8)} at {@code app/cpy/CSUSR01Y.cpy:L18} declares. A longer name is
 * shortened rather than refused: the route already authenticated the caller, and a decision must not
 * fail because a deployment configured a wide user name.
 *
 * <p>An unauthenticated call cannot reach the route that writes an audit row, because
 * {@code config/SecurityConfig} requires a role on {@code POST /authorizations}. A call carrying no
 * principal still reads as {@value #UNAUTHENTICATED_ACTOR} rather than as an absence, so a direct call
 * in a test writes a row the column accepts and an operator can read honestly.
 *
 * <p>Every member is static, so no instance is created and no thread shares state.
 */
public final class AuthenticatedActor {

    /** Name recorded when the call carries no authenticated principal. */
    public static final String UNAUTHENTICATED_ACTOR = "ANONYMOU";

    /** No instance is created. */
    private AuthenticatedActor() {
    }

    /**
     * Returns the name of one principal, shortened to the audit column width.
     *
     * @param caller the authenticated principal the web layer resolved, or {@code null}
     * @return the identity name, or {@value #UNAUTHENTICATED_ACTOR} when the call carries none
     */
    public static String actorOf(Principal caller) {
        if (caller == null) {
            return UNAUTHENTICATED_ACTOR;
        }

        String name = caller.getName();
        if (name == null || name.isBlank()) {
            return UNAUTHENTICATED_ACTOR;
        }
        return name.length() <= AuthorizationDecisionEntity.ACTOR_MAX_LENGTH
                ? name
                : name.substring(0, AuthorizationDecisionEntity.ACTOR_MAX_LENGTH);
    }
}
