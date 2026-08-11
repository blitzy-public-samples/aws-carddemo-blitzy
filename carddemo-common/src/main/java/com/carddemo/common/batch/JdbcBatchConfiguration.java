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

package com.carddemo.common.batch;

import io.micrometer.observation.ObservationRegistry;

import javax.sql.DataSource;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.batch.core.configuration.BatchConfigurationException;
import org.springframework.batch.core.configuration.support.JdbcDefaultBatchConfiguration;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.DatabasePopulatorUtils;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.transaction.annotation.Isolation;

/**
 * :purpose: Give every CardDemo batch-capable service a JDBC-backed Spring Batch {@code
 *     JobRepository} so job and step executions are persisted in the ``BATCH_*`` metadata
 *     tables that ``V5__batch_metadata.sql`` creates.
 * :output: Replaces Spring Boot's default batch configuration wholesale. Extending a
 *     {@code DefaultBatchConfiguration} is the supported extension point:
 *     ``BatchAutoConfiguration`` is annotated
 *     ``@ConditionalOnMissingBean(DefaultBatchConfiguration.class)``, so declaring this class
 *     makes Boot back off and this configuration supplies the ``jobRepository`` and
 *     ``jobOperator`` beans (the ``JobOperator`` also satisfies ``JobLauncher`` injection
 *     points, since ``JobOperator extends JobLauncher``). Spring Batch's {@code
 *     JdbcDefaultBatchConfiguration} resolves the ``DataSource`` and the
 *     ``PlatformTransactionManager`` from the application context by type, so no wiring is
 *     needed here.
 * :note: Why this is required. Spring Batch 6 changed
 *     ``DefaultBatchConfiguration.jobRepository()`` to return a {@code
 *     ResourcelessJobRepository} - an in-memory repository that persists nothing - and Boot's
 *     ``SpringBootBatchDefaultConfiguration`` inherits it. The consequence, confirmed at
 *     runtime, was that every batch job ran and completed while ``BATCH_JOB_INSTANCE`` and
 *     ``BATCH_JOB_EXECUTION`` stayed EMPTY: every execution reported id 1, no job history
 *     existed, the ``/batch/jobs/executions`` status endpoint could never resolve an
 *     execution, duplicate-instance detection could not fire, and the
 *     restart-from-last-committed-chunk behaviour that the chunk readers advertise
 *     (``saveState(true)``) was silently unavailable. The migrated JCL job streams rely on
 *     that metadata exactly as the legacy jobs relied on JES job history.
 * :note: This class is the ONE batch infrastructure definition for every batch-capable
 *     service (``batch-service``, ``reporting-service`` and ``transaction-service`` each
 *     import it). A context must not combine it with a second {@code
 *     DefaultBatchConfiguration} subclass or with ``@EnableBatchProcessing``: both routes
 *     declare the same ``jobRepository`` and ``jobOperator`` beans, so the container would
 *     reject the duplicate definitions.
 * :note: {@code getObservationRegistry()} is overridden because
 *     ``DefaultBatchConfiguration`` returns {@code ObservationRegistry.NOOP}; handing it the
 *     application's real registry keeps anything this configuration instruments consistent
 *     with the ``spring_batch_*`` metrics that Boot's ``BatchObservabilityBeanPostProcessor``
 *     records on the job and step beans.
 */
@Configuration(proxyBeanMethods = false)
public class JdbcBatchConfiguration extends JdbcDefaultBatchConfiguration {

    /** :purpose: Logger for the one-time metadata-schema provisioning decision. */
    private static final Logger log = LoggerFactory.getLogger(JdbcBatchConfiguration.class);

    /**
     * :purpose: Table whose presence proves the metadata schema is already provisioned,
     *  either by the ``V5__batch_metadata.sql`` migration or by another job-hosting
     *  service that reached an empty database first.
     */
    private static final String SENTINEL_TABLE = "batch_job_instance";

    /**
     * :purpose: The Spring Batch PostgreSQL metadata schema shipped inside
     *  ``spring-batch-core``. Using the framework's own script guarantees the schema
     *  always matches the Spring Batch version on the classpath. PostgreSQL is the
     *  datastore the migration targets (AAP 0.1.2), so no dialect lookup is needed.
     */
    private static final String SCHEMA_SCRIPT = "org/springframework/batch/core/schema-postgresql.sql";

    /**
     * :purpose: Build the JDBC-backed job repository, guaranteeing its schema exists
     *  first so a batch-capable service is self-sufficient rather than dependent on
     *  another service having migrated the shared database before it started.
     * :returns: a ``JobRepository`` reading and writing the ``BATCH_*`` tables.
     * :raises BatchConfigurationException: if the repository cannot be built.
     */
    @Override
    public JobRepository jobRepository() throws BatchConfigurationException {
        ensureMetadataSchema();
        return super.jobRepository();
    }

    /**
     * :purpose: Create the ``BATCH_*`` metadata schema when it is absent. The check
     *  makes this a no-op on every normal startup.
     * :raises IllegalStateException: when the schema is absent and cannot be created.
     */
    private void ensureMetadataSchema() {
        DataSource dataSource = getDataSource();
        if (metadataSchemaPresent(dataSource)) {
            return;
        }
        log.info("Spring Batch metadata schema absent; provisioning it from {}", SCHEMA_SCRIPT);
        ResourceDatabasePopulator populator =
                new ResourceDatabasePopulator(new ClassPathResource(SCHEMA_SCRIPT));
        try {
            DatabasePopulatorUtils.execute(populator, dataSource);
        } catch (RuntimeException ex) {
            // Another job-hosting service may have created the schema concurrently.
            // Accept that outcome; only a still-absent schema is a real failure.
            if (!metadataSchemaPresent(dataSource)) {
                throw new IllegalStateException(
                        "Unable to provision the Spring Batch metadata schema", ex);
            }
            log.info("Spring Batch metadata schema was provisioned concurrently; continuing");
        }
    }

    /**
     * :purpose: Report whether the batch metadata schema is already present.
     * :param dataSource: the datasource carrying the ``BATCH_*`` tables.
     * :returns: ``true`` when the sentinel metadata table exists in the current schema.
     */
    private boolean metadataSchemaPresent(DataSource dataSource) {
        Integer found = new JdbcTemplate(dataSource).queryForObject(
                "SELECT count(*) FROM information_schema.tables "
                        + "WHERE table_schema = current_schema() AND lower(table_name) = ?",
                Integer.class, SENTINEL_TABLE);
        return found != null && found > 0;
    }

    /**
     * :purpose: Run the repository's metadata transactions at ``READ COMMITTED`` instead of
     *     the ``SERIALIZABLE`` default.
     * :returns: {@link Isolation#READ_COMMITTED}.
     * :note: {@code AbstractJobRepositoryFactoryBean} applies this isolation level to the
     *     transaction interceptor that advises EVERY ``JobRepository`` method, not only the
     *     ``create*`` ones. Under PostgreSQL's serializable-snapshot isolation that made
     *     concurrent job submissions abort one another: the foreign-key check behind ``INSERT INTO
     *     BATCH_STEP_EXECUTION_CONTEXT`` performs a predicate read (``SELECT 1 FROM
     *     batch_step_execution ... FOR KEY SHARE``) that conflicts with a concurrent
     *     ``BATCH_STEP_EXECUTION`` insert, so PostgreSQL cancelled the transaction with "could not
     *     serialize access due to read/write dependencies among transactions" and an
     *     otherwise-successful job ended ``FAILED``. Reproduced at runtime by submitting the nine
     *     batch-service jobs together: one of the nine failed on the metadata write while its own
     *     business step had done nothing wrong.
     * :note: Nothing is weakened by the change. Two submissions can still never share a
     *     ``JobInstance``: ``BATCH_JOB_INSTANCE`` carries the unique constraint ``JOB_INST_UN
     *     (JOB_NAME, JOB_KEY)``, which rejects a duplicate at any isolation level, and every
     *     ``launch*`` method adds a unique identifying ``run.id`` parameter, so two submissions
     *     never compute the same job key. Concurrent, independent job streams running without
     *     serializing against one another is also what the legacy JES initiators did.
     */
    @Override
    protected Isolation getIsolationLevelForCreate() {
        return Isolation.READ_COMMITTED;
    }

    /**
     * :purpose: Supply the application's Micrometer observation registry instead of
     *  the no-op default, so batch observations this configuration creates are
     *  actually recorded.
     * :returns: the context's {@link ObservationRegistry}, or the inherited no-op
     *  registry when the service has no metrics infrastructure.
     */
    @Override
    protected ObservationRegistry getObservationRegistry() {
        ObservationRegistry registry =
                this.applicationContext.getBeanProvider(ObservationRegistry.class).getIfAvailable();
        return registry != null ? registry : super.getObservationRegistry();
    }
}
