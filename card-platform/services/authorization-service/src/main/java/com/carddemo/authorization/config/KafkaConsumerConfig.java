package com.carddemo.authorization.config;

import com.carddemo.authorization.messaging.DeadLetterMetadata;
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

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;

import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * The consumer side of the authorization service: two replica listeners, a bounded retry policy and
 * one dead-letter route.
 *
 * <p>ADDITIVE IN FULL. The source consumes nothing. {@code app/jcl/POSTTRAN.jcl} hands
 * {@code CBTRN02C} a sequential file and {@code app/cbl/CBTRN02C.cbl:L707-L711} answers any fault by
 * calling the abend service, which is the whole of its error policy. This configuration exists because
 * this service reads two streams to keep its replicas current, and a stream needs a policy for a record
 * it can never apply.
 *
 * <p>Both listeners run behind the one container factory this class builds.
 * {@link ConcurrentKafkaListenerContainerFactoryConfigurer} applies every {@code spring.kafka.listener}
 * and {@code spring.kafka.consumer} value, the manual acknowledgement mode and
 * {@code auto-startup} among them, so none of those is restated here. Restating one is how a
 * hand-built factory quietly stops honouring a setting the configuration file names. Each listener
 * overrides only the group identifier by placeholder, which is what lets two consumer groups run behind
 * one factory.
 *
 * <p>One of those settings is not merely honoured but required. The delivery guarantees this service
 * documents hold under {@code MANUAL_IMMEDIATE} and under no other acknowledgement mode, so
 * {@link #requireImmediateManualAcknowledgement(ContainerProperties.AckMode)} reads back the mode the
 * configurer produced and stops the context when a deployment moved it. Refusing to start is the
 * point: an overridden mode is otherwise accepted in silence and takes away both the
 * commit-after-writes contract and the terminal dead-letter route.
 *
 * <p>The payload arrives as a schema-checked tree. {@code libs/event-contracts} holds the document for
 * each of the two events and names no class to build, because each record belongs to the service that
 * owns the aggregate and no service module may depend on another. The deserializer therefore validates
 * and hands over the tree, and each listener reads it into a record of its own.
 *
 * <p>Nothing a refused record carried reaches the dead-letter topic. {@link SanitizingRecoverer} is
 * what makes that true, and it has to override record construction to make it true: the framework
 * recoverer republishes the refused key and the refused bytes, and where a deserializer refused the
 * record those bytes are the ones its schema rejected. One governed {@code DeadLetterEnvelope}
 * travels instead, carrying the four bounded components of {@code 01 ABEND-DATA} and the broker
 * coordinates of the record, and the outgoing key is those coordinates rather than the key a producer
 * chose.
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

    /** The reason a dead-lettered replica record carries, from {@code ABEND-REASON PIC X(50)}. */
    private static final String UNAPPLIED_REASON = "replica record not applied";

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
     * <p>Fixed text rather than the message of the failure. A broker or database failure can quote
     * the record it was raised for, and that record is the one whose bytes a control refused.
     */
    private static final String SAFE_FAILURE_MESSAGE =
            "record not applied; inspect broker coordinates";

    /**
     * Builds the template the dead-letter route sends through.
     *
     * <p>A separate template rather than the one {@code KafkaProducerConfig} builds, because that one
     * validates a domain event against the schema of its own event type and refuses a topic that type
     * is not bound to. This one carries a text key and a byte value, and the bytes are the rendered
     * {@code DeadLetterEnvelope} that {@link SanitizingRecoverer} validates against
     * {@code schemas/dead-letter-v1.json} before it hands them over. The validation therefore still
     * happens; it happens where the envelope is built rather than inside a serializer bound to one
     * topic.
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
     * {@link SerializationException} are registered as not retryable: a record whose bytes break the
     * schema will break it again on every attempt, so retrying it only delays the partition.
     *
     * <p>An applied record is never dead-lettered. Only a record this service cannot apply at all
     * reaches the route, and the count of those is a series of its own on the failure counter so a
     * per-attempt failure and a spent record are never read as the same number.
     *
     * <p>{@link SanitizingRecoverer} is what reaches the topic, and the reason it exists rather than
     * the framework class it extends is stated on that class: the inherited behaviour republishes the
     * refused key and the refused bytes, and those bytes are the ones a control refused.
     *
     * <p>{@link DefaultErrorHandler#setCommitRecovered(boolean)} makes the route terminal. Both
     * listeners of this service acknowledge by hand, so nothing acknowledges a record a listener
     * never accepted, and left at its default this setting committed no offset for a record the route
     * had already published. The next start-up or the next partition assignment then read that same
     * record again and published a second diagnostic for one set of broker coordinates, so the
     * dead-letter topic and the {@link ObservabilityConfig#REPLICA_STAGE} series grew with no new
     * input. The framework applies the setting under {@code MANUAL_IMMEDIATE} alone, which is why
     * {@link #kafkaListenerContainerFactory} refuses to start under any other mode.
     *
     * @param deadLetterKafkaTemplate the byte-serializing template
     * @param deadLetterTopic         the topic every unconsumable record reaches
     * @param meters                  registry the dead-letter counter registers with
     * @param maxAttempts             deliveries of one record, counting the first
     * @param backoffMs               milliseconds between two deliveries
     * @return the error handler the container factory installs
     * @throws IllegalStateException when the attempt count is under one or the wait is negative
     */
    @Bean
    @Lazy
    public DefaultErrorHandler replicaConsumerErrorHandler(
            KafkaTemplate<String, byte[]> deadLetterKafkaTemplate,
            @Value("${carddemo.kafka.topics.dead-letter}") String deadLetterTopic,
            MeterRegistry meters,
            @Value("${carddemo.consumer.retry.max-attempts}") int maxAttempts,
            @Value("${carddemo.consumer.retry.backoff-ms}") long backoffMs) {

        requireAtLeast(maxAttempts, FIRST_DELIVERY, "carddemo.consumer.retry.max-attempts");
        requireAtLeast(backoffMs, 0L, "carddemo.consumer.retry.backoff-ms");

        Counter deadLettered = Counter.builder(ObservabilityConfig.FAILURES_COUNTER)
                .tag(ObservabilityConfig.STAGE_TAG, ObservabilityConfig.REPLICA_STAGE)
                .register(meters);

        DeadLetterPublishingRecoverer route =
                new SanitizingRecoverer(deadLetterKafkaTemplate, deadLetterTopic);

        DefaultErrorHandler errorHandler =
                new DefaultErrorHandler(new CountingRecoverer(route, deadLettered),
                        new FixedBackOff(backoffMs, maxAttempts - FIRST_DELIVERY));
        errorHandler.addNotRetryableExceptions(DeserializationException.class,
                SerializationException.class);
        errorHandler.setCommitRecovered(true);

        return errorHandler;
    }

    /**
     * Builds the container factory both replica listeners run in.
     *
     * <p>The acknowledgement mode still arrives from
     * {@code spring.kafka.listener.ack-mode} through the configurer rather than being restated here,
     * and the mode it produced is then checked. Only {@code MANUAL_IMMEDIATE} commits the offset at
     * the acknowledgement a listener issues after its own writes commit, and only that mode applies
     * {@link DefaultErrorHandler#setCommitRecovered(boolean)}. Under {@code MANUAL} the framework
     * reports the recovered-offset setting as ignored and commits nothing, and under an automatic mode
     * the container commits an offset for work a listener has not finished. A deployment that names
     * either therefore takes away a guarantee this service claims, silently, which is why the check
     * below stops the context instead.
     *
     * @param consumerFactory              the auto-configured consumer factory
     * @param configurer                   the auto-configured container-factory configurer
     * @param replicaConsumerErrorHandler  the delivery-attempt policy and the dead-letter route
     * @return the container factory every listener of this service runs in
     * @throws IllegalStateException when the effective acknowledgement mode is not
     *                               {@code MANUAL_IMMEDIATE}
     */
    @Bean
    @Lazy
    public ConcurrentKafkaListenerContainerFactory<Object, Object> kafkaListenerContainerFactory(
            ConsumerFactory<Object, Object> consumerFactory,
            ConcurrentKafkaListenerContainerFactoryConfigurer configurer,
            DefaultErrorHandler replicaConsumerErrorHandler) {

        ConcurrentKafkaListenerContainerFactory<Object, Object> factory =
                new ConcurrentKafkaListenerContainerFactory<>();

        configurer.configure(factory, consumerFactory);
        factory.setCommonErrorHandler(replicaConsumerErrorHandler);
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
     * record that failed may hold a credit limit or a pair of cycle balances, and a dead-letter topic
     * is the last place either belongs.
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
     * Primary Account Number a producer put in the wrong field, a card verification value no
     * document declares, or an unbounded string. Republishing them moves the value that failed a
     * control past the control and onto a topic with a different set of readers, and the refused key
     * carries the same risk because a producer chooses it. This class therefore overrides record
     * construction and emits neither.
     *
     * <p>What it emits instead is one {@link DeadLetterEnvelope}, the governed contract
     * {@code schemas/dead-letter-v1.json} describes, rendered as JavaScript Object Notation (JSON)
     * text. Every component of it is bounded: the four fields of {@code 01 ABEND-DATA} at
     * {@code app/cpy/CSMSG02Y.cpy:L21-L29} at their declared widths of four, eight, fifty and
     * seventy-two characters, plus the broker coordinates of the refused record and the count of
     * delivery attempts it took. The envelope is validated against its own document before it is
     * published, so a malformed diagnostic fails here rather than on a topic.
     *
     * <p>The outgoing key is {@value #UNRESOLVED_ACCOUNT_KEY}, the aggregate identifier the envelope
     * itself declares. {@code schemas/dead-letter-v1.json} describes {@code aggregateId} as the Kafka
     * message key of the envelope, so keying on anything else makes the payload contradict the record
     * carrying it. It also splits the shared dead-letter topic across as many partitions as there are
     * distinct source offsets, which loses the ordering the topic's single key form gives an operator
     * replaying it. Where the record came from is not lost by this: {@code sourceTopic},
     * {@code sourcePartition} and {@code sourceOffset} are declared fields of that document and are
     * filled in below, which is where an operator reads them.
     *
     * <p>The contract requires an account-shaped value and a record this service could not read
     * carries no account it can be trusted to name, so the sentinel says exactly that: eleven zeros
     * are not an account this platform seeds or issues. No producer controls it, which is the property
     * the coordinates were reached for.
     *
     * <p>The outgoing headers are rebuilt from the three this service generates, so a header a
     * producer chose is dropped along with the key that carried it.
     *
     * <p>An instance holds no mutable state, so consumer threads may share one.
     */
    private static final class SanitizingRecoverer extends DeadLetterPublishingRecoverer {

        /** Renders one envelope as JSON text. Holds no configuration a payload can influence. */
        private static final ObjectMapper MAPPER = JsonMapper.builder().build();

        /**
         * Builds a recoverer that publishes to one fixed topic.
         *
         * @param template       the byte-serializing template
         * @param deadLetterTopic the topic the envelope is bound to, which is the topic every
         *                        dead letter of this service reaches
         */
        private SanitizingRecoverer(KafkaTemplate<String, byte[]> template,
                String deadLetterTopic) {
            super(template, (failedRecord, failure) ->
                    new TopicPartition(deadLetterTopic, BROKER_SELECTS_PARTITION));
            excludeHeader(HeadersToAdd.EX_STACKTRACE, HeadersToAdd.EX_MSG);
            setHeadersFunction((failedRecord, failure) -> diagnosticHeaders(failure));
        }

        /**
         * Returns a record carrying the governed envelope, the envelope's own aggregate identifier as
         * its key, and the three headers this service generates.
         *
         * <p>{@code key} and {@code value} are the refused bytes the superclass supplies. Neither is
         * read.
         *
         * <p>{@code headers} is not passed through either, and that is the less obvious half of the
         * sanitization. {@code setHeadersFunction} adds to the inbound header set rather than
         * replacing it, so what arrives here is every header the producer chose plus the three this
         * service attached. A header carries a value a producer controls exactly as a key does, so
         * copying the set forward would reintroduce through a header what the key and the value no
         * longer carry. {@link #allowedHeaders(Headers)} rebuilds the set from the three names this
         * class owns.
         */
        @Override
        protected ProducerRecord<Object, Object> createProducerRecord(
                ConsumerRecord<?, ?> failedRecord, TopicPartition topicPartition, Headers headers,
                byte[] key, byte[] value) {

            return new ProducerRecord<>(topicPartition.topic(), partitionOf(topicPartition),
                    UNRESOLVED_ACCOUNT_KEY, governedEnvelope(failedRecord, headers),
                    allowedHeaders(headers));
        }

        /**
         * Rebuilds the outgoing header set from the three names this class generates.
         *
         * <p>An allowlist rather than a blocklist. A blocklist has to enumerate what a producer might
         * send, which is unbounded; this enumerates what this service sends, which is three names.
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

    /** The resolved partition, and {@code null} where the resolver named a negative one. */
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
     * @return the attempt count, never under one
     */
    static int attemptCountOf(ConsumerRecord<?, ?> failedRecord) {
        Header attempt = failedRecord.headers().lastHeader(KafkaHeaders.DELIVERY_ATTEMPT);

        if (attempt == null || attempt.value() == null
                || attempt.value().length != Integer.BYTES) {
            return (int) FIRST_DELIVERY;
        }
        int counted = ByteBuffer.wrap(attempt.value()).getInt();
        return Math.clamp(counted, (int) FIRST_DELIVERY, MAX_REPORTED_ATTEMPTS);
    }

    /**
     * Reads one header as text, and {@code null} where the header carries no value.
     *
     * @param headers the headers this class attached
     * @param name    the header to read
     * @return the value as text, or {@code null}
     */
    private static String headerText(Headers headers, String name) {
        Header header = headers == null ? null : headers.lastHeader(name);
        return header == null || header.value() == null
                ? null : new String(header.value(), StandardCharsets.UTF_8);
    }

    /**
     * Counts one spent record, reports it once, then hands it to the dead-letter route.
     *
     * <p>The unit is one record and not one attempt. The container reaches this recoverer once, after
     * the delivery attempts of a record have all failed, so the count answers how many records this
     * service could never apply. A per-attempt count would answer a different question and the two
     * must not be read as one number.
     */
    private static final class CountingRecoverer implements ConsumerRecordRecoverer {

        /** The route this recoverer hands a spent record to. */
        private final ConsumerRecordRecoverer route;

        /** Counts one record whose delivery attempts ran out. */
        private final Counter deadLettered;

        /**
         * Takes the route and the counter.
         *
         * @param route        the dead-letter route
         * @param deadLettered counts one spent record
         */
        private CountingRecoverer(ConsumerRecordRecoverer route, Counter deadLettered) {
            this.route = route;
            this.deadLettered = deadLettered;
        }

        @Override
        public void accept(ConsumerRecord<?, ?> record, Exception failure) {
            DeadLetterMetadata diagnostics =
                    DeadLetterMetadata.fromFailure(ABEND_CODE, failure, UNAPPLIED_REASON, null);

            LOG.error("Routing one record to the dead-letter topic. topic={} partition={} offset={}"
                            + " code={} culprit={} reason={}", record.topic(), record.partition(),
                    record.offset(), diagnostics.abendCode(), diagnostics.culprit(),
                    diagnostics.reason());

            // Counted after the publication, for the reason the ledger, fraud and notification
            // recoverers count after theirs: an increment ahead of the call counts a diagnostic the
            // broker may refuse, and the series then reports more records than the dead-letter
            // topic holds. A refusal propagates from this call as before, and the ERROR line above
            // is emitted either way.
            route.accept(record, failure);
            deadLettered.increment();
        }
    }
}
