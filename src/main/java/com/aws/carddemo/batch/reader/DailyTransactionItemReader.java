/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License").
 * You may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.aws.carddemo.batch.reader;

import org.springframework.batch.item.data.RepositoryItemReader;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Component;

import com.aws.carddemo.domain.DailyTransaction;
import com.aws.carddemo.repository.DailyTransactionRepository;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Spring Batch {@link org.springframework.batch.item.ItemReader ItemReader} that streams
 * daily-transaction ({@code DALYTRAN}) staging records in the exact order the legacy COBOL
 * batch programs read them. It is the set-based, restartable replacement for the COBOL
 * sequential file read {@code READ DALYTRAN-FILE INTO DALYTRAN-RECORD}; each emitted item is a
 * {@link DailyTransaction} carrying the copybook layout {@code CVTRA06Y.cpy}
 * ({@code DALYTRAN-RECORD}, fixed record length 350).
 *
 * <h2>Legacy lineage</h2>
 * The {@code DALYTRAN} dataset is opened for input and walked front-to-back until end-of-file by
 * two batch programs, both of which declare it
 * {@code ORGANIZATION IS SEQUENTIAL} / {@code ACCESS MODE IS SEQUENTIAL}:
 * <ul>
 *   <li>{@code legacy/cbl/CBTRN01C.cbl} (source {@code app/cbl/CBTRN01C.cbl}) &mdash; the
 *       daily-transaction <em>validate</em> batch. It declares the file at L29-32
 *       ({@code SELECT DALYTRAN-FILE ASSIGN TO DALYTRAN ... FILE STATUS IS DALYTRAN-STATUS}) and
 *       drives the main loop {@code PERFORM 1000-DALYTRAN-GET-NEXT} (L166), reading one record per
 *       iteration and validating it against the customer, cross-reference, card, account, and
 *       transaction files.</li>
 *   <li>{@code legacy/cbl/CBTRN02C.cbl} (source {@code app/cbl/CBTRN02C.cbl}) &mdash; the
 *       daily-transaction <em>posting</em> batch. It declares the identical sequential file at
 *       L29-32 and its paragraph {@code 1000-DALYTRAN-GET-NEXT} (L345) issues
 *       {@code READ DALYTRAN-FILE INTO DALYTRAN-RECORD} (L346), posting each record to the account
 *       or rejecting it with a reason code.</li>
 * </ul>
 * The record layout read by both programs is {@code legacy/cpy/CVTRA06Y.cpy}
 * ({@code DALYTRAN-RECORD}, {@code RECLN = 350}).
 *
 * <h2>Consumers (bean name is a hard contract)</h2>
 * This bean is injected by two parent jobs in {@code com.aws.carddemo.batch}:
 * <ul>
 *   <li>{@code DailyTransactionValidateJob} (CBTRN01C) &mdash; chunk
 *       {@code <DailyTransaction, DailyTransaction>};</li>
 *   <li>{@code DailyTransactionPostingJob} (CBTRN02C) &mdash; chunk size 1.</li>
 * </ul>
 * Both wire this reader by its conventional Spring bean name, the decapitalized class name
 * {@code dailyTransactionItemReader}. The class is therefore a {@link Component} (so the bean name
 * is exactly that), and the same string is set as the reader name via
 * {@link org.springframework.batch.item.ItemStreamSupport#setName(String)} because
 * {@link RepositoryItemReader} uses it as the {@code ExecutionContext} key prefix under which its
 * paging save-state is persisted, so it must be stable across restarts.
 *
 * <h2>Primary implementation &mdash; JPA over the {@code daily_transaction} staging table</h2>
 * The reader delegates to the shared {@link DailyTransactionRepository} and pages through the
 * {@code daily_transaction} table via {@code findAll(Pageable)} (inherited from
 * {@code PagingAndSortingRepository}). {@link RepositoryItemReader} requires a page-accepting
 * method, so {@code findAll} is used rather than the repository's convenience
 * {@code findAllByOrderByIdAsc()}; the {@link Sort} configured below reproduces the identical
 * ordering while keeping the read paged (memory-bounded) and restartable.
 *
 * <p><strong>Ordering equivalence.</strong> Rows are ordered by the surrogate identity primary key
 * {@code id} ascending. Because the seed/staging load inserts {@code DALYTRAN} rows in file order,
 * ascending {@code id} is the faithful analog of the physical/sequential order in which
 * CBTRN01C/CBTRN02C read the file. The reader deliberately does <em>not</em> order by the business
 * identifier {@code dalytranId} ({@code DALYTRAN-ID}); doing so would change the read order relative
 * to the COBOL sequential read and could reorder rows that share a business id across posting runs.</p>
 *
 * <p><strong>Reader is logic-free.</strong> Mirroring the COBOL {@code 1000-DALYTRAN-GET-NEXT}
 * paragraph, which only reads the next record, this reader performs no validation, rejection, or
 * posting. Those concerns belong to the {@code batch/processor/**} components and the parent
 * {@code batch/} job beans (e.g. reject codes 100/101/102/103 in CBTRN02C), preserving a clean
 * separation between record retrieval and business logic.</p>
 *
 * <h2>Concurrency and scope</h2>
 * A singleton {@link Component} is safe here. The two consuming jobs run sequentially and never
 * concurrently ({@code spring.batch.job.enabled=false}; each job is triggered individually via
 * CI/CD, not on startup), and every {@code Step} execution opens and closes this reader through the
 * {@code ItemStream} lifecycle ({@code open}/{@code update}/{@code close}), which resets its paging
 * cursor per execution. Should the jobs ever be run concurrently within the same JVM, annotating
 * this class {@code @StepScope} is an acceptable isolation upgrade that yields a fresh reader
 * instance per step execution; it is intentionally omitted now to avoid unnecessary proxying.
 *
 * <h2>Companion &mdash; external fixed-width {@code DALYTRAN.PS} ingestion</h2>
 * This DB-backed reader operates on the {@code daily_transaction} staging table <em>after</em> it has
 * been populated. Populating it from the raw external file is the job of the companion loader
 * {@code batch/DailyTransactionLoadJob} (bean {@code dailyTransactionLoadJob}), whose
 * {@code @StepScope} {@link DailyTransactionFileItemReader} is a
 * {@code FlatFileItemReader<DailyTransaction>} that reads the preserved 350-byte fixed-width
 * {@code DALYTRAN} record contract (AAP &sect;0.7.2 hotspot M2) and delegates the field decode to the
 * stateless {@link DailyTransactionLineMapper}, which slices each line with
 * {@link com.aws.carddemo.common.util.FixedWidthCodec}. The two readers are therefore complementary,
 * not alternatives: the file reader is the ingestion front door that loads the staging table, and
 * <em>this</em> repository reader is the downstream sequential read that feeds the validate and
 * posting jobs. The exact 0-based byte offsets from {@code CVTRA06Y.cpy} and the corresponding codec
 * calls used by that companion mapper are:
 * <pre>
 * Field         COBOL PIC    Offset  Len   FixedWidthCodec call
 * ------------  -----------  ------  ----  -----------------------------------------
 * dalytranId    X(16)             0    16  readAlphanumericTrimmed(rec,   0,  16)
 * typeCd        X(02)            16     2  readAlphanumericTrimmed(rec,  16,   2)
 * catCd         9(04)            18     4  readNumericInt(rec,  18,   4)
 * tranSource    X(10)            22    10  readAlphanumericTrimmed(rec,  22,  10)
 * tranDesc      X(100)           32   100  readAlphanumericTrimmed(rec,  32, 100)
 * tranAmt       S9(09)V99       132    11  readSignedDecimal(rec, 132,  11, 2)
 * merchantId    9(09)           143     9  readNumeric(rec, 143,   9)
 * merchantName  X(50)           152    50  readAlphanumericTrimmed(rec, 152,  50)
 * merchantCity  X(50)           202    50  readAlphanumericTrimmed(rec, 202,  50)
 * merchantZip   X(10)           252    10  readAlphanumericTrimmed(rec, 252,  10)
 * cardNum       X(16)           262    16  readAlphanumericTrimmed(rec, 262,  16)
 * origTs        X(26)           278    26  readAlphanumericTrimmed(rec, 278,  26)
 * procTs        X(26)           304    26  readAlphanumericTrimmed(rec, 304,  26)
 * (FILLER)      X(20)           330    20  ignored  -- total record length = 350
 * </pre>
 * The amount field {@code tranAmt} ({@code PIC S9(09)V99}) MUST be decoded with
 * {@link com.aws.carddemo.common.util.FixedWidthCodec#readSignedDecimal(String, int, int, int)},
 * whose zoned-decimal overpunch handling carries the sign on the trailing byte (for example a
 * trailing {@code G} denotes a positive last digit 7, whereas a trailing right-brace denotes a
 * negative last digit 0). It must never be parsed with {@code new BigDecimal(String)}, and the
 * amount is always modeled as {@link java.math.BigDecimal} (scale 2), never a primitive
 * {@code double}/{@code float}. That external-file ingestion path is now executable in
 * {@link DailyTransactionLineMapper} / {@link DailyTransactionFileItemReader}; this class remains the
 * downstream staging reader configured over the {@code daily_transaction} table.
 *
 * <h2>On {@code @SuppressWarnings("this-escape")}</h2>
 * The constructor establishes the reader's fixed configuration by invoking the inherited
 * configuration setters of {@link RepositoryItemReader} and its superclasses. Under the project's
 * warning-free build ({@code -Xlint:all} with {@code failOnWarning=true}) those calls raise the
 * {@code this-escape} lint, because the setters are overridable and this class is not {@code final}.
 * The class is intentionally left non-{@code final} so the {@code @StepScope} isolation upgrade
 * described above (a CGLIB {@code TARGET_CLASS} scoped proxy that must subclass this type) remains
 * possible. The escape is nonetheless safe &mdash; the invoked methods are the framework's own
 * configuration setters, this class declares no overriding subclass, and any scoped proxy only
 * delegates to them &mdash; so the lint is suppressed locally on the constructor.
 *
 * @see DailyTransaction
 * @see DailyTransactionRepository
 * @see RepositoryItemReader
 * @see DailyTransactionFileItemReader
 * @see DailyTransactionLineMapper
 * @see com.aws.carddemo.common.util.FixedWidthCodec
 */
@Component
public class DailyTransactionItemReader extends RepositoryItemReader<DailyTransaction> {

    /**
     * Reader name and Spring bean name. Intentionally identical to the decapitalized class name so
     * that {@code DailyTransactionValidateJob} (CBTRN01C) and {@code DailyTransactionPostingJob}
     * (CBTRN02C) can wire this reader by its conventional bean name. It is also used by
     * {@link RepositoryItemReader} as the {@code ExecutionContext} key prefix for paging save-state,
     * so it must remain stable across restarts.
     */
    private static final String READER_NAME = "dailyTransactionItemReader";

    /**
     * Repository method resolved by {@link RepositoryItemReader}. {@code findAll} binds to
     * {@code PagingAndSortingRepository.findAll(Pageable)}, which the reader invokes once per page.
     */
    private static final String FIND_ALL_METHOD_NAME = "findAll";

    /**
     * Sort property reproducing the COBOL sequential/physical read order. Ordering by the surrogate
     * identity primary key {@code id} ascending mirrors the file (insertion) order because the
     * staging load inserts {@code DALYTRAN} rows in file order.
     */
    private static final String ORDER_BY_ID = "id";

    /**
     * JPA paging page size. The reader fetches the ordered result set in pages of this many rows,
     * keeping memory bounded while streaming an arbitrarily large daily-transaction input.
     */
    private static final int PAGE_SIZE = 100;

    /**
     * Constructs and fully configures the reader over the {@code daily_transaction} staging table.
     *
     * <p>The superclass is configured to page through {@link DailyTransactionRepository} using
     * {@code findAll(Pageable)} ordered by {@code id} ascending &mdash; the faithful analog of the
     * COBOL sequential read of the {@code DALYTRAN} file by CBTRN01C/CBTRN02C. Paging is
     * memory-bounded ({@link #PAGE_SIZE}) and save-state is enabled so a chunk-oriented step can
     * track its read count and restart. The reader name is fixed to {@link #READER_NAME} for stable
     * {@code ExecutionContext} keys and conventional bean wiring.</p>
     *
     * <p>{@code afterPropertiesSet()} is intentionally not called here: {@link RepositoryItemReader}
     * implements {@link org.springframework.beans.factory.InitializingBean}, so Spring invokes it
     * automatically once the bean is constructed, validating the configuration.</p>
     *
     * @param repository the Spring Data JPA repository over the {@code daily_transaction} staging
     *                   table; injected by the container and never {@code null}
     */
    @SuppressWarnings("this-escape")
    public DailyTransactionItemReader(DailyTransactionRepository repository) {
        // Data source: the daily_transaction staging table (Java analog of the COBOL DALYTRAN file).
        setRepository(repository);

        // Resolve to PagingAndSortingRepository.findAll(Pageable); RepositoryItemReader requires a
        // Pageable-accepting method, so findAll is used instead of findAllByOrderByIdAsc().
        setMethodName(FIND_ALL_METHOD_NAME);

        // Order by the surrogate identity PK ascending. Because the staging load inserts DALYTRAN
        // rows in file order, id ASC reproduces the physical/sequential order in which
        // CBTRN01C/CBTRN02C read the DALYTRAN file. A LinkedHashMap preserves key order (defensive:
        // a single sort key here, but the map contract for multi-key sorts is order-sensitive).
        Map<String, Sort.Direction> sort = new LinkedHashMap<>();
        sort.put(ORDER_BY_ID, Sort.Direction.ASC);
        setSort(sort);

        // Memory-bounded paging and restartable save-state for chunk-oriented steps.
        setPageSize(PAGE_SIZE);
        setName(READER_NAME);
        setSaveState(true);
    }
}
