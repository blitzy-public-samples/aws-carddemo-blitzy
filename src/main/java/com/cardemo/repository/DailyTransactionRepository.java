package com.cardemo.repository;

import com.cardemo.entity.DailyTransaction;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

/**
 * Spring Data JPA repository for the {@link DailyTransaction} entity.
 *
 * <p>Maps the VSAM DALYTRAN sequential dataset (350-byte records defined in
 * {@code CVTRA06Y.cpy}) used as a batch staging table by the daily transaction
 * posting process ({@code CBTRN02C.cbl} / {@code DailyPostingService} /
 * {@code DailyPostingJobConfig}).
 *
 * <h3>COBOL-to-Java Access Pattern Mapping</h3>
 * <p>In {@code CBTRN02C.cbl}, the DALYTRAN file is opened for sequential input and
 * read in a {@code PERFORM UNTIL END-OF-FILE = 'Y'} loop via the
 * {@code 1000-DALYTRAN-GET-NEXT} paragraph. Each record is validated against the
 * XREF, ACCOUNT, and TCATBAL files, then either posted to TRANSACT or written to
 * DALYREJS (the reject file).
 *
 * <p>In the Java migration, this sequential read pattern is served by the inherited
 * {@link JpaRepository#findAll()} and {@link JpaRepository#findAll(org.springframework.data.domain.Pageable)}
 * methods, which provide sequential and chunk-oriented (paginated) access respectively
 * for Spring Batch {@code ItemReader} consumption.
 *
 * <h3>Inherited Operations (from {@code JpaRepository<DailyTransaction, Long>})</h3>
 * <ul>
 *   <li>{@code findAll()} — Sequential read equivalent of COBOL
 *       {@code READ DALYTRAN-FILE INTO DALYTRAN-RECORD} in a loop</li>
 *   <li>{@code findAll(Pageable)} — Paginated reading for Spring Batch chunk-oriented
 *       processing ({@code JpaPagingItemReader})</li>
 *   <li>{@code findById(Long)} — Lookup by surrogate primary key</li>
 *   <li>{@code save(DailyTransaction)} — Persist a single daily transaction staging
 *       record (equivalent of WRITE to staging area)</li>
 *   <li>{@code saveAll(Iterable)} — Batch insert of daily transaction feed data
 *       from fixed-width file parsing</li>
 *   <li>{@code deleteAll()} — Cleanup of staging table after batch processing
 *       completes (no COBOL equivalent — VSAM file is simply closed)</li>
 *   <li>{@code deleteById(Long)} — Remove a single staging record by surrogate key</li>
 *   <li>{@code existsById(Long)} — Check existence of a staging record</li>
 *   <li>{@code count()} — Record count for batch job statistics (equivalent of
 *       {@code WS-TRANSACTION-COUNT} in CBTRN02C.cbl)</li>
 * </ul>
 *
 * <h3>Primary Key Design</h3>
 * <p>Unlike most other CardDemo repositories that use {@code String} primary keys
 * (mapped from VSAM KSDS record keys), this repository uses {@code Long} because
 * the DALYTRAN VSAM dataset is a sequential input file with no natural primary key.
 * The {@link DailyTransaction} entity uses a surrogate
 * {@code @GeneratedValue(strategy = GenerationType.IDENTITY)} {@code Long id}.
 *
 * <h3>No Custom Query Methods</h3>
 * <p>Per the AAP specification ("Batch staging table access"), standard
 * {@code JpaRepository} methods are sufficient for all daily transaction posting
 * operations. No additional custom query methods are defined — the batch
 * {@code ItemReader} processes all records sequentially through inherited methods.
 *
 * @see DailyTransaction
 * @see org.springframework.data.jpa.repository.JpaRepository
 */
@Repository
public interface DailyTransactionRepository extends JpaRepository<DailyTransaction, Long> {
    // All required operations are inherited from JpaRepository<DailyTransaction, Long>:
    //
    // - findAll()           : Sequential read (← COBOL PERFORM UNTIL END-OF-FILE)
    // - findAll(Pageable)   : Paginated read for Spring Batch chunk processing
    // - findById(Long)      : Lookup by surrogate PK
    // - save(T)             : Persist single staging record
    // - saveAll(Iterable)   : Batch insert of daily feed data
    // - deleteAll()         : Post-batch staging table cleanup
    // - deleteById(Long)    : Remove single record by surrogate PK
    // - existsById(Long)    : Check record existence
    // - count()             : Record count for batch statistics (← WS-TRANSACTION-COUNT)
}
