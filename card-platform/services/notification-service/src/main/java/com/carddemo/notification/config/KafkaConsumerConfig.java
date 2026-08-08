package com.carddemo.notification.config;

import com.carddemo.notification.messaging.DeadLetterMetadata;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

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
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaOperations;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.ConsumerRecordRecoverer;
import org.springframework.kafka.listener.ContainerProperties;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer.HeaderNames.HeadersToAdd;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.kafka.listener.ListenerExecutionFailedException;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.kafka.support.serializer.DeserializationException;
import org.springframework.util.backoff.FixedBackOff;

/**
 * Consume-side wiring for the notification service: the container factory named
 * {@code kafkaListenerContainerFactory}, the delivery-attempt policy, and the route to the
 * dead-letter topic. Every listener of this service runs in that container factory, and every
 * exception one raises reaches the error handler declared here.
 *
 * <p>No COBOL ancestor. No source program holds a per-service configuration class. The nearest
 * analogues are the Customer Information Control System (CICS) resource manifest at {@code
 * app/csd/CARDDEMO.CSD} and the Job Control Language (JCL) dataset allocations, and neither is such
 * a class. The retry policy, the dead-letter topic and the diagnostic headers are additions in
 * full.
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
 * <p>Two properties of the dead-letter route are guarantees rather than details, and
 * {@link #notificationDeadLetterRecoverer} states each with its reason. No byte of a refused record
 * and no header or key a producer chose reaches a dead-letter topic, and one dead-letter topic
 * carries the failures of one source topic and therefore one payload shape.
 *
 * <p>Extension route. A new consumer is a listener that runs in this container factory, and a new
 * topic is a new property key. Neither edits this class. A listener naming no factory of its own
 * resolves the bean name {@code kafkaListenerContainerFactory}, and declaring that name stands the
 * auto-configured factory down.
 *
 * <p>Four facts a contributor needs. This module compiles at release 25 through the
 * {@code java.version} property in its own {@code pom.xml}. The Spring Boot 4.1.0 parent otherwise
 * defaults to 17, and class-file major version 69 is the proof. The five helpers in
 * {@code com.carddemo.cobol} are final and expose static members alone, so none can be a bean and
 * every caller invokes them statically. Nothing here reaches a broker, and the application context
 * starts while the broker is unreachable. The value deserializer and its delegate are named in
 * {@code src/main/resources/application.yml}, and the injected consumer factory carries that wiring.
 *
 * <p>Pinned here: Java 25, Spring Boot 4.1.0, spring-kafka 4.1.0, kafka-clients 4.2.1, and the
 * broker image {@code apache/kafka:4.2.1} in KRaft mode, reached on its internal listener
 * {@code kafka:29092} from inside the compose network and on {@code localhost:9092} from the host.
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

    /** Fixed detail carried instead of an exception message, which may contain rejected data. */
    static final String SAFE_FAILURE_MESSAGE = "record rejected; inspect broker coordinates";

    /**
     * The only headers a dead letter carries. Every other header is dropped, including one the
     * framework added and one a producer chose.
     *
     * <p>Five are generated by this class and carry the four sanitized metadata components plus the
     * canonical message header. Four are the record coordinates the superclass adds, which a reader
     * needs to locate the record in its source topic and which no producer controls.
     *
     * <p>A producer chooses its own headers, so a header this class did not put on the record can
     * carry anything a payload can carry: a full Primary Account Number, a token, a stack trace
     * from an upstream service. Dropping every unnamed header is the only rule that stays correct
     * when a producer adds a header nobody here anticipated.
     */
    static final Set<String> ALLOWED_HEADERS = Set.of(
            HEADER_ABEND_CODE, HEADER_CULPRIT, HEADER_REASON, HEADER_MESSAGE,
            KafkaHeaders.DLT_EXCEPTION_MESSAGE,
            KafkaHeaders.DLT_ORIGINAL_TOPIC, KafkaHeaders.DLT_ORIGINAL_PARTITION,
            KafkaHeaders.DLT_ORIGINAL_OFFSET, KafkaHeaders.DLT_ORIGINAL_TIMESTAMP);

    /** The one logger of this class. Structured console output is configured in the shipped file. */
    private static final Logger LOG = LoggerFactory.getLogger(KafkaConsumerConfig.class);

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

        KafkaTemplate<String, byte[]> template =
                new KafkaTemplate<>(new DefaultKafkaProducerFactory<>(settings));
        // A failed send records its destination and failure type only.
        // SafeProducerListener displaces LoggingProducerListener, which would write the
        // key and the first hundred characters of the payload into the log line.
        template.setProducerListener(new SafeProducerListener<>());
        return template;
    }

    /**
     * Builds the route one unconsumable record travels to a dead-letter topic on.
     *
     * <p>Three properties of this route matter more than its wiring, and each answers a way a
     * dead-letter topic leaks what the record it came from carried.
     *
     * <p>One. <strong>No byte of the refused record travels.</strong> A record reaches this route
     * because a deserializer refused it or a listener failed on it, and in the first case the
     * refused payload is exactly the bytes that failed validation: a full Primary Account Number
     * (PAN) a producer put in the wrong field, or an unbounded string, or a card verification value
     * that no schema declares. Republishing those bytes moves the value that failed a control past
     * the control, onto a topic with a different set of readers. The outgoing value is therefore
     * always the {@value DeadLetterMetadata#RECORD_LENGTH}-character record of
     * {@code 01 ABEND-DATA} at {@code app/cpy/CSMSG02Y.cpy:L21-L29}, built from four sanitized
     * components, and never the payload.
     *
     * <p>Two. <strong>No original header and no original key travels.</strong> A producer chooses
     * both, so both can carry anything a payload can. The outgoing headers are rebuilt from an
     * allowlist -- {@link #ALLOWED_HEADERS}, which names five headers this class generates and the
     * four record coordinates the framework generates -- and every other header is dropped, whether
     * the framework added it or a producer did. The outgoing key is the record's own coordinates,
     * which a reader needs to find the record in its source topic and which no producer controls.
     *
     * <p>Three. <strong>One dead-letter topic carries one payload shape.</strong> The destination
     * is the source topic name with {@code carddemo.kafka.topics.dead-letter-suffix} appended, so a
     * posted-balance failure and an assessment failure land on separate topics. A single shared
     * topic would carry both, and a reader of it could not parse a record without first guessing
     * which shape it held. {@code carddemo.kafka.topics.dead-letter} remains the fallback for a
     * record whose source topic the container did not report, which is the one case a suffix cannot
     * address.
     *
     * <p>Every value on the route is bytes, so a byte serializer writes it onward unchanged.
     *
     * @param deadLetterKafkaTemplate the byte-serializing template
     * @param deadLetterTopic         the fallback topic, used when a record names no source topic
     * @param deadLetterSuffix        appended to the source topic name to address its dead-letter
     *                                topic
     * @return the recoverer the error handler hands one spent record to
     */
    @Bean
    @Lazy
    public DeadLetterPublishingRecoverer notificationDeadLetterRecoverer(
            KafkaTemplate<String, byte[]> deadLetterKafkaTemplate,
            @Value("${carddemo.kafka.topics.dead-letter}") String deadLetterTopic,
            @Value("${carddemo.kafka.topics.dead-letter-suffix}") String deadLetterSuffix) {

        return new ByteValuedRecoverer(deadLetterKafkaTemplate, deadLetterTopic, deadLetterSuffix);
    }

    /**
     * Builds the delivery-attempt policy and hands a spent record to the dead-letter route.
     *
     * <p>{@link FixedBackOff} counts retries, so the second argument is one less than
     * {@code carddemo.consumer.retry.max-attempts}. At the shipped values of three attempts and one
     * thousand milliseconds that yields three deliveries at one-second intervals: the first attempt
     * and two retries. {@link DeserializationException} and {@link SerializationException} are
     * registered as not retryable, and a record either of them refused reaches the recoverer on the
     * first pass.
     *
     * <p>{@link DefaultErrorHandler#setCommitRecovered(boolean)} makes the dead-letter route
     * terminal. Every container of this service acknowledges by hand, so nothing acknowledges a
     * record its listener never accepted, and the offset of a dead-lettered record stayed
     * uncommitted: the next start-up or the next partition assignment read that record again and
     * published a second dead letter naming the same coordinates. The setting commits the offset
     * after the recoverer returns, which is after the diagnostic reached the broker, so one refused
     * record produces exactly one dead letter and {@code records_dead_lettered} carries one
     * increment per record. The container applies it under acknowledgement mode
     * {@code MANUAL_IMMEDIATE}, which {@code spring.kafka.listener.ack-mode} names in the shipped
     * file; under {@code MANUAL} the container reports the setting as ignored and commits nothing.
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
        errorHandler.setCommitRecovered(true);

        return errorHandler;
    }

    /**
     * Builds the container factory the annotation-driven listener infrastructure resolves by name,
     * and hands a listener one record per invocation.
     *
     * <p>The injected consumer factory carries the deserializer wiring, and the injected configurer
     * applies every {@code spring.kafka.listener} and {@code spring.kafka.consumer} value, the
     * acknowledgement mode among them. No such value is restated here. The group identifier stays
     * the one the shipped configuration names, and a listener overrides it by placeholder, which
     * lets more than one consumer group run behind this one factory.
     *
     * <p>The mode that configurer produced is then read back and held at
     * {@code MANUAL_IMMEDIATE}. That is the one mode which commits the offset at the acknowledgement
     * each of these four listeners issues after its own writes commit, and the one mode under which
     * the framework applies {@link DefaultErrorHandler#setCommitRecovered(boolean)} to a
     * dead-lettered record. A deployment naming {@code MANUAL} or an automatic mode would take both
     * away without a word, so the context stops instead.
     *
     * @param consumerFactory the auto-configured consumer factory
     * @param configurer      the auto-configured container-factory configurer
     * @param notificationConsumerErrorHandler the delivery-attempt policy and the dead-letter route
     * @return the container factory every listener of this service runs in
     * @throws IllegalStateException when the effective acknowledgement mode is not
     *                               {@code MANUAL_IMMEDIATE}
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
        requireImmediateManualAcknowledgement(factory.getContainerProperties().getAckMode());

        return factory;
    }

    /**
     * Holds the effective acknowledgement mode at {@code MANUAL_IMMEDIATE}, naming the property that
     * moved it when it is anything else.
     *
     * @param ackMode the mode the configurer left on the container properties
     * @return the same mode, once it is the one this service supports
     * @throws IllegalStateException when the mode is absent or names another mode
     */
    static ContainerProperties.AckMode requireImmediateManualAcknowledgement(
            ContainerProperties.AckMode ackMode) {

        if (ackMode != ContainerProperties.AckMode.MANUAL_IMMEDIATE) {
            throw new IllegalStateException("The property spring.kafka.listener.ack-mode must name "
                    + ContainerProperties.AckMode.MANUAL_IMMEDIATE
                    + ", because that is the one mode which commits the offset at the"
                    + " acknowledgement a listener issues after its own writes commit and the one"
                    + " mode under which a dead-lettered record's offset is committed. The effective"
                    + " mode is " + ackMode + ".");
        }
        return ackMode;
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
     * name of the deepest cause. {@code message} holds one fixed instruction rather than the
     * exception message, because an exception can repeat the rejected value. The factory shortens
     * and pads both to the widths their copybook fields declare.
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
                cause.getClass().getSimpleName(), SAFE_FAILURE_MESSAGE);
    }

    /**
     * Names the {@code failure.kind} tag of one failure, drawn from the bounded set
     * {@code config/ObservabilityConfig} registers.
     *
     * <p>The whole cause chain is searched for one type at a time, most specific first. A schema
     * violation arrives as a {@link SerializationException} wrapped in a
     * {@link DeserializationException}, and it counts as a schema validation failure. A database
     * fault is named by
     * {@link ObservabilityConfig.NotificationMetrics#isPersistenceFault(Throwable)}, which is the one
     * place the platform decides what that tag covers. A fault the listener itself raised arrives
     * wrapped in a {@link ListenerExecutionFailedException}, and once it is not a persistence fault
     * it is a rendering failure. A chain naming none of them resolves to {@code unknown}, which is
     * itself a registered tag value.
     *
     * <p><b>Why the rendering branch has to be here.</b> Each listener classifies its own failures
     * with the same rule -- a persistence fault, or else a rendering failure -- and counts one per
     * attempt on {@link ObservabilityConfig.NotificationMetrics#failures(String)}. This method counts
     * one per record given up on, on
     * {@link ObservabilityConfig.NotificationMetrics#deadLettered(String)}. Without the branch the
     * two series disagreed on the same failure: the attempts were filed as {@code rendering} and the
     * record that exhausted them was filed as {@code unknown}, so the registered
     * {@code rendering} value of the terminal series was unreachable and an operator reconciling the
     * two could not tell which rendering failures had actually been given up on.
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
        if (ObservabilityConfig.NotificationMetrics.isPersistenceFault(failure)) {
            return ObservabilityConfig.NotificationMetrics.FAILURE_PERSISTENCE;
        }
        if (chainCarries(failure, ListenerExecutionFailedException.class)) {
            return ObservabilityConfig.NotificationMetrics.FAILURE_RENDERING;
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
     * Names the dead-letter topic of one source topic.
     *
     * @param failedRecord  the record that could not be consumed
     * @param fallbackTopic the topic to use when the record names no source topic
     * @param suffix        appended to the source topic name
     * @return the source topic name with {@code suffix} appended, or {@code fallbackTopic}
     */
    static String resolveDeadLetterDestination(ConsumerRecord<?, ?> failedRecord,
            String fallbackTopic, String suffix) {

        String sourceTopic = failedRecord == null ? null : failedRecord.topic();
        return sourceTopic == null || sourceTopic.isBlank()
                ? fallbackTopic
                : sourceTopic + suffix;
    }

    /**
     * Rebuilds one failed record as sanitized diagnostic metadata.
     *
     * <p>The method reads the failed record only for its broker coordinates. It never reads its key
     * or value. The outgoing headers are a fresh allowlisted set, so a producer-controlled header
     * has no path to the dead-letter topic either.</p>
     *
     * @param failedRecord   the record that could not be consumed
     * @param topicPartition the dead-letter destination
     * @param headers        the headers the recoverer assembled
     * @return a record carrying coordinates, fixed-width diagnostics and allowlisted headers
     */
    static ProducerRecord<Object, Object> sanitizedDeadLetterRecord(
            ConsumerRecord<?, ?> failedRecord, TopicPartition topicPartition, Headers headers) {

        int partition = topicPartition.partition();
        return new ProducerRecord<>(topicPartition.topic(),
                partition < 0 ? null : partition,
                recordCoordinates(failedRecord),
                diagnosticRecord(headers),
                allowedDeadLetterHeaders(headers));
    }

    /** The record's broker coordinates, which replace the producer-controlled key. */
    private static String recordCoordinates(ConsumerRecord<?, ?> failedRecord) {
        return failedRecord == null
                ? null
                : failedRecord.topic() + "-" + failedRecord.partition() + "-"
                        + failedRecord.offset();
    }

    /** A fresh header set holding allowed diagnostics and broker coordinates alone. */
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
        if (headers == null) {
            return null;
        }

        Header header = headers.lastHeader(name);
        return header == null || header.value() == null
                ? null : new String(header.value(), StandardCharsets.UTF_8);
    }

    /**
     * Publishes one sanitized diagnostic record per failed record, to the dead-letter topic of the
     * topic the record came from.
     *
     * <p>The superclass hands {@code createProducerRecord} the refused key and the refused value
     * when a deserializer rejected them, and the inherited behaviour is to republish both. This
     * class republishes neither. The outgoing value is always the
     * {@value DeadLetterMetadata#RECORD_LENGTH}-character record of {@code 01 ABEND-DATA} at
     * {@code app/cpy/CSMSG02Y.cpy:L21-L29}, and the outgoing key is always the record's own
     * coordinates. One shape reaches a dead-letter topic, from every failure path, so a reader
     * parses it without inspecting it first.
     *
     * <p>{@link #resolveDeadLetterDestination(ConsumerRecord, String, String)} picks the topic, and
     * the four sanitized components come from headers this class attached. An instance holds no
     * mutable state, so consumer threads may share one.
     */
    private static final class ByteValuedRecoverer extends DeadLetterPublishingRecoverer {

        private ByteValuedRecoverer(KafkaOperations<?, ?> template, String deadLetterTopic,
                String deadLetterSuffix) {
            super(template, (failedRecord, failure) -> new TopicPartition(
                    resolveDeadLetterDestination(
                            failedRecord, deadLetterTopic, deadLetterSuffix),
                    BROKER_SELECTS_PARTITION));
            excludeHeader(HeadersToAdd.EX_STACKTRACE, HeadersToAdd.EX_MSG);
            setHeadersFunction((failedRecord, failure) -> diagnosticHeaders(failure));
        }

        @Override
        protected ProducerRecord<Object, Object> createProducerRecord(
                ConsumerRecord<?, ?> failedRecord, TopicPartition topicPartition, Headers headers,
                byte[] key, byte[] value) {

            return sanitizedDeadLetterRecord(failedRecord, topicPartition, headers);
        }

    }

    /**
     * Counts one dead-lettered record, reports it once, then hands it to the dead-letter route.
     *
     * <p>This is the one place a terminal outcome is counted, and it counts one record rather than
     * one attempt. The container reaches it once, after the delivery attempts of a record have run
     * out, so a record taken three times increments
     * {@link ObservabilityConfig.NotificationMetrics#deadLettered(String)} once here and
     * {@link ObservabilityConfig.NotificationMetrics#failures(String)} three times in the listener.
     * Both series therefore carry one documented unit, and neither is a mixture of the two.
     *
     * <p>An instance holds no mutable state and a Micrometer counter accepts concurrent recording,
     * so consumer threads may share one. The log line carries the record coordinates and the four
     * sanitized components, and no payload, no key and no exception chain.
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

            LOG.error("Routing one record to the dead-letter topic."
                    + " topic={} partition={} offset={} code={} culprit={} reason={} message={}",
                    failedRecord.topic(), failedRecord.partition(), failedRecord.offset(),
                    metadata.abendCode(), metadata.culprit(), metadata.reason(),
                    metadata.message());

            // The count follows the publication, and the ordering is the whole point. An increment
            // ahead of this call counted a diagnostic the broker then refused, so the series read
            // one higher than the number of records actually on the dead-letter topic, and an
            // operator reconciling the series against the topic found a record that was never
            // there. The ledger and fraud recoverers already count after their delegate returns;
            // this line makes the notification series mean the same thing. A refusal propagates
            // from route.accept as it always did, and the ERROR line above is emitted either way,
            // so nothing is lost when the count does not happen.
            this.route.accept(failedRecord, failure);
            this.metrics.deadLettered(failureKindOf(failure)).increment();
        }
    }
}
