package com.carddemo.account.config;

import com.carddemo.account.config.ObservabilityConfig.AccountMeters;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

import java.lang.reflect.Method;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Reads the account meter series {@link ObservabilityConfig} registers, and records against each.
 *
 * <p>This class has no COBOL ancestor. Each test builds a context holding one
 * {@link SimpleMeterRegistry} and the configuration class, so no database and no message broker has
 * to run.
 *
 * <p>Three properties matter here. Every name opens with {@code carddemo.account.}, the namespace
 * the fraud detection and notification services use. Every meter registers at start-up, so a scrape
 * taken before the first request lists each one at zero. And each recording method moves its own
 * meter alone.
 *
 * <p>The events-consumed counter has a test of its own. It stays at zero, and no method on the
 * facade increments it, because the account service publishes on a state change and reads no topic.
 *
 * <p>The update service, the cycle-close service and the outbox relay that call these methods arrive
 * with {@code domain/AccountUpdateService.java}, {@code domain/BillingCycleService.java} and
 * {@code outbox/OutboxRelay.java}.
 */
@DisplayName("ObservabilityConfig, the account-service meter set")
class ObservabilityConfigTest {

    /** Prefix every meter name carries. */
    private static final String PREFIX = "carddemo.account.";

    /** Events this service consumed, which stays at zero. */
    private static final String EVENTS_CONSUMED = PREFIX + "events.consumed";

    /** Wall time of one account update. */
    private static final String UPDATE_LATENCY = PREFIX + "update.latency";

    /** Account updates that committed. */
    private static final String UPDATE_APPLIED = PREFIX + "update.applied";

    /** Submitted fields rejected by validation. */
    private static final String VALIDATION_FAILED = PREFIX + "validation.failed";

    /** Billing cycle closes that committed. */
    private static final String CYCLE_CLOSED = PREFIX + "cycle.closed";

    /** Outbox rows the broker accepted. */
    private static final String OUTBOX_PUBLISHED = PREFIX + "outbox.published";

    /** Outbox publish attempts that failed. */
    private static final String PUBLISH_FAILED = PREFIX + "publish.failed";

    /** Transaction rollback and commit failures, separated by operation. */
    private static final String TRANSACTION_FAILURES = PREFIX + "transaction.failures";

    /** Every meter name this service reports. */
    private static final Set<String> DECLARED_METER_NAMES = Set.of(EVENTS_CONSUMED, UPDATE_LATENCY,
            UPDATE_APPLIED, VALIDATION_FAILED, CYCLE_CLOSED, OUTBOX_PUBLISHED, PUBLISH_FAILED,
            TRANSACTION_FAILURES);

    /** The six counter names. {@link #UPDATE_LATENCY} is the one timer. */
    private static final List<String> COUNTER_NAMES = List.of(EVENTS_CONSUMED, UPDATE_APPLIED,
            VALIDATION_FAILED, CYCLE_CLOSED, OUTBOX_PUBLISHED, PUBLISH_FAILED);

    /** Starts the configuration class over one registry that holds nothing else. */
    private static final ApplicationContextRunner RUNNER = new ApplicationContextRunner()
            .withBean(MeterRegistry.class, () -> {
                SimpleMeterRegistry registry = new SimpleMeterRegistry();
                registry.config().commonTags(ObservabilityConfig.SERVICE_TAG, "account-service");
                return registry;
            })
            .withUserConfiguration(ObservabilityConfig.class);

    @Test
    @DisplayName("the declared names are the only meters, and each opens with the prefix")
    void theDeclaredNamesAreTheOnlyMeters() {
        RUNNER.run(context -> {
            assertThat(context).hasNotFailed();
            MeterRegistry registry = context.getBean(MeterRegistry.class);

            assertThat(meterNamesOf(registry))
                    .containsExactlyInAnyOrderElementsOf(DECLARED_METER_NAMES);
            assertThat(meterNamesOf(registry)).allSatisfy(name -> assertThat(name)
                    .withFailMessage("meter %s left the carddemo.account namespace", name)
                    .startsWith(PREFIX));
            assertThat(registry.getMeters()).hasSize(9);
        });
    }

    @Test
    @DisplayName("every meter carries the service tag and failure operations stay bounded")
    void everyMeterCarriesTheServiceTagAndFailureOperationsStayBounded() {
        RUNNER.run(context -> assertThat(context.getBean(MeterRegistry.class).getMeters())
                .allSatisfy(meter -> {
                    assertThat(meter.getId().getTag(ObservabilityConfig.SERVICE_TAG))
                            .isEqualTo("account-service");
                    if (meter.getId().getName().equals(TRANSACTION_FAILURES)) {
                        assertThat(meter.getId().getTag(ObservabilityConfig.OPERATION_TAG))
                                .isIn(ObservabilityConfig.UPDATE_OPERATION,
                                        ObservabilityConfig.CYCLE_CLOSE_OPERATION);
                    }
                }));
    }

    @Test
    @DisplayName("six meters are counters, the update meter is a timer, and all seven read zero")
    void sixMetersAreCountersAndTheUpdateMeterIsATimer() {
        RUNNER.run(context -> {
            MeterRegistry registry = context.getBean(MeterRegistry.class);

            for (String name : COUNTER_NAMES) {
                assertThat(counterOf(registry, name).count())
                        .withFailMessage("counter %s did not read zero at start-up", name)
                        .isZero();
            }
            assertThat(timerOf(registry, UPDATE_LATENCY).count()).isZero();
        });
    }

    @Test
    @DisplayName("each recording method moves its own meter and no other")
    void eachRecordingMethodMovesItsOwnMeterAndNoOther() {
        RUNNER.run(context -> {
            AccountMeters meters = context.getBean(AccountMeters.class);
            MeterRegistry registry = context.getBean(MeterRegistry.class);

            meters.recordUpdateLatency(Duration.ofMillis(23));
            meters.recordUpdateApplied();
            meters.recordCycleClosed();
            meters.recordOutboxPublished(4L);

            assertThat(timerOf(registry, UPDATE_LATENCY).count()).isEqualTo(1L);
            assertThat(timerOf(registry, UPDATE_LATENCY).totalTime(TimeUnit.MILLISECONDS))
                    .isEqualTo(23.0D);
            assertThat(counterOf(registry, UPDATE_APPLIED).count()).isEqualTo(1.0D);
            assertThat(counterOf(registry, CYCLE_CLOSED).count()).isEqualTo(1.0D);
            assertThat(counterOf(registry, OUTBOX_PUBLISHED).count())
                    .withFailMessage("recordOutboxPublished counted sweeps rather than rows")
                    .isEqualTo(4.0D);
            assertThat(counterOf(registry, VALIDATION_FAILED).count())
                    .withFailMessage("a committed update counted as a validation failure")
                    .isZero();
            assertThat(counterOf(registry, PUBLISH_FAILED).count())
                    .withFailMessage("a published sweep counted as a publish failure")
                    .isZero();
        });
    }

    @Test
    @DisplayName("the two failure methods move their own meters")
    void theTwoFailureMethodsMoveTheirOwnMeters() {
        RUNNER.run(context -> {
            AccountMeters meters = context.getBean(AccountMeters.class);
            MeterRegistry registry = context.getBean(MeterRegistry.class);

            meters.recordValidationFailure();
            meters.recordPublishFailure();
            meters.recordUpdateFailure();
            meters.recordCycleCloseFailure();

            assertThat(counterOf(registry, VALIDATION_FAILED).count()).isEqualTo(1.0D);
            assertThat(counterOf(registry, PUBLISH_FAILED).count()).isEqualTo(1.0D);
            assertThat(counterOf(registry, TRANSACTION_FAILURES,
                    ObservabilityConfig.UPDATE_OPERATION).count()).isEqualTo(1.0D);
            assertThat(counterOf(registry, TRANSACTION_FAILURES,
                    ObservabilityConfig.CYCLE_CLOSE_OPERATION).count()).isEqualTo(1.0D);
            assertThat(counterOf(registry, UPDATE_APPLIED).count())
                    .withFailMessage("a failure counted as a committed update")
                    .isZero();
            assertThat(counterOf(registry, CYCLE_CLOSED).count()).isZero();
        });
    }

    @Test
    @DisplayName("the events-consumed counter stays at zero and the facade cannot increment it")
    void theEventsConsumedCounterStaysAtZeroAndTheFacadeCannotIncrementIt() {
        RUNNER.run(context -> {
            AccountMeters meters = context.getBean(AccountMeters.class);
            MeterRegistry registry = context.getBean(MeterRegistry.class);

            meters.recordUpdateApplied();
            meters.recordCycleClosed();
            meters.recordOutboxPublished(9L);
            meters.recordValidationFailure();
            meters.recordPublishFailure();
            meters.recordUpdateFailure();
            meters.recordCycleCloseFailure();
            meters.recordUpdateLatency(Duration.ofMillis(1));

            assertThat(meters.eventsConsumedTotal())
                    .withFailMessage("the events-consumed counter moved, and this service reads no "
                            + "topic")
                    .isZero();
            assertThat(counterOf(registry, EVENTS_CONSUMED).count()).isZero();

            List<String> recordingMethods = Arrays.stream(AccountMeters.class.getDeclaredMethods())
                    .filter(method -> method.getName().startsWith("record"))
                    .map(Method::getName)
                    .sorted()
                    .toList();
            assertThat(recordingMethods)
                    .withFailMessage("a recording method for the events-consumed counter appeared, "
                            + "and this service consumes no event")
                    .doesNotContain("recordEventConsumed", "recordEventsConsumed");
            assertThat(recordingMethods).hasSize(8);
        });
    }

    @Test
    @DisplayName("the three metric families the requirement names are each represented")
    void theThreeMetricFamiliesTheRequirementNamesAreEachRepresented() {
        RUNNER.run(context -> {
            MeterRegistry registry = context.getBean(MeterRegistry.class);

            assertThat(registry.find(EVENTS_CONSUMED).counter())
                    .withFailMessage("the events-consumed family left the account service")
                    .isNotNull();
            assertThat(registry.find(UPDATE_LATENCY).timer())
                    .withFailMessage("the processing-latency family left the account service")
                    .isNotNull();
            assertThat(List.of(counterOf(registry, VALIDATION_FAILED),
                    counterOf(registry, PUBLISH_FAILED),
                    counterOf(registry, TRANSACTION_FAILURES,
                            ObservabilityConfig.UPDATE_OPERATION)))
                    .withFailMessage("the failure-count family left the account service")
                    .hasSize(3);
        });
    }

    @Test
    @DisplayName("AccountMeters is the only bean of its type")
    void accountMetersIsTheOnlyBeanOfItsType() {
        RUNNER.run(context -> assertThat(context).hasSingleBean(AccountMeters.class));
    }

    /**
     * Finds one untagged counter by name.
     *
     * @param registry the registry holding the meter
     * @param name     the meter name
     * @return the counter carrying that name
     */
    private static Counter counterOf(MeterRegistry registry, String name) {
        return Objects.requireNonNull(registry.find(name).counter(),
                "no counter named " + name);
    }

    private static Counter counterOf(MeterRegistry registry, String name, String operation) {
        return Objects.requireNonNull(registry.find(name)
                        .tag(ObservabilityConfig.OPERATION_TAG, operation)
                        .counter(),
                "no counter named " + name + " for operation " + operation);
    }

    /**
     * Finds one timer by name.
     *
     * @param registry the registry holding the meter
     * @param name     the meter name
     * @return the timer carrying that name
     */
    private static Timer timerOf(MeterRegistry registry, String name) {
        return Objects.requireNonNull(registry.find(name).timer(), "no timer named " + name);
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
}
