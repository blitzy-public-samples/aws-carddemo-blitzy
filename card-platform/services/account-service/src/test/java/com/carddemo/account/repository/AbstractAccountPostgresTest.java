package com.carddemo.account.repository;

import com.carddemo.account.AccountServiceDatabase;
import com.carddemo.account.TestIdentityPasswords;
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
 * {@code MONITORING_PASSWORD_HASH}. {@code SecurityConfig.requireUsableCredentials} refuses a
 * blank broker login entry, and {@code SecurityConfig.requireApprovedPasswordEncoding} refuses an
 * identity password whose encoding this platform does not approve. The class annotation supplies
 * one fake value per variable and restates no setting from {@code application.yml}. Each value is
 * inert: the broker password reaches no broker, and the identity hashes in
 * {@code TestIdentityPasswords} encode passwords that authenticate nothing outside this build.</p>
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
 *
 * <p><b>The listener is held shut and the broker address is still set.</b>
 * This service acquired a listener when {@code messaging/TransactionPostedConsumer} arrived, and a
 * booted context starts every listener container it finds. {@code auto-startup=false} above leaves
 * the container registered and unstarted, which is what the ledger, notification, fraud and
 * authorization suites do for the same reason.
 *
 * <p>That setting alone is not enough. The shipped {@code bootstrap-servers} default names the
 * compose service {@code kafka}, which resolves nowhere in a test Java Virtual Machine, and a
 * consumer built against an unresolvable address fails its whole context rather than its one
 * container. The flag prevents the container starting at refresh, but the test context cache stops
 * and restarts a cached context when it evicts around it, and a restart starts every registered
 * container whatever the flag says. Naming a resolvable address the second line is what keeps that
 * restart harmless: the consumer constructs, finds nothing listening, and retries in the background
 * while the test reads the database.
 *
 * <p>The listener's own behaviour is asserted in
 * {@code messaging/TransactionPostedConsumerTest} against mocked collaborators, and end to end
 * against a real broker by {@code docker compose}.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = {
                "spring.kafka.listener.auto-startup=false",
                "spring.kafka.bootstrap-servers=" + AbstractAccountPostgresTest.UNREACHABLE_BROKER,
                "KAFKA_SASL_PASSWORD=not-a-real-broker-password",
                "ADMIN_PASSWORD_HASH=" + TestIdentityPasswords.ADMIN_PASSWORD_HASH,
                "USER_PASSWORD_HASH=" + TestIdentityPasswords.USER_PASSWORD_HASH,
                "MONITORING_PASSWORD_HASH=" + TestIdentityPasswords.MONITORING_PASSWORD_HASH
        })
public abstract class AbstractAccountPostgresTest {

    /**
     * A resolvable address with nothing listening behind it.
     *
     * <p>Port 1 is privileged and unused, so a client resolves the host, fails to connect, and
     * retries. {@code api/AccountControllerIT} names the same address for the same reason.
     */
    static final String UNREACHABLE_BROKER = "localhost:1";

    /**
     * The one container the module fork runs, which this class reads a login from.
     *
     * <p>{@link AccountServiceDatabase} owns it and hands this class a database of its own inside it.
     * Nothing here starts or stops a container.
     */
    private static final PostgreSQLContainer POSTGRES = AccountServiceDatabase.container();

    /**
     * Returns the running container, whose login a subclass reads for raw SQL.
     *
     * <p>Read the user and the password from this. Do <b>not</b> read the locator: the container
     * holds one database per test class and {@link PostgreSQLContainer#getJdbcUrl()} names the one
     * it was created with, which no migration of this module has ever touched. {@link #jdbcUrl()}
     * names the database the Spring context above migrated, which is the one a raw statement has to
     * reach.
     *
     * @return the one container every test class in the module shares
     */
    protected static PostgreSQLContainer postgres() {
        return POSTGRES;
    }

    /**
     * Returns the locator of the database this class and its subclasses share.
     *
     * <p>The same value the Spring datasource above is given, so a statement issued through a raw
     * {@link java.sql.Connection} reads the schema Flyway migrated for this context rather than an
     * empty one.
     *
     * @return the account-service database URL, with the service schema on the search path
     */
    protected static String jdbcUrl() {
        return jdbcUrlOnAccountSchema();
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
        return AccountServiceDatabase.urlFor(AbstractAccountPostgresTest.class);
    }
}
