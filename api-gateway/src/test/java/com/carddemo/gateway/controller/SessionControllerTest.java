/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License").
 * You may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.carddemo.gateway.controller;

import com.carddemo.common.config.GlobalExceptionHandler;
import com.carddemo.common.dto.SessionAttributes;
import com.carddemo.common.dto.SessionContext;
import com.carddemo.gateway.config.SecurityConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * :purpose: Pin the server-authoritative identity probe that replaces the COMMAREA
 *     ``CDEMO-USER-ID`` / ``CDEMO-USER-TYPE`` fields the legacy screens received on every
 *     pseudo-conversational turn. The single-page application resolves who is signed on
 *     from this route, so the route must publish exactly the two session fields, must
 *     answer ``401`` whenever the session carries no sign-on context, and must never
 *     create a session of its own.
 * :output: Assertions over the ``GET /session`` status and body, and over the
 *     no-session-created property of the handler.
 */
@WebMvcTest(SessionController.class)
@Import({SecurityConfig.class, GlobalExceptionHandler.class})
class SessionControllerTest {

    @Autowired
    private WebApplicationContext webApplicationContext;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        this.mockMvc = MockMvcBuilders.webAppContextSetup(webApplicationContext)
                .apply(springSecurity())
                .build();
    }

    /**
     * :purpose: Build the session sign-on leaves behind: one COMMAREA-equivalent context.
     * :param userId: the signed-on user id (``SEC-USR-ID`` / ``CDEMO-USER-ID``).
     * :param type: the signed-on user type (``SEC-USR-TYPE`` / ``CDEMO-USER-TYPE``).
     * :returns: a mock session carrying only that context.
     */
    private static MockHttpSession signedOnSession(String userId, SessionContext.UserType type) {
        SessionContext context = new SessionContext();
        context.setUserId(userId);
        context.setUserType(type);
        context.setProgramContext(SessionContext.ProgramContext.CDEMO_PGM_ENTER);
        MockHttpSession session = new MockHttpSession();
        session.setAttribute(SessionAttributes.SESSION_CONTEXT, context);
        return session;
    }

    @Test
    @DisplayName("publishes the administrator identity the session holds (CDEMO-USRTYP-ADMIN)")
    void publishesTheAdministratorIdentity() throws Exception {
        mockMvc.perform(get("/session")
                        .session(signedOnSession("ADMIN001",
                                SessionContext.UserType.CDEMO_USRTYP_ADMIN)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.userId").value("ADMIN001"))
                .andExpect(jsonPath("$.userType").value("A"));
    }

    @Test
    @DisplayName("publishes the standard-user identity the session holds (CDEMO-USRTYP-USER)")
    void publishesTheStandardUserIdentity() throws Exception {
        mockMvc.perform(get("/session")
                        .session(signedOnSession("USER0001",
                                SessionContext.UserType.CDEMO_USRTYP_USER)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.userId").value("USER0001"))
                .andExpect(jsonPath("$.userType").value("U"));
    }

    @Test
    @DisplayName("reports an empty identity when no session has been established")
    void reportsAnEmptyIdentityWithoutASession() throws Exception {
        mockMvc.perform(get("/session"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.userId").value(nullValue()))
                .andExpect(jsonPath("$.userType").value(nullValue()));
    }

    @Test
    @DisplayName("reports an empty identity when the session carries no sign-on context")
    void reportsAnEmptyIdentityForASessionWithoutContext() throws Exception {
        mockMvc.perform(get("/session").session(new MockHttpSession()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.userId").value(nullValue()))
                .andExpect(jsonPath("$.userType").value(nullValue()));
    }

    @Test
    @DisplayName("answers anonymously without creating a session, so the probe cannot mint one")
    void answersWithoutCreatingASession() {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/session");
        SessionController controller = new SessionController();

        SessionController.SessionIdentity identity = controller.currentSession(request);

        assertThat(identity.userId()).isNull();
        assertThat(identity.userType()).isNull();
        assertThat(request.getSession(false)).isNull();
    }
}
