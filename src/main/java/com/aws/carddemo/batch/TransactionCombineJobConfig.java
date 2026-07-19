package com.aws.carddemo.batch;

import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;

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
 * as a single {@link Tasklet} that reads both inputs, merges them, performs the global sort with a
 * deterministic comparator, and writes the combined output. The subsequent IDCAMS {@code REPRO}
 * ({@code STEP10}, driven by {@code REPROC.prc} / {@code REPROCT.ctl}) is generic
 * load-the-combined-dataset plumbing; under Spring its faithful realization is simply the write of the
 * combined output file, which this job produces. That {@code REPRO} &rarr; file-write mapping is
 * recorded in the traceability matrix.</p>
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
 * <p><strong>Record framing.</strong> Inputs and the combined output use the ASCII fixed-width
 * representation of the AWS CardDemo datasets under {@code legacy/data/ASCII}: one 350-byte record per
 * line terminated by a line feed (a trailing line feed following the final record is written, matching
 * the dataset convention). Carriage-return/line-feed terminators are tolerated on read. An
 * <em>undelimited</em> input (a raw {@code RECFM=FB} image whose length is an exact multiple of 350
 * with no line feeds) is also accepted and split into contiguous fixed-length blocks. A 350-byte
 * transaction record never contains a raw line-feed byte, so the presence or absence of a line feed is
 * an unambiguous discriminator between the two representations.</p>
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
 * <p><strong>Approach chosen (recorded in the decision log).</strong> A file-based combine is used
 * because it is closest to {@code COMBTRAN}, whose inputs are the sequential backup and
 * system-transaction files rather than the live transaction table. The database alternative &mdash;
 * streaming {@link com.aws.carddemo.repository.TransactionRepository} {@code findAll} under a
 * {@code C}/{@code POSIX} column collation and re-serializing the 350-byte records &mdash; is
 * documented but rejected here, because it would sort the master table rather than the two staged
 * input files and could not reproduce the byte-exact input records verbatim.</p>
 *
 * <p><strong>Wiring note (AAP binding constraint):</strong> this class deliberately does
 * <strong>not</strong> use {@code @EnableBatchProcessing} and does not depend on
 * {@code config/BatchConfig}. It relies on Spring Boot's Batch auto-configuration, which supplies the
 * persistent, restartable {@link JobRepository} and the {@link PlatformTransactionManager} injected
 * through the constructor &mdash; the same convention as the sibling batch configurations.</p>
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

    /** Name of the {@code TRAN-ID} sort-key field within the {@code CVTRA05Y} layout (bytes 1-16). */
    private static final String TRAN_ID_FIELD = "TRAN-ID";

    /** The ASCII line-feed byte used to frame records on read and write. */
    private static final byte LINE_FEED = (byte) '\n';

    /** The ASCII carriage-return byte tolerated as part of a CR/LF terminator on read. */
    private static final byte CARRIAGE_RETURN = (byte) '\r';

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

    /** Auto-configured Spring Batch job repository used to build the step and the job. */
    private final JobRepository jobRepository;

    /** Auto-configured transaction manager bounding the tasklet's execution. */
    private final PlatformTransactionManager transactionManager;

    /**
     * Creates the configuration with the collaborators supplied by Spring Boot's Batch
     * auto-configuration.
     *
     * @param jobRepository      the auto-configured Spring Batch {@link JobRepository}
     * @param transactionManager the auto-configured {@link PlatformTransactionManager}
     */
    public TransactionCombineJobConfig(JobRepository jobRepository,
            PlatformTransactionManager transactionManager) {
        this.jobRepository = jobRepository;
        this.transactionManager = transactionManager;
    }

    /**
     * The single {@code @StepScope} tasklet reproducing {@code COMBTRAN}'s {@code STEP05R} sort and the
     * {@code STEP10} {@code REPRO} write. It reads the three dataset-path job parameters, delegates to
     * {@link #combineAndSort(Path, Path, Path)} to merge, globally sort by {@code TRAN-ID} and write the
     * combined output, and returns {@link RepeatStatus#FINISHED}. An I/O failure propagates and fails
     * the step, mirroring a legacy job abend on an I/O error.
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
            Path backupPath = Path.of(requireParam(backupInput, PARAM_BACKUP_INPUT));
            Path systemPath = Path.of(requireParam(systemInput, PARAM_SYSTEM_INPUT));
            Path outputPath = Path.of(requireParam(combinedOutput, PARAM_COMBINED_OUTPUT));
            combineAndSort(backupPath, systemPath, outputPath);
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
     * Merges the two transaction inputs, sorts the concatenation ascending by {@code TRAN-ID} using the
     * bytewise ({@code C}/{@code POSIX}) comparator, and writes the combined 350-byte records to the
     * output path. This is the behavioral core of the job, exposed at package visibility so it can be
     * unit-tested directly without launching a {@code JobLauncher}.
     *
     * <p>The backup input is read first and the system-transaction input second, preserving the
     * physical {@code SORTIN} concatenation order. The sort is <em>stable</em>
     * ({@link List#sort(Comparator)}), so should two records ever share a {@code TRAN-ID} (not expected,
     * as {@code TRAN-ID} is the unique {@code TRANSACT} key) the backup record precedes the
     * system-transaction record, matching the concatenation order. Every input record appears in the
     * output exactly once, so the written count equals the sum of the two input counts.</p>
     *
     * @param backupInput    the transaction backup input path (read first)
     * @param systemInput    the system-transaction input path (read second)
     * @param combinedOutput the combined, sorted output path (its parent directory is created if absent)
     * @return the number of combined records written (the sum of the two input record counts)
     * @throws IOException              if any input cannot be read or the output cannot be written
     * @throws IllegalArgumentException if any record's length is not exactly {@link #RECORD_LENGTH}
     *                                  bytes, or an undelimited input length is not a multiple of it
     */
    static long combineAndSort(Path backupInput, Path systemInput, Path combinedOutput)
            throws IOException {
        List<SortableRecord> records = new ArrayList<>();
        long backupCount = readRecords(backupInput, records);
        long systemCount = readRecords(systemInput, records);
        records.sort(BY_TRAN_ID_BYTEWISE);
        long written = writeRecords(combinedOutput, records);
        LOGGER.info("transactionCombineJob combined {} backup + {} system = {} records, "
                        + "sorted ascending by TRAN-ID (bytewise C/POSIX collation), into {}",
                backupCount, systemCount, written, combinedOutput);
        return written;
    }

    /**
     * Reads every fixed-width record from {@code input}, validating each is exactly
     * {@link #RECORD_LENGTH} bytes and capturing its raw {@code TRAN-ID} sort key, appending the results
     * to {@code into}.
     *
     * @param input the input file to read
     * @param into  the accumulator to append parsed records to
     * @return the number of records read from {@code input}
     * @throws IOException              if the file cannot be read
     * @throws IllegalArgumentException if a record's length is not exactly {@link #RECORD_LENGTH} bytes
     */
    private static long readRecords(Path input, List<SortableRecord> into) throws IOException {
        byte[] data = Files.readAllBytes(input);
        List<byte[]> rawRecords = splitRecords(data);
        for (byte[] recordBytes : rawRecords) {
            // parse(...) validates the exact 350-byte length; getRawBytes extracts the TRAN-ID key.
            byte[] tranIdKey = TRANSACTION_RECORD_MAPPER.parse(recordBytes).getRawBytes(TRAN_ID_FIELD);
            into.add(new SortableRecord(tranIdKey, recordBytes));
        }
        return rawRecords.size();
    }

    /**
     * Splits raw file bytes into individual fixed-width records.
     *
     * <p>If the data contains any line-feed byte it is treated as line-framed: records are the segments
     * between line feeds, with a trailing carriage return stripped (CR/LF tolerance) and empty segments
     * (such as the one after a trailing line feed) skipped. Otherwise the data is treated as an
     * undelimited {@code RECFM=FB} image and split into contiguous {@link #RECORD_LENGTH}-byte blocks;
     * its length must be an exact multiple of the record length.</p>
     *
     * @param data the raw file bytes
     * @return the ordered list of raw record byte arrays (each still to be length-validated on parse)
     * @throws IllegalArgumentException if an undelimited input length is not a multiple of
     *                                  {@link #RECORD_LENGTH}
     */
    static List<byte[]> splitRecords(byte[] data) {
        List<byte[]> records = new ArrayList<>();
        if (data.length == 0) {
            return records;
        }
        if (!containsLineFeed(data)) {
            if (data.length % RECORD_LENGTH != 0) {
                throw new IllegalArgumentException("undelimited input length " + data.length
                        + " is not a multiple of the " + RECORD_LENGTH + "-byte record length");
            }
            for (int offset = 0; offset < data.length; offset += RECORD_LENGTH) {
                records.add(Arrays.copyOfRange(data, offset, offset + RECORD_LENGTH));
            }
            return records;
        }
        int start = 0;
        for (int i = 0; i < data.length; i++) {
            if (data[i] == LINE_FEED) {
                addLine(records, data, start, i);
                start = i + 1;
            }
        }
        if (start < data.length) {
            addLine(records, data, start, data.length);
        }
        return records;
    }

    /**
     * Appends the byte range {@code [start, endExclusive)} of {@code data} to {@code records} as one
     * record, stripping a single trailing carriage return (CR/LF tolerance) and skipping an empty
     * range.
     *
     * @param records      the accumulator to append to
     * @param data         the source bytes
     * @param start        the inclusive start offset of the segment
     * @param endExclusive the exclusive end offset of the segment
     */
    private static void addLine(List<byte[]> records, byte[] data, int start, int endExclusive) {
        int end = endExclusive;
        if (end > start && data[end - 1] == CARRIAGE_RETURN) {
            end--;
        }
        if (end > start) {
            records.add(Arrays.copyOfRange(data, start, end));
        }
    }

    /**
     * Reports whether {@code data} contains any ASCII line-feed byte.
     *
     * @param data the bytes to scan
     * @return {@code true} if a line feed is present
     */
    private static boolean containsLineFeed(byte[] data) {
        for (byte b : data) {
            if (b == LINE_FEED) {
                return true;
            }
        }
        return false;
    }

    /**
     * Writes the combined records to {@code output}, one 350-byte record per line terminated by a line
     * feed (including a trailing line feed after the final record), creating the parent directory if it
     * does not exist. The original record bytes are written verbatim, preserving the record byte-for-byte.
     *
     * @param output  the output file path
     * @param records the ordered records to write
     * @return the number of records written
     * @throws IOException if the parent directory or the file cannot be created or written
     */
    private static long writeRecords(Path output, List<SortableRecord> records) throws IOException {
        Path parent = output.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        try (OutputStream out = new BufferedOutputStream(Files.newOutputStream(output))) {
            for (SortableRecord record : records) {
                out.write(record.recordBytes());
                out.write(LINE_FEED);
            }
        }
        return records.size();
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
     * A combined-input record paired with its precomputed raw {@code TRAN-ID} sort key.
     *
     * <p>The key is the 16 raw bytes of the {@code TRAN-ID} field, precomputed once so the global sort
     * compares keys without re-parsing. The full record bytes are preserved verbatim and written to the
     * combined output unchanged, guaranteeing byte-exact record fidelity.</p>
     *
     * @param tranIdKey   the raw 16-byte {@code TRAN-ID} sort key
     * @param recordBytes the full 350-byte record image, preserved verbatim
     */
    private record SortableRecord(byte[] tranIdKey, byte[] recordBytes) {
    }
}
