package com.carddemo.notification.config;

import com.carddemo.notification.messaging.DeadLetterMetadata;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Pattern;

import org.apache.kafka.clients.CommonClientConfigs;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.errors.SerializationException;
import org.apache.kafka.common.header.Header;
import org.apache.kafka.common.header.Headers;
import org.apache.kafka.common.header.internals.RecordHeaders;
import org.apache.kafka.common.serialization.ByteArraySerializer;
import org.apache.kafka.common.serialization.StringSerializer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.kafka.autoconfigure.ConcurrentKafkaListenerContainerFactoryConfigurer;
import org.springframework.boot.kafka.autoconfigure.KafkaConnectionDetails;
import org.springframework.boot.kafka.autoconfigure.KafkaProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Lazy;
import org.springframework.dao.DataAccessException;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaOperations;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.ConsumerRecordRecoverer;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer.HeaderNames.HeadersToAdd;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.kafka.support.serializer.DeserializationException;
import org.springframework.util.backoff.FixedBackOff;

/**
 * Consume-side wiring for the notification service: the container factory named
 * {@code kafkaListenerContainerFactory}, the delivery-attempt policy, and the route to the
 * dead-letter topic. Every listener of this service runs in that container factory, and every
 * exception one raises reaches the error handler declared here.
 *
 * <p>ADDITIVE. No source program holds a per-service configuration class. The nearest analogues are
 * the Customer Information Control System (CICS) resource manifest at {@code app/csd/CARDDEMO.CSD}
 * and the Job Control Language (JCL) dataset allocations, and neither is such a class. The retry
 * policy, the dead-letter topic and the diagnostic headers are additions in full.
 *
 * <p>Four locators, shape only, no logic. {@code app/cbl/CBTRN02C.cbl:L707-L711} holds the four-line
 * abend paragraph this route replaces, reached from more than twenty call sites and performing no
 * cleanup. {@code app/cbl/CBTRN02C.cbl:L714-L727} renders a two-byte file status as four digits, the
 * width of {@code ABEND-CODE PIC X(4)}. {@code app/cpy/CSMSG02Y.cpy:L21-L29} declares the four-field
 * record the dead-letter headers carry. {@code app/cbl/CBSTM03A.CBL:L359-L360} pairs one failing
 * operation with its return code, and one dead-letter record replaces that report.
 *
 * <p>{@code app/cbl/CBTRN02C.cbl:L229-L230} sets return code 4 once the reject count passes zero,
 * and calls no abend routine. A rejection is expected traffic, and no normal outcome reaches the
 * failure counter. Shape only, no logic.
 *
 * <p>Extension route. A new consumer is a listener that runs in this container factory, and a new
 * topic is a new property key. Neither edits this class. A listener naming no factory of its own
 * resolves the bean name {@code kafkaListenerContainerFactory}, and declaring that name stands the
 * auto-configured factory down.
 *
 * <p>Four facts a contributor needs. This module compiles at release 25 through the
 * {@code java.version} property in its own {@code pom.xml}, which the Spring Boot 4.1.0 parent
 * otherwise defaults to 17, and class-file major version 69 is the proof. The five helpers in
 * {@code com.carddemo.cobol} are final and expose static members alone, so none can be a bean and
 * every caller invokes them statically. Nothing here reaches a broker, and the application context
 * starts while the broker is unreachable. The value deserializer and its delegate are named in
 * {@code src/main/resources/application.yml}, and the injected consumer factory carries that wiring.
 *
 * <p>Pinned here: Java 25, Spring Boot 4.1.0, spring-kafka 4.1.0, kafka-clients 4.2.1, and the
 * broker image {@code apache/kafka:4.2.1} in KRaft mode at {@code kafka:9092}.
 *
 * <p>Decisions: {@code card-platform/docs/decision-log.md}.
 */
@Configuration
public class KafkaConsumerConfig {

    /**
     * The four headers a dead letter carries, one per component of {@code 01 ABEND-DATA} at
     * {@code app/cpy/CSMSG02Y.cpy:L21-L29}, at widths of four, eight, fifty and seventy-two.
     */
    static final String HEADER_ABEND_CODE = "carddemo-dl-code";
    static final String HEADER_CULPRIT = "carddemo-dl-culprit";
    static final String HEADER_REASON = "carddemo-dl-reason";
    static final String HEADER_MESSAGE = "carddemo-dl-message";

    /**
     * The two fixed components of every dead letter here, and the {@code reason} of one whose
     * failure names no class. {@code MOVE 999 TO ABCODE} at {@code app/cbl/CBTRN02C.cbl:L710}
     * carries the code, in the four-digit form the status formatter renders.
     */
    static final String ABEND_CODE = "0999";
    static final String SERVICE_CULPRIT = "NOTIFSVC";
    static final String UNCLASSIFIED_REASON = "UNCLASSIFIED";

    /** Replacement for a run of digits long enough to hold a Primary Account Number (PAN). */
    static final String REDACTED_DIGITS = "[redacted]";

    /** The one logger of this class. Structured console output is configured in the shipped file. */
    private static final Logger LOG = LoggerFactory.getLogger(KafkaConsumerConfig.class);

    /** Shortest run of digits the scrub replaces. A stored card number holds sixteen. */
    private static final Pattern LONG_DIGIT_RUN = Pattern.compile("[0-9]{12,}");

    /** A negative partition lets the broker select one. */
    private static final int BROKER_SELECTS_PARTITION = -1;

    /** {@link FixedBackOff} counts retries, and the first delivery is an attempt. */
    private static final long FIRST_DELIVERY = 1L;

    /** Depth cap on a cause-chain walk, which ends the walk on a self-referencing cause. */
    private static final int MAX_CAUSE_DEPTH = 16;

    /**
     * Builds the template one failed record travels to the dead-letter topic through.
     *
     * <p>Three settings are pinned: the bootstrap servers, text serialization on the key, and byte
     * serialization on the value. Every value on this route is already bytes, either the refused
     * payload or the diagnostic record, and a byte serializer writes it onward unchanged. The
     * connection address, the security protocol and the login settings arrive from the common
     * {@code spring.kafka} block, which both broker client listeners require. This module declares
     * no producer block and no setting here reads one. The factory opens no connection until the
     * first send.
     *
     * @param kafkaProperties   the bound common {@code spring.kafka} block
     * @param connectionDetails the broker address and security protocol this deployment resolved
     * @return the template the recoverer sends one failed record through
     */
    @Bean
    @Lazy
    public KafkaTemplate<String, byte[]> deadLetterKafkaTemplate(
            KafkaProperties kafkaProperties, KafkaConnectionDetails connectionDetails) {

        Map<String, Object> settings = new LinkedHashMap<>(kafkaProperties.getProperties());

        KafkaConnectionDetails.Configuration producer = connectionDetails.getProducer();
        settings.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, producer.getBootstrapServers());
        String securityProtocol = producer.getSecurityProtocol();
        if (securityProtocol != null) {
            settings.put(CommonClientConfigs.SECURITY_PROTOCOL_CONFIG, securityProtocol);
        }
        settings.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        settings.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, ByteArraySerializer.class);

        return new KafkaTemplate<>(new DefaultKafkaProducerFactory<>(settings));
    }

    /**
     * Builds the route to the topic {@code carddemo.kafka.topics.dead-letter} names.
     *
     * <p>Four headers carry the {@link DeadLetterMetadata} components, and
     * {@link KafkaHeaders#DLT_EXCEPTION_MESSAGE} repeats the scrubbed message under its canonical
     * name. The stack-trace header is excluded, and each header value is UTF-8.
     *
     * @param deadLetterKafkaTemplate the byte-serializing template
     * @param deadLetterTopic         the topic every unconsumable record reaches
     * @return the recoverer the error handler hands one spent record to
     */
    @Bean
    @Lazy
    public DeadLetterPublishingRecoverer notificationDeadLetterRecoverer(
            KafkaTemplate<String, byte[]> deadLetterKafkaTemplate,
            @Value("${carddemo.kafka.topics.dead-letter}") String deadLetterTopic) {

        return new ByteValuedRecoverer(deadLetterKafkaTemplate, deadLetterTopic);
    }

    /**
     * Builds the delivery-attempt policy and hands a spent record to the dead-letter route.
     *
     * <p>{@link FixedBackOff} counts retries, so the second argument is one less than
     * {@code carddemo.consumer.retry.max-attempts}. At the shipped values of three attempts and one
     * thousand milliseconds that yields three deliveries at one-second intervals: the first attempt
     * and two retries. {@link DeserializationException} and {@link SerializationException} are
     * registered as not retryable, and a record either of them refused reaches the recoverer on the
     * first pass. Acknowledgement after handling keeps its default, which commits the offset of a
     * recovered record and lets the consumer advance.
     *
     * @param notificationDeadLetterRecoverer the dead-letter route
     * @param notificationMetrics             the meter holder {@code config/ObservabilityConfig}
     *                                        declares
     * @param maxAttempts                     deliveries of one record, counting the first
     * @param backoffMs                       milliseconds between two deliveries
     * @return the error handler the container factory installs
     * @throws IllegalStateException when the attempt count is under one or the wait is negative
     */
    @Bean
    @Lazy
    public DefaultErrorHandler notificationConsumerErrorHandler(
            DeadLetterPublishingRecoverer notificationDeadLetterRecoverer,
            ObservabilityConfig.NotificationMetrics notificationMetrics,
            @Value("${carddemo.consumer.retry.max-attempts}") int maxAttempts,
            @Value("${carddemo.consumer.retry.backoff-ms}") long backoffMs) {

        requireAtLeast(maxAttempts, FIRST_DELIVERY, "carddemo.consumer.retry.max-attempts");
        requireAtLeast(backoffMs, 0L, "carddemo.consumer.retry.backoff-ms");

        ConsumerRecordRecoverer countingRecoverer =
                new CountingRecoverer(notificationDeadLetterRecoverer, notificationMetrics);

        DefaultErrorHandler errorHandler = new DefaultErrorHandler(countingRecoverer,
                new FixedBackOff(backoffMs, maxAttempts - FIRST_DELIVERY));
        errorHandler.addNotRetryableExceptions(DeserializationException.class,
                SerializationException.class);

        return errorHandler;
    }

    /**
     * Builds the container factory the annotation-driven listener infrastructure resolves by name,
     * and hands a listener one record per invocation.
     *
     * <p>The injected consumer factory carries the deserializer wiring, and the injected configurer
     * applies every {@code spring.kafka.listener} and {@code spring.kafka.consumer} value, the
     * acknowledgement mode among them. No such value is restated here. The group identifier stays
     * the one the shipped configuration names, and each listener overrides it by placeholder, which
     * lets two consumer groups run behind this one factory.
     *
     * @param consumerFactory the auto-configured consumer factory
     * @param configurer      the auto-configured container-factory configurer
     * @param notificationConsumerErrorHandler the delivery-attempt policy and the dead-letter route
     * @return the container factory every listener of this service runs in
     */
    @Bean
    @Lazy
    public ConcurrentKafkaListenerContainerFactory<Object, Object> kafkaListenerContainerFactory(
            ConsumerFactory<Object, Object> consumerFactory,
            ConcurrentKafkaListenerContainerFactoryConfigurer configurer,
            DefaultErrorHandler notificationConsumerErrorHandler) {

        ConcurrentKafkaListenerContainerFactory<Object, Object> factory =
                new ConcurrentKafkaListenerContainerFactory<>();

        configurer.configure(factory, consumerFactory);
        factory.setCommonErrorHandler(notificationConsumerErrorHandler);
        factory.getContainerProperties().setDeliveryAttemptHeader(true);

        return factory;
    }

    /**
     * Builds the four metadata headers and the canonical exception-message header.
     *
     * @param failure the exception the container reported; may be {@code null}
     * @return the headers one dead letter carries
     */
    private static Headers diagnosticHeaders(Exception failure) {
        DeadLetterMetadata metadata = metadataOf(failure);
        Headers headers = new RecordHeaders();

        headers.add(HEADER_ABEND_CODE, utf8(metadata.abendCode()));
        headers.add(HEADER_CULPRIT, utf8(metadata.culprit()));
        headers.add(HEADER_REASON, utf8(metadata.reason()));
        headers.add(HEADER_MESSAGE, utf8(metadata.message()));
        headers.add(KafkaHeaders.DLT_EXCEPTION_MESSAGE, utf8(metadata.message()));

        return headers;
    }

    /**
     * Maps one failure onto the four fixed-width components. {@code reason} holds the unqualified
     * name of the deepest cause and {@code message} holds its scrubbed text, each shortened and
     * padded by the factory to the width its copybook field declares.
     *
     * @param failure the exception the container reported; may be {@code null}
     * @return one metadata record, its four components at their declared widths
     */
    static DeadLetterMetadata metadataOf(Exception failure) {
        Throwable cause = deepestCause(failure);

        if (cause == null) {
            return DeadLetterMetadata.of(ABEND_CODE, SERVICE_CULPRIT, UNCLASSIFIED_REASON, null);
        }
        return DeadLetterMetadata.of(ABEND_CODE, SERVICE_CULPRIT,
                cause.getClass().getSimpleName(), scrub(cause.getMessage()));
    }

    /**
     * Names the {@code failure.kind} tag of one failure, drawn from the bounded set
     * {@code config/ObservabilityConfig} registers.
     *
     * <p>The whole cause chain is searched for one type at a time, most specific first. A schema
     * violation arrives as a {@link SerializationException} wrapped in a
     * {@link DeserializationException}, and it counts as a schema validation failure. A chain naming
     * none of the three types resolves to {@code unknown}, which is itself a registered tag value.
     *
     * @param failure the exception the container reported; may be {@code null}
     * @return one registered tag value
     */
    static String failureKindOf(Exception failure) {
        if (chainCarries(failure, SerializationException.class)) {
            return ObservabilityConfig.NotificationMetrics.FAILURE_SCHEMA_VALIDATION;
        }
        if (chainCarries(failure, DeserializationException.class)) {
            return ObservabilityConfig.NotificationMetrics.FAILURE_DESERIALIZATION;
        }
        if (chainCarries(failure, DataAccessException.class)) {
            return ObservabilityConfig.NotificationMetrics.FAILURE_PERSISTENCE;
        }
        return ObservabilityConfig.NotificationMetrics.UNKNOWN;
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

    /**
     * Replaces every run of twelve or more digits with {@link #REDACTED_DIGITS}. A schema names a
     * failing field by its JavaScript Object Notation (JSON) pointer, and a persistence failure can
     * carry a bound parameter into its text. Neither a full PAN nor any other long digit run
     * survives this call.
     *
     * @param text the text of one failure; may be {@code null}
     * @return the text with every long digit run replaced, or {@code null} for a {@code null} input
     */
    static String scrub(String text) {
        return text == null ? null : LONG_DIGIT_RUN.matcher(text).replaceAll(REDACTED_DIGITS);
    }

    /** The deepest cause of {@code failure}, and {@code null} when {@code failure} is null. */
    private static Throwable deepestCause(Throwable failure) {
        Throwable cause = failure;

        for (int depth = 0; cause != null && depth < MAX_CAUSE_DEPTH; depth++) {
            Throwable next = cause.getCause();
            if (next == null || next == cause) {
                return cause;
            }
            cause = next;
        }
        return cause;
    }

    /** One component as UTF-8 bytes. A component of a constructed record is never null. */
    private static byte[] utf8(String component) {
        return component.getBytes(StandardCharsets.UTF_8);
    }

    /** Stops start-up when a bound setting falls below its floor, naming the property and no value. */
    private static void requireAtLeast(long value, long floor, String property) {
        if (value < floor) {
            throw new IllegalStateException("The property " + property + " must be " + floor
                    + " or greater, and the bound value is " + value + ".");
        }
    }

    /**
     * Addresses one failed record to the dead-letter topic with a byte value on every path.
     *
     * <p>The superclass supplies the refused bytes when a deserializer rejected the value, and those
     * bytes travel as the outgoing value. A listener failure leaves those bytes absent, since the
     * deserializer already consumed them, and the outgoing value is then the
     * {@value DeadLetterMetadata#RECORD_LENGTH}-character record of {@code 01 ABEND-DATA} at
     * {@code app/cpy/CSMSG02Y.cpy:L21-L29}. Both forms serialize through
     * {@link org.apache.kafka.common.serialization.ByteArraySerializer}, and neither carries a
     * payload field value.
     *
     * <p>An instance holds no mutable state, so consumer threads may share one.
     */
    private static final class ByteValuedRecoverer extends DeadLetterPublishingRecoverer {

        private ByteValuedRecoverer(KafkaOperations<?, ?> template, String deadLetterTopic) {
            super(template, (failedRecord, failure) ->
                    new TopicPartition(deadLetterTopic, BROKER_SELECTS_PARTITION));
            excludeHeader(HeadersToAdd.EX_STACKTRACE, HeadersToAdd.EX_MSG);
            setHeadersFunction((failedRecord, failure) -> diagnosticHeaders(failure));
        }

        @Override
        protected ProducerRecord<Object, Object> createProducerRecord(
                ConsumerRecord<?, ?> failedRecord, TopicPartition topicPartition, Headers headers,
                byte[] key, byte[] value) {

            Object outgoingKey =
                    key == null ? failedRecord.key() : new String(key, StandardCharsets.UTF_8);
            byte[] outgoingValue = value == null ? diagnosticRecord(headers) : value;
            int partition = topicPartition.partition();

            return new ProducerRecord<>(topicPartition.topic(),
                    partition < 0 ? null : partition, outgoingKey, outgoingValue, headers);
        }

        /** The fixed-width diagnostic record, rebuilt from the four headers already attached. */
        private static byte[] diagnosticRecord(Headers headers) {
            return DeadLetterMetadata.of(headerText(headers, HEADER_ABEND_CODE),
                            headerText(headers, HEADER_CULPRIT),
                            headerText(headers, HEADER_REASON),
                            headerText(headers, HEADER_MESSAGE))
                    .toFixedWidthRecord().getBytes(StandardCharsets.UTF_8);
        }

        /** One header as text, and {@code null} when the header carries no value. */
        private static String headerText(Headers headers, String name) {
            Header header = headers.lastHeader(name);
            return header == null || header.value() == null
                    ? null : new String(header.value(), StandardCharsets.UTF_8);
        }

    }

    /**
     * Counts one dead-lettered record, reports it once, then hands it to the dead-letter route.
     *
     * <p>An instance holds no mutable state and a Micrometer counter accepts concurrent recording,
     * so consumer threads may share one. The log line carries the record coordinates and the four
     * scrubbed components, and no payload, no key and no exception chain.
     */
    private static final class CountingRecoverer implements ConsumerRecordRecoverer {

        private final ConsumerRecordRecoverer route;
        private final ObservabilityConfig.NotificationMetrics metrics;

        private CountingRecoverer(ConsumerRecordRecoverer route,
                ObservabilityConfig.NotificationMetrics metrics) {
            this.route = route;
            this.metrics = metrics;
        }

        @Override
        public void accept(ConsumerRecord<?, ?> failedRecord, Exception failure) {
            DeadLetterMetadata metadata = metadataOf(failure);

            this.metrics.failures(failureKindOf(failure)).increment();
            LOG.error("Routing one record to the dead-letter topic."
                    + " topic={} partition={} offset={} code={} culprit={} reason={} message={}",
                    failedRecord.topic(), failedRecord.partition(), failedRecord.offset(),
                    metadata.abendCode(), metadata.culprit(), metadata.reason(),
                    metadata.message());

            this.route.accept(failedRecord, failure);
        }
    }
}
