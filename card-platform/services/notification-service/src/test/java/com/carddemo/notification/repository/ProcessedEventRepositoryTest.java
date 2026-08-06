package com.carddemo.notification.repository;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.carddemo.notification.entity.ProcessedEventEntity;
import com.carddemo.notification.entity.ProcessedEventEntity.ProcessedEventId;
import java.lang.reflect.Method;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.jpa.test.autoconfigure.TestEntityManager;
import org.springframework.data.repository.ListCrudRepository;

/**
 * Exercises {@link ProcessedEventRepository}, the duplicate-delivery guard of the notification
 * service, against the migrated schema.
 *
 * <p>Two inherited members carry the guard. {@code existsById} reads a marker and {@code save}
 * writes one. Four tests drive both over a live database, and three read the declared surface of
 * the interface through reflection.
 *
 * <p>The two declared native statements, {@code claimEvent} and
 * {@code deleteMarkersProcessedBefore}, are exercised in
 * {@code entity/NotificationEntityPersistenceTest} instead. A native statement names its table
 * unqualified, and only a context that has applied the migration into the named schema resolves
 * that name; this Jakarta Persistence slice resolves mapped entities and not raw SQL.
 *
 * <p>A marker holds a universally unique identifier (UUID), the topic the delivery arrived on, and
 * the instant a listener finished with the event. The identifier and the topic together are the key:
 * {@code src/main/resources/db/migration/V3__processed_event_topic_key.sql} widened it, because four
 * listener groups share this table and three producing services assign identifiers independently, so
 * the same identifier can reach two topics without either producer being at fault. The table
 * {@code processed_event} in {@code src/main/resources/db/migration/V1__schema.sql} keeps the instant
 * to microsecond precision, so every instant below is truncated to microseconds before it is written.
 * A value carrying nanoseconds fails an equality assertion on the digits the column drops.
 *
 * <p>Each test method runs inside a transaction the Jakarta Persistence (JPA) slice rolls back, and
 * each method generates its own identifier. No method reads a row another method wrote, and no
 * method depends on the order the methods run in.
 *
 * <p>Every write here reaches the database before it is read back. {@code save} leaves an insert in
 * the persistence context, so the flush sends it and the clear that follows empties the context.
 * The read then comes from the database.
 *
 * <p>CardDemo detects no duplicate delivery. {@code 2900-WRITE-TRANSACTION-FILE} at
 * {@code app/cbl/CBTRN02C.cbl:L562-L579} writes the transaction record, tests the file status, and
 * on any status other than normal displays an error, dumps the status and performs the abend
 * routine. A replayed daily feed drives that write into a duplicate key and reaches the abend.
 *
 * <p>The additive marker table and the design decisions behind it:
 * {@code card-platform/docs/decision-log.md}.
 *
 * <p>Agent Action Plan section 0.5.1 pins the versions this class runs under:
 * {@code postgres:18.4}, JUnit Jupiter 6.0.3 and Java 25.
 */
@DisplayName("ProcessedEventRepository over the migrated notification schema")
final class ProcessedEventRepositoryTest extends NotificationRepositoryTestSupport {

    /** Instant the first pass over an event records, at the precision its column keeps. */
    private static final Instant FIRST_PASS = Instant.now().truncatedTo(ChronoUnit.MICROS);

    /** Instant a second pass over the same event records, ninety seconds later. */
    private static final Instant SECOND_PASS = FIRST_PASS.plusSeconds(90);

    /** Package prefix of the Spring Data paging, sorting and specification types. */
    private static final String SPRING_DATA_PACKAGE = "org.springframework.data";

    /** Rows one event identifier on one topic occupies, however many deliveries carry it. */
    private static final long ONE_ROW = 1L;

    /** Rows one event identifier occupies when it is claimed on two topics. */
    private static final long TWO_ROWS = 2L;

    /** The topic most markers below record, one of the four this service reads. */
    private static final String MARKER_TOPIC = "transaction.posted";

    /** A second topic this service reads, for the cross-topic assertions. */
    private static final String OTHER_TOPIC = "fraud.assessed";

    /** The guard under test. */
    @Autowired
    private ProcessedEventRepository markers;

    /** Sends pending writes to the database and empties the persistence context. */
    @Autowired
    private TestEntityManager entityManager;

    /**
     * Reports false for an event no marker names, and true once the marker reaches the database.
     *
     * <p>The pair of answers is what a listener reads to tell a first delivery from a repeat.
     */
    @Test
    @DisplayName("existsById reports false before the marker reaches the database and true after")
    void existsByIdReportsFalseBeforeTheMarkerReachesTheDatabaseAndTrueAfter() {
        UUID eventId = UUID.randomUUID();

        assertFalse(markers.existsById(key(eventId, MARKER_TOPIC)),
                "no marker names the event on this topic yet");

        markers.save(new ProcessedEventEntity(eventId, FIRST_PASS, MARKER_TOPIC));
        entityManager.flush();
        entityManager.clear();

        assertTrue(markers.existsById(key(eventId, MARKER_TOPIC)),
                "the marker is in the database");
        assertFalse(markers.existsById(key(eventId, OTHER_TOPIC)),
                "and it says nothing about the same identifier on another topic");
    }

    /**
     * Leaves one row when two saves carry one event identifier, and keeps the later instant.
     *
     * <p>The second save works on a detached marker, so the write goes through the database row the
     * first save left. The empty persistence context then forces the read to load that row, and the
     * instance it returns differs from the one the second save handed back.
     */
    @Test
    @DisplayName("A repeated save of one identifier leaves one row holding the later instant")
    void aRepeatedSaveOfOneEventIdentifierLeavesOneRowCarryingTheLaterInstant() {
        UUID eventId = UUID.randomUUID();

        markers.save(new ProcessedEventEntity(eventId, FIRST_PASS, MARKER_TOPIC));
        entityManager.flush();
        entityManager.clear();

        ProcessedEventEntity secondPass =
                markers.save(new ProcessedEventEntity(eventId, SECOND_PASS, MARKER_TOPIC));
        entityManager.flush();
        entityManager.clear();

        ProcessedEventEntity stored = markers.findById(key(eventId, MARKER_TOPIC)).orElseThrow();
        assertAll(
                () -> assertEquals(ONE_ROW, markers.count(), "two saves, one row"),
                () -> assertNotSame(secondPass, stored, "the read loaded the row"),
                () -> assertEquals(eventId, stored.getEventId(), "the row keeps its identifier"),
                () -> assertEquals(SECOND_PASS, stored.getProcessedAt(),
                        "the later instant stands"));
    }

    /**
     * Inserts a marker into a schema whose read model holds no row, and reads both fields back.
     *
     * <p>{@code processed_event} names no other table in its constraints, so the marker stands on
     * its own. A listener may write the marker before or after the read-model row it guards, inside
     * one transaction. The read model itself belongs to {@code StatementTransactionRepository}.
     */
    @Test
    @DisplayName("A marker inserts on its own, with no read-model row beside it")
    void aMarkerInsertsOnItsOwnWithNoReadModelRowBesideIt() {
        UUID eventId = UUID.randomUUID();

        ProcessedEventEntity written =
                markers.save(new ProcessedEventEntity(eventId, FIRST_PASS, MARKER_TOPIC));
        entityManager.flush();
        entityManager.clear();

        ProcessedEventEntity stored = markers.findById(key(eventId, MARKER_TOPIC)).orElseThrow();
        assertAll(
                () -> assertEquals(ONE_ROW, markers.count(), "the insert stands alone"),
                () -> assertNotSame(written, stored, "the read loaded the row"),
                () -> assertEquals(eventId, stored.getEventId(),
                        "the identifier survives the write"),
                () -> assertEquals(FIRST_PASS, stored.getProcessedAt(),
                        "the instant survives it too"));
    }

    /**
     * Pins the supertype of the interface to the list-based Spring Data
     * create-read-update-delete contract over the marker entity and its composite key.
     *
     * <p>The key holds the {@code eventId} field of the event envelope in
     * {@code card-platform/libs/event-contracts}, which is a {@code java.util.UUID}, and the topic
     * the delivery arrived on. A repository parameterised with the identifier alone would let a
     * caller look a marker up without naming the topic, which is the read that made a cross-topic
     * collision look like a duplicate.
     */
    @Test
    @DisplayName("The interface is parameterised with the marker entity and its composite key")
    void theInterfaceIsParameterisedWithTheMarkerEntityAndItsCompositeKey() {
        Type[] supertypes = ProcessedEventRepository.class.getGenericInterfaces();
        assertEquals(1, supertypes.length, "the interface extends one supertype");

        ParameterizedType crudContract = (ParameterizedType) supertypes[0];
        Type[] arguments = crudContract.getActualTypeArguments();
        assertAll(
                () -> assertSame(ListCrudRepository.class, crudContract.getRawType(),
                        "the list-based create-read-update-delete contract"),
                () -> assertEquals(2, arguments.length, "two type arguments"),
                () -> assertSame(ProcessedEventEntity.class, arguments[0], "the marker entity"),
                () -> assertSame(ProcessedEventId.class, arguments[1], "its composite key"));
    }

    /**
     * Resolves the four inherited members these tests call, and shows the interface redeclares none
     * of them.
     *
     * <p>Every answer the three database tests above assert comes from the inherited contract.
     */
    @Test
    @DisplayName("existsById, save, count and findById resolve on the interface and are inherited")
    void existsByIdSaveCountAndFindByIdResolveOnTheInterfaceAndAreInherited() {
        Class<ProcessedEventRepository> contract = ProcessedEventRepository.class;
        assertAll(
                () -> assertNotNull(contract.getMethod("existsById", Object.class),
                        "existsById resolves"),
                () -> assertNotNull(contract.getMethod("save", Object.class), "save resolves"),
                () -> assertNotNull(contract.getMethod("count"), "count resolves"),
                () -> assertNotNull(contract.getMethod("findById", Object.class),
                        "findById resolves"),
                () -> assertFalse(declaresMethodNamed("existsById"), "existsById is inherited"),
                () -> assertFalse(declaresMethodNamed("save"), "save is inherited"),
                () -> assertFalse(declaresMethodNamed("count"), "count is inherited"),
                () -> assertFalse(declaresMethodNamed("findById"), "findById is inherited"));
    }

    /**
     * Keeps the declared surface of the interface clear of Spring Data query types and of nested
     * types.
     *
     * <p>Every declared parameter and every declared return type sits outside the Spring Data
     * packages, which covers the paging, sorting and specification families in one assertion. The
     * interface nests no type, so it declares no projection either. An edit that adds a paging
     * parameter, a specification parameter or a nested projection fails here.
     */
    @Test
    @DisplayName("No declared member takes or returns a Spring Data type, and none is nested")
    void noDeclaredMemberTakesOrReturnsASpringDataTypeAndNoneIsNested() {
        for (Method declared : ProcessedEventRepository.class.getDeclaredMethods()) {
            for (Class<?> parameterType : declared.getParameterTypes()) {
                assertFalse(isSpringDataType(parameterType),
                        declared.getName() + " takes " + parameterType.getName());
            }
            assertFalse(isSpringDataType(declared.getReturnType()),
                    declared.getName() + " returns " + declared.getReturnType().getName());
        }

        assertEquals(0, ProcessedEventRepository.class.getDeclaredClasses().length,
                "the interface nests no type");
    }

    /**
     * Holds that one identifier claimed on two topics occupies two rows.
     *
     * <p>This is the property the single-column key denied. Two producing services assign event
     * identifiers independently, so a {@code FraudFlagged} event and a {@code TransactionPosted}
     * event may carry the same one. Both are real events and both have work to do, so both must be
     * claimable.
     */
    @Test
    @DisplayName("One identifier on two topics occupies two rows")
    void oneIdentifierOnTwoTopicsOccupiesTwoRows() {
        UUID eventId = UUID.randomUUID();

        markers.save(new ProcessedEventEntity(eventId, FIRST_PASS, MARKER_TOPIC));
        markers.save(new ProcessedEventEntity(eventId, SECOND_PASS, OTHER_TOPIC));
        entityManager.flush();
        entityManager.clear();

        assertAll(
                () -> assertEquals(TWO_ROWS, markers.count(), "one row per topic"),
                () -> assertEquals(FIRST_PASS,
                        markers.findById(key(eventId, MARKER_TOPIC)).orElseThrow()
                                .getProcessedAt(),
                        "each row keeps its own instant"),
                () -> assertEquals(SECOND_PASS,
                        markers.findById(key(eventId, OTHER_TOPIC)).orElseThrow()
                                .getProcessedAt(),
                        "and neither row overwrote the other"));
    }

    /**
     * Builds the composite key of one marker.
     *
     * @param eventId       the event identifier
     * @param consumedTopic the topic the delivery arrived on
     * @return the key both columns of {@code pk_processed_event} carry
     */
    private static ProcessedEventId key(UUID eventId, String consumedTopic) {
        return new ProcessedEventId(eventId, consumedTopic);
    }

    /**
     * Reports whether the interface itself declares a method under the given name.
     *
     * @param name the method name to look for
     * @return {@code true} when the interface declares it, {@code false} when it inherits it
     */
    private static boolean declaresMethodNamed(String name) {
        for (Method declared : ProcessedEventRepository.class.getDeclaredMethods()) {
            if (declared.getName().equals(name)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Reports whether a type comes from the Spring Data packages.
     *
     * @param candidate the type to classify, primitive types included
     * @return {@code true} when its package starts with the Spring Data prefix
     */
    private static boolean isSpringDataType(Class<?> candidate) {
        return candidate.getPackageName().startsWith(SPRING_DATA_PACKAGE);
    }
}
