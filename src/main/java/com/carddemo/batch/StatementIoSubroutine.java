package com.carddemo.batch;

import com.carddemo.entity.Account;
import com.carddemo.entity.CardXref;
import com.carddemo.entity.Customer;
import com.carddemo.entity.Transaction;
import com.carddemo.repository.AccountRepository;
import com.carddemo.repository.CardXrefRepository;
import com.carddemo.repository.CustomerRepository;
import com.carddemo.repository.TransactionRepository;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Component;

import java.util.Iterator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Centralized statement I/O helper &mdash; the Java port of the COBOL batch subroutine
 * {@code app/cbl/CBSTM03B.CBL} (CardDemo_v1.0-15-g27d6c6f-68).
 *
 * <p><strong>Origin &mdash; what this replaces.</strong> In the legacy mainframe system,
 * {@code CBSTM03B} is the file-handling subroutine called by the statement-creation program
 * {@code CBSTM03A}. It receives a single linkage area {@code LK-M03B-AREA} and dispatches a file
 * operation by <em>DD name</em> ({@code LK-M03B-DD}) and <em>operation code</em>
 * ({@code LK-M03B-OPER}) via an {@code EVALUATE LK-M03B-DD} that fans out to one paragraph per
 * dataset:</p>
 * <ul>
 *   <li>{@code 1000-TRNXFILE-PROC} &mdash; the transaction file {@code TRNXFILE} (indexed,
 *       sequential access), read into the statement build.</li>
 *   <li>{@code 2000-XREFFILE-PROC} &mdash; the card cross-reference file {@code XREFFILE}
 *       (indexed, sequential access).</li>
 *   <li>{@code 3000-CUSTFILE-PROC} &mdash; the customer file {@code CUSTFILE} (indexed, random
 *       access by key).</li>
 *   <li>{@code 4000-ACCTFILE-PROC} &mdash; the account file {@code ACCTFILE} (indexed, random
 *       access by key).</li>
 * </ul>
 *
 * <p>The COBOL operation codes (88-levels on {@code LK-M03B-OPER}) and the two-character file
 * status returned in {@code LK-M03B-RC} are preserved here as the {@link Operation} enum and the
 * {@code STATUS_*} constants respectively, so callers observe the same control semantics. Each
 * VSAM dataset is mapped to its Spring Data JPA repository per AAP &sect;0.6.2 (VSAM&rarr;JPA),
 * replacing {@code OPEN}/{@code READ}/{@code CLOSE} of an indexed file with repository access:</p>
 * <ul>
 *   <li>{@code TRNXFILE} &rarr; {@link TransactionRepository} (sequential, key-sequenced by
 *       {@code tran_id}).</li>
 *   <li>{@code XREFFILE} &rarr; {@link CardXrefRepository} (sequential, key-sequenced by
 *       {@code xref_card_num}).</li>
 *   <li>{@code CUSTFILE} &rarr; {@link CustomerRepository} (keyed {@code findById}).</li>
 *   <li>{@code ACCTFILE} &rarr; {@link AccountRepository} (keyed {@code findById}).</li>
 * </ul>
 *
 * <p><strong>Read-only.</strong> Mirroring {@code CBSTM03B}, which opens every dataset
 * {@code OPEN INPUT}, this helper exposes read access only: sequential cursors for the two
 * sequentially-scanned files and keyed lookups for the two randomly-accessed files. The
 * {@link Operation#WRITE} / {@link Operation#REWRITE} codes from the COBOL linkage 88-levels are
 * preserved in the {@link Operation} enum for contract fidelity, but &mdash; exactly as in the
 * COBOL subroutine &mdash; no write path is implemented here; statement <em>output</em> is
 * produced by {@link StatementHtmlBuilder} and the statement writers, not by this reader.</p>
 *
 * <p><strong>Status semantics (preserved from {@code LK-M03B-RC}).</strong>
 * {@link #STATUS_OK} (&quot;00&quot;) a record was returned; {@link #STATUS_EOF} (&quot;10&quot;)
 * the sequential cursor is exhausted (COBOL {@code AT END}); {@link #STATUS_NOT_FOUND}
 * (&quot;23&quot;) a keyed read found no record (COBOL {@code INVALID KEY}). Callers branch on
 * {@link IoRecord#isOk()} / {@link IoRecord#isEof()} / {@link IoRecord#isNotFound()} just as the
 * COBOL caller branched on the moved file status.</p>
 *
 * <p><strong>Threading.</strong> This {@code @Component} is a stateless singleton; all mutable
 * cursor state lives in the {@link SequentialCursor} instance returned by an {@code open*}
 * method, so concurrent statement builds do not interfere. Dependencies are injected via the
 * Lombok-generated constructor over {@code final} fields (PR-29; no field injection). All
 * persistence access is delegated to the repositories, so this helper itself carries no
 * {@code jakarta.persistence} dependency (PR-28).</p>
 *
 * @see StatementHtmlBuilder
 * @see com.carddemo.repository.TransactionRepository
 * @see com.carddemo.repository.CardXrefRepository
 * @see com.carddemo.repository.CustomerRepository
 * @see com.carddemo.repository.AccountRepository
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class StatementIoSubroutine {

    /** File status &quot;00&quot;: operation succeeded / record returned (COBOL {@code LK-M03B-RC}). */
    public static final String STATUS_OK = "00";

    /** File status &quot;10&quot;: end-of-file on a sequential read (COBOL {@code AT END}). */
    public static final String STATUS_EOF = "10";

    /** File status &quot;23&quot;: no record for the supplied key (COBOL {@code INVALID KEY}). */
    public static final String STATUS_NOT_FOUND = "23";

    /**
     * Logical datasets handled by {@code CBSTM03B}, mirroring the {@code EVALUATE LK-M03B-DD}
     * dispatch. The enum constant name doubles as the original 8-character COBOL DD name.
     */
    public enum DataSource {
        /** Transaction file (sequential scan) &rarr; {@link TransactionRepository}. */
        TRNXFILE,
        /** Card cross-reference file (sequential scan) &rarr; {@link CardXrefRepository}. */
        XREFFILE,
        /** Customer file (keyed read) &rarr; {@link CustomerRepository}. */
        CUSTFILE,
        /** Account file (keyed read) &rarr; {@link AccountRepository}. */
        ACCTFILE
    }

    /**
     * File operations supported by {@code CBSTM03B}, preserving the single-character codes of the
     * {@code LK-M03B-OPER} 88-levels ({@code M03B-OPEN}='O', {@code M03B-CLOSE}='C',
     * {@code M03B-READ}='R', {@code M03B-READ-K}='K', {@code M03B-WRITE}='W',
     * {@code M03B-REWRITE}='Z').
     */
    public enum Operation {
        /** {@code 'O'} &mdash; {@code OPEN INPUT}. */
        OPEN('O'),
        /** {@code 'C'} &mdash; {@code CLOSE}. */
        CLOSE('C'),
        /** {@code 'R'} &mdash; sequential {@code READ ... AT END}. */
        READ('R'),
        /** {@code 'K'} &mdash; keyed {@code READ ... INVALID KEY}. */
        READ_BY_KEY('K'),
        /** {@code 'W'} &mdash; {@code WRITE} (declared for contract fidelity; not used by the read-only subroutine). */
        WRITE('W'),
        /** {@code 'Z'} &mdash; {@code REWRITE} (declared for contract fidelity; not used by the read-only subroutine). */
        REWRITE('Z');

        private final char code;

        Operation(char code) {
            this.code = code;
        }

        /**
         * Returns the single-character COBOL operation code (the {@code LK-M03B-OPER} value).
         *
         * @return the COBOL operation code character
         */
        public char getCode() {
            return code;
        }
    }

    private final TransactionRepository transactionRepository;
    private final CardXrefRepository cardXrefRepository;
    private final CustomerRepository customerRepository;
    private final AccountRepository accountRepository;

    /**
     * Opens the {@code TRNXFILE} transaction dataset for sequential reading &mdash; the Java
     * equivalent of {@code CALL 'CBSTM03B' USING LK-M03B-AREA} with
     * {@code LK-M03B-DD = 'TRNXFILE'} and {@code LK-M03B-OPER = 'O'} (then repeated {@code 'R'}).
     *
     * <p>Records are returned in key-sequenced order by {@code tran_id} ascending, matching the
     * indexed-sequential access of the original VSAM {@code TRNXFILE}.</p>
     *
     * @return a fresh, single-use {@link SequentialCursor} positioned before the first record;
     *         drive it with {@link SequentialCursor#read()} until {@link IoRecord#isEof()} and
     *         then {@link SequentialCursor#close()} (or use try-with-resources)
     */
    public SequentialCursor<Transaction> openTransactions() {
        log.debug("CBSTM03B dispatch dd={} oper={} ({})",
                DataSource.TRNXFILE, Operation.OPEN.getCode(), Operation.OPEN);
        List<Transaction> records = transactionRepository.findAll(Sort.by(Sort.Direction.ASC, "tranId"));
        return new SequentialCursor<>(DataSource.TRNXFILE, records.iterator());
    }

    /**
     * Opens the {@code XREFFILE} card cross-reference dataset for sequential reading &mdash; the
     * Java equivalent of {@code CBSTM03B} with {@code LK-M03B-DD = 'XREFFILE'} and
     * {@code LK-M03B-OPER = 'O'} (then repeated {@code 'R'}).
     *
     * <p>Records are returned in key-sequenced order by {@code xref_card_num} ascending, matching
     * the indexed-sequential access of the original VSAM {@code XREFFILE}.</p>
     *
     * @return a fresh, single-use {@link SequentialCursor} over the cross-reference records
     */
    public SequentialCursor<CardXref> openCardXrefs() {
        log.debug("CBSTM03B dispatch dd={} oper={} ({})",
                DataSource.XREFFILE, Operation.OPEN.getCode(), Operation.OPEN);
        List<CardXref> records = cardXrefRepository.findAll(Sort.by(Sort.Direction.ASC, "xrefCardNum"));
        return new SequentialCursor<>(DataSource.XREFFILE, records.iterator());
    }

    /**
     * Reads a single customer by key &mdash; the Java equivalent of {@code CBSTM03B} with
     * {@code LK-M03B-DD = 'CUSTFILE'} and {@code LK-M03B-OPER = 'K'} ({@code READ ... INVALID
     * KEY}). Replaces the random keyed {@code READ CUST-FILE} on {@code FD-CUST-ID}.
     *
     * @param custId the customer id key (COBOL {@code FD-CUST-ID}); must not be {@code null}
     * @return {@link IoRecord} carrying {@link #STATUS_OK} and the customer when found, or
     *         {@link #STATUS_NOT_FOUND} ({@code INVALID KEY}) with no payload when absent
     * @throws NullPointerException if {@code custId} is {@code null}
     */
    public IoRecord<Customer> readCustomer(Long custId) {
        Objects.requireNonNull(custId, "custId");
        log.debug("CBSTM03B dispatch dd={} oper={} ({}) key={}",
                DataSource.CUSTFILE, Operation.READ_BY_KEY.getCode(), Operation.READ_BY_KEY, custId);
        Optional<Customer> found = customerRepository.findById(custId);
        return found.map(IoRecord::ok).orElseGet(IoRecord::notFound);
    }

    /**
     * Reads a single account by key &mdash; the Java equivalent of {@code CBSTM03B} with
     * {@code LK-M03B-DD = 'ACCTFILE'} and {@code LK-M03B-OPER = 'K'} ({@code READ ... INVALID
     * KEY}). Replaces the random keyed {@code READ ACCT-FILE} on {@code FD-ACCT-ID}.
     *
     * @param acctId the account id key (COBOL {@code FD-ACCT-ID}); must not be {@code null}
     * @return {@link IoRecord} carrying {@link #STATUS_OK} and the account when found, or
     *         {@link #STATUS_NOT_FOUND} ({@code INVALID KEY}) with no payload when absent
     * @throws NullPointerException if {@code acctId} is {@code null}
     */
    public IoRecord<Account> readAccount(Long acctId) {
        Objects.requireNonNull(acctId, "acctId");
        log.debug("CBSTM03B dispatch dd={} oper={} ({}) key={}",
                DataSource.ACCTFILE, Operation.READ_BY_KEY.getCode(), Operation.READ_BY_KEY, acctId);
        Optional<Account> found = accountRepository.findById(acctId);
        return found.map(IoRecord::ok).orElseGet(IoRecord::notFound);
    }

    /**
     * Immutable result of a single {@code CBSTM03B} operation, pairing the two-character file
     * status (the COBOL {@code LK-M03B-RC}) with the optional record payload (the COBOL
     * {@code LK-M03B-FLDT}).
     *
     * @param <T>        the record type returned for the operation
     * @param returnCode the two-character file status ({@link #STATUS_OK}, {@link #STATUS_EOF},
     *                   or {@link #STATUS_NOT_FOUND})
     * @param payload    the record when {@code returnCode} is {@link #STATUS_OK}; otherwise
     *                   {@code null}
     */
    public record IoRecord<T>(String returnCode, T payload) {

        /**
         * Creates a success ({@link #STATUS_OK}) result carrying the supplied record.
         *
         * @param payload the record read (must not be {@code null})
         * @param <T>     the record type
         * @return a success {@code IoRecord}
         * @throws NullPointerException if {@code payload} is {@code null}
         */
        public static <T> IoRecord<T> ok(T payload) {
            return new IoRecord<>(STATUS_OK, Objects.requireNonNull(payload, "payload"));
        }

        /**
         * Creates an end-of-file ({@link #STATUS_EOF}) result with no payload.
         *
         * @param <T> the record type
         * @return an EOF {@code IoRecord}
         */
        public static <T> IoRecord<T> eof() {
            return new IoRecord<>(STATUS_EOF, null);
        }

        /**
         * Creates a not-found ({@link #STATUS_NOT_FOUND}) result with no payload.
         *
         * @param <T> the record type
         * @return a not-found {@code IoRecord}
         */
        public static <T> IoRecord<T> notFound() {
            return new IoRecord<>(STATUS_NOT_FOUND, null);
        }

        /**
         * @return {@code true} when a record was returned ({@link #STATUS_OK})
         */
        public boolean isOk() {
            return STATUS_OK.equals(returnCode);
        }

        /**
         * @return {@code true} when the sequential cursor is exhausted ({@link #STATUS_EOF})
         */
        public boolean isEof() {
            return STATUS_EOF.equals(returnCode);
        }

        /**
         * @return {@code true} when a keyed read found no record ({@link #STATUS_NOT_FOUND})
         */
        public boolean isNotFound() {
            return STATUS_NOT_FOUND.equals(returnCode);
        }
    }

    /**
     * A single-use, forward-only cursor over a sequentially-scanned dataset, reproducing the
     * COBOL {@code OPEN INPUT} &rarr; repeated {@code READ ... AT END} &rarr; {@code CLOSE}
     * lifecycle of {@code CBSTM03B}. Each call to an {@code open*} method returns a fresh cursor,
     * keeping all mutable iteration state out of the shared {@link StatementIoSubroutine}
     * singleton. Implements {@link AutoCloseable} so it may be driven inside a
     * try-with-resources block.
     *
     * @param <T> the record type produced by this cursor
     */
    public static final class SequentialCursor<T> implements AutoCloseable {

        private final DataSource dataSource;
        private final Iterator<T> iterator;
        private boolean closed;

        private SequentialCursor(DataSource dataSource, Iterator<T> iterator) {
            this.dataSource = dataSource;
            this.iterator = iterator;
        }

        /**
         * Returns the next record in sequence, or an {@link IoRecord#eof()} result once the
         * dataset is exhausted &mdash; the Java equivalent of {@code READ ... AT END} with the
         * resulting file status moved to {@code LK-M03B-RC}.
         *
         * @return an {@link IoRecord} with {@link #STATUS_OK} and the next record, or
         *         {@link #STATUS_EOF} when no further records remain
         * @throws IllegalStateException if invoked after {@link #close()}
         */
        public IoRecord<T> read() {
            if (closed) {
                throw new IllegalStateException(
                        "read() called after close() on " + dataSource + " cursor");
            }
            if (iterator.hasNext()) {
                return IoRecord.ok(iterator.next());
            }
            return IoRecord.eof();
        }

        /**
         * Closes the cursor &mdash; the Java equivalent of {@code CLOSE}. Idempotent: a second
         * call is a no-op. After closing, {@link #read()} raises {@link IllegalStateException}.
         */
        @Override
        public void close() {
            this.closed = true;
        }

        /**
         * @return the dataset this cursor was opened over (the COBOL {@code LK-M03B-DD})
         */
        public DataSource getDataSource() {
            return dataSource;
        }
    }
}
