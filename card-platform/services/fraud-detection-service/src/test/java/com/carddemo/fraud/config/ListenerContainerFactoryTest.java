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

    @Test
    @DisplayName("It applies the bound listener concurrency rather than discarding it")
    void itAppliesTheBoundListenerConcurrency() {
        assertThat(containerFrom(factoryFor(true, 2)).getConcurrency())
                .as("the shipped file declares spring.kafka.listener.concurrency, and a factory that "
                        + "read the key and did nothing with it left the declaration inert: the "
                        + "container is where the value takes effect")
                .isEqualTo(2);
    }

    @Test
    @DisplayName("It keeps one consumer thread at the shipped value")
    void itKeepsOneConsumerThreadAtTheShippedValue() {
        assertThat(containerFrom(factoryFor(true, 1)).getConcurrency())
                .as("the shipped value is one, so shipped behaviour is unchanged")
                .isEqualTo(1);
    }

    /** Creates one container from {@code factory}, which is where the setting takes effect. */
    private static ConcurrentMessageListenerContainer<String, TransactionAuthorized> containerFrom(
            ConcurrentKafkaListenerContainerFactory<String, TransactionAuthorized> factory) {
        return factory.createContainer(TOPIC);
    }

    /** Builds the shipped factory with {@code autoStartup} bound on the listener block. */
    private static ConcurrentKafkaListenerContainerFactory<String, TransactionAuthorized> factoryFor(
            boolean autoStartup) {
        return factoryFor(autoStartup, null);
    }

    /**
     * Builds the shipped factory with {@code autoStartup} and {@code concurrency} bound on the
     * listener block.
     *
     * @param autoStartup the value bound to {@code spring.kafka.listener.auto-startup}
     * @param concurrency the value bound to {@code spring.kafka.listener.concurrency}, or
     *                    {@code null} for an unset key
     * @return the factory the shipped configuration produces
     */
    @SuppressWarnings("unchecked")
    private static ConcurrentKafkaListenerContainerFactory<String, TransactionAuthorized> factoryFor(
            boolean autoStartup, Integer concurrency) {
        KafkaProperties properties = new KafkaProperties();
        properties.getListener().setAutoStartup(autoStartup);
        properties.getListener().setConcurrency(concurrency);

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
