package com.carddemo.fraud.domain;

import com.carddemo.cobol.PicClause;
import com.carddemo.events.EventEnvelope;
import com.carddemo.events.TransactionAuthorized;
import com.carddemo.fraud.domain.rules.AmountAnomalyRule;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link AmountAnomalyRule}, which compares one transaction amount against a
 * configured threshold and reports {@code AMOUNT_ANOMALY}. Every value here is an inline literal,
 * and no test starts a context or opens a connection.
 *
 * <p>The CardDemo Common Business Oriented Language (COBOL) source holds no risk scoring and no
 * threshold rule, so this rule is net new; no COBOL ancestor.
 */
@DisplayName("AmountAnomalyRule, the magnitude threshold comparison on a transaction amount")
class AmountAnomalyRuleTest {

    /**
     * A card token, sixty-four lower-case hexadecimal characters.
     *
     * <p>ADDITIVE: no source field exists. {@code com.carddemo.cobol.PanMasker#tokenOf} derives a
     * token from a full card number, and the width and case are that method's.</p>
     */
    private static final String CARD_TOKEN =
            "f8da0217fb8bd2e172d427a2ef66d54656a59baa9fe8f9bc2ce9d383b90e1173";

    /** The rule under test. */
    private static final Class<AmountAnomalyRule> SUBJECT = AmountAnomalyRule.class;

    /** The identifier the rule reports. */
    private static final String RULE_IDENTIFIER = "AMOUNT_ANOMALY";

    /** The key {@code application.yml} publishes, and the key the constructor placeholder names. */
    private static final String THRESHOLD_KEY = "carddemo.fraud.risk.amount-anomaly-threshold";

    /** A namespace the shipped configuration file publishes no key under. */
    private static final String UNPUBLISHED_AMOUNT_PREFIX = "carddemo.fraud.amount.";

    /** The threshold every behavioural test passes, and the default the placeholder carries. */
    private static final String THRESHOLD = "500.00";

    /** The amount of the first daily transaction fixture record. */
    private static final String FIXTURE_AMOUNT = "504.77";

    /** A zero amount at the scale of the transaction amount field. */
    private static final String ZERO_AMOUNT = "0.00";

    /** Type names of the binary floating-point forms no field of the rule carries. */
    private static final Set<String> FLOATING_POINT_TYPES =
            Set.of("double", "java.lang.Double", "float", "java.lang.Float");

    /** The event identifier every built event carries. */
    private static final UUID EVENT_ID = UUID.fromString("7f1c9a2e-4b6d-4a11-9c3e-5d8f2a6b0c41");

    /** The moment the envelope carries, one fixed instant for every built event. */
    private static final Instant OCCURRED_AT = Instant.parse("2022-06-10T19:27:53Z");

    /** The eleven-digit account identifier, and the message key. */
    private static final String ACCOUNT_IDENTIFIER = "00000000007";

    /** The sixteen-character identifier of the first fixture record, which opens with a zero. */
    private static final String TRANSACTION_IDENTIFIER = "00000000" + "00683580";

    @Nested
    @DisplayName("How the rule registers with the scoring chain")
    class RegistrationContract {

        @Test
        @DisplayName("Carries @Component and @Order(20), stays public and open, implements RiskRule")
        void registersAsAnOrderedComponentThatImplementsRiskRule() {
            Order order = SUBJECT.getAnnotation(Order.class);
            assertAll("registration of AmountAnomalyRule",
                    () -> assertNotNull(SUBJECT.getAnnotation(Component.class), "@Component"),
                    () -> assertNotNull(order, "@Order"),
                    () -> assertEquals(20, order.value(), "@Order value"),
                    () -> assertTrue(Modifier.isPublic(SUBJECT.getModifiers()), "public"),
                    () -> assertFalse(Modifier.isFinal(SUBJECT.getModifiers()), "final"),
                    () -> assertTrue(RiskRule.class.isAssignableFrom(SUBJECT), "implements RiskRule"));
        }

        @Test
        @DisplayName("Declares one constructor taking one threshold, and carries no @Autowired")
        void declaresOneConstructorTakingOneThreshold() {
            Constructor<?>[] constructors = SUBJECT.getDeclaredConstructors();
            assertEquals(1, constructors.length, "declared constructor count");
            Constructor<?> constructor = constructors[0];
            assertAll("the single constructor",
                    () -> assertEquals(1, constructor.getParameterCount(), "parameter count"),
                    () -> assertEquals(String.class, constructor.getParameterTypes()[0], "type"),
                    () -> assertNull(constructor.getAnnotation(Autowired.class), "@Autowired"));
        }

        @Test
        @DisplayName("Holds only private final fields, none floating-point, none a collaborator")
        void holdsOnlyPrivateFinalStateAndNoCollaborator() {
            List<Field> fields = declaredFields();
            List<String> mutable = fields.stream()
                    .filter(field -> !Modifier.isPrivate(field.getModifiers())
                            || !Modifier.isFinal(field.getModifiers())).map(Field::getName).toList();
            List<String> floating = fields.stream().map(Field::getType).map(Class::getName)
                    .filter(FLOATING_POINT_TYPES::contains).toList();
            List<String> collaborators = fields.stream().map(Field::getType)
                    .filter(AmountAnomalyRuleTest::isCollaboratorType).map(Class::getName).toList();
            assertAll("fields of AmountAnomalyRule",
                    () -> assertFalse(fields.isEmpty(), "declared field count"),
                    () -> assertEquals(List.of(), mutable, "fields not both private and final"),
                    () -> assertEquals(List.of(), floating, "field types holding a binary form"),
                    () -> assertEquals(List.of(), collaborators, "field types of a collaborator"));
        }

        @Test
        @DisplayName("Declares evaluate and ruleId as its only public instance methods")
        void declaresTheTwoInterfaceMethods() {
            List<Method> methods = Arrays.stream(SUBJECT.getDeclaredMethods())
                    .filter(method -> !method.isSynthetic()).toList();
            List<String> names = methods.stream().map(Method::getName).sorted().toList();
            List<String> other = methods.stream().filter(method ->
                    !Modifier.isPublic(method.getModifiers())
                            || Modifier.isStatic(method.getModifiers())).map(Method::getName).toList();
            assertAll("methods AmountAnomalyRule declares",
                    () -> assertEquals(List.of("evaluate", "ruleId"), names, "names"),
                    () -> assertEquals(List.of(), other, "non-public or static methods"));
        }

        @Test
        @DisplayName("Holds AMOUNT_ANOMALY in a private static final field, and ruleId reports it")
        void reportsTheClassLocalIdentifier() {
            List<Field> identifiers = declaredFields().stream().filter(field ->
                    field.getType() == String.class && Modifier.isPrivate(field.getModifiers())
                            && Modifier.isStatic(field.getModifiers())
                            && Modifier.isFinal(field.getModifiers())).toList();
            assertEquals(1, identifiers.size(), "private static final String field count");
            Field identifier = identifiers.get(0);
            identifier.setAccessible(true);
            assertAll("identifier of AmountAnomalyRule",
                    () -> assertEquals(RULE_IDENTIFIER, identifier.get(null), "field value"),
                    () -> assertEquals(RULE_IDENTIFIER, new AmountAnomalyRule(THRESHOLD).ruleId(),
                            "ruleId()"));
        }
    }

    @Nested
    @DisplayName("Negative amounts are ordinary traffic")
    class NegativeAmounts {

        @ParameterizedTest
        @ValueSource(strings = {"-919.00", "-56.77"})
        @DisplayName("Scores a negative amount and returns a contribution")
        void scoresANegativeAmountWithoutThrowing(String amount) {
            RiskRule.Contribution contribution = assertDoesNotThrow(
                    () -> new AmountAnomalyRule(THRESHOLD).evaluate(authorizedFor(amount)));
            assertNotNull(contribution, "contribution for " + amount);
        }

        @Test
        @DisplayName("Treats a small negative amount as no anomaly, carrying no points")
        void treatsASmallNegativeAmountAsNoAnomaly() {
            RiskRule.Contribution contribution =
                    new AmountAnomalyRule(THRESHOLD).evaluate(authorizedFor("-56.77"));
            assertAll("contribution for a small negative amount",
                    () -> assertFalse(contribution.triggered(), "triggered"),
                    () -> assertEquals(0, contribution.points(), "points"));
        }

        @ParameterizedTest
        @CsvSource({
            "500.00, -919.00, true, 30",
            "1000.00, -919.00, false, 0",
            "10.00, -56.77, true, 30"
        })
        @DisplayName("Scores a refund by absolute magnitude")
        void scoresARefundByAbsoluteMagnitude(
                String threshold, String amount, boolean triggered, int points) {
            RiskRule.Contribution contribution =
                    new AmountAnomalyRule(threshold).evaluate(authorizedFor(amount));
            assertAll("contribution for " + amount + " against " + threshold,
                    () -> assertEquals(triggered, contribution.triggered(), "triggered"),
                    () -> assertEquals(points, contribution.points(), "points"));
        }

        @ParameterizedTest
        @CsvSource({"504.77, true, 30", "500.00, true, 30", "499.99, false, 0"})
        @DisplayName("Triggers at the threshold and above it, and not below it")
        void triggersAtTheThresholdAndAboveIt(String amount, boolean triggered, int points) {
            RiskRule.Contribution contribution =
                    new AmountAnomalyRule(THRESHOLD).evaluate(authorizedFor(amount));
            assertAll("contribution for " + amount + " against " + THRESHOLD,
                    () -> assertEquals(triggered, contribution.triggered(), "triggered"),
                    () -> assertEquals(points, contribution.points(), "points"));
        }
    }

    @Nested
    @DisplayName("The shape the amount holds when it reaches the rule")
    class FixedPointAmount {

        @Test
        @DisplayName("Reads the amount at the transaction amount scale, compared by equals")
        void readsTheAmountAtThePictureScaleComparedByEquals() {
            BigDecimal amount = authorizedFor(FIXTURE_AMOUNT).amount();
            assertAll("amount of the built event",
                    () -> assertEquals(PicClause.TRAN_AMT_SCALE, amount.scale(), "scale"),
                    () -> assertEquals(new BigDecimal(FIXTURE_AMOUNT), amount, "value"),
                    () -> assertEquals(FIXTURE_AMOUNT, amount.toPlainString(), "plain text"));
        }

        @Test
        @DisplayName("Scores a zero amount as no anomaly, its magnitude compared by compareTo")
        void scoresAZeroAmountComparedByCompareTo() {
            TransactionAuthorized event = authorizedFor(ZERO_AMOUNT);
            RiskRule.Contribution contribution = assertDoesNotThrow(
                    () -> new AmountAnomalyRule(THRESHOLD).evaluate(event));
            assertAll("contribution for a zero amount",
                    () -> assertEquals(PicClause.TRAN_AMT_SCALE, event.amount().scale(), "scale"),
                    () -> assertEquals(0, new BigDecimal(ZERO_AMOUNT).compareTo(event.amount()),
                            "magnitude"),
                    () -> assertFalse(contribution.triggered(), "triggered"),
                    () -> assertEquals(0, contribution.points(), "points"));
        }
    }

    @Nested
    @DisplayName("The property key the threshold binds")
    class ThresholdPropertyKey {

        @Test
        @DisplayName("Binds the one key the shipped configuration file publishes, with its default")
        void bindsThePublishedThresholdKeyWithItsDefault() {
            List<String> placeholders = constructorPlaceholders();
            assertEquals(1, placeholders.size(), "@Value placeholder count");
            String placeholder = placeholders.get(0);
            assertAll("the single placeholder",
                    () -> assertEquals(THRESHOLD_KEY, keyOf(placeholder), "property key"),
                    () -> assertTrue(placeholder.contains(":"), "carries an explicit default"),
                    () -> assertEquals(THRESHOLD, defaultOf(placeholder), "default value"));
        }

        @Test
        @DisplayName("Binds no key under carddemo.fraud.amount., which nothing publishes")
        void bindsNoKeyUnderTheUnpublishedNamespace() {
            List<String> unpublished = constructorPlaceholders().stream()
                    .map(AmountAnomalyRuleTest::keyOf)
                    .filter(key -> key.startsWith(UNPUBLISHED_AMOUNT_PREFIX)).toList();
            assertEquals(List.of(), unpublished, "keys under " + UNPUBLISHED_AMOUNT_PREFIX);
        }

        @Test
        @DisplayName("Declares @Value on the constructor parameter and on no field")
        void declaresValueOnTheConstructorParameterAlone() {
            List<String> annotated = declaredFields().stream()
                    .filter(field -> field.getAnnotation(Value.class) != null)
                    .map(Field::getName).toList();
            assertAll("placement of @Value",
                    () -> assertEquals(1, constructorPlaceholders().size(), "on parameters"),
                    () -> assertEquals(List.of(), annotated, "on declared fields"));
        }
    }

    private static TransactionAuthorized authorizedFor(String amount) {
        return new TransactionAuthorized(EVENT_ID, TransactionAuthorized.EVENT_TYPE,
                EventEnvelope.SCHEMA_VERSION, OCCURRED_AT, ACCOUNT_IDENTIFIER,
                TRANSACTION_IDENTIFIER, "01", "0001", "POS TERM", "Purchase at Abshire-Lowe",
                new BigDecimal(amount), "800000000", "Abshire-Lowe", "North Enoshaven", "72112",
                "************7065", null, "2022-06-10 19:27:53.000000", ACCOUNT_IDENTIFIER,
                TransactionAuthorized.CURRENCY);
    }

    private static List<Field> declaredFields() {
        return Arrays.stream(SUBJECT.getDeclaredFields())
                .filter(field -> !field.isSynthetic()).toList();
    }

    private static boolean isCollaboratorType(Class<?> type) {
        String name = type.getName();
        return name.endsWith("Repository") || name.startsWith("org.springframework.data.")
                || RiskRule.class.isAssignableFrom(type) || "java.time.Clock".equals(name);
    }

    private static List<String> constructorPlaceholders() {
        return Arrays.stream(SUBJECT.getDeclaredConstructors()[0].getParameters())
                .map(parameter -> parameter.getAnnotation(Value.class))
                .filter(annotation -> annotation != null).map(Value::value).toList();
    }

    private static String keyOf(String placeholder) {
        return placeholder.replace("${", "").replace("}", "").split(":", 2)[0];
    }

    private static String defaultOf(String placeholder) {
        String[] segments = placeholder.replace("${", "").replace("}", "").split(":", 2);
        return segments.length > 1 ? segments[1] : "";
    }
}
