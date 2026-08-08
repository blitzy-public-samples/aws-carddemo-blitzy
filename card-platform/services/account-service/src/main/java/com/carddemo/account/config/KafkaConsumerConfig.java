package com.carddemo.account.config;

import com.carddemo.account.messaging.DeadLetterMetadata;
import com.carddemo.events.DeadLetterEnvelope;
import com.carddemo.events.serde.EventContracts;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

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
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.ConsumerRecordRecoverer;
import org.springframework.kafka.listener.ContainerProperties;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer.HeaderNames.HeadersToAdd;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.kafka.support.serializer.DeserializationException;
import org.springframework.util.backoff.FixedBackOff;

import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * The consume side of the account service: one listener, a bounded retry policy and one dead-letter
 * route.
 *
 * <p>ADDITIVE IN FULL. The source consumes nothing and needed to consume nothing: the posting
 * program added to the {@code ACCTDAT} record at {@code app/cbl/CBTRN02C.cbl:L545-L560} and the
 * account view program read the same record, so no message joined them. This configuration exists
 * because that record is now owned here while the posting arithmetic runs in
 * {@code ledger-posting-service}, and the amount travels between the two as
 * {@code TransactionPosted}. {@code messaging/TransactionPostedConsumer} is the listener, and it
 * exists to keep the balance and the two billing-cycle accumulators of the account record moving
 * with the postings applied against them.
 *
 * <p>The listener runs behind the one container factory this class builds.
 * {@link ConcurrentKafkaListenerContainerFactoryConfigurer} applies every
 * {@code spring.kafka.listener} and {@code spring.kafka.consumer} value the shipped file declares,
 * the manual acknowledgement mode and {@code auto-startup} among them, so none of those is restated
 * here. Restating one is how a hand-built factory quietly stops honouring a setting the
 * configuration file names. What this class adds is the delivery-attempt policy, the dead-letter
 * route and the delivery-attempt header the route reports.
 *
 * <p>The payload arrives as a bound record. {@code libs/event-contracts} holds both the document and
 * the class for {@code TransactionPosted}, so {@code JsonSchemaValidatingDeserializer} validates the
 * document against {@code schemas/transaction-posted-v2.json} and hands over the record itself. That
 * differs from the two replica listeners of the authorization service, which read a tree because the
 * events they consume belong to a service whose module they may not depend on.
 *
 * <p>Two failures, two behaviours. A deserialization or schema failure is not retried, because the
 * same bytes fail the same control on every attempt and retrying them only delays the partition. Any
 * other failure is retried under {@code carddemo.consumer.retry} and then takes the dead-letter
 * route. That route replaces the four-line abend at {@code app/cbl/CBTRN02C.cbl:L707-L711}, which
 * displayed one message, moved 999 into an abend code and ended the address space.
 *
 * <p>Nothing a refused record carried reaches the dead-letter topic. {@link SanitizingRecoverer} is
 * what makes that true, and it overrides record construction to make it true: the framework
 * recoverer republishes the refused key and the refused bytes, and where a deserializer refused the
 * record those bytes are the ones its schema rejected. One governed {@link DeadLetterEnvelope}
 * travels instead, carrying the four bounded components of {@code 01 ABEND-DATA} at
 * {@code app/cpy/CSMSG02Y.cpy:L21-L29} and the broker coordinates of the record, and the outgoing
 * key is those coordinates rather than the key a producer chose.
 *
 * <p>The route is the shared fallback topic rather than one named after the source topic. This
 * service already publishes a governed envelope there for an outbox row its relay gave up on, so one
 * topic carries every terminal diagnostic of this service and a reader binds one contract.
 * {@code card-platform/docs/event-flow.md} lists which services route which way.
 *
 * <p>Decisions: {@code card-platform/docs/decision-log.md}.
 */
@Configuration
public class KafkaConsumerConfig {

    /** Writes the diagnostic line one dead-lettered record produces. */
    private static final Logger LOG = LoggerFactory.getLogger(KafkaConsumerConfig.class);

    /** Deliveries of one record counted by {@link FixedBackOff}, which counts retries. */
    private static final long FIRST_DELIVERY = 1L;

    /** The abend code every diagnostic of this service carries, from {@code ABEND-CODE PIC X(4)}. */
    private static final String ABEND_CODE = "0999";

    /** The reason a dead-lettered posting carries, from {@code ABEND-REASON PIC X(50)}. */
    private static final String UNAPPLIED_REASON = "posted amount not applied";

    /** Header name of the abend code, from {@code ABEND-CODE} at {@code app/cpy/CSMSG02Y.cpy}. */
    private static final String HEADER_ABEND_CODE = "carddemo-abend-code";

    /** Header name of the culprit, from {@code ABEND-CULPRIT}. */
    private static final String HEADER_CULPRIT = "carddemo-abend-culprit";

    /** Header name of the reason, from {@code ABEND-REASON}. */
    private static final String HEADER_REASON = "carddemo-abend-reason";

    /**
     * The only header names {@link SanitizingRecoverer} carries onto the dead-letter topic.
     *
     * <p>Every one of the three is generated by {@link #diagnosticHeaders(Exception)} from the
     * failure's own type, so none holds a value a producer supplied. Anything else the inbound record
     * carried is dropped, including the framework's own exception headers, which quote the refused
     * document inside their text.
     */
    private static final List<String> ALLOWED_DEAD_LETTER_HEADERS =
            List.of(HEADER_ABEND_CODE, HEADER_CULPRIT, HEADER_REASON);

    /** The partition value that leaves the choice of partition to the broker. */
    private static final int BROKER_SELECTS_PARTITION = -1;

    /**
     * The largest attempt count {@code schemas/dead-letter-v1.json} accepts.
     *
     * <p>The container sets the delivery-attempt header, but the header name is not reserved, so a
     * producer can set it too. A value outside the contract range would fail the envelope's own
     * validation and leave the record with nowhere to go, which turns a value a producer chose into a
     * way of blocking a partition. The count is clamped into the range instead.
     */
    private static final int MAX_REPORTED_ATTEMPTS = 1_000;

    /**
     * The aggregate identifier a dead letter of this service declares.
     *
     * <p>{@code schemas/dead-letter-v1.json} requires an account-shaped value, and a record this
     * service could not read carries no account it can be trusted to name. Eleven zeros are not an
     * account this platform seeds or issues, so the sentinel states the absence rather than
     * attributing the failure to an account.
     */
    private static final String UNRESOLVED_ACCOUNT_KEY = "00000000000";

    /**
     * The message component every dead letter of this service carries.
     *
     * <p>Fixed text rather than the message of the failure. A failure raised while applying a posting
     * can quote the record it was raised for, and that record carries an amount and a masked card
     * number.
     */
    private static final String SAFE_FAILURE_MESSAGE =
            "record not applied; inspect broker coordinates";

    /**
     * Builds the template the dead-letter route sends through.
     *
     * <p>A separate template rather than the one {@code KafkaProducerConfig} builds, because that one
     * carries the account service's own events and their publish-side gate. This one carries a text
     * key and a byte value, and the bytes are the rendered {@link DeadLetterEnvelope} that
     * {@link SanitizingRecoverer} validates against {@code schemas/dead-letter-v1.json} before it
     * hands them over. The validation therefore still happens; it happens where the envelope is built.
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
        settings.put(ProducerConfig.ACKS_CONFIG, "all");
        settings.put(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, Boolean.TRUE);

        KafkaTemplate<String, byte[]> template =
                new KafkaTemplate<>(new DefaultKafkaProducerFactory<>(settings));
        // A failed send records its destination and failure type only.
        // SafeProducerListener displaces LoggingProducerListener, which would write the
        // key and the first hundred characters of the payload into the log line.
        template.setProducerListener(new SafeProducerListener<>());
        return template;
    }

    /**
     * Builds the delivery-attempt policy and the dead-letter route.
     *
     * <p>{@link FixedBackOff} counts retries, so the second argument is one less than
     * {@code carddemo.consumer.retry.max-attempts}. {@link DeserializationException} and
     * {@link SerializationException} are registered as not retryable.
     *
     * <p>An applied posting is never dead-lettered. Only a record this service cannot apply at all
     * reaches the route: a payload the schema refused, a key naming another account, or an account
     * this service holds no row for. Each of those is a real inconsistency, and the route is what
     * makes it visible instead of losing the amount in silence.
     *
     * @param deadLetterKafkaTemplate the byte-serializing template
     * @param deadLetterTopic         the topic every unconsumable record reaches
     * @param meters                  the recording surface the terminal outcome is counted against
     * @param maxAttempts             deliveries of one record, counting the first
     * @param backoffMs               milliseconds between two deliveries
     * @return the error handler the container factory installs
     * @throws IllegalStateException when the attempt count is under one or the wait is negative
     */
    @Bean
    @Lazy
    public DefaultErrorHandler postedTransactionErrorHandler(
            KafkaTemplate<String, byte[]> deadLetterKafkaTemplate,
            @Value("${carddemo.kafka.topics.dead-letter}") String deadLetterTopic,
            ObservabilityConfig.AccountMeters meters,
            @Value("${carddemo.consumer.retry.max-attempts}") int maxAttempts,
            @Value("${carddemo.consumer.retry.backoff-ms}") long backoffMs) {

        requireAtLeast(maxAttempts, FIRST_DELIVERY, "carddemo.consumer.retry.max-attempts");
        requireAtLeast(backoffMs, 0L, "carddemo.consumer.retry.backoff-ms");

        DeadLetterPublishingRecoverer route =
                new SanitizingRecoverer(deadLetterKafkaTemplate, deadLetterTopic);

        DefaultErrorHandler errorHandler =
                new DefaultErrorHandler(new CountingRecoverer(route, meters),
                        new FixedBackOff(backoffMs, maxAttempts - FIRST_DELIVERY));
        errorHandler.addNotRetryableExceptions(DeserializationException.class,
                SerializationException.class);
        errorHandler.setCommitRecovered(true);

        return errorHandler;
    }

    /**
     * Builds the container factory the listener of this service runs in.
     *
     * <p>{@code setCommitRecovered(true)} on the error handler commits the offset of a record the
     * route has published. Without it the offset of a dead-lettered record stayed uncommitted, so the
     * next start-up or partition assignment read the same record again and published a second
     * diagnostic for one set of coordinates.
     *
     * <p>That setting only takes effect under {@code MANUAL_IMMEDIATE}, which is also the one mode
     * that commits the offset at the acknowledgement this listener issues after its own writes
     * commit. The mode the configurer produced is therefore read back and checked: a deployment
     * naming {@code MANUAL} or an automatic mode would withdraw both guarantees in silence, so the
     * context stops rather than run without them.
     *
     * @param consumerFactory              the auto-configured consumer factory
     * @param configurer                   the auto-configured container-factory configurer
     * @param postedTransactionErrorHandler the delivery-attempt policy and the dead-letter route
     * @return the container factory the listener of this service runs in
     * @throws IllegalStateException when the effective acknowledgement mode is not
     *                               {@code MANUAL_IMMEDIATE}
     */
    @Bean
    @Lazy
    public ConcurrentKafkaListenerContainerFactory<Object, Object> kafkaListenerContainerFactory(
            ConsumerFactory<Object, Object> consumerFactory,
            ConcurrentKafkaListenerContainerFactoryConfigurer configurer,
            DefaultErrorHandler postedTransactionErrorHandler) {

        ConcurrentKafkaListenerContainerFactory<Object, Object> factory =
                new ConcurrentKafkaListenerContainerFactory<>();

        configurer.configure(factory, consumerFactory);
        factory.setCommonErrorHandler(postedTransactionErrorHandler);
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
     * Holds one bound number at or above a floor, naming the property when it is not.
     *
     * @param value    the bound value
     * @param floor    the smallest value that makes sense
     * @param property the property name the message reports
     * @throws IllegalStateException when the value is under the floor
     */
    private static void requireAtLeast(long value, long floor, String property) {
        if (value < floor) {
            throw new IllegalStateException(property + " must be at least " + floor + ", found "
                    + value);
        }
    }

    /**
     * Builds the three diagnostic headers one dead letter carries.
     *
     * <p>The components are the fixed-width fields of {@code 01 ABEND-DATA} at
     * {@code app/cpy/CSMSG02Y.cpy:L21-L29}, each already bounded to its declared width by
     * {@link DeadLetterMetadata}. No payload value and no message text of the failure is attached: the
     * record that failed carries an amount, a balance and a masked card number, and a dead-letter
     * topic is the last place any of the three belongs.
     *
     * <p>{@link SanitizingRecoverer} reads these three headers back to build the envelope it
     * publishes, so the header set and the envelope carry one set of components rather than two.
     *
     * @param failure the exception the container reported; may be {@code null}
     * @return the headers one dead letter carries
     */
    private static Headers diagnosticHeaders(Exception failure) {
        DeadLetterMetadata diagnostics =
                DeadLetterMetadata.fromFailure(ABEND_CODE, failure, UNAPPLIED_REASON, null);
        Headers headers = new RecordHeaders();

        headers.add(HEADER_ABEND_CODE, utf8(diagnostics.abendCode()));
        headers.add(HEADER_CULPRIT, utf8(diagnostics.culprit()));
        headers.add(HEADER_REASON, utf8(diagnostics.reason()));

        return headers;
    }

    /**
     * Renders one header value as UTF-8, and never in the charset of the host.
     *
     * @param value the header value, which may be {@code null}
     * @return the bytes, or {@code null}
     */
    private static byte[] utf8(String value) {
        return value == null ? null : value.getBytes(StandardCharsets.UTF_8);
    }

    /**
     * Publishes one bounded, governed dead letter per spent record, and never the record itself.
     *
     * <p>The class it extends resolves a destination and then republishes the refused key and the
     * refused value, and where a deserializer refused the record that value is
     * {@link DeserializationException#getData()}: the exact bytes a schema control rejected. Those
     * bytes are the reason the record failed, so they are the bytes most likely to hold a full
     * Primary Account Number a producer put in the wrong field, or an unbounded string. Republishing
     * them moves the value that failed a control past the control and onto a topic with a different
     * set of readers, and the refused key carries the same risk because a producer chooses it. This
     * class therefore overrides record construction and emits neither.
     *
     * <p>An instance holds no mutable state, so consumer threads may share one.
     */
    private static final class SanitizingRecoverer extends DeadLetterPublishingRecoverer {

        /** Renders one envelope as JSON text. Holds no configuration a payload can influence. */
        private static final ObjectMapper MAPPER = JsonMapper.builder().build();

        /**
         * Builds a recoverer that publishes to one fixed topic.
         *
         * @param template        the byte-serializing template
         * @param deadLetterTopic the topic every dead letter of this service reaches
         */
        private SanitizingRecoverer(KafkaTemplate<String, byte[]> template,
                String deadLetterTopic) {
            super(template, (failedRecord, failure) ->
                    new TopicPartition(deadLetterTopic, BROKER_SELECTS_PARTITION));
            excludeHeader(HeadersToAdd.EX_STACKTRACE, HeadersToAdd.EX_MSG);
            setHeadersFunction((failedRecord, failure) -> diagnosticHeaders(failure));
        }

        /**
         * Returns a record carrying the governed envelope, the refused record's coordinates as its
         * key, and the three headers this service generates.
         *
         * <p>{@code key} and {@code value} are the refused bytes the superclass supplies. Neither is
         * read. {@code headers} is not passed through either: {@code setHeadersFunction} adds to the
         * inbound set rather than replacing it, so what arrives here is every header the producer
         * chose plus the three this service attached, and copying the set forward would reintroduce
         * through a header what the key and the value no longer carry.
         */
        @Override
        protected ProducerRecord<Object, Object> createProducerRecord(
                ConsumerRecord<?, ?> failedRecord, TopicPartition topicPartition, Headers headers,
                byte[] key, byte[] value) {

            return new ProducerRecord<>(topicPartition.topic(), partitionOf(topicPartition),
                    recordCoordinates(failedRecord), governedEnvelope(failedRecord, headers),
                    allowedHeaders(headers));
        }

        /**
         * Rebuilds the outgoing header set from the three names this class generates.
         *
         * @param headers the inbound set, holding the producer's headers and this class's three
         * @return a set holding only the diagnostic headers that carry a value
         */
        private static Headers allowedHeaders(Headers headers) {
            Headers allowed = new RecordHeaders();
            for (String name : ALLOWED_DEAD_LETTER_HEADERS) {
                Header header = headers == null ? null : headers.lastHeader(name);
                if (header != null && header.value() != null) {
                    allowed.add(name, header.value());
                }
            }
            return allowed;
        }

        /**
         * Renders one validated envelope as UTF-8 bytes.
         *
         * @param failedRecord the record no attempt could apply
         * @param headers      the headers this class attached, which carry the four components
         * @return the serialized envelope
         * @throws IllegalStateException when the envelope breaks its own contract, which is a defect
         *                               in this class rather than anything the record carried
         */
        private static byte[] governedEnvelope(ConsumerRecord<?, ?> failedRecord, Headers headers) {
            DeadLetterEnvelope envelope = DeadLetterMetadata
                    .of(headerText(headers, HEADER_ABEND_CODE),
                            headerText(headers, HEADER_CULPRIT),
                            headerText(headers, HEADER_REASON),
                            SAFE_FAILURE_MESSAGE)
                    .toEnvelope(UNRESOLVED_ACCOUNT_KEY, failedRecord.topic(),
                            failedRecord.partition(), failedRecord.offset(), null, null,
                            attemptCountOf(failedRecord));

            String json = MAPPER.writeValueAsString(envelope);
            List<String> violations =
                    EventContracts.violationsOf(EventContracts.DEAD_LETTER, json);
            if (!violations.isEmpty()) {
                throw new IllegalStateException(
                        EventContracts.describeViolations(EventContracts.DEAD_LETTER, violations));
            }
            return json.getBytes(StandardCharsets.UTF_8);
        }
    }

    /**
     * Names the refused record inside its source topic, without retaining the key a producer chose.
     *
     * @param failedRecord the record no attempt could apply
     * @return the topic, partition and offset of the record, separated by hyphens
     */
    static String recordCoordinates(ConsumerRecord<?, ?> failedRecord) {
        return failedRecord.topic() + "-" + failedRecord.partition() + "-" + failedRecord.offset();
    }

    /**
     * The resolved partition, and {@code null} where the resolver named a negative one.
     *
     * @param topicPartition the destination the resolver named
     * @return the partition, or {@code null} to let the broker select one
     */
    private static Integer partitionOf(TopicPartition topicPartition) {
        int partition = topicPartition.partition();
        return partition < 0 ? null : partition;
    }

    /**
     * Reads how many delivery attempts one record took.
     *
     * <p>The container attaches the count because
     * {@code containerProperties.setDeliveryAttemptHeader(true)} is set on the factory this class
     * builds. A record that carries no such header took one attempt, which is what a record the
     * deserializer refused inside the poll does.
     *
     * @param failedRecord the record no attempt could apply
     * @return the attempt count, clamped into the range the contract accepts
     */
    static int attemptCountOf(ConsumerRecord<?, ?> failedRecord) {
        Header header = failedRecord.headers() == null
                ? null
                : failedRecord.headers().lastHeader(KafkaHeaders.DELIVERY_ATTEMPT);
        if (header == null || header.value() == null || header.value().length < Integer.BYTES) {
            return (int) FIRST_DELIVERY;
        }
        int reported = ByteBuffer.wrap(header.value()).getInt();
        return Math.clamp(reported, (int) FIRST_DELIVERY, MAX_REPORTED_ATTEMPTS);
    }

    /**
     * One header as UTF-8 text, or {@code null} where it is absent.
     *
     * @param headers the header set to read
     * @param name    the header name
     * @return the value as text, or {@code null}
     */
    private static String headerText(Headers headers, String name) {
        Header header = headers == null ? null : headers.lastHeader(name);
        return header == null || header.value() == null
                ? null
                : new String(header.value(), StandardCharsets.UTF_8);
    }

    /**
     * Counts and reports what the container is about to give up on, then hands the record over.
     *
     * <p>The container calls a recoverer only once the backoff is spent, so every record that arrives
     * here is one this service will not attempt again. That terminal outcome is counted once per
     * record and logged once at {@code ERROR}, which is the level an operator alerts on. A failed
     * attempt that a retry may still recover is logged at {@code WARN} by the listener instead, so the
     * two are never read as the same event.
     *
     * <p>A refused send is counted and rethrown, because the container has to know the record was not
     * recovered.
     */
    private static final class CountingRecoverer implements ConsumerRecordRecoverer {

        /** Publishes the governed envelope on the dead-letter topic. */
        private final ConsumerRecordRecoverer delegate;

        /** The recording surface both counts reach. */
        private final ObservabilityConfig.AccountMeters meters;

        /**
         * Wraps {@code delegate} and counts the terminal outcome of the record.
         *
         * @param delegate the recoverer that publishes the diagnostic
         * @param meters   the recording surface the outcome is counted against
         */
        private CountingRecoverer(ConsumerRecordRecoverer delegate,
                ObservabilityConfig.AccountMeters meters) {
            this.delegate = delegate;
            this.meters = meters;
        }

        @Override
        public void accept(ConsumerRecord<?, ?> failedRecord, Exception failure) {
            LOG.error("Routing one record to the dead-letter topic. topic={} partition={} offset={}"
                            + " attempts={} failure={}",
                    failedRecord.topic(), failedRecord.partition(), failedRecord.offset(),
                    attemptCountOf(failedRecord),
                    failure == null ? "none" : failure.getClass().getSimpleName());

            try {
                delegate.accept(failedRecord, failure);
            } catch (RuntimeException undelivered) {
                meters.recordDeadLetterFailure();
                throw undelivered;
            }
            meters.recordDeadLetterPublished();
        }
    }
}
