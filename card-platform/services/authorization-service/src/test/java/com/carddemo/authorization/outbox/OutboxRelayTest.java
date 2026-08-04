package com.carddemo.authorization.outbox;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.carddemo.authorization.entity.OutboxEventEntity;
import com.carddemo.authorization.messaging.EventPublisherPort;
import com.carddemo.authorization.repository.OutboxEventRepository;
import com.carddemo.events.EventEnvelope;
import com.carddemo.events.TransactionAuthorized;
import com.carddemo.events.serde.EventContracts;
import com.fasterxml.jackson.annotation.JsonUnwrapped;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * Write and relay tests for {@link OutboxWriter} and {@link OutboxRelay}.
 *
 * <p>ADDITIVE in full. The source's one asynchronous handoff is the transient data queue write at
 * {@code app/cbl/CORPT00C.cbl:L515-L523}, where one program writes a record and a separate job reads
 * it later. These two classes play those two halves.
 *
 * <p>The properties under test are the ones the platform's guarantees rest on: one call writes one
 * row, a row that fails validation is rejected before it can be stored, the topic follows the event
 * type, a failed send leaves its row unpublished, and a sweep stops at the first failure so one
 * account's events keep their order.
 */
final class OutboxRelayTest {

    /** The topic the approval event travels on. */
    private static final String AUTHORIZED_TOPIC = "transaction.authorized";

    /** The topic the decline event travels on. */
    private static final String DECLINED_TOPIC = "transaction.declined";

    /** The account every row below is keyed on. */
    private static final String ACCOUNT_ID = "00000000077";

    private OutboxEventRepository outboxEvents;
    private EventPublisherPort publisher;
    private List<OutboxEventEntity> stored;
    private OutboxWriter writer;
    private OutboxRelay relay;

    /** Builds the writer and the relay over a stubbed repository and publisher. */
    @BeforeEach
    void buildWriterAndRelay() {
        outboxEvents = mock(OutboxEventRepository.class);
        publisher = mock(EventPublisherPort.class);
        stored = new ArrayList<>();

        when(outboxEvents.save(any(OutboxEventEntity.class))).thenAnswer(call -> {
            OutboxEventEntity row = call.getArgument(0);
            stored.removeIf(existing -> existing.getEventId().equals(row.getEventId()));
            stored.add(row);
            return row;
        });

        writer = new OutboxWriter(outboxEvents);
        relay = new OutboxRelay(outboxEvents, publisher, 100, AUTHORIZED_TOPIC, DECLINED_TOPIC);
    }

    /** Asserts one write stores one unpublished row carrying the envelope's own identifier. */
    @Test
    void oneWriteStoresOneUnpublishedRowUnderTheEventIdentifier() {
        TransactionAuthorized event = approvalEvent();

        OutboxEventEntity row = writer.write(event.envelope(), event);

        assertEquals(1, stored.size(), "one write stores one row");
        assertEquals(event.envelope().eventId(), row.getEventId(),
                "the row is keyed on the event identifier a consumer deduplicates on");
        assertEquals(TransactionAuthorized.EVENT_TYPE, row.getEventType(),
                "the row records the event type, which names both the document and the topic");
        assertEquals(ACCOUNT_ID, row.getAggregateId(),
                "the row records the message key, so the relay needs no second source for it");
        assertFalse(row.isPublished(), "a fresh row is unpublished");
    }

    /**
     * Asserts a payload that fails its schema document is rejected before the row is stored.
     *
     * <p>Rejecting at write time lets the caller's transaction roll back. Rejecting at publish time
     * would leave a decision committed with an event that can never leave.
     */
    @Test
    void aPayloadThatFailsItsDocumentIsRejectedBeforeStorage() {
        EventEnvelope envelope = EventEnvelope.of(TransactionAuthorized.EVENT_TYPE, ACCOUNT_ID);

        IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class,
                () -> writer.write(envelope, new UndeclaredEvent(envelope, "0000000000683580")),
                "a payload missing declared properties is not one the outbox stores");

        assertTrue(thrown.getMessage().contains(TransactionAuthorized.EVENT_TYPE),
                "the rejection names the event type whose document failed");
        assertEquals(List.of(), stored, "nothing is stored for a rejected payload");
    }

    /** Asserts an unregistered event type is rejected before the row is stored. */
    @Test
    void anUnregisteredEventTypeIsRejectedBeforeStorage() {
        EventEnvelope envelope = new EventEnvelope(UUID.randomUUID(), "TransactionReversed",
                EventEnvelope.SCHEMA_VERSION, Instant.now(), ACCOUNT_ID);

        assertThrows(IllegalArgumentException.class,
                () -> writer.write(envelope, new UndeclaredEvent(envelope, "0000000000683580")),
                "an event type no contract registers has no document and no topic");
        assertEquals(List.of(), stored, "nothing is stored for an unregistered event type");
    }

    /** Asserts the relay sends each pending row to the topic its event type belongs on. */
    @Test
    void theRelaySendsEachRowToTheTopicItsEventTypeBelongsOn() {
        OutboxEventEntity authorized = row(EventContracts.TRANSACTION_AUTHORIZED);
        OutboxEventEntity declined = row(EventContracts.TRANSACTION_DECLINED);
        when(outboxEvents.claimPendingBatch(any()))
                .thenReturn(List.of(authorized, declined));

        relay.publishPendingEvents();

        ArgumentCaptor<String> topics = ArgumentCaptor.forClass(String.class);
        verify(publisher, times(2)).publish(topics.capture(), anyString(),
                anyString());
        assertEquals(List.of(AUTHORIZED_TOPIC, DECLINED_TOPIC), topics.getAllValues(),
                "each event type reaches its own topic, in the order the rows were stored");
        assertTrue(authorized.isPublished(), "a sent row is marked");
        assertTrue(declined.isPublished(), "a sent row is marked");
    }

    /** Asserts the message key is the aggregate identifier the row recorded. */
    @Test
    void theMessageKeyIsTheAggregateIdentifierTheRowRecorded() {
        when(outboxEvents.claimPendingBatch(any()))
                .thenReturn(List.of(row(EventContracts.TRANSACTION_AUTHORIZED)));

        relay.publishPendingEvents();

        verify(publisher).publish(AUTHORIZED_TOPIC, ACCOUNT_ID, "{}");
    }

    /**
     * Asserts a failed send leaves its row unpublished and stops the sweep.
     *
     * <p>Continuing past a failure would let a later event of the same account reach the broker before
     * an earlier one, which reorders that account's stream.
     */
    @Test
    void aFailedSendLeavesItsRowUnpublishedAndStopsTheSweep() {
        OutboxEventEntity first = row(EventContracts.TRANSACTION_AUTHORIZED);
        OutboxEventEntity second = row(EventContracts.TRANSACTION_AUTHORIZED);
        when(outboxEvents.claimPendingBatch(any()))
                .thenReturn(List.of(first, second));
        doThrow(new IllegalStateException("the broker is unreachable")).when(publisher)
                .publish(anyString(), anyString(), anyString());

        relay.publishPendingEvents();

        assertFalse(first.isPublished(), "a row whose send failed stays unpublished");
        assertFalse(second.isPublished(), "the sweep stops, so the later row is not attempted");
        verify(publisher, times(1)).publish(anyString(), anyString(),
                anyString());
    }

    /** Asserts a row whose event type has no configured topic stays unpublished and is not sent. */
    @Test
    void aRowWithNoConfiguredTopicStaysUnpublished() {
        OutboxEventEntity foreign = row(EventContracts.TRANSACTION_POSTED);
        when(outboxEvents.claimPendingBatch(any()))
                .thenReturn(List.of(foreign));

        relay.publishPendingEvents();

        verify(publisher, never()).publish(anyString(), anyString(), anyString());
        assertFalse(foreign.isPublished(), "the row stays unpublished rather than reaching a topic");
    }

    /** Asserts a sweep finding no pending row sends nothing. */
    @Test
    void aSweepFindingNoPendingRowSendsNothing() {
        when(outboxEvents.claimPendingBatch(any())).thenReturn(List.of());

        relay.publishPendingEvents();

        verify(publisher, never()).publish(anyString(), anyString(), anyString());
    }

    /**
     * Proves the sweep runs in a transaction, which its locking claim cannot do without.
     *
     * <p>This is a declaration test rather than a behavioural one, and deliberately so: a mocked
     * repository answers a locking query happily, so no test on this level can observe the absence of
     * a transaction. A real datasource answers it with {@code TransactionRequiredException} on every
     * tick, the scheduler logs the failure, and the relay publishes nothing while appearing to run.
     * Asserting the annotation is what keeps that failure from returning silently.
     *
     * @throws NoSuchMethodException never, because the method asserted on is declared below
     */
    @Test
    void theSweepDeclaresATransactionItsRowLockRequires() throws NoSuchMethodException {
        assertTrue(OutboxRelay.class.getDeclaredMethod("publishPendingEvents")
                        .isAnnotationPresent(org.springframework.transaction.annotation.Transactional.class),
                "publishPendingEvents must be transactional: claimPendingBatch takes a pessimistic "
                        + "write lock, and a lock outside a transaction cannot be taken at all");
    }

    /**
     * Builds a valid approval event.
     *
     * @return the event
     */
    private static TransactionAuthorized approvalEvent() {
        return TransactionAuthorized.of(ACCOUNT_ID, "0000000000683580", "01", "0001", "POS TERM",
                "Purchase at Abshire-Lowe", new BigDecimal("504.77"), "800000000", "Abshire-Lowe",
                "North Enoshaven", "72112", "************7065", "2022-06-10 19:27:53.412000");
    }

    /**
     * Builds one unpublished row of a given event type.
     *
     * <p>The payload is an empty object, because the relay forwards a payload without reading it and
     * the publisher this test uses is stubbed.
     *
     * @param eventType the event type the row records
     * @return the row
     */
    private static OutboxEventEntity row(String eventType) {
        return new OutboxEventEntity(UUID.randomUUID(), eventType, ACCOUNT_ID, "{}", Instant.now());
    }

    /**
     * A payload declaring an envelope and one field, used to prove the writer validates.
     *
     * @param envelope      the five envelope fields, written flat
     * @param transactionId the one payload field
     */
    private record UndeclaredEvent(@JsonUnwrapped EventEnvelope envelope, String transactionId) {
    }
}
