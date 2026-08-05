package com.carddemo.authorization.config;

import com.carddemo.authorization.messaging.DeadLetterMetadata;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

import org.apache.kafka.clients.CommonClientConfigs;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.errors.SerializationException;
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
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer.HeaderNames.HeadersToAdd;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.kafka.support.serializer.DeserializationException;
import org.springframework.util.backoff.FixedBackOff;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;

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
 * <p>The payload arrives as a schema-checked tree. {@code libs/event-contracts} holds the document for
 * each of the two events and names no class to build, because each record belongs to the service that
 * owns the aggregate and no service module may depend on another. The deserializer therefore validates
 * and hands over the tree, and each listener reads it into a record of its own.
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

    /** Partition value that leaves the choice of partition to the broker. */
    private static final int BROKER_SELECTS_PARTITION = -1;

    /**
     * Builds the template the dead-letter route sends through.
     *
     * <p>A separate template rather than the one {@code KafkaProducerConfig} builds, because that one
     * serializes a domain event against its schema and a dead letter is not a domain event. This one
     * carries a string key and a string value, which is the rendered envelope.
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

        return new KafkaTemplate<>(new DefaultKafkaProducerFactory<>(settings));
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
     * @param deadLetterKafkaTemplate the string-serializing template
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
                new DeadLetterPublishingRecoverer(deadLetterKafkaTemplate,
                        (failedRecord, failure) ->
                                new TopicPartition(deadLetterTopic, BROKER_SELECTS_PARTITION));
        route.excludeHeader(HeadersToAdd.EX_STACKTRACE);
        route.setHeadersFunction((failedRecord, failure) -> diagnosticHeaders(failure));

        DefaultErrorHandler errorHandler =
                new DefaultErrorHandler(new CountingRecoverer(route, deadLettered),
                        new FixedBackOff(backoffMs, maxAttempts - FIRST_DELIVERY));
        errorHandler.addNotRetryableExceptions(DeserializationException.class,
                SerializationException.class);

        return errorHandler;
    }

    /**
     * Builds the container factory both replica listeners run in.
     *
     * @param consumerFactory              the auto-configured consumer factory
     * @param configurer                   the auto-configured container-factory configurer
     * @param replicaConsumerErrorHandler  the delivery-attempt policy and the dead-letter route
     * @return the container factory every listener of this service runs in
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

        return factory;
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

            deadLettered.increment();
            LOG.error("Routing one record to the dead-letter topic. topic={} partition={} offset={}"
                            + " code={} culprit={} reason={}", record.topic(), record.partition(),
                    record.offset(), diagnostics.abendCode(), diagnostics.culprit(),
                    diagnostics.reason());

            route.accept(record, failure);
        }
    }
}
