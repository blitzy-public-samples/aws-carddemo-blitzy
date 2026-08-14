package com.carddemo.ledger.config;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.carddemo.events.serde.EventContracts;
import com.carddemo.events.serde.JsonSchemaValidatingSerializer;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.lang.reflect.Method;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.header.internals.RecordHeaders;
import org.apache.kafka.common.serialization.ByteArraySerializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.DisplayName;
import org.mockito.ArgumentMatchers;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.boot.kafka.autoconfigure.KafkaProperties;
import org.springframework.kafka.KafkaException;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.kafka.listener.MessageListenerContainer;

/**
 * Holds the dead-letter destination and the serializer bound to it in agreement.
 *
 * <p>This class has no COBOL ancestor. It exists because the two were once in disagreement, and the
 * failure was invisible from either side read alone. The recoverer addresses a spent record to a
 * source-specific topic, {@code transaction.authorized.DLT}, while the template it published through
 * carried the schema-validating serializer. That serializer refuses a topic the event type is not
 * bound to, and {@code EventContracts} binds every governed type to exactly one topic, so the send
 * failed inside the recoverer after every retry had already been spent. The record had nowhere left
 * to go: no dead-letter row, no exception any listener could catch, and a consumer that had already
 * given up on it.
 *
 * <p>Reading either side alone still looks correct today, which is why the agreement is asserted here
 * rather than left to a reviewer. A change to the destination, to the value the recoverer publishes,
 * or to the serializer bound to the template it publishes through breaks one of these tests.
 *
 * <p>Nothing connects anywhere. No broker is contacted, no producer is created, and every assertion
 * reads either a declaration or an in-process serializer call.
 */
@DisplayName("The dead-letter destination and the serializer bound to it")
class DeadLetterRouteSerializerContractTest {

    /** The topic the posted-transaction listener reads, and the source of a spent record. */
    private static final String SOURCE_TOPIC = "transaction.authorized";

    /** The shared topic, which is the fallback and not the destination of a sourced record. */
    private static final String SHARED_DEAD_LETTER_TOPIC = "carddemo.dead-letter";

    /** The suffix the shipped configuration appends to a source topic. */
    private static final String SUFFIX = ".DLT";

    /** The destination a spent record from {@link #SOURCE_TOPIC} is addressed to. */
    private static final String SOURCED_DESTINATION = SOURCE_TOPIC + SUFFIX;

    /** The name of the bean whose value serializer must accept what the recoverer publishes. */
    private static final String TEMPLATE_BEAN_METHOD = "deadLetterKafkaTemplate";

    /** The name of the bean the container factory installs. */
    private static final String HANDLER_BEAN_METHOD = "ledgerConsumerErrorHandler";

    /** The four shipped topic names and the suffix, as {@code application.yml} carries them. */
    private static LedgerProperties properties() {
        return new LedgerProperties(
                new LedgerProperties.Kafka(new LedgerProperties.Kafka.Topics(SOURCE_TOPIC,
                        "transaction.declined", "account.state-changed", "transaction.posted",
                        SHARED_DEAD_LETTER_TOPIC, SUFFIX)),
                new LedgerProperties.Consumer(new LedgerProperties.Consumer.Retry(3, 10L)),
                new LedgerProperties.Outbox(new LedgerProperties.Outbox.Relay(1000L, 100,
                        "ledger-relay", Duration.ofMinutes(2L), 5_000L), 168L),
                new LedgerProperties.Retention(3_600_000L, 90));
    }

    /** One record of the source topic, carrying nothing this route reads except its coordinates. */
    private static ConsumerRecord<String, Object> spentRecord() {
        return new ConsumerRecord<>(SOURCE_TOPIC, 2, 41L, "00000000001", null);
    }

    /** The record the recoverer publishes for {@link #spentRecord()}. */
    private static ProducerRecord<Object, Object> publishedRecord() {
        return KafkaConsumerConfig.sanitizedDeadLetterRecord(spentRecord(),
                new TopicPartition(SOURCED_DESTINATION, -1), new RecordHeaders());
    }

    /**
     * The value type of one bean-method's {@code KafkaTemplate}, read from its declaration.
     *
     * @param method     the declared bean method or the method declaring the parameter
     * @param genericType the generic type to read the second argument of
     * @return the second type argument, which is the template's value type
     */
    private static Type valueTypeOf(Method method, Type genericType) {
        assertInstanceOf(ParameterizedType.class, genericType,
                method.getName() + " must declare its template's value type, because an unparameter"
                        + "ized template accepts any value and moves this failure to run time");
        Type[] arguments = ((ParameterizedType) genericType).getActualTypeArguments();
        assertEquals(2, arguments.length, "a KafkaTemplate carries a key type and a value type");
        return arguments[1];
    }

    @Nested
    @DisplayName("The destination, which is source-specific and not the shared topic")
    class Destination {

        @Test
        @DisplayName("a spent record is addressed to its own source topic plus the suffix")
        void aSpentRecordIsAddressedToItsSourceTopic() {
            TopicPartition resolved = KafkaConsumerConfig.resolveDeadLetterDestination(
                    spentRecord(), SHARED_DEAD_LETTER_TOPIC, SUFFIX);

            assertAll(
                    () -> assertEquals(SOURCED_DESTINATION, resolved.topic(),
                            "the destination names the source, so a spent record can be traced "
                                    + "back to the topic it arrived on"),
                    () -> assertNotEquals(SHARED_DEAD_LETTER_TOPIC, resolved.topic(),
                            "the shared topic is the fallback for a record whose source is "
                                    + "unavailable, and not the destination of a sourced record"));
        }

        @Test
        @DisplayName("no governed event type is bound to that destination")
        void noGovernedTypeIsBoundToThatDestination() {
            assertAll(EventContracts.eventTypes().stream()
                    .map(eventType -> () -> assertFalse(
                            EventContracts.isBoundToTopic(eventType, SOURCED_DESTINATION),
                            eventType + " must not be bound to " + SOURCED_DESTINATION
                                    + ": if it were, an event-shaped value could be addressed "
                                    + "there and the reason this route publishes bytes would have "
                                    + "gone away")));
        }

        @Test
        @DisplayName("the governed envelope type is bound to the shared topic alone")
        void theEnvelopeTypeIsBoundToTheSharedTopicAlone() {
            assertAll(
                    () -> assertTrue(EventContracts.isBoundToTopic(EventContracts.DEAD_LETTER,
                            SHARED_DEAD_LETTER_TOPIC),
                            "the outbox relay publishes the governed envelope, and it publishes it "
                                    + "here"),
                    () -> assertFalse(EventContracts.isBoundToTopic(EventContracts.DEAD_LETTER,
                            SOURCED_DESTINATION),
                            "the envelope cannot be addressed to a sourced destination either, "
                                    + "which is why this route carries a rendered diagnostic and "
                                    + "not an envelope"));
        }
    }

    @Nested
    @DisplayName("The serializer bound to what the recoverer publishes")
    class BoundSerializer {

        @Test
        @DisplayName("the template bean declares a byte value")
        void theTemplateBeanDeclaresAByteValue() throws Exception {
            Method bean = KafkaConsumerConfig.class.getDeclaredMethod(TEMPLATE_BEAN_METHOD,
                    KafkaProperties.class, LedgerProperties.class);

            assertEquals(byte[].class, valueTypeOf(bean, bean.getGenericReturnType()),
                    "the recoverer publishes a rendered diagnostic as bytes, so the template it "
                            + "publishes through must declare a byte value");
        }

        @Test
        @DisplayName("the error handler takes a template of the same value type")
        void theErrorHandlerTakesTheSameValueType() throws Exception {
            Method bean = KafkaConsumerConfig.class.getDeclaredMethod(HANDLER_BEAN_METHOD,
                    KafkaTemplate.class, LedgerProperties.class,
                    ObservabilityConfig.LedgerMeters.class);

            assertEquals(byte[].class, valueTypeOf(bean, bean.getGenericParameterTypes()[0]),
                    "the handler and the template must agree by declaration, so wiring the event "
                            + "template here fails to inject rather than failing at the send");
        }

        @Test
        @DisplayName("the instance bound to the template writes bytes")
        void theInstanceBoundToTheTemplateWritesBytes() {
            KafkaTemplate<String, byte[]> template = new KafkaConsumerConfig()
                    .deadLetterKafkaTemplate(new KafkaProperties(), properties());

            DefaultKafkaProducerFactory<?, ?> factory =
                    assertInstanceOf(DefaultKafkaProducerFactory.class,
                            template.getProducerFactory(),
                            "the bean builds its own factory so it can bind the serializer pair");

            assertAll(
                    () -> assertInstanceOf(ByteArraySerializer.class, factory.getValueSerializer(),
                            "the value the recoverer publishes is a byte array"),
                    () -> assertInstanceOf(StringSerializer.class, factory.getKeySerializer(),
                            "the key is the account identifier as text, so its leading zero "
                                    + "survives"),
                    () -> assertEquals(SHARED_DEAD_LETTER_TOPIC, template.getDefaultTopic(),
                            "the fallback is the shared topic, for a record whose source topic is "
                                    + "unavailable"));
        }

        @Test
        @DisplayName("a globally named value serializer cannot leak onto this route")
        void aGloballyNamedValueSerializerCannotLeakOntoThisRoute() {
            KafkaProperties inherited = new KafkaProperties();
            inherited.getProducer().setKeySerializer(StringSerializer.class);
            inherited.getProducer().setValueSerializer(JsonSchemaValidatingSerializer.class);

            KafkaTemplate<String, byte[]> template =
                    new KafkaConsumerConfig().deadLetterKafkaTemplate(inherited, properties());

            DefaultKafkaProducerFactory<?, ?> factory = assertInstanceOf(
                    DefaultKafkaProducerFactory.class, template.getProducerFactory());
            Map<String, Object> settings =
                    new LinkedHashMap<>(factory.getConfigurationProperties());

            assertAll(
                    () -> assertInstanceOf(ByteArraySerializer.class, factory.getValueSerializer(),
                            "the bound instance decides, and it must remain the byte serializer "
                                    + "even when spring.kafka.producer names another"),
                    () -> assertFalse(
                            settings.containsKey(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG),
                            "the inherited value-serializer setting is removed rather than "
                                    + "overridden, so nothing reads the schema serializer's name "
                                    + "off this factory and configures it"),
                    () -> assertFalse(
                            settings.containsKey(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG),
                            "the inherited key-serializer setting is removed for the same reason"),
                    () -> assertEquals("all", settings.get(ProducerConfig.ACKS_CONFIG),
                            "a dead-letter publication is the last copy of the record, so it waits "
                                    + "for every in-sync replica"),
                    () -> assertEquals(Boolean.TRUE,
                            settings.get(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG),
                            "a retried publication must not duplicate the diagnostic"));
        }
    }

    @Nested
    @DisplayName("The agreement, asserted end to end without a broker")
    class Agreement {

        @Test
        @DisplayName("the value the recoverer publishes is bytes the bound serializer writes")
        void theRecovererValueIsWrittenByTheBoundSerializer() {
            ProducerRecord<Object, Object> outgoing = publishedRecord();

            byte[] value = assertInstanceOf(byte[].class, outgoing.value(),
                    "the recoverer renders the diagnostic before handing it over, so the value is "
                            + "bytes and no serializer has to understand its shape");

            try (ByteArraySerializer bound = new ByteArraySerializer()) {
                byte[] written = assertDoesNotThrow(
                        () -> bound.serialize(outgoing.topic(), value),
                        "the serializer bound to this route cannot write what the recoverer "
                                + "publishes, which is the failure this class exists to prevent");

                assertArrayEquals(value, written,
                        "the diagnostic must reach the broker unchanged, byte for byte");
            }
        }

        @Test
        @DisplayName("the schema serializer would refuse this destination, which is why bytes")
        void theSchemaSerializerWouldRefuseThisDestination() {
            ProducerRecord<Object, Object> outgoing = publishedRecord();

            assertAll(
                    () -> assertEquals(SOURCED_DESTINATION, outgoing.topic(),
                            "the record is addressed to the sourced destination"),
                    () -> assertFalse(
                            EventContracts.isBoundToTopic(EventContracts.TRANSACTION_AUTHORIZED,
                                    outgoing.topic()),
                            "the schema serializer refuses a topic the type is not bound to, so "
                                    + "publishing an event-shaped value here would fail the send "
                                    + "after every retry had been spent, leaving the record with "
                                    + "nowhere to go"));
        }

        @Test
        @DisplayName("the published key is the coordinates and never the refused key")
        void thePublishedKeyIsTheCoordinates() {
            ProducerRecord<Object, Object> outgoing = publishedRecord();

            String key = assertInstanceOf(String.class, outgoing.key(),
                    "the key is text, which is what the bound key serializer writes");

            assertDoesNotThrow(() -> {
                try (StringSerializer bound = new StringSerializer()) {
                    bound.serialize(outgoing.topic(), key);
                }
            }, "the key serializer bound to this route must write the key the recoverer chose");
        }
    }

    @Nested
    @DisplayName("The terminal count the route records")
    class TerminalCount {

        @Test
        @DisplayName("a record the container gives up on is counted once, not once per attempt")
        void aRecordGivenUpOnIsCountedOncePerRecord() {
            SimpleMeterRegistry registry = new SimpleMeterRegistry();

            recoverOnce(registry, false);

            assertAll(
                    () -> assertEquals(1.0D, terminal(registry, "published"),
                            "the backoff is spent by the time a recoverer runs, so this is the one "
                                    + "moment a delivery can be counted as permanently given up on"),
                    () -> assertEquals(0.0D, terminal(registry, "failed"),
                            "the broker took the diagnostic, so nothing was refused"),
                    () -> assertEquals(0.0D, attempts(registry, "process"),
                            "a terminal outcome must not move an attempt series, or a spent record "
                                    + "and a retry would read the same"),
                    () -> assertEquals(0.0D, attempts(registry, "abandon"),
                            "the abandon stage counts an outbox row, never a consumed record"));
        }

        @Test
        @DisplayName("a refused diagnostic is counted apart, because nothing then names the record")
        void aRefusedDiagnosticIsCountedApart() {
            SimpleMeterRegistry registry = new SimpleMeterRegistry();

            recoverOnce(registry, true);

            assertAll(
                    () -> assertEquals(1.0D, terminal(registry, "failed"),
                            "a diagnostic the broker refused leaves the record named nowhere, which "
                                    + "a log line alone does not report"),
                    () -> assertEquals(0.0D, terminal(registry, "published"),
                            "nothing reached the topic"));
        }

        /**
         * Drives one spent delivery through the shipped handler.
         *
         * @param registry      the registry every count lands in
         * @param sendIsRefused whether the dead-letter template throws when asked to send
         */
        @SuppressWarnings("unchecked")
        private void recoverOnce(SimpleMeterRegistry registry, boolean sendIsRefused) {
            KafkaTemplate<String, byte[]> template = mock(KafkaTemplate.class);
            if (sendIsRefused) {
                when(template.send(ArgumentMatchers.<ProducerRecord<String, byte[]>>any()))
                        .thenThrow(new KafkaException("the broker refused the diagnostic"));
            }

            DefaultErrorHandler handler = new KafkaConsumerConfig().ledgerConsumerErrorHandler(
                    template, oneAttemptProperties(),
                    new ObservabilityConfig().ledgerMeters(registry));

            handler.handleOne(new IllegalStateException("the posting did not complete"),
                    new ConsumerRecord<>(SOURCE_TOPIC, 0, 0L, "00000000011", "{}"),
                    mock(Consumer.class), mock(MessageListenerContainer.class));
        }

        /**
         * The shipped topics with one delivery attempt, so the first failure spends the backoff and
         * the recoverer runs. Every other value matches {@link
         * DeadLetterRouteSerializerContractTest#properties()}.
         *
         * @return properties whose retry policy takes one attempt
         */
        private LedgerProperties oneAttemptProperties() {
            return new LedgerProperties(
                    new LedgerProperties.Kafka(new LedgerProperties.Kafka.Topics(SOURCE_TOPIC,
                            "transaction.declined", "account.state-changed",
                            "transaction.posted", SHARED_DEAD_LETTER_TOPIC, SUFFIX)),
                    new LedgerProperties.Consumer(new LedgerProperties.Consumer.Retry(1, 0L)),
                    new LedgerProperties.Outbox(new LedgerProperties.Outbox.Relay(1000L, 100,
                            "ledger-relay", Duration.ofMinutes(2L), 5_000L), 168L),
                    new LedgerProperties.Retention(3_600_000L, 90));
        }

        /**
         * Reads one outcome of the terminal counter across every failure kind, answering zero
         * where it carries no value.
         *
         * <p>The terminal counter carries a second dimension beside the outcome, so one outcome
         * names a series per failure kind rather than a single series. This helper sums them,
         * because these tests assert how many records reached a terminal outcome and leave the
         * attribution of the kind to {@code DeadLetterFailureAttributionTest}.
         *
         * @param registry the registry every count lands in
         * @param outcome  the terminal outcome to total
         * @return the number of records counted under that outcome
         */
        private double terminal(SimpleMeterRegistry registry, String outcome) {
            return sum(registry, "carddemo.ledger.dead.letters", "outcome", outcome);
        }

        /**
         * Reads one stage of the attempt counter, answering zero where it carries no value.
         *
         * @param registry the registry every count lands in
         * @param stage    the stage to total
         * @return the number of failures counted under that stage
         */
        private double attempts(SimpleMeterRegistry registry, String stage) {
            return sum(registry, "carddemo.ledger.failures", "stage", stage);
        }

        /**
         * Totals every series of one meter name that carries one tag value.
         *
         * @param registry the registry every count lands in
         * @param meter    the meter name
         * @param tag      the tag key that selects the series
         * @param value    the tag value that selects the series
         * @return the sum of the selected series, or zero where none is registered
         */
        private double sum(SimpleMeterRegistry registry, String meter, String tag, String value) {
            double total = 0.0D;
            for (Counter counter : registry.find(meter).tag(tag, value).counters()) {
                total += counter.count();
            }
            return total;
        }
    }
}
