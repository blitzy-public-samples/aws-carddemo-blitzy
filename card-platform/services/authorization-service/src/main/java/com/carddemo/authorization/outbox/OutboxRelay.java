package com.carddemo.authorization.outbox;

import com.carddemo.authorization.entity.OutboxEventEntity;
import com.carddemo.authorization.messaging.EventPublisherPort;
import com.carddemo.authorization.repository.OutboxEventRepository;
import com.carddemo.events.serde.EventContracts;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Limit;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Publishes the rows {@link OutboxWriter} stored, in a transaction of its own.
 *
 * <p>ADDITIVE. This class plays the pick-up half of the one asynchronous handoff the source has. At
 * {@code app/cbl/CORPT00C.cbl:L515-L523} one program writes a record to a transient data queue and a
 * separate job reads it later, so the write and the send are two units of work there as they are
 * here.
 *
 * <p>Request handling publishes nothing. The decision and its outbox row commit first, and this
 * class sends afterwards, so a broker that is unreachable delays an event and never fails a decision.
 *
 * <p>Each row is published then marked, one row at a time, each in its own transaction. A row whose
 * send fails stays unpublished and the next sweep tries it again, so at-least-once delivery is the
 * guarantee. Every consumer records the event identifier before it applies side effects, which is
 * what makes a repeat harmless.
 *
 * <p>The topic follows the event type. {@link EventContracts} pairs each registered type with its
 * topic, and a deployment renames a topic through the two properties this class reads. A row whose
 * type has no configured topic stays unpublished rather than reaching a topic no consumer reads.
 */
@Component
public class OutboxRelay {

    private static final Logger log = LoggerFactory.getLogger(OutboxRelay.class);

    /** Reads unpublished rows and stores the published flag. */
    private final OutboxEventRepository outboxEvents;

    /** Sends one payload to one topic. */
    private final EventPublisherPort publisher;

    /** Rows swept per tick, from {@code carddemo.outbox.relay.batch-size}. */
    private final int batchSize;

    /** Each registered event type this service publishes, mapped to its configured topic. */
    private final Map<String, String> topics;

    /**
     * Takes the store, the publisher and the configured topic names.
     *
     * @param outboxEvents        store of unpublished events
     * @param publisher           the event bus seam
     * @param batchSize           rows to sweep per tick
     * @param authorizedTopic     topic the approval event travels on
     * @param declinedTopic       topic the decline event travels on
     */
    public OutboxRelay(OutboxEventRepository outboxEvents, EventPublisherPort publisher,
            @Value("${carddemo.outbox.relay.batch-size:100}") int batchSize,
            @Value("${carddemo.kafka.topics.transaction-authorized}") String authorizedTopic,
            @Value("${carddemo.kafka.topics.transaction-declined}") String declinedTopic) {
        this.outboxEvents = outboxEvents;
        this.publisher = publisher;
        this.batchSize = batchSize;
        this.topics = Map.of(EventContracts.TRANSACTION_AUTHORIZED, authorizedTopic,
                EventContracts.TRANSACTION_DECLINED, declinedTopic);
    }

    /**
     * Publishes every unpublished row, oldest first, and marks each one it sends.
     *
     * <p>Oldest first keeps the order of one account's events, because the writer stamps rows in the
     * order the decisions committed and the publisher keys every message on the account identifier.
     *
     * <p>A failed send leaves its row unpublished and stops the sweep, so a later event of the same
     * account cannot overtake an earlier one that has not yet reached the broker. The next tick
     * resumes at the row that failed.
     *
     * <p>The sweep is one transaction, and it has to be.
     * {@link OutboxEventRepository#claimPendingBatch} takes a pessimistic write lock on every row it
     * returns, which is how two relay instances divide the work instead of publishing the same event
     * twice, and a lock lives only as long as the transaction that took it. Without a transaction here
     * the claim cannot be made at all: Jakarta Persistence answers a locking query outside one with
     * {@code TransactionRequiredException}, the scheduler logs it, and the sweep publishes nothing
     * while looking like it ran. The cost is a database connection held for the length of a broker
     * round trip, which is the price of the claim and is bounded by
     * {@code carddemo.outbox.relay.batch-size} rows per sweep.
     */
    @Scheduled(fixedDelayString = "${carddemo.outbox.relay.fixed-delay-ms:500}")
    @Transactional
    public void publishPendingEvents() {
        List<OutboxEventEntity> pending = outboxEvents.claimPendingBatch(Limit.of(batchSize));

        for (OutboxEventEntity row : pending) {
            String topic = topics.get(row.getEventType());
            if (topic == null) {
                log.error("Outbox row carries the event type {}, which this service has no "
                        + "configured topic for, so the row stays unpublished", row.getEventType());
                return;
            }
            try {
                publishAndMark(row, topic);
            } catch (RuntimeException failure) {
                log.warn("Publishing the outbox row for event type {} failed, so it stays "
                        + "unpublished and the next sweep retries it: {}", row.getEventType(),
                        failure.getClass().getSimpleName());
                return;
            }
        }
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
}
