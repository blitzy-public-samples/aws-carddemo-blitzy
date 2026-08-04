package com.carddemo.account.repository;

import com.carddemo.account.entity.OutboxEventEntity;

import jakarta.persistence.EntityManager;
import jakarta.persistence.Id;
import jakarta.persistence.PersistenceContext;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Limit;
import org.springframework.data.repository.ListCrudRepository;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Reads the declared shape of {@link OutboxEventRepository}, then the behaviour of its pending
 * finder against rows written to {@code outbox_event}.
 *
 * <p>ADDITIVE. This class has no COBOL ancestor. No copybook and no program of the CardDemo source
 * declares an outbox record. The source carries one asynchronous handoff, the Customer Information
 * Control System (CICS) command {@code EXEC CICS WRITEQ TD} on {@code QUEUE ('JOBS')} at
 * {@code app/cbl/CORPT00C.cbl:L517-L518}. Every row below carries the account identifier
 * {@code ACCT-ID PIC 9(11)} at {@code app/cpy/CVACT01Y.cpy:L5}.
 *
 * <p>Reflection over the interface supplies four properties: the declared method inventory, the row
 * bound as a parameter, the absence of a publication mutator, and the two type arguments. Rows
 * written through the inherited {@code save} supply eight more. They cover the state an insert
 * leaves, the values it round-trips, the publication filter, the ordering, the tiebreak, both
 * bounds, and the empty result.
 *
 * <p>{@link AbstractAccountPostgresTest} owns the one PostgreSQL container of the module, so no
 * container appears here. Each test that writes carries {@link Transactional}, and Spring rolls
 * the insert back as the test returns.
 *
 * <p>{@code card-platform/docs/event-flow.md} draws the path a written row travels.
 * {@code card-platform/docs/decision-log.md} carries the reasoning this file leaves out.
 */
@DisplayName("OutboxEventRepository, the declared contract and the pending finder")
class OutboxEventRepositoryTest extends AbstractAccountPostgresTest {

    /** The one derived finder of the interface, named once for the reflection tests below. */
    private static final String PENDING_FINDER = "findByPublishedFalseOrderByCreatedAtAscEventIdAsc";

    /** The one event type this module writes, within {@link OutboxEventEntity#EVENT_TYPE_MAX_LENGTH}. */
    private static final String EVENT_TYPE = "AccountStateChanged";

    /** An opaque payload, and the shortest text the column accepts. */
    private static final String PAYLOAD = "{}";

    /**
     * The account every row here belongs to, eleven digits with its leading zero kept.
     */
    private static final String ACCOUNT_ID = "00000000011";

    /**
     * Text form of every identifier this class writes, completed by two hexadecimal digits. The
     * leading bytes are equal across the rows, and the final two digits carry the whole difference.
     */
    private static final String EVENT_ID_STEM = "00000000-0000-4000-8000-0000000000";

    /** Characters in the text form of an event identifier. */
    private static final int EVENT_ID_TEXT_LENGTH = 36;

    /** The instant every row measures from, at a whole second. */
    private static final Instant BASE_INSTANT = Instant.parse("2026-01-01T00:00:00Z");

    /** Rows the bounded read asks for, out of the five {@link #writeFivePendingRows()} writes. */
    private static final int BOUNDED_ROWS = 2;

    /** The interface under test, discovered from the scan root {@code com.carddemo.account}. */
    @Autowired
    private OutboxEventRepository outboxEvents;

    /** The flush path. The inherited interface declares no flush method. */
    @PersistenceContext
    private EntityManager entityManager;

    @Test
    @DisplayName("The interface declares exactly the five methods named here")
    void declaredMethodInventoryHoldsFiveNames() {
        List<String> declared = Arrays.stream(OutboxEventRepository.class.getDeclaredMethods())
                .filter(method -> !method.isSynthetic())
                .map(Method::getName)
                .sorted()
                .toList();

        assertThat(declared).containsExactly(
                "claimDueRows",
                "claimPendingBatch",
                "deletePublishedBefore",
                PENDING_FINDER,
                "findByRelayStateAndClaimedAtBeforeOrderByClaimedAtAsc");
    }

    @Test
    @DisplayName("The pending finder takes its row bound as a parameter and returns a row list")
    void pendingFinderTakesItsBoundAsAParameter() throws NoSuchMethodException {
        Method finder = OutboxEventRepository.class.getDeclaredMethod(PENDING_FINDER, Limit.class);

        assertThat(finder.getParameterTypes()).containsExactly(Limit.class);
        assertThat(finder.getReturnType()).isEqualTo(List.class);
        assertThat(elementTypeOf(finder)).isEqualTo(OutboxEventEntity.class);
    }

    @Test
    @DisplayName("No method of the interface marks a row published, and the entity carries that move")
    void publicationIsNotARepositoryMutator() throws NoSuchMethodException {
        List<String> mutators = Arrays.stream(OutboxEventRepository.class.getDeclaredMethods())
                .map(Method::getName)
                .filter(name -> name.startsWith("mark") || name.startsWith("set")
                        || name.startsWith("update"))
                .toList();

        assertThat(mutators).isEmpty();

        Method transition = OutboxEventEntity.class.getDeclaredMethod("markPublished", Instant.class);
        assertThat(Modifier.isPublic(transition.getModifiers())).isTrue();
    }

    @Test
    @DisplayName("The interface fixes the row type and takes its identifier type from the entity")
    void typeArgumentsAgreeWithTheEntity() {
        List<ParameterizedType> contracts = Arrays.stream(
                        OutboxEventRepository.class.getGenericInterfaces())
                .filter(ParameterizedType.class::isInstance)
                .map(ParameterizedType.class::cast)
                .filter(each -> each.getRawType().equals(ListCrudRepository.class))
                .toList();
        assertThat(contracts).hasSize(1);

        Field identifier = Arrays.stream(OutboxEventEntity.class.getDeclaredFields())
                .filter(field -> field.isAnnotationPresent(Id.class))
                .findFirst()
                .orElseThrow();

        Type[] arguments = contracts.get(0).getActualTypeArguments();
        assertThat(arguments[0]).isEqualTo(OutboxEventEntity.class);
        assertThat(arguments[1]).isEqualTo(identifier.getType());
    }

    @Test
    @Transactional
    @DisplayName("An insert leaves the row unpublished and its publication instant empty")
    void insertLeavesTheRowUnpublished() {
        OutboxEventEntity written = pendingRow("01", BASE_INSTANT);

        outboxEvents.save(written);
        flushAndDetach();

        OutboxEventEntity reloaded = outboxEvents.findById(written.getEventId()).orElseThrow();
        assertThat(reloaded.isPublished()).isFalse();
        assertThat(reloaded.getPublishedAt()).isNull();
    }

    @Test
    @Transactional
    @DisplayName("A written row round-trips its identifier, its event type and its account")
    void writtenValuesRoundTrip() {
        OutboxEventEntity written = pendingRow("02", BASE_INSTANT);

        outboxEvents.save(written);
        flushAndDetach();

        OutboxEventEntity reloaded = outboxEvents.findById(written.getEventId()).orElseThrow();
        assertThat(reloaded.getEventId()).isEqualTo(written.getEventId());
        assertThat(reloaded.getEventId().toString()).hasSize(EVENT_ID_TEXT_LENGTH);
        assertThat(reloaded.getEventType())
                .isEqualTo(EVENT_TYPE)
                .hasSizeLessThanOrEqualTo(OutboxEventEntity.EVENT_TYPE_MAX_LENGTH);
        assertThat(reloaded.getAggregateId())
                .isEqualTo(ACCOUNT_ID)
                .hasSize(OutboxEventEntity.AGGREGATE_ID_LENGTH)
                .startsWith("0");
        assertThat(reloaded.getCreatedAt()).isEqualTo(BASE_INSTANT);
    }

    @Test
    @Transactional
    @DisplayName("The pending finder returns the unpublished rows and leaves out the published one")
    void pendingFinderFiltersOnThePublicationFlag() {
        OutboxEventEntity first = pendingRow("01", BASE_INSTANT.plusSeconds(1));
        OutboxEventEntity second = pendingRow("02", BASE_INSTANT.plusSeconds(2));
        OutboxEventEntity third = publishedRow("03", BASE_INSTANT.plusSeconds(3));

        outboxEvents.saveAll(List.of(first, second, third));
        flushAndDetach();

        assertThat(pending(Limit.unlimited())).containsExactly(first, second);
    }

    @Test
    @Transactional
    @DisplayName("The pending finder orders rows by their creation instant, oldest first")
    void pendingFinderOrdersByCreationInstant() {
        // The identifiers run 01, 02, 03 while the creation instants run in the opposite order.
        OutboxEventEntity newest = pendingRow("01", BASE_INSTANT.plusSeconds(3));
        OutboxEventEntity middle = pendingRow("02", BASE_INSTANT.plusSeconds(2));
        OutboxEventEntity oldest = pendingRow("03", BASE_INSTANT.plusSeconds(1));

        outboxEvents.saveAll(List.of(middle, newest, oldest));
        flushAndDetach();

        assertThat(pending(Limit.unlimited())).containsExactly(oldest, middle, newest);
    }

    @Test
    @Transactional
    @DisplayName("The pending finder breaks a tie on the event identifier, ascending")
    void pendingFinderBreaksATieOnTheEventIdentifier() {
        Instant shared = BASE_INSTANT.plusSeconds(4);
        OutboxEventEntity lower = pendingRow("01", shared);
        OutboxEventEntity higher = pendingRow("02", shared);

        outboxEvents.saveAll(List.of(higher, lower));
        flushAndDetach();

        assertThat(pending(Limit.unlimited())).containsExactly(lower, higher);
    }

    @Test
    @Transactional
    @DisplayName("A bound of two returns the two oldest pending rows and no others")
    void pendingFinderHonoursASmallerBound() {
        List<OutboxEventEntity> oldestFirst = writeFivePendingRows();

        assertThat(pending(Limit.of(BOUNDED_ROWS)))
                .containsExactly(oldestFirst.get(0), oldestFirst.get(1));
    }

    @Test
    @Transactional
    @DisplayName("A bound above the row count returns every pending row, still oldest first")
    void pendingFinderReturnsEveryRowUnderALargerBound() {
        List<OutboxEventEntity> oldestFirst = writeFivePendingRows();

        assertThat(pending(Limit.of(oldestFirst.size() + 1)))
                .containsExactlyElementsOf(oldestFirst);
    }

    @Test
    @Transactional
    @DisplayName("The pending finder returns an empty list and never null when no row is pending")
    void pendingFinderReturnsAnEmptyListWhenNoRowIsPending() {
        outboxEvents.save(publishedRow("01", BASE_INSTANT));
        flushAndDetach();

        assertThat(pending(Limit.unlimited())).isNotNull().isEmpty();
    }

    /**
     * Reads the rows awaiting publication through the one derived finder of the interface.
     *
     * @param limit greatest number of rows to read
     * @return the rows awaiting publication, oldest first
     */
    private List<OutboxEventEntity> pending(Limit limit) {
        return outboxEvents.findByPublishedFalseOrderByCreatedAtAscEventIdAsc(limit);
    }

    /**
     * Writes five unpublished rows and returns them in the order the pending finder yields. The
     * identifiers run against the creation instants, and the write order matches neither.
     *
     * @return the five written rows, oldest first
     */
    private List<OutboxEventEntity> writeFivePendingRows() {
        OutboxEventEntity newest = pendingRow("01", BASE_INSTANT.plusSeconds(5));
        OutboxEventEntity fourth = pendingRow("02", BASE_INSTANT.plusSeconds(4));
        OutboxEventEntity third = pendingRow("03", BASE_INSTANT.plusSeconds(3));
        OutboxEventEntity second = pendingRow("04", BASE_INSTANT.plusSeconds(2));
        OutboxEventEntity oldest = pendingRow("05", BASE_INSTANT.plusSeconds(1));

        outboxEvents.saveAll(List.of(third, newest, oldest, fourth, second));
        flushAndDetach();

        return List.of(oldest, second, third, fourth, newest);
    }

    /**
     * Builds one unpublished row for {@link #ACCOUNT_ID}, ready to write.
     *
     * @param idSuffix  two hexadecimal digits completing {@link #EVENT_ID_STEM}
     * @param createdAt the instant the row records as its creation
     * @return an unpublished row
     */
    private OutboxEventEntity pendingRow(String idSuffix, Instant createdAt) {
        return new OutboxEventEntity(UUID.fromString(EVENT_ID_STEM + idSuffix), EVENT_TYPE, PAYLOAD,
                ACCOUNT_ID, createdAt);
    }

    /**
     * Builds one published row, whose publication flag, relay state and publication instant agree,
     * as the check constraints on the table require.
     *
     * @param idSuffix  two hexadecimal digits completing {@link #EVENT_ID_STEM}
     * @param createdAt the instant the row records as its creation
     * @return a published row
     */
    private OutboxEventEntity publishedRow(String idSuffix, Instant createdAt) {
        OutboxEventEntity row = pendingRow(idSuffix, createdAt);
        row.markPublished(createdAt.plusSeconds(1));
        return row;
    }

    /**
     * Writes every pending change to the database and detaches every row, so the next read reaches
     * the table.
     */
    private void flushAndDetach() {
        entityManager.flush();
        entityManager.clear();
    }

    /**
     * Returns the element type of the row list a finder declares.
     *
     * @param finder the declared method to read
     * @return the single type argument of its return type
     */
    private static Type elementTypeOf(Method finder) {
        return ((ParameterizedType) finder.getGenericReturnType()).getActualTypeArguments()[0];
    }
}
