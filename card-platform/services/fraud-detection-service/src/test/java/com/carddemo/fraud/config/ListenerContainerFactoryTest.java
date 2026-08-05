package com.carddemo.fraud.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.carddemo.events.TransactionAuthorized;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.kafka.autoconfigure.KafkaProperties;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.ConcurrentMessageListenerContainer;
import org.springframework.kafka.listener.ContainerProperties;

/**
 * Holds the hand-built listener container factory to the container settings it has to apply.
 *
 * <p>A factory built by hand replaces the auto-configured one, so nothing applies the bound
 * {@code spring.kafka.listener} block on its behalf. {@code auto-startup} is the setting where that
 * matters most: a factory that ignores it starts a container whatever the property says, so a test or
 * a deployment that sets it to false gets a real consumer anyway and no indication that the setting
 * was discarded.
 */
@DisplayName("The listener container factory applies the bound listener settings")
class ListenerContainerFactoryTest {

    /** The topic this service consumes. */
    private static final String TOPIC = "transaction.authorized";

    /** Suffix the per-source-topic dead-letter name carries, the value the shipped file binds. */
    private static final String DEAD_LETTER_SUFFIX = ".DLT";

    /** The dead-letter topic the recoverer publishes to. */
    private static final String DEAD_LETTER_TOPIC = "carddemo.dead-letter";

    @Test
    @DisplayName("It starts its container where auto-startup is true, which is the default")
    void itStartsWhereAutoStartupIsTrue() {
        assertThat(containerFrom(factoryFor(true)).isAutoStartup()).isTrue();
    }

    @Test
    @DisplayName("It does not start its container where auto-startup is false")
    void itDoesNotStartWhereAutoStartupIsFalse() {
        assertThat(containerFrom(factoryFor(false)).isAutoStartup())
                .as("a context has to be loadable without a broker, and this is the property that "
                        + "makes it so")
                .isFalse();
    }

    @Test
    @DisplayName("It acknowledges by hand and immediately, and reads one record per invocation")
    void itAcknowledgesByHandAndReadsOneRecord() {
        ConcurrentKafkaListenerContainerFactory<String, TransactionAuthorized> factory =
                factoryFor(true);

        assertThat(factory.getContainerProperties().getAckMode())
                .isEqualTo(ContainerProperties.AckMode.MANUAL_IMMEDIATE);
        assertThat(factory.isBatchListener())
                .as("one record per invocation, so one event maps to one transaction")
                .isFalse();
    }

    /** Creates one container from {@code factory}, which is where the setting takes effect. */
    private static ConcurrentMessageListenerContainer<String, TransactionAuthorized> containerFrom(
            ConcurrentKafkaListenerContainerFactory<String, TransactionAuthorized> factory) {
        return factory.createContainer(TOPIC);
    }

    /** Builds the shipped factory with {@code autoStartup} bound on the listener block. */
    @SuppressWarnings("unchecked")
    private static ConcurrentKafkaListenerContainerFactory<String, TransactionAuthorized> factoryFor(
            boolean autoStartup) {
        KafkaProperties properties = new KafkaProperties();
        properties.getListener().setAutoStartup(autoStartup);

        KafkaConsumerConfig config = new KafkaConsumerConfig("kafka:29092", TOPIC,
                "fraud-detection", DEAD_LETTER_TOPIC, DEAD_LETTER_SUFFIX, 3L, 1000L);
        ConsumerFactory<String, TransactionAuthorized> consumerFactory =
                config.transactionAuthorizedConsumerFactory(properties);

        assertThat(consumerFactory.getConfigurationProperties())
                .as("the pinned settings the factory installs travel with it")
                .containsEntry(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, Boolean.FALSE);

        return config.transactionAuthorizedListenerContainerFactory(consumerFactory,
                config.transactionAuthorizedErrorHandler(mock(KafkaTemplate.class),
                        new ObservabilityConfig().fraudMeters(new SimpleMeterRegistry())),
                properties);
    }
}
