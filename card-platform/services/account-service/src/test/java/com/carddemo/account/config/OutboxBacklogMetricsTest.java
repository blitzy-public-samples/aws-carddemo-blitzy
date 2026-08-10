package com.carddemo.account.config;

import com.carddemo.account.repository.OutboxEventRepository;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Reads the two backlog gauges {@link OutboxBacklogMetrics} registers for the account service.
 *
 * <p>This class has no COBOL ancestor. The CardDemo source carries no outbox, and its one
 * asynchronous handoff is the Customer Information Control System command
 * {@code EXEC CICS WRITEQ TD} on {@code QUEUE ('JOBS')} at {@code app/cbl/CORPT00C.cbl:L517-L518}.
 * That handoff reported nothing about how much work was waiting.
 *
 * <p>Four properties matter here. Both gauges register at start-up, so a scrape taken before the
 * first event lists each one. Each reads its own query, so a gauge wired to the wrong one reads a
 * plausible number forever. The age reads zero when no row is due, which distinguishes an empty
 * outbox from a stopped relay. And a datastore that cannot answer yields not-a-number rather than an
 * exception, because an exception from one gauge removes every other series in the same scrape.
 */
@DisplayName("OutboxBacklogMetrics, the outbox backlog gauges of the account service")
class OutboxBacklogMetricsTest {

    /** Rows due for an attempt now. */
    private static final String DUE = "carddemo.account.outbox.due";

    /** Seconds the longest-waiting due row has waited. */
    private static final String OLDEST = "carddemo.account.outbox.oldest.due.age";

    /** The registry each test reads from. */
    private MeterRegistry registry;

    /** The outbox each gauge reads. */
    private OutboxEventRepository outboxEvents;

    /** The class under test. */
    private OutboxBacklogMetrics metrics;

    /** Registers both gauges against a fresh registry and a fresh mock outbox. */
    @BeforeEach
    void registerTheGauges() {
        registry = new SimpleMeterRegistry();
        outboxEvents = mock(OutboxEventRepository.class);
        metrics = new OutboxBacklogMetrics();
        when(outboxEvents.countDueBefore(any())).thenReturn(0L);
        when(outboxEvents.findEarliestDueBefore(any())).thenReturn(Optional.empty());
    }

    @Test
    @DisplayName("Both gauges register at start-up under the two documented names")
    void bothGaugesRegisterAtStartUp() {
        List<Gauge> registered = metrics.outboxBacklogGauges(registry, outboxEvents);

        assertThat(registered).as("the gauges the bean returns").hasSize(2);
        assertThat(registry.getMeters()).extracting(meter -> meter.getId().getName())
                .as("the names in the registry").containsExactlyInAnyOrder(DUE, OLDEST);
        assertThat(registry.getMeters()).allSatisfy(meter -> assertThat(meter.getId().getType())
                .as("the instrument type").isEqualTo(Meter.Type.GAUGE));
    }

    @Test
    @DisplayName("Neither gauge carries a tag, because a backlog belongs to the whole service")
    void neitherGaugeCarriesATag() {
        metrics.outboxBacklogGauges(registry, outboxEvents);

        assertThat(registry.getMeters()).allSatisfy(meter -> assertThat(meter.getId().getTags())
                .as("the tags of %s", meter.getId().getName()).isEmpty());
    }

    @Test
    @DisplayName("The age gauge is published in seconds and both carry a description")
    void theAgeGaugeIsPublishedInSeconds() {
        metrics.outboxBacklogGauges(registry, outboxEvents);

        assertThat(registry.find(OLDEST).gauge()).isNotNull()
                .satisfies(gauge -> assertThat(gauge.getId().getBaseUnit())
                        .as("the base unit of the age gauge").isEqualTo("seconds"));
        assertThat(registry.getMeters()).allSatisfy(meter -> assertThat(
                meter.getId().getDescription())
                .as("the description of %s", meter.getId().getName()).isNotBlank());
    }

    @Test
    @DisplayName("The due gauge reads the backlog the outbox reports")
    void theDueGaugeReadsTheBacklog() {
        when(outboxEvents.countDueBefore(any())).thenReturn(7L);
        metrics.outboxBacklogGauges(registry, outboxEvents);

        assertThat(registry.find(DUE).gauge().value())
                .as("the rows the outbox reports as due").isEqualTo(7.0D);
    }

    @Test
    @DisplayName("The age gauge reads how long the longest-waiting row has waited")
    void theAgeGaugeReadsTheWait() {
        when(outboxEvents.findEarliestDueBefore(any()))
                .thenReturn(Optional.of(Instant.now().minusSeconds(90)));
        metrics.outboxBacklogGauges(registry, outboxEvents);

        assertThat(registry.find(OLDEST).gauge().value())
                .as("the seconds the longest-waiting due row has waited")
                .isGreaterThanOrEqualTo(89.0D);
    }

    @Test
    @DisplayName("The age gauge reads zero when no row is due")
    void theAgeGaugeReadsZeroWhenNoRowIsDue() {
        metrics.outboxBacklogGauges(registry, outboxEvents);

        assertThat(registry.find(OLDEST).gauge().value())
                .as("an empty outbox reads zero rather than a stale age").isZero();
    }

    @Test
    @DisplayName("A datastore that cannot answer yields not-a-number rather than a failed scrape")
    void aDatastoreThatCannotAnswerYieldsNotANumber() {
        when(outboxEvents.countDueBefore(any()))
                .thenThrow(new IllegalStateException("datastore unreachable"));
        when(outboxEvents.findEarliestDueBefore(any()))
                .thenThrow(new IllegalStateException("datastore unreachable"));
        metrics.outboxBacklogGauges(registry, outboxEvents);

        assertThat(registry.find(DUE).gauge().value())
                .as("the due reading of an outbox that cannot answer").isNaN();
        assertThat(registry.find(OLDEST).gauge().value())
                .as("the age reading of an outbox that cannot answer").isNaN();
    }

    @Test
    @DisplayName("Each gauge is read again on every scrape rather than held from the first")
    void eachGaugeIsReadAgainOnEveryScrape() {
        when(outboxEvents.countDueBefore(any())).thenReturn(1L, 4L);
        metrics.outboxBacklogGauges(registry, outboxEvents);

        double first = registry.find(DUE).gauge().value();
        double second = registry.find(DUE).gauge().value();

        assertThat(first).as("the first reading").isEqualTo(1.0D);
        assertThat(second).as("the reading after the backlog grew").isEqualTo(4.0D);
    }

    @Test
    @DisplayName("Neither argument may be absent")
    void neitherArgumentMayBeAbsent() {
        assertThatThrownBy(() -> metrics.outboxBacklogGauges(null, outboxEvents))
                .isInstanceOf(NullPointerException.class).hasMessage("registry");
        assertThatThrownBy(() -> metrics.outboxBacklogGauges(registry, null))
                .isInstanceOf(NullPointerException.class).hasMessage("outboxEvents");
    }
}
