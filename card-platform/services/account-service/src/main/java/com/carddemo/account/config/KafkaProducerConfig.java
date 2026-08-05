package com.carddemo.account.config;

import com.carddemo.account.messaging.EventPublisherPort;
import com.carddemo.events.serde.EventContracts;
import com.carddemo.events.serde.EventJsonValidator;
import com.carddemo.events.serde.EventWireBounds;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.regex.Pattern;
import org.apache.kafka.clients.CommonClientConfigs;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.serialization.StringSerializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.kafka.autoconfigure.KafkaConnectionDetails;
import org.springframework.boot.kafka.autoconfigure.KafkaProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.KafkaException;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.core.ProducerFactory;
import tools.jackson.core.json.JsonFactory;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.cfg.DateTimeFeature;
import tools.jackson.databind.json.JsonMapper;

/**
 * Event-publishing wiring for the account service: the producer, the template, the one
 * {@link EventPublisherPort} bean and the mapper that reads a written event.
 *
 * <p>No COBOL program declares an event bus. The nearest source construct is the
 * transient data queue write at {@code app/cbl/CORPT00C.cbl:L517-L518}.
 *
 * <p>Every event carries the eleven-digit account identifier of
 * {@code XREF-ACCT-ID PIC 9(11)} at {@code app/cpy/CVACT03Y.cpy:L7} as its message key, in text, so
 * a leading zero survives. A broker orders messages inside one partition and the key selects the
 * partition, so the events of one account stay in order.
 *
 * <p>A consumer of {@code AccountStateChanged} subscribes to the topic this class resolves and
 * needs no change to this service.
 */
@Configuration
public class KafkaProducerConfig {

    /** Acknowledgement from every replica, the setting all six services carry. */
    private static final String ACKS_FROM_ALL_REPLICAS = "all";

    /** The topic an account update and a billing-cycle close travel on. */
    private final String accountStateChangedTopic;

    /** The topic a change to a cardholder field travels on. */
    private final String customerContextChangedTopic;

    /** Each event type this service publishes, mapped to the topic this deployment configures. */
    private final Map<String, String> configuredTopics;

    /** How long one publish waits for the broker before the attempt is reported as failed. */
    private final Duration publishTimeout;

    /**
     * Reads every topic name from the bound {@code carddemo} block.
     *
     * @param properties the bound configuration, which rejects a blank topic name at start-up
     */
    public KafkaProducerConfig(AccountProperties properties) {
        AccountProperties.Kafka.Topics topics = properties.kafka().topics();
        this.accountStateChangedTopic = topics.accountStateChanged();
        this.customerContextChangedTopic = topics.customerContextChanged();
        this.configuredTopics = Map.of(
                EventContracts.ACCOUNT_STATE_CHANGED, topics.accountStateChanged(),
                EventContracts.CUSTOMER_CONTEXT_CHANGED, topics.customerContextChanged(),
                EventContracts.DEAD_LETTER, topics.deadLetter());
        this.publishTimeout = properties.outbox().relay().publishTimeout();
    }

    /**
     * Returns the topic name the relay passes to
     * {@link EventPublisherPort#publish(String, String, String)}. The default is
     * {@code account.state-changed} and {@code TOPIC_ACCOUNT_STATE_CHANGED} overrides it.
     *
     * @return the resolved topic name, never blank
     */
    public String accountStateChangedTopic() {
        return accountStateChangedTopic;
    }

    /**
     * Returns the topic name a {@code CustomerContextChanged} row travels on. The default is
     * {@code customer.context-changed} and {@code TOPIC_CUSTOMER_CONTEXT_CHANGED} overrides it.
     *
     * @return the resolved topic name, never blank
     */
    public String customerContextChangedTopic() {
        return customerContextChangedTopic;
    }

    /**
     * Returns the topic bound to one event type, or {@code null} when this service publishes no
     * event of that type.
     *
     * @param eventType the {@code EventEnvelope.eventType} value of one stored row
     * @return the configured topic name, or {@code null}
     */
    public String topicFor(String eventType) {
        return configuredTopics.get(eventType);
    }

    /**
     * Builds the producer this service sends every event through.
     *
     * <p>The settings start from {@code spring.kafka}, then take the broker address and the
     * security protocol from {@code connectionDetails}. Four settings are pinned here: text
     * serialization on the key and on the value, {@code acks=all} and
     * {@code enable.idempotence=true}.
     *
     * @param kafkaProperties   the bound {@code spring.kafka} block
     * @param connectionDetails the broker address and protocol this deployment resolved
     * @return the producer factory the template sends through
     */
    @Bean
    public ProducerFactory<String, String> accountEventProducerFactory(
            KafkaProperties kafkaProperties, KafkaConnectionDetails connectionDetails) {
        Map<String, Object> settings =
                new LinkedHashMap<>(kafkaProperties.buildProducerProperties());

        KafkaConnectionDetails.Configuration producer = connectionDetails.getProducer();
        settings.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, producer.getBootstrapServers());
        String securityProtocol = producer.getSecurityProtocol();
        if (securityProtocol != null) {
            settings.put(CommonClientConfigs.SECURITY_PROTOCOL_CONFIG, securityProtocol);
        }

        settings.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        settings.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        settings.put(ProducerConfig.ACKS_CONFIG, ACKS_FROM_ALL_REPLICAS);
        settings.put(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, Boolean.TRUE);

        return new DefaultKafkaProducerFactory<>(settings);
    }

    /**
     * Builds the template the publisher sends through.
     *
     * @param producerFactory the pinned producer factory
     * @return the template, keyed and valued as text
     */
    @Bean
    public KafkaTemplate<String, String> accountEventKafkaTemplate(
            ProducerFactory<String, String> producerFactory) {
        return new KafkaTemplate<>(producerFactory);
    }

    /**
     * Builds the mapper this module reads and writes event text with.
     *
     * <p>The mapper parses inside {@link EventWireBounds#streamReadConstraints()} and writes a
     * moment as an ISO-8601 string. No setting here quotes an ordinary number, so
     * {@code schemaVersion} stays the integer {@code 1}. Each monetary property carries a quoted
     * decimal form of its own, from the three {@code S9(10)V99} pictures at
     * {@code app/cpy/CVACT01Y.cpy:L8}, {@code L13} and {@code L14}.
     *
     * <p>The framework keeps its own primary mapper for the web layer, so a caller of this one
     * names it: {@code @Qualifier("accountEventObjectMapper")}.
     *
     * @return the mapper, safe to share across threads
     */
    @Bean
    public ObjectMapper accountEventObjectMapper() {
        return JsonMapper.builder(JsonFactory.builder()
                        .streamReadConstraints(EventWireBounds.streamReadConstraints())
                        .build())
                .disable(DateTimeFeature.WRITE_DATES_AS_TIMESTAMPS)
                .build();
    }

    /**
     * Builds the one publisher of this module, and the only bean here that holds a broker type.
     *
     * @param kafkaTemplate the pinned template
     * @param meters        the registered instruments, which count a failed publish attempt
     * @return the publisher the relay calls
     */
    @Bean
    public EventPublisherPort accountEventPublisher(KafkaTemplate<String, String> kafkaTemplate,
            ObservabilityConfig.AccountMeters meters) {
        return new KafkaEventPublisher(kafkaTemplate, configuredTopics, meters, publishTimeout);
    }

    /**
     * Sends one written event to one topic, keyed on the account identifier, and runs the four
     * checks {@link EventPublisherPort} states before the send.
     *
     * <p>Every rejection message holds a JavaScript Object Notation (JSON) pointer, a broken
     * keyword, an event type, a topic name or a length. None holds a value read from the payload,
     * so no account identifier, customer name, Social Security number or government-issued
     * identifier reaches a log through a failed publish.
     */
    private static final class KafkaEventPublisher implements EventPublisherPort {

        private static final Logger log = LoggerFactory.getLogger(KafkaEventPublisher.class);

        /** Envelope property naming the event, and with it the schema document and the topic. */
        private static final String EVENT_TYPE = "eventType";

        /** Envelope property carrying the account identifier and the message key. */
        private static final String AGGREGATE_ID = "aggregateId";

        /** Payload property carrying the account identifier. */
        private static final String ACCOUNT_ID = "accountId";

        /** Compiled once from {@link EventPublisherPort#AGGREGATE_ID_PATTERN}. */
        private static final Pattern AGGREGATE_ID_MATCHER = Pattern.compile(AGGREGATE_ID_PATTERN);

        /** Sends every event to the broker. */
        private final KafkaTemplate<String, String> kafkaTemplate;

        /** Each event type this service publishes, mapped to its configured topic. */
        private final Map<String, String> configuredTopics;

        /** Counts a publish attempt that failed. */
        private final ObservabilityConfig.AccountMeters meters;

        /** The one publish-side gate, shared with every other event of this platform. */
        private final EventJsonValidator validator = EventJsonValidator.shared();

        /** The bound {@code carddemo.outbox.relay.publish-timeout} value. */
        private final Duration publishTimeout;

        /**
         * Takes the template, the configured topic per event type, the instruments and the wait.
         *
         * @param kafkaTemplate    the template that sends every event to the broker
         * @param configuredTopics each event type this service publishes, mapped to its topic
         * @param meters           the instruments that count a failed publish attempt
         * @param publishTimeout   how long one send waits for the broker
         */
        KafkaEventPublisher(KafkaTemplate<String, String> kafkaTemplate,
                Map<String, String> configuredTopics, ObservabilityConfig.AccountMeters meters,
                Duration publishTimeout) {
            this.kafkaTemplate = kafkaTemplate;
            this.configuredTopics = configuredTopics;
            this.meters = meters;
            this.publishTimeout = publishTimeout;
        }

        @Override
        public CompletionStage<Void> publish(String topic, String aggregateId, String payload) {
            try {
                if (topic == null || payload == null) {
                    throw new IllegalArgumentException("topic and payload are both required");
                }
                if (aggregateId == null || !AGGREGATE_ID_MATCHER.matcher(aggregateId).matches()) {
                    throw new IllegalArgumentException("aggregateId must match "
                            + AGGREGATE_ID_PATTERN + " and the supplied value "
                            + (aggregateId == null ? "is null"
                                    : "holds " + aggregateId.length() + " characters"));
                }

                JsonNode event = validator.validate(payload);
                String eventType = requireBoundToTopic(event, topic);
                requireSingleAccountIdentity(aggregateId, event);

                log.debug("Publishing account event {} to topic {}, payload length {}", eventType,
                        topic, payload.length());
                return kafkaTemplate.send(topic, aggregateId, payload)
                        .thenApply(result -> (Void) null)
                        .whenComplete((ignored, failure) -> {
                            if (failure != null) {
                                meters.recordPublishFailure();
                            }
                        });
            } catch (RuntimeException failure) {
                meters.recordPublishFailure();
                throw failure;
            }
        }

        /**
         * Sends one payload and waits no longer than {@link #publishTimeout} for the broker.
         *
         * <p>An unbounded wait holds this thread, and with it the relay sweep that called it, for as
         * long as the broker is unreachable. The bounded wait turns that into one thrown failure the
         * caller records against the row.
         *
         * <p>The thrown message names the topic and the bound and reads no field of the payload, so
         * a caller that logs it records no account identifier, customer name or Social Security
         * number.
         *
         * @param topic       the destination topic
         * @param aggregateId the message key
         * @param payload     the event text
         * @throws KafkaException when the broker does not acknowledge inside the bound, when the
         *         send fails, or when the waiting thread is interrupted
         */
        private void sendAndWait(String topic, String aggregateId, String payload) {
            try {
                kafkaTemplate.send(topic, aggregateId, payload)
                        .get(publishTimeout.toMillis(), TimeUnit.MILLISECONDS);
            } catch (TimeoutException lapsed) {
                throw new KafkaException("the broker did not acknowledge a send to topic " + topic
                        + " within " + publishTimeout, lapsed);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new KafkaException("the wait on a send to topic " + topic
                        + " was interrupted", interrupted);
            } catch (ExecutionException failed) {
                throw new KafkaException("a send to topic " + topic + " failed",
                        failed.getCause());
            }
        }

        /**
         * Returns the event type the envelope declares, once that type is bound to {@code topic}.
         * The type comes from the payload and never from a separate argument.
         *
         * @param event the validated event
         * @param topic the destination topic the caller read from configuration
         * @return the event type the envelope declares
         * @throws IllegalArgumentException when that type does not belong on {@code topic}
         */
        private String requireBoundToTopic(JsonNode event, String topic) {
            String eventType = event.path(EVENT_TYPE).asString("");
            if (!EventContracts.isBoundToTopic(eventType, topic,
                    configuredTopics.get(eventType))) {
                throw new IllegalArgumentException(eventType + " belongs on the topic "
                        + EventContracts.defaultTopicFor(eventType)
                        + " and the supplied topic reads " + topic);
            }
            return eventType;
        }

        /**
         * Checks that {@code key} and every account identifier the payload carries agree. No
         * message below names a value, and the account identifier is the value under check.
         *
         * @param key   the message key
         * @param event the validated event
         * @throws IllegalArgumentException when the identifiers differ
         */
        private static void requireSingleAccountIdentity(String key, JsonNode event) {
            if (!key.equals(event.path(AGGREGATE_ID).asString(""))) {
                throw new IllegalArgumentException("the Kafka message key and " + AGGREGATE_ID
                        + " carry one account identifier, and they differ");
            }
            JsonNode accountId = event.path(ACCOUNT_ID);
            if (!accountId.isMissingNode() && !key.equals(accountId.asString(""))) {
                throw new IllegalArgumentException("the Kafka message key and " + ACCOUNT_ID
                        + " carry one account identifier, and they differ");
            }
        }
    }
}
