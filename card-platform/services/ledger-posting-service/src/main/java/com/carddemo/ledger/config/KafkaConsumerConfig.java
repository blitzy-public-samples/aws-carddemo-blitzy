package com.carddemo.ledger.config;

import com.carddemo.events.DeadLetterEnvelope;
import com.carddemo.events.serde.JsonSchemaValidatingDeserializer;
import com.carddemo.ledger.messaging.DeadLetterMetadata;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

import org.apache.kafka.clients.CommonClientConfigs;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.errors.SerializationException;
import org.apache.kafka.common.header.Header;
import org.apache.kafka.common.header.Headers;
import org.apache.kafka.common.header.internals.RecordHeaders;
import org.apache.kafka.common.serialization.StringDeserializer;

import org.springframework.boot.kafka.autoconfigure.KafkaConnectionDetails;
import org.springframework.boot.kafka.autoconfigure.KafkaProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.core.KafkaOperations;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.ContainerProperties;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer.HeaderNames.HeadersToAdd;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.kafka.support.serializer.DeserializationException;
import org.springframework.kafka.support.serializer.ErrorHandlingDeserializer;
import org.springframework.util.backoff.FixedBackOff;

/**
 * Consume-side wiring for the ledger posting service: the consumer factory, the container factory
 * named {@code kafkaListenerContainerFactory}, manual acknowledgement, the delivery-attempt policy
 * and the route to the dead-letter topic. This class declares no producer and no topic.
 *
 * <p>The container replaces the job step at {@code app/jcl/POSTTRAN.jcl:L23}, {@code PGM=CBTRN02C},
 * which read the sequential file that job allocates at {@code app/jcl/POSTTRAN.jcl:L30-L31}.
 *
 * <p>No COBOL ancestor: idempotency, atomicity and the dead-letter topic. {@code
 * app/cbl/CBTRN02C.cbl} detects no duplicate, and it answered an input or output failure by
 * abending at {@code app/cbl/CBTRN02C.cbl:L707-L711}, without retrying and without cleanup.
 *
 * <p>Every setting here is bound, from {@code spring.kafka} and from the {@code carddemo} block
 * {@link LedgerProperties} holds.
 */
@Configuration
public class KafkaConsumerConfig {

    /** The {@code abendCode} of a dead letter. Its schema admits one to four of {@code [0-9A-Z]}. */
    private static final String DEAD_LETTER_ABEND_CODE = "DEAD";

    /**
     * The {@code culprit} of a dead letter: the job at {@code app/jcl/POSTTRAN.jcl} whose step this
     * container replaces. Eight characters, the width of {@code ABEND-CULPRIT PIC X(8)}.
     */
    private static final String POSTING_JOB_NAME = "POSTTRAN";

    /** The {@code reason} of a dead letter whose headers name no failure class. */
    private static final String UNCLASSIFIED_REASON = "UNCLASSIFIED";

    /** The {@code message} of a dead letter, which names where the attempt policy is configured. */
    private static final String DEAD_LETTER_MESSAGE =
            "attempts governed by carddemo.consumer.retry.max-attempts";

    /** In-process header carrying only the failure type selected by this service. */
    static final String HEADER_REASON = "carddemo-dl-reason";

    /**
     * The {@code aggregateId} of a dead letter whose failing record carried no account key. That
     * component admits the eleven-digit form alone, and no account identifier in
     * {@code app/data/ASCII/cardxref.txt} is eleven zeros.
     */
    private static final String UNRESOLVED_ACCOUNT_KEY = "00000000000";

    /** The partition a dead letter is addressed to. A negative value lets the broker select one. */
    private static final int BROKER_SELECTS_PARTITION = -1;

    /** The first delivery is an attempt and not a retry, and {@link FixedBackOff} counts retries. */
    private static final long FIRST_DELIVERY = 1L;

    /** The attempt count a dead letter reports when the container recorded none. */
    private static final int ONE_ATTEMPT = 1;

    /** The prefix the two acknowledgement modes that require the listener to acknowledge share. */
    private static final String MANUAL_ACK_MODE_PREFIX = "MANUAL";

    /**
     * Broker coordinates safe to retain after the refused record and producer-controlled headers
     * have been discarded.
     */
    private static final Set<String> ALLOWED_DEAD_LETTER_HEADERS = Set.of(
            KafkaHeaders.DLT_ORIGINAL_TOPIC,
            KafkaHeaders.DLT_ORIGINAL_PARTITION,
            KafkaHeaders.DLT_ORIGINAL_OFFSET,
            KafkaHeaders.DLT_ORIGINAL_TIMESTAMP);

    /**
     * Builds the consumer every inbound record passes through.
     *
     * <p>The settings start from the bound {@code spring.kafka} block, so the login and protocol a
     * deployment configures carry through, then take the broker address {@code connectionDetails}
     * resolved. Pinned here: text deserialization on the key, {@link ErrorHandlingDeserializer} on
     * the value with {@link JsonSchemaValidatingDeserializer} as its delegate,
     * {@code enable.auto.commit} false, and the group {@code spring.kafka.consumer.group-id} names.
     * That group is this service's own, so another service reading the same topic receives every
     * message too.
     *
     * <p>The key is the eleven-digit account identifier of {@code XREF-ACCT-ID PIC 9(11)} at
     * {@code app/cpy/CVACT03Y.cpy:L7}, which {@code app/cbl/CBTRN02C.cbl:L469} moves onto the
     * category-balance key. It travels as text, so a leading zero survives.
     *
     * <p>Neither this method nor the factory it returns contacts the broker, so the application
     * context starts while the broker is unreachable.
     *
     * @param kafkaProperties   the bound {@code spring.kafka} block
     * @param connectionDetails the broker address and security protocol this deployment resolved
     * @return the consumer factory the listener container reads through
     * @throws IllegalStateException when the group identifier or the offset reset policy is absent
     */
    @Bean
    public ConsumerFactory<String, Object> ledgerEventConsumerFactory(
            KafkaProperties kafkaProperties, KafkaConnectionDetails connectionDetails) {

        Map<String, Object> settings =
                new LinkedHashMap<>(kafkaProperties.buildConsumerProperties());

        KafkaConnectionDetails.Configuration consumer = connectionDetails.getConsumer();
        settings.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, consumer.getBootstrapServers());
        String securityProtocol = consumer.getSecurityProtocol();
        if (securityProtocol != null) {
            settings.put(CommonClientConfigs.SECURITY_PROTOCOL_CONFIG, securityProtocol);
        }

        settings.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        settings.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG,
                ErrorHandlingDeserializer.class);
        settings.put(ErrorHandlingDeserializer.VALUE_DESERIALIZER_CLASS,
                JsonSchemaValidatingDeserializer.class.getName());
        settings.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, Boolean.FALSE);
        settings.put(ConsumerConfig.GROUP_ID_CONFIG,
                requireConfigured(kafkaProperties.getConsumer().getGroupId(),
                        "spring.kafka.consumer.group-id"));
        requireConfigured(kafkaProperties.getConsumer().getAutoOffsetReset(),
                "spring.kafka.consumer.auto-offset-reset");

        return new DefaultKafkaConsumerFactory<>(settings);
    }

    /**
     * Builds the container factory the annotation-driven listener infrastructure looks up by name.
     *
     * <p>A listener that names no container factory of its own resolves this bean name, and
     * declaring the name here stands the auto-configured factory down. The acknowledgement mode
     * comes from {@code spring.kafka.listener.ack-mode}, concurrency from
     * {@code spring.kafka.listener.concurrency} when that key carries a value, the start-up
     * decision from {@code spring.kafka.listener.auto-startup}, and the delivery-attempt header is
     * switched on.
     *
     * <p>Carrying the start-up decision through matters: this factory replaces the auto-configured
     * one, so any {@code spring.kafka.listener} value it did not read would be bound, accepted and
     * then quietly ignored. A deployment or a test that holds the listener down has to be obeyed
     * rather than overruled, and a container starting against an address that does not resolve
     * fails the whole application context.
     *
     * @param ledgerEventConsumerFactory the pinned consumer factory
     * @param kafkaProperties            the bound {@code spring.kafka} block
     * @param ledgerConsumerErrorHandler the delivery-attempt policy and the dead-letter route
     * @return the container factory, which hands the listener one record per invocation
     * @throws IllegalStateException when the acknowledgement mode acknowledges automatically
     */
    @Bean
    public ConcurrentKafkaListenerContainerFactory<String, Object> kafkaListenerContainerFactory(
            ConsumerFactory<String, Object> ledgerEventConsumerFactory,
            KafkaProperties kafkaProperties, DefaultErrorHandler ledgerConsumerErrorHandler) {

        ConcurrentKafkaListenerContainerFactory<String, Object> factory =
                new ConcurrentKafkaListenerContainerFactory<>();
        factory.setConsumerFactory(ledgerEventConsumerFactory);
        factory.setCommonErrorHandler(ledgerConsumerErrorHandler);
        factory.setAutoStartup(kafkaProperties.getListener().isAutoStartup());

        Integer concurrency = kafkaProperties.getListener().getConcurrency();
        if (concurrency != null) {
            factory.setConcurrency(concurrency);
        }
        factory.setAutoStartup(kafkaProperties.getListener().isAutoStartup());

        // spring.kafka.listener.auto-startup reaches the auto-configured factory on its own, and
        // this factory replaces that one, so the setting has to be carried across by hand. A test
        // that exercises a transaction boundary and no broker sets it to false; leaving it unread
        // would start a consumer against an address no broker answers on.
        Boolean autoStartup = kafkaProperties.getListener().isAutoStartup();
        if (autoStartup != null) {
            factory.setAutoStartup(autoStartup);
        }

        ContainerProperties containerProperties = factory.getContainerProperties();
        containerProperties.setAckMode(
                requireManualAcknowledgement(kafkaProperties.getListener().getAckMode()));
        containerProperties.setDeliveryAttemptHeader(true);

        return factory;
    }

    /**
     * Builds the delivery-attempt policy and the dead-letter route.
     *
     * <p>One record is taken up to {@code carddemo.consumer.retry.max-attempts} times, waiting
     * {@code carddemo.consumer.retry.backoff-ms} milliseconds between two attempts, and a spent
     * record is addressed to a source-specific dead-letter topic. The shared dead-letter topic is a
     * fallback for records whose source topic is unavailable.
     *
     * <p>{@link DeserializationException} and {@link SerializationException} are registered as not
     * retryable, so a record the value deserializer refused is taken once.
     * {@code AccountBalanceUpdater.AccountBalanceRowMissingException} is registered under neither
     * classification and keeps the retryable default.
     *
     * @param ledgerEventKafkaTemplate the template {@code config/KafkaProducerConfig} declares
     * @param ledgerProperties         the bound {@code carddemo} block
     * @return the error handler the container factory installs
     */
    @Bean
    public DefaultErrorHandler ledgerConsumerErrorHandler(
            KafkaTemplate<String, Object> ledgerEventKafkaTemplate,
            LedgerProperties ledgerProperties) {

        LedgerProperties.Consumer.Retry retry = ledgerProperties.consumer().retry();
        LedgerProperties.Kafka.Topics topics = ledgerProperties.kafka().topics();
        DeadLetterEnvelopeRecoverer recoverer = new DeadLetterEnvelopeRecoverer(
                ledgerEventKafkaTemplate, topics.deadLetter(), topics.deadLetterSuffix());

        DefaultErrorHandler errorHandler = new DefaultErrorHandler(recoverer,
                new FixedBackOff(retry.backoffMs(), retry.maxAttempts() - FIRST_DELIVERY));
        errorHandler.addNotRetryableExceptions(DeserializationException.class,
                SerializationException.class);

        return errorHandler;
    }

    /**
     * Returns the mode bound from {@code spring.kafka.listener.ack-mode}. The framework default is
     * {@code BATCH}, which acknowledges on the listener's behalf, so this method admits the two
     * manual modes and stops start-up on the five automatic ones.
     */
    private static ContainerProperties.AckMode requireManualAcknowledgement(
            ContainerProperties.AckMode ackMode) {

        if (ackMode == null || !ackMode.name().startsWith(MANUAL_ACK_MODE_PREFIX)) {
            throw new IllegalStateException("The property spring.kafka.listener.ack-mode must name"
                    + " an acknowledgement mode that requires the listener to acknowledge, and the"
                    + " bound mode is " + ackMode + ".");
        }
        return ackMode;
    }

    /**
     * Returns a bound value, and stops start-up when it is absent or blank. The message names the
     * property and quotes no value, so no credential reaches a log.
     */
    private static String requireConfigured(String value, String property) {
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("The property " + property + " carries no value."
                    + " Declare it in the application.yml this service reads.");
        }
        return value;
    }

    /**
     * Resolves a dead-letter destination that preserves the source event format boundary.
     *
     * @param failedRecord the refused source record
     * @param fallbackTopic the shared fallback topic
     * @param suffix the suffix appended to a present source topic
     * @return the broker-selected partition of the source-specific dead-letter topic
     */
    static TopicPartition resolveDeadLetterDestination(ConsumerRecord<?, ?> failedRecord,
            String fallbackTopic, String suffix) {

        String sourceTopic = failedRecord.topic();
        String destination = sourceTopic == null || sourceTopic.isBlank()
                ? fallbackTopic
                : sourceTopic + suffix;
        return new TopicPartition(destination, BROKER_SELECTS_PARTITION);
    }

    /**
     * Rebuilds a dead letter without the refused bytes, producer key or arbitrary headers.
     *
     * <p>The envelope uses a documented sentinel because its schema requires an account-shaped
     * aggregate identifier. Broker coordinates form the outgoing record key and identify the
     * refused delivery without trusting the producer-supplied key.
     *
     * @param record the refused source record
     * @param topicPartition the resolved destination
     * @param headers the headers enriched by the framework
     * @return a safe dead-letter record
     */
    static ProducerRecord<Object, Object> sanitizedDeadLetterRecord(ConsumerRecord<?, ?> record,
            TopicPartition topicPartition, Headers headers) {

        DeadLetterEnvelope envelope = DeadLetterMetadata
                .of(DEAD_LETTER_ABEND_CODE, POSTING_JOB_NAME, reasonOf(headers),
                        DEAD_LETTER_MESSAGE)
                .toEnvelope(UNRESOLVED_ACCOUNT_KEY, record.topic(), record.partition(),
                        record.offset(), null, null, attemptCountOf(record));

        return new ProducerRecord<>(topicPartition.topic(), partitionOf(topicPartition),
                recordCoordinates(record), envelope, allowedDeadLetterHeaders(headers));
    }

    /** Identifies a source record without retaining its producer-controlled key. */
    static String recordCoordinates(ConsumerRecord<?, ?> record) {
        return record.topic() + "-" + record.partition() + "-" + record.offset();
    }

    /** Copies at most one trusted value for each explicitly admitted broker header. */
    static Headers allowedDeadLetterHeaders(Headers headers) {
        RecordHeaders allowed = new RecordHeaders();
        for (String name : ALLOWED_DEAD_LETTER_HEADERS) {
            Header header = headers.lastHeader(name);
            if (header != null && header.value() != null) {
                allowed.add(name, header.value().clone());
            }
        }
        return allowed;
    }

    /**
     * Builds one bounded diagnostic header from the failure type and never from its message.
     */
    static Headers diagnosticHeaders(Exception failure) {
        String reason = UNCLASSIFIED_REASON;
        Throwable cause = failure;
        while (cause != null) {
            String simpleName = cause.getClass().getSimpleName();
            if (!simpleName.isBlank()) {
                reason = simpleName;
            }
            cause = cause.getCause();
        }
        return new RecordHeaders().add(HEADER_REASON,
                reason.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * Addresses one {@link DeadLetterEnvelope} to the dead-letter topic for a record no listener
     * could take.
     *
     * <p>{@link DeadLetterPublishingRecoverer} sends the failing record onward. The template this
     * recoverer sends through writes a registered event record and nothing else. The envelope is the
     * one event type bound to the dead-letter topic, so it is the outgoing value and the failing
     * payload travels nowhere. Neither a card number nor a verification value can reach the topic
     * through it.
     *
     * <p>The outgoing record is rebuilt from an allowlist. The refused bytes, original key,
     * arbitrary producer headers, exception message and stack trace do not travel. The failure
     * class contributes only its short name to the schema-validated envelope. An instance holds no
     * mutable state, so consumer threads may share one.
     */
    private static final class DeadLetterEnvelopeRecoverer extends DeadLetterPublishingRecoverer {

        /**
         * Builds a recoverer addressing one dead-letter topic per source topic, with the configured
         * shared topic as a fallback.
         */
        private DeadLetterEnvelopeRecoverer(KafkaOperations<?, ?> template,
                String deadLetterTopic, String deadLetterSuffix) {
            super(template, (failedRecord, failure) ->
                    resolveDeadLetterDestination(
                            failedRecord, deadLetterTopic, deadLetterSuffix));
            excludeHeader(HeadersToAdd.EX_MSG, HeadersToAdd.EX_STACKTRACE);
            setHeadersFunction((record, failure) -> diagnosticHeaders(failure));
        }

        /**
         * Builds the outgoing record without trusting either refused byte array or the original key.
         */
        @Override
        protected ProducerRecord<Object, Object> createProducerRecord(ConsumerRecord<?, ?> record,
                TopicPartition topicPartition, Headers headers, byte[] key, byte[] value) {

            return sanitizedDeadLetterRecord(record, topicPartition, headers);
        }
    }

    /**
     * The unqualified failure class this service recorded, or an unclassified sentinel.
     */
    private static String reasonOf(Headers headers) {
        String simpleName = headerText(headers, HEADER_REASON);
        if (simpleName == null || simpleName.isBlank()) {
            return UNCLASSIFIED_REASON;
        }
        return simpleName;
    }

    /** One header as text, and {@code null} when the header carries no value. */
    private static String headerText(Headers headers, String name) {
        Header header = headers.lastHeader(name);
        return header == null || header.value() == null
                ? null
                : new String(header.value(), StandardCharsets.UTF_8);
    }

    /** The attempt the container recorded, and {@link #ONE_ATTEMPT} when it recorded none. */
    private static int attemptCountOf(ConsumerRecord<?, ?> record) {
        Header attempt = record.headers().lastHeader(KafkaHeaders.DELIVERY_ATTEMPT);

        if (attempt == null || attempt.value() == null
                || attempt.value().length != Integer.BYTES) {
            return ONE_ATTEMPT;
        }
        return ByteBuffer.wrap(attempt.value()).getInt();
    }

    /** The resolved partition, and {@code null} when the resolver named a negative one. */
    private static Integer partitionOf(TopicPartition topicPartition) {
        int partition = topicPartition.partition();
        return partition < 0 ? null : partition;
    }
}
