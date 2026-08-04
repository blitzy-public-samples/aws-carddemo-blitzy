package com.carddemo.account.outbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.carddemo.account.AccountApplication;
import com.carddemo.account.config.KafkaProducerConfig;
import com.carddemo.account.entity.OutboxEventEntity;
import com.carddemo.account.messaging.EventPublisherPort;
import com.carddemo.account.repository.AbstractAccountPostgresTest;
import com.carddemo.account.repository.OutboxEventRepository;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;

import java.lang.reflect.AnnotatedElement;
import java.lang.reflect.Method;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.data.domain.Limit;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Behaviour of {@link OutboxRelay}: when the sweep runs, how many rows it takes, in what order,
 * what reaches the publisher, how a sent row is marked, and what a refused publish leaves behind.
 *
 * <p>ADDITIVE. The relay has one ancestor construct in the CardDemo source, and it is the one
 * asynchronous handoff there. The paragraph {@code WIRTE-JOBSUB-TDQ} runs from
 * {@code app/cbl/CORPT00C.cbl:L507}, opens at {@code app/cbl/CORPT00C.cbl:L515} under that
 * spelling, and writes at {@code app/cbl/CORPT00C.cbl:L517-L523}: {@code EXEC CICS WRITEQ TD}
 * with {@code QUEUE ('JOBS')} and {@code FROM (JCL-RECORD)}. One record reaches a Customer
 * Information Control System (CICS) transient data queue, and a later reader takes it.
 *
 * <p>The message key is the account identifier, eleven digits wide. The width comes from
 * {@code ACCT-ID PIC 9(11)} at {@code app/cpy/CVACT01Y.cpy:L5} and from {@code KEYS(11 0)} at
 * {@code app/jcl/ACCTFILE.jcl:L40}.
 *
 * <p><b>What the sweep does.</b> {@code OutboxRelay.publishPendingEvents()} carries
 * {@code @Scheduled(fixedDelay = 500)}, reads through
 * {@code findByPublishedFalseOrderByCreatedAtAscEventIdAsc(Limit.of(100))}, publishes each row,
 * then marks it. Ordering therefore runs on {@code created_at} and then on {@code event_id}, and
 * the table carries no other arrival column. The publish sits in a {@code try} block inside the
 * loop over the batch, and a refused row leaves the rows behind it attempted.
 *
 * <p><b>The publisher every test here sees.</b> {@link RecordingEventPublisher} is the
 * {@code @Primary} bean {@link RecordingPublisherConfiguration} installs over the Kafka-backed
 * one. It keeps each topic, key and payload triple in call order, and it refuses a publish on
 * request. No test here starts a broker and none needs a created topic.
 *
 * <p><b>How rows reach the table.</b> {@link AbstractAccountPostgresTest} owns the one PostgreSQL
 * container of the module, and no container appears here. Each writing test below carries
 * {@link Transactional}, calls the sweep directly, and Spring rolls the rows back as the test
 * returns. {@link #theSchedulerPublishesACommittedRow()} is the one test that commits and the one
 * that waits on the scheduler; {@link #removeSeededRows()} deletes what it wrote, in a transaction
 * of its own. Every payload here carries the marker {@value #MARKER}, and every assertion reads
 * the rows and the triples that marker selects.
 *
 * <p><b>What the siblings own.</b> {@code repository/OutboxEventRepositoryTest} asserts the
 * declared method inventory of the repository, its row bound as a parameter, and the absence of a
 * publication mutator. That class also asserts the finder's ordering and its tiebreak at the query
 * level, and the assertions below read the publication sequence.
 * {@code outbox/DeadLetterMetadataTest} asserts the configured topic surface and the dead-letter
 * topic name.
 *
 * <p>{@code card-platform/docs/event-flow.md} draws the path a written row travels.
 * {@code card-platform/docs/decision-log.md} carries the reasoning this file leaves out.
 */
@DisplayName("OutboxRelay, the sweep that publishes outbox_event rows and marks them sent")
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

    @Test
    @DisplayName("The sweep runs on a fixed delay of 500 milliseconds, carried as a number")
    void theSweepRunsOnAFixedDelayOfFiveHundredMilliseconds() {
        Scheduled schedule = sweepMethod().getAnnotation(Scheduled.class);

        assertThat(schedule.fixedDelay())
                .as("fixedDelay of %s, from @Scheduled(fixedDelay = 500) on OutboxRelay",
                        SWEEP_METHOD)
                .isEqualTo(FIXED_DELAY_MS);
        assertThat(schedule.fixedDelayString())
                .as("fixedDelayString of %s, empty while the numeric attribute carries the delay",
                        SWEEP_METHOD)
                .isEmpty();
        assertThat(schedule.timeUnit())
                .as("time unit the delay counts in")
                .isEqualTo(TimeUnit.MILLISECONDS);
    }

    @Test
    @DisplayName("Neither the sweep nor the relay class carries a transaction annotation")
    void neitherTheSweepNorTheRelayCarriesATransactionAnnotation() {
        Method sweep = sweepMethod();

        assertThat(annotationNamesOf(sweep))
                .as("annotations declared on %s", SWEEP_METHOD)
                .doesNotContain(Transactional.class.getSimpleName());
        assertThat(annotationNamesOf(OutboxRelay.class))
                .as("annotations declared on OutboxRelay")
                .doesNotContain(Transactional.class.getSimpleName());
        assertThat(AnnotatedElementUtils.hasAnnotation(sweep, Transactional.class))
                .as("%s carries @Transactional, directly or through a meta-annotation",
                        SWEEP_METHOD)
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
        List<OutboxEventEntity> rows = writeRows(0x00, 5, ACCOUNT_ID_LEADING_ZERO);

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
        OutboxEventEntity third = writeRow(0x30, BASE_INSTANT.plusSeconds(3),
                ACCOUNT_ID_LEADING_ZERO);
        OutboxEventEntity first = writeRow(0x31, BASE_INSTANT.plusSeconds(1),
                ACCOUNT_ID_LEADING_ZERO);
        OutboxEventEntity tiedLower = writeRow(0x32, sharedInstant, ACCOUNT_ID_LEADING_ZERO);
        OutboxEventEntity tiedHigher = writeRow(0x33, sharedInstant, ACCOUNT_ID_LEADING_ZERO);
        OutboxEventEntity last = writeRow(0x34, BASE_INSTANT.plusSeconds(4),
                ACCOUNT_ID_LEADING_ZERO);
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
     * Refuses one publish, reads the row, then accepts a publish and reads the row again.
     *
     * <p>{@code OutboxRelay} writes no retry loop and names no second topic. A refused row keeps
     * {@code published} false and {@code published_at} empty, and the next sweep takes it.
     */
    @Test
    @Transactional
    @DisplayName("A refused publish leaves the row pending, and the next sweep publishes it")
    void aRefusedPublishLeavesTheRowPendingUntilTheNextSweep() {
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
                .as("published column after the next sweep")
                .isTrue();
    }

    /**
     * Refuses every publish of a three-row batch and reads what the sweep attempted.
     *
     * <p>The publish of {@code OutboxRelay.publishPendingEvents()} sits in a {@code try} block
     * inside the loop over the batch.
     */
    @Test
    @Transactional
    @DisplayName("A refused publish leaves the rows behind it in the batch attempted")
    void aRefusedPublishLeavesTheRowsBehindItAttempted() {
        List<OutboxEventEntity> rows = writeRows(0x60, 3, ACCOUNT_ID_LEADING_ZERO);
        publisher.refuseEveryPublish();

        relay.publishPendingEvents();

        assertThat(markedPayloads())
                .as("attempts the sweep made while every publish was refused")
                .containsExactlyElementsOf(payloadsOf(rows));
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

    /**
     * The same sweep under a different value for {@value #TOPIC_PROPERTY}.
     *
     * <p>{@link TestPropertySource} on this class builds a second application context, and the
     * relay reads the topic name from that context.
     */
    @Nested
    @TestPropertySource(properties = TOPIC_PROPERTY + "=" + OVERRIDDEN_TOPIC)
    @DisplayName("The published topic under an overridden property")
    class ConfiguredTopicOverride {

        /**
         * Registers {@link RecordingEventPublisher} in the second application context.
         *
         * <p>A Spring test context takes the configuration classes declared inside its own test
         * class. This declaration inherits the one bean method of
         * {@link RecordingPublisherConfiguration} and registers it under the same name.
         */
        @TestConfiguration
        static class OverridingRecordingPublisherConfiguration
                extends RecordingPublisherConfiguration {
        }

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
                    ACCOUNT_ID_LEADING_ZERO));
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

    /** Sends pending changes to the table and detaches every row, so the next read reaches it. */
    private void flushAndDetach() {
        entityManager.flush();
        entityManager.clear();
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
     * thread-safe list and the refusal flag is volatile. A refused publish is recorded before it
     * throws, so a caller reads what was attempted.
     */
    static final class RecordingEventPublisher implements EventPublisherPort {

        /** Every publication this publisher took, in call order. */
        private final List<PublishedMessage> messages = new CopyOnWriteArrayList<>();

        /** Whether the next publish throws. */
        private volatile boolean refusing;

        @Override
        public void publish(String topic, String aggregateId, String payload) {
            messages.add(new PublishedMessage(topic, aggregateId, payload));
            if (refusing) {
                throw new IllegalStateException("the recording publisher refuses this publish");
            }
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

    /**
     * One publication, as {@link EventPublisherPort#publish(String, String, String)} received it.
     *
     * @param topic   the destination topic name
     * @param key     the message key, an eleven-digit account identifier
     * @param payload the event text
     */
    record PublishedMessage(String topic, String key, String payload) {
    }
}
