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
package com.carddemo.account.integration;

import com.carddemo.common.config.CorrelationIdFilter;
import com.carddemo.account.repository.AccountRepository;
import com.carddemo.account.repository.CardXrefRepository;
import com.carddemo.account.repository.CustomerRepository;
import com.carddemo.common.domain.Account;
import com.carddemo.common.domain.CardXref;
import com.carddemo.common.domain.Customer;
import com.carddemo.common.dto.AccountUpdateRequestDto;
import com.carddemo.common.dto.AccountUpdateResponseDto;
import com.carddemo.common.dto.AccountViewResponseDto;
import com.carddemo.common.dto.ErrorResponse;
import com.carddemo.common.testsupport.MigratedSchemaContainer;
import com.carddemo.common.crypto.PiiMasker;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * :purpose: Full-context ``@SpringBootTest`` + Testcontainers PostgreSQL integration test for
 *  the migrated CardDemo account view (``GET /accounts/{id}``, CICS ``CAVW``, program
 *  ``COACTVWC``) and account update (``PUT /accounts/{id}``, CICS ``CAUP``, program
 *  ``COACTUPC``) flows. It exercises the real controller -> service -> repositories -> mapper
 *  chain against a seeded ``postgres:18`` container, proving functional equivalence with the
 *  two legacy CICS programs.
 * :output: Verifies the view and update happy paths (byte-identical scale-2 money and the
 *  preserved ``ACCT-EXPIRAION-DATE`` misspelling), the three verbatim not-found (HTTP 404)
 *  outcomes, the invalid-account-id edit (HTTP 400), and a lightweight correlation-id
 *  observability smoke check. Regulated PII fields (SSN, government id, card number) are
 *  never asserted.
 */
@SpringBootTest
@ActiveProfiles("test")
class AccountViewUpdateIT {

    /** :purpose: Flyway-seeded account/customer pair used by the happy paths (acct 1 <-> cust 1). */
    private static final long HAPPY_ACCT_ID = 1L;
    private static final long HAPPY_CUST_ID = 1L;

    /** :purpose: Seeded account whose customer row keeps the committed seed identifiers untouched. */
    private static final long SEEDED_ACCT_WITH_PII = 2L;

    /**
     * :purpose: Account id absent from both the accounts master and the card cross-reference
     *  (``card_xref`` is seeded for accounts 1..50), so the first ordered lookup misses.
     */
    private static final long UNSEEDED_ACCT_ID = 60L;

    /** :purpose: Customer id absent from the customers master, used by the referential-integrity cases. */
    private static final long UNSEEDED_CUST_ID = 9999L;

    /** :purpose: Distinct unseeded 16-digit card numbers for the referential-integrity cases (``xref_card_num`` is the PK). */
    private static final String ORPHAN_CARD_MISSING_ACCT = "4111111111111160";
    private static final String ORPHAN_CARD_MISSING_CUST = "4111111111111102";

    /**
     * :purpose: Verbatim COACTVWC not-found message for the first ordered lookup
     *  (WORKING-STORAGE L130). The L132/L134 messages cover orphan states the migrated
     *  schema's foreign keys make unreachable, so they are asserted against the service in
     *  ``AccountServiceTest`` rather than through HTTP here.
     */
    private static final String MSG_ACCT_NOT_IN_XREF = "Did not find this account in account card xref file";

    /** :purpose: Verbatim COACTVWC/COACTUPC account-id edit message (``2210-EDIT-ACCOUNT``). */
    private static final String MSG_INVALID_ACCT_ID = "Account number must be a non zero 11 digit number";

    /**
     * Points the datasource at the shared, already-migrated ``postgres:18`` container from
     * :java:class:`com.carddemo.common.testsupport.MigratedSchemaContainer`, superseding the
     * ``jdbc:tc`` URL declared in ``application-test.yml``. That container's schema and seed
     * data come exclusively from the committed Flyway migrations of every owning module, so
     * this class runs with the ``test`` profile's ``ddl-auto: validate`` against the schema a
     * deployment actually gets - no DDL and no seed row is fabricated here.
     *
     * :param registry: the Spring dynamic property registry.
     */
    @DynamicPropertySource
    static void datasourceProperties(DynamicPropertyRegistry registry) {
        MigratedSchemaContainer.registerDataSource(registry);
    }

    @Autowired
    private WebApplicationContext webApplicationContext;

    // The correlation filter is a shared carddemo-common component registered
    // through a FilterRegistrationBean, so the standalone MockMvc setup below
    // instantiates it directly rather than injecting it.
    private final CorrelationIdFilter correlationIdFilter = new CorrelationIdFilter();

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private AccountRepository accountRepository;

    @Autowired
    private CustomerRepository customerRepository;

    @Autowired
    private CardXrefRepository cardXrefRepository;

    private MockMvc mockMvc;

    /**
     * Prepares a clean, deterministic fixture before each test: restores the mutable seed
     * rows and assembles a ``MockMvc`` wired with the correlation-id filter. The seeded
     * customer PII columns are left EXACTLY as the migration wrote them - the read path
     * must cope with the committed seed as deployed - and the ``card_xref`` rows come from
     * the card-service migration seed (accounts 1..50).
     *
     * :output: an isolated per-test starting state and an initialized ``mockMvc``.
     */
    @BeforeEach
    void setUp() {
        // Restore the seed rows mutated by the update happy path and reset optimistic-lock versions.
        jdbcTemplate.update("UPDATE accounts SET acct_curr_bal = 194.00, version = 0 WHERE acct_id = ?",
                HAPPY_ACCT_ID);
        jdbcTemplate.update("UPDATE customers SET cust_last_name = 'Kessler', version = 0 WHERE cust_id = ?",
                HAPPY_CUST_ID);

        mockMvc = MockMvcBuilders.webAppContextSetup(webApplicationContext)
                .addFilters(correlationIdFilter)
                .build();
    }

    /**
     * Removes the unseeded cross-reference rows a referential-integrity case may have
     * attempted, so cases remain order-independent and the migration seed stays intact.
     */
    @AfterEach
    void tearDown() {
        jdbcTemplate.update("DELETE FROM card_xref WHERE xref_card_num IN (?, ?)",
                ORPHAN_CARD_MISSING_ACCT, ORPHAN_CARD_MISSING_CUST);
    }

    /**
     * Assembles a fully-populated update request from a view response; the mapper copies every
     * editable field unconditionally, so all NOT NULL columns must be supplied.
     *
     * :param view: the current account/customer view whose editable fields seed the request.
     * :returns: an ``AccountUpdateRequestDto`` mirroring the supplied view.
     */
    /** :purpose: State code whose ZIP prefix pairing is in the ``CSLKPCDY`` state-ZIP table. */
    private static final String VALID_STATE_CD = "MI";

    /** :purpose: ZIP whose first two digits pair with {@link #VALID_STATE_CD}. */
    private static final String VALID_ZIP = "48226";

    /** :purpose: Screen-valid phone number 1 whose area code is in ``CSLKPCDY``. */
    private static final String VALID_PHONE_NUM_1 = "(212)555-0101";

    /** :purpose: Screen-valid phone number 2 whose area code is in ``CSLKPCDY``. */
    private static final String VALID_PHONE_NUM_2 = "(801)555-0102";

    /** :purpose: FICO score inside the ``1275-EDIT-US-FICO-SCORE`` 300..850 range. */
    private static final Integer VALID_FICO_SCORE = 700;

    private AccountUpdateRequestDto toUpdateRequest(AccountViewResponseDto view) {
        AccountUpdateRequestDto request = new AccountUpdateRequestDto();
        request.setAcctActiveStatus(view.getAcctActiveStatus());
        request.setAcctCurrBal(view.getAcctCurrBal());
        request.setAcctCreditLimit(view.getAcctCreditLimit());
        request.setAcctCashCreditLimit(view.getAcctCashCreditLimit());
        request.setAcctCurrCycCredit(view.getAcctCurrCycCredit());
        request.setAcctCurrCycDebit(view.getAcctCurrCycDebit());
        request.setAcctOpenDate(view.getAcctOpenDate());
        request.setAcctExpiraionDate(view.getAcctExpiraionDate());
        request.setAcctReissueDate(view.getAcctReissueDate());
        request.setAcctGroupId(view.getAcctGroupId());
        request.setCustFirstName(view.getCustFirstName());
        request.setCustMiddleName(view.getCustMiddleName());
        request.setCustLastName(view.getCustLastName());
        request.setCustAddrLine1(view.getCustAddrLine1());
        request.setCustAddrLine2(view.getCustAddrLine2());
        request.setCustAddrLine3(view.getCustAddrLine3());
        // The randomly generated fixture in app/data/ASCII/custdata.txt does not satisfy the
        // application's own screen edits: its area codes are absent from the CSLKPCDY
        // general-purpose table, its state/ZIP pairs are not in the state-ZIP table, and some
        // FICO scores fall below the 300 floor (customer 2 stores 268), so COACTUPC refuses to
        // rewrite such a row until the operator corrects those fields. Screen-valid values are
        // therefore submitted here; the edits themselves are covered by the validator's own
        // tests, while this class asserts the update round trip.
        request.setCustAddrStateCd(VALID_STATE_CD);
        request.setCustAddrCountryCd(view.getCustAddrCountryCd());
        request.setCustAddrZip(VALID_ZIP);
        request.setCustPhoneNum1(VALID_PHONE_NUM_1);
        request.setCustPhoneNum2(VALID_PHONE_NUM_2);
        request.setCustSsn(view.getCustSsn());
        request.setCustGovtIssuedId(view.getCustGovtIssuedId());
        request.setCustDobYyyyMmDd(view.getCustDobYyyyMmDd());
        request.setCustEftAccountId(view.getCustEftAccountId());
        request.setCustPriCardHolderInd(view.getCustPriCardHolderInd());
        request.setCustFicoCreditScore(VALID_FICO_SCORE);
        return request;
    }

    /**
     * Asserts a money value both by numeric equality and by exact two-place scale.
     *
     * :param actual: the ``BigDecimal`` money value under test.
     * :param expected: the expected value as a plain decimal string (for example ``"194.00"``).
     */
    private void assertMoney(BigDecimal actual, String expected) {
        assertThat(actual).isNotNull();
        assertThat(actual).isEqualByComparingTo(new BigDecimal(expected));
        assertThat(actual.scale()).isEqualTo(2);
    }

    /**
     * Deserializes a captured HTTP response body into the requested type using the application's
     * configured ``ObjectMapper``.
     *
     * :param result: the completed ``MvcResult`` whose body is parsed.
     * :param type: the target type.
     * :returns: the deserialized instance.
     */
    private <T> T parse(MvcResult result, Class<T> type) throws Exception {
        return objectMapper.readValue(result.getResponse().getContentAsString(), type);
    }

    /**
     * :purpose: The view path must work against the committed seed data exactly as deployed:
     *  ``V3__seed_customers.sql`` writes the customer identifier columns as the legacy
     *  fixed-width loads did, and the read path has to hydrate them. No column is edited by
     *  this test.
     * :output: HTTP 200 for a seeded account whose customer row is untouched (the previously
     *  observed outcome was HTTP 500 from the at-rest converter).
     */
    @Test
    void getAccount_withUnmodifiedSeededCustomer_returns200() throws Exception {
        Integer untouchedCustomers = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM customers WHERE cust_ssn <> ''", Integer.class);
        assertThat(untouchedCustomers).isPositive();

        MvcResult result = mockMvc.perform(get("/accounts/{id}", SEEDED_ACCT_WITH_PII))
                .andExpect(status().isOk())
                .andReturn();

        AccountViewResponseDto view = parse(result, AccountViewResponseDto.class);
        assertThat(view.getAcctId()).isEqualTo(SEEDED_ACCT_WITH_PII);
        assertThat(view.getCustId()).isEqualTo(SEEDED_ACCT_WITH_PII);
    }

    /**
     * :purpose: AAP 0.6.7 requires the regulated identifiers to be masked as well as
     *  encrypted: ``GET /accounts/{id}`` must never disclose the stored SSN or
     *  government-issued id, and the mask must preserve the fixed-width field length with
     *  only the trailing four characters visible.
     * :output: HTTP 200 whose body carries the masked identifiers and no plaintext.
     */
    @Test
    void getAccount_masksRegulatedCustomerIdentifiers() throws Exception {
        // The identifiers are read through JPA, so these are the clear values the mask is
        // built from; the column itself holds ciphertext once the migration set has run.
        Customer stored = customerRepository.findById(SEEDED_ACCT_WITH_PII).orElseThrow();
        String storedSsn = stored.getCustSsn();
        String storedGovtId = stored.getCustGovtIssuedId();
        assertThat(storedSsn).isNotBlank();
        assertThat(storedGovtId).isNotBlank();

        MvcResult result = mockMvc.perform(get("/accounts/{id}", SEEDED_ACCT_WITH_PII))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.custSsn").value(PiiMasker.maskSsn(storedSsn)))
                .andExpect(jsonPath("$.custGovtIssuedId").value(PiiMasker.maskIdentifier(storedGovtId)))
                .andReturn();

        String body = result.getResponse().getContentAsString();
        assertThat(body).doesNotContain(storedSsn);
        assertThat(body).doesNotContain(storedGovtId);
        AccountViewResponseDto view = parse(result, AccountViewResponseDto.class);
        // The 3270 mask keeps the group separators (``***-**-nnnn``), so it is deliberately
        // wider than the nine stored digits; what must hold is that only the last four
        // digits survive and nothing else is disclosed.
        assertThat(view.getCustSsn()).endsWith(storedSsn.substring(storedSsn.length() - 4));
        assertThat(view.getCustSsn()).startsWith("*");
        assertThat(view.getCustSsn()).isEqualTo(PiiMasker.maskSsn(storedSsn));
    }

    /**
     * :purpose: The masked identifier a client receives must not corrupt the stored value
     *  when the record is submitted back unchanged: the update echoes every field, so the
     *  mapper has to recognise an echoed mask as "unchanged" (AAP 0.6.7 masking must not
     *  weaken the COACTUPC update contract).
     * :output: HTTP 200 and the originally stored identifiers still present in the database.
     */
    @Test
    void putAccount_withEchoedMaskedIdentifiers_preservesStoredValues() throws Exception {
        Customer before = customerRepository.findById(HAPPY_CUST_ID).orElseThrow();
        String identifierBefore = before.getCustSsn();
        String govtIdBefore = before.getCustGovtIssuedId();
        assertThat(identifierBefore).isNotBlank();
        assertThat(govtIdBefore).isNotBlank();

        AccountViewResponseDto current = parse(
                mockMvc.perform(get("/accounts/{id}", HAPPY_ACCT_ID))
                        .andExpect(status().isOk())
                        .andReturn(),
                AccountViewResponseDto.class);
        assertThat(current.getCustSsn()).isEqualTo(PiiMasker.maskSsn(identifierBefore));

        AccountUpdateRequestDto request = toUpdateRequest(current);
        request.setCustLastName("Kesslerechoed");

        mockMvc.perform(put("/accounts/{id}", HAPPY_ACCT_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk());

        // The logical identifiers survive the round trip unchanged - the echoed mask never
        // becomes the stored value - and the column is now an at-rest ciphertext token
        // because the write path always encrypts (AAP 0.6.7).
        Customer after = customerRepository.findById(HAPPY_CUST_ID).orElseThrow();
        assertThat(after.getCustSsn()).isEqualTo(identifierBefore);
        assertThat(after.getCustGovtIssuedId()).isEqualTo(govtIdBefore);
        assertThat(after.getCustLastName()).isEqualTo("Kesslerechoed");

        Map<String, Object> columns = jdbcTemplate.queryForMap(
                "SELECT cust_ssn, cust_govt_issued_id FROM customers WHERE cust_id = ?",
                HAPPY_CUST_ID);
        assertThat((String) columns.get("cust_ssn")).isNotEqualTo(identifierBefore);
        assertThat(((String) columns.get("cust_ssn")).length())
                .isGreaterThan(identifierBefore.length());
        assertThat((String) columns.get("cust_govt_issued_id")).isNotEqualTo(govtIdBefore);
    }

    /**
     * :purpose: Account view happy path (CICS ``CAVW`` / ``COACTVWC``): after a cross-reference
     *  links the account to its customer, ``GET /accounts/{id}`` returns the combined
     *  account-and-customer view.
     * :output: HTTP 200 with scale-2 money, the preserved ``acctExpiraionDate`` misspelling,
     *  and the seeded non-PII customer fields; regulated PII is not asserted.
     */
    @Test
    void getAccount_returns200WithAccountAndCustomerFields() throws Exception {
        MvcResult result = mockMvc.perform(get("/accounts/{id}", HAPPY_ACCT_ID))
                .andExpect(status().isOk())
                .andReturn();

        AccountViewResponseDto view = parse(result, AccountViewResponseDto.class);
        assertThat(view.getAcctId()).isEqualTo(HAPPY_ACCT_ID);
        assertThat(view.getAcctActiveStatus()).isEqualTo("Y");

        assertMoney(view.getAcctCurrBal(), "194.00");
        assertMoney(view.getAcctCreditLimit(), "2020.00");
        assertMoney(view.getAcctCashCreditLimit(), "1020.00");
        assertMoney(view.getAcctCurrCycCredit(), "0.00");
        assertMoney(view.getAcctCurrCycDebit(), "0.00");

        assertThat(view.getAcctOpenDate()).isEqualTo("2014-11-20");
        assertThat(view.getAcctExpiraionDate()).isEqualTo("2025-05-20");
        assertThat(view.getAcctReissueDate()).isEqualTo("2025-05-20");

        assertThat(view.getCustFirstName()).isEqualTo("Immanuel");
        assertThat(view.getCustMiddleName()).isEqualTo("Madeline");
        assertThat(view.getCustLastName()).isEqualTo("Kessler");
        assertThat(view.getCustAddrLine3()).isEqualTo("Altenwerthshire");
        assertThat(view.getCustAddrStateCd()).isEqualTo("NC");
        assertThat(view.getCustAddrCountryCd()).isEqualTo("USA");
        assertThat(view.getCustFicoCreditScore()).isEqualTo(274);

        // Wire-level confirmation that money renders as a scale-2 string and a PIC 9(n)
        // identifier keeps its full width (COBOL COMP-3 / zero-padded display fidelity).
        String body = result.getResponse().getContentAsString();
        assertThat(body).contains("\"acctCurrBal\":\"194.00\"");
        assertThat(body).contains("\"acctCurrCycCredit\":\"0.00\"");
        assertThat(body).contains("\"acctCurrCycDebit\":\"0.00\"");
        assertThat(body).contains("\"acctId\":\"00000000001\"");
        assertThat(body).contains("\"custFicoCreditScore\":\"274\"");
    }

    /**
     * :purpose: Account update happy path (CICS ``CAUP`` / ``COACTUPC``): ``PUT /accounts/{id}``
     *  applies edited account and customer fields in a single transaction and returns the
     *  post-update view.
     * :output: HTTP 200 echoing the updated values, and a database reload confirming the
     *  changes persisted with scale-2 money.
     */
    @Test
    void putAccount_updatesAndPersistsWithScale2Precision() throws Exception {
        AccountViewResponseDto current = parse(
                mockMvc.perform(get("/accounts/{id}", HAPPY_ACCT_ID))
                        .andExpect(status().isOk())
                        .andReturn(),
                AccountViewResponseDto.class);

        AccountUpdateRequestDto request = toUpdateRequest(current);
        request.setAcctCurrBal(new BigDecimal("250.00"));
        request.setCustLastName("Kesslerupd");

        MvcResult result = mockMvc.perform(put("/accounts/{id}", HAPPY_ACCT_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andReturn();

        AccountUpdateResponseDto response = parse(result, AccountUpdateResponseDto.class);
        assertThat(response.getAcctId()).isEqualTo(HAPPY_ACCT_ID);
        assertMoney(response.getAcctCurrBal(), "250.00");
        assertThat(response.getCustLastName()).isEqualTo("Kesslerupd");

        Account persistedAccount = accountRepository.findById(HAPPY_ACCT_ID).orElseThrow();
        assertMoney(persistedAccount.getAcctCurrBal(), "250.00");

        Customer persistedCustomer = customerRepository.findById(HAPPY_CUST_ID).orElseThrow();
        assertThat(persistedCustomer.getCustLastName()).isEqualTo("Kesslerupd");
    }

    /**
     * :purpose: First ordered lookup miss (``COACTVWC`` L130): the account has no cross-reference
     *  row, so the view short-circuits before the account read. The migration seeds
     *  cross-references for accounts 1..50, so an account id beyond the seed exercises this path.
     * :output: HTTP 404 whose message is the verbatim ``DID-NOT-FIND-ACCT-IN-CARDXREF`` text.
     */
    @Test
    void getAccount_whenCardXrefMissing_returns404() throws Exception {
        MvcResult result = mockMvc.perform(get("/accounts/{id}", UNSEEDED_ACCT_ID))
                .andExpect(status().isNotFound())
                .andReturn();

        ErrorResponse error = parse(result, ErrorResponse.class);
        assertThat(error.getStatus()).isEqualTo(404);
        assertThat(error.getMessage()).isEqualTo(MSG_ACCT_NOT_IN_XREF);
    }

    /**
     * :purpose: Referential integrity at the database level (AAP 0.1.1): the cross-reference
     *  cannot point at an account that is absent from the accounts master, because
     *  ``card_xref.xref_acct_id`` carries a foreign key to ``accounts``. The orphan state the
     *  legacy ``COACTVWC`` L132 path defends against is therefore unreachable in the migrated
     *  schema; that verbatim ``DID-NOT-FIND-ACCT-IN-ACCTDAT`` message remains asserted against
     *  the service in ``AccountServiceTest#viewAccount_accountMiss_throwsRecordNotFound_andSkipsCustomer``.
     * :output: The write is rejected by the database with a data-integrity violation and no
     *  orphan row is persisted.
     */
    @Test
    void cardXref_withUnknownAccount_isRejectedByForeignKey() {
        CardXref orphan = new CardXref(ORPHAN_CARD_MISSING_ACCT, HAPPY_CUST_ID, UNSEEDED_ACCT_ID);

        assertThatThrownBy(() -> cardXrefRepository.saveAndFlush(orphan))
                .isInstanceOf(DataIntegrityViolationException.class);

        assertThat(cardXrefRepository.findFirstByXrefAcctIdOrderByXrefCardNumAsc(UNSEEDED_ACCT_ID)).isEmpty();
    }

    /**
     * :purpose: Referential integrity at the database level (AAP 0.1.1): the cross-reference
     *  cannot point at a customer that is absent from the customers master, because
     *  ``card_xref.xref_cust_id`` carries a foreign key to ``customers``. The orphan state the
     *  legacy ``COACTVWC`` L134 path defends against is therefore unreachable in the migrated
     *  schema; that verbatim ``DID-NOT-FIND-CUST-IN-CUSTDAT`` message remains asserted against
     *  the service in ``AccountServiceTest#viewAccount_customerMiss_throwsRecordNotFound``.
     * :output: The write is rejected by the database with a data-integrity violation.
     */
    @Test
    void cardXref_withUnknownCustomer_isRejectedByForeignKey() {
        CardXref orphan = new CardXref(ORPHAN_CARD_MISSING_CUST, UNSEEDED_CUST_ID, HAPPY_ACCT_ID);

        assertThatThrownBy(() -> cardXrefRepository.saveAndFlush(orphan))
                .isInstanceOf(DataIntegrityViolationException.class);

        assertThat(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM card_xref WHERE xref_card_num = ?", Integer.class,
                ORPHAN_CARD_MISSING_CUST)).isZero();
    }

    /**
     * :purpose: Account-id edit on the view path (``2210-EDIT-ACCOUNT``): a non-numeric,
     *  all-zeros, or over-eleven-digit id is rejected before any lookup.
     * :param invalidId: an account id violating the ``PIC 9(11)`` non-zero eleven-digit rule.
     * :output: HTTP 400 whose message is the verbatim account-id edit text.
     */
    @ParameterizedTest
    @ValueSource(strings = {"00000000000", "abc", "123456789012"})
    void getAccount_whenAccountIdInvalid_returns400(String invalidId) throws Exception {
        MvcResult result = mockMvc.perform(get("/accounts/{id}", invalidId))
                .andExpect(status().isBadRequest())
                .andReturn();

        ErrorResponse error = parse(result, ErrorResponse.class);
        assertThat(error.getStatus()).isEqualTo(400);
        assertThat(error.getMessage()).isEqualTo(MSG_INVALID_ACCT_ID);
    }

    /**
     * :purpose: Account-id edit on the update path (``2210-EDIT-ACCOUNT``): an all-zeros id is
     *  rejected even when the request body is otherwise well-formed.
     * :output: HTTP 400 whose message is the verbatim account-id edit text.
     */
    @Test
    void putAccount_whenAccountIdInvalid_returns400() throws Exception {
        MvcResult result = mockMvc.perform(put("/accounts/{id}", "00000000000")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest())
                .andReturn();

        ErrorResponse error = parse(result, ErrorResponse.class);
        assertThat(error.getStatus()).isEqualTo(400);
        assertThat(error.getMessage()).isEqualTo(MSG_INVALID_ACCT_ID);
    }

    /**
     * :purpose: Correlation-id observability smoke check: the filter supplies an
     *  ``X-Correlation-Id`` response header, generating one when absent and echoing an inbound
     *  value when present. This is non-load-bearing and does not alter business behavior.
     * :output: a generated non-blank header on the first request and the echoed value on the second.
     */
    @Test
    void observability_correlationIdHeader_generatedWhenAbsentAndEchoedWhenPresent() throws Exception {
        MvcResult generated = mockMvc.perform(get("/accounts/{id}", HAPPY_ACCT_ID))
                .andExpect(status().isOk())
                .andReturn();
        assertThat(generated.getResponse().getHeader(CorrelationIdFilter.CORRELATION_ID_HEADER))
                .isNotBlank();

        MvcResult echoed = mockMvc.perform(get("/accounts/{id}", HAPPY_ACCT_ID)
                        .header(CorrelationIdFilter.CORRELATION_ID_HEADER, "test-123"))
                .andExpect(status().isOk())
                .andReturn();
        assertThat(echoed.getResponse().getHeader(CorrelationIdFilter.CORRELATION_ID_HEADER))
                .isEqualTo("test-123");
    }
}
