/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License").
 * You may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.aws.carddemo.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.OptimisticLockingFailureException;
import com.aws.carddemo.domain.Account;
import com.aws.carddemo.domain.CardXref;
import com.aws.carddemo.domain.Customer;
import com.aws.carddemo.exception.RecordNotFoundException;
import com.aws.carddemo.repository.AccountRepository;
import com.aws.carddemo.repository.CardXrefRepository;
import com.aws.carddemo.repository.CustomerRepository;
import com.aws.carddemo.service.AccountService.AccountDetail;
import com.aws.carddemo.service.AccountService.AccountUpdateCommand;
import com.aws.carddemo.service.AccountService.AccountUpdateResult;
import com.aws.carddemo.service.AccountService.DateParts;
import com.aws.carddemo.service.AccountService.PhoneParts;
import com.aws.carddemo.service.AccountService.SsnParts;
import com.aws.carddemo.service.AccountService.Status;
import com.aws.carddemo.service.rule.UsSsnRule;
import com.aws.carddemo.service.rule.UsStateZipRule;
import com.aws.carddemo.service.rule.ValidationResult;

/**
 * Pure unit tests for {@link AccountService}, the Java re-platform of the COBOL account
 * programs {@code COACTVWC} (view, transaction {@code CAVW}) and {@code COACTUPC} (update,
 * transaction {@code CAUP} &mdash; the largest online program). This is the highest
 * parity-risk service in the migration (AAP &sect;0.7 hotspots H1/H5/H6), so these tests lock
 * down the observable behavior of:
 *
 * <ul>
 *   <li>the {@code 9000-READ-ACCT} inquiry chain (cross-reference &rarr; account &rarr;
 *       customer) and its {@code NOTFND} outcomes;</li>
 *   <li>the {@code COACTUPC 2000-DECIDE-ACTION} pseudo-conversational state machine
 *       (SHOW_DETAILS, CHANGES_OK_NOT_CONFIRMED, CHANGES_NOT_OK, DONE) and re-entry
 *       handling;</li>
 *   <li>the {@code 1200-EDIT-MAP-INPUTS} field edits with first-message-wins latching and
 *       the exact caller-visible message literals;</li>
 *   <li>monetary handling &mdash; every money field is {@link BigDecimal} at scale 2 and is
 *       asserted with {@code compareTo}/{@code isEqualByComparingTo}, never {@code equals} and
 *       never {@code double}/{@code float} (AAP &sect;0.7 H3);</li>
 *   <li>the READ-UPDATE-REWRITE concurrency guard, reproduced with JPA optimistic locking
 *       (AAP &sect;0.7 H6);</li>
 *   <li>the invariant that SSN, government id, and date of birth are never leaked.</li>
 * </ul>
 *
 * <h2>Test strategy</h2>
 * The suite is a pure Mockito unit test &mdash; NO Spring context, NO database, NO
 * Testcontainers. Every collaborator is a mock and the system under test is constructed
 * explicitly in the authored constructor order. The COBOL edit paragraphs are reproduced as
 * <em>private</em> methods inside {@link AccountService} and are therefore exercised through
 * real field values on the command (only the injected {@link DateValidationService} date
 * backstop and the {@link UsSsnRule} SSN Strategy are stubbable within the edit pass).
 *
 * <h2>Mockito strictness</h2>
 * The suite runs under {@link MockitoExtension} with the default {@code STRICT_STUBS} policy:
 * each test stubs only the collaborators its code path actually reaches. Short-circuiting
 * paths (blank/invalid account id, no-functional-change, unexpected state) assert
 * {@code verifyNoInteractions}/{@code never()} instead of declaring stubs that would never be
 * used, and the read/write chains are stubbed through small helpers so no stub is left
 * unconsumed.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("AccountService — COACTVWC view + COACTUPC update (2000-DECIDE-ACTION) parity")
public class AccountServiceTest {

    /** Account id as typed on the screen filter ({@code ACCTSIDI}); numeric {@code PIC 9(11)}. */
    private static final String ACCT_ID_STR = "1";

    /** The same account id as the {@link Long} primary key the repositories are keyed on. */
    private static final long ACCT_KEY = 1L;

    /**
     * Cross-referenced customer id, deliberately distinct from the account id so a swapped
     * lookup would be caught. It is the {@link Long} primary key of the customer table.
     */
    private static final long CUST_KEY = 555L;

    /**
     * Representative 16-digit card number on the cross-reference row. It is a well-known test
     * PAN, not a real card, and the account flows never expose a CVV.
     */
    private static final String CARD_NUM = "4111111111111111";

    /** Data-access mock for the {@code account} table (COBOL {@code ACCTDAT} / {@code ACCTFILE}). */
    @Mock
    private AccountRepository accountRepository;

    /** Data-access mock for the {@code customer} table (COBOL {@code CUSTDAT} / {@code CUSTFILE}). */
    @Mock
    private CustomerRepository customerRepository;

    /** Data-access mock for the {@code card_xref} table (COBOL {@code CXACAIX} alternate index). */
    @Mock
    private CardXrefRepository cardXrefRepository;

    /** Date backstop mock reproducing the COBOL {@code CALL CSUTLDTC} ({@code EDIT-DATE-LE}). */
    @Mock
    private DateValidationService dateValidationService;

    /** SSN Strategy mock reproducing the shared {@code 1265-EDIT-US-SSN} rule component. */
    @Mock
    private UsSsnRule usSsnRule;

    /** System under test, constructed explicitly in the authored constructor order. */
    private AccountService service;

    /**
     * Builds the system under test before each test, wiring the five mocks through the authored
     * constructor order: account, customer, cross-reference repositories, the date backstop, and
     * the SSN rule. The state/ZIP cross-field rule ({@link UsStateZipRule}) is supplied as its
     * real, dependency-free instance (it owns a static lookup table and is deterministic), so it is
     * never stubbed.
     */
    @BeforeEach
    void setUp() {
        service = new AccountService(
                accountRepository,
                customerRepository,
                cardXrefRepository,
                dateValidationService,
                usSsnRule,
                new UsStateZipRule());
    }

    // ====================================================================================
    // Fixtures
    // ====================================================================================

    /**
     * Builds the fixed cross-reference row linking {@link #CARD_NUM} to {@link #CUST_KEY} and
     * {@link #ACCT_KEY}, matching the {@code CXACAIX} alternate-index browse the read chain uses.
     *
     * @return a fresh cross-reference fixture
     */
    private static CardXref xref() {
        return new CardXref(CARD_NUM, CUST_KEY, ACCT_KEY);
    }

    /**
     * Builds the stored account master row whose field values are the canonical baseline the
     * {@link CommandBuilder} mirrors, so an unedited command compares equal ({@code 1205-COMPARE
     * -OLD-NEW}). Monetary fields are {@link BigDecimal} at scale 2 and the {@code @Version}
     * column is seeded to {@code 0} to exercise the optimistic-lock path.
     *
     * @return a fresh account fixture at version 0
     */
    private static Account storedAccount() {
        Account account = new Account(
                ACCT_KEY,                    // acctId
                "Y",                         // acctActiveStatus
                new BigDecimal("1234.56"),   // currBal
                new BigDecimal("5000.00"),   // creditLimit
                new BigDecimal("1000.00"),   // cashCreditLimit
                "2020-01-15",                // acctOpenDate
                "2030-12-31",                // acctExpirationDate
                "2020-06-01",                // acctReissueDate
                new BigDecimal("250.00"),    // currCycCredit
                new BigDecimal("75.25"),     // currCycDebit
                "20374",                     // acctAddrZip
                "A000000000");               // groupId
        account.setVersion(0L);
        return account;
    }

    /**
     * Builds the stored customer master row whose field values mirror the canonical command. The
     * sensitive columns (SSN, government id, DOB) hold synthetic, non-real values and are never
     * asserted in full.
     *
     * @return a fresh customer fixture at version 0
     */
    private static Customer storedCustomer() {
        Customer customer = new Customer(
                CUST_KEY,        // custId
                "Grace",         // custFirstName
                "Brewster",      // custMiddleName
                "Franklin",      // custLastName
                "1 Navy Yard",   // custAddrLine1
                "",              // custAddrLine2
                "Washington",    // custAddrLine3 (the screen "city" maps to ADDR-LINE-3)
                "DC",            // custAddrStateCd
                "USA",           // custAddrCountryCd
                "20374",         // custAddrZip
                null,            // custPhoneNum1 (blank phone -> no digits)
                null,            // custPhoneNum2
                null,            // custSsn (blank ssn parts on the command -> no digits)
                "GID1",          // custGovtIssuedId
                "1980-05-20",    // custDob
                "1234567890",    // custEftAccountId
                "Y",             // custPriCardHolderInd
                Integer.valueOf(700)); // custFicoCreditScore
        customer.setVersion(0L);
        return customer;
    }

    /**
     * Stubs the {@code 9000-READ-ACCT} inquiry chain (cross-reference &rarr; account &rarr;
     * customer) with every record present. Used by the paths that read the full chain.
     *
     * @param account  the account the master read returns
     * @param customer the customer the master read returns
     */
    private void stubReadChainPresent(Account account, Customer customer) {
        when(cardXrefRepository.findFirstByAcctIdOrderByXrefCardNumAsc(ACCT_KEY))
                .thenReturn(Optional.of(xref()));
        when(accountRepository.findById(ACCT_KEY)).thenReturn(Optional.of(account));
        when(customerRepository.findById(CUST_KEY)).thenReturn(Optional.of(customer));
    }

    /**
     * Stubs the {@code 9600-WRITE-PROCESSING} read chain used by {@code performWrite}: the account
     * is re-read under update intent via the force-increment query, then the cross-reference and
     * customer are resolved.
     *
     * @param account  the account the versioned update read returns
     * @param customer the customer the master read returns
     */
    private void stubWriteChainPresent(Account account, Customer customer) {
        when(accountRepository.findByIdForVersionedUpdate(ACCT_KEY)).thenReturn(Optional.of(account));
        when(cardXrefRepository.findFirstByAcctIdOrderByXrefCardNumAsc(ACCT_KEY))
                .thenReturn(Optional.of(xref()));
        when(customerRepository.findById(CUST_KEY)).thenReturn(Optional.of(customer));
    }

    /**
     * Stubs the collaborators reached inside a clean {@code 1200-EDIT-MAP-INPUTS} pass over the
     * canonical command: the date backstop accepts every composed date (the four date fields), the
     * date-of-birth parse returns a date safely in the past, and the SSN rule returns valid.
     */
    private void stubEditCollaboratorsValid() {
        when(dateValidationService.isValid(anyString(), anyString())).thenReturn(true);
        when(dateValidationService.parse(anyString())).thenReturn(LocalDate.of(1980, 5, 20));
        when(usSsnRule.validate(anyString(), anyString(), anyString()))
                .thenReturn(ValidationResult.valid());
    }

    /**
     * A mutable builder for {@link AccountUpdateCommand} whose defaults are the canonical, fully
     * valid values that compare equal to {@link #storedAccount()} / {@link #storedCustomer()}. A
     * test overrides exactly the field(s) under test; every other field stays valid so the field
     * under test drives the (first) latched message.
     */
    private static CommandBuilder baseCommand() {
        return new CommandBuilder();
    }

    /** Fluent builder mirroring the 32-component {@link AccountUpdateCommand} record. */
    private static final class CommandBuilder {

        private String accountId = ACCT_ID_STR;
        private Long expectedVersion = 0L;
        private String accountStatus = "Y";
        private DateParts openDate = new DateParts("2020", "01", "15");
        private String creditLimit = "5000.00";
        private DateParts expiryDate = new DateParts("2030", "12", "31");
        private String cashCreditLimit = "1000.00";
        private DateParts reissueDate = new DateParts("2020", "06", "01");
        private String currentBalance = "1234.56";
        private String currentCycleCredit = "250.00";
        private String currentCycleDebit = "75.25";
        private String groupId = "A000000000";
        private SsnParts ssn = new SsnParts("", "", "");
        private DateParts dob = new DateParts("1980", "05", "20");
        private String ficoScore = "700";
        private String firstName = "Grace";
        private String middleName = "Brewster";
        private String lastName = "Franklin";
        private String addressLine1 = "1 Navy Yard";
        private String addressLine2 = "";
        private String city = "Washington";
        private String stateCode = "DC";
        private String zipCode = "20374";
        private String countryCode = "USA";
        private PhoneParts phone1 = new PhoneParts("", "", "");
        private PhoneParts phone2 = new PhoneParts("", "", "");
        private String governmentId = "GID1";
        private String eftAccountId = "1234567890";
        private String primaryHolderFlag = "Y";
        private Status priorStatus = Status.SHOW_DETAILS;
        private boolean confirmSave;
        private boolean cancel;

        private CommandBuilder accountId(String value) {
            this.accountId = value;
            return this;
        }

        private CommandBuilder expectedVersion(Long value) {
            this.expectedVersion = value;
            return this;
        }

        private CommandBuilder creditLimit(String value) {
            this.creditLimit = value;
            return this;
        }

        private CommandBuilder currentBalance(String value) {
            this.currentBalance = value;
            return this;
        }

        private CommandBuilder ficoScore(String value) {
            this.ficoScore = value;
            return this;
        }

        private CommandBuilder stateCode(String value) {
            this.stateCode = value;
            return this;
        }

        private CommandBuilder lastName(String value) {
            this.lastName = value;
            return this;
        }

        private CommandBuilder priorStatus(Status value) {
            this.priorStatus = value;
            return this;
        }

        private CommandBuilder confirmSave(boolean value) {
            this.confirmSave = value;
            return this;
        }

        private CommandBuilder cancel(boolean value) {
            this.cancel = value;
            return this;
        }

        private AccountUpdateCommand build() {
            return new AccountUpdateCommand(
                    accountId, expectedVersion, accountStatus, openDate, creditLimit, expiryDate,
                    cashCreditLimit, reissueDate, currentBalance, currentCycleCredit,
                    currentCycleDebit, groupId, ssn, dob, ficoScore, firstName, middleName,
                    lastName, addressLine1, addressLine2, city, stateCode, zipCode, countryCode,
                    phone1, phone2, governmentId, eftAccountId, primaryHolderFlag, priorStatus,
                    confirmSave, cancel);
        }
    }

    // ====================================================================================
    // viewAccount — COACTVWC inquiry (9000-READ-ACCT: xref -> account -> customer)
    // ====================================================================================

    @Test
    @DisplayName("viewAccount reads xref -> account -> customer and returns the merged detail; money is scale-2 BigDecimal")
    void viewAccount_happyPath_returnsMergedDetail() {
        stubReadChainPresent(storedAccount(), storedCustomer());

        AccountDetail detail = service.viewAccount(ACCT_KEY);

        assertThat(detail).isNotNull();
        assertThat(detail.account().getAcctId()).isEqualTo(ACCT_KEY);
        assertThat(detail.customer().getCustId()).isEqualTo(CUST_KEY);
        // Monetary parity (AAP 0.7 H3): compare by value at scale 2, never equals/double/float.
        assertThat(detail.account().getCurrBal()).isEqualByComparingTo(new BigDecimal("1234.56"));
        assertThat(detail.account().getCurrBal().scale()).isEqualTo(2);
        assertThat(detail.account().getCreditLimit()).isEqualByComparingTo(new BigDecimal("5000.00"));
    }

    @Test
    @DisplayName("viewAccount raises RecordNotFoundException when the cross-reference is missing (9200 NOTFND)")
    void viewAccount_xrefMissing_throwsRecordNotFound() {
        when(cardXrefRepository.findFirstByAcctIdOrderByXrefCardNumAsc(ACCT_KEY))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.viewAccount(ACCT_KEY))
                .isInstanceOf(RecordNotFoundException.class)
                .hasMessageContaining("not found in Cross ref file.");

        verifyNoInteractions(accountRepository, customerRepository);
    }

    @Test
    @DisplayName("viewAccount raises RecordNotFoundException when the account master row is missing (9300 NOTFND)")
    void viewAccount_accountMissing_throwsRecordNotFound() {
        when(cardXrefRepository.findFirstByAcctIdOrderByXrefCardNumAsc(ACCT_KEY))
                .thenReturn(Optional.of(xref()));
        when(accountRepository.findById(ACCT_KEY)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.viewAccount(ACCT_KEY))
                .isInstanceOf(RecordNotFoundException.class)
                .hasMessageContaining("not found in Acct Master file.");

        verifyNoInteractions(customerRepository);
    }

    @Test
    @DisplayName("viewAccount raises RecordNotFoundException when the customer master row is missing (9400 NOTFND)")
    void viewAccount_customerMissing_throwsRecordNotFound() {
        when(cardXrefRepository.findFirstByAcctIdOrderByXrefCardNumAsc(ACCT_KEY))
                .thenReturn(Optional.of(xref()));
        when(accountRepository.findById(ACCT_KEY)).thenReturn(Optional.of(storedAccount()));
        when(customerRepository.findById(CUST_KEY)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.viewAccount(ACCT_KEY))
                .isInstanceOf(RecordNotFoundException.class)
                .hasMessageContaining("not found in customer master.");
    }

    @Test
    @DisplayName("viewAccount rejects a zero or over-11-digit filter with IllegalArgumentException (2210-EDIT-ACCOUNT) and reads nothing")
    void viewAccount_invalidFilter_throwsIllegalArgument() {
        assertThatThrownBy(() -> service.viewAccount(0L))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage(AccountService.MSG_ACCT_FILTER_INVALID);
        assertThatThrownBy(() -> service.viewAccount(100_000_000_000L))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage(AccountService.MSG_ACCT_FILTER_INVALID);

        verifyNoInteractions(cardXrefRepository, accountRepository, customerRepository);
    }

    @Test
    @DisplayName("viewAccount never leaks SSN / government id / date of birth: the customer's toString omits them")
    void viewAccount_doesNotLeakSensitiveCustomerData() {
        // Synthetic, obviously-fake sentinels for the sensitive columns; none is a real value and
        // none is ever asserted "in full" — the test proves only that they cannot escape via
        // toString(), honoring the SSN/govt-id/DOB sensitivity discipline (AAP 0.7 / 0.9.3).
        String ssnSentinel = "SSN-DO-NOT-LEAK";
        String govtSentinel = "GOVT-DO-NOT-LEAK";
        String dobSentinel = "DOB-DO-NOT-LEAK";
        Customer sensitive = new Customer(
                CUST_KEY, "Grace", "Brewster", "Franklin",
                "1 Navy Yard", "", "Washington", "DC", "USA", "20374",
                null, null, ssnSentinel, govtSentinel, dobSentinel,
                "1234567890", "Y", Integer.valueOf(700));
        stubReadChainPresent(storedAccount(), sensitive);

        AccountDetail detail = service.viewAccount(ACCT_KEY);

        // The sensitive values must not appear anywhere in the rendered customer or merged detail.
        assertThat(detail.customer().toString())
                .doesNotContain(ssnSentinel, govtSentinel, dobSentinel)
                .doesNotContain("custSsn", "custGovtIssuedId", "custDob");
        assertThat(detail.toString()).doesNotContain(ssnSentinel, govtSentinel, dobSentinel);
        // Sanity: non-sensitive identity fields are still present.
        assertThat(detail.customer().toString()).contains("custId=" + CUST_KEY, "Grace");
    }

    // ====================================================================================
    // updateAccount — COACTUPC (0000-MAIN re-entry + 2000-DECIDE-ACTION state machine)
    // ====================================================================================

    @Test
    @DisplayName("first entry (reentry=false) fetches the account and returns SHOW_DETAILS with the change prompt")
    void updateAccount_firstEntry_showsDetails() {
        stubReadChainPresent(storedAccount(), storedCustomer());

        AccountUpdateResult result = service.updateAccount(baseCommand().build(), false);

        assertThat(result.status()).isEqualTo(Status.SHOW_DETAILS);
        assertThat(result.message()).isEqualTo(AccountService.MSG_PROMPT_CHANGES);
        assertThat(result.detail()).isNotNull();
        assertThat(result.detail().account().getAcctId()).isEqualTo(ACCT_KEY);
        assertThat(result.detail().customer().getCustId()).isEqualTo(CUST_KEY);
        verify(accountRepository, never()).save(any(Account.class));
    }

    @Test
    @DisplayName("blank account id returns CHANGES_NOT_OK with 'Account number not provided' and reads nothing")
    void updateAccount_blankAccountId_returnsChangesNotOk() {
        AccountUpdateResult result = service.updateAccount(baseCommand().accountId("").build(), false);

        assertThat(result.status()).isEqualTo(Status.CHANGES_NOT_OK);
        assertThat(result.message()).isEqualTo(AccountService.MSG_ACCT_NOT_PROVIDED);
        assertThat(result.detail()).isNull();
        verifyNoInteractions(cardXrefRepository, accountRepository, customerRepository);
    }

    @Test
    @DisplayName("non-numeric account id returns CHANGES_NOT_OK with the '11 digit Non-Zero Number' literal and reads nothing")
    void updateAccount_nonNumericAccountId_returnsChangesNotOk() {
        AccountUpdateResult result =
                service.updateAccount(baseCommand().accountId("12A").build(), false);

        assertThat(result.status()).isEqualTo(Status.CHANGES_NOT_OK);
        assertThat(result.message()).isEqualTo(AccountService.MSG_ACCT_NON_ZERO_11);
        assertThat(result.detail()).isNull();
        verifyNoInteractions(cardXrefRepository, accountRepository, customerRepository);
    }

    @Test
    @DisplayName("zero account id returns CHANGES_NOT_OK with the '11 digit Non-Zero Number' literal and reads nothing")
    void updateAccount_zeroAccountId_returnsChangesNotOk() {
        AccountUpdateResult result =
                service.updateAccount(baseCommand().accountId("0").build(), false);

        assertThat(result.status()).isEqualTo(Status.CHANGES_NOT_OK);
        assertThat(result.message()).isEqualTo(AccountService.MSG_ACCT_NON_ZERO_11);
        assertThat(result.detail()).isNull();
        verifyNoInteractions(cardXrefRepository, accountRepository, customerRepository);
    }

    @Test
    @DisplayName("resubmit with no functional change returns SHOW_DETAILS + 'No change detected...' (1205) and never writes")
    void updateAccount_noFunctionalChange_returnsShowDetails() {
        stubReadChainPresent(storedAccount(), storedCustomer());

        AccountUpdateResult result = service.updateAccount(
                baseCommand().priorStatus(Status.SHOW_DETAILS).build(), true);

        assertThat(result.status()).isEqualTo(Status.SHOW_DETAILS);
        assertThat(result.message()).isEqualTo(AccountService.MSG_NO_CHANGES);
        verify(accountRepository, never()).save(any(Account.class));
        verify(customerRepository, never()).save(any(Customer.class));
    }

    @Test
    @DisplayName("valid edits, not yet confirmed, return CHANGES_OK_NOT_CONFIRMED + confirmation prompt and never write")
    void updateAccount_validChangesNotConfirmed_returnsChangesOkNotConfirmed() {
        stubReadChainPresent(storedAccount(), storedCustomer());
        stubEditCollaboratorsValid();

        // A single valid edit (new current balance) makes 1205 report a change so the edits run.
        AccountUpdateResult result = service.updateAccount(
                baseCommand().priorStatus(Status.SHOW_DETAILS).currentBalance("2222.22").build(), true);

        assertThat(result.status()).isEqualTo(Status.CHANGES_OK_NOT_CONFIRMED);
        assertThat(result.message()).isEqualTo(AccountService.MSG_PROMPT_CONFIRMATION);
        assertThat(result.detail()).isNotNull();
        // Nothing is persisted until the change is confirmed (PF05).
        verify(accountRepository, never()).save(any(Account.class));
        verify(customerRepository, never()).save(any(Customer.class));
    }

    @Test
    @DisplayName("the confirmation preview echoes the SUBMITTED candidate values, not the stored record (QA F-CAUP-2)")
    void updateAccount_confirmationPreview_echoesSubmittedCandidateValues() {
        stubReadChainPresent(storedAccount(), storedCustomer());
        stubEditCollaboratorsValid();

        // COBOL 3203-SHOW-UPDATED-VALUES re-displays the typed ACUP-NEW-* fields. The stored credit
        // limit is 5000.00; the user submits 8888.00 and a changed last name. The confirmation
        // preview must echo the SUBMITTED values, not the un-mutated master record.
        AccountUpdateResult result = service.updateAccount(
                baseCommand()
                        .priorStatus(Status.SHOW_DETAILS)
                        .creditLimit("8888.00")
                        .lastName("Changed")
                        .build(),
                true);

        assertThat(result.status()).isEqualTo(Status.CHANGES_OK_NOT_CONFIRMED);
        assertThat(result.message()).isEqualTo(AccountService.MSG_PROMPT_CONFIRMATION);
        // The echoed candidate values (not the stored 5000.00 / original last name).
        assertThat(result.detail().account().getCreditLimit())
                .isEqualByComparingTo(new BigDecimal("8888.00"));
        assertThat(result.detail().account().getCreditLimit().scale()).isEqualTo(2);
        assertThat(result.detail().customer().getCustLastName()).isEqualTo("Changed");
        // The optimistic-lock version is carried across so the client still gets the version header.
        assertThat(result.detail().account().getVersion()).isEqualTo(0L);
        // Nothing is persisted at the preview step (no premature flush of the managed record).
        verify(accountRepository, never()).save(any(Account.class));
        verify(customerRepository, never()).save(any(Customer.class));
    }

    @Test
    @DisplayName("FICO score outside 300-850 returns CHANGES_NOT_OK with the verbatim range message and never writes")
    void updateAccount_ficoOutOfRange_returnsChangesNotOk() {
        stubReadChainPresent(storedAccount(), storedCustomer());
        stubEditCollaboratorsValid();

        AccountUpdateResult result = service.updateAccount(
                baseCommand().priorStatus(Status.SHOW_DETAILS).ficoScore("200").build(), true);

        assertThat(result.status()).isEqualTo(Status.CHANGES_NOT_OK);
        assertThat(result.message()).isEqualTo("FICO Score: should be between 300 and 850");
        verify(accountRepository, never()).save(any(Account.class));
    }

    @Test
    @DisplayName("an invalid US state code returns CHANGES_NOT_OK with the verbatim state message and never writes")
    void updateAccount_invalidStateCode_returnsChangesNotOk() {
        stubReadChainPresent(storedAccount(), storedCustomer());
        stubEditCollaboratorsValid();

        AccountUpdateResult result = service.updateAccount(
                baseCommand().priorStatus(Status.SHOW_DETAILS).stateCode("ZZ").build(), true);

        assertThat(result.status()).isEqualTo(Status.CHANGES_NOT_OK);
        assertThat(result.message()).isEqualTo("State: is not a valid state code");
        verify(accountRepository, never()).save(any(Account.class));
    }

    @Test
    @DisplayName("a non-numeric signed money field returns CHANGES_NOT_OK with 'Credit Limit is not valid' and never writes")
    void updateAccount_invalidSignedAmount_returnsChangesNotOk() {
        stubReadChainPresent(storedAccount(), storedCustomer());
        stubEditCollaboratorsValid();

        AccountUpdateResult result = service.updateAccount(
                baseCommand().priorStatus(Status.SHOW_DETAILS).creditLimit("ABC").build(), true);

        assertThat(result.status()).isEqualTo(Status.CHANGES_NOT_OK);
        assertThat(result.message()).isEqualTo("Credit Limit is not valid");
        verify(accountRepository, never()).save(any(Account.class));
    }

    @Test
    @DisplayName("editSsn surfaces the injected UsSsnRule's message verbatim as CHANGES_NOT_OK and never writes")
    void updateAccount_ssnRuleInvalid_returnsChangesNotOk() {
        stubReadChainPresent(storedAccount(), storedCustomer());
        // Dates accept; the SSN Strategy rejects. A valid balance edit forces the edit pass to run.
        when(dateValidationService.isValid(anyString(), anyString())).thenReturn(true);
        when(dateValidationService.parse(anyString())).thenReturn(LocalDate.of(1980, 5, 20));
        when(usSsnRule.validate(anyString(), anyString(), anyString()))
                .thenReturn(ValidationResult.invalid("SSN-1: must be all numeric."));

        AccountUpdateResult result = service.updateAccount(
                baseCommand().priorStatus(Status.SHOW_DETAILS).currentBalance("2222.22").build(), true);

        assertThat(result.status()).isEqualTo(Status.CHANGES_NOT_OK);
        assertThat(result.message()).isEqualTo("SSN-1: must be all numeric.");
        verify(accountRepository, never()).save(any(Account.class));
    }

    @Test
    @DisplayName("confirmed valid edits persist both records and return DONE; edited money is stored as scale-2 BigDecimal")
    void updateAccount_confirmedValidChanges_persistsAndReturnsDone() {
        Account account = storedAccount();
        Customer customer = storedCustomer();
        stubWriteChainPresent(account, customer);
        stubEditCollaboratorsValid();
        when(accountRepository.save(any(Account.class))).thenReturn(account);
        when(customerRepository.save(any(Customer.class))).thenReturn(customer);

        AccountUpdateResult result = service.updateAccount(
                baseCommand()
                        .priorStatus(Status.CHANGES_OK_NOT_CONFIRMED)
                        .confirmSave(true)
                        .currentBalance("2222.22")
                        .creditLimit("9999.99")
                        .build(),
                true);

        assertThat(result.status()).isEqualTo(Status.DONE);
        assertThat(result.message()).isEqualTo(AccountService.MSG_CONFIRM_SUCCESS);

        ArgumentCaptor<Account> accountCaptor = ArgumentCaptor.forClass(Account.class);
        verify(accountRepository).save(accountCaptor.capture());
        verify(customerRepository).save(any(Customer.class));
        Account saved = accountCaptor.getValue();
        // Edited money is applied through Money (scale 2, HALF_UP); assert by value + scale.
        assertThat(saved.getCurrBal()).isEqualByComparingTo(new BigDecimal("2222.22"));
        assertThat(saved.getCurrBal().scale()).isEqualTo(2);
        assertThat(saved.getCreditLimit()).isEqualByComparingTo(new BigDecimal("9999.99"));
        assertThat(saved.getCreditLimit().scale()).isEqualTo(2);
    }

    @Test
    @DisplayName("awaiting confirmation without PF05 re-reads and re-shows CHANGES_OK_NOT_CONFIRMED; nothing is written")
    void updateAccount_awaitingConfirmationWithoutConfirm_reshowsConfirmation() {
        stubReadChainPresent(storedAccount(), storedCustomer());
        // reshowConfirm re-runs the field edits defensively (mirroring performWrite) before echoing
        // the candidate values in the preview (QA finding F-CAUP-2), so the edit collaborators are
        // reached on this path and must be stubbed valid.
        stubEditCollaboratorsValid();

        AccountUpdateResult result = service.updateAccount(
                baseCommand().priorStatus(Status.CHANGES_OK_NOT_CONFIRMED).confirmSave(false).build(), true);

        assertThat(result.status()).isEqualTo(Status.CHANGES_OK_NOT_CONFIRMED);
        assertThat(result.message()).isEqualTo(AccountService.MSG_PROMPT_CONFIRMATION);
        verify(accountRepository, never()).save(any(Account.class));
    }

    @Test
    @DisplayName("a fetched version older than the persisted row throws OptimisticLockingFailureException (9700) and writes nothing")
    void updateAccount_versionMismatch_throwsOptimisticLockingFailure() {
        // AAP 0.7.1 H6 / docs/decision-log.md: the READ-UPDATE-REWRITE integrity guard is an
        // INTENTIONAL, documented improvement implemented with JPA @Version optimistic locking.
        // The service throws OptimisticLockingFailureException, which the web layer maps to HTTP 409
        // (the documented Status.CONCURRENT_CHANGE semantic) — this is not a behavioral regression.
        Account account = storedAccount(); // persisted at version 0
        when(accountRepository.findByIdForVersionedUpdate(ACCT_KEY)).thenReturn(Optional.of(account));

        assertThatThrownBy(() -> service.updateAccount(
                baseCommand()
                        .priorStatus(Status.CHANGES_OK_NOT_CONFIRMED)
                        .confirmSave(true)
                        .expectedVersion(7L) // client fetched an older version than persisted (0)
                        .build(),
                true))
                .isInstanceOf(OptimisticLockingFailureException.class)
                .hasMessage(AccountService.MSG_DATA_CHANGED);

        verify(accountRepository, never()).save(any(Account.class));
        verify(customerRepository, never()).save(any(Customer.class));
        verifyNoInteractions(cardXrefRepository);
    }

    @Test
    @DisplayName("a save-time optimistic-lock failure propagates (concurrent change at commit); the customer is not saved")
    void updateAccount_saveDetectsConcurrentChange_throwsOptimisticLockingFailure() {
        Account account = storedAccount();
        Customer customer = storedCustomer();
        stubWriteChainPresent(account, customer);
        stubEditCollaboratorsValid();
        when(accountRepository.save(any(Account.class)))
                .thenThrow(new OptimisticLockingFailureException("row updated by another transaction"));

        assertThatThrownBy(() -> service.updateAccount(
                baseCommand()
                        .priorStatus(Status.CHANGES_OK_NOT_CONFIRMED)
                        .confirmSave(true)
                        .expectedVersion(null) // skip the pre-check; exercise the save-time failure
                        .build(),
                true))
                .isInstanceOf(OptimisticLockingFailureException.class);

        verify(customerRepository, never()).save(any(Customer.class));
    }

    @Test
    @DisplayName("PF12 cancel re-reads the record and returns SHOW_DETAILS, discarding pending edits")
    void updateAccount_cancel_reReadsAndShowsDetails() {
        stubReadChainPresent(storedAccount(), storedCustomer());

        AccountUpdateResult result = service.updateAccount(
                baseCommand().priorStatus(Status.CHANGES_OK_NOT_CONFIRMED).cancel(true).build(), true);

        assertThat(result.status()).isEqualTo(Status.SHOW_DETAILS);
        assertThat(result.message()).isEqualTo(AccountService.MSG_PROMPT_CHANGES);
        verify(accountRepository, never()).save(any(Account.class));
    }

    @Test
    @DisplayName("a completed (DONE) screen re-fetches fresh details on the next request")
    void updateAccount_priorDone_reFetchesDetails() {
        stubReadChainPresent(storedAccount(), storedCustomer());

        AccountUpdateResult result = service.updateAccount(
                baseCommand().priorStatus(Status.DONE).build(), true);

        assertThat(result.status()).isEqualTo(Status.SHOW_DETAILS);
        assertThat(result.message()).isEqualTo(AccountService.MSG_PROMPT_CHANGES);
    }

    @Test
    @DisplayName("a transient outcome token resubmitted as prior status abends with 'UNEXPECTED DATA SCENARIO' and reads nothing")
    void updateAccount_unexpectedPriorStatus_throwsIllegalState() {
        assertThatThrownBy(() -> service.updateAccount(
                baseCommand().priorStatus(Status.LOCK_ERROR).build(), true))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("UNEXPECTED DATA SCENARIO");

        verifyNoInteractions(cardXrefRepository, accountRepository, customerRepository);
    }

}
