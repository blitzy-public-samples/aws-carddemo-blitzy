package com.carddemo.fraud.outbox;

import com.carddemo.events.EventEnvelope;
import com.carddemo.events.FraudCleared;
import com.carddemo.events.FraudFlagged;
import com.carddemo.events.correlation.EventCorrelation;
import com.carddemo.fraud.entity.OutboxEventEntity;
import com.carddemo.fraud.repository.OutboxEventRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.Objects;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * Inserts one row of {@code outbox_event} for one fraud assessment event, inside the transaction
 * its caller already opened. Nothing here publishes.
 *
 * <p>No COBOL ancestor. The outbox pattern's one ancestor construct is
 * the Customer Information Control System (CICS) transient data queue write at
 * {@code app/cbl/CORPT00C.cbl:L517-L523}, in paragraph {@code WIRTE-JOBSUB-TDQ}. There one program
 * writes a Job Control Language (JCL) record and a separate job picks that record up. Shape only,
 * no logic.
 *
 * <p>The assessment row, the processed-event marker and this row reach the database in one local
 * transaction. The listener in the sibling {@code messaging} package opens that transaction and
 * calls {@link #write(Object)}. {@code OutboxRelay} reads the rows written here.
 *
 * <p>Each payload is one flat JavaScript Object Notation (JSON) object. The five envelope
 * properties sit at the top level beside the payload properties, so a written {@link FraudFlagged}
 * holds ten properties and a written {@link FraudCleared} holds eight. A nested {@code envelope}
 * property fails both schema documents, which close their property sets.
 */
@Component
public class OutboxWriter {

    /**
     * Stores the rows {@code OutboxRelay} later reads.
     *
     * <p>The Jakarta Persistence API (JPA) provider flushes the saved row with the rest of the
     * caller's unit of work.
     */
    private final OutboxEventRepository outboxEvents;

    /** Writes one event record to text. Jackson 3, matching the platform. */
    private final ObjectMapper objectMapper;

    /** Supplies the creation instant of each stored row. */
    private final Clock clock;

    /**
     * Takes the store this writer saves through and builds the one mapper it writes payloads with.
     *
     * @param outboxEvents store of unpublished events
     * @throws NullPointerException if {@code outboxEvents} is null
     */
    @Autowired
    public OutboxWriter(OutboxEventRepository outboxEvents) {
        this(outboxEvents, Clock.systemUTC());
    }

    /**
     * Takes the store and the clock used to stamp each row.
     *
     * @param outboxEvents store of unpublished events
     * @param clock        source of row creation instants
     */
    OutboxWriter(OutboxEventRepository outboxEvents, Clock clock) {
        this.outboxEvents = Objects.requireNonNull(outboxEvents, "outboxEvents must be present");
        this.objectMapper = eventMapper();
        this.clock = Objects.requireNonNull(clock, "clock must be present");
    }

    /**
     * Writes one event as an unpublished outbox row.
     *
     * <p>The row takes its identifier from the event, so the primary key of the row is the
     * identifier every consumer deduplicates on. The row takes {@code aggregate_id} from the
     * envelope, and that value is the message key the relay publishes under. {@code created_at}
     * records this insert. It is neither the envelope's {@code occurredAt} nor the event's
     * {@code assessedAt}.
     *
     * <p>{@link Propagation#MANDATORY} joins the caller's transaction and opens none. A call made
     * with no transaction open throws, so the row cannot commit apart from the assessment it
     * belongs to. Rolling the caller's transaction back leaves no row.
     *
     * <p>The row stores the serialized text unchanged. Writing the same event twice fails on the
     * primary key and produces no second message.
     *
     * @param event one {@link FraudFlagged} or one {@link FraudCleared}
     * @return the row saved, unpublished, carrying the event identifier the relay publishes under
     * @throws IllegalArgumentException if {@code event} is null, if its class is neither event type
     *                                  this service publishes, or if its {@code eventType} does not
     *                                  fit the {@code event_type} column
     * @throws org.springframework.transaction.IllegalTransactionStateException if the caller has no
     *                                  transaction open
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public OutboxEventEntity write(Object event) {
        EventEnvelope envelope = publishableEnvelope(event);
        String payload = objectMapper.writeValueAsString(event);

        return outboxEvents.save(correlated(new OutboxEventEntity(envelope.eventId(),
                envelope.eventType(), envelope.aggregateId(), payload, Instant.now(clock))));
    }

    /**
     * Stamps one row with the two correlation identifiers the writing thread is working under.
     *
     * <p>ADDITIVE. The values come from the ambient scope
     * {@code config/CorrelationContextFilter} or the listener opened, rather than from a parameter,
     * so no domain method between that scope and this writer carries an identifier it does not
     * otherwise use.
     *
     * <p>A row written outside any scope starts its own trace: it adopts its own event identifier
     * as the correlation identifier, so every published record carries one and a reader can always
     * join a record to what followed it. Causation stays absent on such a row, because nothing
     * caused it, and an absent causation contributes no record header when
     * {@code outbox/OutboxRelay} publishes the row.
     *
     * @param row the row about to be saved
     * @return the same row, stamped
     */
    private static OutboxEventEntity correlated(OutboxEventEntity row) {
        row.recordCorrelation(
                EventCorrelation.currentCorrelationId().orElseGet(row::getEventId),
                EventCorrelation.currentEventId().orElse(null));
        return row;
    }

    /**
     * Reads the envelope off one event and checks the type that envelope names.
     *
     * <p>The pattern match accepts the two records this service publishes and refuses every other
     * class. Each record carries the five envelope values inline and returns them through its own
     * {@code envelope()} accessor, so the row and the payload cannot disagree. The two checks that
     * follow hold {@code event_type} to {@link FraudFlagged#EVENT_TYPE} or
     * {@link FraudCleared#EVENT_TYPE}, and to the width of its column.
     *
     * <p>No message here names an account identifier, a transaction identifier or a payload. A
     * message about the type reports the two accepted names, and a message about the width reports
     * a character count.
     *
     * @param event the event to read
     * @return the envelope the event carries
     * @throws IllegalArgumentException if {@code event} is null, if its class is neither event type
     *                                  this service publishes, or if its {@code eventType} is wider
     *                                  than the {@code event_type} column
     */
    private static EventEnvelope publishableEnvelope(Object event) {
        if (event == null) {
            throw new IllegalArgumentException("event must be present");
        }

        EventEnvelope envelope = switch (event) {
            case FraudFlagged flagged -> flagged.envelope();
            case FraudCleared cleared -> cleared.envelope();
            default -> throw new IllegalArgumentException("this service publishes "
                    + FraudFlagged.EVENT_TYPE + " and " + FraudCleared.EVENT_TYPE + ", and "
                    + event.getClass().getName() + " is neither");
        };
        String eventType = envelope.eventType();

        boolean namesAPublishedType = FraudFlagged.EVENT_TYPE.equals(eventType)
                || FraudCleared.EVENT_TYPE.equals(eventType);
        if (!namesAPublishedType) {
            throw new IllegalArgumentException("/eventType names a type this service does not "
                    + "publish, and " + FraudFlagged.EVENT_TYPE + " and " + FraudCleared.EVENT_TYPE
                    + " are the two it does");
        }
        if (eventType.length() > OutboxEventEntity.EVENT_TYPE_MAX_LENGTH) {
            throw new IllegalArgumentException("/eventType holds " + eventType.length()
                    + " characters, over the " + OutboxEventEntity.EVENT_TYPE_MAX_LENGTH
                    + " the event_type column holds");
        }
        return envelope;
    }

    /**
     * The one mapper this writer holds.
     *
     * <p>The mapper writes an event record flat, so no {@code envelope} property reaches a row.
     * {@code FAIL_ON_UNKNOWN_PROPERTIES} governs reading rather than writing, so it is not what
     * closes the written document: the {@code additionalProperties} of {@code false} in both fraud
     * schema documents is, and {@code com.carddemo.events.serde.EventContracts} applies it when this
     * writer validates the payload before saving.
     *
     * <p>No setting quotes an ordinary number, so {@code schemaVersion} and {@code riskScore} reach
     * a row as JSON integers. No setting writes a date as a number, so {@code occurredAt} and
     * {@code assessedAt} reach a row as ISO-8601 text.
     *
     * @return the mapper, built once for each instance of this class
     */
    private static ObjectMapper eventMapper() {
        return JsonMapper.builder()
                .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                .build();
    }
}
