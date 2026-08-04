package com.carddemo.ledger.outbox;

import com.carddemo.events.TransactionDeclined;
import com.carddemo.events.TransactionPosted;
import com.carddemo.ledger.entity.OutboxEventEntity;
import com.carddemo.ledger.repository.OutboxEventRepository;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Limit;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

/**
 * Publishes the unpublished rows of {@code outbox_event} on a fixed delay, then marks each one
 * sent.
 *
 * <p>ADDITIVE. The one ancestor construct is the Customer Information Control System (CICS)
 * transient data queue write at {@code app/cbl/CORPT00C.cbl:L517-L518}, whose record a separate job
 * picks up later. That write is the single asynchronous handoff of the CardDemo source. The
 * {@code outbox_event} row this relay reads is ADDITIVE, and so is the processed-event marker each
 * consumer writes.
 *
 * <p>{@code outbox/OutboxWriter} stores those rows in its caller's transaction. The relay opens a
 * transaction of its own and publishes through the {@link KafkaTemplate} that
 * {@code config/KafkaProducerConfig} supplies. Every message is keyed on the eleven-digit account
 * identifier of {@code XREF-ACCT-ID PIC 9(11)} at {@code app/cpy/CVACT03Y.cpy:L7}, as text, so a
 * leading zero survives.
 *
 * <p>Rationale: {@code card-platform/docs/decision-log.md} (planned).
 */
@Component
public class OutboxRelay {

    /** Diagnostic output of this class. */
    private static final Logger log = LoggerFactory.getLogger(OutboxRelay.class);

    /** Holds the rows to publish, and records each publication. */
    private final OutboxEventRepository outboxEvents;

    /** Sends one event record to one topic, keyed on the account identifier. */
    private final KafkaTemplate<String, Object> ledgerEventTemplate;

    /** Reads one stored payload back into the record it was written from. */
    private final JsonMapper jsonMapper;

    /** Rows one sweep claims, from {@code carddemo.outbox.relay.batch-size}. */
    private final int batchSize;

    /** Each event type this service publishes, mapped to its destination. */
    private final Map<String, Destination> destinations;

    /** Stamps {@code published_at}, in Coordinated Universal Time. */
    private final Clock clock = Clock.systemUTC();

    /**
     * Takes the row store, the producer template, the payload reader and the configured names.
     *
     * @param outboxEvents             store of unpublished events
     * @param ledgerEventKafkaTemplate the template {@code config/KafkaProducerConfig} declares
     * @param jsonMapper               the framework-supplied mapper that reads a stored payload
     * @param batchSize                rows one sweep claims, from
     *                                 {@code carddemo.outbox.relay.batch-size}
     * @param transactionPostedTopic   topic of the posted event, from
     *                                 {@code carddemo.kafka.topics.transaction-posted}
     * @param transactionDeclinedTopic topic of the declined event, from
     *                                 {@code carddemo.kafka.topics.transaction-declined}
     * @throws NullPointerException if a collaborator or a topic name is {@code null}
     */
    public OutboxRelay(OutboxEventRepository outboxEvents,
            KafkaTemplate<String, Object> ledgerEventKafkaTemplate, JsonMapper jsonMapper,
            @Value("${carddemo.outbox.relay.batch-size}") int batchSize,
            @Value("${carddemo.kafka.topics.transaction-posted}")
            String transactionPostedTopic,
            @Value("${carddemo.kafka.topics.transaction-declined}")
            String transactionDeclinedTopic) {
        this.outboxEvents = Objects.requireNonNull(outboxEvents, "outboxEvents must be present");
        this.ledgerEventTemplate = Objects.requireNonNull(ledgerEventKafkaTemplate,
                "ledgerEventKafkaTemplate must be present");
        this.jsonMapper = Objects.requireNonNull(jsonMapper, "jsonMapper must be present");
        this.batchSize = batchSize;
        this.destinations = Map.of(
                TransactionPosted.EVENT_TYPE,
                new Destination(transactionPostedTopic, TransactionPosted.class),
                TransactionDeclined.EVENT_TYPE,
                new Destination(transactionDeclinedTopic, TransactionDeclined.class));
    }

    /**
     * Publishes every row of one claimed batch, oldest first, and marks each row the broker took.
     *
     * <p>The sweep runs in one transaction. {@link OutboxEventRepository#claimPendingBatch(Limit)}
     * takes a row lock on each row it returns, and a lock lives only as long as the transaction
     * that took it. The same claim reads unpublished rows alone, so a marked row is never
     * published a second time.
     *
     * <p>A failed publish leaves its row unpublished and ends the sweep, so a later event of one
     * account cannot overtake an earlier one. The next sweep claims that row again. A row whose
     * event type this service publishes to no topic stays unpublished as well.
     */
    @Scheduled(fixedDelayString = "${carddemo.outbox.relay.fixed-delay-ms}")
    @Transactional
    public void publishPendingEvents() {
        List<OutboxEventEntity> claimed = outboxEvents.claimPendingBatch(Limit.of(batchSize));
        int published = 0;

        for (OutboxEventEntity row : claimed) {
            Destination destination = destinations.get(row.getEventType());

            if (destination == null) {
                log.error("Outbox row {} carries the event type {}, which this service publishes to"
                        + " no topic, so the row stays unpublished", row.getEventId(),
                        row.getEventType());
                return;
            }
            try {
                publishAndMark(row, destination);
            } catch (RuntimeException failure) {
                log.warn("Outbox row {} did not reach topic {}, so it stays unpublished and the"
                        + " next sweep claims it again. Rows published ahead of it: {}."
                        + " Failure: {}", row.getEventId(), destination.topic(), published,
                        failure.getClass().getSimpleName());
                return;
            }
            published++;
        }
        if (published > 0) {
            log.debug("Published {} of the {} outbox rows this sweep claimed", published,
                    claimed.size());
        }
    }

    /**
     * Publishes one row, then marks it sent.
     *
     * <p>The stored payload reads back into the event record its {@code event_type} names, and the
     * serializer of {@code config/KafkaProducerConfig} writes that record to the topic. The mark
     * follows the broker acknowledgement, so no row is marked for a message the broker never took.
     *
     * @param row         the unpublished row
     * @param destination the topic its event type travels on, and the record its payload holds
     */
    private void publishAndMark(OutboxEventEntity row, Destination destination) {
        Object event = jsonMapper.readValue(row.getPayload(), destination.eventClass());

        ledgerEventTemplate.send(destination.topic(), row.getAggregateId(), event).join();
        row.markPublished(clock.instant());
        outboxEvents.save(row);
    }

    /**
     * Where one event type goes, and what its stored payload holds.
     *
     * @param topic      the topic that event type travels on
     * @param eventClass the record the payload reads back into
     */
    private record Destination(String topic, Class<?> eventClass) {

        Destination {
            Objects.requireNonNull(topic, "topic must be present");
            Objects.requireNonNull(eventClass, "eventClass must be present");
        }
    }
}
