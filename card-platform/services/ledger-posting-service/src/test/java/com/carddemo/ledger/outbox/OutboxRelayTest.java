package com.carddemo.ledger.outbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.carddemo.events.DeadLetterEnvelope;
import com.carddemo.events.DeclineReason;
import com.carddemo.events.TransactionDeclined;
import com.carddemo.events.TransactionPosted;
import com.carddemo.ledger.config.LedgerProperties;
import com.carddemo.ledger.config.ObservabilityConfig.LedgerMeters;
import com.carddemo.ledger.entity.OutboxEventEntity;
import com.carddemo.ledger.repository.OutboxEventRepository;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.domain.Limit;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

/**
 * Verifies the claimed-row state machine used by the ledger outbox relay.
 *
 * <p>Every branch of one sweep is driven here: a due row published and marked, a row the broker
 * refuses, a claim a stopped instance left behind, an event type this service configures no
 * destination for, the attempt ceiling and the dead letter it names the row on, two relays claiming
 * disjoint rows, the exponential wait between attempts, and a sweep that cannot open its
 * transaction. The repository, the broker template and the recording surface are stubbed; the state
 * machine under test is {@code entity/OutboxEventEntity} and it is the real one.
 *
 * <p>A refused send backs off the account it names and no other. Ordering per account is the claim
 * query's job rather than the pass's: {@code claimDueRows} answers with the due head row of each
 * aggregate, so the rows of one window name distinct accounts and a stalled account's later events
 * wait behind its own unpublished head. Two tests below hold that, and the database-level property
 * they rest on is asserted in {@code repository/OutboxEventRepositoryTest} of the account service
 * and in {@code KafkaDeliveryGuaranteeContractTest}, which reads the claim of all five relays.
 *
 * <p>One asymmetry is asserted rather than assumed. A row the broker refuses reaches
 * {@code recordRefusedRow}, which publishes a dead letter when the row is abandoned. A row whose
 * event type has no destination was bound for no topic, so no diagnostic can declare a source topic
 * for it: it is abandoned silently and only the abandoned-row counter and the error log report it.
 * Two tests below hold both halves of that, so a change to either is visible.
 */
class OutboxRelayTest {

    private static final String ACCOUNT_ID = "00000000077";

    /**
     * A second account, so a window's rows name distinct accounts.
     *
     * <p>{@code claimDueRows} answers with the due head row of each aggregate and never two rows of
     * one account, so a pass that holds several rows holds them for several accounts. A test that
     * built two rows of one account would assert against a batch the claim cannot produce.
     */
    private static final String OTHER_ACCOUNT_ID = "00000000078";

    /** A third account, for the window that refuses every send. */
    private static final String THIRD_ACCOUNT_ID = "00000000079";

    private static final String POSTED_TOPIC = "transaction.posted";
    private static final String DECLINED_TOPIC = "transaction.declined";

    /** The shared dead-letter topic, where a row this relay gave up on is named. */
    private static final String DEAD_LETTER_TOPIC = "carddemo.dead-letter";

    /** The transaction identifier every stored event below carries. */
    private static final String TRANSACTION_ID = "0000000000683580";

    /** The masked card number every stored event below carries, and no dead letter may. */
    private static final String MASKED_CARD_NUMBER = "************7065";

    /** The amount every stored event below carries, and no dead letter may. */
    private static final BigDecimal POSTED_AMOUNT = new BigDecimal("1500.00");

    /** The balance a stored posted event carries. */
    private static final BigDecimal POSTED_BALANCE = new BigDecimal("504.77");

    /** The stamp a stored event carries, at the two significant fractional digits the source holds. */
    private static final String POSTED_AT = "2022-06-10-19.27.53.410000";

    /** What this relay writes into {@code claimed_by}. */
    private static final String INSTANCE_ID = "ledger-relay";

    /** What a second relay instance writes into {@code claimed_by}. */
    private static final String OTHER_INSTANCE = "ledger-relay-2";

    /** Rows one sweep claims, from {@code carddemo.outbox.relay.batch-size}. */
    private static final int BATCH_SIZE = 100;

    /** Base of the retry backoff, from {@code carddemo.outbox.relay.fixed-delay-ms}. */
    private static final Duration SWEEP_DELAY = Duration.ofMillis(500L);

    /** How long a claim may stand, and the ceiling of the backoff. */
    private static final Duration CLAIM_TIMEOUT = Duration.ofMinutes(2L);

    /** An event type this relay configures no destination for. */
    private static final String UNCONFIGURED_EVENT_TYPE = "TransactionRetired";

    /** Reason recorded on the attempts a row is aged with before the sweep under test. */
    private static final String EARLIER_FAILURE = "EarlierAttempt";

    /**
     * {@code ABEND-CODE} an abandoned outbox row carries, from {@code OutboxRelay}. Typed here so
     * the expectation is not the production constant compared against itself.
     */
    private static final String ABANDONED_ROW_ABEND_CODE = "OUTB";

    /** Source partition an outbox dead letter declares: a row arrived on none. */
    private static final int NO_SOURCE_PARTITION = 0;

    /** Source offset an outbox dead letter declares, for the same reason. */
    private static final long NO_SOURCE_OFFSET = 0L;

    /** Step recorded when the broker is handed the message. */
    private static final String SEND_STEP = "send";

    /** Step recorded when the row is saved carrying its published state. */
    private static final String MARK_STEP = "mark";

    /** Rows the stubbed store holds, so a re-read inside a later transaction finds them. */
    private final Map<UUID, OutboxEventEntity> stored = new HashMap<>();

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
        stored.clear();
        outboxEvents = mock(OutboxEventRepository.class);
        kafkaTemplate = mock(KafkaTemplate.class);
        meters = mock(LedgerMeters.class);
        transactionTemplate = mock(TransactionTemplate.class);
        insideTransaction = new AtomicBoolean();

        when(outboxEvents.save(any(OutboxEventEntity.class)))
                .thenAnswer(call -> {
                    OutboxEventEntity saved = call.getArgument(0);
                    stored.put(saved.getEventId(), saved);
                    return saved;
                });
        // The relay records each outcome in a transaction of its own and re-reads the row inside it,
        // because the claim has committed by then and saving the copy the claim loaded would write
        // pre-claim state back over it. A store keyed by identifier answers that read with the row
        // the claim saved, so these tests keep asserting against the instance they created.
        when(outboxEvents.findById(any(UUID.class)))
                .thenAnswer(call -> Optional.ofNullable(stored.get(call.getArgument(0))));
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
        writer = new OutboxWriter(outboxEvents);
        relay = new OutboxRelay(outboxEvents, kafkaTemplate, mapper, transactionTemplate,
                properties(), meters);
    }

    @Test
    void aDueRowIsClaimedBeforeItIsPublishedAndMarked() {
        OutboxEventEntity row = postedRow();
        when(outboxEvents.claimDueRows(any(), any())).thenReturn(List.of(row));
        when(kafkaTemplate.send(recordFor(POSTED_TOPIC, ACCOUNT_ID)))
                .thenAnswer(call -> {
                    assertThat(row.getRelayState())
                            .isEqualTo(OutboxEventEntity.RelayState.CLAIMED);
                    assertThat(row.getClaimedBy()).isEqualTo("ledger-relay");
                    return CompletableFuture.completedFuture(null);
                });

        relay.publishPendingEvents();

        assertTrue(row.isPublished());
        assertThat(row.getClaimedBy()).isNull();
        verify(kafkaTemplate).send(recordFor(POSTED_TOPIC, ACCOUNT_ID));
        verify(meters, never()).recordFailure(LedgerMeters.PUBLISH_STAGE);
        // The publish-success series is what tells a stalled relay from an idle one: the
        // outbox-write count keeps rising while a broker is unreachable, and this one does not.
        verify(meters).recordEventsPublished(1L);
    }

    @Test
    void aRowTheBrokerRefusedIsNeverCountedAsPublished() {
        OutboxEventEntity row = postedRow();
        when(outboxEvents.claimDueRows(any(), any())).thenReturn(List.of(row));
        when(kafkaTemplate.send(recordFor(POSTED_TOPIC, ACCOUNT_ID)))
                .thenReturn(CompletableFuture.failedFuture(
                        new IllegalStateException("broker unavailable")));

        relay.publishPendingEvents();

        assertThat(row.isPublished()).isFalse();
        verify(meters, never()).recordEventsPublished(org.mockito.ArgumentMatchers.anyLong());
        verify(meters).recordFailure(LedgerMeters.PUBLISH_STAGE);
    }

    @Test
    void aBrokerFailureBacksOffItsOwnRowAndLeavesTheOtherAccountsToTheSameSweep() {
        OutboxEventEntity refused = postedRow();
        OutboxEventEntity otherAccount = postedRowFor(OTHER_ACCOUNT_ID);
        when(outboxEvents.claimDueRows(any(), any())).thenReturn(List.of(refused, otherAccount));
        when(kafkaTemplate.send(recordFor(POSTED_TOPIC, ACCOUNT_ID)))
                .thenReturn(CompletableFuture.failedFuture(
                        new IllegalStateException("broker unavailable")));
        when(kafkaTemplate.send(recordFor(POSTED_TOPIC, OTHER_ACCOUNT_ID)))
                .thenReturn(CompletableFuture.completedFuture(null));
        org.mockito.Mockito.doAnswer(call -> {
            assertThat(insideTransaction.get()).isFalse();
            return null;
        }).when(meters).recordFailure(LedgerMeters.PUBLISH_STAGE);

        relay.publishPendingEvents();

        assertThat(refused.getRelayState()).isEqualTo(OutboxEventEntity.RelayState.PENDING);
        assertThat(refused.getAttemptCount()).isEqualTo(1);
        assertThat(refused.getNextAttemptAt()).isAfter(refused.getLastAttemptAt());
        assertTrue(otherAccount.isPublished(),
                "one account's refused send backs that account off and no other, so the row of "
                        + "another account claimed by the same pass is still published");
        verify(kafkaTemplate).send(recordFor(POSTED_TOPIC, ACCOUNT_ID));
        verify(kafkaTemplate).send(recordFor(POSTED_TOPIC, OTHER_ACCOUNT_ID));
        verify(meters).recordFailure(LedgerMeters.PUBLISH_STAGE);
        verify(meters).recordEventsPublished(1L);
    }

    @Test
    void everyAccountOfOneWindowIsAttemptedWhenEverySendIsRefused() {
        List<OutboxEventEntity> rows = List.of(postedRow(), postedRowFor(OTHER_ACCOUNT_ID),
                postedRowFor(THIRD_ACCOUNT_ID));
        when(outboxEvents.claimDueRows(any(), any())).thenReturn(rows);
        when(kafkaTemplate.send(recordOn(POSTED_TOPIC)))
                .thenReturn(CompletableFuture.failedFuture(
                        new IllegalStateException("broker unavailable")));

        relay.publishPendingEvents();

        assertThat(rows).allSatisfy(row -> {
            assertThat(row.getRelayState()).isEqualTo(OutboxEventEntity.RelayState.PENDING);
            assertThat(row.getAttemptCount())
                    .as("each account's failure is recorded on that account's row alone")
                    .isEqualTo(1);
        });
        verify(kafkaTemplate, times(3)).send(recordOn(POSTED_TOPIC));
        verify(meters, times(3)).recordFailure(LedgerMeters.PUBLISH_STAGE);
        verify(meters, never()).recordEventsPublished(org.mockito.ArgumentMatchers.anyLong());
    }

    @Test
    void aStrandedClaimIsRecoveredBeforeTheRowIsPublished() {
        OutboxEventEntity row = postedRow();
        row.claim("stopped-instance", Instant.now().minus(Duration.ofMinutes(3L)));
        when(outboxEvents.findByRelayStateAndClaimedAtBeforeOrderByClaimedAtAsc(
                any(), any(), any())).thenReturn(List.of(row));
        when(outboxEvents.claimDueRows(any(), any())).thenReturn(List.of(row));
        when(kafkaTemplate.send(recordFor(POSTED_TOPIC, ACCOUNT_ID)))
                .thenReturn(CompletableFuture.completedFuture(null));

        relay.publishPendingEvents();

        assertTrue(row.isPublished());
        assertThat(row.getAttemptCount()).isEqualTo(1);
        verify(kafkaTemplate).send(recordFor(POSTED_TOPIC, ACCOUNT_ID));
        verify(meters).recordFailure(LedgerMeters.PUBLISH_STAGE);
    }

    // Every remaining branch of the sweep. The three tests above cover a due row, a refused send
    // and a stranded claim. What follows covers the destination of the other event type, an event
    // type with no destination, the attempt ceiling and the dead letter it publishes, the claim
    // arguments, the ordering of the mark against the send, competing relays, the backoff, and the
    // sweep that fails as a whole.

    /**
     * Asserts no row of this outbox can travel on the declined topic.
     *
     * <p>This service consumes {@code transaction.declined} and publishes nothing on it. The declined
     * event naming a refused transaction is the one the authorization service published, which
     * {@code messaging/TransactionDeclinedConsumer} reads and {@code domain/RejectRecorder} turns
     * into the four-hundred-and-thirty-byte reject row. A second, differently shaped
     * {@code TransactionDeclined} published here would put two events for one decision on a topic
     * this service also reads, so it would consume its own publication.
     *
     * <p>Both halves are asserted, because either alone would leave the invariant reachable.
     * {@code OutboxWriter} refuses to store such a row at all, and the relay configures a
     * destination for {@code TransactionPosted} alone, so a row stored by any other route would find
     * no topic either.
     */
    @Test
    void noRowOfThisOutboxTravelsOnTheDeclinedTopic() {
        TransactionDeclined declined = TransactionDeclined.of(ACCOUNT_ID, TRANSACTION_ID,
                DeclineReason.OVER_CREDIT_LIMIT, POSTED_AMOUNT, MASKED_CARD_NUMBER);

        IllegalArgumentException refused = assertThrows(IllegalArgumentException.class,
                () -> writer.write(declined),
                "a declined event stored here would be published onto a topic this service reads");
        assertThat(refused).hasMessageContaining(TransactionPosted.EVENT_TYPE);

        OutboxEventEntity posted = postedRow();
        when(outboxEvents.claimDueRows(any(), any())).thenReturn(List.of(posted));
        when(kafkaTemplate.send(recordFor(POSTED_TOPIC, ACCOUNT_ID)))
                .thenReturn(CompletableFuture.completedFuture(null));

        relay.publishPendingEvents();

        assertTrue(posted.isPublished());
        verify(kafkaTemplate, never()).send(recordOn(DECLINED_TOPIC));
    }

    @Test
    void theClaimIsAskedForTheConfiguredBatchSizeAtTheSweepInstant() {
        when(outboxEvents.claimDueRows(any(), any())).thenReturn(List.of());
        ArgumentCaptor<Instant> asOf = ArgumentCaptor.forClass(Instant.class);
        ArgumentCaptor<Limit> limit = ArgumentCaptor.forClass(Limit.class);

        Instant before = Instant.now();
        relay.publishPendingEvents();

        verify(outboxEvents).claimDueRows(asOf.capture(), limit.capture());
        assertThat(limit.getValue().max()).isEqualTo(BATCH_SIZE);
        assertThat(asOf.getValue()).isAfterOrEqualTo(before);
        verify(outboxEvents).findByRelayStateAndClaimedAtBeforeOrderByClaimedAtAsc(
                eq(OutboxEventEntity.RelayState.CLAIMED), any(), any());
        verify(meters, never()).recordFailure(anyString());
        verify(meters, never()).recordAbandonedRow();
    }

    @Test
    void theRowIsMarkedOnlyAfterTheBrokerHasTakenTheMessage() {
        OutboxEventEntity row = postedRow();
        when(outboxEvents.claimDueRows(any(), any())).thenReturn(List.of(row));
        List<String> order = new ArrayList<>();
        when(kafkaTemplate.send(recordFor(POSTED_TOPIC, ACCOUNT_ID)))
                .thenAnswer(call -> {
                    order.add(SEND_STEP);
                    assertThat(row.isPublished()).isFalse();
                    assertThat(row.getPublishedAt()).isNull();
                    return CompletableFuture.completedFuture(null);
                });
        when(outboxEvents.save(any(OutboxEventEntity.class))).thenAnswer(call -> {
            OutboxEventEntity saved = call.getArgument(0);
            stored.put(saved.getEventId(), saved);
            if (saved.isPublished()) {
                order.add(MARK_STEP);
            }
            return saved;
        });

        relay.publishPendingEvents();

        assertThat(order).containsExactly(SEND_STEP, MARK_STEP);
        assertThat(row.getPublishedAt()).isNotNull();
    }

    @Test
    void aRefusedSendLeavesTheRowUnpublishedAndUnclaimed() {
        OutboxEventEntity row = postedRow();
        when(outboxEvents.claimDueRows(any(), any())).thenReturn(List.of(row));
        when(kafkaTemplate.send(recordFor(POSTED_TOPIC, ACCOUNT_ID)))
                .thenReturn(CompletableFuture.failedFuture(
                        new IllegalStateException("broker unavailable")));

        relay.publishPendingEvents();

        assertThat(row.isPublished()).isFalse();
        assertThat(row.getPublishedAt()).isNull();
        assertThat(row.getClaimedBy()).isNull();
        assertThat(row.getRelayState()).isEqualTo(OutboxEventEntity.RelayState.PENDING);
        verify(kafkaTemplate, never()).send(recordOn(DEAD_LETTER_TOPIC));
        verify(meters, never()).recordAbandonedRow();
    }

    @Test
    void twoRelaysClaimDisjointRowsAndEachPublishesItsOwn() {
        OutboxEventEntity mine = postedRow();
        OutboxEventEntity theirs = postedRow();
        OutboxRelay other = relayIdentifiedAs(OTHER_INSTANCE);
        when(outboxEvents.claimDueRows(any(), any()))
                .thenReturn(List.of(mine))
                .thenReturn(List.of(theirs));
        when(kafkaTemplate.send(recordFor(POSTED_TOPIC, ACCOUNT_ID)))
                .thenAnswer(call -> CompletableFuture.completedFuture(null));

        relay.publishPendingEvents();
        other.publishPendingEvents();

        assertTrue(mine.isPublished());
        assertTrue(theirs.isPublished());
        assertThat(mine.getEventId()).isNotEqualTo(theirs.getEventId());
        verify(kafkaTemplate, times(2)).send(recordFor(POSTED_TOPIC, ACCOUNT_ID));
        verify(outboxEvents, times(2)).claimDueRows(any(), any());
    }

    @Test
    void aRowClaimedByAnotherInstanceIsNotPublishedTwice() {
        OutboxEventEntity claimedElsewhere = postedRow();
        claimedElsewhere.claim(OTHER_INSTANCE, Instant.now());
        when(outboxEvents.claimDueRows(any(), any())).thenReturn(List.of());
        when(outboxEvents.findByRelayStateAndClaimedAtBeforeOrderByClaimedAtAsc(
                any(), any(), any())).thenReturn(List.of());

        relay.publishPendingEvents();

        assertThat(claimedElsewhere.isPublished()).isFalse();
        assertThat(claimedElsewhere.getClaimedBy()).isEqualTo(OTHER_INSTANCE);
        verify(kafkaTemplate, never()).send(anyRecord());
    }

    @Test
    void anEventTypeWithNoDestinationIsBackedOffAndTheSweepCarriesOn() {
        OutboxEventEntity unroutable = rowOfUnknownType();
        OutboxEventEntity routable = postedRow();
        when(outboxEvents.claimDueRows(any(), any())).thenReturn(List.of(unroutable, routable));
        when(kafkaTemplate.send(recordFor(POSTED_TOPIC, ACCOUNT_ID)))
                .thenReturn(CompletableFuture.completedFuture(null));

        relay.publishPendingEvents();

        assertThat(unroutable.isPublished()).isFalse();
        assertThat(unroutable.getAttemptCount()).isEqualTo(1);
        assertThat(unroutable.getRelayState()).isEqualTo(OutboxEventEntity.RelayState.PENDING);
        assertTrue(routable.isPublished(),
                "an unroutable row is skipped rather than stopping the sweep, unlike a refused "
                        + "send");
        verify(kafkaTemplate).send(recordFor(POSTED_TOPIC, ACCOUNT_ID));
        verify(meters).recordFailure(LedgerMeters.PUBLISH_STAGE);
        verify(meters, never()).recordAbandonedRow();
    }

    @Test
    void theCeilingAbandonsARefusedRowAndNamesItOnTheDeadLetterTopic() {
        OutboxEventEntity row = postedRow();
        failUpToTheCeiling(row);
        when(outboxEvents.claimDueRows(any(), any())).thenReturn(List.of(row));
        when(kafkaTemplate.send(recordFor(POSTED_TOPIC, ACCOUNT_ID)))
                .thenReturn(CompletableFuture.failedFuture(
                        new IllegalStateException("broker unavailable")));
        when(kafkaTemplate.send(recordFor(DEAD_LETTER_TOPIC, ACCOUNT_ID)))
                .thenReturn(CompletableFuture.completedFuture(null));
        org.mockito.Mockito.doAnswer(call -> {
            assertThat(insideTransaction.get()).isFalse();
            return null;
        }).when(meters).recordAbandonedRow();

        relay.publishPendingEvents();

        assertThat(row.getRelayState()).isEqualTo(OutboxEventEntity.RelayState.ABANDONED);
        assertThat(row.getAttemptCount()).isEqualTo(OutboxEventEntity.MAX_DELIVERY_ATTEMPTS);
        assertThat(row.isPublished()).isFalse();

        DeadLetterEnvelope letter = deadLetterSent();
        assertThat(letter.abendCode()).isEqualTo(ABANDONED_ROW_ABEND_CODE);
        assertThat(letter.failedEventId()).isEqualTo(row.getEventId().toString());
        assertThat(letter.failedEventType()).isEqualTo(TransactionPosted.EVENT_TYPE);
        assertThat(letter.attemptCount()).isEqualTo(OutboxEventEntity.MAX_DELIVERY_ATTEMPTS);
        assertThat(letter.sourceTopic()).isEqualTo(POSTED_TOPIC);
        assertThat(letter.sourcePartition()).isEqualTo(NO_SOURCE_PARTITION);
        assertThat(letter.sourceOffset()).isEqualTo(NO_SOURCE_OFFSET);
        assertThat(letter.envelope().aggregateId()).isEqualTo(ACCOUNT_ID);
        verify(meters).recordAbandonedRow();
        verify(meters).recordFailure(LedgerMeters.PUBLISH_STAGE);
    }

    @Test
    void anUnroutableRowAtTheCeilingIsAbandonedWithoutADeadLetter() {
        OutboxEventEntity row = rowOfUnknownType();
        failUpToTheCeiling(row);
        when(outboxEvents.claimDueRows(any(), any())).thenReturn(List.of(row));

        relay.publishPendingEvents();

        assertThat(row.getRelayState()).isEqualTo(OutboxEventEntity.RelayState.ABANDONED);
        verify(meters).recordAbandonedRow();
        verify(kafkaTemplate, never()).send(recordOn(DEAD_LETTER_TOPIC));
    }

    @Test
    void theDeadLetterCarriesNoStoredPayload() {
        OutboxEventEntity row = postedRow();
        failUpToTheCeiling(row);
        when(outboxEvents.claimDueRows(any(), any())).thenReturn(List.of(row));
        when(kafkaTemplate.send(recordFor(POSTED_TOPIC, ACCOUNT_ID)))
                .thenReturn(CompletableFuture.failedFuture(
                        new IllegalStateException("broker unavailable")));
        when(kafkaTemplate.send(recordFor(DEAD_LETTER_TOPIC, ACCOUNT_ID)))
                .thenReturn(CompletableFuture.completedFuture(null));

        relay.publishPendingEvents();

        String rendered = String.valueOf(deadLetterSent());
        assertThat(rendered).doesNotContain(MASKED_CARD_NUMBER);
        assertThat(rendered).doesNotContain(POSTED_AMOUNT.toPlainString());
        assertThat(row.getPayload()).contains(MASKED_CARD_NUMBER);
        assertThat(row.getPayload()).contains(POSTED_AMOUNT.toPlainString());
    }

    @Test
    void aBrokerRefusingTheDeadLetterFailsTheSweepAndLeavesTheRowAbandoned() {
        OutboxEventEntity row = postedRow();
        failUpToTheCeiling(row);
        when(outboxEvents.claimDueRows(any(), any())).thenReturn(List.of(row));
        when(kafkaTemplate.send(recordFor(POSTED_TOPIC, ACCOUNT_ID)))
                .thenReturn(CompletableFuture.failedFuture(
                        new IllegalStateException("broker unavailable")));
        when(kafkaTemplate.send(recordFor(DEAD_LETTER_TOPIC, ACCOUNT_ID)))
                .thenReturn(CompletableFuture.failedFuture(
                        new IllegalStateException("dead-letter topic unavailable")));

        relay.publishPendingEvents();

        assertThat(row.getRelayState()).isEqualTo(OutboxEventEntity.RelayState.ABANDONED);
        verify(meters).recordFailure(LedgerMeters.PUBLISH_STAGE);
        verify(meters, never()).recordAbandonedRow();
    }

    @Test
    void aSweepThatCannotOpenItsTransactionIsRecordedAndRaisesNothing() {
        // doThrow rather than when: when(...) would invoke the stub already primed in setUp, which
        // runs the callback, and the callback would arrive with a null argument.
        org.mockito.Mockito.doThrow(
                        new org.springframework.transaction.CannotCreateTransactionException(
                                "could not open JPA transaction"))
                .when(transactionTemplate).execute(any());

        relay.publishPendingEvents();

        verify(meters).recordFailure(LedgerMeters.PUBLISH_STAGE);
        verify(kafkaTemplate, never()).send(anyRecord());
        verify(meters, never()).recordAbandonedRow();
    }

    @Test
    void theBackoffDoublesPerAttemptAndStopsAtTheClaimTimeout() {
        List<Duration> waits = new ArrayList<>();
        OutboxEventEntity row = postedRow();
        when(outboxEvents.claimDueRows(any(), any())).thenReturn(List.of(row));
        when(kafkaTemplate.send(recordFor(POSTED_TOPIC, ACCOUNT_ID)))
                .thenReturn(CompletableFuture.failedFuture(
                        new IllegalStateException("broker unavailable")));

        for (int attempt = 0; attempt < OutboxEventEntity.MAX_DELIVERY_ATTEMPTS - 1; attempt++) {
            relay.publishPendingEvents();
            waits.add(Duration.between(row.getLastAttemptAt(), row.getNextAttemptAt()));
        }

        assertThat(waits.getFirst()).isEqualTo(SWEEP_DELAY);
        assertThat(waits.get(1)).isEqualTo(SWEEP_DELAY.multipliedBy(2L));
        assertThat(waits.get(2)).isEqualTo(SWEEP_DELAY.multipliedBy(4L));
        assertThat(waits.getLast()).isEqualTo(CLAIM_TIMEOUT);
        assertThat(waits).allSatisfy(wait -> assertThat(wait).isLessThanOrEqualTo(CLAIM_TIMEOUT));
    }

    /**
     * Records failures until one more takes the row to the attempt ceiling.
     *
     * <p>{@link OutboxEventEntity#MAX_DELIVERY_ATTEMPTS} attempts are what the entity abandons a row
     * at, so the sweep under test has to be the last one. Driving the earlier attempts through the
     * relay would run nine sweeps to set up one assertion.
     *
     * @param row the row to age
     */
    private static void failUpToTheCeiling(OutboxEventEntity row) {
        Instant at = Instant.now().minus(Duration.ofMinutes(10L));
        for (int attempt = 1; attempt < OutboxEventEntity.MAX_DELIVERY_ATTEMPTS; attempt++) {
            row.recordFailure(EARLIER_FAILURE, at, at);
        }
        assertThat(row.getAttemptCount()).isEqualTo(OutboxEventEntity.MAX_DELIVERY_ATTEMPTS - 1);
        assertThat(row.getRelayState()).isEqualTo(OutboxEventEntity.RelayState.PENDING);
    }

    /**
     * Selects the dead letter out of everything the sweep sent.
     *
     * <p>A sweep that abandons a row sends twice on one template: the refused destination send
     * first, then the dead letter. Both reach the same single-argument overload, so the record is
     * picked by topic rather than by call count, and exactly one such record is required.
     *
     * @return the one dead letter this sweep published
     */
    @SuppressWarnings("unchecked")
    private DeadLetterEnvelope deadLetterSent() {
        ArgumentCaptor<ProducerRecord<String, Object>> published =
                ArgumentCaptor.forClass(ProducerRecord.class);
        verify(kafkaTemplate, atLeastOnce()).send(published.capture());
        List<ProducerRecord<String, Object>> letters = published.getAllValues().stream()
                .filter(record -> DEAD_LETTER_TOPIC.equals(record.topic()))
                .toList();
        assertThat(letters).hasSize(1);
        ProducerRecord<String, Object> sent = letters.getFirst();
        assertThat(sent.key()).isEqualTo(ACCOUNT_ID);
        assertThat(sent.value()).isInstanceOf(DeadLetterEnvelope.class);
        return (DeadLetterEnvelope) sent.value();
    }

    /** @return a row whose event type this relay configures no destination for */
    private static OutboxEventEntity rowOfUnknownType() {
        return new OutboxEventEntity(UUID.randomUUID(), UNCONFIGURED_EVENT_TYPE, ACCOUNT_ID,
                "{}", Instant.now().minus(Duration.ofMinutes(1L)));
    }

    /**
     * Builds a second relay writing another instance identifier into {@code claimed_by}.
     *
     * @param instanceId what that relay claims a row as
     * @return the relay
     */
    private OutboxRelay relayIdentifiedAs(String instanceId) {
        return new OutboxRelay(outboxEvents, kafkaTemplate, JsonMapper.builder().build(),
                transactionTemplate, propertiesFor(instanceId), meters);
    }

    private OutboxEventEntity postedRow() {
        return postedRowFor(ACCOUNT_ID);
    }

    /**
     * Writes one posted-event row keyed by the given account.
     *
     * @param accountId the eleven-digit account the row is keyed by, and its message key
     * @return the stored row
     */
    private OutboxEventEntity postedRowFor(String accountId) {
        TransactionPosted event = TransactionPosted.forAccount(accountId, TRANSACTION_ID,
                POSTED_BALANCE, POSTED_AT, POSTED_AMOUNT, MASKED_CARD_NUMBER);
        return writer.write(event);
    }

    private static LedgerProperties properties() {
        return propertiesFor(INSTANCE_ID);
    }

    /**
     * Builds the configuration one relay instance reads.
     *
     * @param instanceId what that instance writes into {@code claimed_by}
     * @return the configuration
     */
    private static LedgerProperties propertiesFor(String instanceId) {
        return new LedgerProperties(
                new LedgerProperties.Kafka(new LedgerProperties.Kafka.Topics(
                        "transaction.authorized",
                        DECLINED_TOPIC,
                        "account.state-changed",
                        POSTED_TOPIC,
                        "carddemo.dead-letter",
                        ".DLT")),
                new LedgerProperties.Consumer(new LedgerProperties.Consumer.Retry(3, 1000L)),
                new LedgerProperties.Outbox(new LedgerProperties.Outbox.Relay(
                        500L,
                        100,
                        "ledger-relay",
                        Duration.ofMinutes(2L),
                        5_000L), 168L),
                new LedgerProperties.Retention(3_600_000L, 90));
    }

    /**
     * Matches one record addressed to a topic and keyed on an aggregate.
     *
     * <p>The relay sends a {@code ProducerRecord} rather than a topic, a key and a value, because the
     * two correlation identifiers of the row travel as record headers. This matcher reads the topic
     * and the key off that record, so every expectation below says what it said before.
     *
     * @param topic the destination topic
     * @param key   the message key
     * @return the matcher
     */
    private static ProducerRecord<String, Object> recordFor(String topic, String key) {
        return argThat((ProducerRecord<String, Object> record) -> record != null
                && topic.equals(record.topic()) && key.equals(record.key()));
    }

    /**
     * Matches one record addressed to a topic, under any key.
     *
     * @param topic the destination topic
     * @return the matcher
     */
    private static ProducerRecord<String, Object> recordOn(String topic) {
        return argThat((ProducerRecord<String, Object> record) -> record != null
                && topic.equals(record.topic()));
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
