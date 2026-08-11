package com.carddemo.fraud.domain;

import com.carddemo.events.EventEnvelope;
import com.carddemo.events.TransactionAuthorized;
import com.carddemo.fraud.config.FraudProperties;
import com.carddemo.fraud.domain.rules.MerchantCategoryRule;
import com.carddemo.fraud.repository.VelocityWindowRepository;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.stream.Stream;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.annotation.Order;
import org.springframework.data.repository.Repository;
import org.springframework.stereotype.Component;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link MerchantCategoryRule}: how it registers, how it scores a merchant category
 * code, and which property keys it binds.
 *
 * <p>The CardDemo Common Business Oriented Language (COBOL) source holds no fraud module, so this
 * rule is net new; no COBOL ancestor. Event values come from record one of the daily transaction
 * fixture, as inline literals. No test opens a file, a context, a container or a connection.
 */
@DisplayName("MerchantCategoryRule, the rule that scores a merchant category code")
class MerchantCategoryRuleTest {

    /**
     * Card token of the fixture card number, sixty-four lower-case hexadecimal characters.
     *
     * <p>Additive. No source field exists. {@code com.carddemo.cobol.PanMasker#tokenOf} writes this
     * value from the full card number {@code 4859452612877065}, and the width and case are that
     * method's.</p>
     */
    private static final String CARD_TOKEN =
            "f8da0217fb8bd2e172d427a2ef66d54656a59baa9fe8f9bc2ce9d383b90e1173";

    /** The eleven-digit account identifier the event carries, and its message key. */
    private static final String ACCOUNT_ID = "00000000007";

    /** The transaction identifier of record one, sixteen characters, leading zeros included. */
    private static final String TRANSACTION_ID = "0000000000683580";

    /** The masked card number: twelve asterisks then the last four digits. */
    private static final String MASKED_CARD_NUMBER = "************7065";

    /** The authorization timestamp every fixture record carries, twenty-six characters. */
    private static final String AUTHORIZED_AT = "2022-06-10 19:27:53.000000";

    /** The amount of record one, at the two fractional digits of the source field. */
    private static final BigDecimal AMOUNT = new BigDecimal("504.77");

    /** The moment the producer wrote the event, one fixed instant on every run. */
    private static final Instant OCCURRED_AT = Instant.parse("2022-06-10T19:27:53.512Z");

    /** The idempotency key the event carries, one fixed identifier on every run. */
    private static final UUID EVENT_ID = UUID.fromString("3f2504e0-4f89-41d3-9a0c-0305e82c3301");

    /** The category code every fixture record carries, and one the rule treats as elevated. */
    private static final String FIXTURE_CATEGORY_CODE = "0001";

    /** The points a triggered contribution from this rule carries. */
    private static final int RISK_POINTS = 25;

    /** The identifier this rule reports, and the value its class-local constant holds. */
    private static final String EXPECTED_RULE_ID = "MERCHANT_CATEGORY";

    /** The property family this rule may bind, written as a placeholder prefix. */
    private static final String OWN_PROPERTY_FAMILY = "${carddemo.fraud.merchant.";

    @Nested
    @DisplayName("How the rule registers with the framework")
    class RegistrationContract {

        @Test
        @DisplayName("Carries @Component and @Order(30), fixing its place in the injected list")
        void carriesComponentAndOrderThirty() {
            Order order = MerchantCategoryRule.class.getAnnotation(Order.class);
            assertNotNull(MerchantCategoryRule.class.getAnnotation(Component.class), "@Component");
            assertNotNull(order, "@Order");
            assertEquals(30, order.value(), "@Order value");
        }

        @Test
        @DisplayName("Declares a public, non-final RiskRule with one constructor and no @Autowired")
        void declaresAPublicNonFinalRiskRuleWithOneConstructor() {
            int modifiers = MerchantCategoryRule.class.getModifiers();
            Constructor<?>[] constructors = MerchantCategoryRule.class.getDeclaredConstructors();
            assertAll("declared shape of MerchantCategoryRule",
                    () -> assertTrue(Modifier.isPublic(modifiers), "public"),
                    () -> assertFalse(Modifier.isFinal(modifiers), "final"),
                    () -> assertTrue(RiskRule.class.isAssignableFrom(MerchantCategoryRule.class),
                            "implements RiskRule"),
                    () -> assertEquals(1, constructors.length, "constructor count"),
                    () -> assertNull(constructors[0].getAnnotation(Autowired.class), "@Autowired"));
        }

        @Test
        @DisplayName("Declares evaluate and ruleId as its only public instance methods")
        void declaresEvaluateAndRuleIdAsItsOnlyPublicMethods() {
            List<String> declared = Arrays.stream(MerchantCategoryRule.class.getDeclaredMethods())
                    .filter(method -> !method.isSynthetic() && !method.isBridge())
                    .filter(method -> Modifier.isPublic(method.getModifiers())
                            && !Modifier.isStatic(method.getModifiers()))
                    .map(Method::getName).sorted().toList();
            assertEquals(List.of("evaluate", "ruleId"), declared, "public instance methods");
        }

        @Test
        @DisplayName("Holds MERCHANT_CATEGORY as a class-local constant and returns it as ruleId")
        void holdsTheIdentifierOnTheRuleItself() throws ReflectiveOperationException {
            List<String> constants = new ArrayList<>();
            for (Field field : MerchantCategoryRule.class.getDeclaredFields()) {
                int modifiers = field.getModifiers();
                if (field.getType() == String.class && Modifier.isPrivate(modifiers)
                        && Modifier.isStatic(modifiers) && Modifier.isFinal(modifiers)) {
                    field.setAccessible(true);
                    constants.add((String) field.get(null));
                }
            }
            assertTrue(constants.contains(EXPECTED_RULE_ID), "class-local constants " + constants);
            assertEquals(EXPECTED_RULE_ID, new MerchantCategoryRule().ruleId(), "ruleId");
        }

        @Test
        @DisplayName("Holds every field private and final, and none a store, rule, clock or amount")
        void holdsEveryFieldPrivateFinalAndFreeOfCollaborators() {
            Field[] fields = MerchantCategoryRule.class.getDeclaredFields();
            List<String> mutable = Arrays.stream(fields)
                    .filter(field -> !Modifier.isPrivate(field.getModifiers())
                            || !Modifier.isFinal(field.getModifiers()))
                    .map(Field::getName).toList();
            List<Class<?>> types = Arrays.stream(fields).map(Field::getType).toList();
            assertAll("declared fields of MerchantCategoryRule",
                    () -> assertEquals(List.of(), mutable, "not both private and final"),
                    () -> assertFalse(types.contains(VelocityWindowRepository.class), "store"),
                    () -> assertFalse(types.stream().anyMatch(Repository.class::isAssignableFrom),
                            "repository"),
                    () -> assertFalse(types.stream().anyMatch(RiskRule.class::isAssignableFrom),
                            "sibling rule"),
                    () -> assertFalse(types.contains(Clock.class), "clock"),
                    () -> assertFalse(types.contains(BigDecimal.class), "amount"));
        }
    }

    @Nested
    @DisplayName("Scoring a merchant category code")
    class MerchantCategoryScoring {

        /** The rule under test, built with no property value and no collaborator. */
        private final MerchantCategoryRule rule = new MerchantCategoryRule();

        @Test
        @DisplayName("Triggers on the category code the fixture carries, awarding 25 points")
        void triggersOnTheCategoryCodeTheFixtureCarries() {
            RiskRule.Contribution hit = rule.evaluate(eventWithCategory(FIXTURE_CATEGORY_CODE));
            assertAll("contribution for the fixture category code",
                    () -> assertTrue(hit.triggered(), "triggered"),
                    () -> assertEquals(RISK_POINTS, hit.points(), "points"));
        }

        @ParameterizedTest
        @CsvSource({"0001, true", "0002, true", "0003, false", "0004, true", "0005, false"})
        @DisplayName("Tests set membership over four digits, awarding a non-member no points")
        void testsSetMembershipOverFourDigitsOfText(String categoryCode, boolean triggered) {
            RiskRule.Contribution scored = rule.evaluate(eventWithCategory(categoryCode));
            assertAll("contribution for category code " + categoryCode,
                    () -> assertTrue(categoryCode.matches(
                            TransactionAuthorized.MERCHANT_CATEGORY_CODE_PATTERN), "four digits"),
                    () -> assertEquals(triggered, scored.triggered(), "triggered"),
                    () -> assertEquals(triggered ? RISK_POINTS : 0, scored.points(), "points"));
        }

        @Test
        @DisplayName("Returns an equal contribution on a second call and from a second instance")
        void returnsAnEqualContributionOnEveryCall() {
            TransactionAuthorized event = eventWithCategory(FIXTURE_CATEGORY_CODE);
            RiskRule.Contribution first = rule.evaluate(event);
            RiskRule.Contribution second = rule.evaluate(event);
            RiskRule.Contribution fromAnotherInstance = new MerchantCategoryRule().evaluate(event);
            assertAll("repeated evaluation of one event",
                    () -> assertEquals(first, second, "second call on one instance"),
                    () -> assertEquals(first, fromAnotherInstance, "call on a second instance"));
        }
    }

    @Nested
    @DisplayName("The scorer's configured verdict boundary")
    class ScorerVerdictBoundary {

        @ParameterizedTest
        @CsvSource({"49, false", "50, true", "51, true"})
        @DisplayName("The real scorer clears below 50 and flags at or above 50")
        void scorerHonorsTheConfiguredBoundary(int score, boolean flagged) {
            VelocityWindowRepository repository = mock(VelocityWindowRepository.class);
            when(repository.addAuthorization(eq(ACCOUNT_ID), any(), any(), any())).thenReturn(1);
            RiskRule scoredRule = fixedContribution("VELOCITY", score);
            RiskScoringService scorer = new RiskScoringService(
                    List.of(scoredRule), repository, propertiesWithFlagThreshold(50));

            RiskScoringService.RiskAssessment assessment =
                    scorer.assess(eventWithCategory("0003"));

            assertAll("score " + score + " at the configured boundary",
                    () -> assertEquals(score, assessment.riskScore(), "score"),
                    () -> assertEquals(flagged, assessment.flagged(), "flagged"),
                    () -> assertEquals(List.of("VELOCITY"), assessment.triggeredRules(), "rules"));
        }
    }

    @Nested
    @DisplayName("The risk-threshold property keys the rule binds")
    class RiskThresholdPropertyKeys {

        @Test
        @DisplayName("Binds no property: no constructor argument and no annotated field")
        void bindsNoProperty() {
            assertAll("property binding of MerchantCategoryRule",
                    () -> assertEquals(0, soleConstructor().getParameterCount(), "parameters"),
                    () -> assertEquals(List.of(), declaredPlaceholders(), "placeholders declared"));
        }

        @ParameterizedTest
        @ValueSource(strings = {"carddemo.fraud.risk.flag-threshold",
                "carddemo.fraud.risk.velocity-window-minutes",
                "carddemo.fraud.risk.velocity-count-threshold",
                "carddemo.fraud.risk.amount-anomaly-threshold"})
        @DisplayName("Reads none of the four risk-threshold keys the configuration publishes")
        void readsNoneOfTheShippedRiskThresholdKeys(String shippedKey) {
            assertFalse(String.join(" ", declaredPlaceholders()).contains(shippedKey), shippedKey);
        }

        @Test
        @DisplayName("Keeps every placeholder in its own family and gives each a default")
        void keepsEveryPlaceholderInItsOwnFamilyWithADefault() {
            List<String> placeholders = declaredPlaceholders();
            List<String> outside = placeholders.stream()
                    .filter(text -> !text.startsWith(OWN_PROPERTY_FAMILY)).toList();
            List<String> noDefault = placeholders.stream()
                    .filter(text -> text.indexOf(':') < 0).toList();
            assertAll("placeholders MerchantCategoryRule declares",
                    () -> assertEquals(List.of(), outside, "outside " + OWN_PROPERTY_FAMILY),
                    () -> assertEquals(List.of(), noDefault, "missing a default segment"));
        }
    }

    /** Builds one event carrying {@code categoryCode} over the values of fixture record one. */
    private static TransactionAuthorized eventWithCategory(String categoryCode) {
        return new TransactionAuthorized(EVENT_ID, TransactionAuthorized.EVENT_TYPE,
                EventEnvelope.SCHEMA_VERSION, OCCURRED_AT, ACCOUNT_ID, TRANSACTION_ID, "01",
                categoryCode, "POS TERM", "Purchase at Abshire-Lowe", AMOUNT, "800000000",
                "Abshire-Lowe", "North Enoshaven", "72112", MASKED_CARD_NUMBER, null,
                AUTHORIZED_AT, ACCOUNT_ID, TransactionAuthorized.CURRENCY);
    }

    /** Returns the one constructor {@link MerchantCategoryRule} declares. */
    private static Constructor<?> soleConstructor() {
        return MerchantCategoryRule.class.getDeclaredConstructors()[0];
    }

    /** Returns the placeholder text of every {@link Value} annotation the rule class declares. */
    private static List<String> declaredPlaceholders() {
        Stream<Value> onParameters = Arrays.stream(soleConstructor().getParameters())
                .map(parameter -> parameter.getAnnotation(Value.class));
        Stream<Value> onFields = Arrays.stream(MerchantCategoryRule.class.getDeclaredFields())
                .map(field -> field.getAnnotation(Value.class));
        return Stream.concat(onParameters, onFields).filter(Objects::nonNull)
                .map(Value::value).toList();
    }

    /**
     * Builds a rule that always contributes {@code score} points under {@code identifier}.
     *
     * <p>The scorer's verdict is what these assertions measure, so the rule that feeds it is a stub
     * rather than one of the real rules.
     *
     * @param identifier the rule identifier a triggered contribution reports
     * @param score      the points the rule contributes on every event
     * @return a rule that triggers with {@code score} points
     */
    private static RiskRule fixedContribution(String identifier, int score) {
        return new RiskRule() {
            @Override
            public Contribution evaluate(TransactionAuthorized event) {
                return Contribution.triggeredWith(score);
            }

            @Override
            public String ruleId() {
                return identifier;
            }
        };
    }

    /**
     * Builds the bound settings the scorer reads, carrying one flag threshold.
     *
     * <p>Every other value is the one the shipped {@code application.yml} carries, because none of
     * them takes part in the verdict these assertions measure.
     *
     * @param threshold the score at or above which an assessment is flagged
     * @return settings whose risk block names {@code threshold}
     */
    private static FraudProperties propertiesWithFlagThreshold(int threshold) {
        return new FraudProperties(
                new FraudProperties.Kafka(new FraudProperties.Kafka.Topics(
                        "transaction.authorized", "fraud.assessed", "carddemo.dead-letter",
                        ".DLT")),
                new FraudProperties.Consumer(new FraudProperties.Consumer.Retry(3, 1_000L)),
                new FraudProperties.Outbox(new FraudProperties.Outbox.Relay(
                        500L, 100, "fraud-relay", Duration.ofMinutes(2L), 20_000L), 168L),
                new FraudProperties.Retention(3_600_000L, 90, 7),
                new FraudProperties.Fraud(new FraudProperties.Fraud.Risk(
                        threshold, 60, 5, new BigDecimal("500.00"))));
    }

}
