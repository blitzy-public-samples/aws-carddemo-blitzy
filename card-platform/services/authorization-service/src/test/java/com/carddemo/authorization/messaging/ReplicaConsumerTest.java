package com.carddemo.authorization.messaging;

import com.carddemo.authorization.entity.ProcessedEventEntity;
import com.carddemo.authorization.repository.AccountCreditSnapshotRepository;
import com.carddemo.authorization.repository.CardCrossReferenceRepository;
import com.carddemo.authorization.repository.ProcessedEventRepository;

import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.time.Instant;
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

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
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
 * <p>Both listeners are held to the same four properties: the apply and its marker reach the store
 * together, a repeat delivery applies nothing, a proven duplicate acknowledges while every other
 * integrity failure is rethrown so the record retries, and the topic and group come from configuration
 * rather than from a literal.
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

    private AccountCreditSnapshotRepository snapshots;
    private CardCrossReferenceRepository crossReferences;
    private ProcessedEventRepository processedEvents;
    private Acknowledgment acknowledgment;

    @BeforeEach
    void buildCollaborators() {
        snapshots = Mockito.mock(AccountCreditSnapshotRepository.class);
        crossReferences = Mockito.mock(CardCrossReferenceRepository.class);
        processedEvents = Mockito.mock(ProcessedEventRepository.class);
        acknowledgment = Mockito.mock(Acknowledgment.class);
    }

    @Nested
    @DisplayName("The account-state listener")
    class AccountState {

        private AccountStateChangedConsumer consumer;

        @BeforeEach
        void buildConsumer() {
            consumer = new AccountStateChangedConsumer(snapshots, processedEvents,
                    immediateTransactions());
        }

        @Test
        @DisplayName("applies every replicated field and records one marker")
        void appliesEveryReplicatedFieldAndRecordsOneMarker() {
            when(processedEvents.existsById(EVENT_ID)).thenReturn(false);
            when(snapshots.applyStateChange(anyString(), any(), anyString(), any(), any(), any(),
                    any(), any())).thenReturn(1);

            consumer.onAccountStateChanged(accountMessage(), acknowledgment,
                    "account.state-changed");

            verify(snapshots).applyStateChange(eq(ACCOUNT_ID), eq(new BigDecimal("5000.00")),
                    eq("2026-12-31"), eq(new BigDecimal("120.50")), eq(new BigDecimal("80.25")),
                    eq(EVENT_ID), eq(Instant.parse(OCCURRED_AT)), any(Instant.class));

            ArgumentCaptor<ProcessedEventEntity> marker =
                    ArgumentCaptor.forClass(ProcessedEventEntity.class);
            verify(processedEvents).save(marker.capture());
            assertThat(marker.getValue().getEventId()).isEqualTo(EVENT_ID);
            assertThat(marker.getValue().getConsumedTopic()).isEqualTo("account.state-changed");
            verify(acknowledgment).acknowledge();
        }

        @Test
        @DisplayName("applies nothing on a repeat delivery and still acknowledges")
        void appliesNothingOnARepeatDeliveryAndStillAcknowledges() {
            when(processedEvents.existsById(EVENT_ID)).thenReturn(true);

            consumer.onAccountStateChanged(accountMessage(), acknowledgment,
                    "account.state-changed");

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
            when(processedEvents.existsById(EVENT_ID)).thenReturn(false);
            when(snapshots.applyStateChange(anyString(), any(), anyString(), any(), any(), any(),
                    any(), any())).thenReturn(0);

            consumer.onAccountStateChanged(accountMessage(), acknowledgment,
                    "account.state-changed");

            verify(processedEvents).save(any(ProcessedEventEntity.class));
            verify(acknowledgment).acknowledge();
        }

        @Test
        @DisplayName("acknowledges a proven duplicate and rethrows every other integrity failure")
        void acknowledgesAProvenDuplicateAndRethrowsEveryOther() {
            when(processedEvents.existsById(EVENT_ID)).thenReturn(false, true);
            when(snapshots.applyStateChange(anyString(), any(), anyString(), any(), any(), any(),
                    any(), any())).thenThrow(new DataIntegrityViolationException("marker key"));

            consumer.onAccountStateChanged(accountMessage(), acknowledgment,
                    "account.state-changed");
            verify(acknowledgment).acknowledge();

            Acknowledgment second = Mockito.mock(Acknowledgment.class);
            when(processedEvents.existsById(EVENT_ID)).thenReturn(false, false);

            assertThatThrownBy(() -> consumer.onAccountStateChanged(accountMessage(), second,
                    "account.state-changed"))
                    .isInstanceOf(DataIntegrityViolationException.class);
            verifyNoInteractions(second);
        }

        @Test
        @DisplayName("refuses a tombstone, which this contract never produces")
        void refusesATombstone() {
            assertThatThrownBy(() ->
                    consumer.onAccountStateChanged(null, acknowledgment, "account.state-changed"))
                    .isInstanceOf(IllegalArgumentException.class);
            verifyNoInteractions(acknowledgment);
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
                    immediateTransactions());
        }

        /**
         * The message carries a masked card number and this table is keyed by the full sixteen
         * characters, so the refresh matches on the account and on the four digits the mask leaves
         * visible. It writes no mapping field, which is what makes it safe.
         */
        @Test
        @DisplayName("refreshes on the account and the visible digits, and records one marker")
        void refreshesOnTheAccountAndTheVisibleDigits() {
            when(processedEvents.existsById(EVENT_ID)).thenReturn(false);
            when(crossReferences.refreshObservation(anyString(), anyString(), any(), any(), any()))
                    .thenReturn(1);

            consumer.onCardUpdated(cardMessage(), acknowledgment, "card.updated");

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
            when(processedEvents.existsById(EVENT_ID)).thenReturn(false);
            when(crossReferences.refreshObservation(anyString(), anyString(), any(), any(), any()))
                    .thenReturn(0);

            consumer.onCardUpdated(cardMessage(), acknowledgment, "card.updated");

            verify(processedEvents).save(any(ProcessedEventEntity.class));
            verify(acknowledgment).acknowledge();
        }

        @Test
        @DisplayName("refreshes nothing on a repeat delivery and still acknowledges")
        void refreshesNothingOnARepeatDelivery() {
            when(processedEvents.existsById(EVENT_ID)).thenReturn(true);

            consumer.onCardUpdated(cardMessage(), acknowledgment, "card.updated");

            verify(crossReferences, never()).refreshObservation(anyString(), anyString(), any(),
                    any(), any());
            verify(acknowledgment).acknowledge();
        }

        @Test
        @DisplayName("rethrows when it cannot establish that a duplicate committed")
        void rethrowsWhenItCannotEstablishThatADuplicateCommitted() {
            when(processedEvents.existsById(EVENT_ID))
                    .thenReturn(false)
                    .thenThrow(new QueryTimeoutException("the marker read timed out"));
            when(crossReferences.refreshObservation(anyString(), anyString(), any(), any(), any()))
                    .thenThrow(new DataIntegrityViolationException("some other constraint"));

            assertThatThrownBy(() ->
                    consumer.onCardUpdated(cardMessage(), acknowledgment, "card.updated"))
                    .isInstanceOf(DataIntegrityViolationException.class);
            verifyNoInteractions(acknowledgment);
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
                  "embossedName": "PAULA A DAVIS",
                  "expirationDate": "2026-12-31",
                  "activeStatus": "Y"
                }
                """.formatted(EVENT_ID, OCCURRED_AT, ACCOUNT_ID, ACCOUNT_ID);
    }

    /** The account fixture as the checked tree a listener receives. */
    private static JsonNode accountMessage() {
        return MAPPER.readTree(accountJson());
    }

    /** The card fixture as the checked tree a listener receives. */
    private static JsonNode cardMessage() {
        return MAPPER.readTree(cardJson());
    }
}
