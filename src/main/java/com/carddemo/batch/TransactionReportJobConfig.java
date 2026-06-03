package com.carddemo.batch;

import com.carddemo.entity.Transaction;
import com.carddemo.repository.TransactionRepository;
import com.carddemo.util.BatchOutputPathResolver;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.JobParameters;
import org.springframework.batch.core.Step;
import org.springframework.batch.core.StepContribution;
import org.springframework.batch.core.job.builder.JobBuilder;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.core.scope.context.ChunkContext;
import org.springframework.batch.core.step.builder.StepBuilder;
import org.springframework.batch.core.step.tasklet.Tasklet;
import org.springframework.batch.repeat.RepeatStatus;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.PlatformTransactionManager;

import java.io.BufferedWriter;
import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * Spring Batch configuration for the <strong>{@code transactionReportJob}</strong> — the
 * Java/PostgreSQL replacement for the legacy mainframe transaction-detail report driven by
 * {@code app/jcl/TRANREPT.jcl} (which invokes the {@code REPROC} procedure
 * {@code app/proc/TRANREPT.prc}) and the COBOL batch program {@code app/cbl/CBTRN03C.cbl}.
 *
 * <h2>Legacy mainframe behavior (TRANREPT.jcl + TRANREPT.prc + CBTRN03C.cbl)</h2>
 * The original job ran three logical steps:
 * <ol>
 *   <li><strong>Unload</strong> — REPRO the {@code TRANSACT} VSAM KSDS to a flat backup
 *       ({@code TRANSACT.BKUP}).</li>
 *   <li><strong>Filter &amp; sort</strong> — an {@code EXEC PGM=SORT} step that
 *       {@code INCLUDE}d only records whose {@code TRAN-PROC-DT} (the date portion of the
 *       processing timestamp) fell within {@code [PARM-START-DATE, PARM-END-DATE]} and sorted
 *       the survivors by {@code TRAN-CARD-NUM} ascending (the {@code TRANSACT.AIX}
 *       alternate-index key).</li>
 *   <li><strong>Report</strong> — {@code EXEC PGM=CBTRN03C}, which read the date-parameter file
 *       and the sorted transactions and emitted a fixed-width report ({@code LRECL=133}) using the
 *       {@code CVTRA07Y} layout: a name/date-range header, column headings, one detail line per
 *       transaction, per-page subtotals, a per-card ("Account Total") subtotal at every card
 *       break, and a final grand total.</li>
 * </ol>
 *
 * <h2>Modernized Spring Batch behavior</h2>
 * Per AAP &sect;0.4.1.3 the entire job collapses into a single read-only {@link Step} backed by a
 * {@link Tasklet}. The tasklet:
 * <ol>
 *   <li>reads the optional {@code startDate} / {@code endDate} / {@code outputDir}
 *       {@link JobParameters};</li>
 *   <li>fetches the in-range transactions through
 *       {@link TransactionRepository#findByOrigTimestampBetween(LocalDateTime, LocalDateTime)} —
 *       which is backed by the PostgreSQL B-tree index {@code idx_transaction_orig_ts} that
 *       replaces the VSAM {@code TRANSACT.AIX} alternate index (AAP &sect;0.6.13);</li>
 *   <li>sorts the result by {@code cardNum} then {@code tranId} in memory (reproducing the JCL
 *       {@code SORT FIELDS=(TRAN-CARD-NUM,A)} card grouping); and</li>
 *   <li>writes a fixed-width text report with a header, one detail line per transaction, a
 *       per-card subtotal at every card break (the COBOL {@code 1120-WRITE-ACCOUNT-TOTALS}
 *       equivalent) and a closing grand total ({@code 1110-WRITE-GRAND-TOTALS}).</li>
 * </ol>
 *
 * <p>The original program's page-break / line-counter bookkeeping
 * ({@code WS-LINE-COUNTER}, {@code WS-PAGE-SIZE}, {@code WS-PAGE-TOTAL}) is intentionally
 * simplified away: the modern report is a single continuous text stream, so only the
 * card-grouped subtotals and the grand total are preserved. The COBOL XREF / TRANTYPE / TRANCATG
 * look-ups (which decorated each detail line with the account id and the type/category
 * descriptions) are out of scope for this job — their repositories are not dependencies of this
 * configuration — so each detail line carries exactly the fields available on the
 * {@link Transaction} entity itself ({@code tranId}, {@code cardNum}, {@code origTimestamp},
 * {@code amount}).</p>
 *
 * <h2>Refactoring rules enforced</h2>
 * <ul>
 *   <li><strong>PR-11</strong> (DB2 timestamp format): {@code origTimestamp} is the normalized
 *       {@link LocalDateTime} form of the 26-character DB2 external timestamp
 *       {@code YYYY-MM-DD-HH.MM.SS.MIL0000}; it is rendered back to that textual form
 *       ({@link #DB2_TIMESTAMP}, pattern {@code yyyy-MM-dd-HH.mm.ss.SS'0000'}) only at this report
 *       I/O boundary.</li>
 *   <li><strong>PR-16</strong> (exact money arithmetic): every subtotal and the grand total are
 *       {@link BigDecimal} held at {@link #MONEY_SCALE scale 2} with
 *       {@link RoundingMode#HALF_UP}; {@code float}/{@code double} are never used for monetary
 *       values.</li>
 *   <li><strong>PR-24</strong> (unit-of-work boundary): the step runs inside the injected
 *       {@link PlatformTransactionManager}; the report is read-only and performs no writes,
 *       mirroring the {@code SYNCPOINT}-equivalent read boundary.</li>
 *   <li><strong>PR-25</strong> (single monolith): the report is produced inside the one Spring
 *       Boot context and written to the local filesystem — no external reporting service.</li>
 *   <li><strong>PR-28</strong> (Jakarta / Spring 6 baseline): only Spring Framework 6.1 / Spring
 *       Batch 5.1 APIs and JDK types are used; no {@code javax.*} types.</li>
 *   <li><strong>PR-29</strong> (constructor injection only): the three collaborators are
 *       {@code final} and injected through the Lombok {@code @RequiredArgsConstructor}-generated
 *       constructor — no field injection, no {@code @Autowired}.</li>
 * </ul>
 *
 * <p><strong>Why {@code @EnableBatchProcessing} is intentionally absent:</strong> under Spring
 * Boot 3.x the {@code spring-boot-starter-batch} auto-configuration supplies the
 * {@link JobRepository}, {@code JobLauncher}, {@code JobRegistry} and {@code JobExplorer}; adding
 * {@code @EnableBatchProcessing} would switch off that auto-configuration. Both the
 * {@code @Bean Job} and {@code @Bean Step} declared here are auto-registered with the
 * {@code JobRegistry}, so {@code BatchAdminController}
 * ({@code POST /api/admin/jobs/{jobName}/launch}) can launch this job by its {@link #JOB_NAME}.</p>
 *
 * @see com.carddemo.entity.Transaction
 * @see com.carddemo.repository.TransactionRepository
 * @see TransactionReadJobConfig
 */
@Configuration
@RequiredArgsConstructor
@Slf4j
public class TransactionReportJobConfig {

    /**
     * Logical name of the {@link Job} bean. Matches the {@code @Bean} method name so the bean is
     * registered under this name with the Spring Batch {@code JobRegistry} and is launchable via
     * {@code BatchAdminController} ({@code POST /api/admin/jobs/{jobName}/launch}).
     */
    public static final String JOB_NAME = "transactionReportJob";

    /** Logical name of the single report {@link Step}. */
    private static final String STEP_NAME = "transactionReportStep";

    /**
     * Default output sub-directory used when the {@code outputDir} job parameter is absent.
     * This is a <b>relative</b> sub-path resolved beneath the approved batch output base
     * directory by {@link BatchOutputPathResolver}; it is never passed to {@code Paths.get}
     * directly (CWE-22 confinement).
     */
    private static final String DEFAULT_OUTPUT_DIR = "reports";

    /** Stem of the generated report file name; a timestamp and {@code .txt} suffix are appended. */
    private static final String REPORT_FILE_PREFIX = "transaction-report-";

    /** Suffix of the generated report file name. */
    private static final String REPORT_FILE_SUFFIX = ".txt";

    /**
     * Formatter for the report file-name timestamp (e.g. {@code transaction-report-20220718-231208.txt}).
     * Uses a filesystem-safe pattern with no characters that are illegal in file names.
     */
    private static final DateTimeFormatter FILE_TIMESTAMP =
            DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");

    /**
     * DB2 external timestamp format ({@code yyyy-MM-dd-HH.mm.ss.SS'0000'}, exactly 26 characters)
     * used to render {@link Transaction#getOrigTimestamp()} and the report date-range bounds back
     * to their original textual form per PR-11. This is the codebase-canonical DB2 pattern (the
     * same one used by {@code DateConversionUtil}, the {@link Transaction} entity Javadoc and the
     * transaction DTOs).
     */
    private static final DateTimeFormatter DB2_TIMESTAMP =
            DateTimeFormatter.ofPattern("yyyy-MM-dd-HH.mm.ss.SS'0000'");

    /**
     * Default lower bound of the report window, used when the {@code startDate} job parameter is
     * absent. Chosen far enough in the past to include every demonstration-grade transaction.
     */
    private static final LocalDateTime DEFAULT_START = LocalDateTime.of(2000, 1, 1, 0, 0, 0);

    /**
     * Default upper bound of the report window, used when the {@code endDate} job parameter is
     * absent. Chosen far enough in the future to include every demonstration-grade transaction.
     */
    private static final LocalDateTime DEFAULT_END = LocalDateTime.of(2099, 12, 31, 23, 59, 59);

    /** Money scale (number of decimal places) for all amount arithmetic — PR-16. */
    private static final int MONEY_SCALE = 2;

    /** Rounding mode for all amount arithmetic, matching the COBOL {@code ROUNDED} clause — PR-16. */
    private static final RoundingMode MONEY_ROUNDING = RoundingMode.HALF_UP;

    /** Width of the rule/separator lines in the fixed-width report. */
    private static final String RULE =
            "================================================================";

    /** Thin rule used to separate the column headings from the detail lines. */
    private static final String THIN_RULE =
            "----------------------------------------------------------------";

    /** Spring Batch metadata repository (auto-configured by Spring Boot 3.x). */
    private final JobRepository jobRepository;

    /**
     * Transaction manager bracketing the report step's unit of work (PR-24). The step is read-only
     * (it issues a single {@code SELECT} and writes only to the filesystem), but Spring Batch
     * requires a transaction manager to delimit step processing.
     */
    private final PlatformTransactionManager transactionManager;

    /**
     * Spring Data JPA repository over the {@code transactions} table. The tasklet calls
     * {@link TransactionRepository#findByOrigTimestampBetween(LocalDateTime, LocalDateTime)} to
     * fetch the in-range transactions (replacing the VSAM {@code TRANSACT.AIX} range scan and the
     * {@code SORT INCLUDE} of {@code TRANREPT.jcl}).
     */
    private final TransactionRepository transactionRepository;

    /**
     * Confines the caller-supplied {@code outputDir} job parameter beneath the approved
     * {@code carddemo.batch.output.base-dir} subtree. Because {@code BatchAdminController}
     * lets an ADMIN caller supply arbitrary job parameters, the raw value must never reach
     * {@code Paths.get(...)} directly; {@link BatchOutputPathResolver#resolve(String)} rejects
     * absolute paths and {@code ..} traversal and normalizes the result inside the base
     * directory (CWE-22 path-traversal mitigation). Injected via the Lombok
     * {@code @RequiredArgsConstructor}-generated constructor (PR-29).
     */
    private final BatchOutputPathResolver pathResolver;

    /**
     * The report-producing {@link Tasklet}.
     *
     * <p>Reads the optional {@code startDate} / {@code endDate} (DB2-normalized
     * {@link LocalDateTime} bounds) and {@code outputDir} job parameters, queries the in-range
     * transactions, sorts them by {@code cardNum} then {@code tranId}, and writes a fixed-width
     * report with per-card subtotals and a grand total. All monetary accumulation uses
     * {@link BigDecimal} at {@link #MONEY_SCALE} with {@link #MONEY_ROUNDING} (PR-16). The number
     * of reported transactions is recorded as the step's write count.</p>
     *
     * @return a {@link Tasklet} that generates the transaction detail report and returns
     *         {@link RepeatStatus#FINISHED}
     */
    @Bean
    public Tasklet transactionReportTasklet() {
        return (StepContribution contribution, ChunkContext chunkContext) -> {
            log.info("TransactionReportJob ({}) starting", JOB_NAME);

            JobParameters params =
                    chunkContext.getStepContext().getStepExecution().getJobParameters();
            LocalDateTime startTs = params.getLocalDateTime("startDate", DEFAULT_START);
            LocalDateTime endTs = params.getLocalDateTime("endDate", DEFAULT_END);
            String outputDir = params.getString("outputDir", DEFAULT_OUTPUT_DIR);

            // CWE-22 confinement: resolve the caller-supplied outputDir strictly beneath the
            // approved carddemo.batch.output.base-dir subtree (rejecting absolute paths and
            // ".." traversal) instead of trusting it directly via Paths.get(...).
            Path outputPath = pathResolver.resolve(outputDir);
            // Ensure the (confined) output directory exists (idempotent — no error if present).
            Files.createDirectories(outputPath);

            String fileStamp = LocalDateTime.now().format(FILE_TIMESTAMP);
            Path outputFile = outputPath.resolve(REPORT_FILE_PREFIX + fileStamp + REPORT_FILE_SUFFIX);

            // Date-range query — backed by idx_transaction_orig_ts (replaces TRANSACT.AIX).
            // Repository signature uses LocalDateTime bounds (entity-aligned), NOT String.
            List<Transaction> transactions =
                    transactionRepository.findByOrigTimestampBetween(startTs, endTs);

            log.info("Report covers {} transaction(s) in range [{} .. {}]",
                    transactions.size(), formatTimestamp(startTs), formatTimestamp(endTs));

            // Sort by card number, then by transaction id — reproduces SORT FIELDS=(TRAN-CARD-NUM,A).
            transactions.sort((a, b) -> {
                int cmp = nullSafe(a.getCardNum()).compareTo(nullSafe(b.getCardNum()));
                if (cmp != 0) {
                    return cmp;
                }
                return nullSafe(a.getTranId()).compareTo(nullSafe(b.getTranId()));
            });

            try (BufferedWriter writer = Files.newBufferedWriter(outputFile, StandardCharsets.UTF_8)) {
                writeReportHeader(writer, startTs, endTs);

                BigDecimal grandTotal = zeroMoney();
                int grandCount = 0;

                String currentCard = null;
                BigDecimal cardTotal = zeroMoney();
                int cardCount = 0;

                for (Transaction t : transactions) {
                    String cardNum = nullSafe(t.getCardNum());

                    // Card break — emit the subtotal for the card that just ended (1120-WRITE-ACCOUNT-TOTALS).
                    if (currentCard != null && !cardNum.equals(currentCard)) {
                        writeCardSubtotal(writer, currentCard, cardTotal, cardCount);
                        cardTotal = zeroMoney();
                        cardCount = 0;
                    }
                    currentCard = cardNum;

                    writeTransactionLine(writer, t);

                    BigDecimal amount = t.getAmount() == null ? BigDecimal.ZERO : t.getAmount();
                    cardTotal = cardTotal.add(amount).setScale(MONEY_SCALE, MONEY_ROUNDING);
                    cardCount++;
                    grandTotal = grandTotal.add(amount).setScale(MONEY_SCALE, MONEY_ROUNDING);
                    grandCount++;
                }

                // Final card subtotal for the last card in the stream.
                if (currentCard != null) {
                    writeCardSubtotal(writer, currentCard, cardTotal, cardCount);
                }

                // Grand total (1110-WRITE-GRAND-TOTALS).
                writeGrandTotal(writer, grandTotal, grandCount);
            }

            log.info("Transaction report written to {} ({} transaction(s))",
                    outputFile.toAbsolutePath(), transactions.size());

            contribution.incrementWriteCount(transactions.size());
            return RepeatStatus.FINISHED;
        };
    }

    /**
     * The single report {@link Step}, wiring {@link #transactionReportTasklet()} to the injected
     * {@link PlatformTransactionManager} (PR-24).
     *
     * @return the {@code transactionReportStep} bean
     */
    @Bean
    public Step transactionReportStep() {
        return new StepBuilder(STEP_NAME, jobRepository)
                .tasklet(transactionReportTasklet(), transactionManager)
                .build();
    }

    /**
     * The {@link Job} bean ({@link #JOB_NAME}) consisting of the single
     * {@link #transactionReportStep()}.
     *
     * <p>Auto-registered with the Spring Batch {@code JobRegistry} by Spring Boot
     * auto-configuration, so it is launchable by name through {@code BatchAdminController}. Running
     * the job with the same parameters returns the existing {@code JobExecution} (idempotent);
     * supplying a distinct parameter set (e.g. a different {@code startDate}) creates a new
     * execution.</p>
     *
     * @return the {@code transactionReportJob} bean
     */
    @Bean
    public Job transactionReportJob() {
        return new JobBuilder(JOB_NAME, jobRepository)
                .start(transactionReportStep())
                .build();
    }

    // -------------------------- report-writing helpers --------------------------

    /**
     * Writes the report header: a titled banner, the (DB2-formatted) date-range bounds, the
     * generation timestamp and the column headings, terminated by a thin rule.
     *
     * @param w       the report writer
     * @param startTs inclusive lower bound of the report window
     * @param endTs   inclusive upper bound of the report window
     * @throws IOException if writing to the report file fails
     */
    private void writeReportHeader(BufferedWriter w, LocalDateTime startTs, LocalDateTime endTs)
            throws IOException {
        writeLine(w, RULE);
        writeLine(w, "              CARDDEMO DAILY TRANSACTION DETAIL REPORT");
        writeLine(w, RULE);
        writeLine(w, String.format("Date Range : %s  to  %s",
                formatTimestamp(startTs), formatTimestamp(endTs)));
        writeLine(w, String.format("Generated  : %s", formatTimestamp(LocalDateTime.now())));
        writeLine(w, RULE);
        writeLine(w, String.format("%-16s %-16s %-26s %14s",
                "TRAN-ID", "CARD-NUM", "ORIG-TS", "AMOUNT"));
        writeLine(w, THIN_RULE);
    }

    /**
     * Writes a single transaction detail line: transaction id, card number, the DB2-formatted
     * original timestamp and the scale-2 amount (PR-11, PR-16).
     *
     * @param w the report writer
     * @param t the transaction to render
     * @throws IOException if writing to the report file fails
     */
    private void writeTransactionLine(BufferedWriter w, Transaction t) throws IOException {
        String amount = (t.getAmount() == null ? BigDecimal.ZERO : t.getAmount())
                .setScale(MONEY_SCALE, MONEY_ROUNDING)
                .toPlainString();
        writeLine(w, String.format("%-16s %-16s %-26s %14s",
                nullSafe(t.getTranId()),
                nullSafe(t.getCardNum()),
                formatTimestamp(t.getOrigTimestamp()),
                amount));
    }

    /**
     * Writes a per-card subtotal block (the COBOL {@code 1120-WRITE-ACCOUNT-TOTALS} equivalent),
     * bracketed by blank lines, showing the card number, the transaction count and the scale-2
     * total (PR-16).
     *
     * @param w       the report writer
     * @param cardNum the card number whose run just ended
     * @param total   the accumulated amount for the card (scale 2)
     * @param count   the number of transactions for the card
     * @throws IOException if writing to the report file fails
     */
    private void writeCardSubtotal(BufferedWriter w, String cardNum, BigDecimal total, int count)
            throws IOException {
        writeLine(w, "");
        writeLine(w, String.format("  Subtotal for CARD %-16s  count=%4d  total=%14s",
                nullSafe(cardNum), count, total.toPlainString()));
        writeLine(w, "");
    }

    /**
     * Writes the closing grand total (the COBOL {@code 1110-WRITE-GRAND-TOTALS} equivalent),
     * bracketed by rules, showing the overall transaction count and the scale-2 grand total
     * (PR-16).
     *
     * @param w     the report writer
     * @param total the grand total amount (scale 2)
     * @param count the total number of reported transactions
     * @throws IOException if writing to the report file fails
     */
    private void writeGrandTotal(BufferedWriter w, BigDecimal total, int count) throws IOException {
        writeLine(w, RULE);
        writeLine(w, String.format("GRAND TOTAL%34scount=%4d  total=%14s",
                "", count, total.toPlainString()));
        writeLine(w, RULE);
    }

    /**
     * Writes one line of text followed by a deterministic line-feed ({@code '\n'}), guaranteeing
     * platform-independent line endings in the generated report file.
     *
     * @param w the report writer
     * @param s the line content (without a trailing newline)
     * @throws IOException if writing to the report file fails
     */
    private void writeLine(BufferedWriter w, String s) throws IOException {
        w.write(s);
        w.write('\n');
    }

    /**
     * Renders a {@link LocalDateTime} as the 26-character DB2 external timestamp
     * ({@code yyyy-MM-dd-HH.mm.ss.SS'0000'}) per PR-11, returning the empty string for a
     * {@code null} input.
     *
     * @param ts the timestamp to format (may be {@code null})
     * @return the DB2-formatted timestamp, or {@code ""} if {@code ts} is {@code null}
     */
    private String formatTimestamp(LocalDateTime ts) {
        return ts == null ? "" : ts.format(DB2_TIMESTAMP);
    }

    /**
     * Returns the given string, or the empty string when the argument is {@code null}. Used to keep
     * the card-grouping comparator and the report columns null-safe.
     *
     * @param s the value to guard (may be {@code null})
     * @return {@code s}, or {@code ""} when {@code s} is {@code null}
     */
    private String nullSafe(String s) {
        return s == null ? "" : s;
    }

    /**
     * Returns a fresh {@link BigDecimal} zero fixed at {@link #MONEY_SCALE} with
     * {@link #MONEY_ROUNDING}, used to seed the per-card and grand-total accumulators (PR-16).
     *
     * @return {@code 0.00} as a scale-2 {@link BigDecimal}
     */
    private BigDecimal zeroMoney() {
        return BigDecimal.ZERO.setScale(MONEY_SCALE, MONEY_ROUNDING);
    }
}
