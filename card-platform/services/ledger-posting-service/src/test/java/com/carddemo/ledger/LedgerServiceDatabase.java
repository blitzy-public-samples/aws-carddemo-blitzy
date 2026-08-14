package com.carddemo.ledger;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * The one PostgreSQL container the ledger test fork runs, and one database inside it per
 * test class.
 *
 * <p><b>What it replaces.</b> Every test class in this module used to construct a container of its
 * own. Seven classes here did, and none of them stopped it before the fork ended, so by the end of a
 * run seven PostgreSQL servers were live at once for one module. This class holds one, and each test
 * class asks it for a database.
 *
 * <p><b>A database rather than a schema.</b> {@code src/main/resources/application.yml} names
 * the schema {@code ledger_service} for Flyway and for the persistence layer, and asks Hibernate to
 * validate the entity mapping against it. That name is part of what the tests prove, so it cannot
 * vary per class. A database can: each test class gets an empty one, Flyway creates and migrates
 * {@code ledger_service} inside it, and no class can read another's rows. That is the isolation a
 * container per class used to buy, at the cost of a {@code CREATE DATABASE} rather than a server
 * start.
 *
 * <p><b>Isolation and cleanup.</b> Surefire and Failsafe fork one Java Virtual Machine (JVM) per
 * module and run its test classes in it sequentially, so one static container serves the whole
 * fork. A database is created empty the first time a class asks for it and is never reused by
 * another class, which is what makes the state a class observes its own. Nothing here stops the
 * container: Testcontainers removes it when the fork ends, and its Ryuk sidecar removes it if the
 * fork dies without exiting. A fresh container per fork is why no database needs dropping.
 *
 * <p><b>Container reuse is left off.</b> Testcontainers can keep a container alive across runs when
 * {@code withReuse} is set and the developer opts in through {@code ~/.testcontainers.properties}.
 * It is rejected here: a reused container is exempt from Ryuk, so nothing removes it, and every
 * class matching the same configuration hash would share one database and one migrated schema.
 * That trades the isolation above for a start this class already avoids.
 *
 * <p>The image tag is the one {@code card-platform/docker-compose.yml} runs, so a test reads the
 * server version the demo runs.
 */
public final class LedgerServiceDatabase {

    /** The image tag {@code card-platform/docker-compose.yml} also names. */
    private static final String POSTGRES_IMAGE = "postgres:18.4";

    /**
     * The database name, the login name and the password of the container, one value for all
     * three, as {@code card-platform/docker-compose.yml} names for the first two.
     */
    private static final String CONTAINER_CREDENTIAL = "carddemo";

    /** The private schema this service owns, inside whichever database a class is given. */
    private static final String SERVICE_SCHEMA = "ledger_service";

    /**
     * The longest identifier PostgreSQL stores. A longer name is silently truncated by the server,
     * which would let two test classes collide on one database, so a name is fitted to it here.
     */
    private static final int MAXIMUM_DATABASE_NAME_LENGTH = 63;

    /** Hexadecimal digits of the class-name hash each database name carries. */
    private static final int NAME_SUFFIX_LENGTH = 6;

    /** The one container this fork runs. */
    private static final PostgreSQLContainer CONTAINER;

    /** Database names already created in this fork, so a second request creates nothing. */
    private static final Set<String> CREATED_DATABASES = ConcurrentHashMap.newKeySet();

    static {
        CONTAINER = new PostgreSQLContainer(POSTGRES_IMAGE)
                .withDatabaseName(CONTAINER_CREDENTIAL)
                .withUsername(CONTAINER_CREDENTIAL)
                .withPassword(CONTAINER_CREDENTIAL);
        CONTAINER.start();
    }

    private LedgerServiceDatabase() {
    }

    /**
     * Returns the running container, whose host, port and running state a test may read.
     *
     * @return the one container the fork runs
     */
    public static PostgreSQLContainer container() {
        return CONTAINER;
    }

    /**
     * Returns the image tag the container runs, for a test that reports or asserts it.
     *
     * @return {@value #POSTGRES_IMAGE}
     */
    public static String image() {
        return POSTGRES_IMAGE;
    }

    /**
     * Returns the Java Database Connectivity (JDBC) URL of the database belonging to one test
     * class, creating that database on the first call for it.
     *
     * <p>The URL carries {@code currentSchema}, as the shipped URL at
     * {@code src/main/resources/application.yml} does, so a native statement resolves its table
     * name against the connection search path rather than against {@code public}.
     *
     * <p>Synchronized because the guard below is a two-step act: one caller wins the set and
     * creates, and any other caller has to be holding still while it does, rather than receiving a
     * locator for a database that does not exist yet. Nothing runs test classes in parallel today,
     * so the lock is never contended; it is here so that switching parallel execution on is not a
     * way to make this fail intermittently.
     *
     * @param testClass the class the database belongs to; a shared abstract base names itself
     *                  here, and its subclasses then share one database as they shared one
     *                  container
     * @return a URL naming that database with {@value #SERVICE_SCHEMA} on the search path
     */
    public static synchronized String urlFor(Class<?> testClass) {
        String database = databaseNameOf(testClass);
        if (CREATED_DATABASES.add(database)) {
            createDatabase(database);
        }
        return "jdbc:postgresql://" + CONTAINER.getHost() + ":" + CONTAINER.getFirstMappedPort()
                + "/" + database + "?currentSchema=" + SERVICE_SCHEMA;
    }

    /**
     * Returns the database name one test class is given.
     *
     * <p>The simple name reads in a server log and in a failure message. The hash of the fully
     * qualified name is what keeps two classes of the same simple name in different packages
     * apart, and it is what survives the fitting to
     * {@value #MAXIMUM_DATABASE_NAME_LENGTH} characters.
     *
     * @param testClass the class the database belongs to
     * @return a lower-case name PostgreSQL stores whole
     */
    static String databaseNameOf(Class<?> testClass) {
        String suffix = String.format(
                "%0" + NAME_SUFFIX_LENGTH + "x",
                testClass.getName().hashCode() & 0xFFFFFF);
        int room = MAXIMUM_DATABASE_NAME_LENGTH - NAME_SUFFIX_LENGTH - 1;
        String simple = testClass.getSimpleName().toLowerCase(Locale.ROOT);
        return simple.substring(0, Math.min(simple.length(), room)) + "_" + suffix;
    }

    /**
     * Creates one empty database in the running container.
     *
     * <p>The name is quoted, so a name this class produced is taken literally rather than folded.
     * {@code CREATE DATABASE} runs outside a transaction, which is why the statement is issued on
     * a connection of its own and closed straight away.
     *
     * @param database the name to create
     */
    private static void createDatabase(String database) {
        try (Connection connection = DriverManager.getConnection(
                        CONTAINER.getJdbcUrl(), CONTAINER.getUsername(), CONTAINER.getPassword());
                Statement statement = connection.createStatement()) {
            statement.executeUpdate("CREATE DATABASE \"" + database + "\"");
        } catch (SQLException refused) {
            throw new IllegalStateException(
                    "The container refused to create the database " + database, refused);
        }
    }
}
