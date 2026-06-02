package com.carddemo.repository;

import com.carddemo.entity.Transaction;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;

/**
 * Spring Data JPA repository for {@link Transaction} posted-transaction records.
 *
 * <p>Replaces the VSAM {@code TRANSACT} KSDS keyed file plus its
 * {@code TRANSACT.AIX} alternate-index path. The underlying table is the
 * system-of-record transaction history mapping the 350-byte {@code TRAN-RECORD}
 * layout from {@code app/cpy/CVTRA05Y.cpy} (CardDemo_v1.0-15-g27d6c6f-68); it is
 * populated by:</p>
 * <ul>
 *   <li>Online transaction creation ({@code app/cbl/COTRN02C.cbl} &mdash;
 *       "Add a new Transaction to TRANSACT file", originally an
 *       {@code EXEC CICS WRITE} after a {@code STARTBR}/{@code READPREV} to derive
 *       the next {@code TRAN-ID}) &rarr; {@code TransactionService} via the
 *       inherited {@code save(...)}.</li>
 *   <li>Batch transaction posting ({@code app/cbl/CBTRN02C.cbl}, the POSTTRAN job)
 *       &rarr; {@code TransactionPostingJobConfig} (chunk pipeline, PR-03 validation
 *       codes 100/101/102/103).</li>
 *   <li>Batch interest calculation ({@code app/cbl/CBACT04C.cbl}) &rarr;
 *       {@code InterestCalculationJobConfig} &mdash; emits interest transactions whose
 *       16-character IDs are produced by {@code TransactionIdGenerator} per PR-10.</li>
 *   <li>Batch consolidation ({@code app/cbl/CBTRN03C.cbl} / {@code app/jcl/COMBTRAN.jcl})
 *       &rarr; {@code TransactionConsolidationJobConfig} (merge into the master
 *       transactions table).</li>
 * </ul>
 *
 * <p>It is read by the transaction list/view online flows
 * ({@code app/cbl/COTRN00C.cbl} keyed browse via {@code STARTBR}/{@code READNEXT}/
 * {@code READPREV}; {@code app/cbl/COTRN01C.cbl} keyed {@code READ} by {@code TRAN-ID})
 * and by statement generation ({@code app/cbl/CBSTM03A.CBL}, which enumerates the
 * transactions belonging to each card).</p>
 *
 * <p><strong>Primary key (PR-13).</strong> The {@link Transaction} entity's {@code @Id}
 * is {@code String tranId} mapping {@code TRAN-ID PIC X(16)} &rarr;
 * {@code tran_id VARCHAR(16) NOT NULL PRIMARY KEY}; this interface therefore extends
 * {@code JpaRepository<Transaction, String>}. The 16-character format follows PR-10:
 * {@code parmDate(10) + suffix(6)} (generation lives in {@code TransactionIdGenerator},
 * not here). The inherited {@code findById(String)} reproduces the single-record keyed
 * {@code READ} of {@code COTRN01C}; {@code save(...)} reproduces the {@code WRITE} of
 * {@code COTRN02C} and the batch posting/interest/consolidation writes; {@code findAll}
 * plus the {@code Pageable} overloads back the {@code COTRN00C} list browse (forward/
 * backward paging is computed statelessly at the <em>service</em> layer via
 * {@code PageRequest}, replacing the server-side {@code STARTBR}/{@code READNEXT}/
 * {@code READPREV} cursor).</p>
 *
 * <p><strong>Consumers.</strong> {@code TransactionService} (backs
 * {@code GET /api/transactions}, {@code GET /api/transactions/{tranId}},
 * {@code POST /api/transactions}); {@code BillPaymentService} (persists a payment
 * transaction atomically with the account-balance update inside a
 * {@code @Transactional} scope); {@code StatementService} /
 * {@code StatementGenerationJobConfig} (per-card enumeration for {@code CBSTM03A});
 * {@code TransactionPostingJobConfig} (bulk insert of accepted transactions per
 * {@code CBTRN02C} per-record processing); {@code TransactionConsolidationJobConfig}
 * (merge into the master transactions table per {@code CBTRN03C}/{@code COMBTRAN}).</p>
 *
 * <p><strong>AAP &sect;0.6.13 alternate-index replacement.</strong> The original VSAM
 * {@code TRANSACT.AIX} alternate index on {@code TRAN-ORIG-TS} (position 304, length 26)
 * is replaced by the PostgreSQL B-tree index {@code idx_transaction_orig_ts} on the
 * {@code orig_timestamp} column. The index is declared both via {@code @Index} on the
 * {@link Transaction} entity (keeping the entity self-describing) and physically by
 * Flyway {@code src/main/resources/db/migration/V2__indexes.sql}; PostgreSQL maintains
 * it automatically on every INSERT/UPDATE/DELETE, so the IDCAMS
 * {@code DELETE}&rarr;{@code DEFINE}&rarr;{@code BLDINDEX}&rarr;{@code DEFINE PATH}
 * rebuild sequence of {@code app/jcl/TRANIDX.jcl} has no Java/SQL counterpart. That
 * index backs {@link #findByOrigTimestampBetween(LocalDateTime, LocalDateTime)}.</p>
 *
 * <p><strong>PR-22 optimistic locking.</strong> The {@link Transaction} entity carries a
 * {@code @Version} field; concurrent updates raise {@code OptimisticLockException}, which
 * {@code GlobalExceptionHandler} maps to HTTP 409 Conflict (replacing the VSAM
 * {@code READ UPDATE} exclusive lock with non-blocking optimistic concurrency).</p>
 *
 * <p><strong>Pattern &amp; scope.</strong> Per the Repository Pattern (AAP &sect;0.3.3 #1)
 * this extends {@code JpaRepository} (not {@code CrudRepository}) to inherit the full
 * CRUD, sort, and paging API. The explicit {@code @Repository} stereotype marks the
 * interface for component scanning and activates Spring's
 * {@code PersistenceExceptionTranslationPostProcessor}, translating provider-specific
 * exceptions (including the {@code OptimisticLockException} above) into the
 * {@code org.springframework.dao.DataAccessException} hierarchy. This is a pure
 * data-access component and carries no business logic &mdash; transaction validation
 * (PR-03 codes 100/101/102/103), TCATBAL upsert (PR-06), and balance updates (PR-07)
 * live in the batch processor/writer beans and the service layer.</p>
 *
 * <p><strong>Note on the AAP / agent-prompt parameter-type discrepancy (resolved in
 * favour of the entity).</strong> AAP &sect;0.4.1.5 specifies
 * {@code findByOrigTimestampBetween(LocalDateTime start, LocalDateTime end)}, which is
 * correct for the committed entity. A later agent-prompt revision attempted to
 * "override" this to {@code String} parameters on the assumption that the entity
 * declared {@code origTimestamp} as a {@code String} (length 26) holding the raw DB2
 * external timestamp; however the entity that was actually generated and committed
 * declares {@code private LocalDateTime origTimestamp} mapped to the
 * {@code orig_timestamp TIMESTAMP} column (verified against the entity source, the
 * Flyway DDL {@code V1__schema.sql}, and the sibling
 * {@code DailyTransaction}/{@code RejectedTransaction} entities, all of which normalize
 * the 26-char DB2 form to {@link LocalDateTime} per AAP &sect;0.6.5). Spring Data
 * derives the {@code BETWEEN} query from the {@code origTimestamp} property type, so the
 * finder parameters MUST be {@link LocalDateTime}; declaring {@code String} parameters
 * would bind text arguments against a {@code TIMESTAMP} property and fail at query
 * execution. This interface therefore uses {@link LocalDateTime} parameters to remain
 * consistent with the entity, the schema, and the AAP &mdash; an entity-aligned
 * resolution mirroring the sibling {@code DailyTransactionRepository}. The 26-character
 * DB2 textual form ({@code YYYY-MM-DD-HH.MM.SS.MIL0000}, PR-11) is reproduced only at
 * I/O boundaries by {@code DateConversionUtil} / {@code DateConversionService}, and the
 * service layer performs any {@code String} &harr; {@link LocalDateTime} conversion
 * before invoking this finder.</p>
 *
 * @see com.carddemo.entity.Transaction
 * @see com.carddemo.repository.DailyTransactionRepository
 * @see com.carddemo.repository.RejectedTransactionRepository
 * @see org.springframework.data.jpa.repository.JpaRepository
 */
@Repository
public interface TransactionRepository extends JpaRepository<Transaction, String> {

    /**
     * Finds all transactions whose {@code origTimestamp} falls within the given
     * inclusive range {@code [startTimestamp, endTimestamp]}.
     *
     * <p>Replaces the VSAM {@code TRANSACT.AIX} alternate-index range scan used by
     * transaction reporting, list views, and the consolidation/statement batch jobs.
     * Spring Data parses the method name and auto-generates JPQL equivalent to
     * {@code WHERE t.origTimestamp BETWEEN :startTimestamp AND :endTimestamp} (the
     * predicate references the {@code origTimestamp} entity field; the physical SQL
     * column is {@code orig_timestamp}). Execution is backed by the PostgreSQL B-tree
     * index {@code idx_transaction_orig_ts} (AAP &sect;0.6.13).</p>
     *
     * <p><strong>{@link LocalDateTime} parameters (not {@code String}) &mdash;
     * entity-aligned.</strong> The {@link Transaction} entity declares
     * {@code origTimestamp} as {@link LocalDateTime} over the {@code orig_timestamp
     * TIMESTAMP} column (AAP &sect;0.6.5); Spring Data method-name auto-derivation
     * requires the parameter types to match the property type, so both bounds are
     * {@link LocalDateTime}. The raw 26-character DB2 external timestamp
     * ({@code YYYY-MM-DD-HH.MM.SS.MIL0000}, PR-11) lives only at I/O boundaries; the
     * service layer converts {@code String} &harr; {@link LocalDateTime} (via
     * {@code DateConversionUtil}/{@code DateConversionService}) before calling this
     * method.</p>
     *
     * @param startTimestamp inclusive lower bound of the original-timestamp range;
     *                       must not be {@code null}
     * @param endTimestamp   inclusive upper bound of the original-timestamp range;
     *                       must not be {@code null}
     * @return the matching transactions (an empty list when none match); never
     *         {@code null}
     */
    List<Transaction> findByOrigTimestampBetween(LocalDateTime startTimestamp, LocalDateTime endTimestamp);

    /**
     * Finds all transactions recorded against the given card number.
     *
     * <p>Primary consumer: statement generation ({@code app/cbl/CBSTM03A.CBL} &rarr;
     * {@code StatementGenerationJobConfig}/{@code StatementService}), which enumerates
     * every transaction that occurred on a card during the statement period. Spring
     * Data derives JPQL equivalent to {@code WHERE t.cardNum = :cardNum} (entity field
     * {@code cardNum}; physical column {@code card_num}).</p>
     *
     * <p><strong>Method name uses {@code CardNum} (not {@code CardNumber}) &mdash;
     * entity-aligned.</strong> The {@link Transaction} entity field is {@code cardNum},
     * mapping COBOL {@code TRAN-CARD-NUM PIC X(16)}. Spring Data method-name
     * auto-derivation requires the property segment of the method name to match the
     * entity field name exactly; {@code findByCardNumber} would raise a
     * {@code PropertyReferenceException} at bootstrap because no {@code cardNumber}
     * property exists. Hence {@code findByCardNum}.</p>
     *
     * @param cardNum the 16-character card number (matches {@code TRAN-CARD-NUM
     *                PIC X(16)}); must not be {@code null}
     * @return the transactions for this card (an empty list when none exist); never
     *         {@code null}
     */
    List<Transaction> findByCardNum(String cardNum);

    /**
     * Finds transactions recorded against the given card number, one page at a time.
     *
     * <p>Paginated counterpart of {@link #findByCardNum(String)} backing the
     * {@code COTRN00C} transaction list browse (TRANID {@code CT00}) when the caller
     * narrows the listing to a single card (the {@code GET /api/transactions?cardNumber=...}
     * filter). Spring Data derives JPQL equivalent to {@code WHERE t.cardNum = :cardNum}
     * (entity field {@code cardNum}; physical column {@code card_num}) and applies the
     * supplied {@link Pageable} for {@code LIMIT}/{@code OFFSET}/{@code ORDER BY}. The
     * stateless {@link Pageable} replaces the server-side {@code STARTBR}/{@code READNEXT}/
     * {@code READPREV} cursor and the PF7/PF8 navigation (AAP &sect;0.6.1).</p>
     *
     * <p>The method-name property segment is {@code CardNum} (not {@code CardNumber}) to
     * match the entity field exactly; see {@link #findByCardNum(String)} for the
     * {@code PropertyReferenceException} rationale.</p>
     *
     * @param cardNum  the 16-character card number (matches {@code TRAN-CARD-NUM
     *                 PIC X(16)}); must not be {@code null}
     * @param pageable the page coordinates (page number, size, and sort) supplied by the
     *                 service layer; must not be {@code null}
     * @return the requested page of transactions for this card (a possibly-empty page);
     *         never {@code null}
     */
    Page<Transaction> findByCardNum(String cardNum, Pageable pageable);

    /**
     * Finds transactions recorded against any of the given card numbers, one page at a time.
     *
     * <p>Backs the {@code COTRN00C} transaction list browse when the caller narrows the
     * listing to a single account (the {@code GET /api/transactions?accountId=...} filter):
     * the service first resolves the account's card numbers through
     * {@code CardXrefRepository.findByAccountId(...)} (the {@code CARDXREF}/{@code CXACAIX}
     * cross-reference), then enumerates the matching transactions here. Spring Data derives
     * JPQL equivalent to {@code WHERE t.cardNum IN :cardNums} (entity field {@code cardNum};
     * physical column {@code card_num}) and applies the supplied {@link Pageable}.</p>
     *
     * <p>When the account owns no cards the service passes an empty collection is avoided by
     * short-circuiting to an empty page at the service layer (an empty {@code IN ()} predicate
     * is degenerate), so this method is invoked only with a non-empty collection.</p>
     *
     * @param cardNums the card numbers to match (each {@code TRAN-CARD-NUM PIC X(16)});
     *                 must not be {@code null} and is invoked non-empty by the service
     * @param pageable the page coordinates (page number, size, and sort) supplied by the
     *                 service layer; must not be {@code null}
     * @return the requested page of transactions for these cards (a possibly-empty page);
     *         never {@code null}
     */
    Page<Transaction> findByCardNumIn(Collection<String> cardNums, Pageable pageable);

    /**
     * Finds <em>all</em> transactions recorded against any of the given card numbers, in a single
     * query (no pagination).
     *
     * <p>Non-paged counterpart of {@link #findByCardNumIn(Collection, Pageable)} used by the
     * batch statement-generation flow ({@code CBSTM03A} port). The
     * {@link com.carddemo.batch.StatementGenerationTasklet} streams the card cross-reference one
     * bounded page at a time and, for each page of completed {@code (customer, account)} groups,
     * pre-loads every owned card's transactions with one call to this method instead of issuing a
     * separate {@link #findByCardNum(String)} per card. That collapses the previous per-row query
     * storm (one transaction query per card &mdash; QA finding F4-NPLUS1-01) into a single
     * {@code WHERE t.cardNum IN (:cardNums)} query per page (AAP &sect;0.6.12 N+1 avoidance), while
     * the page-bounded card set keeps memory use bounded (CP4). Spring Data derives JPQL equivalent
     * to {@code WHERE t.cardNum IN :cardNums} (entity field {@code cardNum}; physical column
     * {@code card_num}); no {@code ORDER BY} is implied &mdash; the tasklet re-sorts the aggregate
     * by {@code tranId} so statement output stays byte-for-byte identical (PR-09) regardless of the
     * row order returned here.</p>
     *
     * <p>The caller never invokes this with an empty collection (it short-circuits to an empty
     * result first), so the degenerate empty {@code IN ()} predicate is not exercised.</p>
     *
     * @param cardNums the card numbers to match (each {@code TRAN-CARD-NUM PIC X(16)}); must not be
     *                 {@code null} and is invoked non-empty by the caller
     * @return all transactions whose {@code cardNum} is in {@code cardNums} (a possibly-empty list);
     *         never {@code null}
     */
    List<Transaction> findByCardNumIn(Collection<String> cardNums);

    /**
     * Allocates the next value of the PostgreSQL sequence {@code transaction_id_seq} and
     * returns it as the 6-digit suffix source for an <strong>online</strong> 16-character
     * {@code TRAN-ID} (PR-10, AAP &sect;0.6.10).
     *
     * <p><strong>Why a DB sequence for the online path.</strong> AAP &sect;0.6.10 mandates two
     * distinct {@code TRAN-ID} suffix sources: batch interest posting ({@code CBACT04C} /
     * INTCALC) uses a per-{@code JobExecution} in-memory counter ({@code TransactionIdGenerator}'s
     * {@code AtomicLong}, reset at job start for deterministic, restartable suffixes), whereas
     * online creation ({@code POST /api/transactions}, {@code COTRN02C}) must guarantee uniqueness
     * across concurrent requests and across application restarts. A process-local counter cannot:
     * it resets to zero on restart and would collide on the same {@code parmDate} prefix. The
     * monotonic, persistent {@code transaction_id_seq} (declared {@code START 1 INCREMENT 1 NO
     * CYCLE} in {@code src/main/resources/db/migration/V1__schema.sql}) provides a gap-tolerant,
     * concurrency-safe allocation: each {@code nextval} call returns a distinct value even under
     * parallel transactions, because sequence allocation is non-transactional in PostgreSQL.</p>
     *
     * <p><strong>Composition.</strong> {@code TransactionService} fetches a value here and passes
     * it to {@code TransactionIdGenerator.nextOnlineId(parmDate, sequenceValue)}, which formats the
     * 16-character id as {@code parmDate(10) + %06d(suffix)} &mdash; the same fixed width and
     * layout the batch path produces, keeping PR-10 uniform across both surfaces.</p>
     *
     * <p>Implemented as a native query because {@code nextval(...)} is a PostgreSQL function with
     * no JPQL equivalent; it returns a single {@code bigint} row mapped to a Java {@code long}.</p>
     *
     * @return the next sequence value (a positive, strictly increasing {@code long}); used as the
     *         online {@code TRAN-ID} suffix and combined with the date prefix by
     *         {@code TransactionIdGenerator.nextOnlineId(String, long)}
     */
    @Query(value = "SELECT nextval('transaction_id_seq')", nativeQuery = true)
    long nextTransactionIdSuffix();
}
