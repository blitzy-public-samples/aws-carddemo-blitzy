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
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

/**
 * Statement-generation I/O helper &mdash; the Java port of the COBOL batch subroutine
 * {@code app/cbl/CBSTM03B.CBL} (CardDemo_v1.0-15-g27d6c6f-68).
 *
 * <p><strong>Origin &mdash; what this replaces.</strong> In the legacy mainframe system,
 * {@code CBSTM03B} is the file-handling subroutine called by the statement-creation program
 * {@code CBSTM03A}. It receives a single linkage area {@code LK-M03B-AREA} and dispatches a file
 * operation by <em>DD name</em> ({@code LK-M03B-DD}) and <em>operation code</em>
 * ({@code LK-M03B-OPER}) through an {@code EVALUATE LK-M03B-DD} that fans out to one paragraph per
 * dataset:</p>
 * <ul>
 *   <li>{@code 1000-TRNXFILE-PROC} &mdash; the transaction file {@code TRNXFILE} (indexed,
 *       {@code ACCESS MODE SEQUENTIAL}, {@code RECORD KEY FD-TRNXS-ID}).</li>
 *   <li>{@code 2000-XREFFILE-PROC} &mdash; the card cross-reference file {@code XREFFILE}
 *       (indexed, {@code ACCESS MODE SEQUENTIAL}, {@code RECORD KEY FD-XREF-CARD-NUM}).</li>
 *   <li>{@code 3000-CUSTFILE-PROC} &mdash; the customer file {@code CUSTFILE} (indexed,
 *       {@code ACCESS MODE RANDOM}, {@code RECORD KEY FD-CUST-ID PIC 9(09)}, keyed read
 *       {@code M03B-READ-K}).</li>
 *   <li>{@code 4000-ACCTFILE-PROC} &mdash; the account file {@code ACCTFILE} (indexed,
 *       {@code ACCESS MODE RANDOM}, {@code RECORD KEY FD-ACCT-ID PIC 9(11)}, keyed read
 *       {@code M03B-READ-K}).</li>
 * </ul>
 *
 * <p><strong>Why a thin typed wrapper (no generic dispatch).</strong> The COBOL subroutine drives
 * every dataset through one generic linkage block and a single-character operation code
 * ({@code 'O'}=open, {@code 'C'}=close, {@code 'R'}=read sequential, {@code 'K'}=read keyed,
 * {@code 'W'}=write, {@code 'Z'}=rewrite). That untyped dispatch table exists only because COBOL
 * has no generics; in Java the four datasets are already strongly typed by their Spring Data JPA
 * repositories, so this class deliberately collapses the generic
 * {@code dispatch(operation, ddName, key, fldt)} surface into a small set of explicit, typed
 * accessor methods that {@code StatementGenerationTasklet} calls directly (AAP &sect;0.6.2
 * VSAM&rarr;JPA). The in-memory 51&times;10 transaction matrix that {@code CBSTM03A}'s
 * {@code 8500-READTRNX-READ} paragraph builds is composed by {@code StatementGenerationTasklet},
 * not here &mdash; this class remains a thin I/O wrapper.</p>
 *
 * <p><strong>Dataset&rarr;repository mapping.</strong> Each VSAM dataset maps to its repository,
 * replacing {@code OPEN}/{@code READ}/{@code CLOSE} of the indexed file with repository access:</p>
 * <ul>
 *   <li>{@code CUSTFILE} (keyed) &rarr; {@link CustomerRepository#findById(Object)}.</li>
 *   <li>{@code ACCTFILE} (keyed) &rarr; {@link AccountRepository#findById(Object)}.</li>
 *   <li>{@code XREFFILE} (sequential) &rarr; {@link CardXrefRepository#findAll(Sort)} ordered by
 *       {@code xrefCardNum} (the VSAM key-sequenced order of {@code FD-XREF-CARD-NUM}).</li>
 *   <li>{@code TRNXFILE} (sequential / by-card) &rarr; {@link TransactionRepository#findAll(Sort)}
 *       ordered by {@code cardNum} then {@code tranId}, and
 *       {@link TransactionRepository#findByCardNum(String)} for the per-card slice.</li>
 * </ul>
 *
 * <p><strong>INVALID KEY semantics (file status {@code '23'}).</strong> {@code CBSTM03B}'s keyed
 * reads ({@code 3000-CUSTFILE-PROC} / {@code 4000-ACCTFILE-PROC}) return a two-character file
 * status in {@code LK-M03B-RC}; a missing record yields {@code '23'} ({@code INVALID KEY}). The
 * Java keyed accessors preserve that contract by returning {@link Optional#empty()} when the row is
 * absent, so the caller branches on {@link Optional#isPresent()} exactly as the COBOL caller
 * branched on the moved file status. The sequential accessors return an empty {@link List} when the
 * dataset holds no rows (the COBOL {@code AT END} immediately-true case).</p>
 *
 * <p><strong>Read-only.</strong> Mirroring {@code CBSTM03B}, which opens every dataset
 * {@code OPEN INPUT} and implements only the {@code O}/{@code C}/{@code R}/{@code K} branches (the
 * {@code W}/{@code Z} write/rewrite codes are declared on the linkage 88-levels but never executed
 * by the procedure paragraphs), this helper exposes read access only. Statement <em>output</em> is
 * produced by {@link StatementHtmlBuilder} and the statement writer beans, never by this reader.
 * Every read accessor is annotated {@code @Transactional(readOnly = true)} (PR-24) to enable
 * Hibernate read-only optimizations and a consistent snapshot for the duration of each call; the
 * Spring {@code org.springframework.transaction.annotation.Transactional} annotation is used in
 * preference to {@code jakarta.transaction.Transactional} for its richer attribute support
 * ({@code readOnly}) (PR-28).</p>
 *
 * <p><strong>Lock ordering (PR-23).</strong> When {@code StatementGenerationTasklet} composes a
 * statement for a card it invokes these accessors in the canonical order
 * {@code findCustomer &rarr; findAccount &rarr; findAllXrefsOrdered &rarr;
 * findTransactionsByCardNumber} (CUSTOMER &rarr; ACCOUNT &rarr; CARD &rarr; TRANSACTION), matching
 * the documented VSAM lock-acquisition convention to prevent deadlocks.</p>
 *
 * <p><strong>Threading &amp; injection.</strong> This {@code @Component} is a stateless singleton:
 * it holds no mutable instance state, so concurrent statement builds never interfere. Its four
 * repository collaborators are supplied through the Lombok-generated constructor over {@code final}
 * fields (PR-29; no {@code @Autowired} field injection). All persistence access is delegated to the
 * repositories, so this helper itself carries no {@code jakarta.persistence} dependency.</p>
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

    /**
     * Backs the {@code TRNXFILE} dataset ({@code 1000-TRNXFILE-PROC}). Provides the inherited
     * {@code findAll(Sort)} for the full key-sequenced scan and the derived
     * {@code findByCardNum(String)} for the per-card slice.
     */
    private final TransactionRepository transactionRepository;

    /**
     * Backs the {@code XREFFILE} dataset ({@code 2000-XREFFILE-PROC}). Provides the inherited
     * {@code findAll(Sort)} used to reproduce the VSAM key-sequenced read by {@code xrefCardNum}.
     */
    private final CardXrefRepository cardXrefRepository;

    /**
     * Backs the {@code CUSTFILE} dataset ({@code 3000-CUSTFILE-PROC}). Provides the inherited
     * {@code findById(Long)} keyed read on {@code FD-CUST-ID}.
     */
    private final CustomerRepository customerRepository;

    /**
     * Backs the {@code ACCTFILE} dataset ({@code 4000-ACCTFILE-PROC}). Provides the inherited
     * {@code findById(Long)} keyed read on {@code FD-ACCT-ID}.
     */
    private final AccountRepository accountRepository;

    /**
     * Reads a single customer by key &mdash; the Java equivalent of {@code CBSTM03B}
     * {@code 3000-CUSTFILE-PROC} with {@code LK-M03B-OPER = 'K'} ({@code M03B-READ-K}), which
     * moves {@code LK-M03B-KEY} into {@code FD-CUST-ID} and issues {@code READ CUST-FILE}.
     *
     * <p>Returns {@link Optional#empty()} when no customer matches the supplied id, preserving the
     * COBOL file status {@code '23'} ({@code INVALID KEY}) semantics so the statement tasklet can
     * skip or flag the orphaned cross-reference exactly as the legacy program did.</p>
     *
     * <p>First link in the PR-23 lock order (CUSTOMER &rarr; ACCOUNT &rarr; CARD &rarr;
     * TRANSACTION).</p>
     *
     * @param custId the customer id key (COBOL {@code FD-CUST-ID PIC 9(09)})
     * @return the customer when found, otherwise {@link Optional#empty()}
     */
    @Transactional(readOnly = true)
    public Optional<Customer> findCustomer(Long custId) {
        return customerRepository.findById(custId);
    }

    /**
     * Reads a single account by key &mdash; the Java equivalent of {@code CBSTM03B}
     * {@code 4000-ACCTFILE-PROC} with {@code LK-M03B-OPER = 'K'} ({@code M03B-READ-K}), which
     * moves {@code LK-M03B-KEY} into {@code FD-ACCT-ID} and issues {@code READ ACCT-FILE}.
     *
     * <p>Returns {@link Optional#empty()} when no account matches the supplied id, preserving the
     * COBOL file status {@code '23'} ({@code INVALID KEY}) semantics.</p>
     *
     * <p>Second link in the PR-23 lock order (CUSTOMER &rarr; ACCOUNT &rarr; CARD &rarr;
     * TRANSACTION).</p>
     *
     * @param acctId the account id key (COBOL {@code FD-ACCT-ID PIC 9(11)})
     * @return the account when found, otherwise {@link Optional#empty()}
     */
    @Transactional(readOnly = true)
    public Optional<Account> findAccount(Long acctId) {
        return accountRepository.findById(acctId);
    }

    /**
     * Reads every card cross-reference record in key-sequenced order &mdash; the Java equivalent of
     * {@code CBSTM03B} {@code 2000-XREFFILE-PROC} driven {@code OPEN INPUT} then repeated
     * {@code M03B-READ} ({@code 'R'}, sequential) until {@code AT END}.
     *
     * <p>{@code CBSTM03A} uses {@code XREFFILE} as the primary driver of statement generation
     * (its {@code 1000-XREFFILE-GET-NEXT} paragraph iterates the file front to back); the records
     * are returned sorted by {@code xrefCardNum} ascending to match the VSAM KSDS sequence of
     * {@code FD-XREF-CARD-NUM}.</p>
     *
     * <p>Third link in the PR-23 lock order (CUSTOMER &rarr; ACCOUNT &rarr; CARD &rarr;
     * TRANSACTION).</p>
     *
     * @return all cross-reference records ordered by {@code xrefCardNum} ascending; an empty list
     *         when none exist (never {@code null})
     */
    @Transactional(readOnly = true)
    public List<CardXref> findAllXrefsOrdered() {
        return cardXrefRepository.findAll(Sort.by("xrefCardNum"));
    }

    /**
     * Reads every transaction in card-then-id order &mdash; the bulk pre-load used by
     * {@code StatementGenerationTasklet} to populate the in-memory transaction matrix that
     * {@code CBSTM03A}'s {@code 8500-READTRNX-READ} paragraph builds from {@code TRNXFILE}
     * ({@code CBSTM03B} {@code 1000-TRNXFILE-PROC}, sequential {@code M03B-READ}).
     *
     * <p>The ordering &mdash; {@code cardNum} ascending, then {@code tranId} ascending &mdash;
     * reproduces the {@code CREASTMT.JCL} {@code STEP010} sort
     * {@code SORT FIELDS=(263,16,CH,A,1,16,CH,A)}: the primary key is {@code TRAN-CARD-NUM} at
     * record offset 263 (length 16) and the secondary key is {@code TRAN-ID} at offset 1
     * (length 16). Grouping by card lets the tasklet emit one statement section per card while
     * walking a single ordered stream.</p>
     *
     * <p>Fourth link in the PR-23 lock order (CUSTOMER &rarr; ACCOUNT &rarr; CARD &rarr;
     * TRANSACTION).</p>
     *
     * @return all transactions ordered by {@code cardNum} then {@code tranId} ascending; an empty
     *         list when none exist (never {@code null})
     */
    @Transactional(readOnly = true)
    public List<Transaction> findAllTransactionsOrderedByCardAndId() {
        return transactionRepository.findAll(Sort.by("cardNum", "tranId"));
    }

    /**
     * Reads the transactions belonging to a single card &mdash; the per-card slice of
     * {@code CBSTM03B} {@code 1000-TRNXFILE-PROC}. Where the bulk
     * {@link #findAllTransactionsOrderedByCardAndId()} pre-loads the whole file, this accessor
     * fetches just the rows for one card, mirroring the way {@code CBSTM03A} consumes the matrix
     * row for the card currently being statemented.
     *
     * <p>Delegates to {@link TransactionRepository#findByCardNum(String)} &mdash; the derived
     * finder resolves the {@code cardNum} entity field (COBOL {@code TRAN-CARD-NUM PIC X(16)}).
     * Note the method name is {@code findByCardNum}, not {@code findByCardNumber}: the
     * {@link Transaction} entity field is {@code cardNum}, so {@code findByCardNumber} would raise a
     * {@code PropertyReferenceException} at bootstrap.</p>
     *
     * <p>Final link in the PR-23 lock order (CUSTOMER &rarr; ACCOUNT &rarr; CARD &rarr;
     * TRANSACTION).</p>
     *
     * @param cardNum the 16-character card number (COBOL {@code TRAN-CARD-NUM PIC X(16)})
     * @return the transactions for this card; an empty list when none exist (never {@code null})
     */
    @Transactional(readOnly = true)
    public List<Transaction> findTransactionsByCardNumber(String cardNum) {
        return transactionRepository.findByCardNum(cardNum);
    }

    /**
     * Compatibility no-op for {@code CBSTM03B} {@code M03B-OPEN} ({@code 'O'}, {@code OPEN INPUT}).
     *
     * <p>The COBOL caller opens each dataset before reading and expects file status {@code '00'}
     * (success). Under JPA the connection lifecycle is managed by Spring &mdash; there is no file to
     * open &mdash; so this method does no work beyond emitting a debug trace. It is retained so the
     * statement tasklet can preserve the legacy open&rarr;read&rarr;close call shape (and to keep
     * the migration mapping from {@code CBSTM03B} explicit), always "succeeding".</p>
     */
    public void openFiles() {
        log.debug("StatementIoSubroutine: openFiles() invoked (no-op in JPA; CBSTM03B M03B-OPEN equivalent, RC '00')");
    }

    /**
     * Compatibility no-op for {@code CBSTM03B} {@code M03B-CLOSE} ({@code 'C'}, {@code CLOSE}).
     *
     * <p>The COBOL caller closes each dataset when finished. Under JPA there is no file handle to
     * release (Spring owns the connection), so this method only emits a debug trace, retained to
     * preserve the legacy open&rarr;read&rarr;close call shape.</p>
     */
    public void closeFiles() {
        log.debug("StatementIoSubroutine: closeFiles() invoked (no-op in JPA; CBSTM03B M03B-CLOSE equivalent, RC '00')");
    }
}
