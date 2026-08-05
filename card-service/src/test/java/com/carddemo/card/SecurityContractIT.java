/*
 * Copyright Amazon.com, Inc. or its affiliates.
 * All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License").
 * You may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND,
 * either express or implied. See the License for the specific
 * language governing permissions and limitations under the License.
 */
package com.carddemo.card;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.carddemo.card.service.CardService;
import com.carddemo.common.dto.SessionContext;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Base64;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * :purpose: Freezes the authentication contract of the card service — the three routes
 *     that re-platform ``COCRDLIC`` (list), ``COCRDSLC`` (detail) and ``COCRDUPC``
 *     (update), whose legacy analogues were reachable only through a CICS transaction a
 *     signed-on terminal had been authorized for. Every business route must reject a
 *     caller that presents no signed-on session, and the rejection must happen in the
 *     filter chain — before any business logic runs — which is asserted by proving the
 *     service collaborator is never touched. The card aggregate carries the PAN and the
 *     cardholder name, so an unauthenticated read is a disclosure, not just a
 *     misrouting.
 * :output: A Failsafe (``*IT``) test asserting the observed status of each route for an
 *     anonymous caller and for a signed-on one, the anonymous reachability of the
 *     probes, the body-size limit, the telemetry credential and the hardened response
 *     headers.
 * :note: The production filter chain is deliberately active here (no ``addFilters =
 *     false``): this test exists precisely to exercise it. A request authenticates the
 *     way production does, by presenting the shared session context published at
 *     sign-on.
 * :note: card-service gates ``anyRequest()`` to ``hasAnyRole("USER", "ADMIN")`` rather
 *     than to ``ROLE_ADMIN``, because the legacy card screens are main-menu options a
 *     ``userType 'U'`` operator selects; the role-independent scoping of what a caller
 *     may SEE is the service's own concern and is pinned by ``CardServiceTest``.
 */
@SpringBootTest(properties = "carddemo.monitoring.password=" + SecurityContractIT.MONITORING_PASSWORD)
@AutoConfigureMockMvc
@ActiveProfiles("test")
class SecurityContractIT {

    /*
     * The shared Customer entity encrypts its PII columns through CryptoConverter, which
     * fails fast without a key. An all-zero Base64 32-byte value is installed before the
     * context starts; it is a test-only fixture.
     */
    static {
        if (System.getProperty("carddemo.pii.key") == null
                && System.getenv("CARDDEMO_PII_KEY") == null) {
            System.setProperty("carddemo.pii.key", Base64.getEncoder().encodeToString(new byte[32]));
        }
    }

    /**
     * :purpose: Telemetry scrape credential installed for this context, so the test can
     *     prove the ``monitoring`` principal both exists and is required. In a deployment
     *     the value arrives from the environment; when it is absent the principal is
     *     seeded with a random credential and the endpoint stays closed.
     */
    static final String MONITORING_PASSWORD = "qa-monitoring-secret";

    /** :purpose: A card number in the seeded fixture range, used as a path variable. */
    private static final String CARD_NUMBER = "0500024453765740";

    /**
     * :purpose: A body that satisfies every ``CardUpdateRequestDto`` constraint — including
     *     the legacy-spelled ``cardExpiraionDate`` — so a rejected update can only have been
     *     rejected by authorization, never by deserialization or validation.
     */
    private static final String UPDATE_BODY = """
            {"cardEmbossedName":"Immanuel Kessler",\
            "cardActiveStatus":"Y",\
            "cardExpiraionDate":"2025-05-20"}""";

    @Autowired
    private MockMvc mockMvc;

    /** :purpose: Spied so the test can prove the request never reached business logic. */
    @MockitoSpyBean
    private CardService cardService;

    /**
     * :purpose: Build the signed-on session context a real caller presents.
     * :returns: a regular-user session context.
     */
    private SessionContext userSession() {
        SessionContext session = new SessionContext();
        session.setUserId("USER0001");
        session.setUserType(SessionContext.UserType.CDEMO_USRTYP_USER);
        return session;
    }

    @Test
    @DisplayName("an anonymous card list is rejected with 401 and never reaches the service")
    void anonymousCardListIsRejected() throws Exception {
        mockMvc.perform(get("/cards")).andExpect(status().isUnauthorized());

        verifyNoInteractions(cardService);
    }

    @Test
    @DisplayName("an anonymous card detail read is rejected with 401 and never reaches the service")
    void anonymousCardDetailIsRejected() throws Exception {
        mockMvc.perform(get("/cards/" + CARD_NUMBER)).andExpect(status().isUnauthorized());

        verifyNoInteractions(cardService);
    }

    @Test
    @DisplayName("an anonymous card update is rejected with 401 and never reaches the service")
    void anonymousCardUpdateIsRejected() throws Exception {
        mockMvc.perform(put("/cards/" + CARD_NUMBER)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(UPDATE_BODY))
                .andExpect(status().isUnauthorized());

        verifyNoInteractions(cardService);
    }

    @Test
    @DisplayName("a signed-on session passes authorization and reaches the service")
    void signedOnCardListPassesAuthorization() throws Exception {
        int status = mockMvc.perform(get("/cards")
                        .sessionAttr(SessionContext.SESSION_ATTRIBUTE_NAME, userSession()))
                .andReturn()
                .getResponse()
                .getStatus();

        // The outcome depends on the seeded fixtures; what matters is that authorization
        // no longer blocks the request, so it is anything other than 401/403.
        assertThat(status).isNotIn(401, 403);
    }

    @Test
    @DisplayName("the health probe stays reachable without credentials")
    void healthProbeIsAnonymous() throws Exception {
        mockMvc.perform(get("/actuator/health")).andExpect(status().isOk());
    }

    @Test
    @DisplayName("an oversized request body is rejected with 413 before authentication")
    void oversizedBodyIsRejected() throws Exception {
        byte[] oversized = new byte[(int) (com.carddemo.common.config.RequestSizeLimitFilter
                .DEFAULT_MAX_BODY_BYTES + 1024)];
        Arrays.fill(oversized, (byte) 'A');

        mockMvc.perform(put("/cards/" + CARD_NUMBER)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(oversized))
                // 413: isPayloadTooLarge() is deprecated in favour of the status code.
                .andExpect(status().isContentTooLarge());
    }

    @Test
    @DisplayName("telemetry is closed to an anonymous caller and open to the monitoring principal")
    void telemetryRequiresTheMonitoringPrincipal() throws Exception {
        mockMvc.perform(get("/actuator/prometheus"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(get("/actuator/prometheus")
                        .header("Authorization", basicAuth("monitoring", "wrong-password")))
                .andExpect(status().isUnauthorized());

        var authorized = mockMvc.perform(get("/actuator/prometheus")
                        .header("Authorization", basicAuth("monitoring", MONITORING_PASSWORD)))
                .andExpect(status().isOk())
                .andReturn().getResponse();

        assertThat(authorized.getContentAsString()).contains("jvm_");
    }

    /**
     * :purpose: Build the HTTP basic credential exactly as the Prometheus scrape presents
     *     it, so no test-only security helper library is required.
     * :param username: the principal name.
     * :param password: the raw password.
     * :returns: the ``Authorization`` header value.
     */
    private static String basicAuth(String username, String password) {
        return "Basic " + Base64.getEncoder()
                .encodeToString((username + ":" + password).getBytes(StandardCharsets.UTF_8));
    }

    @Test
    @DisplayName("every response carries the hardened security headers")
    void responsesCarryHardenedHeaders() throws Exception {
        var response = mockMvc.perform(get("/actuator/health")).andReturn().getResponse();

        assertThat(response.getHeader("X-Content-Type-Options")).isEqualTo("nosniff");
        assertThat(response.getHeader("X-Frame-Options")).isEqualTo("DENY");
        assertThat(response.getHeader("Content-Security-Policy")).isNotBlank();
        assertThat(response.getHeader("Referrer-Policy")).isEqualTo("no-referrer");
        assertThat(response.getHeader("Permissions-Policy")).isNotBlank();
        assertThat(response.getHeader("Cache-Control")).contains("no-store");
        assertThat(response.getHeader("X-Correlation-Id")).isNotBlank();
    }
}
