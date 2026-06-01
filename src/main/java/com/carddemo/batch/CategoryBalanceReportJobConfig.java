package com.carddemo.batch;

import com.carddemo.entity.TransactionCategoryBalance;
import com.carddemo.entity.TransactionCategoryBalanceId;
import com.carddemo.repository.TransactionCategoryBalanceRepository;
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
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Comparator;
import java.util.List;

/**
 * Spring Batch configuration for the <strong>{@code categoryBalanceReportJob}</strong> — the
 * Java/PostgreSQL replacement for the legacy mainframe transaction-category-balance report
 * driven by {@code app/jcl/PRTCATBL.jcl}.
 *
 * <h2>Legacy mainframe behavior (PRTCATBL.jcl)</h2>
 * The original job operated on the {@code TCATBALF} VSAM KSDS (the
 * {@code TRAN-CAT-BAL-RECORD} layout from {@code app/cpy/CVTRA01Y.cpy}) in three logical steps:
 * <ol>
 *   <li><strong>DELDEF</strong> — {@code PGM=IEFBR14} pre-deletes the prior report dataset
 *       ({@code AWS.M2.CARDDEMO.TCATBALF.REPT}).</li>
 *   <li><strong>STEP05R (REPROC)</strong> — REPRO the {@code TCATBALF} KSDS to a sequential
 *       backup ({@code TCATBALF.BKUP}).</li>
 *   <li><strong>STEP10R (SORT)</strong> — {@code EXEC PGM=SORT} with
 *       {@code SORT FIELDS=(TRANCAT-ACCT-ID,A,TRANCAT-TYPE-CD,A,TRANCAT-CD,A)} sorted the
 *       balances ascending by the three composite-key components, emitting an
 *       {@code OUTREC} that printed the account id, type code, category code and the
 *       {@code TRAN-CAT-BAL} edited as {@code TTTTTTTTT.TT} (a decimal money mask).</li>
 * </ol>
 *
 * <h2>Modernized Spring Batch behavior</h2>
 * Per AAP &sect;0.4.1.2 / &sect;0.4.1.3 the entire job collapses into a single read-only
 * {@link Step} backed by a {@link Tasklet}. The tasklet:
 * <ol>
 *   <li>reads the optional {@code outputDir} {@link JobParameters} (default
 *       {@link #DEFAULT_OUTPUT_DIR});</li>
 *   <li>loads every {@link TransactionCategoryBalance} row through
 *       {@link TransactionCategoryBalanceRepository#findAll()} (replacing the VSAM
 *       {@code TCATBALF} sequential read / REPRO);</li>
 *   <li>sorts the records in memory by the composite key
 *       {@code (accountId, typeCd, categoryCd)} — reproducing the JCL
 *       {@code SORT FIELDS=(TRANCAT-ACCT-ID,A,TRANCAT-TYPE-CD,A,TRANCAT-CD,A)} order
 *       (PR-15); and</li>
 *   <li>writes a fixed-width text report with a header, one detail line per balance, a
 *       per-account subtotal at every account break (the COBOL CONTROL-BREAK pattern) and a
 *       closing grand total.</li>
 * </ol>
 *
 * <p>The original {@code DELDEF}/{@code REPROC} dataset-management steps have no Java
 * counterpart: the modern report simply writes a fresh, timestamped file into the configured
 * output directory on every run, so there is nothing to pre-delete or unload. The
 * intermediate {@code TCATBALF.BKUP} backup is likewise obsolete — the relational
 * {@code tran_cat_balances} table is read directly.</p>
 *
 * <h2>Refactoring rules enforced</h2>
 * <ul>
 *   <li><strong>PR-15</strong> (composite-key fidelity): the in-memory sort orders by
 *       {@code accountId} then {@code typeCd} then {@code categoryCd}, exactly matching the
 *       COBOL {@code TRAN-CAT-KEY} concatenation and the JCL {@code SORT FIELDS} order.</li>
 *   <li><strong>PR-16</strong> (exact money arithmetic): every per-account subtotal and the
 *       grand total are {@link BigDecimal} held at {@link #MONEY_SCALE scale 2} with
 *       {@link RoundingMode#HALF_UP}; {@code float}/{@code double} are never used for any
 *       monetary value.</li>
 *   <li><strong>PR-24</strong> (unit-of-work boundary): the step runs inside the injected
 *       {@link PlatformTransactionManager}; the report is read-only and performs no writes,
 *       mirroring the {@code SYNCPOINT}-equivalent read boundary.</li>
 *   <li><strong>PR-25</strong> (single monolith): the report is produced inside the one Spring
 *       Boot context and written to the local filesystem — no external reporting service.</li>
 *   <li><strong>PR-28</strong> (Jakarta / Spring 6 baseline): only Spring Framework 6.1 /
 *       Spring Batch 5.1 APIs and JDK types are used; no {@code javax.*} types.</li>
 *   <li><strong>PR-29</strong> (constructor injection only): the three collaborators are
 *       {@code final} and injected through the Lombok {@code @RequiredArgsConstructor}-generated
 *       constructor — no field injection, no {@code @Autowired}.</li>
 * </ul>
 *
 * <p><strong>Why {@code @EnableBatchProcessing} is intentionally absent:</strong> under Spring
 * Boot 3.x the {@code spring-boot-starter-batch} auto-configuration supplies the
 * {@link JobRepository}, {@code JobLauncher}, {@code JobRegistry} and {@code JobExplorer};
 * adding {@code @EnableBatchProcessing} would switch off that auto-configuration. Both the
 * {@code @Bean Job} and {@code @Bean Step} declared here are auto-registered with the
 * {@code JobRegistry}, so {@code BatchAdminController}
 * ({@code POST /api/admin/jobs/{jobName}/launch}) can launch this job by its
 * {@link #JOB_NAME}.</p>
 *
 * @see com.carddemo.entity.TransactionCategoryBalance
 * @see com.carddemo.entity.TransactionCategoryBalanceId
 * @see com.carddemo.repository.TransactionCategoryBalanceRepository
 * @see TransactionReportJobConfig
 */
@Configuration
@RequiredArgsConstructor
@Slf4j
public class CategoryBalanceReportJobConfig {

    /**
     * Logical name of the {@link Job} bean. Matches the {@code @Bean} method name so the bean is
     * registered under this name with the Spring Batch {@code JobRegistry} and is launchable via
     * {@code BatchAdminController} ({@code POST /api/admin/jobs/{jobName}/launch}).
     */
    public static final String JOB_NAME = "categoryBalanceReportJob";

    /** Logical name of the single report {@link Step}. */
    private static final String STEP_NAME = "categoryBalanceReportStep";

    /** Default output directory used when the {@code outputDir} job parameter is absent. */
    private static final String DEFAULT_OUTPUT_DIR = "./reports";

    /** Stem of the generated report file name; a timestamp and {@code .txt} suffix are appended. */
    private static final String REPORT_FILE_PREFIX = "category-balance-report-";

    /** Suffix of the generated report file name. */
    private static final String REPORT_FILE_SUFFIX = ".txt";

    /**
     * Formatter for the report file-name timestamp (e.g.
     * {@code category-balance-report-20220718-231208.txt}). Uses a filesystem-safe pattern with no
     * characters that are illegal in file names.
     */
    private static final DateTimeFormatter FILE_TIMESTAMP =
            DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");

    /** Human-readable timestamp rendered in the report header (e.g. {@code 2022-07-18 23:12:08}). */
    private static final DateTimeFormatter GENERATED_TIMESTAMP =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    /** Money scale (number of decimal places) for all balance arithmetic — PR-16. */
    private static final int MONEY_SCALE = 2;

    /** Rounding mode for all balance arithmetic, matching the COBOL {@code ROUNDED} clause — PR-16. */
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
     * (it issues a single {@code SELECT} via {@code findAll()} and writes only to the filesystem),
     * but Spring Batch requires a transaction manager to delimit step processing.
     */
    private final PlatformTransactionManager transactionManager;

    /**
     * Spring Data JPA repository over the {@code tran_cat_balances} table. The tasklet calls
     * {@link TransactionCategoryBalanceRepository#findAll()} to load every category-balance row
     * (replacing the VSAM {@code TCATBALF} sequential read of {@code PRTCATBL.jcl}).
     */
    private final TransactionCategoryBalanceRepository tcatbalRepository;

    /**
     * The report-producing {@link Tasklet}.
     *
     * <p>Reads the optional {@code outputDir} job parameter, loads every
     * {@link TransactionCategoryBalance} record, sorts it by the composite key
     * {@code (accountId, typeCd, categoryCd)} (PR-15), and writes a fixed-width report with a
     * per-account subtotal at every account break and a closing grand total. All monetary
     * accumulation uses {@link BigDecimal} at {@link #MONEY_SCALE} with {@link #MONEY_ROUNDING}
     * (PR-16). The number of reported records is recorded as the step's write count.</p>
     *
     * @return a {@link Tasklet} that generates the category-balance detail report and returns
     *         {@link RepeatStatus#FINISHED}
     */
    @Bean
    public Tasklet categoryBalanceReportTasklet() {
        return (StepContribution contribution, ChunkContext chunkContext) -> {
            log.info("CategoryBalanceReportJob ({}) starting", JOB_NAME);

            JobParameters params =
                    chunkContext.getStepContext().getStepExecution().getJobParameters();
            String outputDir = params.getString("outputDir", DEFAULT_OUTPUT_DIR);

            // Ensure the output directory exists (idempotent — no error if already present).
            Path outputPath = Paths.get(outputDir);
            Files.createDirectories(outputPath);

            String fileStamp = LocalDateTime.now().format(FILE_TIMESTAMP);
            Path outputFile = outputPath.resolve(REPORT_FILE_PREFIX + fileStamp + REPORT_FILE_SUFFIX);

            // Load every TCATBAL record (replaces the VSAM TCATBALF sequential read / REPRO).
            List<TransactionCategoryBalance> records = tcatbalRepository.findAll();
            log.info("Report covers {} TCATBAL record(s)", records.size());

            // Sort by composite key: accountId, then typeCd, then categoryCd — reproduces
            // SORT FIELDS=(TRANCAT-ACCT-ID,A,TRANCAT-TYPE-CD,A,TRANCAT-CD,A) (PR-15). The three
            // key components are NOT-NULL primary-key columns, so natural ordering is safe.
            records.sort(Comparator
                    .<TransactionCategoryBalance, Long>comparing(r -> r.getId().getAccountId())
                    .thenComparing(r -> r.getId().getTypeCd())
                    .thenComparing(r -> r.getId().getCategoryCd()));

            try (BufferedWriter writer = Files.newBufferedWriter(outputFile, StandardCharsets.UTF_8)) {
                writeReportHeader(writer);

                Long currentAcct = null;
                BigDecimal acctTotal = zeroMoney();
                int acctCount = 0;

                BigDecimal grandTotal = zeroMoney();
                int grandCount = 0;

                for (TransactionCategoryBalance tcb : records) {
                    Long acctId = tcb.getId().getAccountId();

                    // Account break — emit the subtotal for the account that just ended
                    // (the COBOL CONTROL-BREAK pattern) before resetting the accumulators.
                    if (currentAcct != null && !acctId.equals(currentAcct)) {
                        writeAccountSubtotal(writer, currentAcct, acctTotal, acctCount);
                        acctTotal = zeroMoney();
                        acctCount = 0;
                    }
                    currentAcct = acctId;

                    writeDetailLine(writer, tcb);

                    BigDecimal bal = tcb.getTranCatBal() == null ? BigDecimal.ZERO : tcb.getTranCatBal();
                    acctTotal = acctTotal.add(bal).setScale(MONEY_SCALE, MONEY_ROUNDING);
                    acctCount++;
                    grandTotal = grandTotal.add(bal).setScale(MONEY_SCALE, MONEY_ROUNDING);
                    grandCount++;
                }

                // Final account subtotal for the last account in the stream.
                if (currentAcct != null) {
                    writeAccountSubtotal(writer, currentAcct, acctTotal, acctCount);
                }

                // Grand total across all accounts.
                writeGrandTotal(writer, grandTotal, grandCount);
            }

            log.info("Category balance report written to {} ({} record(s))",
                    outputFile.toAbsolutePath(), records.size());

            contribution.incrementWriteCount(records.size());
            return RepeatStatus.FINISHED;
        };
    }

    /**
     * The single report {@link Step}, wiring {@link #categoryBalanceReportTasklet()} to the
     * injected {@link PlatformTransactionManager} (PR-24).
     *
     * @return the {@code categoryBalanceReportStep} bean
     */
    @Bean
    public Step categoryBalanceReportStep() {
        return new StepBuilder(STEP_NAME, jobRepository)
                .tasklet(categoryBalanceReportTasklet(), transactionManager)
                .build();
    }

    /**
     * The {@link Job} bean ({@link #JOB_NAME}) consisting of the single
     * {@link #categoryBalanceReportStep()}.
     *
     * <p>Auto-registered with the Spring Batch {@code JobRegistry} by Spring Boot
     * auto-configuration, so it is launchable by name through {@code BatchAdminController}.
     * Running the job with the same parameters returns the existing {@code JobExecution}
     * (idempotent); supplying a distinct parameter set (e.g. a different {@code outputDir})
     * creates a new execution.</p>
     *
     * @return the {@code categoryBalanceReportJob} bean
     */
    @Bean
    public Job categoryBalanceReportJob() {
        return new JobBuilder(JOB_NAME, jobRepository)
                .start(categoryBalanceReportStep())
                .build();
    }

    // -------------------------- report-writing helpers --------------------------

    /**
     * Writes the report header: a titled banner, the generation timestamp and the column
     * headings, terminated by a thin rule.
     *
     * @param w the report writer
     * @throws IOException if writing to the report file fails
     */
    private void writeReportHeader(BufferedWriter w) throws IOException {
        writeLine(w, RULE);
        writeLine(w, "           CARDDEMO CATEGORY BALANCE DETAIL REPORT");
        writeLine(w, RULE);
        writeLine(w, String.format("Generated  : %s", LocalDateTime.now().format(GENERATED_TIMESTAMP)));
        writeLine(w, RULE);
        writeLine(w, String.format("%-11s %-3s %-5s %15s", "ACCT-ID", "TYP", "CAT", "BALANCE"));
        writeLine(w, THIN_RULE);
    }

    /**
     * Writes a single category-balance detail line: account id, transaction type code,
     * transaction category code and the scale-2 balance (PR-16).
     *
     * <p>The category code is rendered with the {@code %-5s} (string) conversion because the
     * {@link TransactionCategoryBalanceId#getCategoryCd()} key component is a fixed-width
     * {@code CHAR(4)} {@link String} (preserving any leading zeros, e.g. {@code "0001"}); the
     * account id is rendered with {@code %-11d} because
     * {@link TransactionCategoryBalanceId#getAccountId()} is a {@link Long}.</p>
     *
     * @param w   the report writer
     * @param tcb the category balance to render
     * @throws IOException if writing to the report file fails
     */
    private void writeDetailLine(BufferedWriter w, TransactionCategoryBalance tcb) throws IOException {
        TransactionCategoryBalanceId id = tcb.getId();
        String balStr = (tcb.getTranCatBal() == null ? BigDecimal.ZERO : tcb.getTranCatBal())
                .setScale(MONEY_SCALE, MONEY_ROUNDING)
                .toPlainString();
        writeLine(w, String.format("%-11d %-3s %-5s %15s",
                id.getAccountId(),
                nullSafe(id.getTypeCd()),
                nullSafe(id.getCategoryCd()),
                balStr));
    }

    /**
     * Writes a per-account subtotal block (the COBOL CONTROL-BREAK subtotal equivalent),
     * followed by a blank line, showing the account id, the record count and the scale-2
     * subtotal (PR-16).
     *
     * @param w      the report writer
     * @param acctId the account id whose run just ended
     * @param total  the accumulated balance for the account (scale 2)
     * @param count  the number of category-balance records for the account
     * @throws IOException if writing to the report file fails
     */
    private void writeAccountSubtotal(BufferedWriter w, Long acctId, BigDecimal total, int count)
            throws IOException {
        writeLine(w, String.format("  Subtotal for ACCT %-11d  count=%4d  total=%15s",
                acctId, count, total.toPlainString()));
        writeLine(w, "");
    }

    /**
     * Writes the closing grand total, bracketed by rules, showing the overall record count and
     * the scale-2 grand total (PR-16).
     *
     * @param w     the report writer
     * @param total the grand total balance (scale 2)
     * @param count the total number of reported category-balance records
     * @throws IOException if writing to the report file fails
     */
    private void writeGrandTotal(BufferedWriter w, BigDecimal total, int count) throws IOException {
        writeLine(w, RULE);
        writeLine(w, String.format("GRAND TOTAL%26scount=%4d  total=%15s",
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
     * Returns the given string, or the empty string when the argument is {@code null}. Used to
     * keep the report columns null-safe even though the rendered key components are NOT-NULL
     * primary-key fields.
     *
     * @param s the value to guard (may be {@code null})
     * @return {@code s}, or {@code ""} when {@code s} is {@code null}
     */
    private String nullSafe(String s) {
        return s == null ? "" : s;
    }

    /**
     * Returns a fresh {@link BigDecimal} zero fixed at {@link #MONEY_SCALE} with
     * {@link #MONEY_ROUNDING}, used to seed the per-account and grand-total accumulators (PR-16).
     *
     * @return {@code 0.00} as a scale-2 {@link BigDecimal}
     */
    private BigDecimal zeroMoney() {
        return BigDecimal.ZERO.setScale(MONEY_SCALE, MONEY_ROUNDING);
    }
}
