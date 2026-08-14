package com.carddemo.authorization.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.OffsetAndMetadata;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.apache.kafka.common.TopicPartition;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.boot.kafka.autoconfigure.ConcurrentKafkaListenerContainerFactoryConfigurer;
import org.springframework.boot.kafka.autoconfigure.KafkaProperties;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.ContainerProperties;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.kafka.listener.MessageListenerContainer;
import org.springframework.kafka.support.SendResult;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * Asserts the two acknowledgement guarantees the replica listeners of this service rest on.
 *
 * <p>The first is that the dead-letter route is terminal. Both listeners acknowledge by hand, so
 * nothing acknowledges a record a listener never accepted, and the offset of a record the route has
 * published is committed by {@link DefaultErrorHandler#setCommitRecovered(boolean)} alone. Left at
 * its default that setting committed nothing, so the next start-up or the next partition assignment
 * read the same refused record again and published a second diagnostic for one set of broker
 * coordinates.
 *
 * <p>The second is that the container acknowledges immediately. The framework applies the recovered
 * offset commit under {@link ContainerProperties.AckMode#MANUAL_IMMEDIATE} and reports it as ignored
 * under {@code MANUAL}, and every automatic mode commits an offset for work a listener has not
 * finished. The mode reaches the factory from {@code spring.kafka.listener.ack-mode} through the
 * auto-configured configurer, so a deployment can move it; the gate this class exercises is what
 * turns that move into a refused start-up rather than a lost guarantee.
 */
@DisplayName("Replica acknowledgement contract, the authorization service")
class ReplicaAcknowledgementContractTest {

    /** The topic the recoverer is configured to publish to. */
    private static final String DEAD_LETTER_TOPIC = "carddemo.dead-letter";

    /** The topic a refused replica record arrived on. */
    private static final String SOURCE_TOPIC = "account.state-changed";

    /** The partition a refused replica record arrived on. */
    private static final int SOURCE_PARTITION = 1;

    /** The offset a refused replica record arrived at. */
    private static final long SOURCE_OFFSET = 4L;

    /** One delivery, so the first failure spends the whole policy and reaches the route at once. */
    private static final int ONE_DELIVERY = 1;

    /** The configured wait between two deliveries, in milliseconds. */
    private static final long BACKOFF_MS = 10L;

    @Test
    @DisplayName("a record the dead-letter route published has its offset committed")
    void aDeadLetteredRecordHasItsOffsetCommitted() {
        DefaultErrorHandler errorHandler = terminalOnFirstFailureErrorHandler();

        assertEquals(Boolean.TRUE,
                ReflectionTestUtils.getField(errorHandler, "commitRecovered"),
                "commitRecovered, which the container applies under MANUAL_IMMEDIATE alone");

        Consumer<?, ?> consumer = mock(Consumer.class);
        ConsumerRecord<String, Object> refused = new ConsumerRecord<>(SOURCE_TOPIC, SOURCE_PARTITION,
                SOURCE_OFFSET, "00000000011", "{}");

        errorHandler.handleRemaining(new IllegalStateException("the replica was not refreshed"),
                List.of(refused), consumer, manualImmediateContainer());

        verify(consumer).commitSync(
                eq(Map.of(new TopicPartition(SOURCE_TOPIC, SOURCE_PARTITION),
                        new OffsetAndMetadata(SOURCE_OFFSET + 1))),
                nullable(Duration.class));
    }

    @Test
    @DisplayName("the container factory accepts the shipped immediate manual mode")
    void theContainerFactoryAcceptsTheShippedMode() {
        ConcurrentKafkaListenerContainerFactory<Object, Object> factory =
                factoryFor(ContainerProperties.AckMode.MANUAL_IMMEDIATE);

        assertEquals(ContainerProperties.AckMode.MANUAL_IMMEDIATE,
                factory.getContainerProperties().getAckMode());
        assertTrue(factory.getContainerProperties().isDeliveryAttemptHeader(),
                "the delivery-attempt header is what a listener reads to report an attempt number");
    }

    @ParameterizedTest
    @EnumSource(value = ContainerProperties.AckMode.class,
            names = "MANUAL_IMMEDIATE", mode = EnumSource.Mode.EXCLUDE)
    @DisplayName("the container factory refuses every other acknowledgement mode")
    void theContainerFactoryRefusesEveryOtherMode(ContainerProperties.AckMode mode) {
        IllegalStateException refused =
                assertThrows(IllegalStateException.class, () -> factoryFor(mode));

        assertTrue(refused.getMessage().contains("spring.kafka.listener.ack-mode"),
                "the refusal names the property a deployment has to change: " + refused.getMessage());
        assertTrue(refused.getMessage().contains(mode.name()),
                "the refusal names the mode it found: " + refused.getMessage());
    }

    @Test
    @DisplayName("an absent acknowledgement mode is refused rather than defaulted")
    void anAbsentModeIsRefused() {
        assertThrows(IllegalStateException.class,
                () -> KafkaConsumerConfig.requireImmediateManualAcknowledgement(null),
                "the framework default is BATCH, which acknowledges on the listener's behalf");
    }

    @Test
    @DisplayName("the gate answers with the mode it accepted, so a caller can install it")
    void theGateAnswersWithTheModeItAccepted() {
        assertSame(ContainerProperties.AckMode.MANUAL_IMMEDIATE,
                KafkaConsumerConfig.requireImmediateManualAcknowledgement(
                        ContainerProperties.AckMode.MANUAL_IMMEDIATE));
    }

    /**
     * Builds the shipped container factory with one bound acknowledgement mode.
     *
     * @param mode the value {@code spring.kafka.listener.ack-mode} carries
     * @return the configured factory
     */
    @SuppressWarnings("unchecked")
    private static ConcurrentKafkaListenerContainerFactory<Object, Object> factoryFor(
            ContainerProperties.AckMode mode) {

        KafkaProperties properties = new KafkaProperties();
        properties.getListener().setAckMode(mode);

        ConcurrentKafkaListenerContainerFactoryConfigurer configurer =
                new ConcurrentKafkaListenerContainerFactoryConfigurer();
        ReflectionTestUtils.setField(configurer, "properties", properties);

        return new KafkaConsumerConfig().kafkaListenerContainerFactory(
                mock(ConsumerFactory.class), configurer, mock(DefaultErrorHandler.class));
    }

    /**
     * Builds the shipped error handler with one delivery, so the first failure reaches the route.
     *
     * @return the handler the container factory installs
     */
    @SuppressWarnings("unchecked")
    private static DefaultErrorHandler terminalOnFirstFailureErrorHandler() {
        KafkaTemplate<String, byte[]> template = mock(KafkaTemplate.class);
        when(template.send(any(ProducerRecord.class))).thenAnswer(invocation -> {
            ProducerRecord<String, byte[]> published = invocation.getArgument(0);
            return CompletableFuture.completedFuture(new SendResult<>(published,
                    new RecordMetadata(new TopicPartition(DEAD_LETTER_TOPIC, 0), 0L, 0, 0L, 0, 0)));
        });

        return new KafkaConsumerConfig().replicaConsumerErrorHandler(template, DEAD_LETTER_TOPIC,
                new SimpleMeterRegistry(), ONE_DELIVERY, BACKOFF_MS);
    }

    /**
     * A container acknowledging by hand and at once, which is the mode the shipped configuration
     * names and the only mode under which a recovered offset is committed.
     *
     * @return the container the error handler reads its properties from
     */
    private static MessageListenerContainer manualImmediateContainer() {
        ContainerProperties containerProperties = new ContainerProperties(SOURCE_TOPIC);
        containerProperties.setAckMode(ContainerProperties.AckMode.MANUAL_IMMEDIATE);
        MessageListenerContainer container = mock(MessageListenerContainer.class);
        when(container.getContainerProperties()).thenReturn(containerProperties);
        return container;
    }
}
