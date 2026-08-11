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
package com.carddemo.card.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.util.Set;

import com.carddemo.common.config.RemovedSessionTolerantSessionRepository;
import com.carddemo.common.dto.SessionContext;
import com.carddemo.common.testsupport.MigratedSchemaContainer;
import com.carddemo.common.testsupport.SessionRedisContainer;

import jakarta.servlet.Filter;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.session.Session;
import org.springframework.session.SessionRepository;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * Boots ``card-service`` with its PRODUCTION session wiring against a real Redis.
 *
 * :purpose: Cover the externalized-session mechanism that replaces the CICS COMMAREA
 *     (AAP 0.6.3) in THIS service, where the card list, detail and update screens (``COCRDLIC`` / ``COCRDSLC`` / ``COCRDUPC``) carry the session. Every other suite in the module runs under the ``test``
 *     profile, which excludes the Spring Data Redis and Spring Session
 *     auto-configuration so a context loads without a Redis; that keeps the suites fast
 *     but leaves the wiring a deployment actually uses - the repository filter, the
 *     ``carddemo:session`` namespace and the allowlisted-JSON serializer - unexercised, so
 *     a regression in any of them would only have been caught by the api-gateway's single
 *     round-trip test.
 * :output: A Failsafe (``*IT``) test that clears the exclusions, binds a real ``redis:8``,
 *     and asserts the session filter is registered, the repository is Redis-backed, a
 *     ``SessionContext`` survives the round trip through Redis with its frozen
 *     ``CDEMO-USRTYP-*`` / ``CDEMO-PGM-*`` values, the key carries the configured
 *     namespace, and the stored payload is JSON rather than native Java serialization.
 *
 * The Redis and PostgreSQL containers are the module-shared ones, so this class adds one
 * Redis to the module's cost and no second database.
 */
@SpringBootTest(properties = {
        // Reset the `test` profile's exclusion list: this class exists to boot the
        // auto-configuration the other suites switch off.
        "spring.autoconfigure.exclude="
})
@ActiveProfiles("test")
class SessionRedisWiringIT {

    /** :purpose: The namespace every service must resolve, or the shared session breaks. */
    private static final String SESSION_NAMESPACE = "carddemo:session";

    /** :purpose: Signed-on user id stamped onto the session under test. */
    private static final String USER_ID = "ADMIN001";

    /**
     * :purpose: Bind the shared migrated database and the shared Redis.
     * :param registry: the registry supplied by the Spring Test context.
     */
    @DynamicPropertySource
    static void containerProperties(DynamicPropertyRegistry registry) {
        MigratedSchemaContainer.registerDataSource(registry);
        SessionRedisContainer.registerConnection(registry);
    }

    @Autowired
    private ApplicationContext applicationContext;

    @Autowired
    private StringRedisTemplate stringRedisTemplate;

    /**
     * :purpose: The configured Spring Session repository. The wildcard keeps the assertions
     *     independent of which concrete Redis repository the configuration selects.
     */
    @Autowired
    private SessionRepository<? extends Session> sessionRepository;

    /**
     * :purpose: The servlet filter that makes ``HttpSession`` resolve to the Redis-backed
     *     session must be registered, or every request would silently get a container
     *     session and the COMMAREA equivalent would not survive a second request.
     */
    @Test
    @DisplayName("the Spring Session repository filter is registered and Redis-backed")
    void sessionRepositoryFilterIsRegisteredAndRedisBacked() {
        assertThat(applicationContext.containsBean("springSessionRepositoryFilter")).isTrue();
        assertThat(applicationContext.getBean("springSessionRepositoryFilter", Filter.class))
                .isNotNull();
        // Every SessionRepository bean is wrapped for tolerance of a session a PEER service
        // removed (RemovedSessionTolerantSessionRepository, installed by SessionRedisConfig),
        // so the store is asserted BEHIND that wrapper: the wrapper IS the production wiring,
        // and what has to be Redis is what it delegates to.
        SessionRepository<? extends Session> store =
                sessionRepository instanceof RemovedSessionTolerantSessionRepository<?> tolerant
                        ? tolerant.delegate()
                        : sessionRepository;
        assertThat(store.getClass().getName())
                .as("the session store must be Redis, not an in-memory map")
                .contains("Redis");
    }

    /**
     * :purpose: A ``SessionContext`` written through the repository lands in Redis under the
     *     configured namespace and reads back with its frozen COMMAREA values intact.
     */
    @Test
    @DisplayName("a SessionContext round-trips through Redis under the carddemo:session namespace")
    void sessionContextRoundTripsUnderConfiguredNamespace() {
        Session session = sessionRepository.createSession();
        SessionContext context = new SessionContext();
        context.setUserId(USER_ID);
        context.setUserType(SessionContext.UserType.CDEMO_USRTYP_ADMIN);
        context.setProgramContext(SessionContext.ProgramContext.CDEMO_PGM_ENTER);
        session.setAttribute(SessionContext.SESSION_ATTRIBUTE_NAME, context);
        save(session);

        Set<String> keys = stringRedisTemplate.keys(SESSION_NAMESPACE + ":sessions:*");
        assertThat(keys)
                .as("the session must be stored under the configured namespace")
                .isNotNull()
                .contains(SESSION_NAMESPACE + ":sessions:" + session.getId());

        Session reloaded = sessionRepository.findById(session.getId());
        assertThat(reloaded).as("the session must be readable back out of Redis").isNotNull();
        SessionContext restored = reloaded.getAttribute(SessionContext.SESSION_ATTRIBUTE_NAME);
        assertThat(restored).isNotNull();
        assertThat(restored.getUserId()).isEqualTo(USER_ID);
        assertThat(restored.getUserType())
                .isEqualTo(SessionContext.UserType.CDEMO_USRTYP_ADMIN);
        assertThat(restored.getProgramContext())
                .isEqualTo(SessionContext.ProgramContext.CDEMO_PGM_ENTER);
    }

    /**
     * :purpose: The stored payload is the allowlisted JSON the shared ``SessionRedisConfig``
     *     serializer produces, not native Java serialization - which would reopen the
     *     deserialization-gadget surface (CWE-502) this service closed.
     */
    @Test
    @DisplayName("the stored session payload is allowlisted JSON, not Java serialization")
    void storedPayloadIsAllowlistedJson() {
        Session session = sessionRepository.createSession();
        SessionContext context = new SessionContext();
        context.setUserId(USER_ID);
        context.setUserType(SessionContext.UserType.CDEMO_USRTYP_ADMIN);
        session.setAttribute(SessionContext.SESSION_ATTRIBUTE_NAME, context);
        save(session);

        String key = SESSION_NAMESPACE + ":sessions:" + session.getId();
        Object raw = stringRedisTemplate.opsForHash()
                .get(key, "sessionAttr:" + SessionContext.SESSION_ATTRIBUTE_NAME);
        assertThat(raw).as("the attribute must be present in the Redis hash").isNotNull();

        String payload = raw.toString();
        // Native Java serialization would begin with the 0xAC 0xED stream magic.
        assertThat(payload.getBytes(StandardCharsets.UTF_8)[0])
                .as("a Java-serialized payload would start with 0xAC")
                .isNotEqualTo((byte) 0xAC);
        assertThat(payload)
                .contains("\"@class\"")
                .contains(SessionContext.class.getName())
                .contains(USER_ID);
    }

    /**
     * :purpose: Persist a mutated session through the configured repository.
     * :param session: the session whose attributes were changed.
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    private void save(Session session) {
        ((SessionRepository) sessionRepository).save(session);
    }
}
