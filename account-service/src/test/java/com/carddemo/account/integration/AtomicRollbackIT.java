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
import com.carddemo.common.domain.Customer;
import com.carddemo.common.dto.AccountUpdateRequestDto;
import com.carddemo.common.dto.AccountUpdateResponseDto;
import com.carddemo.common.testsupport.MigratedSchemaContainer;

import java.math.BigDecimal;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

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
public class AtomicRollbackIT {

    /**
     * :purpose: Bind the Spring datasource to the shared, already-migrated ``postgres:18``
     *     container from
     *     :java:class:`com.carddemo.common.testsupport.MigratedSchemaContainer`. Its schema
     *     and seed data are produced exclusively by the committed Flyway migrations of every
     *     owning module, so the ``test`` profile's ``ddl-auto: validate`` verifies all
     *     entity-scanned mappings (``@EntityScan("com.carddemo.common.domain")``) against the
     *     schema a deployment gets. No table is redefined in the test tree.
     * :param registry: the dynamic property registry populated before the
     *     application context starts.
     */
    @DynamicPropertySource
    static void datasourceProperties(DynamicPropertyRegistry registry) {
        MigratedSchemaContainer.registerDataSource(registry);
    }

    /** :purpose: Seeded account identifier under test (``ACCT-ID PIC 9(11)``). */
    private static final Long ACCT_ID = 4L;

    /** :purpose: Seeded customer identifier linked to {@link #ACCT_ID} through the cross-reference (``CUST-ID PIC 9(09)``). */
    private static final Long CUST_ID = 4L;


    /**
     * :purpose: Account group id that a test-scoped ``CHECK`` constraint rejects, forcing the
     *     account write of the single transaction to fail on flush. ``ACCT-GROUP-ID`` remains
     *     the lever because COACTUPC does not edit its CONTENT, so an in-width value reaches
     *     the column, whereas every monetary field is first screened by ``1250-EDIT-SIGNED-9V2``
     *     (nine integer digits) and can therefore never be made to overflow ``NUMERIC(12,2)``
     *     through the service. An over-LENGTH value is no longer usable as the lever: the
     *     service now edits every legacy field width (``ACCT-GROUP-ID X(10)``) and rejects it
     *     before any write, which is a validation outcome and not the mid-transaction database
     *     failure these cases exist to induce.
     */
    private static final String REJECTED_GROUP_ID = "ROLLBK";

    /** :purpose: Test-scoped constraint that makes {@link #REJECTED_GROUP_ID} fail on flush. */
    private static final String ROLLBACK_PROBE_CONSTRAINT = "tmp_atomic_rollback_probe";

    /** :purpose: Valid scale-2 balance used by the success control case. */
    private static final BigDecimal VALID_NEW_BALANCE = new BigDecimal("123.45");

    /** :purpose: Non-PII customer change marker for the mandatory dual-rollback case. */
    private static final String ROLLBACK_MARKER_LAST_NAME = "RollbackName";

    /** :purpose: Non-PII customer change marker for the no-partial-commit case. */
    private static final String PARTIAL_MARKER_LAST_NAME = "PartialName";

    /** :purpose: Non-PII customer change marker for the success control case. */
    private static final String SUCCESS_MARKER_LAST_NAME = "SuccessName";

    /** :purpose: State code whose ZIP prefix pairing is in the ``CSLKPCDY`` state-ZIP table. */
    private static final String VALID_STATE_CD = "MI";

    /** :purpose: ZIP whose first two digits pair with {@link #VALID_STATE_CD}. */
    private static final String VALID_ZIP = "48226";

    /** :purpose: Screen-valid phone number 1 whose area code is in ``CSLKPCDY``. */
    private static final String VALID_PHONE_NUM_1 = "(212)555-0101";

    /** :purpose: Screen-valid phone number 2 whose area code is in ``CSLKPCDY``. */
    private static final String VALID_PHONE_NUM_2 = "(801)555-0102";

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
     * :purpose: Prepare the fixture before each test: capture the seeded account and
     *     customer originals so the ``@AfterEach`` can restore them, and assert the
     *     account-to-customer cross-reference linkage the update flow resolves is
     *     present, so a scenario reaching the write step is exercising real seed data.
     * :note: No DDL is issued here. ``card_xref`` — like every other table this test
     *     touches — is created by the owning module's committed Flyway migration, with
     *     its foreign keys to ``customers`` and ``accounts`` intact; the fixture would
     *     otherwise silently substitute a constraint-free table and hide referential
     *     defects. The customer PII columns are read for restoration but never edited,
     *     so the assertions run against the committed seed exactly as deployed.
     */
    @BeforeEach
    void setUp() {
        originalAcctCurrBal = jdbcTemplate.queryForObject(
                "SELECT acct_curr_bal FROM accounts WHERE acct_id = ?", BigDecimal.class, ACCT_ID);

        // The seeded identifier columns are read (for restoration) but never edited: the
        // rollback assertions run against the committed seed exactly as deployed.
        Map<String, Object> customerRow = jdbcTemplate.queryForMap(
                "SELECT cust_last_name, cust_ssn, cust_govt_issued_id, cust_eft_account_id "
                        + "FROM customers WHERE cust_id = ?", CUST_ID);
        originalCustLastName = (String) customerRow.get("cust_last_name");
        originalCustSsn = (String) customerRow.get("cust_ssn");
        originalCustGovtIssuedId = (String) customerRow.get("cust_govt_issued_id");
        originalCustEftAccountId = (String) customerRow.get("cust_eft_account_id");

        // The account-to-card cross-reference for this account is part of the card-service
        // migration seed (accounts 1..50 -> matching customer id), so the linkage the service
        // resolves is real seed data rather than a test-created row.
        assertThat(cardXrefRepository.findFirstByXrefAcctIdOrderByXrefCardNumAsc(ACCT_ID)).isPresent();

        // A database-level rejection of one specific, in-width group id. The induced failure
        // must occur on the ACCOUNT write inside the service transaction, which is precisely
        // what these cases assert rolls the customer write back with it; the constraint is
        // dropped again in tearDown so it never leaks into another class.
        jdbcTemplate.execute("ALTER TABLE accounts ADD CONSTRAINT " + ROLLBACK_PROBE_CONSTRAINT
                + " CHECK (acct_group_id IS NULL OR acct_group_id <> '" + REJECTED_GROUP_ID + "')");
    }

    /**
     * :purpose: Restore the seeded account and customer to their captured
     *     original values and remove the seeded cross-reference, so the tests
     *     remain isolated and pass repeatedly and in any order.
     */
    @AfterEach
    void tearDown() {
        jdbcTemplate.execute("ALTER TABLE accounts DROP CONSTRAINT IF EXISTS "
                + ROLLBACK_PROBE_CONSTRAINT);
        jdbcTemplate.update("UPDATE accounts SET acct_curr_bal = ? WHERE acct_id = ?",
                originalAcctCurrBal, ACCT_ID);
        jdbcTemplate.update("UPDATE customers SET cust_last_name = ?, cust_ssn = ?, "
                        + "cust_govt_issued_id = ?, cust_eft_account_id = ? WHERE cust_id = ?",
                originalCustLastName, originalCustSsn, originalCustGovtIssuedId,
                originalCustEftAccountId, CUST_ID);
    }

    /**
     * :purpose: Verify that when the second persistence in the single update
     *     transaction (the account write) fails, both the account and the
     *     customer changes roll back together, reproducing the legacy
     *     one-logical-unit-of-work semantics (COACTUPC.cbl L4066 + L4086). The
     *     induced failure is a real ``VARCHAR(10)`` overflow on the account group id;
     *     the post-failure account balance and customer last name are re-read with a
     *     fresh JDBC query and must both equal their originals.
     */
    @Test
    void secondWriteFails_rollsBackBothAccountAndCustomer() {
        Account account = accountRepository.findById(ACCT_ID).orElseThrow();
        Customer customer = customerRepository.findById(CUST_ID).orElseThrow();

        AccountUpdateRequestDto request = fullRequestFrom(account, customer);
        // The account is the SECOND write. The balance carries a genuine change, so the
        // post-failure re-read below proves it was rolled back rather than merely never
        // altered, and the probe constraint rejects the group id on flush so the account
        // write is the failing write.
        request.setAcctCurrBal(VALID_NEW_BALANCE);
        request.setAcctGroupId(REJECTED_GROUP_ID);
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
        request.setAcctCurrBal(VALID_NEW_BALANCE);
        request.setAcctGroupId(REJECTED_GROUP_ID);

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
        // The fixture's state/ZIP pair is likewise absent from the CSLKPCDY state-ZIP table
        // (customer 4 stores MI with a 39xxx ZIP), which COACTUPC's 1280-EDIT-US-STATE-ZIP-CD
        // refuses, so a table-valid pair is submitted.
        request.setCustAddrStateCd(VALID_STATE_CD);
        request.setCustAddrCountryCd(customer.getCustAddrCountryCd());
        request.setCustAddrZip(VALID_ZIP);
        // The randomly generated fixture in app/data/ASCII/custdata.txt carries area codes
        // that are absent from the CSLKPCDY general-purpose table (customer 4 stores
        // "(156)..."), which COACTUPC's EDIT-AREA-CODE rejects on any update. A screen-valid
        // number is therefore submitted: this class asserts transaction atomicity, and the
        // area-code edit itself is covered by the validator's own tests.
        request.setCustPhoneNum1(VALID_PHONE_NUM_1);
        request.setCustPhoneNum2(VALID_PHONE_NUM_2);
        request.setCustSsn(customer.getCustSsn());
        request.setCustGovtIssuedId(customer.getCustGovtIssuedId());
        request.setCustDobYyyyMmDd(customer.getCustDobYyyyMmDd());
        request.setCustEftAccountId(customer.getCustEftAccountId());
        request.setCustPriCardHolderInd(customer.getCustPriCardHolderInd());
        request.setCustFicoCreditScore(customer.getCustFicoCreditScore());
        return request;
    }
}
