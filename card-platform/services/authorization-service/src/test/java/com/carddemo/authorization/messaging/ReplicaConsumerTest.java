package com.carddemo.authorization.messaging;

import com.carddemo.authorization.config.ObservabilityConfig;
import com.carddemo.authorization.config.ObservabilityConfig.ReplicaMeters;
import com.carddemo.authorization.domain.ReplicaGapLog;
import com.carddemo.authorization.entity.ProcessedEventEntity;
import com.carddemo.authorization.entity.ProcessedEventEntity.ProcessedEventId;
import com.carddemo.authorization.repository.AccountCreditSnapshotRepository;
import com.carddemo.authorization.repository.CardCrossReferenceRepository;
import com.carddemo.authorization.repository.ProcessedEventRepository;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

import org.slf4j.LoggerFactory;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Holds the two listeners that keep the authorization replicas current.
 *
 * <p>ADDITIVE IN FULL. {@code app/cbl/CBTRN02C.cbl:L382} and {@code app/cbl/CBTRN02C.cbl:L395} issue
 * keyed reads against the cross-reference and account datasets themselves, so the source has no copy
 * and nothing to keep current. These listeners exist because the decline rules read copies, and a copy
 * nothing updates keeps answering with whatever it last knew while raising nothing.
 *
 * <p>Both listeners are held to the same five properties: the apply and its marker reach the store
 * together, a repeat delivery applies nothing, a proven duplicate acknowledges while every other
 * integrity failure is rethrown so the record retries, the topic and group come from configuration
 * rather than from a literal, and the line each one writes when it applied nothing names the event and
 * not the account. The last of those is why {@link #recordedLines} exists: an account identifier in an
 * ordinary log line is retained by whatever collects logs, and the event identifier is a correlation
 * value that leads a reader to the event on the topic, where the access controls are.
 *
 * <p>Every test runs against mocked repositories and an immediate transaction template, so
 * {@code mvn test} needs no database and no broker.
 */
@DisplayName("The two listeners that keep the authorization replicas current")
class ReplicaConsumerTest {

    /** Reads a payload the way the shipped deserializer hands one over: as a checked tree. */
    private static final JsonMapper MAPPER = JsonMapper.builder().build();

    /** The account both fixtures name, eleven digits with leading zeros. */
    private static final String ACCOUNT_ID = "00000000077";

    /** The event identifier both fixtures carry. */
    private static final UUID EVENT_ID = UUID.fromString("3f1a2b3c-4d5e-6f70-8192-a3b4c5d6e7f8");

    /** The producer's clock on both fixtures. */
    private static final String OCCURRED_AT = "2026-08-04T12:00:00Z";

    /** The topic the account-state listener reads, and half of every marker key it writes. */
    private static final String ACCOUNT_STATE_TOPIC = "account.state-changed";

    /** The topic the card listener reads, and half of every marker key it writes. */
    private static final String CARD_UPDATED_TOPIC = "card.updated";

    /**
     * The whole marker key one delivery of {@link #EVENT_ID} on the account-state topic carries.
     *
     * <p>{@code src/main/resources/db/migration/V6__processed_event_topic_key.sql} made the consumed
     * topic half of the key, so a stub that answered on the identifier alone would answer for both
     * listeners at once and hide exactly the defect that migration removed.
     */
    private static final ProcessedEventId ACCOUNT_STATE_KEY =
            new ProcessedEventId(EVENT_ID, ACCOUNT_STATE_TOPIC);

    /** The whole marker key one delivery of {@link #EVENT_ID} on the card topic carries. */
    private static final ProcessedEventId CARD_UPDATED_KEY =
            new ProcessedEventId(EVENT_ID, CARD_UPDATED_TOPIC);

    private AccountCreditSnapshotRepository snapshots;
    private CardCrossReferenceRepository crossReferences;
    private ProcessedEventRepository processedEvents;
    private Acknowledgment acknowledgment;

    /** The registry both listeners record through, read back by the assertions on their series. */
    private MeterRegistry registry;

    /** The recording surface the shipped configuration hands each listener. */
    private ReplicaMeters meters;

    /**
     * The gap log both listeners open and close.
     *
     * <p>Stubbed rather than exercised here. What it records is asserted in
     * {@link com.carddemo.authorization.domain.ReplicaGapLogTest}, and what a decision does with a
     * standing gap is asserted in {@code domain/AuthorizationServiceTest}.
     */
    private ReplicaGapLog replicaGaps;

    @BeforeEach
    void buildCollaborators() {
        snapshots = Mockito.mock(AccountCreditSnapshotRepository.class);
        crossReferences = Mockito.mock(CardCrossReferenceRepository.class);
        processedEvents = Mockito.mock(ProcessedEventRepository.class);
        acknowledgment = Mockito.mock(Acknowledgment.class);
        replicaGaps = Mockito.mock(ReplicaGapLog.class);
        registry = new SimpleMeterRegistry();
        meters = new ObservabilityConfig().replicaMeters(registry);
    }

    /**
     * Reads one consume-side series back.
     *
     * @param name      the meter name
     * @param eventType the stream the series belongs to
     * @return the count the counter carries
     */
    private double counter(String name, String eventType) {
        return registry.get(name).tag(ObservabilityConfig.EVENT_TYPE_TAG, eventType)
                .counter().count();
    }

    /**
     * Reads one delivery timer back.
     *
     * @param eventType the stream the timer belongs to
     * @return the number of deliveries the timer recorded
     */
    private long timedDeliveries(String eventType) {
        return registry.get(ObservabilityConfig.REPLICA_PROCESSING_TIMER)
                .tag(ObservabilityConfig.EVENT_TYPE_TAG, eventType).timer().count();
    }

    @Nested
    @DisplayName("The account-state listener")
    class AccountState {

        private AccountStateChangedConsumer consumer;

        @BeforeEach
        void buildConsumer() {
            consumer = new AccountStateChangedConsumer(snapshots, processedEvents,
                    immediateTransactions(), meters, replicaGaps);
        }

        @Test
        @DisplayName("applies every replicated field and records one marker")
        void appliesEveryReplicatedFieldAndRecordsOneMarker() {
            when(processedEvents.existsById(ACCOUNT_STATE_KEY)).thenReturn(false);
            when(snapshots.applyStateChange(anyString(), any(), anyString(), any(), any(), any(),
                    any(), any())).thenReturn(1);

            consumer.onAccountStateChanged(accountMessage(), ACCOUNT_ID, acknowledgment,
                    ACCOUNT_STATE_TOPIC);

            verify(snapshots).applyStateChange(eq(ACCOUNT_ID), eq(new BigDecimal("5000.00")),
                    eq("2026-12-31"), eq(new BigDecimal("120.50")), eq(new BigDecimal("80.25")),
                    eq(EVENT_ID), eq(Instant.parse(OCCURRED_AT)), any(Instant.class));

            ArgumentCaptor<ProcessedEventEntity> marker =
                    ArgumentCaptor.forClass(ProcessedEventEntity.class);
            verify(processedEvents).save(marker.capture());
            assertThat(marker.getValue().getEventId()).isEqualTo(EVENT_ID);
            assertThat(marker.getValue().getConsumedTopic()).isEqualTo(ACCOUNT_STATE_TOPIC);
            verify(acknowledgment).acknowledge();
        }

        @Test
        @DisplayName("applies nothing on a repeat delivery and still acknowledges")
        void appliesNothingOnARepeatDeliveryAndStillAcknowledges() {
            when(processedEvents.existsById(ACCOUNT_STATE_KEY)).thenReturn(true);

            consumer.onAccountStateChanged(accountMessage(), ACCOUNT_ID, acknowledgment,
                    ACCOUNT_STATE_TOPIC);

            verify(snapshots, never()).applyStateChange(anyString(), any(), anyString(), any(),
                    any(), any(), any(), any());
            verify(processedEvents, never()).save(any());
            verify(acknowledgment).acknowledge();
        }

        /**
         * A row already carrying a newer change answers zero, which is the upsert's own newer-wins
         * guard and not a failure. The marker still records, because this delivery has been dealt with
         * and a redelivery would reach the same guard.
         */
        @Test
        @DisplayName("records the marker when a newer change already holds the row")
        void recordsTheMarkerWhenANewerChangeAlreadyHoldsTheRow() {
            when(processedEvents.existsById(ACCOUNT_STATE_KEY)).thenReturn(false);
            when(snapshots.applyStateChange(anyString(), any(), anyString(), any(), any(), any(),
                    any(), any())).thenReturn(0);

            consumer.onAccountStateChanged(accountMessage(), ACCOUNT_ID, acknowledgment,
                    ACCOUNT_STATE_TOPIC);

            verify(processedEvents).save(any(ProcessedEventEntity.class));
            verify(acknowledgment).acknowledge();
        }

        /**
         * The line written when nothing applied names the event and never the account.
         *
         * <p>This is the one path that logs at {@code INFO} on an ordinary day, so it is the path
         * that decides whether account identifiers reach a log collector at all.
         */
        @Test
        @DisplayName("names the event and not the account when it applied nothing")
        void namesTheEventAndNotTheAccountWhenItAppliedNothing() {
            when(processedEvents.existsById(ACCOUNT_STATE_KEY)).thenReturn(false);
            when(snapshots.applyStateChange(anyString(), any(), anyString(), any(), any(), any(),
                    any(), any())).thenReturn(0);

            List<ILoggingEvent> lines = recordedLines(() ->
                    consumer.onAccountStateChanged(accountMessage(), ACCOUNT_ID, acknowledgment,
                            ACCOUNT_STATE_TOPIC));

            assertThat(lines).as("the path that applied nothing writes one line").isNotEmpty();
            assertThat(lines).allSatisfy(line -> {
                assertThat(line.getFormattedMessage())
                        .as("an account identifier reached an ordinary log line")
                        .doesNotContain(ACCOUNT_ID);
                assertThat(line.getThrowableProxy())
                        .as("no throwable is attached to this outcome")
                        .isNull();
            });
            assertThat(lines).anySatisfy(line ->
                    assertThat(line.getFormattedMessage())
                            .as("the event identifier is the correlation value a reader follows")
                            .contains(EVENT_ID.toString()));
        }

        @Test
        @DisplayName("acknowledges a proven duplicate and rethrows every other integrity failure")
        void acknowledgesAProvenDuplicateAndRethrowsEveryOther() {
            when(processedEvents.existsById(ACCOUNT_STATE_KEY)).thenReturn(false, true);
            when(snapshots.applyStateChange(anyString(), any(), anyString(), any(), any(), any(),
                    any(), any())).thenThrow(new DataIntegrityViolationException("marker key"));

            consumer.onAccountStateChanged(accountMessage(), ACCOUNT_ID, acknowledgment,
                    ACCOUNT_STATE_TOPIC);
            verify(acknowledgment).acknowledge();

            Acknowledgment second = Mockito.mock(Acknowledgment.class);
            when(processedEvents.existsById(ACCOUNT_STATE_KEY)).thenReturn(false, false);

            assertThatThrownBy(() -> consumer.onAccountStateChanged(accountMessage(), ACCOUNT_ID,
                    second,
                    ACCOUNT_STATE_TOPIC))
                    .isInstanceOf(DataIntegrityViolationException.class);
            verifyNoInteractions(second);
        }

        @Test
        @DisplayName("refuses a tombstone, which this contract never produces")
        void refusesATombstone() {
            assertThatThrownBy(() ->
                    consumer.onAccountStateChanged(null, ACCOUNT_ID,
                            acknowledgment, ACCOUNT_STATE_TOPIC))
                    .isInstanceOf(IllegalArgumentException.class);
            verifyNoInteractions(acknowledgment);
        }


        /**
         * A delivery is counted as it arrives, timed whichever way it ends, and a repeat is counted as
         * a duplicate.
         *
         * <p>The consumed count is what makes a stream that stopped arriving visible, and the
         * duplicate count is what tells a stream arriving entirely as repeats apart from one applying
         * changes. Neither reading existed while this listener recorded nothing, so an authorization
         * taken against a replica no event had refreshed for hours was the first sign of either.
         */
        @Test
        @DisplayName("counts and times one applied delivery")
        void countsAndTimesOneAppliedDelivery() {
            when(processedEvents.existsById(ACCOUNT_STATE_KEY)).thenReturn(false);
            when(snapshots.applyStateChange(anyString(), any(), anyString(), any(), any(), any(),
                    any(), any())).thenReturn(1);

            consumer.onAccountStateChanged(accountMessage(), ACCOUNT_ID, acknowledgment,
                    ACCOUNT_STATE_TOPIC);

            assertThat(counter(ObservabilityConfig.EVENTS_CONSUMED_COUNTER,
                    AccountStateChanged.EVENT_TYPE)).isEqualTo(1.0d);
            assertThat(counter(ObservabilityConfig.DUPLICATES_SKIPPED_COUNTER,
                    AccountStateChanged.EVENT_TYPE)).isZero();
            assertThat(timedDeliveries(AccountStateChanged.EVENT_TYPE)).isEqualTo(1L);
        }

        @Test
        @DisplayName("counts a repeat delivery as a duplicate and still times it")
        void countsARepeatDeliveryAsADuplicate() {
            when(processedEvents.existsById(ACCOUNT_STATE_KEY)).thenReturn(true);

            consumer.onAccountStateChanged(accountMessage(), ACCOUNT_ID, acknowledgment,
                    ACCOUNT_STATE_TOPIC);

            assertThat(counter(ObservabilityConfig.EVENTS_CONSUMED_COUNTER,
                    AccountStateChanged.EVENT_TYPE)).isEqualTo(1.0d);
            assertThat(counter(ObservabilityConfig.DUPLICATES_SKIPPED_COUNTER,
                    AccountStateChanged.EVENT_TYPE)).isEqualTo(1.0d);
            assertThat(timedDeliveries(AccountStateChanged.EVENT_TYPE)).isEqualTo(1L);
        }

        @Test
        @DisplayName("counts and times a delivery it refuses, so a dead letter is never the first sign")
        void countsAndTimesARefusedDelivery() {
            assertThatThrownBy(() ->
                    consumer.onAccountStateChanged(null, ACCOUNT_ID, acknowledgment,
                            ACCOUNT_STATE_TOPIC))
                    .isInstanceOf(IllegalArgumentException.class);

            assertThat(counter(ObservabilityConfig.EVENTS_CONSUMED_COUNTER,
                    AccountStateChanged.EVENT_TYPE)).isEqualTo(1.0d);
            assertThat(timedDeliveries(AccountStateChanged.EVENT_TYPE)).isEqualTo(1L);
        }

        @Test
        @DisplayName("leaves the other stream's series untouched")
        void leavesTheOtherStreamsSeriesUntouched() {
            when(processedEvents.existsById(ACCOUNT_STATE_KEY)).thenReturn(false);

            consumer.onAccountStateChanged(accountMessage(), ACCOUNT_ID, acknowledgment,
                    ACCOUNT_STATE_TOPIC);

            assertThat(counter(ObservabilityConfig.EVENTS_CONSUMED_COUNTER, CardUpdated.EVENT_TYPE))
                    .as("one series per stream, so a busy stream never hides a stalled one")
                    .isZero();
        }

        @Test
        @DisplayName("takes its topic and group from configuration")
        void takesItsTopicAndGroupFromConfiguration() {
            KafkaListener listener = listenerOn(AccountStateChangedConsumer.class,
                    "onAccountStateChanged");

            assertThat(listener.topics())
                    .containsExactly("${carddemo.kafka.topics.account-state-changed}");
            assertThat(listener.groupId())
                    .isEqualTo("${carddemo.kafka.groups.account-state-changed}");
        }
    }

    @Nested
    @DisplayName("The card-update listener")
    class CardUpdate {

        private CardUpdatedConsumer consumer;

        @BeforeEach
        void buildConsumer() {
            consumer = new CardUpdatedConsumer(crossReferences, processedEvents,
                    immediateTransactions(), meters, replicaGaps);
        }

        /**
         * The message carries a masked card number and this table is keyed by the full sixteen
         * characters, so the refresh matches on the account and on the four digits the mask leaves
         * visible. It writes no mapping field, which is what makes it safe.
         */
        @Test
        @DisplayName("refreshes on the account and the visible digits, and records one marker")
        void refreshesOnTheAccountAndTheVisibleDigits() {
            when(processedEvents.existsById(CARD_UPDATED_KEY)).thenReturn(false);
            when(crossReferences.refreshObservation(anyString(), anyString(), any(), any(), any()))
                    .thenReturn(1);

            consumer.onCardUpdated(cardMessage(), ACCOUNT_ID, acknowledgment, CARD_UPDATED_TOPIC);

            verify(crossReferences).refreshObservation(eq(ACCOUNT_ID), eq("%7065"), eq(EVENT_ID),
                    eq(Instant.parse(OCCURRED_AT)), any(Instant.class));
            verify(processedEvents).save(any(ProcessedEventEntity.class));
            verify(acknowledgment).acknowledge();
        }

        /**
         * A refresh that matches no row is reported and is not a failure. An absent cross-reference row
         * is already reject reason {@code 0100} at {@code app/cbl/CBTRN02C.cbl:L385-L387}, so there is
         * nothing here to repair and nothing to retry.
         */
        @Test
        @DisplayName("records the marker when the account has no matching row")
        void recordsTheMarkerWhenTheAccountHasNoMatchingRow() {
            when(processedEvents.existsById(CARD_UPDATED_KEY)).thenReturn(false);
            when(crossReferences.refreshObservation(anyString(), anyString(), any(), any(), any()))
                    .thenReturn(0);

            consumer.onCardUpdated(cardMessage(), ACCOUNT_ID, acknowledgment, CARD_UPDATED_TOPIC);

            verify(processedEvents).save(any(ProcessedEventEntity.class));
            verify(acknowledgment).acknowledge();
        }

        /**
         * The line written when nothing refreshed names the event and never the cardholder.
         *
         * <p>Both the account identifier and the visible digits of the card describe the cardholder,
         * so neither belongs in a line a log collector retains.
         */
        @Test
        @DisplayName("names the event and neither the account nor the digits when nothing refreshed")
        void namesTheEventAndNeitherTheAccountNorTheDigits() {
            when(processedEvents.existsById(CARD_UPDATED_KEY)).thenReturn(false);
            when(crossReferences.refreshObservation(anyString(), anyString(), any(), any(), any()))
                    .thenReturn(0);

            List<ILoggingEvent> lines = recordedLines(() ->
                    consumer.onCardUpdated(cardMessage(), ACCOUNT_ID,
                            acknowledgment, CARD_UPDATED_TOPIC));

            assertThat(lines).as("the path that refreshed nothing writes one line").isNotEmpty();
            assertThat(lines).allSatisfy(line -> {
                assertThat(line.getFormattedMessage())
                        .as("an account identifier reached an ordinary log line")
                        .doesNotContain(ACCOUNT_ID);
                assertThat(line.getFormattedMessage())
                        .as("the visible digits of a card reached an ordinary log line")
                        .doesNotContain(CardUpdated.from(cardMessage()).visibleDigitsSuffix());
                assertThat(line.getThrowableProxy())
                        .as("no throwable is attached to this outcome")
                        .isNull();
            });
            assertThat(lines).anySatisfy(line ->
                    assertThat(line.getFormattedMessage())
                            .as("the event identifier is the correlation value a reader follows")
                            .contains(EVENT_ID.toString()));
        }

        @Test
        @DisplayName("refreshes nothing on a repeat delivery and still acknowledges")
        void refreshesNothingOnARepeatDelivery() {
            when(processedEvents.existsById(CARD_UPDATED_KEY)).thenReturn(true);

            consumer.onCardUpdated(cardMessage(), ACCOUNT_ID, acknowledgment, CARD_UPDATED_TOPIC);

            verify(crossReferences, never()).refreshObservation(anyString(), anyString(), any(),
                    any(), any());
            verify(acknowledgment).acknowledge();
        }

        @Test
        @DisplayName("rethrows when it cannot establish that a duplicate committed")
        void rethrowsWhenItCannotEstablishThatADuplicateCommitted() {
            when(processedEvents.existsById(CARD_UPDATED_KEY))
                    .thenReturn(false)
                    .thenThrow(new QueryTimeoutException("the marker read timed out"));
            when(crossReferences.refreshObservation(anyString(), anyString(), any(), any(), any()))
                    .thenThrow(new DataIntegrityViolationException("some other constraint"));

            assertThatThrownBy(() ->
                    consumer.onCardUpdated(cardMessage(), ACCOUNT_ID,
                            acknowledgment, CARD_UPDATED_TOPIC))
                    .isInstanceOf(DataIntegrityViolationException.class);
            verifyNoInteractions(acknowledgment);
        }


        @Test
        @DisplayName("counts and times one refreshing delivery")
        void countsAndTimesOneRefreshingDelivery() {
            when(processedEvents.existsById(CARD_UPDATED_KEY)).thenReturn(false);
            when(crossReferences.refreshObservation(anyString(), anyString(), any(), any(), any()))
                    .thenReturn(1);

            consumer.onCardUpdated(cardMessage(), ACCOUNT_ID, acknowledgment, CARD_UPDATED_TOPIC);

            assertThat(counter(ObservabilityConfig.EVENTS_CONSUMED_COUNTER, CardUpdated.EVENT_TYPE))
                    .isEqualTo(1.0d);
            assertThat(timedDeliveries(CardUpdated.EVENT_TYPE)).isEqualTo(1L);
        }

        @Test
        @DisplayName("counts a repeat delivery as a duplicate")
        void countsARepeatDeliveryAsADuplicate() {
            when(processedEvents.existsById(CARD_UPDATED_KEY)).thenReturn(true);

            consumer.onCardUpdated(cardMessage(), ACCOUNT_ID, acknowledgment, CARD_UPDATED_TOPIC);

            assertThat(counter(ObservabilityConfig.DUPLICATES_SKIPPED_COUNTER,
                    CardUpdated.EVENT_TYPE)).isEqualTo(1.0d);
            assertThat(timedDeliveries(CardUpdated.EVENT_TYPE)).isEqualTo(1L);
        }

        @Test
        @DisplayName("counts and times a delivery it refuses")
        void countsAndTimesARefusedDelivery() {
            assertThatThrownBy(() -> consumer.onCardUpdated(null, ACCOUNT_ID, acknowledgment,
                    CARD_UPDATED_TOPIC)).isInstanceOf(IllegalArgumentException.class);

            assertThat(counter(ObservabilityConfig.EVENTS_CONSUMED_COUNTER, CardUpdated.EVENT_TYPE))
                    .isEqualTo(1.0d);
            assertThat(timedDeliveries(CardUpdated.EVENT_TYPE)).isEqualTo(1L);
        }

        @Test
        @DisplayName("takes its topic and group from configuration")
        void takesItsTopicAndGroupFromConfiguration() {
            KafkaListener listener = listenerOn(CardUpdatedConsumer.class, "onCardUpdated");

            assertThat(listener.topics()).containsExactly("${carddemo.kafka.topics.card-updated}");
            assertThat(listener.groupId()).isEqualTo("${carddemo.kafka.groups.card-updated}");
        }
    }

    @Nested
    @DisplayName("The payload records")
    class PayloadRecords {

        @Test
        @DisplayName("hold the money components at the scale the replica columns declare")
        void holdTheMoneyComponentsAtTheDeclaredScale() {
            AccountStateChanged event = AccountStateChanged.from(accountMessage());

            assertThat(event.creditLimit().scale()).isEqualTo(2);
            assertThat(event.currentCycleCredit().scale()).isEqualTo(2);
            assertThat(event.currentCycleDebit().scale()).isEqualTo(2);
        }

        /**
         * A monetary value must never be read from a JSON number. Most readers parse one into a binary
         * floating-point double, and the whole correctness argument of this platform rests on that never
         * happening to an amount, so the schema sends money as a decimal string and this record reads
         * text.
         */
        @Test
        @DisplayName("refuse a money component that is not a decimal string of two places")
        void refuseAMoneyComponentThatIsNotADecimalStringOfTwoPlaces() {
            JsonNode wrongScale = MAPPER.readTree(accountJson().replace("\"5000.00\"", "\"5000.0\""));

            assertThatThrownBy(() -> AccountStateChanged.from(wrongScale))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("scale");
        }

        @Test
        @DisplayName("refuse an unmasked card number, which would be a full card number")
        void refuseAnUnmaskedCardNumber() {
            JsonNode unmasked =
                    MAPPER.readTree(cardJson().replace("************7065", "4111111111117065"));

            assertThatThrownBy(() -> CardUpdated.from(unmasked))
                    .isInstanceOf(IllegalArgumentException.class)
                    .satisfies(failure -> assertThat(failure.getMessage())
                            .as("the message must not repeat the value it refused")
                            .doesNotContain("4111111111117065"));
        }

        @Test
        @DisplayName("derive the suffix a refresh matches on from the visible digits alone")
        void deriveTheSuffixFromTheVisibleDigitsAlone() {
            assertThat(CardUpdated.from(cardMessage()).visibleDigitsSuffix()).isEqualTo("%7065");
        }

        @Test
        @DisplayName("name the absent property when one is missing")
        void nameTheAbsentPropertyWhenOneIsMissing() {
            JsonNode withoutLimit =
                    MAPPER.readTree(accountJson().replace("\"creditLimit\"", "\"notTheLimit\""));

            assertThatThrownBy(() -> AccountStateChanged.from(withoutLimit))
                    .isInstanceOf(NullPointerException.class)
                    .hasMessageContaining("creditLimit");
        }
    }

    /** Reads the {@link KafkaListener} one named method carries. */
    private static KafkaListener listenerOn(Class<?> consumer, String methodName) {
        for (Method method : consumer.getDeclaredMethods()) {
            if (method.getName().equals(methodName)) {
                KafkaListener listener = method.getAnnotation(KafkaListener.class);
                assertThat(listener).as("@KafkaListener on %s", methodName).isNotNull();
                return listener;
            }
        }
        throw new AssertionError(methodName + " must stay a method of " + consumer.getSimpleName());
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

    /** One account state change, in the shape the schema document governs. */
    private static String accountJson() {
        return """
                {
                  "eventId": "%s",
                  "eventType": "AccountStateChanged",
                  "schemaVersion": 1,
                  "occurredAt": "%s",
                  "aggregateId": "%s",
                  "accountId": "%s",
                  "creditLimit": "5000.00",
                  "currentCycleCredit": "120.50",
                  "currentCycleDebit": "80.25",
                  "expirationDate": "2026-12-31",
                  "changeKind": "ACCOUNT_UPDATED"
                }
                """.formatted(EVENT_ID, OCCURRED_AT, ACCOUNT_ID, ACCOUNT_ID);
    }

    /** One card update, in the shape the schema document governs. */
    private static String cardJson() {
        return """
                {
                  "eventId": "%s",
                  "eventType": "CardUpdated",
                  "schemaVersion": 1,
                  "occurredAt": "%s",
                  "aggregateId": "%s",
                  "accountId": "%s",
                  "maskedCardNumber": "************7065",
                  "expirationDate": "2026-12-31",
                  "activeStatus": "Y"
                }
                """.formatted(EVENT_ID, OCCURRED_AT, ACCOUNT_ID, ACCOUNT_ID);
    }

    /**
     * Runs one delivery with a recorder attached to the package every service logs under.
     *
     * @param delivery the call whose log lines are wanted
     * @return every line written during it, in order
     */
    private static List<ILoggingEvent> recordedLines(Runnable delivery) {
        ListAppender<ILoggingEvent> recorder = new ListAppender<>();
        recorder.setContext((LoggerContext) LoggerFactory.getILoggerFactory());
        recorder.start();
        Logger serviceLogger = (Logger) LoggerFactory.getLogger("com.carddemo");
        serviceLogger.addAppender(recorder);
        try {
            delivery.run();
        } finally {
            serviceLogger.detachAppender(recorder);
            recorder.stop();
        }
        return List.copyOf(recorder.list);
    }

    /** The account fixture as the checked tree a listener receives. */
    private static JsonNode accountMessage() {
        return MAPPER.readTree(accountJson());
    }

    /** The card fixture as the checked tree a listener receives. */
    private static JsonNode cardMessage() {
        return MAPPER.readTree(cardJson());
    }

    @Nested
    @DisplayName("The message key has to name the account each payload names")
    class MessageKeyGuard {

        private AccountStateChangedConsumer accountConsumer;
        private CardUpdatedConsumer cardConsumer;

        @BeforeEach
        void buildConsumers() {
            accountConsumer = new AccountStateChangedConsumer(snapshots, processedEvents,
                    immediateTransactions(), meters, replicaGaps);
            cardConsumer = new CardUpdatedConsumer(crossReferences, processedEvents,
                    immediateTransactions(), meters, replicaGaps);
        }

        /**
         * A record keyed on another account arrived on a partition that does not order this
         * account's events. Applying it would move a credit limit or an expiry onto another
         * account's snapshot, and the four decline rules would then decide that account's
         * transactions from a value that never belonged to it.
         */
        @Test
        @DisplayName("an account-state record keyed on another account is refused and writes"
                + " nothing")
        void anAccountStateRecordKeyedOnAnotherAccountIsRefused() {
            assertThatThrownBy(() -> accountConsumer.onAccountStateChanged(accountMessage(),
                    "00000000099", acknowledgment, ACCOUNT_STATE_TOPIC))
                    .isInstanceOf(IllegalArgumentException.class);

            verifyNoInteractions(snapshots);
            verifyNoInteractions(processedEvents);
            verifyNoInteractions(acknowledgment);
        }

        /** A card update keyed on another account would refresh another account's rows. */
        @Test
        @DisplayName("a card-update record keyed on another account is refused and writes nothing")
        void aCardUpdateRecordKeyedOnAnotherAccountIsRefused() {
            assertThatThrownBy(() -> cardConsumer.onCardUpdated(cardMessage(), "00000000099",
                    acknowledgment, CARD_UPDATED_TOPIC))
                    .isInstanceOf(IllegalArgumentException.class);

            verifyNoInteractions(crossReferences);
            verifyNoInteractions(processedEvents);
            verifyNoInteractions(acknowledgment);
        }

        /** A record with no key at all was partitioned at random. */
        @Test
        @DisplayName("a record carrying no key is refused on both streams")
        void aRecordCarryingNoKeyIsRefusedOnBothStreams() {
            assertThatThrownBy(() -> accountConsumer.onAccountStateChanged(accountMessage(), null,
                    acknowledgment, ACCOUNT_STATE_TOPIC))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> cardConsumer.onCardUpdated(cardMessage(), null, acknowledgment,
                    CARD_UPDATED_TOPIC))
                    .isInstanceOf(IllegalArgumentException.class);

            verifyNoInteractions(snapshots);
            verifyNoInteractions(crossReferences);
            verifyNoInteractions(acknowledgment);
        }

        /** A blank key names nothing, so it is refused for the same reason as an absent one. */
        @Test
        @DisplayName("a blank key is refused on both streams")
        void aBlankKeyIsRefusedOnBothStreams() {
            assertThatThrownBy(() -> accountConsumer.onAccountStateChanged(accountMessage(), "  ",
                    acknowledgment, ACCOUNT_STATE_TOPIC))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> cardConsumer.onCardUpdated(cardMessage(), "  ", acknowledgment,
                    CARD_UPDATED_TOPIC))
                    .isInstanceOf(IllegalArgumentException.class);

            verifyNoInteractions(snapshots);
            verifyNoInteractions(crossReferences);
        }

        /**
         * A key agreeing with the envelope while the payload names another account would route
         * correctly and write to the wrong row, so all three values are compared rather than two.
         */
        @Test
        @DisplayName("an envelope and a payload that disagree are refused on both streams")
        void anEnvelopeAndPayloadThatDisagreeAreRefusedOnBothStreams() {
            ObjectNode account = (ObjectNode) accountMessage();
            account.put("accountId", "00000000099");
            ObjectNode card = (ObjectNode) cardMessage();
            card.put("accountId", "00000000099");

            assertThatThrownBy(() -> accountConsumer.onAccountStateChanged(account, ACCOUNT_ID,
                    acknowledgment, ACCOUNT_STATE_TOPIC))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> cardConsumer.onCardUpdated(card, ACCOUNT_ID, acknowledgment,
                    CARD_UPDATED_TOPIC))
                    .isInstanceOf(IllegalArgumentException.class);

            verifyNoInteractions(snapshots);
            verifyNoInteractions(crossReferences);
        }

        /** No refusal message names the key, the aggregate or the account it refused. */
        @Test
        @DisplayName("no refusal message names an identifier")
        void noRefusalMessageNamesAnIdentifier() {
            assertThatThrownBy(() -> accountConsumer.onAccountStateChanged(accountMessage(),
                    "00000000099", acknowledgment, ACCOUNT_STATE_TOPIC))
                    .hasMessageNotContaining(ACCOUNT_ID)
                    .hasMessageNotContaining("00000000099");
            assertThatThrownBy(() -> cardConsumer.onCardUpdated(cardMessage(), "00000000099",
                    acknowledgment, CARD_UPDATED_TOPIC))
                    .hasMessageNotContaining(ACCOUNT_ID)
                    .hasMessageNotContaining("00000000099");
        }
    }
}
