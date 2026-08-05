package com.carddemo.notification.domain;

import com.carddemo.cobol.PicClause;
import com.carddemo.notification.config.NotificationProperties;
import com.carddemo.notification.repository.NotificationLogRepository;
import com.carddemo.notification.repository.ProcessedEventRepository;
import com.carddemo.notification.repository.StatementTransactionRepository;

import java.lang.reflect.Method;
import java.time.Duration;
import java.time.Instant;
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
 * Asserts {@link RetentionSweep} enforces the three horizons {@code application.yml} declares, and
 * that it enforces them through an explicit transaction rather than through an annotation a
 * self-invocation would bypass.
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
@DisplayName("RetentionSweep, the three retention horizons of the notification service")
class RetentionSweepTest {

    /** Marker horizon the shipped configuration names, in hours. */
    private static final int MARKER_RETENTION_HOURS = 168;

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
    private final ProcessedEventRepository processedEvents =
            Mockito.mock(ProcessedEventRepository.class);

    private final CountingTransactionTemplate transactions = new CountingTransactionTemplate();

    private final RetentionSweep sweep = new RetentionSweep(statementTransactions, notificationLog,
            processedEvents, shippedProperties(), transactions);

    @Nested
    @DisplayName("One pass over the three tables")
    class OnePass {

        @Test
        @DisplayName("reaches all three stores, each in a transaction of its own")
        void reachesAllThreeStoresEachInItsOwnTransaction() {
            when(processedEvents.deleteMarkersProcessedBefore(Mockito.any(), Mockito.anyInt()))
                    .thenReturn(4);
            when(statementTransactions.deleteProcessedBefore(Mockito.anyString(), Mockito.anyInt()))
                    .thenReturn(11);
            when(notificationLog.deleteAttemptsBefore(Mockito.any())).thenReturn(2);

            sweep.sweepExpiredRows();

            verify(processedEvents).deleteMarkersProcessedBefore(Mockito.any(), Mockito.anyInt());
            verify(statementTransactions).deleteProcessedBefore(Mockito.anyString(), Mockito.anyInt());
            verify(notificationLog).deleteAttemptsBefore(Mockito.any());
            assertThat(transactions.opened())
                    .as("transactions opened, one per delete")
                    .isEqualTo(3);
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

            assertThat(transactions.opened()).isEqualTo(3);
            assertThat(annotationNamesOn("sweepMarkers"))
                    .as("annotations on sweepMarkers")
                    .doesNotContain("Transactional");
            assertThat(annotationNamesOn("sweepReadModelRows"))
                    .as("annotations on sweepReadModelRows")
                    .doesNotContain("Transactional");
            assertThat(annotationNamesOn("sweepAttemptRows"))
                    .as("annotations on sweepAttemptRows")
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
        @DisplayName("measures the marker horizon back by the configured hours")
        void measuresTheMarkerHorizonBackByTheConfiguredHours() {
            ArgumentCaptor<Instant> horizon = ArgumentCaptor.forClass(Instant.class);

            sweep.sweepMarkers();

            verify(processedEvents).deleteMarkersProcessedBefore(horizon.capture(), Mockito.anyInt());
            assertThat(horizon.getValue())
                    .as("the marker horizon, %d hours back", MARKER_RETENTION_HOURS)
                    .isCloseTo(Instant.now().minus(Duration.ofHours(MARKER_RETENTION_HOURS)),
                            within(CLOCK_TOLERANCE.toMillis(), ChronoUnit.MILLIS));
        }

        @Test
        @DisplayName("measures the attempt horizon back by the configured days")
        void measuresTheAttemptHorizonBackByTheConfiguredDays() {
            ArgumentCaptor<Instant> horizon = ArgumentCaptor.forClass(Instant.class);

            sweep.sweepAttemptRows();

            verify(notificationLog).deleteAttemptsBefore(horizon.capture());
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

            sweep.sweepReadModelRows();

            verify(statementTransactions).deleteProcessedBefore(horizon.capture(), Mockito.anyInt());
            String stamped = horizon.getValue();
            assertThat(stamped)
                    .as("the rendered read-model horizon")
                    .hasSize(PicClause.PROCESSING_TIMESTAMP_WIDTH);
            assertThat(stamped)
                    .as("the rendered read-model horizon, as the column shape")
                    .matches("[0-9]{4}-[0-9]{2}-[0-9]{2}-[0-9]{2}\\.[0-9]{2}\\.[0-9]{2}"
                            + "\\.[0-9]{2}0000");
            assertThat(stamped.substring(0, 4))
                    .as("the year the horizon falls in, %d days back", STATEMENT_RETENTION_DAYS)
                    .isEqualTo(String.valueOf(Instant.now()
                            .minus(Duration.ofDays(STATEMENT_RETENTION_DAYS))
                            .atZone(java.time.ZoneOffset.UTC).getYear()));
        }
    }

    @Nested
    @DisplayName("A failure on one table")
    class OneTableFailing {

        @Test
        @DisplayName("leaves the other two swept and never reaches the caller")
        void leavesTheOtherTwoSweptAndNeverReachesTheCaller() {
            when(processedEvents.deleteMarkersProcessedBefore(Mockito.any(), Mockito.anyInt()))
                    .thenThrow(new InvalidDataAccessApiUsageException("no permission"));

            assertThatCode(sweep::sweepExpiredRows)
                    .as("a sweep pass with one table failing")
                    .doesNotThrowAnyException();

            verify(statementTransactions).deleteProcessedBefore(Mockito.anyString(), Mockito.anyInt());
            verify(notificationLog).deleteAttemptsBefore(Mockito.any());
        }

        /**
         * A fault that is not a data-access fault is not an operational condition this sweep can
         * report and carry on from, so it leaves the method and the scheduler logs it.
         */
        @Test
        @DisplayName("lets a fault that is not a data-access fault leave the method")
        void letsAFaultThatIsNotADataAccessFaultLeaveTheMethod() {
            when(notificationLog.deleteAttemptsBefore(Mockito.any()))
                    .thenThrow(new IllegalStateException("the entity manager is closed"));

            assertThatThrownBy(sweep::sweepAttemptRows)
                    .isInstanceOf(IllegalStateException.class);
        }
    }

    @Nested
    @DisplayName("The stores this sweep reaches")
    class StoresReached {

        @Test
        @DisplayName("are the three that grow by one row per consumed event, and no other")
        void areTheThreeThatGrowPerConsumedEventAndNoOther() {
            sweep.sweepExpiredRows();

            verify(processedEvents).deleteMarkersProcessedBefore(Mockito.any(), Mockito.anyInt());
            verify(statementTransactions).deleteProcessedBefore(Mockito.anyString(), Mockito.anyInt());
            verify(notificationLog).deleteAttemptsBefore(Mockito.any());
            verifyNoMoreInteractions(processedEvents, statementTransactions, notificationLog);
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
                new NotificationProperties.ProcessedEvent(MARKER_RETENTION_HOURS),
                new NotificationProperties.History(STATEMENT_RETENTION_DAYS, LOG_RETENTION_DAYS,
                        3_600_000L, 50, 200));
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
