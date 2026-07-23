package com.aws.carddemo.security;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import com.aws.carddemo.AbstractPostgresIntegrationTest;
import com.aws.carddemo.TestCredentials;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * End-to-end proof of the session-revocation pipeline that backs review findings #8 (user delete) and
 * #43 (lifecycle-driven revocation / no {@code SessionRegistry}). It exercises the real chain: a
 * controller-managed sign-on registers the (rotated) session in the shared {@code SessionRegistry};
 * {@link SessionRevocationService} expires that user's sessions; and the {@code ConcurrentSessionFilter}
 * (activated by {@code SecurityConfig}'s {@code maximumSessions(-1).sessionRegistry(...)}) enforces the
 * expiry on the very next request by logging the session out and redirecting it to the expired URL.
 *
 * <p>This class deliberately verifies the revocation <em>mechanism</em> in isolation (sign-on &rarr;
 * registry &rarr; revoke &rarr; filter enforcement). The full administrative delete/demotion/password
 * change flows that <em>invoke</em> this mechanism are proven by the {@code UserDeleteService} /
 * {@code UserUpdateService} unit tests (which assert the services schedule revocation on a committed
 * security-relevant change) and are further hardened by the dedicated adversarial suite added later.</p>
 *
 * <p><strong>Infrastructure.</strong> Extends {@link AbstractPostgresIntegrationTest} (single
 * {@code postgres:18-alpine} Testcontainer, {@code test} profile, Flyway seed reset per test) and adds
 * {@link AutoConfigureMockMvc} so requests traverse the production Spring Security filter chain. The
 * seed password is externalized via {@link TestCredentials} (review finding #5).</p>
 */
@AutoConfigureMockMvc
@DisplayName("Session revocation pipeline (findings #8, #43)")
class SessionRevocationIT extends AbstractPostgresIntegrationTest {

    /** Seeded standard user ({@code SEC-USR-TYPE = 'U'}); 8-char {@code SEC-USR-ID}. */
    private static final String STANDARD_USER_ID = "USER0001";

    /** Externalized cleartext seed password (finding #5; no committed default). */
    private static final String SEED_PASSWORD = TestCredentials.seedPassword();

    /** MockMvc bound to the full security filter chain, including the ConcurrentSessionFilter. */
    @Autowired
    private MockMvc mockMvc;

    /** The revocation collaborator under test (same shared registry as the filter chain). */
    @Autowired
    private SessionRevocationService sessionRevocationService;

    /**
     * Signs a standard user on, confirms the session is not yet flagged expired, then revokes that
     * user's sessions and confirms the next request on the same session is expired and bounced to the
     * signon screen with the {@code expired} marker.
     *
     * @throws Exception if a MockMvc request cannot be performed
     */
    @Test
    @DisplayName("revoked user's live session is expired and redirected to signon on its next request")
    void revokedSessionIsExpiredOnNextRequest() throws Exception {
        // 1) Controller-managed sign-on registers the (rotated) session in the SessionRegistry.
        MockHttpSession session = new MockHttpSession();
        mockMvc.perform(post("/signon")
                        .param("userid", STANDARD_USER_ID)
                        .param("passwd", SEED_PASSWORD)
                        .session(session)
                        .with(csrf()))
                .andReturn();

        // 2) Sanity contrast: before revocation the session is NOT flagged expired. We deliberately
        //    hinge the before/after contrast on the "expired" marker rather than on whether /menu is
        //    served or redirected to /signon: a pre-revocation redirect to /signon can legitimately
        //    occur for pseudo-conversational (CardDemoContext first-entry) reasons that are unrelated
        //    to security, whereas the "expired" marker is emitted ONLY by the ConcurrentSessionFilter
        //    acting on an expired registered session. It is therefore the sole deterministic proof of
        //    revocation enforcement, and it must be absent here (before revocation).
        MvcResult before = mockMvc.perform(get("/menu").session(session)).andReturn();
        String beforeLocation = before.getResponse().getRedirectedUrl();
        if (beforeLocation != null) {
            assertThat(beforeLocation)
                    .as("before revocation the session must not be flagged expired")
                    .doesNotContain("expired");
        }

        // 3) Revoke: at least one live session must be found and expired for this user.
        int revoked = sessionRevocationService.revokeSessions(STANDARD_USER_ID);
        assertThat(revoked)
                .as("sign-on must have registered a live session that revocation can expire")
                .isGreaterThanOrEqualTo(1);

        // 4) The ConcurrentSessionFilter enforces the expiry on the next request: logout + redirect to
        //    the expired URL (/signon?expired), proving the registry+filter revocation is effective.
        MvcResult after = mockMvc.perform(get("/menu").session(session)).andReturn();
        int afterStatus = after.getResponse().getStatus();
        String afterLocation = after.getResponse().getRedirectedUrl();
        assertThat(afterStatus)
                .as("a revoked session must be redirected (3xx), was %s", afterStatus)
                .isBetween(300, 399);
        assertThat(afterLocation)
                .as("a revoked session must be bounced to the signon screen with the expired marker")
                .isNotNull()
                .contains("signon")
                .contains("expired");
    }
}
