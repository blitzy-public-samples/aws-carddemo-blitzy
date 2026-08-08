package com.carddemo.notification.domain;

import com.carddemo.cobol.CobolDecimal;
import com.carddemo.cobol.PicClause;
import com.carddemo.notification.config.NotificationProperties;
import com.carddemo.notification.repository.NotificationLogRepository;
import com.carddemo.notification.repository.ProcessedEventRepository;
import com.carddemo.notification.repository.StatementTransactionRepository;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Objects;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Deletes the rows of this service that have passed their retention horizon.
 *
 * <p>ADDITIVE IN FULL. No Common Business Oriented Language (COBOL) program under {@code app/cbl/}
 * expires a record, because a Virtual Storage Access Method dataset was reorganised by an operator
 * and a Generation Data Group aged its own generations off. There is no ancestor to reproduce.
 *
 * <p>This class is here because three tables of this service grow by one row per consumed event and
 * nothing else removes a row. The horizons were declared in
 * {@code src/main/resources/application.yml} and in the {@code COMMENT ON TABLE} statements of
 * {@code src/main/resources/db/migration/V1__schema.sql} before anything applied them, which made
 * every one of them a statement about intent rather than about behaviour. A horizon nothing enforces
 * is worse than no horizon at all: a reader takes the table for bounded and it is not.
 *
 * <p>Each table expires on its own clock and for its own reason. A duplicate-delivery marker matters
 * only while a redelivery of its event is still possible, so its horizon has to outlast the topic
 * retention the broker applies. A read-model row backs the history endpoint, so it outlives an alert
 * by a wide margin, and it expires on the producer's processing timestamp rather than on this
 * service's clock, because that stamp is the one moment the platform agrees on for the transaction.
 * A rendered-alert row records an alert this service produced and did not send, and is the
 * shortest-lived of the three.
 *
 * <p>Each table is swept by a bounded delete repeated until the table is clear, the table's own
 * wall-clock ceiling is reached, or the delete fails. Both halves are needed. The bound keeps one
 * statement from locking a table for as long as its backlog takes to remove, and the repetition keeps
 * that bound from making the backlog permanent, which is what a single bounded pass on an interval
 * does as soon as rows arrive faster than one batch a pass.
 *
 * <p>Each batch runs in a transaction of its own, so a table that fails part-way keeps the rows its
 * earlier batches removed and leaves the other two tables to be swept. A failure is logged and the
 * schedule carries on: a sweep that cannot run is an operational condition and never a reason to fail
 * a delivery.
 *
 * <p>Decisions: {@code card-platform/docs/decision-log.md}.
 */
@Component
public class RetentionSweep {

    /** Writes the diagnostic lines this class emits, none carrying a row value. */
    private static final Logger LOG = LoggerFactory.getLogger(RetentionSweep.class);

    /**
     * Rows one bounded delete removes at most.
     *
     * <p>One number for all three tables, and the same number the sibling services use, so one
     * figure describes the platform rather than six. It replaced two separate thousand-row limits:
     * the ceiling matters less than the drain below it, and a smaller batch releases its locks
     * sooner.
     */
    static final int PURGE_BATCH_SIZE = 500;

    /**
     * Wall-clock ceiling on the drain of one table.
     *
     * <p>Finite by design. Without it a table with an unbounded backlog would hold the scheduled
     * thread for as long as the backlog took to clear, and the tables after it would never be swept
     * at all. This service sweeps three in sequence, so starving the ones behind is the real risk.
     */
    static final Duration MAX_TABLE_DURATION = Duration.ofSeconds(30);

    /** Store of the card-keyed read model. */
    private final StatementTransactionRepository statementTransactions;

    /** Store of the rendered-alert rows. */
    private final NotificationLogRepository notificationLog;

    /** Store of the duplicate-delivery markers. */
    private final ProcessedEventRepository processedEvents;

    /** The bound horizons, from {@code carddemo.history} and {@code carddemo.processed-event}. */
    private final NotificationProperties properties;

    /** Opens one transaction per delete. See {@link #sweepExpiredRows()} for why not an annotation. */
    private final TransactionTemplate transactionTemplate;

    /** Supplies the moment each horizon is measured back from, in Coordinated Universal Time. */
    private final Clock clock = Clock.systemUTC();

    /**
     * The drain ceiling in force, {@link #MAX_TABLE_DURATION} in production.
     *
     * <p>A field rather than the constant read directly, so a test can prove the ceiling stops an
     * endless backlog without spending thirty seconds of the build doing it.
     */
    private final Duration maxTableDuration;

    /**
     * Takes the three stores, the bound horizons and the transaction boundary.
     *
     * @param statementTransactions store of the card-keyed read model
     * @param notificationLog       store of the rendered-alert rows
     * @param processedEvents       store of the duplicate-delivery markers
     * @param properties            the bound {@code carddemo} block
     * @param transactionTemplate   opens one transaction per delete
     * @throws NullPointerException if any argument is {@code null}
     */
    // The annotation is required, not decoration. Two constructors leave the container with two
    // candidates and no way to choose, and it reports "No default constructor found" on every
    // context load rather than naming the ambiguity.
    @Autowired
    public RetentionSweep(StatementTransactionRepository statementTransactions,
            NotificationLogRepository notificationLog, ProcessedEventRepository processedEvents,
            NotificationProperties properties, TransactionTemplate transactionTemplate) {
        this(statementTransactions, notificationLog, processedEvents, properties,
                transactionTemplate, MAX_TABLE_DURATION);
    }

    /**
     * Takes the same collaborators and an explicit drain ceiling.
     *
     * <p>Package-private, for the test that proves an endless backlog stops at the ceiling. The
     * production ceiling is thirty seconds and a test that waited for it would spend that long
     * looping.
     *
     * @param statementTransactions store of the card-keyed read model
     * @param notificationLog       store of the rendered-alert rows
     * @param processedEvents       store of the duplicate-delivery markers
     * @param properties            the bound {@code carddemo} block
     * @param transactionTemplate   opens one transaction per delete
     * @param maxTableDuration      the drain ceiling for one table
     * @throws NullPointerException if any argument is {@code null}
     */
    RetentionSweep(StatementTransactionRepository statementTransactions,
            NotificationLogRepository notificationLog, ProcessedEventRepository processedEvents,
            NotificationProperties properties, TransactionTemplate transactionTemplate,
            Duration maxTableDuration) {
        this.maxTableDuration =
                Objects.requireNonNull(maxTableDuration, "maxTableDuration is required");
        this.statementTransactions =
                Objects.requireNonNull(statementTransactions, "statementTransactions is required");
        this.notificationLog =
                Objects.requireNonNull(notificationLog, "notificationLog is required");
        this.processedEvents =
                Objects.requireNonNull(processedEvents, "processedEvents is required");
        this.properties = Objects.requireNonNull(properties, "properties is required");
        this.transactionTemplate =
                Objects.requireNonNull(transactionTemplate, "transactionTemplate is required");
    }

    /**
     * Runs one pass over the three tables, oldest concern first.
     *
     * <p>The interval comes from {@code carddemo.history.sweep-interval-ms}. It is far shorter than
     * any of the three horizons, which is deliberate: the sweep is cheap, each delete is bounded by
     * an index, and running often keeps one pass from having a day of rows to remove.
     *
     * <p>No failure leaves this method. Each of the three deletes reports its own outcome, so a
     * permission problem on one table does not hide the other two.
     *
     * <p>Each delete opens its transaction through {@link TransactionTemplate} rather than through
     * {@code @Transactional} on the three methods below. The reason is mechanical: a scheduled method
     * reaching its neighbours calls them on {@code this}, which does not pass through the proxy that
     * would start a transaction, and a modifying query outside a transaction fails. That failure
     * arrives as a {@link DataAccessException}, which {@link #drain} logs and swallows, so an
     * annotation here would leave every horizon unenforced while reporting nothing louder than a
     * warning. Opening the transaction explicitly cannot be bypassed that way.
     */
    @Scheduled(fixedDelayString = "${carddemo.history.sweep-interval-ms}")
    public void sweepExpiredRows() {
        sweepMarkers();
        sweepReadModelRows();
        sweepRenderedAlertRows();
    }

    /**
     * Deletes markers past {@code carddemo.processed-event.marker-retention-hours}.
     */
    public void sweepMarkers() {
        Instant horizon = clock.instant()
                .minus(Duration.ofHours(properties.processedEvent().markerRetentionHours()));
        drain("processed_event",
                limit -> processedEvents.deleteMarkersProcessedBefore(horizon, limit));
    }

    /**
     * Deletes read-model rows past {@code carddemo.history.statement-retention-days}.
     *
     * <p>The horizon is rendered into the 26-character form the column holds, through the same
     * formatter that writes the column, so the text this delete compares against is built the way
     * every stored value was.
     */
    public void sweepReadModelRows() {
        LocalDateTime horizon = LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC)
                .minusDays(properties.history().statementRetentionDays());
        String stamped = CobolDecimal.formatProcessingTimestamp(horizon);

        if (stamped.length() != PicClause.PROCESSING_TIMESTAMP_WIDTH) {
            LOG.error("The statement horizon rendered {} characters where the column holds {}, so"
                            + " no read-model row was removed this pass.", stamped.length(),
                    PicClause.PROCESSING_TIMESTAMP_WIDTH);
            return;
        }
        drain("statement_transaction",
                limit -> statementTransactions.deleteProcessedBefore(stamped, limit));
    }

    /**
     * Deletes rendered-alert rows past {@code carddemo.history.log-retention-days}.
     */
    public void sweepRenderedAlertRows() {
        Instant horizon = clock.instant()
                .minus(Duration.ofDays(properties.history().logRetentionDays()));
        drain("notification_log", limit -> notificationLog.deleteRenderedBefore(horizon, limit));
    }

    /**
     * Repeats one bounded delete until it clears the table, exhausts the table deadline, or fails.
     *
     * <p>Each batch runs in a transaction of its own and reports its outcome, naming the table and
     * the row count and no row value.
     *
     * <p>A batch that removes fewer rows than {@link #PURGE_BATCH_SIZE} has reached the end of what
     * the horizon covers, so the drain stops there rather than issuing one more statement that would
     * remove nothing. A batch that removes exactly the ceiling means more rows remain, and the loop
     * takes the next batch inside a fresh transaction.
     *
     * <p>Draining is what keeps the bound from turning a backlog into a permanent one. A single
     * bounded pass on an interval leaves the surplus behind for ever once rows arrive faster than one
     * batch a pass, and the table then grows without limit even though every statement against it was
     * short.
     *
     * @param table  the table being swept, named in the diagnostic
     * @param delete one bounded delete, taking the row ceiling and answering how many it removed
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
                total += count;
                batches++;

                if (count < PURGE_BATCH_SIZE) {
                    break;
                }
                if (System.nanoTime() - deadline >= 0L) {
                    LOG.info("Retention removed {} rows from {} in {} batches and stopped at its {}"
                                    + " ceiling. The remainder is removed on the next pass.",
                            total, table, batches, maxTableDuration);
                    return;
                }
            }
        } catch (DataAccessException failure) {
            LOG.warn("Retention could not sweep {} after a {} having removed {} rows, and the next"
                            + " pass tries again", table, failure.getClass().getSimpleName(), total);
            return;
        }

        if (total > 0) {
            LOG.info("Retention removed {} expired rows from {} in {} batches", total, table,
                    batches);
        }
    }

    /** One bounded retention delete, answering how many rows it removed. */
    @FunctionalInterface
    private interface RowDelete {

        /**
         * Runs the delete against one row ceiling.
         *
         * @param limit the largest number of rows this statement removes
         * @return the number of rows removed
         */
        int run(int limit);
    }
}
