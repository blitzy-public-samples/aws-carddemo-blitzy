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
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Limit;
import org.springframework.data.repository.ListCrudRepository;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Reads the declared shape of {@link OutboxEventRepository}, then exercises its diagnostic,
 * claim, recovery and purge paths against {@code outbox_event}.
 *
 * <p>This class has no COBOL ancestor. No copybook and no program of the CardDemo source
 * declares an outbox record. The source carries one asynchronous handoff, the Customer Information
 * Control System (CICS) command {@code EXEC CICS WRITEQ TD} on {@code QUEUE ('JOBS')} at
 * {@code app/cbl/CORPT00C.cbl:L517-L518}. Every row below carries the account identifier
 * {@code ACCT-ID PIC 9(11)} at {@code app/cpy/CVACT01Y.cpy:L5}.
 *
 * <p>Reflection over the interface supplies four properties: the declared method inventory, the row
 * bound as a parameter, the absence of a publication mutator, and the two type arguments.
 * Database tests cover pending order, disjoint claims, account head-of-line order, stale
 * claims, retention and rollback.
 *
 * <p>{@link AbstractAccountPostgresTest} owns the one PostgreSQL container of the module, so no
 * container appears here. Each test that writes carries {@link Transactional}, and Spring rolls
 * the insert back as the test returns.
 */
@DisplayName("OutboxEventRepository, pending rows, claims, recovery and retention")
class OutboxEventRepositoryTest extends AbstractAccountPostgresTest {

    /** The one derived finder of the interface, named once for the reflection tests below. */
    private static final String PENDING_FINDER = "findByPublishedFalseOrderByCreatedAtAscEventIdAsc";

    /** The derived finder that reads the abandoned rows still owing a terminal diagnostic. */
    private static final String DEAD_LETTER_FINDER = "findByDeadLetterStateOrderByLastAttemptAtAsc";

    /** Instant an acknowledged diagnostic records, distinct from every other instant here. */
    private static final Instant ACKNOWLEDGED_AT = Instant.parse("2024-05-01T00:00:00Z");

    /** The one event type this module writes, within {@link OutboxEventEntity#EVENT_TYPE_MAX_LENGTH}. */
    private static final String EVENT_TYPE = "AccountStateChanged";

    /** An opaque payload, and the shortest text the column accepts. */
    private static final String PAYLOAD = "{}";

    /**
     * The account every row here belongs to, eleven digits with its leading zero kept.
     */
    private static final String ACCOUNT_ID = "00000000011";

    /** A second account, used to prove account partitions can be claimed independently. */
    private static final String OTHER_ACCOUNT_ID = "00000000012";

    /**
     * Text form of every identifier this class writes, completed by two hexadecimal digits. The
     * leading bytes are equal across the rows, and the final two digits carry the whole difference.
     */
    private static final String EVENT_ID_STEM = "00000000-0000-4000-8000-0000000000";

    /** Characters in the text form of an event identifier. */
    private static final int EVENT_ID_TEXT_LENGTH = 36;

    /** The instant every row measures from, at a whole second. */
    private static final Instant BASE_INSTANT = Instant.parse("2026-01-01T00:00:00Z");

    /** Future time used by committed claim tests, beyond the live scheduler's clock. */
    private static final Instant CLAIM_NOW = Instant.parse("2099-01-01T00:00:10Z");

    /** Rows the bounded read asks for, out of the five {@link #writeFivePendingRows()} writes. */
    private static final int BOUNDED_ROWS = 2;

    /** The interface under test, discovered from the scan root {@code com.carddemo.account}. */
    @Autowired
    private OutboxEventRepository outboxEvents;

    /** Opens the independent transactions used by the claim-race tests. */
    @Autowired
    private PlatformTransactionManager transactionManager;

    /** The flush path. The inherited interface declares no flush method. */
    @PersistenceContext
    private EntityManager entityManager;

    /** Identifiers written in committed transactions and removed after each test. */
    private final Set<UUID> committedEventIds = new LinkedHashSet<>();

    /** Removes rows that a claim-race test committed. */
    @AfterEach
    void removeCommittedRows() {
        if (committedEventIds.isEmpty()) {
            return;
        }
        List<UUID> identifiers = List.copyOf(committedEventIds);
        committedEventIds.clear();
        inTransaction(() -> {
            outboxEvents.deleteAllById(identifiers);
            return null;
        });
    }

    @Test
    @DisplayName("The interface declares exactly the eight methods named here")
    void declaredMethodInventoryHoldsEightNames() {
        List<String> declared = Arrays.stream(OutboxEventRepository.class.getDeclaredMethods())
                .filter(method -> !method.isSynthetic())
                .map(Method::getName)
                .sorted()
                .toList();

        assertThat(declared).containsExactly(
                "claimDueRows",
                "countDueBefore",
                "deletePublishedBefore",
                "existsByRelayState",
                DEAD_LETTER_FINDER,
                PENDING_FINDER,
                "findByRelayStateAndClaimedAtBeforeOrderByClaimedAtAsc",
                "findEarliestDueBefore");
    }

    @Test
    @DisplayName("The backlog count holds the pending rows already due and no other row")
    @Transactional
    void theBacklogCountHoldsPendingRowsAlreadyDue() {
        emptyTheOutbox();
        Instant now = BASE_INSTANT.plusSeconds(10);
        outboxEvents.saveAll(List.of(
                pendingRow("e1", BASE_INSTANT.plusSeconds(1)),
                pendingRow("e2", BASE_INSTANT.plusSeconds(2)),
                pendingRow("e3", now.plusSeconds(60)),
                publishedRow("e4", BASE_INSTANT.plusSeconds(1)),
                abandonedRow("e5", BASE_INSTANT.plusSeconds(1))));
        flushAndDetach();

        long due = outboxEvents.countDueBefore(now);

        assertThat(due)
                .as("the two pending rows already due, and not the row due later, the published"
                        + " row or the abandoned row")
                .isEqualTo(2L);
    }

    @Test
    @DisplayName("The earliest due instant names the longest-waiting row")
    @Transactional
    void theEarliestDueInstantNamesTheLongestWaitingRow() {
        emptyTheOutbox();
        Instant oldest = BASE_INSTANT.plusSeconds(1);
        outboxEvents.saveAll(List.of(
                pendingRow("f1", BASE_INSTANT.plusSeconds(3)),
                pendingRow("f2", oldest),
                pendingRow("f3", BASE_INSTANT.plusSeconds(2))));
        flushAndDetach();

        assertThat(outboxEvents.findEarliestDueBefore(BASE_INSTANT.plusSeconds(10)))
                .as("the instant the longest-waiting due row became due")
                .contains(oldest);
    }

    @Test
    @DisplayName("The earliest due instant is empty when the outbox owes nothing yet")
    @Transactional
    void theEarliestDueInstantIsEmptyWhenNothingIsDue() {
        emptyTheOutbox();
        Instant now = BASE_INSTANT.plusSeconds(10);
        outboxEvents.saveAll(List.of(
                pendingRow("f4", now.plusSeconds(60)),
                publishedRow("f5", BASE_INSTANT.plusSeconds(1))));
        flushAndDetach();

        assertThat(outboxEvents.findEarliestDueBefore(now))
                .as("no answer, because the only pending row falls due later")
                .isEmpty();
        assertThat(outboxEvents.countDueBefore(now))
                .as("the backlog is empty at the same instant").isZero();
    }

    /**
     * Removes every row so a backlog reading sees only the rows the calling test wrote.
     *
     * <p>The caller carries {@link Transactional}, so this removal is rolled back with the test. A
     * row another test committed is therefore restored, and the reading here stays deterministic
     * whatever order the class runs in.
     */
    private void emptyTheOutbox() {
        outboxEvents.deleteAll();
        flushAndDetach();
    }

    @Test
    @DisplayName("The owed-diagnostic finder reads the abandoned rows a dead letter still names")
    @Transactional
    void theOwedDiagnosticFinderReadsAbandonedRowsOnly() {
        OutboxEventEntity abandoned = abandonedRow("d1", CLAIM_NOW);
        OutboxEventEntity waiting = pendingRow("d2", CLAIM_NOW);
        outboxEvents.saveAll(List.of(abandoned, waiting));
        flushAndDetach();

        List<OutboxEventEntity> owed = outboxEvents.findByDeadLetterStateOrderByLastAttemptAtAsc(
                OutboxEventEntity.DeadLetterState.REQUIRED, Limit.of(10));

        assertThat(owed).extracting(OutboxEventEntity::getEventId)
                .as("the query answers with the abandoned row owing a diagnostic and no other")
                .contains(abandoned.getEventId())
                .doesNotContain(waiting.getEventId());
        assertThat(owed).allMatch(OutboxEventEntity::owesDeadLetter);
    }

    @Test
    @DisplayName("An acknowledged diagnostic leaves the owed set")
    @Transactional
    void anAcknowledgedDiagnosticLeavesTheOwedSet() {
        OutboxEventEntity row = abandonedRow("d3", CLAIM_NOW);
        outboxEvents.save(row);
        flushAndDetach();

        OutboxEventEntity stored = outboxEvents.findById(row.getEventId()).orElseThrow();
        stored.markDeadLetterPublished(ACKNOWLEDGED_AT);
        outboxEvents.save(stored);
        flushAndDetach();

        assertThat(outboxEvents.findByDeadLetterStateOrderByLastAttemptAtAsc(
                OutboxEventEntity.DeadLetterState.REQUIRED, Limit.of(10)))
                .extracting(OutboxEventEntity::getEventId)
                .as("an acknowledged diagnostic is never offered again")
                .doesNotContain(row.getEventId());
        assertThat(outboxEvents.findById(row.getEventId()).orElseThrow()
                .getDeadLetterPublishedAt())
                .as("the acknowledgement instant reaches the column")
                .isEqualTo(ACKNOWLEDGED_AT);
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

    @Test
    @DisplayName("two due-row claims in concurrent transactions return disjoint rows")
    void dueRowClaimsAreDisjointAcrossConcurrentTransactions() throws Exception {
        OutboxEventEntity first =
                pendingRow("a1", CLAIM_NOW.minusSeconds(2), ACCOUNT_ID);
        OutboxEventEntity second =
                pendingRow("a2", CLAIM_NOW.minusSeconds(1), OTHER_ACCOUNT_ID);
        commitRows(first, second);

        CountDownLatch firstClaimed = new CountDownLatch(1);
        CountDownLatch releaseFirst = new CountDownLatch(1);
        ExecutorService workers = Executors.newFixedThreadPool(2);
        try {
            Future<List<UUID>> firstClaim = workers.submit(
                    () -> claimDueRows("relay-one", 1, firstClaimed, releaseFirst));
            assertThat(firstClaimed.await(5, TimeUnit.SECONDS)).isTrue();

            Future<List<UUID>> secondClaim = workers.submit(
                    () -> claimDueRows("relay-two", 1, null, null));
            List<UUID> secondIdentifiers = secondClaim.get(5, TimeUnit.SECONDS);

            releaseFirst.countDown();
            List<UUID> firstIdentifiers = firstClaim.get(5, TimeUnit.SECONDS);

            assertThat(firstIdentifiers).hasSize(1);
            assertThat(secondIdentifiers).hasSize(1);
            assertThat(firstIdentifiers).doesNotContainAnyElementsOf(secondIdentifiers);
            assertThat(new LinkedHashSet<>(firstIdentifiers))
                    .containsAnyOf(first.getEventId(), second.getEventId());
            assertThat(new LinkedHashSet<>(secondIdentifiers))
                    .containsAnyOf(first.getEventId(), second.getEventId());
        } finally {
            releaseFirst.countDown();
            workers.shutdownNow();
        }
    }

    /**
     * Separates the two reads of pending work, which is what keeps a diagnostic query out of the
     * relay's way.
     *
     * <p>Both rows here carry a creation instant in the future, so neither is due. The claim query
     * filters on {@code nextAttemptAt <= now} and takes a pessimistic lock, so it returns nothing
     * and locks nothing. The pending finder filters only on the publication flag and takes no lock,
     * so it sees both rows while a competing transaction holds one of them. A reader that used the
     * claim query for a health check would take locks the relay then has to skip.
     */
    @Test
    @DisplayName("the diagnostic finder reads rows the due-row claim neither returns nor locks")
    void theDiagnosticFinderReadsRowsTheClaimNeitherReturnsNorLocks() throws Exception {
        OutboxEventEntity first =
                pendingRow("b1", CLAIM_NOW.plusSeconds(1), ACCOUNT_ID);
        OutboxEventEntity second =
                pendingRow("b2", CLAIM_NOW.plusSeconds(2), OTHER_ACCOUNT_ID);
        commitRows(first, second);

        CountDownLatch firstRead = new CountDownLatch(1);
        CountDownLatch releaseFirst = new CountDownLatch(1);
        ExecutorService workers = Executors.newFixedThreadPool(2);
        try {
            Future<List<UUID>> holder = workers.submit(
                    () -> readPendingRows(2, firstRead, releaseFirst));
            assertThat(firstRead.await(5, TimeUnit.SECONDS)).isTrue();

            Future<List<UUID>> concurrentClaim =
                    workers.submit(() -> claimDueRows("relay-three", 2, null, null));
            assertThat(concurrentClaim.get(5, TimeUnit.SECONDS)).isEmpty();

            Future<List<UUID>> concurrentRead =
                    workers.submit(() -> readPendingRows(2, null, null));
            assertThat(concurrentRead.get(5, TimeUnit.SECONDS))
                    .containsExactly(first.getEventId(), second.getEventId());

            releaseFirst.countDown();
            assertThat(holder.get(5, TimeUnit.SECONDS))
                    .containsExactly(first.getEventId(), second.getEventId());
        } finally {
            releaseFirst.countDown();
            workers.shutdownNow();
        }
    }

    @Test
    @Transactional
    @DisplayName("a later row of one account waits behind its earlier row during backoff")
    void dueRowsKeepTheHeadOfEachAccount() {
        OutboxEventEntity earlier =
                pendingRow("c1", CLAIM_NOW.minusSeconds(4), ACCOUNT_ID);
        earlier.claim("relay-that-failed", CLAIM_NOW.minusSeconds(3));
        earlier.recordFailure("refused", CLAIM_NOW.minusSeconds(2), CLAIM_NOW.plusSeconds(30));
        OutboxEventEntity later =
                pendingRow("c2", CLAIM_NOW.minusSeconds(1), ACCOUNT_ID);
        OutboxEventEntity otherAccount =
                pendingRow("c3", CLAIM_NOW.minusSeconds(1), OTHER_ACCOUNT_ID);
        outboxEvents.saveAll(List.of(earlier, later, otherAccount));
        flushAndDetach();

        assertThat(outboxEvents.claimDueRows(CLAIM_NOW, Limit.of(10)))
                .extracting(OutboxEventEntity::getEventId)
                .containsExactly(otherAccount.getEventId());
    }

    @Test
    @Transactional
    @DisplayName("an abandoned account head no longer blocks the next row of that account")
    void abandonedRowsDoNotBlockTheNextAccountRow() {
        OutboxEventEntity abandoned =
                pendingRow("c4", CLAIM_NOW.minusSeconds(20), ACCOUNT_ID);
        for (int attempt = 0; attempt < OutboxEventEntity.MAX_DELIVERY_ATTEMPTS; attempt++) {
            Instant attemptedAt = CLAIM_NOW.minusSeconds(19L - attempt);
            abandoned.claim("exhausted-relay", attemptedAt);
            abandoned.recordFailure("refused", attemptedAt, attemptedAt);
        }
        OutboxEventEntity next =
                pendingRow("c5", CLAIM_NOW.minusSeconds(1), ACCOUNT_ID);
        outboxEvents.saveAll(List.of(abandoned, next));
        flushAndDetach();

        assertThat(outboxEvents.claimDueRows(CLAIM_NOW, Limit.of(10)))
                .extracting(OutboxEventEntity::getEventId)
                .containsExactly(next.getEventId());
    }

    @Test
    @Transactional
    @DisplayName("the stale-claim finder returns only claims older than its cutoff")
    void staleClaimFinderUsesTheClaimTimeCutoff() {
        OutboxEventEntity stale =
                pendingRow("d1", CLAIM_NOW.minusSeconds(60), ACCOUNT_ID);
        stale.claim("expired-relay", CLAIM_NOW.minusSeconds(50));
        OutboxEventEntity live =
                pendingRow("d2", CLAIM_NOW.minusSeconds(40), OTHER_ACCOUNT_ID);
        live.claim("live-relay", CLAIM_NOW.minusSeconds(5));
        outboxEvents.saveAll(List.of(stale, live));
        flushAndDetach();

        assertThat(outboxEvents.findByRelayStateAndClaimedAtBeforeOrderByClaimedAtAsc(
                OutboxEventEntity.RelayState.CLAIMED, CLAIM_NOW.minusSeconds(30),
                Limit.unlimited()))
                .extracting(OutboxEventEntity::getEventId)
                .containsExactly(stale.getEventId());
    }

    @Test
    @Transactional
    @DisplayName("the published-row purge removes only rows before its horizon")
    void publishedRowPurgeUsesThePublicationHorizon() {
        OutboxEventEntity old =
                publishedRow("e1", CLAIM_NOW.minusSeconds(100));
        OutboxEventEntity recent =
                publishedRow("e2", CLAIM_NOW.minusSeconds(10));
        outboxEvents.saveAll(List.of(old, recent));
        flushAndDetach();

        int removed = outboxEvents.deletePublishedBefore(CLAIM_NOW.minusSeconds(50), 500);
        entityManager.clear();

        assertThat(removed).isEqualTo(1);
        assertThat(outboxEvents.findById(old.getEventId())).isEmpty();
        assertThat(outboxEvents.findById(recent.getEventId())).isPresent();
    }

    @Test
    @Transactional
    @DisplayName("the published purge removes at most the row ceiling it is given, oldest first")
    void publishedRowPurgeRemovesAtMostItsCeilingOldestFirst() {
        OutboxEventEntity oldest = publishedRow("b1", CLAIM_NOW.minusSeconds(300));
        OutboxEventEntity middle = publishedRow("b2", CLAIM_NOW.minusSeconds(200));
        OutboxEventEntity newest = publishedRow("b3", CLAIM_NOW.minusSeconds(100));
        outboxEvents.saveAll(List.of(oldest, middle, newest));
        flushAndDetach();

        int firstBatch = outboxEvents.deletePublishedBefore(CLAIM_NOW, 2);
        entityManager.clear();

        assertThat(firstBatch)
                .as("a bound of two removes two rows and leaves the third for the next batch")
                .isEqualTo(2);
        assertThat(outboxEvents.findById(oldest.getEventId()))
                .as("the ordering removes the oldest first")
                .isEmpty();
        assertThat(outboxEvents.findById(middle.getEventId())).isEmpty();
        assertThat(outboxEvents.findById(newest.getEventId()))
                .as("the newest row survives a bound of two")
                .isPresent();

        int secondBatch = outboxEvents.deletePublishedBefore(CLAIM_NOW, 2);
        entityManager.clear();

        assertThat(secondBatch)
                .as("the drain stops when a batch comes back short of its ceiling")
                .isEqualTo(1);
    }

    @Test
    @DisplayName("a rolled-back failed-attempt transition leaves the row pending")
    void failedAttemptTransitionRollsBackWithItsTransaction() {
        OutboxEventEntity row =
                pendingRow("f1", CLAIM_NOW.minusSeconds(1), ACCOUNT_ID);
        commitRows(row);

        assertThatThrownBy(() -> inTransaction(() -> {
            OutboxEventEntity claimed = outboxEvents.claimDueRows(CLAIM_NOW, Limit.of(1)).getFirst();
            claimed.claim("rolling-back-relay", CLAIM_NOW);
            claimed.recordFailure("refused", CLAIM_NOW, CLAIM_NOW.plusSeconds(30));
            outboxEvents.save(claimed);
            throw new IllegalStateException("roll back this transition");
        })).isInstanceOf(IllegalStateException.class);

        OutboxEventEntity reloaded = outboxEvents.findById(row.getEventId()).orElseThrow();
        assertThat(reloaded.getRelayState()).isEqualTo(OutboxEventEntity.RelayState.PENDING);
        assertThat(reloaded.getAttemptCount()).isZero();
        assertThat(reloaded.getClaimedBy()).isNull();
        assertThat(reloaded.getLastAttemptAt()).isNull();
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
        return pendingRow(idSuffix, createdAt, ACCOUNT_ID);
    }

    /**
     * Builds one unpublished row for the supplied account.
     *
     * @param idSuffix  two hexadecimal digits completing {@link #EVENT_ID_STEM}
     * @param createdAt the instant the row records as its creation
     * @param accountId the account identifier the row carries
     * @return an unpublished row
     */
    private OutboxEventEntity pendingRow(String idSuffix, Instant createdAt, String accountId) {
        return new OutboxEventEntity(UUID.fromString(EVENT_ID_STEM + idSuffix), EVENT_TYPE, PAYLOAD,
                accountId, createdAt);
    }

    /**
     * Builds one abandoned row that owes a terminal diagnostic.
     *
     * <p>The row reaches that state the way the relay reaches it, by recording
     * {@link OutboxEventEntity#MAX_DELIVERY_ATTEMPTS} failures. Setting the columns directly would
     * pass the check constraints while proving nothing about how a real row gets there.
     *
     * @param idSuffix  two hexadecimal digits completing {@link #EVENT_ID_STEM}
     * @param createdAt the instant the row records as its creation
     * @return an abandoned row whose dead-letter state is REQUIRED
     */
    private OutboxEventEntity abandonedRow(String idSuffix, Instant createdAt) {
        OutboxEventEntity row = pendingRow(idSuffix, createdAt);
        for (int attempt = 0; attempt < OutboxEventEntity.MAX_DELIVERY_ATTEMPTS; attempt++) {
            row.recordFailure("IllegalStateException", createdAt, createdAt);
        }
        return row;
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

    /** Commits the supplied rows so another transaction can claim them. */
    private void commitRows(OutboxEventEntity... rows) {
        for (OutboxEventEntity row : rows) {
            committedEventIds.add(row.getEventId());
        }
        inTransaction(() -> {
            outboxEvents.saveAll(List.of(rows));
            return null;
        });
    }

    /** Claims due rows in one independent transaction. */
    private List<UUID> claimDueRows(String relayId, int limit, CountDownLatch claimed,
            CountDownLatch release) {
        return inTransaction(() -> {
            List<OutboxEventEntity> rows =
                    outboxEvents.claimDueRows(CLAIM_NOW, Limit.of(limit));
            for (OutboxEventEntity row : rows) {
                row.claim(relayId, CLAIM_NOW);
            }
            entityManager.flush();
            signalAndWait(claimed, release);
            return rows.stream().map(OutboxEventEntity::getEventId).toList();
        });
    }

    /** Reads pending rows through the unlocked diagnostic finder in one independent transaction. */
    private List<UUID> readPendingRows(int limit, CountDownLatch read, CountDownLatch release) {
        return inTransaction(() -> {
            List<OutboxEventEntity> rows =
                    outboxEvents.findByPublishedFalseOrderByCreatedAtAscEventIdAsc(
                            Limit.of(limit));
            entityManager.flush();
            signalAndWait(read, release);
            return rows.stream().map(OutboxEventEntity::getEventId).toList();
        });
    }

    /** Signals that a claim is held, then waits until the caller releases it. */
    private static void signalAndWait(CountDownLatch claimed, CountDownLatch release) {
        if (claimed == null) {
            return;
        }
        claimed.countDown();
        try {
            if (!release.await(5, TimeUnit.SECONDS)) {
                throw new AssertionError("the competing claim did not finish");
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new AssertionError("the claim wait was interrupted", interrupted);
        }
    }

    /** Runs one callback in a new transaction and returns its result. */
    private <T> T inTransaction(Supplier<T> callback) {
        TransactionTemplate transaction = new TransactionTemplate(transactionManager);
        transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        return transaction.execute(status -> callback.get());
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
