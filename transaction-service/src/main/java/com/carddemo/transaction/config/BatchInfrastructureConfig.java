package com.carddemo.transaction.config;

import javax.sql.DataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.batch.core.configuration.support.DefaultBatchConfiguration;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.core.repository.support.JdbcJobRepositoryFactoryBean;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.DatabasePopulatorUtils;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * :purpose: Persist Spring Batch job metadata in the CardDemo PostgreSQL database
 *     instead of memory. Spring Batch 6 changed the default: the
 *     ``DefaultBatchConfiguration`` that Spring Boot's batch auto-configuration
 *     extends now returns a ``ResourcelessJobRepository``, so every job ran against an
 *     in-memory repository and the committed ``BATCH_*`` tables stayed empty. That
 *     silently removed the three guarantees the legacy JES/JCL environment provided: a
 *     durable audit trail of every submission, restart of a failed job from its last
 *     committed point, and rejection of a re-submission of an already-completed job
 *     instance. It also made ``spring.batch.jdbc.initialize-schema`` inert, because
 *     ``BatchProperties`` in Spring Boot 4.1 exposes only the ``job`` group.
 * :output: A ``jobRepository`` bean backed by ``JdbcJobRepositoryFactoryBean`` over the
 *     application ``DataSource``, sharing the application transaction manager so batch
 *     metadata commits atomically with the business rows written in the same chunk.
 * :note: Declaring a ``DefaultBatchConfiguration`` bean is the documented extension
 *     point: Boot's ``BatchAutoConfiguration`` is annotated
 *     ``@ConditionalOnMissingBean(value = DefaultBatchConfiguration.class, annotation =
 *     EnableBatchProcessing.class)`` and therefore backs off in favour of this class,
 *     while the inherited ``jobOperator`` keeps ``BatchJobLauncherAutoConfiguration``
 *     active.
 */
@Configuration
public class BatchInfrastructureConfig extends DefaultBatchConfiguration {

    /** :purpose: Logger for the one-time metadata-schema provisioning decision. */
    private static final Logger log = LoggerFactory.getLogger(BatchInfrastructureConfig.class);

    /** :purpose: Table-name prefix of the ``BATCH_*`` metadata schema. */
    private static final String TABLE_PREFIX = "BATCH_";

    /**
     * :purpose: Table whose presence proves the metadata schema has already been
     *     provisioned, either by the Flyway migration ``V1__batch_metadata.sql`` or by
     *     another job-hosting service reaching the empty database first.
     */
    private static final String SENTINEL_TABLE = "batch_job_instance";

    /**
     * :purpose: The Spring Batch PostgreSQL metadata schema shipped inside
     *     ``spring-batch-core``. Using the framework's own script guarantees the schema
     *     always matches the Spring Batch version on the classpath. PostgreSQL is the
     *     datastore the migration targets (AAP 0.1.2), so no dialect lookup is needed.
     */
    private static final String SCHEMA_SCRIPT = "org/springframework/batch/core/schema-postgresql.sql";

    /** :purpose: Application datasource holding both business and batch-metadata tables. */
    private final DataSource dataSource;

    /** :purpose: Application transaction manager shared with the batch steps. */
    private final PlatformTransactionManager transactionManager;

    /**
     * :purpose: Construct the batch infrastructure over the application datasource and
     *     transaction manager.
     * :param dataSource: the application ``DataSource`` carrying the ``BATCH_*`` tables.
     * :param transactionManager: the application transaction manager, reused so job
     *     metadata and business writes share one transaction per chunk.
     */
    public BatchInfrastructureConfig(DataSource dataSource,
                                     PlatformTransactionManager transactionManager) {
        this.dataSource = dataSource;
        this.transactionManager = transactionManager;
    }

    /**
     * :purpose: Build the JDBC-backed job repository, replacing the resourceless default,
     *     after guaranteeing its schema exists.
     * :returns: a ``JobRepository`` reading and writing the ``BATCH_*`` tables.
     * :raises IllegalStateException: if the repository cannot be initialized, which
     *     would leave every job running without a durable execution record.
     */
    @Override
    public JobRepository jobRepository() {
        ensureMetadataSchema();
        JdbcJobRepositoryFactoryBean factory = new JdbcJobRepositoryFactoryBean();
        factory.setDataSource(dataSource);
        factory.setTransactionManager(transactionManager);
        factory.setTablePrefix(TABLE_PREFIX);
        // Spring Batch creates a JobInstance at SERIALIZABLE isolation to stop two
        // launches producing the same instance. PostgreSQL enforces that with
        // Serializable Snapshot Isolation, which aborts one of two CONCURRENT creates -
        // even for different job parameters - with "could not serialize access due to
        // read/write dependencies among transactions", surfacing a raw SQL error to the
        // caller instead of starting the job. REPEATABLE_READ removes that false
        // conflict while the unique constraint on BATCH_JOB_INSTANCE.JOB_KEY remains the
        // authoritative guard against a genuinely duplicated instance.
        factory.setIsolationLevelForCreateEnum(Isolation.REPEATABLE_READ);
        try {
            factory.afterPropertiesSet();
            return factory.getObject();
        } catch (Exception ex) {
            throw new IllegalStateException(
                    "Unable to initialize the JDBC Spring Batch job repository", ex);
        }
    }

    /**
     * :purpose: Create the ``BATCH_*`` metadata schema when it is absent, so this
     *     service is self-sufficient rather than depending on another service having
     *     started first against a shared empty database. The check makes the call a
     *     no-op on every normal startup.
     * :raises IllegalStateException: when the schema is absent and cannot be created.
     */
    private void ensureMetadataSchema() {
        if (metadataSchemaPresent()) {
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
            if (!metadataSchemaPresent()) {
                throw new IllegalStateException(
                        "Unable to provision the Spring Batch metadata schema", ex);
            }
            log.info("Spring Batch metadata schema was provisioned concurrently; continuing");
        }
    }

    /**
     * :purpose: Report whether the batch metadata schema is already present.
     * :returns: ``true`` when the sentinel metadata table exists in the current schema.
     */
    private boolean metadataSchemaPresent() {
        Integer found = new JdbcTemplate(dataSource).queryForObject(
                "SELECT count(*) FROM information_schema.tables "
                        + "WHERE table_schema = current_schema() AND lower(table_name) = ?",
                Integer.class, SENTINEL_TABLE);
        return found != null && found > 0;
    }

    /**
     * :purpose: Supply the application transaction manager to the inherited batch
     *     infrastructure, so it never falls back to the resourceless transaction manager.
     * :returns: the application ``PlatformTransactionManager``.
     */
    @Override
    protected PlatformTransactionManager getTransactionManager() {
        return transactionManager;
    }
}
