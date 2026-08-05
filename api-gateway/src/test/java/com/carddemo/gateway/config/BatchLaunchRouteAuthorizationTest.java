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
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
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
 * :purpose: Pin the gateway authority of the batch job-launch routes, the same way
 *     {@link StatementRouteAuthorizationTest} pins the statement stream. Eight of the
 *     nine launchable jobs re-platform JCL streams (``INTCALC``, ``READACCT``,
 *     ``READCARD``, ``READCUST``, ``READXREF``, ``PRTCATBL``, ``COMBTRAN`` and the
 *     daily-transaction validation stream) that had NO CICS transaction, so no online
 *     user path to them ever existed and their launches require ``ROLE_ADMIN``. The
 *     ``CBTRN03C`` / ``TRANREPT`` transaction-detail report is reached from main-menu
 *     option 9, a ``userType 'U'`` option, so a signed-on user may launch it.
 * :output: Assertions over the authorization outcome of each launch route and of the
 *     read-only launch-surface routes.
 * :note: The class holds its own Spring slice context. The ``csrf()`` post-processor
 *     swaps the configured double-submit repository for a session-backed test
 *     repository inside whatever chain it touches, so it must not run against the
 *     chain other gateway slice tests cache. An authorized route resolves to 404
 *     because the gateway carries no local handler for it — the proxy route is the
 *     concern of {@code GatewayRoutesConfigTest}, not of this authorization test.
 */
@WebMvcTest(MenuController.class)
@Import({SecurityConfig.class, GlobalExceptionHandler.class})
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class BatchLaunchRouteAuthorizationTest {

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

    @ParameterizedTest
    @ValueSource(strings = {
            "interestCalculationJob",
            "categoryBalanceReportJob",
            "dailyTransactionValidationJob",
            "accountReadJob",
            "cardReadJob",
            "cardXrefReadJob",
            "customerReadJob",
            "combineTransactionsJob"})
    @DisplayName("a signed-on USER is refused every operator-only job launch")
    void userIsRefusedOperatorOnlyJobLaunch(String jobName) throws Exception {
        MockHttpSession session =
                signedOnSession("USER0001", SessionContext.UserType.CDEMO_USRTYP_USER);

        mockMvc.perform(post("/batch/jobs/{jobName}", jobName).session(session).with(csrf()))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("a signed-on USER is authorized for the transaction-detail report launch")
    void userIsAuthorizedForTheTransactionDetailReport() throws Exception {
        MockHttpSession session =
                signedOnSession("USER0001", SessionContext.UserType.CDEMO_USRTYP_USER);

        mockMvc.perform(post("/batch/jobs/transactionDetailReportJob")
                        .session(session).with(csrf()))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("a signed-on ADMIN is authorized for an operator-only job launch")
    void adminIsAuthorizedForOperatorOnlyJobLaunch() throws Exception {
        MockHttpSession session =
                signedOnSession("ADMIN001", SessionContext.UserType.CDEMO_USRTYP_ADMIN);

        mockMvc.perform(post("/batch/jobs/interestCalculationJob").session(session).with(csrf()))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("the read-only launch-surface routes stay open to a signed-on USER")
    void readOnlyLaunchRoutesStayOpenToAUser() throws Exception {
        MockHttpSession session =
                signedOnSession("USER0001", SessionContext.UserType.CDEMO_USRTYP_USER);

        mockMvc.perform(get("/batch/jobs").session(session)).andExpect(status().isNotFound());
        mockMvc.perform(get("/batch/jobs/executions/42").session(session))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("an anonymous caller is refused a job launch with 401")
    void anonymousIsRefusedAJobLaunch() throws Exception {
        mockMvc.perform(post("/batch/jobs/interestCalculationJob").with(csrf()))
                .andExpect(status().isUnauthorized());
    }
}
