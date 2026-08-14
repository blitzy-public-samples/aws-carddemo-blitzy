package com.carddemo.ledger.domain;

import com.carddemo.ledger.config.LedgerProperties;
import com.carddemo.ledger.repository.OutboxEventRepository;
import com.carddemo.ledger.repository.RejectedTransactionRepository;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.function.IntUnaryOperator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Removes published outbox rows and expired rejects after their configured horizons.
 *
 * <p>{@code processed_event} is deliberately absent. A duplicate-delivery claim is permanent,
 * because the posting it guards is permanent, so nothing removes a claim and this sweep has no
 * horizon to apply to that table. Migration
 * {@code V11__processed_event_claims_are_permanent.sql} states the same thing in the catalogue.
 *
 * <p>No COBOL ancestor. The source has no retention concern at all: every dataset is a Virtual
 * Storage Access Method file whose lifetime the Job Control Language owns, and
 * {@code app/jcl/POSTTRAN.jcl} deletes and redefines its output rather than pruning it.
 *
 * <h2>The third table, and why expiring it is faithful</h2>
 *
 * <p>{@code rejected_transaction} is a business table, and it declares
 * {@code retention=90 days; purge_key=rejected_at} in its own {@code COMMENT ON TABLE} in
 * {@code src/main/resources/db/migration/V1__schema.sql}. Nothing applied that horizon, so the
 * table grew by one row per refused feed record while the catalogue said it was bounded. A row
 * carries the transaction identifier, the amount, the merchant and a masked card number, and the
 * first of those resolves to a named customer through this service's own transaction table, so the
 * horizon is a privacy one and not only housekeeping.
 *
 * <p>Expiring a reject by age is faithful rather than additive. The source writes each reject to a
 * generation of a Generation Data Group at {@code app/jcl/POSTTRAN.jcl:L34-L38}, and
 * {@code app/jcl/DALYREJS.jcl:L24-L28} defines that base with {@code LIMIT(5)} and {@code SCRATCH},
 * so writing a sixth generation deletes the oldest. The three balance and transaction tables of
 * this service carry no horizon and are not swept: those are the ledger itself.
 *
 * <h2>Each delete is bounded and drained</h2>
 *
 * <p>Both swept tables are on the hot write path. {@code outbox_event} is written by every posting
 * and swept by the relay on a fixed delay of half a second, and {@code rejected_transaction} is
 * written by every reject the three listeners record. A single unbounded {@code DELETE} holds every
 * row it removes under one lock for the whole statement, so a schema that has been idle long enough
 * to accumulate a week of rows takes one long-running delete that blocks the relay and all three
 * listeners while it runs. The row count is not knowable in advance, so neither is how long that
 * lasts.
 *
 * <p>Each delete is therefore issued as a bounded, ordered statement of at most
 * {@value #PURGE_BATCH_SIZE} rows in a transaction of its own, and repeated until it removes fewer
 * rows than that ceiling. Draining is what keeps the bound from turning a backlog into a permanent
 * one: a single bounded pass on an interval leaves the surplus behind for ever if rows arrive faster
 * than one batch an hour.
 *
 * <p>The drain is itself bounded, by {@link #MAX_TABLE_DURATION} measured from a monotonic clock. A
 * table whose backlog cannot be drained inside that window keeps the remainder for the next pass
 * rather than holding this thread. That ceiling is what stops one very large table from starving the
 * other, and it is why the two tables are swept in sequence with a deadline each rather than under
 * one shared budget.
 *
 * <p>Design decisions: {@code card-platform/docs/decision-log.md}.
 */
@Component
public class RetentionSweep {

    /** Diagnostic output of this class, carrying row counts and table names and no row value. */
    private static final Logger log = LoggerFactory.getLogger(RetentionSweep.class);

    /**
     * Rows one bounded delete removes at most.
     *
     * <p>Large enough that an ordinary pass drains in one or two statements, and small enough that
     * one statement's locks are released promptly. The relay claims a hundred rows a sweep at a
     * half-second delay, so five hundred is several sweeps' worth of published rows and still a
     * short statement.
     */
    static final int PURGE_BATCH_SIZE = 500;

    /**
     * Wall-clock ceiling on the drain of one table.
     *
     * <p>Finite by design. Without it a table with an unbounded backlog would hold the scheduled
     * thread for as long as the backlog took to clear, and the second table would never be swept at
     * all.
     */
    static final Duration MAX_TABLE_DURATION = Duration.ofSeconds(30);

    /** Holds the published rows this sweep removes. */
    private final OutboxEventRepository outboxEvents;

    /** Holds the expired rejects this sweep removes. */
    private final RejectedTransactionRepository rejectedTransactions;

    /**
     * The boundary each bounded delete runs inside.
     *
     * <p>Explicit rather than {@code @Transactional} on this class: a scheduled method calling its
     * own neighbours calls them on {@code this}, which does not pass through the proxy that would
     * start a transaction, and a modifying query outside a transaction fails.
     */
    private final TransactionTemplate transactionTemplate;

    /** How long a published outbox row stays for diagnosis. */
    private final Duration publishedRetention;

    /**
     * How long a reject stays before its privacy horizon expires it.
     *
     * <p>{@code COMMENT ON TABLE rejected_transaction} declares this horizon and nothing applied it
     * until a security review, so the declaration and the delete now read the same setting.
     */
    private final Duration rejectedRetention;

    /**
     * The drain ceiling in force, {@link #MAX_TABLE_DURATION} in production.
     *
     * <p>A field rather than the constant read directly, so a test can prove the ceiling stops an
     * endless backlog without spending thirty seconds of the build doing it.
     */
    private final Duration maxTableDuration;

    /**
     * Takes the two stores, the transaction boundary and the two horizons.
     *
     * @param outboxEvents         store over {@code outbox_event}
     * @param rejectedTransactions store over {@code rejected_transaction}
     * @param transactionTemplate  the boundary each bounded delete runs inside
     * @param properties           the bound {@code carddemo} settings
     * @throws NullPointerException when any argument is {@code null}
     */
    // The annotation is required, not decoration. Two constructors leave the container with two
    // candidates and no way to choose, and it reports "No default constructor found" on every
    // context load rather than naming the ambiguity.
    @Autowired
    public RetentionSweep(OutboxEventRepository outboxEvents,
            RejectedTransactionRepository rejectedTransactions,
            TransactionTemplate transactionTemplate, LedgerProperties properties) {
        this(outboxEvents, rejectedTransactions, transactionTemplate, properties,
                MAX_TABLE_DURATION);
    }

    /**
     * Takes the same collaborators and an explicit drain ceiling.
     *
     * <p>Package-private, for the test that proves an endless backlog stops at the ceiling. The
     * production ceiling is thirty seconds and a test that waited for it would spend that long
     * looping.
     *
     * @param outboxEvents         store over {@code outbox_event}
     * @param rejectedTransactions store over {@code rejected_transaction}
     * @param transactionTemplate  the boundary each bounded delete runs inside
     * @param properties           the bound {@code carddemo} settings
     * @param maxTableDuration     the drain ceiling for one table
     * @throws NullPointerException when any argument is {@code null}
     */
    RetentionSweep(OutboxEventRepository outboxEvents,
            RejectedTransactionRepository rejectedTransactions,
            TransactionTemplate transactionTemplate, LedgerProperties properties,
            Duration maxTableDuration) {
        this.outboxEvents = Objects.requireNonNull(outboxEvents, "outboxEvents");
        this.rejectedTransactions =
                Objects.requireNonNull(rejectedTransactions, "rejectedTransactions");
        this.transactionTemplate =
                Objects.requireNonNull(transactionTemplate, "transactionTemplate");
        LedgerProperties checked = Objects.requireNonNull(properties, "properties");
        this.publishedRetention = Duration.ofHours(checked.outbox().publishedRetentionHours());
        this.rejectedRetention =
                Duration.ofDays(checked.retention().rejectedTransactionRetentionDays());
        this.maxTableDuration = Objects.requireNonNull(maxTableDuration, "maxTableDuration");
    }

    /**
     * Drains each table in bounded batches, each batch in its own explicit transaction.
     *
     * <p>No failure leaves this method. Each table reports its own outcome, so a permission problem
     * on one does not hide the other.
     */
    @Scheduled(fixedDelayString = "${carddemo.retention.sweep-interval-ms:3600000}")
    public void purgeExpiredRows() {
        Instant now = Instant.now();
        Instant publishedHorizon = now.minus(publishedRetention);
        Instant rejectedHorizon = now.minus(rejectedRetention);

        drain("outbox_event",
                limit -> outboxEvents.deletePublishedBefore(publishedHorizon, limit));
        drain("rejected_transaction",
                limit -> rejectedTransactions.deleteRejectedBefore(rejectedHorizon, limit));
    }

    /**
     * Repeats one bounded delete until it clears the table, exhausts the table deadline, or fails.
     *
     * <p>A batch that removes fewer rows than {@link #PURGE_BATCH_SIZE} has reached the end of what
     * the horizon covers, so the drain stops there rather than issuing one more statement that would
     * remove nothing. A batch that removes exactly the ceiling means more rows remain, and the loop
     * takes the next batch inside a fresh transaction.
     *
     * @param table   the table being swept, named in the diagnostic
     * @param deleted one bounded delete, taking the row ceiling and answering how many it removed
     */
    private void drain(String table, IntUnaryOperator deleted) {
        long deadline = System.nanoTime() + maxTableDuration.toNanos();
        TransactionCallback<Integer> batch = status -> deleted.applyAsInt(PURGE_BATCH_SIZE);
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
                    log.info("Retention removed {} rows from {} in {} batches and stopped at its {}"
                                    + " ceiling. The remainder is removed on the next pass.",
                            total, table, batches, maxTableDuration);
                    return;
                }
            }
        } catch (RuntimeException failure) {
            log.warn("Retention sweep for {} failed after {} having removed {} rows, and will run"
                            + " again", table, rootCause(failure).getClass().getSimpleName(),
                    total);
            return;
        }

        if (total > 0) {
            log.info("Retention removed {} expired rows from {} in {} batches", total, table,
                    batches);
        } else {
            log.debug("Retention found no expired rows in {}", table);
        }
    }

    /**
     * Returns the deepest cause of one failure, stopping on a cause that names itself.
     *
     * @param failure the failure to walk
     * @return the deepest cause, or {@code failure} when it wraps none
     */
    private static Throwable rootCause(Throwable failure) {
        Throwable cause = failure;
        while (cause.getCause() != null && cause.getCause() != cause) {
            cause = cause.getCause();
        }
        return cause;
    }
}
