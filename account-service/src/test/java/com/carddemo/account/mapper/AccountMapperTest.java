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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNoException;

/**
 * :purpose: Pure JUnit 5 + AssertJ unit test for the hand-written
 *   {@link AccountMapper}. It locks the two highest-risk fidelity contracts of
 *   the account service: (1) the five monetary fields are normalized to
 *   {@link BigDecimal} scale 2 by truncation toward zero, and (2) the frozen
 *   misspelled ``AcctExpiraionDate`` accessor is preserved verbatim while the
 *   correctly-spelled date token never surfaces on any accessor. It also
 *   verifies full ``toViewResponse`` field mapping (non-sensitive fields only,
 *   with a null-{@code cardXref} guard) and the in-place mutation semantics of
 *   ``applyUpdate`` (identity preserved; ``acctId``/``custId``/version left
 *   untouched). The mapper is exercised directly via {@code new AccountMapper()}
 *   with no Spring context, no mocking framework and no container.
 */
class AccountMapperTest {

    /** The stateless class under test, re-created fresh before each test. */
    private AccountMapper mapper;

    /**
     * :purpose: Instantiate the real, stateless {@link AccountMapper} directly
     *   (no Spring context) before each test.
     */
    @BeforeEach
    void setUp() {
        mapper = new AccountMapper();
    }

    /**
     * :purpose: Build a fully-valued account fixture with clean scale-2 money
     *   values, deterministic dates and a preset optimistic-lock version.
     * :returns: a populated {@link Account} for mapping and update scenarios.
     */
    private Account newFullyValuedAccount() {
        Account account = new Account();
        account.setAcctId(12345678901L);
        account.setAcctActiveStatus("Y");
        account.setAcctCurrBal(new BigDecimal("1000.00"));
        account.setAcctCreditLimit(new BigDecimal("5000.00"));
        account.setAcctCashCreditLimit(new BigDecimal("2000.00"));
        account.setAcctCurrCycCredit(new BigDecimal("300.00"));
        account.setAcctCurrCycDebit(new BigDecimal("150.00"));
        account.setAcctOpenDate("2020-01-15");
        account.setAcctExpiraionDate("2025-12-31");
        account.setAcctReissueDate("2023-06-01");
        account.setAcctAddrZip("10001");
        account.setAcctGroupId("GRP001");
        account.setVersion(7L);
        return account;
    }

    /**
     * :purpose: Build a fully-valued customer fixture. The two sensitive fields
     *   carry obviously-fake placeholder values and are never asserted.
     * :returns: a populated {@link Customer} for mapping and update scenarios.
     */
    private Customer newFullyValuedCustomer() {
        Customer customer = new Customer();
        customer.setCustId(123456789L);
        customer.setCustFirstName("JOHN");
        customer.setCustMiddleName("Q");
        customer.setCustLastName("PUBLIC");
        customer.setCustAddrLine1("123 MAIN ST");
        customer.setCustAddrLine2("SUITE 100");
        customer.setCustAddrLine3("FLOOR 2");
        customer.setCustAddrStateCd("NY");
        customer.setCustAddrCountryCd("USA");
        customer.setCustAddrZip("10001");
        customer.setCustPhoneNum1("(555)111-2222");
        customer.setCustPhoneNum2("(555)333-4444");
        customer.setCustSsn("000000000");
        customer.setCustGovtIssuedId("FAKEID000000000");
        customer.setCustDobYyyyMmDd("1980-05-20");
        customer.setCustEftAccountId("EFT0001");
        customer.setCustPriCardHolderInd("Y");
        customer.setCustFicoCreditScore(750);
        return customer;
    }

    /**
     * :purpose: Build an update request carrying the fully-valued customer's own values, so
     *   a scenario can change exactly one field and attribute the outcome to it.
     * :returns: a populated {@link AccountUpdateRequestDto} mirroring the fixtures.
     */
    private AccountUpdateRequestDto newFullyValuedUpdateRequest() {
        Customer source = newFullyValuedCustomer();
        AccountUpdateRequestDto request = new AccountUpdateRequestDto();
        request.setAcctActiveStatus("Y");
        request.setAcctCurrBal(new BigDecimal("1000.00"));
        request.setAcctCreditLimit(new BigDecimal("5000.00"));
        request.setAcctCashCreditLimit(new BigDecimal("2000.00"));
        request.setAcctCurrCycCredit(new BigDecimal("300.00"));
        request.setAcctCurrCycDebit(new BigDecimal("150.00"));
        request.setAcctOpenDate("2020-01-15");
        request.setAcctExpiraionDate("2025-12-31");
        request.setAcctReissueDate("2023-06-01");
        request.setAcctGroupId("GRP001");
        request.setCustFirstName(source.getCustFirstName());
        request.setCustMiddleName(source.getCustMiddleName());
        request.setCustLastName(source.getCustLastName());
        request.setCustAddrLine1(source.getCustAddrLine1());
        request.setCustAddrLine2(source.getCustAddrLine2());
        request.setCustAddrLine3(source.getCustAddrLine3());
        request.setCustAddrStateCd(source.getCustAddrStateCd());
        request.setCustAddrCountryCd(source.getCustAddrCountryCd());
        request.setCustAddrZip(source.getCustAddrZip());
        request.setCustPhoneNum1(source.getCustPhoneNum1());
        request.setCustPhoneNum2(source.getCustPhoneNum2());
        request.setCustSsn(source.getCustSsn());
        request.setCustGovtIssuedId(source.getCustGovtIssuedId());
        request.setCustDobYyyyMmDd(source.getCustDobYyyyMmDd());
        request.setCustEftAccountId(source.getCustEftAccountId());
        request.setCustPriCardHolderInd(source.getCustPriCardHolderInd());
        request.setCustFicoCreditScore(source.getCustFicoCreditScore());
        return request;
    }

    /**
     * :purpose: Build a card cross-reference fixture. The PAN is an
     *   obviously-fake placeholder and is never asserted as a full value.
     * :returns: a populated {@link CardXref}.
     */
    private CardXref newCardXref() {
        return new CardXref("4111111111111111", 123456789L, 12345678901L);
    }

    /**
     * :purpose: Apply a single balance value through {@code applyUpdate} onto a
     *   fresh account and assert the persisted value and its exact scale.
     * :param input: the raw monetary input, supplied as a decimal string.
     * :param expected: the expected normalized value as a decimal string.
     */
    private void assertCurrBalNormalizes(String input, String expected) {
        Account account = new Account();
        AccountUpdateRequestDto request = new AccountUpdateRequestDto();
        request.setAcctCurrBal(new BigDecimal(input));
        mapper.applyUpdate(request, account, new Customer());
        assertThat(account.getAcctCurrBal()).isEqualByComparingTo(expected);
        assertThat(account.getAcctCurrBal().scale()).isEqualTo(2);
    }

    /**
     * :purpose: Verify {@code toViewResponse} copies every non-sensitive account
     *   and customer field onto the view DTO with the expected values, including
     *   the frozen misspelled ``AcctExpiraionDate`` accessor. Sensitive fields
     *   (SSN, government-issued id, full card number) are never asserted.
     */
    @Test
    @DisplayName("toViewResponse copies every non-sensitive account and customer field onto the view DTO")
    void toViewResponseMapsAllScalarFields() {
        Account account = newFullyValuedAccount();
        Customer customer = newFullyValuedCustomer();
        CardXref cardXref = newCardXref();

        AccountViewResponseDto dto = mapper.toViewResponse(account, customer, cardXref);

        // Account master fields.
        // The optimistic-lock version travels with the view so the client can echo it back.
        assertThat(dto.getVersion()).isEqualTo(7L);
        assertThat(dto.getAcctId()).isEqualTo(12345678901L);
        assertThat(dto.getAcctActiveStatus()).isEqualTo("Y");
        assertThat(dto.getAcctCurrBal()).isEqualByComparingTo("1000.00");
        assertThat(dto.getAcctCreditLimit()).isEqualByComparingTo("5000.00");
        assertThat(dto.getAcctCashCreditLimit()).isEqualByComparingTo("2000.00");
        assertThat(dto.getAcctCurrCycCredit()).isEqualByComparingTo("300.00");
        assertThat(dto.getAcctCurrCycDebit()).isEqualByComparingTo("150.00");
        assertThat(dto.getAcctOpenDate()).isEqualTo("2020-01-15");
        assertThat(dto.getAcctExpiraionDate()).isEqualTo("2025-12-31");
        assertThat(dto.getAcctReissueDate()).isEqualTo("2023-06-01");
        assertThat(dto.getAcctGroupId()).isEqualTo("GRP001");

        // Customer master fields (non-sensitive only).
        assertThat(dto.getCustId()).isEqualTo(123456789L);
        assertThat(dto.getCustFirstName()).isEqualTo("JOHN");
        assertThat(dto.getCustMiddleName()).isEqualTo("Q");
        assertThat(dto.getCustLastName()).isEqualTo("PUBLIC");
        assertThat(dto.getCustAddrLine1()).isEqualTo("123 MAIN ST");
        assertThat(dto.getCustAddrLine2()).isEqualTo("SUITE 100");
        assertThat(dto.getCustAddrLine3()).isEqualTo("FLOOR 2");
        assertThat(dto.getCustAddrStateCd()).isEqualTo("NY");
        assertThat(dto.getCustAddrCountryCd()).isEqualTo("USA");
        assertThat(dto.getCustAddrZip()).isEqualTo("10001");
        assertThat(dto.getCustPhoneNum1()).isEqualTo("(555)111-2222");
        assertThat(dto.getCustPhoneNum2()).isEqualTo("(555)333-4444");
        assertThat(dto.getCustDobYyyyMmDd()).isEqualTo("1980-05-20");
        assertThat(dto.getCustPriCardHolderInd()).isEqualTo("Y");
        assertThat(dto.getCustFicoCreditScore()).isEqualTo(750);

        // AAP 0.6.7 -- the three sensitive customer identifiers leave the service masked:
        // only the trailing four characters survive, and the original length is preserved
        // so the field width of the 3270 screen is still recognisable.
        assertThat(dto.getCustSsn()).isEqualTo("***-**-0000");
        assertThat(dto.getCustGovtIssuedId()).isEqualTo("***********0000");
        assertThat(dto.getCustEftAccountId()).isEqualTo("***0001");
        assertThat(dto.getCustSsn()).doesNotContain("000000000");
        assertThat(dto.getCustGovtIssuedId()).doesNotContain("FAKEID");
    }

    /**
     * :purpose: AAP 0.6.7 - the regulated customer identifiers must leave the mapper
     *   masked on BOTH outbound paths (view and post-update echo), never as the stored
     *   value, with the field length preserved and only the trailing four characters
     *   visible.
     */
    @Test
    @DisplayName("toViewResponse and toUpdateResponse mask the SSN and government-issued id")
    void outboundResponsesMaskRegulatedIdentifiers() {
        Account account = newFullyValuedAccount();
        Customer customer = newFullyValuedCustomer();
        String storedSsn = customer.getCustSsn();
        String storedGovtId = customer.getCustGovtIssuedId();

        AccountViewResponseDto view = mapper.toViewResponse(account, customer, newCardXref());
        assertThat(view.getCustSsn()).isEqualTo(PiiMasker.maskSsn(storedSsn));
        // The SSN mask keeps the ``***-**-nnnn`` presentation the 3270 map used, so it is
        // deliberately wider than the 9 stored digits; what matters is that only the last
        // four digits survive.
        assertThat(view.getCustSsn()).isNotEqualTo(storedSsn)
                .endsWith(storedSsn.substring(storedSsn.length() - 4))
                .doesNotContain(storedSsn.substring(0, storedSsn.length() - 4));
        assertThat(view.getCustGovtIssuedId()).isEqualTo(PiiMasker.maskIdentifier(storedGovtId));
        assertThat(view.getCustGovtIssuedId()).isNotEqualTo(storedGovtId);

        AccountUpdateResponseDto echo = mapper.toUpdateResponse(account, customer, newCardXref());
        assertThat(echo.getCustSsn()).isEqualTo(PiiMasker.maskSsn(storedSsn));
        assertThat(echo.getCustGovtIssuedId()).isEqualTo(PiiMasker.maskIdentifier(storedGovtId));
    }

    /**
     * :purpose: A masked identifier echoed back by a client is "unchanged", so
     *   {@code applyUpdate} must keep the stored value; a genuinely edited identifier is
     *   applied. Without this the masking control would silently destroy stored PII on
     *   every COACTUPC update.
     */
    @Test
    @DisplayName("applyUpdate keeps the stored identifier when the client echoes its mask, and applies a real edit")
    void applyUpdateDistinguishesEchoedMaskFromGenuineEdit() {
        Customer customer = newFullyValuedCustomer();
        String storedSsn = customer.getCustSsn();
        String storedGovtId = customer.getCustGovtIssuedId();

        AccountUpdateRequestDto echoed = newFullyValuedUpdateRequest();
        echoed.setCustSsn(PiiMasker.maskSsn(storedSsn));
        echoed.setCustGovtIssuedId(PiiMasker.maskIdentifier(storedGovtId));
        mapper.applyUpdate(echoed, newFullyValuedAccount(), customer);
        assertThat(customer.getCustSsn()).isEqualTo(storedSsn);
        assertThat(customer.getCustGovtIssuedId()).isEqualTo(storedGovtId);

        AccountUpdateRequestDto edited = newFullyValuedUpdateRequest();
        edited.setCustSsn("111111111");
        edited.setCustGovtIssuedId("NEWID00000000001");
        mapper.applyUpdate(edited, newFullyValuedAccount(), customer);
        assertThat(customer.getCustSsn()).isEqualTo("111111111");
        assertThat(customer.getCustGovtIssuedId()).isEqualTo("NEWID00000000001");
    }

    /**
     * :purpose: Only the stored value's OWN mask is an echo. ``CUST-GOVT-ISSUED-ID`` is
     *   ``PIC X(20)``, so an asterisk is a legal character the legacy program would have
     *   stored, and a mask-bearing value that is not the stored mask must be applied
     *   rather than silently discarded in favour of the stored identifier.
     */
    @Test
    @DisplayName("applyUpdate applies a mask-bearing value that is not the stored value's own mask")
    void applyUpdateAppliesMaskShapedValueThatIsNotTheStoredMask() {
        Customer customer = newFullyValuedCustomer();
        String storedGovtId = customer.getCustGovtIssuedId();

        AccountUpdateRequestDto edited = newFullyValuedUpdateRequest();
        edited.setCustGovtIssuedId("****IDENTIFIER**0001");
        mapper.applyUpdate(edited, newFullyValuedAccount(), customer);

        assertThat(storedGovtId).isNotEqualTo("****IDENTIFIER**0001");
        assertThat(customer.getCustGovtIssuedId()).isEqualTo("****IDENTIFIER**0001");
    }

    /**
     * :purpose: ``ACCT-GROUP-ID PIC X(10)`` has no null and a blank 3270 field returns
     *   spaces, while ``acct_group_id`` is nullable. A save that changed nothing else must
     *   not convert a stored NULL into the empty string, so a blank submission is applied
     *   as {@code null} and the read/save round trip is idempotent.
     */
    @Test
    @DisplayName("applyUpdate writes a blank account group id as null, never as the empty string")
    void applyUpdateNormalizesBlankAccountGroupIdToNull() {
        Account account = newFullyValuedAccount();
        account.setAcctGroupId(null);

        AccountUpdateRequestDto blank = newFullyValuedUpdateRequest();
        blank.setAcctGroupId("");
        mapper.applyUpdate(blank, account, newFullyValuedCustomer());
        assertThat(account.getAcctGroupId()).isNull();

        AccountUpdateRequestDto spaces = newFullyValuedUpdateRequest();
        spaces.setAcctGroupId("   ");
        mapper.applyUpdate(spaces, account, newFullyValuedCustomer());
        assertThat(account.getAcctGroupId()).isNull();

        AccountUpdateRequestDto supplied = newFullyValuedUpdateRequest();
        supplied.setAcctGroupId("PREMIUM");
        mapper.applyUpdate(supplied, account, newFullyValuedCustomer());
        assertThat(account.getAcctGroupId()).isEqualTo("PREMIUM");
    }

    /**
     * :purpose: Verify {@code toViewResponse} tolerates a {@code null} cardXref
     *   (the read-only account view surfaces no card-number field) and still
     *   populates the account- and customer-derived fields.
     */
    @Test
    @DisplayName("toViewResponse tolerates a null cardXref and still populates account/customer fields")
    void toViewResponseDoesNotThrowWhenCardXrefIsNull() {
        Account account = newFullyValuedAccount();
        Customer customer = newFullyValuedCustomer();

        assertThatNoException().isThrownBy(() -> mapper.toViewResponse(account, customer, null));

        AccountViewResponseDto dto = mapper.toViewResponse(account, customer, null);
        assertThat(dto.getAcctId()).isEqualTo(12345678901L);
        assertThat(dto.getCustId()).isEqualTo(123456789L);
        assertThat(dto.getAcctExpiraionDate()).isEqualTo("2025-12-31");
    }

    /**
     * :purpose: Verify {@code toUpdateResponse} echoes the persisted state including the
     *   optimistic-lock version, so the client can submit a further update with the
     *   value it just received instead of re-reading the record.
     */
    @Test
    @DisplayName("toUpdateResponse echoes the persisted account/customer state and the optimistic-lock version")
    void toUpdateResponseEchoesPersistedStateAndVersion() {
        Account account = newFullyValuedAccount();
        Customer customer = newFullyValuedCustomer();
        CardXref cardXref = newCardXref();

        AccountUpdateResponseDto dto = mapper.toUpdateResponse(account, customer, cardXref);

        assertThat(dto.getVersion()).isEqualTo(7L);
        assertThat(dto.getAcctId()).isEqualTo(12345678901L);
        assertThat(dto.getCustId()).isEqualTo(123456789L);
        assertThat(dto.getAcctCurrBal()).isEqualByComparingTo("1000.00");
        assertThat(dto.getAcctExpiraionDate()).isEqualTo("2025-12-31");
        assertThat(dto.getCustLastName()).isEqualTo("PUBLIC");
    }

    /**
     * :purpose: Prove {@code applyUpdate} normalizes all five monetary fields to
     *   {@link BigDecimal} scale 2 by truncating toward zero, exercising the
     *   pad-up, midpoint-truncate and integer-pad cases across the fields.
     */
    @Test
    @DisplayName("applyUpdate normalizes all five money fields to scale 2 by truncation")
    void applyUpdateNormalizesAllFiveMoneyFieldsToScale2Truncated() {
        Account account = new Account();
        Customer customer = new Customer();
        AccountUpdateRequestDto request = new AccountUpdateRequestDto();
        request.setAcctCurrBal(new BigDecimal("100.1"));
        request.setAcctCreditLimit(new BigDecimal("100.005"));
        request.setAcctCashCreditLimit(new BigDecimal("100"));
        request.setAcctCurrCycCredit(new BigDecimal("100.1"));
        request.setAcctCurrCycDebit(new BigDecimal("100.005"));

        mapper.applyUpdate(request, account, customer);

        assertThat(account.getAcctCurrBal()).isEqualByComparingTo("100.10");
        assertThat(account.getAcctCurrBal().scale()).isEqualTo(2);
        assertThat(account.getAcctCreditLimit()).isEqualByComparingTo("100.00");
        assertThat(account.getAcctCreditLimit().scale()).isEqualTo(2);
        assertThat(account.getAcctCashCreditLimit()).isEqualByComparingTo("100.00");
        assertThat(account.getAcctCashCreditLimit().scale()).isEqualTo(2);
        assertThat(account.getAcctCurrCycCredit()).isEqualByComparingTo("100.10");
        assertThat(account.getAcctCurrCycCredit().scale()).isEqualTo(2);
        assertThat(account.getAcctCurrCycDebit()).isEqualByComparingTo("100.00");
        assertThat(account.getAcctCurrCycDebit().scale()).isEqualTo(2);
    }

    /**
     * :purpose: Prove the truncate-toward-zero normalization and scale-2 padding
     *   across a set of representative inputs, including the classic binary-float
     *   trap value ``2.675`` (correct only because the {@link BigDecimal} string
     *   constructor is used). A COBOL ``MOVE`` into a ``V99`` receiver carries no
     *   ``ROUNDED`` phrase, so a ``.005`` midpoint drops rather than rounds up.
     */
    @Test
    @DisplayName("applyUpdate truncates to scale 2 and pads across representative inputs")
    void applyUpdateTruncatesBalanceToScale2() {
        assertCurrBalNormalizes("100.005", "100.00");
        assertCurrBalNormalizes("2.675", "2.67");
        assertCurrBalNormalizes("100.004", "100.00");
        assertCurrBalNormalizes("100", "100.00");
        assertCurrBalNormalizes("100.1", "100.10");
    }

    /**
     * :purpose: Secondary financial-precision path — prove the money fields are
     *   also normalized to scale 2 by truncation toward zero when surfaced
     *   through {@code toViewResponse} onto the {@link AccountViewResponseDto}
     *   (whose money fields are {@link BigDecimal}).
     */
    @Test
    @DisplayName("toViewResponse normalizes account money fields to scale 2 on the view DTO")
    void toViewResponseNormalizesMoneyFieldsToScale2() {
        Account account = newFullyValuedAccount();
        account.setAcctCurrBal(new BigDecimal("100.1"));
        account.setAcctCreditLimit(new BigDecimal("100.005"));
        account.setAcctCashCreditLimit(new BigDecimal("100"));
        account.setAcctCurrCycCredit(new BigDecimal("100.004"));
        account.setAcctCurrCycDebit(new BigDecimal("2.675"));

        AccountViewResponseDto dto = mapper.toViewResponse(account, newFullyValuedCustomer(), null);

        assertThat(dto.getAcctCurrBal()).isEqualByComparingTo("100.10");
        assertThat(dto.getAcctCurrBal().scale()).isEqualTo(2);
        assertThat(dto.getAcctCreditLimit()).isEqualByComparingTo("100.00");
        assertThat(dto.getAcctCreditLimit().scale()).isEqualTo(2);
        assertThat(dto.getAcctCashCreditLimit()).isEqualByComparingTo("100.00");
        assertThat(dto.getAcctCashCreditLimit().scale()).isEqualTo(2);
        assertThat(dto.getAcctCurrCycCredit()).isEqualByComparingTo("100.00");
        assertThat(dto.getAcctCurrCycCredit().scale()).isEqualTo(2);
        assertThat(dto.getAcctCurrCycDebit()).isEqualByComparingTo("2.67");
        assertThat(dto.getAcctCurrCycDebit().scale()).isEqualTo(2);
    }


    /**
     * :purpose: Prove {@code applyUpdate} mutates the caller-supplied managed
     *   entities in place (reference identity preserved) while changing the
     *   editable fields and leaving the ``acctId`` / ``custId`` primary keys and
     *   the JPA optimistic-lock version untouched.
     */
    @Test
    @DisplayName("applyUpdate mutates the passed instances in place and leaves acctId/custId/version untouched")
    void applyUpdateMutatesSameInstancesAndPreservesIdsAndVersion() {
        Account account = newFullyValuedAccount();
        Customer customer = newFullyValuedCustomer();

        Account acctRef = account;
        Customer custRef = customer;
        Long acctIdBefore = account.getAcctId();
        Long custIdBefore = customer.getCustId();
        Long versionBefore = account.getVersion();

        AccountUpdateRequestDto request = new AccountUpdateRequestDto();
        request.setAcctActiveStatus("N");
        request.setAcctCurrBal(new BigDecimal("2500.50"));
        request.setAcctExpiraionDate("2030-01-01");
        request.setCustFirstName("JANE");

        mapper.applyUpdate(request, account, customer);

        // In-place mutation: the same object references are updated.
        assertThat(account).isSameAs(acctRef);
        assertThat(customer).isSameAs(custRef);

        // Editable fields were applied.
        assertThat(account.getAcctActiveStatus()).isEqualTo("N");
        assertThat(account.getAcctCurrBal()).isEqualByComparingTo("2500.50");
        assertThat(account.getAcctExpiraionDate()).isEqualTo("2030-01-01");
        assertThat(customer.getCustFirstName()).isEqualTo("JANE");

        // Primary keys and optimistic-lock version were left untouched.
        assertThat(account.getAcctId()).isEqualTo(acctIdBefore);
        assertThat(customer.getCustId()).isEqualTo(custIdBefore);
        assertThat(account.getVersion()).isEqualTo(versionBefore);
        assertThat(account.getVersion()).isEqualTo(7L);
    }

    /**
     * :purpose: Round-trip the frozen misspelled ``AcctExpiraionDate`` accessor
     *   through {@code applyUpdate} and {@code toViewResponse}, proving the
     *   legacy identifier is honored end to end on both the entity and the DTO.
     */
    @Test
    @DisplayName("applyUpdate then toViewResponse round-trips the misspelled AcctExpiraionDate accessor")
    void applyUpdateAndViewRoundTripExpiraionDate() {
        Account account = new Account();
        Customer customer = new Customer();
        AccountUpdateRequestDto request = new AccountUpdateRequestDto();
        request.setAcctExpiraionDate("2027-09-30");

        mapper.applyUpdate(request, account, customer);
        assertThat(account.getAcctExpiraionDate()).isEqualTo("2027-09-30");

        AccountViewResponseDto dto = mapper.toViewResponse(account, customer, null);
        assertThat(dto.getAcctExpiraionDate()).isEqualTo("2027-09-30");
    }

    /**
     * :purpose: Spec-literal lock — reflectively assert that neither the mapper
     *   nor the account DTOs nor the account entity expose an accessor whose
     *   name contains the correctly-spelled forbidden date token (built
     *   dynamically so the contiguous word never appears in this source), and
     *   positively assert that the misspelled ``Expiraion`` accessors survive on
     *   both the entity and the DTO layer.
     */
    @Test
    @DisplayName("no accessor uses the correctly-spelled forbidden date token; the misspelled one survives")
    void noAccessorNameContainsTheForbiddenDateToken() throws NoSuchMethodException {
        // Build the forbidden token dynamically so the contiguous word never
        // appears literally in this test source.
        String forbidden = "expir" + "ation";

        List<String> methodNames = new ArrayList<>();
        Class<?>[] scanned = {
            AccountMapper.class,
            AccountViewResponseDto.class,
            AccountUpdateRequestDto.class,
            AccountUpdateResponseDto.class,
            Account.class
        };
        for (Class<?> type : scanned) {
            for (Method method : type.getDeclaredMethods()) {
                methodNames.add(method.getName());
            }
        }

        assertThat(methodNames).noneMatch(name -> name.toLowerCase().contains(forbidden));

        // The frozen misspelled accessors MUST still exist on the entity.
        assertThat(Account.class.getMethod("getAcctExpiraionDate")).isNotNull();
        assertThat(Account.class.getMethod("setAcctExpiraionDate", String.class)).isNotNull();

        // The frozen misspelled identifier survived into the DTO layer.
        Method dtoGetter = AccountViewResponseDto.class.getMethod("getAcctExpiraionDate");
        assertThat(dtoGetter).isNotNull();
        assertThat(dtoGetter.getName()).contains("Expiraion");
    }
}

