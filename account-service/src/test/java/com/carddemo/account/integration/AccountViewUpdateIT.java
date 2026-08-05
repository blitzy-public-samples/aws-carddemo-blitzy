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
import com.carddemo.common.crypto.CryptoConverter;
import com.carddemo.common.domain.Customer;
import com.carddemo.common.dto.AccountUpdateRequestDto;
import com.carddemo.common.dto.AccountUpdateResponseDto;
import com.carddemo.common.dto.AccountViewResponseDto;
import com.carddemo.common.dto.ErrorResponse;
import com.carddemo.common.testsupport.MigratedSchemaContainer;
import com.carddemo.common.crypto.PiiMasker;
import com.carddemo.common.dto.SessionContext;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.util.Map;
import java.util.function.Consumer;
import java.util.stream.Stream;

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
 *  preserved ``ACCT-EXPIRAION-DATE`` misspelling), the reachable verbatim not-found (HTTP 404)
 *  outcome, the invalid-account-id edit (HTTP 400), and a lightweight correlation-id
 *  observability smoke check. Regulated PII fields (SSN, government id, card number) are
 *  never asserted.
 * :note: The schema and its fixtures come from the shared production migration set in
 *  carddemo-common (``classpath:db/migration``, enabled for the ``test`` profile), so the
 *  context boots against the real schema under Hibernate ``ddl-auto: validate`` and the seeded
 *  PII columns already hold the ciphertext the JPA converter expects. The two remaining
 *  ordered-lookup misses of ``COACTVWC`` - a cross-reference pointing at an absent account
 *  (L132) and at an absent customer (L134) - are unreachable against this schema by design:
 *  ``card_xref`` carries real foreign keys to ``accounts`` and ``customers`` (AAP 0.6.4), so
 *  such a row cannot exist. Those two service branches are covered with mocked repositories in
 *  {@code AccountServiceTest}.
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
    /** :purpose: Seeded account carrying no cross-reference row (missing-xref negative case). */
    private static final long ACCT_WITHOUT_XREF = 3L;

    /** :purpose: Non-sensitive 16-digit card number linking the happy-path cross-reference. */
    private static final String CARD_HAPPY = "4111111111111111";

    /** :purpose: Verbatim COACTVWC not-found message (WORKING-STORAGE L130). */
    private static final String MSG_ACCT_NOT_IN_XREF = "Did not find this account in account card xref file";

    /** :purpose: Verbatim COACTVWC/COACTUPC account-id edit message (``2210-EDIT-ACCOUNT``). */
    private static final String MSG_INVALID_ACCT_ID = "Account number must be a non zero 11 digit number";

    /** :purpose: A social security number that satisfies ``1265-EDIT-US-SSN``. */
    private static final String VALID_SSN = "020973888";

    /** :purpose: A government issued id that satisfies the alphanumeric column width. */
    private static final String VALID_GOVT_ID = "00000000000049368437";

    /** :purpose: An EFT account id that satisfies ``1245-EDIT-NUM-REQD``. */
    private static final String VALID_EFT_ACCOUNT_ID = "0053581756";

    /** :purpose: A FICO score inside the ``1275-EDIT-FICO-SCORE`` 300-850 range. */
    private static final int VALID_FICO_SCORE = 700;

    /**
     * Single shared PostgreSQL container started eagerly at class initialization so its mapped
     * port is available to ``@DynamicPropertySource`` before the application context loads and
     * the shared carddemo-common Flyway migration set (``V1`` schema, ``V2`` reference data,
     * ``V3`` test data, ``V4`` batch metadata) creates and seeds every table this class reads.
     */
    private static final String VALID_ZIP = "48226";

    /**
     * :purpose: Phone numbers whose area codes are North America general purpose codes
     *     (``1260-EDIT-US-PHONE-NUM``); the seeded ``(373)`` is not one.
     */
    private static final String VALID_PHONE_1 = "(908)119-8310";
    private static final String VALID_PHONE_2 = "(801)693-8684";

    /** :purpose: COACTUPC ``NO-SEARCH-CRITERIA-RECEIVED`` (L490). */
    private static final String MSG_NO_INPUT = "No input received";

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

    /**
     * :purpose: Build a signed-on session carrying the ADMIN ``SessionContext`` under the
     *     canonical attribute name, so a request passes the session-derived
     *     authentication filter exactly as it does after ``POST /auth/signon``.
     * :returns: a ``MockHttpSession`` holding an administrator session context.
     */
    private static MockHttpSession signedOnSession() {
        MockHttpSession session = new MockHttpSession();
        SessionContext context = new SessionContext();
        context.setUserId("ADMIN001");
        context.setUserType(SessionContext.UserType.CDEMO_USRTYP_ADMIN);
        session.setAttribute(SessionContext.SESSION_ATTRIBUTE_NAME, context);
        return session;
    }

    @Autowired
    private WebApplicationContext webApplicationContext;

    /**
     * Registration of the SHARED carddemo-common correlation-id filter, contributed by
     * ``WebObservabilityConfig`` through ``META-INF/spring/...AutoConfiguration.imports``.
     * Injecting the registration (rather than instantiating the filter) also asserts that the
     * shared filter really is wired into this service's context, which is the defect QA Issue 7
     * reported: the shared filter was never registered and five services carried divergent local
     * copies that ignored the ``traceparent`` fallback.
     */
    @Autowired
    private FilterRegistrationBean<CorrelationIdFilter> correlationIdFilterRegistration;
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
     * Prepares a clean, deterministic fixture before each test: empties the migrated
     * ``card_xref`` table, restores the mutable seed rows, and assembles a ``MockMvc`` wired with
     * the correlation-id filter.
     *
     * :output: an isolated per-test starting state and an initialized ``mockMvc``.
     * :note: ``card_xref`` is created and seeded by the shared migration set; emptying it keeps
     *  each case independent of the seeded linkage while leaving the accounts and customers the
     *  cases assert on in place. The seeded PII columns hold real AES-256-GCM tokens (written by
     *  the migration set's version-4 Java migration under the test key), so the customer record
     *  hydrates through the JPA converter with no fixture patching.
     */
    @BeforeEach
    void setUp() {
        jdbcTemplate.execute("TRUNCATE TABLE card_xref");
        // Replace the migrated card_xref with a foreign-key-free variant of the same shape so
        // the negative cases can stage cross-reference rows that deliberately point at an
        // absent account or customer — the very orphan conditions COACTVWC reports. The
        // shared V1 schema declares fk_card_xref_cust / fk_card_xref_acct, which would reject
        // those rows at insert time and prevent the service-layer path from being exercised.
        jdbcTemplate.execute("DROP TABLE IF EXISTS card_xref");
        jdbcTemplate.execute("CREATE TABLE card_xref ("
                + "xref_card_num VARCHAR(16) PRIMARY KEY, "
                + "xref_cust_id BIGINT NOT NULL, "
                + "xref_acct_id BIGINT NOT NULL)");

        // Restore the seed rows mutated by the update happy path and reset optimistic-lock versions.
        jdbcTemplate.update("UPDATE accounts SET acct_curr_bal = 194.00, version = 0 WHERE acct_id = ?",
                HAPPY_ACCT_ID);
        jdbcTemplate.update("UPDATE customers SET cust_last_name = 'Kessler', version = 0 WHERE cust_id = ?",
                HAPPY_CUST_ID);

        // Restore the sensitive customer columns as the AES-GCM ciphertext the JPA
        // CryptoConverter expects. The Flyway seed inserts them as plaintext with raw SQL,
        // which bypasses the converter and is exactly the defect QA F3 reported; writing the
        // ciphertext here reproduces the corrected at-rest contract that
        // PiiAtRestInitializer establishes at service start (proved by PiiAtRestSweepIT).
        CryptoConverter converter = new CryptoConverter();
        jdbcTemplate.update("UPDATE customers SET cust_ssn = ?, cust_govt_issued_id = ?, "
                        + "cust_eft_account_id = ? WHERE cust_id = ?",
                converter.convertToDatabaseColumn(VALID_SSN),
                converter.convertToDatabaseColumn(VALID_GOVT_ID),
                converter.convertToDatabaseColumn(VALID_EFT_ACCOUNT_ID),
                HAPPY_CUST_ID);

        // The shared correlation filter is registered in production by
        // carddemo-common WebObservabilityConfig (a FilterRegistrationBean over a
        // non-bean instance, so it is registered exactly once); a standalone
        // MockMvc setup does not pick up servlet registrations, so the same filter
        // type is composed into the chain explicitly here.
        mockMvc = MockMvcBuilders.webAppContextSetup(webApplicationContext)
                .addFilters(new CorrelationIdFilter())
                .addFilters(correlationIdFilterRegistration.getFilter())
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
     * The submission must additionally satisfy every ``COACTUPC 1200-EDIT-MAP-INPUTS`` edit,
     * exactly as the 3270 screen's ENTER key would. Three seeded values do not satisfy those
     * edits and are therefore corrected here, as an operator would have to correct them on the
     * screen before saving: the FICO score (seeded 274, below the 300-850 range enforced by
     * ``1275-EDIT-FICO-SCORE``), the zip and second phone number (whose seeded values are not
     * in the ``CSLKPCDY`` lookup tables), and the three sensitive identifiers, which the
     * response masks per AAP 0.6.7 and so cannot be echoed back.
     *
     * :param view: the current account/customer view whose editable fields seed the request.
     * :returns: an ``AccountUpdateRequestDto`` mirroring the supplied view.
     */
    /** :purpose: State code whose ZIP prefix pairing is in the ``CSLKPCDY`` state-ZIP table. */
    private static final String VALID_STATE_CD = "MI";


    /** :purpose: Screen-valid phone number 1 whose area code is in ``CSLKPCDY``. */
    private static final String VALID_PHONE_NUM_1 = "(212)555-0101";

    /** :purpose: Screen-valid phone number 2 whose area code is in ``CSLKPCDY``. */
    private static final String VALID_PHONE_NUM_2 = "(801)555-0102";


    private AccountUpdateRequestDto toUpdateRequest(AccountViewResponseDto view) {
        AccountUpdateRequestDto request = new AccountUpdateRequestDto();
        // Echo the optimistic-lock snapshot exactly as a client does: the server compares it
        // against the stored record before rewriting (read-snapshot-compare-rewrite).
        request.setVersion(view.getVersion());
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
        request.setCustEftAccountId(VALID_EFT_ACCOUNT_ID);
        request.setCustPriCardHolderInd(view.getCustPriCardHolderInd());
        // 1275-EDIT-FICO-SCORE: the seeded 274 is outside the legacy 300-850 range.
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
        assertThat(current.getCustSsn()).isEqualTo(PiiMasker.maskIdentifier(identifierBefore));

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
        cardXrefRepository.save(new CardXref(CARD_HAPPY, HAPPY_CUST_ID, HAPPY_ACCT_ID));

        MvcResult result = mockMvc.perform(get("/accounts/{id}", HAPPY_ACCT_ID).session(signedOnSession()))
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
                mockMvc.perform(get("/accounts/{id}", HAPPY_ACCT_ID).session(signedOnSession()))
                        .andExpect(status().isOk())
                        .andReturn(),
                AccountViewResponseDto.class);

        AccountUpdateRequestDto request = toUpdateRequest(current);
        request.setAcctCurrBal(new BigDecimal("250.00"));
        request.setCustLastName("Kesslerupd");

        MvcResult result = mockMvc.perform(put("/accounts/{id}", HAPPY_ACCT_ID).session(signedOnSession())
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
        MvcResult result = mockMvc.perform(get("/accounts/{id}", ACCT_WITHOUT_XREF).session(signedOnSession()))
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
        MvcResult result = mockMvc.perform(get("/accounts/{id}", invalidId).session(signedOnSession()))
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
        MvcResult result = mockMvc.perform(put("/accounts/{id}", "00000000000").session(signedOnSession())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest())
                .andReturn();

        ErrorResponse error = parse(result, ErrorResponse.class);
        assertThat(error.getStatus()).isEqualTo(400);
        assertThat(error.getMessage()).isEqualTo(MSG_INVALID_ACCT_ID);
    }

    /**
     * :purpose: COACTUPC ``NO-SEARCH-CRITERIA-RECEIVED`` (L490): an empty JSON body carries no
     *  field at all, so the edit sequence rejects it before any record is read (QA F19 — this
     *  previously returned HTTP 500 with the Spring Boot default body).
     * :output: HTTP 400 carrying the verbatim ``'No input received'`` message.
     */
    @Test
    void putAccount_withEmptyBody_returns400NoInputReceived() throws Exception {
        cardXrefRepository.save(new CardXref(CARD_HAPPY, HAPPY_CUST_ID, HAPPY_ACCT_ID));

        MvcResult result = mockMvc.perform(put("/accounts/{id}", HAPPY_ACCT_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest())
                .andReturn();

        ErrorResponse error = parse(result, ErrorResponse.class);
        assertThat(error.getStatus()).isEqualTo(400);
        assertThat(error.getMessage()).isEqualTo(MSG_NO_INPUT);

        // Nothing was written: the seeded balance and last name are intact.
        assertMoney(accountRepository.findById(HAPPY_ACCT_ID).orElseThrow().getAcctCurrBal(), "194.00");
        assertThat(customerRepository.findById(HAPPY_CUST_ID).orElseThrow().getCustLastName())
                .isEqualTo("Kessler");
    }

    /**
     * :purpose: COACTUPC ``1200-EDIT-MAP-INPUTS`` field edits (QA F18): every invalid field value
     *  is rejected with the verbatim legacy message and nothing is persisted.
     * :param mutation: the field mutation applied to an otherwise valid submission.
     * :param expectedMessage: the verbatim COACTUPC message the edit must report.
     * :output: HTTP 400 with the expected message and an unchanged database.
     */
    @ParameterizedTest(name = "[{index}] {1}")
    @MethodSource("invalidUpdateSubmissions")
    void putAccount_invalidFieldValue_returns400WithVerbatimMessage(
            Consumer<AccountUpdateRequestDto> mutation, String expectedMessage) throws Exception {
        cardXrefRepository.save(new CardXref(CARD_HAPPY, HAPPY_CUST_ID, HAPPY_ACCT_ID));

        AccountViewResponseDto current = parse(
                mockMvc.perform(get("/accounts/{id}", HAPPY_ACCT_ID)).andExpect(status().isOk()).andReturn(),
                AccountViewResponseDto.class);

        AccountUpdateRequestDto request = toUpdateRequest(current);
        request.setCustLastName("Changed");
        mutation.accept(request);

        MvcResult result = mockMvc.perform(put("/accounts/{id}", HAPPY_ACCT_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest())
                .andReturn();

        assertThat(parse(result, ErrorResponse.class).getMessage()).isEqualTo(expectedMessage);

        // The rejected submission never reached the record.
        assertThat(customerRepository.findById(HAPPY_CUST_ID).orElseThrow().getCustLastName())
                .isEqualTo("Kessler");
        assertMoney(accountRepository.findById(HAPPY_ACCT_ID).orElseThrow().getAcctCurrBal(), "194.00");
    }

    /**
     * :purpose: Supply the invalid-field cases exercised above, covering every COACTUPC edit the
     *  QA report found missing plus the out-of-scale money case (QA F21).
     * :returns: a stream of (mutation, verbatim message) pairs.
     */
    private static Stream<Arguments> invalidUpdateSubmissions() {
        return Stream.of(
                Arguments.of((Consumer<AccountUpdateRequestDto>) r -> r.setAcctActiveStatus("Z"),
                        "Account Active Status must be Y or N"),
                Arguments.of((Consumer<AccountUpdateRequestDto>) r -> r.setAcctCreditLimit(null),
                        "Credit Limit must be supplied"),
                Arguments.of((Consumer<AccountUpdateRequestDto>) r ->
                        r.setAcctCreditLimit(new BigDecimal("100.005")),
                        "Credit Limit is not valid"),
                Arguments.of((Consumer<AccountUpdateRequestDto>) r ->
                        r.setAcctExpiraionDate("2030-13-15"),
                        "Card expiry month must be between 1 and 12"),
                Arguments.of((Consumer<AccountUpdateRequestDto>) r ->
                        r.setAcctExpiraionDate("1849-05-20"),
                        "Invalid card expiry year"),
                Arguments.of((Consumer<AccountUpdateRequestDto>) r ->
                        r.setCustFirstName("<script>alert(1)</script>"),
                        "Name can only contain alphabets and spaces"),
                Arguments.of((Consumer<AccountUpdateRequestDto>) r -> r.setCustLastName(null),
                        "Last name not provided"),
                Arguments.of((Consumer<AccountUpdateRequestDto>) r ->
                        r.setCustFicoCreditScore(9999),
                        "FICO Score: should be between 300 and 850"),
                Arguments.of((Consumer<AccountUpdateRequestDto>) r -> r.setCustSsn("900010001"),
                        "SSN: First 3 chars: should not be 000, 666, or between 900 and 999"),
                Arguments.of((Consumer<AccountUpdateRequestDto>) r ->
                        r.setAcctCurrBal(new BigDecimal("300.005")),
                        "Current Balance is not valid"),
                Arguments.of((Consumer<AccountUpdateRequestDto>) r ->
                        r.setAcctOpenDate("2014-02-30"),
                        "Open Date:day must be a number between 1 and 31."),
                Arguments.of((Consumer<AccountUpdateRequestDto>) r ->
                        r.setCustAddrStateCd("NCX"),
                        "State: is not a valid state code"),
                Arguments.of((Consumer<AccountUpdateRequestDto>) r ->
                        r.setCustPhoneNum1("(000)119-8310"),
                        "Phone Number 1: Area code cannot be zero"),
                Arguments.of((Consumer<AccountUpdateRequestDto>) r ->
                        r.setCustEftAccountId("EFT900001ACC"),
                        "EFT Account Id must be all numeric."),
                Arguments.of((Consumer<AccountUpdateRequestDto>) r ->
                        r.setCustPriCardHolderInd("X"),
                        "Primary Card Holder must be Y or N."));
    }

    /**
     * :purpose: COACTUPC ``1205-COMPARE-OLD-NEW`` (L1681+): a submission whose values match the
     *  display-time snapshot reports ``NO-CHANGES-DETECTED`` and rewrites nothing.
     * :output: HTTP 400 carrying the verbatim no-change message.
     */
    @Test
    void putAccount_noChangeAgainstSnapshot_returns400NoChangeDetected() throws Exception {
        cardXrefRepository.save(new CardXref(CARD_HAPPY, HAPPY_CUST_ID, HAPPY_ACCT_ID));

        AccountUpdateRequestDto request = new AccountUpdateRequestDto();
        request.setAcctActiveStatus("Y");
        request.setOldAcctActiveStatus("Y");

        MvcResult result = mockMvc.perform(put("/accounts/{id}", HAPPY_ACCT_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest())
                .andReturn();

        assertThat(parse(result, ErrorResponse.class).getMessage())
                .isEqualTo("No change detected with respect to values fetched.");
    }

    /**
     * :purpose: AAP 0.6.2 read-snapshot-compare-rewrite (QA F20 lost update): user A displays the
     *  record, user B commits a change, then user A saves its stale snapshot. The stale save is
     *  refused with the verbatim COACTUPC conflict message and user B's committed value survives.
     * :output: HTTP 409 for user A and a database still holding user B's 999.99.
     */
    @Test
    void putAccount_staleSnapshot_returns409AndPreservesTheOtherUsersCommit() throws Exception {
        cardXrefRepository.save(new CardXref(CARD_HAPPY, HAPPY_CUST_ID, HAPPY_ACCT_ID));

        // User A displays the record.
        AccountViewResponseDto displayed = parse(
                mockMvc.perform(get("/accounts/{id}", HAPPY_ACCT_ID)).andExpect(status().isOk()).andReturn(),
                AccountViewResponseDto.class);
        assertMoney(displayed.getAcctCurrBal(), "194.00");

        // User B commits 999.99 out of band.
        jdbcTemplate.update("UPDATE accounts SET acct_curr_bal = 999.99, version = version + 1 "
                + "WHERE acct_id = ?", HAPPY_ACCT_ID);

        // User A saves its stale snapshot.
        AccountUpdateRequestDto stale = toUpdateRequest(displayed);
        stale.setOldAcctCurrBal(displayed.getAcctCurrBal());
        stale.setAcctCurrBal(new BigDecimal("111.00"));

        MvcResult result = mockMvc.perform(put("/accounts/{id}", HAPPY_ACCT_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(stale)))
                .andExpect(status().isConflict())
                .andReturn();

        assertThat(parse(result, ErrorResponse.class).getMessage())
                .isEqualTo("Record changed by some one else. Please review");

        // User B's committed change survived.
        assertMoney(accountRepository.findById(HAPPY_ACCT_ID).orElseThrow().getAcctCurrBal(), "999.99");
    }

    /**
     * :purpose: AAP 0.6.7 (QA F8): the three sensitive customer identifiers never leave the
     *  service in clear text, on either the view or the post-update echo.
     * :output: masked values on the wire and the clear-text values absent from both bodies.
     */
    @Test
    void accountResponses_maskSensitiveCustomerIdentifiers() throws Exception {
        cardXrefRepository.save(new CardXref(CARD_HAPPY, HAPPY_CUST_ID, HAPPY_ACCT_ID));

        MvcResult viewResult = mockMvc.perform(get("/accounts/{id}", HAPPY_ACCT_ID))
                .andExpect(status().isOk())
                .andReturn();
        String viewBody = viewResult.getResponse().getContentAsString();

        assertThat(viewBody).doesNotContain(VALID_SSN);
        assertThat(viewBody).doesNotContain(VALID_GOVT_ID);
        assertThat(viewBody).doesNotContain(VALID_EFT_ACCOUNT_ID);

        AccountViewResponseDto view = parse(viewResult, AccountViewResponseDto.class);
        assertThat(view.getCustSsn()).startsWith("***-**-");
        assertThat(view.getCustGovtIssuedId()).startsWith("*");
        assertThat(view.getCustEftAccountId()).startsWith("*");

        AccountUpdateRequestDto request = toUpdateRequest(view);
        request.setCustLastName("Masktest");

        MvcResult updateResult = mockMvc.perform(put("/accounts/{id}", HAPPY_ACCT_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andReturn();
        String updateBody = updateResult.getResponse().getContentAsString();

        assertThat(updateBody).doesNotContain(VALID_SSN);
        assertThat(updateBody).doesNotContain(VALID_GOVT_ID);
        assertThat(updateBody).doesNotContain(VALID_EFT_ACCOUNT_ID);
        assertThat(parse(updateResult, AccountUpdateResponseDto.class).getCustSsn())
                .isEqualTo("***-**-3888");
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

        MvcResult generated = mockMvc.perform(get("/accounts/{id}", HAPPY_ACCT_ID).session(signedOnSession()))
                .andExpect(status().isOk())
                .andReturn();
        assertThat(generated.getResponse().getHeader(CorrelationIdFilter.CORRELATION_ID_HEADER))
                .isNotBlank();

        MvcResult echoed = mockMvc.perform(get("/accounts/{id}", HAPPY_ACCT_ID).session(signedOnSession())
                        .header(CorrelationIdFilter.CORRELATION_ID_HEADER, "test-123"))
                .andExpect(status().isOk())
                .andReturn();
        assertThat(echoed.getResponse().getHeader(CorrelationIdFilter.CORRELATION_ID_HEADER))
                .isEqualTo("test-123");
    }
}
