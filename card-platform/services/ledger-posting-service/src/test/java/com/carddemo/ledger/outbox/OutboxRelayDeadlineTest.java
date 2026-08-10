package com.carddemo.ledger.outbox;

import static org.junit.jupiter.api.Assertions.assertTimeout;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.carddemo.events.TransactionPosted;
import com.carddemo.ledger.config.LedgerProperties;
import com.carddemo.ledger.config.ObservabilityConfig;
import com.carddemo.ledger.entity.OutboxEventEntity;
import com.carddemo.ledger.repository.OutboxEventRepository;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.apache.kafka.clients.producer.ProducerRecord;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Limit;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

/**
 * Verifies the total wall-time bound of one relay sweep, with no broker and no database.
 *
 * <p>The bound is the reason {@code carddemo.outbox.relay.max-duration-ms} exists. Before it, the
 * sweep joined each send with no deadline of its own, and the sweep runs inside one transaction: a
 * broker that accepted a send and never acknowledged it held the scheduler thread, the database
 * connection and every row lock of the sweep for the whole {@code delivery.timeout.ms}. The sweep now
 * reads one monotonic deadline at its start and awaits every send against what remains of it, so an
 * unacknowledged send costs one sweep.
 *
 * <p>Nothing here starts a container. The producer template answers with a future that is never
 * completed, which is exactly what an accepted-but-unacknowledged send looks like to the relay, and
 * the transaction template runs each callback directly.
 *
 * <p>Design decisions: {@code card-platform/docs/decision-log.md}.
 */
@DisplayName("OutboxRelay total sweep deadline")
class OutboxRelayDeadlineTest {

    /** The whole-sweep budget under test, short so the assertion is quick and unambiguous. */
    private static final long PASS_DEADLINE_MS = 100L;

    /** The topic a posted row resolves to, and the one the stalled send is issued against. */
    private static final String POSTED_TOPIC = "transaction.posted";

    /** The account the stalled row is keyed by, eleven digits from {@code ACCT-ID PIC 9(11)}. */
    private static final String ACCOUNT_ID = "00000000001";

    @Test
    @DisplayName("one broker send cannot hold the sweep past its configured deadline")
    void oneBrokerSendCannotHoldTheSweepPastItsConfiguredDeadline() {
        OutboxEventRepository rows = mock(OutboxEventRepository.class);
        OutboxEventEntity row = mock(OutboxEventEntity.class);
        @SuppressWarnings("unchecked")
        KafkaTemplate<String, Object> template = mock(KafkaTemplate.class);
        CompletableFuture<Object> neverAcknowledged = new CompletableFuture<>();

        when(rows.findByRelayStateAndClaimedAtBeforeOrderByClaimedAtAsc(
                any(OutboxEventEntity.RelayState.class), any(Instant.class), eq(Limit.of(1))))
                .thenReturn(List.of());
        when(rows.claimDueRows(any(Instant.class), eq(Limit.of(1)))).thenReturn(List.of(row));
        // The relay records the outcome of one send in a transaction of its own and re-reads the row
        // inside it, because the claim has committed by then and saving the copy the claim loaded
        // would write pre-claim state back over it. This answers that read with the same row.
        when(rows.findById(any(UUID.class))).thenReturn(java.util.Optional.of(row));
        when(row.getEventId()).thenReturn(UUID.randomUUID());
        when(row.getEventType()).thenReturn(TransactionPosted.EVENT_TYPE);
        when(row.getAggregateId()).thenReturn(ACCOUNT_ID);
        when(row.getPayload()).thenReturn(postedPayload());
        when(row.getAttemptCount()).thenReturn(1);
        when(row.getRelayState()).thenReturn(OutboxEventEntity.RelayState.PENDING);
        when(template.send(postedRecord()))
                .thenAnswer(invocation -> neverAcknowledged);

        OutboxRelay relay = new OutboxRelay(rows, template, JsonMapper.builder().build(),
                immediateTransactions(), properties(),
                new ObservabilityConfig().ledgerMeters(new SimpleMeterRegistry()));

        assertTimeout(Duration.ofSeconds(2L), relay::publishPendingEvents);
        verify(row).recordFailure(eq("RelayDeadlineExceededException"), any(Instant.class),
                any(Instant.class));
        verify(template).send(postedRecord());
    }

    /**
     * Builds one stored payload the relay can read back into a posted event.
     *
     * <p>The relay deserializes before it sends, so the payload has to satisfy the record's canonical
     * constructor or the failure under test never happens.
     *
     * @return the wire form of one posted event
     */
    private static String postedPayload() {
        TransactionPosted posted = TransactionPosted.forAccount(ACCOUNT_ID, "0000000000683580",
                new java.math.BigDecimal("1000.00"), "2022-07-19-23.16.01.470000",
                new java.math.BigDecimal("715.44"), "************7065");
        return JsonMapper.builder().build().writeValueAsString(posted);
    }

    /**
     * Builds settings whose only unusual values are the short sweep deadline and the batch of one.
     *
     * @return one bound settings tree with a hundred-millisecond sweep budget
     */
    private static LedgerProperties properties() {
        return new LedgerProperties(
                new LedgerProperties.Kafka(new LedgerProperties.Kafka.Topics(
                        "transaction.authorized", "transaction.declined", "account.state-changed",
                        POSTED_TOPIC, "carddemo.dead-letter", ".DLT")),
                new LedgerProperties.Consumer(new LedgerProperties.Consumer.Retry(3, 1000L)),
                new LedgerProperties.Outbox(new LedgerProperties.Outbox.Relay(
                        500L, 1, "deadline-test", Duration.ofSeconds(30L), PASS_DEADLINE_MS), 168L),
                new LedgerProperties.ProcessedEvent(720L, 168L),
                new LedgerProperties.Retention(3_600_000L, 90));
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

    @Test
    @DisplayName("an empty table issues no send at all")
    void anEmptyTableIssuesNoSendAtAll() {
        OutboxEventRepository rows = mock(OutboxEventRepository.class);
        @SuppressWarnings("unchecked")
        KafkaTemplate<String, Object> template = mock(KafkaTemplate.class);

        when(rows.findByRelayStateAndClaimedAtBeforeOrderByClaimedAtAsc(
                any(OutboxEventEntity.RelayState.class), any(Instant.class), any(Limit.class)))
                .thenReturn(List.of());
        when(rows.claimDueRows(any(Instant.class), any(Limit.class))).thenReturn(List.of());

        OutboxRelay relay = new OutboxRelay(rows, template, JsonMapper.builder().build(),
                immediateTransactions(), properties(),
                new ObservabilityConfig().ledgerMeters(new SimpleMeterRegistry()));
        relay.publishPendingEvents();

        verify(rows).claimDueRows(any(Instant.class), eq(Limit.of(1)));
        verify(template, org.mockito.Mockito.never()).send(anyRecord());
    }

    /**
     * Matches one record addressed to the posted topic and keyed on the account.
     *
     * <p>The relay sends a {@code ProducerRecord} rather than a topic, a key and a value, because the
     * two correlation identifiers of the row travel as record headers.
     *
     * @return the matcher
     */
    private static ProducerRecord<String, Object> postedRecord() {
        return argThat((ProducerRecord<String, Object> record) -> record != null
                && POSTED_TOPIC.equals(record.topic()) && ACCOUNT_ID.equals(record.key()));
    }

    /**
     * Matches any record at all.
     *
     * @return the matcher
     */
    private static ProducerRecord<String, Object> anyRecord() {
        return any();
    }
}
