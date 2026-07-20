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
package com.aws.carddemo.repository;

import com.aws.carddemo.domain.Account;
import com.aws.carddemo.domain.TransactionCategory;
import com.aws.carddemo.domain.TransactionCategoryBalance;
import com.aws.carddemo.domain.TransactionType;

import org.junit.jupiter.api.Test;
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
 * Testcontainers-backed Spring Data JPA integration tests for
 * {@link TransactionCategoryBalanceRepository} (entity
 * {@link TransactionCategoryBalance}, table {@code tran_cat_balance}).
 *
 * <p>This class verifies that the set-based PostgreSQL&nbsp;16 replacement preserves the
 * record-at-a-time VSAM access the COBOL interest and posting batch programs performed
 * against the {@code TCATBALF} KSDS (copybook {@code legacy/cpy/CVTRA01Y.cpy},
 * {@code TRAN-CAT-BAL-RECORD}, RECLN&nbsp;50). The COBOL {@code TRAN-CAT-KEY} group is the
 * 17-byte compound key {@code TRANCAT-ACCT-ID PIC 9(11)} + {@code TRANCAT-TYPE-CD PIC X(02)}
 * + {@code TRANCAT-CD PIC 9(04)}, and the running balance is
 * {@code TRAN-CAT-BAL PIC S9(09)V99} &rarr; {@code DECIMAL(11,2)}. The behaviours locked in
 * here are exactly the two the legacy programs relied on (AAP&nbsp;0.4.3, 0.7.1&nbsp;H3/H6,
 * 0.9.2):</p>
 * <ul>
 *   <li><strong>Per-account grouped browse</strong> &mdash; the interest-calculation batch
 *       {@code legacy/cbl/CBACT04C.cbl} sequentially reads the {@code TCATBAL-FILE}
 *       ({@code 1000-TCATBALF-GET-NEXT}: {@code READ ... INTO TRAN-CAT-BAL-RECORD}) and
 *       groups rows by {@code TRANCAT-ACCT-ID} to compute monthly interest
 *       ({@code 1300-COMPUTE-INTEREST}: {@code COMPUTE WS-MONTHLY-INT = (TRAN-CAT-BAL *
 *       DIS-INT-RATE) / 1200}). That ascending, account-scoped browse is reproduced by
 *       {@link TransactionCategoryBalanceRepository#findByAccountId(Long)}.</li>
 *   <li><strong>READ-UPDATE-REWRITE</strong> &mdash; the posting batch
 *       {@code legacy/cbl/CBTRN02C.cbl} ({@code 2700-UPDATE-TCATBAL}) reads a single
 *       category-balance row by its full key, {@code ADD DALYTRAN-AMT TO TRAN-CAT-BAL},
 *       and {@code REWRITE}s it. That cycle is reproduced by the inherited
 *       {@code findById} / {@code save}, whose last-writer integrity is protected by the
 *       entity's {@code @Version} optimistic-lock column.</li>
 * </ul>
 *
 * <h2>Harness</h2>
 * <p>The slice runs against a real PostgreSQL&nbsp;16 provisioned by Testcontainers and
 * wired to Spring Boot through {@link ServiceConnection}, so the datasource carries no
 * hardcoded URL or credentials (AAP&nbsp;0.8.1&nbsp;/&nbsp;0.9.3) and no
 * {@code @DynamicPropertySource} is required. {@link AutoConfigureTestDatabase} with
 * {@link AutoConfigureTestDatabase.Replace#NONE} keeps that container datasource in place.
 * Flyway applies the production migrations ({@code V1__schema.sql} then
 * {@code V2__reference_data.sql}) and Hibernate runs in {@code validate} mode (see
 * {@code src/test/resources/application-test.yml}), so this test doubles as a data-tier
 * parity guard: the {@link TransactionCategoryBalance} mapping &mdash; the composite
 * {@code @EmbeddedId}, the {@code bal DECIMAL(11,2)} money column, and the
 * {@code version BIGINT NOT NULL} optimistic-lock column &mdash; must match the
 * Flyway-authored DDL exactly.</p>
 *
 * <p>Both collaborators are supplied by constructor injection with no field
 * {@code @Autowired}; the project sets {@code spring.test.constructor.autowire.mode=all}
 * (see {@code src/test/resources/junit-platform.properties}), so the Spring
 * {@code SpringExtension} autowires the constructor parameters. Under {@code @DataJpaTest}
 * the {@code account} and {@code tran_cat_balance} tables are <em>not</em> seeded (their
 * data lives in {@code db/seed/*.csv}, loaded only by the {@code local} profile), so each
 * test begins with those tables empty and rolls back on completion. The
 * {@code transaction_type} and {@code transaction_category} reference tables are committed
 * by the Flyway {@code V2} seed; the fixtures here deliberately use synthetic,
 * non-colliding codes ({@code "98"}, {@code "99"}) and create their own parents so the
 * class is fully self-contained.</p>
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
@Testcontainers
class TransactionCategoryBalanceRepositoryTest {

    /**
     * Real PostgreSQL&nbsp;16 instance shared by every test in this class. Declared
     * {@code static} so the {@link Testcontainers} extension starts it once for the whole
     * class (before the Spring context is created) and stops it after all tests complete.
     * {@link ServiceConnection} publishes its JDBC url/username/password to the Spring test
     * context, so the datasource is configured dynamically at runtime with no hardcoded
     * credentials.
     */
    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>(DockerImageName.parse("postgres:16-alpine"));

    /** Repository under test. */
    private final TransactionCategoryBalanceRepository repository;

    /** JPA test helper used to persist fixtures and to flush/clear the persistence context. */
    private final TestEntityManager entityManager;

    /**
     * Constructor injection of the collaborators under test (no field {@code @Autowired}).
     * The Spring {@code SpringExtension} resolves both parameters from the
     * {@code @DataJpaTest} slice's application context because the global
     * {@code spring.test.constructor.autowire.mode=all} configuration parameter is set.
     *
     * @param repository    the {@link TransactionCategoryBalanceRepository} under test
     * @param entityManager the JPA {@link TestEntityManager} used to persist parents and to
     *                      flush and clear the persistence context so reloads hit the
     *                      database rather than the first-level cache
     */
    TransactionCategoryBalanceRepositoryTest(TransactionCategoryBalanceRepository repository,
                                             TestEntityManager entityManager) {
        this.repository = repository;
        this.entityManager = entityManager;
    }

    /**
     * Persists a minimal but fully valid {@link Account} parent for the given identifier so
     * the {@code fk_tcb_account} foreign key on {@code tran_cat_balance.acct_id} is
     * satisfied. Every monetary field is initialized with the
     * {@link BigDecimal#BigDecimal(String) string constructor} at scale&nbsp;2 (never a
     * floating-point literal); the optimistic-lock {@code version} is left to the JPA
     * provider.
     *
     * @param id the account identifier (primary key)
     * @return the persisted, managed {@link Account}
     */
    private Account newAccount(long id) {
        Account account = new Account(
                id,                          // acctId             (ACCT-ID PIC 9(11))
                "Y",                         // acctActiveStatus   (ACCT-ACTIVE-STATUS)
                new BigDecimal("0.00"),      // currBal            (ACCT-CURR-BAL)
                new BigDecimal("0.00"),      // creditLimit        (ACCT-CREDIT-LIMIT)
                new BigDecimal("0.00"),      // cashCreditLimit    (ACCT-CASH-CREDIT-LIMIT)
                "2024-01-01",                // acctOpenDate       (ACCT-OPEN-DATE)
                "2025-12-31",                // acctExpirationDate (ACCT-EXPIRAION-DATE)
                "2024-01-01",                // acctReissueDate    (ACCT-REISSUE-DATE)
                new BigDecimal("0.00"),      // currCycCredit      (ACCT-CURR-CYC-CREDIT)
                new BigDecimal("0.00"),      // currCycDebit       (ACCT-CURR-CYC-DEBIT)
                "00000",                     // acctAddrZip        (ACCT-ADDR-ZIP)
                "A000000000");               // groupId            (ACCT-GROUP-ID; scalar, no FK)
        entityManager.persist(account);
        return account;
    }

    /**
     * Ensures the {@link TransactionType} and {@link TransactionCategory} parents required
     * by the {@code fk_tcb_category} foreign key (and its own
     * {@code fk_transaction_category_type} back to {@code transaction_type}) exist for the
     * given {@code (typeCd, catCd)} pair.
     *
     * <p>The type is created idempotently &mdash; only when it is not already present in the
     * persistence context &mdash; because a single account may carry several category
     * balances that share one transaction type (for example {@code ("99", 1000)} and
     * {@code ("99", 2000)}); persisting the same {@code type_cd} twice would violate the
     * {@code transaction_type} primary key. The category, whose composite key
     * {@code (type_cd, cat_cd)} is unique per call, is always persisted.</p>
     *
     * @param typeCd the two-character transaction-type code ({@code TRANCAT-TYPE-CD})
     * @param catCd  the transaction-category code ({@code TRANCAT-CD})
     */
    private void seedTypeAndCategory(String typeCd, int catCd) {
        if (entityManager.find(TransactionType.class, typeCd) == null) {
            entityManager.persist(new TransactionType(typeCd, "TEST TYPE"));
        }
        entityManager.persist(new TransactionCategory(
                new TransactionCategory.TransactionCategoryId(typeCd, catCd), "TEST CAT"));
    }

    /**
     * Builds (without persisting) a transient {@link TransactionCategoryBalance} for the
     * given composite key and balance. The balance uses the
     * {@link BigDecimal#BigDecimal(String) string constructor} to preserve the exact
     * {@code S9(09)V99} decimal semantics; floating-point types are never used.
     *
     * @param acctId the account identifier ({@code TRANCAT-ACCT-ID})
     * @param typeCd the transaction-type code ({@code TRANCAT-TYPE-CD})
     * @param catCd  the transaction-category code ({@code TRANCAT-CD})
     * @param bal    the running balance as a decimal string ({@code TRAN-CAT-BAL})
     * @return a new transient balance ready to persist
     */
    private TransactionCategoryBalance newBalance(long acctId, String typeCd, int catCd, String bal) {
        return new TransactionCategoryBalance(
                new TransactionCategoryBalance.TransactionCategoryBalanceId(acctId, typeCd, catCd),
                new BigDecimal(bal));
    }

    /**
     * Persisting a category balance and re-reading it by its full composite key returns the
     * same row with the money field intact &mdash; the fundamental compound-key parity with
     * the legacy keyed access to {@code TCATBALF}. The balance is asserted with
     * {@code isEqualByComparingTo} (value-based, scale-insensitive) and its scale is
     * asserted separately to prove the {@code DECIMAL(11,2)} contract.
     */
    @Test
    void saveAndFindByCompositeId_roundTripsBalanceAtScale2() {
        newAccount(1L);
        seedTypeAndCategory("99", 9999);
        repository.saveAndFlush(newBalance(1L, "99", 9999, "1234.56"));
        // Detach so the subsequent findById is served from the database, not the
        // persistence-context first-level cache.
        entityManager.clear();

        var found = repository.findById(
                new TransactionCategoryBalance.TransactionCategoryBalanceId(1L, "99", 9999));

        assertThat(found).isPresent();
        TransactionCategoryBalance balance = found.orElseThrow();
        assertThat(balance.getBal()).isEqualByComparingTo(new BigDecimal("1234.56"));
        assertThat(balance.getBal().scale()).isEqualTo(2);
    }

    /**
     * {@link TransactionCategoryBalanceRepository#findByAccountId(Long)} returns only the
     * requested account's category balances, ordered by {@code type_cd} then {@code cat_cd},
     * regardless of insertion order &mdash; reproducing the ascending, account-scoped
     * {@code TCATBALF} browse that {@code CBACT04C} performs to compute interest. A balance
     * for a different account is persisted and must be excluded from the result.
     */
    @Test
    void findByAccountId_returnsRowsOrderedByTypeThenCat() {
        newAccount(1L);
        seedTypeAndCategory("98", 1000);
        seedTypeAndCategory("98", 2000);
        seedTypeAndCategory("99", 1000);
        seedTypeAndCategory("99", 2000);
        newAccount(2L);

        // Insert account-1 balances in scrambled (type, cat) order to prove ORDER BY.
        repository.saveAndFlush(newBalance(1L, "99", 2000, "40.00"));
        repository.saveAndFlush(newBalance(1L, "98", 2000, "20.00"));
        repository.saveAndFlush(newBalance(1L, "99", 1000, "30.00"));
        repository.saveAndFlush(newBalance(1L, "98", 1000, "10.00"));
        // A different account's balance that must NOT appear in findByAccountId(1L).
        repository.saveAndFlush(newBalance(2L, "99", 1000, "99.99"));
        entityManager.clear();

        List<TransactionCategoryBalance> rows = repository.findByAccountId(1L);

        assertThat(rows).hasSize(4);
        assertThat(rows)
                .extracting(row -> row.getId().getTypeCd() + ":" + row.getId().getCatCd())
                .containsExactly("98:1000", "98:2000", "99:1000", "99:2000");
        assertThat(rows)
                .allSatisfy(row -> assertThat(row.getId().getAcctId()).isEqualTo(1L));
    }

    /**
     * Looking up a composite key that was never persisted yields an empty
     * {@link java.util.Optional} &mdash; the caller-visible "record not found" outcome of
     * the legacy keyed {@code READ} on {@code TCATBALF}.
     */
    @Test
    void findById_absentCompositeKey_returnsEmpty() {
        assertThat(repository.findById(
                new TransactionCategoryBalance.TransactionCategoryBalanceId(9_999_999L, "ZZ", 8888)))
                .isEmpty();
    }

    /**
     * The {@code @Version} column is assigned on insert and increments on update, providing
     * the optimistic-lock integrity that reproduces the COBOL READ-UPDATE-REWRITE cycle of
     * {@code CBTRN02C}'s {@code 2700-UPDATE-TCATBAL} ({@code ADD DALYTRAN-AMT TO TRAN-CAT-BAL}
     * then {@code REWRITE}). A flush plus {@code clear} after each write materializes the
     * version increment and forces the subsequent reload to be served from the database. The
     * balance mutation is computed with {@link BigDecimal#add(BigDecimal)} (never
     * floating-point) and the resulting sum is assigned back.
     */
    @Test
    void versionAssignedOnInsertAndIncrementsOnUpdate() {
        newAccount(1L);
        seedTypeAndCategory("99", 9999);
        repository.saveAndFlush(newBalance(1L, "99", 9999, "100.00"));
        entityManager.clear();

        TransactionCategoryBalance.TransactionCategoryBalanceId id =
                new TransactionCategoryBalance.TransactionCategoryBalanceId(1L, "99", 9999);
        TransactionCategoryBalance inserted = repository.findById(id).orElseThrow();
        assertThat(inserted.getVersion()).isNotNull();
        long initialVersion = inserted.getVersion();

        inserted.setBal(inserted.getBal().add(new BigDecimal("10.00")));
        repository.saveAndFlush(inserted);
        entityManager.clear();

        TransactionCategoryBalance updated = repository.findById(id).orElseThrow();
        assertThat(updated.getVersion()).isNotNull();
        assertThat(updated.getVersion()).isGreaterThan(initialVersion);
        assertThat(updated.getBal()).isEqualByComparingTo(new BigDecimal("110.00"));
    }
}
