package com.carddemo.authorization.outbox;

import com.carddemo.authorization.entity.OutboxEventEntity;
import com.carddemo.authorization.repository.OutboxEventRepository;
import com.carddemo.events.EventEnvelope;
import com.carddemo.events.serde.EventContracts;
import java.time.Clock;
import java.util.List;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * Writes one event into {@code outbox_event}, inside the transaction its caller already opened.
 *
 * <p>ADDITIVE. The source publishes nothing. Its one asynchronous handoff is the transient data queue
 * write at {@code app/cbl/CORPT00C.cbl:L515-L523}, where one program writes a record and a separate
 * job later picks it up. This class plays the write half of that handoff, and
 * {@link OutboxRelay} plays the pick-up half.
 *
 * <p>The row and the decision commit together. {@code app/cbl/CBTRN02C.cbl:L440-L442} performs three
 * writes unconditionally with no rollback, and every file definition at
 * {@code app/csd/CARDDEMO.CSD:L3-L9} carries {@code RECOVERY(NONE) JOURNAL(NO)}, so the source
 * offers no atomicity to reproduce. One local transaction is therefore an addition, recorded in
 * {@code card-platform/docs/decision-log.md} (planned).
 *
 * <p>This class opens no transaction of its own and starts no thread. A caller annotated
 * {@code @Transactional} calls it, and the row it saves is part of that caller's unit of work. A
 * caller that has no transaction open would write the row without the decision, which is the failure
 * the outbox exists to prevent.
 *
 * <p>Nothing here publishes. The relay reads the row afterwards, so request handling never waits for
 * a broker.
 */
@Component
public class OutboxWriter {

    /** Stores the rows the relay later reads. */
    private final OutboxEventRepository outboxEvents;

    /**
     * Supplies the moment each row records, in Coordinated Universal Time.
     *
     * <p>The relay reads rows in the order this field stamps them, so one zone governs every row.
     */
    private final Clock clock = Clock.systemUTC();

    /** Writes an event record to text. Jackson 3, matching the platform. */
    private final ObjectMapper objectMapper = JsonMapper.builder().build();

    /**
     * Takes the repository this writer saves through.
     *
     * @param outboxEvents store of unpublished events
     */
    public OutboxWriter(OutboxEventRepository outboxEvents) {
        this.outboxEvents = outboxEvents;
    }

    /**
     * Writes one event as an unpublished outbox row.
     *
     * <p>The row takes its identifier from the envelope, so the event identifier a consumer
     * deduplicates on is the primary key of this row. Writing the same event twice therefore fails on
     * the primary key rather than producing two messages.
     *
     * <p>The payload is validated against the schema document its event type names before the row is
     * written. A payload that fails is rejected here, while the transaction can still roll back,
     * rather than at publish time when the decision has already committed. The rejection message
     * holds JSON pointers and broken keywords only, so no card number and no account identifier
     * reaches a log through it.
     *
     * @param envelope the five fields the event carries, whose {@code eventType} must be registered
     * @param event    the event record, written flat with its envelope
     * @return the row saved, carrying the event identifier the relay publishes under
     * @throws IllegalArgumentException when the event type is not registered, or when the written
     *                                  payload fails the document that type names
     */
    public OutboxEventEntity write(EventEnvelope envelope, Object event) {
        String eventType = envelope.eventType();
        if (!EventContracts.isRegistered(eventType)) {
            throw new IllegalArgumentException("the event type '" + eventType
                    + "' has no contract, and " + EventContracts.eventTypes()
                    + " are the registered types");
        }

        String payload = objectMapper.writeValueAsString(event);
        List<String> violations = EventContracts.violationsOf(eventType, payload);
        if (!violations.isEmpty()) {
            throw new IllegalArgumentException(
                    EventContracts.describeViolations(eventType, violations));
        }

        return outboxEvents.save(new OutboxEventEntity(envelope.eventId(), eventType,
                envelope.aggregateId(), payload, clock.instant()));
    }
}
