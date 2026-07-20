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
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.nio.charset.Charset;
import java.nio.charset.CharsetEncoder;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.FileAttribute;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

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
 *   <li>{@link #beforeStep(StepExecution)} opens two <em>temporary</em> work files (one per output
 *       file) in the configured output directory
 *       (CBSTM03A L293 {@code OPEN OUTPUT STMT-FILE HTML-FILE}; STEP040 {@code DISP=(NEW,CATLG,DELETE)}
 *       fresh-file semantics). Even a run that yields zero cards leaves two empty files present after a
 *       successful publish.</li>
 *   <li>{@link #write(Chunk)} serializes each {@link StatementProcessor.StatementDocument}'s lines to
 *       the temporary files ({@code WRITE FD-STMTFILE-REC} / {@code WRITE FD-HTMLFILE-REC}).</li>
 *   <li>{@link #afterStep(StepExecution)} closes both temporary files once
 *       (CBSTM03A L339 {@code CLOSE STMT-FILE HTML-FILE}) and, <strong>only when the step succeeded</strong>,
 *       atomically publishes each temporary file onto its final path; on any failure the temporary
 *       files are deleted and the final paths are left untouched. The outcome is mapped to a batch
 *       return code.</li>
 * </ul>
 *
 * <h2>Atomic publication (no partial or truncated artifacts)</h2>
 * <p>Records are streamed to per-run temporary work files rather than to the final output paths, so a
 * chunk rollback, a mid-write I/O error, or a job failure can never leave a half-written or truncated
 * file at a final path (CWE-459). Each temporary file is created owner-only where the filesystem
 * supports POSIX permissions ({@code rw-------}), and each is published with an
 * {@link java.nio.file.StandardCopyOption#ATOMIC_MOVE atomic move} (falling back to a replacing move on
 * filesystems that cannot move atomically). The two output files are independent artifacts (the two
 * CBSTM03A output DDs) and are therefore published independently; a reader of a final path observes
 * either the fully written new file or the untouched previous file, never a partial one. If publication
 * of either file fails, the run is reported as {@link ExitStatus#FAILED} (RC 8).</p>
 *
 * <h2>Byte-exact record model</h2>
 * <p>Every line is normalised through {@link FixedWidthCodec#writeAlphanumeric(String, int)}, which
 * implements COBOL {@code MOVE ... TO PIC X(n)} semantics (left-justify, right space-fill, right
 * truncate) and returns a string of exactly the requested width. Records are written with the
 * {@link StandardCharsets#ISO_8859_1} charset so one character maps to exactly one byte — matching the
 * fixed-width mainframe record model and keeping golden-file comparisons deterministic. Records are
 * written back-to-back with <strong>no in-band delimiter</strong>: the files reproduce the COBOL
 * {@code RECFM=FB} contract ({@code FD-STMTFILE-REC PIC X(80)} / {@code FD-HTMLFILE-REC PIC X(100)},
 * STEP040 {@code RECFM=FB}), so a text file of <var>n</var> records is exactly
 * <var>n</var>&nbsp;&times;&nbsp;{@value #TEXT_RECORD_LENGTH} bytes and an HTML file of <var>n</var>
 * records is exactly <var>n</var>&nbsp;&times;&nbsp;{@value #HTML_RECORD_LENGTH} bytes, with no
 * trailing newline. Record boundaries are implied by the fixed record length alone.</p>
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

    /** Output charset: one character maps to exactly one byte, matching the fixed-width record model. */
    private static final Charset OUTPUT_CHARSET = StandardCharsets.ISO_8859_1;

    /** Prefix applied to per-run temporary work files created in the output directory before publish. */
    private static final String TEMP_PREFIX = ".";

    /** Suffix applied to per-run temporary work files; distinguishes in-progress writes from published output. */
    private static final String TEMP_SUFFIX = ".inprogress";

    /**
     * Owner read/write only ({@code rw-------}) for the temporary work files, applied at creation on
     * POSIX filesystems so an in-progress statement artifact is never group/world readable. Non-POSIX
     * filesystems (which do not support these attributes) fall back to platform defaults.
     */
    private static final Set<PosixFilePermission> OWNER_ONLY_PERMISSIONS =
            EnumSet.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE);

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

    /** Resolved final text-file path for the current step (the atomic-publish target). */
    private Path textPath;

    /** Resolved final HTML-file path for the current step (the atomic-publish target). */
    private Path htmlPath;

    /** Temporary text work-file written during the step; atomically published onto {@link #textPath} on success. */
    private Path textTempPath;

    /** Temporary HTML work-file written during the step; atomically published onto {@link #htmlPath} on success. */
    private Path htmlTempPath;

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
     * Opens the step's two <em>temporary</em> work files, reproducing CBSTM03A L293
     * {@code OPEN OUTPUT STMT-FILE HTML-FILE}.
     *
     * <p>All per-run state is reset first (this is a singleton bean re-used across job executions).
     * The output directory is created if absent, then a unique temporary work file is created for each
     * output file <em>in that same directory</em> (so the later publish move stays on one filesystem and
     * can be atomic). Each temporary file is created owner-only ({@code rw-------}) where the filesystem
     * supports POSIX permissions, then opened with the {@link #OUTPUT_CHARSET ISO-8859-1} charset. The
     * final output paths are <strong>not</strong> touched here — they are written only at publish time in
     * {@link #afterStep(StepExecution)}, and only on success — so a failure mid-step cannot leave a
     * truncated file at a final path (CWE-459). The text work file is created first, then the HTML work
     * file, mirroring the operand order of the COBOL {@code OPEN}. An empty run still yields two valid,
     * empty temporary files, which publish to two valid, empty final files.</p>
     *
     * <p>{@link StepExecutionListener#beforeStep(StepExecution)} cannot declare a checked exception, so
     * an {@link IOException} while creating the directory, creating a temporary file, or opening either
     * stream is rethrown as an (unchecked) {@link FileStatusException} after best-effort cleanup of any
     * temporary file already created. This fails the step and job fast — the batch return-code-8 analog
     * of the COBOL open-failure abend. Opening is a hard prerequisite; the writer never silently
     * continues without both streams.</p>
     *
     * @param stepExecution the current step execution (supplied by Spring Batch); not otherwise used
     * @throws FileStatusException if the output directory cannot be created or either work file cannot be
     *                             created or opened for writing
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
        this.textTempPath = null;
        this.htmlTempPath = null;

        final Path outputDir = Path.of(outputDirectory);
        this.textPath = outputDir.resolve(textFileName);
        this.htmlPath = outputDir.resolve(htmlFileName);
        try {
            // Idempotent: creates the directory tree if it does not already exist.
            Files.createDirectories(outputDir);
            // Create unique per-run temporary work files in the output directory (same filesystem as the
            // final paths, so the publish move can be atomic). Owner-only where POSIX is supported.
            this.textTempPath = createTempWorkFile(outputDir, textFileName);
            this.htmlTempPath = createTempWorkFile(outputDir, htmlFileName);
            // OPEN OUTPUT STMT-FILE HTML-FILE (L293): text first, then HTML. Truncate the freshly created
            // (empty) work files so an empty run still yields a valid, empty file to publish. Both writers
            // use a SUBSTITUTING ISO-8859-1 encoder (see newSubstitutingWriter / decision log D56) so a
            // single un-encodable character degrades to one replacement byte instead of aborting the whole
            // job (RC 8) and losing statement output for every account.
            this.textWriter = newSubstitutingWriter(textTempPath);
            this.htmlWriter = newSubstitutingWriter(htmlTempPath);
        } catch (IOException ex) {
            this.ioError = true;
            // Best-effort cleanup: the text stream/temp may exist before the HTML open failed. Never
            // leave an orphaned temporary work file behind.
            closeQuietly(this.textWriter);
            this.textWriter = null;
            this.htmlWriter = null;
            deleteQuietly(this.textTempPath);
            deleteQuietly(this.htmlTempPath);
            this.textTempPath = null;
            this.htmlTempPath = null;
            // Fail fast (batch RC 8), mirroring the COBOL open-failure abend. Paths carry no PII.
            throw new FileStatusException(FileStatusException.STATUS_OK,
                    "Error opening statement output files (STMTFILE=" + textPath
                            + ", HTMLFILE=" + htmlPath + ")", ex);
        }
        log.info("Statement output files opened for writing (temporary work files): "
                + "STMTFILE={} ({}-byte records), HTMLFILE={} ({}-byte records), charset=ISO-8859-1.",
                textPath, TEXT_RECORD_LENGTH, htmlPath, HTML_RECORD_LENGTH);
    }

    /**
     * Creates a unique, owner-only temporary work file in {@code outputDir} for the given final file
     * name. On POSIX filesystems the file is created with {@code rw-------} permissions atomically at
     * creation; on filesystems that do not support POSIX permissions the file is created with platform
     * defaults (the atomic-publish guarantee is unaffected).
     *
     * @param outputDir    the directory that also holds the final output file (same filesystem)
     * @param finalName    the final output file name, used as the temporary file's prefix for legibility
     * @return the path to the newly created temporary work file
     * @throws IOException if the temporary file cannot be created
     */
    private static Path createTempWorkFile(Path outputDir, String finalName) throws IOException {
        final String prefix = finalName + TEMP_PREFIX;
        final boolean posix = FileSystems.getDefault().supportedFileAttributeViews().contains("posix");
        if (posix) {
            final FileAttribute<Set<PosixFilePermission>> ownerOnly =
                    PosixFilePermissions.asFileAttribute(OWNER_ONLY_PERMISSIONS);
            return Files.createTempFile(outputDir, prefix, TEMP_SUFFIX, ownerOnly);
        }
        return Files.createTempFile(outputDir, prefix, TEMP_SUFFIX, new FileAttribute<?>[0]);
    }

    /**
     * Opens a buffered writer over {@code path} using a <strong>substituting</strong>
     * {@link #OUTPUT_CHARSET ISO-8859-1} encoder: any character not representable in ISO-8859-1 (code
     * point &gt; {@code 0xFF}) and any malformed input is replaced with the charset replacement byte
     * ({@code '?'}) rather than throwing {@code UnmappableCharacterException}/{@code MalformedInputException}.
     * This mirrors the sibling {@code DailyTransactionPostingWriter} (decision log D35): a single
     * un-encodable character in a name or transaction description degrades to one replacement instead of
     * aborting the entire statement job (batch RC 8) and producing ZERO output for every account. The
     * default {@link Files#newBufferedWriter(Path, Charset, java.nio.file.OpenOption...)} encoder
     * <em>reports</em> (throws) on such input &mdash; the defect this replaces. See decision log D56.
     *
     * <p>The freshly-created (empty) temporary work file is truncated on open so an empty run still
     * yields a valid, empty file to publish. Byte-level record framing is additionally guaranteed by
     * {@link #toLatin1Record(String)} at write time (the statement files carry <em>no</em> in-band
     * delimiter, so a supplementary code point that the encoder would collapse from two chars to one
     * byte must be replaced char-for-char instead &mdash; see that method and D56).</p>
     *
     * @param path the temporary work file to open for writing
     * @return a buffered writer backed by the substituting encoder
     * @throws IOException if the file cannot be opened for writing
     */
    private static BufferedWriter newSubstitutingWriter(Path path) throws IOException {
        CharsetEncoder encoder = OUTPUT_CHARSET.newEncoder()
                .onUnmappableCharacter(CodingErrorAction.REPLACE)
                .onMalformedInput(CodingErrorAction.REPLACE);
        OutputStream out = Files.newOutputStream(path,
                StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
        return new BufferedWriter(new OutputStreamWriter(out, encoder));
    }

    /**
     * Maps a fixed-width record string to a byte-framing-safe ISO-8859-1 form by replacing every
     * character that is not representable in a single ISO-8859-1 byte (code point &gt; {@code 0xFF},
     * which includes both non-Latin-1 BMP characters and each half of a surrogate pair) with the
     * replacement character {@code '?'}. Latin-1 characters ({@code 0x00}-{@code 0xFF}, e.g. accented
     * letters) pass through unchanged.
     *
     * <p><strong>Why this is needed for the statement files specifically.</strong> The statement text
     * and HTML files are pure {@code RECFM=FB} images with <em>no</em> in-band delimiter (decision log
     * D41), so a record of <var>N</var> characters must serialise to exactly <var>N</var> bytes or every
     * following record is mis-framed. The substituting {@link #newSubstitutingWriter(Path) encoder}
     * alone does not guarantee this: it collapses a valid surrogate <em>pair</em> (one supplementary
     * code point, e.g. an emoji &mdash; two Java {@code char}s) into a <em>single</em> replacement byte,
     * which would shorten the record by one byte. Replacing each offending {@code char} individually
     * here keeps the char-count and byte-count equal (a two-char emoji becomes {@code "??"} &mdash; two
     * bytes), preserving the fixed-block framing. This differs from the sibling reject writer (D35),
     * whose records are newline-<em>delimited</em> and therefore self-re-synchronising.</p>
     *
     * <p>This is a strict no-op for the ASCII/Latin-1 data of the golden fixtures and the real seed and
     * transaction pipeline (every character is already {@code <= 0xFF}), so byte-exact golden parity is
     * unaffected. See decision log D56.</p>
     *
     * @param record the exact-width record produced by {@link FixedWidthCodec#writeAlphanumeric(String, int)}
     * @return an equal-length string in which every char is representable as one ISO-8859-1 byte
     */
    private static String toLatin1Record(String record) {
        int index = 0;
        final int length = record.length();
        // Fast path: scan for the first offending char; if none, return the input unchanged (no alloc).
        while (index < length && record.charAt(index) <= 0xFF) {
            index++;
        }
        if (index == length) {
            return record;
        }
        char[] chars = record.toCharArray();
        for (int i = index; i < length; i++) {
            if (chars[i] > 0xFF) {
                chars[i] = '?';
            }
        }
        return new String(chars);
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
     * left-justify, right space-fill, right truncate) and written back-to-back with <strong>no in-band
     * delimiter</strong> — reproducing the {@code RECFM=FB} fixed-block contract where record boundaries
     * are implied by the fixed {@value #TEXT_RECORD_LENGTH}-byte length alone.
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
            // FixedWidthCodec guarantees a String of exactly TEXT_RECORD_LENGTH characters. No delimiter
            // is written: fixed-length records are self-delimiting (RECFM=FB). toLatin1Record keeps the
            // char-count == byte-count (a supplementary code point becomes two '?' bytes, not one),
            // preserving the fixed-block framing of this delimiter-less file (D56).
            textWriter.write(toLatin1Record(FixedWidthCodec.writeAlphanumeric(line, TEXT_RECORD_LENGTH)));
            textRecordCount++;
        }
    }

    /**
     * Writes one document's HTML lines as {@value #HTML_RECORD_LENGTH}-byte records
     * ({@code WRITE FD-HTMLFILE-REC}). Each line is normalised through
     * {@link FixedWidthCodec#writeAlphanumeric(String, int)} (COBOL {@code PIC X(100)} MOVE semantics:
     * left-justify, right space-fill, right truncate) and written back-to-back with <strong>no in-band
     * delimiter</strong> — reproducing the {@code RECFM=FB} fixed-block contract where record boundaries
     * are implied by the fixed {@value #HTML_RECORD_LENGTH}-byte length alone.
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
            // FixedWidthCodec guarantees a String of exactly HTML_RECORD_LENGTH characters. No delimiter
            // is written: fixed-length records are self-delimiting (RECFM=FB). toLatin1Record keeps the
            // char-count == byte-count (a supplementary code point becomes two '?' bytes, not one),
            // preserving the fixed-block framing of this delimiter-less file (D56). On the HTML path the
            // processor already emits non-ASCII as numeric character references, so this is defensive.
            htmlWriter.write(toLatin1Record(FixedWidthCodec.writeAlphanumeric(line, HTML_RECORD_LENGTH)));
            htmlRecordCount++;
        }
    }

    /**
     * Closes both temporary work files, atomically publishes them onto the final paths when the step
     * succeeded, and maps the step outcome to a batch return code — reproducing CBSTM03A L339
     * {@code CLOSE STMT-FILE HTML-FILE} while adding the atomic-publish integrity guarantee.
     *
     * <p>Both streams are flushed and closed best-effort (text first, then HTML), each guarded against
     * a {@code null} reference in case {@link #beforeStep(StepExecution)} failed before opening one; a
     * flush/close failure sets the sticky {@link #ioError} flag (RC 8) and is logged without content.
     * The three record counters are promoted to the step execution context for observability and
     * restart traceability, and a single INFO summary of the counts and file paths is logged.</p>
     *
     * <p><strong>Publication.</strong> Because statement generation has no reject path, the outcome is
     * only success or failure. When there was no I/O error and the step did not fail, each temporary
     * work file is atomically published onto its final path (text first, then HTML) via
     * {@link #publishAtomically(Path, Path)}; a fully written file therefore appears at a final path in
     * a single move, never as a partial write. If either publish fails, the sticky {@link #ioError} flag
     * is set. When the step failed (or publication failed), the temporary work files are deleted and the
     * final paths are left untouched, so no partial or stale artifact is published (CWE-459).</p>
     * <ul>
     *   <li>{@link #ioError} (including a publish failure) or a {@link BatchStatus#FAILED} step &rarr;
     *       {@link ExitStatus#FAILED} (RC 8); temporary files are removed and final paths untouched;</li>
     *   <li>otherwise &rarr; {@link ExitStatus#COMPLETED} (RC 0) with both final files published.</li>
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

        // Fail-CLOSED publish gate (QA finding F-P5-E): publish BOTH files only on a positively-
        // verified clean completion (status COMPLETED, no failure exceptions, no I/O error). On the
        // mid-write connection-loss race the status is still STARTED at afterStep time, so the previous
        // negative "status != FAILED" gate was fail-open and could publish a partial statement file.
        // See BatchFilePublishDecision.
        final boolean succeeded = BatchFilePublishDecision.isCleanCompletion(stepExecution, ioError);
        if (succeeded) {
            try {
                // Atomic publish (text first, then HTML): a reader sees either the fully written new
                // file or the untouched previous file, never a partial one.
                publishAtomically(textTempPath, textPath);
                publishAtomically(htmlTempPath, htmlPath);
            } catch (IOException ex) {
                ioError = true;
                // File names are non-sensitive configuration; statement content is never logged.
                log.error("Error publishing statement output files (STMTFILE={}, HTMLFILE={}); "
                        + "final outputs were not published.", textPath, htmlPath, ex);
            }
        }
        // On failure (including a publish failure), delete any temporary work file that was not
        // consumed by a successful move, leaving the final paths untouched.
        if (!succeeded || ioError) {
            deleteQuietly(textTempPath);
            deleteQuietly(htmlTempPath);
        }
        this.textTempPath = null;
        this.htmlTempPath = null;

        // Single INFO summary — counts and file paths only (statement content is PII, never logged).
        log.info("Statement writer finished: {} document(s); {} text record(s) (STMTFILE={}); "
                + "{} HTML record(s) (HTMLFILE={}).",
                documentCount, textRecordCount, textPath, htmlRecordCount, htmlPath);

        // Return-code mapping: statements have NO reject path. A non-clean run (F-P5-E: the
        // connection-loss race, an explicit FAILED, or a publish failure that set ioError) -> RC 8;
        // a verified-clean completion -> RC 0.
        if (!succeeded || ioError) {
            return ExitStatus.FAILED;
        }
        return ExitStatus.COMPLETED;
    }

    /**
     * Atomically publishes a fully written temporary work file onto its final path. The move is
     * attempted with {@link StandardCopyOption#ATOMIC_MOVE} (also replacing any existing final file);
     * on filesystems that cannot move atomically it falls back to a replacing move, which is still a
     * single publish step (no partial content is ever visible at the final path because the temporary
     * file was fully written and closed before this call).
     *
     * @param tempPath  the fully written temporary work file; if {@code null} nothing is published
     * @param finalPath the final destination path
     * @throws IOException if the file cannot be moved onto the final path
     */
    private static void publishAtomically(Path tempPath, Path finalPath) throws IOException {
        if (tempPath == null) {
            return;
        }
        try {
            Files.move(tempPath, finalPath,
                    StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException ex) {
            // Filesystem cannot move atomically; fall back to a single replacing move. The temporary
            // file is already fully written, so the final path still never observes a partial write.
            Files.move(tempPath, finalPath, StandardCopyOption.REPLACE_EXISTING);
        }
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

    /**
     * Deletes a temporary work file best-effort, suppressing (but logging) any {@link IOException}.
     * Used to remove an unpublished temporary artifact after an open failure, a step failure, or a
     * publish failure, so no orphaned in-progress file is ever left behind.
     *
     * @param path the temporary work file to delete; a {@code null} value is ignored
     */
    private static void deleteQuietly(Path path) {
        if (path == null) {
            return;
        }
        try {
            Files.deleteIfExists(path);
        } catch (IOException ex) {
            log.warn("Suppressed I/O error while deleting a temporary statement work file during "
                    + "cleanup.", ex);
        }
    }
}
