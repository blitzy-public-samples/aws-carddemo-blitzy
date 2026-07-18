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
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;

import com.aws.carddemo.batch.processor.StatementProcessor;
import com.aws.carddemo.common.util.FixedWidthCodec;
import com.aws.carddemo.exception.FileStatusException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.ExitStatus;
import org.springframework.batch.core.StepExecution;
import org.springframework.batch.core.StepExecutionListener;
import org.springframework.batch.item.Chunk;
import org.springframework.batch.item.ItemWriter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Spring Batch {@link ItemWriter} that reproduces the <strong>output side</strong> of the legacy
 * COBOL statement program {@code legacy/cbl/CBSTM03A.CBL} (source {@code app/cbl/CBSTM03A.CBL}),
 * the batch program triggered by {@code CREASTMT.JCL} ({@code STEP040 EXEC PGM=CBSTM03A}). It is the
 * writer half of {@code StatementGenerationJob}, wired into the {@code statementGenerationStep} bean
 * in the parent {@code batch/} package under the bean name {@code statementItemWriter}.
 *
 * <h2>What this writer does — and does not do</h2>
 * <p>This writer <strong>only serializes</strong> already-assembled statement content. The upstream
 * {@link StatementProcessor} builds every text and HTML line per card into a
 * {@link StatementProcessor.StatementDocument} value object; this writer takes those lines and
 * appends each to the correct fixed-width output file. It performs <strong>no</strong> database
 * access, injects <strong>no</strong> repository or file service, and contains <strong>no</strong>
 * monetary arithmetic — all formatting and rounding happened in the processor.</p>
 *
 * <h2>Two independent sequential output files (the two CBSTM03A output DDs)</h2>
 * <ol>
 *   <li><strong>Plain-text statement file.</strong> Mirrors {@code STMT-FILE} / {@code FD-STMTFILE-REC
 *       PIC X(80)} (CBSTM03A L44-L45) and the {@code STMTFILE} DD in {@code CREASTMT.JCL} STEP040
 *       ({@code DCB=(LRECL=80,...,RECFM=FB)}, L87-L91). Every record is exactly
 *       {@value #TEXT_RECORD_LENGTH} bytes.</li>
 *   <li><strong>HTML statement file.</strong> Mirrors {@code HTML-FILE} / {@code FD-HTMLFILE-REC PIC
 *       X(100)} (CBSTM03A L46-L47) and the {@code HTMLFILE} DD in STEP040
 *       ({@code DCB=(LRECL=100,...,RECFM=FB)}, L92-L96). Every record is exactly
 *       {@value #HTML_RECORD_LENGTH} bytes. (The earlier {@code IEFBR14} delete step STEP030 shows a
 *       stale {@code LRECL=80} for {@code HTMLFILE}; the authoritative width is 100, matching both the
 *       COBOL FD and the STEP040 run-step DCB.)</li>
 * </ol>
 *
 * <p>The two files are independent, so the two streams need not be interleaved: the COBOL interleaves
 * text and HTML {@code WRITE}s in program flow, but because they target different files, writing all
 * of a document's text lines and then all of its HTML lines is byte-identical <em>per file</em>. Only
 * the record order <em>within</em> each file matters, and that order is preserved.</p>
 *
 * <h2>Lifecycle (mirrors {@code OPEN OUTPUT ... / WRITE ... / CLOSE ...})</h2>
 * <p>The class implements {@link StepExecutionListener} so Spring Batch auto-registers its
 * {@link #beforeStep(StepExecution)} / {@link #afterStep(StepExecution)} callbacks for the step:</p>
 * <ul>
 *   <li>{@link #beforeStep(StepExecution)} opens both files once, truncate/create
 *       (CBSTM03A L293 {@code OPEN OUTPUT STMT-FILE HTML-FILE}; STEP040 {@code DISP=(NEW,CATLG,DELETE)}
 *       fresh-file semantics). Even a run that yields zero cards leaves two empty files present.</li>
 *   <li>{@link #write(Chunk)} serializes each {@link StatementProcessor.StatementDocument}'s lines
 *       ({@code WRITE FD-STMTFILE-REC} / {@code WRITE FD-HTMLFILE-REC}).</li>
 *   <li>{@link #afterStep(StepExecution)} closes both files once
 *       (CBSTM03A L339 {@code CLOSE STMT-FILE HTML-FILE}) and maps the outcome to a batch return
 *       code.</li>
 * </ul>
 *
 * <h2>Byte-exact record model</h2>
 * <p>Every line is normalised through {@link FixedWidthCodec#writeAlphanumeric(String, int)}, which
 * implements COBOL {@code MOVE ... TO PIC X(n)} semantics (left-justify, right space-fill, right
 * truncate) and returns a string of exactly the requested width. Records are written with the
 * {@link StandardCharsets#ISO_8859_1} charset so one character maps to exactly one byte — matching the
 * fixed-width mainframe record model and keeping golden-file comparisons deterministic. Each
 * fixed-width record is followed by a single {@code "\n"} separator (the canonical external-file
 * representation shared by the sibling file writers and recorded in {@code docs/decision-log.md}); the
 * {@value #TEXT_RECORD_LENGTH}/{@value #HTML_RECORD_LENGTH} content bytes per record remain exact.</p>
 *
 * <h2>Return-code contract</h2>
 * <p>Statement generation has <strong>no reject path</strong> — only success or an I/O failure:</p>
 * <ul>
 *   <li><strong>RC 0</strong> — {@link ExitStatus#COMPLETED}: no I/O error and the step did not
 *       fail.</li>
 *   <li><strong>RC 8</strong> — {@link ExitStatus#FAILED}: an I/O error occurred opening, writing, or
 *       closing either file (surfaced as a {@link FileStatusException} in
 *       {@link #beforeStep(StepExecution)}/{@link #write(Chunk)}, or recorded via the sticky
 *       {@link #ioError} flag), or the step itself is {@link BatchStatus#FAILED}.</li>
 * </ul>
 *
 * <h2>Sensitive data</h2>
 * <p>Statement content includes customer names, addresses, FICO scores, and account/transaction data.
 * This writer <strong>never logs statement or HTML line content</strong>; it logs only record counts
 * and the (non-sensitive) configured file paths.</p>
 *
 * <p>This is a singleton bean (deliberately <em>not</em> {@code @StepScope}); all per-run state is
 * re-initialised in {@link #beforeStep(StepExecution)}, so re-use across job executions is safe.</p>
 */
@Component
public class StatementItemWriter
        implements ItemWriter<StatementProcessor.StatementDocument>, StepExecutionListener {

    /** SLF4J logger. Only record counts and file paths are ever logged — never line content. */
    private static final Logger log = LoggerFactory.getLogger(StatementItemWriter.class);

    /** Text record width — {@code FD-STMTFILE-REC PIC X(80)} (CBSTM03A L45; STEP040 {@code LRECL=80}). */
    private static final int TEXT_RECORD_LENGTH = 80;

    /** HTML record width — {@code FD-HTMLFILE-REC PIC X(100)} (CBSTM03A L47; STEP040 {@code LRECL=100}). */
    private static final int HTML_RECORD_LENGTH = 100;

    /** Inter-record separator appended after every fixed-width record for deterministic, viewable output. */
    private static final String RECORD_DELIMITER = "\n";

    /** Output charset: one character maps to exactly one byte, matching the fixed-width record model. */
    private static final Charset OUTPUT_CHARSET = StandardCharsets.ISO_8859_1;

    /** Configured output directory (externalised; no hardcoded paths). */
    private final String outputDirectory;

    /** Configured plain-text statement file name (the {@code STMTFILE} DD analog). */
    private final String textFileName;

    /** Configured HTML statement file name (the {@code HTMLFILE} DD analog). */
    private final String htmlFileName;

    /** Open text-file stream for the current step; {@code null} until {@link #beforeStep} opens it. */
    private BufferedWriter textWriter;

    /** Open HTML-file stream for the current step; {@code null} until {@link #beforeStep} opens it. */
    private BufferedWriter htmlWriter;

    /** Resolved text-file path for the current step (used for logging and error messages). */
    private Path textPath;

    /** Resolved HTML-file path for the current step (used for logging and error messages). */
    private Path htmlPath;

    /** Number of {@link StatementProcessor.StatementDocument}s (cards) processed in the current step. */
    private long documentCount;

    /** Total number of {@value #TEXT_RECORD_LENGTH}-byte text records written in the current step. */
    private long textRecordCount;

    /** Total number of {@value #HTML_RECORD_LENGTH}-byte HTML records written in the current step. */
    private long htmlRecordCount;

    /** Sticky I/O-error flag; when set, {@link #afterStep(StepExecution)} maps the step to RC 8. */
    private boolean ioError;

    /**
     * Creates the writer with externalised output-location configuration. Paths are resolved lazily in
     * {@link #beforeStep(StepExecution)} (the directory may not exist until the step runs), so the
     * constructor only stores the raw configuration values.
     *
     * @param outputDirectory the directory that will hold both output files; defaults to
     *                        {@code ./target/batch} when the property is absent
     * @param textFileName    the plain-text statement file name; defaults to {@code statements.txt}
     * @param htmlFileName     the HTML statement file name; defaults to {@code statements.html}
     */
    public StatementItemWriter(
            @Value("${carddemo.batch.statement.output-directory:./target/batch}") String outputDirectory,
            @Value("${carddemo.batch.statement.text-file:statements.txt}") String textFileName,
            @Value("${carddemo.batch.statement.html-file:statements.html}") String htmlFileName) {
        this.outputDirectory = outputDirectory;
        this.textFileName = textFileName;
        this.htmlFileName = htmlFileName;
    }

    /**
     * Opens both output files for the step, reproducing CBSTM03A L293
     * {@code OPEN OUTPUT STMT-FILE HTML-FILE}.
     *
     * <p>All per-run state is reset first (this is a singleton bean re-used across job executions).
     * The output directory is created if absent, then both streams are opened in truncate/create mode
     * with the {@link #OUTPUT_CHARSET ISO-8859-1} charset — reproducing the {@code DISP=(NEW,CATLG,DELETE)}
     * fresh-file semantics of the STEP040 DDs, so a re-run overwrites prior output and a run yielding
     * zero cards still leaves two valid, empty files. The text stream is opened first, then the HTML
     * stream, mirroring the operand order of the COBOL {@code OPEN}.</p>
     *
     * <p>{@link StepExecutionListener#beforeStep(StepExecution)} cannot declare a checked exception, so
     * an {@link IOException} while creating the directory or opening either stream is rethrown as an
     * (unchecked) {@link FileStatusException}. This fails the step and job fast — the batch
     * return-code-8 analog of the COBOL open-failure abend. Opening is a hard prerequisite; the writer
     * never silently continues without both streams.</p>
     *
     * @param stepExecution the current step execution (supplied by Spring Batch); not otherwise used
     * @throws FileStatusException if the output directory cannot be created or either file cannot be
     *                             opened for writing
     */
    @Override
    public void beforeStep(StepExecution stepExecution) {
        // Reset per-run state (mirrors a fresh program invocation with counters at zero).
        this.documentCount = 0L;
        this.textRecordCount = 0L;
        this.htmlRecordCount = 0L;
        this.ioError = false;
        this.textWriter = null;
        this.htmlWriter = null;

        final Path outputDir = Path.of(outputDirectory);
        this.textPath = outputDir.resolve(textFileName);
        this.htmlPath = outputDir.resolve(htmlFileName);
        try {
            // Idempotent: creates the directory tree if it does not already exist.
            Files.createDirectories(outputDir);
            // OPEN OUTPUT STMT-FILE HTML-FILE (L293): text first, then HTML. Truncate/create so a
            // re-run overwrites and an empty run still yields a valid, empty file.
            this.textWriter = Files.newBufferedWriter(textPath, OUTPUT_CHARSET,
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING,
                    StandardOpenOption.WRITE);
            this.htmlWriter = Files.newBufferedWriter(htmlPath, OUTPUT_CHARSET,
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING,
                    StandardOpenOption.WRITE);
        } catch (IOException ex) {
            this.ioError = true;
            // Best-effort cleanup: the text stream may have opened before the HTML open failed.
            closeQuietly(this.textWriter);
            this.textWriter = null;
            this.htmlWriter = null;
            // Fail fast (batch RC 8), mirroring the COBOL open-failure abend. Paths carry no PII.
            throw new FileStatusException(FileStatusException.STATUS_OK,
                    "Error opening statement output files (STMTFILE=" + textPath
                            + ", HTMLFILE=" + htmlPath + ")", ex);
        }
        log.info("Statement output files opened: STMTFILE={} ({}-byte records), "
                + "HTMLFILE={} ({}-byte records), charset=ISO-8859-1.",
                textPath, TEXT_RECORD_LENGTH, htmlPath, HTML_RECORD_LENGTH);
    }

    /**
     * Serialises each {@link StatementProcessor.StatementDocument} in the chunk to the two output
     * files, reproducing the {@code WRITE FD-STMTFILE-REC} / {@code WRITE FD-HTMLFILE-REC} statements
     * of CBSTM03A.
     *
     * <p>For every document (one per card, in reader/processor order) all of its text lines are
     * written to the text file, then all of its HTML lines to the HTML file. Because the two files are
     * independent, this text-then-HTML ordering is byte-identical <em>per file</em> to the COBOL,
     * which is the only ordering that matters. Document order across the chunk (and across chunks) is
     * preserved, reproducing the XREF card-processing order.</p>
     *
     * <p>An {@link IOException} from either stream sets the sticky {@link #ioError} flag and is
     * rethrown as a {@link FileStatusException} so the chunk and step fail fast (batch RC 8) rather
     * than emitting a truncated statement file. The I/O error is never swallowed.</p>
     *
     * @param chunk the chunk of assembled statement documents; never {@code null}
     * @throws Exception if a statement record cannot be written (surfaced as a
     *                   {@link FileStatusException} that fails the step)
     */
    @Override
    public void write(Chunk<? extends StatementProcessor.StatementDocument> chunk) throws Exception {
        for (StatementProcessor.StatementDocument document : chunk.getItems()) {
            documentCount++;
            try {
                writeTextLines(document.textLines());
                writeHtmlLines(document.htmlLines());
            } catch (IOException ex) {
                ioError = true;
                // Fail fast (batch RC 8). File names are non-sensitive configuration; content is not logged.
                throw new FileStatusException(FileStatusException.STATUS_OK,
                        "I/O error writing statement output records (STMTFILE=" + textFileName
                                + ", HTMLFILE=" + htmlFileName + ")", ex);
            }
        }
    }

    /**
     * Writes one document's plain-text lines as {@value #TEXT_RECORD_LENGTH}-byte records
     * ({@code WRITE FD-STMTFILE-REC}). Each line is normalised through
     * {@link FixedWidthCodec#writeAlphanumeric(String, int)} (COBOL {@code PIC X(80)} MOVE semantics:
     * left-justify, right space-fill, right truncate) and followed by the record delimiter.
     *
     * @param lines the ordered text lines for one statement; a {@code null} list writes no records
     * @throws IOException if the underlying stream write fails
     */
    private void writeTextLines(List<String> lines) throws IOException {
        if (lines == null) {
            // Never NPE if the processor unexpectedly supplies a null list. No content is logged.
            log.debug("Statement document supplied a null text-line list; no text records written for it.");
            return;
        }
        for (String line : lines) {
            // FixedWidthCodec guarantees a String of exactly TEXT_RECORD_LENGTH characters.
            textWriter.write(FixedWidthCodec.writeAlphanumeric(line, TEXT_RECORD_LENGTH));
            textWriter.write(RECORD_DELIMITER);
            textRecordCount++;
        }
    }

    /**
     * Writes one document's HTML lines as {@value #HTML_RECORD_LENGTH}-byte records
     * ({@code WRITE FD-HTMLFILE-REC}). Each line is normalised through
     * {@link FixedWidthCodec#writeAlphanumeric(String, int)} (COBOL {@code PIC X(100)} MOVE semantics:
     * left-justify, right space-fill, right truncate) and followed by the record delimiter.
     *
     * @param lines the ordered HTML lines for one statement; a {@code null} list writes no records
     * @throws IOException if the underlying stream write fails
     */
    private void writeHtmlLines(List<String> lines) throws IOException {
        if (lines == null) {
            // Never NPE if the processor unexpectedly supplies a null list. No content is logged.
            log.debug("Statement document supplied a null HTML-line list; no HTML records written for it.");
            return;
        }
        for (String line : lines) {
            // FixedWidthCodec guarantees a String of exactly HTML_RECORD_LENGTH characters.
            htmlWriter.write(FixedWidthCodec.writeAlphanumeric(line, HTML_RECORD_LENGTH));
            htmlWriter.write(RECORD_DELIMITER);
            htmlRecordCount++;
        }
    }

    /**
     * Closes both output files and maps the step outcome to a batch return code, reproducing CBSTM03A
     * L339 {@code CLOSE STMT-FILE HTML-FILE}.
     *
     * <p>Both streams are flushed and closed best-effort (text first, then HTML), each guarded against
     * a {@code null} reference in case {@link #beforeStep(StepExecution)} failed before opening one; a
     * flush/close failure sets the sticky {@link #ioError} flag (RC 8) and is logged without content.
     * The three record counters are promoted to the step execution context for observability and
     * restart traceability, and a single INFO summary of the counts and file paths is logged. Because
     * statement generation has no reject path, the outcome is only success or failure:</p>
     * <ul>
     *   <li>{@link #ioError} or a {@link BatchStatus#FAILED} step &rarr; {@link ExitStatus#FAILED}
     *       (RC 8);</li>
     *   <li>otherwise &rarr; {@link ExitStatus#COMPLETED} (RC 0).</li>
     * </ul>
     *
     * @param stepExecution the current step execution (supplied by Spring Batch)
     * @return the mapped {@link ExitStatus}; never {@code null}
     */
    @Override
    public ExitStatus afterStep(StepExecution stepExecution) {
        // CLOSE STMT-FILE HTML-FILE (L339): flush and close both, null-guarded so a failed open does
        // not NPE. A close/flush failure is an I/O error and drives the RC 8 mapping below.
        if (textWriter != null) {
            try {
                textWriter.flush();
                textWriter.close();
            } catch (IOException ex) {
                ioError = true;
                log.error("Error closing statement text file (STMTFILE={}).", textPath, ex);
            } finally {
                textWriter = null;
            }
        }
        if (htmlWriter != null) {
            try {
                htmlWriter.flush();
                htmlWriter.close();
            } catch (IOException ex) {
                ioError = true;
                log.error("Error closing statement HTML file (HTMLFILE={}).", htmlPath, ex);
            } finally {
                htmlWriter = null;
            }
        }

        // Promote counters for observability/restart traceability.
        stepExecution.getExecutionContext().putLong("statement.documentCount", documentCount);
        stepExecution.getExecutionContext().putLong("statement.textRecordCount", textRecordCount);
        stepExecution.getExecutionContext().putLong("statement.htmlRecordCount", htmlRecordCount);

        // Single INFO summary — counts and file paths only (statement content is PII, never logged).
        log.info("Statement writer finished: {} document(s); {} text record(s) (STMTFILE={}); "
                + "{} HTML record(s) (HTMLFILE={}).",
                documentCount, textRecordCount, textPath, htmlRecordCount, htmlPath);

        // Return-code mapping: statements have NO reject path. I/O error or failed step -> RC 8; else RC 0.
        if (ioError || stepExecution.getStatus() == BatchStatus.FAILED) {
            return ExitStatus.FAILED;
        }
        return ExitStatus.COMPLETED;
    }

    /**
     * Closes a writer best-effort during open-failure cleanup, suppressing (but logging) any
     * {@link IOException}. Used only when {@link #beforeStep(StepExecution)} is already aborting, so
     * the suppressed error does not mask the primary open failure.
     *
     * @param writer the writer to close; a {@code null} value is ignored
     */
    private static void closeQuietly(BufferedWriter writer) {
        if (writer == null) {
            return;
        }
        try {
            writer.close();
        } catch (IOException ex) {
            log.warn("Suppressed I/O error while closing a statement output stream during "
                    + "open-failure cleanup.", ex);
        }
    }
}
