package com.carddemo.fraud.domain;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import com.carddemo.events.EventEnvelope;
import com.carddemo.events.TransactionAuthorized;
import com.carddemo.fraud.config.FraudProperties;
import com.carddemo.fraud.domain.RiskScoringService.RiskAssessment;
import com.carddemo.fraud.domain.rules.AmountAnomalyRule;
import com.carddemo.fraud.domain.rules.MerchantCategoryRule;
import com.carddemo.fraud.domain.rules.VelocityRule;
import com.carddemo.fraud.repository.VelocityWindowRepository;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

/**
 * Verifies the invocation of every rule double on each call to {@link RiskScoringService#assess}.
 * No case here reads the score alone.
 *
 * <p>The chain shape comes from {@code 1500-VALIDATE-TRAN} at
 * {@code app/cbl/CBTRN02C.cbl:L370-L378}, whose {@code app/cbl/CBTRN02C.cbl:L377} comment marks
 * the extension point, and the borrowing is shape only, no logic. That paragraph gates its second
 * step on the first leaving no failure reason. The gate is not reproduced, and this service
 * evaluates every rule.
 *
 * <p>Plain JUnit 5 with Mockito doubles. No Spring context starts and no file is read. The
 * deviation is recorded in {@code card-platform/docs/decision-log.md}.
 */
@DisplayName("RiskScoringService, which evaluates every registered rule")
class RiskScoringServiceNoShortCircuitTest {

    // Record 1 of the daily transaction fixture. Every identifier is text and its leading zeros
    // belong to the value, so the transaction identifier is ten zeros then six digits.
    private static final String ACCOUNT_ID = "00000000007";
    private static final String TRANSACTION_ID = "0".repeat(10) + "683580";
    private static final Instant OCCURRED_AT = Instant.parse("2022-06-10T19:27:53Z");
    private static final String AUTHORIZED_AT = "2022-06-10 19:27:53.000000";
    private static final String FIXTURE_AMOUNT = "504.77";
    private static final String TYPE_CODE = "01";
    private static final String CATEGORY_CODE = "0001";
    private static final String SOURCE = "POS TERM";
    private static final String DESCRIPTION = "Purchase at Abshire-Lowe";
    private static final String MERCHANT_ID = "800000000";
    private static final String MERCHANT_NAME = "Abshire-Lowe";
    private static final String MERCHANT_CITY = "North Enoshaven";
    private static final String MERCHANT_ZIP = "72112";
    private static final String MASKED_CARD_NUMBER = "************7065";
    private static final String EVENT_ID = "3d7a4f61-9c28-4e5b-b1a6-2f8c0d4e9b73";

    // The three doubles and their distinct point values, whose total stays under the upper bound.
    private static final String FIRST_RULE = "RULE_ONE";
    private static final String SECOND_RULE = "RULE_TWO";
    private static final String THIRD_RULE = "RULE_THREE";
    private static final int FIRST_POINTS = 7;
    private static final int SECOND_POINTS = 11;
    private static final int THIRD_POINTS = 13;
    private static final int EVERY_POINT = FIRST_POINTS + SECOND_POINTS + THIRD_POINTS;
    private static final int NO_POINTS = 0;

    // Counts the assertions read back, and the one row the window statement writes.
    private static final int ONE_RULE = 1;
    private static final int THREE_RULES = 3;
    private static final int ONE_ROW = 1;

    // The shipped flag threshold, and the three settings the shipped rule classes take.
    private static final int SHIPPED_THRESHOLD = 50;
    private static final int LOOKBACK_MINUTES = 60;
    private static final int COUNT_THRESHOLD = 5;
    private static final String AMOUNT_ANOMALY_THRESHOLD = "500.00";

    @Nested
    @DisplayName("Evaluating every registered rule, whatever an earlier rule reported")
    class EveryRuleEvaluated {

        @Test
        @DisplayName("Evaluates all three rules once when all three trigger, and sums all three "
                + "contributions")
        void evaluatesAllThreeRulesWhenAllThreeTrigger() {
            TransactionAuthorized event = authorization();
            RiskRule first = triggering(FIRST_RULE, FIRST_POINTS);
            RiskRule second = triggering(SECOND_RULE, SECOND_POINTS);
            RiskRule third = triggering(THIRD_RULE, THIRD_POINTS);

            RiskAssessment assessment = scorer(first, second, third).assess(event);

            verify(first, times(1)).evaluate(event);
            verify(second, times(1)).evaluate(event);
            verify(third, times(1)).evaluate(event);
            assertAll("the three identifiers and the summed score",
                    () -> assertEquals(THREE_RULES, assessment.triggeredRules().size(), "count"),
                    () -> assertTrue(assessment.triggeredRules()
                            .containsAll(List.of(FIRST_RULE, SECOND_RULE, THIRD_RULE)), "names"),
                    () -> assertEquals(EVERY_POINT, assessment.riskScore(), "summed score"));
        }

        @Test
        @DisplayName("Evaluates the second and third rules after the first has already triggered")
        void evaluatesLaterRulesAfterTheFirstTriggers() {
            TransactionAuthorized event = authorization();
            RiskRule first = triggering(FIRST_RULE, FIRST_POINTS);
            RiskRule second = silent(SECOND_RULE);
            RiskRule third = silent(THIRD_RULE);

            RiskAssessment assessment = scorer(first, second, third).assess(event);

            verify(first, times(1)).evaluate(event);
            verify(second, times(1)).evaluate(event);
            verify(third, times(1)).evaluate(event);
            assertAll("the one rule that triggered of the three that ran",
                    () -> assertEquals(ONE_RULE, assessment.triggeredRules().size(), "count"),
                    () -> assertTrue(assessment.triggeredRules().contains(FIRST_RULE), "name"),
                    () -> assertEquals(FIRST_POINTS, assessment.riskScore(), "score"));
        }

        @Test
        @DisplayName("Evaluates the first and third rules when only the second triggers")
        void evaluatesEveryRuleWhenOnlyTheSecondTriggers() {
            TransactionAuthorized event = authorization();
            RiskRule first = silent(FIRST_RULE);
            RiskRule second = triggering(SECOND_RULE, SECOND_POINTS);
            RiskRule third = silent(THIRD_RULE);

            RiskAssessment assessment = scorer(first, second, third).assess(event);

            verify(first, times(1)).evaluate(event);
            verify(second, times(1)).evaluate(event);
            verify(third, times(1)).evaluate(event);
            assertAll("the middle rule that triggered of the three that ran",
                    () -> assertEquals(ONE_RULE, assessment.triggeredRules().size(), "count"),
                    () -> assertTrue(assessment.triggeredRules().contains(SECOND_RULE), "name"),
                    () -> assertEquals(SECOND_POINTS, assessment.riskScore(), "score"));
        }

        @Test
        @DisplayName("Evaluates all three rules when none triggers, and reports no score")
        void evaluatesAllThreeRulesWhenNoneTriggers() {
            TransactionAuthorized event = authorization();
            RiskRule first = silent(FIRST_RULE);
            RiskRule second = silent(SECOND_RULE);
            RiskRule third = silent(THIRD_RULE);

            RiskAssessment assessment = scorer(first, second, third).assess(event);

            verify(first, times(1)).evaluate(event);
            verify(second, times(1)).evaluate(event);
            verify(third, times(1)).evaluate(event);
            assertAll("the assessment of three rules that stayed silent",
                    () -> assertTrue(assessment.triggeredRules().isEmpty(), "no identifier"),
                    () -> assertEquals(NO_POINTS, assessment.riskScore(), "score"));
        }

        @Test
        @DisplayName("Calls each rule once, in the order the rules were supplied, and reads "
                + "nothing else from them")
        void callsEachRuleOnceInTheOrderSupplied() {
            TransactionAuthorized event = authorization();
            RiskRule first = triggering(FIRST_RULE, FIRST_POINTS);
            RiskRule second = triggering(SECOND_RULE, SECOND_POINTS);
            RiskRule third = triggering(THIRD_RULE, THIRD_POINTS);

            scorer(first, second, third).assess(event);

            InOrder sequence = inOrder(first, second, third);
            sequence.verify(first, times(1)).evaluate(event);
            sequence.verify(second, times(1)).evaluate(event);
            sequence.verify(third, times(1)).evaluate(event);
            verify(first).ruleId();
            verify(second).ruleId();
            verify(third).ruleId();
            verifyNoMoreInteractions(first, second, third);
        }

        @Test
        @DisplayName("Hands back a rule list that refuses an addition")
        void handsBackARuleListThatRefusesAnAddition() {
            RiskAssessment assessment = scorer(triggering(FIRST_RULE, FIRST_POINTS),
                    silent(SECOND_RULE), silent(THIRD_RULE)).assess(authorization());

            assertThrows(UnsupportedOperationException.class,
                    () -> assessment.triggeredRules().add(SECOND_RULE));
        }
    }

    @Nested
    @DisplayName("The identifier each shipped rule class reports")
    class RuleIdentifierMapping {

        @Test
        @DisplayName("Maps each rule class to one identifier, and no two report the same one")
        void mapsEachRuleClassToOneIdentifier() {
            RiskRule velocity = new VelocityRule(mock(VelocityWindowRepository.class),
                    LOOKBACK_MINUTES, COUNT_THRESHOLD);
            RiskRule amount = new AmountAnomalyRule(AMOUNT_ANOMALY_THRESHOLD);
            RiskRule merchant = new MerchantCategoryRule();

            assertAll("the three identifiers and their distinctness",
                    () -> assertEquals("VELOCITY", velocity.ruleId(), "velocity"),
                    () -> assertEquals("AMOUNT_ANOMALY", amount.ruleId(), "amount anomaly"),
                    () -> assertEquals("MERCHANT_CATEGORY", merchant.ruleId(), "merchant category"),
                    () -> assertNotEquals(velocity.ruleId(), amount.ruleId(), "first pair"),
                    () -> assertNotEquals(amount.ruleId(), merchant.ruleId(), "second pair"),
                    () -> assertNotEquals(velocity.ruleId(), merchant.ruleId(), "third pair"));
        }

        @Test
        @DisplayName("Declares all three rule classes as implementations of the rule interface")
        void declaresAllThreeRuleClassesAsImplementations() {
            assertAll("the three classes the chain accepts",
                    () -> assertTrue(RiskRule.class.isAssignableFrom(VelocityRule.class),
                            "velocity"),
                    () -> assertTrue(RiskRule.class.isAssignableFrom(AmountAnomalyRule.class),
                            "amount anomaly"),
                    () -> assertTrue(RiskRule.class.isAssignableFrom(MerchantCategoryRule.class),
                            "merchant category"));
        }
    }

    private static RiskScoringService scorer(RiskRule... rules) {
        return new RiskScoringService(List.of(rules), countingWindow(), settings());
    }

    private static VelocityWindowRepository countingWindow() {
        VelocityWindowRepository windows = mock(VelocityWindowRepository.class);
        when(windows.addAuthorization(eq(ACCOUNT_ID), any(), any(), any())).thenReturn(ONE_ROW);
        return windows;
    }

    private static RiskRule triggering(String identifier, int points) {
        RiskRule rule = mock(RiskRule.class);
        when(rule.evaluate(any())).thenReturn(RiskRule.Contribution.triggeredWith(points));
        when(rule.ruleId()).thenReturn(identifier);
        return rule;
    }

    private static RiskRule silent(String identifier) {
        RiskRule rule = mock(RiskRule.class);
        when(rule.evaluate(any())).thenReturn(RiskRule.Contribution.notTriggered());
        when(rule.ruleId()).thenReturn(identifier);
        return rule;
    }

    private static TransactionAuthorized authorization() {
        return new TransactionAuthorized(UUID.fromString(EVENT_ID),
                TransactionAuthorized.EVENT_TYPE, EventEnvelope.SCHEMA_VERSION, OCCURRED_AT,
                ACCOUNT_ID, TRANSACTION_ID, TYPE_CODE, CATEGORY_CODE, SOURCE, DESCRIPTION,
                new BigDecimal(FIXTURE_AMOUNT), MERCHANT_ID, MERCHANT_NAME, MERCHANT_CITY,
                MERCHANT_ZIP, MASKED_CARD_NUMBER, null, AUTHORIZED_AT, ACCOUNT_ID,
                TransactionAuthorized.CURRENCY);
    }

    private static FraudProperties settings() {
        return new FraudProperties(null, null, null, null,
                new FraudProperties.Fraud(new FraudProperties.Fraud.Risk(SHIPPED_THRESHOLD,
                        LOOKBACK_MINUTES, COUNT_THRESHOLD,
                        new BigDecimal(AMOUNT_ANOMALY_THRESHOLD))));
    }
}
