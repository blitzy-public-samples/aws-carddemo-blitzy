package com.carddemo.fraud.outbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.carddemo.events.EventEnvelope;
import com.carddemo.events.FraudCleared;
import com.carddemo.events.FraudFlagged;
import com.carddemo.fraud.entity.OutboxEventEntity;
import com.carddemo.fraud.repository.OutboxEventRepository;
import com.networknt.schema.InputFormat;
import com.networknt.schema.Schema;
import com.networknt.schema.SchemaRegistry;
import com.networknt.schema.SpecificationVersion;
import jakarta.persistence.Column;
import jakarta.persistence.EntityManager;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Lob;
import jakarta.persistence.Table;
import java.io.IOException;
import java.io.InputStream;
import java.lang.annotation.Annotation;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.LoggerFactory;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.stereotype.Component;
import org.springframework.stereotype.Service;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * Verifies transaction propagation, row construction, wire form, and diagnostic hygiene for
 * {@link OutboxWriter}.
 *
 * <p>The writer and these assertions have no Common Business-Oriented Language (COBOL) source.
 * They are net new; no COBOL ancestor. Fixed event values and in-memory collaborators keep the
 * tests independent of a broker, database, container, or network.
 *
 * <p>Design decisions: {@code card-platform/docs/decision-log.md} (planned).
 */
@DisplayName("Outbox writer")
public class OutboxWriterTest {

    private static final String ACCOUNT_IDENTIFIER = "00000000007";
    private static final String TRANSACTION_IDENTIFIER = "0000000000683580";
    private static final Instant FLAGGED_ENVELOPE_INSTANT =
            Instant.parse("2022-06-10T19:27:53Z");
    private static final Instant ASSESSMENT_INSTANT = Instant.parse("2022-06-10T19:27:54Z");
    private static final UUID FLAGGED_EVENT_IDENTIFIER =
            UUID.fromString("7c3a5b2e-4d16-4f8a-b0c5-2e7d6a4f8b31");
    private static final UUID CLEARED_EVENT_IDENTIFIER =
            UUID.fromString("2f8b6d40-5c71-4a23-8e6f-3b5d7c2a4e68");
    private static final int RISK_SCORE = 72;
    private static final List<String> TRIGGERED_RULES =
            List.of("VELOCITY", "AMOUNT_ANOMALY");
    private static final Pattern MASKED_FORM_PATTERN =
            Pattern.compile("^\\*{12}[0-9]{4}$");
    private static final Pattern LONG_DIGIT_RUN = Pattern.compile("[0-9]{12,}");
    private static final String NEW_TRANSACTION_PROPAGATION = "REQUIRES" + "_NEW";
    private static final Set<String> FLAGGED_PROPERTY_NAMES = Set.of(
            "eventId", "eventType", "schemaVersion", "occurredAt", "aggregateId",
            "transactionId", "accountId", "riskScore", "triggeredRules", "assessedAt");
    private static final Set<String> CLEARED_PROPERTY_NAMES = Set.of(
            "eventId", "eventType", "schemaVersion", "occurredAt", "aggregateId",
            "transactionId", "accountId", "assessedAt");

    private static FraudFlagged flaggedEvent() {
        return new FraudFlagged(
                FLAGGED_EVENT_IDENTIFIER,
                FraudFlagged.EVENT_TYPE,
                EventEnvelope.SCHEMA_VERSION,
                FLAGGED_ENVELOPE_INSTANT,
                ACCOUNT_IDENTIFIER,
                TRANSACTION_IDENTIFIER,
                RISK_SCORE,
                TRIGGERED_RULES,
                ASSESSMENT_INSTANT,
                ACCOUNT_IDENTIFIER);
    }

    private static FraudCleared clearedEvent() {
        return new FraudCleared(
                CLEARED_EVENT_IDENTIFIER,
                FraudCleared.EVENT_TYPE,
                EventEnvelope.SCHEMA_VERSION,
                FLAGGED_ENVELOPE_INSTANT,
                ACCOUNT_IDENTIFIER,
                TRANSACTION_IDENTIFIER,
                ACCOUNT_IDENTIFIER,
                ASSESSMENT_INSTANT);
    }

    private static Method writeMethod() throws NoSuchMethodException {
        return OutboxWriter.class.getDeclaredMethod("write", Object.class);
    }

    private static Field entityField(String name) throws NoSuchFieldException {
        return OutboxEventEntity.class.getDeclaredField(name);
    }

    private static void returnSavedArgument(OutboxEventRepository repository) {
        when(repository.save(any(OutboxEventEntity.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
    }

    @Nested
    @DisplayName("Production shape")
    class ProductionShape {

        @Test
        @DisplayName("the writer declares one component stereotype")
        void writerDeclaresOneComponentStereotype() {
            long stereotypeCount = Stream.of(Component.class, Service.class)
                    .filter(OutboxWriter.class::isAnnotationPresent)
                    .count();

            assertThat(stereotypeCount).isEqualTo(1);
        }

        @Test
        @DisplayName("the write method has the proxy-visible modifier set")
        void writeMethodHasProxyVisibleModifiers() throws NoSuchMethodException {
            Method write = writeMethod();
            int classModifiers = OutboxWriter.class.getModifiers();
            int methodModifiers = write.getModifiers();

            assertThat(Modifier.isFinal(classModifiers))
                    .as("writer class final modifier")
                    .isFalse();
            assertThat(Modifier.isPublic(methodModifiers))
                    .as("write method public modifier")
                    .isTrue();
            assertThat(Modifier.isPrivate(methodModifiers))
                    .as("write method private modifier")
                    .isFalse();
            assertThat(Modifier.isStatic(methodModifiers))
                    .as("write method static modifier")
                    .isFalse();
            assertThat(Modifier.isFinal(methodModifiers))
                    .as("write method final modifier")
                    .isFalse();
            assertThat(write.getParameterTypes()).containsExactly(Object.class);
            assertThat(write.getReturnType()).isEqualTo(OutboxEventEntity.class);
            assertThat(Arrays.stream(OutboxWriter.class.getDeclaredMethods())
                    .filter(method -> method.getName().equals("write")))
                    .containsExactly(write);
        }

        @Test
        @DisplayName("the write method requires an existing transaction")
        void writeMethodRequiresExistingTransaction() throws NoSuchMethodException {
            Method write = writeMethod();
            Transactional transaction = write.getAnnotation(Transactional.class);

            assertThat(transaction).isNotNull();
            assertThat(transaction.annotationType().getName())
                    .isEqualTo("org.springframework.transaction.annotation.Transactional");
            assertThat(transaction.propagation()).isEqualTo(Propagation.MANDATORY);

            Propagation newTransaction = Propagation.valueOf(NEW_TRANSACTION_PROPAGATION);
            List<Transactional> declarations = new ArrayList<>();
            Transactional classDeclaration = OutboxWriter.class.getAnnotation(Transactional.class);
            if (classDeclaration != null) {
                declarations.add(classDeclaration);
            }
            Arrays.stream(OutboxWriter.class.getDeclaredMethods())
                    .map(method -> method.getAnnotation(Transactional.class))
                    .filter(annotation -> annotation != null)
                    .forEach(declarations::add);
            assertThat(declarations)
                    .allMatch(annotation -> annotation.propagation() != newTransaction);
        }

        @Test
        @DisplayName("the writer owns no transaction resource")
        void writerOwnsNoTransactionResource() {
            Set<Class<?>> forbiddenTypes = Set.of(
                    TransactionTemplate.class,
                    PlatformTransactionManager.class,
                    EntityManager.class);

            assertThat(Arrays.stream(OutboxWriter.class.getDeclaredFields())
                    .map(Field::getType))
                    .noneMatch(forbiddenTypes::contains);
            assertThat(Arrays.stream(OutboxWriter.class.getDeclaredConstructors())
                    .flatMap(constructor -> Arrays.stream(constructor.getParameterTypes())))
                    .noneMatch(forbiddenTypes::contains);
        }

        @Test
        @DisplayName("the writer has no message-broker member")
        void writerHasNoMessageBrokerMember() {
            assertThat(Arrays.stream(OutboxWriter.class.getDeclaredFields())
                    .map(Field::getType))
                    .noneMatch(this::isMessageBrokerType);
            assertThat(Arrays.stream(OutboxWriter.class.getDeclaredConstructors())
                    .flatMap(constructor -> Arrays.stream(constructor.getParameterTypes())))
                    .noneMatch(this::isMessageBrokerType);
            assertThat(Arrays.stream(OutboxWriter.class.getDeclaredMethods())
                    .map(Method::getName))
                    .doesNotContain("send");
        }

        @Test
        @DisplayName("the writer source has no broker or new-transaction token")
        void writerSourceHasNoBrokerOrNewTransactionToken() throws IOException {
            String source = locateWriterSource();

            assertThat(source).doesNotContain("Kafka", NEW_TRANSACTION_PROPAGATION);
        }

        private boolean isMessageBrokerType(Class<?> type) {
            String typeName = type.getName();
            return typeName.startsWith("org.springframework." + "kafka")
                    || typeName.startsWith("org.apache." + "kafka");
        }

        private String locateWriterSource() throws IOException {
            Path base = Path.of("").toAbsolutePath().normalize();
            List<Path> relativeCandidates = List.of(
                    Path.of("src/main/java/com/carddemo/fraud/outbox/OutboxWriter.java"),
                    Path.of("services/fraud-detection-service/src/main/java/"
                            + "com/carddemo/fraud/outbox/OutboxWriter.java"));
            List<Path> attempted = new ArrayList<>();

            for (int depth = 0; depth <= 5 && base != null; depth++) {
                for (Path relative : relativeCandidates) {
                    Path candidate = base.resolve(relative).normalize();
                    attempted.add(candidate);
                    if (Files.isRegularFile(candidate)) {
                        return Files.readString(candidate);
                    }
                }
                base = base.getParent();
            }
            throw new AssertionError("OutboxWriter.java was not found; candidate paths: "
                    + attempted);
        }
    }

    @Nested
    @DisplayName("Entity shape")
    class EntityShape {

        @Test
        @DisplayName("one public constructor accepts the five writer values")
        void onePublicConstructorAcceptsFiveWriterValues() {
            Constructor<?>[] constructors = OutboxEventEntity.class.getConstructors();

            assertThat(constructors).hasSize(1);
            Constructor<?> constructor = constructors[0];
            assertThat(constructor.getParameterTypes()).containsExactly(
                    UUID.class,
                    String.class,
                    String.class,
                    String.class,
                    Instant.class);
            assertThat(Arrays.stream(constructor.getParameters())
                    .map(parameter -> parameter.getName()))
                    .containsExactly("eventId", "eventType", "aggregateId", "payload", "createdAt");
        }

        @Test
        @DisplayName("the seven writer fields keep their declared column mappings")
        void sevenWriterFieldsKeepDeclaredColumnMappings() throws NoSuchFieldException {
            Map<String, String> expectedColumns = Map.of(
                    "eventId", "event_id",
                    "eventType", "event_type",
                    "aggregateId", "aggregate_id",
                    "payload", "payload",
                    "published", "published",
                    "createdAt", "created_at",
                    "publishedAt", "published_at");
            Map<String, Class<?>> expectedTypes = Map.of(
                    "eventId", UUID.class,
                    "eventType", String.class,
                    "aggregateId", String.class,
                    "payload", String.class,
                    "published", boolean.class,
                    "createdAt", Instant.class,
                    "publishedAt", Instant.class);

            Set<String> declaredNames = Arrays.stream(OutboxEventEntity.class.getDeclaredFields())
                    .map(Field::getName)
                    .collect(java.util.stream.Collectors.toSet());
            assertThat(declaredNames).containsAll(expectedColumns.keySet());

            for (Map.Entry<String, String> entry : expectedColumns.entrySet()) {
                Field field = entityField(entry.getKey());
                Column column = field.getAnnotation(Column.class);

                assertThat(field.getType())
                        .as(entry.getKey() + " declared type")
                        .isEqualTo(expectedTypes.get(entry.getKey()));
                assertThat(column)
                        .as(entry.getKey() + " column annotation")
                        .isNotNull();
                assertThat(column.name())
                        .as(entry.getKey() + " column name")
                        .isEqualTo(entry.getValue());
            }
        }

        @Test
        @DisplayName("the table has no schema qualifier and declares the pending index")
        void tableHasNoSchemaQualifierAndDeclaresPendingIndex() {
            Table table = OutboxEventEntity.class.getAnnotation(Table.class);

            assertThat(table).isNotNull();
            assertThat(table.name()).isEqualTo("outbox_event");
            assertThat(table.schema()).isEmpty();
            assertThat(Arrays.stream(table.indexes()))
                    .anyMatch(index -> index.name().equals("ix_outbox_event_pending")
                            && index.columnList().equals("created_at, event_id"));
        }

        @Test
        @DisplayName("identifier type and payload annotations preserve the write contract")
        void identifierTypeAndPayloadAnnotationsPreserveWriteContract()
                throws NoSuchFieldException {
            Field eventId = entityField("eventId");
            Field eventType = entityField("eventType");
            Field payload = entityField("payload");

            assertThat(eventId.isAnnotationPresent(GeneratedValue.class)).isFalse();
            assertThat(eventType.getType()).isEqualTo(String.class);
            assertThat(eventType.isAnnotationPresent(Enumerated.class)).isFalse();
            assertThat(payload.getType()).isEqualTo(String.class);
            assertThat(payload.isAnnotationPresent(Lob.class)).isFalse();
            assertThat(payload.getAnnotation(Column.class).columnDefinition()).isEqualTo("TEXT");
        }

        @Test
        @DisplayName("publication fields use boolean and instant values")
        void publicationFieldsUseBooleanAndInstantValues() throws NoSuchFieldException {
            Field published = entityField("published");
            Field createdAt = entityField("createdAt");
            Field publishedAt = entityField("publishedAt");

            assertThat(published.getType()).isEqualTo(boolean.class);
            assertThat(createdAt.getType()).isEqualTo(Instant.class);
            assertThat(publishedAt.getType()).isEqualTo(Instant.class);
            assertThat(publishedAt.getAnnotation(Column.class).nullable()).isTrue();
        }

        @Test
        @DisplayName("the entity carries no serialization annotation")
        void entityCarriesNoSerializationAnnotation() {
            String earlierJacksonPackage = "com." + "fasterxml";
            List<Annotation> annotations = new ArrayList<>(
                    Arrays.asList(OutboxEventEntity.class.getDeclaredAnnotations()));

            Arrays.stream(OutboxEventEntity.class.getDeclaredFields())
                    .flatMap(field -> Arrays.stream(field.getDeclaredAnnotations()))
                    .forEach(annotations::add);
            Arrays.stream(OutboxEventEntity.class.getDeclaredMethods())
                    .flatMap(method -> Arrays.stream(method.getDeclaredAnnotations()))
                    .forEach(annotations::add);
            Arrays.stream(OutboxEventEntity.class.getDeclaredMethods())
                    .flatMap(method -> Arrays.stream(method.getParameters()))
                    .flatMap(parameter -> Arrays.stream(parameter.getDeclaredAnnotations()))
                    .forEach(annotations::add);
            Arrays.stream(OutboxEventEntity.class.getDeclaredConstructors())
                    .flatMap(constructor -> Arrays.stream(constructor.getDeclaredAnnotations()))
                    .forEach(annotations::add);
            Arrays.stream(OutboxEventEntity.class.getDeclaredConstructors())
                    .flatMap(constructor -> Arrays.stream(constructor.getParameters()))
                    .flatMap(parameter -> Arrays.stream(parameter.getDeclaredAnnotations()))
                    .forEach(annotations::add);

            assertThat(annotations)
                    .noneMatch(annotation -> annotation.annotationType().getPackageName()
                            .startsWith("tools.jackson")
                            || annotation.annotationType().getPackageName()
                            .startsWith(earlierJacksonPackage));
        }

        @Test
        @DisplayName("a new row is unpublished and has no publication instant")
        void newRowIsUnpublishedAndHasNoPublicationInstant() {
            OutboxEventEntity row = new OutboxEventEntity(
                    FLAGGED_EVENT_IDENTIFIER,
                    FraudFlagged.EVENT_TYPE,
                    ACCOUNT_IDENTIFIER,
                    "{}",
                    FLAGGED_ENVELOPE_INSTANT);

            assertThat(row.isPublished()).isFalse();
            assertThat(row.getPublishedAt()).isNull();
        }
    }

    /**
     * Supplies the writer, a mocked repository and a transaction manager to the two propagation tests
     * below, each of which registers this class explicitly through
     * {@link AnnotationConfigApplicationContext}.
     *
     * <p>The stereotype is {@link TestConfiguration} rather than
     * {@code org.springframework.context.annotation.Configuration}. {@code TestConfiguration} carries
     * {@code @TestComponent}, which {@code TestTypeExcludeFilter} removes from the component scan that
     * {@code @SpringBootApplication} performs over {@code com.carddemo.fraud}. A plain
     * {@code @Configuration} here is scan-eligible, so every {@code @SpringBootTest} in this module
     * would also register the {@code outboxEventRepository} bean below and collide with the repository
     * Spring Data JPA builds under that same name. {@code TestConfiguration} is meta-annotated
     * {@code @Configuration}, so explicit registration keeps working unchanged.
     */
    @TestConfiguration(proxyBeanMethods = false)
    @EnableTransactionManagement(proxyTargetClass = true)
    static class TransactionTestConfiguration {

        @Bean
        OutboxEventRepository outboxEventRepository() {
            return mock(OutboxEventRepository.class);
        }

        @Bean
        PlatformTransactionManager transactionManager() {
            return new TestTransactionManager();
        }

        @Bean
        OutboxWriter outboxWriter(OutboxEventRepository repository) {
            return new OutboxWriter(repository);
        }
    }

    static class TestTransactionManager extends AbstractPlatformTransactionManager {

        private static final long serialVersionUID = 1L;

        @Override
        protected Object doGetTransaction() {
            return new TestTransaction();
        }

        @Override
        protected boolean isExistingTransaction(Object transaction) {
            return ((TestTransaction) transaction).active
                    || TransactionSynchronizationManager.isActualTransactionActive();
        }

        @Override
        protected void doBegin(Object transaction, TransactionDefinition definition) {
            ((TestTransaction) transaction).active = true;
        }

        @Override
        protected void doCommit(DefaultTransactionStatus status) {
            ((TestTransaction) status.getTransaction()).active = false;
        }

        @Override
        protected void doRollback(DefaultTransactionStatus status) {
            ((TestTransaction) status.getTransaction()).active = false;
        }

        private static final class TestTransaction {

            private boolean active;
        }
    }

    @Nested
    @DisplayName("Transaction propagation")
    class TransactionPropagation {

        @Test
        @DisplayName("a proxied write with no transaction is rejected")
        void proxiedWriteWithNoTransactionIsRejected() {
            try (AnnotationConfigApplicationContext context =
                    new AnnotationConfigApplicationContext(TransactionTestConfiguration.class)) {
                OutboxWriter writer = context.getBean(OutboxWriter.class);
                OutboxEventRepository repository =
                        context.getBean(OutboxEventRepository.class);

                assertThatThrownBy(() -> writer.write(flaggedEvent()))
                        .isExactlyInstanceOf(IllegalTransactionStateException.class);
                verify(repository, never()).save(any(OutboxEventEntity.class));
            }
        }

        @Test
        @DisplayName("a proxied write joins the active transaction and saves once")
        void proxiedWriteJoinsActiveTransactionAndSavesOnce() {
            try (AnnotationConfigApplicationContext context =
                    new AnnotationConfigApplicationContext(TransactionTestConfiguration.class)) {
                OutboxWriter writer = context.getBean(OutboxWriter.class);
                OutboxEventRepository repository =
                        context.getBean(OutboxEventRepository.class);
                PlatformTransactionManager manager =
                        context.getBean(PlatformTransactionManager.class);
                returnSavedArgument(repository);

                OutboxEventEntity saved = new TransactionTemplate(manager)
                        .execute(status -> writer.write(flaggedEvent()));

                assertThat(saved).isNotNull();
                verify(repository, times(1)).save(any(OutboxEventEntity.class));
            }
        }
    }

    @Nested
    @DisplayName("Event type validation")
    @ExtendWith(MockitoExtension.class)
    class EventTypeValidation {

        @Mock
        private OutboxEventRepository repository;

        private OutboxWriter writer;

        @BeforeEach
        void setUp() {
            writer = new OutboxWriter(repository);
        }

        @Test
        @DisplayName("a flagged event is saved once")
        void flaggedEventIsSavedOnce() {
            returnSavedArgument(repository);

            OutboxEventEntity saved = writer.write(flaggedEvent());

            assertThat(saved.getEventType()).isEqualTo(FraudFlagged.EVENT_TYPE);
            verify(repository, times(1)).save(any(OutboxEventEntity.class));
        }

        @Test
        @DisplayName("a cleared event is saved once")
        void clearedEventIsSavedOnce() {
            returnSavedArgument(repository);

            OutboxEventEntity saved = writer.write(clearedEvent());

            assertThat(saved.getEventType()).isEqualTo(FraudCleared.EVENT_TYPE);
            verify(repository, times(1)).save(any(OutboxEventEntity.class));
        }

        @Test
        @DisplayName("an unsupported event names its type and is not saved")
        void unsupportedEventNamesItsTypeAndIsNotSaved() {
            InventoryAdjusted unsupported = new InventoryAdjusted();

            IllegalArgumentException exception = catchThrowableOfType(
                    IllegalArgumentException.class,
                    () -> writer.write(unsupported));

            assertThat(exception.getMessage()).contains(InventoryAdjusted.class.getName());
            verify(repository, never()).save(any(OutboxEventEntity.class));
        }

        @Test
        @DisplayName("a null event is rejected and is not saved")
        void nullEventIsRejectedAndIsNotSaved() {
            assertThatThrownBy(() -> writer.write(null))
                    .isExactlyInstanceOf(IllegalArgumentException.class)
                    .hasMessage("event must be present");
            verify(repository, never()).save(any(OutboxEventEntity.class));
        }
    }

    private record InventoryAdjusted() {
    }

    @Nested
    @DisplayName("Row construction")
    @ExtendWith(MockitoExtension.class)
    class RowConstruction {

        @Mock
        private OutboxEventRepository repository;

        private OutboxWriter writer;

        @BeforeEach
        void setUp() {
            writer = new OutboxWriter(repository);
        }

        @Test
        @DisplayName("the saved row carries envelope identity and publication defaults")
        void savedRowCarriesEnvelopeIdentityAndPublicationDefaults() {
            returnSavedArgument(repository);
            ArgumentCaptor<OutboxEventEntity> rows =
                    ArgumentCaptor.forClass(OutboxEventEntity.class);
            FraudFlagged event = flaggedEvent();
            Instant beforeWrite = Instant.now();

            writer.write(event);

            Instant afterWrite = Instant.now();
            verify(repository).save(rows.capture());
            OutboxEventEntity row = rows.getValue();

            assertThat(row.getEventId()).isEqualTo(event.eventId());
            assertThat(row.getEventType()).isEqualTo(event.getClass().getSimpleName());
            assertThat(row.getAggregateId())
                    .isInstanceOf(String.class)
                    .isEqualTo(ACCOUNT_IDENTIFIER)
                    .hasSize(11)
                    .startsWith("0")
                    .matches("^[0-9]{11}$");
            assertThat(row.isPublished()).isFalse();
            assertThat(row.getPublishedAt()).isNull();
            assertThat(row.getCreatedAt()).isBetween(beforeWrite, afterWrite);
            assertThat(row.getCreatedAt())
                    .isNotEqualTo(FLAGGED_ENVELOPE_INSTANT)
                    .isNotEqualTo(ASSESSMENT_INSTANT);
        }
    }

    @Nested
    @DisplayName("Payload wire form")
    @ExtendWith(MockitoExtension.class)
    class PayloadWireForm {

        @Mock
        private OutboxEventRepository repository;

        private OutboxWriter writer;
        private ObjectMapper objectMapper;

        @BeforeEach
        void setUp() {
            writer = new OutboxWriter(repository);
            objectMapper = JsonMapper.builder().build();
        }

        @Test
        @DisplayName("a flagged payload is a flat ten-property schema document")
        void flaggedPayloadIsFlatTenPropertySchemaDocument() throws IOException {
            returnSavedArgument(repository);
            FraudFlagged event = flaggedEvent();

            String payload = writer.write(event).getPayload();
            JsonNode root = objectMapper.readTree(payload);
            Set<String> names = new LinkedHashSet<>(root.propertyNames());

            assertThat(names).isEqualTo(FLAGGED_PROPERTY_NAMES);
            assertThat(names).doesNotContain("envelope");
            assertThat(root.get("eventType").stringValue())
                    .isEqualTo(event.getClass().getSimpleName());
            assertBareSchemaVersion(payload, root);

            JsonNode riskScore = root.get("riskScore");
            assertThat(riskScore.isIntegralNumber()).isTrue();
            assertThat(riskScore.isInt()).isTrue();
            assertThat(riskScore.isString()).isFalse();
            assertThat(riskScore.intValue()).isEqualTo(RISK_SCORE);
            assertThat(root.get("triggeredRules").valueStream()
                    .map(JsonNode::stringValue)
                    .toList())
                    .containsExactlyElementsOf(TRIGGERED_RULES);

            assertTextEnvelopeValues(
                    root,
                    FLAGGED_EVENT_IDENTIFIER,
                    FLAGGED_ENVELOPE_INSTANT,
                    ASSESSMENT_INSTANT);
            assertCompactStorage(payload, root);
            assertSchemaAccepts("/schemas/fraud-flagged-v1.json", payload);
            assertCardDataAbsent(root);
        }

        @Test
        @DisplayName("a cleared payload is a flat eight-property schema document")
        void clearedPayloadIsFlatEightPropertySchemaDocument() throws IOException {
            returnSavedArgument(repository);
            FraudCleared event = clearedEvent();

            String payload = writer.write(event).getPayload();
            JsonNode root = objectMapper.readTree(payload);
            Set<String> names = new LinkedHashSet<>(root.propertyNames());

            assertThat(names).isEqualTo(CLEARED_PROPERTY_NAMES);
            assertThat(names).doesNotContain("envelope");
            assertThat(root.get("eventType").stringValue())
                    .isEqualTo(event.getClass().getSimpleName());
            assertBareSchemaVersion(payload, root);
            assertTextEnvelopeValues(
                    root,
                    CLEARED_EVENT_IDENTIFIER,
                    FLAGGED_ENVELOPE_INSTANT,
                    ASSESSMENT_INSTANT);
            assertCompactStorage(payload, root);
            assertSchemaAccepts("/schemas/fraud-cleared-v1.json", payload);
            assertCardDataAbsent(root);
        }

        private void assertBareSchemaVersion(String payload, JsonNode root) {
            assertThat(payload)
                    .contains("\"schemaVersion\":1")
                    .doesNotContain("\"schemaVersion\":\"1\"");
            assertThat(root.get("schemaVersion").isIntegralNumber()).isTrue();
            assertThat(root.get("schemaVersion").intValue())
                    .isEqualTo(EventEnvelope.SCHEMA_VERSION);
        }

        private void assertTextEnvelopeValues(
                JsonNode root,
                UUID eventIdentifier,
                Instant occurredAt,
                Instant assessedAt) {
            assertThat(root.get("eventId").isString()).isTrue();
            assertThat(root.get("eventId").stringValue()).isEqualTo(eventIdentifier.toString());
            assertThat(root.get("occurredAt").isString()).isTrue();
            assertThat(root.get("occurredAt").stringValue()).isEqualTo(occurredAt.toString());
            assertThat(root.get("assessedAt").isString()).isTrue();
            assertThat(root.get("assessedAt").stringValue()).isEqualTo(assessedAt.toString());
            assertThat(root.get("aggregateId").stringValue()).isEqualTo(ACCOUNT_IDENTIFIER);
            assertThat(root.get("accountId").stringValue()).isEqualTo(ACCOUNT_IDENTIFIER);
            assertThat(root.get("transactionId").stringValue())
                    .isEqualTo(TRANSACTION_IDENTIFIER);
        }

        private void assertCompactStorage(String payload, JsonNode root) {
            assertThat(payload).doesNotContain("\n", "  ");

            String compact = objectMapper.writeValueAsString(root);

            assertThat(compact).hasSize(payload.length());
        }

        private void assertSchemaAccepts(String resourceName, String payload) throws IOException {
            try (InputStream resource =
                    OutboxWriterTest.class.getResourceAsStream(resourceName)) {
                assertThat(resource)
                        .as("schema resource " + resourceName)
                        .isNotNull();
                SchemaRegistry registry = SchemaRegistry.withDefaultDialect(
                        SpecificationVersion.DRAFT_2020_12);
                Schema schema = registry.getSchema(resource, InputFormat.JSON);

                assertThat(schema.validate(payload, InputFormat.JSON))
                        .as("schema messages for " + resourceName)
                        .isEmpty();
            }
        }

        private void assertCardDataAbsent(JsonNode root) {
            Set<String> propertyNames = new LinkedHashSet<>();
            List<String> stringValues = new ArrayList<>();
            collectNamesAndStrings(root, propertyNames, stringValues);

            assertThat(propertyNames).doesNotContain("cardNumber", "maskedCardNumber");
            assertThat(propertyNames).noneMatch(name -> name.equalsIgnoreCase("cvv"));
            assertThat(stringValues)
                    .noneMatch(value -> MASKED_FORM_PATTERN.matcher(value).matches());
        }

        private void collectNamesAndStrings(
                JsonNode node,
                Set<String> propertyNames,
                List<String> stringValues) {
            if (node.isObject()) {
                for (Map.Entry<String, JsonNode> property : node.properties()) {
                    propertyNames.add(property.getKey());
                    collectNamesAndStrings(property.getValue(), propertyNames, stringValues);
                }
            } else if (node.isArray()) {
                node.valueStream()
                        .forEach(value -> collectNamesAndStrings(
                                value, propertyNames, stringValues));
            } else if (node.isString()) {
                stringValues.add(node.stringValue());
            }
        }
    }

    @Nested
    @DisplayName("Leak discipline")
    @ExtendWith(MockitoExtension.class)
    class LeakDiscipline {

        @Mock
        private OutboxEventRepository repository;

        private OutboxWriter writer;
        private Logger logger;
        private Level originalLevel;
        private ListAppender<ILoggingEvent> appender;

        @BeforeEach
        void setUp() {
            writer = new OutboxWriter(repository);
            logger = (Logger) LoggerFactory.getLogger(OutboxWriter.class);
            originalLevel = logger.getLevel();
            appender = new ListAppender<>();
            appender.setContext(logger.getLoggerContext());
            appender.start();
            logger.setLevel(Level.TRACE);
            logger.addAppender(appender);
        }

        @AfterEach
        void tearDown() {
            logger.detachAppender(appender);
            appender.stop();
            logger.setLevel(originalLevel);
        }

        @Test
        @DisplayName("successful and rejected writes expose no event data to the logger")
        void successfulAndRejectedWritesExposeNoEventDataToLogger() {
            returnSavedArgument(repository);
            OutboxEventEntity saved = writer.write(flaggedEvent());
            InventoryAdjusted unsupported = new InventoryAdjusted();

            IllegalArgumentException rejection = catchThrowableOfType(
                    IllegalArgumentException.class,
                    () -> writer.write(unsupported));
            List<String> capturedText = capturedText();

            assertThat(appender.list).isEmpty();
            assertThat(capturedText)
                    .noneMatch(text -> text.contains(saved.getPayload()));
            assertThat(capturedText)
                    .noneMatch(text -> LONG_DIGIT_RUN.matcher(text).find());
            assertThat(capturedText)
                    .noneMatch(text -> text.toLowerCase(java.util.Locale.ROOT).contains("cvv"));
            assertThat(rejection.getMessage()).contains(InventoryAdjusted.class.getName());
            assertThat(LONG_DIGIT_RUN.matcher(rejection.getMessage()).find()).isFalse();
            verify(repository, times(1)).save(any(OutboxEventEntity.class));
        }

        private List<String> capturedText() {
            List<String> captured = new ArrayList<>();
            for (ILoggingEvent event : appender.list) {
                if (event.getMessage() != null) {
                    captured.add(event.getMessage());
                }
                if (event.getFormattedMessage() != null) {
                    captured.add(event.getFormattedMessage());
                }
                Object[] arguments = event.getArgumentArray();
                if (arguments != null) {
                    Arrays.stream(arguments)
                            .map(String::valueOf)
                            .forEach(captured::add);
                }
            }
            return captured;
        }
    }
}
