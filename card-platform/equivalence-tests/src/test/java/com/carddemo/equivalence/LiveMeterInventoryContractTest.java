package com.carddemo.equivalence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tag;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
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
import java.util.TreeMap;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

/**
 * Reads every meter each service registers at start-up and requires its own guide to name it.
 *
 * <p>This class has no COBOL ancestor. The CardDemo source reports through {@code DISPLAY} to
 * {@code SYSOUT} and registers nothing, so it has no inventory to compare against.
 *
 * <p>A companion test reads the same guides against the meter names appearing as string literals in
 * each service's sources. That comparison is exact in both directions and it is still only a
 * comparison of text against text: a literal that no bean ever registers passes it, and so does a
 * name whose instrument is a counter where its guide calls it a gauge. This class registers the
 * meters instead, then reads the registry.
 *
 * <p>Four properties are read from live registration. Every registered name appears in the guide of
 * the service that registered it. Every tag key comes from a closed set, because a tag key nothing
 * expects is how a series becomes unbounded. No tag value looks like an account identifier, a card
 * number or an amount. And where a guide states the instrument type, the registered instrument is
 * that type.
 *
 * <p>Each service is started as a bare context holding one registry and its own configuration class,
 * which is how each service's own tests start it. No database, no broker and no container is
 * involved. Meters a service registers outside that class, such as a filter that builds its own
 * counter on construction, are outside what this test can register and are covered by the literal
 * comparison instead.
 *
 * <p>Design decisions: {@code card-platform/docs/decision-log.md}.
 */
@DisplayName("Every meter registered at start-up is named in its own service guide")
class LiveMeterInventoryContractTest {

    /** Each service module, mapped to the package segment of its configuration class. */
    private static final Map<String, String> SERVICES = new TreeMap<>(Map.of(
            "authorization-service", "authorization",
            "ledger-posting-service", "ledger",
            "fraud-detection-service", "fraud",
            "notification-service", "notification",
            "account-service", "account",
            "card-service", "card"));

    /**
     * Every tag key any meter of this platform is permitted to carry.
     *
     * <p>The set is closed on purpose. Each value of each key below is drawn from a fixed list in
     * code, so the number of series a name can produce is known before the first message.
     *
     * <p>Two spellings of one idea appear here. Five services tag an event type {@code eventType} and
     * the notification service tags it {@code event.type}. Both are named in the guide of the service
     * that uses them, so both are read as correct here. Renaming either would change a series a
     * dashboard already reads, which is recorded as a follow-up rather than done quietly.
     */
    private static final Set<String> ALLOWED_TAG_KEYS = Set.of(
            "service", "outcome", "stage", "eventType", "event.type", "operation", "failure.kind",
            "format", "reason");

    @Test
    @DisplayName("no service registers a meter its own guide leaves unnamed")
    void everyRegisteredMeterIsNamedInItsGuide() {
        List<String> undocumented = new ArrayList<>();

        SERVICES.forEach((module, segment) -> {
            String guide = read(guideOf(module));
            Set<String> registered = registerLive(module, segment);

            assertThat(registered)
                    .as("%s registered no meter at all, so this test proved nothing about it",
                            module)
                    .isNotEmpty();
            registered.stream()
                    .filter(name -> !guide.contains("`" + name + "`"))
                    .forEach(name -> undocumented.add(module + " registers " + name));
        });

        assertThat(undocumented)
                .as("each of these is registered at start-up and named in no guide, so an operator"
                        + " reading the guide as a list would not know it exists")
                .isEmpty();
    }

    @Test
    @DisplayName("every tag key comes from the closed set, so no series can grow without bound")
    void everyTagKeyComesFromTheClosedSet() {
        List<String> unexpected = new ArrayList<>();

        SERVICES.forEach((module, segment) -> withLiveRegistry(module, segment, registry ->
                registry.getMeters().forEach(meter -> meter.getId().getTags().stream()
                        .map(Tag::getKey)
                        .filter(key -> !ALLOWED_TAG_KEYS.contains(key))
                        .forEach(key -> unexpected.add(
                                module + " tags " + meter.getId().getName() + " by " + key)))));

        assertThat(unexpected).as("a tag key outside the closed set").isEmpty();
    }

    @Test
    @DisplayName("every tag key a service registers is named in that service's own guide")
    void everyTagKeyIsNamedInItsOwnGuide() {
        List<String> undocumented = new ArrayList<>();

        SERVICES.forEach((module, segment) -> {
            String guide = read(guideOf(module));
            withLiveRegistry(module, segment, registry -> registry.getMeters()
                    .forEach(meter -> meter.getId().getTags().stream()
                            .map(Tag::getKey)
                            .filter(key -> !"service".equals(key))
                            .filter(key -> !namesTagKey(guide, key))
                            .forEach(key -> undocumented.add(module + " tags by " + key))));
        });

        assertThat(undocumented)
                .as("a tag a service registers and its guide never names is a dimension a reader"
                        + " cannot group by, because nothing tells them it is there")
                .isEmpty();
    }

    @Test
    @DisplayName("no tag value carries an identifier, a card number or an amount")
    void noTagValueCarriesAnIdentifier() {
        List<String> leaking = new ArrayList<>();

        SERVICES.forEach((module, segment) -> withLiveRegistry(module, segment, registry ->
                registry.getMeters().forEach(meter -> meter.getId().getTags().forEach(tag -> {
                    String value = tag.getValue();
                    if (value.matches(".*\\d{4,}.*") || value.matches(".*\\d+[.,]\\d{2}.*")) {
                        leaking.add(module + " tags " + meter.getId().getName() + " with " + value);
                    }
                }))));

        assertThat(leaking)
                .as("a tag value holding four or more consecutive digits, or an amount, would make"
                        + " the series unbounded and would publish the value besides")
                .isEmpty();
    }

    @Test
    @DisplayName("where a guide states the instrument type, the registered instrument is that type")
    void statedInstrumentTypesMatchTheRegisteredInstruments() {
        List<String> mismatched = new ArrayList<>();

        SERVICES.forEach((module, segment) -> {
            Map<String, String> stated = statedTypes(guideOf(module));
            withLiveRegistry(module, segment, registry -> registry.getMeters().forEach(meter -> {
                String declared = stated.get(meter.getId().getName());
                if (declared == null) {
                    return;
                }
                String actual = switch (meter.getId().getType()) {
                    case COUNTER -> "counter";
                    case TIMER, LONG_TASK_TIMER -> "timer";
                    case GAUGE -> "gauge";
                    default -> meter.getId().getType().name().toLowerCase();
                };
                if (!declared.equals(actual)) {
                    mismatched.add(module + " documents " + meter.getId().getName() + " as "
                            + declared + " and registers a " + actual);
                }
            }));
        });

        assertThat(mismatched)
                .as("a guide naming the wrong instrument sends a reader to write a query that"
                        + " cannot answer, such as a rate over a gauge")
                .isEmpty();
    }

    @Test
    @DisplayName("every service tag carries the name of the service that registered the meter")
    void everyServiceTagNamesItsOwnService() {
        SERVICES.forEach((module, segment) -> withLiveRegistry(module, segment, registry ->
                registry.getMeters().forEach(meter -> {
                    String owner = meter.getId().getTag("service");
                    if (owner != null) {
                        assertThat(owner)
                                .as("%s registered %s under the service tag %s", module,
                                        meter.getId().getName(), owner)
                                .isEqualTo(module);
                    }
                    assertThat(meter.getId().getName())
                            .as("%s registered a meter outside its own namespace", module)
                            .startsWith("carddemo." + segment + ".");
                })));
    }


    /**
     * Answers whether a guide names one tag key.
     *
     * <p>A guide writes a key either on its own, as {@code `stage`}, or beside one of its values, as
     * {@code `outcome=published`}. Both name the key, so both count.
     *
     * @param guide the guide text
     * @param key   the tag key to look for
     * @return {@code true} when the guide names that key
     */
    private static boolean namesTagKey(String guide, String key) {
        return guide.contains("`" + key + "`") || guide.contains("`" + key + "=");
    }

    /**
     * Registers one service's meters and answers the names that appeared.
     *
     * @param module  the service module directory
     * @param segment the package segment of that service
     * @return every meter name the service registered at start-up
     */
    private static Set<String> registerLive(String module, String segment) {
        Set<String> names = new LinkedHashSet<>();
        withLiveRegistry(module, segment, registry -> registry.getMeters()
                .forEach(meter -> names.add(meter.getId().getName())));
        return names;
    }

    /**
     * Starts one service's configuration over a bare registry and hands the registry to the reader.
     *
     * @param module  the service module directory
     * @param segment the package segment of that service
     * @param reader  reads the registry once registration has happened
     */
    private static void withLiveRegistry(String module, String segment,
            java.util.function.Consumer<MeterRegistry> reader) {
        new ApplicationContextRunner()
                .withPropertyValues("spring.application.name=" + module)
                .withBean(MeterRegistry.class, () -> {
                    SimpleMeterRegistry registry = new SimpleMeterRegistry();
                    registry.config().commonTags("service", module);
                    return registry;
                })
                .withUserConfiguration(configurationClassOf(segment))
                .run(context -> {
                    assertThat(context)
                            .as("the configuration of " + module + " did not start")
                            .hasNotFailed();
                    reader.accept(context.getBean(MeterRegistry.class));
                });
    }

    /**
     * Loads the observability configuration class of one service.
     *
     * @param segment the package segment of that service
     * @return its configuration class
     */
    private static Class<?> configurationClassOf(String segment) {
        String name = "com.carddemo." + segment + ".config.ObservabilityConfig";
        try {
            return Class.forName(name);
        } catch (ClassNotFoundException absent) {
            throw new AssertionError(name + " must be on the test classpath", absent);
        }
    }

    /**
     * Reads the instrument type each guide row states, where its table carries a type column.
     *
     * <p>A row reads {@code | `name` | Type | meaning |}. Guides whose table has no type column
     * yield no entry, and their rows are then not type-checked rather than wrongly checked.
     *
     * @param guide the guide to read
     * @return each documented meter name mapped to the lower-case type its row states
     */
    private static Map<String, String> statedTypes(Path guide) {
        Map<String, String> stated = new TreeMap<>();
        java.util.regex.Pattern row = java.util.regex.Pattern.compile(
                "^\\|\\s*`(carddemo\\.[a-z.\\-]+)`\\s*\\|\\s*(Counter|Timer|Gauge)\\s*\\|",
                java.util.regex.Pattern.MULTILINE);
        java.util.regex.Matcher matcher = row.matcher(read(guide));
        while (matcher.find()) {
            stated.put(matcher.group(1), matcher.group(2).toLowerCase());
        }
        return stated;
    }

    /**
     * Locates one service's guide.
     *
     * @param module the service module directory
     * @return the path of its guide
     */
    private static Path guideOf(String module) {
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
