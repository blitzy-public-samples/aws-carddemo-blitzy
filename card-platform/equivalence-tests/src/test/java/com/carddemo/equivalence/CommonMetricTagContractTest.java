package com.carddemo.equivalence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.boot.micrometer.metrics.autoconfigure.MeterRegistryCustomizer;

/** Verifies every service applies the same bounded common-tag policy. */
class CommonMetricTagContractTest {

    @Test
    void everyServiceAddsItsApplicationNameUnderTheServiceTag() {
        List<TagPolicy> policies = List.of(
                new TagPolicy("account-service",
                        new com.carddemo.account.config.ObservabilityConfig()
                                .accountCommonTags("account-service")),
                new TagPolicy("authorization-service",
                        new com.carddemo.authorization.config.ObservabilityConfig()
                                .authorizationCommonTags("authorization-service")),
                new TagPolicy("card-service",
                        new com.carddemo.card.config.ObservabilityConfig()
                                .cardServiceCommonTags("card-service")),
                new TagPolicy("fraud-detection-service",
                        new com.carddemo.fraud.config.ObservabilityConfig()
                                .fraudCommonTags("fraud-detection-service")),
                new TagPolicy("ledger-posting-service",
                        new com.carddemo.ledger.config.ObservabilityConfig()
                                .ledgerCommonTags("ledger-posting-service")),
                new TagPolicy("notification-service",
                        new com.carddemo.notification.config.ObservabilityConfig()
                                .notificationCommonTags("notification-service")));

        for (TagPolicy policy : policies) {
            MeterRegistry registry = new SimpleMeterRegistry();
            policy.customizer().customize(registry);
            Counter counter = Counter.builder("contract.counter").register(registry);

            assertThat(counter.getId().getTag("service"))
                    .as(policy.applicationName())
                    .isEqualTo(policy.applicationName());
        }
    }

    @Test
    void everyServiceRefusesABlankApplicationName() {
        assertThatThrownBy(() -> new com.carddemo.account.config.ObservabilityConfig()
                .accountCommonTags(" ")).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> new com.carddemo.authorization.config.ObservabilityConfig()
                .authorizationCommonTags(" ")).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> new com.carddemo.card.config.ObservabilityConfig()
                .cardServiceCommonTags(" ")).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> new com.carddemo.fraud.config.ObservabilityConfig()
                .fraudCommonTags(" ")).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> new com.carddemo.ledger.config.ObservabilityConfig()
                .ledgerCommonTags(" ")).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> new com.carddemo.notification.config.ObservabilityConfig()
                .notificationCommonTags(" ")).isInstanceOf(IllegalStateException.class);
    }

    private record TagPolicy(
            String applicationName,
            MeterRegistryCustomizer<MeterRegistry> customizer) {
    }
}