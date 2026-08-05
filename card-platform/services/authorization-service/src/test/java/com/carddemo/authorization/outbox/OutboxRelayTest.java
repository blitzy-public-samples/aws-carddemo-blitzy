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

import com.carddemo.authorization.config.AuthorizationProperties;
import com.carddemo.authorization.entity.OutboxEventEntity;
import com.carddemo.authorization.messaging.EventPublisherPort;
import com.carddemo.authorization.repository.OutboxEventRepository;
import com.carddemo.cobol.PanMasker;
import com.carddemo.events.DeclineReason;
import com.carddemo.events.EventEnvelope;
import com.carddemo.events.TransactionAuthorized;
import com.carddemo.events.TransactionDeclined;
import com.carddemo.events.serde.EventContracts;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Write and relay tests for {@link OutboxWriter} and {@link OutboxRelay}.
 *
 * <p>The source's one asynchronous handoff is the transient data queue write at
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

    /**
     * A sixteen-digit card number this repository does not carry.
     *
     * <p>The four leading digits are {@code 9999}, and none of the fifty records of
     * {@code app/data/ASCII/carddata.txt} begins with them. The value is derived without a
     * committed literal, so no card number this repository holds reaches this source file.
     */
    private static final String FIXTURE_CARD_NUMBER = syntheticCardNumber(452612877065L);

    /**
     * Builds a sixteen-digit card number this repository does not carry.
     *
     * @param serial the trailing serial, at most twelve digits
     * @return sixteen digits, opening with {@code 9999}
     */
    private static String syntheticCardNumber(long serial) {
        return "9999" + String.format("%012d", serial);
    }

    /** Reads a stored payload back. Jackson 3, as the writer writes it. */
    private static final JsonMapper MAPPER = JsonMapper.builder().build();

    private OutboxEventRepository outboxEvents;
    private EventPublisherPort publisher;
    private List<OutboxEventEntity> stored;
    private OutboxWriter writer;
    private SimpleMeterRegistry meters;
    private TransactionTemplate transactionTemplate;

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
        meters = new SimpleMeterRegistry();
        transactionTemplate = mock(TransactionTemplate.class);
        when(transactionTemplate.execute(any())).thenAnswer(call -> {
            TransactionCallback<?> callback = call.getArgument(0);
            return callback.doInTransaction(mock(org.springframework.transaction.TransactionStatus.class));
        });
        when(outboxEvents.findByRelayStateAndClaimedAtBeforeOrderByClaimedAtAsc(
                any(), any(), any())).thenReturn(List.of());
        relay = new OutboxRelay(outboxEvents, publisher, meters, transactionTemplate, properties());
    }

    /** Asserts one write stores one unpublished row carrying the envelope's own identifier. */
    @Test
    void oneWriteStoresOneUnpublishedRowUnderTheEventIdentifier() {
        TransactionAuthorized event = approvalEvent();

        OutboxEventEntity row = writer.writeAuthorized(event);

        assertEquals(1, stored.size(), "one write stores one row");
        assertEquals(event.envelope().eventId(), row.getEventId(),
                "the row is keyed on the event identifier a consumer deduplicates on");
        assertEquals(TransactionAuthorized.EVENT_TYPE, row.getEventType(),
                "the row records the event type, which names both the document and the topic");
        assertEquals(ACCOUNT_ID, row.getAggregateId(),
                "the row records the message key, so the relay needs no second source for it");
        assertFalse(row.isPublished(), "a fresh row is unpublished");
    }

    /** Asserts one decline write stores one unpublished row keyed on the account. */
    @Test
    void oneDeclineWriteStoresOneUnpublishedRowKeyedOnTheAccount() {
        TransactionDeclined event = declineEvent();

        OutboxEventEntity row = writer.writeDeclined(event);

        assertEquals(1, stored.size(), "one write stores one row");
        assertEquals(TransactionDeclined.EVENT_TYPE, row.getEventType(),
                "the row records the event type, which names both the document and the topic");
        assertEquals(ACCOUNT_ID, row.getAggregateId(), "the row records the message key");
        assertTrue(row.getPayload().contains(DeclineReason.OVER_CREDIT_LIMIT.code()),
                "the payload carries the reject code as its four-character text");
    }

    /** Asserts reject reason 0100 is stored under the transaction key of its version-two event. */
    @Test
    void aDeclineThatResolvedNoAccountIsStoredUnderItsTransactionKey() {
        String transactionId = "0000000000683580";
        TransactionDeclined event = TransactionDeclined.ofUnresolvedAccount(transactionId,
                new BigDecimal("504.77"), "************7065");

        OutboxEventEntity row = writer.writeDeclined(event);

        assertEquals(1, stored.size(), "one authorization call stores one event");
        assertEquals(transactionId, row.getAggregateId(),
                "the transaction identifier is the message key when no account exists");
        assertTrue(row.getPayload().contains("\"schemaVersion\":2"),
                "the payload names the unresolved-account contract");
        assertFalse(row.getPayload().contains("\"accountId\""),
                "the unresolved-account contract carries no invented account identifier");
    }

    /**
     * Asserts each payload is one flat object carrying the envelope beside the payload properties.
     *
     * <p>{@code transaction-authorized-v1.json} names twenty required properties and
     * {@code transaction-declined-v1.json} names eleven. A payload nested under an envelope key
     * would fail both documents on the {@code required} array and never reach a topic.
     */
    @Test
    void eachPayloadIsOneFlatObjectOfTheCountItsDocumentRequires() {
        JsonNode approval = MAPPER.readTree(writer.writeAuthorized(approvalEvent()).getPayload());
        stored.clear();
        JsonNode decline = MAPPER.readTree(writer.writeDeclined(declineEvent()).getPayload());

        assertEquals(20, approval.size(), "fifteen payload properties beside five envelope ones");
        assertEquals(11, decline.size(), "six payload properties beside five envelope ones");
        assertTrue(approval.path("envelope").isMissingNode(), "no nested envelope object");
        assertTrue(decline.path("envelope").isMissingNode(), "no nested envelope object");
        assertEquals(ACCOUNT_ID, approval.path("aggregateId").asString(), "the message key");
        assertEquals(ACCOUNT_ID, approval.path("accountId").asString(), "one account, two names");
    }

    /**
     * Asserts money travels as a decimal string truncated toward zero, on both signs.
     *
     * <p>The {@code ROUNDED} phrase appears zero times across the twenty-eight programs of
     * {@code app/cbl/}, so every arithmetic store truncates. Half-up rounding would carry
     * {@code 504.779} to {@code 504.78}, and the floor of a negative amount would carry
     * {@code -504.779} to {@code -504.78}.
     */
    @Test
    void moneyTravelsAsADecimalStringTruncatedTowardZero() {
        JsonNode positive = MAPPER.readTree(
                writer.writeAuthorized(approvalEvent(new BigDecimal("504.779"))).getPayload());
        stored.clear();
        JsonNode negative = MAPPER.readTree(
                writer.writeAuthorized(approvalEvent(new BigDecimal("-504.779"))).getPayload());

        assertTrue(positive.path("amount").isString(), "money is a string, never a JSON number");
        assertEquals("504.77", positive.path("amount").asString(), "truncated toward zero");
        assertEquals("-504.77", negative.path("amount").asString(), "toward zero on a refund too");
    }

    /** Asserts the envelope timestamp travels as text and the card number travels masked. */
    @Test
    void theEnvelopeTimestampTravelsAsTextAndTheCardNumberTravelsMasked() {
        String payload = writer.writeAuthorized(approvalEvent()).getPayload();
        JsonNode event = MAPPER.readTree(payload);

        assertTrue(event.path("occurredAt").isString(), "an epoch number would fail the document");
        assertTrue(event.path("occurredAt").asString().endsWith("Z"), "Coordinated Universal Time");
        assertTrue(event.path("schemaVersion").isNumber(), "the version stays an integer");
        assertEquals("************7065", event.path("maskedCardNumber").asString(),
                "only the last four digits travel");
        assertFalse(payload.contains(FIXTURE_CARD_NUMBER), "no full card number travels");
    }

    /**
     * Asserts both write operations join the transaction their caller opened.
     *
     * <p>{@link org.springframework.transaction.annotation.Propagation#REQUIRES_NEW} would open a
     * second transaction, and the row would then commit apart from the decision it describes.
     */
    @Test
    void bothWriteOperationsJoinTheCallersTransaction() throws NoSuchMethodException {
        Transactional approval = OutboxWriter.class
                .getMethod("writeAuthorized", TransactionAuthorized.class)
                .getAnnotation(Transactional.class);
        Transactional decline = OutboxWriter.class
                .getMethod("writeDeclined", TransactionDeclined.class)
                .getAnnotation(Transactional.class);

        for (Transactional annotation : List.of(approval, decline)) {
            assertEquals(Propagation.REQUIRED, annotation.propagation(), "the caller's transaction");
            assertFalse(annotation.readOnly(), "the operation writes a row");
        }
    }

    /** Asserts an absent event is refused by both write operations before anything is stored. */
    @Test
    void anAbsentEventIsRefusedBeforeStorage() {
        assertThrows(NullPointerException.class, () -> writer.writeAuthorized(null),
                "an approval write takes one event");
        assertThrows(NullPointerException.class, () -> writer.writeDeclined(null),
                "a decline write takes one event");
        assertEquals(List.of(), stored, "nothing is stored for an absent event");
    }

    /** Asserts the relay sends each pending row to the topic its event type belongs on. */
    @Test
    void theRelaySendsEachRowToTheTopicItsEventTypeBelongsOn() {
        OutboxEventEntity authorized = row(EventContracts.TRANSACTION_AUTHORIZED);
        OutboxEventEntity declined = row(EventContracts.TRANSACTION_DECLINED);
        when(outboxEvents.claimDueRows(any(), any()))
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
        when(outboxEvents.claimDueRows(any(), any()))
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
        when(outboxEvents.claimDueRows(any(), any()))
                .thenReturn(List.of(first, second));
        doThrow(new IllegalStateException("the broker is unreachable")).when(publisher)
                .publish(anyString(), anyString(), anyString());

        relay.publishPendingEvents();

        assertFalse(first.isPublished(), "a row whose send failed stays unpublished");
        assertEquals(OutboxEventEntity.RelayState.PENDING, first.getRelayState(),
                "a refused row releases its claim for a later attempt");
        assertEquals(1, first.getAttemptCount(), "the refused attempt is recorded once");
        assertFalse(second.isPublished(), "the sweep stops, so the later row is not attempted");
        verify(publisher, times(1)).publish(anyString(), anyString(),
                anyString());
    }

    /** Asserts a row whose event type has no configured topic stays unpublished and is not sent. */
    @Test
    void aRowWithNoConfiguredTopicStaysUnpublished() {
        OutboxEventEntity foreign = row(EventContracts.TRANSACTION_POSTED);
        when(outboxEvents.claimDueRows(any(), any()))
                .thenReturn(List.of(foreign));

        relay.publishPendingEvents();

        verify(publisher, never()).publish(anyString(), anyString(), anyString());
        assertFalse(foreign.isPublished(), "the row stays unpublished rather than reaching a topic");
        assertEquals(1, foreign.getAttemptCount(), "the unpublishable row records one attempt");
    }

    /** Asserts a sweep finding no pending row sends nothing. */
    @Test
    void aSweepFindingNoPendingRowSendsNothing() {
        when(outboxEvents.claimDueRows(any(), any())).thenReturn(List.of());

        relay.publishPendingEvents();

        verify(publisher, never()).publish(anyString(), anyString(), anyString());
    }

    /** Asserts a claim left by a stopped instance is recovered before the row is published. */
    @Test
    void aStrandedClaimIsRecoveredBeforePublication() {
        OutboxEventEntity stranded = row(EventContracts.TRANSACTION_AUTHORIZED);
        stranded.claim("stopped-instance", Instant.now().minus(Duration.ofMinutes(3L)));
        when(outboxEvents.findByRelayStateAndClaimedAtBeforeOrderByClaimedAtAsc(
                any(), any(), any())).thenReturn(List.of(stranded));
        when(outboxEvents.claimDueRows(any(), any())).thenReturn(List.of(stranded));

        relay.publishPendingEvents();

        assertTrue(stranded.isPublished(), "the recovered row reaches its configured topic");
        assertEquals(1, stranded.getAttemptCount(), "recovering the expired claim records an attempt");
        verify(publisher).publish(AUTHORIZED_TOPIC, ACCOUNT_ID, "{}");
    }

    /** Proves the scheduled method opens an explicit transaction for the locking claim. */
    @Test
    void theSweepOpensItsTransactionExplicitly() throws NoSuchMethodException {
        assertFalse(OutboxRelay.class.getDeclaredMethod("publishPendingEvents")
                        .isAnnotationPresent(Transactional.class),
                "the explicit boundary keeps meter writes outside the database transaction");

        relay.publishPendingEvents();

        verify(transactionTemplate).execute(any());
    }

    /**
     * Builds a valid approval event.
     *
     * @return the event
     */
    private static TransactionAuthorized approvalEvent() {
        return approvalEvent(new BigDecimal("504.77"));
    }

    /**
     * Builds a valid approval event carrying one amount.
     *
     * @param amount the amount the event carries, at any scale
     * @return the event
     */
    private static TransactionAuthorized approvalEvent(BigDecimal amount) {
        return TransactionAuthorized.of(ACCOUNT_ID, "0000000000683580", "01", "0001", "POS TERM",
                "Purchase at Abshire-Lowe", amount, "800000000", "Abshire-Lowe",
                "North Enoshaven", "72112", PanMasker.maskCardNumber(FIXTURE_CARD_NUMBER),
                PanMasker.cardToken(FIXTURE_CARD_NUMBER), "2022-06-10 19:27:53.412000");
    }

    /**
     * Builds a valid decline event, carrying reject code {@code 0102}.
     *
     * @return the event
     */
    private static TransactionDeclined declineEvent() {
        return TransactionDeclined.of(ACCOUNT_ID, "0000000000683580",
                DeclineReason.OVER_CREDIT_LIMIT, new BigDecimal("504.77"),
                PanMasker.maskCardNumber(FIXTURE_CARD_NUMBER));
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

    /** Builds the typed settings the relay reads. */
    private static AuthorizationProperties properties() {
        return new AuthorizationProperties(
                new AuthorizationProperties.Kafka(
                        new AuthorizationProperties.Kafka.Topics(
                                AUTHORIZED_TOPIC,
                                DECLINED_TOPIC,
                                "account.state-changed",
                                "card.updated",
                                "carddemo.dead-letter"),
                        new AuthorizationProperties.Kafka.Groups(
                                "authorization-account-state",
                                "authorization-card-updated")),
                new AuthorizationProperties.Outbox(
                        new AuthorizationProperties.Outbox.Relay(
                                500L,
                                100,
                                "authorization-relay",
                                Duration.ofMinutes(2L)),
                        168L),
                new AuthorizationProperties.ProcessedEvent(168L),
                new AuthorizationProperties.Retention(3_600_000L, 365L),
                new AuthorizationProperties.Replica(Duration.ofDays(1L)));
    }
}
