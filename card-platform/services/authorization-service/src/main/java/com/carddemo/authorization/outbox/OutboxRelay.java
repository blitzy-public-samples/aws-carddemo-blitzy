package com.carddemo.authorization.outbox;

import com.carddemo.authorization.config.AuthorizationProperties;
import com.carddemo.authorization.config.ObservabilityConfig;
import com.carddemo.authorization.entity.OutboxEventEntity;
import com.carddemo.authorization.entity.OutboxEventEntity.RelayState;
import com.carddemo.authorization.messaging.EventPublisherPort;
import com.carddemo.authorization.repository.OutboxEventRepository;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Limit;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Publishes the rows {@code OutboxWriter} stored, in a transaction of its own.
 *
 * <p>This class plays the pick-up half of the one asynchronous handoff the source has. At
 * {@code app/cbl/CORPT00C.cbl:L515-L523} paragraph {@code WIRTE-JOBSUB-TDQ} writes one record to a
 * Customer Information Control System (CICS) transient data queue, and a separate job reads it
 * later. The write and the send are two units of work there, as they are here.
 *
 * <p>Request handling publishes nothing. The decision and its outbox row commit first, and this
 * class sends afterwards, so a broker that is unreachable delays an event and never fails a
 * decision.
 *
 * <p>Each sweep runs in one transaction. It recovers stranded claims, locks a due batch with
 * {@code SKIP LOCKED}, records this instance on each row, then publishes and marks in order. A
 * send failure records a bounded retry and ends the sweep.
 *
 * <p>Atomicity and idempotency are both ADDITIVE. The three writes at
 * {@code app/cbl/CBTRN02C.cbl:L440-L442} run under no condition, and no rollback follows them. All
 * eight file definitions in {@code app/csd/CARDDEMO.CSD} carry {@code RECOVERY(NONE)} and
 * {@code JOURNAL(NO)}. The source detects no duplicate at all, and the write at
 * {@code app/cbl/CBTRN02C.cbl:L562-L579} answers a repeated key with an abend. Delivery here is
 * at-least-once, and each consumer records the event identifier it has processed.
 *
 * <p>The topic follows the event type. Each of the two types this service writes has one bound
 * topic property, so a deployment renames either topic with no change here. A row whose type has no
 * bound topic records an attempt and stays unpublished, reaching no topic at all.
 *
 * <p>A different event bus needs one more implementation of {@code messaging/EventPublisherPort}.
 *
 * <p>Design decisions: {@code card-platform/docs/decision-log.md}.
 */
@Component
public class OutboxRelay {

    /** Structured output for this class. No log line carries a payload or a message key. */
    private static final Logger log = LoggerFactory.getLogger(OutboxRelay.class);

    /** Reason recorded on a row whose claiming instance died before it finished. */
    private static final String CLAIM_EXPIRED = "ClaimExpired";

    /** Reason recorded on a row whose event type has no authorization destination. */
    private static final String UNKNOWN_EVENT_TYPE = "UnknownEventType";

    /**
     * The {@code event_type} an approval row carries, twenty-one characters.
     *
     * <p>{@code entity/OutboxEventEntity} names this value and the one below as the only two its
     * {@code event_type} column holds for this service.
     */
    private static final String TRANSACTION_AUTHORIZED = "TransactionAuthorized";

    /** The {@code event_type} a decline row carries, nineteen characters. */
    private static final String TRANSACTION_DECLINED = "TransactionDeclined";

    /** Reads unpublished rows and stores the published flag. */
    private final OutboxEventRepository outboxEvents;

    /** Sends one payload to one topic. */
    private final EventPublisherPort publisher;

    /** Rows swept per tick, from {@code carddemo.outbox.relay.batch-size}. */
    private final int batchSize;

    /** Each registered event type this service publishes, mapped to its configured topic. */
    private final Map<String, String> topics;

    /** The boundary one sweep runs inside, so the claim holds and no counter joins it. */
    private final TransactionTemplate transactionTemplate;

    /** Base of the retry backoff, from {@code carddemo.outbox.relay.fixed-delay-ms}. */
    private final Duration sweepDelay;

    /** How long a claim may stand before another sweep recovers it, and the backoff ceiling. */
    private final Duration claimTimeout;

    /** What this instance writes into {@code claimed_by}. */
    private final String instanceId;

    /**
     * Counts one publish fault, the one stage of {@link ObservabilityConfig#FAILURES_COUNTER} this
     * class records. This relay is the only part of the service that publishes.
     */
    private final Counter publishFailures;

    /**
     * Takes the store, the publisher and the configured topic names.
     *
     * @param outboxEvents        store of unpublished events
     * @param publisher           the event bus seam
     * @param meters              registry the publish failure counter registers with
     * @param transactionTemplate boundary one sweep runs inside
     * @param properties          the bound {@code carddemo} settings
     */
    public OutboxRelay(OutboxEventRepository outboxEvents, EventPublisherPort publisher,
            MeterRegistry meters, TransactionTemplate transactionTemplate,
            AuthorizationProperties properties) {
        this.outboxEvents = Objects.requireNonNull(outboxEvents, "outboxEvents");
        this.publisher = Objects.requireNonNull(publisher, "publisher");
        this.transactionTemplate =
                Objects.requireNonNull(transactionTemplate, "transactionTemplate");

        AuthorizationProperties checked = Objects.requireNonNull(properties, "properties");
        AuthorizationProperties.Outbox.Relay relay = checked.outbox().relay();
        AuthorizationProperties.Kafka.Topics configuredTopics = checked.kafka().topics();
        this.batchSize = relay.batchSize();
        this.sweepDelay = Duration.ofMillis(relay.fixedDelayMs());
        this.claimTimeout = relay.claimTimeout();
        this.instanceId = relay.instanceId();
        this.topics = Map.of(
                TRANSACTION_AUTHORIZED, configuredTopics.transactionAuthorized(),
                TRANSACTION_DECLINED, configuredTopics.transactionDeclined());
        this.publishFailures = Counter.builder(ObservabilityConfig.FAILURES_COUNTER)
                .tag(ObservabilityConfig.STAGE_TAG, ObservabilityConfig.PUBLISH_STAGE)
                .register(Objects.requireNonNull(meters, "meters"));
    }

    /** Runs one claimed sweep and records its failures after the transaction commits. */
    @Scheduled(fixedDelayString = "${carddemo.outbox.relay.fixed-delay-ms}")
    public void publishPendingEvents() {
        SweepResult result;
        try {
            result = transactionTemplate.execute(status -> sweepOnce());
        } catch (RuntimeException failure) {
            publishFailures.increment();
            log.warn("The authorization outbox sweep failed after {} and will run again",
                    rootCause(failure).getClass().getSimpleName());
            return;
        }
        Objects.requireNonNull(result, "the sweep must answer with a result")
                .record(publishFailures);
    }

    /**
     * Recovers stranded claims, claims due rows and publishes them in order.
     *
     * @return what this sweep published and what it could not, counted after the commit
     */
    private SweepResult sweepOnce() {
        Instant now = Instant.now();
        int failed = recoverStrandedClaims(now);
        int published = 0;

        List<OutboxEventEntity> pending = outboxEvents.claimDueRows(now, Limit.of(batchSize));
        for (OutboxEventEntity row : pending) {
            row.claim(instanceId, now);
            String topic = topics.get(row.getEventType());
            if (topic == null) {
                row.recordFailure(UNKNOWN_EVENT_TYPE, now,
                        now.plus(backoffAfter(row.getAttemptCount())));
                outboxEvents.save(row);
                failed++;
                log.error("An authorization outbox row carries the unconfigured event type {}. "
                        + "Attempt {} of {}.", row.getEventType(), row.getAttemptCount(),
                        OutboxEventEntity.MAX_DELIVERY_ATTEMPTS);
                continue;
            }
            try {
                publishAndMark(row, topic);
                published++;
            } catch (RuntimeException failure) {
                recordRefusedRow(row, failure, now);
                return new SweepResult(published, failed + 1);
            }
        }
        return new SweepResult(published, failed);
    }

    /**
     * Returns claims left by a stopped instance to {@link RelayState#PENDING}.
     *
     * @param now the moment this sweep started
     * @return the number of recovered rows
     */
    private int recoverStrandedClaims(Instant now) {
        List<OutboxEventEntity> stranded =
                outboxEvents.findByRelayStateAndClaimedAtBeforeOrderByClaimedAtAsc(
                        RelayState.CLAIMED, now.minus(claimTimeout), Limit.of(batchSize));
        for (OutboxEventEntity row : stranded) {
            row.recordFailure(CLAIM_EXPIRED, now, now);
            outboxEvents.save(row);
            log.warn("An authorization outbox claim expired for event type {}. Attempt {} of {}.",
                    row.getEventType(), row.getAttemptCount(),
                    OutboxEventEntity.MAX_DELIVERY_ATTEMPTS);
        }
        return stranded.size();
    }

    /**
     * Schedules another attempt for a row the publisher refused.
     *
     * @param row     the refused row
     * @param failure the publish failure
     * @param now     the moment this sweep started
     */
    private void recordRefusedRow(OutboxEventEntity row, RuntimeException failure, Instant now) {
        String failureClass = rootCause(failure).getClass().getSimpleName();
        row.recordFailure(failureClass, now, now.plus(backoffAfter(row.getAttemptCount())));
        outboxEvents.save(row);
        log.warn("An authorization outbox row of type {} stays unpublished after {}. "
                        + "Attempt {} of {}; the sweep stops here.",
                row.getEventType(), failureClass, row.getAttemptCount(),
                OutboxEventEntity.MAX_DELIVERY_ATTEMPTS);
    }

    /**
     * Returns the configured exponential wait before another attempt.
     *
     * @param attemptsSoFar attempts recorded before the failure being scheduled
     * @return a wait no longer than the claim timeout
     */
    private Duration backoffAfter(int attemptsSoFar) {
        Duration wait = sweepDelay;
        for (int step = 0; step < attemptsSoFar && wait.compareTo(claimTimeout) < 0; step++) {
            wait = wait.multipliedBy(2L);
        }
        return wait.compareTo(claimTimeout) > 0 ? claimTimeout : wait;
    }

    /**
     * Publishes one row, then marks it published.
     *
     * <p>The mark is written after the send returns, so no row is marked for a message the broker
     * did not acknowledge. Both writes happen inside the sweep's transaction, which holds the row
     * lock until it commits.
     *
     * <p>A send that returns while the mark fails leaves the row unpublished, and the next sweep
     * sends it again. Each consumer's processed-event table absorbs that duplicate.
     *
     * @param row   the unpublished row
     * @param topic the topic its event type travels on
     */
    private void publishAndMark(OutboxEventEntity row, String topic) {
        publisher.publish(topic, row.getAggregateId(), row.getPayload());
        row.markPublished(Instant.now());
        outboxEvents.save(row);
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

    /**
     * What one sweep committed, carried outside the transaction for recording.
     *
     * @param published rows the broker accepted and this sweep marked
     * @param failed    rows this sweep left unpublished
     */
    private record SweepResult(int published, int failed) {

        /**
         * Counts each failure this sweep left behind and logs the published total.
         *
         * @param publishFailures the publish stage of the failures counter
         */
        void record(Counter publishFailures) {
            if (published > 0) {
                log.debug("Published {} authorization outbox rows", published);
            }
            for (int failure = 0; failure < failed; failure++) {
                publishFailures.increment();
            }
        }
    }
}
