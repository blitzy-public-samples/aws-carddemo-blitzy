package com.aws.carddemo.domain;

import static org.assertj.core.api.Assertions.assertThat;

import com.aws.carddemo.AbstractPostgresIntegrationTest;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.annotation.Transactional;

/**
 * Persistence integration test ({@code *IT} &rarr; Maven Failsafe) for the JPA entity
 * {@link Transaction} against a real, Flyway-migrated PostgreSQL 18 instance started by
 * Testcontainers. Part of the AWS CardDemo COBOL &rarr; Java 25 / Spring Boot 3.5.16 migration; it
 * proves that the entity round-trips faithfully through the versioned schema that ships in
 * production ({@code V0 -> V1__schema.sql -> V2__reference_data.sql -> V3__indexes.sql}) with
 * Hibernate {@code ddl-auto=validate}.
 *
 * <p><strong>COBOL oracle (AAP &sect;0.6.10 traceability):</strong> the entity under test is
 * migrated one-for-one from the copybook {@code TRAN-RECORD} (record length 350) defined in
 * {@code legacy/cpy/CVTRA05Y.cpy} (retained read-only for reference). Where the sibling
 * {@code TransactionTest} unit test locks the object-relational <em>mapping metadata</em> in
 * isolation, this test locks the <em>runtime behaviour</em> of that mapping against the actual
 * relational {@code transaction} table declared in {@code V1__schema.sql} (AAP &sect;0.4.1).</p>
 *
 * <p><strong>Empty-table special case (AAP &sect;0.4.1):</strong> unlike the nine reference tables,
 * the {@code transaction} table is deliberately <em>not</em> seeded &mdash;
 * {@code V2__reference_data.sql} inserts zero rows into it because transactions are produced at
 * runtime by the batch posting job ({@code CBTRN02C} / {@code POSTTRAN}), not loaded as reference
 * data. This test therefore intentionally deviates from the seeded-row assertion used by the other
 * persistence tests: it first asserts the table is empty, then performs a persist &rarr; find
 * round-trip to exercise the write/read path end-to-end.</p>
 *
 * <p><strong>Decimal fidelity (AAP &sect;0.6.1):</strong> the monetary amount
 * {@code TRAN-AMT PIC S9(09)V99} maps to {@link java.math.BigDecimal} at
 * {@code NUMERIC(11,2)}; the round-trip below asserts both numeric equality and a preserved scale of
 * two, and every {@code BigDecimal} is built from a {@code String} literal so no binary
 * floating-point representation is ever introduced.</p>
 *
 * <p><strong>{@code card_num} mapping (AAP &sect;0.4.1, &sect;0.6.2):</strong> the card number
 * {@code TRAN-CARD-NUM} is the single field whose {@code TRAN-} prefix is dropped, so it is exposed
 * as the property {@code cardNum} mapped to column {@code card_num}. That rename is what makes the
 * Spring Data derived query {@code TransactionRepository.findByCardNum(String)} and the secondary
 * index {@code idx_transaction_card_num} (created in {@code V3__indexes.sql}) resolve; the JPQL
 * query in {@link #findByCardNumColumnResolves()} proves the property-to-column binding is
 * queryable.</p>
 *
 * <p><strong>Timestamp persistence (AAP &sect;0.6.2):</strong> the two 26-character COBOL timestamp
 * fields {@code TRAN-ORIG-TS} and {@code TRAN-PROC-TS} map to {@link java.time.LocalDateTime} over
 * SQL {@code TIMESTAMP}; the round-trip asserts both survive persistence unchanged.</p>
 *
 * <p><strong>Infrastructure.</strong> Extends {@link AbstractPostgresIntegrationTest}, inheriting
 * the shared {@code postgres:18-alpine} container, the {@code test} profile, and the dynamic
 * datasource wiring &mdash; no Spring or container configuration is redeclared here. The class is
 * {@link Transactional} so every test method runs in its own transaction that is rolled back on
 * completion, keeping the {@code transaction} table empty for the next method and making each test
 * independent regardless of execution order. Persistence is exercised directly through a
 * container-managed {@link EntityManager} (no repository), so the test validates the entity mapping
 * itself rather than any repository abstraction. Docker must be reachable for the container to
 * start; when it is not, the behavioural parity contracts are additionally covered in-process by the
 * sibling {@code TransactionTest} unit test.</p>
 *
 * @see Transaction
 * @see AbstractPostgresIntegrationTest
 */
@Transactional
class TransactionPersistenceIT extends AbstractPostgresIntegrationTest {

    /**
     * A card number that is present in the {@code V2__reference_data.sql} seed (a real
     * {@code card}/{@code card_xref} row). Exactly sixteen characters so it fills the
     * {@code CHAR(16)} {@code card_num} column without padding. There is no foreign key from
     * {@code transaction} to {@code card} in the schema (the flat VSAM design is preserved, AAP
     * &sect;0.6.2), so this value is used for realism and traceability rather than referential
     * enforcement.
     */
    private static final String SEEDED_CARD_NUM = "0500024453765740";

    /** Container-managed persistence context bound to the current (rolled-back) test transaction. */
    @PersistenceContext
    private EntityManager em;

    /**
     * Builds a fully populated, transient {@link Transaction} mirroring the {@code TRAN-RECORD}
     * layout of {@code legacy/cpy/CVTRA05Y.cpy}. Every column is set (the amount via a
     * {@code String} {@code BigDecimal} constructor, never {@code double}); {@code tranId} is a
     * caller-supplied sixteen-character key so each test persists a distinct row.
     *
     * @param tranId the sixteen-character primary key ({@code TRAN-ID PIC X(16)}) for the new row
     * @return a new, unpersisted {@link Transaction} ready for {@code em.persist}
     */
    private static Transaction newSampleTransaction(String tranId) {
        Transaction tx = new Transaction();
        tx.setTranId(tranId);                                     // TRAN-ID        PIC X(16)
        tx.setTranTypeCd("01");                                   // TRAN-TYPE-CD   PIC X(02)
        tx.setTranCatCd(1);                                       // TRAN-CAT-CD    PIC 9(04)
        tx.setTranSource("POS");                                  // TRAN-SOURCE    PIC X(10)
        tx.setTranDesc("Integration test purchase");              // TRAN-DESC      PIC X(100)
        tx.setTranAmt(new BigDecimal("-123.45"));                 // TRAN-AMT       PIC S9(09)V99 (signed)
        tx.setMerchantId(999999999L);                             // TRAN-MERCHANT-ID   PIC 9(09)
        tx.setMerchantName("Test Merchant");                      // TRAN-MERCHANT-NAME PIC X(50)
        tx.setMerchantCity("Testville");                          // TRAN-MERCHANT-CITY PIC X(50)
        tx.setMerchantZip("1234567890");                          // TRAN-MERCHANT-ZIP  PIC X(10)
        tx.setCardNum(SEEDED_CARD_NUM);                           // TRAN-CARD-NUM -> card_num CHAR(16)
        tx.setOrigTs(LocalDateTime.of(2024, 1, 15, 10, 30, 0));   // TRAN-ORIG-TS PIC X(26)
        tx.setProcTs(LocalDateTime.of(2024, 1, 15, 10, 30, 5));   // TRAN-PROC-TS PIC X(26)
        return tx;
    }

    /**
     * The {@code transaction} table is populated by the batch posting job at runtime, never by the
     * reference-data seed, so it must contain zero rows at the start of a test. This method performs
     * no inserts, and the class-level rollback prevents any sibling method's inserts from committing,
     * so the count is deterministically zero regardless of test execution order.
     */
    @Test
    void transactionTableInitiallyEmpty() {
        long count = em.createQuery("select count(t) from Transaction t", Long.class)
                .getSingleResult();

        assertThat(count)
                .as("transaction is batch-populated, not seeded: V2__reference_data.sql inserts 0 rows")
                .isZero();
    }

    /**
     * Persists a new transaction, flushes and clears the persistence context to force a fresh
     * database read, and re-loads it by primary key &mdash; verifying the {@code NUMERIC(11,2)}
     * amount survives with value and scale intact, the {@code card_num} column mapping resolves, and
     * both {@code TIMESTAMP} fields round-trip to {@link LocalDateTime} unchanged. The transaction is
     * rolled back at method end.
     */
    @Test
    void persistAndFindNewTransactionRoundTrip() {
        Transaction toPersist = newSampleTransaction("TESTTRAN00000001");

        em.persist(toPersist);
        em.flush();
        em.clear();

        Transaction found = em.find(Transaction.class, "TESTTRAN00000001");
        assertThat(found)
                .as("persisted transaction must be retrievable by its TRAN-ID primary key")
                .isNotNull();

        assertThat(found.getTranAmt())
                .as("TRAN-AMT S9(09)V99 -> NUMERIC(11,2): signed monetary value must be preserved")
                .isEqualByComparingTo(new BigDecimal("-123.45"));
        assertThat(found.getTranAmt().scale())
                .as("NUMERIC(11,2) round-trip must preserve a scale of exactly 2 (decimal fidelity)")
                .isEqualTo(2);

        assertThat(found.getCardNum().strip())
                .as("TRAN-CARD-NUM -> card_num CHAR(16): property/column mapping must round-trip")
                .isEqualTo(SEEDED_CARD_NUM);

        assertThat(found.getOrigTs())
                .as("TRAN-ORIG-TS -> tran_orig_ts TIMESTAMP must round-trip unchanged")
                .isEqualTo(LocalDateTime.of(2024, 1, 15, 10, 30, 0));
        assertThat(found.getProcTs())
                .as("TRAN-PROC-TS -> tran_proc_ts TIMESTAMP must round-trip unchanged")
                .isEqualTo(LocalDateTime.of(2024, 1, 15, 10, 30, 5));
    }

    /**
     * Persists a transaction, then queries it back through a JPQL predicate on the {@code cardNum}
     * property. A single result proves that {@code cardNum} maps to the {@code card_num} column
     * (the {@code TRAN-} prefix having been dropped) &mdash; the mapping that backs
     * {@code TransactionRepository.findByCardNum(String)} and the {@code idx_transaction_card_num}
     * secondary index from {@code V3__indexes.sql}. The persist happens inside this method so the
     * class-level rollback cleans it up.
     */
    @Test
    void findByCardNumColumnResolves() {
        em.persist(newSampleTransaction("TESTTRAN00000002"));
        em.flush();
        em.clear();

        List<Transaction> results = em.createQuery(
                        "select t from Transaction t where t.cardNum = :cn", Transaction.class)
                .setParameter("cn", SEEDED_CARD_NUM)
                .getResultList();

        assertThat(results)
                .as("cardNum property must map to column card_num (backs findByCardNum / idx_transaction_card_num)")
                .hasSize(1);
        assertThat(results.get(0).getCardNum().strip())
                .as("the row returned by the card_num predicate must carry the queried card number")
                .isEqualTo(SEEDED_CARD_NUM);
    }
}
