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
package com.carddemo.common.startup;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.connection.RedisConnectionFactory;

import java.nio.charset.StandardCharsets;
import java.time.Duration;

/**
 * :purpose: Warm the Redis path of a service before it reports readiness. Every
 *     authenticated request in this deployment reads its session from Redis (the externalised
 *     replacement for the legacy COMMAREA, AAP 0.6.3), so the Lettuce connection handshake,
 *     its codec and its command pipeline sit on the critical path of the very first request.
 *     Establishing them here removes that cost from it.
 * :output: One connection is taken from the factory, a ``PING`` is issued and the
 *     existence of a single reserved key is checked, then the connection is returned to the
 *     pool. Returns the number of commands completed. Nothing is written and no session key is
 *     read, so no caller's state is touched.
 * :note: Applies only where Redis is on the classpath and a connection factory has been
 *     configured, so a service or a test context without Redis simply has no such task. A
 *     failure here is reported by the runner and does not stop start-up: Redis availability is
 *     already expressed through the readiness health group, which is the signal that decides
 *     whether the instance takes traffic.
 */
public class RedisWarmUpTask implements WarmUpTask {

    /** :purpose: Reports Redis warm-up problems without failing start-up. */
    private static final Logger log = LoggerFactory.getLogger(RedisWarmUpTask.class);

    /**
     * :purpose: A key this deployment never stores, so the existence check is a pure
     *     read that can never observe or disturb a session.
     */
    private static final byte[] PROBE_KEY = "carddemo:warmup:probe".getBytes(StandardCharsets.UTF_8);

    /**
     * :purpose: Provider for the factory whose connection is established. Resolved when
     *     the task runs, so a context that excluded Redis simply has nothing to warm and
     *     no auto-configuration ordering has to be declared.
     */
    private final ObjectProvider<RedisConnectionFactory> connectionFactories;

    /**
     * :purpose: Construct the task.
     * :param connectionFactories: provider for the service's Redis connection factory.
     */
    public RedisWarmUpTask(ObjectProvider<RedisConnectionFactory> connectionFactories) {
        this.connectionFactories = connectionFactories;
    }

    /**
     * :purpose: Identify the task in the start-up log.
     * :returns: ``redis``.
     */
    @Override
    public String name() {
        return "redis";
    }

    /**
     * :purpose: Establish the connection and issue two read-only commands.
     * :param budget: the time this task may take; the commands are bounded by the
     *     Lettuce command timeout the service already configures, which is well inside
     *     any budget this task is given.
     * :returns: the number of commands completed.
     */
    @Override
    public int warmUp(Duration budget) {
        RedisConnectionFactory connectionFactory = connectionFactories.getIfAvailable();
        if (connectionFactory == null) {
            log.debug("No Redis connection factory available; the session store path is not warmed");
            return 0;
        }
        try (RedisConnection connection = connectionFactory.getConnection()) {
            connection.ping();
            connection.keyCommands().exists(PROBE_KEY);
            return 2;
        } catch (Exception e) {
            log.debug("Redis warm-up did not complete: {}", e.toString());
            return 0;
        }
    }
}
