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
package com.aws.carddemo.repository;

import com.aws.carddemo.domain.Account;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Testcontainers-backed Spring Data JPA integration test for
 * {@link AccountRepository} over the {@code account} table.
 *
 * <p>This test re-platforms and verifies the keyed and ordered access the COBOL
 * account programs performed against {@code ACCTDATA.VSAM.KSDS} (copybook
 * {@code legacy/cpy/CVACT01Y.cpy}, record length 300):</p>
 * <ul>
 *   <li><strong>Sequential ascending key browse</strong> &mdash; the account
 *       master-print batch {@code legacy/cbl/CBACT01C.cbl}
 *       ({@code ORGANIZATION IS INDEXED}, {@code ACCESS MODE IS SEQUENTIAL},
 *       {@code RECORD KEY IS FD-ACCT-ID}) and the account iteration in the
 *       interest-calculation batch {@code legacy/cbl/CBACT04C.cbl} read accounts
 *       in primary-key order. Reproduced by
 *       {@link AccountRepository#findAllByOrderByAcctIdAsc()}.</li>
 *   <li><strong>Monetary fidelity</strong> &mdash; the five COBOL {@code COMP-3}
 *       {@code S9(10)V99} money fields ({@code ACCT-CURR-BAL},
 *       {@code ACCT-CREDIT-LIMIT}, {@code ACCT-CASH-CREDIT-LIMIT},
 *       {@code ACCT-CURR-CYC-CREDIT}, {@code ACCT-CURR-CYC-DEBIT}) become
 *       {@link BigDecimal} {@code DECIMAL(12,2)} columns and must round-trip at
 *       scale 2 with no floating-point drift (AAP 0.7.1 H3, 0.9.2).</li>
 *   <li><strong>READ-UPDATE-REWRITE integrity</strong> &mdash; the online update
 *       {@code legacy/cbl/COACTUPC.cbl} and the posting balance update
 *       {@code legacy/cbl/CBTRN02C.cbl} (paragraph {@code 2800-UPDATE-ACCOUNT-REC}:
 *       {@code ADD DALYTRAN-AMT TO ACCT-CURR-BAL} then {@code REWRITE}) are
 *       protected by the entity's {@code @Version} optimistic lock &mdash; a
 *       documented intentional improvement (AAP 0.7.1 H6).</li>
 * </ul>
 *
 * <h2>Harness</h2>
 * <p>The slice runs against a real PostgreSQL 16 provided by Testcontainers and
 * wired into the test datasource by {@link ServiceConnection} (no
 * {@code @DynamicPropertySource}, no hardcoded credentials). Flyway applies the
 * production migrations ({@code V1__schema.sql}, {@code V2__reference_data.sql})
 * and Hibernate runs in {@code validate} mode (see {@code application-test.yml}),
 * so this test also acts as a data-tier parity guard: the {@link Account} mapping
 * &mdash; including the five {@code DECIMAL(12,2)} columns and the
 * {@code version BIGINT NOT NULL} optimistic-lock column &mdash; must match the
 * Flyway-authored DDL exactly.</p>
 *
 * <p>Under {@code @DataJpaTest} the {@code account} table is <em>not</em> seeded,
 * so each test begins with an empty table and rolls back on completion. Because
 * {@code account.group_id} is an index-only scalar column (no foreign key), rows
 * are inserted without any {@code disclosure_group} parent.</p>
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
@Testcontainers
class AccountRepositoryTest {

    /**
     * Real PostgreSQL 16 instance shared by every test in this class.
     * {@link ServiceConnection} publishes its JDBC url/username/password to the
     * Spring test context so the datasource is configured dynamically at runtime.
     */
    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>(DockerImageName.parse("postgres:16-alpine"));

    private final AccountRepository repository;
    private final TestEntityManager entityManager;

    /**
     * Constructor injection of the collaborators under test. {@code @Autowired}
     * marks the constructor autowirable so the Spring TestContext framework
     * resolves both parameters (the project does not set the global
     * {@code spring.test.constructor.autowire.mode=all}).
     *
     * @param repository    the repository under test
     * @param entityManager the JPA test entity manager (used to flush and clear
     *                      the persistence context so reloads hit the database)
     */
    @Autowired
    AccountRepositoryTest(AccountRepository repository, TestEntityManager entityManager) {
        this.repository = repository;
        this.entityManager = entityManager;
    }

    /**
     * Builds a fully-populated transient {@link Account}. The optimistic-lock
     * {@code version} is intentionally left {@code null} (the convenience
     * constructor does not set it) so the JPA provider treats a first
     * {@code save} as an insert. The four non-{@code currBal} money fields carry
     * distinct fixed values so the monetary round-trip test can assert each one
     * independently.
     *
     * @param id      the account identifier (primary key)
     * @param currBal the current balance, as a decimal string for the
     *                {@link BigDecimal#BigDecimal(String) string constructor}
     * @return a new transient account ready to persist
     */
    private Account newAccount(long id, String currBal) {
        return new Account(
                id,                          // acctId            (ACCT-ID PIC 9(11))
                "Y",                         // acctActiveStatus  (ACCT-ACTIVE-STATUS)
                new BigDecimal(currBal),     // currBal           (ACCT-CURR-BAL)
                new BigDecimal("5000.00"),   // creditLimit       (ACCT-CREDIT-LIMIT)
                new BigDecimal("1000.00"),   // cashCreditLimit   (ACCT-CASH-CREDIT-LIMIT)
                "2024-01-01",                // acctOpenDate      (ACCT-OPEN-DATE)
                "2025-12-31",                // acctExpirationDate(ACCT-EXPIRAION-DATE)
                "2024-01-01",                // acctReissueDate   (ACCT-REISSUE-DATE)
                new BigDecimal("250.00"),    // currCycCredit     (ACCT-CURR-CYC-CREDIT)
                new BigDecimal("75.25"),     // currCycDebit      (ACCT-CURR-CYC-DEBIT)
                "12345",                     // acctAddrZip       (ACCT-ADDR-ZIP)
                "A000000000");               // groupId           (ACCT-GROUP-ID; scalar, no FK)
    }

    /**
     * All five monetary fields must survive a persist/reload round-trip with
     * their exact value and a scale of 2, matching the COBOL {@code S9(10)V99}
     * packed-decimal contract. Values are compared with
     * {@code isEqualByComparingTo} (value-based, scale-insensitive) and the scale
     * is asserted separately.
     */
    @Test
    void saveAndFindById_roundTripsMonetaryFieldsAtScale2() {
        repository.saveAndFlush(newAccount(1L, "1234.56"));
        entityManager.clear();

        assertThat(repository.findById(1L)).isPresent();
        Account found = repository.findById(1L).orElseThrow();

        assertThat(found.getCurrBal()).isEqualByComparingTo(new BigDecimal("1234.56"));
        assertThat(found.getCurrBal().scale()).isEqualTo(2);

        assertThat(found.getCreditLimit()).isEqualByComparingTo(new BigDecimal("5000.00"));
        assertThat(found.getCreditLimit().scale()).isEqualTo(2);

        assertThat(found.getCashCreditLimit()).isEqualByComparingTo(new BigDecimal("1000.00"));
        assertThat(found.getCashCreditLimit().scale()).isEqualTo(2);

        assertThat(found.getCurrCycCredit()).isEqualByComparingTo(new BigDecimal("250.00"));
        assertThat(found.getCurrCycCredit().scale()).isEqualTo(2);

        assertThat(found.getCurrCycDebit()).isEqualByComparingTo(new BigDecimal("75.25"));
        assertThat(found.getCurrCycDebit().scale()).isEqualTo(2);
    }

    /**
     * {@link AccountRepository#findAllByOrderByAcctIdAsc()} returns accounts in
     * ascending primary-key order regardless of insertion order, reproducing the
     * {@code CBACT01C} sequential KSDS key browse.
     */
    @Test
    void findAllByOrderByAcctIdAsc_returnsAscending() {
        repository.saveAndFlush(newAccount(30L, "300.00"));
        repository.saveAndFlush(newAccount(10L, "100.00"));
        repository.saveAndFlush(newAccount(20L, "200.00"));
        entityManager.clear();

        List<Account> accounts = repository.findAllByOrderByAcctIdAsc();

        assertThat(accounts)
                .extracting(Account::getAcctId)
                .containsExactly(10L, 20L, 30L);
    }

    /**
     * The {@code @Version} column is assigned on insert and increments on update,
     * providing the optimistic-lock integrity that reproduces the COBOL
     * READ-UPDATE-REWRITE cycle. A flush plus {@code clear} is required after each
     * write so the version increment is materialized and the subsequent reload is
     * served from the database rather than the persistence-context cache.
     */
    @Test
    void versionAssignedOnInsertAndIncrementsOnUpdate() {
        repository.saveAndFlush(newAccount(2L, "100.00"));
        entityManager.clear();

        Account inserted = repository.findById(2L).orElseThrow();
        assertThat(inserted.getVersion()).isNotNull();
        long initialVersion = inserted.getVersion();

        inserted.setCurrBal(new BigDecimal("200.00"));
        repository.saveAndFlush(inserted);
        entityManager.clear();

        Account updated = repository.findById(2L).orElseThrow();
        assertThat(updated.getVersion()).isNotNull();
        long updatedVersion = updated.getVersion();

        assertThat(updatedVersion).isGreaterThan(initialVersion);
    }

    /**
     * {@link AccountRepository#findByIdForVersionedUpdate(Long)} resolves the
     * account by primary key and returns an empty {@link java.util.Optional} for an
     * absent key. This finder is the load step of the online account-update write
     * path ({@code COACTUPC 9600-WRITE-PROCESSING}); it is annotated
     * {@code @Lock(OPTIMISTIC_FORCE_INCREMENT)} so that <em>any</em> confirmed write
     * &mdash; including a customer-only edit that leaves every {@code account} column
     * untouched &mdash; advances {@code account.version}, causing a second,
     * stale-versioned editor of the account-plus-customer aggregate to be rejected
     * ({@code 9700-CHECK-CHANGE-IN-REC}; AAP&nbsp;0.7.1&nbsp;H6; see
     * {@code docs/decision-log.md}).
     *
     * <p><strong>Scope of this slice test.</strong> It verifies only the finder's
     * query semantics (correct row by key, empty for a miss), which is all that is
     * observable inside a {@code @DataJpaTest} slice. The force-increment behaviour
     * itself is a commit-time action ({@code OPTIMISTIC_FORCE_INCREMENT} is emitted
     * during before-transaction-completion, not on an intermediate {@code flush()}),
     * so it cannot be observed in this rollback-per-test slice; it is verified
     * end-to-end against a real commit boundary by
     * {@code com.aws.carddemo.service.AccountServiceTest}.</p>
     */
    @Test
    void findByIdForVersionedUpdateReturnsManagedAccountByKey() {
        repository.saveAndFlush(newAccount(5L, "500.00"));
        entityManager.clear();

        // The @Query resolves the correct row by primary key and maps its scalar state.
        Account forUpdate = repository.findByIdForVersionedUpdate(5L).orElseThrow();
        assertThat(forUpdate.getAcctId()).isEqualTo(5L);
        assertThat(forUpdate.getCurrBal()).isEqualByComparingTo(new BigDecimal("500.00"));
        assertThat(forUpdate.getVersion()).isNotNull();

        // A key that was never persisted yields an empty Optional (keyed-read miss).
        assertThat(repository.findByIdForVersionedUpdate(999L)).isEmpty();
    }

    /**
     * A lookup for an account id that was never persisted returns an empty
     * {@link java.util.Optional}, reproducing the COBOL "record not found"
     * outcome of a keyed read.
     */
    @Test
    void findById_absentId_returnsEmpty() {
        assertThat(repository.findById(9_999_999L)).isEmpty();
    }
}
