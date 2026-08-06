package com.carddemo.notification.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.carddemo.events.serde.JsonSchemaValidatingDeserializer;
import com.carddemo.notification.messaging.DeadLetterMetadata;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.ParameterizedType;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.OffsetAndMetadata;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.errors.SerializationException;
import org.apache.kafka.common.header.Headers;
import org.apache.kafka.common.header.internals.RecordHeaders;
import org.apache.kafka.common.serialization.ByteArraySerializer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.kafka.autoconfigure.ConcurrentKafkaListenerContainerFactoryConfigurer;
import org.springframework.boot.kafka.autoconfigure.KafkaConnectionDetails;
import org.springframework.boot.kafka.autoconfigure.KafkaProperties;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.core.env.Environment;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.config.KafkaListenerContainerFactory;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaAdmin;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.ContainerProperties;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.kafka.listener.ListenerExecutionFailedException;
import org.springframework.kafka.listener.MessageListenerContainer;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.kafka.support.serializer.DeserializationException;
import org.springframework.kafka.support.serializer.ErrorHandlingDeserializer;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.util.backoff.FixedBackOff;

/**
 * Verifies the notification Kafka consumer wiring without opening external connections.
 *
 * <p>A rejected value may carry a Primary Account Number (PAN). The sanitization checks ensure no
 * rejected key, value, or foreign header leaves the consumer boundary.</p>
 *
 * <p>{@code app/cpy/CSMSG02Y.cpy:L21-L29} defines the four diagnostic widths.
 * {@code app/cbl/CBTRN02C.cbl:L707-L727} supplies the failure-record provenance.</p>
 *
 * <p>Design decisions: {@code card-platform/docs/decision-log.md}.</p>
 * <p>Traceability: {@code card-platform/docs/traceability-matrix.md}.</p>
 */
@DisplayName("KafkaConsumerConfig, local consumer wiring and sanitized dead-letter recovery")
final class KafkaConsumerConfigTest {

    private static final String FACTORY_BEAN = "kafkaListenerContainerFactory";
    private static final String SOURCE_TOPIC = "transaction.posted";
    private static final String SECOND_SOURCE_TOPIC = "fraud.assessed";
    private static final String BROKER_ADDRESS = "broker.invalid:9092";
    private static final String FALLBACK_TOPIC = "carddemo.dead-letter";
    private static final String SUFFIX = ".DLT";
    private static final String REFUSED_KEY = "REFUSED_KEY";
    private static final byte[] REFUSED_VALUE =
            "REFUSED_VALUE".getBytes(StandardCharsets.UTF_8);

    private static final String MAX_ATTEMPTS_PROPERTY =
            "carddemo.consumer.retry.max-attempts";
    private static final String BACKOFF_PROPERTY = "carddemo.consumer.retry.backoff-ms";
    private static final String DEAD_LETTER_TOPIC_PROPERTY =
            "carddemo.kafka.topics.dead-letter";
    private static final String DEAD_LETTER_SUFFIX_PROPERTY =
            "carddemo.kafka.topics.dead-letter-suffix";
    private static final String ADMIN_FAIL_FAST_PROPERTY = "spring.kafka.admin.fail-fast";

    private static final int MAX_ATTEMPTS = 3;
    private static final long BACKOFF_MS = 1000L;

    /** Loads the shipped consumer settings and supplies connection-free collaborators. */
    private static final ApplicationContextRunner RUNNER = new ApplicationContextRunner()
            .withInitializer(new ConfigDataApplicationContextInitializer())
            .withPropertyValues(
                    MAX_ATTEMPTS_PROPERTY + "=" + MAX_ATTEMPTS,
                    BACKOFF_PROPERTY + "=" + BACKOFF_MS,
                    DEAD_LETTER_TOPIC_PROPERTY + "=" + FALLBACK_TOPIC,
                    DEAD_LETTER_SUFFIX_PROPERTY + "=" + SUFFIX)
            .withUserConfiguration(Collaborators.class, ObservabilityConfig.class,
                    KafkaConsumerConfig.class);

    /** Loads only the shipped configuration file for raw property assertions. */
    private static final ApplicationContextRunner CONFIG_DATA_RUNNER =
            new ApplicationContextRunner()
                    .withInitializer(new ConfigDataApplicationContextInitializer());

    @Test
    void factoryUsesTheInjectedConsumerSettingsAndErrorHandler() {
        RUNNER.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context.getBeanNamesForType(
                    ConcurrentKafkaListenerContainerFactory.class))
                    .containsExactly(FACTORY_BEAN);
            assertThat(context.getBeanNamesForType(KafkaListenerContainerFactory.class))
                    .containsExactly(FACTORY_BEAN);

            ConcurrentKafkaListenerContainerFactory<?, ?> factory =
                    context.getBean(FACTORY_BEAN,
                            ConcurrentKafkaListenerContainerFactory.class);
            ConsumerFactory<?, ?> consumerFactory = context.getBean(ConsumerFactory.class);
            DefaultErrorHandler errorHandler = context.getBean(DefaultErrorHandler.class);
            KafkaProperties properties = context.getBean(KafkaProperties.class);

            assertThat(factory.getConsumerFactory()).isSameAs(consumerFactory);
            assertThat(ReflectionTestUtils.getField(factory, "commonErrorHandler"))
                    .isSameAs(errorHandler);
            assertThat(factory.getContainerProperties().getAckMode())
                    .isEqualTo(ContainerProperties.AckMode.MANUAL_IMMEDIATE);
            assertThat(factory.getContainerProperties().isDeliveryAttemptHeader()).isTrue();

            assertThat(properties.getConsumer().getEnableAutoCommit()).isFalse();
            assertThat(properties.getConsumer().getKeyDeserializer())
                    .isEqualTo(StringDeserializer.class);
            assertThat(properties.getConsumer().getValueDeserializer())
                    .isEqualTo(ErrorHandlingDeserializer.class);
            assertThat(properties.getConsumer().getProperties())
                    .containsEntry(ErrorHandlingDeserializer.VALUE_DESERIALIZER_CLASS,
                            JsonSchemaValidatingDeserializer.class.getName());

            assertThat(consumerFactory).isInstanceOf(DefaultKafkaConsumerFactory.class);
            DefaultKafkaConsumerFactory<?, ?> defaultFactory =
                    (DefaultKafkaConsumerFactory<?, ?>) consumerFactory;
            assertThat(defaultFactory.getConfigurationProperties())
                    .containsEntry(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false)
                    .containsEntry(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG,
                            StringDeserializer.class)
                    .containsEntry(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG,
                            ErrorHandlingDeserializer.class)
                    .containsEntry(ErrorHandlingDeserializer.VALUE_DESERIALIZER_CLASS,
                            JsonSchemaValidatingDeserializer.class.getName());
            assertThat(defaultFactory.getKeyDeserializer()).isNull();
            assertThat(defaultFactory.getValueDeserializer()).isNull();
        });
    }

    @Test
    void errorHandlerUsesTheConfiguredFixedBackOff() {
        RUNNER.run(context -> {
            DefaultErrorHandler errorHandler = context.getBean(DefaultErrorHandler.class);
            FixedBackOff backOff = configuredBackOff(errorHandler);

            assertThat(backOff.getInterval()).isEqualTo(BACKOFF_MS);
            assertThat(backOff.getMaxAttempts())
                    .as("FixedBackOff counts retries, so three deliveries carry two retries")
                    .isEqualTo(MAX_ATTEMPTS - 1L);
            assertThat(ReflectionTestUtils.getField(errorHandler, "commitRecovered"))
                    .isEqualTo(Boolean.TRUE);
        });
    }

    @Test
    void refusedPayloadAndSchemaFailuresRecoverOnTheFirstPass() {
        assertRecoveredOnFirstPass(new DeserializationException(
                "rejected input", REFUSED_VALUE, false,
                new IllegalArgumentException("invalid envelope")));
        assertRecoveredOnFirstPass(new SerializationException("schema violation"));
    }

    @Test
    void retryableFailureReachesRecoveryAfterItsDeliveryBudget() {
        DeadLetterPublishingRecoverer recoverer = mock(DeadLetterPublishingRecoverer.class);
        DefaultErrorHandler errorHandler = errorHandler(recoverer, MAX_ATTEMPTS, 0L);
        ConsumerRecord<String, byte[]> record =
                new ConsumerRecord<>(SOURCE_TOPIC, 1, 7L, REFUSED_KEY, REFUSED_VALUE);
        Consumer<?, ?> consumer = mock(Consumer.class);
        MessageListenerContainer container = manualImmediateContainer();
        ListenerExecutionFailedException failure = new ListenerExecutionFailedException(
                "listener failed", new IllegalStateException("write unavailable"));

        assertThat(errorHandler.handleOne(failure, record, consumer, container)).isFalse();
        verifyNoInteractions(recoverer);
        assertThat(errorHandler.handleOne(failure, record, consumer, container)).isFalse();
        verifyNoInteractions(recoverer);
        assertThat(errorHandler.handleOne(failure, record, consumer, container)).isTrue();
        verify(recoverer).accept(record, failure);
    }

    @Test
    void templateAndRecovererUseByteSerializationAndConfiguredDestinations() {
        RUNNER.run(context -> {
            assertThat(context).hasSingleBean(KafkaTemplate.class);
            assertThat(context).hasSingleBean(DeadLetterPublishingRecoverer.class);

            KafkaTemplate<?, ?> template = context.getBean(KafkaTemplate.class);
            assertThat(template.getProducerFactory())
                    .isInstanceOf(DefaultKafkaProducerFactory.class);
            assertThat(template.getProducerFactory().getConfigurationProperties())
                    .containsEntry(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG,
                            List.of(BROKER_ADDRESS))
                    .containsEntry(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG,
                            StringSerializer.class)
                    .containsEntry(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG,
                            ByteArraySerializer.class);

            Method method = beanMethods().stream()
                    .filter(candidate -> candidate.getName().equals("deadLetterKafkaTemplate"))
                    .findFirst()
                    .orElseThrow();
            assertThat(method.getGenericReturnType()).isInstanceOf(ParameterizedType.class);
            ParameterizedType returnType = (ParameterizedType) method.getGenericReturnType();
            assertThat(returnType.getActualTypeArguments())
                    .containsExactly(String.class, byte[].class);

            String fallback = context.getEnvironment()
                    .getRequiredProperty(DEAD_LETTER_TOPIC_PROPERTY);
            String suffix = context.getEnvironment()
                    .getRequiredProperty(DEAD_LETTER_SUFFIX_PROPERTY);
            assertThat(KafkaConsumerConfig.resolveDeadLetterDestination(
                    recordFor(SOURCE_TOPIC), fallback, suffix))
                    .isEqualTo(SOURCE_TOPIC + SUFFIX);
            assertThat(KafkaConsumerConfig.resolveDeadLetterDestination(
                    recordFor(SECOND_SOURCE_TOPIC), fallback, suffix))
                    .isEqualTo(SECOND_SOURCE_TOPIC + SUFFIX);
            assertThat(KafkaConsumerConfig.resolveDeadLetterDestination(null, fallback, suffix))
                    .isEqualTo(FALLBACK_TOPIC);
        });
    }

    @Test
    void deadLetterRecordCarriesOnlyCoordinatesAndFixedWidthDiagnostics() {
        ConsumerRecord<String, byte[]> refused =
                new ConsumerRecord<>(SOURCE_TOPIC, 2, 41L, REFUSED_KEY, REFUSED_VALUE);
        refused.headers().add("foreign-header", REFUSED_VALUE);
        refused.headers().add(KafkaConsumerConfig.HEADER_MESSAGE, REFUSED_VALUE);

        DeadLetterMetadata metadata = KafkaConsumerConfig.metadataOf(
                new IllegalArgumentException("rejected input"));
        Headers assembled = new RecordHeaders(refused.headers());
        assembled.add(KafkaConsumerConfig.HEADER_ABEND_CODE, bytes(metadata.abendCode()));
        assembled.add(KafkaConsumerConfig.HEADER_CULPRIT, bytes(metadata.culprit()));
        assembled.add(KafkaConsumerConfig.HEADER_REASON, bytes(metadata.reason()));
        assembled.add(KafkaConsumerConfig.HEADER_MESSAGE, bytes(metadata.message()));
        assembled.add(KafkaHeaders.DLT_EXCEPTION_MESSAGE, bytes(metadata.message()));
        assembled.add(KafkaHeaders.DLT_ORIGINAL_TOPIC, bytes(SOURCE_TOPIC));

        ProducerRecord<Object, Object> sanitized =
                KafkaConsumerConfig.sanitizedDeadLetterRecord(
                        refused, new TopicPartition(SOURCE_TOPIC + SUFFIX, -1), assembled);

        assertThat(sanitized.topic()).isEqualTo(SOURCE_TOPIC + SUFFIX);
        assertThat(sanitized.key()).isEqualTo(SOURCE_TOPIC + "-2-41");
        assertThat(sanitized.key()).isNotEqualTo(REFUSED_KEY);

        byte[] outgoingValue = (byte[]) sanitized.value();
        assertThat(outgoingValue).hasSize(DeadLetterMetadata.RECORD_LENGTH);
        assertThat(Arrays.equals(REFUSED_VALUE, outgoingValue)).isFalse();
        String diagnostic = new String(outgoingValue, StandardCharsets.UTF_8);
        assertThat(diagnostic).doesNotContain(REFUSED_KEY, "REFUSED_VALUE");
        assertThat(diagnostic).contains(KafkaConsumerConfig.SAFE_FAILURE_MESSAGE);

        Set<String> outgoingHeaders = StreamSupport.stream(
                        sanitized.headers().spliterator(), false)
                .map(header -> header.key())
                .collect(Collectors.toUnmodifiableSet());
        assertThat(outgoingHeaders).doesNotContain("foreign-header");
        assertThat(KafkaConsumerConfig.ALLOWED_HEADERS).containsAll(outgoingHeaders);
        assertThat(outgoingHeaders).containsExactlyInAnyOrder(
                KafkaConsumerConfig.HEADER_ABEND_CODE,
                KafkaConsumerConfig.HEADER_CULPRIT,
                KafkaConsumerConfig.HEADER_REASON,
                KafkaConsumerConfig.HEADER_MESSAGE,
                KafkaHeaders.DLT_EXCEPTION_MESSAGE,
                KafkaHeaders.DLT_ORIGINAL_TOPIC);
        assertThat(StreamSupport.stream(
                        sanitized.headers().headers(KafkaConsumerConfig.HEADER_MESSAGE)
                                .spliterator(), false))
                .hasSize(1);
    }

    @Test
    void deadLetteredRecordCommitsItsFollowingOffset() {
        DefaultErrorHandler errorHandler =
                errorHandler(mock(DeadLetterPublishingRecoverer.class), 1, 0L);
        Consumer<?, ?> consumer = mock(Consumer.class);
        ConsumerRecord<String, byte[]> refused =
                new ConsumerRecord<>(SOURCE_TOPIC, 1, 4L, REFUSED_KEY, REFUSED_VALUE);

        assertThat(ReflectionTestUtils.getField(errorHandler, "commitRecovered"))
                .isEqualTo(Boolean.TRUE);
        assertThat(errorHandler.isAckAfterHandle()).isTrue();
        assertThat(errorHandler.seeksAfterHandling()).isTrue();

        errorHandler.handleRemaining(new IllegalStateException("write unavailable"),
                List.of(refused), consumer, manualImmediateContainer());

        verify(consumer).commitSync(
                eq(Map.of(new TopicPartition(SOURCE_TOPIC, 1), new OffsetAndMetadata(5L))),
                nullable(Duration.class));
    }

    @Test
    void metadataUsesCopybookWidthsAndDeclaresNoKafkaType() {
        assertThat(List.of(
                DeadLetterMetadata.ABEND_CODE_MAX_LENGTH,
                DeadLetterMetadata.CULPRIT_MAX_LENGTH,
                DeadLetterMetadata.REASON_MAX_LENGTH,
                DeadLetterMetadata.MESSAGE_MAX_LENGTH))
                .containsExactly(4, 8, 50, 72);

        DeadLetterMetadata metadata =
                DeadLetterMetadata.of("9", "NOTIF", null, "blocked");
        assertThat(metadata.abendCode()).isEqualTo("9   ");
        assertThat(metadata.culprit()).isEqualTo("NOTIF   ");
        assertThat(metadata.reason()).isEqualTo(" ".repeat(50));
        assertThat(metadata.message()).hasSize(72).startsWith("blocked").endsWith(" ");
        assertThat(List.of(metadata.abendCode(), metadata.culprit(), metadata.reason(),
                metadata.message())).doesNotContainNull();
        assertThat(metadata.toFixedWidthRecord()).hasSize(DeadLetterMetadata.RECORD_LENGTH);

        DeadLetterMetadata spaces = DeadLetterMetadata.allSpaces();
        assertThat(spaces.abendCode()).isEqualTo(" ".repeat(4));
        assertThat(spaces.culprit()).isEqualTo(" ".repeat(8));
        assertThat(spaces.reason()).isEqualTo(" ".repeat(50));
        assertThat(spaces.message()).isEqualTo(" ".repeat(72));

        assertThat(declaredTypeNames(DeadLetterMetadata.class))
                .noneMatch(type -> type.contains("org.apache.kafka.")
                        || type.contains("org.springframework.kafka."));
    }

    @Test
    void failureClassificationPreservesDatabaseAndSchemaPrecedence() {
        String fallback = KafkaConsumerConfig.failureKindOf(
                new IllegalArgumentException("invalid aggregate"));
        String persistence = KafkaConsumerConfig.failureKindOf(new IllegalStateException(
                "write unavailable",
                new CannotCreateTransactionException("connection unavailable")));
        String schema = KafkaConsumerConfig.failureKindOf(
                new SerializationException("schema violation"));
        String wrappedSchema = KafkaConsumerConfig.failureKindOf(
                new DeserializationException("rejected input", REFUSED_VALUE, false,
                        new SerializationException("schema violation")));
        String deserialization = KafkaConsumerConfig.failureKindOf(
                new DeserializationException("rejected input", REFUSED_VALUE, false,
                        new IllegalArgumentException("invalid envelope")));

        assertThat(persistence).isNotEqualTo(fallback);
        assertThat(wrappedSchema).isEqualTo(schema);
        assertThat(wrappedSchema).isNotEqualTo(deserialization);
    }

    @Test
    void configurationDeclaresOnlyTheExpectedInfrastructureBeans() {
        RUNNER.run(context -> {
            assertThat(context).doesNotHaveBean(NewTopic.class);
            assertThat(context).doesNotHaveBean(KafkaAdmin.class);
            assertThat(context).hasSingleBean(KafkaTemplate.class);
            assertThat(Arrays.stream(context.getBeanDefinitionNames())
                    .map(context::getType)
                    .filter(type -> type != null)
                    .map(Class::getSimpleName))
                    .noneMatch("EventPublisherPort"::equals);
        });

        List<Method> methods = beanMethods();
        assertThat(methods).hasSize(4);
        assertThat(methods).extracting(Method::getReturnType)
                .containsExactlyInAnyOrder(
                        KafkaTemplate.class,
                        DeadLetterPublishingRecoverer.class,
                        DefaultErrorHandler.class,
                        ConcurrentKafkaListenerContainerFactory.class);
    }

    @Test
    void configurationAndBeanMethodsRemainOpenForTheContainer() {
        int classModifiers = KafkaConsumerConfig.class.getModifiers();
        assertThat(Modifier.isPublic(classModifiers)).isTrue();
        assertThat(Modifier.isFinal(classModifiers)).isFalse();

        assertThat(beanMethods()).allSatisfy(method -> {
            int modifiers = method.getModifiers();
            assertThat(Modifier.isPrivate(modifiers)).isFalse();
            assertThat(Modifier.isFinal(modifiers)).isFalse();
            assertThat(Modifier.isStatic(modifiers)).isFalse();
        });
    }

    @Test
    void shippedFileDeclaresRetryAndDeadLetterPropertiesWithoutAdminFailFast() {
        CONFIG_DATA_RUNNER.run(context -> {
            Environment environment = context.getEnvironment();

            assertThat(environment.getRequiredProperty(
                    MAX_ATTEMPTS_PROPERTY, Integer.class)).isEqualTo(MAX_ATTEMPTS);
            assertThat(environment.getRequiredProperty(
                    BACKOFF_PROPERTY, Long.class)).isEqualTo(BACKOFF_MS);
            assertThat(environment.getRequiredProperty(DEAD_LETTER_TOPIC_PROPERTY))
                    .isEqualTo(FALLBACK_TOPIC);
            assertThat(environment.getRequiredProperty(DEAD_LETTER_SUFFIX_PROPERTY))
                    .isEqualTo(SUFFIX);
            assertThat(environment.getProperty(ADMIN_FAIL_FAST_PROPERTY)).isNull();
        });
    }

    /** Returns the configured fixed back-off from the handler's failure tracker. */
    private static FixedBackOff configuredBackOff(DefaultErrorHandler errorHandler) {
        Object failureTracker = ReflectionTestUtils.getField(errorHandler, "failureTracker");
        assertThat(failureTracker).isNotNull();
        Object backOff = ReflectionTestUtils.getField(failureTracker, "backOff");
        assertThat(backOff).isInstanceOf(FixedBackOff.class);
        return (FixedBackOff) backOff;
    }

    /** Verifies one non-retryable exception reaches its injected route immediately. */
    private static void assertRecoveredOnFirstPass(Exception failure) {
        DeadLetterPublishingRecoverer recoverer = mock(DeadLetterPublishingRecoverer.class);
        DefaultErrorHandler errorHandler = errorHandler(recoverer, MAX_ATTEMPTS, 0L);
        ConsumerRecord<String, byte[]> record =
                new ConsumerRecord<>(SOURCE_TOPIC, 0, 3L, REFUSED_KEY, REFUSED_VALUE);

        assertThat(errorHandler.handleOne(failure, record, mock(Consumer.class),
                manualImmediateContainer())).isTrue();
        verify(recoverer).accept(record, failure);
    }

    /** Builds the production handler with local metrics and the supplied retry values. */
    private static DefaultErrorHandler errorHandler(
            DeadLetterPublishingRecoverer recoverer, int attempts, long backoffMs) {
        return new KafkaConsumerConfig().notificationConsumerErrorHandler(
                recoverer,
                new ObservabilityConfig().notificationMetrics(new SimpleMeterRegistry()),
                attempts,
                backoffMs);
    }

    /** Supplies manual-immediate acknowledgement settings without starting a container. */
    private static MessageListenerContainer manualImmediateContainer() {
        ContainerProperties properties = new ContainerProperties(SOURCE_TOPIC);
        properties.setAckMode(ContainerProperties.AckMode.MANUAL_IMMEDIATE);
        MessageListenerContainer container = mock(MessageListenerContainer.class);
        when(container.getContainerProperties()).thenReturn(properties);
        return container;
    }

    /** Builds a minimal record for destination selection. */
    private static ConsumerRecord<String, byte[]> recordFor(String topic) {
        return new ConsumerRecord<>(topic, 0, 1L, REFUSED_KEY, REFUSED_VALUE);
    }

    /** Returns the bean methods declared by the production configuration. */
    private static List<Method> beanMethods() {
        return Arrays.stream(KafkaConsumerConfig.class.getDeclaredMethods())
                .filter(method -> method.isAnnotationPresent(Bean.class))
                .toList();
    }

    /** Returns every field, constructor, parameter, and method type the class declares. */
    private static List<String> declaredTypeNames(Class<?> type) {
        List<String> names = new ArrayList<>();
        for (Field field : type.getDeclaredFields()) {
            names.add(field.getGenericType().getTypeName());
        }
        for (Constructor<?> constructor : type.getDeclaredConstructors()) {
            Arrays.stream(constructor.getGenericParameterTypes())
                    .map(java.lang.reflect.Type::getTypeName)
                    .forEach(names::add);
        }
        for (Method method : type.getDeclaredMethods()) {
            names.add(method.getGenericReturnType().getTypeName());
            Arrays.stream(method.getGenericParameterTypes())
                    .map(java.lang.reflect.Type::getTypeName)
                    .forEach(names::add);
        }
        return List.copyOf(names);
    }

    private static byte[] bytes(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }

    /** Supplies only the collaborators the consumer configuration requires. */
    static class Collaborators {

        @Bean
        KafkaProperties kafkaProperties(Environment environment) {
            KafkaProperties properties = new KafkaProperties();
            Binder binder = Binder.get(environment);
            binder.bind("spring.kafka.consumer",
                    Bindable.ofInstance(properties.getConsumer()));
            binder.bind("spring.kafka.listener",
                    Bindable.ofInstance(properties.getListener()));
            properties.setBootstrapServers(List.of(BROKER_ADDRESS));
            return properties;
        }

        @Bean
        KafkaConnectionDetails kafkaConnectionDetails() {
            return () -> List.of(BROKER_ADDRESS);
        }

        @Bean
        ConsumerFactory<Object, Object> consumerFactory(KafkaProperties properties) {
            return new DefaultKafkaConsumerFactory<>(properties.buildConsumerProperties());
        }

        @Bean
        ConcurrentKafkaListenerContainerFactoryConfigurer containerFactoryConfigurer(
                KafkaProperties properties) {
            ConcurrentKafkaListenerContainerFactoryConfigurer configurer =
                    new ConcurrentKafkaListenerContainerFactoryConfigurer();
            ReflectionTestUtils.setField(configurer, "properties", properties);
            return configurer;
        }

        @Bean
        SimpleMeterRegistry meterRegistry() {
            return new SimpleMeterRegistry();
        }
    }
}
