package com.carddemo.authorization.entity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Asserts what a replica row records about the event it was written from, and that an older event
 * cannot move it backwards.
 *
 * <p>No COBOL ancestor, because the source and the target read different things.
 * {@code app/cbl/CBTRN02C.cbl:L382} and {@code app/cbl/CBTRN02C.cbl:L395} issue keyed reads against
 * the cross-reference and account datasets, so the source reads the records themselves and holds no
 * copy that an event could reorder.
 *
 * <p><strong>What this file deliberately no longer asserts.</strong> These rows once answered whether
 * their last observation fell inside a configured window, and a decision refused the call when it did
 * not. That question was the wrong one. The account and card services publish on a state change and
 * on nothing else, so a card nobody edits is a perfectly correct copy whose last observation recedes
 * for ever: every seeded card crossed the window within a day and every call for it was refused,
 * while a delivery that genuinely failed left {@code observed_at} exactly as recent as a successful
 * one and was never detected at all. Replica trust is now a property of the streams and of the
 * accounts a delivery failed for, and it is asserted in
 * {@code com.carddemo.authorization.domain.AuthorizationServiceTest} under "Replica usability".
 *
 * <p>{@code observed_at} remains, and remains useful: an operator reads it to see when a row was last
 * written, and {@link #aSeededRowNamesNoEvent()} pins that a seeded row still carries one. Nothing
 * decides anything from its age.
 *
 * <p>Every test runs in memory. None opens a database connection or contacts a broker.
 */
@DisplayName("replica observation and event ordering")
class ReplicaObservationTest {

    /** A fixed instant, so no test depends on the clock. */
    private static final Instant NOW = Instant.parse("2031-08-31T10:11:12Z");

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

    @Test
    @DisplayName("a seeded row names no source event, and still records when it was written")
    void aSeededRowNamesNoEvent() {
        CardCrossReferenceEntity seeded = xrefObservedAt(NOW);

        assertNull(seeded.getSourceEventId(),
                "V2__seed.sql is the initial load rather than an event");
        assertNull(seeded.getSourceOccurredAt(), "so it names no occurrence time either");
        assertEquals(NOW, seeded.getObservedAt(),
                "but it still says when it was written, which is what an operator reads");
        assertEquals(NOW, snapshotObservedAt(NOW).getObservedAt(),
                "and the account snapshot records the same fact the same way");
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
}
