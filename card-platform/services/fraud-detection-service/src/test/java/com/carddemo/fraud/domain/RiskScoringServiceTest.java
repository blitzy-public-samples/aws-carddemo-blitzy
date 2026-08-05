package com.carddemo.fraud.domain;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.carddemo.events.EventEnvelope;
import com.carddemo.events.TransactionAuthorized;
import com.carddemo.fraud.config.FraudProperties;
import com.carddemo.fraud.domain.RiskScoringService.RiskAssessment;
import com.carddemo.fraud.repository.VelocityWindowRepository;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;

/**
 * Verifies the configured verdict threshold and the atomic, current-event velocity update.
 */
@DisplayName("RiskScoringService threshold and velocity ordering")
class RiskScoringServiceTest {

    private static final String ACCOUNT_ID = "00000000007";
    private static final String TRANSACTION_ID = "0000000000683580";
    private static final Instant OCCURRED_AT = Instant.parse("2022-06-10T19:27:53Z");

    @Test
    @DisplayName("one thirty-point rule stays cleared at fifty and is flagged at thirty")
    void configuredThresholdDeterminesTheVerdict() {
        TransactionAuthorized event = authorization("504.77");
        RiskRule rule = triggeredRule("AMOUNT_ANOMALY", 30, event);

        VelocityWindowRepository clearedWindows = acceptingWindowUpdate(event);
        RiskAssessment cleared = new RiskScoringService(List.of(rule), clearedWindows,
                propertiesWithFlagThreshold(50)).assess(event);

        VelocityWindowRepository flaggedWindows = acceptingWindowUpdate(event);
        RiskAssessment flagged = new RiskScoringService(List.of(rule), flaggedWindows,
                propertiesWithFlagThreshold(30)).assess(event);

        assertAll(
                () -> assertEquals(30, cleared.riskScore(), "cleared score"),
                () -> assertFalse(cleared.flagged(), "fifty-point threshold"),
                () -> assertEquals(List.of("AMOUNT_ANOMALY"), cleared.triggeredRules(),
                        "cleared contributing rules"),
                () -> assertTrue(flagged.flagged(), "thirty-point threshold"));
    }

    @Test
    @DisplayName("the current refund magnitude is atomically added before any rule evaluates")
    void currentRefundIsAddedBeforeRulesRun() {
        TransactionAuthorized event = authorization("-919.00");
        VelocityWindowRepository windows = mock(VelocityWindowRepository.class);
        when(windows.addAuthorization(eq(ACCOUNT_ID), any(), any(), any())).thenReturn(1);
        RiskRule rule = triggeredRule("VELOCITY", 30, event);

        new RiskScoringService(List.of(rule), windows, propertiesWithFlagThreshold(50))
                .assess(event);

        ArgumentCaptor<Instant> bucket = ArgumentCaptor.forClass(Instant.class);
        ArgumentCaptor<BigDecimal> magnitude = ArgumentCaptor.forClass(BigDecimal.class);
        InOrder order = inOrder(windows, rule);
        order.verify(windows).addAuthorization(
                eq(ACCOUNT_ID), bucket.capture(), magnitude.capture(), any());
        order.verify(rule).evaluate(event);
        assertAll(
                () -> assertEquals(OCCURRED_AT.truncatedTo(ChronoUnit.HOURS), bucket.getValue(),
                        "hourly bucket"),
                () -> assertEquals(new BigDecimal("919.00"), magnitude.getValue(),
                        "refund magnitude"));
    }

    @Test
    @DisplayName("an unexpected atomic-update row count stops scoring")
    void unexpectedWindowUpdateCountStopsScoring() {
        TransactionAuthorized event = authorization("10.00");
        VelocityWindowRepository windows = mock(VelocityWindowRepository.class);
        when(windows.addAuthorization(eq(ACCOUNT_ID), any(), any(), any())).thenReturn(0);
        RiskRule rule = mock(RiskRule.class);

        assertThrows(IllegalStateException.class,
                () -> new RiskScoringService(List.of(rule), windows,
                        propertiesWithFlagThreshold(50)).assess(event));
        verify(rule, never()).evaluate(event);
    }

    @Test
    @DisplayName("a threshold outside the event-contract range is refused")
    void invalidThresholdIsRefused() {
        VelocityWindowRepository windows = mock(VelocityWindowRepository.class);
        assertThrows(IllegalArgumentException.class,
                () -> new RiskScoringService(List.of(), windows, propertiesWithFlagThreshold(0)));
        assertThrows(IllegalArgumentException.class,
                () -> new RiskScoringService(List.of(), windows, propertiesWithFlagThreshold(101)));
    }

    private static VelocityWindowRepository acceptingWindowUpdate(TransactionAuthorized event) {
        VelocityWindowRepository windows = mock(VelocityWindowRepository.class);
        when(windows.addAuthorization(
                eq(event.accountId()), any(), any(), any())).thenReturn(1);
        return windows;
    }

    private static RiskRule triggeredRule(
            String identifier, int points, TransactionAuthorized event) {
        RiskRule rule = mock(RiskRule.class);
        when(rule.evaluate(event)).thenReturn(RiskRule.Contribution.triggeredWith(points));
        when(rule.ruleId()).thenReturn(identifier);
        return rule;
    }

    private static TransactionAuthorized authorization(String amount) {
        return new TransactionAuthorized(
                UUID.fromString("7f1c9a2e-4b6d-4a11-9c3e-5d8f2a6b0c41"),
                TransactionAuthorized.EVENT_TYPE,
                EventEnvelope.SCHEMA_VERSION,
                OCCURRED_AT,
                ACCOUNT_ID,
                TRANSACTION_ID,
                "01",
                "0001",
                "POS TERM",
                "Purchase at Abshire-Lowe",
                new BigDecimal(amount),
                "800000000",
                "Abshire-Lowe",
                "North Enoshaven",
                "72112",
                "************7065",
                null,
                "2022-06-10 19:27:53.000000",
                ACCOUNT_ID,
                TransactionAuthorized.CURRENCY);
    }

    /**
     * Builds the bound settings this service reads, carrying one flag threshold.
     *
     * <p>Every other value is the one the shipped {@code application.yml} carries, because none
     * of them takes part in the verdict these assertions measure.
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
                new FraudProperties.ProcessedEvent(168L),
                new FraudProperties.Retention(3_600_000L),
                new FraudProperties.Fraud(new FraudProperties.Fraud.Risk(
                        threshold, 60, 5, new BigDecimal("500.00"))));
    }

}
