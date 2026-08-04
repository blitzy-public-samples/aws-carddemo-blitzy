package com.carddemo.account.repository;

import com.carddemo.account.entity.ProcessedEventEntity;

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
import java.util.stream.Collectors;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.repository.Repository;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * Tests {@link ProcessedEventRepository} against the migrated {@code processed_event} table.
 *
 * <p>ADDITIVE. No copybook and no program declares this marker.
 * {@code app/cbl/CBTRN02C.cbl:L562-L579} writes each posted transaction in the paragraph
 * {@code 2900-WRITE-TRANSACTION-FILE}, performs no duplicate check, and routes every file status
 * other than {@code '00'} to {@code 9999-ABEND-PROGRAM}.
 *
 * <p>The account service registers no listener, so no component writes a marker here today. The
 * tests below cover the three operations a listener added to this service would call: the existence
 * check, the write, and the retention purge.
 *
 * <p>Three tests reach no row. Two read the declared method surface and the identifier type
 * argument, a Universally Unique Identifier (UUID), by reflection. One reads the Jakarta Persistence
 * metamodel for the persistent attributes. A method or an attribute added later makes one of the
 * three fail, which keeps the addition deliberate.
 *
 * <p>Five tests reach the table, and the four that write carry {@code @Transactional}. Spring rolls
 * those rows back, so the table returns to the state the migrations leave.
 * {@link AbstractAccountPostgresTest} owns the one PostgreSQL container and the Spring context, and
 * this class declares neither.
 *
 * <p>{@code card-platform/docs/decision-log.md} (planned) records the idempotent-consumer decision,
 * and {@code card-platform/docs/traceability-matrix.md} (planned) carries the mapping.
 * {@code card-platform/docs/event-flow.md} (planned) draws the publish and consume paths.
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

    /** Identifier the duplicate-write test saves twice. */
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

    /** Processing time the second write of one identifier carries. */
    private static final Instant REPLAY_PROCESSED_AT = Instant.parse("2026-02-17T11:07:33.654321Z");

    /** Processing time of the marker that sits before the purge horizon. */
    private static final Instant OLDER_PROCESSED_AT = Instant.parse("2026-01-04T00:00:00.000000Z");

    /** Processing time of the marker that sits after the purge horizon. */
    private static final Instant NEWER_PROCESSED_AT = Instant.parse("2026-03-04T00:00:00.000000Z");

    /** Horizon the purge ranges below, sitting between the two written processing times. */
    private static final Instant PURGE_HORIZON = Instant.parse("2026-02-04T00:00:00.000000Z");

    /** The interface under test, discovered from the {@code com.carddemo.account} scan root. */
    @Autowired
    private ProcessedEventRepository repository;

    /**
     * Flushes a write to the row, empties the persistence context, and counts rows. The metamodel
     * assertions read it too.
     */
    @PersistenceContext
    private EntityManager entityManager;

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
                "existsById(java.util.UUID):boolean",
                "save(com.carddemo.account.entity.ProcessedEventEntity)"
                        + ":com.carddemo.account.entity.ProcessedEventEntity");
    }

    @Test
    @DisplayName("the repository identifier type argument is java.util.UUID")
    void theRepositoryIdentifierTypeArgumentIsUuid() {
        Type[] extended = ProcessedEventRepository.class.getGenericInterfaces();

        assertThat(extended).hasSize(1);
        assertThat(extended[0]).isInstanceOf(ParameterizedType.class);

        ParameterizedType base = (ParameterizedType) extended[0];
        assertThat(base.getRawType()).isEqualTo(Repository.class);
        assertThat(base.getActualTypeArguments())
                .containsExactly(ProcessedEventEntity.class, UUID.class);
    }

    @Test
    @DisplayName("the Jakarta Persistence metamodel reports three persistent attributes and "
            + "the event identifier as the primary key")
    void theMetamodelReportsThreePersistentAttributes() {
        EntityType<ProcessedEventEntity> marker =
                entityManager.getMetamodel().entity(ProcessedEventEntity.class);

        List<String> attributes = marker.getAttributes().stream()
                .map(Attribute::getName)
                .sorted()
                .toList();

        assertThat(attributes).containsExactly("consumedTopic", "eventId", "processedAt");
        assertThat(marker.getIdType().getJavaType()).isEqualTo(UUID.class);
        assertThat(marker.getId(UUID.class).getName()).isEqualTo("eventId");
        assertThat(marker.getSingularAttribute("processedAt").getJavaType())
                .isEqualTo(Instant.class);
    }

    @Test
    @DisplayName("existsById reads false for an identifier no row carries")
    void existsByIdReadsFalseForAnIdentifierNoRowCarries() {
        assertThat(repository.existsById(ABSENT_EVENT_ID)).isFalse();
    }

    @Test
    @Transactional
    @DisplayName("existsById reads true once one marker is saved and flushed")
    void existsByIdReadsTrueOnceOneMarkerIsSavedAndFlushed() {
        repository.save(new ProcessedEventEntity(SAVED_EVENT_ID, MARKER_PROCESSED_AT));
        entityManager.flush();

        assertThat(repository.existsById(SAVED_EVENT_ID))
                .withFailMessage("existsById found no marker for the identifier a flushed row "
                        + "carries")
                .isTrue();
    }

    @Test
    @Transactional
    @DisplayName("the saved marker reads back with its identifier and its processing time intact")
    void theSavedMarkerReadsBackWithItsIdentifierAndProcessingTimeIntact() {
        repository.save(new ProcessedEventEntity(READ_BACK_EVENT_ID, MARKER_PROCESSED_AT));
        entityManager.flush();
        entityManager.clear();

        ProcessedEventEntity reloaded =
                entityManager.find(ProcessedEventEntity.class, READ_BACK_EVENT_ID);

        assertThat(reloaded).isNotNull();
        assertThat(reloaded.getEventId()).isEqualTo(READ_BACK_EVENT_ID);
        assertThat(reloaded.getEventId().toString())
                .isEqualTo(READ_BACK_EVENT_ID.toString())
                .hasSize(EVENT_ID_TEXT_LENGTH);
        assertThat(reloaded.getProcessedAt())
                .isCloseTo(MARKER_PROCESSED_AT, within(MICROSECOND_TOLERANCE, ChronoUnit.MICROS));
    }

    @Test
    @Transactional
    @DisplayName("saving one identifier twice leaves one row")
    void savingOneIdentifierTwiceLeavesOneRow() {
        repository.save(new ProcessedEventEntity(REPLAYED_EVENT_ID, MARKER_PROCESSED_AT));
        entityManager.flush();

        repository.save(new ProcessedEventEntity(REPLAYED_EVENT_ID, REPLAY_PROCESSED_AT));
        entityManager.flush();

        assertThat(markersCarrying(REPLAYED_EVENT_ID))
                .withFailMessage("a second write of one event identifier left more than one row")
                .isEqualTo(1L);
    }

    @Test
    @Transactional
    @DisplayName("deleteMarkersProcessedBefore removes the earlier marker and keeps the later one")
    void deleteMarkersProcessedBeforeRemovesTheEarlierMarker() {
        repository.save(new ProcessedEventEntity(OLDER_EVENT_ID, OLDER_PROCESSED_AT));
        repository.save(new ProcessedEventEntity(NEWER_EVENT_ID, NEWER_PROCESSED_AT));
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
     * @param eventId the identifier to count rows for
     * @return how many rows carry the identifier, {@code 0} or {@code 1}
     */
    private long markersCarrying(UUID eventId) {
        return entityManager.createQuery(
                        "SELECT COUNT(marker) FROM ProcessedEventEntity marker "
                                + "WHERE marker.eventId = :eventId", Long.class)
                .setParameter("eventId", eventId)
                .getSingleResult();
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
