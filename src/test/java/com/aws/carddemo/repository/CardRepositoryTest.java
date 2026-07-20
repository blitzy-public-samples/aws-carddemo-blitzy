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
import com.aws.carddemo.domain.Card;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestConstructor;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Testcontainers-backed Spring Data JPA integration tests for
 * {@link CardRepository} (entity {@link Card}, table {@code card}).
 *
 * <p>This test is <strong>alternate-index browse-critical</strong>. It proves
 * that the legacy {@code CARDDATA.VSAM.AIX} card-by-account browse &mdash; used
 * by {@code legacy/cbl/COCRDLIC.cbl}, which issues
 * {@code EXEC CICS STARTBR RIDFLD(WS-CARD-RID-CARDNUM)} followed by forward
 * {@code READNEXT} (and {@code READPREV} for backward paging) over
 * {@code CARDDATA} filtered to one account &mdash; is faithfully re-expressed as
 * a PostgreSQL B-tree index ({@code idx_card_acct_id}) plus a sorted/paged
 * repository query that preserves <em>card-number key ordering within an
 * account</em> (AAP &sect;0.4.3, &sect;0.7.1 H5, &sect;0.9.2). It also proves the
 * global ascending master browse of {@code legacy/cbl/CBACT02C.cbl}
 * (sequential {@code READ CARDFILE} to end-of-file), the {@code @Version}
 * optimistic-lock behaviour that reproduces the COBOL READ-UPDATE-REWRITE
 * integrity, the newly-formalized foreign key from {@code card.acct_id} to
 * {@code account(acct_id)}, and that the sensitive CVV never leaks through
 * {@link Card#toString()} (AAP &sect;0.9.3).</p>
 *
 * <h2>Harness</h2>
 * <ul>
 *   <li>{@link DataJpaTest} boots only the JPA slice and wraps every test method
 *       in a transaction that is rolled back afterwards, so the tests are fully
 *       isolated and the {@code card}/{@code account} tables start empty.</li>
 *   <li>{@link AutoConfigureTestDatabase} with
 *       {@link AutoConfigureTestDatabase.Replace#NONE} keeps the real datasource
 *       (the Testcontainers PostgreSQL below) rather than substituting an
 *       embedded database, so the migration and mappings are validated against
 *       the production database engine.</li>
 *   <li>A single static {@link PostgreSQLContainer} (image
 *       {@code postgres:16-alpine}) is wired into Spring Boot by
 *       {@link ServiceConnection}, so no JDBC URL, username or password is ever
 *       hardcoded (AAP &sect;0.8.1 / &sect;0.9.3).</li>
 *   <li>The {@code test} profile ({@code application-test.yml}) runs Flyway
 *       ({@code V1__schema.sql} + {@code V2__reference_data.sql}) to create the
 *       schema and then has Hibernate <em>validate</em> the {@link Card} and
 *       {@link Account} mappings (including {@code idx_card_acct_id} and the
 *       {@code fk_card_account} foreign key) &mdash; it never mutates the
 *       schema.</li>
 *   <li>{@link TestConstructor} with
 *       {@link TestConstructor.AutowireMode#ALL} lets the Spring TestContext
 *       autowire the repository and {@link TestEntityManager} through the
 *       constructor without per-parameter {@code @Autowired} annotations.</li>
 * </ul>
 *
 * <p><strong>Foreign-key ordering.</strong> {@code card.acct_id} is a real
 * {@code NOT NULL} foreign key, so every persisted {@link Card} must have a
 * pre-existing parent {@link Account}. Each test that inserts cards therefore
 * persists the owning account(s) <em>first</em> via {@link #newAccount(long)}.</p>
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
@Testcontainers
@TestConstructor(autowireMode = TestConstructor.AutowireMode.ALL)
class CardRepositoryTest {

    /**
     * Real PostgreSQL 16 engine for the integration tests. It is
     * {@code static} so the JUnit {@link Testcontainers} extension starts it
     * once for the whole class before the Spring context is created, and
     * {@link ServiceConnection} publishes its connection details to Spring Boot
     * so the datasource is configured with no hardcoded credentials.
     */
    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>(DockerImageName.parse("postgres:16-alpine"));

    /** Repository under test. */
    private final CardRepository repository;

    /**
     * JPA test helper used to persist parent {@link Account} rows and control
     * the persistence context (flush/clear) so reads exercise the database
     * rather than the first-level cache.
     */
    private final TestEntityManager entityManager;

    /**
     * Constructor injection of the collaborators managed by the Spring
     * TestContext (enabled by {@link TestConstructor}).
     *
     * @param repository    the {@link CardRepository} under test
     * @param entityManager the JPA test entity manager
     */
    CardRepositoryTest(CardRepository repository, TestEntityManager entityManager) {
        this.repository = repository;
        this.entityManager = entityManager;
    }

    // -------------------------------------------------------------------------
    // Fixture helpers
    // -------------------------------------------------------------------------

    /**
     * Builds and persists a parent {@link Account} so that child {@link Card}
     * rows referencing it satisfy the {@code fk_card_account} foreign key.
     *
     * <p>All five monetary fields are seeded with {@code BigDecimal} values
     * constructed from strings (never {@code double}/{@code float}) at scale 2,
     * matching the {@code DECIMAL(12,2)} columns.</p>
     *
     * @param id the account identifier (primary key)
     * @return the persisted, flushed {@link Account}
     */
    private Account newAccount(long id) {
        Account account = new Account(
                id,
                "Y",
                new BigDecimal("0.00"),
                new BigDecimal("0.00"),
                new BigDecimal("0.00"),
                "2020-01-01",
                "2027-12-31",
                "2020-01-01",
                new BigDecimal("0.00"),
                new BigDecimal("0.00"),
                "12345",
                "A000000000");
        return entityManager.persistAndFlush(account);
    }

    /**
     * Builds an unpersisted {@link Card} for the given account. The caller is
     * responsible for persisting it (after the parent {@link Account} exists).
     * The optimistic-lock {@code version} is intentionally left {@code null};
     * the persistence provider assigns it on insert.
     *
     * @param cardNum the card number (primary key, 16 characters)
     * @param acctId  the owning account identifier
     * @return a new, unpersisted {@link Card}
     */
    private Card newCard(String cardNum, long acctId) {
        return new Card(
                cardNum,
                acctId,
                "123",
                "JOHN Q PUBLIC",
                "2027-12-31",
                "Y");
    }

    // -------------------------------------------------------------------------
    // Tests
    // -------------------------------------------------------------------------

    /**
     * A card round-trips through the repository: after persisting a parent
     * account and one card, {@link CardRepository#findById(Object)} returns the
     * card with its owning account, status and embossed name intact. This is the
     * keyed {@code READ} of {@code legacy/cbl/COCRDSLC.cbl} re-expressed as a
     * primary-key lookup.
     */
    @Test
    void saveAndFindById_roundTrips() {
        newAccount(1L);
        entityManager.persistAndFlush(newCard("4000000000000001", 1L));
        entityManager.flush();
        entityManager.clear();

        Card found = repository.findById("4000000000000001").orElseThrow();

        assertThat(found.getAcctId()).isEqualTo(1L);
        assertThat(found.getCardActiveStatus()).isEqualTo("Y");
        assertThat(found.getCardEmbossedName()).isEqualTo("JOHN Q PUBLIC");
    }

    /**
     * Core {@code CARDDATA.VSAM.AIX} browse parity: the cards of one account are
     * returned in ascending card-number order and the cards of other accounts
     * are excluded. Cards are inserted in scrambled order under account {@code 1}
     * and one card is inserted under account {@code 2}; the query must return
     * exactly account {@code 1}'s three cards, sorted, reproducing the
     * account-scoped forward browse of {@code legacy/cbl/COCRDLIC.cbl}.
     */
    @Test
    void findByAcctIdOrderByCardNumAsc_returnsCardsForAccountInKeyOrder() {
        newAccount(1L);
        newAccount(2L);
        entityManager.persist(newCard("4000000000000003", 1L));
        entityManager.persist(newCard("4000000000000001", 1L));
        entityManager.persist(newCard("4000000000000002", 1L));
        entityManager.persist(newCard("4000000000000009", 2L));
        entityManager.flush();
        entityManager.clear();

        List<Card> cards = repository.findByAcctIdOrderByCardNumAsc(1L);

        assertThat(cards)
                .extracting(Card::getCardNum)
                .containsExactly(
                        "4000000000000001",
                        "4000000000000002",
                        "4000000000000003");
    }

    /**
     * Paged variant of the account-index browse. With three cards under account
     * {@code 1} and a page size of two ordered by card number ascending, the
     * first page carries the two lowest card numbers while the page metadata
     * reports three total elements across two pages. This mirrors the forward
     * paging ({@code STARTBR}/{@code READNEXT}) of the online card-list screen.
     * Because {@link CardRepository#findByAcctId(Long, Pageable)} carries no
     * {@code OrderBy} in its name, the ascending sort is supplied through the
     * {@link Pageable} to keep the slice deterministic.
     */
    @Test
    void findByAcctId_pageable_returnsPagedSlice() {
        newAccount(1L);
        entityManager.persist(newCard("4000000000000003", 1L));
        entityManager.persist(newCard("4000000000000001", 1L));
        entityManager.persist(newCard("4000000000000002", 1L));
        entityManager.flush();
        entityManager.clear();

        Pageable pageable = PageRequest.of(0, 2, Sort.by("cardNum").ascending());
        Page<Card> page = repository.findByAcctId(1L, pageable);

        assertThat(page.getTotalElements()).isEqualTo(3L);
        assertThat(page.getTotalPages()).isEqualTo(2);
        assertThat(page.getContent()).hasSize(2);
        assertThat(page.getContent())
                .extracting(Card::getCardNum)
                .containsExactly("4000000000000001", "4000000000000002");
    }

    /**
     * Global master-browse parity: every card, across all accounts, is returned
     * in ascending card-number order. This reproduces the sequential
     * primary-key read of the card master-print batch
     * {@code legacy/cbl/CBACT02C.cbl}
     * ({@code PERFORM UNTIL END-OF-FILE} over {@code CARDDATA}).
     */
    @Test
    void findAllByOrderByCardNumAsc_returnsAllInKeyOrder() {
        newAccount(1L);
        newAccount(2L);
        entityManager.persist(newCard("4000000000000002", 1L));
        entityManager.persist(newCard("4000000000000004", 2L));
        entityManager.persist(newCard("4000000000000001", 1L));
        entityManager.persist(newCard("4000000000000003", 2L));
        entityManager.flush();
        entityManager.clear();

        List<Card> all = repository.findAllByOrderByCardNumAsc();

        assertThat(all)
                .extracting(Card::getCardNum)
                .containsExactly(
                        "4000000000000001",
                        "4000000000000002",
                        "4000000000000003",
                        "4000000000000004");
    }

    /**
     * Optimistic-lock parity: the {@code @Version} column is populated on insert
     * and increments on update, reproducing the last-writer integrity of the
     * legacy READ-UPDATE-REWRITE cycle. The card is persisted, re-read, mutated
     * and saved; the reloaded version must be greater than the version observed
     * after the initial insert.
     */
    @Test
    void versionAssignedOnInsertAndIncrementsOnUpdate() {
        newAccount(1L);
        entityManager.persistAndFlush(newCard("4000000000000001", 1L));
        entityManager.flush();
        entityManager.clear();

        Card inserted = repository.findById("4000000000000001").orElseThrow();
        Long initialVersion = inserted.getVersion();
        assertThat(initialVersion).isNotNull();

        inserted.setCardActiveStatus("N");
        repository.saveAndFlush(inserted);
        entityManager.clear();

        Card updated = repository.findById("4000000000000001").orElseThrow();
        assertThat(updated.getVersion()).isGreaterThan(initialVersion);
        assertThat(updated.getCardActiveStatus()).isEqualTo("N");
    }

    /**
     * Foreign-key parity: inserting a card whose {@code acct_id} has no parent
     * {@code account} row is rejected by the database. This confirms the
     * newly-formalized {@code fk_card_account} constraint behaves as the
     * documented integrity improvement (AAP &sect;0.4.3): the legacy VSAM store
     * enforced the relationship only in application code, whereas the relational
     * target enforces it as a real foreign key. A broad {@link Exception} is
     * asserted so the test is resilient to the exact persistence-provider
     * wrapper type.
     */
    @Test
    void insertingCardWithoutParentAccount_violatesForeignKey() {
        assertThatThrownBy(() -> {
            entityManager.persist(newCard("4000000000009999", 999L));
            entityManager.flush();
        }).isInstanceOf(Exception.class);
    }

    /**
     * Sensitive-data guard: {@link Card#toString()} must never expose the card
     * verification value (AAP &sect;0.9.3). A card is built with a distinctive
     * CVV and the rendered string is asserted not to contain it. The CVV itself
     * is never printed or logged by this test.
     */
    @Test
    void toStringExcludesCvv() {
        Card card = newCard("4000000000000001", 1L);
        card.setCvv("987");

        assertThat(card.toString()).doesNotContain("987");
    }
}
