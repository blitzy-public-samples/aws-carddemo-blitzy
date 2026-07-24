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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.carddemo.account.repository.AccountRepository;
import com.carddemo.account.repository.CardXrefRepository;
import com.carddemo.account.repository.CustomerRepository;
import com.carddemo.account.service.AccountService;
import com.carddemo.common.domain.Account;
import com.carddemo.common.domain.CardXref;
import com.carddemo.common.domain.Customer;
import com.carddemo.common.dto.AccountUpdateRequestDto;
import com.carddemo.common.dto.AccountUpdateResponseDto;

import java.math.BigDecimal;
import java.util.Map;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

/**
 * :purpose: Full-context integration test proving that the migrated account
 *     update flow (``PUT /accounts/{id}``, CICS transaction ``CAUP``, program
 *     ``app/cbl/COACTUPC.cbl``) preserves the legacy multi-file logical unit of
 *     work. The legacy dual ``REWRITE`` -- ACCTFILE (COACTUPC.cbl L4066) and
 *     CUSTFILE (COACTUPC.cbl L4086) with ``SYNCPOINT ROLLBACK`` on failure --
 *     becomes a single ``@Transactional`` {@code AccountService.updateAccount}
 *     that persists the customer first and the account second. This test forces
 *     the second persistence (the account) to fail and verifies that BOTH the
 *     account and the customer changes roll back together, so the atomicity
 *     boundary is never broken.
 * :output: JUnit 5 test cases executed against an ephemeral Testcontainers
 *     PostgreSQL instance seeded by the account-service Flyway migrations. The
 *     second-write failure is induced with a genuine database constraint
 *     violation (a monetary value exceeding ``NUMERIC(12,2)`` on the account),
 *     and the post-failure state is asserted through cache-bypassing JDBC reads
 *     so a stale JPA first-level cache cannot mask a partial commit.
 */
@SpringBootTest
@ActiveProfiles("test")
@Testcontainers
public class AtomicRollbackIT {

    /**
     * :purpose: Ephemeral PostgreSQL 18 backing datastore, started once for the
     *     class by the Testcontainers JUnit 5 extension. Its connection details
     *     are bound to the Spring datasource by {@link #datasourceProperties}.
     */
    @Container
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>(DockerImageName.parse("postgres:18"))
                    .withDatabaseName("carddemo")
                    .withUsername("test")
                    .withPassword("test");

    /**
     * :purpose: Bind the running container's JDBC coordinates to the Spring
     *     datasource and relax Hibernate schema validation for this context. The
     *     account-service {@code @EntityScan("com.carddemo.common.domain")}
     *     registers every shared entity, but the account-service Flyway
     *     migrations (V1-V4) create only the ``customers`` and ``accounts``
     *     tables; ``ddl-auto=none`` lets the context boot without validating the
     *     unrelated entities while leaving the Flyway-owned production schema
     *     untouched (no test-side schema redefinition).
     * :param registry: the dynamic property registry populated before the
     *     application context starts.
     */
    @DynamicPropertySource
    static void datasourceProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.datasource.driver-class-name", POSTGRES::getDriverClassName);
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "none");
    }

    /**
     * :purpose: Apply the account-service Flyway migrations (V1 through V4) to the
     *     ephemeral container once, before any test runs, creating and seeding the
     *     ``customers`` and ``accounts`` tables. The migration scripts are executed
     *     directly through the Flyway API against the production migration location
     *     (``classpath:db/migration``); no schema is redefined in the test tree.
     */
    @BeforeAll
    static void migrateSchema() {
        Flyway.configure()
                .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .locations("classpath:db/migration")
                .table("flyway_schema_history_account")
                .baselineOnMigrate(true)
                .load()
                .migrate();
    }

    /** :purpose: Seeded account identifier under test (``ACCT-ID PIC 9(11)``). */
    private static final Long ACCT_ID = 4L;

    /** :purpose: Seeded customer identifier linked to {@link #ACCT_ID} through the cross-reference (``CUST-ID PIC 9(09)``). */
    private static final Long CUST_ID = 4L;

    /** :purpose: Non-sensitive synthetic 16-digit card number used only to seed the cross-reference linkage (``XREF-CARD-NUM PIC X(16)``). */
    private static final String TEST_CARD_NUM = "4000000000000004";

    /**
     * :purpose: Monetary value engineered to overflow the account
     *     ``acct_curr_bal NUMERIC(12,2)`` column (fourteen integer digits against
     *     a ten-digit maximum), forcing the second (account) write of the single
     *     transaction to fail on flush.
     */
    private static final BigDecimal OVERFLOW_BALANCE = new BigDecimal("99999999999999.99");

    /** :purpose: Valid scale-2 balance used by the success control case. */
    private static final BigDecimal VALID_NEW_BALANCE = new BigDecimal("123.45");

    /** :purpose: Non-PII customer change marker for the mandatory dual-rollback case. */
    private static final String ROLLBACK_MARKER_LAST_NAME = "RollbackName";

    /** :purpose: Non-PII customer change marker for the no-partial-commit case. */
    private static final String PARTIAL_MARKER_LAST_NAME = "PartialName";

    /** :purpose: Non-PII customer change marker for the success control case. */
    private static final String SUCCESS_MARKER_LAST_NAME = "SuccessName";

    /** :purpose: Account view/update service under test (``COACTVWC``/``COACTUPC``); the transactional proxy. */
    @Autowired
    private AccountService accountService;

    /** :purpose: Account master repository for pre-invocation reads (ACCTFILE). */
    @Autowired
    private AccountRepository accountRepository;

    /** :purpose: Customer master repository for pre-invocation reads (CUSTFILE). */
    @Autowired
    private CustomerRepository customerRepository;

    /** :purpose: Card cross-reference repository used to seed and clear the account-to-customer linkage (CXACAIX). */
    @Autowired
    private CardXrefRepository cardXrefRepository;

    /** :purpose: JDBC template issuing cache-bypassing reads and the setup/cleanup statements. */
    @Autowired
    private JdbcTemplate jdbcTemplate;

    /** :purpose: Captured original account balance restored during cleanup. */
    private BigDecimal originalAcctCurrBal;

    /** :purpose: Captured original customer last name restored during cleanup. */
    private String originalCustLastName;

    /** :purpose: Captured original (plaintext seed) customer SSN restored during cleanup. */
    private String originalCustSsn;

    /** :purpose: Captured original (plaintext seed) customer government-issued id restored during cleanup. */
    private String originalCustGovtIssuedId;

    /** :purpose: Captured original (plaintext seed) customer EFT account id restored during cleanup. */
    private String originalCustEftAccountId;

    /**
     * :purpose: Prepare the fixture before each test: create the ``card_xref``
     *     table (absent from the account-service migration set), capture the
     *     seeded account and customer originals, neutralize the customer PII
     *     columns to empty strings so the encrypted-attribute converter reads
     *     them as pass-through values without a configured key, and seed the
     *     account-to-customer cross-reference linkage so the update flow reaches
     *     the write step rather than short-circuiting on a missing reference.
     */
    @BeforeEach
    void setUp() {
        jdbcTemplate.execute("CREATE TABLE IF NOT EXISTS card_xref ("
                + "xref_card_num VARCHAR(16) PRIMARY KEY, "
                + "xref_cust_id BIGINT NOT NULL, "
                + "xref_acct_id BIGINT NOT NULL)");

        originalAcctCurrBal = jdbcTemplate.queryForObject(
                "SELECT acct_curr_bal FROM accounts WHERE acct_id = ?", BigDecimal.class, ACCT_ID);

        Map<String, Object> customerRow = jdbcTemplate.queryForMap(
                "SELECT cust_last_name, cust_ssn, cust_govt_issued_id, cust_eft_account_id "
                        + "FROM customers WHERE cust_id = ?", CUST_ID);
        originalCustLastName = (String) customerRow.get("cust_last_name");
        originalCustSsn = (String) customerRow.get("cust_ssn");
        originalCustGovtIssuedId = (String) customerRow.get("cust_govt_issued_id");
        originalCustEftAccountId = (String) customerRow.get("cust_eft_account_id");

        // Empty the encrypted PII columns so the CryptoConverter read path is a
        // key-free pass-through; the plaintext seed values are restored on teardown.
        jdbcTemplate.update("UPDATE customers SET cust_ssn = '', cust_govt_issued_id = '', "
                + "cust_eft_account_id = '' WHERE cust_id = ?", CUST_ID);

        cardXrefRepository.deleteAll();
        cardXrefRepository.save(new CardXref(TEST_CARD_NUM, CUST_ID, ACCT_ID));
    }

    /**
     * :purpose: Restore the seeded account and customer to their captured
     *     original values and remove the seeded cross-reference, so the tests
     *     remain isolated and pass repeatedly and in any order.
     */
    @AfterEach
    void tearDown() {
        jdbcTemplate.update("UPDATE accounts SET acct_curr_bal = ? WHERE acct_id = ?",
                originalAcctCurrBal, ACCT_ID);
        jdbcTemplate.update("UPDATE customers SET cust_last_name = ?, cust_ssn = ?, "
                        + "cust_govt_issued_id = ?, cust_eft_account_id = ? WHERE cust_id = ?",
                originalCustLastName, originalCustSsn, originalCustGovtIssuedId,
                originalCustEftAccountId, CUST_ID);
        cardXrefRepository.deleteAll();
    }

    /**
     * :purpose: Verify that when the second persistence in the single update
     *     transaction (the account write) fails, both the account and the
     *     customer changes roll back together, reproducing the legacy
     *     one-logical-unit-of-work semantics (COACTUPC.cbl L4066 + L4086). The
     *     induced failure is a real ``NUMERIC(12,2)`` overflow on the account
     *     balance; the post-failure account balance and customer last name are
     *     re-read with a fresh JDBC query and must both equal their originals.
     */
    @Test
    void secondWriteFails_rollsBackBothAccountAndCustomer() {
        Account account = accountRepository.findById(ACCT_ID).orElseThrow();
        Customer customer = customerRepository.findById(CUST_ID).orElseThrow();

        AccountUpdateRequestDto request = fullRequestFrom(account, customer);
        // The account is the SECOND write; an over-precision balance overflows the
        // NUMERIC(12,2) column on flush so the account write is the failing write.
        request.setAcctCurrBal(OVERFLOW_BALANCE);
        // The customer is the FIRST write; this change must not survive the rollback.
        request.setCustLastName(ROLLBACK_MARKER_LAST_NAME);

        assertThatThrownBy(() -> accountService.updateAccount(ACCT_ID, request, null))
                .isInstanceOf(DataIntegrityViolationException.class);

        BigDecimal balanceAfter = jdbcTemplate.queryForObject(
                "SELECT acct_curr_bal FROM accounts WHERE acct_id = ?", BigDecimal.class, ACCT_ID);
        String lastNameAfter = jdbcTemplate.queryForObject(
                "SELECT cust_last_name FROM customers WHERE cust_id = ?", String.class, CUST_ID);

        assertThat(balanceAfter).isEqualByComparingTo(originalAcctCurrBal);
        assertThat(balanceAfter.scale()).isEqualTo(2);
        assertThat(lastNameAfter).isEqualTo(originalCustLastName);
        assertThat(lastNameAfter).isNotEqualTo(ROLLBACK_MARKER_LAST_NAME);
    }

    /**
     * :purpose: Reinforce that there is no partial commit: the customer, written
     *     first inside the transaction, is not persisted once the subsequent
     *     account write fails. This proves the two writes share one transaction
     *     rather than committing independently.
     */
    @Test
    void firstWriteAppliedThenSecondFails_noPartialCommit() {
        Account account = accountRepository.findById(ACCT_ID).orElseThrow();
        Customer customer = customerRepository.findById(CUST_ID).orElseThrow();

        AccountUpdateRequestDto request = fullRequestFrom(account, customer);
        // Change the first-written entity (customer) and force the second (account) to fail.
        request.setCustLastName(PARTIAL_MARKER_LAST_NAME);
        request.setAcctCurrBal(OVERFLOW_BALANCE);

        assertThatThrownBy(() -> accountService.updateAccount(ACCT_ID, request, null))
                .isInstanceOf(DataIntegrityViolationException.class);

        String lastNameAfter = jdbcTemplate.queryForObject(
                "SELECT cust_last_name FROM customers WHERE cust_id = ?", String.class, CUST_ID);
        BigDecimal balanceAfter = jdbcTemplate.queryForObject(
                "SELECT acct_curr_bal FROM accounts WHERE acct_id = ?", BigDecimal.class, ACCT_ID);

        assertThat(lastNameAfter).isEqualTo(originalCustLastName);
        assertThat(lastNameAfter).isNotEqualTo(PARTIAL_MARKER_LAST_NAME);
        assertThat(balanceAfter).isEqualByComparingTo(originalAcctCurrBal);
    }

    /**
     * :purpose: Control case confirming the failure in the rollback scenarios is
     *     due to the induced fault, not a broken happy path: a fully valid update
     *     commits both the account and the customer, and a fresh JDBC read
     *     reflects both changes.
     */
    @Test
    void successfulUpdate_commitsBoth() {
        Account account = accountRepository.findById(ACCT_ID).orElseThrow();
        Customer customer = customerRepository.findById(CUST_ID).orElseThrow();

        AccountUpdateRequestDto request = fullRequestFrom(account, customer);
        request.setAcctCurrBal(VALID_NEW_BALANCE);
        request.setCustLastName(SUCCESS_MARKER_LAST_NAME);

        AccountUpdateResponseDto response = accountService.updateAccount(ACCT_ID, request, null);
        assertThat(response).isNotNull();

        BigDecimal balanceAfter = jdbcTemplate.queryForObject(
                "SELECT acct_curr_bal FROM accounts WHERE acct_id = ?", BigDecimal.class, ACCT_ID);
        String lastNameAfter = jdbcTemplate.queryForObject(
                "SELECT cust_last_name FROM customers WHERE cust_id = ?", String.class, CUST_ID);

        assertThat(balanceAfter).isEqualByComparingTo(VALID_NEW_BALANCE);
        assertThat(balanceAfter.scale()).isEqualTo(2);
        assertThat(lastNameAfter).isEqualTo(SUCCESS_MARKER_LAST_NAME);
    }

    /**
     * :purpose: Build a complete account update request that mirrors the current
     *     editable account and customer master fields, so a subsequently mutated
     *     single field is the only difference and non-null columns are satisfied.
     *     The PII fields are carried as their (emptied) pass-through values.
     * :param account: the currently persisted account whose editable fields are copied.
     * :param customer: the currently persisted customer whose editable fields are copied.
     * :returns: a fully populated {@link AccountUpdateRequestDto}.
     */
    private AccountUpdateRequestDto fullRequestFrom(Account account, Customer customer) {
        AccountUpdateRequestDto request = new AccountUpdateRequestDto();
        request.setAcctActiveStatus(account.getAcctActiveStatus());
        request.setAcctCurrBal(account.getAcctCurrBal());
        request.setAcctCreditLimit(account.getAcctCreditLimit());
        request.setAcctCashCreditLimit(account.getAcctCashCreditLimit());
        request.setAcctCurrCycCredit(account.getAcctCurrCycCredit());
        request.setAcctCurrCycDebit(account.getAcctCurrCycDebit());
        request.setAcctOpenDate(account.getAcctOpenDate());
        request.setAcctExpiraionDate(account.getAcctExpiraionDate());
        request.setAcctReissueDate(account.getAcctReissueDate());
        request.setAcctGroupId(account.getAcctGroupId());

        request.setCustFirstName(customer.getCustFirstName());
        request.setCustMiddleName(customer.getCustMiddleName());
        request.setCustLastName(customer.getCustLastName());
        request.setCustAddrLine1(customer.getCustAddrLine1());
        request.setCustAddrLine2(customer.getCustAddrLine2());
        request.setCustAddrLine3(customer.getCustAddrLine3());
        request.setCustAddrStateCd(customer.getCustAddrStateCd());
        request.setCustAddrCountryCd(customer.getCustAddrCountryCd());
        request.setCustAddrZip(customer.getCustAddrZip());
        request.setCustPhoneNum1(customer.getCustPhoneNum1());
        request.setCustPhoneNum2(customer.getCustPhoneNum2());
        request.setCustSsn(customer.getCustSsn());
        request.setCustGovtIssuedId(customer.getCustGovtIssuedId());
        request.setCustDobYyyyMmDd(customer.getCustDobYyyyMmDd());
        request.setCustEftAccountId(customer.getCustEftAccountId());
        request.setCustPriCardHolderInd(customer.getCustPriCardHolderInd());
        request.setCustFicoCreditScore(customer.getCustFicoCreditScore());
        return request;
    }
}
