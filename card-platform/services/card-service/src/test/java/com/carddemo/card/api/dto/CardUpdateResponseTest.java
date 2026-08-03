package com.carddemo.card.api.dto;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.annotation.Annotation;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.RecordComponent;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

/**
 * Shape tests for {@link CardUpdateResponse} and for its nested
 * {@link CardUpdateResponse.RefreshedCard} snapshot.
 *
 * <p>Paragraph {@code 9300-CHECK-CHANGE-IN-REC} at {@code app/cbl/COCRDUPC.cbl:L1498} re-reads the
 * stored card and compares six saved values at lines 1503 to 1508. The card verification value
 * comes first, on line 1503. Line 1511 marks the record changed, and lines 1512 to 1517 refresh all
 * six values, the verification value on line 1512.</p>
 *
 * <p> {@code RefreshedCard} carries five of those six values. The tests below hold the count at
 * five, hold the five names, and hold the absence of the verification value under any name.</p>
 *
 * <p>Lines 1499 to 1501 fold the freshly read embossed name to upper case before that comparison,
 * so a difference of letter case alone is no conflict. The fold and the comparison are
 * orchestration and sit outside this record, and no test here exercises either.</p>
 *
 * <p>Two texts reach this record character for character. Line 208 supplies the conflict text and
 * line 188 the no-change text. Both sit under {@code WS-RETURN-MSG PIC X(75)} at
 * {@code app/cbl/COCRDUPC.cbl:L173}, one fixed-width field that holds one text.
 * {@code CCARD-RETURN-MSG} repeats that width at {@code app/cpy/CVCRD01Y.cpy:L29}.</p>
 */
class CardUpdateResponseTest {

    /** Count of components {@link CardUpdateResponse} declares. */
    private static final int RESPONSE_COMPONENT_COUNT = 3;

    /** Count of components {@link CardUpdateResponse.RefreshedCard} declares. */
    private static final int SNAPSHOT_COMPONENT_COUNT = 5;

    /** Count of values {@link CardUpdateResponse.UpdateOutcome} declares. */
    private static final int OUTCOME_VALUE_COUNT = 7;

    /**
     * A synthetic value at the width of {@code CARD-CVV-CD PIC 9(03)}, the field at
     * {@code app/cpy/CVACT02Y.cpy:L7} offsets 28 through 30. One test scans a snapshot for these
     * characters, and no component of that snapshot carries any of them.
     */
    private static final String SYNTHETIC_VERIFICATION_VALUE = "451";

    /**
     * Embossed name of a snapshot built here. Source
     * {@code CARD-EMBOSSED-NAME PIC X(50)} at {@code app/cpy/CVACT02Y.cpy:L8}, refreshed
     * at {@code app/cbl/COCRDUPC.cbl:L1513}.
     */
    private static final String SNAPSHOT_EMBOSSED_NAME = "JOHN Q PUBLIC";

    /**
     * Expiry year of a snapshot built here. Source {@code CARD-EXPIRAION-DATE(1:4)},
     * refreshed at {@code app/cbl/COCRDUPC.cbl:L1514}.
     */
    private static final String SNAPSHOT_EXPIRY_YEAR = "2023";

    /**
     * Expiry month of a snapshot built here. Source {@code CARD-EXPIRAION-DATE(6:2)},
     * refreshed at {@code app/cbl/COCRDUPC.cbl:L1515}.
     */
    private static final String SNAPSHOT_EXPIRY_MONTH = "03";

    /**
     * Expiry day of a snapshot built here. Source {@code CARD-EXPIRAION-DATE(9:2)},
     * refreshed at {@code app/cbl/COCRDUPC.cbl:L1516}.
     */
    private static final String SNAPSHOT_EXPIRY_DAY = "09";

    /**
     * Active status of a snapshot built here. Source
     * {@code CARD-ACTIVE-STATUS PIC X(01)} at {@code app/cpy/CVACT02Y.cpy:L10}, refreshed
     * at {@code app/cbl/COCRDUPC.cbl:L1517}.
     */
    private static final String SNAPSHOT_ACTIVE_STATUS = "Y";

    // Shape of the response. Three components carry the outcome, one message and the
    // snapshot.

    /**
     * Asserts that {@link CardUpdateResponse} is a record of three components, named and
     * ordered as the production record declares them.
     */
    @Test
    void responseDeclaresThreeComponentsNamedOutcomeMessageAndRefreshedCard() {
        assertTrue(CardUpdateResponse.class.isRecord(),
                "CardUpdateResponse is a record");
        assertEquals(RESPONSE_COMPONENT_COUNT,
                CardUpdateResponse.class.getRecordComponents().length,
                "CardUpdateResponse declares 3 components: the outcome, one message and "
                        + "the snapshot of app/cbl/COCRDUPC.cbl:L1512-L1517");
        assertEquals(List.of("outcome", "message", "refreshedCard"),
                componentNames(CardUpdateResponse.class),
                "CardUpdateResponse declares outcome, message and refreshedCard in that "
                        + "order");
    }

    /**
     * Asserts that the outcome component holds the nested enumeration
     * {@link CardUpdateResponse.UpdateOutcome}, declared inside the response record.
     */
    @Test
    void outcomeComponentHoldsTheNestedUpdateOutcomeEnum() {
        RecordComponent outcome = component(CardUpdateResponse.class, "outcome");
        assertSame(CardUpdateResponse.UpdateOutcome.class, outcome.getType(),
                "the outcome component holds CardUpdateResponse.UpdateOutcome");
        assertTrue(CardUpdateResponse.UpdateOutcome.class.isEnum(),
                "CardUpdateResponse.UpdateOutcome is an enumeration");
        assertSame(CardUpdateResponse.class,
                CardUpdateResponse.UpdateOutcome.class.getDeclaringClass(),
                "CardUpdateResponse declares UpdateOutcome as a nested type");
    }

    /**
     * Asserts the seven outcome values and their order, and asserts that the set is
     * closed. A name outside the seven reaches no value, so no caller can represent an
     * unknown outcome.
     */
    @Test
    void updateOutcomeDeclaresSevenValuesAndAdmitsNoOther() {
        assertEquals(List.of("UPDATED", "NO_CHANGE_DETECTED", "VALIDATION_REJECTED",
                        "CARD_NOT_FOUND", "CHANGED_BEFORE_UPDATE", "LOCK_NOT_ACQUIRED",
                        "UPDATE_FAILED_AFTER_LOCK"),
                outcomeNames(),
                "UpdateOutcome names the rewrite at app/cbl/COCRDUPC.cbl:L1477-L1483, the "
                        + "whole-group compare at lines 680 and 681, the field edits, the "
                        + "not-found branch at line 1400, the conflict at line 1511, the "
                        + "failed read for update at line 1446 and the failed rewrite at "
                        + "line 1491");
        assertEquals(OUTCOME_VALUE_COUNT,
                CardUpdateResponse.UpdateOutcome.values().length,
                "UpdateOutcome declares 7 values");
        assertThrows(IllegalArgumentException.class,
                () -> CardUpdateResponse.UpdateOutcome.valueOf("PARTIALLY_UPDATED"),
                "UpdateOutcome holds a closed set of 7 values and admits no eighth name");
    }

    /**
     * Asserts that the message component holds a plain {@code String} and stays empty on
     * the updated outcome. {@code WS-RETURN-MSG-OFF VALUE SPACES} at
     * {@code app/cbl/COCRDUPC.cbl:L174} names the empty state of the source field.
     */
    @Test
    void messageComponentIsANullableStringAndTheUpdatedOutcomeCarriesNone() {
        RecordComponent message = component(CardUpdateResponse.class, "message");
        assertSame(String.class, message.getType(),
                "the message component holds a String, matching WS-RETURN-MSG PIC X(75) "
                        + "at app/cbl/COCRDUPC.cbl:L173");

        CardUpdateResponse updated = CardUpdateResponse.updated();
        assertNull(updated.message(),
                "the rewrite at app/cbl/COCRDUPC.cbl:L1477-L1483 leaves WS-RETURN-MSG "
                        + "blank, so the updated outcome carries no message");
        assertFalse(updated.hasMessage(),
                "hasMessage reports the blank state named WS-RETURN-MSG-OFF at "
                        + "app/cbl/COCRDUPC.cbl:L174");

        CardUpdateResponse noChange = CardUpdateResponse.noChangeDetected();
        assertNotNull(noChange.message(),
                "app/cbl/COCRDUPC.cbl:L682 writes a text, so the no-change outcome "
                        + "carries one");
        assertTrue(noChange.hasMessage(),
                "hasMessage reports the filled state of WS-RETURN-MSG at "
                        + "app/cbl/COCRDUPC.cbl:L173");
    }

    /**
     * Asserts that the snapshot component holds the nested record
     * {@link CardUpdateResponse.RefreshedCard}, and holds no map, no collection and no
     * text.
     */
    @Test
    void refreshedCardComponentHoldsTheNestedSnapshotRecord() {
        RecordComponent refreshedCard = component(CardUpdateResponse.class, "refreshedCard");
        Class<?> type = refreshedCard.getType();
        assertSame(CardUpdateResponse.RefreshedCard.class, type,
                "the refreshedCard component holds CardUpdateResponse.RefreshedCard");
        assertTrue(CardUpdateResponse.RefreshedCard.class.isRecord(),
                "CardUpdateResponse.RefreshedCard is a record");
        assertSame(CardUpdateResponse.class,
                CardUpdateResponse.RefreshedCard.class.getDeclaringClass(),
                "CardUpdateResponse declares RefreshedCard as a nested type");
        assertFalse(Map.class.isAssignableFrom(type),
                "the snapshot of app/cbl/COCRDUPC.cbl:L1512-L1517 is a named record and "
                        + "holds no map");
        assertFalse(Collection.class.isAssignableFrom(type),
                "the snapshot of app/cbl/COCRDUPC.cbl:L1512-L1517 holds no collection");
        assertFalse(CharSequence.class.isAssignableFrom(type),
                "the snapshot of app/cbl/COCRDUPC.cbl:L1512-L1517 holds no text");
    }

    // Shape of the snapshot. Five components against the six values the source refreshes.

    /**
     * Asserts that the snapshot declares five components.
     * {@code app/cbl/COCRDUPC.cbl:L1512-L1517} refreshes six values, and line 1512
     * refreshes the card verification value.
     */
    @Test
    void refreshedCardDeclaresExactlyFiveComponents() {
        assertEquals(SNAPSHOT_COMPONENT_COUNT,
                CardUpdateResponse.RefreshedCard.class.getRecordComponents().length,
                "app/cbl/COCRDUPC.cbl:L1512-L1517 refreshes 6 values and RefreshedCard "
                        + "carries 5, leaving out the card verification value of line 1512");
    }

    /**
     * Asserts the five component names and their order, and asserts that each one holds
     * text. Lines 1513 to 1517 of {@code app/cbl/COCRDUPC.cbl} refresh the embossed name,
     * the year, the month, the day and the active status, in that order.
     */
    @Test
    void refreshedCardComponentNamesMatchTheFiveRefreshedFields() {
        assertEquals(List.of("embossedName", "expiryYear", "expiryMonth", "expiryDay",
                        "activeStatus"),
                componentNames(CardUpdateResponse.RefreshedCard.class),
                "app/cbl/COCRDUPC.cbl:L1513-L1517 refreshes the embossed name, the year "
                        + "slice, the month slice, the day slice and the active status");
        for (RecordComponent component
                : CardUpdateResponse.RefreshedCard.class.getRecordComponents()) {
            assertSame(String.class, component.getType(),
                    "app/cbl/COCRDUPC.cbl:L1505-L1507 compares character slices of "
                            + "CARD-EXPIRAION-DATE PIC X(10), so " + component.getName()
                            + " holds text");
        }
    }

    // Absences. Three properties the source would have supplied and the target omits.

    /**
     * Asserts that no property of the response or of the snapshot names the card
     * verification value. The card record declares {@code CARD-CVV-CD PIC 9(03)} at
     * {@code app/cpy/CVACT02Y.cpy:L7}, {@code app/cbl/COCRDUPC.cbl:L1503} compares it
     * first of the six values, and line 1512 refreshes it. The scan folds every
     * component, field and method name of both records and looks for five spellings.
     */
    @Test
    void conflictSnapshotOmitsCardVerificationValue() {
        List<String> needles =
                List.of("cvv", "cvc", "verification", "security", "threedigit");
        for (String property : propertyNames()) {
            for (String needle : needles) {
                assertFalse(property.contains(needle),
                        "app/cbl/COCRDUPC.cbl:L1512 refreshes CARD-CVV-CD and no property "
                                + "of CardUpdateResponse or RefreshedCard names it: "
                                + property);
            }
        }
    }

    /**
     * Asserts that a snapshot of one stored card carries no card verification value. The five
     * values are synthetic and sit at the {@code app/cpy/CVACT02Y.cpy} offsets that place the
     * embossed name at 31 through 80, the expiry date at 81 through 90 and the active status
     * at 91.
     *
     * <p>The scan covers that one snapshot, and no test here reads a fixture record.</p>
     */
    @Test
    void conflictSnapshotOfOneCardHoldsNoVerificationValue() {
        CardUpdateResponse.RefreshedCard snapshot = new CardUpdateResponse.RefreshedCard(
                "JOHN Q PUBLIC", "2023", "03", "09", "Y");
        CardUpdateResponse response = CardUpdateResponse.changedBeforeUpdate(snapshot);

        for (RecordComponent component
                : CardUpdateResponse.RefreshedCard.class.getRecordComponents()) {
            String value = (String) read(component, snapshot);
            assertFalse(value.contains(SYNTHETIC_VERIFICATION_VALUE),
                    "component " + component.getName() + " holds a card verification value, "
                            + "which app/cbl/COCRDUPC.cbl:L1512 refreshes and this response omits");
        }
        assertFalse(response.toString().contains(SYNTHETIC_VERIFICATION_VALUE),
                "the rendered response holds a card verification value, which "
                        + "app/cbl/COCRDUPC.cbl:L1512 refreshes and this response omits");
    }

    /**
     * Asserts that neither the response nor the snapshot declares a concurrency token.
     * {@code app/cbl/COCRDUPC.cbl:L1503-L1508} detects a competing write by comparing six
     * saved values, and the card record at {@code app/cpy/CVACT02Y.cpy} holds no version,
     * no sequence and no timestamp.
     */
    @Test
    void neitherResponseNorSnapshotDeclaresAVersionProperty() {
        List<String> needles =
                List.of("version", "revision", "etag", "sequence", "timestamp");
        for (String property : propertyNames()) {
            for (String needle : needles) {
                assertFalse(property.contains(needle),
                        "app/cbl/COCRDUPC.cbl:L1503-L1508 compares six saved values, and "
                                + "neither CardUpdateResponse nor RefreshedCard declares a "
                                + "concurrency token: " + property);
            }
        }
    }

    /**
     * Asserts that no annotation named {@code Version} sits on either record, on their
     * components, on their fields or on their accessors. A second assertion holds that the
     * record components carry no annotation at all. Both records are payload types and
     * reach no persistence provider.
     */
    @Test
    void noComponentCarriesAVersionAnnotation() {
        for (Annotation annotation : annotations()) {
            assertNotEquals("Version", annotation.annotationType().getSimpleName(),
                    "CardUpdateResponse and RefreshedCard carry no Version annotation");
        }
        for (Class<?> record : payloadRecords()) {
            for (RecordComponent component : record.getRecordComponents()) {
                assertEquals(0, component.getAnnotations().length,
                        "component " + component.getName() + " of "
                                + record.getSimpleName() + " carries no annotation");
            }
        }
    }

    /**
     * Asserts one message slot and no collection of messages.
     * {@code WS-RETURN-MSG PIC X(75)} at {@code app/cbl/COCRDUPC.cbl:L173} is one
     * fixed-width field, and {@code CCARD-RETURN-MSG} repeats that width at
     * {@code app/cpy/CVCRD01Y.cpy:L29}. Fifteen guarded writes in the card update program
     * keep the first failing text and drop the rest.
     */
    @Test
    void responseCarriesOneMessageAndNoCollectionOfMessages() {
        int messageSlots = 0;
        for (String name : componentNames(CardUpdateResponse.class)) {
            if (fold(name).contains("message")) {
                messageSlots++;
            }
        }
        assertEquals(1, messageSlots,
                "app/cbl/COCRDUPC.cbl:L173 declares one message field and "
                        + "CardUpdateResponse declares one message component");

        for (Class<?> record : payloadRecords()) {
            for (RecordComponent component : record.getRecordComponents()) {
                Class<?> type = component.getType();
                String slot = record.getSimpleName() + "." + component.getName();
                assertFalse(Collection.class.isAssignableFrom(type),
                        "app/cbl/COCRDUPC.cbl:L173 holds one text, so " + slot
                                + " holds no collection");
                assertFalse(Map.class.isAssignableFrom(type),
                        "app/cbl/COCRDUPC.cbl:L173 holds one text, so " + slot
                                + " holds no map");
                assertFalse(type.isArray(),
                        "app/cbl/COCRDUPC.cbl:L173 holds one text, so " + slot
                                + " holds no array");
            }
        }

        for (String property : propertyNames()) {
            assertFalse(property.contains("errors"),
                    "app/cbl/COCRDUPC.cbl:L173 holds one text and no property of "
                            + "CardUpdateResponse or RefreshedCard names a set of errors: "
                            + property);
            assertFalse(property.contains("violation"),
                    "app/cbl/COCRDUPC.cbl:L173 holds one text and no property of "
                            + "CardUpdateResponse or RefreshedCard names a violation: "
                            + property);
        }
    }

    /**
     * Asserts that the conflict outcome is the one outcome carrying a snapshot.
     * {@code app/cbl/COCRDUPC.cbl:L1511-L1517} refreshes the saved values on that branch
     * alone. The two rejected constructions cover a missing snapshot and a stray one.
     */
    @Test
    void onlyTheConflictOutcomeCarriesASnapshot() {
        List<CardUpdateResponse> withoutSnapshot = List.of(
                CardUpdateResponse.updated(),
                CardUpdateResponse.noChangeDetected(),
                CardUpdateResponse.validationRejected(
                        CardValidationMessages.CARD_STATUS_MUST_BE_YES_NO),
                CardUpdateResponse.cardNotFound(),
                CardUpdateResponse.lockNotAcquired(),
                CardUpdateResponse.updateFailedAfterLock());
        for (CardUpdateResponse response : withoutSnapshot) {
            assertNull(response.refreshedCard(),
                    "app/cbl/COCRDUPC.cbl:L1512-L1517 runs on the conflict branch alone, "
                            + "so the " + response.outcome() + " outcome carries no "
                            + "snapshot");
            assertFalse(response.hasRefreshedCard(),
                    "hasRefreshedCard reports no snapshot for the " + response.outcome()
                            + " outcome");
        }

        assertThrows(IllegalArgumentException.class,
                () -> new CardUpdateResponse(
                        CardUpdateResponse.UpdateOutcome.CHANGED_BEFORE_UPDATE,
                        CardValidationMessages.DATA_WAS_CHANGED_BEFORE_UPDATE,
                        null),
                "app/cbl/COCRDUPC.cbl:L1512-L1517 always refreshes, so the conflict "
                        + "outcome requires a snapshot");
        assertThrows(IllegalArgumentException.class,
                () -> new CardUpdateResponse(
                        CardUpdateResponse.UpdateOutcome.UPDATED,
                        null,
                        new CardUpdateResponse.RefreshedCard(
                                SNAPSHOT_EMBOSSED_NAME, "2023", "03", "09", "Y")),
                "app/cbl/COCRDUPC.cbl:L1509 refreshes nothing on the matching branch, so "
                        + "the updated outcome takes no snapshot");
    }

    // The two texts this record carries. Each expected value is typed here and compared
    // for exact equality.

    /**
     * Asserts the conflict text of {@code DATA-WAS-CHANGED-BEFORE-UPDATE}, declared at
     * {@code app/cbl/COCRDUPC.cbl:L208} and set at line 1511. The second assertion holds
     * the constant against the same literal, so a drift between the two fails here.
     */
    @Test
    void conflictOutcomeCarriesTheTextFromLine208() {
        CardUpdateResponse response = CardUpdateResponse.changedBeforeUpdate(
                new CardUpdateResponse.RefreshedCard(
                        SNAPSHOT_EMBOSSED_NAME, "2023", "03", "09", "Y"));

        assertSame(CardUpdateResponse.UpdateOutcome.CHANGED_BEFORE_UPDATE,
                response.outcome(),
                "app/cbl/COCRDUPC.cbl:L1511 marks the record changed before the update");
        assertEquals("Record changed by some one else. Please review", response.message(),
                "app/cbl/COCRDUPC.cbl:L208 reads: "
                        + "Record changed by some one else. Please review");
        assertEquals("Record changed by some one else. Please review",
                CardValidationMessages.DATA_WAS_CHANGED_BEFORE_UPDATE,
                "app/cbl/COCRDUPC.cbl:L208 reads: "
                        + "Record changed by some one else. Please review");
        assertTrue(response.hasRefreshedCard(),
                "app/cbl/COCRDUPC.cbl:L1512-L1517 refreshes the saved values on this "
                        + "branch");
    }

    /**
     * Asserts the no-change text of {@code NO-CHANGES-DETECTED}, declared at
     * {@code app/cbl/COCRDUPC.cbl:L188} and set at line 682, including its trailing full
     * stop. The second assertion holds the constant against the same literal, so a drift
     * between the two fails here.
     */
    @Test
    void noChangeOutcomeCarriesTheTextFromLine188() {
        CardUpdateResponse response = CardUpdateResponse.noChangeDetected();

        assertSame(CardUpdateResponse.UpdateOutcome.NO_CHANGE_DETECTED, response.outcome(),
                "app/cbl/COCRDUPC.cbl:L680-L681 compares the new and the old card group "
                        + "whole and line 682 sets the text");
        assertEquals("No change detected with respect to values fetched.",
                response.message(),
                "app/cbl/COCRDUPC.cbl:L188 reads: "
                        + "No change detected with respect to values fetched.");
        assertEquals("No change detected with respect to values fetched.",
                CardValidationMessages.NO_CHANGES_DETECTED,
                "app/cbl/COCRDUPC.cbl:L188 reads: "
                        + "No change detected with respect to values fetched.");
        assertNull(response.refreshedCard(),
                "app/cbl/COCRDUPC.cbl:L682 writes a text and refreshes no saved value");
    }

    /**
     * Asserts the not-found text of {@code DID-NOT-FIND-ACCTCARD-COMBO}, declared at
     * {@code app/cbl/COCRDUPC.cbl:L204} and set at line 1400 under the guard on line
     * 1399. The second assertion holds the constant against the same literal, so a drift
     * between the two fails here.
     */
    @Test
    void notFoundOutcomeCarriesTheTextFromLine204() {
        CardUpdateResponse response = CardUpdateResponse.cardNotFound();

        assertSame(CardUpdateResponse.UpdateOutcome.CARD_NOT_FOUND, response.outcome(),
                "app/cbl/COCRDUPC.cbl:L1400 reports no card for the numbers supplied");
        assertEquals("Did not find cards for this search condition", response.message(),
                "app/cbl/COCRDUPC.cbl:L204 reads: "
                        + "Did not find cards for this search condition");
        assertEquals("Did not find cards for this search condition",
                CardValidationMessages.DID_NOT_FIND_ACCTCARD_COMBO,
                "app/cbl/COCRDUPC.cbl:L204 reads: "
                        + "Did not find cards for this search condition");
        assertTrue(response.hasMessage(),
                "hasMessage reports the filled state of WS-RETURN-MSG at "
                        + "app/cbl/COCRDUPC.cbl:L173");
        assertNull(response.refreshedCard(),
                "app/cbl/COCRDUPC.cbl:L1400 refreshes no saved value");
    }

    /**
     * Asserts the lock text of {@code COULD-NOT-LOCK-FOR-UPDATE}, declared at
     * {@code app/cbl/COCRDUPC.cbl:L206} and set at line 1446 under the guard on line
     * 1445. The second assertion holds the constant against the same literal, so a drift
     * between the two fails here.
     */
    @Test
    void lockOutcomeCarriesTheTextFromLine206() {
        CardUpdateResponse response = CardUpdateResponse.lockNotAcquired();

        assertSame(CardUpdateResponse.UpdateOutcome.LOCK_NOT_ACQUIRED, response.outcome(),
                "app/cbl/COCRDUPC.cbl:L1441 tests the read for update and line 1446 "
                        + "reports its failure");
        assertEquals("Could not lock record for update", response.message(),
                "app/cbl/COCRDUPC.cbl:L206 reads: Could not lock record for update");
        assertEquals("Could not lock record for update",
                CardValidationMessages.COULD_NOT_LOCK_FOR_UPDATE,
                "app/cbl/COCRDUPC.cbl:L206 reads: Could not lock record for update");
        assertTrue(response.hasMessage(),
                "hasMessage reports the filled state of WS-RETURN-MSG at "
                        + "app/cbl/COCRDUPC.cbl:L173");
        assertNull(response.refreshedCard(),
                "app/cbl/COCRDUPC.cbl:L1446 refreshes no saved value");
    }

    /**
     * Asserts the rewrite-failure text of {@code LOCKED-BUT-UPDATE-FAILED}, declared at
     * {@code app/cbl/COCRDUPC.cbl:L210} and set at line 1491 in a bare {@code ELSE} with
     * no guard. The second assertion holds the constant against the same literal, so a
     * drift between the two fails here.
     */
    @Test
    void updateFailureOutcomeCarriesTheTextFromLine210() {
        CardUpdateResponse response = CardUpdateResponse.updateFailedAfterLock();

        assertSame(CardUpdateResponse.UpdateOutcome.UPDATE_FAILED_AFTER_LOCK,
                response.outcome(),
                "app/cbl/COCRDUPC.cbl:L1488 tests the rewrite and line 1491 reports its "
                        + "failure");
        assertEquals("Update of record failed", response.message(),
                "app/cbl/COCRDUPC.cbl:L210 reads: Update of record failed");
        assertEquals("Update of record failed",
                CardValidationMessages.LOCKED_BUT_UPDATE_FAILED,
                "app/cbl/COCRDUPC.cbl:L210 reads: Update of record failed");
        assertTrue(response.hasMessage(),
                "hasMessage reports the filled state of WS-RETURN-MSG at "
                        + "app/cbl/COCRDUPC.cbl:L173");
        assertNull(response.refreshedCard(),
                "app/cbl/COCRDUPC.cbl:L1491 refreshes no saved value");
    }

    /**
     * Asserts that a rejected field edit carries the text it is given, character for
     * character, for each of the five edits {@code CardUpdateRequest} can fail. The
     * fifteen {@code IF WS-RETURN-MSG-OFF} guards in {@code app/cbl/COCRDUPC.cbl} keep
     * the first failing text, and the caller supplies it here.
     */
    @Test
    void aRejectedFieldEditCarriesTheTextItIsGiven() {
        Map<String, String> editTexts = Map.of(
                "app/cbl/COCRDUPC.cbl:L182", "Card name not provided",
                "app/cbl/COCRDUPC.cbl:L184",
                "Card name can only contain alphabets and spaces",
                "app/cbl/COCRDUPC.cbl:L196", "Card Active Status must be Y or N",
                "app/cbl/COCRDUPC.cbl:L198", "Card expiry month must be between 1 and 12",
                "app/cbl/COCRDUPC.cbl:L200", "Invalid card expiry year");

        for (Map.Entry<String, String> edit : editTexts.entrySet()) {
            CardUpdateResponse response =
                    CardUpdateResponse.validationRejected(edit.getValue());

            assertSame(CardUpdateResponse.UpdateOutcome.VALIDATION_REJECTED,
                    response.outcome(),
                    "a failing field edit rejects the payload, and the text comes from "
                            + edit.getKey());
            assertEquals(edit.getValue(), response.message(),
                    "the response carries the text of " + edit.getKey()
                            + " character for character");
            assertTrue(response.hasMessage(),
                    "hasMessage reports the filled state of WS-RETURN-MSG at "
                            + "app/cbl/COCRDUPC.cbl:L173 for " + edit.getKey());
            assertNull(response.refreshedCard(),
                    "a failing field edit writes nothing, so " + edit.getKey()
                            + " refreshes no saved value");
        }
    }

    /**
     * Asserts that the rejection factory refuses a missing text. The fifteen guards in
     * {@code app/cbl/COCRDUPC.cbl} reach this outcome only after one edit has set a text,
     * so a rejection with no text names no failing edit.
     */
    @Test
    void theRejectionFactoryRefusesAMissingText() {
        NullPointerException refusal = assertThrows(NullPointerException.class,
                () -> CardUpdateResponse.validationRejected(null),
                "a rejection with no text names no failing edit and is refused");
        assertEquals("firstFailingMessage is required", refusal.getMessage(),
                "the refusal names the missing argument");
    }

    /**
     * Asserts that the conflict factory refuses a missing snapshot.
     * {@code app/cbl/COCRDUPC.cbl:L1512-L1517} always refreshes the saved values on that
     * branch, so the outcome never arrives without them.
     */
    @Test
    void theConflictFactoryRefusesAMissingSnapshot() {
        NullPointerException refusal = assertThrows(NullPointerException.class,
                () -> CardUpdateResponse.changedBeforeUpdate(null),
                "app/cbl/COCRDUPC.cbl:L1512-L1517 always refreshes, so the conflict "
                        + "outcome requires a snapshot");
        assertEquals("refreshedCard is required", refusal.getMessage(),
                "the refusal names the missing argument");
    }

    /**
     * Asserts that every snapshot value is required, one null at a time.
     * {@code app/cbl/COCRDUPC.cbl:L1513-L1517} moves a value into each saved field, so no
     * field of a refreshed snapshot is ever left empty.
     */
    @Test
    void eachSnapshotValueIsRequired() {
        Map<String, Runnable> constructions = new LinkedHashMap<>();
        constructions.put("embossedName", () -> new CardUpdateResponse.RefreshedCard(
                null, SNAPSHOT_EXPIRY_YEAR, SNAPSHOT_EXPIRY_MONTH, SNAPSHOT_EXPIRY_DAY,
                SNAPSHOT_ACTIVE_STATUS));
        constructions.put("expiryYear", () -> new CardUpdateResponse.RefreshedCard(
                SNAPSHOT_EMBOSSED_NAME, null, SNAPSHOT_EXPIRY_MONTH, SNAPSHOT_EXPIRY_DAY,
                SNAPSHOT_ACTIVE_STATUS));
        constructions.put("expiryMonth", () -> new CardUpdateResponse.RefreshedCard(
                SNAPSHOT_EMBOSSED_NAME, SNAPSHOT_EXPIRY_YEAR, null, SNAPSHOT_EXPIRY_DAY,
                SNAPSHOT_ACTIVE_STATUS));
        constructions.put("expiryDay", () -> new CardUpdateResponse.RefreshedCard(
                SNAPSHOT_EMBOSSED_NAME, SNAPSHOT_EXPIRY_YEAR, SNAPSHOT_EXPIRY_MONTH, null,
                SNAPSHOT_ACTIVE_STATUS));
        constructions.put("activeStatus", () -> new CardUpdateResponse.RefreshedCard(
                SNAPSHOT_EMBOSSED_NAME, SNAPSHOT_EXPIRY_YEAR, SNAPSHOT_EXPIRY_MONTH,
                SNAPSHOT_EXPIRY_DAY, null));

        assertEquals(SNAPSHOT_COMPONENT_COUNT, constructions.size(),
                "one construction covers each of the five values refreshed at "
                        + "app/cbl/COCRDUPC.cbl:L1513-L1517");
        for (Map.Entry<String, Runnable> construction : constructions.entrySet()) {
            String component = construction.getKey();
            NullPointerException refusal = assertThrows(NullPointerException.class,
                    () -> construction.getValue().run(),
                    "app/cbl/COCRDUPC.cbl:L1513-L1517 moves a value into every saved "
                            + "field, so the snapshot requires " + component);
            assertEquals(component + " is required", refusal.getMessage(),
                    "the refusal names the missing component " + component);
        }
    }

    /**
     * Asserts, across all seven outcomes, the exact text each carries and whether it
     * carries a snapshot. Each expected text is typed here and each is compared for exact
     * equality, so this test holds the whole outcome matrix in one place.
     *
     * <p>The two helpers this test calls switch over
     * {@link CardUpdateResponse.UpdateOutcome} without a default arm, so an eighth
     * outcome fails compilation rather than escaping coverage.</p>
     */
    @Test
    void everyOutcomeHoldsItsOwnTextAndSnapshotState() {
        CardUpdateResponse.UpdateOutcome[] outcomes =
                CardUpdateResponse.UpdateOutcome.values();
        assertEquals(OUTCOME_VALUE_COUNT, outcomes.length,
                "the matrix below covers every outcome CardUpdateResponse can report");

        for (CardUpdateResponse.UpdateOutcome outcome : outcomes) {
            CardUpdateResponse response = responseFor(outcome);
            String expectedText = expectedMessage(outcome);
            boolean carriesSnapshot =
                    outcome == CardUpdateResponse.UpdateOutcome.CHANGED_BEFORE_UPDATE;

            assertSame(outcome, response.outcome(),
                    "the factory for " + outcome + " reports that outcome");
            assertEquals(expectedText, response.message(),
                    "the " + outcome + " outcome carries the text of its source line "
                            + "character for character");
            assertEquals(expectedText != null, response.hasMessage(),
                    "hasMessage reports the state of WS-RETURN-MSG at "
                            + "app/cbl/COCRDUPC.cbl:L173 for the " + outcome
                            + " outcome");
            assertEquals(carriesSnapshot, response.hasRefreshedCard(),
                    "app/cbl/COCRDUPC.cbl:L1512-L1517 refreshes on the conflict branch "
                            + "alone, and this outcome is " + outcome);
            assertEquals(carriesSnapshot, response.refreshedCard() != null,
                    "hasRefreshedCard agrees with the snapshot component for the "
                            + outcome + " outcome");
        }
    }

    /**
     * Asserts that a text of only white space is refused where the outcome carries the text of the
     * failing edit, and that the empty state {@code WS-RETURN-MSG-OFF VALUE SPACES} at
     * {@code app/cbl/COCRDUPC.cbl:L174} names belongs to the one outcome that carries no message.
     *
     * <p>A screen field can hold spaces and say nothing. A response cannot: a caller that reads
     * {@code VALIDATION_REJECTED} with a blank message learns that the update failed and never
     * learns which edit failed.</p>
     */
    @Test
    void aBlankTextIsRefusedWhereTheOutcomeCarriesTheFailingEditText() {
        for (String blank : List.of("", " ", "\t", "     ")) {
            IllegalArgumentException refusal = assertThrows(IllegalArgumentException.class,
                    () -> new CardUpdateResponse(
                            CardUpdateResponse.UpdateOutcome.VALIDATION_REJECTED, blank, null),
                    "a text of width " + blank.length() + " holding only white space names no "
                            + "failing edit");
            assertEquals("VALIDATION_REJECTED requires the text of the failing edit",
                    refusal.getMessage(),
                    "the refusal names the outcome and what it requires");
        }

        CardUpdateResponse updated =
                new CardUpdateResponse(CardUpdateResponse.UpdateOutcome.UPDATED, null, null);
        assertFalse(updated.hasMessage(),
                "hasMessage reports the state named WS-RETURN-MSG-OFF at "
                        + "app/cbl/COCRDUPC.cbl:L174 for the one outcome that carries no message");
        assertNull(updated.message(), "the message slot of an updated response stays empty");

        CardUpdateResponse filled = new CardUpdateResponse(
                CardUpdateResponse.UpdateOutcome.VALIDATION_REJECTED, "Invalid card expiry year",
                null);
        assertTrue(filled.hasMessage(),
                "hasMessage reports the filled state of WS-RETURN-MSG at "
                        + "app/cbl/COCRDUPC.cbl:L173");
    }

    /**
     * Asserts that the response refuses a missing outcome. Every branch of
     * {@code 9200-WRITE-PROCESSING} in {@code app/cbl/COCRDUPC.cbl} reaches exactly one
     * outcome, so no response exists without one.
     */
    @Test
    void theResponseRefusesAMissingOutcome() {
        NullPointerException refusal = assertThrows(NullPointerException.class,
                () -> new CardUpdateResponse(null, null, null),
                "every branch of app/cbl/COCRDUPC.cbl:L1435-L1520 reaches one outcome, so "
                        + "the response requires one");
        assertEquals("outcome is required", refusal.getMessage(),
                "the refusal names the missing component");
    }

    // Outcome matrix helpers. Both switch over UpdateOutcome with no default arm, so an
    // eighth outcome fails compilation.

    /** Returns the response the factory for one outcome builds. */
    private static CardUpdateResponse responseFor(
            CardUpdateResponse.UpdateOutcome outcome) {
        return switch (outcome) {
            case UPDATED -> CardUpdateResponse.updated();
            case NO_CHANGE_DETECTED -> CardUpdateResponse.noChangeDetected();
            case VALIDATION_REJECTED ->
                    CardUpdateResponse.validationRejected("Card Active Status must be Y or N");
            case CARD_NOT_FOUND -> CardUpdateResponse.cardNotFound();
            case CHANGED_BEFORE_UPDATE ->
                    CardUpdateResponse.changedBeforeUpdate(sampleSnapshot());
            case LOCK_NOT_ACQUIRED -> CardUpdateResponse.lockNotAcquired();
            case UPDATE_FAILED_AFTER_LOCK -> CardUpdateResponse.updateFailedAfterLock();
        };
    }

    /**
     * Returns the text one outcome carries, typed from the source line that declares it.
     * Null names the empty state {@code WS-RETURN-MSG-OFF} at
     * {@code app/cbl/COCRDUPC.cbl:L174}.
     */
    private static String expectedMessage(CardUpdateResponse.UpdateOutcome outcome) {
        return switch (outcome) {
            case UPDATED -> null;
            case NO_CHANGE_DETECTED -> "No change detected with respect to values fetched.";
            case VALIDATION_REJECTED -> "Card Active Status must be Y or N";
            case CARD_NOT_FOUND -> "Did not find cards for this search condition";
            case CHANGED_BEFORE_UPDATE -> "Record changed by some one else. Please review";
            case LOCK_NOT_ACQUIRED -> "Could not lock record for update";
            case UPDATE_FAILED_AFTER_LOCK -> "Update of record failed";
        };
    }

    /** Returns a snapshot holding one value in each of the five components. */
    private static CardUpdateResponse.RefreshedCard sampleSnapshot() {
        return new CardUpdateResponse.RefreshedCard(
                SNAPSHOT_EMBOSSED_NAME, SNAPSHOT_EXPIRY_YEAR, SNAPSHOT_EXPIRY_MONTH,
                SNAPSHOT_EXPIRY_DAY, SNAPSHOT_ACTIVE_STATUS);
    }


    // Reflection helpers.

    /** Returns the two payload records this class covers. */
    private static List<Class<?>> payloadRecords() {
        return List.of(CardUpdateResponse.class, CardUpdateResponse.RefreshedCard.class);
    }

    /** Returns the component names of a record type in declared order. */
    private static List<String> componentNames(Class<?> record) {
        List<String> names = new ArrayList<>();
        for (RecordComponent component : record.getRecordComponents()) {
            names.add(component.getName());
        }
        return names;
    }

    /**
     * Returns one named component of a record type.
     *
     * @throws AssertionError when the record declares no component of that name
     */
    private static RecordComponent component(Class<?> record, String name) {
        for (RecordComponent candidate : record.getRecordComponents()) {
            if (candidate.getName().equals(name)) {
                return candidate;
            }
        }
        throw new AssertionError(
                record.getSimpleName() + " declares no component named " + name);
    }

    /** Returns the names of the outcome values in declared order. */
    private static List<String> outcomeNames() {
        List<String> names = new ArrayList<>();
        for (CardUpdateResponse.UpdateOutcome outcome
                : CardUpdateResponse.UpdateOutcome.values()) {
            names.add(outcome.name());
        }
        return names;
    }

    /**
     * Returns the folded names of every component, field and method the two payload
     * records declare.
     */
    private static List<String> propertyNames() {
        List<String> names = new ArrayList<>();
        for (Class<?> record : payloadRecords()) {
            for (RecordComponent component : record.getRecordComponents()) {
                names.add(fold(component.getName()));
            }
            for (Field field : record.getDeclaredFields()) {
                names.add(fold(field.getName()));
            }
            for (Method method : record.getDeclaredMethods()) {
                names.add(fold(method.getName()));
            }
        }
        return names;
    }

    /**
     * Returns every annotation the two payload records carry on themselves, on their
     * components, on their fields and on their methods.
     */
    private static List<Annotation> annotations() {
        List<Annotation> found = new ArrayList<>();
        for (Class<?> record : payloadRecords()) {
            found.addAll(List.of(record.getDeclaredAnnotations()));
            for (RecordComponent component : record.getRecordComponents()) {
                found.addAll(List.of(component.getAnnotations()));
            }
            for (Field field : record.getDeclaredFields()) {
                found.addAll(List.of(field.getDeclaredAnnotations()));
            }
            for (Method method : record.getDeclaredMethods()) {
                found.addAll(List.of(method.getDeclaredAnnotations()));
            }
        }
        return found;
    }

    /**
     * Lowers a name and drops every character outside the letters and digits, so
     * {@code securityCode} and {@code security_code} fold alike.
     */
    private static String fold(String name) {
        StringBuilder folded = new StringBuilder(name.length());
        for (int index = 0; index < name.length(); index++) {
            char character = name.charAt(index);
            if (Character.isLetterOrDigit(character)) {
                folded.append(Character.toLowerCase(character));
            }
        }
        return folded.toString();
    }

    /**
     * Reads one component through its accessor. Every accessor is public on a public
     * record in this package, and the read never fails.
     */
    private static Object read(RecordComponent component, Object target) {
        try {
            return component.getAccessor().invoke(target);
        } catch (ReflectiveOperationException exception) {
            throw new AssertionError(
                    "component " + component.getName() + " is not readable", exception);
        }
    }

}
