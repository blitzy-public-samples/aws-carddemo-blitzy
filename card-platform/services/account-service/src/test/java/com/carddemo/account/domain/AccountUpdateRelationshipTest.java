package com.carddemo.account.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.carddemo.account.config.AccountProperties;
import com.carddemo.account.config.ObservabilityConfig;
import com.carddemo.account.domain.validation.EditResult;
import com.carddemo.account.entity.AccountEntity;
import com.carddemo.account.entity.CardCrossReferenceEntity;
import com.carddemo.account.entity.CustomerEntity;
import com.carddemo.account.outbox.OutboxWriter;
import com.carddemo.account.repository.AccountRepository;
import com.carddemo.account.repository.CardCrossReferenceRepository;
import com.carddemo.account.repository.CustomerRepository;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Verifies that the account update path derives its customer from {@code card_xref}.
 *
 * <p>The tests use the no-change branch so no unrelated field edit obscures the authority check.
 * A malicious pairing must be refused even when the submitted old and new copies are identical.
 */
@DisplayName("AccountUpdateService account-to-customer authority")
class AccountUpdateRelationshipTest {

    private static final String ACCOUNT_ID = "00000000001";
    private static final String CUSTOMER_ID = "000000001";
    private static final String OTHER_CUSTOMER_ID = "000000002";

    private AccountRepository accounts;
    private CustomerRepository customers;
    private CardCrossReferenceRepository crossReferences;
    private OutboxWriter outboxWriter;
    private AccountUpdateService service;

    @BeforeEach
    void setUp() {
        accounts = mock(AccountRepository.class);
        customers = mock(CustomerRepository.class);
        crossReferences = mock(CardCrossReferenceRepository.class);
        outboxWriter = mock(OutboxWriter.class);
        service = new AccountUpdateService(accounts, customers, crossReferences,
                mock(ConcurrentChangeDetector.class), outboxWriter, immediateTransactions(), accountMeters(),
                accountProperties());
    }

    @Test
    @DisplayName("a caller cannot pair an account with another stored customer")
    void aCallerCannotPairAnAccountWithAnotherStoredCustomer() {
        AccountEntity account = account(ACCOUNT_ID);
        CustomerEntity suppliedCustomer = customer(OTHER_CUSTOMER_ID);
        CardCrossReferenceEntity storedRelationship = relationship(CUSTOMER_ID);
        when(crossReferences.findFirstByAccountIdOrderByCardNumberAsc(ACCOUNT_ID))
                .thenReturn(Optional.of(storedRelationship));

        EditResult result = service.updateAccount(
                account, suppliedCustomer, account, suppliedCustomer);

        assertThat(result.valid()).isFalse();
        assertThat(result.message())
                .isEqualTo(AccountUpdateService.ACCOUNT_CUSTOMER_RELATIONSHIP_NOT_FOUND);
        verify(customers, never()).findForUpdateByCustomerId(OTHER_CUSTOMER_ID);
        verify(outboxWriter, never()).write(org.mockito.ArgumentMatchers.any());
    }

    @Test
    @DisplayName("a stored account-to-customer relationship preserves the no-change result")
    void aStoredRelationshipPreservesTheNoChangeResult() {
        AccountEntity account = account(ACCOUNT_ID);
        CustomerEntity customer = customer(CUSTOMER_ID);
        CardCrossReferenceEntity storedRelationship = relationship(CUSTOMER_ID);
        when(crossReferences.findFirstByAccountIdOrderByCardNumberAsc(ACCOUNT_ID))
                .thenReturn(Optional.of(storedRelationship));

        EditResult result = service.updateAccount(account, customer, account, customer);

        assertThat(result.valid()).isTrue();
        assertThat(result.message()).isEqualTo(AccountUpdateService.NO_CHANGE_DETECTED);
        verify(accounts, never()).findForUpdateByAccountId(ACCOUNT_ID);
        verify(customers, never()).findForUpdateByCustomerId(CUSTOMER_ID);
        verify(outboxWriter, never()).write(org.mockito.ArgumentMatchers.any());
    }

    @Test
    @DisplayName("a missing relationship is refused without probing a caller-selected customer")
    void aMissingRelationshipIsRefusedWithoutProbingACallerSelectedCustomer() {
        AccountEntity account = account(ACCOUNT_ID);
        CustomerEntity customer = customer(CUSTOMER_ID);
        when(crossReferences.findFirstByAccountIdOrderByCardNumberAsc(ACCOUNT_ID))
                .thenReturn(Optional.empty());

        EditResult result = service.updateAccount(account, customer, account, customer);

        assertThat(result.valid()).isFalse();
        assertThat(result.message())
                .isEqualTo(AccountUpdateService.ACCOUNT_CUSTOMER_RELATIONSHIP_NOT_FOUND);
        verify(customers, never()).findForUpdateByCustomerId(CUSTOMER_ID);
    }

    private static AccountEntity account(String accountId) {
        AccountEntity account = mock(AccountEntity.class);
        when(account.getAccountId()).thenReturn(accountId);
        return account;
    }

    private static CustomerEntity customer(String customerId) {
        CustomerEntity customer = mock(CustomerEntity.class);
        when(customer.getCustomerId()).thenReturn(customerId);
        return customer;
    }

    private static CardCrossReferenceEntity relationship(String customerId) {
        CardCrossReferenceEntity relationship = mock(CardCrossReferenceEntity.class);
        when(relationship.getCustomerId()).thenReturn(customerId);
        return relationship;
    }

    /** Runs a transaction callback directly, so no transaction manager takes part. */
    private static TransactionTemplate immediateTransactions() {
        return new TransactionTemplate() {

            private static final long serialVersionUID = 1L;

            @Override
            public <T> T execute(TransactionCallback<T> action) {
                return action.doInTransaction(new SimpleTransactionStatus());
            }
        };
    }

    /** The shared meter holder this service records through. */
    private static ObservabilityConfig.AccountMeters accountMeters() {
        return new ObservabilityConfig().accountMeters(new SimpleMeterRegistry());
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
                        "carddemo.dead-letter")),
                new AccountProperties.Outbox(new AccountProperties.Outbox.Relay(500L, 100,
                        "account-relay", java.time.Duration.ofMinutes(2L), 1_000L,
                        java.time.Duration.ofSeconds(10L)), 168L),
                new AccountProperties.ProcessedEvent(168L),
                new AccountProperties.Retention(3_600_000L),
                new AccountProperties.Write(3_000L));
    }
}
