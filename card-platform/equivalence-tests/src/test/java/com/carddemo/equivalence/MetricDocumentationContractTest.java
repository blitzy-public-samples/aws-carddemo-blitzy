package com.carddemo.equivalence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

/**
 * Requires each service to name in its own guide every meter it registers.
 *
 * <p>A metric table is read as a complete list, because nothing about it says otherwise. A reader
 * looking for the counter that says an outbox row was abandoned, finding a table of five meters and
 * no such row, concludes the service does not count it. Two guides of this platform named none of
 * their meters while registering fourteen and eight, and four named every one, so the drift was
 * invisible from any single guide.
 *
 * <p>The list is read from the code rather than restated here. Every string literal of the form
 * {@code "carddemo.<service>.<name>"} in a service's main sources is a meter name that service
 * registers, so a meter added without a line in the guide fails this test, and a meter renamed
 * in the guide alone fails it too.
 *
 * <p>Three prefixes are deliberately not service meters and are excluded by construction, because
 * each names something other than one service's instrumentation: {@code carddemo.card-token} and
 * {@code carddemo.security} are cross-service, and {@code carddemo.kafka.topics} names
 * configuration properties rather than meters. The service segment is matched exactly, so
 * none of the three is reached.
 *
 * <p>The reverse direction is checked as well. A guide naming {@code carddemo.<service>.}
 * something the service does not register is a documented meter an operator will look for and
 * never find, which is the same defect read from the other end. A service's own configuration keys
 * share that prefix and are not meters, so they are read out of its {@code application.yml} and
 * excluded rather than listed here, which keeps the exclusion accurate when a key is added.
 */
@DisplayName("Every service guide names every meter its service registers")
class MetricDocumentationContractTest {

    /** Each service module, mapped to the segment its own meter names carry. */
    private static final Map<String, String> SERVICE_SEGMENTS = Map.of(
            "authorization-service", "authorization",
            "ledger-posting-service", "ledger",
            "fraud-detection-service", "fraud",
            "notification-service", "notification",
            "account-service", "account",
            "card-service", "card");

    /** The count each guide is expected to carry, measured from the shipped instrumentation. */
    private static final Map<String, Integer> MINIMUM_METERS = Map.of(
            "authorization-service", 7,
            "ledger-posting-service", 5,
            "fraud-detection-service", 6,
            "notification-service", 6,
            "account-service", 14,
            "card-service", 8);

    @Test
    @DisplayName("no service registers a meter its guide leaves out")
    void everyRegisteredMeterIsNamedInItsGuide() {
        List<String> undocumented = new ArrayList<>();
        for (Map.Entry<String, String> service : SERVICE_SEGMENTS.entrySet()) {
            Set<String> registered = registeredMeters(service.getKey(), service.getValue());
            int expected = MINIMUM_METERS.get(service.getKey());
            assertTrue(registered.size() >= expected,
                    service.getKey() + " registers " + registered.size() + " meters where "
                            + expected + " were measured, so either instrumentation was removed or"
                            + " this test's search has drifted");

            String guide = read(serviceReadme(service.getKey()));
            for (String meter : registered) {
                if (!guide.contains(meter)) {
                    undocumented.add(service.getKey() + " does not name " + meter);
                }
            }
        }
        assertThat(undocumented)
                .as("a guide that names some of its meters is read as naming all of them")
                .isEmpty();
    }

    @Test
    @DisplayName("no guide names a meter its service does not register")
    void everyDocumentedMeterIsRegistered() {
        List<String> phantom = new ArrayList<>();
        for (Map.Entry<String, String> service : SERVICE_SEGMENTS.entrySet()) {
            Set<String> registered = registeredMeters(service.getKey(), service.getValue());
            Set<String> configured = configurationKeys(service.getKey());
            String prefix = "carddemo." + service.getValue() + ".";
            Matcher named = Pattern.compile("`(" + Pattern.quote(prefix) + "[a-z.\\-]+)`")
                    .matcher(read(serviceReadme(service.getKey())));
            while (named.find()) {
                String documented = named.group(1);
                if (!registered.contains(documented) && !configured.contains(documented)) {
                    phantom.add(service.getKey() + " names " + documented
                            + ", which is neither a registered meter nor a configuration key");
                }
            }
        }
        assertThat(phantom)
                .as("a documented meter an operator cannot find is the same defect read backwards")
                .isEmpty();
    }

    /**
     * Reads the meter names one service registers, out of its own main sources.
     *
     * @param module  the service module directory
     * @param segment the service segment its meter names carry
     * @return every distinct meter name found
     */
    private static Set<String> registeredMeters(String module, String segment) {
        Pattern meter = Pattern.compile(
                "\"(carddemo\\." + Pattern.quote(segment) + "\\.[a-z][a-z.\\-]*)\"");
        Path root = platformDirectory().resolve("services").resolve(module)
                .resolve("src/main/java");
        assertTrue(Files.isDirectory(root), root + " must exist");

        Set<String> found = new LinkedHashSet<>();
        try (Stream<Path> tree = Files.walk(root)) {
            tree.filter(path -> path.toString().endsWith(".java")).sorted().forEach(path -> {
                Matcher matcher = meter.matcher(read(path));
                while (matcher.find()) {
                    found.add(matcher.group(1));
                }
            });
        } catch (IOException unreadable) {
            throw new UncheckedIOException("cannot walk " + root, unreadable);
        }
        return found;
    }

    /**
     * Reads the dotted configuration keys one service binds, out of its shipped configuration.
     *
     * <p>A key such as {@code carddemo.fraud.risk.velocity-window-minutes} shares the prefix a
     * meter name carries and is not a meter. Reading the keys rather than naming them keeps this
     * exclusion true as the configuration grows.
     *
     * @param module the service module directory
     * @return every dotted key under {@code carddemo} in that service's {@code application.yml}
     */
    private static Set<String> configurationKeys(String module) {
        Path configuration = platformDirectory().resolve("services").resolve(module)
                .resolve("src/main/resources/application.yml");
        assertTrue(Files.isRegularFile(configuration), configuration + " must exist");

        Set<String> keys = new LinkedHashSet<>();
        Object document = new Yaml().load(read(configuration));
        if (document instanceof Map<?, ?> root && root.get("carddemo") instanceof Map<?, ?> owned) {
            flatten("carddemo", owned, keys);
        }
        return keys;
    }

    /**
     * Adds every dotted path of one configuration subtree, including the intermediate ones.
     *
     * <p>The intermediate paths are wanted: a guide naming {@code carddemo.fraud.risk} to describe
     * a group of settings is naming configuration and not a meter.
     *
     * @param prefix the path reached so far
     * @param node   the subtree below it
     * @param into   the set collecting the paths
     */
    private static void flatten(String prefix, Map<?, ?> node, Set<String> into) {
        for (Map.Entry<?, ?> entry : node.entrySet()) {
            String path = prefix + "." + entry.getKey();
            into.add(path);
            if (entry.getValue() instanceof Map<?, ?> child) {
                flatten(path, child, into);
            }
        }
    }

    /**
     * Locates one service's guide.
     *
     * @param module the service module directory
     * @return the path of its {@code README.md}
     */
    private static Path serviceReadme(String module) {
        Path guide = platformDirectory().resolve("services").resolve(module).resolve("README.md");
        assertTrue(Files.isRegularFile(guide), guide + " must exist");
        return guide;
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
