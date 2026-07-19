package com.aws.carddemo.batch;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.Step;
import org.springframework.batch.core.configuration.annotation.StepScope;
import org.springframework.batch.core.job.builder.JobBuilder;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.core.step.builder.StepBuilder;
import org.springframework.batch.core.step.tasklet.Tasklet;
import org.springframework.batch.item.Chunk;
import org.springframework.batch.item.ExecutionContext;
import org.springframework.batch.item.file.FlatFileItemWriter;
import org.springframework.batch.item.file.builder.FlatFileItemWriterBuilder;
import org.springframework.batch.item.file.transform.PassThroughLineAggregator;
import org.springframework.batch.repeat.RepeatStatus;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.WritableResource;
import org.springframework.data.domain.Sort;
import org.springframework.transaction.PlatformTransactionManager;

import com.aws.carddemo.domain.CardXref;
import com.aws.carddemo.domain.Transaction;
import com.aws.carddemo.domain.TransactionCategory;
import com.aws.carddemo.domain.TransactionType;
import com.aws.carddemo.dto.report.ReportAccountTotals;
import com.aws.carddemo.dto.report.ReportGrandTotals;
import com.aws.carddemo.dto.report.ReportNameHeader;
import com.aws.carddemo.dto.report.ReportPageTotals;
import com.aws.carddemo.dto.report.TransactionDetailReport;
import com.aws.carddemo.dto.report.TransactionReportHeaders;
import com.aws.carddemo.repository.CardXrefRepository;
import com.aws.carddemo.repository.TransactionCategoryRepository;
import com.aws.carddemo.repository.TransactionRepository;
import com.aws.carddemo.repository.TransactionTypeRepository;
import com.aws.carddemo.util.CobolDecimal;

/**
 * Spring Batch job configuration that reproduces the AWS CardDemo daily transaction detail report.
 *
 * <p><strong>Origin:</strong> COBOL program {@code legacy/cbl/CBTRN03C.cbl} (source branch
 * {@code app/cbl/CBTRN03C.cbl}), orchestrated by {@code legacy/jcl/TRANREPT.jcl} which invokes the
 * cataloged procedure {@code legacy/proc/TRANREPT.prc}. Implements AAP &sect;0.4.1
 * ({@code CBTRN03C} + {@code TRANREPT.jcl} + {@code TRANREPT.prc} &rarr; this batch job),
 * &sect;0.6.1 ({@code BigDecimal} running totals, no floating point) and &sect;0.6.6 (deterministic
 * bytewise/{@code C}-collation ordering of the card key).</p>
 *
 * <h2>What the report does (COBOL parity)</h2>
 * <p>On z/OS, {@code TRANREPT.jcl} runs a two-step flow: a {@code SORT} step (defined in
 * {@code TRANREPT.prc}) orders the transaction file ascending by card number
 * ({@code SORT FIELDS=(TRAN-CARD-NUM,A)}) and filters it to the requested processing-date window via
 * {@code INCLUDE COND}, then {@code CBTRN03C} reads the card-ordered file and prints a 133-column,
 * paginated report with per-page, per-account (card) and grand totals. Each detail line resolves the
 * transaction type and category descriptions from their reference files and prints the account id
 * obtained by cross-referencing the card number. This configuration performs the equivalent work:
 * it reads the transactions in card-number order, applies the same inclusive date filter in code,
 * and drives the same control-break + pagination state machine, delegating all fixed-width line
 * rendering to the {@code com.aws.carddemo.dto.report} DTOs.</p>
 *
 * <h2>File / DD to Spring binding (AAP &sect;0.4.1, &sect;0.4.2)</h2>
 * <ul>
 *   <li>{@code TRANSACT-FILE} (sequential {@code CVTRA05Y}, pre-sorted by card number by the
 *       {@code TRANREPT.prc} {@code SORT}) &rarr; {@link TransactionRepository#findAll(Sort)} with
 *       {@code Sort.by("cardNum", "tranId")}: card number is the primary sort key (matching
 *       {@code SORT FIELDS=(TRAN-CARD-NUM,A)}); {@code tranId} is a deterministic secondary key so
 *       ties order reproducibly. The database uses {@code C}/{@code POSIX} collation, giving the
 *       bytewise/ASCII ordering of the {@code CHAR} card key required for legacy ordering parity
 *       (&sect;0.6.6).</li>
 *   <li>{@code XREF-FILE} ({@code CARDXREF} KSDS by card number, {@code CVACT03Y}) &rarr;
 *       {@link CardXrefRepository#findByXrefCardNum(String)} to obtain {@code XREF-ACCT-ID} for the
 *       current card's detail lines (resolved once per card at the control break, paragraph
 *       {@code 1500-A}).</li>
 *   <li>{@code TRANTYPE-FILE} ({@code CVTRA03Y}) &rarr;
 *       {@link TransactionTypeRepository#findById(Object)} to resolve {@code TRAN-TYPE-DESC}
 *       (paragraph {@code 1500-B}).</li>
 *   <li>{@code TRANCATG-FILE} ({@code CVTRA04Y}) &rarr;
 *       {@link TransactionCategoryRepository#findById(Object)} keyed by
 *       {@link TransactionCategory.TransactionCategoryId} to resolve {@code TRAN-CAT-TYPE-DESC}
 *       (paragraph {@code 1500-C}).</li>
 *   <li>{@code DATE-PARMS-FILE} ({@code DATEPARM}: {@code WS-START-DATE X(10)} + {@code FILLER X(1)}
 *       + {@code WS-END-DATE X(10)}, read by paragraph {@code 0550-DATEPARM-READ}) &rarr; the
 *       {@code startDate} / {@code endDate} job parameters ({@code yyyy-MM-dd}). {@code DATEPARM} is
 *       an internal control file, not an external interface, so modeling the window as job
 *       parameters preserves behavior without introducing a new interface (the choice is recorded in
 *       {@code docs/decision-log.md}).</li>
 *   <li>{@code REPORT-FILE} ({@code TRANREPT}, {@code LRECL 133 RECFM FB}) &rarr; a
 *       {@link FlatFileItemWriter} of exactly-133-character lines. The JCL output is a
 *       generation-data-group ({@code +1}); absent an explicit {@code outputPath} job parameter this
 *       job writes a timestamped file (job-instance versioning), preserving the GDG semantics
 *       (&sect;0.6.3).</li>
 * </ul>
 *
 * <h2>Report structure (paragraph {@code 1120-WRITE-HEADERS} and helpers)</h2>
 * <p>Each page opens with a four-line header block mirroring {@code 1120-WRITE-HEADERS}: the report
 * name header ({@link ReportNameHeader} &mdash; {@code DALYREPT} / "Daily Transaction Report" /
 * "Date Range: " + start + " to " + end), a blank line, the column headings
 * ({@link TransactionReportHeaders#TRANSACTION_HEADER_1}) and the separator rule
 * ({@link TransactionReportHeaders#TRANSACTION_HEADER_2} = 133 dashes). Detail lines are rendered by
 * {@link TransactionDetailReport}; page, account and grand totals by {@link ReportPageTotals},
 * {@link ReportAccountTotals} and {@link ReportGrandTotals} respectively. Every emitted record is
 * padded (or truncated) to exactly {@value #REPORT_RECORD_WIDTH} bytes
 * ({@code FD-REPTFILE-REC PIC X(133)}).</p>
 *
 * <h2>Behavioral decisions (recorded in {@code docs/decision-log.md})</h2>
 * <ul>
 *   <li><strong>Three independent {@code BigDecimal} accumulators.</strong> {@code WS-PAGE-TOTAL},
 *       {@code WS-ACCOUNT-TOTAL} and {@code WS-GRAND-TOTAL} are all {@code S9(09)V99}; each is
 *       incremented by every included detail amount via {@link BigDecimal#add(BigDecimal)} with no
 *       rounding (the COBOL {@code ADD} statements carry no {@code ROUNDED}). Only their reset
 *       boundaries differ. The grand total therefore equals the sum of all included detail amounts,
 *       as required by the migration spec. Floating-point types are never used (&sect;0.6.1).</li>
 *   <li><strong>Pagination on a detail-line counter.</strong> {@code WS-PAGE-SIZE} = 20. A per-page
 *       detail-line counter is maintained; when it reaches {@value #PAGE_SIZE} the page-total line is
 *       written, the page total and counter reset, and the header block is re-emitted for the next
 *       page (paragraphs {@code 1110-WRITE-PAGE-TOTALS} then {@code 1120-WRITE-HEADERS}).</li>
 *   <li><strong>Control break on card number.</strong> When {@code TRAN-CARD-NUM} changes, the
 *       previous card's account total is written and reset before the new card's first detail line,
 *       then {@code XREF-ACCT-ID} is resolved once for the new card. The account id printed on detail
 *       lines comes from the cross-reference, not from the transaction record (key insight from the
 *       migration spec).</li>
 *   <li><strong>End of report.</strong> After the last transaction the final account total (for the
 *       last card) is written, then the grand total. This follows the migration spec's explicit
 *       end-of-report ordering (AAP &sect;0.4.1 "Phase G" and the validation checklist); the
 *       divergence from the literal COBOL end-of-file path is recorded in the decision log.</li>
 *   <li><strong>Missing reference data.</strong> A missing card cross-reference, transaction type or
 *       transaction category surfaces as an {@link IllegalStateException} that fails the job,
 *       mirroring the COBOL {@code INVALID KEY} abend in paragraphs {@code 1500-A}/{@code 1500-B}/
 *       {@code 1500-C}. Card numbers are never included in exception messages (CWE-532).</li>
 * </ul>
 *
 * <h2>Dependencies deliberately not injected</h2>
 * <ul>
 *   <li>{@code util.FixedWidthRecordMapper} is <em>not</em> used: it parses binary fixed-width COBOL
 *       feed records via a field-definition layout and is not a Spring bean. The report record is a
 *       single {@code PIC X(133)} text field composed entirely by the {@code dto.report} DTOs'
 *       {@code toReportLine()} contracts, keeping all edit-mask formatting single-sourced in
 *       {@code dto.report.ReportAmountFormatter} (binding constraint: do not re-implement edit
 *       masks). Padding a composed line to 133 bytes is a trivial local concern.</li>
 *   <li>{@code util.CobolDecimal} is a non-instantiable static utility (private constructor); it is
 *       used statically via {@link CobolDecimal#nullToZero(BigDecimal)} to guard total accumulation,
 *       never constructor-injected.</li>
 *   <li>{@code exception.EndOfFileException} models a streaming-read end-of-file signal; it is not
 *       needed here because {@link TransactionRepository#findAll(Sort)} returns a materialized list
 *       whose iteration ends naturally, matching {@code FILE STATUS 10} as normal termination.</li>
 * </ul>
 *
 * <h2>Wiring note (AAP binding constraint)</h2>
 * <p>This class deliberately does <strong>not</strong> use {@code @EnableBatchProcessing} and does not
 * depend on {@code config/BatchConfig}. It relies on Spring Boot's Batch auto-configuration, which
 * supplies the persistent, restartable {@link JobRepository} and the
 * {@link PlatformTransactionManager} injected through the constructor &mdash; the same convention used
 * by the sibling batch configurations. The migration rationale is recorded in
 * {@code docs/decision-log.md} rather than in code comments.</p>
 *
 * <p>This configuration is stateless and thread-safe: it holds only immutable collaborators, and all
 * mutable report state lives in method-local variables within {@link #buildReportLines(List,
 * LocalDate, LocalDate)}. The {@code @StepScope} tasklet reads its inputs exclusively from the
 * per-execution job parameters.</p>
 */
@Configuration
public class TransactionReportJobConfig {

    /** Logger used for structured batch progress and diagnostics (never logs card numbers). */
    private static final Logger LOGGER = LoggerFactory.getLogger(TransactionReportJobConfig.class);

    /** Bean/registry name of the Spring Batch {@link Job} translating {@code CBTRN03C}. */
    static final String JOB_NAME = "transactionReportJob";

    /** Name of the single step within {@link #JOB_NAME}. */
    static final String STEP_NAME = "transactionReportStep";

    /**
     * Number of detail lines printed per page before a page-total line and a fresh header block are
     * emitted. Mirrors {@code WS-PAGE-SIZE PIC S9(03) VALUE 20} in {@code CBTRN03C}.
     */
    static final int PAGE_SIZE = 20;

    /**
     * Exact width, in bytes, of every emitted report record. Mirrors
     * {@code FD-REPTFILE-REC PIC X(133)} ({@code LRECL 133 RECFM FB}). Every header, detail and total
     * line is padded (or truncated) to this width.
     */
    static final int REPORT_RECORD_WIDTH = 133;

    /**
     * Width, in digits, of the account id printed on detail lines. The cross-reference
     * {@code XREF-ACCT-ID} is {@code PIC 9(11)}; moving it into the report's
     * {@code TRAN-REPORT-ACCOUNT-ID PIC X(11)} preserves leading zeros, so the {@code long} account
     * id is rendered zero-padded to this width.
     */
    static final int ACCOUNT_ID_WIDTH = 11;

    /** Fixed width of {@link ReportNameHeader#SHORT_NAME} ({@code REPT-SHORT-NAME PIC X(38)}). */
    private static final int NAME_HEADER_SHORT_WIDTH = 38;

    /** Fixed width of {@link ReportNameHeader#LONG_NAME} ({@code REPT-LONG-NAME PIC X(41)}). */
    private static final int NAME_HEADER_LONG_WIDTH = 41;

    /**
     * Fixed width of the {@code REPT-START-DATE} and {@code REPT-END-DATE} fields
     * ({@code PIC X(10)}). An ISO {@code yyyy-MM-dd} date is exactly this many characters.
     */
    private static final int NAME_HEADER_DATE_WIDTH = 10;

    /** Default directory for the generated report when no {@code outputPath} job parameter is given. */
    private static final String DEFAULT_OUTPUT_DIR = "target/reports";

    /**
     * Base name of the generation-data-group report dataset ({@code TRANREPT}). The default output
     * file name combines this with a timestamp to emulate the GDG {@code +1} generation.
     */
    private static final String DEFAULT_OUTPUT_BASENAME = "TRANREPT";

    /** Timestamp pattern used to emulate GDG generation versioning in the default output file name. */
    private static final DateTimeFormatter OUTPUT_TIMESTAMP =
            DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss");

    /**
     * Formatter used both to parse the {@code startDate}/{@code endDate} job parameters and to render
     * the reporting window in the name header. {@code yyyy-MM-dd} is exactly 10 characters, matching
     * {@code REPT-START-DATE}/{@code REPT-END-DATE PIC X(10)}, and its lexical order equals its
     * chronological order.
     */
    private static final DateTimeFormatter DATE_FORMAT = DateTimeFormatter.ISO_LOCAL_DATE;

    /** Auto-configured Spring Batch job repository used to build the step and the job. */
    private final JobRepository jobRepository;

    /** Auto-configured transaction manager bounding the tasklet's execution. */
    private final PlatformTransactionManager transactionManager;

    /** Source of the card-ordered transaction stream ({@code TRANSACT-FILE}). */
    private final TransactionRepository transactionRepository;

    /** Card-to-account cross-reference used to resolve {@code XREF-ACCT-ID} per card ({@code XREF-FILE}). */
    private final CardXrefRepository cardXrefRepository;

    /** Reference lookup for the transaction type description ({@code TRANTYPE-FILE}). */
    private final TransactionTypeRepository transactionTypeRepository;

    /** Reference lookup for the transaction category description ({@code TRANCATG-FILE}). */
    private final TransactionCategoryRepository transactionCategoryRepository;

    /**
     * Creates the configuration with the collaborators supplied by Spring Boot's Batch
     * auto-configuration and component scanning.
     *
     * @param jobRepository                 the auto-configured Spring Batch {@link JobRepository}
     * @param transactionManager            the auto-configured {@link PlatformTransactionManager}
     * @param transactionRepository         source of the card-ordered transactions ({@code TRANSACT-FILE})
     * @param cardXrefRepository            card cross-reference for {@code XREF-ACCT-ID} ({@code XREF-FILE})
     * @param transactionTypeRepository     transaction-type reference lookup ({@code TRANTYPE-FILE})
     * @param transactionCategoryRepository transaction-category reference lookup ({@code TRANCATG-FILE})
     */
    public TransactionReportJobConfig(JobRepository jobRepository,
            PlatformTransactionManager transactionManager,
            TransactionRepository transactionRepository,
            CardXrefRepository cardXrefRepository,
            TransactionTypeRepository transactionTypeRepository,
            TransactionCategoryRepository transactionCategoryRepository) {
        this.jobRepository = jobRepository;
        this.transactionManager = transactionManager;
        this.transactionRepository = transactionRepository;
        this.cardXrefRepository = cardXrefRepository;
        this.transactionTypeRepository = transactionTypeRepository;
        this.transactionCategoryRepository = transactionCategoryRepository;
    }

    /**
     * The single {@code @StepScope} tasklet that reproduces the {@code CBTRN03C} main procedure:
     * read the card-ordered transactions, apply the inclusive processing-date filter, drive the
     * control-break + pagination state machine, and write the resulting 133-byte lines to the report
     * file.
     *
     * <p>The reporting window is bound from the {@code startDate} and {@code endDate} job parameters
     * ({@code yyyy-MM-dd}, the {@code DATEPARM} equivalent read by paragraph {@code 0550-DATEPARM-READ}).
     * The optional {@code outputPath} job parameter selects the report file; when absent, a timestamped
     * file under {@value #DEFAULT_OUTPUT_DIR} emulates the JCL generation-data-group {@code +1} output.</p>
     *
     * <p>Because the bean is {@code @StepScope}, the {@code @Value} job-parameter expressions are
     * resolved lazily per step execution; {@link #transactionReportStep()} passes {@code null}
     * placeholders that the scoped proxy replaces with the real parameter values at run time.</p>
     *
     * @param startDateParam  the inclusive start of the reporting window ({@code WS-START-DATE}),
     *                        bound from job parameter {@code startDate} ({@code yyyy-MM-dd})
     * @param endDateParam    the inclusive end of the reporting window ({@code WS-END-DATE}), bound
     *                        from job parameter {@code endDate} ({@code yyyy-MM-dd})
     * @param outputPathParam optional target report path, bound from job parameter {@code outputPath};
     *                        {@code null}/blank selects the default timestamped GDG-style path
     * @return a {@link Tasklet} that generates the daily transaction report and returns
     *         {@link RepeatStatus#FINISHED}
     */
    @Bean
    @StepScope
    public Tasklet transactionReportTasklet(
            @Value("#{jobParameters['startDate']}") String startDateParam,
            @Value("#{jobParameters['endDate']}") String endDateParam,
            @Value("#{jobParameters['outputPath']}") String outputPathParam) {
        return (contribution, chunkContext) -> {
            LocalDate startDate = parseRequiredDate(startDateParam, "startDate");
            LocalDate endDate = parseRequiredDate(endDateParam, "endDate");
            if (endDate.isBefore(startDate)) {
                throw new IllegalArgumentException(
                        "Job parameter 'endDate' (" + endDate + ") must not precede 'startDate' ("
                                + startDate + ")");
            }

            // TRANREPT.prc SORT FIELDS=(TRAN-CARD-NUM,A): read the transactions in card-number order
            // (tranId as a deterministic secondary key). The C/POSIX database collation gives the
            // bytewise ordering of the CHAR card key required for legacy parity (AAP 0.6.6).
            List<Transaction> transactions =
                    transactionRepository.findAll(Sort.by("cardNum", "tranId"));

            List<String> reportLines = buildReportLines(transactions, startDate, endDate);

            WritableResource resource = resolveOutputResource(outputPathParam);
            writeReport(reportLines, resource);

            LOGGER.info("Daily transaction report generated: window=[{} .. {}], "
                            + "transactionsScanned={}, reportRecords={}, output=[{}]",
                    startDate, endDate, transactions.size(), reportLines.size(),
                    resource.getDescription());
            return RepeatStatus.FINISHED;
        };
    }

    /**
     * The single step of {@link #transactionReportJob()}, wrapping the {@code @StepScope}
     * {@link #transactionReportTasklet(String, String, String)} within the auto-configured
     * transaction boundary. The {@code null} arguments are placeholders: the scoped proxy resolves the
     * actual {@code startDate}/{@code endDate}/{@code outputPath} job parameters at run time.
     *
     * @return the {@code transactionReportStep} {@link Step}
     */
    @Bean
    public Step transactionReportStep() {
        return new StepBuilder(STEP_NAME, jobRepository)
                .tasklet(transactionReportTasklet(null, null, null), transactionManager)
                .build();
    }

    /**
     * The Spring Batch {@link Job} corresponding to COBOL program {@code CBTRN03C} orchestrated by
     * {@code TRANREPT.jcl}/{@code TRANREPT.prc}. It consists of the single
     * {@link #transactionReportStep()} and requires the {@code startDate} and {@code endDate} job
     * parameters (and an optional {@code outputPath}).
     *
     * @return the {@code transactionReportJob} {@link Job}
     */
    @Bean
    public Job transactionReportJob() {
        return new JobBuilder(JOB_NAME, jobRepository)
                .start(transactionReportStep())
                .build();
    }

    /**
     * Builds the complete daily transaction report as an ordered list of exactly-133-byte records,
     * reproducing the {@code CBTRN03C} main procedure and its {@code 1100}/{@code 1110}/{@code 1120}
     * write helpers.
     *
     * <p>This method is the behavioral heart of the job and is deliberately pure with respect to its
     * {@code transactions} input: it accepts the already card-ordered transaction list, applies the
     * inclusive processing-date filter, and drives the control-break + pagination state machine using
     * only method-local state, delegating every reference lookup to the injected repositories and
     * every line rendering to the {@code dto.report} DTOs. Keeping the logic here (rather than in the
     * tasklet lambda) makes it unit-testable with mocked repositories and without a database or file
     * system.</p>
     *
     * <p>Sequence for each included transaction (matching COBOL):</p>
     * <ol>
     *   <li><strong>Date filter</strong> ({@code TRAN-PROC-TS(1:10)} between {@code WS-START-DATE} and
     *       {@code WS-END-DATE} inclusive): a record whose {@code procTs} date is outside
     *       {@code [startDate, endDate]} (or is {@code null}) is skipped.</li>
     *   <li><strong>Control break</strong> on {@code TRAN-CARD-NUM}: on the first card, and on every
     *       card-number change, the previous card's account total is written and reset (except before
     *       the very first card), then {@code XREF-ACCT-ID} is resolved once for the new card
     *       (paragraph {@code 1500-A}).</li>
     *   <li><strong>Pagination</strong> ({@code 1110-WRITE-PAGE-TOTALS} then {@code 1120-WRITE-HEADERS}):
     *       when the page already holds {@value #PAGE_SIZE} detail lines, the page-total line is
     *       written, the page total and counter reset, and the header block is re-emitted before the
     *       next detail line.</li>
     *   <li><strong>Detail line</strong> ({@code 1120-WRITE-DETAIL}): the type and category
     *       descriptions are resolved (paragraphs {@code 1500-B}/{@code 1500-C}) and a
     *       {@link TransactionDetailReport} line is emitted, printing the cross-referenced account id
     *       rather than any value from the transaction record.</li>
     *   <li><strong>Accumulate</strong>: the page, account and grand totals are each incremented by
     *       the detail amount.</li>
     * </ol>
     *
     * <p>After the last transaction, the final account total (for the last card, if any detail line
     * was written) is emitted, then the grand total (AAP &sect;0.4.1 "Phase G").</p>
     *
     * @param transactions the card-ordered transactions to report (never {@code null})
     * @param startDate    the inclusive start of the reporting window ({@code WS-START-DATE})
     * @param endDate      the inclusive end of the reporting window ({@code WS-END-DATE})
     * @return the report as ordered, exactly-{@value #REPORT_RECORD_WIDTH}-byte records
     * @throws IllegalStateException if a required card cross-reference, transaction type or
     *                               transaction category is missing (COBOL {@code INVALID KEY} abend)
     */
    List<String> buildReportLines(List<Transaction> transactions, LocalDate startDate,
            LocalDate endDate) {
        List<String> out = new ArrayList<>();

        // Report name header carries the reporting window rendered as two 10-char yyyy-MM-dd strings.
        ReportNameHeader nameHeader =
                new ReportNameHeader(startDate.format(DATE_FORMAT), endDate.format(DATE_FORMAT));
        appendHeaderBlock(out, nameHeader);

        // WS-PAGE-TOTAL / WS-ACCOUNT-TOTAL / WS-GRAND-TOTAL (all S9(09)V99): three independent
        // accumulators, each incremented by every included detail amount, differing only in reset
        // boundary. No rounding: the COBOL ADD statements carry no ROUNDED (AAP 0.6.1).
        BigDecimal pageTotal = BigDecimal.ZERO;
        BigDecimal accountTotal = BigDecimal.ZERO;
        BigDecimal grandTotal = BigDecimal.ZERO;

        int pageDetailCount = 0;
        String currentCardNum = null;
        String currentAccountId = null;
        boolean firstCard = true;

        for (Transaction transaction : transactions) {
            LocalDateTime procTs = transaction.getProcTs();
            if (procTs == null) {
                continue;
            }
            LocalDate procDate = procTs.toLocalDate();
            if (procDate.isBefore(startDate) || procDate.isAfter(endDate)) {
                continue;
            }

            String cardNum = transaction.getCardNum();

            // Control break on card number (TRAN-CARD-NUM != WS-CURR-CARD-NUM).
            if (firstCard || !Objects.equals(cardNum, currentCardNum)) {
                if (!firstCard) {
                    out.add(toRecord(new ReportAccountTotals(accountTotal).toReportLine()));
                    accountTotal = BigDecimal.ZERO;
                }
                currentCardNum = cardNum;
                currentAccountId = resolveAccountId(cardNum);
                firstCard = false;
            }

            // Pagination: close the full page before writing the next detail line
            // (1110-WRITE-PAGE-TOTALS then 1120-WRITE-HEADERS).
            if (pageDetailCount == PAGE_SIZE) {
                out.add(toRecord(new ReportPageTotals(pageTotal).toReportLine()));
                pageTotal = BigDecimal.ZERO;
                pageDetailCount = 0;
                appendHeaderBlock(out, nameHeader);
            }

            String typeCd = transaction.getTranTypeCd();
            Integer catCd = transaction.getTranCatCd();
            String typeDesc = resolveTypeDescription(typeCd);
            String catDesc = resolveCategoryDescription(typeCd, catCd);
            BigDecimal amount = CobolDecimal.nullToZero(transaction.getTranAmt());

            TransactionDetailReport detail = new TransactionDetailReport(
                    transaction.getTranId(),
                    currentAccountId,
                    typeCd,
                    typeDesc,
                    (catCd == null) ? 0 : catCd,
                    catDesc,
                    transaction.getTranSource(),
                    transaction.getTranAmt());
            out.add(toRecord(detail.toReportLine()));

            pageTotal = pageTotal.add(amount);
            accountTotal = accountTotal.add(amount);
            grandTotal = grandTotal.add(amount);
            pageDetailCount++;
        }

        // End of report: final account total for the last card (if any), then the grand total.
        if (!firstCard) {
            out.add(toRecord(new ReportAccountTotals(accountTotal).toReportLine()));
        }
        out.add(toRecord(new ReportGrandTotals(grandTotal).toReportLine()));

        return out;
    }

    /**
     * Appends the four-line page header block, reproducing paragraph {@code 1120-WRITE-HEADERS}: the
     * report name header, a blank line, the column-heading line
     * ({@link TransactionReportHeaders#TRANSACTION_HEADER_1}) and the 133-dash separator rule
     * ({@link TransactionReportHeaders#TRANSACTION_HEADER_2}). Every line is normalized to exactly
     * {@value #REPORT_RECORD_WIDTH} bytes.
     *
     * @param out        the report accumulator to append to
     * @param nameHeader the populated report name header for the current reporting window
     */
    private static void appendHeaderBlock(List<String> out, ReportNameHeader nameHeader) {
        out.add(toRecord(composeNameHeaderLine(nameHeader)));
        out.add(toRecord(""));
        out.add(toRecord(TransactionReportHeaders.TRANSACTION_HEADER_1));
        out.add(toRecord(TransactionReportHeaders.TRANSACTION_HEADER_2));
    }

    /**
     * Composes the 115-byte {@code REPORT-NAME-HEADER} line ({@code CVTRA07Y}) from the fixed
     * {@link ReportNameHeader} literals and the runtime reporting-window dates:
     * {@code REPT-SHORT-NAME X(38)} + {@code REPT-LONG-NAME X(41)} + {@code REPT-DATE-HEADER X(12)} +
     * {@code REPT-START-DATE X(10)} + {@code FILLER ' to ' X(4)} + {@code REPT-END-DATE X(10)}. The
     * caller pads the result to the {@value #REPORT_RECORD_WIDTH}-byte record width. The
     * {@link ReportNameHeader} class intentionally does not assemble this line itself, so the byte
     * layout is reproduced here.
     *
     * @param nameHeader the header holding the two runtime date strings
     * @return the composed 115-character name-header line
     */
    private static String composeNameHeaderLine(ReportNameHeader nameHeader) {
        return padRight(ReportNameHeader.SHORT_NAME, NAME_HEADER_SHORT_WIDTH)
                + padRight(ReportNameHeader.LONG_NAME, NAME_HEADER_LONG_WIDTH)
                + ReportNameHeader.DATE_HEADER
                + padRight(nameHeader.getReptStartDate(), NAME_HEADER_DATE_WIDTH)
                + ReportNameHeader.DATE_SEPARATOR
                + padRight(nameHeader.getReptEndDate(), NAME_HEADER_DATE_WIDTH);
    }

    /**
     * Resolves {@code XREF-ACCT-ID} for a card number via the cross-reference (paragraph
     * {@code 1500-A READ CARDXREF-FILE}), rendering it zero-padded to {@value #ACCOUNT_ID_WIDTH}
     * digits. The COBOL {@code XREF-ACCT-ID PIC 9(11)} is moved into the report's
     * {@code TRAN-REPORT-ACCOUNT-ID PIC X(11)}, preserving leading zeros.
     *
     * @param cardNum the current card number (control-break key)
     * @return the 11-digit, zero-padded account id string
     * @throws IllegalStateException if no cross-reference exists for the card (COBOL {@code INVALID
     *                               KEY} abend). The card number is not included in the message
     *                               (CWE-532).
     */
    private String resolveAccountId(String cardNum) {
        CardXref xref = cardXrefRepository.findByXrefCardNum(cardNum)
                .orElseThrow(() -> new IllegalStateException(
                        "No card cross-reference found for the current card "
                                + "(CBTRN03C 1500-A INVALID KEY)"));
        Long acctId = xref.getXrefAcctId();
        if (acctId == null) {
            throw new IllegalStateException(
                    "Card cross-reference has no account id (CBTRN03C 1500-A INVALID KEY)");
        }
        return String.format("%0" + ACCOUNT_ID_WIDTH + "d", acctId);
    }

    /**
     * Resolves {@code TRAN-TYPE-DESC} for a transaction type code via the transaction-type reference
     * file (paragraph {@code 1500-B READ TRANTYPE-FILE}).
     *
     * @param typeCd the transaction type code ({@code TRAN-TYPE-CD})
     * @return the transaction type description
     * @throws IllegalStateException if no type record exists (COBOL {@code INVALID KEY} abend)
     */
    private String resolveTypeDescription(String typeCd) {
        return transactionTypeRepository.findById(typeCd)
                .map(TransactionType::getTranTypeDesc)
                .orElseThrow(() -> new IllegalStateException(
                        "No transaction type found for type code '" + typeCd
                                + "' (CBTRN03C 1500-B INVALID KEY)"));
    }

    /**
     * Resolves {@code TRAN-CAT-TYPE-DESC} for a transaction type/category pair via the
     * transaction-category reference file (paragraph {@code 1500-C READ TRANCATG-FILE}).
     *
     * @param typeCd the transaction type code ({@code TRAN-TYPE-CD})
     * @param catCd  the transaction category code ({@code TRAN-CAT-CD})
     * @return the transaction category description
     * @throws IllegalStateException if no category record exists (COBOL {@code INVALID KEY} abend)
     */
    private String resolveCategoryDescription(String typeCd, Integer catCd) {
        TransactionCategory.TransactionCategoryId id =
                new TransactionCategory.TransactionCategoryId(typeCd, catCd);
        return transactionCategoryRepository.findById(id)
                .map(TransactionCategory::getDescription)
                .orElseThrow(() -> new IllegalStateException(
                        "No transaction category found for type '" + typeCd + "' category '" + catCd
                                + "' (CBTRN03C 1500-C INVALID KEY)"));
    }

    /**
     * Normalizes a composed line to exactly {@value #REPORT_RECORD_WIDTH} bytes
     * ({@code FD-REPTFILE-REC PIC X(133)}): a shorter line is right-padded with spaces and a longer
     * line is truncated on the right, reproducing a COBOL {@code MOVE} into the fixed report record.
     *
     * @param line the composed report line
     * @return the line as exactly {@value #REPORT_RECORD_WIDTH} characters
     */
    private static String toRecord(String line) {
        return padRight(line, REPORT_RECORD_WIDTH);
    }

    /**
     * Left-justifies {@code value} in a fixed {@code width} field of spaces, reproducing COBOL's
     * {@code MOVE} into a {@code PIC X(width)} item: a {@code null} or shorter value is right-padded
     * with spaces and a longer value is truncated on the right to {@code width} characters.
     *
     * @param value the field value ({@code null} is treated as spaces)
     * @param width the fixed field width
     * @return the value rendered in exactly {@code width} characters
     */
    private static String padRight(String value, int width) {
        String v = (value == null) ? "" : value;
        if (v.length() >= width) {
            return v.substring(0, width);
        }
        return v + " ".repeat(width - v.length());
    }

    /**
     * Parses a required {@code yyyy-MM-dd} job parameter into a {@link LocalDate}, mirroring the
     * {@code DATEPARM} start/end dates read by paragraph {@code 0550-DATEPARM-READ}.
     *
     * @param raw       the raw job-parameter value
     * @param paramName the parameter name, used only in the error message
     * @return the parsed date
     * @throws IllegalArgumentException if the value is missing/blank or not a valid {@code yyyy-MM-dd}
     *                                  date
     */
    private static LocalDate parseRequiredDate(String raw, String paramName) {
        if (raw == null || raw.isBlank()) {
            throw new IllegalArgumentException(
                    "Job parameter '" + paramName + "' is required (yyyy-MM-dd)");
        }
        try {
            return LocalDate.parse(raw.trim(), DATE_FORMAT);
        } catch (DateTimeParseException ex) {
            throw new IllegalArgumentException(
                    "Job parameter '" + paramName + "' must be yyyy-MM-dd but was '" + raw + "'", ex);
        }
    }

    /**
     * Resolves the report output resource. An explicit {@code outputPath} job parameter is used
     * verbatim; otherwise a timestamped file under {@value #DEFAULT_OUTPUT_DIR} named after the
     * {@code TRANREPT} dataset is generated, emulating the JCL generation-data-group {@code +1}
     * output. The parent directory is created if necessary.
     *
     * @param outputPath the optional target path ({@code null}/blank selects the default)
     * @return a writable resource for the report file
     * @throws IOException if the parent directory cannot be created
     */
    private static WritableResource resolveOutputResource(String outputPath) throws IOException {
        String target = (outputPath == null || outputPath.isBlank())
                ? DEFAULT_OUTPUT_DIR + "/" + DEFAULT_OUTPUT_BASENAME + "."
                        + OUTPUT_TIMESTAMP.format(LocalDateTime.now()) + ".txt"
                : outputPath.trim();
        Path path = Paths.get(target);
        Path parent = path.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        return new FileSystemResource(path);
    }

    /**
     * Writes the 133-byte report records to the target resource using a {@link FlatFileItemWriter}
     * with a {@link PassThroughLineAggregator} (each already-formatted record is written verbatim,
     * followed by a newline). The writer is opened and closed within this call; the file is
     * overwritten if it already exists.
     *
     * @param reportLines the exactly-133-byte report records to write
     * @param resource    the target report resource ({@code REPORT-FILE})
     * @throws Exception if the writer fails to open, write or close (surfaced as a step failure)
     */
    private static void writeReport(List<String> reportLines, WritableResource resource)
            throws Exception {
        FlatFileItemWriter<String> writer = new FlatFileItemWriterBuilder<String>()
                .name("transactionReportItemWriter")
                .resource(resource)
                .lineAggregator(new PassThroughLineAggregator<>())
                .lineSeparator("\n")
                .encoding("UTF-8")
                .shouldDeleteIfExists(true)
                .transactional(false)
                .build();
        writer.afterPropertiesSet();
        writer.open(new ExecutionContext());
        try {
            writer.write(new Chunk<>(reportLines));
        } finally {
            writer.close();
        }
    }
}
