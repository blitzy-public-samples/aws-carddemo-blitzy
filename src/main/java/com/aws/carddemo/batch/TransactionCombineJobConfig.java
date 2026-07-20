package com.aws.carddemo.batch;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.PriorityQueue;
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

import com.aws.carddemo.util.FixedWidthRecordMapper;
import com.aws.carddemo.util.FixedWidthRecordMapper.FieldDef;
import com.aws.carddemo.util.batch.BatchFilePathResolver;

/**
 * Spring Batch job configuration translating the mainframe transaction-combine job {@code COMBTRAN}.
 *
 * <p><strong>Origin and traceability (AAP &sect;0.6.10):</strong> migrated from three retained,
 * read-only legacy sources:</p>
 * <ul>
 *   <li>{@code legacy/jcl/COMBTRAN.jcl} (source branch {@code app/jcl/COMBTRAN.jcl}) &mdash; the JCL
 *       job whose {@code STEP05R} runs {@code PGM=SORT} over the concatenation of the transaction
 *       backup file ({@code AWS.M2.CARDDEMO.TRANSACT.BKUP}) and the daily system-generated
 *       transaction file ({@code AWS.M2.CARDDEMO.SYSTRAN}), sorting ascending by transaction id
 *       ({@code SYMNAMES TRAN-ID,1,16,CH}; {@code SORT FIELDS=(TRAN-ID,A)}) into the combined output
 *       ({@code AWS.M2.CARDDEMO.TRANSACT.COMBINED}), then whose {@code STEP10} runs {@code PGM=IDCAMS}
 *       {@code REPRO} to load that combined file into the {@code TRANSACT} VSAM KSDS;</li>
 *   <li>{@code legacy/ctl/REPROCT.ctl} (source branch {@code app/ctl/REPROCT.ctl}) &mdash; the generic
 *       IDCAMS control statement {@code REPRO INFILE(FILEIN) OUTFILE(FILEOUT)}; and</li>
 *   <li>{@code legacy/proc/REPROC.prc} (source branch {@code app/proc/REPROC.prc}) &mdash; the generic
 *       IDCAMS {@code REPRO} cataloged procedure that wraps {@code REPROCT.ctl}.</li>
 * </ul>
 *
 * <p>This class implements AAP &sect;0.4.1 (JCL job &rarr; Spring Batch {@link Job}), &sect;0.6.3
 * (JCL/JES2 {@code SORT} &rarr; Spring Batch topology with a Java ordering), and &mdash; the key
 * parity concern here &mdash; &sect;0.6.6 (EBCDIC/ASCII collation fidelity).</p>
 *
 * <p><strong>Behavior reproduced.</strong> The single {@link #transactionCombineStep()} concatenates
 * the two 350-byte transaction inputs (the backup file first, then the system-transaction file, in the
 * physical {@code SORTIN} concatenation order), sorts the concatenation ascending by the 16-character
 * {@code TRAN-ID} field, and writes the sorted, combined 350-byte records to a single output file.
 * Because {@code SORT} performs a <em>global</em> sort across the entire concatenated input &mdash;
 * something a streaming, chunk-oriented Spring Batch step cannot express &mdash; the step is realized
 * as a single {@link Tasklet} that performs an <strong>external, bounded-memory merge sort</strong>:
 * each input is read in a streaming fashion and partitioned into sorted <em>runs</em> of at most
 * {@link #DEFAULT_MAX_RECORDS_PER_RUN} records that are spilled to secure temporary files, and the runs
 * are then k-way merged, in {@code TRAN-ID} order, straight into the combined output. Peak heap use is
 * therefore bounded by the configured run size rather than by the total input size, so the job scales
 * to arbitrarily large inputs without materializing either feed wholly in memory (finding #22). The
 * subsequent IDCAMS {@code REPRO} ({@code STEP10}, driven by {@code REPROC.prc} / {@code REPROCT.ctl})
 * is generic load-the-combined-dataset plumbing; under Spring its faithful realization is simply the
 * write of the combined output file, which this job produces. That {@code REPRO} &rarr; file-write
 * mapping is recorded in the traceability matrix.</p>
 *
 * <p><strong>Collation decision (AAP &sect;0.6.6) &mdash; the critical parity risk.</strong> EBCDIC and
 * ASCII/UTF-8 sort orders differ, and a locale-sensitive collator would reorder mixed digit/letter and
 * mixed-case keys differently again. To reproduce the legacy {@code SORT FIELDS=(TRAN-ID,A)} ordering
 * deterministically, the comparison is <em>bytewise</em> over the raw {@code TRAN-ID} bytes using
 * {@link Arrays#compareUnsigned(byte[], byte[])} &mdash; equivalent to a {@code C} / {@code POSIX}
 * collation and identical to the {@code C} collation applied to the {@code tran_id CHAR(16)} column in
 * the database. The JVM's locale-sensitive {@code Collator} is deliberately <em>not</em> used. The
 * rationale for this collation choice is recorded in {@code docs/decision-log.md} rather than repeated
 * in code comments (Explainability rule).</p>
 *
 * <p><strong>Stable ordering.</strong> The external merge preserves the exact ordering the legacy
 * global {@code SORT} produces for equal keys: each run is sorted with a <em>stable</em> sort so records
 * that share a {@code TRAN-ID} keep their read order within a run, and the k-way merge breaks ties by
 * ascending run index. Because the backup input is read (and thus spilled into runs) before the
 * system-transaction input, and because earlier reads always land in lower-indexed runs, a record from
 * the backup file always precedes an equal-keyed record from the system file &mdash; matching the
 * physical {@code SORTIN} concatenation order. The output is therefore byte-for-byte identical to a
 * single stable in-memory sort of the concatenated inputs.</p>
 *
 * <p><strong>Byte-exact records (AAP &sect;0.6.1, &sect;0.6.4).</strong> Each record is the 350-byte
 * {@code CVTRA05Y} {@code TRAN-RECORD} layout ({@code legacy/cpy/CVTRA05Y.cpy}), the same record that
 * the {@link com.aws.carddemo.domain.Transaction} entity persists. The layout is modeled by a private
 * {@link FixedWidthRecordMapper} whose monetary {@code TRAN-AMT PIC S9(09)V99} field is a signed
 * decimal (backed by {@link java.math.BigDecimal}); no {@code float} or {@code double} is used
 * anywhere. The mapper is used to validate each record's length and to extract the {@code TRAN-ID}
 * sort key; the record's <em>original</em> bytes are preserved verbatim through the sort and written
 * back unchanged, so filler bytes, zoned-decimal signs, and every other byte survive the combine
 * byte-for-byte. Field content other than the length is intentionally not validated, matching the
 * legacy {@code SORT}, which inspects only the named sort field and passes every record through
 * verbatim.</p>
 *
 * <p><strong>Record framing.</strong> Inputs, the spilled run files, and the combined output are
 * <em>undelimited</em> fixed-block ({@code RECFM=FB}) images: contiguous 350-byte records with no
 * record delimiter, matching the native EBCDIC {@code .PS} datasets that are the source of truth (AAP
 * &sect;0.6.6). No line feed is written after any record, and no line-feed/carriage-return framing is
 * assumed on read; an input whose length is not an exact multiple of 350 bytes is rejected. This is the
 * byte-for-byte {@code RECFM=FB} contract required by finding #17. (The ASCII {@code .txt} datasets
 * under {@code legacy/data} carry a line feed per line purely as an editing convenience; a caller that
 * stages such data reframes it to the undelimited image before feeding this job.)</p>
 *
 * <p><strong>Job parameters.</strong> The three dataset paths are supplied per execution as job
 * parameters, resolved lazily into the {@code @StepScope} tasklet:</p>
 * <ul>
 *   <li>{@value #PARAM_BACKUP_INPUT} &mdash; the transaction backup input ({@code SORTIN} DD 1,
 *       {@code TRANSACT.BKUP});</li>
 *   <li>{@value #PARAM_SYSTEM_INPUT} &mdash; the system-transaction input ({@code SORTIN} DD 2,
 *       {@code SYSTRAN}); and</li>
 *   <li>{@value #PARAM_COMBINED_OUTPUT} &mdash; the combined, sorted output ({@code SORTOUT},
 *       {@code TRANSACT.COMBINED}).</li>
 * </ul>
 * <p>A missing or blank parameter fails the step fast, mirroring a JCL error for an unsatisfied DD.</p>
 *
 * <p><strong>Safe path handling and atomic publication (finding #18).</strong> Every input path is
 * resolved through the shared {@link BatchFilePathResolver}, which enforces safe-root containment and
 * rejects escaping symlinks before the file is read, and the combined output is written through
 * {@link BatchFilePathResolver#publish(Path, BatchFilePathResolver.ContentWriter)} &mdash; content is
 * streamed into an owner-only ({@code 0600}) sibling temporary file, flushed to durable storage, and
 * atomically renamed onto the target, with the temporary file deleted on any failure so a partial
 * output is never left behind. The spilled run files live in a per-execution scratch directory created
 * with owner-only permissions and are deleted in a {@code finally} block regardless of outcome.</p>
 *
 * <p><strong>Approach chosen (recorded in the decision log).</strong> A file-based combine is used
 * because it is closest to {@code COMBTRAN}, whose inputs are the sequential backup and
 * system-transaction files rather than the live transaction table. The database alternative &mdash;
 * streaming {@link com.aws.carddemo.repository.TransactionRepository} {@code findAll} under a
 * {@code C}/{@code POSIX} column collation and re-serializing the 350-byte records &mdash; is
 * documented but rejected here, because it would sort the master table rather than the two staged
 * input files and could not reproduce the byte-exact input records verbatim.</p>
 *
 * <p><strong>Wiring note (AAP binding constraint):</strong> this class deliberately does
 * <strong>not</strong> use {@code @EnableBatchProcessing}. It relies on Spring Boot's Batch
 * auto-configuration for the persistent, restartable {@link JobRepository} and the
 * {@link PlatformTransactionManager}, and additionally injects the shared {@link BatchFilePathResolver}
 * (declared in {@code config/BatchConfig}) through which every input path is containment-resolved and
 * the combined output is published atomically (finding #18). Tests that exercise this configuration
 * therefore import {@code BatchConfig} (or supply an equivalent {@code batchFilePathResolver} bean).</p>
 *
 * <p>This configuration is stateless and thread-safe: it holds only immutable collaborators, the
 * layout mapper and comparator are immutable and shared, and the {@code @StepScope} tasklet reads its
 * inputs exclusively from the per-execution job parameters.</p>
 */
@Configuration
public class TransactionCombineJobConfig {

    /** Logger used to record the combine outcome (record counts and output path; never record content). */
    private static final Logger LOGGER = LoggerFactory.getLogger(TransactionCombineJobConfig.class);

    /** Bean/registry name of the Spring Batch {@link Job} translating {@code COMBTRAN}. */
    static final String JOB_NAME = "transactionCombineJob";

    /** Name of the single step within {@link #JOB_NAME}. */
    static final String STEP_NAME = "transactionCombineStep";

    /** Job-parameter key for the transaction backup input ({@code SORTIN} DD 1, {@code TRANSACT.BKUP}). */
    static final String PARAM_BACKUP_INPUT = "backupInput";

    /** Job-parameter key for the system-transaction input ({@code SORTIN} DD 2, {@code SYSTRAN}). */
    static final String PARAM_SYSTEM_INPUT = "systemInput";

    /** Job-parameter key for the combined, sorted output ({@code SORTOUT}, {@code TRANSACT.COMBINED}). */
    static final String PARAM_COMBINED_OUTPUT = "combinedOutput";

    /** Fixed record length of the {@code CVTRA05Y} {@code TRAN-RECORD} layout, in bytes. */
    static final int RECORD_LENGTH = 350;

    /**
     * Default upper bound on the number of 350-byte records held in memory per sort run before that run
     * is spilled to a temporary file. At 350 bytes/record this bounds per-run heap use to roughly
     * {@code 50_000 * 350} bytes (~17&nbsp;MB) regardless of total input size, satisfying the
     * bounded-memory requirement (finding #22). Package-visible so tests can drive the multi-run merge
     * path with a deliberately small bound.
     */
    static final int DEFAULT_MAX_RECORDS_PER_RUN = 50_000;

    /** Prefix for the per-execution scratch directory holding spilled sort runs. */
    private static final String SCRATCH_DIR_PREFIX = "carddemo-combine-sort-";

    /** Prefix for an individual spilled sort-run temporary file. */
    private static final String RUN_FILE_PREFIX = "run-";

    /** Suffix for an individual spilled sort-run temporary file. */
    private static final String RUN_FILE_SUFFIX = ".fb";

    /** Name of the {@code TRAN-ID} sort-key field within the {@code CVTRA05Y} layout (bytes 1-16). */
    private static final String TRAN_ID_FIELD = "TRAN-ID";

    /**
     * Immutable, thread-safe mapper for the 350-byte {@code CVTRA05Y} {@code TRAN-RECORD} layout. Used
     * to validate each record's length and to extract the {@code TRAN-ID} sort key; the original record
     * bytes are otherwise preserved verbatim.
     */
    private static final FixedWidthRecordMapper TRANSACTION_RECORD_MAPPER = buildTransactionRecordMapper();

    /**
     * Deterministic bytewise ascending comparator over the raw 16-byte {@code TRAN-ID} key, reproducing
     * {@code SORT FIELDS=(TRAN-ID,A)} under a {@code C}/{@code POSIX} collation (AAP &sect;0.6.6). It is
     * locale-independent by construction: {@link Arrays#compareUnsigned(byte[], byte[])} compares the
     * bytes as unsigned values, never through a locale-sensitive collator.
     */
    private static final Comparator<SortableRecord> BY_TRAN_ID_BYTEWISE =
            (left, right) -> Arrays.compareUnsigned(left.tranIdKey(), right.tranIdKey());

    /**
     * Merge-phase cursor comparator: order by the bytewise {@code TRAN-ID} key, then by ascending run
     * index so that equal keys are emitted lowest-run-first. Because earlier reads land in lower-indexed
     * runs (and the backup input is read before the system input), this reproduces the stable ordering
     * of a single in-memory sort of the concatenated inputs.
     */
    private static final Comparator<RunCursor> MERGE_ORDER =
            Comparator.<RunCursor, byte[]>comparing(RunCursor::currentKey, Arrays::compareUnsigned)
                    .thenComparingInt(RunCursor::runIndex);

    /** Auto-configured Spring Batch job repository used to build the step and the job. */
    private final JobRepository jobRepository;

    /** Auto-configured transaction manager bounding the tasklet's execution. */
    private final PlatformTransactionManager transactionManager;

    /** Shared safe-path resolver enforcing containment on inputs and atomic 0600 publication on output. */
    private final BatchFilePathResolver batchFilePathResolver;

    /**
     * Creates the configuration with the collaborators supplied by Spring Boot's Batch
     * auto-configuration plus the shared safe-path resolver.
     *
     * @param jobRepository         the auto-configured Spring Batch {@link JobRepository}
     * @param transactionManager    the auto-configured {@link PlatformTransactionManager}
     * @param batchFilePathResolver the shared {@link BatchFilePathResolver} (from {@code BatchConfig})
     *                              enforcing safe-root containment and atomic output publication
     */
    public TransactionCombineJobConfig(JobRepository jobRepository,
            PlatformTransactionManager transactionManager,
            BatchFilePathResolver batchFilePathResolver) {
        this.jobRepository = jobRepository;
        this.transactionManager = transactionManager;
        this.batchFilePathResolver = batchFilePathResolver;
    }

    /**
     * The single {@code @StepScope} tasklet reproducing {@code COMBTRAN}'s {@code STEP05R} sort and the
     * {@code STEP10} {@code REPRO} write. It resolves the three dataset-path job parameters through the
     * {@link BatchFilePathResolver} (containment-checked inputs; atomically published output), spills
     * each input into sorted runs, k-way merges the runs in {@code TRAN-ID} order into the combined
     * output, and returns {@link RepeatStatus#FINISHED}. An I/O failure propagates and fails the step,
     * mirroring a legacy job abend on an I/O error.
     *
     * <p>Because the bean is {@code @StepScope}, the {@code @Value} job-parameter expressions are
     * resolved lazily per step execution; {@link #transactionCombineStep()} passes {@code null}
     * placeholders that the scoped proxy replaces with the real parameter values at run time.</p>
     *
     * @param backupInput    the transaction backup input path, bound from job parameter
     *                       {@value #PARAM_BACKUP_INPUT}
     * @param systemInput    the system-transaction input path, bound from job parameter
     *                       {@value #PARAM_SYSTEM_INPUT}
     * @param combinedOutput the combined output path, bound from job parameter
     *                       {@value #PARAM_COMBINED_OUTPUT}
     * @return a {@link Tasklet} that combines and sorts the two inputs into the combined output
     */
    @Bean
    @StepScope
    public Tasklet transactionCombineTasklet(
            @Value("#{jobParameters['" + PARAM_BACKUP_INPUT + "']}") String backupInput,
            @Value("#{jobParameters['" + PARAM_SYSTEM_INPUT + "']}") String systemInput,
            @Value("#{jobParameters['" + PARAM_COMBINED_OUTPUT + "']}") String combinedOutput) {
        return (contribution, chunkContext) -> {
            Path backupPath = batchFilePathResolver.resolveInputFile(
                    requireParam(backupInput, PARAM_BACKUP_INPUT));
            Path systemPath = batchFilePathResolver.resolveInputFile(
                    requireParam(systemInput, PARAM_SYSTEM_INPUT));
            Path outputTarget = batchFilePathResolver.resolveOutputTarget(
                    requireParam(combinedOutput, PARAM_COMBINED_OUTPUT));

            Path scratchDir = createScratchDirectory();
            try {
                List<Path> runFiles = new ArrayList<>();
                long backupCount = spillSortedRuns(backupPath, DEFAULT_MAX_RECORDS_PER_RUN,
                        runFiles, scratchDir);
                long systemCount = spillSortedRuns(systemPath, DEFAULT_MAX_RECORDS_PER_RUN,
                        runFiles, scratchDir);
                long[] written = {0L};
                batchFilePathResolver.publish(outputTarget, out -> written[0] = mergeRunsTo(runFiles, out));
                LOGGER.info("transactionCombineJob combined {} backup + {} system = {} records, sorted "
                                + "ascending by TRAN-ID (bytewise C/POSIX collation, external merge sort), "
                                + "atomically published to {}",
                        backupCount, systemCount, written[0], outputTarget);
            } finally {
                deleteRecursively(scratchDir);
            }
            return RepeatStatus.FINISHED;
        };
    }

    /**
     * The single step of {@link #transactionCombineJob()}, wrapping the {@code @StepScope}
     * {@link #transactionCombineTasklet(String, String, String)} within the auto-configured transaction
     * boundary. The {@code null} arguments are placeholders: the scoped proxy resolves the actual job
     * parameters at run time.
     *
     * @return the {@code transactionCombineStep} {@link Step}
     */
    @Bean
    public Step transactionCombineStep() {
        return new StepBuilder(STEP_NAME, jobRepository)
                .tasklet(transactionCombineTasklet(null, null, null), transactionManager)
                .build();
    }

    /**
     * The Spring Batch {@link Job} corresponding to legacy JCL job {@code COMBTRAN}. It consists of the
     * single {@link #transactionCombineStep()} and completes with batch status {@code COMPLETED} once
     * the combined, transaction-id-ordered output file has been written.
     *
     * @return the {@code transactionCombineJob} {@link Job}
     */
    @Bean
    public Job transactionCombineJob() {
        return new JobBuilder(JOB_NAME, jobRepository)
                .start(transactionCombineStep())
                .build();
    }

    /**
     * Combines and sorts the two inputs using the default per-run bound, writing the combined 350-byte
     * records to the output path. This overload is the behavioral core of the job exposed at package
     * visibility so the external-merge-sort algorithm can be unit-tested directly without launching a
     * {@code JobLauncher} or wiring the {@link BatchFilePathResolver}.
     *
     * @param backupInput    the transaction backup input path (read first)
     * @param systemInput    the system-transaction input path (read second)
     * @param combinedOutput the combined, sorted output path (its parent directory is created if absent)
     * @return the number of combined records written (the sum of the two input record counts)
     * @throws IOException              if any input cannot be read or the output cannot be written
     * @throws IllegalArgumentException if any record's length is not exactly {@link #RECORD_LENGTH}
     *                                  bytes, or an input length is not a multiple of it
     */
    static long combineAndSort(Path backupInput, Path systemInput, Path combinedOutput)
            throws IOException {
        return combineAndSort(backupInput, systemInput, combinedOutput, DEFAULT_MAX_RECORDS_PER_RUN);
    }

    /**
     * Combines and sorts the two transaction inputs using an <strong>external, bounded-memory merge
     * sort</strong> and writes the combined 350-byte records to the output path. Each input is streamed
     * and partitioned into sorted runs of at most {@code maxRecordsPerRun} records spilled to temporary
     * files; the runs are then k-way merged in {@code TRAN-ID} order (bytewise {@code C}/{@code POSIX}
     * collation) straight to the output. Peak heap use is bounded by the run size, never by the total
     * input size (finding #22). This overload is exposed at package visibility for direct unit testing;
     * it writes the output file directly (the {@code @StepScope} tasklet adds resolver-backed
     * containment and 0600 atomic publication).
     *
     * <p>The backup input is read (and spilled) first and the system-transaction input second,
     * preserving the physical {@code SORTIN} concatenation order. The per-run sort is <em>stable</em>
     * and the merge breaks ties by ascending run index, so should two records ever share a
     * {@code TRAN-ID} (not expected, as {@code TRAN-ID} is the unique {@code TRANSACT} key) the backup
     * record precedes the system-transaction record, matching the concatenation order. Every input
     * record appears in the output exactly once, so the written count equals the sum of the two input
     * counts, and the output is byte-for-byte identical to a single stable in-memory sort of the
     * concatenation.</p>
     *
     * @param backupInput      the transaction backup input path (read first)
     * @param systemInput      the system-transaction input path (read second)
     * @param combinedOutput   the combined, sorted output path (its parent directory is created if absent)
     * @param maxRecordsPerRun the maximum number of records buffered before a run is spilled; must be
     *                         positive
     * @return the number of combined records written (the sum of the two input record counts)
     * @throws IOException              if any input cannot be read or the output cannot be written
     * @throws IllegalArgumentException if {@code maxRecordsPerRun} is not positive, or any record's
     *                                  length is not exactly {@link #RECORD_LENGTH} bytes, or an input
     *                                  length is not a multiple of it
     */
    static long combineAndSort(Path backupInput, Path systemInput, Path combinedOutput,
            int maxRecordsPerRun) throws IOException {
        if (maxRecordsPerRun <= 0) {
            throw new IllegalArgumentException("maxRecordsPerRun must be positive, was " + maxRecordsPerRun);
        }
        Path parent = combinedOutput.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        Path scratchDir = createScratchDirectory();
        try {
            List<Path> runFiles = new ArrayList<>();
            long backupCount = spillSortedRuns(backupInput, maxRecordsPerRun, runFiles, scratchDir);
            long systemCount = spillSortedRuns(systemInput, maxRecordsPerRun, runFiles, scratchDir);
            long written;
            try (OutputStream out = new BufferedOutputStream(Files.newOutputStream(combinedOutput))) {
                written = mergeRunsTo(runFiles, out);
            }
            LOGGER.info("transactionCombineJob combined {} backup + {} system = {} records, sorted "
                            + "ascending by TRAN-ID (bytewise C/POSIX collation, external merge sort), into {}",
                    backupCount, systemCount, written, combinedOutput);
            return written;
        } finally {
            deleteRecursively(scratchDir);
        }
    }

    /**
     * Streams every fixed-width record from {@code input}, partitioning them into sorted runs of at most
     * {@code maxRecordsPerRun} records. Each full buffer is stably sorted by {@code TRAN-ID} and spilled
     * to a new undelimited temporary file appended to {@code runFiles}; the final partial buffer is
     * spilled likewise. The input is read as a strict undelimited {@code RECFM=FB} image: 350-byte
     * blocks with no delimiter, never assuming or tolerating line feeds (finding #17).
     *
     * @param input            the input file to read (already containment-resolved by the caller)
     * @param maxRecordsPerRun the maximum records buffered before a run is spilled
     * @param runFiles         the accumulator of spilled run-file paths, in creation (read) order
     * @param scratchDir       the owner-only scratch directory that holds the run files
     * @return the number of records read from {@code input}
     * @throws IOException              if the file cannot be read or a run cannot be spilled
     * @throws IllegalArgumentException if a record's length is not exactly {@link #RECORD_LENGTH} bytes
     *                                  or the input ends on a partial block
     */
    private static long spillSortedRuns(Path input, int maxRecordsPerRun, List<Path> runFiles,
            Path scratchDir) throws IOException {
        long total = 0;
        List<SortableRecord> buffer = new ArrayList<>(Math.min(maxRecordsPerRun, 1024));
        try (InputStream in = new BufferedInputStream(Files.newInputStream(input))) {
            byte[] block = new byte[RECORD_LENGTH];
            while (readBlock(in, block)) {
                // The reusable block is copied so buffered records retain their own bytes.
                byte[] recordBytes = block.clone();
                // parse(...) validates the exact 350-byte length; getRawBytes extracts the TRAN-ID key.
                byte[] tranIdKey = TRANSACTION_RECORD_MAPPER.parse(recordBytes).getRawBytes(TRAN_ID_FIELD);
                buffer.add(new SortableRecord(tranIdKey, recordBytes));
                total++;
                if (buffer.size() >= maxRecordsPerRun) {
                    spillRun(buffer, runFiles, scratchDir);
                    buffer.clear();
                }
            }
        }
        if (!buffer.isEmpty()) {
            spillRun(buffer, runFiles, scratchDir);
        }
        return total;
    }

    /**
     * Stably sorts {@code buffer} ascending by {@code TRAN-ID} (bytewise) and writes it as one
     * undelimited run file (no record delimiter) into {@code scratchDir}, appending the new run's path
     * to {@code runFiles}. The list index of the appended path is the run's index, which the merge uses
     * as the stability tie-breaker (earlier reads occupy lower-indexed runs).
     *
     * @param buffer     the run's records (mutated in place by the stable sort)
     * @param runFiles   the accumulator of spilled run-file paths, in creation order
     * @param scratchDir the owner-only scratch directory that holds the run files
     * @throws IOException if the run file cannot be created or written
     */
    private static void spillRun(List<SortableRecord> buffer, List<Path> runFiles, Path scratchDir)
            throws IOException {
        buffer.sort(BY_TRAN_ID_BYTEWISE);
        Path runFile = Files.createTempFile(scratchDir, RUN_FILE_PREFIX, RUN_FILE_SUFFIX);
        try (OutputStream out = new BufferedOutputStream(Files.newOutputStream(runFile))) {
            for (SortableRecord record : buffer) {
                out.write(record.recordBytes());
            }
        }
        runFiles.add(runFile);
    }

    /**
     * Performs a k-way merge of the sorted run files into {@code out}, writing the combined records in
     * {@code TRAN-ID} order (bytewise {@code C}/{@code POSIX} collation), undelimited (finding #17), and
     * stably (ties broken by ascending run index). Only one record per open run is held in memory at a
     * time, so the merge is bounded-memory (finding #22).
     *
     * @param runFiles the sorted run files to merge, in creation (read) order
     * @param out      the destination stream for the combined output
     * @return the number of records written
     * @throws IOException if a run cannot be read or the output cannot be written
     */
    private static long mergeRunsTo(List<Path> runFiles, OutputStream out) throws IOException {
        PriorityQueue<RunCursor> queue = new PriorityQueue<>(Math.max(1, runFiles.size()), MERGE_ORDER);
        List<RunCursor> cursors = new ArrayList<>(runFiles.size());
        long written = 0;
        try {
            for (int i = 0; i < runFiles.size(); i++) {
                RunCursor cursor = new RunCursor(i, runFiles.get(i));
                cursors.add(cursor);
                if (cursor.hasCurrent()) {
                    queue.add(cursor);
                }
            }
            while (!queue.isEmpty()) {
                RunCursor least = queue.poll();
                out.write(least.currentRecord());
                written++;
                least.advance();
                if (least.hasCurrent()) {
                    queue.add(least);
                }
            }
        } finally {
            for (RunCursor cursor : cursors) {
                closeQuietly(cursor);
            }
        }
        return written;
    }

    /**
     * Fully reads the next {@link #RECORD_LENGTH}-byte block from {@code in} into {@code block},
     * handling short reads. Returns {@code true} when a complete block was read, {@code false} on a
     * clean end-of-file at a record boundary. A partial block at end-of-file is a truncated
     * {@code RECFM=FB} image and raises an {@link IOException} (finding #17: no delimiter tolerance).
     *
     * @param in    the input stream positioned at a record boundary
     * @param block the reusable {@link #RECORD_LENGTH}-byte buffer to fill
     * @return {@code true} if a full block was read; {@code false} at a clean record-boundary EOF
     * @throws IOException if reading fails or the stream ends mid-record
     */
    private static boolean readBlock(InputStream in, byte[] block) throws IOException {
        int total = 0;
        while (total < block.length) {
            int read = in.read(block, total, block.length - total);
            if (read < 0) {
                if (total == 0) {
                    return false;
                }
                throw new IOException("truncated undelimited RECFM=FB input: partial record of " + total
                        + " bytes (expected " + block.length + ")");
            }
            total += read;
        }
        return true;
    }

    /**
     * Splits raw file bytes into individual undelimited fixed-width records. The input is treated
     * strictly as a {@code RECFM=FB} image (finding #17): its length must be an exact multiple of
     * {@link #RECORD_LENGTH}, and no line-feed/carriage-return framing is inferred. Retained at package
     * visibility as the canonical framing contract and used by tests to slice a combined output file
     * into records for verification.
     *
     * @param data the raw file bytes
     * @return the ordered list of {@link #RECORD_LENGTH}-byte record arrays
     * @throws IllegalArgumentException if {@code data.length} is not a multiple of {@link #RECORD_LENGTH}
     */
    static List<byte[]> splitRecords(byte[] data) {
        List<byte[]> records = new ArrayList<>();
        if (data.length == 0) {
            return records;
        }
        if (data.length % RECORD_LENGTH != 0) {
            throw new IllegalArgumentException("undelimited RECFM=FB input length " + data.length
                    + " is not a multiple of the " + RECORD_LENGTH + "-byte record length");
        }
        for (int offset = 0; offset < data.length; offset += RECORD_LENGTH) {
            records.add(Arrays.copyOfRange(data, offset, offset + RECORD_LENGTH));
        }
        return records;
    }

    /**
     * Validates that a required job parameter is present and non-blank.
     *
     * @param value the resolved parameter value (possibly {@code null})
     * @param name  the parameter name, used only in the diagnostic message
     * @return the validated, non-blank value
     * @throws IllegalArgumentException if {@code value} is {@code null} or blank
     */
    private static String requireParam(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(
                    "required job parameter '" + name + "' is missing or blank");
        }
        return value;
    }

    /**
     * Creates a fresh per-execution scratch directory for spilled sort runs. On POSIX file systems the
     * directory is created with owner-only ({@code 0700}) permissions by default.
     *
     * @return the newly created scratch directory
     * @throws IOException if the directory cannot be created
     */
    private static Path createScratchDirectory() throws IOException {
        return Files.createTempDirectory(SCRATCH_DIR_PREFIX);
    }

    /**
     * Recursively deletes {@code dir} and all spilled run files beneath it, logging (never throwing) on
     * a failed deletion so scratch cleanup never masks the job outcome.
     *
     * @param dir the scratch directory to remove (may be {@code null})
     */
    private static void deleteRecursively(Path dir) {
        if (dir == null) {
            return;
        }
        try (Stream<Path> paths = Files.walk(dir)) {
            paths.sorted(Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException ex) {
                    LOGGER.warn("failed to delete combine scratch path {}: {}", path, ex.toString());
                }
            });
        } catch (IOException ex) {
            LOGGER.warn("failed to clean up combine scratch directory {}: {}", dir, ex.toString());
        }
    }

    /**
     * Closes a run cursor, logging (never throwing) any {@link IOException} so merge cleanup never masks
     * the job outcome.
     *
     * @param cursor the cursor to close
     */
    private static void closeQuietly(RunCursor cursor) {
        try {
            cursor.close();
        } catch (IOException ex) {
            LOGGER.warn("failed to close combine run cursor: {}", ex.toString());
        }
    }

    /**
     * Builds the immutable {@link FixedWidthRecordMapper} for the 350-byte {@code CVTRA05Y}
     * {@code TRAN-RECORD} layout ({@code legacy/cpy/CVTRA05Y.cpy}). Field widths sum to
     * {@link #RECORD_LENGTH}: 16 + 2 + 4 + 10 + 100 + 11 + 9 + 50 + 50 + 10 + 16 + 26 + 26 + 20 = 350.
     * The monetary {@code TRAN-AMT PIC S9(09)V99} is modeled as a signed decimal (backed by
     * {@link java.math.BigDecimal}); no floating-point type is used.
     *
     * @return the transaction-record layout mapper
     */
    private static FixedWidthRecordMapper buildTransactionRecordMapper() {
        return FixedWidthRecordMapper.of(
                FieldDef.text(TRAN_ID_FIELD, 16),
                FieldDef.text("TRAN-TYPE-CD", 2),
                FieldDef.numeric("TRAN-CAT-CD", 4),
                FieldDef.text("TRAN-SOURCE", 10),
                FieldDef.text("TRAN-DESC", 100),
                FieldDef.signedDecimal("TRAN-AMT", 11, 2),
                FieldDef.numeric("TRAN-MERCHANT-ID", 9),
                FieldDef.text("TRAN-MERCHANT-NAME", 50),
                FieldDef.text("TRAN-MERCHANT-CITY", 50),
                FieldDef.text("TRAN-MERCHANT-ZIP", 10),
                FieldDef.text("TRAN-CARD-NUM", 16),
                FieldDef.text("TRAN-ORIG-TS", 26),
                FieldDef.text("TRAN-PROC-TS", 26),
                FieldDef.filler(20));
    }

    /**
     * A lazily advancing cursor over one spilled sort-run file, reading undelimited 350-byte records and
     * exposing the current record together with its precomputed {@code TRAN-ID} key. Cursors are ordered
     * in the merge {@link PriorityQueue} by {@link #MERGE_ORDER}; only the single current record is held
     * in memory, keeping the merge bounded-memory (finding #22).
     */
    private static final class RunCursor implements Closeable {

        /** The run's index (its position in the creation-ordered run-file list), the stability key. */
        private final int runIndex;

        /** Buffered stream over the spilled run file. */
        private final InputStream in;

        /** Reusable {@link #RECORD_LENGTH}-byte read buffer. */
        private final byte[] block = new byte[RECORD_LENGTH];

        /** The current record's bytes, or {@code null} once the run is exhausted. */
        private byte[] currentRecord;

        /** The current record's raw {@code TRAN-ID} key, or {@code null} once the run is exhausted. */
        private byte[] currentKey;

        /**
         * Opens the run file and positions the cursor on its first record.
         *
         * @param runIndex the run's creation-order index (the stability tie-breaker)
         * @param runFile  the spilled run file
         * @throws IOException if the file cannot be opened or its first record cannot be read
         */
        RunCursor(int runIndex, Path runFile) throws IOException {
            this.runIndex = runIndex;
            this.in = new BufferedInputStream(Files.newInputStream(runFile));
            advance();
        }

        /**
         * Returns whether the cursor currently references a record (i.e. the run is not yet exhausted).
         *
         * @return {@code true} if a current record is available
         */
        boolean hasCurrent() {
            return currentRecord != null;
        }

        /**
         * Returns the current record's bytes.
         *
         * @return the current 350-byte record image
         */
        byte[] currentRecord() {
            return currentRecord;
        }

        /**
         * Returns the current record's raw {@code TRAN-ID} sort key.
         *
         * @return the current 16-byte {@code TRAN-ID} key
         */
        byte[] currentKey() {
            return currentKey;
        }

        /**
         * Returns the run's creation-order index used as the merge stability tie-breaker.
         *
         * @return the run index
         */
        int runIndex() {
            return runIndex;
        }

        /**
         * Advances to the next record in the run, clearing the current record on a clean end-of-file.
         *
         * @throws IOException if reading fails or the run ends mid-record
         */
        void advance() throws IOException {
            if (readBlock(in, block)) {
                currentRecord = block.clone();
                currentKey = TRANSACTION_RECORD_MAPPER.parse(currentRecord).getRawBytes(TRAN_ID_FIELD);
            } else {
                currentRecord = null;
                currentKey = null;
            }
        }

        @Override
        public void close() throws IOException {
            in.close();
        }
    }

    /**
     * A buffered-run record paired with its precomputed raw {@code TRAN-ID} sort key.
     *
     * <p>The key is the 16 raw bytes of the {@code TRAN-ID} field, precomputed once so the per-run sort
     * compares keys without re-parsing. The full record bytes are preserved verbatim and written to the
     * spilled run (and ultimately the combined output) unchanged, guaranteeing byte-exact record
     * fidelity.</p>
     *
     * @param tranIdKey   the raw 16-byte {@code TRAN-ID} sort key
     * @param recordBytes the full 350-byte record image, preserved verbatim
     */
    private record SortableRecord(byte[] tranIdKey, byte[] recordBytes) {
    }
}
