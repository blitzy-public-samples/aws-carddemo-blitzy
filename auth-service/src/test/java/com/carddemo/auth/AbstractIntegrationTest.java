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
package com.carddemo.auth;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import com.carddemo.common.testsupport.MigratedSchemaContainer;

import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

/**
 * :purpose: Shared base class for auth-service integration tests. It binds the
 *     shared, already-migrated PostgreSQL (``postgres:18``) container from
 *     :java:class:`com.carddemo.common.testsupport.MigratedSchemaContainer` - whose
 *     schema is produced exclusively by the committed Flyway migrations of every
 *     owning module - and starts a single Redis (``redis:8``) container for the whole
 *     test run, injecting their runtime coordinates into the Spring ``Environment``
 *     via ``@DynamicPropertySource`` so that Spring Data JPA, Flyway, and Spring
 *     Session (Redis) bind to the real containers. Subclasses extend this class to
 *     obtain the full application context under the ``test`` profile, where
 *     ``ddl-auto: validate`` verifies every entity mapping against that schema.
 */
@SpringBootTest
@Testcontainers
@ActiveProfiles("test")
public abstract class AbstractIntegrationTest {

    protected static final GenericContainer<?> REDIS =
            new GenericContainer<>(DockerImageName.parse("redis:8"))
                    .withExposedPorts(6379);

    static {
        REDIS.start();
    }

    /**
     * :purpose: Registers the container-assigned datasource and Redis
     *     coordinates (and enables Flyway) so the Spring context connects to
     *     the running Testcontainers instances rather than to any hardcoded
     *     host/port.
     * :param registry: the Spring dynamic property registry.
     */
    @DynamicPropertySource
    static void registerDynamicProperties(DynamicPropertyRegistry registry) {
        MigratedSchemaContainer.registerDataSource(registry);
        registry.add("spring.data.redis.host", REDIS::getHost);
        registry.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379));
        registry.add("spring.flyway.enabled", () -> true);
    }
}
