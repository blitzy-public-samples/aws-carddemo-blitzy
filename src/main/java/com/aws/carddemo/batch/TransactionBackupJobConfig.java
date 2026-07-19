package com.aws.carddemo.batch;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import org.springframework.batch.core.Job;
import org.springframework.batch.core.Step;
import org.springframework.batch.core.configuration.annotation.StepScope;
import org.springframework.batch.core.job.builder.JobBuilder;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.core.step.builder.StepBuilder;
import org.springframework.batch.item.data.RepositoryItemReader;
import org.springframework.batch.item.data.builder.RepositoryItemReaderBuilder;
import org.springframework.batch.item.file.FlatFileItemWriter;
import org.springframework.batch.item.file.builder.FlatFileItemWriterBuilder;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.FileSystemResource;
import org.springframework.data.domain.Sort;
import org.springframework.transaction.PlatformTransactionManager;

import com.aws.carddemo.domain.Transaction;
import com.aws.carddemo.repository.TransactionRepository;
import com.aws.carddemo.util.FixedWidthRecordMapper;
import com.aws.carddemo.util.FixedWidthRecordMapper.FieldDef;

/**
 * Spring Batch job configuration realizing the transaction-master backup/export performed by the
 * mainframe JCL job {@code TRANBKP.jcl}.
 *
 * <p><strong>Origin and traceability (AAP &sect;0.4.1, &sect;0.6.3, &sect;0.6.4; Explainability
 * rule):</strong> the authoritative source is {@code legacy/jcl/TRANBKP.jcl} (source branch
 * {@code app/jcl/TRANBKP.jcl}). That job runs the {@code REPROC} PROC, whose {@code IDCAMS REPRO}
 * copies the transaction master ({@code TRANSACT} VSAM KSDS) record-for-record to a sequential
 * backup dataset (a GDG generation, {@code DCB=(LRECL=350,RECFM=FB)}). This configuration reproduces
 * exactly that behavior: it streams every {@link Transaction} and writes each as a byte-exact
 * 350-byte {@code CVTRA05Y} {@code TRAN-RECORD} line, preserving the fixed-width layout.</p>
 *
 * <p><strong>{@code CBTRN01C} is superseded &mdash; reference only.</strong> The COBOL program
 * {@code legacy/cbl/CBTRN01C.cbl} ("validate the daily transaction file") is a legacy validation-only
 * pass that was superseded by {@code CBTRN02C} (the posting program, migrated as
 * {@code PostTransactionJobConfig}) and has <em>no</em> JCL runner in the repository. Its validation
 * logic is intentionally <strong>not</strong> reproduced here; it is subsumed by the posting job.
 * This class therefore realizes only the {@code TRANBKP.jcl} {@code REPRO} export. The rationale is
 * recorded in {@code docs/decision-log.md} per the Explainability rule.</p>
 *
 * <p><strong>Byte-level parity (AAP &sect;0.6.4).</strong> Each exported record is produced through
 * the shared {@link FixedWidthRecordMapper} using the exact {@code CVTRA05Y} field definitions
 * (13 data fields + a trailing 20-byte {@code FILLER}, summing to 350 bytes). Monetary values use
 * {@link BigDecimal} exclusively &mdash; never {@code float}/{@code double} &mdash; preserving the
 * COBOL {@code S9(09)V99} fixed-scale semantics. The writer encodes with the mapper's single-byte
 * {@link StandardCharsets#ISO_8859_1} charset so the emitted line is exactly 350 record bytes.</p>
 *
 * <p><strong>Layout single-sourcing (design note).</strong> {@link FixedWidthRecordMapper} is a
 * plain, immutable utility (not a Spring bean); by its own contract each batch job supplies its own
 * ordered field-definition list per layout. Accordingly the {@code CVTRA05Y} layout is built once
 * here as the immutable, thread-safe constant {@link #TRAN_RECORD_MAPPER}, and the
 * {@link Transaction}-to-record encoding is single-sourced through {@link #encodeRecord(Transaction)}.
 * This keeps the 350-byte contract in one place rather than injecting a nonexistent mapper bean.</p>
 *
 * <p><strong>Wiring note (AAP binding constraint).</strong> This class deliberately does
 * <strong>not</strong> use {@code @EnableBatchProcessing} and does not depend on
 * {@code config/BatchConfig}. It relies on Spring Boot's Batch auto-configuration, which supplies the
 * persistent, restartable {@link JobRepository} and the {@link PlatformTransactionManager} injected
 * through the constructor &mdash; the same convention used by the sibling batch configurations. The
 * export is strictly read-only: the transaction table is never mutated.</p>
 *
 * <p>This configuration is stateless and thread-safe: it holds only immutable collaborators, and the
 * {@code @StepScope} reader and writer resolve their per-execution state (and the output path job
 * parameter) lazily per step execution.</p>
 */
@Configuration
public class TransactionBackupJobConfig {

    /** Bean/registry name of the Spring Batch {@link Job} translating {@code TRANBKP.jcl}. */
    static final String JOB_NAME = "transactionBackupJob";

    /** Name of the single chunk-oriented step within {@link #JOB_NAME}. */
    static final String STEP_NAME = "transactionBackupStep";

    /** Registry name of the {@code @StepScope} paging reader. */
    static final String READER_NAME = "transactionBackupReader";

    /** Registry name of the {@code @StepScope} fixed-width writer. */
    static final String WRITER_NAME = "transactionBackupWriter";

    /**
     * Chunk size (commit interval and reader page size) for this read-only export.
     *
     * <p>Defined locally (not sourced from {@code config/BatchConfig}, which must not be imported per
     * the AAP binding constraint). The value mirrors the read-only "print/report" convention: each
     * record is exported independently with no accumulating running total, so a moderate commit
     * interval balances throughput against memory and transaction-log pressure without affecting the
     * observable output.</p>
     */
    private static final int CHUNK_SIZE = 100;

    // ------------------------------------------------------------------------------------------
    // CVTRA05Y TRAN-RECORD field names (350-byte layout; oracle: legacy/cpy/CVTRA05Y.cpy).
    // ------------------------------------------------------------------------------------------

    /** {@code TRAN-ID PIC X(16)}. */
    static final String F_TRAN_ID = "TRAN-ID";
    /** {@code TRAN-TYPE-CD PIC X(02)}. */
    static final String F_TRAN_TYPE_CD = "TRAN-TYPE-CD";
    /** {@code TRAN-CAT-CD PIC 9(04)}. */
    static final String F_TRAN_CAT_CD = "TRAN-CAT-CD";
    /** {@code TRAN-SOURCE PIC X(10)}. */
    static final String F_TRAN_SOURCE = "TRAN-SOURCE";
    /** {@code TRAN-DESC PIC X(100)}. */
    static final String F_TRAN_DESC = "TRAN-DESC";
    /** {@code TRAN-AMT PIC S9(09)V99}. */
    static final String F_TRAN_AMT = "TRAN-AMT";
    /** {@code TRAN-MERCHANT-ID PIC 9(09)}. */
    static final String F_TRAN_MERCHANT_ID = "TRAN-MERCHANT-ID";
    /** {@code TRAN-MERCHANT-NAME PIC X(50)}. */
    static final String F_TRAN_MERCHANT_NAME = "TRAN-MERCHANT-NAME";
    /** {@code TRAN-MERCHANT-CITY PIC X(50)}. */
    static final String F_TRAN_MERCHANT_CITY = "TRAN-MERCHANT-CITY";
    /** {@code TRAN-MERCHANT-ZIP PIC X(10)}. */
    static final String F_TRAN_MERCHANT_ZIP = "TRAN-MERCHANT-ZIP";
    /** {@code TRAN-CARD-NUM PIC X(16)}. */
    static final String F_TRAN_CARD_NUM = "TRAN-CARD-NUM";
    /** {@code TRAN-ORIG-TS PIC X(26)}. */
    static final String F_TRAN_ORIG_TS = "TRAN-ORIG-TS";
    /** {@code TRAN-PROC-TS PIC X(26)}. */
    static final String F_TRAN_PROC_TS = "TRAN-PROC-TS";

    /** Total fixed record length of the {@code CVTRA05Y} {@code TRAN-RECORD} (bytes). */
    static final int RECORD_LENGTH = 350;

    /**
     * Byte-exact serializer for the 350-byte {@code CVTRA05Y} {@code TRAN-RECORD} layout, built once
     * from the copybook field order (13 data fields followed by the trailing {@code FILLER PIC X(20)}
     * reserved pad). The mapper is immutable and thread-safe, so a single shared instance is safe.
     */
    static final FixedWidthRecordMapper TRAN_RECORD_MAPPER = new FixedWidthRecordMapper(List.of(
            FieldDef.text(F_TRAN_ID, 16),
            FieldDef.text(F_TRAN_TYPE_CD, 2),
            FieldDef.numeric(F_TRAN_CAT_CD, 4),
            FieldDef.text(F_TRAN_SOURCE, 10),
            FieldDef.text(F_TRAN_DESC, 100),
            FieldDef.signedDecimal(F_TRAN_AMT, 11, 2),
            FieldDef.numeric(F_TRAN_MERCHANT_ID, 9),
            FieldDef.text(F_TRAN_MERCHANT_NAME, 50),
            FieldDef.text(F_TRAN_MERCHANT_CITY, 50),
            FieldDef.text(F_TRAN_MERCHANT_ZIP, 10),
            FieldDef.text(F_TRAN_CARD_NUM, 16),
            FieldDef.text(F_TRAN_ORIG_TS, 26),
            FieldDef.text(F_TRAN_PROC_TS, 26),
            FieldDef.filler(20)), StandardCharsets.ISO_8859_1);

    /**
     * Formatter for the two 26-character timestamp fields ({@code TRAN-ORIG-TS} / {@code TRAN-PROC-TS}).
     *
     * <p>The pattern {@code yyyy-MM-dd HH:mm:ss.SSSSSS} matches the textual contract documented by the
     * {@link Transaction} entity (this file's source of the values) and renders exactly 26 characters
     * (10 date + 1 space + 8 time + 1 dot + 6 microsecond digits). It is locale-independent and, being
     * a {@link DateTimeFormatter}, immutable and thread-safe.</p>
     */
    private static final DateTimeFormatter TS_FORMATTER =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSSSSS");

    /** Auto-configured Spring Batch job repository used to build the step and the job. */
    private final JobRepository jobRepository;

    /** Auto-configured transaction manager bounding each chunk's commit. */
    private final PlatformTransactionManager transactionManager;

    /** Read-only source of transaction records for the export (the {@code TRANSACT} KSDS replacement). */
    private final TransactionRepository transactionRepository;

    /**
     * Creates the configuration with the collaborators supplied by Spring Boot's Batch
     * auto-configuration and component scanning.
     *
     * @param jobRepository         the auto-configured Spring Batch {@link JobRepository}
     * @param transactionManager    the auto-configured {@link PlatformTransactionManager}
     * @param transactionRepository the repository streamed as the backup source (read-only)
     */
    public TransactionBackupJobConfig(JobRepository jobRepository,
            PlatformTransactionManager transactionManager,
            TransactionRepository transactionRepository) {
        this.jobRepository = jobRepository;
        this.transactionManager = transactionManager;
        this.transactionRepository = transactionRepository;
    }

    /**
     * Paging reader that streams every transaction ascending by {@code tranId}, reproducing the VSAM
     * KSDS primary-key ({@code TRAN-ID}) read order that {@code REPRO} preserves (AAP &sect;0.6.6:
     * deterministic bytewise/{@code C}-collation ordering on the 16-character key). It delegates to
     * {@link TransactionRepository}'s inherited {@code findAll(Pageable)}; a {@code null} page result
     * (no further records) marks end-of-file, exactly as the COBOL sequential read loop terminates.
     *
     * <p>The reader is {@code @StepScope} so each step execution obtains a fresh, correctly reset
     * instance (supporting re-launches without shared cursor state); the export performs no writes to
     * the transaction table.</p>
     *
     * @return the {@code transactionBackupReader} paging {@link RepositoryItemReader}
     */
    @Bean
    @StepScope
    public RepositoryItemReader<Transaction> transactionBackupReader() {
        return new RepositoryItemReaderBuilder<Transaction>()
                .name(READER_NAME)
                .repository(transactionRepository)
                .methodName("findAll")
                .sorts(Map.of("tranId", Sort.Direction.ASC))
                .pageSize(CHUNK_SIZE)
                .build();
    }

    /**
     * Fixed-width writer that emits each transaction as a byte-exact 350-byte {@code CVTRA05Y} record.
     *
     * <p>The output resource path is supplied by the required {@code outputPath} job parameter (a GDG
     * generation on z/OS becomes a job-instance/timestamped path here). Each record is built via
     * {@link #encodeRecord(Transaction)} and the writer encodes it with the mapper's
     * {@link StandardCharsets#ISO_8859_1} charset so every emitted line is exactly 350 record bytes,
     * delimited by a single {@code '\n'}. Any pre-existing output file is replaced, matching the JCL
     * {@code DISP=(NEW,CATLG,DELETE)} allocation of a fresh backup generation each run.</p>
     *
     * <p>The bean is {@code @StepScope} so the {@code @Value} job-parameter expression is resolved
     * lazily per step execution; {@link #transactionBackupStep()} passes a {@code null} placeholder
     * that the scoped proxy replaces with the real value at run time.</p>
     *
     * @param outputPath the backup file path, bound from the required job parameter {@code outputPath}
     * @return the {@code transactionBackupWriter} {@link FlatFileItemWriter}
     */
    @Bean
    @StepScope
    public FlatFileItemWriter<Transaction> transactionBackupWriter(
            @Value("#{jobParameters['outputPath']}") String outputPath) {
        Objects.requireNonNull(outputPath, "job parameter 'outputPath' is required");
        return new FlatFileItemWriterBuilder<Transaction>()
                .name(WRITER_NAME)
                .resource(new FileSystemResource(outputPath))
                .lineAggregator(TransactionBackupJobConfig::encodeRecord)
                .encoding(TRAN_RECORD_MAPPER.getCharset().name())
                .lineSeparator("\n")
                .shouldDeleteIfExists(true)
                .build();
    }

    /**
     * The single chunk-oriented step of {@link #transactionBackupJob()}: read a page of transactions,
     * write each as a 350-byte record, and commit per chunk within the auto-configured transaction
     * boundary. There is no processor &mdash; this is a straight record-for-record export &mdash; so
     * the chunk item type is {@code <Transaction, Transaction>}. The {@code null} writer argument is a
     * placeholder resolved by the {@code @StepScope} proxy at run time.
     *
     * @return the {@code transactionBackupStep} {@link Step}
     */
    @Bean
    public Step transactionBackupStep() {
        return new StepBuilder(STEP_NAME, jobRepository)
                .<Transaction, Transaction>chunk(CHUNK_SIZE, transactionManager)
                .reader(transactionBackupReader())
                .writer(transactionBackupWriter(null))
                .build();
    }

    /**
     * The Spring Batch {@link Job} corresponding to legacy JCL job {@code TRANBKP.jcl}. It consists of
     * the single {@link #transactionBackupStep()} and completes with batch status {@code COMPLETED}
     * once every transaction has been exported.
     *
     * @return the {@code transactionBackupJob} {@link Job}
     */
    @Bean
    public Job transactionBackupJob() {
        return new JobBuilder(JOB_NAME, jobRepository)
                .start(transactionBackupStep())
                .build();
    }

    /**
     * Encodes a single {@link Transaction} as a byte-exact 350-byte {@code CVTRA05Y} {@code TRAN-RECORD}
     * line, returned as a string decoded with the mapper's single-byte {@link StandardCharsets#ISO_8859_1}
     * charset (so re-encoding by the writer with the same charset yields exactly 350 bytes).
     *
     * <p>Field handling mirrors the copybook picture clauses and COBOL initialization semantics:</p>
     * <ul>
     *   <li>{@code PIC X(n)} text fields are left-justified and right-padded with spaces; a
     *       {@code null} value is written as spaces.</li>
     *   <li>{@code PIC 9(n)} numeric fields ({@code TRAN-CAT-CD}, {@code TRAN-MERCHANT-ID}) are
     *       right-justified and zero-padded; a {@code null} value defaults to zero.</li>
     *   <li>{@code TRAN-AMT PIC S9(09)V99} is written as an 11-digit, scale-2 signed decimal with a
     *       trailing overpunch sign, from a {@link BigDecimal} ({@code null} defaults to zero); no
     *       floating-point type is ever used.</li>
     *   <li>The two {@code PIC X(26)} timestamps are rendered with {@link #TS_FORMATTER}; a
     *       {@code null} value is written as spaces.</li>
     *   <li>The trailing {@code FILLER PIC X(20)} is not carried on the entity and defaults to spaces
     *       (COBOL alphanumeric initialization), rounding the record out to 350 bytes.</li>
     * </ul>
     *
     * <p>Used as the writer's {@code LineAggregator}; it never mutates the entity.</p>
     *
     * @param transaction the transaction to serialize; must not be {@code null}
     * @return the 350-character record string (ISO-8859-1) for the fixed-width writer
     * @throws NullPointerException     if {@code transaction} is {@code null}
     * @throws IllegalArgumentException if any value overflows its fixed-width field (never truncated)
     */
    static String encodeRecord(Transaction transaction) {
        Objects.requireNonNull(transaction, "transaction must not be null");
        byte[] record = TRAN_RECORD_MAPPER.newRecord()
                .setText(F_TRAN_ID, transaction.getTranId())
                .setText(F_TRAN_TYPE_CD, transaction.getTranTypeCd())
                .setNumeric(F_TRAN_CAT_CD, transaction.getTranCatCd() != null ? transaction.getTranCatCd() : 0)
                .setText(F_TRAN_SOURCE, transaction.getTranSource())
                .setText(F_TRAN_DESC, transaction.getTranDesc())
                .setSignedDecimal(F_TRAN_AMT,
                        transaction.getTranAmt() != null ? transaction.getTranAmt() : BigDecimal.ZERO)
                .setNumeric(F_TRAN_MERCHANT_ID,
                        transaction.getMerchantId() != null ? transaction.getMerchantId() : 0L)
                .setText(F_TRAN_MERCHANT_NAME, transaction.getMerchantName())
                .setText(F_TRAN_MERCHANT_CITY, transaction.getMerchantCity())
                .setText(F_TRAN_MERCHANT_ZIP, transaction.getMerchantZip())
                .setText(F_TRAN_CARD_NUM, transaction.getCardNum())
                .setText(F_TRAN_ORIG_TS, formatTimestamp(transaction.getOrigTs()))
                .setText(F_TRAN_PROC_TS, formatTimestamp(transaction.getProcTs()))
                .build();
        return new String(record, TRAN_RECORD_MAPPER.getCharset());
    }

    /**
     * Renders a timestamp field to its fixed 26-character text form, or {@code null} when the value is
     * absent (the caller writes {@code null} as 26 spaces, matching an uninitialized COBOL field).
     *
     * @param timestamp the timestamp to format, or {@code null}
     * @return the 26-character formatted timestamp, or {@code null} if {@code timestamp} is {@code null}
     */
    private static String formatTimestamp(LocalDateTime timestamp) {
        return timestamp != null ? TS_FORMATTER.format(timestamp) : null;
    }
}
