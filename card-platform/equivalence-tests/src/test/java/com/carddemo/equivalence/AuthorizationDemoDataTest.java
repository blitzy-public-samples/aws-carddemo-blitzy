package com.carddemo.equivalence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Keeps the authorization demo dataset separate from the fixture the equivalence suite measures
 * against.
 *
 * <p>Reason code 103 at {@code app/cbl/CBTRN02C.cbl:L414-L420} approves only while the account expiry
 * is greater than or equal to the first ten characters of the origin timestamp, compared
 * as raw text. Every one of the 50 accounts in {@code app/data/ASCII/acctdata.txt} carries an expiry
 * in 2025. Every one of the 300 transactions in {@code app/data/ASCII/dailytran.txt} carries the
 * origin date {@code 2022-06-10}. The fixture is therefore internally consistent and the equivalence
 * suite exercises the rule correctly.
 *
 * <p>A live demo is not. A caller sending the current date as its origin timestamp sends a value
 * later than every seeded expiry. Reason 103 then declines every account, and no
 * {@code TransactionAuthorized} event is published. The fan-out has nothing to show.
 *
 * <p>The fix keeps two datasets apart. {@code db/migration} reproduces the fixture value for value.
 * {@code db/demo} holds one migration that extends the expiries and changes nothing else. These
 * tests assert three things. The fixture-faithful seed still matches the fixture, the demo value
 * appears only in the demo location, and the demo migration touches only the expiry column.
 *
 * <p>An expiry edited in {@code V2__seed.sql} moves the oracle the suite measures against, and
 * these tests fail on it. {@code card-platform/docs/decision-log.md} records the split.
 */
class AuthorizationDemoDataTest {

    /** Directory name of the service under test. */
    private static final String MODULE = "authorization-service";

    /** The synthetic expiry the demo migration writes. */
    private static final String DEMO_EXPIRY = "2099-12-31";

    /** File name of the demo migration. */
    private static final String DEMO_MIGRATION = "V900__demo_expiry_extension.sql";

    /** Accounts in {@code app/data/ASCII/acctdata.txt}. */
    private static final int FIXTURE_ACCOUNT_COUNT = 50;

    @Test
    @DisplayName("The fixture-faithful seed carries every expiry the account fixture carries")
    void seedCarriesEveryFixtureExpiry() {
        Set<String> fixtureExpiries = CardDemoFixtureLoader.loadAccounts().stream()
                .map(account -> account.expirationDate().trim())
                .collect(Collectors.toSet());
        assertEquals(FIXTURE_ACCOUNT_COUNT, fixtureExpiries.size(),
                "the account fixture holds one distinct expiry per account");

        String seed = read(migrationDirectory().resolve("V2__seed.sql"));
        for (String expiry : fixtureExpiries) {
            assertTrue(seed.contains("'" + expiry + "'"),
                    "V2__seed.sql lost the fixture expiry " + expiry
                            + ", so the equivalence oracle has moved");
        }
    }

    @Test
    @DisplayName("The fixture-faithful seed carries no demo value")
    void seedCarriesNoDemoValue() {
        assertFalse(read(migrationDirectory().resolve("V2__seed.sql")).contains(DEMO_EXPIRY),
                "the demo expiry reached V2__seed.sql, which silently alters the oracle");
    }

    @Test
    @DisplayName("Every expiry the fixture carries falls before the demo value")
    void everyFixtureExpiryFallsBeforeTheDemoValue() {
        List<String> notBefore = CardDemoFixtureLoader.loadAccounts().stream()
                .map(account -> account.expirationDate().trim())
                .filter(expiry -> expiry.compareTo(DEMO_EXPIRY) >= 0)
                .toList();
        assertTrue(notBefore.isEmpty(),
                "the demo value has to extend every account, and these already reach it: "
                        + notBefore);
    }

    @Test
    @DisplayName("The demo migration lives outside the fixture-faithful location")
    void demoMigrationLivesOutsideTheMigrationLocation() {
        assertTrue(Files.isRegularFile(demoDirectory().resolve(DEMO_MIGRATION)),
                "the demo migration is missing from db/demo");
        assertFalse(Files.exists(migrationDirectory().resolve(DEMO_MIGRATION)),
                "the demo migration reached db/migration, so every run would apply it");
    }

    @Test
    @DisplayName("The demo migration writes the expiry column and nothing else")
    void demoMigrationTouchesOnlyTheExpiry() {
        String demo = read(demoDirectory().resolve(DEMO_MIGRATION));
        assertTrue(demo.contains("UPDATE account_credit_snapshot"),
                "the demo migration updates the projection the expiry rule reads");
        assertTrue(demo.contains("SET account_expiration_date = '" + DEMO_EXPIRY + "'"),
                "the demo migration writes the documented synthetic expiry");

        for (String forbidden : List.of("credit_limit", "current_cycle_credit",
                "current_cycle_debit", "card_xref", "outbox_event", "processed_event",
                "DROP ", "DELETE ", "INSERT ")) {
            assertFalse(demo.contains(forbidden),
                    "the demo migration reaches " + forbidden.trim()
                            + ", so it changes more than the expiry");
        }
    }

    @Test
    @DisplayName("The demo migration asserts it left no row behind")
    void demoMigrationAssertsItsOwnCompleteness() {
        String demo = read(demoDirectory().resolve(DEMO_MIGRATION));
        assertTrue(demo.contains("RAISE EXCEPTION"),
                "a partial update would show as an unexplained decline during the demo");
    }

    @Test
    @DisplayName("The shipped configuration defaults to the fixture-faithful location alone")
    void shippedConfigurationDefaultsToTheFixture() {
        String yaml = read(moduleDirectory().resolve(
                Path.of("src", "main", "resources", "application.yml")));
        assertTrue(yaml.contains("locations: ${SPRING_FLYWAY_LOCATIONS:classpath:db/migration}"),
                "the default has to name db/migration alone, so a run compared against the fixture "
                        + "gets the fixture");
        assertFalse(yaml.contains("${SPRING_FLYWAY_LOCATIONS:classpath:db/migration,classpath:db/demo}"),
                "the default must not include the demo location");
    }

    @Test
    @DisplayName("The composition enables the demo location so the fan-out demo can authorize")
    void compositionEnablesTheDemoLocation() {
        String compose = read(platformDirectory().resolve("docker-compose.yml"));
        assertTrue(compose.contains("SPRING_FLYWAY_LOCATIONS"),
                "the composition has to name the locations for the authorization service");
        assertTrue(compose.contains("classpath:db/migration,classpath:db/demo"),
                "the demo stack has to apply both locations, or every authorization declines");
    }

    /**
     * Reads one file.
     *
     * @param file the file to read
     * @return its content
     * @throws UncheckedIOException when the file cannot be read
     */
    private static String read(Path file) {
        assertTrue(Files.isRegularFile(file), file + " is missing");
        try {
            return Files.readString(file);
        } catch (IOException unreadable) {
            throw new UncheckedIOException("could not read " + file, unreadable);
        }
    }

    /**
     * Returns the fixture-faithful migration directory of the module under test.
     *
     * @return the directory
     */
    private static Path migrationDirectory() {
        return moduleDirectory().resolve(
                Path.of("src", "main", "resources", "db", "migration"));
    }

    /**
     * Returns the demo migration directory of the module under test.
     *
     * @return the directory
     */
    private static Path demoDirectory() {
        return moduleDirectory().resolve(Path.of("src", "main", "resources", "db", "demo"));
    }

    /**
     * Returns the base directory of the module under test.
     *
     * @return the directory
     */
    private static Path moduleDirectory() {
        return platformDirectory().resolve(Path.of("services", MODULE));
    }

    /**
     * Locates the platform directory by walking up from the working directory.
     *
     * <p>{@link CardDemoFixtureLoader#fixtureDirectory()} locates the CardDemo fixtures the same
     * way, so a test runs whether it starts in the module directory or at the repository root.
     *
     * @return the {@code card-platform} directory
     * @throws IllegalStateException when no ancestor of the working directory holds it
     */
    private static Path platformDirectory() {
        for (Path candidate = Path.of("").toAbsolutePath().normalize(); candidate != null;
                candidate = candidate.getParent()) {
            Path platform = candidate.resolve("card-platform");
            if (Files.isDirectory(platform.resolve("services"))) {
                return platform;
            }
        }
        throw new IllegalStateException("no ancestor of '" + Path.of("").toAbsolutePath()
                + "' holds the card-platform directory");
    }
}
