package com.carddemo.card.messaging;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTimeout;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import com.carddemo.card.config.KafkaProducerConfig;
import com.carddemo.events.DeadLetterEnvelope;
import com.carddemo.events.serde.EventContracts;
import tools.jackson.databind.json.JsonMapper;

import java.lang.annotation.Annotation;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.TimeoutException;
import java.util.stream.Stream;

import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.KafkaException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.annotation.Bean;
import org.mockito.ArgumentMatchers;
import org.springframework.kafka.core.ProducerFactory;
import org.springframework.kafka.support.SendResult;
import org.springframework.stereotype.Component;

/**
 * Tests for {@link KafkaEventPublisher}, the card service's only Kafka adapter.
 *
 * <p>Every assertion about forwarding reads the arguments the publisher actually passed to
 * {@code KafkaTemplate.send}, captured with an {@link ArgumentCaptor}. No assertion here reads a
 * value the mock was configured to return.</p>
 *
 * <p>The message key carries the eleven-digit account identifier, matching
 * {@code XREF-ACCT-ID PIC 9(11)} at {@code app/cpy/CVACT03Y.cpy:L7}. Kafka guarantees order inside
 * one partition only, and it partitions on the key. The key must therefore equal the account
 * identifier.</p>
 *
 * <p>{@code send} returns a future and the publisher waits on it for no longer than the configured
 * publish timeout, so a broker failure arrives as an unchecked
 * {@code org.springframework.kafka.KafkaException} carrying the reported cause. The failure escapes
 * the publisher and leaves the outbox row unpublished. A broker that never answers costs the caller
 * the bound and no more, which is what keeps one unreachable broker from holding the relay sweep
 * open indefinitely.</p>
 *
 * <p>The publisher checks the producer settings once, at construction. It checks two properties of
 * every message: the key equals the account identity the payload carries, and the payload satisfies
 * the versioned schema document its envelope names. Every payload below is therefore a complete
 * {@code CardUpdated} version 2 event, and the stub template reports the four producer settings the
 * platform pins.</p>
 */
class KafkaEventPublisherTest {

    /** Topic the card service publishes a card change to. */
    private static final String TOPIC = "card.updated";

    /**
     * The dead-letter destination, renamed away from the registry default on purpose.
     *
     * <p>{@code carddemo.kafka.topics.dead-letter} is configurable in {@code docker-compose.yml} and
     * in {@code deploy/k8s/30-configmap.yaml}, so a deployment may rename it. Using a renamed value
     * here is what proves the publisher follows configuration rather than the default: the publisher
     * used to bind the card-update topic alone, so every terminal diagnostic of an abandoned outbox
     * row was refused before it was sent under exactly this configuration.
     */
    private static final String RENAMED_DEAD_LETTER_TOPIC = "carddemo.dead-letter.v2";

    /**
     * Message key, the eleven-digit account identifier of
     * {@code XREF-ACCT-ID PIC 9(11)} at {@code app/cpy/CVACT03Y.cpy:L7}, leading zeros included.
     */
    private static final String ACCOUNT_KEY = "00000000077";

    /** Width of {@code XREF-ACCT-ID PIC 9(11)} at {@code app/cpy/CVACT03Y.cpy:L7}. */
    private static final int ACCOUNT_KEY_WIDTH = 11;

    /** Identifier of the event every single-publish test sends. */
    private static final String EVENT_ID = "b21f7a45-6c93-4de8-8f02-1a7d94e6c530";

    /**
     * A full sixteen-digit Primary Account Number (PAN), used only to prove that a rejection
     * message carries no value read from the payload. No test of this class publishes it.
     */
    private static final String FULL_CARD_NUMBER = "4859452612877065";

    /**
     * Event body, one serialized {@code CardUpdated} version 2 event. The publisher validates
     * every payload against the document its envelope names, so a body the publisher would reject
     * could not prove anything about forwarding.
     */
    private static final String PAYLOAD = cardUpdated(ACCOUNT_KEY, EVENT_ID);

    /**
     * How long the publisher waits for a broker acknowledgement, matching
     * {@code carddemo.outbox.relay.publish-timeout} of {@code src/main/resources/application.yml}.
     */
    private static final Duration PUBLISH_TIMEOUT = Duration.ofSeconds(30);

    /**
     * A bound short enough that a test can wait it out, used where the assertion is that the wait
     * ends at all.
     */
    private static final Duration BRIEF_TIMEOUT = Duration.ofMillis(50);

    /** The template the publisher sends through. */
    private KafkaTemplateStub template;

    /** The publisher under test. */
    private KafkaEventPublisher publisher;

    /** Builds a fresh stub template and publisher before each test. */
    @BeforeEach
    void setUp() {
        template = new KafkaTemplateStub();
        publisher = new KafkaEventPublisher(template.template(), TOPIC,
                RENAMED_DEAD_LETTER_TOPIC, PUBLISH_TIMEOUT);
    }

    // Forwarding. Every assertion reads a captured argument.

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

    @Test
    void everyEventOfOneAccountCarriesTheSameKey() {
        template.acknowledge();

        publisher.publish(TOPIC, ACCOUNT_KEY, PAYLOAD);
        publisher.publish(TOPIC, ACCOUNT_KEY,
                cardUpdated(ACCOUNT_KEY, "5f1c0d3e-2b48-4a19-9c7e-0d3f8b6a2c14"));
        publisher.publish(TOPIC, ACCOUNT_KEY,
                cardUpdated(ACCOUNT_KEY, "9a2e6b71-4c05-4f83-b1d6-7e40c9a5f238"));

        template.captureSends(3);
        assertEquals(List.of(ACCOUNT_KEY, ACCOUNT_KEY, ACCOUNT_KEY), template.capturedKeys(),
                "every event of one account carries one key, so Kafka keeps them on one partition "
                        + "and in order");
        assertEquals(List.of(TOPIC, TOPIC, TOPIC), template.capturedTopics(),
                "each event reaches the topic it was given, in the order it was published");
    }

    /**
     * Asserts that an event reaches no topic other than the one its event type belongs on. The
     * event type comes from the payload envelope, so a relay that read the wrong topic name from
     * configuration is stopped here instead of delivering a card update to consumers reading a
     * transaction topic.
     */
    @Test
    void anEventTypeReachesNoTopicOtherThanTheOneItIsBoundTo() {
        template.acknowledge();

        IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class,
                () -> publisher.publish("transaction.authorized", ACCOUNT_KEY, PAYLOAD),
                "a card update must not reach a transaction topic");

        assertTrue(thrown.getMessage().contains("CardUpdated"),
                "the rejection names the event type that was published");
        assertTrue(thrown.getMessage().contains(TOPIC),
                "the rejection names the topic the event type belongs on");
        template.verifyNothingSent();
    }

    /**
     * Asserts a terminal diagnostic reaches the dead-letter topic this deployment configures.
     *
     * <p>The failure this stands over is total for one configuration. Binding the card-update topic
     * alone would leave
     * {@link EventContracts#isBoundToTopic(String, String, String)}
     * confirming {@code DeadLetterEnvelope} against a configured name of {@code null}, and so only
     * against the registry default. A deployment that renamed {@code carddemo.kafka.topics.dead-letter} — which
     * {@code docker-compose.yml} and {@code deploy/k8s/30-configmap.yaml} both allow — would then have
     * every diagnostic refused before it was sent, losing the one broker-side record of an outbox row
     * the relay gave up on.
     *
     * <p>{@link #RENAMED_DEAD_LETTER_TOPIC} is deliberately not the default, so binding by default
     * cannot make this pass.
     */
    @Test
    void aTerminalDiagnosticReachesTheConfiguredDeadLetterTopic() {
        template.acknowledge();
        String diagnostic = terminalDiagnostic();

        assertDoesNotThrow(
                () -> publisher.publish(RENAMED_DEAD_LETTER_TOPIC, ACCOUNT_KEY, diagnostic),
                "the publisher follows the configured dead-letter name rather than the default");

        template.captureOneSend();
        assertEquals(List.of(RENAMED_DEAD_LETTER_TOPIC), template.capturedTopics(),
                "the diagnostic reaches the renamed topic");
        assertEquals(List.of(ACCOUNT_KEY), template.capturedKeys(),
                "and it is keyed on the account, as every event on this platform is");
    }

    /**
     * Asserts a diagnostic still reaches no topic other than the dead-letter one.
     *
     * <p>Adding the second entry widens what the publisher accepts, so this holds the widening to
     * exactly one destination: a diagnostic sent to the card-update topic would reach consumers
     * reading it as a card change.
     */
    @Test
    void aTerminalDiagnosticReachesNoOtherTopic() {
        template.acknowledge();
        String diagnostic = terminalDiagnostic();

        IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class,
                () -> publisher.publish(TOPIC, ACCOUNT_KEY, diagnostic),
                "a diagnostic must not reach the card-update topic");

        assertTrue(thrown.getMessage().contains(DeadLetterEnvelope.EVENT_TYPE),
                "the rejection names the event type that was published");
        template.verifyNothingSent();
    }

    /**
     * Asserts that a payload declaring an unregistered event type reaches no topic. An event with
     * no contract has no schema document and no topic, so publishing it would put a payload on the
     * bus that no consumer can validate.
     */
    @Test
    void anUnregisteredEventTypeReachesNoTopic() {
        template.acknowledge();
        String unregistered = PAYLOAD.replace("\"CardUpdated\"", "\"CardReissued\"");

        IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class,
                () -> publisher.publish(TOPIC, ACCOUNT_KEY, unregistered),
                "an event type no contract registers must not reach a topic");

        assertTrue(thrown.getMessage().contains("CardReissued"),
                "the rejection names the unregistered event type");
        template.verifyNothingSent();
    }

    /**
     * Asserts that a schema failure is reported as a pointer and a keyword, and that no value from
     * the payload reaches the message. A validator message carrying the rejected value would put a
     * full Primary Account Number (PAN) into the log of every caller that records the failure.
     */
    @Test
    void aSchemaFailureNamesThePropertyAndCarriesNoValueFromThePayload() {
        template.acknowledge();
        String unmasked = PAYLOAD.replace("************7065", FULL_CARD_NUMBER);

        IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class,
                () -> publisher.publish(TOPIC, ACCOUNT_KEY, unmasked),
                "an unmasked card number must not reach a topic");

        assertTrue(thrown.getMessage().contains("maskedCardNumber"),
                "the rejection names the property that failed");
        assertFalse(thrown.getMessage().contains(FULL_CARD_NUMBER),
                "the rejection carries no Primary Account Number read from the payload");
        assertFalse(thrown.getMessage().contains(ACCOUNT_KEY),
                "the rejection carries no account identifier read from the payload");
        template.verifyNothingSent();
    }

    @Test
    void twoAccountsReachTheBrokerUnderTwoDifferentKeys() {
        String secondAccountKey = "00000000123";
        template.acknowledge();

        publisher.publish(TOPIC, ACCOUNT_KEY, PAYLOAD);
        publisher.publish(TOPIC, secondAccountKey, cardUpdated(secondAccountKey, EVENT_ID));

        template.captureSends(2);
        List<String> keys = template.capturedKeys();
        assertEquals(List.of(ACCOUNT_KEY, secondAccountKey), keys,
                "each account reaches the broker under its own key");
        assertNotEquals(keys.get(0), keys.get(1),
                "two accounts do not share one key, so one account cannot delay another");
    }

    @Test
    void publishReturnsWhenTheBrokerAcknowledgesAndSendsExactlyOnce() {
        template.acknowledge();

        CompletionStage<Void> published = assertDoesNotThrow(
                () -> publisher.publish(TOPIC, ACCOUNT_KEY, PAYLOAD),
                "an acknowledged send returns normally");

        template.verifySentExactlyOnce();
        CompletableFuture<Void> settled = published.toCompletableFuture();
        assertAll(
                () -> assertTrue(settled.isDone(),
                        "the stage a caller awaits has to complete once the broker acknowledged,"
                                + " or the caller waits for an acknowledgement it already has"),
                () -> assertFalse(settled.isCompletedExceptionally(),
                        "and complete normally rather than carrying a failure"));
    }

    // Failure propagation. The failure must escape, not be swallowed.

    @Test
    void publishReportsABrokerFailureOnTheStageWithItsCause() {
        KafkaException brokerFailure = new KafkaException("the broker is unreachable");
        template.fail(brokerFailure);

        Throwable reported = failureOf(publisher.publish(TOPIC, ACCOUNT_KEY, PAYLOAD));

        assertSame(brokerFailure, reported,
                "the cause the broker reported reaches the caller unchanged. A failed send must "
                        + "not complete normally, or the outbox row would be marked published "
                        + "while the event never reached the broker");
        assertEquals("the broker is unreachable", reported.getMessage(),
                "the cause keeps its message");
    }

    @Test
    void publishSwallowsNoFailureAndRetriesNothing() {
        template.fail(new KafkaException("the broker refused the record"));

        assertNotNull(failureOf(publisher.publish(TOPIC, ACCOUNT_KEY, PAYLOAD)),
                "the failure reaches the caller on the stage rather than being swallowed");

        template.verifySentExactlyOnce();
    }

    @Test
    void aBrokerThatNeverAnswersFailsTheStageAtTheConfiguredBound() {
        KafkaTemplateStub silent = new KafkaTemplateStub();
        silent.neverAcknowledge();
        KafkaEventPublisher bounded =
                new KafkaEventPublisher(silent.template(), TOPIC,
                        RENAMED_DEAD_LETTER_TOPIC, BRIEF_TIMEOUT);

        CompletionStage<Void> pending = assertTimeout(RETURNS_PROMPTLY,
                () -> bounded.publish(TOPIC, ACCOUNT_KEY, PAYLOAD),
                "the call itself does not wait for the broker, so a silent broker cannot hold the "
                        + "relay sweep open for as long as it stays unreachable");

        Throwable reported = failureOf(pending);

        assertInstanceOf(TimeoutException.class, reported,
                "the bound the publisher applies lapses on the stage rather than being waited out");
        assertFalse(String.valueOf(reported.getMessage()).contains(FULL_CARD_NUMBER),
                "the reported failure reads no field of the payload");
        silent.verifySentExactlyOnce();
    }

    @Test
    void aFailureOfAnyCauseTypeIsReportedAndTheAttemptedArgumentsStand() {
        List<RuntimeException> causes = List.of(
                new KafkaException("a broker fault"),
                new IllegalStateException("the producer is closed"),
                new RuntimeException("an unclassified fault"));

        for (RuntimeException cause : causes) {
            KafkaTemplateStub failing = new KafkaTemplateStub();
            failing.fail(cause);
            KafkaEventPublisher failingPublisher =
                    new KafkaEventPublisher(failing.template(), TOPIC,
                            RENAMED_DEAD_LETTER_TOPIC, PUBLISH_TIMEOUT);

            Throwable reported = failureOf(
                    failingPublisher.publish(TOPIC, ACCOUNT_KEY, PAYLOAD));
            assertSame(cause, reported,
                    "the cause of type " + cause.getClass().getSimpleName()
                            + " reaches the caller unchanged, whatever its type");

            failing.captureOneSend();
            assertEquals(TOPIC, failing.capturedTopic(),
                    "a failing send still attempted the topic it was given");
            assertEquals(ACCOUNT_KEY, failing.capturedKey(),
                    "a failing send still attempted the key it was given");
            assertEquals(PAYLOAD, failing.capturedPayload(),
                    "a failing send still attempted the payload it was given");
        }
    }

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

    @Test
    void thePortNamesNoKafkaTypeSoTheSeamStaysSwappable() {
        assertTrue(EventPublisherPort.class.isAssignableFrom(KafkaEventPublisher.class),
                "KafkaEventPublisher implements EventPublisherPort");
        // The relay still receives this by injection, and the bean is declared rather than
        // scanned. config/KafkaProducerConfig builds it from the bound CardProperties, so the
        // publish timeout it holds is the value that record's constraints accepted. A constructor
        // @Value was a second binding of the same property and met none of them.
        assertFalse(KafkaEventPublisher.class.isAnnotationPresent(Component.class),
                "KafkaEventPublisher carries no stereotype: a scanned component would have to read "
                        + "its own property placeholders, which is the unvalidated second binding "
                        + "this wiring removed");
        assertTrue(Stream.of(KafkaProducerConfig.class.getDeclaredMethods())
                        .filter(method -> method.isAnnotationPresent(Bean.class))
                        .anyMatch(method -> method.getReturnType()
                                .isAssignableFrom(KafkaEventPublisher.class)),
                "config/KafkaProducerConfig declares the bean the relay receives, so the publisher "
                        + "is built from values the bound record already checked");
        for (Constructor<?> declared : KafkaEventPublisher.class.getDeclaredConstructors()) {
            for (Annotation[] parameter : declared.getParameterAnnotations()) {
                assertEquals(0, parameter.length,
                        "no constructor parameter of the publisher binds a property of its own");
            }
        }
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

        /**
         * Reads back the records the publisher sent.
         *
         * <p>The publisher reaches the broker through the single-argument {@code send} overload so
         * each record can carry the correlation headers a consumer reads, which is why one captor
         * over the record replaces the three that read separate arguments.
         */
        @SuppressWarnings("unchecked")
        private final ArgumentCaptor<ProducerRecord<String, String>> recordCaptor =
                ArgumentCaptor.forClass(ProducerRecord.class);

        @SuppressWarnings("unchecked")
        private KafkaTemplateStub() {
            this.template = mock(org.springframework.kafka.core.KafkaTemplate.class);
            ProducerFactory<String, String> producerFactory = mock(ProducerFactory.class);
            when(template.getProducerFactory()).thenReturn(producerFactory);
            when(producerFactory.getConfigurationProperties())
                    .thenReturn(reliableProducerConfiguration());
        }

        private org.springframework.kafka.core.KafkaTemplate<String, String> template() {
            return template;
        }

        private void acknowledge() {
            when(template.send(anyRecord()))
                    .thenAnswer(invocation -> CompletableFuture.completedFuture(
                            (SendResult<String, String>) null));
        }

        private void acknowledgeWithNoResult() {
            acknowledge();
        }

        /** Answers every send with a future that never completes. */
        private void neverAcknowledge() {
            when(template.send(anyRecord()))
                    .thenAnswer(invocation -> new CompletableFuture<SendResult<String, String>>());
        }

        private void fail(Throwable cause) {
            when(template.send(anyRecord()))
                    .thenAnswer(invocation -> CompletableFuture.failedFuture(cause));
        }

        private void captureOneSend() {
            verify(template, times(1)).send(recordCaptor.capture());
        }

        /**
         * Captures the arguments of every send, in the order the publisher made them.
         *
         * @param expectedSends how many sends the publisher was asked to make
         */
        private void captureSends(int expectedSends) {
            verify(template, times(expectedSends)).send(recordCaptor.capture());
        }

        private List<String> capturedTopics() {
            return new ArrayList<>(recordCaptor.getAllValues().stream()
                    .map(ProducerRecord::topic).toList());
        }

        private List<String> capturedKeys() {
            return new ArrayList<>(recordCaptor.getAllValues().stream()
                    .map(ProducerRecord::key).toList());
        }

        /** Asserts one send happened, one topic reached the broker, and nothing else was called. */
        private void verifyNothingSent() {
            verify(template, never()).send(anyRecord());
            verify(template, never()).send(anyString(), anyString(), anyString());
            verify(template, never()).send(anyString(), anyString());
        }

        private void verifySentExactlyOnce() {
            verify(template, times(1)).send(recordCaptor.capture());
            verify(template, never()).send(anyString(), anyString(), anyString());
            verify(template, never()).send(anyString(), anyString());
            verify(template, atLeastOnce()).getProducerFactory();
            verifyNoMoreInteractions(template);
        }

        private String capturedTopic() {
            return recordCaptor.getValue().topic();
        }

        private String capturedKey() {
            return recordCaptor.getValue().key();
        }

        private String capturedPayload() {
            return recordCaptor.getValue().value();
        }

        /**
         * Matches any record the publisher sends.
         *
         * @return the matcher
         */
        private static ProducerRecord<String, String> anyRecord() {
            return ArgumentMatchers.any();
        }
    }

    /**
     * Serializes one terminal diagnostic the way {@code outbox/OutboxRelay} does.
     *
     * <p>The relay renders the envelope with an {@code ObjectMapper} and hands the text to the publish
     * port, so building it the same way here means the publisher validates the same bytes it would see
     * in production.
     *
     * @return one serialized {@link DeadLetterEnvelope}
     */
    private static String terminalDiagnostic() {
        return JsonMapper.builder().build().writeValueAsString(DeadLetterEnvelope.fromFailure(
                ACCOUNT_KEY, "0999", new IllegalStateException("held, never read"),
                "PUBLISH-ABANDONED", "The relay abandoned this row.", TOPIC, 0, 41L, EVENT_ID,
                EventContracts.CARD_UPDATED, 5));
    }

    /**
     * Builds one serialized {@code CardUpdated} event, at the version a producer publishes.
     *
     * <p>The envelope declares {@code schemaVersion} 2 and the document is
     * {@code schemas/card-updated-v2.json}. Both account identifiers hold {@code accountKey}, so the
     * publisher's key check and its schema check both pass. The card number is a masked form, so no
     * test of this class holds a Primary Account Number (PAN).</p>
     *
     * @param accountKey the eleven-digit account identifier the envelope and the payload carry
     * @param eventId    the identifier of this event, as text
     * @return one JSON object, on one line
     */
    private static String cardUpdated(String accountKey, String eventId) {
        return "{\"eventId\":\"" + eventId + "\",\"eventType\":\"CardUpdated\","
                + "\"schemaVersion\":2,\"occurredAt\":\"2022-06-10T19:27:53.412Z\","
                + "\"aggregateId\":\"" + accountKey + "\","
                + "\"maskedCardNumber\":\"************7065\",\"accountId\":\"" + accountKey
                + "\",\"expirationDate\":\"2024-12-31\","
                + "\"activeStatus\":\"Y\"}";
    }

    /**
     * Returns the four producer settings the platform pins, which the publisher reads once at
     * construction. Construction fails when a stub template reports none of them.
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
     * Longest the call itself may take, given it no longer waits for the broker.
     *
     * <p>Generous on purpose. The assertion is that the call returns without waiting out the send,
     * not that it returns inside any particular number of milliseconds.
     */
    private static final java.time.Duration RETURNS_PROMPTLY = java.time.Duration.ofSeconds(5L);

    /**
     * Reads the failure one publish reported on its stage.
     *
     * <p>{@code publish} answers a stage rather than waiting, so a broker failure arrives here
     * instead of at the call. {@code join} wraps it in a {@link CompletionException}, and this
     * unwraps that one layer so a test asserts on the cause the broker actually reported.
     *
     * @param stage the stage one publish returned
     * @return the failure it reported
     */
    private static Throwable failureOf(CompletionStage<Void> stage) {
        CompletionException wrapper = assertThrows(CompletionException.class,
                () -> stage.toCompletableFuture().join(),
                "the stage completed normally, so no failure was reported");
        return wrapper.getCause();
    }

    /**
     * A second implementation of {@link EventPublisherPort} that records what it received. It names
     * no Kafka type, which is the property {@link #aSecondImplementationOfThePortNeedsNoKafkaType}
     * asserts.
     */
    private static final class RecordingPublisher implements EventPublisherPort {

        private final List<String> published = new ArrayList<>();

        @Override
        public CompletionStage<Void> publish(String topic, String key, String payload) {
            published.add(topic + '|' + key + '|' + payload);
            return CompletableFuture.completedFuture(null);
        }
    }
}
