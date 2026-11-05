package com.cardemo.repository;

import com.cardemo.entity.Transaction;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * Spring Data JPA repository for the {@link Transaction} entity.
 *
 * <p>Maps the TRANSACT VSAM KSDS dataset (350-byte records defined by
 * {@code CVTRA05Y.cpy}) to PostgreSQL via JPA. This is the most complex
 * repository in the CardDemo migration, providing:
 *
 * <ul>
 *   <li><strong>Paginated browse</strong> — translates the CICS
 *       {@code STARTBR / READNEXT / READPREV} pattern from
 *       {@code COTRN00C.cbl} (PF7 = page back, PF8 = page forward,
 *       10 records per page) into Spring Data {@link Page} queries.</li>
 *   <li><strong>AIX-equivalent chronological index</strong> — the VSAM
 *       alternate index (AIX) on {@code TRAN-ORIG-TS} (position 304,
 *       length 26) is replicated through
 *       {@link #findByOrigTimestampBetween(String, String)}, enabling
 *       date-range-based transaction queries for reporting and statement
 *       generation ({@code CBSTM03A.CBL}).</li>
 *   <li><strong>Browse-last for ID generation</strong> — the COBOL
 *       technique of reading the last record by key to determine the
 *       next transaction ID ({@code COTRN02C.cbl}'s
 *       {@code EXEC CICS STARTBR ... READPREV}) is translated to
 *       {@link #findFirstByOrderByTranIdDesc()}.</li>
 *   <li><strong>Card-based filtering</strong> — supports transaction
 *       list filtering by card number for online browse
 *       ({@code COTRN00C.cbl}) and statement generation
 *       ({@code CBSTM03A.CBL}).</li>
 * </ul>
 *
 * <h3>COBOL Program Mapping</h3>
 * <table>
 *   <caption>COBOL-to-Repository method mapping</caption>
 *   <tr><th>COBOL Program</th><th>CICS Operation</th><th>Repository Method</th></tr>
 *   <tr><td>COTRN00C</td><td>STARTBR/READNEXT/READPREV</td>
 *       <td>{@code findAll(Pageable)}, {@code findByCardNum(String, Pageable)}</td></tr>
 *   <tr><td>COTRN01C</td><td>READ by TRAN-ID</td>
 *       <td>{@code findById(String)}</td></tr>
 *   <tr><td>COTRN02C</td><td>STARTBR READPREV (browse-last) + WRITE</td>
 *       <td>{@code findFirstByOrderByTranIdDesc()}, {@code save(Transaction)}</td></tr>
 *   <tr><td>CBTRN02C</td><td>WRITE (batch posting)</td>
 *       <td>{@code save(Transaction)}, {@code saveAll(Iterable)}</td></tr>
 *   <tr><td>CBSTM03A</td><td>READ by timestamp range</td>
 *       <td>{@code findByOrigTimestampBetween(String, String)}</td></tr>
 * </table>
 *
 * <h3>Inherited JpaRepository Operations</h3>
 * <p>Standard CRUD operations inherited from {@link JpaRepository}:
 * {@code findById}, {@code save}, {@code saveAll}, {@code findAll},
 * {@code deleteAll}, {@code deleteById}, {@code existsById}, {@code count}.
 *
 * @see Transaction
 * @see <a href="app/cpy/CVTRA05Y.cpy">COBOL TRAN-RECORD copybook</a>
 * @see <a href="app/cbl/COTRN00C.cbl">Transaction List (online browse)</a>
 * @see <a href="app/cbl/COTRN02C.cbl">Transaction Add (ID generation)</a>
 * @see <a href="app/cbl/CBTRN02C.cbl">Daily Posting (batch write)</a>
 */
@Repository
public interface TransactionRepository extends JpaRepository<Transaction, String> {

    /**
     * Finds transactions whose origination timestamp falls within the
     * specified range (inclusive).
     *
     * <p><strong>VSAM AIX equivalent:</strong> Maps to the alternate
     * index (AIX) on {@code TRAN-ORIG-TS} (position 304, length 26)
     * in the TRANSACT VSAM KSDS dataset. The database index
     * {@code idx_transaction_orig_ts} on column {@code tran_orig_ts}
     * supports this query efficiently.
     *
     * <p>Timestamps use ISO-8601 extended format:
     * {@code YYYY-MM-DD-HH.MM.SS.mmmmmm} (26 characters with
     * microsecond precision). Lexicographic string comparison is
     * correct because the format sorts chronologically.
     *
     * <p>Used by statement generation ({@code CBSTM03A.CBL}) and
     * reporting ({@code CORPT00C.cbl}) for date-range-based
     * transaction retrieval.
     *
     * @param startTimestamp lower bound of the timestamp range
     *                       (inclusive), in ISO-8601 extended format
     * @param endTimestamp   upper bound of the timestamp range
     *                       (inclusive), in ISO-8601 extended format
     * @return list of transactions within the timestamp range,
     *         ordered by origination timestamp ascending
     */
    List<Transaction> findByOrigTimestampBetween(String startTimestamp,
                                                  String endTimestamp);

    /**
     * Finds transactions associated with a specific card number, with
     * pagination support.
     *
     * <p>Translates the card-based transaction browse pattern from
     * {@code COTRN00C.cbl} where transactions are filtered by the
     * {@code TRAN-CARD-NUM} field (16 characters). Also used by
     * {@code CBSTM03A.CBL} (statement generation) to retrieve
     * transactions for a specific card/account.
     *
     * <p>Pagination parameters map to the CICS browse model:
     * page size 10 matches {@code WS-MAX-SCREEN-LINES} from the
     * original COBOL program.
     *
     * @param cardNum  the 16-character card number to filter by
     * @param pageable pagination and sorting parameters
     * @return a page of transactions for the specified card number
     */
    Page<Transaction> findByCardNum(String cardNum, Pageable pageable);

    /**
     * Finds the transaction with the highest (last) transaction ID.
     *
     * <p><strong>Browse-last for ID generation:</strong> Faithfully
     * replicates the COBOL technique from {@code COTRN02C.cbl} where
     * a new transaction ID is generated by browsing to the last record
     * in the TRANSACT VSAM KSDS:
     * <pre>
     *   EXEC CICS STARTBR DATASET(WS-TRANSACT-FILE)
     *       RIDFLD(WS-TRAN-ID) KEYLENGTH(0) GENERIC RESP(...)
     *   EXEC CICS READPREV DATASET(WS-TRANSACT-FILE)
     *       INTO(TRAN-RECORD) RIDFLD(WS-TRAN-ID) RESP(...)
     * </pre>
     *
     * <p>Returns {@link Optional#empty()} when the table is empty
     * (equivalent to VSAM NOTFND condition), allowing the caller to
     * initialize the first transaction ID.
     *
     * <p>Per AAP Section 0.7.4: "Transaction ID generation must
     * replicate the COBOL browse-last technique: read the last
     * transaction by key, increment, and assign."
     *
     * @return an {@link Optional} containing the transaction with the
     *         highest {@code tranId}, or {@link Optional#empty()} if
     *         no transactions exist
     */
    Optional<Transaction> findFirstByOrderByTranIdDesc();

    /**
     * Retrieves all transactions ordered by transaction ID ascending.
     *
     * <p>Provides a deterministic ordering that matches the VSAM KSDS
     * natural key order (ascending by {@code TRAN-ID}). Used for batch
     * processing scenarios where all transactions must be iterated in
     * key sequence, such as the transaction sort step
     * ({@code COMBTRAN} JCL job equivalent) and full-file scans
     * during statement generation.
     *
     * <p>For large datasets, prefer {@link #findAll(Pageable)} with
     * appropriate sorting to avoid loading the entire table into
     * memory.
     *
     * @return list of all transactions ordered by transaction ID
     *         ascending
     */
    List<Transaction> findAllByOrderByTranIdAsc();

    /**
     * Finds transactions with a transaction ID greater than or equal to the
     * specified value, with pagination support.
     *
     * <p>Replicates the COBOL {@code STARTBR} (Start Browse) semantics from
     * {@code COTRN00C.cbl} where a browse is positioned at a specific
     * transaction ID and reads forward:</p>
     * <pre>
     *   EXEC CICS STARTBR DATASET(WS-TRANSACT-FILE)
     *       RIDFLD(WS-TRAN-ID) RESP(...)
     *   EXEC CICS READNEXT DATASET(WS-TRANSACT-FILE)
     *       INTO(TRAN-RECORD) RIDFLD(WS-TRAN-ID) RESP(...)
     * </pre>
     *
     * <p>Used by {@code TransactionListService.listTransactions()} when a
     * specific {@code transactionId} filter is provided.</p>
     *
     * @param tranId   the starting transaction ID for the browse
     * @param pageable pagination and sorting specification
     * @return a page of transactions with IDs ≥ the specified value
     */
    Page<Transaction> findByTranIdGreaterThanEqual(String tranId, Pageable pageable);
}
