package com.carddemo.card.outbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.carddemo.card.config.CardProperties;
import com.carddemo.card.config.ObservabilityConfig.CardLatencyTimers;
import com.carddemo.card.messaging.EventPublisherPort;
import com.carddemo.card.repository.OutboxEventRepository;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Timer;
import jakarta.persistence.LockModeType;
import java.lang.reflect.Method;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Verifies that a relay sweep claims its rows under a lock, and that it opens the boundary itself.
 */
@DisplayName("Card outbox relay claim")
class OutboxRelayClaimTest {

    private static final String TOPIC = "card.updated";

    private static final int BATCH_SIZE = 3;

    @Test
    @DisplayName("the relay uses the locking claim and never the unlocked diagnostic finder")
    void theRelayUsesTheLockingClaim() {
        OutboxEventRepository rows = mock(OutboxEventRepository.class);
        CardLatencyTimers timers = mock(CardLatencyTimers.class);
        when(timers.eventPublish()).thenReturn(mock(Timer.class));
        when(rows.findByRelayStateAndClaimedAtBeforeOrderByClaimedAtAsc(any(), any(), any()))
                .thenReturn(List.of());
        when(rows.claimDueRows(any(Instant.class), eq(Limit.of(BATCH_SIZE))))
                .thenReturn(List.of());

        OutboxRelay relay = new OutboxRelay(rows, mock(EventPublisherPort.class),
                mock(Counter.class), mock(Counter.class), mock(Counter.class),
                mock(Counter.class), timers, TOPIC, immediateTransactions(), properties());

        relay.publishPendingEvents();

        verify(rows).claimDueRows(any(Instant.class), eq(Limit.of(BATCH_SIZE)));
        verify(rows, never()).findByPublishedFalseOrderByCreatedAtAsc(any());
    }

    /**
     * The sweep opens its own boundary, and carries no declarative one.
     *
     * <p>A method annotated {@code @Transactional} would put the counters inside the transaction,
     * and an increment made inside one survives a rollback. A sweep that then failed to commit
     * would report rows as published that were never marked. The relay therefore runs the claim,
     * the publish and the marking inside {@link TransactionTemplate#execute} and counts afterwards,
     * which is what this assertion pins.
     */
    @Test
    @DisplayName("the scheduled sweep opens its boundary itself rather than declaring one")
    void theScheduledSweepOpensItsOwnBoundary() throws Exception {
        Method sweep = OutboxRelay.class.getMethod("publishPendingEvents");

        assertThat(sweep.isAnnotationPresent(Transactional.class)).isFalse();
        assertThat(OutboxRelay.class.getDeclaredFields())
                .anySatisfy(field -> assertThat(field.getType()).isEqualTo(TransactionTemplate.class));
    }

    @Test
    @DisplayName("the repository claim takes a pessimistic write lock")
    void theRepositoryClaimTakesAPessimisticWriteLock() throws Exception {
        Method claim = OutboxEventRepository.class.getMethod(
                "claimDueRows", Instant.class, Limit.class);

        assertThat(claim.getAnnotation(Lock.class).value())
                .isEqualTo(LockModeType.PESSIMISTIC_WRITE);
    }

    /** Runs a transaction callback directly, so no transaction manager takes part. */
    private static TransactionTemplate immediateTransactions() {
        return new TransactionTemplate() {

            private static final long serialVersionUID = 1L;

            @Override
            public <T> T execute(TransactionCallback<T> action) {
                return action.doInTransaction(new SimpleTransactionStatus());
            }
        };
    }

    /** Returns valid card properties whose relay claims {@value #BATCH_SIZE} rows per sweep. */
    private static CardProperties properties() {
        return new CardProperties(
                new CardProperties.Api(65536L),
                new CardProperties.Kafka(new CardProperties.Kafka.Topics(TOPIC, "carddemo.dead-letter")),
                new CardProperties.Outbox(new CardProperties.Outbox.Relay(
                        500L, BATCH_SIZE, "card-relay", Duration.ofMinutes(2L)), 168L),
                new CardProperties.ProcessedEvent(168L),
                new CardProperties.Retention(3_600_000L));
    }
}
