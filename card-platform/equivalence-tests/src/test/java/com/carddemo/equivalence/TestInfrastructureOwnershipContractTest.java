package com.carddemo.equivalence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Holds every test module to one PostgreSQL container per fork, owned by one facility class.
 *
 * <p><b>What this stops coming back.</b> Forty-two test classes each constructed a container of
 * their own, and most never stopped it, so a module fork ended with as many database servers live
 * as it had container-using classes: ten in the card module, seven in the ledger and notification
 * modules. Each module now declares one facility, the facility holds one container, and each test
 * class asks it for a database of its own inside that container. The isolation is what it was — a
 * class still reads no other class's rows — and the servers are one instead of ten.
 *
 * <p><b>A test rather than a note in a document.</b> The next class that needs a database will
 * be written by copying the one beside it. If that neighbour constructs a container, so will the
 * copy, and the count climbs back one class at a time without anybody deciding to let it. The
 * assertions below fail on the first such class.
 *
 * <p><b>The broker is deliberately not pooled, and that is asserted too.</b> Eight classes need a
 * Kafka broker and each starts its own. Sharing one across classes in a fork would need the topic
 * names to differ per class, because two classes reading the same topic in the same group would
 * read each other's records; and the topic names are exactly what those tests prove the service
 * reads, since each one names the shipped topic literally and asserts a record arrived on it.
 * Renaming them per class would leave a typo in a shipped topic name undetected, which is a worse
 * trade than the two container starts the pooling would save — only two forks hold more than one
 * broker class. {@link #theBrokerDeclarationsAreTheKnownEight()} pins the set so a ninth is a
 * decision somebody makes rather than one that happens.
 */
@DisplayName("Test infrastructure ownership: one database container per module fork")
class TestInfrastructureOwnershipContractTest {

    /** Every module with a test tree, against the facility class that owns its container. */
    private static final Map<String, String> FACILITIES =
            Map.of(
                    "services/authorization-service", "AuthorizationServiceDatabase",
                    "services/ledger-posting-service", "LedgerServiceDatabase",
                    "services/fraud-detection-service", "FraudServiceDatabase",
                    "services/notification-service", "NotificationServiceDatabase",
                    "services/account-service", "AccountServiceDatabase",
                    "services/card-service", "CardServiceDatabase",
                    "equivalence-tests", "EquivalenceDatabase");

    /**
     * The eight classes that start a Kafka broker, each named by its module and simple name.
     *
     * <p>A broker is the one piece of infrastructure a class may still construct. The set is closed
     * so that adding a ninth is a change to this list.
     */
    private static final Set<String> BROKER_CLASSES =
            Set.of(
                    "services/authorization-service:PoisonRecordRecoveryIT",
                    "services/authorization-service:ReplicaRefreshToDecisionIT",
                    "services/ledger-posting-service:TransactionAuthorizedConsumerIT",
                    "services/fraud-detection-service:ConsumeToPublishIT",
                    "services/notification-service:DeadLetterRoutingIT",
                    "services/notification-service:DuplicateDeliveryIT",
                    "services/card-service:CardEventPublicationTest",
                    "equivalence-tests:ThreeConsumerAuthorizationFlowIT");

    /** The image tag every facility runs, which {@code docker-compose.yml} also names. */
    private static final String POSTGRES_IMAGE = "postgres:18.4";

    /** The broker image tag every declaration above runs. */
    private static final String KAFKA_IMAGE = "apache/kafka:4.2.1";

    @Test
    @DisplayName("every module declares exactly one database facility")
    void everyModuleDeclaresExactlyOneDatabaseFacility() {
        Map<String, List<String>> found = new TreeMap<>();
        FACILITIES.forEach(
                (module, facility) -> {
                    List<String> owners =
                            testSourcesOf(module).stream()
                                    .filter(path -> declares(read(path), "new PostgreSQLContainer("))
                                    .map(path -> path.getFileName().toString())
                                    .sorted()
                                    .toList();
                    found.put(module, owners);
                });
        Map<String, List<String>> expected = new TreeMap<>();
        FACILITIES.forEach((module, facility) -> expected.put(module, List.of(facility + ".java")));
        assertEquals(
                expected,
                found,
                "one class per module constructs the container, and it is that module's facility");
    }

    @Test
    @DisplayName("no test class outside a facility constructs a database container")
    void noTestClassOutsideAFacilityConstructsADatabaseContainer() {
        List<String> offenders = new ArrayList<>();
        FACILITIES.forEach(
                (module, facility) ->
                        testSourcesOf(module).stream()
                                .filter(path -> !path.getFileName().toString()
                                        .equals(facility + ".java"))
                                .filter(path -> declares(read(path), POSTGRES_IMAGE)
                                        && declares(read(path), "new PostgreSQLContainer("))
                                .forEach(path -> offenders.add(module + ":"
                                        + path.getFileName())));
        assertTrue(
                offenders.isEmpty(),
                () -> "these classes start a database server of their own instead of asking the"
                        + " module facility for a database: " + offenders);
    }

    @Test
    @DisplayName("no test class outside a facility reads the container's own database locator")
    void noTestClassOutsideAFacilityReadsTheContainerLocator() {
        // The container holds one database per test class, and getJdbcUrl() names the database it
        // was created with -- a database no migration ever reaches. A class that reads it for a raw
        // statement queries an empty catalogue and reports every table as missing, which is how
        // this was found. Only a facility may read it, because that is the login it uses to issue
        // CREATE DATABASE before any schema exists.
        List<String> offenders = new ArrayList<>();
        FACILITIES.forEach(
                (module, facility) ->
                        testSourcesOf(module).stream()
                                .filter(path -> !path.getFileName().toString()
                                        .equals(facility + ".java"))
                                .filter(path -> declares(read(path), ".getJdbcUrl()")
                                        || declares(read(path), ".getDatabaseName()"))
                                .forEach(path -> offenders.add(module + ":"
                                        + path.getFileName())));
        assertTrue(
                offenders.isEmpty(),
                () -> "these classes read a locator off the shared container instead of the one the"
                        + " facility gave them, so their statements would run against a database"
                        + " carrying no migrated schema: " + offenders);
    }

    @Test
    @DisplayName("every facility keys its database on a test class and selects the service schema")
    void everyFacilityKeysItsDatabaseOnATestClass() {
        FACILITIES.forEach(
                (module, facility) -> {
                    String source = read(facilityPathOf(module, facility));
                    assertTrue(
                            source.contains("String urlFor(Class<?> testClass)"),
                            facility + " gives no database per test class");
                    assertTrue(
                            source.contains("CREATE DATABASE"),
                            facility + " creates no database, so two classes would share one");
                    assertTrue(
                            source.contains("CREATED_DATABASES.add(database)"),
                            facility + " would create the same database twice");
                    // Winning the set and creating the database are two steps, and a caller that
                    // arrives between them would be handed a locator for a database that does not
                    // exist. Nothing runs test classes in parallel today, so this is the guard that
                    // keeps switching that on from becoming an intermittent failure.
                    assertTrue(
                            source.contains("synchronized String urlFor("),
                            facility + " hands out a locator without holding other callers while it"
                                    + " creates the database");
                    assertTrue(
                            source.contains(POSTGRES_IMAGE),
                            facility + " runs an image other than the one compose runs");
                    assertFalse(
                            declares(source, "withReuse"),
                            facility + " opts into container reuse, which Ryuk does not clean up"
                                    + " and which would put every class in one database");
                });
    }

    @Test
    @DisplayName("the classes that start a broker are the known eight")
    void theBrokerDeclarationsAreTheKnownEight() {
        Set<String> found = new TreeSet<>();
        FACILITIES.keySet()
                .forEach(module -> testSourcesOf(module).stream()
                        .filter(path -> declares(read(path), "new KafkaContainer("))
                        .forEach(path -> found.add(module + ":"
                                + path.getFileName().toString().replace(".java", ""))));
        assertEquals(new TreeSet<>(BROKER_CLASSES), found);
        found.forEach(
                entry -> {
                    String module = entry.substring(0, entry.indexOf(':'));
                    String simple = entry.substring(entry.indexOf(':') + 1);
                    Path path = testSourcesOf(module).stream()
                            .filter(candidate -> candidate.getFileName().toString()
                                    .equals(simple + ".java"))
                            .findFirst()
                            .orElseThrow();
                    assertTrue(
                            read(path).contains(KAFKA_IMAGE),
                            simple + " runs a broker image other than the one compose runs");
                });
    }

    /** Returns the repository root, found by walking up to the directory holding {@code app}. */
    private static Path repositoryRoot() {
        Path directory = Path.of("").toAbsolutePath();
        while (directory != null && !Files.isDirectory(directory.resolve("app"))) {
            directory = directory.getParent();
        }
        if (directory == null) {
            throw new IllegalStateException("no repository root above " + Path.of("").toAbsolutePath());
        }
        return directory;
    }

    /** Returns every Java test source of one module. */
    private static List<Path> testSourcesOf(String module) {
        Path tests = repositoryRoot().resolve("card-platform").resolve(module)
                .resolve("src/test/java");
        assertTrue(Files.isDirectory(tests), "no test tree under " + module);
        try (Stream<Path> walk = Files.walk(tests)) {
            return walk.filter(path -> path.getFileName().toString().endsWith(".java"))
                    .sorted()
                    .toList();
        } catch (IOException unreadable) {
            throw new UncheckedIOException(unreadable);
        }
    }

    /** Returns the facility source path of one module. */
    private static Path facilityPathOf(String module, String facility) {
        return testSourcesOf(module).stream()
                .filter(path -> path.getFileName().toString().equals(facility + ".java"))
                .findFirst()
                .orElseThrow(() -> new AssertionError(module + " declares no " + facility));
    }

    /**
     * Reports whether one source declares a token, rather than merely naming it in a string.
     *
     * <p>Two kinds of line name a token without using it. This class names every token it looks
     * for inside a string literal, in the assertions above, and each facility explains in its
     * documentation why it does not opt into container reuse. A plain substring search would report
     * both as declarations. A line whose token sits behind a quotation mark, and a line that is a
     * comment, are lines writing about the token rather than using it, which is the whole
     * distinction needed.
     *
     * @param source the file content
     * @param token  the code fragment to look for
     * @return {@code true} when some line of code uses the token
     */
    private static boolean declares(String source, String token) {
        return source.lines()
                .filter(line -> line.contains(token))
                .filter(line -> !line.strip().startsWith("*")
                        && !line.strip().startsWith("//")
                        && !line.strip().startsWith("/*"))
                .anyMatch(line -> !line.contains("\"" + token));
    }

    /** Reads one source file. */
    private static String read(Path path) {
        try {
            return Files.readString(path);
        } catch (IOException unreadable) {
            throw new UncheckedIOException(unreadable);
        }
    }
}
