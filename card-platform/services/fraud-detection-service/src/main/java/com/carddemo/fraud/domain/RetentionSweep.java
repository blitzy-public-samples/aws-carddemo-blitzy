package com.carddemo.fraud.domain;

import com.carddemo.fraud.config.FraudProperties;
import com.carddemo.fraud.repository.FraudAssessmentRepository;
import com.carddemo.fraud.repository.OutboxEventRepository;
import com.carddemo.fraud.repository.ProcessedEventRepository;
import com.carddemo.fraud.repository.VelocityWindowRepository;
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
 * Removes published outbox rows, processed-event markers, expired risk assessments and elapsed
 * velocity windows after their configured horizons.
 *
 * <p>No COBOL ancestor, in two senses. This whole service is ADDITIVE, so nothing in
 * {@code app/cbl/} corresponds to it; and the source has no retention concern of any kind, because
 * every dataset is a Virtual Storage Access Method file whose lifetime the Job Control Language
 * owns rather than the program. {@code app/jcl/POSTTRAN.jcl} deletes and redefines its output
 * instead of pruning it.
 *
 * <h2>The four tables and why each grows</h2>
 *
 * <p>{@code outbox_event} holds one row per published assessment and keeps it after publication so
 * a delivery can be diagnosed. {@code processed_event} holds one marker per consumed event for as
 * long as a redelivery of it is still possible. {@code velocity_window} is the one that grows
 * fastest and the one whose growth is least visible: {@code domain/RiskScoringService} opens a new
 * bucket as soon as the configured span elapses, so every authorization leaves behind a row that
 * the scoring path never reads again. One row per account per span, for ever, until something
 * removes it.
 *
 * <p>{@code fraud_assessment} and {@code velocity_window} are the business tables of this service,
 * and each declared its horizon before anything applied it. {@code COMMENT ON TABLE} in
 * {@code src/main/resources/db/migration/V1__schema.sql} names ninety days from
 * {@code assessed_at} and seven days from {@code window_start}, and this sweep once removed outbox
 * rows and markers only, so both declarations described an intention while the tables only grew.
 * Both rows are pseudonymous rather than anonymous, because {@code account_id} and
 * {@code transaction_id} resolve to a named customer through the account and ledger services, so
 * both horizons are privacy horizons and not only housekeeping ones.
 *
 * <h2>Each delete is bounded and drained</h2>
 *
 * <p>All four tables are on the hot path. The relay sweeps {@code outbox_event} on a fixed delay,
 * the authorization listener writes {@code processed_event} on every event, and
 * {@code velocity_window} is written by the same listener and read by the velocity rule inside the
 * scoring transaction. A single unbounded {@code DELETE} holds every row it removes under one lock
 * for the whole statement, so a schema idle long enough to accumulate a week of rows takes one long
 * statement that blocks the relay, the listener and the scoring path while it runs. The row count
 * is not knowable in advance, so neither is how long that lasts.
 *
 * <p>Each delete is therefore a bounded, ordered statement of at most {@value #PURGE_BATCH_SIZE}
 * rows in a transaction of its own, repeated until it removes fewer rows than that ceiling.
 * Draining is what keeps the bound from turning a backlog into a permanent one: a single bounded
 * pass on an hourly interval leaves the surplus behind for ever once rows arrive faster than one
 * batch an hour, and the table then grows without limit even though every statement against it was
 * small.
 *
 * <p>The drain is itself bounded, by {@link #MAX_TABLE_DURATION} measured from a monotonic clock. A
 * table whose backlog cannot be drained inside that window keeps the remainder for the next pass
 * rather than holding this thread. That ceiling is what stops one very large table from starving
 * the other three, which is why the four are swept in sequence with a deadline each rather than
 * under one shared budget.
 *
 * <h2>What the velocity horizon costs</h2>
 *
 * <p>The other three horizons may be any positive duration. The velocity horizon may not: a window
 * is counted into by every authorization arriving while it is current, so a horizon shorter than
 * the window span would delete the bucket the scoring path is still counting into. The symptom
 * would be a burst that quietly stopped triggering the velocity rule rather than an error anyone
 * could see, which is why start-up is refused twice: {@link FraudProperties} refuses the
 * comparison as it binds, and {@link #requireVelocityHorizonOutlastsItsWindow(FraudProperties)}
 * refuses it again as this class is built, so neither a bound record nor a hand-built one reaches
 * the sweep.
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
     * number describes the platform rather than three.
     */
    static final int PURGE_BATCH_SIZE = 500;

    /** Minutes in one day, for comparing a horizon in days against a window span in minutes. */
    private static final int MINUTES_PER_DAY = 1440;

    /**
     * Wall-clock ceiling on the drain of one table.
     *
     * <p>Finite by design. Without it a table with an unbounded backlog would hold the scheduled
     * thread for as long as the backlog took to clear, and the tables after it would never be swept
     * at all.
     */
    static final Duration MAX_TABLE_DURATION = Duration.ofSeconds(30);

    /** Holds the published rows this sweep removes. */
    private final OutboxEventRepository outboxEvents;

    /** Holds the duplicate-delivery markers this sweep removes. */
    private final ProcessedEventRepository processedEvents;

    /** Holds the expired risk assessments this sweep removes. */
    private final FraudAssessmentRepository assessments;

    /** Holds the elapsed velocity buckets this sweep removes. */
    private final VelocityWindowRepository velocityWindows;

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

    /** How long a processed-event marker stays while a redelivery is still possible. */
    private final Duration markerRetention;

    /**
     * How long a risk assessment stays after it was made.
     *
     * <p>{@code COMMENT ON TABLE fraud_assessment} declares this horizon and nothing applied it
     * until a security review, so the declaration and the delete now read the same setting.
     */
    private final Duration assessmentRetention;

    /**
     * How long a velocity bucket stays after it started.
     *
     * <p>Validated at binding to exceed the scoring window span, so this class can subtract it from
     * the present without re-checking that the result is safe.
     */
    private final Duration velocityRetention;

    /**
     * The drain ceiling in force, {@link #MAX_TABLE_DURATION} in production.
     *
     * <p>A field rather than the constant read directly, so a test can prove the ceiling stops an
     * endless backlog without spending thirty seconds of the build doing it.
     */
    private final Duration maxTableDuration;

    /**
     * Takes the four stores, the transaction boundary and the four horizons.
     *
     * @param outboxEvents        store over {@code outbox_event}
     * @param processedEvents     store over {@code processed_event}
     * @param assessments         store over {@code fraud_assessment}
     * @param velocityWindows     store over {@code velocity_window}
     * @param transactionTemplate the boundary each bounded delete runs inside
     * @param properties          the bound {@code carddemo} settings
     * @throws NullPointerException when any argument is {@code null}
     */
    // The annotation is required, not decoration. Two constructors leave the container with two
    // candidates and no way to choose, and it reports "No default constructor found" on every
    // context load rather than naming the ambiguity.
    @Autowired
    public RetentionSweep(OutboxEventRepository outboxEvents,
            ProcessedEventRepository processedEvents, FraudAssessmentRepository assessments,
            VelocityWindowRepository velocityWindows, TransactionTemplate transactionTemplate,
            FraudProperties properties) {
        this(outboxEvents, processedEvents, assessments, velocityWindows, transactionTemplate,
                properties, MAX_TABLE_DURATION);
    }

    /**
     * Takes the same collaborators and an explicit drain ceiling.
     *
     * <p>Package-private, for the test that proves an endless backlog stops at the ceiling. The
     * production ceiling is thirty seconds and a test that waited for it would spend that long
     * looping.
     *
     * @param outboxEvents        store over {@code outbox_event}
     * @param processedEvents     store over {@code processed_event}
     * @param assessments         store over {@code fraud_assessment}
     * @param velocityWindows     store over {@code velocity_window}
     * @param transactionTemplate the boundary each bounded delete runs inside
     * @param properties          the bound {@code carddemo} settings
     * @param maxTableDuration    the drain ceiling for one table
     * @throws NullPointerException when any argument is {@code null}
     */
    RetentionSweep(OutboxEventRepository outboxEvents, ProcessedEventRepository processedEvents,
            FraudAssessmentRepository assessments, VelocityWindowRepository velocityWindows,
            TransactionTemplate transactionTemplate, FraudProperties properties,
            Duration maxTableDuration) {
        this.outboxEvents = Objects.requireNonNull(outboxEvents, "outboxEvents");
        this.processedEvents = Objects.requireNonNull(processedEvents, "processedEvents");
        this.assessments = Objects.requireNonNull(assessments, "assessments");
        this.velocityWindows = Objects.requireNonNull(velocityWindows, "velocityWindows");
        this.transactionTemplate =
                Objects.requireNonNull(transactionTemplate, "transactionTemplate");
        FraudProperties checked = Objects.requireNonNull(properties, "properties");
        this.publishedRetention = Duration.ofHours(checked.outbox().publishedRetentionHours());
        this.markerRetention = Duration.ofHours(checked.processedEvent().markerRetentionHours());
        this.assessmentRetention = Duration.ofDays(checked.retention().assessmentRetentionDays());
        this.velocityRetention = Duration.ofDays(checked.retention().velocityRetentionDays());
        this.maxTableDuration = Objects.requireNonNull(maxTableDuration, "maxTableDuration");
        requireVelocityHorizonOutlastsItsWindow(checked);
    }

    /**
     * Refuses a velocity horizon that does not outlast the window it expires.
     *
     * <p>A bucket is still being counted into for the length of the configured window, so a
     * retention shorter than that window deletes a row a live authorization is incrementing and the
     * count is silently lost. {@link FraudProperties} makes the same comparison as it binds, and
     * this one covers a record built by hand: the two settings live in different sub-records, so
     * neither can validate the other and the check belongs wherever both are in scope. Start-up
     * stops, naming both values, rather than a sweep discovering it once an hour.
     *
     * @param properties the bound settings
     * @throws IllegalArgumentException when the horizon does not exceed the window span
     */
    private static void requireVelocityHorizonOutlastsItsWindow(FraudProperties properties) {
        int windowMinutes = properties.fraud().risk().velocityWindowMinutes();
        long horizonMinutes =
                (long) properties.retention().velocityRetentionDays() * MINUTES_PER_DAY;

        if (horizonMinutes <= windowMinutes) {
            throw new IllegalArgumentException("carddemo.retention.velocity-retention-days of "
                    + properties.retention().velocityRetentionDays() + " must exceed"
                    + " carddemo.fraud.risk.velocity-window-minutes of " + windowMinutes
                    + ", or the sweep removes the window a live authorization is counting into");
        }
    }

    /**
     * Drains each table in bounded batches, each batch in its own explicit transaction.
     *
     * <p>No failure leaves this method. Each table reports its own outcome, so a permission problem
     * on one does not hide the others.
     */
    @Scheduled(fixedDelayString = "${carddemo.retention.sweep-interval-ms:3600000}")
    public void purgeExpiredRows() {
        Instant now = Instant.now();
        Instant publishedHorizon = now.minus(publishedRetention);
        Instant markerHorizon = now.minus(markerRetention);
        Instant assessmentHorizon = now.minus(assessmentRetention);
        Instant velocityHorizon = now.minus(velocityRetention);

        drain("outbox_event",
                limit -> outboxEvents.deletePublishedBefore(publishedHorizon, limit));
        drain("processed_event",
                limit -> processedEvents.deleteMarkersProcessedBefore(markerHorizon, limit));
        drain("fraud_assessment",
                limit -> assessments.deleteAssessedBefore(assessmentHorizon, limit));
        drain("velocity_window",
                limit -> velocityWindows.deleteWindowsStartedBefore(velocityHorizon, limit));
    }

    /**
     * Repeats one bounded delete until it clears the table, exhausts the table deadline, or fails.
     *
     * <p>A batch that removes fewer rows than {@link #PURGE_BATCH_SIZE} has reached the end of what
     * the horizon covers, so the drain stops there rather than issuing one more statement that
     * would remove nothing. A batch that removes exactly the ceiling means more rows remain, and
     * the loop takes the next batch inside a fresh transaction.
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
