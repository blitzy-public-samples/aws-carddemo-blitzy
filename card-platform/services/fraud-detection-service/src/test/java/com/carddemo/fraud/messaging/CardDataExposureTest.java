package com.carddemo.fraud.messaging;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.carddemo.cobol.PanMasker;
import com.carddemo.events.EventEnvelope;
import com.carddemo.events.FraudCleared;
import com.carddemo.events.FraudFlagged;
import com.carddemo.events.TransactionAuthorized;
import com.carddemo.events.serde.JsonSchemaValidatingSerializer;
import com.carddemo.fraud.config.KafkaConsumerConfig;
import com.carddemo.fraud.config.ObservabilityConfig;
import com.carddemo.fraud.domain.RiskScoringService;
import com.carddemo.fraud.entity.FraudAssessmentEntity;
import com.carddemo.fraud.outbox.OutboxWriter;
import com.carddemo.fraud.repository.FraudAssessmentRepository;
import com.carddemo.fraud.repository.ProcessedEventRepository;
import com.networknt.schema.Error;
import com.networknt.schema.InputFormat;
import com.networknt.schema.Schema;
import com.networknt.schema.SchemaRegistry;
import com.networknt.schema.SpecificationVersion;
import jakarta.persistence.Column;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.lang.reflect.Field;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.errors.SerializationException;
import org.apache.kafka.common.header.Header;
import org.apache.kafka.common.serialization.ByteArrayDeserializer;
import org.apache.kafka.common.serialization.ByteArraySerializer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.logging.LogLevel;
import org.springframework.boot.logging.LoggerConfiguration;
import org.springframework.boot.logging.LoggingSystem;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.kafka.annotation.EnableKafka;
import org.springframework.kafka.test.EmbeddedKafkaBroker;
import org.springframework.kafka.test.context.EmbeddedKafka;
import org.springframework.kafka.test.utils.KafkaTestUtils;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * No full card number reaches a fraud payload, a captured log line, a dead-letter topic header or a
 * declared column. A published card value takes twelve asterisks and four digits.
 *
 * <p>The fraud detection service is net new; no COBOL ancestor defines it, and masking is an
 * additive deviation recorded in {@code card-platform/docs/business-rule-flags.md}.
 *
 * <p>One constant holds a full sixteen-digit Primary Account Number (PAN) as an exposure probe.
 * Four nested groups hand it to a masking call, to a refused authorization, to a refused
 * JavaScript Object Notation (JSON) document and to a dead-lettered record. Each group then reads
 * the output back.
 */
@DisplayName("Card data reaches no fraud payload, log line, dead-letter topic header or column")
class CardDataExposureTest {

    /**
     * A full Primary Account Number held as an exposure probe, taken from record 21 of
     * {@code app/data/ASCII/cardxref.txt}.
     *
     * <p>Every assertion below hands this value to a path that could surface it, then reads the
     * output back.
     */
    private static final String EXPOSURE_PROBE_CARD_NUMBER = "4859452612877065";

    /** The published card form: twelve asterisks ahead of the last four digits. */
    private static final String MASKED_CARD_NUMBER = "*".repeat(12) + "7065";

    /** The transaction identifier, sixteen characters: ten zeros ahead of six digits. */
    private static final String TRANSACTION_ID = "0".repeat(10) + "683580";

    /** The account identifier, eleven characters, and the Kafka message key. */
    private static final String ACCOUNT_ID = "00000000007";

    /** The authorization timestamp, twenty-six characters. */
    private static final String AUTHORIZED_AT = "2022-06-10 19:27:53.000000";

    /** The merchant category code, four digits. */
    private static final String MERCHANT_CATEGORY_CODE = "0001";

    /** The merchant identifier, nine digits. */
    private static final String MERCHANT_ID = "800000000";

    /** The merchant postal code, padded to the declared width. */
    private static final String MERCHANT_ZIP = "72112     ";

    /** The transaction amount of the first fixture record, as a decimal string. */
    private static final String AMOUNT = "504.77";

    /** The transaction type code, two characters. */
    private static final String TRANSACTION_TYPE_CODE = "01";

    /** The capture channel of the authorization, padded to the declared width. */
    private static final String SOURCE = "POS TERM  ";

    /** The merchant description the fixture record carries. */
    private static final String DESCRIPTION = "Purchase at Abshire-Lowe";

    /** The merchant name the fixture record carries. */
    private static final String MERCHANT_NAME = "Abshire-Lowe";

    /** The merchant city the fixture record carries. */
    private static final String MERCHANT_CITY = "North Enoshaven";

    /** A pinned event identifier, so a serialized document reads the same on every run. */
    private static final UUID EVENT_ID =
            UUID.fromString("3f1d9c62-8b4e-4a17-9f0c-2d6a5e73b418");

    /** A pinned publish timestamp, so a serialized document reads the same on every run. */
    private static final Instant OCCURRED_AT = Instant.parse("2022-06-10T19:27:53.412Z");

    /** A pinned assessment timestamp. */
    private static final Instant ASSESSED_AT = Instant.parse("2022-06-10T19:27:53.512Z");

    /** A timestamp outside the form the two fraud documents declare. */
    private static final Instant UNDECLARED_TIMESTAMP = Instant.parse("+10000-01-01T00:00:00Z");

    /** The topic that carries both fraud events. */
    private static final String FRAUD_ASSESSED_TOPIC = "fraud.assessed";

    /** The topic the fraud detection service reads. */
    private static final String TRANSACTION_AUTHORIZED_TOPIC = "transaction.authorized";

    /** The dead-letter topic the shipped configuration names as its fallback. */
    private static final String DEAD_LETTER_TOPIC = "carddemo.dead-letter";

    /** The dead-letter topic belonging to the consumed topic. */
    private static final String SUFFIXED_DEAD_LETTER_TOPIC = "transaction.authorized.DLT";

    /**
     * The property the embedded broker writes its own address to.
     *
     * <p>By default the broker writes {@code spring.kafka.bootstrap-servers} into the system
     * properties of the whole test runtime. This name sits outside every bound configuration prefix
     * and is read by the dead-letter header group alone.
     */
    private static final String BROKER_ADDRESS_PROPERTY = "card-data-exposure.brokers";

    /** Twelve or more consecutive digits, compiled once. */
    private static final Pattern LONG_DIGIT_RUN = Pattern.compile("[0-9]{12,}");

    /** A standalone run of exactly sixteen digits. */
    private static final Pattern SIXTEEN_DIGIT_RUN =
            Pattern.compile("(?<![0-9])[0-9]{16}(?![0-9])");

    /** A masked value: twelve or more asterisks and the digits that follow. */
    private static final Pattern MASKED_VALUE = Pattern.compile("\\*{12,}[0-9]*");

    /** The fragment the name of a card verification value field would carry. */
    private static final String CARD_VERIFICATION_FRAGMENT = "cvv";

    /** Property-name fragments a card-bearing field would carry, folded to lower case. */
    private static final List<String> CARD_DATA_FRAGMENTS =
            List.of("cardnumber", "maskedcard", "pan", CARD_VERIFICATION_FRAGMENT);

    /** The two names a card-bearing property would take on a fraud document. */
    private static final List<String> CARD_PROPERTY_NAMES =
            List.of("cardNumber", "maskedCardNumber");

    /** The flagged assessment schema document. */
    private static final String FLAGGED_SCHEMA = "schemas/fraud-flagged-v1.json";

    /** The cleared assessment schema document. */
    private static final String CLEARED_SCHEMA = "schemas/fraud-cleared-v1.json";

    /** The migration this service ships, read from the classpath. */
    private static final String MIGRATION_RESOURCE = "db/migration/V1__schema.sql";

    /** The column name a masked card value would take. */
    private static final String MASKED_CARD_NUMBER_COLUMN = "masked_card_number";

    /** The tables the migration declares. */
    private static final Set<String> DECLARED_TABLES =
            Set.of("fraud_assessment", "velocity_window", "processed_event", "outbox_event");

    /** A {@code CREATE TABLE} heading and the name that follows it. */
    private static final Pattern CREATED_TABLE = Pattern.compile(
            "(?i)\\bCREATE\\s+TABLE\\s+(?:IF\\s+NOT\\s+EXISTS\\s+)?([a-z_][a-z0-9_]*)");

    /** Reads the two schema documents and every serialized event. */
    private static final ObjectMapper MAPPER = JsonMapper.builder().build();

    /** The dialect both fraud documents declare. */
    private static final SchemaRegistry REGISTRY =
            SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_2020_12);

    /** Writes an event the way the outbox relay writes one. */
    private static final JsonSchemaValidatingSerializer<Object> SERIALIZER =
            new JsonSchemaValidatingSerializer<>();

    /**
     * A published fraud assessment and every refusal the contract reports.
     *
     * <p>Neither fraud event declares a card field. A refusal names the failing property as a JSON
     * pointer and withholds the value that failed.
     */
    @Nested
    @DisplayName("A published fraud assessment payload")
    class FraudPayloadAbsence {

        @Test
        @DisplayName("declares no property naming a card number or a card verification value")
        void declaresNoPropertyNamingACardNumberOrACardVerificationValue() {
            assertAll(List.of(FLAGGED_SCHEMA, CLEARED_SCHEMA).stream().map(resource -> () -> {
                List<String> declared = declaredPropertyNames(schemaDocument(resource));

                assertFalse(declared.isEmpty(), resource + " declares property names");
                assertAll(declared.stream().map(name -> () -> assertAll(CARD_DATA_FRAGMENTS.stream()
                        .map(fragment -> () -> assertFalse(
                                name.toLowerCase(Locale.ROOT).contains(fragment),
                                resource + " declares " + name + ", holding " + fragment)))));
            }));
        }

        @Test
        @DisplayName("carries no such property once written to the fraud.assessed topic")
        void carriesNoSuchPropertyOnceWrittenToTheFraudAssessedTopic() {
            List<String> written = new ArrayList<>();
            written.addAll(propertyNamesOf(wireFormOf(flaggedAssessment())));
            written.addAll(propertyNamesOf(wireFormOf(clearedAssessment())));

            assertFalse(written.isEmpty(), "both written assessments carry property names");
            assertAll(written.stream().map(name -> () -> assertAll(CARD_DATA_FRAGMENTS.stream()
                    .map(fragment -> () -> assertFalse(
                            name.toLowerCase(Locale.ROOT).contains(fragment),
                            "a written assessment carries " + name + ", holding " + fragment)))));
        }

        @Test
        @DisplayName("holds one long digit run, the transaction identifier, and no card number")
        void holdsOneLongDigitRunTheTransactionIdentifierAndNoCardNumber() {
            String flagged = wireFormOf(flaggedAssessment());
            String cleared = wireFormOf(clearedAssessment());

            assertAll(
                    () -> assertEquals(Set.of(TRANSACTION_ID), longDigitRunsIn(flagged),
                            "every long digit run a flagged assessment carries"),
                    () -> assertEquals(Set.of(TRANSACTION_ID), longDigitRunsIn(cleared),
                            "every long digit run a cleared assessment carries"),
                    () -> assertFalse(flagged.contains(EXPOSURE_PROBE_CARD_NUMBER),
                            "a flagged assessment carries no full card number"),
                    () -> assertFalse(cleared.contains(EXPOSURE_PROBE_CARD_NUMBER),
                            "a cleared assessment carries no full card number"),
                    () -> assertFalse(SIXTEEN_DIGIT_RUN.matcher(MASKED_CARD_NUMBER).find(),
                            "the masked form holds no sixteen-digit run"));
        }

        @Test
        @DisplayName("refuses a cleared document that adds a card property")
        void refusesAClearedDocumentThatAddsACardProperty() {
            String cleared = wireFormOf(clearedAssessment());

            assertAll(CARD_PROPERTY_NAMES.stream().map(property -> () -> {
                String added = withAddedProperty(cleared, property, EXPOSURE_PROBE_CARD_NUMBER);
                List<String> pointers = pointersFor(CLEARED_SCHEMA, added);

                assertEquals(List.of("/" + property + " (additionalProperties)"), pointers,
                        "the refusal a " + property + " addition draws");
                assertTrue(pointersFor(CLEARED_SCHEMA, cleared).isEmpty(),
                        "the document without the addition is accepted");
            }));
        }

        @Test
        @DisplayName("keeps a masked value at twelve asterisks, four digits and sixteen characters")
        void keepsAMaskedValueAtTwelveAsterisksFourDigitsAndSixteenCharacters() {
            String masked = PanMasker.maskCardNumber(EXPOSURE_PROBE_CARD_NUMBER);
            TransactionAuthorized authorized = authorizationCarrying(masked);
            List<String> digitRuns = matchesOf(Pattern.compile("[0-9]+"), masked);

            assertAll(
                    () -> assertEquals(MASKED_CARD_NUMBER, masked, "the masked form"),
                    () -> assertTrue(
                            masked.matches(TransactionAuthorized.MASKED_CARD_NUMBER_PATTERN),
                            "the masked form satisfies "
                                    + TransactionAuthorized.MASKED_CARD_NUMBER_PATTERN),
                    () -> assertEquals(TransactionAuthorized.MASKED_CARD_NUMBER_LENGTH,
                            masked.length(), "the masked length, minimum and maximum alike"),
                    () -> assertEquals(masked, authorized.maskedCardNumber(),
                            "the value the authorization carries"),
                    () -> assertEquals(List.of("7065"), digitRuns,
                            "the masked form holds one run of four digits"),
                    () -> assertFalse(masked.contains(EXPOSURE_PROBE_CARD_NUMBER),
                            "the masked form holds no full card number"));
        }

        @Test
        @DisplayName("reports a refusal as a JSON pointer and withholds the value that failed")
        void reportsARefusalAsAJsonPointerAndWithholdsTheValueThatFailed() {
            FraudCleared undeclared =
                    FraudCleared.of(TRANSACTION_ID, ACCOUNT_ID, UNDECLARED_TIMESTAMP);
            SerializationException refused = assertThrows(SerializationException.class,
                    () -> SERIALIZER.serialize(FRAUD_ASSESSED_TOPIC, undeclared));
            String message = refused.getMessage();
            String pointer = "/assessedAt (pattern)";

            assertAll(
                    () -> assertTrue(message.contains(pointer),
                            "the refusal names " + pointer),
                    () -> assertEquals(1, countOf(message, pointer),
                            "the count of " + pointer + " the refusal names"),
                    () -> assertTrue(message.contains(CLEARED_SCHEMA),
                            "the refusal names " + CLEARED_SCHEMA),
                    () -> assertFalse(message.contains(UNDECLARED_TIMESTAMP.toString()),
                            "the refusal withholds the value that failed"),
                    () -> assertFalse(LONG_DIGIT_RUN.matcher(message).find(),
                            "the refusal holds no long digit run"),
                    () -> assertFalse(message.contains(EXPOSURE_PROBE_CARD_NUMBER),
                            "the refusal holds no full card number"));
        }
    }

    /**
     * Captured console output, taken while four paths handle the exposure probe.
     *
     * <p>Each test raises the two shipped log levels on the logger context, writes through a
     * {@code com.carddemo} logger, then reads the captured text. The two Hibernate loggers stay
     * where the shipped configuration leaves them.
     */
    @Nested
    @ExtendWith(OutputCaptureExtension.class)
    @DisplayName("Captured log output")
    class CapturedLogOutput {

        /** The logger name the shipped configuration raises to DEBUG. */
        private static final String SERVICE_LOGGER = "com.carddemo";

        /** The token every line the exercised path writes carries. */
        private static final String EXERCISED_PATH = "exposure-probe";

        /** The two loggers whose bind-parameter output the shipped configuration leaves down. */
        private static final List<String> HIBERNATE_LOGGERS =
                List.of("org.hibernate.SQL", "org.hibernate.orm.jdbc.bind");

        /** Reads and writes levels on the logger context this service runs on. */
        private final LoggingSystem loggingSystem =
                LoggingSystem.get(CardDataExposureTest.class.getClassLoader());

        /** Writes the lines each test reads back. */
        private final Logger log = LoggerFactory.getLogger(CardDataExposureTest.class);

        /** The root level held before a test raised the shipped levels. */
        private LogLevel priorRootLevel;

        /** The service level held before a test raised the shipped levels. */
        private LogLevel priorServiceLevel;

        @BeforeEach
        void applyShippedLevels() {
            priorRootLevel = configuredLevelOf(LoggingSystem.ROOT_LOGGER_NAME);
            priorServiceLevel = configuredLevelOf(SERVICE_LOGGER);
            loggingSystem.setLogLevel(LoggingSystem.ROOT_LOGGER_NAME, LogLevel.INFO);
            loggingSystem.setLogLevel(SERVICE_LOGGER, LogLevel.DEBUG);
        }

        @AfterEach
        void restorePriorLevels() {
            if (priorRootLevel != null) {
                loggingSystem.setLogLevel(LoggingSystem.ROOT_LOGGER_NAME, priorRootLevel);
            }
            loggingSystem.setLogLevel(SERVICE_LOGGER, priorServiceLevel);
        }

        @Test
        @DisplayName("holds no sixteen-digit run, no long digit run and no card verification value")
        void holdsNoSixteenDigitRunNoLongDigitRunAndNoCardVerificationValue(
                CapturedOutput output) {
            exerciseEveryPathTheProbeTravels();
            String everything = output.getAll();
            String written = linesCarrying(everything, EXERCISED_PATH);

            assertAll(
                    () -> assertTrue(written.contains(MASKED_CARD_NUMBER),
                            "the exercised path wrote the masked form, so the capture caught it"),
                    () -> assertFalse(everything.contains(EXPOSURE_PROBE_CARD_NUMBER),
                            "no captured text holds the full card number"),
                    () -> assertFalse(SIXTEEN_DIGIT_RUN.matcher(written).find(),
                            "the written lines hold no sixteen-digit run"),
                    () -> assertEquals(Set.of(), longDigitRunsIn(written),
                            "every long digit run the written lines hold"),
                    () -> assertFalse(written.toLowerCase(Locale.ROOT)
                                    .contains(CARD_VERIFICATION_FRAGMENT),
                            "the written lines name no card verification value"),
                    () -> assertEquals(PanMasker.REDACTED_CARD_VERIFICATION_VALUE,
                            PanMasker.redactCardVerificationValue(
                                    EXPOSURE_PROBE_CARD_NUMBER.substring(13)),
                            "the redacted card verification value"));
        }

        @Test
        @DisplayName("shows every masked value in the declared twelve-asterisk form")
        void showsEveryMaskedValueInTheDeclaredTwelveAsteriskForm(CapturedOutput output) {
            exerciseEveryPathTheProbeTravels();
            List<String> maskedValues =
                    matchesOf(MASKED_VALUE, linesCarrying(output.getAll(), EXERCISED_PATH));

            assertFalse(maskedValues.isEmpty(), "the written lines hold a masked value");
            assertAll(maskedValues.stream().map(value -> () -> assertTrue(
                    value.matches(TransactionAuthorized.MASKED_CARD_NUMBER_PATTERN)
                            || value.equals(PanMasker.FULLY_MASKED_CARD_NUMBER),
                    "a captured masked value takes the declared form: " + value)));
        }

        @Test
        @DisplayName("leaves both Hibernate loggers below DEBUG and below TRACE")
        void leavesBothHibernateLoggersBelowDebugAndBelowTrace() {
            assertAll(HIBERNATE_LOGGERS.stream().map(name -> () -> {
                LoggerFactory.getLogger(name);
                LoggerConfiguration configuration = loggingSystem.getLoggerConfiguration(name);

                assertNotNull(configuration, name + " is present on the logger context");
                assertNull(configuration.getConfiguredLevel(),
                        name + " carries no level of its own");
                assertNotEquals(LogLevel.DEBUG, configuration.getEffectiveLevel(),
                        name + " sits below DEBUG");
                assertNotEquals(LogLevel.TRACE, configuration.getEffectiveLevel(),
                        name + " sits below TRACE");
            }));
        }

        /**
         * Hands the exposure probe to a masking call, a refused authorization, a refused document
         * and a refused assessment, writing each result through a service logger.
         */
        private void exerciseEveryPathTheProbeTravels() {
            log.debug("{} masked card value {}", EXERCISED_PATH,
                    PanMasker.maskCardNumber(EXPOSURE_PROBE_CARD_NUMBER));
            log.debug("{} redacted card verification value {}", EXERCISED_PATH,
                    PanMasker.redactCardVerificationValue(
                            EXPOSURE_PROBE_CARD_NUMBER.substring(13)));

            IllegalArgumentException refusedAuthorization = assertThrows(
                    IllegalArgumentException.class,
                    () -> authorizationCarrying(EXPOSURE_PROBE_CARD_NUMBER));
            log.debug("{} refused authorization {}", EXERCISED_PATH,
                    refusedAuthorization.getMessage());

            String added = withAddedProperty(wireFormOf(clearedAssessment()),
                    CARD_PROPERTY_NAMES.getFirst(), EXPOSURE_PROBE_CARD_NUMBER);
            log.debug("{} refused document {}", EXERCISED_PATH,
                    pointersFor(CLEARED_SCHEMA, added));

            SerializationException refusedAssessment = assertThrows(SerializationException.class,
                    () -> SERIALIZER.serialize(FRAUD_ASSESSED_TOPIC,
                            FraudCleared.of(TRANSACTION_ID, ACCOUNT_ID, UNDECLARED_TIMESTAMP)));
            log.debug("{} refused assessment {}", EXERCISED_PATH,
                    refusedAssessment.getMessage());
        }

        /** Reads one logger's own level, which is {@code null} where the logger inherits one. */
        private LogLevel configuredLevelOf(String name) {
            LoggerConfiguration configuration = loggingSystem.getLoggerConfiguration(name);
            return configuration == null ? null : configuration.getConfiguredLevel();
        }
    }

    /**
     * Header content on a record the broker dead-lettered.
     *
     * <p>A document carrying the exposure probe in the masked position fails validation, and the
     * shipped error handler routes it. Every header key and every header value is then read back
     * from the record that arrived.
     */
    @Nested
    @SpringBootTest(
            classes = DeadLetterHeaderConfiguration.class,
            webEnvironment = SpringBootTest.WebEnvironment.NONE,
            properties = {
                "spring.kafka.bootstrap-servers=${" + BROKER_ADDRESS_PROPERTY + "}",
                "spring.kafka.security.protocol=PLAINTEXT",
                "spring.kafka.consumer.group-id=fraud-detection-card-data-exposure",
                "spring.main.banner-mode=off",
                "spring.autoconfigure.exclude="
                        + "org.springframework.boot.jdbc.autoconfigure"
                        + ".DataSourceAutoConfiguration,"
                        + "org.springframework.boot.hibernate.autoconfigure"
                        + ".HibernateJpaAutoConfiguration,"
                        + "org.springframework.boot.flyway.autoconfigure"
                        + ".FlywayAutoConfiguration,"
                        + "org.springframework.boot.data.jpa.autoconfigure"
                        + ".JpaRepositoriesAutoConfiguration",
                "management.endpoint.health.group.readiness.include=readinessState",
                "management.endpoint.health.group.liveness.include=livenessState",
                "carddemo.kafka.topics.transaction-authorized=transaction.authorized",
                "carddemo.kafka.topics.dead-letter=carddemo.dead-letter",
                "carddemo.kafka.topics.dead-letter-suffix=.DLT",
                "carddemo.consumer.retry.max-attempts=1",
                "carddemo.consumer.retry.backoff-ms=10"
            })
    @EmbeddedKafka(
            partitions = 1,
            bootstrapServersProperty = BROKER_ADDRESS_PROPERTY,
            topics = {"transaction.authorized", "transaction.authorized.DLT",
                "carddemo.dead-letter"})
    @DisplayName("Dead-letter topic header content")
    class DeadLetterHeaderContent {

        /** How long a dead-letter record is waited for. */
        private static final Duration ARRIVAL_CEILING = Duration.ofSeconds(60);

        /** How often the reader is polled while waiting. */
        private static final Duration POLL_SPACING = Duration.ofMillis(250);

        /** How long one poll call blocks. */
        private static final Duration POLL_BLOCK = Duration.ofMillis(500);

        /** The broker the listener and the reader below share. */
        @Autowired
        private EmbeddedKafkaBroker broker;

        /** Stands in for the risk rules, which this group never reaches. */
        @MockitoBean
        private RiskScoringService riskScoring;

        /** Stands in for the verdict table, which this group never reaches. */
        @MockitoBean
        private FraudAssessmentRepository assessments;

        /** Stands in for the marker table, which this group never reaches. */
        @MockitoBean
        private ProcessedEventRepository processedEvents;

        /** Stands in for the outbox write, which this group never reaches. */
        @MockitoBean
        private OutboxWriter outboxWriter;

        /** Every dead-letter record the refused document produced. */
        private List<ConsumerRecord<String, byte[]>> deadLettered;

        /** The document the refusal was raised on. */
        private String refusedDocument;

        @BeforeEach
        void publishADocumentCarryingTheProbe() {
            refusedDocument = authorizationDocumentCarrying(EXPOSURE_PROBE_CARD_NUMBER);
            publishRawValue(refusedDocument);
            deadLettered = awaitDeadLetterRecords();
        }

        @Test
        @DisplayName("carries no long digit run in any header key or header value")
        void carriesNoLongDigitRunInAnyHeaderKeyOrHeaderValue() {
            List<String> headerText = headerTextOf(deadLettered);

            assertFalse(headerText.isEmpty(), "the dead-letter record carries header text to read");
            assertAll(headerText.stream().map(text -> () -> assertAll(
                    () -> assertEquals(Set.of(), longDigitRunsIn(text),
                            "every long digit run the header text holds: " + text),
                    () -> assertFalse(SIXTEEN_DIGIT_RUN.matcher(text).find(),
                            "the header text holds no sixteen-digit run: " + text))));
        }

        @Test
        @DisplayName("names no card verification value and no card number in any header")
        void namesNoCardVerificationValueAndNoCardNumberInAnyHeader() {
            List<String> headerText = headerTextOf(deadLettered);

            assertFalse(headerText.isEmpty(), "the dead-letter record carries header text to read");
            assertAll(headerText.stream().map(text -> () -> assertAll(
                    () -> assertFalse(text.toLowerCase(Locale.ROOT)
                                    .contains(CARD_VERIFICATION_FRAGMENT),
                            "the header text names no card verification value: " + text),
                    () -> assertFalse(text.contains(EXPOSURE_PROBE_CARD_NUMBER),
                            "the header text holds no full card number"))));
        }

        @Test
        @DisplayName("attaches the refused document to no header value and to no record value")
        void attachesTheRefusedDocumentToNoHeaderValueAndToNoRecordValue() {
            List<String> headerText = headerTextOf(deadLettered);

            assertAll(
                    () -> assertAll(headerText.stream().map(text -> () -> assertAll(
                            () -> assertFalse(text.contains(refusedDocument),
                                    "no header value holds the refused document"),
                            () -> assertAll(CARD_PROPERTY_NAMES.stream()
                                    .map(property -> () -> assertFalse(text.contains(property),
                                            "no header value names " + property)))))),
                    () -> assertAll(deadLettered.stream().map(deadLetter -> () -> {
                        String value = deadLetter.value() == null ? ""
                                : new String(deadLetter.value(), StandardCharsets.UTF_8);

                        assertFalse(value.contains(refusedDocument),
                                "the record value holds no refused document");
                        assertFalse(value.contains(EXPOSURE_PROBE_CARD_NUMBER),
                                "the record value holds no full card number");
                    })));
        }

        /** Writes one raw value onto the consumed topic, keyed by the account identifier. */
        private void publishRawValue(String value) {
            Map<String, Object> settings = new HashMap<>(KafkaTestUtils.producerProps(broker));

            try (KafkaProducer<String, byte[]> producer = new KafkaProducer<>(settings,
                    new StringSerializer(), new ByteArraySerializer())) {
                producer.send(new ProducerRecord<>(TRANSACTION_AUTHORIZED_TOPIC, ACCOUNT_ID,
                        value.getBytes(StandardCharsets.UTF_8)));
                producer.flush();
            }
        }

        /** Reads both dead-letter topics until at least one record arrives. */
        private List<ConsumerRecord<String, byte[]>> awaitDeadLetterRecords() {
            Map<String, Object> settings = new HashMap<>(KafkaTestUtils.consumerProps(broker,
                    "card-data-exposure-" + UUID.randomUUID(), true));
            List<TopicPartition> assigned =
                    List.of(new TopicPartition(SUFFIXED_DEAD_LETTER_TOPIC, 0),
                            new TopicPartition(DEAD_LETTER_TOPIC, 0));
            List<ConsumerRecord<String, byte[]>> arrived = new ArrayList<>();

            try (KafkaConsumer<String, byte[]> reader = new KafkaConsumer<>(settings,
                    new StringDeserializer(), new ByteArrayDeserializer())) {
                reader.assign(assigned);
                reader.seekToBeginning(assigned);
                Awaitility.await("a dead-letter record")
                        .atMost(ARRIVAL_CEILING)
                        .pollInterval(POLL_SPACING)
                        .until(() -> {
                            ConsumerRecords<String, byte[]> polled = reader.poll(POLL_BLOCK);
                            polled.forEach(arrived::add);
                            return !arrived.isEmpty();
                        });
            }
            return List.copyOf(arrived);
        }
    }

    /**
     * The context this class starts for the dead-letter header group.
     *
     * <p>The shipped consumer configuration supplies the container factory, the error handler and
     * the route a refused record takes. The listener joins the consumer group and reads the record
     * that fails validation.
     */
    @Configuration(proxyBeanMethods = false)
    @EnableAutoConfiguration
    @EnableKafka
    @Import({KafkaConsumerConfig.class, ObservabilityConfig.class,
        TransactionAuthorizedConsumer.class})
    static class DeadLetterHeaderConfiguration {
    }

    /**
     * The columns and tables this service declares.
     *
     * <p>The verdict table declares no masked card column. The migration declares four tables and
     * no card table.
     */
    @Nested
    @DisplayName("Declared columns and tables")
    class StructuralBackstop {

        @Test
        @DisplayName("leave the verdict entity without a masked card number column or field")
        void leaveTheVerdictEntityWithoutAMaskedCardNumberColumnOrField() {
            List<Field> fields = List.of(FraudAssessmentEntity.class.getDeclaredFields());
            List<String> columns = new ArrayList<>();
            List<String> names = new ArrayList<>();

            for (Field field : fields) {
                if (field.isSynthetic()) {
                    continue;
                }
                names.add(field.getName());
                Column column = field.getAnnotation(Column.class);
                if (column != null) {
                    columns.add(column.name());
                }
            }

            assertFalse(columns.isEmpty(), "the verdict entity declares column names");
            assertAll(
                    () -> assertFalse(columns.contains(MASKED_CARD_NUMBER_COLUMN),
                            "the declared columns hold no " + MASKED_CARD_NUMBER_COLUMN + ": "
                                    + columns),
                    () -> assertAll(names.stream().map(name -> () -> assertAll(
                            CARD_DATA_FRAGMENTS.stream().map(fragment -> () -> assertFalse(
                                    name.toLowerCase(Locale.ROOT).contains(fragment),
                                    "the field " + name + " holds " + fragment))))),
                    () -> assertAll(columns.stream().map(column -> () -> assertAll(
                            CARD_DATA_FRAGMENTS.stream().map(fragment -> () -> assertFalse(
                                    column.toLowerCase(Locale.ROOT).contains(fragment),
                                    "the column " + column + " holds " + fragment))))));
        }

        @Test
        @DisplayName("leave the migration without a masked card number column on any table")
        void leaveTheMigrationWithoutAMaskedCardNumberColumnOnAnyTable() {
            String migration = resourceText(MIGRATION_RESOURCE);
            String statements = withoutLineComments(migration).toLowerCase(Locale.ROOT);

            assertAll(
                    () -> assertFalse(migration.contains(MASKED_CARD_NUMBER_COLUMN),
                            "the migration declares no " + MASKED_CARD_NUMBER_COLUMN),
                    () -> assertAll(CARD_DATA_FRAGMENTS.stream().map(fragment -> () -> assertFalse(
                            statements.contains(fragment),
                            "the migration statements hold no " + fragment))));
        }

        @Test
        @DisplayName("declare four tables, so this service owns no card table")
        void declareFourTablesSoThisServiceOwnsNoCardTable() {
            Set<String> created = new LinkedHashSet<>();
            Matcher headings =
                    CREATED_TABLE.matcher(withoutLineComments(resourceText(MIGRATION_RESOURCE)));

            while (headings.find()) {
                created.add(headings.group(1).toLowerCase(Locale.ROOT));
            }

            assertEquals(DECLARED_TABLES, created, "the tables the migration declares");
        }
    }

    /** Builds a flagged assessment with a pinned envelope, so it serializes the same each run. */
    private static FraudFlagged flaggedAssessment() {
        return new FraudFlagged(EVENT_ID, FraudFlagged.EVENT_TYPE, EventEnvelope.SCHEMA_VERSION,
                OCCURRED_AT, ACCOUNT_ID, TRANSACTION_ID, FraudFlagged.MAXIMUM_RISK_SCORE,
                List.of(FraudFlagged.VELOCITY_RULE), ASSESSED_AT, ACCOUNT_ID);
    }

    /** Builds a cleared assessment with a pinned envelope, so it serializes the same each run. */
    private static FraudCleared clearedAssessment() {
        return new FraudCleared(EVENT_ID, FraudCleared.EVENT_TYPE, EventEnvelope.SCHEMA_VERSION,
                OCCURRED_AT, ACCOUNT_ID, TRANSACTION_ID, ACCOUNT_ID, ASSESSED_AT);
    }

    /**
     * Builds an authorization event holding {@code cardValue} in the masked position.
     *
     * @throws IllegalArgumentException where the value is not twelve asterisks and four digits
     */
    private static TransactionAuthorized authorizationCarrying(String cardValue) {
        return new TransactionAuthorized(EVENT_ID, TransactionAuthorized.EVENT_TYPE,
                EventEnvelope.SCHEMA_VERSION, OCCURRED_AT, ACCOUNT_ID, TRANSACTION_ID,
                TRANSACTION_TYPE_CODE, MERCHANT_CATEGORY_CODE, SOURCE, DESCRIPTION,
                new BigDecimal(AMOUNT), MERCHANT_ID, MERCHANT_NAME, MERCHANT_CITY, MERCHANT_ZIP,
                cardValue, null, AUTHORIZED_AT, ACCOUNT_ID, TransactionAuthorized.CURRENCY);
    }

    /** Builds a JSON document holding {@code cardValue} in the masked position. */
    private static String authorizationDocumentCarrying(String cardValue) {
        return """
                {
                  "eventId": "%s",
                  "eventType": "%s",
                  "schemaVersion": %d,
                  "occurredAt": "%s",
                  "aggregateId": "%s",
                  "transactionId": "%s",
                  "accountId": "%s",
                  "transactionTypeCode": "%s",
                  "merchantCategoryCode": "%s",
                  "source": "%s",
                  "description": "%s",
                  "amount": "%s",
                  "merchantId": "%s",
                  "merchantName": "%s",
                  "merchantCity": "%s",
                  "merchantZip": "%s",
                  "maskedCardNumber": "%s",
                  "authorizedAt": "%s",
                  "currency": "%s"
                }""".formatted(EVENT_ID, TransactionAuthorized.EVENT_TYPE,
                EventEnvelope.SCHEMA_VERSION, OCCURRED_AT, ACCOUNT_ID, TRANSACTION_ID, ACCOUNT_ID,
                TRANSACTION_TYPE_CODE, MERCHANT_CATEGORY_CODE, SOURCE, DESCRIPTION, AMOUNT,
                MERCHANT_ID, MERCHANT_NAME, MERCHANT_CITY, MERCHANT_ZIP, cardValue, AUTHORIZED_AT,
                TransactionAuthorized.CURRENCY);
    }

    /** Writes one event the way the outbox relay writes it, and returns the bytes as text. */
    private static String wireFormOf(Object event) {
        return new String(SERIALIZER.serialize(FRAUD_ASSESSED_TOPIC, event),
                StandardCharsets.UTF_8);
    }

    /** Returns {@code document} carrying one added top-level property, ahead of the rest. */
    private static String withAddedProperty(String document, String property, String value) {
        return document.replaceFirst("^\\{",
                Matcher.quoteReplacement("{\"" + property + "\":\"" + value + "\","));
    }

    /** Validates {@code json}, returning one pointer and keyword per violation. */
    private static List<String> pointersFor(String resource, String json) {
        List<String> pointers = new ArrayList<>();

        for (Error violation : compiledSchema(resource).validate(json, InputFormat.JSON)) {
            String location = violation.getInstanceLocation().toString();
            String property = violation.getProperty();
            String pointer = property == null || property.isBlank()
                    ? (location.isEmpty() ? "/" : location)
                    : location + "/" + property;

            pointers.add(pointer + " (" + violation.getKeyword() + ")");
        }
        return List.copyOf(pointers);
    }

    /** Collects every property name a schema document declares, at any depth. */
    private static List<String> declaredPropertyNames(JsonNode document) {
        List<String> names = new ArrayList<>();

        if (document.isObject()) {
            JsonNode properties = document.get("properties");

            if (properties != null && properties.isObject()) {
                names.addAll(properties.propertyNames());
            }
        }
        if (document.isObject() || document.isArray()) {
            document.values().forEach(child -> names.addAll(declaredPropertyNames(child)));
        }
        return List.copyOf(names);
    }

    /** Reads the top-level property names of one written document. */
    private static List<String> propertyNamesOf(String json) {
        return List.copyOf(MAPPER.readTree(json).propertyNames());
    }

    /** Collects every header key and every header value of every record, as text. */
    private static List<String> headerTextOf(List<ConsumerRecord<String, byte[]>> records) {
        List<String> text = new ArrayList<>();

        for (ConsumerRecord<String, byte[]> record : records) {
            for (Header header : record.headers()) {
                text.add(header.key());
                text.add(header.value() == null ? ""
                        : new String(header.value(), StandardCharsets.UTF_8));
            }
        }
        return List.copyOf(text);
    }

    /** Collects every distinct run of twelve or more consecutive digits. */
    private static Set<String> longDigitRunsIn(String text) {
        return Set.copyOf(matchesOf(LONG_DIGIT_RUN, text));
    }

    /** Collects every match of one pattern, in the order found. */
    private static List<String> matchesOf(Pattern pattern, String text) {
        List<String> matches = new ArrayList<>();
        Matcher found = pattern.matcher(text);

        while (found.find()) {
            matches.add(found.group());
        }
        return List.copyOf(matches);
    }

    /** Counts how often one token appears. */
    private static int countOf(String text, String token) {
        int found = 0;

        for (int at = text.indexOf(token); at >= 0; at = text.indexOf(token, at + token.length())) {
            found++;
        }
        return found;
    }

    /** Keeps the lines carrying one token, joined by a newline. */
    private static String linesCarrying(String text, String token) {
        return text.lines().filter(line -> line.contains(token))
                .reduce(new StringBuilder(), (all, line) -> all.append(line).append('\n'),
                        StringBuilder::append)
                .toString();
    }

    /** Drops every line comment, keeping the statements. */
    private static String withoutLineComments(String text) {
        return text.replaceAll("--[^\\n]*", "");
    }

    /** Reads one schema document from the classpath. */
    private static JsonNode schemaDocument(String resource) {
        try (InputStream document = openResource(resource)) {
            return MAPPER.readTree(document);
        } catch (IOException unreadable) {
            throw new UncheckedIOException("Reading " + resource + " failed.", unreadable);
        }
    }

    /** Compiles one schema document read from the classpath. */
    private static Schema compiledSchema(String resource) {
        try (InputStream document = openResource(resource)) {
            return REGISTRY.getSchema(document, InputFormat.JSON);
        } catch (IOException unreadable) {
            throw new UncheckedIOException("Reading " + resource + " failed.", unreadable);
        }
    }

    /** Reads one classpath resource as text. */
    private static String resourceText(String resource) {
        try (InputStream content = openResource(resource)) {
            return new String(content.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException unreadable) {
            throw new UncheckedIOException("Reading " + resource + " failed.", unreadable);
        }
    }

    /** Opens one classpath resource, asserting that it ships. */
    private static InputStream openResource(String resource) {
        InputStream content =
                CardDataExposureTest.class.getClassLoader().getResourceAsStream(resource);

        assertNotNull(content, resource + " ships on the classpath");
        return content;
    }
}
