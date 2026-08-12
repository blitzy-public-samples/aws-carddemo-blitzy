package com.carddemo.authorization.messaging;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.carddemo.authorization.config.KafkaProducerConfig;
import com.carddemo.events.DeclineReason;
import com.carddemo.events.TransactionDeclined;
import java.lang.annotation.Annotation;
import java.lang.reflect.Constructor;
import com.carddemo.events.correlation.CorrelationScope;
import com.carddemo.events.correlation.EventCorrelation;
import java.math.BigDecimal;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Stream;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.Bean;
import org.mockito.ArgumentCaptor;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;
import org.springframework.kafka.support.SendResult;
import tools.jackson.databind.json.JsonMapper;

/**
 * Covers both aggregate-key forms the authorization publisher sends.
 */
class KafkaEventPublisherTest {

    private static final String AUTHORIZED_TOPIC = "transaction.authorized";

    private static final String DECLINED_TOPIC = "transaction.declined";

    /**
     * The dead-letter destination this publisher is configured with.
     *
     * <p>{@code outbox/OutboxRelay} publishes the diagnostic of an abandoned row through this same
     * seam, so the topic has to be bound here or every such diagnostic would be refused before it
     * started. The value matches {@code carddemo.kafka.topics.dead-letter} in
     * {@code application.yml}.
     */
    private static final String DEAD_LETTER_TOPIC = "carddemo.dead-letter";

    private static final String ACCOUNT_ID = "00000000007";

    /** A second account, so a key naming another aggregate can be refused without inventing a width. */
    private static final String OTHER_ACCOUNT_ID = "00000000050";

    private static final String TRANSACTION_ID = "0000001000000001";

    private static final String MASKED_CARD_NUMBER = "************7065";

    /** The capture moment a detail-bearing decline carries, twenty-six characters. */
    private static final String ORIGIN_TIMESTAMP = "2022-06-10 19:27:53.412000";

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
        when(template.send(org.mockito.ArgumentMatchers.<ProducerRecord<String, String>>any()))
                .thenReturn(CompletableFuture.completedFuture(result));
        publisher = new KafkaEventPublisher(template, AUTHORIZED_TOPIC, DECLINED_TOPIC,
                DEAD_LETTER_TOPIC, java.time.Duration.ofSeconds(30L));
        jsonMapper = JsonMapper.builder().build();
    }

    /**
     * A payload declaring a retained contract version reaches no topic.
     *
     * <p>{@code contracts/released-contracts.json} records
     * {@code schemas/transaction-declined-v1.json} as retained: a record published under it before the
     * nine descriptive values {@code REJECT-TRAN-DATA} needs were added is still readable, and no
     * producer writes it. The publisher is a producer path, so it refuses one rather than putting a
     * superseded shape back on a topic.
     */
    @Test
    void aRetainedContractVersionReachesNoTopic() {
        TransactionDeclined retained = TransactionDeclined.of(ACCOUNT_ID, TRANSACTION_ID,
                DeclineReason.OVER_CREDIT_LIMIT, new BigDecimal("1.00"), MASKED_CARD_NUMBER);
        String payload = jsonMapper.writeValueAsString(retained);

        IllegalArgumentException refused = assertThrows(IllegalArgumentException.class,
                () -> publisher.publish(DECLINED_TOPIC, retained.aggregateId(), payload));

        assertTrue(refused.getMessage().contains("RETAINED"),
                "the refusal names the posture that stopped the publish: " + refused.getMessage());
        verify(template, never())
                .send(org.mockito.ArgumentMatchers.<ProducerRecord<String, String>>any());
    }

    /**
     * The one decline that resolves no account publishes under the identifier this platform minted.
     *
     * <p>Reject code {@code 0100} is assigned by the cross-reference read failing, so it has no
     * eleven-digit key, and {@code schemas/transaction-declined-v2.json} retypes {@code aggregateId} to
     * the sixteen printable characters a transaction identifier occupies. The key check this publisher
     * runs compares the key with {@code aggregateId} and reads {@code accountId} only when the payload
     * declares one, so an account-less payload passes it on the value it does carry.
     */
    @Test
    void theAccountLessDeclinePublishesUnderItsTransactionKey() {
        TransactionDeclined event = TransactionDeclined.ofUnresolvedAccount(
                TRANSACTION_ID, new BigDecimal("1.00"), MASKED_CARD_NUMBER);
        String payload = jsonMapper.writeValueAsString(event);

        assertDoesNotThrow(() -> publisher.publish(DECLINED_TOPIC, event.aggregateId(), payload));

        ProducerRecord<String, String> sent = captureSend();
        assertEquals(DECLINED_TOPIC, sent.topic(), "the record is addressed to the declined topic");
        assertEquals(TRANSACTION_ID, sent.key(),
                "the transaction identifier is the message key, so no caller chose the partition");
        assertEquals(payload, sent.value(), "the payload travels unchanged");
    }

    /**
     * Every published decline keys on the account identifier its decision applies to.
     */
    @Test
    void aDeclinePublishesUnderItsAccountKey() {
        TransactionDeclined event = TransactionDeclined.withTransactionDetail(ACCOUNT_ID,
                TRANSACTION_ID, DeclineReason.OVER_CREDIT_LIMIT, "01", "0001", "POS TERM",
                "Purchase at Abshire-Lowe", new BigDecimal("1.00"), "800000000", "Abshire-Lowe",
                "North Enoshaven", "72112", MASKED_CARD_NUMBER, ORIGIN_TIMESTAMP);
        String payload = jsonMapper.writeValueAsString(event);

        assertDoesNotThrow(
                () -> publisher.publish(DECLINED_TOPIC, event.aggregateId(), payload));

        ProducerRecord<String, String> sent = captureSend();
        assertEquals(DECLINED_TOPIC, sent.topic(), "the record is addressed to the declined topic");
        assertEquals(ACCOUNT_ID, sent.key(), "the account identifier is the message key");
        assertEquals(payload, sent.value(), "the payload travels unchanged");
    }

    /**
     * A payload must not be published under a message key naming another aggregate.
     */
    @Test
    void aDeclineRefusesADifferentMessageKeyWithoutEchoingEitherValue() {
        TransactionDeclined event = TransactionDeclined.withTransactionDetail(ACCOUNT_ID,
                TRANSACTION_ID, DeclineReason.OVER_CREDIT_LIMIT, "01", "0001", "POS TERM",
                "Purchase at Abshire-Lowe", new BigDecimal("1.00"), "800000000", "Abshire-Lowe",
                "North Enoshaven", "72112", MASKED_CARD_NUMBER, ORIGIN_TIMESTAMP);
        String payload = jsonMapper.writeValueAsString(event);

        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> publisher.publish(DECLINED_TOPIC, OTHER_ACCOUNT_ID, payload));

        assertTrue(failure.getMessage().contains("aggregate identifier"));
        assertFalse(failure.getMessage().contains(ACCOUNT_ID));
        assertFalse(failure.getMessage().contains(OTHER_ACCOUNT_ID));
        assertFalse(failure.getMessage().contains(TRANSACTION_ID));
        verify(template, never())
                .send(org.mockito.ArgumentMatchers.<ProducerRecord<String, String>>any());
    }

    /**
     * A key outside both governed forms is refused before any broker call.
     */
    @Test
    void anUnknownAggregateKeyFormReachesNoTopic() {
        TransactionDeclined event = TransactionDeclined.withTransactionDetail(ACCOUNT_ID,
                TRANSACTION_ID, DeclineReason.OVER_CREDIT_LIMIT, "01", "0001", "POS TERM",
                "Purchase at Abshire-Lowe", new BigDecimal("1.00"), "800000000", "Abshire-Lowe",
                "North Enoshaven", "72112", MASKED_CARD_NUMBER, ORIGIN_TIMESTAMP);
        String payload = jsonMapper.writeValueAsString(event);

        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> publisher.publish(DECLINED_TOPIC, "short", payload));

        assertTrue(failure.getMessage().contains(EventPublisherPort.AGGREGATE_ID_PATTERN));
        assertFalse(failure.getMessage().contains("short"));
        verify(template, never())
                .send(org.mockito.ArgumentMatchers.<ProducerRecord<String, String>>any());
    }

    /**
     * A publish inside one correlation scope carries both identifiers as record headers.
     *
     * <p>ADDITIVE, and the mechanism an observability review asked for. The identifiers travel as
     * headers rather than as payload properties, so the payload the broker receives is byte-for-byte
     * the payload the outbox stored and no schema document changes.
     */
    @Test
    void aPublishInsideAScopeCarriesBothCorrelationHeaders() {
        UUID correlationId = UUID.fromString("11111111-2222-3333-4444-555555555555");
        UUID causationId = UUID.fromString("66666666-7777-8888-9999-aaaaaaaaaaaa");
        TransactionDeclined event = TransactionDeclined.withTransactionDetail(ACCOUNT_ID,
                TRANSACTION_ID, DeclineReason.OVER_CREDIT_LIMIT, "01", "0001", "POS TERM",
                "Purchase at Abshire-Lowe", new BigDecimal("1.00"), "800000000", "Abshire-Lowe",
                "North Enoshaven", "72112", MASKED_CARD_NUMBER, ORIGIN_TIMESTAMP);
        String payload = jsonMapper.writeValueAsString(event);

        try (CorrelationScope scope = CorrelationScope.open()
                .withCorrelation(correlationId)
                .withCausation(causationId)) {
            assertDoesNotThrow(
                    () -> publisher.publish(DECLINED_TOPIC, event.aggregateId(), payload));
        }

        ProducerRecord<String, String> sent = captureSend();
        assertEquals(correlationId,
                EventCorrelation.read(sent.headers(), EventCorrelation.CORRELATION_ID_HEADER)
                        .orElse(null),
                "the correlation identifier reaches the record header");
        assertEquals(causationId,
                EventCorrelation.read(sent.headers(), EventCorrelation.CAUSATION_ID_HEADER)
                        .orElse(null),
                "the causation identifier reaches the record header");
        assertEquals(payload, sent.value(), "the payload is unchanged by either header");
    }

    /**
     * A publish outside any scope carries no correlation header at all.
     *
     * <p>An absent identifier contributes no header rather than a header holding nothing, so a
     * consumer that reads a header always reads a value.
     */
    @Test
    void aPublishOutsideAScopeCarriesNoCorrelationHeader() {
        TransactionDeclined event = TransactionDeclined.withTransactionDetail(ACCOUNT_ID,
                TRANSACTION_ID, DeclineReason.OVER_CREDIT_LIMIT, "01", "0001", "POS TERM",
                "Purchase at Abshire-Lowe", new BigDecimal("1.00"), "800000000", "Abshire-Lowe",
                "North Enoshaven", "72112", MASKED_CARD_NUMBER, ORIGIN_TIMESTAMP);
        String payload = jsonMapper.writeValueAsString(event);

        assertDoesNotThrow(() -> publisher.publish(DECLINED_TOPIC, event.aggregateId(), payload));

        ProducerRecord<String, String> sent = captureSend();
        assertFalse(sent.headers().iterator().hasNext(),
                "no header is attached for an identifier this call does not have");
    }

    /**
     * Captures the one record the publisher sent.
     *
     * @return the sent record
     */
    @SuppressWarnings("unchecked")
    private ProducerRecord<String, String> captureSend() {
        ArgumentCaptor<ProducerRecord<String, String>> captor =
                ArgumentCaptor.forClass(ProducerRecord.class);
        verify(template).send(captor.capture());
        return captor.getValue();
    }

    /**
     * Asserts the publisher is declared from the bound properties rather than scanned.
     *
     * <p>Reading property placeholders in this class would bind
     * {@code carddemo.outbox.relay.publish-timeout} a second time, beside the component of
     * {@code AuthorizationProperties.Outbox.Relay} that already binds it. A second binding meets none
     * of the constraints the record declares, so a publish timeout of zero would start the service and
     * then fail every send the instant it was issued.
     *
     * <p>The three assertions are what keeps that from returning: no stereotype, a declared bean in
     * the configuration that builds it, and no property annotation on any constructor parameter.
     */
    @Test
    @DisplayName("the publisher is built from the bound properties, not scanned and not self-bound")
    void thePublisherIsBuiltFromTheBoundProperties() {
        assertFalse(KafkaEventPublisher.class.isAnnotationPresent(Component.class),
                "a scanned component would have to read its own property placeholders, which is the "
                        + "unvalidated second binding this wiring removed");
        assertTrue(Stream.of(KafkaProducerConfig.class.getDeclaredMethods())
                        .filter(method -> method.isAnnotationPresent(Bean.class))
                        .anyMatch(method -> method.getReturnType()
                                .isAssignableFrom(KafkaEventPublisher.class)),
                "config/KafkaProducerConfig declares the bean outbox/OutboxRelay receives");
        for (Constructor<?> declared : KafkaEventPublisher.class.getDeclaredConstructors()) {
            for (Annotation[] parameter : declared.getParameterAnnotations()) {
                assertEquals(0, parameter.length,
                        "no constructor parameter of the publisher binds a property of its own");
            }
        }
    }
}
