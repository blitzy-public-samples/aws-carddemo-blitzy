package com.carddemo.account.outbox;

import com.carddemo.account.config.AccountProperties;
import com.carddemo.account.config.KafkaProducerConfig;
import com.carddemo.account.config.ObservabilityConfig;
import com.carddemo.account.entity.OutboxEventEntity;
import com.carddemo.account.messaging.EventPublisherPort;
import com.carddemo.account.repository.OutboxEventRepository;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Limit;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;
import static org.junit.jupiter.api.Assertions.assertTimeout;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Verifies the total wall-time bound of one relay pass without a broker or a database.
 */
@DisplayName("OutboxRelay total pass deadline")
class OutboxRelayDeadlineTest {

    private static final long PASS_DEADLINE_MS = 100L;

    @Test
    @DisplayName("one broker send cannot hold the sweep past its configured deadline")
    void oneBrokerSendCannotHoldTheSweepPastItsConfiguredDeadline() {
        OutboxEventRepository rows = mock(OutboxEventRepository.class);
        OutboxEventEntity row = mock(OutboxEventEntity.class);
        EventPublisherPort publisher = mock(EventPublisherPort.class);
        CompletableFuture<Void> neverAcknowledged = new CompletableFuture<>();

        when(rows.claimDueRows(any(Instant.class), eq(Limit.of(1)))).thenReturn(List.of(row));
        when(row.getEventType()).thenReturn("AccountStateChanged");
        when(row.getAggregateId()).thenReturn("00000000001");
        when(row.getPayload()).thenReturn("{}");
        when(publisher.publish("account.state-changed", "00000000001", "{}"))
                .thenReturn(neverAcknowledged);

        OutboxRelay relay = new OutboxRelay(rows, publisher, immediateTransactions(),
                properties(), new KafkaProducerConfig(properties()).accountEventObjectMapper(),
                new ObservabilityConfig().accountMeters(new SimpleMeterRegistry()),
                Clock.systemUTC(), "deadline-test");

        assertTimeout(Duration.ofSeconds(2), relay::publishPendingEvents);
        verify(row).recordFailure(eq("RelayDeadlineExceededException"), any(Instant.class),
                any(Instant.class));
        verify(rows).save(row);
    }

    private static AccountProperties properties() {
        return new AccountProperties(
                new AccountProperties.Api(65536L),
                new AccountProperties.Kafka(
                        new AccountProperties.Kafka.Topics(
                                "account.state-changed", "customer.context-changed",
                                "carddemo.dead-letter")),
                new AccountProperties.Outbox(
                        new AccountProperties.Outbox.Relay(
                                500L, 1, "deadline-test", Duration.ofSeconds(30L),
                                PASS_DEADLINE_MS, Duration.ofSeconds(10L)), 168L),
                new AccountProperties.ProcessedEvent(168L),
                new AccountProperties.Retention(3_600_000L));
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
}
