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
import com.aws.carddemo.domain.Transaction;
import com.aws.carddemo.domain.TransactionCategory;
import com.aws.carddemo.domain.TransactionType;
import com.aws.carddemo.exception.DuplicateKeyException;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;

/**
 * Testcontainers-backed Spring Data JPA integration tests for
 * {@link TransactionRepository} (entity {@link Transaction}, table
 * {@code transaction}).
 *
 * <p>This is the <strong>most browse-critical</strong> repository test in the
 * suite: it proves that two legacy VSAM behaviours from the posted-transaction
 * master {@code TRANSACT.VSAM.KSDS} (copybook {@code legacy/cpy/CVTRA05Y.cpy},
 * {@code TRAN-RECORD}, RECLN 350) survive the re-platform to PostgreSQL
 * (AAP &sect;0.7.1 <strong>H5</strong>):</p>
 * <ol>
 *   <li><strong>Reverse-browse max-key id generation.</strong>
 *       {@code legacy/cbl/COTRN02C.cbl} {@code ADD-TRANSACTION} (lines 444-451)
 *       moved {@code HIGH-VALUES} into {@code TRAN-ID}, issued
 *       {@code STARTBR} + {@code READPREV} + {@code ENDBR} to read the highest
 *       existing key, then {@code ADD 1}. The Java target replaces that with
 *       {@link TransactionRepository#findMaxTranId()} (and the equivalent
 *       {@link TransactionRepository#findTopByOrderByTranIdDesc()}); the
 *       increment-from-max and its concurrency guard live in the service layer.
 *       The empty-table branch is asserted here: with no rows the max lookup is
 *       empty, so the first generated id must seed at base {@code 1}
 *       ({@code "0000000000000001"}) &mdash; {@code nextId = empty ? 1 : max + 1}.</li>
 *   <li><strong>Alternate-index chronological browse.</strong> The VSAM
 *       alternate index {@code TRANSACT.VSAM.AIX} that backed the
 *       transaction-list forward/backward paging of
 *       {@code legacy/cbl/COTRN00C.cbl} (an {@code EXEC CICS STARTBR} followed by
 *       up to eleven {@code READNEXT}/{@code READPREV} per screen) becomes the
 *       timestamp-ordered query
 *       {@link TransactionRepository#findByCardNumOrderByProcTsAscTranIdAsc(String)} and
 *       its {@link Pageable} overload.</li>
 * </ol>
 *
 * <p>It also guards {@code tran_amt} monetary fidelity: the COBOL
 * {@code TRAN-AMT PIC S9(09)V99} packed-decimal field is stored as
 * {@code DECIMAL(11,2)} and must round-trip through a {@link BigDecimal} at
 * scale 2 with no floating-point drift (AAP &sect;0.7.1 H3, &sect;0.9.2).
 * Source lineage: {@code COTRN00C.cbl} (list), {@code COTRN02C.cbl}
 * (add / max-key), {@code CBTRN02C.cbl} (posting), copybook
 * {@code CVTRA05Y.cpy}.</p>
 *
 * <h2>Harness</h2>
 * <ul>
 *   <li>{@link DataJpaTest} boots only the JPA slice and wraps each test in a
 *       transaction that is rolled back afterwards, so tests are isolated and the
 *       {@code transaction} table (and its master parents) start empty &mdash;
 *       which is precisely what lets the empty-table max-key branch be
 *       exercised.</li>
 *   <li>{@link AutoConfigureTestDatabase} with
 *       {@link AutoConfigureTestDatabase.Replace#NONE} keeps the real datasource
 *       (the Testcontainers PostgreSQL below) instead of an embedded database, so
 *       mappings and migrations are validated against the production engine.</li>
 *   <li>A single static {@link PostgreSQLContainer} ({@code postgres:16-alpine})
 *       is wired into Spring Boot by {@link ServiceConnection}, so no JDBC URL,
 *       username or password is ever hardcoded (AAP &sect;0.8.1 / &sect;0.9.3).</li>
 *   <li>The {@code test} profile ({@code application-test.yml}) runs the real
 *       Flyway migrations ({@code V1__schema.sql} + {@code V2__reference_data.sql})
 *       and then has Hibernate <em>validate</em> the {@link Transaction} and
 *       parent mappings (including {@code tran_amt DECIMAL(11,2)} and every
 *       foreign key) &mdash; it never mutates the schema.</li>
 *   <li>The collaborators are injected through the constructor; the global
 *       {@code spring.test.constructor.autowire.mode=all} (see
 *       {@code src/test/resources/junit-platform.properties}) makes a
 *       per-parameter {@code @Autowired} unnecessary.</li>
 * </ul>
 *
 * <p><strong>Foreign-key ordering.</strong> A {@link Transaction} row has three
 * real foreign keys &mdash; {@code card_num} &rarr; {@code card} (whose own
 * {@code acct_id} &rarr; {@code account}), {@code type_cd} &rarr;
 * {@code transaction_type}, and the composite {@code (type_cd, cat_cd)} &rarr;
 * {@code transaction_category}. Every parent is therefore persisted, in the
 * order <em>Account &rarr; Card &rarr; TransactionType &rarr;
 * TransactionCategory</em>, before any transaction (see {@link #seedParents}).
 * Synthetic non-colliding reference codes ({@code "99"} / {@code 9999}) are used
 * so the fixtures never clash with the {@code V2} seed data
 * ({@code type_cd} {@code '01'}-{@code '07'}).</p>
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
@Testcontainers
class TransactionRepositoryTest {

    /**
     * Real PostgreSQL 16 engine shared by every test in this class. It is
     * {@code static} so the JUnit {@link Testcontainers} extension starts it once
     * before the Spring context is created, and {@link ServiceConnection}
     * publishes its connection details to Spring Boot so the datasource is
     * configured with no hardcoded credentials.
     */
    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>(DockerImageName.parse("postgres:16-alpine"));

    /** Synthetic transaction-type code that never collides with the V2 seed data. */
    private static final String SYNTHETIC_TYPE_CD = "99";

    /** Synthetic transaction-category code paired with {@link #SYNTHETIC_TYPE_CD}. */
    private static final int SYNTHETIC_CAT_CD = 9999;

    /** Repository under test. */
    private final TransactionRepository repository;

    /**
     * JPA test helper used to persist parent rows and to control the persistence
     * context (flush/clear) so that reads exercise the database rather than the
     * first-level cache.
     */
    private final TestEntityManager entityManager;

    /**
     * Constructor injection of the collaborators managed by the Spring
     * TestContext framework. No {@code @Autowired} is required because the suite
     * enables {@code spring.test.constructor.autowire.mode=all} globally.
     *
     * @param repository    the {@link TransactionRepository} under test
     * @param entityManager the JPA test entity manager
     */
    TransactionRepositoryTest(TransactionRepository repository, TestEntityManager entityManager) {
        this.repository = repository;
        this.entityManager = entityManager;
    }

    // -------------------------------------------------------------------------
    // Fixture helpers
    // -------------------------------------------------------------------------

    /**
     * Persists the shared reference parents required by the composite foreign key
     * {@code (type_cd, cat_cd)} &rarr; {@code transaction_category} (which in turn
     * carries {@code type_cd} &rarr; {@code transaction_type}).
     *
     * <p>The persist is <em>idempotent</em>: several tests seed more than one card
     * that share the same reference codes, so the type and category rows must be
     * inserted at most once. Existence is checked through the persistence context
     * with {@link TestEntityManager#find} before persisting. No flush is performed
     * here; the caller controls flushing.</p>
     *
     * @param typeCd the two-character transaction-type code
     * @param catCd  the transaction-category code paired with {@code typeCd}
     */
    private void seedTransactionTypeAndCategory(String typeCd, int catCd) {
        if (entityManager.find(TransactionType.class, typeCd) == null) {
            entityManager.persist(new TransactionType(typeCd, "SYNTHETIC TEST TYPE"));
        }
        TransactionCategory.TransactionCategoryId categoryId =
                new TransactionCategory.TransactionCategoryId(typeCd, catCd);
        if (entityManager.find(TransactionCategory.class, categoryId) == null) {
            entityManager.persist(new TransactionCategory(typeCd, catCd, "SYNTHETIC TEST CATEGORY"));
        }
    }

    /**
     * Persists the full parent chain a {@link Transaction} depends on, in
     * foreign-key order: {@link Account} (owning account), {@link Card}
     * (referenced by {@code card_num}), then the shared {@link TransactionType}
     * and {@link TransactionCategory} reference rows. The persistence context is
     * flushed at the end so that a subsequent transaction insert sees committed
     * parents.
     *
     * <p>All monetary account fields are {@link BigDecimal} values built from
     * strings (never {@code double}/{@code float}) at scale 2, matching the
     * {@code DECIMAL(12,2)} columns.</p>
     *
     * @param cardNum the 16-character card number (primary key of {@code card})
     * @param acctId  the owning account identifier (unique per call)
     * @param typeCd  the synthetic transaction-type code
     * @param catCd   the synthetic transaction-category code
     */
    private void seedParents(String cardNum, long acctId, String typeCd, int catCd) {
        entityManager.persist(new Account(
                acctId,
                "Y",
                new BigDecimal("0.00"),
                new BigDecimal("5000.00"),
                new BigDecimal("1000.00"),
                "2020-01-01",
                "2027-12-31",
                "2020-01-01",
                new BigDecimal("0.00"),
                new BigDecimal("0.00"),
                "12345",
                "A000000000"));
        entityManager.persist(new Card(
                cardNum,
                acctId,
                "123",
                "TEST CARDHOLDER",
                "2027-12-31",
                "Y"));
        seedTransactionTypeAndCategory(typeCd, catCd);
        entityManager.flush();
    }

    /**
     * Builds an unpersisted {@link Transaction} in the copybook field order of
     * {@code CVTRA05Y.cpy}. The caller persists it (after the parent chain
     * exists). The monetary amount is constructed with the {@link BigDecimal}
     * string constructor so the requested scale is preserved exactly; the
     * origination and processing timestamps are set to the same value.
     *
     * @param tranId 16-character transaction id (zero-padded so lexical order
     *               equals numeric order)
     * @param cardNum the owning card number ({@code card_num} foreign key)
     * @param typeCd  the transaction-type code
     * @param catCd   the transaction-category code
     * @param amt     the monetary amount as a decimal string (for example
     *                {@code "1234.56"})
     * @param origTs  the origination timestamp text ({@code PIC X(26)})
     * @return a new, unpersisted {@link Transaction}
     */
    private Transaction newTxn(String tranId,
                               String cardNum,
                               String typeCd,
                               int catCd,
                               String amt,
                               String origTs) {
        return new Transaction(
                tranId,
                typeCd,
                catCd,
                "POS",
                "TEST",
                new BigDecimal(amt),
                123456789L,
                "TEST MERCHANT",
                "TEST CITY",
                "12345",
                cardNum,
                origTs,
                origTs);
    }

    // -------------------------------------------------------------------------
    // Tests
    // -------------------------------------------------------------------------

    /**
     * Empty-table branch of the reverse-browse id generation
     * ({@code legacy/cbl/COTRN02C.cbl} {@code ADD-TRANSACTION}, lines 444-451).
     *
     * <p>With no transactions present both the aggregate max lookup
     * ({@link TransactionRepository#findMaxTranId()}) and the top-key browse
     * ({@link TransactionRepository#findTopByOrderByTranIdDesc()}) return
     * {@link Optional#empty()}. This is the branch that seeds the very first id:
     * because the legacy pattern read {@code HIGH-VALUES} &rarr; {@code STARTBR}
     * &rarr; {@code READPREV} &rarr; {@code ENDBR} and then {@code ADD 1}, the
     * Java service computes {@code nextId = empty ? 1 : max + 1}; on an empty
     * table that base is {@code 1}, i.e. the id {@code "0000000000000001"}.</p>
     */
    @Test
    void findMaxTranId_onEmptyTable_returnsEmptyOptional() {
        Optional<String> maxTranId = repository.findMaxTranId();
        Optional<Transaction> topTransaction = repository.findTopByOrderByTranIdDesc();

        assertThat(maxTranId).isEmpty();
        assertThat(topTransaction).isEmpty();

        // Empty-table parity with COTRN02C L444-L451: nextId = empty ? 1 : max + 1,
        // so the first generated transaction id seeds at base 1.
        String firstGeneratedId =
                maxTranId.map(max -> String.format("%016d", Long.parseLong(max) + 1L))
                        .orElse(String.format("%016d", 1L));
        assertThat(firstGeneratedId).isEqualTo("0000000000000001");
    }

    /**
     * Populated-table branch of the reverse-browse max-key lookup. Three
     * transactions are inserted out of order under one card; the repository must
     * report the lexically (and therefore numerically, thanks to zero-padded
     * 16-character ids) highest key. This reproduces the
     * {@code MOVE HIGH-VALUES} &rarr; {@code READPREV} tip read of
     * {@code legacy/cbl/COTRN02C.cbl}; the next generated id would be
     * {@code max + 1}.
     */
    @Test
    void findMaxTranId_returnsHighestId_afterInserts() {
        String cardNum = "4000000000000001";
        seedParents(cardNum, 1L, SYNTHETIC_TYPE_CD, SYNTHETIC_CAT_CD);

        entityManager.persist(newTxn(
                "0000000000000001", cardNum, SYNTHETIC_TYPE_CD, SYNTHETIC_CAT_CD,
                "10.00", "2024-01-01-10.00.00.000000"));
        entityManager.persist(newTxn(
                "0000000000000005", cardNum, SYNTHETIC_TYPE_CD, SYNTHETIC_CAT_CD,
                "20.00", "2024-01-02-10.00.00.000000"));
        entityManager.persist(newTxn(
                "0000000000000003", cardNum, SYNTHETIC_TYPE_CD, SYNTHETIC_CAT_CD,
                "30.00", "2024-01-03-10.00.00.000000"));
        entityManager.flush();
        entityManager.clear();

        Optional<String> maxTranId = repository.findMaxTranId();
        Transaction topTransaction = repository.findTopByOrderByTranIdDesc().orElseThrow();

        assertThat(maxTranId).contains("0000000000000005");
        assertThat(topTransaction.getTranId()).isEqualTo("0000000000000005");
    }

    /**
     * Chronological alternate-index browse (List variant). Under card A three
     * transactions are inserted with deliberately out-of-order origination
     * timestamps, and one transaction is inserted under card B. The finder must
     * return only card A's rows, ordered ascending by {@code proc_ts}, excluding
     * card B entirely. This reproduces the {@code TRANSACT.VSAM.AIX} chronological
     * browse used by the transaction-list screen
     * ({@code legacy/cbl/COTRN00C.cbl}).
     */
    @Test
    void findByCardNumOrderByProcTsAscTranIdAsc_list_returnsChronologicalForCard() {
        String cardA = "4000000000000001";
        String cardB = "4000000000000002";
        seedParents(cardA, 1L, SYNTHETIC_TYPE_CD, SYNTHETIC_CAT_CD);
        seedParents(cardB, 2L, SYNTHETIC_TYPE_CD, SYNTHETIC_CAT_CD);

        // Card A: inserted out of chronological order (March, January, February).
        entityManager.persist(newTxn(
                "0000000000000010", cardA, SYNTHETIC_TYPE_CD, SYNTHETIC_CAT_CD,
                "10.00", "2024-03-01-10.00.00.000000"));
        entityManager.persist(newTxn(
                "0000000000000011", cardA, SYNTHETIC_TYPE_CD, SYNTHETIC_CAT_CD,
                "10.00", "2024-01-01-10.00.00.000000"));
        entityManager.persist(newTxn(
                "0000000000000012", cardA, SYNTHETIC_TYPE_CD, SYNTHETIC_CAT_CD,
                "10.00", "2024-02-01-10.00.00.000000"));
        // Card B: a single transaction that must never appear in card A's browse.
        entityManager.persist(newTxn(
                "0000000000000020", cardB, SYNTHETIC_TYPE_CD, SYNTHETIC_CAT_CD,
                "10.00", "2024-01-15-10.00.00.000000"));
        entityManager.flush();
        entityManager.clear();

        List<Transaction> cardATransactions = repository.findByCardNumOrderByProcTsAscTranIdAsc(cardA);

        assertThat(cardATransactions).hasSize(3);
        assertThat(cardATransactions)
                .extracting(Transaction::getOrigTs)
                .containsExactly(
                        "2024-01-01-10.00.00.000000",
                        "2024-02-01-10.00.00.000000",
                        "2024-03-01-10.00.00.000000");
        assertThat(cardATransactions)
                .extracting(Transaction::getCardNum)
                .containsOnly(cardA);
    }

    /**
     * Chronological alternate-index browse (paged variant). Reusing card A's
     * three transactions, the {@link Pageable} overload with a page size of two
     * returns the two earliest by {@code proc_ts} while reporting three total
     * elements. The ascending ordering is supplied by the derived-query method
     * name ({@code OrderByProcTsAscTranIdAsc}); no additional {@code Sort} is needed on the
     * {@link Pageable}. This mirrors the eleven-rows-per-screen forward paging of
     * {@code legacy/cbl/COTRN00C.cbl}.
     */
    @Test
    void findByCardNumOrderByProcTsAscTranIdAsc_pageable_returnsChronologicalSlice() {
        String cardA = "4000000000000001";
        String cardB = "4000000000000002";
        seedParents(cardA, 1L, SYNTHETIC_TYPE_CD, SYNTHETIC_CAT_CD);
        seedParents(cardB, 2L, SYNTHETIC_TYPE_CD, SYNTHETIC_CAT_CD);

        entityManager.persist(newTxn(
                "0000000000000010", cardA, SYNTHETIC_TYPE_CD, SYNTHETIC_CAT_CD,
                "10.00", "2024-03-01-10.00.00.000000"));
        entityManager.persist(newTxn(
                "0000000000000011", cardA, SYNTHETIC_TYPE_CD, SYNTHETIC_CAT_CD,
                "10.00", "2024-01-01-10.00.00.000000"));
        entityManager.persist(newTxn(
                "0000000000000012", cardA, SYNTHETIC_TYPE_CD, SYNTHETIC_CAT_CD,
                "10.00", "2024-02-01-10.00.00.000000"));
        entityManager.persist(newTxn(
                "0000000000000020", cardB, SYNTHETIC_TYPE_CD, SYNTHETIC_CAT_CD,
                "10.00", "2024-01-15-10.00.00.000000"));
        entityManager.flush();
        entityManager.clear();

        Pageable pageable = PageRequest.of(0, 2);
        Page<Transaction> firstPage = repository.findByCardNumOrderByProcTsAscTranIdAsc(cardA, pageable);

        assertThat(firstPage.getTotalElements()).isEqualTo(3L);
        assertThat(firstPage.getContent()).hasSize(2);
        assertThat(firstPage.getContent())
                .extracting(Transaction::getOrigTs)
                .containsExactly(
                        "2024-01-01-10.00.00.000000",
                        "2024-02-01-10.00.00.000000");
    }

    /**
     * Schema regression guard for the statement per-card browse index.
     *
     * <p>The statement-generation job reads every transaction for one card via
     * {@link TransactionRepository#findByCardNumOrderByProcTsAscTranIdAsc(String)}
     * ({@code WHERE card_num = ? ORDER BY proc_ts, tran_id}). Because
     * {@code transaction.card_num} is a foreign key and PostgreSQL does <em>not</em>
     * auto-create an index on a foreign-key column, that query would otherwise fall
     * back to a full sequential scan plus an in-memory (disk-spilling) sort. Flyway
     * migration {@code V4__add_transaction_card_num_index.sql} adds the covering
     * index {@code idx_transaction_card_num} on {@code (card_num, proc_ts, tran_id)}
     * so the filter and the ordering are both served by a single Index Scan (AAP
     * &sect;0.4.3; decision log D10, &ldquo;Statement per-card covering index&rdquo;).</p>
     *
     * <p>This test asserts the migration created that index on the {@code transaction}
     * table with the exact column order the query relies on, guarding against an
     * accidental removal or reordering. It reads {@code pg_indexes.indexdef}, whose
     * canonical rendering for this index is
     * {@code ... USING btree (card_num, proc_ts, tran_id)}.</p>
     */
    @Test
    void schema_definesCoveringIndexForStatementPerCardBrowse() {
        List<?> indexDefs = entityManager.getEntityManager()
                .createNativeQuery(
                        "SELECT indexdef FROM pg_indexes "
                                + "WHERE tablename = 'transaction' "
                                + "AND indexname = 'idx_transaction_card_num'")
                .getResultList();

        assertThat(indexDefs)
                .as("Flyway V4 must create idx_transaction_card_num on the transaction table")
                .hasSize(1);
        assertThat(String.valueOf(indexDefs.get(0)))
                .as("the statement per-card browse index must cover (card_num, proc_ts, tran_id) in order")
                .containsIgnoringCase("btree (card_num, proc_ts, tran_id)");
    }

    /**
     * Monetary-fidelity round trip. A transaction is persisted with
     * {@code tran_amt = 1234.56} (built with the {@link BigDecimal} string
     * constructor, never {@code double}/{@code float}); after a flush and clear it
     * is re-read from the database and its amount must compare equal to
     * {@code "1234.56"} and carry scale 2, matching the {@code DECIMAL(11,2)}
     * column derived from {@code TRAN-AMT PIC S9(09)V99}.
     */
    @Test
    void saveAndFindById_roundTripsAmountAtScale2() {
        String cardNum = "4000000000000001";
        seedParents(cardNum, 1L, SYNTHETIC_TYPE_CD, SYNTHETIC_CAT_CD);

        entityManager.persist(newTxn(
                "0000000000000001", cardNum, SYNTHETIC_TYPE_CD, SYNTHETIC_CAT_CD,
                "1234.56", "2024-01-01-10.00.00.000000"));
        entityManager.flush();
        entityManager.clear();

        Transaction found = repository.findById("0000000000000001").orElseThrow();

        assertThat(found.getTranAmt()).isEqualByComparingTo("1234.56");
        assertThat(found.getTranAmt().scale()).isEqualTo(2);
    }

    /**
     * Formalized foreign-key integrity. The type and category reference rows are
     * seeded but <em>no</em> card is created, so persisting a transaction that
     * references a non-existent {@code card_num} must be rejected by the database
     * ({@code fk_transaction_card}). The legacy VSAM store enforced this
     * relationship only in application code; the relational target enforces it as
     * a real foreign key (a documented improvement, AAP &sect;0.4.3). A broad
     * {@link Exception} is asserted so the test is resilient to the exact
     * persistence-provider wrapper type.
     */
    @Test
    void insertingTransactionWithoutParentCard_violatesForeignKey() {
        seedTransactionTypeAndCategory(SYNTHETIC_TYPE_CD, SYNTHETIC_CAT_CD);
        entityManager.flush();

        assertThatThrownBy(() -> {
            entityManager.persist(newTxn(
                    "0000000000000001", "9999999999999999", SYNTHETIC_TYPE_CD, SYNTHETIC_CAT_CD,
                    "10.00", "2024-01-01-10.00.00.000000"));
            entityManager.flush();
        }).isInstanceOf(Exception.class);
    }

    /**
     * QA CRITICAL-1 regression proof: a colliding transaction id must FAIL as a
     * genuine {@code INSERT} (unique-constraint violation), never a silent
     * {@code UPDATE} (overwrite).
     *
     * <p>Because {@link Transaction} implements
     * {@link org.springframework.data.domain.Persistable} and a freshly-constructed
     * record reports {@code isNew() == true}, Spring Data issues
     * {@code EntityManager.persist(...)} (an {@code INSERT}) rather than
     * {@code merge(...)}. Saving a <em>new</em> record whose {@code tran_id} already
     * exists therefore violates the {@code pk_transaction} primary key and raises a
     * {@link DataIntegrityViolationException} at flush &mdash; reproducing the COBOL
     * {@code WRITE}&hellip;{@code DUPKEY} contract. Under the former assigned-id +
     * no-{@code @Version} mapping the identical call would have merged (an
     * {@code UPDATE}), silently overwriting the existing row and throwing nothing:
     * the exact lost-insert this test guards against.</p>
     *
     * <p>The thrown violation is additionally asserted to be recognized by
     * {@link DuplicateKeyException#isTransactionIdCollision(DataIntegrityViolationException)},
     * proving the real PostgreSQL {@code 23505}/{@code pk_transaction} error flows
     * through the exact classifier both posting services use to raise a
     * {@link DuplicateKeyException}.</p>
     */
    @Test
    void savingNewTransactionWithCollidingId_insertsAndFailsUniqueConstraint_neverSilentlyMerges() {
        String cardNum = "4444333322221111";
        long acctId = 990001L;
        seedParents(cardNum, acctId, SYNTHETIC_TYPE_CD, SYNTHETIC_CAT_CD);

        String collidingId = "0000000000009001";
        // Commit an original row under the colliding id, then detach it so the second
        // save must reach the database rather than merging within the persistence context.
        repository.saveAndFlush(newTxn(
                collidingId, cardNum, SYNTHETIC_TYPE_CD, SYNTHETIC_CAT_CD,
                "100.00", "2024-01-01-10.00.00.000000"));
        entityManager.clear();

        // A brand-new record (isNew() == true) reusing the same id. Persistable forces an
        // INSERT, so the pk_transaction unique constraint is violated at flush; a merge
        // (the old behavior) would instead have UPDATEd this row and thrown nothing.
        Transaction colliding = newTxn(
                collidingId, cardNum, SYNTHETIC_TYPE_CD, SYNTHETIC_CAT_CD,
                "999.99", "2024-02-02-11.11.11.111111");

        Throwable thrown = catchThrowable(() -> repository.saveAndFlush(colliding));

        assertThat(thrown)
                .as("a colliding tran_id must fail as a genuine INSERT (unique violation), "
                        + "never silently merge/UPDATE the existing row")
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThat(DuplicateKeyException.isTransactionIdCollision(
                (DataIntegrityViolationException) thrown))
                .as("the real PostgreSQL 23505 on pk_transaction is recognized by the "
                        + "shared collision classifier both posting services use")
                .isTrue();
    }
}
