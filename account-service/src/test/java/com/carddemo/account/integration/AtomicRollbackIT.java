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
import com.carddemo.common.domain.CardXref;

import java.math.BigDecimal;
import java.util.Base64;
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
 *     PostgreSQL instance provisioned and seeded by the shared Flyway migration set. The
 *     second-write failure is induced with a genuine database constraint
 *     violation (a monetary value exceeding ``NUMERIC(12,2)`` on the account),
 *     and the post-failure state is asserted through cache-bypassing JDBC reads
 *     so a stale JPA first-level cache cannot mask a partial commit.
 */
@SpringBootTest
@ActiveProfiles("test")
public class AtomicRollbackIT {

    static {
        // The customer PII columns are encrypted at rest by a JPA AttributeConverter
        // that resolves its AES-256 key from the ``carddemo.pii.key`` system property and
        // fails closed when none is configured. The ``test`` profile configures no key, so
        // a throwaway all-zero test key (never a real secret) is installed before any
        // entity conversion runs, exactly as OptimisticLockConflictIT does.
        System.setProperty("carddemo.pii.key", Base64.getEncoder().encodeToString(new byte[32]));
    }

    /**
     * :purpose: Bind the running container's JDBC coordinates to the Spring datasource. Schema
     *     provisioning needs no test-side handling: the ``test`` profile enables Flyway against
     *     the shared production migration set in carddemo-common
     *     (``classpath:db/migration``), which creates every table the account-service
     *     {@code @EntityScan("com.carddemo.common.domain")} registers - including the
     *     ``card_xref`` linkage used below - and seeds them, so the context boots under the
     *     production ``ddl-auto: validate`` with no schema redefined in the test tree.
     * :param registry: the dynamic property registry populated before the
     *     application context starts.
     */
    @DynamicPropertySource
    static void datasourceProperties(DynamicPropertyRegistry registry) {
        MigratedSchemaContainer.registerDataSource(registry);
    }

    /** :purpose: Account group id rejected by the temporary probe constraint. */
    private static final String REJECTED_GROUP_ID = "ROLLBK";

    /** :purpose: Name of the temporary CHECK constraint used to force the write to fail. */
    private static final String ROLLBACK_PROBE_CONSTRAINT = "tmp_atomic_rollback_probe";

    /** :purpose: Card number of the cross-reference row written by the rollback probe. */
    private static final String TEST_CARD_NUM = "4000000000000004";

    /** :purpose: Seeded account identifier under test (``ACCT-ID PIC 9(11)``). */
    private static final Long ACCT_ID = 4L;

    /** :purpose: Seeded customer identifier linked to {@link #ACCT_ID} through the cross-reference (``CUST-ID PIC 9(09)``). */
    private static final Long CUST_ID = 4L;



    /** :purpose: A social security number that satisfies ``1265-EDIT-US-SSN``. */
    private static final String VALID_SSN = "020973888";

    /** :purpose: A government issued id that fits the column width. */
    private static final String VALID_GOVT_ID = "00000000000049368437";

    /** :purpose: An EFT account id that satisfies ``1245-EDIT-NUM-REQD``. */
    private static final String VALID_EFT_ACCOUNT_ID = "0053581756";

    /** :purpose: A FICO score inside the ``1275-EDIT-FICO-SCORE`` 300-850 range. */
    private static final int VALID_FICO_SCORE = 700;

    /** :purpose: A zip forming a known combination with the seeded ``MI`` state code. */
    private static final String VALID_ZIP = "48035";

    /** :purpose: Phone numbers carrying North America general purpose area codes. */
    private static final String VALID_PHONE_1 = "(801)603-4121";
    private static final String VALID_PHONE_2 = "(908)074-6837";

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
     * :purpose: Prepare the fixture before each test: capture the seeded account and customer
     *     originals and seed the account-to-customer cross-reference linkage so the update flow
     *     reaches the write step rather than short-circuiting on a missing reference.
     * :note: The migrated ``card_xref`` table carries real foreign keys to ``accounts`` and
     *     ``customers``, so the linkage is seeded against the seeded rows. The customer PII
     *     columns hold genuine AES-256-GCM tokens written by the migration set's version-4 Java
     *     migration under the test key, so the encrypted-attribute converter reads them directly
     *     and the captured originals are restored verbatim on teardown.
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

        // A database-level rejection of one specific, in-width group id. The induced failure
        // must occur on the ACCOUNT write inside the service transaction, which is precisely
        // what these cases assert rolls the customer write back with it; the constraint is
        // dropped again in tearDown so it never leaks into another class.
        jdbcTemplate.execute("ALTER TABLE accounts ADD CONSTRAINT " + ROLLBACK_PROBE_CONSTRAINT
                + " CHECK (acct_group_id IS NULL OR acct_group_id <> '" + REJECTED_GROUP_ID + "')");
        cardXrefRepository.deleteAll();
        cardXrefRepository.save(new CardXref(TEST_CARD_NUM, CUST_ID, ACCT_ID));
        assertThat(cardXrefRepository.findFirstByXrefAcctIdOrderByXrefCardNumAsc(ACCT_ID)).isPresent();
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
     *     induced failure is a database CHECK constraint that rejects one specific,
     *     in-width account group id on flush, so the ACCOUNT write is the failing write
     *     and the service-layer edits all pass; the post-failure account balance and
     *     customer last name are re-read with a fresh JDBC query and must both equal
     *     their originals.
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
        // Carry the account's current optimistic-lock version, as a client echoes back the
        // snapshot it read; the server compares it before rewriting either record.
        request.setVersion(account.getVersion());
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
        // The seeded zip (ZIP+4 whose leading digits are not a known MI combination) and the
        // seeded second phone area code are not accepted by the CSLKPCDY lookup tables the
        // legacy edits consult, so screen-valid values are supplied instead.
        request.setCustAddrZip(VALID_ZIP);
        request.setCustPhoneNum1(VALID_PHONE_1);
        request.setCustPhoneNum2(VALID_PHONE_2);
        // The persisted PII columns are blanked by setUp so the record hydrates without a
        // decryptable value; the COACTUPC edits require real values, which an operator would
        // supply on the screen. The seeded FICO score (274) is likewise outside the legacy
        // 300-850 range enforced by 1275-EDIT-FICO-SCORE.
        request.setCustSsn(VALID_SSN);
        request.setCustGovtIssuedId(VALID_GOVT_ID);
        request.setCustDobYyyyMmDd(customer.getCustDobYyyyMmDd());
        request.setCustEftAccountId(VALID_EFT_ACCOUNT_ID);
        request.setCustPriCardHolderInd(customer.getCustPriCardHolderInd());
        request.setCustFicoCreditScore(VALID_FICO_SCORE);
        return request;
    }
}
