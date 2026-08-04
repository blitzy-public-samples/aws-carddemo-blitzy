package com.carddemo.card.config;

import java.util.LinkedHashMap;
import java.util.List;
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
 * Kafka producer wiring for the card service: one producer factory, one template, and the name of
 * the topic a card update travels on.
 *
 * <p>ADDITIVE. No COBOL program and no copybook declares an event bus. The nearest source
 * construct is the transient data queue write at {@code app/cbl/CORPT00C.cbl:L517-L518}. All eight
 * {@code DEFINE FILE} blocks of {@code app/csd/CARDDEMO.CSD} carry {@code RECOVERY(NONE)} and
 * {@code JOURNAL(NO)}.
 *
 * <p>Both type parameters are {@code String}. {@code messaging/KafkaEventPublisher} takes a
 * {@code KafkaTemplate<String, String>}, and the payload it sends is already-serialized
 * JavaScript Object Notation (JSON) text read from an {@code outbox_event} row.
 *
 * <p>Every producer setting comes from the {@code spring.kafka} block of
 * {@code src/main/resources/application.yml}. Four entries are pinned below: the broker address,
 * the security protocol this deployment resolved, and text serialization on the key and the value.
 * {@code messaging/KafkaEventPublisher} reads acknowledgement, idempotence, the in-flight ceiling
 * and the three timeouts back at construction, and stops start-up unless every one holds.
 *
 * <p>The message key is the eleven-digit account identifier of {@code XREF-ACCT-ID PIC 9(11)} at
 * {@code app/cpy/CVACT03Y.cpy:L7}, carried as text so a leading zero survives. A broker orders
 * messages inside one partition and the key selects the partition, so the events of one account
 * stay in order. A caller supplies the key and this class supplies the serializer.
 *
 * <p>This service consumes no event, so it registers no listener and joins no consumer group.
 * Neither bean below reaches the broker while the context builds, so this service starts with the
 * broker down and answers on {@code /actuator/health}.
 *
 * <p>Decisions: {@code card-platform/docs/decision-log.md} (planned).
 */
@Configuration
public class KafkaProducerConfig {

    /**
     * The topic a card update travels on, resolved from
     * {@code carddemo.kafka.topics.card-updated}.
     */
    private final String cardUpdatedTopic;

    /**
     * Reads the one topic name this service publishes on from the bound {@code carddemo} block.
     *
     * @param properties the bound configuration, which refuses a blank topic name at start-up
     */
    public KafkaProducerConfig(CardProperties properties) {
        this.cardUpdatedTopic = properties.kafka().topics().cardUpdated();
    }

    /**
     * Returns the topic name a caller passes to the publish port of the {@code messaging} package.
     * A card list and a card read publish nothing, so this is the only topic this service names.
     *
     * @return the resolved topic name, never blank
     */
    public String cardUpdatedTopic() {
        return cardUpdatedTopic;
    }

    /**
     * Builds the producer every card event travels through.
     *
     * <p>The settings start from the bound {@code spring.kafka} block. The broker address and the
     * security protocol then come from {@code connectionDetails}, so a deployment that resolves a
     * broker at run time reaches that broker. The two serializer entries are pinned last, which
     * holds them to the type parameters this bean declares.
     *
     * <p>Construction reaches no broker. A producer opens its connection on the first send.
     *
     * @param kafkaProperties   the bound {@code spring.kafka} block
     * @param connectionDetails the broker address and protocol this deployment resolved
     * @return the producer factory the template sends through
     * @throws IllegalStateException when this deployment resolved no broker address
     */
    @Bean
    public ProducerFactory<String, String> cardEventProducerFactory(
            KafkaProperties kafkaProperties, KafkaConnectionDetails connectionDetails) {

        Map<String, Object> settings =
                new LinkedHashMap<>(kafkaProperties.buildProducerProperties());

        KafkaConnectionDetails.Configuration producer = connectionDetails.getProducer();
        List<String> brokerAddresses = producer.getBootstrapServers();
        if (brokerAddresses == null || brokerAddresses.isEmpty()) {
            throw new IllegalStateException("spring.kafka.bootstrap-servers carries at least one "
                    + "broker address, and this deployment resolved none. "
                    + "card-platform/.env.example documents the variable that supplies it.");
        }
        settings.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, brokerAddresses);

        String securityProtocol = producer.getSecurityProtocol();
        if (securityProtocol != null) {
            settings.put(CommonClientConfigs.SECURITY_PROTOCOL_CONFIG, securityProtocol);
        }

        settings.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        settings.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class);

        return new DefaultKafkaProducerFactory<>(settings);
    }

    /**
     * Builds the template {@code messaging/KafkaEventPublisher} sends every card event through.
     *
     * @param producerFactory the producer factory above
     * @return the template, keyed and valued as text
     */
    @Bean
    public KafkaTemplate<String, String> cardEventKafkaTemplate(
            ProducerFactory<String, String> producerFactory) {
        return new KafkaTemplate<>(producerFactory);
    }
}
