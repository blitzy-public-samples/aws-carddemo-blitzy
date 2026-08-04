package com.carddemo.account.outbox;

import com.carddemo.account.entity.OutboxEventEntity;
import com.carddemo.account.messaging.AccountStateChanged;
import com.carddemo.account.repository.OutboxEventRepository;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

/**
 * Persists one {@code outbox_event} row inside the transaction its caller opened, and publishes
 * nothing.
 *
 * <p>ADDITIVE. The one ancestor construct is the Transient Data Queue write of the Customer
 * Information Control System (CICS) at {@code app/cbl/CORPT00C.cbl:L515-L523}. One program writes
 * the record there, and a separate job collects it later.
 *
 * <p>{@code app/cbl/COACTUPC.cbl} rewrites two files in one unit of work. The account rewrite at
 * {@code app/cbl/COACTUPC.cbl:L4066} fails with no rollback at
 * {@code app/cbl/COACTUPC.cbl:L4076-L4081}. The customer rewrite at
 * {@code app/cbl/COACTUPC.cbl:L4086} reaches {@code SYNCPOINT ROLLBACK} at
 * {@code app/cbl/COACTUPC.cbl:L4099-L4101}. All eight file definitions in
 * {@code app/csd/CARDDEMO.CSD} carry {@code RECOVERY(NONE)} and {@code JOURNAL(NO)}.
 * {@code card-platform/docs/business-rule-flags.md} records that asymmetry.
 *
 * <p>The account row, the customer row and this row commit together.
 * {@link #write(AccountStateChanged)} joins the transaction its caller opened, with
 * {@link Propagation#MANDATORY}, and opens none of its own.
 *
 * <p>{@code card-platform/docs/event-flow.md} draws the message path, and
 * {@code card-platform/docs/decision-log.md} carries the rationale.
 */
@Component
public class OutboxWriter {

    /**
     * Widest {@code payload} this writer stores, counted in octets.
     *
     * <p>The check constraint {@code ck_outbox_event_payload_bytes} in
     * {@code src/main/resources/db/migration/V1__schema.sql} holds the column to
     * {@value #PAYLOAD_MAX_BYTES} octets. PostgreSQL counts that column in UTF-8, and this writer
     * measures the same encoding.
     */
    public static final int PAYLOAD_MAX_BYTES = 8192;

    /** Stores the row this writer inserts, through the {@code save} it inherits. */
    private final OutboxEventRepository outboxEventRepository;

    /**
     * Writes one event to JavaScript Object Notation (JSON) text.
     *
     * <p>{@code config/KafkaProducerConfig} declares this bean under the name
     * {@code accountEventObjectMapper} and settles every serialization setting it carries. Each
     * monetary property of {@link AccountStateChanged} takes a quoted decimal form of its own, and
     * {@code schemaVersion} stays an unquoted integer.
     */
    private final ObjectMapper objectMapper;

    /**
     * Takes the repository this writer saves through and the mapper it serializes with.
     *
     * @param outboxEventRepository store of unpublished events
     * @param objectMapper          the module-local Jackson 3 mapper, bean
     *                              {@code accountEventObjectMapper}
     * @throws NullPointerException when either argument is {@code null}
     */
    public OutboxWriter(OutboxEventRepository outboxEventRepository,
            @Qualifier("accountEventObjectMapper") ObjectMapper objectMapper) {
        this.outboxEventRepository = Objects.requireNonNull(outboxEventRepository,
                "outboxEventRepository must be present");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper must be present");
    }

    /**
     * Writes one account state change as an unpublished {@code outbox_event} row.
     *
     * <p>The row carries the five
     * {@link com.carddemo.events.EventEnvelope EventEnvelope} values the event already holds. Its
     * primary key is the event identifier a consumer deduplicates on. A second write of one event
     * fails on that key. On insert {@code published} is {@code false} and {@code published_at} is
     * null.
     *
     * <p>{@link Propagation#MANDATORY} joins the transaction its caller opened and starts none. A
     * call with no transaction open fails, and a failure anywhere in that caller's unit of work
     * leaves no row.
     *
     * <p>The aggregate identifier is the account identifier: eleven digits held as text, which
     * keeps a leading zero. Its width comes from {@code XREF-ACCT-ID PIC 9(11)} at
     * {@code app/cpy/CVACT03Y.cpy:L7}.
     *
     * <p>The canonical constructor of {@link AccountStateChanged} has already checked all eleven
     * components, and the constructor of {@link OutboxEventEntity} checks every column value this
     * method supplies. No message thrown here holds a payload, an account identifier or a monetary
     * value.
     *
     * @param event the event to store
     * @throws NullPointerException                when {@code event} is {@code null}
     * @throws IllegalArgumentException            when the serialized event exceeds
     *                                             {@value #PAYLOAD_MAX_BYTES} octets
     * @throws tools.jackson.core.JacksonException when the event cannot be written as text
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void write(AccountStateChanged event) {
        Objects.requireNonNull(event, "event must be present");

        String payload = objectMapper.writeValueAsString(event);
        requirePayloadWithinCeiling(payload, event.eventId());

        outboxEventRepository.save(new OutboxEventEntity(event.eventId(), event.eventType(),
                payload, event.aggregateId(), event.occurredAt()));
    }

    /**
     * Checks one serialized event against {@link #PAYLOAD_MAX_BYTES} octets of UTF-8.
     *
     * <p>The check runs before the insert, and an oversized document fails with its measured length
     * named. The message carries the event identifier, which names no account and no person.
     *
     * @param payload the serialized event
     * @param eventId the identifier of the event the payload holds
     * @throws IllegalArgumentException when the payload exceeds {@value #PAYLOAD_MAX_BYTES} octets
     */
    private static void requirePayloadWithinCeiling(String payload, UUID eventId) {
        int octets = payload.getBytes(StandardCharsets.UTF_8).length;
        if (octets > PAYLOAD_MAX_BYTES) {
            throw new IllegalArgumentException("event " + eventId + " serializes to " + octets
                    + " octets and the payload column holds " + PAYLOAD_MAX_BYTES);
        }
    }
}
