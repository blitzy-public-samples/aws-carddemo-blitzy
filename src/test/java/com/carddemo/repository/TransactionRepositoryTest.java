package com.carddemo.repository;

import com.carddemo.entity.Transaction;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Comparator;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code @DataJpaTest} slice test for {@link TransactionRepository}.
 *
 * <h2>What this test pins (parity)</h2>
 * <p>The legacy AWS CardDemo "List Transactions" screen ({@code app/cbl/COTRN00C.cbl})
 * browsed the VSAM {@code TRANSACT.AIX} alternate index by account, in origination-timestamp
 * order, using {@code STARTBR}/{@code READNEXT} (and {@code READPREV} for paging back), and
 * displayed a fixed page of <strong>seven</strong> rows (field suffixes {@code TRNID07I}/
 * {@code SEL0007I} confirm the 7-row map). That browse is replaced by the derived,
 * paginated, ordered query
 * {@link TransactionRepository#findByAcctIdOrderByOrigTs(Long, org.springframework.data.domain.Pageable)},
 * which filters on {@code acct_id} and orders by {@code orig_ts} and is index-supported by the
 * V1 composite index {@code idx_transactions_acct_id_orig_ts (acct_id, orig_ts)} (AAP &sect;0.6.4).</p>
 *
 * <p>Two parity-critical behaviors are asserted:</p>
 * <ol>
 *   <li><b>Page size 7 + ascending origination order.</b> {@code findByAcctIdOrderByOrigTs}
 *       returns at most seven rows per page (the preserved 3270 screen size) and orders them
 *       ascending by {@code orig_ts} regardless of physical insertion order.</li>
 *   <li><b>Dual timestamps persist.</b> The copybook {@code app/cpy/CVTRA05Y.cpy} carries BOTH
 *       {@code TRAN-ORIG-TS X(26)} and {@code TRAN-PROC-TS X(26)}; both map to distinct
 *       {@link LocalDateTime} columns ({@code orig_ts}, {@code proc_ts}) and must round-trip
 *       independently &mdash; never collapsed into one (AAP &sect;0.7.3 #10).</li>
 * </ol>
 *
 * <h2>Test data ownership</h2>
 * <p>The {@code transactions} table is created by {@code V1__schema.sql} but is intentionally
 * <em>never</em> seeded (the master seed {@code V3__seed_master.sql} populates customers,
 * accounts, cards, card_xref and transaction_category_balance only). Each test therefore starts
 * from an empty table and inserts its own rows. {@code @DataJpaTest} wraps every test method in a
 * transaction that is rolled back afterward, so the rows never leak between tests.</p>
 *
 * <h2>Foreign-key-safe self-managed rows</h2>
 * <p>Inserted rows reference only seeded parents so the three {@code transactions} foreign keys
 * are satisfied without fabricating extra fixtures:</p>
 * <ul>
 *   <li>{@code acctId = 1L} &rarr; {@code fk_tran_acct} (account 1 is seeded by V3).</li>
 *   <li>{@code typeCd = "01"} + {@code catCd = 1} &rarr; {@code fk_tran_cat} (category
 *       {@code ('01', 1)} "Regular Sales Draft" is seeded by V2).</li>
 *   <li>{@code cardNum = null} &rarr; {@code fk_tran_card} is nullable, so no card row is needed.</li>
 * </ul>
 *
 * <p>No personally identifiable information (PII) is used: the {@link Transaction} entity carries
 * no CVV, SSN or password fields (AAP &sect;0.6.8, &sect;0.7.1).</p>
 *
 * <h2>Binding conventions</h2>
 * <p>Per the assigned-folder spec, every repository slice test repeats the same three class
 * annotations (there is no shared base class):</p>
 * <pre>
 * &#64;DataJpaTest
 * &#64;AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
 * &#64;ActiveProfiles("test")
 * </pre>
 * <p>{@code Replace.NONE} keeps the Flyway-migrated H2 datasource defined by
 * {@code application-test.yml} (instead of substituting Boot's default embedded database), so the
 * test runs against the real V1&ndash;V4 schema and seed data.</p>
 *
 * @see TransactionRepository#findByAcctIdOrderByOrigTs(Long, org.springframework.data.domain.Pageable)
 * @see Transaction
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
class TransactionRepositoryTest {

    /**
     * Preserved legacy page size. The COTRN00C transaction list screen displayed exactly seven
     * rows per page; callers build their {@code Pageable} as {@code PageRequest.of(n, 7)}.
     */
    private static final int PAGE_SIZE = 7;

    /** Owning account for all self-managed rows; seeded by {@code V3__seed_master.sql}. */
    private static final long SEEDED_ACCOUNT_ID = 1L;

    /** Transaction type code of the seeded category {@code ('01', 1)} "Regular Sales Draft". */
    private static final String VALID_TYPE_CD = "01";

    /** Transaction category code of the seeded category {@code ('01', 1)}. */
    private static final int VALID_CAT_CD = 1;

    @Autowired
    private TransactionRepository transactionRepository;

    @Autowired
    private TestEntityManager entityManager;

    /**
     * The {@code transactions} table is created by V1 but never seeded, so it must be empty at the
     * start of every test (AAP: created empty, not seeded).
     */
    @Test
    @DisplayName("transactions table is created empty (no seed rows)")
    void transactionsTable_isEmptyBySeed() {
        // V1 creates `transactions`; no migration populates it.
        assertThat(transactionRepository.count()).isEqualTo(0L);
    }

    /**
     * Verifies the {@code COTRN00C} browse replacement: {@code findByAcctIdOrderByOrigTs} returns a
     * fixed page of seven rows per page, ordered ascending by origination timestamp, regardless of
     * the order in which the rows were physically inserted.
     */
    @Test
    @DisplayName("findByAcctIdOrderByOrigTs paginates 7-per-page and orders ascending by orig_ts")
    void findByAcctIdOrderByOrigTs_paginatesSevenAndOrdersByOrigTs() {
        LocalDateTime base = LocalDateTime.of(2023, 1, 1, 0, 0, 0);
        // Insert 10 rows for account 1 in REVERSE chronological order (i = 9 -> 0) so that the
        // physical insertion order is the opposite of the expected ORDER BY orig_ts ASC result.
        for (int i = 9; i >= 0; i--) {
            Transaction t = new Transaction();
            t.setTranId("TESTTRAN" + String.format("%08d", i)); // 8 + 8 = 16 chars
            t.setAcctId(SEEDED_ACCOUNT_ID);
            t.setTypeCd(VALID_TYPE_CD);   // valid composite category ('01', 1)
            t.setCatCd(VALID_CAT_CD);
            t.setSource("TEST");
            t.setDescription("Test transaction " + i);
            t.setAmt(new BigDecimal("10.00"));
            t.setCardNum(null);           // nullable FK -> avoids needing a card row
            t.setOrigTs(base.plusMinutes(i));
            t.setProcTs(base.plusMinutes(i).plusSeconds(30));
            entityManager.persist(t);
        }
        entityManager.flush();
        entityManager.clear();

        Page<Transaction> page0 =
                transactionRepository.findByAcctIdOrderByOrigTs(SEEDED_ACCOUNT_ID, PageRequest.of(0, PAGE_SIZE));
        assertThat(page0.getContent()).hasSize(PAGE_SIZE);          // exactly 7 per page
        assertThat(page0.getTotalElements()).isEqualTo(10L);
        assertThat(page0.getTotalPages()).isEqualTo(2);
        assertThat(page0.hasNext()).isTrue();
        assertThat(page0.getContent())
                .isSortedAccordingTo(Comparator.comparing(Transaction::getOrigTs)); // ascending order
        assertThat(page0.getContent()).allSatisfy(
                t -> assertThat(t.getAcctId()).isEqualTo(SEEDED_ACCOUNT_ID));

        Page<Transaction> page1 =
                transactionRepository.findByAcctIdOrderByOrigTs(SEEDED_ACCOUNT_ID, PageRequest.of(1, PAGE_SIZE));
        assertThat(page1.getContent()).hasSize(3);                  // 10 - 7 = 3 remaining
    }

    /**
     * Verifies that BOTH the origination and processing timestamps round-trip independently, and
     * that the monetary {@code amt} column preserves scale (AAP &sect;0.7.3 #10; copybook
     * {@code CVTRA05Y} fields {@code TRAN-ORIG-TS} + {@code TRAN-PROC-TS}).
     */
    @Test
    @DisplayName("save persists BOTH orig_ts and proc_ts timestamps (dual-timestamp parity)")
    void save_persistsBothOrigAndProcTimestamps() {
        LocalDateTime orig = LocalDateTime.of(2023, 6, 1, 9, 0, 0);
        LocalDateTime proc = LocalDateTime.of(2023, 6, 1, 9, 0, 30);
        Transaction t = new Transaction();
        t.setTranId("TESTTRAN10000000"); // 16 chars
        t.setAcctId(SEEDED_ACCOUNT_ID);
        t.setTypeCd(VALID_TYPE_CD);
        t.setCatCd(VALID_CAT_CD);
        t.setAmt(new BigDecimal("25.50"));
        t.setOrigTs(orig);
        t.setProcTs(proc);
        entityManager.persist(t);
        entityManager.flush();
        entityManager.clear();

        Transaction reloaded = transactionRepository.findById("TESTTRAN10000000").orElseThrow();
        assertThat(reloaded.getOrigTs()).isEqualTo(orig);
        assertThat(reloaded.getProcTs()).isEqualTo(proc);   // dual timestamps both persisted (AAP 0.7.3 #10)
        // BigDecimal MUST be compared by value, never by isEqualTo (scale-sensitive).
        assertThat(reloaded.getAmt()).isEqualByComparingTo(new BigDecimal("25.50"));
    }
}
