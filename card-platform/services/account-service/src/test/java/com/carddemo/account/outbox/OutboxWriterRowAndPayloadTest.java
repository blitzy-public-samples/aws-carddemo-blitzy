package com.carddemo.account.outbox;

import com.carddemo.account.entity.OutboxEventEntity;
import com.carddemo.account.messaging.AccountStateChanged;
import com.carddemo.account.messaging.EventPublisherPort;
import com.carddemo.account.repository.AbstractAccountPostgresTest;
import com.carddemo.account.repository.OutboxEventRepository;
import com.networknt.schema.Error;
import com.networknt.schema.InputFormat;
import com.networknt.schema.Schema;
import com.networknt.schema.SchemaRegistry;
import com.networknt.schema.SpecificationVersion;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;
import java.util.regex.Pattern;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Asserts what {@link OutboxWriter} produces: one {@code outbox_event} row and one JavaScript
 * Object Notation (JSON) payload.
 *
 * <p>The writer joins the transaction its caller opened and starts none. A call with no
 * transaction open fails. On insert the row is unpublished and its publication instant is empty.
 *
 * <p>Every monetary property travels as a quoted decimal string carrying two fractional digits.
 * Most parsers read a JSON number into a double, which puts binary floating point back into a
 * system whose correctness rests on fixed-point arithmetic. Rationale for the wire form sits in
 * {@code card-platform/docs/decision-log.md}.
 *
 * <p>Scale provenance: {@code app/cpy/CVACT01Y.cpy} declares five {@code PIC S9(10)V99} fields, at
 * {@code L7} current balance, {@code L8} credit limit, {@code L9} cash credit limit, {@code L13}
 * current cycle credit and {@code L14} current cycle debit.
 *
 * <p>{@code aggregate_id} carries the account identifier, from {@code ACCT-ID PIC 9(11)} at
 * {@code app/cpy/CVACT01Y.cpy:L5}, whose key width is {@code KEYS(11 0)} at
 * {@code app/jcl/ACCTFILE.jcl:L40}. The column stores fixed-width text, so a leading zero
 * survives. Every account identifier below opens with a zero.
 *
 * <p>{@code expirationDate} travels as ten characters of text and carries no date type. The field
 * is {@code ACCT-EXPIRAION-DATE PIC X(10)} at {@code app/cpy/CVACT01Y.cpy:L11}, and
 * {@code app/cbl/CBTRN02C.cbl:L414} reads
 * {@code IF ACCT-EXPIRAION-DATE >= DALYTRAN-ORIG-TS (1:10)}.
 *
 * <p>The one ancestor construct is the Transient Data Queue write of the Customer Information
 * Control System (CICS) at {@code app/cbl/CORPT00C.cbl:L517-L518}. One program writes the record
 * there and a separate job collects it. {@code card-platform/docs/event-flow.md} draws the message
 * path.
 *
 * <p>Four neighbours own what this class leaves alone. {@code OutboxRelayTest} owns every relay
 * assertion. {@code repository/OutboxEventRepositoryTest} owns the repository surface and the
 * Jakarta Persistence (JPA) identifier type. {@code repository/SchemaColumnTypeTest} owns the Data
 * Definition Language (DDL) column types. {@code domain/AccountUpdateServiceTest} and
 * {@code domain/BillingCycleServiceTest} own the service-level row outcome.
 *
 * <p>Every write below runs inside a transaction this class opens and then rolls back. Nothing
 * commits, so every assertion reads insert-time state, the relay sweep reads no row of this class,
 * and the seed row counts other test classes read stay intact.
 *
 * <p>The class annotation names {@link RecordingPublisherConfiguration}. A group annotated
 * {@code Nested} resolves its configuration from its own declared classes, and the class annotation
 * carries that registration to all four groups. The bean definitions of the module arrive alongside
 * that configuration, from the class annotated {@code SpringBootConfiguration}.
 */
@DisplayName("OutboxWriter, the row and the payload one insert produces")
@ContextConfiguration(classes = OutboxWriterRowAndPayloadTest.RecordingPublisherConfiguration.class)
class OutboxWriterRowAndPayloadTest extends AbstractAccountPostgresTest {

    /** The one value {@code event_type} carries. */
    private static final String EVENT_TYPE = AccountStateChanged.EVENT_TYPE;

    /** The account an account update reports, opening with nine zeros. */
    private static final String ACCOUNT_ID = "00000000042";

    /** The account a cycle close reports, opening with ten zeros. */
    private static final String CYCLE_CLOSE_ACCOUNT_ID = "00000000007";

    /** The account expiry text, ten characters wide. */
    private static final String EXPIRATION_DATE = "2026-12-31";

    /** Characters {@code expirationDate} carries, from {@code PIC X(10)}. */
    private static final int EXPIRATION_DATE_LENGTH =
            AccountStateChanged.EXPIRATION_DATE_MAX_LENGTH;

    /** Characters the canonical text form of a {@code UUID} spans. */
    private static final int EVENT_ID_TEXT_LENGTH = 36;

    /** Digits an account identifier carries, from {@code KEYS(11 0)}. */
    private static final int ACCOUNT_ID_LENGTH = OutboxEventEntity.AGGREGATE_ID_LENGTH;

    /** The contract version every payload of this class reports. */
    private static final int SCHEMA_VERSION = AccountStateChanged.SCHEMA_VERSION;

    /** Fractional digits every monetary property carries on the wire. */
    private static final int MONETARY_SCALE = AccountStateChanged.MONETARY_SCALE;

    /** The schema document, read from the classpath of {@code com.carddemo:event-contracts}. */
    private static final String SCHEMA_RESOURCE = "schemas/account-state-changed-v1.json";

    /** The form a monetary property takes: optional minus, integer digits, point, two digits. */
    private static final Pattern MONETARY_FORM =
            Pattern.compile(AccountStateChanged.MONETARY_PATTERN);

    /** The form an account identifier takes: exactly eleven decimal digits. */
    private static final Pattern ACCOUNT_ID_FORM =
            Pattern.compile(AccountStateChanged.ACCOUNT_ID_PATTERN);

    /** The property name a nested envelope would occupy. No payload carries it. */
    private static final String NESTED_ENVELOPE_PROPERTY = "envelope";

    /** The five envelope properties, at the same level as the payload properties. */
    private static final Set<String> ENVELOPE_PROPERTIES = Set.of(
            "eventId", "eventType", "schemaVersion", "occurredAt", "aggregateId");

    /** The four monetary properties, in record component order. */
    private static final List<String> MONETARY_PROPERTIES = List.of(
            "currentBalance", "creditLimit", "currentCycleCredit", "currentCycleDebit");

    /** Every property one payload carries, envelope and payload at one level. */
    private static final Set<String> WIRE_PROPERTIES = Set.of(
            "eventId", "eventType", "schemaVersion", "occurredAt", "aggregateId",
            "accountId", "changeKind",
            "currentBalance", "creditLimit", "currentCycleCredit", "currentCycleDebit",
            "expirationDate");

    /** The two values {@code changeKind} carries. */
    private static final Set<String> CHANGE_KINDS = Set.of(
            AccountStateChanged.ChangeKind.ACCOUNT_UPDATED.name(),
            AccountStateChanged.ChangeKind.BILLING_CYCLE_CLOSED.name());

    /** A change kind the document declares nowhere. */
    private static final String UNDECLARED_CHANGE_KIND = "ACCOUNT_CLOSED";

    /** The balance an account update reports, already at two fractional digits. */
    private static final BigDecimal CURRENT_BALANCE = new BigDecimal("1250.75");

    /** The credit limit an account update reports, supplied with no fractional digit. */
    private static final BigDecimal CREDIT_LIMIT = new BigDecimal("1940");

    /** The cycle credit accumulator an account update reports, supplied as a bare zero. */
    private static final BigDecimal CYCLE_CREDIT = BigDecimal.ZERO;

    /** The cycle debit accumulator an account update reports, supplied with one decimal digit. */
    private static final BigDecimal CYCLE_DEBIT = new BigDecimal("-125.5");

    /** The text {@link #CREDIT_LIMIT} takes on the wire, with the trailing zeros the scale adds. */
    private static final String CREDIT_LIMIT_TEXT = "1940.00";

    /** The text {@link #CYCLE_CREDIT} takes on the wire. */
    private static final String CYCLE_CREDIT_TEXT = "0.00";

    /** The text {@link #CYCLE_DEBIT} takes on the wire. */
    private static final String CYCLE_DEBIT_TEXT = "-125.50";

    /** The prefix the class name of a Jackson 3 mapper opens with. */
    private static final String JACKSON_THREE_PACKAGE = "tools.jackson";

    /** {@link #SCHEMA_RESOURCE} compiled once as JSON Schema Draft 2020-12. */
    private static final Schema ACCOUNT_STATE_CHANGED_SCHEMA = compileSchema();

    /** The container-managed proxy carrying the transaction interceptor. */
    @Autowired
    private OutboxWriter outboxWriter;

    /** Reads back the row the writer inserted. */
    @Autowired
    private OutboxEventRepository outboxEventRepository;

    /** The module-local Jackson 3 mapper, bean {@code accountEventObjectMapper}. */
    @Autowired
    @Qualifier("accountEventObjectMapper")
    private ObjectMapper objectMapper;

    /** Opens the transaction the writer joins. */
    @Autowired
    private PlatformTransactionManager transactionManager;

    /** Forces the insert and drops the first-level cache, so a read reaches the database. */
    @PersistenceContext
    private EntityManager entityManager;

    /** The publisher every bean of the context reaches, recording and reaching no broker. */
    @Autowired
    private RecordingEventPublisher recordingEventPublisher;

    /**
     * Compiles the schema document from the classpath.
     *
     * @return the compiled schema
     * @throws IllegalStateException when the classpath holds no such resource, or reading it fails
     */
    private static Schema compileSchema() {
        SchemaRegistry registry =
                SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_2020_12);
        try (InputStream document =
                OutboxWriterRowAndPayloadTest.class.getClassLoader().getResourceAsStream(SCHEMA_RESOURCE)) {
            if (document == null) {
                throw new IllegalStateException(
                        "classpath resource " + SCHEMA_RESOURCE + " is missing");
            }
            return registry.getSchema(document, InputFormat.JSON);
        } catch (IOException cause) {
            throw new IllegalStateException(
                    "classpath resource " + SCHEMA_RESOURCE + " could not be read", cause);
        }
    }

    /**
     * Builds one account-update event for the account that opens with eight zeros.
     *
     * @return an event carrying a fresh envelope and the four monetary values of this class
     */
    private static AccountStateChanged accountUpdated() {
        return AccountStateChanged.of(ACCOUNT_ID,
                AccountStateChanged.ChangeKind.ACCOUNT_UPDATED,
                CURRENT_BALANCE, CREDIT_LIMIT, CYCLE_CREDIT, CYCLE_DEBIT, EXPIRATION_DATE);
    }

    /**
     * Builds one cycle-close event, reporting both accumulators at zero.
     *
     * <p>{@code app/cbl/CBACT04C.cbl:L353-L354} zeroes both accumulators, and a cycle-close event
     * reports what that leaves.
     *
     * @return an event carrying a fresh envelope and both accumulators at zero
     */
    private static AccountStateChanged billingCycleClosed() {
        return AccountStateChanged.of(CYCLE_CLOSE_ACCOUNT_ID,
                AccountStateChanged.ChangeKind.BILLING_CYCLE_CLOSED,
                CURRENT_BALANCE, CREDIT_LIMIT, BigDecimal.ZERO, BigDecimal.ZERO, EXPIRATION_DATE);
    }

    /**
     * Runs one body inside a transaction of its own and rolls that transaction back.
     *
     * <p>The writer requires an open transaction, and the body reads insert-time state. The
     * rollback runs whether the body returns or throws.
     *
     * @param body the assertions to run inside the transaction
     */
    private void inRolledBackTransaction(Consumer<TransactionStatus> body) {
        TransactionTemplate transaction = new TransactionTemplate(transactionManager);
        transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        transaction.executeWithoutResult(status -> {
            try {
                body.accept(status);
            } finally {
                status.setRollbackOnly();
            }
        });
    }

    /**
     * Writes one event and reads its row back from the database.
     *
     * <p>The flush issues the insert, so the table constraints of {@code V1__schema.sql} run here.
     * Clearing the first-level cache sends the read to the database.
     *
     * @param event the event to write
     * @return the row the writer inserted
     */
    private OutboxEventEntity writeAndRead(AccountStateChanged event) {
        outboxWriter.write(event);
        entityManager.flush();
        entityManager.clear();
        return outboxEventRepository.findById(event.eventId())
                .orElseThrow(() -> new AssertionError("the writer left no row for the event"));
    }

    /**
     * Parses the payload one row carries.
     *
     * @param row the row the writer inserted
     * @return the payload as a tree
     */
    private JsonNode wireForm(OutboxEventEntity row) {
        return objectMapper.readTree(row.getPayload());
    }

    /**
     * Returns the top-level property names one payload carries, in document order.
     *
     * @param wire the parsed payload
     * @return the property names
     */
    private static Set<String> propertyNamesOf(JsonNode wire) {
        return new LinkedHashSet<>(wire.propertyNames());
    }

    /**
     * Validates one document against {@link #SCHEMA_RESOURCE}.
     *
     * @param json the document to validate
     * @return every violation the schema reports, empty when the document conforms
     */
    private static List<Error> violationsOf(String json) {
        return ACCOUNT_STATE_CHANGED_SCHEMA.validate(json, InputFormat.JSON);
    }

    /**
     * Renders every violation as a pointer and a broken keyword, for a failure message.
     *
     * @param violations the violations to render
     * @return one line naming each violation
     */
    private static String describe(List<Error> violations) {
        return violations.stream()
                .map(violation -> violation.getInstanceLocation() + " (" + violation.getKeyword()
                        + ")")
                .toList()
                .toString();
    }

    /**
     * Returns one payload with a single property replaced by a JSON number.
     *
     * @param payload  the conforming payload
     * @param property the property to replace
     * @param number   the numeric value to write
     * @return the mutated document as text
     */
    private String withNumericProperty(String payload, String property, BigDecimal number) {
        ObjectNode mutated = (ObjectNode) objectMapper.readTree(payload);
        mutated.put(property, number);
        return objectMapper.writeValueAsString(mutated);
    }

    /**
     * Returns one payload with a single property replaced by text.
     *
     * @param payload  the conforming payload
     * @param property the property to replace
     * @param text     the text to write
     * @return the mutated document as text
     */
    private String withTextProperty(String payload, String property, String text) {
        ObjectNode mutated = (ObjectNode) objectMapper.readTree(payload);
        mutated.put(property, text);
        return objectMapper.writeValueAsString(mutated);
    }

    /** Asserts the propagation contract the writer declares and the interceptor enforces. */
    @Nested
    @DisplayName("The transaction the caller opens")
    class PropagationContract {

        @Test
        @DisplayName("A write with no transaction open fails and leaves no row")
        void aWriteWithNoTransactionOpenFailsAndLeavesNoRow() {
            AccountStateChanged event = accountUpdated();
            long before = outboxEventRepository.count();

            assertThatThrownBy(() -> outboxWriter.write(event))
                    .as("a write reaching the writer with no transaction open")
                    .isInstanceOf(IllegalTransactionStateException.class);

            assertThat(outboxEventRepository.count())
                    .as("rows in outbox_event after the refused write")
                    .isEqualTo(before);
            assertThat(outboxEventRepository.findById(event.eventId()))
                    .as("a row for the identifier of the refused event")
                    .isEmpty();
        }

        @Test
        @DisplayName("The write method declares MANDATORY propagation")
        void theWriteMethodDeclaresMandatoryPropagation() throws NoSuchMethodException {
            Method write = OutboxWriter.class.getMethod("write", AccountStateChanged.class);

            Transactional declared =
                    AnnotatedElementUtils.findMergedAnnotation(write, Transactional.class);

            assertThat(declared)
                    .as("the transactional annotation OutboxWriter.write carries")
                    .isNotNull();
            assertThat(declared.propagation())
                    .as("the propagation OutboxWriter.write declares")
                    .isEqualTo(Propagation.MANDATORY);
        }

        @Test
        @DisplayName("A write inside an open transaction returns and reaches no broker")
        void aWriteInsideAnOpenTransactionReturnsAndReachesNoBroker() {
            AccountStateChanged event = accountUpdated();
            recordingEventPublisher.reset();

            inRolledBackTransaction(status -> {
                OutboxEventEntity row = writeAndRead(event);

                assertThat(row.getEventId())
                        .as("the identifier of the row the writer inserted")
                        .isEqualTo(event.eventId());
                assertThat(recordingEventPublisher.publicationsCarrying(ACCOUNT_ID))
                        .as("publications the writer took for account %s", ACCOUNT_ID)
                        .isEmpty();
            });
        }
    }

    /** Asserts the row the writer inserts, column by column, before any commit. */
    @Nested
    @DisplayName("The row the writer inserts")
    class PersistedRow {

        @Test
        @DisplayName("One write adds exactly one row")
        void oneWriteAddsExactlyOneRow() {
            AccountStateChanged event = accountUpdated();

            inRolledBackTransaction(status -> {
                long before = outboxEventRepository.count();

                writeAndRead(event);

                assertThat(outboxEventRepository.count() - before)
                        .as("rows one write adds to outbox_event")
                        .isEqualTo(1L);
                assertThat(outboxEventRepository.findById(event.eventId()))
                        .as("the row carrying the identifier of the written event")
                        .isPresent();
            });
        }

        @Test
        @DisplayName("The identifier column carries the envelope identifier, 36 characters as text")
        void theIdentifierColumnCarriesTheEnvelopeIdentifier() {
            AccountStateChanged event = accountUpdated();

            inRolledBackTransaction(status -> {
                OutboxEventEntity row = writeAndRead(event);

                UUID stored = row.getEventId();
                assertThat(stored)
                        .as("the identifier the writer copied from the envelope")
                        .isEqualTo(event.eventId());
                assertThat(stored.toString())
                        .as("the canonical text form of the stored identifier")
                        .hasSize(EVENT_ID_TEXT_LENGTH)
                        .isEqualTo(event.eventId().toString());
            });
        }

        @Test
        @DisplayName("The event type column carries AccountStateChanged, within the column width")
        void theEventTypeColumnCarriesAccountStateChanged() {
            AccountStateChanged event = accountUpdated();

            inRolledBackTransaction(status -> {
                OutboxEventEntity row = writeAndRead(event);

                assertThat(row.getEventType())
                        .as("the event type the writer copied from the envelope")
                        .isEqualTo(EVENT_TYPE)
                        .hasSizeLessThanOrEqualTo(OutboxEventEntity.EVENT_TYPE_MAX_LENGTH);
            });
        }

        @Test
        @DisplayName("The creation column carries the envelope instant")
        void theCreationColumnCarriesTheEnvelopeInstant() {
            AccountStateChanged event = accountUpdated();

            inRolledBackTransaction(status -> {
                OutboxEventEntity row = writeAndRead(event);

                assertThat(row.getCreatedAt())
                        .as("the instant the writer copied from the envelope")
                        .isNotNull()
                        .isEqualTo(event.occurredAt());
            });
        }

        @Test
        @DisplayName("The aggregate column carries eleven digits and keeps the leading zero")
        void theAggregateColumnKeepsTheLeadingZero() {
            AccountStateChanged event = accountUpdated();

            inRolledBackTransaction(status -> {
                OutboxEventEntity row = writeAndRead(event);

                String stored = row.getAggregateId();
                assertThat(stored)
                        .as("the account identifier the writer copied from the envelope")
                        .isEqualTo(ACCOUNT_ID)
                        .hasSize(ACCOUNT_ID_LENGTH)
                        .startsWith("0");
                assertThat(ACCOUNT_ID_FORM.matcher(stored).matches())
                        .as("%s matches %s", stored, AccountStateChanged.ACCOUNT_ID_PATTERN)
                        .isTrue();
            });
        }

        @Test
        @DisplayName("The row is unpublished and its publication instant is empty")
        void theRowIsUnpublishedAndItsPublicationInstantIsEmpty() {
            AccountStateChanged event = accountUpdated();

            inRolledBackTransaction(status -> {
                OutboxEventEntity row = writeAndRead(event);

                assertThat(row.isPublished())
                        .as("the publication flag of the row at insert time")
                        .isFalse();
                assertThat(row.getPublishedAt())
                        .as("the publication instant of the row at insert time")
                        .isNull();
            });
        }

        @Test
        @DisplayName("The payload column carries one JSON object")
        void thePayloadColumnCarriesOneJsonObject() {
            AccountStateChanged event = accountUpdated();

            inRolledBackTransaction(status -> {
                OutboxEventEntity row = writeAndRead(event);

                assertThat(row.getPayload())
                        .as("the payload the writer serialized")
                        .isNotBlank();
                assertThat(wireForm(row).isObject())
                        .as("the payload parses as one JSON object")
                        .isTrue();
            });
        }

        @Test
        @DisplayName("A cycle close writes one row for its own account")
        void aCycleCloseWritesOneRowForItsOwnAccount() {
            AccountStateChanged event = billingCycleClosed();

            inRolledBackTransaction(status -> {
                OutboxEventEntity row = writeAndRead(event);

                assertThat(row.getAggregateId())
                        .as("the account identifier of the cycle-close row")
                        .isEqualTo(CYCLE_CLOSE_ACCOUNT_ID)
                        .hasSize(ACCOUNT_ID_LENGTH);
                assertThat(row.getEventType())
                        .as("the event type of the cycle-close row")
                        .isEqualTo(EVENT_TYPE);
            });
        }
    }

    /** Asserts the payload the writer serialized into the row. */
    @Nested
    @DisplayName("The payload the writer serialized")
    class WireForm {

        @Test
        @DisplayName("The mapper the writer serializes with is a Jackson 3 mapper")
        void theMapperIsAJacksonThreeMapper() {
            assertThat(objectMapper.getClass().getName())
                    .as("the class of bean accountEventObjectMapper")
                    .startsWith(JACKSON_THREE_PACKAGE);
        }

        @Test
        @DisplayName("The payload carries twelve properties at one level and no nested envelope")
        void thePayloadCarriesTwelvePropertiesAtOneLevel() {
            AccountStateChanged event = accountUpdated();

            inRolledBackTransaction(status -> {
                JsonNode wire = wireForm(writeAndRead(event));

                assertThat(propertyNamesOf(wire))
                        .as("the top-level property names of the payload")
                        .containsExactlyInAnyOrderElementsOf(WIRE_PROPERTIES)
                        .hasSize(WIRE_PROPERTIES.size());
                assertThat(wire.has(NESTED_ENVELOPE_PROPERTY))
                        .as("a nested %s object in the payload", NESTED_ENVELOPE_PROPERTY)
                        .isFalse();
            });
        }

        @Test
        @DisplayName("The five envelope properties sit beside the payload properties")
        void theFiveEnvelopePropertiesSitBesideThePayloadProperties() {
            AccountStateChanged event = accountUpdated();

            inRolledBackTransaction(status -> {
                JsonNode wire = wireForm(writeAndRead(event));

                assertThat(propertyNamesOf(wire))
                        .as("the top-level property names of the payload")
                        .containsAll(ENVELOPE_PROPERTIES);
                for (String property : ENVELOPE_PROPERTIES) {
                    assertThat(wire.get(property))
                            .as("envelope property %s", property)
                            .isNotNull();
                    assertThat(wire.get(property).isNull())
                            .as("envelope property %s is null", property)
                            .isFalse();
                }
                assertThat(wire.get("eventId").stringValue())
                        .as("the identifier the payload reports")
                        .isEqualTo(event.eventId().toString())
                        .hasSize(EVENT_ID_TEXT_LENGTH);
            });
        }

        @Test
        @DisplayName("The event type reports AccountStateChanged")
        void theEventTypeReportsAccountStateChanged() {
            AccountStateChanged event = accountUpdated();

            inRolledBackTransaction(status -> {
                JsonNode wire = wireForm(writeAndRead(event));

                assertThat(wire.get("eventType").isString())
                        .as("eventType is textual")
                        .isTrue();
                assertThat(wire.get("eventType").stringValue())
                        .as("the event type the payload reports")
                        .isEqualTo(EVENT_TYPE);
            });
        }

        @Test
        @DisplayName("The schema version is an unquoted integer 1")
        void theSchemaVersionIsAnUnquotedInteger() {
            AccountStateChanged event = accountUpdated();

            inRolledBackTransaction(status -> {
                JsonNode wire = wireForm(writeAndRead(event));

                JsonNode version = wire.get("schemaVersion");
                assertThat(version.isIntegralNumber())
                        .as("schemaVersion is an integral number")
                        .isTrue();
                assertThat(version.isString())
                        .as("schemaVersion is textual")
                        .isFalse();
                assertThat(version.intValue())
                        .as("the contract version the payload reports")
                        .isEqualTo(SCHEMA_VERSION);
            });
        }

        @Test
        @DisplayName("The occurrence instant is an ISO-8601 instant carrying a zone designator")
        void theOccurrenceInstantIsAnIsoInstant() {
            AccountStateChanged event = accountUpdated();

            inRolledBackTransaction(status -> {
                JsonNode wire = wireForm(writeAndRead(event));

                JsonNode occurredAt = wire.get("occurredAt");
                assertThat(occurredAt.isString())
                        .as("occurredAt is textual")
                        .isTrue();
                assertThat(occurredAt.stringValue())
                        .as("the occurrence instant the payload reports")
                        .endsWith("Z");
                assertThat(Instant.parse(occurredAt.stringValue()))
                        .as("the occurrence instant the payload reports, parsed")
                        .isEqualTo(event.occurredAt());
            });
        }

        @Test
        @DisplayName("The aggregate identifier is eleven-digit text keeping its leading zero")
        void theAggregateIdentifierIsElevenDigitText() {
            AccountStateChanged event = accountUpdated();

            inRolledBackTransaction(status -> {
                JsonNode wire = wireForm(writeAndRead(event));

                JsonNode aggregateId = wire.get("aggregateId");
                assertThat(aggregateId.isString())
                        .as("aggregateId is textual")
                        .isTrue();
                assertThat(aggregateId.isNumber())
                        .as("aggregateId is numeric")
                        .isFalse();
                assertThat(aggregateId.stringValue())
                        .as("the aggregate identifier the payload reports")
                        .isEqualTo(ACCOUNT_ID)
                        .hasSize(ACCOUNT_ID_LENGTH)
                        .startsWith("0");
                assertThat(ACCOUNT_ID_FORM.matcher(aggregateId.stringValue()).matches())
                        .as("aggregateId matches %s", AccountStateChanged.ACCOUNT_ID_PATTERN)
                        .isTrue();
            });
        }

        @Test
        @DisplayName("The account identifier repeats the aggregate identifier as eleven-digit text")
        void theAccountIdentifierRepeatsTheAggregateIdentifier() {
            AccountStateChanged event = accountUpdated();

            inRolledBackTransaction(status -> {
                JsonNode wire = wireForm(writeAndRead(event));

                JsonNode accountId = wire.get("accountId");
                assertThat(accountId.isString())
                        .as("accountId is textual")
                        .isTrue();
                assertThat(accountId.stringValue())
                        .as("the account identifier the payload reports")
                        .isEqualTo(ACCOUNT_ID)
                        .hasSize(ACCOUNT_ID_LENGTH)
                        .isEqualTo(wire.get("aggregateId").stringValue());
                assertThat(ACCOUNT_ID_FORM.matcher(accountId.stringValue()).matches())
                        .as("accountId matches %s", AccountStateChanged.ACCOUNT_ID_PATTERN)
                        .isTrue();
            });
        }

        @Test
        @DisplayName("Every monetary property is a decimal string at two fractional digits")
        void everyMonetaryPropertyIsADecimalString() {
            AccountStateChanged event = accountUpdated();

            inRolledBackTransaction(status -> {
                JsonNode wire = wireForm(writeAndRead(event));

                for (String property : MONETARY_PROPERTIES) {
                    JsonNode amount = wire.get(property);
                    assertThat(amount)
                            .as("monetary property %s", property)
                            .isNotNull();
                    assertThat(amount.isString())
                            .as("%s is textual", property)
                            .isTrue();
                    assertThat(amount.isNumber())
                            .as("%s is numeric", property)
                            .isFalse();
                    String text = amount.stringValue();
                    assertThat(MONETARY_FORM.matcher(text).matches())
                            .as("%s value %s matches %s", property, text,
                                    AccountStateChanged.MONETARY_PATTERN)
                            .isTrue();
                    assertThat(text.substring(text.indexOf('.') + 1))
                            .as("fractional digits %s carries", property)
                            .hasSize(MONETARY_SCALE);
                }
            });
        }

        @Test
        @DisplayName("A monetary value keeps its trailing zeros at the declared scale")
        void aMonetaryValueKeepsItsTrailingZeros() {
            AccountStateChanged event = accountUpdated();

            inRolledBackTransaction(status -> {
                JsonNode wire = wireForm(writeAndRead(event));

                assertThat(wire.get("creditLimit").stringValue())
                        .as("the credit limit the payload reports")
                        .isEqualTo(CREDIT_LIMIT_TEXT);
                assertThat(wire.get("currentCycleCredit").stringValue())
                        .as("the cycle credit accumulator the payload reports")
                        .isEqualTo(CYCLE_CREDIT_TEXT);
                assertThat(wire.get("currentCycleDebit").stringValue())
                        .as("the cycle debit accumulator the payload reports")
                        .isEqualTo(CYCLE_DEBIT_TEXT);
                assertThat(wire.get("currentBalance").stringValue())
                        .as("the balance the payload reports")
                        .isEqualTo(CURRENT_BALANCE.toPlainString());
            });
        }

        @Test
        @DisplayName("The expiry travels as ten characters of text")
        void theExpiryTravelsAsTenCharactersOfText() {
            AccountStateChanged event = accountUpdated();

            inRolledBackTransaction(status -> {
                JsonNode wire = wireForm(writeAndRead(event));

                JsonNode expirationDate = wire.get("expirationDate");
                assertThat(expirationDate.isString())
                        .as("expirationDate is textual")
                        .isTrue();
                assertThat(expirationDate.stringValue())
                        .as("the account expiry the payload reports")
                        .isEqualTo(EXPIRATION_DATE)
                        .hasSize(EXPIRATION_DATE_LENGTH);
            });
        }

        @Test
        @DisplayName("An account update reports the change kind of an account update")
        void anAccountUpdateReportsItsChangeKind() {
            AccountStateChanged event = accountUpdated();

            inRolledBackTransaction(status -> {
                JsonNode wire = wireForm(writeAndRead(event));

                assertThat(wire.get("changeKind").stringValue())
                        .as("the change kind the payload reports")
                        .isEqualTo(AccountStateChanged.ChangeKind.ACCOUNT_UPDATED.name())
                        .isIn(CHANGE_KINDS);
            });
        }

        @Test
        @DisplayName("A cycle close reports the change kind of a cycle close")
        void aCycleCloseReportsItsChangeKind() {
            AccountStateChanged event = billingCycleClosed();

            inRolledBackTransaction(status -> {
                JsonNode wire = wireForm(writeAndRead(event));

                assertThat(wire.get("changeKind").stringValue())
                        .as("the change kind the payload reports")
                        .isEqualTo(AccountStateChanged.ChangeKind.BILLING_CYCLE_CLOSED.name())
                        .isIn(CHANGE_KINDS);
                assertThat(propertyNamesOf(wire))
                        .as("the top-level property names of the cycle-close payload")
                        .containsExactlyInAnyOrderElementsOf(WIRE_PROPERTIES);
            });
        }
    }

    /** Asserts the payload against its schema document, and asserts the document rejects. */
    @Nested
    @DisplayName("The payload against schemas/account-state-changed-v1.json")
    class SchemaConformance {

        @Test
        @DisplayName("An account-update payload conforms to the document")
        void anAccountUpdatePayloadConforms() {
            AccountStateChanged event = accountUpdated();

            inRolledBackTransaction(status -> {
                String payload = writeAndRead(event).getPayload();

                List<Error> violations = violationsOf(payload);

                assertThat(violations)
                        .as("violations of %s: %s", SCHEMA_RESOURCE, describe(violations))
                        .isEmpty();
            });
        }

        @Test
        @DisplayName("A cycle-close payload conforms to the document")
        void aCycleClosePayloadConforms() {
            AccountStateChanged event = billingCycleClosed();

            inRolledBackTransaction(status -> {
                String payload = writeAndRead(event).getPayload();

                List<Error> violations = violationsOf(payload);

                assertThat(violations)
                        .as("violations of %s: %s", SCHEMA_RESOURCE, describe(violations))
                        .isEmpty();
            });
        }

        @Test
        @DisplayName("A monetary value written as a JSON number breaks the document")
        void aMonetaryValueWrittenAsANumberBreaksTheDocument() {
            AccountStateChanged event = accountUpdated();

            inRolledBackTransaction(status -> {
                String payload = writeAndRead(event).getPayload();

                String broken =
                        withNumericProperty(payload, "creditLimit", new BigDecimal("1940.00"));

                assertThat(violationsOf(payload))
                        .as("violations the conforming payload reports")
                        .isEmpty();
                assertThat(violationsOf(broken))
                        .as("violations a numeric creditLimit reports")
                        .isNotEmpty();
            });
        }

        @Test
        @DisplayName("A change kind the document declares nowhere breaks the document")
        void anUndeclaredChangeKindBreaksTheDocument() {
            AccountStateChanged event = accountUpdated();

            inRolledBackTransaction(status -> {
                String payload = writeAndRead(event).getPayload();

                String broken =
                        withTextProperty(payload, "changeKind", UNDECLARED_CHANGE_KIND);

                assertThat(CHANGE_KINDS)
                        .as("the change kinds the document declares")
                        .doesNotContain(UNDECLARED_CHANGE_KIND);
                assertThat(violationsOf(broken))
                        .as("violations change kind %s reports", UNDECLARED_CHANGE_KIND)
                        .isNotEmpty();
            });
        }

        @Test
        @DisplayName("A quoted schema version breaks the document")
        void aQuotedSchemaVersionBreaksTheDocument() {
            AccountStateChanged event = accountUpdated();

            inRolledBackTransaction(status -> {
                String payload = writeAndRead(event).getPayload();

                String broken = withTextProperty(payload, "schemaVersion",
                        String.valueOf(SCHEMA_VERSION));

                assertThat(violationsOf(broken))
                        .as("violations a quoted schemaVersion reports")
                        .isNotEmpty();
            });
        }
    }

    /** Registers {@link RecordingEventPublisher} over the Kafka-backed publisher of the module. */
    @TestConfiguration
    static class RecordingPublisherConfiguration {

        /**
         * Builds the publisher every bean of this context reaches.
         *
         * @return the recording publisher, primary among the publishers of the context
         */
        @Bean
        @Primary
        RecordingEventPublisher recordingEventPublisher() {
            return new RecordingEventPublisher();
        }
    }

    /**
     * Keeps every publication in call order and reaches no broker.
     *
     * <p>The scheduled sweep of {@code OutboxRelay} and the calling test both reach this publisher,
     * so the recording is a thread-safe list.
     */
    static final class RecordingEventPublisher implements EventPublisherPort {

        /** Every publication this publisher took, in call order. */
        private final List<Publication> publications = new CopyOnWriteArrayList<>();

        @Override
        public CompletionStage<Void> publish(String topic, String aggregateId, String payload) {
            publications.add(new Publication(topic, aggregateId, payload));
            return CompletableFuture.completedFuture(null);
        }

        /** Empties the recording. */
        void reset() {
            publications.clear();
        }

        /**
         * Returns the publications keyed on one account, in call order.
         *
         * @param aggregateId the account identifier a publication carries as its key
         * @return the recorded publications that key selects
         */
        List<Publication> publicationsCarrying(String aggregateId) {
            return publications.stream()
                    .filter(publication -> aggregateId.equals(publication.aggregateId()))
                    .toList();
        }
    }

    /**
     * One publication a publisher took.
     *
     * @param topic       the topic the publication reached
     * @param aggregateId the message key, an account identifier
     * @param payload     the document the publication carried
     */
    record Publication(String topic, String aggregateId, String payload) {
    }
}
