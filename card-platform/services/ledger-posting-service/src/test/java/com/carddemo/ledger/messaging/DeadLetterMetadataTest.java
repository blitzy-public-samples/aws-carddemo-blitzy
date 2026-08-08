package com.carddemo.ledger.messaging;

import com.carddemo.ledger.config.KafkaConsumerConfig;
import com.carddemo.ledger.config.ObservabilityConfig;
import com.carddemo.ledger.config.LedgerProperties;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.regex.Pattern;

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
import org.mockito.ArgumentMatchers;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.kafka.listener.MessageListenerContainer;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.kafka.support.SendResult;
import org.springframework.kafka.support.serializer.DeserializationException;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertIterableEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Asserts the width invariants of {@link DeadLetterMetadata} against the abend reporting record
 * {@code 01 ABEND-DATA} at {@code app/cpy/CSMSG02Y.cpy:L21-L29}. Each component is exercised at
 * its maximum length and one character past it.
 *
 * <p>The constructor throws nothing. A dead-letter record describes a failure that already
 * happened, so a second failure raised while building it would suppress the dead-letter message
 * and leave the broker redelivering for ever. An over-long component therefore keeps its leading
 * characters.</p>
 *
 * <p>No COBOL ancestor. Dead-letter routing has no CardDemo ancestor: the posting job sends
 * rejected records to a fresh generation of an output dataset at {@code
 * app/jcl/POSTTRAN.jcl:L34-L38}, which no program reads back.</p>
 *
 * <p>This record is service-local. Six service modules each declare their own record of this name. Each
 * one shortens an over-length component rather than refusing it. The record stays inside this
 * service's own package and carries no shared-library package prefix.</p>
 */
class DeadLetterMetadataTest {

    /**
     * Maximum length of the first component, from {@code ABEND-CODE PIC X(4)} at
     * {@code app/cpy/CSMSG02Y.cpy:L22}.
     */
    private static final int ABEND_CODE_MAX_LENGTH = 4;

    /**
     * Maximum length of the second component, from {@code ABEND-CULPRIT PIC X(8)} at
     * {@code app/cpy/CSMSG02Y.cpy:L24}.
     */
    private static final int CULPRIT_MAX_LENGTH = 8;

    /**
     * Maximum length of the third component, from {@code ABEND-REASON PIC X(50)} at
     * {@code app/cpy/CSMSG02Y.cpy:L26}.
     */
    private static final int REASON_MAX_LENGTH = 50;

    /**
     * Maximum length of the fourth component, from {@code ABEND-MSG PIC X(72)} at
     * {@code app/cpy/CSMSG02Y.cpy:L28}.
     */
    private static final int MESSAGE_MAX_LENGTH = 72;

    /** A failure code at {@link #ABEND_CODE_MAX_LENGTH}. */
    private static final String ABEND_CODE = "LDGR";

    /**
     * A program name at {@link #CULPRIT_MAX_LENGTH}, the value {@code ABEND-CULPRIT} carries in
     * the CardDemo source.
     */
    private static final String CULPRIT = "CBTRN02C";

    /** A failure classification shorter than {@link #REASON_MAX_LENGTH}. */
    private static final String REASON = "CATEGORY BALANCE UPSERT FAILED";

    /**
     * An exception simple name shorter than {@link #MESSAGE_MAX_LENGTH}, the shape a consumer
     * supplies for the fourth component.
     */
    private static final String MESSAGE = "AccountBalanceRowMissingException";

    /** Twelve or more consecutive digits, the shape of a card number. */
    private static final Pattern LONG_DIGIT_RUN = Pattern.compile("\\d{12,}");

    /** A three-digit run with no digit on either side. */
    private static final Pattern STANDALONE_THREE_DIGIT_RUN =
            Pattern.compile("(?<!\\d)\\d{3}(?!\\d)");

    /** Package this record belongs to. A shared, interoperable payload would sit elsewhere. */
    private static final String OWNING_PACKAGE = "com.carddemo.ledger.messaging";

    /**
     * Asserts that values at the four maximum lengths construct and survive unchanged. Widths from
     * {@code app/cpy/CSMSG02Y.cpy:L21-L29}.
     */
    @Test
    @DisplayName("Values at the four maximum lengths construct and survive unchanged")
    void constructorAcceptsValuesAtEachMaximumLength() {
        String codeAtMaximum = "A".repeat(ABEND_CODE_MAX_LENGTH);
        String culpritAtMaximum = "B".repeat(CULPRIT_MAX_LENGTH);
        String classificationAtMaximum = "C".repeat(REASON_MAX_LENGTH);
        String detailAtMaximum = "D".repeat(MESSAGE_MAX_LENGTH);

        DeadLetterMetadata metadata = DeadLetterMetadata.of(
                codeAtMaximum, culpritAtMaximum, classificationAtMaximum, detailAtMaximum);

        assertEquals(codeAtMaximum, metadata.abendCode());
        assertEquals(culpritAtMaximum, metadata.culprit());
        assertEquals(classificationAtMaximum, metadata.reason());
        assertEquals(detailAtMaximum, metadata.message());
        assertEquals(ABEND_CODE_MAX_LENGTH, metadata.abendCode().length());
        assertEquals(CULPRIT_MAX_LENGTH, metadata.culprit().length());
        assertEquals(REASON_MAX_LENGTH, metadata.reason().length());
        assertEquals(MESSAGE_MAX_LENGTH, metadata.message().length());
    }

    /**
     * Asserts that a one-character code stays one character. Each width at
     * {@code app/cpy/CSMSG02Y.cpy:L21-L29} caps a component and does not fix its size.
     */
    @Test
    @DisplayName("Short values keep their own length")
    void constructorPadsNothingAndTrimsNothing() {
        DeadLetterMetadata metadata = DeadLetterMetadata.of("L", CULPRIT, REASON, MESSAGE);

        assertEquals("L", metadata.abendCode());
        assertEquals(1, metadata.abendCode().length());
        assertEquals(CULPRIT, metadata.culprit());
        assertEquals(REASON, metadata.reason());
        assertEquals(MESSAGE, metadata.message());
        assertEquals(REASON.length(), metadata.reason().length());
        assertEquals(MESSAGE.length(), metadata.message().length());
    }

    /**
     * Asserts that a component one character over its maximum keeps its leading characters, and
     * that the constructor throws nothing. Widths from {@code app/cpy/CSMSG02Y.cpy:L21-L29}.
     */
    @Test
    @DisplayName("A component over its maximum is shortened, and nothing throws")
    void constructorShortensAnOverLongComponent() {
        String longCode = "A".repeat(ABEND_CODE_MAX_LENGTH + 1);
        String longCulprit = "B".repeat(CULPRIT_MAX_LENGTH + 1);
        String longReason = "C".repeat(REASON_MAX_LENGTH + 1);
        String longDetail = "D".repeat(MESSAGE_MAX_LENGTH + 1);

        DeadLetterMetadata metadata = assertDoesNotThrow(() -> DeadLetterMetadata.of(
                longCode, longCulprit, longReason, longDetail));

        assertEquals("A".repeat(ABEND_CODE_MAX_LENGTH), metadata.abendCode());
        assertEquals("B".repeat(CULPRIT_MAX_LENGTH), metadata.culprit());
        assertEquals("C".repeat(REASON_MAX_LENGTH), metadata.reason());
        assertEquals("D".repeat(MESSAGE_MAX_LENGTH), metadata.message());
    }

    /**
     * Asserts that shortening one over-long component leaves the other three as they were supplied.
     */
    @Test
    @DisplayName("Only the component actually over its maximum is shortened")
    void onlyTheComponentOverItsMaximumIsShortened() {
        DeadLetterMetadata onlyReason = DeadLetterMetadata.of(
                ABEND_CODE, CULPRIT, "C".repeat(REASON_MAX_LENGTH + 1), MESSAGE);

        assertEquals("C".repeat(REASON_MAX_LENGTH), onlyReason.reason());
        assertEquals(ABEND_CODE, onlyReason.abendCode());
        assertEquals(CULPRIT, onlyReason.culprit());
        assertEquals(MESSAGE, onlyReason.message());

        DeadLetterMetadata nothingShortened =
                DeadLetterMetadata.of(ABEND_CODE, CULPRIT, REASON, MESSAGE);

        assertIterableEquals(List.of(ABEND_CODE, CULPRIT, REASON, MESSAGE),
                List.of(nothingShortened.abendCode(), nothingShortened.culprit(),
                        nothingShortened.reason(), nothingShortened.message()),
                "a record within every maximum reached its components unchanged");
    }

    /**
     * Asserts that a Java class name is shortened to the culprit width rather than refused. The
     * component holds an eight-character program name at {@code app/cpy/CSMSG02Y.cpy:L24}.
     */
    @Test
    @DisplayName("A Java class name is shortened to the culprit width")
    void constructorShortensAJavaClassNameToTheCulpritWidth() {
        String className = "TransactionCategoryBalanceEntity";

        assertTrue(className.length() > CULPRIT_MAX_LENGTH, className);

        DeadLetterMetadata metadata = assertDoesNotThrow(() -> DeadLetterMetadata.of(
                ABEND_CODE, className, REASON, MESSAGE));

        assertEquals(className.substring(0, CULPRIT_MAX_LENGTH), metadata.culprit());
    }

    @Test
    @DisplayName("Every character outside printable ASCII becomes one substitute character")
    void constructorReplacesEveryCharacterOutsidePrintableAscii() {
        DeadLetterMetadata metadata = DeadLetterMetadata.of(
                "A\u0000B", "C\r\nD", "E\u00e9F", "G\ud83d\ude00H");

        assertEquals("A.B", metadata.abendCode());
        assertEquals("C..D", metadata.culprit());
        assertEquals("E.F", metadata.reason());
        assertEquals("G.H", metadata.message());

        String rendered = metadata.toString();

        assertFalse(rendered.contains("\n"), "the rendered record carried a line break");
        assertFalse(rendered.contains("\u0000"), "the rendered record carried a control character");
    }

    @Test
    @DisplayName("The failure factory copies the failure type and no failure text")
    void failureFactoryCopiesOnlyTheFailureType() {
        String cardShapedValue = "9999" + "452612877065";
        String amountShapedValue = "1250.75";
        Exception failure = new IllegalStateException(
                "card " + cardShapedValue + " balance " + amountShapedValue);

        DeadLetterMetadata metadata =
                DeadLetterMetadata.fromFailure(ABEND_CODE, failure, REASON, MESSAGE);

        assertEquals("IllegalS", metadata.culprit());
        assertFalse(metadata.toString().contains(cardShapedValue),
                "the record carried a card number from the failure text");
        assertFalse(metadata.toString().contains(amountShapedValue),
                "the record carried a monetary value from the failure text");

        DeadLetterMetadata noFailure =
                DeadLetterMetadata.fromFailure(ABEND_CODE, null, REASON, MESSAGE);

        assertEquals("", noFailure.culprit());
    }

    /**
     * Asserts that an exception simple name is accepted as the fourth component, whose width comes
     * from {@code app/cpy/CSMSG02Y.cpy:L28}.
     */
    @Test
    @DisplayName("An exception simple name is accepted as the fourth component")
    void constructorAcceptsExceptionSimpleNameAsMessage() {
        String shortName = "DataAccessException";

        DeadLetterMetadata fromLongName =
                DeadLetterMetadata.of(ABEND_CODE, CULPRIT, REASON, MESSAGE);
        DeadLetterMetadata fromShortName =
                DeadLetterMetadata.of(ABEND_CODE, CULPRIT, REASON, shortName);

        assertEquals(MESSAGE, fromLongName.message());
        assertEquals(shortName, fromShortName.message());
        assertTrue(MESSAGE.length() <= MESSAGE_MAX_LENGTH, MESSAGE);
    }

    /**
     * Asserts that equality and hash code cover the four text components of the record at
     * {@code app/cpy/CSMSG02Y.cpy:L21-L29}.
     */
    @Test
    @DisplayName("Equality and hash code cover all four text components")
    void equalityCoversAllFourTextComponents() {
        DeadLetterMetadata first = DeadLetterMetadata.of(ABEND_CODE, CULPRIT, REASON, MESSAGE);
        DeadLetterMetadata second = DeadLetterMetadata.of(ABEND_CODE, CULPRIT, REASON, MESSAGE);

        assertEquals(first, second);
        assertEquals(first.hashCode(), second.hashCode());

        assertNotEquals(first, DeadLetterMetadata.of("POST", CULPRIT, REASON, MESSAGE));
        assertNotEquals(first, DeadLetterMetadata.of(ABEND_CODE, "POSTTRAN", REASON, MESSAGE));
        assertNotEquals(first,
                DeadLetterMetadata.of(ABEND_CODE, CULPRIT, "ACCOUNT BALANCE ROW ABSENT", MESSAGE));
        assertNotEquals(first,
                DeadLetterMetadata.of(ABEND_CODE, CULPRIT, REASON, "DataAccessException"));
    }

    /**
     * Asserts that the rendered record carries no long digit run and no standalone three-digit run.
     * The record at {@code app/cpy/CSMSG02Y.cpy:L21-L29} carries no card data of any kind.
     *
     * <p>Each failure message below is fixed text. A digit run these checks caught would be the
     * very text that must not reach a log, so the message names the check and prints nothing the
     * rendering held.</p>
     */
    @Test
    @DisplayName("Rendered text carries no digit run that could be card data")
    void toStringCarriesNoCardDigits() {
        DeadLetterMetadata metadata = DeadLetterMetadata.of(ABEND_CODE, CULPRIT, REASON, MESSAGE);

        String rendered = metadata.toString();

        assertNotNull(rendered, "the rendered record was null");
        assertFalse(LONG_DIGIT_RUN.matcher(rendered).find(),
                "the rendered record carries a digit run long enough to be a card number");
        assertFalse(STANDALONE_THREE_DIGIT_RUN.matcher(rendered).find(),
                "the rendered record carries a standalone three-digit run");
    }

    /**
     * Asserts that a null component becomes the empty string, matching the {@code VALUE SPACES}
     * default each field carries at {@code app/cpy/CSMSG02Y.cpy:L21-L29}.
     */
    @Test
    @DisplayName("A null component becomes the empty string")
    void constructorTurnsNullComponentsIntoEmptyStrings() {
        DeadLetterMetadata allNull = DeadLetterMetadata.of(null, null, null, null);

        assertEquals("", allNull.abendCode());
        assertEquals("", allNull.culprit());
        assertEquals("", allNull.reason());
        assertEquals("", allNull.message());

        DeadLetterMetadata oneNull = DeadLetterMetadata.of(ABEND_CODE, null, REASON, MESSAGE);

        assertEquals("", oneNull.culprit());
        assertEquals(ABEND_CODE, oneNull.abendCode());
        assertEquals(REASON, oneNull.reason());
        assertEquals(MESSAGE, oneNull.message());
    }

    @Test
    @DisplayName("Belongs to this service alone and to no shared library")
    void recordBelongsToThisServiceAloneAndToNoSharedLibrary() {
        String actualPackage = DeadLetterMetadata.class.getPackageName();

        assertEquals(OWNING_PACKAGE, actualPackage,
                "DeadLetterMetadata belongs to the ledger posting service alone");
        assertFalse(actualPackage.startsWith("com.carddemo.events"),
                "a shared event contract would sit under com.carddemo.events, and this record "
                        + "does not");
        assertFalse(actualPackage.startsWith("com.carddemo.cobol"),
                "a shared compatibility type would sit under com.carddemo.cobol, and this record "
                        + "does not");
        assertEquals(0, DeadLetterMetadata.class.getAnnotations().length,
                "DeadLetterMetadata carries no annotation, so no framework publishes it as a "
                        + "shared contract");
    }

    @Test
    @DisplayName("Truncates an over-length component instead of refusing it")
    void recordTruncatesAnOverLengthComponentInsteadOfRefusingIt() {
        String overLength = "E".repeat(CULPRIT_MAX_LENGTH + 1);

        DeadLetterMetadata truncated = assertDoesNotThrow(
                () -> DeadLetterMetadata.of(ABEND_CODE, overLength, REASON, MESSAGE),
                "building the record raised a failure of its own");

        assertEquals(CULPRIT_MAX_LENGTH, truncated.culprit().length(),
                "the culprit holds its leading " + CULPRIT_MAX_LENGTH + " characters");
        assertEquals(overLength.substring(0, CULPRIT_MAX_LENGTH), truncated.culprit(),
                "the culprit keeps the leading characters of the value supplied");
        assertNotEquals(overLength, truncated.culprit(),
                "the culprit differs from the value supplied, so the record truncated it");

        DeadLetterMetadata atMaximum = DeadLetterMetadata.of(
                ABEND_CODE, overLength.substring(0, CULPRIT_MAX_LENGTH), REASON, MESSAGE);
        assertEquals(truncated.culprit(), atMaximum.culprit(),
                "the value already cut to width " + CULPRIT_MAX_LENGTH + " reaches the component "
                        + "unchanged, which shows the truncation is about length alone");
    }

    /**
     * The real dead-letter route, over a raw failing record.
     *
     * <p>Every test above builds the record directly. These drive the shipped
     * {@link DefaultErrorHandler} that {@code config/KafkaConsumerConfig} declares, whose recoverer
     * builds the outgoing record for a delivery no listener could take. One attempt is configured,
     * so the first failure recovers with no retry.
     *
     * <p>The failing payload below carries six shapes of sensitive value at once: a card number, a
     * card verification value, a password, a government identifier, a bearer credential and a
     * monetary amount. Each is generated for this test and stated nowhere else in this repository.
     * None of the six may appear anywhere in the record the recoverer sends, key, value or header.
     */
    @Nested
    @DisplayName("The real recoverer, over a raw record carrying several sensitive forms")
    class RealRecoverer {

        /** The shared fallback when a failing record has no source topic. */
        private static final String DEAD_LETTER_TOPIC = "carddemo.dead-letter";

        /** The topic the failing record arrived on, and the account it was keyed on. */
        private static final String SOURCE_TOPIC = "transaction.authorized";
        private static final String SOURCE_DEAD_LETTER_TOPIC = SOURCE_TOPIC + ".DLT";
        private static final String ACCOUNT_KEY = "00000000007";

        /** The partition, the offset and the one delivery attempt the record carries. */
        private static final int SOURCE_PARTITION = 2;
        private static final long SOURCE_OFFSET = 4321L;

        /**
         * Six generated values under the labels the failing payload carries, none of them written
         * anywhere else in this repository.
         *
         * <p>The payload below is built from this map, so a value added here travels and is
         * asserted on without a second edit.
         */
        private static final Map<String, String> SENSITIVE_BY_LABEL = new LinkedHashMap<>();

        static {
            SENSITIVE_BY_LABEL.put("cardNumber", "9999" + "452612877065");
            SENSITIVE_BY_LABEL.put("cvv", "731");
            SENSITIVE_BY_LABEL.put("password", "a-generated-password-for-the-recoverer-test");
            SENSITIVE_BY_LABEL.put("governmentId", "900" + "55" + "8213");
            SENSITIVE_BY_LABEL.put("authorization",
                    "a-generated-bearer-value-for-the-recoverer-test");
            SENSITIVE_BY_LABEL.put("amount", "1250.75");
        }

        /** The card number, and the amount, named where an assertion needs one of them. */
        private static final String CARD_NUMBER = SENSITIVE_BY_LABEL.get("cardNumber");
        private static final String PASSWORD = SENSITIVE_BY_LABEL.get("password");
        private static final String AMOUNT = SENSITIVE_BY_LABEL.get("amount");

        /**
         * How long a value must be before the sweep looks for it on its own.
         *
         * <p>A card verification value holds three digits, and three digits appear inside a random
         * identifier often enough to fail a run that leaked nothing. Every value is swept for under
         * its own label, and a value at or above this width is swept for on its own as well.
         */
        private static final int IDENTIFYING_LENGTH = 6;

        /** The payload of the failing record, carrying all six shapes under their labels. */
        private static final String RAW_PAYLOAD = rawPayload();

        /** Builds the failing payload from the map, as one flat object. */
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

        /**
         * Asserts one outgoing record carries no label, no labelled pair, and no identifying value.
         *
         * @param sent the record the recoverer published
         */
        private static void assertCarriesNoSensitiveForm(ProducerRecord<String, Object> sent) {
            String outgoing = rendered(sent);

            for (Map.Entry<String, String> field : SENSITIVE_BY_LABEL.entrySet()) {
                String label = field.getKey();
                String value = field.getValue();

                assertFalse(outgoing.contains(label),
                        "the dead-letter record carries the field label " + label
                                + ", so the failing payload reached the topic");
                assertFalse(outgoing.contains("\"" + label + "\":\"" + value),
                        "the dead-letter record carries the value held under " + label);
                if (value.length() >= IDENTIFYING_LENGTH) {
                    assertFalse(outgoing.contains(value),
                            "the dead-letter record carries the value held under " + label);
                }
            }
            assertFalse(outgoing.contains(RAW_PAYLOAD),
                    "the whole failing payload reached the dead-letter record");
        }

        /** One attempt, so the first failure recovers, and the four shipped topic names. */
        private static LedgerProperties properties() {
            return new LedgerProperties(
                    new LedgerProperties.Kafka(new LedgerProperties.Kafka.Topics(SOURCE_TOPIC,
                            "transaction.declined", "account.state-changed",
                            "transaction.posted", DEAD_LETTER_TOPIC, ".DLT")),
                    new LedgerProperties.Consumer(new LedgerProperties.Consumer.Retry(1, 0L)),
                    new LedgerProperties.Outbox(new LedgerProperties.Outbox.Relay(1000L, 100,
                            "ledger-relay", java.time.Duration.ofMinutes(2L), 5_000L), 168L),
                    new LedgerProperties.ProcessedEvent(720L, 168L),
                    new LedgerProperties.Retention(3_600_000L, 90));
        }

        /** Sends one failing record through the shipped handler and returns what it published. */
        private ProducerRecord<String, Object> recover(String recordKey, Object recordValue,
                Exception failure) {
            @SuppressWarnings("unchecked")
            KafkaTemplate<String, byte[]> template = mock(KafkaTemplate.class);
            when(template.send(ArgumentMatchers.<ProducerRecord<String, byte[]>>any()))
                    .thenAnswer(invocation -> CompletableFuture.completedFuture(
                            sendResultFor(invocation.getArgument(0))));

            DefaultErrorHandler handler = new KafkaConsumerConfig().ledgerConsumerErrorHandler(
                    template, properties(),
                    new ObservabilityConfig().ledgerMeters(new SimpleMeterRegistry()));
            ConsumerRecord<String, Object> failing = new ConsumerRecord<>(SOURCE_TOPIC,
                    SOURCE_PARTITION, SOURCE_OFFSET, recordKey, recordValue);

            handler.handleOne(failure, failing, mock(Consumer.class),
                    mock(MessageListenerContainer.class));

            @SuppressWarnings("unchecked")
            ArgumentCaptor<ProducerRecord<String, byte[]>> sent =
                    ArgumentCaptor.forClass(ProducerRecord.class);
            verify(template).send(sent.capture());

            ProducerRecord<String, byte[]> published = sent.getValue();
            ProducerRecord<String, Object> asObject = new ProducerRecord<>(published.topic(),
                    published.partition(), published.key(), (Object) published.value(),
                    published.headers());
            return asObject;
        }

        /** Answers the send with a result over the record the caller passed. */
        private static SendResult<String, byte[]> sendResultFor(
                ProducerRecord<String, byte[]> out) {
            return new SendResult<>(out, new RecordMetadata(
                    new TopicPartition(out.topic(), 0), 0L, 0, 0L, 0, 0));
        }

        /**
         * Reads the outgoing value as the text it holds.
         *
         * <p>The recoverer hands over the rendered diagnostic as bytes, because the value serializer
         * of its template writes bytes. Reading them back as text is what lets every assertion below
         * search the outgoing record for a value that must not be in it.
         *
         * @param sent the record the recoverer published
         * @return the value decoded as text
         */
        private static String valueText(ProducerRecord<String, Object> sent) {
            assertTrue(sent.value() instanceof byte[],
                    "the outgoing value is " + sent.value().getClass().getName()
                            + ", and the byte-serializing template writes bytes");
            return new String((byte[]) sent.value(),
                    java.nio.charset.StandardCharsets.UTF_8);
        }

        /** Renders one outgoing record as text: topic, key, value and every header. */
        private static String rendered(ProducerRecord<String, Object> sent) {
            StringBuilder text = new StringBuilder(sent.topic())
                    .append(' ').append(sent.key())
                    .append(' ').append(valueText(sent));
            for (Header header : sent.headers()) {
                text.append(' ').append(header.key()).append('=')
                        .append(header.value() == null ? "" : new String(header.value(),
                                java.nio.charset.StandardCharsets.UTF_8));
            }
            return text.toString();
        }

        @Test
        @DisplayName("the recoverer sends the shared envelope and none of the six sensitive forms")
        void theRecovererSendsTheEnvelopeAndNoSensitiveForm() {
            ProducerRecord<String, Object> sent = recover(ACCOUNT_KEY, RAW_PAYLOAD,
                    new IllegalStateException("posting failed for " + RAW_PAYLOAD));

            assertEquals(SOURCE_DEAD_LETTER_TOPIC, sent.topic(),
                    "the spent record did not reach its source-specific dead-letter topic");
            assertEquals(DeadLetterMetadata.RECORD_LENGTH, valueText(sent).length(),
                    "the outgoing value is the four fixed-width fields of 01 ABEND-DATA and "
                            + "nothing else, so the failing payload cannot have travelled on");
            assertEquals(SOURCE_TOPIC + "-" + SOURCE_PARTITION + "-" + SOURCE_OFFSET, sent.key(),
                    "broker coordinates identify the refused delivery without trusting its key");

            assertCarriesNoSensitiveForm(sent);
        }

        @Test
        @DisplayName("the recoverer names the failure class and carries no failure text")
        void theRecovererNamesTheFailureClassAndCarriesNoText() {
            ProducerRecord<String, Object> sent = recover(ACCOUNT_KEY, RAW_PAYLOAD,
                    new IllegalStateException("balance " + AMOUNT + " card " + CARD_NUMBER));

            String diagnostic = valueText(sent);
            assertEquals(SOURCE_DEAD_LETTER_TOPIC, sent.topic(),
                    "the destination names the stream the failing record arrived on");
            assertEquals(SOURCE_TOPIC + "-" + SOURCE_PARTITION + "-" + SOURCE_OFFSET, sent.key(),
                    "the key names the partition and offset the failing record arrived on");
            assertTrue(diagnostic.contains(IllegalStateException.class.getSimpleName()),
                    "the diagnostic carries the bounded failure class");
            assertFalse(diagnostic.contains(AMOUNT),
                    "the failure text named an amount, and the diagnostic must not repeat it");
            assertFalse(diagnostic.contains(CARD_NUMBER),
                    "the failure text named a card number, and the diagnostic must not repeat it");

            assertNull(sent.headers().lastHeader(KafkaHeaders.DLT_EXCEPTION_MESSAGE),
                    "the exception message header travelled, and a message can carry a value");
            assertNull(sent.headers().lastHeader(KafkaHeaders.DLT_EXCEPTION_STACKTRACE),
                    "the stack trace header travelled, and a trace can carry a value");
            assertNull(sent.headers().lastHeader(KafkaHeaders.DLT_EXCEPTION_FQCN),
                    "the recoverer allowlist carries classification in the envelope, not in an "
                            + "extra framework header");
        }

        @Test
        @DisplayName("a producer-controlled key is replaced by broker coordinates")
        void aProducerControlledKeyIsReplacedByBrokerCoordinates() {
            String keyThatNamesNoAccount = PASSWORD;

            ProducerRecord<String, Object> sent = recover(keyThatNamesNoAccount, RAW_PAYLOAD,
                    new IllegalStateException("posting failed"));

            assertEquals(SOURCE_TOPIC + "-" + SOURCE_PARTITION + "-" + SOURCE_OFFSET, sent.key(),
                    "the outgoing key is derived from broker coordinates");
            assertNotEquals(keyThatNamesNoAccount, sent.key(),
                    "the key of the failing record became the key of the dead letter");
            assertFalse(rendered(sent).contains(keyThatNamesNoAccount),
                    "the key of the failing record reached the dead-letter record");
        }

        @Test
        @DisplayName("a record the value deserializer refused takes the same route with no retry")
        void aRefusedPayloadTakesTheSameRouteWithNoRetry() {
            ProducerRecord<String, Object> sent = recover(ACCOUNT_KEY, null,
                    new DeserializationException("payload refused", RAW_PAYLOAD.getBytes(
                            java.nio.charset.StandardCharsets.UTF_8), false,
                            new IllegalArgumentException("schema violation")));

            assertEquals(SOURCE_DEAD_LETTER_TOPIC, sent.topic(),
                    "a refused payload did not reach its source-specific dead-letter topic");
            assertCarriesNoSensitiveForm(sent);
        }
    }
}
