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
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.carddemo.account.mapper.AccountMapper;
import com.carddemo.account.repository.AccountRepository;
import com.carddemo.account.repository.CardXrefRepository;
import com.carddemo.account.repository.CustomerRepository;
import com.carddemo.common.crypto.PiiMasker;
import com.carddemo.common.domain.Account;
import com.carddemo.common.domain.CardXref;
import com.carddemo.common.domain.Customer;
import com.carddemo.common.dto.AccountUpdateRequestDto;
import com.carddemo.common.dto.AccountUpdateResponseDto;
import com.carddemo.common.dto.AccountViewResponseDto;
import com.carddemo.common.exception.CardDemoException;
import com.carddemo.common.exception.OptimisticLockConflictException;
import com.carddemo.common.exception.RecordNotFoundException;

import java.math.BigDecimal;
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

    /**
     * :purpose: COACTUPC ``1200-EDIT-MAP-INPUTS`` edit-sequence mock. These tests exercise
     *  the service's orchestration; the edit sequence itself is covered exhaustively by
     *  {@link AccountUpdateValidatorTest}.
     */
    @Mock
    private AccountUpdateValidator accountUpdateValidator;

    /** :purpose: Service under test with the five mocks injected via its single constructor. */
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
        AccountUpdateRequestDto request = new AccountUpdateRequestDto();
        AccountUpdateResponseDto expected = mock(AccountUpdateResponseDto.class);

        when(cardXrefRepository.findByXrefAcctId(ACCT_ID)).thenReturn(Optional.of(cardXref));
        when(accountRepository.findById(ACCT_ID)).thenReturn(Optional.of(account));
        when(customerRepository.findById(CUST_ID)).thenReturn(Optional.of(customer));
        // The client echoes back the version it read, so the compare step passes.
        request.setVersion(2L);
        when(account.getVersion()).thenReturn(2L);
        when(accountMapper.toUpdateResponse(account, customer, cardXref)).thenReturn(expected);
        // One submitted value that differs from the stored record, so COACTUPC
        // 1205-COMPARE-OLD-NEW reports a change rather than NO-CHANGES-DETECTED.
        request.setAcctActiveStatus("Y");

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
        AccountUpdateRequestDto request = new AccountUpdateRequestDto();
        request.setAcctActiveStatus("Y");

        when(cardXrefRepository.findByXrefAcctId(ACCT_ID)).thenReturn(Optional.of(cardXref));
        when(accountRepository.findById(ACCT_ID)).thenReturn(Optional.of(account));
        when(customerRepository.findById(CUST_ID)).thenReturn(Optional.of(customer));
        // The snapshot still matches at read time; the competing commit lands before the flush.
        request.setVersion(5L);
        when(account.getVersion()).thenReturn(5L);
        when(accountRepository.save(any(Account.class)))
                .thenThrow(new ObjectOptimisticLockingFailureException(Account.class, ACCT_ID));

        assertThatExceptionOfType(OptimisticLockConflictException.class)
                .isThrownBy(() -> accountService.updateAccount(ACCT_ID, request, null))
                .withMessage(MSG_DATA_WAS_CHANGED)
                .withCauseInstanceOf(ObjectOptimisticLockingFailureException.class);

        assertThat(OptimisticLockConflictException.MESSAGE).isEqualTo(MSG_DATA_WAS_CHANGED);
    }

    /**
     * :purpose: Verify the ``COACTUPC`` compare step: when the version snapshot carried by
     *  the request no longer matches the stored record, the update is abandoned with the
     *  verbatim conflict message BEFORE anything is applied or written, so a stale client
     *  can never overwrite a concurrent change (``DATA-WAS-CHANGED-BEFORE-UPDATE``).
     */
    @Test
    void updateAccount_staleVersionSnapshot_throwsConflictAndWritesNothing() {
        CardXref cardXref = xref();
        Account account = mock(Account.class);
        Customer customer = mock(Customer.class);
        AccountUpdateRequestDto request = new AccountUpdateRequestDto();

        when(cardXrefRepository.findByXrefAcctId(ACCT_ID)).thenReturn(Optional.of(cardXref));
        when(accountRepository.findById(ACCT_ID)).thenReturn(Optional.of(account));
        when(customerRepository.findById(CUST_ID)).thenReturn(Optional.of(customer));
        // The screen was displayed at version 3; the stored record has since moved to 7.
        request.setVersion(3L);
        when(account.getVersion()).thenReturn(7L);

        assertThatExceptionOfType(OptimisticLockConflictException.class)
                .isThrownBy(() -> accountService.updateAccount(ACCT_ID, request, null))
                .withMessage(MSG_DATA_WAS_CHANGED)
                .withNoCause();

        verify(accountMapper, never()).applyUpdate(any(), any(), any());
        verify(customerRepository, never()).save(any(Customer.class));
        verify(accountRepository, never()).save(any(Account.class));
        verify(accountRepository, never()).flush();
    }

    /**
     * :purpose: Verify the ``COACTUPC`` snapshot compare accepts the masked form of the
     *  three regulated identifiers, because the view and update responses only ever emit
     *  them masked (``PiiMasker``, AAP 0.6.7) and so a mask is the only snapshot a client
     *  can echo. Comparing it against the stored cleartext would report drift on every
     *  request and make the account update unreachable through its own API.
     */
    @Test
    void updateAccount_maskedIdentifierSnapshotEcho_isNotAConcurrentChange() {
        String storedSsn = "611264288";
        String storedGovtId = "1234567890123456";
        String storedEftId = "9876541756";

        CardXref cardXref = xref();
        Account account = mock(Account.class);
        Customer customer = mock(Customer.class);
        AccountUpdateResponseDto expected = mock(AccountUpdateResponseDto.class);

        when(cardXrefRepository.findByXrefAcctId(ACCT_ID)).thenReturn(Optional.of(cardXref));
        when(accountRepository.findById(ACCT_ID)).thenReturn(Optional.of(account));
        when(customerRepository.findById(CUST_ID)).thenReturn(Optional.of(customer));
        when(customer.getCustSsn()).thenReturn(storedSsn);
        when(customer.getCustGovtIssuedId()).thenReturn(storedGovtId);
        when(customer.getCustEftAccountId()).thenReturn(storedEftId);
        when(accountMapper.toUpdateResponse(account, customer, cardXref)).thenReturn(expected);

        AccountUpdateRequestDto request = new AccountUpdateRequestDto();
        request.setOldCustSsn(PiiMasker.maskSsn(storedSsn));
        request.setOldCustGovtIssuedId(PiiMasker.maskIdentifier(storedGovtId));
        request.setOldCustEftAccountId(PiiMasker.maskIdentifier(storedEftId));
        request.setCustSsn(PiiMasker.maskSsn(storedSsn));
        request.setCustGovtIssuedId(PiiMasker.maskIdentifier(storedGovtId));
        request.setCustEftAccountId(PiiMasker.maskIdentifier(storedEftId));
        // One genuine edit so 1205-COMPARE-OLD-NEW reports a change rather than
        // NO-CHANGES-DETECTED, which is what makes the write path observable here.
        request.setAcctActiveStatus("N");

        AccountUpdateResponseDto result = accountService.updateAccount(ACCT_ID, request, null);

        assertThat(result).isSameAs(expected);
        verify(accountMapper).applyUpdate(request, account, customer);
        verify(customerRepository).save(customer);
        verify(accountRepository).save(account);
    }

    /**
     * :purpose: Verify accepting a masked snapshot does not blunt concurrency detection:
     *  a mask whose visible digits no longer match the stored identifier means the record
     *  moved after the screen was displayed, so ``DATA-WAS-CHANGED-BEFORE-UPDATE`` is still
     *  reported and nothing is written.
     */
    @Test
    void updateAccount_maskedIdentifierSnapshotOfDifferentValue_throwsConflict() {
        String storedSsn = "611264288";

        CardXref cardXref = xref();
        Account account = mock(Account.class);
        Customer customer = mock(Customer.class);

        when(cardXrefRepository.findByXrefAcctId(ACCT_ID)).thenReturn(Optional.of(cardXref));
        when(accountRepository.findById(ACCT_ID)).thenReturn(Optional.of(account));
        when(customerRepository.findById(CUST_ID)).thenReturn(Optional.of(customer));
        when(customer.getCustSsn()).thenReturn(storedSsn);

        AccountUpdateRequestDto request = new AccountUpdateRequestDto();
        // The screen was displayed while the SSN still ended 9999; it now ends 4288.
        request.setOldCustSsn(PiiMasker.maskSsn("611269999"));
        request.setAcctActiveStatus("N");

        assertThatExceptionOfType(OptimisticLockConflictException.class)
                .isThrownBy(() -> accountService.updateAccount(ACCT_ID, request, null))
                .withMessage(MSG_DATA_WAS_CHANGED)
                .withNoCause();

        verify(accountMapper, never()).applyUpdate(any(), any(), any());
        verify(customerRepository, never()).save(any(Customer.class));
        verify(accountRepository, never()).save(any(Account.class));
    }

    /**
     * :purpose: Verify the compare step is fail-closed: a request that carries no version
     *  snapshot at all cannot prove the client read the current record, so the rewrite is
     *  refused with the legacy conflict message rather than applied.
     */
    @Test
    void updateAccount_staleVersionWithUnchangedValues_stillThrowsConflict() {
        CardXref cardXref = xref();
        Account account = mock(Account.class);
        Customer customer = mock(Customer.class);
        AccountUpdateRequestDto request = new AccountUpdateRequestDto();

        when(cardXrefRepository.findByXrefAcctId(ACCT_ID)).thenReturn(Optional.of(cardXref));
        when(accountRepository.findById(ACCT_ID)).thenReturn(Optional.of(account));
        when(customerRepository.findById(CUST_ID)).thenReturn(Optional.of(customer));
        // The submitted value equals what is on file, so the no-change determination would
        // also match - but the version proves the record moved after the screen was shown,
        // and the concurrency outcome must win over the benign no-change message.
        request.setVersion(3L);
        request.setAcctActiveStatus("Y");
        // Deliberately lenient: this stub is never consulted, and that is the guarantee -
        // the version compare refuses the rewrite before the stored value is weighed.
        lenient().when(account.getAcctActiveStatus()).thenReturn("Y");
        when(account.getVersion()).thenReturn(7L);

        assertThatExceptionOfType(OptimisticLockConflictException.class)
                .isThrownBy(() -> accountService.updateAccount(ACCT_ID, request, null))
                .withMessage(MSG_DATA_WAS_CHANGED);

        verify(accountMapper, never()).applyUpdate(any(), any(), any());
        verify(customerRepository, never()).save(any(Customer.class));
        verify(accountRepository, never()).save(any(Account.class));
    }

    /**
     * :purpose: Verify a matching version snapshot passes the compare step, so the update
     *  proceeds and the echo response carries the record the mapper assembled.
     */
    @Test
    void updateAccount_matchingVersionSnapshot_appliesAndPersists() {
        CardXref cardXref = xref();
        Account account = mock(Account.class);
        Customer customer = mock(Customer.class);
        AccountUpdateRequestDto request = new AccountUpdateRequestDto();
        AccountUpdateResponseDto expected = mock(AccountUpdateResponseDto.class);

        when(cardXrefRepository.findByXrefAcctId(ACCT_ID)).thenReturn(Optional.of(cardXref));
        when(accountRepository.findById(ACCT_ID)).thenReturn(Optional.of(account));
        when(customerRepository.findById(CUST_ID)).thenReturn(Optional.of(customer));
        request.setVersion(4L);
        when(account.getVersion()).thenReturn(4L);
        when(accountMapper.toUpdateResponse(account, customer, cardXref)).thenReturn(expected);

        AccountUpdateResponseDto result = accountService.updateAccount(ACCT_ID, request, null);

        verify(accountMapper).applyUpdate(request, account, customer);
        verify(customerRepository).save(customer);
        verify(accountRepository).save(account);
        assertThat(result).isSameAs(expected);
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
        AccountUpdateRequestDto request = new AccountUpdateRequestDto();
        request.setAcctActiveStatus("Y");

        when(cardXrefRepository.findByXrefAcctId(ACCT_ID)).thenReturn(Optional.of(cardXref));
        when(accountRepository.findById(ACCT_ID)).thenReturn(Optional.of(account));
        when(customerRepository.findById(CUST_ID)).thenReturn(Optional.of(customer));
        request.setVersion(1L);
        when(account.getVersion()).thenReturn(1L);

        accountService.updateAccount(ACCT_ID, request, null);

        verify(accountMapper).applyUpdate(request, account, customer);
        verify(account, never()).setAcctId(any());
        verify(customer, never()).setCustId(any());
        verify(account, never()).setVersion(any());
    }

    /**
     * :purpose: Verify the COACTUPC ``1200-EDIT-MAP-INPUTS`` edit sequence runs BEFORE any
     *  file is read, so an invalid submission never touches the account, customer or
     *  cross-reference records (QA F18/F19).
     */
    @Test
    void updateAccount_runsEditSequenceBeforeAnyFileRead() {
        AccountUpdateRequestDto request = new AccountUpdateRequestDto();
        request.setAcctActiveStatus("Z");
        doThrow(new CardDemoException(AccountUpdateValidator.MSG_STATUS_YN))
                .when(accountUpdateValidator).validate(request);

        assertThatExceptionOfType(CardDemoException.class)
                .isThrownBy(() -> accountService.updateAccount(ACCT_ID, request, null))
                .withMessage("Account Active Status must be Y or N");

        verifyNoInteractions(cardXrefRepository, accountRepository, customerRepository, accountMapper);
    }

    /**
     * :purpose: Verify COACTUPC ``9300-CHECK-CHANGE-IN-REC`` (L4131-4189): when the caller
     *  carries the display-time ``ACUP-OLD-*`` snapshot and the stored record no longer
     *  matches it, the update is abandoned with the verbatim conflict message and nothing
     *  is written (QA F20 lost update).
     */
    @Test
    void updateAccount_staleSnapshot_throwsOptimisticLockConflictAndWritesNothing() {
        CardXref cardXref = xref();
        Account account = mock(Account.class);
        Customer customer = mock(Customer.class);
        AccountUpdateRequestDto request = new AccountUpdateRequestDto();
        // The caller saw 103.00 at display time and submits 111.00; the record now holds 999.99.
        request.setOldAcctCurrBal(new BigDecimal("103.00"));
        request.setAcctCurrBal(new BigDecimal("111.00"));

        when(cardXrefRepository.findByXrefAcctId(ACCT_ID)).thenReturn(Optional.of(cardXref));
        when(accountRepository.findById(ACCT_ID)).thenReturn(Optional.of(account));
        when(customerRepository.findById(CUST_ID)).thenReturn(Optional.of(customer));
        when(account.getAcctCurrBal()).thenReturn(new BigDecimal("999.99"));

        assertThatExceptionOfType(OptimisticLockConflictException.class)
                .isThrownBy(() -> accountService.updateAccount(ACCT_ID, request, null))
                .withMessage(MSG_DATA_WAS_CHANGED);

        verify(accountMapper, never()).applyUpdate(any(), any(), any());
        verify(accountRepository, never()).save(any(Account.class));
        verify(customerRepository, never()).save(any(Customer.class));
    }

    /**
     * :purpose: Verify COACTUPC ``9300-CHECK-CHANGE-IN-REC`` passes when the stored record
     *  still matches the caller's display-time snapshot, so a well-behaved read-modify-write
     *  is not spuriously rejected.
     */
    @Test
    void updateAccount_matchingSnapshot_proceedsToRewrite() {
        CardXref cardXref = xref();
        Account account = mock(Account.class);
        Customer customer = mock(Customer.class);
        AccountUpdateRequestDto request = new AccountUpdateRequestDto();
        request.setOldAcctCurrBal(new BigDecimal("103.00"));
        request.setAcctCurrBal(new BigDecimal("111.00"));
        AccountUpdateResponseDto expected = mock(AccountUpdateResponseDto.class);

        when(cardXrefRepository.findByXrefAcctId(ACCT_ID)).thenReturn(Optional.of(cardXref));
        when(accountRepository.findById(ACCT_ID)).thenReturn(Optional.of(account));
        when(customerRepository.findById(CUST_ID)).thenReturn(Optional.of(customer));
        when(accountMapper.toUpdateResponse(account, customer, cardXref)).thenReturn(expected);
        when(account.getAcctCurrBal()).thenReturn(new BigDecimal("103.00"));

        assertThat(accountService.updateAccount(ACCT_ID, request, null)).isSameAs(expected);

        verify(accountMapper).applyUpdate(request, account, customer);
    }

    /**
     * :purpose: Verify COACTUPC ``1205-COMPARE-OLD-NEW``: a submission whose values already
     *  match the stored record reports ``NO-CHANGES-DETECTED`` and rewrites nothing.
     */
    @Test
    void updateAccount_noChangeSubmitted_reportsNoChangesDetected() {
        CardXref cardXref = xref();
        Account account = mock(Account.class);
        Customer customer = mock(Customer.class);
        AccountUpdateRequestDto request = new AccountUpdateRequestDto();
        request.setAcctActiveStatus("Y");

        when(cardXrefRepository.findByXrefAcctId(ACCT_ID)).thenReturn(Optional.of(cardXref));
        when(accountRepository.findById(ACCT_ID)).thenReturn(Optional.of(account));
        when(customerRepository.findById(CUST_ID)).thenReturn(Optional.of(customer));
        when(account.getAcctActiveStatus()).thenReturn("Y");

        assertThatExceptionOfType(CardDemoException.class)
                .isThrownBy(() -> accountService.updateAccount(ACCT_ID, request, null))
                .withMessage("No change detected with respect to values fetched.");

        verify(accountMapper, never()).applyUpdate(any(), any(), any());
        verify(accountRepository, never()).save(any(Account.class));
        verify(customerRepository, never()).save(any(Customer.class));
    }
}
