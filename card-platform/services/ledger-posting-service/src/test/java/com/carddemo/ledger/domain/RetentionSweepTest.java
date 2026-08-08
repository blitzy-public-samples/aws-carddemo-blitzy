package com.carddemo.ledger.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertTimeout;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.carddemo.ledger.config.LedgerProperties;
import com.carddemo.ledger.repository.OutboxEventRepository;
import com.carddemo.ledger.repository.ProcessedEventRepository;
import com.carddemo.ledger.repository.RejectedTransactionRepository;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.TransientDataAccessResourceException;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Proves each retention delete is bounded, ordered, drained and finite.
 *
 * <p>No COBOL ancestor. The source has no retention concern: every dataset's lifetime belongs to the
 * Job Control Language, and {@code app/jcl/POSTTRAN.jcl} deletes and redefines its output rather than
 * pruning it.
 *
 * <p>The defect these tests close is a single unbounded {@code DELETE} on two tables that are on the
 * hot write path. {@code outbox_event} is swept by the relay every half second and
 * {@code processed_event} is written by all three listeners, so one statement holding every expired
 * row under one lock blocks all four of them for a duration nobody can predict from the
 * configuration.
 *
 * <p>Nothing here opens a database connection. Each repository is a stand-in that answers with a row
 * count, and the transaction template runs its callback directly so the number of transactions is
 * exactly the number of statements.
 *
 * <p>Design decisions: {@code card-platform/docs/decision-log.md}.
 */
@DisplayName("RetentionSweep, its three tables, its bounded batches and its finite drain")
class RetentionSweepTest {

    /** Hours a published outbox row stays, matching the shipped default. */
    private static final long PUBLISHED_RETENTION_HOURS = 168L;

    /** Hours a processed-event marker stays, matching the shipped default. */
    private static final long MARKER_RETENTION_HOURS = 720L;

    /** Hours the broker keeps a topic log, which the marker horizon has to outlast. */
    private static final long BROKER_RETENTION_HOURS = 168L;

    /** Days a reject stays, matching the horizon COMMENT ON TABLE rejected_transaction declares. */
    private static final int REJECTED_RETENTION_DAYS = 90;

    /** Store of the published rows, stubbed here. */
    private OutboxEventRepository outboxEvents;

    /** Store of the duplicate-delivery markers, stubbed here. */
    private ProcessedEventRepository processedEvents;

    /** Store of the refused feed records, stubbed here. */
    private RejectedTransactionRepository rejectedTransactions;

    /** Counts how many transactions the sweep opened. */
    private List<String> transactions;

    /** The sweep under test. */
    private RetentionSweep sweep;

    @BeforeEach
    void buildSweep() {
        outboxEvents = mock(OutboxEventRepository.class);
        processedEvents = mock(ProcessedEventRepository.class);
        rejectedTransactions = mock(RejectedTransactionRepository.class);
        transactions = new ArrayList<>();
        sweep = new RetentionSweep(outboxEvents, processedEvents, rejectedTransactions,
                countingTransactions(transactions), properties());
    }

    /** Stubs every delete to report an empty table, which is one statement each. */
    private void everyTableIsEmpty() {
        when(outboxEvents.deletePublishedBefore(any(Instant.class), anyInt())).thenReturn(0);
        when(processedEvents.deleteMarkersProcessedBefore(any(Instant.class), anyInt()))
                .thenReturn(0);
        when(rejectedTransactions.deleteRejectedBefore(any(Instant.class), anyInt())).thenReturn(0);
    }

    /** Settings carrying the three shipped horizons and the shipped sweep interval. */
    private static LedgerProperties properties() {
        return new LedgerProperties(
                new LedgerProperties.Kafka(new LedgerProperties.Kafka.Topics(
                        "transaction.authorized", "transaction.declined", "account.state-changed",
                        "transaction.posted", "carddemo.dead-letter", ".DLT")),
                new LedgerProperties.Consumer(new LedgerProperties.Consumer.Retry(3, 1000L)),
                new LedgerProperties.Outbox(new LedgerProperties.Outbox.Relay(
                        500L, 100, "retention-test", Duration.ofMinutes(2L), 5_000L),
                        PUBLISHED_RETENTION_HOURS),
                new LedgerProperties.ProcessedEvent(MARKER_RETENTION_HOURS,
                        BROKER_RETENTION_HOURS),
                new LedgerProperties.Retention(3_600_000L, REJECTED_RETENTION_DAYS));
    }

    /**
     * Builds a template that records one entry per transaction and runs its callback directly.
     *
     * @param record the list one entry is appended to per transaction
     * @return the recording template
     */
    private static TransactionTemplate countingTransactions(List<String> record) {
        return new TransactionTemplate() {

            private static final long serialVersionUID = 1L;

            @Override
            public <T> T execute(TransactionCallback<T> action) {
                record.add("transaction");
                return action.doInTransaction(new SimpleTransactionStatus());
            }
        };
    }

    @Nested
    @DisplayName("The bound on one statement")
    class OneStatement {

        @Test
        @DisplayName("each delete names the batch ceiling rather than the whole table")
        void eachDeleteNamesTheBatchCeiling() {
            everyTableIsEmpty();

            sweep.purgeExpiredRows();

            verify(outboxEvents).deletePublishedBefore(any(Instant.class),
                    eq(RetentionSweep.PURGE_BATCH_SIZE));
            verify(processedEvents).deleteMarkersProcessedBefore(any(Instant.class),
                    eq(RetentionSweep.PURGE_BATCH_SIZE));
            verify(rejectedTransactions).deleteRejectedBefore(any(Instant.class),
                    eq(RetentionSweep.PURGE_BATCH_SIZE));
            assertEquals(3, transactions.size(), "one transaction per statement, and no more");
        }

        @Test
        @DisplayName("each horizon is the configured retention subtracted from now")
        void eachHorizonIsTheConfiguredRetention() {
            everyTableIsEmpty();
            Instant before = Instant.now();

            sweep.purgeExpiredRows();

            ArgumentCaptor<Instant> published = ArgumentCaptor.forClass(Instant.class);
            verify(outboxEvents).deletePublishedBefore(published.capture(), anyInt());
            ArgumentCaptor<Instant> markers = ArgumentCaptor.forClass(Instant.class);
            verify(processedEvents).deleteMarkersProcessedBefore(markers.capture(), anyInt());
            ArgumentCaptor<Instant> rejects = ArgumentCaptor.forClass(Instant.class);
            verify(rejectedTransactions).deleteRejectedBefore(rejects.capture(), anyInt());

            Instant publishedHorizon = published.getValue();
            Instant markerHorizon = markers.getValue();
            Instant rejectedHorizon = rejects.getValue();
            assertTrue(publishedHorizon
                            .isBefore(before.minus(Duration.ofHours(PUBLISHED_RETENTION_HOURS))
                                    .plusSeconds(1L)),
                    "the published horizon is not the configured week back: " + publishedHorizon);
            assertTrue(markerHorizon
                            .isBefore(before.minus(Duration.ofHours(MARKER_RETENTION_HOURS))
                                    .plusSeconds(1L)),
                    "the marker horizon is not the configured month back: " + markerHorizon);
            assertTrue(rejectedHorizon
                            .isBefore(before.minus(Duration.ofDays(REJECTED_RETENTION_DAYS))
                                    .plusSeconds(1L)),
                    "the reject horizon is not the declared ninety days back: " + rejectedHorizon);
        }

        @Test
        @DisplayName("the ledger itself carries no horizon and is never swept")
        void theLedgerItselfCarriesNoHorizon() {
            everyTableIsEmpty();

            sweep.purgeExpiredRows();

            assertEquals(3, transactions.size(),
                    "only outbox_event, processed_event and rejected_transaction expire here; the"
                            + " transaction, category-balance and balance-projection stores are not"
                            + " collaborators of this sweep at all");
        }
    }

    @Nested
    @DisplayName("The drain, so a bounded delete does not leave a permanent backlog")
    class Drain {

        @Test
        @DisplayName("a full batch is followed by another until one comes back short")
        void aFullBatchIsFollowedByAnotherUntilOneComesBackShort() {
            when(outboxEvents.deletePublishedBefore(any(Instant.class), anyInt()))
                    .thenReturn(RetentionSweep.PURGE_BATCH_SIZE,
                            RetentionSweep.PURGE_BATCH_SIZE, 7);
            when(processedEvents.deleteMarkersProcessedBefore(any(Instant.class), anyInt()))
                    .thenReturn(0);
            when(rejectedTransactions.deleteRejectedBefore(any(Instant.class), anyInt()))
                    .thenReturn(0);

            sweep.purgeExpiredRows();

            verify(outboxEvents, times(3)).deletePublishedBefore(any(Instant.class), anyInt());
            verify(processedEvents, times(1))
                    .deleteMarkersProcessedBefore(any(Instant.class), anyInt());
            verify(rejectedTransactions, times(1))
                    .deleteRejectedBefore(any(Instant.class), anyInt());
            assertEquals(5, transactions.size(),
                    "three outbox statements, one marker statement and one reject statement, each"
                            + " in its own transaction");
        }

        @Test
        @DisplayName("a short first batch issues no second statement")
        void aShortFirstBatchIssuesNoSecondStatement() {
            everyTableIsEmpty();
            when(outboxEvents.deletePublishedBefore(any(Instant.class), anyInt()))
                    .thenReturn(RetentionSweep.PURGE_BATCH_SIZE - 1);

            sweep.purgeExpiredRows();

            verify(outboxEvents, times(1)).deletePublishedBefore(any(Instant.class), anyInt());
        }

        @Test
        @DisplayName("a full batch of rejects is drained rather than left behind")
        void aFullBatchOfRejectsIsDrained() {
            everyTableIsEmpty();
            when(rejectedTransactions.deleteRejectedBefore(any(Instant.class), anyInt()))
                    .thenReturn(RetentionSweep.PURGE_BATCH_SIZE, 4);

            sweep.purgeExpiredRows();

            verify(rejectedTransactions, times(2))
                    .deleteRejectedBefore(any(Instant.class), anyInt());
        }

        @Test
        @DisplayName("an endless backlog stops at the table ceiling rather than holding the thread")
        void anEndlessBacklogStopsAtTheTableCeiling() {
            RetentionSweep bounded = new RetentionSweep(outboxEvents, processedEvents,
                    rejectedTransactions, countingTransactions(transactions), properties(),
                    Duration.ofMillis(50L));
            everyTableIsEmpty();
            when(outboxEvents.deletePublishedBefore(any(Instant.class), anyInt()))
                    .thenReturn(RetentionSweep.PURGE_BATCH_SIZE);

            assertTimeout(Duration.ofSeconds(10L), bounded::purgeExpiredRows);

            verify(processedEvents, times(1))
                    .deleteMarkersProcessedBefore(any(Instant.class), anyInt());
            verify(rejectedTransactions, times(1))
                    .deleteRejectedBefore(any(Instant.class), anyInt());
            assertTrue(RetentionSweep.MAX_TABLE_DURATION.toSeconds() > 0L,
                    "the production ceiling must be finite and positive");
        }

        @Test
        @DisplayName("a failure on one table still sweeps the others")
        void aFailureOnOneTableStillSweepsTheOthers() {
            everyTableIsEmpty();
            when(outboxEvents.deletePublishedBefore(any(Instant.class), anyInt()))
                    .thenThrow(new TransientDataAccessResourceException("outbox_event unavailable"));
            when(processedEvents.deleteMarkersProcessedBefore(any(Instant.class), anyInt()))
                    .thenReturn(3);

            assertDoesNotThrow(() -> sweep.purgeExpiredRows(),
                    "a scheduled method that raises stops the schedule for the rest of the run");

            verify(processedEvents, times(1))
                    .deleteMarkersProcessedBefore(any(Instant.class), anyInt());
            verify(rejectedTransactions, times(1))
                    .deleteRejectedBefore(any(Instant.class), anyInt());
        }
    }
}
