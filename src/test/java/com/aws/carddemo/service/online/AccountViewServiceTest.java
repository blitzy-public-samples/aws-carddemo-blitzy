/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License").
 * You may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.aws.carddemo.service.online;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.aws.carddemo.domain.Account;
import com.aws.carddemo.domain.CardXref;
import com.aws.carddemo.domain.Customer;
import com.aws.carddemo.dto.CardDemoContext;
import com.aws.carddemo.dto.CardWorkArea;
import com.aws.carddemo.dto.CardWorkArea.PfKey;
import com.aws.carddemo.dto.screen.COACTVWForm;
import com.aws.carddemo.exception.RecordNotFoundException;
import com.aws.carddemo.repository.AccountRepository;
import com.aws.carddemo.repository.CardXrefRepository;
import com.aws.carddemo.repository.CustomerRepository;
import com.aws.carddemo.service.online.AccountViewService.AccountViewResult;
import com.aws.carddemo.service.online.AccountViewService.RoutingAction;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Pure-Mockito unit tests for {@link AccountViewService}.
 *
 * <p><b>Origin / parity oracle (read-only):</b> {@code legacy/cbl/COACTVWC.cbl}
 * (CICS COBOL program {@code COACTVWC}, transaction id {@code CAVW}) &mdash; the
 * read-only online "Account View" program. These tests assert one-for-one
 * control-flow parity with the numbered business paragraphs of the oracle that
 * the service migrates (AAP &sect;0.1.2 / &sect;0.4.1 / &sect;0.6.10):</p>
 * <ul>
 *   <li>{@code 0000-MAIN} &rarr; {@link AccountViewService#mainEntry} &mdash; the
 *       pseudo-conversational state machine (PF-key remap, the {@code PF3} hand-off,
 *       the enter/re-enter {@code EVALUATE} branches, and the {@code WHEN OTHER}
 *       abend path).</li>
 *   <li>{@code 2000-PROCESS-INPUTS} &rarr; {@link AccountViewService#processInputs}
 *       &mdash; the input-edit orchestration on re-entry.</li>
 *   <li>{@code 2200-EDIT-MAP-INPUTS} &rarr; {@link AccountViewService#editMapInputs}
 *       &mdash; account-filter normalization ({@code '*'}/spaces &rarr; "no filter")
 *       and the cross-field "No input received" override.</li>
 *   <li>{@code 2210-EDIT-ACCOUNT} &rarr; {@link AccountViewService#editAccount}
 *       &mdash; the {@code PIC 9(11)} non-zero numeric account-id edit and the exact
 *       message literals.</li>
 *   <li>{@code 9000-READ-ACCT} &rarr; {@link AccountViewService#readAcct} &mdash; the
 *       three-file alternate-index read chain and the screen data moves.</li>
 *   <li>{@code 9200-GETCARDXREF-BYACCT} &rarr;
 *       {@link AccountViewService#getCardXrefByAccount} &mdash; the {@code CXACAIX}
 *       alternate-index cross-reference read.</li>
 *   <li>{@code 9300-GETACCTDATA-BYACCT} &rarr;
 *       {@link AccountViewService#getAcctDataByAccount} &mdash; the account-master
 *       primary-key read.</li>
 *   <li>{@code 9400-GETCUSTDATA-BYCUST} &rarr;
 *       {@link AccountViewService#getCustDataByCust} &mdash; the customer-master
 *       primary-key read.</li>
 * </ul>
 *
 * <p><b>Test tier:</b> a strict-stubs pure-Mockito unit test
 * ({@link MockitoExtension}); it uses no database, no Spring context, and no
 * Testcontainers. The session context ({@code COMMAREA} replacement) and the three
 * repositories are mocked, and the service is exercised in isolation.</p>
 *
 * <p><b>Read-only guarantee (AAP &sect;0.4.1):</b> {@code COACTVWC} never writes,
 * so these tests assert &mdash; via {@link #verifyNoWrites()} and
 * {@code verifyNoInteractions} &mdash; that no {@code save}/{@code delete} is ever
 * issued against any repository.</p>
 *
 * <p><b>Decimal fidelity (AAP &sect;0.6.1):</b> monetary values flow through the
 * service as {@link BigDecimal} (never {@code float}/{@code double}); the account
 * read test asserts both the {@link BigDecimal} type and the value-faithful display
 * string.</p>
 */
@ExtendWith(MockitoExtension.class)
class AccountViewServiceTest {

    // --- Program identity (COACTVWC / CAVW) and menu fallback --------------

    /** COBOL {@code LIT-THISTRANID}; recorded as the origin transaction id on PF3. */
    private static final String THIS_TRANID = "CAVW";

    /** COBOL {@code LIT-THISPGM}; recorded as the origin program on PF3. */
    private static final String THIS_PROGRAM = "COACTVWC";

    /** COBOL {@code LIT-THISMAPSET}; recorded as the last mapset on PF3. */
    private static final String THIS_MAPSET = "COACTVW";

    /** COBOL {@code LIT-THISMAP}; recorded as the last map on PF3. */
    private static final String THIS_MAP = "CACTVWA";

    /** COBOL {@code LIT-MENUTRANID}; PF3 fallback target when no caller is recorded. */
    private static final String MENU_TRANID = "CM00";

    /** COBOL {@code LIT-MENUPGM}; PF3 fallback target when no caller is recorded. */
    private static final String MENU_PROGRAM = "COMEN01C";

    /** An example recorded caller transaction id (card list, {@code CCLI}). */
    private static final String CALLER_TRANID = "CCLI";

    /** An example recorded caller program (card list, {@code COCRDLIC}). */
    private static final String CALLER_PROGRAM = "COCRDLIC";

    // --- Exact oracle message literals (COACTVWC.cbl / AccountViewService) --

    /** {@code WS-PROMPT-FOR-INPUT} informational prompt (COACTVWC.cbl L113-114). */
    private static final String PROMPT_FOR_INPUT = "Enter or update id of account to display";

    /** {@code WS-PROMPT-FOR-ACCT} "not supplied" message from 2210-EDIT-ACCOUNT (L121-122). */
    private static final String ACCT_NOT_PROVIDED = "Account number not provided";

    /** {@code NO-SEARCH-CRITERIA-RECEIVED} cross-field message (L123-124). */
    private static final String NO_INPUT_RECEIVED = "No input received";

    /**
     * The inline invalid-filter literal executed by 2210-EDIT-ACCOUNT
     * (COACTVWC.cbl L672), reproduced byte-for-byte including the <b>double space</b>
     * between "must" and "be" and the hyphen in "non-zero".
     */
    private static final String ACCT_FILTER_INVALID = "Account Filter must  be a non-zero 11 digit number";

    /** {@code DID-NOT-FIND-ACCT-IN-CARDXREF} (L129-130). */
    private static final String XREF_NOT_FOUND = "Did not find this account in account card xref file";

    /** {@code DID-NOT-FIND-ACCT-IN-ACCTDAT} (L131-132). */
    private static final String ACCT_NOT_FOUND = "Did not find this account in account master file";

    /** {@code DID-NOT-FIND-CUST-IN-CUSTDAT} (L133-134). */
    private static final String CUST_NOT_FOUND = "Did not find associated customer in master file";

    /** {@code WHEN OTHER} abend message from 0000-MAIN (COACTVWC.cbl L379). */
    private static final String UNEXPECTED_DATA_SCENARIO = "UNEXPECTED DATA SCENARIO";

    // --- Fixture identifiers -----------------------------------------------

    /** A valid 11-digit account id ({@code PIC 9(11)}). */
    private static final Long ACCT_ID = 12345678901L;

    /** The 11-character display string for {@link #ACCT_ID}. */
    private static final String ACCT_ID_TEXT = "12345678901";

    /** A valid 9-digit customer id ({@code PIC 9(09)}), discovered from the cross-reference. */
    private static final Long CUST_ID = 987654321L;

    /** A 16-character card number ({@code PIC X(16)}), carried on the cross-reference. */
    private static final String CARD_NUM = "1234567890123456";

    // --- Mocked collaborators ----------------------------------------------

    /** Session-scoped {@code COMMAREA} replacement, mocked so context reads/writes are observable. */
    @Mock
    private CardDemoContext context;

    /** Card cross-reference repository (VSAM {@code CCXREF}; alternate index {@code CXACAIX}). */
    @Mock
    private CardXrefRepository cardXrefRepository;

    /** Account master repository (VSAM {@code ACCTDAT}). */
    @Mock
    private AccountRepository accountRepository;

    /** Customer master repository (VSAM {@code CUSTDAT}). */
    @Mock
    private CustomerRepository customerRepository;

    /** Service under test; Mockito constructor-injects the four mocked collaborators. */
    @InjectMocks
    private AccountViewService service;

    // --- Fixture builders (real in-memory records) -------------------------

    /**
     * Builds a fully populated account-master fixture keyed by {@link #ACCT_ID},
     * with {@link BigDecimal} monetary fields and {@link LocalDate} dates.
     *
     * @return a real {@link Account} instance
     */
    private static Account newAccount() {
        Account account = new Account();
        account.setAcctId(ACCT_ID);
        account.setActiveStatus("Y");
        account.setCurrBal(new BigDecimal("1234.56"));
        account.setCreditLimit(new BigDecimal("5000.00"));
        account.setCashCreditLimit(new BigDecimal("2000.00"));
        account.setCurrCycCredit(new BigDecimal("300.00"));
        account.setCurrCycDebit(new BigDecimal("150.00"));
        account.setOpenDate(LocalDate.of(2020, 1, 15));
        account.setExpiraionDate(LocalDate.of(2027, 12, 31));
        account.setReissueDate(LocalDate.of(2024, 6, 1));
        account.setGroupId("DEFAULT");
        return account;
    }

    /**
     * Builds a fully populated customer-master fixture keyed by {@link #CUST_ID}.
     * Note {@code addrLine3} is deliberately set to a city-like value to exercise
     * the legacy "address line 3 populates the CITY field" quirk.
     *
     * @return a real {@link Customer} instance
     */
    private static Customer newCustomer() {
        Customer customer = new Customer();
        customer.setCustId(CUST_ID);
        customer.setFirstName("JOHN");
        customer.setMiddleName("Q");
        customer.setLastName("PUBLIC");
        customer.setAddrLine1("123 MAIN ST");
        customer.setAddrLine2("APT 4");
        customer.setAddrLine3("SPRINGFIELD");
        customer.setAddrStateCd("IL");
        customer.setAddrCountryCd("USA");
        customer.setAddrZip("62704");
        customer.setPhoneNum1("5551234567");
        customer.setPhoneNum2("5559876543");
        customer.setSsn(123456789L);
        customer.setGovtIssuedId("GOVID123");
        customer.setDateOfBirth(LocalDate.of(1985, 3, 20));
        customer.setEftAccountId("EFT99999");
        customer.setPriCardHolderInd("Y");
        customer.setFicoCreditScore(750);
        return customer;
    }

    /**
     * Builds the cross-reference fixture linking {@link #CARD_NUM} to
     * {@link #CUST_ID} and {@link #ACCT_ID}.
     *
     * @return a real {@link CardXref} instance
     */
    private static CardXref newCardXref() {
        return new CardXref(CARD_NUM, CUST_ID, ACCT_ID);
    }

    /**
     * Builds a screen form carrying the given raw account-id filter ({@code ACCTSIDI}).
     *
     * @param acctsid the raw account-id filter to place on the form
     * @return a populated {@link COACTVWForm}
     */
    private static COACTVWForm formWithAcctId(String acctsid) {
        COACTVWForm form = new COACTVWForm();
        form.setAcctsid(acctsid);
        return form;
    }

    /**
     * Builds a work area carrying the given candidate account id ({@code CC-ACCT-ID}).
     *
     * @param acctId the candidate account-id value
     * @return a populated {@link CardWorkArea}
     */
    private static CardWorkArea workAreaWith(String acctId) {
        CardWorkArea workArea = new CardWorkArea();
        workArea.setAcctId(acctId);
        return workArea;
    }

    /**
     * Asserts the read-only guarantee: no {@code save}/{@code delete}/{@code deleteById}
     * is ever issued against any of the three repositories ({@code COACTVWC} never
     * writes).
     */
    private void verifyNoWrites() {
        verify(cardXrefRepository, never()).save(any());
        verify(cardXrefRepository, never()).delete(any());
        verify(cardXrefRepository, never()).deleteById(any());
        verify(accountRepository, never()).save(any());
        verify(accountRepository, never()).delete(any());
        verify(accountRepository, never()).deleteById(any());
        verify(customerRepository, never()).save(any());
        verify(customerRepository, never()).delete(any());
        verify(customerRepository, never()).deleteById(any());
    }

    // ------------------------------------------------------------------
    // 2210-EDIT-ACCOUNT (checklist item 1): account-id validation
    // ------------------------------------------------------------------

    /**
     * A blank / spaces account filter is "not supplied": 2210-EDIT-ACCOUNT sets the
     * selected account id to zero and returns "Account number not provided", with no
     * repository access.
     */
    @Test
    void editAccount_blankFilter_returnsAccountNotProvided_andSelectsZero() {
        String message = service.editAccount(workAreaWith("   "));

        assertThat(message).isEqualTo(ACCT_NOT_PROVIDED);
        verify(context).setAcctId(0L);
        verifyNoInteractions(cardXrefRepository, accountRepository, customerRepository);
    }

    /**
     * A non-numeric filter is rejected with the exact inline invalid-filter literal
     * (COACTVWC.cbl L672), and the selected account id is set to zero.
     */
    @Test
    void editAccount_nonNumericFilter_returnsInvalidFilterMessage() {
        String message = service.editAccount(workAreaWith("12A45"));

        assertThat(message).isEqualTo(ACCT_FILTER_INVALID);
        verify(context).setAcctId(0L);
        verifyNoInteractions(cardXrefRepository, accountRepository, customerRepository);
    }

    /**
     * An all-zeroes filter is rejected ({@code IS ... ZEROES}) with the invalid-filter
     * literal.
     */
    @Test
    void editAccount_allZeroesFilter_returnsInvalidFilterMessage() {
        String message = service.editAccount(workAreaWith("00000000000"));

        assertThat(message).isEqualTo(ACCT_FILTER_INVALID);
        verify(context).setAcctId(0L);
    }

    /**
     * A value wider than eleven digits exceeds the {@code PIC 9(11)} field and is
     * rejected with the invalid-filter literal.
     */
    @Test
    void editAccount_moreThanElevenDigits_returnsInvalidFilterMessage() {
        String message = service.editAccount(workAreaWith("123456789012"));

        assertThat(message).isEqualTo(ACCT_FILTER_INVALID);
        verify(context).setAcctId(0L);
    }

    /**
     * A valid non-zero eleven-digit filter passes: 2210-EDIT-ACCOUNT stores it as the
     * selected account id ({@code CDEMO-ACCT-ID}) and returns no error.
     */
    @Test
    void editAccount_validElevenDigitFilter_returnsNull_andStoresSelectedAccount() {
        String message = service.editAccount(workAreaWith(ACCT_ID_TEXT));

        assertThat(message).isNull();
        verify(context).setAcctId(ACCT_ID);
        verifyNoInteractions(cardXrefRepository, accountRepository, customerRepository);
    }

    /**
     * A shorter all-digit value does <em>not</em> fill the fixed {@code PIC X(11)}
     * field: the legacy character {@code MOVE ACCTSIDI TO CC-ACCT-ID} leaves trailing
     * spaces, so {@code IS NUMERIC} fails and 2210-EDIT-ACCOUNT (COACTVWC.cbl
     * L666-667) rejects it with the invalid-filter literal - identical to the
     * account-update screen's rule. Parity with the mainframe (and with
     * {@code COACTUPC 1210-EDIT-ACCOUNT}) takes precedence over the earlier
     * zero-filling convenience (QA finding w002 m1).
     */
    @Test
    void editAccount_shorterAllDigitFilter_returnsInvalidFilterMessage() {
        String message = service.editAccount(workAreaWith("123"));

        assertThat(message).isEqualTo(ACCT_FILTER_INVALID);
        verify(context).setAcctId(0L);
    }

    // ------------------------------------------------------------------
    // 2200-EDIT-MAP-INPUTS / 2000-PROCESS-INPUTS (checklist item 1)
    // ------------------------------------------------------------------

    /**
     * 2200-EDIT-MAP-INPUTS treats a blank filter as "no filter" and the cross-field
     * edit overrides the "not provided" message with "No input received".
     */
    @Test
    void editMapInputs_blankFilter_returnsNoInputReceived() {
        String message = service.editMapInputs(formWithAcctId("   "), new CardWorkArea());

        assertThat(message).isEqualTo(NO_INPUT_RECEIVED);
        verify(context).setAcctId(0L);
        verifyNoInteractions(cardXrefRepository, accountRepository, customerRepository);
    }

    /**
     * The wildcard {@code '*'} filter is normalized to "no filter" and likewise yields
     * "No input received".
     */
    @Test
    void editMapInputs_wildcardFilter_returnsNoInputReceived() {
        String message = service.editMapInputs(formWithAcctId("*"), new CardWorkArea());

        assertThat(message).isEqualTo(NO_INPUT_RECEIVED);
        verify(context).setAcctId(0L);
    }

    /**
     * A valid filter passes the field and cross-field edits with no error and stores
     * the selected account id.
     */
    @Test
    void editMapInputs_validFilter_returnsNull_andStoresSelectedAccount() {
        String message = service.editMapInputs(formWithAcctId(ACCT_ID_TEXT), new CardWorkArea());

        assertThat(message).isNull();
        verify(context).setAcctId(ACCT_ID);
        verifyNoInteractions(cardXrefRepository, accountRepository, customerRepository);
    }

    /**
     * 2000-PROCESS-INPUTS delegates to the edits and returns no error for a valid
     * filter, without any repository access.
     */
    @Test
    void processInputs_validFilter_delegatesToEdits_andReturnsNull() {
        String message = service.processInputs(formWithAcctId(ACCT_ID_TEXT));

        assertThat(message).isNull();
        verify(context).setAcctId(ACCT_ID);
        verifyNoInteractions(cardXrefRepository, accountRepository, customerRepository);
    }

    /**
     * 2000-PROCESS-INPUTS surfaces the invalid-filter message for a non-numeric filter,
     * without any repository access.
     */
    @Test
    void processInputs_nonNumericFilter_returnsInvalidFilterMessage() {
        String message = service.processInputs(formWithAcctId("ABC"));

        assertThat(message).isEqualTo(ACCT_FILTER_INVALID);
        verify(context).setAcctId(0L);
        verifyNoInteractions(cardXrefRepository, accountRepository, customerRepository);
    }

    // ------------------------------------------------------------------
    // 9200-GETCARDXREF-BYACCT (checklist items 2 and 3)
    // ------------------------------------------------------------------

    /**
     * When the {@code CXACAIX} alternate-index read returns matches, the first record
     * is used, yielding the customer id and card number.
     */
    @Test
    void getCardXrefByAccount_whenPresent_returnsFirstMatch() {
        CardXref cardXref = newCardXref();
        when(cardXrefRepository.findByXrefAcctId(ACCT_ID)).thenReturn(List.of(cardXref));

        CardXref result = service.getCardXrefByAccount(ACCT_ID);

        assertThat(result).isSameAs(cardXref);
        assertThat(result.getXrefCustId()).isEqualTo(CUST_ID);
        assertThat(result.getXrefCardNum()).isEqualTo(CARD_NUM);
        verifyNoInteractions(accountRepository, customerRepository);
        verifyNoWrites();
    }

    /**
     * An empty cross-reference result is the CICS {@code NOTFND} equivalent and becomes
     * a {@link RecordNotFoundException}; the account and customer reads must not be
     * attempted (checklist item 3).
     */
    @Test
    void getCardXrefByAccount_whenEmpty_throwsRecordNotFound() {
        when(cardXrefRepository.findByXrefAcctId(ACCT_ID)).thenReturn(List.of());

        assertThatThrownBy(() -> service.getCardXrefByAccount(ACCT_ID))
                .isInstanceOf(RecordNotFoundException.class)
                .hasMessage(XREF_NOT_FOUND);

        verifyNoInteractions(accountRepository, customerRepository);
    }

    // ------------------------------------------------------------------
    // 9300-GETACCTDATA-BYACCT (checklist items 2 and 4)
    // ------------------------------------------------------------------

    /**
     * A present account is returned, and its monetary fields are {@link BigDecimal}
     * (never {@code float}/{@code double}), preserving decimal fidelity (AAP
     * &sect;0.6.1).
     */
    @Test
    void getAcctDataByAccount_whenPresent_returnsAccount_withBigDecimalMoney() {
        Account account = newAccount();
        when(accountRepository.findById(ACCT_ID)).thenReturn(Optional.of(account));

        Account result = service.getAcctDataByAccount(ACCT_ID);

        assertThat(result).isSameAs(account);
        Object currentBalance = result.getCurrBal();
        Object creditLimit = result.getCreditLimit();
        assertThat(currentBalance).isInstanceOf(BigDecimal.class);
        assertThat(creditLimit).isInstanceOf(BigDecimal.class);
        assertThat(result.getCurrBal()).isEqualByComparingTo(new BigDecimal("1234.56"));
        verifyNoInteractions(cardXrefRepository, customerRepository);
    }

    /**
     * A missing account is the {@code NOTFND} equivalent and becomes a
     * {@link RecordNotFoundException} with the account-master message; the customer
     * read is not attempted here (this method reads only the account).
     */
    @Test
    void getAcctDataByAccount_whenEmpty_throwsRecordNotFound() {
        when(accountRepository.findById(ACCT_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.getAcctDataByAccount(ACCT_ID))
                .isInstanceOf(RecordNotFoundException.class)
                .hasMessage(ACCT_NOT_FOUND);

        verifyNoInteractions(cardXrefRepository, customerRepository);
    }

    // ------------------------------------------------------------------
    // 9400-GETCUSTDATA-BYCUST (checklist items 2 and 5)
    // ------------------------------------------------------------------

    /** A present customer is returned by primary-key read. */
    @Test
    void getCustDataByCust_whenPresent_returnsCustomer() {
        Customer customer = newCustomer();
        when(customerRepository.findById(CUST_ID)).thenReturn(Optional.of(customer));

        Customer result = service.getCustDataByCust(CUST_ID);

        assertThat(result).isSameAs(customer);
        assertThat(result.getCustId()).isEqualTo(CUST_ID);
        verifyNoInteractions(cardXrefRepository, accountRepository);
    }

    /**
     * A missing customer becomes a {@link RecordNotFoundException} with the
     * customer-master message (checklist item 5).
     */
    @Test
    void getCustDataByCust_whenEmpty_throwsRecordNotFound() {
        when(customerRepository.findById(CUST_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.getCustDataByCust(CUST_ID))
                .isInstanceOf(RecordNotFoundException.class)
                .hasMessage(CUST_NOT_FOUND);

        verifyNoInteractions(cardXrefRepository, accountRepository);
    }

    // ------------------------------------------------------------------
    // 9000-READ-ACCT (checklist items 2, 3, 4, 5): the three-file read chain
    // ------------------------------------------------------------------

    /**
     * Happy path: the read chain walks the cross-reference, then the account, then the
     * customer, in that exact order (checklist item 2). The cross-reference discoveries
     * (customer id and card number) are fed back into the session context, and no
     * repository write is issued.
     */
    @Test
    void readAcct_happyPath_readsXrefThenAccountThenCustomer_inOrder() {
        when(context.getAcctId()).thenReturn(ACCT_ID);
        when(context.getCustId()).thenReturn(CUST_ID);
        when(cardXrefRepository.findByXrefAcctId(ACCT_ID)).thenReturn(List.of(newCardXref()));
        when(accountRepository.findById(ACCT_ID)).thenReturn(Optional.of(newAccount()));
        when(customerRepository.findById(CUST_ID)).thenReturn(Optional.of(newCustomer()));

        service.readAcct(new COACTVWForm());

        InOrder inOrder = inOrder(cardXrefRepository, accountRepository, customerRepository);
        inOrder.verify(cardXrefRepository).findByXrefAcctId(ACCT_ID);
        inOrder.verify(accountRepository).findById(ACCT_ID);
        inOrder.verify(customerRepository).findById(CUST_ID);

        verify(context).setCustId(CUST_ID);
        verify(context).setCardNum(CARD_NUM);
        verifyNoWrites();
    }

    /**
     * Happy path: 1200-SETUP-SCREEN-VARS data moves populate the form's account and
     * customer fields. Asserts the {@link BigDecimal} money values render value-faithful
     * display strings, the {@code PIC 9(11)} account-id echo, the SSN {@code XXX-XX-XXXX}
     * mask, and the legacy quirk where address line 3 populates the CITY field.
     */
    @Test
    void readAcct_happyPath_populatesAccountAndCustomerFieldsOntoForm() {
        when(context.getAcctId()).thenReturn(ACCT_ID);
        when(context.getCustId()).thenReturn(CUST_ID);
        when(cardXrefRepository.findByXrefAcctId(ACCT_ID)).thenReturn(List.of(newCardXref()));
        when(accountRepository.findById(ACCT_ID)).thenReturn(Optional.of(newAccount()));
        when(customerRepository.findById(CUST_ID)).thenReturn(Optional.of(newCustomer()));

        COACTVWForm form = new COACTVWForm();
        service.readAcct(form);

        // Account data moves (money rendered from BigDecimal via toPlainString).
        assertThat(form.getAcsttus()).isEqualTo("Y");
        assertThat(form.getAcurbal()).isEqualTo("1234.56");
        assertThat(form.getAcrdlim()).isEqualTo("5000.00");
        assertThat(form.getAcshlim()).isEqualTo("2000.00");
        assertThat(form.getAcrcycr()).isEqualTo("300.00");
        assertThat(form.getAcrcydb()).isEqualTo("150.00");
        assertThat(form.getAdtopen()).isEqualTo("2020-01-15");
        assertThat(form.getAexpdt()).isEqualTo("2027-12-31");
        assertThat(form.getAreisdt()).isEqualTo("2024-06-01");
        assertThat(form.getAaddgrp()).isEqualTo("DEFAULT");
        assertThat(form.getAcctsid()).isEqualTo(ACCT_ID_TEXT);

        // Customer data moves.
        assertThat(form.getAcstnum()).isEqualTo("987654321");
        assertThat(form.getAcstssn()).isEqualTo("123-45-6789");
        assertThat(form.getAcstfco()).isEqualTo("750");
        assertThat(form.getAcstdob()).isEqualTo("1985-03-20");
        assertThat(form.getAcsfnam()).isEqualTo("JOHN");
        assertThat(form.getAcsmnam()).isEqualTo("Q");
        assertThat(form.getAcslnam()).isEqualTo("PUBLIC");
        assertThat(form.getAcsadl1()).isEqualTo("123 MAIN ST");
        assertThat(form.getAcsadl2()).isEqualTo("APT 4");
        // Legacy quirk: address line 3 populates the CITY field (ACSCITYO).
        assertThat(form.getAcscity()).isEqualTo("SPRINGFIELD");
        assertThat(form.getAcsstte()).isEqualTo("IL");
        assertThat(form.getAcszipc()).isEqualTo("62704");
        assertThat(form.getAcsctry()).isEqualTo("USA");
        assertThat(form.getAcsphn1()).isEqualTo("5551234567");
        assertThat(form.getAcsphn2()).isEqualTo("5559876543");
        assertThat(form.getAcsgovt()).isEqualTo("GOVID123");
        assertThat(form.getAcseftc()).isEqualTo("EFT99999");
        assertThat(form.getAcspflg()).isEqualTo("Y");
    }

    /**
     * When the cross-reference read finds nothing, the chain short-circuits with a
     * {@link RecordNotFoundException} and neither the account nor the customer read is
     * attempted (checklist item 3).
     */
    @Test
    void readAcct_xrefNotFound_throwsAndSkipsAccountAndCustomerReads() {
        when(context.getAcctId()).thenReturn(ACCT_ID);
        when(cardXrefRepository.findByXrefAcctId(ACCT_ID)).thenReturn(List.of());

        assertThatThrownBy(() -> service.readAcct(new COACTVWForm()))
                .isInstanceOf(RecordNotFoundException.class)
                .hasMessage(XREF_NOT_FOUND);

        verifyNoInteractions(accountRepository, customerRepository);
    }

    /**
     * When the account read finds nothing, the chain short-circuits with a
     * {@link RecordNotFoundException} and the customer read is not attempted
     * (checklist item 4).
     */
    @Test
    void readAcct_accountNotFound_throwsAndSkipsCustomerRead() {
        when(context.getAcctId()).thenReturn(ACCT_ID);
        when(cardXrefRepository.findByXrefAcctId(ACCT_ID)).thenReturn(List.of(newCardXref()));
        when(accountRepository.findById(ACCT_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.readAcct(new COACTVWForm()))
                .isInstanceOf(RecordNotFoundException.class)
                .hasMessage(ACCT_NOT_FOUND);

        verify(customerRepository, never()).findById(any());
    }

    /**
     * When the customer read finds nothing, the chain ends with a
     * {@link RecordNotFoundException} carrying the customer-master message
     * (checklist item 5).
     */
    @Test
    void readAcct_customerNotFound_throws() {
        when(context.getAcctId()).thenReturn(ACCT_ID);
        when(context.getCustId()).thenReturn(CUST_ID);
        when(cardXrefRepository.findByXrefAcctId(ACCT_ID)).thenReturn(List.of(newCardXref()));
        when(accountRepository.findById(ACCT_ID)).thenReturn(Optional.of(newAccount()));
        when(customerRepository.findById(CUST_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.readAcct(new COACTVWForm()))
                .isInstanceOf(RecordNotFoundException.class)
                .hasMessage(CUST_NOT_FOUND);
    }

    // ------------------------------------------------------------------
    // 0000-MAIN (checklist item 6): pseudo-conversational state machine
    // ------------------------------------------------------------------

    /**
     * First entry (program-enter) presents the empty prompt screen and performs no
     * read (checklist item 6, first entry).
     */
    @Test
    void mainEntry_firstEntry_showsPromptScreen_withoutReadingAnyFile() {
        when(context.isProgramEnter()).thenReturn(true);

        COACTVWForm form = new COACTVWForm();
        AccountViewResult result = service.mainEntry(form, PfKey.ENTER);

        assertThat(result.action()).isEqualTo(RoutingAction.SHOW_SCREEN);
        assertThat(result.infoMessage()).isEqualTo(PROMPT_FOR_INPUT);
        assertThat(result.returnMessage()).isEmpty();
        // No read populated the screen's data fields on first entry.
        assertThat(form.getAcurbal()).isNull();
        verifyNoInteractions(cardXrefRepository, accountRepository, customerRepository);
    }

    /**
     * Re-entry with a valid account id runs the input edits and then the three-file
     * read chain in order, displaying the account (checklist item 6, re-entry).
     */
    @Test
    void mainEntry_reentryWithValidInput_readsChain_andShowsScreen() {
        when(context.isProgramEnter()).thenReturn(false);
        when(context.isProgramReenter()).thenReturn(true);
        when(context.getAcctId()).thenReturn(ACCT_ID);
        when(context.getCustId()).thenReturn(CUST_ID);
        when(cardXrefRepository.findByXrefAcctId(ACCT_ID)).thenReturn(List.of(newCardXref()));
        when(accountRepository.findById(ACCT_ID)).thenReturn(Optional.of(newAccount()));
        when(customerRepository.findById(CUST_ID)).thenReturn(Optional.of(newCustomer()));

        COACTVWForm form = formWithAcctId(ACCT_ID_TEXT);
        AccountViewResult result = service.mainEntry(form, PfKey.ENTER);

        assertThat(result.action()).isEqualTo(RoutingAction.SHOW_SCREEN);
        assertThat(result.infoMessage()).isEqualTo(PROMPT_FOR_INPUT);
        assertThat(result.returnMessage()).isEmpty();

        InOrder inOrder = inOrder(cardXrefRepository, accountRepository, customerRepository);
        inOrder.verify(cardXrefRepository).findByXrefAcctId(ACCT_ID);
        inOrder.verify(accountRepository).findById(ACCT_ID);
        inOrder.verify(customerRepository).findById(CUST_ID);
        assertThat(form.getAcurbal()).isEqualTo("1234.56");
        verifyNoWrites();
    }

    /**
     * Re-entry with an invalid account id re-displays the screen with the error line
     * and the input prompt, and performs no read.
     */
    @Test
    void mainEntry_reentryWithInvalidInput_redisplaysWithErrorAndPrompt_withoutReading() {
        when(context.isProgramEnter()).thenReturn(false);
        when(context.isProgramReenter()).thenReturn(true);

        AccountViewResult result = service.mainEntry(formWithAcctId("ABC"), PfKey.ENTER);

        assertThat(result.action()).isEqualTo(RoutingAction.SHOW_SCREEN);
        assertThat(result.returnMessage()).isEqualTo(ACCT_FILTER_INVALID);
        assertThat(result.infoMessage()).isEqualTo(PROMPT_FOR_INPUT);
        verifyNoInteractions(cardXrefRepository, accountRepository, customerRepository);
    }

    /**
     * PF3 with no recorded caller hands off to the main menu ({@code CM00}/
     * {@code COMEN01C}), records this program as the origin, sets the user type, and
     * marks program-enter for the target.
     */
    @Test
    void mainEntry_pf3WithNoCaller_redirectsToMainMenu() {
        AccountViewResult result = service.mainEntry(new COACTVWForm(), PfKey.PFK03);

        assertThat(result.action()).isEqualTo(RoutingAction.REDIRECT);
        verify(context).setToTranid(MENU_TRANID);
        verify(context).setToProgram(MENU_PROGRAM);
        verify(context).setFromTranid(THIS_TRANID);
        verify(context).setFromProgram(THIS_PROGRAM);
        verify(context).setUser();
        verify(context).markEnter();
        verify(context).setLastMapset(THIS_MAPSET);
        verify(context).setLastMap(THIS_MAP);
        verifyNoInteractions(cardXrefRepository, accountRepository, customerRepository);
    }

    /**
     * PF3 with a recorded caller hands off back to that caller
     * ({@code CDEMO-FROM-TRANID}/{@code CDEMO-FROM-PROGRAM}).
     */
    @Test
    void mainEntry_pf3WithRecordedCaller_redirectsToCaller() {
        when(context.getFromTranid()).thenReturn(CALLER_TRANID);
        when(context.getFromProgram()).thenReturn(CALLER_PROGRAM);

        AccountViewResult result = service.mainEntry(new COACTVWForm(), PfKey.PFK03);

        assertThat(result.action()).isEqualTo(RoutingAction.REDIRECT);
        verify(context).setToTranid(CALLER_TRANID);
        verify(context).setToProgram(CALLER_PROGRAM);
        verify(context).setFromTranid(THIS_TRANID);
        verify(context).setFromProgram(THIS_PROGRAM);
        verifyNoInteractions(cardXrefRepository, accountRepository, customerRepository);
    }

    /**
     * Any attention key other than ENTER or PF3 is coerced to ENTER (COBOL
     * {@code IF PFK-INVALID SET ENTER}); with program-enter this shows the prompt
     * screen just like ENTER.
     */
    @Test
    void mainEntry_nonEnterNonPf3Key_isCoercedToEnter() {
        when(context.isProgramEnter()).thenReturn(true);

        AccountViewResult result = service.mainEntry(new COACTVWForm(), PfKey.PFK07);

        assertThat(result.action()).isEqualTo(RoutingAction.SHOW_SCREEN);
        assertThat(result.infoMessage()).isEqualTo(PROMPT_FOR_INPUT);
        verifyNoInteractions(cardXrefRepository, accountRepository, customerRepository);
    }

    /**
     * The defensive {@code WHEN OTHER} branch (neither enter nor re-enter) raises the
     * "UNEXPECTED DATA SCENARIO" abend, surfaced as an {@link IllegalStateException}.
     */
    @Test
    void mainEntry_unexpectedProgramContext_throwsIllegalState() {
        when(context.isProgramEnter()).thenReturn(false);
        when(context.isProgramReenter()).thenReturn(false);

        assertThatThrownBy(() -> service.mainEntry(new COACTVWForm(), PfKey.ENTER))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage(UNEXPECTED_DATA_SCENARIO);

        verifyNoInteractions(cardXrefRepository, accountRepository, customerRepository);
    }

    // ------------------------------------------------------------------
    // Read-only guarantee (checklist item 7)
    // ------------------------------------------------------------------

    /**
     * The account-view program is strictly read-only: a full successful read chain
     * issues no {@code save}/{@code delete} against any repository (checklist item 7).
     */
    @Test
    void accountViewIsReadOnly_neverWritesToAnyRepository() {
        when(context.getAcctId()).thenReturn(ACCT_ID);
        when(context.getCustId()).thenReturn(CUST_ID);
        when(cardXrefRepository.findByXrefAcctId(ACCT_ID)).thenReturn(List.of(newCardXref()));
        when(accountRepository.findById(ACCT_ID)).thenReturn(Optional.of(newAccount()));
        when(customerRepository.findById(CUST_ID)).thenReturn(Optional.of(newCustomer()));

        service.readAcct(new COACTVWForm());

        verifyNoWrites();
    }
}
