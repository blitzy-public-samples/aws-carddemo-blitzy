package com.carddemo.fraud.messaging;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import com.carddemo.events.EventEnvelope;
import com.carddemo.events.FraudCleared;
import com.carddemo.events.FraudFlagged;
import com.carddemo.events.TransactionAuthorized;
import com.carddemo.fraud.config.FraudProperties;
import com.carddemo.fraud.config.ObservabilityConfig.FraudMeters;
import com.carddemo.fraud.domain.RiskRule;
import com.carddemo.fraud.domain.RiskScoringService;
import com.carddemo.fraud.domain.RiskScoringService.RiskAssessment;
import com.carddemo.fraud.entity.FraudAssessmentEntity;
import com.carddemo.fraud.entity.ProcessedEventEntity;
import com.carddemo.fraud.outbox.OutboxWriter;
import com.carddemo.fraud.repository.FraudAssessmentRepository;
import com.carddemo.fraud.repository.OutboxEventRepository;
import com.carddemo.fraud.repository.ProcessedEventRepository;
import com.carddemo.fraud.repository.VelocityWindowRepository;
import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Converter;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.lang.annotation.Annotation;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.ParameterizedType;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.kafka.annotation.EnableKafka;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/**
 * Asserts the duplicate-delivery guard of {@link TransactionAuthorizedConsumer}, the order of the
 * work one delivery performs, and the single assessment event each consumed event produces. Fraud
 * detection has no source in Common Business Oriented Language (COBOL):
 * net new; no COBOL ancestor.
 *
 * <p>Each collaborator is a Mockito double or a small nested implementation, so these assertions
 * need no broker, no database and no application context. The scoring service is real where a
 * velocity window update is counted and stubbed where a fixed rule set fixes the outcome. The
 * marker store is stateful: its claim statement writes one row for an unseen event identifier and
 * reports zero for a repeat.
 *
 * <p>{@code card-platform/docs/decision-log.md} carries the decisions behind these shapes, and
 * every literal below is a measured feed value.
 */
@DisplayName("Fraud consumer: one event in, one assessment event out")
class TransactionAuthorizedConsumerTest {

    /** Topic the delivery arrives on, and the value the marker row records. */
    private static final String SOURCE_TOPIC = "transaction.authorized";
    /** Transaction identifier of feed record 1, sixteen characters with leading zeros. */
    private static final String TRANSACTION_ID = "0000000000683580";
    /** Account identifier of feed record 1, eleven digits, and the message key. */
    private static final String ACCOUNT_ID = "00000000007";
    /** Authorization timestamp of every feed record, twenty-six characters. */
    private static final String AUTHORIZED_AT = "2022-06-10 19:27:53.000000";
    /** The same moment as {@link #AUTHORIZED_AT}, which fixes the velocity window. */
    private static final Instant OCCURRED_AT = Instant.parse("2022-06-10T19:27:53Z");
    /** Moment a stubbed assessment reports. */
    private static final Instant ASSESSED_AT = Instant.parse("2022-06-10T19:27:54Z");
    /** Transaction type code of feed record 1. The feed carries this one and one other. */
    private static final String TRANSACTION_TYPE_CODE = "01";
    /** Merchant category code, the only value the feed carries. */
    private static final String MERCHANT_CATEGORY_CODE = "0001";
    /** Merchant identifier, the only value the feed carries. */
    private static final String MERCHANT_ID = "800000000";
    /** Merchant postal code of feed record 1. */
    private static final String MERCHANT_ZIP = "72112";
    /** Card number as the event carries it: twelve asterisks then the last four digits. */
    private static final String MASKED_CARD_NUMBER = "************7065";
    /** Version 1 of the contract carries no card token, so the component holds nothing. */
    private static final String NO_CARD_TOKEN = null;
    /** Amount of feed record 1, a decimal string. */
    private static final String POSITIVE_AMOUNT = "504.77";
    /** A negative amount the feed carries. Fifty of its three hundred records hold one. */
    private static final String NEGATIVE_AMOUNT = "-919.00";
    /** A second negative amount the feed carries. */
    private static final String SECOND_NEGATIVE_AMOUNT = "-56.77";
    /** Idempotency key both deliveries carry, a Universally Unique Identifier (UUID). */
    private static final UUID EVENT_ID = UUID.fromString("1a2b3c4d-5e6f-4a7b-8c9d-0e1f2a3b4c5d");
    /** Verdict threshold the delivered settings carry. */
    private static final int FLAG_THRESHOLD = 50;
    /** Score one rule contributes, above {@link #FLAG_THRESHOLD}. */
    private static final int FLAGGING_POINTS = 60;
    /** Score one rule contributes, below {@link #FLAG_THRESHOLD}. */
    private static final int CLEARING_POINTS = 20;
    /** What the claim statement reports for an event identifier no marker covers. */
    private static final int ONE_MARKER_ROW = 1;
    /** What the velocity statement reports for one window it opened or raised. */
    private static final int ONE_WINDOW_ROW = 1;
    /** Partition the delivery arrives on. */
    private static final int PARTITION = 0;
    /** Offset the delivery arrives at. */
    private static final long OFFSET = 0L;
    /** The four stores this service owns. */
    private static final List<Class<?>> STORES = List.of(
            FraudAssessmentRepository.class,
            ProcessedEventRepository.class,
            VelocityWindowRepository.class,
            OutboxEventRepository.class);
    /** Marker column names this service excludes. */
    private static final Set<String> EXCLUDED_MARKER_COLUMNS = Set.of(
            "event_type", "payload", "retry_count", "attempt_count", "consumer_group", "status",
            "error_message");
    /** Assessment column names this service excludes. */
    private static final Set<String> EXCLUDED_ASSESSMENT_COLUMNS = Set.of(
            "merchant_category", "amount", "currency", "decline_reason", "model_version",
            "rule_count", "notes");
    /** Packages holding the publish-side types no consumer collaborator may come from. */
    private static final List<String> PUBLISH_SIDE_PACKAGES = List.of(
            "org.springframework.kafka.core", "org.apache.kafka.clients.producer");
    /** Annotation packages no store may name. */
    private static final List<String> EXCLUDED_STORE_ANNOTATIONS = List.of(
            "org.springframework.transaction", "org.springframework.cache",
            "org.springframework.scheduling");
    /** Signature packages no store may reach. */
    private static final List<String> EXCLUDED_STORE_TYPES = List.of(
            "com.carddemo.events", "org.apache.kafka", "org.springframework.kafka",
            "tools.jackson", "org.springframework.web");
    /** Event identifiers the claim statement already holds. */
    private Set<UUID> claimedEventIds;
    /** Rows the claim statement wrote across every delivery of one test. */
    private AtomicInteger markerRowsWritten;
    /** Store of duplicate-delivery markers, backed by {@link #claimedEventIds}. */
    private ProcessedEventRepository markers;
    /** Store of assessment rows. */
    private FraudAssessmentRepository assessments;
    /** Store the real scoring service raises one window on. */
    private VelocityWindowRepository velocityWindows;
    /** Writer of the unpublished event row. */
    private OutboxWriter outboxWriter;
    /** Counters and the timer the consumer records. */
    private FraudMeters meters;
    /** Offset commit the listener invokes once its work commits. */
    private Acknowledgment acknowledgment;

    /**
     * Builds the stateful marker store and the five remaining doubles.
     *
     * <p>The claim answer adds the event identifier to a set and counts the rows it wrote, which
     * is what the delivered statement does through its conflict clause.
     */
    @BeforeEach
    void createTestDoubles() {
        claimedEventIds = new HashSet<>();
        markerRowsWritten = new AtomicInteger();
        markers = mock(ProcessedEventRepository.class);
        when(markers.claimEvent(any(), any(), any())).thenAnswer(invocation -> {
            UUID eventId = invocation.getArgument(0);
            if (!claimedEventIds.add(eventId)) {
                return ProcessedEventRepository.ALREADY_CLAIMED;
            }
            markerRowsWritten.incrementAndGet();
            return ONE_MARKER_ROW;
        });

        assessments = mock(FraudAssessmentRepository.class);
        velocityWindows = mock(VelocityWindowRepository.class);
        when(velocityWindows.addAuthorization(any(), any(), any(), any()))
                .thenReturn(ONE_WINDOW_ROW);
        outboxWriter = mock(OutboxWriter.class);
        meters = mock(FraudMeters.class);
        acknowledgment = mock(Acknowledgment.class);
    }

    /** Two deliveries of one event identifier, and the effects each of them leaves. */
    @Nested
    @DisplayName("Duplicate delivery")
    class DuplicateDelivery {

        @Test
        @DisplayName("one event identifier delivered twice writes one assessment row, one marker "
                + "row, one outbox row and one velocity window update")
        void oneEventIdentifierDeliveredTwiceWritesEachEffectOnce() {
            TransactionAuthorized event = authorized(EVENT_ID, POSITIVE_AMOUNT);
            TransactionAuthorizedConsumer consumer = consumerScoringWith(
                    realScoring(triggeredRule(FraudFlagged.VELOCITY_RULE, FLAGGING_POINTS)));

            consumer.onTransactionAuthorized(delivery(event), acknowledgment);
            consumer.onTransactionAuthorized(delivery(event), acknowledgment);

            verify(markers, times(2)).claimEvent(eq(EVENT_ID), any(), eq(SOURCE_TOPIC));
            assertEquals(ONE_MARKER_ROW, markerRowsWritten.get(),
                    "rows the claim statement wrote on ProcessedEventRepository");
            verify(assessments, times(1)).save(any(FraudAssessmentEntity.class));
            verify(outboxWriter, times(1)).write(any());
            verify(velocityWindows, times(1))
                    .addAuthorization(eq(ACCOUNT_ID), any(), any(), any());
        }

        @Test
        @DisplayName("both deliveries of one event identifier acknowledge")
        void bothDeliveriesOfOneEventIdentifierAcknowledge() {
            TransactionAuthorized event = authorized(EVENT_ID, POSITIVE_AMOUNT);
            TransactionAuthorizedConsumer consumer = consumerScoringWith(
                    realScoring(triggeredRule(FraudFlagged.VELOCITY_RULE, FLAGGING_POINTS)));

            consumer.onTransactionAuthorized(delivery(event), acknowledgment);
            consumer.onTransactionAuthorized(delivery(event), acknowledgment);

            verify(acknowledgment, times(2)).acknowledge();
        }

        @Test
        @DisplayName("the repeat delivery adds no scoring call, no assessment row and no outbox "
                + "row")
        void theRepeatDeliveryAddsNoScoringCallAndNoWrite() {
            TransactionAuthorized event = authorized(EVENT_ID, POSITIVE_AMOUNT);
            RiskScoringService scoring = scoringReturning(event, flaggedAssessment());
            TransactionAuthorizedConsumer consumer = consumerScoringWith(scoring);

            consumer.onTransactionAuthorized(delivery(event), acknowledgment);
            consumer.onTransactionAuthorized(delivery(event), acknowledgment);

            verify(scoring, times(1)).assess(event);
            verify(assessments, times(1)).save(any(FraudAssessmentEntity.class));
            verify(outboxWriter, times(1)).write(any());
        }
    }

    /** The order one delivery performs its work in, and what a failure leaves behind. */
    @Nested
    @DisplayName("Ordered work in one transaction")
    class OrderedWork {

        @Test
        @DisplayName("the claim precedes scoring, the assessment row, the outbox row and the "
                + "acknowledgement")
        void theClaimPrecedesEveryLaterStep() {
            TransactionAuthorized event = authorized(EVENT_ID, POSITIVE_AMOUNT);
            RiskScoringService scoring = scoringReturning(event, flaggedAssessment());

            consumerScoringWith(scoring).onTransactionAuthorized(delivery(event), acknowledgment);

            InOrder order = inOrder(markers, scoring, assessments, outboxWriter, acknowledgment);
            order.verify(markers).claimEvent(eq(EVENT_ID), any(), eq(SOURCE_TOPIC));
            order.verify(scoring).assess(event);
            order.verify(assessments).save(any(FraudAssessmentEntity.class));
            order.verify(outboxWriter).write(any(FraudFlagged.class));
            order.verify(acknowledgment).acknowledge();
            order.verifyNoMoreInteractions();
        }

        @Test
        @DisplayName("the claim is the only marker operation one delivery performs")
        void theClaimIsTheOnlyMarkerOperation() {
            TransactionAuthorized event = authorized(EVENT_ID, POSITIVE_AMOUNT);

            consumerScoringWith(scoringReturning(event, flaggedAssessment()))
                    .onTransactionAuthorized(delivery(event), acknowledgment);

            verify(markers).claimEvent(eq(EVENT_ID), any(), eq(SOURCE_TOPIC));
            verifyNoMoreInteractions(markers);
        }

        @Test
        @DisplayName("the business method carries the transaction and the listener method carries "
                + "none")
        void theBusinessMethodCarriesTheTransaction() throws NoSuchMethodException {
            Method business = TransactionAuthorizedConsumer.class.getMethod(
                    "assessOneEvent", TransactionAuthorized.class, String.class);

            assertTrue(business.isAnnotationPresent(Transactional.class),
                    "transaction on assessOneEvent");
            assertFalse(listenerMethod().isAnnotationPresent(Transactional.class),
                    "transaction on the listener method");
        }

        @Test
        @DisplayName("the consumer declares no velocity window field and takes no velocity window "
                + "parameter")
        void theConsumerHoldsNoVelocityWindowStore() {
            for (Field field : TransactionAuthorizedConsumer.class.getDeclaredFields()) {
                assertNotEquals(VelocityWindowRepository.class, field.getType(),
                        "field " + field.getName());
            }
            for (Constructor<?> constructor
                    : TransactionAuthorizedConsumer.class.getDeclaredConstructors()) {
                for (Class<?> parameterType : constructor.getParameterTypes()) {
                    assertNotEquals(VelocityWindowRepository.class, parameterType,
                            "constructor parameter of TransactionAuthorizedConsumer");
                }
            }
        }

        @Test
        @DisplayName("a scoring failure leaves the offset uncommitted and writes nothing")
        void aScoringFailureLeavesTheOffsetUncommitted() {
            TransactionAuthorized event = authorized(EVENT_ID, POSITIVE_AMOUNT);
            RiskScoringService scoring = mock(RiskScoringService.class);
            when(scoring.assess(event))
                    .thenThrow(new IllegalStateException("scoring is unavailable"));
            TransactionAuthorizedConsumer consumer = consumerScoringWith(scoring);
            ConsumerRecord<String, TransactionAuthorized> delivery = delivery(event);

            assertThrows(IllegalStateException.class,
                    () -> consumer.onTransactionAuthorized(delivery, acknowledgment));

            verify(acknowledgment, never()).acknowledge();
            verifyNoInteractions(assessments, outboxWriter);
        }
    }

    /** The declared form of the listener method and of the class that holds it. */
    @Nested
    @DisplayName("Listener shape")
    class ListenerShape {

        @Test
        @DisplayName("one declared method carries the listener annotation")
        void oneDeclaredMethodCarriesTheListenerAnnotation() {
            long annotated = Arrays.stream(TransactionAuthorizedConsumer.class.getDeclaredMethods())
                    .filter(method -> method.isAnnotationPresent(KafkaListener.class))
                    .count();

            assertEquals(1L, annotated, "declared methods carrying the listener annotation");
        }

        @Test
        @DisplayName("the listener takes one authorized event and one acknowledgement")
        void theListenerTakesOneRecordAndOneAcknowledgment() throws NoSuchMethodException {
            Method listener = listenerMethod();

            assertEquals(2, listener.getParameterCount(), "listener parameters");
            assertFalse(Collection.class.isAssignableFrom(listener.getParameterTypes()[0]),
                    "collection in the record position");
            ParameterizedType delivered = assertInstanceOf(ParameterizedType.class,
                    listener.getGenericParameterTypes()[0], "record parameter");
            assertEquals(ConsumerRecord.class, delivered.getRawType(), "record type");
            assertEquals(TransactionAuthorized.class, delivered.getActualTypeArguments()[1],
                    "payload type");
            assertEquals(Acknowledgment.class, listener.getParameterTypes()[1],
                    "acknowledgement parameter");
        }

        @Test
        @DisplayName("the listener names its container factory bean by a literal name")
        void theListenerNamesAContainerFactoryBean() throws NoSuchMethodException {
            String factory = listenerAnnotation().containerFactory();

            assertFalse(factory.isBlank(), "container factory name");
            assertFalse(factory.contains("${"), "placeholder in the container factory name");
        }

        @Test
        @DisplayName("the topic and the group bind through placeholders carrying defaults")
        void theTopicAndTheGroupBindThroughPlaceholders() throws NoSuchMethodException {
            KafkaListener listener = listenerAnnotation();

            assertEquals(1, listener.topics().length, "topics the listener reads");
            assertPlaceholderWithDefault(listener.topics()[0], "topic expression");
            assertPlaceholderWithDefault(listener.groupId(), "group expression");
        }

        @Test
        @DisplayName("the class enables nothing and declares no bean method")
        void theClassEnablesNothingAndDeclaresNoBeanMethod() {
            assertFalse(TransactionAuthorizedConsumer.class.isAnnotationPresent(EnableKafka.class),
                    "listener registration on the consumer class");
            assertFalse(
                    TransactionAuthorizedConsumer.class.isAnnotationPresent(EnableScheduling.class),
                    "scheduling on the consumer class");
            for (Method method : TransactionAuthorizedConsumer.class.getDeclaredMethods()) {
                assertFalse(method.isAnnotationPresent(Bean.class),
                        "bean method " + method.getName());
            }
        }

        @Test
        @DisplayName("the consumer holds no publish-side collaborator and declares no publishing "
                + "method")
        void theConsumerPublishesNothingItself() {
            for (Field field : TransactionAuthorizedConsumer.class.getDeclaredFields()) {
                assertNoPublishSideType(field.getType(), "field " + field.getName());
            }
            for (Constructor<?> constructor
                    : TransactionAuthorizedConsumer.class.getDeclaredConstructors()) {
                for (Class<?> parameterType : constructor.getParameterTypes()) {
                    assertNoPublishSideType(parameterType,
                            "constructor parameter of TransactionAuthorizedConsumer");
                }
            }
            for (Method method : TransactionAuthorizedConsumer.class.getDeclaredMethods()) {
                assertFalse(method.getName().toLowerCase(Locale.ROOT).contains("send"),
                        "publishing method " + method.getName());
            }
        }
    }

    /** The one event the outbox receives for each event the listener reads. */
    @Nested
    @DisplayName("One event per consumed event")
    class OneEventPerConsumedEvent {

        @Test
        @DisplayName("a triggered rule writes one flagged event and no cleared event")
        void aTriggeredRuleWritesOneFlaggedEvent() {
            TransactionAuthorized event = authorized(EVENT_ID, POSITIVE_AMOUNT);

            consumerScoringWith(scoringReturning(event, flaggedAssessment()))
                    .onTransactionAuthorized(delivery(event), acknowledgment);

            List<Object> written = writtenOutboxEvents();
            assertEquals(1, written.size(), "events written through OutboxWriter");
            FraudFlagged flagged = assertInstanceOf(FraudFlagged.class, written.get(0),
                    "written event");
            assertEquals(TRANSACTION_ID, flagged.transactionId(), "transaction identifier");
            assertEquals(ACCOUNT_ID, flagged.accountId(), "account identifier");
            assertTrue(written.stream().noneMatch(FraudCleared.class::isInstance),
                    "cleared events written through OutboxWriter");
        }

        @Test
        @DisplayName("no triggered rule writes one cleared event and no flagged event")
        void noTriggeredRuleWritesOneClearedEvent() {
            TransactionAuthorized event = authorized(EVENT_ID, POSITIVE_AMOUNT);

            consumerScoringWith(scoringReturning(event, clearedAssessment()))
                    .onTransactionAuthorized(delivery(event), acknowledgment);

            List<Object> written = writtenOutboxEvents();
            assertEquals(1, written.size(), "events written through OutboxWriter");
            FraudCleared cleared = assertInstanceOf(FraudCleared.class, written.get(0),
                    "written event");
            assertEquals(TRANSACTION_ID, cleared.transactionId(), "transaction identifier");
            assertTrue(written.stream().noneMatch(FraudFlagged.class::isInstance),
                    "flagged events written through OutboxWriter");
        }

        @Test
        @DisplayName("three triggered rules travel on one flagged event in the order supplied")
        void threeTriggeredRulesTravelInTheOrderSupplied() {
            List<String> supplied = List.of(FraudFlagged.MERCHANT_CATEGORY_RULE,
                    FraudFlagged.VELOCITY_RULE, FraudFlagged.AMOUNT_ANOMALY_RULE);
            TransactionAuthorized event = authorized(EVENT_ID, POSITIVE_AMOUNT);

            consumerScoringWith(scoringReturning(event, flaggedAssessment(supplied)))
                    .onTransactionAuthorized(delivery(event), acknowledgment);

            List<Object> written = writtenOutboxEvents();
            assertEquals(1, written.size(), "events written through OutboxWriter");
            FraudFlagged flagged = assertInstanceOf(FraudFlagged.class, written.get(0),
                    "written event");
            assertEquals(supplied, flagged.triggeredRules(), "rule identifiers");
        }
    }

    /** A negative amount, which fifty of the three hundred feed records carry. */
    @Nested
    @DisplayName("Negative amount")
    class NegativeAmount {

        @ParameterizedTest(name = "amount {0}")
        @ValueSource(strings = {NEGATIVE_AMOUNT, SECOND_NEGATIVE_AMOUNT})
        @DisplayName("a negative amount consumes and writes each effect once")
        void aNegativeAmountConsumesAndWritesEachEffectOnce(String amount) {
            TransactionAuthorized event = authorized(EVENT_ID, amount);
            TransactionAuthorizedConsumer consumer = consumerScoringWith(
                    realScoring(triggeredRule(FraudFlagged.AMOUNT_ANOMALY_RULE, FLAGGING_POINTS)));

            consumer.onTransactionAuthorized(delivery(event), acknowledgment);

            assertEquals(ONE_MARKER_ROW, markerRowsWritten.get(),
                    "rows the claim statement wrote on ProcessedEventRepository");
            verify(assessments, times(1)).save(any(FraudAssessmentEntity.class));
            verify(outboxWriter, times(1)).write(any());
            verify(velocityWindows, times(1))
                    .addAuthorization(eq(ACCOUNT_ID), any(), any(), any());
            verify(acknowledgment, times(1)).acknowledge();
        }
    }

    /** The mapped form of the two rows one delivery writes, and the shape of the four stores. */
    @Nested
    @DisplayName("Marker, assessment and store shape")
    class MarkerAndAssessmentShape {

        @Test
        @DisplayName("the marker keys on a supplied event identifier and generates no value")
        void theMarkerKeysOnASuppliedEventIdentifier() throws NoSuchFieldException {
            Field eventId = ProcessedEventEntity.class.getDeclaredField("eventId");

            assertEquals(UUID.class, eventId.getType(), "identifier type");
            assertTrue(eventId.isAnnotationPresent(Id.class), "primary key on eventId");
            assertFalse(eventId.isAnnotationPresent(GeneratedValue.class),
                    "generation strategy on eventId");
            assertEquals("event_id", columnOf(ProcessedEventEntity.class, "eventId").name(),
                    "identifier column name");
            assertFalse(columnOf(ProcessedEventEntity.class, "processedAt").nullable(),
                    "nullable processedAt");
        }

        @Test
        @DisplayName("the marker carries none of the seven retry or routing column names")
        void theMarkerCarriesNoRetryOrRoutingColumn() {
            for (String column : columnNames(ProcessedEventEntity.class)) {
                assertFalse(EXCLUDED_MARKER_COLUMNS.contains(column),
                        "excluded column " + column + " on processed_event");
            }
        }

        @Test
        @DisplayName("the assessment row carries six columns under their declared names, and its "
                + "score column carries no precision and no scale")
        void theAssessmentRowCarriesSixColumns() throws NoSuchFieldException {
            List<String> columns = columnNames(FraudAssessmentEntity.class);

            assertEquals(6, columns.size(), "columns on fraud_assessment");
            assertTrue(columns.containsAll(List.of("transaction_id", "account_id", "risk_score",
                    "flagged", "triggered_rules", "assessed_at")), "column names");
            for (String column : columns) {
                assertFalse(EXCLUDED_ASSESSMENT_COLUMNS.contains(column),
                        "excluded column " + column + " on fraud_assessment");
            }
            assertEquals(int.class,
                    FraudAssessmentEntity.class.getDeclaredField("riskScore").getType(),
                    "score type");
            assertEquals(0, columnOf(FraudAssessmentEntity.class, "riskScore").precision(),
                    "precision on risk_score");
            assertEquals(0, columnOf(FraudAssessmentEntity.class, "riskScore").scale(),
                    "scale on risk_score");
        }

        @Test
        @DisplayName("the assessment key holds sixteen characters, the account eleven, the rule "
                + "list sixty-four, and the account column carries its named index")
        void theAssessmentWidthsAndIndexMatchTheDeclaredSchema() throws NoSuchFieldException {
            assertEquals(16, columnOf(FraudAssessmentEntity.class, "transactionId").length(),
                    "transaction identifier width");
            assertEquals(11, columnOf(FraudAssessmentEntity.class, "accountId").length(),
                    "account identifier width");
            assertEquals(64, columnOf(FraudAssessmentEntity.class, "triggeredRules").length(),
                    "rule list width");
            Table table = FraudAssessmentEntity.class.getAnnotation(Table.class);
            assertNotNull(table, "table mapping on FraudAssessmentEntity");
            assertEquals("fraud_assessment", table.name(), "table name");
            assertTrue(Arrays.stream(table.indexes())
                            .anyMatch(index -> "ix_fraud_assessment_account".equals(index.name())
                                    && "account_id".equals(index.columnList())),
                    "account index");
        }

        @Test
        @DisplayName("the rule list converts through a converter nested in the entity, named on "
                + "the field and not auto-applied")
        void theRuleListConvertsThroughANestedConverter() throws NoSuchFieldException {
            Convert convert = FraudAssessmentEntity.class.getDeclaredField("triggeredRules")
                    .getAnnotation(Convert.class);

            assertNotNull(convert, "convert mapping on triggeredRules");
            assertEquals(FraudAssessmentEntity.class, convert.converter().getEnclosingClass(),
                    "class enclosing the converter");
            assertTrue(AttributeConverter.class.isAssignableFrom(convert.converter()),
                    "converter contract");
            Converter declared = convert.converter().getAnnotation(Converter.class);
            assertFalse(declared != null && declared.autoApply(), "auto-applied converter");
        }

        @Test
        @DisplayName("the assessment store declares no score, verdict or rule finder")
        void theAssessmentStoreDeclaresNoScoreVerdictOrRuleFinder() {
            for (Method method : FraudAssessmentRepository.class.getDeclaredMethods()) {
                String name = method.getName();
                assertFalse(name.contains("RiskScore"), "score finder " + name);
                assertFalse(name.contains("Flagged"), "verdict finder " + name);
                assertFalse(name.contains("TriggeredRules"), "rule finder " + name);
            }
        }

        @Test
        @DisplayName("every store extends the Jakarta Persistence repository, carries the "
                + "stereotype, and reaches no event, broker or web type")
        void everyStoreCarriesTheDeclaredShape() {
            for (Class<?> store : STORES) {
                String label = store.getSimpleName();
                assertTrue(JpaRepository.class.isAssignableFrom(store), "persistence contract on "
                        + label);
                assertTrue(store.isAnnotationPresent(Repository.class), "stereotype on " + label);
                assertNoExcludedAnnotation(store.getAnnotations(), label);
                for (Method method : store.getDeclaredMethods()) {
                    String methodLabel = label + "." + method.getName();
                    assertNoExcludedAnnotation(method.getAnnotations(), methodLabel);
                    assertNoExcludedStoreType(method.getReturnType(), methodLabel);
                    for (Class<?> parameterType : method.getParameterTypes()) {
                        assertNoExcludedStoreType(parameterType, methodLabel);
                    }
                }
            }
        }
    }

    /** Answers the listener method the consumer declares. */
    private static Method listenerMethod() throws NoSuchMethodException {
        return TransactionAuthorizedConsumer.class.getMethod(
                "onTransactionAuthorized", ConsumerRecord.class, Acknowledgment.class);
    }

    /** Answers the listener annotation the listener method carries. */
    private static KafkaListener listenerAnnotation() throws NoSuchMethodException {
        KafkaListener listener = listenerMethod().getAnnotation(KafkaListener.class);
        assertNotNull(listener, "listener annotation");
        return listener;
    }

    /** Builds one consumer over the doubles this test holds, reachable through its provider. */
    private TransactionAuthorizedConsumer consumerScoringWith(RiskScoringService scoring) {
        SelfProvider self = new SelfProvider();
        TransactionAuthorizedConsumer consumer = new TransactionAuthorizedConsumer(
                scoring, assessments, markers, outboxWriter, meters, self);
        self.publish(consumer);
        return consumer;
    }

    /** Builds the delivered scoring service, which raises one window per event it assesses. */
    private RiskScoringService realScoring(RiskRule... rules) {
        return new RiskScoringService(List.of(rules), velocityWindows, deliveredSettings());
    }

    /** Builds a scoring double answering one fixed assessment for one event. */
    private static RiskScoringService scoringReturning(TransactionAuthorized event,
            RiskAssessment assessment) {
        RiskScoringService scoring = mock(RiskScoringService.class);
        when(scoring.assess(event)).thenReturn(assessment);
        return scoring;
    }

    /** Builds one rule that triggers with a fixed score. */
    private static RiskRule triggeredRule(String ruleId, int points) {
        return new FixedRule(ruleId, RiskRule.Contribution.triggeredWith(points));
    }

    /** Answers one flagged assessment naming the velocity rule. */
    private static RiskAssessment flaggedAssessment() {
        return flaggedAssessment(List.of(FraudFlagged.VELOCITY_RULE));
    }

    /** Answers one flagged assessment naming the rules given, in the order given. */
    private static RiskAssessment flaggedAssessment(List<String> triggeredRules) {
        return new RiskAssessment(TRANSACTION_ID, ACCOUNT_ID, FLAGGING_POINTS, true,
                triggeredRules, ASSESSED_AT);
    }

    /** Answers one cleared assessment naming no rule. */
    private static RiskAssessment clearedAssessment() {
        return new RiskAssessment(TRANSACTION_ID, ACCOUNT_ID, CLEARING_POINTS, false, List.of(),
                ASSESSED_AT);
    }

    /** Answers every event handed to the outbox writer, in the order written. */
    private List<Object> writtenOutboxEvents() {
        ArgumentCaptor<Object> written = ArgumentCaptor.forClass(Object.class);
        verify(outboxWriter, atLeast(0)).write(written.capture());
        return written.getAllValues();
    }

    /** Wraps one event as the delivery the listener reads, keyed on the account identifier. */
    private static ConsumerRecord<String, TransactionAuthorized> delivery(
            TransactionAuthorized event) {
        return new ConsumerRecord<>(SOURCE_TOPIC, PARTITION, OFFSET, event.aggregateId(), event);
    }

    /** Builds one authorized event from feed record 1 at the amount given. */
    private static TransactionAuthorized authorized(UUID eventId, String amount) {
        return new TransactionAuthorized(
                eventId,
                TransactionAuthorized.EVENT_TYPE,
                EventEnvelope.SCHEMA_VERSION,
                OCCURRED_AT,
                ACCOUNT_ID,
                TRANSACTION_ID,
                TRANSACTION_TYPE_CODE,
                MERCHANT_CATEGORY_CODE,
                "POS TERM",
                "Purchase at Abshire-Lowe",
                new BigDecimal(amount),
                MERCHANT_ID,
                "Abshire-Lowe",
                "North Enoshaven",
                MERCHANT_ZIP,
                MASKED_CARD_NUMBER,
                NO_CARD_TOKEN,
                AUTHORIZED_AT,
                ACCOUNT_ID,
                TransactionAuthorized.CURRENCY);
    }

    /** Builds the delivered settings, whose verdict threshold is {@link #FLAG_THRESHOLD}. */
    private static FraudProperties deliveredSettings() {
        return new FraudProperties(
                new FraudProperties.Kafka(new FraudProperties.Kafka.Topics(
                        SOURCE_TOPIC, "fraud.assessed", "carddemo.dead-letter", ".DLT")),
                new FraudProperties.Consumer(new FraudProperties.Consumer.Retry(3, 1_000L)),
                new FraudProperties.Outbox(new FraudProperties.Outbox.Relay(
                        500L, 100, "fraud-relay", Duration.ofMinutes(2L), 20_000L), 168L),
                new FraudProperties.ProcessedEvent(168L),
                new FraudProperties.Retention(3_600_000L),
                new FraudProperties.Fraud(new FraudProperties.Fraud.Risk(
                        FLAG_THRESHOLD, 60, 5, new BigDecimal("500.00"))));
    }

    /** Collects the column names one mapped class declares. */
    private static List<String> columnNames(Class<?> entity) {
        List<String> names = new ArrayList<>();
        for (Field field : entity.getDeclaredFields()) {
            Column column = field.getAnnotation(Column.class);
            if (column != null) {
                names.add(column.name());
            }
        }
        return names;
    }

    /** Answers the column mapping one named field carries. */
    private static Column columnOf(Class<?> entity, String fieldName) throws NoSuchFieldException {
        Column column = entity.getDeclaredField(fieldName).getAnnotation(Column.class);
        assertNotNull(column, "column mapping on " + fieldName);
        return column;
    }

    /** Asserts one annotation value opens a placeholder and carries a default. */
    private static void assertPlaceholderWithDefault(String expression, String label) {
        assertTrue(expression.startsWith("${"), label + " opens a placeholder");
        assertTrue(expression.endsWith("}"), label + " closes a placeholder");
        assertTrue(expression.indexOf(':') > "${".length(), label + " carries a default");
    }

    /** Asserts one declared type comes from neither publish-side package. */
    private static void assertNoPublishSideType(Class<?> type, String label) {
        for (String publishSidePackage : PUBLISH_SIDE_PACKAGES) {
            assertFalse(type.getName().startsWith(publishSidePackage),
                    label + " names " + type.getName());
        }
    }

    /** Asserts no declared annotation comes from an excluded package. */
    private static void assertNoExcludedAnnotation(Annotation[] annotations, String label) {
        for (Annotation annotation : annotations) {
            String name = annotation.annotationType().getName();
            for (String excluded : EXCLUDED_STORE_ANNOTATIONS) {
                assertFalse(name.startsWith(excluded), label + " carries " + name);
            }
        }
    }

    /** Asserts one store signature type comes from no excluded package. */
    private static void assertNoExcludedStoreType(Class<?> type, String label) {
        for (String excluded : EXCLUDED_STORE_TYPES) {
            assertFalse(type.getName().startsWith(excluded), label + " names " + type.getName());
        }
    }

    /** Hands the consumer back to itself, as the framework does through its own provider. */
    private static final class SelfProvider
            implements ObjectProvider<TransactionAuthorizedConsumer> {

        /** The consumer this provider answers. */
        private TransactionAuthorizedConsumer target;

        /** Records the consumer this provider answers. */
        void publish(TransactionAuthorizedConsumer consumer) {
            this.target = consumer;
        }

        @Override
        public TransactionAuthorizedConsumer getObject() {
            return target;
        }
    }

    /** One rule answering a fixed contribution under a fixed identifier. */
    private record FixedRule(String ruleId, RiskRule.Contribution contribution)
            implements RiskRule {

        @Override
        public RiskRule.Contribution evaluate(TransactionAuthorized event) {
            return contribution;
        }
    }
}
