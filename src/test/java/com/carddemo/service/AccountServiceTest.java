package com.carddemo.service;

import java.lang.reflect.RecordComponent;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.orm.ObjectOptimisticLockingFailureException;

import com.carddemo.dto.AccountResponse;
import com.carddemo.dto.AccountUpdateRequest;
import com.carddemo.entity.Account;
import com.carddemo.entity.CardXref;
import com.carddemo.entity.Customer;
import com.carddemo.exception.ConcurrentModificationException;
import com.carddemo.exception.ResourceNotFoundException;
import com.carddemo.mapper.AccountMapper;
import com.carddemo.repository.AccountRepository;
import com.carddemo.repository.CardXrefRepository;
import com.carddemo.repository.CustomerRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Pure Mockito unit test for {@link AccountService}, the Java replacement for the two CICS online
 * account programs {@code COACTVWC} (account view) and {@code COACTUPC} (account maintenance).
 *
 * <h2>What this test pins (AAP &sect;0.4.1.3 / &sect;0.6.6 / &sect;0.6.8 / &sect;0.7.1)</h2>
 * <ol>
 *   <li><strong>Flattened account&nbsp;+&nbsp;customer view.</strong>
 *       {@link AccountService#getAccount(Long)} resolves the owning customer through the legacy
 *       ACCOUNT&nbsp;&rarr;&nbsp;CARD-XREF&nbsp;&rarr;&nbsp;CUSTOMER read chain and flattens both records
 *       into a single {@link AccountResponse}. We assert the account half (id, money fields, dates,
 *       group/zip), the customer half (id, names), and that monetary values are {@link BigDecimal} of
 *       scale&nbsp;2 (compared numerically with {@code isEqualByComparingTo}).</li>
 *   <li><strong>SSN suppression to last four (the headline PII rule).</strong> The flattened view MUST
 *       expose only {@code ssnLastFour}; the synthetic nine-digit SSN {@code 123456789} surfaces as
 *       {@code "6789"} and never in full. We prove this three ways: a value assertion
 *       ({@code ssnLastFour == "6789"} with {@code hasSize(4)}); a <em>structural</em> reflection over
 *       the {@link AccountResponse} record components proving no full-SSN component exists; and a JSON
 *       serialization check proving the full SSN never leaks while the last four are present.</li>
 *   <li><strong>Optimistic-lock round-trip &rarr; HTTP&nbsp;409 (the core parity point).</strong> The
 *       JPA {@code @Version} token is echoed on the view and re-checked on update. A stale version
 *       trips the explicit, deterministic guard and raises the CardDemo domain
 *       {@link ConcurrentModificationException}; a flush-race that slips past it is caught by the JPA
 *       {@code @Version} backstop ({@link ObjectOptimisticLockingFailureException}). Both map to
 *       HTTP&nbsp;409 in {@code GlobalExceptionHandler}, reproducing {@code COACTUPC}'s
 *       {@code READ ... UPDATE} / {@code REWRITE} lost-update guard.</li>
 *   <li><strong>404 on every missing hop.</strong> A missing account, a missing cross-reference, or a
 *       missing customer all surface as {@link ResourceNotFoundException} (HTTP&nbsp;404).</li>
 * </ol>
 *
 * <h2>Test character</h2>
 * <p>This is a <strong>pure unit test</strong>: {@code @ExtendWith(MockitoExtension.class)} with no
 * Spring context and no database. The real {@link AccountService} constructor declares four
 * collaborators &mdash; {@link AccountRepository}, {@link CustomerRepository},
 * {@link CardXrefRepository}, and {@link AccountMapper} &mdash; so all four are {@code @Mock}ed and
 * wired via {@code @InjectMocks}. Because the service delegates the flatten / SSN-mask projection and
 * the editable-field apply to the mapper, the {@code AccountMapper} mock is given a faithful
 * {@code thenAnswer}/{@code doAnswer} behaviour that mirrors the real {@code AccountMapper} +
 * {@code CustomerMapper} (it masks the SSN to the last four, echoes the {@code @Version} token, and
 * applies only the editable subset while never touching the immutable keys or the version). This keeps
 * the captured entities and responses meaningful &mdash; the PII, version, and scale assertions test
 * real behaviour &mdash; without coupling the test to a concrete mapper instance.</p>
 *
 * <p>{@code MockitoExtension} runs in strict-stubbing mode: every stub declared in a test is exercised
 * by that test (the not-found tests deliberately stub only the hops they actually reach), and argument
 * captors are used in the {@code verify} position rather than during stubbing, per Mockito best
 * practice.</p>
 *
 * <p><strong>Name-clash guard (AAP critical):</strong> the conflict exception under test is
 * {@link com.carddemo.exception.ConcurrentModificationException}, whose simple name collides with
 * {@code java.util.ConcurrentModificationException}. This file imports the {@code com.carddemo} type
 * explicitly and never imports the {@code java.util} variant.</p>
 *
 * @see AccountService
 * @see AccountRepository
 * @see CardXrefRepository
 * @see CustomerRepository
 * @see AccountMapper
 * @see AccountResponse
 * @see AccountUpdateRequest
 */
@ExtendWith(MockitoExtension.class)
class AccountServiceTest {

    // ---------------------------------------------------------------------------------------------
    // Fixture constants — a single, well-known account and its owning customer. The SSN is the
    // synthetic value 123456789 (NEVER a real person's SSN); its last four digits are "6789".
    // ---------------------------------------------------------------------------------------------

    /** Account identifier ({@code ACCT-ID PIC 9(11)}); the immutable primary key. */
    private static final Long ACCT_ID = 10L;
    /** Owning customer identifier ({@code CUST-ID PIC 9(09)}); the immutable customer key. */
    private static final Long CUST_ID = 99L;
    /** 16-character card number that owns the cross-reference row resolving account &rarr; customer. */
    private static final String CARD_NUM = "4111111111111111";

    /** Synthetic full SSN ({@code CUST-SSN PIC 9(09)}) — input/persisted only, never serialized out. */
    private static final String FULL_SSN = "123456789";
    /** The only SSN-derived value allowed outward: the last four digits of {@link #FULL_SSN}. */
    private static final String SSN_LAST_FOUR = "6789";

    /** Optimistic-lock version observed on the view path; a distinctive (non-default) value. */
    private static final Long VIEW_VERSION = 7L;
    /** Optimistic-lock version persisted at the start of the update tests. */
    private static final Long BASE_VERSION = 5L;
    /** A stale version a client might echo after another writer advanced the row; &ne; {@link #BASE_VERSION}. */
    private static final Long STALE_VERSION = 3L;

    // Money fixtures — all scale 2 to mirror the COBOL S9(10)V99 / NUMERIC(12,2) money columns.
    private static final BigDecimal CURR_BAL = new BigDecimal("1234.56");
    private static final BigDecimal CREDIT_LIMIT = new BigDecimal("5000.00");
    private static final BigDecimal CASH_CREDIT_LIMIT = new BigDecimal("1000.00");
    private static final BigDecimal CURR_CYC_CREDIT = new BigDecimal("250.00");
    private static final BigDecimal CURR_CYC_DEBIT = new BigDecimal("75.00");

    // Date fixtures — ISO calendar dates mirroring the COBOL X(10) date fields.
    private static final LocalDate OPEN_DATE = LocalDate.of(2020, 1, 15);
    private static final LocalDate EXPIRATION_DATE = LocalDate.of(2027, 1, 31);
    private static final LocalDate REISSUE_DATE = LocalDate.of(2024, 6, 1);
    private static final LocalDate DOB = LocalDate.of(1985, 3, 20);

    // Account text fixtures.
    private static final String ACTIVE_STATUS = "Y";
    private static final String ACCT_ZIP = "73301";
    private static final String GROUP_ID = "GRP01";

    // Customer text fixtures.
    private static final String FIRST_NAME = "JANE";
    private static final String MIDDLE_NAME = "Q";
    private static final String LAST_NAME = "DOE";
    private static final String ADDR_LINE_1 = "123 MAIN ST";
    private static final String ADDR_LINE_2 = "APT 4";
    private static final String ADDR_LINE_3 = "BLDG C";
    private static final String STATE_CD = "TX";
    private static final String COUNTRY_CD = "USA";
    private static final String CUST_ZIP = "73301-1234";
    private static final String PHONE_1 = "5125550100";
    private static final String PHONE_2 = "5125550101";
    private static final String GOVT_ID = "TX-DL-9988";
    private static final String EFT_ACCOUNT_ID = "EFT0001234";
    private static final String PRI_CARD_IND = "Y";
    private static final Integer FICO = 720;

    // Edited values used by the update happy-path test.
    private static final String NEW_STATUS = "N";
    private static final BigDecimal NEW_CREDIT_LIMIT = new BigDecimal("7500.00");
    private static final BigDecimal NEW_CURR_BAL = new BigDecimal("2222.22");

    /** Account persistence gateway — mocked. Supplies {@code findById} and {@code save}. */
    @Mock
    private AccountRepository accountRepository;

    /** Customer persistence gateway — mocked. Supplies {@code findById} and {@code save}. */
    @Mock
    private CustomerRepository customerRepository;

    /** Card cross-reference gateway — mocked. Supplies {@code findByXrefAcctId(Long, Pageable)}. */
    @Mock
    private CardXrefRepository cardXrefRepository;

    /**
     * Entity&harr;DTO mapper — mocked. The real service delegates the flatten/SSN-mask projection and
     * the editable-subset update to this collaborator; the mock is given faithful answers in the tests
     * that reach it (see {@link #toResponseLikeMapper(Account, Customer)} and
     * {@link #applyUpdateLikeMapper(AccountUpdateRequest, Account, Customer)}).
     */
    @Mock
    private AccountMapper accountMapper;

    /** Class under test, with all four mocks injected through its single constructor. */
    @InjectMocks
    private AccountService accountService;

    /** Fresh fixtures rebuilt before every test to guarantee isolation. */
    private Account account;
    private Customer customer;
    private CardXref xref;

    @BeforeEach
    void setUp() {
        account = newAccount(VIEW_VERSION);
        customer = newCustomer();
        xref = new CardXref(CARD_NUM, CUST_ID, ACCT_ID);
    }

    // ---------------------------------------------------------------------------------------------
    // Fixture builders
    // ---------------------------------------------------------------------------------------------

    /**
     * Builds a fully-populated {@link Account} with the supplied optimistic-lock version. All monetary
     * fields are scale-2 {@link BigDecimal}s, mirroring the COBOL {@code S9(10)V99} money columns.
     *
     * @param version the optimistic-lock token to seed (uses the public test-only {@code setVersion})
     * @return a new, fully-populated account fixture
     */
    private static Account newAccount(Long version) {
        Account a = new Account();
        a.setAcctId(ACCT_ID);
        a.setActiveStatus(ACTIVE_STATUS);
        a.setCurrBal(CURR_BAL);
        a.setCreditLimit(CREDIT_LIMIT);
        a.setCashCreditLimit(CASH_CREDIT_LIMIT);
        a.setOpenDate(OPEN_DATE);
        a.setExpirationDate(EXPIRATION_DATE);
        a.setReissueDate(REISSUE_DATE);
        a.setCurrCycCredit(CURR_CYC_CREDIT);
        a.setCurrCycDebit(CURR_CYC_DEBIT);
        a.setAddrZip(ACCT_ZIP);
        a.setGroupId(GROUP_ID);
        a.setVersion(version);
        return a;
    }

    /**
     * Builds a fully-populated {@link Customer} carrying the synthetic full SSN {@link #FULL_SSN}. The
     * service / mapper must expose only the last four digits of this value.
     *
     * @return a new, fully-populated customer fixture
     */
    private static Customer newCustomer() {
        Customer c = new Customer();
        c.setCustId(CUST_ID);
        c.setFirstName(FIRST_NAME);
        c.setMiddleName(MIDDLE_NAME);
        c.setLastName(LAST_NAME);
        c.setAddrLine1(ADDR_LINE_1);
        c.setAddrLine2(ADDR_LINE_2);
        c.setAddrLine3(ADDR_LINE_3);
        c.setAddrStateCd(STATE_CD);
        c.setAddrCountryCd(COUNTRY_CD);
        c.setAddrZip(CUST_ZIP);
        c.setPhoneNum1(PHONE_1);
        c.setPhoneNum2(PHONE_2);
        c.setSsn(FULL_SSN);
        c.setGovtIssuedId(GOVT_ID);
        c.setDob(DOB);
        c.setEftAccountId(EFT_ACCOUNT_ID);
        c.setPriCardHolderInd(PRI_CARD_IND);
        c.setFicoCreditScore(FICO);
        return c;
    }

    /** A page containing exactly the fixture cross-reference row (what {@code findByXrefAcctId} returns). */
    private Page<CardXref> xrefPage() {
        return new PageImpl<>(List.of(xref));
    }

    /** An empty cross-reference page (the "account has no cross-reference" / 404 branch). */
    private static Page<CardXref> emptyXrefPage() {
        return new PageImpl<>(List.<CardXref>of());
    }

    // ---------------------------------------------------------------------------------------------
    // Faithful mapper stand-ins — mirror the REAL AccountMapper + CustomerMapper so the mocked mapper
    // behaves like production. These are the test's "thenAnswer/doAnswer" bodies (CardServiceTest idiom).
    // ---------------------------------------------------------------------------------------------

    /**
     * Reproduces {@code CustomerMapper.maskSsnLastFour}: returns at most the last four digits of the
     * SSN (digit-only, no {@code "***-**-"} prefix), null/blank-safe. This is the single sanctioned
     * outward SSN path.
     *
     * @param ssn the raw SSN as stored on the entity; may be {@code null} or blank
     * @return at most the last four digits, or {@code null} for null/blank input
     */
    private static String maskLastFour(String ssn) {
        if (ssn == null) {
            return null;
        }
        String digits = ssn.trim();
        if (digits.isEmpty()) {
            return null;
        }
        int len = digits.length();
        return len <= 4 ? digits : digits.substring(len - 4);
    }

    /**
     * Reproduces {@code AccountMapper.toAccountResponse} exactly: flattens account + customer into the
     * 31-component {@link AccountResponse} in canonical order, masks the SSN to its last four, echoes
     * the {@code @Version} token, and is null-safe on a missing customer (returns {@code null} when the
     * account itself is {@code null}). Used as the {@code thenAnswer} body for the mocked mapper.
     *
     * @param a the account entity (when {@code null}, the method returns {@code null})
     * @param c the owning customer entity (may be {@code null}: customer-half components become null)
     * @return the flattened, SSN-suppressed response, or {@code null} when {@code a} is {@code null}
     */
    private static AccountResponse toResponseLikeMapper(Account a, Customer c) {
        if (a == null) {
            return null;
        }
        boolean hasCustomer = c != null;
        return new AccountResponse(
                a.getAcctId(),
                a.getActiveStatus(),
                a.getCurrBal(),
                a.getCreditLimit(),
                a.getCashCreditLimit(),
                a.getOpenDate(),
                a.getExpirationDate(),
                a.getReissueDate(),
                a.getCurrCycCredit(),
                a.getCurrCycDebit(),
                a.getAddrZip(),
                a.getGroupId(),
                hasCustomer ? c.getCustId() : null,
                hasCustomer ? c.getFirstName() : null,
                hasCustomer ? c.getMiddleName() : null,
                hasCustomer ? c.getLastName() : null,
                hasCustomer ? c.getAddrLine1() : null,
                hasCustomer ? c.getAddrLine2() : null,
                hasCustomer ? c.getAddrLine3() : null,
                hasCustomer ? c.getAddrStateCd() : null,
                hasCustomer ? c.getAddrCountryCd() : null,
                hasCustomer ? c.getAddrZip() : null,
                hasCustomer ? c.getPhoneNum1() : null,
                hasCustomer ? c.getPhoneNum2() : null,
                hasCustomer ? maskLastFour(c.getSsn()) : null,
                hasCustomer ? c.getGovtIssuedId() : null,
                hasCustomer ? c.getDob() : null,
                hasCustomer ? c.getEftAccountId() : null,
                hasCustomer ? c.getPriCardHolderInd() : null,
                hasCustomer ? c.getFicoCreditScore() : null,
                a.getVersion());
    }

    /**
     * Reproduces {@code AccountMapper.applyUpdate} + {@code CustomerMapper.applyCustomerUpdate}: applies
     * each editable field only when the request carries a non-{@code null} value, and never touches the
     * immutable {@code acctId}/{@code custId} keys nor the {@code @Version} token. Used as the
     * {@code doAnswer} body for the mocked mapper on the update path.
     *
     * @param req the inbound update request (a {@code null} request applies nothing)
     * @param a   the managed account to mutate in place (a {@code null} account skips the account half)
     * @param c   the managed customer to mutate in place (a {@code null} customer is a safe no-op)
     */
    private static void applyUpdateLikeMapper(AccountUpdateRequest req, Account a, Customer c) {
        if (req != null && a != null) {
            if (req.activeStatus() != null) {
                a.setActiveStatus(req.activeStatus());
            }
            if (req.currentBalance() != null) {
                a.setCurrBal(req.currentBalance());
            }
            if (req.creditLimit() != null) {
                a.setCreditLimit(req.creditLimit());
            }
            if (req.cashCreditLimit() != null) {
                a.setCashCreditLimit(req.cashCreditLimit());
            }
            if (req.currentCycleCredit() != null) {
                a.setCurrCycCredit(req.currentCycleCredit());
            }
            if (req.currentCycleDebit() != null) {
                a.setCurrCycDebit(req.currentCycleDebit());
            }
            if (req.openDate() != null) {
                a.setOpenDate(req.openDate());
            }
            if (req.expirationDate() != null) {
                a.setExpirationDate(req.expirationDate());
            }
            if (req.reissueDate() != null) {
                a.setReissueDate(req.reissueDate());
            }
            if (req.accountAddressZip() != null) {
                a.setAddrZip(req.accountAddressZip());
            }
            if (req.accountGroupId() != null) {
                a.setGroupId(req.accountGroupId());
            }
            // acctId and version intentionally NOT written (AAP §0.6.6 / immutable key).
        }
        if (req != null && c != null) {
            if (req.firstName() != null) {
                c.setFirstName(req.firstName());
            }
            if (req.lastName() != null) {
                c.setLastName(req.lastName());
            }
            if (req.ssn() != null) {
                c.setSsn(req.ssn());
            }
            // (Remaining customer fields follow the same non-null-apply rule in production.)
        }
    }

    /**
     * Builds an {@link AccountUpdateRequest} carrying the supplied version plus a small set of edited
     * account fields (active status, credit limit, current balance). All other components are
     * {@code null}, exercising the partial-update ({@code PUT}) semantics.
     *
     * @param version the optimistic-lock token the client echoes back
     * @return a populated update request
     */
    private static AccountUpdateRequest editRequest(Long version) {
        return new AccountUpdateRequest(
                NEW_STATUS,        // activeStatus
                NEW_CURR_BAL,      // currentBalance
                NEW_CREDIT_LIMIT,  // creditLimit
                null,              // cashCreditLimit
                null,              // currentCycleCredit
                null,              // currentCycleDebit
                null,              // openDate
                null,              // expirationDate
                null,              // reissueDate
                null,              // accountAddressZip
                null,              // accountGroupId
                null,              // firstName
                null,              // middleName
                null,              // lastName
                null,              // addressLine1
                null,              // addressLine2
                null,              // addressLine3
                null,              // stateCode
                null,              // countryCode
                null,              // customerZip
                null,              // phoneNumber1
                null,              // phoneNumber2
                null,              // ssn
                null,              // governmentIssuedId
                null,              // dateOfBirth
                null,              // eftAccountId
                null,              // primaryCardHolderIndicator
                null,              // ficoScore
                version);          // version (optimistic-lock token)
    }

    // =============================================================================================
    // Phase 2 — getAccount: flatten + ssnLastFour + version round-trip
    // =============================================================================================

    @Test
    @DisplayName("getAccount flattens account+customer, masks SSN to last four, and echoes the @Version")
    void getAccount_flattensView_masksSsn_echoesVersion() {
        when(accountRepository.findById(ACCT_ID)).thenReturn(Optional.of(account));
        when(cardXrefRepository.findByXrefAcctId(eq(ACCT_ID), any(Pageable.class))).thenReturn(xrefPage());
        when(customerRepository.findById(CUST_ID)).thenReturn(Optional.of(customer));
        when(accountMapper.toAccountResponse(account, customer))
                .thenAnswer(inv -> toResponseLikeMapper(inv.getArgument(0), inv.getArgument(1)));

        AccountResponse resp = accountService.getAccount(ACCT_ID);

        // --- Account half ---
        assertThat(resp.accountId()).isEqualTo(ACCT_ID);
        assertThat(resp.activeStatus()).isEqualTo(ACTIVE_STATUS);
        assertThat(resp.accountAddressZip()).isEqualTo(ACCT_ZIP);
        assertThat(resp.accountGroupId()).isEqualTo(GROUP_ID);
        assertThat(resp.openDate()).isEqualTo(OPEN_DATE);
        assertThat(resp.expirationDate()).isEqualTo(EXPIRATION_DATE);
        assertThat(resp.reissueDate()).isEqualTo(REISSUE_DATE);

        // --- Money: BigDecimal, scale 2, numerically equal (COBOL S9(10)V99 fixed-point parity) ---
        assertThat(resp.currentBalance()).isEqualByComparingTo(CURR_BAL);
        assertThat(resp.currentBalance().scale()).isEqualTo(2);
        assertThat(resp.creditLimit()).isEqualByComparingTo(CREDIT_LIMIT);
        assertThat(resp.creditLimit().scale()).isEqualTo(2);
        assertThat(resp.cashCreditLimit()).isEqualByComparingTo(CASH_CREDIT_LIMIT);
        assertThat(resp.cashCreditLimit().scale()).isEqualTo(2);
        assertThat(resp.currentCycleCredit()).isEqualByComparingTo(CURR_CYC_CREDIT);
        assertThat(resp.currentCycleCredit().scale()).isEqualTo(2);
        assertThat(resp.currentCycleDebit()).isEqualByComparingTo(CURR_CYC_DEBIT);
        assertThat(resp.currentCycleDebit().scale()).isEqualTo(2);

        // --- Customer half ---
        assertThat(resp.customerId()).isEqualTo(CUST_ID);
        assertThat(resp.firstName()).isEqualTo(FIRST_NAME);
        assertThat(resp.lastName()).isEqualTo(LAST_NAME);

        // --- PII (headline rule): SSN suppressed to last four ONLY ---
        assertThat(resp.ssnLastFour()).isEqualTo(SSN_LAST_FOUR);
        assertThat(resp.ssnLastFour()).hasSize(4);

        // --- Optimistic-lock token round-trip (echoed for a later PUT) ---
        assertThat(resp.version()).isEqualTo(VIEW_VERSION);
    }

    @Test
    @DisplayName("AccountResponse exposes only ssnLastFour — no full-SSN component exists (structural)")
    void accountResponse_hasNoFullSsnComponent() {
        List<String> componentNames = Arrays.stream(AccountResponse.class.getRecordComponents())
                .map(RecordComponent::getName)
                .toList();

        // The sanctioned last-four component is present...
        assertThat(componentNames).contains("ssnLastFour");

        // ...and NO component could carry a full SSN: every SSN-related name is exactly "ssnLastFour".
        assertThat(componentNames)
                .filteredOn(n -> n.toLowerCase().contains("ssn"))
                .containsExactly("ssnLastFour");
        assertThat(componentNames)
                .noneMatch(n -> n.equalsIgnoreCase("ssn")
                        || n.toLowerCase().contains("socialsecurity")
                        || n.toLowerCase().contains("fullssn"));
    }

    @Test
    @DisplayName("Serialized AccountResponse never leaks the full SSN; only the last four appear")
    void getAccount_serializedJson_doesNotLeakFullSsn() throws Exception {
        when(accountRepository.findById(ACCT_ID)).thenReturn(Optional.of(account));
        when(cardXrefRepository.findByXrefAcctId(eq(ACCT_ID), any(Pageable.class))).thenReturn(xrefPage());
        when(customerRepository.findById(CUST_ID)).thenReturn(Optional.of(customer));
        when(accountMapper.toAccountResponse(account, customer))
                .thenAnswer(inv -> toResponseLikeMapper(inv.getArgument(0), inv.getArgument(1)));

        AccountResponse resp = accountService.getAccount(ACCT_ID);

        ObjectMapper json = new ObjectMapper().registerModule(new JavaTimeModule());
        String body = json.writeValueAsString(resp);

        assertThat(body).doesNotContain(FULL_SSN);   // "123456789" must never be serialized
        assertThat(body).contains(SSN_LAST_FOUR);     // but the last four ("6789") are present
    }

    // =============================================================================================
    // Phase 3 — getAccount not-found paths -> HTTP 404
    // =============================================================================================

    @Test
    @DisplayName("getAccount throws ResourceNotFoundException (404) when the account does not exist")
    void getAccount_accountMissing_throws404() {
        when(accountRepository.findById(404L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> accountService.getAccount(404L))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    @DisplayName("getAccount throws ResourceNotFoundException (404) when no cross-reference resolves the owner")
    void getAccount_xrefMissing_throws404() {
        when(accountRepository.findById(ACCT_ID)).thenReturn(Optional.of(account));
        when(cardXrefRepository.findByXrefAcctId(eq(ACCT_ID), any(Pageable.class))).thenReturn(emptyXrefPage());

        assertThatThrownBy(() -> accountService.getAccount(ACCT_ID))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    @DisplayName("getAccount throws ResourceNotFoundException (404) when the cross-referenced customer is absent")
    void getAccount_customerMissing_throws404() {
        when(accountRepository.findById(ACCT_ID)).thenReturn(Optional.of(account));
        when(cardXrefRepository.findByXrefAcctId(eq(ACCT_ID), any(Pageable.class))).thenReturn(xrefPage());
        when(customerRepository.findById(CUST_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> accountService.getAccount(ACCT_ID))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    // =============================================================================================
    // Phase 4 — updateAccount happy path
    // =============================================================================================

    @Test
    @DisplayName("updateAccount applies editable fields, saves once, keeps money at scale 2, and never mutates keys/version")
    void updateAccount_appliesEditableFields_savesOnce() {
        account.setVersion(BASE_VERSION);
        AccountUpdateRequest request = editRequest(BASE_VERSION); // version matches -> explicit guard passes

        when(accountRepository.findById(ACCT_ID)).thenReturn(Optional.of(account));
        when(cardXrefRepository.findByXrefAcctId(eq(ACCT_ID), any(Pageable.class))).thenReturn(xrefPage());
        when(customerRepository.findById(CUST_ID)).thenReturn(Optional.of(customer));
        doAnswer(inv -> {
            applyUpdateLikeMapper(inv.getArgument(0), inv.getArgument(1), inv.getArgument(2));
            return null;
        }).when(accountMapper).applyUpdate(any(AccountUpdateRequest.class), any(Account.class), any(Customer.class));
        when(accountMapper.toAccountResponse(same(account), same(customer)))
                .thenAnswer(inv -> toResponseLikeMapper(inv.getArgument(0), inv.getArgument(1)));

        AccountResponse resp = accountService.updateAccount(ACCT_ID, request);

        // The service delegates the editable-field apply to the mapper with the loaded entities.
        verify(accountMapper).applyUpdate(eq(request), same(account), same(customer));

        // The account is persisted exactly once; capture it and assert the edits landed.
        ArgumentCaptor<Account> savedAccount = ArgumentCaptor.forClass(Account.class);
        verify(accountRepository).save(savedAccount.capture());
        verify(customerRepository).save(same(customer));

        Account persisted = savedAccount.getValue();
        assertThat(persisted.getActiveStatus()).isEqualTo(NEW_STATUS);
        assertThat(persisted.getCreditLimit()).isEqualByComparingTo(NEW_CREDIT_LIMIT);
        assertThat(persisted.getCreditLimit().scale()).isEqualTo(2);
        assertThat(persisted.getCurrBal()).isEqualByComparingTo(NEW_CURR_BAL);
        assertThat(persisted.getCurrBal().scale()).isEqualTo(2);
        // Untouched account field stays as seeded.
        assertThat(persisted.getCashCreditLimit()).isEqualByComparingTo(CASH_CREDIT_LIMIT);
        // Immutable key and the Hibernate-owned @Version are NOT rewritten by the mapper (AAP §0.6.6).
        assertThat(persisted.getAcctId()).isEqualTo(ACCT_ID);
        assertThat(persisted.getVersion()).isEqualTo(BASE_VERSION);

        // The returned view reflects the new state, echoes the version, and still masks the SSN.
        assertThat(resp.activeStatus()).isEqualTo(NEW_STATUS);
        assertThat(resp.creditLimit()).isEqualByComparingTo(NEW_CREDIT_LIMIT);
        assertThat(resp.version()).isEqualTo(BASE_VERSION);
        assertThat(resp.ssnLastFour()).isEqualTo(SSN_LAST_FOUR);
    }

    // =============================================================================================
    // Phase 5 — updateAccount conflict -> HTTP 409 (the core parity point)
    // =============================================================================================

    @Test
    @DisplayName("updateAccount rejects a stale version with the CardDemo ConcurrentModificationException and never saves")
    void updateAccount_staleVersion_throwsConflict_andDoesNotSave() {
        account.setVersion(BASE_VERSION);                          // persisted row is at version 5
        AccountUpdateRequest staleReq = editRequest(STALE_VERSION); // client echoes stale version 3

        when(accountRepository.findById(ACCT_ID)).thenReturn(Optional.of(account));
        when(cardXrefRepository.findByXrefAcctId(eq(ACCT_ID), any(Pageable.class))).thenReturn(xrefPage());
        when(customerRepository.findById(CUST_ID)).thenReturn(Optional.of(customer));

        // The domain exception MUST be com.carddemo.exception.ConcurrentModificationException
        // (NOT java.util) — GlobalExceptionHandler maps it to HTTP 409.
        assertThatThrownBy(() -> accountService.updateAccount(ACCT_ID, staleReq))
                .isInstanceOf(ConcurrentModificationException.class);

        verify(accountMapper, never()).applyUpdate(any(), any(), any());
        verify(accountRepository, never()).save(any());
        verify(customerRepository, never()).save(any());
    }

    @Test
    @DisplayName("updateAccount surfaces a JPA optimistic-lock flush failure as a 409-mapped exception")
    void updateAccount_jpaOptimisticLockFailure_propagatesAs409() {
        account.setVersion(BASE_VERSION);
        AccountUpdateRequest request = editRequest(BASE_VERSION); // version matches: explicit guard passes

        when(accountRepository.findById(ACCT_ID)).thenReturn(Optional.of(account));
        when(cardXrefRepository.findByXrefAcctId(eq(ACCT_ID), any(Pageable.class))).thenReturn(xrefPage());
        when(customerRepository.findById(CUST_ID)).thenReturn(Optional.of(customer));
        when(accountRepository.save(any(Account.class)))
                .thenThrow(new ObjectOptimisticLockingFailureException(Account.class, ACCT_ID));

        // The service does not catch the flush-race failure; both the domain conflict exception and
        // the raw JPA optimistic-lock failure map to HTTP 409 in GlobalExceptionHandler.
        assertThatThrownBy(() -> accountService.updateAccount(ACCT_ID, request))
                .isInstanceOfAny(ConcurrentModificationException.class,
                        ObjectOptimisticLockingFailureException.class);

        // The flush race aborts the unit of work before the customer half is written.
        verify(customerRepository, never()).save(any());
    }

    // =============================================================================================
    // Phase 6 — updateAccount not-found -> HTTP 404
    // =============================================================================================

    @Test
    @DisplayName("updateAccount throws ResourceNotFoundException (404) when the account does not exist, and never saves")
    void updateAccount_accountMissing_throws404_noSave() {
        AccountUpdateRequest request = editRequest(BASE_VERSION);
        when(accountRepository.findById(404L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> accountService.updateAccount(404L, request))
                .isInstanceOf(ResourceNotFoundException.class);

        verify(accountRepository, never()).save(any());
        verify(customerRepository, never()).save(any());
    }
}
