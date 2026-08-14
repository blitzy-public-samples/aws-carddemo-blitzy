package com.carddemo.authorization.domain;

import com.carddemo.authorization.config.AuthorizationProperties;
import com.carddemo.authorization.repository.AuthorizationDecisionRepository;
import com.carddemo.authorization.repository.OutboxEventRepository;
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
 * Removes published outbox rows and decision rows after their configured horizons.
 *
 * <p>{@code processed_event} is deliberately absent. A duplicate-delivery claim is permanent, so
 * nothing removes one and this sweep has no horizon to apply to that table. Migration
 * {@code V21__processed_event_claims_are_permanent.sql} states the same thing in the catalogue.
 *
 * <p>No COBOL ancestor. The source has no retention concern at all: every dataset is a Virtual
 * Storage Access Method file whose lifetime the Job Control Language owns, and
 * {@code app/jcl/POSTTRAN.jcl} deletes and redefines its output rather than pruning it.
 *
 * <h2>Each delete is bounded and drained</h2>
 *
 * <p>Each delete is issued as a bounded, ordered statement of at most {@value #PURGE_BATCH_SIZE}
 * rows in a transaction of its own, and repeated until it removes fewer rows than that ceiling.
 * Both halves are needed and each is useless alone. An unbounded statement holds every row it
 * removes under one lock for the whole statement, on tables the authorization path writes to on
 * every call. A bounded statement issued once an interval and never repeated leaves every row above
 * one batch behind for ever, so a table grows without limit even though every statement against it
 * was small.
 *
 * <p>That second failure is the one this class had. Each pass removed at most a thousand rows from
 * each of four tables and stopped, once an hour, while the repository documentation stated that the
 * caller repeated the call until it came back short. Any arrival rate above four thousand expired
 * rows an hour therefore grew the schema for ever, and the documentation described a drain that did
 * not exist.
 *
 * <p>The drain is itself bounded, by {@link #MAX_TABLE_DURATION} measured from a monotonic clock. A
 * table whose backlog cannot be cleared inside that window leaves the remainder to the next pass
 * rather than holding the scheduled thread. That ceiling is what stops one very large table from
 * starving the ones swept after it, which is why the tables are swept in sequence with a deadline
 * each rather than under one budget shared between them.
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
     * one statement's locks are released promptly. The same ceiling as the sibling services, so one
     * number describes the platform rather than six.
     */
    static final int PURGE_BATCH_SIZE = 500;

    /**
     * Wall-clock ceiling on the drain of one table.
     *
     * <p>Finite by design. Without it a table with an unbounded backlog would hold the scheduled
     * thread for as long as the backlog took to clear, and the tables after it would not be swept at
     * all.
     */
    static final Duration MAX_TABLE_DURATION = Duration.ofSeconds(30);

    /** Holds the published rows this sweep removes. */
    private final OutboxEventRepository outboxEvents;

    /** Holds the decision rows this sweep removes. */
    private final AuthorizationDecisionRepository decisions;

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

    /** How long a decision row stays for audit. */
    private final Duration decisionRetention;

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
     * @param outboxEvents           store over {@code outbox_event}
     * @param decisions              store over {@code authorization_decision}
     * @param transactionTemplate    the boundary each bounded delete runs inside
     * @param properties             the bound {@code carddemo} settings
     * @throws NullPointerException when any argument is {@code null}
     */
    // The annotation is required, not decoration. Two constructors leave the container with two
    // candidates and no way to choose, and it reports "No default constructor found" on every
    // context load rather than naming the ambiguity.
    @Autowired
    public RetentionSweep(OutboxEventRepository outboxEvents,
            AuthorizationDecisionRepository decisions, TransactionTemplate transactionTemplate,
            AuthorizationProperties properties) {
        this(outboxEvents, decisions, transactionTemplate, properties, MAX_TABLE_DURATION);
    }

    /**
     * Takes the same collaborators and an explicit drain ceiling.
     *
     * <p>Package-private, for the test that proves an endless backlog stops at the ceiling. The
     * production ceiling is thirty seconds and a test that waited for it would spend that long
     * looping.
     *
     * @param outboxEvents           store over {@code outbox_event}
     * @param decisions              store over {@code authorization_decision}
     * @param transactionTemplate    the boundary each bounded delete runs inside
     * @param properties             the bound {@code carddemo} settings
     * @param maxTableDuration       the drain ceiling for one table
     * @throws NullPointerException when any argument is {@code null}
     */
    RetentionSweep(OutboxEventRepository outboxEvents,
            AuthorizationDecisionRepository decisions, TransactionTemplate transactionTemplate,
            AuthorizationProperties properties, Duration maxTableDuration) {
        this.outboxEvents = Objects.requireNonNull(outboxEvents, "outboxEvents");
        this.decisions = Objects.requireNonNull(decisions, "decisions");
        this.transactionTemplate =
                Objects.requireNonNull(transactionTemplate, "transactionTemplate");
        AuthorizationProperties checked = Objects.requireNonNull(properties, "properties");
        this.publishedRetention = Duration.ofHours(checked.outbox().publishedRetentionHours());
        this.decisionRetention = Duration.ofDays(checked.retention().decisionRetentionDays());
        this.maxTableDuration = Objects.requireNonNull(maxTableDuration, "maxTableDuration");
    }

    /**
     * Drains each table in bounded batches, each batch in its own explicit transaction.
     *
     * <p>No failure leaves this method. Each table reports its own outcome, so a permission problem
     * on one does not hide the other.
     *
     * <p>One audit table remains, and {@code carddemo.retention.decision-retention-days} is its
     * horizon. {@code unresolved_card_attempt} was the second, and it is withdrawn: a card that
     * resolves no cross-reference row now refuses the call rather than deciding it, so nothing
     * writes that table and migration {@code V20} drops it.
     */
    @Scheduled(fixedDelayString = "${carddemo.retention.sweep-interval-ms:3600000}")
    public void purgeExpiredRows() {
        Instant now = Instant.now();
        Instant publishedHorizon = now.minus(publishedRetention);
        Instant auditHorizon = now.minus(decisionRetention);

        drain("outbox_event",
                limit -> outboxEvents.deletePublishedBefore(publishedHorizon, limit));
        drain("authorization_decision",
                limit -> decisions.deleteDecidedBefore(auditHorizon, limit));
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
