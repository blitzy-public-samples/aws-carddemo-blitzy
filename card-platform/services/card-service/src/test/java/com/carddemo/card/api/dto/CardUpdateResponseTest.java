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
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

/**
 * Shape tests for {@link CardUpdateResponse} and for its nested
 * {@link CardUpdateResponse.RefreshedCard} snapshot.
 *
 * <p>Paragraph {@code 9300-CHECK-CHANGE-IN-REC} at {@code app/cbl/COCRDUPC.cbl:L1498}
 * re-reads the stored card and compares six saved values at lines 1503 to 1508. The card
 * verification value comes first, on line 1503. Line 1511 marks the record changed, and
 * lines 1512 to 1517 refresh all six values, the verification value on line 1512.</p>
 *
 * <p>{@code RefreshedCard} carries five of those six values. The tests below hold the
 * count at five, hold the five names, and hold the absence of the verification value
 * under any name. Source to target mapping for the six fields at lines 1503 to 1508, and
 * for {@code CARD-CVV-CD PIC 9(03)} at {@code app/cpy/CVACT02Y.cpy:L7} as a declared
 * omission: {@code card-platform/docs/traceability-matrix.md}.</p>
 *
 * <p>Lines 1499 to 1501 fold the freshly read embossed name to upper case before that
 * comparison, so a difference of letter case alone is no conflict.
 * {@code ConcurrentChangeDetector} runs the fold and the comparison, and no test here
 * exercises either.</p>
 *
 * <p>Two texts reach this record character for character. Line 208 supplies the conflict
 * text and line 188 the no-change text. Both sit under {@code WS-RETURN-MSG PIC X(75)} at
 * {@code app/cbl/COCRDUPC.cbl:L173}, one fixed-width field that holds one text.
 * {@code CCARD-RETURN-MSG} repeats that width at {@code app/cpy/CVCRD01Y.cpy:L29}.</p>
 *
 * <p>Rationale for the five-value snapshot, for detecting a conflict by field comparison
 * with no version column, and for scoping the digit scan to one fixture card:
 * {@code card-platform/docs/decision-log.md}. The six compared values against five
 * published values, as a finding:
 * {@code card-platform/docs/business-rule-flags.md}.</p>
 */
class CardUpdateResponseTest {

    /** Count of components {@link CardUpdateResponse} declares. */
    private static final int RESPONSE_COMPONENT_COUNT = 3;

    /** Count of components {@link CardUpdateResponse.RefreshedCard} declares. */
    private static final int SNAPSHOT_COMPONENT_COUNT = 5;

    /** Count of values {@link CardUpdateResponse.UpdateOutcome} declares. */
    private static final int OUTCOME_VALUE_COUNT = 7;

    /**
     * Card verification value of the first card in {@code app/data/ASCII/carddata.txt},
     * read at the {@code app/cpy/CVACT02Y.cpy:L7} offsets 28 through 30. One test scans a
     * snapshot of that single card for these digits.
     */
    private static final String FIRST_CARD_VERIFICATION_DIGITS = "747";

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
     * Asserts that a snapshot of one stored card carries none of that card's verification
     * digits. The five values come from the first card in
     * {@code app/data/ASCII/carddata.txt}. The {@code app/cpy/CVACT02Y.cpy} offsets place
     * the embossed name at 31 through 80, the expiry date at 81 through 90 and the active
     * status at 91.
     *
     * <p>The scan covers that one card. Within the first card the digits 747 occur once,
     * in the verification value at offsets 28 through 30.</p>
     */
    @Test
    void conflictSnapshotOfOneFixtureCardHoldsNoVerificationValueDigits() {
        CardUpdateResponse.RefreshedCard snapshot = new CardUpdateResponse.RefreshedCard(
                "Aniya Von", "2023", "03", "09", "Y");
        CardUpdateResponse response = CardUpdateResponse.changedBeforeUpdate(snapshot);

        for (RecordComponent component
                : CardUpdateResponse.RefreshedCard.class.getRecordComponents()) {
            String value = (String) read(component, snapshot);
            assertFalse(value.contains(FIRST_CARD_VERIFICATION_DIGITS),
                    "component " + component.getName() + " of the first card in "
                            + "app/data/ASCII/carddata.txt holds none of the digits "
                            + "refreshed at app/cbl/COCRDUPC.cbl:L1512");
        }
        assertFalse(response.toString().contains(FIRST_CARD_VERIFICATION_DIGITS),
                "the rendered response for the first card in "
                        + "app/data/ASCII/carddata.txt holds none of the digits refreshed "
                        + "at app/cbl/COCRDUPC.cbl:L1512");
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
                                "Aniya Von", "2023", "03", "09", "Y")),
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
                        "Aniya Von", "2023", "03", "09", "Y"));

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
