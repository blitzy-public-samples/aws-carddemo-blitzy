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
package com.carddemo.gateway;

import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * :purpose: Boots the full api-gateway Spring context with a mocked Redis
 *     connection factory so the whole re-platformed wiring (Spring Cloud
 *     Gateway server-webmvc routing, Spring Security, Spring Session, and the
 *     imported carddemo-common observability and exception-handling beans)
 *     loads successfully without a running Redis instance. This smoke test
 *     guards the gateway that re-platforms the legacy CICS menu programs
 *     COMEN01C (main menu, CM00) and COADM01C (admin menu, CA00).
 */
@SpringBootTest
@TestPropertySource(properties = {
        "spring.session.redis.configure-action=none",
        "spring.data.redis.repositories.enabled=false",
        "management.health.redis.enabled=false",
        "spring.autoconfigure.exclude="
                + "org.springframework.boot.data.redis.autoconfigure.DataRedisReactiveAutoConfiguration"
})
class GatewayApplicationTests {

    /**
     * :purpose: Replaces the auto-configured Redis connection factory with a
     *     Mockito mock so Spring Session binds against it and no live Redis
     *     connection is opened during context startup.
     */
    @MockitoBean
    private RedisConnectionFactory redisConnectionFactory;

    /**
     * :purpose: The fully-initialized application context under assertion.
     */
    @Autowired
    private ApplicationContext applicationContext;

    /**
     * :purpose: Asserts the full application context loaded and the key servlet
     *     gateway beans (the Spring Security filter chain and the correlation-id
     *     propagation filter registration) are present.
     */
    @Test
    void contextLoads() {
        Mockito.lenient().when(redisConnectionFactory.getConnection())
                .thenReturn(Mockito.mock(RedisConnection.class));
        assertThat(applicationContext).isNotNull();
        // Servlet Spring Security filter chain is configured (SecurityConfig).
        assertThat(applicationContext.getBeanNamesForType(
                org.springframework.security.web.SecurityFilterChain.class)).isNotEmpty();
        // Correlation-id propagation filter registration (GatewayRoutesConfig).
        assertThat(applicationContext.getBeanNamesForType(
                org.springframework.boot.web.servlet.FilterRegistrationBean.class)).isNotEmpty();
    }
}
