package com.carddemo.equivalence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Requires every service that relays an outbox to publish how much of it is waiting.
 *
 * <p>A backlog is a state rather than an event, so no counter moves while it grows. The abandonment
 * counter moves only once every attempt of a row is spent, which on the shipped settings is well
 * after an operator needed to know. Between a broker becoming unreachable and the first abandonment,
 * no series of a relaying service changed at all.
 *
 * <p>This is read across services rather than inside one, because the gap was uniform. Five services
 * relay an outbox and all five reported the same nothing, so no single service's tests could show
 * it. The notification service relays no outbox and is required to declare no gauge, which keeps
 * this test from being satisfied by a copy that reads a repository the service does not have.
 *
 * <p>Two properties are read from the source rather than restated. Each gauge is registered outside
 * {@code ObservabilityConfig}, because that class takes the meter registry alone and its own tests
 * load it on that basis. And neither gauge carries a tag, because a backlog belongs to a service as
 * a whole, and a tag naming an event type or a row would make the series unbounded.
 *
 * <p>Design decisions: {@code card-platform/docs/decision-log.md}.
 */
@DisplayName("Every relaying service publishes its outbox backlog")
class OutboxBacklogVisibilityContractTest {

    /** Each relaying service module, mapped to the package segment its meter names carry. */
    private static final Map<String, String> RELAYING_SERVICES = Map.of(
            "authorization-service", "authorization",
            "ledger-posting-service", "ledger",
            "fraud-detection-service", "fraud",
            "account-service", "account",
            "card-service", "card");

    /** The service that relays nothing, and therefore declares no backlog gauge. */
    private static final String SERVICE_WITHOUT_A_RELAY = "notification-service";

    @Test
    @DisplayName("each relaying service declares both backlog gauges under its own names")
    void eachRelayingServiceDeclaresBothGauges() {
        RELAYING_SERVICES.forEach((module, segment) -> {
            String source = read(backlogMetrics(module, segment));

            assertThat(source)
                    .as("%s must name the rows it has not published", module)
                    .contains("\"carddemo." + segment + ".outbox.due\"")
                    .as("%s must name how long the longest-waiting row has waited", module)
                    .contains("\"carddemo." + segment + ".outbox.oldest.due.age\"");
            assertThat(source)
                    .as("%s must publish the age in a unit a reader can act on", module)
                    .contains("baseUnit(\"seconds\")");
            assertThat(source)
                    .as("%s must read its outbox rather than hold a number", module)
                    .contains("countDueBefore")
                    .contains("findEarliestDueBefore");
        });
    }

    @Test
    @DisplayName("neither gauge carries a tag, so neither series can grow without bound")
    void neitherGaugeCarriesATag() {
        RELAYING_SERVICES.forEach((module, segment) -> assertThat(read(backlogMetrics(module,
                segment)))
                .as("%s must not tag a reading that belongs to the whole service", module)
                .doesNotContain(".tag(")
                .doesNotContain(".tags("));
    }

    @Test
    @DisplayName("a datastore that cannot answer does not take the scrape with it")
    void aDatastoreThatCannotAnswerDoesNotTakeTheScrapeWithIt() {
        RELAYING_SERVICES.forEach((module, segment) -> {
            String source = read(backlogMetrics(module, segment));

            assertThat(source)
                    .as("%s must answer a failed read rather than let it escape a scrape", module)
                    .contains("catch (RuntimeException")
                    .contains("Double.NaN");
        });
    }

    @Test
    @DisplayName("the gauges stay out of the registry-only configuration class")
    void theGaugesStayOutOfTheRegistryOnlyConfiguration() {
        RELAYING_SERVICES.forEach((module, segment) -> {
            Path observability = platformDirectory().resolve("services").resolve(module)
                    .resolve("src/main/java/com/carddemo").resolve(segment)
                    .resolve("config/ObservabilityConfig.java");
            assertTrue(Files.isRegularFile(observability), observability + " must exist");

            assertThat(read(observability))
                    .as("%s registers its counters and timers from the registry alone, and a"
                            + " repository here would stop that class loading on its own", module)
                    .doesNotContain("outbox.due")
                    .doesNotContain("OutboxEventRepository");
        });
    }

    @Test
    @DisplayName("each relaying service names its backlog against a threshold it can act on")
    void eachRelayingServiceNamesItsBacklogAgainstAThreshold() {
        RELAYING_SERVICES.forEach((module, segment) -> {
            Path readiness = platformDirectory().resolve("services").resolve(module)
                    .resolve("src/main/java/com/carddemo").resolve(segment)
                    .resolve("config/ReadinessHealthConfig.java");
            assertTrue(Files.isRegularFile(readiness), readiness + " must exist");
            String source = read(readiness);

            assertThat(source)
                    .as("%s must read a threshold on size and one on age, because a burst raises"
                            + " size while a stopped relay raises age", module)
                    .contains("BACKLOG_DUE_THRESHOLD")
                    .contains("BACKLOG_AGE_THRESHOLD_SECONDS");
            assertThat(source)
                    .as("%s must name the state from a closed set rather than from a number", module)
                    .contains("BACKLOG_CLEAR")
                    .contains("BACKLOG_BEHIND");
            assertThat(collapseWhitespace(source))
                    .as("%s must keep an abandoned row as the only condition that fails readiness,"
                            + " so a broker outage cannot evict the one component still recording",
                            module)
                    .contains("Health.up() .withDetail(\"state\","
                            + " backlogState(due, oldestDueAgeSeconds))");
        });
    }

    @Test
    @DisplayName("the service that relays nothing declares no backlog gauge")
    void theServiceThatRelaysNothingDeclaresNoGauge() {
        Path main = platformDirectory().resolve("services").resolve(SERVICE_WITHOUT_A_RELAY)
                .resolve("src/main/java");
        assertTrue(Files.isDirectory(main), main + " must exist");

        List<Path> declaring = walk(main).stream()
                .filter(path -> read(path).contains("outbox.due"))
                .toList();

        assertThat(declaring)
                .as("this service holds no outbox and no relay, so a backlog gauge here would"
                        + " report a number nothing produces")
                .isEmpty();
    }


    /**
     * Collapses every run of whitespace to one space, so a structural check survives reformatting.
     *
     * @param source the text to normalise
     * @return the same text with each whitespace run reduced to a single space
     */
    private static String collapseWhitespace(String source) {
        return source.replaceAll("\\s+", " ");
    }

    /**
     * Locates the class that registers one service's backlog gauges.
     *
     * @param module  the service module directory
     * @param segment the package segment of that service
     * @return the path of its backlog metrics class
     */
    private static Path backlogMetrics(String module, String segment) {
        Path source = platformDirectory().resolve("services").resolve(module)
                .resolve("src/main/java/com/carddemo").resolve(segment)
                .resolve("config/OutboxBacklogMetrics.java");
        assertTrue(Files.isRegularFile(source), source + " must exist");
        return source;
    }

    /**
     * Lists every Java source file below one directory.
     *
     * @param root the directory to walk
     * @return the Java files found, in a stable order
     */
    private static List<Path> walk(Path root) {
        try (var tree = Files.walk(root)) {
            return tree.filter(path -> path.toString().endsWith(".java")).sorted().toList();
        } catch (IOException unreadable) {
            throw new UncheckedIOException("cannot walk " + root, unreadable);
        }
    }

    /**
     * Finds the platform directory from wherever the build was started.
     *
     * @return the {@code card-platform} directory
     */
    private static Path platformDirectory() {
        Path current = Path.of("").toAbsolutePath().normalize();
        while (current != null
                && !Files.isRegularFile(current.resolve("card-platform/docker-compose.yml"))) {
            current = current.getParent();
        }
        if (current == null) {
            throw new AssertionError("repository root was not found");
        }
        return current.resolve("card-platform");
    }

    /**
     * Reads one file as text.
     *
     * @param path the file to read
     * @return its content
     */
    private static String read(Path path) {
        try {
            return Files.readString(path, StandardCharsets.UTF_8);
        } catch (IOException unreadable) {
            throw new UncheckedIOException("cannot read " + path, unreadable);
        }
    }
}
