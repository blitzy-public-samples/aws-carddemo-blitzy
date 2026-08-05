package com.carddemo.card.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.carddemo.card.config.ObservabilityConfig.CardLatencyTimers;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.lang.reflect.Method;
import java.time.Duration;
import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

/** Verifies every card-service meter series has a production recording path. */
class ObservabilityConfigTest {

    private static final Set<String> BACKED_METERS = Set.of(
            ObservabilityConfig.METRIC_CARD_UPDATE_APPLIED,
            ObservabilityConfig.METRIC_CARD_UPDATE_CONFLICTS,
            ObservabilityConfig.METRIC_CARD_EVENTS_PUBLISHED,
            ObservabilityConfig.METRIC_CARD_FAILURES,
            ObservabilityConfig.METRIC_CARD_XREF_DIVERGENCE,
            ObservabilityConfig.METRIC_CARD_UPDATE_LATENCY,
            ObservabilityConfig.METRIC_CARD_PUBLISH_LATENCY);

    private static final ApplicationContextRunner RUNNER = new ApplicationContextRunner()
            .withBean(MeterRegistry.class, () -> {
                SimpleMeterRegistry registry = new SimpleMeterRegistry();
                registry.config().commonTags(
                        ObservabilityConfig.TAG_SERVICE,
                        ObservabilityConfig.SERVICE_TAG_VALUE);
                return registry;
            })
            .withUserConfiguration(ObservabilityConfig.class);

    @Test
    void onlyProductionBackedMetersAreRegistered() {
        RUNNER.run(context -> {
            MeterRegistry registry = context.getBean(MeterRegistry.class);

            assertThat(registry.getMeters().stream()
                    .map(meter -> meter.getId().getName())
                    .collect(Collectors.toSet()))
                    .containsExactlyInAnyOrderElementsOf(BACKED_METERS);
        });
    }

    @Test
    void everyMeterCarriesTheServiceTagAndItsRecordingSurfaceWorks() {
        RUNNER.run(context -> {
            MeterRegistry registry = context.getBean(MeterRegistry.class);
            Counter published = context.getBean("cardEventsPublishedCounter", Counter.class);
            Counter failures =
                    context.getBean("cardInfrastructureFailureCounter", Counter.class);
            Counter updates = context.getBean("cardUpdatesAppliedCounter", Counter.class);
            Counter conflicts = context.getBean("cardUpdateConflictCounter", Counter.class);
            Counter divergence =
                    context.getBean("cardCrossReferenceDivergenceCounter", Counter.class);
            CardLatencyTimers timers = context.getBean(CardLatencyTimers.class);

            published.increment();
            failures.increment();
            updates.increment();
            conflicts.increment();
            divergence.increment();
            timers.cardUpdate().record(Duration.ofMillis(8L));
            timers.eventPublish().record(Duration.ofMillis(12L));

            assertThat(registry.getMeters()).allSatisfy(meter ->
                    assertThat(meter.getId().getTag(ObservabilityConfig.TAG_SERVICE))
                            .isEqualTo("card-service"));
            assertThat(published.count()).isEqualTo(1.0D);
            assertThat(failures.count()).isEqualTo(1.0D);
            assertThat(updates.count()).isEqualTo(1.0D);
            assertThat(conflicts.count()).isEqualTo(1.0D);
            assertThat(divergence.count()).isEqualTo(1.0D);
            assertThat(timers.cardUpdate().count()).isEqualTo(1L);
            assertThat(timers.eventPublish().count()).isEqualTo(1L);
        });
    }

    @Test
    void cardUpdateRecordingConstantsAndTimersRemainAvailableToTheirLiveCaller() {
        assertThat(Arrays.stream(ObservabilityConfig.class.getDeclaredFields())
                .map(field -> field.getName()))
                .contains(
                        "METRIC_CARD_UPDATE_APPLIED",
                        "METRIC_CARD_UPDATE_CONFLICTS",
                        "METRIC_CARD_UPDATE_LATENCY");
        assertThat(Arrays.stream(CardLatencyTimers.class.getDeclaredMethods())
                .map(Method::getName))
                .containsExactlyInAnyOrder("cardUpdate", "eventPublish");
    }
}