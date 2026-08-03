package com.carddemo.fraud.messaging;

import java.lang.annotation.Annotation;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.RecordComponent;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Contract tests for {@link DeadLetterMetadata}, the four values that travel with a message routed
 * to the dead-letter topic.
 *
 * <p>ADDITIVE IN FULL: net new; no COBOL ancestor. The record declares four {@link String}
 * components capped at 4, 8, 50 and 72 characters, which total 134.
 *
 * <p>The tests assert the component list and the four widths. A value over its maximum keeps its
 * leading characters, a {@code null} value becomes the empty string, and no fifth component or
 * framework annotation exists. They build the record directly and reach no file, context or broker.
 * The width mapping appears in {@code card-platform/docs/traceability-matrix.md}.
 */
@DisplayName("DeadLetterMetadata, the four values that travel to the dead-letter topic")
final class DeadLetterMetadataTest {

    /** The component that holds the failure code. */
    private static final String COMPONENT_FAILURE_CODE = "abendCode";

    /** The component that names the failing part. */
    private static final String COMPONENT_CULPRIT = "culprit";

    /** The component that records what failed. */
    private static final String COMPONENT_REASON = "reason";

    /** The component that carries text for whoever reads the dead-letter topic. */
    private static final String COMPONENT_MESSAGE = "message";

    /** The four component names, in the order {@link DeadLetterMetadata} declares them. */
    private static final List<String> EXPECTED_COMPONENT_NAMES = List.of(
            COMPONENT_FAILURE_CODE, COMPONENT_CULPRIT, COMPONENT_REASON, COMPONENT_MESSAGE);

    /** The record declares four components and no fifth. */
    private static final int EXPECTED_COMPONENT_COUNT = 4;

    /** The four maximum lengths, in component order. */
    private static final List<Integer> MAXIMUM_LENGTHS = List.of(
            DeadLetterMetadata.CODE_MAX_LENGTH,
            DeadLetterMetadata.CULPRIT_MAX_LENGTH,
            DeadLetterMetadata.REASON_MAX_LENGTH,
            DeadLetterMetadata.MESSAGE_MAX_LENGTH);

    /** The total the four maximum lengths add up to. */
    private static final int EXPECTED_TOTAL_LENGTH = 134;

    /** Fragments that appear in no component name. */
    private static final List<String> FORBIDDEN_NAME_FRAGMENTS = List.of(
            "time", "stack", "payload", "offset", "partition", "topic", "key", "retry");

    /** Package prefixes of the frameworks that annotate no part of the record. */
    private static final List<String> FRAMEWORK_PACKAGE_PREFIXES = List.of(
            "tools.jackson", "com.fasterxml.jackson", "org.springframework", "jakarta.persistence");

    /** The count of letters cycled to build a value whose characters vary along its length. */
    private static final int ALPHABET_SIZE = 26;

    /** A value short enough for every one of the four components. */
    private static final String SHORT_VALUE = "abc";

    /**
     * Asserts that the record declares four components, in a fixed order, each typed
     * {@link String}.
     */
    @Test
    @DisplayName("Declares exactly four String components, in the order the record lists them")
    void declaresFourStringComponentsInOrder() {
        RecordComponent[] components = DeadLetterMetadata.class.getRecordComponents();
        List<String> names = Arrays.stream(components).map(RecordComponent::getName).toList();
        List<String> typeNames = Arrays.stream(components)
                .map(component -> component.getType().getName()).toList();

        assertAll("component list of DeadLetterMetadata",
                () -> assertEquals(EXPECTED_COMPONENT_COUNT, components.length, "component count"),
                () -> assertEquals(EXPECTED_COMPONENT_NAMES, names, "names in index order"),
                () -> assertEquals(
                        Collections.nCopies(EXPECTED_COMPONENT_COUNT, String.class.getName()),
                        typeNames, "types in index order"));
    }

    /** Asserts the four maximum lengths and the total they add up to. */
    @Test
    @DisplayName("Caps the four components at 4, 8, 50 and 72 characters, which total 134")
    void capsEachComponentAtItsDeclaredWidth() {
        assertAll("maximum lengths",
                () -> assertEquals(4, DeadLetterMetadata.CODE_MAX_LENGTH, "first maximum"),
                () -> assertEquals(8, DeadLetterMetadata.CULPRIT_MAX_LENGTH, "second maximum"),
                () -> assertEquals(50, DeadLetterMetadata.REASON_MAX_LENGTH, "third maximum"),
                () -> assertEquals(72, DeadLetterMetadata.MESSAGE_MAX_LENGTH, "fourth maximum"),
                () -> assertEquals(EXPECTED_TOTAL_LENGTH,
                        DeadLetterMetadata.CODE_MAX_LENGTH + DeadLetterMetadata.CULPRIT_MAX_LENGTH
                                + DeadLetterMetadata.REASON_MAX_LENGTH
                                + DeadLetterMetadata.MESSAGE_MAX_LENGTH,
                        "total of the four maximums"));
    }

    /** Asserts that a value of exactly the maximum length reaches the accessor unchanged. */
    @Test
    @DisplayName("Keeps a value of exactly the maximum length unchanged")
    void keepsAValueOfExactlyTheMaximumLength() {
        for (int index = 0; index < EXPECTED_COMPONENT_COUNT; index++) {
            int maximum = MAXIMUM_LENGTHS.get(index);
            String atMaximum = valueOfLength(maximum);

            String stored = componentValues(withComponentAt(index, atMaximum)).get(index);

            assertEquals(atMaximum, stored, "component " + index + " at length " + maximum);
        }
    }

    /**
     * Asserts that a value one character over the maximum loses its last character and keeps the
     * rest. The stored value equals the leading characters of the argument and differs from its
     * trailing characters.
     */
    @Test
    @DisplayName("Keeps the leading characters of a value one character over the maximum length")
    void keepsLeadingCharactersOfAnOverLongValue() {
        for (int index = 0; index < EXPECTED_COMPONENT_COUNT; index++) {
            int maximum = MAXIMUM_LENGTHS.get(index);
            String overLong = valueOfLength(maximum + 1);

            String stored = componentValues(withComponentAt(index, overLong)).get(index);

            assertAll("component " + index + " at length " + (maximum + 1),
                    () -> assertEquals(maximum, stored.length(), "stored length"),
                    () -> assertEquals(overLong.substring(0, maximum), stored, "leading characters"),
                    () -> assertNotEquals(overLong.substring(1), stored, "trailing characters"));
        }
    }

    /**
     * Asserts that the constructor throws nothing when all four components arrive over their
     * maximum lengths, and that each stored value lands at its maximum.
     */
    @Test
    @DisplayName("Throws nothing when all four components arrive over their maximum lengths")
    void throwsNothingForOverLongComponents() {
        DeadLetterMetadata metadata = assertDoesNotThrow(() -> new DeadLetterMetadata(
                        valueOfLength(DeadLetterMetadata.CODE_MAX_LENGTH + 1),
                        valueOfLength(DeadLetterMetadata.CULPRIT_MAX_LENGTH + 1),
                        valueOfLength(DeadLetterMetadata.REASON_MAX_LENGTH + 1),
                        valueOfLength(DeadLetterMetadata.MESSAGE_MAX_LENGTH + 1)),
                "construction with four over-long values");

        List<String> stored = componentValues(metadata);
        for (int index = 0; index < EXPECTED_COMPONENT_COUNT; index++) {
            int maximum = MAXIMUM_LENGTHS.get(index);
            assertEquals(maximum, stored.get(index).length(), "component " + index + " length");
        }
    }

    /**
     * Asserts that one {@code null} component becomes the empty string and the other three arrive
     * unchanged.
     */
    @Test
    @DisplayName("Turns one null component into the empty string and leaves the other three alone")
    void turnsOneNullComponentIntoTheEmptyString() {
        for (int index = 0; index < EXPECTED_COMPONENT_COUNT; index++) {
            List<String> stored = componentValues(withComponentAt(index, null));

            for (int position = 0; position < EXPECTED_COMPONENT_COUNT; position++) {
                String value = stored.get(position);
                String where = "component " + position + ", null supplied at " + index;
                assertNotNull(value, where);
                assertEquals(position == index ? "" : SHORT_VALUE, value, where);
            }
        }
    }

    /**
     * Asserts that four {@code null} components become four empty strings. No accessor returns
     * {@code null}, and no value arrives padded with spaces to its component width.
     */
    @Test
    @DisplayName("Turns four null components into four empty strings and pads none of them")
    void turnsFourNullComponentsIntoFourEmptyStrings() {
        List<String> stored = componentValues(new DeadLetterMetadata(null, null, null, null));

        assertEquals(EXPECTED_COMPONENT_COUNT, stored.size(), "value count");
        for (int index = 0; index < EXPECTED_COMPONENT_COUNT; index++) {
            String value = stored.get(index);
            String padded = " ".repeat(MAXIMUM_LENGTHS.get(index));

            assertAll("component " + index,
                    () -> assertNotNull(value, "never null"),
                    () -> assertEquals("", value, "empty string"),
                    () -> assertNotEquals(" ", value, "not one space"),
                    () -> assertNotEquals(padded, value, "not padded to the component width"));
        }
    }

    /**
     * Asserts that the record carries no fifth component and that no component name matches a
     * message-coordinate fragment.
     */
    @Test
    @DisplayName("Carries no fifth component and no name matching a message-coordinate fragment")
    void carriesNoFifthComponent() {
        RecordComponent[] components = DeadLetterMetadata.class.getRecordComponents();

        assertEquals(EXPECTED_COMPONENT_COUNT, components.length, "component count");
        for (RecordComponent component : components) {
            String name = component.getName().toLowerCase(Locale.ROOT);
            for (String fragment : FORBIDDEN_NAME_FRAGMENTS) {
                assertFalse(name.contains(fragment),
                        "component " + component.getName() + " matches the fragment " + fragment);
            }
        }
    }

    /** Asserts that no framework annotation sits on the record or on any of its components. */
    @Test
    @DisplayName("Carries no framework annotation on the record or on any component")
    void carriesNoFrameworkAnnotation() {
        List<String> declared =
                new ArrayList<>(typeNamesOf(DeadLetterMetadata.class.getAnnotations()));
        for (RecordComponent component : DeadLetterMetadata.class.getRecordComponents()) {
            declared.addAll(typeNamesOf(component.getAnnotations()));
        }

        for (String annotationTypeName : declared) {
            for (String prefix : FRAMEWORK_PACKAGE_PREFIXES) {
                assertFalse(annotationTypeName.startsWith(prefix),
                        "annotation " + annotationTypeName + " comes from " + prefix);
            }
        }
    }

    /** Asserts that at most one static method returns the record. */
    @Test
    @DisplayName("Declares at most one static factory")
    void declaresAtMostOneStaticFactory() {
        List<String> factories = Arrays.stream(DeadLetterMetadata.class.getDeclaredMethods())
                .filter(method -> Modifier.isStatic(method.getModifiers()))
                .filter(method -> DeadLetterMetadata.class.equals(method.getReturnType()))
                .map(Method::getName)
                .sorted()
                .toList();

        assertTrue(factories.size() <= 1, "static factory count " + factories.size() + " " + factories);
    }

    /**
     * Asserts that the factory fits the failure type name inside the culprit width. A
     * {@code null} failure yields an empty culprit, and every value it returns fits its component.
     */
    @Test
    @DisplayName("Fits the failure type name inside the culprit width and empties a null failure")
    void factoryFitsTheFailureTypeNameInsideTheCulpritWidth() {
        int culpritMaximum = DeadLetterMetadata.CULPRIT_MAX_LENGTH;
        String failureTypeName = IllegalStateException.class.getSimpleName();

        DeadLetterMetadata named = DeadLetterMetadata.fromFailure(
                valueOfLength(DeadLetterMetadata.CODE_MAX_LENGTH), new IllegalStateException(),
                SHORT_VALUE, SHORT_VALUE);
        DeadLetterMetadata absent = DeadLetterMetadata.fromFailure(null, null, null, null);

        assertAll("factory output",
                () -> assertEquals(failureTypeName.substring(0, culpritMaximum), named.culprit(),
                        "leading characters of the failure type name"),
                () -> assertTrue(named.culprit().length() <= culpritMaximum, "culprit width"),
                () -> assertTrue(withinMaximumLengths(named), "every value fits its component"),
                () -> assertEquals("", absent.culprit(), "culprit for a null failure"),
                () -> assertEquals(EXPECTED_COMPONENT_COUNT,
                        componentValues(absent).stream().filter(String::isEmpty).count(),
                        "empty value count for four nulls"));
    }

    /** Returns the four component values, in the order {@link DeadLetterMetadata} declares them. */
    private static List<String> componentValues(DeadLetterMetadata metadata) {
        return Arrays.asList(metadata.abendCode(), metadata.culprit(), metadata.reason(),
                metadata.message());
    }

    /** Returns a record holding {@code value} at {@code index} and a short value elsewhere. */
    private static DeadLetterMetadata withComponentAt(int index, String value) {
        String[] values = {SHORT_VALUE, SHORT_VALUE, SHORT_VALUE, SHORT_VALUE};
        values[index] = value;
        return new DeadLetterMetadata(values[0], values[1], values[2], values[3]);
    }

    /** Returns a value of {@code length} characters whose characters vary along its length. */
    private static String valueOfLength(int length) {
        StringBuilder builder = new StringBuilder(length);
        for (int position = 0; position < length; position++) {
            builder.append((char) ('a' + position % ALPHABET_SIZE));
        }
        return builder.toString();
    }

    /** Reports whether every value of {@code metadata} fits its component's maximum length. */
    private static boolean withinMaximumLengths(DeadLetterMetadata metadata) {
        List<String> values = componentValues(metadata);
        for (int index = 0; index < EXPECTED_COMPONENT_COUNT; index++) {
            if (values.get(index).length() > MAXIMUM_LENGTHS.get(index)) {
                return false;
            }
        }
        return true;
    }

    /** Returns the fully qualified type name of every annotation in {@code annotations}. */
    private static List<String> typeNamesOf(Annotation[] annotations) {
        return Arrays.stream(annotations)
                .map(annotation -> annotation.annotationType().getName())
                .toList();
    }
}
