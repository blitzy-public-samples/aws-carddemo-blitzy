package com.carddemo.fraud.messaging;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.carddemo.events.FraudCleared;
import com.carddemo.events.FraudFlagged;
import com.carddemo.events.TransactionAuthorized;
import com.carddemo.fraud.config.ObservabilityConfig.FraudMeters;
import com.carddemo.fraud.domain.RiskScoringService;
import com.carddemo.fraud.domain.RiskScoringService.RiskAssessment;
import com.carddemo.fraud.entity.FraudAssessmentEntity;
import com.carddemo.fraud.entity.OutboxEventEntity;
import com.carddemo.fraud.entity.VelocityWindowEntity;
import com.carddemo.fraud.outbox.OutboxWriter;
import com.carddemo.fraud.repository.FraudAssessmentRepository;
import com.carddemo.fraud.repository.OutboxEventRepository;
import com.carddemo.fraud.repository.ProcessedEventRepository;
import com.carddemo.fraud.repository.VelocityWindowRepository;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Verifies the fraud consumer's guard, effects, marker, transaction and acknowledgement order.
 * ADDITIVE IN FULL: net new; no COBOL ancestor.
 */
@DisplayName("Fraud TransactionAuthorized consumer")
class TransactionAuthorizedConsumerTest {

    private static final String SOURCE_TOPIC = "transaction.authorized";
    private static final String ACCOUNT_ID = "00000000007";
    private static final String TRANSACTION_ID = "CONSUMER-CASE001";
    private static final Instant OCCURRED_AT = Instant.parse("2026-08-04T14:00:00Z");
    private static final Instant ASSESSED_AT = Instant.parse("2026-08-04T14:00:01Z");
    private static final BigDecimal AMOUNT = new BigDecimal("10.00");

    private RiskScoringService riskScoring;
    private FraudAssessmentRepository assessments;
    private ProcessedEventRepository processedEvents;
    private OutboxWriter outboxWriter;
    private FraudMeters meters;
    private TransactionAuthorizedConsumer consumer;

    @BeforeEach
    void buildConsumer() {
        riskScoring = mock(RiskScoringService.class);
        assessments = mock(FraudAssessmentRepository.class);
        processedEvents = mock(ProcessedEventRepository.class);
        outboxWriter = mock(OutboxWriter.class);
        meters = mock(FraudMeters.class);
        SelfHandle self = new SelfHandle();
        consumer = new TransactionAuthorizedConsumer(
                riskScoring, assessments, processedEvents, outboxWriter, meters, self);
        self.target.set(consumer);
    }

    @Nested
    @DisplayName("Listener and transaction contract")
    class ListenerContract {

        @Test
        @DisplayName("the listener names the authorized topic, fraud group and shipped factory")
        void listenerNamesTheShippedBindings() throws NoSuchMethodException {
            KafkaListener listener = listenerMethod().getAnnotation(KafkaListener.class);

            assertNotNull(listener, "listener annotation");
            assertEquals(List.of(
                    "${carddemo.kafka.topics.transaction-authorized:transaction.authorized}"),
                    List.of(listener.topics()), "topics");
            assertEquals("${spring.kafka.consumer.group-id:fraud-detection}",
                    listener.groupId(), "group");
            assertEquals("kafkaListenerContainerFactory",
                    listener.containerFactory(), "factory");
        }

        @Test
        @DisplayName("the business method owns one required transaction")
        void businessMethodOwnsTheTransaction() throws NoSuchMethodException {
            Method method = TransactionAuthorizedConsumer.class.getMethod(
                    "assessOneEvent", TransactionAuthorized.class, String.class);
            Transactional boundary = method.getAnnotation(Transactional.class);

            assertNotNull(boundary, "transactional boundary");
            assertEquals(Propagation.REQUIRED, boundary.propagation(), "propagation");
        }

        @Test
        @DisplayName("the listener itself opens no transaction")
        void listenerOpensNoTransaction() throws NoSuchMethodException {
            assertFalse(listenerMethod().isAnnotationPresent(Transactional.class),
                    "listener transaction");
        }

        @Test
        @DisplayName("the consumer holds no collaborator from another service or HTTP client")
        void consumerReachesNoOtherService() {
            List<String> forbiddenPrefixes = List.of(
                    "com.carddemo.authorization",
                    "com.carddemo.ledger",
                    "com.carddemo.notification",
                    "com.carddemo.account",
                    "com.carddemo.card.");
            List<String> forbiddenTypes = List.of(
                    "org.springframework.web.client.RestTemplate",
                    "org.springframework.web.client.RestClient",
                    "org.springframework.web.reactive.function.client.WebClient");

            for (Field field : TransactionAuthorizedConsumer.class.getDeclaredFields()) {
                String type = field.getType().getName();
                assertFalse(forbiddenPrefixes.stream().anyMatch(type::startsWith), type);
                assertFalse(forbiddenTypes.contains(type), type);
            }
        }

        private Method listenerMethod() throws NoSuchMethodException {
            return TransactionAuthorizedConsumer.class.getMethod(
                    "onTransactionAuthorized", ConsumerRecord.class, Acknowledgment.class);
        }
    }

    @Nested
    @DisplayName("One successful delivery")
    class SuccessfulDelivery {

        @Test
        @DisplayName("the guard precedes scoring, both writes and the marker")
        void guardPrecedesEveryEffect() {
            TransactionAuthorized event = authorized(1L, TRANSACTION_ID);
            markerAbsent(event);
            when(riskScoring.assess(event)).thenReturn(flaggedAssessment(event));

            consumer.onTransactionAuthorized(delivery(event), new RecordingAcknowledgment());

            InOrder order = inOrder(processedEvents, riskScoring, assessments, outboxWriter);
            order.verify(processedEvents).claimEvent(eq(event.eventId()), any(Instant.class),
                    eq(SOURCE_TOPIC));
            order.verify(riskScoring).assess(event);
            order.verify(assessments).save(any(FraudAssessmentEntity.class));
            order.verify(outboxWriter).write(any(FraudFlagged.class));
        }

        @Test
        @DisplayName("a flagged assessment writes one flagged event")
        void flaggedAssessmentWritesOneFlaggedEvent() {
            TransactionAuthorized event = authorized(2L, TRANSACTION_ID);
            markerAbsent(event);
            when(riskScoring.assess(event)).thenReturn(flaggedAssessment(event));

            consumer.onTransactionAuthorized(delivery(event), new RecordingAcknowledgment());

            ArgumentCaptor<Object> outgoing = ArgumentCaptor.forClass(Object.class);
            verify(outboxWriter, times(1)).write(outgoing.capture());
            FraudFlagged flagged = assertInstanceOf(
                    FraudFlagged.class, outgoing.getValue(), "outgoing event");
            assertEquals(event.transactionId(), flagged.transactionId(), "transaction");
            assertEquals(event.accountId(), flagged.accountId(), "account");
        }

        @Test
        @DisplayName("a cleared assessment writes one cleared event")
        void clearedAssessmentWritesOneClearedEvent() {
            TransactionAuthorized event = authorized(3L, TRANSACTION_ID);
            markerAbsent(event);
            when(riskScoring.assess(event)).thenReturn(clearedAssessment(event));

            consumer.onTransactionAuthorized(delivery(event), new RecordingAcknowledgment());

            ArgumentCaptor<Object> outgoing = ArgumentCaptor.forClass(Object.class);
            verify(outboxWriter, times(1)).write(outgoing.capture());
            assertInstanceOf(FraudCleared.class, outgoing.getValue(), "outgoing event");
        }

        @Test
        @DisplayName("the marker carries the event identifier and source topic")
        void markerCarriesTheEventIdentifierAndTopic() {
            TransactionAuthorized event = authorized(4L, TRANSACTION_ID);
            markerAbsent(event);
            when(riskScoring.assess(event)).thenReturn(clearedAssessment(event));

            consumer.onTransactionAuthorized(delivery(event), new RecordingAcknowledgment());

            ArgumentCaptor<Instant> processedAt = ArgumentCaptor.forClass(Instant.class);
            verify(processedEvents).claimEvent(eq(event.eventId()), processedAt.capture(),
                    eq(SOURCE_TOPIC));
            assertNotNull(processedAt.getValue(), "processed time");
        }

        @Test
        @DisplayName("one successful delivery acknowledges exactly once")
        void successfulDeliveryAcknowledgesOnce() {
            TransactionAuthorized event = authorized(5L, TRANSACTION_ID);
            markerAbsent(event);
            when(riskScoring.assess(event)).thenReturn(clearedAssessment(event));
            RecordingAcknowledgment acknowledgment = new RecordingAcknowledgment();

            consumer.onTransactionAuthorized(delivery(event), acknowledgment);

            assertEquals(1, acknowledgment.count, "acknowledgements");
        }
    }

    @Nested
    @DisplayName("Repeat delivery")
    class RepeatDelivery {

        @Test
        @DisplayName("a marked event writes no assessment, outbox row or marker")
        void markedEventWritesNothing() {
            TransactionAuthorized event = authorized(6L, TRANSACTION_ID);
            when(processedEvents.claimEvent(eq(event.eventId()), any(Instant.class),
                    eq(SOURCE_TOPIC))).thenReturn(ProcessedEventRepository.ALREADY_CLAIMED);

            consumer.onTransactionAuthorized(delivery(event), new RecordingAcknowledgment());

            verifyNoInteractions(riskScoring, assessments, outboxWriter);
            verify(processedEvents).claimEvent(eq(event.eventId()), any(Instant.class),
                    eq(SOURCE_TOPIC));
        }

        @Test
        @DisplayName("a marked event still acknowledges once")
        void markedEventAcknowledges() {
            TransactionAuthorized event = authorized(7L, TRANSACTION_ID);
            when(processedEvents.claimEvent(eq(event.eventId()), any(Instant.class),
                    eq(SOURCE_TOPIC))).thenReturn(ProcessedEventRepository.ALREADY_CLAIMED);
            RecordingAcknowledgment acknowledgment = new RecordingAcknowledgment();

            consumer.onTransactionAuthorized(delivery(event), acknowledgment);

            assertEquals(1, acknowledgment.count, "acknowledgements");
        }
    }

    @Nested
    @DisplayName("Failure leaves the offset uncommitted")
    class FailedDelivery {

        @Test
        @DisplayName("a scoring failure writes no effects and acknowledges nothing")
        void scoringFailureLeavesOffsetUncommitted() {
            TransactionAuthorized event = authorized(8L, TRANSACTION_ID);
            markerAbsent(event);
            IllegalStateException failure = new IllegalStateException("scoring unavailable");
            when(riskScoring.assess(event)).thenThrow(failure);
            RecordingAcknowledgment acknowledgment = new RecordingAcknowledgment();

            IllegalStateException raised = assertThrows(IllegalStateException.class,
                    () -> consumer.onTransactionAuthorized(delivery(event), acknowledgment));

            assertEquals(failure, raised, "failure");
            assertEquals(0, acknowledgment.count, "acknowledgements");
            verify(processedEvents).claimEvent(eq(event.eventId()), any(Instant.class),
                    eq(SOURCE_TOPIC));
            verifyNoInteractions(assessments, outboxWriter);
        }

        @Test
        @DisplayName("a delivery with no payload reaches no store and acknowledges nothing")
        void emptyDeliveryIsRefused() {
            ConsumerRecord<String, TransactionAuthorized> empty =
                    new ConsumerRecord<>(SOURCE_TOPIC, 0, 0L, ACCOUNT_ID, null);
            RecordingAcknowledgment acknowledgment = new RecordingAcknowledgment();

            assertThrows(IllegalArgumentException.class,
                    () -> consumer.onTransactionAuthorized(empty, acknowledgment));

            assertEquals(0, acknowledgment.count, "acknowledgements");
            verifyNoInteractions(riskScoring, assessments, processedEvents, outboxWriter);
        }

        @Test
        @DisplayName("missing listener arguments and a missing business event are refused")
        void missingArgumentsAreRefused() {
            assertThrows(NullPointerException.class,
                    () -> consumer.onTransactionAuthorized(null, new RecordingAcknowledgment()));
            assertThrows(NullPointerException.class,
                    () -> consumer.onTransactionAuthorized(
                            delivery(authorized(9L, TRANSACTION_ID)), null));
            assertThrows(NullPointerException.class,
                    () -> consumer.assessOneEvent(null, SOURCE_TOPIC));
        }
    }

    /**
     * Runs the shipped consumer against PostgreSQL with the listener and relay stopped.
     */
    @Nested
    @SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE, properties = {
            "spring.kafka.listener.auto-startup=false",
            "carddemo.outbox.relay.fixed-delay-ms=3600000",
            "KAFKA_SASL_PASSWORD=not-a-real-broker-password",
            "ADMIN_PASSWORD_HASH={noop}not-a-real-admin-password",
            "USER_PASSWORD_HASH={noop}not-a-real-user-password",
            "MONITORING_PASSWORD_HASH={noop}not-a-real-monitoring-password"
    })
    @DisplayName("The consumer over PostgreSQL")
    class OverPostgres {

        private static final String DATABASE = "carddemo";
        private static final String SERVICE_SCHEMA = "fraud_service";
        private static final PostgreSQLContainer POSTGRES =
                new PostgreSQLContainer("postgres:18.4")
                        .withDatabaseName(DATABASE)
                        .withUsername(DATABASE)
                        .withPassword(DATABASE);

        static {
            POSTGRES.start();
        }

        private TransactionAuthorizedConsumer wiredConsumer;
        private FraudAssessmentRepository storedAssessments;
        private OutboxEventRepository storedOutboxEvents;
        private ProcessedEventRepository storedMarkers;
        private VelocityWindowRepository storedWindows;

        @DynamicPropertySource
        static void databaseProperties(DynamicPropertyRegistry registry) {
            registry.add("spring.datasource.url", OverPostgres::jdbcUrlOnServiceSchema);
            registry.add("spring.datasource.username", POSTGRES::getUsername);
            registry.add("spring.datasource.password", POSTGRES::getPassword);
        }

        private static String jdbcUrlOnServiceSchema() {
            String url = POSTGRES.getJdbcUrl();
            return url + (url.contains("?") ? "&" : "?")
                    + "currentSchema=" + SERVICE_SCHEMA;
        }

        @BeforeEach
        void resolveBeans(ApplicationContext context) {
            wiredConsumer = context.getBean(TransactionAuthorizedConsumer.class);
            storedAssessments = context.getBean(FraudAssessmentRepository.class);
            storedOutboxEvents = context.getBean(OutboxEventRepository.class);
            storedMarkers = context.getBean(ProcessedEventRepository.class);
            storedWindows = context.getBean(VelocityWindowRepository.class);
            removeRows();
        }

        @AfterEach
        void removeRows() {
            if (storedOutboxEvents == null) {
                return;
            }
            storedOutboxEvents.deleteAllInBatch();
            storedMarkers.deleteAllInBatch();
            storedAssessments.deleteAllInBatch();
            storedWindows.deleteAllInBatch();
        }

        @Test
        @DisplayName("one delivery commits one assessment, outbox row, marker and window update")
        void oneDeliveryCommitsOneEffectOfEachKind() {
            TransactionAuthorized event = authorized(101L, "CONSUMER-CASE101");
            RecordingAcknowledgment acknowledgment = new RecordingAcknowledgment();

            wiredConsumer.onTransactionAuthorized(delivery(event), acknowledgment);

            assertEquals(1, acknowledgment.count, "acknowledgements");
            assertEquals(1L, storedAssessments.count(), "assessments");
            assertEquals(1L, storedOutboxEvents.count(), "outbox rows");
            assertEquals(1L, storedMarkers.count(), "markers");
            assertEquals(1L, storedWindows.count(), "window rows");
            assertTrue(storedMarkers.existsById(event.eventId()), "event marker");
        }

        @Test
        @DisplayName("a duplicate delivery acknowledges twice and applies every effect once")
        void duplicateDeliveryAppliesEveryEffectOnce() {
            TransactionAuthorized event = authorized(102L, "CONSUMER-CASE102");
            RecordingAcknowledgment acknowledgment = new RecordingAcknowledgment();

            wiredConsumer.onTransactionAuthorized(delivery(event), acknowledgment);
            wiredConsumer.onTransactionAuthorized(delivery(event), acknowledgment);

            VelocityWindowEntity window = storedWindows.findById(
                    new VelocityWindowEntity.VelocityWindowId(
                            ACCOUNT_ID, OCCURRED_AT.truncatedTo(ChronoUnit.HOURS)))
                    .orElseThrow();
            assertEquals(2, acknowledgment.count, "acknowledgements");
            assertEquals(1L, storedAssessments.count(), "assessments");
            assertEquals(1L, storedOutboxEvents.count(), "outbox rows");
            assertEquals(1L, storedMarkers.count(), "markers");
            assertEquals(1, window.getAuthorizationCount(), "window count");
        }

        @Test
        @DisplayName("the acknowledgement runs after commit and outside the transaction")
        void acknowledgementRunsAfterCommit() {
            TransactionAuthorized event = authorized(103L, "CONSUMER-CASE103");
            AtomicReference<Boolean> rowVisible = new AtomicReference<>();
            AtomicReference<Boolean> transactionActive = new AtomicReference<>();

            wiredConsumer.onTransactionAuthorized(delivery(event), () -> {
                rowVisible.set(storedAssessments.existsById(event.transactionId()));
                transactionActive.set(
                        TransactionSynchronizationManager.isActualTransactionActive());
            });

            assertEquals(Boolean.TRUE, rowVisible.get(), "assessment visible at acknowledgement");
            assertEquals(Boolean.FALSE, transactionActive.get(),
                    "transaction active at acknowledgement");
        }
    }

    private void markerAbsent(TransactionAuthorized event) {
        when(processedEvents.claimEvent(eq(event.eventId()), any(Instant.class),
                eq(SOURCE_TOPIC))).thenReturn(1);
    }

    private static RiskAssessment flaggedAssessment(TransactionAuthorized event) {
        return new RiskAssessment(event.transactionId(), event.accountId(), 55, true,
                List.of("VELOCITY", "MERCHANT_CATEGORY"), ASSESSED_AT);
    }

    private static RiskAssessment clearedAssessment(TransactionAuthorized event) {
        return new RiskAssessment(event.transactionId(), event.accountId(), 25, false,
                List.of("MERCHANT_CATEGORY"), ASSESSED_AT);
    }

    private static ConsumerRecord<String, TransactionAuthorized> delivery(
            TransactionAuthorized event) {
        return new ConsumerRecord<>(SOURCE_TOPIC, 0, 0L, ACCOUNT_ID, event);
    }

    private static TransactionAuthorized authorized(long eventNumber, String transactionId) {
        return new TransactionAuthorized(
                new UUID(0L, eventNumber),
                TransactionAuthorized.EVENT_TYPE,
                TransactionAuthorized.CARD_TOKEN_SCHEMA_VERSION,
                OCCURRED_AT,
                ACCOUNT_ID,
                transactionId,
                "01",
                "0003",
                "POS TERM",
                "Fraud consumer test",
                AMOUNT,
                "800000000",
                "Demo Merchant",
                "Demo City",
                "72112",
                "************0001",
                "2bf90b0da1627234a5d993f0fcaab0f2a3640f8a033bf69969de2fb60b83fa8d",
                "2026-08-04 14:00:00.000000",
                ACCOUNT_ID,
                TransactionAuthorized.CURRENCY);
    }

    private static final class SelfHandle
            implements ObjectProvider<TransactionAuthorizedConsumer> {

        private final AtomicReference<TransactionAuthorizedConsumer> target =
                new AtomicReference<>();

        @Override
        public TransactionAuthorizedConsumer getObject() {
            return target.get();
        }
    }

    private static final class RecordingAcknowledgment implements Acknowledgment {

        private int count;

        @Override
        public void acknowledge() {
            count++;
        }
    }
}