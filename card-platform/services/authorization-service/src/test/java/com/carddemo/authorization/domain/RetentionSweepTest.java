package com.carddemo.authorization.domain;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTimeout;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.carddemo.authorization.config.AuthorizationProperties;
import com.carddemo.authorization.repository.AuthorizationDecisionRepository;
import com.carddemo.authorization.repository.OutboxEventRepository;
import com.carddemo.authorization.repository.ProcessedEventRepository;
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
 * Proves each retention delete is bounded, drained and finite, and that no pass expires a
 * duplicate-delivery claim.
 *
 * <p>No COBOL ancestor. The source has no retention concern: every dataset's lifetime belongs to the
 * Job Control Language, and {@code app/jcl/POSTTRAN.jcl} deletes and redefines its output rather than
 * pruning it.
 *
 * <p>The defect these tests close is <strong>not</strong> the one the sibling services had. Every
 * statement here was already bounded to a batch ceiling, so no statement ever held a large lock. What
 * was missing was the repetition: one pass issued exactly one bounded delete per table and stopped,
 * once an hour. One thousand rows an hour per table is a fixed removal rate, so any arrival
 * rate above it grew the schema for ever while every individual statement stayed small and fast. A
 * bound without a drain is a slower leak, not a fixed one, and it is the harder of the two to notice
 * because nothing about it is ever slow.
 *
 * <p>The repository documentation compounded it by describing the drain as already present, stating
 * that the caller repeated the delete until it came back short. It did not, so the sentence a reader
 * would have relied on was the opposite of the behaviour.
 *
 * <p>{@code processed_event} is absent from every assertion below, and one test asserts that
 * absence directly. A claim is permanent and this sweep holds no store over that table, because any
 * horizon would remove a claim while the decision row and the two replica tables it guards stand.
 *
 * <p>Nothing here opens a database connection. Each store is a stand-in that answers with a row
 * count, and the transaction template runs its callback directly, so the number of recorded
 * transactions is exactly the number of statements issued.
 *
 * <p>Design decisions: {@code card-platform/docs/decision-log.md}.
 */
@DisplayName("RetentionSweep, its bounded batches and its finite drain")
class RetentionSweepTest {

    /** Hours a published outbox row stays, matching the shipped default. */
    private static final long PUBLISHED_RETENTION_HOURS = 168L;

    /** Days a decision row and its accompanying unresolved-card attempt stay, shipped default. */
    private static final long DECISION_RETENTION_DAYS = 400L;

    /** Tables one pass sweeps, and therefore statements a pass with nothing to remove issues. */
    private static final int SWEPT_TABLES = 2;

    /** Store over {@code outbox_event}, stubbed here. */
    private OutboxEventRepository outboxEvents;

    /** Store over {@code authorization_decision}, stubbed here. */
    private AuthorizationDecisionRepository decisions;

    /** Counts how many transactions the sweep opened. */
    private List<String> transactions;

    /** The sweep under test. */
    private RetentionSweep sweep;

    @BeforeEach
    void buildSweep() {
        outboxEvents = mock(OutboxEventRepository.class);
        decisions = mock(AuthorizationDecisionRepository.class);
        transactions = new ArrayList<>();
        sweep = new RetentionSweep(outboxEvents, decisions, countingTransactions(transactions),
                properties());
    }

    /** Makes every table answer that it holds nothing expired, so one pass issues one statement each. */
    private void everyTableIsEmpty() {
        when(outboxEvents.deletePublishedBefore(any(Instant.class), anyInt())).thenReturn(0);
        when(decisions.deleteDecidedBefore(any(Instant.class), anyInt())).thenReturn(0);
    }

    /** Settings carrying the shipped horizons and the shipped sweep interval. */
    private static AuthorizationProperties properties() {
        return new AuthorizationProperties(
                new AuthorizationProperties.Kafka(
                        new AuthorizationProperties.Kafka.Topics(
                                "transaction.authorized", "transaction.declined",
                                "account.state-changed", "card.updated", "carddemo.dead-letter"),
                        new AuthorizationProperties.Kafka.Groups(
                                "authorization-account-state", "authorization-card-updated")),
                new AuthorizationProperties.Outbox(
                        new AuthorizationProperties.Outbox.Relay(
                                500L, 100, "retention-test", Duration.ofSeconds(30L), 5_000L,
                                Duration.ofSeconds(10L)),
                        PUBLISHED_RETENTION_HOURS),
                new AuthorizationProperties.Retention(3_600_000L, DECISION_RETENTION_DAYS),
                new AuthorizationProperties.Replica(0L),
                new AuthorizationProperties.Decision(3_000L, Duration.ofMinutes(15L)));
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
        @DisplayName("each of the two deletes names the batch ceiling rather than the whole table")
        void eachDeleteNamesTheBatchCeiling() {
            everyTableIsEmpty();

            sweep.purgeExpiredRows();

            verify(outboxEvents).deletePublishedBefore(any(Instant.class),
                    eq(RetentionSweep.PURGE_BATCH_SIZE));
            verify(decisions).deleteDecidedBefore(any(Instant.class),
                    eq(RetentionSweep.PURGE_BATCH_SIZE));
            assertEquals(SWEPT_TABLES, transactions.size(),
                    "one transaction per statement, and no more");
        }

        @Test
        @DisplayName("each horizon is the configured retention subtracted from now")
        void eachHorizonIsTheConfiguredRetention() {
            everyTableIsEmpty();
            Instant before = Instant.now();

            sweep.purgeExpiredRows();

            ArgumentCaptor<Instant> published = ArgumentCaptor.forClass(Instant.class);
            verify(outboxEvents).deletePublishedBefore(published.capture(), anyInt());
            ArgumentCaptor<Instant> decided = ArgumentCaptor.forClass(Instant.class);
            verify(decisions).deleteDecidedBefore(decided.capture(), anyInt());

            assertTrue(published.getValue()
                            .isBefore(before.minus(Duration.ofHours(PUBLISHED_RETENTION_HOURS))
                                    .plusSeconds(1L)),
                    "the published horizon is not the configured week back: "
                            + published.getValue());
            assertTrue(decided.getValue()
                            .isBefore(before.minus(Duration.ofDays(DECISION_RETENTION_DAYS))
                                    .plusSeconds(1L)),
                    "the audit horizon is not the configured retention back: "
                            + decided.getValue());
        }

        @Test
        @DisplayName("two tables are swept, and the withdrawn audit table is not among them")
        void twoTablesAreSweptAndTheWithdrawnOneIsNot() {
            everyTableIsEmpty();

            sweep.purgeExpiredRows();

            // No table records an unresolved card attempt: a card that resolves no cross-reference
            // row is decided like any other outcome, and its authorization_decision and outbox_event
            // rows are swept by the two verifications below. A sweep naming a table migration V20
            // withdrew would fail against a migrated database rather than quietly doing nothing.
            verify(outboxEvents).deletePublishedBefore(any(Instant.class), anyInt());
            verify(decisions).deleteDecidedBefore(any(Instant.class), anyInt());
            assertEquals(SWEPT_TABLES, transactions.size(),
                    "one transaction per swept table, and the withdrawn table is not swept");
        }

        /**
         * Asserts nothing in this class can expire a duplicate-delivery claim.
         *
         * <p>Stated structurally rather than behaviourally, because a sweep holding no store over
         * {@code processed_event} cannot delete from it however it is scheduled or configured. The
         * effects a claim guards are a decision row kept for audit and two replica tables kept for
         * as long as the service runs, so no horizon shorter than those outlives them.
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
            everyTableIsEmpty();
            when(outboxEvents.deletePublishedBefore(any(Instant.class), anyInt()))
                    .thenReturn(RetentionSweep.PURGE_BATCH_SIZE,
                            RetentionSweep.PURGE_BATCH_SIZE, 7);

            sweep.purgeExpiredRows();

            verify(outboxEvents, times(3)).deletePublishedBefore(any(Instant.class), anyInt());
            assertEquals(SWEPT_TABLES + 2, transactions.size(),
                    "three outbox statements and one for the other table, every one in its own"
                            + " transaction");
        }

        @Test
        @DisplayName("every table drains, not only the first")
        void everyTableDrains() {
            everyTableIsEmpty();
            when(decisions.deleteDecidedBefore(any(Instant.class), anyInt()))
                    .thenReturn(RetentionSweep.PURGE_BATCH_SIZE, 1);
            when(outboxEvents.deletePublishedBefore(any(Instant.class), anyInt()))
                    .thenReturn(RetentionSweep.PURGE_BATCH_SIZE, 0);

            sweep.purgeExpiredRows();

            verify(decisions, times(2)).deleteDecidedBefore(any(Instant.class), anyInt());
            verify(outboxEvents, times(2)).deletePublishedBefore(any(Instant.class), anyInt());
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
        @DisplayName("an endless backlog stops at the table ceiling rather than holding the thread")
        void anEndlessBacklogStopsAtTheTableCeiling() {
            RetentionSweep bounded = new RetentionSweep(outboxEvents, decisions,
                    countingTransactions(transactions), properties(), Duration.ofMillis(50L));
            everyTableIsEmpty();
            when(outboxEvents.deletePublishedBefore(any(Instant.class), anyInt()))
                    .thenReturn(RetentionSweep.PURGE_BATCH_SIZE);

            assertTimeout(Duration.ofSeconds(10L), bounded::purgeExpiredRows);

            // The ceiling stopping one table is only useful if the table behind it is still
            // reached, which is why the sweep gives each table a deadline of its own rather than
            // sharing one budget between them.
            verify(decisions, times(1)).deleteDecidedBefore(any(Instant.class), anyInt());
            assertTrue(RetentionSweep.MAX_TABLE_DURATION.toSeconds() > 0L,
                    "the production ceiling must be finite and positive");
        }

        @Test
        @DisplayName("a failure on one table still sweeps the other")
        void aFailureOnOneTableStillSweepsTheOthers() {
            everyTableIsEmpty();
            when(outboxEvents.deletePublishedBefore(any(Instant.class), anyInt()))
                    .thenThrow(new TransientDataAccessResourceException("outbox_event unavailable"));

            assertDoesNotThrow(() -> sweep.purgeExpiredRows(),
                    "a scheduled method that raises stops the schedule for the rest of the run");

            verify(decisions, times(1)).deleteDecidedBefore(any(Instant.class), anyInt());
        }
    }
}
