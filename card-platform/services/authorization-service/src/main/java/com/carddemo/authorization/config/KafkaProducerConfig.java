package com.carddemo.authorization.config;

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
 * Producer wiring for the authorization service: the producer factory and the template that carry
 * every published event to the broker.
 *
 * <p>ADDITIVE. No COBOL program declares an event bus. The nearest source construct is the
 * transient data queue write at {@code app/cbl/CORPT00C.cbl:L517-L518}.
 *
 * <p>Four settings are pinned in this class: text serialization on the key, text serialization on
 * the value, {@code acks=all} and {@code enable.idempotence=true}. Idempotent production is
 * ADDITIVE as well. The source detects no duplicate delivery anywhere, and a replayed feed drives
 * the transaction write at {@code app/cbl/CBTRN02C.cbl:L562-L579} into a duplicate-key condition
 * and on to its abend routine. All eight file definitions in {@code app/csd/CARDDEMO.CSD} carry
 * {@code RECOVERY(NONE)} and {@code JOURNAL(NO)}.
 *
 * <p>The message key is the eleven-digit account identifier of {@code XREF-ACCT-ID PIC 9(11)} at
 * {@code app/cpy/CVACT03Y.cpy:L7}. The key travels as text, so a leading zero survives. A broker
 * keeps message order inside one partition and the key selects the partition, so every event of one
 * account lands on one partition and stays in order.
 *
 * <p>Neither bean below reaches the broker while the context builds, and a producer connects on its
 * first send. A send that finds no broker fails, and the outbox row it came from stays unpublished
 * until a later sweep.
 *
 * <p>{@code messaging/KafkaEventPublisher} is the one component that takes the template, and a
 * different event bus needs one more implementation of {@code messaging/EventPublisherPort}.
 *
 * <p>Decisions: {@code card-platform/docs/decision-log.md}.
 */
@Configuration
public class KafkaProducerConfig {

    /** Acknowledgement from every replica, the setting all six services carry. */
    private static final String ACKS_FROM_ALL_REPLICAS = "all";

    /**
     * Builds the producer every published event travels through.
     *
     * <p>The settings start from the bound {@code spring.kafka} block, which carries the login
     * module and the send timeouts of {@code src/main/resources/application.yml}. The broker
     * address and the security protocol then come from {@code connectionDetails}, which is what a
     * deployment overrides. The four pinned settings follow, so a reader finds each of them here.
     *
     * <p>Both serializers take text. The payload reaches this producer already written, and
     * {@code messaging/KafkaEventPublisher} measures it against the schema document its event type
     * names before the send.
     *
     * @param kafkaProperties   the bound {@code spring.kafka} block
     * @param connectionDetails the broker address and security protocol this deployment resolved
     * @return the producer factory the template sends through
     */
    @Bean
    public ProducerFactory<String, String> authorizationEventProducerFactory(
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
     * Builds the template {@code messaging/KafkaEventPublisher} sends every event through.
     *
     * @param producerFactory the pinned producer factory
     * @return the template, keyed and valued as text
     */
    @Bean
    public KafkaTemplate<String, String> authorizationEventKafkaTemplate(
            ProducerFactory<String, String> producerFactory) {
        return new KafkaTemplate<>(producerFactory);
    }
}
