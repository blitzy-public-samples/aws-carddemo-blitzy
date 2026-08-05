package com.carddemo.fraud.domain;

import com.carddemo.fraud.config.FraudProperties;
import com.carddemo.fraud.repository.OutboxEventRepository;
import com.carddemo.fraud.repository.ProcessedEventRepository;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Removes published outbox rows and processed-event markers after their configured horizons.
 *
 * <p>Every delete is bounded by {@link #MARKER_PURGE_LIMIT} rows and runs in its own transaction, so
 * a schema left idle for a long time cannot produce one statement that locks the table for the
 * length of the purge.
 */
@Component
public class RetentionSweep {

    private static final Logger log = LoggerFactory.getLogger(RetentionSweep.class);

    /** Rows one purge deletes, so a long-idle schema cannot produce one huge statement. */
    private static final int MARKER_PURGE_LIMIT = 1000;

    private final OutboxEventRepository outboxEvents;
    private final ProcessedEventRepository processedEvents;
    private final TransactionTemplate transactionTemplate;
    private final Duration publishedRetention;
    private final Duration markerRetention;

    public RetentionSweep(OutboxEventRepository outboxEvents,
            ProcessedEventRepository processedEvents, TransactionTemplate transactionTemplate,
            FraudProperties properties) {
        this.outboxEvents = Objects.requireNonNull(outboxEvents, "outboxEvents");
        this.processedEvents = Objects.requireNonNull(processedEvents, "processedEvents");
        this.transactionTemplate = Objects.requireNonNull(transactionTemplate, "transactionTemplate");
        FraudProperties checked = Objects.requireNonNull(properties, "properties");
        this.publishedRetention =
                Duration.ofHours(checked.outbox().publishedRetentionHours());
        this.markerRetention =
                Duration.ofHours(checked.processedEvent().markerRetentionHours());
    }

    /** Runs each bounded delete in its own explicit transaction. */
    @Scheduled(fixedDelayString = "${carddemo.retention.sweep-interval-ms:3600000}")
    public void purgeExpiredRows() {
        Instant now = Instant.now();
        purge("outbox_event",
                () -> outboxEvents.deletePublishedBefore(now.minus(publishedRetention),
                        MARKER_PURGE_LIMIT));
        purge("processed_event",
                () -> processedEvents.deleteMarkersProcessedBefore(now.minus(markerRetention),
                        MARKER_PURGE_LIMIT));
    }

    private void purge(String table, Supplier<Integer> deletion) {
        try {
            Integer removed = transactionTemplate.execute(status -> deletion.get());
            log.debug("Retention sweep removed {} rows from {}", removed == null ? 0 : removed,
                    table);
        } catch (RuntimeException failure) {
            log.warn("Retention sweep for {} failed after {} and will run again", table,
                    rootCause(failure).getClass().getSimpleName());
        }
    }

    private static Throwable rootCause(Throwable failure) {
        Throwable cause = failure;
        while (cause.getCause() != null && cause.getCause() != cause) {
            cause = cause.getCause();
        }
        return cause;
    }
}