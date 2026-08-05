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
import com.carddemo.common.crypto.PiiMasker;
import com.carddemo.common.domain.Account;
import com.carddemo.common.domain.CardXref;
import com.carddemo.common.domain.Customer;
import com.carddemo.common.dto.AccountUpdateRequestDto;
import com.carddemo.common.dto.AccountUpdateResponseDto;
import com.carddemo.common.dto.AccountViewResponseDto;
import com.carddemo.common.dto.SessionContext;
import com.carddemo.common.exception.CardDemoException;
import com.carddemo.common.exception.OptimisticLockConflictException;
import com.carddemo.common.exception.RecordNotFoundException;

import jakarta.persistence.OptimisticLockException;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;

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

    /** :purpose: COACTUPC ``1200-EDIT-MAP-INPUTS`` field-level input edits. */
    private final AccountUpdateValidator accountUpdateValidator;

    /**
     * :purpose: Construct the account service with its collaborating repositories and mapper.
     * :param cardXrefRepository: card cross-reference repository resolving account-to-customer linkage.
     * :param accountRepository: account master repository.
     * :param customerRepository: customer master repository.
     * :param accountMapper: mapper translating between entities and account DTOs.
     * :param accountUpdateValidator: the COACTUPC ``1200-EDIT-MAP-INPUTS`` edit sequence.
     */
    public AccountService(CardXrefRepository cardXrefRepository,
                          AccountRepository accountRepository,
                          CustomerRepository customerRepository,
                          AccountMapper accountMapper,
                          AccountUpdateValidator accountUpdateValidator) {
        this.cardXrefRepository = cardXrefRepository;
        this.accountRepository = accountRepository;
        this.customerRepository = customerRepository;
        this.accountMapper = accountMapper;
        this.accountUpdateValidator = accountUpdateValidator;
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
     *  same ordered short-circuit fashion as the view path, compares the version the
     *  client read at display time against the freshly loaded record, applies the
     *  editable account and customer fields onto the managed entities, and persists
     *  both within one transactional unit of work. Concurrent modification is detected
     *  twice over, exactly reproducing the legacy read-snapshot-compare-rewrite: the
     *  submitted version snapshot is compared before anything is written, and JPA
     *  ``@Version`` optimistic locking covers a commit landing between that read and
     *  the flush. Both the account and customer changes commit together or roll back
     *  together, replacing the legacy dual ``REWRITE`` plus ``SYNCPOINT ROLLBACK``.
     * :param acctId: the 11-digit account identifier to update.
     * :param request: the editable account and customer master fields to apply, carrying
     *  the mandatory ``version`` snapshot read when the screen was displayed.
     * :param sessionContext: externalized session context to update with the resolved
     *  account and customer identifiers; ignored when {@code null}.
     * :returns: the post-update echo response reflecting the persisted state.
     * :raises RecordNotFoundException: (HTTP 404) when the cross-reference, the account
     *  master, or the customer master holds no matching record.
     * :raises OptimisticLockConflictException: (HTTP 409) when the submitted version no
     *  longer matches the stored record, or when the account is modified concurrently
     *  between load and flush.
     * :note: Persists customer first, then account - the reverse of the legacy
     *  account-then-customer ``REWRITE`` order. See docs/decision-log.md.
     */
    @Transactional
    public AccountUpdateResponseDto updateAccount(Long acctId,
                                                  AccountUpdateRequestDto request,
                                                  SessionContext sessionContext) {
        log.info("Account update requested for acctId={}", acctId);

        boolean snapshotSupplied = snapshotPresent(request);

        // Step 1 -- COACTUPC 1205-COMPARE-OLD-NEW (L1459-1467, L1681+): the submitted
        // ACUP-NEW-* values are compared against the display-time ACUP-OLD-* snapshot
        // BEFORE any field edit runs, and an identical submission reports
        // NO-CHANGES-FOUND without rewriting anything.
        if (snapshotSupplied && !hasSubmittedChangeAgainstSnapshot(request)) {
            log.debug("Account update no-op for acctId={}: submitted values match the snapshot", acctId);
            throw new CardDemoException(AccountUpdateValidator.MSG_NO_CHANGES);
        }

        // Step 2 -- COACTUPC 1200-EDIT-MAP-INPUTS (L1472-1672): field-level input edits,
        // fail-fast in COBOL PERFORM order. A submission with no field at all reports
        // NO-SEARCH-CRITERIA-RECEIVED rather than nulling the record.
        accountUpdateValidator.validate(request);

        AccountRecords records = readAccountRecords(acctId);
        Account account = records.account();
        Customer customer = records.customer();

        // Step 3 -- COACTUPC 9300/9700-CHECK-CHANGE-IN-REC (L4131-4189): the caller's
        // display-time snapshot is compared against the freshly re-read record and the
        // rewrite is abandoned on any difference rather than overwriting a concurrent edit
        // (AAP 0.6.2). Two snapshot forms are honoured, because either one proves what the
        // caller actually read: the ``version`` counter and the ACUP-OLD-* field values.
        // The provider-level @Version check still covers a commit that lands between this
        // read and the flush.
        assertVersionUnchanged(acctId, request, account);

        if (snapshotSupplied && hasDataChangedSinceSnapshot(request, account, customer)) {
            log.warn("Account update conflict for acctId={}: record changed since display", acctId);
            throw new OptimisticLockConflictException();
        }

        // Step 4 -- callers that carry neither the ACUP-OLD-* snapshot nor a version cannot
        // take part in 1205-COMPARE-OLD-NEW, so the equivalent no-change determination is
        // made against the stored record. A caller that echoed a matching version has
        // already proven its read, and its submission is applied.
        if (!snapshotSupplied && request.getVersion() == null
                && isUnchanged(request, account, customer)) {
            log.debug("Account update no-op for acctId={}: submitted values match the record", acctId);
            throw new CardDemoException(AccountUpdateValidator.MSG_NO_CHANGES);
        }

        // Step 5 -- apply the submitted edits onto the managed entities in place so their
        // JPA @Version and primary keys are preserved for optimistic-lock detection.
        List<Object> accountFieldsBefore = editableAccountFields(account);
        accountMapper.applyUpdate(request, account, customer);
        boolean accountRowChanged = !accountFieldsBefore.equals(editableAccountFields(account));

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

        // Step 6 -- the account is the ROOT of the account+customer aggregate COACTUPC
        // rewrites, so its version must move whenever the aggregate moves. The provider
        // advances it only when the account ROW is dirty, so an update confined to customer
        // fields is completed by advancing the root token explicitly; otherwise the next
        // writer's stale snapshot would still match and its submission would silently
        // overwrite this change (AAP 0.6.2).
        Long effectiveVersion = account.getVersion();
        if (!accountRowChanged) {
            accountRepository.advanceAggregateVersion(acctId);
            effectiveVersion = accountRepository.findVersionByAcctId(acctId).orElse(effectiveVersion);
        }

        if (sessionContext != null) {
            sessionContext.setAcctId(acctId);
            sessionContext.setCustId(records.custId());
        }

        AccountUpdateResponseDto response =
                accountMapper.toUpdateResponse(account, customer, records.cardXref());
        if (response != null && effectiveVersion != null) {
            // Echo the token the caller must present on its NEXT submission, which after a
            // customer-only change is the advanced root version rather than the value the
            // managed account entity still carries.
            response.setVersion(effectiveVersion);
        }
        return response;
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
        CardXref cardXref = cardXrefRepository.findFirstByXrefAcctIdOrderByXrefCardNumAsc(acctId)
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
     * :purpose: Capture the values of the ten editable ACCOUNT-record fields, so the service
     *  can tell an update that rewrites the account row from one whose only changes land on
     *  the customer record.
     * :param account: the freshly loaded account.
     * :returns: an order-stable list of the editable field values, suitable for equality.
     */
    private static List<Object> editableAccountFields(Account account) {
        return Arrays.asList(
                account.getAcctActiveStatus(),
                account.getAcctCurrBal(),
                account.getAcctCreditLimit(),
                account.getAcctCashCreditLimit(),
                account.getAcctCurrCycCredit(),
                account.getAcctCurrCycDebit(),
                account.getAcctOpenDate(),
                account.getAcctExpiraionDate(),
                account.getAcctReissueDate(),
                account.getAcctGroupId());
    }

    /**
     * :purpose: Reproduce the ``COACTUPC`` compare step of the read-snapshot-compare-rewrite
     *  pattern: the version the client read when the screen was displayed must still be the
     *  version stored on the record, otherwise another user changed the record in the
     *  meantime and the rewrite must be abandoned rather than overwrite that change
     *  (``DATA-WAS-CHANGED-BEFORE-UPDATE``, AAP 0.6.2). An absent version snapshot is
     *  treated as "not compared" rather than as a conflict, because a caller may instead
     *  carry the ACUP-OLD-* field snapshot, which
     *  :meth:`hasDataChangedSinceSnapshot` compares field by field.
     * :param acctId: the account identifier being updated, for the conflict log line.
     * :param request: the update request carrying the client's version snapshot.
     * :param account: the freshly loaded managed account record.
     * :returns: nothing; the method either passes silently or raises.
     * :raises OptimisticLockConflictException: (HTTP 409, message "Record changed by some
     *  one else. Please review") when the submitted version does not match the stored one.
     */
    private void assertVersionUnchanged(Long acctId, AccountUpdateRequestDto request, Account account) {
        Long submitted = request == null ? null : request.getVersion();
        Long current = account.getVersion();
        if (submitted == null) {
            // Not compared: the caller either carries the ACUP-OLD-* snapshot, which
            // hasDataChangedSinceSnapshot compares field by field, or submits values that
            // are weighed against the stored record. Refusing purely for the absence of a
            // version has no COACTUPC analogue.
            return;
        }
        if (!Objects.equals(submitted, current)) {
            log.warn("Stale account update rejected for acctId={}: submitted version={}, current version={}",
                    acctId, submitted, current);
            throw new OptimisticLockConflictException();
        }
    }

    /**
     * :purpose: Report whether the caller supplied any part of the display-time
     *  ``ACUP-OLD-*`` snapshot. When no snapshot is carried the concurrency compare is
     *  skipped, preserving the behaviour of callers that do not send it.
     * :param request: the submitted update request.
     * :returns: ``true`` when at least one snapshot field is present.
     */
    private boolean snapshotPresent(AccountUpdateRequestDto request) {
        return request.getOldAcctActiveStatus() != null
                || request.getOldAcctCurrBal() != null
                || request.getOldAcctCreditLimit() != null
                || request.getOldAcctCashCreditLimit() != null
                || request.getOldAcctCurrCycCredit() != null
                || request.getOldAcctCurrCycDebit() != null
                || request.getOldAcctOpenDate() != null
                || request.getOldAcctExpiraionDate() != null
                || request.getOldAcctReissueDate() != null
                || request.getOldAcctGroupId() != null
                || request.getOldCustFirstName() != null
                || request.getOldCustMiddleName() != null
                || request.getOldCustLastName() != null
                || request.getOldCustAddrLine1() != null
                || request.getOldCustAddrLine2() != null
                || request.getOldCustAddrLine3() != null
                || request.getOldCustAddrStateCd() != null
                || request.getOldCustAddrCountryCd() != null
                || request.getOldCustAddrZip() != null
                || request.getOldCustPhoneNum1() != null
                || request.getOldCustPhoneNum2() != null
                || request.getOldCustSsn() != null
                || request.getOldCustGovtIssuedId() != null
                || request.getOldCustDobYyyyMmDd() != null
                || request.getOldCustEftAccountId() != null
                || request.getOldCustPriCardHolderInd() != null
                || request.getOldCustFicoCreditScore() != null;
    }

    /**
     * :purpose: Reproduce ``COACTUPC 1205-COMPARE-OLD-NEW`` (L1681+): compare the submitted
     *  ``ACUP-NEW-*`` values against the display-time ``ACUP-OLD-*`` snapshot the caller
     *  carries. Text fields compare case-insensitively after trimming and amounts compare
     *  numerically, exactly as the COBOL ``FUNCTION UPPER-CASE`` / ``FUNCTION TRIM``
     *  comparison does.
     * :param request: the submitted update request carrying both the new and old values.
     * :returns: ``true`` when at least one submitted value differs from its snapshot.
     */
    private boolean hasSubmittedChangeAgainstSnapshot(AccountUpdateRequestDto request) {
        return textDiffers(request.getAcctActiveStatus(), request.getOldAcctActiveStatus())
                || amountDiffers(request.getAcctCurrBal(), request.getOldAcctCurrBal())
                || amountDiffers(request.getAcctCreditLimit(), request.getOldAcctCreditLimit())
                || amountDiffers(request.getAcctCashCreditLimit(), request.getOldAcctCashCreditLimit())
                || amountDiffers(request.getAcctCurrCycCredit(), request.getOldAcctCurrCycCredit())
                || amountDiffers(request.getAcctCurrCycDebit(), request.getOldAcctCurrCycDebit())
                || textDiffers(request.getAcctOpenDate(), request.getOldAcctOpenDate())
                || textDiffers(request.getAcctExpiraionDate(), request.getOldAcctExpiraionDate())
                || textDiffers(request.getAcctReissueDate(), request.getOldAcctReissueDate())
                || textDiffers(request.getAcctGroupId(), request.getOldAcctGroupId())
                || textDiffers(request.getCustFirstName(), request.getOldCustFirstName())
                || textDiffers(request.getCustMiddleName(), request.getOldCustMiddleName())
                || textDiffers(request.getCustLastName(), request.getOldCustLastName())
                || textDiffers(request.getCustAddrLine1(), request.getOldCustAddrLine1())
                || textDiffers(request.getCustAddrLine2(), request.getOldCustAddrLine2())
                || textDiffers(request.getCustAddrLine3(), request.getOldCustAddrLine3())
                || textDiffers(request.getCustAddrStateCd(), request.getOldCustAddrStateCd())
                || textDiffers(request.getCustAddrCountryCd(), request.getOldCustAddrCountryCd())
                || textDiffers(request.getCustAddrZip(), request.getOldCustAddrZip())
                || textDiffers(request.getCustPhoneNum1(), request.getOldCustPhoneNum1())
                || textDiffers(request.getCustPhoneNum2(), request.getOldCustPhoneNum2())
                || textDiffers(request.getCustSsn(), request.getOldCustSsn())
                || textDiffers(request.getCustGovtIssuedId(), request.getOldCustGovtIssuedId())
                || textDiffers(request.getCustDobYyyyMmDd(), request.getOldCustDobYyyyMmDd())
                || textDiffers(request.getCustEftAccountId(), request.getOldCustEftAccountId())
                || textDiffers(request.getCustPriCardHolderInd(), request.getOldCustPriCardHolderInd())
                || !Objects.equals(request.getCustFicoCreditScore(), request.getOldCustFicoCreditScore());
    }

    /**
     * :purpose: Compare two text values as ``COACTUPC 1205-COMPARE-OLD-NEW`` does, treating
     *  ``null`` and blank alike and ignoring case and padding.
     * :param left: the first value.
     * :param right: the second value.
     * :returns: ``true`` when the two values differ.
     */
    private boolean textDiffers(String left, String right) {
        String a = left == null ? "" : left.trim();
        String b = right == null ? "" : right.trim();
        return !a.equalsIgnoreCase(b);
    }

    /**
     * :purpose: Compare two monetary values numerically, treating ``null`` as absent.
     * :param left: the first amount.
     * :param right: the second amount.
     * :returns: ``true`` when the two amounts differ.
     */
    private boolean amountDiffers(BigDecimal left, BigDecimal right) {
        if (left == null || right == null) {
            return left != right;
        }
        return left.compareTo(right) != 0;
    }

    /**
     * :purpose: Reproduce ``COACTUPC 9300-CHECK-CHANGE-IN-REC``: compare the freshly
     *  re-read account and customer, field by field, against the display-time snapshot
     *  the caller carries. Only fields the caller actually snapshotted participate, so a
     *  partial snapshot still guards the fields it covers.
     * :param request: the submitted update request carrying the ``ACUP-OLD-*`` values.
     * :param account: the account master record re-read inside this transaction.
     * :param customer: the customer master record re-read inside this transaction.
     * :returns: ``true`` when any snapshotted field differs from the stored value.
     */
    private boolean hasDataChangedSinceSnapshot(AccountUpdateRequestDto request,
                                                Account account,
                                                Customer customer) {
        return textChanged(request.getOldAcctActiveStatus(), account.getAcctActiveStatus())
                || amountChanged(request.getOldAcctCurrBal(), account.getAcctCurrBal())
                || amountChanged(request.getOldAcctCreditLimit(), account.getAcctCreditLimit())
                || amountChanged(request.getOldAcctCashCreditLimit(), account.getAcctCashCreditLimit())
                || amountChanged(request.getOldAcctCurrCycCredit(), account.getAcctCurrCycCredit())
                || amountChanged(request.getOldAcctCurrCycDebit(), account.getAcctCurrCycDebit())
                || textChanged(request.getOldAcctOpenDate(), account.getAcctOpenDate())
                || textChanged(request.getOldAcctExpiraionDate(), account.getAcctExpiraionDate())
                || textChanged(request.getOldAcctReissueDate(), account.getAcctReissueDate())
                || textChanged(request.getOldAcctGroupId(), account.getAcctGroupId())
                || textChanged(request.getOldCustFirstName(), customer.getCustFirstName())
                || textChanged(request.getOldCustMiddleName(), customer.getCustMiddleName())
                || textChanged(request.getOldCustLastName(), customer.getCustLastName())
                || textChanged(request.getOldCustAddrLine1(), customer.getCustAddrLine1())
                || textChanged(request.getOldCustAddrLine2(), customer.getCustAddrLine2())
                || textChanged(request.getOldCustAddrLine3(), customer.getCustAddrLine3())
                || textChanged(request.getOldCustAddrStateCd(), customer.getCustAddrStateCd())
                || textChanged(request.getOldCustAddrCountryCd(), customer.getCustAddrCountryCd())
                || textChanged(request.getOldCustAddrZip(), customer.getCustAddrZip())
                || textChanged(request.getOldCustPhoneNum1(), customer.getCustPhoneNum1())
                || textChanged(request.getOldCustPhoneNum2(), customer.getCustPhoneNum2())
                || maskedIdentifierChanged(request.getOldCustSsn(), customer.getCustSsn())
                || maskedIdentifierChanged(request.getOldCustGovtIssuedId(), customer.getCustGovtIssuedId())
                || textChanged(request.getOldCustDobYyyyMmDd(), customer.getCustDobYyyyMmDd())
                || maskedIdentifierChanged(request.getOldCustEftAccountId(), customer.getCustEftAccountId())
                || textChanged(request.getOldCustPriCardHolderInd(), customer.getCustPriCardHolderInd())
                || numberChanged(request.getOldCustFicoCreditScore(), customer.getCustFicoCreditScore());
    }

    /**
     * :purpose: Reproduce ``COACTUPC 1205-COMPARE-OLD-NEW``: report whether the submitted
     *  values are identical to the stored record, in which case there is nothing to
     *  rewrite and the program reports ``NO-CHANGES-DETECTED``.
     * :param request: the submitted update request.
     * :param account: the stored account master record.
     * :param customer: the stored customer master record.
     * :returns: ``true`` when every submitted field already matches the stored value.
     */
    private boolean isUnchanged(AccountUpdateRequestDto request, Account account, Customer customer) {
        return !textChanged(request.getAcctActiveStatus(), account.getAcctActiveStatus())
                && !amountChanged(request.getAcctCurrBal(), account.getAcctCurrBal())
                && !amountChanged(request.getAcctCreditLimit(), account.getAcctCreditLimit())
                && !amountChanged(request.getAcctCashCreditLimit(), account.getAcctCashCreditLimit())
                && !amountChanged(request.getAcctCurrCycCredit(), account.getAcctCurrCycCredit())
                && !amountChanged(request.getAcctCurrCycDebit(), account.getAcctCurrCycDebit())
                && !textChanged(request.getAcctOpenDate(), account.getAcctOpenDate())
                && !textChanged(request.getAcctExpiraionDate(), account.getAcctExpiraionDate())
                && !textChanged(request.getAcctReissueDate(), account.getAcctReissueDate())
                && !textChanged(request.getAcctGroupId(), account.getAcctGroupId())
                && !textChanged(request.getCustFirstName(), customer.getCustFirstName())
                && !textChanged(request.getCustMiddleName(), customer.getCustMiddleName())
                && !textChanged(request.getCustLastName(), customer.getCustLastName())
                && !textChanged(request.getCustAddrLine1(), customer.getCustAddrLine1())
                && !textChanged(request.getCustAddrLine2(), customer.getCustAddrLine2())
                && !textChanged(request.getCustAddrLine3(), customer.getCustAddrLine3())
                && !textChanged(request.getCustAddrStateCd(), customer.getCustAddrStateCd())
                && !textChanged(request.getCustAddrCountryCd(), customer.getCustAddrCountryCd())
                && !textChanged(request.getCustAddrZip(), customer.getCustAddrZip())
                && !textChanged(request.getCustPhoneNum1(), customer.getCustPhoneNum1())
                && !textChanged(request.getCustPhoneNum2(), customer.getCustPhoneNum2())
                && !maskedIdentifierChanged(request.getCustSsn(), customer.getCustSsn())
                && !maskedIdentifierChanged(request.getCustGovtIssuedId(), customer.getCustGovtIssuedId())
                && !textChanged(request.getCustDobYyyyMmDd(), customer.getCustDobYyyyMmDd())
                && !maskedIdentifierChanged(request.getCustEftAccountId(), customer.getCustEftAccountId())
                && !textChanged(request.getCustPriCardHolderInd(), customer.getCustPriCardHolderInd())
                && !numberChanged(request.getCustFicoCreditScore(), customer.getCustFicoCreditScore());
    }

    /**
     * :purpose: Compare a submitted text value against the stored one, treating an absent
     *  submitted value as "not compared" so a partial snapshot or a partial submission
     *  never reports a spurious difference.
     * :param submitted: the submitted or snapshotted value; ``null`` means "not compared".
     * :param stored: the value currently held in the record.
     * :returns: ``true`` when both are present and differ after trimming.
     */
    /**
     * :purpose: Compare a submitted regulated identifier (SSN, government-issued id, EFT
     *  account id) against the stored one, honouring the fact that the view and update
     *  responses only ever emit these three fields in masked form
     *  (``AccountMapper``/``PiiMasker``, AAP 0.6.7).
     * :note: A mask carries no cleartext, so comparing it against the stored value the way
     *  ``textChanged`` does would report drift on every request a client can actually
     *  build from what it was shown, refusing every update with
     *  ``DATA-WAS-CHANGED-BEFORE-UPDATE``. A mask-shaped value is therefore weighed by
     *  ``PiiMasker.isMaskOf``: it matches only when it is the mask of the value now
     *  stored, so a concurrent change that alters the visible digits is still reported as
     *  drift. This mirrors ``AccountMapper.retainWhenMasked`` on the submitted-value side.
     * :param submitted: the submitted or snapshotted value; ``null`` means "not compared".
     * :param stored: the value currently held in the record.
     * :returns: ``true`` when the two are present and differ.
     */
    private boolean maskedIdentifierChanged(String submitted, String stored) {
        if (!PiiMasker.isMaskShaped(submitted)) {
            return textChanged(submitted, stored);
        }
        if (stored == null || stored.isEmpty()) {
            // Nothing is stored, so the mask cannot contradict a stored value; treated as
            // "not compared", exactly as retainWhenMasked keeps the stored value here.
            return false;
        }
        return !PiiMasker.isMaskOf(submitted, stored);
    }

    private boolean textChanged(String submitted, String stored) {
        if (submitted == null) {
            return false;
        }
        // COACTUPC compares text fields through FUNCTION UPPER-CASE / LOWER-CASE after
        // trimming, so a case-only or padding-only difference is not a change.
        String left = submitted.trim();
        String right = stored == null ? "" : stored.trim();
        return !left.equalsIgnoreCase(right);
    }

    /**
     * :purpose: Compare a submitted monetary value against the stored one by numeric
     *  value, so a scale-only difference is not treated as a change.
     * :param submitted: the submitted or snapshotted amount; ``null`` means "not compared".
     * :param stored: the amount currently held in the record.
     * :returns: ``true`` when both are present and differ numerically.
     */
    private boolean amountChanged(BigDecimal submitted, BigDecimal stored) {
        if (submitted == null) {
            return false;
        }
        return stored == null || submitted.compareTo(stored) != 0;
    }

    /**
     * :purpose: Compare a submitted integer value against the stored one.
     * :param submitted: the submitted or snapshotted value; ``null`` means "not compared".
     * :param stored: the value currently held in the record.
     * :returns: ``true`` when both are present and differ.
     */
    private boolean numberChanged(Integer submitted, Integer stored) {
        if (submitted == null) {
            return false;
        }
        return !Objects.equals(submitted, stored);
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
