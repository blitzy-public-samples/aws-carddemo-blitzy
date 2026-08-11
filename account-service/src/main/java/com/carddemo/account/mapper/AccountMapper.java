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
package com.carddemo.account.mapper;

import com.carddemo.common.domain.Account;
import com.carddemo.common.domain.CardXref;
import com.carddemo.common.domain.Customer;
import com.carddemo.common.dto.AccountUpdateRequestDto;
import com.carddemo.common.dto.AccountUpdateResponseDto;
import com.carddemo.common.dto.AccountViewResponseDto;
import com.carddemo.common.crypto.PiiMasker;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * :purpose: Hand-written, stateless mapper that translates between the shared
 *     ``carddemo-common`` JPA entities (``Account``, ``Customer``, ``CardXref``) and the
 *     account view/update DTOs (``com.carddemo.common.dto``). It re-expresses the
 *     field-assembly logic of the legacy CICS programs ``COACTVWC`` (transaction ``CAVW``,
 *     account view SEND-MAP assembly) and ``COACTUPC`` (transaction ``CAUP``, update-record
 *     preparation). Reads copy entity fields into the read-only view response and post-update
 *     echo; writes apply the inbound update-request fields onto the caller-supplied managed
 *     entities so JPA ``@Version`` optimistic locking is preserved. The five monetary account
 *     fields are normalized to ``BigDecimal`` scale 2 by truncation toward zero. Performs a
 *     pure field copy only: no validation, no persistence, no reformatting, no logging, and no
 *     other business logic (those responsibilities belong to ``AccountService`` and the DTO
 *     layer).
 */
@Component
public class AccountMapper {

    /**
     * :purpose: Assemble the read-only account view response echoing the account and customer
     *     master fields rendered on the legacy ``CACTVWA`` screen (``COACTVWC``). Monetary fields
     *     are carried as ``BigDecimal`` at scale 2; sensitive customer fields (SSN,
     *     government-issued id) are copied verbatim for the DTO layer to mask. Pure field copy
     *     with no business logic.
     * :param account: the account entity to read; account fields are copied only when it is
     *     non-{@code null}.
     * :param customer: the customer entity to read; customer fields are copied only when it is
     *     non-{@code null}.
     * :param cardXref: the resolved card cross-reference (CVACT03Y) supplying the
     *     card-to-customer-to-account linkage; the read-only account view assembly (COACTVWC
     *     ``CACTVWA``) surfaces no card-number field, so it is accepted for contract stability and
     *     may be {@code null}.
     * :returns: a populated {@link AccountViewResponseDto}.
     */
    public AccountViewResponseDto toViewResponse(Account account, Customer customer, CardXref cardXref) {
        AccountViewResponseDto response = new AccountViewResponseDto();
        applyAccountToView(account, response);
        applyCustomerToView(customer, response);
        return response;
    }

    /**
     * :purpose: Assemble the post-update echo response reflecting the freshly persisted
     *     account and customer state, using the same field set as the view response (``COACTUPC``
     *     confirmation). Monetary fields are carried as ``BigDecimal`` at scale 2; sensitive
     *     customer fields are copied verbatim for the DTO layer to mask. Pure field copy with no
     *     business logic.
     * :param account: the persisted account entity to read; account fields are copied only
     *     when it is non-{@code null}.
     * :param customer: the persisted customer entity to read; customer fields are copied only
     *     when it is non-{@code null}.
     * :param cardXref: the resolved card cross-reference (CVACT03Y) supplying the
     *     card-to-customer-to-account linkage; the account update echo (COACTUPC) surfaces no
     *     card-number field, so it is accepted for contract stability and may be {@code null}.
     * :returns: a populated {@link AccountUpdateResponseDto}.
     */
    public AccountUpdateResponseDto toUpdateResponse(Account account, Customer customer, CardXref cardXref) {
        AccountUpdateResponseDto response = new AccountUpdateResponseDto();
        applyAccountToUpdateResponse(account, response);
        applyCustomerToUpdateResponse(customer, response);
        return response;
    }

    /**
     * :purpose: Apply the editable screen fields carried by an account update request onto the
     *     caller-supplied managed ``Account`` and ``Customer`` entities, re-expressing the
     *     ``COACTUPC`` update-record preparation. The entities are mutated in place so their JPA
     *     ``@Version`` value is retained for optimistic-lock conflict detection; the primary keys
     *     (``acctId`` / ``custId``) and the version are never assigned from the request. Monetary
     *     account fields are normalized to scale 2; all other values are copied as submitted with
     *     no reformatting. Pure field copy with no business logic.
     * :param request: the update request DTO; when {@code null} the method is a no-op.
     * :param account: the managed account entity to mutate; account fields are applied only
     *     when it is non-{@code null}.
     * :param customer: the managed customer entity to mutate; customer fields are applied only
     *     when it is non-{@code null}.
     */
    public void applyUpdate(AccountUpdateRequestDto request, Account account, Customer customer) {
        if (request == null) {
            return;
        }
        if (account != null) {
            // Editable account master fields (COACTUPC ACCT-UPDATE-* preparation);
            // the acctId primary key and the @Version column are intentionally not set.
            account.setAcctActiveStatus(request.getAcctActiveStatus());
            account.setAcctCurrBal(scale2(request.getAcctCurrBal()));
            account.setAcctCreditLimit(scale2(request.getAcctCreditLimit()));
            account.setAcctCashCreditLimit(scale2(request.getAcctCashCreditLimit()));
            account.setAcctCurrCycCredit(scale2(request.getAcctCurrCycCredit()));
            account.setAcctCurrCycDebit(scale2(request.getAcctCurrCycDebit()));
            account.setAcctOpenDate(request.getAcctOpenDate());
            account.setAcctExpiraionDate(request.getAcctExpiraionDate());
            account.setAcctReissueDate(request.getAcctReissueDate());
            // ``ACCT-GROUP-ID PIC X(10)`` has no null: an unset group is SPACES, and the
            // 3270 field returns spaces for an empty box. ``acct_group_id`` IS nullable,
            // so writing the empty string a blank box submits would silently convert a
            // stored NULL into '' on a save that changed nothing else. The absent value
            // keeps its stored representation.
            account.setAcctGroupId(blankToNull(request.getAcctGroupId()));
        }
        if (customer != null) {
            // Editable customer master fields (COACTUPC CUST-UPDATE-* preparation);
            // the custId primary key and the @Version column are intentionally not set.
            customer.setCustFirstName(request.getCustFirstName());
            customer.setCustMiddleName(request.getCustMiddleName());
            customer.setCustLastName(request.getCustLastName());
            customer.setCustAddrLine1(request.getCustAddrLine1());
            customer.setCustAddrLine2(request.getCustAddrLine2());
            customer.setCustAddrLine3(request.getCustAddrLine3());
            customer.setCustAddrStateCd(request.getCustAddrStateCd());
            customer.setCustAddrCountryCd(request.getCustAddrCountryCd());
            customer.setCustAddrZip(request.getCustAddrZip());
            customer.setCustPhoneNum1(request.getCustPhoneNum1());
            customer.setCustPhoneNum2(request.getCustPhoneNum2());
            // A client that received the masked identifier and submitted the record back
            // unchanged must not overwrite the stored value with asterisks; a genuinely
            // edited value is applied as-is (COACTUPC treats both fields as editable).
            customer.setCustSsn(retainWhenMasked(request.getCustSsn(), customer.getCustSsn()));
            customer.setCustGovtIssuedId(
                    retainWhenMasked(request.getCustGovtIssuedId(), customer.getCustGovtIssuedId()));
            customer.setCustDobYyyyMmDd(request.getCustDobYyyyMmDd());
            // The EFT account id is masked in BOTH responses this mapper builds, so it needs
            // the same retention as the SSN and the government-issued id; writing it raw
            // would replace the stored identifier with its own mask.
            customer.setCustEftAccountId(
                    retainWhenMasked(request.getCustEftAccountId(), customer.getCustEftAccountId()));
            customer.setCustPriCardHolderInd(request.getCustPriCardHolderInd());
            customer.setCustFicoCreditScore(request.getCustFicoCreditScore());
        }
    }

    /**
     * :purpose: Copy the account master fields into the view response DTO.
     * :param account: the source account entity; a no-op when {@code null}.
     * :param response: the view response DTO to populate.
     */
    private void applyAccountToView(Account account, AccountViewResponseDto response) {
        if (account == null) {
            return;
        }
        // Account master fields (COACTVWC CACTVWA assembly).
        // The optimistic-lock version travels with the record so the client can echo it
        // back on update; it is the snapshot half of the read-snapshot-compare-rewrite.
        response.setVersion(account.getVersion());
        response.setAcctId(account.getAcctId());
        response.setAcctActiveStatus(account.getAcctActiveStatus());
        response.setAcctCurrBal(scale2(account.getAcctCurrBal()));
        response.setAcctCreditLimit(scale2(account.getAcctCreditLimit()));
        response.setAcctCashCreditLimit(scale2(account.getAcctCashCreditLimit()));
        response.setAcctCurrCycCredit(scale2(account.getAcctCurrCycCredit()));
        response.setAcctCurrCycDebit(scale2(account.getAcctCurrCycDebit()));
        response.setAcctOpenDate(account.getAcctOpenDate());
        response.setAcctExpiraionDate(account.getAcctExpiraionDate());
        response.setAcctReissueDate(account.getAcctReissueDate());
        response.setAcctGroupId(account.getAcctGroupId());
    }

    /**
     * :purpose: Copy the customer master fields into the view response DTO. The two
     *   regulated identifiers (SSN, government-issued id) leave the service masked
     *   (AAP 0.6.7); every other field is copied verbatim.
     * :param customer: the source customer entity; a no-op when {@code null}.
     * :param response: the view response DTO to populate.
     */
    private void applyCustomerToView(Customer customer, AccountViewResponseDto response) {
        if (customer == null) {
            return;
        }
        // Customer master fields (COACTVWC CACTVWA assembly); values copied raw.
        response.setCustId(customer.getCustId());
        response.setCustFirstName(customer.getCustFirstName());
        response.setCustMiddleName(customer.getCustMiddleName());
        response.setCustLastName(customer.getCustLastName());
        response.setCustAddrLine1(customer.getCustAddrLine1());
        response.setCustAddrLine2(customer.getCustAddrLine2());
        response.setCustAddrLine3(customer.getCustAddrLine3());
        response.setCustAddrStateCd(customer.getCustAddrStateCd());
        response.setCustAddrZip(customer.getCustAddrZip());
        response.setCustAddrCountryCd(customer.getCustAddrCountryCd());
        response.setCustPhoneNum1(customer.getCustPhoneNum1());
        response.setCustPhoneNum2(customer.getCustPhoneNum2());
        // AAP 0.6.7 -- the social security number, government issued id and EFT account
        // id are masked before they leave the service boundary; only the trailing four
        // characters survive so the field the 3270 screen displayed is still present.
        response.setCustSsn(PiiMasker.maskSsn(customer.getCustSsn()));
        response.setCustGovtIssuedId(PiiMasker.maskIdentifier(customer.getCustGovtIssuedId()));
        response.setCustDobYyyyMmDd(customer.getCustDobYyyyMmDd());
        response.setCustEftAccountId(PiiMasker.maskIdentifier(customer.getCustEftAccountId()));
        response.setCustPriCardHolderInd(customer.getCustPriCardHolderInd());
        response.setCustFicoCreditScore(customer.getCustFicoCreditScore());
    }

    /**
     * :purpose: Copy the account master fields into the update echo response DTO.
     * :param account: the source account entity; a no-op when {@code null}.
     * :param response: the update response DTO to populate.
     */
    private void applyAccountToUpdateResponse(Account account, AccountUpdateResponseDto response) {
        if (account == null) {
            return;
        }
        // Account master fields (post-update echo of the persisted ACCTFILE record).
        // The incremented optimistic-lock version is echoed so a subsequent update can be
        // issued without re-reading the record.
        response.setVersion(account.getVersion());
        response.setAcctId(account.getAcctId());
        response.setAcctActiveStatus(account.getAcctActiveStatus());
        response.setAcctCurrBal(scale2(account.getAcctCurrBal()));
        response.setAcctCreditLimit(scale2(account.getAcctCreditLimit()));
        response.setAcctCashCreditLimit(scale2(account.getAcctCashCreditLimit()));
        response.setAcctCurrCycCredit(scale2(account.getAcctCurrCycCredit()));
        response.setAcctCurrCycDebit(scale2(account.getAcctCurrCycDebit()));
        response.setAcctOpenDate(account.getAcctOpenDate());
        response.setAcctExpiraionDate(account.getAcctExpiraionDate());
        response.setAcctReissueDate(account.getAcctReissueDate());
        response.setAcctGroupId(account.getAcctGroupId());
    }

    /**
     * :purpose: Copy the customer master fields into the update echo response DTO.
     *   The two regulated identifiers (SSN, government-issued id) leave the service
     *   masked (AAP 0.6.7); every other field echoes the persisted value verbatim.
     * :param customer: the source customer entity; a no-op when {@code null}.
     * :param response: the update response DTO to populate.
     */
    private void applyCustomerToUpdateResponse(Customer customer, AccountUpdateResponseDto response) {
        if (customer == null) {
            return;
        }
        // Customer master fields (post-update echo of the persisted CUSTFILE record).
        response.setCustId(customer.getCustId());
        response.setCustFirstName(customer.getCustFirstName());
        response.setCustMiddleName(customer.getCustMiddleName());
        response.setCustLastName(customer.getCustLastName());
        response.setCustAddrLine1(customer.getCustAddrLine1());
        response.setCustAddrLine2(customer.getCustAddrLine2());
        response.setCustAddrLine3(customer.getCustAddrLine3());
        response.setCustAddrStateCd(customer.getCustAddrStateCd());
        response.setCustAddrZip(customer.getCustAddrZip());
        response.setCustAddrCountryCd(customer.getCustAddrCountryCd());
        response.setCustPhoneNum1(customer.getCustPhoneNum1());
        response.setCustPhoneNum2(customer.getCustPhoneNum2());
        // AAP 0.6.7 -- the social security number, government issued id and EFT account
        // id are masked before they leave the service boundary; only the trailing four
        // characters survive so the field the 3270 screen displayed is still present.
        response.setCustSsn(PiiMasker.maskSsn(customer.getCustSsn()));
        response.setCustGovtIssuedId(PiiMasker.maskIdentifier(customer.getCustGovtIssuedId()));
        response.setCustDobYyyyMmDd(customer.getCustDobYyyyMmDd());
        response.setCustEftAccountId(PiiMasker.maskIdentifier(customer.getCustEftAccountId()));
        response.setCustPriCardHolderInd(customer.getCustPriCardHolderInd());
        response.setCustFicoCreditScore(customer.getCustFicoCreditScore());
    }

    /**
     * :purpose: Resolve the value to persist for a masked identifier: the stored value
     *   when the client merely echoed a mask back, otherwise the submitted value.
     * :note: Any mask-shaped submission retains the stored value, not only this record's own
     *   mask. A regulated identifier is all digits, so a mask character can only have come
     *   from the view; treating it as "unchanged" makes it impossible for a mismatched echo
     *   to write asterisks into the column, which is the outcome that would destroy data.
     * :param submitted: the identifier carried by the update request.
     * :param stored: the identifier currently persisted on the managed entity.
     * :returns: the value that must end up in the column.
     */
    private static String retainWhenMasked(String submitted, String stored) {
        // Only the stored value's OWN mask is an echo. A mask character in any other value
        // means the operator typed it: for the two numeric identifiers the update was
        // already refused by AccountUpdateValidator.validateMaskedIdentifiers, and for the
        // ``PIC X(20)`` government-issued id an asterisk is a legal character the legacy
        // program would have stored, so the submitted value stands.
        return PiiMasker.isMaskOf(submitted, stored) ? stored : submitted;
    }

    /**
     * :purpose: Normalize a monetary value to scale 2 by truncating toward zero,
     *   preserving the legacy COBOL ``PIC S9(10)V99`` fixed-scale semantics: a
     *   COBOL ``MOVE`` into a ``V99`` receiver carries no ``ROUNDED`` phrase, so
     *   excess fraction digits are dropped rather than rounded.
     * :param value: the monetary amount to normalize; may be {@code null}.
     * :returns: the value scaled to two fraction digits, or {@code null} when the
     *   input is {@code null}.
     */
    private static BigDecimal scale2(BigDecimal value) {
        return value == null ? null : value.setScale(2, RoundingMode.DOWN);
    }

    /**
     * :purpose: Reduce a value carrying nothing but whitespace to {@code null}, so a
     *   nullable text column keeps its absent representation when the screen submits the
     *   spaces a blank 3270 field returns.
     * :param value: the submitted value; may be {@code null}.
     * :returns: the value unchanged, or {@code null} when it is absent or all whitespace.
     */
    private static String blankToNull(String value) {
        return value == null || value.trim().isEmpty() ? null : value;
    }
}
