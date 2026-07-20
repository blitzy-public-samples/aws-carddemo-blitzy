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
package com.aws.carddemo.batch.writer;

import java.io.BufferedWriter;
import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.EnumSet;

import com.aws.carddemo.batch.processor.TransactionReportProcessor;
import com.aws.carddemo.common.util.FixedWidthCodec;
import com.aws.carddemo.exception.FileStatusException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.ExitStatus;
import org.springframework.batch.core.JobParameters;
import org.springframework.batch.core.StepExecution;
import org.springframework.batch.core.StepExecutionListener;
import org.springframework.batch.item.Chunk;
import org.springframework.batch.item.ExecutionContext;
import org.springframework.batch.item.ItemWriter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Spring Batch {@link ItemWriter} that reproduces the <em>report-generation output</em> of the
 * legacy COBOL batch program {@code CBTRN03C} (job {@code TransactionReportJob}, PROC
 * {@code legacy/proc/TRANREPT.prc}, online trigger {@code CR00} via {@code service/ReportService}).
 *
 * <h2>COBOL lineage</h2>
 * <p>{@code CBTRN03C} reads date-filtered transactions and produces a single sequential
 * {@code REPORT-FILE} whose record is {@code FD-REPTFILE-REC PIC X(133)}
 * ({@code legacy/cbl/CBTRN03C.cbl} L84-L85). The {@code TRANREPT} DD is
 * {@code DCB=(LRECL=133,RECFM=FB)} and a fresh generation is catalogued per run
 * ({@code DISP=(NEW,CATLG,DELETE)}, GDG {@code (+1)}). The report is a paginated "Daily Transaction
 * Report" ({@code DALYREPT}) composed of a report name header, column headers, per-transaction
 * detail lines, per-page subtotals, per-account subtotals (a control break on card number), and a
 * grand total. The exact 133-byte layouts are defined in copybook {@code legacy/cpy/CVTRA07Y.cpy}.</p>
 *
 * <h2>Division of responsibility</h2>
 * <p>The upstream {@code TransactionReportProcessor} has already read each transaction, applied the
 * date-range filter, resolved the cross-reference account and the transaction type/category
 * descriptions, and emitted one fully-resolved {@link TransactionReportProcessor.ReportLine} per
 * in-range transaction, ordered by {@code (cardNum, tranId)}. This writer performs <strong>no data
 * access, no date filtering, and no sorting</strong>; its sole responsibility is the report
 * <strong>formatting, pagination, control-break, and totals state machine</strong>, reproducing
 * {@code CBTRN03C}'s {@code WRITE FD-REPTFILE-REC} behaviour byte-for-byte.</p>
 *
 * <h2>Wiring &amp; lifecycle</h2>
 * <p>The bean name is {@code transactionReportWriter} (the decapitalized class name), which is the
 * wiring contract with the {@code transactionReportStep} bean in the parent {@code batch/} package.
 * It is a singleton (deliberately not {@code @StepScope}); all per-run state is reset in
 * {@link #beforeStep(StepExecution)}, where the report date range is read from the step's job
 * parameters ({@code startDate} / {@code endDate}, format {@code YYYY-MM-DD}) and the output file is
 * opened. {@link #afterStep(StepExecution)} performs the end-of-file finalization and closes the
 * file, mapping the outcome to a batch return code via the returned {@link ExitStatus}.</p>
 *
 * <p>Because that per-run state (line/record counters, running totals, and the open output stream) is
 * held on the singleton bean, running two {@code TransactionReportJob} executions concurrently in the
 * same JVM is <strong>not</strong> supported. This matches the operating model in which each batch job
 * is launched in its own process by the CI/CD scheduler (AAP &sect;0.4.4), so the single-launch-per-JVM
 * constraint is faithful operational parity rather than a defect. The rationale and the
 * {@code @StepScope} alternative are recorded in decision log D37.</p>
 *
 * <h2>Parity notes</h2>
 * <ul>
 *   <li>All monetary running totals are {@link BigDecimal} at scale 2 with
 *       {@link RoundingMode#HALF_UP}; {@code double}/{@code float} are never used.</li>
 *   <li>Every emitted line is exactly {@value #RECORD_LENGTH} bytes, encoded as ISO-8859-1 so that
 *       one character maps to one byte, matching the fixed-width mainframe record contract.</li>
 *   <li>The end-of-file finalization intentionally reproduces a legacy quirk: the last
 *       transaction's amount is added to the page and grand totals a <em>second</em> time (the
 *       COBOL record area still holds the final {@code TRAN-AMT} when the read hits end-of-file).
 *       This is a faithful behavioural-parity reproduction, not a bug to fix; the rationale is
 *       recorded in {@code docs/decision-log.md}. See {@link #afterStep(StepExecution)}.</li>
 *   <li>No card number, account id, or transaction detail is ever logged; only line/record counts
 *       and the output path appear in log messages.</li>
 * </ul>
 *
 * @see TransactionReportProcessor.ReportLine
 * @see FixedWidthCodec
 * @see ItemWriter
 * @see StepExecutionListener
 */
@Component
public class TransactionReportWriter
        implements ItemWriter<TransactionReportProcessor.ReportLine>, StepExecutionListener {

    private static final Logger LOGGER = LoggerFactory.getLogger(TransactionReportWriter.class);

    // ------------------------------------------------------------------------
    // Parity-critical constants (CBTRN03C working storage + CVTRA07Y layouts)
    // ------------------------------------------------------------------------

    /** Fixed record width of {@code FD-REPTFILE-REC PIC X(133)} (CBTRN03C L85). */
    private static final int RECORD_LENGTH = 133;

    /** {@code WS-PAGE-SIZE PIC 9(03) COMP-3 VALUE 20} (CBTRN03C L133-134): lines per page. */
    private static final int PAGE_SIZE = 20;

    /** Width of the edited amount field ({@code -ZZZ,ZZZ,ZZZ.ZZ} / {@code +ZZZ,ZZZ,ZZZ.ZZ}). */
    private static final int AMOUNT_WIDTH = 15;

    /** Zero-based offset of the amount field in the detail and totals lines (CVTRA07Y). */
    private static final int AMOUNT_OFFSET = 97;

    /** Line separator appended after every 133-byte record. */
    private static final String RECORD_DELIMITER = "\n";

    /**
     * Output encoding. ISO-8859-1 (Latin-1) is a single-byte charset, so each 133-character record
     * serializes to exactly 133 bytes, preserving the {@code RECFM=FB LRECL=133} contract.
     */
    private static final Charset OUTPUT_CHARSET = StandardCharsets.ISO_8859_1;

    /**
     * Suffix of the owner-only staging file that the step writes to before the completed report is
     * atomically published to its final name. Writing to a distinct hidden staging file (rather than
     * the final path) means a restart or failure can never destroy a previously-published report and
     * a consumer never observes a partial final-named file (QA finding F-P5-C).
     */
    private static final String PART_SUFFIX = ".part";

    /** Owner read/write only ({@code 0600}) staging-file permissions on POSIX filesystems. */
    private static final EnumSet<PosixFilePermission> OWNER_ONLY_PERMISSIONS =
            EnumSet.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE);

    /**
     * COBOL {@code FILE STATUS} used for a report-file I/O failure (open/write/close). '30' is the
     * QSAM "permanent error, no further information" code; the legacy program moves the real
     * {@code TRANREPT-STATUS} to {@code IO-STATUS} and abends via {@code 9999-ABEND-PROGRAM}
     * (CBTRN03C L354-357). Surfaced here as a {@link FileStatusException} so the step fails with the
     * I/O-error return code (batch RC8).
     */
    private static final String FILE_STATUS_PERMANENT_ERROR = "30";

    /**
     * {@code TRANSACTION-HEADER-1} (CVTRA07Y L33-46): the column-header line. Fully static, so it is
     * built once. Content is 114 bytes right-padded to {@value #RECORD_LENGTH}.
     */
    private static final String HEADER_1 = buildColumnHeader1();

    /** {@code TRANSACTION-HEADER-2 PIC X(133) VALUE ALL '-'} (CVTRA07Y L48): 133 dashes. */
    private static final String HEADER_2 = "-".repeat(RECORD_LENGTH);

    /** {@code WS-BLANK-LINE PIC X(133) VALUE SPACES} (CBTRN03C L135): 133 spaces. */
    private static final String BLANK_LINE = " ".repeat(RECORD_LENGTH);

    // ------------------------------------------------------------------------
    // Injected configuration (config-driven output path; never hardcoded)
    // ------------------------------------------------------------------------

    /** Directory into which the report file is written (key {@code carddemo.batch.report.output-directory}). */
    private final String outputDirectory;

    /** Report file name (key {@code carddemo.batch.report.output-file}). */
    private final String reportFileName;

    // ------------------------------------------------------------------------
    // Mutable per-run state (reset in beforeStep; single-threaded step execution)
    // ------------------------------------------------------------------------

    /** Open output stream for the report file; {@code null} until {@link #beforeStep(StepExecution)} runs. */
    private BufferedWriter reportWriter;

    /** Resolved final output path, retained for the (PII-free) completion log message and atomic publish. */
    private Path reportPath;

    /**
     * Owner-only staging file the step writes to during the run; atomically renamed to
     * {@link #reportPath} on success or deleted on failure. {@code null} outside an active step.
     */
    private Path reportTempPath;

    /** {@code WS-FIRST-TIME} ('Y' -&gt; {@code true}): guards header emission and the first control break. */
    private boolean firstTime;

    /** {@code WS-LINE-COUNTER}: counts every written line except the grand total; drives pagination. */
    private long lineCounter;

    /** {@code WS-PAGE-TOTAL} (scale 2): running per-page amount total. */
    private BigDecimal pageTotal;

    /** {@code WS-ACCOUNT-TOTAL} (scale 2): running per-account (per-card) amount total. */
    private BigDecimal accountTotal;

    /** {@code WS-GRAND-TOTAL} (scale 2): running report-wide amount total. */
    private BigDecimal grandTotal;

    /** {@code WS-CURR-CARD-NUM}: current control-break card number; {@code null} models the COBOL initial {@code SPACES}. */
    private String currentCardNum;

    /** The last transaction's amount (scale 2), reused at end-of-file to reproduce the stale-amount quirk. */
    private BigDecimal lastAmount;

    /** {@code REPT-START-DATE} (10 chars) taken from the {@code startDate} job parameter. */
    private String reportStartDate;

    /** {@code REPT-END-DATE} (10 chars) taken from the {@code endDate} job parameter. */
    private String reportEndDate;

    /** Observability: number of detail lines written during the step. */
    private long detailCount;

    /** Observability: total number of 133-byte records written during the step. */
    private long recordCount;

    /** Set {@code true} on any open/write/close I/O failure; drives the {@link ExitStatus} mapping. */
    private boolean ioError;

    /**
     * Creates the writer with a config-driven output location. Both keys carry a sensible default so
     * the bean is usable in tests and local runs without additional configuration; the production
     * values are authored under {@code carddemo.batch.report.*} in {@code application.yml} by the
     * configuration component (this writer only consumes them).
     *
     * @param outputDirectory the directory into which the report file is written; defaults to
     *                        {@code ./target/batch}
     * @param reportFileName  the report file name; defaults to {@code DALYREPT.txt}
     */
    public TransactionReportWriter(
            @Value("${carddemo.batch.report.output-directory:./target/batch}") String outputDirectory,
            @Value("${carddemo.batch.report.output-file:DALYREPT.txt}") String reportFileName) {
        this.outputDirectory = outputDirectory;
        this.reportFileName = reportFileName;
    }

    // ------------------------------------------------------------------------
    // Spring Batch lifecycle (StepExecutionListener + ItemWriter)
    // ------------------------------------------------------------------------

    /**
     * Resets all per-run state, captures the report date range from the step's job parameters, and
     * opens the report file (truncate/create). Reproduces {@code CBTRN03C}'s {@code OPEN OUTPUT
     * REPORT-FILE} (L394-396) plus the {@code DISP=(NEW,CATLG,DELETE)} fresh-file semantics.
     *
     * <p>The {@code startDate} / {@code endDate} job parameters (format {@code YYYY-MM-DD}, the
     * {@code DATEPARM} analog) are the same keys bound by the report reader; a missing parameter is
     * rendered as ten spaces, matching a blank {@code DATEPARM} field. An I/O failure sets the error
     * flag and is surfaced as an (unchecked) {@link FileStatusException} so the step fails fast with
     * the I/O-error return code (RC8).</p>
     *
     * @param stepExecution the step execution supplying the job parameters; never {@code null}
     */
    @Override
    public void beforeStep(StepExecution stepExecution) {
        this.firstTime = true;
        this.lineCounter = 0L;
        this.pageTotal = BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);
        this.accountTotal = BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);
        this.grandTotal = BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);
        this.currentCardNum = null;
        this.lastAmount = BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);
        this.detailCount = 0L;
        this.recordCount = 0L;
        this.ioError = false;
        this.reportWriter = null;

        final JobParameters jobParameters = stepExecution.getJobParameters();
        this.reportStartDate = normalizeDate(jobParameters.getString("startDate"));
        this.reportEndDate = normalizeDate(jobParameters.getString("endDate"));

        final Path directory = Path.of(outputDirectory);
        this.reportPath = directory.resolve(reportFileName);
        // Write to a hidden, owner-only staging file in the same directory; the completed report is
        // atomically published to reportPath in afterStep. Using a staging file rather than the final
        // path means a restart (which re-reads the full input, see TransactionReportItemReader) or a
        // failure can never truncate or destroy a previously-published report (F-P5-C).
        this.reportTempPath = directory.resolve("." + reportFileName + PART_SUFFIX);
        try {
            Files.createDirectories(directory);
            this.reportWriter = openOwnerOnlyStagingWriter(reportTempPath);
        } catch (IOException ex) {
            this.ioError = true;
            deleteQuietly(reportTempPath);
            this.reportTempPath = null;
            LOGGER.error("Unable to open transaction report staging file in directory '{}': {}",
                    outputDirectory, ex.getMessage());
            throw new FileStatusException(FILE_STATUS_PERMANENT_ERROR,
                    "Unable to open transaction report output file", ex);
        }
        LOGGER.info("Transaction report staging opened: {} (133-byte fixed-width records, ISO-8859-1); "
                + "will be atomically published to {} on success.", reportTempPath, reportPath);
    }

    /**
     * Creates the owner-only staging file and returns a buffered writer over it. On a POSIX
     * filesystem the file is created with {@code 0600} permissions; on a non-POSIX filesystem it is
     * created without them. Any pre-existing staging file (from an aborted prior attempt) is removed
     * first so the fresh run always starts from an empty file.
     *
     * @param temp the staging file path to create and open; never {@code null}
     * @return a buffered {@link BufferedWriter} over the freshly created staging file
     * @throws IOException if the staging file cannot be created or opened
     */
    private static BufferedWriter openOwnerOnlyStagingWriter(final Path temp) throws IOException {
        Files.deleteIfExists(temp);
        try {
            Files.createFile(temp, PosixFilePermissions.asFileAttribute(OWNER_ONLY_PERMISSIONS));
        } catch (UnsupportedOperationException nonPosixFileSystem) {
            Files.createFile(temp);
        }
        return Files.newBufferedWriter(temp, OUTPUT_CHARSET,
                StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING);
    }

    /**
     * Processes a chunk of report lines, reproducing the {@code CBTRN03C} main-loop body
     * (L181-198) for each in-range record in order. For every line it first performs the
     * card-number control break &mdash; writing the <em>previous</em> account's total when the card
     * changes (guarded by {@code WS-FIRST-TIME}) &mdash; then formats and writes the detail line via
     * {@link #writeTransactionReport(TransactionReportProcessor.ReportLine)}. The last amount is
     * retained for the end-of-file finalization quirk (see {@link #afterStep(StepExecution)}).
     *
     * @param chunk the chunk of fully-resolved report lines, in {@code (cardNum, tranId)} order;
     *              never {@code null}
     * @throws Exception if writing to the report file fails (surfaced as a {@link FileStatusException})
     */
    @Override
    public void write(Chunk<? extends TransactionReportProcessor.ReportLine> chunk) throws Exception {
        for (final TransactionReportProcessor.ReportLine rl : chunk.getItems()) {
            // Control break BEFORE the detail (CBTRN03C L183-190). currentCardNum == null models the
            // COBOL WS-CURR-CARD-NUM initial SPACES, so the first record always takes this branch;
            // the firstTime guard prevents an account total before any detail has been written.
            if (currentCardNum == null || !currentCardNum.equals(rl.cardNum())) {
                if (!firstTime) {
                    writeAccountTotals();
                }
                currentCardNum = rl.cardNum();
            }
            writeTransactionReport(rl);
            lastAmount = scale2(rl.amount());
        }
    }

    /**
     * Reproduces paragraph {@code 1100-WRITE-TRANSACTION-REPORT} (CBTRN03C L274-290) for one record:
     * emits the header block on the first detail, performs a page break when the line counter is a
     * multiple of the page size, accumulates the amount into the page and account totals, and writes
     * the detail line.
     *
     * <p>The pagination test uses the line-counter value <em>before</em> the current detail: after
     * the first-time header block the counter is 4, so the first page carries 16 detail lines before
     * the counter reaches 20 and the next call triggers a page total plus re-printed headers. The
     * increments are reproduced verbatim so paginated golden files match byte-for-byte.</p>
     *
     * @param rl the report line to format and write; never {@code null}
     */
    private void writeTransactionReport(TransactionReportProcessor.ReportLine rl) {
        if (firstTime) {
            firstTime = false;
            // REPT-START-DATE / REPT-END-DATE were captured in beforeStep and are used by writeHeaders.
            writeHeaders();
        }
        if (lineCounter % PAGE_SIZE == 0) {
            writePageTotals();
            writeHeaders();
        }
        pageTotal = pageTotal.add(scale2(rl.amount()));
        accountTotal = accountTotal.add(scale2(rl.amount()));
        writeDetail(rl);
    }

    /**
     * Reproduces {@code 1120-WRITE-HEADERS} (CBTRN03C L328-346): four lines &mdash; the report name
     * header, a blank (133-space) line, column header 1, and column header 2 &mdash; advancing the
     * line counter by four.
     */
    private void writeHeaders() {
        writeReportRecord(buildNameHeader());
        lineCounter++;
        writeReportRecord(BLANK_LINE);
        lineCounter++;
        writeReportRecord(HEADER_1);
        lineCounter++;
        writeReportRecord(HEADER_2);
        lineCounter++;
    }

    /**
     * Reproduces {@code 1120-WRITE-DETAIL} (CBTRN03C L361-374): writes the detail line for the
     * current record and advances the line counter by one. Also increments the detail-line
     * observability counter.
     *
     * @param rl the report line to format and write; never {@code null}
     */
    private void writeDetail(TransactionReportProcessor.ReportLine rl) {
        writeReportRecord(buildDetail(rl));
        lineCounter++;
        detailCount++;
    }

    /**
     * Reproduces {@code 1110-WRITE-PAGE-TOTALS} (CBTRN03C L294-307): writes the page-total line,
     * accumulates the page total into the grand total, resets the page total to zero, advances the
     * counter, then writes a {@code HEADER-2} rule and advances again (net {@code +2}). The
     * grand-total accumulation and page-total reset happen <em>after</em> the page-total line is
     * written, matching the COBOL evaluation order exactly.
     */
    private void writePageTotals() {
        writeReportRecord(buildPageTotals(pageTotal));
        grandTotal = grandTotal.add(pageTotal);
        pageTotal = BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);
        lineCounter++;
        writeReportRecord(HEADER_2);
        lineCounter++;
    }

    /**
     * Reproduces {@code 1120-WRITE-ACCOUNT-TOTALS} (CBTRN03C L309-320): writes the account-total
     * line for the just-completed card, resets the account total to zero, advances the counter, then
     * writes a {@code HEADER-2} rule and advances again (net {@code +2}). Unlike the page total, the
     * account total is <em>not</em> rolled into the grand total (the COBOL grand total is the sum of
     * page totals only).
     */
    private void writeAccountTotals() {
        writeReportRecord(buildAccountTotals(accountTotal));
        accountTotal = BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);
        lineCounter++;
        writeReportRecord(HEADER_2);
        lineCounter++;
    }

    /**
     * Reproduces {@code 1110-WRITE-GRAND-TOTALS} (CBTRN03C L322-326): writes the grand-total line.
     * The COBOL paragraph does not touch the line counter, so no increment occurs here.
     */
    private void writeGrandTotals() {
        writeReportRecord(buildGrandTotals(grandTotal));
    }

    /**
     * Reproduces {@code 1111-WRITE-REPORT-REC} (CBTRN03C L347-359): writes one fully-formed record
     * plus the line separator and increments the record counter. A defensive length check enforces
     * the {@code FD-REPTFILE-REC PIC X(133)} contract. Any {@link IOException} sets the error flag
     * and is re-thrown as a {@link FileStatusException} (the {@code TRANREPT-STATUS != '00'} &rarr;
     * {@code 9999-ABEND-PROGRAM} path).
     *
     * @param line133 a fully-formed report line that must be exactly {@value #RECORD_LENGTH}
     *                characters
     */
    private void writeReportRecord(String line133) {
        if (line133.length() != RECORD_LENGTH) {
            throw new IllegalStateException("report record width " + line133.length()
                    + " does not match the required " + RECORD_LENGTH + " bytes");
        }
        try {
            reportWriter.write(line133);
            reportWriter.write(RECORD_DELIMITER);
            recordCount++;
        } catch (IOException ex) {
            ioError = true;
            throw new FileStatusException(FILE_STATUS_PERMANENT_ERROR,
                    "Error writing transaction report record", ex);
        }
    }

    /**
     * Performs the end-of-file finalization, closes the report file, promotes observability
     * counters, and maps the outcome to a batch return code via the returned {@link ExitStatus}.
     *
     * <p>Reproduces the {@code CBTRN03C} end-of-file {@code ELSE} branch (main loop L200-204),
     * including a <strong>documented legacy quirk</strong>: the last transaction's amount is added a
     * <em>second</em> time to the page and account totals because the COBOL record area still holds
     * the final {@code TRAN-AMT} when the read reaches end-of-file. The final page total and grand
     * total therefore include the last amount twice. This behaviour is preserved deliberately for
     * parity and is recorded in {@code docs/decision-log.md}; it must not be "fixed". Note that the
     * end-of-file branch emits a page total and a grand total but <em>no</em> final account total.</p>
     *
     * <p>If the step already failed (an earlier I/O error, or a {@link BatchStatus#FAILED} status),
     * the finalization is skipped, the writer is closed best-effort, and {@link ExitStatus#FAILED} is
     * returned. When zero records were processed the last amount is {@code 0.00}, so the double-count
     * adds nothing, yet &mdash; matching COBOL &mdash; a page total and grand total are still emitted
     * even though no headers or details were written.</p>
     *
     * <p>After the staging stream is closed the completed report is either <strong>atomically
     * published</strong> to its final name (on a clean run) or <strong>deleted</strong> (on any
     * failure). Because the reader re-reads the full input on restart
     * ({@code TransactionReportItemReader.setSaveState(false)}), the staging file always holds the
     * complete report, so a restart or failure can never truncate or destroy a previously-published
     * report and a consumer never observes a partial or stale final-named file (QA finding F-P5-C).</p>
     *
     * @param stepExecution the completing step execution; never {@code null}
     * @return {@link ExitStatus#COMPLETED} (RC0) on a clean run, or {@link ExitStatus#FAILED} (RC8)
     *         if any open/write/close I/O error occurred, the step was already failing, or the
     *         completed report could not be published
     */
    @Override
    public ExitStatus afterStep(StepExecution stepExecution) {
        // Finalize EOF totals ONLY on a positively-verified clean completion (fail-closed, F-P5-E).
        // On the mid-write connection-loss race the status is still STARTED at afterStep time (FAILED
        // is persisted afterwards and fails on the dead DB), so a negative "== FAILED" check would
        // wrongly treat the run as clean, write EOF totals, and then publish a partial report. See
        // BatchFilePublishDecision.
        final boolean cleanCompletion =
                BatchFilePublishDecision.isCleanCompletion(stepExecution, ioError);
        if (cleanCompletion) {
            try {
                // EOF ELSE branch (CBTRN03C L200-204): ADD TRAN-AMT (the STALE last value) TO
                // WS-PAGE-TOTAL WS-ACCOUNT-TOTAL, then write the page total and grand total.
                pageTotal = pageTotal.add(lastAmount);
                accountTotal = accountTotal.add(lastAmount);
                writePageTotals();
                writeGrandTotals();
            } catch (FileStatusException ex) {
                // ioError has already been set by writeReportRecord; do not propagate, so the step
                // is finalized cleanly and mapped to the I/O-error return code below.
                LOGGER.error("I/O error while finalizing the transaction report: {}",
                        ex.getMessage());
            }
        }

        // Flush and close the staging stream before deciding whether to publish it. A close failure
        // sets ioError, so the final failed-state decision must be taken after this call.
        closeReportWriter();

        final ExecutionContext executionContext = stepExecution.getExecutionContext();
        executionContext.putLong("report.recordCount", recordCount);
        executionContext.putLong("report.detailCount", detailCount);
        executionContext.putString("report.grandTotal", grandTotal.toPlainString());

        // Publish the completed staging file atomically on success or discard it on failure, so a
        // consumer never observes a partial or stale final-named report (F-P5-C). All per-step file
        // state is cleared in the finally block so the singleton bean carries no state between runs.
        final Path temp = this.reportTempPath;
        final Path published = this.reportPath;
        // Re-evaluate the fail-closed gate AFTER closeReportWriter() (a close failure sets ioError):
        // publish ONLY on a verified-clean completion, otherwise discard the staging file (F-P5-E).
        final boolean clean = BatchFilePublishDecision.isCleanCompletion(stepExecution, ioError);
        try {
            if (temp == null) {
                // beforeStep never opened a staging file; nothing to publish or discard.
                return clean ? ExitStatus.COMPLETED : ExitStatus.FAILED;
            }
            if (!clean) {
                deleteQuietly(temp);
                LOGGER.error("Transaction report did not complete cleanly (status={}); staging file {} "
                        + "discarded and no report was published (no partial final-named file left "
                        + "behind).", stepExecution.getStatus(), temp);
                return ExitStatus.FAILED;
            }
            try {
                publishAtomically(temp, published);
            } catch (IOException ex) {
                LOGGER.error("Failed to publish transaction report {} -> {}", temp, published, ex);
                deleteQuietly(temp);
                return ExitStatus.FAILED;
            }
            LOGGER.info("Transaction report published: {} ({} records, {} detail lines).",
                    published, recordCount, detailCount);
            return ExitStatus.COMPLETED;
        } finally {
            this.reportTempPath = null;
        }
    }

    // ------------------------------------------------------------------------
    // Internal helpers
    // ------------------------------------------------------------------------

    /**
     * Flushes and closes the report writer (reproducing {@code 9100-REPTFILE-CLOSE}, CBTRN03C
     * L532-548). A close failure sets the error flag so the step maps to the I/O-error return code.
     * The stream reference is always cleared so the singleton bean carries no state between runs.
     */
    private void closeReportWriter() {
        if (reportWriter == null) {
            return;
        }
        try {
            reportWriter.flush();
            reportWriter.close();
        } catch (IOException ex) {
            ioError = true;
            LOGGER.error("Error closing the transaction report file: {}", ex.getMessage());
        } finally {
            reportWriter = null;
        }
    }

    /**
     * Publishes the completed staging file to its final path with an atomic move. On POSIX this is a
     * single {@code rename(2)}, so a consumer sees either the previously-published report or the
     * fully-written new report, never a partial file. If the filesystem cannot perform an atomic move
     * a plain same-directory rename is used as the closest available fallback (which still replaces
     * any previous report in a single directory operation).
     *
     * @param temp      the completed staging file; never {@code null}
     * @param published the final destination path; never {@code null}
     * @throws IOException if the move fails
     */
    private static void publishAtomically(final Path temp, final Path published) throws IOException {
        try {
            Files.move(temp, published,
                    StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException atomicUnsupported) {
            Files.move(temp, published, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    /**
     * Deletes the given path if it exists, swallowing any {@link IOException} (logged at {@code WARN})
     * so cleanup on a failure path never masks the original failure.
     *
     * @param path the path to delete; may be {@code null}
     */
    private static void deleteQuietly(final Path path) {
        if (path == null) {
            return;
        }
        try {
            Files.deleteIfExists(path);
        } catch (IOException ex) {
            LOGGER.warn("Unable to delete transaction report staging file {}: {}",
                    path, ex.getMessage());
        }
    }

    /**
     * Normalizes a job-parameter date to exactly ten characters for the {@code REPT-START-DATE} /
     * {@code REPT-END-DATE} fields ({@code PIC X(10)}). A {@code null} value (absent parameter)
     * becomes ten spaces (a blank {@code DATEPARM} field); a longer value is truncated and a shorter
     * value is right-padded with spaces, matching a COBOL {@code MOVE} into a {@code PIC X(10)} item.
     *
     * @param value the raw job-parameter value; may be {@code null}
     * @return an exactly ten-character date string
     */
    private String normalizeDate(String value) {
        return FixedWidthCodec.writeAlphanumeric(value, 10);
    }

    // ------------------------------------------------------------------------
    // Static line builders (fully constant lines)
    // ------------------------------------------------------------------------

    /**
     * Builds {@code TRANSACTION-HEADER-1} (CVTRA07Y L33-46) once: the fixed column-header line. Each
     * COBOL {@code FILLER} is a left-justified, space-padded literal at a fixed offset. The single
     * space {@code FILLER} at offset 97 (CVTRA07Y L44) is left as a space, so the "Amount" label
     * begins at offset 98 &mdash; exactly one column to the right of the amount value column (offset
     * {@value #AMOUNT_OFFSET}), reproducing the legacy alignment.
     *
     * @return the 133-character column-header line
     */
    private static String buildColumnHeader1() {
        return FixedWidthCodec.of(RECORD_LENGTH)
                .putRaw(0, FixedWidthCodec.writeAlphanumeric("Transaction ID", 17))
                .putRaw(17, FixedWidthCodec.writeAlphanumeric("Account ID", 12))
                .putRaw(29, FixedWidthCodec.writeAlphanumeric("Transaction Type", 19))
                .putRaw(48, FixedWidthCodec.writeAlphanumeric("Tran Category", 35))
                .putRaw(83, FixedWidthCodec.writeAlphanumeric("Tran Source", 14))
                .putRaw(98, FixedWidthCodec.writeAlphanumeric("        Amount", 16))
                .build();
    }

    // ------------------------------------------------------------------------
    // Amount edit-mask formatters (COBOL numeric-edit PICTURE reproduction)
    // ------------------------------------------------------------------------

    /**
     * Reproduces the COBOL numeric-edit PICTUREs {@code -ZZZ,ZZZ,ZZZ.ZZ} (detail, non-negative sign
     * = space) and {@code +ZZZ,ZZZ,ZZZ.ZZ} (totals, non-negative sign = {@code '+'}), always
     * producing an exactly {@value #AMOUNT_WIDTH}-character string. The masks differ only in the
     * character used for a non-negative value.
     *
     * <p>Semantics (all locale-independent, matching COBOL regardless of the JVM default locale):</p>
     * <ul>
     *   <li><strong>All-Z zero rule:</strong> because every numeric position in the PICTURE is
     *       {@code Z}, an exact-zero value blanks the entire field (sign, digits, and decimal point)
     *       &mdash; 15 spaces.</li>
     *   <li><strong>Sign:</strong> a fixed leading sign position: {@code '-'} for a negative value,
     *       otherwise {@code signForNonNegative}.</li>
     *   <li><strong>Integer part:</strong> {@code ZZZ,ZZZ,ZZZ} with leading-zero and leading-comma
     *       suppression (see {@link #suppressAndGroup(long)}).</li>
     *   <li><strong>Fraction:</strong> always two digits after the inserted decimal point once the
     *       value is non-zero.</li>
     *   <li><strong>Field truncation:</strong> the COBOL field is {@code S9(9)V99}, so the integer
     *       part holds at most nine digits; an overflow is high-order truncated ({@code % 10^9}),
     *       mirroring a COBOL {@code MOVE} into the fixed-size edited field.</li>
     * </ul>
     *
     * @param value              the amount to render; {@code null} is treated as zero
     * @param signForNonNegative the sign character emitted for a value &ge; 0 ({@code ' '} for the
     *                           detail mask, {@code '+'} for the totals mask)
     * @return an exactly {@value #AMOUNT_WIDTH}-character edited amount string
     */
    private String formatEditedAmount(BigDecimal value, char signForNonNegative) {
        final BigDecimal v = (value == null ? BigDecimal.ZERO : value).setScale(2, RoundingMode.HALF_UP);
        if (v.signum() == 0) {
            // COBOL "all-Z, value zero" rule: blank the entire field (sign, digits, and point).
            return " ".repeat(AMOUNT_WIDTH);
        }
        final boolean negative = v.signum() < 0;
        final BigDecimal abs = v.abs();
        final long totalCents = abs.movePointRight(2).longValueExact();
        long intPart = totalCents / 100;
        final long frac = totalCents % 100;
        // S9(9)V99: the integer part holds at most nine digits; mirror field truncation on overflow.
        intPart = intPart % 1_000_000_000L;
        final String intField = suppressAndGroup(intPart);
        final char sign = negative ? '-' : signForNonNegative;
        final String cents = (frac < 10 ? "0" + frac : Long.toString(frac));
        return sign + intField + "." + cents;
    }

    /**
     * Formats an amount with the detail edit mask {@code -ZZZ,ZZZ,ZZZ.ZZ} (space sign for values
     * &ge; 0). Used for {@code TRAN-REPORT-AMT} (CVTRA07Y L30).
     *
     * @param value the amount to render; {@code null} is treated as zero
     * @return an exactly {@value #AMOUNT_WIDTH}-character edited amount string
     */
    private String formatDetailAmount(BigDecimal value) {
        return formatEditedAmount(value, ' ');
    }

    /**
     * Formats an amount with the totals edit mask {@code +ZZZ,ZZZ,ZZZ.ZZ} ({@code '+'} sign for
     * values &ge; 0). Used for {@code REPT-PAGE-TOTAL}, {@code REPT-ACCOUNT-TOTAL}, and
     * {@code REPT-GRAND-TOTAL} (CVTRA07Y L54, L60, L66).
     *
     * @param value the amount to render; {@code null} is treated as zero
     * @return an exactly {@value #AMOUNT_WIDTH}-character edited amount string
     */
    private String formatTotalAmount(BigDecimal value) {
        return formatEditedAmount(value, '+');
    }

    /**
     * Renders the integer part into the 11-character {@code ZZZ,ZZZ,ZZZ} group with COBOL
     * zero-suppression: leading zeros and the commas that precede the first significant digit are
     * replaced with spaces.
     *
     * <ul>
     *   <li>{@code intPart == 0} &rarr; 11 spaces (every integer digit suppressed; occurs when
     *       {@code 0 < |value| < 1}, e.g. {@code 0.77}).</li>
     *   <li>Otherwise the value is zero-padded to nine digits, grouped as {@code "ddd,ddd,ddd"}
     *       (11 characters), then each leading {@code '0'} and each {@code ','} that precedes the
     *       first digit {@code 1-9} is replaced with a space (scanning left to right, stopping at the
     *       first significant digit).</li>
     * </ul>
     *
     * @param intPart the non-negative integer part, already reduced to at most nine digits
     * @return an exactly 11-character, right-justified, zero-suppressed group string
     */
    private String suppressAndGroup(long intPart) {
        if (intPart == 0) {
            return " ".repeat(11);
        }
        final String digits = FixedWidthCodec.writeNumeric(intPart, 9);
        final char[] grouped = (digits.substring(0, 3) + "," + digits.substring(3, 6)
                + "," + digits.substring(6, 9)).toCharArray();
        for (int i = 0; i < grouped.length; i++) {
            final char c = grouped[i];
            if (c == '0' || c == ',') {
                grouped[i] = ' ';
            } else {
                break;
            }
        }
        return new String(grouped);
    }

    // ------------------------------------------------------------------------
    // Per-record line builders (each returns an exactly 133-character line)
    // ------------------------------------------------------------------------

    /**
     * Builds {@code REPORT-NAME-HEADER} (CVTRA07Y L4-13) using the report date range captured in
     * {@link #beforeStep(StepExecution)}. Content is 115 bytes right-padded to
     * {@value #RECORD_LENGTH}.
     *
     * @return the 133-character report name-header line
     */
    private String buildNameHeader() {
        return FixedWidthCodec.of(RECORD_LENGTH)
                .putRaw(0, FixedWidthCodec.writeAlphanumeric("DALYREPT", 38))
                .putRaw(38, FixedWidthCodec.writeAlphanumeric("Daily Transaction Report", 41))
                .putRaw(79, "Date Range: ")
                .putRaw(91, FixedWidthCodec.writeAlphanumeric(reportStartDate, 10))
                .putRaw(101, " to ")
                .putRaw(105, FixedWidthCodec.writeAlphanumeric(reportEndDate, 10))
                .build();
    }

    /**
     * Builds {@code TRANSACTION-DETAIL-REPORT} (CVTRA07Y L15-31) for a single report line. Fields
     * are placed at their exact offsets; the two literal {@code '-'} separators sit at offsets 31
     * and 52, the account id is a zero-padded {@code 9(11)}, the category code is a zero-padded
     * {@code 9(4)}, and the amount uses the detail edit mask at offset {@value #AMOUNT_OFFSET}.
     * Positions not written remain spaces, reproducing the COBOL {@code INITIALIZE} of the group.
     *
     * @param rl the fully-resolved report line to format; never {@code null}
     * @return the 133-character detail line
     */
    private String buildDetail(TransactionReportProcessor.ReportLine rl) {
        final long accountId = rl.accountId() == null ? 0L : rl.accountId();
        final long catCd = rl.catCd() == null ? 0L : rl.catCd().longValue();
        return FixedWidthCodec.of(RECORD_LENGTH)
                .putRaw(0, FixedWidthCodec.writeAlphanumeric(rl.tranId(), 16))
                .putRaw(17, FixedWidthCodec.writeNumeric(accountId, 11))
                .putRaw(29, FixedWidthCodec.writeAlphanumeric(rl.typeCd(), 2))
                .putRaw(31, "-")
                .putRaw(32, FixedWidthCodec.writeAlphanumeric(rl.typeDesc(), 15))
                .putRaw(48, FixedWidthCodec.writeNumeric(catCd, 4))
                .putRaw(52, "-")
                .putRaw(53, FixedWidthCodec.writeAlphanumeric(rl.catDesc(), 29))
                .putRaw(83, FixedWidthCodec.writeAlphanumeric(rl.source(), 10))
                .putRaw(AMOUNT_OFFSET, formatDetailAmount(rl.amount()))
                .build();
    }

    /**
     * Builds {@code REPORT-PAGE-TOTALS} (CVTRA07Y L50-54): the literal {@code "Page Total"} label, a
     * run of 86 dots, and the page total formatted with the totals edit mask at offset
     * {@value #AMOUNT_OFFSET}.
     *
     * @param amount the page total to render
     * @return the 133-character page-total line
     */
    private String buildPageTotals(BigDecimal amount) {
        return FixedWidthCodec.of(RECORD_LENGTH)
                .putRaw(0, FixedWidthCodec.writeAlphanumeric("Page Total", 11))
                .putRaw(11, ".".repeat(86))
                .putRaw(AMOUNT_OFFSET, formatTotalAmount(amount))
                .build();
    }

    /**
     * Builds {@code REPORT-ACCOUNT-TOTALS} (CVTRA07Y L56-60): the literal {@code "Account Total"}
     * label, a run of 84 dots, and the account total formatted with the totals edit mask at offset
     * {@value #AMOUNT_OFFSET}.
     *
     * @param amount the account total to render
     * @return the 133-character account-total line
     */
    private String buildAccountTotals(BigDecimal amount) {
        return FixedWidthCodec.of(RECORD_LENGTH)
                .putRaw(0, FixedWidthCodec.writeAlphanumeric("Account Total", 13))
                .putRaw(13, ".".repeat(84))
                .putRaw(AMOUNT_OFFSET, formatTotalAmount(amount))
                .build();
    }

    /**
     * Builds {@code REPORT-GRAND-TOTALS} (CVTRA07Y L62-66): the literal {@code "Grand Total"} label,
     * a run of 86 dots, and the grand total formatted with the totals edit mask at offset
     * {@value #AMOUNT_OFFSET}.
     *
     * @param amount the grand total to render
     * @return the 133-character grand-total line
     */
    private String buildGrandTotals(BigDecimal amount) {
        return FixedWidthCodec.of(RECORD_LENGTH)
                .putRaw(0, FixedWidthCodec.writeAlphanumeric("Grand Total", 11))
                .putRaw(11, ".".repeat(86))
                .putRaw(AMOUNT_OFFSET, formatTotalAmount(amount))
                .build();
    }

    /**
     * Normalizes an amount to the money scale used throughout the report: scale 2 with
     * {@link RoundingMode#HALF_UP}. A {@code null} value becomes {@code 0.00}.
     *
     * @param value the amount to normalize; may be {@code null}
     * @return a non-{@code null} {@link BigDecimal} at scale 2
     */
    private BigDecimal scale2(BigDecimal value) {
        return (value == null ? BigDecimal.ZERO : value).setScale(2, RoundingMode.HALF_UP);
    }
}
