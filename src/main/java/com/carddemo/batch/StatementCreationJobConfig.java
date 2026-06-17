package com.carddemo.batch;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.Step;
import org.springframework.batch.core.configuration.annotation.StepScope;
import org.springframework.batch.core.job.builder.JobBuilder;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.core.step.builder.StepBuilder;
import org.springframework.batch.core.step.tasklet.Tasklet;
import org.springframework.batch.item.ItemWriter;
import org.springframework.batch.item.data.RepositoryItemReader;
import org.springframework.batch.item.data.builder.RepositoryItemReaderBuilder;
import org.springframework.batch.repeat.RepeatStatus;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.transaction.PlatformTransactionManager;

import com.carddemo.entity.Account;
import com.carddemo.entity.Transaction;
import com.carddemo.entity.TransactionCategory;
import com.carddemo.entity.TransactionType;
import com.carddemo.repository.AccountRepository;
import com.carddemo.repository.TransactionCategoryRepository;
import com.carddemo.repository.TransactionRepository;
import com.carddemo.repository.TransactionTypeRepository;
import com.carddemo.service.StatementService;
import com.carddemo.util.CardDemoConstants;

/**
 * Spring Batch&nbsp;5 configuration that hosts the <strong>two</strong> reporting jobs of the migrated
 * CardDemo application:
 *
 * <ol>
 *   <li><strong>{@code statementCreationJob}</strong> &mdash; the Java port of the COBOL statement
 *       batch {@code app/cbl/CBSTM03A.CBL} (and its VSAM-I/O subroutine {@code CBSTM03B.CBL}), the
 *       legacy {@code STMTGEN} step. It is a chunk-oriented job that iterates every account and
 *       delegates the per-account text&nbsp;+&nbsp;HTML statement assembly to
 *       {@link StatementService#generateStatement(Long, LocalDate, LocalDate)}. This configuration
 *       contains <em>no</em> statement-formatting logic &mdash; all of that lives in the service.</li>
 *   <li><strong>{@code transactionReportJob}</strong> &mdash; the Java port of the COBOL transaction
 *       detail report {@code app/cbl/CBTRN03C.cbl} (the F-009 batch utility, layout
 *       {@code app/cpy/CVTRA07Y.cpy}). It is a self-contained, single-tasklet
 *       <em>control-break</em> report writer that iterates accounts in ascending order, pages through
 *       each account's transactions in origination-timestamp order, filters them to an inclusive
 *       {@code [startDate, endDate]} window, resolves transaction-type and category descriptions from
 *       the reference tables, prints a detail line per transaction, accumulates a per-account subtotal
 *       and a grand total, and writes the formatted report under {@code report.output.path}.</li>
 * </ol>
 *
 * <h2>The {@code transactionReportJob} bean-name contract (do not rename)</h2>
 * <p>The {@code transactionReportJob} {@link Bean} method name is the Spring bean name and is the
 * <strong>exact</strong> key by which {@code com.carddemo.service.ReportService} resolves the report
 * job from its injected {@code Map<String, Job>} ({@code jobs.get("transactionReportJob")}). Renaming
 * the method would silently break the asynchronous report-submission contract (the service would raise
 * {@code IllegalStateException: Report job 'transactionReportJob' is not available}). The job reads its
 * filtering inputs from the <em>string</em> job parameters {@code reportType}, {@code startDate} and
 * {@code endDate} that {@code ReportService} sets (alongside a unique {@code run.id}); {@code startDate}
 * /{@code endDate} are ISO {@code yyyy-MM-dd} and are treated as the authoritative inclusive filter
 * range, while {@code reportType} (one of {@code MONTHLY}, {@code YEARLY}, {@code CUSTOM}) is used only
 * as a header label.</p>
 *
 * <h2>Batch-enablement model &mdash; OPTION&nbsp;A (Spring Boot auto-configuration)</h2>
 * <p>Consistent with {@code CardDemoApplication}, {@code config/BatchConfig} and the sibling
 * {@code *JobConfig} classes in this package, this configuration carries <strong>no</strong>
 * {@code @EnableBatchProcessing}. Under Spring Boot&nbsp;3.2 / Spring Batch&nbsp;5 the shared
 * infrastructure ({@link JobRepository}, {@link PlatformTransactionManager}, {@code JobLauncher} and the
 * {@code stepScope}/{@code jobScope} post-processors) is supplied by Boot's {@code BatchAutoConfiguration};
 * adding {@code @EnableBatchProcessing} would cause that auto-configuration to back off and break the
 * {@code BATCH_*} schema initialization and the on-demand launch model. Because the scope infrastructure
 * is present, the {@link StepScope} SpEL late binding used by the step-scoped writer and tasklet resolves
 * correctly at step-execution time. The auto-configured {@link JobRepository} and
 * {@link PlatformTransactionManager} are constructor-injected and handed directly to the
 * {@link JobBuilder} / {@link StepBuilder}. Jobs run on demand ({@code spring.batch.job.enabled=false});
 * nothing launches at startup.</p>
 *
 * <h2>Layering &mdash; allowed {@code batch &rarr; service} and {@code batch &rarr; repository} only</h2>
 * <p>This configuration imports collaborators only from the {@code service} layer
 * ({@link StatementService}) and the {@code repository}/{@code entity}/{@code util} packages. There is
 * deliberately <strong>no</strong> {@code service &rarr; batch} back-edge (the statement service imports
 * no Spring Batch type and nothing from {@code com.carddemo.batch}), so no circular dependency arises;
 * and the {@code transactionReportJob} performs its own data access through the injected repositories
 * rather than calling any service. The online transaction-id generator
 * ({@code com.carddemo.util.TranIdGenerator}) is intentionally not referenced &mdash; neither job
 * creates transactions.</p>
 *
 * <h2>Parity-critical conventions</h2>
 * <ul>
 *   <li><strong>Money.</strong> Every monetary accumulation uses {@link BigDecimal} at
 *       {@link CardDemoConstants#MONEY_SCALE} (scale&nbsp;2); {@code double}/{@code float} are never
 *       used. Subtotals and the grand total are exact additions, so no rounding is required, but the
 *       formatter normalizes the scale with {@link RoundingMode#HALF_UP} defensively.</li>
 *   <li><strong>Inclusive date window.</strong> A transaction is included when its origination date
 *       ({@code origTs.toLocalDate()}) is {@code >= startDate} (when supplied) and {@code <= endDate}
 *       (when supplied). A {@code null} bound imposes no constraint on that side, so {@code (null, null)}
 *       selects every transaction (mirroring {@code CBTRN03C}, which had no period filter beyond its
 *       date-parm range).</li>
 *   <li><strong>Description fallback.</strong> Transaction-type and category descriptions are resolved
 *       from {@link TransactionType}/{@link TransactionCategory} reference data via
 *       {@link Map#getOrDefault(Object, Object)}, falling back to the raw code when a description is
 *       absent &mdash; mirroring the {@code TRANTYPE}/{@code TRANCATG} lookups of {@code CBTRN03C}.</li>
 *   <li><strong>Count-agnostic paging.</strong> The report pages an account's transactions until the
 *       data source is exhausted ({@code while (page.hasNext())}); no record total is hard-coded.</li>
 * </ul>
 *
 * @see StatementService
 * @see com.carddemo.config.BatchConfig
 * @see InterestCalculationJobConfig
 * @see AccountRefreshJobConfig
 * @see <a href="file:app/cbl/CBSTM03A.CBL">app/cbl/CBSTM03A.CBL (statement text + HTML layout)</a>
 * @see <a href="file:app/cbl/CBTRN03C.cbl">app/cbl/CBTRN03C.cbl (transaction detail report)</a>
 * @see <a href="file:app/cpy/CVTRA07Y.cpy">app/cpy/CVTRA07Y.cpy (report record layout)</a>
 */
@Configuration
public class StatementCreationJobConfig {

    /** SLF4J logger; emits non-sensitive INFO markers (counts, paths) only &mdash; never PII or money. */
    private static final Logger log = LoggerFactory.getLogger(StatementCreationJobConfig.class);

    /**
     * Chunk / commit-interval size for the statement job and the page size of its account reader. This
     * is a pure throughput-tuning value (the per-account work is delegated to the service), so it is
     * defined locally rather than sourced from {@link CardDemoConstants}.
     */
    private static final int CHUNK_SIZE = 10;

    /**
     * Page size used while streaming an account's transactions inside the transaction-report tasklet.
     * A pure data-access tuning value: larger pages reduce round-trips for accounts with many
     * transactions without changing the report content.
     */
    private static final int REPORT_PAGE_SIZE = 200;

    /**
     * Total printable width of a detail row, reproduced from the column layout below
     * (16 + 1 + 10 + 1 + 15 + 1 + 29 + 1 + 30 + 1 + 18 = 123). Used to size the {@code '-'} rule lines
     * and to right-align the subtotal/grand-total amounts, echoing the {@code CVTRA07Y}
     * {@code TRANSACTION-HEADER-2} separator and {@code REPORT-*-TOTALS} dotted fillers.
     */
    private static final int REPORT_WIDTH = 123;

    /** Width of the right-aligned monetary column shared by detail rows and total rows. */
    private static final int AMOUNT_COL_WIDTH = 18;

    /**
     * Width of the dotted label area preceding a subtotal/grand-total amount, sized so the amount lands
     * in the same column as the detail-row amount ({@link #REPORT_WIDTH} &minus; {@link #AMOUNT_COL_WIDTH}
     * &minus; 1 separating space).
     */
    private static final int TOTAL_LABEL_WIDTH = REPORT_WIDTH - AMOUNT_COL_WIDTH - 1;

    /**
     * Shared {@code printf}-style template for both the column-header row and every detail row, so the
     * two stay aligned: transaction id (16), date (10), type description (15), category description
     * (29), free-text description (30) and a right-aligned amount ({@link #AMOUNT_COL_WIDTH}).
     */
    private static final String DETAIL_FORMAT = "%-16s %-10s %-15s %-29s %-30s %" + AMOUNT_COL_WIDTH + "s";

    /** Deterministic line separator (a literal {@code \n}) so generated reports are byte-stable. */
    private static final char NL = '\n';

    // ------------------------------------------------------------------------
    // Injected collaborators (all constructor-injected; single ctor => no @Autowired)
    // ------------------------------------------------------------------------

    /** Auto-configured Spring Batch metadata repository (Boot {@code BatchAutoConfiguration}, OPTION A). */
    private final JobRepository jobRepository;

    /** Auto-configured transaction manager bounding the chunk step and the report tasklet step. */
    private final PlatformTransactionManager transactionManager;

    /** Statement service owning the entire {@code CBSTM03A} per-account statement assembly/rendering. */
    private final StatementService statementService;

    /** Account master repository &mdash; supplies the statement reader and the report's account scan. */
    private final AccountRepository accountRepository;

    /** Transaction repository &mdash; supplies the paginated, origination-ordered per-account browse. */
    private final TransactionRepository transactionRepository;

    /** Transaction-type reference repository &mdash; source of type-code descriptions for the report. */
    private final TransactionTypeRepository transactionTypeRepository;

    /** Transaction-category reference repository &mdash; source of category descriptions for the report. */
    private final TransactionCategoryRepository transactionCategoryRepository;

    /**
     * Single all-arguments constructor. Because it is the sole constructor, Spring performs constructor
     * injection without an explicit {@code @Autowired}; every collaborator is stored in a {@code final}
     * field and is therefore immutable after construction. The {@code report.output.path} property is
     * intentionally <em>not</em> injected here &mdash; it is bound via {@code @Value} on the
     * {@code transactionReportTasklet} bean method (property injection) so the statement job, which
     * delegates output-path handling to {@link StatementService}, does not depend on it.
     *
     * @param jobRepository                 the auto-configured Spring Batch {@link JobRepository}
     * @param transactionManager            the auto-configured {@link PlatformTransactionManager}
     * @param statementService              the per-account statement generation service
     * @param accountRepository             repository for the account master
     * @param transactionRepository         repository for posted transactions (paginated browse)
     * @param transactionTypeRepository     repository for transaction-type reference data
     * @param transactionCategoryRepository repository for transaction-category reference data
     */
    public StatementCreationJobConfig(JobRepository jobRepository,
                                      PlatformTransactionManager transactionManager,
                                      StatementService statementService,
                                      AccountRepository accountRepository,
                                      TransactionRepository transactionRepository,
                                      TransactionTypeRepository transactionTypeRepository,
                                      TransactionCategoryRepository transactionCategoryRepository) {
        this.jobRepository = jobRepository;
        this.transactionManager = transactionManager;
        this.statementService = statementService;
        this.accountRepository = accountRepository;
        this.transactionRepository = transactionRepository;
        this.transactionTypeRepository = transactionTypeRepository;
        this.transactionCategoryRepository = transactionCategoryRepository;
    }

    // ========================================================================
    // statementCreationJob  (CBSTM03A / CBSTM03B port — STMTGEN statement batch)
    // ========================================================================

    /**
     * Reader for the {@code statementCreationJob}: streams every {@link Account} in ascending
     * {@code acctId} order so the batch generates statements deterministically, one account at a time.
     *
     * <p>It invokes {@link AccountRepository}'s inherited {@code findAll(Pageable)} (via
     * {@code methodName = "findAll"} with an empty argument list) and supplies the mandatory, non-empty
     * {@code sorts} map keyed by the entity field {@code acctId}. A {@link RepositoryItemReader}
     * <em>requires</em> a non-empty sort because it pages the repository and must impose a stable total
     * order across pages; omitting it throws at reader initialization.</p>
     *
     * @return a configured {@link RepositoryItemReader} streaming accounts in {@code acctId} order
     */
    @Bean
    public RepositoryItemReader<Account> statementAccountReader() {
        return new RepositoryItemReaderBuilder<Account>()
                .name("statementAccountReader")
                .repository(accountRepository)
                .methodName("findAll")
                .arguments(Collections.emptyList())
                .sorts(Map.of("acctId", Sort.Direction.ASC))
                .pageSize(CHUNK_SIZE)
                .build();
    }

    /**
     * Step-scoped chunk writer for the {@code statementCreationJob}. For every account in the chunk it
     * delegates the complete statement generation (text&nbsp;+&nbsp;HTML rendering and file output) to
     * {@link StatementService#generateStatement(Long, LocalDate, LocalDate)}; this method contains
     * <strong>no</strong> formatting logic of its own.
     *
     * <p>{@code @StepScope} is mandatory: the {@code startDate}/{@code endDate} job parameters are bound
     * by SpEL late binding, which can only be resolved once a {@code StepExecution} (and therefore the
     * launch-time parameters) exists. Each bound value is parsed leniently by
     * {@link #parseIsoDateOrNull(String)} so a blank/absent/"null" bound becomes a Java {@code null},
     * which the service interprets as an unbounded (all-time) side &mdash; matching its documented
     * contract.</p>
     *
     * <p>{@link ItemWriter} is a functional interface ({@code write(Chunk<? extends Account>)}), so the
     * writer is expressed as a lambda that iterates the supplied chunk.</p>
     *
     * @param startDateParam the inclusive lower-bound date (ISO {@code yyyy-MM-dd}) bound from the
     *                       {@code startDate} job parameter, or {@code null}/blank for no lower bound
     * @param endDateParam   the inclusive upper-bound date (ISO {@code yyyy-MM-dd}) bound from the
     *                       {@code endDate} job parameter, or {@code null}/blank for no upper bound
     * @return a step-scoped {@link ItemWriter} that generates one statement per account in the chunk
     */
    @Bean
    @StepScope
    public ItemWriter<Account> statementItemWriter(
            @Value("#{jobParameters['startDate']}") String startDateParam,
            @Value("#{jobParameters['endDate']}") String endDateParam) {
        final LocalDate start = parseIsoDateOrNull(startDateParam);
        final LocalDate end = parseIsoDateOrNull(endDateParam);
        return chunk -> {
            log.info("Generating statements for a chunk of {} account(s) [range {} .. {}]",
                    chunk.size(),
                    start == null ? "ALL" : start,
                    end == null ? "ALL" : end);
            for (Account account : chunk) {
                statementService.generateStatement(account.getAcctId(), start, end);
            }
        };
    }

    /**
     * The single chunk-oriented step of the {@code statementCreationJob}. Item input and output types
     * are identical ({@code <Account, Account>}) because there is no processor &mdash; each read account
     * is passed straight through to {@link #statementItemWriter(String, String)}, which performs the
     * per-account statement generation. The reader and the (step-scoped, proxied) writer are injected by
     * name; the {@link #transactionManager} bounds the chunk transaction.
     *
     * @param statementAccountReader the account reader bean (injected by name)
     * @param statementItemWriter    the step-scoped statement writer bean (injected by name)
     * @return the {@code statementCreationStep} {@link Step}
     */
    @Bean
    public Step statementCreationStep(RepositoryItemReader<Account> statementAccountReader,
                                      ItemWriter<Account> statementItemWriter) {
        return new StepBuilder("statementCreationStep", jobRepository)
                .<Account, Account>chunk(CHUNK_SIZE, transactionManager)
                .reader(statementAccountReader)
                .writer(statementItemWriter)
                .build();
    }

    /**
     * The {@code statementCreationJob}, composed of the single {@link #statementCreationStep(RepositoryItemReader, ItemWriter)}.
     * It is launched on demand through the auto-configured {@code JobLauncher} (typically with optional
     * {@code startDate}/{@code endDate} parameters and a unique {@code run.id}); it does not run at
     * startup ({@code spring.batch.job.enabled=false}). Running the single chunk step to completion
     * mirrors the linear account walk of {@code CBSTM03A}.
     *
     * @param statementCreationStep the statement step bean (injected by name)
     * @return the configured {@link Job} named {@code statementCreationJob}
     */
    @Bean
    public Job statementCreationJob(Step statementCreationStep) {
        return new JobBuilder("statementCreationJob", jobRepository)
                .start(statementCreationStep)
                .build();
    }

    // ========================================================================
    // transactionReportJob  (CBTRN03C port — transaction detail report, F-009)
    // ========================================================================

    /**
     * Step-scoped tasklet implementing the {@code CBTRN03C} <em>control-break</em> transaction detail
     * report. It is self-contained &mdash; it calls no service and performs its own data access through
     * the injected repositories.
     *
     * <p>The three string job parameters set by {@code ReportService} are bound here via SpEL late
     * binding ({@code @StepScope} is therefore mandatory): {@code reportType} (a header label, defaulted
     * to {@code CUSTOM} when blank), and the inclusive {@code startDate}/{@code endDate} window (parsed
     * leniently; a {@code null} bound is unbounded on that side). The {@code report.output.path}
     * directory is bound by property injection ({@code ${report.output.path}}) on this method rather
     * than in the constructor.</p>
     *
     * <p>The tasklet builds the type/category description lookup maps once, then delegates the report
     * rendering to {@link #writeTransactionReport(String, LocalDate, LocalDate, String, Map, Map)} and
     * returns {@link RepeatStatus#FINISHED} so the step executes exactly once. Any I/O failure surfaces
     * as an {@link UncheckedIOException}, failing the step visibly.</p>
     *
     * @param reportType      the report-type label bound from the {@code reportType} job parameter
     * @param startDateParam  the inclusive start date (ISO {@code yyyy-MM-dd}) bound from {@code startDate}
     * @param endDateParam    the inclusive end date (ISO {@code yyyy-MM-dd}) bound from {@code endDate}
     * @param reportOutputPath the output <em>directory</em> bound from the {@code report.output.path} property
     * @return a {@link Tasklet} that writes the transaction detail report and finishes
     */
    @Bean
    @StepScope
    public Tasklet transactionReportTasklet(
            @Value("#{jobParameters['reportType']}") String reportType,
            @Value("#{jobParameters['startDate']}") String startDateParam,
            @Value("#{jobParameters['endDate']}") String endDateParam,
            @Value("${report.output.path}") String reportOutputPath) {
        return (contribution, chunkContext) -> {
            final LocalDate start = parseIsoDateOrNull(startDateParam);
            final LocalDate end = parseIsoDateOrNull(endDateParam);
            final String label = (reportType == null || reportType.isBlank()) ? "CUSTOM" : reportType.trim();

            // Reference-data description maps (TRANTYPE / TRANCATG lookups), built once per run.
            final Map<String, String> typeDesc = buildTypeDescriptions();
            final Map<String, String> catDesc = buildCategoryDescriptions();

            Path outputFile = writeTransactionReport(label, start, end, reportOutputPath, typeDesc, catDesc);
            log.info("Transaction report written to {}", outputFile);
            return RepeatStatus.FINISHED;
        };
    }

    /**
     * The single tasklet step of the {@code transactionReportJob}, wrapping
     * {@link #transactionReportTasklet(String, String, String, String)}. Built with the Spring
     * Batch&nbsp;5 {@code StepBuilder.tasklet(Tasklet, PlatformTransactionManager)} overload so the
     * tasklet runs within a managed (read-only in practice) transaction; the step-scoped tasklet is
     * injected by name as a scoped proxy resolved per step execution.
     *
     * @param transactionReportTasklet the step-scoped report tasklet bean (injected by name)
     * @return the {@code transactionReportStep} {@link Step}
     */
    @Bean
    public Step transactionReportStep(Tasklet transactionReportTasklet) {
        return new StepBuilder("transactionReportStep", jobRepository)
                .tasklet(transactionReportTasklet, transactionManager)
                .build();
    }

    /**
     * The {@code transactionReportJob}, composed of the single
     * {@link #transactionReportStep(Tasklet)}.
     *
     * <p><strong>The bean name is exactly {@code transactionReportJob}.</strong> It is the key under
     * which {@code com.carddemo.service.ReportService} resolves this job from its injected
     * {@code Map<String, Job>} ({@code jobs.get("transactionReportJob")}) to submit it asynchronously
     * through the {@code JobLauncher}; the method must not be renamed. The job is launched on demand
     * with the {@code reportType}/{@code startDate}/{@code endDate} string parameters plus a unique
     * {@code run.id}; it does not run at startup ({@code spring.batch.job.enabled=false}).</p>
     *
     * @param transactionReportStep the report step bean (injected by name)
     * @return the configured {@link Job} named {@code transactionReportJob}
     */
    @Bean
    public Job transactionReportJob(Step transactionReportStep) {
        return new JobBuilder("transactionReportJob", jobRepository)
                .start(transactionReportStep)
                .build();
    }

    // ========================================================================
    // Report rendering helpers (transactionReportJob internals)
    // ========================================================================

    /**
     * Renders the full transaction detail report to a freshly created file under the configured output
     * directory and returns its path.
     *
     * <p>The {@code reportOutputPath} is treated as a directory (created if absent); the report file is
     * named {@code transaction-report-<label>-<epochMillis>.txt} so repeated submissions never collide.
     * Accounts are scanned in ascending {@code acctId} order; each account contributes a subtotal that
     * is folded into the grand total written as the report footer.</p>
     *
     * @param label            the report-type label printed in the header
     * @param start            inclusive lower bound on transaction origination date, or {@code null}
     * @param end              inclusive upper bound on transaction origination date, or {@code null}
     * @param reportOutputPath the output directory for the report file
     * @param typeDesc         transaction-type code &rarr; description lookup
     * @param catDesc          {@code typeCd-catCd} &rarr; category description lookup
     * @return the {@link Path} of the written report file
     * @throws UncheckedIOException if the directory or file cannot be created/written
     */
    private Path writeTransactionReport(String label,
                                        LocalDate start,
                                        LocalDate end,
                                        String reportOutputPath,
                                        Map<String, String> typeDesc,
                                        Map<String, String> catDesc) {
        try {
            Path dir = Path.of(reportOutputPath);
            Files.createDirectories(dir);
            Path outputFile = dir.resolve("transaction-report-" + label + "-" + System.currentTimeMillis() + ".txt");
            try (BufferedWriter writer = Files.newBufferedWriter(outputFile, StandardCharsets.UTF_8)) {
                writeReportHeader(writer, label, start, end);

                BigDecimal grandTotal = BigDecimal.ZERO.setScale(CardDemoConstants.MONEY_SCALE);
                for (Account account : accountRepository.findAll(Sort.by(Sort.Direction.ASC, "acctId"))) {
                    grandTotal = grandTotal.add(
                            writeAccountSection(writer, account, start, end, typeDesc, catDesc));
                }

                writeGrandTotal(writer, grandTotal);
            }
            return outputFile;
        } catch (IOException ex) {
            throw new UncheckedIOException("Failed to write transaction report to " + reportOutputPath, ex);
        }
    }

    /**
     * Writes one account's section of the report &mdash; an account header, a detail line per in-range
     * transaction (resolving type/category descriptions), and an account subtotal &mdash; and returns
     * the account's subtotal.
     *
     * <p>The account's transactions are streamed in origination-timestamp order via
     * {@link TransactionRepository#findByAcctIdOrderByOrigTs(Long, org.springframework.data.domain.Pageable)},
     * paging until exhausted (no count is assumed). Each transaction is filtered to the inclusive
     * {@code [start, end]} window on its origination date; a transaction with a {@code null} origination
     * timestamp is excluded whenever a bound is supplied (it cannot be placed within a bounded period)
     * and included only when both bounds are {@code null}. The account header and subtotal are emitted
     * <em>only</em> when at least one transaction falls in range, so accounts with no qualifying
     * activity produce no output and contribute {@link BigDecimal#ZERO} to the grand total.</p>
     *
     * @param writer   the open report writer
     * @param account  the account whose section is written
     * @param start    inclusive lower bound on origination date, or {@code null}
     * @param end      inclusive upper bound on origination date, or {@code null}
     * @param typeDesc transaction-type code &rarr; description lookup
     * @param catDesc  {@code typeCd-catCd} &rarr; category description lookup
     * @return the account subtotal (scale {@link CardDemoConstants#MONEY_SCALE}); {@code 0.00} if no
     *         transactions were in range
     * @throws IOException if writing to the report fails
     */
    private BigDecimal writeAccountSection(BufferedWriter writer,
                                           Account account,
                                           LocalDate start,
                                           LocalDate end,
                                           Map<String, String> typeDesc,
                                           Map<String, String> catDesc) throws IOException {
        final Long acctId = account.getAcctId();
        BigDecimal acctTotal = BigDecimal.ZERO.setScale(CardDemoConstants.MONEY_SCALE);
        boolean accountHeaderWritten = false;

        int page = 0;
        Page<Transaction> txnPage;
        do {
            txnPage = transactionRepository.findByAcctIdOrderByOrigTs(
                    acctId, PageRequest.of(page, REPORT_PAGE_SIZE));
            for (Transaction tran : txnPage.getContent()) {
                LocalDate origDate = tran.getOrigTs() == null ? null : tran.getOrigTs().toLocalDate();
                if (start != null && (origDate == null || origDate.isBefore(start))) {
                    continue;
                }
                if (end != null && (origDate == null || origDate.isAfter(end))) {
                    continue;
                }
                if (!accountHeaderWritten) {
                    writeAccountHeader(writer, acctId);
                    accountHeaderWritten = true;
                }
                writeDetailLine(writer, tran, origDate, typeDesc, catDesc);
                BigDecimal amt = tran.getAmt() == null ? BigDecimal.ZERO : tran.getAmt();
                acctTotal = acctTotal.add(amt);
            }
            page++;
        } while (txnPage.hasNext());

        if (accountHeaderWritten) {
            writeAccountTotal(writer, acctId, acctTotal);
        }
        return acctTotal;
    }

    /**
     * Writes the report title, date-range line, blank line, column header and a rule line, echoing the
     * {@code CVTRA07Y REPORT-NAME-HEADER} / {@code TRANSACTION-HEADER-1} / {@code TRANSACTION-HEADER-2}
     * structure of {@code CBTRN03C} while using the project's column set
     * (Tran&nbsp;ID, Date, Type, Category, Description, Amount).
     *
     * @param writer the open report writer
     * @param label  the report-type label
     * @param start  inclusive start date, or {@code null} (rendered as {@code ALL})
     * @param end    inclusive end date, or {@code null} (rendered as {@code ALL})
     * @throws IOException if writing fails
     */
    private void writeReportHeader(BufferedWriter writer, String label, LocalDate start, LocalDate end)
            throws IOException {
        line(writer, "Daily Transaction Report");
        line(writer, "TRANSACTION DETAIL REPORT - " + label);
        line(writer, "Date Range: " + (start == null ? "ALL" : start) + " to " + (end == null ? "ALL" : end));
        line(writer, "");
        line(writer, String.format(Locale.US, DETAIL_FORMAT,
                "Tran ID", "Date", "Type", "Category", "Description", "Amount"));
        line(writer, rule());
    }

    /**
     * Writes a per-account header line carrying the (non-sensitive) account id, emitted once before the
     * account's first in-range detail line.
     *
     * @param writer the open report writer
     * @param acctId the account identifier
     * @throws IOException if writing fails
     */
    private void writeAccountHeader(BufferedWriter writer, Long acctId) throws IOException {
        line(writer, "Account: " + acctId);
    }

    /**
     * Writes a single transaction detail line, resolving the transaction-type and category descriptions
     * (falling back to the raw code when a description is missing) and right-aligning the amount.
     *
     * @param writer   the open report writer
     * @param tran     the transaction to render
     * @param origDate the transaction's origination date (may be {@code null})
     * @param typeDesc transaction-type code &rarr; description lookup
     * @param catDesc  {@code typeCd-catCd} &rarr; category description lookup
     * @throws IOException if writing fails
     */
    private void writeDetailLine(BufferedWriter writer,
                                 Transaction tran,
                                 LocalDate origDate,
                                 Map<String, String> typeDesc,
                                 Map<String, String> catDesc) throws IOException {
        String typeCode = trimKey(tran.getTypeCd());
        String typeText = typeDesc.getOrDefault(typeCode, nz(tran.getTypeCd()));
        String catText = catDesc.getOrDefault(catKey(tran.getTypeCd(), tran.getCatCd()),
                String.valueOf(tran.getCatCd()));

        String row = String.format(Locale.US, DETAIL_FORMAT,
                fit(nz(tran.getTranId()), 16),
                origDate == null ? "" : origDate.toString(),
                fit(typeText, 15),
                fit(catText, 29),
                fit(nz(tran.getDescription()), 30),
                formatMoney(tran.getAmt()));
        line(writer, row);
    }

    /**
     * Writes the account subtotal line: a dotted {@code "Account Total (acct <id>)"} label followed by
     * the right-aligned signed subtotal, echoing the {@code CVTRA07Y REPORT-ACCOUNT-TOTALS} dotted
     * filler.
     *
     * @param writer    the open report writer
     * @param acctId    the account identifier
     * @param acctTotal the account subtotal
     * @throws IOException if writing fails
     */
    private void writeAccountTotal(BufferedWriter writer, Long acctId, BigDecimal acctTotal)
            throws IOException {
        String labelText = "Account Total (acct " + acctId + ")";
        line(writer, String.format(Locale.US, "%-" + TOTAL_LABEL_WIDTH + "s %" + AMOUNT_COL_WIDTH + "s",
                dotLabel(labelText, TOTAL_LABEL_WIDTH), formatSignedMoney(acctTotal)));
        line(writer, rule());
    }

    /**
     * Writes the grand-total footer line: a dotted {@code "Grand Total"} label followed by the
     * right-aligned signed grand total, echoing the {@code CVTRA07Y REPORT-GRAND-TOTALS} dotted filler.
     *
     * @param writer     the open report writer
     * @param grandTotal the report grand total
     * @throws IOException if writing fails
     */
    private void writeGrandTotal(BufferedWriter writer, BigDecimal grandTotal) throws IOException {
        line(writer, String.format(Locale.US, "%-" + TOTAL_LABEL_WIDTH + "s %" + AMOUNT_COL_WIDTH + "s",
                dotLabel("Grand Total", TOTAL_LABEL_WIDTH), formatSignedMoney(grandTotal)));
    }

    /**
     * Builds the transaction-type description lookup from {@link TransactionTypeRepository#findAll()},
     * keyed by the trimmed type code. Null descriptions are skipped so the detail-line fallback to the
     * raw code applies.
     *
     * @return a map of type code &rarr; type description (never {@code null})
     */
    private Map<String, String> buildTypeDescriptions() {
        List<TransactionType> types = transactionTypeRepository.findAll();
        Map<String, String> map = new HashMap<>();
        for (TransactionType type : types) {
            if (type.getTypeCd() != null && type.getTypeDesc() != null) {
                map.put(trimKey(type.getTypeCd()), type.getTypeDesc());
            }
        }
        return map;
    }

    /**
     * Builds the transaction-category description lookup from
     * {@link TransactionCategoryRepository#findAll()}, keyed by {@code typeCd-catCd} (read from the
     * entity's embedded id &mdash; the composite key is never constructed manually). Entries with a
     * null id or null description are skipped so the detail-line fallback to the raw category code
     * applies.
     *
     * @return a map of {@code typeCd-catCd} &rarr; category description (never {@code null})
     */
    private Map<String, String> buildCategoryDescriptions() {
        List<TransactionCategory> categories = transactionCategoryRepository.findAll();
        Map<String, String> map = new HashMap<>();
        for (TransactionCategory category : categories) {
            TransactionCategory.TransactionCategoryId id = category.getId();
            if (id != null && category.getCatTypeDesc() != null) {
                map.put(catKey(id.getTypeCd(), id.getCatCd()), category.getCatTypeDesc());
            }
        }
        return map;
    }

    // ------------------------------------------------------------------------
    // Small pure utilities (no I/O, no state)
    // ------------------------------------------------------------------------

    /**
     * Leniently parses an ISO {@code yyyy-MM-dd} date string into a {@link LocalDate}.
     *
     * <p>Returns {@code null} when the input is {@code null}, blank, or the literal text {@code "null"}
     * (which can arise from a missing job parameter rendered to a string), and also when the value is
     * not a valid ISO date &mdash; a {@link DateTimeParseException} is swallowed and treated as "no
     * bound". This leniency lets a {@code null}/absent {@code startDate}/{@code endDate} map cleanly to
     * an unbounded date window.</p>
     *
     * @param value the candidate ISO date string
     * @return the parsed {@link LocalDate}, or {@code null} for an absent/blank/invalid value
     */
    private static LocalDate parseIsoDateOrNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        if (trimmed.isEmpty() || "null".equalsIgnoreCase(trimmed)) {
            return null;
        }
        try {
            return LocalDate.parse(trimmed);
        } catch (DateTimeParseException ex) {
            log.warn("Ignoring unparseable ISO date '{}' (treated as no bound)", trimmed);
            return null;
        }
    }

    /**
     * Formats a monetary amount for a detail row: grouped thousands, two decimals, with a leading minus
     * for negatives (mirroring the {@code CVTRA07Y} {@code PIC -ZZZ,ZZZ,ZZZ.ZZ} edit). Locale-pinned to
     * {@code US} for deterministic {@code ,}/{@code .} separators. The value is normalized to
     * {@link CardDemoConstants#MONEY_SCALE} with {@link RoundingMode#HALF_UP} defensively.
     *
     * @param amount the amount (may be {@code null}, treated as {@code 0.00})
     * @return the formatted amount string
     */
    private static String formatMoney(BigDecimal amount) {
        BigDecimal value = (amount == null ? BigDecimal.ZERO : amount)
                .setScale(CardDemoConstants.MONEY_SCALE, RoundingMode.HALF_UP);
        return String.format(Locale.US, "%,.2f", value);
    }

    /**
     * Formats a total with an explicit leading sign ({@code +}/{@code -}), grouped thousands and two
     * decimals (mirroring the {@code CVTRA07Y} {@code PIC +ZZZ,ZZZ,ZZZ.ZZ} edit of the account/grand
     * totals). Locale-pinned to {@code US}; normalized to {@link CardDemoConstants#MONEY_SCALE} with
     * {@link RoundingMode#HALF_UP}.
     *
     * @param amount the total (may be {@code null}, treated as {@code 0.00})
     * @return the signed, formatted total string
     */
    private static String formatSignedMoney(BigDecimal amount) {
        BigDecimal value = (amount == null ? BigDecimal.ZERO : amount)
                .setScale(CardDemoConstants.MONEY_SCALE, RoundingMode.HALF_UP);
        String magnitude = String.format(Locale.US, "%,.2f", value.abs());
        return (value.signum() < 0 ? "-" : "+") + magnitude;
    }

    /**
     * Returns a {@code '-'} rule line {@link #REPORT_WIDTH} characters wide, echoing the
     * {@code CVTRA07Y TRANSACTION-HEADER-2} separator.
     *
     * @return the rule line
     */
    private static String rule() {
        return "-".repeat(REPORT_WIDTH);
    }

    /**
     * Appends {@code '.'} characters to {@code label} until it reaches {@code width}, mirroring the
     * dotted filler of the {@code CVTRA07Y} total rows. If the label is already at least {@code width}
     * characters it is truncated to {@code width}.
     *
     * @param label the label text
     * @param width the target width
     * @return the dotted, width-fitted label
     */
    private static String dotLabel(String label, int width) {
        String text = nz(label);
        if (text.length() >= width) {
            return text.substring(0, width);
        }
        return text + ".".repeat(width - text.length());
    }

    /**
     * Truncates {@code value} to at most {@code width} characters (no padding; padding is applied by the
     * column format), reproducing the fixed-width truncation of the COBOL report fields.
     *
     * @param value the value (must not be {@code null}; callers pass {@link #nz(String)} output)
     * @param width the maximum width
     * @return the value truncated to {@code width} characters
     */
    private static String fit(String value, int width) {
        return value.length() <= width ? value : value.substring(0, width);
    }

    /**
     * Null-safe string: returns {@code ""} for a {@code null} input, otherwise the input unchanged.
     *
     * @param value the value
     * @return {@code value} or {@code ""}
     */
    private static String nz(String value) {
        return value == null ? "" : value;
    }

    /**
     * Trims a code to a stable map key, guarding against {@code CHAR} column right-padding. Returns
     * {@code ""} for {@code null}.
     *
     * @param value the code
     * @return the trimmed code, or {@code ""}
     */
    private static String trimKey(String value) {
        return value == null ? "" : value.trim();
    }

    /**
     * Builds the category lookup key {@code <trimmedTypeCd>-<catCd>} used by both the map builder and
     * the detail-line lookup, so the two always agree regardless of {@code CHAR} padding.
     *
     * @param typeCd the transaction type code (may be {@code null})
     * @param catCd  the transaction category code (may be {@code null})
     * @return the composite key string
     */
    private static String catKey(String typeCd, Integer catCd) {
        return trimKey(typeCd) + "-" + catCd;
    }

    /**
     * Writes {@code text} followed by the deterministic {@link #NL} line separator.
     *
     * @param writer the open report writer
     * @param text   the line text
     * @throws IOException if writing fails
     */
    private static void line(BufferedWriter writer, String text) throws IOException {
        writer.write(text);
        writer.write(NL);
    }
}
