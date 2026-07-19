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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.aws.carddemo.domain.Account;
import com.aws.carddemo.domain.CardXref;
import com.aws.carddemo.domain.Customer;
import com.aws.carddemo.dto.CardDemoContext;
import com.aws.carddemo.dto.CardWorkArea.PfKey;
import com.aws.carddemo.dto.screen.COACTUPForm;
import com.aws.carddemo.repository.AccountRepository;
import com.aws.carddemo.repository.CardXrefRepository;
import com.aws.carddemo.repository.CustomerRepository;
import com.aws.carddemo.service.online.AccountUpdateService.AccountSnapshot;
import com.aws.carddemo.service.online.AccountUpdateService.AccountUpdateResult;
import com.aws.carddemo.service.online.AccountUpdateService.AccountUpdateState;
import com.aws.carddemo.service.online.AccountUpdateService.ChangeAction;
import com.aws.carddemo.service.online.AccountUpdateService.MessageSeverity;
import com.aws.carddemo.service.online.AccountUpdateService.ScreenField;
import com.aws.carddemo.service.online.AccountUpdateService.ScreenOutcome;
import com.aws.carddemo.util.CobolDecimal;
import com.aws.carddemo.util.DateConversionSupport;
import com.aws.carddemo.util.DateConversionSupport.DateEditResult;
import com.aws.carddemo.util.DateConversionSupport.FieldFlag;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;

/**
 * Pure-Mockito JUnit 5 unit test for {@link AccountUpdateService}.
 *
 * <p><strong>Parity oracle (read-only):</strong> {@code legacy/cbl/COACTUPC.cbl} - CICS transaction
 * {@code CAUP}, program {@code COACTUPC}, the largest CardDemo online program (~4236 lines,
 * ~78 numbered paragraphs). This test exercises the paragraph-derived methods <em>individually</em>
 * (they are deliberately not collapsed) so that the paragraph&rarr;method traceability mandated by
 * the migration (AAP &sect;0.6.10) is preserved and every edit / read / decide / write / lock branch
 * is covered for the &ge;80% JaCoCo gate.</p>
 *
 * <p><strong>Paragraphs / behaviours exercised:</strong></p>
 * <ul>
 *   <li>{@code 0000-MAIN} / {@code 2000-DECIDE-ACTION} - the decision state machine
 *       (PF3 exit, PF5 confirm/save, PF12 re-fetch, ENTER re-edit) and first-entry semantics.</li>
 *   <li>{@code 1000-PROCESS-INPUTS} plus the {@code 1200}-series edit paragraphs -
 *       mandatory ({@code 1215}), yes/no ({@code 1220}), alphabetic ({@code 1225}/{@code 1230}),
 *       numeric ({@code 1245}), signed 9V2 money ({@code 1250} via the real {@link CobolDecimal}),
 *       NANP phone ({@code 1260}/area-prefix-line), 3-part SSN ({@code 1265}), US state
 *       ({@code 1270}), FICO ({@code 1275}), ZIP, and delegated date-of-birth ({@code CSUTLDTC}).</li>
 *   <li>{@code 1205-COMPARE-OLD-NEW} - no-change detection.</li>
 *   <li>{@code 9000}/{@code 9200}/{@code 9300}/{@code 9400}/{@code 9500} - the
 *       xref&rarr;account&rarr;customer read chain and {@code storeFetchedData} snapshot capture,
 *       including the three not-found banners.</li>
 *   <li>{@code 9600-WRITE-PROCESSING} ({@code @Transactional}) and {@code 9700-CHECK-CHANGE-IN-REC}
 *       - dual {@code Account}+{@code Customer} write and optimistic-lock / concurrent-change
 *       handling.</li>
 * </ul>
 *
 * <p><strong>Fidelity notes (actual service is the source of truth):</strong> the read chain does
 * <em>not</em> throw {@code RecordNotFoundException}; not-found conditions surface as an error banner
 * plus {@code inputError=true}. A concurrent change ({@code 9700}) does <em>not</em> throw
 * {@code LogicError}; it yields {@link AccountUpdateService.WriteResult#DATA_CHANGED} which re-displays the
 * screen with no committed save. These deviations from a literal reading of the checklist are asserted
 * against the real behaviour rather than the (unreachable) exceptions.</p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("AccountUpdateService (COACTUPC / CAUP) - pure-Mockito unit test")
class AccountUpdateServiceTest {

    // ----------------------------------------------------------------------------------------------
    // Collaborators. CobolDecimal is a static utility and is used for real (never mocked).
    // ----------------------------------------------------------------------------------------------

    @Mock
    private CardDemoContext context;

    @Mock
    private CardXrefRepository cardXrefRepository;

    @Mock
    private AccountRepository accountRepository;

    @Mock
    private CustomerRepository customerRepository;

    @Mock
    private DateConversionSupport dateConversionSupport;

    @InjectMocks
    private AccountUpdateService service;

    // ----------------------------------------------------------------------------------------------
    // Test-key fixtures. The baseline account id (11) and customer id (1) match the zero-padded
    // COBOL keys "00000000011" and "000000001" surfaced in the not-found banners.
    // ----------------------------------------------------------------------------------------------

    private static final Long ACCT_ID = 11L;
    private static final Long CUST_ID = 1L;
    private static final String ACCT_ID_TEXT = "00000000011";
    private static final String XREF_CARD_NUM = "1234567890123456";

    // Exact banner literals copied verbatim from AccountUpdateService (parity oracle COACTUPC.cbl).
    private static final String MSG_XREF_NOT_FOUND =
            "Account:00000000011 not found in Cross ref file.  Resp:13 Reas:0";
    private static final String MSG_ACCT_NOT_FOUND =
            "Account:00000000011 not found in Acct Master file.Resp:13 Reas:0";
    private static final String MSG_CUST_NOT_FOUND =
            "CustId:000000001 not found in customer master.Resp: 13 REAS:0";
    private static final String MSG_NO_CHANGES_DETECTED =
            "No change detected with respect to values fetched.";
    private static final String MSG_COULD_NOT_LOCK_ACCT = "Could not lock account record for update";
    private static final String MSG_COULD_NOT_LOCK_CUST = "Could not lock customer record for update";
    private static final String MSG_DATA_CHANGED = "Record changed by some one else. Please review";
    private static final String MSG_UPDATE_FAILED = "Update of record failed";

    private COACTUPForm form;

    /**
     * Builds a fully valid baseline {@link COACTUPForm} in which all 24 field edits pass (given valid
     * date stubs). Each edit-rule test flips exactly one field invalid so the first-error-wins banner
     * is unambiguous.
     */
    @BeforeEach
    void setUp() {
        form = baselineForm();
    }

    private static COACTUPForm baselineForm() {
        COACTUPForm f = new COACTUPForm();
        f.setAcctsid(ACCT_ID_TEXT);
        f.setAcsttus("Y");
        f.setAcrdlim("1000.00");
        f.setAcshlim("500.00");
        f.setAcurbal("250.00");
        f.setAcrcycr("100.00");
        f.setAcrcydb("50.00");
        f.setOpnyear("2020");
        f.setOpnmon("01");
        f.setOpnday("15");
        f.setExpyear("2025");
        f.setExpmon("12");
        f.setExpday("31");
        f.setRisyear("2021");
        f.setRismon("06");
        f.setRisday("01");
        f.setAaddgrp("GROUP01");
        f.setAcstnum("000000001");
        f.setActssn1("123");
        f.setActssn2("45");
        f.setActssn3("6789");
        f.setDobyear("1980");
        f.setDobmon("05");
        f.setDobday("20");
        f.setAcstfco("700");
        f.setAcsfnam("JOHN");
        f.setAcsmnam("QUINCY");
        f.setAcslnam("DOE");
        f.setAcsadl1("123 MAIN ST");
        f.setAcsstte("CA");
        f.setAcsadl2("APT 4");
        f.setAcszipc("90001");
        f.setAcscity("LOS ANGELES");
        f.setAcsctry("USA");
        f.setAcsph1a("212");
        f.setAcsph1b("555");
        f.setAcsph1c("1234");
        f.setAcsgovt("GOVT123");
        f.setAcsph2a("");
        f.setAcsph2b("");
        f.setAcsph2c("");
        f.setAcseftc("1234567890");
        f.setAcspflg("Y");
        return f;
    }

    /** A fetched {@link AccountUpdateState} whose fetched-and-displayed action drives the full edit pass. */
    private static AccountUpdateState fetchedState() {
        AccountUpdateState state = new AccountUpdateState();
        state.setChangeAction(ChangeAction.SHOW_DETAILS);
        return state;
    }

    /** A state that has passed the edits and is awaiting the PF5 confirmation (drives the write path). */
    private static AccountUpdateState awaitingConfirmState() {
        AccountUpdateState state = new AccountUpdateState();
        state.setChangeAction(ChangeAction.CHANGES_OK_NOT_CONFIRMED);
        return state;
    }

    /** Valid {@link DateEditResult} used to stub the delegated {@link DateConversionSupport}. */
    private static DateEditResult validDate() {
        return new DateEditResult(true, false, FieldFlag.VALID, FieldFlag.VALID, FieldFlag.VALID, "");
    }

    /** Invalid {@link DateEditResult} carrying the DOB banner text asserted by the DOB test. */
    private static DateEditResult invalidDob(String message) {
        return new DateEditResult(false, true, FieldFlag.NOT_OK, FieldFlag.VALID, FieldFlag.VALID, message);
    }

    /** Stubs every delegated date edit (open/expiry/reissue via CCYYMMDD, plus DOB) to valid. */
    private void stubAllDatesValid() {
        when(dateConversionSupport.editDateCcyymmdd(anyString(), anyString())).thenReturn(validDate());
        when(dateConversionSupport.editDateOfBirth(anyString(), anyString())).thenReturn(validDate());
    }

    /** A fresh persistent {@link Account} carrying only its key (all compared fields null). */
    private static Account freshAccount() {
        Account account = new Account();
        account.setAcctId(ACCT_ID);
        return account;
    }

    /** A fresh persistent {@link Customer} carrying only its key (all compared fields null). */
    private static Customer freshCustomer() {
        Customer customer = new Customer();
        customer.setCustId(CUST_ID);
        return customer;
    }

    // ==============================================================================================
    // (A) Read chain - 9000-READ-ACCT / 9200-GETCARDXREF / 9300-GETACCTDATA / 9400-GETCUSTDATA /
    //     9500-STORE-FETCHED-DATA. Not-found conditions surface as banners, never as exceptions.
    // ==============================================================================================

    @Test
    @DisplayName("(A1) Valid account id reads xref->account->customer in order and stores the snapshot")
    void readChain_validAccountId_readsInOrderAndStoresSnapshot() {
        AccountUpdateState state = new AccountUpdateState(); // DETAILS_NOT_FETCHED

        Account account = new Account();
        account.setAcctId(ACCT_ID);
        account.setActiveStatus("Y");
        Customer customer = new Customer();
        customer.setCustId(CUST_ID);
        customer.setFirstName("JOHN");
        customer.setLastName("DOE");

        when(context.getAcctId()).thenReturn(ACCT_ID);
        when(context.getCustId()).thenReturn(CUST_ID);
        when(cardXrefRepository.findByXrefAcctId(ACCT_ID))
                .thenReturn(List.of(new CardXref(XREF_CARD_NUM, CUST_ID, ACCT_ID)));
        when(accountRepository.findById(ACCT_ID)).thenReturn(Optional.of(account));
        when(customerRepository.findById(CUST_ID)).thenReturn(Optional.of(customer));

        AccountUpdateResult result = service.process(form, PfKey.ENTER, state);

        // Read chain executes xref -> account -> customer strictly in order (9200 -> 9300 -> 9400).
        InOrder inOrder = Mockito.inOrder(cardXrefRepository, accountRepository, customerRepository);
        inOrder.verify(cardXrefRepository).findByXrefAcctId(ACCT_ID);
        inOrder.verify(accountRepository).findById(ACCT_ID);
        inOrder.verify(customerRepository).findById(CUST_ID);

        // 9500-STORE-FETCHED-DATA captured the old snapshot onto the pseudo-conversational state.
        AccountSnapshot snapshot = state.getOldSnapshot();
        assertThat(snapshot).isNotNull();
        assertThat(snapshot.getAcctId()).isEqualTo(ACCT_ID_TEXT);
        assertThat(snapshot.getCustId()).isEqualTo("000000001");
        assertThat(snapshot.getFirstName()).isEqualTo("JOHN");
        verify(context).setCustFirstName("JOHN");

        // A successfully fetched record is shown, with no edit error.
        assertThat(result.changeAction()).isEqualTo(ChangeAction.SHOW_DETAILS);
        assertThat(result.inputError()).isFalse();
        assertThat(result.message()).isEmpty();
    }

    @Test
    @DisplayName("(A2a) Xref not found -> banner, input error, and the read chain stops (guard active)")
    void readChain_xrefNotFound_setsBannerAndStopsChain() {
        AccountUpdateState state = new AccountUpdateState();

        when(context.getAcctId()).thenReturn(ACCT_ID);
        when(cardXrefRepository.findByXrefAcctId(ACCT_ID)).thenReturn(List.of());

        AccountUpdateResult result = service.process(form, PfKey.ENTER, state);

        assertThat(result.message()).isEqualTo(MSG_XREF_NOT_FOUND);
        assertThat(result.inputError()).isTrue();
        assertThat(result.changeAction()).isEqualTo(ChangeAction.DETAILS_NOT_FETCHED);
        // 9200 xref-not-found returns early: neither the account nor the customer master is read.
        verifyNoInteractions(accountRepository, customerRepository);
    }

    @Test
    @DisplayName("(A2b) Account not found -> account banner (first-error-wins) while chain continues")
    void readChain_accountNotFound_setsAccountBanner() {
        AccountUpdateState state = new AccountUpdateState();

        when(context.getAcctId()).thenReturn(ACCT_ID);
        when(context.getCustId()).thenReturn(CUST_ID);
        when(cardXrefRepository.findByXrefAcctId(ACCT_ID))
                .thenReturn(List.of(new CardXref(XREF_CARD_NUM, CUST_ID, ACCT_ID)));
        when(accountRepository.findById(ACCT_ID)).thenReturn(Optional.empty());
        when(customerRepository.findById(CUST_ID)).thenReturn(Optional.empty());

        AccountUpdateResult result = service.process(form, PfKey.ENTER, state);

        // The account-master banner wins because 9300 runs before 9400 (guarded first message).
        assertThat(result.message()).isEqualTo(MSG_ACCT_NOT_FOUND);
        assertThat(result.inputError()).isTrue();
        assertThat(result.changeAction()).isEqualTo(ChangeAction.DETAILS_NOT_FETCHED);
        // The (dead) 9300 guard does not stop the chain: the customer read is still attempted.
        verify(accountRepository).findById(ACCT_ID);
        verify(customerRepository).findById(CUST_ID);
    }

    @Test
    @DisplayName("(A2c) Customer not found -> customer banner and no fetched details")
    void readChain_customerNotFound_setsCustomerBanner() {
        AccountUpdateState state = new AccountUpdateState();

        Account account = new Account();
        account.setAcctId(ACCT_ID);
        account.setActiveStatus("Y");

        when(context.getAcctId()).thenReturn(ACCT_ID);
        when(context.getCustId()).thenReturn(CUST_ID);
        when(cardXrefRepository.findByXrefAcctId(ACCT_ID))
                .thenReturn(List.of(new CardXref(XREF_CARD_NUM, CUST_ID, ACCT_ID)));
        when(accountRepository.findById(ACCT_ID)).thenReturn(Optional.of(account));
        when(customerRepository.findById(CUST_ID)).thenReturn(Optional.empty());

        AccountUpdateResult result = service.process(form, PfKey.ENTER, state);

        assertThat(result.message()).isEqualTo(MSG_CUST_NOT_FOUND);
        assertThat(result.inputError()).isTrue();
        assertThat(result.changeAction()).isEqualTo(ChangeAction.DETAILS_NOT_FETCHED);
    }

    // ==============================================================================================
    // (B) 1200-series edit rules. State = SHOW_DETAILS drives the full 24-field pass; each test flips
    //     exactly one field invalid so the first-error-wins banner is the asserted literal. All dates
    //     are delegated to the mocked DateConversionSupport (stubbed valid unless the test is the DOB
    //     test). A negative edit leaves the action at CHANGES_NOT_OK; a clean pass yields
    //     CHANGES_OK_NOT_CONFIRMED.
    // ==============================================================================================

    /** Runs the full edit pass over {@code form} with all delegated dates stubbed valid. */
    private AccountUpdateResult runEditPass() {
        stubAllDatesValid();
        return service.process(form, PfKey.ENTER, fetchedState());
    }

    @Test
    @DisplayName("(B0) All fields valid -> no error, action advances to CHANGES_OK_NOT_CONFIRMED")
    void editPass_allValid_advancesToAwaitingConfirm() {
        AccountUpdateResult result = runEditPass();

        assertThat(result.inputError()).isFalse();
        assertThat(result.message()).isEmpty();
        assertThat(result.severity()).isEqualTo(MessageSeverity.NONE);
        assertThat(result.changeAction()).isEqualTo(ChangeAction.CHANGES_OK_NOT_CONFIRMED);
    }

    @Test
    @DisplayName("(B3a) Mandatory Address Line 1 blank -> '<field> must be supplied.' (1215)")
    void edit_mandatoryAddressLine1Blank_reportsMustBeSupplied() {
        form.setAcsadl1("");

        AccountUpdateResult result = runEditPass();

        assertThat(result.message()).isEqualTo("Address Line 1 must be supplied.");
        assertThat(result.inputError()).isTrue();
        assertThat(result.severity()).isEqualTo(MessageSeverity.ERROR);
        assertThat(result.changeAction()).isEqualTo(ChangeAction.CHANGES_NOT_OK);
    }

    @Test
    @DisplayName("(B3b) Mandatory First Name blank -> dynamic '<field> must be supplied.' (proves field name)")
    void edit_firstNameBlank_reportsDynamicFieldName() {
        form.setAcsfnam("");

        AccountUpdateResult result = runEditPass();

        // Same rule, different field: the field name in the message is dynamic.
        assertThat(result.message()).isEqualTo("First Name must be supplied.");
        assertThat(result.inputError()).isTrue();
    }

    @Test
    @DisplayName("(B4) Numeric Zip with letters -> '<field> must be all numeric.' (1245)")
    void edit_zipNonNumeric_reportsMustBeAllNumeric() {
        form.setAcszipc("ABCDE");

        AccountUpdateResult result = runEditPass();

        assertThat(result.message()).isEqualTo("Zip must be all numeric.");
        assertThat(result.inputError()).isTrue();
    }

    @Test
    @DisplayName("(B5) Not-zero Zip all zeroes -> '<field> must not be zero.' (1245)")
    void edit_zipAllZero_reportsMustNotBeZero() {
        form.setAcszipc("00000");

        AccountUpdateResult result = runEditPass();

        assertThat(result.message()).isEqualTo("Zip must not be zero.");
        assertThat(result.inputError()).isTrue();
    }

    @Test
    @DisplayName("(B6) Yes/No Account Status not Y or N -> '<field> must be Y or N.' (1220)")
    void edit_accountStatusInvalidFlag_reportsMustBeYorN() {
        form.setAcsttus("X");

        AccountUpdateResult result = runEditPass();

        assertThat(result.message()).isEqualTo("Account Status must be Y or N.");
        assertThat(result.inputError()).isTrue();
    }

    @Test
    @DisplayName("(B7) Alphabetic First Name with digits -> '<field> can have alphabets only.' (1225)")
    void edit_firstNameWithDigits_reportsAlphabetsOnly() {
        form.setAcsfnam("JOHN2");

        AccountUpdateResult result = runEditPass();

        assertThat(result.message()).isEqualTo("First Name can have alphabets only.");
        assertThat(result.inputError()).isTrue();
    }

    @Test
    @DisplayName("(B8a) Signed 9V2 money with letters -> '<field> is not valid' (no period) (1250)")
    void edit_creditLimitNonNumeric_reportsIsNotValid() {
        form.setAcrdlim("ABC");

        AccountUpdateResult result = runEditPass();

        // Deliberately no trailing period - matches the COBOL WS-INVALID-* literal.
        assertThat(result.message()).isEqualTo("Credit Limit is not valid");
        assertThat(result.inputError()).isTrue();
    }

    @Test
    @DisplayName("(B8b) Real CobolDecimal money truncates DOWN to scale 2 - never float/double")
    void money_realCobolDecimal_truncatesDown() {
        // The production service parses money through the REAL CobolDecimal (never mocked). Assert its
        // COBOL parity: truncation toward zero (RoundingMode.DOWN), fixed scale 2, BigDecimal type.
        BigDecimal truncated = CobolDecimal.money(new BigDecimal("1000.999"));
        assertThat(truncated).isInstanceOf(BigDecimal.class);
        assertThat(truncated).isEqualTo(new BigDecimal("1000.99"));
        assertThat(truncated.scale()).isEqualTo(2);

        // Even a value that HALF_UP would round up is truncated down (no rounding up).
        assertThat(CobolDecimal.money(new BigDecimal("123.995"))).isEqualTo(new BigDecimal("123.99"));
        // Negative amounts truncate toward zero as well.
        assertThat(CobolDecimal.money(new BigDecimal("-1.239"))).isEqualTo(new BigDecimal("-1.23"));
        assertThat(CobolDecimal.COBOL_DEFAULT_ROUNDING).isEqualTo(java.math.RoundingMode.DOWN);
        assertThat(CobolDecimal.MONEY_SCALE).isEqualTo(2);
    }

    @Test
    @DisplayName("(B9a) NANP phone with a non-general-purpose area code -> invalid area message (1260)")
    void edit_phoneInvalidAreaCode_reportsInvalidArea() {
        form.setAcsph1a("999"); // 3 digits, non-zero, but not a valid NANP general-purpose area code

        AccountUpdateResult result = runEditPass();

        assertThat(result.message())
                .isEqualTo("Phone Number 1: Not valid North America general purpose area code");
        assertThat(result.inputError()).isTrue();
    }

    @Test
    @DisplayName("(B9b) Valid NANP phone accepted -> PHONE_NUM_1 flag VALID, no error")
    void edit_validPhone_accepted() {
        AccountUpdateResult result = runEditPass(); // baseline phone (212)555-1234 is a valid NANP number

        assertThat(result.inputError()).isFalse();
        assertThat(result.fieldFlags().get(ScreenField.PHONE_NUM_1)).isEqualTo(FieldFlag.VALID);
    }

    @Test
    @DisplayName("(B10a) SSN part 1 = 666 rejected -> range message (1265)")
    void edit_ssnPart1_666_rejected() {
        form.setActssn1("666");

        AccountUpdateResult result = runEditPass();

        assertThat(result.message())
                .isEqualTo("SSN: First 3 chars: should not be 000, 666, or between 900 and 999");
        assertThat(result.inputError()).isTrue();
    }

    @Test
    @DisplayName("(B10b) SSN part 1 in 900-999 rejected -> range message (1265)")
    void edit_ssnPart1_900_rejected() {
        form.setActssn1("900");

        AccountUpdateResult result = runEditPass();

        assertThat(result.message())
                .isEqualTo("SSN: First 3 chars: should not be 000, 666, or between 900 and 999");
        assertThat(result.inputError()).isTrue();
    }

    @Test
    @DisplayName("(B10c) SSN part 1 = 000 caught by the not-zero edit before the range check (1245/1265)")
    void edit_ssnPart1_000_rejectedByNotZero() {
        form.setActssn1("000");

        AccountUpdateResult result = runEditPass();

        // 000 is all-zero, so 1245-EDIT-NUM-REQD rejects it before the 1265 range test runs.
        assertThat(result.message()).isEqualTo("SSN: First 3 chars must not be zero.");
        assertThat(result.inputError()).isTrue();
    }

    @Test
    @DisplayName("(B10d) SSN part 2 non-numeric rejected -> '<field> must be all numeric.' (1265 part 2)")
    void edit_ssnPart2NonNumeric_rejected() {
        form.setActssn2("XY");

        AccountUpdateResult result = runEditPass();

        assertThat(result.message()).isEqualTo("SSN 4th & 5th chars must be all numeric.");
        assertThat(result.inputError()).isTrue();
    }

    @Test
    @DisplayName("(B10e) Valid SSN accepted -> SSN_PART1 flag VALID")
    void edit_validSsn_accepted() {
        AccountUpdateResult result = runEditPass(); // baseline SSN 123-45-6789

        assertThat(result.inputError()).isFalse();
        assertThat(result.fieldFlags().get(ScreenField.SSN_PART1)).isEqualTo(FieldFlag.VALID);
    }

    @Test
    @DisplayName("(B11a) Unknown US state code -> '<field>: is not a valid state code' (1270)")
    void edit_unknownStateCode_rejected() {
        form.setAcsstte("ZZ");

        AccountUpdateResult result = runEditPass();

        assertThat(result.message()).isEqualTo("State: is not a valid state code");
        assertThat(result.inputError()).isTrue();
    }

    @Test
    @DisplayName("(B11b) Known US state code (CA) accepted -> STATE flag VALID")
    void edit_knownStateCode_accepted() {
        AccountUpdateResult result = runEditPass();

        assertThat(result.inputError()).isFalse();
        assertThat(result.fieldFlags().get(ScreenField.STATE)).isEqualTo(FieldFlag.VALID);
    }

    @Test
    @DisplayName("(B12a) FICO 299 (below floor) rejected -> 'should be between 300 and 850' (1275)")
    void edit_ficoBelowFloor_rejected() {
        form.setAcstfco("299");

        AccountUpdateResult result = runEditPass();

        assertThat(result.message()).isEqualTo("FICO Score: should be between 300 and 850");
        assertThat(result.inputError()).isTrue();
    }

    @Test
    @DisplayName("(B12b) FICO 851 (above ceiling) rejected -> 'should be between 300 and 850' (1275)")
    void edit_ficoAboveCeiling_rejected() {
        form.setAcstfco("851");

        AccountUpdateResult result = runEditPass();

        assertThat(result.message()).isEqualTo("FICO Score: should be between 300 and 850");
        assertThat(result.inputError()).isTrue();
    }

    @Test
    @DisplayName("(B12c) FICO boundary values 300/850 and a mid value accepted (1275)")
    void edit_ficoBoundaries_accepted() {
        stubAllDatesValid();
        for (String fico : new String[] {"300", "850", "700"}) {
            form = baselineForm();
            form.setAcstfco(fico);
            AccountUpdateResult result = service.process(form, PfKey.ENTER, fetchedState());
            assertThat(result.inputError())
                    .as("FICO %s should be accepted", fico)
                    .isFalse();
            assertThat(result.fieldFlags().get(ScreenField.FICO_SCORE)).isEqualTo(FieldFlag.VALID);
        }
    }

    @Test
    @DisplayName("(B13) ZIP not valid for the state -> 'Invalid zip code for state' (1280 cross-field)")
    void edit_zipInvalidForState_rejected() {
        // State CA and a well-formed 5-digit zip that is not a CA prefix (CA90..CA96 are valid).
        form.setAcszipc("99999");

        AccountUpdateResult result = runEditPass();

        assertThat(result.message()).isEqualTo("Invalid zip code for state");
        assertThat(result.inputError()).isTrue();
    }

    @Test
    @DisplayName("(B14a) DOB delegated to DateConversionSupport - invalid result surfaces its message")
    void edit_dobInvalid_surfacesDelegatedMessage() {
        String dobMessage = "Date of Birth - Not a valid date";
        // Calendar edit passes for all dates; the DOB future-date edit fails.
        when(dateConversionSupport.editDateCcyymmdd(anyString(), anyString())).thenReturn(validDate());
        when(dateConversionSupport.editDateOfBirth(anyString(), anyString()))
                .thenReturn(invalidDob(dobMessage));

        AccountUpdateResult result = service.process(form, PfKey.ENTER, fetchedState());

        assertThat(result.message()).isEqualTo(dobMessage);
        assertThat(result.inputError()).isTrue();
        // The service delegates DOB validation - it does not re-implement date math.
        verify(dateConversionSupport).editDateOfBirth(anyString(), anyString());
    }

    @Test
    @DisplayName("(B14b) DOB delegated valid result accepted -> DATE_OF_BIRTH flag VALID")
    void edit_dobValid_accepted() {
        AccountUpdateResult result = runEditPass();

        assertThat(result.inputError()).isFalse();
        assertThat(result.fieldFlags().get(ScreenField.DATE_OF_BIRTH)).isEqualTo(FieldFlag.VALID);
        verify(dateConversionSupport).editDateOfBirth(anyString(), anyString());
    }

    // ==============================================================================================
    // (C) 1205-COMPARE-OLD-NEW change detection. A fetched snapshot that matches every submitted
    //     field yields the informational NO-CHANGES-DETECTED banner and keeps SHOW_DETAILS; any
    //     difference re-runs the edits and advances toward confirmation.
    // ==============================================================================================

    /** A form fully populated (including phone 2) so it can exactly match {@link #matchingSnapshot()}. */
    private static COACTUPForm fullyPopulatedForm() {
        COACTUPForm f = baselineForm();
        f.setAcsph2a("312");
        f.setAcsph2b("555");
        f.setAcsph2c("6789");
        return f;
    }

    /** An {@link AccountSnapshot} whose every compared field matches {@link #fullyPopulatedForm()}. */
    private static AccountSnapshot matchingSnapshot() {
        AccountSnapshot s = new AccountSnapshot();
        s.setAcctId(ACCT_ID_TEXT);
        s.setActiveStatus("Y");
        s.setCurrBal(new BigDecimal("250.00"));
        s.setCreditLimit(new BigDecimal("1000.00"));
        s.setCashCreditLimit(new BigDecimal("500.00"));
        s.setOpenDate("20200115");
        s.setExpirationDate("20251231");
        s.setReissueDate("20210601");
        s.setCurrCycCredit(new BigDecimal("100.00"));
        s.setCurrCycDebit(new BigDecimal("50.00"));
        s.setGroupId("GROUP01");
        s.setCustId("000000001");
        s.setFirstName("JOHN");
        s.setMiddleName("QUINCY");
        s.setLastName("DOE");
        s.setAddrLine1("123 MAIN ST");
        s.setAddrLine2("APT 4");
        s.setAddrLine3("LOS ANGELES");
        s.setAddrStateCd("CA");
        s.setAddrCountryCd("USA");
        s.setAddrZip("90001");
        s.setPhoneNum1("(212)555-1234");
        s.setPhoneNum2("(312)555-6789");
        s.setSsn("123456789");
        s.setGovtIssuedId("GOVT123");
        s.setDateOfBirth("19800520");
        s.setEftAccountId("1234567890");
        s.setPriHolderInd("Y");
        s.setFicoScore("700");
        return s;
    }

    @Test
    @DisplayName("(C15a) No field changed vs snapshot -> 'No change detected...' and stays SHOW_DETAILS")
    void compareOldNew_noChange_reportsNoChangeDetected() {
        AccountUpdateState state = fetchedState();
        state.setOldSnapshot(matchingSnapshot());

        // No 24-field edit pass runs when nothing changed, so no date stubs are needed here.
        AccountUpdateResult result = service.process(fullyPopulatedForm(), PfKey.ENTER, state);

        assertThat(result.message()).isEqualTo(MSG_NO_CHANGES_DETECTED);
        assertThat(result.changeAction()).isEqualTo(ChangeAction.SHOW_DETAILS);
        assertThat(result.inputError()).isFalse();
    }

    @Test
    @DisplayName("(C15b) A changed field vs snapshot -> edits re-run and advance to CHANGES_OK_NOT_CONFIRMED")
    void compareOldNew_fieldChanged_advancesToConfirm() {
        AccountUpdateState state = fetchedState();
        state.setOldSnapshot(matchingSnapshot());
        COACTUPForm changed = fullyPopulatedForm();
        changed.setAcsfnam("JANE"); // differs from snapshot "JOHN"

        stubAllDatesValid(); // the change makes the 24-field edit pass run
        AccountUpdateResult result = service.process(changed, PfKey.ENTER, state);

        assertThat(result.changeAction()).isEqualTo(ChangeAction.CHANGES_OK_NOT_CONFIRMED);
        assertThat(result.inputError()).isFalse();
        assertThat(result.message()).isEmpty();
    }

    // ==============================================================================================
    // (D) 2000-DECIDE-ACTION state machine: PF5 confirm/save, PF12 re-fetch, PF3 exit, invalid key
    //     coerced to ENTER (re-edit).
    // ==============================================================================================

    /** A state that has passed the edits and is awaiting PF5 confirmation, with no matching snapshot. */
    private static AccountUpdateState writeReadyState() {
        return awaitingConfirmState(); // CHANGES_OK_NOT_CONFIRMED, oldSnapshot null
    }

    @Test
    @DisplayName("(D16) PF5 on CHANGES_OK_NOT_CONFIRMED -> writeProcessing saves and confirms the update")
    void decideAction_pf5_confirmsAndWrites() {
        when(context.getAcctId()).thenReturn(ACCT_ID);
        when(context.getCustId()).thenReturn(CUST_ID);
        when(accountRepository.findById(ACCT_ID)).thenReturn(Optional.of(freshAccount()));
        when(customerRepository.findById(CUST_ID)).thenReturn(Optional.of(freshCustomer()));

        AccountUpdateResult result = service.process(form, PfKey.PFK05, writeReadyState());

        verify(accountRepository).saveAndFlush(any(Account.class));
        verify(customerRepository).saveAndFlush(any(Customer.class));
        assertThat(result.changeAction()).isEqualTo(ChangeAction.CHANGES_OKAYED_AND_DONE);
    }

    @Test
    @DisplayName("(D17) PF12 re-reads the account (discards edits) and performs no save")
    void decideAction_pf12_reFetchesWithoutSaving() {
        AccountUpdateState state = fetchedState(); // SHOW_DETAILS so PF12 is a valid key

        Account account = new Account();
        account.setAcctId(ACCT_ID);
        account.setActiveStatus("Y");
        Customer customer = new Customer();
        customer.setCustId(CUST_ID);
        customer.setFirstName("JOHN");

        when(context.getAcctId()).thenReturn(ACCT_ID);
        when(context.getCustId()).thenReturn(CUST_ID);
        when(cardXrefRepository.findByXrefAcctId(ACCT_ID))
                .thenReturn(List.of(new CardXref(XREF_CARD_NUM, CUST_ID, ACCT_ID)));
        when(accountRepository.findById(ACCT_ID)).thenReturn(Optional.of(account));
        when(customerRepository.findById(CUST_ID)).thenReturn(Optional.of(customer));
        stubAllDatesValid(); // the edit pass still runs before PF12 re-reads

        AccountUpdateResult result = service.process(form, PfKey.PFK12, state);

        // PF12 re-runs the read chain and never writes.
        verify(cardXrefRepository).findByXrefAcctId(ACCT_ID);
        verify(accountRepository).findById(ACCT_ID);
        verify(customerRepository).findById(CUST_ID);
        verify(accountRepository, never()).saveAndFlush(any(Account.class));
        verify(customerRepository, never()).saveAndFlush(any(Customer.class));
        assertThat(result.changeAction()).isEqualTo(ChangeAction.SHOW_DETAILS);
    }

    @Test
    @DisplayName("(D18a) PF3 exits to the caller (XCTL) and routes back to the menu when origin is blank")
    void decideAction_pf3_exitsToCaller() {
        AccountUpdateResult result = service.process(form, PfKey.PFK03, fetchedState());

        assertThat(result.outcome()).isEqualTo(ScreenOutcome.EXIT_TO_CALLER);
        // Blank origin -> return to the main menu; this program is recorded as the caller.
        verify(context).setToProgram("COMEN01C");
        verify(context).setToTranid("CM00");
        verify(context).setFromProgram("COACTUPC");
        verify(context).setFromTranid("CAUP");
        verify(context).markEnter();
        // Exiting performs no data access.
        verifyNoInteractions(cardXrefRepository, accountRepository, customerRepository);
    }

    @Test
    @DisplayName("(D18b) An unrecognized AID key is coerced to ENTER -> re-edit, no exit, no save")
    void decideAction_unknownKey_coercedToEnterReEdit() {
        // PFK07 is not valid for this state; mainline coerces it to ENTER (re-display the screen),
        // so the fetched screen re-runs its 24-field edit pass (delegated dates stubbed valid).
        stubAllDatesValid();

        AccountUpdateResult result = service.process(form, PfKey.PFK07, fetchedState());

        assertThat(result.outcome()).isEqualTo(ScreenOutcome.SHOW_SCREEN);
        assertThat(result.changeAction()).isEqualTo(ChangeAction.CHANGES_OK_NOT_CONFIRMED);
        verify(accountRepository, never()).saveAndFlush(any(Account.class));
    }

    // ==============================================================================================
    // (E) 9600-WRITE-PROCESSING (@Transactional) + 9700-CHECK-CHANGE-IN-REC. Writes use saveAndFlush;
    //     lock / concurrent-change failures map to the WriteResult-derived change actions.
    // ==============================================================================================

    @Test
    @DisplayName("(E19) Successful update saves BOTH Account and Customer with the edited values")
    void writeProcessing_success_savesAccountAndCustomer() {
        form.setAcrdlim("1000.999"); // 3 decimals prove CobolDecimal DOWN truncation through the service

        when(context.getAcctId()).thenReturn(ACCT_ID);
        when(context.getCustId()).thenReturn(CUST_ID);
        when(accountRepository.findById(ACCT_ID)).thenReturn(Optional.of(freshAccount()));
        when(customerRepository.findById(CUST_ID)).thenReturn(Optional.of(freshCustomer()));

        AccountUpdateResult result = service.process(form, PfKey.PFK05, writeReadyState());

        ArgumentCaptor<Account> accountCaptor = ArgumentCaptor.forClass(Account.class);
        ArgumentCaptor<Customer> customerCaptor = ArgumentCaptor.forClass(Customer.class);
        verify(accountRepository).saveAndFlush(accountCaptor.capture());
        verify(customerRepository).saveAndFlush(customerCaptor.capture());

        Account savedAccount = accountCaptor.getValue();
        assertThat(savedAccount.getActiveStatus()).isEqualTo("Y");
        assertThat(savedAccount.getGroupId()).isEqualTo("GROUP01");
        assertThat(savedAccount.getOpenDate()).isEqualTo(LocalDate.of(2020, 1, 15));
        // Monetary value is a BigDecimal (never float/double), scale 2, truncated DOWN (1000.999 -> 1000.99).
        assertThat(savedAccount.getCreditLimit()).isInstanceOf(BigDecimal.class);
        assertThat(savedAccount.getCreditLimit()).isEqualTo(new BigDecimal("1000.99"));
        assertThat(savedAccount.getCreditLimit().scale()).isEqualTo(2);
        assertThat(savedAccount.getCurrBal()).isEqualTo(new BigDecimal("250.00"));

        Customer savedCustomer = customerCaptor.getValue();
        assertThat(savedCustomer.getFirstName()).isEqualTo("JOHN");
        assertThat(savedCustomer.getLastName()).isEqualTo("DOE");
        assertThat(savedCustomer.getSsn()).isEqualTo(123456789L);
        assertThat(savedCustomer.getFicoCreditScore()).isEqualTo(700);
        assertThat(savedCustomer.getPhoneNum1()).isEqualTo("(212)555-1234");

        assertThat(result.changeAction()).isEqualTo(ChangeAction.CHANGES_OKAYED_AND_DONE);
    }

    @Test
    @DisplayName("(E20a) Account cannot be locked (not found) -> LOCK_ERROR, no save")
    void writeProcessing_accountLockFails_reportsLockError() {
        when(context.getAcctId()).thenReturn(ACCT_ID);
        when(accountRepository.findById(ACCT_ID)).thenReturn(Optional.empty());

        AccountUpdateResult result = service.process(form, PfKey.PFK05, writeReadyState());

        assertThat(result.message()).isEqualTo(MSG_COULD_NOT_LOCK_ACCT);
        assertThat(result.changeAction()).isEqualTo(ChangeAction.CHANGES_OKAYED_LOCK_ERROR);
        assertThat(result.inputError()).isTrue();
        verify(accountRepository, never()).saveAndFlush(any(Account.class));
        verify(customerRepository, never()).saveAndFlush(any(Customer.class));
    }

    @Test
    @DisplayName("(E20b) Customer cannot be locked -> message set, but preserved quirk lands on OKAYED-AND-DONE")
    void writeProcessing_customerLockFails_preservedQuirk() {
        when(context.getAcctId()).thenReturn(ACCT_ID);
        when(context.getCustId()).thenReturn(CUST_ID);
        when(accountRepository.findById(ACCT_ID)).thenReturn(Optional.of(freshAccount()));
        when(customerRepository.findById(CUST_ID)).thenReturn(Optional.empty());

        AccountUpdateResult result = service.process(form, PfKey.PFK05, writeReadyState());

        assertThat(result.message()).isEqualTo(MSG_COULD_NOT_LOCK_CUST);
        // COBOL quirk (preserved): the COULD_NOT_LOCK_CUST result falls through the inner EVALUATE
        // WHEN OTHER, so the change action becomes CHANGES_OKAYED_AND_DONE despite the lock message.
        assertThat(result.changeAction()).isEqualTo(ChangeAction.CHANGES_OKAYED_AND_DONE);
        assertThat(result.inputError()).isTrue();
        verify(accountRepository, never()).saveAndFlush(any(Account.class));
        verify(customerRepository, never()).saveAndFlush(any(Customer.class));
    }

    @Test
    @DisplayName("(E20c) Concurrent change (9700) -> DATA_CHANGED re-displays SHOW_DETAILS, no committed save")
    void writeProcessing_concurrentChange_reDisplaysNoSave() {
        // The re-read DB record differs from the pre-update snapshot (active status Z vs fresh null).
        AccountUpdateState state = awaitingConfirmState();
        AccountSnapshot snapshot = new AccountSnapshot();
        snapshot.setActiveStatus("Z");
        state.setOldSnapshot(snapshot);

        when(context.getAcctId()).thenReturn(ACCT_ID);
        when(context.getCustId()).thenReturn(CUST_ID);
        when(accountRepository.findById(ACCT_ID)).thenReturn(Optional.of(freshAccount()));
        when(customerRepository.findById(CUST_ID)).thenReturn(Optional.of(freshCustomer()));

        AccountUpdateResult result = service.process(form, PfKey.PFK05, state);

        // Not a LogicError: the service re-displays with the "record changed" banner and no save.
        assertThat(result.message()).isEqualTo(MSG_DATA_CHANGED);
        assertThat(result.changeAction()).isEqualTo(ChangeAction.SHOW_DETAILS);
        verify(accountRepository, never()).saveAndFlush(any(Account.class));
        verify(customerRepository, never()).saveAndFlush(any(Customer.class));
    }

    @Test
    @DisplayName("(E20d) Account rewrite failure -> LOCKED_BUT_UPDATE_FAILED; customer save not reached")
    void writeProcessing_rewriteFails_reportsUpdateFailed() {
        when(context.getAcctId()).thenReturn(ACCT_ID);
        when(context.getCustId()).thenReturn(CUST_ID);
        when(accountRepository.findById(ACCT_ID)).thenReturn(Optional.of(freshAccount()));
        when(customerRepository.findById(CUST_ID)).thenReturn(Optional.of(freshCustomer()));
        when(accountRepository.saveAndFlush(any(Account.class)))
                .thenThrow(new DataIntegrityViolationException("rewrite failed"));

        AccountUpdateResult result = service.process(form, PfKey.PFK05, writeReadyState());

        assertThat(result.message()).isEqualTo(MSG_UPDATE_FAILED);
        assertThat(result.changeAction()).isEqualTo(ChangeAction.CHANGES_OKAYED_BUT_FAILED);
        verify(accountRepository).saveAndFlush(any(Account.class));
        // The account rewrite threw first, so the customer rewrite is never attempted.
        verify(customerRepository, never()).saveAndFlush(any(Customer.class));
    }

    // ==============================================================================================
    // (F) Entry semantics: first entry initializes an empty search screen; re-entry drives the
    //     edit/decide path.
    // ==============================================================================================

    @Test
    @DisplayName("(F21a) First entry (new COMMAREA) shows an empty search screen and reads nothing")
    void entry_firstEntry_showsEmptyScreen() {
        AccountUpdateState state = new AccountUpdateState(); // DETAILS_NOT_FETCHED
        when(context.isNew()).thenReturn(true);
        when(context.isProgramEnter()).thenReturn(true);

        AccountUpdateResult result = service.process(form, PfKey.ENTER, state);

        assertThat(result.outcome()).isEqualTo(ScreenOutcome.SHOW_SCREEN);
        assertThat(result.changeAction()).isEqualTo(ChangeAction.DETAILS_NOT_FETCHED);
        assertThat(result.message()).isEmpty();
        verifyNoInteractions(cardXrefRepository, accountRepository, customerRepository);
    }

    @Test
    @DisplayName("(F21b) Re-entry drives the edit/decide path (valid edits advance to confirm)")
    void entry_reEntry_drivesEditDecide() {
        // A fetched, re-entered screen with valid edits advances to awaiting confirmation.
        AccountUpdateResult result = runEditPass();

        assertThat(result.outcome()).isEqualTo(ScreenOutcome.SHOW_SCREEN);
        assertThat(result.changeAction()).isEqualTo(ChangeAction.CHANGES_OK_NOT_CONFIRMED);
        assertThat(result.inputError()).isFalse();
    }
}
