package com.carddemo.authorization.outbox;

import com.carddemo.authorization.config.AuthorizationProperties;
import com.carddemo.authorization.config.ObservabilityConfig;
import com.carddemo.authorization.entity.OutboxEventEntity;
import com.carddemo.authorization.entity.OutboxEventEntity.RelayState;
import com.carddemo.authorization.messaging.EventPublisherPort;
import com.carddemo.authorization.repository.OutboxEventRepository;
import com.carddemo.events.serde.EventContracts;
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
 * Publishes the rows {@link OutboxWriter} stored, in a transaction of its own.
 *
 * <p>This class plays the pick-up half of the one asynchronous handoff the source has. At
 * {@code app/cbl/CORPT00C.cbl:L515-L523} one program writes a record to a transient data queue and a
 * separate job reads it later, so the write and the send are two units of work there as they are
 * here.
 *
 * <p>Request handling publishes nothing. The decision and its outbox row commit first, and this
 * class sends afterwards, so a broker that is unreachable delays an event and never fails a decision.
 *
 * <p>Each sweep runs in one transaction. It recovers stranded claims, locks a due batch with
 * {@code SKIP LOCKED}, records this instance on each row, then publishes and marks in order. A send
 * failure records a bounded retry and ends the sweep. Every consumer records the event identifier,
 * so a repeat after a send succeeds and the database commit fails is harmless.
 *
 * <p>The topic follows the event type. {@link EventContracts} pairs each registered type with its
 * topic, and a deployment renames a topic through the two properties this class reads. A row whose
 * type has no configured topic stays unpublished rather than reaching a topic no consumer reads.
 */
@Component
public class OutboxRelay {

    private static final Logger log = LoggerFactory.getLogger(OutboxRelay.class);

    /** Reason recorded on a row whose claiming instance died before it finished. */
    private static final String CLAIM_EXPIRED = "ClaimExpired";

    /** Reason recorded on a row whose event type has no authorization destination. */
    private static final String UNKNOWN_EVENT_TYPE = "UnknownEventType";

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
     * Counts one publish fault, which is the only stage of the failure counter this class records.
     *
     * <p>This relay is the only component of the service that publishes, so it is the only place the
     * publish stage can be recorded truthfully. Recording it anywhere in request handling would name
     * a stage that code never reaches.
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
                EventContracts.TRANSACTION_AUTHORIZED,
                configuredTopics.transactionAuthorized(),
                EventContracts.TRANSACTION_DECLINED,
                configuredTopics.transactionDeclined());
        this.publishFailures = Counter.builder(ObservabilityConfig.FAILURES_COUNTER)
                .tag(ObservabilityConfig.STAGE_TAG, ObservabilityConfig.PUBLISH_STAGE)
                .register(Objects.requireNonNull(meters, "meters"));
    }

    /** Runs one claimed sweep and records its failures after the transaction commits. */
    @Scheduled(fixedDelayString = "${carddemo.outbox.relay.fixed-delay-ms:500}")
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
     * @return the number of failures the committed sweep recorded
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
        Duration doubled = sweepDelay;
        for (int step = 0; step < attemptsSoFar && doubled.compareTo(claimTimeout) < 0; step++) {
            doubled = doubled.multipliedBy(2L);
        }
        return doubled.compareTo(claimTimeout) > 0 ? claimTimeout : doubled;
    }

    /**
     * Publishes one row, then marks it published.
     *
     * <p>The mark is written only after the send returns, so a row is never marked for a message the
     * broker did not acknowledge. Both happen inside the sweep's transaction, which is the one that
     * holds the row lock: releasing it before the mark is written would let a second relay instance
     * claim a row this one has already sent.
     *
     * <p>A send that succeeds while the mark fails leaves the row unpublished, and the next sweep
     * sends it again. That is the duplicate every consumer's processed-event table absorbs, and it is
     * why at-least-once is the guarantee this relay offers.
     *
     * @param row   the unpublished row
     * @param topic the topic its event type travels on
     */
    void publishAndMark(OutboxEventEntity row, String topic) {
        publisher.publish(topic, row.getAggregateId(), row.getPayload());
        row.markPublished(Instant.now());
        outboxEvents.save(row);
    }

    /** Returns the deepest cause of one failure. */
    private static Throwable rootCause(Throwable failure) {
        Throwable cause = failure;
        while (cause.getCause() != null && cause.getCause() != cause) {
            cause = cause.getCause();
        }
        return cause;
    }

    /** What one sweep committed, carried outside the transaction for recording. */
    private record SweepResult(int published, int failed) {

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
