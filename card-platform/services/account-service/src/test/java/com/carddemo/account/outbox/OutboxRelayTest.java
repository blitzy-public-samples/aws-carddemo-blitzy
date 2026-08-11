package com.carddemo.account.outbox;

import com.carddemo.account.AccountApplication;
import com.carddemo.account.config.AccountProperties;
import com.carddemo.account.config.KafkaProducerConfig;
import com.carddemo.account.config.ObservabilityConfig;
import com.carddemo.account.entity.OutboxEventEntity;
import com.carddemo.account.messaging.EventPublisherPort;
import com.carddemo.account.repository.AbstractAccountPostgresTest;
import com.carddemo.account.repository.OutboxEventRepository;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.lang.reflect.AnnotatedElement;
import java.lang.reflect.Method;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.data.domain.Limit;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;
import static org.awaitility.Awaitility.await;
import tools.jackson.databind.ObjectMapper;

/**
 * Behaviour of {@link OutboxRelay}. Six questions.
 *
 * <ul>
 *   <li>when the sweep runs</li>
 *   <li>how many rows it takes</li>
 *   <li>in what order</li>
 *   <li>what reaches the publisher</li>
 *   <li>how a sent row is marked</li>
 *   <li>what a refused publish leaves behind</li>
 * </ul>
 *
 * <p>No COBOL ancestor. The relay has one ancestor construct in the CardDemo source, and it is the
 * one asynchronous handoff there. The paragraph {@code WIRTE-JOBSUB-TDQ} runs from {@code
 * app/cbl/CORPT00C.cbl:L507}, opens at {@code app/cbl/CORPT00C.cbl:L515} under that spelling, and
 * writes at {@code app/cbl/CORPT00C.cbl:L517-L523}: {@code EXEC CICS WRITEQ TD} with {@code QUEUE
 * ('JOBS')} and {@code FROM (JCL-RECORD)}. One record reaches a Customer Information Control System
 * (CICS) transient data queue, and a later reader takes it.
 *
 * <p>The message key is the account identifier, eleven digits wide. The width comes from
 * {@code ACCT-ID PIC 9(11)} at {@code app/cpy/CVACT01Y.cpy:L5} and from {@code KEYS(11 0)} at
 * {@code app/jcl/ACCTFILE.jcl:L40}.
 *
 * <p><b>What the sweep does.</b> {@code OutboxRelay.publishPendingEvents()} carries
 * {@code @Scheduled} and {@code @Transactional}, then reads through
 * {@code claimDueRows}. The query returns the due head row of each account partition under
 * {@code FOR UPDATE SKIP LOCKED}. A refused row pauses its account partition for that sweep,
 * while rows of other accounts remain eligible.
 *
 * <p><b>The publisher every test here sees.</b> {@link RecordingEventPublisher} is the
 * {@code @Primary} bean {@link RecordingPublisherConfiguration} installs over the Kafka-backed
 * one. It keeps each topic, key and payload triple in call order, and it refuses a publish on
 * request. No test here starts a broker and none needs a created topic.
 *
 * <p>The class annotation names that configuration outright. A group annotated {@code Nested}
 * resolves its configuration from its own declared classes, so a group that declares none would
 * otherwise leave Spring to detect the one nested here: Spring Framework 7.0 detects it, ignores
 * it, and warns that 7.1 will stop ignoring it. Naming it on the class carries the one registration
 * to every group under both lines, and the bean definitions of the module arrive alongside it from
 * the class annotated {@code SpringBootConfiguration}.
 *
 * <p><b>How rows reach the table.</b> {@link AbstractAccountPostgresTest} owns the one PostgreSQL
 * container of the module, and no container appears here. Most writing tests carry
 * {@link Transactional}; the scheduler and concurrency cases commit their rows and
 * {@link #removeSeededRows()} removes them. Every payload carries the marker
 * {@value #MARKER}, which selects this class's rows and publications.
 *
 * <p><b>What the siblings own.</b> {@code repository/OutboxEventRepositoryTest} asserts the
 * declared method inventory of the repository, disjoint skip-locked claims, due-row filtering,
 * stale-claim recovery and retention purge. The assertions below read the relay's publication
 * sequence.
 * {@code outbox/DeadLetterMetadataTest} asserts the configured topic surface and the dead-letter
 * topic name.
 */
@DisplayName("OutboxRelay, the transactional sweep over claimed outbox_event rows")
@ContextConfiguration(classes = OutboxRelayTest.RecordingPublisherConfiguration.class)
class OutboxRelayTest extends AbstractAccountPostgresTest {

    /** Name of the one scheduled method of the relay. */
    private static final String SWEEP_METHOD = "publishPendingEvents";

    /** Milliseconds between the end of one sweep and the start of the next. */
    private static final long FIXED_DELAY_MS = 500L;

    /** Rows one sweep takes, the bound {@code Limit.of(100)} carries. */
    private static final int SWEEP_BATCH_SIZE = 100;

    /** Rows the batching test writes, five above {@link #SWEEP_BATCH_SIZE}. */
    private static final int ROWS_ABOVE_ONE_BATCH = 105;

    /** The one event type this module writes, within {@link OutboxEventEntity#EVENT_TYPE_MAX_LENGTH}. */
    private static final String EVENT_TYPE = "AccountStateChanged";

    /** Text every payload here carries, and the filter every assertion applies. */
    private static final String MARKER = "outbox-relay-test";

    /** Topic name {@code carddemo.kafka.topics.account-state-changed} resolves to by default. */
    private static final String DEFAULT_TOPIC = "account.state-changed";

    /** The property key {@code KafkaProducerConfig} owns for the published topic. */
    private static final String TOPIC_PROPERTY = "carddemo.kafka.topics.account-state-changed";

    /** Name {@code carddemo.kafka.topics.dead-letter} resolves to in {@code application.yml}. */
    private static final String DEAD_LETTER_TOPIC = "carddemo.dead-letter";

    /** Value {@link ConfiguredTopicOverride} sets {@value #TOPIC_PROPERTY} to. */
    private static final String OVERRIDDEN_TOPIC = "account.state-changed-under-test";

    /** Row number {@link ConfiguredTopicOverride} writes. */
    private static final int OVERRIDE_ROW = 0x90;

    /** The second event type this module writes, which {@code OutboxRelay} routes elsewhere. */
    private static final String CUSTOMER_CONTEXT_EVENT_TYPE = "CustomerContextChanged";

    /** Name {@code carddemo.kafka.topics.customer-context-changed} resolves to by default. */
    private static final String CUSTOMER_CONTEXT_TOPIC = "customer.context-changed";

    /** The property key {@code KafkaProducerConfig} owns for that destination. */
    private static final String CONTEXT_TOPIC_PROPERTY =
            "carddemo.kafka.topics.customer-context-changed";

    /** Value {@link ConfiguredContextTopicOverride} sets {@value #CONTEXT_TOPIC_PROPERTY} to. */
    private static final String OVERRIDDEN_CONTEXT_TOPIC = "customer.context-changed-under-test";

    /** Row number {@link ConfiguredContextTopicOverride} writes. */
    private static final int OVERRIDE_CONTEXT_ROW = 0x91;

    /** An event type {@code OutboxRelay.topicFor} answers no destination for. */
    private static final String UNCONFIGURED_EVENT_TYPE = "AccountRetired";

    /**
     * The placeholder a diagnostic reports in place of a destination the stored type names none for.
     * {@code OutboxRelay} declares the same text privately, and
     * {@code schemas/dead-letter-v1.json} has to accept it.
     */
    private static final String UNRESOLVED_DESTINATION = "no-configured-topic";

    /**
     * One payload whose keys run out of alphabetical order and whose spacing is irregular, with a
     * line break and a tab inside it. A publisher that parsed and wrote the document again would
     * deliver different text.
     */
    private static final String UNUSUAL_PAYLOAD = "{   \"zeta\" : 3,\n  \"marker\":\"" + MARKER
            + "\",\n\t\"alpha\"   :{\"second\":2,\"first\":1}   }";

    /** Text form of every identifier here, completed by two hexadecimal digits. */
    private static final String EVENT_ID_STEM = "00000000-0000-4000-8000-0000000000";

    /** Characters an account identifier holds, from {@code ACCT-ID PIC 9(11)}. */
    private static final int ACCOUNT_ID_LENGTH = 11;

    /**
     * An account identifier opening with a zero. All 50 identifiers of
     * {@code app/data/ASCII/acctdata.txt} open with one.
     */
    private static final String ACCOUNT_ID_LEADING_ZERO = "00000000011";

    /** A second account identifier, opening with a digit other than zero. */
    private static final String ACCOUNT_ID_PLAIN = "10000000042";

    /** The instant every transactional row here measures from, well behind any live row. */
    private static final Instant BASE_INSTANT = Instant.parse("2024-01-01T00:00:00Z");

    /** Future instant used by committed concurrency rows, beyond the live scheduler's clock. */
    private static final Instant CLAIM_NOW = Instant.parse("2099-01-01T00:00:10Z");

    /** Row number of the committed row the scheduler test writes, above every other number here. */
    private static final int SCHEDULED_ROW = 0xe0;

    /**
     * Seconds beyond {@link #BASE_INSTANT} the committed row records, which place it behind every
     * other row this class writes.
     */
    private static final long SCHEDULED_ROW_OFFSET_SECONDS = 10_000L;

    /** Longest wait for the scheduler to sweep, above {@link #FIXED_DELAY_MS} by a wide margin. */
    private static final Duration SCHEDULER_TIMEOUT = Duration.ofSeconds(20);

    /** Gap between two reads while waiting for the scheduler. */
    private static final Duration SCHEDULER_POLL_INTERVAL = Duration.ofMillis(100);

    /** The relay under test, the container-managed instance the scheduler also drives. */
    @Autowired
    private OutboxRelay relay;

    /** The bound relay settings, so a test asserts the values the relay actually runs with. */
    @Autowired
    private AccountProperties accountProperties;

    /** The table this class writes rows to and reads them back from. */
    @Autowired
    private OutboxEventRepository outboxEvents;

    /** The publisher stub every publish of this class reaches. */
    @Autowired
    private RecordingEventPublisher publisher;

    /** The configuration that resolves the published topic name. */
    @Autowired
    private KafkaProducerConfig kafkaProducerConfig;

    /** The manager {@link #removeSeededRows()} opens its own transaction through. */
    @Autowired
    private PlatformTransactionManager transactionManager;

    /** Mapper the production relay uses for a terminal dead-letter envelope. */
    @Autowired
    @Qualifier("accountEventObjectMapper")
    private ObjectMapper objectMapper;

    /** Registry supplied to manually constructed relay instances. */
    @Autowired
    private MeterRegistry meterRegistry;

    /** The flush path, which sends pending changes to the table before a read. */
    @PersistenceContext
    private EntityManager entityManager;

    /** Identifier of every row this class writes, in write order. */
    private final Set<UUID> seededEventIds = new LinkedHashSet<>();

    /** Empties the recording and returns the stub to accepting every publish. */
    @BeforeEach
    void resetPublisher() {
        publisher.reset();
    }

    /**
     * Deletes every row this class wrote, in a transaction of its own.
     *
     * <p>A row a rolled-back test wrote is invisible to the deleting transaction, which passes over
     * it. The row {@link #theSchedulerPublishesACommittedRow()} committed is visible, and the delete
     * removes it.
     */
    @AfterEach
    void removeSeededRows() {
        if (seededEventIds.isEmpty()) {
            return;
        }
        List<UUID> written = List.copyOf(seededEventIds);
        seededEventIds.clear();
        TransactionTemplate ownTransaction = new TransactionTemplate(transactionManager);
        ownTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        ownTransaction.executeWithoutResult(status -> outboxEvents.deleteAllById(written));
    }

    @Test
    @DisplayName("One method of the relay carries the scheduling annotation, and it is the sweep")
    void theRelayDeclaresOneScheduledMethod() {
        List<String> scheduled = Arrays.stream(OutboxRelay.class.getDeclaredMethods())
                .filter(method -> method.isAnnotationPresent(Scheduled.class))
                .map(Method::getName)
                .toList();

        assertThat(scheduled)
                .as("methods of OutboxRelay carrying @Scheduled")
                .containsExactly(SWEEP_METHOD);
    }

    /**
     * The delay has to come from the shipped file, not from a number written into the class.
     *
     * <p>A numeric {@code fixedDelay} ignores {@code carddemo.outbox.relay.fixed-delay-ms} entirely,
     * so the shipped value and the running value can differ with nothing to say which one applies.
     * Asserting on the placeholder text is what keeps the two in step.
     */
    @Test
    @DisplayName("The sweep reads its fixed delay from the shipped relay property")
    void theSweepRunsOnAFixedDelayOfFiveHundredMilliseconds() {
        Scheduled schedule = sweepMethod().getAnnotation(Scheduled.class);

        assertThat(schedule.fixedDelay())
                .as("numeric fixedDelay of %s", SWEEP_METHOD)
                .isNegative();
        assertThat(schedule.fixedDelayString())
                .as("fixedDelayString of %s", SWEEP_METHOD)
                .isEqualTo("${carddemo.outbox.relay.fixed-delay-ms:500}");
        assertThat(schedule.timeUnit())
                .as("time unit the delay counts in")
                .isEqualTo(TimeUnit.MILLISECONDS);
    }

    @Test
    @DisplayName("The sweep opens its claim transaction explicitly")
    void theSweepOpensItsClaimTransactionExplicitly() {
        Method sweep = sweepMethod();

        assertThat(annotationNamesOf(sweep))
                .as("annotations declared on %s", SWEEP_METHOD)
                .doesNotContain(Transactional.class.getSimpleName());
        assertThat(annotationNamesOf(OutboxRelay.class))
                .as("annotations declared on OutboxRelay")
                .doesNotContain(Transactional.class.getSimpleName());
        assertThat(AnnotatedElementUtils.hasAnnotation(sweep, Transactional.class))
                .as("%s leaves transaction ownership to TransactionTemplate", SWEEP_METHOD)
                .isFalse();
        assertThat(AnnotatedElementUtils.hasAnnotation(OutboxRelay.class, Transactional.class))
                .as("OutboxRelay carries @Transactional, directly or through a meta-annotation")
                .isFalse();
    }

    @Test
    @DisplayName("AccountApplication enables scheduling, which the sweep runs under")
    void accountApplicationEnablesScheduling() {
        assertThat(AccountApplication.class.isAnnotationPresent(EnableScheduling.class))
                .as("@EnableScheduling on AccountApplication")
                .isTrue();
    }

    /**
     * Writes one committed row and waits for the scheduler to publish it, with no direct call to the
     * sweep.
     *
     * <p>The row records an instant {@value #SCHEDULED_ROW_OFFSET_SECONDS} seconds past
     * {@link #BASE_INSTANT}, behind every other row this class writes.
     */
    @Test
    @DisplayName("The scheduler publishes a committed row with no direct call to the sweep")
    void theSchedulerPublishesACommittedRow() {
        OutboxEventEntity row = pendingRow(SCHEDULED_ROW,
                BASE_INSTANT.plusSeconds(SCHEDULED_ROW_OFFSET_SECONDS), ACCOUNT_ID_LEADING_ZERO);
        UUID eventId = row.getEventId();
        outboxEvents.save(row);

        await().atMost(SCHEDULER_TIMEOUT)
                .pollInterval(SCHEDULER_POLL_INTERVAL)
                .until(() -> outboxEvents.findById(eventId)
                        .map(OutboxEventEntity::isPublished)
                        .orElse(false));

        assertThat(markedMessages())
                .as("triples the scheduler recorded for the committed row")
                .containsExactly(new PublishedMessage(DEFAULT_TOPIC, ACCOUNT_ID_LEADING_ZERO,
                        payloadFor(SCHEDULED_ROW)));
    }

    /**
     * Writes {@value #ROWS_ABOVE_ONE_BATCH} pending rows, then runs two sweeps.
     *
     * <p>The first sweep publishes {@value #SWEEP_BATCH_SIZE} rows, the bound
     * {@code Limit.of(100)} carries in {@code OutboxRelay}. The second sweep publishes the five
     * rows left.
     */
    @Test
    @Transactional
    @DisplayName("One sweep publishes 100 of 105 pending rows, and the next sweep takes the rest")
    void oneSweepPublishesOneHundredRowsAndTheNextSweepTakesTheRest() {
        List<OutboxEventEntity> oldestFirst = writeRowsAboveOneBatch();
        List<String> everyPayload = payloadsOf(oldestFirst);

        relay.publishPendingEvents();
        flushAndDetach();

        assertThat(markedPayloads())
                .as("payloads the first sweep published")
                .containsExactlyElementsOf(everyPayload.subList(0, SWEEP_BATCH_SIZE));
        assertThat(pendingPayloads())
                .as("payloads still pending after the first sweep")
                .containsExactlyElementsOf(
                        everyPayload.subList(SWEEP_BATCH_SIZE, ROWS_ABOVE_ONE_BATCH));

        relay.publishPendingEvents();
        flushAndDetach();

        assertThat(markedPayloads())
                .as("payloads both sweeps published, in publication order")
                .containsExactlyElementsOf(everyPayload);
        assertThat(pendingPayloads())
                .as("payloads still pending after the second sweep")
                .isEmpty();
    }

    @Test
    @Transactional
    @DisplayName("Each row the sweep takes reaches the publisher exactly once")
    void eachRowReachesThePublisherExactlyOnce() {
        List<OutboxEventEntity> rows = writeRowsAcrossAccounts(0x00, 5);

        relay.publishPendingEvents();
        flushAndDetach();

        assertThat(markedPayloads())
                .as("one publish per row, and no row twice")
                .hasSize(rows.size())
                .doesNotHaveDuplicates()
                .containsExactlyElementsOf(payloadsOf(rows));
    }

    /**
     * Writes one row whose payload carries unusual key order and unusual spacing, then compares the
     * published text to the stored text character for character.
     */
    @Test
    @Transactional
    @DisplayName("The published payload is the stored payload column, character for character")
    void thePublishedPayloadIsTheStoredTextCharacterForCharacter() {
        OutboxEventEntity row = new OutboxEventEntity(eventId(0x11), EVENT_TYPE, UNUSUAL_PAYLOAD,
                ACCOUNT_ID_LEADING_ZERO, BASE_INSTANT);
        seededEventIds.add(row.getEventId());
        outboxEvents.save(row);
        flushAndDetach();
        String stored = outboxEvents.findById(row.getEventId()).orElseThrow().getPayload();

        relay.publishPendingEvents();

        List<String> published = markedPayloads();
        assertThat(published)
                .as("payload text the sweep published, against the stored column")
                .containsExactly(stored);
        assertThat(published.getFirst())
                .as("payload text the sweep published, against the written text")
                .isEqualTo(UNUSUAL_PAYLOAD);
    }

    /**
     * Writes two rows on two accounts and reads the key each publication carried.
     *
     * <p>The first account identifier opens with a zero. Its width is
     * {@value #ACCOUNT_ID_LENGTH} characters, from {@code ACCT-ID PIC 9(11)} at
     * {@code app/cpy/CVACT01Y.cpy:L5} and {@code KEYS(11 0)} at {@code app/jcl/ACCTFILE.jcl:L40}. A
     * numeric rendering of the same value would carry fewer characters.
     */
    @Test
    @Transactional
    @DisplayName("The message key is the eleven-digit account identifier, leading zero kept")
    void theMessageKeyIsTheElevenDigitAccountIdentifier() {
        OutboxEventEntity withLeadingZero = writeRow(0x20, BASE_INSTANT.plusSeconds(1),
                ACCOUNT_ID_LEADING_ZERO);
        OutboxEventEntity withoutLeadingZero = writeRow(0x21, BASE_INSTANT.plusSeconds(2),
                ACCOUNT_ID_PLAIN);
        flushAndDetach();

        relay.publishPendingEvents();

        List<String> keys = markedMessages().stream().map(PublishedMessage::key).toList();
        assertThat(keys)
                .as("keys the sweep published, in publication order")
                .containsExactly(withLeadingZero.getAggregateId(),
                        withoutLeadingZero.getAggregateId());
        assertThat(keys)
                .as("every key against %s", EventPublisherPort.AGGREGATE_ID_PATTERN)
                .allSatisfy(key -> assertThat(key)
                        .hasSize(ACCOUNT_ID_LENGTH)
                        .matches(EventPublisherPort.AGGREGATE_ID_PATTERN));
        assertThat(keys.getFirst())
                .as("the leading zero of the first account identifier")
                .startsWith("0")
                .isEqualTo(ACCOUNT_ID_LEADING_ZERO);
    }

    /**
     * Writes five rows whose creation instants run against their identifiers, two of them sharing
     * one instant, and reads the order the publications arrived in.
     *
     * <p>{@code findByPublishedFalseOrderByCreatedAtAscEventIdAsc} supplies the order, and the sweep
     * publishes one row at a time down that list.
     */
    @Test
    @Transactional
    @DisplayName("The sweep publishes by created_at ascending, then by event_id ascending")
    void publicationFollowsTheCreationInstantThenTheEventIdentifier() {
        Instant sharedInstant = BASE_INSTANT.plusSeconds(2);
        OutboxEventEntity third =
                writeRow(0x30, BASE_INSTANT.plusSeconds(3), accountIdFor(0x30));
        OutboxEventEntity first =
                writeRow(0x31, BASE_INSTANT.plusSeconds(1), accountIdFor(0x31));
        OutboxEventEntity tiedLower =
                writeRow(0x32, sharedInstant, accountIdFor(0x32));
        OutboxEventEntity tiedHigher =
                writeRow(0x33, sharedInstant, accountIdFor(0x33));
        OutboxEventEntity last =
                writeRow(0x34, BASE_INSTANT.plusSeconds(4), accountIdFor(0x34));
        flushAndDetach();

        relay.publishPendingEvents();

        assertThat(markedPayloads())
                .as("publication order, oldest instant first and the lower identifier of the tie")
                .containsExactly(payloadOf(first), payloadOf(tiedLower), payloadOf(tiedHigher),
                        payloadOf(third), payloadOf(last));
    }

    @Test
    @Transactional
    @DisplayName("A published row carries both mark columns, and no later sweep takes it again")
    void aPublishedRowCarriesBothMarkColumnsAndNoLaterSweepTakesItAgain() {
        OutboxEventEntity row = writeRow(0x40, BASE_INSTANT, ACCOUNT_ID_LEADING_ZERO);
        flushAndDetach();

        relay.publishPendingEvents();
        flushAndDetach();

        OutboxEventEntity marked = outboxEvents.findById(row.getEventId()).orElseThrow();
        assertThat(marked.isPublished()).as("published column of the swept row").isTrue();
        assertThat(marked.getPublishedAt())
                .as("published_at column of the swept row")
                .isNotNull();

        relay.publishPendingEvents();

        assertThat(markedPayloads())
                .as("payloads published across both sweeps")
                .containsExactly(payloadOf(row));
    }

    /**
     * Refuses one publish, reads the row, then accepts a publish once the row is due again.
     *
     * <p>{@code OutboxRelay} writes no retry loop and names no second topic. A refused row keeps
     * {@code published} false and {@code published_at} empty, and a sweep takes it again once its
     * backoff has passed.
     *
     * <p>The immediate sweep in the middle is the assertion that the backoff exists. Retrying a
     * refused row on the very next sweep is what makes one undeliverable row hold every row behind it,
     * because the sweep stops at the first failure. The row is then made due by hand rather than by
     * sleeping, so the test states the rule instead of waiting on a clock.
     */
    @Test
    @Transactional
    @DisplayName("A refused publish leaves the row pending, and a sweep takes it once it is due")
    void aRefusedPublishLeavesTheRowPendingUntilItIsDueAgain() {
        OutboxEventEntity row = writeRow(0x50, BASE_INSTANT, ACCOUNT_ID_LEADING_ZERO);
        flushAndDetach();
        publisher.refuseEveryPublish();

        relay.publishPendingEvents();
        flushAndDetach();

        OutboxEventEntity refused = outboxEvents.findById(row.getEventId()).orElseThrow();
        assertThat(refused.isPublished())
                .as("published column after the refused publish")
                .isFalse();
        assertThat(refused.getPublishedAt())
                .as("published_at column after the refused publish")
                .isNull();

        publisher.acceptEveryPublish();
        relay.publishPendingEvents();
        flushAndDetach();

        assertThat(outboxEvents.findById(row.getEventId()).orElseThrow().isPublished())
                .as("published column while the row is still inside its backoff, which is what "
                        + "keeps one undeliverable row from being retried by every sweep")
                .isFalse();

        makeDueNow(row.getEventId());
        relay.publishPendingEvents();
        flushAndDetach();

        assertThat(outboxEvents.findById(row.getEventId()).orElseThrow().isPublished())
                .as("published column after the sweep that follows the backoff")
                .isTrue();
    }

    /**
     * Refuses every publish of a three-row batch and reads what the sweep attempted.
     *
     * <p>One attempt, not three. Ordering exists to keep one account's events in the order its state
     * changed, and attempting the rest of the batch after a refusal defeats it: the second and third
     * rows would reach the broker while the first had not, leaving a consumer with a later value and
     * never the earlier one. The sweep therefore stops on the first refusal and the next sweep resumes
     * at that row.
     */
    @Test
    @Transactional
    @DisplayName("A refused publish stops later rows of the same account partition")
    void aRefusedPublishStopsTheAccountPartition() {
        List<OutboxEventEntity> rows = writeRows(0x60, 3, ACCOUNT_ID_LEADING_ZERO);
        OutboxEventEntity otherAccount =
                writeRow(0x63, BASE_INSTANT.plusSeconds(4), ACCOUNT_ID_PLAIN);
        publisher.refuseEveryPublish();

        relay.publishPendingEvents();

        assertThat(markedPayloads())
                .as("one attempt per account partition")
                .containsExactly(payloadOf(rows.getFirst()), payloadOf(otherAccount));
    }

    @Test
    @Transactional
    @DisplayName("The sweep never addresses the dead-letter topic, refused or accepted")
    void noPublicationIsAddressedToTheDeadLetterTopic() {
        writeRow(0x70, BASE_INSTANT, ACCOUNT_ID_LEADING_ZERO);
        flushAndDetach();

        publisher.refuseEveryPublish();
        relay.publishPendingEvents();
        publisher.acceptEveryPublish();
        relay.publishPendingEvents();
        flushAndDetach();

        assertThat(publisher.everyMessage())
                .as("every publication of both sweeps")
                .isNotEmpty()
                .extracting(PublishedMessage::topic)
                .doesNotContain(DEAD_LETTER_TOPIC);
    }

    @Test
    @DisplayName("two concurrent relays publish disjoint claimed rows")
    void twoConcurrentRelaysPublishDisjointClaimedRows() throws Exception {
        OutboxEventEntity firstRow =
                pendingRow(0xa0, CLAIM_NOW.minusSeconds(2), ACCOUNT_ID_LEADING_ZERO);
        OutboxEventEntity secondRow =
                pendingRow(0xa1, CLAIM_NOW.minusSeconds(1), ACCOUNT_ID_PLAIN);
        inTransaction(() -> {
            outboxEvents.saveAll(List.of(firstRow, secondRow));
            return null;
        });

        BlockingEventPublisher blockingPublisher = new BlockingEventPublisher();
        AccountProperties oneRowPerClaim = propertiesWithBatchSize(1);
        Clock clock = Clock.fixed(CLAIM_NOW, ZoneOffset.UTC);
        OutboxRelay firstRelay = new OutboxRelay(
                outboxEvents, blockingPublisher, immediateTransactions(), oneRowPerClaim,
                objectMapper, accountMeters(), clock, "relay-one");
        OutboxRelay secondRelay = new OutboxRelay(
                outboxEvents, blockingPublisher, immediateTransactions(), oneRowPerClaim,
                objectMapper, accountMeters(), clock, "relay-two");

        ExecutorService workers = Executors.newFixedThreadPool(2);
        try {
            Future<?> first = workers.submit(
                    () -> inTransaction(() -> {
                        firstRelay.publishPendingEvents();
                        return null;
                    }));
            assertThat(blockingPublisher.firstPublishEntered.await(5, TimeUnit.SECONDS)).isTrue();

            Future<?> second = workers.submit(
                    () -> inTransaction(() -> {
                        secondRelay.publishPendingEvents();
                        return null;
                    }));
            second.get(5, TimeUnit.SECONDS);

            blockingPublisher.releaseFirstPublish.countDown();
            first.get(5, TimeUnit.SECONDS);
        } finally {
            blockingPublisher.releaseFirstPublish.countDown();
            workers.shutdownNow();
        }

        assertThat(blockingPublisher.messages)
                .extracting(PublishedMessage::payload)
                .containsExactlyInAnyOrder(payloadOf(firstRow), payloadOf(secondRow))
                .doesNotHaveDuplicates();
        assertThat(outboxEvents.findById(firstRow.getEventId()).orElseThrow().isPublished())
                .isTrue();
        assertThat(outboxEvents.findById(secondRow.getEventId()).orElseThrow().isPublished())
                .isTrue();
    }

    @Test
    @DisplayName("a stale claim returns to pending and is published in the same sweep")
    void aStaleClaimIsRecoveredAndPublished() {
        OutboxEventEntity row =
                pendingRow(0xa2, CLAIM_NOW.minusSeconds(120), ACCOUNT_ID_LEADING_ZERO);
        row.claim("relay-that-stopped", CLAIM_NOW.minusSeconds(60));
        inTransaction(() -> {
            outboxEvents.save(row);
            return null;
        });
        OutboxRelay recoveringRelay = manualRelay(
                publisher, propertiesWithBatchSize(1), "recovering-relay");

        inTransaction(() -> {
            recoveringRelay.publishPendingEvents();
            return null;
        });

        OutboxEventEntity recovered = outboxEvents.findById(row.getEventId()).orElseThrow();
        assertThat(recovered.isPublished()).isTrue();
        assertThat(recovered.getRelayState())
                .isEqualTo(OutboxEventEntity.RelayState.PUBLISHED);
        assertThat(recovered.getAttemptCount()).isEqualTo(1);
        assertThat(markedPayloads()).contains(payloadOf(row));
    }

    @Test
    @DisplayName("the relay leaves published-row retention to the dedicated retention sweep")
    void theRelayLeavesPublishedRowRetentionToTheDedicatedSweep() {
        OutboxEventEntity expired =
                pendingRow(0xa3, CLAIM_NOW.minus(Duration.ofHours(3)), ACCOUNT_ID_LEADING_ZERO);
        expired.markPublished(CLAIM_NOW.minus(Duration.ofHours(2)));
        OutboxEventEntity retained =
                pendingRow(0xa4, CLAIM_NOW.minus(Duration.ofHours(2)), ACCOUNT_ID_PLAIN);
        retained.markPublished(CLAIM_NOW.minus(Duration.ofMinutes(30)));
        inTransaction(() -> {
            outboxEvents.saveAll(List.of(expired, retained));
            return null;
        });
        OutboxRelay purgingRelay = manualRelay(
                publisher, propertiesWithBatchSizeAndRetention(1, 1L), "purging-relay");

        inTransaction(() -> {
            purgingRelay.publishPendingEvents();
            return null;
        });

        assertThat(outboxEvents.findById(expired.getEventId())).isPresent();
        assertThat(outboxEvents.findById(retained.getEventId())).isPresent();
    }

    @Test
    @Transactional
    @DisplayName("The sweep publishes to account.state-changed with no override in place")
    void theSweepPublishesToTheConfiguredAccountTopic() {
        writeRow(0x80, BASE_INSTANT, ACCOUNT_ID_LEADING_ZERO);
        flushAndDetach();

        relay.publishPendingEvents();

        assertThat(markedMessages())
                .as("topic every publication of the sweep carried")
                .isNotEmpty()
                .extracting(PublishedMessage::topic)
                .containsOnly(DEFAULT_TOPIC);
        assertThat(kafkaProducerConfig.accountStateChangedTopic())
                .as("name %s resolves to with no override in place", TOPIC_PROPERTY)
                .isEqualTo(DEFAULT_TOPIC);
    }

    // =============================================================================================
    // The second configured destination. OutboxRelay.topicFor answers two topics, and every
    // assertion above writes AccountStateChanged, so the second arm of that switch was never taken.
    // A relay that routed both types to the account topic, or that answered no topic for the customer
    // type, would satisfy all of them.
    //
    // The two types are not interchangeable. AccountUpdateService writes one, the other, or both from
    // one update depending on which record the caller changed, so a consumer of the account topic
    // handed a customer payload would be handed a document its schema refuses, and a consumer of the
    // customer topic would never learn of the change at all.
    // =============================================================================================

    @Test
    @Transactional
    @DisplayName("A customer context row is published to customer.context-changed")
    void aCustomerContextRowReachesItsOwnTopic() {
        OutboxEventEntity row = writeRow(0xa0, BASE_INSTANT, ACCOUNT_ID_LEADING_ZERO,
                CUSTOMER_CONTEXT_EVENT_TYPE);
        flushAndDetach();

        relay.publishPendingEvents();

        assertThat(markedMessages())
                .as("the publication of the one customer context row")
                .singleElement()
                .satisfies(message -> {
                    assertThat(message.topic())
                            .as("destination of a customer context event")
                            .isEqualTo(CUSTOMER_CONTEXT_TOPIC)
                            .isNotEqualTo(DEFAULT_TOPIC);
                    assertThat(message.key())
                            .as("the key is the account identifier, leading zero kept")
                            .isEqualTo(ACCOUNT_ID_LEADING_ZERO);
                    assertThat(message.payload())
                            .as("the payload column reaches the broker character for character")
                            .isEqualTo(payloadOf(row));
                });
        assertThat(kafkaProducerConfig.customerContextChangedTopic())
                .as("name %s resolves to with no override in place", CONTEXT_TOPIC_PROPERTY)
                .isEqualTo(CUSTOMER_CONTEXT_TOPIC);
    }

    @Test
    @Transactional
    @DisplayName("One sweep carrying both types sends each to its own topic")
    void oneSweepRoutesEachTypeToItsOwnTopic() {
        OutboxEventEntity accountRow =
                writeRow(0xa1, BASE_INSTANT, ACCOUNT_ID_LEADING_ZERO, EVENT_TYPE);
        OutboxEventEntity customerRow = writeRow(0xa2, BASE_INSTANT.plusSeconds(1L),
                ACCOUNT_ID_PLAIN, CUSTOMER_CONTEXT_EVENT_TYPE);
        flushAndDetach();

        relay.publishPendingEvents();

        assertThat(markedMessages())
                .as("both publications, in the order the sweep made them")
                .extracting(PublishedMessage::topic, PublishedMessage::payload)
                .containsExactly(tuple(DEFAULT_TOPIC, payloadOf(accountRow)),
                        tuple(CUSTOMER_CONTEXT_TOPIC, payloadOf(customerRow)));
    }

    /**
     * The retry path of the second type, which shares one backoff with the first.
     *
     * <p>The immediate sweep in the middle is the assertion that the backoff applies to this type
     * too. A row retried by the very next sweep holds every row of its account behind it.
     */
    @Test
    @Transactional
    @DisplayName("A refused customer context publish waits for its backoff, then is published")
    void aRefusedCustomerContextPublishWaitsForItsBackoff() {
        OutboxEventEntity row = writeRow(0xa3, BASE_INSTANT, ACCOUNT_ID_LEADING_ZERO,
                CUSTOMER_CONTEXT_EVENT_TYPE);
        flushAndDetach();
        publisher.refuseEveryPublish();

        relay.publishPendingEvents();
        flushAndDetach();

        OutboxEventEntity refused = outboxEvents.findById(row.getEventId()).orElseThrow();
        assertThat(refused.isPublished())
                .as("published column after the refused publish")
                .isFalse();
        assertThat(refused.getAttemptCount())
                .as("the refused attempt was recorded against the row")
                .isEqualTo(1);

        publisher.acceptEveryPublish();
        relay.publishPendingEvents();
        flushAndDetach();

        assertThat(outboxEvents.findById(row.getEventId()).orElseThrow().isPublished())
                .as("published column while the row is still inside its backoff")
                .isFalse();

        makeDueNow(row.getEventId());
        relay.publishPendingEvents();
        flushAndDetach();

        assertThat(outboxEvents.findById(row.getEventId()).orElseThrow().isPublished())
                .as("published column after the sweep that follows the backoff")
                .isTrue();
        assertThat(markedMessages())
                .as("the destination every attempt of this row carried")
                .extracting(PublishedMessage::topic)
                .containsOnly(CUSTOMER_CONTEXT_TOPIC);
    }

    /**
     * An event type the switch answers no topic for, read against a real table.
     *
     * <p>{@code OutboxRelayTerminalPathTest} asserts the same rule over a recording repository, which
     * is where the bookkeeping order is pinned. This one asserts it against the migrated table and the
     * container-managed relay, so the row's own columns carry the outcome: the attempt count rises,
     * the row reaches {@code ABANDONED} on schedule rather than being retried for ever, and the
     * diagnostic names itself on the dead-letter topic.
     */
    @Test
    @Transactional
    @DisplayName("An event type with no configured topic is abandoned and dead-lettered")
    void anUnconfiguredEventTypeIsAbandonedAndDeadLettered() {
        OutboxEventEntity row = writeRow(0xa4, BASE_INSTANT, ACCOUNT_ID_LEADING_ZERO,
                UNCONFIGURED_EVENT_TYPE);
        flushAndDetach();

        for (int sweep = 0; sweep < OutboxEventEntity.MAX_DELIVERY_ATTEMPTS; sweep++) {
            makeDueNow(row.getEventId());
            relay.publishPendingEvents();
            flushAndDetach();
        }

        OutboxEventEntity spent = outboxEvents.findById(row.getEventId()).orElseThrow();
        assertThat(spent.getAttemptCount())
                .as("every sweep recorded its attempt, so the row could reach its bound")
                .isEqualTo(OutboxEventEntity.MAX_DELIVERY_ATTEMPTS);
        assertThat(spent.getRelayState())
                .as("a row naming no topic is given up on rather than retried for ever")
                .isEqualTo(OutboxEventEntity.RelayState.ABANDONED);
        assertThat(spent.getDeadLetterState())
                .as("the diagnostic this row owed was discharged")
                .isEqualTo(OutboxEventEntity.DeadLetterState.PUBLISHED);
        assertThat(publisher.everyMessage())
                .as("the one publication a row naming no topic produces")
                .filteredOn(message -> DEAD_LETTER_TOPIC.equals(message.topic()))
                .singleElement()
                .satisfies(letter -> assertThat(letter.payload())
                        .contains(row.getEventId().toString())
                        .contains(UNCONFIGURED_EVENT_TYPE)
                        .contains(UNRESOLVED_DESTINATION));
        assertThat(publisher.everyMessage())
                .as("no publication was addressed to either configured topic")
                .extracting(PublishedMessage::topic)
                .doesNotContain(DEFAULT_TOPIC, CUSTOMER_CONTEXT_TOPIC);
    }

    /**
     * The customer destination under a different value for {@value #CONTEXT_TOPIC_PROPERTY}.
     *
     * <p>{@link TestPropertySource} on this class builds a third application context, and the relay
     * reads the customer destination from that context. The account destination is left alone, so the
     * two are shown to be separately configurable rather than one name serving both.
     */
    @Nested
    @TestPropertySource(properties = CONTEXT_TOPIC_PROPERTY + "=" + OVERRIDDEN_CONTEXT_TOPIC)
    @DisplayName("The customer destination under an overridden property")
    class ConfiguredContextTopicOverride {

        /**
         * Registers {@link RecordingEventPublisher} in the third application context.
         *
         * <p>A Spring test context takes the configuration classes declared inside its own test
         * class, so this declaration is not optional: without it the context of this class carries no
         * recording publisher and the enclosing instance cannot be injected.
         */
        @TestConfiguration
        static class ContextOverridingRecordingPublisherConfiguration
                extends RecordingPublisherConfiguration {
        }

        @Test
        @Transactional
        @DisplayName("The sweep publishes to the overridden customer topic name")
        void theSweepPublishesToTheOverriddenContextTopic() {
            writeRow(OVERRIDE_CONTEXT_ROW, BASE_INSTANT, ACCOUNT_ID_LEADING_ZERO,
                    CUSTOMER_CONTEXT_EVENT_TYPE);
            flushAndDetach();

            relay.publishPendingEvents();

            assertThat(markedMessages())
                    .as("publications the sweep recorded in the overriding context")
                    .extracting(PublishedMessage::topic)
                    .containsExactly(OVERRIDDEN_CONTEXT_TOPIC);
            assertThat(kafkaProducerConfig.customerContextChangedTopic())
                    .as("name %s resolves to under the override", CONTEXT_TOPIC_PROPERTY)
                    .isEqualTo(OVERRIDDEN_CONTEXT_TOPIC);
            assertThat(kafkaProducerConfig.accountStateChangedTopic())
                    .as("the account destination is untouched by that override")
                    .isEqualTo(DEFAULT_TOPIC);
        }
    }

    /**
     * The same sweep under a different value for {@value #TOPIC_PROPERTY}.
     *
     * <p>{@link TestPropertySource} on this class builds a second application context, and the
     * relay reads the topic name from that context. {@link RecordingEventPublisher} reaches that
     * second context from the class annotation of the enclosing class, so this group declares no
     * configuration of its own.
     */
    @Nested
    @TestPropertySource(properties = TOPIC_PROPERTY + "=" + OVERRIDDEN_TOPIC)
    @DisplayName("The published topic under an overridden property")
    class ConfiguredTopicOverride {

        @Test
        @Transactional
        @DisplayName("The sweep publishes to the overridden topic name")
        void theSweepPublishesToTheOverriddenTopic() {
            writeRow(OVERRIDE_ROW, BASE_INSTANT, ACCOUNT_ID_LEADING_ZERO);
            flushAndDetach();

            relay.publishPendingEvents();

            assertThat(markedMessages())
                    .as("publications the sweep recorded in the overriding context")
                    .extracting(PublishedMessage::topic)
                    .containsExactly(OVERRIDDEN_TOPIC);
            assertThat(kafkaProducerConfig.accountStateChangedTopic())
                    .as("name %s resolves to under the override", TOPIC_PROPERTY)
                    .isEqualTo(OVERRIDDEN_TOPIC);
        }
    }

    /**
     * Writes {@value #ROWS_ABOVE_ONE_BATCH} pending rows, newest first, and returns them oldest
     * first.
     *
     * <p>The write order runs against the creation instants, so the publication order below comes
     * from the finder and not from the insert sequence.
     *
     * @return the written rows, in the order the pending finder yields them
     */
    private List<OutboxEventEntity> writeRowsAboveOneBatch() {
        List<OutboxEventEntity> oldestFirst = new ArrayList<>();
        for (int rowNumber = 0; rowNumber < ROWS_ABOVE_ONE_BATCH; rowNumber++) {
            oldestFirst.add(pendingRow(rowNumber, BASE_INSTANT.plusSeconds(rowNumber + 1L),
                    accountIdFor(rowNumber)));
        }
        List<OutboxEventEntity> newestFirst = new ArrayList<>(oldestFirst);
        Collections.reverse(newestFirst);
        outboxEvents.saveAll(newestFirst);
        flushAndDetach();
        return List.copyOf(oldestFirst);
    }

    /**
     * Writes a run of pending rows on one account, one second apart.
     *
     * @param firstRowNumber row number of the oldest row
     * @param count          how many rows to write
     * @param accountId      the account identifier every row carries
     * @return the written rows, oldest first
     */
    private List<OutboxEventEntity> writeRows(int firstRowNumber, int count, String accountId) {
        List<OutboxEventEntity> rows = new ArrayList<>();
        for (int offset = 0; offset < count; offset++) {
            rows.add(pendingRow(firstRowNumber + offset, BASE_INSTANT.plusSeconds(offset + 1L),
                    accountId));
        }
        outboxEvents.saveAll(rows);
        flushAndDetach();
        return List.copyOf(rows);
    }

    /**
     * Writes a run of pending rows, each under a separate account partition.
     *
     * @param firstRowNumber row number of the oldest row
     * @param count          how many rows to write
     * @return the written rows, oldest first
     */
    private List<OutboxEventEntity> writeRowsAcrossAccounts(int firstRowNumber, int count) {
        List<OutboxEventEntity> rows = new ArrayList<>();
        for (int offset = 0; offset < count; offset++) {
            int rowNumber = firstRowNumber + offset;
            rows.add(pendingRow(rowNumber, BASE_INSTANT.plusSeconds(offset + 1L),
                    accountIdFor(rowNumber)));
        }
        outboxEvents.saveAll(rows);
        flushAndDetach();
        return List.copyOf(rows);
    }

    /**
     * Writes one pending row.
     *
     * @param rowNumber the row number, which the payload and the identifier both carry
     * @param createdAt the instant the row records as its creation
     * @param accountId the account identifier the row carries
     * @return the written row
     */
    private OutboxEventEntity writeRow(int rowNumber, Instant createdAt, String accountId) {
        OutboxEventEntity row = pendingRow(rowNumber, createdAt, accountId);
        outboxEvents.save(row);
        return row;
    }

    /**
     * Stores one pending row of a named event type.
     *
     * <p>{@link #writeRow(int, Instant, String)} writes {@value #EVENT_TYPE}, which is one of the two
     * types this relay routes. This overload names the type, so a test can write the other one, or one
     * the relay routes nowhere.
     *
     * @param rowNumber the row number, which fixes the event identifier and the payload
     * @param createdAt the creation instant the pending finder orders by
     * @param accountId the aggregate identifier, which becomes the message key
     * @param eventType the stored event type the relay resolves a destination from
     * @return the stored row
     */
    private OutboxEventEntity writeRow(int rowNumber, Instant createdAt, String accountId,
            String eventType) {
        UUID identifier = eventId(rowNumber);
        seededEventIds.add(identifier);
        OutboxEventEntity row = new OutboxEventEntity(identifier, eventType,
                payloadFor(rowNumber), accountId, createdAt);
        outboxEvents.save(row);
        return row;
    }

    /**
     * Builds one unpublished row and records its identifier for {@link #removeSeededRows()}.
     *
     * @param rowNumber the row number, which the payload and the identifier both carry
     * @param createdAt the instant the row records as its creation
     * @param accountId the account identifier the row carries
     * @return an unpublished row, not yet written
     */
    private OutboxEventEntity pendingRow(int rowNumber, Instant createdAt, String accountId) {
        UUID identifier = eventId(rowNumber);
        seededEventIds.add(identifier);
        return new OutboxEventEntity(identifier, EVENT_TYPE, payloadFor(rowNumber), accountId,
                createdAt);
    }

    /**
     * Returns the identifier of one row, from {@link #EVENT_ID_STEM} and two hexadecimal digits.
     *
     * @param rowNumber the row number, at most {@code 0xff}
     * @return the identifier of that row
     */
    private static UUID eventId(int rowNumber) {
        return UUID.fromString(EVENT_ID_STEM + String.format("%02x", rowNumber));
    }

    /** Returns one eleven-digit account identifier for a row number. */
    private static String accountIdFor(int rowNumber) {
        return String.format(Locale.ROOT, "%011d", 1_000L + rowNumber);
    }

    /** Returns valid account properties with the supplied claim size. */
    private static AccountProperties propertiesWithBatchSize(int batchSize) {
        return propertiesWithBatchSizeAndRetention(batchSize, 168L);
    }

    /** Returns valid account properties with the supplied claim size and retention. */
    private static AccountProperties propertiesWithBatchSizeAndRetention(
            int batchSize, long retentionHours) {
        return new AccountProperties(
                new AccountProperties.Api(65536L),
                new AccountProperties.Kafka(
                        new AccountProperties.Kafka.Topics(DEFAULT_TOPIC,
                                "customer.context-changed", "transaction.posted",
                                DEAD_LETTER_TOPIC),
                        new AccountProperties.Kafka.Groups("account-posted")),
                new AccountProperties.Consumer(new AccountProperties.Consumer.Retry(3, 1_000L)),
                new AccountProperties.Outbox(
                        new AccountProperties.Outbox.Relay(
                                FIXED_DELAY_MS, batchSize, "account-relay",
                                java.time.Duration.ofSeconds(30L), 30_000L,
                                java.time.Duration.ofSeconds(10L)),
                        retentionHours),
                new AccountProperties.Retention(3_600_000L),
                new AccountProperties.Write(3_000L));
    }

    /** Builds a manually driven relay on the fixed concurrency-test clock. */
    private OutboxRelay manualRelay(EventPublisherPort eventPublisher,
            AccountProperties properties, String relayId) {
        return new OutboxRelay(
                outboxEvents, eventPublisher, immediateTransactions(), properties, objectMapper,
                accountMeters(), Clock.fixed(CLAIM_NOW, ZoneOffset.UTC), relayId);
    }

    /**
     * Returns the payload of one row, carrying {@value #MARKER} and the row number.
     *
     * @param rowNumber the row number
     * @return the payload text of that row
     */
    private static String payloadFor(int rowNumber) {
        return "{\"marker\":\"" + MARKER + "\",\"row\":" + rowNumber + "}";
    }

    /**
     * Returns the payload of one written row.
     *
     * @param row the row to read
     * @return the value of its {@code payload} column
     */
    private static String payloadOf(OutboxEventEntity row) {
        return row.getPayload();
    }

    /**
     * Returns the payloads of several written rows, in the order given.
     *
     * @param rows the rows to read
     * @return their {@code payload} values
     */
    private static List<String> payloadsOf(List<OutboxEventEntity> rows) {
        return rows.stream().map(OutboxRelayTest::payloadOf).toList();
    }

    /**
     * Returns the publications of this class, in call order.
     *
     * @return the recorded triples whose payload carries {@value #MARKER}
     */
    private List<PublishedMessage> markedMessages() {
        return publisher.messagesCarrying(MARKER);
    }

    /**
     * Returns the payloads this class published, in call order.
     *
     * @return the payload of each recorded triple whose payload carries {@value #MARKER}
     */
    private List<String> markedPayloads() {
        return markedMessages().stream().map(PublishedMessage::payload).toList();
    }

    /**
     * Returns the payloads of the rows this class wrote that await publication, oldest first.
     *
     * @return the pending payloads of the written rows, and empty when none awaits publication
     */
    private List<String> pendingPayloads() {
        return outboxEvents.findByPublishedFalseOrderByCreatedAtAscEventIdAsc(Limit.unlimited())
                .stream()
                .filter(row -> seededEventIds.contains(row.getEventId()))
                .map(OutboxEventEntity::getPayload)
                .toList();
    }

    /**
     * Brings one row's next attempt forward to now, so a sweep claims it without a wait.
     *
     * <p>The update runs through the entity manager rather than through the entity, because
     * {@code nextAttemptAt} has no setter: the entity moves it only through
     * {@code recordFailure}, which is what keeps a caller from quietly cancelling a backoff in
     * production. A test that needs the row due says so here, in one place a reader can find.
     *
     * @param eventId the row to bring forward
     */
    private void makeDueNow(UUID eventId) {
        entityManager.createQuery("""
                        UPDATE OutboxEventEntity row
                        SET row.nextAttemptAt = :now
                        WHERE row.eventId = :eventId
                        """)
                .setParameter("now", Instant.now())
                .setParameter("eventId", eventId)
                .executeUpdate();
        flushAndDetach();
    }

    /** Sends pending changes to the table and detaches every row, so the next read reaches it. */
    private void flushAndDetach() {
        entityManager.flush();
        entityManager.clear();
    }

    /** Runs one callback in a new transaction. */
    private <T> T inTransaction(java.util.function.Supplier<T> callback) {
        TransactionTemplate transaction = new TransactionTemplate(transactionManager);
        transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        return transaction.execute(status -> callback.get());
    }

    /**
     * Returns the scheduled method of the relay.
     *
     * @return the declared method named {@value #SWEEP_METHOD}
     */
    private static Method sweepMethod() {
        try {
            return OutboxRelay.class.getDeclaredMethod(SWEEP_METHOD);
        } catch (NoSuchMethodException absent) {
            throw new AssertionError("OutboxRelay declares no method " + SWEEP_METHOD, absent);
        }
    }

    /**
     * Returns the simple name of every annotation on one element.
     *
     * @param element the class or method to read
     * @return the annotation names present on it
     */
    private static List<String> annotationNamesOf(AnnotatedElement element) {
        return Arrays.stream(element.getAnnotations())
                .map(annotation -> annotation.annotationType().getSimpleName())
                .toList();
    }

    /** Registers {@link RecordingEventPublisher} over the Kafka-backed publisher of the module. */
    @TestConfiguration
    static class RecordingPublisherConfiguration {

        /**
         * Builds the publisher every sweep of this class reaches.
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
     * Keeps every publication in call order and refuses a publish on request.
     *
     * <p>The scheduler and the calling test both reach this publisher, so the recording is a
     * thread-safe list and the refusal flag is volatile. A refused publish is recorded before its
     * returned stage fails, so a caller reads what was attempted.
     */
    static final class RecordingEventPublisher implements EventPublisherPort {

        /** Every publication this publisher took, in call order. */
        private final List<PublishedMessage> messages = new CopyOnWriteArrayList<>();

        /** Whether every publish returns an exceptional completion. */
        private volatile boolean refusing;

        @Override
        public CompletionStage<Void> publish(String topic, String aggregateId, String payload) {
            messages.add(new PublishedMessage(topic, aggregateId, payload));
            if (refusing) {
                return CompletableFuture.failedFuture(
                        new IllegalStateException("the recording publisher refuses this publish"));
            }
            return CompletableFuture.completedFuture(null);
        }

        /** Empties the recording and returns to accepting every publish. */
        void reset() {
            messages.clear();
            refusing = false;
        }

        /** Makes every further publish throw. */
        void refuseEveryPublish() {
            refusing = true;
        }

        /** Makes every further publish return. */
        void acceptEveryPublish() {
            refusing = false;
        }

        /**
         * Returns every publication taken, in call order.
         *
         * @return the recorded triples
         */
        List<PublishedMessage> everyMessage() {
            return List.copyOf(messages);
        }

        /**
         * Returns the publications whose payload carries one marker, in call order.
         *
         * @param marker the text a payload carries
         * @return the recorded triples that marker selects
         */
        List<PublishedMessage> messagesCarrying(String marker) {
            return messages.stream()
                    .filter(message -> message.payload().contains(marker))
                    .toList();
        }
    }

    /** Blocks its first publication while a competing relay claims another row. */
    static final class BlockingEventPublisher implements EventPublisherPort {

        private final AtomicBoolean blockFirst = new AtomicBoolean(true);
        private final CountDownLatch firstPublishEntered = new CountDownLatch(1);
        private final CountDownLatch releaseFirstPublish = new CountDownLatch(1);
        private final List<PublishedMessage> messages = new CopyOnWriteArrayList<>();

        @Override
        public CompletionStage<Void> publish(String topic, String aggregateId, String payload) {
            messages.add(new PublishedMessage(topic, aggregateId, payload));
            if (!blockFirst.compareAndSet(true, false)) {
                return CompletableFuture.completedFuture(null);
            }
            firstPublishEntered.countDown();
            try {
                if (!releaseFirstPublish.await(5, TimeUnit.SECONDS)) {
                    throw new AssertionError("the competing relay did not finish");
                }
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new AssertionError("the blocked publish was interrupted", interrupted);
            }
            return CompletableFuture.completedFuture(null);
        }
    }

    /**
     * One publication, as {@link EventPublisherPort#publish(String, String, String)} received it.
     *
     * @param topic   the destination topic name
     * @param key     the message key, an eleven-digit account identifier
     * @param payload the event text
     */
    record PublishedMessage(String topic, String key, String payload) {
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

    /** The shared meter holder this service records through. */
    private static ObservabilityConfig.AccountMeters accountMeters() {
        return new ObservabilityConfig().accountMeters(new SimpleMeterRegistry());
    }

}
