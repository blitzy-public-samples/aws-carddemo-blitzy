package com.carddemo.ledger.config;


import com.carddemo.events.serde.JsonSchemaValidatingDeserializer;
import com.carddemo.ledger.messaging.DeadLetterMetadata;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.apache.kafka.clients.CommonClientConfigs;
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
import org.springframework.boot.kafka.autoconfigure.KafkaConnectionDetails;
import org.springframework.boot.kafka.autoconfigure.KafkaProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaOperations;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.ConsumerRecordRecoverer;
import org.springframework.kafka.listener.ContainerProperties;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer.HeaderNames.HeadersToAdd;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.kafka.listener.ListenerExecutionFailedException;
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

    /**
     * Where the one terminal line of a spent record goes.
     *
     * <p>Declared here rather than on the listener because the terminal outcome is this class's to
     * report: the listener sees each attempt and cannot know which one was the last.
     */
    private static final Logger LOG = LoggerFactory.getLogger(KafkaConsumerConfig.class);

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

    /**
     * The four headers a dead letter carries, one per component of {@code 01 ABEND-DATA} at
     * {@code app/cpy/CSMSG02Y.cpy:L21-L29}.
     *
     * <p>Every value is generated here from a constant or from the failure type, and the outgoing set
     * is rebuilt from {@link #ALLOWED_DEAD_LETTER_HEADERS} reading the LAST value of each name, so a
     * producer that put one of these names on its own record cannot have it survive.
     *
     * <p>The four components already travel in the fixed-width value. They travel as headers as well
     * because the notification and fraud dead-letter routes carry the same four, and a reader of all
     * three topics would otherwise have to parse the value of one service and read the headers of the
     * others.
     */
    static final String HEADER_ABEND_CODE = "carddemo-dl-code";
    static final String HEADER_CULPRIT = "carddemo-dl-culprit";
    static final String HEADER_REASON = "carddemo-dl-reason";
    static final String HEADER_MESSAGE = "carddemo-dl-message";

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

    /**
     * The largest attempt count a diagnostic reports.
     *
     * <p>The container sets the delivery-attempt header, but the header name is not reserved, so a
     * producer can set it too. The count is clamped rather than trusted, so a value a producer chose
     * cannot reach a diagnostic or an envelope that bounds it.
     */
    private static final int MAX_REPORTED_ATTEMPTS = 1_000;

    /** Depth cap on a cause-chain walk, which also ends the walk on a self-referencing cause. */
    private static final int MAX_CAUSE_DEPTH = 16;

    /** Bean name of the byte-serializing template the dead-letter route publishes through. */
    private static final String DEAD_LETTER_TEMPLATE_BEAN = "deadLetterKafkaTemplate";

    /** Acknowledgement setting every producer of this service carries. */
    private static final String ACKS_FROM_ALL_REPLICAS = "all";

    /** The prefix the two acknowledgement modes that require the listener to acknowledge share. */
    private static final String MANUAL_ACK_MODE_PREFIX = "MANUAL";

    /**
     * Broker coordinates safe to retain after the refused record and producer-controlled headers
     * have been discarded.
     */
    private static final Set<String> ALLOWED_DEAD_LETTER_HEADERS = Set.of(
            HEADER_ABEND_CODE,
            HEADER_CULPRIT,
            HEADER_REASON,
            HEADER_MESSAGE,
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
     * Builds the template a spent record's diagnostic is published through.
     *
     * <p>Production is idempotent and acknowledged by every in-sync replica, matching the event
     * template. The key serializer writes text and the value serializer writes raw bytes, because the
     * recoverer renders the diagnostic itself and hands over the rendered form.
     *
     * <p>The two serializer settings the bound block carries are removed rather than overridden. That
     * block names the schema-validating serializer for a business event, and leaving it in place would
     * hand these bytes to a serializer that expects an event record.
     *
     * @param kafkaProperties  the bound {@code spring.kafka} block
     * @param ledgerProperties the bound {@code carddemo} block, read for the fallback topic
     * @return the template the error handler injects as {@code deadLetterKafkaTemplate}
     */
    @Bean(name = DEAD_LETTER_TEMPLATE_BEAN)
    public KafkaTemplate<String, byte[]> deadLetterKafkaTemplate(KafkaProperties kafkaProperties,
            LedgerProperties ledgerProperties) {

        Map<String, Object> settings =
                new LinkedHashMap<>(kafkaProperties.buildProducerProperties());
        settings.put(ProducerConfig.ACKS_CONFIG, ACKS_FROM_ALL_REPLICAS);
        settings.put(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, Boolean.TRUE);
        settings.remove(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG);
        settings.remove(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG);

        KafkaTemplate<String, byte[]> template = new KafkaTemplate<>(
                new DefaultKafkaProducerFactory<>(settings, new StringSerializer(),
                        new ByteArraySerializer()));
        template.setDefaultTopic(ledgerProperties.kafka().topics().deadLetter());
        return template;
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
     * <p>The route publishes through {@link #deadLetterKafkaTemplate}, whose value serializer writes
     * raw bytes, and not through the event template {@code config/KafkaProducerConfig} declares. That
     * template validates an event against the schema of its own type and refuses a topic that type is
     * not bound to, and {@code EventContracts} binds every governed type to exactly one topic. A
     * source-specific destination handed an event-shaped value would therefore fail serialization
     * before the send, leaving every spent record with nowhere to go at all. The diagnostic is
     * rendered and bounded before it is handed over instead, which is what the fraud and notification
     * consumers do with the same four-field layout.
     *
     * <p>The route is wrapped so the terminal outcome is counted. A recoverer runs only once the
     * backoff is spent, so it is the one point at which a delivery can be counted as permanently given
     * up on rather than as one more failed attempt. {@code carddemo.ledger.dead.letters} carries that
     * count, once per record and tagged by whether the diagnostic reached the broker.
     *
     * <p>{@link DefaultErrorHandler#setCommitRecovered(boolean)} commits the offset of a record the
     * route has published, which is what makes the route terminal. Both containers acknowledge by
     * hand, so nothing acknowledges a record its listener never accepted, and without this setting
     * the offset of a dead-lettered record stayed uncommitted: the next start-up or the next
     * partition assignment read that record again and published a second diagnostic for the same
     * coordinates, so {@code carddemo.ledger.dead.letters} counted one record more than once. The
     * container applies the setting under acknowledgement mode {@code MANUAL_IMMEDIATE}, which the
     * shipped {@code spring.kafka.listener.ack-mode} names; under {@code MANUAL} it reports the
     * setting as ignored and commits nothing.
     *
     * @param deadLetterKafkaTemplate the byte-serializing template, resolved by bean name
     * @param ledgerProperties        the bound {@code carddemo} block
     * @param meters                  the recording surface the terminal outcome is counted against
     * @return the error handler the container factory installs
     */
    @Bean
    public DefaultErrorHandler ledgerConsumerErrorHandler(
            KafkaTemplate<String, byte[]> deadLetterKafkaTemplate,
            LedgerProperties ledgerProperties,
            ObservabilityConfig.LedgerMeters meters) {

        LedgerProperties.Consumer.Retry retry = ledgerProperties.consumer().retry();
        LedgerProperties.Kafka.Topics topics = ledgerProperties.kafka().topics();
        ByteValuedRecoverer recoverer = new ByteValuedRecoverer(
                deadLetterKafkaTemplate, topics.deadLetter(), topics.deadLetterSuffix());

        DefaultErrorHandler errorHandler = new DefaultErrorHandler(
                new CountingRecoverer(recoverer, meters),
                new FixedBackOff(retry.backoffMs(), retry.maxAttempts() - FIRST_DELIVERY));
        errorHandler.addNotRetryableExceptions(DeserializationException.class,
                SerializationException.class);
        errorHandler.setCommitRecovered(true);

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

        String diagnostic = DeadLetterMetadata
                .of(DEAD_LETTER_ABEND_CODE, POSTING_JOB_NAME, reasonOf(headers),
                        DEAD_LETTER_MESSAGE)
                .toFixedWidthRecord();

        return new ProducerRecord<>(topicPartition.topic(), partitionOf(topicPartition),
                recordCoordinates(record), diagnostic.getBytes(StandardCharsets.UTF_8),
                allowedDeadLetterHeaders(headers));
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
     * Builds the four bounded diagnostic headers from constants and the failure type, and never from a
     * failure message, which can repeat a rejected value.
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
        return new RecordHeaders()
                .add(HEADER_ABEND_CODE, utf8(DEAD_LETTER_ABEND_CODE))
                .add(HEADER_CULPRIT, utf8(POSTING_JOB_NAME))
                .add(HEADER_REASON, utf8(reason))
                .add(HEADER_MESSAGE, utf8(DEAD_LETTER_MESSAGE));
    }

    /** One diagnostic component as UTF-8 bytes. Every component here is a constant or a type name. */
    private static byte[] utf8(String component) {
        return component.getBytes(StandardCharsets.UTF_8);
    }

    /**
     * Addresses one rendered diagnostic to the dead-letter topic of a record no listener could take.
     *
     * <p>{@link DeadLetterPublishingRecoverer} sends the failing record onward, and the inherited
     * behaviour republishes the refused key and the refused bytes. Where a deserializer refused the
     * record those bytes are the ones a schema control rejected, so record construction is overridden
     * and neither travels. Neither a card number nor a verification value can reach the topic.
     *
     * <p>What travels is the four fixed-width fields of {@code 01 ABEND-DATA} at
     * {@code app/cpy/CSMSG02Y.cpy:L21-L29}, rendered by
     * {@link DeadLetterMetadata#toFixedWidthRecord()} at their declared widths of four, eight, fifty
     * and seventy-two characters. The value is bytes rather than an event object, and that is what
     * lets the destination stay the dead-letter topic of the source stream. The envelope form of the
     * same diagnostics carries the other case: {@code outbox/OutboxRelay} publishes it to the shared
     * dead-letter topic for a row this service gave up on, where a single bound topic is correct.
     *
     * <p>The outgoing record is rebuilt from an allowlist. The refused bytes, original key, arbitrary
     * producer headers, exception message and stack trace do not travel, and the failure class
     * contributes only its short name. An instance holds no mutable state, so consumer threads may
     * share one.
     */
    private static final class ByteValuedRecoverer extends DeadLetterPublishingRecoverer {

        /**
         * Builds a recoverer addressing one dead-letter topic per source topic, with the configured
         * shared topic as a fallback.
         */
        private ByteValuedRecoverer(KafkaOperations<?, ?> template,
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
        return Math.clamp(ByteBuffer.wrap(attempt.value()).getInt(), ONE_ATTEMPT,
                MAX_REPORTED_ATTEMPTS);
    }

    /** The resolved partition, and {@code null} when the resolver named a negative one. */
    private static Integer partitionOf(TopicPartition topicPartition) {
        int partition = topicPartition.partition();
        return partition < 0 ? null : partition;
    }

    /**
     * Counts one delivery this service has given up on, then hands the record to the real route.
     *
     * <p>The container calls a recoverer only once the backoff is spent, so every record arriving here
     * is one this service will not attempt again. That is counted once per RECORD under
     * {@code carddemo.ledger.dead.letters}, which is the denominator no stage of
     * {@code carddemo.ledger.failures} carries: those count attempts, and a poison message previously
     * showed only as a rising per-attempt figure with nothing marking the point of abandonment.
     *
     * <p>The count follows the send rather than preceding it, so the two outcomes are distinguishable:
     * {@code published} once the diagnostic has reached the broker, and {@code failed} when the send
     * refused it. A refusal is rethrown as well as counted, because the container has to know the
     * record was not recovered.
     *
     * <p>The cause of the failure is counted elsewhere and never here, so one business failure is
     * never reported as several. An instance holds no mutable state and a Micrometer counter accepts
     * concurrent recording, so consumer threads may share one.
     */
    private static final class CountingRecoverer implements ConsumerRecordRecoverer {

        /** Publishes the bounded diagnostic on the dead-letter topic of the source topic. */
        private final ConsumerRecordRecoverer route;

        /** The recording surface the terminal outcome lands on. */
        private final ObservabilityConfig.LedgerMeters meters;

        /**
         * Wraps {@code route} with the terminal count.
         *
         * @param route  the recoverer that publishes the diagnostic
         * @param meters the recording surface
         */
        private CountingRecoverer(ConsumerRecordRecoverer route,
                ObservabilityConfig.LedgerMeters meters) {
            this.route = Objects.requireNonNull(route, "route must be present");
            this.meters = Objects.requireNonNull(meters, "meters must be present");
        }

        @Override
        public void accept(ConsumerRecord<?, ?> failedRecord, Exception failure) {
            String failureKind = failureKindOf(failure);

            // A record the value deserializer or the schema validator refused never reaches a
            // listener, so no listener can count it. Both exceptions are registered as not
            // retryable, so a refused record reaches this recoverer exactly once and this is the one
            // place its refusal can be counted. Without this line
            // carddemo.ledger.failures{stage=deserialize} was registered and structurally
            // unreachable: it read zero however many payloads a schema control rejected, and an
            // alerting rule written against the documented metric contract could never fire.
            if (ObservabilityConfig.LedgerMeters.FAILURE_DESERIALIZATION.equals(failureKind)
                    || ObservabilityConfig.LedgerMeters.FAILURE_SCHEMA_VALIDATION
                            .equals(failureKind)) {
                meters.recordDeserializeFailure();
            }

            // One ERROR per record, at the moment the record becomes terminal. Each individual
            // attempt is logged at WARN by the listener, because a retry may still succeed and a
            // level that says otherwise trains an operator to ignore it. This line is the level an
            // alerting rule should watch, and every consumer of this platform emits it here: see
            // the same line in the notification, authorization and fraud services. It carries the
            // record coordinates and the four components of 01 ABEND-DATA at
            // app/cpy/CSMSG02Y.cpy:L21-L29, and no payload, no key and no exception chain.
            LOG.error("Routing one record to the dead-letter topic."
                    + " topic={} partition={} offset={} code={} culprit={} reason={} message={}",
                    failedRecord.topic(), failedRecord.partition(), failedRecord.offset(),
                    DEAD_LETTER_ABEND_CODE, POSTING_JOB_NAME, failureKind, DEAD_LETTER_MESSAGE);

            try {
                route.accept(failedRecord, failure);
            } catch (RuntimeException undelivered) {
                meters.recordDeadLetterFailure(failureKind);
                throw undelivered;
            }
            meters.recordDeadLetterPublished(failureKind);
        }
    }

    /**
     * Names what one delivery this service gave up on failed at, from the bounded set
     * {@code config/ObservabilityConfig} registers.
     *
     * <p>The whole cause chain is searched, most specific first. A payload the schema validator
     * rejected arrives as a {@link SerializationException} wrapped in a
     * {@link DeserializationException}, and it counts as a schema validation failure; a payload the
     * deserializer could not read at all carries the second alone. A listener that raised is wrapped
     * by the container in a {@link ListenerExecutionFailedException}, and that is a processing
     * failure whatever the business exception underneath it was. A chain naming none of the three
     * resolves to the unknown value, which is registered as well.
     *
     * @param failure the exception the container reported; may be {@code null}
     * @return one registered tag value
     */
    static String failureKindOf(Exception failure) {
        if (chainCarries(failure, SerializationException.class)) {
            return ObservabilityConfig.LedgerMeters.FAILURE_SCHEMA_VALIDATION;
        }
        if (chainCarries(failure, DeserializationException.class)) {
            return ObservabilityConfig.LedgerMeters.FAILURE_DESERIALIZATION;
        }
        if (chainCarries(failure, ListenerExecutionFailedException.class)) {
            return ObservabilityConfig.LedgerMeters.FAILURE_PROCESSING;
        }
        return ObservabilityConfig.LedgerMeters.FAILURE_UNKNOWN;
    }

    /** Whether the cause chain of {@code failure} carries an instance of {@code type}. */
    private static boolean chainCarries(Throwable failure, Class<? extends Throwable> type) {
        Throwable cause = failure;

        for (int depth = 0; cause != null && depth < MAX_CAUSE_DEPTH; depth++) {
            if (type.isInstance(cause)) {
                return true;
            }
            cause = cause.getCause() == cause ? null : cause.getCause();
        }
        return false;
    }
}
