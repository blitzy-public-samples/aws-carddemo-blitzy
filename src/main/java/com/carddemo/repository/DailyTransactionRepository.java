package com.carddemo.repository;

import com.carddemo.entity.DailyTransaction;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * Spring Data JPA repository for {@link DailyTransaction} staging-table records.
 *
 * <p>Replaces the VSAM/PS {@code DALYTRAN} sequential daily-transaction feed file. Per the
 * AAP &sect;0.6.6 staging strategy, the EBCDIC fixed-width file
 * {@code app/data/ASCII/dailytran.txt} cannot be consumed directly by PostgreSQL; instead a
 * pre-step ({@code DailyTransactionReadJobConfig}, derived from {@code app/cbl/CBTRN01C.cbl}
 * &mdash; "Post the records from daily transaction file") parses it via
 * {@code FlatFileItemReader} + {@code FixedWidthRecordParser} and INSERTs each row into the
 * {@code daily_transactions} staging table with {@code processed = false}. The 350-byte
 * record layout is defined by {@code app/cpy/CVTRA06Y.cpy} ({@code DALYTRAN-RECORD}).</p>
 *
 * <p>The main step ({@code TransactionPostingJobConfig}, the POSTTRAN /
 * {@code app/cbl/CBTRN02C.cbl} equivalent) then reads this staging table via
 * {@code JdbcCursorItemReader}, processes each row through {@code TransactionPostingProcessor}
 * (validation codes 100/101/102/103, PR-03, paragraph {@code 1500-VALIDATE-TRAN}
 * [app/cbl/CBTRN02C.cbl:L370-L422]), and routes records via {@code CompositeItemWriter}:</p>
 * <ul>
 *   <li>Accepted &rarr; {@code TransactionRepository.save(...)} (master {@code transactions}
 *       table) + {@code TransactionCategoryBalanceUpsertWriter} (PR-06 TCATBAL upsert)
 *       + {@code AccountBalanceUpdater} (PR-07 sign-based balance bucket).</li>
 *   <li>Rejected &rarr; {@code RejectedTransactionRepository.save(...)} (the DALYREJS
 *       equivalent reject sink).</li>
 * </ul>
 *
 * <p>A final tasklet marks {@code daily_transactions.processed = true} (supporting idempotent
 * Spring Batch chunk-restart) or truncates the staging table once every feed row has been
 * accounted for.</p>
 *
 * <p><strong>Primary-key type &mdash; {@code <DailyTransaction, Long>}.</strong> The
 * {@link DailyTransaction} entity's {@code @Id} is a <em>synthetic</em>, database-generated
 * surrogate &mdash; {@code @GeneratedValue(strategy = GenerationType.IDENTITY)} over the
 * {@code daily_tran_id BIGSERIAL} column (a Java {@code Long}). The original COBOL
 * {@code DALYTRAN-ID PIC X(16)} feed identifier is preserved as the ordinary, <em>non-key</em>
 * {@code tran_id VARCHAR(16) NOT NULL} column (the entity's {@code dalytranId} field). The
 * surrogate key is mandated by the committed Flyway schema
 * ({@code src/main/resources/db/migration/V1__schema.sql}, table {@code daily_transactions})
 * because it enables Spring Batch chunk-restart and because the same logical feed transaction
 * may be re-staged across processing cycles ({@code tran_id} is therefore not unique and is
 * unsafe as an identity basis). This repository's ID type parameter MUST match the entity's
 * actual {@code @Id} field type; hence {@code JpaRepository<DailyTransaction, Long>}. This
 * mirrors the sibling {@link RejectedTransactionRepository}, which shares the same record
 * layout and synthetic-{@code Long}-surrogate design.</p>
 *
 * <p><strong>Note on the AAP / agent-prompt PK-type discrepancy (resolved in favour of the
 * entity).</strong> AAP &sect;0.4.1.5 originally specified
 * {@code JpaRepository<DailyTransaction, Long>}, which is correct for the committed entity. A
 * later agent-prompt revision attempted to "correct" this to {@code <…, String>} on the
 * assumption that the entity declared {@code @Id String dalytranId}; however the entity that
 * was actually generated and committed uses the synthetic {@code Long} surrogate described
 * above (verified against the entity source, the Flyway DDL, and the sibling
 * {@code RejectedTransaction}). Declaring a {@code String} ID here would not match the
 * entity's {@code @Id} and would cause Spring Data JPA repository initialization to fail
 * (the application context would not start under {@code ddl-auto: validate}). This interface
 * therefore extends {@code JpaRepository<DailyTransaction, Long>} to remain consistent with
 * the entity, the schema, and the sibling repository &mdash; an entity-aligned resolution of
 * the documentation inconsistency.</p>
 *
 * <p>This is a STAGING table holding transient data (loaded, posted once, then flagged or
 * truncated), so neither the entity nor this repository participates in {@code @Version}
 * optimistic locking &mdash; per AAP &sect;0.3.3 optimistic locking is mandated only for the
 * {@code Account}, {@code Card}, {@code Customer}, and {@code Transaction} master entities.</p>
 *
 * <p>No custom finder methods are declared. Per the Repository Pattern (AAP &sect;0.3.3 #1)
 * this interface extends {@code JpaRepository} (not {@code CrudRepository}) to inherit the
 * full CRUD and paging API, which fully covers the staging-table use case:</p>
 * <ul>
 *   <li>{@code save} / {@code saveAll} &mdash; ItemWriter-based insertion during the ASCII
 *       feed-load phase, and test-fixture setup.</li>
 *   <li>{@code findAll} / {@code findById} / {@code existsById} / {@code count} &mdash;
 *       operational queries (the high-throughput posting read path uses
 *       {@code JdbcCursorItemReader} directly for streaming, not these methods).</li>
 *   <li>{@code deleteById} / {@code delete} / {@code deleteAll} / {@code deleteAllInBatch}
 *       &mdash; the cleanup/truncation tasklet at the end of the posting job.</li>
 * </ul>
 *
 * <p>The explicit {@code @Repository} stereotype marks the interface as a persistence
 * component for component scanning and activates Spring's
 * {@code PersistenceExceptionTranslationPostProcessor}, translating provider-specific
 * exceptions into the {@code org.springframework.dao.DataAccessException} hierarchy. This
 * interface is a pure data-access component and carries no business logic &mdash; validation
 * (PR-03 codes 100/101/102/103) and posting semantics live in
 * {@code TransactionPostingProcessor} and the batch writer beans.</p>
 *
 * @see com.carddemo.entity.DailyTransaction
 * @see com.carddemo.repository.RejectedTransactionRepository
 * @see org.springframework.data.jpa.repository.JpaRepository
 */
@Repository
public interface DailyTransactionRepository extends JpaRepository<DailyTransaction, Long> {

    /**
     * Returns one page of staging rows that have NOT yet been posted
     * ({@code processed = false}), ordered by the supplied {@link Pageable}.
     *
     * <p><strong>Rerun / restart idempotency (POSTTRAN, CBTRN02C parity).</strong> The
     * POSTTRAN posting step ({@code TransactionPostingJobConfig}) must never re-post a
     * staging row it has already accounted for; doing so would double-apply the PR-06
     * {@code TCATBAL} upsert and the PR-07 account-balance bucket update, or fail on a
     * duplicate {@code TRAN-ID} primary key. The posting writer flips
     * {@code DailyTransaction.processed = true} for every accepted <em>and</em> rejected
     * row inside the chunk transaction, so this finder is the filtered read that lets a
     * rerun (a brand-new {@code JobInstance}) or a restart (after a failed chunk) naturally
     * resume over only the rows that remain unprocessed &mdash; reproducing the
     * once-and-only-once semantics of the original sequential {@code DALYTRAN} consumption.</p>
     *
     * <p><strong>Process-indicator paging contract.</strong> The posting reader invokes this
     * finder with {@code PageRequest.of(0, chunkSize, Sort.by(ASC, "dalytranId"))} and
     * re-issues the <em>same page-0 request</em> each time its in-memory buffer drains, rather
     * than incrementing the page index. Because the previous chunk has, by then, committed
     * {@code processed = true} on the rows it consumed, page 0 of the residual
     * {@code processed = false} set returns the next distinct batch &mdash; avoiding the
     * classic offset-pagination defect in which advancing the page index while the result set
     * shrinks under mutation skips rows. Spring Data derives the query as
     * {@code WHERE processed = false} (entity field {@code processed}; physical column
     * {@code processed BOOLEAN NOT NULL}).</p>
     *
     * @param pageable the page request (size aligned with the posting chunk size and an
     *                 ascending {@code dalytranId} sort for deterministic ordering); must not
     *                 be {@code null}
     * @return the unprocessed staging rows for the requested page (an empty list when none
     *         remain, which signals the posting step to stop); never {@code null}
     */
    List<DailyTransaction> findByProcessedFalse(Pageable pageable);
}
