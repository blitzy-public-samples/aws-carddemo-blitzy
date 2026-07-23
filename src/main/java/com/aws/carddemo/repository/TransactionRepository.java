package com.aws.carddemo.repository;

import com.aws.carddemo.domain.Transaction;
import jakarta.persistence.QueryHint;
import org.hibernate.jpa.HibernateHints;
import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.QueryHints;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.stream.Stream;

/**
 * Spring Data JPA repository for the {@link Transaction} entity. It replaces the legacy
 * {@code EXEC CICS} / {@code FILE SECTION} VSAM I/O against the {@code TRANSACT} KSDS with relational
 * access over PostgreSQL, as part of the AWS CardDemo COBOL-to-Java migration (AAP 0.3.3, 0.4.1, 0.6.2).
 *
 * <p><strong>Origin and traceability (AAP 0.6.10):</strong>
 * Origin: legacy/cpy/CVTRA05Y.cpy (TRAN-RECORD, RECLN 350); VSAM TRANSACT; transaction-by-card
 * alt-index per legacy/jcl/TRANIDX.jcl (KEYS(26 304)) + legacy/csd/CARDDEMO.CSD.</p>
 *
 * <p><strong>Key semantics (AAP 0.6.2):</strong> the VSAM primary key {@code TRAN-ID PIC X(16)} is
 * preserved as the entity identifier {@code tranId} (column {@code tran_id}), so this repository is
 * typed {@code JpaRepository<Transaction, String>}. Primary-key CRUD is served by the inherited
 * {@code findById(String)} and {@code save(...)} operations, exercised by transaction posting
 * ({@code CBTRN02C}), transaction view ({@code COTRN01C}), and transaction add ({@code COTRN02C}).</p>
 *
 * <p><strong>Alternate index (AAP 0.4.1):</strong> the legacy transaction-by-card alternate index
 * (alternate key {@code TRAN-CARD-NUM}) is reproduced by the derived query
 * {@link #findByCardNum(String)}, backed by the secondary database index on {@code transaction(card_num)}
 * created by the Flyway {@code V3} migration. It is consumed by statement generation
 * ({@code CBSTM03A} / {@code CBSTM03B}), which reads all transactions for a single card.</p>
 *
 * <p><strong>Ordering fidelity (AAP 0.6.6):</strong> {@link #findByCardNumOrderByTranIdAsc(String)}
 * returns a card's transactions ascending by {@code TRAN-ID}, preserving the legacy
 * {@code SORT FIELDS=(TRAN-ID,A)} statement/report ordering. The unfiltered full-list browse used by
 * the transaction-list screen ({@code COTRN00C}; {@code STARTBR} / {@code READNEXT} / {@code READPREV})
 * is served by the inherited {@code findAll(Sort)} / {@code findAll(Pageable)} with the caller supplying
 * {@code Sort.by("tranId")}; no explicit method is declared for that here. Ordering parity relies on the
 * {@code C} / {@code POSIX} collation applied to the {@code tran_id CHAR(16)} column by Flyway.</p>
 *
 * <p>No finders beyond primary-key CRUD and the by-card access path are declared. This preserves the
 * legacy VSAM access paths exactly and introduces no feature expansion (AAP 0.7.1).</p>
 *
 * @see Transaction
 */
@Repository
public interface TransactionRepository extends JpaRepository<Transaction, String> {

    /**
     * Returns every transaction posted against the given card number, reproducing the legacy
     * transaction-by-card alternate index ({@code TRAN-CARD-NUM}, {@code legacy/jcl/TRANIDX.jcl}).
     * The derived query resolves against the {@link Transaction} property {@code cardNum}
     * (column {@code card_num}).
     *
     * @param cardNum the 16-character card number ({@code TRAN-CARD-NUM PIC X(16)}) to match
     * @return the matching transactions in no guaranteed order; an empty list when the card has none
     */
    List<Transaction> findByCardNum(String cardNum);

    /**
     * Returns every transaction posted against the given card number, ordered ascending by transaction
     * id. This preserves the legacy {@code SORT FIELDS=(TRAN-ID,A)} ordering relied upon by statement
     * and report generation ({@code CBSTM03A} / {@code CBSTM03B}). The derived query resolves against
     * the {@link Transaction} properties {@code cardNum} (column {@code card_num}) and {@code tranId}
     * (column {@code tran_id}).
     *
     * @param cardNum the 16-character card number ({@code TRAN-CARD-NUM PIC X(16)}) to match
     * @return the matching transactions ascending by {@code tranId}; an empty list when the card has none
     */
    List<Transaction> findByCardNumOrderByTranIdAsc(String cardNum);

    /**
     * Streams every transaction in card-number order (with {@code tranId} as a deterministic
     * secondary key) as a forward-only, cursor-backed {@link Stream}, reproducing the sequential
     * card-ordered read of {@code TRANSACT-FILE} performed by the daily transaction report program
     * {@code CBTRN03C} (after its {@code TRANREPT.prc} {@code SORT FIELDS=(TRAN-CARD-NUM,A)} step).
     *
     * <p><strong>Why a stream (AAP 0.6.3, review finding #21):</strong> the report drives a
     * control-break and pagination state machine over the full transaction file. Materializing the
     * entire table into a {@code List} (the former {@code findAll(Sort)} approach) loads every
     * {@link Transaction} entity into the persistence context at once, which does not scale and
     * defeats the chunk/streaming intent of a batch read. This method returns a JDBC-cursor-backed
     * stream (fetch size {@code 200}, read-only so Hibernate keeps no dirty-checking snapshots) so the
     * report processes one row at a time with bounded memory. The stream <strong>must</strong> be
     * consumed inside an active transaction and closed (try-with-resources); the batch step's
     * transaction boundary satisfies both.</p>
     *
     * <p><strong>Ordering fidelity (AAP 0.6.6):</strong> ties on {@code cardNum} are broken by
     * {@code tranId} ascending so the sequence is fully deterministic. The {@code tran_id} /
     * {@code card_num} {@code CHAR} columns use the {@code C} / {@code POSIX} collation, giving the
     * bytewise/ASCII ordering required to match the legacy EBCDIC-sorted sequence for the numeric and
     * uppercase-alphanumeric keys involved.</p>
     *
     * @return a lazily-populated, cursor-backed stream of all transactions ordered by
     *         {@code cardNum} then {@code tranId} ascending; never {@code null}
     */
    @QueryHints({
            @QueryHint(name = HibernateHints.HINT_FETCH_SIZE, value = "200"),
            @QueryHint(name = HibernateHints.HINT_READ_ONLY, value = "true")
    })
    @Query("select t from Transaction t order by t.cardNum asc, t.tranId asc")
    Stream<Transaction> streamAllByCardOrder();

    /**
     * Reads a bounded, ascending window of transactions whose id is <strong>greater than or equal
     * to</strong> {@code tranId}, ordered ascending by {@code TRAN-ID} and capped at {@code limit}
     * rows &mdash; the forward paging window of the online transaction-list browse.
     *
     * <p><strong>Origin / parity (review finding #21; AAP &sect;0.6.2):</strong> the transaction-list
     * program {@code legacy/cbl/COTRN00C.cbl} browses {@code TRANSACT} with {@code STARTBR}
     * (positioning at the first key <em>&ge;</em> the start key) then up to a page of
     * {@code READNEXT}s (plus a skip-one on PF8 and a one-record look-ahead). The initial migration
     * positioned by materialising the <em>entire</em> {@code TRANSACT} table with
     * {@code findAll(Sort)} and scanning it in memory &mdash; unbounded and non-scaling for a table
     * that can grow without limit. This finder reproduces the VSAM {@code STARTBR}-at-key plus
     * forward {@code READNEXT} sequence as one bounded, key-ordered query returning only the slice
     * the browse consumes in a single screen paint (the page plus the skip-one and look-ahead
     * reads), never more than {@code limit} rows. Ascending {@code TRAN-ID} order is the VSAM key
     * order; the {@code transaction.tran_id} {@code CHAR(16)} column uses the {@code C} collation, so
     * the ordering is the same byte-wise ordering the legacy KSDS browse relied on. The service
     * positions within this window with the identical greater-than-or-equal comparison the COBOL
     * browse used, so the emitted rows are byte-identical to the legacy paging. No predicate beyond
     * the key bound is applied, so no record is silently dropped &mdash; the browse has no in-loop
     * record filter, keeping this a pure, parity-faithful paging window.</p>
     *
     * @param tranId the inclusive lower-bound transaction id (right-padded to the 16-byte
     *               {@code TRAN-ID} width by the caller; the empty string selects the first page)
     * @param limit  the maximum number of rows to return (the page size plus the browse's skip-one
     *               and look-ahead reads); must be positive
     * @return the matching transactions, ascending by id, at most {@code limit} in size (possibly
     *         empty when no transaction id is at or beyond {@code tranId})
     */
    List<Transaction> findByTranIdGreaterThanEqualOrderByTranIdAsc(String tranId, Limit limit);

    /**
     * Reads a bounded, <strong>descending</strong> window of transactions whose id is
     * <strong>less than or equal to</strong> {@code tranId}, ordered descending by {@code TRAN-ID}
     * and capped at {@code limit} rows &mdash; the backward paging window of the transaction-list
     * browse.
     *
     * <p><strong>Origin / parity (review finding #21; AAP &sect;0.6.2):</strong> paragraph
     * {@code PROCESS-PAGE-BACKWARD} in {@code legacy/cbl/COTRN00C.cbl} browses {@code TRANSACT}
     * backward: {@code STARTBR} at the first key already displayed then up to a page of
     * {@code READPREV}s (plus a look-ahead). This finder returns the page-sized slice ending at
     * {@code tranId} in descending key order, capped at {@code limit}. The caller reverses the slice
     * to ascending before handing it to the shared in-memory browse cursor (which always walks an
     * ascending snapshot, positioning at the greater-than-or-equal index and reading backward from
     * it), so the backward-paging output is byte-identical to the legacy {@code READPREV} sequence.
     * As with the forward window, ordering uses the {@code C} collation to match the VSAM key order
     * and no predicate beyond the key bound is applied.</p>
     *
     * @param tranId the inclusive upper-bound transaction id (right-padded to the 16-byte
     *               {@code TRAN-ID} width by the caller)
     * @param limit  the maximum number of rows to return (the page size plus the browse's skip-one
     *               and look-ahead reads); must be positive
     * @return the matching transactions, <em>descending</em> by id, at most {@code limit} in size
     *         (the caller reverses them to ascending for the browse cursor)
     */
    List<Transaction> findByTranIdLessThanEqualOrderByTranIdDesc(String tranId, Limit limit);

    /**
     * Reports whether <strong>any</strong> transaction has an id strictly greater than
     * {@code tranId} &mdash; the bounded existence check behind the transaction-list next-page
     * indicator.
     *
     * <p><strong>Origin / parity (review finding #21):</strong> paragraph {@code PROCESS-PF8-KEY}
     * in {@code legacy/cbl/COTRN00C.cbl} sets {@code CDEMO-CT00-NEXT-PAGE-FLG} from a forward peek
     * that fired when one more record existed beyond the current last row. The initial migration
     * recomputed this by scanning the <em>entire</em> materialised {@code TRANSACT} table looking for
     * a key sorting after the current last key. This finder replaces that full scan with a single
     * bounded existence query ({@code exists ... where tran_id &gt; :tranId}), which the database
     * answers by touching at most one index entry. The strictly-greater comparison and the {@code C}
     * collation match the in-memory comparison it replaces, so the next-page indicator is unchanged.
     * </p>
     *
     * @param tranId the current page's last transaction id (16-byte {@code TRAN-ID} width)
     * @return {@code true} when at least one transaction sorts strictly after {@code tranId}
     */
    boolean existsByTranIdGreaterThan(String tranId);
}
