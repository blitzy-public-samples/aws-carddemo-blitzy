package com.carddemo.ledger.config;

import com.carddemo.events.serde.EventContracts;
import com.carddemo.events.serde.JsonSchemaValidatingSerializer;
import java.util.LinkedHashMap;
import java.util.Map;
import org.apache.kafka.clients.CommonClientConfigs;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.serialization.StringSerializer;
import org.springframework.boot.kafka.autoconfigure.KafkaConnectionDetails;
import org.springframework.boot.kafka.autoconfigure.KafkaProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.core.ProducerFactory;

/**
 * Producer wiring for the ledger posting service: the producer factory and the {@link KafkaTemplate}
 * the outbox relay publishes every ledger event through.
 *
 * <p>No COBOL program here declares an event bus, and none detects a duplicate, so a
 * replayed feed reaches the abend routine at {@code app/cbl/CBTRN02C.cbl:L562-L579}.
 *
 * <p>Every message is keyed on the eleven-digit account identifier of
 * {@code XREF-ACCT-ID PIC 9(11)} at {@code app/cpy/CVACT03Y.cpy:L7}, in text, so a leading zero
 * survives. {@code app/cbl/CBTRN02C.cbl:L469} moves that value onto the category-balance key. A
 * broker orders messages within one partition and the key selects the partition, so the balance
 * updates of one account keep their written order.
 *
 * <p>{@code config/LedgerProperties} resolves every topic name, and no Kafka transaction is
 * configured here.
 */
@Configuration
public class KafkaProducerConfig {

    /** Acknowledgement from every in-sync replica, the setting all six services carry. */
    private static final String ACKS_FROM_ALL_REPLICAS = "all";

    /**
     * Builds the producer every ledger event travels through.
     *
     * <p>The settings start from the bound {@code spring.kafka} block, so the login and protocol a
     * deployment configures carry through, then take the broker address {@code connectionDetails}
     * resolved. Four settings are pinned here: text serialization on the key, schema-validating
     * serialization on the value, {@code acks=all} and {@code enable.idempotence=true}. Each topic
     * name {@code LedgerProperties} holds is passed to the value serializer, which accepts a
     * renamed topic only when the producer names it.
     *
     * <p>Neither this method nor the factory it returns contacts the broker, so the application
     * context starts while the broker is unreachable.
     *
     * @param kafkaProperties   the bound {@code spring.kafka} block
     * @param connectionDetails the broker address and security protocol this deployment resolved
     * @param ledgerProperties  the bound {@code carddemo} block, holding every topic name
     * @return the producer factory the template sends through
     * @throws IllegalStateException when the assembled settings carry no in-flight request limit
     */
    @Bean
    public ProducerFactory<String, Object> ledgerEventProducerFactory(
            KafkaProperties kafkaProperties, KafkaConnectionDetails connectionDetails,
            LedgerProperties ledgerProperties) {

        Map<String, Object> settings =
                new LinkedHashMap<>(kafkaProperties.buildProducerProperties());

        KafkaConnectionDetails.Configuration producer = connectionDetails.getProducer();
        settings.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, producer.getBootstrapServers());
        String securityProtocol = producer.getSecurityProtocol();
        if (securityProtocol != null) {
            settings.put(CommonClientConfigs.SECURITY_PROTOCOL_CONFIG, securityProtocol);
        }

        settings.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        settings.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG,
                JsonSchemaValidatingSerializer.class);
        settings.put(ProducerConfig.ACKS_CONFIG, ACKS_FROM_ALL_REPLICAS);
        settings.put(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, Boolean.TRUE);
        requireConfigured(settings, ProducerConfig.MAX_IN_FLIGHT_REQUESTS_PER_CONNECTION);

        publishedTopicsByEventType(ledgerProperties).forEach((eventType, topic) -> settings.put(
                JsonSchemaValidatingSerializer.TOPIC_OVERRIDE_PREFIX + eventType, topic));

        return new DefaultKafkaProducerFactory<>(settings);
    }

    /**
     * Builds the template the outbox relay publishes through.
     *
     * <p>The value type is {@code Object}, so one template carries each event record this service
     * publishes. {@link JsonSchemaValidatingSerializer} refuses a class that names no registered
     * event type, so a value that is not an event never reaches a topic.
     *
     * @param ledgerEventProducerFactory the pinned producer factory
     * @return the template, keyed on the account identifier as text
     */
    @Bean
    public KafkaTemplate<String, Object> ledgerEventKafkaTemplate(
            ProducerFactory<String, Object> ledgerEventProducerFactory) {
        KafkaTemplate<String, Object> template = new KafkaTemplate<>(ledgerEventProducerFactory);
        // A failed send records its destination and failure type only.
        // SafeProducerListener displaces LoggingProducerListener, which would write the
        // key and the first hundred characters of the payload into the log line.
        template.setProducerListener(new SafeProducerListener<>());
        return template;
    }

    /**
     * Maps each event type this service publishes to the topic {@code LedgerProperties} resolved.
     *
     * <p>{@code domain/PostingService} publishes the posted event and {@code domain/RejectRecorder}
     * publishes the declined event. The third entry names the dead-letter topic a record reaches
     * once its delivery attempts run out.
     *
     * @param ledgerProperties the bound {@code carddemo} block
     * @return one entry per published event type, each topic name non-blank
     */
    private static Map<String, String> publishedTopicsByEventType(
            LedgerProperties ledgerProperties) {
        LedgerProperties.Kafka.Topics topics = ledgerProperties.kafka().topics();

        return Map.of(
                EventContracts.TRANSACTION_POSTED, topics.transactionPosted(),
                EventContracts.TRANSACTION_DECLINED, topics.transactionDeclined(),
                EventContracts.DEAD_LETTER, topics.deadLetter());
    }

    /**
     * Stops start-up when a producer property this service depends on carries no value.
     *
     * <p>The message names the property and the block that declares it, and quotes no value, so no
     * credential reaches a log.
     *
     * @param settings the assembled producer settings
     * @param property the property key that must be present
     * @throws IllegalStateException when {@code property} is absent or blank
     */
    private static void requireConfigured(Map<String, Object> settings, String property) {
        Object value = settings.get(property);

        if (value == null || String.valueOf(value).isBlank()) {
            throw new IllegalStateException("Producer property \"" + property
                    + "\" carries no value. Declare it under spring.kafka.producer.properties of"
                    + " the application.yml this service reads.");
        }
    }
}
