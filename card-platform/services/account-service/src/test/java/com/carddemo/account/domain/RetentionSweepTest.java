package com.carddemo.account.domain;

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

import com.carddemo.account.config.AccountProperties;
import com.carddemo.account.repository.OutboxEventRepository;
import com.carddemo.account.repository.ProcessedEventRepository;
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
 * hot write path. {@code outbox_event} is written by every account update and every cycle close and
 * swept by the relay on a fixed delay, and {@code processed_event} is written by the
 * posted-transaction listener on every delivery, so one statement holding every expired row under one
 * lock blocks all three of them for a duration nobody can predict from the configuration.
 *
 * <p>Both statements here were genuinely unbounded rather than bounded and undrained, so the bound and
 * the drain arrived together. Either alone is worse than it looks: an unbounded statement blocks for
 * an unpredictable time, and a bounded statement issued once an hour leaves every row above one batch
 * behind for ever.
 *
 * <p>Nothing here opens a database connection. Each repository is a stand-in that answers with a row
 * count, and the transaction template runs its callback directly so the number of transactions is
 * exactly the number of statements.
 *
 * <p>Design decisions: {@code card-platform/docs/decision-log.md}.
 */
@DisplayName("RetentionSweep, its bounded batches and its finite drain")
class RetentionSweepTest {

    /** Hours a published outbox row stays, matching the shipped default. */
    private static final long PUBLISHED_RETENTION_HOURS = 168L;

    /** Hours a processed-event marker stays, matching the shipped default. */
    private static final long MARKER_RETENTION_HOURS = 720L;

    /**
     * Hours the broker keeps a record, matching the shipped default.
     *
     * <p>The marker horizon has to outlast this by
     * {@code AccountProperties.ProcessedEvent.MINIMUM_RETENTION_MARGIN}, so a replayed record
     * cannot arrive after the marker that suppresses it has gone.
     */
    private static final long BROKER_RETENTION_HOURS = 168L;

    /** Store of the published rows, stubbed here. */
    private OutboxEventRepository outboxEvents;

    /** Store of the duplicate-delivery markers, stubbed here. */
    private ProcessedEventRepository processedEvents;

    /** Counts how many transactions the sweep opened. */
    private List<String> transactions;

    /** The sweep under test. */
    private RetentionSweep sweep;

    @BeforeEach
    void buildSweep() {
        outboxEvents = mock(OutboxEventRepository.class);
        processedEvents = mock(ProcessedEventRepository.class);
        transactions = new ArrayList<>();
        sweep = new RetentionSweep(outboxEvents, processedEvents,
                countingTransactions(transactions), properties());
    }

    /** Settings carrying the two shipped horizons and the shipped sweep interval. */
    private static AccountProperties properties() {
        return new AccountProperties(
                new AccountProperties.Api(65_536L),
                new AccountProperties.Kafka(new AccountProperties.Kafka.Topics(
                        "account.state-changed", "customer.context-changed",
                        "transaction.posted", "carddemo.dead-letter"),
                        new AccountProperties.Kafka.Groups("account-posted")),
                new AccountProperties.Consumer(new AccountProperties.Consumer.Retry(3, 1000L)),
                new AccountProperties.Outbox(new AccountProperties.Outbox.Relay(
                        500L, 100, "retention-test", Duration.ofMinutes(2L), 5_000L,
                        Duration.ofSeconds(10L)),
                        PUBLISHED_RETENTION_HOURS),
                new AccountProperties.ProcessedEvent(MARKER_RETENTION_HOURS, BROKER_RETENTION_HOURS),
                new AccountProperties.Retention(3_600_000L),
                new AccountProperties.Write(3_000L));
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
            when(outboxEvents.deletePublishedBefore(any(Instant.class), anyInt())).thenReturn(0);
            when(processedEvents.deleteMarkersProcessedBefore(any(Instant.class), anyInt()))
                    .thenReturn(0);

            sweep.purgeExpiredRows();

            verify(outboxEvents).deletePublishedBefore(any(Instant.class),
                    eq(RetentionSweep.PURGE_BATCH_SIZE));
            verify(processedEvents).deleteMarkersProcessedBefore(any(Instant.class),
                    eq(RetentionSweep.PURGE_BATCH_SIZE));
            assertEquals(2, transactions.size(), "one transaction per statement, and no more");
        }

        @Test
        @DisplayName("each horizon is the configured retention subtracted from now")
        void eachHorizonIsTheConfiguredRetention() {
            when(outboxEvents.deletePublishedBefore(any(Instant.class), anyInt())).thenReturn(0);
            when(processedEvents.deleteMarkersProcessedBefore(any(Instant.class), anyInt()))
                    .thenReturn(0);
            Instant before = Instant.now();

            sweep.purgeExpiredRows();

            ArgumentCaptor<Instant> published = ArgumentCaptor.forClass(Instant.class);
            verify(outboxEvents).deletePublishedBefore(published.capture(), anyInt());
            ArgumentCaptor<Instant> markers = ArgumentCaptor.forClass(Instant.class);
            verify(processedEvents).deleteMarkersProcessedBefore(markers.capture(), anyInt());

            Instant publishedHorizon = published.getValue();
            Instant markerHorizon = markers.getValue();
            assertTrue(publishedHorizon
                            .isBefore(before.minus(Duration.ofHours(PUBLISHED_RETENTION_HOURS))
                                    .plusSeconds(1L)),
                    "the published horizon is not the configured week back: " + publishedHorizon);
            assertTrue(markerHorizon
                            .isBefore(before.minus(Duration.ofHours(MARKER_RETENTION_HOURS))
                                    .plusSeconds(1L)),
                    "the marker horizon is not the configured week back: " + markerHorizon);
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

            sweep.purgeExpiredRows();

            verify(outboxEvents, times(3)).deletePublishedBefore(any(Instant.class), anyInt());
            verify(processedEvents, times(1))
                    .deleteMarkersProcessedBefore(any(Instant.class), anyInt());
            assertEquals(4, transactions.size(),
                    "three outbox statements and one marker statement, each in its own"
                            + " transaction");
        }

        @Test
        @DisplayName("a short first batch issues no second statement")
        void aShortFirstBatchIssuesNoSecondStatement() {
            when(outboxEvents.deletePublishedBefore(any(Instant.class), anyInt()))
                    .thenReturn(RetentionSweep.PURGE_BATCH_SIZE - 1);
            when(processedEvents.deleteMarkersProcessedBefore(any(Instant.class), anyInt()))
                    .thenReturn(0);

            sweep.purgeExpiredRows();

            verify(outboxEvents, times(1)).deletePublishedBefore(any(Instant.class), anyInt());
        }

        @Test
        @DisplayName("an endless backlog stops at the table ceiling rather than holding the thread")
        void anEndlessBacklogStopsAtTheTableCeiling() {
            RetentionSweep bounded = new RetentionSweep(outboxEvents, processedEvents,
                    countingTransactions(transactions), properties(), Duration.ofMillis(50L));
            when(outboxEvents.deletePublishedBefore(any(Instant.class), anyInt()))
                    .thenReturn(RetentionSweep.PURGE_BATCH_SIZE);
            when(processedEvents.deleteMarkersProcessedBefore(any(Instant.class), anyInt()))
                    .thenReturn(0);

            assertTimeout(Duration.ofSeconds(10L), bounded::purgeExpiredRows);

            verify(processedEvents, times(1))
                    .deleteMarkersProcessedBefore(any(Instant.class), anyInt());
            assertTrue(RetentionSweep.MAX_TABLE_DURATION.toSeconds() > 0L,
                    "the production ceiling must be finite and positive");
        }

        @Test
        @DisplayName("a failure on one table still sweeps the other")
        void aFailureOnOneTableStillSweepsTheOther() {
            when(outboxEvents.deletePublishedBefore(any(Instant.class), anyInt()))
                    .thenThrow(new TransientDataAccessResourceException("outbox_event unavailable"));
            when(processedEvents.deleteMarkersProcessedBefore(any(Instant.class), anyInt()))
                    .thenReturn(3);

            assertDoesNotThrow(() -> sweep.purgeExpiredRows(),
                    "a scheduled method that raises stops the schedule for the rest of the run");

            verify(processedEvents, times(1))
                    .deleteMarkersProcessedBefore(any(Instant.class), anyInt());
        }
    }
}
