package com.aws.carddemo.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.aws.carddemo.AbstractPostgresIntegrationTest;
import com.aws.carddemo.domain.Transaction;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;

/**
 * Integration tests for {@link TransactionRepository} against the Flyway-seeded Testcontainers
 * PostgreSQL provided by {@link AbstractPostgresIntegrationTest}. Unlike the other repository tests,
 * {@code transaction} seeds EMPTY (it is populated only by the posting batch job {@code CBTRN02C} /
 * {@code PostTransactionJobConfig} at runtime, never by the Flyway {@code V2} reference-data
 * migration), so this is the single <em>writer</em> among the repository integration tests: it
 * inserts rows, asserts money round-trip fidelity for the {@code NUMERIC(11,2)} amount and the
 * transaction-by-card derived queries (backed by the {@code V3} index
 * {@code idx_transaction_card_num} on column {@code card_num}), and verifies that
 * {@code findByCardNumOrderByTranIdAsc} reproduces the legacy {@code SORT FIELDS=(TRAN-ID,A)} bytewise
 * ascending order (guaranteed by the {@code CHAR(16) COLLATE "C"} key; AAP &sect;0.6.6), then cleans
 * up.
 *
 * <p>Parity oracles (AAP &sect;0.6.10):
 * <ul>
 *   <li>{@code legacy/cpy/CVTRA05Y.cpy} &mdash; {@code TRAN-RECORD}, RECLN 350 ({@code TRAN-ID X(16)}
 *       primary key; {@code TRAN-AMT S9(09)V99}; {@code TRAN-CARD-NUM X(16)} &rarr; column
 *       {@code card_num}).</li>
 *   <li>{@code legacy/data/ASCII/dailytran.txt} &mdash; the DALYTRAN batch feed (350-byte rows) the
 *       posting job reads to populate {@code transaction} at runtime; it is NOT a Flyway seed, which
 *       is why the table is empty at seed time.</li>
 * </ul>
 *
 * <p>The shared static container declared by the base class applies no {@code @Transactional}
 * rollback boundary, so writes made here are committed and persist across tests. The mandatory
 * {@code @AfterEach} {@link #restoreSeededEmptyState()} therefore calls
 * {@link TransactionRepository#deleteAll()} to restore the seeded-empty state and leave the shared
 * database exactly as found. Correctness relies on the suite's sequential (JUnit 5 default,
 * parallel-disabled) execution.</p>
 *
 * @see TransactionRepository
 * @see Transaction
 * @see AbstractPostgresIntegrationTest
 */
class TransactionRepositoryIT extends AbstractPostgresIntegrationTest {

    /** A 16-character card number ({@code TRAN-CARD-NUM PIC X(16)} width) used as the primary fixture. */
    private static final String CARD_A = "1111222233334444";

    /** A second, distinct 16-character card number used to prove per-card filtering. */
    private static final String CARD_B = "5555666677778888";

    @Autowired
    private TransactionRepository transactionRepository;

    /**
     * Restores the seeded-empty state of the {@code transaction} table after every test. The base
     * class shares a single static container with no {@code @Transactional} rollback, so committed
     * writes would otherwise leak into subsequent tests; deleting all rows leaves the shared database
     * as found.
     */
    @AfterEach
    void restoreSeededEmptyState() {
        transactionRepository.deleteAll();
    }

    /**
     * Builds an unsaved {@link Transaction} populated with only the fields exercised by the
     * assertions. The {@code transaction} table declares no foreign keys and marks every column except
     * {@code tran_id} nullable, so the arbitrary card numbers used here need not reference any seeded
     * card and the remaining columns are intentionally left unset.
     *
     * @param tranId  the 16-character transaction id (primary key, {@code TRAN-ID PIC X(16)})
     * @param cardNum the 16-character card number ({@code TRAN-CARD-NUM PIC X(16)} &rarr; {@code card_num})
     * @param amount  the exact decimal amount string mapped to the {@code NUMERIC(11,2)} column
     * @return a transient {@link Transaction} ready to persist
     */
    private Transaction newTransaction(String tranId, String cardNum, String amount) {
        Transaction tx = new Transaction();
        tx.setTranId(tranId);
        tx.setCardNum(cardNum);
        tx.setTranTypeCd("01");
        tx.setTranCatCd(1);
        tx.setTranAmt(new BigDecimal(amount));
        return tx;
    }

    @Test
    @DisplayName("transaction table seeds empty (populated only by the posting batch job)")
    void tableSeedsEmpty() {
        assertThat(transactionRepository.count()).isZero();
    }

    @Test
    @DisplayName("saved transaction round-trips with an exact NUMERIC(11,2) amount")
    void saveRoundTripsMoneyExactly() {
        transactionRepository.save(newTransaction("0000000000000001", CARD_A, "100.00"));

        Transaction reloaded = transactionRepository.findById("0000000000000001").orElseThrow();
        assertThat(reloaded.getCardNum().trim()).isEqualTo(CARD_A);
        assertThat(reloaded.getTranAmt()).isEqualByComparingTo("100.00");
    }

    @Test
    @DisplayName("findByCardNum returns only the matching card's transactions")
    void findByCardNumFiltersByCard() {
        transactionRepository.save(newTransaction("0000000000000001", CARD_A, "10.00"));
        transactionRepository.save(newTransaction("0000000000000002", CARD_A, "20.00"));
        transactionRepository.save(newTransaction("0000000000000003", CARD_B, "30.00"));

        assertThat(transactionRepository.findByCardNum(CARD_A)).hasSize(2);
        assertThat(transactionRepository.findByCardNum(CARD_B)).hasSize(1);
        assertThat(transactionRepository.findByCardNum("0000000000000000")).isEmpty();
    }

    @Test
    @DisplayName("findByCardNumOrderByTranIdAsc reproduces legacy SORT FIELDS=(TRAN-ID,A) order")
    void findByCardNumOrderedReproducesLegacySort() {
        transactionRepository.save(newTransaction("0000000000000030", CARD_A, "3.00"));
        transactionRepository.save(newTransaction("0000000000000010", CARD_A, "1.00"));
        transactionRepository.save(newTransaction("0000000000000020", CARD_A, "2.00"));

        List<Transaction> ordered = transactionRepository.findByCardNumOrderByTranIdAsc(CARD_A);
        assertThat(ordered).extracting(tx -> tx.getTranId().trim())
                .containsExactly("0000000000000010", "0000000000000020", "0000000000000030");
    }

    /**
     * Review finding #32 (insert semantics parity). Proves that a second {@code save} of a
     * <em>brand-new</em> {@link Transaction} object carrying an <em>already-committed</em>
     * {@code TRAN-ID} is executed as a true SQL {@code INSERT} that collides with the existing
     * primary key &mdash; the VSAM {@code FILE STATUS "22"} / CICS {@code WRITE DUPKEY} (DUPREC)
     * equivalent &mdash; rather than as a silent {@code UPDATE}.
     *
     * <p><strong>Why this proves the fix.</strong> {@link Transaction} now implements
     * {@code org.springframework.data.domain.Persistable} and reports {@code isNew() == true} on
     * fresh construction, so Spring Data's {@code SimpleJpaRepository.save} routes to
     * {@code EntityManager.persist} (an unconditional {@code INSERT}). Before the fix, the entity
     * had an assigned {@code @Id} and no {@code isNew} override, so {@code save} routed to
     * {@code EntityManager.merge} (a {@code SELECT}-then-{@code UPDATE} upsert): a re-run or a
     * duplicate feed record silently overwrote the ledger row and the {@code DuplicateKeyException}
     * catch guarding the insert sites was dead code. This test therefore fails on the pre-fix
     * merge behaviour (no exception, row overwritten) and passes only with the {@code persist}
     * (insert) semantics that restore batch/online DUPREC parity.
     *
     * <p>The committed row is asserted unchanged after the rejected insert, proving the failed
     * {@code INSERT} did not mutate the existing ledger entry.
     */
    @Test
    @DisplayName("#32 duplicate TRAN-ID INSERTs (Persistable), not merges: second save raises DUPREC and does not overwrite")
    void duplicateTranIdInsertsNotMergesReproducingDuprec() {
        // Commit the first ledger row in its own transaction (the base class declares no ambient
        // @Transactional boundary), exactly as the posting/interest batch writers commit a chunk.
        transactionRepository.saveAndFlush(newTransaction("0000000000000001", CARD_A, "100.00"));

        // A NEW object with the SAME primary key but different data. isNew()==true -> persist ->
        // INSERT -> unique-key violation (DUPREC), never a silent merge/UPDATE.
        Transaction duplicate = newTransaction("0000000000000001", CARD_B, "999.99");
        assertThatThrownBy(() -> transactionRepository.saveAndFlush(duplicate))
                .isInstanceOf(DataIntegrityViolationException.class);

        // Parity: the original committed row survives untouched.
        Transaction survivor = transactionRepository.findById("0000000000000001").orElseThrow();
        assertThat(survivor.getCardNum().trim()).isEqualTo(CARD_A);
        assertThat(survivor.getTranAmt()).isEqualByComparingTo("100.00");
    }
}
