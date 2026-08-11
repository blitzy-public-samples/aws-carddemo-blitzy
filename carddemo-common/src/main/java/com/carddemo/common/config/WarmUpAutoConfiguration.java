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
package com.carddemo.common.config;

import com.carddemo.common.startup.DatabaseWarmUpTask;
import com.carddemo.common.startup.ReadinessWarmUp;
import com.carddemo.common.startup.RedisWarmUpTask;
import com.carddemo.common.startup.WarmUpTask;

import jakarta.persistence.EntityManagerFactory;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.data.redis.connection.RedisConnectionFactory;

import javax.sql.DataSource;

import java.time.Duration;
import java.util.List;

/**
 * :purpose: Register the start-up warm-up that makes a service's readiness signal mean
 *     "able to serve inside the 200 ms 95th-percentile target of AAP 0.7.1", rather than only
 *     "the process started". Every task is contributed conditionally, so one shared
 *     configuration covers a JPA service, the Redis-only gateway and a test context that has
 *     neither, and no service needs warm-up code of its own.
 * :output: A {@link ReadinessWarmUp} application runner plus the {@link WarmUpTask} beans
 *     applicable to the service: ``database`` where a datasource and an entity manager factory
 *     are present, and ``redis`` where a connection factory is present.
 * :note: Ordering is intentional, and so is the budget arithmetic behind it. Each task
 *     receives an equal share of what REMAINS of the budget, so the cheap fixed-cost tasks run
 *     first and donate their unused time to the ones that benefit from repetition: ``redis``
 *     (order 10) establishes the session store's connection, ``database`` (20) pre-fills the
 *     pool and compiles the persistence read paths, and a service's own read path —
 *     contributed by that service as a {@code LoopingWarmUpTask} at order 25 — receives all of
 *     the remainder, because measurement showed the controller-to-DTO path to be the expensive
 *     half.
 * :note: Warm-up deliberately issues NO HTTP request to the service that is starting. An
 *     earlier revision looped over the instance's own ``/actuator/health/liveness`` to compile
 *     the servlet, security and dispatcher layers; those requests arrive from loopback with no
 *     caller identity, so {@code RateLimitFilter} counts them under the SAME anonymous
 *     per-source-address budget the container healthcheck and the Kubernetes probes use, and a
 *     warm-up loop exhausts it in seconds. The instance then answered its own liveness probe
 *     with 429 while warming — a start-up that can fail its own health gate — and the
 *     api-gateway integration tests, which probe over loopback too, saw 429 in place of 200.
 *     Warming the request path is not worth spending a budget that exists to protect
 *     availability, so each service warms its own read path directly instead.
 * :note: Set ``carddemo.warmup.enabled=false`` to disable warm-up entirely — the test
 *     profiles do, so no test pays for it.
 */
@AutoConfiguration
@ConditionalOnProperty(prefix = "carddemo.warmup", name = "enabled", matchIfMissing = true)
public class WarmUpAutoConfiguration {

    /**
     * :purpose: Assemble the runner from whichever tasks this service contributed.
     * :param warmUpTasks: provider for the applicable warm-up tasks. Resolved as an ordered
     *     stream so the {@link Order} on each task decides the sequence and a service with no
     *     applicable task yields an empty list rather than a failed injection.
     * :param budgetSeconds: total seconds warm-up may add to start-up, and the reason this is
     *     a BUDGET rather than a fixed amount of work: HotSpot needs thousands of invocations to
     *     leave the interpreter, so the tasks loop for the time they are given and a faster host
     *     simply converges sooner and stops. The default of 30 s is a CEILING rather than a cost:
     *     the read-path task stops as soon as its measured duration converges, and the container
     *     healthcheck grace period and the Kubernetes startup probe both allow more than this
     *     ceiling, so a service that does spend all of it is still never restarted for it.
     * :returns: the configured runner.
     */
    @Bean
    public ReadinessWarmUp carddemoReadinessWarmUp(
            ObjectProvider<WarmUpTask> warmUpTasks,
            @Value("${carddemo.warmup.budget-seconds:30}") long budgetSeconds) {
        List<WarmUpTask> tasks = warmUpTasks.orderedStream().toList();
        return new ReadinessWarmUp(tasks, Duration.ofSeconds(Math.max(1L, budgetSeconds)));
    }

    /**
     * :purpose: Contribute the persistence warm-up where the service maps entities.
     */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(EntityManagerFactory.class)
    static class DatabaseWarmUpConfiguration {

        /**
         * :purpose: Pre-establish pooled connections and warm every mapped entity's read path.
         * :param dataSources: provider for the service's pooled datasource; a service with no
         *     persistence simply has nothing to warm.
         * :param entityManagerFactories: provider for the service's entity manager factory.
         * :param connections: simultaneous connections to pre-establish. The default of 8 is half
         *     of the 16-connection ceiling each service is allowed: enough to absorb the arrival burst
         *     that a pool idling at its 2-connection minimum could not, while leaving the
         *     whole-deployment steady state untouched because HikariCP retires the extra connections
         *     again once they idle.
         * :param concurrency: threads issuing the reads, which is also what causes the pool to be
         *     genuinely exercised rather than serially reused.
         * :param databaseSeconds: cap on this task's share of the budget, so the rest is left to
         *     the service's own read path — the one measurement showed to be expensive.
         * :returns: the task.
         */
        @Bean
        @Order(20)
        public WarmUpTask carddemoDatabaseWarmUpTask(
                ObjectProvider<DataSource> dataSources,
                ObjectProvider<EntityManagerFactory> entityManagerFactories,
                @Value("${carddemo.warmup.connections:8}") int connections,
                @Value("${carddemo.warmup.concurrency:32}") int concurrency,
                @Value("${carddemo.warmup.database-seconds:3}") long databaseSeconds) {
            return new DatabaseWarmUpTask(dataSources, entityManagerFactories, connections, concurrency,
                    Duration.ofSeconds(Math.max(1L, databaseSeconds)));
        }
    }

    /**
     * :purpose: Contribute the Redis warm-up where a connection factory is configured.
     */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(RedisConnectionFactory.class)
    static class RedisWarmUpConfiguration {

        /**
         * :purpose: Establish the session store's connection before the first request
         *     needs it.
         * :param connectionFactories: provider for the service's Redis connection
         *     factory.
         * :returns: the task.
         */
        @Bean
        @Order(10)
        public WarmUpTask carddemoRedisWarmUpTask(
                ObjectProvider<RedisConnectionFactory> connectionFactories) {
            return new RedisWarmUpTask(connectionFactories);
        }
    }

}
