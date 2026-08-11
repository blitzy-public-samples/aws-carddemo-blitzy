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
package com.carddemo.common.testsupport;

import org.springframework.test.context.DynamicPropertyRegistry;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * One real Redis for every session-wiring test in a module.
 *
 * :purpose: Let a service boot its PRODUCTION session wiring - Spring Data Redis plus
 *     Spring Session - against a real ``redis:8`` instead of excluding that
 *     auto-configuration to keep the context loadable. The COMMAREA replacement
 *     (AAP 0.6.3) lives entirely in that wiring, so a test that switches it off cannot
 *     observe it, and a regression in the namespace, the serializer or the repository
 *     filter would reach a deployment unnoticed.
 * :output: A started container shared by the whole test JVM, and a
 *     ``spring.data.redis.*`` binding for a ``@DynamicPropertySource`` method.
 *
 * The container is started once from the static initializer and left for the
 * Testcontainers reaper, which mirrors
 * :java:class:`com.carddemo.common.testsupport.MigratedSchemaContainer` so a module pays
 * for at most one PostgreSQL and one Redis however many classes bind them.
 */
public final class SessionRedisContainer {

    /** :purpose: Production Redis major version, pinned to match deployments. */
    private static final DockerImageName IMAGE = DockerImageName.parse("redis:8");

    /** :purpose: Redis' fixed in-container port. */
    private static final int REDIS_PORT = 6379;

    /** :purpose: The shared container for this JVM. */
    private static final GenericContainer<?> CONTAINER;

    static {
        CONTAINER = new GenericContainer<>(IMAGE).withExposedPorts(REDIS_PORT);
        CONTAINER.start();
    }

    /**
     * :purpose: Prevent instantiation of this static holder.
     */
    private SessionRedisContainer() {
        throw new AssertionError("SessionRedisContainer is a static holder");
    }

    /**
     * Returns the host the container is reachable on.
     *
     * :output: the Docker host name Testcontainers resolved.
     */
    public static String host() {
        return CONTAINER.getHost();
    }

    /**
     * Returns the ephemeral host port mapped to Redis.
     *
     * :output: the mapped port for {@code 6379}.
     */
    public static int port() {
        return CONTAINER.getMappedPort(REDIS_PORT);
    }

    /**
     * Binds the container's coordinates to a Spring test context.
     *
     * :param registry: the registry supplied by a ``@DynamicPropertySource`` method.
     * :output: ``spring.data.redis.host`` and ``spring.data.redis.port`` resolved to the
     *     shared container, so the service's own Spring Session configuration - namespace,
     *     flush mode and serializer - is the wiring under test.
     */
    public static void registerConnection(DynamicPropertyRegistry registry) {
        registry.add("spring.data.redis.host", SessionRedisContainer::host);
        registry.add("spring.data.redis.port", SessionRedisContainer::port);
    }
}
