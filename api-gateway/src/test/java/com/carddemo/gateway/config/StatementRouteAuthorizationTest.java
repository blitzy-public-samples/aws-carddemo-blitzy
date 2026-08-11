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
package com.carddemo.gateway.config;

import com.carddemo.common.config.GlobalExceptionHandler;
import com.carddemo.common.dto.SessionAttributes;
import com.carddemo.common.dto.SessionContext;
import com.carddemo.gateway.controller.MenuController;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * :purpose: Pin the gateway authority of the statement job-stream route. ``CORPT00`` is the
 *     only report surface an ordinary signed-on user reached, while the ``CREASTMT`` /
 *     ``CBSTM03A`` statement stream had no online transaction and was submitted by an
 *     operator, so ``POST /reports/statements`` requires ``ROLE_ADMIN`` while
 *     ``POST /reports`` stays open to ``ROLE_USER``.
 * :output: Assertions over the authorization outcome of the two report routes.
 * :note: The class holds its own Spring slice context. The ``csrf()`` post-processor swaps the
 *     configured double-submit repository for a session-backed test repository inside whatever
 *     chain it touches, so it must not run against the chain other gateway slice tests cache.
 */
@WebMvcTest(MenuController.class)
@Import({SecurityConfig.class, GlobalExceptionHandler.class})
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class StatementRouteAuthorizationTest {

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
     * :param userId: the signed-on user id (``SEC-USR-ID``).
     * :param type: the signed-on user type (``SEC-USR-TYPE``).
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
    @DisplayName("a signed-on USER reaches /reports but is refused POST /reports/statements")
    void userIsRefusedTheStatementStream() throws Exception {
        MockHttpSession session =
                signedOnSession("USER0001", SessionContext.UserType.CDEMO_USRTYP_USER);

        // Authorized: the gateway has no local handler for the route, so it resolves to 404.
        mockMvc.perform(get("/reports").session(session)).andExpect(status().isNotFound());

        mockMvc.perform(post("/reports/statements").session(session).with(csrf()))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("a signed-on ADMIN is authorized for POST /reports/statements")
    void adminIsAuthorizedForTheStatementStream() throws Exception {
        MockHttpSession session =
                signedOnSession("ADMIN001", SessionContext.UserType.CDEMO_USRTYP_ADMIN);

        mockMvc.perform(post("/reports/statements").session(session).with(csrf()))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("an anonymous caller is refused POST /reports/statements with 401")
    void anonymousIsRefusedTheStatementStream() throws Exception {
        mockMvc.perform(post("/reports/statements").with(csrf()))
                .andExpect(status().isUnauthorized());
    }
}
