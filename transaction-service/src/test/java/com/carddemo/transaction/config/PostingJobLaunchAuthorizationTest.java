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
package com.carddemo.transaction.config;

import com.carddemo.common.config.GlobalExceptionHandler;
import com.carddemo.common.dto.SessionAttributes;
import com.carddemo.common.dto.SessionContext;
import com.carddemo.transaction.controller.TransactionController;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;

/**
 * :purpose: Pin the authority transaction-service itself requires on the ``POSTTRAN``
 *     posting-job launch surface, independently of the api-gateway. ``CBTRN02C`` had no
 *     CICS transaction: the legacy job stream was submitted to JES by an operator, and the
 *     run POSTS FINANCIAL MOVEMENTS - it rewrites account balances and inserts transaction
 *     rows [app/jcl/POSTTRAN.jcl, app/cbl/CBTRN02C.cbl]. A signed-on ``ROLE_USER``
 *     therefore must not reach it, while the online inquiry screens that share the
 *     ``/transactions`` prefix (``COTRN00C``/``COTRN01C``/``COTRN02C``) must stay open.
 * :output: Assertions that a user session is refused ``403`` on ``/transactions/batch/**``
 *     and admitted on the online transaction surface, and that an administrator session
 *     passes the authorization gate on the launch surface.
 * :note: The gateway enforces the same rule at the edge; this test proves the rule holds
 *     for a caller that reaches the service directly on the cluster network, so the gate
 *     does not depend on the edge alone.
 */
@WebMvcTest(TransactionController.class)
@Import({SecurityConfig.class, GlobalExceptionHandler.class})
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@DisplayName("POSTTRAN launch-surface authorization (transaction-service chain)")
class PostingJobLaunchAuthorizationTest {

    @Autowired
    private WebApplicationContext webApplicationContext;

    // The controller under slice is not the subject here. The transaction-service main class
    // declares an explicit @EnableJpaRepositories and imports the JDBC batch infrastructure,
    // so the web slice eagerly registers persistence and batch beans that reach for a real
    // DataSource. Each is overridden with a mock - exactly as TransactionControllerTest does -
    // so the context starts without a database and every request reaches the security chain,
    // which is the only thing this class asserts on. None of them is ever exercised.

    /** :purpose: Mocked online transaction service (the sliced controller's collaborator). */
    @MockitoBean
    private com.carddemo.transaction.service.TransactionService transactionService;

    /** :purpose: Mock standing in for the transaction master repository bean. */
    @MockitoBean
    private com.carddemo.transaction.repository.TransactionRepository transactionRepository;

    /** :purpose: Mock standing in for the card cross-reference repository bean. */
    @MockitoBean
    private com.carddemo.transaction.repository.CardXrefRepository cardXrefRepository;

    /** :purpose: Mock standing in for the account master repository bean. */
    @MockitoBean
    private com.carddemo.transaction.repository.AccountRepository accountRepository;

    /** :purpose: Mock standing in for the daily-transaction feed repository bean. */
    @MockitoBean
    private com.carddemo.transaction.repository.DailyTransactionRepository dailyTransactionRepository;

    /** :purpose: Mock standing in for the transaction-category-balance repository bean. */
    @MockitoBean
    private com.carddemo.transaction.repository.TranCatBalRepository tranCatBalRepository;

    /** :purpose: Mock standing in for the transaction-category reference repository bean. */
    @MockitoBean
    private com.carddemo.transaction.repository.TranCatgRepository tranCatgRepository;

    /** :purpose: Mock standing in for the transaction-type reference repository bean. */
    @MockitoBean
    private com.carddemo.transaction.repository.TranTypeRepository tranTypeRepository;

    /**
     * :purpose: Mock ``entityManagerFactory`` satisfying the shared-EntityManager and
     *  metamodel singletons the explicit ``@EnableJpaRepositories`` registers eagerly.
     */
    @MockitoBean(name = "entityManagerFactory", answers = org.mockito.Answers.RETURNS_MOCKS)
    private jakarta.persistence.EntityManagerFactory entityManagerFactory;

    /**
     * :purpose: Mock ``jobRepository``; the real factory reaches for a ``DataSource`` and
     *  provisions the ``BATCH_*`` schema, which a web slice has no business doing.
     */
    @MockitoBean
    private org.springframework.batch.core.repository.JobRepository jobRepository;

    /** :purpose: Mock ``transactionManager``, resolved BY NAME by the batch infrastructure. */
    @MockitoBean(name = "transactionManager")
    private org.springframework.transaction.PlatformTransactionManager transactionManager;

    private MockMvc mockMvc;

    /**
     * :purpose: Build the MockMvc instance with the service's real security chain applied.
     */
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

    /**
     * :purpose: A signed-on ROLE_USER is refused the whole posting-job launch surface.
     */
    @Test
    @DisplayName("a signed-on USER is refused the posting-job launch surface")
    void userIsRefusedThePostingLaunchSurface() throws Exception {
        MockHttpSession session =
                signedOnSession("USER0001", SessionContext.UserType.CDEMO_USRTYP_USER);

        mockMvc.perform(post("/transactions/batch/jobs/transactionPostingJob")
                        .param("postingDate", "2026-09-02").session(session))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/transactions/batch/jobs").session(session))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/transactions/batch/jobs/executions/1").session(session))
                .andExpect(status().isForbidden());
    }

    /**
     * :purpose: A signed-on ROLE_ADMIN passes the gate (the slice carries no launch
     *     controller, so the request resolves to 404 rather than 403 - the authorization
     *     outcome is the assertion).
     */
    @Test
    @DisplayName("a signed-on ADMIN passes the gate on the posting-job launch surface")
    void adminPassesTheGate() throws Exception {
        MockHttpSession session =
                signedOnSession("ADMIN001", SessionContext.UserType.CDEMO_USRTYP_ADMIN);

        mockMvc.perform(post("/transactions/batch/jobs/transactionPostingJob")
                        .param("postingDate", "2026-09-02").session(session))
                .andExpect(status().isNotFound());
    }

    /**
     * :purpose: An anonymous caller is refused with 401, not 403, so the SPA can send the
     *     operator back to the sign-on screen.
     */
    @Test
    @DisplayName("an anonymous caller is refused with 401")
    void anonymousIsRefused() throws Exception {
        mockMvc.perform(post("/transactions/batch/jobs/transactionPostingJob"))
                .andExpect(status().isUnauthorized());
    }
}
