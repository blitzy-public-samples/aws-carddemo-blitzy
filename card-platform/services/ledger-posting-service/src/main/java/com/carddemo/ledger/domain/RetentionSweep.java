package com.carddemo.ledger.domain;

import com.carddemo.ledger.config.LedgerProperties;
import com.carddemo.ledger.repository.OutboxEventRepository;
import com.carddemo.ledger.repository.ProcessedEventRepository;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/** Removes published outbox rows and processed-event markers after their configured horizons. */
@Component
public class RetentionSweep {

    private static final Logger log = LoggerFactory.getLogger(RetentionSweep.class);

    private final OutboxEventRepository outboxEvents;
    private final ProcessedEventRepository processedEvents;
    private final TransactionTemplate transactionTemplate;
    private final Duration publishedRetention;
    private final Duration markerRetention;

    public RetentionSweep(OutboxEventRepository outboxEvents,
            ProcessedEventRepository processedEvents, TransactionTemplate transactionTemplate,
            LedgerProperties properties) {
        this.outboxEvents = Objects.requireNonNull(outboxEvents, "outboxEvents");
        this.processedEvents = Objects.requireNonNull(processedEvents, "processedEvents");
        this.transactionTemplate = Objects.requireNonNull(transactionTemplate, "transactionTemplate");
        LedgerProperties checked = Objects.requireNonNull(properties, "properties");
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
                () -> outboxEvents.deletePublishedBefore(now.minus(publishedRetention)));
        purge("processed_event",
                () -> processedEvents.deleteMarkersProcessedBefore(now.minus(markerRetention)));
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