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
import org.springframework.dao.DataAccessException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
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
 * An attempt row records an alert already sent and is the shortest-lived of the three.
 *
 * <p>Each delete runs in a transaction of its own, so one that fails leaves the other two done. A
 * failure is logged and the schedule carries on: a sweep that cannot run is an operational condition
 * and never a reason to fail a delivery.
 *
 * <p>Decisions: {@code card-platform/docs/decision-log.md}.
 */
@Component
public class RetentionSweep {

    /** Writes the diagnostic lines this class emits, none carrying a row value. */
    private static final Logger LOG = LoggerFactory.getLogger(RetentionSweep.class);

    /** Rows one marker purge deletes, so a long-idle schema cannot produce one huge statement. */
    private static final int MARKER_PURGE_LIMIT = 1000;

    /** Store of the card-keyed read model. */
    /** Rows one purge deletes, so a long-idle schema cannot produce one huge statement. */
    private static final int ROW_PURGE_LIMIT = 1000;

    private final StatementTransactionRepository statementTransactions;

    /** Store of the alert-attempt rows. */
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
     * Takes the three stores, the bound horizons and the transaction boundary.
     *
     * @param statementTransactions store of the card-keyed read model
     * @param notificationLog       store of the alert-attempt rows
     * @param processedEvents       store of the duplicate-delivery markers
     * @param properties            the bound {@code carddemo} block
     * @param transactionTemplate   opens one transaction per delete
     * @throws NullPointerException if any argument is {@code null}
     */
    public RetentionSweep(StatementTransactionRepository statementTransactions,
            NotificationLogRepository notificationLog, ProcessedEventRepository processedEvents,
            NotificationProperties properties, TransactionTemplate transactionTemplate) {
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
     * arrives as a {@link DataAccessException}, which {@link #report} logs and swallows, so an
     * annotation here would leave every horizon unenforced while reporting nothing louder than a
     * warning. Opening the transaction explicitly cannot be bypassed that way.
     */
    @Scheduled(fixedDelayString = "${carddemo.history.sweep-interval-ms}")
    public void sweepExpiredRows() {
        sweepMarkers();
        sweepReadModelRows();
        sweepAttemptRows();
    }

    /**
     * Deletes markers past {@code carddemo.processed-event.marker-retention-hours}.
     */
    public void sweepMarkers() {
        Instant horizon = clock.instant()
                .minus(Duration.ofHours(properties.processedEvent().markerRetentionHours()));
        report("processed_event",
                () -> processedEvents.deleteMarkersProcessedBefore(horizon, MARKER_PURGE_LIMIT));
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
        report("statement_transaction",
                () -> statementTransactions.deleteProcessedBefore(stamped, ROW_PURGE_LIMIT));
    }

    /**
     * Deletes alert-attempt rows past {@code carddemo.history.log-retention-days}.
     */
    public void sweepAttemptRows() {
        Instant horizon = clock.instant()
                .minus(Duration.ofDays(properties.history().logRetentionDays()));
        report("notification_log", () -> notificationLog.deleteAttemptsBefore(horizon));
    }

    /**
     * Runs one delete in a transaction of its own and reports its outcome, naming the table and the
     * row count and no row value.
     *
     * @param table  the table being swept, named in the diagnostic
     * @param delete the delete to run, answering how many rows it removed
     */
    private void report(String table, RowDelete delete) {
        try {
            Integer removed = transactionTemplate.execute(status -> delete.run());
            if (removed != null && removed > 0) {
                LOG.info("Retention removed {} expired rows from {}", removed, table);
            }
        } catch (DataAccessException failure) {
            LOG.warn("Retention could not sweep {} after a {}, and the next pass tries again",
                    table, failure.getClass().getSimpleName());
        }
    }

    /** One retention delete, answering how many rows it removed. */
    @FunctionalInterface
    private interface RowDelete {

        /**
         * Runs the delete.
         *
         * @return the number of rows removed
         */
        int run();
    }
}
