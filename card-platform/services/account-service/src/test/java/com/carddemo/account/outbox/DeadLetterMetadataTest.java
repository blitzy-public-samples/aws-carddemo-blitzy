package com.carddemo.account.outbox;

import com.carddemo.account.config.AccountProperties;
import com.carddemo.account.config.KafkaProducerConfig;
import com.carddemo.account.config.ObservabilityConfig;
import com.carddemo.account.entity.OutboxEventEntity;
import com.carddemo.account.messaging.DeadLetterMetadata;
import com.carddemo.account.messaging.EventPublisherPort;
import com.carddemo.account.repository.OutboxEventRepository;
import com.carddemo.events.DeadLetterEnvelope;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.lang.reflect.Modifier;
import java.lang.reflect.RecordComponent;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.config.YamlPropertiesFactoryBean;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.domain.Limit;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.junit.jupiter.params.provider.Arguments.arguments;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Shape and configuration tests for {@link DeadLetterMetadata}, read from a calling package.
 *
 * <p>The record carries the group {@code 01 ABEND-DATA.} at {@code app/cpy/CSMSG02Y.cpy:L21},
 * whose four alphanumeric fields occupy 134 bytes. The widths are typed here from the copybook,
 * then compared against the record's published maximums. The four fields are
 * {@code ABEND-CODE PIC X(4)} at L22, {@code ABEND-CULPRIT PIC X(8)} at L24,
 * {@code ABEND-REASON PIC X(50)} at L26 and {@code ABEND-MSG PIC X(72)} at L28. Two batch
 * programs fill the group, {@code app/cbl/CBTRN02C.cbl} and {@code app/cbl/COACTUPC.cbl}.
 *
 * <p>Every field of the group carries {@code VALUE SPACES}. A {@code null} component therefore
 * arrives as the empty string, one component at a time and all four at once.
 *
 * <p>This class sits in {@code com.carddemo.account.outbox} and the record sits in
 * {@code com.carddemo.account.messaging}. Only public members reach here. One test names every
 * public member an outbox caller uses, so a narrowed modifier fails here.
 *
 * <p>Building the record raises nothing. A component longer than its maximum keeps its leading
 * characters. The tests below hold that behaviour at each of the four widths.
 *
 * <p>The header at {@code app/cpy/CSMSG02Y.cpy:L2} names the file {@code CABENDD.CPY}, and the
 * version stamp at L34 reads {@code 2022-07-19 23:15:58 CDT}.
 *
 * <p>One test drives the real {@link OutboxRelay} recovery path with a raw nested payload and
 * inspects the emitted dead-letter record. Another reads {@code application.yml} from the test
 * classpath. No test here starts an application context, database or broker.
 */
@DisplayName("DeadLetterMetadata seen from the outbox package, from ABEND-DATA at CSMSG02Y.cpy L21")
class DeadLetterMetadataTest {

    /** Width of {@code ABEND-CODE PIC X(4)} at {@code app/cpy/CSMSG02Y.cpy:L22}. */
    private static final int ABEND_CODE_WIDTH = 4;

    /** Width of {@code ABEND-CULPRIT PIC X(8)} at {@code app/cpy/CSMSG02Y.cpy:L24}. */
    private static final int CULPRIT_WIDTH = 8;

    /** Width of {@code ABEND-REASON PIC X(50)} at {@code app/cpy/CSMSG02Y.cpy:L26}. */
    private static final int REASON_WIDTH = 50;

    /** Width of {@code ABEND-MSG PIC X(72)} at {@code app/cpy/CSMSG02Y.cpy:L28}. */
    private static final int MESSAGE_WIDTH = 72;

    /** Bytes the group {@code 01 ABEND-DATA} occupies at {@code app/cpy/CSMSG02Y.cpy:L21}. */
    private static final int ABEND_DATA_WIDTH = 134;

    /** Fields the group declares at {@code app/cpy/CSMSG02Y.cpy:L22}, L24, L26 and L28. */
    private static final int TEXT_COMPONENT_COUNT = 4;

    /** The four text components, in the field order of {@code 01 ABEND-DATA}. */
    private static final List<String> TEXT_COMPONENTS =
            List.of("abendCode", "culprit", "reason", "message");

    /**
     * One distinct value per text component, each inside the width of the copybook field it
     * carries. The positions match {@link #TEXT_COMPONENTS}.
     */
    private static final List<String> FITTING_VALUES =
            List.of("0902", "outbox", "reason", "message");

    /** Package the record belongs to. A shared contract would sit elsewhere. */
    private static final String RECORD_PACKAGE = "com.carddemo.account.messaging";

    /** Package this test belongs to, and the caller that builds the record. */
    private static final String CALLER_PACKAGE = "com.carddemo.account.outbox";

    /** Public width constants the record publishes, one per text component. */
    private static final List<String> PUBLISHED_WIDTH_CONSTANTS = List.of("ABEND_CODE_MAX_LENGTH",
            "CULPRIT_MAX_LENGTH", "REASON_MAX_LENGTH", "MESSAGE_MAX_LENGTH");

    /** Configuration document the module ships, resolved from the test classpath. */
    private static final String CONFIGURATION_RESOURCE = "application.yml";

    /** Prefix every configured topic name sits under. */
    private static final String TOPIC_KEY_PREFIX = "carddemo.kafka.topics.";

    /** The one topic key the module declares. */
    private static final String PUBLISHED_TOPIC_KEY = TOPIC_KEY_PREFIX + "account-state-changed";

    /** Name the published topic key resolves to with no environment override in place. */
    private static final String PUBLISHED_TOPIC_NAME = "account.state-changed";

    /** Property and default of the customer-context event this service also publishes. */
    private static final String CUSTOMER_CONTEXT_TOPIC_KEY =
            TOPIC_KEY_PREFIX + "customer-context-changed";
    private static final String CUSTOMER_CONTEXT_TOPIC_NAME = "customer.context-changed";

    /** Key naming the shared dead-letter topic every service routes to. */
    private static final String DEAD_LETTER_TOPIC_KEY = TOPIC_KEY_PREFIX + "dead-letter";

    /** Name the dead-letter topic key resolves to with no environment override in place. */
    private static final String DEAD_LETTER_TOPIC_NAME = "carddemo.dead-letter";

    /** Prefixes a consumer or a listener setting takes. */
    private static final List<String> LISTENER_KEY_PREFIXES =
            List.of("spring.kafka.consumer.", "spring.kafka.listener.");

    /**
     * The consumed-topic key, which arrived with {@code messaging/TransactionPostedConsumer}.
     *
     * <p>It is the one key under {@link #TOPIC_KEY_PREFIX} this module reads rather than writes.
     */
    private static final String CONSUMED_TOPIC_KEY = TOPIC_KEY_PREFIX + "transaction-posted";

    /** The topic the consumed-topic key resolves to with no override in place. */
    private static final String CONSUMED_TOPIC_NAME = "transaction.posted";

    /**
     * Every {@code spring.kafka} consumer and listener key this module declares, in name order.
     *
     * <p>The list was two entries long while this module read no topic. It is eight now, and the two
     * that were always here still carry the acknowledgement contract: auto-commit off, and an
     * acknowledgement the listener issues itself once its transaction has committed. Without both,
     * an offset commits ahead of the write it stands for and a crash loses the posting.
     *
     * <p>The eighth is the listener concurrency, which is a throughput setting rather than an
     * acknowledgement one and belongs here because this list is the whole declared surface. It ships
     * as the partition count every topic of this platform carries, so the postings of one account
     * still arrive in publish order: a partition is assigned to exactly one consumer thread, and the
     * account identifier is the message key. Omitting it would leave the framework default of one
     * thread in place, applying every partition of the topic in sequence.
     *
     * <p>The two fetch ceilings are the read side of the wire ladder. They bound one partition fetch at 65,536 bytes and one whole fetch at 262,144, against the
     * 8,192-byte envelope {@code EventWireBounds} governs, so a record this platform publishes always
     * fits and the memory one poll can hold is bounded. {@code KafkaDeliveryGuaranteeContractTest}
     * holds the whole ladder in order across all six services.
     */
    private static final List<String> LISTENER_KEYS = List.of(
            "spring.kafka.consumer.auto-offset-reset",
            "spring.kafka.consumer.enable-auto-commit",
            "spring.kafka.consumer.group-id",
            "spring.kafka.consumer.key-deserializer",
            "spring.kafka.consumer.properties.fetch.max.bytes",
            "spring.kafka.consumer.properties.max.partition.fetch.bytes",
            "spring.kafka.consumer.properties.spring.deserializer.value.delegate.class",
            "spring.kafka.consumer.value-deserializer",
            "spring.kafka.listener.ack-mode",
            "spring.kafka.listener.concurrency");

    /** Opening delimiter of a configured placeholder. */
    private static final String PLACEHOLDER_OPEN = "${";

    /** Closing delimiter of a configured placeholder. */
    private static final String PLACEHOLDER_CLOSE = "}";

    /** Character separating a placeholder's variable name from its default. */
    private static final char PLACEHOLDER_SEPARATOR = ':';

    /** Time of the terminal publish attempt in the recoverer test. */
    private static final Instant TERMINAL_ATTEMPT =
            Instant.parse("2099-02-01T00:00:00Z");

    /**
     * Asserts that the record declares the four fields of {@code 01 ABEND-DATA} as {@code String}
     * components, and declares no fifth. The order follows {@code app/cpy/CSMSG02Y.cpy:L22}, L24,
     * L26 and L28.
     */
    @Test
    @DisplayName("Four components: the four copybook fields, in copybook order, and no more")
    void declaresTheFourCopybookFieldsAndNoMore() {
        assertThat(DeadLetterMetadata.class.isRecord())
                .as("DeadLetterMetadata is a record")
                .isTrue();

        RecordComponent[] components = DeadLetterMetadata.class.getRecordComponents();
        assertThat(components)
                .as("the four fields of app/cpy/CSMSG02Y.cpy:L21 and no other component")
                .hasSize(TEXT_COMPONENT_COUNT);

        List<String> names = new ArrayList<>();
        for (RecordComponent component : components) {
            names.add(component.getName());
        }
        assertThat(names)
                .as("component order follows the field order of app/cpy/CSMSG02Y.cpy:L22 onward")
                .containsExactlyElementsOf(TEXT_COMPONENTS);

        for (RecordComponent component : components) {
            assertThat(component.getType())
                    .as("field %s of app/cpy/CSMSG02Y.cpy:L21 is alphanumeric",
                            component.getName())
                    .isEqualTo(String.class);
        }
    }

    /**
     * Asserts that each published maximum equals the width of the copybook field it carries, and
     * that the four maximums sum to the 134 bytes of {@code 01 ABEND-DATA} at
     * {@code app/cpy/CSMSG02Y.cpy:L21}. The total is summed from the published maximums.
     */
    @Test
    @DisplayName("Each published maximum equals its copybook field width, and the four sum to 134")
    void eachPublishedMaximumEqualsItsCopybookFieldWidth() {
        assertThat(DeadLetterMetadata.ABEND_CODE_MAX_LENGTH)
                .as("ABEND-CODE PIC X(4) at app/cpy/CSMSG02Y.cpy:L22 holds 4 characters")
                .isEqualTo(ABEND_CODE_WIDTH);
        assertThat(DeadLetterMetadata.CULPRIT_MAX_LENGTH)
                .as("ABEND-CULPRIT PIC X(8) at app/cpy/CSMSG02Y.cpy:L24 holds 8 characters")
                .isEqualTo(CULPRIT_WIDTH);
        assertThat(DeadLetterMetadata.REASON_MAX_LENGTH)
                .as("ABEND-REASON PIC X(50) at app/cpy/CSMSG02Y.cpy:L26 holds 50 characters")
                .isEqualTo(REASON_WIDTH);
        assertThat(DeadLetterMetadata.MESSAGE_MAX_LENGTH)
                .as("ABEND-MSG PIC X(72) at app/cpy/CSMSG02Y.cpy:L28 holds 72 characters")
                .isEqualTo(MESSAGE_WIDTH);

        int publishedTotal = DeadLetterMetadata.ABEND_CODE_MAX_LENGTH
                + DeadLetterMetadata.CULPRIT_MAX_LENGTH
                + DeadLetterMetadata.REASON_MAX_LENGTH
                + DeadLetterMetadata.MESSAGE_MAX_LENGTH;
        assertThat(publishedTotal)
                .as("the four published maximums sum to the group width at "
                        + "app/cpy/CSMSG02Y.cpy:L21")
                .isEqualTo(ABEND_DATA_WIDTH);
        assertThat(ABEND_CODE_WIDTH + CULPRIT_WIDTH + REASON_WIDTH + MESSAGE_WIDTH)
                .as("the four copybook widths sum to the same group width")
                .isEqualTo(ABEND_DATA_WIDTH);
    }

    /** Supplies each text component with the width of the copybook field it carries. */
    static Stream<Arguments> textComponentWidths() {
        return Stream.of(arguments("abendCode", ABEND_CODE_WIDTH),
                arguments("culprit", CULPRIT_WIDTH),
                arguments("reason", REASON_WIDTH),
                arguments("message", MESSAGE_WIDTH));
    }

    @ParameterizedTest(name = "{0} holds {1} characters and shortens one character more")
    @MethodSource("textComponentWidths")
    @DisplayName("A component holds its copybook width and shortens one character more")
    void aComponentHoldsItsWidthAndShortensOneCharacterMore(String component, int width) {
        String atWidth = printableRun(width);
        DeadLetterMetadata whole = buildWith(component, atWidth);
        assertThat(valueOf(whole, component))
                .as("%s holds a value of width %d whole", component, width)
                .isEqualTo(atWidth);

        String pastWidth = printableRun(width + 1);
        DeadLetterMetadata shortened = buildWith(component, pastWidth);
        assertThat(valueOf(shortened, component))
                .as("%s keeps the leading %d characters of a value of width %d", component, width,
                        width + 1)
                .isEqualTo(atWidth)
                .hasSize(width);
        for (String other : TEXT_COMPONENTS) {
            if (!other.equals(component)) {
                assertThat(valueOf(shortened, other))
                        .as("shortening %s leaves %s empty", component, other)
                        .isEmpty();
            }
        }
    }

    /**
     * Asserts that a {@code null} in every position arrives as the empty string, matching the
     * {@code VALUE SPACES} clause every field of {@code 01 ABEND-DATA} carries at
     * {@code app/cpy/CSMSG02Y.cpy:L21}.
     */
    @Test
    @DisplayName("Four null components arrive as four empty strings")
    void fourNullComponentsArriveAsFourEmptyStrings() {
        DeadLetterMetadata metadata = DeadLetterMetadata.of(null, null, null, null);

        for (String component : TEXT_COMPONENTS) {
            assertThat(valueOf(metadata, component))
                    .as("a null %s arrives as the empty string, matching VALUE SPACES at "
                            + "app/cpy/CSMSG02Y.cpy:L21", component)
                    .isEmpty();
        }
    }

    @Test
    @DisplayName("One null component arrives empty and leaves the other three untouched")
    void oneNullComponentLeavesTheOtherThreeUntouched() {
        for (int nulled = 0; nulled < TEXT_COMPONENT_COUNT; nulled++) {
            String[] supplied = FITTING_VALUES.toArray(new String[0]);
            supplied[nulled] = null;
            DeadLetterMetadata metadata =
                    DeadLetterMetadata.of(supplied[0], supplied[1], supplied[2], supplied[3]);
            String nulledName = TEXT_COMPONENTS.get(nulled);

            assertThat(valueOf(metadata, nulledName))
                    .as("a null %s arrives as the empty string", nulledName)
                    .isEmpty();
            for (int other = 0; other < TEXT_COMPONENT_COUNT; other++) {
                if (other != nulled) {
                    assertThat(valueOf(metadata, TEXT_COMPONENTS.get(other)))
                            .as("a null %s leaves %s untouched", nulledName,
                                    TEXT_COMPONENTS.get(other))
                            .isEqualTo(FITTING_VALUES.get(other));
                }
            }
        }
    }

    @Test
    @DisplayName("A short value arrives unchanged, with no padding and no trimming")
    void aShortValueArrivesUnchangedWithNoPaddingAndNoTrimming() {
        DeadLetterMetadata metadata =
                DeadLetterMetadata.of("0902", "outbox", " publish refused ", " account row ");

        assertThat(metadata.abendCode()).isEqualTo("0902").hasSize(4);
        assertThat(metadata.culprit()).isEqualTo("outbox").hasSize(6);
        assertThat(metadata.reason())
                .as("reason keeps its leading and trailing spaces")
                .isEqualTo(" publish refused ");
        assertThat(metadata.message())
                .as("message keeps its leading and trailing spaces")
                .isEqualTo(" account row ");

        DeadLetterMetadata single = DeadLetterMetadata.of("9", "r", "o", "b");
        for (String component : TEXT_COMPONENTS) {
            assertThat(valueOf(single, component))
                    .as("a one-character %s is not padded to its copybook width", component)
                    .hasSize(1);
        }
    }

    @Test
    @DisplayName("Building the record raises nothing, whatever the four components carry")
    void buildingTheRecordRaisesNothing() {
        String hostile = printableRun(MESSAGE_WIDTH * 4) + "\r\n\t\u0000\u00e9";

        assertThatCode(() -> DeadLetterMetadata.of(hostile, hostile, hostile, hostile))
                .as("four over-length components carrying control characters raise nothing")
                .doesNotThrowAnyException();
        assertThatCode(() -> DeadLetterMetadata.fromFailure(null, null, null, null))
                .as("four missing arguments raise nothing")
                .doesNotThrowAnyException();

        DeadLetterMetadata metadata =
                DeadLetterMetadata.of(hostile, hostile, hostile, hostile);
        assertThat(metadata.abendCode()).hasSize(ABEND_CODE_WIDTH);
        assertThat(metadata.culprit()).hasSize(CULPRIT_WIDTH);
        assertThat(metadata.reason()).hasSize(REASON_WIDTH);
        assertThat(metadata.message()).hasSize(MESSAGE_WIDTH);
    }

    @Test
    @DisplayName("A character outside printable ASCII arrives as the substitute character")
    void aCharacterOutsidePrintableAsciiArrivesAsTheSubstituteCharacter() {
        char substitute = DeadLetterMetadata.SUBSTITUTE_CHARACTER;
        String supplied = "a\nb\tc\u0000d\u00e9e";
        String expected = "a" + substitute + "b" + substitute + "c" + substitute + "d" + substitute
                + "e";

        String reason = DeadLetterMetadata.of("", "", supplied, "").reason();

        assertThat(reason)
                .as("each character outside printable ASCII arrives as one substitute")
                .isEqualTo(expected);
        assertThat(reason)
                .as("no line break, tab or null character reaches the dead-letter topic")
                .doesNotContain("\n", "\r", "\t", "\u0000");
        assertThat(DeadLetterMetadata.of(" ~", "", "", "").abendCode())
                .as("the space and the tilde bound printable ASCII and both survive")
                .isEqualTo(" ~");
    }

    /**
     * Asserts that every component over its width is shortened to that width, and that a component
     * within its width arrives whole. The widths follow {@code app/cpy/CSMSG02Y.cpy:L22}, L24, L26
     * and L28.
     */
    @Test
    @DisplayName("Every component over its width is shortened, and a whole one is left alone")
    void everyComponentOverItsWidthIsShortened() {
        String pastEveryWidth = printableRun(MESSAGE_WIDTH + 1);

        DeadLetterMetadata allShortened = DeadLetterMetadata.of(pastEveryWidth, pastEveryWidth,
                pastEveryWidth, pastEveryWidth);

        assertThat(allShortened.abendCode()).hasSize(ABEND_CODE_WIDTH);
        assertThat(allShortened.culprit()).hasSize(CULPRIT_WIDTH);
        assertThat(allShortened.reason()).hasSize(REASON_WIDTH);
        assertThat(allShortened.message()).hasSize(MESSAGE_WIDTH);

        DeadLetterMetadata twoShortened = DeadLetterMetadata.of(printableRun(ABEND_CODE_WIDTH),
                printableRun(CULPRIT_WIDTH + 1), printableRun(REASON_WIDTH),
                printableRun(MESSAGE_WIDTH + 1));

        assertThat(twoShortened.abendCode())
                .as("a whole abendCode arrives at its own length")
                .hasSize(ABEND_CODE_WIDTH);
        assertThat(twoShortened.culprit()).hasSize(CULPRIT_WIDTH);
        assertThat(twoShortened.reason())
                .as("a whole reason arrives at its own length")
                .hasSize(REASON_WIDTH);
        assertThat(twoShortened.message()).hasSize(MESSAGE_WIDTH);
    }

    /**
     * Asserts that the canonical constructor sanitises the same way the factory does, so a caller
     * that bypasses the factory gains nothing.
     */
    @Test
    @DisplayName("The canonical constructor sanitises the same way the factory does")
    void theCanonicalConstructorSanitisesTheSameWayTheFactoryDoes() {
        assertThat(new DeadLetterMetadata("0902", "outbox", "reason", "message"))
                .as("the canonical constructor and the factory agree on a whole value")
                .isEqualTo(DeadLetterMetadata.of("0902", "outbox", "reason", "message"));

        assertThat(new DeadLetterMetadata(printableRun(ABEND_CODE_WIDTH + 1), "outbox", "reason",
                "message").abendCode())
                .as("the canonical constructor shortens an over-long abendCode")
                .hasSize(ABEND_CODE_WIDTH);
    }

    /**
     * Asserts that the failure type names the culprit and that the failure text never arrives. A
     * type name past {@code ABEND-CULPRIT PIC X(8)} at {@code app/cpy/CSMSG02Y.cpy:L24} keeps its
     * leading eight characters.
     */
    @Test
    @DisplayName("The failure type names the culprit and the failure text never arrives")
    void theFailureTypeNamesTheCulpritAndTheFailureTextNeverArrives() {
        IllegalStateException failure = new IllegalStateException("sensitive detail");
        String typeName = failure.getClass().getSimpleName();

        DeadLetterMetadata metadata =
                DeadLetterMetadata.fromFailure("0902", failure, "publish refused", "outbox row");

        assertThat(typeName)
                .as("the type name runs past ABEND-CULPRIT PIC X(8)")
                .hasSizeGreaterThan(CULPRIT_WIDTH);
        assertThat(metadata.culprit())
                .as("culprit keeps the leading %d characters of the type name", CULPRIT_WIDTH)
                .isEqualTo(typeName.substring(0, CULPRIT_WIDTH));
        assertThat(List.of(metadata.abendCode(), metadata.culprit(), metadata.reason(),
                metadata.message()))
                .as("the failure text reaches no component")
                .noneMatch(value -> value.contains("sensitive"));
        assertThat(DeadLetterMetadata.fromFailure("0902", null, "publish refused", "outbox row")
                .culprit())
                .as("a missing failure leaves culprit empty")
                .isEmpty();
    }

    @Test
    @DisplayName("the real outbox recoverer emits a raw dead-letter record with no sensitive value")
    void theRealRecovererEmitsNoSensitiveValue() {
        Map<String, String> sensitive = Map.of(
                "cardNumber", "9999" + "452612877065",
                "cardVerificationValue", "731",
                "socialSecurityNumber", "999" + "000001",
                "credential", "generated-secret-for-recoverer-test");
        String failedPayload = """
                {"customer":{"cardNumber":"%s","cardVerificationValue":"%s"},
                 "identity":{"socialSecurityNumber":"%s"},
                 "credential":"%s"}
                """.formatted(
                sensitive.get("cardNumber"), sensitive.get("cardVerificationValue"),
                sensitive.get("socialSecurityNumber"), sensitive.get("credential"));
        OutboxEventEntity row = rowAtNinthAttempt(failedPayload);
        OutboxEventRepository repository = mock(OutboxEventRepository.class);
        when(repository.findByRelayStateAndClaimedAtBeforeOrderByClaimedAtAsc(
                eq(OutboxEventEntity.RelayState.CLAIMED), any(), any()))
                .thenReturn(List.of());
        when(repository.deletePublishedBefore(any(), anyInt())).thenReturn(0);
        when(repository.claimDueRows(any(), eq(Limit.of(1)))).thenReturn(List.of(row));
        // The relay records each outcome in a transaction of its own and re-reads the row inside it,
        // because the claim has committed by then and saving the copy the claim loaded would write
        // pre-claim state back over it. This answers that read with the same row.
        when(repository.findById(row.getEventId())).thenReturn(Optional.of(row));

        AccountProperties properties = propertiesWithOneRowPerClaim();
        ObjectMapper mapper = new KafkaProducerConfig(properties).accountEventObjectMapper();
        CapturingRecovererPublisher publisher =
                new CapturingRecovererPublisher(failedPayload);
        OutboxRelay relay = new OutboxRelay(
                repository, publisher, immediateTransactions(), properties, mapper,
                new ObservabilityConfig().accountMeters(new SimpleMeterRegistry()),
                Clock.fixed(TERMINAL_ATTEMPT, ZoneOffset.UTC), "recoverer-test");

        relay.publishPendingEvents();

        assertThat(row.getRelayState()).isEqualTo(OutboxEventEntity.RelayState.ABANDONED);
        assertThat(row.getAttemptCount()).isEqualTo(OutboxEventEntity.MAX_DELIVERY_ATTEMPTS);
        // Three saves, and the last two are the point: the claim commits before any send starts, the
        // abandonment is then stored with the obligation to name this row, and the acknowledgement of
        // the diagnostic is stored separately once the broker has taken it. Folding the last two into
        // one would mean the relay either forgot the obligation or cleared it before the broker
        // answered.
        verify(repository, times(3)).save(row);
        assertThat(row.getDeadLetterState())
                .as("the acknowledged diagnostic discharges the obligation")
                .isEqualTo(OutboxEventEntity.DeadLetterState.PUBLISHED);
        assertThat(row.getDeadLetterPublishedAt()).isEqualTo(TERMINAL_ATTEMPT);
        assertThat(row.owesDeadLetter()).isFalse();
        assertThat(publisher.deadLetterTopic).isEqualTo(DEAD_LETTER_TOPIC_NAME);
        assertThat(publisher.deadLetterPayload).isNotNull();

        JsonNode rawRecord = mapper.readTree(publisher.deadLetterPayload);
        assertThat(rawRecord.path("eventType").asString()).isEqualTo(DeadLetterEnvelope.EVENT_TYPE);
        assertThat(rawRecord.path("failedEventId").asString())
                .isEqualTo(row.getEventId().toString());
        assertThat(rawRecord.path("failedEventType").asString()).isEqualTo(row.getEventType());
        assertThat(rawRecord.path("attemptCount").asInt())
                .isEqualTo(OutboxEventEntity.MAX_DELIVERY_ATTEMPTS);
        List<String> textValues = textValuesOf(rawRecord);
        sensitive.forEach((label, value) -> {
            assertThat(publisher.deadLetterPayload)
                    .as("raw dead-letter record carries no %s property", label)
                    .doesNotContain(label);
            if (value.length() <= ABEND_CODE_WIDTH) {
                assertThat(textValues)
                        .as("no dead-letter field equals the short %s value", label)
                        .doesNotContain(value);
            } else {
                assertThat(publisher.deadLetterPayload)
                        .as("raw dead-letter record contains no %s", label)
                        .doesNotContain(value);
            }
        });
    }

    @Test
    @DisplayName("Every member an outbox caller uses is public and reaches a different package")
    void everyMemberAnOutboxCallerUsesIsPublic() {
        assertThat(DeadLetterMetadata.class.getPackageName())
                .as("the record belongs to the account service messaging package")
                .isEqualTo(RECORD_PACKAGE);
        assertThat(DeadLetterMetadataTest.class.getPackageName())
                .as("this test calls from a different package")
                .isEqualTo(CALLER_PACKAGE)
                .isNotEqualTo(RECORD_PACKAGE);
        assertThat(Modifier.isPublic(DeadLetterMetadata.class.getModifiers()))
                .as("the record is public")
                .isTrue();

        for (RecordComponent component : DeadLetterMetadata.class.getRecordComponents()) {
            assertThat(Modifier.isPublic(component.getAccessor().getModifiers()))
                    .as("accessor %s is public", component.getName())
                    .isTrue();
        }

        for (String constant : PUBLISHED_WIDTH_CONSTANTS) {
            assertThatCode(() -> {
                int modifiers = DeadLetterMetadata.class.getField(constant).getModifiers();
                assertThat(Modifier.isStatic(modifiers) && Modifier.isFinal(modifiers))
                        .as("%s is static and final", constant)
                        .isTrue();
            })
                    .as("%s is a public constant", constant)
                    .doesNotThrowAnyException();
        }
        assertThatCode(() -> DeadLetterMetadata.class.getField("SUBSTITUTE_CHARACTER"))
                .as("SUBSTITUTE_CHARACTER is a public constant")
                .doesNotThrowAnyException();
        assertThatCode(() -> DeadLetterMetadata.class.getMethod("of", String.class, String.class,
                String.class, String.class))
                .as("the four-argument factory is public")
                .doesNotThrowAnyException();
        assertThatCode(() -> DeadLetterMetadata.class.getMethod("fromFailure", String.class,
                Throwable.class, String.class, String.class))
                .as("the failure factory is public")
                .doesNotThrowAnyException();
    }

    /**
     * Asserts the module's configured topic surface, read from {@code application.yml} on the test
     * classpath. The module declares two state-change destinations, the topic it reads, and the
     * shared dead-letter topic.
     *
     * <p>The published topic name arrives as a plain literal and the dead-letter name as a
     * placeholder holding a default. The consumed topic and the group the listener joins arrived with
     * {@code messaging/TransactionPostedConsumer}: this module read no topic before that, and the
     * amount that belonged in its own account record travelled past it. No application context,
     * database or broker starts here.
     */
    @Test
    @DisplayName("The configured topic surface holds the published topics, the consumed topic and "
            + "the dead-letter topic")
    void theConfiguredTopicSurfaceHoldsThePublishedConsumedAndDeadLetterTopics() {
        Properties configuration = loadConfiguration();

        assertThat(configuration.stringPropertyNames())
                .as("%s declares four topics under %s", CONFIGURATION_RESOURCE, TOPIC_KEY_PREFIX)
                .filteredOn(name -> name.startsWith(TOPIC_KEY_PREFIX))
                .containsExactlyInAnyOrder(PUBLISHED_TOPIC_KEY, CUSTOMER_CONTEXT_TOPIC_KEY,
                        CONSUMED_TOPIC_KEY, DEAD_LETTER_TOPIC_KEY);

        assertThat(configuredDefault(configuration.getProperty(CONSUMED_TOPIC_KEY)))
                .as("%s resolves to the topic the ledger publishes a posting on", CONSUMED_TOPIC_KEY)
                .isEqualTo(CONSUMED_TOPIC_NAME);

        String configured = configuration.getProperty(PUBLISHED_TOPIC_KEY);
        assertThat(configured)
                .as("%s carries a value", PUBLISHED_TOPIC_KEY)
                .isNotNull();
        assertThat(configuredDefault(configured))
                .as("%s resolves to the published topic name with no override in place",
                        PUBLISHED_TOPIC_KEY)
                .isEqualTo(PUBLISHED_TOPIC_NAME);

        assertThat(configuredDefault(configuration.getProperty(CUSTOMER_CONTEXT_TOPIC_KEY)))
                .as("%s resolves to the customer-context topic name",
                        CUSTOMER_CONTEXT_TOPIC_KEY)
                .isEqualTo(CUSTOMER_CONTEXT_TOPIC_NAME);

        String configuredDeadLetter = configuration.getProperty(DEAD_LETTER_TOPIC_KEY);
        assertThat(configuredDeadLetter)
                .as("%s carries a value", DEAD_LETTER_TOPIC_KEY)
                .isNotNull();
        assertThat(configuredDefault(configuredDeadLetter))
                .as("%s resolves to the shared dead-letter topic name", DEAD_LETTER_TOPIC_KEY)
                .isEqualTo(DEAD_LETTER_TOPIC_NAME);

        List<String> acknowledgementKeys = configuration.keySet().stream()
                .map(String::valueOf)
                .filter(name -> LISTENER_KEY_PREFIXES.stream().anyMatch(name::startsWith))
                .sorted()
                .toList();
        assertThat(acknowledgementKeys)
                .as("the module declares one listener, its acknowledgement stays manual so no "
                        + "offset commits ahead of the write it stands for, and it reads one thread "
                        + "per partition rather than one thread for every partition")
                .isEqualTo(LISTENER_KEYS);
        assertThat(configuration.getProperty("spring.kafka.consumer.enable-auto-commit"))
                .as("auto-commit, which must stay off for the marker to guard anything")
                .isEqualTo("false");
    }

    /**
     * Loads {@code application.yml} from the test classpath and flattens it into dotted keys.
     *
     * @return the flattened configuration, never empty
     */
    private static Properties loadConfiguration() {
        ClassPathResource resource = new ClassPathResource(CONFIGURATION_RESOURCE);
        assertThat(resource.exists())
                .as("%s sits on the test classpath", CONFIGURATION_RESOURCE)
                .isTrue();

        YamlPropertiesFactoryBean reader = new YamlPropertiesFactoryBean();
        reader.setResources(resource);
        reader.afterPropertiesSet();
        Properties configuration = reader.getObject();

        assertThat(configuration)
                .as("%s parses into dotted keys", CONFIGURATION_RESOURCE)
                .isNotNull()
                .isNotEmpty();
        return configuration;
    }

    /** Returns valid account properties with one row per claim. */
    private static AccountProperties propertiesWithOneRowPerClaim() {
        return new AccountProperties(
                new AccountProperties.Api(65536L),
                new AccountProperties.Kafka(
                        new AccountProperties.Kafka.Topics(PUBLISHED_TOPIC_NAME,
                                "customer.context-changed", "transaction.posted",
                                DEAD_LETTER_TOPIC_NAME),
                        new AccountProperties.Kafka.Groups("account-posted")),
                new AccountProperties.Consumer(new AccountProperties.Consumer.Retry(3, 1_000L)),
                new AccountProperties.Outbox(
                        new AccountProperties.Outbox.Relay(
                                500L, 1, "account-relay", java.time.Duration.ofSeconds(30L),
                                30_000L, java.time.Duration.ofSeconds(10L)),
                        168L),
                new AccountProperties.Retention(3_600_000L),
                new AccountProperties.Write(3_000L));
    }

    /** Builds one row whose next failed attempt reaches the terminal state. */
    private static OutboxEventEntity rowAtNinthAttempt(String payload) {
        OutboxEventEntity row = new OutboxEventEntity(
                UUID.fromString("7c9e0a2b-8d1f-4a3c-b5e7-9a1c3e5f7b9d"),
                "AccountStateChanged", payload, "00000000042",
                TERMINAL_ATTEMPT.minusSeconds(20));
        for (int attempt = 1; attempt < OutboxEventEntity.MAX_DELIVERY_ATTEMPTS; attempt++) {
            Instant attemptedAt = TERMINAL_ATTEMPT.minusSeconds(20L - attempt);
            row.claim("seed-relay", attemptedAt);
            row.recordFailure("seeded failure", attemptedAt, attemptedAt);
        }
        return row;
    }

    /** Refuses the source topic and captures the raw dead-letter record. */
    private static final class CapturingRecovererPublisher implements EventPublisherPort {

        private final String failureText;
        private String deadLetterTopic;
        private String deadLetterPayload;

        private CapturingRecovererPublisher(String failureText) {
            this.failureText = failureText;
        }

        @Override
        public java.util.concurrent.CompletionStage<Void> publish(String topic, String aggregateId,
                String payload) {
            if (PUBLISHED_TOPIC_NAME.equals(topic)) {
                throw new IllegalStateException("publish refused for " + failureText);
            }
            if (!DEAD_LETTER_TOPIC_NAME.equals(topic)) {
                throw new AssertionError("unexpected topic " + topic);
            }
            deadLetterTopic = topic;
            deadLetterPayload = payload;
            return java.util.concurrent.CompletableFuture.completedFuture(null);
        }
    }

    /**
     * Reads the default out of a configured value.
     *
     * @param configuredValue the value as {@code application.yml} holds it
     * @return the placeholder's default, or the value itself when it holds no placeholder
     */
    private static String configuredDefault(String configuredValue) {
        if (!configuredValue.startsWith(PLACEHOLDER_OPEN)
                || !configuredValue.endsWith(PLACEHOLDER_CLOSE)) {
            return configuredValue;
        }
        String body = configuredValue.substring(PLACEHOLDER_OPEN.length(),
                configuredValue.length() - PLACEHOLDER_CLOSE.length());
        int separator = body.indexOf(PLACEHOLDER_SEPARATOR);
        return separator < 0 ? body : body.substring(separator + 1);
    }

    /**
     * Builds a run of printable characters whose every position differs from its neighbour, so a
     * shortened value shows which characters survived.
     *
     * @param length characters to build
     * @return a run of lower-case letters of the requested length
     */
    private static String printableRun(int length) {
        StringBuilder run = new StringBuilder(length);
        for (int index = 0; index < length; index++) {
            run.append((char) ('a' + index % 26));
        }
        return run.toString();
    }

    /**
     * Collects every textual leaf of one JSON tree.
     *
     * <p>A three-digit card verification value is too short for a raw substring assertion: the
     * same three digits can occur by chance inside an event identifier or timestamp. Equality at
     * the JSON-field boundary detects an emitted value without turning unrelated metadata into a
     * flaky failure.
     *
     * @param node tree or subtree to inspect
     * @return textual leaf values in traversal order
     */
    private static List<String> textValuesOf(JsonNode node) {
        List<String> values = new ArrayList<>();
        collectTextValues(node, values);
        return List.copyOf(values);
    }

    /** Adds every textual leaf below {@code node} to {@code values}. */
    private static void collectTextValues(JsonNode node, List<String> values) {
        if (node.isString()) {
            values.add(node.asString());
            return;
        }
        node.forEach(child -> collectTextValues(child, values));
    }

    /**
     * Builds a record carrying one value in one text component and the empty string elsewhere.
     *
     * @param component one of {@link #TEXT_COMPONENTS}
     * @param value     the value that component carries
     * @return the built record
     */
    private static DeadLetterMetadata buildWith(String component, String value) {
        return switch (component) {
            case "abendCode" -> DeadLetterMetadata.of(value, "", "", "");
            case "culprit" -> DeadLetterMetadata.of("", value, "", "");
            case "reason" -> DeadLetterMetadata.of("", "", value, "");
            case "message" -> DeadLetterMetadata.of("", "", "", value);
            default -> throw new IllegalArgumentException(
                    "01 ABEND-DATA declares no field named " + component);
        };
    }

    /**
     * Reads one text component out of a record by name.
     *
     * @param metadata  the record to read
     * @param component one of {@link #TEXT_COMPONENTS}
     * @return the value that component carries
     */
    private static String valueOf(DeadLetterMetadata metadata, String component) {
        return switch (component) {
            case "abendCode" -> metadata.abendCode();
            case "culprit" -> metadata.culprit();
            case "reason" -> metadata.reason();
            case "message" -> metadata.message();
            default -> throw new IllegalArgumentException(
                    "01 ABEND-DATA declares no field named " + component);
        };
    }

    /** Runs a transaction callback directly, so no transaction manager takes part. */
    private static TransactionTemplate immediateTransactions() {
        return new TransactionTemplate() {

            private static final long serialVersionUID = 1L;

            @Override
            public <T> T execute(TransactionCallback<T> action) {
                return action.doInTransaction(new SimpleTransactionStatus());
            }
        };
    }


}
