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
import com.carddemo.common.dto.SessionContext;
import com.carddemo.common.exception.OptimisticLockConflictException;
import com.carddemo.common.exception.RecordNotFoundException;

import jakarta.persistence.OptimisticLockException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * :purpose: Business-logic service for the CardDemo Account feature. Re-expresses
 *  the ``PROCEDURE DIVISION`` logic of the two legacy CICS account programs while
 *  preserving complete functional equivalence:
 *
 *  - ``COACTVWC`` (transaction ``CAVW``, read-only account view) becomes
 *    {@link #viewAccount(Long, SessionContext)}, reproducing the strict,
 *    ordered, short-circuit-on-first-miss read of the card cross-reference,
 *    the account master, and the customer master.
 *  - ``COACTUPC`` (transaction ``CAUP``, optimistic-locked account update)
 *    becomes {@link #updateAccount(Long, AccountUpdateRequestDto, SessionContext)},
 *    reproducing the read-snapshot-compare-rewrite pattern as a single atomic
 *    transaction guarded by JPA ``@Version`` optimistic locking.
 *
 *  The customer id is always derived from the card cross-reference, never taken
 *  as a separate input, mirroring the legacy ``MOVE XREF-CUST-ID TO CDEMO-CUST-ID``.
 * :note: This service performs no monetary arithmetic and never logs sensitive
 *  customer data (SSN, government-issued id, or full card number).
 */
@Service
public class AccountService {

    /** :purpose: SLF4J logger emitting non-sensitive account-feature diagnostics (account id only). */
    private static final Logger log = LoggerFactory.getLogger(AccountService.class);

    // ---------------------------------------------------------------------
    // Verbatim user-facing message constants (byte-identical to the COBOL
    // 88-level VALUE clauses). Reproduced character-for-character as frozen
    // contracts; not paraphrased, re-cased, or re-punctuated.
    // ---------------------------------------------------------------------

    /** :purpose: COACTVWC ``DID-NOT-FIND-ACCT-IN-CARDXREF`` (WORKING-STORAGE L130). */
    private static final String MSG_ACCT_NOT_IN_XREF =
            "Did not find this account in account card xref file";

    /** :purpose: COACTVWC ``DID-NOT-FIND-ACCT-IN-ACCTDAT`` (WORKING-STORAGE L132). */
    private static final String MSG_ACCT_NOT_IN_MASTER =
            "Did not find this account in account master file";

    /** :purpose: COACTVWC ``DID-NOT-FIND-CUST-IN-CUSTDAT`` (WORKING-STORAGE L134). */
    private static final String MSG_CUST_NOT_IN_MASTER =
            "Did not find associated customer in master file";

    /**
     * :purpose: COACTUPC ``COULD-NOT-LOCK-ACCT-FOR-UPDATE`` (WORKING-STORAGE L518).
     *  Preserved verbatim for traceability; the pessimistic lock step collapses to
     *  JPA ``@Version`` optimistic locking, so this text is not surfaced at runtime.
     */
    private static final String MSG_COULD_NOT_LOCK_ACCT =
            "Could not lock account record for update";

    /**
     * :purpose: COACTUPC ``COULD-NOT-LOCK-CUST-FOR-UPDATE`` (WORKING-STORAGE L520).
     *  Preserved verbatim for traceability; the pessimistic lock step collapses to
     *  JPA ``@Version`` optimistic locking, so this text is not surfaced at runtime.
     */
    private static final String MSG_COULD_NOT_LOCK_CUST =
            "Could not lock customer record for update";

    /**
     * :purpose: COACTUPC ``DATA-WAS-CHANGED-BEFORE-UPDATE`` (WORKING-STORAGE L522).
     *  Byte-identical to {@link OptimisticLockConflictException#MESSAGE}, which is the
     *  text actually surfaced (HTTP 409) when the optimistic-lock conflict is detected;
     *  kept here for COBOL traceability.
     */
    private static final String MSG_DATA_WAS_CHANGED =
            "Record changed by some one else. Please review";

    /**
     * :purpose: COACTUPC ``LOCKED-BUT-UPDATE-FAILED`` (WORKING-STORAGE L524).
     *  Preserved verbatim for traceability; a failed persist rolls the single
     *  transaction back rather than surfacing this text.
     */
    private static final String MSG_UPDATE_FAILED =
            "Update of record failed";

    /** :purpose: Card cross-reference repository (CXACAIX alternate index; COACTVWC 9200). */
    private final CardXrefRepository cardXrefRepository;

    /** :purpose: Account master repository (ACCTFILE; COACTVWC 9300, COACTUPC REWRITE). */
    private final AccountRepository accountRepository;

    /** :purpose: Customer master repository (CUSTFILE; COACTVWC 9400, COACTUPC REWRITE). */
    private final CustomerRepository customerRepository;

    /** :purpose: Entity-to-DTO mapper assembling view/update responses and applying edits. */
    private final AccountMapper accountMapper;

    /**
     * :purpose: Construct the account service with its collaborating repositories and mapper.
     * :param cardXrefRepository: card cross-reference repository resolving account-to-customer linkage.
     * :param accountRepository: account master repository.
     * :param customerRepository: customer master repository.
     * :param accountMapper: mapper translating between entities and account DTOs.
     */
    public AccountService(CardXrefRepository cardXrefRepository,
                          AccountRepository accountRepository,
                          CustomerRepository customerRepository,
                          AccountMapper accountMapper) {
        this.cardXrefRepository = cardXrefRepository;
        this.accountRepository = accountRepository;
        this.customerRepository = customerRepository;
        this.accountMapper = accountMapper;
    }

    /**
     * :purpose: Retrieve a single account for read-only display, reproducing the
     *  ``COACTVWC`` (transaction ``CAVW``) ordered short-circuit read: the card
     *  cross-reference by account id, then the account master by account id, then
     *  the customer master by the cross-reference's customer id. The first missing
     *  record aborts the read; later lookups do not execute. The resolved account,
     *  customer, and card identifiers are propagated into the session context,
     *  mirroring the legacy COMMAREA moves.
     * :param acctId: the 11-digit account identifier to view.
     * :param sessionContext: externalized session context to update with the resolved
     *  account, customer, and card identifiers; ignored when {@code null}.
     * :returns: the assembled read-only account view response.
     * :raises RecordNotFoundException: (HTTP 404) when the cross-reference, the account
     *  master, or the customer master holds no matching record.
     */
    @Transactional(readOnly = true)
    public AccountViewResponseDto viewAccount(Long acctId, SessionContext sessionContext) {
        log.debug("Account view requested for acctId={}", acctId);

        AccountRecords records = readAccountRecords(acctId);

        if (sessionContext != null) {
            sessionContext.setAcctId(acctId);
            sessionContext.setCustId(records.custId());
            sessionContext.setCardNum(records.cardXref().getXrefCardNum());
        }

        return accountMapper.toViewResponse(records.account(), records.customer(), records.cardXref());
    }

    /**
     * :purpose: Apply an account update as a single atomic transaction, reproducing
     *  ``COACTUPC`` (transaction ``CAUP``). Re-reads the same three records in the
     *  same ordered short-circuit fashion as the view path, applies the editable
     *  account and customer fields onto the freshly loaded managed entities, and
     *  persists both within one transactional unit of work. Concurrent modification
     *  is detected through JPA ``@Version`` optimistic locking (the equivalent of the
     *  legacy read-snapshot-compare-rewrite) and surfaced as a conflict. Both the
     *  account and customer changes commit together or roll back together, replacing
     *  the legacy dual ``REWRITE`` plus ``SYNCPOINT ROLLBACK``.
     * :param acctId: the 11-digit account identifier to update.
     * :param request: the editable account and customer master fields to apply.
     * :param sessionContext: externalized session context to update with the resolved
     *  account and customer identifiers; ignored when {@code null}.
     * :returns: the post-update echo response reflecting the persisted state.
     * :raises RecordNotFoundException: (HTTP 404) when the cross-reference, the account
     *  master, or the customer master holds no matching record.
     * :raises OptimisticLockConflictException: (HTTP 409) when the account was modified
     *  concurrently between load and flush.
     * :note: The account and customer persists are ordered customer then account for
     *  cross-service deadlock avoidance; the rationale for this deviation from the
     *  legacy account-then-customer rewrite order is recorded in docs/decision-log.md.
     */
    @Transactional
    public AccountUpdateResponseDto updateAccount(Long acctId,
                                                  AccountUpdateRequestDto request,
                                                  SessionContext sessionContext) {
        log.info("Account update requested for acctId={}", acctId);

        AccountRecords records = readAccountRecords(acctId);
        Account account = records.account();
        Customer customer = records.customer();

        // Apply the submitted edits onto the managed entities in place so their
        // JPA @Version and primary keys are preserved for optimistic-lock detection.
        accountMapper.applyUpdate(request, account, customer);

        try {
            // Single unit of work: customer then account (AAP deadlock-avoidance order).
            // The explicit flush forces the @Version check to run inside this transaction
            // so a concurrent modification is caught here and never leaks past the boundary.
            customerRepository.save(customer);
            accountRepository.save(account);
            accountRepository.flush();
        } catch (ObjectOptimisticLockingFailureException | OptimisticLockException e) {
            log.warn("Optimistic lock conflict updating acctId={}", acctId);
            throw new OptimisticLockConflictException(e);
        }

        if (sessionContext != null) {
            sessionContext.setAcctId(acctId);
            sessionContext.setCustId(records.custId());
        }

        return accountMapper.toUpdateResponse(account, customer, records.cardXref());
    }

    /**
     * :purpose: Perform the ordered, short-circuit-on-first-miss read shared by the
     *  view and update flows (``COACTVWC``/``COACTUPC`` ``9000-READ-ACCT``): the card
     *  cross-reference by account id (``9200``), the account master by account id
     *  (``9300``), then the customer master by the cross-reference's customer id
     *  (``9400``). Runs inside the caller's transaction and therefore carries no
     *  transaction annotation of its own.
     * :param acctId: the 11-digit account identifier to resolve.
     * :returns: the resolved cross-reference, account, and customer plus the derived customer id.
     * :raises RecordNotFoundException: (HTTP 404) when the cross-reference, the account
     *  master, or the customer master holds no matching record; the first miss wins.
     */
    private AccountRecords readAccountRecords(Long acctId) {
        CardXref cardXref = cardXrefRepository.findByXrefAcctId(acctId)
                .orElseThrow(() -> new RecordNotFoundException(MSG_ACCT_NOT_IN_XREF));

        // Customer id is derived from the cross-reference (COBOL MOVE XREF-CUST-ID
        // TO CDEMO-CUST-ID), never taken as a separate input.
        Long custId = cardXref.getXrefCustId();

        Account account = accountRepository.findById(acctId)
                .orElseThrow(() -> new RecordNotFoundException(MSG_ACCT_NOT_IN_MASTER));

        Customer customer = customerRepository.findById(custId)
                .orElseThrow(() -> new RecordNotFoundException(MSG_CUST_NOT_IN_MASTER));

        return new AccountRecords(cardXref, account, customer, custId);
    }

    /**
     * :purpose: Immutable carrier for the three records resolved by the ordered account
     *  read plus the customer id derived from the cross-reference.
     * :param cardXref: the resolved card cross-reference.
     * :param account: the resolved account master record.
     * :param customer: the resolved customer master record.
     * :param custId: the customer id derived from the cross-reference.
     */
    private record AccountRecords(CardXref cardXref, Account account, Customer customer, Long custId) {
    }
}
