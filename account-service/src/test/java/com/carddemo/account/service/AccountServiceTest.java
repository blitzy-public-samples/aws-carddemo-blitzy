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
package com.carddemo.account.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.carddemo.account.mapper.AccountMapper;
import com.carddemo.account.repository.AccountRepository;
import com.carddemo.account.repository.CardXrefRepository;
import com.carddemo.account.repository.CustomerRepository;
import com.carddemo.common.domain.Account;
import com.carddemo.common.domain.CardXref;
import com.carddemo.common.domain.Customer;
import com.carddemo.common.dto.AccountUpdateRequestDto;
import com.carddemo.common.dto.AccountUpdateResponseDto;
import com.carddemo.common.dto.AccountViewResponseDto;
import com.carddemo.common.exception.OptimisticLockConflictException;
import com.carddemo.common.exception.RecordNotFoundException;

import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.orm.ObjectOptimisticLockingFailureException;

/**
 * :purpose: Pure Mockito unit tests for {@link AccountService}, verifying the
 *  migrated account business logic in isolation from Spring, JPA, and any
 *  database. Two legacy CICS programs are covered:
 *
 *  - ``COACTVWC`` (transaction ``CAVW``, account view): the strict, ordered,
 *    short-circuit-on-first-miss read of the card cross-reference, the account
 *    master, and the customer master, and the derivation of the customer id from
 *    the cross-reference.
 *  - ``COACTUPC`` (transaction ``CAUP``, account update): the single atomic
 *    customer-then-account persist and the JPA ``@Version`` optimistic-lock
 *    conflict surfaced as {@link OptimisticLockConflictException}.
 *
 *  All four collaborators are Mockito mocks; DTO fixtures are mocked and asserted
 *  by identity and interaction only (never by field) so the test is resilient to
 *  DTO shape changes. Sensitive customer data is never referenced.
 */
@ExtendWith(MockitoExtension.class)
class AccountServiceTest {

    /** :purpose: Card cross-reference repository mock (CXACAIX; COACTVWC 9200). */
    @Mock
    private CardXrefRepository cardXrefRepository;

    /** :purpose: Account master repository mock (ACCTFILE; COACTVWC 9300). */
    @Mock
    private AccountRepository accountRepository;

    /** :purpose: Customer master repository mock (CUSTFILE; COACTVWC 9400). */
    @Mock
    private CustomerRepository customerRepository;

    /** :purpose: Entity-to-DTO mapper mock assembling view/update responses and applying edits. */
    @Mock
    private AccountMapper accountMapper;

    /** :purpose: Service under test with the four mocks injected via its single constructor. */
    @InjectMocks
    private AccountService accountService;

    // ---------------------------------------------------------------------
    // Verbatim COBOL message constants (byte-identical to the 88-level VALUE
    // clauses). Reproduced character-for-character as frozen contracts.
    // ---------------------------------------------------------------------

    /** :purpose: COACTVWC ``DID-NOT-FIND-ACCT-IN-CARDXREF`` (L130). */
    private static final String MSG_ACCT_NOT_IN_XREF =
            "Did not find this account in account card xref file";

    /** :purpose: COACTVWC ``DID-NOT-FIND-ACCT-IN-ACCTDAT`` (L132). */
    private static final String MSG_ACCT_NOT_IN_MASTER =
            "Did not find this account in account master file";

    /** :purpose: COACTVWC ``DID-NOT-FIND-CUST-IN-CUSTDAT`` (L134). */
    private static final String MSG_CUST_NOT_IN_MASTER =
            "Did not find associated customer in master file";

    /** :purpose: COACTUPC ``DATA-WAS-CHANGED-BEFORE-UPDATE`` (L522). */
    private static final String MSG_DATA_WAS_CHANGED =
            "Record changed by some one else. Please review";

    /** :purpose: Sample 11-digit account identifier (COBOL ``PIC 9(11)``). */
    private static final Long ACCT_ID = 11111111111L;

    /** :purpose: Sample 9-digit customer identifier (COBOL ``PIC 9(09)``). */
    private static final Long CUST_ID = 999999999L;

    /** :purpose: Sample 16-character card number (COBOL ``PIC X(16)``); non-sensitive fixture only. */
    private static final String CARD_NUM = "1234567890123456";

    /**
     * :purpose: Build a real cross-reference whose ``getXrefCustId()`` yields the
     *  known customer id with no stubbing, so the derived-customer-id assertions
     *  are strict-stub-safe.
     * :returns: a populated {@link CardXref} linking the sample card, customer, and account.
     */
    private static CardXref xref() {
        return new CardXref(CARD_NUM, CUST_ID, ACCT_ID);
    }

    // =====================================================================
    // VIEW scenarios (<- COACTVWC.cbl, transaction CAVW)
    // =====================================================================

    /**
     * :purpose: Verify the account-view happy path returns the mapper's response
     *  unchanged and reads the customer by the id derived from the cross-reference
     *  (COACTVWC ``MOVE XREF-CUST-ID TO CDEMO-CUST-ID``), never by a direct input.
     */
    @Test
    void viewAccount_happyPath_returnsMappedDtoAndDerivesCustIdFromXref() {
        CardXref cardXref = xref();
        Account account = mock(Account.class);
        Customer customer = mock(Customer.class);
        AccountViewResponseDto expected = mock(AccountViewResponseDto.class);

        when(cardXrefRepository.findByXrefAcctId(ACCT_ID)).thenReturn(Optional.of(cardXref));
        when(accountRepository.findById(ACCT_ID)).thenReturn(Optional.of(account));
        when(customerRepository.findById(CUST_ID)).thenReturn(Optional.of(customer));
        when(accountMapper.toViewResponse(account, customer, cardXref)).thenReturn(expected);

        AccountViewResponseDto result = accountService.viewAccount(ACCT_ID, null);

        assertThat(result).isSameAs(expected);
        verify(accountMapper).toViewResponse(account, customer, cardXref);

        ArgumentCaptor<Long> custIdCaptor = ArgumentCaptor.forClass(Long.class);
        verify(customerRepository).findById(custIdCaptor.capture());
        assertThat(custIdCaptor.getValue()).isEqualTo(CUST_ID);
    }

    /**
     * :purpose: Verify a cross-reference miss throws {@link RecordNotFoundException}
     *  with the verbatim COACTVWC message and short-circuits the read so the account
     *  master, customer master, and mapper are never reached.
     */
    @Test
    void viewAccount_xrefMiss_throwsRecordNotFound_andShortCircuits() {
        when(cardXrefRepository.findByXrefAcctId(ACCT_ID)).thenReturn(Optional.empty());

        assertThatExceptionOfType(RecordNotFoundException.class)
                .isThrownBy(() -> accountService.viewAccount(ACCT_ID, null))
                .withMessage(MSG_ACCT_NOT_IN_XREF);

        verifyNoInteractions(accountRepository, customerRepository, accountMapper);
    }

    /**
     * :purpose: Verify an account-master miss throws {@link RecordNotFoundException}
     *  with the verbatim COACTVWC message and skips the customer read and the mapper.
     */
    @Test
    void viewAccount_accountMiss_throwsRecordNotFound_andSkipsCustomer() {
        CardXref cardXref = xref();

        when(cardXrefRepository.findByXrefAcctId(ACCT_ID)).thenReturn(Optional.of(cardXref));
        when(accountRepository.findById(ACCT_ID)).thenReturn(Optional.empty());

        assertThatExceptionOfType(RecordNotFoundException.class)
                .isThrownBy(() -> accountService.viewAccount(ACCT_ID, null))
                .withMessage(MSG_ACCT_NOT_IN_MASTER);

        verifyNoInteractions(customerRepository, accountMapper);
    }

    /**
     * :purpose: Verify a customer-master miss throws {@link RecordNotFoundException}
     *  with the verbatim COACTVWC message and never invokes the response mapper.
     */
    @Test
    void viewAccount_customerMiss_throwsRecordNotFound() {
        CardXref cardXref = xref();
        Account account = mock(Account.class);

        when(cardXrefRepository.findByXrefAcctId(ACCT_ID)).thenReturn(Optional.of(cardXref));
        when(accountRepository.findById(ACCT_ID)).thenReturn(Optional.of(account));
        when(customerRepository.findById(CUST_ID)).thenReturn(Optional.empty());

        assertThatExceptionOfType(RecordNotFoundException.class)
                .isThrownBy(() -> accountService.viewAccount(ACCT_ID, null))
                .withMessage(MSG_CUST_NOT_IN_MASTER);

        verifyNoInteractions(accountMapper);
    }

    // =====================================================================
    // UPDATE scenarios (<- COACTUPC.cbl, transaction CAUP, single @Transactional)
    // =====================================================================

    /**
     * :purpose: Verify the account-update happy path delegates field mutation to the
     *  mapper, persists customer before account (AAP 0.6.2 deadlock-avoidance order),
     *  and returns the mapper's echo response unchanged.
     */
    @Test
    void updateAccount_happyPath_appliesUpdateThenSavesCustomerBeforeAccount() {
        CardXref cardXref = xref();
        Account account = mock(Account.class);
        Customer customer = mock(Customer.class);
        AccountUpdateRequestDto request = mock(AccountUpdateRequestDto.class);
        AccountUpdateResponseDto expected = mock(AccountUpdateResponseDto.class);

        when(cardXrefRepository.findByXrefAcctId(ACCT_ID)).thenReturn(Optional.of(cardXref));
        when(accountRepository.findById(ACCT_ID)).thenReturn(Optional.of(account));
        when(customerRepository.findById(CUST_ID)).thenReturn(Optional.of(customer));
        when(accountMapper.toUpdateResponse(account, customer, cardXref)).thenReturn(expected);

        AccountUpdateResponseDto result = accountService.updateAccount(ACCT_ID, request, null);

        verify(accountMapper).applyUpdate(request, account, customer);

        InOrder inOrder = inOrder(customerRepository, accountRepository);
        inOrder.verify(customerRepository).save(customer);
        inOrder.verify(accountRepository).save(account);

        assertThat(result).isSameAs(expected);
    }

    /**
     * :purpose: Verify a JPA optimistic-lock failure raised while persisting the
     *  account is translated into {@link OptimisticLockConflictException} carrying
     *  the verbatim COACTUPC conflict message and wrapping the original cause.
     */
    @Test
    void updateAccount_concurrentModification_throwsOptimisticLockConflict() {
        CardXref cardXref = xref();
        Account account = mock(Account.class);
        Customer customer = mock(Customer.class);
        AccountUpdateRequestDto request = mock(AccountUpdateRequestDto.class);

        when(cardXrefRepository.findByXrefAcctId(ACCT_ID)).thenReturn(Optional.of(cardXref));
        when(accountRepository.findById(ACCT_ID)).thenReturn(Optional.of(account));
        when(customerRepository.findById(CUST_ID)).thenReturn(Optional.of(customer));
        when(accountRepository.save(any(Account.class)))
                .thenThrow(new ObjectOptimisticLockingFailureException(Account.class, ACCT_ID));

        assertThatExceptionOfType(OptimisticLockConflictException.class)
                .isThrownBy(() -> accountService.updateAccount(ACCT_ID, request, null))
                .withMessage(MSG_DATA_WAS_CHANGED)
                .withCauseInstanceOf(ObjectOptimisticLockingFailureException.class);

        assertThat(OptimisticLockConflictException.MESSAGE).isEqualTo(MSG_DATA_WAS_CHANGED);
    }

    /**
     * :purpose: Verify the service delegates all field mutation to the mapper and
     *  never re-assigns the account id, customer id, or optimistic-lock version
     *  itself (COACTUPC ``9700-CHECK-CHANGE`` excludes id/version from the update).
     */
    @Test
    void updateAccount_neverMutatesIdOrVersion_delegatesToMapper() {
        CardXref cardXref = xref();
        Account account = mock(Account.class);
        Customer customer = mock(Customer.class);
        AccountUpdateRequestDto request = mock(AccountUpdateRequestDto.class);

        when(cardXrefRepository.findByXrefAcctId(ACCT_ID)).thenReturn(Optional.of(cardXref));
        when(accountRepository.findById(ACCT_ID)).thenReturn(Optional.of(account));
        when(customerRepository.findById(CUST_ID)).thenReturn(Optional.of(customer));

        accountService.updateAccount(ACCT_ID, request, null);

        verify(accountMapper).applyUpdate(request, account, customer);
        verify(account, never()).setAcctId(any());
        verify(customer, never()).setCustId(any());
        verify(account, never()).setVersion(any());
    }
}
