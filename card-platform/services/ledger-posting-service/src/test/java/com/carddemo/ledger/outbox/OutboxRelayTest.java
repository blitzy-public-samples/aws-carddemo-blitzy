package com.carddemo.ledger.outbox;

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

import com.carddemo.events.TransactionPosted;
import com.carddemo.ledger.config.LedgerProperties;
import com.carddemo.ledger.config.ObservabilityConfig.LedgerMeters;
import com.carddemo.ledger.entity.OutboxEventEntity;
import com.carddemo.ledger.repository.OutboxEventRepository;
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
import tools.jackson.databind.json.JsonMapper;

/** Verifies the claimed-row state machine used by the ledger outbox relay. */
class OutboxRelayTest {

    private static final String ACCOUNT_ID = "00000000077";
    private static final String POSTED_TOPIC = "transaction.posted";
    private static final String DECLINED_TOPIC = "transaction.declined";

    private OutboxEventRepository outboxEvents;
    private KafkaTemplate<String, Object> kafkaTemplate;
    private LedgerMeters meters;
    private TransactionTemplate transactionTemplate;
    private AtomicBoolean insideTransaction;
    private OutboxWriter writer;
    private OutboxRelay relay;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        outboxEvents = mock(OutboxEventRepository.class);
        kafkaTemplate = mock(KafkaTemplate.class);
        meters = mock(LedgerMeters.class);
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

        JsonMapper mapper = JsonMapper.builder().build();
        writer = new OutboxWriter(outboxEvents, mapper);
        relay = new OutboxRelay(outboxEvents, kafkaTemplate, mapper, transactionTemplate,
                properties(), meters);
    }

    @Test
    void aDueRowIsClaimedBeforeItIsPublishedAndMarked() {
        OutboxEventEntity row = postedRow();
        when(outboxEvents.claimDueRows(any(), any())).thenReturn(List.of(row));
        when(kafkaTemplate.send(eq(POSTED_TOPIC), eq(ACCOUNT_ID), any()))
                .thenAnswer(call -> {
                    assertThat(row.getRelayState())
                            .isEqualTo(OutboxEventEntity.RelayState.CLAIMED);
                    assertThat(row.getClaimedBy()).isEqualTo("ledger-relay");
                    return CompletableFuture.completedFuture(null);
                });

        relay.publishPendingEvents();

        assertTrue(row.isPublished());
        assertThat(row.getClaimedBy()).isNull();
        verify(kafkaTemplate).send(eq(POSTED_TOPIC), eq(ACCOUNT_ID), any());
        verify(meters, never()).recordFailure(LedgerMeters.PUBLISH_STAGE);
    }

    @Test
    void aBrokerFailureBacksOffTheRowAndStopsTheSweep() {
        OutboxEventEntity first = postedRow();
        OutboxEventEntity second = postedRow();
        when(outboxEvents.claimDueRows(any(), any())).thenReturn(List.of(first, second));
        when(kafkaTemplate.send(eq(POSTED_TOPIC), anyString(), any()))
                .thenReturn(CompletableFuture.failedFuture(
                        new IllegalStateException("broker unavailable")));
        org.mockito.Mockito.doAnswer(call -> {
            assertThat(insideTransaction.get()).isFalse();
            return null;
        }).when(meters).recordFailure(LedgerMeters.PUBLISH_STAGE);

        relay.publishPendingEvents();

        assertThat(first.getRelayState()).isEqualTo(OutboxEventEntity.RelayState.PENDING);
        assertThat(first.getAttemptCount()).isEqualTo(1);
        assertThat(first.getNextAttemptAt()).isAfter(first.getLastAttemptAt());
        assertThat(second.getAttemptCount()).isZero();
        verify(kafkaTemplate, times(1)).send(eq(POSTED_TOPIC), anyString(), any());
        verify(meters).recordFailure(LedgerMeters.PUBLISH_STAGE);
    }

    @Test
    void aStrandedClaimIsRecoveredBeforeTheRowIsPublished() {
        OutboxEventEntity row = postedRow();
        row.claim("stopped-instance", Instant.now().minus(Duration.ofMinutes(3L)));
        when(outboxEvents.findByRelayStateAndClaimedAtBeforeOrderByClaimedAtAsc(
                any(), any(), any())).thenReturn(List.of(row));
        when(outboxEvents.claimDueRows(any(), any())).thenReturn(List.of(row));
        when(kafkaTemplate.send(eq(POSTED_TOPIC), eq(ACCOUNT_ID), any()))
                .thenReturn(CompletableFuture.completedFuture(null));

        relay.publishPendingEvents();

        assertTrue(row.isPublished());
        assertThat(row.getAttemptCount()).isEqualTo(1);
        verify(kafkaTemplate).send(eq(POSTED_TOPIC), eq(ACCOUNT_ID), any());
        verify(meters).recordFailure(LedgerMeters.PUBLISH_STAGE);
    }

    private OutboxEventEntity postedRow() {
        TransactionPosted event = TransactionPosted.forAccount(
                ACCOUNT_ID,
                "0000000000683580",
                new BigDecimal("1500.00"),
                "2022-06-10-19.27.53.410000",
                new BigDecimal("504.77"),
                "************7065");
        return writer.write(event);
    }

    private static LedgerProperties properties() {
        return new LedgerProperties(
                new LedgerProperties.Kafka(new LedgerProperties.Kafka.Topics(
                        "transaction.authorized",
                        POSTED_TOPIC,
                        DECLINED_TOPIC,
                        "carddemo.dead-letter",
                        ".DLT")),
                new LedgerProperties.Consumer(new LedgerProperties.Consumer.Retry(3, 1000L)),
                new LedgerProperties.Outbox(new LedgerProperties.Outbox.Relay(
                        500L,
                        100,
                        "ledger-relay",
                        Duration.ofMinutes(2L)), 168L),
                new LedgerProperties.ProcessedEvent(168L),
                new LedgerProperties.Retention(3_600_000L));
    }
}