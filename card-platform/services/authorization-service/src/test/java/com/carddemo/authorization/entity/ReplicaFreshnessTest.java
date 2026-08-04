package com.carddemo.authorization.entity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.carddemo.authorization.domain.DeclineRule;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Asserts that a replica row can say how old it is, and that an authorization refuses to claim
 * freshness it cannot establish.
 *
 * <p>ADDITIVE, and the reason is a difference between the source and the target rather than a
 * difference of opinion. {@code app/cbl/CBTRN02C.cbl:L382} and {@code app/cbl/CBTRN02C.cbl:L395}
 * issue keyed reads against the cross-reference and account datasets, so the source reads the
 * records themselves and has nothing that can go stale. This service reads copies kept current by
 * state-change events, and a copy whose events stopped arriving keeps answering with whatever it
 * last knew.
 *
 * <p>What makes that dangerous is that it fails quietly. The credit-limit rule at
 * {@code app/cbl/CBTRN02C.cbl:L403-L407} computes an answer from whatever the accumulators hold, so
 * a stale pair produces an approval rather than an error, and nothing in the response says the
 * numbers were old.
 *
 * <p>Every test runs in memory. None opens a database connection or contacts a broker.
 */
@DisplayName("replica freshness and the authorization fail-safe")
class ReplicaFreshnessTest {

    /** A fixed instant, so no test depends on the clock. */
    private static final Instant NOW = Instant.parse("2031-08-31T10:11:12Z");

    /** The freshness window a test compares against. */
    private static final Duration WINDOW = Duration.ofMinutes(15);

    /** Row one of {@code app/data/ASCII/cardxref.txt}, columns 1 through 16. */
    private static final String CARD_NUMBER = "0500024453765740";

    /** Row one of {@code app/data/ASCII/cardxref.txt}, its customer identifier. */
    private static final String CUSTOMER_ID = "000000050";

    /** Row one of {@code app/data/ASCII/cardxref.txt}, its account identifier. */
    private static final String ACCOUNT_ID = "00000000050";

    /**
     * Builds a cross-reference replica observed at the given time.
     *
     * @param observedAt when the row was last written
     * @return the replica row
     */
    private static CardCrossReferenceEntity xrefObservedAt(Instant observedAt) {
        return new CardCrossReferenceEntity(CARD_NUMBER, CUSTOMER_ID, ACCOUNT_ID, observedAt);
    }

    /**
     * Builds an account snapshot observed at the given time.
     *
     * @param observedAt when the row was last written
     * @return the snapshot row
     */
    private static AccountCreditSnapshotEntity snapshotObservedAt(Instant observedAt) {
        return new AccountCreditSnapshotEntity(ACCOUNT_ID, new BigDecimal("2020.00"), "2025-05-20",
                new BigDecimal("0.00"), new BigDecimal("0.00"), observedAt);
    }

    @Nested
    @DisplayName("A row reports its own freshness")
    class RowFreshness {

        @Test
        @DisplayName("a row observed inside the window is fresh, and one outside it is not")
        void theWindowDecides() {
            assertTrue(xrefObservedAt(NOW.minus(WINDOW).plusSeconds(1)).isFreshAt(NOW, WINDOW),
                    "an observation inside the window counts");
            assertTrue(xrefObservedAt(NOW.minus(WINDOW)).isFreshAt(NOW, WINDOW),
                    "the boundary counts as fresh, so the window is inclusive at its edge");
            assertFalse(xrefObservedAt(NOW.minus(WINDOW).minusSeconds(1)).isFreshAt(NOW, WINDOW),
                    "an observation older than the window does not count");
        }

        @Test
        @DisplayName("the account snapshot answers the same way")
        void theSnapshotAnswersTheSameWay() {
            assertTrue(snapshotObservedAt(NOW).isFreshAt(NOW, WINDOW), "just written");
            assertFalse(snapshotObservedAt(NOW.minus(Duration.ofDays(1))).isFreshAt(NOW, WINDOW),
                    "a day-old credit limit is not a credit limit to authorize against");
        }

        @Test
        @DisplayName("a seeded row names no source event, and still reports a freshness")
        void aSeededRowNamesNoEvent() {
            CardCrossReferenceEntity seeded = xrefObservedAt(NOW);

            assertNull(seeded.getSourceEventId(),
                    "V2__seed.sql is the initial load rather than an event");
            assertNull(seeded.getSourceOccurredAt(), "so it names no occurrence time either");
            assertEquals(NOW, seeded.getObservedAt(),
                    "but it still says when it was written, which is what a check reads");
        }
    }

    @Nested
    @DisplayName("An older event cannot move a replica backwards")
    class Ordering {

        @Test
        @DisplayName("the first event supersedes a seeded row")
        void theFirstEventSupersedesTheSeed() {
            CardCrossReferenceEntity row = xrefObservedAt(NOW.minus(Duration.ofHours(1)));
            UUID event = UUID.randomUUID();

            assertTrue(row.markObserved(event, NOW, NOW),
                    "a seeded row records no occurrence time, so any event supersedes it");
            assertEquals(event, row.getSourceEventId(), "the event is recorded");
            assertEquals(NOW, row.getSourceOccurredAt(), "its occurrence time is recorded");
            assertEquals(NOW, row.getObservedAt(), "the observation time moves");
        }

        @Test
        @DisplayName("a redelivery of the same event changes nothing")
        void aRedeliveryChangesNothing() {
            CardCrossReferenceEntity row = xrefObservedAt(NOW.minusSeconds(60));
            UUID event = UUID.randomUUID();
            row.markObserved(event, NOW, NOW);

            assertFalse(row.markObserved(event, NOW, NOW.plusSeconds(30)),
                    "the same occurrence time is not after the stored one, so nothing applies");
            assertEquals(NOW, row.getObservedAt(),
                    "and the observation time does not move either, so replaying the topic"
                            + " converges rather than drifting");
        }

        @Test
        @DisplayName("an event that occurred earlier is discarded")
        void anEarlierEventIsDiscarded() {
            CardCrossReferenceEntity row = xrefObservedAt(NOW.minusSeconds(60));
            row.markObserved(UUID.randomUUID(), NOW, NOW);
            UUID older = UUID.randomUUID();

            assertFalse(row.markObserved(older, NOW.minusSeconds(10), NOW.plusSeconds(1)),
                    "a redelivery arriving behind a newer event must not regress the replica");
            assertEquals(NOW, row.getSourceOccurredAt(), "the newer occurrence time stands");
        }

        @Test
        @DisplayName("a later event applies")
        void aLaterEventApplies() {
            CardCrossReferenceEntity row = xrefObservedAt(NOW.minusSeconds(60));
            row.markObserved(UUID.randomUUID(), NOW, NOW);
            Instant later = NOW.plusSeconds(5);

            assertTrue(row.markObserved(UUID.randomUUID(), later, later), "a newer event applies");
            assertEquals(later, row.getSourceOccurredAt(),
                    "and becomes the stored occurrence time");
        }
    }

    @Nested
    @DisplayName("The authorization context refuses freshness it cannot establish")
    class ContextFailSafe {

        @Test
        @DisplayName("both rows fresh reports fresh")
        void bothFreshReportsFresh() {
            DeclineRule.Context context = contextWith(xrefObservedAt(NOW), snapshotObservedAt(NOW));

            assertTrue(context.isReplicaDataFresh(NOW, WINDOW),
                    "both replicas were just written");
        }

        @Test
        @DisplayName("one stale row is enough to report stale")
        void oneStaleRowReportsStale() {
            Instant stale = NOW.minus(Duration.ofDays(1));

            assertFalse(contextWith(xrefObservedAt(stale), snapshotObservedAt(NOW))
                            .isReplicaDataFresh(NOW, WINDOW),
                    "a stale ownership mapping authorizes against the wrong account");
            assertFalse(contextWith(xrefObservedAt(NOW), snapshotObservedAt(stale))
                            .isReplicaDataFresh(NOW, WINDOW),
                    "a stale credit limit approves a transaction that should have declined");
        }

        @Test
        @DisplayName("a context that resolved nothing reports stale rather than fresh")
        void anUnresolvedContextReportsStale() {
            DeclineRule.Context context =
                    new DeclineRule.Context(CARD_NUMBER, new BigDecimal("100.00"),
                            "2031-08-31 10:11:12.130000");

            assertFalse(context.isReplicaDataFresh(NOW, WINDOW),
                    "absent is not fresh: a check that answered true here would be claiming"
                            + " freshness for a row it never read");
        }

        /**
         * Builds a context carrying both resolved replica rows.
         *
         * @param xref     the cross-reference row a rule resolved
         * @param snapshot the account snapshot a rule resolved
         * @return the populated context
         */
        private DeclineRule.Context contextWith(CardCrossReferenceEntity xref,
                AccountCreditSnapshotEntity snapshot) {
            DeclineRule.Context context =
                    new DeclineRule.Context(CARD_NUMBER, new BigDecimal("100.00"),
                            "2031-08-31 10:11:12.130000");
            context.setCardCrossReference(xref);
            context.setAccountCreditSnapshot(snapshot);
            return context;
        }
    }
}
