package com.carddemo.account.domain;

import com.carddemo.account.config.ObservabilityConfig.AccountMeters;
import com.carddemo.account.config.ObservabilityConfig;
import com.carddemo.account.domain.validation.EditResult;
import com.carddemo.account.entity.AccountEntity;
import com.carddemo.account.entity.CustomerEntity;
import com.carddemo.account.outbox.OutboxWriter;
import com.carddemo.account.repository.AccountRepository;
import com.carddemo.account.repository.CardCrossReferenceRepository;
import com.carddemo.account.repository.CustomerRepository;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

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
                mock(CardCrossReferenceRepository.class),
                changeDetector,
                outboxWriter,
                transactions,
                meters);
    }
}