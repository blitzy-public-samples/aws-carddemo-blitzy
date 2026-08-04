package com.carddemo.authorization.entity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.carddemo.authorization.entity.OutboxEventEntity.RelayState;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Asserts the relay-state machine on {@link OutboxEventEntity}.
 *
 * <p>ADDITIVE throughout. The CardDemo source has one asynchronous handoff, the transient data
 * queue write at {@code app/cbl/CORPT00C.cbl:L517}, and one scheduled job picks the record up, so
 * nothing there can claim a row twice or give up on one. There is no COBOL behaviour to compare
 * against here; what these tests assert is that the two failures the columns exist to prevent are
 * actually prevented.
 *
 * <p>Without a claim, two relay instances read the same unpublished row and publish it twice, so a
 * claimed row must refuse a second claim once it is terminal and must release its claim when an
 * attempt fails. Without an attempt count and a next-attempt time, one undeliverable row is retried
 * forever, so the count must rise, the due time must move, and the row must stop at the ceiling.
 *
 * <p>Every test runs in memory. None opens a database connection or contacts a broker.
 */
@DisplayName("the outbox row's relay state")
class OutboxRelayStateTest {

    /** A fixed instant, so no test depends on the clock. */
    private static final Instant WRITTEN_AT = Instant.parse("2031-08-31T10:11:12Z");

    /** Row one of {@code app/data/ASCII/cardxref.txt}, the account identifier it resolves to. */
    private static final String AGGREGATE_ID = "00000000050";

    /** One relay instance name, within the {@code claimed_by VARCHAR(64)} column. */
    private static final String RELAY_ID = "authorization-relay-1";

    /**
     * Builds one freshly written row.
     *
     * @return an unpublished, unclaimed row created at {@link #WRITTEN_AT}
     */
    private static OutboxEventEntity newRow() {
        return new OutboxEventEntity(UUID.randomUUID(), "TransactionAuthorized", AGGREGATE_ID,
                "{}", WRITTEN_AT);
    }

    @Nested
    @DisplayName("A new row is pending and due at once")
    class NewRow {

        @Test
        @DisplayName("state is PENDING, no attempt has run, and the row is due when written")
        void aNewRowIsPendingAndDue() {
            OutboxEventEntity row = newRow();

            assertEquals(RelayState.PENDING, row.getRelayState(),
                    "a row the relay has not looked at yet is pending");
            assertEquals(0, row.getAttemptCount(), "no attempt has run");
            assertEquals(WRITTEN_AT, row.getNextAttemptAt(),
                    "a new row is due as soon as it is written, so the first sweep takes it");
            assertNull(row.getLastAttemptAt(), "no attempt has run");
            assertNull(row.getLastError(), "no attempt has failed");
            assertNull(row.getClaimedBy(), "no claim is held");
            assertNull(row.getClaimedAt(), "no claim is held");
            assertNull(row.getPublishedAt(), "the row is unpublished");
            assertFalse(row.isPublished(), "the row is unpublished");
            assertFalse(row.isTerminal(), "a pending row is not terminal");
        }
    }

    @Nested
    @DisplayName("A claim records who holds the row")
    class Claiming {

        @Test
        @DisplayName("claiming moves the state and records both halves of the claim")
        void claimingRecordsBothHalves() {
            OutboxEventEntity row = newRow();
            Instant at = WRITTEN_AT.plusSeconds(1);

            row.claim(RELAY_ID, at);

            assertEquals(RelayState.CLAIMED, row.getRelayState(), "the row is held");
            assertEquals(RELAY_ID, row.getClaimedBy(), "the holder is named");
            assertEquals(at, row.getClaimedAt(),
                    "the claim time is recorded, so a stranded claim can be found later");
        }

        @Test
        @DisplayName("a published row refuses a second claim, which is the duplicate publish")
        void aPublishedRowRefusesAClaim() {
            OutboxEventEntity row = newRow();
            row.markPublished(WRITTEN_AT.plusSeconds(1));

            IllegalStateException refusal = assertThrows(IllegalStateException.class,
                    () -> row.claim(RELAY_ID, WRITTEN_AT.plusSeconds(2)),
                    "re-claiming a published row is how the same event gets published twice");
            assertTrue(refusal.getMessage().contains("PUBLISHED"),
                    "the refusal names the state it refused: " + refusal.getMessage());
        }

        @Test
        @DisplayName("an abandoned row refuses a claim too")
        void anAbandonedRowRefusesAClaim() {
            OutboxEventEntity row = abandonedRow();

            assertThrows(IllegalStateException.class,
                    () -> row.claim(RELAY_ID, WRITTEN_AT.plusSeconds(99)),
                    "the relay has given up on this row and must leave it alone");
        }

        @Test
        @DisplayName("a relay identifier wider than its column is refused, not truncated")
        void anOversizedRelayIdentifierIsRefused() {
            OutboxEventEntity row = newRow();
            String tooLong = "r".repeat(OutboxEventEntity.CLAIMED_BY_MAX_LENGTH + 1);

            IllegalArgumentException refusal = assertThrows(IllegalArgumentException.class,
                    () -> row.claim(tooLong, WRITTEN_AT),
                    "a truncated relay name names a relay that does not exist");
            assertFalse(refusal.getMessage().contains(tooLong),
                    "the refusal reports a length and not the value: " + refusal.getMessage());
        }

        @Test
        @DisplayName("a blank relay identifier is refused")
        void aBlankRelayIdentifierIsRefused() {
            OutboxEventEntity row = newRow();

            assertThrows(IllegalArgumentException.class, () -> row.claim("   ", WRITTEN_AT),
                    "a claim nobody holds is not a claim");
        }
    }

    @Nested
    @DisplayName("A failure backs the row off and eventually abandons it")
    class Failing {

        @Test
        @DisplayName("a failure counts, releases the claim and moves the due time")
        void aFailureBacksTheRowOff() {
            OutboxEventEntity row = newRow();
            row.claim(RELAY_ID, WRITTEN_AT);
            Instant at = WRITTEN_AT.plusSeconds(2);
            Instant retryAt = at.plus(Duration.ofSeconds(30));

            row.recordFailure("BROKER_UNREACHABLE", at, retryAt);

            assertEquals(1, row.getAttemptCount(), "the attempt counts");
            assertEquals(RelayState.PENDING, row.getRelayState(), "the row is due again");
            assertEquals(retryAt, row.getNextAttemptAt(),
                    "the due time moves, so this row stops blocking the ones behind it");
            assertEquals(at, row.getLastAttemptAt(), "the attempt time is recorded");
            assertEquals("BROKER_UNREACHABLE", row.getLastError(), "the reason is recorded");
            assertNull(row.getClaimedBy(),
                    "the claim is released, so a relay that dies mid-attempt strands nothing");
            assertNull(row.getClaimedAt(), "the claim is released");
        }

        @Test
        @DisplayName("the row is abandoned on the attempt that reaches the ceiling")
        void theRowIsAbandonedAtTheCeiling() {
            OutboxEventEntity row = newRow();

            for (int attempt = 1; attempt <= OutboxEventEntity.MAX_DELIVERY_ATTEMPTS; attempt++) {
                assertFalse(row.isTerminal(),
                        "attempt " + attempt + " runs, so the row cannot be terminal yet");
                row.recordFailure("BROKER_UNREACHABLE", WRITTEN_AT.plusSeconds(attempt),
                        WRITTEN_AT.plusSeconds(attempt + 30));
            }

            assertEquals(RelayState.ABANDONED, row.getRelayState(),
                    "the relay gives up at " + OutboxEventEntity.MAX_DELIVERY_ATTEMPTS
                            + " attempts rather than retrying forever");
            assertEquals(OutboxEventEntity.MAX_DELIVERY_ATTEMPTS, row.getAttemptCount(),
                    "the count stops at the ceiling");
            assertTrue(row.isTerminal(), "an abandoned row is terminal");
            assertThrows(IllegalStateException.class,
                    () -> row.recordFailure("AGAIN", WRITTEN_AT.plusSeconds(99),
                            WRITTEN_AT.plusSeconds(199)),
                    "an abandoned row takes no further attempt");
        }

        @Test
        @DisplayName("a reason wider than its column is truncated rather than refused")
        void anOversizedReasonIsTruncated() {
            OutboxEventEntity row = newRow();
            String tooLong = "e".repeat(OutboxEventEntity.LAST_ERROR_MAX_LENGTH + 50);

            row.recordFailure(tooLong, WRITTEN_AT, WRITTEN_AT.plusSeconds(30));

            assertEquals(OutboxEventEntity.LAST_ERROR_MAX_LENGTH, row.getLastError().length(),
                    "losing the tail of a diagnostic is better than losing the row");
        }

        @Test
        @DisplayName("a null reason is accepted, because a failure without one still counts")
        void aNullReasonIsAccepted() {
            OutboxEventEntity row = newRow();

            row.recordFailure(null, WRITTEN_AT, WRITTEN_AT.plusSeconds(30));

            assertNull(row.getLastError(), "no reason was available");
            assertEquals(1, row.getAttemptCount(), "the attempt still counts");
        }
    }

    @Nested
    @DisplayName("Publishing moves three values together")
    class Publishing {

        @Test
        @DisplayName("the flag, the state and the timestamp all move, and the claim is released")
        void allThreeValuesMove() {
            OutboxEventEntity row = newRow();
            row.claim(RELAY_ID, WRITTEN_AT);
            Instant at = WRITTEN_AT.plusSeconds(3);

            row.markPublished(at);

            assertTrue(row.isPublished(), "the boolean moves");
            assertEquals(RelayState.PUBLISHED, row.getRelayState(), "the state moves");
            assertEquals(at, row.getPublishedAt(), "the timestamp moves");
            assertNull(row.getClaimedBy(), "a published row needs no claim");
            assertTrue(row.isTerminal(), "a published row is terminal");
        }

        @Test
        @DisplayName("a second publish is ignored rather than rewriting the timestamp")
        void aSecondPublishIsIgnored() {
            OutboxEventEntity row = newRow();
            Instant first = WRITTEN_AT.plusSeconds(3);
            row.markPublished(first);

            row.markPublished(WRITTEN_AT.plusSeconds(60));

            assertEquals(first, row.getPublishedAt(),
                    "a relay that publishes and then fails before its own transaction commits must"
                            + " not corrupt the row on the retry");
        }

        @Test
        @DisplayName("an abandoned row cannot be marked published")
        void anAbandonedRowCannotBePublished() {
            OutboxEventEntity row = abandonedRow();

            assertThrows(IllegalStateException.class,
                    () -> row.markPublished(WRITTEN_AT.plusSeconds(99)),
                    "the two terminal states are exclusive");
        }
    }

    /**
     * Builds a row the relay has given up on.
     *
     * @return a row in {@link RelayState#ABANDONED}
     */
    private static OutboxEventEntity abandonedRow() {
        OutboxEventEntity row = newRow();
        for (int attempt = 1; attempt <= OutboxEventEntity.MAX_DELIVERY_ATTEMPTS; attempt++) {
            row.recordFailure("BROKER_UNREACHABLE", WRITTEN_AT.plusSeconds(attempt),
                    WRITTEN_AT.plusSeconds(attempt + 30));
        }
        return row;
    }
}
