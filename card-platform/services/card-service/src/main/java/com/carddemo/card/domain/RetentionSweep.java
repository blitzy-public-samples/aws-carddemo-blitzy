package com.carddemo.card.domain;

import com.carddemo.card.config.CardProperties;
import com.carddemo.card.repository.OutboxEventRepository;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Removes published outbox rows after their configured horizon.
 *
 * <p>{@code processed_event} is deliberately absent. A duplicate-delivery claim is permanent, so
 * nothing removes one and this sweep has no horizon to apply to that table. Migration
 * {@code V9__processed_event_claims_are_permanent.sql} states the same thing in the catalogue.
 *
 * <p>The table is drained in bounded batches, one transaction per batch, under a per-table
 * ceiling. Both the bound and the repetition are needed: the bound keeps one delete from locking
 * every expired row at once, and the repetition keeps a backlog larger than one batch from
 * outliving every run.
 */
@Component
public class RetentionSweep {

    private static final Logger log = LoggerFactory.getLogger(RetentionSweep.class);

    /**
     * Rows one delete statement removes at most.
     *
     * <p>The bound is what keeps the sweep out of the relay's way. An unbounded delete over a table
     * every event passes through holds a lock on every matching row for the length of one
     * transaction, and the relay claim then waits behind the sweep.
     */
    static final int PURGE_BATCH_SIZE = 500;

    /**
     * Longest one table is swept for before the remainder is left to the next run.
     *
     * <p>The bound alone does not drain a table. A service idle over a weekend expires far more
     * than one batch, and a single batch an hour would never catch up, so each table is swept
     * repeatedly until a batch comes back short. This ceiling stops that repetition being unbounded
     * in the other direction: a backlog is cleared over several runs rather than in one that holds
     * the scheduled thread for as long as the backlog takes.
     */
    static final Duration MAX_TABLE_DURATION = Duration.ofSeconds(30);

    private final OutboxEventRepository outboxEvents;
    private final TransactionTemplate transactionTemplate;
    private final Duration publishedRetention;
    private final Duration maxTableDuration;

    @Autowired
    public RetentionSweep(OutboxEventRepository outboxEvents,
            TransactionTemplate transactionTemplate, CardProperties properties) {
        this(outboxEvents, transactionTemplate, properties, MAX_TABLE_DURATION);
    }

    /**
     * Builds a sweep whose per-table ceiling is given rather than taken from
     * {@link #MAX_TABLE_DURATION}.
     *
     * <p>A test asserting that the ceiling stops a drain would otherwise have to wait out the
     * shipped thirty seconds.
     *
     * @param outboxEvents        the published-row purge
     * @param transactionTemplate the boundary one batch commits in
     * @param properties          the configured horizon
     * @param maxTableDuration    longest one table is swept for
     * @throws NullPointerException if any argument is {@code null}
     */
    RetentionSweep(OutboxEventRepository outboxEvents,
            TransactionTemplate transactionTemplate, CardProperties properties,
            Duration maxTableDuration) {
        this.outboxEvents = Objects.requireNonNull(outboxEvents, "outboxEvents");
        this.transactionTemplate =
                Objects.requireNonNull(transactionTemplate, "transactionTemplate");
        this.maxTableDuration = Objects.requireNonNull(maxTableDuration, "maxTableDuration");
        CardProperties checked = Objects.requireNonNull(properties, "properties");
        this.publishedRetention =
                Duration.ofHours(checked.outbox().publishedRetentionHours());
    }

    /** Drains each bounded delete, one explicit transaction per batch. */
    @Scheduled(fixedDelayString = "${carddemo.retention.sweep-interval-ms:3600000}")
    public void purgeExpiredRows() {
        Instant now = Instant.now();
        drain("outbox_event",
                limit -> outboxEvents.deletePublishedBefore(now.minus(publishedRetention), limit));
    }

    /**
     * Removes expired rows from one table a batch at a time, until the table is drained or the
     * ceiling is reached.
     *
     * <p>Each batch commits on its own, so the locks one batch takes are released before the next
     * begins and the relay claim is never held behind the whole sweep. A batch that comes back
     * short of {@link #PURGE_BATCH_SIZE} is the last one there was anything to remove in, which is
     * how the loop ends on a drained table without asking for a count first.
     *
     * <p>The statement orders its rows, so successive batches move forward through the table
     * rather than re-reading what an earlier batch already considered.
     *
     * <p>A failure leaves the table for the next run. The batches that already committed stand, and
     * any table swept after it is still swept, because one unreachable table is not a reason to stop
     * removing rows from the others.
     *
     * @param table  the table being swept, named in the log line
     * @param delete removes at most the given number of expired rows and answers how many went
     */
    private void drain(String table, RowDelete delete) {
        long deadline = System.nanoTime() + maxTableDuration.toNanos();
        TransactionCallback<Integer> batch = status -> delete.run(PURGE_BATCH_SIZE);
        int total = 0;
        int batches = 0;
        try {
            while (true) {
                Integer removed = transactionTemplate.execute(batch);
                int count = removed == null ? 0 : removed;
                total = total + count;
                batches = batches + 1;
                if (count < PURGE_BATCH_SIZE) {
                    break;
                }
                if (System.nanoTime() - deadline >= 0L) {
                    log.info("Retention removed {} rows from {} in {} batches and stopped at its {}"
                                    + " ceiling. The remainder is removed on the next run.",
                            total, table, batches, maxTableDuration);
                    return;
                }
            }
        } catch (RuntimeException failure) {
            log.warn("Retention could not sweep {} after a {} having removed {} rows, and the next"
                            + " run tries again", table,
                    rootCause(failure).getClass().getSimpleName(), total);
            return;
        }
        if (total > 0) {
            log.debug("Retention removed {} expired rows from {} in {} batches", total, table,
                    batches);
        }
    }

    /** One bounded delete of expired rows. */
    private interface RowDelete {

        /**
         * Removes expired rows up to the given ceiling.
         *
         * @param limit rows this batch removes at most
         * @return how many rows were removed
         */
        int run(int limit);
    }

    private static Throwable rootCause(Throwable failure) {
        Throwable cause = failure;
        while (cause.getCause() != null && cause.getCause() != cause) {
            cause = cause.getCause();
        }
        return cause;
    }
}