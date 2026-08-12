package com.carddemo.fraud.messaging;

import com.carddemo.fraud.config.KafkaConsumerConfig;
import com.carddemo.fraud.config.ObservabilityConfig;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.lang.annotation.Annotation;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.RecordComponent;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.header.Header;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.kafka.listener.MessageListenerContainer;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.kafka.support.SendResult;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Contract tests for {@link DeadLetterMetadata}, the four text values that travel with a message
 * routed to the dead-letter topic plus the record of which of them was shortened.
 *
 * <p>The four widths come from the Common Business Oriented Language (COBOL) abend reporting
 * record, and dead-letter routing itself has no COBOL ancestor. The record declares four
 * {@link String} components capped at 4, 8, 50 and 72
 * characters, which total 134, and no fifth component.
 *
 * <p>The tests assert the component list and the four widths. A value over its maximum keeps its
 * leading characters, a {@code null} value becomes the empty string, and no framework annotation
 * exists. They build the record directly and reach no file, context or broker.
 *
 * <p>This record is service-local. Six service modules each declare their own record of this name. Each
 * one shortens an over-length component rather than refusing it. The record stays inside this
 * service's own package and carries no shared-library package prefix.
 */
@DisplayName("DeadLetterMetadata, the values that travel to the dead-letter topic")
final class DeadLetterMetadataTest {

    private static final String COMPONENT_FAILURE_CODE = "abendCode";

    /** The component that names the failing part. */
    private static final String COMPONENT_CULPRIT = "culprit";

    /** The component that records what failed. */
    private static final String COMPONENT_REASON = "reason";

    /** The component that carries text for whoever reads the dead-letter topic. */
    private static final String COMPONENT_MESSAGE = "message";

    /** The four component names, in the order {@link DeadLetterMetadata} declares them. */
    private static final List<String> EXPECTED_COMPONENT_NAMES = List.of(
            COMPONENT_FAILURE_CODE, COMPONENT_CULPRIT, COMPONENT_REASON, COMPONENT_MESSAGE);

    /** The record declares four text components. */
    private static final int EXPECTED_COMPONENT_COUNT = 4;

    /** The four maximum lengths, in component order. */
    private static final List<Integer> MAXIMUM_LENGTHS = List.of(
            DeadLetterMetadata.ABEND_CODE_MAX_LENGTH,
            DeadLetterMetadata.CULPRIT_MAX_LENGTH,
            DeadLetterMetadata.REASON_MAX_LENGTH,
            DeadLetterMetadata.MESSAGE_MAX_LENGTH);

    /** The total the four maximum lengths add up to. */
    private static final int EXPECTED_TOTAL_LENGTH = 134;

    /** Fragments that appear in no component name. */
    private static final List<String> FORBIDDEN_NAME_FRAGMENTS = List.of(
            "time", "stack", "payload", "offset", "partition", "topic", "key", "retry");

    /** Package prefixes of the frameworks that annotate no part of the record. */
    private static final List<String> FRAMEWORK_PACKAGE_PREFIXES = List.of(
            "tools.jackson", "com.fasterxml.jackson", "org.springframework", "jakarta.persistence");

    /** The count of letters cycled to build a value whose characters vary along its length. */
    private static final int ALPHABET_SIZE = 26;

    /** A value short enough for every one of the four components. */
    private static final String SHORT_VALUE = "abc";

    /** Package this record belongs to. */
    private static final String OWNING_PACKAGE = "com.carddemo.fraud.messaging";

    /** Package prefixes this record must not carry. */
    private static final List<String> SHARED_LIBRARY_PACKAGE_PREFIXES = List.of(
            "com.carddemo.events", "com.carddemo.cobol");

    /**
     * Asserts that the record declares exactly four text components, each typed {@link String}.
     */
    @Test
    @DisplayName("Declares four String components and no more")
    void declaresFourStringComponentsInOrder() {
        RecordComponent[] components = DeadLetterMetadata.class.getRecordComponents();
        List<String> names = Arrays.stream(components).map(RecordComponent::getName).toList();
        List<String> textTypeNames = Arrays.stream(components)
                .map(component -> component.getType().getName()).toList();

        assertAll("component list of DeadLetterMetadata",
                () -> assertEquals(EXPECTED_COMPONENT_COUNT, components.length,
                        "component count"),
                () -> assertEquals(EXPECTED_COMPONENT_NAMES, names, "names in index order"),
                () -> assertEquals(
                        Collections.nCopies(EXPECTED_COMPONENT_COUNT, String.class.getName()),
                        textTypeNames, "text component types in index order"));
    }

    @Test
    @DisplayName("Caps the four components at 4, 8, 50 and 72 characters, which total 134")
    void capsEachComponentAtItsDeclaredWidth() {
        assertAll("maximum lengths",
                () -> assertEquals(4, DeadLetterMetadata.ABEND_CODE_MAX_LENGTH, "first maximum"),
                () -> assertEquals(8, DeadLetterMetadata.CULPRIT_MAX_LENGTH, "second maximum"),
                () -> assertEquals(50, DeadLetterMetadata.REASON_MAX_LENGTH, "third maximum"),
                () -> assertEquals(72, DeadLetterMetadata.MESSAGE_MAX_LENGTH, "fourth maximum"),
                () -> assertEquals(EXPECTED_TOTAL_LENGTH,
                        DeadLetterMetadata.ABEND_CODE_MAX_LENGTH + DeadLetterMetadata.CULPRIT_MAX_LENGTH
                                + DeadLetterMetadata.REASON_MAX_LENGTH
                                + DeadLetterMetadata.MESSAGE_MAX_LENGTH,
                        "total of the four maximums"));
    }

    @Test
    @DisplayName("Keeps a value of exactly the maximum length unchanged")
    void keepsAValueOfExactlyTheMaximumLength() {
        for (int index = 0; index < EXPECTED_COMPONENT_COUNT; index++) {
            int maximum = MAXIMUM_LENGTHS.get(index);
            String atMaximum = valueOfLength(maximum);

            String stored = componentValues(withComponentAt(index, atMaximum)).get(index);

            assertEquals(atMaximum, stored, "component " + index + " at length " + maximum);
        }
    }

    @Test
    @DisplayName("Keeps the leading characters of a value one character over the maximum length")
    void keepsLeadingCharactersOfAnOverLongValue() {
        for (int index = 0; index < EXPECTED_COMPONENT_COUNT; index++) {
            int maximum = MAXIMUM_LENGTHS.get(index);
            String overLong = valueOfLength(maximum + 1);

            String stored = componentValues(withComponentAt(index, overLong)).get(index);

            assertAll("component " + index + " at length " + (maximum + 1),
                    () -> assertEquals(maximum, stored.length(), "stored length"),
                    () -> assertEquals(overLong.substring(0, maximum), stored, "leading characters"),
                    () -> assertNotEquals(overLong.substring(1), stored, "trailing characters"));
        }
    }

    @Test
    @DisplayName("Throws nothing when all four components arrive over their maximum lengths")
    void throwsNothingForOverLongComponents() {
        DeadLetterMetadata metadata = assertDoesNotThrow(() -> DeadLetterMetadata.of(
                        valueOfLength(DeadLetterMetadata.ABEND_CODE_MAX_LENGTH + 1),
                        valueOfLength(DeadLetterMetadata.CULPRIT_MAX_LENGTH + 1),
                        valueOfLength(DeadLetterMetadata.REASON_MAX_LENGTH + 1),
                        valueOfLength(DeadLetterMetadata.MESSAGE_MAX_LENGTH + 1)),
                "construction with four over-long values");

        List<String> stored = componentValues(metadata);
        for (int index = 0; index < EXPECTED_COMPONENT_COUNT; index++) {
            int maximum = MAXIMUM_LENGTHS.get(index);
            assertEquals(maximum, stored.get(index).length(), "component " + index + " length");
        }
    }

    @Test
    @DisplayName("Turns one null component into the empty string and leaves the other three alone")
    void turnsOneNullComponentIntoTheEmptyString() {
        for (int index = 0; index < EXPECTED_COMPONENT_COUNT; index++) {
            List<String> stored = componentValues(withComponentAt(index, null));

            for (int position = 0; position < EXPECTED_COMPONENT_COUNT; position++) {
                String value = stored.get(position);
                String where = "component " + position + ", null supplied at " + index;
                assertNotNull(value, where);
                assertEquals(position == index ? "" : SHORT_VALUE, value, where);
            }
        }
    }

    @Test
    @DisplayName("Turns four null components into four empty strings and pads none of them")
    void turnsFourNullComponentsIntoFourEmptyStrings() {
        List<String> stored = componentValues(DeadLetterMetadata.of(null, null, null, null));

        assertEquals(EXPECTED_COMPONENT_COUNT, stored.size(), "value count");
        for (int index = 0; index < EXPECTED_COMPONENT_COUNT; index++) {
            String value = stored.get(index);
            String padded = " ".repeat(MAXIMUM_LENGTHS.get(index));

            assertAll("component " + index,
                    () -> assertNotNull(value, "never null"),
                    () -> assertEquals("", value, "empty string"),
                    () -> assertNotEquals(" ", value, "not one space"),
                    () -> assertNotEquals(padded, value, "not padded to the component width"));
        }
    }

    /**
     * Asserts that the record carries no component beyond the four text values, and that no
     * component name matches a message-coordinate fragment.
     */
    @Test
    @DisplayName("Carries no extra component and no name matching a message-coordinate fragment")
    void carriesNoComponentBeyondTheDeclaredFour() {
        RecordComponent[] components = DeadLetterMetadata.class.getRecordComponents();

        assertEquals(EXPECTED_COMPONENT_COUNT, components.length, "component count");
        for (RecordComponent component : components) {
            String name = component.getName().toLowerCase(Locale.ROOT);
            for (String fragment : FORBIDDEN_NAME_FRAGMENTS) {
                assertFalse(name.contains(fragment),
                        "component " + component.getName() + " matches the fragment " + fragment);
            }
        }
    }

    @Test
    @DisplayName("Carries no framework annotation on the record or on any component")
    void carriesNoFrameworkAnnotation() {
        List<String> declared =
                new ArrayList<>(typeNamesOf(DeadLetterMetadata.class.getAnnotations()));
        for (RecordComponent component : DeadLetterMetadata.class.getRecordComponents()) {
            declared.addAll(typeNamesOf(component.getAnnotations()));
        }

        for (String annotationTypeName : declared) {
            for (String prefix : FRAMEWORK_PACKAGE_PREFIXES) {
                assertFalse(annotationTypeName.startsWith(prefix),
                        "annotation " + annotationTypeName + " comes from " + prefix);
            }
        }
    }

    @Test
    @DisplayName("Declares exactly the two named static factories")
    void declaresExactlyTheTwoNamedStaticFactories() {
        List<String> factories = Arrays.stream(DeadLetterMetadata.class.getDeclaredMethods())
                .filter(method -> Modifier.isStatic(method.getModifiers()))
                .filter(method -> DeadLetterMetadata.class.equals(method.getReturnType()))
                .map(Method::getName)
                .sorted()
                .toList();

        assertEquals(List.of("fromFailure", "of"), factories, "static factory list " + factories);
    }

    @Test
    @DisplayName("Replaces every character outside printable ASCII with one substitute character")
    void replacesEveryCharacterOutsidePrintableAscii() {
        DeadLetterMetadata metadata = DeadLetterMetadata.of(
                "A\u0000B", "C\r\nD", "E\u00e9F", "G\ud83d\ude00H");

        assertAll("substituted values",
                () -> assertEquals("A.B", metadata.abendCode(), "control character"),
                () -> assertEquals("C..D", metadata.culprit(), "carriage return and line feed"),
                () -> assertEquals("E.F", metadata.reason(), "accented letter"),
                () -> assertEquals("G.H", metadata.message(), "supplementary code point"));
    }

    /**
     * Asserts that the constructor shortens every component that arrives over its maximum, and
     * shortens no component that arrives within it.
     */
    @Test
    @DisplayName("Shortens exactly the components that arrive over their maximum")
    void shortensExactlyTheComponentsThatArriveOverTheirMaximum() {
        DeadLetterMetadata everythingShortened = DeadLetterMetadata.of(
                valueOfLength(DeadLetterMetadata.ABEND_CODE_MAX_LENGTH + 1),
                valueOfLength(DeadLetterMetadata.CULPRIT_MAX_LENGTH + 1),
                valueOfLength(DeadLetterMetadata.REASON_MAX_LENGTH + 1),
                valueOfLength(DeadLetterMetadata.MESSAGE_MAX_LENGTH + 1));

        List<String> stored = componentValues(everythingShortened);
        for (int index = 0; index < EXPECTED_COMPONENT_COUNT; index++) {
            assertEquals(MAXIMUM_LENGTHS.get(index), stored.get(index).length(),
                    "component " + EXPECTED_COMPONENT_NAMES.get(index) + " was shortened");
        }

        DeadLetterMetadata oneShortened = DeadLetterMetadata.of(SHORT_VALUE, SHORT_VALUE,
                valueOfLength(DeadLetterMetadata.REASON_MAX_LENGTH + 1), SHORT_VALUE);

        assertAll("only the third component was shortened",
                () -> assertEquals(SHORT_VALUE, oneShortened.abendCode()),
                () -> assertEquals(SHORT_VALUE, oneShortened.culprit()),
                () -> assertEquals(DeadLetterMetadata.REASON_MAX_LENGTH,
                        oneShortened.reason().length()),
                () -> assertEquals(SHORT_VALUE, oneShortened.message()));
    }

    /**
     * Asserts that the canonical constructor sanitises the same way the factory does, so a caller
     * that bypasses {@link DeadLetterMetadata#of(String, String, String, String)} gains nothing.
     */
    @Test
    @DisplayName("The canonical constructor sanitises the same way the factory does")
    void theCanonicalConstructorSanitisesTheSameWayTheFactoryDoes() {
        DeadLetterMetadata built = new DeadLetterMetadata(SHORT_VALUE, SHORT_VALUE, SHORT_VALUE,
                SHORT_VALUE);

        assertEquals(DeadLetterMetadata.of(SHORT_VALUE, SHORT_VALUE, SHORT_VALUE, SHORT_VALUE),
                built, "the canonical constructor and the factory agree");
    }

    @Test
    @DisplayName("Fits the failure type name inside the culprit width and empties a null failure")
    void factoryFitsTheFailureTypeNameInsideTheCulpritWidth() {
        int culpritMaximum = DeadLetterMetadata.CULPRIT_MAX_LENGTH;
        String failureTypeName = IllegalStateException.class.getSimpleName();

        DeadLetterMetadata named = DeadLetterMetadata.fromFailure(
                valueOfLength(DeadLetterMetadata.ABEND_CODE_MAX_LENGTH), new IllegalStateException(),
                SHORT_VALUE, SHORT_VALUE);
        DeadLetterMetadata absent = DeadLetterMetadata.fromFailure(null, null, null, null);

        assertAll("factory output",
                () -> assertEquals(failureTypeName.substring(0, culpritMaximum), named.culprit(),
                        "leading characters of the failure type name"),
                () -> assertTrue(named.culprit().length() <= culpritMaximum, "culprit width"),
                () -> assertTrue(withinMaximumLengths(named), "every value fits its component"),
                () -> assertEquals("", absent.culprit(), "culprit for a null failure"),
                () -> assertEquals(EXPECTED_COMPONENT_COUNT,
                        componentValues(absent).stream().filter(String::isEmpty).count(),
                        "empty value count for four nulls"));
    }

    @Test
    @DisplayName("Belongs to this service alone and to no shared library")
    void belongsToThisServiceAloneAndToNoSharedLibrary() {
        String actualPackage = DeadLetterMetadata.class.getPackageName();

        assertEquals(OWNING_PACKAGE, actualPackage,
                "DeadLetterMetadata belongs to the fraud detection service alone");
        for (String prefix : SHARED_LIBRARY_PACKAGE_PREFIXES) {
            assertFalse(actualPackage.startsWith(prefix),
                    "a shared contract would sit under " + prefix
                            + ", and this record does not");
        }
    }

    @Test
    @DisplayName("Truncates an over-length component instead of refusing it")
    void truncatesAnOverLengthComponentInsteadOfRefusingIt() {
        int culpritMaximum = DeadLetterMetadata.CULPRIT_MAX_LENGTH;
        String overLength = valueOfLength(culpritMaximum + 1);

        DeadLetterMetadata metadata = assertDoesNotThrow(
                () -> DeadLetterMetadata.of(SHORT_VALUE, overLength, SHORT_VALUE, SHORT_VALUE),
                "building the record raised a failure of its own");

        assertEquals(culpritMaximum, metadata.culprit().length(),
                "the culprit holds its leading " + culpritMaximum + " characters");
        assertEquals(overLength.substring(0, culpritMaximum), metadata.culprit(),
                "the culprit keeps the leading characters of the value supplied");
        assertNotEquals(overLength, metadata.culprit(),
                "the culprit differs from the value supplied, so the record truncated it");
    }

    @Nested
    @DisplayName("The real recoverer over a record carrying several sensitive forms")
    class RealRecoverer {

        private static final String SOURCE_TOPIC = "transaction.authorized";
        private static final String DEAD_LETTER_TOPIC = "carddemo.dead-letter";

        private static final String DEAD_LETTER_SUFFIX = ".DLT";
        private static final String ACCOUNT_KEY = "00000000007";
        private static final int SOURCE_PARTITION = 1;
        private static final long SOURCE_OFFSET = 42L;
        private static final Map<String, String> SENSITIVE_BY_LABEL = new LinkedHashMap<>();

        static {
            SENSITIVE_BY_LABEL.put("cardNumber", "9999" + "452612877065");
            SENSITIVE_BY_LABEL.put("cardVerificationValue", "731");
            SENSITIVE_BY_LABEL.put("socialSecurityNumber", "999" + "000001");
            SENSITIVE_BY_LABEL.put("credential", "generated-secret-for-recoverer-test");
        }

        private static final String RAW_PAYLOAD = rawPayload();

        @Test
        @DisplayName("the recoverer sends a safe diagnostic record and no failing payload value")
        void recovererSendsSafeEnvelopeWithoutSensitiveValues() {
            ProducerRecord<String, byte[]> sent = recover(
                    new IllegalStateException("processing failed for " + RAW_PAYLOAD));

            assertEquals(SOURCE_TOPIC + DEAD_LETTER_SUFFIX, sent.topic(),
                    "the per-source dead-letter topic, so one poison record is traceable to the "
                            + "topic it arrived on");
            assertEquals(SOURCE_TOPIC + "-" + SOURCE_PARTITION + "-" + SOURCE_OFFSET, sent.key(),
                    "the broker coordinates of the failed record replace the producer-supplied key");
            String record = new String(
                    assertInstanceOf(byte[].class, sent.value(), "outgoing value"),
                    java.nio.charset.StandardCharsets.UTF_8);
            assertEquals(DeadLetterMetadata.RECORD_LENGTH, record.length(),
                    "the fixed-width diagnostic record the four-field abend layout defines");

            String rendered = rendered(sent);
            SENSITIVE_BY_LABEL.forEach((label, value) -> {
                assertFalse(rendered.contains(label), "record carries label " + label);
                assertFalse(rendered.contains(value), "record carries value under " + label);
            });
            assertFalse(rendered.contains(RAW_PAYLOAD), "record carries the failing payload");
            assertNull(sent.headers().lastHeader(KafkaHeaders.DLT_EXCEPTION_MESSAGE),
                    "exception message header");
            assertNull(sent.headers().lastHeader(KafkaHeaders.DLT_EXCEPTION_STACKTRACE),
                    "exception stack header");
        }

        @SuppressWarnings("unchecked")
        private ProducerRecord<String, byte[]> recover(Exception failure) {
            KafkaTemplate<String, byte[]> template = mock(KafkaTemplate.class);
            when(template.send(any(ProducerRecord.class)))
                    .thenAnswer(invocation -> CompletableFuture.completedFuture(
                            sendResultFor(invocation.getArgument(0))));
            KafkaConsumerConfig configuration = new KafkaConsumerConfig(
                    "localhost:9092", SOURCE_TOPIC, "fraud-detection", DEAD_LETTER_TOPIC,
                    DEAD_LETTER_SUFFIX, 1L, 0L);
            DefaultErrorHandler handler = configuration.transactionAuthorizedErrorHandler(
                    template, new ObservabilityConfig().fraudMeters(new SimpleMeterRegistry()));
            ConsumerRecord<String, Object> failing = new ConsumerRecord<>(
                    SOURCE_TOPIC, SOURCE_PARTITION, SOURCE_OFFSET, ACCOUNT_KEY, RAW_PAYLOAD);

            handler.handleOne(failure, failing, mock(Consumer.class),
                    mock(MessageListenerContainer.class));

            ArgumentCaptor<ProducerRecord<String, byte[]>> sent =
                    ArgumentCaptor.forClass(ProducerRecord.class);
            verify(template).send(sent.capture());
            return sent.getValue();
        }

        private static SendResult<String, byte[]> sendResultFor(
                ProducerRecord<String, byte[]> record) {
            return new SendResult<>(record, new RecordMetadata(
                    new TopicPartition(record.topic(), 0), 0L, 0, 0L, 0, 0));
        }

        private static String rawPayload() {
            StringBuilder payload = new StringBuilder("{");
            for (Map.Entry<String, String> field : SENSITIVE_BY_LABEL.entrySet()) {
                if (payload.length() > 1) {
                    payload.append(',');
                }
                payload.append('"').append(field.getKey()).append("\":\"")
                        .append(field.getValue()).append('"');
            }
            return payload.append('}').toString();
        }

        private static String rendered(ProducerRecord<String, byte[]> sent) {
            StringBuilder text = new StringBuilder(sent.topic())
                    .append(' ').append(sent.key())
                    .append(' ').append(sent.value() == null ? "" : new String(sent.value(),
                            java.nio.charset.StandardCharsets.UTF_8));
            for (Header header : sent.headers()) {
                text.append(' ').append(header.key()).append('=')
                        .append(header.value() == null ? "" : new String(
                                header.value(), java.nio.charset.StandardCharsets.UTF_8));
            }
            return text.toString();
        }
    }

    private static List<String> componentValues(DeadLetterMetadata metadata) {
        return Arrays.asList(metadata.abendCode(), metadata.culprit(), metadata.reason(),
                metadata.message());
    }

    private static DeadLetterMetadata withComponentAt(int index, String value) {
        String[] values = {SHORT_VALUE, SHORT_VALUE, SHORT_VALUE, SHORT_VALUE};
        values[index] = value;
        return DeadLetterMetadata.of(values[0], values[1], values[2], values[3]);
    }

    private static String valueOfLength(int length) {
        StringBuilder builder = new StringBuilder(length);
        for (int position = 0; position < length; position++) {
            builder.append((char) ('a' + position % ALPHABET_SIZE));
        }
        return builder.toString();
    }

    private static boolean withinMaximumLengths(DeadLetterMetadata metadata) {
        List<String> values = componentValues(metadata);
        for (int index = 0; index < EXPECTED_COMPONENT_COUNT; index++) {
            if (values.get(index).length() > MAXIMUM_LENGTHS.get(index)) {
                return false;
            }
        }
        return true;
    }

    private static List<String> typeNamesOf(Annotation[] annotations) {
        return Arrays.stream(annotations)
                .map(annotation -> annotation.annotationType().getName())
                .toList();
    }
}
