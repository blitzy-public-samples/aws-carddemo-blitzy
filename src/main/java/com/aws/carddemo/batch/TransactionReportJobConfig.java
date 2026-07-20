package com.aws.carddemo.batch;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.UncheckedIOException;
import java.io.Writer;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.stream.Stream;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.Step;
import org.springframework.batch.core.configuration.annotation.StepScope;
import org.springframework.batch.core.job.builder.JobBuilder;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.core.step.builder.StepBuilder;
import org.springframework.batch.core.step.tasklet.Tasklet;
import org.springframework.batch.repeat.RepeatStatus;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
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
import com.aws.carddemo.util.batch.BatchFilePathResolver;

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
 *       {@code TRANREPT.prc} {@code SORT}) &rarr; {@link TransactionRepository#streamAllByCardOrder()},
 *       a forward-only, cursor-backed stream ordered by card number then {@code tranId} ascending:
 *       card number is the primary sort key (matching {@code SORT FIELDS=(TRAN-CARD-NUM,A)});
 *       {@code tranId} is a deterministic secondary key so ties order reproducibly. Streaming (rather
 *       than a materialized {@code findAll} list) processes one row at a time with bounded memory
 *       (review finding #21). The database uses {@code C}/{@code POSIX} collation, giving the
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
 *   <li>{@code REPORT-FILE} ({@code TRANREPT}, {@code LRECL 133 RECFM FB}) &rarr; an undelimited
 *       fixed-block file of exactly-133-byte records (no line separator), written through the shared
 *       {@link BatchFilePathResolver}: the path is resolved against the batch safe root (traversal and
 *       symlink rejected), content is streamed to an owner-only ({@code 0600}) sibling temporary file,
 *       flushed to durable storage and atomically renamed onto the target, and the temporary file is
 *       removed if the step fails so a partial report is never published (review findings #17, #18).
 *       The JCL output is a generation-data-group ({@code +1}); absent an explicit {@code outputPath}
 *       job parameter this job writes {@value #DEFAULT_OUTPUT_DIR}{@code /}{@value
 *       #DEFAULT_OUTPUT_BASENAME}{@code .<jobInstanceId>.txt}, using the Spring Batch job-instance id
 *       as the restart-stable generation number so a restarted instance re-publishes the same file
 *       (&sect;0.6.3, review finding #19).</li>
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
 *   <li><strong>Pagination on the running line counter.</strong> {@code WS-PAGE-SIZE} = 20 and
 *       {@code WS-LINE-COUNTER} counts <em>every</em> physical record written to the report (each
 *       header line, each detail line, and each total/separator line), exactly as the COBOL
 *       {@code ADD 1 TO WS-LINE-COUNTER} statements in {@code 1120-WRITE-HEADERS} (+4),
 *       {@code 1120-WRITE-DETAIL} (+1), {@code 1110-WRITE-PAGE-TOTALS} (+2) and
 *       {@code 1120-WRITE-ACCOUNT-TOTALS} (+2) do. Before writing each detail line
 *       {@code 1100-WRITE-TRANSACTION-REPORT} evaluates {@code FUNCTION MOD(WS-LINE-COUNTER,
 *       WS-PAGE-SIZE) = 0}; when true it emits the page-total line and a fresh header block. Because
 *       the four header lines pre-charge the counter, the first page carries 16 detail lines and each
 *       subsequent page carries 14 (the page break itself adds 6 lines: a page total + separator then
 *       a 4-line header). Control-break account totals also advance the counter, so they shift
 *       pagination exactly as in the source.</li>
 *   <li><strong>Control break on card number.</strong> When {@code TRAN-CARD-NUM} changes, the
 *       previous card's account total is written and reset before the new card's first detail line
 *       (only once {@code WS-FIRST-TIME = 'N'}, i.e. not before the very first card), then
 *       {@code XREF-ACCT-ID} is resolved once for the new card. The account id printed on detail
 *       lines comes from the cross-reference, not from the transaction record (key insight from the
 *       migration spec).</li>
 *   <li><strong>End of report (source-faithful, review finding #31).</strong> The COBOL end-of-file
 *       branch ({@code CBTRN03C} lines 197-203) does <em>not</em> write a final account total for the
 *       last card. Instead, because {@code READ ... INTO TRAN-RECORD} leaves {@code TRAN-RECORD}
 *       unchanged at {@code AT END}, the last physical record's {@code TRAN-AMT} is still present and
 *       is added once more to both the page and account totals (the "stale-add" quirk); the final
 *       page-total line (with its {@code WS-GRAND-TOTAL} accumulation and 133-dash separator) and then
 *       the grand-total line are written. An empty input therefore yields exactly a zero page total,
 *       a separator and a zero grand total, with no header block (because {@code WS-FIRST-TIME} never
 *       flips). This job reproduces that behavior exactly; the earlier divergence (a forbidden final
 *       account total and a per-detail grand accumulation) is corrected and the fidelity decision is
 *       recorded in the decision log.</li>
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
 *       needed here because {@link TransactionRepository#streamAllByCardOrder()} ends its cursor
 *       naturally when the last row is consumed, matching {@code FILE STATUS 10} as normal
 *       termination.</li>
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
 * mutable report state lives in a fresh {@code ReportGenerator} instance created per invocation of
 * {@link #buildReportLines(List, LocalDate, LocalDate)} (and per tasklet run). The {@code @StepScope}
 * tasklet reads its inputs exclusively from the per-execution job parameters.</p>
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
     * file name combines this with the Spring Batch job-instance id to emulate the GDG {@code +1}
     * generation while remaining restart-stable (a restarted instance re-publishes the same file).
     */
    private static final String DEFAULT_OUTPUT_BASENAME = "TRANREPT";

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
     * Shared safe-path resolver used to resolve the report output path against the batch safe root and
     * to publish the report atomically (secure {@code 0600} temp &rarr; fsync &rarr; atomic rename,
     * with cleanup of the temp on failure). Centralizes review findings #17 (raw fixed-block output)
     * and #18 (safe path resolution + atomic publication).
     */
    private final BatchFilePathResolver batchFilePathResolver;

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
     * @param batchFilePathResolver         shared safe-path resolver for atomic report publication
     */
    public TransactionReportJobConfig(JobRepository jobRepository,
            PlatformTransactionManager transactionManager,
            TransactionRepository transactionRepository,
            CardXrefRepository cardXrefRepository,
            TransactionTypeRepository transactionTypeRepository,
            TransactionCategoryRepository transactionCategoryRepository,
            BatchFilePathResolver batchFilePathResolver) {
        this.jobRepository = jobRepository;
        this.transactionManager = transactionManager;
        this.transactionRepository = transactionRepository;
        this.cardXrefRepository = cardXrefRepository;
        this.transactionTypeRepository = transactionTypeRepository;
        this.transactionCategoryRepository = transactionCategoryRepository;
        this.batchFilePathResolver = batchFilePathResolver;
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

            // The report file name uses the Spring Batch job-instance id as a restart-stable GDG
            // generation number so a restarted instance re-publishes the same file (finding #19).
            long jobInstanceId = chunkContext.getStepContext().getStepExecution()
                    .getJobExecution().getJobInstance().getInstanceId();
            Path target = batchFilePathResolver
                    .resolveOutputTarget(resolveReportPath(outputPathParam, jobInstanceId));

            // Counters carried across the streaming publish callback: [0] transactions scanned,
            // [1] report records written (final long[] so the sink lambda can mutate them).
            final long[] counters = new long[2];

            // REPORT-FILE is LRECL 133 RECFM FB: write undelimited, exactly-133-byte records to a
            // secure 0600 temp and atomically publish, deleting the temp if the step fails so a
            // partial report is never left in place (findings #17, #18). TRANREPT.prc
            // SORT FIELDS=(TRAN-CARD-NUM,A) is reproduced by streamAllByCardOrder() (card number then
            // tranId ascending under C/POSIX collation, AAP 0.6.6); the cursor-backed stream is
            // consumed inside the step transaction so it stays valid and uses bounded memory
            // (finding #21).
            batchFilePathResolver.publish(target, out -> {
                Writer writer = new BufferedWriter(
                        new OutputStreamWriter(out, StandardCharsets.ISO_8859_1));
                Consumer<String> sink = line -> writeFixedRecord(writer, line, counters);
                try (Stream<Transaction> transactions = transactionRepository.streamAllByCardOrder()) {
                    ReportGenerator generator = new ReportGenerator(startDate, endDate, sink);
                    Iterator<Transaction> iterator = transactions.iterator();
                    while (iterator.hasNext()) {
                        generator.process(iterator.next());
                        counters[0]++;
                    }
                    generator.finish();
                }
                writer.flush();
            });

            LOGGER.info("Daily transaction report generated: window=[{} .. {}], "
                            + "transactionsScanned={}, reportRecords={}, output=[{}]",
                    startDate, endDate, counters[0], counters[1], target.getFileName());
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
     * Builds the complete daily transaction report as an ordered list of exactly-{@value
     * #REPORT_RECORD_WIDTH}-byte records, reproducing the {@code CBTRN03C} main procedure and its
     * {@code 1100}/{@code 1110}/{@code 1120} write helpers.
     *
     * <p>This overload is the in-memory, list-based entry point used by unit tests (mocked
     * repositories, no database or file system): it constructs a {@link ReportGenerator}, feeds it the
     * already card-ordered {@code transactions} in order, then finalizes the report. The streaming
     * tasklet ({@link #transactionReportTasklet(String, String, String)}) uses the same
     * {@link ReportGenerator} against a cursor-backed stream, so both paths share one behavioral
     * implementation. See {@link ReportGenerator} for the exact per-record and end-of-file semantics
     * (control break, line-counter pagination and the source end-of-file stale-add quirk).</p>
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
        ReportGenerator generator = new ReportGenerator(startDate, endDate, out::add);
        for (Transaction transaction : transactions) {
            generator.process(transaction);
        }
        generator.finish();
        return out;
    }

    /**
     * Stateful reproduction of the {@code CBTRN03C} report state machine, shared by the list-based
     * {@link #buildReportLines(List, LocalDate, LocalDate)} unit path and the streaming tasklet. Each
     * composed record is normalized to exactly {@value #REPORT_RECORD_WIDTH} bytes and pushed to the
     * supplied {@link Consumer} sink (a list accumulator in tests, the fixed-block file writer in the
     * job).
     *
     * <p><strong>Line counter (the pivot of source parity, review finding #31).</strong> The single
     * {@code lineCounter} mirrors {@code WS-LINE-COUNTER} and is incremented for <em>every</em>
     * physical record written, exactly as the COBOL {@code ADD 1 TO WS-LINE-COUNTER} statements do:
     * the four-line header block adds 4, each detail line adds 1, and each page-total and
     * account-total block adds 2 (its total line plus a 133-dash separator). Before writing each
     * detail line, {@code 1100-WRITE-TRANSACTION-REPORT} tests {@code FUNCTION MOD(WS-LINE-COUNTER,
     * WS-PAGE-SIZE) = 0}; when true it emits the page total and a fresh header block. This is why the
     * first page holds 16 detail lines (the opening header pre-charges the counter to 4, so the 17th
     * detail's pre-write test at counter 20 triggers the break) and each later page holds 14.</p>
     *
     * <p><strong>Per-record sequence ({@code process}).</strong> Every physical record first refreshes
     * {@link #lastPhysicalAmount} (the source {@code READ ... INTO TRAN-RECORD} overwrites
     * {@code TRAN-RECORD} on each successful read). A record whose {@code procTs} date lies outside
     * {@code [startDate, endDate]} (or is {@code null}) is skipped, mirroring the inclusive
     * {@code TRAN-PROC-TS(1:10)} filter (the source's {@code NEXT SENTENCE} on the else branch is
     * treated as a per-record skip; the ambiguity is recorded in the decision log). On a
     * {@code TRAN-CARD-NUM} change the previous card's account total is written (once
     * {@code WS-FIRST-TIME = 'N'}) and {@code XREF-ACCT-ID} is resolved once for the new card
     * ({@code 1500-A}). Then {@code 1100-WRITE-TRANSACTION-REPORT} writes the one-time opening headers,
     * applies the line-counter page break, adds the detail amount to the page and account totals, and
     * writes the {@link TransactionDetailReport} detail line (printing the cross-referenced account id,
     * not any value from the transaction record).</p>
     *
     * <p><strong>End of file ({@code finish}).</strong> Faithful to {@code CBTRN03C} lines 197-203, no
     * final account total is written; instead the retained last-record {@code TRAN-AMT} is added once
     * more to the page and account totals (the "stale-add" quirk), then the final page-total line
     * (which accumulates {@code WS-GRAND-TOTAL}) with its separator and the grand-total line are
     * written. For an empty input this yields exactly a zero page total, a separator and a zero grand
     * total, with no header block (because {@code WS-FIRST-TIME} never flips).</p>
     *
     * <p><strong>Totals.</strong> {@code WS-PAGE-TOTAL}, {@code WS-ACCOUNT-TOTAL} and
     * {@code WS-GRAND-TOTAL} are all {@code S9(09)V99} {@link BigDecimal} accumulators with no rounding
     * (the COBOL {@code ADD}s carry no {@code ROUNDED}; AAP &sect;0.6.1). The grand total is
     * accumulated only inside {@code 1110-WRITE-PAGE-TOTALS} (grand {@code +=} page), never per detail,
     * so it equals the sum of the page totals as the source computes it.</p>
     */
    private final class ReportGenerator {

        /** Sink receiving each exactly-133-byte record (list accumulator or file writer). */
        private final Consumer<String> sink;

        /** Inclusive start of the reporting window ({@code WS-START-DATE}). */
        private final LocalDate startDate;

        /** Inclusive end of the reporting window ({@code WS-END-DATE}). */
        private final LocalDate endDate;

        /** Report name header carrying the reporting window as two 10-char {@code yyyy-MM-dd} strings. */
        private final ReportNameHeader nameHeader;

        /** {@code WS-LINE-COUNTER}: counts every physical record written; drives pagination. */
        private long lineCounter;

        /** {@code WS-PAGE-TOTAL} ({@code S9(09)V99}). */
        private BigDecimal pageTotal = BigDecimal.ZERO;

        /** {@code WS-ACCOUNT-TOTAL} ({@code S9(09)V99}). */
        private BigDecimal accountTotal = BigDecimal.ZERO;

        /** {@code WS-GRAND-TOTAL} ({@code S9(09)V99}); accumulated only from page totals. */
        private BigDecimal grandTotal = BigDecimal.ZERO;

        /** {@code WS-CURR-CARD-NUM} (control-break key); {@code null} models the initial SPACES. */
        private String currentCardNum;

        /** Cross-referenced {@code XREF-ACCT-ID} for the current card, resolved once per control break. */
        private String currentAccountId;

        /** {@code WS-FIRST-TIME}: {@code true} until the first detail line writes the opening headers. */
        private boolean firstTime = true;

        /**
         * {@code TRAN-AMT} of the last physically read record, retained for the end-of-file stale-add
         * ({@code READ ... INTO} leaves {@code TRAN-RECORD} unchanged at {@code AT END}); {@code null}
         * when no record was read (empty input).
         */
        private BigDecimal lastPhysicalAmount;

        /**
         * @param startDate the inclusive start of the reporting window ({@code WS-START-DATE})
         * @param endDate   the inclusive end of the reporting window ({@code WS-END-DATE})
         * @param sink      receiver of each exactly-133-byte report record
         */
        ReportGenerator(LocalDate startDate, LocalDate endDate, Consumer<String> sink) {
            this.startDate = startDate;
            this.endDate = endDate;
            this.sink = sink;
            this.nameHeader =
                    new ReportNameHeader(startDate.format(DATE_FORMAT), endDate.format(DATE_FORMAT));
        }

        /**
         * Processes one physically-read transaction: refresh the retained amount, apply the inclusive
         * processing-date filter, handle the card-number control break, then write the transaction
         * report line(s). Mirrors the body of the {@code CBTRN03C} main {@code PERFORM UNTIL} loop
         * (lines 170-196).
         *
         * @param transaction the next card-ordered transaction (never {@code null})
         */
        void process(Transaction transaction) {
            // Every physical READ INTO TRAN-RECORD refreshes TRAN-AMT; the value survives at AT END.
            lastPhysicalAmount = CobolDecimal.nullToZero(transaction.getTranAmt());

            LocalDateTime procTs = transaction.getProcTs();
            if (procTs == null) {
                return;
            }
            LocalDate procDate = procTs.toLocalDate();
            if (procDate.isBefore(startDate) || procDate.isAfter(endDate)) {
                // TRAN-PROC-TS(1:10) outside [WS-START-DATE, WS-END-DATE]: per-record skip.
                return;
            }

            String cardNum = transaction.getCardNum();
            // Control break on TRAN-CARD-NUM (main loop lines 181-189).
            if (!Objects.equals(cardNum, currentCardNum)) {
                if (!firstTime) {
                    writeAccountTotals();
                }
                currentCardNum = cardNum;
                currentAccountId = resolveAccountId(cardNum);
            }
            writeTransactionReport(transaction);
        }

        /**
         * Finalizes the report at end of file, reproducing the main loop's end-of-file else branch
         * ({@code CBTRN03C} lines 197-203): the retained last-record {@code TRAN-AMT} is added once
         * more to the page and account totals (the stale-add quirk), then the page totals and grand
         * totals are written. No final account total is written.
         */
        void finish() {
            if (lastPhysicalAmount != null) {
                pageTotal = pageTotal.add(lastPhysicalAmount);
                accountTotal = accountTotal.add(lastPhysicalAmount);
            }
            writePageTotals();
            writeGrandTotals();
        }

        /**
         * Reproduces {@code 1100-WRITE-TRANSACTION-REPORT}: write the one-time opening headers, apply
         * the {@code FUNCTION MOD(WS-LINE-COUNTER, WS-PAGE-SIZE) = 0} page break, add the amount to the
         * page and account totals, then write the detail line.
         *
         * @param transaction the current in-window transaction
         */
        private void writeTransactionReport(Transaction transaction) {
            if (firstTime) {
                firstTime = false;
                writeHeaders();
            }
            if (lineCounter % PAGE_SIZE == 0) {
                writePageTotals();
                writeHeaders();
            }
            BigDecimal amount = CobolDecimal.nullToZero(transaction.getTranAmt());
            pageTotal = pageTotal.add(amount);
            accountTotal = accountTotal.add(amount);
            writeDetail(transaction);
        }

        /**
         * Reproduces {@code 1120-WRITE-HEADERS}: the report name header, a blank line, the
         * column-heading line ({@link TransactionReportHeaders#TRANSACTION_HEADER_1}) and the 133-dash
         * separator ({@link TransactionReportHeaders#TRANSACTION_HEADER_2}); advances the line counter
         * by four.
         */
        private void writeHeaders() {
            sink.accept(toRecord(composeNameHeaderLine(nameHeader)));
            sink.accept(toRecord(""));
            sink.accept(toRecord(TransactionReportHeaders.TRANSACTION_HEADER_1));
            sink.accept(toRecord(TransactionReportHeaders.TRANSACTION_HEADER_2));
            lineCounter += 4;
        }

        /**
         * Reproduces {@code 1120-WRITE-DETAIL}: resolve the type/category descriptions
         * ({@code 1500-B}/{@code 1500-C}), emit the {@link TransactionDetailReport} line printing the
         * cross-referenced account id, then advance the line counter by one.
         *
         * @param transaction the current in-window transaction
         */
        private void writeDetail(Transaction transaction) {
            String typeCd = transaction.getTranTypeCd();
            Integer catCd = transaction.getTranCatCd();
            String typeDesc = resolveTypeDescription(typeCd);
            String catDesc = resolveCategoryDescription(typeCd, catCd);
            TransactionDetailReport detail = new TransactionDetailReport(
                    transaction.getTranId(),
                    currentAccountId,
                    typeCd,
                    typeDesc,
                    (catCd == null) ? 0 : catCd,
                    catDesc,
                    transaction.getTranSource(),
                    transaction.getTranAmt());
            sink.accept(toRecord(detail.toReportLine()));
            lineCounter += 1;
        }

        /**
         * Reproduces {@code 1110-WRITE-PAGE-TOTALS}: write the page-total line, accumulate it into the
         * grand total, reset the page total, then write the 133-dash separator; advances the line
         * counter by two. The grand total is accumulated here (never per detail), matching the source.
         */
        private void writePageTotals() {
            sink.accept(toRecord(new ReportPageTotals(pageTotal).toReportLine()));
            grandTotal = grandTotal.add(pageTotal);
            pageTotal = BigDecimal.ZERO;
            lineCounter += 1;
            sink.accept(toRecord(TransactionReportHeaders.TRANSACTION_HEADER_2));
            lineCounter += 1;
        }

        /**
         * Reproduces {@code 1120-WRITE-ACCOUNT-TOTALS}: write the account-total line for the card just
         * ended, reset the account total, then write the 133-dash separator; advances the line counter
         * by two.
         */
        private void writeAccountTotals() {
            sink.accept(toRecord(new ReportAccountTotals(accountTotal).toReportLine()));
            accountTotal = BigDecimal.ZERO;
            lineCounter += 1;
            sink.accept(toRecord(TransactionReportHeaders.TRANSACTION_HEADER_2));
            lineCounter += 1;
        }

        /**
         * Reproduces {@code 1110-WRITE-GRAND-TOTALS}: write the grand-total line only (the source does
         * not change the line counter here).
         */
        private void writeGrandTotals() {
            sink.accept(toRecord(new ReportGrandTotals(grandTotal).toReportLine()));
        }
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
     * Resolves the raw report output path. An explicit {@code outputPath} job parameter is used
     * verbatim (trimmed); otherwise a file under {@value #DEFAULT_OUTPUT_DIR} named after the
     * {@code TRANREPT} dataset and the Spring Batch job-instance id is generated, emulating the JCL
     * generation-data-group {@code +1} output while remaining restart-stable (finding #19). The
     * returned string is later passed to {@link BatchFilePathResolver#resolveOutputTarget(String)},
     * which enforces safe-root containment, rejects symbolic links and creates the parent directory.
     *
     * @param outputPath    the optional target path ({@code null}/blank selects the default)
     * @param jobInstanceId the Spring Batch job-instance id used as the default generation number
     * @return the raw output path string to resolve against the batch safe root
     */
    private static String resolveReportPath(String outputPath, long jobInstanceId) {
        if (outputPath != null && !outputPath.isBlank()) {
            return outputPath.trim();
        }
        return DEFAULT_OUTPUT_DIR + "/" + DEFAULT_OUTPUT_BASENAME + "." + jobInstanceId + ".txt";
    }

    /**
     * Writes one already-composed report record to the fixed-block writer with no trailing delimiter,
     * enforcing the exact {@value #REPORT_RECORD_WIDTH}-byte record length ({@code FD-REPTFILE-REC
     * PIC X(133)}, {@code LRECL 133 RECFM FB}). The record content is pure ASCII (digits, uppercase
     * letters, spaces, dashes and edit-mask punctuation), so under {@link StandardCharsets#ISO_8859_1}
     * one character equals one byte and the {@link String#length()} check equals the byte-length
     * contract enforced elsewhere by {@code FixedBlockLineAggregator} (finding #17).
     *
     * @param writer   the fixed-block report writer (ISO-8859-1, unbuffered flushing deferred to the
     *                 caller)
     * @param line     the composed record, already normalized to exactly {@value #REPORT_RECORD_WIDTH}
     *                 characters by {@link #toRecord(String)}
     * @param counters the shared counter array; index {@code 1} (records written) is incremented
     * @throws IllegalStateException if {@code line} is not exactly {@value #REPORT_RECORD_WIDTH}
     *                               characters (a programming error in record composition)
     * @throws UncheckedIOException  if the underlying writer fails (surfaced as a step failure so the
     *                               partial temp file is cleaned up and no report is published)
     */
    private static void writeFixedRecord(Writer writer, String line, long[] counters) {
        if (line.length() != REPORT_RECORD_WIDTH) {
            throw new IllegalStateException("Report record must be exactly " + REPORT_RECORD_WIDTH
                    + " bytes but was " + line.length());
        }
        try {
            writer.write(line);
        } catch (IOException ex) {
            throw new UncheckedIOException("Failed to write report record", ex);
        }
        counters[1]++;
    }
}
