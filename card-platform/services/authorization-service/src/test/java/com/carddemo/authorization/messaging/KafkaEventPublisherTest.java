package com.carddemo.authorization.messaging;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.carddemo.events.DeclineReason;
import com.carddemo.events.TransactionDeclined;
import java.math.BigDecimal;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import tools.jackson.databind.json.JsonMapper;

/**
 * Covers both aggregate-key forms the authorization publisher sends.
 */
class KafkaEventPublisherTest {

    private static final String AUTHORIZED_TOPIC = "transaction.authorized";

    private static final String DECLINED_TOPIC = "transaction.declined";

    private static final String ACCOUNT_ID = "00000000007";

    private static final String TRANSACTION_ID = "0000001000000001";

    private static final String MASKED_CARD_NUMBER = "************7065";

    private KafkaTemplate<String, String> template;

    private KafkaEventPublisher publisher;

    private JsonMapper jsonMapper;

    /**
     * Gives every test an acknowledged broker send.
     */
    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        template = mock(KafkaTemplate.class);
        SendResult<String, String> result = mock(SendResult.class);
        when(template.send(org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString()))
                .thenReturn(CompletableFuture.completedFuture(result));
        publisher = new KafkaEventPublisher(template, AUTHORIZED_TOPIC, DECLINED_TOPIC,
                java.time.Duration.ofSeconds(30L));
        jsonMapper = JsonMapper.builder().build();
    }

    /**
     * The unresolved-card contract has no account identifier and uses its transaction identifier
     * as both aggregate and Kafka key.
     */
    @Test
    void unresolvedCardDeclinePublishesUnderItsTransactionKey() {
        TransactionDeclined event = TransactionDeclined.ofUnresolvedAccount(
                TRANSACTION_ID, new BigDecimal("1.00"), MASKED_CARD_NUMBER);
        String payload = jsonMapper.writeValueAsString(event);

        assertDoesNotThrow(
                () -> publisher.publish(DECLINED_TOPIC, event.aggregateId(), payload));

        verify(template).send(DECLINED_TOPIC, TRANSACTION_ID, payload);
    }

    /**
     * The original decline contract keeps the account identifier as aggregate and message key.
     */
    @Test
    void resolvedCardDeclineStillPublishesUnderItsAccountKey() {
        TransactionDeclined event = TransactionDeclined.of(ACCOUNT_ID, TRANSACTION_ID,
                DeclineReason.OVER_CREDIT_LIMIT, new BigDecimal("1.00"), MASKED_CARD_NUMBER);
        String payload = jsonMapper.writeValueAsString(event);

        assertDoesNotThrow(
                () -> publisher.publish(DECLINED_TOPIC, event.aggregateId(), payload));

        verify(template).send(DECLINED_TOPIC, ACCOUNT_ID, payload);
    }

    /**
     * A transaction-keyed payload must not be published under an account partition.
     */
    @Test
    void unresolvedCardDeclineRefusesADifferentMessageKeyWithoutEchoingEitherValue() {
        TransactionDeclined event = TransactionDeclined.ofUnresolvedAccount(
                TRANSACTION_ID, new BigDecimal("1.00"), MASKED_CARD_NUMBER);
        String payload = jsonMapper.writeValueAsString(event);

        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> publisher.publish(DECLINED_TOPIC, ACCOUNT_ID, payload));

        assertTrue(failure.getMessage().contains("aggregate identifier"));
        assertFalse(failure.getMessage().contains(ACCOUNT_ID));
        assertFalse(failure.getMessage().contains(TRANSACTION_ID));
        verify(template, never()).send(org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString());
    }

    /**
     * A key outside both governed forms is refused before any broker call.
     */
    @Test
    void anUnknownAggregateKeyFormReachesNoTopic() {
        TransactionDeclined event = TransactionDeclined.ofUnresolvedAccount(
                TRANSACTION_ID, new BigDecimal("1.00"), MASKED_CARD_NUMBER);
        String payload = jsonMapper.writeValueAsString(event);

        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> publisher.publish(DECLINED_TOPIC, "short", payload));

        assertTrue(failure.getMessage().contains(EventPublisherPort.AGGREGATE_ID_PATTERN));
        assertFalse(failure.getMessage().contains("short"));
        verify(template, never()).send(org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString());
    }
}
