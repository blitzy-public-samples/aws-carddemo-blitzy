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
package com.aws.carddemo.batch.reader;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import com.aws.carddemo.common.util.FixedWidthCodec;
import com.aws.carddemo.domain.Transaction;
import org.springframework.batch.core.configuration.annotation.StepScope;
import org.springframework.batch.item.ExecutionContext;
import org.springframework.batch.item.ItemStreamException;
import org.springframework.batch.item.ItemStreamReader;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Component;

/**
 * Spring Batch reader that reproduces the SORT step of the legacy job
 * {@code legacy/jcl/COMBTRAN.jcl} (source {@code app/jcl/COMBTRAN.jcl}, "Combine
 * transactions"). It reads the <em>backup</em> transaction dataset concatenated
 * with the <em>system-generated</em> transaction dataset and emits every record
 * ordered by transaction id ascending, so the downstream writer
 * ({@code com.aws.carddemo.batch.writer.TransactionJpaItemWriter}) can load the
 * combined, sorted stream into the {@code transaction} table &mdash; the Java
 * re-platform of the job's IDCAMS {@code REPRO} into
 * {@code AWS.M2.CARDDEMO.TRANSACT.VSAM.KSDS}.
 *
 * <h2>COBOL / JCL lineage (COMBTRAN.jcl STEP05R, authoritative)</h2>
 * <ul>
 *   <li><strong>Concatenated input order.</strong> {@code SORTIN} is the
 *       concatenation of {@code AWS.M2.CARDDEMO.TRANSACT.BKUP(0)} <em>then</em>
 *       {@code AWS.M2.CARDDEMO.SYSTRAN(0)} &mdash; backup first, system second.
 *       This reader loads {@link #backupResource} before {@link #systemResource}
 *       to preserve that concatenation order.</li>
 *   <li><strong>Sort key.</strong> {@code SYMNAMES} declares
 *       {@code TRAN-ID,1,16,CH} and {@code SYSIN} declares
 *       {@code SORT FIELDS=(TRAN-ID,A)}: the key is {@code TRAN-ID} at record
 *       position 1, length 16, character, ascending. Reproduced here as a sort
 *       on {@link Transaction#getTranId()} ascending.</li>
 * </ul>
 *
 * <h2>Record layout ({@code legacy/cpy/CVTRA05Y.cpy}, {@code TRAN-RECORD}, RECLN 350)</h2>
 * Both inputs are fixed-width 350-byte {@code TRAN-RECORD} images. Each record is
 * decoded field-by-field with the shared
 * {@link com.aws.carddemo.common.util.FixedWidthCodec}; see {@link #mapRecord(String)}
 * for the offset table. The signed monetary field {@code TRAN-AMT}
 * ({@code PIC S9(09)V99}) is decoded to {@link java.math.BigDecimal} at scale 2
 * via the codec's zoned-decimal (overpunch-aware) reader &mdash; never through
 * {@code new BigDecimal(String)} and never as {@code double}/{@code float}. The
 * trailing {@code FILLER PIC X(20)} is layout padding and is intentionally not
 * mapped.
 *
 * <h2>Sorting and stability</h2>
 * The records from both inputs are buffered into a single list and sorted with
 * {@link java.util.List#sort(java.util.Comparator)} (TimSort), which is
 * <strong>stable</strong>: records that share the same {@code TRAN-ID} therefore
 * retain their backup-before-system relative order. The mainframe {@code SORT}
 * is invoked without the {@code EQUALS} option, which leaves the relative order
 * of equal-key records formally unspecified; a stable sort with backup-first
 * concatenation is the deterministic, defensible parity choice for
 * {@code SORT FIELDS=(TRAN-ID,A)}.
 *
 * <h2>Reading and character set</h2>
 * The canonical dataset layout is {@code RECFM=FB, LRECL=350}. When the input is
 * materialised as a text file with one record per line, {@link #readRecords} reads
 * it line-by-line. Bytes are decoded with {@link java.nio.charset.StandardCharsets#ISO_8859_1}
 * (a byte-preserving 8-bit charset) rather than UTF-8, so the zoned-decimal
 * overpunch bytes (<code>{ } A-R</code>) survive intact for the amount decode. If
 * an input is instead a true fixed-block stream with no line terminators, the
 * equivalent strategy is to read the whole stream and slice it into
 * {@value #RECORD_LENGTH}-character records; the line-oriented form is used here
 * because it matches the delimited ASCII datasets shipped with CardDemo.
 *
 * <h2>Buffering rationale</h2>
 * A global sort across the two concatenated inputs requires all records in memory
 * at once, which is why this is a custom {@link ItemStreamReader} rather than a
 * streaming {@code FlatFileItemReader}. Buffering is acceptable: the combined
 * transaction volume is small (the seed data holds 311 transactions plus any
 * backups).
 *
 * <h2>Restartability</h2>
 * The reader participates in the Spring Batch {@link org.springframework.batch.item.ItemStream}
 * lifecycle. {@link #update(ExecutionContext)} persists the next read index under
 * the key {@value #INDEX_KEY}, and {@link #open(ExecutionContext)} restores it, so a
 * restarted step resumes emitting from where it stopped rather than replaying the
 * whole (re-sorted) stream.
 *
 * <h2>Bean wiring</h2>
 * Registered as a Spring {@link Component}; its bean name is the decapitalized
 * class name, {@code combinedTransactionItemReader}, which the parent
 * {@code TransactionCombineJob} (COMBTRAN.jcl) wires to the paired
 * {@code TransactionJpaItemWriter}. Because the input locations are late-bound
 * job parameters resolved with SpEL ({@code #{jobParameters[...]}}), the bean is
 * {@link StepScope step-scoped}: a singleton could not late-bind the parameters.
 * No file path or credential is hard-coded &mdash; the two {@link Resource}s are
 * supplied entirely by the job's parameters. The class carries no
 * {@code @EnableBatchProcessing} concern and is compatible with
 * {@code spring.batch.job.enabled=false}.
 *
 * <h2>Alternative: database-backed reader</h2>
 * If both the backup and the system-generated transactions were already persisted
 * in the {@code transaction} table, an equivalent reader would be a
 * {@code RepositoryItemReader<Transaction>} over {@code TransactionRepository}
 * configured with {@code setMethodName("findAll")} and a {@code tranId} ascending
 * sort. The fixed-width, two-resource design implemented here is the
 * <strong>primary</strong> because COMBTRAN's inputs are external sequential
 * datasets that are combined and sorted <em>before</em> the load into the master.
 *
 * <h2>Scope</h2>
 * This reader performs no business logic: it only reads, concatenates and sorts.
 * Persisting the combined stream (the IDCAMS {@code REPRO}) is the writer's
 * responsibility.
 *
 * @see Transaction
 * @see com.aws.carddemo.common.util.FixedWidthCodec
 * @see ItemStreamReader
 * @see <a href="http://www.apache.org/licenses/LICENSE-2.0">Apache License 2.0</a>
 */
@Component
@StepScope
public class CombinedTransactionItemReader implements ItemStreamReader<Transaction> {

    /**
     * {@link ExecutionContext} key under which the next read index is saved for
     * restart. Fully qualified with the bean name to avoid collisions with any
     * other stream contributing to the same step's execution context.
     */
    private static final String INDEX_KEY = "combinedTransactionItemReader.nextIndex";

    /** Canonical fixed record length of {@code TRAN-RECORD} (CVTRA05Y): {@code RECFM=FB, LRECL=350}. */
    private static final int RECORD_LENGTH = 350;

    /**
     * Number of leading characters spanned by the mapped fields, i.e. the end
     * offset of the last mapped field {@code TRAN-PROC-TS} ({@code @304} + 26).
     * The trailing {@code FILLER PIC X(20)} ({@code @330}) is not mapped, so a
     * record is readable once it is at least this long.
     */
    private static final int MAPPED_SPAN_LENGTH = 330;

    // --- CVTRA05Y TRAN-RECORD field offsets (0-based) and lengths ---
    /** {@code TRAN-ID PIC X(16)} at offset 0. */
    private static final int TRAN_ID_OFFSET = 0;
    private static final int TRAN_ID_LENGTH = 16;
    /** {@code TRAN-TYPE-CD PIC X(02)} at offset 16. */
    private static final int TRAN_TYPE_CD_OFFSET = 16;
    private static final int TRAN_TYPE_CD_LENGTH = 2;
    /** {@code TRAN-CAT-CD PIC 9(04)} at offset 18. */
    private static final int TRAN_CAT_CD_OFFSET = 18;
    private static final int TRAN_CAT_CD_LENGTH = 4;
    /** {@code TRAN-SOURCE PIC X(10)} at offset 22. */
    private static final int TRAN_SOURCE_OFFSET = 22;
    private static final int TRAN_SOURCE_LENGTH = 10;
    /** {@code TRAN-DESC PIC X(100)} at offset 32. */
    private static final int TRAN_DESC_OFFSET = 32;
    private static final int TRAN_DESC_LENGTH = 100;
    /** {@code TRAN-AMT PIC S9(09)V99} at offset 132 (length 11, scale 2). */
    private static final int TRAN_AMT_OFFSET = 132;
    private static final int TRAN_AMT_LENGTH = 11;
    private static final int TRAN_AMT_SCALE = 2;
    /** {@code TRAN-MERCHANT-ID PIC 9(09)} at offset 143. */
    private static final int TRAN_MERCHANT_ID_OFFSET = 143;
    private static final int TRAN_MERCHANT_ID_LENGTH = 9;
    /** {@code TRAN-MERCHANT-NAME PIC X(50)} at offset 152. */
    private static final int TRAN_MERCHANT_NAME_OFFSET = 152;
    private static final int TRAN_MERCHANT_NAME_LENGTH = 50;
    /** {@code TRAN-MERCHANT-CITY PIC X(50)} at offset 202. */
    private static final int TRAN_MERCHANT_CITY_OFFSET = 202;
    private static final int TRAN_MERCHANT_CITY_LENGTH = 50;
    /** {@code TRAN-MERCHANT-ZIP PIC X(10)} at offset 252. */
    private static final int TRAN_MERCHANT_ZIP_OFFSET = 252;
    private static final int TRAN_MERCHANT_ZIP_LENGTH = 10;
    /** {@code TRAN-CARD-NUM PIC X(16)} at offset 262. */
    private static final int TRAN_CARD_NUM_OFFSET = 262;
    private static final int TRAN_CARD_NUM_LENGTH = 16;
    /** {@code TRAN-ORIG-TS PIC X(26)} at offset 278. */
    private static final int TRAN_ORIG_TS_OFFSET = 278;
    private static final int TRAN_ORIG_TS_LENGTH = 26;
    /** {@code TRAN-PROC-TS PIC X(26)} at offset 304. */
    private static final int TRAN_PROC_TS_OFFSET = 304;
    private static final int TRAN_PROC_TS_LENGTH = 26;

    /**
     * Backup transaction input ({@code AWS.M2.CARDDEMO.TRANSACT.BKUP(0)}), the
     * first {@code SORTIN} concatenation member. Late-bound from the
     * {@code backupResource} job parameter; may be {@code null} or point to a
     * non-existent location when a run supplies only system input.
     */
    private final Resource backupResource;

    /**
     * System-generated transaction input ({@code AWS.M2.CARDDEMO.SYSTRAN(0)}),
     * the second {@code SORTIN} concatenation member. Late-bound from the
     * {@code systemResource} job parameter; may be {@code null} or point to a
     * non-existent location when a run supplies only backup input.
     */
    private final Resource systemResource;

    /** Buffered, concatenated and sorted records; {@code null} until {@link #open} and after {@link #close}. */
    private List<Transaction> items;

    /** Index of the next record to emit from {@link #items}; restored on restart. */
    private int nextIndex;

    /**
     * Creates the step-scoped reader from the two late-bound input locations.
     *
     * <p>Spring converts each job-parameter string location into a
     * {@link Resource} before injection, so no file path is ever hard-coded in
     * this class. The constructor only stores its arguments; all I/O is deferred
     * to {@link #open(ExecutionContext)}.</p>
     *
     * @param backupResource the backup transaction dataset ({@code SORTIN} member 1),
     *                        late-bound from the {@code backupResource} job parameter;
     *                        may be {@code null} when only system input is supplied
     * @param systemResource the system-generated transaction dataset ({@code SORTIN}
     *                        member 2), late-bound from the {@code systemResource}
     *                        job parameter; may be {@code null} when only backup input
     *                        is supplied
     */
    public CombinedTransactionItemReader(
            @Value("#{jobParameters['backupResource']}") Resource backupResource,
            @Value("#{jobParameters['systemResource']}") Resource systemResource) {
        this.backupResource = backupResource;
        this.systemResource = systemResource;
    }

    /**
     * Opens the stream: buffers the backup input then the system input
     * (preserving the {@code SORTIN} concatenation order), performs the stable
     * ascending sort on {@code TRAN-ID}, and restores the next read index from a
     * prior execution when restarting.
     *
     * @param executionContext the step's execution context; carries the saved
     *                         {@value #INDEX_KEY} on restart
     * @throws ItemStreamException if either input cannot be read
     */
    @Override
    public void open(ExecutionContext executionContext) throws ItemStreamException {
        items = new ArrayList<>();
        try {
            // SORTIN concatenation order: backup first, then system.
            readRecords(backupResource, items);
            readRecords(systemResource, items);
        } catch (IOException ex) {
            items = null;
            throw new ItemStreamException(
                    "Failed to read combined transaction input (COMBTRAN SORTIN)", ex);
        }
        // SORT FIELDS=(TRAN-ID,A): stable ascending sort by transaction id.
        items.sort(Comparator.comparing(Transaction::getTranId));
        nextIndex = executionContext.containsKey(INDEX_KEY) ? executionContext.getInt(INDEX_KEY) : 0;
    }

    /**
     * Returns the next transaction in ascending {@code TRAN-ID} order, or
     * {@code null} once the combined stream is exhausted (the {@code ItemReader}
     * end-of-data contract).
     *
     * @return the next {@link Transaction}, or {@code null} at end of data
     * @throws Exception to honour the {@link org.springframework.batch.item.ItemReader}
     *                   contract; this implementation does not itself throw
     */
    @Override
    public Transaction read() throws Exception {
        if (items != null && nextIndex < items.size()) {
            return items.get(nextIndex++);
        }
        return null;
    }

    /**
     * Persists the next read index so a restarted step resumes from the correct
     * position.
     *
     * @param executionContext the step's execution context to update
     */
    @Override
    public void update(ExecutionContext executionContext) {
        executionContext.putInt(INDEX_KEY, nextIndex);
    }

    /**
     * Releases the buffered records and resets the cursor. Safe to call more than
     * once.
     */
    @Override
    public void close() {
        items = null;
        nextIndex = 0;
    }

    /**
     * Reads every fixed-width record from {@code resource} and appends the decoded
     * {@link Transaction}s to {@code target}.
     *
     * <p>The stream is decoded with {@link StandardCharsets#ISO_8859_1} so the
     * zoned-decimal overpunch bytes survive intact, and is read one record per
     * line; wholly blank lines are skipped. A {@code null} or non-existent
     * resource is treated as empty input, because a given run may supply only the
     * backup or only the system dataset. (For a true fixed-block stream with no
     * line terminators the equivalent approach is to read the whole stream and
     * slice it into {@value #RECORD_LENGTH}-character records.)</p>
     *
     * @param resource the input dataset; may be {@code null} or non-existent
     * @param target   the accumulating list to append decoded records to
     * @throws IOException if the resource exists but cannot be opened or read
     */
    private void readRecords(Resource resource, List<Transaction> target) throws IOException {
        if (resource == null || !resource.exists()) {
            return;
        }
        try (InputStream in = resource.getInputStream();
             BufferedReader reader = new BufferedReader(
                     new InputStreamReader(in, StandardCharsets.ISO_8859_1))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isBlank()) {
                    continue;
                }
                target.add(mapRecord(line));
            }
        }
    }

    /**
     * Decodes a single 350-byte {@code TRAN-RECORD} into a {@link Transaction}
     * using {@link FixedWidthCodec}, following the CVTRA05Y offset table:
     * <pre>
     *   TRAN-ID           X(16)   @0     TRAN-MERCHANT-NAME X(50)  @152
     *   TRAN-TYPE-CD      X(02)   @16    TRAN-MERCHANT-CITY X(50)  @202
     *   TRAN-CAT-CD       9(04)   @18    TRAN-MERCHANT-ZIP  X(10)  @252
     *   TRAN-SOURCE       X(10)   @22    TRAN-CARD-NUM      X(16)  @262
     *   TRAN-DESC         X(100)  @32    TRAN-ORIG-TS       X(26)  @278
     *   TRAN-AMT          S9(9)V99 @132  TRAN-PROC-TS       X(26)  @304
     *   TRAN-MERCHANT-ID  9(09)   @143   FILLER             X(20)  @330 (unmapped)
     * </pre>
     *
     * <p>{@code TRAN-AMT} is decoded with the overpunch-aware
     * {@link FixedWidthCodec#readSignedDecimal(String, int, int, int)} to a scale-2
     * {@link java.math.BigDecimal}.</p>
     *
     * @param record one fixed-width transaction record; must span at least the
     *               mapped fields
     * @return the decoded {@link Transaction}
     * @throws IllegalArgumentException if {@code record} is shorter than the mapped
     *                                  field span
     */
    private Transaction mapRecord(String record) {
        if (record.length() < MAPPED_SPAN_LENGTH) {
            throw new IllegalArgumentException(
                    "TRAN-RECORD too short: expected at least " + MAPPED_SPAN_LENGTH
                            + " characters (canonical LRECL " + RECORD_LENGTH + ") but was "
                            + record.length());
        }
        return new Transaction(
                FixedWidthCodec.readAlphanumericTrimmed(record, TRAN_ID_OFFSET, TRAN_ID_LENGTH),
                FixedWidthCodec.readAlphanumericTrimmed(record, TRAN_TYPE_CD_OFFSET, TRAN_TYPE_CD_LENGTH),
                FixedWidthCodec.readNumericInt(record, TRAN_CAT_CD_OFFSET, TRAN_CAT_CD_LENGTH),
                FixedWidthCodec.readAlphanumericTrimmed(record, TRAN_SOURCE_OFFSET, TRAN_SOURCE_LENGTH),
                FixedWidthCodec.readAlphanumericTrimmed(record, TRAN_DESC_OFFSET, TRAN_DESC_LENGTH),
                FixedWidthCodec.readSignedDecimal(record, TRAN_AMT_OFFSET, TRAN_AMT_LENGTH, TRAN_AMT_SCALE),
                FixedWidthCodec.readNumeric(record, TRAN_MERCHANT_ID_OFFSET, TRAN_MERCHANT_ID_LENGTH),
                FixedWidthCodec.readAlphanumericTrimmed(record, TRAN_MERCHANT_NAME_OFFSET, TRAN_MERCHANT_NAME_LENGTH),
                FixedWidthCodec.readAlphanumericTrimmed(record, TRAN_MERCHANT_CITY_OFFSET, TRAN_MERCHANT_CITY_LENGTH),
                FixedWidthCodec.readAlphanumericTrimmed(record, TRAN_MERCHANT_ZIP_OFFSET, TRAN_MERCHANT_ZIP_LENGTH),
                FixedWidthCodec.readAlphanumericTrimmed(record, TRAN_CARD_NUM_OFFSET, TRAN_CARD_NUM_LENGTH),
                FixedWidthCodec.readAlphanumericTrimmed(record, TRAN_ORIG_TS_OFFSET, TRAN_ORIG_TS_LENGTH),
                FixedWidthCodec.readAlphanumericTrimmed(record, TRAN_PROC_TS_OFFSET, TRAN_PROC_TS_LENGTH));
    }
}
