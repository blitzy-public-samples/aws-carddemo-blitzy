package com.carddemo.util;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;

import org.springframework.stereotype.Component;

/**
 * Generates the 16-character transaction identifier for the <strong>online
 * transaction-add path</strong>, faithfully reproducing the legacy CICS COBOL
 * scheme of {@code COTRN02C.cbl} ("Add a new Transaction to TRANSACT file")
 * with a concurrency-safe PostgreSQL sequence read.
 *
 * <h2>Legacy provenance (REFERENCE: {@code app/cbl/COTRN02C.cbl})</h2>
 * <p>In the {@code ADD-TRANSACTION} paragraph the COBOL program derived the next
 * transaction id by browsing the {@code TRANSACT} VSAM file for the highest
 * existing key and adding one:</p>
 * <pre>
 *     MOVE HIGH-VALUES TO TRAN-ID            *&gt; position the browse at end-of-file
 *     PERFORM STARTBR-TRANSACT-FILE          *&gt; open browse
 *     PERFORM READPREV-TRANSACT-FILE         *&gt; read the LAST (highest) record
 *     PERFORM ENDBR-TRANSACT-FILE            *&gt; close browse
 *     MOVE TRAN-ID     TO WS-TRAN-ID-N       *&gt; WS-TRAN-ID-N PIC 9(16)
 *     ADD 1 TO WS-TRAN-ID-N                  *&gt; "max id + 1"
 *     MOVE WS-TRAN-ID-N TO TRAN-ID           *&gt; TRAN-ID PIC X(16)
 * </pre>
 * <p>On an empty file the {@code READPREV} returns {@code ENDFILE} and the
 * program moves {@code ZEROS} to {@code TRAN-ID}, so the first id becomes
 * {@code 0 + 1 = 1}. That "highest existing value + 1, starting from one"
 * behavior is, by definition, a monotonically increasing sequence.</p>
 *
 * <h2>Modern translation</h2>
 * <p>The fragile, race-prone {@code STARTBR}/{@code READPREV}/{@code ENDBR}
 * browse is replaced by a database <em>sequence</em>,
 * {@code transaction_id_seq}, declared in the Flyway migration
 * {@code src/main/resources/db/migration/V1__schema.sql} as
 * {@code CREATE SEQUENCE transaction_id_seq START WITH 1 INCREMENT BY 1}.
 * Because the sequence starts at one, the very first generated id is {@code 1},
 * exactly matching the COBOL empty-file semantics. The numeric value is then
 * left-padded with zeros to a fixed width of 16 characters to reproduce the
 * legacy {@code TRAN-ID PIC X(16)} field (and to fit the {@code tran_id
 * VARCHAR(16)} primary-key column of the {@code transactions} table). For
 * example, sequence value {@code 1} renders as {@code "0000000000000001"} and
 * {@code 12345} as {@code "0000000000012345"}.</p>
 *
 * <h2>Scope &mdash; online transaction-add path ONLY</h2>
 * <p><strong>This generator is intended exclusively for the online add flow</strong>
 * ({@code COTRN02C} &rarr; {@code TransactionService.add(...)}). It
 * <strong>MUST NOT</strong> be wired into any Spring Batch job, because the
 * batch programs assign transaction ids by entirely different rules:</p>
 * <ul>
 *   <li>{@code CBTRN02C} (daily transaction posting) carries the inbound
 *       {@code DALYTRAN-ID} through <em>verbatim</em>
 *       ({@code MOVE DALYTRAN-ID TO TRAN-ID}) &mdash; it never generates a new
 *       id, so {@code TransactionPostingJobConfig} must not call this class.</li>
 *   <li>{@code CBACT04C} (interest calculation) builds each interest
 *       transaction's id from the run's <em>parameter date</em> plus a numeric
 *       suffix ({@code PARM-DATE} concatenated with {@code WS-TRANID-SUFFIX})
 *       &mdash; so {@code InterestCalculationJobConfig} must not call this class
 *       either.</li>
 * </ul>
 *
 * <h2>Concurrency and transactional semantics</h2>
 * <ul>
 *   <li><strong>Stateless &amp; thread-safe:</strong> the bean holds no mutable
 *       instance state (no counters, caches, or {@code AtomicLong}); the
 *       database sequence is the single source of monotonicity. Sequence
 *       allocation is atomic at the database level, so concurrent invocations
 *       of {@code nextval} always receive distinct values and no Java-side
 *       synchronization is required. A single injected, container-managed
 *       {@link EntityManager} proxy is the only collaborator.</li>
 *   <li><strong>Non-transactional sequence:</strong> sequence values are not
 *       rolled back with the surrounding transaction (this is standard
 *       PostgreSQL behavior). If the caller's transaction rolls back after an id
 *       was drawn, that id is simply skipped &mdash; gaps are acceptable and
 *       expected of any real id generator. No attempt is made to "reserve and
 *       roll back" ids.</li>
 *   <li><strong>Caller-owned transaction:</strong> this method participates in
 *       whatever transaction the caller ({@code TransactionService.add},
 *       annotated {@code @Transactional}) is running in. It therefore declares
 *       no transactional semantics of its own.</li>
 * </ul>
 *
 * <h2>Cross-database portability</h2>
 * <p>The native query uses the {@code SELECT nextval('transaction_id_seq')}
 * form, which is honored by <em>both</em> PostgreSQL 15.x (the {@code prod}
 * profile) and H2 running in {@code MODE=PostgreSQL} (the {@code dev}/{@code test}
 * profiles). The SQL-standard {@code NEXT VALUE FOR ...} syntax is deliberately
 * avoided because PostgreSQL does not support it. The scalar result is read via
 * {@link Number#longValue()} because the JDBC drivers surface the {@code BIGINT}
 * result with different concrete types ({@code Long} versus {@code BigInteger});
 * widening through {@link Number} is robust across both.</p>
 *
 * <p>This is a <strong>tier-0 foundational</strong> utility: it depends only on
 * the JPA persistence context and Spring's stereotype annotation, never on any
 * sibling {@code com.carddemo} package, so it can never participate in a cyclic
 * dependency.</p>
 *
 * @see <a href="file:app/cbl/COTRN02C.cbl">COTRN02C.cbl</a> (ADD-TRANSACTION)
 */
@Component
public class TranIdGenerator {

    /**
     * {@link String#format(String, Object...)} pattern that left-pads a
     * non-negative {@code long} with leading zeros to a fixed width of 16
     * digits, reproducing the legacy {@code TRAN-ID PIC X(16)} fixed-length
     * field and fitting the {@code transactions.tran_id VARCHAR(16)} column.
     * Sequence value {@code 1} renders as {@code "0000000000000001"}.
     */
    private static final String ID_FORMAT = "%016d";

    /**
     * Portable native SQL that draws the next value of the
     * {@code transaction_id_seq} sequence created by {@code V1__schema.sql}.
     * The {@code nextval('...')} function form works on both PostgreSQL and H2
     * in {@code MODE=PostgreSQL}; the standard {@code NEXT VALUE FOR} form is
     * intentionally not used because PostgreSQL rejects it.
     */
    private static final String NEXT_VAL_SQL = "SELECT nextval('transaction_id_seq')";

    /**
     * Container-managed JPA persistence context. Spring injects a thread-safe,
     * shared {@link EntityManager} proxy that transparently routes each call to
     * the {@code EntityManager} bound to the current transaction, so this single
     * field is safe to share across concurrent requests. It is the bean's only
     * collaborator; there is no other (mutable) instance state.
     */
    @PersistenceContext
    private EntityManager entityManager;

    /**
     * Generates the next online transaction identifier as a 16-character,
     * zero-padded numeric string.
     *
     * <p>Reads {@code nextval('transaction_id_seq')} from the database &mdash;
     * the modern, concurrency-safe replacement for the COBOL
     * {@code STARTBR}/{@code READPREV}/{@code ENDBR} "highest id + 1" browse in
     * {@code COTRN02C} &mdash; and left-pads the result to width 16 to match the
     * legacy {@code TRAN-ID PIC X(16)} field. Because the sequence is declared
     * {@code START WITH 1}, the first call returns {@code "0000000000000001"};
     * each subsequent call returns a strictly larger, unique value.</p>
     *
     * <p>The 16-character width comfortably accommodates every value this sample
     * application will ever produce: the {@code long}-typed sequence stays well
     * below the {@code 9,999,999,999,999,999} ceiling implied by 16 decimal
     * digits, so the formatted string is always exactly 16 characters. The id is
     * unaffected by transaction rollback (see the class documentation on
     * non-transactional sequence semantics).</p>
     *
     * @return a newly allocated transaction id: a strictly increasing, unique,
     *         zero-padded 16-character numeric {@code String}
     *         (e.g. {@code "0000000000000001"})
     */
    public String generateTransactionId() {
        // Draw the next sequence value. nextval() is atomic at the database
        // level, so concurrent callers always receive distinct values without
        // any Java-side locking.
        final Object raw = entityManager
                .createNativeQuery(NEXT_VAL_SQL)
                .getSingleResult();

        // PostgreSQL returns the BIGINT as Long while H2 (PostgreSQL mode)
        // returns it as BigInteger; widening through Number handles both.
        final long next = ((Number) raw).longValue();

        // Left-pad with zeros to the legacy PIC X(16) fixed width.
        return String.format(ID_FORMAT, next);
    }
}
