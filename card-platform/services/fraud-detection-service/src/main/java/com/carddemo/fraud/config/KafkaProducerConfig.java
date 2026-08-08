package com.carddemo.fraud.config;

import com.carddemo.events.DeadLetterEnvelope;
import com.carddemo.events.FraudCleared;
import com.carddemo.events.FraudFlagged;
import com.carddemo.events.serde.EventContracts;
import com.carddemo.events.serde.JsonSchemaValidatingSerializer;

import java.util.LinkedHashMap;
import java.util.Map;

import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.serialization.StringSerializer;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.kafka.autoconfigure.KafkaProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.core.ProducerFactory;

/**
 * Kafka producer wiring for the fraud detection service: one producer factory and one template.
 *
 * <p>No COBOL ancestor. Searching {@code app/cbl/} for {@code fraud},
 * {@code velocit}, {@code risk} and {@code scoring} matches zero of its 28 programs.
 *
 * <p>Idempotent production is enabled and every record is acknowledged by all replicas. The value
 * serializer writes each event as JavaScript Object Notation (JSON). It then checks that JSON
 * against the schema document of its own event type, so a malformed event never reaches a topic.
 * Both serializers are instances this class constructs, and the settings map carries no serializer
 * entry of its own.
 *
 * <p>{@code FraudFlagged} and {@code FraudCleared} both travel on the topic
 * {@link #fraudAssessedTopic()} returns, and the envelope {@code eventType} separates them. The two
 * topic-override entries carry that resolved name to the serializer, which checks the destination
 * topic of every event it writes.
 *
 * <p>Every record is keyed on the eleven-digit account identifier the envelope carries as
 * {@code aggregateId}, in text, so a leading zero survives. Width from
 * {@code XREF-ACCT-ID PIC 9(11)} at {@code app/cpy/CVACT03Y.cpy:L7} (shape only, no logic). A
 * broker orders records inside one partition and the key selects the partition, so the events of
 * one account arrive in publish order.
 *
 * <p>Two beans, and the code that reads each one. {@code outbox/OutboxRelay.java} sends through
 * {@code @Qualifier("fraudEventKafkaTemplate")}. {@code config/KafkaConsumerConfig.java} declares a
 * second template for the dead-letter topic, and each injection point names the template it takes.
 *
 * <p>Three facts a reader needs. Every class in {@code com.carddemo.cobol} is final, holds static
 * members only and keeps a private constructor, so no bean method here returns one. This module
 * compiles at release 25 while the Spring Boot parent defaults to 17, and class-file major
 * version 69 is the proof. The wire form is flat: an event nested under an {@code envelope} key
 * fails every schema document this platform ships.
 */
@Configuration
public class KafkaProducerConfig {

    /** Bean name of the producer factory below, and the name the template asks for. */
    static final String PRODUCER_FACTORY_BEAN = "fraudEventProducerFactory";

    /** Acknowledgement from every in-sync replica, the setting all six services carry. */
    private static final String ACKS_FROM_ALL_REPLICAS = "all";

    /** Property naming the broker address, declared in {@code application.yml}. */
    private static final String BOOTSTRAP_SERVERS_PROPERTY = "spring.kafka.bootstrap-servers";

    /** Property naming the topic both assessment outcomes travel on. */
    private static final String FRAUD_ASSESSED_TOPIC_PROPERTY =
            "carddemo.kafka.topics.fraud-assessed";

    /** Property naming the topic a row this relay cannot publish travels to. */
    private static final String DEAD_LETTER_TOPIC_PROPERTY = "carddemo.kafka.topics.dead-letter";

    /** Opening characters of a placeholder no property source resolved. */
    private static final String UNRESOLVED_PLACEHOLDER = "${";

    /** The broker address every producer of this service connects to. */
    private final String bootstrapServers;

    /** The topic {@code FraudFlagged} and {@code FraudCleared} are published to. */
    private final String fraudAssessedTopic;

    /** The topic a {@code DeadLetterEnvelope} from the relay is published to. */
    private final String deadLetterTopic;

    /**
     * Reads the broker address and the two topic names, each from a property carrying its default.
     *
     * <p>An unset property leaves the default in place: {@code kafka:29092} for the address, which is
     * the internal listener the compose stack publishes, {@code fraud.assessed} for the assessment
     * topic and {@code carddemo.dead-letter} for the dead-letter topic. A blank or unresolved value
     * stops start-up with the property named. No failure message here holds a value read from
     * configuration.
     *
     * @param bootstrapServers   the broker address, from {@code spring.kafka.bootstrap-servers}
     * @param fraudAssessedTopic the topic name, from {@code carddemo.kafka.topics.fraud-assessed}
     * @param deadLetterTopic    the topic name, from {@code carddemo.kafka.topics.dead-letter}
     * @throws IllegalArgumentException when any property resolves to no usable value
     */
    public KafkaProducerConfig(
            @Value("${spring.kafka.bootstrap-servers:kafka:29092}") String bootstrapServers,
            @Value("${carddemo.kafka.topics.fraud-assessed:fraud.assessed}")
                    String fraudAssessedTopic,
            @Value("${carddemo.kafka.topics.dead-letter:carddemo.dead-letter}")
                    String deadLetterTopic) {
        this.bootstrapServers = resolved(bootstrapServers, BOOTSTRAP_SERVERS_PROPERTY);
        this.fraudAssessedTopic = resolved(fraudAssessedTopic, FRAUD_ASSESSED_TOPIC_PROPERTY);
        this.deadLetterTopic = resolved(deadLetterTopic, DEAD_LETTER_TOPIC_PROPERTY);
    }

    /**
     * Returns the topic name the relay passes to the template on every send.
     *
     * @return the resolved topic name, never blank
     */
    public String fraudAssessedTopic() {
        return fraudAssessedTopic;
    }

    /**
     * Builds the producer this service publishes every assessment through.
     *
     * <p>The settings start from the bound {@code spring.kafka} block, which carries the broker
     * authentication and the client timeouts the shipped file declares. Four settings are pinned
     * here: the broker address, {@code acks=all}, {@code enable.idempotence=true} and the topic
     * each event type is published to. The two serializer class entries are dropped, and the factory
     * holds the two serializer instances this method constructs.
     *
     * <p>Three event types travel through this one factory, so three topic overrides are registered.
     * The validating serializer refuses an event sent to a topic its type is not bound to, and it
     * knows only the default name until an override names the deployed one. The relay sends its
     * {@code DeadLetterEnvelope} through this same template, so a deployment that renames the
     * dead-letter topic and registered no override for it would have every dead letter refused —
     * leaving the row that could not be published unpublishable as well, retried by each sweep and
     * failing the same way for as long as the service runs.
     *
     * @param kafkaProperties the bound {@code spring.kafka} block
     * @return the producer factory the template sends through
     */
    @Bean(name = PRODUCER_FACTORY_BEAN)
    public ProducerFactory<String, Object> fraudEventProducerFactory(
            KafkaProperties kafkaProperties) {
        Map<String, Object> settings =
                new LinkedHashMap<>(kafkaProperties.buildProducerProperties());

        settings.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        settings.put(ProducerConfig.ACKS_CONFIG, ACKS_FROM_ALL_REPLICAS);
        settings.put(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, Boolean.TRUE);

        settings.remove(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG);
        settings.remove(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG);

        settings.put(JsonSchemaValidatingSerializer.TOPIC_OVERRIDE_PREFIX + FraudFlagged.EVENT_TYPE,
                fraudAssessedTopic);
        settings.put(JsonSchemaValidatingSerializer.TOPIC_OVERRIDE_PREFIX + FraudCleared.EVENT_TYPE,
                fraudAssessedTopic);
        settings.put(
                JsonSchemaValidatingSerializer.TOPIC_OVERRIDE_PREFIX + EventContracts.DEAD_LETTER,
                deadLetterTopic);

        return new DefaultKafkaProducerFactory<>(settings, new StringSerializer(),
                new JsonSchemaValidatingSerializer<>());
    }

    /**
     * Builds the template the relay sends through.
     *
     * <p>The value is one of the two event records of this service, and the key is the eleven-digit
     * account identifier as text. A send that names no topic reaches
     * {@link #fraudAssessedTopic()}.
     *
     * @param producerFactory the pinned producer factory, resolved by bean name
     * @return the template the relay injects as {@code fraudEventKafkaTemplate}
     */
    @Bean(name = "fraudEventKafkaTemplate")
    public KafkaTemplate<String, Object> fraudEventKafkaTemplate(
            @Qualifier(PRODUCER_FACTORY_BEAN) ProducerFactory<String, Object> producerFactory) {
        KafkaTemplate<String, Object> template = new KafkaTemplate<>(producerFactory);
        template.setDefaultTopic(fraudAssessedTopic);
        // A failed send records its destination and failure type only.
        // SafeProducerListener displaces LoggingProducerListener, which would write the
        // key and the first hundred characters of the payload into the log line.
        template.setProducerListener(new SafeProducerListener<>());
        return template;
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
}
