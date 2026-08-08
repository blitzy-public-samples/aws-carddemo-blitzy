package com.carddemo.card.config;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Holds every test class that owns a database container to one rule: no scheduled work of this
 * service repeats while that container is alive.
 *
 * <p>Two components of this module carry {@code @Scheduled}. {@code outbox/OutboxRelay} sweeps
 * every {@code carddemo.outbox.relay.fixed-delay-ms}, which the shipped file sets to 500
 * milliseconds, and {@code domain/RetentionSweep} runs every {@code
 * carddemo.retention.sweep-interval-ms}, which it sets to an hour. A class that owns its container
 * starts one, runs its tests and stops it, and the Spring context outlives the class because the
 * framework caches it. A sweep that is still repeating when the container stops reaches a closed
 * connection pool and logs a failure that belongs to nothing the suite asserted.
 *
 * <p>That noise is worse than it looks. It is shaped exactly like a real failure, it appears on an
 * otherwise green run, and whether it appears at all depends on where the sweep happens to be when
 * the container goes. Three classes of this module left the relay at its 500-millisecond default
 * and were one unlucky interleaving away from reporting it.
 *
 * <p>The rule this test applies is not "declare both settings". It is that a scheduled task must
 * not repeat inside the lifetime of a test class. A delay of an hour satisfies that: the task runs
 * once at start-up, while the container is up, and its next run is never reached. So an absent
 * setting passes only when the shipped default is already an hour or more, which is why the relay
 * must always be named and the retention sweep need not be.
 *
 * <p>Standing the schedule down is not the only way to satisfy the rule, because it is not always
 * available. {@code messaging/CardEventPublicationTest} waits for the scheduled sweep to place an
 * event on the topic, so the tick is the thing it asserts on and a stood-down schedule would leave
 * it waiting for a sweep that never comes. That class keeps its tick and stops it in an
 * {@code @AfterAll} through {@code ScheduledWorkShutdown}, while the containers are still up.
 * Either remedy passes; neither being present does not.
 */
@DisplayName("Scheduled work stands down in every card test that owns a container")
class ScheduledWorkStandDownTest {

    /** Shortest repeat this rule tolerates inside one test class, in milliseconds. */
    private static final long MINIMUM_STOOD_DOWN_MS = 60_000L;

    /** The relay sweep interval. Its shipped default repeats, so every such class must name it. */
    private static final String RELAY_DELAY_KEY = "carddemo.outbox.relay.fixed-delay-ms";

    /** The retention sweep interval. Its shipped default is already an hour. */
    private static final String RETENTION_INTERVAL_KEY = "carddemo.retention.sweep-interval-ms";

    /**
     * The other remedy: the class stops scheduled work itself, before its containers go.
     *
     * <p>Matched at the head of a trimmed line for the same reason as the container marker, so this
     * file's own mention of the helper does not count as a use of it.
     */
    private static final Pattern TEARDOWN_GUARD = Pattern.compile(
            "^\\s*ScheduledWorkShutdown\\.stopBefore\\(", Pattern.MULTILINE);

    /**
     * Marks a class that starts a container of its own.
     *
     * <p>Matched at the head of a trimmed line, so an annotation counts and a mention of the same
     * text inside a string literal does not. This file carries such a literal, and without the
     * anchor it would report itself.
     */
    private static final Pattern CONTAINER_MARKER =
            Pattern.compile("^\\s*@Container\\b", Pattern.MULTILINE);

    @Test
    @DisplayName("every container-owning class either stands the relay down or stops it itself")
    void theRelaySweepStandsDownWhereverAContainerIsOwned() {
        List<Path> owners = containerOwningTests();
        assertFalse(owners.isEmpty(), "no test class of this module owns a container, so this rule "
                + "has stopped measuring anything and its search has drifted");

        List<String> offenders = new ArrayList<>();
        for (Path source : owners) {
            String text = read(source);
            if (TEARDOWN_GUARD.matcher(text).find()) {
                continue;
            }
            Long declared = declaredValue(text, RELAY_DELAY_KEY);
            if (declared == null) {
                offenders.add(source.getFileName() + " neither names " + RELAY_DELAY_KEY
                        + " nor stops scheduled work before teardown, so the relay keeps its"
                        + " repeating default");
            } else if (declared < MINIMUM_STOOD_DOWN_MS) {
                offenders.add(source.getFileName() + " sets " + RELAY_DELAY_KEY + " to " + declared
                        + "ms, which repeats inside one test class");
            }
        }
        assertTrue(offenders.isEmpty(),
                () -> "scheduled work outlives a container in: " + offenders);
    }

    @Test
    @DisplayName("a class that does name the retention interval stands it down too")
    void theRetentionSweepIsNeverTunedDownwards() {
        List<String> offenders = new ArrayList<>();
        for (Path source : containerOwningTests()) {
            Long declared = declaredValue(read(source), RETENTION_INTERVAL_KEY);
            if (declared != null && declared < MINIMUM_STOOD_DOWN_MS) {
                offenders.add(source.getFileName() + " sets " + RETENTION_INTERVAL_KEY + " to "
                        + declared + "ms");
            }
        }
        assertTrue(offenders.isEmpty(),
                () -> "a retention sweep was tuned to repeat inside one test class: " + offenders);
    }

    @Test
    @DisplayName("the class that keeps its tick stops it before its containers go")
    void theClassThatKeepsItsTickCarriesTheTeardownGuard() {
        List<Path> owners = containerOwningTests();
        List<Path> guarded = owners.stream()
                .filter(source -> TEARDOWN_GUARD.matcher(read(source)).find())
                .toList();
        List<Path> unguarded = owners.stream()
                .filter(source -> !TEARDOWN_GUARD.matcher(read(source)).find())
                .toList();

        assertTrue(guarded.stream().anyMatch(source -> source.getFileName().toString()
                        .equals("CardEventPublicationTest.java")),
                () -> "CardEventPublicationTest waits for the scheduled sweep, so it must stop that"
                        + " sweep itself. Guarded classes found: " + guarded);
        assertFalse(unguarded.isEmpty(), "every container-owning class now carries the teardown"
                + " guard, so the property remedy has stopped being exercised and this rule no"
                + " longer distinguishes the two");
    }

    /**
     * Reads the milliseconds a source assigns to one property, following one level of constant
     * reference.
     *
     * <p>A class may write the value inline or name a constant, and both forms appear in this
     * module. Where a constant is named its declaration is read from the same file.
     *
     * @param text the test source
     * @param key  the property to look for
     * @return the assigned milliseconds, or {@code null} when the source assigns it nothing
     */
    private static Long declaredValue(String text, String key) {
        Matcher inline = Pattern.compile(
                "\"" + Pattern.quote(key) + "=([0-9_]+)\"").matcher(text);
        if (inline.find()) {
            return Long.parseLong(inline.group(1).replace("_", ""));
        }
        Matcher named = Pattern.compile(
                "\"" + Pattern.quote(key) + "=\"\\s*\\+\\s*(?:[A-Za-z0-9_]+\\.)?([A-Za-z0-9_]+)")
                .matcher(text);
        if (!named.find()) {
            return null;
        }
        Matcher constant = Pattern.compile(
                "\\b" + Pattern.quote(named.group(1)) + "\\s*=\\s*\"?([0-9_]+)\"?").matcher(text);
        return constant.find() ? Long.parseLong(constant.group(1).replace("_", "")) : null;
    }

    /**
     * Lists every test source of this module that starts a container of its own.
     *
     * @return the sources declaring a container field
     */
    private static List<Path> containerOwningTests() {
        Path root = testSourceRoot();
        try (Stream<Path> tree = Files.walk(root)) {
            return tree.filter(path -> path.toString().endsWith(".java"))
                    .filter(path -> CONTAINER_MARKER.matcher(read(path)).find())
                    .sorted()
                    .toList();
        } catch (IOException unreadable) {
            throw new UncheckedIOException("the card test sources could not be walked", unreadable);
        }
    }

    /**
     * Finds the test source root whether the build runs from the module or from the aggregator.
     *
     * @return the directory holding this module's test sources
     */
    private static Path testSourceRoot() {
        Path relative = Path.of("src", "test", "java", "com", "carddemo", "card");
        Path directory = Path.of("").toAbsolutePath();
        for (Path candidate : List.of(directory.resolve(relative),
                directory.resolve("services").resolve("card-service").resolve(relative))) {
            if (Files.isDirectory(candidate)) {
                return candidate;
            }
        }
        return fail("the card test sources were not found from " + directory);
    }

    /**
     * Reads one source file.
     *
     * @param source the file to read
     * @return its text
     */
    private static String read(Path source) {
        try {
            return Files.readString(source, StandardCharsets.UTF_8);
        } catch (IOException unreadable) {
            throw new UncheckedIOException("unreadable test source " + source, unreadable);
        }
    }
}
