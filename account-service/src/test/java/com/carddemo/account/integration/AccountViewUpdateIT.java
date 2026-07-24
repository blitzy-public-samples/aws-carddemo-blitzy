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

import com.carddemo.account.config.CorrelationIdFilter;
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

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
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
@SpringBootTest(properties = {"spring.jpa.hibernate.ddl-auto=none"})
@ActiveProfiles("test")
@Sql(scripts = {
        "classpath:db/migration/V1__create_customers_table.sql",
        "classpath:db/migration/V2__create_accounts_table.sql",
        "classpath:db/migration/V3__seed_customers.sql",
        "classpath:db/migration/V4__seed_accounts.sql"
}, executionPhase = Sql.ExecutionPhase.BEFORE_TEST_CLASS)
class AccountViewUpdateIT {

    /** :purpose: Flyway-seeded account/customer pair used by the happy paths (acct 1 <-> cust 1). */
    private static final long HAPPY_ACCT_ID = 1L;
    private static final long HAPPY_CUST_ID = 1L;

    /** :purpose: Seeded read-only account used by the missing-customer negative case. */
    private static final long ACCT_WITH_MISSING_CUST = 2L;

    /** :purpose: Seeded account carrying no cross-reference row (missing-xref negative case). */
    private static final long ACCT_WITHOUT_XREF = 3L;

    /** :purpose: Account id absent from the accounts master (missing-account negative case). */
    private static final long UNSEEDED_ACCT_ID = 60L;

    /** :purpose: Customer id absent from the customers master (missing-customer negative case). */
    private static final long UNSEEDED_CUST_ID = 9999L;

    /** :purpose: Distinct 16-digit card numbers per cross-reference (``xref_card_num`` is the PK). */
    private static final String CARD_HAPPY = "4111111111111111";
    private static final String CARD_MISSING_ACCT = "4111111111111160";
    private static final String CARD_MISSING_CUST = "4111111111111102";

    /** :purpose: Verbatim COACTVWC not-found messages (WORKING-STORAGE L130/L132/L134). */
    private static final String MSG_ACCT_NOT_IN_XREF = "Did not find this account in account card xref file";
    private static final String MSG_ACCT_NOT_IN_MASTER = "Did not find this account in account master file";
    private static final String MSG_CUST_NOT_IN_MASTER = "Did not find associated customer in master file";

    /** :purpose: Verbatim COACTVWC/COACTUPC account-id edit message (``2210-EDIT-ACCOUNT``). */
    private static final String MSG_INVALID_ACCT_ID = "Account number must be a non zero 11 digit number";

    /**
     * Single shared PostgreSQL container started eagerly at class initialization so its mapped
     * port is available to ``@DynamicPropertySource`` before ``@Sql(BEFORE_TEST_CLASS)`` triggers
     * the application-context load.
     */
    private static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>(DockerImageName.parse("postgres:18"));

    static {
        POSTGRES.start();
    }

    /**
     * Points the datasource at the shared container, superseding the ``jdbc:tc`` URL declared in
     * ``application-test.yml`` so exactly one container backs the whole test class.
     *
     * :param registry: the Spring dynamic property registry.
     */
    @DynamicPropertySource
    static void datasourceProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
    }

    @Autowired
    private WebApplicationContext webApplicationContext;

    @Autowired
    private CorrelationIdFilter correlationIdFilter;

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
     * Prepares a clean, deterministic fixture before each test: (re)creates the test-only
     * ``card_xref`` table, empties it, restores the mutable seed rows, blanks customer-1 PII
     * columns, and assembles a ``MockMvc`` wired with the correlation-id filter.
     *
     * :output: an isolated per-test starting state and an initialized ``mockMvc``.
     */
    @BeforeEach
    void setUp() {
        // Test-only linkage table (no foreign keys) so negative-case rows may reference an
        // absent account or customer; the account-service owns no card_xref migration.
        jdbcTemplate.execute("CREATE TABLE IF NOT EXISTS card_xref ("
                + "xref_card_num VARCHAR(16) PRIMARY KEY, "
                + "xref_cust_id BIGINT NOT NULL, "
                + "xref_acct_id BIGINT NOT NULL)");
        jdbcTemplate.execute("TRUNCATE TABLE card_xref");

        // Restore the seed rows mutated by the update happy path and reset optimistic-lock versions.
        jdbcTemplate.update("UPDATE accounts SET acct_curr_bal = 194.00, version = 0 WHERE acct_id = ?",
                HAPPY_ACCT_ID);
        jdbcTemplate.update("UPDATE customers SET cust_last_name = 'Kessler', version = 0 WHERE cust_id = ?",
                HAPPY_CUST_ID);

        // Blank customer-1 PII columns: the CryptoConverter passes empty strings through unchanged,
        // so the record hydrates without a configured encryption key and no PII is exposed.
        jdbcTemplate.update("UPDATE customers SET cust_ssn = '', cust_govt_issued_id = '', "
                + "cust_eft_account_id = '' WHERE cust_id = ?", HAPPY_CUST_ID);

        mockMvc = MockMvcBuilders.webAppContextSetup(webApplicationContext)
                .addFilters(correlationIdFilter)
                .build();
    }

    /**
     * Removes any cross-reference rows seeded by a test so cases remain order-independent.
     */
    @AfterEach
    void tearDown() {
        jdbcTemplate.execute("TRUNCATE TABLE card_xref");
    }

    /**
     * Assembles a fully-populated update request from a view response; the mapper copies every
     * editable field unconditionally, so all NOT NULL columns must be supplied.
     *
     * :param view: the current account/customer view whose editable fields seed the request.
     * :returns: an ``AccountUpdateRequestDto`` mirroring the supplied view.
     */
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
        request.setCustAddrStateCd(view.getCustAddrStateCd());
        request.setCustAddrCountryCd(view.getCustAddrCountryCd());
        request.setCustAddrZip(view.getCustAddrZip());
        request.setCustPhoneNum1(view.getCustPhoneNum1());
        request.setCustPhoneNum2(view.getCustPhoneNum2());
        request.setCustSsn(view.getCustSsn());
        request.setCustGovtIssuedId(view.getCustGovtIssuedId());
        request.setCustDobYyyyMmDd(view.getCustDobYyyyMmDd());
        request.setCustEftAccountId(view.getCustEftAccountId());
        request.setCustPriCardHolderInd(view.getCustPriCardHolderInd());
        request.setCustFicoCreditScore(view.getCustFicoCreditScore());
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
     * :purpose: Account view happy path (CICS ``CAVW`` / ``COACTVWC``): after a cross-reference
     *  links the account to its customer, ``GET /accounts/{id}`` returns the combined
     *  account-and-customer view.
     * :output: HTTP 200 with scale-2 money, the preserved ``acctExpiraionDate`` misspelling,
     *  and the seeded non-PII customer fields; regulated PII is not asserted.
     */
    @Test
    void getAccount_returns200WithAccountAndCustomerFields() throws Exception {
        cardXrefRepository.save(new CardXref(CARD_HAPPY, HAPPY_CUST_ID, HAPPY_ACCT_ID));

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

        // Wire-level confirmation that money renders at scale 2 (COBOL COMP-3 fidelity).
        String body = result.getResponse().getContentAsString();
        assertThat(body).contains("\"acctCurrBal\":194.00");
        assertThat(body).contains("\"acctCurrCycCredit\":0.00");
        assertThat(body).contains("\"acctCurrCycDebit\":0.00");
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
        cardXrefRepository.save(new CardXref(CARD_HAPPY, HAPPY_CUST_ID, HAPPY_ACCT_ID));

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
     *  row, so the view short-circuits before the account read.
     * :output: HTTP 404 whose message is the verbatim ``DID-NOT-FIND-ACCT-IN-CARDXREF`` text.
     */
    @Test
    void getAccount_whenCardXrefMissing_returns404() throws Exception {
        MvcResult result = mockMvc.perform(get("/accounts/{id}", ACCT_WITHOUT_XREF))
                .andExpect(status().isNotFound())
                .andReturn();

        ErrorResponse error = parse(result, ErrorResponse.class);
        assertThat(error.getStatus()).isEqualTo(404);
        assertThat(error.getMessage()).isEqualTo(MSG_ACCT_NOT_IN_XREF);
    }

    /**
     * :purpose: Second ordered lookup miss (``COACTVWC`` L132): the cross-reference resolves but
     *  points at an account absent from the master file.
     * :output: HTTP 404 whose message is the verbatim ``DID-NOT-FIND-ACCT-IN-ACCTDAT`` text.
     */
    @Test
    void getAccount_whenAccountMasterMissing_returns404() throws Exception {
        cardXrefRepository.save(new CardXref(CARD_MISSING_ACCT, HAPPY_CUST_ID, UNSEEDED_ACCT_ID));

        MvcResult result = mockMvc.perform(get("/accounts/{id}", UNSEEDED_ACCT_ID))
                .andExpect(status().isNotFound())
                .andReturn();

        ErrorResponse error = parse(result, ErrorResponse.class);
        assertThat(error.getStatus()).isEqualTo(404);
        assertThat(error.getMessage()).isEqualTo(MSG_ACCT_NOT_IN_MASTER);
    }

    /**
     * :purpose: Third ordered lookup miss (``COACTVWC`` L134): the cross-reference and account
     *  resolve but the linked customer is absent from the master file.
     * :output: HTTP 404 whose message is the verbatim ``DID-NOT-FIND-CUST-IN-CUSTDAT`` text.
     */
    @Test
    void getAccount_whenCustomerMasterMissing_returns404() throws Exception {
        cardXrefRepository.save(new CardXref(CARD_MISSING_CUST, UNSEEDED_CUST_ID, ACCT_WITH_MISSING_CUST));

        MvcResult result = mockMvc.perform(get("/accounts/{id}", ACCT_WITH_MISSING_CUST))
                .andExpect(status().isNotFound())
                .andReturn();

        ErrorResponse error = parse(result, ErrorResponse.class);
        assertThat(error.getStatus()).isEqualTo(404);
        assertThat(error.getMessage()).isEqualTo(MSG_CUST_NOT_IN_MASTER);
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
        cardXrefRepository.save(new CardXref(CARD_HAPPY, HAPPY_CUST_ID, HAPPY_ACCT_ID));

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
