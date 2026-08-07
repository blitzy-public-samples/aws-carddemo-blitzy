package com.carddemo.account.repository;

import com.carddemo.account.entity.ProcessedEventEntity;
import com.carddemo.account.entity.ProcessedEventEntity.ProcessedEventId;
import com.carddemo.account.entity.OutboxEventEntity;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.metamodel.Attribute;
import jakarta.persistence.metamodel.EntityType;

import java.lang.reflect.Method;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import java.util.stream.Collectors;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.repository.Repository;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * Tests {@link ProcessedEventRepository} against the migrated {@code processed_event} table.
 *
 * <p>No COBOL ancestor. No copybook and no program declares this marker.
 * {@code app/cbl/CBTRN02C.cbl:L562-L579} writes each posted transaction in the paragraph
 * {@code 2900-WRITE-TRANSACTION-FILE}, performs no duplicate check, and routes every file status
 * other than {@code '00'} to {@code 9999-ABEND-PROGRAM}.
 *
 * <p>The account service registers no listener, so no component writes a marker here today. The
 * tests below cover the operations a listener added to this service would call: existence,
 * atomic claim, write and retention purge.
 *
 * <p>Three tests reach no row. Two read the declared method surface and the identifier type
 * argument, a Universally Unique Identifier (UUID), by reflection. One reads the Jakarta Persistence
 * metamodel for the persistent attributes. A method or an attribute added later makes one of the
 * three fail, which keeps the addition deliberate.
 *
 * <p>The simultaneous-claim test opens two transactions and writes one outbox side effect.
 * Its cleanup removes both committed rows. Other writing tests carry {@code @Transactional}
 * and roll back as they return. {@link AbstractAccountPostgresTest} owns the PostgreSQL
 * container and Spring context.
 *
 * <p>{@code card-platform/docs/decision-log.md} records the idempotent-consumer decision,
 * and {@code card-platform/docs/traceability-matrix.md} carries the mapping.
 * {@code card-platform/docs/event-flow.md} draws the publish and consume paths.
 */
@DisplayName("ProcessedEventRepository over the migrated processed_event table")
class ProcessedEventRepositoryTest extends AbstractAccountPostgresTest {

    /** Character count of the canonical text form of a UUID. */
    private static final int EVENT_ID_TEXT_LENGTH = 36;

    /** Widest gap two timestamps may show and still count as one instant, in microseconds. */
    private static final long MICROSECOND_TOLERANCE = 1L;

    /** Identifier the existence check reads while no row carries it. */
    private static final UUID ABSENT_EVENT_ID =
            UUID.fromString("0a2b4c6d-8e0f-4a2b-9c4d-6e0f2a4b6c8d");

    /** Identifier of the marker the existence check reads after a write. */
    private static final UUID SAVED_EVENT_ID =
            UUID.fromString("1c3e5a7b-2d4f-4a6c-8b0d-3e5f7a9b1c2d");

    /** Identifier of the marker the round-trip test reads back. */
    private static final UUID READ_BACK_EVENT_ID =
            UUID.fromString("2d4f6b8a-3c5e-4b7d-9a1c-4e6f8b0d2a3c");

    /** Identifier both simultaneous deliveries try to claim. */
    private static final UUID REPLAYED_EVENT_ID =
            UUID.fromString("3e5a7c9b-4d6f-4c8a-b0d2-5f7a9c1e3b4d");

    /** Identifier of the marker the purge removes. */
    private static final UUID OLDER_EVENT_ID =
            UUID.fromString("4f6b8d0a-5e7c-4d9b-a1c3-6a8c0e2f4b5d");

    /** Identifier of the marker the purge keeps. */
    private static final UUID NEWER_EVENT_ID =
            UUID.fromString("5a7c9e0b-6f8d-4e0c-b2d4-7a9c1e3f5b6d");

    /**
     * Processing time each single-marker test writes. Six fractional digits match the precision the
     * column holds.
     */
    private static final Instant MARKER_PROCESSED_AT = Instant.parse("2026-02-17T09:41:22.123456Z");

    /** Topic recorded on the marker claimed by the concurrent delivery. */
    private static final String CONSUMED_TOPIC = "account.state-changed";

    /**
     * The topic this service's own listener reads, and the topic every marker below is keyed on
     * unless a test names {@link #CONSUMED_TOPIC} deliberately.
     *
     * <p>{@code messaging/TransactionPostedConsumer} subscribes to it, so it is the value a real
     * marker of this schema carries.
     */
    private static final String POSTED_TOPIC = "transaction.posted";

    /** Identifier of the one outbox side effect the winning claim writes. */
    private static final UUID SIDE_EFFECT_EVENT_ID =
            UUID.fromString("6b8d0f2a-7c9e-4f1b-a3d5-8c0e2f4b6d7a");

    /** Account identifier carried by the side-effect row. */
    private static final String SIDE_EFFECT_ACCOUNT_ID = "00000000042";

    /** Event type carried by the side-effect row. */
    private static final String SIDE_EFFECT_EVENT_TYPE = "AccountStateChanged";

    /** Processing time of the marker that sits before the purge horizon. */
    private static final Instant OLDER_PROCESSED_AT = Instant.parse("2026-01-04T00:00:00.000000Z");

    /** Processing time of the marker that sits after the purge horizon. */
    private static final Instant NEWER_PROCESSED_AT = Instant.parse("2026-03-04T00:00:00.000000Z");

    /** Horizon the purge ranges below, sitting between the two written processing times. */
    private static final Instant PURGE_HORIZON = Instant.parse("2026-02-04T00:00:00.000000Z");

    /** The interface under test, discovered from the {@code com.carddemo.account} scan root. */
    @Autowired
    private ProcessedEventRepository repository;

    /** Store of the business side effect written by the winning claim. */
    @Autowired
    private OutboxEventRepository outboxEvents;

    /** Opens the two independent claim transactions. */
    @Autowired
    private PlatformTransactionManager transactionManager;

    /**
     * Flushes a write to the row, empties the persistence context, and counts rows. The metamodel
     * assertions read it too.
     */
    @PersistenceContext
    private EntityManager entityManager;

    /**
     * Removes committed rows written by the concurrent claim test.
     *
     * <p>The delete reaches the identifier through the embedded key, so it removes the marker
     * whichever topic keyed it. {@code src/main/resources/db/migration/}
     * {@code V6__processed_event_topic_key.sql} made the consumed topic half of that key.
     */
    @AfterEach
    void removeConcurrentClaimRows() {
        inTransaction(() -> {
            outboxEvents.deleteById(SIDE_EFFECT_EVENT_ID);
            entityManager.createQuery(
                            "DELETE FROM ProcessedEventEntity marker "
                                    + "WHERE marker.id.eventId = :eventId")
                    .setParameter("eventId", REPLAYED_EVENT_ID)
                    .executeUpdate();
            return null;
        });
    }

    @Test
    @DisplayName("the interface declares existsById, save, claimEvent and "
            + "deleteMarkersProcessedBefore and no other method")
    void theInterfaceDeclaresFourMethodsAndNoOther() {
        List<String> declared = Arrays.stream(ProcessedEventRepository.class.getDeclaredMethods())
                .filter(method -> !method.isSynthetic())
                .map(ProcessedEventRepositoryTest::signatureOf)
                .sorted()
                .toList();

        assertThat(declared).containsExactly(
                "claimEvent(java.util.UUID,java.time.Instant,java.lang.String):int",
                "deleteMarkersProcessedBefore(java.time.Instant):int",
                "existsById(com.carddemo.account.entity.ProcessedEventEntity$ProcessedEventId)"
                        + ":boolean",
                "save(com.carddemo.account.entity.ProcessedEventEntity)"
                        + ":com.carddemo.account.entity.ProcessedEventEntity");
    }

    /**
     * Asserts the repository is keyed on the composite identifier rather than on the raw
     * {@link UUID}.
     *
     * <p>{@code src/main/resources/db/migration/V6__processed_event_topic_key.sql} made the consumed
     * topic half of the primary key, because a delivery is identified by its event and the stream it
     * arrived on. The type argument is what forces every caller to supply both halves: a repository
     * still keyed on {@code UUID} would compile against a two-column key and fail at runtime.
     */
    @Test
    @DisplayName("the repository identifier type argument is the composite ProcessedEventId")
    void theRepositoryIdentifierTypeArgumentIsUuid() {
        Type[] extended = ProcessedEventRepository.class.getGenericInterfaces();

        assertThat(extended).hasSize(1);
        assertThat(extended[0]).isInstanceOf(ParameterizedType.class);

        ParameterizedType base = (ParameterizedType) extended[0];
        assertThat(base.getRawType()).isEqualTo(Repository.class);
        assertThat(base.getActualTypeArguments())
                .containsExactly(ProcessedEventEntity.class, ProcessedEventId.class);
    }

    /**
     * Asserts the mapping carries the three marker columns, with two of them inside the embedded
     * key.
     *
     * <p>The entity declares {@code id} and {@code processedAt}. The key parts {@code eventId} and
     * {@code consumedTopic} are attributes of the embeddable, so the three columns
     * {@code V1__schema.sql} declares are all still mapped and the key names two of them.
     */
    @Test
    @DisplayName("the Jakarta Persistence metamodel reports the embedded key and the processing "
            + "time, and keys the marker on the event and the topic")
    void theMetamodelReportsThreePersistentAttributes() {
        EntityType<ProcessedEventEntity> marker =
                entityManager.getMetamodel().entity(ProcessedEventEntity.class);

        List<String> attributes = marker.getAttributes().stream()
                .map(Attribute::getName)
                .sorted()
                .toList();

        List<String> keyParts = entityManager.getMetamodel()
                .embeddable(ProcessedEventId.class).getAttributes().stream()
                .map(Attribute::getName)
                .sorted()
                .toList();

        assertThat(attributes).containsExactly("id", "processedAt");
        assertThat(keyParts).containsExactly("consumedTopic", "eventId");
        assertThat(marker.getIdType().getJavaType()).isEqualTo(ProcessedEventId.class);
        assertThat(marker.getId(ProcessedEventId.class).getName()).isEqualTo("id");
        assertThat(marker.getSingularAttribute("processedAt").getJavaType())
                .isEqualTo(Instant.class);
    }

    @Test
    @DisplayName("existsById reads false for a key no row carries")
    void existsByIdReadsFalseForAnIdentifierNoRowCarries() {
        assertThat(repository.existsById(new ProcessedEventId(ABSENT_EVENT_ID, POSTED_TOPIC)))
                .isFalse();
    }

    @Test
    @Transactional
    @DisplayName("existsById reads true once one marker is saved and flushed")
    void existsByIdReadsTrueOnceOneMarkerIsSavedAndFlushed() {
        repository.save(
                new ProcessedEventEntity(SAVED_EVENT_ID, MARKER_PROCESSED_AT, POSTED_TOPIC));
        entityManager.flush();

        assertThat(repository.existsById(new ProcessedEventId(SAVED_EVENT_ID, POSTED_TOPIC)))
                .withFailMessage("existsById found no marker for the key a flushed row carries")
                .isTrue();
    }

    /**
     * Asserts a marker answers for the topic it was written on and for no other.
     *
     * <p>This is the property {@code V6__processed_event_topic_key.sql} exists for, read through the
     * guard a consumer actually calls. Two producing services assign event identifiers
     * independently, so one identifier can arrive on two topics carrying two different events. Keyed
     * on the identifier alone, the second consumer read the first consumer's marker, concluded it had
     * already handled the event, and applied nothing: right for a redelivery, and a silently dropped
     * effect for a different event.
     */
    @Test
    @Transactional
    @DisplayName("a marker answers for its own topic and for no other")
    void aMarkerAnswersForItsOwnTopicAndNoOther() {
        repository.save(
                new ProcessedEventEntity(SAVED_EVENT_ID, MARKER_PROCESSED_AT, POSTED_TOPIC));
        entityManager.flush();

        assertThat(repository.existsById(new ProcessedEventId(SAVED_EVENT_ID, CONSUMED_TOPIC)))
                .withFailMessage("the marker of one topic answered for another, which is how a "
                        + "different event carrying the same identifier lost its effect")
                .isFalse();
    }

    /**
     * Asserts one identifier is claimable once per topic rather than once per service.
     *
     * <p>Both claims below carry the same event identifier and different topics, and both must
     * succeed. Under the narrow key the second returned zero, and its caller skipped its side
     * effects and acknowledged.
     */
    @Test
    @Transactional
    @DisplayName("claimEvent takes one identifier once per topic, not once per service")
    void claimEventTakesOneIdentifierOncePerTopic() {
        int onPostedTopic =
                repository.claimEvent(SAVED_EVENT_ID, MARKER_PROCESSED_AT, POSTED_TOPIC);
        int onStateChangedTopic =
                repository.claimEvent(SAVED_EVENT_ID, MARKER_PROCESSED_AT, CONSUMED_TOPIC);
        int repeatOnPostedTopic =
                repository.claimEvent(SAVED_EVENT_ID, MARKER_PROCESSED_AT, POSTED_TOPIC);

        assertThat(onPostedTopic).isEqualTo(1);
        assertThat(onStateChangedTopic)
                .withFailMessage("a different event sharing an identifier lost its claim to the "
                        + "first, which is the defect the composite key removes")
                .isEqualTo(1);
        assertThat(repeatOnPostedTopic)
                .withFailMessage("a redelivery on one topic must still be suppressed")
                .isZero();
        assertThat(markersCarrying(SAVED_EVENT_ID)).isEqualTo(2L);
    }

    @Test
    @Transactional
    @DisplayName("the saved marker reads back with its identifier and its processing time intact")
    void theSavedMarkerReadsBackWithItsIdentifierAndProcessingTimeIntact() {
        repository.save(
                new ProcessedEventEntity(READ_BACK_EVENT_ID, MARKER_PROCESSED_AT, POSTED_TOPIC));
        entityManager.flush();
        entityManager.clear();

        ProcessedEventEntity reloaded = entityManager.find(ProcessedEventEntity.class,
                new ProcessedEventId(READ_BACK_EVENT_ID, POSTED_TOPIC));

        assertThat(reloaded).isNotNull();
        assertThat(reloaded.getEventId()).isEqualTo(READ_BACK_EVENT_ID);
        assertThat(reloaded.getConsumedTopic()).isEqualTo(POSTED_TOPIC);
        assertThat(reloaded.getEventId().toString())
                .isEqualTo(READ_BACK_EVENT_ID.toString())
                .hasSize(EVENT_ID_TEXT_LENGTH);
        assertThat(reloaded.getProcessedAt())
                .isCloseTo(MARKER_PROCESSED_AT, within(MICROSECOND_TOLERANCE, ChronoUnit.MICROS));
    }

    @Test
    @DisplayName("simultaneous claimEvent calls leave one marker and one business side effect")
    void simultaneousClaimsLeaveOneMarkerAndOneSideEffect() throws Exception {
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService workers = Executors.newFixedThreadPool(2);
        try {
            Future<Integer> first =
                    workers.submit(() -> claimWithSideEffect(ready, start));
            Future<Integer> second =
                    workers.submit(() -> claimWithSideEffect(ready, start));

            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            start.countDown();

            assertThat(List.of(first.get(5, TimeUnit.SECONDS),
                    second.get(5, TimeUnit.SECONDS)))
                    .containsExactlyInAnyOrder(0, 1);
        } finally {
            start.countDown();
            workers.shutdownNow();
        }

        assertThat(markersCarrying(REPLAYED_EVENT_ID)).isEqualTo(1L);
        assertThat(outboxEvents.findById(SIDE_EFFECT_EVENT_ID)).isPresent();
    }

    @Test
    @Transactional
    @DisplayName("deleteMarkersProcessedBefore removes the earlier marker and keeps the later one")
    void deleteMarkersProcessedBeforeRemovesTheEarlierMarker() {
        repository.save(
                new ProcessedEventEntity(OLDER_EVENT_ID, OLDER_PROCESSED_AT, POSTED_TOPIC));
        repository.save(
                new ProcessedEventEntity(NEWER_EVENT_ID, NEWER_PROCESSED_AT, POSTED_TOPIC));
        entityManager.flush();

        int removed = repository.deleteMarkersProcessedBefore(PURGE_HORIZON);
        entityManager.clear();

        assertThat(removed).isEqualTo(1);
        assertThat(markersCarrying(OLDER_EVENT_ID)).isZero();
        assertThat(markersCarrying(NEWER_EVENT_ID)).isEqualTo(1L);
    }

    /**
     * Counts the rows carrying one event identifier.
     *
     * <p>The count reaches through the embedded key, so it counts a row per topic. One identifier
     * carries at most one row per topic since
     * {@code src/main/resources/db/migration/V6__processed_event_topic_key.sql}.
     *
     * @param eventId the identifier to count rows for
     * @return how many rows carry the identifier, across every topic
     */
    private long markersCarrying(UUID eventId) {
        return entityManager.createQuery(
                        "SELECT COUNT(marker) FROM ProcessedEventEntity marker "
                                + "WHERE marker.id.eventId = :eventId", Long.class)
                .setParameter("eventId", eventId)
                .getSingleResult();
    }

    /** Claims the shared event and writes one outbox row only for the winning transaction. */
    private int claimWithSideEffect(CountDownLatch ready, CountDownLatch start) {
        ready.countDown();
        try {
            if (!start.await(5, TimeUnit.SECONDS)) {
                throw new AssertionError("the simultaneous claim did not start");
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new AssertionError("the simultaneous claim was interrupted", interrupted);
        }

        return inTransaction(() -> {
            int claimed = repository.claimEvent(
                    REPLAYED_EVENT_ID, MARKER_PROCESSED_AT, CONSUMED_TOPIC);
            if (claimed == 1) {
                outboxEvents.save(new OutboxEventEntity(
                        SIDE_EFFECT_EVENT_ID, SIDE_EFFECT_EVENT_TYPE, "{}",
                        SIDE_EFFECT_ACCOUNT_ID, MARKER_PROCESSED_AT));
            }
            return claimed;
        });
    }

    /** Runs one callback in a new transaction. */
    private <T> T inTransaction(Supplier<T> callback) {
        TransactionTemplate transaction = new TransactionTemplate(transactionManager);
        transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        return transaction.execute(status -> callback.get());
    }

    /**
     * Renders one declared method as {@code name(parameterType,...):returnType}, with every type
     * spelled in full.
     *
     * @param method a method the interface declares
     * @return the rendered signature
     */
    private static String signatureOf(Method method) {
        return Arrays.stream(method.getParameterTypes())
                .map(Class::getName)
                .collect(Collectors.joining(",", method.getName() + "(", "):"))
                + method.getReturnType().getName();
    }
}
