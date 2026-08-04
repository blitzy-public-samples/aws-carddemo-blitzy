package com.carddemo.fraud.config;

import com.carddemo.events.TransactionAuthorized;
import com.carddemo.events.serde.JsonSchemaValidatingDeserializer;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.errors.SerializationException;
import org.apache.kafka.common.header.Headers;
import org.apache.kafka.common.serialization.ByteArraySerializer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.kafka.autoconfigure.KafkaProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaOperations;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.core.ProducerFactory;
import org.springframework.kafka.listener.ContainerProperties;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer.HeaderNames.HeadersToAdd;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.kafka.support.serializer.DeserializationException;
import org.springframework.kafka.support.serializer.ErrorHandlingDeserializer;
import org.springframework.util.backoff.FixedBackOff;

/**
 * Kafka consumer wiring for the fraud detection service: one consumer factory, one listener
 * container factory, and the route a failed record takes.
 *
 * <p>ADDITIVE IN FULL: net new; no COBOL ancestor. Searching {@code app/cbl/} for {@code fraud},
 * {@code velocit}, {@code risk} and {@code scoring} matches zero of its 28 programs.
 *
 * <p>The listener reads topic {@code transaction.authorized} in consumer group
 * {@code fraud-detection}. It reaches no other service and stays outside the authorization response
 * path. The value deserializer is an instance this class builds: {@link
 * JsonSchemaValidatingDeserializer} for {@link TransactionAuthorized}, wrapped in {@link
 * ErrorHandlingDeserializer}. A record that fails to parse reaches the listener with a
 * {@code null} value, and the failure travels in a header.
 *
 * <p>Two failures, two behaviours. A deserialization or schema failure routes straight to topic
 * {@code carddemo.dead-letter} and is not retried. An infrastructure failure is retried, three
 * attempts at 1000 ms, and then routes to the same topic. That route replaces the four-line abend
 * at {@code app/cbl/CBTRN02C.cbl:L707-L711} (shape only, no logic).
 *
 * <p>A key holds the eleven-digit account identifier from {@code XREF-ACCT-ID PIC 9(11)} at
 * {@code app/cpy/CVACT03Y.cpy:L7} (shape only, no logic) and travels as text, so a leading zero
 * survives. Automatic commit is off and the acknowledgement mode is {@link
 * ContainerProperties.AckMode#MANUAL_IMMEDIATE}, so a listener commits its offset once its own
 * writes commit. One record reaches the listener per call, and no batch listener is registered.
 *
 * <p>A listener names {@link #LISTENER_CONTAINER_FACTORY_BEAN} in
 * {@code @KafkaListener(containerFactory = ...)}, and that one bean also answers to
 * {@code kafkaListenerContainerFactory}. The dead-letter template is
 * {@code deadLetterKafkaTemplate} and writes raw bytes; the outbox template is
 * {@code fraudEventKafkaTemplate} in {@code config/KafkaProducerConfig.java}. Adding a consumer
 * means adding a listener that names the factory, and adding a topic means adding one binding.
 *
 * <p>Three facts a reader needs. Every class in {@code com.carddemo.cobol} is final, holds static
 * members only and keeps a private constructor, so no bean method here returns one. This module
 * compiles at release 25 while the Spring Boot parent defaults to 17, and class-file major
 * version 69 is the proof. The wire form is flat: an event nested under an {@code envelope} key
 * fails every schema document this platform ships.
 *
 * <p>No failure this class configures reports a field value or a payload.
 * {@code messaging/DeadLetterMetadata.java} builds the diagnostic record and
 * {@code config/ObservabilityConfig.java} declares the meters.
 *
 * <p>Design decisions: {@code card-platform/docs/decision-log.md} (planned).
 */
@Configuration
public class KafkaConsumerConfig {

    /** Bean name of the listener container factory, and the name a listener of this service names. */
    public static final String LISTENER_CONTAINER_FACTORY_BEAN =
            "transactionAuthorizedListenerContainerFactory";

    /**
     * The conventional name the same factory also answers to. Spring Boot declares a factory of its
     * own only while no bean carries this name, so this context holds one.
     */
    static final String DEFAULT_LISTENER_CONTAINER_FACTORY_BEAN = "kafkaListenerContainerFactory";

    /** Bean name of the consumer factory, and the name the container factory asks for. */
    static final String CONSUMER_FACTORY_BEAN = "transactionAuthorizedConsumerFactory";

    /** Bean name of the dead-letter template, distinct from the outbox template. */
    static final String DEAD_LETTER_TEMPLATE_BEAN = "deadLetterKafkaTemplate";

    /** Bean name of the error handler the container factory carries. */
    static final String ERROR_HANDLER_BEAN = "transactionAuthorizedErrorHandler";

    /** Offset a new consumer group starts from, so an event published earlier still arrives. */
    private static final String OFFSET_RESET_EARLIEST = "earliest";

    /** Acknowledgement from every in-sync replica, the setting all six services carry. */
    private static final String ACKS_FROM_ALL_REPLICAS = "all";

    /** Destination partition the producer selects from the message key. */
    private static final int PARTITION_BY_KEY = -1;

    /** The delivery every record takes before the first retry. */
    private static final long FIRST_ATTEMPT = 1L;

    /** Opening characters of a placeholder no property source resolved. */
    private static final String UNRESOLVED_PLACEHOLDER = "${";

    private final String bootstrapServers;
    private final String consumedTopic;
    private final String consumerGroup;
    private final String deadLetterTopic;
    private final long deliveryAttempts;
    private final long retryBackoffMs;

    /**
     * Reads the broker address, two topic names, the consumer group and two retry settings, each
     * from a property carrying its default.
     *
     * <p>An unset property leaves the default in place: {@code kafka:9092} for the address,
     * {@code transaction.authorized} and {@code carddemo.dead-letter} for the topics,
     * {@code fraud-detection} for the group. A blank or unresolved name stops start-up with the
     * property named, and {@link FraudProperties} checks the range of both retry settings. No
     * failure message here holds a value read from configuration.
     *
     * @param bootstrapServers the broker address, from {@code spring.kafka.bootstrap-servers}
     * @param consumedTopic    the read topic, from the {@code carddemo.kafka.topics} block
     * @param consumerGroup    the consumer group, from {@code spring.kafka.consumer.group-id}
     * @param deadLetterTopic  the dead-letter topic, from the {@code carddemo.kafka.topics} block
     * @param deliveryAttempts delivery attempts, from the {@code carddemo.consumer.retry} block
     * @param retryBackoffMs   the wait between two attempts, from the same block
     * @throws IllegalArgumentException when a name resolves to no usable value
     */
    public KafkaConsumerConfig(
            @Value("${spring.kafka.bootstrap-servers:kafka:9092}") String bootstrapServers,
            @Value("${carddemo.kafka.topics.transaction-authorized:transaction.authorized}")
                    String consumedTopic,
            @Value("${spring.kafka.consumer.group-id:fraud-detection}") String consumerGroup,
            @Value("${carddemo.kafka.topics.dead-letter:carddemo.dead-letter}")
                    String deadLetterTopic,
            @Value("${carddemo.consumer.retry.max-attempts:3}") long deliveryAttempts,
            @Value("${carddemo.consumer.retry.backoff-ms:1000}") long retryBackoffMs) {
        this.bootstrapServers = resolved(bootstrapServers, "spring.kafka.bootstrap-servers");
        this.consumedTopic =
                resolved(consumedTopic, "carddemo.kafka.topics.transaction-authorized");
        this.consumerGroup = resolved(consumerGroup, "spring.kafka.consumer.group-id");
        this.deadLetterTopic = resolved(deadLetterTopic, "carddemo.kafka.topics.dead-letter");
        this.deliveryAttempts = deliveryAttempts;
        this.retryBackoffMs = retryBackoffMs;
    }

    /**
     * Returns the topic a listener of this service subscribes to.
     *
     * @return the resolved topic name, never blank
     */
    public String transactionAuthorizedTopic() {
        return consumedTopic;
    }

    /**
     * Builds the consumer the listener of this service reads through.
     *
     * <p>The settings start from the bound {@code spring.kafka} block, which carries the broker
     * authentication and the client timeouts the shipped file declares. Four settings are pinned
     * here: the broker address, the consumer group, automatic commit off, and the earliest offset.
     * The two deserializer class entries and both delegate class entries are dropped, and the
     * factory holds the two deserializer instances this method constructs.
     *
     * @param kafkaProperties the bound {@code spring.kafka} block
     * @return the consumer factory the container factory reads through
     */
    @Bean(name = CONSUMER_FACTORY_BEAN)
    public ConsumerFactory<String, TransactionAuthorized> transactionAuthorizedConsumerFactory(
            KafkaProperties kafkaProperties) {
        Map<String, Object> settings =
                new LinkedHashMap<>(kafkaProperties.buildConsumerProperties());

        settings.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        settings.put(ConsumerConfig.GROUP_ID_CONFIG, consumerGroup);
        settings.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, Boolean.FALSE);
        settings.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, OFFSET_RESET_EARLIEST);

        settings.remove(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG);
        settings.remove(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG);
        settings.remove(ErrorHandlingDeserializer.KEY_DESERIALIZER_CLASS);
        settings.remove(ErrorHandlingDeserializer.VALUE_DESERIALIZER_CLASS);

        ErrorHandlingDeserializer<TransactionAuthorized> valueDeserializer =
                new ErrorHandlingDeserializer<>(
                        new JsonSchemaValidatingDeserializer<>(TransactionAuthorized.class));

        return new DefaultKafkaConsumerFactory<>(settings, new StringDeserializer(),
                valueDeserializer);
    }

    /**
     * Builds the template a failed record is published through.
     *
     * <p>Production is idempotent and acknowledged by every in-sync replica. The key serializer
     * writes text and the value serializer writes raw bytes, so a record that broke its schema
     * document travels as it arrived.
     *
     * @param kafkaProperties the bound {@code spring.kafka} block
     * @return the template the error handler injects as {@code deadLetterKafkaTemplate}
     */
    @Bean(name = DEAD_LETTER_TEMPLATE_BEAN)
    public KafkaTemplate<String, byte[]> deadLetterKafkaTemplate(KafkaProperties kafkaProperties) {
        Map<String, Object> settings =
                new LinkedHashMap<>(kafkaProperties.buildProducerProperties());

        settings.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        settings.put(ProducerConfig.ACKS_CONFIG, ACKS_FROM_ALL_REPLICAS);
        settings.put(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, Boolean.TRUE);

        settings.remove(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG);
        settings.remove(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG);

        ProducerFactory<String, byte[]> producerFactory = new DefaultKafkaProducerFactory<>(
                settings, new StringSerializer(), new ByteArraySerializer());

        KafkaTemplate<String, byte[]> template = new KafkaTemplate<>(producerFactory);
        template.setDefaultTopic(deadLetterTopic);
        return template;
    }

    /**
     * Builds the error handler the listener container carries.
     *
     * <p>The backoff carries the bound attempt count and the bound wait, and both deserialization
     * exception types are registered as not retryable. The recoverer publishes every failed record
     * on the dead-letter topic, and the producer selects the partition from the message key. The
     * outgoing record names the failing exception class and its cause class. Its exception message
     * header and its stack-trace header are excluded. A parse failure names the fragment it stopped
     * on.
     *
     * @param deadLetterTemplate the raw-byte template, resolved by bean name
     * @return the error handler the container factory carries
     */
    @Bean(name = ERROR_HANDLER_BEAN)
    public DefaultErrorHandler transactionAuthorizedErrorHandler(
            @Qualifier(DEAD_LETTER_TEMPLATE_BEAN)
                    KafkaTemplate<String, byte[]> deadLetterTemplate) {
        RawBytesDeadLetterRecoverer recoverer =
                new RawBytesDeadLetterRecoverer(deadLetterTemplate, deadLetterTopic);
        recoverer.excludeHeader(HeadersToAdd.EX_MSG, HeadersToAdd.EX_STACKTRACE);

        DefaultErrorHandler errorHandler = new DefaultErrorHandler(recoverer,
                new FixedBackOff(retryBackoffMs, deliveryAttempts - FIRST_ATTEMPT));
        errorHandler.addNotRetryableExceptions(DeserializationException.class,
                SerializationException.class);
        return errorHandler;
    }

    /**
     * Builds the one listener container factory of this service.
     *
     * <p>Both names on the annotation resolve to this single bean.
     *
     * @param consumerFactory the pinned consumer factory, resolved by bean name
     * @param errorHandler    the retry and dead-letter handler, resolved by bean name
     * @return the factory a listener of this service names
     */
    @Bean(name = {LISTENER_CONTAINER_FACTORY_BEAN, DEFAULT_LISTENER_CONTAINER_FACTORY_BEAN})
    public ConcurrentKafkaListenerContainerFactory<String, TransactionAuthorized>
            transactionAuthorizedListenerContainerFactory(
                    @Qualifier(CONSUMER_FACTORY_BEAN)
                            ConsumerFactory<String, TransactionAuthorized> consumerFactory,
                    @Qualifier(ERROR_HANDLER_BEAN) DefaultErrorHandler errorHandler) {
        ConcurrentKafkaListenerContainerFactory<String, TransactionAuthorized> factory =
                new ConcurrentKafkaListenerContainerFactory<>();
        factory.setConsumerFactory(consumerFactory);
        factory.setCommonErrorHandler(errorHandler);
        factory.setBatchListener(Boolean.FALSE);
        factory.getContainerProperties().setAckMode(ContainerProperties.AckMode.MANUAL_IMMEDIATE);
        return factory;
    }

    /**
     * Returns {@code value} trimmed once it holds a usable setting.
     *
     * @param value        the resolved property value
     * @param propertyName the property that supplied it, named in a failure
     * @return the trimmed value
     * @throws IllegalArgumentException when the value is absent, blank or still a placeholder
     */
    private static String resolved(String value, String propertyName) {
        if (value == null || value.isBlank() || value.contains(UNRESOLVED_PLACEHOLDER)) {
            throw new IllegalArgumentException(
                    "Property " + propertyName + " resolved to no usable value.");
        }
        return value.trim();
    }

    /**
     * Publishes a failed record whose value is raw bytes.
     *
     * <p>A record that broke its schema document carries the bytes that arrived. A record that
     * parsed carries no body, and its key, its original topic, its partition, its offset and the
     * failing exception class travel with it. Nothing here composes a value of its own.
     */
    private static final class RawBytesDeadLetterRecoverer extends DeadLetterPublishingRecoverer {

        /** Sends every failed record to {@code deadLetterTopic} through {@code template}. */
        private RawBytesDeadLetterRecoverer(KafkaOperations<?, ?> template, String deadLetterTopic) {
            super(template,
                    (record, failure) -> new TopicPartition(deadLetterTopic, PARTITION_BY_KEY));
        }

        /**
         * Builds the outgoing record, with a text key and a {@code byte[]} value. The two byte
         * arrays hold the raw key and the raw value of a deserialization failure, and are
         * {@code null} for a record that parsed.
         */
        @Override
        protected ProducerRecord<Object, Object> createProducerRecord(ConsumerRecord<?, ?> record,
                TopicPartition topicPartition, Headers headers, byte[] key, byte[] value) {
            Integer partition = topicPartition.partition() < 0 ? null : topicPartition.partition();
            Object outgoingKey =
                    key != null ? new String(key, StandardCharsets.UTF_8) : record.key();
            Object outgoingValue = asBytes(value != null ? value : record.value());
            return new ProducerRecord<>(topicPartition.topic(), partition, outgoingKey,
                    outgoingValue, headers);
        }

        /** Returns {@code value} when it is raw bytes, and {@code null} for anything else. */
        private static byte[] asBytes(Object value) {
            return value instanceof byte[] bytes ? bytes : null;
        }
    }
}
