package com.carddemo.card.entity;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.carddemo.card.entity.CardTokenRotationMappingEntity.CardTokenRotationMappingId;
import com.carddemo.cobol.PanMasker;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Proves the two rows a card-token rotation leaves behind describe the run and name no card.
 *
 * <p>A card token is the platform identity of one card, so re-deriving every stored token at start-up
 * whenever the derivation changes would move that identity silently. Three other stores hold a token
 * derived from the same key and hold no card number, so none can re-derive its own rows:
 * {@code statement_transaction.card_token} and {@code notification_log.card_token} in the notification
 * service, and {@code authorization_decision.card_token} in the authorization service. A granted
 * {@code SCOPE_CARD} authority is a fourth. The mapping row is what those four are re-keyed from, and
 * the audit row is what says a rotation happened at all.
 *
 * <p>Two properties are asserted here that no other test can see. The invariants each row holds itself
 * to, which is what stops a mapping that re-keys nothing or an audit row that claims more rewrites than
 * it read, and the renderings, which carry a token suffix rather than a token because a token names a
 * card as completely as a card number does.
 *
 * <p>No assertion here reaches a database. {@code CardRepositoryIT} exercises the three outcomes of a
 * run against a real PostgreSQL, and {@code com.carddemo.cobol.PanMaskerTest} proves the derivation of
 * both halves of a mapping. This class covers what those two leave: the row contracts themselves.
 */
@DisplayName("The rotation audit row and the mapping row it owns")
class CardTokenRotationEntityTest {

    /** The run every row in this class belongs to. */
    private static final UUID ROTATION_ID =
            UUID.fromString("6f1d3a2c-7b45-4e8a-9c31-0d5a8e2f4b60");

    /** When the run began. */
    private static final Instant STARTED_AT = Instant.parse("2026-03-04T09:15:00Z");

    /** When the run finished, later than it started. */
    private static final Instant FINISHED_AT = Instant.parse("2026-03-04T09:15:07Z");

    /** A token of the declared shape, standing for the value another store still holds. */
    private static final String PREVIOUS_TOKEN = "a".repeat(PanMasker.CARD_TOKEN_LENGTH);

    /** A second token of the declared shape, standing for the value it now has to hold. */
    private static final String CURRENT_TOKEN = "b".repeat(PanMasker.CARD_TOKEN_LENGTH);

    /** The identity a run records, as the reconciler reads it from the process. */
    private static final String ACTOR = "carddemo";

    /** Builds one closed audit row. */
    private static CardTokenRotationEntity rotation(int read, int rewritten) {
        return new CardTokenRotationEntity(ROTATION_ID, STARTED_AT, FINISHED_AT, "1", "2",
                read, rewritten, ACTOR);
    }

    /** Builds one mapping row of the run. */
    private static CardTokenRotationMappingEntity mapping(String previous, String current) {
        return new CardTokenRotationMappingEntity(ROTATION_ID, previous, current, "1", "2");
    }

    /**
     * The audit row: what one run reports, and the three claims it refuses to make.
     */
    @Nested
    @DisplayName("the audit row of one run")
    class AuditRow {

        @Test
        @DisplayName("a closed row reports the window, the versions and both counts")
        void aClosedRowReportsItsRunAggregate() {
            CardTokenRotationEntity closed = rotation(50, 3);

            assertAll(
                    () -> assertEquals(ROTATION_ID, closed.getRotationId(),
                            "the identifier every mapping row of the run names"),
                    () -> assertEquals(STARTED_AT, closed.getStartedAt(),
                            "when the run began reading"),
                    () -> assertEquals(FINISHED_AT, closed.getFinishedAt(),
                            "and when it finished rewriting"),
                    () -> assertEquals("1", closed.getFromVersion(),
                            "the version the rewritten tokens carried, which is what an operator"
                                    + " needs to tell one rotation from another"),
                    () -> assertEquals("2", closed.getToVersion(),
                            "and the version they carry now"),
                    () -> assertEquals(50, closed.getRowsRead(), "rows the run read"),
                    () -> assertEquals(3, closed.getRowsRewritten(), "rows it moved"),
                    () -> assertEquals(ACTOR, closed.getActor(),
                            "the identity the process ran under, which is the nearest thing to an"
                                    + " actor a start-up task has"));
        }

        /**
         * A run opens its record before its first rewrite, so the counts arrive at the close.
         *
         * <p>The order matters: a mapping row names its run, so the run has to exist before the first
         * mapping row is written, and the totals are not known until the last page has been read.
         */
        @Test
        @DisplayName("an opened row is closed with the figures the finished run measured")
        void anOpenedRowIsClosedWithTheMeasuredFigures() {
            CardTokenRotationEntity opened = new CardTokenRotationEntity(ROTATION_ID, STARTED_AT,
                    STARTED_AT, "1", "2", 0, 0, ACTOR);

            opened.complete(FINISHED_AT, 50, 3);

            assertAll(
                    () -> assertEquals(FINISHED_AT, opened.getFinishedAt(),
                            "the close records when the run ended"),
                    () -> assertEquals(50, opened.getRowsRead(), "and how many rows it read"),
                    () -> assertEquals(3, opened.getRowsRewritten(), "and how many it moved"));
        }

        @Test
        @DisplayName("a row cannot report more rewrites than reads, a negative count or a backward window")
        void aRowRefusesAnImpossibleClaim() {
            assertAll(
                    () -> assertThrows(IllegalArgumentException.class, () -> rotation(2, 3),
                            "a run cannot rewrite more rows than it read, and a row claiming so"
                                    + " would misreport how much of the table a rotation reached"),
                    () -> assertThrows(IllegalArgumentException.class, () -> rotation(-1, 0),
                            "a run reads no negative number of rows"),
                    () -> assertThrows(IllegalArgumentException.class,
                            () -> new CardTokenRotationEntity(ROTATION_ID, FINISHED_AT, STARTED_AT,
                                    "1", "2", 0, 0, ACTOR),
                            "a run cannot finish before it started"),
                    () -> assertThrows(IllegalArgumentException.class,
                            () -> rotation(50, 3).complete(STARTED_AT.minusSeconds(1), 50, 3),
                            "and the close is held to the same window as the opening"),
                    () -> assertThrows(NullPointerException.class,
                            () -> new CardTokenRotationEntity(ROTATION_ID, STARTED_AT, FINISHED_AT,
                                    "1", "2", 0, 0, null),
                            "a row that named no actor would record that something moved an"
                                    + " identity and not what"));
        }

        @Test
        @DisplayName("two rows are the same row when they name the same run")
        void identityIsTheRunIdentifier() {
            assertAll(
                    () -> assertEquals(rotation(50, 3), rotation(10, 1),
                            "the primary key is the run identifier alone"),
                    () -> assertEquals(rotation(50, 3).hashCode(), rotation(10, 1).hashCode(),
                            "and the hash follows the key"),
                    () -> assertNotEquals(rotation(50, 3),
                            new CardTokenRotationEntity(UUID.randomUUID(), STARTED_AT, FINISHED_AT,
                                    "1", "2", 50, 3, ACTOR),
                            "two runs are two rows"));
        }

        @Test
        @DisplayName("the rendering carries no token, no card number and no key")
        void theRenderingNamesNoCard() {
            String rendered = rotation(50, 3).toString();

            assertAll(
                    () -> assertTrue(rendered.contains(ROTATION_ID.toString()),
                            "an operator correlates a log line with a row by the run identifier"),
                    () -> assertFalse(rendered.contains(PREVIOUS_TOKEN),
                            "a token names a card as completely as a card number, so no rendering"
                                    + " may carry one"),
                    () -> assertFalse(rendered.contains(CURRENT_TOKEN),
                            "and that holds for the value the rotation moved to as well"));
        }
    }

    /**
     * The mapping row: the artifact the rest of the platform is re-keyed from.
     */
    @Nested
    @DisplayName("the mapping row the other stores are re-keyed from")
    class MappingRow {

        @Test
        @DisplayName("a row states which token became which, keyed on the value others hold")
        void aRowStatesWhichTokenBecameWhich() {
            CardTokenRotationMappingEntity mapped = mapping(PREVIOUS_TOKEN, CURRENT_TOKEN);

            assertAll(
                    () -> assertEquals(ROTATION_ID, mapped.getId().getRotationId(),
                            "a mapping row belongs to one run, so an operator can export one run's"
                                    + " mapping and no other"),
                    () -> assertEquals(PREVIOUS_TOKEN, mapped.getId().getPreviousCardToken(),
                            "the key is the value another store is still holding, because that is"
                                    + " the column a re-key statement joins on"),
                    () -> assertEquals(CURRENT_TOKEN, mapped.getCardToken(),
                            "and the row carries the value it has to hold afterwards"),
                    () -> assertEquals("1", mapped.getPreviousVersion(),
                            "the version the held value was taken under"),
                    () -> assertEquals("2", mapped.getVersion(), "and the version it moves to"));
        }

        @Test
        @DisplayName("a row that maps a token onto itself cannot be built")
        void aRowRefusesAMappingOntoItself() {
            IllegalArgumentException refused = assertThrows(IllegalArgumentException.class,
                    () -> mapping(PREVIOUS_TOKEN, PREVIOUS_TOKEN),
                    "a mapping onto itself re-keys nothing while reporting that it did, which is"
                            + " worse than an error because the statements read from it succeed");

            assertFalse(refused.getMessage().contains(PREVIOUS_TOKEN),
                    "and the refusal names no token");
        }

        @Test
        @DisplayName("a row refuses a value that is not a token of the declared shape")
        void aRowRefusesAValueThatIsNotAToken() {
            assertAll(
                    () -> assertThrows(IllegalArgumentException.class,
                            () -> mapping("4111111111111111", CURRENT_TOKEN),
                            "a card number in the previous-token column would put a Primary Account"
                                    + " Number in a table that exists so tokens can move without"
                                    + " one"),
                    () -> assertThrows(IllegalArgumentException.class,
                            () -> mapping(PREVIOUS_TOKEN, PREVIOUS_TOKEN.toUpperCase()),
                            "the shape is lower-case hexadecimal, and a case difference would make"
                                    + " a join miss the row it was written for"),
                    () -> assertThrows(NullPointerException.class,
                            () -> mapping(PREVIOUS_TOKEN, null),
                            "a mapping row that named one value states nothing"));
        }

        @Test
        @DisplayName("two rows are the same row when they name one run and one previous token")
        void identityIsTheCompositeKey() {
            CardTokenRotationMappingId key =
                    new CardTokenRotationMappingId(ROTATION_ID, PREVIOUS_TOKEN);

            assertAll(
                    () -> assertEquals(mapping(PREVIOUS_TOKEN, CURRENT_TOKEN),
                            mapping(PREVIOUS_TOKEN, "c".repeat(PanMasker.CARD_TOKEN_LENGTH)),
                            "the key is the run and the previous token, so one run maps one held"
                                    + " value once"),
                    () -> assertEquals(key, mapping(PREVIOUS_TOKEN, CURRENT_TOKEN).getId(),
                            "the key a caller builds equals the key the row carries"),
                    () -> assertEquals(key.hashCode(),
                            mapping(PREVIOUS_TOKEN, CURRENT_TOKEN).getId().hashCode(),
                            "and the hash follows it"),
                    () -> assertNotEquals(mapping(PREVIOUS_TOKEN, CURRENT_TOKEN),
                            mapping(CURRENT_TOKEN, PREVIOUS_TOKEN),
                            "two held values are two rows"));
        }

        @Test
        @DisplayName("the rendering carries a token suffix rather than either token")
        void theRenderingCarriesASuffixRatherThanAToken() {
            String rendered = mapping(PREVIOUS_TOKEN, CURRENT_TOKEN).toString();

            assertAll(
                    () -> assertFalse(rendered.contains(PREVIOUS_TOKEN),
                            "a log line carrying the value another store holds names that store's"
                                    + " card"),
                    () -> assertFalse(rendered.contains(CURRENT_TOKEN),
                            "and one carrying the new value names the same card"),
                    () -> assertTrue(rendered.contains("previousCardToken=..."),
                            "the rendering says it is showing part of a value rather than all of"
                                    + " it"),
                    () -> assertTrue(rendered.contains(ROTATION_ID.toString()),
                            "and names the run, which is what correlates it with the audit row"));
        }
    }
}
