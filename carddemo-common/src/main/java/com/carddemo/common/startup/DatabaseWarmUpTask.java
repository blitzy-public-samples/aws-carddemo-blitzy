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

import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import jakarta.persistence.metamodel.EntityType;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;

import javax.sql.DataSource;

import java.sql.Connection;
import java.sql.Statement;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * :purpose: Warm the persistence path of a service: open the connections a first burst of
 *     traffic would otherwise have to establish, and run one bounded read against every entity
 *     the service maps. Both halves address a measured cost. The connection pool idles at two
 *     connections by deliberate design (the whole deployment shares PostgreSQL's connection
 *     budget), so an instantaneous 150-user arrival has to open the other fourteen while
 *     requests wait; a run whose pool was already grown by earlier traffic met the 200 ms
 *     target on every endpoint, while the same run against a pool at its idle minimum did not.
 *     The per-entity read is what builds Hibernate's persister SQL, its query-plan cache entry
 *     and the JDBC statement for that entity, and drives the JIT through the mapping code —
 *     the work that made a first account view cost 2810 ms at the 95th percentile.
 * :output: Up to ``connections`` pooled connections are opened concurrently, each
 *     validated with ``SELECT 1``, and released; then ``iterations`` bounded ``SELECT``
 *     queries are issued per mapped entity across ``concurrency`` threads. Returns the number
 *     of operations completed. Nothing is written.
 * :note: Every statement is a read. The per-entity query is capped at a single row and
 *     only the identifier of any row returned is touched, so no lazy association is
 *     initialised and an empty table warms the same code path as a populated one. A failure
 *     against one entity is logged at debug and the remaining entities are still warmed, so a
 *     converter or mapping problem on one table cannot leave the rest of the service cold.
 */
public class DatabaseWarmUpTask implements WarmUpTask {

    /** :purpose: Reports per-entity warm-up problems without failing start-up. */
    private static final Logger log = LoggerFactory.getLogger(DatabaseWarmUpTask.class);

    /**
     * :purpose: Provider for the pool whose connections are pre-established. Resolved
     *     when the task runs rather than when it is built, so this task never forces
     *     the datasource to be created early and never depends on the order in which
     *     auto-configurations are evaluated.
     */
    private final ObjectProvider<DataSource> dataSources;

    /** :purpose: Provider for the metamodel and entity managers used for the reads. */
    private final ObjectProvider<EntityManagerFactory> entityManagerFactories;

    /** :purpose: How many connections to hold open simultaneously to grow the pool. */
    private final int connections;

    /** :purpose: How many threads issue the reads, so the pool is genuinely used. */
    private final int concurrency;

    /**
     * :purpose: Upper bound on this task's own share of the warm-up budget. Capped
     *     deliberately: this task's unique contributions — the pre-filled pool and one
     *     compiled persister per mapped entity — are complete after a few seconds, while
     *     the expensive path is the service's own read, which is warmed by a separate
     *     task that should receive the rest of the budget.
     */
    private final Duration cap;

    /**
     * :purpose: Construct the task.
     * :param dataSources: provider for the service's pooled datasource.
     * :param entityManagerFactories: provider for the service's entity manager factory.
     * :param connections: simultaneous connections to pre-establish.
     * :param concurrency: threads used to issue the reads.
     * :param cap: upper bound on this task's share of the warm-up budget.
     */
    public DatabaseWarmUpTask(ObjectProvider<DataSource> dataSources,
                              ObjectProvider<EntityManagerFactory> entityManagerFactories,
                              int connections, int concurrency, Duration cap) {
        this.dataSources = dataSources;
        this.entityManagerFactories = entityManagerFactories;
        this.connections = Math.max(1, connections);
        this.concurrency = Math.max(1, concurrency);
        this.cap = cap;
    }

    /**
     * :purpose: Identify the task in the start-up log.
     * :returns: ``database``.
     */
    @Override
    public String name() {
        return "database";
    }

    /**
     * :purpose: Pre-establish pooled connections and warm every entity's read path.
     * :param budget: the time this task may take.
     * :returns: the number of warm-up operations completed.
     * :raises Exception: if the connection pre-fill itself fails, which means the
     *     database is unreachable and the caller should record that.
     */
    @Override
    public int warmUp(Duration budget) throws Exception {
        DataSource dataSource = dataSources.getIfAvailable();
        EntityManagerFactory entityManagerFactory = entityManagerFactories.getIfAvailable();
        if (dataSource == null || entityManagerFactory == null) {
            // A service with no persistence of its own, or a context that excluded it.
            log.debug("No datasource or entity manager factory available; the persistence path is not warmed");
            return 0;
        }
        long deadline = System.nanoTime() + Math.min(budget.toNanos(), cap.toNanos());
        int operations = prefillPool(dataSource);
        operations += warmEntityReadPaths(entityManagerFactory, deadline);
        return operations;
    }

    /**
     * :purpose: Grow the connection pool by holding several connections at once.
     * :param dataSource: the pool to grow.
     * :returns: the number of connections established.
     * :raises Exception: if no connection can be obtained at all.
     * :note: The connections are acquired together and released together on purpose:
     *     acquiring them one at a time would be satisfied by the same pooled
     *     connection every time and would grow the pool to one.
     */
    private int prefillPool(DataSource dataSource) throws Exception {
        List<Connection> held = new ArrayList<>(connections);
        try {
            for (int i = 0; i < connections; i++) {
                Connection connection;
                try {
                    connection = dataSource.getConnection();
                } catch (Exception e) {
                    if (held.isEmpty()) {
                        throw e;
                    }
                    // The pool's ceiling is below the requested count, or it is
                    // momentarily saturated. What was opened is still warm.
                    log.debug("Connection pre-fill stopped at {} connections: {}", held.size(), e.toString());
                    break;
                }
                held.add(connection);
                try (Statement statement = connection.createStatement()) {
                    statement.execute("SELECT 1");
                }
            }
            return held.size();
        } finally {
            for (Connection connection : held) {
                try {
                    connection.close();
                } catch (Exception e) {
                    log.debug("Releasing a pre-filled connection failed: {}", e.toString());
                }
            }
        }
    }

    /**
     * :purpose: Issue bounded reads against every mapped entity, in parallel.
     * :param entityManagerFactory: the factory whose metamodel names the entities.
     * :param deadline: the {@link System#nanoTime()} value at which to stop.
     * :returns: the number of reads completed.
     * :raises InterruptedException: if the warm-up is interrupted while waiting.
     */
    private int warmEntityReadPaths(EntityManagerFactory entityManagerFactory, long deadline)
            throws InterruptedException {
        List<EntityType<?>> entities = new ArrayList<>(entityManagerFactory.getMetamodel().getEntities());
        if (entities.isEmpty()) {
            return 0;
        }
        AtomicInteger completed = new AtomicInteger();
        ExecutorService executor = Executors.newFixedThreadPool(concurrency, runnable -> {
            Thread thread = new Thread(runnable, "carddemo-warmup-db");
            thread.setDaemon(true);
            return thread;
        });
        try {
            for (int worker = 0; worker < concurrency; worker++) {
                executor.execute(() -> {
                    // Concurrently, and for as long as the budget allows: the parallelism
                    // is what grows the connection pool and exercises the contended path a
                    // burst of arrivals actually takes, and the repetition is what carries
                    // the read path out of the interpreter. One round always runs, so even
                    // a minimal budget warms every entity once.
                    do {
                        for (EntityType<?> entity : entities) {
                            if (readOneRow(entityManagerFactory, entity)) {
                                completed.incrementAndGet();
                            }
                        }
                    } while (System.nanoTime() < deadline);
                });
            }
        } finally {
            executor.shutdown();
            // Every worker observes the same deadline, so this wait only covers the
            // in-flight query each is on when the deadline passes.
            if (!executor.awaitTermination(30, TimeUnit.SECONDS)) {
                log.debug("Entity warm-up did not finish inside its budget; {} reads completed",
                        completed.get());
                executor.shutdownNow();
            }
        }
        return completed.get();
    }

    /**
     * :purpose: Read at most one row of one entity and touch its identifier.
     * :param entityManagerFactory: the factory supplying the entity manager.
     * :param entity: the metamodel entry naming the entity to read.
     * :returns: ``true`` when the read completed, ``false`` when it failed.
     */
    private boolean readOneRow(EntityManagerFactory entityManagerFactory, EntityType<?> entity) {
        EntityManager entityManager = entityManagerFactory.createEntityManager();
        try {
            List<?> rows = entityManager
                    .createQuery("select e from " + entity.getName() + " e", Object.class)
                    .setMaxResults(1)
                    .getResultList();
            for (Object row : rows) {
                // Touching only the identifier keeps this a warm-up of the read path
                // and never triggers a lazy association load.
                entityManagerFactory.getPersistenceUnitUtil().getIdentifier(row);
            }
            return true;
        } catch (Exception e) {
            log.debug("Warm-up read of entity '{}' failed: {}", entity.getName(), e.toString());
            return false;
        } finally {
            entityManager.close();
        }
    }
}
