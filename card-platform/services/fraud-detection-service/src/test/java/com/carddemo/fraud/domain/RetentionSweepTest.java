package com.carddemo.fraud.domain;

import static org.junit.jupiter.api.Assertions.assertAll;
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

import com.carddemo.fraud.config.FraudProperties;
import com.carddemo.fraud.repository.FraudAssessmentRepository;
import com.carddemo.fraud.repository.OutboxEventRepository;
import com.carddemo.fraud.repository.ProcessedEventRepository;
import com.carddemo.fraud.repository.VelocityWindowRepository;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
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
 * Proves each retention delete is bounded, ordered, drained and finite, and that the velocity table
 * is swept at all.
 *
 * <p>No COBOL ancestor. This whole service is ADDITIVE, and the source has no retention concern of
 * any kind: every dataset's lifetime belongs to the Job Control Language, and
 * {@code app/jcl/POSTTRAN.jcl} deletes and redefines its output rather than pruning it.
 *
 * <p>Two defects these tests close. The first is a bounded delete issued once per hourly sweep: the
 * bound keeps one statement short, and without a drain the surplus above one batch an hour is left
 * behind for ever, so a table grows without limit even though every statement against it was small.
 * The second is narrower and was the more serious of the two. {@code velocity_window} had a bounded,
 * ordered delete written and documented, and no production path called it, so one row per account
 * per span accumulated for the life of the schema while the method that would have removed them sat
 * unreferenced.
 *
 * <p>Nothing here opens a database connection. Each repository is a stand-in that answers with a row
 * count, and the transaction template runs its callback directly so the number of transactions is
 * exactly the number of statements.
 *
 * <p>Design decisions: {@code card-platform/docs/decision-log.md}.
 */
@DisplayName("RetentionSweep, its four tables, its bounded batches and its finite drain")
class RetentionSweepTest {

    /** Hours a published outbox row stays, matching the shipped default. */
    private static final long PUBLISHED_RETENTION_HOURS = 168L;

    /** Hours a processed-event marker stays, matching the shipped default. */
    private static final long MARKER_RETENTION_HOURS = 720L;

    /** Hours the broker keeps a topic log, which the marker horizon has to outlast. */
    private static final long BROKER_RETENTION_HOURS = 168L;

    /** Days a risk assessment stays, matching the horizon COMMENT ON TABLE declares. */
    private static final int ASSESSMENT_RETENTION_DAYS = 90;

    /** Days a velocity window stays, matching the horizon COMMENT ON TABLE declares. */
    private static final int VELOCITY_RETENTION_DAYS = 7;

    /** The shipped scoring window span, which the horizon above must outlast. */
    private static final int VELOCITY_WINDOW_MINUTES = 60;

    /** Store of the published rows, stubbed here. */
    private OutboxEventRepository outboxEvents;

    /** Store of the duplicate-delivery markers, stubbed here. */
    private ProcessedEventRepository processedEvents;

    /** Store of the risk assessments, stubbed here. */
    private FraudAssessmentRepository assessments;

    /** Store of the velocity buckets, stubbed here. */
    private VelocityWindowRepository velocityWindows;

    /** Counts how many transactions the sweep opened. */
    private List<String> transactions;

    /** The sweep under test. */
    private RetentionSweep sweep;

    @BeforeEach
    void buildSweep() {
        outboxEvents = mock(OutboxEventRepository.class);
        processedEvents = mock(ProcessedEventRepository.class);
        assessments = mock(FraudAssessmentRepository.class);
        velocityWindows = mock(VelocityWindowRepository.class);
        transactions = new ArrayList<>();
        sweep = new RetentionSweep(outboxEvents, processedEvents, assessments, velocityWindows,
                countingTransactions(transactions), properties());
    }

    /** Stubs every delete to report an empty table, which is one statement each. */
    private void everyTableIsEmpty() {
        when(outboxEvents.deletePublishedBefore(any(Instant.class), anyInt())).thenReturn(0);
        when(processedEvents.deleteMarkersProcessedBefore(any(Instant.class), anyInt()))
                .thenReturn(0);
        when(assessments.deleteAssessedBefore(any(Instant.class), anyInt())).thenReturn(0);
        when(velocityWindows.deleteWindowsStartedBefore(any(Instant.class), anyInt())).thenReturn(0);
    }

    /** Settings carrying the four shipped horizons and the shipped sweep interval. */
    private static FraudProperties properties() {
        return properties(VELOCITY_RETENTION_DAYS, VELOCITY_WINDOW_MINUTES);
    }

    /**
     * Settings carrying an explicit velocity horizon and window span.
     *
     * @param velocityRetentionDays days a velocity window stays
     * @param velocityWindowMinutes the scoring window span
     * @return the bound settings
     */
    private static FraudProperties properties(int velocityRetentionDays,
            int velocityWindowMinutes) {
        return new FraudProperties(
                new FraudProperties.Kafka(new FraudProperties.Kafka.Topics(
                        "transaction.authorized", "fraud.assessed", "carddemo.dead-letter",
                        ".DLT")),
                new FraudProperties.Consumer(new FraudProperties.Consumer.Retry(3, 1000L)),
                new FraudProperties.Outbox(new FraudProperties.Outbox.Relay(
                        500L, 100, "retention-test", Duration.ofMinutes(2L), 5_000L),
                        PUBLISHED_RETENTION_HOURS),
                new FraudProperties.ProcessedEvent(MARKER_RETENTION_HOURS,
                        BROKER_RETENTION_HOURS),
                new FraudProperties.Retention(3_600_000L, ASSESSMENT_RETENTION_DAYS,
                        velocityRetentionDays),
                new FraudProperties.Fraud(new FraudProperties.Fraud.Risk(
                        70, velocityWindowMinutes, 5, new BigDecimal("500.00"))));
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
            verify(assessments).deleteAssessedBefore(any(Instant.class),
                    eq(RetentionSweep.PURGE_BATCH_SIZE));
            verify(velocityWindows).deleteWindowsStartedBefore(any(Instant.class),
                    eq(RetentionSweep.PURGE_BATCH_SIZE));
            assertEquals(4, transactions.size(), "one transaction per statement, and no more");
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
            ArgumentCaptor<Instant> assessed = ArgumentCaptor.forClass(Instant.class);
            verify(assessments).deleteAssessedBefore(assessed.capture(), anyInt());
            ArgumentCaptor<Instant> windows = ArgumentCaptor.forClass(Instant.class);
            verify(velocityWindows).deleteWindowsStartedBefore(windows.capture(), anyInt());

            assertTrue(published.getValue()
                            .isBefore(before.minus(Duration.ofHours(PUBLISHED_RETENTION_HOURS))
                                    .plusSeconds(1L)),
                    "the published horizon is not the configured week back: "
                            + published.getValue());
            assertTrue(markers.getValue()
                            .isBefore(before.minus(Duration.ofHours(MARKER_RETENTION_HOURS))
                                    .plusSeconds(1L)),
                    "the marker horizon is not the configured month back: " + markers.getValue());
            assertTrue(assessed.getValue()
                            .isBefore(before.minus(Duration.ofDays(ASSESSMENT_RETENTION_DAYS))
                                    .plusSeconds(1L)),
                    "the assessment horizon is not the declared ninety days back: "
                            + assessed.getValue());
            assertTrue(windows.getValue()
                            .isBefore(before.minus(Duration.ofDays(VELOCITY_RETENTION_DAYS))
                                    .plusSeconds(1L)),
                    "the velocity horizon is not the declared seven days back: "
                            + windows.getValue());
        }

        @Test
        @DisplayName("the velocity horizon lies further back than the window a live score reads")
        void theVelocityHorizonLiesFurtherBackThanTheWindow() {
            everyTableIsEmpty();
            Instant before = Instant.now();

            sweep.purgeExpiredRows();

            ArgumentCaptor<Instant> windows = ArgumentCaptor.forClass(Instant.class);
            verify(velocityWindows).deleteWindowsStartedBefore(windows.capture(), anyInt());

            Instant currentWindowStart = before.minus(Duration.ofMinutes(VELOCITY_WINDOW_MINUTES))
                    .truncatedTo(ChronoUnit.HOURS);
            assertTrue(windows.getValue().isBefore(currentWindowStart),
                    "the horizon " + windows.getValue() + " reaches the bucket a live"
                            + " authorization is counting into, which starts at "
                            + currentWindowStart);
        }
    }

    @Nested
    @DisplayName("The drain, so a bounded delete does not leave a permanent backlog")
    class Drain {

        @Test
        @DisplayName("a full batch is followed by another until one comes back short")
        void aFullBatchIsFollowedByAnotherUntilOneComesBackShort() {
            everyTableIsEmpty();
            when(velocityWindows.deleteWindowsStartedBefore(any(Instant.class), anyInt()))
                    .thenReturn(RetentionSweep.PURGE_BATCH_SIZE, RetentionSweep.PURGE_BATCH_SIZE,
                            7);

            sweep.purgeExpiredRows();

            verify(velocityWindows, times(3))
                    .deleteWindowsStartedBefore(any(Instant.class), anyInt());
            assertEquals(6, transactions.size(),
                    "one outbox statement, one marker statement, one assessment statement and"
                            + " three velocity statements, each in its own transaction");
        }

        @Test
        @DisplayName("a short first batch issues no second statement")
        void aShortFirstBatchIssuesNoSecondStatement() {
            everyTableIsEmpty();
            when(velocityWindows.deleteWindowsStartedBefore(any(Instant.class), anyInt()))
                    .thenReturn(RetentionSweep.PURGE_BATCH_SIZE - 1);

            sweep.purgeExpiredRows();

            verify(velocityWindows, times(1))
                    .deleteWindowsStartedBefore(any(Instant.class), anyInt());
        }

        @Test
        @DisplayName("an endless backlog stops at the table ceiling rather than holding the thread")
        void anEndlessBacklogStopsAtTheTableCeiling() {
            RetentionSweep bounded = new RetentionSweep(outboxEvents, processedEvents, assessments,
                    velocityWindows, countingTransactions(transactions), properties(),
                    Duration.ofMillis(50L));
            everyTableIsEmpty();
            when(outboxEvents.deletePublishedBefore(any(Instant.class), anyInt()))
                    .thenReturn(RetentionSweep.PURGE_BATCH_SIZE);

            assertTimeout(Duration.ofSeconds(10L), bounded::purgeExpiredRows);

            verify(processedEvents, times(1))
                    .deleteMarkersProcessedBefore(any(Instant.class), anyInt());
            verify(assessments, times(1)).deleteAssessedBefore(any(Instant.class), anyInt());
            verify(velocityWindows, times(1))
                    .deleteWindowsStartedBefore(any(Instant.class), anyInt());
            assertTrue(RetentionSweep.MAX_TABLE_DURATION.toSeconds() > 0L,
                    "the production ceiling must be finite and positive");
        }

        @Test
        @DisplayName("a failure on one table still sweeps the others")
        void aFailureOnOneTableStillSweepsTheOthers() {
            everyTableIsEmpty();
            when(outboxEvents.deletePublishedBefore(any(Instant.class), anyInt()))
                    .thenThrow(new TransientDataAccessResourceException("outbox_event unavailable"));

            assertDoesNotThrow(() -> sweep.purgeExpiredRows(),
                    "a scheduled method that raises stops the schedule for the rest of the run");

            verify(processedEvents, times(1))
                    .deleteMarkersProcessedBefore(any(Instant.class), anyInt());
            verify(assessments, times(1)).deleteAssessedBefore(any(Instant.class), anyInt());
            verify(velocityWindows, times(1))
                    .deleteWindowsStartedBefore(any(Instant.class), anyInt());
        }
    }

    @Nested
    @DisplayName("The assessment table, whose declared horizon nothing applied")
    class AssessmentTable {

        @Test
        @DisplayName("the scheduled sweep expires assessments at the horizon the table declares")
        void theScheduledSweepExpiresAssessmentsAtTheDeclaredHorizon() {
            everyTableIsEmpty();

            sweep.purgeExpiredRows();

            verify(assessments, times(1)).deleteAssessedBefore(any(Instant.class),
                    eq(RetentionSweep.PURGE_BATCH_SIZE));
        }

        @Test
        @DisplayName("a full batch of assessments is drained rather than left behind")
        void aFullBatchOfAssessmentsIsDrained() {
            everyTableIsEmpty();
            when(assessments.deleteAssessedBefore(any(Instant.class), anyInt()))
                    .thenReturn(RetentionSweep.PURGE_BATCH_SIZE, 3);

            sweep.purgeExpiredRows();

            verify(assessments, times(2)).deleteAssessedBefore(any(Instant.class), anyInt());
        }
    }

    @Nested
    @DisplayName("The velocity table, which no production path used to sweep")
    class VelocityTable {

        @Test
        @DisplayName("the scheduled sweep calls the bounded delete the repository declares")
        void theScheduledSweepCallsTheBoundedDelete() {
            everyTableIsEmpty();

            sweep.purgeExpiredRows();

            verify(velocityWindows, times(1))
                    .deleteWindowsStartedBefore(any(Instant.class), anyInt());
        }

        @Test
        @DisplayName("a horizon no longer than the window span is refused at binding")
        void aHorizonNoLongerThanTheWindowSpanIsRefusedAtBinding() {
            IllegalArgumentException equal = org.junit.jupiter.api.Assertions.assertThrows(
                    IllegalArgumentException.class, () -> properties(1, 1440));
            IllegalArgumentException shorter = org.junit.jupiter.api.Assertions.assertThrows(
                    IllegalArgumentException.class, () -> properties(1, 2880));

            assertTrue(equal.getMessage().contains("velocity-retention-days"),
                    "the refusal must name the setting a deployment would change: "
                            + equal.getMessage());
            assertTrue(shorter.getMessage().contains("velocity-window-minutes"),
                    "the refusal must name the setting it was compared against: "
                            + shorter.getMessage());
        }

        @Test
        @DisplayName("a horizon longer than the window span is accepted")
        void aHorizonLongerThanTheWindowSpanIsAccepted() {
            FraudProperties bound = assertDoesNotThrow(() -> properties(1, 60),
                    "a day outlasts a sixty-minute window and must bind");

            assertAll(
                    () -> assertEquals(1, bound.retention().velocityRetentionDays(),
                            "the horizon binds as the day it was given"),
                    () -> assertEquals(60, bound.fraud().risk().velocityWindowMinutes(),
                            "and the window as the sixty minutes it was given"),
                    () -> assertTrue(Duration.ofDays(bound.retention().velocityRetentionDays())
                                    .compareTo(Duration.ofMinutes(bound.fraud().risk().velocityWindowMinutes()))
                                    > 0,
                            "which is the relation this case is about: the horizon outlasts the"
                                    + " window, so a swept row is one no window still reads"));
        }
    }
}
