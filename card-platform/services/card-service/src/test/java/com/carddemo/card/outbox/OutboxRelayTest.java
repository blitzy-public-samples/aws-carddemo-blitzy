package com.carddemo.card.outbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.carddemo.card.config.CardProperties;
import com.carddemo.card.config.ObservabilityConfig.CardLatencyTimers;
import com.carddemo.card.entity.OutboxEventEntity;
import com.carddemo.card.messaging.CardUpdated;
import com.carddemo.card.messaging.EventPublisherPort;
import com.carddemo.card.repository.OutboxEventRepository;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Timer;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

/** Verifies the claimed-row state machine used by the card outbox relay. */
class OutboxRelayTest {

    private static final String ACCOUNT_ID = "00000000077";
    private static final String TOPIC = "card.updated";

    private OutboxEventRepository outboxEvents;
    private EventPublisherPort publisher;
    private Counter eventsPublished;
    private Counter failures;
    private Timer publishLatency;
    private TransactionTemplate transactionTemplate;
    private AtomicBoolean insideTransaction;
    private OutboxRelay relay;

    @BeforeEach
    void setUp() {
        outboxEvents = mock(OutboxEventRepository.class);
        publisher = mock(EventPublisherPort.class);
        eventsPublished = mock(Counter.class);
        failures = mock(Counter.class);
        publishLatency = mock(Timer.class);
        transactionTemplate = mock(TransactionTemplate.class);
        insideTransaction = new AtomicBoolean();

        CardLatencyTimers timers = mock(CardLatencyTimers.class);
        when(timers.eventPublish()).thenReturn(publishLatency);
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

        relay = new OutboxRelay(outboxEvents, publisher, eventsPublished, failures, timers, TOPIC,
                transactionTemplate, properties());
    }

    @Test
    void aDueRowIsClaimedBeforeItIsPublishedAndMarked() {
        OutboxEventEntity row = row();
        when(outboxEvents.claimDueRows(any(), any())).thenReturn(List.of(row));
        doAnswer(call -> {
            assertThat(row.getRelayState()).isEqualTo(OutboxEventEntity.RelayState.CLAIMED);
            assertThat(row.getClaimedBy()).isEqualTo("card-relay");
            return null;
        }).when(publisher).publish(TOPIC, ACCOUNT_ID, "{}");
        doAnswer(call -> {
            assertFalse(insideTransaction.get(), "success meters are recorded after commit");
            return null;
        }).when(eventsPublished).increment();

        relay.publishPendingEvents();

        assertTrue(row.isPublished());
        assertThat(row.getClaimedBy()).isNull();
        verify(publisher).publish(TOPIC, ACCOUNT_ID, "{}");
        verify(eventsPublished).increment();
        verify(publishLatency).record(anyLong(), any());
    }

    @Test
    void anInfrastructureFailureBacksOffTheRowAndStopsTheSweep() {
        OutboxEventEntity first = row();
        OutboxEventEntity second = row();
        when(outboxEvents.claimDueRows(any(), any())).thenReturn(List.of(first, second));
        doThrow(new IllegalStateException("broker unavailable")).when(publisher)
                .publish(anyString(), anyString(), anyString());
        doAnswer(call -> {
            assertFalse(insideTransaction.get(), "failure meters are recorded after commit");
            return null;
        }).when(failures).increment();

        relay.publishPendingEvents();

        assertThat(first.getRelayState()).isEqualTo(OutboxEventEntity.RelayState.PENDING);
        assertThat(first.getAttemptCount()).isEqualTo(1);
        assertThat(first.getNextAttemptAt()).isAfter(first.getLastAttemptAt());
        assertThat(second.getAttemptCount()).isZero();
        verify(publisher, times(1)).publish(anyString(), anyString(), anyString());
        verify(eventsPublished, never()).increment();
        verify(failures).increment();
    }

    @Test
    void aStrandedClaimIsRecoveredBeforeTheRowIsPublished() {
        OutboxEventEntity row = row();
        row.claim("stopped-instance", Instant.now().minus(Duration.ofMinutes(3L)));
        when(outboxEvents.findByRelayStateAndClaimedAtBeforeOrderByClaimedAtAsc(
                any(), any(), any())).thenReturn(List.of(row));
        when(outboxEvents.claimDueRows(any(), any())).thenReturn(List.of(row));

        relay.publishPendingEvents();

        assertTrue(row.isPublished());
        assertThat(row.getAttemptCount()).isEqualTo(1);
        verify(publisher).publish(TOPIC, ACCOUNT_ID, "{}");
    }

    private static OutboxEventEntity row() {
        return new OutboxEventEntity(
                UUID.randomUUID(),
                CardUpdated.EVENT_TYPE,
                ACCOUNT_ID,
                "{}",
                Instant.now());
    }

    private static CardProperties properties() {
        return new CardProperties(
                new CardProperties.Api(65536L),
                new CardProperties.Kafka(new CardProperties.Kafka.Topics(TOPIC)),
                new CardProperties.Outbox(new CardProperties.Outbox.Relay(
                        500L,
                        100,
                        "card-relay",
                        Duration.ofMinutes(2L)), 168L),
                new CardProperties.ProcessedEvent(168L),
                new CardProperties.Retention(3_600_000L));
    }
}