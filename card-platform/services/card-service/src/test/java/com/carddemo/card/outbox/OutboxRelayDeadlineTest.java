package com.carddemo.card.outbox;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTimeout;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
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
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Limit;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Proves one sweep cannot outlast its configured deadline, whatever the broker does.
 *
 * <p>No COBOL ancestor. The source's one asynchronous handoff writes to a transient data queue at
 * {@code app/cbl/CORPT00C.cbl:L517-L523} and waits for nothing, so it has no equivalent of a send
 * that never completes.
 *
 * <p>The defect these tests close had two halves that compounded. The publisher waited ten seconds
 * for each acknowledgement while the producer was configured to keep trying for two minutes, so a
 * wait that ran out left a send the producer still held: nothing cancelled it, nothing observed it,
 * the row stayed unpublished, and the next sweep published the same event a second time. And
 * nothing
 * bounded the sweep as a whole, so a hundred claimed rows could each consume that ten-second wait
 * in
 * turn while the claim transaction stayed open.
 *
 * <p>Both halves are now one budget. {@code src/main/resources/application.yml} sets the producer
 * to
 * give up before the sweep does, and {@code carddemo.outbox.relay.max-duration-ms} bounds the sweep
 * on the monotonic clock. The first test proves the bound holds against a send that never answers
 * at
 * all, which is the case no per-send timeout can bound when the sweep has many rows to get through.
 *
 * <p>Nothing here opens a connection or reaches a broker. The publisher answers a stage that never
 * completes, and the transaction template runs its callback directly.
 *
 * <p>Design decisions: {@code card-platform/docs/decision-log.md}.
 */
@DisplayName("OutboxRelay, the bound on one whole sweep")
class OutboxRelayDeadlineTest {

    /** The whole-sweep budget under test, short so the assertion is quick and unambiguous. */
    private static final long SWEEP_DEADLINE_MS = 100L;

    /** The topic a card update resolves to, and the one the stalled send is issued against. */
    private static final String TOPIC = "card.updated";

    /** Where a diagnostic would go, named so the relay can be built. */
    private static final String DEAD_LETTER_TOPIC = "carddemo.dead-letter";

    /** The account the stalled row is keyed by: eleven digits of {@code CARD-ACCT-ID PIC 9(11)}. */
    private static final String ACCOUNT_ID = "00000000001";

    @Test
    @DisplayName("a send that never answers cannot hold the sweep past its deadline")
    void aSendThatNeverAnswersCannotHoldTheSweepPastItsDeadline() {
        OutboxEventRepository rows = mock(OutboxEventRepository.class);
        OutboxEventEntity row = mock(OutboxEventEntity.class);
        EventPublisherPort publisher = mock(EventPublisherPort.class);
        Counter failures = mock(Counter.class);
        UUID eventId = UUID.randomUUID();
        CompletableFuture<Void> neverAcknowledged = new CompletableFuture<>();

        when(rows.findByRelayStateAndClaimedAtBeforeOrderByClaimedAtAsc(any(), any(), any()))
                .thenReturn(List.of());
        when(rows.claimDueRows(any(Instant.class), eq(Limit.of(1)))).thenReturn(List.of(row));
        when(rows.save(any(OutboxEventEntity.class))).thenAnswer(call -> call.getArgument(0));
        when(row.getEventId()).thenReturn(eventId);
        when(row.getEventType()).thenReturn(CardUpdated.EVENT_TYPE);
        when(row.getAggregateId()).thenReturn(ACCOUNT_ID);
        when(row.getPayload()).thenReturn("{}");
        when(publisher.publish(eq(TOPIC), eq(ACCOUNT_ID), anyString()))
                .thenReturn(neverAcknowledged);

        OutboxRelay relay = relayOver(rows, publisher, failures);

        assertTimeout(Duration.ofSeconds(5L), relay::publishPendingEvents,
                "the sweep waited on a send that never answers instead of giving up at its "
                        + "configured deadline");

        verify(publisher).publish(eq(TOPIC), eq(ACCOUNT_ID), anyString());
        assertTrue(neverAcknowledged.isCancelled(),
                "the sweep gave up on the send and left it running. Cancelling is what stops a "
                        + "later acknowledgement completing unobserved after the row has already "
                        + "been offered to another sweep");
        verify(row, org.mockito.Mockito.never()).markPublished(any(Instant.class));
    }

    @Test
    @DisplayName("the configured deadline is what the sweep is bounded by")
    void theConfiguredDeadlineIsWhatTheSweepIsBoundedBy() {
        assertEquals(SWEEP_DEADLINE_MS, properties().outbox().relay().maxDurationMs(),
                "the settings under test carry the short deadline these assertions rely on. The "
                        + "ceiling on that setting is asserted by config/CardPropertiesTest, which "
                        + "sits in the package the constant is visible from");
    }

    /**
     * Builds a relay over the given collaborators, with counters and timers that record nothing.
     *
     * @param rows      the claim and mark surface
     * @param publisher the port whose stage never completes
     * @param failures  the infrastructure failure counter
     * @return the relay under test
     */
    private static OutboxRelay relayOver(OutboxEventRepository rows, EventPublisherPort publisher,
            Counter failures) {
        CardLatencyTimers timers = mock(CardLatencyTimers.class);
        when(timers.eventPublish()).thenReturn(mock(Timer.class));
        return new OutboxRelay(rows, publisher, mock(Counter.class), failures, mock(Counter.class),
                mock(Counter.class), timers, TOPIC, immediateTransactions(), properties());
    }

    /**
     * Builds settings whose only unusual value is the short sweep deadline.
     *
     * @return one bound settings tree with a hundred-millisecond sweep budget
     */
    private static CardProperties properties() {
        return new CardProperties(
                new CardProperties.Api(65_536L),
                new CardProperties.Kafka(
                        new CardProperties.Kafka.Topics(TOPIC, DEAD_LETTER_TOPIC)),
                new CardProperties.Outbox(new CardProperties.Outbox.Relay(
                        500L, 1, "deadline-test", Duration.ofMinutes(2L), SWEEP_DEADLINE_MS,
                        Duration.ofSeconds(10L)),
                        168L),
                new CardProperties.Retention(3_600_000L),
                new CardProperties.Write(3_000L));
    }

    /**
     * Runs each transaction callback directly, so no transaction manager takes part.
     *
     * @return a template that executes its callback in place
     */
    private static TransactionTemplate immediateTransactions() {
        return new TransactionTemplate() {

            private static final long serialVersionUID = 1L;

            @Override
            public <T> T execute(TransactionCallback<T> action) {
                TransactionStatus status = new SimpleTransactionStatus();
                return action.doInTransaction(status);
            }
        };
    }
}
