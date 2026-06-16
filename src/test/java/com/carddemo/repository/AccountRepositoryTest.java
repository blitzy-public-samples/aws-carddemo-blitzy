package com.carddemo.repository;

import com.carddemo.entity.Account;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code @DataJpaTest} slice test for {@link AccountRepository}.
 *
 * <p>Parity: replaces VSAM {@code ACCTDATA} keyed reads (copybook {@code app/cpy/CVACT01Y.cpy};
 * key length 11 per {@code app/catlg/LISTCAT.txt}). Verifies exact {@code NUMERIC(12,2)} money
 * values, the JPA {@code @Version} optimistic-lock increment that reproduces {@code COACTUPC}
 * {@code READ ... UPDATE} / {@code REWRITE}-conflict detection (AAP &sect;0.6.6, surfaced as
 * HTTP&nbsp;409 by the service layer), and the optional pessimistic {@code findByIdForUpdate}
 * ({@code PESSIMISTIC_WRITE}) used by batch posting ({@code CBTRN02C}). Seeded by
 * {@code V3__seed_master.sql} (50 accounts, all {@code version = 0}; ground-truth
 * {@code app/data/ASCII/acctdata.txt}).</p>
 *
 * <h2>Test infrastructure</h2>
 * <ul>
 *   <li>{@link DataJpaTest} bootstraps a JPA slice (entities + Spring Data repositories) and wraps
 *       every test method in a transaction that is <strong>rolled back</strong> afterwards, so the
 *       {@code @Version}-increment test below does not permanently alter the seed.</li>
 *   <li>{@link AutoConfigureTestDatabase}{@code (replace = NONE)} keeps the configured datasource
 *       (in-memory H2 in PostgreSQL-compatibility mode from {@code application-test.yml}) instead of
 *       substituting a default embedded database, so the PostgreSQL-dialect Flyway migrations
 *       {@code V1..V4} run unchanged and populate the {@code accounts} table.</li>
 *   <li>{@link ActiveProfiles}{@code ("test")} selects {@code application-test.yml} (H2 +
 *       {@code ddl-auto: validate} + Flyway).</li>
 * </ul>
 *
 * <p>This slice deliberately verifies only the {@code @Version} increment <em>mechanism</em> and the
 * lock-query contract; the end-to-end two-thread concurrent-conflict (HTTP&nbsp;409) behavior is
 * covered by the mandated {@code AccountConcurrencyTest} / service test, not here.</p>
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
class AccountRepositoryTest {

    @Autowired
    private AccountRepository accountRepository;

    @Autowired
    private TestEntityManager entityManager;

    /**
     * Primary-key read of the anchor account (acct_id = 1) returns every seeded scalar exactly:
     * the single-character active status, the three {@code NUMERIC(12,2)} money fields (compared with
     * {@code isEqualByComparingTo} so scale never affects equality), and the three {@code LocalDate}
     * fields. Also asserts the money scale is 2, confirming the {@code NUMERIC(12,2)} mapping.
     */
    @Test
    void findById_returnsSeededAccount_withMoneyAndDates() {
        Account a = accountRepository.findById(1L).orElseThrow();
        assertThat(a.getActiveStatus()).isEqualTo("Y");
        assertThat(a.getCurrBal()).isEqualByComparingTo(new BigDecimal("194.00"));
        assertThat(a.getCreditLimit()).isEqualByComparingTo(new BigDecimal("2020.00"));
        assertThat(a.getCashCreditLimit()).isEqualByComparingTo(new BigDecimal("1020.00"));
        assertThat(a.getOpenDate()).isEqualTo(LocalDate.of(2014, 11, 20));
        assertThat(a.getExpirationDate()).isEqualTo(LocalDate.of(2025, 5, 20));
        assertThat(a.getReissueDate()).isEqualTo(LocalDate.of(2025, 5, 20));
        // money scale: stored as NUMERIC(12,2)
        assertThat(a.getCurrBal().scale()).isEqualTo(2);
    }

    /**
     * Every seeded account starts at {@code version = 0} (see {@code V3__seed_master.sql}); this is
     * the baseline the optimistic-lock increment test relies on.
     */
    @Test
    void findById_seededVersionIsZero() {
        Account a = accountRepository.findById(1L).orElseThrow();
        assertThat(a.getVersion()).isEqualTo(0L);
    }

    /**
     * Loads the account, mutates a non-key field, and flushes &mdash; Hibernate bumps the
     * {@code @Version} from 0 to 1. This is the unit-level evidence of the {@code COACTUPC}
     * {@code REWRITE}-conflict guard (a stale version would raise an optimistic-locking failure that
     * the service layer maps to HTTP&nbsp;409). The surrounding {@code @DataJpaTest} transaction
     * rolls back, so the seed balance and version are restored for the next test.
     */
    @Test
    void save_incrementsOptimisticVersion() {
        // Load, mutate a non-key field, flush -> @Version goes 0 -> 1 (COACTUPC REWRITE-conflict guard).
        Account a = accountRepository.findById(1L).orElseThrow();
        assertThat(a.getVersion()).isEqualTo(0L);
        a.setCurrBal(a.getCurrBal().add(new BigDecimal("10.00")));
        Account saved = accountRepository.saveAndFlush(a);
        assertThat(saved.getVersion()).isEqualTo(1L);
        // (this test transaction rolls back, so the seed is restored afterwards)
    }

    /**
     * The pessimistic {@code SELECT ... FOR UPDATE} query (batch-posting lock path) compiles and
     * returns the requested account; the {@code PESSIMISTIC_WRITE} lock mode is honored by H2 in the
     * test profile. Verifies presence and primary-key identity.
     */
    @Test
    void findByIdForUpdate_returnsAccount_underWriteLock() {
        Optional<Account> locked = accountRepository.findByIdForUpdate(1L);
        assertThat(locked).isPresent();
        assertThat(locked.get().getAcctId()).isEqualTo(1L);
    }

    /**
     * A primary-key read for an identifier outside the seeded range yields an empty {@link Optional}
     * (the posting flow maps this "account not found" condition to reject code 101).
     */
    @Test
    void findById_unknownAccount_isEmpty() {
        assertThat(accountRepository.findById(99999L)).isEmpty();
    }

    /**
     * The repository sees exactly the 50 seeded rows ({@code V3__seed_master.sql}, sourced from the
     * 50-line {@code app/data/ASCII/acctdata.txt}).
     */
    @Test
    void count_matchesSeedRowCount() {
        // V3__seed_master.sql seeds 50 accounts (acctdata.txt has 50 rows)
        assertThat(accountRepository.count()).isEqualTo(50L);
    }
}
