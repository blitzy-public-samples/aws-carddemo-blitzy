package com.carddemo.fraud.domain;

import com.carddemo.events.TransactionAuthorized;
import java.lang.annotation.Annotation;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.RecordComponent;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.function.Predicate;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Contract tests for {@link RiskRule} and its nested {@link RiskRule.Contribution} record.
 *
 * <p>The CardDemo Common Business Oriented Language (COBOL) source holds no risk scoring and no
 * rules engine, so this contract is net new; no COBOL ancestor.
 *
 * <p>Every value is an inline literal. No test opens a file, a context or a connection, and none
 * calls a production rule class.
 */
@DisplayName("RiskRule, the interface every risk rule implements")
class RiskRuleTest {

    /** The nested record the interface declares, and the type both factories return. */
    private static final Class<?> CONTRIBUTION = RiskRule.Contribution.class;

    /** The names of the two static factories the nested record declares. */
    private static final Set<String> FACTORY_NAMES = Set.of("triggeredWith", "notTriggered");

    /** Package prefixes of the frameworks that annotate no part of the interface. */
    private static final List<String> FRAMEWORK_PREFIXES =
            List.of("org.springframework", "jakarta.", "javax.");

    /** The identifier the hand-written double reports. No production rule owns it. */
    private static final String DOUBLE_RULE_ID = "TEST_DOUBLE_RULE";

    /** A point count a triggering contribution carries. */
    private static final int SAMPLE_POINTS = 25;

    /** Accepts a method an implementation has to supply. */
    private static final Predicate<Method> ABSTRACT_INSTANCE = method ->
            Modifier.isAbstract(method.getModifiers())
                    && !Modifier.isStatic(method.getModifiers()) && !method.isDefault();

    /** Accepts a method the declaring type resolves itself. */
    private static final Predicate<Method> STATIC_METHOD =
            method -> Modifier.isStatic(method.getModifiers());

    @Nested
    @DisplayName("The declared shape of the interface")
    class InterfaceShape {

        @Test
        @DisplayName("Declares an open interface with no field of its own and one nested record")
        void declaresAnOpenInterfaceWithOneNestedRecord() {
            Class<?>[] nested = RiskRule.class.getDeclaredClasses();
            assertAll("shape of RiskRule",
                    () -> assertTrue(RiskRule.class.isInterface(), "isInterface"),
                    () -> assertFalse(RiskRule.class.isSealed(), "isSealed"),
                    () -> assertNull(RiskRule.class.getPermittedSubclasses(), "permitted subclasses"),
                    () -> assertNull(RiskRule.class.getAnnotation(FunctionalInterface.class), "marker"),
                    () -> assertEquals("com.carddemo.fraud.domain", RiskRule.class.getPackageName()),
                    () -> assertEquals(0, RiskRule.class.getDeclaredFields().length, "field count"),
                    () -> assertEquals(1, nested.length, "nested type count"),
                    () -> assertEquals("Contribution", nested[0].getSimpleName(), "nested type name"),
                    () -> assertTrue(nested[0].isRecord(), "nested type is a record"));
        }

        @Test
        @DisplayName("Declares two abstract methods named evaluate and ruleId, and nothing else")
        void declaresTwoAbstractMethodsAndNothingElse() {
            List<String> abstractNames = namesOf(methodsOf(RiskRule.class, ABSTRACT_INSTANCE));
            List<String> defaultNames = namesOf(methodsOf(RiskRule.class, Method::isDefault));
            List<String> staticNames = namesOf(methodsOf(RiskRule.class, STATIC_METHOD));
            assertAll("methods RiskRule declares",
                    () -> assertEquals(2, abstractNames.size(), "abstract instance method count"),
                    () -> assertEquals(Set.of("evaluate", "ruleId"), Set.copyOf(abstractNames)),
                    () -> assertEquals(List.of(), defaultNames, "default methods"),
                    () -> assertEquals(List.of(), staticNames, "static methods"));
        }

        @Test
        @DisplayName("evaluate takes one authorized transaction, and ruleId takes no argument")
        void declaresTheTwoMethodSignatures() throws NoSuchMethodException {
            Method evaluate = RiskRule.class.getDeclaredMethod("evaluate", TransactionAuthorized.class);
            Method ruleId = RiskRule.class.getDeclaredMethod("ruleId");
            assertAll("signatures RiskRule declares",
                    () -> assertEquals(CONTRIBUTION, evaluate.getReturnType(), "evaluate returns"),
                    () -> assertEquals(1, evaluate.getParameterCount(), "evaluate parameter count"),
                    () -> assertEquals(TransactionAuthorized.class, evaluate.getParameterTypes()[0]),
                    () -> assertEquals(String.class, ruleId.getReturnType(), "ruleId returns"),
                    () -> assertEquals(0, ruleId.getParameterCount(), "ruleId parameter count"));
        }

        @Test
        @DisplayName("Carries no framework annotation on the interface or on either method")
        void carriesNoFrameworkAnnotation() {
            List<String> onType = frameworkPackagesOf(RiskRule.class.getAnnotations());
            List<String> onMethods = Arrays.stream(RiskRule.class.getDeclaredMethods())
                    .flatMap(method -> frameworkPackagesOf(method.getAnnotations()).stream()).toList();
            assertAll("framework annotations",
                    () -> assertEquals(List.of(), onType, "on the interface"),
                    () -> assertEquals(List.of(), onMethods, "on the declared methods"));
        }
    }

    @Nested
    @DisplayName("The canonical constructor of Contribution")
    class CanonicalConstructor {

        @Test
        @DisplayName("Declares two components, the boolean triggered then the int points")
        void declaresTwoComponentsInSequence() {
            RecordComponent[] components = CONTRIBUTION.getRecordComponents();
            List<String> names = Arrays.stream(components).map(RecordComponent::getName).toList();
            List<Class<?>> types = Arrays.stream(components).map(RecordComponent::getType).toList();
            assertAll("components of Contribution",
                    () -> assertEquals(2, components.length, "count"),
                    () -> assertEquals(List.of("triggered", "points"), names, "names"),
                    () -> assertEquals(List.<Class<?>>of(boolean.class, int.class), types, "types"));
        }

        @ParameterizedTest
        @ValueSource(ints = {-1, -2, -42, Integer.MIN_VALUE})
        @DisplayName("Refuses a negative point count on either verdict")
        void refusesNegativePoints(int points) {
            assertAll("negative points",
                    () -> assertThrows(IllegalArgumentException.class,
                            () -> new RiskRule.Contribution(true, points), "triggered verdict"),
                    () -> assertThrows(IllegalArgumentException.class,
                            () -> new RiskRule.Contribution(false, points), "untriggered verdict"));
        }

        @ParameterizedTest
        @ValueSource(ints = {1, 2, SAMPLE_POINTS})
        @DisplayName("Refuses points on a contribution that did not trigger")
        void refusesPointsWithoutTriggering(int points) {
            assertThrows(IllegalArgumentException.class,
                    () -> new RiskRule.Contribution(false, points), "points without a trigger");
        }

        @Test
        @DisplayName("Accepts zero points on both verdicts")
        void acceptsZeroPointsOnBothVerdicts() {
            RiskRule.Contribution hit = assertDoesNotThrow(() -> new RiskRule.Contribution(true, 0));
            RiskRule.Contribution miss = assertDoesNotThrow(() -> new RiskRule.Contribution(false, 0));
            assertAll("zero-point boundaries",
                    () -> assertTrue(hit.triggered(), "verdict of the triggered boundary"),
                    () -> assertEquals(0, hit.points(), "points of the triggered boundary"),
                    () -> assertFalse(miss.triggered(), "verdict of the untriggered boundary"),
                    () -> assertEquals(0, miss.points(), "points of the untriggered boundary"));
        }

        @Test
        @DisplayName("Reads back what a triggering call supplied, and compares by component")
        void readsBackAndComparesByComponent() {
            RiskRule.Contribution first = new RiskRule.Contribution(true, SAMPLE_POINTS);
            RiskRule.Contribution second = new RiskRule.Contribution(true, SAMPLE_POINTS);
            assertAll("value semantics of Contribution",
                    () -> assertTrue(first.triggered(), "triggered"),
                    () -> assertEquals(SAMPLE_POINTS, first.points(), "points"),
                    () -> assertEquals(first, second, "equals"),
                    () -> assertEquals(first.hashCode(), second.hashCode(), "hashCode"));
        }
    }

    @Nested
    @DisplayName("The two static factories on Contribution")
    class StaticFactories {

        @Test
        @DisplayName("Declares exactly two static methods, both returning a contribution")
        void declaresExactlyTwoStaticFactories() {
            List<Method> factories = methodsOf(CONTRIBUTION, STATIC_METHOD);
            List<Class<?>> returnTypes = factories.stream().map(Method::getReturnType).toList();
            assertAll("static methods of Contribution",
                    () -> assertEquals(2, factories.size(), "count"),
                    () -> assertEquals(FACTORY_NAMES, Set.copyOf(namesOf(factories)), "names"),
                    () -> assertEquals(List.of(CONTRIBUTION, CONTRIBUTION), returnTypes, "returns"));
        }

        @ParameterizedTest
        @ValueSource(ints = {0, 1, 2, SAMPLE_POINTS, 40})
        @DisplayName("triggeredWith reports a triggered verdict and keeps the point count")
        void triggeredWithKeepsThePointCount(int points) {
            RiskRule.Contribution contribution = RiskRule.Contribution.triggeredWith(points);
            assertAll("contribution from triggeredWith",
                    () -> assertTrue(contribution.triggered(), "triggered"),
                    () -> assertEquals(points, contribution.points(), "points"));
        }

        @Test
        @DisplayName("notTriggered takes no argument and reports no points")
        void notTriggeredReportsNoPoints() {
            RiskRule.Contribution contribution = RiskRule.Contribution.notTriggered();
            assertAll("contribution from notTriggered",
                    () -> assertFalse(contribution.triggered(), "triggered"),
                    () -> assertEquals(0, contribution.points(), "points"));
        }

        @ParameterizedTest
        @ValueSource(ints = {-1, -42, Integer.MIN_VALUE})
        @DisplayName("triggeredWith refuses a negative point count")
        void triggeredWithRefusesNegativePoints(int points) {
            assertThrows(IllegalArgumentException.class,
                    () -> RiskRule.Contribution.triggeredWith(points), "negative points");
        }
    }

    @Nested
    @DisplayName("Implementing the interface without a framework")
    class FrameworkFreeImplementation {

        @Test
        @DisplayName("A plain class implements the interface, reports an identifier and keeps no state")
        void aPlainClassImplementsTheInterface() {
            RiskRule rule = new ScoringDouble();
            List<String> mutable = Arrays.stream(ScoringDouble.class.getDeclaredFields())
                    .filter(field -> !Modifier.isStatic(field.getModifiers())
                            || !Modifier.isFinal(field.getModifiers()))
                    .map(field -> field.getName()).toList();
            assertAll("hand-written implementation of RiskRule",
                    () -> assertEquals(DOUBLE_RULE_ID, rule.ruleId(), "ruleId"),
                    () -> assertFalse(rule.evaluate(null).triggered(), "verdict of the double"),
                    () -> assertEquals(List.of(), mutable, "fields not both static and final"));
        }
    }

    /** Returns the declared, non-synthetic methods of {@code type} that {@code filter} accepts. */
    private static List<Method> methodsOf(Class<?> type, Predicate<Method> filter) {
        return Arrays.stream(type.getDeclaredMethods())
                .filter(method -> !method.isSynthetic()).filter(filter).toList();
    }

    /** Returns the names of {@code methods}, in the sequence supplied. */
    private static List<String> namesOf(List<Method> methods) {
        return methods.stream().map(Method::getName).toList();
    }

    /** Returns the package name of every annotation in {@code annotations} a framework owns. */
    private static List<String> frameworkPackagesOf(Annotation[] annotations) {
        return Arrays.stream(annotations)
                .map(annotation -> annotation.annotationType().getPackageName())
                .filter(name -> FRAMEWORK_PREFIXES.stream().anyMatch(name::startsWith)).toList();
    }

    /** A hand-written implementation of {@link RiskRule}, holding one identifier and no state. */
    private static final class ScoringDouble implements RiskRule {

        private static final String RULE_ID = DOUBLE_RULE_ID;

        public Contribution evaluate(TransactionAuthorized event) {
            return Contribution.notTriggered();
        }

        public String ruleId() {
            return RULE_ID;
        }
    }
}
