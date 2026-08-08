package com.carddemo.authorization.domain;

import java.time.Clock;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

import com.carddemo.authorization.entity.ReplicaGapEntity;
import com.carddemo.authorization.repository.ReplicaGapRepository;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Opens and closes the record of accounts whose replica copy is missing a change.
 *
 * <p>ADDITIVE, with no COBOL ancestor: the source reads its datasets directly and holds no replica
 * that can fall behind.
 *
 * <p>This is the half of replica trust that consumer lag cannot supply. A record that is delivered
 * and cannot be applied has its offset advanced once its diagnostic is away, because
 * {@code config/KafkaConsumerConfig} sets {@code commitRecovered} rather than redelivering a poison
 * record for ever. Lag therefore returns to zero while one account's copy is behind, and a decision
 * for that account would read a credit limit or an expiry this service knows it failed to apply.
 *
 * <p>{@link #recordFailure} runs in its <strong>own</strong> transaction, and that is the whole
 * reason this class exists rather than a repository call inside the listener. The delivery that
 * failed rolls back, and a gap written inside that transaction would roll back with it, leaving no
 * trace of the very failure it was recording. {@link Propagation#REQUIRES_NEW} suspends the caller's
 * transaction, commits the gap, and lets the failure propagate afterwards so the container still
 * retries and still dead-letters.
 *
 * <p>{@link #clear} runs inside the caller's transaction, which is the opposite requirement for the
 * opposite reason: the copy and the record of its gap have to move together, and a delete that
 * committed without the apply would open the same hole the gap was closing.
 *
 * <p>Nothing expires a gap on a timer. A row lives until a later record for that account applies,
 * which is what makes the refusal it drives a statement about a missing change rather than about
 * elapsed time.
 */
@Service
public class ReplicaGapLog {

    /** Records that a gap opened, closed, or widened. Carries no value from any record. */
    private static final Logger LOG = LoggerFactory.getLogger(ReplicaGapLog.class);

    /** The gaps standing across both replica streams. */
    private final ReplicaGapRepository replicaGaps;

    /** The clock the failure moments are read from. */
    private final Clock clock = Clock.systemUTC();

    /**
     * Takes the store the gaps live in.
     *
     * @param replicaGaps the gap store
     * @throws NullPointerException when the store is absent
     */
    public ReplicaGapLog(ReplicaGapRepository replicaGaps) {
        this.replicaGaps = Objects.requireNonNull(replicaGaps, "replicaGaps must be present");
    }

    /**
     * Records that one account's record on one replica stream could not be applied.
     *
     * <p>Committed in its own transaction, so the failing delivery's rollback leaves the gap
     * standing. A second failure for the same account and stream widens the row it already has
     * rather than raising a duplicate key.
     *
     * <p>The log line and the stored value both carry the failure's class name and nothing else. A
     * failure message can quote the record it was raised for, and a record on either replica topic
     * carries a credit limit, a cycle balance and an account identifier.
     *
     * @param aggregateId the account whose record failed, eleven digits, or {@code null} when the
     *                    record named none
     * @param stream      the replica topic the record arrived on
     * @param failure     the failure the attempt raised, or {@code null} when none is available
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordFailure(String aggregateId, String stream, Throwable failure) {
        if (aggregateId == null || !aggregateId.matches("^[0-9]{11}$")) {
            // A record that names no account cannot have been applied to any row, so no account is
            // behind because of it. It is reported and counted by the listener's own error handling
            // and its diagnostic reaches the dead-letter topic; attributing it to every account
            // would let one unparseable record refuse every decision on the platform.
            LOG.error("A record on {} named no account, so no replica gap is attributable to it."
                    + " Its diagnostic carries the failure.", stream);
            return;
        }

        String failureClass = failure == null ? null : failure.getClass().getSimpleName();
        Instant now = clock.instant();
        Optional<ReplicaGapEntity> standing =
                replicaGaps.findByAggregateAndStream(aggregateId, stream);

        if (standing.isPresent()) {
            ReplicaGapEntity gap = standing.get();
            gap.recordFurtherFailure(now, failureClass);
            replicaGaps.save(gap);
            LOG.error("A further record on {} failed with {} for an account already behind. That"
                    + " account is refused until one applies.", stream, failureClass);
            return;
        }

        replicaGaps.save(new ReplicaGapEntity(aggregateId, stream, now, failureClass));
        LOG.error("A record on {} failed with {}, so the account it names is behind and is refused"
                + " until a later record for it applies.", stream, failureClass);
    }

    /**
     * Clears every gap standing for one account.
     *
     * <p>Joins the caller's transaction, so the applied copy and the cleared gap commit together.
     *
     * @param aggregateId the account whose record applied, eleven digits
     * @throws NullPointerException when the account is absent
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void clear(String aggregateId) {
        Objects.requireNonNull(aggregateId, "aggregateId must be present");

        if (replicaGaps.clearForAggregate(aggregateId) > 0) {
            LOG.info("A record applied for an account that was behind, so its replica gap is"
                    + " closed and decisions for it resume.");
        }
    }
}
