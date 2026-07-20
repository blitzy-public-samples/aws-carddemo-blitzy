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

import java.io.IOException;
import java.io.UncheckedIOException;
import java.io.Writer;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.EnumSet;

import com.aws.carddemo.common.util.FixedWidthCodec;
import com.aws.carddemo.common.util.FixedWidthCodec.FieldDef;
import com.aws.carddemo.common.util.FixedWidthCodec.RecordBuilder;
import com.aws.carddemo.domain.Transaction;
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
 * Spring Batch {@link ItemWriter} that reproduces the <em>REPRO export</em> of the legacy
 * mainframe transaction-backup job by serializing every {@link Transaction} row to a byte-exact
 * 350-byte fixed-width record in a timestamped backup file.
 *
 * <h2>COBOL / JCL lineage</h2>
 * <p>This writer is the relational re-platform of the batch chain
 * {@code legacy/jcl/TRANBKP.jcl} &rarr; {@code legacy/proc/REPROC.prc} &rarr;
 * {@code legacy/ctl/REPROCT.ctl} (source {@code app/jcl/TRANBKP.jcl}, {@code app/proc/REPROC.prc},
 * {@code app/ctl/REPROCT.ctl}):</p>
 * <ul>
 *   <li>{@code TRANBKP.jcl} step {@code STEP05R} invokes {@code PROC=REPROC};</li>
 *   <li>{@code REPROC.prc} runs {@code PGM=IDCAMS} with {@code SYSIN} bound to the control member
 *       {@code REPROCT};</li>
 *   <li>{@code REPROCT.ctl} contains the single command {@code REPRO INFILE(FILEIN)
 *       OUTFILE(FILEOUT)}, which copies the key-sequenced dataset
 *       {@code AWS.M2.CARDDEMO.TRANSACT.VSAM.KSDS} into a brand-new generation-data-group member
 *       {@code AWS.M2.CARDDEMO.TRANSACT.BKUP(+1)} declared {@code DCB=(LRECL=350,RECFM=FB)}.</li>
 * </ul>
 *
 * <p>In the relational target the upstream reader configured in
 * {@code batch/TransactionBackupJob.transactionBackupStep} (a
 * {@code RepositoryItemReader}/{@code JpaPagingItemReader} ordered by {@code tranId} ascending)
 * streams the entire {@code transaction} table; this writer serializes each streamed
 * {@link Transaction} to the 350-byte record image defined by copybook
 * {@code legacy/cpy/CVTRA05Y.cpy} ({@code TRAN-RECORD}, {@code RECLN = 350}). The <em>unique-per-run
 * output filename</em> ({@code file-prefix + yyyyMMddHHmmssSSS + "." + jobExecutionId +
 * "-" + stepExecutionId}) is the relational analog of the GDG {@code (+1)} "new generation": each run
 * writes a fresh, immutable backup file rather than overwriting the previous one. The millisecond
 * timestamp keeps names human-readable and time-ordered, while the {@code JobRepository}-assigned
 * execution-id suffix guarantees uniqueness even across two separate JVM processes launched within the
 * same wall-clock second (they share the same database sequence), so a concurrent backup is never
 * silently lost (QA finding F7; see {@link #uniqueRunToken(StepExecution)}).</p>
 *
 * <h2>Documented deviation &mdash; VSAM storage management is NOT replicated</h2>
 * <p>Beyond the REPRO export, {@code TRANBKP.jcl} also runs two IDCAMS storage-management steps:
 * {@code STEP05} ({@code DELETE ... CLUSTER} and {@code DELETE ... ALTERNATEINDEX}) drops the VSAM
 * cluster and its alternate index, and {@code STEP10} ({@code DEFINE CLUSTER ...}) recreates them.
 * Those steps exist only to reclaim and re-initialize VSAM storage on the mainframe. They are
 * <strong>intentionally not replicated here</strong>: a PostgreSQL backup export never drops or
 * recreates the {@code transaction} table. This is a deliberate, documented deviation recorded in
 * {@code docs/decision-log.md} (GDG/VSAM backup semantics &rarr; scheduled database export;
 * {@code DELETE}/{@code DEFINE} not carried over). This class only references that decision; it does
 * not own or edit the decision log.</p>
 *
 * <h2>350-byte record layout (copybook {@code CVTRA05Y.cpy})</h2>
 * <p>Each record is assembled with {@link FixedWidthCodec} using the exact zero-based offsets below;
 * the field descriptors are declared as {@code private static final} {@link FieldDef} constants on
 * this class. A {@link RecordBuilder} of length {@value #RECORD_LENGTH} starts as all spaces, so the
 * trailing {@code FILLER PIC X(20)} at offset {@code 330} remains spaces with no explicit write.</p>
 * <ul>
 *   <li>{@code TRAN-ID PIC X(16)} &mdash; offset 0</li>
 *   <li>{@code TRAN-TYPE-CD PIC X(02)} &mdash; offset 16</li>
 *   <li>{@code TRAN-CAT-CD PIC 9(04)} &mdash; offset 18</li>
 *   <li>{@code TRAN-SOURCE PIC X(10)} &mdash; offset 22</li>
 *   <li>{@code TRAN-DESC PIC X(100)} &mdash; offset 32</li>
 *   <li>{@code TRAN-AMT PIC S9(09)V99} &mdash; offset 132, length 11, scale 2 (overpunch-signed)</li>
 *   <li>{@code TRAN-MERCHANT-ID PIC 9(09)} &mdash; offset 143</li>
 *   <li>{@code TRAN-MERCHANT-NAME PIC X(50)} &mdash; offset 152</li>
 *   <li>{@code TRAN-MERCHANT-CITY PIC X(50)} &mdash; offset 202</li>
 *   <li>{@code TRAN-MERCHANT-ZIP PIC X(10)} &mdash; offset 252</li>
 *   <li>{@code TRAN-CARD-NUM PIC X(16)} &mdash; offset 262</li>
 *   <li>{@code TRAN-ORIG-TS PIC X(26)} &mdash; offset 278</li>
 *   <li>{@code TRAN-PROC-TS PIC X(26)} &mdash; offset 304</li>
 *   <li>{@code FILLER PIC X(20)} &mdash; offset 330 (spaces)</li>
 * </ul>
 *
 * <h2>Monetary parity</h2>
 * <p>{@code TRAN-AMT} is encoded through {@link FixedWidthCodec#writeSignedDecimal(BigDecimal, int,
 * int)} (via the typed {@code RecordBuilder.put}) as a {@code 9(9)V99} zoned-decimal value with the
 * sign carried as an ASCII overpunch on the last digit, at scale 2 with
 * {@link java.math.RoundingMode#HALF_UP}. Monetary values are always {@link BigDecimal}; binary
 * floating point ({@code double}/{@code float}) is never used.</p>
 *
 * <h2>Byte-exactness and record delimiter</h2>
 * <p>The output file is opened with {@link StandardCharsets#ISO_8859_1}, which maps every
 * {@code char} in {@code [0x00, 0xFF]} to exactly one byte. Because all record data &mdash;
 * including the overpunch characters <code>{ } A&ndash;R</code> and the space fill &mdash; is
 * {@code <= 0xFF}, each 350-character record is written as exactly 350 bytes, preserving the
 * {@code LRECL=350 RECFM=FB} contract. A single literal line feed ({@code "\n"}) is appended after
 * each record so the file is line-delimited and re-readable by a line-based
 * {@code FlatFileItemReader} (for example the transaction-combine job). {@code RECFM=FB} has no
 * on-disk delimiter; the added {@code "\n"} is a documented text-file convenience. A literal
 * {@code "\n"} is used rather than {@link System#lineSeparator()} so golden-file fixtures are
 * deterministic across operating systems.</p>
 *
 * <h2>Ordering</h2>
 * <p>This writer performs <strong>no sorting</strong>. The upstream reader supplies rows already in
 * {@code tranId}-ascending order, mirroring an IDCAMS REPRO of a key-sequenced dataset, and this
 * writer preserves that arrival order verbatim.</p>
 *
 * <h2>Lifecycle, state, and restartability</h2>
 * <p>The class implements {@link StepExecutionListener} to bracket the output stream around the
 * step: {@link #beforeStep(StepExecution)} resolves the timestamped path and opens the writer, and
 * {@link #afterStep(StepExecution)} flushes and closes it. The output {@link Writer} and resolved
 * {@link Path} are therefore mutable per-step state. This backup is a <strong>full regeneration on
 * every run</strong> (it mirrors a complete REPRO copy), so it is deliberately <em>not</em>
 * restartable and holds no chunk-level save state: a re-run simply produces a new, complete,
 * freshly named file. Because the output filename embeds the {@code JobRepository}-assigned
 * execution-id suffix (see {@link #uniqueRunToken(StepExecution)}), two <em>separate</em> JVM
 * processes launched within the same wall-clock second write to distinct files rather than colliding
 * on a shared name (QA finding F7). The remaining constraint is purely in-JVM: because the open
 * output {@link Writer} and counters are singleton instance state, a single JVM must not run two of
 * <em>this</em> step concurrently against itself; the batch is scheduled one launch per process, and
 * this in-JVM single-active-step constraint is the one recorded in {@code docs/decision-log.md}
 * (D37).</p>
 *
 * <h2>Configuration</h2>
 * <p>The output location is fully configuration-driven through two properties (safe relative
 * defaults; never a hardcoded absolute path or any credential), which may be declared in
 * {@code application.yml}:</p>
 * <ul>
 *   <li>{@code carddemo.batch.backup.directory} &mdash; output directory (default
 *       {@code ./target/backup});</li>
 *   <li>{@code carddemo.batch.backup.file-prefix} &mdash; filename prefix (default
 *       {@code TRANSACT.BKUP.}); a {@code yyyyMMddHHmmssSSS} millisecond timestamp and a
 *       {@code .jobExecutionId-stepExecutionId} suffix are appended to form the unique-per-run,
 *       generation-style filename.</li>
 * </ul>
 *
 * <h2>Registration and security</h2>
 * <p>The bean's default name is {@code transactionBackupItemWriter}; it is injected by
 * {@code batch/TransactionBackupJob} into {@code transactionBackupStep}. The writer logs only
 * non-sensitive operational metadata (resolved path and record counts); it never logs a full card
 * number, CVV, SSN, password, or any other sensitive field.</p>
 *
 * @see FixedWidthCodec
 * @see Transaction
 * @see ItemWriter
 * @see StepExecutionListener
 * @see <a href="http://www.apache.org/licenses/LICENSE-2.0">Apache License 2.0</a>
 */
@Component
public class TransactionBackupItemWriter
        implements ItemWriter<Transaction>, StepExecutionListener {

    /** SLF4J logger; emits only non-sensitive operational metadata. */
    private static final Logger LOGGER =
            LoggerFactory.getLogger(TransactionBackupItemWriter.class);

    /** Fixed transaction record length in characters/bytes ({@code CVTRA05Y.cpy} {@code RECLN}). */
    private static final int RECORD_LENGTH = 350;

    /**
     * Record delimiter appended after each 350-character record. A literal line feed (never
     * {@link System#lineSeparator()}) keeps golden-file fixtures deterministic across platforms.
     */
    private static final String RECORD_DELIMITER = "\n";

    /**
     * Timestamp pattern that forms the human-readable, sortable portion of the generation-style
     * filename. Millisecond precision ({@code SSS}) is used rather than the original second-only
     * pattern so that two launches within the same wall-clock second still receive distinct,
     * time-ordered names; the guaranteed-unique discriminator, however, is the execution-id suffix
     * appended in {@link #beforeStep(StepExecution)} (see {@link #uniqueRunToken(StepExecution)}).
     */
    private static final DateTimeFormatter TIMESTAMP_FORMAT =
            DateTimeFormatter.ofPattern("yyyyMMddHHmmssSSS");

    /**
     * Suffix of the owner-only staging file that the step writes to before the completed backup is
     * atomically published to its final generation-style name. A hidden {@code .}-prefixed name with
     * this suffix keeps a partially-written backup clearly distinguishable from a published one and
     * ensures no consumer ever observes a final-named partial file (QA finding F-P5-E).
     */
    private static final String PART_SUFFIX = ".part";

    /** Owner read/write only ({@code 0600}) staging-file permissions on POSIX filesystems. */
    private static final EnumSet<PosixFilePermission> OWNER_ONLY_PERMISSIONS =
            EnumSet.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE);

    // ------------------------------------------------------------------------
    // 350-byte TRAN-RECORD field descriptors (copybook CVTRA05Y.cpy).
    // Offsets are zero-based and contiguous; see the class Javadoc for the full table.
    // ------------------------------------------------------------------------

    /** {@code TRAN-ID PIC X(16)} at offset 0. */
    private static final FieldDef TRAN_ID = FieldDef.alphanumeric("TRAN-ID", 0, 16);

    /** {@code TRAN-TYPE-CD PIC X(02)} at offset 16. */
    private static final FieldDef TRAN_TYPE_CD = FieldDef.alphanumeric("TRAN-TYPE-CD", 16, 2);

    /** {@code TRAN-CAT-CD PIC 9(04)} at offset 18. */
    private static final FieldDef TRAN_CAT_CD = FieldDef.numeric("TRAN-CAT-CD", 18, 4);

    /** {@code TRAN-SOURCE PIC X(10)} at offset 22. */
    private static final FieldDef TRAN_SOURCE = FieldDef.alphanumeric("TRAN-SOURCE", 22, 10);

    /** {@code TRAN-DESC PIC X(100)} at offset 32. */
    private static final FieldDef TRAN_DESC = FieldDef.alphanumeric("TRAN-DESC", 32, 100);

    /** {@code TRAN-AMT PIC S9(09)V99} at offset 132 (length 11, scale 2, overpunch-signed). */
    private static final FieldDef TRAN_AMT = FieldDef.signedDecimal("TRAN-AMT", 132, 11, 2);

    /** {@code TRAN-MERCHANT-ID PIC 9(09)} at offset 143. */
    private static final FieldDef TRAN_MERCHANT_ID = FieldDef.numeric("TRAN-MERCHANT-ID", 143, 9);

    /** {@code TRAN-MERCHANT-NAME PIC X(50)} at offset 152. */
    private static final FieldDef TRAN_MERCHANT_NAME =
            FieldDef.alphanumeric("TRAN-MERCHANT-NAME", 152, 50);

    /** {@code TRAN-MERCHANT-CITY PIC X(50)} at offset 202. */
    private static final FieldDef TRAN_MERCHANT_CITY =
            FieldDef.alphanumeric("TRAN-MERCHANT-CITY", 202, 50);

    /** {@code TRAN-MERCHANT-ZIP PIC X(10)} at offset 252. */
    private static final FieldDef TRAN_MERCHANT_ZIP =
            FieldDef.alphanumeric("TRAN-MERCHANT-ZIP", 252, 10);

    /** {@code TRAN-CARD-NUM PIC X(16)} at offset 262. */
    private static final FieldDef TRAN_CARD_NUM = FieldDef.alphanumeric("TRAN-CARD-NUM", 262, 16);

    /** {@code TRAN-ORIG-TS PIC X(26)} at offset 278. */
    private static final FieldDef TRAN_ORIG_TS = FieldDef.alphanumeric("TRAN-ORIG-TS", 278, 26);

    /** {@code TRAN-PROC-TS PIC X(26)} at offset 304. */
    private static final FieldDef TRAN_PROC_TS = FieldDef.alphanumeric("TRAN-PROC-TS", 304, 26);

    /** Configured output directory (safe relative default {@code ./target/backup}). */
    private final String backupDirectory;

    /**
     * Configured filename prefix (default {@code TRANSACT.BKUP.}); the millisecond timestamp and the
     * unique execution-id suffix are appended to it.
     */
    private final String filePrefix;

    /** Open output stream over the staging file for the current step; {@code null} outside an active step. */
    private Writer writer;

    /**
     * Owner-only staging file the step writes to during the run; atomically renamed to
     * {@link #backupFile} on success or deleted on failure. {@code null} outside an active step.
     */
    private Path tempFile;

    /**
     * Final published backup file path for the current step (the generation-style name); the staging
     * file is atomically moved here only after the backup completes cleanly. {@code null} outside an
     * active step.
     */
    private Path backupFile;

    /** Count of records written during the current step, for the completion log line. */
    private long recordsWritten;

    /**
     * Creates the writer with a configuration-driven, relative-by-default output location.
     *
     * <p>Only constructor injection is used; both configuration values are stored in
     * {@code private final} fields. The defaults are deliberately relative
     * ({@code ./target/backup}) so the component is safe to instantiate in any environment without a
     * hardcoded absolute path or credential.</p>
     *
     * @param backupDirectory the directory into which backup files are written; bound from
     *                        {@code carddemo.batch.backup.directory} (default {@code ./target/backup})
     * @param filePrefix      the filename prefix to which the {@code yyyyMMddHHmmssSSS} timestamp and
     *                        the unique {@code .jobExecutionId-stepExecutionId} suffix are appended;
     *                        bound from {@code carddemo.batch.backup.file-prefix} (default
     *                        {@code TRANSACT.BKUP.})
     */
    public TransactionBackupItemWriter(
            @Value("${carddemo.batch.backup.directory:./target/backup}") String backupDirectory,
            @Value("${carddemo.batch.backup.file-prefix:TRANSACT.BKUP.}") String filePrefix) {
        this.backupDirectory = backupDirectory;
        this.filePrefix = filePrefix;
    }

    /**
     * Opens a fresh, unique-per-run backup file for the step and prepares the output stream.
     *
     * <p>The filename is {@code filePrefix + yyyyMMddHHmmssSSS + "." + jobExecutionId + "-" +
     * stepExecutionId} (the GDG {@code (+1)} generation analog), resolved beneath the configured
     * {@link #backupDirectory}. The millisecond timestamp keeps the name human-readable and
     * time-ordered; the {@code JobRepository}-assigned execution-id suffix (see
     * {@link #uniqueRunToken(StepExecution)}) guarantees the name is unique per run, so two separate
     * JVM processes launched in the same wall-clock second no longer collide on one filename (QA
     * finding F7). The parent directory is created if it does not already exist, and the stream is
     * opened with {@link StandardCharsets#ISO_8859_1} so that each 350-character record serializes to
     * exactly 350 bytes. The per-step record counter is reset to zero.</p>
     *
     * <p>{@link StepExecutionListener#beforeStep(StepExecution)} does not permit checked exceptions,
     * so any {@link IOException} raised while creating the directory or opening the file is rethrown
     * as an {@link UncheckedIOException}. That failure aborts the step (and thus the job), which is
     * the batch return-code-8 (I/O abend) analog for a backup that could not even be started.</p>
     *
     * @param stepExecution the current step execution; never {@code null}
     * @throws UncheckedIOException if the backup directory cannot be created or the file cannot be
     *                              opened for writing
     */
    @Override
    public void beforeStep(StepExecution stepExecution) {
        final String timestamp = LocalDateTime.now().format(TIMESTAMP_FORMAT);
        final String uniqueToken = uniqueRunToken(stepExecution);
        final Path target = Path.of(backupDirectory, filePrefix + timestamp + "." + uniqueToken);
        // Hidden, owner-only staging file in the same directory as the target, so the atomic publish
        // is a same-filesystem rename. The final generation-style name is never used for the
        // in-progress write, so no consumer can observe a final-named partial file (F-P5-E).
        final Path temp = Path.of(backupDirectory,
                "." + filePrefix + timestamp + "." + uniqueToken + PART_SUFFIX);
        try {
            final Path parent = target.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            this.writer = openOwnerOnlyStagingWriter(temp);
            this.tempFile = temp;
            this.backupFile = target;
            this.recordsWritten = 0L;
        } catch (IOException ex) {
            // Best-effort cleanup of any partially-created staging file, then fail fast (RC 8 analog).
            deleteQuietly(temp);
            this.writer = null;
            this.tempFile = null;
            this.backupFile = null;
            throw new UncheckedIOException(
                    "Unable to open transaction backup staging file " + temp, ex);
        }
        LOGGER.info("Transaction backup staging opened: {} (350-byte fixed-width records, ISO-8859-1); "
                + "will be atomically published to {} on success.", temp, target);
    }

    /**
     * Creates the owner-only staging file and returns a buffered writer over it. On a POSIX
     * filesystem the file is created with {@code 0600} permissions (owner read/write only) so an
     * in-progress backup is never world-readable; on a non-POSIX filesystem (which does not support
     * POSIX permission attributes) it is created without them. Any pre-existing staging file at the
     * same path (from an aborted prior attempt) is removed first so the fresh run always starts from
     * an empty file.
     *
     * @param temp the staging file path to create and open; never {@code null}
     * @return a buffered {@link Writer} over the freshly created staging file, encoded ISO-8859-1
     * @throws IOException if the staging file cannot be created or opened
     */
    private static Writer openOwnerOnlyStagingWriter(final Path temp) throws IOException {
        Files.deleteIfExists(temp);
        try {
            Files.createFile(temp, PosixFilePermissions.asFileAttribute(OWNER_ONLY_PERMISSIONS));
        } catch (UnsupportedOperationException nonPosixFileSystem) {
            // Non-POSIX filesystem (e.g. Windows): create without POSIX permission attributes.
            Files.createFile(temp);
        }
        return Files.newBufferedWriter(temp, StandardCharsets.ISO_8859_1,
                StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING);
    }

    /**
     * Derives a guaranteed-unique-per-run filename discriminator from the step execution.
     *
     * <p>The suffix is {@code jobExecutionId + "-" + stepExecutionId}. Both identifiers are assigned
     * by the shared Spring Batch {@code JobRepository} (the PostgreSQL {@code BATCH_JOB_EXECUTION_SEQ}
     * / {@code BATCH_STEP_EXECUTION_SEQ} sequences) <em>before</em> this listener runs, so they are
     * monotonically increasing and unique across <strong>every</strong> execution recorded in that
     * repository — including two launches from separate JVM processes that share the same database.
     * Appending this token therefore eliminates the cross-process, same-wall-clock-second filename
     * collision that a second-granularity timestamp alone could produce (QA finding F7): each run
     * writes to a distinct file and no backup is silently overwritten. It is also the faithful
     * relational analog of the GDG {@code (+1)} "new generation" number, which the mainframe likewise
     * assigns uniquely per run.</p>
     *
     * <p>In a normally launched step both identifiers are always present. As a defensive fallback for
     * an unpersisted {@link StepExecution} (not produced by the framework during a real launch), a
     * high-resolution {@link System#nanoTime()} token is used so the filename remains unique.</p>
     *
     * @param stepExecution the current step execution; never {@code null}
     * @return a non-blank filename discriminator that is unique per run
     */
    private static String uniqueRunToken(final StepExecution stepExecution) {
        final Long jobExecutionId = stepExecution.getJobExecutionId();
        final Long stepExecutionId = stepExecution.getId();
        if (jobExecutionId != null && stepExecutionId != null) {
            return jobExecutionId + "-" + stepExecutionId;
        }
        return "run" + System.nanoTime();
    }

    /**
     * Serializes each {@link Transaction} in the chunk to a 350-byte fixed-width record followed by
     * a single line feed.
     *
     * <p>Records are written in the exact order the reader supplies them ({@code tranId} ascending);
     * this writer never reorders. Any {@link IOException} raised by the underlying stream propagates
     * out unchanged (the method is declared {@code throws Exception}), failing the step so the batch
     * reports the I/O-error return code rather than silently producing a truncated backup.</p>
     *
     * @param chunk the chunk of transactions to serialize; never {@code null}
     * @throws Exception               if writing to the underlying stream fails
     * @throws IllegalStateException   if invoked before {@link #beforeStep(StepExecution)} has opened
     *                                 the output stream
     */
    @Override
    public void write(Chunk<? extends Transaction> chunk) throws Exception {
        final Writer target = this.writer;
        if (target == null) {
            throw new IllegalStateException(
                    "Transaction backup writer is not open; beforeStep(StepExecution) must run "
                            + "before write(Chunk).");
        }
        for (final Transaction transaction : chunk) {
            target.write(toFixedWidthRecord(transaction));
            target.write(RECORD_DELIMITER);
            this.recordsWritten++;
        }
    }

    /**
     * Assembles the byte-exact 350-character record image for a single transaction.
     *
     * <p>A {@link RecordBuilder} of length {@value #RECORD_LENGTH} is initialized to all spaces, so
     * the trailing {@code FILLER PIC X(20)} at offset 330 remains spaces automatically. Null-safety
     * mirrors the COBOL fixed-width semantics: null numeric fields ({@code catCd},
     * {@code tranMerchantId}) are written as zero, null alphanumeric fields are space-filled by the
     * codec, and a null monetary amount is defensively encoded as {@code 0.00} (with a WARN log,
     * since a posted transaction should always carry an amount).</p>
     *
     * @param transaction the transaction to serialize; never {@code null}
     * @return a string of exactly {@value #RECORD_LENGTH} characters
     */
    private String toFixedWidthRecord(final Transaction transaction) {
        BigDecimal amount = transaction.getTranAmt();
        if (amount == null) {
            LOGGER.warn("Transaction {} has a null amount; encoding 0.00 (data anomaly).",
                    transaction.getTranId());
            amount = BigDecimal.ZERO;
        }

        final Integer catCd = transaction.getCatCd();
        final Long merchantId = transaction.getTranMerchantId();

        final RecordBuilder record = FixedWidthCodec.of(RECORD_LENGTH)
                .put(TRAN_ID, transaction.getTranId())
                .put(TRAN_TYPE_CD, transaction.getTypeCd())
                .put(TRAN_CAT_CD, catCd == null ? 0L : catCd.longValue())
                .put(TRAN_SOURCE, transaction.getTranSource())
                .put(TRAN_DESC, transaction.getTranDesc())
                .put(TRAN_AMT, amount)
                .put(TRAN_MERCHANT_ID, merchantId == null ? 0L : merchantId.longValue())
                .put(TRAN_MERCHANT_NAME, transaction.getTranMerchantName())
                .put(TRAN_MERCHANT_CITY, transaction.getTranMerchantCity())
                .put(TRAN_MERCHANT_ZIP, transaction.getTranMerchantZip())
                .put(TRAN_CARD_NUM, transaction.getCardNum())
                .put(TRAN_ORIG_TS, transaction.getOrigTs())
                .put(TRAN_PROC_TS, transaction.getProcTs());

        return record.build();
    }

    /**
     * Finalizes the backup at the end of the step by flushing and closing the staging stream and then
     * either <strong>atomically publishing</strong> the completed staging file to its final
     * generation-style name (on success) or <strong>deleting</strong> it (on any failure), so a
     * consumer never observes a final-named partial file (QA finding F-P5-E).
     *
     * <p>The publish is gated <strong>fail-closed</strong> on a positively-verified clean completion
     * ({@link BatchFilePublishDecision#isCleanCompletion}): the staging file is renamed to its final
     * name only when the step status is {@link BatchStatus#COMPLETED}, no failure exception was
     * recorded, and the stream flushed/closed without I/O error. Every other outcome discards the
     * staging file and returns {@link ExitStatus#FAILED} (the return-code-8 analog) &mdash; including
     * an explicit {@code FAILED} status and, critically, the mid-write connection-loss race in which
     * the reader threw because the database connection was terminated: in that race the durable
     * {@code FAILED} status is persisted only <em>after</em> {@code afterStep} (and then fails on the
     * dead connection), so at this point the in-memory status is still {@code STARTED}; a negative
     * {@code != FAILED} gate would have wrongly published the partial file. On a clean run the atomic
     * move (falling back to a same-directory rename only where the filesystem cannot perform an atomic
     * move) publishes the final file and the step's existing {@link ExitStatus} is returned unchanged
     * so a clean run maps to return code 0. All per-step state is cleared in a {@code finally} block
     * so the singleton bean carries no state between runs.</p>
     *
     * @param stepExecution the current step execution; never {@code null}
     * @return {@link ExitStatus#FAILED} if the step failed or the backup could not be closed and
     *         published cleanly, otherwise the unchanged {@link StepExecution#getExitStatus() step
     *         exit status}
     */
    @Override
    public ExitStatus afterStep(StepExecution stepExecution) {
        final Writer target = this.writer;
        final Path temp = this.tempFile;
        final Path published = this.backupFile;
        boolean ioError = false;
        try {
            if (target != null) {
                try {
                    target.flush();
                    target.close();
                } catch (IOException ex) {
                    LOGGER.error("Failed to flush/close transaction backup staging file {}", temp, ex);
                    ioError = true;
                }
            }
            // Fail-CLOSED publish gate (QA finding F-P5-E): publish ONLY on a positively-verified
            // clean completion (step status COMPLETED, no failure exceptions, no I/O error). On the
            // mid-write connection-loss race the step status is still STARTED at afterStep time
            // (FAILED is persisted afterwards and itself fails on the dead DB), so the previous
            // negative "status == FAILED" gate was fail-open and wrongly renamed the partial staging
            // file to its final name. See BatchFilePublishDecision.
            final boolean clean = BatchFilePublishDecision.isCleanCompletion(stepExecution, ioError);
            if (temp == null) {
                // beforeStep never opened a staging file; nothing to publish or discard.
                return clean ? stepExecution.getExitStatus() : ExitStatus.FAILED;
            }
            if (!clean) {
                deleteQuietly(temp);
                LOGGER.error("Transaction backup did not complete cleanly (status={}); staging file {} "
                        + "discarded and no backup was published (no partial final-named file left "
                        + "behind).", stepExecution.getStatus(), temp);
                return ExitStatus.FAILED;
            }
            try {
                publishAtomically(temp, published);
            } catch (IOException ex) {
                LOGGER.error("Failed to publish transaction backup {} -> {}", temp, published, ex);
                deleteQuietly(temp);
                return ExitStatus.FAILED;
            }
            LOGGER.info("Transaction backup published: {} ({} record(s) written).",
                    published, this.recordsWritten);
            return stepExecution.getExitStatus();
        } finally {
            this.writer = null;
            this.tempFile = null;
            this.backupFile = null;
        }
    }

    /**
     * Publishes the completed staging file to its final path with an atomic move. On POSIX this is a
     * single {@code rename(2)}, so a consumer sees either the previous state or the fully-written
     * backup, never a partial file. If the filesystem cannot perform an atomic move a plain
     * same-directory rename is used as the closest available fallback; the final generation-style
     * name is unique per run, so no pre-existing file is ever replaced.
     *
     * @param temp      the completed staging file; never {@code null}
     * @param published the final destination path; never {@code null}
     * @throws IOException if the move fails
     */
    private static void publishAtomically(final Path temp, final Path published) throws IOException {
        try {
            Files.move(temp, published, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException atomicUnsupported) {
            Files.move(temp, published);
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
            LOGGER.warn("Unable to delete transaction backup staging file {}: {}",
                    path, ex.getMessage());
        }
    }
}
