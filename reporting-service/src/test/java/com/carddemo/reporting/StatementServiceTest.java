/*
 * Copyright Amazon.com, Inc. or its affiliates.
 * All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License").
 * You may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND,
 * either express or implied. See the License for the specific
 * language governing permissions and limitations under the License.
 */
package com.carddemo.reporting;

import com.carddemo.common.domain.Account;
import com.carddemo.common.domain.CardXref;
import com.carddemo.common.domain.Customer;
import com.carddemo.common.domain.Transaction;
import com.carddemo.common.exception.RecordNotFoundException;
import com.carddemo.reporting.mapper.StatementMapper;
import com.carddemo.reporting.repository.AccountRepository;
import com.carddemo.reporting.repository.CardXrefRepository;
import com.carddemo.reporting.repository.CustomerRepository;
import com.carddemo.reporting.repository.TransactionRepository;
import com.carddemo.reporting.service.StatementService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Sort;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * :purpose: Pure Mockito JUnit 5 unit test for {@link StatementService} that pins the
 *   fidelity-critical orchestration contract re-platformed from the legacy batch statement
 *   engine ``CBSTM03A`` (I/O subroutine ``CBSTM03B``, layout ``COSTM01``). It asserts the
 *   FROZEN referential lookup order ``cross-reference -> customer -> account -> transactions``
 *   that reproduces the COBOL ``1000-MAINLINE`` read sequence (satisfying the 100%
 *   referential-integrity mandate), the byte-exact {@link RecordNotFoundException} messages and
 *   downstream short-circuit on each missing link, the FROZEN mapper parameter order
 *   ``(account, customer, cardXref, transactions)``, empty-transaction tolerance, and the
 *   ascending card-number sort plus not-found propagation of ``generateAllStatements()``.
 * :output: JUnit 5 assertions executed under Surefire with Mockito mocks only; no Spring
 *   application context, no container-based integration harness and no database are involved.
 *   All collaborators
 *   ({@link CardXrefRepository}, {@link CustomerRepository}, {@link AccountRepository},
 *   {@link TransactionRepository}, {@link StatementMapper}) are mocked and the service under
 *   test is wired by constructor injection.
 */
@ExtendWith(MockitoExtension.class)
class StatementServiceTest {

    /** Obviously-synthetic 16-character card number used in place of any real PAN. */
    private static final String SYNTHETIC_CARD = "0000000000000000";

    /** Second obviously-synthetic 16-character card number for the multi-card sweep. */
    private static final String SECOND_CARD = "0000000000000001";

    /** Synthetic customer id; typed {@link Long} to match {@code CustomerRepository<Customer, Long>}. */
    private static final Long CUST_ID = 1L;

    /** Synthetic account id; typed {@link Long} to match {@code AccountRepository<Account, Long>}. */
    private static final Long ACCT_ID = 2L;

    @Mock
    private CardXrefRepository cardXrefRepository;

    @Mock
    private CustomerRepository customerRepository;

    @Mock
    private AccountRepository accountRepository;

    @Mock
    private TransactionRepository transactionRepository;

    @Mock
    private StatementMapper statementMapper;

    @InjectMocks
    private StatementService statementService;

    /**
     * :purpose: Build a card cross-reference fixture via the real all-args constructor,
     *   binding the card number and the {@link Long} customer/account ids.
     * :param cardNumber: the 16-character card number key.
     * :param custId: the owning customer id.
     * :param acctId: the owning account id.
     * :returns: a populated {@link CardXref} instance.
     */
    private static CardXref cardXref(String cardNumber, Long custId, Long acctId) {
        return new CardXref(cardNumber, custId, acctId);
    }

    /**
     * :purpose: Build an empty {@link Customer} fixture; field content is irrelevant because the
     *   mapper is mocked and the service performs no field access on the customer.
     * :returns: a fresh {@link Customer} instance.
     */
    private static Customer customer() {
        return new Customer();
    }

    /**
     * :purpose: Build an empty {@link Account} fixture; field content is irrelevant because the
     *   mapper is mocked and the service performs no field access on the account.
     * :returns: a fresh {@link Account} instance.
     */
    private static Account account() {
        return new Account();
    }

    /**
     * :purpose: Build an empty {@link Transaction} fixture used to populate a card's
     *   transaction list.
     * :returns: a fresh {@link Transaction} instance.
     */
    private static Transaction transaction() {
        return new Transaction();
    }

    /**
     * :purpose: Verify the happy path returns the exact {@link StatementMapper.StatementModel}
     *   produced by the mapper, confirming the service delegates assembly and returns the
     *   mapper's result unchanged.
     */
    @Test
    void generateStatementReturnsMapperModelOnHappyPath() {
        CardXref cardXref = cardXref(SYNTHETIC_CARD, CUST_ID, ACCT_ID);
        Customer customer = customer();
        Account account = account();
        List<Transaction> transactions = List.of(transaction());
        StatementMapper.StatementModel sentinel = new StatementMapper.StatementModel();

        when(cardXrefRepository.findById(SYNTHETIC_CARD)).thenReturn(Optional.of(cardXref));
        when(customerRepository.findById(CUST_ID)).thenReturn(Optional.of(customer));
        when(accountRepository.findById(ACCT_ID)).thenReturn(Optional.of(account));
        when(transactionRepository.findByTranCardNumOrderByTranIdAsc(SYNTHETIC_CARD))
                .thenReturn(transactions);
        when(statementMapper.toStatement(account, customer, cardXref, transactions))
                .thenReturn(sentinel);

        StatementMapper.StatementModel result = statementService.generateStatement(SYNTHETIC_CARD);

        assertThat(result).isSameAs(sentinel);
    }

    /**
     * :purpose: Verify the FROZEN lookup ORDER inside {@code generateStatement}: the service must
     *   read the cross-reference, then the customer, then the account, then the transactions, and
     *   finally invoke the mapper. This is the core referential-integrity assertion reproducing
     *   the ``CBSTM03A`` ``1000-MAINLINE`` read sequence.
     */
    @Test
    void generateStatementFollowsFrozenLookupOrder() {
        CardXref cardXref = cardXref(SYNTHETIC_CARD, CUST_ID, ACCT_ID);
        Customer customer = customer();
        Account account = account();
        List<Transaction> transactions = List.of(transaction());

        when(cardXrefRepository.findById(SYNTHETIC_CARD)).thenReturn(Optional.of(cardXref));
        when(customerRepository.findById(CUST_ID)).thenReturn(Optional.of(customer));
        when(accountRepository.findById(ACCT_ID)).thenReturn(Optional.of(account));
        when(transactionRepository.findByTranCardNumOrderByTranIdAsc(SYNTHETIC_CARD))
                .thenReturn(transactions);
        when(statementMapper.toStatement(account, customer, cardXref, transactions))
                .thenReturn(new StatementMapper.StatementModel());

        statementService.generateStatement(SYNTHETIC_CARD);

        InOrder inOrder = inOrder(cardXrefRepository, customerRepository, accountRepository,
                transactionRepository, statementMapper);
        inOrder.verify(cardXrefRepository).findById(SYNTHETIC_CARD);
        inOrder.verify(customerRepository).findById(CUST_ID);
        inOrder.verify(accountRepository).findById(ACCT_ID);
        inOrder.verify(transactionRepository).findByTranCardNumOrderByTranIdAsc(SYNTHETIC_CARD);
        inOrder.verify(statementMapper).toStatement(account, customer, cardXref, transactions);
        inOrder.verifyNoMoreInteractions();
    }

    /**
     * :purpose: Verify the mapper is invoked with the FROZEN positional parameter order
     *   ``(account, customer, cardXref, transactions)`` by capturing each entity argument and
     *   asserting it is the same instance placed in the correct slot.
     */
    @Test
    void generateStatementInvokesMapperWithFrozenParamOrder() {
        CardXref cardXref = cardXref(SYNTHETIC_CARD, CUST_ID, ACCT_ID);
        Customer customer = customer();
        Account account = account();
        List<Transaction> transactions = List.of(transaction());

        when(cardXrefRepository.findById(SYNTHETIC_CARD)).thenReturn(Optional.of(cardXref));
        when(customerRepository.findById(CUST_ID)).thenReturn(Optional.of(customer));
        when(accountRepository.findById(ACCT_ID)).thenReturn(Optional.of(account));
        when(transactionRepository.findByTranCardNumOrderByTranIdAsc(SYNTHETIC_CARD))
                .thenReturn(transactions);
        when(statementMapper.toStatement(any(), any(), any(), any()))
                .thenReturn(new StatementMapper.StatementModel());

        statementService.generateStatement(SYNTHETIC_CARD);

        ArgumentCaptor<Account> accountCaptor = ArgumentCaptor.forClass(Account.class);
        ArgumentCaptor<Customer> customerCaptor = ArgumentCaptor.forClass(Customer.class);
        ArgumentCaptor<CardXref> cardXrefCaptor = ArgumentCaptor.forClass(CardXref.class);
        verify(statementMapper).toStatement(accountCaptor.capture(), customerCaptor.capture(),
                cardXrefCaptor.capture(), eq(transactions));
        assertThat(accountCaptor.getValue()).isSameAs(account);
        assertThat(customerCaptor.getValue()).isSameAs(customer);
        assertThat(cardXrefCaptor.getValue()).isSameAs(cardXref);
    }

    /**
     * :purpose: Verify a missing card cross-reference raises {@link RecordNotFoundException} with
     *   the byte-exact message and short-circuits: no customer, account, transaction or mapper
     *   interaction occurs.
     */
    @Test
    void generateStatementThrowsWhenCrossReferenceMissing() {
        when(cardXrefRepository.findById(SYNTHETIC_CARD)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> statementService.generateStatement(SYNTHETIC_CARD))
                .isInstanceOf(RecordNotFoundException.class)
                .hasMessage("Card cross-reference not found for card: " + SYNTHETIC_CARD);

        verifyNoInteractions(customerRepository, accountRepository, transactionRepository,
                statementMapper);
    }

    /**
     * :purpose: Verify a missing customer raises {@link RecordNotFoundException} with the
     *   byte-exact message and short-circuits: the account, transaction and mapper collaborators
     *   are never invoked.
     */
    @Test
    void generateStatementThrowsWhenCustomerMissing() {
        CardXref cardXref = cardXref(SYNTHETIC_CARD, CUST_ID, ACCT_ID);
        when(cardXrefRepository.findById(SYNTHETIC_CARD)).thenReturn(Optional.of(cardXref));
        when(customerRepository.findById(CUST_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> statementService.generateStatement(SYNTHETIC_CARD))
                .isInstanceOf(RecordNotFoundException.class)
                .hasMessage("Customer not found for id: " + CUST_ID);

        verifyNoInteractions(accountRepository, transactionRepository, statementMapper);
    }

    /**
     * :purpose: Verify a missing account raises {@link RecordNotFoundException} with the
     *   byte-exact message and short-circuits: the transaction and mapper collaborators are
     *   never invoked (the customer read has already succeeded).
     */
    @Test
    void generateStatementThrowsWhenAccountMissing() {
        CardXref cardXref = cardXref(SYNTHETIC_CARD, CUST_ID, ACCT_ID);
        when(cardXrefRepository.findById(SYNTHETIC_CARD)).thenReturn(Optional.of(cardXref));
        when(customerRepository.findById(CUST_ID)).thenReturn(Optional.of(customer()));
        when(accountRepository.findById(ACCT_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> statementService.generateStatement(SYNTHETIC_CARD))
                .isInstanceOf(RecordNotFoundException.class)
                .hasMessage("Account not found for id: " + ACCT_ID);

        verifyNoInteractions(transactionRepository, statementMapper);
    }

    /**
     * :purpose: Verify an empty transaction list is NOT an error: with the cross-reference,
     *   customer and account all present, the service still returns the mapper's model and
     *   invokes the mapper exactly once with the empty transaction list.
     */
    @Test
    void generateStatementTreatsEmptyTransactionListAsSuccess() {
        CardXref cardXref = cardXref(SYNTHETIC_CARD, CUST_ID, ACCT_ID);
        Customer customer = customer();
        Account account = account();
        List<Transaction> noTransactions = List.of();
        StatementMapper.StatementModel sentinel = new StatementMapper.StatementModel();

        when(cardXrefRepository.findById(SYNTHETIC_CARD)).thenReturn(Optional.of(cardXref));
        when(customerRepository.findById(CUST_ID)).thenReturn(Optional.of(customer));
        when(accountRepository.findById(ACCT_ID)).thenReturn(Optional.of(account));
        when(transactionRepository.findByTranCardNumOrderByTranIdAsc(SYNTHETIC_CARD))
                .thenReturn(noTransactions);
        when(statementMapper.toStatement(account, customer, cardXref, noTransactions))
                .thenReturn(sentinel);

        StatementMapper.StatementModel result = statementService.generateStatement(SYNTHETIC_CARD);

        assertThat(result).isSameAs(sentinel);
        verify(statementMapper).toStatement(eq(account), eq(customer), eq(cardXref),
                eq(noTransactions));
    }

    /**
     * :purpose: Verify {@code generateAllStatements} reads every cross-reference in ascending
     *   card-number order and produces one statement model per card. It captures the {@link Sort}
     *   passed to {@code findAll} and asserts it equals ``Sort.by(Sort.Direction.ASC,
     *   "xrefCardNum")``, and asserts the returned list has one model per cross-reference.
     */
    @Test
    void generateAllStatementsSortsAscendingAndMapsEachCard() {
        CardXref firstXref = cardXref(SYNTHETIC_CARD, CUST_ID, ACCT_ID);
        CardXref secondXref = cardXref(SECOND_CARD, 3L, 4L);
        StatementMapper.StatementModel model = new StatementMapper.StatementModel();

        when(cardXrefRepository.findAll(any(Sort.class)))
                .thenReturn(List.of(firstXref, secondXref));
        when(customerRepository.findById(any())).thenReturn(Optional.of(customer()));
        when(accountRepository.findById(any())).thenReturn(Optional.of(account()));
        when(transactionRepository.findByTranCardNumOrderByTranIdAsc(any()))
                .thenReturn(List.of());
        when(statementMapper.toStatement(any(), any(), any(), any())).thenReturn(model);

        List<StatementMapper.StatementModel> result = statementService.generateAllStatements();

        assertThat(result).hasSize(2);

        ArgumentCaptor<Sort> sortCaptor = ArgumentCaptor.forClass(Sort.class);
        verify(cardXrefRepository).findAll(sortCaptor.capture());
        assertThat(sortCaptor.getValue())
                .isEqualTo(Sort.by(Sort.Direction.ASC, "xrefCardNum"));
    }

    /**
     * :purpose: Verify {@code generateAllStatements} propagates (never swallows) a
     *   {@link RecordNotFoundException} raised while resolving a referenced record: a single
     *   cross-reference whose customer is missing causes the whole sweep to fail with the
     *   byte-exact customer-not-found message.
     */
    @Test
    void generateAllStatementsPropagatesNotFoundFailure() {
        CardXref firstXref = cardXref(SYNTHETIC_CARD, CUST_ID, ACCT_ID);

        when(cardXrefRepository.findAll(any(Sort.class))).thenReturn(List.of(firstXref));
        when(customerRepository.findById(CUST_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> statementService.generateAllStatements())
                .isInstanceOf(RecordNotFoundException.class)
                .hasMessage("Customer not found for id: " + CUST_ID);
    }
}
