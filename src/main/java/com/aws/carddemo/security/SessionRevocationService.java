package com.aws.carddemo.security;

import java.util.List;
import java.util.Locale;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.session.SessionInformation;
import org.springframework.security.core.session.SessionRegistry;
import org.springframework.stereotype.Service;

/**
 * Revokes the live HTTP sessions of a CardDemo user, so that an identity which has been deleted,
 * demoted (admin&nbsp;&rarr;&nbsp;user), or had its password changed cannot keep operating with a
 * still-authenticated session (review findings #8 and #43; CWE-613 "Insufficient Session Expiration").
 *
 * <p><strong>Why this exists.</strong> The legacy CICS model had no long-lived server session: each
 * pseudo-conversational turn re-entered the program and the RACF/USRSEC state was consulted afresh, so
 * a mid-flight change to a user took effect on the user's very next interaction. The migrated
 * application keeps an authenticated {@code HttpSession} for the pseudo-conversational
 * {@code CardDemoContext}, which would otherwise let a revoked identity continue until it happened to
 * log out. This service restores the legacy "next interaction re-checks the user" behavior by expiring
 * the affected sessions; the {@code ConcurrentSessionFilter} (activated by the {@code SessionRegistry}
 * wired in {@code SecurityConfig}) then redirects the next request from an expired session back to the
 * signon screen.</p>
 *
 * <p><strong>How matching works.</strong> The {@link SessionRegistry} tracks principals; for CardDemo
 * every registered principal is a {@link CardDemoUserDetails} whose {@link CardDemoUserDetails#getUsername()
 * username} is the {@code SEC-USR-ID}. Because the id column is fixed-width {@code CHAR(8)} and the
 * signon lookup uppercases the entered id, both the registered username and the caller-supplied target
 * id are normalized (leading/trailing blanks stripped, upper-cased with {@link Locale#ROOT}) before
 * comparison, so a match is found regardless of blank padding or case.</p>
 *
 * <p><strong>Transaction ordering.</strong> Callers must invoke {@link #revokeSessions(String)}
 * <em>after</em> the database mutation has committed (e.g. from the controller once the
 * {@code @Transactional} service method has returned, or via a post-commit hook). Expiring sessions
 * is an in-memory, non-transactional action; performing it before commit could revoke sessions for a
 * change that later rolls back.</p>
 */
@Service
public class SessionRevocationService {

    /** Logger for structured, non-sensitive lifecycle events (never logs credentials). */
    private static final Logger LOGGER = LoggerFactory.getLogger(SessionRevocationService.class);

    /** Shared registry of authenticated sessions (same instance used by the filter chain). */
    private final SessionRegistry sessionRegistry;

    /**
     * Creates the revocation service.
     *
     * @param sessionRegistry the shared {@link SessionRegistry} declared in {@code SecurityConfig};
     *                        must be the same instance the security filter chain and the signon
     *                        registration strategy use, otherwise revocation would target a different
     *                        registry than the one tracking live sessions
     */
    public SessionRevocationService(SessionRegistry sessionRegistry) {
        this.sessionRegistry = sessionRegistry;
    }

    /**
     * Expires every live session belonging to the given user id, revoking the identity's access on its
     * next request.
     *
     * <p>Idempotent and null-safe: a {@code null} or effectively blank id, or a user with no live
     * sessions, revokes nothing and returns {@code 0}. Already-expired sessions are ignored (they are
     * not counted again).</p>
     *
     * @param userId the target {@code SEC-USR-ID} (any case / blank padding); the user whose sessions
     *               are to be revoked
     * @return the number of live sessions that were expired by this call
     */
    public int revokeSessions(String userId) {
        String target = normalize(userId);
        if (target.isEmpty()) {
            return 0;
        }
        int revoked = 0;
        for (Object principal : sessionRegistry.getAllPrincipals()) {
            if (principal instanceof CardDemoUserDetails userDetails
                    && target.equals(normalize(userDetails.getUsername()))) {
                // includeExpiredSessions=false: only act on sessions that are still live.
                List<SessionInformation> sessions = sessionRegistry.getAllSessions(principal, false);
                for (SessionInformation session : sessions) {
                    session.expireNow();
                    revoked++;
                }
            }
        }
        if (revoked > 0) {
            LOGGER.info("Revoked {} live session(s) for user id '{}' following a lifecycle change",
                    revoked, target);
        }
        return revoked;
    }

    /**
     * Normalizes a user id for identity comparison: {@code null} becomes an empty string, otherwise
     * leading/trailing blanks are stripped and the value is upper-cased with {@link Locale#ROOT},
     * matching how the signon path uppercases the id and how {@code CHAR(8)} pads it.
     *
     * @param id the raw user id (possibly {@code null}, mixed case, or blank-padded)
     * @return the normalized comparison key (never {@code null})
     */
    private static String normalize(String id) {
        return id == null ? "" : id.strip().toUpperCase(Locale.ROOT);
    }
}
