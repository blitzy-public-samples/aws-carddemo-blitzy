package com.carddemo.authorization.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

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

    /**
     * Asserts a name the column holds is recorded whole, however long it is.
     *
     * <p>Shortening is what this class used to do, and it is the defect: two identities agreeing in
     * their leading characters shared one recorded actor, and the shipped nine-character monitoring
     * identity reached an eight-character column as {@code monitor0}. An audit row that cannot name one
     * identity does not audit.
     */
    @Test
    @DisplayName("a long name is recorded whole rather than shortened into another identity")
    void aLongNameIsRecordedWhole() {
        String recorded = AuthenticatedActor.actorOf(named("a-very-long-principal-name"));

        assertEquals("a-very-long-principal-name", recorded,
                "a shortened name would attribute this decision to every identity sharing its "
                        + "leading characters");
        assertEquals("monitor01", AuthenticatedActor.actorOf(named("monitor01")),
                "the shipped monitoring identity is nine characters and was recorded as monitor0 "
                        + "while the column held eight");
    }

    /**
     * Asserts a name the column cannot hold stops the call rather than being shortened to fit.
     *
     * <p>{@code config/SecurityConfig} refuses such an identity at start-up, so this is a guard on a
     * state no deployment reaches. It answers {@link IllegalStateException} and not
     * {@link IllegalArgumentException}, because the request is well formed and the configuration is
     * not: {@code api/GlobalExceptionHandler} then answers 500 rather than 422.
     */
    @Test
    @DisplayName("a name wider than the column stops the call and names the bound")
    void aNameWiderThanTheColumnStopsTheCall() {
        String tooWide = "x".repeat(AuthorizationDecisionEntity.ACTOR_MAX_LENGTH + 1);

        IllegalStateException refused = assertThrows(IllegalStateException.class,
                () -> AuthenticatedActor.actorOf(named(tooWide)),
                "a name the audit column cannot hold must not be shortened to fit");

        assertTrue(refused.getMessage()
                        .contains(String.valueOf(AuthorizationDecisionEntity.ACTOR_MAX_LENGTH)),
                "the message names the bound so an operator can correct the configuration");
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
        String exact = "a".repeat(AuthorizationDecisionEntity.ACTOR_MAX_LENGTH);

        assertEquals(exact, AuthenticatedActor.actorOf(named(exact)),
                "a name at the bound already fits, so nothing is removed");
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
