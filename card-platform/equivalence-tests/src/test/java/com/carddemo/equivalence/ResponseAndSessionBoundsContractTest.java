package com.carddemo.equivalence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Asserts the two runtime bounds every service declares in its {@code application.yml}: what a
 * response costs to deliver, and what a borrowed database connection may do.
 *
 * <p>Both were found missing by a performance review. Six services answered a 69,959-byte history
 * page and a 37,299-byte assessment page byte for byte, with no {@code Content-Encoding} header on
 * either and no {@code server.compression} block in any of the six files. And every one of
 * {@code statement_timeout}, {@code lock_timeout} and {@code idle_in_transaction_session_timeout}
 * read {@code 0} on the server, so a connection this platform had already acquired could execute or
 * wait for a lock without any ceiling in the application, the driver or the server.
 *
 * <p>Uniformity is the point of the first two assertions rather than a tidiness preference. The
 * Agent Action Plan section 0.4.2 requires the settings that carry a platform-wide guarantee to be
 * identical in every service's {@code application.yml}, because one service holding a different
 * value is what turns a guarantee into a coincidence.
 *
 * <p>The third assertion runs the shipped initialisation statement through the real driver against
 * a real server, because the two questions that matter about it cannot be answered by reading the
 * file: whether one {@code execute} call carrying three semicolon-separated {@code SET} statements
 * is accepted, and whether the server then reports the three values the file intended. A pool whose
 * initialisation statement the driver rejects fails every connection it opens.
 *
 * <p>Design decisions: {@code card-platform/docs/decision-log.md}.
 */
@DisplayName("Response transfer and database session bounds across the six services")
class ResponseAndSessionBoundsContractTest {

    /** The six services, each of which ships one {@code application.yml}. */
    private static final List<String> APPLICATION_SERVICES = List.of(
            "authorization-service",
            "ledger-posting-service",
            "fraud-detection-service",
            "account-service",
            "card-service",
            "notification-service");

    /**
     * The compression settings every service declares, and the value each carries.
     *
     * <p>The two media types are the only ones the surface answers with: JSON for a representation
     * and problem+JSON for a refusal. The floor keeps a short answer out of the compressor
     * altogether, and every body the review measured above it was tens of kilobytes.
     */
    private static final Map<String, String> COMPRESSION_SETTINGS = Map.of(
            "enabled", "${SERVER_COMPRESSION_ENABLED:true}",
            "mime-types",
            "${SERVER_COMPRESSION_MIME_TYPES:application/json,application/problem+json}",
            "min-response-size", "${SERVER_COMPRESSION_MIN_RESPONSE_SIZE:2KB}");

    /** The pool settings every service declares to bound a connection it has already opened. */
    private static final Map<String, String> CONNECTION_BOUNDS = Map.of(
            "connection-init-sql",
            "${SPRING_DATASOURCE_HIKARI_CONNECTION_INIT_SQL:SET statement_timeout = '30s';"
                    + " SET lock_timeout = '10s';"
                    + " SET idle_in_transaction_session_timeout = '60s'}",
            "max-lifetime", "${SPRING_DATASOURCE_HIKARI_MAX_LIFETIME:1800000}",
            "idle-timeout", "${SPRING_DATASOURCE_HIKARI_IDLE_TIMEOUT:600000}",
            "leak-detection-threshold",
            "${SPRING_DATASOURCE_HIKARI_LEAK_DETECTION_THRESHOLD:120000}");

    /**
     * The values the server has to report once the shipped statement has run, in milliseconds.
     *
     * <p>{@code pg_settings.setting} carries each of the three in milliseconds, so the expectation
     * is a number rather than the text a {@code SHOW} would answer, which the server normalises.
     */
    private static final Map<String, Integer> EXPECTED_SESSION_SETTINGS = new LinkedHashMap<>(
            Map.of(
                    "statement_timeout", 30_000,
                    "lock_timeout", 10_000,
                    "idle_in_transaction_session_timeout", 60_000));

    /** Reads the shipped default out of a {@code ${NAME:default}} placeholder. */
    private static final Pattern SHIPPED_DEFAULT = Pattern.compile("\\$\\{[A-Z0-9_]+:(.*)}$");

    @Test
    @DisplayName("every service compresses the same two media types above the same floor")
    void everyServiceCompressesTheSameTwoMediaTypesAboveTheSameFloor() {
        List<String> divergences = new ArrayList<>();

        for (String service : APPLICATION_SERVICES) {
            String application = read(applicationFileOf(service));
            for (Map.Entry<String, String> setting : COMPRESSION_SETTINGS.entrySet()) {
                String declared = valueOf(application, "compression", setting.getKey());
                if (!setting.getValue().equals(declared)) {
                    divergences.add(service + " declares server.compression." + setting.getKey()
                            + " as " + declared + " where every service declares "
                            + setting.getValue());
                }
            }
        }

        assertEquals(List.of(), divergences,
                "a body large enough to matter leaves one connector compressed and another one "
                        + "whole: " + divergences);
    }

    @Test
    @DisplayName("every service bounds statement execution, lock waiting and an idle transaction")
    void everyServiceBoundsStatementExecutionLockWaitingAndAnIdleTransaction() {
        List<String> divergences = new ArrayList<>();

        for (String service : APPLICATION_SERVICES) {
            String application = read(applicationFileOf(service));
            for (Map.Entry<String, String> bound : CONNECTION_BOUNDS.entrySet()) {
                String declared = valueOf(application, "hikari", bound.getKey());
                if (!bound.getValue().equals(declared)) {
                    divergences.add(service + " declares spring.datasource.hikari."
                            + bound.getKey() + " as " + declared + " where every service declares "
                            + bound.getValue());
                }
            }
        }

        assertEquals(List.of(), divergences,
                "one service's connections carry a different ceiling from the rest: "
                        + divergences);
    }

    @Test
    @DisplayName("the shipped initialisation statement is one the driver takes and the server applies")
    void theShippedInitialisationStatementIsOneTheDriverTakesAndTheServerApplies() {
        String initialisation = shippedDefaultOf(
                CONNECTION_BOUNDS.get("connection-init-sql"), "connection-init-sql");

        try (Connection connection = DriverManager.getConnection(
                EquivalenceDatabase.urlFor(ResponseAndSessionBoundsContractTest.class),
                EquivalenceDatabase.container().getUsername(),
                EquivalenceDatabase.container().getPassword())) {

            // One call, exactly as HikariCP issues it on each connection it opens. A driver that
            // refused three statements in one call would fail here rather than in a container.
            try (Statement session = connection.createStatement()) {
                session.execute(initialisation);
            }

            for (Map.Entry<String, Integer> expected : EXPECTED_SESSION_SETTINGS.entrySet()) {
                assertEquals(expected.getValue().intValue(),
                        settingOf(connection, expected.getKey()),
                        "the shipped statement leaves " + expected.getKey()
                                + " at a value the file did not intend, so the bound the review "
                                + "asked for is not the bound in force");
            }
        } catch (SQLException refused) {
            throw new AssertionError(
                    "the shipped connection-init-sql is not one statement this driver and server "
                            + "accept: " + initialisation, refused);
        }
    }

    @Test
    @DisplayName("each bound leaves room for the one it has to outlast")
    void eachBoundLeavesRoomForTheOneItHasToOutlast() {
        int statementTimeout = EXPECTED_SESSION_SETTINGS.get("statement_timeout");
        int idleInTransaction =
                EXPECTED_SESSION_SETTINGS.get("idle_in_transaction_session_timeout");
        int leakDetection = Integer.parseInt(shippedDefaultOf(
                CONNECTION_BOUNDS.get("leak-detection-threshold"), "leak-detection-threshold"));
        int idleTimeout = Integer.parseInt(
                shippedDefaultOf(CONNECTION_BOUNDS.get("idle-timeout"), "idle-timeout"));
        int maxLifetime = Integer.parseInt(
                shippedDefaultOf(CONNECTION_BOUNDS.get("max-lifetime"), "max-lifetime"));

        assertTrue(idleInTransaction > statementTimeout,
                "a transaction has to be allowed to run its longest permitted statement before it "
                        + "counts as idle, and " + idleInTransaction + "ms is not longer than "
                        + statementTimeout + "ms");
        assertTrue(leakDetection > idleInTransaction,
                "a held connection is reported only once neither a statement nor an open "
                        + "transaction could still explain the hold, and " + leakDetection
                        + "ms is not longer than " + idleInTransaction + "ms");
        assertTrue(maxLifetime > idleTimeout,
                "a connection retired for age has to outlive one retired for idleness, or the "
                        + "pool never closes an idle connection on its own account: " + maxLifetime
                        + "ms against " + idleTimeout + "ms");
    }

    /** Reads {@code pg_settings.setting} for one name, in the milliseconds it records. */
    private static int settingOf(Connection connection, String name) throws SQLException {
        String query = "SELECT setting FROM pg_settings WHERE name = '" + name + "'";
        try (Statement lookup = connection.createStatement();
                ResultSet answer = lookup.executeQuery(query)) {
            assertTrue(answer.next(), "this server declares no setting named " + name);
            return Integer.parseInt(answer.getString(1));
        }
    }

    /**
     * Returns the single-line value of one key inside one block, whatever quoting the file uses.
     *
     * <p>Three of the six files quote every placeholder and three leave them bare, so the
     * comparison is made against the placeholder text rather than against the line.
     *
     * <p>The block name is not decoration. {@code enabled} names a key under {@code compression}
     * and another under {@code flyway}, and the three files that put {@code server:} last carry the
     * Flyway one first, so a search of the whole file answers the wrong line for two services.
     */
    private static String valueOf(String application, String block, String key) {
        Matcher opening = Pattern.compile("^\\s*" + Pattern.quote(block) + ":\\s*$",
                Pattern.MULTILINE).matcher(application);
        if (!opening.find()) {
            return "no " + block + " block at all";
        }
        Matcher declaration = Pattern
                .compile("^\\s*" + Pattern.quote(key) + ": \"?(.*?)\"?\\s*$", Pattern.MULTILINE)
                .matcher(application)
                .region(opening.end(), application.length());
        return declaration.find() ? declaration.group(1) : "nothing at all";
    }

    /** Returns what a {@code ${NAME:default}} placeholder falls back to. */
    private static String shippedDefaultOf(String placeholder, String key) {
        Matcher value = SHIPPED_DEFAULT.matcher(placeholder);
        assertTrue(value.find(), key + " carries no shipped default: " + placeholder);
        return value.group(1);
    }

    private static Path applicationFileOf(String service) {
        return platformDirectory()
                .resolve("services")
                .resolve(service)
                .resolve("src/main/resources/application.yml");
    }

    private static Path platformDirectory() {
        return CardDemoFixtureLoader.fixtureDirectory()
                .getParent()
                .getParent()
                .getParent()
                .resolve("card-platform");
    }

    private static String read(Path file) {
        try {
            return Files.readString(file, StandardCharsets.UTF_8);
        } catch (IOException unreadable) {
            throw new UncheckedIOException("cannot read " + file, unreadable);
        }
    }
}
