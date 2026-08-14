package com.carddemo.authorization.outbox;

import static org.junit.jupiter.api.Assertions.assertTimeout;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.carddemo.authorization.config.AuthorizationProperties;
import com.carddemo.authorization.entity.OutboxEventEntity;
import com.carddemo.authorization.messaging.EventPublisherPort;
import com.carddemo.authorization.repository.OutboxEventRepository;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Limit;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Verifies the total wall-time bound of one relay pass, with no broker and no database.
 *
 * <p>The bound is the reason {@code carddemo.outbox.relay.max-duration-ms} exists. Before it, the
 * relay joined each send with no deadline of its own: a broker that accepted a send and never
 * acknowledged it held the scheduler thread, the database connection and every row lock of the pass
 * for as long as the process ran. The pass reads one monotonic deadline at its start and awaits every
 * send against what remains of it, so an unacknowledged send costs one pass.
 *
 * <p>Nothing here starts a container. The publisher answers with a stage that is never completed,
 * which is exactly what an accepted-but-unacknowledged send looks like to the relay, and the
 * transaction template runs each callback directly.
 *
 * <p>Traceability: {@code card-platform/docs/traceability-matrix.md}. Decision log:
 * {@code card-platform/docs/decision-log.md}.
 */
@DisplayName("OutboxRelay total pass deadline")
class OutboxRelayDeadlineTest {

    /** The whole-pass budget under test, short so the assertion is quick and unambiguous. */
    private static final long PASS_DEADLINE_MS = 100L;

    /** The topic an approval row resolves to, and the one the stalled send is issued against. */
    private static final String AUTHORIZED_TOPIC = "transaction.authorized";

    /** The account the stalled row is keyed by, eleven digits from {@code ACCT-ID PIC 9(11)}. */
    private static final String ACCOUNT_ID = "00000000001";

    @Test
    @DisplayName("one broker send cannot hold the pass past its configured deadline")
    void oneBrokerSendCannotHoldThePassPastItsConfiguredDeadline() {
        OutboxEventRepository rows = mock(OutboxEventRepository.class);
        OutboxEventEntity row = mock(OutboxEventEntity.class);
        EventPublisherPort publisher = mock(EventPublisherPort.class);
        UUID eventId = UUID.randomUUID();
        CompletableFuture<Void> neverAcknowledged = new CompletableFuture<>();

        when(rows.claimDueRows(any(Instant.class), eq(Limit.of(1)))).thenReturn(List.of(row));
        when(rows.findById(eventId)).thenReturn(Optional.of(row));
        when(row.getEventId()).thenReturn(eventId);
        when(row.getEventType()).thenReturn("TransactionAuthorized");
        when(row.getAggregateId()).thenReturn(ACCOUNT_ID);
        when(row.getPayload()).thenReturn("{}");
        when(publisher.publish(AUTHORIZED_TOPIC, ACCOUNT_ID, "{}"))
                .thenReturn(neverAcknowledged);

        OutboxRelay relay = new OutboxRelay(rows, publisher, new SimpleMeterRegistry(),
                immediateTransactions(), properties(), Clock.systemUTC());

        assertTimeout(Duration.ofSeconds(2L), relay::publishPendingEvents);
        verify(row).recordFailure(eq("RelayDeadlineExceededException"), any(Instant.class),
                any(Instant.class));
        verify(publisher).publish(AUTHORIZED_TOPIC, ACCOUNT_ID, "{}");
    }

    /**
     * Builds settings whose only unusual value is the short pass deadline.
     *
     * @return one bound settings tree with a hundred-millisecond pass budget
     */
    private static AuthorizationProperties properties() {
        return new AuthorizationProperties(
                new AuthorizationProperties.Kafka(
                        new AuthorizationProperties.Kafka.Topics(
                                AUTHORIZED_TOPIC, "transaction.declined",
                                "account.state-changed", "card.updated", "carddemo.dead-letter"),
                        new AuthorizationProperties.Kafka.Groups(
                                "authorization-account-state", "authorization-card-updated")),
                new AuthorizationProperties.Outbox(
                        new AuthorizationProperties.Outbox.Relay(
                                500L, 1, "deadline-test", Duration.ofSeconds(30L),
                                PASS_DEADLINE_MS, Duration.ofSeconds(10L)),
                        168L),
                new AuthorizationProperties.Retention(3_600_000L, 400L),
                new AuthorizationProperties.Replica(0L),
                new AuthorizationProperties.Decision(3_000L, Duration.ofMinutes(15L)));
    }

    /**
     * Runs each transaction callback directly, so no transaction manager takes part.
     *
     * @return a template that executes its callback on the calling thread
     */
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
