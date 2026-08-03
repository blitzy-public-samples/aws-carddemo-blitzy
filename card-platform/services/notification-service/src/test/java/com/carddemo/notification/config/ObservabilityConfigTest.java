package com.carddemo.notification.config;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tag;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.stream.Collectors;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.EnumerablePropertySource;
import org.springframework.core.env.Environment;
import org.springframework.core.env.PropertySource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Asserts the notification service registers five meters under one prefix, and asserts the
 * observability properties {@code src/main/resources/application.yml} declares. Every test starts a
 * bare application context holding one {@link SimpleMeterRegistry}, so {@code mvn test} runs with no
 * database, no message broker and no container.
 *
 * <p>Meter provenance. {@code app/cbl/CBTRN02C.cbl:L185-L186} declares
 * {@code WS-TRANSACTION-COUNT} and {@code WS-REJECT-COUNT}, both counting up from zero.
 * {@code app/cbl/CBTRN02C.cbl:L229-L230} sets return code 4 once the reject count passes zero, so
 * the failure counter reports the number a batch run reported.
 * {@code app/cbl/CBSTM03A.CBL:L359-L360} names one failing operation and prints its return code,
 * the shape the {@code failure.kind} tag carries.</p>
 *
 * <p>Format provenance. {@code app/cbl/CBSTM03A.CBL:L85-L146} holds the fixed-column text layout and
 * {@code app/cbl/CBSTM03A.CBL:L148} declares {@code HTML-LINES}. The source renders two formats, and
 * the {@code format} tag carries one value per format plus the fallback series
 * {@code ObservabilityConfig.NotificationMetrics} declares.</p>
 *
 * <p>Metrics and structured logging are additive. The source answers an infrastructure fault with
 * the abend routine at {@code app/cbl/CBTRN02C.cbl:L707-L711} and formats a two-byte file status by
 * hand at {@code app/cbl/CBTRN02C.cbl:L714-L727}. See {@code card-platform/docs/decision-log.md}
 * for the decisions behind this module.</p>
 */
@DisplayName("ObservabilityConfig, five meters under one prefix and the observability properties of"
        + " the notification service")
class ObservabilityConfigTest {

    /** Prefix every meter of this module carries. No meter sits outside it. */
    private static final String METER_PREFIX = "carddemo.notification";

    /** The five meter names, each read verbatim from {@link ObservabilityConfig}. */
    private static final String EVENTS_CONSUMED = "carddemo.notification.events.consumed";
    private static final String PROCESSING_LATENCY = "carddemo.notification.processing.latency";
    private static final String FAILURES = "carddemo.notification.failures";
    private static final String NOTIFICATIONS_RENDERED =
            "carddemo.notification.notifications.rendered";
    private static final String DUPLICATES_SKIPPED = "carddemo.notification.duplicates.skipped";

    /** The five names as one set. A sixth meter name fails the set comparison. */
    private static final Set<String> DECLARED_METER_NAMES = Set.of(EVENTS_CONSUMED,
            PROCESSING_LATENCY, FAILURES, NOTIFICATIONS_RENDERED, DUPLICATES_SKIPPED);

    /** The four meter names a {@link Counter} carries. */
    private static final List<String> COUNTER_NAMES =
            List.of(EVENTS_CONSUMED, FAILURES, NOTIFICATIONS_RENDERED, DUPLICATES_SKIPPED);

    /** The three tag keys, one per tagged meter. */
    private static final String EVENT_TYPE_TAG = "event.type";
    private static final String FAILURE_KIND_TAG = "failure.kind";
    private static final String FORMAT_TAG = "format";

    /** Tag value every tagged meter registers for input outside its own set. */
    private static final String FALLBACK_TAG_VALUE = "unknown";

    /** Tag values the {@code event.type} dimension carries, three event types and the fallback. */
    private static final Set<String> EVENT_TYPE_VALUES = Set.of("TransactionPosted", "FraudFlagged",
            "FraudCleared", FALLBACK_TAG_VALUE);

    /** Tag values the {@code format} dimension carries, one per renderer and the fallback. */
    private static final Set<String> FORMAT_VALUES = Set.of("text", "html", FALLBACK_TAG_VALUE);

    /** Name prefix the failure-kind constants share. */
    private static final String FAILURE_CONSTANT_PREFIX = "FAILURE_";

    /**
     * Every public string constant {@code ObservabilityConfig.NotificationMetrics} declares, mapped
     * to the literal it holds. The comparison runs both ways, so an added, renamed or retyped
     * constant fails.
     */
    private static final Map<String, String> DECLARED_TAG_VALUE_CONSTANTS = Map.ofEntries(
            Map.entry("EVENT_TRANSACTION_POSTED", "TransactionPosted"),
            Map.entry("EVENT_FRAUD_FLAGGED", "FraudFlagged"),
            Map.entry("EVENT_FRAUD_CLEARED", "FraudCleared"),
            Map.entry("FAILURE_SCHEMA_VALIDATION", "schema_validation"),
            Map.entry("FAILURE_DESERIALIZATION", "deserialization"),
            Map.entry("FAILURE_PERSISTENCE", "persistence"),
            Map.entry("FAILURE_RENDERING", "rendering"),
            Map.entry("FORMAT_TEXT", "text"),
            Map.entry("FORMAT_HTML", "html"),
            Map.entry("UNKNOWN", FALLBACK_TAG_VALUE));

    /** Property keys this test reads from the loaded {@code application.yml}. */
    private static final String EXPOSURE_KEY = "management.endpoints.web.exposure.include";
    private static final String PROMETHEUS_EXPORT_KEY =
            "management.prometheus.metrics.export.enabled";
    private static final String CONSOLE_FORMAT_KEY = "logging.structured.format.console";
    private static final String ROOT_LEVEL_KEY = "logging.level.root";
    private static final String PLATFORM_LEVEL_KEY = "logging.level.com.carddemo";
    private static final String STATEMENT_LOGGER_KEY = "logging.level.org.hibernate.SQL";
    private static final String PARAMETER_LOGGER_KEY =
            "logging.level.org.hibernate.orm.jdbc.bind";

    /** Property name prefixes {@code application.yml} declares nothing under. */
    private static final String LOG_PATTERN_PREFIX = "logging.pattern.";
    private static final String LOG_FILE_PREFIX = "logging.file.";

    /** Endpoint names the exposure list holds. */
    private static final Set<String> EXPOSED_ENDPOINTS = Set.of("health", "metrics", "prometheus");

    /** Endpoint names the exposure list withholds. Each one reports runtime internals. */
    private static final Set<String> WITHHELD_ENDPOINTS = Set.of("env", "configprops", "beans",
            "shutdown", "threaddump", "heapdump");

    /** Classpath resources this module declares none of. Each must resolve to null. */
    private static final List<String> ABSENT_CLASSPATH_RESOURCES = List.of("logback.xml",
            "logback-spring.xml", "application-test.yml", "application-test.yaml",
            "application-test.properties", "application-local.yml");

    /** Level names that print a card number into a log line. */
    private static final String DEBUG_LEVEL = "DEBUG";
    private static final String TRACE_LEVEL = "TRACE";

    /** Stand-in level for a logger key that resolves to null. */
    private static final String ABSENT = "ABSENT";

    /**
     * Starts {@link ObservabilityConfig} over one {@link SimpleMeterRegistry} and loads
     * {@code application.yml} into the environment. The initializer reads the file and starts
     * nothing else, so every property below carries the value the file declares.
     */
    private static final ApplicationContextRunner RUNNER = new ApplicationContextRunner()
            .withInitializer(new ConfigDataApplicationContextInitializer())
            .withBean(MeterRegistry.class, SimpleMeterRegistry::new)
            .withUserConfiguration(ObservabilityConfig.class);

    /**
     * Asserts every public string constant of {@code NotificationMetrics} holds the literal the
     * assertions below repeat. A rename on one side alone fails here.
     *
     * @throws IllegalAccessException when a constant refuses a read, which fails the test
     */
    @Test
    void publicTagValueConstantsHoldTheirDeclaredLiterals() throws IllegalAccessException {
        assertThat(publicStringConstants())
                .as("every public string constant of NotificationMetrics, name to value")
                .containsExactlyInAnyOrderEntriesOf(DECLARED_TAG_VALUE_CONSTANTS);
    }

    /**
     * Asserts the registry holds the five declared meter names and no sixth, and that every name
     * starts with {@code carddemo.notification}.
     */
    @Test
    @DisplayName("the registry holds five meter names, all under one prefix")
    void theFiveDeclaredMeterNamesAreTheOnlyOnesInTheRegistry() {
        RUNNER.run(context -> {
            assertThat(context).hasNotFailed();
            Set<String> names = meterNamesOf(context.getBean(MeterRegistry.class));

            assertThat(names)
                    .as("meter names this module registers")
                    .containsExactlyInAnyOrderElementsOf(DECLARED_METER_NAMES)
                    .hasSize(5)
                    .allSatisfy(name -> assertThat(name).startsWith(METER_PREFIX + "."));
        });
    }

    /**
     * Asserts the kind of each meter. Four register as a {@link Counter} and the latency meter
     * registers as a {@link Timer}.
     */
    @Test
    void fourMetersAreCountersAndProcessingLatencyIsATimer() {
        RUNNER.run(context -> {
            MeterRegistry registry = context.getBean(MeterRegistry.class);

            for (String name : COUNTER_NAMES) {
                assertThat(metersNamed(registry, name))
                        .as("meters registered under %s", name)
                        .isNotEmpty()
                        .allSatisfy(meter -> assertThat(meter).isInstanceOf(Counter.class));
            }
            assertThat(metersNamed(registry, PROCESSING_LATENCY))
                    .as("meters registered under %s", PROCESSING_LATENCY)
                    .isNotEmpty()
                    .allSatisfy(meter -> assertThat(meter).isInstanceOf(Timer.class));
        });
    }

    /**
     * Asserts each tagged meter carries one tag key and no other. A registry-wide tag adds a second
     * key to every meter, which fails this test.
     */
    @Test
    void eachTaggedMeterCarriesExactlyOneTagKey() {
        RUNNER.run(context -> {
            MeterRegistry registry = context.getBean(MeterRegistry.class);
            Map<String, String> keyByMeter = Map.of(
                    EVENTS_CONSUMED, EVENT_TYPE_TAG,
                    PROCESSING_LATENCY, EVENT_TYPE_TAG,
                    FAILURES, FAILURE_KIND_TAG,
                    NOTIFICATIONS_RENDERED, FORMAT_TAG);

            keyByMeter.forEach((name, tagKey) -> assertThat(tagKeysOf(registry, name))
                    .as("tag keys meter %s carries", name)
                    .containsExactly(tagKey));
        });
    }

    /**
     * Asserts the {@code event.type} dimension carries the three consumed event types and the
     * fallback series. {@code FraudFlagged} and {@code FraudCleared} share one topic, and the
     * envelope event type separates them.
     */
    @Test
    @DisplayName("event.type carries TransactionPosted, FraudFlagged, FraudCleared and the fallback")
    void theEventTypeTagCarriesThreeConsumedTypesAndTheFallbackSeries() {
        RUNNER.run(context -> {
            MeterRegistry registry = context.getBean(MeterRegistry.class);

            for (String name : List.of(EVENTS_CONSUMED, PROCESSING_LATENCY)) {
                assertThat(tagValuesOf(registry, name, EVENT_TYPE_TAG))
                        .as("event.type values meter %s carries", name)
                        .containsExactlyInAnyOrderElementsOf(EVENT_TYPE_VALUES);
            }
        });
    }

    /**
     * Asserts the {@code format} dimension carries one value per renderer and the fallback series.
     * The source renders plain text at {@code app/cbl/CBSTM03A.CBL:L85-L146} and markup from
     * {@code app/cbl/CBSTM03A.CBL:L148}, so a third format has no source.
     */
    @Test
    void theFormatTagCarriesTextHtmlAndTheFallbackSeries() {
        RUNNER.run(context -> {
            Set<String> values = tagValuesOf(context.getBean(MeterRegistry.class),
                    NOTIFICATIONS_RENDERED, FORMAT_TAG);

            assertThat(values)
                    .as("format values meter %s carries", NOTIFICATIONS_RENDERED)
                    .containsExactlyInAnyOrderElementsOf(FORMAT_VALUES)
                    .hasSize(3);
        });
    }

    /**
     * Asserts the {@code failure.kind} dimension carries the failure kinds
     * {@code NotificationMetrics} declares as constants, and the fallback series. The set is read
     * from those constants, so this test names no failure kind of its own.
     */
    @Test
    @DisplayName("failure.kind carries the declared failure-kind constants and the fallback series")
    void theFailureKindTagValuesMatchTheDeclaredConstants() {
        RUNNER.run(context -> {
            Set<String> declared = declaredFailureKinds();
            assertThat(declared)
                    .as("failure kinds NotificationMetrics declares as constants")
                    .isNotEmpty();

            Set<String> expected = new TreeSet<>(declared);
            expected.add(FALLBACK_TAG_VALUE);

            assertThat(tagValuesOf(context.getBean(MeterRegistry.class), FAILURES,
                    FAILURE_KIND_TAG))
                    .as("failure.kind values meter %s carries", FAILURES)
                    .containsExactlyInAnyOrderElementsOf(expected);
        });
    }

    /**
     * Asserts the skipped-duplicate counter carries no tag. It is the one meter of this module with
     * a single series.
     */
    @Test
    void theDuplicatesSkippedCounterCarriesNoTag() {
        RUNNER.run(context -> {
            List<Meter> meters = metersNamed(context.getBean(MeterRegistry.class),
                    DUPLICATES_SKIPPED);

            assertThat(meters)
                    .as("meters registered under %s", DUPLICATES_SKIPPED)
                    .hasSize(1);
            assertThat(meters.get(0).getId().getTags())
                    .as("tags meter %s carries", DUPLICATES_SKIPPED)
                    .isEmpty();
        });
    }

    /**
     * Asserts every meter reads zero once the context starts. A count read registers nothing, so a
     * meter missing here is a meter created on first use.
     */
    @Test
    void everyMeterRegistersEagerlyAndReadsZero() {
        RUNNER.run(context -> {
            MeterRegistry registry = context.getBean(MeterRegistry.class);
            assertThat(meterNamesOf(registry))
                    .as("meter names present before the first message")
                    .containsExactlyInAnyOrderElementsOf(DECLARED_METER_NAMES);

            for (Meter meter : registry.getMeters()) {
                assertThat(meter)
                        .as("meter %s", meter.getId())
                        .isInstanceOfAny(Counter.class, Timer.class);
                double reading = meter instanceof Counter counter
                        ? counter.count()
                        : ((Timer) meter).count();
                assertThat(reading)
                        .as("reading of meter %s before the first message", meter.getId())
                        .isZero();
            }
        });
    }

    /** Asserts the context holds one {@code NotificationMetrics} bean and no second. */
    @Test
    void notificationMetricsIsTheOnlyBeanOfItsType() {
        RUNNER.run(context -> {
            assertThat(context).hasSingleBean(ObservabilityConfig.NotificationMetrics.class);
            assertThat(context.getBeansOfType(ObservabilityConfig.NotificationMetrics.class))
                    .as("beans of type NotificationMetrics")
                    .hasSize(1);
        });
    }

    /**
     * Asserts the exposure list names the endpoints it opens, holds no wildcard, and withholds every
     * endpoint that reports runtime internals.
     */
    @Test
    @DisplayName("actuator exposes health, metrics and prometheus, and no wildcard")
    void actuatorExposesThreeNamedEndpointsAndNoWildcard() {
        RUNNER.run(context -> {
            String exposure = context.getBean(ConfigurableEnvironment.class)
                    .getProperty(EXPOSURE_KEY);

            assertThat(exposure)
                    .as("%s with no environment variable set", EXPOSURE_KEY)
                    .isNotNull()
                    .doesNotContain("*");
            assertThat(endpointNamesIn(exposure))
                    .as("endpoint names %s opens", EXPOSURE_KEY)
                    .containsExactlyInAnyOrderElementsOf(EXPOSED_ENDPOINTS)
                    .doesNotContainAnyElementsOf(WITHHELD_ENDPOINTS);
        });
    }

    /** Asserts the Prometheus registry exports metrics, which is what a scrape reads. */
    @Test
    void prometheusMetricsExportIsEnabled() {
        RUNNER.run(context -> assertThat(context.getBean(ConfigurableEnvironment.class)
                .getProperty(PROMETHEUS_EXPORT_KEY))
                .as("%s with no environment variable set", PROMETHEUS_EXPORT_KEY)
                .isEqualTo("true"));
    }

    /**
     * Asserts one property key carries the whole structured-logging setup, and that no log pattern
     * key and no log file key resolves. The module ships no logging configuration file.
     */
    @Test
    void structuredConsoleLoggingUsesOneKeyAndNoPatternOrFileKey() {
        RUNNER.run(context -> {
            ConfigurableEnvironment environment = context.getBean(ConfigurableEnvironment.class);

            assertThat(environment.getProperty(CONSOLE_FORMAT_KEY))
                    .as("%s with no environment variable set", CONSOLE_FORMAT_KEY)
                    .isEqualTo("logstash");
            assertThat(propertyNamesStartingWith(environment, LOG_PATTERN_PREFIX))
                    .as("property names under %s", LOG_PATTERN_PREFIX)
                    .isEmpty();
            assertThat(propertyNamesStartingWith(environment, LOG_FILE_PREFIX))
                    .as("property names under %s", LOG_FILE_PREFIX)
                    .isEmpty();
        });
    }

    /**
     * Asserts neither the statement logger nor the bind-parameter logger sits at {@code DEBUG} or
     * {@code TRACE}. Either level prints a card number into a log line.
     */
    @Test
    @DisplayName("neither the statement logger nor the bind-parameter logger prints card numbers")
    void neitherStatementNorParameterLoggerSitsAtDebugOrTrace() {
        RUNNER.run(context -> {
            Environment environment = context.getBean(ConfigurableEnvironment.class);

            assertThat(declaredLevelOf(environment, STATEMENT_LOGGER_KEY))
                    .as("level of %s, or %s when the key resolves to null", STATEMENT_LOGGER_KEY,
                            ABSENT)
                    .isNotIn(DEBUG_LEVEL, TRACE_LEVEL);
            assertThat(declaredLevelOf(environment, PARAMETER_LOGGER_KEY))
                    .as("level of %s, or %s when the key resolves to null", PARAMETER_LOGGER_KEY,
                            ABSENT)
                    .isNotEqualTo(TRACE_LEVEL);
        });
    }

    /**
     * Asserts the root logger and the platform logger both resolve to {@code INFO} with no
     * environment variable set. Both keys read a variable and fall back to that level.
     */
    @Test
    @DisplayName("the root and platform log levels resolve with no environment variable set")
    void rootAndPlatformLogLevelsResolveToInfo() {
        RUNNER.run(context -> {
            Environment environment = context.getBean(ConfigurableEnvironment.class);

            assertThat(environment.getProperty(ROOT_LEVEL_KEY))
                    .as("%s with no environment variable set", ROOT_LEVEL_KEY)
                    .isEqualTo("INFO");
            assertThat(environment.getProperty(PLATFORM_LEVEL_KEY))
                    .as("%s with no environment variable set", PLATFORM_LEVEL_KEY)
                    .isEqualTo("INFO");
        });
    }

    /**
     * Asserts the class loader finds no logging configuration file and no profile companion of
     * {@code application.yml}. One file holds the whole configuration of this module.
     */
    @Test
    void noLoggingConfigurationFileOrProfileCompanionSitsOnTheClasspath() {
        ClassLoader loader = getClass().getClassLoader();

        assertThat(loader.getResource("application.yml"))
                .as("application.yml, the one configuration file of this module")
                .isNotNull();
        assertThat(ABSENT_CLASSPATH_RESOURCES)
                .as("resources this module declares none of")
                .allSatisfy(resource -> assertThat(loader.getResource(resource))
                        .as("classpath resource %s", resource)
                        .isNull());
    }

    /**
     * Asserts {@link ObservabilityConfig} is public and not final. The framework proxies a
     * configuration class by subclassing it, and a final class fails that step at start-up.
     */
    @Test
    void observabilityConfigIsPublicAndNotFinal() {
        int modifiers = ObservabilityConfig.class.getModifiers();

        assertThat(Modifier.isPublic(modifiers))
                .as("ObservabilityConfig must be public")
                .isTrue();
        assertThat(Modifier.isFinal(modifiers))
                .as("ObservabilityConfig must not be final, which the proxy step needs")
                .isFalse();
    }

    /**
     * Asserts {@code NotificationMetrics} is public, static and a declared member class of
     * {@link ObservabilityConfig}. A non-static nested class needs an enclosing instance, which the
     * bean factory has none of.
     */
    @Test
    void notificationMetricsIsAPublicStaticMemberClassOfObservabilityConfig() {
        Class<?> nested = ObservabilityConfig.NotificationMetrics.class;

        assertThat(Modifier.isPublic(nested.getModifiers()))
                .as("NotificationMetrics must be public")
                .isTrue();
        assertThat(Modifier.isStatic(nested.getModifiers()))
                .as("NotificationMetrics must be static")
                .isTrue();
        assertThat(nested.isMemberClass())
                .as("NotificationMetrics must stay a member class")
                .isTrue();
        assertThat(ObservabilityConfig.class.getDeclaredClasses())
                .as("classes ObservabilityConfig declares")
                .contains(nested);
    }

    /** Returns the distinct meter names one registry holds. */
    private static Set<String> meterNamesOf(MeterRegistry registry) {
        return registry.getMeters().stream().map(meter -> meter.getId().getName())
                .collect(Collectors.toCollection(TreeSet::new));
    }

    /** Returns the meters one name identifies, one per registered tag value. */
    private static List<Meter> metersNamed(MeterRegistry registry, String name) {
        return registry.getMeters().stream()
                .filter(meter -> meter.getId().getName().equals(name)).toList();
    }

    /** Returns the tag keys the meters under one name carry. */
    private static Set<String> tagKeysOf(MeterRegistry registry, String name) {
        return metersNamed(registry, name).stream()
                .flatMap(meter -> meter.getId().getTags().stream()).map(Tag::getKey)
                .collect(Collectors.toCollection(TreeSet::new));
    }

    /** Returns the values the meters under one name carry for one tag key. */
    private static Set<String> tagValuesOf(MeterRegistry registry, String name, String tagKey) {
        return metersNamed(registry, name).stream().map(meter -> meter.getId().getTag(tagKey))
                .filter(Objects::nonNull).collect(Collectors.toCollection(TreeSet::new));
    }

    /**
     * Returns every public static final string constant {@code NotificationMetrics} declares, keyed
     * by field name.
     *
     * @throws IllegalAccessException when a constant refuses a read
     */
    private static Map<String, String> publicStringConstants() throws IllegalAccessException {
        Map<String, String> byName = new TreeMap<>();
        for (Field field : ObservabilityConfig.NotificationMetrics.class.getDeclaredFields()) {
            int modifiers = field.getModifiers();
            boolean constant = Modifier.isPublic(modifiers)
                    && Modifier.isStatic(modifiers)
                    && Modifier.isFinal(modifiers)
                    && field.getType() == String.class;
            if (constant) {
                byName.put(field.getName(), (String) field.get(null));
            }
        }
        return byName;
    }

    /**
     * Returns the failure kinds {@code NotificationMetrics} declares, read from the constants whose
     * name opens with {@code FAILURE_}.
     *
     * @throws IllegalAccessException when a constant refuses a read
     */
    private static Set<String> declaredFailureKinds() throws IllegalAccessException {
        Set<String> kinds = new TreeSet<>();
        publicStringConstants().forEach((name, value) -> {
            if (name.startsWith(FAILURE_CONSTANT_PREFIX)) {
                kinds.add(value);
            }
        });
        return kinds;
    }

    /** Returns the property names one prefix opens, read from every enumerable property source. */
    private static List<String> propertyNamesStartingWith(ConfigurableEnvironment environment,
            String prefix) {
        List<String> names = new ArrayList<>();
        for (PropertySource<?> source : environment.getPropertySources()) {
            if (source instanceof EnumerablePropertySource<?> enumerable) {
                for (String name : enumerable.getPropertyNames()) {
                    if (name.startsWith(prefix)) {
                        names.add(name);
                    }
                }
            }
        }
        return names;
    }

    /** Returns the endpoint names one comma-separated exposure list holds. */
    private static Set<String> endpointNamesIn(String exposureList) {
        return Arrays.stream(exposureList.split(",")).map(String::trim)
                .filter(name -> !name.isEmpty()).collect(Collectors.toCollection(TreeSet::new));
    }

    /** Returns the declared level of one logger key, or {@code ABSENT} when it resolves to null. */
    private static String declaredLevelOf(Environment environment, String loggerKey) {
        String level = environment.getProperty(loggerKey);
        return level == null ? ABSENT : level.toUpperCase(Locale.ROOT);
    }
}
