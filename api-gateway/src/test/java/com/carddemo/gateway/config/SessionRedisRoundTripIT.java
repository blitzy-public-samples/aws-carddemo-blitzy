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
package com.carddemo.gateway.config;

import com.carddemo.common.dto.SessionContext;

import jakarta.servlet.Filter;
import jakarta.servlet.http.Cookie;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.session.Session;
import org.springframework.session.SessionRepository;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * :purpose: Integration test of the externalized-session mechanism that replaces the CICS
 *   COMMAREA (AAP 0.6.3): the shared
 *   {@code com.carddemo.common.config.SessionRedisConfig} serializer, exercised against a
 *   REAL ``redis:8``. It proves the whole store -> serialize -> persist -> reload -> update
 *   cycle across separate HTTP requests, which is the behaviour the legacy
 *   ``XCTL``/COMMAREA hand-off provided and which no test previously touched.
 * :output: A Failsafe (``*IT``) test whose scenarios confirm the session is written under
 *   the CONFIGURED Redis namespace, that the ``SessionContext`` survives the JSON
 *   serializer round trip with its ``CDEMO-USRTYP-*``/``CDEMO-PGM-*`` 88-level values and
 *   7-character map name intact, that a second request resolves the SAME session and sees
 *   state a previous request left behind (rather than silently starting a new one), and
 *   that the stored payload is allowlisted JSON rather than native Java serialization.
 */
@SpringBootTest
@Testcontainers
class SessionRedisRoundTripIT {

    /**
     * :purpose: Session attribute name the gateway's ``MenuController`` reads and writes.
     *     Bound to the shared frozen constant rather than repeated as a literal: every
     *     service resolves the COMMAREA equivalent under this one name, so a drift here
     *     would silently look like a lost session instead of a mismatched key.
     */
    private static final String SESSION_CONTEXT_ATTR = SessionContext.SESSION_ATTRIBUTE_NAME;

    /** :purpose: Configured Spring Session key namespace (``spring.session.data.redis.namespace``). */
    private static final String SESSION_NAMESPACE = "carddemo:session";

    /** :purpose: Main-menu transaction id and program the gateway stamps onto the context. */
    private static final String MAIN_TRANID = "CM00";

    /** :purpose: Main-menu program name (``COMEN01C``). */
    private static final String MAIN_PROGRAM = "COMEN01C";

    /** :purpose: Real Redis for the whole class; the session store is never simulated. */
    @Container
    @SuppressWarnings("resource")
    private static final GenericContainer<?> REDIS =
            new GenericContainer<>(DockerImageName.parse("redis:8")).withExposedPorts(6379);

    /**
     * :purpose: Bind the gateway's Redis connection to the container.
     * :param registry: the dynamic property registry supplied by the test context.
     */
    @DynamicPropertySource
    static void redisProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.data.redis.host", REDIS::getHost);
        registry.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379));
    }

    @Autowired
    private WebApplicationContext webApplicationContext;

    @Autowired
    private StringRedisTemplate stringRedisTemplate;

    /**
     * :purpose: The configured Spring Session repository. The wildcard keeps the test
     *   independent of which concrete repository implementation the configuration selects.
     */
    @Autowired
    private SessionRepository<? extends Session> sessionRepository;

    /** :purpose: MockMvc carrying BOTH the Spring Session filter and the security chain. */
    private MockMvc mockMvc;

    /** :purpose: CSRF token issued by the last main-menu request, echoed on writes. */
    private String csrfToken;

    /**
     * :purpose: Build MockMvc over the real servlet wiring: the Spring Session repository
     *   filter is added first so ``HttpSession`` resolves to the Redis-backed session (not a
     *   mock session), then the Spring Security test configurer is applied so authorization
     *   runs and a test principal can be supplied per request. Every Redis key from a
     *   previous scenario is cleared so each scenario starts from an empty store.
     */
    @BeforeEach
    void setUp() {
        Filter sessionRepositoryFilter =
                webApplicationContext.getBean("springSessionRepositoryFilter", Filter.class);
        mockMvc = MockMvcBuilders.webAppContextSetup(webApplicationContext)
                .addFilters(sessionRepositoryFilter)
                .apply(springSecurity())
                .build();

        Set<String> existing = stringRedisTemplate.keys(SESSION_NAMESPACE + ":*");
        if (existing != null && !existing.isEmpty()) {
            stringRedisTemplate.delete(existing);
        }
    }

    /**
     * :purpose: Issue an authenticated main-menu request and return the resulting session id.
     * :param role: the role to authenticate with (``USER`` or ``ADMIN``).
     * :returns: the Spring Session id carried by the response ``SESSION`` cookie.
     */
    private String requestMainMenuAndReturnSessionId(String role) throws Exception {
        MvcResult result = mockMvc.perform(get("/menu").with(user("USER0001").roles(role)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tranId").value(MAIN_TRANID))
                .andExpect(jsonPath("$.programName").value(MAIN_PROGRAM))
                .andReturn();

        // The chain issues the CSRF token as a script-readable cookie, exactly as it does
        // for the SPA; a write is only accepted when that value is echoed back as a header
        // (double submit), so it is captured here for the POST scenario below.
        Cookie csrfCookie = result.getResponse().getCookie(SecurityConfig.CSRF_COOKIE_NAME);
        assertThat(csrfCookie)
                .as("the chain must issue the %s cookie", SecurityConfig.CSRF_COOKIE_NAME)
                .isNotNull();
        this.csrfToken = csrfCookie.getValue();

        Cookie cookie = result.getResponse().getCookie("SESSION");
        assertThat(cookie).as("Spring Session must issue a SESSION cookie").isNotNull();
        return decodeSessionId(cookie.getValue());
    }

    /**
     * :purpose: Decode the Spring Session cookie value to the underlying session id.
     *   ``DefaultCookieSerializer`` Base64-encodes the id in the cookie.
     * :param cookieValue: the raw ``SESSION`` cookie value.
     * :returns: the decoded session id.
     */
    private static String decodeSessionId(String cookieValue) {
        return new String(Base64.getDecoder().decode(cookieValue), StandardCharsets.UTF_8);
    }

    /**
     * :purpose: Persist a mutated session through the repository.
     * :param session: the session whose attributes were changed.
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    private void saveSession(Session session) {
        ((SessionRepository) sessionRepository).save(session);
    }

    /**
     * :purpose: A request that touches the session writes it to Redis under the CONFIGURED
     *   namespace, and the stored ``SessionContext`` carries the values the gateway stamped
     *   on it. This is the store half of the COMMAREA replacement, asserted against the real
     *   store rather than an in-memory stand-in.
     */
    @Test
    @DisplayName("a menu request stores the SessionContext in Redis under the configured namespace")
    void sessionContextIsStoredInRedisUnderConfiguredNamespace() throws Exception {
        String sessionId = requestMainMenuAndReturnSessionId("USER");

        Set<String> keys = stringRedisTemplate.keys(SESSION_NAMESPACE + ":sessions:*");
        assertThat(keys).isNotNull();
        assertThat(keys).contains(SESSION_NAMESPACE + ":sessions:" + sessionId);

        Session stored = sessionRepository.findById(sessionId);
        assertThat(stored).as("the session must be readable back out of Redis").isNotNull();
        SessionContext context = stored.getAttribute(SESSION_CONTEXT_ATTR);
        assertThat(context).isNotNull();
        assertThat(context.getFromTranid()).isEqualTo(MAIN_TRANID);
        assertThat(context.getFromProgram()).isEqualTo(MAIN_PROGRAM);
        assertThat(context.getProgramContext())
                .isEqualTo(SessionContext.ProgramContext.CDEMO_PGM_ENTER);
    }

    /**
     * :purpose: The frozen COMMAREA value contract survives the Redis serializer round trip:
     *   the ``CDEMO-USRTYP-ADMIN`` user type, the ``CDEMO-PGM-REENTER`` program context, the
     *   7-character ``CDEMO-LAST-MAP``/``CDEMO-LAST-MAPSET`` names and the identity fields
     *   all reload unchanged from Redis.
     */
    @Test
    @DisplayName("the COMMAREA 88-level value contract survives the Redis round trip")
    void commareaValueContractSurvivesTheRoundTrip() throws Exception {
        String sessionId = requestMainMenuAndReturnSessionId("ADMIN");

        Session stored = sessionRepository.findById(sessionId);
        assertThat(stored).isNotNull();
        SessionContext outbound = stored.getAttribute(SESSION_CONTEXT_ATTR);
        assertThat(outbound).isNotNull();
        outbound.setUserId("ADMIN001");
        outbound.setUserType(SessionContext.UserType.CDEMO_USRTYP_ADMIN);
        outbound.setProgramContext(SessionContext.ProgramContext.CDEMO_PGM_REENTER);
        outbound.setLastMap("COMEN01");
        outbound.setLastMapset("COMEN01");
        outbound.setCustId(1L);
        outbound.setAcctId(11L);
        outbound.setCardNum("0000000000000000");
        stored.setAttribute(SESSION_CONTEXT_ATTR, outbound);
        saveSession(stored);

        Session reloaded = sessionRepository.findById(sessionId);
        assertThat(reloaded).isNotNull();
        SessionContext inbound = reloaded.getAttribute(SESSION_CONTEXT_ATTR);
        assertThat(inbound).isNotNull();
        assertThat(inbound.getUserId()).isEqualTo("ADMIN001");
        assertThat(inbound.getUserType())
                .isEqualTo(SessionContext.UserType.CDEMO_USRTYP_ADMIN);
        assertThat(inbound.getUserType().getCode()).isEqualTo('A');
        assertThat(inbound.getProgramContext())
                .isEqualTo(SessionContext.ProgramContext.CDEMO_PGM_REENTER);
        assertThat(inbound.getProgramContext().getCode()).isEqualTo(1);
        assertThat(inbound.getLastMap()).isEqualTo("COMEN01").hasSize(7);
        assertThat(inbound.getLastMapset()).isEqualTo("COMEN01").hasSize(7);
        assertThat(inbound.getCustId()).isEqualTo(1L);
        assertThat(inbound.getAcctId()).isEqualTo(11L);
        assertThat(inbound.getCardNum()).isEqualTo("0000000000000000");
    }

    /**
     * :purpose: The retrieve half of the COMMAREA replacement: a SECOND request presenting the
     *   same ``SESSION`` cookie resolves the SAME Redis session and observes state an earlier
     *   request left behind. State written between the two requests and never touched by the
     *   handler (user id, user type, last map) is still present afterwards, which a
     *   silently-recreated session could not reproduce; the session id is unchanged and no
     *   extra session is created.
     */
    @Test
    @DisplayName("a second request resolves the same Redis session and sees earlier state")
    void secondRequestResolvesTheSameSessionAndSeesEarlierState() throws Exception {
        String sessionId = requestMainMenuAndReturnSessionId("USER");

        // State a previous "program" left in the COMMAREA equivalent.
        Session first = sessionRepository.findById(sessionId);
        assertThat(first).isNotNull();
        SessionContext carried = first.getAttribute(SESSION_CONTEXT_ATTR);
        assertThat(carried).isNotNull();
        carried.setUserId("USER0001");
        carried.setUserType(SessionContext.UserType.CDEMO_USRTYP_USER);
        carried.setLastMap("COSGN00");
        first.setAttribute(SESSION_CONTEXT_ATTR, carried);
        saveSession(first);

        String cookieValue = Base64.getEncoder()
                .encodeToString(sessionId.getBytes(StandardCharsets.UTF_8));
        MvcResult second = mockMvc.perform(post("/menu/select")
                        .cookie(new Cookie("SESSION", cookieValue),
                                new Cookie(SecurityConfig.CSRF_COOKIE_NAME, csrfToken))
                        .header(SecurityConfig.CSRF_HEADER_NAME, csrfToken)
                        .cookie(new Cookie("SESSION", cookieValue))
                        .contentType("application/json")
                        .content("{\"option\":\"1\",\"aid\":\"ENTER\"}")
                        .with(user("USER0001").roles("USER")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.dispatched").value(true))
                .andExpect(jsonPath("$.programName").value("COACTVWC"))
                .andExpect(jsonPath("$.targetRoute").value("/accounts"))
                .andReturn();

        // No new session was minted for the second request.
        Cookie reissued = second.getResponse().getCookie("SESSION");
        if (reissued != null) {
            assertThat(decodeSessionId(reissued.getValue())).isEqualTo(sessionId);
        }
        Set<String> keys = stringRedisTemplate.keys(SESSION_NAMESPACE + ":sessions:*");
        assertThat(keys).isNotNull();
        assertThat(keys).containsExactly(SESSION_NAMESPACE + ":sessions:" + sessionId);

        Session afterSecondRequest = sessionRepository.findById(sessionId);
        assertThat(afterSecondRequest).isNotNull();
        SessionContext survived = afterSecondRequest.getAttribute(SESSION_CONTEXT_ATTR);
        assertThat(survived).isNotNull();
        assertThat(survived.getUserId()).isEqualTo("USER0001");
        assertThat(survived.getUserType())
                .isEqualTo(SessionContext.UserType.CDEMO_USRTYP_USER);
        assertThat(survived.getLastMap()).isEqualTo("COSGN00");
        assertThat(survived.getFromTranid()).isEqualTo(MAIN_TRANID);
        assertThat(survived.getFromProgram()).isEqualTo(MAIN_PROGRAM);
    }

    /**
     * :purpose: A PF3 selection on the second request records the sign-on hand-off
     *   (``COSGN00C``/``CC00``) in the shared session, mirroring the legacy ``XCTL`` back to
     *   the sign-on program, and that hand-off is readable from Redis afterwards.
     */
    @Test
    @DisplayName("a PF3 selection records the sign-on hand-off in the shared session")
    void pf3SelectionRecordsSignonHandoffInTheSession() throws Exception {
        String sessionId = requestMainMenuAndReturnSessionId("USER");
        String cookieValue = Base64.getEncoder()
                .encodeToString(sessionId.getBytes(StandardCharsets.UTF_8));

        mockMvc.perform(post("/menu/select")
                        .cookie(new Cookie("SESSION", cookieValue),
                                new Cookie(SecurityConfig.CSRF_COOKIE_NAME, csrfToken))
                        .header(SecurityConfig.CSRF_HEADER_NAME, csrfToken)
                        .cookie(new Cookie("SESSION", cookieValue))
                        .contentType("application/json")
                        .content("{\"option\":\"1\",\"aid\":\"PF3\"}")
                        .with(user("USER0001").roles("USER")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.dispatched").value(true))
                .andExpect(jsonPath("$.targetRoute").value("/auth"));

        Session stored = sessionRepository.findById(sessionId);
        assertThat(stored).isNotNull();
        SessionContext context = stored.getAttribute(SESSION_CONTEXT_ATTR);
        assertThat(context).isNotNull();
        assertThat(context.getToProgram()).isEqualTo("COSGN00C");
        assertThat(context.getToTranid()).isEqualTo("CC00");
    }

    /**
     * :purpose: The session payload is stored as allowlisted JSON by the shared
     *   ``SessionRedisConfig`` serializer, not by native Java serialization. The stored value
     *   is human-readable JSON naming the ``SessionContext`` type, which is what closes the
     *   native-deserialization gadget surface (CWE-502) while keeping the session
     *   interoperable between independently deployed services.
     */
    @Test
    @DisplayName("the stored session attribute is allowlisted JSON, not Java serialization")
    void storedSessionAttributeIsAllowlistedJson() throws Exception {
        String sessionId = requestMainMenuAndReturnSessionId("USER");

        Map<Object, Object> hash = stringRedisTemplate.opsForHash()
                .entries(SESSION_NAMESPACE + ":sessions:" + sessionId);
        assertThat(hash).isNotEmpty();

        Object rawContext = hash.get("sessionAttr:" + SESSION_CONTEXT_ATTR);
        assertThat(rawContext).as("the SessionContext attribute must be present in the hash").isNotNull();
        String json = rawContext.toString();
        assertThat(json).startsWith("{");
        assertThat(json).contains(SessionContext.class.getName());
        assertThat(json).contains(MAIN_PROGRAM);
        // Native Java serialization would start with the 0xAC 0xED stream magic instead.
        assertThat(json).doesNotStartWith("\u00AC\u00ED");
    }
}
