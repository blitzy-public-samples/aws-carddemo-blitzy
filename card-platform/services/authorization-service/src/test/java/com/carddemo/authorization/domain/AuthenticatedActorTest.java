package com.carddemo.authorization.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.carddemo.authorization.entity.AuthorizationDecisionEntity;
import java.security.Principal;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Measures which name reaches the audit row, and that it always fits the column.
 *
 * <p>ADDITIVE. {@code app/cbl/COSGN00C.cbl:L223-L236} authenticates an identity and forks on its
 * type, and {@code app/cbl/COMEN01C.cbl:L149-L150} carries two commented-out statements that would
 * have copied the signed-on identifier forward. Nothing in the source records that identifier on a
 * decision, so these tests measure a target-side addition.
 *
 * <p>The identity arrives as a {@link Principal} the web layer resolved, so these tests construct one
 * directly. Reading a thread-bound security context instead would require an access-control type in
 * the domain layer, which {@code equivalence-tests} refuses.
 */
@DisplayName("AuthenticatedActor, the request identity an audit row records")
class AuthenticatedActorTest {

    @Test
    @DisplayName("an authenticated identity reaches the audit row by name")
    void anAuthenticatedIdentityReachesTheAuditRowByName() {
        assertEquals("user0001", AuthenticatedActor.actorOf(named("user0001")),
                "the audit row records the identity that asked for the decision");
    }

    @Test
    @DisplayName("a name wider than the column is shortened rather than refused")
    void aWideNameIsShortenedRatherThanRefused() {
        String recorded = AuthenticatedActor.actorOf(named("a-very-long-principal-name"));

        assertEquals("a-very-l", recorded,
                "SEC-USR-ID PIC X(8) at app/cpy/CSUSR01Y.cpy:L18 bounds the column, and a decision "
                        + "must not fail because a deployment configured a wide user name");
        assertEquals(AuthorizationDecisionEntity.ACTOR_MAX_LENGTH, recorded.length(),
                "the shortened name is exactly the column width");
    }

    @Test
    @DisplayName("a call carrying no identity reads honestly rather than as an absence")
    void aCallCarryingNoIdentityReadsHonestly() {
        assertEquals(AuthenticatedActor.UNAUTHENTICATED_ACTOR, AuthenticatedActor.actorOf(null),
                "a null principal produced a null actor, which the audit column refuses");
        assertEquals(AuthenticatedActor.UNAUTHENTICATED_ACTOR, AuthenticatedActor.actorOf(named(null)),
                "a principal with no name is the same absence as no principal");
        assertEquals(AuthenticatedActor.UNAUTHENTICATED_ACTOR, AuthenticatedActor.actorOf(named("   ")),
                "a blank name is an absence the column must not carry");
    }

    @Test
    @DisplayName("a name exactly the column width passes through unchanged")
    void aNameExactlyTheColumnWidthPassesThrough() {
        assertEquals("abcdefgh", AuthenticatedActor.actorOf(named("abcdefgh")),
                "eight characters already fit, so nothing is removed");
    }

    /**
     * Returns one principal carrying the supplied name.
     *
     * @param name the name the principal reports, which may be {@code null}
     * @return a principal reporting that name
     */
    private static Principal named(String name) {
        return () -> name;
    }
}
