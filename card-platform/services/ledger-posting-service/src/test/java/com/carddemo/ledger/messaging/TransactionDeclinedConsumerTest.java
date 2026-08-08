package com.carddemo.ledger.messaging;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.carddemo.events.DeclineReason;
import com.carddemo.events.TransactionDeclined;
import com.carddemo.ledger.config.ObservabilityConfig;
import com.carddemo.ledger.config.ObservabilityConfig.LedgerMeters;
import com.carddemo.ledger.domain.RejectRecorder;
import com.carddemo.ledger.domain.RejectRecorder.FeedTransaction;
import com.carddemo.ledger.entity.ProcessedEventEntity;
import com.carddemo.ledger.entity.ProcessedEventEntity.ProcessedEventId;
import com.carddemo.ledger.repository.ProcessedEventRepository;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.regex.Pattern;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.TransientDataAccessResourceException;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Proves the reject path has a production ingress, and that a duplicate delivery of one refusal
 * writes one row.
 *
 * <p>{@link TransactionDeclinedConsumer} is the half of the source fork that had no production
 * caller. {@code 1500-VALIDATE-TRAN} at {@code app/cbl/CBTRN02C.cbl:L370-L378} either falls through
 * to {@code 2000-POST-TRANSACTION} or branches to {@code 2500-WRITE-REJECT-REC} at
 * {@code app/cbl/CBTRN02C.cbl:L446-L465}, and both branches run in one program. The target splits
 * them across two services because AAP 0.1.1 makes the authorization service the sole writer of the
 * decision, so the refusal reaches this service as an event rather than as a fall-through.
 *
 * <p>Nothing here opens a database connection or reaches a broker. Every collaborator is a mock or a
 * recording stand-in.
 *
 * <p>Design decisions: {@code card-platform/docs/decision-log.md}.
 */
@DisplayName("The transaction-declined listener, the reject row it writes and its guard")
final class TransactionDeclinedConsumerTest {

    /**
     * Account row 21 of {@code app/data/ASCII/cardxref.txt} resolves the fixture card to, eleven
     * digits with the leading zeros held.
     *
     * <p>Width from {@code XREF-ACCT-ID PIC 9(11)} at {@code app/cpy/CVACT03Y.cpy:L7}.
     */
    private static final String ACCOUNT_ID = "00000000007";

    /** Transaction identifier of record 1 of {@code app/data/ASCII/dailytran.txt}, sixteen bytes. */
    private static final String TRANSACTION_ID = "0000000000683580";

    /**
     * The card number of that record as an event carries it: twelve mask characters, then the last
     * four digits. ADDITIVE, since no source program masks anything. The full card number appears
     * nowhere in this file.
     */
    private static final String MASKED_CARD_NUMBER = "************7065";

    /**
     * The origin timestamp of that record, twenty-six characters, from
     * {@code DALYTRAN-ORIG-TS PIC X(26)} at {@code app/cpy/CVTRA06Y.cpy:L16}.
     */
    private static final String ORIGIN_TIMESTAMP = "2022-06-10 19:27:53.000000";

    /** Amount of that record, at the two fractional digits of {@code PIC S9(09)V99}. */
    private static final BigDecimal AMOUNT = new BigDecimal("715.44");

    /** Transaction type code of that record, {@code DALYTRAN-TYPE-CD PIC X(02)}. */
    private static final String TYPE_CODE = "01";

    /** Category code of that record, {@code DALYTRAN-CAT-CD PIC 9(04)}. */
    private static final String CATEGORY_CODE = "0001";

    /** Source of that record, {@code DALYTRAN-SOURCE PIC X(10)}. */
    private static final String SOURCE = "POS TERM";

    /** Description of that record, {@code DALYTRAN-DESC PIC X(100)}. */
    private static final String DESCRIPTION = "Purchase at Abshire-Lowe";

    /** Merchant identifier of that record, {@code DALYTRAN-MERCHANT-ID PIC 9(09)}. */
    private static final String MERCHANT_ID = "800000000";

    /** Merchant name of that record, {@code DALYTRAN-MERCHANT-NAME PIC X(50)}. */
    private static final String MERCHANT_NAME = "Abshire-Lowe";

    /** Merchant city of that record, {@code DALYTRAN-MERCHANT-CITY PIC X(50)}. */
    private static final String MERCHANT_CITY = "North Enoshaven";

    /** Merchant postal code of that record, {@code DALYTRAN-MERCHANT-ZIP PIC X(10)}. */
    private static final String MERCHANT_ZIP = "72112";

    /** The topic this listener reads. */
    private static final String TOPIC = "transaction.declined";

    /** The topic a spent record is addressed to. */
    private static final String DEAD_LETTER_TOPIC = "carddemo.dead-letter";

    /** Journal token a transaction start appends. */
    private static final String BEGIN = "begin";

    /** Journal token a commit appends. */
    private static final String COMMIT = "commit";

    /** Journal token a rollback appends. */
    private static final String ROLLBACK = "rollback";

    /** Journal token an offset commit appends. */
    private static final String ACK = "ack";

    /** Twelve or more digits in one run, which no diagnostic of this listener may carry. */
    private static final Pattern LONG_DIGIT_RUN = Pattern.compile("\\d{12,}");

    /** The ordered call record every collaborator of one test appends to. */
    private final List<String> journal = new ArrayList<>();

    /** Writes the reject row, stubbed here. */
    private RejectRecorder rejectRecorder;

    /** Store of the duplicate-delivery markers, stubbed here. */
    private ProcessedEventRepository processedEvents;

    /** The registry the assertions read counter values out of. */
    private MeterRegistry registry;

    /** The recording surface this listener writes to. */
    private LedgerMeters meters;

    /** The offset commit this listener invokes after its transaction commits. */
    private Acknowledgment acknowledgment;

    /** The listener under test. */
    private TransactionDeclinedConsumer consumer;

    @BeforeEach
    void buildSubject() {
        journal.clear();
        rejectRecorder = mock(RejectRecorder.class);
        processedEvents = mock(ProcessedEventRepository.class);
        registry = new SimpleMeterRegistry();
        meters = new ObservabilityConfig().ledgerMeters(registry);
        acknowledgment = new RecordingAcknowledgment(journal);
        consumer = new TransactionDeclinedConsumer(rejectRecorder, processedEvents,
                new TransactionTemplate(new RecordingTransactionManager(journal)), meters, TOPIC,
                DEAD_LETTER_TOPIC);
        when(processedEvents.existsById(any(ProcessedEventId.class))).thenReturn(false);
    }

    /** Builds the detail-bearing refusal this listener is meant to record. */
    private static TransactionDeclined declined(DeclineReason reason) {
        return TransactionDeclined.withTransactionDetail(ACCOUNT_ID, TRANSACTION_ID, reason,
                TYPE_CODE, CATEGORY_CODE, SOURCE, DESCRIPTION, AMOUNT, MERCHANT_ID, MERCHANT_NAME,
                MERCHANT_CITY, MERCHANT_ZIP, MASKED_CARD_NUMBER, ORIGIN_TIMESTAMP);
    }

    /** Captures the one feed record the listener handed to the recorder. */
    private FeedTransaction recordedFeedRecord() {
        ArgumentCaptor<FeedTransaction> captor = ArgumentCaptor.forClass(FeedTransaction.class);
        verify(rejectRecorder).recordReject(captor.capture(), any(DeclineReason.class));
        return captor.getValue();
    }

    /** Reads the value of one outcome-tagged transaction counter. */
    private double outcome(String tag) {
        return registry.counter("carddemo.ledger.transactions.processed", "outcome", tag).count();
    }

    @Nested
    @DisplayName("The production ingress of app/cbl/CBTRN02C.cbl:L446-L465")
    class Ingress {

        @Test
        @DisplayName("one refusal writes one reject row, marks the event and commits the offset")
        void oneRefusalWritesOneRowAndCommitsTheOffset() {
            consumer.onTransactionDeclined(declined(DeclineReason.OVER_CREDIT_LIMIT), ACCOUNT_ID,
                    TOPIC, acknowledgment);

            verify(rejectRecorder).recordReject(any(FeedTransaction.class),
                    any(DeclineReason.class));
            verify(processedEvents).save(any(ProcessedEventEntity.class));
            assertEquals(List.of(BEGIN, COMMIT, ACK), journal,
                    "the row, the marker and the offset commit in that order");
            assertEquals(1.0D, registry.counter("carddemo.ledger.events.consumed").count(),
                    "one delivery is one event read");
            assertEquals(0.0D, outcome("rejected"),
                    "the counter WS-REJECT-COUNT at app/cbl/CBTRN02C.cbl:L186 stands for is raised"
                            + " inside RejectRecorder, beside the row it wrote. Raising it here as"
                            + " well would double every reject the demo reports");
        }

        @Test
        @DisplayName("the marker is written after the row, inside the same transaction")
        void theMarkerFollowsTheRowInsideOneTransaction() {
            List<String> order = new ArrayList<>();
            doThrowNothingButRecord(order);

            consumer.onTransactionDeclined(declined(DeclineReason.OVER_CREDIT_LIMIT), ACCOUNT_ID,
                    TOPIC, acknowledgment);

            assertEquals(List.of("row", "marker"), order,
                    "a marker written first would cover an event whose row failed");
            assertEquals(List.of(BEGIN, COMMIT, ACK), journal, "one transaction, then the offset");
        }

        /**
         * Asserts every reason that resolves an account reaches the recorder as its typed constant.
         *
         * <p>Reason {@code 0100} resolves no account at {@code app/cbl/CBTRN02C.cbl:L383-L387} and
         * travels at contract version 2, which carries none of the nine descriptive values, so it
         * cannot arrive on this path carrying detail.
         *
         * @param reason one reject reason that resolves an account identifier
         */
        @ParameterizedTest
        @EnumSource(value = DeclineReason.class, names = "INVALID_CARD_NUMBER",
                mode = EnumSource.Mode.EXCLUDE)
        @DisplayName("the reason the event carries reaches the recorder as its typed constant")
        void theReasonReachesTheRecorderAsItsConstant(DeclineReason reason) {
            consumer.onTransactionDeclined(declined(reason), ACCOUNT_ID, TOPIC, acknowledgment);

            ArgumentCaptor<DeclineReason> captor = ArgumentCaptor.forClass(DeclineReason.class);
            verify(rejectRecorder).recordReject(any(FeedTransaction.class), captor.capture());
            assertSame(reason, captor.getValue(),
                    "the recorder derives the trailer text from this constant, so a substituted"
                            + " reason would put the wrong text in VALIDATION-TRAILER");
        }

        @Test
        @DisplayName("all thirteen components of the feed record are copied off the event")
        void everyComponentIsCopiedOffTheEvent() {
            TransactionDeclined event = declined(DeclineReason.OVER_CREDIT_LIMIT);

            consumer.onTransactionDeclined(event, ACCOUNT_ID, TOPIC, acknowledgment);

            FeedTransaction record = recordedFeedRecord();
            assertEquals(event.accountId(), record.accountId(), "XREF-ACCT-ID");
            assertEquals(event.transactionId(), record.transactionId(), "DALYTRAN-ID");
            assertEquals(event.transactionTypeCode(), record.transactionTypeCode(),
                    "DALYTRAN-TYPE-CD");
            assertEquals(event.merchantCategoryCode(), record.categoryCode(), "DALYTRAN-CAT-CD");
            assertEquals(event.source(), record.source(), "DALYTRAN-SOURCE");
            assertEquals(event.description(), record.description(), "DALYTRAN-DESC");
            assertEquals(event.amount(), record.amount(), "DALYTRAN-AMT");
            assertEquals(event.merchantId(), record.merchantId(), "DALYTRAN-MERCHANT-ID");
            assertEquals(event.merchantName(), record.merchantName(), "DALYTRAN-MERCHANT-NAME");
            assertEquals(event.merchantCity(), record.merchantCity(), "DALYTRAN-MERCHANT-CITY");
            assertEquals(event.merchantZip(), record.merchantZip(), "DALYTRAN-MERCHANT-ZIP");
            assertEquals(event.maskedCardNumber(), record.maskedCardNumber(), "DALYTRAN-CARD-NUM");
            assertEquals(event.originTimestamp(), record.originTimestamp(), "DALYTRAN-ORIG-TS");
        }

        @Test
        @DisplayName("the amount keeps its scale and its sign, so a refund stays a refund")
        void theAmountKeepsItsScaleAndSign() {
            TransactionDeclined refund = TransactionDeclined.withTransactionDetail(ACCOUNT_ID,
                    TRANSACTION_ID, DeclineReason.OVER_CREDIT_LIMIT, TYPE_CODE, CATEGORY_CODE,
                    SOURCE, DESCRIPTION, new BigDecimal("-715.44"), MERCHANT_ID, MERCHANT_NAME,
                    MERCHANT_CITY, MERCHANT_ZIP, MASKED_CARD_NUMBER, ORIGIN_TIMESTAMP);

            consumer.onTransactionDeclined(refund, ACCOUNT_ID, TOPIC, acknowledgment);

            FeedTransaction record = recordedFeedRecord();
            assertEquals(2, record.amount().scale(), "PIC S9(09)V99 holds two fractional digits");
            assertEquals(-1, record.amount().signum(), "the minus sign of a refund survives");
        }
    }

    @Nested
    @DisplayName("The duplicate-delivery guard, which has no COBOL ancestor")
    class DuplicateGuard {

        @Test
        @DisplayName("a second delivery writes no row, counts a duplicate and commits the offset")
        void aSecondDeliveryWritesNoRow() {
            when(processedEvents.existsById(any(ProcessedEventId.class))).thenReturn(true);

            consumer.onTransactionDeclined(declined(DeclineReason.OVER_CREDIT_LIMIT), ACCOUNT_ID,
                    TOPIC, acknowledgment);

            verifyNoInteractions(rejectRecorder);
            verify(processedEvents, org.mockito.Mockito.never())
                    .save(any(ProcessedEventEntity.class));
            assertEquals(List.of(BEGIN, COMMIT, ACK), journal,
                    "a covered delivery still commits its offset, or every rebalance reads it"
                            + " again");
            assertEquals(1.0D, outcome("duplicate"), "the duplicate is counted apart");
        }

        @Test
        @DisplayName("the marker key names the topic the delivery arrived on")
        void theMarkerKeyNamesTheTopicOfTheDelivery() {
            consumer.onTransactionDeclined(declined(DeclineReason.OVER_CREDIT_LIMIT), ACCOUNT_ID,
                    "another.topic", acknowledgment);

            ArgumentCaptor<ProcessedEventEntity> captor =
                    ArgumentCaptor.forClass(ProcessedEventEntity.class);
            verify(processedEvents).save(captor.capture());
            assertEquals("another.topic", captor.getValue().getConsumedTopic(),
                    "the marker records the topic the delivery carried, not the configured name");
        }

        @Test
        @DisplayName("a delivery with no topic header falls back to the configured topic")
        void aDeliveryWithNoTopicHeaderFallsBackToTheConfiguredName() {
            consumer.onTransactionDeclined(declined(DeclineReason.OVER_CREDIT_LIMIT), ACCOUNT_ID,
                    null, acknowledgment);

            ArgumentCaptor<ProcessedEventEntity> captor =
                    ArgumentCaptor.forClass(ProcessedEventEntity.class);
            verify(processedEvents).save(captor.capture());
            assertEquals(TOPIC, captor.getValue().getConsumedTopic(),
                    "the topic is half of the key, and a key column holds no null");
        }
    }

    @Nested
    @DisplayName("What is acknowledged without a row, and why")
    class AcknowledgedWithoutARow {

        @Test
        @DisplayName("a decline carrying no descriptive detail is marked and writes no row")
        void aDeclineWithoutDetailIsMarkedAndWritesNoRow() {
            TransactionDeclined bare = TransactionDeclined.of(ACCOUNT_ID, TRANSACTION_ID,
                    DeclineReason.OVER_CREDIT_LIMIT, AMOUNT, MASKED_CARD_NUMBER);

            consumer.onTransactionDeclined(bare, ACCOUNT_ID, TOPIC, acknowledgment);

            assertFalse(bare.carriesTransactionDetail(),
                    "a version-one decline carries none of the nine, so the 350 bytes of"
                            + " REJECT-TRAN-DATA cannot be rendered from it");
            verifyNoInteractions(rejectRecorder);
            verify(processedEvents).save(any(ProcessedEventEntity.class));
            assertEquals(List.of(BEGIN, COMMIT, ACK), journal,
                    "replaying it would reach the same conclusion, so it is marked and let go");
            assertEquals(0.0D, outcome("duplicate"),
                    "this is not a duplicate: no earlier delivery of this event was suppressed, so"
                            + " reporting it as one would put a number in the demo that means"
                            + " nothing");
            assertEquals(0.0D, outcome("rejected"), "no row, so no reject is accounted for");
        }

        @Test
        @DisplayName("an unresolved-card decline is marked and writes no row")
        void anUnresolvedCardDeclineIsMarkedAndWritesNoRow() {
            TransactionDeclined unresolved = TransactionDeclined.ofUnresolvedAccount(TRANSACTION_ID,
                    AMOUNT, MASKED_CARD_NUMBER);

            consumer.onTransactionDeclined(unresolved, TRANSACTION_ID, TOPIC, acknowledgment);

            assertFalse(unresolved.carriesTransactionDetail(),
                    "the unresolved-account contract carries none of the nine");
            verifyNoInteractions(rejectRecorder);
            verify(processedEvents).save(any(ProcessedEventEntity.class));
            assertEquals(List.of(BEGIN, COMMIT, ACK), journal, "marked, and let go");
        }
    }

    @Nested
    @DisplayName("The key check, which the partition guarantee needs")
    class KeyCheck {

        @Test
        @DisplayName("a record with no key is refused before the transaction opens")
        void aRecordWithNoKeyIsRefused() {
            IllegalArgumentException refused = assertThrows(IllegalArgumentException.class,
                    () -> consumer.onTransactionDeclined(
                            declined(DeclineReason.OVER_CREDIT_LIMIT), null, TOPIC,
                            acknowledgment));

            assertTrue(refused.getMessage().contains("no message key"),
                    "the refusal does not say what is missing: " + refused.getMessage());
            assertTrue(journal.isEmpty(), "no transaction opened and no offset was committed");
            verifyNoInteractions(rejectRecorder, processedEvents);
            assertEquals(1.0D,
                    registry.counter("carddemo.ledger.failures", "stage", "process").count(),
                    "the refusal is counted as a processing failure");
        }

        @Test
        @DisplayName("a record whose key names another aggregate is refused, naming neither")
        void aRecordKeyedOnAnotherAggregateIsRefused() {
            IllegalArgumentException refused = assertThrows(IllegalArgumentException.class,
                    () -> consumer.onTransactionDeclined(
                            declined(DeclineReason.OVER_CREDIT_LIMIT), "00000000042", TOPIC,
                            acknowledgment));

            assertTrue(refused.getMessage().contains("does not name the aggregate"),
                    "the refusal does not say what is wrong: " + refused.getMessage());
            assertFalse(LONG_DIGIT_RUN.matcher(refused.getMessage()).find(),
                    "the refusal carried a long digit run: " + refused.getMessage());
            assertTrue(journal.isEmpty(), "nothing was written and no offset was committed");
        }
    }

    @Nested
    @DisplayName("The failure path, and the offset it does not commit")
    class FailurePath {

        @Test
        @DisplayName("a store failure rolls the transaction back and commits no offset")
        void aStoreFailureCommitsNoOffset() {
            doThrow(new TransientDataAccessResourceException("rejected_transaction unavailable"))
                    .when(rejectRecorder)
                    .recordReject(any(FeedTransaction.class), any(DeclineReason.class));

            assertThrows(TransientDataAccessResourceException.class,
                    () -> consumer.onTransactionDeclined(
                            declined(DeclineReason.OVER_CREDIT_LIMIT), ACCOUNT_ID, TOPIC,
                            acknowledgment));

            assertEquals(List.of(BEGIN, ROLLBACK), journal,
                    "the offset stays uncommitted, so the container redelivers the record");
            assertEquals(1.0D,
                    registry.counter("carddemo.ledger.failures", "stage", "process").count(),
                    "the attempt is counted as a processing failure");
        }
    }

    @Nested
    @DisplayName("The listener declaration, so a wrong binding fails the build")
    class Declaration {

        @Test
        @DisplayName("it reads the declined topic under a group of its own")
        void itReadsTheDeclinedTopicUnderItsOwnGroup() throws NoSuchMethodException {
            Method listener = TransactionDeclinedConsumer.class.getMethod("onTransactionDeclined",
                    TransactionDeclined.class, String.class, String.class, Acknowledgment.class);
            KafkaListener annotation = listener.getAnnotation(KafkaListener.class);

            assertNotNull(annotation, "the method carries no listener annotation");
            assertEquals(List.of("${carddemo.kafka.topics.transaction-declined}"),
                    List.of(annotation.topics()), "it must read the declined topic");
            assertEquals("${carddemo.kafka.groups.transaction-declined:ledger-reject}",
                    annotation.groupId(),
                    "a group of its own, so the reject stream lags and rebalances apart from the"
                            + " posting stream");
            assertEquals("kafkaListenerContainerFactory", annotation.containerFactory(),
                    "the same container factory the posting listener runs on");
        }
    }

    /** Records the order of the two writes one delivery performs. */
    private void doThrowNothingButRecord(List<String> order) {
        org.mockito.Mockito.doAnswer(invocation -> {
            order.add("row");
            return null;
        }).when(rejectRecorder).recordReject(any(FeedTransaction.class), any(DeclineReason.class));
        when(processedEvents.save(any(ProcessedEventEntity.class))).thenAnswer(invocation -> {
            order.add("marker");
            return invocation.getArgument(0);
        });
    }

    /** A transaction manager that records its boundaries instead of reaching a database. */
    private static final class RecordingTransactionManager implements PlatformTransactionManager {

        /** The shared journal this fake appends to. */
        private final List<String> journal;

        /**
         * Takes the journal every collaborator of one test shares.
         *
         * @param journal the ordered call record
         */
        RecordingTransactionManager(List<String> journal) {
            this.journal = journal;
        }

        @Override
        public TransactionStatus getTransaction(TransactionDefinition definition) {
            journal.add(BEGIN);
            return new SimpleTransactionStatus(true);
        }

        @Override
        public void commit(TransactionStatus status) {
            journal.add(COMMIT);
        }

        @Override
        public void rollback(TransactionStatus status) {
            journal.add(ROLLBACK);
        }
    }

    /** An acknowledgement that records its call instead of reaching a broker. */
    private static final class RecordingAcknowledgment implements Acknowledgment {

        /** The shared journal this fake appends to. */
        private final List<String> journal;

        /**
         * Takes the journal every collaborator of one test shares.
         *
         * @param journal the ordered call record
         */
        RecordingAcknowledgment(List<String> journal) {
            this.journal = journal;
        }

        @Override
        public void acknowledge() {
            journal.add(ACK);
        }
    }

    @Test
    @DisplayName("the marker key holds the event identifier the delivery carried")
    void theMarkerKeyHoldsTheEventIdentifier() {
        TransactionDeclined event = declined(DeclineReason.OVER_CREDIT_LIMIT);

        consumer.onTransactionDeclined(event, ACCOUNT_ID, TOPIC, acknowledgment);

        ArgumentCaptor<ProcessedEventEntity> captor =
                ArgumentCaptor.forClass(ProcessedEventEntity.class);
        verify(processedEvents).save(captor.capture());
        UUID stored = captor.getValue().getEventId();
        assertEquals(event.eventId(), stored, "the marker names the event it covers");
    }
}
