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
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

import com.aws.carddemo.common.util.FixedWidthCodec;
import com.aws.carddemo.common.util.FixedWidthCodec.FieldDef;
import com.aws.carddemo.common.util.FixedWidthCodec.RecordBuilder;
import com.aws.carddemo.domain.Transaction;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
 * {@code legacy/cpy/CVTRA05Y.cpy} ({@code TRAN-RECORD}, {@code RECLN = 350}). The <em>timestamped
 * output filename</em> ({@code file-prefix + yyyyMMddHHmmss}) is the relational analog of the GDG
 * {@code (+1)} "new generation": each run writes a fresh, immutable backup file rather than
 * overwriting the previous one.</p>
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
 * freshly timestamped file. As a singleton {@link Component} it assumes a single active execution of
 * its step at a time (the batch is scheduled, not run concurrently against itself).</p>
 *
 * <h2>Configuration</h2>
 * <p>The output location is fully configuration-driven through two properties (safe relative
 * defaults; never a hardcoded absolute path or any credential), which may be declared in
 * {@code application.yml}:</p>
 * <ul>
 *   <li>{@code carddemo.batch.backup.directory} &mdash; output directory (default
 *       {@code ./target/backup});</li>
 *   <li>{@code carddemo.batch.backup.file-prefix} &mdash; filename prefix (default
 *       {@code TRANSACT.BKUP.}); the {@code yyyyMMddHHmmss} timestamp is appended to form the
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
     * Timestamp pattern that forms the generation-style filename suffix; the relational analog of a
     * GDG {@code (+1)} generation.
     */
    private static final DateTimeFormatter TIMESTAMP_FORMAT =
            DateTimeFormatter.ofPattern("yyyyMMddHHmmss");

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

    /** Configured filename prefix (default {@code TRANSACT.BKUP.}); the timestamp is appended. */
    private final String filePrefix;

    /** Open output stream for the current step; {@code null} outside an active step. */
    private Writer writer;

    /** Resolved backup file path for the current step; {@code null} outside an active step. */
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
     * @param filePrefix      the filename prefix to which the {@code yyyyMMddHHmmss} timestamp is
     *                        appended; bound from {@code carddemo.batch.backup.file-prefix} (default
     *                        {@code TRANSACT.BKUP.})
     */
    public TransactionBackupItemWriter(
            @Value("${carddemo.batch.backup.directory:./target/backup}") String backupDirectory,
            @Value("${carddemo.batch.backup.file-prefix:TRANSACT.BKUP.}") String filePrefix) {
        this.backupDirectory = backupDirectory;
        this.filePrefix = filePrefix;
    }

    /**
     * Opens a fresh, timestamped backup file for the step and prepares the output stream.
     *
     * <p>The filename is {@code filePrefix + yyyyMMddHHmmss} (the GDG {@code (+1)} generation
     * analog), resolved beneath the configured {@link #backupDirectory}. The parent directory is
     * created if it does not already exist, and the stream is opened with
     * {@link StandardCharsets#ISO_8859_1} so that each 350-character record serializes to exactly
     * 350 bytes. The per-step record counter is reset to zero.</p>
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
        final Path target = Path.of(backupDirectory, filePrefix + timestamp);
        try {
            final Path parent = target.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            this.writer = Files.newBufferedWriter(target, StandardCharsets.ISO_8859_1);
            this.backupFile = target;
            this.recordsWritten = 0L;
        } catch (IOException ex) {
            throw new UncheckedIOException(
                    "Unable to open transaction backup file " + target, ex);
        }
        LOGGER.info("Transaction backup opened: {} (350-byte fixed-width records, ISO-8859-1).",
                target);
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
     * Flushes and closes the backup stream at the end of the step.
     *
     * <p>The flush/close runs inside a {@code try/finally} that always clears the per-step state,
     * guarding against a {@code null} stream (for example when {@link #beforeStep(StepExecution)}
     * never ran). If the close fails, the failure is logged at {@code ERROR} and the step exit
     * status is downgraded to {@link ExitStatus#FAILED} (the return-code-8 analog); otherwise the
     * step's existing {@link ExitStatus} is returned unchanged so a clean run maps to return code
     * 0.</p>
     *
     * @param stepExecution the current step execution; never {@code null}
     * @return {@link ExitStatus#FAILED} if the stream could not be closed cleanly, otherwise the
     *         unchanged {@link StepExecution#getExitStatus() step exit status}
     */
    @Override
    public ExitStatus afterStep(StepExecution stepExecution) {
        final Writer target = this.writer;
        if (target == null) {
            return stepExecution.getExitStatus();
        }
        try {
            target.flush();
            target.close();
            LOGGER.info("Transaction backup closed: {} ({} record(s) written).",
                    this.backupFile, this.recordsWritten);
        } catch (IOException ex) {
            LOGGER.error("Failed to flush/close transaction backup file {}", this.backupFile, ex);
            return ExitStatus.FAILED;
        } finally {
            this.writer = null;
            this.backupFile = null;
        }
        return stepExecution.getExitStatus();
    }
}
