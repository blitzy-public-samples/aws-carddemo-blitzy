package com.carddemo.equivalence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Holds each service's readiness listener expectation to the listeners that service declares.
 *
 * <p>Readiness requires a declared number of listener containers registered and running. Comparing the
 * registry against itself instead reports ready for an empty registry, so a service whose listeners
 * never registered passed its probe and received traffic it could not consume.
 *
 * <p>A declared number brings its own failure, and this platform has already had it: a count written
 * into one service said two while three listeners were declared, and that service was permanently
 * unready. Both failures are one-sided, and the difference between them is where the disagreement
 * surfaces. A number checked against the sources fails a build; a number checked against nothing fails
 * a deployment.
 *
 * <p>So the constant is read here and compared with the {@code @KafkaListener} methods of the same
 * module. Annotations in comments are not counted, because one service's configuration explains the
 * annotation in prose and reading that as a listener would overstate the count by one.
 */
@DisplayName("Readiness listener expectations, every service")
class ReadinessListenerExpectationContractTest {

    /** Path below the platform directory to the service modules. */
    private static final String SERVICES_DIRECTORY = "card-platform/services";

    /** Path below a service module to its main sources. */
    private static final String MAIN_SOURCES = "src/main/java";

    /** The six service module directory names. */
    private static final List<String> MODULES = List.of(
            "authorization-service", "ledger-posting-service", "fraud-detection-service",
            "notification-service", "account-service", "card-service");

    /** The eleven listeners the platform declares, by module. Card serves requests only. */
    private static final Map<String, Integer> DECLARED_LISTENERS = Map.of(
            "authorization-service", 2, "ledger-posting-service", 3,
            "fraud-detection-service", 1, "notification-service", 4,
            "account-service", 1, "card-service", 0);

    /** Matches the constant each readiness configuration declares. */
    private static final Pattern DECLARED_CONSTANT =
            Pattern.compile("static final int DECLARED_LISTENERS\\s*=\\s*(\\d+)\\s*;");

    private static final Pattern LISTENER_ANNOTATION = Pattern.compile("@KafkaListener\\b");

    private static final Pattern LINE_COMMENT = Pattern.compile("//[^\\n]*");

    /** Matches one block comment, which is how every class carries its javadoc. */
    private static final Pattern BLOCK_COMMENT = Pattern.compile("/\\*.*?\\*/", Pattern.DOTALL);

    @Test
    @DisplayName("every service declares the listener count its own sources carry")
    void everyServiceDeclaresTheListenerCountItsOwnSourcesCarry() {
        Map<String, String> disagreeing = new LinkedHashMap<>();

        for (String module : MODULES) {
            int declared = declaredListenersOf(module);
            int annotated = annotatedListenersOf(module);
            if (declared != annotated) {
                disagreeing.put(module, "declares " + declared + " and annotates " + annotated);
            }
        }

        assertEquals(Map.of(), disagreeing,
                "a readiness expectation that disagrees with the listeners a service declares makes "
                        + "that service either permanently unready or ready while short of "
                        + "consumers: " + disagreeing);
    }

    @Test
    @DisplayName("the expectations match the platform inventory of eleven listeners")
    void theExpectationsMatchThePlatformInventory() {
        Map<String, Integer> declared = new LinkedHashMap<>();
        for (String module : MODULES) {
            declared.put(module, declaredListenersOf(module));
        }

        assertEquals(DECLARED_LISTENERS, declared,
                "the readiness expectations and the inventory this platform publishes have to be the "
                        + "same eleven listeners, or one of the two is describing a system that does "
                        + "not exist");
        assertEquals(11, declared.values().stream().mapToInt(Integer::intValue).sum(),
                "the platform declares eleven listeners, which ProjectionBootstrapContractTest names "
                        + "one by one");
    }

    @Test
    @DisplayName("only a service that declares no listener expects none")
    void onlyAServiceThatDeclaresNoListenerExpectsNone() {
        List<String> expectingNone = new ArrayList<>();

        for (String module : MODULES) {
            if (declaredListenersOf(module) == 0) {
                expectingNone.add(module);
            }
        }

        assertEquals(List.of("card-service"), expectingNone,
                "expecting no listener is what let an empty registry report ready, so it is correct "
                        + "for the one service that consumes nothing and wrong for every other: "
                        + expectingNone);
    }

    /**
     * Returns the count one module's readiness configuration declares.
     *
     * @param module the service module directory name
     * @return the declared listener count
     */
    private static int declaredListenersOf(String module) {
        Path configuration = sourcesOf(module).stream()
                .filter(path -> path.getFileName().toString().equals("ReadinessHealthConfig.java"))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException(module + " ships no readiness config"));
        Matcher declared = DECLARED_CONSTANT.matcher(readText(configuration));
        assertTrue(declared.find(), module
                + " declares no DECLARED_LISTENERS, so its readiness compares the registry against "
                + "itself and an empty registry reports ready");
        return Integer.parseInt(declared.group(1));
    }

    /**
     * Counts the listener annotations in one module's main sources, comments excluded.
     *
     * @param module the service module directory name
     * @return how many listener methods it declares
     */
    private static int annotatedListenersOf(String module) {
        int annotated = 0;
        for (Path source : sourcesOf(module)) {
            Matcher listener = LISTENER_ANNOTATION.matcher(withoutComments(readText(source)));
            while (listener.find()) {
                annotated++;
            }
        }
        return annotated;
    }

    /**
     * Lists every main Java source of one module.
     *
     * @param module the service module directory name
     * @return every {@code .java} file below its main source root
     */
    private static List<Path> sourcesOf(String module) {
        Path root = repositoryRoot().resolve(SERVICES_DIRECTORY).resolve(module)
                .resolve(MAIN_SOURCES);
        if (!Files.isDirectory(root)) {
            throw new IllegalStateException("no main sources sit at " + root);
        }
        try (Stream<Path> entries = Files.walk(root)) {
            return entries.filter(path -> path.getFileName().toString().endsWith(".java"))
                    .sorted()
                    .toList();
        } catch (IOException unreadable) {
            throw new UncheckedIOException("cannot walk " + root, unreadable);
        }
    }

    /**
     * Returns one source with its comments removed.
     *
     * @param source the source text
     * @return the same text with block and line comments removed
     */
    private static String withoutComments(String source) {
        return LINE_COMMENT.matcher(BLOCK_COMMENT.matcher(source).replaceAll("")).replaceAll("");
    }

    /**
     * Reads a file as text, turning the checked failure into an unchecked one.
     *
     * @param path the file to read
     * @return its contents
     */
    private static String readText(Path path) {
        try {
            return Files.readString(path, StandardCharsets.UTF_8);
        } catch (IOException unreadable) {
            throw new UncheckedIOException("cannot read " + path, unreadable);
        }
    }

    /**
     * Returns the repository root, being the ancestor of the fixture directory.
     *
     * @return that directory
     */
    private static Path repositoryRoot() {
        return CardDemoFixtureLoader.fixtureDirectory().getParent().getParent().getParent();
    }
}
