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
 * <p>The events-consumed counter has a test of its own, because this service reads
 * {@code transaction.posted}. The counter moves once per delivery, which is what makes the two
 * posting outcomes separable: an applied posting and a duplicate the marker suppressed mean opposite
 * things and are indistinguishable on a consumed count alone.
 *
 * <p>The update service, the cycle-close service, the posted-amount listener and the outbox relay
 * that call these methods arrive with {@code domain/AccountUpdateService.java},
 * {@code domain/BillingCycleService.java}, {@code messaging/TransactionPostedConsumer.java} and
 * {@code outbox/OutboxRelay.java}.
 */
@DisplayName("ObservabilityConfig, the account-service meter set")
class ObservabilityConfigTest {

    private static final String PREFIX = "carddemo.account.";

    /** Events this service consumed, one per delivery of a posted transaction. */
    private static final String EVENTS_CONSUMED = PREFIX + "events.consumed";

    /** Wall time of one posted-transaction delivery, whether it committed or failed. */
    private static final String POSTING_LATENCY = PREFIX + "posting.latency";

    /** Posted amounts this service added to an account record. */
    private static final String POSTING_APPLIED = PREFIX + "posting.applied";

    /** Repeat deliveries the processed-event marker suppressed. */
    private static final String POSTING_DUPLICATES_SKIPPED = PREFIX + "posting.duplicates.skipped";

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

    /** Outbox rows the relay gave up on. */
    private static final String OUTBOX_ABANDONED = PREFIX + "outbox.abandoned";

    /** Terminal diagnostics the broker acknowledged. */
    private static final String DEAD_LETTERS_PUBLISHED = PREFIX + "dead.letters.published";

    /** Terminal diagnostic attempts the broker refused. */
    private static final String DEAD_LETTERS_FAILED = PREFIX + "dead.letters.failed";

    /** Transaction rollback and commit failures, separated by operation. */
    private static final String TRANSACTION_FAILURES = PREFIX + "transaction.failures";

    /** Every meter name this service reports, fourteen of them. */
    private static final Set<String> DECLARED_METER_NAMES = Set.of(EVENTS_CONSUMED, POSTING_LATENCY,
            POSTING_APPLIED, POSTING_DUPLICATES_SKIPPED, UPDATE_LATENCY, UPDATE_APPLIED,
            VALIDATION_FAILED, CYCLE_CLOSED, OUTBOX_PUBLISHED, PUBLISH_FAILED, OUTBOX_ABANDONED,
            DEAD_LETTERS_PUBLISHED, DEAD_LETTERS_FAILED, TRANSACTION_FAILURES);

    /**
     * The eleven untagged counter names.
     *
     * <p>{@link #POSTING_LATENCY} and {@link #UPDATE_LATENCY} are the two timers, and
     * {@link #TRANSACTION_FAILURES} is the one counter carrying a tag, so none of the three appears
     * here.
     */
    private static final List<String> COUNTER_NAMES = List.of(EVENTS_CONSUMED, POSTING_APPLIED,
            POSTING_DUPLICATES_SKIPPED, UPDATE_APPLIED, VALIDATION_FAILED, CYCLE_CLOSED,
            OUTBOX_PUBLISHED, PUBLISH_FAILED, OUTBOX_ABANDONED, DEAD_LETTERS_PUBLISHED,
            DEAD_LETTERS_FAILED);

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
            assertThat(registry.getMeters())
                    .as("fourteen names, and transaction.failures carries three operations")
                    .hasSize(16);
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
                                        ObservabilityConfig.CYCLE_CLOSE_OPERATION,
                                        ObservabilityConfig.POSTING_OPERATION);
                    }
                }));
    }

    @Test
    @DisplayName("the eleven untagged counters and the two timers all read zero at start-up")
    void elevenMetersAreCountersAndTwoAreTimers() {
        RUNNER.run(context -> {
            MeterRegistry registry = context.getBean(MeterRegistry.class);

            for (String name : COUNTER_NAMES) {
                assertThat(counterOf(registry, name).count())
                        .withFailMessage("counter %s did not read zero at start-up", name)
                        .isZero();
            }
            assertThat(timerOf(registry, UPDATE_LATENCY).count()).isZero();
            assertThat(timerOf(registry, POSTING_LATENCY).count()).isZero();
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
    @DisplayName("the terminal series separates a spent row, a named row and an unnamed one")
    void theTerminalSeriesSeparatesASpentRowFromItsDiagnostic() {
        RUNNER.run(context -> {
            AccountMeters meters = context.getBean(AccountMeters.class);
            MeterRegistry registry = context.getBean(MeterRegistry.class);

            meters.recordOutboxAbandoned();
            meters.recordDeadLetterFailure();
            meters.recordDeadLetterPublished();

            assertThat(counterOf(registry, OUTBOX_ABANDONED).count())
                    .withFailMessage("a row given up on must be countable apart from its attempts")
                    .isEqualTo(1.0D);
            assertThat(counterOf(registry, DEAD_LETTERS_FAILED).count()).isEqualTo(1.0D);
            assertThat(counterOf(registry, DEAD_LETTERS_PUBLISHED).count()).isEqualTo(1.0D);
            assertThat(counterOf(registry, PUBLISH_FAILED).count())
                    .withFailMessage("an abandonment or a refused diagnostic counted as a per-"
                            + "attempt publish failure, which is the double counting the review "
                            + "named")
                    .isZero();
            assertThat(counterOf(registry, OUTBOX_PUBLISHED).count())
                    .withFailMessage("a diagnostic counted as a published business event")
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

    /**
     * The events-consumed counter moves once per delivery, and only the listener moves it.
     *
     * <p>This test asserted the opposite until this service acquired a listener. It held the counter
     * at zero and refused a recording method for it, on the grounds that the account service
     * published on a state change and read no topic. That was true of the service and false of the
     * platform: {@code transaction.posted} carried the amount that belonged in this service's own
     * account record, nothing here read it, and the two failures were silent. Cumulative billing-cycle
     * exposure was never enforced, and {@code GET /accounts/{id}} answered with the balance as it
     * stood at deployment.
     *
     * <p>What survives from the original intent is the separation. Every other recording method is
     * exercised below and none of them touches the consumed counter, because a state change this
     * service publishes is not an event it consumed.
     */
    @Test
    @DisplayName("the events-consumed counter moves once per delivery and nothing else moves it")
    void theEventsConsumedCounterMovesOncePerDeliveryAndNothingElseMovesIt() {
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
            meters.recordOutboxAbandoned();
            meters.recordDeadLetterPublished();
            meters.recordDeadLetterFailure();

            assertThat(meters.eventsConsumedTotal())
                    .withFailMessage("a method other than the listener's moved the events-consumed "
                            + "counter, and a published state change is not a consumed event")
                    .isZero();
            assertThat(counterOf(registry, EVENTS_CONSUMED).count()).isZero();

            meters.recordEventConsumed();
            assertThat(meters.eventsConsumedTotal())
                    .withFailMessage("one delivery must count as one, and the reader of this series "
                            + "cannot separate a missing increment from an idle topic")
                    .isEqualTo(1.0D);
            assertThat(counterOf(registry, EVENTS_CONSUMED).count()).isEqualTo(1.0D);

            List<String> recordingMethods = Arrays.stream(AccountMeters.class.getDeclaredMethods())
                    .filter(method -> method.getName().startsWith("record"))
                    .map(Method::getName)
                    .sorted()
                    .toList();
            assertThat(recordingMethods)
                    .withFailMessage("the listener's recording method left the facade, and a "
                            + "listener whose deliveries are uncounted cannot be shown to run")
                    .contains("recordEventConsumed");
            assertThat(recordingMethods).hasSize(16);
        });
    }

    /**
     * The four posting methods move four separate meters.
     *
     * <p>An applied posting and a duplicate the marker suppressed both arrive as one delivery, so a
     * consumed count reports the same number for either. They mean opposite things: the first moved a
     * balance and the second deliberately moved nothing. Separating them is what makes a redelivery
     * storm visible as a duplicate count rather than as apparent throughput.
     */
    @Test
    @DisplayName("the four posting methods each move their own meter")
    void theFourPostingMethodsEachMoveTheirOwnMeter() {
        RUNNER.run(context -> {
            AccountMeters meters = context.getBean(AccountMeters.class);
            MeterRegistry registry = context.getBean(MeterRegistry.class);

            meters.recordPostingLatency(Duration.ofMillis(17));
            meters.recordPostingApplied();
            meters.recordPostingDuplicateSkipped();
            meters.recordPostingFailure();

            assertThat(timerOf(registry, POSTING_LATENCY).count()).isEqualTo(1L);
            assertThat(timerOf(registry, POSTING_LATENCY).totalTime(TimeUnit.MILLISECONDS))
                    .isEqualTo(17.0D);
            assertThat(counterOf(registry, POSTING_APPLIED).count()).isEqualTo(1.0D);
            assertThat(counterOf(registry, POSTING_DUPLICATES_SKIPPED).count()).isEqualTo(1.0D);
            assertThat(counterOf(registry, TRANSACTION_FAILURES,
                    ObservabilityConfig.POSTING_OPERATION).count()).isEqualTo(1.0D);
            assertThat(counterOf(registry, TRANSACTION_FAILURES,
                    ObservabilityConfig.UPDATE_OPERATION).count())
                    .withFailMessage("a posting failure counted against the update operation")
                    .isZero();
            assertThat(counterOf(registry, UPDATE_APPLIED).count())
                    .withFailMessage("a posting counted as an account update, and the two reach the "
                            + "account row by different routes")
                    .isZero();
            assertThat(timerOf(registry, UPDATE_LATENCY).count())
                    .withFailMessage("a posting delivery was timed as an update")
                    .isZero();
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

    @Test
    @DisplayName("all seven instruments this file was asked for register at start-up")
    void allSevenRequestedInstrumentsRegisterAtStartUp() {
        RUNNER.run(context -> {
            MeterRegistry registry = context.getBean(MeterRegistry.class);

            for (String counter : List.of(EVENTS_CONSUMED, PUBLISH_FAILED, VALIDATION_FAILED,
                    UPDATE_APPLIED, CYCLE_CLOSED, OUTBOX_PUBLISHED)) {
                assertThat(counterOf(registry, counter))
                        .withFailMessage("the requested counter %s is not registered", counter)
                        .isNotNull();
            }
            assertThat(timerOf(registry, UPDATE_LATENCY))
                    .withFailMessage("the requested update-path timer is not registered")
                    .isNotNull();
        });
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
