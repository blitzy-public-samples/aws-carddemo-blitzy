package com.carddemo.card.domain;

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

import com.carddemo.card.config.CardProperties;
import com.carddemo.card.repository.OutboxEventRepository;
import com.carddemo.card.repository.ProcessedEventRepository;
import java.lang.reflect.Constructor;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
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
 * <p>No COBOL ancestor. The source has no retention concern: every dataset's lifetime belongs to
 * the
 * Job Control Language, and {@code app/jcl/CARDFILE.jcl} deletes and redefines its dataset rather
 * than pruning it.
 *
 * <p>The defect these tests close is narrower here than in the other services, because the card
 * delete was already bounded and ordered. What was missing was the repetition. One batch of
 * {@value com.carddemo.card.domain.RetentionSweep#PURGE_BATCH_SIZE} rows an hour is a ceiling on
 * how
 * fast the tables can shrink, so a service that expires more than that in an hour never catches up
 * and the backlog is permanent. A bound without a drain trades an unpredictable stall for a
 * predictable leak.
 *
 * <p>{@code processed_event} is absent from every assertion below, and one test asserts that
 * absence directly. A claim is permanent, so this sweep holds no store over that table: a marker
 * horizon would expire a claim while the cross-reference replica it guards stayed.
 *
 * <p>Nothing here opens a database connection. The repository is a stand-in that answers with a row
 * count, and the transaction template runs its callback directly so the number of transactions is
 * exactly the number of statements.
 *
 * <p>Design decisions: {@code card-platform/docs/decision-log.md}.
 */
@DisplayName("RetentionSweep, its bounded batches and its finite drain")
class RetentionSweepTest {

    /** Hours a published outbox row stays, matching the shipped default. */
    private static final long PUBLISHED_RETENTION_HOURS = 168L;

    /** Store of the published rows, stubbed here. */
    private OutboxEventRepository outboxEvents;

    /** Counts how many transactions the sweep opened. */
    private List<String> transactions;

    /** The sweep under test. */
    private RetentionSweep sweep;

    @BeforeEach
    void buildSweep() {
        outboxEvents = mock(OutboxEventRepository.class);
        transactions = new ArrayList<>();
        sweep = new RetentionSweep(outboxEvents, countingTransactions(transactions), properties());
    }

    /** Settings carrying the shipped horizon and the shipped sweep interval. */
    private static CardProperties properties() {
        return new CardProperties(
                new CardProperties.Api(65_536L),
                new CardProperties.Kafka(new CardProperties.Kafka.Topics(
                        "card.updated", "carddemo.dead-letter")),
                new CardProperties.Outbox(new CardProperties.Outbox.Relay(
                        500L, 100, "retention-test", Duration.ofMinutes(2L), 5_000L,
                        Duration.ofSeconds(10L)),
                        PUBLISHED_RETENTION_HOURS),
                new CardProperties.Retention(3_600_000L),
                new CardProperties.Write(3_000L));
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
        @DisplayName("the delete names the batch ceiling rather than the whole table")
        void eachDeleteNamesTheBatchCeiling() {
            when(outboxEvents.deletePublishedBefore(any(Instant.class), anyInt())).thenReturn(0);

            sweep.purgeExpiredRows();

            verify(outboxEvents).deletePublishedBefore(any(Instant.class),
                    eq(RetentionSweep.PURGE_BATCH_SIZE));
            assertEquals(1, transactions.size(), "one transaction per statement, and no more");
        }

        @Test
        @DisplayName("the horizon is the configured retention subtracted from now")
        void eachHorizonIsTheConfiguredRetention() {
            when(outboxEvents.deletePublishedBefore(any(Instant.class), anyInt())).thenReturn(0);
            Instant before = Instant.now();

            sweep.purgeExpiredRows();

            ArgumentCaptor<Instant> published = ArgumentCaptor.forClass(Instant.class);
            verify(outboxEvents).deletePublishedBefore(published.capture(), anyInt());

            Instant publishedHorizon = published.getValue();
            assertTrue(publishedHorizon
                            .isBefore(before.minus(Duration.ofHours(PUBLISHED_RETENTION_HOURS))
                                    .plusSeconds(1L)),
                    "the published horizon is not the configured week back: " + publishedHorizon);
        }

        /**
         * Asserts nothing in this class can expire a duplicate-delivery claim.
         *
         * <p>Stated structurally, because a sweep holding no store over {@code processed_event}
         * cannot delete from it however it is scheduled or configured.
         */
        @Test
        @DisplayName("no constructor takes a store over processed_event, so no claim expires")
        void noConstructorTakesAStoreOverProcessedEvent() {
            assertTrue(Arrays.stream(RetentionSweep.class.getDeclaredFields())
                            .map(field -> field.getType())
                            .noneMatch(ProcessedEventRepository.class::equals),
                    "a field over processed_event is a delete waiting to be reintroduced");
            assertTrue(Arrays.stream(RetentionSweep.class.getDeclaredConstructors())
                            .map(Constructor::getParameterTypes)
                            .flatMap(Arrays::stream)
                            .noneMatch(ProcessedEventRepository.class::equals),
                    "no constructor may take a store over processed_event");
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

            sweep.purgeExpiredRows();

            verify(outboxEvents, times(3)).deletePublishedBefore(any(Instant.class), anyInt());
            assertEquals(3, transactions.size(),
                    "three outbox statements, each in its own transaction");
        }

        @Test
        @DisplayName("a short first batch issues no second statement")
        void aShortFirstBatchIssuesNoSecondStatement() {
            when(outboxEvents.deletePublishedBefore(any(Instant.class), anyInt()))
                    .thenReturn(RetentionSweep.PURGE_BATCH_SIZE - 1);

            sweep.purgeExpiredRows();

            verify(outboxEvents, times(1)).deletePublishedBefore(any(Instant.class), anyInt());
        }

        @Test
        @DisplayName("an endless backlog stops at the table ceiling rather than holding the thread")
        void anEndlessBacklogStopsAtTheTableCeiling() {
            RetentionSweep bounded = new RetentionSweep(outboxEvents,
                    countingTransactions(transactions), properties(), Duration.ofMillis(50L));
            when(outboxEvents.deletePublishedBefore(any(Instant.class), anyInt()))
                    .thenReturn(RetentionSweep.PURGE_BATCH_SIZE);

            assertTimeout(Duration.ofSeconds(10L), bounded::purgeExpiredRows);

            assertTrue(RetentionSweep.MAX_TABLE_DURATION.toSeconds() > 0L,
                    "the production ceiling must be finite and positive");
        }

        @Test
        @DisplayName("a failure leaves the schedule running rather than raising")
        void aFailureLeavesTheScheduleRunning() {
            when(outboxEvents.deletePublishedBefore(any(Instant.class), anyInt()))
                    .thenThrow(new TransientDataAccessResourceException(
                            "outbox_event unavailable"));

            assertDoesNotThrow(() -> sweep.purgeExpiredRows(),
                    "a scheduled method that raises stops the schedule for the rest of the run");

            verify(outboxEvents, times(1)).deletePublishedBefore(any(Instant.class), anyInt());
        }
    }
}
