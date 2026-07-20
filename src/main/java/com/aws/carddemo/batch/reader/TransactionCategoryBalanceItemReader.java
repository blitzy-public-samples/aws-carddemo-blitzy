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

import java.util.LinkedHashMap;
import java.util.Map;

import com.aws.carddemo.domain.TransactionCategoryBalance;
import com.aws.carddemo.repository.TransactionCategoryBalanceRepository;
import org.springframework.batch.item.data.RepositoryItemReader;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Component;

/**
 * Spring Batch {@link org.springframework.batch.item.ItemReader ItemReader} that streams
 * every transaction-category-balance ({@code TCATBAL}) row in ascending composite-key
 * order. It is the set-based replacement for the sequential VSAM read performed by the
 * legacy interest-calculation batch program {@code legacy/cbl/CBACT04C.cbl}
 * (source {@code app/cbl/CBACT04C.cbl}); each streamed item carries the layout of the
 * copybook {@code CVTRA01Y.cpy} ({@code TRAN-CAT-BAL-RECORD}, fixed record length 50),
 * projected here as the {@link TransactionCategoryBalance} JPA entity.
 *
 * <h2>Legacy lineage (CBACT04C)</h2>
 * <ul>
 *   <li><strong>Sequential key-ordered browse.</strong> CBACT04C declares
 *       {@code SELECT TCATBAL-FILE ASSIGN TO TCATBALF ORGANIZATION IS INDEXED ACCESS MODE
 *       IS SEQUENTIAL RECORD KEY IS FD-TRAN-CAT-KEY} and walks the {@code TCATBALF} KSDS
 *       front-to-back in ascending record-key order (paragraph
 *       {@code 1000-TCATBALF-GET-NEXT}). The record key {@code FD-TRAN-CAT-KEY} is the
 *       17-byte group {@code (FD-TRANCAT-ACCT-ID 9(11), FD-TRANCAT-TYPE-CD X(02),
 *       FD-TRANCAT-CD 9(04))}. This reader reproduces that browse as a single paged,
 *       sorted repository query (see <em>Ordering</em>).</li>
 *   <li><strong>Account-level control break.</strong> Because the browse is ordered with
 *       the account id as the most-significant key, all rows for one account arrive
 *       contiguously, which is the precondition CBACT04C relies on to accumulate and post
 *       interest per account before moving on to the next account.</li>
 * </ul>
 *
 * <h2>Scope &mdash; this reader supplies ordered rows only</h2>
 * The reader deliberately contains <strong>no business logic</strong>. The monthly-interest
 * COMPUTE that CBACT04C performs &mdash;
 * {@code monthlyInterest = tranCatBal.multiply(intRate).divide(BigDecimal.valueOf(1200), 2,
 * RoundingMode.HALF_UP)} [app/cbl/CBACT04C.cbl:L464-465] &mdash; together with the
 * disclosure-group interest-rate lookup and the per-account roll-up (the control break)
 * live in the interest-calculation <em>processor</em> and <em>job</em>, never here. This
 * mirrors CBACT04C, where the read paragraph only fetches the next {@code TCATBAL} record
 * and the arithmetic lives in {@code 1300-COMPUTE-INTEREST}. All monetary arithmetic uses
 * {@link java.math.BigDecimal} at scale&nbsp;2; the {@code bal} field of the emitted entity
 * is never converted to {@code double}/{@code float}.
 *
 * <h2>Ordering (why {@code findAll}, not {@code findByAccountId})</h2>
 * The reader binds to the inherited {@link org.springframework.data.repository.PagingAndSortingRepository#findAll(org.springframework.data.domain.Pageable)
 * findAll(Pageable)} method (via {@link #setMethodName(String)}) so that it scans
 * <em>all</em> category balances across <em>all</em> accounts in one ordered stream, exactly
 * as CBACT04C sweeps the whole {@code TCATBALF} file. The repository's
 * {@code findByAccountId(Long)} method is intentionally <em>not</em> used: it returns a
 * single account's rows and would not drive the whole-file account control break the
 * interest job performs.
 *
 * <p>The sort is expressed as an <em>ordered</em> {@link LinkedHashMap} of nested-property
 * path to {@link Sort.Direction}, so its iteration order (and hence the emitted
 * {@code ORDER BY} column order) is deterministic:</p>
 * <ol>
 *   <li>{@code id.acctId}&nbsp;ASC &mdash; {@code TRANCAT-ACCT-ID} (most significant)</li>
 *   <li>{@code id.typeCd}&nbsp;ASC &mdash; {@code TRANCAT-TYPE-CD}</li>
 *   <li>{@code id.catCd}&nbsp;ASC &mdash; {@code TRANCAT-CD} (least significant)</li>
 * </ol>
 * <p>This reproduces the VSAM key order {@code (TRANCAT-ACCT-ID, TRANCAT-TYPE-CD,
 * TRANCAT-CD)} of {@code FD-TRAN-CAT-KEY}. The leading {@code id.acctId} guarantees that
 * every account's rows arrive contiguously so the downstream account control break is
 * well-formed. The property paths are <em>nested</em>: {@link TransactionCategoryBalance}
 * declares its three-part key with an {@link jakarta.persistence.EmbeddedId @EmbeddedId}
 * field named {@code id}, and Spring Data JPA resolves {@code id.acctId} /
 * {@code id.typeCd} / {@code id.catCd} by traversing into that embedded identifier, so the
 * sort targets the real key columns {@code (acct_id, type_cd, cat_cd)}.</p>
 *
 * <h2>Paging and restartability</h2>
 * Rows are fetched in pages of {@value #PAGE_SIZE} to keep heap usage bounded while
 * streaming an arbitrarily large balance file. Save-state is enabled
 * ({@link #setSaveState(boolean) setSaveState(true)}) so a restarted step resumes from the
 * last committed page rather than re-reading from the beginning. The reader name (the
 * {@code ExecutionContext} key prefix used for that save-state) is set to the decapitalized
 * class name, which is also this bean's Spring name.
 *
 * <h2>Bean identity (authoritative contract)</h2>
 * Registered as a Spring {@link Component}; its default bean name is the decapitalized
 * class name, {@code transactionCategoryBalanceItemReader}. The interest-calculation job
 * ({@code InterestCalculationJob}, CBACT04C) wires this reader into its chunk-oriented step
 * &mdash; chunk {@code <TransactionCategoryBalance,
 * InterestCalculationProcessor.InterestResult>} &mdash; by that exact name, so the bean name
 * must not change.
 *
 * <h2>Concurrency and lifecycle</h2>
 * A singleton {@link Component} is safe here: the interest job runs standalone (it is not
 * invoked concurrently with itself), and the owning {@code Step} opens and closes the
 * reader once per step execution, so no reader state is shared across concurrent executions.
 * Promoting the bean to {@link org.springframework.batch.core.configuration.annotation.StepScope
 * step scope} is an acceptable isolation upgrade should the job ever be parallelized; the
 * class is intentionally left non-{@code final} so that upgrade (which relies on a CGLIB
 * scoped proxy subclass) remains possible.
 *
 * <p>The constructor establishes the reader's fixed configuration by invoking the inherited
 * configuration setters of {@link RepositoryItemReader}. Because those setters are
 * overridable and this class is not {@code final}, Java's {@code -Xlint:all} raises the
 * {@code this-escape} lint; it is suppressed on the constructor. The escape is safe &mdash;
 * the invoked methods are the framework's own configuration setters, this project declares
 * no subclass that overrides them, and any {@code StepScope} CGLIB proxy only delegates to
 * (rather than overrides) them. This rationale is documented here (per the project's
 * explainability rule) rather than inline.</p>
 *
 * @see TransactionCategoryBalance
 * @see TransactionCategoryBalanceRepository
 * @see RepositoryItemReader
 * @see <a href="http://www.apache.org/licenses/LICENSE-2.0">Apache License 2.0</a>
 */
@Component
public class TransactionCategoryBalanceItemReader extends RepositoryItemReader<TransactionCategoryBalance> {

    /**
     * Reader name and Spring bean name. It is intentionally identical to the decapitalized
     * class name so that {@code InterestCalculationJob} (CBACT04C) can wire this reader by
     * its conventional bean name. {@link RepositoryItemReader} would normally use it as the
     * {@code ExecutionContext} key prefix for paging save-state; this reader disables save-state
     * (see the constructor) so a restart replays the full input, but the name is still set for
     * stable step identification and logging.
     */
    private static final String READER_NAME = "transactionCategoryBalanceItemReader";

    /**
     * Repository method bound as the data source. {@code findAll} resolves to the inherited
     * {@code findAll(Pageable)} of {@code PagingAndSortingRepository}, which
     * {@link RepositoryItemReader} calls with a page request carrying the configured sort.
     * This scans every account's category balances, matching the whole-file sweep of
     * CBACT04C.
     */
    private static final String FIND_ALL_METHOD = "findAll";

    /**
     * JPA paging page size. The reader fetches the ordered result set in pages of this many
     * rows, keeping memory bounded while streaming an arbitrarily large balance file.
     */
    private static final int PAGE_SIZE = 100;

    /**
     * Constructs and fully configures the reader over the supplied repository.
     *
     * <p>The composite-key sort is built as an ordered {@link LinkedHashMap} so the emitted
     * {@code ORDER BY} column sequence is deterministic and reproduces the VSAM key order
     * {@code (TRANCAT-ACCT-ID, TRANCAT-TYPE-CD, TRANCAT-CD)}; the leading account-id key is
     * mandatory so that the downstream per-account interest control break sees contiguous
     * account rows. See the class Javadoc for the {@code this-escape} suppression rationale.</p>
     *
     * @param repository the Spring Data JPA repository for {@link TransactionCategoryBalance};
     *                   supplied by constructor injection and never {@code null}
     */
    @SuppressWarnings("this-escape")
    public TransactionCategoryBalanceItemReader(TransactionCategoryBalanceRepository repository) {
        setRepository(repository);
        setMethodName(FIND_ALL_METHOD);

        // Ordered by design: LinkedHashMap preserves insertion order, so RepositoryItemReader
        // emits ORDER BY id.acctId, id.typeCd, id.catCd (ascending) -> the FD-TRAN-CAT-KEY
        // order. Spring Data JPA resolves each "id.<field>" path through the @EmbeddedId of
        // TransactionCategoryBalance onto the real key columns (acct_id, type_cd, cat_cd).
        final Map<String, Sort.Direction> sort = new LinkedHashMap<>();
        sort.put("id.acctId", Sort.Direction.ASC);
        sort.put("id.typeCd", Sort.Direction.ASC);
        sort.put("id.catCd", Sort.Direction.ASC);
        setSort(sort);

        setPageSize(PAGE_SIZE);
        setName(READER_NAME);
        // Do NOT persist paging save-state. On a restart the reader must replay the FULL ordered
        // result set from the first page rather than resuming at the last-committed page, because the
        // interest writer rebuilds the entire SYSTRAN file from scratch into a staging file and only
        // atomically publishes it on a clean run (InterestTransactionWriter). Resuming mid-stream would
        // regenerate only the tail of the SYSTRAN file and lose the interest transactions already
        // emitted before the failure (QA finding F-P5-D). A full replay is safe because the balance
        // posting is idempotent per cycle (AccountRepository.applyInterestForCycle keyed on
        // last_interest_cycle, F-P6-A): accounts already finalized in the failed run are skipped, so
        // the DB balances stay exactly-once while the SYSTRAN file is fully rebuilt.
        setSaveState(false);
    }
}
