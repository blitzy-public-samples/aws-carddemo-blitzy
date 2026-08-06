package com.carddemo.ledger.messaging;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.carddemo.events.DeclineReason;
import com.carddemo.events.TransactionAuthorized;
import com.carddemo.ledger.config.ObservabilityConfig;
import com.carddemo.ledger.config.ObservabilityConfig.LedgerMeters;
import com.carddemo.ledger.domain.AccountBalanceUpdater.AccountBalanceRowMissingException;
import com.carddemo.ledger.domain.PostingService;
import com.carddemo.ledger.domain.RejectRecorder;
import com.carddemo.ledger.entity.ProcessedEventEntity;
import com.carddemo.ledger.repository.ProcessedEventRepository;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Direct tests for {@link TransactionAuthorizedConsumer}, the ingress that replaces a job step.
 *
 * <p>The subject stands in for {@code app/jcl/POSTTRAN.jcl:L23}, which names program
 * {@code CBTRN02C}, and for the sequential feed that job allocated. Two source facts decide every
 * assertion below.
 *
 * <p>First, the order of the two decisions. {@code 1500-B-LOOKUP-ACCT} reads the account record at
 * {@code app/cbl/CBTRN02C.cbl:L395} and assigns its reject reason on an invalid key at
 * {@code :L397-L399}. Only a record that read cleanly reaches {@code 2000-POST-TRANSACTION} at
 * {@code :L424}. A missing balance row must therefore be a fault here and never a decline.
 *
 * <p>Second, a reject is expected traffic. {@code app/cbl/CBTRN02C.cbl:L229-L230} moves 4 into
 * {@code RETURN-CODE} when the reject count is positive and raises no abend, so a reject is counted
 * as an outcome and not as a failure. This listener records none.
 *
 * <p>Duplicate handling is ADDITIVE, and the source proves the need: a replayed feed reaches
 * {@code 2900-WRITE-TRANSACTION-FILE} at {@code :L562-L579}, hits a duplicate key and abends. The
 * guard is one read, so the tests below drive it by that read.
 *
 * <p>Every test runs in memory. None opens a database connection, and none contacts a broker.
 */
final class TransactionAuthorizedConsumerTest {

    /** Account of row 7 of {@code app/data/ASCII/acctdata.txt}, eleven digits with zeros held. */
    private static final String ACCOUNT_ID = "00000000007";

    /** Transaction identifier width from {@code TRAN-ID PIC X(16)}. */
    private static final String TRANSACTION_ID = "0000000000000001";

    /** The card number as it arrives: twelve mask characters, then four digits. */
    private static final String MASKED_CARD_NUMBER = "************5740";

    /**
     * The card token every fixture event carries: the rendering {@code PanMasker.cardToken}
     * produces.
     */
    private static final String CARD_TOKEN =
            com.carddemo.cobol.PanMasker.cardToken("4859452612877065");

    /** The origin timestamp layout all 300 records of the daily feed carry. */
    private static final String AUTHORIZED_AT = "2022-07-19 23:16:01.470000";

    /** An amount at the scale {@code TRAN-AMT PIC S9(09)V99} declares. */
    private static final BigDecimal AMOUNT = new BigDecimal("38.72");

    /** The topic this listener reads, and the value every marker records. */
    private static final String TOPIC = "transaction.authorized";

    /** The topic a record the container gives up on is addressed to. */
    private static final String DEAD_LETTER_TOPIC = "carddemo.dead-letter";

    private PostingService postingService;
    private ProcessedEventRepository processedEvents;
    private MeterRegistry registry;
    private LedgerMeters meters;
    private Acknowledgment acknowledgment;
    private TransactionAuthorizedConsumer consumer;

    /**
     * Builds the subject over stubbed collaborators and a real meter registry.
     *
     * <p>The transaction template runs its callback on the calling thread, so no transaction
     * manager is needed. Each assertion below observes the calls, not the commit.
     */
    @BeforeEach
    void buildSubject() {
        postingService = mock(PostingService.class);
        processedEvents = mock(ProcessedEventRepository.class);
        registry = new SimpleMeterRegistry();
        meters = new ObservabilityConfig().ledgerMeters(registry);
        acknowledgment = mock(Acknowledgment.class);
        consumer = new TransactionAuthorizedConsumer(postingService, processedEvents,
                immediateTransactions(), meters, TOPIC, DEAD_LETTER_TOPIC);
    }

    /** Runs the callback on the calling thread, so no transaction manager is needed. */
    private static TransactionTemplate immediateTransactions() {
        return new TransactionTemplate() {
            @Override
            public <T> T execute(TransactionCallback<T> action) {
                return action.doInTransaction(new SimpleTransactionStatus(true));
            }
        };
    }

    /**
     * Builds one authorized event carrying the fixture values.
     *
     * @return the event a delivery carries
     */
    private static TransactionAuthorized anEvent() {
        return TransactionAuthorized.of(ACCOUNT_ID, TRANSACTION_ID, "01", "0001", "POS TERM",
                "Purchase at Abshire-Lowe", AMOUNT, "800000000", "Abshire-Lowe", "North Enoshaven",
                "72112", MASKED_CARD_NUMBER, CARD_TOKEN, AUTHORIZED_AT);
    }

    /** Reports the value of one counter series. */
    private double counter(String name, String tagKey, String tagValue) {
        return registry.get(name).tag(tagKey, tagValue).counter().count();
    }

    /** Answers the guard with no marker, so this delivery is the first to hold the identifier. */
    private void markerAbsent() {
        when(processedEvents.existsById(any(UUID.class))).thenReturn(false);
    }

    /** Answers the guard with a marker, so a delivery of this event has already been applied. */
    private void markerPresent() {
        when(processedEvents.existsById(any(UUID.class))).thenReturn(true);
    }

    /**
     * Makes the posting raise the fault a missing balance row produces.
     *
     * <p>{@code domain/AccountBalanceUpdater} performs that read, so this class observes only the
     * exception.
     */
    private void postingFindsNoBalanceRow() {
        doThrow(new AccountBalanceRowMissingException(ACCOUNT_ID))
                .when(postingService).postTransaction(any(TransactionAuthorized.class),
                        anyString());
    }

    /** Captures the marker the subject saved. */
    private ProcessedEventEntity savedMarker() {
        ArgumentCaptor<ProcessedEventEntity> captor =
                ArgumentCaptor.forClass(ProcessedEventEntity.class);
        verify(processedEvents).save(captor.capture());
        return captor.getValue();
    }

    /** Reads the {@link KafkaListener} the one listener method carries. */
    private static KafkaListener listenerAnnotation() {
        return listenerMethod().getAnnotation(KafkaListener.class);
    }

    /** Finds the one listener method of the subject. */
    private static Method listenerMethod() {
        for (Method method : TransactionAuthorizedConsumer.class.getDeclaredMethods()) {
            if (method.getName().equals("onTransactionAuthorized")) {
                return method;
            }
        }
        throw new AssertionError("onTransactionAuthorized must stay a method of the subject");
    }

    @Nested
    @DisplayName("The posting path, app/cbl/CBTRN02C.cbl:L424-L444")
    class PostingPath {

        @Test
        @DisplayName("one delivery with no marker posts once and acknowledges once")
        void oneDeliveryPostsAndAcknowledges() {
            markerAbsent();
            TransactionAuthorized event = anEvent();

            consumer.onTransactionAuthorized(event, acknowledgment);

            verify(postingService, times(1)).postTransaction(event, ACCOUNT_ID);
            verify(acknowledgment, times(1)).acknowledge();
        }

        @Test
        @DisplayName("the event travels to the posting service unchanged, keyed by its aggregate")
        void theEventTravelsUnchanged() {
            markerAbsent();
            TransactionAuthorized event = anEvent();

            consumer.onTransactionAuthorized(event, acknowledgment);

            ArgumentCaptor<TransactionAuthorized> captor =
                    ArgumentCaptor.forClass(TransactionAuthorized.class);
            verify(postingService).postTransaction(captor.capture(), eq(ACCOUNT_ID));
            assertSame(event, captor.getValue(), "the listener passes the event it received");
            assertEquals(event.aggregateId(), ACCOUNT_ID,
                    "the key the posting service requires is the aggregate identifier");
        }

        @Test
        @DisplayName("the marker follows the posting, so no marker outlives a failed posting")
        void theMarkerFollowsThePosting() {
            markerAbsent();

            consumer.onTransactionAuthorized(anEvent(), acknowledgment);

            InOrder order = inOrder(processedEvents, postingService);
            order.verify(processedEvents).existsById(any(UUID.class));
            order.verify(postingService).postTransaction(any(TransactionAuthorized.class),
                    anyString());
            order.verify(processedEvents).save(any(ProcessedEventEntity.class));
        }

        @Test
        @DisplayName("the marker records the event identifier and the topic the delivery arrived on")
        void theMarkerRecordsTheTopic() {
            markerAbsent();
            TransactionAuthorized event = anEvent();

            consumer.onTransactionAuthorized(event, acknowledgment);

            ProcessedEventEntity marker = savedMarker();
            assertEquals(event.eventId(), marker.getEventId(),
                    "the marker is keyed by the envelope identifier");
            assertEquals(TOPIC, marker.getConsumedTopic(),
                    "the marker names the topic this listener reads");
            assertNotNull(marker.getProcessedAt(), "the consumer supplies the instant");
        }

        @Test
        @DisplayName("a posting counts one consumed event and one posted outcome")
        void aPostingCountsItsOutcome() {
            markerAbsent();

            consumer.onTransactionAuthorized(anEvent(), acknowledgment);

            assertEquals(1.0d, registry.get("carddemo.ledger.events.consumed").counter().count(),
                    "one delivery is one consumed event");
            assertEquals(1.0d, counter("carddemo.ledger.transactions.processed", "outcome",
                    "posted"), "WS-TRANSACTION-COUNT at app/cbl/CBTRN02C.cbl:L206 counts a posting");
            assertEquals(0.0d, counter("carddemo.ledger.transactions.processed", "outcome",
                    "rejected"), "a posting is not a reject");
            assertEquals(1L, registry.get("carddemo.ledger.processing.latency").timer().count(),
                    "the latency timer records one observation per delivery");
        }
    }

    @Nested
    @DisplayName("A missing balance row is a fault and never a decline")
    class MissingBalanceRow {

        @Test
        @DisplayName("an absent balance row refuses the acknowledgement and records no decline")
        void anAbsentBalanceRowIsAFault() {
            markerAbsent();
            postingFindsNoBalanceRow();

            assertThrows(AccountBalanceRowMissingException.class,
                    () -> consumer.onTransactionAuthorized(anEvent(), acknowledgment),
                    "the fault travels to the container and is not answered with a decline");

            verify(acknowledgment, never()).acknowledge();
            assertEquals(0.0d, counter("carddemo.ledger.transactions.processed", "outcome",
                    "rejected"), "an approved authorization is never counted as a reject here");
            assertEquals(1.0d, counter("carddemo.ledger.failures", "stage", "process"),
                    "the fault is counted as a failure, which is what it is");
        }

        @Test
        @DisplayName("this listener holds no reject path, so it cannot report one")
        void thisListenerHoldsNoRejectPath() {
            for (Field field : TransactionAuthorizedConsumer.class.getDeclaredFields()) {
                assertFalse(RejectRecorder.class.equals(field.getType()),
                        () -> "field " + field.getName() + " must not be a reject recorder: a"
                                + " reject is a feed-validation failure and belongs to"
                                + " RejectRecorder");
            }
        }

        @Test
        @DisplayName("the reject reason of app/cbl/CBTRN02C.cbl:L398 is established before"
                + " publication")
        void theReasonIsEstablishedBeforePublication() {
            assertEquals("ACCOUNT RECORD NOT FOUND", DeclineReason.ACCOUNT_NOT_FOUND.description(),
                    "the text is moved at app/cbl/CBTRN02C.cbl:L398");
            assertTrue(DeclineReason.ACCOUNT_NOT_FOUND.resolvesAccount(),
                    "the reason resolves an account, so the authorization service can carry it");
        }
    }

    @Nested
    @DisplayName("Duplicate delivery, the gap at app/cbl/CBTRN02C.cbl:L562-L579")
    class DuplicateDelivery {

        @Test
        @DisplayName("a marked identifier leaves every table untouched and still acknowledges")
        void aMarkedIdentifierAppliesNothing() {
            markerPresent();

            consumer.onTransactionAuthorized(anEvent(), acknowledgment);

            verifyNoInteractions(postingService);
            verify(processedEvents, never()).save(any(ProcessedEventEntity.class));
            verify(acknowledgment, times(1)).acknowledge();
            assertEquals(1.0d, counter("carddemo.ledger.transactions.processed", "outcome",
                    "duplicate"), "a duplicate delivery counts under its own outcome");
        }

        @Test
        @DisplayName("the guard runs before the posting, so no write precedes it")
        void theGuardRunsFirst() {
            markerPresent();
            TransactionAuthorized event = anEvent();

            consumer.onTransactionAuthorized(event, acknowledgment);

            verify(processedEvents, times(1)).existsById(event.eventId());
            verifyNoInteractions(postingService);
        }

        @Test
        @DisplayName("a duplicate delivery counts no failure")
        void aDuplicateCountsNoFailure() {
            markerPresent();

            consumer.onTransactionAuthorized(anEvent(), acknowledgment);

            assertEquals(0.0d, counter("carddemo.ledger.failures", "stage", "process"),
                    "a duplicate is expected traffic and not a fault");
            assertEquals(0.0d, counter("carddemo.ledger.failures", "stage", "deserialize"),
                    "a duplicate reached this listener, so nothing failed to deserialize");
        }
    }

    @Nested
    @DisplayName("Failure handling and acknowledgement")
    class FailureHandling {

        @Test
        @DisplayName("a posting fault leaves the offset uncommitted and counts one failure")
        void aPostingFaultRefusesTheAcknowledgement() {
            markerAbsent();
            doThrow(new IllegalStateException("store unavailable")).when(postingService)
                    .postTransaction(any(TransactionAuthorized.class), eq(ACCOUNT_ID));

            assertThrows(IllegalStateException.class,
                    () -> consumer.onTransactionAuthorized(anEvent(), acknowledgment),
                    "a fault must reach the container, which decides between retry and dead letter");
            verify(acknowledgment, never()).acknowledge();
            assertEquals(1.0d, counter("carddemo.ledger.failures", "stage", "process"),
                    "a store fault counts under the process stage");
        }

        @Test
        @DisplayName("a failed posting saves no marker")
        void aFailedPostingSavesNoMarker() {
            markerAbsent();
            postingFindsNoBalanceRow();

            assertThrows(AccountBalanceRowMissingException.class,
                    () -> consumer.onTransactionAuthorized(anEvent(), acknowledgment));

            verify(processedEvents, never()).save(any(ProcessedEventEntity.class));
        }

        @Test
        @DisplayName("the latency timer records even when the delivery failed")
        void theTimerRecordsAFailedDelivery() {
            markerAbsent();
            doThrow(new IllegalStateException("store unavailable")).when(postingService)
                    .postTransaction(any(TransactionAuthorized.class), eq(ACCOUNT_ID));

            assertThrows(IllegalStateException.class,
                    () -> consumer.onTransactionAuthorized(anEvent(), acknowledgment));

            assertEquals(1L, registry.get("carddemo.ledger.processing.latency").timer().count(),
                    "the timer is stopped in a finally block, so a failure is measured too");
        }

        @Test
        @DisplayName("neither argument may be absent")
        void neitherArgumentMayBeAbsent() {
            assertThrows(NullPointerException.class,
                    () -> consumer.onTransactionAuthorized(null, acknowledgment),
                    "a listener with no event has nothing to apply");
            assertThrows(NullPointerException.class,
                    () -> consumer.onTransactionAuthorized(anEvent(), null),
                    "a listener with no acknowledgement could never commit its offset");
        }
    }

    @Nested
    @DisplayName("The listener contract the container binds")
    class ListenerContract {

        @Test
        @DisplayName("the topic is a property placeholder and never a literal")
        void theTopicIsAPlaceholder() {
            String[] topics = listenerAnnotation().topics();

            assertEquals(1, topics.length, "one listener reads one topic");
            assertEquals("${carddemo.kafka.topics.transaction-authorized}", topics[0],
                    "a renamed key must break startup rather than bind a stale topic");
        }

        @Test
        @DisplayName("the group is a property placeholder, so the source names the group it joins")
        void theGroupIsAPlaceholder() {
            assertEquals("${spring.kafka.consumer.group-id}", listenerAnnotation().groupId(),
                    "a group of its own is what makes this reader independent of the other two");
        }

        @Test
        @DisplayName("the method takes one event and one acknowledgement, and returns nothing")
        void theMethodTakesTwoParameters() {
            Method listener = listenerMethod();

            assertEquals(2, listener.getParameterCount(),
                    "one record per invocation, and no header or record parameter");
            assertEquals(TransactionAuthorized.class, listener.getParameterTypes()[0],
                    "the first parameter is the deserialized payload");
            assertEquals(Acknowledgment.class, listener.getParameterTypes()[1],
                    "the second parameter is the offset commit");
            assertEquals(void.class, listener.getReturnType(), "a listener returns nothing");
        }

        @Test
        @DisplayName("no diagnostic constant carries a digit, so none can carry an identifier")
        void noDiagnosticConstantCarriesADigit() throws ReflectiveOperationException {
            assertConstantIsSafe("ABEND_CODE", DeadLetterMetadata.ABEND_CODE_MAX_LENGTH);
            assertConstantIsSafe("CULPRIT", DeadLetterMetadata.CULPRIT_MAX_LENGTH);
            assertConstantIsSafe("POSTING_NOT_COMPLETED", DeadLetterMetadata.REASON_MAX_LENGTH);
        }

        /**
         * Asserts one diagnostic constant fits its component and carries no digit.
         *
         * @param fieldName the constant to read
         * @param maxLength the width the component holds
         * @throws ReflectiveOperationException if the constant is absent
         */
        private void assertConstantIsSafe(String fieldName, int maxLength)
                throws ReflectiveOperationException {
            Field field = TransactionAuthorizedConsumer.class.getDeclaredField(fieldName);
            field.setAccessible(true);
            String value = (String) field.get(null);

            assertNotNull(value, () -> fieldName + " must hold text");
            assertTrue(value.length() <= maxLength,
                    () -> fieldName + " must fit the component that holds it");
            assertFalse(value.chars().anyMatch(Character::isDigit),
                    () -> fieldName + " must carry no digit, so no identifier can travel in it");
        }
    }

    @Nested
    @DisplayName("The meter families a demonstration reads")
    class MeterFamilies {

        @Test
        @DisplayName("all three required families register before the first message")
        void allThreeFamiliesRegisterAtStartUp() {
            assertNotNull(registry.find("carddemo.ledger.events.consumed").counter(),
                    "events consumed is one of the three families the platform requires");
            assertNotNull(registry.find("carddemo.ledger.processing.latency").timer(),
                    "processing latency is the second");
            assertNotNull(registry.find("carddemo.ledger.failures").tag("stage", "process")
                            .counter(),
                    "failure count is the third");
            assertEquals(0.0d, registry.get("carddemo.ledger.events.consumed").counter().count(),
                    "a scrape taken before the first message reports zero rather than nothing");
        }

        @Test
        @DisplayName("the two source counters and the additive one are distinct series")
        void theOutcomeSeriesAreDistinct() {
            meters.recordTransactionPosted();
            meters.recordTransactionRejected();
            meters.recordDuplicateSkipped();

            assertEquals(1.0d,
                    counter("carddemo.ledger.transactions.processed", "outcome", "posted"));
            assertEquals(1.0d,
                    counter("carddemo.ledger.transactions.processed", "outcome", "rejected"));
            assertEquals(1.0d,
                    counter("carddemo.ledger.transactions.processed", "outcome", "duplicate"));
        }

        @Test
        @DisplayName("the four failure stages are distinct series under one name")
        void theFailureStagesAreDistinct() {
            meters.recordDeserializeFailure();
            meters.recordProcessFailure();
            meters.recordPublishFailure();
            meters.recordAbandonedRow();

            assertEquals(1.0d, counter("carddemo.ledger.failures", "stage", "deserialize"));
            assertEquals(1.0d, counter("carddemo.ledger.failures", "stage", "process"));
            assertEquals(1.0d, counter("carddemo.ledger.failures", "stage", "publish"));
            assertEquals(1.0d, counter("carddemo.ledger.failures", "stage", "abandon"));
        }

        @Test
        @DisplayName("no meter name and no tag value carries an identifier")
        void noMeterCarriesAnIdentifier() {
            markerAbsent();
            consumer.onTransactionAuthorized(anEvent(), acknowledgment);

            registry.getMeters().forEach(meter -> {
                String rendered = meter.getId().getName() + " "
                        + meter.getId().getTags().toString();
                assertTrue(!rendered.contains(ACCOUNT_ID) && !rendered.contains(TRANSACTION_ID)
                                && !rendered.contains(MASKED_CARD_NUMBER),
                        () -> "meter " + rendered + " must carry no identifier, so the series count"
                                + " stays fixed however much traffic arrives");
            });
        }
    }
}
