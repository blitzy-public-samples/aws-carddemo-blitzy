package com.carddemo.notification.domain;

import com.carddemo.cobol.CobolDecimal;
import com.carddemo.cobol.PicClause;
import com.carddemo.notification.config.NotificationProperties;
import com.carddemo.notification.repository.NotificationLogRepository;
import com.carddemo.notification.repository.ProcessedEventRepository;
import com.carddemo.notification.repository.StatementTransactionRepository;

import java.lang.reflect.Method;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.springframework.dao.InvalidDataAccessApiUsageException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

/**
 * Asserts {@link RetentionSweep} enforces the two horizons {@code application.yml} declares, and
 * that it enforces them through an explicit transaction rather than through an annotation a
 * self-invocation would bypass.
 *
 * <p>{@code processed_event} is not among them. A security review found the marker horizon expiring
 * duplicate-delivery claims while the read-model row and the rendered alert they guard outlived them,
 * so a claim is permanent now and one test below asserts this sweep holds no store over that
 * table.
 *
 * <p>The horizons are additive in full. No program under {@code app/cbl/} expires a record: a Virtual
 * Storage Access Method dataset was reorganised by an operator and a Generation Data Group aged its
 * own generations off, so there is no ancestor to compare against. What these tests hold to account
 * is narrower and entirely checkable: every horizon the shipped configuration names reaches the store
 * that owns those rows, measured back from now by exactly the configured amount.
 *
 * <p>Every test runs against mocked repositories and a counting {@link TransactionTemplate}, so
 * {@code mvn test} needs no database and no container.
 */
@DisplayName("RetentionSweep, the two retention horizons of the notification service")
class RetentionSweepTest {

    /** Read-model horizon the shipped configuration names, in days. */
    private static final int STATEMENT_RETENTION_DAYS = 400;

    /** Attempt-row horizon the shipped configuration names, in days. */
    private static final int LOG_RETENTION_DAYS = 90;

    /**
     * How far a horizon this test computes may sit from the one the sweep computed. The sweep reads
     * its own clock, so the two moments differ by the time between the two reads and by nothing else.
     */
    private static final Duration CLOCK_TOLERANCE = Duration.ofMinutes(1);

    private final StatementTransactionRepository statementTransactions =
            Mockito.mock(StatementTransactionRepository.class);
    private final NotificationLogRepository notificationLog =
            Mockito.mock(NotificationLogRepository.class);
    private final CountingTransactionTemplate transactions = new CountingTransactionTemplate();

    private final RetentionSweep sweep = new RetentionSweep(statementTransactions, notificationLog,
            shippedProperties(), transactions);

    @Nested
    @DisplayName("One pass over the two swept tables")
    class OnePass {

        @Test
        @DisplayName("reaches both stores, each in a transaction of its own")
        void reachesBothStoresEachInItsOwnTransaction() {
            when(statementTransactions.deleteProcessedBefore(Mockito.anyString(), Mockito.anyInt()))
                    .thenReturn(11);
            when(notificationLog.deleteRenderedBefore(Mockito.any(), Mockito.anyInt()))
                    .thenReturn(2);

            sweep.sweepExpiredRows();

            verify(statementTransactions).deleteProcessedBefore(Mockito.anyString(), Mockito.anyInt());
            verify(notificationLog).deleteRenderedBefore(Mockito.any(), Mockito.anyInt());
            assertThat(transactions.opened())
                    .as("transactions opened, one per delete")
                    .isEqualTo(2);
        }

        /**
         * The defect this test exists to prevent. A modifying query needs an open transaction, and a
         * scheduled method reaching its neighbours on {@code this} never passes through the proxy
         * {@code @Transactional} would need. Every delete would then fail as a data-access fault the
         * sweep logs and swallows, leaving all three horizons unenforced while reporting nothing
         * louder than a warning. Counting the transactions is what makes that silent outcome
         * impossible to reintroduce.
         */
        @Test
        @DisplayName("opens its own transaction rather than relying on an annotation")
        void opensItsOwnTransactionRatherThanRelyingOnAnAnnotation() {
            sweep.sweepExpiredRows();

            assertThat(transactions.opened()).isEqualTo(2);
            assertThat(annotationNamesOn("sweepReadModelRows"))
                    .as("annotations on sweepReadModelRows")
                    .doesNotContain("Transactional");
            assertThat(annotationNamesOn("sweepRenderedAlertRows"))
                    .as("annotations on sweepRenderedAlertRows")
                    .doesNotContain("Transactional");
        }

        @Test
        @DisplayName("carries the configured interval on its schedule")
        void carriesTheConfiguredIntervalOnItsSchedule() throws NoSuchMethodException {
            Scheduled schedule = RetentionSweep.class.getMethod("sweepExpiredRows")
                    .getAnnotation(Scheduled.class);

            assertThat(schedule).as("@Scheduled on sweepExpiredRows").isNotNull();
            assertThat(schedule.fixedDelayString())
                    .as("the interval this sweep runs on")
                    .isEqualTo("${carddemo.history.sweep-interval-ms}");
        }
    }

    @Nested
    @DisplayName("Each horizon")
    class EachHorizon {

        @Test
        @DisplayName("measures the attempt horizon back by the configured days")
        void measuresTheAttemptHorizonBackByTheConfiguredDays() {
            ArgumentCaptor<Instant> horizon = ArgumentCaptor.forClass(Instant.class);

            sweep.sweepRenderedAlertRows();

            verify(notificationLog).deleteRenderedBefore(horizon.capture(), Mockito.anyInt());
            assertThat(horizon.getValue())
                    .as("the attempt horizon, %d days back", LOG_RETENTION_DAYS)
                    .isCloseTo(Instant.now().minus(Duration.ofDays(LOG_RETENTION_DAYS)),
                            within(CLOCK_TOLERANCE.toMillis(), ChronoUnit.MILLIS));
        }

        /**
         * The read-model column holds {@code TRNX-PROC-TS PIC X(26)}, a fixed-width text stamp whose
         * lexical order is its chronological order. The horizon therefore has to arrive as text of
         * exactly that width, built by the same formatter that wrote every stored value, or the
         * comparison would be against a shape no row carries.
         */
        @Test
        @DisplayName("renders the read-model horizon at the 26-character width of the column")
        void rendersTheReadModelHorizonAtTheDeclaredWidth() {
            ArgumentCaptor<String> horizon = ArgumentCaptor.forClass(String.class);

            Instant before = Instant.now();
            sweep.sweepReadModelRows();
            Instant after = Instant.now();

            verify(statementTransactions).deleteProcessedBefore(horizon.capture(), Mockito.anyInt());
            String stamped = horizon.getValue();
            assertThat(stamped)
                    .as("the rendered read-model horizon")
                    .hasSize(PicClause.PROCESSING_TIMESTAMP_WIDTH);
            assertThat(stamped)
                    .as("the rendered read-model horizon, as the column shape")
                    .matches("[0-9]{4}-[0-9]{2}-[0-9]{2}-[0-9]{2}\\.[0-9]{2}\\.[0-9]{2}"
                            + "\\.[0-9]{2}0000");
            // The sweep read its own clock somewhere between the two instants above, so the horizon
            // it rendered sits between the two horizons those instants imply. The comparison is
            // lexical, which is exact here rather than approximate: the column is fixed-width text
            // whose lexical order is its chronological order, which is the property the delete
            // itself relies on.
            //
            // The earlier form of this assertion compared only the four-digit year against a second
            // Instant.now() read after the sweep. It agreed with itself on every day but one: a
            // sweep whose horizon fell on the last instant of a year and an assertion evaluated
            // after midnight read two different years and failed for a reason unrelated to the
            // rendering under test. Bracketing removes that, and pins all 26 characters instead of
            // the first four.
            assertThat(stamped)
                    .as("the horizon the sweep rendered, %d days back from a clock read between "
                            + "%s and %s", STATEMENT_RETENTION_DAYS, before, after)
                    .isBetween(renderedHorizon(before), renderedHorizon(after));
        }

        /**
         * Renders the read-model horizon one clock reading implies, the way the sweep renders it.
         *
         * @param read the moment the clock returned
         * @return the 26-character stamp the delete would compare against
         */
        private String renderedHorizon(Instant read) {
            return CobolDecimal.formatProcessingTimestamp(
                    LocalDateTime.ofInstant(read, ZoneOffset.UTC)
                            .minusDays(STATEMENT_RETENTION_DAYS));
        }
    }

    @Nested
    @DisplayName("A failure on one table")
    class OneTableFailing {

        @Test
        @DisplayName("leaves the other swept and never reaches the caller")
        void leavesTheOtherSweptAndNeverReachesTheCaller() {
            when(statementTransactions.deleteProcessedBefore(Mockito.anyString(), Mockito.anyInt()))
                    .thenThrow(new InvalidDataAccessApiUsageException("no permission"));

            assertThatCode(sweep::sweepExpiredRows)
                    .as("a sweep pass with one table failing")
                    .doesNotThrowAnyException();

            verify(statementTransactions).deleteProcessedBefore(Mockito.anyString(), Mockito.anyInt());
            verify(notificationLog).deleteRenderedBefore(Mockito.any(), Mockito.anyInt());
        }

        /**
         * A fault that is not a data-access fault is not an operational condition this sweep can
         * report and carry on from, so it leaves the method and the scheduler logs it.
         */
        @Test
        @DisplayName("lets a fault that is not a data-access fault leave the method")
        void letsAFaultThatIsNotADataAccessFaultLeaveTheMethod() {
            when(notificationLog.deleteRenderedBefore(Mockito.any(), Mockito.anyInt()))
                    .thenThrow(new IllegalStateException("the entity manager is closed"));

            assertThatThrownBy(sweep::sweepRenderedAlertRows)
                    .isInstanceOf(IllegalStateException.class);
        }
    }

    @Nested
    @DisplayName("The stores this sweep reaches")
    class StoresReached {

        @Test
        @DisplayName("are the two that grow by one row per consumed event, and no other")
        void areTheTwoThatGrowPerConsumedEventAndNoOther() {
            sweep.sweepExpiredRows();

            verify(statementTransactions).deleteProcessedBefore(Mockito.anyString(), Mockito.anyInt());
            verify(notificationLog).deleteRenderedBefore(Mockito.any(), Mockito.anyInt());
            verifyNoMoreInteractions(statementTransactions, notificationLog);
        }

        /**
         * Asserts nothing in this class can expire a duplicate-delivery claim.
         *
         * <p>Stated structurally, because a sweep holding no store over {@code processed_event}
         * cannot delete from it however it is scheduled or configured. The effects a claim guards are
         * a read-model row kept for four hundred days and a rendered alert kept for ninety, and both
         * outlived the 720-hour marker horizon this replaced.
         */
        @Test
        @DisplayName("do not include the marker store, so no pass expires a claim")
        void doNotIncludeTheMarkerStore() {
            List<String> fields = java.util.Arrays.stream(RetentionSweep.class
                            .getDeclaredFields())
                    .map(field -> field.getType().getName())
                    .toList();
            List<String> accepted = java.util.Arrays.stream(RetentionSweep.class
                            .getDeclaredConstructors())
                    .map(java.lang.reflect.Constructor::getParameterTypes)
                    .flatMap(java.util.Arrays::stream)
                    .map(Class::getName)
                    .toList();

            assertThat(fields)
                    .as("the collaborators this sweep holds")
                    .doesNotContain(ProcessedEventRepository.class.getName());
            assertThat(accepted)
                    .as("the collaborators any constructor of this sweep accepts")
                    .doesNotContain(ProcessedEventRepository.class.getName());
        }
    }

    @Nested
    @DisplayName("The bounded drain")
    class BoundedDrain {

        @Test
        @DisplayName("asks for no more than the batch ceiling in one statement")
        void asksForNoMoreThanTheBatchCeilingInOneStatement() {
            ArgumentCaptor<Integer> rowLimit = ArgumentCaptor.forClass(Integer.class);
            ArgumentCaptor<Integer> attemptLimit = ArgumentCaptor.forClass(Integer.class);

            sweep.sweepExpiredRows();

            verify(statementTransactions)
                    .deleteProcessedBefore(Mockito.anyString(), rowLimit.capture());
            verify(notificationLog).deleteRenderedBefore(Mockito.any(), attemptLimit.capture());

            assertThat(rowLimit.getValue()).isEqualTo(RetentionSweep.PURGE_BATCH_SIZE);
            assertThat(attemptLimit.getValue()).isEqualTo(RetentionSweep.PURGE_BATCH_SIZE);
        }

        /**
         * The defect this test exists to prevent. A bound without a drain leaves the surplus behind
         * on every pass as soon as rows arrive faster than one batch a pass, so the table grows
         * without limit even though every statement against it was short. A full batch means more
         * rows remain, so the sweep has to come back for them inside the same pass.
         */
        @Test
        @DisplayName("takes further batches while a table keeps answering a full one")
        void takesFurtherBatchesWhileATableKeepsAnsweringAFullOne() {
            when(notificationLog.deleteRenderedBefore(Mockito.any(), Mockito.anyInt()))
                    .thenReturn(RetentionSweep.PURGE_BATCH_SIZE, RetentionSweep.PURGE_BATCH_SIZE, 7);

            sweep.sweepRenderedAlertRows();

            verify(notificationLog, Mockito.times(3))
                    .deleteRenderedBefore(Mockito.any(), Mockito.anyInt());
            assertThat(transactions.opened())
                    .as("transactions opened, one per batch")
                    .isEqualTo(3);
        }

        @Test
        @DisplayName("stops at the first short batch rather than issuing one that removes nothing")
        void stopsAtTheFirstShortBatchRatherThanIssuingOneThatRemovesNothing() {
            when(notificationLog.deleteRenderedBefore(Mockito.any(), Mockito.anyInt()))
                    .thenReturn(RetentionSweep.PURGE_BATCH_SIZE - 1);

            sweep.sweepRenderedAlertRows();

            verify(notificationLog, Mockito.times(1))
                    .deleteRenderedBefore(Mockito.any(), Mockito.anyInt());
        }

        /**
         * A backlog no drain can clear must not hold the scheduled thread for as long as the backlog
         * takes to remove, because a table swept after this one would then never be reached.
         * An exhausted ceiling is a deliberate return, not a failure: the remainder is removed on the
         * next pass.
         */
        @Test
        @DisplayName("returns at its wall-clock ceiling when a table never answers a short batch")
        void returnsAtItsWallClockCeilingWhenATableNeverAnswersAShortBatch() {
            RetentionSweep ceilinged = new RetentionSweep(statementTransactions, notificationLog,
                    shippedProperties(), transactions, Duration.ZERO);
            when(notificationLog.deleteRenderedBefore(Mockito.any(), Mockito.anyInt()))
                    .thenReturn(RetentionSweep.PURGE_BATCH_SIZE);

            assertThatCode(ceilinged::sweepRenderedAlertRows)
                    .as("a drain against an inexhaustible backlog")
                    .doesNotThrowAnyException();

            verify(notificationLog, Mockito.times(1))
                    .deleteRenderedBefore(Mockito.any(), Mockito.anyInt());
        }

        @Test
        @DisplayName("keeps what earlier batches removed when a later one fails, and sweeps on")
        void keepsWhatEarlierBatchesRemovedWhenALaterOneFailsAndSweepsOn() {
            when(statementTransactions.deleteProcessedBefore(Mockito.anyString(), Mockito.anyInt()))
                    .thenReturn(RetentionSweep.PURGE_BATCH_SIZE)
                    .thenThrow(new InvalidDataAccessApiUsageException("no permission"));

            assertThatCode(sweep::sweepExpiredRows)
                    .as("a pass whose first table fails on its second batch")
                    .doesNotThrowAnyException();

            verify(statementTransactions, Mockito.times(2))
                    .deleteProcessedBefore(Mockito.anyString(), Mockito.anyInt());
            verify(notificationLog).deleteRenderedBefore(Mockito.any(), Mockito.anyInt());
        }

        @Test
        @DisplayName("carries a ceiling in production that is finite")
        void carriesACeilingInProductionThatIsFinite() {
            assertThat(RetentionSweep.MAX_TABLE_DURATION)
                    .as("the production drain ceiling for one table")
                    .isPositive()
                    .isLessThanOrEqualTo(Duration.ofMinutes(1));
        }
    }

    /** The horizons {@code src/main/resources/application.yml} ships. */
    private static NotificationProperties shippedProperties() {
        return new NotificationProperties(
                new NotificationProperties.Kafka(
                        new NotificationProperties.Kafka.Groups("notification-authorized",
                                "notification-posted", "notification-fraud",
                                "notification-customer"),
                        new NotificationProperties.Kafka.Topics("transaction.authorized",
                                "transaction.posted", "fraud.assessed",
                                "customer.context-changed", "carddemo.dead-letter", ".DLT")),
                new NotificationProperties.Consumer(
                        new NotificationProperties.Consumer.Retry(3, 1000L)),
                new NotificationProperties.History(STATEMENT_RETENTION_DAYS, LOG_RETENTION_DAYS,
                        3_600_000L));
    }

    /** The simple names of the annotations one declared method of the sweep carries. */
    private static List<String> annotationNamesOn(String methodName) {
        Method method;
        try {
            method = RetentionSweep.class.getMethod(methodName);
        } catch (NoSuchMethodException absent) {
            throw new AssertionError(methodName + " must stay a public method of RetentionSweep",
                    absent);
        }
        return java.util.Arrays.stream(method.getAnnotations())
                .map(annotation -> annotation.annotationType().getSimpleName())
                .toList();
    }

    /**
     * Runs each callback straight through and counts the transactions that were opened.
     *
     * <p>Counting is the point. A sweep that stopped opening transactions would still pass every
     * assertion about which store it reached, and would delete nothing at all in production.
     */
    private static final class CountingTransactionTemplate extends TransactionTemplate {

        /** Declared because the framework superclass is serializable; no instance is serialized. */
        private static final long serialVersionUID = 1L;

        private int opened;

        @Override
        public <T> T execute(TransactionCallback<T> action) {
            opened++;
            return action.doInTransaction(new SimpleTransactionStatus(true));
        }

        int opened() {
            return opened;
        }
    }
}
