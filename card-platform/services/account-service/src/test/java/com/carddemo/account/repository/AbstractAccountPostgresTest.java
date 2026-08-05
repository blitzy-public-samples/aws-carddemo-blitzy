package com.carddemo.account.repository;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Starts one PostgreSQL container for the account-service test tree and points the Spring context
 * at it.
 *
 * <p>The class is the only container definition in the module. The test packages
 * {@code com.carddemo.account.domain} and {@code com.carddemo.account.outbox} extend it and
 * declare no container of their own. The class declares no test method and asserts nothing.
 * Surefire discovers it under the default {@code *Test.java} include pattern and skips every
 * abstract class.</p>
 *
 * <p><b>What every subclass checks by starting.</b> {@code src/main/resources/application.yml}
 * asks Hibernate to validate the entity mapping against the migrated schema, and leaves schema
 * creation to Flyway. A context fails to start when an entity class disagrees with that schema.
 * Hibernate reads all six Jakarta Persistence (JPA) entity classes and checks each column name,
 * type, precision, scale and nullability. Every subclass carries that check.</p>
 *
 * <p><b>Four variables the shipped configuration leaves undefined.</b>
 * {@code application.yml} reads four variables and gives none a default:
 * {@code KAFKA_SASL_PASSWORD}, {@code ADMIN_PASSWORD_HASH}, {@code USER_PASSWORD_HASH} and
 * {@code MONITORING_PASSWORD_HASH}. The guard at {@code config/SecurityConfig.java:L589-L599}
 * refuses a blank broker login entry, and {@code config/SecurityConfig.java:L648-L652} refuses an
 * identity password carrying no encoding prefix. The class annotation supplies one fake value per
 * variable and restates no setting from {@code application.yml}. Each value is inert: the broker
 * password reaches no broker, and a {@code noop} identity password authenticates nobody.</p>
 *
 * <p><b>What a subclass author does.</b>
 * A subclass that inserts a row annotates itself {@code @Transactional}, and Spring then rolls
 * the insert back. Two consequences follow. The seed and reference row counts stay correct for
 * every other test class. The outbox relay, which sweeps on a 500-millisecond fixed delay inside
 * its own transaction, reads no uncommitted row.</p>
 *
 * <p>{@link #postgres()} is the whole surface a subclass inherits from here. A subclass that opens
 * a plain Java Database Connectivity (JDBC) connection for raw Structured Query Language (SQL)
 * reads the coordinates from that accessor. A subclass that needs a bean injects one.</p>
 *
 * <p><b>Source provenance.</b>
 * Three Job Control Language members supply the migrated keys and record widths.
 * {@code app/jcl/ACCTFILE.jcl:L40-L41} declares {@code KEYS(11 0)} and
 * {@code RECORDSIZE(300 300)}. {@code app/jcl/CUSTFILE.jcl:L50-L51} declares {@code KEYS(9 0)}
 * and {@code RECORDSIZE(500 500)}. {@code app/jcl/DISCGRP.jcl:L40-L41} declares
 * {@code KEYS(16 0)} and {@code RECORDSIZE(50 50)}.</p>
 *
 * <p>The account open, expiry and reissue columns hold ten characters of text and carry no date
 * type. The comparison at {@code app/cbl/CBTRN02C.cbl:L414} reads
 * {@code IF ACCT-EXPIRAION-DATE >= DALYTRAN-ORIG-TS (1:10)}.</p>
 *
 * <p>Two concern the database: Testcontainers over an in-memory database, and one container shared
 * across the Java Virtual Machine (JVM) over one container per test class. Two concern the
 * annotations: the {@code SpringBootTest} annotation over the {@code DataJpaTest} slice, and the
 * {@code DynamicPropertySource} method over the {@code ServiceConnection} annotation.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = {
                "KAFKA_SASL_PASSWORD=not-a-real-broker-password",
                "ADMIN_PASSWORD_HASH={noop}not-a-real-admin-password",
                "USER_PASSWORD_HASH={noop}not-a-real-user-password",
                "MONITORING_PASSWORD_HASH={noop}not-a-real-monitoring-password"
        })
public abstract class AbstractAccountPostgresTest {

    /** The image tag {@code card-platform/docker-compose.yml:L128} also names. */
    private static final String POSTGRES_IMAGE = "postgres:18.4";

    /**
     * The database name, the login name and the password of the container, one value for all
     * three. {@code card-platform/docker-compose.yml:L131-L132} names the same value for the
     * database and the login.
     */
    private static final String POSTGRES_CREDENTIAL = "carddemo";

    /** The private schema the account service owns. */
    private static final String ACCOUNT_SCHEMA = "account_service";

    /**
     * The schema {@code src/main/resources/application.yml} names for Flyway and for the
     * persistence layer, and the schema this class puts on the connection search path.
     */
    private static final String MIGRATED_SCHEMA = "account_service";

    /**
     * The one container every test class in the module shares.
     *
     * <p>The class name comes from {@code org.testcontainers.postgresql}, the package
     * Testcontainers 2.0.5 ships it in.
     * {@code org.testcontainers.containers.PostgreSQLContainer} carries a deprecation on the same
     * artifact.</p>
     *
     * <p>No annotation manages the lifecycle of the field, and no code here stops the container.
     * Testcontainers removes it when the Java Virtual Machine (JVM) exits.</p>
     */
    private static final PostgreSQLContainer POSTGRES;

    static {
        POSTGRES = new PostgreSQLContainer(POSTGRES_IMAGE)
                .withDatabaseName(POSTGRES_CREDENTIAL)
                .withUsername(POSTGRES_CREDENTIAL)
                .withPassword(POSTGRES_CREDENTIAL);
        POSTGRES.start();
    }

    /**
     * Returns the running container, whose JDBC coordinates a subclass reads for raw SQL.
     *
     * @return the one container every test class in the module shares
     */
    protected static PostgreSQLContainer postgres() {
        return POSTGRES;
    }

    /**
     * Points the Spring datasource at the running container.
     *
     * <p>Three properties leave here. {@code application.yml} sits on the test classpath and
     * carries every other datasource, Flyway and persistence setting, so no line below repeats
     * one.</p>
     *
     * <p>The uniform resource locator carries {@code currentSchema}, as the shipped one at
     * {@code src/main/resources/application.yml:L39} does. {@code hibernate.default_schema}
     * qualifies a mapped query alone, so a native statement such as
     * {@code OutboxEventRepository.claimDueRows} resolves its table name against the connection
     * search path.</p>
     *
     * @param registry the registry the Spring test context supplies
     */
    @DynamicPropertySource
    static void datasourceProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", AbstractAccountPostgresTest::jdbcUrlOnAccountSchema);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    /**
     * Returns the container URL with the service schema on the connection search path.
     *
     * @return the account-service database URL
     */
    private static String jdbcUrlOnAccountSchema() {
        String url = POSTGRES.getJdbcUrl();
        return url + (url.contains("?") ? "&" : "?") + "currentSchema=" + ACCOUNT_SCHEMA;
    }
}
