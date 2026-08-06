package com.carddemo.card.outbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.carddemo.card.CardApplication;
import com.carddemo.card.api.dto.CardUpdateRequest;
import com.carddemo.card.api.dto.CardUpdateResponse;
import com.carddemo.card.api.dto.CardUpdateResponse.UpdateOutcome;
import com.carddemo.card.domain.CardUpdateService;
import com.carddemo.card.entity.CardEntity;
import com.carddemo.card.entity.OutboxEventEntity;
import com.carddemo.card.entity.ProcessedEventEntity;
import com.carddemo.card.messaging.CardUpdated;
import com.carddemo.card.messaging.EventPublisherPort;
import com.carddemo.card.repository.CardRepository;
import com.carddemo.card.repository.OutboxEventRepository;
import com.carddemo.card.repository.ProcessedEventRepository;
import com.carddemo.cobol.PanMasker;
import com.carddemo.events.EventEnvelope;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Asserts that {@link OutboxWriter} joins the transaction its caller opened, stores one row of
 * {@code outbox_event}, and publishes nothing.
 *
 * <p>The centre of this class is the propagation assertion. Every test below reads the propagation
 * from the rows that survive a rollback, and no test reads an annotation.
 *
 * <p>Provenance of the pattern. The CardDemo source carries one asynchronous handoff, in paragraph
 * {@code WIRTE-JOBSUB-TDQ} at {@code app/cbl/CORPT00C.cbl:L515}. That paragraph writes one record to
 * a Customer Information Control System (CICS) transient data queue at
 * {@code app/cbl/CORPT00C.cbl:L517-L518}, and a separate job reads the record later.
 *
 * <p>Atomicity, the transactional outbox and the processed-event marker are declared additive
 * deviations. The source offers no atomicity to reproduce. {@code app/cbl/CBTRN02C.cbl:L440-L442}
 * performs three writes under no condition and tests no status between them, and
 * {@code app/cbl/COACTUPC.cbl:L4066} and {@code app/cbl/COACTUPC.cbl:L4086} rewrite two files in one
 * unit of work. Every {@code DEFINE FILE} block of {@code app/csd/CARDDEMO.CSD} disables recovery
 * and journalling. The card file block spans {@code app/csd/CARDDEMO.CSD:L25-L36}, carrying
 * {@code JOURNAL(NO)} on L31 and {@code RECOVERY(NONE)} on L33.
 *
 * <p>The source detects no duplicate delivery either. Paragraph
 * {@code 2900-WRITE-TRANSACTION-FILE} at {@code app/cbl/CBTRN02C.cbl:L562-L579} writes the record
 * and reaches {@code PERFORM 9999-ABEND-PROGRAM} at {@code app/cbl/CBTRN02C.cbl:L577} on any file
 * status other than {@code '00'}, and a duplicate key is one such status.
 *
 * <p>Card fields come from {@code app/cpy/CVACT02Y.cpy}: {@code CARD-NUM PIC X(16)} on L5,
 * {@code CARD-ACCT-ID PIC 9(11)} on L6, {@code CARD-CVV-CD PIC 9(03)} on L7,
 * {@code CARD-EMBOSSED-NAME PIC X(50)} on L8, {@code CARD-EXPIRAION-DATE PIC X(10)} on L9 and
 * {@code CARD-ACTIVE-STATUS PIC X(01)} on L10. {@code XREF-ACCT-ID PIC 9(11)} at
 * {@code app/cpy/CVACT03Y.cpy:L7} fixes the eleven-character width of {@code aggregate_id}.
 *
 * <p>How this class runs. One PostgreSQL 18.4 container serves the whole class, on the image tag
 * {@code card-platform/docker-compose.yml} also names. Flyway creates schema
 * {@value #MIGRATED_SCHEMA}, applies {@code V1__schema.sql} and loads the fifty card rows of
 * {@code V2__seed.sql}. That seed writes no {@code outbox_event} row and no {@code processed_event}
 * row, so every test here inserts the card it needs and creates every row it reads.
 * {@link DynamicPropertySource} points three datasource properties at the container.
 * {@code services/card-service/pom.xml} declares no {@code spring-boot-testcontainers} artifact.
 *
 * <p>Both scheduled sweeps of the service stand down for the duration, through the two delay
 * properties below. The relay sweep calls the publisher, and two tests below count publisher calls.
 *
 * <p>Run this class from {@code card-platform/} with
 * {@code mvn -o -B -pl services/card-service -am test}.
 *
 * <p>The message path is drawn in {@code card-platform/docs/event-flow.md}.
 *
 * <p>Design decisions: {@code card-platform/docs/decision-log.md}.
 */
@SpringBootTest(
        classes = {CardApplication.class, OutboxWriterTest.RecordingPublisherConfiguration.class},
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = {
                "KAFKA_SASL_PASSWORD=not-a-real-broker-password",
                "ADMIN_PASSWORD_HASH={noop}not-a-real-admin-password",
                "USER_PASSWORD_HASH={noop}not-a-real-user-password",
                "MONITORING_PASSWORD_HASH={noop}not-a-real-monitoring-password",
                "carddemo.outbox.relay.fixed-delay-ms=" + OutboxWriterTest.STOOD_DOWN_SWEEP_MS,
                "carddemo.retention.sweep-interval-ms=" + OutboxWriterTest.STOOD_DOWN_SWEEP_MS
        })
@Testcontainers
@DisplayName("OutboxWriter: one local transaction, one row, no publish")
class OutboxWriterTest {

    /**
     * Milliseconds between scheduled sweeps while this class runs, which is one full day.
     *
     * <p>A fixed-delay task runs once as the scheduler starts and then waits this long. The
     * start-up run meets an empty {@code outbox_event}, and the next run falls well past the last
     * test.
     */
    static final String STOOD_DOWN_SWEEP_MS = "86400000";

    /** The image tag {@code card-platform/docker-compose.yml} also names. */
    private static final String POSTGRES_IMAGE = "postgres:18.4";

    /**
     * The database name, the login name and the password of the container, one value for all three.
     * {@code card-platform/.env.example} declares the same value.
     */
    private static final String POSTGRES_CREDENTIAL = "carddemo";

    /**
     * The schema Flyway creates, from {@code spring.flyway.schemas} and
     * {@code spring.jpa.properties.hibernate.default_schema} in
     * {@code src/main/resources/application.yml}.
     */
    private static final String MIGRATED_SCHEMA = "card_service";

    /**
     * Card number of the row every test in this class inserts, sixteen digits as
     * {@code CARD-NUM PIC X(16)} at {@code app/cpy/CVACT02Y.cpy:L5} holds them.
     *
     * <p>The value opens with four nines, the prefix the sibling repository tests use for a row no
     * fixture carries. Its last four digits are {@code 7065}.
     */
    private static final String INSERTED_CARD_NUMBER = syntheticCardNumber(917_065L);

    /**
     * Account identifier of the inserted row, eleven digits with eight leading zeros.
     *
     * <p>Eight of the eleven characters are padding, so a store that dropped them would answer with
     * a shorter key.
     */
    private static final String INSERTED_ACCOUNT_ID = "00000000913";

    /**
     * Card number of the second row, used where an account key carries one significant digit.
     */
    private static final String SHORT_KEY_CARD_NUMBER = syntheticCardNumber(910_042L);

    /**
     * Account identifier holding ten leading zeros and a single significant digit.
     *
     * <p>{@code XREF-ACCT-ID PIC 9(11)} at {@code app/cpy/CVACT03Y.cpy:L7} is a display field, so
     * the ten zeros belong to the value.
     */
    private static final String SHORT_ACCOUNT_ID = "00000000007";

    /**
     * Card verification value both inserted rows carry, from {@code CARD-CVV-CD PIC 9(03)} at
     * {@code app/cpy/CVACT02Y.cpy:L7}. The column {@code card.card_verification_value} stores it and
     * {@link CardUpdated} declares no property for it.
     */
    private static final String INSERTED_VERIFICATION_VALUE = "398";

    /** Embossed cardholder name both inserted rows carry, in the letters and spaces the edit allows. */
    private static final String INSERTED_EMBOSSED_NAME = "OUTBOX WRITER CARD";

    /** Expiration date both inserted rows carry, ten characters wide as text. */
    private static final LocalDate INSERTED_EXPIRATION_DATE = LocalDate.of(2026, 3, 9);

    /** Year both inserted rows carry, four characters as the field edit requires. */
    private static final String INSERTED_EXPIRY_YEAR = "2026";

    /** Month both inserted rows carry, two characters as the field edit requires. */
    private static final String INSERTED_EXPIRY_MONTH = "03";

    /**
     * Day of the month both inserted rows carry.
     *
     * <p>{@code domain/CardUpdateService} assembles a new expiry from the submitted year, the
     * submitted month and the stored day, so this value survives every update below.
     */
    private static final String INSERTED_EXPIRY_DAY = "09";

    /** Embossed cardholder name a committed card update writes. */
    private static final String UPDATED_EMBOSSED_NAME = "OUTBOX WRITER RENAMED";

    /** Expiry year a committed card update writes. */
    private static final String UPDATED_EXPIRY_YEAR = "2027";

    /** Expiry month a committed card update writes. */
    private static final String UPDATED_EXPIRY_MONTH = "05";

    /** Expiration date a committed card update leaves on the row. */
    private static final LocalDate UPDATED_EXPIRATION_DATE = LocalDate.of(2027, 5, 9);

    /** Embossed cardholder name a rolled-back card update attempts and never commits. */
    private static final String ROLLED_BACK_EMBOSSED_NAME = "ROLLED BACK NAME";

    /** Expiration date a rolled-back card update attempts and never commits. */
    private static final LocalDate ROLLED_BACK_EXPIRATION_DATE = LocalDate.of(2029, 11, 30);

    /**
     * Identifier of the duplicate-delivery marker the marker tests write.
     *
     * <p>The value is fixed, so a read after a rollback names the same row the write named.
     */
    private static final UUID MARKER_EVENT_ID =
            UUID.fromString("3a7c1d0e-4b52-4f18-9c6a-8d2e5f7b1049");

    /**
     * The nine properties {@code card-updated-v2.json} lists in its {@code required} array, in the
     * order that document lists them. Five carry the envelope and four carry the card.
     */
    private static final List<String> CONTRACT_PROPERTIES = List.of("eventId", "eventType",
            "schemaVersion", "occurredAt", "aggregateId", "accountId", "maskedCardNumber",
            "expirationDate", "activeStatus");

    /**
     * Reads three columns of every event row, oldest first, through the names
     * {@code V1__schema.sql} declares.
     */
    private static final String EVENT_COLUMNS_SQL = "SELECT published, event_type, aggregate_id"
            + " FROM " + MIGRATED_SCHEMA + ".outbox_event ORDER BY created_at ASC";

    /** Parses a stored payload. Jackson 3 writes it and Jackson 3 reads it back. */
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /**
     * The one container every test in this class shares.
     *
     * <p>The class name comes from {@code org.testcontainers.postgresql}, the package
     * Testcontainers 2.0.5 ships it in. {@link Container} on a static field gives one container per
     * class, and {@link Testcontainers} starts it before the Spring context reads a property below.
     */
    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(POSTGRES_IMAGE)
            .withDatabaseName(POSTGRES_CREDENTIAL)
            .withUsername(POSTGRES_CREDENTIAL)
            .withPassword(POSTGRES_CREDENTIAL);

    /**
     * Points the Spring datasource at the running container.
     *
     * <p>Three properties leave here, each as a supplier the context resolves at refresh.
     * {@code src/main/resources/application.yml} sits on the test classpath and carries every other
     * datasource, Flyway and persistence setting. No line below repeats one, and no line creates the
     * schema: {@code spring.flyway.create-schemas} does that.
     *
     * @param registry the registry the Spring test context supplies
     */
    @DynamicPropertySource
    static void datasourceProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", OutboxWriterTest::migratedSchemaUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    /**
     * Returns the container uniform resource locator with {@code currentSchema} appended.
     *
     * <p>Testcontainers appends one query parameter of its own, so the separator is {@code &}
     * whenever a {@code ?} is present and {@code ?} otherwise.
     *
     * @return the connection uniform resource locator whose search path holds
     *         {@value #MIGRATED_SCHEMA}
     */
    private static String migratedSchemaUrl() {
        String url = POSTGRES.getJdbcUrl();
        String separator = url.contains("?") ? "&" : "?";
        return url + separator + "currentSchema=" + MIGRATED_SCHEMA;
    }

    /** The writer under test, taken from the context so that its transaction proxy is in play. */
    @Autowired
    private OutboxWriter outboxWriter;

    /** The card update path, which owns the transaction boundary the writer joins. */
    @Autowired
    private CardUpdateService cardUpdateService;

    /** Reads and stores card rows. */
    @Autowired
    private CardRepository cards;

    /** Reads and removes event rows. */
    @Autowired
    private OutboxEventRepository outboxEvents;

    /** Reads, stores and removes duplicate-delivery markers. */
    @Autowired
    private ProcessedEventRepository processedEvents;

    /** Opens the transactions this class starts and rolls back. */
    @Autowired
    private PlatformTransactionManager transactionManager;

    /** Reads columns straight from the migrated schema. */
    @Autowired
    private JdbcTemplate jdbc;

    /** The publisher the context injected into the relay, recording every call it takes. */
    @Autowired
    private RecordingEventPublisher publisher;

    /**
     * Leaves both outbox tables empty and inserts the two card rows the tests read.
     *
     * <p>{@code V2__seed.sql} writes no event row and no marker, and this method holds that state
     * for a repeat run in the same session.
     */
    @BeforeEach
    void insertTheCardsAndEmptyTheOutbox() {
        runInNewTransaction(() -> {
            outboxEvents.deleteAll();
            processedEvents.deleteAll();
            cards.deleteAllById(List.of(INSERTED_CARD_NUMBER, SHORT_KEY_CARD_NUMBER));
        });
        runInNewTransaction(() -> {
            cards.save(cardRow(INSERTED_CARD_NUMBER, INSERTED_ACCOUNT_ID));
            cards.save(cardRow(SHORT_KEY_CARD_NUMBER, SHORT_ACCOUNT_ID));
        });
        publisher.forget();
    }

    /** Removes every row this class wrote, leaving the fifty seeded cards for the next class. */
    @AfterEach
    void removeEveryRowThisClassWrote() {
        runInNewTransaction(() -> {
            outboxEvents.deleteAll();
            processedEvents.deleteAll();
            cards.deleteAllById(List.of(INSERTED_CARD_NUMBER, SHORT_KEY_CARD_NUMBER));
        });
        publisher.forget();
    }

    /**
     * The card change and the event row commit together, or neither of them commits.
     *
     * <p>Three tests read that property from the database. The first commits and finds both rows.
     * The second fails between the two writes and finds neither. The third calls the writer, fails
     * after it, and finds that the event row went with the card change.
     */
    @Nested
    @DisplayName("One local transaction carries the card change and the event row")
    class OneLocalTransaction {

        @Test
        @DisplayName("a committed card update leaves the changed card and one unpublished event")
        void aCommittedCardUpdateLeavesBothRows() {
            CardUpdateResponse response = cardUpdateService.updateCard(committedUpdateRequest());

            assertThat(response.outcome()).isEqualTo(UpdateOutcome.UPDATED);

            CardEntity changed = storedCard(INSERTED_CARD_NUMBER);
            assertThat(changed.getEmbossedName().strip())
                    .as("column embossed_name is CHAR(50) and PostgreSQL pads it")
                    .isEqualTo(UPDATED_EMBOSSED_NAME);
            assertThat(changed.getExpirationDate()).isEqualTo(UPDATED_EXPIRATION_DATE);
            assertThat(changed.getActiveStatus().strip())
                    .isEqualTo(CardUpdated.ACTIVE_STATUS_INACTIVE);

            List<OutboxEventEntity> stored = storedEvents();
            assertThat(stored).hasSize(1);
            assertThat(stored.getFirst().getEventType()).isEqualTo(CardUpdated.EVENT_TYPE);
            assertThat(stored.getFirst().isPublished())
                    .as("the relay has not run, so the row waits")
                    .isFalse();
        }

        @Test
        @DisplayName("a failure between the two writes leaves neither the card change nor a row")
        void aFailureBetweenTheTwoWritesLeavesNeitherRow() {
            assertThatThrownBy(() -> runInNewTransaction(() -> {
                CardEntity card = cards.findByCardNumber(INSERTED_CARD_NUMBER).orElseThrow();
                card.applyUpdate(ROLLED_BACK_EMBOSSED_NAME, ROLLED_BACK_EXPIRATION_DATE,
                        CardUpdated.ACTIVE_STATUS_INACTIVE);
                cards.save(card);
                throw new ForcedFailure();
            })).isInstanceOf(ForcedFailure.class);

            CardEntity unchanged = storedCard(INSERTED_CARD_NUMBER);
            assertThat(unchanged.getEmbossedName().strip()).isEqualTo(INSERTED_EMBOSSED_NAME);
            assertThat(unchanged.getExpirationDate()).isEqualTo(INSERTED_EXPIRATION_DATE);
            assertThat(storedEventCount())
                    .as("the writer never ran, so no row reached the table")
                    .isZero();
        }

        @Test
        @DisplayName("the writer joins the caller's transaction, so an outer rollback takes its row")
        void theWriterJoinsSoAnOuterRollbackTakesItsRow() {
            AtomicReference<UUID> written = new AtomicReference<>();

            assertThatThrownBy(() -> runInNewTransaction(() -> {
                CardEntity card = cards.findByCardNumber(INSERTED_CARD_NUMBER).orElseThrow();
                card.applyUpdate(ROLLED_BACK_EMBOSSED_NAME, ROLLED_BACK_EXPIRATION_DATE,
                        CardUpdated.ACTIVE_STATUS_INACTIVE);
                cards.save(card);
                written.set(outboxWriter.writeCardUpdated(card).getEventId());
                throw new ForcedFailure();
            })).isInstanceOf(ForcedFailure.class);

            assertThat(written.get())
                    .as("the writer ran and answered with a row, so the reads below are meaningful")
                    .isNotNull();
            assertThat(storedEvent(written.get()))
                    .as("a writer that opened its own transaction would have committed this row")
                    .isEmpty();
            assertThat(storedEventCount()).isZero();
            assertThat(storedCard(INSERTED_CARD_NUMBER).getEmbossedName().strip())
                    .as("the card change rolled back with the event row")
                    .isEqualTo(INSERTED_EMBOSSED_NAME);
        }
    }

    /**
     * The writer reaches no broker. Publication belongs to the relay, in a transaction of its own,
     * and {@code OutboxRelayTest} covers that path.
     */
    @Nested
    @DisplayName("No publish happens while the request runs")
    class NoPublishWhileTheRequestRuns {

        @Test
        @DisplayName("a committed card update calls the publisher no times")
        void aCommittedCardUpdateCallsThePublisherNoTimes() {
            CardUpdateResponse response = cardUpdateService.updateCard(committedUpdateRequest());

            assertThat(response.outcome()).isEqualTo(UpdateOutcome.UPDATED);
            assertThat(publisher.recorded())
                    .as("the card update path holds no publisher")
                    .isEmpty();
        }

        @Test
        @DisplayName("the stored row is the one durable artefact of a direct write")
        void theStoredRowIsTheOneDurableArtefactOfADirectWrite() {
            CardEntity card = storedCard(INSERTED_CARD_NUMBER);

            UUID written = outboxWriter.writeCardUpdated(card).getEventId();

            assertThat(storedEvent(written)).isPresent();
            assertThat(storedEventCount()).isEqualTo(1L);
            assertThat(publisher.recorded()).isEmpty();
        }
    }

    /**
     * The writer fixes two values the rest of the platform reads: the routing discriminator and the
     * eleven-character account key.
     *
     * <p>The relay publishes {@code aggregate_id} as the message key, so the column and the
     * {@code aggregateId} inside the payload have to agree character for character. A divergence
     * would spread the events of one account across partitions.
     */
    @Nested
    @DisplayName("The identifiers the writer defines")
    class IdentifiersTheWriterDefines {

        @Test
        @DisplayName("the row carries the discriminator CardUpdated inside its column width")
        void theRowCarriesTheDiscriminatorInsideItsColumnWidth() {
            outboxWriter.writeCardUpdated(storedCard(INSERTED_CARD_NUMBER));

            Map<String, Object> columns = storedEventColumns().getFirst();
            assertThat(columns.get("event_type")).isEqualTo(CardUpdated.EVENT_TYPE);
            assertThat(CardUpdated.EVENT_TYPE.length())
                    .as("column event_type is VARCHAR(50)")
                    .isLessThanOrEqualTo(OutboxEventEntity.EVENT_TYPE_MAX_LENGTH);
            assertThat(columns.get("published")).isEqualTo(Boolean.FALSE);
        }

        @Test
        @DisplayName("the account key holds eleven digits with its leading zeros kept")
        void theAccountKeyHoldsElevenDigitsWithItsLeadingZerosKept() {
            outboxWriter.writeCardUpdated(storedCard(SHORT_KEY_CARD_NUMBER));

            String key = String.valueOf(storedEventColumns().getFirst().get("aggregate_id"));
            assertThat(key)
                    .as("XREF-ACCT-ID PIC 9(11) at app/cpy/CVACT03Y.cpy:L7 fixes the width")
                    .hasSize(OutboxEventEntity.AGGREGATE_ID_LENGTH)
                    .matches(EventEnvelope.AGGREGATE_ID_PATTERN)
                    .isEqualTo(SHORT_ACCOUNT_ID)
                    .startsWith("0000000000");
        }

        @Test
        @DisplayName("the column and the payload carry one account key, character for character")
        void theColumnAndThePayloadCarryOneAccountKey() {
            OutboxEventEntity row = storedEvent(
                    outboxWriter.writeCardUpdated(storedCard(SHORT_KEY_CARD_NUMBER)).getEventId())
                    .orElseThrow();

            JsonNode payload = MAPPER.readTree(row.getPayload());
            assertThat(payload.get("aggregateId").stringValue()).isEqualTo(row.getAggregateId());
            assertThat(payload.get("accountId").stringValue()).isEqualTo(row.getAggregateId());
            assertThat(row.getAggregateId()).isEqualTo(SHORT_ACCOUNT_ID);
        }

        @Test
        @DisplayName("the payload carries all five envelope values and the row takes its identifier")
        void thePayloadCarriesAllFiveEnvelopeValues() {
            OutboxEventEntity row = storedEvent(
                    outboxWriter.writeCardUpdated(storedCard(INSERTED_CARD_NUMBER)).getEventId())
                    .orElseThrow();

            JsonNode payload = MAPPER.readTree(row.getPayload());
            assertThat(payload.get("eventId").stringValue())
                    .as("the primary key of the row is the identifier the event carries")
                    .isEqualTo(row.getEventId().toString());
            assertThat(payload.get("eventType").stringValue()).isEqualTo(CardUpdated.EVENT_TYPE);
            assertThat(payload.get("schemaVersion").intValue())
                    .isEqualTo(CardUpdated.SCHEMA_VERSION);
            assertThat(payload.get("occurredAt").stringValue()).endsWith("Z");
            assertThat(payload.get("aggregateId").stringValue()).isEqualTo(INSERTED_ACCOUNT_ID);
        }
    }

    /**
     * The payload carries four card values and no other. The card verification value and the full
     * Primary Account Number (PAN) stay in the card aggregate.
     *
     * <p>The stored text is JavaScript Object Notation (JSON), flat and one level deep. Each test
     * below parses that text and asserts on the properties it holds.
     */
    @Nested
    @DisplayName("The payload the writer stores")
    class PayloadTheWriterStores {

        @Test
        @DisplayName("the payload declares the nine properties its contract document requires")
        void thePayloadDeclaresTheNinePropertiesItsContractRequires() {
            JsonNode payload = MAPPER.readTree(storedPayloadOf(INSERTED_CARD_NUMBER));

            assertThat(payload.propertyNames())
                    .as("card-updated-v2.json lists these nine and closes its property set")
                    .containsExactlyInAnyOrderElementsOf(CONTRACT_PROPERTIES);
            assertThat(payload.get("accountId").stringValue()).isEqualTo(INSERTED_ACCOUNT_ID);
            assertThat(payload.get("expirationDate").stringValue())
                    .isEqualTo(INSERTED_EXPIRATION_DATE.toString());
            assertThat(payload.get("activeStatus").stringValue())
                    .isEqualTo(CardUpdated.ACTIVE_STATUS_ACTIVE);
        }

        @Test
        @DisplayName("the masked card number keeps twelve mask characters and the last four digits")
        void theMaskedCardNumberKeepsTwelveMaskCharactersAndTheLastFourDigits() {
            JsonNode payload = MAPPER.readTree(storedPayloadOf(INSERTED_CARD_NUMBER));

            String masked = payload.get("maskedCardNumber").stringValue();
            assertThat(masked)
                    .hasSize(PanMasker.CARD_NUMBER_LENGTH)
                    .matches(CardUpdated.MASKED_CARD_NUMBER_PATTERN);
            assertThat(lastFourOf(masked)).isEqualTo(lastFourOf(INSERTED_CARD_NUMBER));
        }

        @Test
        @DisplayName("the card verification value reaches no property and no value of the payload")
        void theCardVerificationValueReachesNoPartOfThePayload() {
            String payload = storedPayloadOf(INSERTED_CARD_NUMBER);

            assertThat(payload)
                    .as("a delimited JSON string carries the quoted form of its value")
                    .doesNotContain("\"" + INSERTED_VERIFICATION_VALUE + "\"");
            assertThat(MAPPER.readTree(payload).propertyNames())
                    .as("a verification value added under any name would widen this set")
                    .containsExactlyInAnyOrderElementsOf(CONTRACT_PROPERTIES)
                    .noneMatch(name -> name.toLowerCase(Locale.ROOT).contains("verification"))
                    .noneMatch(name -> name.toLowerCase(Locale.ROOT).contains("cvv"));
        }

        @Test
        @DisplayName("the full card number reaches no part of the payload")
        void theFullCardNumberReachesNoPartOfThePayload() {
            String payload = storedPayloadOf(INSERTED_CARD_NUMBER);

            assertThat(payload)
                    .as("the card read keys on all sixteen characters and the payload carries none")
                    .doesNotContain(INSERTED_CARD_NUMBER);
            assertThat(payload).doesNotContain(
                    INSERTED_CARD_NUMBER.substring(0,
                            PanMasker.CARD_NUMBER_LENGTH - PanMasker.VISIBLE_DIGIT_COUNT));
        }

        @Test
        @DisplayName("the payload is JavaScript Object Notation the event record reads back whole")
        void thePayloadIsNotationTheEventRecordReadsBackWhole() {
            OutboxEventEntity row = storedEvent(
                    outboxWriter.writeCardUpdated(storedCard(INSERTED_CARD_NUMBER)).getEventId())
                    .orElseThrow();

            CardUpdated parsed = MAPPER.readValue(row.getPayload(), CardUpdated.class);

            assertThat(parsed.eventId()).isEqualTo(row.getEventId());
            assertThat(parsed.eventType()).isEqualTo(row.getEventType());
            assertThat(parsed.aggregateId()).isEqualTo(row.getAggregateId());
            assertThat(parsed.accountId()).isEqualTo(INSERTED_ACCOUNT_ID);
            assertThat(parsed.activeStatus()).isEqualTo(CardUpdated.ACTIVE_STATUS_ACTIVE);
            assertThat(MAPPER.writeValueAsString(MAPPER.readTree(row.getPayload())))
                    .as("the text survives a parse and a rewrite")
                    .isNotBlank();
        }
    }

    /**
     * A duplicate-delivery marker commits with the effect it guards, or not at all.
     *
     * <p>The card service subscribes to no topic today, so no consumer exercises the marker. The two
     * tests below hold the property for the first consumer this schema serves.
     */
    @Nested
    @DisplayName("The duplicate-delivery marker follows its transaction")
    class MarkerAtomicity {

        @Test
        @DisplayName("a rolled-back effect leaves no marker behind")
        void aRolledBackEffectLeavesNoMarkerBehind() {
            assertThatThrownBy(() -> runInNewTransaction(() -> {
                CardEntity card = cards.findByCardNumber(INSERTED_CARD_NUMBER).orElseThrow();
                card.applyUpdate(ROLLED_BACK_EMBOSSED_NAME, ROLLED_BACK_EXPIRATION_DATE,
                        CardUpdated.ACTIVE_STATUS_INACTIVE);
                cards.save(card);
                processedEvents.save(new ProcessedEventEntity(MARKER_EVENT_ID, Instant.now()));
                throw new ForcedFailure();
            })).isInstanceOf(ForcedFailure.class);

            assertThat(markerExists(MARKER_EVENT_ID))
                    .as("a marker that outlived its effect would suppress a needed retry")
                    .isFalse();
            assertThat(storedCard(INSERTED_CARD_NUMBER).getEmbossedName().strip())
                    .isEqualTo(INSERTED_EMBOSSED_NAME);
        }

        @Test
        @DisplayName("a committed marker is found by the same read")
        void aCommittedMarkerIsFoundByTheSameRead() {
            runInNewTransaction(() ->
                    processedEvents.save(new ProcessedEventEntity(MARKER_EVENT_ID, Instant.now())));

            assertThat(markerExists(MARKER_EVENT_ID))
                    .as("the read above answers true for a marker that committed")
                    .isTrue();
        }
    }

    /**
     * The card service produces an event on a state change and on nothing else.
     *
     * <p>{@code app/cbl/COCRDUPC.cbl:L188} answers an unchanged record with
     * {@code 'No change detected with respect to values fetched.'}, and the sibling test packages
     * hold that text. The assertion here reads the table.
     */
    @Nested
    @DisplayName("A card update that changes nothing produces no event")
    class PublishOnlyOnStateChange {

        @Test
        @DisplayName("submitting the stored values leaves the outbox empty")
        void submittingTheStoredValuesLeavesTheOutboxEmpty() {
            CardUpdateResponse response = cardUpdateService.updateCard(unchangedUpdateRequest());

            assertThat(response.outcome()).isEqualTo(UpdateOutcome.NO_CHANGE_DETECTED);
            assertThat(storedEventCount())
                    .as("a supporting service produces an event on a state change alone")
                    .isZero();
            assertThat(publisher.recorded()).isEmpty();
        }
    }

    /**
     * Runs one callback in a transaction of its own and answers with its result.
     *
     * @param <T>  the result type
     * @param work the work to run
     * @return whatever the callback answered
     */
    private <T> T inNewTransaction(Supplier<T> work) {
        TransactionTemplate template = new TransactionTemplate(transactionManager);
        template.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        return template.execute(status -> work.get());
    }

    /**
     * Runs one callback in a transaction of its own and answers with nothing.
     *
     * @param work the work to run
     */
    private void runInNewTransaction(Runnable work) {
        inNewTransaction(() -> {
            work.run();
            return null;
        });
    }

    /**
     * Reads one card row in a transaction of its own.
     *
     * @param cardNumber the sixteen-character key of the row to read
     * @return the row, detached once the transaction closes
     */
    private CardEntity storedCard(String cardNumber) {
        return inNewTransaction(() -> cards.findByCardNumber(cardNumber).orElseThrow());
    }

    /**
     * Reads every event row in a transaction of its own.
     *
     * @return the rows the table holds
     */
    private List<OutboxEventEntity> storedEvents() {
        return inNewTransaction(outboxEvents::findAll);
    }

    /**
     * Counts the event rows in a transaction of its own.
     *
     * @return how many rows the table holds
     */
    private long storedEventCount() {
        return inNewTransaction(outboxEvents::count);
    }

    /**
     * Reads one event row by its primary key, in a transaction of its own.
     *
     * @param eventId the identifier the writer assigned
     * @return the row, or an empty answer where the key names none
     */
    private Optional<OutboxEventEntity> storedEvent(UUID eventId) {
        return inNewTransaction(() -> outboxEvents.findById(eventId));
    }

    /**
     * Reads three columns of every event row straight from the migrated schema.
     *
     * @return one map per row, keyed on the column names {@code V1__schema.sql} declares
     */
    private List<Map<String, Object>> storedEventColumns() {
        return jdbc.queryForList(EVENT_COLUMNS_SQL);
    }

    /**
     * Reports whether one duplicate-delivery marker committed.
     *
     * @param eventId the identifier the marker carries
     * @return {@code true} where the row is present
     */
    private boolean markerExists(UUID eventId) {
        return inNewTransaction(() -> processedEvents.existsByEventId(eventId));
    }

    /**
     * Stores one event for the named card and answers with the text the row holds.
     *
     * @param cardNumber the sixteen-character key of the card the event describes
     * @return the stored payload, as the column holds it
     */
    private String storedPayloadOf(String cardNumber) {
        UUID written = outboxWriter.writeCardUpdated(storedCard(cardNumber)).getEventId();
        return storedEvent(written).orElseThrow().getPayload();
    }

    /**
     * Builds one card row for the named key and account.
     *
     * @param cardNumber the sixteen-character key
     * @param accountId  the eleven-digit account identifier
     * @return a row carrying the inserted verification value, name, expiry and status
     */
    private static CardEntity cardRow(String cardNumber, String accountId) {
        return new CardEntity(cardNumber, accountId, INSERTED_VERIFICATION_VALUE,
                INSERTED_EMBOSSED_NAME, INSERTED_EXPIRATION_DATE,
                CardUpdated.ACTIVE_STATUS_ACTIVE);
    }

    /**
     * Builds the request that changes the name, the expiry and the status of the inserted card.
     *
     * @return a request every field edit accepts
     */
    private static CardUpdateRequest committedUpdateRequest() {
        return new CardUpdateRequest(INSERTED_CARD_NUMBER, UPDATED_EMBOSSED_NAME,
                UPDATED_EXPIRY_YEAR, UPDATED_EXPIRY_MONTH, INSERTED_EXPIRY_DAY,
                CardUpdated.ACTIVE_STATUS_INACTIVE);
    }

    /**
     * Builds the request that submits the values the inserted card already holds.
     *
     * @return a request naming the stored name, expiry and status
     */
    private static CardUpdateRequest unchangedUpdateRequest() {
        return new CardUpdateRequest(INSERTED_CARD_NUMBER, INSERTED_EMBOSSED_NAME,
                INSERTED_EXPIRY_YEAR, INSERTED_EXPIRY_MONTH, INSERTED_EXPIRY_DAY,
                CardUpdated.ACTIVE_STATUS_ACTIVE);
    }

    /**
     * Reads the last four characters of one value.
     *
     * @param value the text to read
     * @return its final {@value PanMasker#VISIBLE_DIGIT_COUNT} characters
     */
    private static String lastFourOf(String value) {
        return value.substring(value.length() - PanMasker.VISIBLE_DIGIT_COUNT);
    }

    /**
     * Builds a sixteen-digit card number no fixture carries.
     *
     * @param serial the trailing serial, padded on the left with zeros
     * @return four nines followed by twelve digits of the serial
     */
    private static String syntheticCardNumber(long serial) {
        return "9999" + String.format(Locale.ROOT, "%012d", serial);
    }

    /**
     * Contributes the recording publisher the context injects into the relay.
     *
     * <p>The {@code classes} attribute of {@link SpringBootTest} names this class beside
     * {@link CardApplication}. Default detection of a nested configuration class reads the
     * immediate test class, and every test here sits in a {@link Nested} group.
     */
    @TestConfiguration
    static class RecordingPublisherConfiguration {

        /**
         * Builds the recorder the relay publishes through while this class runs.
         *
         * @return the one publisher the context prefers
         */
        @Bean
        @Primary
        RecordingEventPublisher recordingEventPublisher() {
            return new RecordingEventPublisher();
        }
    }

    /**
     * Records every call it takes and reaches no broker.
     *
     * <p>{@code messaging/KafkaEventPublisher} implements the same port in production, and
     * {@code outbox/OutboxRelay} is its one caller.
     */
    static final class RecordingEventPublisher implements EventPublisherPort {

        /** Every call taken, in the order the calls arrived. */
        private final List<PublishedMessage> calls = new CopyOnWriteArrayList<>();

        @Override
        public void publish(String topic, String aggregateId, String payload) {
            calls.add(new PublishedMessage(topic, aggregateId, payload));
        }

        /**
         * Answers with the calls taken so far.
         *
         * @return one entry per call, oldest first
         */
        List<PublishedMessage> recorded() {
            return List.copyOf(calls);
        }

        /** Drops every recorded call, which each test does before and after it runs. */
        void forget() {
            calls.clear();
        }
    }

    /**
     * One publish call: the topic named, the message key supplied, and the text carried.
     *
     * @param topic       the topic the caller named
     * @param aggregateId the message key the caller supplied
     * @param payload     the text the caller carried
     */
    record PublishedMessage(String topic, String aggregateId, String payload) {

        /**
         * Names the topic and withholds the key and the text.
         *
         * @return a rendering a failed assertion can print
         */
        @Override
        public String toString() {
            return "PublishedMessage[topic=" + topic + ", aggregateId=" + EventEnvelope.WITHHELD
                    + ", payload=" + EventEnvelope.WITHHELD + "]";
        }
    }

    /** The failure a test raises to roll one unit of work back. */
    private static final class ForcedFailure extends RuntimeException {

        /** Serialization identifier, required of every throwable. */
        private static final long serialVersionUID = 1L;

        private ForcedFailure() {
            super("a test forced this unit of work to fail");
        }
    }
}
