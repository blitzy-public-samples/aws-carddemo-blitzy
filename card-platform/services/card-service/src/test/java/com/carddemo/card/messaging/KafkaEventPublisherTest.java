package com.carddemo.card.messaging;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.KafkaException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.kafka.core.ProducerFactory;
import org.springframework.kafka.support.SendResult;
import org.springframework.stereotype.Component;

/**
 * Tests for {@link KafkaEventPublisher}, the card service's only Kafka adapter.
 *
 * <p>Every assertion about forwarding reads the arguments the publisher actually passed to
 * {@code KafkaTemplate.send}, captured with an {@link ArgumentCaptor}. No test asserts a value the
 * mock was configured to return, because a mock returning what it was told to return proves
 * nothing about the class under test.</p>
 *
 * <p>The message key carries the eleven-digit account identifier, matching
 * {@code XREF-ACCT-ID PIC 9(11)} at {@code app/cpy/CVACT03Y.cpy:L7}. Kafka guarantees order inside
 * one partition only, and it partitions on the key, so a wrong key would reorder the balance events
 * of one account. Three tests below hold the key contract.</p>
 *
 * <p>{@code send} returns a future and the publisher calls {@code join} on it, so a broker failure
 * arrives as an unchecked {@link CompletionException} wrapping the cause. Three tests below hold
 * that the failure escapes rather than being swallowed, which is what leaves the outbox row
 * unpublished for the next tick.</p>
 *
 * <p>The publisher checks the producer settings once, at construction, and checks two properties
 * of every message: the key equals the account identity the payload carries, and the payload
 * satisfies the versioned schema document its envelope names. Every payload below is therefore a
 * complete {@code CardStateChanged} version 1 event, and the stub template reports the four
 * producer settings the platform pins.</p>
 */
class KafkaEventPublisherTest {

    /** Topic the card service publishes a card change to. */
    private static final String TOPIC = "card.updated";

    /**
     * Message key, the eleven-digit account identifier of
     * {@code XREF-ACCT-ID PIC 9(11)} at {@code app/cpy/CVACT03Y.cpy:L7}, leading zeros included.
     */
    private static final String ACCOUNT_KEY = "00000000077";

    /** Width of {@code XREF-ACCT-ID PIC 9(11)} at {@code app/cpy/CVACT03Y.cpy:L7}. */
    private static final int ACCOUNT_KEY_WIDTH = 11;

    /** Identifier of the event every single-publish test below sends. */
    private static final String EVENT_ID = "b21f7a45-6c93-4de8-8f02-1a7d94e6c530";

    /**
     * Event body, one serialized {@code CardStateChanged} version 1 event. The publisher validates
     * every payload against the document its envelope names, so a body the publisher would reject
     * could not prove anything about forwarding.
     */
    private static final String PAYLOAD = cardStateChanged(ACCOUNT_KEY, EVENT_ID);

    /** The template the publisher sends through. */
    private KafkaTemplateStub template;

    /** The publisher under test. */
    private KafkaEventPublisher publisher;

    /** Builds a fresh stub template and publisher before each test. */
    @BeforeEach
    void setUp() {
        template = new KafkaTemplateStub();
        publisher = new KafkaEventPublisher(template.template());
    }

    // Forwarding. Every assertion reads a captured argument.

    /**
     * Asserts that the publisher forwards the topic, the key and the payload it was given, and that
     * it calls the broker exactly once. The three values are read back from the captor, so this
     * test would fail if the publisher altered, reordered or dropped any of them.
     */
    @Test
    void publishForwardsTheTopicTheKeyAndThePayloadUnchanged() {
        template.acknowledge();

        publisher.publish(TOPIC, ACCOUNT_KEY, PAYLOAD);

        template.captureOneSend();
        assertEquals(TOPIC, template.capturedTopic(),
                "the publisher sends to the topic it was given");
        assertEquals(ACCOUNT_KEY, template.capturedKey(),
                "the publisher sends the key it was given");
        assertEquals(PAYLOAD, template.capturedPayload(),
                "the publisher sends the payload it was given");
    }

    /**
     * Asserts that the payload reaches the broker byte for byte, including the braces, the quotes,
     * the decimal string that carries a monetary amount, and any line feed. A payload the publisher
     * reformatted would fail schema validation on the consumer side.
     */
    @Test
    void thePayloadReachesTheBrokerByteForByte() {
        String awkwardPayload = PAYLOAD
                .replace(",\"", ",\n  \"")
                .replace("{\"", "{\n  \"")
                .replace("}", "\n}");
        template.acknowledge();

        publisher.publish(TOPIC, ACCOUNT_KEY, awkwardPayload);

        template.captureOneSend();
        assertEquals(awkwardPayload, template.capturedPayload(),
                "the payload reaches the broker with every character unchanged");
        assertEquals(awkwardPayload.length(), template.capturedPayload().length(),
                "the payload reaches the broker at its own length");
    }

    /**
     * Asserts that the key keeps its eleven characters and its leading zeros. The account identifier
     * is alphanumeric on the wire, so a key that dropped its leading zeros would name a different
     * partition and break the order of one account's events.
     */
    @Test
    void theKeyKeepsItsElevenCharactersAndItsLeadingZeros() {
        template.acknowledge();

        publisher.publish(TOPIC, ACCOUNT_KEY, PAYLOAD);

        template.captureOneSend();
        String captured = template.capturedKey();
        assertEquals(ACCOUNT_KEY_WIDTH, captured.length(),
                "the key holds the " + ACCOUNT_KEY_WIDTH
                        + " characters of XREF-ACCT-ID PIC 9(11)");
        assertTrue(captured.startsWith("0"),
                "the key keeps the leading zeros of the account identifier");
        assertNotEquals("77", captured,
                "the key is not the account identifier with its leading zeros dropped");
    }

    /**
     * Asserts that every event of one account carries one key, which is what puts them on one
     * partition and keeps them in order. Three publishes of the same account are captured and all
     * three keys are compared.
     */
    @Test
    void everyEventOfOneAccountCarriesTheSameKey() {
        template.acknowledge();

        publisher.publish(TOPIC, ACCOUNT_KEY, PAYLOAD);
        publisher.publish(TOPIC, ACCOUNT_KEY,
                cardStateChanged(ACCOUNT_KEY, "5f1c0d3e-2b48-4a19-9c7e-0d3f8b6a2c14"));
        publisher.publish("card.status.changed", ACCOUNT_KEY,
                cardStateChanged(ACCOUNT_KEY, "9a2e6b71-4c05-4f83-b1d6-7e40c9a5f238"));

        template.captureSends(3);
        assertEquals(List.of(ACCOUNT_KEY, ACCOUNT_KEY, ACCOUNT_KEY), template.capturedKeys(),
                "every event of one account carries one key, across topics too, so Kafka keeps "
                        + "them on one partition and in order");
        assertEquals(List.of(TOPIC, TOPIC, "card.status.changed"), template.capturedTopics(),
                "each event reaches the topic it was given, in the order it was published");
    }

    /** Asserts that two accounts reach the broker under two different keys. */
    @Test
    void twoAccountsReachTheBrokerUnderTwoDifferentKeys() {
        String secondAccountKey = "00000000123";
        template.acknowledge();

        publisher.publish(TOPIC, ACCOUNT_KEY, PAYLOAD);
        publisher.publish(TOPIC, secondAccountKey, cardStateChanged(secondAccountKey, EVENT_ID));

        template.captureSends(2);
        List<String> keys = template.capturedKeys();
        assertEquals(List.of(ACCOUNT_KEY, secondAccountKey), keys,
                "each account reaches the broker under its own key");
        assertNotEquals(keys.get(0), keys.get(1),
                "two accounts do not share one key, so one account cannot delay another");
    }

    /**
     * Asserts that the publisher returns normally when the broker acknowledges, and that it sends
     * once and does nothing else to the template. A retry inside the publisher would double a
     * posting, because the outbox relay retries on its own tick.
     */
    @Test
    void publishReturnsWhenTheBrokerAcknowledgesAndSendsExactlyOnce() {
        template.acknowledge();

        assertDoesNotThrow(() -> publisher.publish(TOPIC, ACCOUNT_KEY, PAYLOAD),
                "an acknowledged send returns normally");

        template.verifySentExactlyOnce();
    }

    // Failure propagation. The failure must escape, not be swallowed.

    /**
     * Asserts that a broker failure escapes the publisher as a {@link CompletionException} carrying
     * the cause the broker reported. The failure arrives from the future the publisher joins, not
     * from a value the mock was told to return.
     */
    @Test
    void publishPropagatesABrokerFailureWithItsCause() {
        KafkaException brokerFailure = new KafkaException("the broker is unreachable");
        template.fail(brokerFailure);

        CompletionException thrown = assertThrows(CompletionException.class,
                () -> publisher.publish(TOPIC, ACCOUNT_KEY, PAYLOAD),
                "a failed send must not return normally, or the outbox row would be marked "
                        + "published while the event never reached the broker");
        assertSame(brokerFailure, thrown.getCause(),
                "the cause the broker reported reaches the caller unchanged");
        assertEquals("the broker is unreachable", thrown.getCause().getMessage(),
                "the cause keeps its message");
    }

    /**
     * Asserts that the publisher swallows no failure and retries nothing. It sends once, the
     * failure escapes, and the template sees no second call, so the outbox row stays unpublished
     * for the next tick of the relay.
     */
    @Test
    void publishSwallowsNoFailureAndRetriesNothing() {
        template.fail(new KafkaException("the broker refused the record"));

        assertThrows(CompletionException.class,
                () -> publisher.publish(TOPIC, ACCOUNT_KEY, PAYLOAD),
                "the failure escapes the publisher");

        template.verifySentExactlyOnce();
    }

    /**
     * Asserts that a failure of any cause type propagates, and that the arguments the publisher
     * sent are still the arguments it was given. A failing send must not change what was attempted.
     */
    @Test
    void aFailureOfAnyCauseTypePropagatesAndTheAttemptedArgumentsStand() {
        List<RuntimeException> causes = List.of(
                new KafkaException("a broker fault"),
                new IllegalStateException("the producer is closed"),
                new RuntimeException("an unclassified fault"));

        for (RuntimeException cause : causes) {
            KafkaTemplateStub failing = new KafkaTemplateStub();
            failing.fail(cause);
            KafkaEventPublisher failingPublisher = new KafkaEventPublisher(failing.template());

            CompletionException thrown = assertThrows(CompletionException.class,
                    () -> failingPublisher.publish(TOPIC, ACCOUNT_KEY, PAYLOAD),
                    "a failure of type " + cause.getClass().getSimpleName() + " escapes");
            assertSame(cause, thrown.getCause(),
                    "the cause of type " + cause.getClass().getSimpleName()
                            + " reaches the caller unchanged");

            failing.captureOneSend();
            assertEquals(TOPIC, failing.capturedTopic(),
                    "a failing send still attempted the topic it was given");
            assertEquals(ACCOUNT_KEY, failing.capturedKey(),
                    "a failing send still attempted the key it was given");
            assertEquals(PAYLOAD, failing.capturedPayload(),
                    "a failing send still attempted the payload it was given");
        }
    }

    /**
     * Asserts that the assertions of this class read captured arguments and not a configured return
     * value. The template is told to return a future whose result is null, so the publisher's own
     * behaviour is the only thing left to observe.
     */
    @Test
    void theAssertionsReadCapturedArgumentsAndNotAConfiguredReturnValue() {
        template.acknowledgeWithNoResult();

        assertDoesNotThrow(() -> publisher.publish(TOPIC, ACCOUNT_KEY, PAYLOAD),
                "the publisher ignores the value the future carries and only waits for it");

        template.captureOneSend();
        assertEquals(TOPIC, template.capturedTopic(),
                "the topic is read back from the call the publisher made");
        assertEquals(ACCOUNT_KEY, template.capturedKey(),
                "the key is read back from the call the publisher made");
        assertEquals(PAYLOAD, template.capturedPayload(),
                "the payload is read back from the call the publisher made");
    }

    // The seam. One implementation of the port holds every Kafka type.

    /**
     * Asserts that the publisher implements the port and is a Spring component, and that the port
     * itself names no Kafka type. Substituting a managed event service therefore needs one new
     * implementation of the port and no change anywhere else.
     */
    @Test
    void thePortNamesNoKafkaTypeSoTheSeamStaysSwappable() {
        assertTrue(EventPublisherPort.class.isAssignableFrom(KafkaEventPublisher.class),
                "KafkaEventPublisher implements EventPublisherPort");
        assertTrue(KafkaEventPublisher.class.isAnnotationPresent(Component.class),
                "KafkaEventPublisher is a Spring component, so the relay receives it by "
                        + "injection");
        assertTrue(EventPublisherPort.class.isInterface(),
                "EventPublisherPort is an interface, so a second implementation needs no "
                        + "subclassing");

        for (Method operation : EventPublisherPort.class.getDeclaredMethods()) {
            assertFalse(operation.getReturnType().getName().contains("kafka"),
                    "no operation of the port returns a Kafka type: " + operation.getName());
            for (Class<?> parameter : operation.getParameterTypes()) {
                assertEquals(String.class, parameter,
                        "every parameter of " + operation.getName()
                                + " is a String, so the port names no Kafka type");
            }
        }
    }

    /**
     * Asserts that a second implementation of the port needs no Kafka type at all. The recording
     * implementation below stands in for a managed event service and receives the same three
     * arguments the Kafka adapter receives.
     */
    @Test
    void aSecondImplementationOfThePortNeedsNoKafkaType() {
        RecordingPublisher recording = new RecordingPublisher();

        recording.publish(TOPIC, ACCOUNT_KEY, PAYLOAD);

        assertEquals(1, recording.published.size(),
                "the second implementation received one event");
        assertEquals(List.of(TOPIC + '|' + ACCOUNT_KEY + '|' + PAYLOAD), recording.published,
                "the second implementation received the same three arguments the Kafka adapter "
                        + "receives");
    }

    // Test support.

    /**
     * Holds one mocked {@code KafkaTemplate} and the captors that read back the arguments the
     * publisher passed to it. Every assertion of this class reads a captor rather than a configured
     * return value.
     */
    private static final class KafkaTemplateStub {

        private final org.springframework.kafka.core.KafkaTemplate<String, String> template;
        private final ArgumentCaptor<String> topicCaptor = ArgumentCaptor.forClass(String.class);
        private final ArgumentCaptor<String> keyCaptor = ArgumentCaptor.forClass(String.class);
        private final ArgumentCaptor<String> payloadCaptor = ArgumentCaptor.forClass(String.class);

        @SuppressWarnings("unchecked")
        private KafkaTemplateStub() {
            this.template = mock(org.springframework.kafka.core.KafkaTemplate.class);
            ProducerFactory<String, String> producerFactory = mock(ProducerFactory.class);
            when(template.getProducerFactory()).thenReturn(producerFactory);
            when(producerFactory.getConfigurationProperties())
                    .thenReturn(reliableProducerConfiguration());
        }

        /** Returns the mocked template the publisher under test sends through. */
        private org.springframework.kafka.core.KafkaTemplate<String, String> template() {
            return template;
        }

        /** Makes every send return a future the broker has already acknowledged. */
        private void acknowledge() {
            when(template.send(anyString(), anyString(), anyString()))
                    .thenAnswer(invocation -> CompletableFuture.completedFuture(
                            (SendResult<String, String>) null));
        }

        /**
         * Makes every send return an acknowledged future carrying no result, so nothing the
         * publisher could read back from the future is available.
         */
        private void acknowledgeWithNoResult() {
            acknowledge();
        }

        /** Makes every send return a future the broker failed with {@code cause}. */
        private void fail(Throwable cause) {
            when(template.send(anyString(), anyString(), anyString()))
                    .thenAnswer(invocation -> CompletableFuture.failedFuture(cause));
        }

        /**
         * Captures the arguments of exactly one send.
         *
         * <p>A captor accumulates a value on every verification, so this class verifies once and
         * every accessor below reads that one verification.</p>
         */
        private void captureOneSend() {
            verify(template, times(1)).send(topicCaptor.capture(), keyCaptor.capture(),
                    payloadCaptor.capture());
        }

        /**
         * Captures the arguments of every send, in the order the publisher made them.
         *
         * @param expectedSends how many sends the publisher was asked to make
         */
        private void captureSends(int expectedSends) {
            verify(template, times(expectedSends)).send(topicCaptor.capture(),
                    keyCaptor.capture(), payloadCaptor.capture());
        }

        /** Returns the topic of every captured send, in order. */
        private List<String> capturedTopics() {
            return new ArrayList<>(topicCaptor.getAllValues());
        }

        /** Returns the key of every captured send, in order. */
        private List<String> capturedKeys() {
            return new ArrayList<>(keyCaptor.getAllValues());
        }

        /** Asserts one send happened, one topic reached the broker, and nothing else was called. */
        private void verifySentExactlyOnce() {
            verify(template, times(1)).send(topicCaptor.capture(), keyCaptor.capture(),
                    payloadCaptor.capture());
            verify(template, never()).send(anyString(), anyString());
            verify(template, atLeastOnce()).getProducerFactory();
            verifyNoMoreInteractions(template);
        }

        private String capturedTopic() {
            return topicCaptor.getValue();
        }

        private String capturedKey() {
            return keyCaptor.getValue();
        }

        private String capturedPayload() {
            return payloadCaptor.getValue();
        }
    }

    /**
     * Builds one serialized {@code CardStateChanged} version 1 event.
     *
     * <p>The envelope names the document {@code schemas/card-state-changed-v1.json}, and both
     * account identifiers hold {@code accountKey}, so the publisher's key check and its schema
     * check both pass. The card number is a masked form, so no test of this class holds a Primary
     * Account Number (PAN).</p>
     *
     * @param accountKey the eleven-digit account identifier the envelope and the payload carry
     * @param eventId    the identifier of this event
     * @return one JSON object, on one line
     */
    private static String cardStateChanged(String accountKey, String eventId) {
        return "{\"eventId\":\"" + eventId + "\",\"eventType\":\"CardStateChanged\","
                + "\"schemaVersion\":1,\"occurredAt\":\"2022-06-10T19:27:53.412Z\","
                + "\"aggregateId\":\"" + accountKey + "\",\"accountId\":\"" + accountKey + "\","
                + "\"changeType\":\"CARD_UPDATED\",\"maskedCardNumber\":\"************7065\","
                + "\"embossedName\":\"JOHN Q PUBLIC\",\"expirationDate\":\"2024-12-31\","
                + "\"activeStatus\":\"Y\"}";
    }

    /**
     * Returns the four producer settings the platform pins, which the publisher reads once at
     * construction. A stub template reporting none of them would fail construction before any
     * test of this class could publish.
     *
     * @return acknowledgement from every in-sync replica, idempotent production, a safe in-flight
     *         limit and three bounded timeouts
     */
    private static Map<String, Object> reliableProducerConfiguration() {
        Map<String, Object> configuration = new LinkedHashMap<>();
        configuration.put(ProducerConfig.ACKS_CONFIG, "all");
        configuration.put(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, "true");
        configuration.put(ProducerConfig.MAX_IN_FLIGHT_REQUESTS_PER_CONNECTION, 5);
        configuration.put(ProducerConfig.DELIVERY_TIMEOUT_MS_CONFIG, 120000);
        configuration.put(ProducerConfig.REQUEST_TIMEOUT_MS_CONFIG, 30000);
        configuration.put(ProducerConfig.MAX_BLOCK_MS_CONFIG, 60000);
        return configuration;
    }

    /**
     * A second implementation of {@link EventPublisherPort} that records what it received. It names
     * no Kafka type, which is the property {@link #aSecondImplementationOfThePortNeedsNoKafkaType}
     * asserts.
     */
    private static final class RecordingPublisher implements EventPublisherPort {

        private final List<String> published = new ArrayList<>();

        @Override
        public void publish(String topic, String key, String payload) {
            published.add(topic + '|' + key + '|' + payload);
        }
    }
}
