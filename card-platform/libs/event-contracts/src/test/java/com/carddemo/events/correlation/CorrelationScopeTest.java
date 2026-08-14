package com.carddemo.events.correlation;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

/** Proves one scope leaves the handling thread as it found it. */
@DisplayName("The correlation fields of one request or one delivery")
class CorrelationScopeTest {

    private static final UUID CORRELATION = UUID.fromString("11111111-2222-3333-4444-555555555555");

    private static final UUID CAUSATION = UUID.fromString("66666666-7777-8888-9999-aaaaaaaaaaaa");

    private static final UUID HANDLED = UUID.fromString("bbbbbbbb-cccc-dddd-eeee-ffffffffffff");

    @AfterEach
    void clearTheThread() {
        MDC.clear();
    }

    @Test
    @DisplayName("every field is readable inside the scope")
    void everyFieldIsReadableInsideTheScope() {
        try (CorrelationScope _ = CorrelationScope.open()
                .withCorrelation(CORRELATION)
                .withCausation(CAUSATION)
                .withEvent(HANDLED, "TransactionPosted")
                .withTransaction("0000000000000042")) {

            assertThat(MDC.get(EventCorrelation.CORRELATION_ID_FIELD))
                    .isEqualTo(CORRELATION.toString());
            assertThat(MDC.get(EventCorrelation.CAUSATION_ID_FIELD))
                    .isEqualTo(CAUSATION.toString());
            assertThat(MDC.get(EventCorrelation.EVENT_ID_FIELD)).isEqualTo(HANDLED.toString());
            assertThat(MDC.get(EventCorrelation.EVENT_TYPE_FIELD)).isEqualTo("TransactionPosted");
            assertThat(MDC.get(EventCorrelation.TRANSACTION_ID_FIELD))
                    .isEqualTo("0000000000000042");
        }
    }

    @Test
    @DisplayName("closing removes every key the scope introduced")
    void closingRemovesEveryKeyTheScopeIntroduced() {
        try (CorrelationScope _ = CorrelationScope.open()
                .withCorrelation(CORRELATION)
                .withEvent(HANDLED, "TransactionPosted")) {
            assertThat(MDC.getCopyOfContextMap()).isNotEmpty();
        }

        assertThat(MDC.get(EventCorrelation.CORRELATION_ID_FIELD)).isNull();
        assertThat(MDC.get(EventCorrelation.EVENT_ID_FIELD)).isNull();
        assertThat(MDC.get(EventCorrelation.EVENT_TYPE_FIELD)).isNull();
    }

    @Test
    @DisplayName("a nested scope restores the value the outer scope set")
    void aNestedScopeRestoresTheOuterValue() {
        try (CorrelationScope _ = CorrelationScope.open().withCorrelation(CORRELATION)) {
            try (CorrelationScope _ = CorrelationScope.open().withCorrelation(CAUSATION)) {
                assertThat(MDC.get(EventCorrelation.CORRELATION_ID_FIELD))
                        .isEqualTo(CAUSATION.toString());
            }
            assertThat(MDC.get(EventCorrelation.CORRELATION_ID_FIELD))
                    .isEqualTo(CORRELATION.toString());
        }
        assertThat(MDC.get(EventCorrelation.CORRELATION_ID_FIELD)).isNull();
    }

    @Test
    @DisplayName("a delivery on a reused thread leaves nothing behind for the next one")
    void aDeliveryLeavesNothingBehind() {
        for (int delivery = 0; delivery < 3; delivery++) {
            UUID handled = UUID.randomUUID();
            try (CorrelationScope _ = CorrelationScope.open()
                    .withCorrelation(CORRELATION)
                    .withEvent(handled, "TransactionAuthorized")) {
                assertThat(MDC.get(EventCorrelation.EVENT_ID_FIELD)).isEqualTo(handled.toString());
            }
            assertThat(MDC.getCopyOfContextMap() == null || MDC.getCopyOfContextMap().isEmpty())
                    .as("delivery %s left a field behind", delivery)
                    .isTrue();
        }
    }

    @Test
    @DisplayName("an absent value writes no field rather than an empty one")
    void anAbsentValueWritesNoField() {
        try (CorrelationScope _ = CorrelationScope.open()
                .withCorrelation(null)
                .withCausation(null)
                .withEvent(null, null)
                .withTransaction("  ")) {

            assertThat(MDC.get(EventCorrelation.CORRELATION_ID_FIELD)).isNull();
            assertThat(MDC.get(EventCorrelation.CAUSATION_ID_FIELD)).isNull();
            assertThat(MDC.get(EventCorrelation.EVENT_ID_FIELD)).isNull();
            assertThat(MDC.get(EventCorrelation.EVENT_TYPE_FIELD)).isNull();
            assertThat(MDC.get(EventCorrelation.TRANSACTION_ID_FIELD)).isNull();
        }
    }

    @Test
    @DisplayName("closing twice is harmless")
    void closingTwiceIsHarmless() {
        CorrelationScope scope = CorrelationScope.open().withCorrelation(CORRELATION);
        scope.close();
        MDC.put(EventCorrelation.CORRELATION_ID_FIELD, CAUSATION.toString());
        scope.close();

        assertThat(MDC.get(EventCorrelation.CORRELATION_ID_FIELD)).isEqualTo(CAUSATION.toString());
    }

    @Test
    @DisplayName("a key written twice in one scope restores what it held before the first write")
    void aKeyWrittenTwiceRestoresTheOriginal() {
        MDC.put(EventCorrelation.EVENT_TYPE_FIELD, "Original");
        try (CorrelationScope _ = CorrelationScope.open()
                .with(EventCorrelation.EVENT_TYPE_FIELD, "First")
                .with(EventCorrelation.EVENT_TYPE_FIELD, "Second")) {
            assertThat(MDC.get(EventCorrelation.EVENT_TYPE_FIELD)).isEqualTo("Second");
        }

        assertThat(MDC.get(EventCorrelation.EVENT_TYPE_FIELD)).isEqualTo("Original");
    }
}
