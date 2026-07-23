package com.aws.carddemo.repository;

import com.aws.carddemo.AbstractPostgresIntegrationTest;
import com.aws.carddemo.domain.Account;
import com.aws.carddemo.domain.Transaction;
import com.aws.carddemo.domain.UserSecurity;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Real-concurrency parity suite (review finding&nbsp;#15) proving, against a live Testcontainers
 * PostgreSQL 18 database, that the migration reproduces the two mainframe record-locking guarantees
 * the COBOL relied on. It is the concrete concurrent proof that {@code PessimisticLockFinderTest}
 * (a reflection-only mechanism check) explicitly deferred, and it is the suite referenced by the
 * decision log's record-locking entry.
 *
 * <p><strong>Why genuine threads and no {@code @Transactional} boundary.</strong> A lost-update /
 * duplicate-insert race can only be observed when multiple transactions run <em>simultaneously</em>
 * on <em>separate</em> database connections and <em>commit</em>. This class therefore:</p>
 * <ul>
 *   <li>extends {@link AbstractPostgresIntegrationTest}, whose full {@code @SpringBootTest} context
 *       supplies a real HikariCP pool and a {@link PlatformTransactionManager}, and which resets the
 *       database to the pristine Flyway seed before each test &mdash; but deliberately declares
 *       <em>no</em> {@code @Transactional} rollback (committed state is the whole point);</li>
 *   <li>spawns a fixed pool of worker threads, each driving its own transaction via a
 *       {@link TransactionTemplate} (so every thread borrows a distinct pooled connection);</li>
 *   <li>aligns the workers on a {@link CyclicBarrier} start gate so they collide on the same row at
 *       the same instant, maximising contention.</li>
 * </ul>
 * A mock, single-threaded, or {@code @Transactional} (rollback) test cannot satisfy finding&nbsp;#15
 * because none of those actually races committed transactions on one row.
 *
 * <p><strong>Connection budget.</strong> The application pool is {@code maximum-pool-size: 10}
 * (see {@code application.yml}). Every worker that blocks on the row lock keeps holding its
 * connection while it waits, so the worker count must stay within the pool; the tests use 6 workers,
 * leaving headroom.</p>
 *
 * <p><strong>Origin / parity:</strong> net-new verification infrastructure (no COBOL ancestor). The
 * guarantees it asserts mirror the CICS {@code READ ... UPDATE} record lock and the
 * write-only {@code REWRITE}/{@code WRITE} discipline of the legacy programs retained read-only under
 * {@code legacy/**} (e.g. {@code COACTUPC.cbl}, {@code COUSR02C.cbl}, {@code CBTRN02C.cbl}).</p>
 */
class ConcurrencyParityIT extends AbstractPostgresIntegrationTest {

    /** Seeded account used for the lost-update race (V2 reference data loads {@code acct_id = 1}). */
    private static final Long RACE_ACCT_ID = 1L;

    /** Worker count for every race; stays within the HikariCP pool (max 10) with headroom. */
    private static final int WORKERS = 6;

    /**
     * Increment rounds per worker in the lost-update race. {@code WORKERS * ROUNDS} serialized
     * read-modify-write increments are applied; a run without the pessimistic lock would lose a
     * large fraction of them, so the exact-total assertion is a genuine fail-without-the-lock proof.
     */
    private static final int ROUNDS = 40;

    /** Each increment adds exactly this much to the balance (an exact {@code NUMERIC(12,2)} value). */
    private static final BigDecimal ONE_DOLLAR = new BigDecimal("1.00");

    @Autowired
    private AccountRepository accountRepository;

    @Autowired
    private TransactionRepository transactionRepository;

    @Autowired
    private UserSecurityRepository userSecurityRepository;

    @Autowired
    private PlatformTransactionManager transactionManager;

    /** Per-thread transactions are driven through this template; it is thread-safe once configured. */
    private TransactionTemplate txTemplate;

    private void initTemplate() {
        if (txTemplate == null) {
            txTemplate = new TransactionTemplate(transactionManager);
        }
    }

    /**
     * Removes rows this suite commits into tables that other {@code *IT} classes scan whole
     * ({@code transaction}, and the extra {@code user_security} row). The superclass reset also runs
     * before each test, but cleaning here keeps the shared database tidy immediately after the
     * committed race, matching the sibling repository tests' housekeeping convention.
     */
    @AfterEach
    void restoreSharedTables() {
        transactionRepository.deleteAll();
        userSecurityRepository.findByUsrId("RACEUSR1").ifPresent(userSecurityRepository::delete);
    }

    /**
     * Lost-update proof for the pessimistic write lock (review finding&nbsp;#14). Six workers each
     * apply {@link #ROUNDS} independent {@code findByIdForUpdate} &rarr; add &rarr; {@code saveAndFlush}
     * transactions to the same account. The {@code SELECT ... FOR UPDATE} row lock serializes every
     * read-modify-write, so the committed balance must equal the seed balance plus exactly
     * {@code WORKERS * ROUNDS} dollars &mdash; no increment lost, no deadlock (a single row is locked
     * in one consistent order).
     */
    @Test
    @DisplayName("#14: concurrent read-modify-write on one account loses no update (PESSIMISTIC_WRITE)")
    void concurrentBalanceUpdatesLoseNothing() throws Exception {
        initTemplate();

        BigDecimal initialBalance = accountRepository.findById(RACE_ACCT_ID).orElseThrow().getCurrBal();

        CyclicBarrier startGate = new CyclicBarrier(WORKERS);
        List<Throwable> failures = new CopyOnWriteArrayList<>();
        ExecutorService pool = Executors.newFixedThreadPool(WORKERS);
        try {
            List<Future<?>> futures = new ArrayList<>();
            for (int w = 0; w < WORKERS; w++) {
                futures.add(pool.submit(() -> {
                    try {
                        startGate.await(30, TimeUnit.SECONDS);
                        for (int r = 0; r < ROUNDS; r++) {
                            txTemplate.executeWithoutResult(status -> {
                                Account locked = accountRepository.findByIdForUpdate(RACE_ACCT_ID)
                                        .orElseThrow();
                                locked.setCurrBal(locked.getCurrBal().add(ONE_DOLLAR));
                                accountRepository.saveAndFlush(locked);
                            });
                        }
                    } catch (Throwable ex) {
                        failures.add(ex);
                    }
                }));
            }
            pool.shutdown();
            assertThat(pool.awaitTermination(90, TimeUnit.SECONDS))
                    .as("all workers finished within the timeout (no deadlock/hang)").isTrue();
            for (Future<?> f : futures) {
                f.get();
            }
        } finally {
            pool.shutdownNow();
        }

        assertThat(failures).as("no worker threw (every locked update succeeded)").isEmpty();

        BigDecimal expected = initialBalance.add(ONE_DOLLAR.multiply(BigDecimal.valueOf((long) WORKERS * ROUNDS)));
        BigDecimal actual = accountRepository.findById(RACE_ACCT_ID).orElseThrow().getCurrBal();
        assertThat(actual)
                .as("committed balance reflects every one of the %d serialized increments", WORKERS * ROUNDS)
                .isEqualByComparingTo(expected);

        // Restore the seed balance immediately (the superclass reset also runs before the next test).
        txTemplate.executeWithoutResult(status -> {
            Account a = accountRepository.findByIdForUpdate(RACE_ACCT_ID).orElseThrow();
            a.setCurrBal(initialBalance);
            accountRepository.saveAndFlush(a);
        });
    }

    /**
     * Duplicate-insert proof for {@code Transaction} (review finding&nbsp;#32). Six workers race to
     * {@code saveAndFlush} a transaction with the <em>same</em> primary key. Because {@code Transaction}
     * implements {@code Persistable} with {@code isNew()==true} for a fresh instance, {@code save}
     * issues a true {@code INSERT} (never a silent {@code merge}); PostgreSQL's primary-key uniqueness
     * therefore lets exactly one worker commit while the rest fail with
     * {@link DataIntegrityViolationException} (the {@code DUPREC} / {@code FILE STATUS 22} parity path).
     */
    @Test
    @DisplayName("#32: concurrent duplicate Transaction inserts -> exactly one wins, losers get DUPREC")
    void concurrentDuplicateTransactionInsertsYieldOneWinner() throws Exception {
        initTemplate();

        String tranId = "RACETRAN00000001";
        RaceOutcome outcome = runInsertRace(() ->
                txTemplate.executeWithoutResult(status -> {
                    Transaction tx = new Transaction();
                    tx.setTranId(tranId);
                    tx.setCardNum("4111111111111111");
                    tx.setTranTypeCd("01");
                    tx.setTranCatCd(1);
                    tx.setTranAmt(new BigDecimal("10.00"));
                    transactionRepository.saveAndFlush(tx);
                }));

        assertThat(outcome.unexpected).as("no worker threw an unexpected exception").isEmpty();
        assertThat(outcome.successes.get()).as("exactly one INSERT committed").isEqualTo(1);
        assertThat(outcome.duplicates.get())
                .as("every losing INSERT failed with DataIntegrityViolationException (DUPREC)")
                .isEqualTo(WORKERS - 1);
        assertThat(transactionRepository.findById(tranId)).as("the single surviving row exists").isPresent();
    }

    /**
     * Duplicate-insert proof for {@code UserSecurity} (review finding&nbsp;#32, add-user parity). Six
     * workers race to add a user with the same non-seeded id. {@code UserSecurity} is also
     * {@code Persistable}, so the add path is a true {@code INSERT}: exactly one commits and the rest
     * fail with {@link DataIntegrityViolationException}, and the seeded 10-row table gains exactly one
     * row.
     */
    @Test
    @DisplayName("#32: concurrent duplicate UserSecurity inserts -> exactly one wins, losers get DUPREC")
    void concurrentDuplicateUserInsertsYieldOneWinner() throws Exception {
        initTemplate();

        long seededUserCount = userSecurityRepository.count();
        String usrId = "RACEUSR1";
        RaceOutcome outcome = runInsertRace(() ->
                txTemplate.executeWithoutResult(status -> {
                    UserSecurity u = new UserSecurity();
                    u.setUsrId(usrId);
                    u.setUsrFname("RACE");
                    u.setUsrLname("WINNER");
                    u.setUsrPwd("PWDRACE1");
                    u.setUsrType("U");
                    userSecurityRepository.saveAndFlush(u);
                }));

        assertThat(outcome.unexpected).as("no worker threw an unexpected exception").isEmpty();
        assertThat(outcome.successes.get()).as("exactly one INSERT committed").isEqualTo(1);
        assertThat(outcome.duplicates.get())
                .as("every losing INSERT failed with DataIntegrityViolationException (DUPREC)")
                .isEqualTo(WORKERS - 1);
        assertThat(userSecurityRepository.findByUsrId(usrId)).as("the single surviving user exists").isPresent();
        assertThat(userSecurityRepository.count())
                .as("the seeded table gained exactly one row").isEqualTo(seededUserCount + 1);
    }

    /**
     * Runs {@link #WORKERS} barrier-synchronized workers that each invoke {@code insertAttempt}
     * exactly once, tallying committed inserts, {@link DataIntegrityViolationException} duplicates,
     * and any unexpected throwable.
     *
     * @param insertAttempt the single-insert action each worker performs inside its own transaction
     * @return the aggregated race outcome
     */
    private RaceOutcome runInsertRace(Runnable insertAttempt) throws Exception {
        CyclicBarrier startGate = new CyclicBarrier(WORKERS);
        RaceOutcome outcome = new RaceOutcome();
        ExecutorService pool = Executors.newFixedThreadPool(WORKERS);
        try {
            List<Future<?>> futures = new ArrayList<>();
            for (int w = 0; w < WORKERS; w++) {
                futures.add(pool.submit(() -> {
                    try {
                        startGate.await(30, TimeUnit.SECONDS);
                        try {
                            insertAttempt.run();
                            outcome.successes.incrementAndGet();
                        } catch (DataIntegrityViolationException duplicate) {
                            outcome.duplicates.incrementAndGet();
                        }
                    } catch (Throwable ex) {
                        outcome.unexpected.add(ex);
                    }
                }));
            }
            pool.shutdown();
            assertThat(pool.awaitTermination(90, TimeUnit.SECONDS))
                    .as("all insert workers finished within the timeout (no deadlock/hang)").isTrue();
            for (Future<?> f : futures) {
                f.get();
            }
        } finally {
            pool.shutdownNow();
        }
        return outcome;
    }

    /** Mutable, thread-safe tally of an insert race's results. */
    private static final class RaceOutcome {
        private final AtomicInteger successes = new AtomicInteger();
        private final AtomicInteger duplicates = new AtomicInteger();
        private final List<Throwable> unexpected = new CopyOnWriteArrayList<>();
    }
}
