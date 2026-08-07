package com.carddemo.fraud.config;

import com.carddemo.events.TransactionAuthorized;
import com.carddemo.events.serde.JsonSchemaValidatingDeserializer;
import com.carddemo.fraud.messaging.DeadLetterMetadata;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.errors.SerializationException;
import org.apache.kafka.common.header.Header;
import org.apache.kafka.common.header.Headers;
import org.apache.kafka.common.header.internals.RecordHeaders;
import org.apache.kafka.common.serialization.ByteArraySerializer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
import org.springframework.kafka.listener.ConsumerRecordRecoverer;
import org.springframework.kafka.listener.ContainerProperties;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer.HeaderNames.HeadersToAdd;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.kafka.support.KafkaHeaders;
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
 * <p>Two failures, two behaviours. A deserialization or schema failure routes straight to the
 * source topic's dead-letter topic and is not retried. An infrastructure failure is retried, three
 * attempts at 1000 ms, and then takes the same source-specific route. That route replaces the
 * four-line abend at {@code app/cbl/CBTRN02C.cbl:L707-L711} (shape only, no logic).
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
 * {@code deadLetterKafkaTemplate} and writes sanitized diagnostic bytes; the outbox template is
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
 * <p>Design decisions: {@code card-platform/docs/decision-log.md}.
 */
@Configuration
public class KafkaConsumerConfig {

    /**
     * Where the one terminal line of a spent record goes.
     *
     * <p>Declared here rather than on the listener because the terminal outcome is this class's to
     * report: the listener sees each attempt and cannot know which one was the last.
     */
    private static final Logger LOG = LoggerFactory.getLogger(KafkaConsumerConfig.class);

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

    /** Destination partition the broker selects from the sanitized coordinate key. */
    private static final int PARTITION_BY_KEY = -1;

    /** The delivery every record takes before the first retry. */
    private static final long FIRST_ATTEMPT = 1L;

    /** Opening characters of a placeholder no property source resolved. */
    private static final String UNRESOLVED_PLACEHOLDER = "${";

    static final String HEADER_ABEND_CODE = "carddemo-dl-code";
    static final String HEADER_CULPRIT = "carddemo-dl-culprit";
    static final String HEADER_REASON = "carddemo-dl-reason";
    static final String HEADER_MESSAGE = "carddemo-dl-message";
    static final String ABEND_CODE = "0999";
    static final String SERVICE_CULPRIT = "FRAUDSVC";
    static final String UNCLASSIFIED_REASON = "UNCLASSIFIED";
    static final String SAFE_FAILURE_MESSAGE = "record rejected; inspect broker coordinates";

    static final Set<String> ALLOWED_HEADERS = Set.of(
            HEADER_ABEND_CODE, HEADER_CULPRIT, HEADER_REASON, HEADER_MESSAGE,
            KafkaHeaders.DLT_ORIGINAL_TOPIC, KafkaHeaders.DLT_ORIGINAL_PARTITION,
            KafkaHeaders.DLT_ORIGINAL_OFFSET, KafkaHeaders.DLT_ORIGINAL_TIMESTAMP);

    private final String bootstrapServers;
    private final String consumedTopic;
    private final String consumerGroup;
    private final String deadLetterTopic;
    private final String deadLetterSuffix;
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
     * @param deadLetterSuffix suffix appended to the source topic for its dead-letter topic
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
            @Value("${carddemo.kafka.topics.dead-letter-suffix:.DLT}") String deadLetterSuffix,
            @Value("${carddemo.consumer.retry.max-attempts:3}") long deliveryAttempts,
            @Value("${carddemo.consumer.retry.backoff-ms:1000}") long retryBackoffMs) {
        this.bootstrapServers = resolved(bootstrapServers, "spring.kafka.bootstrap-servers");
        this.consumedTopic =
                resolved(consumedTopic, "carddemo.kafka.topics.transaction-authorized");
        this.consumerGroup = resolved(consumerGroup, "spring.kafka.consumer.group-id");
        this.deadLetterTopic = resolved(deadLetterTopic, "carddemo.kafka.topics.dead-letter");
        this.deadLetterSuffix =
                resolved(deadLetterSuffix, "carddemo.kafka.topics.dead-letter-suffix");
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
     * writes text and the value serializer writes raw bytes. The recoverer writes a fixed-width,
     * sanitized diagnostic record and never republishes refused payload bytes.
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
     * on the dead-letter topic belonging to its source topic. The outgoing key is the source
     * coordinates, the value is fixed-width diagnostic metadata, and the headers are rebuilt from an
     * allowlist.
     *
     * <p>The recoverer is also where a deserialization or schema failure is counted, because it is
     * the only place that observes one. {@code ErrorHandlingDeserializer} records the failure on the
     * record it produces and the container raises it before invoking any listener, so a listener
     * cannot see and cannot count it.
     *
     * <p>It is equally the only place the TERMINAL outcome of a delivery is observable. By the time
     * the container calls a recoverer the backoff is spent and no further attempt will be made, so
     * this is the one moment at which a record can be counted as permanently given up on rather than
     * as one more failed attempt. {@code carddemo.fraud.dead.letters} carries that count, tagged by
     * whether the diagnostic reached the broker.
     *
     * <p>{@link DefaultErrorHandler#setCommitRecovered(boolean)} commits the offset of a record the
     * route has published. The container acknowledges by hand, so nothing acknowledges a record its
     * listener never accepted, and without this setting the offset of a dead-lettered record stayed
     * uncommitted: the next start-up or the next partition assignment read that record again and
     * published a second diagnostic for the same coordinates. The container applies the setting under
     * acknowledgement mode {@code MANUAL_IMMEDIATE}, which
     * {@link #transactionAuthorizedListenerContainerFactory} sets on every container it builds.
     *
     * @param deadLetterTemplate the raw-byte template, resolved by bean name
     * @param meters             the recording surface, so a failure the listener cannot see is still
     *                           counted
     * @return the error handler the container factory carries
     */
    @Bean(name = ERROR_HANDLER_BEAN)
    public DefaultErrorHandler transactionAuthorizedErrorHandler(
            @Qualifier(DEAD_LETTER_TEMPLATE_BEAN)
                    KafkaTemplate<String, byte[]> deadLetterTemplate,
            ObservabilityConfig.FraudMeters meters) {
        ByteValuedRecoverer recoverer =
                new ByteValuedRecoverer(deadLetterTemplate, deadLetterTopic, deadLetterSuffix);

        DefaultErrorHandler errorHandler = new DefaultErrorHandler(
                new CountingRecoverer(recoverer, meters),
                new FixedBackOff(retryBackoffMs, deliveryAttempts - FIRST_ATTEMPT));
        errorHandler.addNotRetryableExceptions(DeserializationException.class,
                SerializationException.class);
        errorHandler.setCommitRecovered(true);
        return errorHandler;
    }

    /**
     * Builds the one listener container factory of this service.
     *
     * <p>Both names on the annotation resolve to this single bean.
     *
     * <p>A hand-built factory replaces the auto-configured one, so nothing applies the bound
     * {@code spring.kafka.listener} block on its behalf. {@code auto-startup} is applied here for
     * that reason: a factory that ignored it would start a consumer whatever the property said, so a
     * context that has to load without a broker could not.
     *
     * <p>{@code concurrency} is applied for the same reason, and the shipped file declares it. A
     * factory that read the key and did nothing with it left the declaration inert: the shipped value
     * of one happens to equal the framework default, so a deployment raising it got one consumer
     * thread anyway and no indication that its setting was discarded. The ledger factory applies the
     * same value the same way, so the two services now answer a bound listener block alike.
     *
     * <p>The acknowledgement mode is the one listener setting this factory does not read. It is
     * pinned to {@code MANUAL_IMMEDIATE} because the listener acknowledges after its own writes
     * commit, and an automatic mode would acknowledge on its behalf.
     *
     * @param consumerFactory the pinned consumer factory, resolved by bean name
     * @param errorHandler    the retry and dead-letter handler, resolved by bean name
     * @param kafkaProperties the bound {@code spring.kafka} block, read for
     *                        {@code listener.auto-startup} and {@code listener.concurrency}
     * @return the factory a listener of this service names
     */
    @Bean(name = {LISTENER_CONTAINER_FACTORY_BEAN, DEFAULT_LISTENER_CONTAINER_FACTORY_BEAN})
    public ConcurrentKafkaListenerContainerFactory<String, TransactionAuthorized>
            transactionAuthorizedListenerContainerFactory(
                    @Qualifier(CONSUMER_FACTORY_BEAN)
                            ConsumerFactory<String, TransactionAuthorized> consumerFactory,
                    @Qualifier(ERROR_HANDLER_BEAN) DefaultErrorHandler errorHandler,
                    KafkaProperties kafkaProperties) {
        ConcurrentKafkaListenerContainerFactory<String, TransactionAuthorized> factory =
                new ConcurrentKafkaListenerContainerFactory<>();
        factory.setConsumerFactory(consumerFactory);
        factory.setCommonErrorHandler(errorHandler);
        factory.setBatchListener(Boolean.FALSE);
        factory.setAutoStartup(kafkaProperties.getListener().isAutoStartup());

        Integer concurrency = kafkaProperties.getListener().getConcurrency();
        if (concurrency != null) {
            factory.setConcurrency(concurrency);
        }

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

    /** Builds the fixed-width metadata headers attached before a dead letter is rebuilt. */
    private static Headers diagnosticHeaders(Exception failure) {
        DeadLetterMetadata metadata = metadataOf(failure);
        Headers headers = new RecordHeaders();
        headers.add(HEADER_ABEND_CODE, utf8(metadata.abendCode()));
        headers.add(HEADER_CULPRIT, utf8(metadata.culprit()));
        headers.add(HEADER_REASON, utf8(metadata.reason()));
        headers.add(HEADER_MESSAGE, utf8(metadata.message()));
        return headers;
    }

    /** Maps one failure to bounded metadata without copying its message. */
    static DeadLetterMetadata metadataOf(Exception failure) {
        Throwable cause = deepestCause(failure);
        if (cause == null) {
            return DeadLetterMetadata.of(
                    ABEND_CODE, SERVICE_CULPRIT, UNCLASSIFIED_REASON, null);
        }
        return DeadLetterMetadata.of(ABEND_CODE, SERVICE_CULPRIT,
                cause.getClass().getSimpleName(), SAFE_FAILURE_MESSAGE);
    }

    /** Returns the deepest cause, ending safely on a self-referencing chain. */
    private static Throwable deepestCause(Throwable failure) {
        Throwable cause = failure;
        while (cause != null && cause.getCause() != null && cause.getCause() != cause) {
            cause = cause.getCause();
        }
        return cause;
    }

    /** One metadata component as UTF-8 bytes. */
    private static byte[] utf8(String component) {
        return component.getBytes(StandardCharsets.UTF_8);
    }

    /** Resolves the source-specific dead-letter topic, or the fallback where no source exists. */
    static String resolveDeadLetterDestination(ConsumerRecord<?, ?> failedRecord,
            String fallbackTopic, String suffix) {
        String sourceTopic = failedRecord == null ? null : failedRecord.topic();
        return sourceTopic == null || sourceTopic.isBlank()
                ? fallbackTopic
                : sourceTopic + suffix;
    }

    /** Rebuilds a failed record from broker coordinates and allowlisted diagnostics alone. */
    static ProducerRecord<Object, Object> sanitizedDeadLetterRecord(
            ConsumerRecord<?, ?> failedRecord, TopicPartition topicPartition, Headers headers) {
        int partition = topicPartition.partition();
        return new ProducerRecord<>(topicPartition.topic(),
                partition < 0 ? null : partition,
                recordCoordinates(failedRecord),
                diagnosticRecord(headers),
                allowedDeadLetterHeaders(headers));
    }

    /** The source coordinates, replacing any producer-controlled key. */
    private static String recordCoordinates(ConsumerRecord<?, ?> failedRecord) {
        return failedRecord == null
                ? null
                : failedRecord.topic() + "-" + failedRecord.partition() + "-"
                        + failedRecord.offset();
    }

    /** A fresh header set carrying one final value for each allowed name. */
    private static Headers allowedDeadLetterHeaders(Headers headers) {
        Headers permitted = new RecordHeaders();
        if (headers == null) {
            return permitted;
        }
        for (String allowed : ALLOWED_HEADERS) {
            Header header = headers.lastHeader(allowed);
            if (header != null) {
                permitted.add(allowed,
                        header.value() == null ? null : header.value().clone());
            }
        }
        return permitted;
    }

    /** The source-shaped diagnostic value rebuilt from sanitized metadata headers. */
    private static byte[] diagnosticRecord(Headers headers) {
        return DeadLetterMetadata.of(headerText(headers, HEADER_ABEND_CODE),
                        headerText(headers, HEADER_CULPRIT),
                        headerText(headers, HEADER_REASON),
                        headerText(headers, HEADER_MESSAGE))
                .toFixedWidthRecord().getBytes(StandardCharsets.UTF_8);
    }

    /** One header as UTF-8 text, or {@code null} where it is absent. */
    private static String headerText(Headers headers, String name) {
        if (headers == null) {
            return null;
        }
        Header header = headers.lastHeader(name);
        return header == null || header.value() == null
                ? null
                : new String(header.value(), StandardCharsets.UTF_8);
    }

    /**
     * Publishes a sanitized diagnostic record to the dead-letter topic of the source topic.
     *
     * <p>The superclass supplies refused key and value bytes, but this implementation discards both.
     * It also discards every unapproved header, including producer-controlled duplicates.
     */
    private static final class ByteValuedRecoverer extends DeadLetterPublishingRecoverer {

        private ByteValuedRecoverer(KafkaOperations<?, ?> template, String deadLetterTopic,
                String deadLetterSuffix) {
            super(template, (record, failure) -> new TopicPartition(
                    resolveDeadLetterDestination(record, deadLetterTopic, deadLetterSuffix),
                    PARTITION_BY_KEY));
            excludeHeader(HeadersToAdd.EX_MSG, HeadersToAdd.EX_STACKTRACE);
            setHeadersFunction((record, failure) -> diagnosticHeaders(failure));
        }

        @Override
        protected ProducerRecord<Object, Object> createProducerRecord(ConsumerRecord<?, ?> record,
                TopicPartition topicPartition, Headers headers, byte[] key, byte[] value) {
            return sanitizedDeadLetterRecord(record, topicPartition, headers);
        }
    }

    /**
     * Counts what the container is about to give up on, then hands the record to the real recoverer.
     *
     * <p>Two different facts are counted here, and the difference is the point of this class.
     *
     * <p>The CAUSE, for one class of failure only. A deserialization or schema failure is raised
     * inside the container before a listener is invoked, so the listener that would otherwise count
     * it never runs, and this is the only point in the service that observes one. A failure a
     * listener did reach is counted there instead, once per attempt, and counting it again here would
     * report one business failure as several. The cause is counted before the delegate publishes, so a
     * dead-letter send that itself fails still leaves the failure counted rather than losing it.
     *
     * <p>The TERMINAL OUTCOME, for every failure without exception. The container calls a recoverer
     * only once the backoff is spent, so every record that arrives here is one this service will not
     * attempt again. That is counted once per record under {@code carddemo.fraud.dead.letters}, tagged
     * {@code published} once the diagnostic has reached the broker and {@code failed} when the send
     * refused it. A refusal is rethrown as well as counted, because the container has to know the
     * record was not recovered; the count is what makes the loss visible without reading a log.
     */
    private static final class CountingRecoverer implements ConsumerRecordRecoverer {

        /** Publishes the failed record on the dead-letter topic. */
        private final ConsumerRecordRecoverer delegate;

        /** The recording surface this recoverer counts against. */
        private final ObservabilityConfig.FraudMeters meters;

        /**
         * Wraps {@code delegate}, counting a deserialization or schema failure against
         * {@code meters} first and the terminal outcome of the record afterwards.
         *
         * @param delegate the recoverer that publishes the diagnostic
         * @param meters   the recording surface both counts reach
         */
        private CountingRecoverer(ConsumerRecordRecoverer delegate,
                ObservabilityConfig.FraudMeters meters) {
            this.delegate = Objects.requireNonNull(delegate, "delegate must be present");
            this.meters = Objects.requireNonNull(meters, "meters must be present");
        }

        @Override
        public void accept(ConsumerRecord<?, ?> record, Exception failure) {
            if (isPayloadFailure(failure)) {
                meters.recordDeserializeFailure();
            }

            // One ERROR per record, at the moment the record becomes terminal. Each individual
            // attempt is logged at WARN by the listener, because a retry may still succeed and a
            // level that says otherwise trains an operator to ignore it. This line is the level an
            // alerting rule should watch, and every consumer of this platform emits it here: see
            // the same line in the notification, authorization and ledger services. It carries the
            // record coordinates and the four components of 01 ABEND-DATA at
            // app/cpy/CSMSG02Y.cpy:L21-L29, and no payload, no key and no exception chain.
            DeadLetterMetadata metadata = metadataOf(failure);
            LOG.error("Routing one record to the dead-letter topic."
                    + " topic={} partition={} offset={} code={} culprit={} reason={} message={}",
                    record.topic(), record.partition(), record.offset(), metadata.abendCode(),
                    metadata.culprit(), metadata.reason(), metadata.message());

            try {
                delegate.accept(record, failure);
            } catch (RuntimeException undelivered) {
                meters.recordDeadLetterFailure();
                throw undelivered;
            }
            meters.recordDeadLetterPublished();
        }

        /**
         * Reports whether one failure names a payload that did not read.
         *
         * <p>The walk follows the cause chain, because the container wraps the failure it caught, and
         * it ends on a chain naming itself as its own cause.
         *
         * @param failure the failure the container recovered from
         * @return true when the chain carries a deserialization or a serialization failure
         */
        private static boolean isPayloadFailure(Throwable failure) {
            Throwable cause = failure;
            while (cause != null) {
                if (cause instanceof DeserializationException
                        || cause instanceof SerializationException) {
                    return true;
                }
                Throwable next = cause.getCause();
                cause = next == cause ? null : next;
            }
            return false;
        }
    }
}
