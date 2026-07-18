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

import com.aws.carddemo.domain.Account;
import com.aws.carddemo.domain.CardXref;
import com.aws.carddemo.domain.Customer;
import com.aws.carddemo.repository.AccountRepository;
import com.aws.carddemo.repository.CardXrefRepository;
import com.aws.carddemo.repository.CustomerRepository;
import com.aws.carddemo.service.AccountService.AccountUpdateCommand;
import com.aws.carddemo.service.AccountService.AccountUpdateResult;
import com.aws.carddemo.service.AccountService.DateParts;
import com.aws.carddemo.service.AccountService.PhoneParts;
import com.aws.carddemo.service.AccountService.SsnParts;
import com.aws.carddemo.service.AccountService.Status;

import java.math.BigDecimal;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

/**
 * Full-context integration tests for {@link AccountService}'s confirmed
 * account-update write path ({@code COACTUPC} / transaction {@code CAUP}),
 * exercised against a real PostgreSQL&nbsp;16 database.
 *
 * <p><strong>What this locks down (QA MAJOR-1).</strong> These tests reproduce
 * the concurrency-parity fix for the online account update: the COBOL
 * {@code 9700-CHECK-CHANGE-IN-REC} paragraph ({@code legacy/cbl/COACTUPC.cbl})
 * re-reads and field-compares <em>both</em> the account and its owning customer
 * record before rewriting, aborting with "Record changed by some one else.
 * Please review" (COACTUPC line&nbsp;522) when either changed since the user
 * fetched the details. In the relational target this is enforced by an
 * optimistic-lock version guard anchored on the account row, which the write
 * path loads with {@code OPTIMISTIC_FORCE_INCREMENT}
 * ({@link com.aws.carddemo.repository.AccountRepository#findByIdForVersionedUpdate(Long)})
 * so that <em>any</em> confirmed write &mdash; including a customer-only edit that
 * leaves every account column untouched &mdash; advances the account version and
 * causes a subsequent stale-versioned editor to be rejected. Both the account
 * and the customer additionally carry a JPA {@code @Version} column (AAP&nbsp;0.7.1&nbsp;H6;
 * documented in {@code docs/decision-log.md}).</p>
 *
 * <h2>Why {@link SpringBootTest} and not a {@code @DataJpaTest} slice</h2>
 * <p>{@code OPTIMISTIC_FORCE_INCREMENT} schedules its version bump as a
 * before-transaction-completion action &mdash; it is emitted when the transaction
 * <strong>commits</strong>, not on an intermediate {@code EntityManager.flush()}.
 * A {@code @DataJpaTest} slice wraps each test in a single transaction that rolls
 * back, so the forced increment is never observable there. This test therefore
 * boots the full application context and drives the <em>real</em>
 * {@link AccountService} Spring bean, whose {@code @Transactional} method commits
 * on return &mdash; exactly the per-request commit boundary that exists in
 * production. Two sequential online requests are modelled as two separate
 * {@code updateAccount} invocations; the first commits (firing the forced
 * increment) before the second runs, precisely reproducing two users who both
 * opened the edit screen at the same version.</p>
 *
 * <p>The datasource is supplied dynamically from an ephemeral
 * {@code postgres:16-alpine} container via {@link DynamicPropertySource} (no
 * hardcoded credentials; AAP&nbsp;0.8.1 / 0.9.3), mirroring
 * {@code CardDemoApplicationTests}. Flyway applies the production migrations
 * ({@code V1}, {@code V2}, {@code V3__add_customer_version.sql}) and Hibernate
 * runs in {@code validate} mode. Because the test itself is not transactional,
 * every repository {@code save}/{@code delete} and every service call commits;
 * the seeded rows are removed in {@link #cleanUp()} after each test.</p>
 */
@SpringBootTest
@ActiveProfiles("test")
@Testcontainers
@DisplayName("AccountService — COACTUPC account-update optimistic-lock concurrency parity")
class AccountServiceTest {

    /** Account identifier seeded and edited by every test. */
    private static final long ACCT_ID = 1L;

    /** Owning customer identifier, linked to {@link #ACCT_ID} through the cross-reference. */
    private static final long CUST_ID = 1L;

    /** Card number of the cross-reference row that links the customer to the account. */
    private static final String CARD_NUM = "4000000000000001";

    /**
     * Shared, single-instance PostgreSQL 16 container backing the integration context.
     * Declared {@code static} so Testcontainers starts it once for the class and reuses it.
     */
    @Container
    static PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>(DockerImageName.parse("postgres:16-alpine"));

    /**
     * Binds the Spring datasource to the running Testcontainers PostgreSQL instance so no
     * connection string or credential is ever hardcoded (the values are resolved lazily from the
     * live container after it starts).
     *
     * @param registry the Spring test property registry to populate with the container coordinates
     */
    @DynamicPropertySource
    static void registerDataSourceProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    private final AccountService service;
    private final AccountRepository accountRepository;
    private final CustomerRepository customerRepository;
    private final CardXrefRepository cardXrefRepository;

    /**
     * Constructor injection of the real {@link AccountService} bean and the repositories used to
     * seed fixtures and read back committed state.
     *
     * @param service            the real, transactionally-managed service under test
     * @param accountRepository  the account repository (seeding + version read-back)
     * @param customerRepository the customer repository (seeding + read-back)
     * @param cardXrefRepository the card cross-reference repository (seeding + cleanup)
     */
    @Autowired
    AccountServiceTest(AccountService service,
                       AccountRepository accountRepository,
                       CustomerRepository customerRepository,
                       CardXrefRepository cardXrefRepository) {
        this.service = service;
        this.accountRepository = accountRepository;
        this.customerRepository = customerRepository;
        this.cardXrefRepository = cardXrefRepository;
    }

    /**
     * Removes the rows seeded by each test. The test is non-transactional, so every service call
     * committed real data; deletes run child-first to respect the {@code card_xref} foreign keys.
     * The {@code test} profile does not run {@code LocalSeedDataLoader}, so these three tables hold
     * only what a test seeded.
     */
    @AfterEach
    void cleanUp() {
        cardXrefRepository.deleteAll();
        accountRepository.deleteAll();
        customerRepository.deleteAll();
    }

    // ------------------------------------------------------------------------
    // Fixture helpers (parents committed before the cross-reference; card_xref
    // has real NOT NULL foreign keys to both customer and account).
    // ------------------------------------------------------------------------

    /** Commits the seeded account (version starts at 0 on insert), customer, and cross-reference. */
    private void seedAccountCustomerAndXref(String customerLastName) {
        Customer customer = new Customer(
                CUST_ID,        // custId (primary key)
                "Grace",        // custFirstName
                "Brewster",     // custMiddleName
                customerLastName, // custLastName
                "1 Navy Yard",  // custAddrLine1
                null,           // custAddrLine2
                "Washington",   // custAddrLine3 (city)
                "DC",           // custAddrStateCd
                "USA",          // custAddrCountryCd
                "20374",        // custAddrZip
                null,           // custPhoneNum1
                null,           // custPhoneNum2
                null,           // custSsn (sensitive; omitted)
                null,           // custGovtIssuedId (sensitive; omitted)
                null,           // custDob (sensitive; omitted)
                "1234567890",   // custEftAccountId
                "Y",            // custPriCardHolderInd
                700);           // custFicoCreditScore
        customerRepository.save(customer);

        Account account = new Account(
                ACCT_ID,                    // acctId (primary key)
                "Y",                        // acctActiveStatus
                new BigDecimal("1234.56"),  // currBal
                new BigDecimal("5000.00"),  // creditLimit
                new BigDecimal("1000.00"),  // cashCreditLimit
                "2020-01-15",               // acctOpenDate
                "2030-12-31",               // acctExpirationDate
                "2020-06-01",               // acctReissueDate
                new BigDecimal("250.00"),   // currCycCredit
                new BigDecimal("75.25"),    // currCycDebit
                "20374",                    // acctAddrZip
                "A000000000");              // groupId (scalar; no FK)
        accountRepository.save(account);

        cardXrefRepository.save(new CardXref(CARD_NUM, CUST_ID, ACCT_ID));
    }

    /**
     * Builds a fully-valid confirmed account-update command whose only difference
     * from the seeded state is the customer last name. {@code priorStatus} is
     * {@code CHANGES_OK_NOT_CONFIRMED} with {@code confirmSave=true} so
     * {@link AccountService#updateAccount(AccountUpdateCommand, boolean)} (called
     * with {@code reentry=true}) routes straight to the write path
     * ({@code 9600-WRITE-PROCESSING}); every field is populated so the defensive
     * {@code 1200-EDIT-MAP-INPUTS} re-edit passes. The account fields equal the
     * seeded values, so the account entity is not dirtied &mdash; any advance of the
     * account version is therefore due solely to the force-increment.
     *
     * @param expectedVersion the account version the editor observed at fetch time
     * @param lastName        the (alphabetic) customer last name to write
     * @return a valid, confirmed customer-only edit command
     */
    private static AccountUpdateCommand customerEditCommand(Long expectedVersion, String lastName) {
        return new AccountUpdateCommand(
                String.valueOf(ACCT_ID),            // accountId
                expectedVersion,                    // expectedVersion (fetched @Version)
                "Y",                                // accountStatus
                new DateParts("2020", "01", "15"),  // openDate
                "5000.00",                          // creditLimit
                new DateParts("2030", "12", "31"),  // expiryDate
                "1000.00",                          // cashCreditLimit
                new DateParts("2020", "06", "01"),  // reissueDate
                "1234.56",                          // currentBalance
                "250.00",                           // currentCycleCredit
                "75.25",                            // currentCycleDebit
                "A000000000",                       // groupId
                new SsnParts("123", "45", "6789"),  // ssn
                new DateParts("1980", "05", "20"),  // dob (past date)
                "700",                              // ficoScore (300-850)
                "Grace",                            // firstName
                "Brewster",                         // middleName
                lastName,                           // lastName (the customer-only change)
                "1 Navy Yard",                      // addressLine1
                "",                                 // addressLine2 (optional; not edited)
                "Washington",                       // city -> custAddrLine3 (alphabetic)
                "DC",                               // stateCode (valid USPS code)
                "20374",                            // zipCode (numeric, DC prefix)
                "USA",                              // countryCode (alphabetic)
                new PhoneParts("", "", ""),         // phone1 (blank area+prefix -> skipped)
                new PhoneParts("", "", ""),         // phone2 (skipped)
                "GID1",                             // governmentId (not edited)
                "1234567890",                       // eftAccountId (numeric, non-zero)
                "Y",                                // primaryHolderFlag
                Status.CHANGES_OK_NOT_CONFIRMED,    // priorStatus -> write path
                true,                               // confirmSave (PF05)
                false);                             // cancel
    }

    // ------------------------------------------------------------------------
    // Tests
    // ------------------------------------------------------------------------

    /**
     * A confirmed customer-only edit succeeds and advances the account's
     * optimistic-lock version even though no account column changes. Because the
     * command's account fields equal the seeded values, the account entity is not
     * dirty, so the version advance is caused solely by the force-increment that
     * anchors the account-plus-customer aggregate guard.
     */
    @Test
    @DisplayName("confirmed customer-only edit succeeds and force-increments the account version")
    void confirmedCustomerOnlyEditForceIncrementsAccountVersion() {
        seedAccountCustomerAndXref("OldLast");

        long initialVersion = accountRepository.findById(ACCT_ID).orElseThrow().getVersion();

        AccountUpdateResult result =
                service.updateAccount(customerEditCommand(initialVersion, "Newname"), true);

        assertThat(result.status()).isEqualTo(Status.DONE);

        long afterVersion = accountRepository.findById(ACCT_ID).orElseThrow().getVersion();
        assertThat(afterVersion)
                .as("a confirmed customer-only edit must still advance the account version")
                .isGreaterThan(initialVersion);

        assertThat(customerRepository.findById(CUST_ID).orElseThrow().getCustLastName())
                .isEqualTo("Newname");
    }

    /**
     * The MAJOR-1 concurrency reproduction: after a first editor confirms a
     * customer-only edit (advancing the account version at commit), a second editor
     * who still holds the now-stale version and confirms another customer-only edit
     * is rejected with the COBOL {@code 9700-CHECK-CHANGE-IN-REC} message. The two
     * sequential online requests are two separate committed service calls.
     */
    @Test
    @DisplayName("stale second editor after a prior customer-only edit is rejected (9700-CHECK-CHANGE-IN-REC)")
    void staleSecondEditorAfterCustomerOnlyEditIsRejected() {
        seedAccountCustomerAndXref("OldLast");

        long fetchedVersion = accountRepository.findById(ACCT_ID).orElseThrow().getVersion();

        // Request A: first editor confirms a customer-only edit with the fetched version -> commits.
        AccountUpdateResult first =
                service.updateAccount(customerEditCommand(fetchedVersion, "FirstEditor"), true);
        assertThat(first.status()).isEqualTo(Status.DONE);

        // The commit fired the force-increment, so the persistent account version has advanced.
        assertThat(accountRepository.findById(ACCT_ID).orElseThrow().getVersion())
                .isGreaterThan(fetchedVersion);

        // Request B: second editor still holds the stale version -> the aggregate guard rejects it,
        // exactly as COACTUPC 9700-CHECK-CHANGE-IN-REC aborts the update.
        assertThatThrownBy(() ->
                service.updateAccount(customerEditCommand(fetchedVersion, "SecondEditor"), true))
                .isInstanceOf(OptimisticLockingFailureException.class)
                .hasMessage(AccountService.MSG_DATA_CHANGED);

        // The rejected edit was rolled back: the customer still shows the first editor's value.
        assertThat(customerRepository.findById(CUST_ID).orElseThrow().getCustLastName())
                .isEqualTo("FirstEditor");
    }
}
