package com.carddemo.equivalence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.stream.Stream;
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
 * <p><b>Two modules carry the overlay, not one.</b> The account service owns the account record and
 * the authorization service holds a replica of the four columns its decline rules read, so one record
 * exists as two rows in two schemas. The account service publishes its expiry on every state change
 * and the authorization listener applies it, so extending the replica while leaving the record itself
 * in 2025 was not a partial fix but a broken one: the first posted transaction wrote a 2025 expiry
 * back over the extension, and reason code 103 then declined every later call on that account. The
 * assertions below therefore run over both modules, and one further assertion pins that the two
 * overlays and the two shipped defaults move together.
 *
 * <p>An expiry edited in {@code V2__seed.sql} moves the oracle the suite measures against, and
 * these tests fail on it. {@code card-platform/docs/decision-log.md} records the split.
 */
class AuthorizationDemoDataTest {

    /** Directory name of the service holding the replica the decline rules read. */
    private static final String MODULE = "authorization-service";

    /** Directory name of the service that owns the account record itself. */
    private static final String ACCOUNT_MODULE = "account-service";

    /**
     * Each module carrying the overlay, mapped to the table and column its migration writes.
     *
     * <p>The two names differ because the replica renames the column it copies:
     * {@code account_credit_snapshot.account_expiration_date} against {@code account.expiration_date}.
     */
    private static final Map<String, String> OVERLAY_TARGETS = Map.of(
            MODULE, "account_credit_snapshot|account_expiration_date",
            ACCOUNT_MODULE, "account|expiration_date");

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
    @DisplayName("Both copies of the account record carry the overlay, or the demo declines")
    void bothCopiesOfTheAccountRecordCarryTheOverlay() {
        for (Map.Entry<String, String> overlay : OVERLAY_TARGETS.entrySet()) {
            String module = overlay.getKey();
            String[] target = overlay.getValue().split("\\|");
            String table = target[0];
            String column = target[1];

            Path migration = demoDirectory(module).resolve(DEMO_MIGRATION);
            assertTrue(Files.isRegularFile(migration),
                    module + " carries no demo overlay, and one copy of the account record left in "
                            + "2025 declines every authorization on it with reason 103 once the "
                            + "other copy is extended");
            assertFalse(Files.exists(migrationDirectory(module).resolve(DEMO_MIGRATION)),
                    module + " has the demo migration in db/migration, so every run would apply it");

            String demo = read(migration);
            assertTrue(demo.contains("UPDATE " + table),
                    module + " demo overlay must write " + table);
            assertTrue(demo.contains("SET " + column + " = '" + DEMO_EXPIRY + "'"),
                    module + " demo overlay must write the documented synthetic expiry into "
                            + column);
            assertTrue(demo.contains("RAISE EXCEPTION"),
                    module + " demo overlay must assert it left no row behind, because a partial "
                            + "update shows as an unexplained decline during the demo itself");
            assertFalse(read(migrationDirectory(module).resolve("V2__seed.sql"))
                            .contains(DEMO_EXPIRY),
                    module + " has the demo expiry in V2__seed.sql, which silently alters the "
                            + "oracle the equivalence suite measures against");
        }
    }

    @Test
    @DisplayName("The account overlay writes the expiry column and nothing else")
    void theAccountOverlayTouchesOnlyTheExpiry() {
        String demo = read(demoDirectory(ACCOUNT_MODULE).resolve(DEMO_MIGRATION));

        for (String forbidden : List.of("current_balance", "credit_limit", "cash_credit_limit",
                "current_cycle_credit", "current_cycle_debit", "open_date", "reissue_date",
                "address_zip", "group_id", "active_status", "customer", "card_xref",
                "outbox_event", "processed_event", "DROP ", "DELETE ", "INSERT ")) {
            assertFalse(demo.contains(forbidden),
                    "the account demo overlay reaches " + forbidden.trim()
                            + ", so it changes more than the expiry");
        }
    }

    @Test
    @DisplayName("Every module carrying the overlay defaults to the fixture-faithful location")
    void everyOverlayModuleDefaultsToTheFixture() {
        for (String module : OVERLAY_TARGETS.keySet()) {
            String yaml = read(moduleDirectory(module).resolve(
                    Path.of("src", "main", "resources", "application.yml")));
            assertTrue(yaml.contains("locations: ${SPRING_FLYWAY_LOCATIONS:classpath:db/migration}"),
                    module + " must default to db/migration alone, so a run compared against the "
                            + "fixture gets the fixture");
            assertFalse(yaml.contains(
                            "${SPRING_FLYWAY_LOCATIONS:classpath:db/migration,classpath:db/demo}"),
                    module + " must not carry the demo location as its shipped default");
        }
    }

    /**
     * The two Deployments that carry the overlay, each with the ConfigMap key it reads.
     *
     * <p>No other Deployment sets {@code SPRING_FLYWAY_LOCATIONS} at all: the four remaining
     * modules ship one migration folder, so their images' own default is the only value.
     */
    private static final Map<String, String> OVERLAY_DEPLOYMENTS = Map.of(
            "40-authorization-service.yaml", "AUTHORIZATION_FLYWAY_LOCATIONS",
            "44-account-service.yaml", "ACCOUNT_FLYWAY_LOCATIONS");

    @Test
    @DisplayName("The composition and the ConfigMap enable the overlay for both modules together")
    void theCompositionEnablesBothOverlaysTogether() {
        String compose = read(platformDirectory().resolve("docker-compose.yml"));
        assertTrue(compose.contains(
                        "SPRING_FLYWAY_LOCATIONS: ${AUTHORIZATION_FLYWAY_LOCATIONS:-classpath:db/migration,classpath:db/demo}"),
                "the composition must apply both locations to the authorization service, or every "
                        + "authorization declines");
        assertTrue(compose.contains(
                        "SPRING_FLYWAY_LOCATIONS: ${ACCOUNT_FLYWAY_LOCATIONS:-classpath:db/migration,classpath:db/demo}"),
                "the composition must apply both locations to the account service too, or the first "
                        + "posted transaction writes a 2025 expiry back over the extended replica");

        String environmentExample = read(platformDirectory().resolve(".env.example"));
        assertTrue(environmentExample.contains(
                        "ACCOUNT_FLYWAY_LOCATIONS=classpath:db/migration,classpath:db/demo"),
                "the documented environment must carry the account locations beside the "
                        + "authorization ones, since the two are changed together or not at all");

        String configMap = read(platformDirectory()
                .resolve(Path.of("deploy", "k8s", "30-configmap.yaml")));
        assertTrue(configMap.contains(
                        "AUTHORIZATION_FLYWAY_LOCATIONS: \"classpath:db/migration,classpath:db/demo\""),
                "the ConfigMap must carry the authorization locations, because a Deployment that "
                        + "fixes the value offers no base-profile opt-out");
        assertTrue(configMap.contains(
                        "ACCOUNT_FLYWAY_LOCATIONS: \"classpath:db/migration,classpath:db/demo\""),
                "and the account locations beside them, since the two change together or not at "
                        + "all");
        assertTrue(configMap.contains("classpath:db/migration\" for a run whose output"),
                "the ConfigMap must state the base-profile opt-out where the value is set, so an "
                        + "operator can tell which profile a cluster runs");

        for (Map.Entry<String, String> deployment : OVERLAY_DEPLOYMENTS.entrySet()) {
            String manifest = read(platformDirectory()
                    .resolve(Path.of("deploy", "k8s", deployment.getKey())));
            assertTrue(manifest.contains("key: " + deployment.getValue()),
                    deployment.getKey() + " must read " + deployment.getValue()
                            + " from the ConfigMap, so the cluster path carries the same opt-out "
                            + "the composition carries");
            assertFalse(manifest.contains("value: \"classpath:db/migration,classpath:db/demo\""),
                    deployment.getKey() + " fixes the locations in the Deployment, which is what "
                            + "made the two deployment paths disagree about the profile");
        }
    }

    /**
     * No migration may write a placeholder expression, comments included.
     *
     * <p>Flyway substitutes placeholders across the whole text of a migration before parsing it,
     * so a dollar sign followed by a brace is a placeholder wherever it appears, and an unresolved
     * one answers with a refusal to start rather than a warning. A comment is the likely place for
     * one, because a comment is where a file explains which property selects it, and naming that
     * property with its default is the natural way to write the sentence.
     *
     * <p>This is not hypothetical. A revision of the overlay's own header quoted
     * {@code SPRING_FLYWAY_LOCATIONS} with its default in that form, and every service that
     * applies the overlay then failed at start-up with "No value provided for placeholder", while
     * no test noticed: the suites run against {@code classpath:db/migration} alone, which is the
     * one location that never reads the file carrying the comment.
     */
    @Test
    @DisplayName("No migration or overlay file carries a placeholder expression")
    void noMigrationCarriesAPlaceholderExpression() {
        Pattern placeholder = Pattern.compile("\\$\\{");
        for (String module : List.of("authorization-service", "ledger-posting-service",
                "fraud-detection-service", "notification-service", "account-service",
                "card-service")) {
            Path resources = moduleDirectory(module).resolve(Path.of("src", "main", "resources"));
            for (String location : List.of("db/migration", "db/demo")) {
                Path directory = resources.resolve(location);
                if (!Files.isDirectory(directory)) {
                    continue;
                }
                try (Stream<Path> files = Files.list(directory)) {
                    for (Path file : files.filter(Files::isRegularFile).sorted().toList()) {
                        assertFalse(placeholder.matcher(read(file)).find(),
                                module + "/" + location + "/" + file.getFileName()
                                        + " carries a placeholder expression. Flyway resolves one"
                                        + " anywhere in the text, comments included, and refuses to"
                                        + " start when it cannot. Name the property without its"
                                        + " braces.");
                    }
                } catch (IOException unreadable) {
                    throw new UncheckedIOException("cannot list " + directory, unreadable);
                }
            }
        }
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
     * Returns the fixture-faithful migration directory of the authorization module.
     *
     * @return the directory
     */
    private static Path migrationDirectory() {
        return migrationDirectory(MODULE);
    }

    /**
     * Returns the fixture-faithful migration directory of one module.
     *
     * @param module the module directory name
     * @return the directory
     */
    private static Path migrationDirectory(String module) {
        return moduleDirectory(module).resolve(
                Path.of("src", "main", "resources", "db", "migration"));
    }

    /**
     * Returns the demo migration directory of the authorization module.
     *
     * @return the directory
     */
    private static Path demoDirectory() {
        return demoDirectory(MODULE);
    }

    /**
     * Returns the demo migration directory of one module.
     *
     * @param module the module directory name
     * @return the directory
     */
    private static Path demoDirectory(String module) {
        return moduleDirectory(module).resolve(Path.of("src", "main", "resources", "db", "demo"));
    }

    /**
     * Returns the base directory of one module.
     *
     * @param module the module directory name
     * @return the directory
     */
    private static Path moduleDirectory(String module) {
        return platformDirectory().resolve(Path.of("services", module));
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
