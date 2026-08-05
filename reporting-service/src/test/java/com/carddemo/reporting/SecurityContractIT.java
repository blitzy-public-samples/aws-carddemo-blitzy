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
package com.carddemo.reporting;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.carddemo.common.dto.SessionContext;
import com.carddemo.reporting.service.ReportService;

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
 * :purpose: Freezes the authentication contract of the reporting service: every business
 *     route must reject a caller that presents no signed-on session, and the rejection
 *     must happen in the filter chain - before any business logic runs - which is
 *     asserted by proving the service collaborator is never touched. It also pins the
 *     request-size cap and the hardened response headers, which are cross-cutting but
 *     must hold on this service's own port and not only behind the gateway.
 * :note: The production filter chain is deliberately active here (no ``addFilters =
 *     false``): this test exists precisely to exercise it. A request authenticates the
 *     way production does, by presenting the shared session context published at
 *     sign-on.
 */
@SpringBootTest
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

    @Autowired
    private MockMvc mockMvc;

    /** :purpose: Spied so the test can prove the request never reached business logic. */
    @MockitoSpyBean
    private ReportService reportService;

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
    @DisplayName("an anonymous read is rejected with 401 and never reaches the service")
    void anonymousReadIsRejected() throws Exception {
        // /reports exposes POST only; an anonymous GET must still be answered by the
        // filter chain with 401 rather than by the dispatcher with 405, because the
        // caller has no business learning which methods exist.
        mockMvc.perform(get("/reports")).andExpect(status().isUnauthorized());

        verifyNoInteractions(reportService);
    }

    @Test
    @DisplayName("an anonymous write is rejected with 401 and never reaches the service")
    void anonymousWriteIsRejected() throws Exception {
        mockMvc.perform(post("/reports")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reportType\":\"MONTHLY\",\"confirm\":\"Y\"}"))
                .andExpect(status().isUnauthorized());

        verifyNoInteractions(reportService);
    }

    @Test
    @DisplayName("a signed-on session passes authorization and reaches the service")
    void signedOnRequestPassesAuthorization() throws Exception {
        int status = mockMvc.perform(post("/reports")
                        .sessionAttr(SessionContext.SESSION_ATTRIBUTE_NAME, userSession())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reportType\":\"MONTHLY\",\"confirm\":\"Y\"}"))
                .andReturn()
                .getResponse()
                .getStatus();

        assertThat(status).isNotIn(401, 403);
    }

    /**
     * :purpose: Build the signed-on session context an administrator presents.
     * :returns: an administrator session context.
     */
    private SessionContext adminSession() {
        SessionContext session = new SessionContext();
        session.setUserId("ADMIN001");
        session.setUserType(SessionContext.UserType.CDEMO_USRTYP_ADMIN);
        return session;
    }

    @Test
    @DisplayName("the CREASTMT statement stream is refused for a signed-on regular user")
    void statementStreamIsAdministratorOnly() throws Exception {
        mockMvc.perform(post("/reports/statements")
                        .sessionAttr(SessionContext.SESSION_ATTRIBUTE_NAME, userSession()))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("the CREASTMT statement stream passes authorization for an administrator")
    void statementStreamIsAuthorizedForAnAdministrator() throws Exception {
        int status = mockMvc.perform(post("/reports/statements")
                        .sessionAttr(SessionContext.SESSION_ATTRIBUTE_NAME, adminSession()))
                .andReturn()
                .getResponse()
                .getStatus();

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
        byte[] oversized =
                new byte[(int) (com.carddemo.common.config.RequestSizeLimitFilter.DEFAULT_MAX_BODY_BYTES + 1024)];
        Arrays.fill(oversized, (byte) 'A');

        mockMvc.perform(post("/reports")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(oversized))
                .andExpect(status().isContentTooLarge());
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
