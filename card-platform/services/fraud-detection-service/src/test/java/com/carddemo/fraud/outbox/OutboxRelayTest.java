package com.carddemo.fraud.outbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.carddemo.events.FraudCleared;
import com.carddemo.fraud.config.FraudProperties;
import com.carddemo.fraud.config.ObservabilityConfig.FraudMeters;
import com.carddemo.fraud.entity.OutboxEventEntity;
import com.carddemo.fraud.repository.OutboxEventRepository;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

/** Verifies the claimed-row state machine used by the fraud outbox relay. */
class OutboxRelayTest {

    private static final String ACCOUNT_ID = "00000000077";
    private static final String TRANSACTION_ID = "0000000000683580";
    private static final String ASSESSED_TOPIC = "fraud.assessed";
    private static final String DEAD_LETTER_TOPIC = "carddemo.dead-letter";

    private OutboxEventRepository outboxEvents;
    private KafkaTemplate<String, Object> kafkaTemplate;
    private FraudMeters meters;
    private TransactionTemplate transactionTemplate;
    private AtomicBoolean insideTransaction;
    private OutboxWriter writer;
    private OutboxRelay relay;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        outboxEvents = mock(OutboxEventRepository.class);
        kafkaTemplate = mock(KafkaTemplate.class);
        meters = mock(FraudMeters.class);
        transactionTemplate = mock(TransactionTemplate.class);
        insideTransaction = new AtomicBoolean();

        when(outboxEvents.save(any(OutboxEventEntity.class)))
                .thenAnswer(call -> call.getArgument(0));
        when(outboxEvents.findByRelayStateAndClaimedAtBeforeOrderByClaimedAtAsc(
                any(), any(), any())).thenReturn(List.of());
        when(transactionTemplate.execute(any())).thenAnswer(call -> {
            TransactionCallback<?> callback = call.getArgument(0);
            insideTransaction.set(true);
            try {
                return callback.doInTransaction(mock(TransactionStatus.class));
            } finally {
                insideTransaction.set(false);
            }
        });

        writer = new OutboxWriter(outboxEvents);
        relay = new OutboxRelay(outboxEvents, kafkaTemplate, meters, ASSESSED_TOPIC,
                DEAD_LETTER_TOPIC, transactionTemplate, properties());
    }

    @Test
    void aDueRowIsClaimedBeforeItIsPublishedAndMarked() {
        OutboxEventEntity row = clearedRow();
        when(outboxEvents.claimDueRows(any(), any())).thenReturn(List.of(row));
        when(kafkaTemplate.send(eq(ASSESSED_TOPIC), eq(ACCOUNT_ID), any()))
                .thenAnswer(call -> {
                    assertThat(row.getRelayState())
                            .isEqualTo(OutboxEventEntity.RelayState.CLAIMED);
                    assertThat(row.getClaimedBy()).isEqualTo("fraud-relay");
                    return CompletableFuture.completedFuture(null);
                });

        relay.publishPendingEvents();

        assertTrue(row.isPublished());
        assertThat(row.getClaimedBy()).isNull();
        verify(kafkaTemplate).send(eq(ASSESSED_TOPIC), eq(ACCOUNT_ID), any());
        verify(meters, never()).recordPublishFailure();
    }

    @Test
    void aBrokerFailureBacksOffTheRowAndStopsTheTick() {
        OutboxEventEntity first = clearedRow();
        OutboxEventEntity second = clearedRow();
        when(outboxEvents.claimDueRows(any(), any())).thenReturn(List.of(first, second));
        when(kafkaTemplate.send(eq(ASSESSED_TOPIC), anyString(), any()))
                .thenReturn(CompletableFuture.failedFuture(
                        new IllegalStateException("broker unavailable")));
        org.mockito.Mockito.doAnswer(call -> {
            assertThat(insideTransaction.get()).isFalse();
            return null;
        }).when(meters).recordPublishFailure();

        relay.publishPendingEvents();

        assertThat(first.getRelayState()).isEqualTo(OutboxEventEntity.RelayState.PENDING);
        assertThat(first.getAttemptCount()).isEqualTo(1);
        assertThat(first.getNextAttemptAt()).isAfter(first.getLastAttemptAt());
        assertThat(second.getAttemptCount()).isZero();
        verify(kafkaTemplate, times(1)).send(eq(ASSESSED_TOPIC), anyString(), any());
        verify(meters).recordPublishFailure();
    }

    @Test
    void aStrandedClaimIsRecoveredBeforeTheRowIsPublished() {
        OutboxEventEntity row = clearedRow();
        row.claim("stopped-instance", Instant.now().minus(Duration.ofMinutes(3L)));
        when(outboxEvents.findByRelayStateAndClaimedAtBeforeOrderByClaimedAtAsc(
                any(), any(), any())).thenReturn(List.of(row));
        when(outboxEvents.claimDueRows(any(), any())).thenReturn(List.of(row));
        when(kafkaTemplate.send(eq(ASSESSED_TOPIC), eq(ACCOUNT_ID), any()))
                .thenReturn(CompletableFuture.completedFuture(null));

        relay.publishPendingEvents();

        assertTrue(row.isPublished());
        assertThat(row.getAttemptCount()).isEqualTo(1);
        verify(kafkaTemplate).send(eq(ASSESSED_TOPIC), eq(ACCOUNT_ID), any());
        verify(meters).recordPublishFailure();
    }

    private OutboxEventEntity clearedRow() {
        return writer.write(FraudCleared.of(TRANSACTION_ID, ACCOUNT_ID, Instant.now()));
    }

    private static FraudProperties properties() {
        return new FraudProperties(
                new FraudProperties.Kafka(new FraudProperties.Kafka.Topics(
                        "transaction.authorized",
                        ASSESSED_TOPIC,
                        DEAD_LETTER_TOPIC,
                        ".DLT")),
                new FraudProperties.Consumer(new FraudProperties.Consumer.Retry(3, 1000L)),
                new FraudProperties.Outbox(new FraudProperties.Outbox.Relay(
                        500L,
                        100,
                        "fraud-relay",
                        Duration.ofMinutes(2L),
                        20_000L), 168L),
                new FraudProperties.ProcessedEvent(168L),
                new FraudProperties.Retention(3_600_000L),
                new FraudProperties.Fraud(new FraudProperties.Fraud.Risk(
                        50,
                        5,
                        3,
                        new BigDecimal("1000.00"))));
    }
}