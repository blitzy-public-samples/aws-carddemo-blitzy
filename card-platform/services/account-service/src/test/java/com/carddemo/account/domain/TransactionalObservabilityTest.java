package com.carddemo.account.domain;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.carddemo.account.config.AccountProperties;
import com.carddemo.account.config.ObservabilityConfig.AccountMeters;
import com.carddemo.account.domain.validation.EditResult;
import com.carddemo.account.entity.AccountEntity;
import com.carddemo.account.entity.CustomerEntity;
import com.carddemo.account.outbox.OutboxWriter;
import com.carddemo.account.repository.AccountRepository;
import com.carddemo.account.repository.AccountCustomerLinkRepository;
import com.carddemo.account.repository.CustomerRepository;
import java.time.Duration;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.transaction.support.TransactionTemplate;

/** Verifies account success meters are recorded after commit and rollback meters separately. */
class TransactionalObservabilityTest {

    private final AccountRepository accounts = mock(AccountRepository.class);
    private final CustomerRepository customers = mock(CustomerRepository.class);
    private final ConcurrentChangeDetector changeDetector = mock(ConcurrentChangeDetector.class);
    private final OutboxWriter outboxWriter = mock(OutboxWriter.class);
    private final TransactionTemplate transactions = mock(TransactionTemplate.class);
    private final AccountMeters meters = mock(AccountMeters.class);

    @Test
    void anAppliedUpdateRecordsSuccessOnlyAfterTheTransactionReturns() {
        AccountUpdateService.TransactionResult committed =
                new AccountUpdateService.TransactionResult(EditResult.ok(), true, false);
        when(transactions.execute(any())).thenReturn(committed);
        AccountUpdateService service = updateService();

        service.updateAccount(
                mock(AccountEntity.class),
                mock(CustomerEntity.class),
                null,
                null);

        InOrder order = inOrder(transactions, meters);
        order.verify(transactions).execute(any());
        order.verify(meters).recordUpdateLatency(any(Duration.class));
        order.verify(meters).recordUpdateApplied();
        verify(meters, never()).recordUpdateFailure();
        verify(meters, never()).recordValidationFailure();
    }

    @Test
    void aValidationOutcomeRecordsAfterTheTransactionReturns() {
        EditResult rejected = EditResult.failure("invalid");
        when(transactions.execute(any())).thenReturn(
                new AccountUpdateService.TransactionResult(rejected, false, true));

        updateService().updateAccount(
                mock(AccountEntity.class),
                mock(CustomerEntity.class),
                null,
                null);

        InOrder order = inOrder(transactions, meters);
        order.verify(transactions).execute(any());
        order.verify(meters).recordUpdateLatency(any(Duration.class));
        order.verify(meters).recordValidationFailure();
        verify(meters, never()).recordUpdateApplied();
        verify(meters, never()).recordUpdateFailure();
    }

    @Test
    void anUpdateRollbackRecordsOnlyTheFailureSeries() {
        when(transactions.execute(any())).thenThrow(new IllegalStateException("commit failed"));

        assertThatThrownBy(() -> updateService().updateAccount(
                mock(AccountEntity.class),
                mock(CustomerEntity.class),
                null,
                null))
                .isInstanceOf(IllegalStateException.class);

        verify(meters).recordUpdateFailure();
        verify(meters, never()).recordUpdateApplied();
        verify(meters, never()).recordUpdateLatency(any());
    }

    /**
     * A locked read the datastore refused answers the source text, and counts as neither a failure
     * nor a validation refusal.
     *
     * <p>{@code writeProcessing} reports a lock it could not take two ways, because
     * {@code app/cbl/COACTUPC.cbl:L3907} draws no distinction between them. A row that has gone
     * returns empty and the verdict travels back inside the transaction. A datastore that refuses the
     * locking statement raises instead, and that leaves a PostgreSQL transaction unusable, so the
     * verdict cannot travel back across the boundary and is carried out through it.
     *
     * <p>The two arms are counted alike: the latency and nothing else. Counting the raising arm as an
     * update failure would make an operator read a contended write as a defect of this service, and
     * counting it as a validation refusal would blame the caller for a row someone else held.
     */
    @Test
    void aRefusedLockAnswersItsSourceTextAndCountsAsNeitherFailureNorRefusal() {
        when(transactions.execute(any())).thenThrow(new AccountUpdateService.LockNotTaken(
                AccountUpdateService.COULD_NOT_LOCK_ACCOUNT,
                new CannotAcquireLockException("canceling statement due to lock timeout")));
        AccountUpdateService service = updateService();

        EditResult verdict = service.updateAccount(
                mock(AccountEntity.class),
                mock(CustomerEntity.class),
                null,
                null);

        assertFalse(verdict.valid(), "a lock not taken is a failing verdict");
        assertEquals(AccountUpdateService.COULD_NOT_LOCK_ACCOUNT, verdict.message(),
                "the text app/cbl/COACTUPC.cbl:L517-L518 declares");
        verify(meters).recordUpdateLatency(any(Duration.class));
        verify(meters, never()).recordUpdateFailure();
        verify(meters, never()).recordValidationFailure();
        verify(meters, never()).recordUpdateApplied();
    }

    @Test
    void aCycleCloseRecordsSuccessOnlyAfterTheTransactionReturns() {
        AccountEntity account = mock(AccountEntity.class);
        when(transactions.execute(any())).thenReturn(Optional.of(account));

        BillingCycleService service =
                new BillingCycleService(accounts, outboxWriter, transactions, meters);
        service.closeBillingCycle("00000000077");

        InOrder order = inOrder(transactions, meters);
        order.verify(transactions).execute(any());
        order.verify(meters).recordCycleClosed();
        verify(meters, never()).recordCycleCloseFailure();
    }

    @Test
    void aCycleCloseRollbackRecordsOnlyTheFailureSeries() {
        when(transactions.execute(any())).thenThrow(new IllegalStateException("commit failed"));
        BillingCycleService service =
                new BillingCycleService(accounts, outboxWriter, transactions, meters);

        assertThatThrownBy(() -> service.closeBillingCycle("00000000077"))
                .isInstanceOf(IllegalStateException.class);

        verify(meters).recordCycleCloseFailure();
        verify(meters, never()).recordCycleClosed();
    }

    private AccountUpdateService updateService() {
        return new AccountUpdateService(
                accounts,
                customers,
                mock(AccountCustomerLinkRepository.class),
                changeDetector,
                outboxWriter,
                transactions,
                meters,
                accountProperties());
    }

    /**
     * Builds the bound configuration this service reads, with the shipped lock-wait bound.
     *
     * <p>{@code AccountUpdateService} reads one value from it, {@code carddemo.write.lock-wait-ms},
     * which it renders once into the PostgreSQL interval string its locked reads are bounded by. The
     * value below is the one {@code src/main/resources/application.yml} ships.
     *
     * @return the configuration record
     */
    private static AccountProperties accountProperties() {
        return new AccountProperties(
                new AccountProperties.Api(65_536L),
                new AccountProperties.Kafka(new AccountProperties.Kafka.Topics(
                        "account.state-changed", "customer.context-changed",
                        "transaction.posted", "carddemo.dead-letter"),
                        new AccountProperties.Kafka.Groups("account-posted")),
                new AccountProperties.Consumer(new AccountProperties.Consumer.Retry(3, 1_000L)),
                new AccountProperties.Outbox(new AccountProperties.Outbox.Relay(500L, 100,
                        "account-relay", java.time.Duration.ofMinutes(2L), 1_000L,
                        java.time.Duration.ofSeconds(10L)), 168L),
                new AccountProperties.ProcessedEvent(720L, 168L),
                new AccountProperties.Retention(3_600_000L),
                new AccountProperties.Write(3_000L));
    }
}