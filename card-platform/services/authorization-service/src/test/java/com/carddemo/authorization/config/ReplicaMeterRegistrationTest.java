package com.carddemo.authorization.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.carddemo.authorization.config.ObservabilityConfig.ReplicaMeters;
import com.carddemo.authorization.messaging.AccountStateChanged;
import com.carddemo.authorization.messaging.CardUpdated;

import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

import java.time.Duration;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Asserts the consume side of this service's meter set exists before the first delivery and stays
 * bounded.
 *
 * <p>Both replica listeners used to record nothing. A stream that had stopped arriving, a stream
 * arriving entirely as duplicates and a stream taking seconds per delivery all read the same on the
 * metrics endpoint — as an absent series — and the first observable consequence was a decision taken
 * against a replica no event had refreshed. Registering each series at construction is what lets a
 * reader tell "no deliveries" from "no such series".
 *
 * <p>The second property is that the tag value set is closed. A tag value taken from a delivery would
 * let one malformed producer register an unbounded number of series, so the surface answers for two
 * compile-time event types and refuses everything else.
 */
@DisplayName("Replica meter registration, the authorization service")
class ReplicaMeterRegistrationTest {

    /** The registry the surface registers against. */
    private SimpleMeterRegistry registry;

    /** The surface under test, built the way the shipped bean method builds it. */
    private ReplicaMeters meters;

    @BeforeEach
    void buildSurface() {
        registry = new SimpleMeterRegistry();
        meters = new ObservabilityConfig().replicaMeters(registry);
    }

    @Test
    @DisplayName("every consume-side series of both streams reads zero before the first delivery")
    void everySeriesReadsZeroBeforeTheFirstDelivery() {
        for (String eventType : ObservabilityConfig.REPLICA_EVENT_TYPES) {
            assertThat(counter(ObservabilityConfig.EVENTS_CONSUMED_COUNTER, eventType))
                    .as("%s consumed", eventType)
                    .isZero();
            assertThat(counter(ObservabilityConfig.DUPLICATES_SKIPPED_COUNTER, eventType))
                    .as("%s duplicates", eventType)
                    .isZero();
            assertThat(registry.get(ObservabilityConfig.REPLICA_PROCESSING_TIMER)
                    .tag(ObservabilityConfig.EVENT_TYPE_TAG, eventType).timer().count())
                    .as("%s deliveries timed", eventType)
                    .isZero();
        }
    }

    @Test
    @DisplayName("the two streams it carries are the two the listeners read")
    void theTwoStreamsItCarriesAreTheTwoTheListenersRead() {
        assertThat(ObservabilityConfig.REPLICA_EVENT_TYPES)
                .containsExactly(AccountStateChanged.EVENT_TYPE, CardUpdated.EVENT_TYPE);
    }

    @Test
    @DisplayName("each recording reaches the series of its own stream")
    void eachRecordingReachesTheSeriesOfItsOwnStream() {
        meters.recordEventConsumed(AccountStateChanged.EVENT_TYPE);
        meters.recordDuplicateSkipped(AccountStateChanged.EVENT_TYPE);
        meters.recordProcessingLatency(AccountStateChanged.EVENT_TYPE, Duration.ofMillis(7));

        assertThat(counter(ObservabilityConfig.EVENTS_CONSUMED_COUNTER,
                AccountStateChanged.EVENT_TYPE)).isEqualTo(1.0d);
        assertThat(counter(ObservabilityConfig.DUPLICATES_SKIPPED_COUNTER,
                AccountStateChanged.EVENT_TYPE)).isEqualTo(1.0d);
        assertThat(counter(ObservabilityConfig.EVENTS_CONSUMED_COUNTER, CardUpdated.EVENT_TYPE))
                .isZero();
    }

    @Test
    @DisplayName("an event type neither listener reads is refused rather than registered")
    void anUnknownEventTypeIsRefused() {
        assertThatThrownBy(() -> meters.recordEventConsumed("TransactionAuthorized"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("TransactionAuthorized");
        assertThatThrownBy(() -> meters.recordDuplicateSkipped("Whatever"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() ->
                meters.recordProcessingLatency("Whatever", Duration.ofMillis(1)))
                .isInstanceOf(IllegalArgumentException.class);

        assertThat(registry.getMeters().stream()
                .map(Meter::getId)
                .filter(id -> id.getName().equals(ObservabilityConfig.EVENTS_CONSUMED_COUNTER))
                .count())
                .as("a refused type registers no series")
                .isEqualTo(ObservabilityConfig.REPLICA_EVENT_TYPES.size());
    }

    @Test
    @DisplayName("no consume-side series carries an identifier as a tag value")
    void noSeriesCarriesAnIdentifierAsATagValue() {
        List<String> consumeSideMeters = List.of(ObservabilityConfig.EVENTS_CONSUMED_COUNTER,
                ObservabilityConfig.DUPLICATES_SKIPPED_COUNTER,
                ObservabilityConfig.REPLICA_PROCESSING_TIMER);

        for (Meter meter : registry.getMeters()) {
            if (!consumeSideMeters.contains(meter.getId().getName())) {
                continue;
            }
            assertThat(meter.getId().getTags())
                    .as("one tag per series, and its value is a compile-time event type")
                    .hasSize(1);
            assertThat(meter.getId().getTag(ObservabilityConfig.EVENT_TYPE_TAG))
                    .isIn(ObservabilityConfig.REPLICA_EVENT_TYPES);
        }
    }

    /**
     * Reads one consume-side counter back.
     *
     * @param name      the meter name
     * @param eventType the stream the series belongs to
     * @return the count the counter carries
     */
    private double counter(String name, String eventType) {
        return registry.get(name).tag(ObservabilityConfig.EVENT_TYPE_TAG, eventType)
                .counter().count();
    }
}
