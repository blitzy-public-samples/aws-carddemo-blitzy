package com.carddemo.notification.config;

import com.carddemo.notification.config.ObservabilityConfig.NotificationMetrics;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tag;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import com.carddemo.notification.config.ObservabilityConfig.NotificationMetrics;
import java.time.Duration;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.concurrent.TimeUnit;
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
 * Asserts the notification service registers six meters under one prefix, and asserts the
 * observability properties {@code src/main/resources/application.yml} declares. Every test starts a
 * bare application context holding one {@link SimpleMeterRegistry}, so {@code mvn test} runs with no
 * database, no message broker and no container.
 *
 * <p>Counting units. {@code carddemo.notification.failures} counts one failed attempt, and a record
 * the container retries contributes one to it per attempt.
 * {@code carddemo.notification.records.dead.lettered} counts one record whose attempts ran out, so
 * the two never share a unit and neither one can be read as the other.</p>
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
 * hand at {@code app/cbl/CBTRN02C.cbl:L714-L727}.</p>
 *
 * <p>Design decisions: {@code card-platform/docs/decision-log.md}.
 */
@DisplayName("ObservabilityConfig, six meters under one prefix and the observability properties of"
        + " the notification service")
class ObservabilityConfigTest {

    /** Prefix every meter of this module carries. No meter sits outside it. */
    private static final String METER_PREFIX = "carddemo.notification";

    /** The six meter names, each read verbatim from {@link ObservabilityConfig}. */
    private static final String EVENTS_CONSUMED = "carddemo.notification.events.consumed";
    private static final String PROCESSING_LATENCY = "carddemo.notification.processing.latency";
    private static final String FAILURES = "carddemo.notification.failures";
    private static final String DEAD_LETTERED = "carddemo.notification.records.dead.lettered";
    private static final String NOTIFICATIONS_RENDERED =
            "carddemo.notification.notifications.rendered";
    private static final String DUPLICATES_SKIPPED = "carddemo.notification.duplicates.skipped";

    /** The six names as one set. A seventh meter name fails the set comparison. */
    private static final Set<String> DECLARED_METER_NAMES = Set.of(EVENTS_CONSUMED,
            PROCESSING_LATENCY, FAILURES, DEAD_LETTERED, NOTIFICATIONS_RENDERED,
            DUPLICATES_SKIPPED);

    /** The five meter names a {@link Counter} carries. */
    private static final List<String> COUNTER_NAMES = List.of(EVENTS_CONSUMED, FAILURES,
            DEAD_LETTERED, NOTIFICATIONS_RENDERED, DUPLICATES_SKIPPED);

    /** The three tag keys, one per tagged meter. */
    private static final String EVENT_TYPE_TAG = "event.type";
    private static final String FAILURE_KIND_TAG = "failure.kind";
    private static final String FORMAT_TAG = "format";

    /** Tag value every tagged meter registers for input outside its own set. */
    private static final String FALLBACK_TAG_VALUE = "unknown";

    /**
     * Tag values the {@code event.type} dimension carries, three consumed event types and the
     * fallback. This service reads {@code transaction.posted} and {@code fraud.assessed}, which is
     * exactly what its broker entries grant it. It registers no {@code TransactionAuthorized}
     * series, because a series that can only ever read zero states a topology that is not true.
     */
    private static final Set<String> EVENT_TYPE_VALUES =
            Set.of("TransactionAuthorized", "TransactionPosted", "FraudFlagged", "FraudCleared",
                    "CustomerContextChanged", FALLBACK_TAG_VALUE);

    /** Tag values the {@code format} dimension carries, one per renderer and the fallback. */
    private static final Set<String> FORMAT_VALUES = Set.of("text", "html", FALLBACK_TAG_VALUE);

    /** One declared {@code event.type} value, used where one label is enough. */
    private static final String EVENT_TRANSACTION_POSTED = "TransactionPosted";

    /** A second declared {@code event.type} value, used where one label is enough. */
    private static final String EVENT_FRAUD_FLAGGED = "FraudFlagged";

    /** One declared {@code format} value, used where one label is enough. */
    private static final String FORMAT_HTML = "html";

    /** A label no dimension declares, which every lookup resolves to the fallback series. */
    private static final String UNDECLARED_LABEL = "NoSuchLabel";

    /** Elapsed time one timed recording carries, in milliseconds. */
    private static final long LATENCY_SAMPLE_MILLIS = 17L;

    /** Name prefix the failure-kind constants share. */
    private static final String FAILURE_CONSTANT_PREFIX = "FAILURE_";

    /**
     * Every public string constant {@code ObservabilityConfig.NotificationMetrics} declares, mapped
     * to the literal it holds. The comparison runs both ways, so an added, renamed or retyped
     * constant fails.
     */
    private static final Map<String, String> DECLARED_TAG_VALUE_CONSTANTS = Map.ofEntries(
            Map.entry("EVENT_TRANSACTION_AUTHORIZED", "TransactionAuthorized"),
            Map.entry("EVENT_TRANSACTION_POSTED", "TransactionPosted"),
            Map.entry("EVENT_FRAUD_FLAGGED", "FraudFlagged"),
            Map.entry("EVENT_FRAUD_CLEARED", "FraudCleared"),
            Map.entry("EVENT_CUSTOMER_CONTEXT_CHANGED", "CustomerContextChanged"),
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
    private static final Set<String> EXPOSED_ENDPOINTS =
            Set.of("health", "metrics", "prometheus");

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
     * Asserts each lookup returns the meter its arguments name, and that recording against one
     * series leaves every other series untouched.
     *
     * <p>A meter registered against the wrong name or the wrong tag reads zero forever while a
     * dashboard shows a flat line. This test moves each series by one and reads the rest back, so a
     * lookup wired to the wrong series fails here rather than at a scrape.</p>
     *
     * <p>The listeners that call these lookups arrive with
     * {@code messaging/TransactionPostedConsumer.java} and
     * {@code messaging/FraudFlaggedConsumer.java}, and the renderer counters with
     * {@code domain/NotificationService.java}. Every lookup below already returns a live meter.</p>
     */
    @Test
    @DisplayName("each lookup returns its own series and recording moves that series alone")
    void eachLookupReturnsItsOwnSeriesAndRecordingMovesThatSeriesAlone() {
        RUNNER.run(context -> {
            NotificationMetrics metrics = context.getBean(NotificationMetrics.class);
            MeterRegistry registry = context.getBean(MeterRegistry.class);

            metrics.eventsConsumed(NotificationMetrics.EVENT_FRAUD_CLEARED).increment();
            metrics.processingLatency(NotificationMetrics.EVENT_TRANSACTION_POSTED)
                    .record(Duration.ofMillis(7));
            metrics.failures(NotificationMetrics.FAILURE_RENDERING).increment();
            metrics.notificationsRendered(NotificationMetrics.FORMAT_HTML).increment();
            metrics.duplicatesSkipped().increment();

            assertThat(counterCount(registry, EVENTS_CONSUMED, EVENT_TYPE_TAG,
                    NotificationMetrics.EVENT_FRAUD_CLEARED)).isEqualTo(1.0D);
            assertThat(counterCount(registry, EVENTS_CONSUMED, EVENT_TYPE_TAG,
                    NotificationMetrics.EVENT_TRANSACTION_POSTED)).isZero();
            assertThat(timerCount(registry, PROCESSING_LATENCY, EVENT_TYPE_TAG,
                    NotificationMetrics.EVENT_TRANSACTION_POSTED)).isEqualTo(1L);
            assertThat(timerCount(registry, PROCESSING_LATENCY, EVENT_TYPE_TAG,
                    NotificationMetrics.EVENT_FRAUD_FLAGGED)).isZero();
            assertThat(counterCount(registry, FAILURES, FAILURE_KIND_TAG,
                    NotificationMetrics.FAILURE_RENDERING)).isEqualTo(1.0D);
            assertThat(counterCount(registry, FAILURES, FAILURE_KIND_TAG,
                    NotificationMetrics.FAILURE_PERSISTENCE)).isZero();
            assertThat(counterCount(registry, NOTIFICATIONS_RENDERED, FORMAT_TAG,
                    NotificationMetrics.FORMAT_HTML)).isEqualTo(1.0D);
            assertThat(counterCount(registry, NOTIFICATIONS_RENDERED, FORMAT_TAG,
                    NotificationMetrics.FORMAT_TEXT)).isZero();
            assertThat(metrics.duplicatesSkipped().count()).isEqualTo(1.0D);
        });
    }

    /**
     * Asserts a tag value outside its own set, and a null value, both resolve to the fallback series
     * rather than registering a new meter or returning null.
     *
     * <p>An event type this service does not consume must not create a series at scrape time, and a
     * null must not reach a caller as a null.</p>
     */
    @Test
    @DisplayName("an unknown or null tag value records against the fallback series")
    void anUnknownOrNullTagValueRecordsAgainstTheFallbackSeries() {
        RUNNER.run(context -> {
            NotificationMetrics metrics = context.getBean(NotificationMetrics.class);
            MeterRegistry registry = context.getBean(MeterRegistry.class);

            metrics.eventsConsumed("TransactionSettled").increment();
            metrics.eventsConsumed(null).increment();

            assertThat(counterCount(registry, EVENTS_CONSUMED, EVENT_TYPE_TAG, FALLBACK_TAG_VALUE))
                    .isEqualTo(2.0D);
            assertThat(tagValuesOf(registry, EVENTS_CONSUMED, EVENT_TYPE_TAG))
                    .containsExactlyInAnyOrderElementsOf(EVENT_TYPE_VALUES);
        });
    }

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

    @Test
    @DisplayName("the registry holds six meter names, all under one prefix")
    void theSixDeclaredMeterNamesAreTheOnlyOnesInTheRegistry() {
        RUNNER.run(context -> {
            assertThat(context).hasNotFailed();
            Set<String> names = meterNamesOf(context.getBean(MeterRegistry.class));

            assertThat(names)
                    .as("meter names this module registers")
                    .containsExactlyInAnyOrderElementsOf(DECLARED_METER_NAMES)
                    .hasSize(6)
                    .allSatisfy(name -> assertThat(name).startsWith(METER_PREFIX + "."));
        });
    }

    @Test
    void fiveMetersAreCountersAndProcessingLatencyIsATimer() {
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

    @Test
    void eachTaggedMeterCarriesExactlyOneTagKey() {
        RUNNER.run(context -> {
            MeterRegistry registry = context.getBean(MeterRegistry.class);
            Map<String, String> keyByMeter = Map.of(
                    EVENTS_CONSUMED, EVENT_TYPE_TAG,
                    PROCESSING_LATENCY, EVENT_TYPE_TAG,
                    FAILURES, FAILURE_KIND_TAG,
                    DEAD_LETTERED, FAILURE_KIND_TAG,
                    NOTIFICATIONS_RENDERED, FORMAT_TAG);

            keyByMeter.forEach((name, tagKey) -> assertThat(tagKeysOf(registry, name))
                    .as("tag keys meter %s carries", name)
                    .containsExactly(tagKey));
        });
    }

    /**
     * Asserts the {@code event.type} dimension carries one series per consumed event type, plus the
     * fallback.
     *
     * <p>This service reads four topics. {@code transaction.authorized} carries the authorization
     * decision, and reading it directly makes this the third independent consumer of that event
     * beside the ledger and the fraud detector. {@code transaction.posted} carries the new balance.
     * {@code FraudFlagged} and {@code FraudCleared} share {@code fraud.assessed}, and the envelope
     * event type separates them. {@code customer.context-changed} refreshes the cardholder
     * projection.
     *
     * <p>Every value a listener records against must appear here. A value with no series does not
     * fail to compile and does not fail a test elsewhere: the lookup silently falls back, and that
     * listener's events are attributed to a tag naming nothing. {@code CustomerContextChanged} was
     * in exactly that state, counted by its listener and registered by nothing.
     */
    @Test
    @DisplayName("event.type carries one series per consumed event type and the fallback")
    void theEventTypeTagCarriesEveryConsumedTypeAndTheFallbackSeries() {
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

    /**
     * Asserts the three metric families the requirement names are each represented. The requirement
     * reads "event consumed, processing latency, failure count", so a family that loses its last
     * meter fails here rather than leaving a dashboard panel blank.
     */
    @Test
    @DisplayName("the three metric families the requirement names are each represented")
    void theThreeMetricFamiliesTheRequirementNamesAreEachRepresented() {
        RUNNER.run(context -> {
            MeterRegistry registry = context.getBean(MeterRegistry.class);

            assertThat(metersNamed(registry, EVENTS_CONSUMED))
                    .as("the events-consumed family of the notification service")
                    .isNotEmpty();
            assertThat(metersNamed(registry, PROCESSING_LATENCY))
                    .as("the processing-latency family of the notification service")
                    .isNotEmpty();
            assertThat(metersNamed(registry, FAILURES))
                    .as("the failure-count family of the notification service")
                    .isNotEmpty();
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

    @Test
    void prometheusMetricsExportIsEnabled() {
        RUNNER.run(context -> assertThat(context.getBean(ConfigurableEnvironment.class)
                .getProperty(PROMETHEUS_EXPORT_KEY))
                .as("%s with no environment variable set", PROMETHEUS_EXPORT_KEY)
                .isEqualTo("true"));
    }

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
     * Asserts the root logger resolves to {@code INFO} and the platform logger to {@code DEBUG}
     * with no environment variable set. Both keys read a variable and fall back to that level.
     */
    @Test
    @DisplayName("the root and platform log levels resolve with no environment variable set")
    void rootAndPlatformLogLevelsResolveToTheirShippedDefaults() {
        RUNNER.run(context -> {
            Environment environment = context.getBean(ConfigurableEnvironment.class);

            assertThat(environment.getProperty(ROOT_LEVEL_KEY))
                    .as("%s with no environment variable set", ROOT_LEVEL_KEY)
                    .isEqualTo("INFO");
            assertThat(environment.getProperty(PLATFORM_LEVEL_KEY))
                    .as("%s with no environment variable set", PLATFORM_LEVEL_KEY)
                    .isEqualTo(DEBUG_LEVEL);
        });
    }

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

    /**
     * Asserts every lookup returns the meter its own name identifies. A lookup reading the wrong
     * map returns a meter of the wrong name, and the tag value it carries would still match.
     */
    @Test
    @DisplayName("each lookup returns a meter registered under its own name and tag value")
    void everyLookupReturnsTheMeterItsOwnNameIdentifies() {
        RUNNER.run(context -> {
            NotificationMetrics metrics = context.getBean(NotificationMetrics.class);

            for (String eventType : EVENT_TYPE_VALUES) {
                assertMeterIdentity(metrics.eventsConsumed(eventType), EVENTS_CONSUMED,
                        EVENT_TYPE_TAG, eventType);
                assertMeterIdentity(metrics.processingLatency(eventType), PROCESSING_LATENCY,
                        EVENT_TYPE_TAG, eventType);
            }
            for (String failureKind : declaredFailureKinds()) {
                assertMeterIdentity(metrics.failures(failureKind), FAILURES, FAILURE_KIND_TAG,
                        failureKind);
            }
            assertMeterIdentity(metrics.failures(FALLBACK_TAG_VALUE), FAILURES, FAILURE_KIND_TAG,
                    FALLBACK_TAG_VALUE);
            for (String format : FORMAT_VALUES) {
                assertMeterIdentity(metrics.notificationsRendered(format), NOTIFICATIONS_RENDERED,
                        FORMAT_TAG, format);
            }

            assertThat(metrics.duplicatesSkipped().getId().getName())
                    .as("name of the meter duplicatesSkipped returns")
                    .isEqualTo(DUPLICATES_SKIPPED);
            assertThat(metrics.duplicatesSkipped().getId().getTags())
                    .as("tags of the meter duplicatesSkipped returns")
                    .isEmpty();
        });
    }

    /**
     * Records through every lookup, one label at a time, and asserts the recording reaches exactly
     * one series. A lookup returning a shared meter would move two series, and a lookup resolving
     * the wrong tag value would move the wrong one.
     */
    @Test
    @DisplayName("recording through one lookup moves that series alone")
    void recordingThroughOneLookupMovesThatSeriesAlone() {
        RUNNER.run(context -> {
            MeterRegistry registry = context.getBean(MeterRegistry.class);
            NotificationMetrics metrics = context.getBean(NotificationMetrics.class);

            for (String eventType : EVENT_TYPE_VALUES) {
                assertOnlySeriesMoved(registry,
                        () -> metrics.eventsConsumed(eventType).increment(),
                        EVENTS_CONSUMED, EVENT_TYPE_TAG, eventType, 1.0d);
                assertOnlySeriesMoved(registry,
                        () -> metrics.processingLatency(eventType)
                                .record(Duration.ofMillis(LATENCY_SAMPLE_MILLIS)),
                        PROCESSING_LATENCY, EVENT_TYPE_TAG, eventType, 1.0d);
            }
            for (String failureKind : declaredFailureKinds()) {
                assertOnlySeriesMoved(registry, () -> metrics.failures(failureKind).increment(),
                        FAILURES, FAILURE_KIND_TAG, failureKind, 1.0d);
            }
            for (String format : FORMAT_VALUES) {
                assertOnlySeriesMoved(registry,
                        () -> metrics.notificationsRendered(format).increment(),
                        NOTIFICATIONS_RENDERED, FORMAT_TAG, format, 1.0d);
            }
            assertOnlySeriesMoved(registry, () -> metrics.duplicatesSkipped().increment(),
                    DUPLICATES_SKIPPED, null, null, 1.0d);
        });
    }

    /**
     * Asserts a label outside its own set, and a null label, both resolve to the fallback series.
     * The fallback keeps an unexpected label from registering a meter of its own, which is how a
     * tagged registry grows without bound.
     */
    @Test
    @DisplayName("an unknown label and a null label both record on the fallback series")
    void anUnknownLabelAndANullLabelBothRecordOnTheFallbackSeries() {
        RUNNER.run(context -> {
            MeterRegistry registry = context.getBean(MeterRegistry.class);
            NotificationMetrics metrics = context.getBean(NotificationMetrics.class);

            for (String label : List.of(UNDECLARED_LABEL, "")) {
                assertOnlySeriesMoved(registry, () -> metrics.eventsConsumed(label).increment(),
                        EVENTS_CONSUMED, EVENT_TYPE_TAG, FALLBACK_TAG_VALUE, 1.0d);
                assertOnlySeriesMoved(registry,
                        () -> metrics.processingLatency(label)
                                .record(Duration.ofMillis(LATENCY_SAMPLE_MILLIS)),
                        PROCESSING_LATENCY, EVENT_TYPE_TAG, FALLBACK_TAG_VALUE, 1.0d);
                assertOnlySeriesMoved(registry, () -> metrics.failures(label).increment(),
                        FAILURES, FAILURE_KIND_TAG, FALLBACK_TAG_VALUE, 1.0d);
                assertOnlySeriesMoved(registry,
                        () -> metrics.notificationsRendered(label).increment(),
                        NOTIFICATIONS_RENDERED, FORMAT_TAG, FALLBACK_TAG_VALUE, 1.0d);
            }

            assertOnlySeriesMoved(registry, () -> metrics.eventsConsumed(null).increment(),
                    EVENTS_CONSUMED, EVENT_TYPE_TAG, FALLBACK_TAG_VALUE, 1.0d);
            assertOnlySeriesMoved(registry,
                    () -> metrics.processingLatency(null)
                            .record(Duration.ofMillis(LATENCY_SAMPLE_MILLIS)),
                    PROCESSING_LATENCY, EVENT_TYPE_TAG, FALLBACK_TAG_VALUE, 1.0d);
            assertOnlySeriesMoved(registry, () -> metrics.failures(null).increment(),
                    FAILURES, FAILURE_KIND_TAG, FALLBACK_TAG_VALUE, 1.0d);
            assertOnlySeriesMoved(registry, () -> metrics.notificationsRendered(null).increment(),
                    NOTIFICATIONS_RENDERED, FORMAT_TAG, FALLBACK_TAG_VALUE, 1.0d);
        });
    }

    /**
     * Asserts no lookup returns null and no lookup registers a meter. A lookup that registered on
     * demand would grow the registry once an unexpected label arrived.
     */
    @Test
    @DisplayName("no lookup returns null and no lookup registers a meter")
    void noLookupReturnsNullAndNoLookupRegistersAMeter() {
        RUNNER.run(context -> {
            MeterRegistry registry = context.getBean(MeterRegistry.class);
            NotificationMetrics metrics = context.getBean(NotificationMetrics.class);
            int registeredBefore = registry.getMeters().size();

            List<String> labels = new ArrayList<>();
            labels.addAll(EVENT_TYPE_VALUES);
            labels.addAll(FORMAT_VALUES);
            labels.addAll(declaredFailureKinds());
            labels.add(UNDECLARED_LABEL);
            labels.add("");

            for (String label : labels) {
                assertThat(metrics.eventsConsumed(label))
                        .as("eventsConsumed answers every label").isNotNull();
                assertThat(metrics.processingLatency(label))
                        .as("processingLatency answers every label").isNotNull();
                assertThat(metrics.failures(label))
                        .as("failures answers every label").isNotNull();
                assertThat(metrics.notificationsRendered(label))
                        .as("notificationsRendered answers every label").isNotNull();
            }
            assertThat(metrics.eventsConsumed(null)).as("eventsConsumed answers null").isNotNull();
            assertThat(metrics.processingLatency(null))
                    .as("processingLatency answers null").isNotNull();
            assertThat(metrics.failures(null)).as("failures answers null").isNotNull();
            assertThat(metrics.notificationsRendered(null))
                    .as("notificationsRendered answers null").isNotNull();
            assertThat(metrics.duplicatesSkipped())
                    .as("duplicatesSkipped answers with its one counter").isNotNull();

            assertThat(registry.getMeters()).as("meters registered after every lookup")
                    .hasSize(registeredBefore);
        });
    }

    /**
     * Asserts a timer records elapsed time, not just an occurrence count. A timer whose recording
     * reached the count alone would report every event as instantaneous.
     */
    @Test
    void theLatencyTimerRecordsElapsedTimeAndNotOnlyACount() {
        RUNNER.run(context -> {
            Timer timer = context.getBean(NotificationMetrics.class)
                    .processingLatency(EVENT_TRANSACTION_POSTED);

            timer.record(Duration.ofMillis(LATENCY_SAMPLE_MILLIS));

            assertThat(timer.count()).as("recordings the latency timer holds").isEqualTo(1L);
            assertThat(timer.totalTime(TimeUnit.MILLISECONDS))
                    .as("total time the latency timer holds, in milliseconds")
                    .isEqualTo((double) LATENCY_SAMPLE_MILLIS);
        });
    }

    /**
     * Asserts one lookup returns the same meter on every call, so a caller holding the result and a
     * caller looking it up again record on one series.
     */
    @Test
    void everyLookupAnswersWithTheSameMeterEachTime() {
        RUNNER.run(context -> {
            NotificationMetrics metrics = context.getBean(NotificationMetrics.class);

            assertThat(metrics.eventsConsumed(EVENT_TRANSACTION_POSTED))
                    .as("counter eventsConsumed answers with, on two calls")
                    .isSameAs(metrics.eventsConsumed(EVENT_TRANSACTION_POSTED));
            assertThat(metrics.processingLatency(EVENT_FRAUD_FLAGGED))
                    .as("timer processingLatency answers with, on two calls")
                    .isSameAs(metrics.processingLatency(EVENT_FRAUD_FLAGGED));
            assertThat(metrics.failures(FALLBACK_TAG_VALUE))
                    .as("counter failures answers with, on two calls")
                    .isSameAs(metrics.failures(UNDECLARED_LABEL));
            assertThat(metrics.notificationsRendered(FORMAT_HTML))
                    .as("counter notificationsRendered answers with, on two calls")
                    .isSameAs(metrics.notificationsRendered(FORMAT_HTML));
            assertThat(metrics.duplicatesSkipped())
                    .as("counter duplicatesSkipped answers with, on two calls")
                    .isSameAs(metrics.duplicatesSkipped());
        });
    }

    /**
     * Asserts one meter identity: the name it registered under, and the value it carries for one
     * tag key.
     *
     * @param meter    the meter a lookup returned
     * @param name     the meter name the lookup belongs to
     * @param tagKey   the one tag key that meter carries
     * @param tagValue the value expected for that key
     */
    private static void assertMeterIdentity(Meter meter, String name, String tagKey,
            String tagValue) {
        assertThat(meter.getId().getName())
                .as("name of the meter a lookup answered for tag value %s", tagValue)
                .isEqualTo(name);
        assertThat(meter.getId().getTag(tagKey))
                .as("%s of the meter a lookup answered under %s", tagKey, name)
                .isEqualTo(tagValue);
    }

    /**
     * Runs one recording and asserts exactly one series moved, by the expected amount.
     *
     * <p>The reading of a counter is its count. The reading of a timer is its recording count.
     * One increment and one timed recording each move their series by one.</p>
     *
     * @param registry the registry holding every series
     * @param recording the recording to run
     * @param name      the meter name expected to move
     * @param tagKey    the tag key of the moving series, or {@code null} for the untagged meter
     * @param tagValue  the tag value of the moving series, or {@code null} for the untagged meter
     * @param expected  the amount the reading is expected to gain
     */
    private static void assertOnlySeriesMoved(MeterRegistry registry, Runnable recording,
            String name, String tagKey, String tagValue, double expected) {
        Map<Meter.Id, Double> before = readingsOf(registry);

        recording.run();

        Map<Meter.Id, Double> after = readingsOf(registry);
        Map<Meter.Id, Double> moved = new LinkedHashMap<>();
        after.forEach((id, reading) -> {
            double gain = reading - before.getOrDefault(id, 0.0d);
            if (gain != 0.0d) {
                moved.put(id, gain);
            }
        });

        assertThat(moved.keySet())
                .as("series that moved on one recording through %s", name)
                .hasSize(1);
        Meter.Id movedId = moved.keySet().iterator().next();
        assertThat(movedId.getName()).as("name of the series that moved").isEqualTo(name);
        if (tagKey != null) {
            assertThat(movedId.getTag(tagKey))
                    .as("%s of the series that moved under %s", tagKey, name)
                    .isEqualTo(tagValue);
        } else {
            assertThat(movedId.getTags()).as("tags of the untagged series that moved").isEmpty();
        }
        assertThat(moved.get(movedId)).as("gain of the series that moved").isEqualTo(expected);
        assertThat(after.keySet())
                .as("series present after one recording through %s", name)
                .containsExactlyInAnyOrderElementsOf(before.keySet());
    }

    /** Returns the reading of every meter in one registry, keyed by identity. */
    private static Map<Meter.Id, Double> readingsOf(MeterRegistry registry) {
        Map<Meter.Id, Double> readings = new LinkedHashMap<>();
        for (Meter meter : registry.getMeters()) {
            double reading = meter instanceof Counter counter
                    ? counter.count()
                    : ((Timer) meter).count();
            readings.put(meter.getId(), reading);
        }
        return readings;
    }

    /**
     * Reads the count of the counter carrying one tag value.
     *
     * @param registry the registry holding the meter
     * @param name     the meter name
     * @param tagKey   the tag dimension
     * @param tagValue the tag value naming the series
     * @return the count of that series
     */
    private static double counterCount(MeterRegistry registry, String name, String tagKey,
            String tagValue) {
        return Objects.requireNonNull(registry.find(name).tag(tagKey, tagValue).counter(),
                "no counter named " + name + " carries " + tagKey + "=" + tagValue).count();
    }

    /**
     * Reads the recording count of the timer carrying one tag value.
     *
     * @param registry the registry holding the meter
     * @param name     the meter name
     * @param tagKey   the tag dimension
     * @param tagValue the tag value naming the series
     * @return the number of recordings that series took
     */
    private static long timerCount(MeterRegistry registry, String name, String tagKey,
            String tagValue) {
        return Objects.requireNonNull(registry.find(name).tag(tagKey, tagValue).timer(),
                "no timer named " + name + " carries " + tagKey + "=" + tagValue).count();
    }

    /** Returns the distinct meter names one registry holds. */
    private static Set<String> meterNamesOf(MeterRegistry registry) {
        return registry.getMeters().stream().map(meter -> meter.getId().getName())
                .collect(Collectors.toCollection(TreeSet::new));
    }

    private static List<Meter> metersNamed(MeterRegistry registry, String name) {
        return registry.getMeters().stream()
                .filter(meter -> meter.getId().getName().equals(name)).toList();
    }

    private static Set<String> tagKeysOf(MeterRegistry registry, String name) {
        return metersNamed(registry, name).stream()
                .flatMap(meter -> meter.getId().getTags().stream()).map(Tag::getKey)
                .collect(Collectors.toCollection(TreeSet::new));
    }

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

    private static Set<String> endpointNamesIn(String exposureList) {
        return Arrays.stream(exposureList.split(",")).map(String::trim)
                .filter(name -> !name.isEmpty()).collect(Collectors.toCollection(TreeSet::new));
    }

    private static String declaredLevelOf(Environment environment, String loggerKey) {
        String level = environment.getProperty(loggerKey);
        return level == null ? ABSENT : level.toUpperCase(Locale.ROOT);
    }
}
