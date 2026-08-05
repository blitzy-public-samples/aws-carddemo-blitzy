package com.carddemo.fraud.config;

import com.carddemo.fraud.config.ObservabilityConfig.FraudMeters;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Reads the seven meters {@link ObservabilityConfig} registers, and records against each one.
 *
 * <p>This class has no COBOL ancestor. Each test builds a context holding one
 * {@link SimpleMeterRegistry} and the configuration class, so no database and no message broker has
 * to run.
 *
 * <p>Two properties matter here. Every series registers at start-up, so a scrape taken before the
 * first message lists each one at zero. And each recording method moves its own series alone: a
 * method wired to the wrong tag reads zero forever while a dashboard shows a flat line, and this
 * class fails instead.
 *
 * <p>The listener, the scoring service and the outbox relay that call these methods arrive with
 * {@code messaging/TransactionAuthorizedConsumer.java},
 * {@code domain/RiskScoringService.java} and {@code outbox/OutboxRelay.java}.
 */
@DisplayName("ObservabilityConfig, the seven meters of the fraud detection service")
class ObservabilityConfigTest {

    /** Events read from the consumed topic. */
    private static final String EVENTS_CONSUMED = "carddemo.fraud.events.consumed";

    /** Assessments published, tagged by outcome. */
    private static final String ASSESSMENTS_PRODUCED = "carddemo.fraud.assessments.produced";

    /** Wall time of one event. */
    private static final String PROCESSING_LATENCY = "carddemo.fraud.processing.latency";

    /** Processing faults, tagged by the stage that failed. */
    private static final String FAILURES = "carddemo.fraud.failures";

    /** Every meter name this service reports. */
    private static final Set<String> DECLARED_METER_NAMES = Set.of(EVENTS_CONSUMED,
            ASSESSMENTS_PRODUCED, PROCESSING_LATENCY, FAILURES);

    /** The dimension an assessment counter carries. */
    private static final String OUTCOME_TAG = "outcome";

    /** The dimension a failure counter carries. */
    private static final String STAGE_TAG = "stage";

    /** Outcome values, one per assessment result. A flag is a normal outcome, not a fault. */
    private static final Set<String> OUTCOME_VALUES = Set.of("flagged", "cleared");

    /** Stage values, one per processing fault. */
    private static final Set<String> STAGE_VALUES = Set.of("deserialize", "process", "publish");

    /** Starts the configuration class over one registry that holds nothing else. */
    private static final ApplicationContextRunner RUNNER = new ApplicationContextRunner()
            .withBean(MeterRegistry.class, SimpleMeterRegistry::new)
            .withUserConfiguration(ObservabilityConfig.class);

    @Test
    @DisplayName("the four declared names are the only meters in the registry")
    void theFourDeclaredNamesAreTheOnlyMetersInTheRegistry() {
        RUNNER.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(meterNamesOf(context.getBean(MeterRegistry.class)))
                    .containsExactlyInAnyOrderElementsOf(DECLARED_METER_NAMES);
        });
    }

    @Test
    @DisplayName("seven series register eagerly and every one reads zero")
    void sevenSeriesRegisterEagerlyAndEveryOneReadsZero() {
        RUNNER.run(context -> {
            MeterRegistry registry = context.getBean(MeterRegistry.class);

            assertThat(registry.getMeters()).hasSize(7);
            assertThat(counterOf(registry, EVENTS_CONSUMED, null, null).count()).isZero();
            for (String outcome : OUTCOME_VALUES) {
                assertThat(counterOf(registry, ASSESSMENTS_PRODUCED, OUTCOME_TAG, outcome).count())
                        .isZero();
            }
            for (String stage : STAGE_VALUES) {
                assertThat(counterOf(registry, FAILURES, STAGE_TAG, stage).count()).isZero();
            }
            assertThat(timerOf(registry).count()).isZero();
        });
    }

    @Test
    @DisplayName("the tagged meters carry exactly their declared tag values")
    void theTaggedMetersCarryExactlyTheirDeclaredTagValues() {
        RUNNER.run(context -> {
            MeterRegistry registry = context.getBean(MeterRegistry.class);

            assertThat(tagValuesOf(registry, ASSESSMENTS_PRODUCED, OUTCOME_TAG))
                    .containsExactlyInAnyOrderElementsOf(OUTCOME_VALUES);
            assertThat(tagValuesOf(registry, FAILURES, STAGE_TAG))
                    .containsExactlyInAnyOrderElementsOf(STAGE_VALUES);
            assertThat(metersNamed(registry, EVENTS_CONSUMED).get(0).getId().getTags()).isEmpty();
            assertThat(metersNamed(registry, PROCESSING_LATENCY).get(0).getId().getTags()).isEmpty();
        });
    }

    @Test
    @DisplayName("each recording method moves its own series and no other")
    void eachRecordingMethodMovesItsOwnSeriesAndNoOther() {
        RUNNER.run(context -> {
            FraudMeters meters = context.getBean(FraudMeters.class);
            MeterRegistry registry = context.getBean(MeterRegistry.class);

            meters.recordEventConsumed();
            meters.recordAssessmentFlagged();
            meters.recordProcessingLatency(Duration.ofMillis(11));
            meters.recordDeserializeFailure();

            assertThat(counterOf(registry, EVENTS_CONSUMED, null, null).count()).isEqualTo(1.0D);
            assertThat(counterOf(registry, ASSESSMENTS_PRODUCED, OUTCOME_TAG, "flagged").count())
                    .isEqualTo(1.0D);
            assertThat(counterOf(registry, ASSESSMENTS_PRODUCED, OUTCOME_TAG, "cleared").count())
                    .withFailMessage("recordAssessmentFlagged moved the cleared series")
                    .isZero();
            assertThat(timerOf(registry).count()).isEqualTo(1L);
            assertThat(timerOf(registry).totalTime(java.util.concurrent.TimeUnit.MILLISECONDS))
                    .isEqualTo(11.0D);
            assertThat(counterOf(registry, FAILURES, STAGE_TAG, "deserialize").count())
                    .isEqualTo(1.0D);
            assertThat(counterOf(registry, FAILURES, STAGE_TAG, "process").count())
                    .withFailMessage("recordDeserializeFailure moved the process series")
                    .isZero();
            assertThat(counterOf(registry, FAILURES, STAGE_TAG, "publish").count())
                    .withFailMessage("recordDeserializeFailure moved the publish series")
                    .isZero();
        });
    }

    @Test
    @DisplayName("the three remaining recording methods move their own series")
    void theThreeRemainingRecordingMethodsMoveTheirOwnSeries() {
        RUNNER.run(context -> {
            FraudMeters meters = context.getBean(FraudMeters.class);
            MeterRegistry registry = context.getBean(MeterRegistry.class);

            meters.recordAssessmentCleared();
            meters.recordProcessFailure();
            meters.recordPublishFailure();

            assertThat(counterOf(registry, ASSESSMENTS_PRODUCED, OUTCOME_TAG, "cleared").count())
                    .isEqualTo(1.0D);
            assertThat(counterOf(registry, ASSESSMENTS_PRODUCED, OUTCOME_TAG, "flagged").count())
                    .isZero();
            assertThat(counterOf(registry, FAILURES, STAGE_TAG, "process").count())
                    .isEqualTo(1.0D);
            assertThat(counterOf(registry, FAILURES, STAGE_TAG, "publish").count())
                    .isEqualTo(1.0D);
            assertThat(counterOf(registry, FAILURES, STAGE_TAG, "deserialize").count()).isZero();
            assertThat(counterOf(registry, EVENTS_CONSUMED, null, null).count())
                    .withFailMessage("a failure moved the events-consumed counter")
                    .isZero();
        });
    }

    @Test
    @DisplayName("a flag is a normal outcome and never lands on the failure meter")
    void aFlagIsANormalOutcomeAndNeverLandsOnTheFailureMeter() {
        RUNNER.run(context -> {
            FraudMeters meters = context.getBean(FraudMeters.class);
            MeterRegistry registry = context.getBean(MeterRegistry.class);

            meters.recordAssessmentFlagged();

            for (String stage : STAGE_VALUES) {
                assertThat(counterOf(registry, FAILURES, STAGE_TAG, stage).count())
                        .withFailMessage("a flagged assessment counted as a %s failure, and the "
                                + "batch program treated a reject as a normal outcome at "
                                + "app/cbl/CBTRN02C.cbl:L229-L230", stage)
                        .isZero();
            }
        });
    }

    @Test
    @DisplayName("the three metric families the requirement names are each represented")
    void theThreeMetricFamiliesTheRequirementNamesAreEachRepresented() {
        RUNNER.run(context -> {
            MeterRegistry registry = context.getBean(MeterRegistry.class);

            assertThat(registry.find(EVENTS_CONSUMED).counter())
                    .withFailMessage("the events-consumed family left the fraud detection service")
                    .isNotNull();
            assertThat(registry.find(PROCESSING_LATENCY).timer())
                    .withFailMessage("the processing-latency family left the fraud detection "
                            + "service")
                    .isNotNull();
            assertThat(metersNamed(registry, FAILURES))
                    .withFailMessage("the failure-count family left the fraud detection service")
                    .isNotEmpty();
        });
    }

    @Test
    @DisplayName("FraudMeters is the only bean of its type")
    void fraudMetersIsTheOnlyBeanOfItsType() {
        RUNNER.run(context -> assertThat(context).hasSingleBean(FraudMeters.class));
    }

    /**
     * Finds one counter by name, and by tag when a tag is supplied.
     *
     * @param registry the registry holding the meter
     * @param name     the meter name
     * @param tagKey   the tag dimension, or {@code null} for an untagged counter
     * @param tagValue the tag value, or {@code null} for an untagged counter
     * @return the counter that name and tag select
     */
    private static Counter counterOf(MeterRegistry registry, String name, String tagKey,
            String tagValue) {
        Counter counter = tagKey == null
                ? registry.find(name).counter()
                : registry.find(name).tag(tagKey, tagValue).counter();
        return Objects.requireNonNull(counter,
                "no counter named " + name + " carries " + tagKey + "=" + tagValue);
    }

    /**
     * Finds the one timer this service registers.
     *
     * @param registry the registry holding the meter
     * @return the processing-latency timer
     */
    private static Timer timerOf(MeterRegistry registry) {
        return Objects.requireNonNull(registry.find(PROCESSING_LATENCY).timer(),
                "no timer named " + PROCESSING_LATENCY);
    }

    /**
     * Reads every distinct meter name in the registry.
     *
     * @param registry the registry to read
     * @return the meter names, deduplicated
     */
    private static Set<String> meterNamesOf(MeterRegistry registry) {
        return registry.getMeters().stream()
                .map(meter -> meter.getId().getName())
                .collect(Collectors.toCollection(TreeSet::new));
    }

    /**
     * Reads every meter registered under one name.
     *
     * @param registry the registry to read
     * @param name     the meter name
     * @return the meters carrying that name, in registry order
     */
    private static List<Meter> metersNamed(MeterRegistry registry, String name) {
        return registry.getMeters().stream()
                .filter(meter -> name.equals(meter.getId().getName()))
                .toList();
    }

    /**
     * Reads the values one tag dimension carries across every meter of one name.
     *
     * @param registry the registry to read
     * @param name     the meter name
     * @param tagKey   the tag dimension
     * @return the tag values, deduplicated
     */
    private static Set<String> tagValuesOf(MeterRegistry registry, String name, String tagKey) {
        return metersNamed(registry, name).stream()
                .map(meter -> meter.getId().getTag(tagKey))
                .filter(Objects::nonNull)
                .collect(Collectors.toCollection(TreeSet::new));
    }
}
