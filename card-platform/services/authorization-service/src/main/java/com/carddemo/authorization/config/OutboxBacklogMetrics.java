package com.carddemo.authorization.config;

import com.carddemo.authorization.repository.OutboxEventRepository;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.function.DoubleSupplier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Publishes the size and the age of this service's outbox backlog.
 *
 * <p>Every meter in {@link ObservabilityConfig} counts or times an event that has happened. A backlog
 * is a state instead, so nothing counts it: a row waiting to be published raises no event, and the
 * counter that eventually moves is the abandonment counter, which moves only once every attempt of
 * that row is spent. Between a broker becoming unreachable and the first abandonment, no series of
 * this service changed at all, and readiness reported only whether some row had been abandoned.
 *
 * <p>These two gauges live here rather than beside the counters because they read the datastore, and
 * {@link ObservabilityConfig} takes the meter registry and nothing else. Keeping that class free of a
 * repository is what lets it be loaded on its own, which is how its own tests assert that the meters
 * it declares are the only ones it registers.
 *
 * <p>Design decisions: {@code card-platform/docs/decision-log.md}.
 */
@Configuration
public class OutboxBacklogMetrics {

    /** Rows due for a publish attempt now, being the backlog this service has not yet published. */
    public static final String METRIC_OUTBOX_DUE = "carddemo.authorization.outbox.due";

    /** Seconds the longest-waiting due row has been waiting, and zero when no row is due. */
    public static final String METRIC_OUTBOX_OLDEST_DUE_AGE =
            "carddemo.authorization.outbox.oldest.due.age";

    /**
     * Registers both gauges and returns them, so the context holds them for its lifetime.
     *
     * <p>Neither gauge carries a tag. A backlog belongs to this service as a whole, and tagging one by
     * event type or by row would make the series unbounded in the way a gauge must not be.
     *
     * @param registry     the meter registry Spring Boot auto-configuration supplies
     * @param outboxEvents the outbox this service relays from
     * @return both registered gauges
     * @throws NullPointerException when either argument is {@code null}
     */
    @Bean
    public List<Gauge> outboxBacklogGauges(MeterRegistry registry,
            OutboxEventRepository outboxEvents) {
        Objects.requireNonNull(registry, "registry");
        Objects.requireNonNull(outboxEvents, "outboxEvents");

        Gauge due = Gauge.builder(METRIC_OUTBOX_DUE,
                        () -> readOrNotANumber(
                                () -> outboxEvents.countDueBefore(Instant.now())))
                .description("Outbox rows due for an attempt now, being the backlog this service"
                        + " has not yet published")
                .register(registry);

        Gauge oldest = Gauge.builder(METRIC_OUTBOX_OLDEST_DUE_AGE,
                        () -> readOrNotANumber(() -> oldestDueAgeSeconds(outboxEvents)))
                .description("Seconds the longest-waiting due outbox row has been waiting, which"
                        + " separates a service working through a burst from a stopped relay")
                .baseUnit("seconds")
                .register(registry);

        return List.of(due, oldest);
    }

    /**
     * Returns how long the longest-waiting due row has been waiting.
     *
     * @param outboxEvents the outbox to read
     * @return the age in seconds, and zero when no row is due
     */
    private static double oldestDueAgeSeconds(OutboxEventRepository outboxEvents) {
        Instant now = Instant.now();
        return outboxEvents.findEarliestDueBefore(now)
                .map(earliest -> (double) Duration.between(earliest, now).toSeconds())
                .orElse(0.0D);
    }

    /**
     * Answers a gauge read, or not-a-number when the datastore could not answer.
     *
     * <p>A gauge that let a failure escape would take the whole scrape with it, so a datastore that is
     * away would remove every other series of this service at the same moment an operator needed them.
     *
     * @param read the read to attempt
     * @return the value read, or {@link Double#NaN} when the read failed
     */
    private static double readOrNotANumber(DoubleSupplier read) {
        try {
            return read.getAsDouble();
        } catch (RuntimeException unavailable) {
            return Double.NaN;
        }
    }
}
