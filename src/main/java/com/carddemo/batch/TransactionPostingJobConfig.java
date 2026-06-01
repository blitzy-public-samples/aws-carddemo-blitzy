package com.carddemo.batch;

import com.carddemo.entity.Account;
import com.carddemo.entity.DailyTransaction;
import com.carddemo.entity.RejectedTransaction;
import com.carddemo.entity.Transaction;
import com.carddemo.repository.AccountRepository;
import com.carddemo.repository.DailyTransactionRepository;
import com.carddemo.repository.RejectedTransactionRepository;
import com.carddemo.repository.TransactionRepository;
import com.carddemo.util.DateConversionUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.Step;
import org.springframework.batch.core.job.builder.JobBuilder;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.core.step.builder.StepBuilder;
import org.springframework.batch.item.ItemWriter;
import org.springframework.batch.item.data.RepositoryItemReader;
import org.springframework.batch.item.data.builder.RepositoryItemReaderBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.domain.Sort;
import org.springframework.transaction.PlatformTransactionManager;

import java.util.HashMap;
import java.util.Map;

/**
 * Spring Batch configuration for the <strong>{@code transactionPostingJob}</strong> &mdash; the
 * Java/PostgreSQL replacement for the legacy POSTTRAN batch flow
 * ({@code app/jcl/POSTTRAN.jcl} driving the batch COBOL program {@code app/cbl/CBTRN02C.cbl}).
 *
 * <h2>Legacy mainframe behavior (POSTTRAN.jcl + CBTRN02C.cbl)</h2>
 * <p>{@code POSTTRAN.jcl} executes a single step {@code PGM=CBTRN02C} wired to six datasets:
 * {@code DALYTRAN} (the daily-transaction PS feed, input), {@code XREFFILE}
 * ({@code CARDXREF} KSDS), {@code ACCTFILE} ({@code ACCTDATA} KSDS), {@code TCATBALF}
 * ({@code TCATBALF} KSDS), {@code TRANFILE} (the {@code TRANSACT} master KSDS, the accepted
 * sink) and {@code DALYREJS} (a {@code LRECL=430} GDG, the reject sink). {@code CBTRN02C}
 * reads each daily-transaction record sequentially ({@code PERFORM UNTIL END-OF-DAILY-TRANS-FILE}),
 * validates it ({@code 1500-VALIDATE-TRAN}, codes 100/101/102/103), and on success posts it to
 * {@code TRANSACT}, upserts the matching {@code TCATBAL} record
 * ({@code 2700-UPDATE-TCATBAL}) and updates the {@code ACCOUNT} balance buckets
 * ({@code 2800-UPDATE-ACCOUNT-REC}); on failure it writes a reject record to {@code DALYREJS}
 * ({@code 2500-WRITE-REJECT-REC}). The job carries no {@code PARM}, so it accepts no required
 * job parameters.</p>
 *
 * <h2>Target Spring Batch design</h2>
 * <p>This {@code @Configuration} assembles a single-{@link Step} chunk-oriented {@link Job}:</p>
 * <ol>
 *   <li><strong>Reader</strong> &mdash; {@link #dailyTransactionReader()} pages the
 *       {@code daily_transactions} staging table (the materialized {@code DALYTRAN} feed,
 *       AAP &sect;0.6.6) in ascending {@code dalytranId} order via {@link RepositoryItemReader},
 *       reproducing the deterministic sequential read of the PS file.</li>
 *   <li><strong>Processor</strong> &mdash; the injected {@link TransactionPostingProcessor}
 *       reproduces {@code 1500-VALIDATE-TRAN} (PR-03/PR-04/PR-05): it resolves the card via the
 *       cross-reference, resolves the account, and applies the over-limit and expiration checks,
 *       emitting a {@link TransactionPostingProcessor.ProcessingResult} that is either
 *       {@code ACCEPTED} (carrying the resolved account id) or {@code REJECTED} (carrying the
 *       validation code 100/101/102/103 and the exact COBOL reason text).</li>
 *   <li><strong>Composite writer</strong> &mdash; {@link #transactionPostingWriter()} routes each
 *       result by {@link TransactionPostingProcessor.ProcessingResult#getStatus()}:
 *       <ul>
 *         <li><em>ACCEPTED</em> &rarr; persist a {@link Transaction} (the {@code TRANSACT} record),
 *             upsert the {@code TCATBAL} balance (PR-06) via
 *             {@link TransactionCategoryBalanceUpsertWriter}, and update the {@link Account}
 *             sign-based cycle buckets and running balance (PR-07) via
 *             {@link AccountBalanceUpdater}.</li>
 *         <li><em>REJECTED</em> &rarr; persist a {@link RejectedTransaction} (the {@code DALYREJS}
 *             430-byte reject sink) carrying the validation code and reason.</li>
 *       </ul>
 *       Both paths flip {@code DailyTransaction.processed = true} so reruns naturally skip
 *       already-accounted rows.</li>
 * </ol>
 *
 * <h2>Position in the critical batch sequence (PR-12)</h2>
 * <p>This is the <strong>first</strong> job in {@code POSTTRAN &rarr; INTCALC &rarr; COMBTRAN
 * &rarr; CREASTMT}. The {@code @Bean Job} is auto-registered with the Spring Batch
 * {@code JobRegistry} (by {@code BatchConfig}'s {@code JobRegistryBeanPostProcessor}) under the
 * name {@link #JOB_NAME}, so the {@code BatchAdminController} can launch it by name; it also
 * remains independently runnable.</p>
 *
 * <h2>Transaction model (PR-24)</h2>
 * <p>Each chunk is bracketed by a transaction supplied by the constructor-injected
 * {@link PlatformTransactionManager} (the CICS {@code SYNCPOINT} equivalent). The processor
 * ({@code @Transactional(MANDATORY, readOnly)}) and the writer collaborators
 * ({@code @Transactional(MANDATORY)}) participate in that chunk transaction; the account is
 * re-fetched inside the writer so it is a managed entity, and the optimistic-locking
 * {@code @Version} on {@link Account}/{@link Transaction} (PR-22) maps the VSAM exclusive
 * {@code READ UPDATE} / {@code REWRITE} lock semantics to non-blocking concurrency.</p>
 *
 * <h2>Notes on alignment with the committed dependencies</h2>
 * <ul>
 *   <li>{@link DailyTransaction} exposes the staging fields as {@code typeCd}/{@code categoryCd}/
 *       {@code source} (the {@code dalytranId} field is the non-key {@code tran_id} business
 *       identifier); these are copied onto the {@link Transaction}/{@link RejectedTransaction}
 *       fields of the same names.</li>
 *   <li>{@code Transaction.procTimestamp} is a {@code LocalDateTime}; PR-11's 26-character DB2
 *       external string is rendered at the I/O boundary (the mapper), so here the posting instant
 *       is normalized through the DB2 external format via {@link DateConversionUtil} to carry
 *       exactly the precision {@code CBTRN02C} emitted.</li>
 *   <li>{@link TransactionPostingProcessor.ProcessingResult#getValidationCode()} is a
 *       {@code Short}; {@code RejectedTransaction.validationCode} is an {@code Integer}, so the
 *       code is widened explicitly when building the reject record.</li>
 * </ul>
 *
 * @see TransactionPostingProcessor
 * @see TransactionCategoryBalanceUpsertWriter
 * @see AccountBalanceUpdater
 * @see com.carddemo.repository.DailyTransactionRepository
 */
@Configuration
@RequiredArgsConstructor
@Slf4j
public class TransactionPostingJobConfig {

    /**
     * Chunk size for the posting step. A chunk of {@value} rows is read, processed and written
     * inside one transaction (AAP &sect;0.6.3 default; the demonstration dataset is far smaller,
     * so the whole feed typically fits in a single chunk/page).
     */
    public static final int CHUNK_SIZE = 100;

    /**
     * Logical {@link Job} name. Matches the {@code transactionPostingJob} bean method name and the
     * name under which the job is auto-registered with the {@code JobRegistry}; used by the
     * {@code BatchAdminController} to launch POSTTRAN by name (PR-12).
     */
    public static final String JOB_NAME = "transactionPostingJob";

    /** Logical step name (the single chunk-oriented posting step). */
    private static final String STEP_NAME = "transactionPostingStep";

    /**
     * {@link RepositoryItemReader} name &mdash; the key prefix under which the reader saves its
     * paging state in the Spring Batch {@code ExecutionContext} for restart.
     *
     * <p>Deliberately namespaced with the {@code transactionPosting} prefix so it does not collide
     * with the structurally identical {@code RepositoryItemReader<DailyTransaction>} declared by
     * the diagnostic {@code DailyTransactionReadJobConfig}: a shared state-key prefix could clash
     * in the {@code ExecutionContext} and a shared Spring bean name triggers a
     * {@code BeanDefinitionOverrideException} (bean-definition overriding is disabled by default
     * under Spring Boot 3). The same value is reused as the explicit {@code @Bean} name on
     * {@link #dailyTransactionReader()} below.</p>
     */
    private static final String READER_NAME = "transactionPostingDailyTransactionReader";

    /**
     * Entity property the reader sorts on. {@code dalytranId} maps to the {@code tran_id} column;
     * a non-empty ascending sort is mandatory for {@link RepositoryItemReader} paging and
     * reproduces the deterministic sequential order in which {@code CBTRN02C} consumed the
     * {@code DALYTRAN} PS feed.
     */
    private static final String SORT_PROPERTY = "dalytranId";

    private final JobRepository jobRepository;
    private final PlatformTransactionManager transactionManager;
    private final DailyTransactionRepository dailyTransactionRepository;
    private final TransactionRepository transactionRepository;
    private final RejectedTransactionRepository rejectedTransactionRepository;
    private final AccountRepository accountRepository;
    private final TransactionPostingProcessor processor;
    private final TransactionCategoryBalanceUpsertWriter balanceUpsertWriter;
    private final AccountBalanceUpdater accountBalanceUpdater;

    /**
     * Chunk reader over the {@code daily_transactions} staging table.
     *
     * <p>Built on {@link RepositoryItemReader}, which invokes
     * {@code dailyTransactionRepository.findAll(Pageable)} one page at a time (the repository
     * extends {@code JpaRepository}, hence {@code PagingAndSortingRepository}). The configured
     * {@code methodName} {@code "findAll"} combined with the {@code PageRequest} the reader passes
     * resolves to {@code PagingAndSortingRepository.findAll(Pageable)}; the mandatory ascending
     * sort on {@link #SORT_PROPERTY} guarantees deterministic paging and reproduces the
     * sequential {@code DALYTRAN} read order. The page size is aligned with {@link #CHUNK_SIZE}
     * so each page maps to one chunk transaction.</p>
     *
     * <p>Reruns are safe: the writer flips {@code processed = true} on every row it accounts for,
     * and re-posting is idempotent ({@link Transaction} is keyed by its natural {@code tran_id};
     * the {@code TCATBAL} upsert and account update are deterministic), so a restart after a
     * failed chunk does not double-post committed work.</p>
     *
     * <p>The bean is registered under the explicit, unique name {@link #READER_NAME} (rather than
     * the default method-derived name {@code "dailyTransactionReader"}) to avoid a
     * {@code BeanDefinitionOverrideException} with the structurally identical reader bean declared
     * by {@code DailyTransactionReadJobConfig}. The factory method name is intentionally preserved
     * so intra-class wiring in {@link #transactionPostingStep()} resolves the singleton through the
     * standard {@code @Configuration} CGLIB factory-method interception.</p>
     *
     * @return a configured {@link RepositoryItemReader} streaming {@link DailyTransaction} rows
     */
    @Bean(name = READER_NAME)
    public RepositoryItemReader<DailyTransaction> dailyTransactionReader() {
        Map<String, Sort.Direction> sortMap = new HashMap<>();
        sortMap.put(SORT_PROPERTY, Sort.Direction.ASC);
        return new RepositoryItemReaderBuilder<DailyTransaction>()
                .name(READER_NAME)
                .repository(dailyTransactionRepository)
                .methodName("findAll")
                .sorts(sortMap)
                .pageSize(CHUNK_SIZE)
                .build();
    }

    /**
     * Composite writer that posts accepted transactions and sinks rejected ones, dispatching on
     * {@link TransactionPostingProcessor.ProcessingResult#getStatus()}.
     *
     * <p>For each {@code ACCEPTED} result it posts a {@link Transaction}, upserts the
     * {@code TCATBAL} balance (PR-06) and updates the {@link Account} buckets (PR-07); for each
     * {@code REJECTED} result it persists a {@link RejectedTransaction} (the {@code DALYREJS}
     * equivalent). A {@code null} item is skipped defensively. All work runs within the chunk
     * transaction supplied by the {@link PlatformTransactionManager} (PR-24).</p>
     *
     * @return an {@link ItemWriter} consuming {@link TransactionPostingProcessor.ProcessingResult}
     */
    @Bean
    public ItemWriter<TransactionPostingProcessor.ProcessingResult> transactionPostingWriter() {
        return chunk -> {
            for (TransactionPostingProcessor.ProcessingResult result : chunk) {
                if (result == null) {
                    // Defensive: the processor only returns null for a null input row (filtered).
                    continue;
                }
                DailyTransaction dt = result.getDailyTransaction();
                if (result.getStatus() == TransactionPostingProcessor.ProcessingResult.Status.REJECTED) {
                    writeRejected(dt, result.getValidationCode(), result.getRejectionReason());
                } else {
                    writeAccepted(dt, result.getResolvedAccountId());
                }
            }
        };
    }

    /**
     * Posts a validated daily transaction (the {@code CBTRN02C} accepted path).
     *
     * <p>Reproduces, in order: {@code 2600-WRITE-PROCESSED-TRAN} (build and write the
     * {@code TRANSACT} record), {@code 2700-UPDATE-TCATBAL} (PR-06 balance upsert) and
     * {@code 2800-UPDATE-ACCOUNT-REC} (PR-07 sign-based bucket + running-balance update), then
     * marks the staging row processed.</p>
     *
     * @param dt        the accepted staging row
     * @param accountId the account id the processor resolved via the cross-reference (PR-23: the
     *                  processor performed the XREF&rarr;ACCT reads; the writer performs the writes)
     */
    private void writeAccepted(DailyTransaction dt, Long accountId) {
        // === 1. Persist the accepted transaction (TRANSACT / 2600-WRITE-PROCESSED-TRAN) ===
        Transaction tx = new Transaction();
        // tran_id is the natural key: copy the DALYTRAN business id (DALYTRAN-ID -> TRAN-ID).
        tx.setTranId(dt.getDalytranId());
        tx.setTypeCd(dt.getTypeCd());
        tx.setCategoryCd(dt.getCategoryCd());
        tx.setSource(dt.getSource());
        tx.setDescription(dt.getDescription());
        tx.setAmount(dt.getAmount());
        tx.setMerchantId(dt.getMerchantId());
        tx.setMerchantName(dt.getMerchantName());
        tx.setMerchantCity(dt.getMerchantCity());
        tx.setMerchantZip(dt.getMerchantZip());
        tx.setCardNum(dt.getCardNum());
        // TRAN-ORIG-TS carries over unchanged from the daily feed.
        tx.setOrigTimestamp(dt.getOrigTimestamp());
        // PR-11: stamp TRAN-PROC-TS at posting time. The entity field is a LocalDateTime (the
        // 26-char DB2 external string is rendered at the mapper/JSON I/O boundary, AAP §0.6.5);
        // normalizing 'now' through the DB2 external format via DateConversionUtil yields a
        // LocalDateTime carrying exactly the timestamp precision CBTRN02C emitted.
        tx.setProcTimestamp(
                DateConversionUtil.fromDb2Timestamp(DateConversionUtil.nowAsDb2Timestamp()));
        transactionRepository.save(tx);

        // === 2. Upsert TransactionCategoryBalance (PR-06 / 2700-UPDATE-TCATBAL) ===
        // The DALYTRAN feed carries the category code as a numeric string (e.g. "0001"); the
        // String overload normalizes it to the canonical cat_cd CHAR(4) key before upserting.
        balanceUpsertWriter.upsertBalance(
                accountId, dt.getTypeCd(), dt.getCategoryCd(), dt.getAmount());

        // === 3. Update the Account balance (PR-07 / 2800-UPDATE-ACCOUNT-REC) ===
        // Re-fetch the account inside this chunk transaction so it is a managed entity; the
        // updater applies the sign-based credit/debit bucket + running-balance update and REWRITEs
        // (it persists the entity internally and honors @Version optimistic locking, PR-22).
        Account account = accountRepository.findById(accountId)
                .orElseThrow(() -> new IllegalStateException(
                        "Account " + accountId + " was resolved during validation but is absent "
                                + "at posting time (concurrent deletion?)"));
        accountBalanceUpdater.applyDailyTransaction(account, dt.getAmount());

        // === 4. Mark the staging row processed (idempotent rerun support) ===
        dt.setProcessed(Boolean.TRUE);
        dailyTransactionRepository.save(dt);

        log.debug("POSTTRAN accepted daily transaction id={} posted to account={} (amount={})",
                dt.getDalytranId(), accountId, dt.getAmount());
    }

    /**
     * Sinks a rejected daily transaction to the {@code DALYREJS} equivalent (the {@code CBTRN02C}
     * {@code 2500-WRITE-REJECT-REC} path).
     *
     * <p>Copies every {@code DALYTRAN} field onto the {@link RejectedTransaction} (the 350-byte
     * transaction image) and appends the validation code and exact COBOL reason text (the 80-byte
     * reject metadata, total 430 bytes), then marks the staging row processed.</p>
     *
     * @param dt     the rejected staging row
     * @param code   the validation code (100/101/102/103) from the processor; widened from
     *               {@code Short} to the entity's {@code Integer} column type
     * @param reason the exact COBOL reason text preserved verbatim (PR-03)
     */
    private void writeRejected(DailyTransaction dt, Short code, String reason) {
        RejectedTransaction rej = new RejectedTransaction();
        // id is a DB-generated surrogate (@GeneratedValue IDENTITY) — deliberately not set here.
        rej.setDalytranId(dt.getDalytranId());
        rej.setTypeCd(dt.getTypeCd());
        rej.setCategoryCd(dt.getCategoryCd());
        rej.setSource(dt.getSource());
        rej.setDescription(dt.getDescription());
        rej.setAmount(dt.getAmount());
        rej.setMerchantId(dt.getMerchantId());
        rej.setMerchantName(dt.getMerchantName());
        rej.setMerchantCity(dt.getMerchantCity());
        rej.setMerchantZip(dt.getMerchantZip());
        rej.setCardNum(dt.getCardNum());
        rej.setOrigTimestamp(dt.getOrigTimestamp());
        // ProcessingResult.getValidationCode() is a Short; the column/field is Integer — widen it
        // explicitly (boxed Short does not auto-convert to boxed Integer).
        rej.setValidationCode(code == null ? null : code.intValue());
        rej.setRejectionReason(reason);
        rejectedTransactionRepository.save(rej);

        // Mark the staging row processed for the rejected path too, mirroring the accepted path.
        dt.setProcessed(Boolean.TRUE);
        dailyTransactionRepository.save(dt);

        log.info("POSTTRAN rejected daily transaction id={} (code={}, reason='{}')",
                dt.getDalytranId(), code, reason);
    }

    /**
     * The single chunk-oriented posting step (the {@code CBTRN02C} {@code PERFORM UNTIL EOF}
     * loop).
     *
     * <p>Reads {@link DailyTransaction} rows, processes them into
     * {@link TransactionPostingProcessor.ProcessingResult} via the injected processor, and routes
     * them through {@link #transactionPostingWriter()}. Each chunk of {@link #CHUNK_SIZE} rows is
     * committed in one transaction managed by {@link #transactionManager} (PR-24).
     * {@code faultTolerant()} enables Spring Batch's skip/retry/restart infrastructure so a failed
     * run can resume from the last committed chunk via the {@code JobRepository}.</p>
     *
     * @return the configured posting {@link Step}
     */
    @Bean
    public Step transactionPostingStep() {
        return new StepBuilder(STEP_NAME, jobRepository)
                .<DailyTransaction, TransactionPostingProcessor.ProcessingResult>chunk(
                        CHUNK_SIZE, transactionManager)
                .reader(dailyTransactionReader())
                .processor(processor)
                .writer(transactionPostingWriter())
                .faultTolerant()
                .build();
    }

    /**
     * The POSTTRAN {@link Job} &mdash; a single-step job wrapping {@link #transactionPostingStep()}.
     *
     * <p>Registered with the {@code JobRegistry} under {@link #JOB_NAME} (PR-12) so it can be
     * launched by name and chained ahead of {@code INTCALC &rarr; COMBTRAN &rarr; CREASTMT}. The
     * job declares no required parameters, matching {@code POSTTRAN.jcl} (no {@code PARM}).</p>
     *
     * @return the configured {@link Job}
     */
    @Bean
    public Job transactionPostingJob() {
        return new JobBuilder(JOB_NAME, jobRepository)
                .start(transactionPostingStep())
                .build();
    }
}
